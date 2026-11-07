// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp and TriggeredEvents.cpp (the
// CustomEventsParser parse functions, GPL-3.0-or-later; this engine is AGPL-3.0-or-later).
package xyz.znix.xftl.hs.events

import org.jdom2.Element
import xyz.znix.xftl.hs.HS

/**
 * CustomEventsParser: everything Hyperspace reads from `hyperspace.xml` `<events>` and from the HS children of
 * every event node, as plain data. The order of the calls follows HS:
 *
 * 1. [earlyParse] (Global::PreInitializeResources: event files, named load lists, boxes, sounds, warnings,
 *    beacon types), before any event file is read;
 * 2. [parseVanillaEventNode] / [parseVanillaShipEventNode] for every event node as the event files load
 *    (EventsParser::ProcessEvent / ProcessShipEvent hooks), `data/events_hyperspace.xml` and the `<eventFile>`s
 *    first, then the vanilla list (CApp 0x4037f0 loads events after PreloadResources);
 * 3. [parseCustomEventNode] (Global::InitializeResources, after the event files) and [postProcess];
 * 4. [parseVanillaBaseNode] whenever the sector descriptions are read (EventsParser::ProcessBaseNode hook).
 */
class HsEventParser(
    /** EventsParser::ProcessEvent for an inline `<event>` (load lists): registers it and returns its name. */
    private val processEvent: (Element, String) -> String,
    /** TextLibrary::GetText, for `<win text>`/`<lose text>` (the text is resolved at parse time, as HS does). */
    private val text: (String) -> String?
) {
    // HS defaults (CustomOptionsManager::defaults), read from hyperspace.xml <defaults> by [readDefaults].
    @JvmField var defaultCheckCargo = false
    @JvmField var defaultChoiceRequiresCrew = false
    @JvmField var defaultBeaconTypeHideVanillaLabel = false
    @JvmField var defaultInstantEscapeRemoveShipBox = false

    @JvmField val eventFiles = ArrayList<String>()
    @JvmField val defaultVictory = CustomEvent("default_victory")
    @JvmField val defaultQuest = CustomQuest()
    @JvmField var defaultRevisit = ""
    @JvmField var defaultRevisitSeeded = true
    @JvmField var defaultRevisitIgnoreUnique = false
    @JvmField val initialPlayerVars = ArrayList<VariableModifier>()
    @JvmField val initialMetaVars = ArrayList<VariableModifier>()

    @JvmField val triggeredDefs = ArrayList<TriggeredEventDefinition>()
    @JvmField val timerSoundDefs = HashMap<String, List<Pair<Float, String>>>()
    @JvmField val boxDefs = HashMap<String, TriggeredEventBoxDefinition>()
    @JvmField val warningDefs = HashMap<String, TriggeredEventWarningDefinition>()

    private val customSectors = HashMap<String, CustomSector>()
    private val customSectorsPreload = HashMap<String, CustomSector>()
    private val customEvents = HashMap<String, CustomEvent>()
    private val customRecursiveEvents = HashMap<String, CustomEvent>()
    private val vCustomEvents = HashMap<String, ArrayList<CustomEvent>>()
    private val customShipEvents = HashMap<String, CustomShipEvent>()
    private val customQuests = HashMap<String, CustomQuest>()
    private val bossShipIds = HashMap<String, BossShipDefinition>()
    private val customBeacons = HashMap<String, BeaconType>()
    private val customReqs = HashMap<String, CustomReq>()
    private val customEventLoadLists = HashMap<String, EventLoadList>()

    /** Every `<hiddenAug>` name the named events give (the console's pickers mark those augments). */
    fun hiddenAugNames(): Set<String> {
        val out = LinkedHashSet<String>()
        for (e in customEvents.values) out += e.hiddenAugs
        for (e in customRecursiveEvents.values) out += e.hiddenAugs
        for (list in vCustomEvents.values) for (e in list) out += e.hiddenAugs
        return out
    }

    /** Named `<loadEventList>`s whose inline events are registered once the event pass starts. */
    private val pendingLoadLists = ArrayList<Pair<Element, EventLoadList>>()

    // ---------------------------------------------------------------- lookups (CustomEventsParser::Get*)

    /** GetCustomEvent: the exact name first, then the recursive definition of the base name (before the first space). */
    fun getCustomEvent(event: String?): CustomEvent? {
        if (event == null) return null
        customEvents[event]?.let { return it }
        return customRecursiveEvents[baseEventName(event)]
    }

    fun getCustomShipEvent(event: String?): CustomShipEvent? = if (event == null) null else customShipEvents[event]
    fun getCustomQuest(event: String): CustomQuest? = customQuests[event]
    fun getCustomSector(name: String): CustomSector? = customSectors[name]
    fun getCustomSectorPreload(name: String): CustomSector? = customSectorsPreload[name]
    fun getCustomReq(name: String): CustomReq? = customReqs[name]
    fun getEventLoadList(name: String): EventLoadList? = customEventLoadLists[name]
    fun getBossShipDefinition(shipId: String): BossShipDefinition? = bossShipIds[shipId]
    fun getBeaconType(name: String): BeaconType? = customBeacons[name]

    /** Diagnostics/Lua: the numbers of everything parsed. */
    fun counts(): Map<String, Int> = linkedMapOf(
        "customEvents" to customEvents.size, "recursiveEvents" to customRecursiveEvents.size,
        "shipEvents" to customShipEvents.size, "quests" to customQuests.size, "sectors" to customSectors.size,
        "sectorsPreload" to customSectorsPreload.size, "bossShips" to bossShipIds.size, "beaconTypes" to customBeacons.size,
        "reqs" to customReqs.size, "loadEventLists" to customEventLoadLists.size, "triggeredDefs" to triggeredDefs.size,
        "initialPlayerVars" to initialPlayerVars.size, "initialMetaVars" to initialMetaVars.size
    )

    val customEventNames: Set<String> get() = customEvents.keys
    val recursiveEventNames: Set<String> get() = customRecursiveEvents.keys

    // ---------------------------------------------------------------- hyperspace.xml

    /** CustomOptionsManager::defaults (every hyperspace.xml `<defaults>`, in order); read before anything else is parsed. */
    fun readDefaults(root: Element) {
        for (defaults in root.getChildren("defaults")) for (child in defaults.children) when (child.name) {
            "checkCargo" -> defaultCheckCargo = parseBool(child.text)
            "choiceRequiresCrew" -> defaultChoiceRequiresCrew = parseBool(child.text)
            "beaconType_hideVanillaLabel" -> defaultBeaconTypeHideVanillaLabel = parseBool(child.text)
            "instantEscape_removeShipBox" -> defaultInstantEscapeRemoveShipBox = parseBool(child.text)
        }
        // CustomEvent's constructor copies these defaults.
        defaultVictory.checkCargo = defaultCheckCargo
        defaultVictory.choiceRequiresCrew = defaultChoiceRequiresCrew
    }

    private fun newCustomEvent(name: String): CustomEvent = CustomEvent(name).also {
        it.checkCargo = defaultCheckCargo
        it.choiceRequiresCrew = defaultChoiceRequiresCrew
    }

    /** EarlyParseCustomEventNode: `<events>` children needed before the event files load. */
    fun earlyParse(node: Element) {
        for (eventNode in node.children) {
            when (eventNode.name) {
                "eventFile" -> eventFiles += eventNode.text
                "loadEventList" -> {
                    val list = EventLoadList()
                    val name = eventNode.getAttributeValue("name") ?: ""
                    // The inline events of a named list are processed with the list's name as parent.
                    pendingLoadLists += eventNode to list
                    parseLoadListAttributes(eventNode, list)
                    if (name.isNotEmpty()) customEventLoadLists[name] = list
                }
                "triggeredEventBox" -> {
                    val box = TriggeredEventBoxDefinition()
                    parseTriggeredEventBoxNode(eventNode, box)
                    eventNode.getAttributeValue("name")?.let { boxDefs[it] = box }
                }
                "timerSounds" -> {
                    val sounds = ArrayList<Pair<Float, String>>()
                    parseTriggeredEventSounds(eventNode, sounds)
                    eventNode.getAttributeValue("name")?.let { timerSoundDefs[it] = sounds }
                }
                "warningMessage" -> {
                    val warning = TriggeredEventWarningDefinition()
                    parseTriggeredEventWarningNode(eventNode, warning)
                    eventNode.getAttributeValue("name")?.let { warningDefs[it] = warning }
                }
                "beaconType" -> eventNode.getAttributeValue("name")?.let { name ->
                    val beaconType = BeaconType()
                    beaconType.eventName = name
                    parseCustomBeaconType(eventNode, beaconType)
                    customBeacons[name] = beaconType
                }
                // CustomBackgroundObjectManager::ParseCustomBackgroundObject (render: hs.render.HsBackgroundObjects)
                "backgroundObject" -> if (eventNode.getAttributeValue("name") != null)
                    xyz.znix.xftl.hs.render.HsBackgroundObjects.parse(eventNode, processEvent)
                // EventButtonManager::ParseEventButton (render: hs.render.HsEventButtons)
                "eventButton" -> if (eventNode.getAttributeValue("name") != null)
                    xyz.znix.xftl.hs.render.HsEventButtons.parse(eventNode, processEvent)
            }
        }
    }

    /** Called by the event loader when its event pass starts: the inline events of the named load lists. */
    fun processPendingLoadLists() {
        for ((node, list) in pendingLoadLists) {
            val name = node.getAttributeValue("name") ?: ""
            parseLoadListEvents(node, list, name)
        }
        pendingLoadLists.clear()
    }

    /** ParseCustomEventNode: the rest of `<events>`, once the event files are loaded. */
    fun parseCustomEventNode(node: Element) {
        for (eventNode in node.children) {
            when (eventNode.name) {
                "bossShip" -> {
                    val def = BossShipDefinition(eventNode.text)
                    eventNode.getAttributeValue("yOffset")?.let { def.yOffset = parseInt(it) }
                    bossShipIds[def.shipId] = def
                }
                "fleetDef" -> HsCustomFleet.parse(eventNode)
                "sector" -> {
                    val name = eventNode.getAttributeValue("name") ?: ""
                    if (name.isNotEmpty()) {
                        val sec = customSectorsPreload[name] ?: CustomSector()
                        sec.sectorName = name
                        parseCustomSector(eventNode, sec)
                        customSectorsPreload[name] = sec
                    }
                }
                "revisitEvent" -> {
                    defaultRevisit = eventNode.text
                    eventNode.getAttributeValue("seeded")?.let { defaultRevisitSeeded = parseBool(it) }
                    eventNode.getAttributeValue("ignoreUnique")?.let { defaultRevisitIgnoreUnique = parseBool(it) }
                }
                "event" -> eventNode.getAttributeValue("name")?.let { parseHyperspaceEvent(eventNode, it) }
                "shipEvent" -> eventNode.getAttributeValue("name")?.let { name ->
                    val customEvent = customShipEvents.getOrPut(name) { CustomShipEvent(name) }
                    parseCustomShipEvent(eventNode, customEvent)
                }
                "quest" -> parseCustomQuestNode(eventNode, defaultQuest)
                "req" -> {
                    val name = eventNode.getAttributeValue("name")
                    if (name != null) {
                        val req = CustomReq()
                        parseCustomReqNode(eventNode, req)
                        customReqs[name] = req
                    } else {
                        HS.unsupported("error", "hyperspace.xml events", "req without name")
                    }
                }
                "combatTimerPosition" -> for (child in eventNode.children) {
                    // CustomEvents.cpp: TriggeredEventGui::normalBoxPos / bossBoxPos
                    when (child.name) {
                        "normalPos" -> {
                            child.getAttributeValue("x")?.let { HsTriggeredGui.normalBoxX = parseInt(it) }
                            child.getAttributeValue("y")?.let { HsTriggeredGui.normalBoxY = parseInt(it) }
                        }
                        "bossPos" -> {
                            child.getAttributeValue("x")?.let { HsTriggeredGui.bossBoxX = parseInt(it) }
                            child.getAttributeValue("y")?.let { HsTriggeredGui.bossBoxY = parseInt(it) }
                        }
                    }
                }
                "variable" -> initialPlayerVars += VariableModifier().also { it.parse(eventNode) }
                "metaVariable" -> initialMetaVars += VariableModifier().also { it.parse(eventNode) }
            }
        }
    }

    private fun parseHyperspaceEvent(eventNode: Element, eventName: String) {
        if (eventName == "default_victory") {
            defaultVictory.eventName = "default_victory"
            parseCustomEvent(eventNode, defaultVictory)
            return
        }
        val recursiveAttr = eventNode.getAttributeValue("recursive")
        if (recursiveAttr != null && !parseBool(recursiveAttr)) {
            val existing = customEvents[eventName]
            if (existing == null) {
                val customEvent = customRecursiveEvents[eventName]?.copy() ?: newCustomEvent(eventName)
                customEvent.eventName = eventName
                customEvent.recursive = false
                parseCustomEvent(eventNode, customEvent)
                customEvents[eventName] = customEvent
                vCustomEvents.getOrPut(eventName) { ArrayList() } += customEvent
            } else {
                parseCustomEvent(eventNode, existing)
            }
        } else {
            val existing = customRecursiveEvents[eventName]
            if (existing == null) {
                val customEvent = newCustomEvent(eventName)
                parseCustomEvent(eventNode, customEvent)
                customRecursiveEvents[eventName] = customEvent
            } else {
                parseCustomEvent(eventNode, existing)
            }
            // The exact definitions already made for this base name (and its sub-events) get the same tags.
            vCustomEvents[eventName]?.let { list -> for (customEvent in list) parseCustomEvent(eventNode, customEvent) }
        }
    }

    /** PostProcessCustomEvents: deferred box/warning/sound loads and `<beaconType load>`. */
    fun postProcess() {
        for (def in triggeredDefs) {
            def.loadBox?.let { node ->
                val box = def.box ?: TriggeredEventBoxDefinition().also { def.box = it }
                node.getAttributeValue("load")?.let { loadName ->
                    val src = boxDefs[loadName]
                    if (src != null) def.box = src.copy() else HS.unsupported("error", "triggeredEventBox", "load $loadName")
                }
                parseTriggeredEventBoxNode(node, def.box ?: box)
                def.loadBox = null
            }
            if (def.loadTimerSounds.isNotEmpty()) {
                val sounds = timerSoundDefs[def.loadTimerSounds]
                if (sounds != null) {
                    def.timerSounds += sounds
                    def.timerSounds.sortWith { a, b -> b.first.compareTo(a.first) }
                } else HS.unsupported("error", "timerSounds", "load ${def.loadTimerSounds}")
            }
            def.loadWarning?.let { node ->
                node.getAttributeValue("load")?.let { loadName ->
                    val src = warningDefs[loadName]
                    if (src != null) def.warning = src.copy() else HS.unsupported("error", "warningMessage", "load $loadName")
                }
                parseTriggeredEventWarningNode(node, def.warning ?: TriggeredEventWarningDefinition().also { def.warning = it })
                def.loadWarning = null
            }
        }
        for (def in customEvents.values + customRecursiveEvents.values) {
            if (def.loadBeacon.isNotEmpty()) customBeacons[def.loadBeacon]?.let { def.beacon = it }
            def.loadBeacon = ""
        }
    }

    // ---------------------------------------------------------------- event files

    /**
     * ParseVanillaEventNode (EventsParser::ProcessEvent hook, nodes without `load`): the HS tags of the node itself,
     * kept only when it has any. A later definition of the same name resets and replaces the earlier one.
     */
    fun parseVanillaEventNode(node: Element, eventName: String, baseEventName: String) {
        val existing = customEvents[eventName]
        if (existing == null) {
            val customEvent = newCustomEvent(eventName)
            customEvent.recursive = false
            val isDefault = parseCustomEvent(node, customEvent, true)
            if (!isDefault) {
                customEvents[eventName] = customEvent
                vCustomEvents.getOrPut(baseEventName) { ArrayList() } += customEvent
            }
        } else {
            // `*customEvent = CustomEvent()` then a fresh parse: the default constructor's values (recursive is
            // true again). The object is replaced everywhere HS's pointer is held.
            val fresh = newCustomEvent(eventName)
            customEvents[eventName] = fresh
            for (list in vCustomEvents.values) {
                val i = list.indexOf(existing)
                if (i >= 0) list[i] = fresh
            }
            parseCustomEvent(node, fresh, true)
        }
    }

    /** ParseVanillaShipEventNode (EventsParser::ProcessShipEvent hook, named ship events). */
    fun parseVanillaShipEventNode(node: Element, eventName: String) {
        val existing = customShipEvents[eventName]
        if (existing == null) {
            val customEvent = CustomShipEvent(eventName)
            if (!parseCustomShipEvent(node, customEvent)) customShipEvents[eventName] = customEvent
        } else {
            val fresh = CustomShipEvent(eventName)
            parseCustomShipEvent(node, fresh)
            customShipEvents[eventName] = fresh
        }
    }

    /** ParseVanillaBaseNode (EventsParser::ProcessBaseNode hook): `<sectorDescription>` gets the preloaded HS sector. */
    fun parseVanillaBaseNode(node: Element) {
        when (node.name) {
            "sectorDescription" -> {
                val name = node.getAttributeValue("name") ?: return
                val preload = customSectorsPreload[name]
                val sec = preload?.copy() ?: CustomSector()
                sec.sectorName = name
                parseCustomSector(node, sec, true)
                customSectors[name] = sec
            }
            "sectorMap" -> HS.unsupported("xml", "sector_data.xml", "sectorMap")
        }
    }

    // ---------------------------------------------------------------- the node parsers

    /** ParseCustomEvent: true when the node has none of the HS tags. */
    fun parseCustomEvent(node: Element, e: CustomEvent, parsingVanilla: Boolean = false): Boolean {
        e.instantEscapeRemoveShipBox = defaultInstantEscapeRemoveShipBox
        var isDefault = true
        for (child in node.children) {
            val v = child.text
            when (child.name) {
                "checkCargo" -> { isDefault = false; e.checkCargo = if (v.isEmpty()) true else parseBool(v) }
                "preventQuest" -> { isDefault = false; e.preventQuest = true }
                "noQuestText" -> { isDefault = false; e.noQuestText = true }
                "quest" -> {
                    // Not a reason to keep the event: a quest's options live in customQuests.
                    val questEventName = child.getAttributeValue("event") ?: e.eventName
                    val quest = CustomQuest()
                    if (!parseCustomQuestNode(child, quest)) customQuests[questEventName] = quest
                }
                "triggeredEvent" -> {
                    isDefault = false
                    val def = TriggeredEventDefinition()
                    parseCustomTriggeredEventNode(child, def)
                    e.triggeredEvents += pushDef(def)
                }
                "clearTriggeredEvent" -> child.getAttributeValue("name")?.let { isDefault = false; e.clearTriggeredEvents += it }
                    ?: HS.unsupported("error", e.eventName, "clearTriggeredEvent without name")
                "triggeredEventModifier" -> {
                    val name = child.getAttributeValue("name")
                    if (name != null) {
                        isDefault = false
                        e.triggeredEventModifiers += parseTriggeredEventModifier(child)
                    } else HS.unsupported("error", e.eventName, "triggeredEventModifier without name")
                }
                "preventFleet" -> { isDefault = false; e.preventFleet = true }
                "preventBossFleet" -> {
                    isDefault = false
                    e.preventBossFleet = if (child.getAttributeValue("forever")?.let(::parseBool) == true) 2 else 1
                }
                "runFromFleet" -> {
                    isDefault = false
                    e.runFromFleet = if (child.getAttributeValue("closest")?.let(::parseBool) == true) 1 else 2
                }
                "secretSectorWarp", "secretSector" -> if (v.isNotEmpty()) { isDefault = false; e.secretSectorWarp = v }
                "loadEvent" -> {
                    isDefault = false
                    e.eventLoad = v
                    child.getAttributeValue("seeded")?.let { e.eventLoadSeeded = parseBool(it) }
                    child.getAttributeValue("ignoreUnique")?.let { e.eventLoadIgnoreUnique = parseBool(it) }
                }
                "revisitEvent" -> {
                    isDefault = false
                    e.eventRevisit = v
                    child.getAttributeValue("seeded")?.let { e.eventRevisitSeeded = parseBool(it) }
                    child.getAttributeValue("ignoreUnique")?.let { e.eventRevisitIgnoreUnique = parseBool(it) }
                }
                "loadEventList" -> {
                    isDefault = false
                    val list = EventLoadList()
                    parseLoadListAttributes(child, list)
                    parseLoadListEvents(child, list, e.eventName)
                    e.eventLoadList = list
                }
                "eventAlias" -> {
                    isDefault = false
                    val alias = EventAlias(v)
                    child.getAttributeValue("jumpClear")?.let { alias.jumpClear = parseBool(it) }
                    child.getAttributeValue("once")?.let { alias.once = parseBool(it) }
                    e.eventAlias += (child.getAttributeValue("name") ?: "") to alias
                }
                "queueEvent" -> {
                    isDefault = false
                    val q = EventQueueEvent(v, v)
                    child.getAttributeValue("name")?.let { q.label = it }
                    child.getAttributeValue("seeded")?.let { q.seed = if (parseBool(it)) 0 else -1 }
                    e.queueEvents += q
                }
                "clearQueueEvent" -> { isDefault = false; e.clearQueueEvents += child.getAttributeValue("name") ?: "" }
                "restartEvent" -> { isDefault = false; e.restartEvent = true }
                "renameBeacon" -> { isDefault = false; e.renameBeacon = v }
                "jumpEvent" -> { isDefault = false; e.jumpEvents += parseJumpEvent(child) }
                "clearJumpEvent" -> { isDefault = false; e.clearJumpEvents += child.getAttributeValue("name") ?: "" }
                "deathEvent" -> { isDefault = false; e.deathEvents += parseDeathEvent(child) }
                "clearDeathEvent" -> { isDefault = false; e.clearDeathEvents += child.getAttributeValue("name") ?: "" }
                "replaceSector" -> {
                    isDefault = false
                    child.getAttributeValue("name")?.let { e.replaceSectorTarget = it }
                    e.replaceSectorList = v
                }
                "recallBoarders" -> {
                    isDefault = false
                    e.recallBoarders = true
                    when (child.getAttributeValue("ship")) { "enemy" -> e.recallBoardersShip = 1; "player" -> e.recallBoardersShip = -1 }
                    child.getAttributeValue("force")?.let { e.recallBoardersForce = parseBool(it) }
                }
                "resetFtl" -> { isDefault = false; e.resetFtl = true }
                "instantEscape" -> {
                    isDefault = false
                    e.instantEscape = true
                    child.getAttributeValue("removeShipBox")?.let { e.instantEscapeRemoveShipBox = parseBool(it) }
                }
                "escape" -> { isDefault = false; e.escape = true }
                "surrender" -> { isDefault = false; e.surrender = true }
                "loadEscape" -> { isDefault = false; e.loadEscape = true; child.getAttributeValue("force")?.let { e.loadEscapeForced = parseBool(it) } }
                "loadSurrender" -> { isDefault = false; e.loadSurrender = true; child.getAttributeValue("force")?.let { e.loadSurrenderForced = parseBool(it) } }
                "disableEscape" -> { isDefault = false; e.disableEscape = true; child.getAttributeValue("force")?.let { e.disableEscapeForced = parseBool(it) } }
                "disableSurrender" -> { isDefault = false; e.disableSurrender = true; child.getAttributeValue("force")?.let { e.disableSurrenderForced = parseBool(it) } }
                "goToFlagship" -> {
                    isDefault = false
                    e.goToFlagship = true
                    child.getAttributeValue("atBase")?.let { e.goToFlagshipBase = parseBool(it) }
                    child.getAttributeValue("allFleet")?.let { e.goToFlagshipFleet = parseBool(it) }
                }
                "allowNoSlot" -> { isDefault = false; e.allowNoSlot = true }
                "blockNoSlot" -> { isDefault = false; e.blockNoSlot = true }
                "choiceRequiresCrew" -> { isDefault = false; e.choiceRequiresCrew = true }
                "ignoreEssential" -> {
                    isDefault = false
                    e.ignoreEssentialMin = child.getAttributeValue("min")?.let(::parseFloat) ?: 0f
                    e.ignoreEssentialMax = child.getAttributeValue("max")?.let(::parseFloat) ?: 0f
                }
                "transformRace" -> {
                    isDefault = false
                    e.transformRaceTo = v
                    child.getAttributeValue("class")?.let { e.transformRaceFrom = it }
                }
                "beaconType" -> {
                    isDefault = false
                    val load = child.getAttributeValue("load")
                    if (load != null) e.loadBeacon = load
                    else e.beacon = BeaconType().also { it.eventName = e.eventName; parseCustomBeaconType(child, it) }
                }
                "removeHazards" -> { isDefault = false; e.removeHazards = true }
                "removeNebula" -> { isDefault = false; e.removeNebula = true }
                "hiddenAug" -> { isDefault = false; e.hiddenAugs += v }
                "win" -> {
                    isDefault = false
                    e.gameOver.enabled = true; e.gameOver.victory = true; e.gameOver.sound = "victory"
                    child.getAttributeValue("text")?.let { e.gameOver.text = text(it) ?: it }
                    child.getAttributeValue("creditsText")?.let { e.gameOver.creditsText = it }
                    child.getAttributeValue("creditsBackground")?.let { e.gameOver.creditsBackground = it }
                    child.getAttributeValue("music")?.let { e.gameOver.creditsMusic = it }
                    child.getAttributeValue("sound")?.let { e.gameOver.sound = it }
                    child.getAttributeValue("ach")?.let { e.gameOver.ach = it }
                }
                "lose" -> {
                    isDefault = false
                    e.gameOver.enabled = true
                    child.getAttributeValue("text")?.let { e.gameOver.text = text(it) ?: it }
                    child.getAttributeValue("sound")?.let { e.gameOver.sound = it }
                }
                "achievement" -> {
                    isDefault = false
                    e.achievement = v
                    child.getAttributeValue("silent")?.let { e.achievementSilent = parseBool(it) }
                }
                "playSound" -> { isDefault = false; e.playSound = v }
                "playMusic" -> { isDefault = false; if (v.isEmpty()) e.resetMusic = true else e.playMusic = v }
                "changeBackground" -> { isDefault = false; e.changeBackground = v }
                "backgroundObject" -> {
                    isDefault = false; e.backgroundObjects += child
                    xyz.znix.xftl.hs.render.HsBackgroundObjects.parse(child, processEvent)
                }
                "clearBackgroundObject" -> { isDefault = false; e.clearBackgroundObjects += child.getAttributeValue("name") ?: "" }
                "transformBackgroundObject" -> {
                    isDefault = false
                    e.transformBackgroundObjects += (child.getAttributeValue("name") ?: "") to v
                }
                "eventButton" -> {
                    isDefault = false; e.eventButtons += child
                    xyz.znix.xftl.hs.render.HsEventButtons.parse(child, processEvent)
                }
                "clearEventButton" -> { isDefault = false; e.clearEventButtons += child.getAttributeValue("name") ?: "" }
                "unlockCustomShip", "unlockShip" -> if (child.name == "unlockCustomShip" || !parsingVanilla) {
                    if (v.isNotEmpty()) {
                        isDefault = false
                        e.unlockShip = v
                        child.getAttributeValue("silent")?.let { e.unlockShipSilent = parseBool(it) }
                        child.getAttributeValue("shipReq")?.let { e.unlockShipReq = it }
                    }
                }
                "disableScrapScore" -> { isDefault = false; e.disableScrapScore = true }
                "disableScrapAugments" -> { isDefault = false; e.disableScrapAugments = true }
                "removeItem" -> { isDefault = false; e.removeItems += v }
                "removeSystem" -> {
                    isDefault = false
                    val player = child.getAttributeValue("player")?.let(::parseBool) ?: true
                    e.removeSystems += player to v
                }
                "variable", "metaVariable", "tempVariable" -> {
                    isDefault = false
                    e.variables += VariableModifier().also { it.parse(child) }
                }
                "customStore", "store" -> if (v.isNotEmpty()) { isDefault = false; e.customStore = v }
                "removeStore" -> { isDefault = false; e.removeStore = true }
                "customFleet" -> {
                    isDefault = false
                    val right = child.getAttributeValue("right")?.let(::parseBool) ?: false
                    val firing = child.getAttributeValue("firing")?.let(::parseBool) ?: false
                    val autoDarkening = child.getAttributeValue("autoDarkening")?.let(::parseBool) ?: false
                    if (firing) e.noASBPlanet = true
                    val fleet = if (right) e.rightFleet else e.leftFleet
                    fleet.fleetDefName = v; fleet.firing = firing; fleet.autoDarkening = autoDarkening
                }
                "clearCustomFleet" -> { isDefault = false; e.clearCustomFleet = true }
                "enemyDamage" -> {
                    isDefault = false
                    val d = HsEventDamage(-1, 0, 0)
                    child.getAttributeValue("amount")?.let { d.amount = parseInt(it) }
                    child.getAttributeValue("system")?.let { d.systemName = it }
                    when (child.getAttributeValue("effect")) { "fire" -> d.effect = 1; "breach" -> d.effect = 2; "all" -> d.effect = 3; "random" -> d.effect = 4 }
                    if (child.getAttributeValue("force")?.let(::parseBool) == true) d.effect = d.effect or 8
                    val damageHull = child.getAttributeValue("damageHull")
                    if (damageHull != null && !parseBool(damageHull)) d.effect = d.effect or 16
                    e.enemyDamage += d
                }
                "repairAllSystems" -> { isDefault = false; e.repairAllSystems = true }
                "killEnemyBoarders" -> { isDefault = false; e.killEnemyBoarders = true }
                "superDrones" -> {
                    isDefault = false
                    e.superDrones = child.getAttributeValue("player")?.let { if (parseBool(it)) 0 else 1 } ?: 1
                    child.getAttributeValue("name")?.let { e.superDronesName = it }
                }
                "clearSuperDrones" -> {
                    isDefault = false
                    e.clearSuperDrones = child.getAttributeValue("player")?.let { if (parseBool(it)) 0 else 1 } ?: 1
                }
                "superBarrage" -> {
                    isDefault = false
                    e.superBarrage = child.getAttributeValue("player")?.let { if (parseBool(it)) 0 else 1 } ?: 1
                    child.getAttributeValue("name")?.let { e.superBarrageName = it }
                }
                "superShields" -> {
                    isDefault = false
                    e.powerSuperShields = child.getAttributeValue("player")?.let { if (parseBool(it)) 0 else 1 } ?: 1
                    child.getAttributeValue("amount")?.let { e.powerSuperShieldsSet = parseInt(it) }
                    child.getAttributeValue("add")?.let { e.powerSuperShieldsAdd = parseInt(it) }
                }
                "noASBPlanet" -> { isDefault = false; e.noASBPlanet = true }
            }
        }
        return isDefault
    }

    private fun parseTriggeredEventModifier(child: Element): TriggeredEventModifier {
        val def = TriggeredEventModifier()
        child.getAttributeValue("name")?.let { def.name = it }
        child.getAttributeValue("time")?.let { def.minTime = parseFloat(it); def.maxTime = def.minTime }
        child.getAttributeValue("minTime")?.let { def.minTime = parseFloat(it) }
        child.getAttributeValue("maxTime")?.let { def.maxTime = parseFloat(it) }
        child.getAttributeValue("jumps")?.let { def.minJumps = parseInt(it); def.maxJumps = def.minJumps }
        child.getAttributeValue("minJumps")?.let { def.minJumps = parseInt(it) }
        child.getAttributeValue("maxJumps")?.let { def.maxJumps = parseInt(it) }
        return def
    }

    private fun parseJumpEvent(child: Element): JumpEvent {
        val j = JumpEvent(child.text, child.text)
        child.getAttributeValue("name")?.let { j.label = it }
        child.getAttributeValue("loop")?.let { j.loop = parseBool(it) }
        child.getAttributeValue("priority")?.let { j.priority = parseInt(it) }
        return j
    }

    private fun parseDeathEvent(child: Element): DeathEvent {
        val d = DeathEvent(child.text, child.text)
        child.getAttributeValue("name")?.let { d.label = it }
        child.getAttributeValue("thisFight")?.let { d.thisFight = parseBool(it); d.jumpClear = d.thisFight }
        child.getAttributeValue("jumpClear")?.let { d.jumpClear = parseBool(it) }
        child.getAttributeValue("priority")?.let { d.priority = parseInt(it) }
        return d
    }

    /** ParseCustomShipEvent: true when the node has none of the HS tags. */
    fun parseCustomShipEvent(node: Element, e: CustomShipEvent): Boolean {
        var isDefault = true
        for (child in node.children) {
            when (child.name) {
                "triggeredEvent" -> {
                    isDefault = false
                    val def = TriggeredEventDefinition()
                    parseCustomTriggeredEventNode(child, def)
                    e.triggeredEvents += pushDef(def)
                }
                "clearTriggeredEvent" -> { isDefault = false; e.clearTriggeredEvents += child.getAttributeValue("name") ?: "" }
                "triggeredEventModifier" -> { isDefault = false; e.triggeredEventModifiers += parseTriggeredEventModifier(child) }
                "jumpEvent" -> { isDefault = false; e.jumpEvents += parseJumpEvent(child) }
                "clearJumpEvent" -> { isDefault = false; e.clearJumpEvents += child.getAttributeValue("name") ?: "" }
                "deathEvent" -> { isDefault = false; e.deathEvents += parseDeathEvent(child) }
                "clearDeathEvent" -> { isDefault = false; e.clearDeathEvents += child.getAttributeValue("name") ?: "" }
                "invincible" -> { isDefault = false; e.invincible = true }
                "deadCrewAuto" -> { isDefault = false; e.deadCrewAuto = true }
                "finalBoss", "miniBoss" -> {
                    isDefault = false
                    e.finalBoss.enabled = true
                    e.finalBoss.victory = child.name == "finalBoss"
                    e.finalBoss.sound = "victory"
                    child.getAttributeValue("text")?.let { e.finalBoss.text = text(it) ?: it }
                    child.getAttributeValue("creditsText")?.let { e.finalBoss.creditsText = it }
                    child.getAttributeValue("creditsBackground")?.let { e.finalBoss.creditsBackground = it }
                    child.getAttributeValue("music")?.let { e.finalBoss.creditsMusic = it }
                    child.getAttributeValue("sound")?.let { e.finalBoss.sound = it }
                    child.getAttributeValue("musicDelay")?.let { e.bossMusicDelay = parseInt(it) }
                }
            }
        }
        return isDefault
    }

    /** ParseCustomQuestNode: true when the node sets none of the quest options. */
    fun parseCustomQuestNode(node: Element, quest: CustomQuest): Boolean {
        var isDefault = true
        for (child in node.children) {
            val v = child.text
            when (child.name) {
                "nonNebulaBeacon" -> { isDefault = false; quest.nonNebulaBeacon.set(parseBool(v)) }
                "nebulaBeacon" -> { isDefault = false; quest.nebulaBeacon.set(parseBool(v)) }
                "createNebula" -> { isDefault = false; quest.createNebula.set(parseBool(v)) }
                "removeNebula" -> { isDefault = false; quest.removeNebula.set(parseBool(v)) }
                "nebulaEvent" -> { isDefault = false; quest.nebulaEvent.set(v) }
                "currentSector" -> { isDefault = false; quest.currentSector.set(parseBool(v)) }
                "nextSector" -> { isDefault = false; quest.nextSector.set(parseBool(v)) }
                "aggressive" -> { isDefault = false; quest.aggressive.set(parseInt(v)) }
                "sectorEight" -> { isDefault = false; quest.sectorEight.set(parseBool(v)) }
                "lastStand" -> { isDefault = false; quest.lastStand.set(parseBool(v)) }
            }
        }
        return isDefault
    }

    fun parseCustomBeaconType(node: Element, b: BeaconType) {
        b.hideVanillaLabel = defaultBeaconTypeHideVanillaLabel
        val id = node.getAttributeValue("id")
        if (id != null) b.beaconText = HsTextString(id, false)
        else node.getAttributeValue("text")?.let { b.beaconText = HsTextString(it, true) }
        node.getAttributeValue("req")?.let { b.equipmentReq = it }
        node.getAttributeValue("global")?.let { b.global = parseBool(it) }
        node.getAttributeValue("persist")?.let { b.persistent = parseBool(it) }
        node.getAttributeValue("hideVanillaLabel")?.let { b.hideVanillaLabel = parseBool(it) }
        var r = 255f; var g = 255f; var bl = 255f; var a = 1f
        for (child in node.children) {
            fun tooltip(): HsTextString = child.getAttributeValue("id")?.let { HsTextString(it, false) } ?: HsTextString(child.text, true)
            when (child.name) {
                "unvisitedTooltip" -> b.unvisitedTooltip = tooltip()
                "visitedTooltip" -> b.visitedTooltip = tooltip()
                "undiscoveredTooltip" -> b.undiscoveredTooltip = tooltip()
                // ParseColorNode: r/g/b/a attributes.
                "color" -> {
                    child.getAttributeValue("r")?.let { r = parseFloat(it) }
                    child.getAttributeValue("g")?.let { g = parseFloat(it) }
                    child.getAttributeValue("b")?.let { bl = parseFloat(it) }
                    child.getAttributeValue("a")?.let { a = parseFloat(it) }
                }
            }
        }
        b.colorR = r; b.colorG = g; b.colorB = bl; b.colorA = a
    }

    fun parseCustomReqNode(node: Element, req: CustomReq) {
        node.getAttributeValue("name")?.let { req.name = it }
        node.getAttributeValue("type")?.let { t ->
            when (t.lowercase()) {
                "any", "max" -> req.type = CustomReq.TYPE_ANY
                "all", "min" -> req.type = CustomReq.TYPE_ALL
                "sum", "count" -> req.type = CustomReq.TYPE_SUM
                else -> HS.unsupported("error", "req ${req.name}", "type $t")
            }
        }
        node.getAttributeValue("lvl")?.let { req.lvl = parseInt(it) }
        node.getAttributeValue("max_lvl")?.let { req.maxLvl = parseInt(it) }
        node.getAttributeValue("mult")?.let { req.mult = parseInt(it) }
        node.getAttributeValue("const")?.let { req.constant = parseInt(it) }
        node.getAttributeValue("load")?.let { pendingReqLists += req to it }
        for (child in node.children) when (child.name) {
            "name" -> req.blueprints += child.text
            "req" -> req.children += CustomReq().also { parseCustomReqNode(child, it) }
        }
    }

    /**
     * `<req load="LIST">` takes the blueprint list's names (BlueprintManager::GetBlueprintList), which exist only once
     * the blueprints are loaded; [resolveReqLists] fills them. The list's names come before the `<name>` children,
     * as in HS (the assignment happens before the children are read).
     */
    private val pendingReqLists = ArrayList<Pair<CustomReq, String>>()

    fun resolveReqLists(blueprintList: (String) -> List<String>) {
        for ((req, list) in pendingReqLists) req.blueprints.addAll(0, blueprintList(list))
        pendingReqLists.clear()
    }

    private fun parseLoadListAttributes(node: Element, list: EventLoadList) {
        node.getAttributeValue("seeded")?.let { list.seeded = parseBool(it) }
        node.getAttributeValue("first")?.let { list.useFirst = parseBool(it) }
        node.getAttributeValue("default")?.let { list.defaultEvent = it }
        node.getAttributeValue("generate")?.let { list.onGenerate = parseBool(it) }
        node.getAttributeValue("ignoreUnique")?.let { list.ignoreUnique = parseBool(it) }
    }

    /** ParseCustomEventLoadList's `<event>` children; an entry without name/load is an inline event. */
    private fun parseLoadListEvents(node: Element, list: EventLoadList, eventName: String) {
        for (child in node.children) {
            if (child.name != "event") continue
            val ev = EventLoadListEvent()
            child.getAttributeValue("name")?.let { ev.event = it }
            child.getAttributeValue("load")?.let { ev.event = it }
            child.getAttributeValue("req")?.let { ev.req = it }
            child.getAttributeValue("max_lvl")?.let { ev.maxLvl = parseInt(it); ev.lvl = Int.MIN_VALUE }
            child.getAttributeValue("lvl")?.let { ev.lvl = parseInt(it) }
            child.getAttributeValue("max_group")?.let { ev.maxGroup = parseInt(it) }
            if (ev.event.isEmpty()) ev.event = processEvent(child, eventName)
            list.events += ev
        }
    }

    fun parseCustomSector(node: Element, sector: CustomSector, parsingVanilla: Boolean = false) {
        var isDefault = true
        node.getAttributeValue("maxSector")?.let { isDefault = false; sector.maxSector = parseInt(it) }
        for (child in node.children) {
            when (child.name) {
                "exitBeacon" -> {
                    isDefault = false
                    sector.exitBeacons = SectorExit(child.getAttributeValue("event") ?: "", child.getAttributeValue("rebelEvent") ?: "",
                        child.getAttributeValue("nebulaEvent") ?: "")
                }
                "rebelBeacon" -> {
                    isDefault = false
                    sector.fleetBeacons = SectorFleet(child.getAttributeValue("event") ?: "", child.getAttributeValue("nebulaEvent") ?: "")
                }
                "removeFirstBeaconNebula" -> { isDefault = false; sector.removeFirstBeaconNebula = true }
                "priorityNebulaFix" -> { isDefault = false; sector.priorityNebulaFix = true }
                "noExit" -> { isDefault = false; sector.noExit = true }
                "nebulaSector" -> { isDefault = false; sector.nebulaSector.set(parseBool(child.text)) }
                "bossSector" -> { isDefault = false; sector.bossSector.set(parseBool(child.text)) }
                "maxSector" -> { isDefault = false; sector.maxSector = parseInt(child.text) }
                "priorityEvent" -> {
                    isDefault = false
                    val p = PriorityEvent()
                    child.getAttributeValue("name")?.let { p.name = it }
                    child.getAttributeValue("min")?.let { p.min = parseInt(it) }
                    child.getAttributeValue("max")?.let { p.max = parseInt(it) }
                    p.priority = child.getAttributeValue("priority")?.let(::parseInt) ?: 0
                    child.getAttributeValue("req")?.let { p.req = it }
                    child.getAttributeValue("max_lvl")?.let { p.maxLvl = parseInt(it); p.lvl = Int.MIN_VALUE }
                    child.getAttributeValue("lvl")?.let { p.lvl = parseInt(it) }
                    sector.priorityEventCounts += p
                }
                "quest" -> {
                    isDefault = false
                    val q = SectorQuest()
                    child.getAttributeValue("event")?.let { ev ->
                        q.event = ev
                        val quest = CustomQuest()
                        if (!parseCustomQuestNode(child, quest)) customQuests[ev] = quest
                    }
                    child.getAttributeValue("req")?.let { q.req = it }
                    child.getAttributeValue("max_lvl")?.let { q.maxLvl = parseInt(it); q.lvl = Int.MIN_VALUE }
                    child.getAttributeValue("lvl")?.let { q.lvl = parseInt(it) }
                    sector.sectorQuests += q
                }
            }
        }
        // std::stable_sort by descending priority (Kotlin's sortWith is stable).
        if (!isDefault) sector.priorityEventCounts.sortWith { a, b -> b.priority.compareTo(a.priority) }
    }

    fun pushDef(def: TriggeredEventDefinition): Int {
        def.idx = triggeredDefs.size
        triggeredDefs += def
        return def.idx
    }

    fun parseCustomTriggeredEventNode(node: Element, def: TriggeredEventDefinition) {
        fun a(n: String) = node.getAttributeValue(n)
        a("event")?.let { def.event = it; def.name = it }
        a("name")?.let { def.name = it }
        a("seeded")?.let { def.seeded = parseBool(it) }
        a("thisFight")?.let { def.thisFight = parseBool(it); def.clearOnJump = def.thisFight }
        a("clearOnJump")?.let { def.clearOnJump = parseBool(it) }
        a("loops")?.let { def.minLoops = parseInt(it); def.maxLoops = def.minLoops }
        a("minLoops")?.let { def.minLoops = parseInt(it) }
        a("maxLoops")?.let { def.maxLoops = parseInt(it) }
        a("time")?.let { def.triggerMinTime = parseFloat(it); def.triggerMaxTime = def.triggerMinTime }
        a("minTime")?.let { def.triggerMinTime = parseFloat(it) }
        a("maxTime")?.let { def.triggerMaxTime = parseFloat(it) }
        a("jumps")?.let { def.triggerMinJumps = parseInt(it); def.triggerMaxJumps = def.triggerMinJumps }
        a("minJumps")?.let { def.triggerMinJumps = parseInt(it) }
        a("maxJumps")?.let { def.triggerMaxJumps = parseInt(it) }
        a("playerHull")?.let { def.minPlayerHull = parseInt(it); def.maxPlayerHull = def.minPlayerHull }
        a("minPlayerHull")?.let { def.minPlayerHull = parseInt(it) }
        a("maxPlayerHull")?.let { def.maxPlayerHull = parseInt(it) }
        a("playerDamage")?.let { def.minPlayerDamage = parseInt(it); def.maxPlayerDamage = def.minPlayerDamage }
        a("minPlayerDamage")?.let { def.minPlayerDamage = parseInt(it) }
        a("maxPlayerDamage")?.let { def.maxPlayerDamage = parseInt(it) }
        a("enemyHull")?.let { def.minEnemyHull = parseInt(it); def.maxEnemyHull = def.minEnemyHull }
        a("minEnemyHull")?.let { def.minEnemyHull = parseInt(it) }
        a("maxEnemyHull")?.let { def.maxEnemyHull = parseInt(it) }
        a("enemyDamage")?.let { def.minEnemyDamage = parseInt(it); def.maxEnemyDamage = def.minEnemyDamage }
        a("minEnemyDamage")?.let { def.minEnemyDamage = parseInt(it) }
        a("maxEnemyDamage")?.let { def.maxEnemyDamage = parseInt(it) }
        a("enemyHullScaling")?.let { def.enemyHullScaling = parseFloat(it) }
        a("enemyDamageScaling")?.let { def.enemyDamageScaling = parseFloat(it) }
        a("playerCrew")?.let { def.minPlayerCrew = parseInt(it); def.maxPlayerCrew = def.minPlayerCrew }
        a("minPlayerCrew")?.let { def.minPlayerCrew = parseInt(it) }
        a("maxPlayerCrew")?.let { def.maxPlayerCrew = parseInt(it) }
        a("playerDeaths")?.let { def.minPlayerDeaths = parseInt(it); def.maxPlayerDeaths = def.minPlayerDeaths }
        a("minPlayerDeaths")?.let { def.minPlayerDeaths = parseInt(it) }
        a("maxPlayerDeaths")?.let { def.maxPlayerDeaths = parseInt(it) }
        a("enemyCrew")?.let { def.minEnemyCrew = parseInt(it); def.maxEnemyCrew = def.minEnemyCrew }
        a("minEnemyCrew")?.let { def.minEnemyCrew = parseInt(it) }
        a("maxEnemyCrew")?.let { def.maxEnemyCrew = parseInt(it) }
        a("enemyDeaths")?.let { def.minEnemyDeaths = parseInt(it); def.maxEnemyDeaths = def.minEnemyDeaths }
        a("minEnemyDeaths")?.let { def.minEnemyDeaths = parseInt(it) }
        a("maxEnemyDeaths")?.let { def.maxEnemyDeaths = parseInt(it) }

        for (child in node.children) {
            fun c(n: String) = child.getAttributeValue(n)
            when (child.name) {
                "time" -> {
                    c("amount")?.let { def.triggerMinTime = parseFloat(it); def.triggerMaxTime = def.triggerMinTime }
                    c("min")?.let { def.triggerMinTime = parseFloat(it) }
                    c("max")?.let { def.triggerMaxTime = parseFloat(it) }
                }
                "jumps" -> {
                    c("amount")?.let { def.triggerMinJumps = parseInt(it); def.triggerMaxJumps = def.triggerMinJumps }
                    c("min")?.let { def.triggerMinJumps = parseInt(it) }
                    c("max")?.let { def.triggerMaxJumps = parseInt(it) }
                    // lexical_cast<int> into a uint8_t.
                    c("type")?.let { def.jumpType = parseInt(it) and 0xff }
                }
                "playerHull" -> {
                    c("amount")?.let { def.minPlayerHull = parseInt(it); def.maxPlayerHull = def.minPlayerHull }
                    c("min")?.let { def.minPlayerHull = parseInt(it) }
                    c("max")?.let { def.maxPlayerHull = parseInt(it) }
                }
                "enemyHull" -> {
                    c("amount")?.let { def.minEnemyHull = parseInt(it); def.maxEnemyHull = def.minEnemyHull }
                    c("min")?.let { def.minEnemyHull = parseInt(it) }
                    c("max")?.let { def.maxEnemyHull = parseInt(it) }
                    c("scaling")?.let { def.enemyHullScaling = parseFloat(it) }
                }
                "playerDamage" -> {
                    c("amount")?.let { def.minPlayerDamage = parseInt(it); def.maxPlayerDamage = def.minPlayerDamage }
                    c("min")?.let { def.minPlayerDamage = parseInt(it) }
                    c("max")?.let { def.maxPlayerDamage = parseInt(it) }
                    c("countRepairs")?.let { def.playerCountRepairs = parseBool(it) }
                }
                "enemyDamage" -> {
                    c("amount")?.let { def.minEnemyDamage = parseInt(it); def.maxEnemyDamage = def.minEnemyDamage }
                    c("min")?.let { def.minEnemyDamage = parseInt(it) }
                    c("max")?.let { def.maxEnemyDamage = parseInt(it) }
                    c("scaling")?.let { def.enemyDamageScaling = parseFloat(it) }
                    c("countRepairs")?.let { def.enemyCountRepairs = parseBool(it) }
                }
                "playerCrew" -> {
                    c("amount")?.let { def.minPlayerCrew = parseInt(it); def.maxPlayerCrew = def.minPlayerCrew }
                    c("min")?.let { def.minPlayerCrew = parseInt(it) }
                    c("max")?.let { def.maxPlayerCrew = parseInt(it) }
                    c("includeClonebay")?.let { def.playerCrewCountClonebay = parseBool(it) }
                }
                "enemyCrew" -> {
                    c("amount")?.let { def.minEnemyCrew = parseInt(it); def.maxEnemyCrew = def.minEnemyCrew }
                    c("min")?.let { def.minEnemyCrew = parseInt(it) }
                    c("max")?.let { def.maxEnemyCrew = parseInt(it) }
                    c("includeClonebay")?.let { def.enemyCrewCountClonebay = parseBool(it) }
                }
                "playerDeaths" -> {
                    c("amount")?.let { def.minPlayerDeaths = parseInt(it); def.maxPlayerDeaths = def.minPlayerDeaths }
                    c("min")?.let { def.minPlayerDeaths = parseInt(it) }
                    c("max")?.let { def.maxPlayerDeaths = parseInt(it) }
                    c("countNewCrew")?.let { def.playerCountNewCrew = parseBool(it) }
                    c("includeClonebay")?.let { def.playerDeathsCountClonebay = parseBool(it) }
                }
                "enemyDeaths" -> {
                    c("amount")?.let { def.minEnemyDeaths = parseInt(it); def.maxEnemyDeaths = def.minEnemyDeaths }
                    c("min")?.let { def.minEnemyDeaths = parseInt(it) }
                    c("max")?.let { def.maxEnemyDeaths = parseInt(it) }
                    c("countNewCrew")?.let { def.enemyCountNewCrew = parseBool(it) }
                    c("includeClonebay")?.let { def.enemyDeathsCountClonebay = parseBool(it) }
                }
                "req" -> {
                    var hasMin = false
                    var hasMax = false
                    c("name")?.let { def.req = it }
                    c("inverse")?.let { def.inverseReq = parseBool(it) }
                    c("lvl")?.let { hasMin = true; def.reqMinLvlFirst = parseInt(it); def.reqMinLvlSecond = def.reqMinLvlFirst }
                    c("max_lvl")?.let { hasMax = true; def.reqMaxLvlFirst = parseInt(it); def.reqMaxLvlSecond = def.reqMaxLvlFirst }
                    for (reqChild in child.children) {
                        fun r(n: String) = reqChild.getAttributeValue(n)
                        when (reqChild.name) {
                            "lvl" -> {
                                hasMin = true
                                r("amount")?.let { def.reqMinLvlFirst = parseInt(it); def.reqMinLvlSecond = def.reqMinLvlFirst }
                                r("min")?.let { def.reqMinLvlFirst = parseInt(it) }
                                r("max")?.let { def.reqMinLvlSecond = parseInt(it) }
                            }
                            "max_lvl" -> {
                                hasMax = true
                                r("amount")?.let { def.reqMaxLvlFirst = parseInt(it); def.reqMaxLvlSecond = def.reqMaxLvlFirst }
                                r("min")?.let { def.reqMaxLvlFirst = parseInt(it) }
                                r("max")?.let { def.reqMaxLvlSecond = parseInt(it) }
                            }
                        }
                    }
                    if (hasMax && !hasMin) { def.reqMinLvlFirst = Int.MIN_VALUE; def.reqMinLvlSecond = Int.MIN_VALUE }
                }
                "triggeredEventBox" -> {
                    val box = TriggeredEventBoxDefinition()
                    def.box = box
                    val loadName = c("load")
                    if (loadName != null) {
                        val src = boxDefs[loadName]
                        if (src != null) def.box = src.copy() else { def.loadBox = child; continue }
                    }
                    parseTriggeredEventBoxNode(child, def.box!!)
                }
                "timerSounds" -> {
                    c("load")?.let { loadName ->
                        val src = timerSoundDefs[loadName]
                        if (src != null) { def.timerSounds.clear(); def.timerSounds += src } else def.loadTimerSounds = loadName
                    }
                    parseTriggeredEventSounds(child, def.timerSounds)
                }
                "warningMessage" -> {
                    def.warning = TriggeredEventWarningDefinition()
                    val loadName = c("load")
                    if (loadName != null) {
                        val src = warningDefs[loadName]
                        if (src != null) def.warning = src.copy() else { def.loadWarning = child; continue }
                    }
                    parseTriggeredEventWarningNode(child, def.warning!!)
                }
            }
        }
    }

    fun parseTriggeredEventBoxNode(node: Element, box: TriggeredEventBoxDefinition) {
        node.getAttributeValue("pos")?.let { p ->
            when (p.lowercase()) { "default" -> box.boxPosition = "DEFAULT"; "scrap" -> box.boxPosition = "SCRAP"; "topright" -> box.boxPosition = "TOPRIGHT"; "combat" -> box.boxPosition = "COMBAT" }
        }
        node.getAttributeValue("priority")?.let { box.priority = parseInt(it) }
        for (child in node.children) {
            for (attr in child.attributes) box.raw["${child.name}.${attr.name}"] = attr.value
            // TriggeredEvents.cpp: r g b are /255, a is as written; a missing attribute keeps the default.
            fun colour(c: FloatArray) {
                child.getAttributeValue("r")?.let { c[0] = parseFloat(it) / 255f }
                child.getAttributeValue("g")?.let { c[1] = parseFloat(it) / 255f }
                child.getAttributeValue("b")?.let { c[2] = parseFloat(it) / 255f }
                child.getAttributeValue("a")?.let { c[3] = parseFloat(it) }
            }
            when (child.name) {
                "image" -> {
                    child.getAttributeValue("name")?.let { box.image = it }
                    child.getAttributeValue("x")?.let { box.x = parseInt(it) }
                    child.getAttributeValue("y")?.let { box.y = parseInt(it) }
                    child.getAttributeValue("w")?.let { box.w = parseInt(it) }
                    child.getAttributeValue("h")?.let { box.h = parseInt(it) }
                    child.getAttributeValue("left")?.let { box.left = parseInt(it) }
                    child.getAttributeValue("right")?.let { box.right = parseInt(it) }
                    child.getAttributeValue("top")?.let { box.top = parseInt(it) }
                    child.getAttributeValue("bottom")?.let { box.bottom = parseInt(it) }
                    colour(box.imageColor)
                }
                "image2" -> {
                    child.getAttributeValue("name")?.let { box.image2 = it }
                    colour(box.imageColor2)
                }
                "icon" -> {
                    child.getAttributeValue("name")?.let { box.imageFront = it }
                    child.getAttributeValue("x")?.let { box.frontX = parseInt(it) }
                    child.getAttributeValue("y")?.let { box.frontY = parseInt(it) }
                    colour(box.imageColorFront)
                }
                "text" -> {
                    child.getAttributeValue("type")?.let { t ->
                        when (t.lowercase()) {
                            "auto" -> box.textType = "TIME_AUTO"; "clock" -> box.textType = "TIME_CLOCK"; "seconds" -> box.textType = "TIME_SECONDS"
                            "jumps" -> box.textType = "JUMPS"; "req" -> box.textType = "REQ"; "reqprogress" -> box.textType = "REQ_PROGRESS"
                        }
                    }
                    child.getAttributeValue("x")?.let { box.textX = parseInt(it) }
                    child.getAttributeValue("y")?.let { box.textY = parseInt(it) }
                    colour(box.textColor)
                }
                "text2" -> colour(box.textColor2)
                "warning" -> {
                    child.getAttributeValue("time")?.let { box.warningTime = parseFloat(it) }
                    child.getAttributeValue("jumps")?.let { box.warningJumps = parseInt(it) }
                    child.getAttributeValue("flash")?.let { box.flash = parseBool(it) }
                }
                "tooltip" -> box.tooltip = child.getAttributeValue("id")?.let { HsTextString(it, false) } ?: HsTextString(child.text, true)
            }
        }
    }

    fun parseTriggeredEventSounds(node: Element, vec: MutableList<Pair<Float, String>>) {
        for (child in node.children) {
            if (child.name == "timerSound")
                vec += (child.getAttributeValue("t")?.let(::parseFloat) ?: 0f) to child.text
        }
        vec.sortWith { a, b -> b.first.compareTo(a.first) }
    }

    fun parseTriggeredEventWarningNode(node: Element, w: TriggeredEventWarningDefinition) {
        node.getAttributeValue("image")?.let { w.image = it }
        w.text = node.getAttributeValue("id")?.let { HsTextString(it, false) } ?: HsTextString(node.text, true)
        node.getAttributeValue("x")?.let { w.x = parseInt(it) }
        node.getAttributeValue("y")?.let { w.y = parseInt(it) }
        node.getAttributeValue("time")?.let { w.time = parseFloat(it) }
        node.getAttributeValue("centerText")?.let { w.centerText = parseBool(it) }
        node.getAttributeValue("flash")?.let { w.flash = parseBool(it) }
        node.getAttributeValue("sound")?.let { w.sound = it }
        node.getAttributeValue("useWarningLine")?.let { w.useWarningLine = parseBool(it) }
        // r g b are read as written (no /255), the alpha stays 1
        node.getAttributeValue("r")?.let { w.textColor[0] = parseFloat(it) }
        node.getAttributeValue("g")?.let { w.textColor[1] = parseFloat(it) }
        node.getAttributeValue("b")?.let { w.textColor[2] = parseFloat(it) }
    }

    companion object {
        /** CustomEventsParser::GetBaseEventName: everything before the first space. */
        @JvmStatic
        fun baseEventName(event: String): String {
            val i = event.indexOf(' ')
            return if (i < 0) event else event.substring(0, i)
        }

        /**
         * EventsParser::ParseBoolean ([xyz.znix.xftl.sector.parseBoolean]): "true", "TRUE" or "True" only.
         */
        @JvmStatic
        fun parseBool(s: String): Boolean = xyz.znix.xftl.sector.parseBoolean(s)

        /** boost::lexical_cast<int>: an invalid number is an HS parse error (logged), read here as 0. */
        @JvmStatic
        fun parseInt(s: String): Int = s.trim().toIntOrNull() ?: run {
            HS.unsupported("error", "lexical_cast<int>", s)
            0
        }

        @JvmStatic
        fun parseFloat(s: String): Float = s.trim().toFloatOrNull() ?: run {
            HS.unsupported("error", "lexical_cast<float>", s)
            0f
        }
    }
}

/** VariableModifier::ParseVariableModifierNode. */
fun VariableModifier.parse(node: Element) {
    vType = when (node.name) {
        "metaVariable" -> VariableModifier.VarType.METAVAR
        "tempVariable" -> VariableModifier.VarType.TEMP
        else -> VariableModifier.VarType.VAR
    }
    name = node.getAttributeValue("name") ?: ""
    node.getAttributeValue("op")?.let { opAttr ->
        when (opAttr.take(3)) {
            "set" -> op = VariableModifier.Op.SET
            "add" -> op = VariableModifier.Op.ADD
            "mul" -> op = VariableModifier.Op.MUL
            "div" -> op = VariableModifier.Op.DIV
            "min" -> op = VariableModifier.Op.MIN
            "max" -> op = VariableModifier.Op.MAX
        }
    }
    node.getAttributeValue("var")?.let { variable = it; minVal = 1; maxVal = 1 }
    node.getAttributeValue("val")?.let { minVal = HsEventParser.parseInt(it); maxVal = minVal }
    node.getAttributeValue("value")?.let { minVal = HsEventParser.parseInt(it); maxVal = minVal }
    node.getAttributeValue("amount")?.let { minVal = HsEventParser.parseInt(it); maxVal = minVal }
    node.getAttributeValue("min")?.let { minVal = HsEventParser.parseInt(it) }
    node.getAttributeValue("max")?.let { maxVal = HsEventParser.parseInt(it) }
    node.getAttributeValue("force")?.let { force = HsEventParser.parseBool(it) }
}
