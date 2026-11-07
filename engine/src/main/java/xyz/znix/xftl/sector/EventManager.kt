package xyz.znix.xftl.sector

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.Translator
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsEventFiles
import xyz.znix.xftl.hs.events.HsEventParser
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.shipgen.EnemyShipSpec

class EventManager(
    val df: Datafile,
    private val translator: Translator,
    private val bp: BlueprintManager,
    eventFileNames: List<String>
) {
    // EventGenerator keeps named events and event lists in separate maps
    // (+0x60 and +0x44). CreateEvent (0x5dce90) resolves a name to its list
    // first, so an event that shares a list's name is never produced.
    private val events = HashMap<String, IEvent>()
    private val eventLists = HashMap<String, EventList>()
    private val textLists = HashMap<String, TextList>()

    /** The `planet`/`back` image lists of the texts that name them, by text id (for texts rebuilt from a save). */
    val textImages = HashMap<String, Pair<String?, String?>>()

    /**
     * Unique events created this session. CreateEvent moves a unique event
     * out of the base-event map the first time it creates one (outside the
     * ignore-unique mode) and nothing moves it back, so it can't be produced
     * again until the game restarts.
     */
    private val consumedUniques = LinkedHashSet<Event>() // ordered: saves are byte-stable (WP6 save)
    val uniqueEvents: UniqueEventRegistry = object : UniqueEventRegistry {
        override fun isConsumed(event: Event): Boolean = consumedUniques.contains(event)
        override fun consume(event: Event) {
            consumedUniques.add(event)
        }

        override fun restoreAll() {
            consumedUniques.clear()
        }
    }

    /** HS: EventGenerator::ClearUsedEvent, a created unique event that was not used is available again. */
    fun hsReturnUnique(event: Event) {
        consumedUniques.remove(event)
    }

    /**
     * HS's LOADEVENT (CommandConsole.cpp:279-302): the event is loaded with the used unique events back in the base map,
     * and afterwards the base map, the used events and the text lists are put back as they were: the call leaves no
     * unique event used and no text taken out of its list. (Its insert of the used text lists adds nothing, every list
     * is still in the map.)
     */
    fun <T> withoutLastingUse(block: () -> T): T {
        val uniques = ArrayList(consumedUniques)
        val texts = textLists.mapValues { it.value.stateToString() }
        consumedUniques.clear()
        try {
            return block()
        } finally {
            consumedUniques.clear()
            consumedUniques.addAll(uniques)
            for ((name, state) in texts) textLists[name]?.loadState(state)
        }
    }

    /**
     * EventGenerator 0x5db020 (every map generation): the used texts come
     * back to the end of their lists and the unique events can be created
     * again.
     */
    fun restoreEventState() {
        uniqueEvents.restoreAll()
        for (list in textLists.values)
            list.restoreUsed()
    }

    /**
     * The state of a freshly started game: no unique event consumed and
     * every text list in its file order (the original keeps both for the
     * life of the process, so a run's events depend on what ran before it;
     * a new run here starts from the state of a fresh process).
     */
    fun resetEventState() {
        crewKillSeed = null
        uniqueEvents.restoreAll()
        for (list in textLists.values)
            list.loadState(null)
    }

    /**
     * WorldManager +0x12a0: the seed an event's crew kill is picked with. It is drawn from the global stream the
     * first time it is needed (`random32()`), shared by every preview and the application of the event, and
     * cleared where a new event sequence starts (see DialogueWindow). Saved with the game (0x61a3a0).
     */
    private var crewKillSeed: Int? = null

    fun crewKillSeed(): Int {
        crewKillSeed?.let { return it }
        val seed = ftl.browser.NativeRandom.next31()
        crewKillSeed = seed
        return seed
    }

    /** Diagnostics: whether the current event drew its crew-kill seed already. */
    val hasCrewKillSeed: Boolean get() = crewKillSeed != null

    fun resetCrewKillSeed() {
        crewKillSeed = null
    }

    /** Save support for the state [restoreEventState] resets: consumed uniques and text list orders. */
    fun saveEventState(root: Element) {
        crewKillSeed?.let { root.setAttribute("crewKillSeed", it.toString()) }
        for (event in consumedUniques)
            root.addContent(Element("consumedUnique").setAttribute("id", event.deserialisationId))
        for ((name, list) in textLists) {
            val state = list.stateToString() ?: continue
            root.addContent(Element("textListState").setAttribute("name", name).setAttribute("state", state))
        }
    }

    fun loadEventState(root: Element) {
        resetEventState()
        crewKillSeed = root.getAttributeValue("crewKillSeed")?.toInt()
        for (elem in root.getChildren("consumedUnique"))
            getByDeserialisationIdOrNull(elem.getAttributeValue("id"))?.let { consumedUniques.add(it) }
        for (elem in root.getChildren("textListState"))
            textLists[elem.getAttributeValue("name")]?.loadState(elem.getAttributeValue("state"))
    }
    private val imageLists = HashMap<String, ImageList>()
    private val ships = HashMap<String, EnemyShipSpec>()

    private val byDeserialisationId = HashMap<String, Event>()
    private val byChoiceId = HashMap<String, Choice>()

    // For the debug console
    val eventNames: Collection<String> get() = events.keys + eventLists.keys

    /**
     * Hyperspace's CustomEventsParser for this content, null in vanilla (HS.active is false, nothing below runs).
     * Every event made here also gets its HS name ([Event.hsName], EventsParser::ProcessEvent's naming).
     */
    val hs: HsEventParser? = if (HS.active) HsEventParser(::hsProcessEvent, ::hsText) else null

    /** HS: every event under its native name, generated ones ("PARENT 12") included, as GetBaseEvent finds them. */
    private val hsEventsByName = HashMap<String, Event>()
    private var hsEventCounter = 0
    /** [keepsEarlierFile]: names defined by the files loaded so far, and by the one loading now. */
    private val definedInEarlierFile = HashSet<String>()
    private val definedInThisFile = HashSet<String>()
    private val shipsInEarlierFile = HashSet<String>()
    private val shipsInThisFile = HashSet<String>()
    private var hsCurrentFile = ""

    init {
        imageLists[ImageList.NONE.name] = ImageList.NONE

        val hyperspaceRoot = if (hs != null) df.getOrNull("data/hyperspace.xml")?.let { df.parseXML(it).rootElement } else null
        val files = if (hs != null) HsEventFiles.beforeVanilla(hs, hyperspaceRoot, df) + eventFileNames else eventFileNames

        for (event in files) {
            loadEvents(df.parseXML(df[event]), true)
        }
        hs?.processPendingLoadLists()
        for (event in files) {
            hsCurrentFile = event
            loadEvents(df.parseXML(df[event]), false)
        }
        if (hs != null) HsEventFiles.afterEvents(hs, hyperspaceRoot, bp)

        // With the DLC enabled CreateEvent tries "OVERRIDE_<name>" in the list
        // map before "<name>"; every override in the DAT is a list.
        if (ftl.browser.BrowserFiles.enableAE) {
            for ((name, list) in eventLists.toMap()) if (name.startsWith("OVERRIDE_")) eventLists[name.removePrefix("OVERRIDE_")] = list
        }

        // Make sure all the referenced ships do exist
        if (hs != null) {
            // HS content: GetShipEvent gives an empty ship for an unknown name; report instead of failing.
            for (ev in hsEventsByName.values)
                if (ev.loadShipName != null && !ships.containsKey(ev.loadShipName))
                    HsEventFiles.missing("ship", ev.hsName ?: "", ev.loadShipName)
        } else for (ev in events.values) {
            if (ev is Event) {
                xyz.znix.xftl.hs.HsLoad.item("events", { "ship reference ${ev.loadShipName}" }) { check(ev.loadShipName == null || ships.containsKey(ev.loadShipName)) }
            }
        }
        if (hs == null) for (list in eventLists.values) {
            // Load the lazy-loadable events list, to make sure we're not going
            // to crash at runtime because one an event doesn't exist.
            xyz.znix.xftl.hs.HsLoad.item("events", { "eventList contents" }) { list.events.toString() }
        }
    }

    /**
     * Modded sessions: the event of a beacon that a sector entry left without one. FTL's StarMap::GenerateEvents keeps
     * that location's LocationEvent nullptr when CreateEvent finds only consumed unique events (MV's
     * DROPPOINT_SUPPLY_DEPOT); Hyperspace guards it everywhere (WorldManager::CreateLocation returns early, the map
     * draws the beacon shadowed, RenderLabels skips it). An event without text, choices or rewards is our nullptr: made
     * without random draws, its dialogue closes at once. Registered for saves; not an HS event name.
     */
    // Ported from FTL-Hyperspace 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (the nullptr guards)
    val hsNoEvent: Event? = if (HS.modded)
        Event(null, emptyList(), Element("event"), "hsNoEvent", "hsNoEvent", ::getImageListOrNull, ::loadText, uniqueEvents)
            .also { byDeserialisationId[it.deserialisationId] = it }
    else null

    /** An event with nothing in it, for what a save names that these game files no longer have. */
    private val emptyEvent: Event by lazy {
        hsNoEvent ?: Event(null, emptyList(), Element("event"), "hsNoEvent", "hsNoEvent", ::getImageListOrNull, ::loadText, uniqueEvents)
            .also { byDeserialisationId[it.deserialisationId] = it }
    }

    /** The event a save names: if its mod left the save's list the run goes on with an empty event there, and the page says so. */
    fun getSavedEvent(id: String): Event = byDeserialisationId[id] ?: run {
        if (id != "hsNoEvent") xyz.znix.xftl.savegame.MissingContent.note(id)
        emptyEvent
    }

    /** The choice a save names, or nothing if it is gone. */
    fun getSavedChoice(id: String): Choice? = byChoiceId[id] ?: run {
        xyz.znix.xftl.savegame.MissingContent.note(id)
        null
    }

    /**
     * Load the standard vanilla event files.
     */
    constructor(df: Datafile, translator: Translator, bp: BlueprintManager) :
            this(df, translator, bp, FILE_NAMES.filter { !it.startsWith("dlc") || ftl.browser.BrowserFiles.enableAE }.map { "data/$it.xml" })

    operator fun get(name: String): IEvent = getOrNull(name) ?: error("Missing event $name")

    /** A list shadows an event of the same name, as in CreateEvent. HS also finds the generated names. */
    fun getOrNull(name: String): IEvent? = eventLists[name] ?: events[name] ?: if (hs != null) hsEventsByName[name] else null

    /** The text list with this name (for tools that name one). */
    fun getTextList(name: String): TextList? = textLists[name]

    fun getByDeserialisationId(id: String): Event {
        return byDeserialisationId[id] ?: error("Missing event with deserialisation ID '$id'")
    }

    /** Like [getByDeserialisationId], but null for unknown ids. Used by the
     * vanilla save loader, which can encounter events from mods that aren't
     * installed. */
    fun getByDeserialisationIdOrNull(id: String): Event? = byDeserialisationId[id]

    fun getChoiceByDeserialisationId(id: String): Choice {
        return byChoiceId[id] ?: error("Missing choice with deserialisation ID '$id'")
    }

    fun getShip(name: String): EnemyShipSpec = ships[name] ?: error("Missing enemy ship spec '$name'")
    fun getImageList(name: String): ImageList = imageLists[name] ?: error("Missing image list '$name'")
    fun getImageListOrNull(name: String): ImageList? = imageLists[name]

    fun hasImageList(name: String): Boolean = imageLists.containsKey(name)

    fun hasShip(name: String): Boolean = ships.containsKey(name)
    fun getShips(): Collection<EnemyShipSpec> = ships.values

    /**
     * EventsParser::AddEvents (0x5e9760): a file's events and ship events go to the parser's own maps (a repeated name
     * in the same file replaces the earlier one, 0x5e52c0), which are then range-inserted into the generator's
     * (0x84b820, 0x849e20): a name an earlier file defined keeps that file's definition. Lists, text lists and sector
     * descriptions are assigned directly (the last one wins). Vanilla's files define no name twice across files;
     * the rule is applied for Hyperspace sessions (MV redefines vanilla events in its own files, which load first).
     */
    // (the name sets are declared before init, which loads the files)

    /** Whether a definition of [name] from the file being loaded may replace the one in [map]. */
    private fun keepsEarlierFile(name: String, earlier: Set<String>, thisFile: MutableSet<String>): Boolean {
        if (hs == null) return false
        if (name in earlier && name !in thisFile) return true
        thisFile += name
        return false
    }

    /** Diagnostics: the nodes of one more events document (an oracle probe's authored append), both passes. */
    fun diagnosticAppend(doc: Document) { loadEvents(doc, true); loadEvents(doc, false) }

    private fun loadEvents(doc: Document, resourcePass: Boolean) {
        val root = doc.rootElement
        check(root.name == "FTL")
        if (!resourcePass) {
            definedInEarlierFile += definedInThisFile; definedInThisFile.clear()
            shipsInEarlierFile += shipsInThisFile; shipsInThisFile.clear()
        }

        for (elem in root.children) {
            if (hs != null) {
                // HS content: the original's parser never stops on a bad node; one that our loader can't read is
                // recorded (capability report) and skipped instead of failing the whole file.
                try {
                    loadRootNode(elem, resourcePass)
                } catch (e: RuntimeException) {
                    HsEventFiles.badNode(hsCurrentFile, elem, e)
                }
                continue
            }
            loadRootNode(elem, resourcePass)
        }
    }

    private fun loadRootNode(elem: Element, resourcePass: Boolean) {
        run {
            // Everything is loaded in two passes: once for the textlists/images (later is not yet implemented), and
            // a second time to load the events (which reference the resources).
            if (resourcePass) {
                xyz.znix.xftl.hs.HsLoad.item("events", { "${elem.name} ${elem.getAttributeValue("name")}" }) { when (elem.name) {
                    "textList" -> loadTextList(elem)
                    "imageList" -> loadImageList(elem)
                } }
            } else {
                // EventsParser::ProcessBaseNode (0x5e8510): a root node without a name is called "Base Event: <n>"
                // from the event counter before its type is looked at (newEvents.xml's <eventCounts> take four).
                if (hs != null && elem.getAttributeValue("name") == null) ++hsEventCounter
                xyz.znix.xftl.hs.HsLoad.item("events", { "${elem.name} ${elem.getAttributeValue("name")}" }) { when (elem.name) {
                    "textList", "imageList" -> Unit // Handled in the resource pass
                    "eventList" -> loadEventList(elem)
                    "ship" -> loadShip(elem)
                    "eventCounts" -> loadEventCounts(elem)
                    "event" -> {
                        val name = elem.requireAttributeValue("name")
                        val serialId = "rootEvent::$name"
                        eventCheck(name, events)
                        val keep = keepsEarlierFile(name, definedInEarlierFile, definedInThisFile)
                        val loaded = loadEvent(elem, name, serialId, hsParent = name).value
                        if (!keep) events[name] = loaded
                    }

                    // EventsParser::ProcessBaseNode skips nodes it doesn't know; HS files carry some.
                    else -> if (hs != null) HsEventFiles.unknownRootNode(hsCurrentFile, elem)
                        else if (!xyz.znix.xftl.hs.HsLoad.skipUnknown("events", elem.name)) error("Unknown eventfile item ${elem.name}")
                } }
            }
        }
    }

    private fun loadEventList(elem: Element) {
        check(elem.name == "eventList")
        val name = elem.requireAttributeValue("name")
        eventCheck(name, eventLists)

        // EventsParser::ProcessEventList (1.6.14 0x5e7d10, 1.6.9 0x5c3af0) walks first_node("event") /
        // next_sibling("event"): other children are not entries. A list a mod wrote with only other children (Alder's
        // Additions 2.1.1 AA_SHIP_MVBOSS_HACKER_CRUISER_DESTROYED_CHAOS, an event typed as a list) would be an empty
        // list, which GetEvent divides by (0x5dce90: random % size); it stays unregistered (an empty event where it is
        // loaded, as before), recorded for the capability report.
        val entries = elem.getChildren("event")
        if (entries.isEmpty() && elem.children.isNotEmpty() && HS.modded) {
            HS.unsupported("parse", "events", "eventList $name has no <event> entries (the original divides by zero when it is used)")
            return
        }
        val events = entries.withIndex().map {
            val serialId = "$name::eventList${it.index}"
            loadEvent(it.value, "$name.${it.index}", serialId, nested = true, hsParent = name)
        }
        eventLists[name] = EventList(name, events)
        // EventsParser::ProcessEventList hook: the list node's own HS tags, under the list's name.
        hs?.parseVanillaEventNode(elem, name, name)
    }

    private fun eventCheck(name: String, map: Map<String, IEvent>) {
        // The parser (0x5e52c0) reports a repeated name, then the later
        // definition replaces the earlier one. An event and a list with the
        // same name (NEBULA_PIRATE, NEBULA_REBEL, BOARDERS_PIRATE) live in
        // separate maps and don't collide.
        if (map.containsKey(name))
            println("Found duplicate event $name")
    }

    private fun loadTextList(elem: Element) {
        check(elem.name == "textList")
        val name = elem.requireAttributeValue("name")
        // A `<text load="LIST"/>` entry is replaced at load time by the entries of that list (0x5e2e50), so one pick
        // from the outer list is one draw over all of them; a list that isn't loaded yet adds nothing.
        val texts = ArrayList<IEventText>()
        for (child in elem.children) {
            val load = child.getAttributeValue("load")
            if (child.name == "text" && load != null) {
                val loaded = textLists[load]
                if (loaded == null)
                    println("WARNING: didn't find text list $load")
                else
                    texts.addAll(loaded.items)
                continue
            }
            texts.add(loadText(child))
        }
        textLists[name] = TextList(name, texts)
    }

    private fun loadEventCounts(elem: Element) {
        check(elem.name == "eventCounts")

        // It appears this element is unused. It does not appear in the game binary. It looks
        // like it used to hold the the likelihood of events per sector (1..7), but that's now
        // stored in the sector type definitions (so a sector 4 nebula will have different contents
        // to an engi-controlled sector 4)
    }

    private fun loadShip(elem: Element) {
        check(elem.name == "ship")

        val ship = EnemyShipSpec(elem, bp, this)
        if (!keepsEarlierFile(ship.name, shipsInEarlierFile, shipsInThisFile)) ships[ship.name] = ship
        // EventsParser::ProcessShipEvent hook: named ship events keep their HS tags.
        hs?.parseVanillaShipEventNode(elem, ship.name)
    }

    fun loadEmbeddedEvent(elem: Element, uniqueId: String): Lazy<IEvent> {
        val deserialisationId = "embeddedEvent::$uniqueId::${elem.name}"
        // EventsParser::ProcessShipEvent (0x5e3c10) names a ship's unnamed events "SHIP surrender  n" etc.: the
        // ship's name, the event's label between spaces, then ProcessEvent's own space and counter.
        val label = when (elem.name) { "deadCrew" -> "dead crew"; "gotaway" -> "got away"; else -> elem.name }
        return loadEvent(elem, "$uniqueId.${elem.name}", deserialisationId, true, hsParent = "$uniqueId $label ")
    }

    /** HS EventsParser::ProcessEvent for an inline event (a load list entry): its HS name, findable by it. */
    private fun hsProcessEvent(elem: Element, parent: String): String {
        val load = elem.getAttributeValue("load")
        if (load != null) return load
        val event = loadEvent(elem, "hsInline.$parent", "hsInline::$parent", nested = true, hsParent = parent).value
        return (event as? Event)?.hsName ?: parent
    }

    /** TextLibrary::GetText, null for a missing id (HS keeps the attribute text itself then). */
    private fun hsText(id: String): String? = translator.translations[id]

    private fun loadEvent(
        elem: Element, debugId: String, serialId: String, embed: Boolean = false, nested: Boolean = false,
        hsParent: String? = null
    ): Lazy<IEvent> {
        if (!embed)
            check(elem.name == "event")

        elem.getAttributeValue("load")?.let {
            return lazy {
                // Vanilla data has no such thing. A mod's goes on with nothing there.
                getOrNull(it) ?: if (HS.modded) emptyEvent else error("Missing event '$it', referenced from '$debugId'")
            }
        }

        // The event parser 0x5e52c0 registers every event under its name
        // attribute (replacing an earlier one) and hands the parent only that
        // name, which CreateEvent resolves later with lists first. So a
        // nested `<event name="X"/>` is a reference to X (the list X when
        // there is one), as FUELING_STATION_BLOW in a fuel station choice.
        val nestedName = if (nested) elem.getAttributeValue("name") else null

        // Build a mapping of the events by their deserialisation ID, which
        // we'll use to pick out events when the game is being loaded.
        // Mods can overwrite events, so we may need to add a number to make
        // this name unique.
        var uniqueId = serialId
        var suffix = 0
        while (byDeserialisationId.containsKey(uniqueId)) {
            uniqueId = "$serialId-$suffix"
            suffix++
        }

        // EventsParser::ProcessEvent names the event before its children: its `name`, or the parent's name, a
        // space and the next value of the global counter (0x919c64), so a choice's event is "PARENT 12".
        val hsName = if (hs != null) elem.getAttributeValue("name") ?: "${hsParent ?: ""} ${++hsEventCounter}" else null

        // The same name in vanilla as far as a rule reads it: CheckRequirements' REPAIR_STATION test reads only its start,
        // the event's own name or the parent's (the number after it is left out here).
        val nativeName = hsName ?: elem.getAttributeValue("name") ?: hsParent?.let { "$it " }

        val text = elem.getChild("text")?.let(::loadText)
        val choices = if (hs == null) elem.getChildren("choice").withIndex().mapNotNull { loadChoice(it.value, it.index, uniqueId, nativeName?.trimEnd()) }
        else hsChoices(elem, uniqueId, hsName)
        val event = Event(text, choices, elem, debugId, uniqueId, ::getImageListOrNull, ::loadText, uniqueEvents)
        event.nativeName = nativeName

        byDeserialisationId[uniqueId] = event
        if (hsName != null) {
            event.hsName = hsName
            // GetBaseEvent's view: a name an earlier file defined keeps that event ([keepsEarlierFile]), and its
            // Hyperspace tags with it (oracle hg-fed: MV's BOSS_TEXT_1 keeps its custom fleet over events_boss.xml's).
            if (hsName !in definedInEarlierFile || hsName in definedInThisFile) {
                hsEventsByName[hsName] = event
                hs!!.parseVanillaEventNode(elem, hsName, HsEventParser.baseEventName(hsName))
            }
        }

        if (nestedName != null) {
            eventCheck(nestedName, events)
            if (!keepsEarlierFile(nestedName, definedInEarlierFile, definedInThisFile)) events[nestedName] = event
            return lazy { getOrNull(nestedName) ?: event }
        }

        return lazyOf(event)
    }

    /**
     * HS sessions: the choices in document order with ProcessEvent's other numbered child, an inline `<ship>` without
     * `load` ([hsInlineShip]), at its place among them.
     */
    private fun hsChoices(elem: Element, uniqueId: String, hsName: String?): List<Choice> {
        val out = ArrayList<Choice>()
        var index = 0
        for (child in elem.children) when (child.name) {
            "choice" -> loadChoice(child, index++, uniqueId, hsName)?.let { out += it }
            "ship" -> if (child.getAttribute("load") == null) hsInlineShip(child, uniqueId)
        }
        return out
    }

    /**
     * EventsParser::ProcessShipEvent (0x5e3c10) for an inline `<ship>` without `load`: its name (or the next counter
     * value), then its surrender, escape, deadCrew, destroyed and gotaway events through ProcessEvent with the parent
     * "SHIP label " - their counter values and HS names. The engine does not use inline ship definitions otherwise.
     */
    private fun hsInlineShip(ship: Element, uniqueId: String) {
        val name = ship.getAttributeValue("name") ?: (++hsEventCounter).toString()
        for (tag in listOf("surrender", "escape", "deadCrew", "destroyed", "gotaway")) {
            val elem = ship.getChild(tag) ?: continue
            val label = when (tag) { "deadCrew" -> "dead crew"; "gotaway" -> "got away"; else -> tag }
            loadEvent(elem, "$uniqueId.inlineShip.$tag", "inlineShip::$uniqueId::$tag", true, hsParent = "$name $label ")
        }
    }

    private fun loadChoice(elem: Element, choiceIndex: Int, parentDeserialisationId: String, hsParent: String?): Choice? {
        check(elem.name == "choice")

        // Apparently if the event element is missing it crashes in vanilla,
        // but that only happens when the event is loaded.
        // Thus mods (notably Multiverse 5.3) contain unused events without
        // an event node in one of their choices.
        val eventElem = elem.getChild("event")
        if (eventElem == null) {
            println("[WARN] Missing <event> tag in choice with parent deserialisation ID '$parentDeserialisationId'")
            return null
        }

        val text = elem.getChild("text").let(::loadText)
        val deserialisationId = "$parentDeserialisationId::choice$choiceIndex"
        val event = loadEvent(eventElem, "choice.ukn", deserialisationId, nested = true, hsParent = hsParent)

        // We could use the same deserialisation ID for a choice and it's event,
        // but it's probably a bit nicer not to - if you're not that familiar
        // with the serialisation system, having a different name would let you
        // tell apart choices and their events.
        val choiceId = "choiceOf::$deserialisationId"
        val choice = Choice(text, event, elem, choiceId)

        if (byChoiceId.containsKey(choiceId)) {
            error("Choice deserialisation ID '$choiceId' is not unique!")
        }
        byChoiceId[choiceId] = choice

        return choice
    }

    private fun loadText(elem: Element): IEventText {
        check(elem.name == "text")
        elem.getAttributeValue("load")?.let {
            return textLists[it] ?: error("Missing textList $it")
        }
        val planet = elem.getAttributeValue("planet")
        val back = elem.getAttributeValue("back")
        elem.getAttributeValue("id")?.let {
            if (planet != null || back != null)
                textImages[it] = Pair(planet, back)
            return EventText(translator[it], it, planet, back)
        }
        // The CRYSTAL_HELP_DIG event has an empty string for
        // the crew clone message (since that is never triggered).
        return EventText(keepSpaces(elem), null, planet, back)
    }

    /**
     * The text as the game reads it: a run of spaces at either end stays (FTL does not trim an event's text), while
     * indentation that sits on its own line around the text (a line break in it) is not part of the text.
     */
    private fun keepSpaces(elem: Element): String {
        val raw = elem.text
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val start = raw.indexOf(trimmed)
        val lead = raw.substring(0, start)
        val trail = raw.substring(start + trimmed.length)
        return (if (lead.all { it == ' ' }) lead else "") + trimmed + (if (trail.all { it == ' ' }) trail else "")
    }

    private fun loadImageList(elem: Element) {
        if (elem.getAttributeValue("ui") == "ipad") return

        val name = elem.requireAttributeValue("name")
        val images = ArrayList<EnvironmentImage>()
        for (child in elem.children) {
            check(child.name == "img")
            val path = "img/" + child.textTrim
            if (df.getOrNull(path) == null) {
                println("[WARN] Non-existent image '$path' specified in image list '$name'")
                continue
            }
            // The w and h attributes are not read: the original keeps only the path of each img (EventsParser::ProcessImageList,
            // 0x5e2b00) and a planet or backdrop is as large as its image (SpaceManager::SwitchPlanet, 0x418280).
            images += EnvironmentImage(path)
        }
        imageLists[name] = ImageList(name, images)
    }

    companion object {
        // The order of the events loader (0x5e9f20); a later definition
        // replaces an earlier one. nameEvents.xml only holds unreferenced
        // test events and the original never loads it.
        private val FILE_NAMES = listOf(
            "events",
            "dlcEvents",
            "dlcEvents_anaerobic",
            "newEvents",
            "events_rebel",
            "events_mantis",
            "events_engi",
            "events_pirate",
            "events_fuel",
            "events_nebula",
            "events_ships",
            "events_imageList",
            "events_crystal",
            "events_boss",
            "events_zoltan",
            "events_slug",
            "events_rock",
            "dlcEventsOverwrite"
        )
    }
}
