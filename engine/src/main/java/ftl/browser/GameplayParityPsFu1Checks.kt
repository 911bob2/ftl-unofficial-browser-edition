package ftl.browser

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.systems.Engines
import xyz.znix.xftl.systems.Sensors
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep follow-up FU1 (B1-C1): a crew member at a capped system. Every mode (vanilla, F2, F3).
 * - CrewMember::SetCurrentSystem (1.6.14 0x47b190, 1.6.9 0x46e210) sets bActiveManning and iActiveManned (typing, the
 *   manning bonus, skill gain) only while ShipSystem::BlockedBoosted(true) (1.6.14 0x63b8a0) lets it: a status cap
 *   below the level blocks, a cap at the level does not; for the sensors and doors (bLevelBoostable) a cap below the
 *   level plus one blocks. A capped engine gives no engine-manning evasion; the box hides a main system's crew slot and
 *   shows the sensors' ion bar (RenderPowerBoxes 1.6.14 FUN_0063c310); the hover box has no "unmanned" line.
 * - CrewMember::OnLoop (1.6.14 0x482a80) walks a crew member who is not busy to the room's free console whatever state
 *   its system is in, so crew in a capped room stand idle at its console.
 * - In a nebula (sensors capped at 0) the crew member at the sensors stands idle at the console (O-B1-2).
 */
internal object GameplayParityPsFu1Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("cappedEngines") { cappedEngines(main, container) }
            case("cappedSensors") { cappedSensors(main, container) }
            case("walkToConsole") { walkToConsole(main, container) }
            case("nebulaSensorsCrew") { nebulaSensorsCrew(main, container) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-fu1 ok: " + results.joinToString(", ")
    }

    private fun fresh(main: MainGame, container: GameContainer): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        // A start beacon in a nebula caps the sensors (StatusEffect::GetNebulaEffect); start from no status at all.
        state.currentBeacon.powerLimitEffects.clear()
        state.currentBeacon.powerLossEffects.clear()
        state.player.updateScriptedPowerLimits()
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        return state
    }

    private fun settle(state: InGameState, frames: Int) = repeat(frames) { state.updateGameState(DT) }

    private fun atConsole(crew: AbstractCrew, sys: AbstractSystem): Boolean =
        crew.room === sys.room && crew.standingPosition?.let { p -> sys.configuration.computerPoint?.let { p posEq it } } == true

    /** A crew member at [sys]'s console: the one there, else one walked there from another room. */
    private fun crewAt(state: InGameState, sys: AbstractSystem): LivingCrew {
        sys.manningCrew?.let { return it }
        val room = sys.room ?: error("${sys.blueprint.name} has no room")
        // Someone already at the console of a system that is not manned now (the start weapons have no power).
        room.crew.firstOrNull { it is LivingCrew && it.mode == AbstractCrew.SlotType.CREW && atConsole(it, sys) }
            ?.let { return it as LivingCrew }
        val crew = state.player.crew.filterIsInstance<LivingCrew>()
            .filter { it.mode == AbstractCrew.SlotType.CREW && it.room !== room && it.room.system !is xyz.znix.xftl.systems.Piloting }
            .firstOrNull { it.setTargetRoom(room) } ?: error("nobody can walk to the ${sys.blueprint.name}")
        var frames = 0
        while (!atConsole(crew, sys) && frames++ < 600) state.updateGameState(DT)
        check(atConsole(crew, sys)) { "${crew.info.name} did not reach the ${sys.blueprint.name} console" }
        settle(state, 2)
        return crew
    }

    private fun unmannedLine(state: InGameState, sys: AbstractSystem): Boolean {
        val tip = xyz.znix.xftl.game.SystemButtonTooltip(state, sys).getText()
        return tip.lines().any { it.trim() == "-" + state.translator["unmanned"] }
    }

    /** Engines capped at their level stay boosted; one bar lower, the crew member stands idle with no evasion bonus. */
    private fun cappedEngines(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        val engines = state.player.engines ?: return "capped engines: no engines under these blueprints"
        if (engines.energyLevels < 2) return "capped engines: engines at level ${engines.energyLevels}"
        val crew = crewAt(state, engines)
        check(engines.manningCrew === crew && engines.getBoostSkillLevel(Skill.ENGINES) != null) { "Set-up: engines not manned" }
        val level = engines.energyLevels

        engines.scriptedPowerLimit = level
        settle(state, 3)
        check(engines.manningCrew === crew && engines.getBoostSkillLevel(Skill.ENGINES) != null) {
            "engines capped at their level $level are not manned (${crew.currentAction})"
        }
        check(engines.boxCrewSlotShown) { "engines capped at their level: the box hides the crew slot" }

        engines.scriptedPowerLimit = level - 1
        settle(state, 3)
        check(engines.manningCrew == null && engines.getBoostSkillLevel(Skill.ENGINES) == null) {
            "engines capped at ${level - 1} of $level still manned (${crew.currentAction})"
        }
        check(atConsole(crew, engines) && crew.currentAction == AbstractCrew.Action.IDLE) {
            "the engineer left the console or is ${crew.currentAction}, not idle"
        }
        val power = engines.powerSupplied
        val table = if (power > 0) Engines.EVASIONS.getOrNull(power - 1) ?: 0 else 0
        check(engines.evasion == table) { "capped engines give ${engines.evasion} evasion at power $power, the table says $table" }
        check(!engines.boxCrewSlotShown) { "capped engines: the box still draws the crew slot" }
        check(!unmannedLine(state, engines)) { "capped engines: the hover box says unmanned" }

        engines.scriptedPowerLimit = null
        settle(state, 3)
        check(engines.manningCrew === crew) { "engines not manned again once the cap ends" }
        return "capped engines (cap $level manned, ${level - 1} idle, evasion ${engines.evasion} again)"
    }

    /** Sensors (level boostable): a cap at the level blocks the boost, one above it does not. */
    private fun cappedSensors(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        val sensors = state.player.systems.filterIsInstance<Sensors>().firstOrNull() ?: return "capped sensors: no sensors"
        val crew = crewAt(state, sensors)
        val level = sensors.energyLevels
        check(sensors.manningCrew === crew && sensors.effectivePower == level + 1) {
            "Set-up: sensors manned ${sensors.manningCrew != null}, power ${sensors.effectivePower}"
        }

        sensors.scriptedPowerLimit = level
        settle(state, 3)
        check(sensors.manningCrew == null && sensors.effectivePower == level) {
            "sensors capped at their level $level: manned ${sensors.manningCrew != null}, power ${sensors.effectivePower}"
        }
        check(atConsole(crew, sensors) && crew.currentAction == AbstractCrew.Action.IDLE) { "the crew member left the sensors or is ${crew.currentAction}" }
        check(sensors.boxCrewSlotShown && sensors.manningBarName == "ion") {
            "sensors capped at their level: crew slot ${sensors.boxCrewSlotShown}, bar ${sensors.manningBarName} (want ion)"
        }

        sensors.scriptedPowerLimit = level + 1
        settle(state, 3)
        check(sensors.manningCrew === crew && sensors.effectivePower == level + 1 && sensors.manningBarName == "on") {
            "sensors capped at ${level + 1}: manned ${sensors.manningCrew != null}, power ${sensors.effectivePower}, bar ${sensors.manningBarName}"
        }
        sensors.scriptedPowerLimit = null
        return "capped sensors (cap $level idle with the ion bar, ${level + 1} manned)"
    }

    /**
     * A crew member at another tile of a capped room (engines one bar under their level) walks to its free console and
     * stands idle there, manned again once the cap ends; the same at the console of a system without power (weapons).
     */
    private fun walkToConsole(main: MainGame, container: GameContainer): String {
        val out = ArrayList<String>()
        run {
            val state = fresh(main, container)
            val engines = state.player.engines ?: return@run
            if (engines.energyLevels < 2) return@run
            val crew = crewAt(state, engines)
            out += walkBack(state, engines, crew, "capped engines") { engines.scriptedPowerLimit = engines.energyLevels - 1 }
            engines.scriptedPowerLimit = null
            settle(state, 3)
            check(engines.manningCrew === crew) { "engines not manned once the cap ends" }
        }
        run {
            val state = fresh(main, container)
            val weapons = state.player.weapons ?: return@run
            val crew = crewAt(state, weapons)
            out += walkBack(state, weapons, crew, "unpowered weapons") {
                if (weapons.isMannableBy(crew)) weapons.scriptedPowerLimit = 0
            }
        }
        return "walked back to the console (" + out.joinToString(", ") + ")"
    }

    private fun walkBack(state: InGameState, sys: AbstractSystem, crew: LivingCrew, label: String, block: () -> Unit): String {
        val room = sys.room!!
        val console = sys.configuration.computerPoint!!
        val other = (0 until room.width).flatMap { x -> (0 until room.height).map { y -> ConstPoint(x, y) } }
            .firstOrNull { !(it posEq console) && room.isSlotFree(it, AbstractCrew.SlotType.CREW, crew) }
            ?: return "$label: no free tile"
        block()
        check(!sys.isMannableBy(crew)) { "Set-up: $label still mannable" }
        check(crew.setTargetRoom(room, other)) { "Set-up: cannot send the crew member to $other" }
        var frames = 0
        while (crew.standingPosition?.let { it posEq other } != true && frames++ < 400) state.updateGameState(DT)
        check(crew.standingPosition?.let { it posEq other } == true) { "Set-up: the crew member never stood at $other ($label)" }
        frames = 0
        while (!atConsole(crew, sys) && frames++ < 400) state.updateGameState(DT)
        check(atConsole(crew, sys)) { "$label: the crew member stayed at $other instead of walking to the console" }
        settle(state, 3)
        check(crew.currentAction == AbstractCrew.Action.IDLE && sys.manningCrew == null) {
            "$label: at the console ${crew.currentAction}, manned ${sys.manningCrew != null}"
        }
        return "$label in $frames frames, idle"
    }

    /** NEBULA_REBEL: the crew member at the sensors stays at the console, idle, with no crew slot on the box. */
    private fun nebulaSensorsCrew(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        state.debugFlags.noEnemyFire.set = true
        val sensors = state.player.systems.filterIsInstance<Sensors>().firstOrNull() ?: return "nebula sensors crew: no sensors"
        val crew = crewAt(state, sensors)
        check(sensors.manningCrew === crew) { "Set-up: sensors not manned" }
        xyz.znix.xftl.hs.render.HsConsole.run(state, "EVENT NEBULA_REBEL")
        repeat(10) { main.update(container, DT) }
        BrowserDiagnostics.closeBoxes(main, container)
        val game = main.currentState as InGameState
        if (game.isPaused && !game.shipUI.isWindowOpen) game.toggleQuickPause()
        settle(game, 5)
        check(sensors.scriptedPowerLimit == 0) { "NEBULA_REBEL did not cap the sensors: ${sensors.scriptedPowerLimit}" }
        check(atConsole(crew, sensors) && crew.currentAction == AbstractCrew.Action.IDLE && sensors.manningCrew == null) {
            "in the nebula the crew member at the sensors is ${crew.currentAction}, at the console ${atConsole(crew, sensors)}"
        }
        check(!sensors.boxCrewSlotShown) { "in the nebula the sensors box shows the crew slot" }
        return "nebula sensors: crew idle at the console"
    }
}
