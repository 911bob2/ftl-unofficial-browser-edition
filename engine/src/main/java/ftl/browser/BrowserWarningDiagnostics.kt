package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/** Controlled fixtures exercise actual menu actions, separate from played runs. */
object BrowserWarningDiagnostics {
    private fun start(main: MainGame, container: GameContainer): InGameState {
        main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
        repeat(90) {
            main.update(container, .05f)
            if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
            }
        }
        return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
    }

    fun boarderFixture(main: MainGame, container: GameContainer): InGameState {
        val state = start(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 537)
        val boarder = state.playerCrew.first { it.room !== state.player.piloting!!.room }
        boarder.teleportAnimatedTo(state.enemy!!.rooms.first())
        repeat(100) { state.updateGameState(.05f) }
        state.player.ftlChargeProgress = 1f
        check(state.shipUI.strandedPlayerCrewCount == 1 && state.player.isFtlReady && state.player.canChargeFTL) { "BrowserWarningDiagnostics.kt:30" }
        return state
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val profile = BrowserXml.write(main.profile.save())
        val storedProfile = BrowserFiles.getProfile()
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        fun save(state: InGameState) = BrowserXml.write(state.saveGameState())
        fun click(win: ConfirmationWindow, yes: Boolean) {
            val p = if (yes) win.yesCentre else win.noCentre
            win.mouseClick(Input.MOUSE_LEFT_BUTTON, p.x, p.y)
        }
        fun titleStart(): MainMenuState {
            val menu = main.currentState as MainMenuState
            menu.mouseClicked(Input.MOUSE_LEFT_BUTTON, 1180, 353, 1)
            return menu
        }
        fun pauseAction(state: InGameState, restart: Boolean): ConfirmationWindow {
            state.shipUI.showPauseWindow()
            main.render(container, graphics)
            val bounds = state.shipUI.diagnosticWindowBounds
            val x = Regex("\"x\":(\\d+)").find(bounds)!!.groupValues[1].toInt()
            val y = Regex("\"y\":(\\d+)").find(bounds)!!.groupValues[1].toInt()
            state.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, x + 50, y + if (restart) 146 else 109, xyz.znix.xftl.math.ConstPoint.ZERO)
            return state.shipUI.confirmationWindow ?: error("Pause action skipped confirmation")
        }
        try {
            main.profile.load(SaveProfile.createBlank().save())
            var state = start(main, container)
            state.browserProgress.collectScrap(123)
            main.writeRunSave()
            val checkpoint = BrowserFiles.getRun()
            val before = save(state)
            val beforeProfile = BrowserXml.write(main.profile.save())
            for (restart in listOf(true, false)) {
                val warning = pauseAction(state, restart)
                check(warning.messageKey == "confirm_end_game") { "BrowserWarningDiagnostics.kt:68" }
                warning.mouseClick(Input.MOUSE_RIGHT_BUTTON, warning.yesCentre.x, warning.yesCentre.y)
                warning.mouseClick(Input.MOUSE_LEFT_BUTTON, 0, 0)
                check(main.currentState === state && state.shipUI.confirmationWindow === warning) { "BrowserWarningDiagnostics.kt:71" }
                warning.onTextInput(Input.KEY_1, '1')
                check(!state.browserProgress.finished) { "BrowserWarningDiagnostics.kt:73" }
                click(warning, false)
                check(state.shipUI.diagnosticWindow == "PauseWindow") { "BrowserWarningDiagnostics.kt:75" }
                check(save(state) == before && BrowserFiles.getRun() == checkpoint) { "BrowserWarningDiagnostics.kt:76" }
                check(BrowserXml.write(main.profile.save()) == beforeProfile) { "BrowserWarningDiagnostics.kt:77" }
                state.shipUI.escapePressed()
                val escapeWarning = pauseAction(state, restart)
                escapeWarning.onTextInput(Input.KEY_ESCAPE, '\u0000')
                check(state.shipUI.diagnosticWindow == "PauseWindow" && main.currentState === state) { "BrowserWarningDiagnostics.kt:81" }
                state.shipUI.escapePressed()
            }
            val oldProgress = state.browserProgress
            val oldScore = oldProgress.score
            val confirmed = pauseAction(state, true)
            click(confirmed, true); click(confirmed, true)
            check(main.currentState is InGameState && main.currentState !== state) { "BrowserWarningDiagnostics.kt:88" }
            check(main.profile.browserHighScores().single().score == oldScore) { "BrowserWarningDiagnostics.kt:89" }
            check(main.profile.browserVictories == 0 && oldProgress.finished) { "BrowserWarningDiagnostics.kt:90" }
            val profileAfter = BrowserXml.write(main.profile.save())
            oldProgress.finishAbandoned(); oldProgress.finish(GameOverWindow.Outcome.WIN)
            check(BrowserXml.write(main.profile.save()) == profileAfter) { "BrowserWarningDiagnostics.kt:93" }
            main.loadSavedGame(BrowserXml.parse(save(state)))
            state = main.currentState as InGameState
            check(state.browserProgress.finished) { "BrowserWarningDiagnostics.kt:96" }
            state.browserProgress.finishAbandoned()
            check(BrowserXml.write(main.profile.save()) == profileAfter) { "BrowserWarningDiagnostics.kt:98" }

            state = start(main, container)
            main.writeRunSave()
            click(pauseAction(state, false), true)
            check(main.currentState is xyz.znix.xftl.hangar.SelectShipState && !main.hasRunSave()) { "BrowserWarningDiagnostics.kt:103" }
            check(state.browserRunEnded && state.browserProgress.finished) { "BrowserWarningDiagnostics.kt:104" }

            state = start(main, container)
            main.quitGame()
            val saved = BrowserFiles.getRun()
            val savedProfile = BrowserXml.write(main.profile.save())
            var menu = titleStart()
            check(menu.confirmationWindow!!.messageKey == "confirm_new_game") { "BrowserWarningDiagnostics.kt:111" }
            click(menu.confirmationWindow!!, false)
            check(main.currentState === menu && BrowserFiles.getRun() == saved) { "BrowserWarningDiagnostics.kt:113" }
            menu = titleStart(); menu.keyPressed(Input.KEY_ESCAPE, '\u0000')
            check(menu.confirmationWindow == null && BrowserFiles.getRun() == saved) { "BrowserWarningDiagnostics.kt:115" }
            menu = titleStart(); click(menu.confirmationWindow!!, true)
            check(main.currentState is xyz.znix.xftl.hangar.SelectShipState && !main.hasRunSave()) { "BrowserWarningDiagnostics.kt:117" }
            check(BrowserXml.write(main.profile.save()) == savedProfile) { "BrowserWarningDiagnostics.kt:118" }
            main.switchToMainMenu(); titleStart()
            check(main.currentState is xyz.znix.xftl.hangar.SelectShipState) { "BrowserWarningDiagnostics.kt:120" }

            state = boarderFixture(main, container)
            val boarder = state.enemy!!.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
            boarder.mindControlledBy = xyz.znix.xftl.systems.MindControl(state.blueprintManager["mind"] as xyz.znix.xftl.systems.SystemBlueprint)
            check(state.shipUI.strandedPlayerCrewCount == 1) { "Temporary allegiance hid player-owned boarder" }
            boarder.mindControlledBy = null
            boarder.ownerShip = state.enemy
            check(state.shipUI.strandedPlayerCrewCount == 0) { "Enemy-owned crew triggered a warning" }
            boarder.ownerShip = state.player
            main.writeRunSave(); val boardedSave = save(state)
            val fuel = state.player.fuelCount
            for (hotkey in listOf(false, true)) {
                if (hotkey) state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(VanillaHotkeys.FTL_JUMP), container.input)
                else state.shipUI.openJumpMap()
                val warning = state.shipUI.confirmationWindow ?: error("Boarder did not trigger jump warning")
                check(warning.messageKey == "confirm_leave_crew") { "BrowserWarningDiagnostics.kt:136" }
                main.render(container, graphics)
                click(warning, false)
                check(!state.shipUI.isWindowOpen && save(state) == boardedSave) { "BrowserWarningDiagnostics.kt:139" }
                state.shipUI.openJumpMap(); state.shipUI.escapePressed()
                check(!state.shipUI.isWindowOpen && save(state) == boardedSave) { "BrowserWarningDiagnostics.kt:141" }
            }
            state.shipUI.openJumpMap(); click(state.shipUI.confirmationWindow!!, true)
            check(state.shipUI.tutorialMapOpen && state.player.fuelCount == fuel && boarder.room.ship === state.enemy) { "BrowserWarningDiagnostics.kt:144" }
            state.shipUI.escapePressed()
            main.loadSavedGame(BrowserXml.parse(boardedSave)); state = main.currentState as InGameState
            state.shipUI.openJumpMap(); check(state.shipUI.confirmationWindow != null) { "BrowserWarningDiagnostics.kt:147" }
            state.shipUI.escapePressed()
            val restoredBoarder = state.enemy!!.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
            restoredBoarder.health = 0f
            check(state.shipUI.strandedPlayerCrewCount == 0) { "BrowserWarningDiagnostics.kt:151" }
            state.shipUI.openJumpMap(); check(state.shipUI.tutorialMapOpen) { "BrowserWarningDiagnostics.kt:152" }
            state.shipUI.escapePressed()
            restoredBoarder.health = 100f
            state.player.ftlChargeProgress = 0f
            state.shipUI.openJumpMap(); check(!state.shipUI.isWindowOpen) { "BrowserWarningDiagnostics.kt:156" }
            return "PASS restart/hangar/title warnings, right/outside click and keyboard rejection, No/Escape exact run/profile/checkpoint preservation, once-only abandonment scores after restore, Save+Quit retention, empty-save New Game, living boarders and pointer/hotkey jump warnings before map, Cancel fuel/crew/state preservation, accepted map without jump and dead/not-ready negatives"
        } finally {
            main.profile.load(BrowserXml.parse(profile))
            BrowserFiles.hydrate(run, storedProfile)
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
    }
}
