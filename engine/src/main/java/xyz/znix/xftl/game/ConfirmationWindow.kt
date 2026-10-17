package xyz.znix.xftl.game

import ftl.browser.BrowserDebug
import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input
import kotlin.math.max

/**
 * The desktop ConfirmWindow. UpdateSize (0x0055b6a0) sizes it to its font 62
 * YES/NO TextButtons (label + 6 wide, 24 tall) and the font 52 message wrapped at
 * width - 40; the render (0x0055bc60) slices confirm_window.png around the text and
 * the two button wells, prints the message centred from y + 17, and SetPosition
 * (0x0055cc30) centres the buttons 14px apart, 28px below the text.
 */
class ConfirmationWindow(
    private val context: SettingsContext,
    val messageKey: String,
    minimumWidth: Int = 300,
    yesKey: String = "confirm_yes",
    noKey: String = "confirm_no",
    private val accept: () -> Unit,
    private val cancel: () -> Unit
) : Window() {
    private val image = context.getImg("img/confirm_window.png")
    private val bodyFont = NativeFonts.font(context::getFont, 52)
    private val buttonFont = NativeFonts.font(context::getFont, 62)
    private val yesLabel = context.translator[yesKey]
    private val noLabel = context.translator[noKey]
    private val yesWidth = buttonFont.measure(yesLabel) + 6
    private val noWidth = buttonFont.measure(noLabel) + 6
    private val boxWidth = max(minimumWidth, yesWidth + noWidth + 100)
    private val lines = context.translator[messageKey].replace("\\n", "\n").split('\n')
        .flatMap { bodyFont.font.wrapString(it, boxWidth - 40) }
    private val bodyHeight = lines.size * bodyFont.lineHeight
    override val size: IPoint = ConstPoint(boxWidth, bodyHeight + image.height - 22)
    private val yesX = boxWidth / 2 - (yesWidth + 14 + noWidth) / 2
    private val noX = yesX + yesWidth + 14
    private val buttonY = bodyHeight + 28
    private var hovered = -1
    private var resolved = false

    /** A window left on screen under the prompt, frozen and tinted 0.5 (MenuScreen). */
    var backdrop: Window? = null
    val yesCentre: IPoint get() = ConstPoint(position.x + yesX + yesWidth / 2, position.y + buttonY + 12)
    val noCentre: IPoint get() = ConstPoint(position.x + noX + noWidth / 2, position.y + buttonY + 12)

    val diagnosticState: String get() = "{\"message\":${BrowserDebug.quote(messageKey)},\"yes\":${bounds(yesX, yesWidth)},\"no\":${bounds(noX, noWidth)}}"
    private fun bounds(x: Int, width: Int) = "{\"x\":${position.x + x},\"y\":${position.y + buttonY},\"width\":$width,\"height\":24}"

    fun centre(width: Int, height: Int) {
        position = ConstPoint((width - size.x) / 2, (height - size.y) / 2)
    }

    override fun draw(g: Graphics) {
        backdrop?.let {
            val tint = Graphics.tint
            Graphics.tint = Colour(tint.r * .5f, tint.g * .5f, tint.b * .5f, tint.a)
            it.draw(g)
            Graphics.tint = tint
        }
        fun piece(x: Int, y: Int, w: Int, h: Int, sx: Int, sy: Int, sw: Int, sh: Int) {
            if (w > 0 && h > 0) image.draw((position.x + x).f, (position.y + y).f,
                (position.x + x + w).f, (position.y + y + h).f, sx.f, sy.f, (sx + sw).f, (sy + sh).f)
        }
        for ((y, h, sy, sh) in listOf(intArrayOf(0, 26, 0, 26),
            intArrayOf(26, bodyHeight - 21, 26, 1), intArrayOf(bodyHeight + 5, 17, 27, 17))) {
            piece(0, y, 70, h, 0, sy, 70, sh)
            piece(70, y, boxWidth - 139, h, 70, sy, 1, sh)
            piece(boxWidth - 69, y, 69, h, 71, sy, 69, sh)
        }
        val y = bodyHeight + 22
        val right = noX + noWidth
        piece(0, y, 20, 46, 0, 44, 20, 46)
        piece(20, y, yesX - 41, 46, 20, 44, 1, 46)
        piece(yesX - 21, y, 31, 46, 21, 44, 31, 46)
        piece(yesX + 10, y, yesWidth - 20, 46, 52, 44, 1, 46)
        piece(yesX + yesWidth - 10, y, 34, 46, 53, 44, 34, 46)
        piece(noX + 10, y, noWidth - 20, 46, 87, 44, 1, 46)
        piece(right - 10, y, 31, 46, 88, 44, 31, 46)
        piece(right + 21, y, boxWidth - right - 41, 46, 119, 44, 1, 46)
        piece(boxWidth - 20, y, 20, 46, 120, 44, 20, 46)
        bodyFont.printCenter((position.x + boxWidth / 2).f, (position.y + 17).f, lines.joinToString("\n"),
            Constants.TEXT_BUTTON_NORMAL)
        for ((index, x, width, label) in listOf(
            ButtonLabel(0, yesX, yesWidth, yesLabel), ButtonLabel(1, noX, noWidth, noLabel))) {
            g.colour = if (hovered == index) Constants.TEXT_BUTTON_HOVER else Constants.TEXT_BUTTON_NORMAL
            Buttons.drawChamfer(g, position.x + x, position.y + buttonY, width, 24, 4)
            buttonFont.printButtonLabel(position.x + x, position.y + buttonY, width, 24, label, Constants.TEXT_BUTTON_TEXT)
        }
    }

    private data class ButtonLabel(val index: Int, val x: Int, val width: Int, val label: String)
    private fun targetAt(x: Int, y: Int): Int = when {
        y !in position.y + buttonY until position.y + buttonY + 24 -> -1
        x in position.x + yesX until position.x + yesX + yesWidth -> 0
        x in position.x + noX until position.x + noX + noWidth -> 1
        else -> -1
    }

    override fun updateUI(x: Int, y: Int) {
        val target = targetAt(x, y)
        if (target >= 0 && target != hovered && !resolved)
            context.mainGame.gameContent.sounds.getSample("hoverBeep").play()
        hovered = target
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON || resolved) return
        when (targetAt(x, y)) { 0 -> resolve(true); 1 -> resolve(false) }
    }

    private fun resolve(accepted: Boolean) {
        if (resolved) return
        resolved = true
        BrowserDebug.record("ui", "confirmation.answer",
            "{\"message\":${BrowserDebug.quote(messageKey)},\"accepted\":$accepted}")
        if (accepted) accept() else cancel()
    }

    override fun escapePressed() = resolve(false)
    override fun onTextInput(key: Int, c: Char): Boolean {
        if (key == Input.KEY_ESCAPE) escapePressed()
        return true // A modal confirmation must consume all gameplay shortcuts.
    }
}
