package xyz.znix.xftl.devutil

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The scroll of one of the console's panels, in pixels (docs/CONSOLE.md, "Scrolling"): never by rows.
 *
 * - A wheel notch moves a target the position glides to, critically damped ([GLIDE]): it speeds up and slows down
 *   smoothly, and notches that come while it glides keep its speed instead of jerking it. A trackpad's small steps
 *   ([PRECISE]) move it at once, as the fingers do.
 * - A drag moves it with the finger; let go while moving and it flings on ([fling]), slowing evenly ([FRICTION]) to a stop.
 * - It stops dead at either end: no overshoot to spring back from, so nothing jitters there.
 * - A press stops any motion where the last frame drew it, so a tap lands on what the finger hit.
 *
 * [update] runs once a frame with the frame's time; the drawing takes [drawn], the position rounded to whole pixels, so
 * everything in a panel moves together.
 */
class ConsoleScroller {
    /** Where it is, 0 the top. */
    var pos = 0f
        private set

    /** Where the wheel or the keys asked it to go. */
    var target = 0f
        private set

    /** The fling's speed, pixels a second (0: none). */
    var velocity = 0f
        private set

    /** The glide's speed towards [target], pixels a second. */
    private var glideV = 0f

    /** The furthest it goes: the content's height less the view's. */
    var max = 0f
        private set

    /** A finger or the mouse holds it. */
    var held = false
        private set

    private var clock = 0f
    // the drag's recent positions (time, pos), for the speed when it is let go
    private val sampleT = FloatArray(SAMPLES)
    private val sampleP = FloatArray(SAMPLES)
    private var samples = 0

    /** The position the drawing uses. */
    val drawn: Int get() = pos.roundToInt()

    /** Moving by itself (the wheel's glide or a fling). */
    val moving: Boolean get() = !held && (velocity != 0f || target != pos || glideV != 0f)

    /** The content's height and the view's: the end moves; the position stays inside it. */
    fun setExtent(content: Int, view: Int) {
        max = maxOf(0, content - view).toFloat()
        if (pos > max) pos = max
        if (target > max) target = max
        if (pos < 0f) pos = 0f
        if (target < 0f) target = 0f
    }

    /** Back to the top at once (new content). */
    fun reset() {
        pos = 0f; target = 0f; velocity = 0f; glideV = 0f; held = false; samples = 0
    }

    /** Straight to [p], no glide. */
    fun jumpTo(p: Float) {
        pos = p.coerceIn(0f, max); target = pos; velocity = 0f; glideV = 0f
    }

    /** The wheel: [pixels] down (negative up): a notch glides there, a trackpad's small step goes at once. */
    fun wheel(pixels: Float) {
        velocity = 0f
        if (held) return
        val gliding = target != pos || glideV != 0f
        target = (target + pixels).coerceIn(0f, max)
        if (abs(pixels) < PRECISE && !gliding) { pos = target; glideV = 0f }
    }

    /** Glides to [p] (the keys: a page, an item brought into view). */
    fun glideTo(p: Float) {
        velocity = 0f
        target = p.coerceIn(0f, max)
    }

    /** Glides just far enough that [top]..[bottom] (content pixels) is in a view of [view] pixels. */
    fun reveal(top: Int, bottom: Int, view: Int) {
        val t = target
        when {
            top < t -> glideTo(top.toFloat())
            bottom > t + view -> glideTo((bottom - view).toFloat())
        }
    }

    /** A finger or button down on the panel: whatever moved stops where it was drawn. */
    fun press() {
        held = true
        velocity = 0f
        glideV = 0f
        target = pos
        samples = 0
        sample()
    }

    /** The finger moved by [dy] pixels (down positive): the content goes with it. */
    fun drag(dy: Float) {
        if (!held) press()
        pos = (pos - dy).coerceIn(0f, max)
        target = pos
    }

    /** Let go: on with the speed of the last moments of the drag ([fling]; else it stays where it is). */
    fun release(fling: Boolean = true) {
        if (!held) return
        held = false
        velocity = if (fling) fling() else 0f
        target = pos
        samples = 0
    }

    /** The drag's speed over its last [WINDOW] seconds; held still that long, none. */
    fun fling(): Float {
        if (samples == 0) return 0f
        // the newest sample is this frame's position, the oldest one inside the window the start
        var i = samples - 1
        while (i > 0 && clock - sampleT[i - 1] <= WINDOW) i--
        val dt = clock - sampleT[i]
        val moved = pos - sampleP[i]
        if (dt < MIN_SPAN || abs(moved) < 1f) return 0f
        val v = (moved / dt).coerceIn(-MAX_FLING, MAX_FLING)
        return if (abs(v) < MIN_FLING) 0f else v
    }

    private fun sample() {
        if (samples == SAMPLES) {
            System.arraycopy(sampleT, 1, sampleT, 0, SAMPLES - 1)
            System.arraycopy(sampleP, 1, sampleP, 0, SAMPLES - 1)
            samples--
        }
        sampleT[samples] = clock
        sampleP[samples] = pos
        samples++
    }

    /** One frame of [dt] seconds. A step with no time (the hosts run one after each input event) changes nothing. */
    fun update(dt: Float) {
        if (dt <= 0f) return
        clock += dt
        if (held) { sample(); return }
        if (velocity != 0f) {
            pos += velocity * dt
            velocity *= exp(-FRICTION * dt)
            if (pos <= 0f || pos >= max) { pos = pos.coerceIn(0f, max); velocity = 0f }
            if (abs(velocity) < STOP) velocity = 0f
            target = pos
            return
        }
        if (target != pos || glideV != 0f) {
            // the critically damped spring's exact step: x'' = -w^2 x - 2w x', x the way still to go
            val x0 = pos - target
            val c = glideV + GLIDE * x0
            val e = exp(-GLIDE * dt)
            val x = (x0 + c * dt) * e
            glideV = (glideV - GLIDE * c * dt) * e
            pos = target + x
            if (pos < 0f || pos > max) { pos = pos.coerceIn(0f, max); glideV = 0f }
            if (abs(pos - target) < 0.5f && abs(glideV) < 30f) { pos = target; glideV = 0f }
        }
    }

    companion object {
        /** The glide's stiffness (1/s): a notch is nearly there after about 7/GLIDE seconds (0.23 s), no overshoot. */
        const val GLIDE = 30f
        /** A wheel step smaller than this (pixels) is a trackpad's or a fine wheel's: it moves at once. */
        const val PRECISE = 24f
        /** How fast a fling slows: its speed falls by e every 1/FRICTION seconds, so it runs v/FRICTION pixels. */
        const val FRICTION = 4f
        /** Slower than this (pixels a second) a fling has stopped. */
        const val STOP = 12f
        /** A flick slower than this is a drag that stopped, not a fling. */
        const val MIN_FLING = 60f
        const val MAX_FLING = 7000f
        /** The part of the drag the fling's speed is measured over. */
        const val WINDOW = 0.08f
        const val MIN_SPAN = 0.012f
        private const val SAMPLES = 16

        /** Pixels a wheel notch (120) moves when the host gives no pixel amount (a mouse on Android). */
        const val NOTCH_PIXELS = 96f

        /**
         * The website's wheel in screen units, set by the host for the one wheel event it sends (BrowserEngine.wheel,
         * from the event's deltaY), NaN when the host has only notches.
         */
        @JvmField
        var hostWheelPixels = Float.NaN
    }
}
