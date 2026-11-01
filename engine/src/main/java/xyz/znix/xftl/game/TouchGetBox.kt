package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input

/**
 * The touch layout's purchase box under the info box: the price in a scrap box with a text button to its right. Tapping
 * an item only selects it; this button is what spends the scrap (the store's BUY, the upgrades' level, the sell tab's SELL).
 *
 * It moves with the info box's height, so it is placed when drawn and is only hit once it has been drawn.
 */
class TouchGetBox(
    game: InGameState, label: String,
    private val price: () -> Int?,
    private val active: () -> Boolean,
    action: () -> Unit
) : Buttons.BasicButton(
    game, ConstPoint.ZERO, ConstPoint(widthFor(game, label), BUTTON_H), label,
    3, game.getFont("HL2", 3f), 25, action
) {
    override val nativeFont = NativeFonts.font(game::getFont, 63)
    override val disabled: Boolean get() = !active()

    private val priceFont = NativeFonts.font(game::getFont, 0)
    private val scrapBox = game.getImgIfExists("img/ipad/upgradeUI/get_box_scrap.png")
    private val buttonBase = game.getImgIfExists("img/ipad/upgradeUI/get_box_button.png")

    /** Drawn last frame, so a press can hit it. */
    private var shown = false

    fun hide() {
        shown = false
    }

    /** Draws the box below everything [panel] drew this frame. */
    fun drawBelow(g: Graphics, panel: InfoPanel) {
        val x = panel.position.x - 3 - (size.x + 96) / 2 + 249
        val y = maxOf(panel.touchBottom, panel.position.y) + 3
        if (windowOffset.x != x || windowOffset.y != y) windowOffset = ConstPoint(x, y)
        shown = true

        if (scrapBox != null) {
            scrapBox.draw(x - 79, y - 2)
        } else {
            g.colour = Colour(0f, 0f, 0f, 0.5f)
            g.fillRect(x - 79f, y - 2f, 82f, 45f)
        }
        val priceText = price()?.takeIf { it != Int.MAX_VALUE }?.toString() ?: "--"
        priceFont.printCenter(x - 25f, y + 13f, priceText, Constants.SECTOR_CUTOUT_TEXT)

        if (buttonBase != null) {
            // The art's right half is the box's rounded end; its left half stretches under the text.
            val capW = buttonBase.width / 2
            val left = x + 3
            val capX = left + size.x + 10 - capW
            val top = y - 2f
            val bottom = top + buttonBase.height
            buttonBase.draw(left.f, top, capX.f, bottom, 0f, 0f, capW.f, buttonBase.height.f)
            buttonBase.draw(capX.f, top, capX.f + capW, bottom, capW.f, 0f, buttonBase.width.f, buttonBase.height.f)
        }
        draw(g)
    }

    /** The finger came down: true if it hit the active button, which has then acted. */
    fun press(x: Int, y: Int): Boolean {
        if (!shown || disabled || !contains(x, y)) return false
        mouseDown(Input.MOUSE_LEFT_BUTTON, x, y)
        return true
    }

    fun updateHover(x: Int, y: Int) {
        if (shown) update(x, y) else update(-1, -1)
    }

    companion object {
        private const val BUTTON_H = 34

        private fun widthFor(game: InGameState, label: String) =
            maxOf(NativeFonts.font(game::getFont, 63).measure(label) + 6, 77)
    }
}
