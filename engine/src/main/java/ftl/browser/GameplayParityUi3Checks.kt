package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.WarningFlasher
import xyz.znix.xftl.sys.GameContainer
import kotlin.math.abs

/**
 * Round four UI fixes (fix/ui): the HUD's WarningMessages (U6, P12), the screen shake (U8) and the hull tint
 * of the background (U21). Placement and drawing were compared with native captures; these pin the rules.
 */
internal object GameplayParityUi3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val placement = placement()
            val armament = armament(main, container)
            val missiles = noMissiles(main, container)
            val shake = shake(main, container)
            val tint = tint(main, container)
            val credits = credits(main, container)
            val neutral = neutralShipReload(main, container)
            val waiting = outOfFuelWait(main, container)
            val scores = newScoreRows()
            val planet = planetPlacement(main, container)
            val powered = poweredSlot(main, container)
            val destruction = destruction(main, container)
            val outcome = outcomeBox(main, container)
            val overCapacity = overCapacityKept(main, container)
            val centring = centring()
            return "ui3 ok: $placement, $armament, $missiles, $shake, $tint, $credits, $neutral, $waiting, $scores, $planet, $powered, $destruction, $outcome, $overCapacity, $centring"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * Round five (U19, U5): the New High Score! line and the New! row follow where AddTopScore put the score: after
     * every one at least as high, gone once trimmed (five overall, four per ship); zero scores aren't submitted.
     */
    private fun newScoreRows(): String {
        val profile = xyz.znix.xftl.game.SaveProfile.createBlank()
        fun record(ship: String, beacons: Int) = profile.recordBrowserRun(BrowserRunRecord(ship, ship,
            xyz.znix.xftl.game.Difficulty.NORMAL, false, 0, beacons, 0, true, xyz.znix.xftl.game.GameOverWindow.Outcome.LOOSE_HULL))
        record("A", 0)
        check(profile.browserNewTopIndex == -1 && profile.browserNewShipIndex == -1) { "A zero score was placed" }
        record("A", 3)
        check(profile.browserNewTopIndex == 0 && profile.browserNewShipIndex == 0 && profile.browserNewScoreShip == "A") { "First score" }
        record("B", 3)
        check(profile.browserNewTopIndex == 1 && profile.browserNewShipIndex == 0) { "A tie goes after the earlier score" }
        for (beacons in listOf(10, 11, 12, 13)) record("A", beacons)
        check(profile.browserNewTopIndex == 0 && profile.browserNewShipIndex == 0) { "A new best" }
        record("B", 20)
        check(profile.browserNewTopIndex == 0 && profile.browserNewShipIndex == 0 && profile.browserNewScoreShip == "B") { "Another ship's best" }
        record("A", 12)
        check(profile.browserNewTopIndex == 3 && profile.browserNewShipIndex == 2) { "Ties in both lists" }
        record("A", 2)
        check(profile.browserNewTopIndex == -1 && profile.browserNewShipIndex == -1) { "Trimmed from both lists" }
        return "new score rows"
    }

    /** Round five (U12): SwitchPlanet 0x00418280 sizes the planet from its image list and keeps it on screen. */
    private fun planetPlacement(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val environment = state.currentBeacon.getEnvironment(state)
        val image = environment.diagnosticPlanetImage ?: return "no planet"
        val (x, y, w, h) = environment.diagnosticPlanet!!
        check(image.width == null || (w == image.width && h == image.height)) { "Planet not at its list size" }
        check(x in 0 until maxOf(1, 1280 - w) && y in 0 until maxOf(1, 720 - h)) { "Planet at $x,$y off screen" }
        return "planet"
    }

    /**
     * Round five: ShipSystem::Powered (GetEffectivePower above zero, two for shields) gates the crew slot icon and
     * manning, Zoltan and battery bars included.
     */
    private fun poweredSlot(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val shields = state.player.shields!!
        val weapons = state.player.weapons!!
        val crew = state.player.crew.first()
        val savedShields = shields.powerSelected
        val savedWeapons = weapons.powerSelected
        try {
            setPower(weapons, 0)
            state.player.updateAvailablePower()
            check(!weapons.isNativePowered && !weapons.isMannableBy(crew)) { "Unpowered weapons mannable" }
            setPower(weapons, 1)
            state.player.updateAvailablePower()
            check(weapons.isNativePowered && weapons.isMannableBy(crew)) { "One weapon bar not powered" }
            setPower(shields, 2)
            state.player.updateAvailablePower()
            check(shields.isNativePowered) { "Two shield bars not powered" }
        } finally {
            setPower(shields, savedShields)
            setPower(weapons, savedWeapons)
            state.player.updateAvailablePower()
        }
        return "powered slot"
    }

    /**
     * Round five: the ExplosionAnimation (0x493f70, 0x488e80) keeps the hull whole under small explosions for
     * 1.5 s, then blows it apart, and the wreck is gone when its 3 s tracker ends.
     */
    private fun destruction(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), xyz.znix.xftl.game.Difficulty.NORMAL, 0, 71)
        val ship = state.enemy!!
        ship.health = 0
        var clock = 0f
        var explosions = 0
        while (clock < 1.4f) {
            ship.update(.02f)
            clock += .02f
            explosions = maxOf(explosions, ship.diagnosticDestruction.first)
        }
        check(explosions > 0 && !ship.diagnosticDestruction.second && !ship.isGone) { "No small explosions on the whole hull" }
        while (clock < 1.6f) {
            ship.update(.02f)
            clock += .02f
        }
        check(ship.diagnosticDestruction.second && !ship.isGone) { "Hull not blown apart after 1.5 s" }
        while (clock < 2.9f) {
            ship.update(.02f)
            clock += .02f
        }
        check(!ship.isGone) { "Wreck gone before 3 s" }
        repeat(10) { ship.update(.02f) }
        check(ship.isGone) { "Wreck still there after 3 s" }
        return "destruction"
    }

    /**
     * Round five (T10): ChoiceBox 0x004f7f10 lays an outcome out in one row (the tutorial reward measured 316 x 39
     * natively, drawn 321 wide) and puts a lone message 30px in (the intruder alert measured 186 x 32).
     */
    private fun outcomeBox(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val window = xyz.znix.xftl.game.DialogueWindow(state, state.player, null as xyz.znix.xftl.sector.CreatedEvent?) {}
        val reward = xyz.znix.xftl.game.ResourceSet()
        reward.fuel = 1
        reward.missiles = 3
        reward.scrap = 100
        reward.items.add(state.blueprintManager["BEAM_2"] as xyz.znix.xftl.Blueprint)
        val size = window.diagnosticOutcomeSize(reward)
        check(size.x == 316 && size.y == 39) { "Tutorial reward box $size" }
        val boarders = xyz.znix.xftl.game.ResourceSet()
        boarders.boarderCount = 3
        val alert = window.diagnosticOutcomeSize(boarders)
        check(alert.x == 186 && alert.y == 32) { "Intruder alert box $alert" }
        return "outcome box"
    }

    private fun setPower(system: xyz.znix.xftl.systems.MainSystem, power: Int) {
        repeat(system.energyLevels + 1) { system.decreasePower() }
        repeat(power) { system.increasePower() }
        check(system.powerSelected == power) { "Couldn't set ${system.codename} to $power power" }
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 0.001f

    /** 0x005d6490: the text sits 24 below WARNING!, font 59 has its baseline 16 down and 18 between lines. */
    private fun placement(): String {
        check(WarningFlasher.nativeCentreY(550, 3, false) == 550 + 16 + 36) { "Three-line message baseline" }
        check(WarningFlasher.nativeCentreY(84, 1, true) == 84 + 16 + 24) { "WARNING! line offset" }
        return "placement"
    }

    /**
     * DroneControl 0x0054e080 (WeaponControl alike): room in the system means only the reactor was short (NOT
     * ENOUGH POWER); otherwise the level, the other items or the damage decides the box's message.
     */
    private fun armament(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ui = state.shipUI
        val weapons = state.player.engines!! // the rule is the same for any MainSystem
        val warnings = ui.diagnosticWarnings
        val box = warnings.getValue("weaponPower")
        val levels = weapons.energyLevels
        val savedPower = weapons.powerSelected
        val savedDamage = weapons.damagedEnergyLevels
        try {
            setPower(weapons, 0)
            weapons.damagedEnergyLevels = 0
            ui.armamentPowerFailed(weapons, levels + 1, box)
            check(box.isRunning && box.text == state.translator["warning_needs_upgrade"]) { "Above the level" }

            box.stop()
            setPower(weapons, levels - 1)
            ui.armamentPowerFailed(weapons, 2, box)
            check(box.isRunning && box.text == state.translator["warning_no_system_power"]) { "Taken by other items" }

            box.stop()
            setPower(weapons, 0)
            ui.armamentPowerFailed(weapons, 1, box)
            check(!box.isRunning && warnings.getValue("noPower").isRunning) { "Room in the system means the reactor" }

            weapons.damagedEnergyLevels = levels - 1
            ui.armamentPowerFailed(weapons, 2, box)
            check(box.isRunning && box.text == state.translator["warning_system_broken"]) { "Damaged bars" }
        } finally {
            weapons.damagedEnergyLevels = savedDamage
            weapons.powerLimitChanged()
            setPower(weapons, savedPower)
        }
        return "armament texts"
    }

    /** A missile weapon without missiles is refused with NO MISSILES (0x00569b30), not accepted and dropped. */
    private fun noMissiles(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val slot = player.hardpoints.indexOfFirst { (it.weapon?.type?.missilesUsed ?: 0) > 0 }
        if (slot < 0) return "no missile weapon"
        val weapon = player.hardpoints[slot].weapon!!
        if (weapon.isPowered) state.shipUI.weaponHotkeyPressed(slot, true)
        player.missilesCount = 0
        state.shipUI.weaponHotkeyPressed(slot, false)
        check(!weapon.isPowered) { "A weapon without missiles took power" }
        check(state.shipUI.diagnosticWarnings.getValue("noMissiles").isRunning) { "NO MISSILES didn't start" }
        return "no missiles"
    }

    /** CommandGui::OnLoop 0x00536770: a hit sets the shake to damage / 5, at most 1; an ASB shot to 0.05+. */
    private fun shake(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.shakeForHullDamage(3)
        check(near(state.screenShakeOffset[2], 0.6f)) { "Three hull damage" }
        state.shakeForHullDamage(9)
        check(near(state.screenShakeOffset[2], 1f)) { "Shake magnitude cap" }
        state.shakeForHullDamage(0)
        check(near(state.screenShakeOffset[2], 1f)) { "No damage leaves the shake" }
        state.shakeForHullDamage(1)
        check(near(state.screenShakeOffset[2], 0.2f)) { "A later small hit replaces the magnitude" }
        state.shakeForAsbShot()
        check(near(state.screenShakeOffset[2], 0.2f)) { "ASB shot over a larger shake" }
        return "shake"
    }

    /** SpaceManager 0x004194c0: running (1, 1 - (1-h)^2 / 2, same); paused (0.4 + 0.4(1-h), 0.4, 0.4). */
    private fun tint(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        player.health = player.maxHealth / 2
        val lost = 1f - player.health.toFloat() / player.maxHealth
        val env = state.currentBeacon.getEnvironment(state)
        val running = env.pauseShade(state)
        check(near(running.r, 1f) && near(running.g, 1f - lost * lost * 0.5f) && near(running.b, running.g)) {
            "Running hull tint"
        }
        state.toggleQuickPause()
        val paused = env.pauseShade(state)
        check(near(paused.r, 0.4f + 0.4f * lost) && near(paused.g, 0.4f) && near(paused.b, 0.4f)) { "Paused tint" }
        state.toggleQuickPause()
        return "hull tint"
    }

    /**
     * Credits (start 0x005753b0, render 0x00572240): the menu roll starts at -70 and moves at once; a victory
     * roll starts at 0 behind a 5 s timer and thanks the crew, joined with crewlist_comma / crewlist_and.
     * GameOver::KeyDown (0x004d51a0): Escape skips the victory roll.
     */
    private fun credits(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val menu = xyz.znix.xftl.game.CreditsRoll(state.content)
        check(menu.scroll == -70f) { "Menu credits start" }
        menu.advance(1f)
        check(near(menu.scroll, -126f)) { "Menu credits speed" }

        val victory = xyz.znix.xftl.game.CreditsRoll(
            state.content, xyz.znix.xftl.game.CreditsRoll.Victory("The Kestrel", listOf("Ohm", "Nelis", "Ken"))
        )
        check(victory.scroll == 0f && victory.lineCount == menu.lineCount + 5) { "Victory roll lines" }
        check("Ohm, Nelis and Ken" in victory.texts) { "Crew list: ${victory.texts.take(5)}" }
        victory.advance(4.9f)
        check(victory.scroll == 0f) { "Victory roll moved during its 5 s wait" }
        victory.advance(0.6f)
        check(near(victory.scroll, -28f)) { "Victory roll after the wait" }

        val window = xyz.znix.xftl.game.GameOverWindow(state, xyz.znix.xftl.game.GameOverWindow.Outcome.WIN)
        check(window.victoryCredits != null) { "A victory starts with the credits" }
        window.escapePressed()
        check(window.victoryCredits == null) { "Escape didn't skip the victory credits" }
        check(xyz.znix.xftl.game.GameOverWindow(state, xyz.znix.xftl.game.GameOverWindow.Outcome.LOOSE_HULL)
            .victoryCredits == null) { "A loss rolled credits" }
        return "credits"
    }

    /**
     * U33: WAIT without fuel starts StarMap's 2 s tracker (Waiting...) rather than opening the event, and the
     * map can't be closed meanwhile (StarMap::Close 0x005ea780).
     */
    private fun outOfFuelWait(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.player.fuelCount = 0
        var jumped = false
        val window = xyz.znix.xftl.game.JumpWindow(state, {}, { jumped = true })
        val event = state.currentBeacon.event
        window.waitPressed()
        check(window.isWaiting && !jumped && state.currentBeacon.event === event) { "WAIT opened the event at once" }
        window.escapePressed()
        check(window.isWaiting && !jumped) { "The map closed while waiting" }
        state.setRenderingDeltaTime(0.05f)
        repeat(30) { window.updateUI(0, 0) }
        check(window.isWaiting && !jumped) { "The wait ended within 1.5 s" }
        repeat(12) { window.updateUI(0, 0) }
        check(!window.isWaiting && jumped) { "The wait didn't happen after 2 s" }
        return "out-of-fuel wait"
    }

    /**
     * Round 5c: an over-capacity box stays on the equipment screen, emptied, once something went in it, until
     * the next jump (Equipment +0x3e0/+0x3e1, cleared by Equipment::Jump 0x0053d290).
     */
    private fun overCapacityKept(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val weapon = state.blueprintManager["LASER_BURST_1"] as xyz.znix.xftl.Blueprint
        while (ship.cargoBlueprints[4] == null) ship.addBlueprint(weapon, true)
        check(ship.overCapacityBoxShown[0] && !ship.overCapacityBoxShown[1]) { "The item box wasn't flagged" }
        ship.cargoBlueprints[4] = null
        check(ship.overCapacityBoxShown[0]) { "The emptied item box went away before a jump" }
        ship.resetAfterJump()
        check(!ship.overCapacityBoxShown[0]) { "The emptied item box outlived the jump" }
        return "over-capacity box kept"
    }

    /**
     * Round 5c: printCenter's half pixels follow native roundf (0x007458d0) in the caller's frame: up right of
     * its origin (drawString's rounding), left of it as in Equipment's frame for the over-capacity boxes.
     */
    private fun centring(): String {
        val fonts = xyz.znix.xftl.rendering.NativeFonts
        check(fonts.centredStart(155.5f, 0f) == 155.5f) { "A half pixel in the screen frame moved" }
        check(fonts.centredStart(155.5f, 342f) == 155f) { "A half pixel left of the frame didn't round left" }
        check(fonts.centredStart(155f, 342f) == 155f) { "A whole pixel moved" }
        return "centring"
    }

    /** P11: an event's neutral ship, which its beacon doesn't keep, is still there (and neutral) after a reload. */
    private fun neutralShipReload(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), xyz.znix.xftl.game.Difficulty.NORMAL, 1, 5)
        state.browserEndHostility()
        state.currentBeacon.ship = null
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        check(loaded.enemy != null) { "The neutral ship vanished on reload" }
        check(loaded.browserCanWait()) { "The neutral ship came back hostile" }
        return "neutral ship reload"
    }
}
