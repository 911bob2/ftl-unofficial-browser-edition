package ftl.browser

import xyz.znix.xftl.crew.*
import xyz.znix.xftl.game.*
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.rendering.Graphics

object BrowserRunStatisticsDiagnostics {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics) {
        val profile = main.profile
        profile.load(SaveProfile.createBlank().save())
        fun start(ship: String = "PLAYER_SHIP_HARD"): InGameState {
            main.startDiagnosticGame(ship, Difficulty.NORMAL)
            repeat(90) {
                main.update(container, .05f)
                if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        BrowserEngine.diagnosticProgress("native run statistics start and recruitment")
        var state = start()
        check(profile.browserGamesPlayed == 1 && profile.browserLifetimeBeacons == 1L) { "BrowserRunStatisticsDiagnostics.kt:26" }
        check(state.browserProgress.crewRecruited == 3 && profile.browserLifetimeCrew == 3L) { "Starting crew not counted" }
        val race = state.blueprintManager["human"] as CrewBlueprint
        val recruit = state.player.addCrewMember(LivingCrewInfo.generateRandom(race, state), false)
        check(state.browserProgress.crewRecruited == 4 && profile.browserLifetimeCrew == 4L) { "BrowserRunStatisticsDiagnostics.kt:30" }
        state.player.addCrewMember(LivingCrewInfo.generateRandom(race, state), false, true)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 1, 537)
        check(state.browserProgress.crewRecruited == 4 && profile.browserLifetimeCrew == 4L) { "Enemy/intruder crew counted as recruitment" }
        recruit.onCloned()
        check(state.browserProgress.crewRecruited == 4) { "Cloning counted as recruitment" }
        val enemyRoom = state.enemy!!.rooms.first { it.cellCount >= 2 }
        recruit.teleportAnimatedTo(enemyRoom)
        repeat(80) { recruit.update(.05f) }
        check(recruit.room.ship === state.enemy) { "BrowserRunStatisticsDiagnostics.kt:39" }
        recruit.teleportAnimatedTo(state.player.rooms.first { it.cellCount >= 2 })
        repeat(80) { recruit.update(.05f) }
        check(recruit.room.ship === state.player && state.browserProgress.crewRecruited == 4 && profile.browserLifetimeCrew == 4L) { "Teleporting counted as recruitment" }
        val totals = Triple(profile.browserGamesPlayed, profile.browserLifetimeBeacons, profile.browserLifetimeCrew)
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        check(state.browserProgress.crewRecruited == 4 && state.browserProgress.crewHistoryComplete) { "BrowserRunStatisticsDiagnostics.kt:46" }
        check(Triple(profile.browserGamesPlayed, profile.browserLifetimeBeacons, profile.browserLifetimeCrew) == totals) { "Restoration repeated run/recruitment/visit totals" }
        val next = state.currentBeacon.neighbours.first()
        state.currentBeacon = next; state.currentBeacon = next
        check(profile.browserLifetimeBeacons == 2L) { "BrowserRunStatisticsDiagnostics.kt:50" }
        val old = state.saveGameState()
        old.rootElement.getChild("browserProgress").removeAttribute("crewComplete")
        main.loadSavedGame(old); state = main.currentState as InGameState
        check(!state.browserProgress.crewHistoryComplete && state.browserProgress.historyComplete) { "Missing old recruitment history invalidated known score or fabricated crew history" }

        BrowserEngine.diagnosticProgress("native run statistics abandoned runs and record maxima")
        state = start() // Replaces the unfinished run, while retaining its lifetime actions.
        check(profile.browserGamesPlayed == 2 && profile.browserVictories == 0 && profile.browserLifetimeCrew == 7L) { "BrowserRunStatisticsDiagnostics.kt:58" }
        state.givePlayerResources(ResourceSet().also { it.scrap = 200 })
        state.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
        val completed = state.browserProgress.snapshot()
        check(completed.sector == 1) { "BrowserRunStatisticsDiagnostics.kt:62" }
        check(profile.browserMostCrew == 3 && profile.browserMostScrap == completed.scrap && profile.browserMostBeacons == completed.beacons) { "BrowserRunStatisticsDiagnostics.kt:63" }
        val wins = profile.browserVictories
        state.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
        check(profile.browserVictories == wins && profile.browserGamesPlayed == 2) { "BrowserRunStatisticsDiagnostics.kt:66" }
        val xml = BrowserXml.write(profile.save())
        val restored = SaveProfile.createBlank().also { it.load(BrowserXml.parse(xml)) }
        check(restored.browserGamesPlayed == 2 && restored.browserLifetimeBeacons == 3L && restored.browserLifetimeCrew == 7L) { "BrowserRunStatisticsDiagnostics.kt:69" }
        check(restored.browserMostCrew == 3 && restored.browserMostScrap == completed.scrap && restored.browserMostShips == profile.browserMostShips) { "BrowserRunStatisticsDiagnostics.kt:70" }
        check(restored.browserLastRun?.sector == 1 && BrowserRunRecord.load(completed.save("score")) == completed) { "BrowserRunStatisticsDiagnostics.kt:71" }
        val partial = completed.copy(crew = 99, crewComplete = false, scrap = 0, beacons = 0, ships = 0)
        restored.recordBrowserRun(partial)
        check(restored.browserMostCrew == 3 && restored.browserMostScrap == completed.scrap) { "Unknown recruitment history or smaller run replaced maxima" }
        restored.recordBrowserRun(completed.copy(crew = 5, ships = 3))
        check(restored.browserMostCrew == 5 && restored.browserMostShips == 3) { "BrowserRunStatisticsDiagnostics.kt:76" }

        BrowserEngine.diagnosticProgress("native run statistics clone queue and hangar exclusions")
        state = start("PLAYER_SHIP_MANTIS_3")
        val cloning = state.player.crew.filterIsInstance<LivingCrew>().first()
        val crewBefore = state.browserProgress.crewRecruited
        val lifetimeBefore = profile.browserLifetimeCrew
        val clonebay = state.player.clonebay!!
        if (clonebay.powerSelected == 0) clonebay.increasePower()
        check(clonebay.powerSupplied > 0) { "BrowserRunStatisticsDiagnostics.kt:85" }
        cloning.dealDamage(SuffocationDamage(1000f))
        repeat(90) { if (state.player.crew.contains(cloning)) cloning.update(.05f) }
        check(clonebay.queue.contains(cloning)) { "Crew did not enter actual clone queue" }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        val savedClonebay = state.player.clonebay!!
        val savedCrew = savedClonebay.queue.first()
        if (savedClonebay.powerSelected == 0) savedClonebay.increasePower()
        check(savedClonebay.powerSupplied > 0) { "BrowserRunStatisticsDiagnostics.kt:94" }
        repeat(300) { if (savedClonebay.queue.isNotEmpty()) savedClonebay.update(.05f) }
        check(savedClonebay.queue.isEmpty() && state.player.crew.contains(savedCrew)) { "Actual cloning did not finish" }
        check(state.browserProgress.crewRecruited == crewBefore && profile.browserLifetimeCrew == lifetimeBefore) { "Clone queue or restoration counted recruitment twice" }
        val hangarBefore = BrowserXml.write(profile.save())
        main.switchToShipSelect()
        check(BrowserXml.write(profile.save()) == hangarBefore) { "Hangar preview changed lifetime statistics" }

        BrowserEngine.diagnosticProgress("native statistics AE scores after choosing vanilla")
        profile.unlockLayout(ShipProgression.LANIUS, Difficulty.NORMAL)
        profile.unlockLayout("PLAYER_SHIP_HARD_3", Difficulty.NORMAL)
        profile.recordBrowserRun(completed.copy(ship = ShipProgression.LANIUS, title = "The Kruos", scrap = 8000))
        profile.recordBrowserRun(completed.copy(ship = "PLAYER_SHIP_HARD_3", title = "The Swallow", scrap = 7000))
        main.changeHangarMode(false, ShipProgression.KESTREL, Difficulty.NORMAL)
        val statsBefore = BrowserXml.write(profile.save())
        val randomBefore = BrowserRandom.snapshot()
        val stats = BrowserStatisticsState(main, null)
        main.setCurrentState(stats)
        main.render(container, graphics)
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 480, 65, 1)
        stats.mouseClicked(Input.MOUSE_LEFT_BUTTON, 340, 170, 1)
        check(stats.diagnosticShip == "PLAYER_SHIP_HARD_3") { "BrowserRunStatisticsDiagnostics.kt:115" }
        main.render(container, graphics)
        stats.keyPressed(Input.KEY_RIGHT, '\u0000')
        check(stats.diagnosticShip == ShipProgression.LANIUS) { "BrowserRunStatisticsDiagnostics.kt:118" }
        main.render(container, graphics)
        check(!main.gameContent.enableAdvancedEdition && BrowserXml.write(profile.save()) == statsBefore && BrowserRandom.snapshot() == randomBefore) { "Viewing AE scores changed next-run mode, progression or RNG" }
        stats.close()
    }
}
