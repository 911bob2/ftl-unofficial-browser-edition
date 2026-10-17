package xyz.znix.xftl.game

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.ResourceContext

/**
 * The game's title screen, shown when the game opens (issue #2).
 *
 * Vanilla-look layout: the logo in the top-right and a column of menu
 * buttons under it, right-aligned. Continue remains visible when disabled.
 * Tutorial uses an independent game session.
 */
class MainMenuState(private val main: MainGame, private val datafile: Datafile) : MainGame.GameState() {
    private val resourceContext = ResourceContext()
    private val images = HashMap<String, Image>()

    private val background = getImg("img/main_menus/main_base2.png")
    private val logo = getImg("img/main_menus/main_FTL2.png")
    private val promotion = getImg("img/itb_mech_punch_a.png")

    private val buttons = ArrayList<MenuButton>()

    // All the buttons share a right edge (under the logo), and are stacked
    // one image-height apart - the images include their own visual padding.
    private val buttonsRightX = 1200
    private val firstButtonY = 275
    private val buttonSpacing = 58
    private var hoveredButton: MenuButton? = null

    private var mouseX = 0
    private var mouseY = 0

    private var musicStarted = false
    private var logoTime = 0f
    private var promotionTime = 0f
    private var confirmation: ConfirmationWindow? = null
    private var confirmedButton: MenuButton? = null
    // MainMenu::changelog: the native welcome ChoiceBox for a newly created profile.
    private var welcome: NativeChoiceBox? = if (main.consumeNewProfileWelcome()) SettingsContext(main, main.gameContainer.input).let { ctx ->
        NativeChoiceBox(ctx::getFont, ctx.getWindowRenderer(), ctx.translator["welcome"], listOf(NativeChoiceBox.Choice(ctx.translator["continue"])))
    } else null
    val diagnosticWindow get() = if (welcome != null) "WelcomeChoiceBox" else confirmation?.javaClass?.simpleName ?: "main"
    val diagnosticWindowRect get() = confirmation?.let {
        "{\"x\":${it.position.x},\"y\":${it.position.y},\"width\":${it.size.x},\"height\":${it.size.y}}"
    } ?: "null"
    val diagnosticConfirmation get() = confirmation?.diagnosticState ?: "null"
    val confirmationWindow get() = confirmation

    init {
        var y = firstButtonY

        // The desktop keeps this slot even without a saved run.
        buttons.add(makeButton("continue", y, if (main.hasRunSave()) ({ main.continueSavedRun() }) else null))
        y += buttonSpacing

        buttons.add(makeButton("start", y, this::newGameClicked))
        y += buttonSpacing

        for (name in listOf("tutorial", "stats", "options", "credits")) {
            val action: (() -> Unit)? = when (name) {
                "tutorial" -> { { main.startTutorialSession(); Unit } }
                "stats" -> { { main.setCurrentState(ftl.browser.BrowserStatisticsState(main, null)) } }
                "options" -> { { keepForOverlay = true; main.setCurrentState(SettingsMenuState(main, main::switchToMainMenu, this)) } }
                "credits" -> { { main.setCurrentState(CreditsState(main, main::switchToMainMenu)) } }
                else -> null
            }
            buttons.add(makeButton(name, y, action))
            y += buttonSpacing
        }

        buttons.add(makeButton("quit", y) { main.exitGame() })
        // MainMenu::itbButton: image and hit box at (0,595), 414x109 (live native object).
        buttons.add(MenuButton(getImg("img/itb_banner_ftl_on.png"),getImg("img/itb_banner_ftl_select2.png"),
            0,595,ftl.browser.BrowserEngine::openOriginalGamePromotion,0))
    }

    // The options screen keeps drawing this menu behind it, and frees it when it closes.
    private var keepForOverlay = false

    override fun shutdown() {
        if (keepForOverlay) {
            keepForOverlay = false
            return
        }
        resourceContext.freeAll()
    }

    override fun update(container: GameContainer, delta: Float) {
        logoTime = (logoTime + delta) % 3f
        promotionTime = (promotionTime + delta) % 1.2f
        mouseX = container.input.mouseX
        mouseY = container.input.mouseY
        welcome?.mouseMove(mouseX, mouseY)
        val hovered = if (confirmation == null && welcome == null) buttons.firstOrNull { it.action != null && it.contains(mouseX, mouseY) } else null
        if (hovered != null && hovered !== hoveredButton) main.gameContent.sounds.getSample("hoverBeep").play()
        hoveredButton = hovered
        confirmation?.let {
            it.centre(container.width, container.height)
            it.updateUI(mouseX, mouseY)
        }

        // Play the title music. Switching is a one-off, but the streaming
        // refills need pumping every frame like InGameState does.
        val sounds = main.gameContent.sounds
        if (!musicStarted) {
            musicStarted = true
            val title=sounds.getTrack("title")
            if((sounds as? RealSoundManager)?.currentMusic !== title) sounds.switchMusicList(listOf(title))
        }
        (sounds as? RealSoundManager)?.let {
            it.updateLoopedSounds(false)
            it.updateMusic(delta)
        }
    }

    override fun render(container: GameContainer, g: Graphics) {
        // Under a ConfirmWindow the menu is drawn at the 0.15 focus tint, with the
        // button that opened it left highlighted.
        val confirming = confirmation != null
        if (confirming) Graphics.tint = PlayerShipUI.FOCUS_TINT
        background.draw(0f, 0f)
        val alpha = (1.5f - kotlin.math.abs(logoTime - 1.5f)) / 1.5f
        logo.draw(LOGO_X.f, LOGO_Y.f, xyz.znix.xftl.rendering.Colour(1f, 1f, 1f, alpha))

        for (button in buttons) {
            button.draw(if (confirming) button === confirmedButton else button.action != null && button.contains(mouseX, mouseY))
        }
        promotion.getSubImage((promotionTime/0.3f).toInt().coerceAtMost(3)*80,0,80,68).draw(25,610)
        if (confirming) Graphics.tint = xyz.znix.xftl.rendering.Colour.white
        welcome?.draw(g)
        confirmation?.draw(g)
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        confirmation?.let { it.mouseClick(button, x, y); return }
        welcome?.let { if (button == Input.MOUSE_LEFT_BUTTON && it.click(x, y) != null) welcome = null; return }
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        for (b in buttons) {
            if (b.contains(x, y) && b.action != null) {
                b.action.invoke()
                return
            }
        }
    }

    override fun keyPressed(key: Int, c: Char) {
        welcome?.let { if (it.key(c) != null) welcome = null; return }
        confirmation?.onTextInput(key, c)
    }

    private fun newGameClicked() {
        if (!main.hasRunSave()) { main.switchToShipSelect(); return }
        confirmedButton = hoveredButton
        confirmation = ConfirmationWindow(SettingsContext(main, main.gameContainer.input),
            "confirm_new_game", 380, "button_confirm", "button_cancel", accept = {
                confirmation = null
                main.discardSavedRun()
                main.switchToShipSelect()
            }, cancel = { confirmation = null }).also {
                it.centre(main.gameContainer.width, main.gameContainer.height)
            }
        ftl.browser.BrowserDebug.event("ui", "confirmation.open", "message", "confirm_new_game")
    }

    private fun makeButton(name: String, y: Int, action: (() -> Unit)? = null): MenuButton {
        // The buttons are right-aligned, anchored by the on-state texture width like vanilla (0x004ca260).
        val onImage = getImg("img/main_menus/${name}_on.png")
        val image = if (action == null) getImg("img/main_menus/${name}_off.png") else onImage
        val hovered = getImg("img/main_menus/${name}_select2.png")
        return MenuButton(image, hovered, buttonsRightX - onImage.width, y, action)
    }

    private fun getImg(path: String): Image {
        images[path]?.let { return it }

        val file = datafile.getOrNull(path)
            ?: error("Missing main menu image '$path'")
        val img = datafile.readImage(resourceContext, file)
        images[path] = img
        return img
    }

    private class MenuButton(
        val image: Image,
        val hovered: Image,
        val x: Int,
        val y: Int,
        val action: (() -> Unit)?,
        /** Menu items have a hit box 2px inside their art vertically; the banner has none. */
        private val hitInset: Int = 2
    ) {
        fun contains(px: Int, py: Int): Boolean {
            return px in x until x + image.width && py in y + hitInset until y + image.height - hitInset
        }

        fun draw(highlighted: Boolean) {
            val img = if (highlighted) hovered else image
            img.draw(x.f, y.f)
        }
    }

    companion object {
        private const val LOGO_X = 820
        private const val LOGO_Y = 60
    }
}
