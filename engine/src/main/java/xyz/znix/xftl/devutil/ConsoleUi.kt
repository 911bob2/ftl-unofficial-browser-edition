package xyz.znix.xftl.devutil

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/** A rectangle of the console's screen. */
data class Area(val x: Int, val y: Int, val w: Int, val h: Int) {
    val bottom get() = y + h
    val right get() = x + w
    fun contains(px: Int, py: Int) = px in x until x + w && py in y until y + h
}

/** Something tappable drawn this frame: a tap or click inside [area] runs [action]. */
class Hit(val area: Area, val action: () -> Unit)

/**
 * A completer the console draws into a scrollable area (the suggestion list, the `get`/`unlock` pickers, the command
 * reference). It draws what fits, records what can be tapped in [ConsoleUi.hits], and gives its full height so the
 * console can scroll it with the wheel or a drag.
 */
interface ConsolePanel : DebugConsole.Overlay {
    /** Draws into [area] scrolled down by [scroll] pixels; returns the content's full height. */
    fun draw(g: Graphics, ui: ConsoleUi, area: Area, scroll: Int): Int

    /** Changes when the content does (a new search): the scroll goes back to the top. */
    val contentKey: Any? get() = null
}

/**
 * The console's look on this screen: the desktop's half-screen console in the c&c font, or (TouchMode, Android) the
 * full-screen touch console with text three times as big and rows a finger can hit.
 */
class ConsoleUi(val game: InGameState, val touch: Boolean) {
    /** The text scale: c&c is 7 pixels high. */
    val scale = if (touch) 3 else 1
    val font: SILFontLoader = game.getFont("c&c", scale.f)
    /** Tags and small notes. */
    val small: SILFontLoader = game.getFont("c&c", if (touch) 2f else 1f)
    val lineH = if (touch) 34 else 12
    val rowH = if (touch) 52 else 15
    val textH = 7 * scale
    val smallH = if (touch) 14 else 7
    val pad = if (touch) 10 else 5

    var mouseX = -1
    var mouseY = -1
    var time = 0f

    /** What can be tapped this frame. */
    val hits = ArrayList<Hit>()

    fun hit(area: Area, action: () -> Unit) { hits += Hit(area, action) }

    fun hovered(a: Area) = !touch && a.contains(mouseX, mouseY)

    /** The baseline for text of [font] centred in a row starting at [top] of [height]. */
    fun baseline(top: Int, height: Int, f: SILFontLoader = font) = top + (height + (if (f === small) smallH else textH)) / 2

    fun box(g: Graphics, a: Area, fill: Colour, border: Colour? = null) {
        g.colour = fill
        g.fillRect(a.x.f, a.y.f, a.w.f, a.h.f)
        if (border != null) {
            g.colour = border
            g.drawRect(a.x.f, a.y.f, a.w - 1f, a.h - 1f)
        }
    }

    /** A button with a centred label; tapping it runs [action]. */
    fun button(g: Graphics, a: Area, label: String, on: Boolean = false, f: SILFontLoader = font, action: () -> Unit) {
        val over = hovered(a)
        box(g, a, if (on) Colour(70, 110, 160, 230) else if (over) Colour(80, 80, 80, 230) else Colour(45, 45, 50, 230), Colour(150, 155, 165, 255))
        f.drawString(a.x + (a.w - f.getWidth(label)) / 2f, baseline(a.y, a.h, f).f, label, Colour.white)
        hit(a, action)
    }

    /** Tag colours: the item kinds' marks in the pickers. */
    fun tagColour(tag: String): Colour = when (tag) {
        ConsoleItems.TAG_EVENT_ONLY -> Colour(255, 170, 60)
        ConsoleItems.TAG_HIDDEN -> Colour(200, 120, 255)
        ConsoleItems.TAG_LOCKED, ConsoleItems.TAG_NO_ROOM -> Colour(255, 90, 90)
        ConsoleItems.TAG_UNLOCKED, ConsoleItems.TAG_INSTALLED -> Colour(120, 230, 120)
        ConsoleItems.TAG_NO_ART -> Colour(120, 200, 255)
        else -> Colour.lightGray
    }

    /** The height of a row of [tags]. */
    val tagRowH get() = smallH + 8

    /** [text] cut to [w] pixels of [f], ".." marking the cut. */
    fun fit(f: SILFontLoader, text: String, w: Int): String {
        if (f.getWidth(text) <= w) return text
        var n = text.length
        while (n > 0 && f.getWidth(text.substring(0, n) + "..") > w) n--
        return text.substring(0, n) + ".."
    }

    /** The item's tags in a row from [x], [y] (its top), no wider than [w], in [f] of [fh] pixels high. */
    fun tags(g: Graphics, x: Int, y: Int, tags: List<String>, w: Int = Int.MAX_VALUE, f: SILFontLoader = small, fh: Int = smallH) {
        var tx = x
        for (t in tags) {
            if (tx + f.getWidth(t) + 6 > x + w) break
            val w = f.getWidth(t) + 6
            g.colour = Colour(0, 0, 0, 200)
            g.fillRect(tx.f, y.f, w.f, fh + 6f)
            f.drawString(tx + 3f, y + fh + 2f, t, tagColour(t))
            tx += w + 2
        }
    }

    companion object {
        /** HSV (all 0..1) to a colour. */
        @JvmStatic
        fun hsv(h: Float, s: Float, v: Float, a: Float = 1f): Colour {
            val hh = ((h % 1f) + 1f) % 1f * 6f
            val i = hh.toInt()
            val f = hh - i
            val p = v * (1 - s)
            val q = v * (1 - s * f)
            val t = v * (1 - s * (1 - f))
            return when (i) {
                0 -> Colour(v, t, p, a); 1 -> Colour(q, v, p, a); 2 -> Colour(p, v, t, a)
                3 -> Colour(p, q, v, a); 4 -> Colour(t, p, v, a); else -> Colour(v, p, q, a)
            }
        }

        /**
         * The new `get` and `unlock` commands stand out: each letter its own hue, the hues moving along with time,
         * with a soft glow of the same colours behind.
         */
        @JvmStatic
        fun rainbow(font: SILFontLoader, x: Float, y: Float, text: String, time: Float, glow: Float = 1f) {
            for ((i, ch) in text.withIndex()) {
                if (ch == ' ') continue
                val cx = x + font.getAdvance(text.substring(0, i))
                val hue = time * 0.3f + i * 0.08f
                val s = ch.toString()
                if (glow > 0f) {
                    val c = hsv(hue, 0.9f, 1f, 0.28f)
                    val d = font.scale.coerceAtLeast(1f)
                    font.drawString(cx - d, y, s, c); font.drawString(cx + d, y, s, c)
                    font.drawString(cx, y - d, s, c); font.drawString(cx, y + d, s, c)
                }
                font.drawString(cx, y, s, hsv(hue, 0.55f, 1f))
            }
        }

        /** Is this the name of one of the new rainbow commands? */
        @JvmStatic
        fun featured(commands: List<DebugConsole.Cmd>, word: String) = ConsoleRegistry.find(commands, word)?.featured == true
    }
}
