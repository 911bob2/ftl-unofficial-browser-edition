package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/** Focused diagnostic coverage of native tutorial initial beacon, jump encounter,
 * COMBAT_4 missile event injection, restocking and pirate reward routes.
 * Controller observations, FTL charge and deaths use explicit fixture setup;
 * this does not establish a complete played tutorial. */
object BrowserTutorialEncounterDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        val profileXml = BrowserXml.write(profile.save())
        val storedProfile = BrowserFiles.getProfile()
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val mode = main.gameContent.enableAdvancedEdition
        val browserMode = BrowserFiles.enableAE

        fun totalWeapons(ship: Ship): List<String> =
            ship.hardpoints.mapNotNull { it.weapon?.type?.name } + ship.cargoBlueprints.filterNotNull().map { it.name }

        try {
            main.switchToMainMenu()
            var state = main.startTutorialSession()
            val adapter = main.tutorialAdapter!!
            val controller = adapter.controller
            val events = adapter.events

            fun dismissDialogue() {
                repeat(10) {
                    if (state.shipUI.tutorialDialogueOpen) {
                        BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                        BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                    }
                }
                check(!state.shipUI.tutorialDialogueOpen) { "Dialogue remained open after dismiss" }
            }

            fun unchanged(action: () -> Unit) {
                val before = BrowserXml.write(state.saveGameState())
                val beforeRng = BrowserRandom.snapshot()
                action()
                check(BrowserXml.write(state.saveGameState()) == before && BrowserRandom.snapshot() == beforeRng) {
                    "Rejected or repeated tutorial encounter action mutated state or consumed RNG"
                }
            }

            fun traverseControllerTo(targetStep: String, active: BrowserTutorialAdapter = adapter) {
                val controller = active.controller
                val enabled = setOf("POWER_SYSTEM_2", "DAMAGE_SYSTEM_4", "FIRE_END", "DAMAGE_SYSTEM_6")
                while (controller.step.id != targetStep && !controller.completed) {
                    if (controller.step.continueButton) {
                        check(active.continueStep(state) != null) { "Failed to continue step ${controller.step.id}" }
                    } else {
                        val good = when (controller.step.id) {
                            "POWER_SYSTEM_2" -> BrowserTutorialController.Observation(enginePower = 1)
                            "DAMAGE_SYSTEM_2" -> BrowserTutorialController.Observation(selectedCrew = 1)
                            "DAMAGE_SYSTEM_3" -> BrowserTutorialController.Observation(engineCrew = 1)
                            "DAMAGE_SYSTEM_4" -> BrowserTutorialController.Observation(engineCapacity = 2, engineHealth = 2)
                            "FIRE_PC_1" -> BrowserTutorialController.Observation(airlockOpen = true)
                            "FIRE_END" -> BrowserTutorialController.Observation(roomFires = 0)
                            "DAMAGE_SYSTEM_6" -> BrowserTutorialController.Observation(pilotCrew = 1)
                            "DAMAGE_SYSTEM_7" -> BrowserTutorialController.Observation(mapOpen = true)
                            "STAR_MAP_1" -> BrowserTutorialController.Observation()
                            "COMBAT_0" -> BrowserTutorialController.Observation(playerPaused = true)
                            "COMBAT_1" -> BrowserTutorialController.Observation(nonMissilePowered = true)
                            "COMBAT_2" -> BrowserTutorialController.Observation(nonMissileFired = true)
                            "COMBAT_4" -> BrowserTutorialController.Observation(missileFired = true)
                            "COMBAT_4_6" -> BrowserTutorialController.Observation(enemyPresent = false)
                            "INVENTORY_1" -> BrowserTutorialController.Observation(shipInfoOpen = true)
                            "INVENTORY_2" -> BrowserTutorialController.Observation(beamEquipped = true)
                            "COMBAT_5" -> BrowserTutorialController.Observation(upgradesOpen = true)
                            "UPGRADES_1_2" -> BrowserTutorialController.Observation(pendingWeaponUpgrades = 1)
                            "UPGRADES_2" -> BrowserTutorialController.Observation(beamPowered = true)
                            else -> BrowserTutorialController.Observation()
                        }
                        if (controller.step.id in enabled) {
                            controller.observe(good)
                            check(active.continueStep(state) != null) { "Failed to continue enabled step ${controller.step.id}" }
                        } else {
                            val transition = controller.observe(good)
                            check(transition != null) { "Observation did not advance step ${controller.step.id}" }
                            active.update(state)
                        }
                    }
                }
            }

            BrowserEngine.diagnosticProgress("tutorial starting beacon TUTORIAL_START and filler TUTORIAL_ENEMY")
            val startBeacon = state.currentBeacon
            check(startBeacon.event.debugId == "TUTORIAL_START") { "Initial beacon does not have TUTORIAL_START event: ${startBeacon.event.debugId}" }
            check(state.shipUI.tutorialDialogueOpen && state.shipUI.isWindowOpen) { "Initial tutorial dialogue not open" }
            val adjacent = startBeacon.neighbours
            check(adjacent.isNotEmpty() && adjacent.all { it.event.debugId == "TUTORIAL_ENEMY" }) { "Adjacent beacons do not all use TUTORIAL_ENEMY filler" }
            check(startBeacon.sector.beacons.filter { it !== startBeacon && !it.isExit }.all { it.event.debugId == "TUTORIAL_ENEMY" }) {
                "Non-exit tutorial sector beacons must use TUTORIAL_ENEMY filler"
            }

            dismissDialogue()
            // Number-key event choices do not advance elapsed time. Let the
            // ordinary starting arrival finish before opening the map.
            repeat(100) { main.update(container, .05f) }

            BrowserEngine.diagnosticProgress("tutorial map jump to pirate, hostile target and blueprint verification")
            traverseControllerTo("DAMAGE_SYSTEM_7")
            check(controller.step.id == "DAMAGE_SYSTEM_7" && controller.mapOpeningAllowed) { "BrowserTutorialEncounterDiagnostics.kt:114" }

            // Controlled encounter setup: early action traversal is covered separately.
            while (state.player.engines!!.damaged) state.player.engines!!.repair(1f)
            state.player.update(0f)
            state.player.engines!!.increasePower()
            state.playerCrew.first().jumpTo(state.player.findSpaceForCrew(state.player.piloting!!.room!!, AbstractCrew.SlotType.CREW))
            state.player.piloting!!.room!!.updateCrewInRoom()
            state.player.ftlChargeProgress = 1f
            state.player.update(0f)
            check(state.player.isFtlReady && state.player.canChargeFTL) {
                "Fixture FTL unavailable: engine ${state.player.engines!!.powerSelected}/${state.player.engines!!.powerSupplied}/${state.player.engines!!.undamagedEnergy}, pilot ${state.player.piloting!!.effectivePower}, reactor ${state.player.reactorPower}, free ${state.player.powerAvailable}"
            }
            state.shipUI.openJumpMap()
            check(state.shipUI.tutorialMapOpen) { "BrowserTutorialEncounterDiagnostics.kt:128" }
            val jumpWindow = state.shipUI.tutorialJumpWindow!!
            main.render(container, graphics)

            val targetBeacon = adjacent.first()
            val beforeFuel = state.player.fuelCount
            val dangerCenter = targetBeacon.sector.dangerZoneCentre.x
            val modifier = targetBeacon.sector.fleetAdvanceModifier

            val clickX = jumpWindow.position.x + Sector.OFFSET.x + targetBeacon.pos.x
            val clickY = jumpWindow.position.y + Sector.OFFSET.y + targetBeacon.pos.y
            jumpWindow.updateUI(clickX, clickY)
            jumpWindow.mouseClick(Input.MOUSE_LEFT_BUTTON, clickX, clickY)

            check(state.player.fuelCount == beforeFuel - 1) { "Fuel was not decremented on jump" }
            check(targetBeacon.sector.dangerZoneCentre.x == dangerCenter && targetBeacon.sector.fleetAdvanceModifier == modifier) {
                "Fleet pursuit advanced during tutorial jump"
            }

            // The arrival event opens about 4 s after the jump (2 s jump-out, 2 s arrival), as in the original.
            repeat(100) {
                if (state.currentBeacon !== targetBeacon || state.enemy == null || !state.shipUI.tutorialDialogueOpen) {
                    main.update(container, 0.05f)
                }
            }

            check(state.currentBeacon === targetBeacon) { "Ship did not arrive at target beacon" }
            val pirate = state.enemy ?: error("TUTORIAL_PIRATE enemy was not spawned upon beacon arrival")
            check(state.shipUI.tutorialDialogueOpen) { "TUTORIAL_ENEMY arrival dialogue was not displayed" }
            check(pirate.type.name == "TUTORIAL_PIRATE" && pirate.spec?.name == "TUTORIAL_PIRATE") {
                "Spawned enemy is not TUTORIAL_PIRATE: ${pirate.type.name}"
            }
            check(state.isInDanger && state.currentBeacon.ship === pirate) { "TUTORIAL_PIRATE enemy is not an active hostile encounter" }
            check(pirate.health == 5) { "TUTORIAL_PIRATE starting hull is not 5: ${pirate.health}" }
            check(pirate.shields != null && pirate.shields!!.energyLevels == 4 && pirate.shields!!.room?.id == 1) {
                "TUTORIAL_PIRATE shield blueprint mismatch"
            }
            check(pirate.weapons != null && pirate.weapons!!.energyLevels == 3 && pirate.weapons!!.room?.id == 2) {
                "TUTORIAL_PIRATE weapon blueprint mismatch"
            }
            check(pirate.hardpoints.mapNotNull { it.weapon }.single().type.name == "LASER_BURST_1") {
                "TUTORIAL_PIRATE weapon blueprint is not LASER_BURST_1"
            }
            check(pirate.piloting?.room?.id == 0 && pirate.oxygen?.room?.id == 3 && pirate.engines?.room?.id == 4) {
                "TUTORIAL_PIRATE system room layout mismatch"
            }

            dismissDialogue()

            BrowserEngine.diagnosticProgress("tutorial COMBAT_4 missile delivery, queue restore and restocking")
            traverseControllerTo("COMBAT_4")
            check(controller.step.id == "COMBAT_4") { "BrowserTutorialEncounterDiagnostics.kt:178" }
            check(events.pendingEventName == "TUTORIAL_MISSILE") { "TUTORIAL_MISSILE was not queued upon entering COMBAT_4" }
            check(!events.missileDelivered) { "Missile was delivered prematurely before update" }
            check(!state.isPlayerPaused) { "Pause state was not cleared on entering COMBAT_4" }

            check(main.doSaveLoadGame()) { "Save/load failed during pending TUTORIAL_MISSILE" }
            state = main.currentState as InGameState
            check(main.tutorialAdapter === adapter && adapter.events === events) { "BrowserTutorialEncounterDiagnostics.kt:185" }
            check(events.pendingEventName == "TUTORIAL_MISSILE" && !events.missileDelivered) {
                "Queued event state did not survive save/load restoration"
            }

            // Options window must defer event delivery without replacement
            state.shipUI.showOptionsWindow()
            check(state.shipUI.isWindowOpen) { "BrowserTutorialEncounterDiagnostics.kt:192" }
            events.update(state)
            check(events.pendingEventName == "TUTORIAL_MISSILE" && !events.missileDelivered && !state.shipUI.tutorialDialogueOpen) {
                "Event delivered underneath Options window"
            }
            state.cancelMenu()
            check(!state.shipUI.isWindowOpen) { "BrowserTutorialEncounterDiagnostics.kt:198" }

            // Debug console must defer event delivery without replacement
            state.openDebugConsole()
            check(state.isDebugConsoleVisible) { "BrowserTutorialEncounterDiagnostics.kt:202" }
            events.update(state)
            check(events.pendingEventName == "TUTORIAL_MISSILE" && !events.missileDelivered && !state.shipUI.tutorialDialogueOpen) {
                "Event delivered underneath Debug Console"
            }
            state.cancelMenu()
            check(!state.isDebugConsoleVisible) { "BrowserTutorialEncounterDiagnostics.kt:208" }

            // Safe update delivers once and opens dialogue
            events.update(state)
            check(events.pendingEventName == null && events.missileDelivered) { "Event was not consumed on safe update" }
            check(state.shipUI.tutorialDialogueOpen) { "TUTORIAL_MISSILE dialogue did not open" }
            check(totalWeapons(state.player).none { it == "MISSILES_2_PLAYER" }) {
                "MISSILES_2_PLAYER granted before event Continue"
            }

            // Save and reload while dialogue is open
            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:219" }
            state = main.currentState as InGameState
            check(state.shipUI.tutorialDialogueOpen && events.missileDelivered) { "BrowserTutorialEncounterDiagnostics.kt:221" }
            check(totalWeapons(state.player).none { it == "MISSILES_2_PLAYER" }) { "BrowserTutorialEncounterDiagnostics.kt:222" }

            dismissDialogue()
            check(totalWeapons(state.player).count { it == "MISSILES_2_PLAYER" } == 1) {
                "Exactly one MISSILES_2_PLAYER should be granted after event Continue"
            }

            // Repeated updates and reloads must not duplicate weapon
            repeat(5) {
                events.update(state)
                main.update(container, 0.05f)
            }
            check(totalWeapons(state.player).count { it == "MISSILES_2_PLAYER" } == 1) { "BrowserTutorialEncounterDiagnostics.kt:234" }
            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:235" }
            state = main.currentState as InGameState
            repeat(5) {
                events.update(state)
                main.update(container, 0.05f)
            }
            check(totalWeapons(state.player).count { it == "MISSILES_2_PLAYER" } == 1) {
                "Repeated updates or restore duplicated MISSILES_2_PLAYER"
            }

            // Missile restocking: empty (< 1) adds ten, positive count unchanged
            state.player.missilesCount = 0
            events.update(state)
            check(state.player.missilesCount == 10) { "Empty missiles did not restock to 10: ${state.player.missilesCount}" }
            state.player.missilesCount = 5
            events.update(state)
            check(state.player.missilesCount == 5) { "Positive missiles changed during update: ${state.player.missilesCount}" }

            // Stale / ordinary state guards reject before mutation or RNG
            val staleState = state
            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:255" }
            state = main.currentState as InGameState
            unchanged {
                var rejected = false
                try { events.update(staleState) } catch (expected: IllegalStateException) { rejected = true }
                check(rejected) { "Stale state update was not rejected" }
            }
            unchanged {
                var rejected = false
                try { events.enter(staleState) } catch (expected: IllegalStateException) { rejected = true }
                check(rejected) { "Stale state enter was not rejected" }
            }
            unchanged {
                var rejected = false
                try { staleState.unpauseTutorial() } catch (expected: IllegalStateException) { rejected = true }
                check(rejected) { "Stale state unpauseTutorial was not rejected" }
            }
            unchanged {
                var rejected = false
                try { staleState.deliverTutorialEvent("TUTORIAL_MISSILE") } catch (expected: IllegalStateException) { rejected = true }
                check(rejected) { "Stale state deliverTutorialEvent was not rejected" }
            }

            BrowserEngine.diagnosticProgress("tutorial pirate hull destruction rewards and save idempotence")
            val fuelBeforeHull = state.player.fuelCount
            val missilesBeforeHull = state.player.missilesCount
            val scrapBeforeHull = state.player.scrap
            val beamsBeforeHull = totalWeapons(state.player).count { it == "BEAM_2" }

            check(state.enemy != null) { "Enemy pirate missing before hull destruction" }
            state.enemy!!.health = 0
            repeat(200) { if (state.enemy != null) state.updateGameState(0.05f) }
            check(state.enemy == null) { "Destroyed enemy pirate did not clear active target" }
            check(state.shipUI.tutorialDialogueOpen) { "Enemy destroyed dialogue was not shown" }

            check(state.player.fuelCount == fuelBeforeHull && state.player.missilesCount == missilesBeforeHull &&
                state.player.scrap == scrapBeforeHull && totalWeapons(state.player).count { it == "BEAM_2" } == beamsBeforeHull) {
                "Destruction rewards granted before dialogue Continue"
            }

            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:295" }
            state = main.currentState as InGameState
            check(state.enemy == null && state.shipUI.tutorialDialogueOpen) { "BrowserTutorialEncounterDiagnostics.kt:297" }
            check(state.player.fuelCount == fuelBeforeHull && state.player.missilesCount == missilesBeforeHull &&
                state.player.scrap == scrapBeforeHull && totalWeapons(state.player).count { it == "BEAM_2" } == beamsBeforeHull) { "BrowserTutorialEncounterDiagnostics.kt:298" }

            dismissDialogue()

            check(state.player.fuelCount == fuelBeforeHull + 1) { "Fuel +1 reward missing after hull destruction" }
            check(state.player.missilesCount == missilesBeforeHull + 3) { "Missiles +3 reward missing after hull destruction" }
            check(state.player.scrap == scrapBeforeHull + 100) { "Scrap +100 reward missing after hull destruction" }
            check(totalWeapons(state.player).count { it == "BEAM_2" } == beamsBeforeHull + 1) {
                "BEAM_2 weapon reward missing after hull destruction"
            }
            check(state.enemy == null) { "Active enemy must remain null after reward resolution" }

            val fuelAfterHull = state.player.fuelCount
            val missilesAfterHull = state.player.missilesCount
            val scrapAfterHull = state.player.scrap
            val beamsAfterHull = totalWeapons(state.player).count { it == "BEAM_2" }
            repeat(5) {
                events.update(state)
                main.update(container, 0.05f)
            }
            check(state.player.fuelCount == fuelAfterHull && state.player.missilesCount == missilesAfterHull &&
                state.player.scrap == scrapAfterHull && totalWeapons(state.player).count { it == "BEAM_2" } == beamsAfterHull) {
                "Repeated update changed rewards after hull destruction"
            }
            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:323" }
            state = main.currentState as InGameState
            repeat(5) {
                events.update(state)
                main.update(container, 0.05f)
            }
            check(state.player.fuelCount == fuelAfterHull && state.player.missilesCount == missilesAfterHull &&
                state.player.scrap == scrapAfterHull && totalWeapons(state.player).count { it == "BEAM_2" } == beamsAfterHull) {
                "Save restoration duplicated rewards after hull destruction"
            }

            main.endTutorialSession()
            check(!main.isTutorialRunning && !BrowserFiles.isTemporaryWrites()) { "BrowserTutorialEncounterDiagnostics.kt:335" }
            check(main.profile === profile && BrowserXml.write(profile.save()) == profileXml) { "BrowserTutorialEncounterDiagnostics.kt:336" }
            check(BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialEncounterDiagnostics.kt:337" }
            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == storedProfile) { "BrowserTutorialEncounterDiagnostics.kt:338" }

            BrowserEngine.diagnosticProgress("tutorial pirate crew death rewards and save idempotence")
            state = main.startTutorialSession()
            val adapter2 = main.tutorialAdapter!!
            val controller2 = adapter2.controller
            val events2 = adapter2.events

            dismissDialogue()
            repeat(100) { main.update(container, .05f) }

            traverseControllerTo("DAMAGE_SYSTEM_7", adapter2)
            check(controller2.step.id == "DAMAGE_SYSTEM_7") { "BrowserTutorialEncounterDiagnostics.kt:350" }

            // Controlled encounter setup: early action traversal is covered separately.
            while (state.player.engines!!.damaged) state.player.engines!!.repair(1f)
            state.player.update(0f)
            state.player.engines!!.increasePower()
            state.playerCrew.first().jumpTo(state.player.findSpaceForCrew(state.player.piloting!!.room!!, AbstractCrew.SlotType.CREW))
            state.player.piloting!!.room!!.updateCrewInRoom()
            state.player.ftlChargeProgress = 1f
            state.player.update(0f)
            state.shipUI.openJumpMap()
            check(state.shipUI.tutorialMapOpen) { "BrowserTutorialEncounterDiagnostics.kt:361" }
            val jumpWindow2 = state.shipUI.tutorialJumpWindow!!
            main.render(container, graphics)

            val targetBeacon2 = state.currentBeacon.neighbours.first()
            val clickX2 = jumpWindow2.position.x + Sector.OFFSET.x + targetBeacon2.pos.x
            val clickY2 = jumpWindow2.position.y + Sector.OFFSET.y + targetBeacon2.pos.y
            jumpWindow2.updateUI(clickX2, clickY2)
            jumpWindow2.mouseClick(Input.MOUSE_LEFT_BUTTON, clickX2, clickY2)

            // The arrival event opens about 4 s after the jump (2 s jump-out, 2 s arrival), as in the original.
            repeat(100) {
                if (state.currentBeacon !== targetBeacon2 || state.enemy == null || !state.shipUI.tutorialDialogueOpen) {
                    main.update(container, 0.05f)
                }
            }

            check(state.currentBeacon === targetBeacon2 && state.enemy != null && state.shipUI.tutorialDialogueOpen) { "BrowserTutorialEncounterDiagnostics.kt:377" }
            dismissDialogue()
            traverseControllerTo("COMBAT_4", adapter2)
            events2.update(state)
            dismissDialogue()
            traverseControllerTo("COMBAT_4_6", adapter2)
            check(adapter2.observation(state).enemyPresent) { "BrowserTutorialEncounterDiagnostics.kt:383" }

            val fuelBeforeCrew = state.player.fuelCount
            val missilesBeforeCrew = state.player.missilesCount
            val scrapBeforeCrew = state.player.scrap
            val beamsBeforeCrew = totalWeapons(state.player).count { it == "BEAM_2" }

            val enemyShip = state.enemy!!
            enemyShip.crew.filterIsInstance<LivingCrew>().forEach {
                it.health = 0f
                it.removeFromShip()
            }
            enemyShip.crew.clear()
            state.checkCrewKillDefeat()
            check(state.shipUI.tutorialDialogueOpen) { "Enemy crew death dialogue was not shown" }
            check(!adapter2.observation(state).enemyPresent) {
                "The defeated ship's visible hull incorrectly blocked the tutorial combat gate"
            }

            check(state.player.fuelCount == fuelBeforeCrew && state.player.missilesCount == missilesBeforeCrew &&
                state.player.scrap == scrapBeforeCrew && totalWeapons(state.player).count { it == "BEAM_2" } == beamsBeforeCrew) {
                "Crew death rewards granted before dialogue Continue"
            }

            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:407" }
            state = main.currentState as InGameState
            check(state.enemy == null) { "Defeated crew enemy did not clear active target after restore" }
            check(state.shipUI.tutorialDialogueOpen) { "BrowserTutorialEncounterDiagnostics.kt:410" }
            check(state.player.fuelCount == fuelBeforeCrew && state.player.missilesCount == missilesBeforeCrew &&
                state.player.scrap == scrapBeforeCrew && totalWeapons(state.player).count { it == "BEAM_2" } == beamsBeforeCrew) { "BrowserTutorialEncounterDiagnostics.kt:411" }

            dismissDialogue()
            check(controller2.step.id == "INVENTORY_1") { "Crew defeat did not advance the combat lesson" }

            check(state.player.fuelCount == fuelBeforeCrew + 1) { "Fuel +1 reward missing after crew death" }
            check(state.player.missilesCount == missilesBeforeCrew + 3) { "Missiles +3 reward missing after crew death" }
            check(state.player.scrap == scrapBeforeCrew + 100) { "Scrap +100 reward missing after crew death" }
            check(totalWeapons(state.player).count { it == "BEAM_2" } == beamsBeforeCrew + 1) {
                "BEAM_2 weapon reward missing after crew death"
            }
            check(state.enemy == null) { "Active enemy must remain null after crew reward resolution" }

            val fuelAfterCrew = state.player.fuelCount
            val missilesAfterCrew = state.player.missilesCount
            val scrapAfterCrew = state.player.scrap
            val beamsAfterCrew = totalWeapons(state.player).count { it == "BEAM_2" }
            repeat(5) {
                events2.update(state)
                main.update(container, 0.05f)
            }
            check(state.player.fuelCount == fuelAfterCrew && state.player.missilesCount == missilesAfterCrew &&
                state.player.scrap == scrapAfterCrew && totalWeapons(state.player).count { it == "BEAM_2" } == beamsAfterCrew) {
                "Repeated update changed rewards after crew death"
            }
            check(main.doSaveLoadGame()) { "BrowserTutorialEncounterDiagnostics.kt:437" }
            state = main.currentState as InGameState
            repeat(5) {
                adapter2.events.update(state)
                main.update(container, 0.05f)
            }
            check(state.player.fuelCount == fuelAfterCrew && state.player.missilesCount == missilesAfterCrew &&
                state.player.scrap == scrapAfterCrew && totalWeapons(state.player).count { it == "BEAM_2" } == beamsAfterCrew) {
                "Save restoration duplicated rewards after crew death"
            }

            main.endTutorialSession()

            // Ordinary simulation rejects tutorial events without RNG/state mutation
            val ordinaryRng = BrowserRandom.snapshot()
            var rejected = false
            try { adapter.events.update(state) } catch (expected: IllegalStateException) { rejected = true }
            check(rejected && BrowserRandom.snapshot() == ordinaryRng) { "Ordinary state update was not rejected" }

            rejected = false
            try { adapter.events.enter(state) } catch (expected: IllegalStateException) { rejected = true }
            check(rejected && BrowserRandom.snapshot() == ordinaryRng) { "Ordinary state enter was not rejected" }

            check(BrowserFiles.getRun() == run && BrowserFiles.getProfile() == storedProfile) { "BrowserTutorialEncounterDiagnostics.kt:460" }
            check(main.profile === profile && BrowserXml.write(profile.save()) == profileXml) { "BrowserTutorialEncounterDiagnostics.kt:461" }
            check(BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialEncounterDiagnostics.kt:462" }
            check(!BrowserFiles.isTemporaryWrites()) { "BrowserTutorialEncounterDiagnostics.kt:463" }
        } finally {
            main.endTutorialSession()
            BrowserFiles.hydrate(run, storedProfile)
            BrowserFiles.enableAE = browserMode
            BrowserRandom.restore(rng)
        }
    }
}
