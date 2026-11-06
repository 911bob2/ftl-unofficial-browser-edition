// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/ScrollingChoiceBox.cpp
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.Utils
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image

/**
 * The event box scrolls when its choices run past y 516 (ChoiceBox::lastChoice.y - 516): the text and choices are
 * drawn moved up by the scroll amount and clipped to the frame (WindowFrame::Draw's stencil), the
 * `statusUI/button_crew_up/down` buttons sit at (919, 158) and (919, 499) (150 px left when the box is not centred)
 * with a 10 px bar at x 920 from y 173, 325 px tall for the whole content (the TextButton active colour, the selected
 * one while hovered or dragged). The wheel scrolls 50 px a notch, the buttons 50, a click on the bar track a page
 * (369); the bar drags. Choices only answer between y 147 and 524, at the scrolled position.
 */
object HsScrollingChoiceBox {
    private const val BUTTON_X = 919
    private const val BUTTON_Y1 = 158
    private const val BUTTON_Y2 = 499
    private const val BAR_HEIGHT = 325
    private const val BAR_X = 920
    private const val BAR_Y1 = 173
    private const val BAR_Y2 = 498
    private const val PAGE = 369f

    @JvmField var scroll = 0f
    private var dragging = -1
    private var barSelected = false
    private var upHover = false
    private var downHover = false
    /** ChoiceBox::lastChoice.y of the last frame drawn. */
    @JvmField var lastChoiceY = 0f
    private var centered = true

    // The touch layout's frame sits elsewhere and can be taller than the desktop's 377 (touchExtra).
    private var touchExtra = 0
    private fun touchDy() = if (TouchMode.enabled) 44 else 0
    private fun touchDx() = if (TouchMode.enabled) TouchMode.hudX - 128 else 0

    @JvmStatic fun maxScroll(): Float = lastChoiceY - 516f - touchDy() - touchExtra

    private fun clamp() { scroll = scroll.coerceAtMost(maxScroll()).coerceAtLeast(0f) }

    /** WorldManager::CreateChoiceBox. */
    @JvmStatic fun reset() { scroll = 0f; dragging = -1; lastChoiceY = 0f }

    private fun dx() = (if (centered) 0 else -150) + touchDx()

    private class Bar(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun bar(): Bar {
        val top = scroll
        val height = maxScroll() + PAGE
        val y = (BAR_Y1 + touchDy() + BAR_HEIGHT * (top / height)).toInt()
        val bottom = (BAR_Y1 + touchDy() + BAR_HEIGHT * ((top + PAGE) / height)).toInt()
        return Bar(BAR_X + dx(), y, 10, bottom - y)
    }

    /**
     * ChoiceBox::OnRender with the scroll: [frame] draws the box frame, [content] the text and choices (returning
     * lastChoice.y); [frameX], [frameY], [frameW], [frameH] is the frame for the clip.
     */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, centered: Boolean, frameX: Int, frameY: Int, frameW: Int, frameH: Int,
               content: () -> Float) {
        this.centered = centered
        touchExtra = if (TouchMode.enabled) frameH - 377 else 0
        clamp()
        if (lastChoiceY > 0f && maxScroll() <= 0f && scroll == 0f) {
            lastChoiceY = content()
        } else {
            Utils.drawStenciled(Utils.StencilMode.MASKING, {
                g.fillRect(frameX.toFloat(), frameY.toFloat(), frameW.toFloat(), frameH.toFloat())
            }) {
                g.pushTransform()
                g.translate(0f, -scroll)
                lastChoiceY = content()
                g.popTransform()
            }
        }
        if (maxScroll() > 0f) {
            image(game, "img/statusUI/button_crew_up", upHover).draw(BUTTON_X + dx(), BUTTON_Y1 + touchDy())
            image(game, "img/statusUI/button_crew_down", downHover).draw(BUTTON_X + dx(), BUTTON_Y2 + touchDy())
            val b = bar()
            g.colour = if (barSelected) HsColors.tbSelected() else HsColors.tbActive()
            g.fillRect(b.x.toFloat(), b.y.toFloat(), b.w.toFloat(), b.h.toFloat())
        }
    }

    private fun image(game: InGameState, base: String, hover: Boolean): Image =
        game.getImg(if (hover) "${base}_select2.png" else "${base}_on.png")

    /** The scroll button at ([x], [y]): 1 up, 2 down, 0 none. */
    private fun buttonAt(game: InGameState, x: Int, y: Int): Int {
        if (maxScroll() <= 0f) return 0
        val up = game.getImg("img/statusUI/button_crew_up_on.png")
        if (x < BUTTON_X + dx() || x >= BUTTON_X + dx() + up.width) return 0
        if (y >= BUTTON_Y1 + touchDy() && y < BUTTON_Y1 + touchDy() + up.height) return 1
        if (y >= BUTTON_Y2 + touchDy() && y < BUTTON_Y2 + touchDy() + up.height) return 2
        return 0
    }

    /** The y the choices see at [y] (the scrolled position), or null outside the box. */
    @JvmStatic
    fun choiceY(y: Int): Int? = if (y in 147 + touchDy()..524 + touchDy() + touchExtra) y + scroll.toInt() else null

    /** What a finger taps (TouchRouter): a scroll button, or null (the bar and the rest take the press). */
    @JvmStatic
    fun liftTarget(game: InGameState, x: Int, y: Int): Any? = buttonAt(game, x, y).takeIf { it != 0 }?.let { "scroll$it" }

    /** ChoiceBox::MouseMove: the y the choices see, or null when outside 147..524. Also the buttons and the bar. */
    @JvmStatic
    fun mouseMove(game: InGameState, x: Int, y: Int): Int? {
        val can = maxScroll() > 0f
        if (can) {
            val b = buttonAt(game, x, y)
            upHover = b == 1
            downHover = b == 2
        } else {
            upHover = false; downHover = false; barSelected = false
        }
        if (dragging != -1) {
            scroll = (maxScroll() + PAGE) * (y - dragging - BAR_Y1 - touchDy()) / BAR_HEIGHT
            clamp()
        } else if (can) {
            val b = bar()
            barSelected = x >= b.x - 1 && x < b.x + b.w + 1 && y >= b.y && y < b.y + b.h
        }
        return choiceY(y)
    }

    /** ChoiceBox::MouseClick (after the original). */
    @JvmStatic
    fun mouseClick(x: Int, y: Int) {
        when {
            upHover -> { scroll -= 50f; clamp() }
            downHover -> { scroll += 50f; clamp() }
            else -> {
                val b = bar()
                if (x >= b.x - 1 && x < b.x + b.w + 1) {
                    when {
                        y >= b.y && y < b.y + b.h -> { dragging = y - b.y; barSelected = true }
                        y >= BAR_Y1 + touchDy() - 1 && y < b.y -> { scroll -= PAGE; clamp() }
                        y <= BAR_Y2 + touchDy() -> { scroll += PAGE; clamp() }
                    }
                }
            }
        }
    }

    /** CommandGui::LButtonUp. */
    @JvmStatic fun mouseUp() { dragging = -1 }

    /** ChoiceBox open: the wheel scrolls 50 px a notch (down = forward). */
    @JvmStatic
    fun wheel(change: Int) {
        if (change == 0) return
        scroll += if (change > 0) -50f else 50f
        clamp()
    }
}
