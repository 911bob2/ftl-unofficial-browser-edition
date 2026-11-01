// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (run state, NewGame/LoadGame/SaveGame
// hooks, VariableModifier::ApplyVariables) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.sector.GameMap

/**
 * Hyperspace's event run state (the CustomEvents.cpp globals) and the profile's meta variables. Plain Kotlin data,
 * read by the Lua bridge as `Hyperspace.playerVariables` / `Hyperspace.metaVariables`.
 *
 * Saved with [writeRun]/[readRun] and [writeProfile]/[readProfile] (to be registered with hscore's HsSave).
 */
object HsEvents {
    /** `playerVariables` (run). */
    @JvmField val playerVariables = HashMap<String, Int>()

    /** `metaVariables` (profile). */
    @JvmField val metaVariables = HashMap<String, Int>()

    /** `eventAliases`. Insertion order kept (HS: unordered_map; only lookups and erasure depend on it). */
    @JvmField val eventAliases = LinkedHashMap<String, EventAlias>()

    /** `renamedBeacons` (location index -> name) and `regeneratedBeacons` (index -> event, seed). */
    @JvmField val renamedBeacons = LinkedHashMap<Int, String>()
    @JvmField val regeneratedBeacons = LinkedHashMap<Int, Pair<String, Int>>()

    /** `savedPriorityEventReq`: whether each priorityEvent's req held when the sector was generated. */
    @JvmField val savedPriorityEventReq = ArrayList<Boolean>()

    @JvmField val jumpEventQueue = ArrayList<JumpEvent>()
    @JvmField val deathEventQueue = ArrayDeque<DeathEvent>()

    /** CustomEventsParser::eventQueue. */
    @JvmField val eventQueue = ArrayDeque<EventQueueEvent>()

    /** ShipInfo augList "HIDDEN x" entries (hidden augments given by events). */
    @JvmField val hiddenAugs = LinkedHashMap<String, Int>()


    /** `advancedCheckEquipment` (a bitset: 0 choice box, 2 loadEvent, 4 variables, 6 generation). */
    @JvmField var advancedCheck = 0

    /** `g_checkCargo`. */
    @JvmField var checkCargo = false

    /** The sector being generated (its description is the "current sector" before the player arrives). */
    @JvmField var generatingSector: GameMap.SectorInfo? = null

    fun withCheck(bit: Int, block: () -> Unit) {
        val before = advancedCheck
        advancedCheck = advancedCheck or (1 shl bit)
        try { block() } finally { advancedCheck = before }
    }

    /** SetCheckCargo: the event's checkCargo, or the hyperspace.xml default. */
    fun setCheckCargo(parser: HsEventParser, event: CustomEvent?) {
        checkCargo = event?.checkCargo ?: parser.defaultCheckCargo
    }

    /**
     * The sector's SectorDescription::type as HS sees it: the first sector draws its events from STANDARD_SPACE but
     * is the civilian sector (GetCurrentCustomSector maps it back to STANDARD_SPACE).
     */
    fun descriptionType(info: GameMap.SectorInfo): String =
        if (info.columnNumber == 0 && info.type.name == "STANDARD_SPACE" && !info.isSecretSector) "CIVILIAN_SECTOR" else info.type.name

    /** GetCurrentCustomSector's name: CIVILIAN_SECTOR at level 0 is STANDARD_SPACE. */
    fun customSectorName(info: GameMap.SectorInfo): String {
        val type = descriptionType(info)
        return if (type == "CIVILIAN_SECTOR" && info.columnNumber == 0) "STANDARD_SPACE" else type
    }

    /**
     * StarMap::NewGame, before the map: the HS run state is reset, the run seed chosen (Seeds.cpp) and the initial
     * player variables applied with the first quest seed. The player's ship is created after the map here (before
     * it in the original), so the initial variables see no ship.
     */
    @JvmOverloads
    fun newGame(game: InGameState, playerShip: String? = null) {
        HsTriggeredEvents.clear() // WorldManager::CreateNewGame (TriggeredEvents.cpp)
        HsCustomFleet.clear()
        val parser = game.eventManager.hs ?: return
        jumpEventQueue.clear()
        deathEventQueue.clear()
        eventAliases.clear()
        regeneratedBeacons.clear()
        renamedBeacons.clear()
        savedPriorityEventReq.clear()
        eventQueue.clear()
        xyz.znix.xftl.hs.render.HsEventButtons.newGame()
        xyz.znix.xftl.hs.render.HsBackgroundObjects.newGame()
        xyz.znix.xftl.hs.render.HsCredits.newGame()
        HsFinalBoss.newGame()
        hiddenAugs.clear()
        playerShip?.let { shipHiddenAugs(it) }
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaAdapters.starting(game)
        playerVariables.clear()
        if (HsSeeds.enabled) HsSeeds.newGame()
        if (HsSeeds.enabled) HsSeeds.questSeed = (HsSeeds.currentSeed xor 0x282b2048) and 0x7fffffff
        // The original's player ship exists here; ours is built after the first sector. The rolls are made now (with
        // their draws and quest seed changes), and [afterPlayerShip] redoes the list with them once the ship exists,
        // for the variables multiplied by a ship's equipment (var=).
        val rolls = ArrayList<Int>()
        val metaBefore = HashMap(metaVariables)
        applyVariables(game, parser.initialPlayerVars, game.player, record = rolls)
        pendingInitial = if (game.player == null) Pair(rolls, metaBefore) else null
        // Seeds.cpp NewGame (priority 500): the quest and boss seeds restart before the original NewGame.
        HsSeeds.questSeed = 0
        HsSeeds.bossFleetSeed = 0
    }

    /**
     * ShipManager_Extend::Initialize (CustomShips.cpp): the player ship's `<customShip><hiddenAug>` entries become
     * "HIDDEN name" augments (counted per name). The original creates the ship before StarMap::NewGame, so they hold
     * for the first sector's requirements. Read from the raw `<ships>` nodes here until hs.ship owns the
     * ship's augment list (ships workstream); then this moves there.
     */
    private fun shipHiddenAugs(ship: String) {
        val def = shipDefinition(ship) ?: return
        for ((name, n) in def.hiddenAugs) hiddenAugs["HIDDEN $name"] = n
    }

    /** The parts of CustomShipDefinition the event code needs until hs.ship owns the definitions. */
    class ShipDef {
        @JvmField val hiddenAugs = LinkedHashMap<String, Int>()
        @JvmField var startingFuel = -1
        @JvmField var startingScrap = -1

        fun copy(): ShipDef = ShipDef().also { it.hiddenAugs.putAll(hiddenAugs); it.startingFuel = startingFuel; it.startingScrap = startingScrap }

        /** ParseCustomShipNode's hiddenAug (raw text, counted), startingFuel and startingScrap. */
        fun parse(node: org.jdom2.Element) {
            for (c in node.children) when (c.name) {
                "hiddenAug" -> hiddenAugs[c.text] = (hiddenAugs[c.text] ?: 0) + 1
                "startingFuel" -> c.textTrim.toIntOrNull()?.let { startingFuel = it }
                "startingScrap" -> c.textTrim.toIntOrNull()?.let { startingScrap = it }
            }
        }
    }

    /**
     * CustomShipSelect::GetDefinition([ship]) built from the raw `<ships>` nodes as ParseShipsNode does: the unnamed
     * `<customShip>`s make the default definition; a named one starts from its `load` definition, or (when new) a
     * copy of the default, or continues the existing one. Ships without a definition get the default.
     */
    fun shipDefinition(ship: String): ShipDef? {
        // hs.ship owns the definitions (CustomShipSelect::GetDefinition) once they are built.
        if (xyz.znix.xftl.hs.ship.HsShips.built) {
            val d = xyz.znix.xftl.hs.ship.HsShips.def(ship)
            return ShipDef().also { it.hiddenAugs.putAll(d.hiddenAugs); it.startingFuel = d.startingFuel; it.startingScrap = d.startingScrap }
        }
        val cfg = xyz.znix.xftl.hs.HS.config ?: return null
        val default = ShipDef()
        for (ships in cfg.nodesOf("ships")) for (n in ships.getChildren("customShip"))
            if (n.getAttribute("name") == null) default.parse(n)
        val defs = HashMap<String, ShipDef>()
        for (ships in cfg.nodesOf("ships")) for (n in ships.getChildren("customShip")) {
            val name = n.getAttributeValue("name") ?: continue
            val load = n.getAttributeValue("load")
            val def = when {
                load != null -> defs[load]?.copy() ?: defs[name] ?: ShipDef()
                else -> defs[name] ?: default.copy()
            }
            def.parse(n)
            defs[name] = def
        }
        return defs[ship] ?: default
    }

    /** The initial variables' rolls and the meta variables before them, until the player ship exists. */
    private var pendingInitial: Pair<List<Int>, Map<String, Int>>? = null

    /** The new player ship exists: the initial variables again, with the same rolls and the ship's equipment. */
    fun afterPlayerShip(game: InGameState) {
        // startingScrap/startingFuel: hs.ship's HsShipState.applyStartingResources (createNewPlayerShip) owns them.
        val (rolls, metaBefore) = pendingInitial ?: return
        pendingInitial = null
        val parser = game.eventManager.hs ?: return
        playerVariables.clear()
        metaVariables.clear(); metaVariables.putAll(metaBefore)
        applyVariables(game, parser.initialPlayerVars, game.player, replay = rolls.iterator())
    }

    /**
     * VariableModifier::ApplyVariables. [record] collects each ranged roll; with [replay] the rolls are taken from it
     * instead (no draws, no quest seed change).
     */
    @JvmOverloads
    fun applyVariables(game: InGameState, variables: List<VariableModifier>, ship: Ship?, record: MutableList<Int>? = null, replay: Iterator<Int>? = null) {
        val temp = HashMap<String, Int>()
        for (v in variables) {
            var value: Int
            if (v.minVal == v.maxVal) {
                value = v.minVal
            } else if (replay != null) {
                value = replay.next()
            } else {
                var saved = 0
                if (HsSeeds.enabled) {
                    saved = NativeRandom.next31()
                    NativeRandom.seed(HsSeeds.questSeed)
                }
                value = v.minVal + NativeRandom.next31() % (v.maxVal - v.minVal + 1)
                if (HsSeeds.enabled) {
                    HsSeeds.questSeed = NativeRandom.next31()
                    NativeRandom.seed(saved)
                }
                record?.add(value)
            }

            if (v.variable.isNotEmpty()) {
                val t = temp[v.variable]
                value *= when {
                    t != null -> t
                    ship != null -> { var r = 0; withCheck(4) { r = HsReq.hasEquipment(game, ship, v.variable) }; r }
                    else -> playerVariables[v.variable] ?: metaVariables[v.variable] ?: 1
                }
            }

            val target = when (v.vType) {
                VariableModifier.VarType.VAR -> playerVariables
                VariableModifier.VarType.METAVAR -> {
                    if (!v.force && !HsSeeds.allowMetaVars && HsSeeds.isSeededRun) continue
                    metaVariables
                }
                else -> temp
            }
            val old = target[v.name] ?: 0
            target[v.name] = when (v.op) {
                VariableModifier.Op.SET -> value
                VariableModifier.Op.ADD -> old + value
                VariableModifier.Op.MUL -> old * value
                VariableModifier.Op.DIV -> if (value == 0) { HS.unsupported("divide-by-zero", "variable", v.name); old } else old / value
                VariableModifier.Op.MIN -> minOf(old, value)
                VariableModifier.Op.MAX -> maxOf(old, value)
            }
            onVariableChanged?.invoke(v.name, target[v.name]!!)
        }
    }

    /** CustomAchievementTracker::UpdateVariableAchievements (set by the achievements code when it exists). */
    @JvmField var onVariableChanged: ((String, Int) -> Unit)? = null

    /** The profile was wiped (or is new): the initial meta variables, with a throwaway quest seed as HS does. */
    fun resetProfile(game: InGameState?, parser: HsEventParser) {
        metaVariables.clear()
        if (game == null) {
            for (v in parser.initialMetaVars) if (v.minVal == v.maxVal && v.vType == VariableModifier.VarType.METAVAR) applyStatic(v)
            return
        }
        val saved = HsSeeds.questSeed
        HsSeeds.questSeed = NativeRandom.next31()
        applyVariables(game, parser.initialMetaVars, null)
        HsSeeds.questSeed = saved
    }

    private fun applyStatic(v: VariableModifier) {
        val old = metaVariables[v.name] ?: 0
        metaVariables[v.name] = when (v.op) {
            VariableModifier.Op.SET -> v.minVal
            VariableModifier.Op.ADD -> old + v.minVal
            VariableModifier.Op.MUL -> old * v.minVal
            VariableModifier.Op.DIV -> if (v.minVal == 0) old else old / v.minVal
            VariableModifier.Op.MIN -> minOf(old, v.minVal)
            VariableModifier.Op.MAX -> maxOf(old, v.minVal)
        }
    }

    /** SoundControl::PlaySoundMix (no sound manager in the JVM checks). */
    fun playSound(game: InGameState, name: String) {
        val sounds: xyz.znix.xftl.game.SoundManager? = game.sounds
        sounds?.getSampleOrWarn(name)?.play()
    }

    // ------------------------------------------------------------------ persistence

    /** Where the save blocks go: hscore's `HsSave.register(key, scope, writer, reader)` with `run` as the scope. */
    fun interface SaveRegistrar {
        fun register(key: String, run: Boolean, writer: (Element) -> Unit, reader: (Element?) -> Unit)
    }

    /** Register the run block and the profile block under the key "events". */
    fun registerSaves(registrar: SaveRegistrar) {
        registrar.register("events", true, ::writeRun, ::readRun)
        registrar.register("events", false, ::writeProfile, ::readProfile)
    }

    /** The run block: variables (non-zero, as HS saves them), queues, aliases, beacons, seeds. */
    fun writeRun(elem: Element) {
        val vars = Element("playerVariables")
        for ((k, v) in playerVariables.toSortedMap()) if (v != 0) vars.addContent(Element("v").setAttribute("n", k).setAttribute("x", v.toString()))
        elem.addContent(vars)
        val jumps = Element("jumpEvents")
        for (j in jumpEventQueue) jumps.addContent(Element("e").setAttribute("event", j.event).setAttribute("label", j.label)
            .setAttribute("loop", j.loop.toString()).setAttribute("priority", j.priority.toString()))
        elem.addContent(jumps)
        val deaths = Element("deathEvents")
        for (d in deathEventQueue) deaths.addContent(Element("e").setAttribute("event", d.event).setAttribute("label", d.label)
            .setAttribute("jumpClear", d.jumpClear.toString()).setAttribute("thisFight", d.thisFight.toString()).setAttribute("priority", d.priority.toString()))
        elem.addContent(deaths)
        val queue = Element("eventQueue")
        for (q in eventQueue) queue.addContent(Element("e").setAttribute("event", q.event).setAttribute("label", q.label).setAttribute("seed", q.seed.toString()))
        elem.addContent(queue)
        val aliases = Element("aliases")
        for ((k, a) in eventAliases) aliases.addContent(Element("a").setAttribute("name", k).setAttribute("event", a.event)
            .setAttribute("jumpClear", a.jumpClear.toString()).setAttribute("once", a.once.toString()))
        elem.addContent(aliases)
        val beacons = Element("beacons")
        for ((i, n) in renamedBeacons) beacons.addContent(Element("renamed").setAttribute("i", i.toString()).setAttribute("name", n))
        for ((i, p) in regeneratedBeacons) beacons.addContent(Element("regenerated").setAttribute("i", i.toString())
            .setAttribute("event", p.first).setAttribute("seed", p.second.toString()))
        for (b in savedPriorityEventReq) beacons.addContent(Element("priorityReq").setAttribute("met", b.toString()))
        elem.addContent(beacons)
        val hidden = Element("hiddenAugs")
        for ((k, v) in hiddenAugs) hidden.addContent(Element("a").setAttribute("n", k).setAttribute("x", v.toString()))
        elem.addContent(hidden)
        val seeds = Element("seeds")
        HsSeeds.save(seeds)
        elem.addContent(seeds)
        val triggered = Element("triggered")
        HsTriggeredEvents.write(triggered)
        elem.addContent(triggered)
        val fleet = Element("customFleet")
        HsCustomFleet.write(fleet)
        elem.addContent(fleet)
    }

    fun readRun(elem: Element?) {
        playerVariables.clear(); jumpEventQueue.clear(); deathEventQueue.clear(); eventQueue.clear()
        eventAliases.clear(); renamedBeacons.clear(); regeneratedBeacons.clear(); savedPriorityEventReq.clear()
        hiddenAugs.clear()
        HsTriggeredEvents.read(elem?.getChild("triggered"))
        HsCustomFleet.read(elem?.getChild("customFleet"))
        elem ?: return
        fun int(e: Element, a: String) = e.getAttributeValue(a)?.toIntOrNull() ?: 0
        fun bool(e: Element, a: String) = e.getAttributeValue(a) == "true"
        elem.getChild("playerVariables")?.children?.forEach { playerVariables[it.getAttributeValue("n")] = int(it, "x") }
        elem.getChild("jumpEvents")?.children?.forEach {
            jumpEventQueue += JumpEvent(it.getAttributeValue("event") ?: "", it.getAttributeValue("label") ?: "", bool(it, "loop"), int(it, "priority"))
        }
        elem.getChild("deathEvents")?.children?.forEach {
            deathEventQueue += DeathEvent(it.getAttributeValue("event") ?: "", it.getAttributeValue("label") ?: "", bool(it, "jumpClear"), bool(it, "thisFight"), int(it, "priority"))
        }
        elem.getChild("eventQueue")?.children?.forEach {
            eventQueue += EventQueueEvent(it.getAttributeValue("event") ?: "", it.getAttributeValue("label") ?: "", int(it, "seed"))
        }
        elem.getChild("aliases")?.children?.forEach {
            eventAliases[it.getAttributeValue("name")] = EventAlias(it.getAttributeValue("event") ?: "", bool(it, "jumpClear"), bool(it, "once"))
        }
        elem.getChild("beacons")?.children?.forEach {
            when (it.name) {
                "renamed" -> renamedBeacons[int(it, "i")] = it.getAttributeValue("name") ?: ""
                "regenerated" -> regeneratedBeacons[int(it, "i")] = Pair(it.getAttributeValue("event") ?: "", int(it, "seed"))
                "priorityReq" -> savedPriorityEventReq += bool(it, "met")
            }
        }
        elem.getChild("hiddenAugs")?.children?.forEach { hiddenAugs[it.getAttributeValue("n")] = int(it, "x") }
        elem.getChild("seeds")?.let { HsSeeds.load(it) }
    }

    // TODO(events, from hscore): metaVariables/playerVariables are HashMaps, so a profile re-saved after loading can list
    // the <v> elements in another order (hsCoreProbe notes it); a sorted or insertion-ordered map would keep saves stable.
    /** The profile block: meta variables (LoadMetaVars/SaveMetaVars). */
    fun writeProfile(elem: Element) {
        for ((k, v) in metaVariables.toSortedMap()) elem.addContent(Element("v").setAttribute("n", k).setAttribute("x", v.toString()))
    }

    /** Null (a new profile, or one without the block) leaves the variables to [resetProfile] ([profileNeedsReset]). */
    fun readProfile(elem: Element?) {
        metaVariables.clear()
        profileNeedsReset = elem == null
        elem?.children?.forEach { metaVariables[it.getAttributeValue("n")] = it.getAttributeValue("x")?.toIntOrNull() ?: 0 }
    }

    /**
     * The profile read has no meta variables: ScoreKeeper::WipeProfile's initial meta variables are due once the
     * event data (hyperspace.xml's `<metaVariable>`s) is loaded ([HsEventFiles.afterEvents]).
     */
    @JvmField var profileNeedsReset = false
}
