package ftl.browser

import xyz.znix.xftl.Constants.ROOM_SIZE
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.BeamBlueprint

/** Time-stepped action traversal using normal ship mechanics and input paths.
 * No controller observations, direct rewards, hull changes, charge grants,
 * invulnerability or enemy-fire suppression. This is automated acceptance;
 * it does not replace human play or native visual/timing comparisons. */
object BrowserTutorialPlayDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        val profileXml = BrowserXml.write(profile.save())
        val profileWasDirty = profile.dirty
        val savedProfile = BrowserFiles.getProfile()
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val mode = main.gameContent.enableAdvancedEdition
        val browserMode = BrowserFiles.enableAE
        val focused = main.isBrowserFocused
        try {
            // The probe runs synchronously, including when launched from a
            // toolbar button. Its temporary simulation must have active focus.
            main.setBrowserFocus(true)
            main.switchToMainMenu()
            var state = main.startTutorialSession()
            val controller = main.tutorialAdapter!!.controller
            fun tick() {
                check(!state.debugFlags.noDmg.set && !state.debugFlags.noEnemyFire.set) { "BrowserTutorialPlayDiagnostics.kt:35" }
                check(state.player.health > 0) { "Tutorial ship died at ${controller.step.id}" }
                main.update(container, .05f)
            }
            fun waitFor(label: String, condition: () -> Boolean) {
                var frames = 0
                while (!condition() && frames++ < 12000) tick()
                check(condition()) { "Tutorial action timed out: $label at ${controller.step.id}; ${BrowserDebugState.snapshot(main)}" }
            }
            fun click(x: Int, y: Int, button: Int = Input.MOUSE_LEFT_BUTTON) {
                main.render(container, graphics)
                BrowserEngine.mouse(x, y, 0, button); BrowserEngine.step(0f)
                BrowserEngine.mouse(x, y, 1, button); BrowserEngine.step(0f)
                BrowserEngine.mouse(x, y, 2, button); BrowserEngine.step(0f)
            }
            fun hotkey(id: String) {
                val weapon = VanillaHotkeys.WEAPON_SLOTS.indexOf(id)
                if (weapon >= 0) state.shipUI.weaponHotkeyPressed(weapon, false)
                else state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(id), container.input)
                main.update(container, 0f)
            }
            fun pause(paused: Boolean) {
                if (state.isPlayerPaused == paused) return
                BrowserEngine.key(Input.KEY_SPACE, ' '.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(Input.KEY_SPACE, ' '.code, false); BrowserEngine.step(0f)
                check(state.isPlayerPaused == paused) { "BrowserTutorialPlayDiagnostics.kt:60" }
            }
            fun dismiss() {
                repeat(10) {
                    val dialogue = state.shipUI.diagnosticDialogue
                    if (dialogue != null) {
                        waitFor("event-choice delay") { dialogue.choiceInputReady }
                        main.render(container, graphics)
                        val centre = checkNotNull(dialogue.choiceCentre(0))
                        click(centre.x, centre.y)
                    }
                }
                check(!state.shipUI.tutorialDialogueOpen) { "BrowserTutorialPlayDiagnostics.kt:72" }
            }
            fun move(room: Room) {
                // Same room-order operation used by the crew pointer handler.
                check(state.playerCrew.first().setTargetRoom(room)) { "BrowserTutorialPlayDiagnostics.kt:76" }
                waitFor("crew movement") { state.playerCrew.first().room === room }
                state.player.update(0f)
                main.update(container, 0f)
            }
            fun continueInstruction() {
                check(controller.canContinue) { "BrowserTutorialPlayDiagnostics.kt:82" }
                val bounds = main.tutorialOverlay!!.continueBounds(state)
                click(bounds.x + 50, bounds.y + 25)
            }
            fun target(slot: Int) {
                hotkey(VanillaHotkeys.WEAPON_SLOTS[slot])
                main.render(container, graphics)
                val room = state.enemy!!.shields!!.room!!
                val centre = state.enemyShipRenderToScreen(
                    ((room.position.x + state.enemy!!.offset.x) * ROOM_SIZE + ROOM_SIZE / 2),
                    ((room.position.y + state.enemy!!.offset.y) * ROOM_SIZE + ROOM_SIZE / 2))
                click(centre.x, centre.y)
            }
            dismiss()
            val steps = BrowserTutorialDefinition(main.gameContent.datafile).steps
            for ((index, step) in steps.withIndex()) {
                BrowserEngine.diagnosticProgress("tutorial normal mechanics ${index + 1}/33 ${step.id}")
                check(controller.index == index) { "Unexpected tutorial transition to ${controller.step.id}" }
                if (step.continueButton) {
                    continueInstruction()
                    continue
                }
                when (step.id) {
                    "POWER_SYSTEM_2" -> { hotkey("sys_power_engines"); check(controller.canContinue) { "BrowserTutorialPlayDiagnostics.kt:105" } }
                    "DAMAGE_SYSTEM_2" -> hotkey(VanillaHotkeys.SELECT_CREW.first())
                    "DAMAGE_SYSTEM_3" -> move(state.player.engines!!.room!!)
                    "DAMAGE_SYSTEM_4" -> waitFor("engine repair") { controller.canContinue }
                    "FIRE_PC_1" -> {
                        val door = state.player.rooms.single { it.id == 9 }.doors.first { it.isAirlock }
                        check(door.click(door.pixelCentre.x, door.pixelCentre.y)) { "BrowserTutorialPlayDiagnostics.kt:111" }
                        main.update(container, 0f)
                    }
                    "FIRE_END" -> waitFor("venting") { controller.canContinue }
                    "DAMAGE_SYSTEM_6" -> {
                        hotkey(VanillaHotkeys.SYS_ACTION_DOOR_CLOSE)
                        move(state.player.piloting!!.room!!)
                        waitFor("pilot room") { controller.canContinue }
                    }
                    "DAMAGE_SYSTEM_7" -> {
                        waitFor("FTL charge") { state.player.isFtlReady }
                        hotkey(VanillaHotkeys.FTL_JUMP)
                        check(state.shipUI.tutorialMapOpen) { "BrowserTutorialPlayDiagnostics.kt:123" }
                    }
                    "STAR_MAP_1" -> {
                        val window = state.shipUI.tutorialJumpWindow!!
                        main.render(container, graphics)
                        // Native instructions cover part of the map. Some RNG
                        // states put the first neighbour beneath that panel.
                        val overlay = main.tutorialOverlay!!
                        val beacon = state.currentBeacon.neighbours.firstOrNull {
                            !it.isExit && !overlay.hitTest(state,
                                window.position.x + Sector.OFFSET.x + it.pos.x,
                                window.position.y + Sector.OFFSET.y + it.pos.y)
                        }
                        checkNotNull(beacon) { "No visible tutorial neighbour outside the instruction panel" }
                        val fuel = state.player.fuelCount
                        click(window.position.x + Sector.OFFSET.x + beacon.pos.x,
                            window.position.y + Sector.OFFSET.y + beacon.pos.y)
                        check(!state.shipUI.tutorialMapOpen && state.player.fuelCount == fuel - 1) {
                            "Tutorial map click did not initiate a jump; ${BrowserDebugState.snapshot(main)}"
                        }
                        waitFor("tutorial pirate arrival") { state.shipUI.tutorialDialogueOpen && state.enemy != null }
                        dismiss()
                    }
                    "COMBAT_0" -> {
                        check(state.enemy!!.hardpoints.mapNotNull { it.weapon }.single().type.name == "LASER_BURST_1") { "BrowserTutorialPlayDiagnostics.kt:147" }
                        waitFor("pirate weapon firing") {
                            state.enemy!!.hardpoints.mapNotNull { it.weapon }.any { (it.browserEncounterVolleys ?: 0) > 0 }
                        }
                        pause(true)
                    }
                    "COMBAT_1" -> {
                        hotkey(VanillaHotkeys.WEAPON_SLOTS[0])
                        check(state.player.hardpoints[0].weapon!!.isPowered) { "BrowserTutorialPlayDiagnostics.kt:155" }
                    }
                    "COMBAT_2" -> {
                        target(0); pause(false)
                        waitFor("burst firing") { controller.step.id != step.id }
                        pause(true)
                    }
                    "COMBAT_4" -> {
                        waitFor("missile event") { state.shipUI.tutorialDialogueOpen }
                        dismiss()
                        // Swap the available power normally: burst uses two,
                        // Artemis one; the unupgraded system cannot power both.
                        state.player.weapons!!.setWeaponPower(state.player.hardpoints[0].weapon!!, false)
                        hotkey(VanillaHotkeys.WEAPON_SLOTS[1])
                        target(1); pause(false)
                        waitFor("missile firing") { controller.step.id != step.id }
                    }
                    "COMBAT_4_6" -> {
                        // Keep the learned missile target firing until the
                        // pirate dies. Ammunition comes from native tutorial rules.
                        if (!state.player.weapons!!.autofire) state.shipUI.toggleAutofire()
                        target(1); pause(false)
                        waitFor("pirate defeat") { state.enemy == null }
                        dismiss()
                    }
                    "INVENTORY_1" -> hotkey(VanillaHotkeys.SHIP_INVENTORY)
                    "INVENTORY_2" -> {
                        val window = state.shipUI.tutorialShipWindow!!
                        main.render(container, graphics)
                        val cargo = state.player.cargoBlueprints.indexOfFirst { it is BeamBlueprint }
                        check(cargo in 0..3) { "Tutorial victory did not put the beam in cargo" }
                        val box = state.getImg("img/upgradeUI/Equipment/box_weapons_off.png")
                        val weaponX = (579 - (box.width + 8) * 2) / 2 + box.width + 8
                        val startX = window.position.x + 23 + 130 * (cargo % 2) + 30
                        val startY = window.position.y + 293 + 80 * (cargo / 2) + 20
                        val endX = window.position.x + weaponX + 30
                        val endY = window.position.y + 90
                        BrowserEngine.mouse(startX, startY, 0, 0); BrowserEngine.step(0f)
                        BrowserEngine.mouse(startX, startY, 1, 0); BrowserEngine.step(0f)
                        BrowserEngine.mouse(endX, endY, 0, 0); BrowserEngine.step(0f)
                        BrowserEngine.mouse(endX, endY, 2, 0); BrowserEngine.step(0f)
                        check(state.player.hardpoints[1].weapon!!.type is BeamBlueprint) { "BrowserTutorialPlayDiagnostics.kt:196" }
                        click(window.position.x + 480, window.position.y + 485)
                    }
                    "COMBAT_5" -> hotkey(VanillaHotkeys.SHIP_UPGRADES)
                    "UPGRADES_1_2" -> {
                        val window = state.shipUI.tutorialShipWindow!!
                        main.render(container, graphics)
                        val weapons = state.player.mainSystems.indexOfFirst { it === state.player.weapons }
                        click(window.position.x + 62 + 66 * weapons, window.position.y + 180)
                        check(window.pendingWeaponUpgrades == 1) { "BrowserTutorialPlayDiagnostics.kt:205" }
                        click(window.position.x + 480, window.position.y + 485)
                    }
                    "UPGRADES_2" -> hotkey(VanillaHotkeys.WEAPON_SLOTS[1])
                    else -> error("Missing normal tutorial action ${step.id}")
                }
                if (controller.index == index && controller.canContinue) continueInstruction()
                check(controller.index == index + 1) { "Normal action failed at ${step.id}: ${controller.step.id}" }
            }
            check(controller.completed && !main.isTutorialRunning && main.currentState is MainMenuState) { "BrowserTutorialPlayDiagnostics.kt:214" }
            check(main.profile === profile && BrowserXml.write(profile.save()) == profileXml) { "BrowserTutorialPlayDiagnostics.kt:215" }
            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == if (profileWasDirty) profileXml else savedProfile) {
                "Tutorial changed ordinary saved progress"
            }
            check(BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialPlayDiagnostics.kt:219" }
        } finally {
            main.endTutorialSession()
            BrowserFiles.hydrate(run, savedProfile)
            BrowserFiles.enableAE = browserMode
            BrowserRandom.restore(rng)
            main.setBrowserFocus(focused)
        }
    }
}
