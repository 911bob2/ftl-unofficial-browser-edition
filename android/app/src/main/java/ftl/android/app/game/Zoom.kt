package ftl.android.app.game

import android.content.Context
import kotlin.math.hypot

/**
 * Settings > Pinch to zoom (off by default, a device setting): two fingers pinch the game's picture larger, up to [MAX]
 * times, and move the larger picture; a minimap shows the whole screen with the part in view marked.
 *
 * Neither FTL on iPad nor on Windows has one, and it changes no rule of the game: it sits between the fingers and the
 * game, and between the game's frame and the screen. The game draws its frame as always; GameRenderer copies the part in
 * view onto the screen, larger ([blit]), and a finger's point on the screen goes back to the frame's point
 * ([unzoomX], [unzoomY]) before it reaches the game, so every touch control gets the point under the finger.
 *
 * Fingers ([touch], view pixels, in the order they come): the first finger is held back until it moves [SLOP], is held
 * [HOLD_MS] or lifts; then the game gets it with everything it did so far, in order and with its own times, and every
 * later finger as well, until all are up (so the beam's second finger still slides the line). A second finger that comes
 * down while the first is held back makes it a pinch instead: the game gets neither finger. Pinching sets the zoom, the
 * point between the fingers stays under them, so moving both fingers moves the picture. Two fingers tapped together
 * (lifted within [TAP_MS], neither moved) go back to 1x, or at 1x zoom to [TAP_ZOOM] on that point. A pinch ending below
 * [SNAP] is 1x.
 *
 * The minimap (shown while zoomed, unless switched off): a bar (on the side away from the nearer edge) to drag it about (its place is kept), a 1x button
 * at the bar's end, and the picture: a finger on it moves the view so that its centre is under the finger. Fingers on
 * the minimap never reach the game.
 *
 * With the setting off there is no Zoom at all ([forGame]), so nothing waits and the picture is copied as before.
 * Called on the game thread only.
 */
class Zoom(
    val settings: Settings = Settings(),
    /** The minimap was moved to this place (fractions of the free room, [Settings.placeX], [Settings.placeY]). */
    private val onPlaced: (Float, Float) -> Unit = { _, _ -> },
) {
    /** The minimap's sizes: its picture's height in hundredths of the view's. */
    enum class Size(val label: String, val share: Float) { SMALL("Small", 22f), MEDIUM("Medium", 30f), LARGE("Large", 38f) }

    /**
     * [minimap] on or off; its [size]; its place, where 0 is the left (top) edge of the room it can take and 1 the right
     * (bottom) edge.
     */
    data class Settings(val minimap: Boolean = true, val size: Size = Size.MEDIUM, val placeX: Float = DEFAULT_X, val placeY: Float = DEFAULT_Y)

    /** A rectangle in view pixels. */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun has(px: Float, py: Float) = px >= x && py >= y && px < x + w && py < y + h
    }

    /** The minimap's parts on the view: the whole box, the bar, the bar's 1x button, the picture, the view's part in it. */
    data class Minimap(val box: Box, val bar: Box, val reset: Box, val map: Box, val seen: Box, val border: Float)

    /** One finger event for the game, held back or passed on (view pixels). */
    fun interface Sink {
        fun touch(action: Int, id: Int, x: Float, y: Float, time: Long)
    }

    private enum class Mode { IDLE, HELD, PASS, PINCH, MINIMAP, DONE }

    private var viewW = 1f
    private var viewH = 1f

    /** The picture's size: 1 is the game as without zoom. */
    var scale = 1f
        private set
    /** Where the zoomed picture's corner is on the view: a view point is `unzoomed * scale + pan`. */
    var panX = 0f
        private set
    var panY = 0f
        private set

    val zoomed get() = scale > 1f

    private var placeX = settings.placeX
    private var placeY = settings.placeY
    /**
     * The bar is on the side away from the nearer edge (below the picture in the top half), so dragging it never starts at
     * the screen's edge, where the system takes a swipe (its bars, Home). It changes side when a drag ends.
     */
    private var barBelow = placeY < 0.5f

    private var mode = Mode.IDLE
    private val down = HashMap<Int, FloatArray>()

    // The first finger while it is held back, and what it did meanwhile.
    private var first = -1
    private var firstX = 0f
    private var firstY = 0f
    private var firstAt = 0L
    private val held = ArrayList<Event>()

    private class Event(val action: Int, val id: Int, val x: Float, val y: Float, val time: Long)

    // The pinch: its two fingers, the zoom and point when it began, whether it can still be a two-finger tap.
    private var a = -1
    private var b = -1
    private var startDist = 1f
    private var startScale = 1f
    private var anchorX = 0f
    private var anchorY = 0f
    private var tap = false
    private var pinchAt = 0L

    // A finger on the minimap: what it took (the bar, the 1x button, the picture) and where the box was.
    private var mapFinger = -1
    private var mapPart = 0
    private var grabX = 0f
    private var grabY = 0f
    private var boxX0 = 0f
    private var boxY0 = 0f

    /** The view's size changed (the game view, in pixels). The zoom keeps its centre. */
    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val cx = unzoomX(viewW / 2) / viewW
        val cy = unzoomY(viewH / 2) / viewH
        viewW = w.toFloat()
        viewH = h.toFloat()
        if (zoomed) lookAt(cx * viewW, cy * viewH)
    }

    fun unzoomX(x: Float) = (x - panX) / scale
    fun unzoomY(y: Float) = (y - panY) / scale

    /** Back to the game's own size. */
    fun reset() {
        scale = 1f
        panX = 0f
        panY = 0f
    }

    /** Zoom [to] times with the unzoomed point ([ux], [uy]) at the view's point ([vx], [vy]), kept on the picture. */
    fun set(to: Float, ux: Float, uy: Float, vx: Float, vy: Float) {
        scale = to.coerceIn(1f, MAX)
        panX = vx - ux * scale
        panY = vy - uy * scale
        keepIn()
    }

    /** The view's centre on the unzoomed point ([ux], [uy]), as near as the picture's edges allow. */
    private fun lookAt(ux: Float, uy: Float) = set(scale, ux, uy, viewW / 2, viewH / 2)

    private fun keepIn() {
        panX = panX.coerceIn(viewW - scale * viewW, 0f)
        panY = panY.coerceIn(viewH - scale * viewH, 0f)
        if (scale == 1f) { panX = 0f; panY = 0f }
    }

    /** A finger event, view pixels, in the order the view got them; what the game is to get goes to [sink]. */
    fun touch(action: Int, id: Int, x: Float, y: Float, time: Long, sink: Sink) {
        when (action) {
            DOWN -> {
                down[id] = floatArrayOf(x, y)
                when (mode) {
                    Mode.IDLE -> {
                        val m = minimap()
                        if (m != null && m.box.has(x, y)) {
                            mode = Mode.MINIMAP
                            mapFinger = id
                            mapPart = if (m.reset.has(x, y)) PART_RESET else if (m.bar.has(x, y)) PART_BAR else PART_MAP
                            grabX = x; grabY = y
                            boxX0 = m.box.x; boxY0 = m.box.y
                            if (mapPart == PART_MAP) mapLook(m, x, y)
                        } else {
                            mode = Mode.HELD
                            first = id
                            firstX = x; firstY = y
                            firstAt = time
                            held.clear()
                            held += Event(action, id, x, y, time)
                        }
                    }
                    Mode.HELD -> startPinch(id, time)
                    Mode.PASS -> sink.touch(action, id, x, y, time)
                    else -> {}
                }
            }
            MOVE -> {
                val at = down[id] ?: return
                at[0] = x; at[1] = y
                when (mode) {
                    Mode.HELD -> if (id == first) {
                        held += Event(action, id, x, y, time)
                        if (hypot(x - firstX, y - firstY) > SLOP * unit()) pass(sink)
                    }
                    Mode.PASS -> sink.touch(action, id, x, y, time)
                    Mode.PINCH -> if (id == a || id == b) pinch()
                    Mode.MINIMAP -> if (id == mapFinger) mapMove(x, y)
                    else -> {}
                }
            }
            UP, CANCEL -> {
                if (down.remove(id) == null) return
                when (mode) {
                    Mode.HELD -> if (id == first) {
                        // Lifted before it was passed on: a tap, with its own times. A cancel is nothing to the game.
                        if (action == UP) {
                            held += Event(action, id, x, y, time)
                            pass(sink)
                        } else held.clear()
                    }
                    Mode.PASS -> sink.touch(action, id, x, y, time)
                    Mode.PINCH -> if (id == a || id == b) {
                        endPinch(action == UP && tap && time - pinchAt <= TAP_MS)
                        mode = Mode.DONE
                    }
                    Mode.MINIMAP -> if (id == mapFinger) {
                        mapEnd(action == UP, x, y)
                        mode = Mode.DONE
                    }
                    else -> {}
                }
                if (down.isEmpty()) { mode = Mode.IDLE; first = -1; held.clear() }
            }
        }
    }

    /** Each frame (the touch events' clock): a first finger held back [HOLD_MS] goes to the game. */
    fun tick(now: Long, sink: Sink) {
        if (mode == Mode.HELD && now - firstAt >= HOLD_MS) pass(sink)
    }

    /** Every finger gone without its end (the app went away): what the game has gets its cancel, the rest is dropped. */
    fun cancelAll(time: Long, sink: Sink) {
        if (mode == Mode.PASS) for ((id, at) in down) sink.touch(CANCEL, id, at[0], at[1], time)
        if (mode == Mode.PINCH) endPinch(false)
        down.clear()
        held.clear()
        first = -1
        mapFinger = -1
        mode = Mode.IDLE
    }

    private fun pass(sink: Sink) {
        mode = Mode.PASS
        for (e in held) sink.touch(e.action, e.id, e.x, e.y, e.time)
        held.clear()
        if (down.isEmpty()) mode = Mode.IDLE
    }

    private fun startPinch(second: Int, time: Long) {
        held.clear()
        mode = Mode.PINCH
        a = first
        b = second
        val p = down[a]!!
        val q = down[b]!!
        startDist = hypot(q[0] - p[0], q[1] - p[1]).coerceAtLeast(1f)
        startScale = scale
        anchorX = unzoomX((p[0] + q[0]) / 2)
        anchorY = unzoomY((p[1] + q[1]) / 2)
        tap = hypot(p[0] - firstX, p[1] - firstY) <= SLOP * unit()
        pinchAt = firstAt.coerceAtMost(time)
        startA = floatArrayOf(p[0], p[1])
        startB = floatArrayOf(q[0], q[1])
    }

    private var startA = FloatArray(2)
    private var startB = FloatArray(2)

    private fun pinch() {
        val p = down[a] ?: return
        val q = down[b] ?: return
        val slop = SLOP * unit()
        if (tap && (hypot(p[0] - startA[0], p[1] - startA[1]) > slop || hypot(q[0] - startB[0], q[1] - startB[1]) > slop)) tap = false
        if (tap) return
        val d = hypot(q[0] - p[0], q[1] - p[1])
        set(startScale * d / startDist, anchorX, anchorY, (p[0] + q[0]) / 2, (p[1] + q[1]) / 2)
    }

    private fun endPinch(tapped: Boolean) {
        if (tapped) {
            if (zoomed) reset()
            else {
                val p = startA
                val q = startB
                val mx = (p[0] + q[0]) / 2
                val my = (p[1] + q[1]) / 2
                set(TAP_ZOOM, unzoomX(mx), unzoomY(my), mx, my)
            }
        } else if (scale < SNAP) reset()
        a = -1
        b = -1
    }

    // ---- the minimap ----

    private fun unit() = viewH / 100f

    /** Where the minimap is on the view, or null when it does not show. */
    fun minimap(): Minimap? {
        if (!zoomed || !settings.minimap) return null
        return layout(boxLeft(placeX), boxTop(placeY))
    }

    private fun mapH() = unit() * settings.size.share
    private fun mapW() = mapH() * viewW / viewH
    private fun barH() = unit() * BAR
    private fun margin() = unit() * MARGIN
    private fun roomX() = (viewW - 2 * margin() - mapW()).coerceAtLeast(0f)
    private fun roomY() = (viewH - 2 * margin() - mapH() - barH()).coerceAtLeast(0f)
    private fun boxLeft(fx: Float) = margin() + fx.coerceIn(0f, 1f) * roomX()
    private fun boxTop(fy: Float) = margin() + fy.coerceIn(0f, 1f) * roomY()

    private fun layout(left: Float, top: Float): Minimap {
        val w = mapW()
        val barTop = if (barBelow) top + mapH() else top
        val bar = Box(left, barTop, w, barH())
        val reset = Box(left + w - barH() * RESET_W, barTop, barH() * RESET_W, barH())
        val map = Box(left, if (barBelow) top else top + barH(), w, mapH())
        val seen = Box(map.x + unzoomX(0f) / viewW * map.w, map.y + unzoomY(0f) / viewH * map.h, map.w / scale, map.h / scale)
        return Minimap(Box(left, top, w, barH() + mapH()), bar, reset, map, seen, (unit() * BORDER).coerceAtLeast(2f))
    }

    /** The view's centre on the minimap point ([x], [y]). */
    private fun mapLook(m: Minimap, x: Float, y: Float) {
        lookAt((x - m.map.x) / m.map.w * viewW, (y - m.map.y) / m.map.h * viewH)
    }

    private fun mapMove(x: Float, y: Float) {
        when (mapPart) {
            PART_MAP -> minimap()?.let { mapLook(it, x, y) }
            PART_BAR, PART_RESET -> {
                if (mapPart == PART_RESET && hypot(x - grabX, y - grabY) <= SLOP * unit()) return
                // A finger that came down on the 1x button and slides is moving the minimap.
                mapPart = PART_BAR
                placeX = if (roomX() > 0) ((boxX0 + x - grabX - margin()) / roomX()).coerceIn(0f, 1f) else 0f
                placeY = if (roomY() > 0) ((boxY0 + y - grabY - margin()) / roomY()).coerceIn(0f, 1f) else 0f
            }
        }
    }

    private fun mapEnd(lifted: Boolean, x: Float, y: Float) {
        when (mapPart) {
            PART_RESET -> if (lifted && minimap()?.reset?.has(x, y) != false) reset()
            PART_BAR -> {
                barBelow = placeY < 0.5f
                onPlaced(placeX, placeY)
            }
        }
        mapFinger = -1
    }

    // ---- the picture on the view ----

    /**
     * What GameRenderer copies, GL's way (rows from the bottom): the source rectangle in the frame's pixels and the
     * destination on the view, x0 y0 x1 y1 each. The frame of [frameW] x [frameH] game units drawn at [pixels] per unit lies
     * at ([left], [top]) on the unzoomed view, [sx] and [sy] view pixels per unit. Only the part in view is copied, in whole
     * frame pixels, so the picture does not shimmer as it moves.
     */
    fun blit(frameW: Int, frameH: Int, pixels: Int, left: Float, top: Float, sx: Float, sy: Float): IntArray {
        val fullW = frameW * pixels
        val fullH = frameH * pixels
        // The view's corners in the frame's pixels (rows from the top), widened to whole pixels, kept in the frame.
        val x0 = kotlin.math.floor((unzoomX(0f) - left) / sx * pixels).toInt().coerceIn(0, fullW)
        val x1 = kotlin.math.ceil((unzoomX(viewW) - left) / sx * pixels).toInt().coerceIn(0, fullW)
        val y0 = kotlin.math.floor((unzoomY(0f) - top) / sy * pixels).toInt().coerceIn(0, fullH)
        val y1 = kotlin.math.ceil((unzoomY(viewH) - top) / sy * pixels).toInt().coerceIn(0, fullH)
        // Where those whole pixels land on the view.
        fun vx(fx: Int) = ((fx.toFloat() / pixels * sx + left) * scale + panX).roundToIntExact()
        fun vy(fy: Int) = ((fy.toFloat() / pixels * sy + top) * scale + panY).roundToIntExact()
        val vh = viewH.roundToIntExact()
        return intArrayOf(x0, fullH - y1, x1, fullH - y0, vx(x0), vh - vy(y1), vx(x1), vh - vy(y0))
    }

    private fun Float.roundToIntExact() = Math.round(this)

    /** For the debug console's touch state. */
    fun probe(): Map<String, Any?> = mapOf(
        "scale" to Math.round(scale * 1000) / 1000.0, "panX" to Math.round(panX), "panY" to Math.round(panY),
        "mode" to mode.name, "view" to listOf(viewW.toInt(), viewH.toInt()),
        "minimap" to minimap()?.let { m -> mapOf(
            "box" to m.box.list(), "bar" to m.bar.list(), "reset" to m.reset.list(), "map" to m.map.list(), "seen" to m.seen.list()) },
        "place" to listOf(placeX, placeY), "barBelow" to barBelow, "size" to settings.size.name, "on" to settings.minimap,
    )

    private fun Box.list() = listOf(Math.round(x), Math.round(y), Math.round(w), Math.round(h))

    companion object {
        const val DOWN = 1
        const val MOVE = 2
        const val UP = 3
        const val CANCEL = 4

        /** The largest zoom. */
        const val MAX = 4f
        /** A two-finger tap at 1x zooms this far. */
        const val TAP_ZOOM = 2f
        /** A pinch that ends below this is 1x. */
        const val SNAP = 1.1f
        /** The first finger waits this long for a second one before the game gets it. */
        const val HOLD_MS = 150L
        /** Two fingers lifted within this of the first one's down, neither moved, are a two-finger tap. */
        const val TAP_MS = 300L
        /** A finger that moves this far (hundredths of the view's height) is a drag: the game gets it at once. */
        const val SLOP = 2.5f
        /** The minimap's bar and margin to the view's edges, the border; hundredths of the view's height. */
        const val BAR = 7f
        const val MARGIN = 2f
        const val BORDER = 0.5f
        /** The 1x button's width in bar heights. */
        const val RESET_W = 1.4f
        /** The minimap's first place: the top right corner. */
        const val DEFAULT_X = 1f
        const val DEFAULT_Y = 0f

        private const val PART_MAP = 0
        private const val PART_BAR = 1
        private const val PART_RESET = 2

        private const val PREFS = "zoom"

        /** Off until the player switches it on. */
        const val DEFAULT_ON = false

        /** Settings > Pinch to zoom. */
        fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", DEFAULT_ON)

        fun setEnabled(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean("on", on).apply()
        }

        fun load(context: Context): Settings {
            val p = prefs(context)
            val size = Size.entries.firstOrNull { it.name == p.getString("size", null) } ?: Size.MEDIUM
            return Settings(p.getBoolean("minimap", true), size, p.getFloat("x", DEFAULT_X), p.getFloat("y", DEFAULT_Y))
        }

        fun save(context: Context, s: Settings) {
            prefs(context).edit().putBoolean("minimap", s.minimap).putString("size", s.size.name)
                .putFloat("x", s.placeX).putFloat("y", s.placeY).apply()
        }

        /** The minimap's settings in a few words: "Medium, top right" or "Off". */
        fun describe(s: Settings): String {
            if (!s.minimap) return "Off"
            val across = when { s.placeX < 1 / 3f -> "left"; s.placeX > 2 / 3f -> "right"; else -> "" }
            val down = when { s.placeY < 1 / 3f -> "top"; s.placeY > 2 / 3f -> "bottom"; else -> "" }
            val place = listOf(down, across).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "middle" }
            return "${s.size.label}, $place"
        }

        /** The game's zoom for the setting: none at all when it is off, so nothing holds a finger back. */
        fun forGame(on: Boolean, settings: Settings = Settings(), onPlaced: (Float, Float) -> Unit = { _, _ -> }): Zoom? =
            if (on) Zoom(settings, onPlaced) else null

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
