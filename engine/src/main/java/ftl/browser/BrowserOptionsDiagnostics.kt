package ftl.browser

import xyz.znix.xftl.game.*
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/** Controlled actual input paths; never an ordinary played campaign. */
object BrowserOptionsDiagnostics {
    private fun press(key: Int, char: Char = key.toChar()) {
        BrowserEngine.key(key, char.code, true); BrowserEngine.step(0f)
        BrowserEngine.key(key, char.code, false); BrowserEngine.step(0f)
    }

    private fun start(main: MainGame, container: GameContainer): InGameState {
        main.setBrowserFocus(true)
        main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
        repeat(90) {
            main.update(container, .05f)
            // Initial event setup is separate from the input modes tested below.
            (main.currentState as InGameState).shipUI.diagnosticDialogue?.selectOption(0)
        }
        return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
    }

    fun fixture(main: MainGame, container: GameContainer, graphics: Graphics, kind: String): String {
        require(kind in listOf("run", "dialog", "map")) { "BrowserOptionsDiagnostics.kt:29" }
        val game = start(main, container)
        if (kind == "dialog") game.shipUI.showSyntheticDialogue(DialogueWindow.SyntheticEvent("Input test"))
        if (kind != "map") { main.render(container, graphics); return "{}" }
        game.player.ftlChargeProgress = 1f
        game.shipUI.openJumpMap()
        main.render(container, graphics)
        val map = checkNotNull(game.shipUI.tutorialJumpWindow)
        val beacon = game.currentBeacon.neighbours.first { b -> b.neighbours.any { it !== game.currentBeacon } }
        val other = beacon.neighbours.first { it !== game.currentBeacon }
        fun point(x: Int, y: Int) = "{\"x\":${map.position.x + Sector.OFFSET.x + x},\"y\":${map.position.y + Sector.OFFSET.y + y}}"
        return "{\"hover\":${point(beacon.pos.x, beacon.pos.y)},\"connection\":${point((beacon.pos.x + other.pos.x) / 2, (beacon.pos.y + other.pos.y) / 2)}}"
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val profile = BrowserXml.write(main.profile.save())
        val stored = BrowserFiles.getProfile()
        val run = BrowserFiles.getRun()
        val rng = BrowserRandom.snapshot()
        val focused = main.isBrowserFocused
        var phase = "legacy defaults"
        fun xml(game: InGameState) = BrowserXml.write(game.saveGameState())
        try {
            println("TEST native gameplay option defaults and persistence")
            val legacy = BrowserXml.parse(profile)
            for (attr in listOf("autoPause", "beaconPaths", "dialogKeys")) legacy.rootElement.removeAttribute(attr)
            main.profile.load(legacy)
            println("TEST legacy defaults loaded")
            check(main.profile.autoPauseOnFocusLoss && main.profile.showBeaconPaths && main.profile.eventChoiceHotkeys == EventChoiceHotkeyMode.ENABLED) { "Legacy defaults: ${main.profile.autoPauseOnFocusLoss}/${main.profile.showBeaconPaths}/${main.profile.eventChoiceHotkeys}" }
            main.profile.autoPauseOnFocusLoss = false
            main.profile.showBeaconPaths = false
            for (mode in EventChoiceHotkeyMode.entries) {
                phase = "profile round trip $mode"
                main.profile.eventChoiceHotkeys = mode
                main.profile.load(BrowserXml.parse(BrowserXml.write(main.profile.save())))
                check(!main.profile.autoPauseOnFocusLoss && !main.profile.showBeaconPaths && main.profile.eventChoiceHotkeys == mode) { "Round trip $mode: ${main.profile.autoPauseOnFocusLoss}/${main.profile.showBeaconPaths}/${main.profile.eventChoiceHotkeys}" }
            }
            legacy.rootElement.setAttribute("dialogKeys", "-50")
            phase = "malformed defaults"
            legacy.rootElement.setAttribute("autoPause", "invalid")
            legacy.rootElement.setAttribute("beaconPaths", "invalid")
            main.profile.load(legacy)
            check(main.profile.autoPauseOnFocusLoss && main.profile.showBeaconPaths && main.profile.eventChoiceHotkeys == EventChoiceHotkeyMode.ENABLED) { "Malformed defaults: ${main.profile.autoPauseOnFocusLoss}/${main.profile.showBeaconPaths}/${main.profile.eventChoiceHotkeys}" }

            println("TEST focus pause restoration and explicit/modal separation")
            phase = "focus pause"
            var game = start(main, container)
            game.player.ftlChargeProgress = .5f
            val before = xml(game)
            main.setBrowserFocus(false)
            check(game.isPaused && game.isFocusPaused && !game.isPlayerPaused && !game.shipUI.isWindowOpen) { "Lost focus pause: ${game.isPaused}/${game.isFocusPaused}/${game.isPlayerPaused}/${game.shipUI.diagnosticWindow}" }
            check(xml(game) == before) { "Focus changed serialized simulation" }
            main.update(container, .05f)
            check(game.player.ftlChargeProgress == .5f) { "Focus pause advanced FTL charge: ${game.player.ftlChargeProgress}" }
            main.setBrowserFocus(true)
            check(!game.isPaused && !game.isFocusPaused) { "Focus return retained pause: ${game.shipUI.diagnosticWindow}/${game.isPlayerPaused}/${game.isFocusPaused}" }
            main.update(container, .05f)
            check(game.player.ftlChargeProgress > .5f) { "Focused simulation did not charge FTL: ${game.player.ftlChargeProgress}/${game.player.canChargeFTL}" }
            game.toggleQuickPause()
            main.setBrowserFocus(false); main.setBrowserFocus(false); main.setBrowserFocus(true)
            check(game.isPlayerPaused && game.isPaused) { "Focus cycle discarded explicit pause" }
            game.toggleQuickPause()
            main.profile.autoPauseOnFocusLoss = false
            main.setBrowserFocus(false)
            game.player.ftlChargeProgress = .5f
            val charge = game.player.ftlChargeProgress
            main.update(container, .05f)
            check(!game.isFocusPaused && game.player.ftlChargeProgress > charge) { "Disabled focus pause blocked simulation: ${game.isFocusPaused}/${game.player.ftlChargeProgress}/$charge" }
            main.profile.autoPauseOnFocusLoss = true; main.refreshFocusPause()
            check(game.isFocusPaused) { "Enabling focus pause did not apply immediately" }
            main.loadSavedGame(game.saveGameState())
            game = main.currentState as InGameState
            check(game.isFocusPaused && !game.isPlayerPaused) { "Restored focus state: ${game.isFocusPaused}/${game.isPlayerPaused}" }
            main.setBrowserFocus(true)
            check(!game.isPaused) { "Restore focus return retained pause: ${game.shipUI.diagnosticWindow}/${game.isPlayerPaused}/${game.isFocusPaused}" }

            println("TEST physical event keys independent of weapon bindings")
            phase = "physical event keys"
            val weapon = game.hotkeyManager.byID.getValue(VanillaHotkeys.WEAPON_SLOTS[0])
            main.profile.setKeybind(weapon, HotkeyButton.BY_KEY_ID.getValue(Input.KEY_Q))
            game.updateHotkeyBindings()
            fun dialogue(): DialogueWindow {
                game.shipUI.showSyntheticDialogue(DialogueWindow.SyntheticEvent("Input test"))
                main.render(container, graphics)
                return checkNotNull(game.shipUI.diagnosticDialogue)
            }
            main.profile.eventChoiceHotkeys = EventChoiceHotkeyMode.ENABLED
            var window = dialogue()
            press(Input.KEY_Q)
            check(game.shipUI.diagnosticDialogue === window) { "BrowserOptionsDiagnostics.kt:118" }
            press(Input.KEY_9)
            check(game.shipUI.diagnosticDialogue === window) { "BrowserOptionsDiagnostics.kt:120" }
            press(Input.KEY_1, '!')
            check(game.shipUI.diagnosticDialogue == null) { "BrowserOptionsDiagnostics.kt:122" }
            main.profile.eventChoiceHotkeys = EventChoiceHotkeyMode.DISABLED
            window = dialogue()
            val disabled = xml(game)
            press(Input.KEY_1)
            check(game.shipUI.diagnosticDialogue === window && xml(game) == disabled) { "BrowserOptionsDiagnostics.kt:127" }
            val mouse = checkNotNull(window.choiceCentre(0))
            game.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, mouse.x, mouse.y, ConstPoint.ZERO)
            check(game.shipUI.diagnosticDialogue == null) { "BrowserOptionsDiagnostics.kt:130" }

            println("TEST brief delay gates mouse/key and survives event chains and saves")
            phase = "brief delay"
            main.profile.eventChoiceHotkeys = EventChoiceHotkeyMode.BRIEF_DELAY
            window = dialogue()
            main.update(container, .299f)
            val delayed = xml(game)
            press(Input.KEY_1)
            val centre = checkNotNull(window.choiceCentre(0))
            game.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, centre.x, centre.y, ConstPoint.ZERO)
            check(game.shipUI.diagnosticDialogue === window && xml(game) == delayed) { "Early input changed event/save at ${window.openTime}" }
            val saved = game.saveGameState()
            main.loadSavedGame(saved); game = main.currentState as InGameState
            window = checkNotNull(game.shipUI.diagnosticDialogue)
            check(window.openTime == .299f && !window.choiceInputReady) { "Restored brief delay: ${window.openTime}/${window.choiceInputReady}" }
            main.update(container, .001f)
            check(!window.choiceInputReady) { "Rounded native float timer bypassed threshold: ${window.openTime}" }
            main.update(container, .0001f)
            check(window.choiceInputReady) { "Above 300ms boundary not ready: ${window.openTime}" }
            game.shipUI.showSyntheticDialogue(DialogueWindow.SyntheticEvent("Next input test"))
            check(window.openTime == .3f) { "Event chain reset open time: ${window.openTime}" }
            press(Input.KEY_1)
            check(game.shipUI.diagnosticDialogue === window && window.choiceInputReady) { "Event chain lost pending choice or readiness" }
            press(Input.KEY_1)
            check(game.shipUI.diagnosticDialogue == null) { "Event chain final choice remained open" }
            window = dialogue()
            check(window.openTime == 0f && !window.choiceInputReady) { "Fresh event reused previous delay" }
            main.update(container, .3f)
            main.render(container, graphics)
            val final = checkNotNull(window.choiceCentre(0))
            game.shipUI.mouseClick(Input.MOUSE_LEFT_BUTTON, final.x, final.y, ConstPoint.ZERO)
            check(game.shipUI.diagnosticDialogue == null) { "Ready mouse choice remained open" }
            phase = "UI clock independent of render caps"
            for (cap in listOf(30, 60, 120, 240, 0)) {
                main.profile.frameRateLimit = cap
                window = dialogue()
                repeat(10) { main.render(container, graphics) }
                check(window.openTime == 0f) { "Rendering advanced choice clock at cap$cap" }
                main.update(container, .299f)
                repeat(3) { main.render(container, graphics) }
                check(!window.choiceInputReady) { "Rendering bypassed delay at cap$cap" }
                main.update(container, .0011f)
                check(window.choiceInputReady) { "Update without drawing failed at cap$cap" }
                press(Input.KEY_1)
                check(game.shipUI.diagnosticDialogue == null) { "BrowserOptionsDiagnostics.kt:175" }
            }
            return "PASS native gameplay defaults, legacy/malformed profile fallback, all dialog modes/save restore, physical numbers independent of weapon shortcuts, mouse/key 0.3-second boundaries, retained event-chain time, focus pause/return preserving explicit pause and saves; beacon rendering and browser focus have separate interface acceptance"
        } catch (error: Throwable) {
            throw IllegalStateException("Gameplay options [$phase]: ${error.message}", error)
        } finally {
            main.profile.load(BrowserXml.parse(profile))
            BrowserFiles.hydrate(run, stored)
            BrowserRandom.restore(rng)
            main.setBrowserFocus(focused)
            main.switchToMainMenu()
        }
    }
}
