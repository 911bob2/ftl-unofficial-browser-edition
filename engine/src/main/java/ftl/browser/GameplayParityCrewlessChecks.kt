package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Player report 201 (release 2.2.x): a ship without a starting crew ended its run at once with "Your crew is dead".
 * The original's rule, each part against the decompilation:
 * - ShipManager::AddInitialCrew (1.6.14 0x4ae2f0, 1.6.9 0x4a3610) sets bAutomated (ShipManager +0x4d9) when the
 *   blueprint gave it no crew, for the player's ship as for any; nothing but Hyperspace's crew list and its
 *   `autoShipForce` changes the flag afterwards.
 * - CompleteShip::DeadCrew (0x40a2f0): no crew counted and the ship not automated. So an automated ship never has
 *   dead crew, and a crewed ship still loses when its last crew member is dead and no clone bay can bring one back.
 * - ShipManager's crew loop (0x4aa670) starts every system as manned on an automated ship (bManned, +0x45): it has
 *   a pilot (full dodge, the FTL drive charges), its systems take the manning boost and repair themselves.
 * Two neighbours (201b): the crew screen's Dismiss needs more than one crew member ([dismissNeedsTwoCrew]), and
 * Hyperspace gives an automated ship without crew a crew member when a random one is asked for ([autoShipRandomCrew]).
 */
internal object GameplayParityCrewlessChecks {
    /** Above this many player ships [everyPlayerShipStarts] leaves the full list to the ships job. */
    private const val ALL_SHIPS_LIMIT = 60

    private const val DT = 0.05f
    private var stage = ""

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            stage = ""
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name" + (if (stage.isEmpty()) "" else " ($stage)") + ": " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("everyPlayerShipStarts") { everyPlayerShipStarts(main, container) }
            case("crewlessBlueprintIsAutomated") { crewlessBlueprintIsAutomated(main, container) }
            case("automatedShipHasNoDeadCrew") { automatedShipHasNoDeadCrew(main, container) }
            case("deadCrewEndsTheRun") { deadCrewEndsTheRun(main, container) }
            case("cloneBayAndCrewAway") { cloneBayAndCrewAway(main, container) }
            case("automatedShipFlies") { automatedShipFlies(main, container) }
            case("automatedShipShowsItsManning") { automatedShipShowsItsManning(main, container) }
            for (touch in listOf(false, true)) case("dismissNeedsTwoCrew ${if (touch) "touch" else "mouse"}") { dismissNeedsTwoCrew(main, container, touch) }
            case("autoShipRandomCrew") { autoShipRandomCrew(main, container) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "crewless ships ok: " + results.joinToString(", ")
    }

    /** Runs the game for [seconds], answering any box that opens. */
    private fun play(main: MainGame, container: GameContainer, seconds: Float) {
        var steps = (seconds / DT).toInt()
        while (steps-- > 0) {
            val state = main.currentState as? InGameState ?: return
            if (state.shipUI.diagnosticDialogue != null) BrowserDiagnostics.closeBoxes(main, container)
            main.update(container, DT)
        }
    }

    private fun ownCrewAlive(state: InGameState): Int =
        (state.player.crew + (state.enemy?.crew ?: emptyList())).count { it is LivingCrew && it.ownerShip === state.player && it.health > 0f }

    /** What [afterStart] found: the run's end if it is over, the living crew of the player's own, the automated flag. */
    internal class AfterStart(@JvmField val over: String?, @JvmField val ownCrewAlive: Int, @JvmField val automated: Boolean)

    /**
     * The run that was just started, two seconds after its start boxes are answered. The ships job asks this for every
     * ship it starts (BrowserHsShipsDiagnostics.start), this job for the ships below.
     */
    internal fun afterStart(main: MainGame, container: GameContainer): AfterStart {
        BrowserDiagnostics.closeBoxes(main, container)
        play(main, container, 2f)
        val state = main.currentState as InGameState
        val over = if (state.browserRunEnded || state.shipUI.isGameOverOpen) (state.shipUI.gameOverWindow?.outcome?.toString() ?: "ended") else null
        return AfterStart(over, ownCrewAlive(state), state.player.isAutomated)
    }

    /** The data gives [ship] no starting crew: none in the blueprint and none in Hyperspace's crew list. */
    private fun crewlessByData(main: MainGame, ship: String): Boolean {
        val blueprint = (main.gameContent.blueprintManager.blueprints[ship] as xyz.znix.xftl.LazyShipBlueprint).real
        if (blueprint.initialCrew.any { it.amount > 0 }) return false
        return !xyz.znix.xftl.hs.HS.active || xyz.znix.xftl.hs.ship.HsShips.def(ship).crewList.isEmpty()
    }

    /**
     * Player ships (each `PLAYER_SHIP_*` blueprint and every hangar layout) start a run that is not over two seconds
     * after its start boxes are answered; one that starts with no living crew of its own is automated, and the ships
     * the data gives no crew are exactly those. With few ships (vanilla, plain Hyperspace: 30) every one is started
     * here. With many (Multiverse: 342) this job starts the ships the data gives no crew or an `autoShipForce`, and the
     * ships job (tests/hs-ships.mjs, hs-ships-F3) asks the same of every ship it starts: 342 more runs in this page
     * took the job's memory from 4.7 GB to 6.3 GB, its cap (report 201).
     */
    private fun everyPlayerShipStarts(main: MainGame, container: GameContainer): String {
        val all = BrowserHsShipsDiagnostics.playerShips(main).split('\n').filter { it.isNotEmpty() }
        check(all.isNotEmpty()) { "No player ship in the loaded data" }
        val byData = all.filter { crewlessByData(main, it) }
        val forced = if (!xyz.znix.xftl.hs.HS.active) emptyList() else all.filter { xyz.znix.xftl.hs.ship.HsShips.def(it).forceAutomated != null }
        val ships = if (all.size <= ALL_SHIPS_LIMIT) all else all.filter { it in byData || it in forced }
        val ended = ArrayList<String>()
        val crewless = ArrayList<String>()
        val notAutomated = ArrayList<String>()
        val broken = ArrayList<String>()
        var started = 0
        for (ship in ships) {
            stage = ship
            val found = try {
                main.startDiagnosticGame(ship, Difficulty.NORMAL)
                afterStart(main, container)
            } catch (e: Throwable) {
                broken += ship + " (" + (e.message ?: e::class.simpleName)?.take(80) + ")"
                continue
            }
            started++
            if (found.over != null) ended += ship + " (" + found.over + ")"
            if (found.ownCrewAlive == 0) {
                crewless += ship
                if (!found.automated) notAutomated += ship
            }
        }
        stage = ""
        check(broken.isEmpty()) { "${broken.size} ships did not start or kept a box open: " + broken.joinToString(", ") }
        check(ended.isEmpty()) { "The run is over two seconds after its start with ${ended.size} of $started ships: " + ended.joinToString(", ") }
        check(notAutomated.isEmpty()) { "Ships that start without crew and are not automated: " + notAutomated.joinToString(", ") }
        check(crewless == byData) { "Ships that start without living crew: $crewless; the data gives no crew to $byData" }
        val which = if (ships.size == all.size) "$started player ships start and go on"
        else "$started of ${all.size} player ships start and go on (those the data gives no crew or an autoShipForce; the ships job starts every one)"
        return which + (if (crewless.isEmpty()) "" else ", ${crewless.size} without crew (" + crewless.joinToString(" ") + ")")
    }

    /**
     * The start rule in every mode, with a blueprint that has no crew: the original's own automated scouts as the
     * player's ship. The ship is automated, and its run goes on.
     */
    private fun crewlessBlueprintIsAutomated(main: MainGame, container: GameContainer): String {
        val manager = main.gameContent.blueprintManager
        val name = listOf("AUTO_BASIC", "AUTO_ASSAULT", "AUTO_BOMBER", "AUTO_SCOUT").firstOrNull { manager.getOrNull(it) != null }
            ?: return "crewless blueprint: the loaded data has none of the original's automated scouts"
        stage = name
        main.startDiagnosticGame(name, Difficulty.NORMAL)
        BrowserDiagnostics.closeBoxes(main, container)
        val state = main.currentState as InGameState
        check(state.player.type.initialCrew.sumOf { it.amount } == 0) { "Set-up: $name has blueprint crew" }
        check(state.player.isAutomated) { "A player ship whose blueprint has no crew ($name) is not automated" }
        play(main, container, 3f)
        check(!state.browserRunEnded && !state.shipUI.isGameOverOpen) { "The run of a crewless ship ($name) is over after three seconds (${state.shipUI.gameOverWindow?.outcome})" }
        // A crewed blueprint is not automated.
        val crewed = BrowserGameplayParityDiagnostics.fresh(main, container)
        check(!crewed.player.isAutomated && ownCrewAlive(crewed) > 0) { "The default ship starts automated" }
        return "a crewless blueprint ($name) starts automated"
    }

    /**
     * CompleteShip::DeadCrew is false for an automated ship: with nobody aboard, and after everybody it took on died.
     * The flag alone decides: the same ship without it loses at once.
     */
    private fun automatedShipHasNoDeadCrew(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        player.isAutomated = true
        val keep = player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === player }
        player.crew.filter { it !== keep }.toList().forEach { it.removeFromShip() }
        state.updatePlayerCrew()
        // Its last crew member dies; the ship has no clone bay.
        stage = "the last crew member dies"
        check(player.clonebay == null) { "Set-up: the default ship has a clone bay" }
        keep.health = 0f
        play(main, container, 6f)
        // The run first: once it is over the game stands still and the body never leaves the list.
        check(!state.browserRunEnded && !state.shipUI.isGameOverOpen) {
            "An automated ship whose crew died lost its run (${state.shipUI.gameOverWindow?.outcome})"
        }
        check(ownCrewAlive(state) == 0 && !player.crew.contains(keep)) { "Set-up: the crew member did not die" }
        // Without the flag the same ship has dead crew.
        stage = "not automated"
        player.isAutomated = false
        play(main, container, 1f)
        check(state.shipUI.gameOverWindow?.outcome == GameOverWindow.Outcome.LOOSE_CREW) {
            "The same ship, not automated, goes on without crew (${state.shipUI.gameOverWindow?.outcome})"
        }
        return "an automated ship has no dead crew"
    }

    /** A crewed ship whose crew all die still loses, as before. */
    private fun deadCrewEndsTheRun(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        check(!state.player.isAutomated && state.player.clonebay == null) { "Set-up: the default ship is automated or has a clone bay" }
        play(main, container, 1f)
        check(!state.browserRunEnded) { "Set-up: the run of the default ship is over" }
        state.player.crew.filterIsInstance<LivingCrew>().forEach { it.health = 0f }
        play(main, container, 6f)
        check(state.shipUI.gameOverWindow?.outcome == GameOverWindow.Outcome.LOOSE_CREW) {
            "A crewed ship whose crew all died goes on (${state.shipUI.gameOverWindow?.outcome}, ended ${state.browserRunEnded})"
        }
        return "dead crew ends a crewed ship's run"
    }

    /**
     * The count behind DeadCrew, unchanged: bodies waiting in a working clone bay count, a crew member alive aboard
     * the enemy counts, and without either the run is lost.
     */
    private fun cloneBayAndCrewAway(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS_3")
        var clone = "no clone bay under these blueprints"
        val bay = state.player.clonebay
        if (bay != null) {
            // In a fight: out of combat Multiverse's instant clone would finish at once.
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
            state.debugFlags.noEnemyFire.set = true
            state.debugFlags.noClone.set = true
            try {
                stage = "bodies in the clone bay"
                state.player.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === state.player }.forEach { it.health = 0f }
                play(main, container, 5f)
                check(ownCrewAlive(state) == 0 && bay.queue.isNotEmpty()) { "Set-up: ${ownCrewAlive(state)} alive, ${bay.queue.size} bodies" }
                check(!state.browserRunEnded && !state.shipUI.isGameOverOpen) { "With its crew waiting in a working clone bay the run is over" }
                stage = "the clone bay destroyed"
                bay.damagedEnergyLevels = bay.energyLevels
                play(main, container, 1f)
                check(state.shipUI.gameOverWindow?.outcome == GameOverWindow.Outcome.LOOSE_CREW) {
                    "With the clone bay destroyed and nobody alive the run goes on (${state.shipUI.gameOverWindow?.outcome})"
                }
                clone = "bodies in a working clone bay count"
            } finally {
                state.debugFlags.noClone.set = false
            }
        }

        // One crew member aboard the enemy, everybody at home dead.
        stage = "a crew member aboard the enemy"
        state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        try {
            val player = state.player
            val enemy = state.enemy!!
            val away = player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === player }
            val info = away.info
            away.removeFromShip()
            val aboard = enemy.addCrewMember(info, false, true)
            aboard.ownerShip = player
            state.updatePlayerCrew()
            state.debugFlags.noCrewDamage.set = false
            player.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === player }.forEach { it.health = 0f }
            state.debugFlags.noCrewDamage.set = true
            play(main, container, 5f)
            check(player.crew.none { it is LivingCrew && it.ownerShip === player && it.health > 0f } && aboard.health > 0f) {
                "Set-up: crew at home alive, or the boarder dead (${aboard.health})"
            }
            check(!state.browserRunEnded && !state.shipUI.isGameOverOpen) { "With a crew member alive aboard the enemy the run is over" }
            stage = "the crew member aboard the enemy dies"
            state.debugFlags.noCrewDamage.set = false
            aboard.health = 0f
            play(main, container, 6f)
            check(state.shipUI.gameOverWindow?.outcome == GameOverWindow.Outcome.LOOSE_CREW) {
                "With its last crew member dead aboard the enemy the run goes on (${state.shipUI.gameOverWindow?.outcome})"
            }
        } finally {
            state.debugFlags.noCrewDamage.set = false
        }
        return "$clone, a crew member aboard the enemy counts"
    }

    /**
     * What an automated player ship does without anybody aboard: it has a pilot (the manned dodge, the FTL drive
     * charges), its systems take the manning boost and repair themselves, and the run saves and loads as automated.
     */
    private fun automatedShipFlies(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var player = state.player
        state.currentBeacon.powerLimitEffects.clear()
        player.updateScriptedPowerLimits()
        // The dodge with the pilot and the engines crew at their stations, both without skill.
        val piloting = player.piloting!!
        val engines = player.engines!!
        play(main, container, 1f)
        check(piloting.hasPilot) { "Set-up: the default ship's cockpit is empty" }
        player.crew.filterIsInstance<LivingCrew>().forEach { for (skill in it.info.skills.keys.toList()) it.info.skills[skill] = 0f }
        play(main, container, 0.5f)
        val manned = player.dodgeFactor
        val enginesManned = engines.getSkillLevel(xyz.znix.xftl.crew.Skill.ENGINES) != null
        check(manned > 0) { "Set-up: the manned default ship does not dodge" }

        stage = "nobody aboard"
        player.crew.toList().forEach { it.removeFromShip() }
        state.updatePlayerCrew()
        player.isAutomated = true
        play(main, container, 1f)
        check(!state.browserRunEnded) { "The automated ship's run is over" }
        check(piloting.hasPilot) { "An automated ship has no pilot" }
        // The manned ship above had its engines manned too when a crew member stood there.
        if (enginesManned) check(player.dodgeFactor == manned) { "The automated ship dodges ${player.dodgeFactor}, a manned one $manned" }
        else check(player.dodgeFactor >= manned) { "The automated ship dodges ${player.dodgeFactor}, less than with a pilot alone ($manned)" }
        check(player.canChargeFTL) { "The automated ship's FTL drive does not charge" }
        check(engines.getSkillLevel(xyz.znix.xftl.crew.Skill.ENGINES) == xyz.znix.xftl.crew.SkillLevel.BASE) { "The automated ship's engines are not manned" }

        stage = "repairs"
        engines.damagedEnergyLevels = 1
        play(main, container, 5f)
        check(engines.damagedEnergyLevels == 1 && engines.repairProgress > 0.1f) { "Five seconds on, a damaged system of the automated ship has repair progress ${engines.repairProgress}" }
        play(main, container, 40f)
        check(engines.damagedEnergyLevels == 0) { "45 seconds on, the automated ship's system is still damaged (${engines.repairProgress})" }

        stage = "save and load"
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        player = state.player
        check(player.isAutomated && player.crew.none { it.mode == AbstractCrew.SlotType.CREW }) { "The loaded run's ship is not automated" }
        play(main, container, 2f)
        check(!state.browserRunEnded && !state.shipUI.isGameOverOpen) { "The loaded run of an automated ship is over (${state.shipUI.gameOverWindow?.outcome})" }
        check(player.piloting!!.hasPilot && player.canChargeFTL) { "The loaded automated ship has no pilot" }
        return "an automated ship flies itself (dodge $manned), repairs and reloads"
    }

    /**
     * What the player sees and a script reads of an automated ship's manning (native: Multiverse's Auto-Cruiser, oracle
     * fix-201 f201-auto; the default ship with bAutomated set, f201-flag): the crew slot of sensors and doors lit
     * (ShipSystem::RenderPowerBoxes 0x63c310, an active-manning level of 1), no "unmanned" line in a system's tooltip
     * (0x4df760, IsMannedBoost not 0), the FTL button's pilot light on (FTLButton::OnRender 1.6.9 0x5428b0,
     * ShipSystem::Functioning of piloting is bManned and power), sensors and doors one level up with their manned line
     * (0x4df760: GetEffectivePower, subsystem_manned), and ShipSystem.bManned true for a script. All of them
     * are asked, so one message names every part that is wrong.
     */
    private fun automatedShipShowsItsManning(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        state.currentBeacon.powerLimitEffects.clear()
        player.updateScriptedPowerLimits()
        player.crew.toList().forEach { it.removeFromShip() }
        state.updatePlayerCrew()
        player.isAutomated = true
        play(main, container, 1f)
        val wrong = ArrayList<String>()
        val subs = player.systems.filterIsInstance<xyz.znix.xftl.systems.SubSystem>()
            .filter { it.info.canBeManned && !it.damaged && !it.isIonised && it !is xyz.znix.xftl.systems.Piloting }
        check(subs.isNotEmpty()) { "Set-up: the default ship has no subsystem a crew member mans" }
        subs.filter { it.manningBarName != "on" }.forEach { wrong += "the crew slot of ${it.codename} is ${it.manningBarName}" }
        val unmanned = state.translator["unmanned"]
        for (system in listOfNotNull(player.engines, player.shields, player.piloting) + subs) {
            val text = xyz.znix.xftl.game.SystemButtonTooltip(state, system).getText()
            if (text.contains(unmanned)) wrong += "the tooltip of ${system.codename} says $unmanned"
        }
        // sensors and doors: one level up and the manned line (native: "Level 2: See enemy interior", "Manned: +1 System
        // Level" on sensors of level 1)
        val mannedLine = state.translator["manning_bonus"].replaceArg(state.translator["subsystem_manned"])
        for (system in subs.filter { (it is xyz.znix.xftl.systems.Sensors || it is xyz.znix.xftl.systems.Doors) && it.undamagedEnergy > 0 }) {
            val text = xyz.znix.xftl.game.SystemButtonTooltip(state, system).getText()
            val level = state.translator["level"].replaceArg(system.undamagedEnergy + 1)
                .replaceArg(xyz.znix.xftl.hs.lua.LuaShipEvents.levelName(system.blueprint, system.undamagedEnergy, true, state.translator), 2)
            if (!text.contains(mannedLine)) wrong += "the tooltip of ${system.codename} lacks \"$mannedLine\""
            if (!text.contains(level)) wrong += "the tooltip of ${system.codename} lacks \"$level\""
        }
        if (!player.piloting!!.pilotLight) wrong += "the FTL button's pilot light is off"
        var lua = ""
        if (xyz.znix.xftl.hs.HS.luaActive) {
            val err = xyz.znix.xftl.hs.lua.HsLua.runString("fix201-manned", """
                local p = Hyperspace.ships.player
                error('R|' .. tostring(p:GetSystem(6).bManned) .. ',' .. tostring(p:GetSystem(1).bManned) .. ',' .. string.format('%d', p:GetSystem(6).iActiveManned))
            """.trimIndent()) ?: "no result"
            if (err.substringAfter("R|") != "true,true,1") wrong += "a script reads bManned of piloting and engines and piloting's iActiveManned as ${err.substringAfter("R|")}"
            lua = ", a script reads them manned"
        }
        check(wrong.isEmpty()) { wrong.joinToString("; ") }
        return "an automated ship shows its ${subs.size} subsystems manned, no unmanned line and the pilot light$lua"
    }

    /** A pointer on the open ship window: the mouse, or a finger through the touch router as the Android host sends it. */
    private class Pointer(val window: () -> xyz.znix.xftl.game.ShipWindow, val touch: Boolean) {
        private val mouse = xyz.znix.xftl.game.TouchRouter.Mouse { x, y, type, _ ->
            when (type) {
                0 -> window().updateUI(x, y)
                1 -> window().mouseClick(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, x, y)
                else -> window().mouseReleased(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, x, y)
            }
        }
        private val targets = xyz.znix.xftl.game.TouchRouter.LiftTargets { x, y -> window().liftTarget(x, y) }
        private fun settle() { var n = 0; while (xyz.znix.xftl.game.TouchRouter.afterStep() && n++ < 4) Unit }
        private fun route(kind: Int, x: Int, y: Int) {
            val p = window().diagnosticScreenPoint(x, y)
            xyz.znix.xftl.game.TouchRouter.route(null, targets, kind, 0, p.x, p.y, 0L, mouse)
        }

        fun down(x: Int, y: Int) {
            if (touch) { route(xyz.znix.xftl.game.TouchRouter.DOWN, x, y); settle() }
            else { val p = window().diagnosticScreenPoint(x, y); mouse.event(p.x, p.y, 0, 0); mouse.event(p.x, p.y, 1, 0) }
        }

        fun move(x: Int, y: Int) {
            if (touch) route(xyz.znix.xftl.game.TouchRouter.MOVE, x, y)
            else { val p = window().diagnosticScreenPoint(x, y); mouse.event(p.x, p.y, 0, 0) }
        }

        fun up(x: Int, y: Int) {
            if (touch) { route(xyz.znix.xftl.game.TouchRouter.UP, x, y); settle() }
            else { val p = window().diagnosticScreenPoint(x, y); mouse.event(p.x, p.y, 2, 0) }
        }
    }

    private val graphics: xyz.znix.xftl.rendering.Graphics get() = BrowserEngine.diagnosticGraphics()

    private fun rect(json: String, key: String): IntArray? {
        val m = Regex("\"$key\":\\{\"x\":(-?\\d+),\"y\":(-?\\d+),\"w\":(\\d+),\"h\":(\\d+)\\}").find(json) ?: return null
        return IntArray(4) { m.groupValues[it + 1].toInt() }
    }

    /**
     * One try at dismissing the first crew member listed on the crew screen: its box's Dismiss button pressed (the
     * mouse; a finger taps it), or ([drop], the touch layout without Hyperspace) the box carried onto the DISMISSAL box.
     * Whether the confirmation opened; the screen is closed again (the confirmation cancelled).
     */
    private fun tryDismiss(main: MainGame, container: GameContainer, state: InGameState, touch: Boolean, drop: Boolean): Boolean {
        state.shipUI.diagnosticCloseWindow()
        state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(xyz.znix.xftl.game.VanillaHotkeys.SHIP_CREW), container.input)
        val window = state.shipUI.diagnosticOpenShipWindow ?: error("the crew screen did not open (${state.shipUI.diagnosticWindow})")
        check(window.tutorialTab == xyz.znix.xftl.game.ShipWindow.Tab.CREW) { "the ship window opened on ${window.tutorialTab}" }
        try {
            main.render(container, graphics)
            window.diagnosticRelayout(graphics)
            val layout = window.diagnosticOverCapacity
            val ox = Regex("\"origin\":\\{\"x\":(-?\\d+),\"y\":(-?\\d+)\\}").find(layout)?.groupValues ?: error("no origin in $layout")
            val origin = xyz.znix.xftl.math.ConstPoint(ox[1].toInt(), ox[2].toInt())
            // The first box that holds a crew member (its dismiss button is listed only then).
            val box = Regex("\\{\"id\":\\d+,\"over\":(true|false),\"crew\":\"[^\"]*\",\"x\":(-?\\d+),\"y\":(-?\\d+),\"w\":100,\"h\":86,\"dismiss\":")
                .find(layout)?.groupValues ?: error("no crew box with a dismiss button: $layout")
            val bx = origin.x + box[2].toInt()
            val by = origin.y + box[3].toInt()
            val pointer = Pointer({ window }, touch)
            if (!drop) {
                val d = rect(layout.substring(layout.indexOf("\"x\":${box[2]},\"y\":${box[3]},\"w\":100")), "dismiss") ?: error("no dismiss button")
                val x = origin.x + d[0] + d[2] / 2
                val y = origin.y + d[1] + d[3] / 2
                pointer.move(x, y)
                pointer.down(x, y)
                pointer.up(x, y)
            } else {
                val target = rect(layout, "dismissBox") ?: error("no DISMISSAL box on the touch crew screen: $layout")
                val tx = origin.x + target[0] + target[2] / 2
                val ty = origin.y + target[1] + target[3] / 2
                // Held at the box's (50, 40), past 9 px to pick it up, then carried until its (50, 46) is on the box.
                pointer.down(bx + 50, by + 40)
                pointer.move(bx + 62, by + 40)
                for (i in 1..8) pointer.move(bx + 62 + (tx - bx - 50) * i / 8, by + 40 + (ty - by - 46) * i / 8)
                pointer.up(tx + 12, ty - 6)
            }
            return !window.diagnosticOverCapacity.contains("\"confirm\":null")
        } finally {
            xyz.znix.xftl.game.TouchRouter.route(null, null, xyz.znix.xftl.game.TouchRouter.CANCEL, 0, -1, -1, 0L) { _, _, _, _ -> }
            window.escapePressed()
            state.shipUI.diagnosticCloseWindow()
        }
    }

    /**
     * 201b, the crew screen's Dismiss (CrewEquipBox::OnRender 1.6.14 0x544b10 / 1.6.9 0x52f410: deleteButton.SetActive;
     * MouseClick 0x542af0 / 0x52d440: the same test before bConfirmDelete): active only while
     * CrewMemberFactory::GetPlayerCrewCount is above 1, so the last crew member of a crewed ship cannot be dismissed.
     * Hyperspace's rewrites (CustomCrewManifest.cpp:817, :844) also allow it on an automated ship and for the crew member
     * in the over-limit box. The touch layout's DISMISSAL box (without Hyperspace) follows the same rule. Asked of the
     * first crew box: two crew, one, one on an automated ship, and under Hyperspace one in the over-limit box.
     */
    private fun dismissNeedsTwoCrew(main: MainGame, container: GameContainer, touch: Boolean): String {
        val hs = xyz.znix.xftl.hs.HS.active
        xyz.znix.xftl.game.TouchMode.enabled = touch
        var def: xyz.znix.xftl.hs.ship.CustomShipDefinition? = null
        var limit = 0
        try {
            val state = BrowserGameplayParityDiagnostics.fresh(main, container)
            val player = state.player
            if (hs) { def = xyz.znix.xftl.hs.ship.HsShips.def(player.type.name); limit = def.crewLimit }
            check(!player.isAutomated && player.clonebay == null) { "Set-up: the default ship is automated or has a clone bay" }
            fun keep(n: Int) {
                player.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === player }.drop(n).forEach { it.removeFromShip() }
                state.updatePlayerCrew()
                check(state.crewFactoryPlayerCount() == n) { "Set-up: the crew factory counts ${state.crewFactoryPlayerCount()} crew, not $n" }
            }
            val ways = listOf(false) + (if (touch && !hs) listOf(true) else emptyList())
            val wrong = ArrayList<String>()
            fun ask(label: String, allowed: Boolean) {
                for (drop in ways) {
                    val opened = tryDismiss(main, container, state, touch, drop)
                    val how = if (drop) "the DISMISSAL box" else "the Dismiss button"
                    if (opened != allowed) wrong += "$label: $how ${if (opened) "opens" else "does not open"} the dismiss confirmation"
                }
            }
            keep(2)
            ask("two crew", true)
            keep(1)
            ask("the last crew member", false)
            player.isAutomated = true
            ask("the last crew member of an automated ship", hs)
            player.isAutomated = false
            def?.let {
                // No crew slot left: the only crew member is in the over-limit box (CustomCrewManifest overCrewBox).
                it.crewLimit = 0
                ask("the last crew member in the over-limit box", true)
            }
            check(wrong.isEmpty()) { wrong.joinToString("; ") }
            val how = (if (touch) "a finger" else "the mouse") + (if (ways.size > 1) " and the DISMISSAL box" else "")
            return "Dismiss ($how): allowed with two crew, refused for the last one" +
                (if (hs) ", allowed for it on an automated ship and in the over-limit box" else ", also on an automated ship")
        } finally {
            def?.crewLimit = limit
            xyz.znix.xftl.game.TouchMode.enabled = false
        }
    }

    /**
     * 201b, Hyperspace's ShipManager::SelectRandomCrew hook (CustomEvents.cpp:6776): a ship that is automated and holds
     * none of its own crew first gets AddCrewMemberFromString(GetText("autoship_dismissed_crew_name"), "human", false, 0,
     * false, false), a woman in room 0 who stays. Asked by a script (ShipManager:SelectRandomCrew) and by an event's
     * transformRace (CustomEvents.cpp:3995, :5731, only with playerCrew above 0: crew aboard the enemy here). A crew
     * loss on such a ship asks nobody (the CreateLocation / UpdateLocation hooks clear it while CountCrew(false) is 0),
     * nor does a transformRace with no crew at all. Under Multiverse the event is its own TEMP_OBYN_JUMP_CONVERT.
     */
    private fun autoShipRandomCrew(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "the crewless ship's random crew member: Hyperspace only"
        val wrong = ArrayList<String>()
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val name = state.translator["autoship_dismissed_crew_name"]
        state.eventManager.diagnosticAppend(BrowserXml.parse("<FTL>" +
            "<event name=\"F201B_LOSE\"><text>f201b loss.</text><removeCrew><clone>false</clone><text>f201b lost.</text></removeCrew></event>" +
            "<event name=\"F201B_TRANSFORM\"><text>f201b transform.</text><transformRace class=\"human\">engi</transformRace></event></FTL>"))
        fun empty(state: InGameState) {
            state.player.crew.filterIsInstance<LivingCrew>().toList().forEach { it.removeFromShip() }
            state.updatePlayerCrew()
        }
        fun named(state: InGameState) = state.player.crew.filterIsInstance<LivingCrew>().filter { it.info.name == name }
        fun human(state: InGameState, label: String): Boolean {
            val found = named(state)
            if (found.size > 1) wrong += "$label: ${found.size} crew members named \"$name\""
            val c = found.firstOrNull() ?: return false
            if (c.blueprint.name != "human" || c.room.id != 0 || c.ownerShip !== state.player || c.mode != AbstractCrew.SlotType.CREW)
                wrong += "$label: the crew member is ${c.blueprint.name} in room ${c.room.id}, ${c.mode}"
            if (c.info.colour < c.blueprint.baseNumberOfColours) wrong += "$label: the crew member is a man"
            return true
        }
        val player = state.player
        empty(state)

        // A script asks.
        var lua = ""
        if (xyz.znix.xftl.hs.HS.luaActive) {
            fun select(): String = (xyz.znix.xftl.hs.lua.HsLua.runString("fix201b-select", """
                local p = Hyperspace.ships.player
                local bp = p:SelectRandomCrew(-1, '')
                error('R|' .. bp.name .. '|' .. bp.crewNameLong.data .. '|' .. string.format('%d', p:CountCrew(false)))
            """.trimIndent()) ?: "no result").substringAfter("R|")
            val notAuto = select()
            if (named(state).isNotEmpty() || notAuto != "||0") wrong += "a ship that is not automated: a script's pick gives $notAuto and ${named(state).size} new crew"
            player.isAutomated = true
            val first = select()
            if (!human(state, "a script's pick")) wrong += "a script's pick on an automated ship without crew adds nobody ($first)"
            if (first != "human|$name|1") wrong += "a script's pick reads $first, not human|$name|1"
            val second = select()
            if (named(state).size != 1 || second != "human|$name|1") wrong += "a second pick reads $second with ${named(state).size} named crew"
            lua = "a script's pick adds her once, "
        }

        // Events: a crew loss and a transformRace without any crew ask nobody.
        state = BrowserGameplayParityDiagnostics.fresh(main, container)
        empty(state)
        state.player.isAutomated = true
        for (event in listOf("F201B_LOSE", "F201B_TRANSFORM")) {
            xyz.znix.xftl.hs.render.HsConsole.run(state, "LOADEVENT $event")
            BrowserDiagnostics.closeBoxes(main, container)
            if (named(state).isNotEmpty() || state.player.crew.any { it is LivingCrew }) wrong += "$event without crew: ${state.player.crew.size} crew aboard after it"
        }
        // With the only crew member aboard the enemy, a transformRace asks: the woman comes aboard first.
        fun away(state: InGameState): LivingCrew {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
            state.debugFlags.noEnemyFire.set = true
            state.debugFlags.noCrewDamage.set = true
            val race = state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint
            val aboard = state.enemy!!.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(race, state), false, true)
            aboard.ownerShip = state.player
            state.updatePlayerCrew()
            return aboard
        }
        val events = ArrayList<String>()
        try {
            away(state)
            xyz.znix.xftl.hs.render.HsConsole.run(state, "LOADEVENT F201B_TRANSFORM")
            BrowserDiagnostics.closeBoxes(main, container)
            if (!human(state, "F201B_TRANSFORM")) wrong += "F201B_TRANSFORM with the crew aboard the enemy adds nobody"
            events += "a transformRace with the crew aboard the enemy adds her"
        } finally {
            state.debugFlags.noEnemyFire.set = false
            state.debugFlags.noCrewDamage.set = false
        }
        // Multiverse: one of its crewless ships and its own event.
        if (state.eventManager.getOrNull("TEMP_OBYN_JUMP_CONVERT") != null && main.gameContent.blueprintManager.getOrNull("PLAYER_SHIP_AUTO") != null) {
            stage = "PLAYER_SHIP_AUTO"
            main.startDiagnosticGame("PLAYER_SHIP_AUTO", Difficulty.NORMAL)
            BrowserDiagnostics.closeBoxes(main, container)
            state = main.currentState as InGameState
            check(state.player.isAutomated && state.player.crew.none { it is LivingCrew }) { "Set-up: the Auto-Cruiser has crew or is not automated" }
            try {
                away(state)
                xyz.znix.xftl.hs.render.HsConsole.run(state, "LOADEVENT TEMP_OBYN_JUMP_CONVERT")
                BrowserDiagnostics.closeBoxes(main, container)
                if (named(state).isEmpty()) wrong += "TEMP_OBYN_JUMP_CONVERT on the Auto-Cruiser with its crew aboard the enemy adds nobody"
                events += "Multiverse's TEMP_OBYN_JUMP_CONVERT on the Auto-Cruiser too"
            } finally {
                state.debugFlags.noEnemyFire.set = false
                state.debugFlags.noCrewDamage.set = false
            }
            stage = ""
        }
        check(wrong.isEmpty()) { wrong.joinToString("; ") }
        return "an automated ship without crew: ${lua}a crew loss and a transformRace without crew add nobody, " + events.joinToString(", ")
    }
}
