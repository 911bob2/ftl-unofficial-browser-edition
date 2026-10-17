package xyz.znix.xftl.rendering

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState

/**
 * Represents something that can draw a tooltip.
 *
 * This is part of the rendering API, since it's convenient to track what
 * the cursor is on top of there.
 */
interface ITooltipProvider {
    fun drawTooltip(
        g: Graphics,
        mouseX: Int, mouseY: Int,
        firstFrame: Boolean,
        screenWidth: Int, screenHeight: Int
    )
}

abstract class StandardTooltip(private val font: SILFontLoader) : ITooltipProvider {
    constructor(game: InGameState) : this(game.getFont("JustinFont11Bold"))
    protected open val customMaxWidth: Int? = null

    abstract fun getText(): String

    override fun drawTooltip(
        g: Graphics,
        mouseX: Int, mouseY: Int,
        firstFrame: Boolean,
        screenWidth: Int, screenHeight: Int
    ) {
        NativeTooltip.draw(g, null, font, mouseX, mouseY, null, getText(), customMaxWidth)
    }
}

/**
 * A tooltip that only shows up if the user stops moving the mouse for a period.
 *
 * TODO: Don't use InGameState
 */
abstract class DelayedTooltip(font: SILFontLoader, private val deltaTime: () -> Float) : StandardTooltip(font) {
    constructor(game: InGameState) : this(game.getFont("JustinFont11Bold"), { game.renderingDeltaTime })
    open val delayPeriod: Float = NativeTooltip.DELAY

    private var showing: Boolean = false
    private var timer: Float = 0f
    private var lastX: Int = 0
    private var lastY: Int = 0

    override fun drawTooltip(
        g: Graphics,
        mouseX: Int, mouseY: Int,
        firstFrame: Boolean,
        screenWidth: Int, screenHeight: Int
    ) {
        // If the tooltip was un-hovered, reset the timer before it shows up again.
        if (firstFrame) {
            showing = false
            timer = 0f
        }

        // Implement the delay before the tooltip appears
        if (!showing) {
            timer += deltaTime()

            if (mouseX != lastX || mouseY != lastY) {
                timer = 0f
            } else if (timer >= delayPeriod) {
                showing = true
            }
            lastX = mouseX
            lastY = mouseY
            return
        }

        super.drawTooltip(g, mouseX, mouseY, false, screenWidth, screenHeight)
    }
}

/**
 * A [DelayedTooltip] instance that shows a translated string.
 */
class FixedDelayedTooltip(private val game: InGameState, val textKey: String) : DelayedTooltip(game) {
    override fun getText(): String {
        return game.translator[textKey]
    }
}

/**
 * A [StandardTooltip] instance that shows a translated string.
 */
class FixedStandardTooltip(private val game: InGameState, val textKey: String) : StandardTooltip(game) {
    override fun getText(): String {
        return game.translator[textKey]
    }
}

/**
 * Similar to [FixedDelayedTooltip], this shows a translated string but with
 * the key bound to [hotkeyId] inserted in place of the '\1' template.
 */
class HotkeyDelayedTooltip(private val game: InGameState, val textKey: String, val hotkeyId: String?) : DelayedTooltip(game) {
    override fun getText(): String {
        return game.translator[textKey].replace("\\1", game.getButtonNameForHotkey(hotkeyId, ""))
    }
}

/**
 * MouseControl::RenderTooltip (0x004e5ed0). The box hangs from the mouse plus
 * (0,22) unless placed statically, wraps at 350 with a title or 275 without, and
 * is the text plus (27,18), 5 more with a title. A 0.95-black fill is outlined
 * by a 2px white border inside the box. The title uses font 13 from +13, the
 * body font 12 from +11, below the title block plus 5.
 */
object NativeTooltip {
    /** MouseControl shows a tooltip once the hover has lasted this long. */
    const val DELAY = 0.15f

    fun draw(
        g: Graphics, titleFont: SILFontLoader?, bodyFont: SILFontLoader,
        mouseX: Int, mouseY: Int, title: String?, body: String, customWidth: Int? = null,
        anchorX: Int = mouseX, anchorY: Int = mouseY + 22
    ) {
        val titleSpec = NativeFonts.spec(13)
        val bodySpec = NativeFonts.spec(12)
        val wrap = customWidth?.takeIf { it > 0 } ?: if (title != null) 350 else 275
        val titleLines = if (title != null && titleFont != null) titleFont.wrapString(title, wrap) else emptyList()
        val bodyLines = bodyFont.wrapString(body, wrap)
        val textWidth = maxOf(titleLines.maxOfOrNull { titleFont!!.getWidth(it) } ?: 0, bodyLines.maxOfOrNull(bodyFont::getWidth) ?: 0)
        val titleHeight = titleLines.size * titleSpec.lineHeight
        val width = textWidth + 27
        val height = titleHeight + bodyLines.size * bodySpec.lineHeight + 18 + if (titleLines.isEmpty()) 0 else 5

        // Edges are tested against the mouse, then the box is moved.
        var x = anchorX
        var y = anchorY
        if (mouseY + height > 670) {
            x += 16
            y -= mouseY + height - 670
            if (mouseY < y + height) y -= y + height - mouseY + 10
        }
        if (mouseX + width > 1260) x -= mouseX + width - 1240

        g.colour = Colour(0f, 0f, 0f, 0.95f)
        g.fillRect(x, y, width, height)
        g.colour = Colour.white
        g.fillRect(x, y, width, 2)
        g.fillRect(x, y + height - 2, width, 2)
        g.fillRect(x, y + 2, 2, height - 4)
        g.fillRect(x + width - 2, y + 2, 2, height - 4)

        var top = y + 13
        for (line in titleLines) {
            titleFont!!.drawString(x + 15f, (top + titleSpec.baseline).f, line, Colour.white)
            top += titleSpec.lineHeight
        }
        top = y + 11 + if (titleLines.isEmpty()) 0 else titleHeight + 5
        for (line in bodyLines) {
            bodyFont.drawString(x + 15f, (top + bodySpec.baseline).f, line, Colour.white)
            top += bodySpec.lineHeight
        }
    }
}

object TooltipConstants {
    const val LINE_SPACING = 20 // px between lines
    const val BOX_TOP_OFFSET = 23
    const val BOX_BOTTOM_OFFSET = 15
    const val BOX_LEFT_OFFSET = 15
    const val BOX_RIGHT_OFFSET = 12

    fun widthOf(lines: List<String>, font: SILFontLoader): Int {
        return BOX_LEFT_OFFSET + lines.maxOf(font::getWidth) + BOX_RIGHT_OFFSET
    }

    fun heightOf(lines: List<String>): Int {
        return BOX_TOP_OFFSET + (lines.size - 1) * LINE_SPACING + BOX_BOTTOM_OFFSET
    }

    fun drawTooltip(g: Graphics, font: SILFontLoader, x: Int, y: Int, width: Int, height: Int, lines: List<String>) {
        // Same box as NativeTooltip, for statically placed tooltips.
        g.colour = Colour(0f, 0f, 0f, 0.95f)
        g.fillRect(x, y, width, height)
        g.colour = Colour.white
        g.fillRect(x, y, width, 2)
        g.fillRect(x, y + height - 2, width, 2)
        g.fillRect(x, y + 2, 2, height - 4)
        g.fillRect(x + width - 2, y + 2, 2, height - 4)

        for ((i, line) in lines.withIndex()) {
            font.drawString(x.f + BOX_LEFT_OFFSET, y.f + BOX_TOP_OFFSET + i * LINE_SPACING, line, Colour.white)
        }
    }
}
