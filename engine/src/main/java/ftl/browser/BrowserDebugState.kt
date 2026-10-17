package ftl.browser
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.systems.Weapons

object BrowserDebugState {
    fun snapshot(main: MainGame): String {
        val state = main.currentState
        if (state == null) return "{\"state\":\"transition\",\"tick\":${BrowserDebug.tick},\"recording\":${BrowserDebug.enabled}}"
        val sounds = main.gameContent.sounds as? xyz.znix.xftl.game.RealSoundManager
        val tutorial = main.tutorialAdapter?.controller
        val settings = ",\"autoPause\":${main.profile.autoPauseOnFocusLoss},\"beaconPaths\":${main.profile.showBeaconPaths},\"dialogKeys\":${main.profile.eventChoiceHotkeys.id},\"focused\":${main.isBrowserFocused}"
        val common = "\"tick\":${BrowserDebug.tick},\"rng\":${BrowserDebug.quote(BrowserRandom.snapshot())},\"recording\":${BrowserDebug.enabled},\"tutorial\":${main.isTutorialRunning},\"state\":${BrowserDebug.quote(state.javaClass.simpleName)},\"fpsLimit\":${main.profile.frameRateLimit},\"soundVolume\":${sounds?.soundEffectVolume},\"musicVolume\":${sounds?.musicVolume}$settings"
        if (state is xyz.znix.xftl.game.SettingsMenuState) return "{$common,\"window\":${BrowserDebug.quote(state.diagnosticWindow)},\"windowRect\":${state.diagnosticWindowRect},\"notice\":${state.diagnosticNotice}}"
        if (state is xyz.znix.xftl.game.CreditsState) return "{$common,\"scroll\":${state.scroll},\"backers\":${state.backerCount},\"creditLines\":${state.lineCount}}"
        if (state is BrowserStatisticsState) return "{$common,\"statisticsTab\":${BrowserDebug.quote(state.diagnosticTab)},\"statisticsScoresTab\":${BrowserDebug.quote(state.diagnosticScoresTab)},\"statisticsShip\":${BrowserDebug.quote(state.diagnosticShip)},\"statisticsShipList\":${state.diagnosticShipList}}"
        if (state is xyz.znix.xftl.hangar.SelectShipState) {
            val ui=state.hangarPresentation
            val crew=ui.crew.joinToString(",", "[", "]") { "{\"species\":${BrowserDebug.quote(it.species)},\"name\":${BrowserDebug.quote(it.name)},\"colour\":${it.colour}}" }
            return "{$common,\"ae\":${state.advancedEdition},\"difficulty\":${BrowserDebug.quote(state.selectedDifficulty.name)},\"ship\":${BrowserDebug.quote(state.selectedShipId)},\"unlocked\":${state.isSelectedUnlocked},\"lockReason\":${BrowserDebug.quote(if(state.isSelectedUnlocked) null else state.lockReason)},\"roomsVisible\":${state.roomsVisible},\"shipList\":${state.isShipListVisible},\"shipListTutorial\":${state.isShipListTutorialOpen},\"shipName\":${BrowserDebug.quote(ui.diagnosticName)},\"crew\":$crew,\"editing\":${ui.diagnosticEditing},\"customizing\":${ui.isCustomizing},\"tooltip\":${BrowserDebug.quote(ui.diagnosticTooltip)},\"controls\":${ui.diagnosticControls}}"
        }
        if (state is xyz.znix.xftl.game.MainMenuState) return "{$common,\"window\":${BrowserDebug.quote(state.diagnosticWindow)},\"windowRect\":${state.diagnosticWindowRect},\"confirmation\":${state.diagnosticConfirmation}}"
        val game = state as? InGameState ?: return "{$common}"
        val dialogue = game.shipUI.diagnosticDialogue
        val dialogueState = if (dialogue == null) "null" else "{\"openTime\":${dialogue.openTime},\"ready\":${dialogue.choiceInputReady},\"choices\":${dialogue.diagnosticChoiceBounds}}"
        val tutorialState = "\"tutorialStep\":${BrowserDebug.quote(tutorial?.step?.id)},\"tutorialContinue\":${tutorial?.canContinue ?: false},\"tutorialCompleted\":${tutorial?.completed ?: false},\"tutorialUI\":${BrowserTutorialUiState.snapshot(main, game)}"
        val ship=game.player
        val weapons=ship.weapons
        val droneState = ship.drones?.drones?.mapIndexed { slot, info ->
            val instance = info?.instance
            val pawn = (instance as? xyz.znix.xftl.drones.AbstractIndoorsDrone)?.pawn
            "{\"slot\":$slot,\"type\":${BrowserDebug.quote(info?.type?.name)},\"powered\":${instance?.isPowered},\"cooldown\":${info?.cooldown},\"shieldRecharge\":${(instance as? xyz.znix.xftl.drones.ShieldDrone)?.rechargeRemaining},\"pawnHealth\":${pawn?.health},\"pawnRoom\":${pawn?.room?.id},\"pawnAction\":${BrowserDebug.quote(pawn?.currentAction?.name)}}"
        }?.joinToString(",") ?: ""
        val progress = game.browserAchievements
        val details = "\"hull\":${ship.health},\"superShield\":${ship.superShield},\"droneState\":[$droneState],\"sector\":${game.currentBeacon.sector.sectorNumber},\"secretSector\":${game.currentBeacon.sector.info.isSecretSector},\"focusPaused\":${game.isFocusPaused},\"dialogue\":$dialogueState,\"achievementCounters\":{\"nebulaJumps\":${progress.nebulaJumps},\"crewBlueChoices\":${progress.crewBlueChoices},\"crewDefeats\":${progress.crewDefeats},\"rockHullDefeats\":${progress.rockHullDefeats}}"
        val slots=ship.hardpoints.mapIndexed { index,h ->
            val w=h.weapon
            val target = weapons?.selectedTargets?.getTarget(index)
            val targetJson = when (target) {
                is xyz.znix.xftl.systems.SelectedTarget.RoomAim -> "{\"kind\":\"room\",\"ship\":${BrowserDebug.quote(target.targetShip.type.name)},\"room\":${target.room.id}}"
                is xyz.znix.xftl.systems.SelectedTarget.BeamAim -> "{\"kind\":\"beam\",\"ship\":${BrowserDebug.quote(target.targetShip.type.name)},\"x\":${target.startShipPoint.x},\"y\":${target.startShipPoint.y},\"angle\":${target.angle},\"length\":${target.length}}"
                else -> "null"
            }
            if(w==null) "{\"slot\":$index,\"weapon\":null}" else "{\"slot\":$index,\"identity\":${System.identityHashCode(w)},\"weapon\":${BrowserDebug.quote(w.type.name)},\"powered\":${w.isPowered},\"charge\":${w.timeCharged},\"charges\":${w.extraCharges},\"chain\":${w.chainCount},\"firing\":${w.isFiring},\"autofireOverride\":${w.autofireOverride},\"target\":$targetJson}"
        }.joinToString(",")
        return "{$common,$tutorialState,$details,\"ship\":${BrowserDebug.quote(ship.type.name)},\"ae\":${game.content.enableAdvancedEdition},\"difficulty\":${BrowserDebug.quote(game.difficulty.name)},\"paused\":${game.isPaused},\"playerPaused\":${game.isPlayerPaused},\"window\":${BrowserDebug.quote(game.shipUI.diagnosticWindow)},\"windowRect\":${game.shipUI.diagnosticWindowBounds},\"notice\":${game.shipUI.diagnosticNotice},\"confirmation\":${game.shipUI.diagnosticConfirmation},\"console\":${game.isDebugConsoleVisible},\"scrap\":${ship.scrap},\"fuel\":${ship.fuelCount},\"missiles\":${ship.missilesCount},\"drones\":${ship.dronesCount},\"autofire\":${weapons?.autofire},\"armaments\":${game.shipUI.diagnosticArmaments},\"weapons\":[$slots]}"
    }
}
