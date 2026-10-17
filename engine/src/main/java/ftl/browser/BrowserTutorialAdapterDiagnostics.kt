package ftl.browser

import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.*

/** Actual engine observations/actions with explicit fixture setup. This is
 * adapter coverage, not a played tutorial or verification of native setup/UI. */
object BrowserTutorialAdapterDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        val profileXml = BrowserXml.write(profile.save())
        val storedProfile = BrowserFiles.getProfile()
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val mode = main.gameContent.enableAdvancedEdition
        val browserMode = BrowserFiles.enableAE
        try {
            main.switchToMainMenu()
            var state = main.startTutorialSession()
            val adapter = main.tutorialAdapter!!
            val controller = adapter.controller
            fun tick() = main.update(container, 0f)
            fun hotkey(id: String) = state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(id), container.input)
            fun move(room: Room) {
                val crew = state.playerCrew.first()
                check(crew.setTargetRoom(room)) { "Tutorial fixture could not order crew to room ${room.id}" }
                repeat(600) { if (crew.room !== room) state.updateGameState(.05f) }
                room.updateCrewInRoom()
                check(crew.room === room) { "Crew did not reach room ${room.id}" }
            }
            fun equip(name: String, slot: Int): AbstractWeaponInstance {
                val ship = state.player
                ship.hardpoints[slot].weapon?.let { ship.weapons!!.setWeaponPower(it, false) }
                return (state.blueprintManager[name] as AbstractWeaponBlueprint).buildInstance(ship).also {
                    ship.hardpoints[slot].weapon = it
                    it.bindToWeaponsSystem(ship.weapons!!)
                }
            }
            fun fire(weapon: AbstractWeaponInstance) {
                check(state.player.weapons!!.setWeaponPower(weapon, true)) { "BrowserTutorialAdapterDiagnostics.kt:45" }
                weapon.timeCharged = weapon.chargeTime
                (weapon as IRoomTargetingWeapon).fire { state.enemy!!.rooms.first() }
                repeat(60) { if ((weapon.browserEncounterVolleys ?: 0) == 0) weapon.update(.05f, 0f, false) }
                check((weapon.browserEncounterVolleys ?: 0) > 0) { "Fixture weapon did not actually fire" }
            }
            fun refused() {
                val index = controller.index
                check(adapter.continueStep(state) == null) { "BrowserTutorialAdapterDiagnostics.kt:53" }
                tick()
                check(controller.index == index && !controller.canContinue) { "Wrong action accepted at ${controller.step.id}" }
            }
            fun upgradeClick(button: Int) {
                val window = state.shipUI.tutorialShipWindow!!
                main.render(container, graphics)
                val index = state.player.mainSystems.indexOfFirst { it === state.player.weapons }
                check(index >= 0) { "BrowserTutorialAdapterDiagnostics.kt:61" }
                window.mouseClick(button, window.position.x + 62 + 66 * index, window.position.y + 180)
            }
            // Clear the native starting dialogue before instruction actions.
            repeat(90) {
                main.update(container, .05f)
                if (state.shipUI.isWindowOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            check(!state.shipUI.isWindowOpen) { "BrowserTutorialAdapterDiagnostics.kt:72" }
            // Both inputs and disabled artwork use native opening permissions,
            // even when the ship is otherwise ready and out of danger.
            state.player.ftlChargeProgress = 1f
            state.player.update(0f)
            check(state.player.isFtlReady && state.player.canChargeFTL && !state.isInDanger) { "BrowserTutorialAdapterDiagnostics.kt:77" }
            val navBefore = BrowserXml.write(state.saveGameState())
            val navRng = BrowserRandom.snapshot()
            hotkey(VanillaHotkeys.FTL_JUMP)
            hotkey(VanillaHotkeys.SHIP_UPGRADES)
            hotkey(VanillaHotkeys.SHIP_CREW)
            hotkey(VanillaHotkeys.SHIP_INVENTORY)
            state.shipUI.openJumpMap()
            state.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, 546, 44, xyz.znix.xftl.math.ConstPoint.ZERO)
            state.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, 647, 44, xyz.znix.xftl.math.ConstPoint.ZERO)
            val jumpButton = Buttons.JumpButton(xyz.znix.xftl.math.ConstPoint.ZERO, state.player, state) {
                error("Disabled tutorial Jump button dispatched its action")
            }
            val shipButton = Buttons.ShipButton(xyz.znix.xftl.math.ConstPoint.ZERO, state) {
                error("Disabled tutorial Ship button dispatched its action")
            }
            check(jumpButton.disabled && shipButton.disabled) { "BrowserTutorialAdapterDiagnostics.kt:93" }
            check(jumpButton.mouseDown(Input.MOUSE_LEFT_BUTTON, 15, 15) && shipButton.mouseDown(Input.MOUSE_LEFT_BUTTON, 15, 15)) {
                "Disabled tutorial navigation did not consume a click inside its hitbox"
            }
            check(!state.shipUI.isWindowOpen && BrowserXml.write(state.saveGameState()) == navBefore && BrowserRandom.snapshot() == navRng) { "BrowserTutorialAdapterDiagnostics.kt:97" }
            state.debugFlags.noDmg.set = true
            state.debugFlags.noEnemyFire.set = true
            state.player.purchasedReactorPower = 25
            state.player.updateAvailablePower()
            val steps = BrowserTutorialDefinition(main.gameContent.datafile).steps
            for ((index, step) in steps.withIndex()) {
                BrowserEngine.diagnosticProgress("tutorial live adapter ${index + 1}/33 ${step.id}")
                check(controller.index == index && controller.step.id == step.id) { "BrowserTutorialAdapterDiagnostics.kt:105" }
                check(controller.mapOpeningAllowed == (step.id == "DAMAGE_SYSTEM_7")) { "BrowserTutorialAdapterDiagnostics.kt:106" }
                check(controller.shipOpeningAllowed == (step.id in setOf("INVENTORY_1", "COMBAT_5"))) { "BrowserTutorialAdapterDiagnostics.kt:107" }
                if (!state.shipUI.isWindowOpen) {
                    if (!controller.mapOpeningAllowed) { hotkey(VanillaHotkeys.FTL_JUMP); check(!state.shipUI.tutorialMapOpen) { "BrowserTutorialAdapterDiagnostics.kt:109" } }
                    if (!controller.shipOpeningAllowed) {
                        for (id in listOf(VanillaHotkeys.SHIP_UPGRADES, VanillaHotkeys.SHIP_CREW, VanillaHotkeys.SHIP_INVENTORY)) {
                            hotkey(id); check(state.shipUI.tutorialShipWindow == null) { "Premature Ship hotkey opened at ${step.id}" }
                        }
                    }
                }
                val beforeRng = BrowserRandom.snapshot()
                val beforeShip = BrowserXml.write(state.saveGameState())
                repeat(3) { adapter.observation(state) }
                check(BrowserRandom.snapshot() == beforeRng && BrowserXml.write(state.saveGameState()) == beforeShip) {
                    "Tutorial observation changed simulation state"
                }
                if (step.continueButton) {
                    if (step.id == "UPGRADES_1") {
                        val window = state.shipUI.tutorialShipWindow!!
                        state.cancelMenu()
                        check(state.shipUI.tutorialShipWindow === window) { "Escape bypassed upgrade explanation" }
                    }
                    check(adapter.continueStep(state) != null) { "BrowserTutorialAdapterDiagnostics.kt:128" }
                    continue
                }
                when (step.id) {
                    "POWER_SYSTEM_2" -> {
                        val engines = state.player.engines!!
                        repeat(engines.powerSelected) { engines.decreasePower() }
                        refused()
                        engines.increasePower(); tick()
                        check(controller.canContinue && controller.index == index) { "BrowserTutorialAdapterDiagnostics.kt:137" }
                        engines.decreasePower(); tick()
                        check(controller.canContinue) { "Depowering lost Continue latch" }
                        engines.increasePower()
                    }
                    "DAMAGE_SYSTEM_2" -> {
                        refused()
                        hotkey(VanillaHotkeys.SELECT_CREW.first()); tick()
                    }
                    "DAMAGE_SYSTEM_3" -> {
                        refused()
                        move(state.player.engines!!.room!!); tick()
                    }
                    "DAMAGE_SYSTEM_4" -> {
                        val engines = state.player.engines!!
                        engines.dealDamage(1, 0); refused()
                        repeat(1200) { if (engines.damaged) state.updateGameState(.05f) }
                        check(!engines.damaged) { "Real crew did not repair engines" }
                        tick()
                    }
                    "FIRE_PC_1" -> {
                        val room = state.player.rooms.single { it.id == 9 }
                        val airlock = room.doors.first { it.isAirlock }
                        airlock.open = false
                        room.fires[0] = FireInstance(room, 0)
                        refused()
                        state.player.rooms.first { it.doors.any { d -> d.isAirlock } && it !== room }
                            .doors.first { it.isAirlock }.open = true
                        refused()
                        airlock.open = true; tick()
                    }
                    "FIRE_END" -> {
                        refused()
                        val room = state.player.rooms.single { it.id == 9 }
                        room.oxygen = 0f
                        repeat(400) { room.fires.filterNotNull().forEach { it.update(.05f) } }
                        check(room.fires.all { it == null }) { "BrowserTutorialAdapterDiagnostics.kt:173" }
                        tick()
                    }
                    "DAMAGE_SYSTEM_6" -> {
                        refused()
                        move(state.player.piloting!!.room!!); tick()
                    }
                    "DAMAGE_SYSTEM_7" -> {
                        refused()
                        state.player.ftlChargeProgress = 1f
                        state.player.update(0f)
                        check(!Buttons.JumpButton(xyz.znix.xftl.math.ConstPoint.ZERO, state.player, state) {}.disabled) { "BrowserTutorialAdapterDiagnostics.kt:184" }
                        hotkey(VanillaHotkeys.FTL_JUMP); tick()
                    }
                    "STAR_MAP_1" -> { refused(); state.cancelMenu(); tick() }
                    "COMBAT_0" -> {
                        check(!state.isPlayerPaused) { "BrowserTutorialAdapterDiagnostics.kt:189" }
                        state.shipUI.showOptionsWindow()
                        check(state.isPaused && !adapter.observation(state).playerPaused) { "BrowserTutorialAdapterDiagnostics.kt:191" }
                        refused()
                        state.cancelMenu()
                        state.openDebugConsole(); refused(); state.cancelMenu()
                        state.autoPause(); refused(); state.cancelMenu()
                        BrowserEngine.key(Input.KEY_SPACE, ' '.code, true); BrowserEngine.step(0f)
                        BrowserEngine.key(Input.KEY_SPACE, ' '.code, false); BrowserEngine.step(0f)
                    }
                    "COMBAT_1" -> {
                        state.player.hardpoints.mapNotNull { it.weapon }.forEach { state.player.weapons!!.setWeaponPower(it, false) }
                        refused()
                        state.debugSpawnShip(state.eventManager.getShip("TUTORIAL_PIRATE"), Difficulty.EASY, 0, 71)
                        check(state.enemy != null) { "Tutorial pirate fixture did not spawn" }
                        check(state.player.weapons!!.setWeaponPower(state.player.hardpoints[0].weapon!!, true)) { "BrowserTutorialAdapterDiagnostics.kt:204" }
                        tick()
                    }
                    "COMBAT_2" -> {
                        refused()
                        val weapon = state.player.hardpoints[0].weapon!!
                        state.player.weapons!!.selectedTargets.targetRoom(0, state.enemy!!.rooms.first())
                        refused()
                        fire(weapon)
                        state.player.weapons!!.setWeaponPower(weapon, false)
                        val old = controller.index
                        check(main.doSaveLoadGame()) { "BrowserTutorialAdapterDiagnostics.kt:215" }
                        state = main.currentState as InGameState
                        check(main.tutorialAdapter === adapter && controller.index == old) { "BrowserTutorialAdapterDiagnostics.kt:217" }
                        check(adapter.observation(state).nonMissileFired) { "Reload/depower lost actual weapon firing history" }
                        tick()
                    }
                    "COMBAT_4" -> {
                        refused()
                        check(state.shipUI.tutorialDialogueOpen && adapter.events.missileDelivered) { "BrowserTutorialAdapterDiagnostics.kt:223" }
                        BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                        BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                        check(!state.shipUI.tutorialDialogueOpen) { "BrowserTutorialAdapterDiagnostics.kt:226" }
                        val missile = state.player.hardpoints.mapNotNull { it.weapon }
                            .single { it.type.name == "MISSILES_2_PLAYER" }
                        check(!state.isPlayerPaused) { "BrowserTutorialAdapterDiagnostics.kt:229" }
                        state.player.missilesCount = 5
                        fire(missile); tick()
                    }
                    "COMBAT_4_6" -> {
                        refused()
                        state.enemy!!.health = 0
                        check(adapter.observation(state).enemyPresent) { "Hull value replaced native target-presence gate" }
                        repeat(200) { if (state.enemy != null) state.updateGameState(.05f) }
                        check(state.enemy == null) { "Destroyed enemy did not leave the active target" }
                        tick()
                        if (state.shipUI.isWindowOpen) {
                            BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                            BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                        }
                    }
                    "INVENTORY_1" -> {
                        refused()
                        check(!Buttons.ShipButton(xyz.znix.xftl.math.ConstPoint.ZERO, state) {}.disabled) { "BrowserTutorialAdapterDiagnostics.kt:247" }
                        hotkey(VanillaHotkeys.SHIP_CREW); tick()
                        check(state.shipUI.tutorialShipWindow!!.tutorialTab == ShipWindow.Tab.EQUIPMENT) { "BrowserTutorialAdapterDiagnostics.kt:249" }
                    }
                    "INVENTORY_2" -> {
                        refused()
                        val window = state.shipUI.tutorialShipWindow!!
                        state.player.cargoBlueprints[0] = state.blueprintManager["BEAM_2"] as AbstractWeaponBlueprint
                        check(!adapter.observation(state).beamEquipped) { "Cargo beam incorrectly satisfied equipped gate" }
                        state.cancelMenu()
                        window.mouseClick(Input.MOUSE_LEFT_BUTTON, window.position.x + 480, window.position.y + 485)
                        check(state.shipUI.tutorialShipWindow === window) { "Accept/Escape bypassed missing beam" }
                        equip("BEAM_2", 1)
                        check(adapter.acceptShipWindow(state)) { "BrowserTutorialAdapterDiagnostics.kt:260" }
                        state.cancelMenu(); tick()
                    }
                    "COMBAT_5" -> {
                        refused()
                        hotkey(VanillaHotkeys.SHIP_CREW); refused()
                        state.cancelMenu()
                        hotkey(VanillaHotkeys.SHIP_UPGRADES); tick()
                    }
                    "UPGRADES_1_2" -> {
                        refused()
                        val window = state.shipUI.tutorialShipWindow!!
                        state.cancelMenu()
                        check(state.shipUI.tutorialShipWindow === window) { "BrowserTutorialAdapterDiagnostics.kt:273" }
                        state.player.scrap = 1000
                        upgradeClick(Input.MOUSE_LEFT_BUTTON)
                        check(window.pendingWeaponUpgrades == 1 && adapter.acceptShipWindow(state)) { "BrowserTutorialAdapterDiagnostics.kt:276" }
                        upgradeClick(Input.MOUSE_RIGHT_BUTTON)
                        check(window.pendingWeaponUpgrades == 0 && !adapter.acceptShipWindow(state)) { "Refunded upgrade left acceptance enabled" }
                        upgradeClick(Input.MOUSE_LEFT_BUTTON)
                        state.cancelMenu(); tick()
                    }
                    "UPGRADES_2" -> {
                        refused()
                        state.player.hardpoints[0].weapon?.let { state.player.weapons!!.setWeaponPower(it, false) }
                        check(state.player.weapons!!.setWeaponPower(state.player.hardpoints[1].weapon!!, true)) { "BrowserTutorialAdapterDiagnostics.kt:285" }
                        tick()
                    }
                    else -> error("Missing live tutorial gate fixture ${step.id}")
                }
                if (controller.canContinue && controller.index == index) check(adapter.continueStep(state) != null) { "BrowserTutorialAdapterDiagnostics.kt:290" }
                check(controller.index == index + 1) { "Live action did not advance ${step.id}: ${controller.step.id}" }
            }
            check(controller.completed && adapter.continueStep(state) == null) { "BrowserTutorialAdapterDiagnostics.kt:293" }
            check(controller.mapOpeningAllowed && controller.shipOpeningAllowed) { "BrowserTutorialAdapterDiagnostics.kt:294" }
            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == storedProfile) { "BrowserTutorialAdapterDiagnostics.kt:295" }
            main.endTutorialSession()
            check(main.profile === profile && BrowserXml.write(profile.save()) == profileXml) { "BrowserTutorialAdapterDiagnostics.kt:297" }
            check(BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialAdapterDiagnostics.kt:298" }
        } finally {
            main.endTutorialSession()
            BrowserFiles.hydrate(run, storedProfile)
            BrowserFiles.enableAE = browserMode
            BrowserRandom.restore(rng)
        }
    }
}
