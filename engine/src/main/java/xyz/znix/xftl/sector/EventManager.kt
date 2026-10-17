package xyz.znix.xftl.sector

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.Translator
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
    private val consumedUniques = HashSet<Event>()
    val uniqueEvents: UniqueEventRegistry = object : UniqueEventRegistry {
        override fun isConsumed(event: Event): Boolean = consumedUniques.contains(event)
        override fun consume(event: Event) {
            consumedUniques.add(event)
        }

        override fun restoreAll() {
            consumedUniques.clear()
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
     * cleared when the next event starts. Saved with the game (0x61a3a0).
     */
    private var crewKillSeed: Int? = null

    fun crewKillSeed(): Int {
        crewKillSeed?.let { return it }
        val seed = ftl.browser.NativeRandom.next31()
        crewKillSeed = seed
        return seed
    }

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

    init {
        imageLists[ImageList.NONE.name] = ImageList.NONE

        for (event in eventFileNames) {
            loadEvents(df.parseXML(df[event]), true)
        }
        for (event in eventFileNames) {
            loadEvents(df.parseXML(df[event]), false)
        }

        // With the DLC enabled CreateEvent tries "OVERRIDE_<name>" in the list
        // map before "<name>"; every override in the DAT is a list.
        if (ftl.browser.BrowserFiles.enableAE) {
            for ((name, list) in eventLists.toMap()) if (name.startsWith("OVERRIDE_")) eventLists[name.removePrefix("OVERRIDE_")] = list
        }

        // Make sure all the referenced ships do exist
        for (ev in events.values) {
            if (ev is Event) {
                check(ev.loadShipName == null || ships.containsKey(ev.loadShipName))
            }
        }
        for (list in eventLists.values) {
            // Load the lazy-loadable events list, to make sure we're not going
            // to crash at runtime because one an event doesn't exist.
            list.events.toString()
        }
    }

    /**
     * Load the standard vanilla event files.
     */
    constructor(df: Datafile, translator: Translator, bp: BlueprintManager) :
            this(df, translator, bp, FILE_NAMES.filter { !it.startsWith("dlc") || ftl.browser.BrowserFiles.enableAE }.map { "data/$it.xml" })

    operator fun get(name: String): IEvent = getOrNull(name) ?: error("Missing event $name")

    /** A list shadows an event of the same name, as in CreateEvent. */
    fun getOrNull(name: String): IEvent? = eventLists[name] ?: events[name]

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

    fun hasShip(name: String): Boolean = ships.containsKey(name)
    fun getShips(): Collection<EnemyShipSpec> = ships.values

    private fun loadEvents(doc: Document, resourcePass: Boolean) {
        val root = doc.rootElement
        check(root.name == "FTL")

        for (elem in root.children) {
            // Everything is loaded in two passes: once for the textlists/images (later is not yet implemented), and
            // a second time to load the events (which reference the resources).
            if (resourcePass) {
                when (elem.name) {
                    "textList" -> loadTextList(elem)
                    "imageList" -> loadImageList(elem)
                }
            } else {
                when (elem.name) {
                    "textList", "imageList" -> Unit // Handled in the resource pass
                    "eventList" -> loadEventList(elem)
                    "ship" -> loadShip(elem)
                    "eventCounts" -> loadEventCounts(elem)
                    "event" -> {
                        val name = elem.requireAttributeValue("name")
                        val serialId = "rootEvent::$name"
                        eventCheck(name, events)
                        events[name] = loadEvent(elem, name, serialId).value
                    }

                    else -> error("Unknown eventfile item ${elem.name}")
                }
            }
        }
    }

    private fun loadEventList(elem: Element) {
        check(elem.name == "eventList")
        val name = elem.requireAttributeValue("name")
        eventCheck(name, eventLists)

        val events = elem.children.withIndex().map {
            val serialId = "$name::eventList${it.index}"
            loadEvent(it.value, "$name.${it.index}", serialId, nested = true)
        }
        eventLists[name] = EventList(name, events)
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
        ships[ship.name] = ship
    }

    fun loadEmbeddedEvent(elem: Element, uniqueId: String): Lazy<IEvent> {
        val deserialisationId = "embeddedEvent::$uniqueId::${elem.name}"
        return loadEvent(elem, "$uniqueId.${elem.name}", deserialisationId, true)
    }

    private fun loadEvent(elem: Element, debugId: String, serialId: String, embed: Boolean = false, nested: Boolean = false): Lazy<IEvent> {
        if (!embed)
            check(elem.name == "event")

        elem.getAttributeValue("load")?.let {
            return lazy { getOrNull(it) ?: error("Missing event '$it', referenced from '$debugId'") }
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

        val text = elem.getChild("text")?.let(::loadText)
        val choices = elem.getChildren("choice").withIndex().mapNotNull { loadChoice(it.value, it.index, uniqueId) }
        val event = Event(text, choices, elem, debugId, uniqueId, ::getImageList, ::loadText, uniqueEvents)

        byDeserialisationId[uniqueId] = event

        if (nestedName != null) {
            eventCheck(nestedName, events)
            events[nestedName] = event
            return lazy { getOrNull(nestedName) ?: event }
        }

        return lazyOf(event)
    }

    private fun loadChoice(elem: Element, choiceIndex: Int, parentDeserialisationId: String): Choice? {
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
        val event = loadEvent(eventElem, "choice.ukn", deserialisationId, nested = true)

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
        return EventText(elem.textTrim, null, planet, back)
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
            images += EnvironmentImage(path, child.getAttributeValue("w")?.toIntOrNull(), child.getAttributeValue("h")?.toIntOrNull())
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
