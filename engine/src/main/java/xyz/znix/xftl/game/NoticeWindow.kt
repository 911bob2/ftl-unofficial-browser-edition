package xyz.znix.xftl.game

import ftl.browser.BrowserDebug
import xyz.znix.xftl.Constants
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input

/** Informational modal; acknowledgment never changes profile/gameplay settings. */
class NoticeWindow(private val context: SettingsContext, val title: String,
                   val message: String, private val close: () -> Unit) : Window() {
    override val size: IPoint = ConstPoint(430, 170)
    override val hasCloseControl = true
    private val titleFont = context.getFont("JustinFont12Bold")
    private val bodyFont = context.getFont("JustinFont11Bold")
    private var resolved = false

    val diagnosticState: String get() = "{\"title\":${BrowserDebug.quote(title)},\"message\":${BrowserDebug.quote(message)}}"

    override fun draw(g: Graphics) {
        context.getWindowRenderer().render(position.x, position.y, size.x, size.y)
        titleFont.drawStringCentred(position.x + 20f, position.y + 38f,
            size.x - 40f, title, Constants.JUMP_DISABLED_TEXT)
        bodyFont.drawStringCentred(position.x + 20f, position.y + 83f,
            size.x - 40f, message, Constants.JUMP_DISABLED_TEXT)
        drawCloseControl(g, titleFont)
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON && closeControlHit(x, y)) escapePressed()
    }

    override fun escapePressed() {
        if (resolved) return
        resolved = true
        BrowserDebug.event("ui", "notice.close", "title", title)
        close()
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        if (key in listOf(Input.KEY_ESCAPE, Input.KEY_ENTER, Input.KEY_SPACE)) escapePressed()
        return true
    }
}
