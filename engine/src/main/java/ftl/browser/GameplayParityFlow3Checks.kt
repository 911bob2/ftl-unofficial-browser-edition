package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.BackupBattery

/**
 * Round-4 jump and new-game flow: the starting power (ShipSystem ctor 0x642370, CreateSystems order, Zoltan bonus on
 * top), the fuel ShipManager::JumpLeave (0x49c9c0) takes, the jumping flag (+0x4d8: systems idle, shots miss), the
 * 2 s jump tracker and its fade, the arrival hold, Shields::Jump (0x6340e0) keeping the charge, BatterySystem::Jump
 * (0x62e1d0), ShipManager::Wait (0x49d300) and CheckGameover (0x52c900) not running while jumping.
 */
internal object GameplayParityFlow3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            // First: a run that ended earlier in this fixture leaves the sound manager in its game-over state.
            val continueSounds = continueEnginesOff(main, container)
            val power = BrowserGameplayParityDiagnostics.vanillaOnly("power") { startPower(main, container) }
            val fade = jumpFade()
            val fuel = jumpFuel(main, container)
            val jumping = jumpingFlag(main, container)
            val hold = jumpSimulation(main, container)
            val shields = shieldsKeepCharge(main, container)
            val battery = batteryJump(main, container)
            val wait = noFuelWait(main, container)
            val neutral = noFuelBesideNeutralShip(main, container)
            val gameOver = gameOverWhileJumping(main, container)
            val pausedGameOver = BrowserGameplayParityDiagnostics.vanillaOnly("pausedGameOver") { gameOverWhilePaused(main, container) }
            return "flow3 ok: $power, $fade, $fuel, $jumping, $hold, $shields, $battery, $wait, $neutral, $gameOver, $continueSounds, $pausedGameOver"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun powers(state: InGameState): Map<String, Int> =
        // The bars the HUD shows: a Zoltan's bar stays in a weapons system that has no weapon powered yet.
        state.player.mainSystems.associate { it.codename to maxOf(it.powerSelected, it.forcedPower) }

    /**
     * The starting power of a new ship, as the original shows it (live captures of each ship's first beacon): one bar
     * per system, two for the shields, none for the weapons and drones, in creation order while the reactor lasts; a
     * Zoltan's bar comes on top. The shields start charged.
     */
    private fun startPower(main: MainGame, container: GameContainer): String {
        val expected = mapOf(
            "PLAYER_SHIP_HARD" to mapOf("shields" to 2, "engines" to 1, "medbay" to 1, "oxygen" to 1, "weapons" to 0),
            "PLAYER_SHIP_MANTIS" to mapOf("shields" to 2, "engines" to 1, "medbay" to 1, "oxygen" to 1, "teleporter" to 1, "weapons" to 0),
            "PLAYER_SHIP_STEALTH" to mapOf("engines" to 1, "medbay" to 1, "oxygen" to 1, "cloaking" to 1, "weapons" to 0),
            "PLAYER_SHIP_ENERGY" to mapOf("shields" to 2, "engines" to 1, "medbay" to 1, "oxygen" to 1, "weapons" to 1),
            "PLAYER_SHIP_ENERGY_3" to mapOf("shields" to 1, "engines" to 2, "clonebay" to 0, "oxygen" to 1, "weapons" to 1),
        )
        for ((ship, want) in expected) {
            val state = BrowserGameplayParityDiagnostics.fresh(main, container, ship)
            val got = powers(state).filterKeys { it in want }
            check(got == want) { "$ship start power $got, expected $want" }
            val shields = state.player.shields
            if (shields != null)
                check(shields.activeShields == shields.selectedShieldBars) { "$ship shields not charged at the start" }
        }
        return "start power"
    }

    /** The jump tracker (2 s) fades the hull between 1 s and 1.5 s and leaves it gone for the rest. */
    private fun jumpFade(): String {
        check(InGameState.JUMP_TRACKER_TIME == 2f) { "Jump tracker ${InGameState.JUMP_TRACKER_TIME} s" }
        check(InGameState.browserJumpFade(0.9f) == 0f && InGameState.browserJumpFade(1.25f) == 0.5f &&
                InGameState.browserJumpFade(1.6f) == 1f) { "Jump fade curve" }
        return "jump fade"
    }

    /** Every jump that leaves takes one fuel in JumpLeave, floored at zero. */
    private fun jumpFuel(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.player.fuelCount = 5
        state.beginJumpOut(state.currentBeacon.neighbours.first())
        check(state.player.fuelCount == 4) { "Jump left ${state.player.fuelCount} fuel from 5" }

        val empty = BrowserGameplayParityDiagnostics.fresh(main, container)
        empty.player.fuelCount = 0
        empty.beginJumpOut(empty.currentBeacon.neighbours.first())
        check(empty.player.fuelCount == 0) { "Fuel went below zero: ${empty.player.fuelCount}" }
        return "jump fuel"
    }

    /** A jumping ship's systems stand still (no shield recharge, no FTL charge) and every shot at it misses. */
    private fun jumpingFlag(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val shields = player.shields!!
        shields.activeShields = 0
        val timer = shields.rechargeTimer
        player.ftlChargeProgress = 0.5f
        player.browserJumping = true
        try {
            player.update(0.5f)
            check(shields.activeShields == 0 && shields.rechargeTimer == timer) {
                "Shields charged while jumping: ${shields.activeShields} ${shields.rechargeTimer}"
            }
            check(player.ftlChargeProgress == 0.5f) { "FTL charged while jumping: ${player.ftlChargeProgress}" }
            check((0 until 5).all { player.pickMissed(missDeadEnemies = false) }) { "A shot hit a jumping ship" }
        } finally {
            player.browserJumping = false
        }
        return "jumping flag"
    }

    /**
     * The simulation keeps running through the jump-out (the air still moves between rooms), but holds once the new
     * beacon's event is pending, until the event box opens.
     */
    private fun jumpSimulation(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val door = state.player.doors.first { !it.isAirlock }
        val a = door.left!!
        val b = door.right!!

        fun gap(): Float {
            door.open = true
            return Math.abs(a.oxygen - b.oxygen)
        }

        val target = state.currentBeacon.neighbours.first()
        state.beginJumpOut(target)
        a.oxygen = 0f
        b.oxygen = 1f
        val before = gap()
        repeat(10) { main.update(container, .05f) }
        check(state.isPlayerJumping && state.currentBeacon != target) { "The jump-out ended within 0.5 s" }
        check(gap() < before) { "The simulation froze during the jump-out" }

        repeat(32) { main.update(container, .05f) }
        check(state.currentBeacon == target && state.isPlayerJumping) { "No arrival after 2.1 s" }
        a.oxygen = 0f
        b.oxygen = 1f
        repeat(5) { main.update(container, .05f) }
        check(gap() == 1f) { "The simulation ran while the arrival event was pending" }
        return "jump simulation"
    }

    /** A jump leaves the shield layers as they were; they recharge at the next beacon. */
    private fun shieldsKeepCharge(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val shields = state.player.shields!!
        shields.activeShields = 0
        val timer = shields.rechargeTimer
        state.player.resetAfterJump()
        check(shields.activeShields == 0 && shields.rechargeTimer == timer) {
            "The jump recharged the shields: ${shields.activeShields} ${shields.rechargeTimer}"
        }
        return "shields keep charge"
    }

    /** An active battery stops at the jump and, with its lock cleared, is ready at the next beacon. */
    private fun batteryJump(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC")
        val battery = state.player.systems.filterIsInstance<BackupBattery>().firstOrNull()
            ?: return "battery jump (no battery)"
        battery.startBattery()
        check(battery.timeRemaining != null) { "Battery did not start" }
        battery.onJump()
        check(battery.timeRemaining == null && battery.ionTimer == 0f) {
            "Battery after a jump: running ${battery.timeRemaining} lock ${battery.ionTimer}"
        }

        // JumpLeave runs the system Jump as the jump-out starts (the original's battery is off in the first jump frame).
        val leaving = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ENERGY_3")
        val running = leaving.player.systems.filterIsInstance<BackupBattery>().first()
        running.startBattery()
        leaving.beginJumpOut(leaving.currentBeacon.neighbours.first())
        check(leaving.isPlayerJumping && running.timeRemaining == null && running.ionTimer == 0f) {
            "Battery at the jump-out: running ${running.timeRemaining} lock ${running.ionTimer}"
        }
        return "battery jump"
    }

    /** The no-fuel wait takes no fuel and unlocks only the shields, engines, weapons and piloting. */
    private fun noFuelWait(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        player.fuelCount = 0
        player.shields!!.lockUnits(2)
        player.engines!!.lockUnits(2)
        val oxygen = player.mainSystems.first { it.codename == "oxygen" }
        oxygen.lockUnits(2)
        player.browserWaitOutOfFuel()
        check(player.fuelCount == 0) { "The wait changed the fuel" }
        check(player.shields!!.ionTimer == 0f && player.engines!!.ionTimer == 0f) { "The wait kept the shield/engine lock" }
        check(oxygen.ionTimer > 0f) { "The wait cleared the oxygen lock" }
        return "no-fuel wait"
    }

    /**
     * Out of fuel beside a ship that is no longer hostile (its crew dead, its surrender taken, a neutral event ship):
     * CommandGui::OnLoop (1.6.14 0x536770) locks the jump button with no fuel only while CombatControl's current target
     * is hostile (0x577a40, Targetable::hostile), so the button stays active, on the desktop and the touch layout
     * alike. It opens the map with WAIT and the distress toggle (StarMap::OnLoop: outOfFuel = fuel < 1); the toggle
     * outlives a save; the wait clears the ship as a jump does (ClearLocation 0x614d00, oracle r24-nofuel) and with the
     * beacon on draws NO_FUEL_DISTRESS (StarMap 0x5f4510); fuel ends it all.
     */
    private fun noFuelBesideNeutralShip(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), xyz.znix.xftl.game.Difficulty.NORMAL, 2, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        player.fuelCount = 0
        player.ftlChargeProgress = 1f
        state.updateGameState(.05f)
        player.ftlChargeProgress = 1f
        val desktop = state.shipUI.jumpButton ?: error("Set-up: no desktop jump button")
        var touch = xyz.znix.xftl.game.TouchHud(state.shipUI, state)
        touch.update(0f)
        check(player.isFtlReady && desktop.disabled && !touch.diagnosticJumpActive) {
            "Out of fuel beside a hostile ship: ready ${player.isFtlReady}, desktop jump open ${!desktop.disabled}, touch jump open ${touch.diagnosticJumpActive}"
        }

        // The fight ends (as when the crew die or the surrender is taken): the ship stays, no longer hostile.
        state.browserEndHostility()
        // and the beacon no longer keeps it, as the crew kill's end does (InGameState: it is saved as the ship nearby)
        state.currentBeacon.ship = null
        state.updateGameState(.05f)
        check(state.enemy != null && state.browserCanWait()) { "Set-up: the ship left or is still hostile" }
        touch.update(0f)
        check(!desktop.disabled) { "Out of fuel beside a ship that is not hostile, the desktop jump button is inactive" }
        check(touch.diagnosticJumpActive) { "Out of fuel beside a ship that is not hostile, the touch jump button is inactive" }

        // The button opens the map (the touch tap calls the same openJumpMap) with the no-fuel controls.
        state.shipUI.openJumpMap()
        var window = state.shipUI.diagnosticJumpWindow ?: error("The jump button opened no map: ${state.shipUI.diagnosticWindows}")
        check(window.noFuelButtons.size == 2) { "The out-of-fuel map has ${window.noFuelButtons.size} wait/distress buttons" }
        fun press(b: xyz.znix.xftl.game.Button) =
            check(b.mouseDown(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, b.pos.x + b.size.x / 2, b.pos.y + b.size.y / 2)) { "The button took no click" }
        press(window.noFuelButtons[1])
        check(state.fuelDistressBeacon) { "The distress toggle did not switch the beacon on" }

        // A save keeps the toggle, the empty tank and the neutral ship: the button is still open after the load.
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        loaded.updateGameState(.05f)
        touch = xyz.znix.xftl.game.TouchHud(loaded.shipUI, loaded)
        touch.update(0f)
        check(loaded.fuelDistressBeacon && loaded.player.fuelCount == 0 && loaded.enemy != null && loaded.browserCanWait()) {
            "After a load: distress ${loaded.fuelDistressBeacon}, fuel ${loaded.player.fuelCount}, ship ${loaded.enemy != null}, not hostile ${loaded.browserCanWait()}"
        }
        check(touch.diagnosticJumpActive && loaded.shipUI.jumpButton?.disabled == false) { "After a load the jump button is inactive" }

        // WAIT: two seconds of Waiting..., then the wait event; with the beacon on it comes from NO_FUEL_DISTRESS.
        val stayed = loaded.enemy
        loaded.shipUI.openJumpMap()
        window = loaded.shipUI.diagnosticJumpWindow ?: error("After the load the jump button opened no map: ${loaded.shipUI.diagnosticWindows}")
        press(window.noFuelButtons[0])
        check(window.isWaiting) { "WAIT did not start the wait" }
        var frames = 0
        while (loaded.shipUI.diagnosticJumpWindow != null && frames++ < 100) main.update(container, .05f)
        check(loaded.shipUI.diagnosticJumpWindow == null) { "The wait never happened" }
        check(loaded.player.fuelCount == 0 && loaded.fuelDistressBeacon) { "The wait took fuel or switched the beacon off" }
        // The wait is a new location (CheckForNewLocation -> ClearLocation): the ship that was not hostile goes.
        check(loaded.enemy !== stayed && loaded.currentBeacon.ship !== stayed) { "The ship that was not hostile is still there after the wait" }
        val distressList = BrowserGameplayParityDiagnostics.vanillaOnly("distress event") {
            val list = loaded.eventManager["NO_FUEL_DISTRESS"] as xyz.znix.xftl.sector.EventList
            val id = loaded.currentBeacon.event.deserialisationId
            check(list.events.any { it is xyz.znix.xftl.sector.Event && it.deserialisationId == id }) { "The distress wait made $id, not a NO_FUEL_DISTRESS event" }
            "NO_FUEL_DISTRESS"
        }

        // Fuel again: the map is the normal one.
        repeat(5) {
            loaded.shipUI.diagnosticDialogue?.selectOption(0)
            main.update(container, .05f)
        }
        loaded.player.fuelCount = 1
        check(!xyz.znix.xftl.game.Buttons.JumpButton.outOfFuel(loaded, loaded.player)) { "With fuel the jump is still out of fuel" }
        return "no fuel beside a neutral ship: jump open (desktop, touch), map with wait and distress, kept over a load, the ship cleared by the wait, $distressList"
    }

    /** CheckGameover is not called while the player jumps; the lost crew ends the run once the ship has arrived. */
    private fun gameOverWhileJumping(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        // The arrival event must be one the crewless ship can close: MERCENARY's first choice costs scrap, so
        // selectOption(0) is refused and the focus window never closes. Which event a seed rolls changes with every
        // game an earlier step starts, so the check pins a plain one.
        val destination = state.currentBeacon.neighbours.first()
        destination.event = state.eventManager["NOTHING"].resolve()
        state.beginJumpOut(destination)
        state.player.crew.removeAll { it is LivingCrew }
        state.updateGameState(.05f)
        check(state.browserProgress.outcome == null) { "Game over while jumping: ${state.browserProgress.outcome}" }

        repeat(100) {
            main.update(container, .05f)
            state.shipUI.diagnosticDialogue?.selectOption(0)
        }
        check(!state.isPlayerJumping) { "Still jumping after 5 s" }
        // CheckGameover waits while a focus window (the arrival event) is open.
        repeat(20) {
            val dialogue = state.shipUI.diagnosticDialogue
            if (dialogue != null) dialogue.selectOption(0)
            else if (state.shipUI.isWindowOpen && state.browserProgress.outcome == null) state.shipUI.escapePressed()
            main.update(container, .05f)
        }
        check(state.browserProgress.outcome == GameOverWindow.Outcome.LOOSE_CREW) {
            "Crew loss after the jump: ${state.browserProgress.outcome} window ${state.shipUI.diagnosticWindow}"
        }
        return "game over after jump"
    }

    /**
     * The first loop after a load sees an unmanned cockpit (the crew pass runs after the dodge check), so a continued
     * run without an autopilot rings enginesOff once with jumpReady; with an autopilot (piloting 2+) it does not.
     */
    private fun continueEnginesOff(main: MainGame, container: GameContainer): String {
        val log = ArrayList<String>()
        fun enginesOff(pilotLevel: Int): Int? {
            var state = BrowserGameplayParityDiagnostics.fresh(main, container)
            state.player.piloting!!.energyLevels = pilotLevel
            val sounds = state.content.sounds as? xyz.znix.xftl.game.RealSoundManager ?: return null
            log.clear()
            // Traced from before the load: the load itself may already run the first loop.
            sounds.traceSink = { kind, name -> log += "$kind $name" }
            try {
                state = BrowserGameplayParityDiagnostics.reload(main, state)
                if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
                repeat(20) { main.update(container, .05f) }
            } finally {
                sounds.traceSink = null
            }
            check(!state.player.browserMannedStale && state.player.piloting!!.hasPilot) { "Cockpit still unmanned after a second" }
            return log.count { it == "play enginesOff" }
        }
        val first = enginesOff(1) ?: return "continue sounds (no sound manager)"
        check(first == 1) { "Continue without autopilot: ${log.filter { it.startsWith("play") }}" }
        check(enginesOff(2) == 0) { "Continue with autopilot: ${log.filter { it.startsWith("play") }}" }
        return "continue enginesOff"
    }

    /**
     * CheckGameover runs while the game is paused (no focus window open), and the crew test comes first: crew at zero
     * health and a hull at zero give the crew ending at once (CommandGui::OnLoop runs while paused).
     */
    private fun gameOverWhilePaused(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (!state.isPaused) state.toggleQuickPause()
        state.player.crew.filterIsInstance<LivingCrew>().forEach { it.health = 0f }
        state.player.health = 0
        main.update(container, .05f)
        check(state.browserProgress.outcome == GameOverWindow.Outcome.LOOSE_CREW) {
            "Paused crew and hull loss: ${state.browserProgress.outcome}"
        }
        return "paused game over"
    }
}
