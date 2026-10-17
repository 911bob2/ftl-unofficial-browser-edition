package ftl.browser

import xyz.znix.xftl.game.*
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/** UI coverage with explicit controller observations. Native transition setup
 * and a complete playable tutorial are separate acceptance requirements. */
object BrowserTutorialOverlayDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        val profileXml = BrowserXml.write(profile.save())
        val run = BrowserFiles.getRun()
        val storedProfile = BrowserFiles.getProfile()
        val rng = BrowserRandom.snapshot()
        val mode = main.gameContent.enableAdvancedEdition
        val browserMode = BrowserFiles.enableAE
        try {
            main.switchToMainMenu()
            var state = main.startTutorialSession()
            val overlay = main.tutorialOverlay!!
            val controller = main.tutorialAdapter!!.controller
            // Dismiss the native start event before testing instruction input.
            repeat(90) {
                main.update(container, .05f)
                if (state.shipUI.isWindowOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            main.render(container, graphics)
            val panel = overlay.panelBounds(state)
            val continueBox = overlay.continueBounds(state)
            BrowserEngine.mouse(panel.x + 40, panel.y + 50, 0, 0)
            graphics.tooltip = object : xyz.znix.xftl.rendering.StandardTooltip(state) {
                override fun getText() = "Underlying tooltip"
            }
            overlay.draw(state, graphics)
            check(graphics.tooltip == null && state.currentCursor == null) { "Gameplay hover covered tutorial instructions" }
            val crewPos = state.shipUI.crewScreenPos(state.playerCrew.first(), ConstPoint(350, 170))
            var fired = 0
            state.clickEvent = InGameState.RoomClickListener { _, _ -> fired++ }
            BrowserEngine.mouse(panel.x + 40, panel.y + 50, 1, Input.MOUSE_LEFT_BUTTON)
            BrowserEngine.step(0f)
            BrowserEngine.mouse(crewPos.x + 8, crewPos.y + 8, 0, Input.MOUSE_LEFT_BUTTON)
            BrowserEngine.step(0f)
            check(fired == 0 && state.clickEvent != null) { "Instruction click/drag reached room targeting" }
            check(main.doSaveLoadGame()) { "BrowserTutorialOverlayDiagnostics.kt:50" }
            state = main.currentState as InGameState
            BrowserEngine.mouse(crewPos.x + 8, crewPos.y + 8, 2, Input.MOUSE_LEFT_BUTTON)
            BrowserEngine.step(0f)
            check(state.shipUI.tutorialSelectedCrewCount == 0) { "Captured release selected underlying crew after reload" }
            check(main.tutorialOverlay === overlay && controller.index == 0) { "BrowserTutorialOverlayDiagnostics.kt:55" }

            // Continue uses the actual engine pointer path, once per press.
            BrowserEngine.mouse(continueBox.x + 50, continueBox.y + 25, 1, Input.MOUSE_LEFT_BUTTON)
            BrowserEngine.step(0f); BrowserEngine.step(0f)
            check(controller.index == 1) { "BrowserTutorialOverlayDiagnostics.kt:60" }
            BrowserEngine.mouse(0, 0, 2, Input.MOUSE_LEFT_BUTTON); BrowserEngine.step(0f)

            state.shipUI.showOptionsWindow()
            check(overlay.isSuppressed(state) && !overlay.hitTest(state, panel.x + 40, panel.y + 50)) { "BrowserTutorialOverlayDiagnostics.kt:64" }
            check(!overlay.click(state, Input.MOUSE_LEFT_BUTTON, panel.x + 40, panel.y + 50)) { "BrowserTutorialOverlayDiagnostics.kt:65" }
            state.cancelMenu()
            state.openDebugConsole()
            check(overlay.isSuppressed(state)) { "BrowserTutorialOverlayDiagnostics.kt:68" }
            state.cancelMenu()
            check(!overlay.isSuppressed(state) && controller.index == 1) { "BrowserTutorialOverlayDiagnostics.kt:70" }

            val positive = BrowserTutorialController.Observation(
                enginePower = 2, selectedCrew = 1, engineCrew = 1, engineHealth = 2, engineCapacity = 2,
                airlockOpen = true, roomFires = 0, pilotCrew = 1, playerPaused = true,
                nonMissilePowered = true, nonMissileFired = true, missileFired = true,
                enemyPresent = false, beamEquipped = true, pendingWeaponUpgrades = 1, beamPowered = true
            )
            val steps = BrowserTutorialDefinition(main.gameContent.datafile).steps
            var rendered = 1
            fun window(id: String) = state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(id), container.input)
            while (!controller.completed) {
                val index = controller.index
                val step = controller.step
                BrowserEngine.diagnosticProgress("tutorial overlay ${index + 1}/33 ${step.id}")
                if (step.id == "INVENTORY_1") window(VanillaHotkeys.SHIP_INVENTORY)
                if (step.id == "COMBAT_5") { state.cancelMenu(); window(VanillaHotkeys.SHIP_UPGRADES) }
                if (step.id == "UPGRADES_2") state.cancelMenu()
                val before = BrowserXml.write(state.saveGameState())
                val beforeRng = BrowserRandom.snapshot()
                repeat(3) { BrowserDebugState.snapshot(main) }
                check(BrowserXml.write(state.saveGameState()) == before && BrowserRandom.snapshot() == beforeRng) {
                    "Tutorial diagnostic geometry changed gameplay state/RNG"
                }
                val expectedFlash = when (step.id) { "COMBAT_1" -> 0; "COMBAT_5" -> 2; else -> -1 }
                check(state.shipUI.tutorialWeaponFlashSlot == expectedFlash) { "BrowserTutorialOverlayDiagnostics.kt:95" }
                val pulse = BrowserTutorialVisuals(controller)
                pulse.update(.1875f)
                check(pulse.arrowAlpha == .5f) { "BrowserTutorialOverlayDiagnostics.kt:98" }
                pulse.update(.1875f)
                check(pulse.arrowAlpha == 1f) { "BrowserTutorialOverlayDiagnostics.kt:100" }
                pulse.update(.375f)
                check(pulse.arrowAlpha == 0f) { "BrowserTutorialOverlayDiagnostics.kt:102" }
                // These samples are derived from native GetAlphaLevel, not a
                // screenshot guess. Wrong weapon slots must never receive it.
                pulse.updateWeapon(.375f)
                for (slot in 0..3)
                    check(pulse.weaponAlpha(slot) == if (slot == expectedFlash) 1f else 0f) { "BrowserTutorialOverlayDiagnostics.kt:107" }
                if (step.id == "COMBAT_1") {
                    val previousAlpha = state.shipUI.tutorialWeaponFlashAlpha(0)
                    state.shipUI.updateAlways(.05f)
                    val nextAlpha = state.shipUI.tutorialWeaponFlashAlpha(0)
                    state.shipUI.updateAlways(.05f)
                    check(nextAlpha != previousAlpha || state.shipUI.tutorialWeaponFlashAlpha(0) != previousAlpha) { "BrowserTutorialOverlayDiagnostics.kt:113" }
                    check(state.shipUI.tutorialWeaponFlashAlpha(1) == 0f) { "BrowserTutorialOverlayDiagnostics.kt:114" }
                    val equipped = state.player.hardpoints[0].weapon
                    try {
                        state.player.hardpoints[0].weapon = null
                        val emptyAlpha = state.shipUI.tutorialWeaponFlashAlpha(0)
                        state.shipUI.updateAlways(.1875f)
                        val middleAlpha = state.shipUI.tutorialWeaponFlashAlpha(0)
                        state.shipUI.updateAlways(.1875f)
                        check(middleAlpha != emptyAlpha || state.shipUI.tutorialWeaponFlashAlpha(0) != emptyAlpha) {
                            "Native flash clock stopped at an existing empty weapon box"
                        }
                    } finally { state.player.hardpoints[0].weapon = equipped }
                    // UI clocks continue while paused; rendering alone cannot
                    // consume RNG, change power/targets or advance a lesson.
                    for (fps in listOf(30, 60, 120, 240)) {
                        val clock = BrowserTutorialVisuals(controller)
                        repeat(fps) { clock.updateWeapon(1f / fps); main.render(container, graphics) }
                        check(kotlin.math.abs(clock.weaponAlpha(0) - 2f / 3f) < .0001f) { "BrowserTutorialOverlayDiagnostics.kt:131" }
                    }
                    BrowserEngine.diagnosticCapture("COMBAT_1_POWER_FLASH")
                }
                repeat(3) { main.render(container, graphics) }
                check(BrowserXml.write(state.saveGameState()) == before && BrowserRandom.snapshot() == beforeRng) {
                    "Rendering tutorial ${step.id} changed game state/RNG"
                }
                val bounds = overlay.panelBounds(state)
                val next = overlay.continueBounds(state)
                check(bounds.width == 323 && bounds.height >= 212) { "BrowserTutorialOverlayDiagnostics.kt:141" }
                check(next.width == 170 && next.height == 32) { "BrowserTutorialOverlayDiagnostics.kt:142" }
                if (controller.canContinue) {
                    check(!overlay.hitTest(state, next.x - 10, next.y + 10)) {
                        "Continue texture glow became a clickable button at ${step.id}"
                    }
                }
                // Check actual private instruction text at the native font metrics.
                check(BrowserTutorialOverlay.INSTRUCTION_FONT_NAME == "JustinFont11Bold") { "BrowserTutorialOverlayDiagnostics.kt:149" }
                check(BrowserTutorialOverlay.TEXT_BASELINE_OFFSET == 12 && BrowserTutorialOverlay.TEXT_LINE_SPACING == 20) { "BrowserTutorialOverlayDiagnostics.kt:150" }
                val instructionFont = state.content.getFont("JustinFont11Bold")
                val lines = instructionFont.wrapString(state.content.translator[step.text], 308)
                check(lines.all { instructionFont.getWidth(it.trimEnd()) <= 308 }) {
                    "Tutorial instruction wraps outside native width at ${step.id}"
                }
                val textBottom = bounds.y + 15 + lines.size * 20
                check(textBottom <= bounds.y + bounds.height) { "Tutorial text overflows its panel at ${step.id}" }
                val expectedPosition = when {
                    step.id == "STAR_MAP_1" -> 50 to 200
                    step.id in setOf("UPGRADES_1", "UPGRADES_1_2") -> 0 to 230
                    step.id == "INVENTORY_2" -> 0 to 185
                    index in 18..24 -> 520 to 3
                    else -> 900 to 50
                }
                check(bounds.x to bounds.y == expectedPosition) { "Incorrect native panel anchor at ${step.id}" }
                check(bounds.x >= 0 && bounds.x + bounds.width <= 1280 && next.y + next.height <= 720) { "BrowserTutorialOverlayDiagnostics.kt:166" }
                check(overlay.hitTest(state, bounds.x + 40, bounds.y + 50)) { "BrowserTutorialOverlayDiagnostics.kt:167" }
                check(!overlay.hitTest(state, bounds.x - 10, bounds.y + 50)) { "BrowserTutorialOverlayDiagnostics.kt:168" }
                check(overlay.click(state, Input.MOUSE_RIGHT_BUTTON, bounds.x + 40, bounds.y + 50)) { "BrowserTutorialOverlayDiagnostics.kt:169" }
                check(controller.index == index) { "BrowserTutorialOverlayDiagnostics.kt:170" }
                state.shipUI.tutorialShipWindow?.let { shipWindow ->
                    val tab = shipWindow.tutorialTab
                    val x = if (tab == ShipWindow.Tab.EQUIPMENT) 120 else 230
                    shipWindow.mouseClick(Input.MOUSE_LEFT_BUTTON, shipWindow.position.x + x, shipWindow.position.y + 12)
                    check(shipWindow.tutorialTab == tab) { "Tutorial tab lock accepted a tab change" }
                    state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(VanillaHotkeys.SHIP_CREW), container.input)
                    check(state.shipUI.tutorialShipWindow === shipWindow && shipWindow.tutorialTab == tab) { "BrowserTutorialOverlayDiagnostics.kt:177" }
                }
                BrowserEngine.diagnosticCapture(step.id)
                rendered++
                if (!controller.canContinue) {
                    check(!overlay.click(state, Input.MOUSE_LEFT_BUTTON, next.x + 50, next.y + 25)) { "BrowserTutorialOverlayDiagnostics.kt:182" }
                    check(controller.index == index) { "BrowserTutorialOverlayDiagnostics.kt:183" }
                    controller.observe(positive.copy(
                        mapOpen = step.id == "DAMAGE_SYSTEM_7",
                        shipInfoOpen = step.id == "INVENTORY_1",
                        upgradesOpen = step.id == "COMBAT_5"
                    ))
                }
                if (controller.index == index && controller.canContinue)
                    check(overlay.click(state, Input.MOUSE_LEFT_BUTTON, next.x + 50, next.y + 25)) { "BrowserTutorialOverlayDiagnostics.kt:191" }
                check(controller.completed || controller.index == index + 1) { "BrowserTutorialOverlayDiagnostics.kt:192" }
            }
            check(rendered == steps.size) { "BrowserTutorialOverlayDiagnostics.kt:194" }
            check(!main.isTutorialRunning && main.currentState is MainMenuState) { "BrowserTutorialOverlayDiagnostics.kt:195" }
            check(main.profile === profile && BrowserXml.write(profile.save()) == profileXml) { "BrowserTutorialOverlayDiagnostics.kt:196" }
            check(BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialOverlayDiagnostics.kt:197" }
            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == storedProfile) { "Tutorial completion published ordinary progress" }

            // The pause menu's Main Menu restores the ordinary session and releases held input.
            state = main.startTutorialSession()
            main.update(container, 0f)
            val freshOverlay = main.tutorialOverlay!!
            check(state.shipUI.tutorialDialogueOpen && freshOverlay.isSuppressed(state)) { "BrowserTutorialOverlayDiagnostics.kt:204" }
            repeat(90) {
                main.update(container, .05f)
                if (state.shipUI.tutorialDialogueOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            check(!freshOverlay.isSuppressed(state)) { "BrowserTutorialOverlayDiagnostics.kt:212" }
            state.shipUI.showPauseWindow()
            val menu = state.shipUI.diagnosticPauseWindow!!
            val exit = BrowserTutorialOverlay.Bounds(menu.position.x + menu.size.x / 2 - 40, menu.position.y + 69, 80, 24)
            BrowserEngine.key(Input.KEY_LSHIFT, 0, true)
            BrowserEngine.mouse(exit.x + 40, exit.y + 12, 1, Input.MOUSE_LEFT_BUTTON)
            BrowserEngine.step(0f)
            check(!main.isTutorialRunning && main.currentState is MainMenuState) { "BrowserTutorialOverlayDiagnostics.kt:219" }
            check(!container.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON) && !container.input.isKeyDown(Input.KEY_LSHIFT)) { "BrowserTutorialOverlayDiagnostics.kt:220" }
            // Releasing the press must not click a title menu item underneath.
            BrowserEngine.mouse(exit.x + 40, exit.y + 12, 2, Input.MOUSE_LEFT_BUTTON)
            check(main.currentState is MainMenuState) { "BrowserTutorialOverlayDiagnostics.kt:223" }
            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == storedProfile) { "Tutorial exit published ordinary progress" }
            if (profile.dirty) {
                main.update(container, 0f)
                check(!profile.dirty && BrowserFiles.getProfile() == profileXml) { "Pending ordinary profile changes were lost after exit" }
            }
        } finally {
            main.endTutorialSession()
            BrowserFiles.hydrate(run, storedProfile)
            BrowserFiles.enableAE = browserMode
            BrowserRandom.restore(rng)
        }
    }
}
