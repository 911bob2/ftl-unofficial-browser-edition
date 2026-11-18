package xyz.znix.xftl.game

import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.roundToInt

/**
 * The one layout rule for boxes of text that sit with a control the player must reach (an item's description, its tip,
 * the crew skills, a system's levels, the touch layout's purchase box under them).
 *
 * A screen says what it stacks from [fit]'s top down to the edge of the screen: text boxes, which may be made lower
 * than their text needs, and fixed parts (a control, a box of fixed art), which never change. Text that fits keeps the
 * height the original gives its box. When the column would pass the edge, the fixed parts keep their size and the text
 * boxes share what is left: a box that needs less than an even share keeps its height, the others split the rest. A box
 * made lower than its text shows the text through a [Scroller]: clipped to the box, moved by a finger's drag or the
 * wheel, with a bar at its right edge while there is more.
 *
 * So a control below the boxes is never covered and never leaves the screen, and every line of text can be reached,
 * whatever a mod's text length, the language or the screen.
 */
object InfoStack {
    /** One part of a column: [natural] is the height it has when nothing limits it. */
    class Part(@JvmField val natural: Int, @JvmField val text: Boolean, @JvmField val gapAbove: Int = 0)

    /** A text box is never made lower than this (its frame, a title line and two lines of text). */
    const val MIN_TEXT = 78

    /** The column stops this far above the screen's edge. */
    const val EDGE = 4

    /**
     * How far inside a box drawn by WindowRenderer (img/window_outline.png: every info box, the hangar's, the reward
     * box, the welcome box) its frame reaches, at the top and at the bottom: the border's 4 rows and the glow inside it
     * (vanilla's and Multiverse's art alike; the glow shows down to row 9). Text that scrolls is cut there.
     */
    const val FRAME_INNER = 10

    /** For the checks: false lays every box out at its own height, as before the rule (the checks must then fail). */
    @JvmStatic
    var enabled = true

    /** For the checks: a screen of this size in place of the real one (width, height), or null. */
    @JvmStatic
    var screenOverride: IntArray? = null

    /** The screen's height in game units (768 on the touch canvas, 720 on the desktop one). */
    @JvmStatic
    fun screenHeight(game: InGameState?): Int {
        screenOverride?.let { return it[1] }
        val container = game?.mainGame?.gameContainer
        return container?.height ?: if (TouchMode.enabled) TouchMode.HUD_H else 720
    }

    /**
     * The heights of [parts] stacked from [top] (each after its gap) so that the last one ends at or above [bottom].
     * Fixed parts keep their height; see the class text for how the text boxes share the rest.
     */
    @JvmStatic
    fun fit(top: Int, bottom: Int, parts: List<Part>): IntArray {
        val out = IntArray(parts.size) { parts[it].natural }
        val total = parts.sumOf { it.natural + it.gapAbove }
        if (top + total <= bottom || !enabled) return out
        var room = bottom - top - parts.sumOf { it.gapAbove + if (it.text) 0 else it.natural }
        // lowest need first: a box under the even share keeps its height and leaves the rest to the others
        val texts = parts.indices.filter { parts[it].text }.sortedBy { parts[it].natural }
        var left = texts.size
        for (i in texts) {
            val share = if (left > 0) room / left else 0
            val h = minOf(parts[i].natural, maxOf(share, minOf(MIN_TEXT, parts[i].natural)))
            out[i] = h
            room -= h
            left--
        }
        return out
    }

    /**
     * The touch layout shows some boxes only while a finger holds what they describe (a hangar button, an event's
     * choice, a tooltip's owner): the box is gone when the finger lifts, so it cannot be dragged itself. There the
     * holding finger's way up or down moves the text, the screen keeps showing what the finger held, and a finger that
     * moved the text is no tap when it lifts.
     */
    class HoldScroll {
        private var lastY = NONE
        private var moved = 0

        /** The finger moved the text since it came down: its lift acts on nothing. */
        val scrolled: Boolean get() = moved > 8

        /**
         * Called every frame (or move) with whether a finger is [down], its [y] and whether the box it shows [scrolls];
         * [scrollBy] gets the finger's way (down the text when it moves up). True while the hold scrolls: the screen
         * then leaves its hover where it was.
         */
        fun update(down: Boolean, y: Int, scrolls: Boolean, scrollBy: (Int) -> Unit): Boolean {
            if (!down) { lastY = NONE; moved = 0; return false }
            if (!scrolls) { lastY = NONE; return false }
            if (lastY != NONE && lastY != y) {
                scrollBy(lastY - y)
                moved += kotlin.math.abs(lastY - y)
            }
            lastY = y
            return true
        }

        private companion object { const val NONE = Int.MIN_VALUE }
    }

    /**
     * The text of one box: [content] high, shown through a window [view] high. Holds how far it is moved and draws the
     * bar. A screen keeps one per box and gives it presses, moves and the wheel.
     */
    class Scroller {
        /** How far the text is moved up, 0..[max]. */
        var offset = 0
            private set
        var content = 0
            private set
        var view = 0
            private set
        /** The box on the screen as last drawn (x, y, width, height), in the coordinates it was drawn in. */
        @JvmField val box = IntArray(4)
        /** Where the text is cut (x, y, width, height), same coordinates: inside the frame's inner edge. */
        @JvmField val clip = IntArray(4)
        private var key: Any? = null
        private var dragFrom = Int.MIN_VALUE
        private var dragOffset = 0

        val max: Int get() = maxOf(0, content - view)
        val active: Boolean get() = max > 0

        /**
         * Called when the box is drawn: [key] names the text (another text starts at its top again). The text is cut to
         * the rows [clipTop] until [clipBottom] of the box's width: the inside of its frame.
         */
        fun begin(key: Any?, x: Int, y: Int, width: Int, height: Int, content: Int, view: Int, clipTop: Int, clipBottom: Int) {
            if (this.key != key) { this.key = key; offset = 0; dragFrom = Int.MIN_VALUE }
            box[0] = x; box[1] = y; box[2] = width; box[3] = height
            clip[0] = x; clip[1] = clipTop; clip[2] = width; clip[3] = clipBottom - clipTop
            this.content = content
            this.view = view
            offset = offset.coerceIn(0, max)
        }

        /** Runs [fn] (it draws the text) cut to [clip]. */
        fun clipped(g: Graphics, fn: () -> Unit) = g.clipped(clip[0], clip[1], clip[2], clip[3], fn)

        /** Not drawn this frame. */
        fun clear() {
            key = null; content = 0; view = 0; offset = 0; dragFrom = Int.MIN_VALUE
            box.fill(0)
            clip.fill(0)
        }

        fun contains(x: Int, y: Int) = active && x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3]

        /** Moves the text by [by] (down the text when positive); what could not be used is returned. */
        fun scrollBy(by: Int): Int {
            val to = (offset + by).coerceIn(0, max)
            val used = to - offset
            offset = to
            return by - used
        }

        fun press(x: Int, y: Int): Boolean {
            if (!contains(x, y)) return false
            dragFrom = y
            dragOffset = offset
            return true
        }

        val dragging: Boolean get() = dragFrom != Int.MIN_VALUE

        fun drag(y: Int) {
            if (dragging) offset = (dragOffset + dragFrom - y).coerceIn(0, max)
        }

        fun release() {
            dragFrom = Int.MIN_VALUE
        }

        /**
         * The sign that there is more text: a bar inside the box's right edge (a track with the part now shown) and,
         * at its ends, an arrow towards the text that waits above or below.
         */
        fun drawBar(g: Graphics) {
            if (!active) return
            val x = box[0] + box[2] - 13
            val top = box[1] + 16
            val height = box[3] - 32
            if (height <= 8) return
            g.colour = TRACK
            g.fillRect(x.f, top.f, 5f, height.f)
            val thumb = maxOf(12, (height * view.toFloat() / content).roundToInt())
            val y = top + ((height - thumb) * offset.toFloat() / max).roundToInt()
            g.colour = THUMB
            g.fillRect(x.f, y.f, 5f, thumb.f)
            // the arrows: five rows, 9 wide at the base
            if (offset > 0) for (i in 0 until 5) g.fillRect(x + 2f - i, top - 8f + i, 1f + 2 * i, 1f)
            if (offset < max) for (i in 0 until 5) g.fillRect(x + 2f - i, top + height + 7f - i, 1f + 2 * i, 1f)
            g.colour = Colour.white
        }

        private companion object {
            val TRACK = Colour(255, 255, 255, 70)
            val THUMB = Colour(255, 255, 255, 255)
        }
    }
}
