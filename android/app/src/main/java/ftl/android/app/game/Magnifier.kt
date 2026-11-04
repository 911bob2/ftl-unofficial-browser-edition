package ftl.android.app.game

import android.content.Context

/**
 * Settings > Magnifier (off by default, a device setting): while a finger is held on the game, a square shows what is under
 * it at twice its size, where the hand does not cover it, with the touch point marked. It only looks: every touch goes to
 * the game exactly as without it, so a slide and lift does what it always did (onto a button that takes a finger sliding
 * onto it, it presses that button), and the power drags, the beam, crew selection and the held finger's hover all stay.
 *
 * Neither FTL on iPad nor on Windows has one. It follows the third-party ftl-android runner's magnifier in behaviour (its
 * hold delay, size, zoom and placement; docs/ANDROID.md); none of its code is used.
 *
 * Coordinates are the game view's pixels; [Area] is where the game picture lies on the view. Called on the game thread
 * (Input posts the fingers there; GameRenderer draws the lens).
 */
class Magnifier {
    /** Where the game picture is on the view. */
    data class Area(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * What to draw: the square at ([x], [y]) of side [size] with a [border], showing the square at ([srcX], [srcY]) of side
     * [srcSize] of the picture, and the touch point at ([markX], [markY]) inside it.
     */
    data class Lens(val x: Float, val y: Float, val size: Float, val border: Float, val srcX: Float, val srcY: Float, val srcSize: Float,
                    val markX: Float, val markY: Float, val markSize: Float)

    private var finger = -1
    private var x = 0f
    private var y = 0f
    private var x0 = 0f
    private var y0 = 0f
    private var downAt = 0L
    private var moved = false
    private var shown = false

    /** A finger event as the game gets it (TouchRouter's DOWN, MOVE, UP, CANCEL). Only the first finger down counts. */
    fun touch(action: Int, id: Int, fx: Float, fy: Float, time: Long, area: Area) {
        when (action) {
            DOWN -> if (finger == -1) {
                finger = id
                x = fx; y = fy; x0 = fx; y0 = fy
                downAt = time
                moved = false
            }
            MOVE -> if (id == finger) {
                x = fx; y = fy
                if (Math.hypot((fx - x0).toDouble(), (fy - y0).toDouble()) > area.height * MOVE_SHOWS) moved = true
            }
            UP, CANCEL -> if (id == finger) finger = -1
        }
    }

    /** All fingers gone without their ends (the app went away). */
    fun reset() { finger = -1 }

    /** The lens to draw at [now] (the touch events' clock), or null. */
    fun lens(now: Long, area: Area): Lens? {
        if (finger == -1 || (now - downAt < DELAY_MS && !moved)) return null
        val unit = area.height / 100f
        val size = unit * SIZE
        val gap = unit * GAP
        // Above the finger; with no room there, beside it, on the side away from the nearer edge.
        var cx = x
        var cy = y - (size / 2 + gap)
        if (cy - size / 2 < area.top + unit) {
            cy = y
            cx = if (x > area.left + area.width / 2) x - (size / 2 + gap) else x + (size / 2 + gap)
        }
        cx = cx.coerceIn(area.left + size / 2 + unit, area.left + area.width - size / 2 - unit)
        cy = cy.coerceIn(area.top + size / 2 + unit, area.top + area.height - size / 2 - unit)
        // What it shows: the square around the finger, kept inside the picture (the mark moves off centre at an edge).
        val src = size / ZOOM
        val sx = (x - src / 2).coerceIn(area.left, area.left + area.width - src)
        val sy = (y - src / 2).coerceIn(area.top, area.top + area.height - src)
        val lx = cx - size / 2
        val ly = cy - size / 2
        return Lens(lx, ly, size, unit * BORDER, sx, sy, src, lx + (x - sx) * ZOOM, ly + (y - sy) * ZOOM, unit * MARK)
    }

    /** True once each time the lens comes up (for its haptic), called with each frame's [lens]. */
    fun appeared(lens: Lens?): Boolean {
        val now = lens != null
        val first = now && !shown
        shown = now
        return first
    }

    companion object {
        const val DOWN = 1
        const val MOVE = 2
        const val UP = 3
        const val CANCEL = 4
        /** Held this long before it shows, so quick taps stay clean. */
        const val DELAY_MS = 220L
        /** A finger that moves this far (of the picture's height) shows it at once. */
        const val MOVE_SHOWS = 0.03f
        const val ZOOM = 2f
        /** Sizes in hundredths of the picture's height: the square, its distance from the finger, its border, the mark. */
        const val SIZE = 32f
        const val GAP = 10f
        const val BORDER = 0.7f
        const val MARK = 3.2f

        private const val PREFS = "magnifier"

        /** Off until the player switches it on. */
        const val DEFAULT_ON = false

        /** Settings > Magnifier. */
        fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", DEFAULT_ON)

        /** The game's magnifier for the setting: none at all when it is off, so nothing watches the fingers. */
        fun forGame(on: Boolean): Magnifier? = if (on) Magnifier() else null

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        }
    }
}
