package xyz.znix.xftl.game

import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * The title menu's options share the windows used during a run. Like MainMenu with
 * its OptionsScreen open, the menu stays drawn behind them at the 0.15 focus tint.
 */
class SettingsMenuState(
    private val main: MainGame, private val returnToMenu: () -> Unit,
    private val background: MainGame.GameState? = null
) : MainGame.GameState() {
    private lateinit var context: SettingsContext
    private lateinit var window: Window
    private var lastTooltip: xyz.znix.xftl.rendering.ITooltipProvider? = null
    private val diagnosticActiveWindow get() = (window as? OptionsWindow)?.diagnosticActiveWindow ?: window
    val diagnosticNotice get() = (window as? OptionsWindow)?.diagnosticNotice ?: "null"
    val diagnosticWindow get() = diagnosticActiveWindow.javaClass.simpleName
    val diagnosticWindowRect get() = "{\"x\":${diagnosticActiveWindow.position.x},\"y\":${diagnosticActiveWindow.position.y},\"width\":${diagnosticActiveWindow.size.x},\"height\":${diagnosticActiveWindow.size.y}}"

    override fun init(container: GameContainer) {
        context = SettingsContext(main, container.input)
        showOptions()
    }

    private fun showOptions() {
        window = OptionsWindow(context, returnToMenu, {
            window = HotkeysWindow(context, this::showOptions)
        }, titleMenu = true)
    }

    override fun shutdown() { background?.shutdown() }
    fun prepareBrowserRender(delta: Float) { context.renderingDeltaTime = delta }
    override fun update(container: GameContainer, delta: Float) {
        context.renderingDeltaTime = delta
        (main.gameContent.sounds as? RealSoundManager)?.let {
            it.updateLoopedSounds(false)
            it.updateMusic(delta)
        }
        window.position = ConstPoint((container.width - window.size.x) / 2, (container.height - window.size.y) / 2) +
                window.windowCentreOffset
        window.updateUI(container.input.mouseX, container.input.mouseY)
    }

    override fun render(container: GameContainer, g: Graphics) {
        if (background != null) {
            Graphics.tint = PlayerShipUI.FOCUS_TINT
            background.render(container, g)
            Graphics.tint = Colour.white
        } else {
            context.getImg("img/main_menus/main_base2.png").draw(0f, 0f)
            g.colour = Colour(0f, 0f, 0f, .65f)
            g.fillRect(0, 0, container.width, container.height)
        }
        g.tooltip = null
        window.draw(g)
        g.tooltip?.drawTooltip(g, container.input.mouseX, container.input.mouseY,
            g.tooltip !== lastTooltip, container.width, container.height)
        lastTooltip = g.tooltip
    }

    override fun mousePressed(button: Int, x: Int, y: Int) { window.mouseClick(button, x, y) }
    override fun mouseReleased(button: Int, x: Int, y: Int) { window.mouseReleased(button, x, y) }
    override fun mouseWheelMoved(change: Int) { window.mouseScroll(change) }
    override fun keyPressed(key: Int, c: Char) {
        if (window.onTextInput(key, c)) return
        if (key == Input.KEY_ESCAPE) window.escapePressed()
    }
}
