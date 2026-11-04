// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1.23.2: the save hooks of src/game/Gameplay/SpaceId.cpp, Ship/CustomCrew.cpp
// (CrewMember::SaveState), Gameplay/World/CrewAbilities.cpp (ActivatedPower(Resource)::SaveState), Ship/StatBoost.cpp
// (StatBoost::Save), Ship/CustomAugments.cpp, Ship/CustomShips.cpp, Ship/OxygenWithoutSystem.cpp,
// Ship/ShipSystems/CustomSystems.cpp, Ship/ShipSystems/TemporalSystem.cpp (ShipManager::ExportShip),
// Gameplay/World/{CustomEvents,CustomSectors,Infinite,Seeds,TriggeredEvents}.cpp (StarMap::SaveGame),
// Gameplay/CustomBackgroundObject.cpp, UserInterface/EventButtons.cpp and UserInterface/CustomStore.cpp (Store::SaveStore).
package xyz.znix.xftl.vanillasave

/**
 * What a Hyperspace run file holds beyond the original's (docs/SAVES.md, "Saves from the original game"). Hyperspace
 * writes its data inside the original's layout, where its hooks run: before or after the original function, in the
 * order of the hook chain (a higher priority sits closer to the original; equal priorities by install order). The
 * order below was read from Hyperspace's source and checked on real Multiverse saves.
 *
 * Floats go through FileHelper::writeFloat, which stores the value times 1000 as an int: [milli].
 */
class NativeHsRun {
    var nextSpaceId = 0
    /** Hyperspace system ids of the blueprint's starting systems the ship no longer has. */
    val removedSystems = ArrayList<Int>()
    /** The `HIDDEN ...` augments, one entry per copy. */
    val hiddenAugments = ArrayList<String>()
    val crew = ArrayList<Crew>()

    // StarMap::SaveGame
    val replacedSectors = ArrayList<Triple<Int, String, Pair<Int, Int>>>() // when, sector list, (level, y)
    var worldLevel = 0
    var currentSeed = 0
    var customSeed = false
    var worldRng = 0 to 0 // seed, counter
    var secretRng = 0 to 0
    var questSeed = 0
    var bossFleetSeed = 0
    var sectorType = ""
    val jumpEvents = ArrayList<JumpEvent>()
    val deathEvents = ArrayList<DeathEvent>()
    val aliases = LinkedHashMap<String, Alias>()
    val regeneratedBeacons = LinkedHashMap<Int, Pair<String, Int>>()
    val renamedBeacons = LinkedHashMap<Int, String>()
    val priorityEventReq = ArrayList<Boolean>()
    var backgroundObjects = 0
    val eventButtons = ArrayList<Int>()
    val playerVariables = LinkedHashMap<String, Int>()
    var alreadyWon = false
    var alreadyWonCustom = false
    var gameOverText = ""
    var gameOverCreditsText = ""
    var creditsBackground = ""
    var creditsMusic = ""
    var customVictoryType = ""
    val triggeredEvents = ArrayList<Triggered>()
    val eventQueue = ArrayList<Triple<String, String, Int>>() // event, label, seed
    /** Beacons whose store is one of Hyperspace's own (CustomStore): the import keeps the beacon, not the store. */
    val customStoreBeacons = ArrayList<Int>()

    class Crew {
        var selfId = 0
        var health = 0f
        var maxHealth = 0f
        val powers = ArrayList<Int>() // power definition indices
        var originalRace = ""
        var originalColorRace = ""
        var statBoosts = 0
    }

    class JumpEvent(val event: String, val label: String, val loop: Boolean, val priority: Int)
    class DeathEvent(val event: String, val label: String, val jumpClear: Boolean, val thisFight: Boolean, val priority: Int)
    class Alias(val event: String, val jumpClear: Boolean, val once: Boolean)
    class Triggered(val name: String, val defIdx: Int, val seed: Int, val loops: Int, val timer: Pair<Float, Float>?, val values: IntArray, val triggered: Boolean)

    companion object {
        @JvmStatic
        fun milli(v: Int): Float = v / 1000f
    }
}

/** The game data a run's layout depends on. Without mods only [layout] and [isShipBlueprint] are asked. */
interface NativeContent {
    /** Tiles per room and the number of doors of a ship blueprint. */
    fun layout(blueprint: String): Pair<List<Int>, Int>
    fun isShipBlueprint(name: String): Boolean = false

    /** The original writes the crystal lockdown timers for crystal crew; Hyperspace makes a defined race a plain crew member. */
    fun hasCrystalTimers(race: String): Boolean = race == "crystal"

    /** Hyperspace: the custom system ids after the original's (SYS_CUSTOM_FIRST to the last). */
    fun customSystemCount(): Int = 0

    /** Hyperspace: a ship without an oxygen system gets the dummy one (`<oxygenWithoutSystem>`). */
    fun dummyOxygen(): Boolean = false

    fun backgroundHasHover(def: Int): Boolean = false
    fun backgroundHasInactive(def: Int): Boolean = false

    /** Hyperspace: a triggered event of this definition has a timer (`<time>`). */
    fun triggeredHasTimer(def: Int): Boolean = false
}

/** The Hyperspace parts of the run layout, called by [VanillaSaveParser] at the places Hyperspace writes them. */
internal class NativeHsReader(private val r: VanillaSaveReader, private val content: NativeContent, val run: NativeHsRun) {
    private fun count(max: Int): Int {
        val n = r.int()
        require(n in 0..max) { "Bad count $n at ${r.pos}" }
        return n
    }

    /** ScoreKeeper::SaveGame, after the original. */
    fun afterStateVars() {
        run.nextSpaceId = r.int()
    }

    /** ShipManager::ExportShip, CustomSystems.cpp before the original. */
    fun beforeShip(player: Boolean) {
        val ids = (0 until count(64)).map { r.int() }
        if (player) run.removedSystems.addAll(ids)
    }

    /** CrewMember::SaveState, before the original's part (which starts after the five career counters). */
    fun crew(player: Boolean): NativeHsRun.Crew {
        val c = NativeHsRun.Crew()
        c.selfId = NativeHsRun.milli(r.int()).toInt()
        c.health = NativeHsRun.milli(r.int())
        c.maxHealth = NativeHsRun.milli(r.int())
        r.int() // noSlot
        repeat(4) { r.int() } // passive heal and death timers (goal, time)
        repeat(count(64)) { c.powers.add(power()) }
        repeat(count(64)) { repeat(7) { r.int() } } // ActivatedPowerResource::SaveState
        c.originalColorRace = r.string()
        c.originalRace = r.string()
        repeat(4) { r.int() } // custom teleport: teleporting, ship, room, slot
        c.statBoosts = count(4096)
        repeat(c.statBoosts) { statBoost() }
        if (player) run.crew.add(c)
        return c
    }

    private fun power(): Int {
        val def = r.int()
        // CrewAbilities.cpp ActivatedPower::SaveState: enabled, modified charges, cooldown (2), temporary duration (2),
        // charges (2), room, ship, activated, temporary active, done, temporary done
        repeat(14) { r.int() }
        repeat(3) { if (r.string().isNotEmpty()) animation() } // effect, temporary effect, finish effect
        repeat(count(64)) { r.string(); animation() }
        repeat(4) { r.int() } // effect position, world position
        return def
    }

    /** Animation::SaveState: playing, looping, frame, progress, scale, x, y. */
    private fun animation() = repeat(7) { r.int() }

    private fun statBoost() = repeat(6) { r.int() } // definition, stacks, crew source, ship source, timer goal, time

    /** ShipManager::ExportShip, the hooks after the original, innermost first. */
    fun afterShip(player: Boolean, hasOxygen: Boolean, roomTiles: List<Int>) {
        // CustomAugments.cpp (priority 100)
        val hidden = (0 until count(1024)).map { r.string() }
        if (player) run.hiddenAugments.addAll(hidden)
        // TemporalSystem.cpp, then CustomSystems.cpp (the systems after the original's)
        if (r.bool()) completeSystem()
        repeat(content.customSystemCount()) { if (r.bool()) completeSystem() }
        // OxygenWithoutSystem.cpp
        if (!hasOxygen && content.dummyOxygen()) {
            r.int()
            repeat(count(512)) { r.int() }
            r.int(); r.int()
        }
        // CustomShips.cpp: per room its stat boosts and erosion
        for (tiles in roomTiles) {
            repeat(count(4096)) { statBoost() }
            r.int(); r.int(); r.int() // erosion timer, speed, repair multiplier
            if (r.bool()) roomAnimation()
        }
    }

    /** ShipSystem::CompleteSave: ten values, then the system's own SaveState (the three temporary capacity values). */
    private fun completeSystem() = repeat(13) { r.int() }

    private fun roomAnimation() {
        repeat(5) { r.int() } // layer, x, y, w, h
        if (r.bool()) { r.string(); animation() }
        repeat(count(4096)) { r.string(); animation() }
    }

    /** StarMap::SaveGame, the hooks before the original, outermost first. */
    fun beforeStarMap() {
        // CustomEvents.cpp
        repeat(count(10_000)) { run.jumpEvents.add(NativeHsRun.JumpEvent(r.string(), r.string(), r.bool(), r.int())) }
        repeat(count(10_000)) { run.deathEvents.add(NativeHsRun.DeathEvent(r.string(), r.string(), r.bool(), r.bool(), r.int())) }
        repeat(count(10_000)) { val name = r.string(); run.aliases[name] = NativeHsRun.Alias(r.string(), r.bool(), r.bool()) }
        repeat(count(10_000)) { val i = r.int(); run.regeneratedBeacons[i] = r.string() to r.int() }
        repeat(count(10_000)) { val i = r.int(); run.renamedBeacons[i] = r.string() }
        repeat(count(10_000)) { run.priorityEventReq.add(r.bool()) }
        repeat(count(10_000)) {
            r.int() // location
            repeat(count(1_000)) {
                val def = r.int()
                repeat(4) { r.int() } // x, y, reqActive, active
                animState()
                if (content.backgroundHasHover(def)) animState()
                if (content.backgroundHasInactive(def)) animState()
                repeat(count(1_000)) { repeat(6) { r.int() } } // action timers
                run.backgroundObjects++
            }
        }
        repeat(count(10_000)) { run.eventButtons.add(r.int()) }
        repeat(count(1_000_000)) { val name = r.string(); run.playerVariables[name] = r.int() }
        run.alreadyWon = r.bool()
        run.alreadyWonCustom = r.bool()
        run.gameOverText = r.string()
        run.gameOverCreditsText = r.string()
        run.creditsBackground = r.string()
        run.creditsMusic = r.string()
        run.customVictoryType = r.string()
        run.sectorType = r.string()
        // CustomSectors.cpp
        repeat(count(1_000)) {
            val whenLevel = r.int()
            repeat(count(1_000)) { run.replacedSectors.add(Triple(whenLevel, r.string(), r.int() to r.int())) }
        }
        // Infinite.cpp
        run.worldLevel = r.int()
        // Seeds.cpp (priority 0), then priority 500
        run.currentSeed = r.int()
        run.customSeed = r.bool()
        run.worldRng = r.int() to r.int()
        run.secretRng = r.int() to r.int()
        run.questSeed = r.int()
        run.bossFleetSeed = r.int()
    }

    /** An animation's frame and tracker as CustomBackgroundObject::Save writes them. */
    private fun animState() = repeat(7) { r.int() }

    /** Store::SaveStore: whether the store is Hyperspace's; a custom store is read and left behind. */
    fun storeIsCustom(beacon: Int): Boolean {
        if (!r.bool()) return false
        repeat(count(64)) {
            repeat(count(64)) {
                r.int(); r.string()
                repeat(count(64)) {
                    repeat(count(64)) {
                        if (r.int() != -1) { r.string(); repeat(5) { r.int() } }
                    }
                }
            }
        }
        repeat(count(64)) { repeat(3) { r.int() } }
        if (r.bool()) r.int()
        r.int(); r.int() // purchase limit, purchased
        run.customStoreBeacons.add(beacon)
        return true
    }

    /** StarMap::SaveGame, TriggeredEvents.cpp (priority 100) after the original. */
    fun afterStarMap() {
        repeat(count(10_000)) {
            val name = r.string()
            val def = r.int()
            val seed = r.int()
            val loops = r.int()
            val timer = if (content.triggeredHasTimer(def)) NativeHsRun.milli(r.int()) to NativeHsRun.milli(r.int()) else null
            val values = IntArray(16) { r.int() }
            run.triggeredEvents.add(NativeHsRun.Triggered(name, def, seed, loops, timer, values, r.bool()))
        }
        repeat(count(10_000)) { run.eventQueue.add(Triple(r.string(), r.string(), r.int())) }
    }
}
