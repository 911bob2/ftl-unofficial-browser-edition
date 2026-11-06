package xyz.znix.xftl.devutil

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/** A rectangle of the console's screen. */
data class Area(val x: Int, val y: Int, val w: Int, val h: Int) {
    val bottom get() = y + h
    val right get() = x + w
    fun contains(px: Int, py: Int) = px in x until x + w && py in y until y + h

    /** The part both cover (zero width or height when none). */
    fun intersect(o: Area): Area {
        val l = maxOf(x, o.x)
        val t = maxOf(y, o.y)
        return Area(l, t, maxOf(0, minOf(right, o.right) - l), maxOf(0, minOf(bottom, o.bottom) - t))
    }

    fun inset(d: Int) = Area(x + d, y + d, maxOf(0, w - 2 * d), maxOf(0, h - 2 * d))
    val empty get() = w <= 0 || h <= 0
}

/** Something tappable drawn this frame: a tap or click inside [area] runs [action]. */
class Hit(val area: Area, val action: () -> Unit)

/**
 * A completer the console draws into a window (the suggestion list, the `get`/`unlock` pickers, the command reference,
 * docs/CONSOLE.md "The window"): a fixed header (title, counts, tabs), the scrolled content, clipped at the window's
 * edges, and a fixed footer. The console scrolls the content (ConsoleScroller) and draws the scroll bar.
 */
interface ConsolePanel : DebugConsole.Overlay {
    /** The fixed header, drawn into the top of [area] (its full width); returns the height it took. */
    fun drawHeader(g: Graphics, ui: ConsoleUi, area: Area): Int = 0

    /** Draws the content into [area] (clipped to it) scrolled down by [scroll] pixels; returns the content's full height. */
    fun draw(g: Graphics, ui: ConsoleUi, area: Area, scroll: Int): Int

    /** The fixed footer's height (0: none). */
    fun footerHeight(ui: ConsoleUi): Int = 0

    /** The footer, drawn after the content (so it can tell what is under the pointer). */
    fun drawFooter(g: Graphics, ui: ConsoleUi, area: Area) {}

    /** Changes when the content does (a new search): the scroll goes back to the top. */
    val contentKey: Any? get() = null

    /** Takes most of the screen (a picker, the reference); else the lower half (suggestions). */
    val big: Boolean get() = false

    /** The window's title tab, or null for none. */
    fun title(): String? = null

    // ---- the keys (docs/CONSOLE.md "Keys"): the arrows move a selection, Enter takes it, Escape drops it

    /** Something is selected with the keys (or was tapped last). */
    val hasSelection: Boolean get() = false

    /**
     * Moves the selection for [key] (Input.KEY_UP/DOWN/LEFT/RIGHT); returns the selected entry's top and bottom in content
     * pixels, to bring it into view, or null when the key does nothing here (Up from the top drops the selection).
     */
    fun navigate(key: Int): IntRange? = null

    /** Enter on the selection: true when it took it. */
    fun activate(): Boolean = false

    /** Escape: drops the selection; true when there was one. */
    fun clearSelection(): Boolean = false
}

/**
 * The console's look on this screen: the desktop console in the c&c font, or (TouchMode, Android) the full-screen touch
 * console with text three times as big and rows a finger can hit. One visual language with the game's own windows: the
 * window frame (window_base), the native TextButton (chamfered, light, yellow on hover), the sector cutout colours.
 */
class ConsoleUi(val game: InGameState, val touch: Boolean) {
    /** The text scale: c&c is 7 pixels high. */
    val scale = if (touch) 3 else 1
    val font: SILFontLoader = game.getFont("c&c", scale.f)
    /** Tags and small notes. */
    val small: SILFontLoader = game.getFont("c&c", if (touch) 2f else 1f)
    /** The window's title tab: the map's side tab with HL2 at twice its size, as the game's own titled windows. */
    val titleFont: SILFontLoader by lazy { game.getFont("HL2", 2f) }
    val lineH = if (touch) 34 else 12
    val rowH = if (touch) 52 else 16
    val textH = 7 * scale
    val smallH = if (touch) 14 else 7
    val pad = if (touch) 10 else 6
    /** The scroll bar's width, and the part of it a finger or the mouse can take. */
    val barW = if (touch) 10 else 8
    val barHitW = if (touch) 34 else 14

    var mouseX = -1
    var mouseY = -1
    var time = 0f

    /** A press is down and has not become a drag: what it is on draws pressed. */
    var pressing = false
    var pressX = -1
    var pressY = -1

    /** What can be tapped this frame. */
    val hits = ArrayList<Hit>()

    // the clips pushed now: a hit or a hover counts only inside them
    private val clips = ArrayList<Area>()

    /** The innermost clip, or null. */
    val clip: Area? get() = clips.lastOrNull()

    /** Draws and hits from now on are cut to [a] (within any clip already pushed). */
    fun pushClip(g: Graphics, a: Area) {
        val c = clip?.let { a.intersect(it) } ?: a
        clips += c
        g.pushClip(c.x, c.y, c.w, c.h)
    }

    fun popClip(g: Graphics) {
        clips.removeAt(clips.size - 1)
        g.popClip()
    }

    inline fun clipped(g: Graphics, a: Area, fn: () -> Unit) {
        pushClip(g, a)
        try { fn() } finally { popClip(g) }
    }

    /** A new frame: no hits, no clips. */
    fun startFrame() {
        hits.clear()
        clips.clear()
    }

    /** [action] runs on a tap inside [area], the part of it inside the clip. */
    fun hit(area: Area, action: () -> Unit) {
        val a = clip?.let { area.intersect(it) } ?: area
        if (!a.empty) hits += Hit(a, action)
    }

    private fun inClip(px: Int, py: Int) = clip?.contains(px, py) ?: true

    fun hovered(a: Area) = !touch && a.contains(mouseX, mouseY) && inClip(mouseX, mouseY)

    /** The press is on [a] (and it is not a drag). */
    fun pressed(a: Area) = pressing && a.contains(pressX, pressY) && inClip(pressX, pressY)

    /** The baseline for text of [f] centred in a row starting at [top] of [height]. */
    fun baseline(top: Int, height: Int, f: SILFontLoader = font) = top + (height + (if (f === small) smallH else textH)) / 2

    fun box(g: Graphics, a: Area, fill: Colour, border: Colour? = null) {
        g.colour = fill
        g.fillRect(a.x.f, a.y.f, a.w.f, a.h.f)
        if (border != null) {
            g.colour = border
            g.drawRect(a.x.f, a.y.f, a.w - 1f, a.h - 1f)
        }
    }

    /** An outline [t] pixels thick just inside [a]. */
    fun outline(g: Graphics, a: Area, colour: Colour, t: Int) {
        g.colour = colour
        g.fillRect(a.x.f, a.y.f, a.w.f, t.f)
        g.fillRect(a.x.f, a.bottom - t.f, a.w.f, t.f)
        g.fillRect(a.x.f, a.y + t.f, t.f, a.h - 2f * t)
        g.fillRect(a.right - t.f, a.y + t.f, t.f, a.h - 2f * t)
    }

    /** The selection's mark: the crew box's selected green, around [a]. */
    fun selection(g: Graphics, a: Area) = outline(g, Area(a.x - 3, a.y - 3, a.w + 6, a.h + 6), SELECTED, if (touch) 3 else 2)

    /**
     * A button with a centred label, the native TextButton's look (Buttons.drawTextButton): light, yellow under the
     * pointer, a deeper yellow while pressed or switched [on]; tapping it runs [action].
     */
    fun button(g: Graphics, a: Area, label: String, on: Boolean = false, f: SILFontLoader = font, action: () -> Unit) {
        val fill = when {
            pressed(a) -> PRESSED
            on -> Constants.TEXT_BUTTON_SELECTED
            hovered(a) -> Constants.TEXT_BUTTON_HOVER
            else -> Constants.TEXT_BUTTON_NORMAL
        }
        g.colour = fill
        Buttons.drawChamfer(g, a.x, a.y, a.w, a.h, if (touch) 5 else 3)
        val text = fit(f, label, a.w - pad)
        f.drawString(a.x + (a.w - f.getWidth(text)) / 2f, baseline(a.y, a.h, f).f, text, Constants.TEXT_BUTTON_TEXT)
        hit(a, action)
    }

    /** A dark field with a light edge: the input line, a search. */
    fun field(g: Graphics, a: Area, focused: Boolean = true) =
        box(g, a, FIELD, if (focused) Constants.TEXT_BUTTON_NORMAL else Constants.TEXT_BUTTON_DISABLED)

    /** A row or chip: the sector cutout's blue, lighter under the pointer or the finger. */
    fun rowFill(g: Graphics, a: Area, hot: Boolean, alt: Boolean = false) =
        box(g, a, if (hot) ROW_HOT else if (alt) ROW_ALT else ROW, if (hot) Constants.UI_BUTTON_HOVER else null)

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

    /** [text] cut to [w] pixels of [f], "..." marking the cut. */
    fun fit(f: SILFontLoader, text: String, w: Int): String = fitText(f, text, w)

    /** The item's tags in a row from [x], [y] (its top), no wider than [w], in [f] of [fh] pixels high. */
    fun tags(g: Graphics, x: Int, y: Int, tags: List<String>, w: Int = Int.MAX_VALUE, f: SILFontLoader = small, fh: Int = smallH) {
        var tx = x
        for (t in tags) {
            val tw = f.getWidth(t) + 6
            if (tx + tw > x + w) break
            g.colour = Colour(0, 0, 0, 200)
            g.fillRect(tx.f, y.f, tw.f, fh + 6f)
            f.drawString(tx + 3f, y + fh + 2f, t, tagColour(t))
            tx += tw + 2
        }
    }

    /** The scroll bar of a view [track] high showing [view] of [content] pixels at [pos]; [hot] the pointer is on it. */
    fun scrollBar(g: Graphics, track: Area, pos: Float, view: Int, content: Int, hot: Boolean, held: Boolean) {
        if (content <= view) return
        box(g, track, Colour(0, 0, 0, 120), Colour(90, 100, 105, 200))
        val thumb = thumbArea(track, pos, view, content)
        g.colour = when {
            held -> PRESSED
            hot -> Constants.TEXT_BUTTON_HOVER
            else -> Constants.TEXT_BUTTON_NORMAL
        }
        Buttons.drawChamfer(g, thumb.x + 1, thumb.y, thumb.w - 2, thumb.h, 1)
    }

    companion object {
        /** The keys' selection: the crew box's selected green. */
        @JvmField val SELECTED: Colour = Constants.CREW_BOX_SELECT
        /** A button held down: the hover yellow, deeper. */
        @JvmField val PRESSED = Colour(214, 180, 50)
        @JvmField val FIELD = Colour(14, 20, 24, 240)
        /** Rows: the sector cutout's blue (53, 75, 89), a little see-through. */
        @JvmField val ROW = Colour(53, 75, 89, 200)
        @JvmField val ROW_ALT = Colour(43, 62, 74, 200)
        @JvmField val ROW_HOT = Colour(78, 104, 120, 230)
        /** The console's own background. */
        @JvmField val BACKDROP = Colour(8, 11, 16, 215)
        /** Headings in the reference: the event window's text yellow. */
        @JvmField val HEADING = Colour(255, 230, 150)
        @JvmField val TEXT = Constants.SECTOR_CUTOUT_TEXT
        @JvmField val DIM = Colour(180, 188, 178)

        /** The scroll bar's thumb in a [track] for [view] of [content] at [pos]. */
        @JvmStatic
        fun thumbArea(track: Area, pos: Float, view: Int, content: Int): Area {
            if (content <= view || track.h <= 0) return track
            val h = maxOf(minOf(track.h, 24), track.h * view / content)
            val y = track.y + ((track.h - h) * (pos / (content - view)).coerceIn(0f, 1f)).toInt()
            return Area(track.x, y, track.w, h)
        }

        /** [text] cut to [w] pixels of [f], "..." marking the cut. */
        @JvmStatic
        fun fitText(f: SILFontLoader, text: String, w: Int): String {
            if (f.getWidth(text) <= w) return text
            var n = text.length
            while (n > 0 && f.getWidth(text.substring(0, n) + ELLIPSIS) > w) n--
            return text.substring(0, n).trimEnd() + ELLIPSIS
        }

        const val ELLIPSIS = "..."

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
