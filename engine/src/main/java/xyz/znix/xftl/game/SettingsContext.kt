package xyz.znix.xftl.game

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.WindowRenderer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.ui.UIProvider
import xyz.znix.xftl.ui.Widget

/** Settings use shared content and the profile, without constructing a run. */
class SettingsContext(val mainGame: MainGame, val input: Input, private val bindingsChanged: () -> Unit = {}) : UIProvider {
    constructor(game: InGameState) : this(game.mainGame, game.input, game::updateHotkeyBindings) { run = game }
    private var run: InGameState? = null

    val translator get() = mainGame.gameContent.translator
    val hotkeyManager get() = mainGame.gameContent.hotkeyManager
    val reverseHotkeyBindings = HashMap<Hotkey, HotkeyButton>()
    var renderingDeltaTime = 0f
        get() = run?.renderingDeltaTime ?: field

    fun updateHotkeyBindings() {
        hotkeyManager.calculateBindings(mainGame.profile, HashMap(), reverseHotkeyBindings)
        bindingsChanged()
    }

    override fun getFont(name: String): SILFontLoader = mainGame.gameContent.getFont(name)
    fun getFont(name: String, scale: Float): SILFontLoader = getFont(name).also { it.scale = scale }
    override fun getImg(path: String): Image = mainGame.gameContent.getImg(path)
    override fun translate(key: String): String? = translator.translations[key]
    override fun getDebugOutlineColour(widget: Widget): Colour? = null
    override fun getWindowRenderer(): WindowRenderer = mainGame.gameContent.windowRenderer
}
