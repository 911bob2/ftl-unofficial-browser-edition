package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.*
import xyz.znix.xftl.sector.*
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.rendering.Graphics

/** Tests use real event dispatch, beacon arrivals, enemy outcomes and run serialization. */
object BrowserTravelDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        fun dismiss() {
            if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
            }
        }
        fun reset(ship: String): InGameState {
            profile.load(SaveProfile.createBlank().save())
            main.startDiagnosticGame(ship, Difficulty.NORMAL)
            repeat(90) {
                main.update(container, .05f)
                dismiss()
            }
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun earned(id: String) = profile.getAchievement(main.gameContent.achievements[id]) != null
        fun moveToSector(state: InGameState, column: Int) {
            state.currentBeacon = state.gameMap.generateSector(state.gameMap.sectors[column][0], state).startBeacon
            state.currentBeacon.debugSetEnvironment(Beacon.EnvironmentType.NORMAL.create(state, state.currentBeacon))
        }
        fun reload(state: InGameState): InGameState {
            main.loadSavedGame(state.saveGameState())
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun event(elem: Element, choices: List<Choice> = emptyList(), text: IEventText? = null) =
            Event(text, choices, elem, "diagnostic-travel", "diagnostic-travel", { ImageList.NONE }, { EventText(it.textTrim) })
        fun choose(state: InGameState, req: String, blue: Boolean = true, minimum: Int = 1, fuelCost: Int = 0) {
            val outcome = Element("event")
            if (fuelCost > 0) outcome.addContent(Element("item_modify").addContent(Element("item")
                .setAttribute("type", "fuel").setAttribute("min", (-fuelCost).toString()).setAttribute("max", (-fuelCost).toString())))
            val child = event(outcome)
            val choice = Choice(EventText("Diagnostic option"), lazy { child },
                Element("choice").setAttribute("req", req).setAttribute("blue", blue.toString()).setAttribute("lvl", minimum.toString()), "diagnostic-choice")
            DialogueWindow(state, state.player, event(Element("event"), listOf(choice), EventText("Diagnostic event")), 91) {}.selectOption(0)
        }
        fun spawn(state: InGameState, rock: Boolean = false): Ship {
            var seed = 71
            do {
                state.debugSpawnShip(state.eventManager.getShip(if (rock) "ROCK_SHIP" else "PIRATE"), Difficulty.NORMAL, 4, seed++)
                check(seed < 150) { "BrowserTravelDiagnostics.kt:55" }
            } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated)
            return state.enemy!!
        }
        fun crewKill(state: InGameState) {
            val enemy = state.enemy!!
            enemy.clonebay?.let { it.damagedEnergyLevels = it.energyLevels }
            enemy.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().filter { it.ownerShip === enemy }.toList().forEach { it.removeFromShip() }
            state.checkCrewKillDefeat()
        }
        fun hullKill(state: InGameState) {
            state.enemy!!.health = 0
            repeat(180) { if (state.enemy != null) state.updateGameState(.05f) }
            check(state.enemy == null) { "Hull fixture did not complete destruction" }
        }

        var state = reset("PLAYER_SHIP_JELLY")
        val beacons = state.currentBeacon.sector.beacons.take(2)
        repeat(29) { index ->
            val destination = beacons.first { it !== state.currentBeacon }
            destination.debugSetEnvironment(Beacon.EnvironmentType.NEBULA.create(state, destination))
            state.currentBeacon = destination
            check(state.browserAchievements.nebulaJumps == index + 1) { "BrowserTravelDiagnostics.kt:77" }
        }
        check(!earned("ACH_SLUG_NEBULA")) { "BrowserTravelDiagnostics.kt:79" }
        state = reload(state)
        check(state.browserAchievements.nebulaJumps == 29) { "Loading counted a jump" }
        val destination = state.currentBeacon.sector.beacons.first { it !== state.currentBeacon }
        destination.debugSetEnvironment(Beacon.EnvironmentType.ION_STORM.create(state, destination))
        state.currentBeacon = destination
        check(state.browserAchievements.nebulaJumps == 30 && earned("ACH_SLUG_NEBULA")) { "BrowserTravelDiagnostics.kt:85" }
        state.currentBeacon = state.currentBeacon
        check(state.browserAchievements.nebulaJumps == 30) { "Same beacon counted twice" }

        state = reset("PLAYER_SHIP_STEALTH")
        state.browserAchievements.visit(7)
        check(earned("ACH_STEALTH_TACTICAL")) { "BrowserTravelDiagnostics.kt:91" }
        for (type in listOf(Beacon.EnvironmentType.ASTEROID, Beacon.EnvironmentType.SUN, Beacon.EnvironmentType.PULSAR, Beacon.EnvironmentType.ION_STORM)) {
            state = reset("PLAYER_SHIP_STEALTH")
            state.currentBeacon.debugSetEnvironment(type.create(state, state.currentBeacon))
            state.browserAchievements.update()
            state = reload(state)
            state.browserAchievements.visit(7)
            check(!earned("ACH_STEALTH_TACTICAL")) { "$type did not disqualify tactical approach after reload" }
        }

        state = reset("PLAYER_SHIP_FED")
        choose(state, "engines"); choose(state, "engines", minimum = 0)
        choose(state, "human", false); choose(state, "human", minimum = 4)
        state.player.fuelCount = 0; choose(state, "human", fuelCost = 1)
        check(state.browserAchievements.crewBlueChoices == 0) { "Non-crew, ordinary or rejected option counted" }
        repeat(3) { choose(state, "human") }
        check(state.browserAchievements.crewBlueChoices == 3 && !earned("ACH_FED_DIPLOMACY")) { "BrowserTravelDiagnostics.kt:107" }
        state = reload(state); moveToSector(state, 4); choose(state, "human")
        check(state.browserAchievements.crewBlueChoices == 4 && earned("ACH_FED_DIPLOMACY")) { "Sector-five crew blue boundary" }
        state = reset("PLAYER_SHIP_FED"); repeat(3) { choose(state, "human") }
        moveToSector(state, 5); choose(state, "human")
        check(!earned("ACH_FED_DIPLOMACY")) { "Late diplomatic choice earned achievement" }
        state = reset("PLAYER_SHIP_FED")
        state.playerCrew.filter { it.codename == "engi" }.toList().forEach { it.removeFromShip() }
        state.player.addCrewMember(LivingCrewInfo.generateRandom(state.blueprintManager["engi"] as CrewBlueprint, state), false, true)
        choose(state, "engi")
        check(state.browserAchievements.crewBlueChoices == 0) { "Intruder satisfied crew option" }
        state.player.addCrewMember(LivingCrewInfo.generateRandom(state.blueprintManager["engi"] as CrewBlueprint, state), true)
        choose(state, "engi")
        check(state.browserAchievements.crewBlueChoices == 1) { "BrowserTravelDiagnostics.kt:120" }

        state = reset("PLAYER_SHIP_MANTIS")
        state.browserAchievements.load(state.browserAchievements.save().setAttribute("crewDefeats", "19"))
        moveToSector(state, 5); spawn(state); crewKill(state)
        check(state.browserAchievements.crewDefeats == 20 && earned("ACH_MANTIS_CREW_DEAD")) { "BrowserTravelDiagnostics.kt:125" }
        state = reload(state); state.checkCrewKillDefeat()
        check(state.browserAchievements.crewDefeats == 20) { "Crew victory repeated on load" }
        state = reset("PLAYER_SHIP_MANTIS")
        state.browserAchievements.load(state.browserAchievements.save().setAttribute("crewDefeats", "19"))
        moveToSector(state, 6); spawn(state); crewKill(state)
        check(!earned("ACH_MANTIS_CREW_DEAD")) { "Late crew victory earned achievement" }
        state = reset("PLAYER_SHIP_MANTIS"); val boarded = spawn(state)
        state.debugFlags.noDmg.set = true; state.debugFlags.noCrewDamage.set = true
        state.playerCrew.drop(1).toList().forEach { it.removeFromShip() }
        state.playerCrew.single().teleportAnimatedTo(boarded.rooms.first())
        repeat(70) { state.updateGameState(.05f) }
        check(state.playerCrew.single().room.ship === boarded) { "BrowserTravelDiagnostics.kt:137" }
        crewKill(state)
        check(earned("ACH_MANTIS_SURVIVOR")) { "BrowserTravelDiagnostics.kt:139" }

        state = reset("PLAYER_SHIP_CRYSTAL")
        state.browserAchievements.load(state.browserAchievements.save().setAttribute("rockHullDefeats", "9"))
        spawn(state, true); crewKill(state)
        check(state.browserAchievements.rockHullDefeats == 9 && !earned("ACH_CRYSTAL_CLASH")) { "BrowserTravelDiagnostics.kt:144" }
        spawn(state); hullKill(state)
        check(state.browserAchievements.rockHullDefeats == 9 && !earned("ACH_CRYSTAL_CLASH")) { "BrowserTravelDiagnostics.kt:146" }
        state = reload(state); spawn(state, true); hullKill(state)
        check(state.browserAchievements.rockHullDefeats == 10 && earned("ACH_CRYSTAL_CLASH")) { "BrowserTravelDiagnostics.kt:148" }
        state = reload(state)
        check(state.browserAchievements.rockHullDefeats == 10) { "Hull victory repeated on load" }

        secretSector(main, container, graphics)
    }

    private fun secretSector(main: MainGame, container: GameContainer, graphics: Graphics) {
        main.profile.load(SaveProfile.createBlank().save())
        main.startDiagnosticGame("PLAYER_SHIP_ROCK", Difficulty.NORMAL)
        var state = main.currentState as InGameState
        fun dismiss() {
            BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
            BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
        }
        repeat(90) { main.update(container, .05f); if (state.shipUI.isWindowOpen) dismiss() }
        val origin = state.gameMap.sectors[5][0]
        state.currentBeacon = state.gameMap.generateSector(origin, state).startBeacon
        val fuel = state.player.fuelCount
        val warpEvent = state.eventManager.loadEmbeddedEvent(Element("event")
            .addContent(Element("text").setText("Diagnostic sector transition"))
            .addContent(Element("secretSector")), "travel-diagnostics").value.resolve()
        state.shipUI.showEventDialogue(warpEvent, 91)
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        check(!state.currentBeacon.sector.info.isSecretSector) { "Saving an open event applied the warp early" }
        repeat(5) { if (state.shipUI.isWindowOpen) dismiss() }
        check(state.currentBeacon.sector.type.name == "CRYSTAL_HOME") { "secretSector event did not warp" }
        // The warp is a jump: ShipManager::JumpLeave takes one fuel, as in the original (16 -> 15).
        check(state.currentBeacon.sector.sectorNumber == 5 && state.player.fuelCount == fuel - 1) { "BrowserTravelDiagnostics.kt:175" }
        check(main.profile.getAchievement(state.content.achievements["ACH_ROCK_CRYSTAL"]) != null) { "BrowserTravelDiagnostics.kt:176" }
        SectorMapWindow(state) {}.draw(graphics)
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        val secret = state.currentBeacon.sector.info
        check(secret.secretOrigin === state.gameMap.sectors[5][0]) { "Secret origin identity did not survive save" }
        check(secret.nextSectors == secret.secretOrigin!!.nextSectors && secret.nextSectors.isNotEmpty()) { "BrowserTravelDiagnostics.kt:181" }
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        repeat(90) { main.update(container, .05f); if (state.shipUI.isWindowOpen) dismiss() }
        val quest = state.currentBeacon.sector.beacons.firstOrNull { it.hasQuest && it.event.debugId == "CRYSTAL_UNLOCK" }
        check(quest != null) { "Saving during arrival lost the Crystal unlock quest" }
        check(!main.profile.isShipUnlocked(ShipProgression.CRYSTAL)) { "BrowserTravelDiagnostics.kt:186" }
        state.currentBeacon = quest
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        repeat(90) { main.update(container, .05f); if (state.shipUI.isWindowOpen) dismiss() }
        check(main.profile.isShipUnlocked(ShipProgression.CRYSTAL)) { "Crystal quest did not grant the ship" }
        val next = secret.nextSectors.first()
        val restoredNext = state.currentBeacon.sector.info.nextSectors.first { it.columnIndex == next.columnIndex }
        state.currentBeacon = state.gameMap.generateSector(restoredNext, state).startBeacon
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        check(state.currentBeacon.sector.sectorNumber == 6 && !state.currentBeacon.sector.info.isSecretSector) { "BrowserTravelDiagnostics.kt:196" }
        check(state.visitedSectors.any { it.isSecretSector }) { "Past secret sector reference was lost" }
        // The operation must also be parsed and dispatched from the supplied DAT.
        state.shipUI.showEventDialogue(state.eventManager["CRYSTAL_TEST"].resolve(), 91)
        repeat(5) { if (state.shipUI.isWindowOpen) dismiss() }
        check(state.currentBeacon.sector.info.isSecretSector) { "Official secret-sector operation was ignored" }
    }
}
