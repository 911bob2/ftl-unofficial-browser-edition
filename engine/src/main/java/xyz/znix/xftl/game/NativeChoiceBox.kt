package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.rendering.WindowRenderer

/**
 * The desktop ChoiceBox (OnRender 0x004fc0f0) for text-and-choice dialogs
 * without resources, as used by the title welcome and ship-list tutorial.
 *
 * Read from live native objects: centred boxes use font 12 and a 30px gap; the
 * frame is WindowFrame(7,22,602,h) drawn from (327,125), 150px further left when
 * not centred, and h is max(377, 32 per choice + 88). The main text is printed
 * from (+25,+45) wrapped at 565. Each choice row starts below the previous one,
 * prints "N. text" 10px into the row, and is its text height + 12 tall and its
 * text width + 10 wide.
 */
class NativeChoiceBox(
    fonts: (String) -> SILFontLoader,
    private val windowRenderer: WindowRenderer,
    var text: String,
    var choices: List<Choice>,
    private val centered: Boolean = true
) {
    /** [type]: 0 ordinary, 1 unavailable, 2 blue (ChoiceText::type). */
    class Choice(val text: String, val type: Int = 0)

    private val font = NativeFonts.font(fonts, FONT)
    private val rows = ArrayList<IntArray>()
    var hovered = -1
        private set

    private val originX get() = if (centered) 327 else 177
    val frameX get() = originX + 7
    val frameY get() = ORIGIN_Y + 22
    val frameHeight get() = maxOf(377, (if (TouchMode.enabled) 49 else 32) * choices.size + 88)

    fun draw(g: Graphics) {
        windowRenderer.render(frameX, frameY, WIDTH, frameHeight)
        val x = originX + 25
        val lines = font.font.wrapString(text, WRAP)
        printLines(lines, x, ORIGIN_Y + 45, Colour.white)

        var rowY = ORIGIN_Y + 45 + lines.size * font.lineHeight + GAP
        rows.clear()
        if (TouchMode.enabled) {
            drawTouchRows(g, x + 10, rowY)
            return
        }
        choices.forEachIndexed { i, choice ->
            val label = lines(choice, i)
            val colour = when {
                choice.type == 1 -> DISABLED
                hovered == i -> Constants.TEXT_OPTION_HOVER
                choice.type == 2 -> Constants.TEXT_OPTION_BLUE
                else -> Colour.white
            }
            printLines(label, x, rowY + 10, colour)
            val width = label.maxOf { font.width(it) } + 10
            val height = label.size * font.lineHeight + 12
            rows += intArrayOf(x, rowY, width, height)
            rowY += height
        }
    }

    /** The touch layout's boxed rows: the text wrapped at 540, a box around it, 15 px down to the next row's start. */
    private fun drawTouchRows(g: Graphics, x: Int, firstRowY: Int) {
        var rowY = firstRowY
        choices.forEachIndexed { i, choice ->
            val label = font.font.wrapString("${i + 1}. ${choice.text}", TOUCH_WRAP)
            val colour = when {
                choice.type == 1 -> TOUCH_DISABLED
                hovered == i -> Constants.TEXT_OPTION_HOVER
                choice.type == 2 -> Constants.TEXT_OPTION_BLUE
                else -> Colour.white
            }
            val top = rowY + 10
            val width = label.maxOf { font.width(it) } + 14
            val height = label.size * font.lineHeight + 2
            val boxX = x - 7

            g.colour = if (choice.type == 1) Colour(0f, 0f, 0f, 0.25f) else Colour(0f, 0f, 0f, 0.5f)
            g.fillRect(boxX.f, top.f, width.f, height.f)
            g.colour = colour
            g.fillRect(boxX.f, top.f, width.f, 2f)
            g.fillRect(boxX.f, top + height - 2f, width.f, 2f)
            g.fillRect(boxX.f, top.f, 2f, height.f)
            g.fillRect(boxX + width - 2f, top.f, 2f, height.f)

            printLines(label, x, top, colour)
            rows += intArrayOf(boxX, top, width, height)
            rowY = top + height + 15
        }
    }

    private fun lines(choice: Choice, index: Int) = font.font.wrapString("${index + 1}. ${choice.text}", WRAP)

    private fun printLines(lines: List<String>, x: Int, top: Int, colour: Colour) {
        lines.forEachIndexed { i, line -> font.print(x.f, (top + i * font.lineHeight).f, line, colour) }
    }

    /** ChoiceBox::MouseMove: the row under the pointer, or -1. */
    fun mouseMove(x: Int, y: Int) {
        hovered = rowAt(x, y)
    }

    /** The row at ([x], [y]), or -1. */
    fun rowAt(x: Int, y: Int): Int {
        if (TouchMode.enabled) {
            // Touch bands: half of the gap between rows each way, 10 px past the box at the sides and the ends.
            var at = -1
            rows.forEachIndexed { i, r ->
                val above = if (i == 0) 10 else GAP / 2
                val below = if (i == rows.lastIndex) 10 else GAP / 2
                if (choices.getOrNull(i)?.type != 1 && x >= r[0] - 10 && x <= r[0] + r[2] + 10 && y >= r[1] - above && y <= r[1] + r[3] + below)
                    at = i
            }
            return at
        }
        return rows.indexOfFirst { x >= it[0] && x < it[0] + it[2] && y >= it[1] && y < it[1] + it[3] }
    }

    /** What a finger taps at ([x], [y]) (TouchRouter.LiftTargets): an available choice's index, or null. */
    fun liftTarget(x: Int, y: Int): Int? = rowAt(x, y).takeIf { it >= 0 && choices[it].type != 1 }

    /** The chosen index, if the click lands on an available choice. */
    fun click(x: Int, y: Int): Int? {
        mouseMove(x, y)
        return hovered.takeIf { it >= 0 && choices[it].type != 1 }
    }

    /** Number keys choose directly, like the native dialog keys. */
    fun key(c: Char): Int? {
        val index = c - '1'
        return index.takeIf { it in choices.indices && choices[it].type != 1 }
    }

    companion object {
        const val FONT = 12
        const val WIDTH = 602
        const val ORIGIN_Y = 125
        const val WRAP = 565
        const val GAP = 30
        private val DISABLED = Colour(150, 150, 150)
        private const val TOUCH_WRAP = 540
        private val TOUCH_DISABLED = Colour(243 * 3 / 4, 255 * 3 / 4, 80 * 3 / 4, 127)
    }
}
