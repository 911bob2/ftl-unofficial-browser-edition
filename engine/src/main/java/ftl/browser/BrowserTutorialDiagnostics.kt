package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.MainMenuState
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.IBlueprint

/** Script/isolation coverage; this does not claim an implemented interactive tutorial. */
object BrowserTutorialDiagnostics {
    fun gates(main: MainGame) {
        BrowserEngine.diagnosticProgress("33 desktop tutorial controller states and native action gates")
        val profile = BrowserXml.write(main.profile.save())
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val mode = main.gameContent.enableAdvancedEdition
        val definition = BrowserTutorialDefinition(main.gameContent.datafile)
        val controller = BrowserTutorialController(definition)
        val neutral = BrowserTutorialController.Observation()
        val enabled = setOf("POWER_SYSTEM_2", "DAMAGE_SYSTEM_4", "FIRE_END", "DAMAGE_SYSTEM_6")
        var automatic = 0
        var manual = 0
        val transitions = ArrayList<BrowserTutorialController.Transition>()
        for ((index, step) in definition.steps.withIndex()) {
            check(controller.index == index && controller.step.id == step.id && !controller.completed) { "BrowserTutorialDiagnostics.kt:29" }
            val before = controller.index
            val bad = when (step.id) {
                "STAR_MAP_1" -> neutral.copy(mapOpen = true)
                "INVENTORY_2" -> neutral.copy(shipInfoOpen = true)
                "UPGRADES_1_2" -> neutral.copy(upgradesOpen = true)
                else -> neutral
            }
            check(controller.observe(bad) == null && controller.index == before) { "Wrong action advanced ${step.id}" }
            val transition: BrowserTutorialController.Transition?
            if (step.continueButton) {
                manual++
                if (step.id == "UPGRADES_1") {
                    check(!controller.upgradeAcceptAllowed(neutral.copy(pendingWeaponUpgrades = 1))) {
                        "Upgrade window accepted during its first explanatory step"
                    }
                }
                check(controller.canContinue) { "BrowserTutorialDiagnostics.kt:46" }
                transition = controller.continueStep()
            } else {
                check(!controller.canContinue && controller.continueStep() == null) { "Continue bypassed ${step.id}" }
                val good = when (step.id) {
                    "POWER_SYSTEM_2" -> neutral.copy(enginePower = 1)
                    "DAMAGE_SYSTEM_2" -> neutral.copy(selectedCrew = 1)
                    "DAMAGE_SYSTEM_3" -> neutral.copy(engineCrew = 1)
                    "DAMAGE_SYSTEM_4" -> neutral.copy(engineCapacity = 2, engineHealth = 2)
                    "FIRE_PC_1" -> neutral.copy(airlockOpen = true)
                    "FIRE_END" -> neutral.copy(roomFires = 0)
                    "DAMAGE_SYSTEM_6" -> neutral.copy(pilotCrew = 1)
                    "DAMAGE_SYSTEM_7" -> neutral.copy(mapOpen = true)
                    "STAR_MAP_1" -> neutral
                    "COMBAT_0" -> neutral.copy(playerPaused = true)
                    "COMBAT_1" -> neutral.copy(nonMissilePowered = true)
                    "COMBAT_2" -> neutral.copy(nonMissileFired = true)
                    "COMBAT_4" -> neutral.copy(missileFired = true)
                    "COMBAT_4_6" -> neutral.copy(enemyPresent = false)
                    "INVENTORY_1" -> neutral.copy(shipInfoOpen = true)
                    "INVENTORY_2" -> neutral.copy(beamEquipped = true)
                    "COMBAT_5" -> neutral.copy(upgradesOpen = true)
                    "UPGRADES_1_2" -> neutral.copy(pendingWeaponUpgrades = 1)
                    "UPGRADES_2" -> neutral.copy(beamPowered = true)
                    else -> error("Missing reference-backed fixture ${step.id}")
                }
                if (step.id == "INVENTORY_2") {
                    check(!controller.equipmentAcceptAllowed(bad)) { "BrowserTutorialDiagnostics.kt:73" }
                    check(controller.equipmentAcceptAllowed(good)) { "BrowserTutorialDiagnostics.kt:74" }
                }
                if (step.id == "UPGRADES_1_2") {
                    check(!controller.upgradeAcceptAllowed(bad)) { "BrowserTutorialDiagnostics.kt:77" }
                    check(controller.upgradeAcceptAllowed(good)) { "BrowserTutorialDiagnostics.kt:78" }
                }
                if (step.id in enabled) {
                    check(controller.observe(good) == null && controller.index == before && controller.canContinue) {
                        "Action automatically advanced ${step.id} instead of enabling Continue"
                    }
                    check(controller.observe(bad) == null && controller.canContinue) { "Continue latch was lost in ${step.id}" }
                    transition = controller.continueStep()
                } else {
                    automatic++
                    transition = controller.observe(good)
                }
            }
            check(transition != null && transition.previous == step.id && transition.sequence == index + 1) { "BrowserTutorialDiagnostics.kt:91" }
            transitions.add(transition)
            check(transition.next == definition.steps.getOrNull(index + 1)?.id) { "BrowserTutorialDiagnostics.kt:93" }
        }
        check(manual == 14 && automatic == 15 && controller.completed && transitions.size == 33) { "BrowserTutorialDiagnostics.kt:95" }
        check(controller.continueStep() == null && controller.observe(neutral) == null && !controller.canContinue) {
            "Tutorial completion was emitted twice"
        }
        check(BrowserXml.write(main.profile.save()) == profile && BrowserFiles.getRun() == run && BrowserRandom.snapshot() == rng && main.gameContent.enableAdvancedEdition == mode) { "BrowserTutorialDiagnostics.kt:99" }
    }

    fun isolation(main: MainGame, container: GameContainer, graphics: Graphics) {
        val originalProfile = main.profile
        val originalDocument = originalProfile.save()
        val originalRun = BrowserFiles.getRun()
        val originalStoredProfile = BrowserFiles.getProfile()
        val originalRng = BrowserRandom.snapshot()
        val originalMode = main.gameContent.enableAdvancedEdition
        val originalBrowserMode = BrowserFiles.enableAE
        check(main.currentState is MainMenuState) { "BrowserTutorialDiagnostics.kt:110" }
        try {
            for (mode in listOf(false, true)) {
                BrowserEngine.diagnosticProgress("tutorial isolation with ordinary AE=$mode save")
                main.changeHangarMode(mode, "PLAYER_SHIP_HARD", Difficulty.EASY)
                main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
                main.writeRunSave()
                main.switchToMainMenu()
                val savedRun = BrowserFiles.getRun()
                val savedProfile = BrowserXml.write(main.profile.save())
                BrowserFiles.hydrate(savedRun, savedProfile)
                val beforeProfile = main.profile
                val beforeDirty = beforeProfile.dirty
                val beforeGames = beforeProfile.browserGamesPlayed
                val beforeNotification = main.progressNotifications.active
                val beforeRng = BrowserRandom.snapshot()
                val state = main.startTutorialSession()
                check(main.isTutorialRunning && BrowserFiles.isTemporaryWrites()) { "BrowserTutorialDiagnostics.kt:127" }
                check(state.player.type.name == "PLAYER_SHIP_TUTORIAL" && !state.content.enableAdvancedEdition) { "BrowserTutorialDiagnostics.kt:128" }
                // The original tutorial runs at normal difficulty: 10 starting scrap.
                check(state.difficulty == Difficulty.NORMAL && state.player.scrap == 10) { "Tutorial difficulty ${state.difficulty}, scrap ${state.player.scrap}" }
                check(main.profile !== beforeProfile && main.profile.browserGamesPlayed == beforeGames) {
                    "Tutorial start counted as an ordinary game or reused the ordinary profile"
                }
                var rejected = false
                try { main.startTutorialSession() } catch (expected: IllegalStateException) { rejected = true }
                check(rejected && main.isTutorialRunning) { "Nested tutorial replaced the first isolation context" }
                main.profile.frameRateLimit = 123
                main.profile.lastAdvancedEdition = false
                main.profile.unlockLayout("PLAYER_SHIP_HARD_2", Difficulty.HARD)
                main.profile.unlockAchievement(main.gameContent.achievements["ACH_WIN_EASY"], Difficulty.HARD)
                state.browserProgress.collectScrap(10001)
                state.browserProgress.finish(GameOverWindow.Outcome.WIN)
                main.profile.markDirty()
                main.update(container, 0f)
                main.render(container, graphics)
                main.writeRunSave()
                main.deleteRunSave()
                BrowserEngine.save()
                check(BrowserFiles.getRun() == savedRun && BrowserFiles.getProfile() == savedProfile) {
                    "Tutorial save, completion or deletion published temporary state"
                }
                check(BrowserXml.write(beforeProfile.save()) == savedProfile && beforeProfile.dirty == beforeDirty) { "BrowserTutorialDiagnostics.kt:150" }
                check(main.progressNotifications.active == beforeNotification) { "Tutorial exposed a progression notification" }
                check(main.doSaveLoadGame()) { "Temporary tutorial combat checkpoint failed to restore" }
                BrowserEngine.key(Input.KEY_LSHIFT, 0, true)
                BrowserEngine.mouse(0, 0, 1, Input.MOUSE_LEFT_BUTTON)
                main.quitGame()
                check(!container.input.isKeyDown(Input.KEY_LSHIFT) && !container.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)) {
                    "Tutorial exit retained held input"
                }
                check(!main.isTutorialRunning && !BrowserFiles.isTemporaryWrites() && main.currentState is MainMenuState) { "BrowserTutorialDiagnostics.kt:159" }
                check(main.profile === beforeProfile && BrowserXml.write(main.profile.save()) == savedProfile) { "BrowserTutorialDiagnostics.kt:160" }
                check(main.gameContent.enableAdvancedEdition == mode && BrowserFiles.enableAE == mode) { "BrowserTutorialDiagnostics.kt:161" }
                check(BrowserRandom.snapshot() == beforeRng) { "Tutorial consumed the ordinary simulation RNG" }
                check(main.hasRunSave()) { "BrowserTutorialDiagnostics.kt:163" }
                main.continueSavedRun()
                val continued = main.currentState as InGameState
                check(continued.player.type.name == "PLAYER_SHIP_HARD" && continued.content.enableAdvancedEdition == mode) { "BrowserTutorialDiagnostics.kt:166" }
                check(main.profile.browserGamesPlayed == beforeGames) { "Continue added a tutorial/loaded game" }
                main.switchToMainMenu()
                main.startTutorialSession()
                main.restartGame()
                check(main.isTutorialRunning && BrowserFiles.getRun() == savedRun) { "BrowserTutorialDiagnostics.kt:171" }
                main.endTutorialSession()
                main.endTutorialSession()
                check(main.profile === beforeProfile && !BrowserFiles.isTemporaryWrites()) { "BrowserTutorialDiagnostics.kt:174" }
                check(BrowserFiles.getRun() == savedRun && BrowserFiles.getProfile() == savedProfile) { "BrowserTutorialDiagnostics.kt:175" }
            }

            BrowserEngine.diagnosticProgress("failed tutorial initialization restores normal session")
            main.changeHangarMode(false, "PLAYER_SHIP_HARD", Difficulty.EASY)
            main.switchToMainMenu()
            val beforeProfile = main.profile
            val beforeXml = BrowserXml.write(beforeProfile.save())
            val beforeRng = BrowserRandom.snapshot()
            val blueprints = main.gameContent.blueprintManager.blueprints as MutableMap<String, IBlueprint>
            val tutorialBlueprint = blueprints.remove("PLAYER_SHIP_TUTORIAL") ?: error("Missing tutorial fixture blueprint")
            var failed = false
            try { main.startTutorialSession() } catch (expected: RuntimeException) { failed = true }
            finally { blueprints["PLAYER_SHIP_TUTORIAL"] = tutorialBlueprint }
            check(failed && !main.isTutorialRunning && !BrowserFiles.isTemporaryWrites()) { "BrowserTutorialDiagnostics.kt:189" }
            check(main.currentState is MainMenuState && main.profile === beforeProfile) { "BrowserTutorialDiagnostics.kt:190" }
            check(BrowserXml.write(main.profile.save()) == beforeXml && BrowserRandom.snapshot() == beforeRng) { "BrowserTutorialDiagnostics.kt:191" }

            BrowserEngine.diagnosticProgress("tutorial shutdown restores ordinary profile and write access")
            val disposable = MainGame(MainGame.CommandLineArgs())
            disposable.init(container)
            val shutdownProfile = disposable.profile
            val shutdownRng = BrowserRandom.snapshot()
            try {
                disposable.startTutorialSession()
                disposable.shutdown()
                check(!disposable.isTutorialRunning && !BrowserFiles.isTemporaryWrites() && disposable.profile === shutdownProfile) { "BrowserTutorialDiagnostics.kt:201" }
                check(BrowserRandom.snapshot() == shutdownRng) { "BrowserTutorialDiagnostics.kt:202" }
            } finally { if (disposable.isTutorialRunning) disposable.shutdown() }
        } finally {
            main.endTutorialSession()
            main.changeHangarMode(originalMode, "PLAYER_SHIP_HARD", Difficulty.EASY)
            main.switchToMainMenu()
            // The diagnostic engine is disposable. Do not hydrate a tutorial
            // checkpoint or its temporary profile into ordinary browser storage.
            originalProfile.load(originalDocument)
            BrowserFiles.hydrate(originalRun, originalStoredProfile)
            BrowserFiles.enableAE = originalBrowserMode
            BrowserRandom.restore(originalRng)
        }
    }

    fun definition(main: MainGame) {
        BrowserEngine.diagnosticProgress("official desktop tutorial script and arrow metadata")
        val profileBefore = BrowserXml.write(main.profile.save())
        val runBefore = BrowserFiles.getRun()
        val rngBefore = BrowserRandom.snapshot()
        val modeBefore = main.gameContent.enableAdvancedEdition
        val definition = BrowserTutorialDefinition(main.gameContent.datafile)
        check(definition.steps.size == 33) { "BrowserTutorialDiagnostics.kt:224" }
        check(definition.steps.first().id == "SHIP_INTRO_1" && definition.steps.last().id == "DONE_1") { "BrowserTutorialDiagnostics.kt:225" }
        check(definition.steps.none { "IPAD" in it.id }) { "BrowserTutorialDiagnostics.kt:226" }
        check(definition.steps.count { it.continueButton } == 14) { "BrowserTutorialDiagnostics.kt:227" }
        check(definition.steps.all { main.gameContent.translator[it.text].isNotBlank() }) { "BrowserTutorialDiagnostics.kt:228" }
        check(definition.steps.all { step -> step.text.getKeyForTesting()?.let { it in main.gameContent.translator.translations } ?: true }) {
            "A desktop tutorial instruction is missing its translation"
        }
        val steps = definition.steps.associateBy { it.id }
        check(steps.getValue("SHIP_INTRO_4").text.getKeyForTesting() == "tutorial_SHIP_INTRO_4_PC") { "BrowserTutorialDiagnostics.kt:233" }
        check(steps.getValue("SHIP_INTRO_4").continueButton) { "BrowserTutorialDiagnostics.kt:234" }
        check(!steps.getValue("POWER_SYSTEM_2").continueButton) { "BrowserTutorialDiagnostics.kt:235" }
        check(steps.getValue("FIRE_PC_1").arrows.single().let { it.x == 495 && it.y == 515 && it.angle == 270 }) { "BrowserTutorialDiagnostics.kt:236" }
        check(steps.getValue("INVENTORY_2").arrows.all { it.anchor == BrowserTutorialDefinition.Anchor.UPGRADES }) { "BrowserTutorialDiagnostics.kt:237" }
        check(steps.getValue("INVENTORY_2").arrows.any { it.x == -140 && it.angle == 0 }) { "BrowserTutorialDiagnostics.kt:238" }
        check(steps.getValue("COMBAT_4").arrows.all { it.anchor == BrowserTutorialDefinition.Anchor.SCREEN }) { "BrowserTutorialDiagnostics.kt:239" }
        check(BrowserXml.write(main.profile.save()) == profileBefore && BrowserFiles.getRun() == runBefore && BrowserRandom.snapshot() == rngBefore && main.gameContent.enableAdvancedEdition == modeBefore) {
            "Reading tutorial data changed ordinary state"
        }
    }
}
