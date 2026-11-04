package xyz.znix.xftl.game

import xyz.znix.xftl.Languages
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts

/**
 * LanguageChooser, the options screen's language list. LanguageChooser::OnInit places it at
 * (496, 360 - n*37/2) and gives every language of OptionsScreen::languageList a 256x26 TextButton (corner 4, font
 * 62) 17 pixels in, 37 apart, labelled with that language's own `language_name`. OnRender draws
 * optionsUI/language_top (46 tall), _mid (37) and _bottom behind them and switches TextLibrary to each button's
 * language while drawing it, so every name is drawn with its own language's fonts. MouseClick takes
 * an active button under the mouse; OptionsScreen::OnLoop then applies the choice.
 */
class LanguageChooser(
    private val context: SettingsContext,
    private val x: Int = 496,
    centreY: Int = 360,
    private val choose: (String) -> Unit
) {
    private val codes = Languages.list.toList()
    private val labels = codes.map { context.translator.getIn(it, "language_name") }
    private val top = context.getImg("img/optionsUI/language_top.png")
    private val mid = context.getImg("img/optionsUI/language_mid.png")
    private val bottom = context.getImg("img/optionsUI/language_bottom.png")
    private val font = NativeFonts.font(context::getFont, 62)
    private val y = centreY - codes.size * 37 / 2
    private var hovered = -1

    val diagnosticState: String get() = "{\"languages\":${codes.size},\"hovered\":$hovered}"

    private fun buttonAt(mx: Int, my: Int): Int = codes.indices.firstOrNull { i ->
        val bx = x + 17
        val by = y + 17 + i * 37
        mx > bx && mx < bx + 256 && my > by && my < by + 26
    } ?: -1

    fun mouseMove(mx: Int, my: Int) {
        val h = buttonAt(mx, my)
        if (h >= 0 && h != hovered) context.mainGame.gameContent.sounds.getSample("hoverBeep").play()
        hovered = h
    }

    fun mouseClick(mx: Int, my: Int) {
        val i = buttonAt(mx, my)
        if (i >= 0) choose(codes[i])
    }

    fun draw(g: Graphics) {
        val current = Languages.current
        // The frame: the top piece (46 tall), a middle piece per further language (37), the bottom piece last.
        var by = y
        for (i in codes.indices) {
            val bg = when (i) { 0 -> top; codes.size - 1 -> bottom; else -> mid }
            bg.draw(x.f, by.f)
            by += if (i == 0) 46 else 37
        }
        // LanguageChooser::OnRender switches each button's language for its label's glyphs, but the
        // label's line height and offset stay what TextButton::OnInit measured when the buttons were made, in the
        // language the game started in (native ja-chooser against ch2-ja).
        try {
            Languages.select(Languages.atStart)
            val lineHeight = font.lineHeight
            val textOffset = NativeFonts.buttonTextOffset(font.id)
            for (i in codes.indices) {
                Languages.select(codes[i])
                val bx = x + 17
                val bty = y + 17 + i * 37
                g.colour = if (i == hovered) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
                Buttons.drawChamfer(g, bx, bty, 256, 26, 4)
                font.printButtonLabel(bx, bty, 256, 26, labels[i], xyz.znix.xftl.hs.render.HsColors.tbText(), lineHeight, textOffset)
            }
        } finally {
            Languages.select(current)
        }
    }
}

/** What follows a language choice: Settings::language, TextLibrary::SetLanguage and the language's fonts. */
object LanguageSwitch {
    @JvmStatic
    fun apply(main: MainGame, code: String) {
        main.profile.language = code
        main.profile.markDirty()
        Languages.select(code)
        main.gameContent.translator.setLanguage(code)
        // CommandGui::OnLanguageChange: SystemControl lays its boxes out again (the subsystems label's
        // sub_spacing), with the other screens reading the new texts as they draw.
        (main.currentState as? InGameState)?.shipUI?.updateButtons()
        ftl.browser.BrowserDebug.record("ui", "settings.language", "{\"language\":${ftl.browser.BrowserDebug.quote(code)}}")
    }
}

/**
 * The first start: without a chosen language (settings.ini has no `language=`, Settings::languageSet false) CApp
 * opens the LanguageChooser instead of the main menu (CApp::OnExecute). CApp::OnRender draws
 * it on black under "choose_language" (font 12, centred on 640 at 165); once a language is picked CApp::OnLoop
 * runs OnLanguageChange and MainMenu::Open.
 *
 * Automated runs (navigator.webdriver, and the Tests page's temporary runs) stand for the reference captures' setup,
 * whose settings.ini already has `language=en`, so they start at the main menu as before.
 */
class LanguageSelectState(private val main: MainGame) : MainGame.GameState() {
    private lateinit var context: SettingsContext
    private lateinit var chooser: LanguageChooser
    private lateinit var title: xyz.znix.xftl.rendering.NativeFonts.NativeFont

    val diagnosticState: String get() = chooser.diagnosticState

    override fun init(container: xyz.znix.xftl.sys.GameContainer) {
        context = SettingsContext(main, container.input)
        title = NativeFonts.font(context::getFont, 12)
        chooser = LanguageChooser(context) { code ->
            LanguageSwitch.apply(main, code)
            main.switchToMainMenu()
        }
    }

    override fun shutdown() {}
    override fun update(container: xyz.znix.xftl.sys.GameContainer, delta: Float) {}

    override fun render(container: xyz.znix.xftl.sys.GameContainer, g: Graphics) {
        g.colour = xyz.znix.xftl.rendering.Colour.black
        g.fillRect(0, 0, container.width, container.height)
        title.printCenter(640f, 165f, context.translator["choose_language"], xyz.znix.xftl.rendering.Colour.white)
        chooser.draw(g)
    }

    override fun mouseMoved(oldx: Int, oldy: Int, newx: Int, newy: Int) = chooser.mouseMove(newx, newy)

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (button == xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON) chooser.mouseClick(x, y)
    }

    companion object {
        /** Settings::languageSet; the automated runs stand for a settings.ini with a language. */
        @JvmStatic
        fun languageSet(profile: SaveProfile): Boolean = profile.language != null || LanguageJs.automated()

        /** Whether the start opens the language list: no language chosen on this device yet (a device setting, as settings.ini). */
        @JvmStatic
        fun needed(main: MainGame): Boolean = !languageSet(main.profile)
    }
}
