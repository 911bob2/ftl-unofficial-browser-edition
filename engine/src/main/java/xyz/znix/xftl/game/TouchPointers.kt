package xyz.znix.xftl.game

import xyz.znix.xftl.math.Matrix3f
import xyz.znix.xftl.rendering.DrawQueue
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pointers and highlights a mod draws over the HUD for the desktop layout, drawn in the touch layout (TouchMode) at the
 * touch place of the HUD element they point at, with no code per mod or per element.
 *
 * The touch HUD knows where each of its elements is in both layouts ([Anchor]: the desktop rectangle and the mapping to
 * the touch one): the crew boxes, the top buttons, the player's ship, the target box and every piece [TouchExtras] moves
 * (Hyperspace's event buttons, More Info, a script's HUD drawing). A script draws in desktop coordinates; inside a
 * [TouchExtras] Lua piece, two of its draws are pointers:
 * - Hyperspace's TutorialArrow ([arrow]): it points at the element its tip reaches (inside it, or the first one ahead of
 *   it within [REACH]); drawn at the same angle with its tip as far from that element's touch place as it was on the
 *   desktop.
 * - a rectangle outline (CSurface.GL_DrawRectOutline, [rect]) over an element: drawn around that element's touch place.
 *   An outline over several elements of one kind (the crew boxes) spans their touch places.
 * Either drawn over nothing the HUD knows keeps the desktop layout with the rest of the piece. What was mapped in the last
 * drawn frame is in [drawn], for the checks.
 */
object TouchPointers {
    /** A HUD element: [desk] is its desktop rectangle (x0, y0, x1, y1); its touch place is desk * s + o, HUD block coordinates. */
    class Anchor(@JvmField val key: String, @JvmField val desk: FloatArray, @JvmField val sx: Float, @JvmField val sy: Float,
                 @JvmField val ox: Float, @JvmField val oy: Float) {
        fun tx(x: Float) = x * sx + ox
        fun ty(y: Float) = y * sy + oy
        fun touch() = floatArrayOf(tx(desk[0]), ty(desk[1]), tx(desk[2]), ty(desk[3]))
        val kind: String get() = key.substringBefore(':')

        /** True when ([x], [y]) is in the desktop rectangle grown by [pad]. */
        fun contains(x: Float, y: Float, pad: Float = 0f) =
            x >= desk[0] - pad && x <= desk[2] + pad && y >= desk[1] - pad && y <= desk[3] + pad

        companion object {
            /** The element [desk] at [touch] (both x0, y0, x1, y1): each axis scaled and moved to fit. */
            @JvmStatic
            fun fit(key: String, desk: FloatArray, touch: FloatArray): Anchor {
                val sx = (touch[2] - touch[0]) / maxOf(1f, desk[2] - desk[0])
                val sy = (touch[3] - touch[1]) / maxOf(1f, desk[3] - desk[1])
                return Anchor(key, desk, sx, sy, touch[0] - desk[0] * sx, touch[1] - desk[1] * sy)
            }

            /** The element [desk] moved by ([dx], [dy]). */
            @JvmStatic
            fun shift(key: String, desk: FloatArray, dx: Float, dy: Float) = Anchor(key, desk, 1f, 1f, dx, dy)
        }
    }

    /** A pointer drawn at its element's touch place: what it was ("arrow", "rect"), its desktop and touch places, the element. */
    class Drawn(@JvmField val kind: String, @JvmField val desk: FloatArray, @JvmField val touch: FloatArray, @JvmField val anchor: String,
                @JvmField val anchorTouch: FloatArray)

    /** The touch HUD's elements this frame (set by [TouchHud] before its pieces draw). */
    @JvmField var anchors: List<Anchor> = emptyList()

    /** Every element of the last drawn frame: the touch HUD's and TouchExtras' placed pieces. */
    @JvmField var frameAnchors: List<Anchor> = emptyList()

    /** The desktop layout fitted to the canvas (TouchExtras' overlay): scale, x and y offsets, HUD block coordinates. */
    @JvmField var overlay: FloatArray = floatArrayOf(1f, 0f, 0f)

    /**
     * Where a script reads the mouse for the canvas point ([x], [y]) (Hyperspace's MouseControl::position): the desktop
     * layout's point of what is under it. Over a window, the window's point where the desktop places the window; in flight,
     * the point of the HUD element under it ([frameAnchors]), else the overlay's (the desktop layout fitted to the
     * canvas). Elsewhere (menus, the hangar) and off the canvas, the point itself.
     */
    @JvmStatic
    fun deskMouse(x: Int, y: Int): IntArray? {
        if (x < 0 || y < 0) return null
        val game = (xyz.znix.xftl.hs.lua.HsLua.game?.currentState as? InGameState) ?: return null
        val ui = game.shipUI as? PlayerShipUI ?: return null
        ui.touchTopWindow?.let { w -> val p = w.desktopPoint(x, y); return intArrayOf(p.x, p.y) }
        val hx = (x - TouchMode.hudX).toFloat()
        val hy = y.toFloat()
        val a = frameAnchors.filter { val t = it.touch(); hx >= t[0] && hx < t[2] && hy >= t[1] && hy < t[3] }
            .minByOrNull { area(it.desk) }
        if (a != null) return intArrayOf(((hx - a.ox) / a.sx).toInt(), ((hy - a.oy) / a.sy).toInt())
        val o = overlay
        return intArrayOf(((hx - o[1]) / o[0]).toInt(), ((hy - o[2]) / o[0]).toInt())
    }

    /** The pointers of the last drawn frame. */
    @JvmStatic val drawn: List<Drawn> get() = last
    private var last: List<Drawn> = emptyList()
    private var frame = ArrayList<Drawn>()

    // The Lua piece drawing now: its base transform (HUD block * its place) and its place.
    private var base: Matrix3f? = null
    private var pieceS = 1f
    private var pieceParts: List<FloatArray> = emptyList()
    private var pieceOx = 0f
    private var pieceOy = 0f
    private val point = FloatArray(2)

    /** A new frame: the last one's pointers become [drawn]. */
    fun beginFrame() {
        last = frame
        frame = ArrayList()
    }

    /** A Lua piece starts drawing under [g]'s transform, placed at HUD = desk * [s] + ([ox], [oy]). */
    fun enterPiece(g: Graphics, s: Float, ox: Float, oy: Float, parts: List<FloatArray> = emptyList()) {
        pieceParts = parts.map { r -> floatArrayOf(r[0] * s + ox, r[1] * s + oy, r[2] * s + ox, r[3] * s + oy) }
        base = g.copyTransform()
        drewPanel = false
        pieceS = s
        pieceOx = ox
        pieceOy = oy
    }

    /** The Lua piece is done; true when it drew a window frame ([panel]). */
    fun leavePiece(): Boolean {
        base = null
        return drewPanel.also { drewPanel = false }
    }

    private var drewPanel = false

    /** A script drew a Hyperspace.WindowFrame: inside a Lua piece, a panel or text box of its own. */
    @JvmStatic
    fun panel() {
        if (base != null) drewPanel = true
    }

    /** True while a draw can be remapped (a Lua piece of the touch HUD is drawing). */
    @JvmStatic
    val active: Boolean get() = base != null && anchors.isNotEmpty()

    /** [g]'s point ([x], [y]) in desktop coordinates: through the live transform, back through the piece's. */
    private fun desk(g: Graphics, x: Float, y: Float): FloatArray {
        g.map(x, y, point)
        val b = base!!
        val det = b.m00 * b.m11 - b.m01 * b.m10
        val dx = point[0] - b.m02
        val dy = point[1] - b.m12
        return floatArrayOf((b.m11 * dx - b.m01 * dy) / det, (-b.m10 * dx + b.m00 * dy) / det)
    }

    /**
     * A TutorialArrow of size [w] x [h] at ([x], [y]) turned [degrees] about its centre (its tip at the right end before
     * turning): true when it was drawn at an element's touch place by [draw] (given the new x, y in HUD block coordinates),
     * false when the caller draws it as it is.
     */
    @JvmStatic
    fun arrow(g: Graphics, x: Float, y: Float, w: Float, h: Float, degrees: Float, draw: (Float, Float, Float) -> Unit): Boolean {
        if (!active) return false
        val c = desk(g, x + w / 2, y + h / 2)
        val r = Math.toRadians(degrees.toDouble())
        val dx = cos(r).toFloat()
        val dy = sin(r).toFloat()
        val tipX = c[0] + dx * w / 2
        val tipY = c[1] + dy * w / 2
        val hit = arrowTarget(anchors, tipX, tipY, dx, dy) ?: return false
        val (a, reach) = hit
        // Where the arrow's line enters the element (its edge, not the slack around it), and the tip as far before it as
        // on the desktop.
        val dist = enter(a.desk, tipX, tipY, dx, dy, 0f) ?: reach
        val ex = tipX + dx * dist
        val ey = tipY + dy * dist
        // The touch HUD packs its elements closer than the desktop: the tip stays near the element it points at.
        val gap = minOf(dist, GAP)
        val p = placeArrow(a.tx(ex) - dx * gap, a.ty(ey) - dy * gap, degrees, gap, a.touch(), w, h, pieceParts,
            floatArrayOf(-TouchMode.hudX.toFloat(), 0f, (TouchMode.canvasW - TouchMode.hudX).toFloat(), TouchMode.HUD_H.toFloat()))
        val (nx, ny, deg) = Triple(p[0], p[1], p[2])
        val r2 = Math.toRadians(deg.toDouble())
        val ndx = cos(r2).toFloat()
        val ndy = sin(r2).toFloat()
        inHud(g) { draw(nx - ndx * w / 2 - w / 2, ny - ndy * w / 2 - h / 2, deg) }
        frame += Drawn("arrow", floatArrayOf(tipX, tipY), floatArrayOf(nx, ny), a.key, a.touch())
        return true
    }

    /**
     * A rectangle outline ([x], [y], [w], [h], line [lw]) over an element: true when it was drawn around the element's
     * touch place by [draw] (given x, y, w, h in HUD block coordinates), false when the caller draws it as it is.
     */
    @JvmStatic
    fun rect(g: Graphics, x: Float, y: Float, w: Float, h: Float, draw: (Float, Float, Float, Float) -> Unit): Boolean {
        if (!active) return false
        val p0 = desk(g, x, y)
        val p1 = desk(g, x + w, y + h)
        val r = floatArrayOf(minOf(p0[0], p1[0]), minOf(p0[1], p1[1]), maxOf(p0[0], p1[0]), maxOf(p0[1], p1[1]))
        val t = mapRect(anchors, r) ?: return false
        inHud(g) { draw(t[0], t[1], t[2] - t[0], t[3] - t[1]) }
        frame += Drawn("rect", r, t.r, t.anchor, t.anchorTouch)
        return true
    }

    private class Mapped(val r: FloatArray, val anchor: String, val anchorTouch: FloatArray) {
        operator fun get(i: Int) = r[i]
    }

    private inline fun inHud(g: Graphics, body: () -> Unit) {
        val probe = DrawQueue.probe
        g.flushAll()
        DrawQueue.probe = null
        g.pushTransform()
        try {
            g.loadTransform(base!!)
            g.scale(1f / pieceS, 1f / pieceS)
            g.translate(-pieceOx, -pieceOy)
            body()
            g.flushAll()
        } finally {
            g.popTransform()
            DrawQueue.probe = probe
        }
    }

    /**
     * Where an arrow of [w] x [h] pointing at [element] (HUD x0, y0, x1, y1) goes: with its tip at ([tipX], [tipY]) at
     * [degrees] as the desktop had it, unless its body would lie over the script's own drawing ([parts], its text boxes);
     * then from the side of the element (nearest the desktop's direction first) where it covers the least of that drawing
     * without leaving [bounds], its tip [gap] from the element's edge. Answers tip x, tip y and the angle.
     */
    @JvmStatic
    fun placeArrow(tipX: Float, tipY: Float, degrees: Float, gap: Float, element: FloatArray, w: Float, h: Float,
                   parts: List<FloatArray>, bounds: FloatArray): FloatArray {
        fun body(tx: Float, ty: Float, deg: Float): FloatArray {
            val r = Math.toRadians(deg.toDouble())
            val bx = tx - cos(r).toFloat() * w
            val by = ty - sin(r).toFloat() * w
            val hw = Math.abs(sin(r).toFloat()) * h / 2
            val hh = Math.abs(cos(r).toFloat()) * h / 2
            return floatArrayOf(minOf(tx, bx) - hw, minOf(ty, by) - hh, maxOf(tx, bx) + hw, maxOf(ty, by) + hh)
        }
        // How much of the script's drawing the body covers; leaving the bounds rules a place out.
        fun cost(b: FloatArray): Float {
            if (b[0] < bounds[0] || b[1] < bounds[1] || b[2] > bounds[2] || b[3] > bounds[3]) return Float.MAX_VALUE
            return parts.sumOf { overlap(it, b).toDouble() }.toFloat()
        }
        var best = floatArrayOf(tipX, tipY, degrees)
        var bestCost = cost(body(tipX, tipY, degrees))
        if (bestCost == 0f) return best
        val cx = (element[0] + element[2]) / 2
        val cy = (element[1] + element[3]) / 2
        val sides = listOf(0f, 90f, 180f, 270f).sortedBy { val d = Math.abs(((it - degrees) % 360 + 540) % 360 - 180); d }
        for (deg in sides) {
            val (tx, ty) = when (deg) {
                0f -> element[0] - gap to cy
                90f -> cx to element[1] - gap
                180f -> element[2] + gap to cy
                else -> cx to element[3] + gap
            }
            val c = cost(body(tx, ty, deg))
            if (c < bestCost) {
                best = floatArrayOf(tx, ty, deg)
                bestCost = c
                if (c == 0f) break
            }
        }
        return best
    }

    /** How far ahead of its tip an arrow may point at an element (desktop pixels). */
    const val REACH = 120f

    /** The most room between a moved arrow's tip and its element. */
    const val GAP = 10f

    /** Slack around an element for an arrow's line (its art is 38 px thick). */
    const val SLACK = 8f

    /**
     * The element an arrow with its tip at ([tipX], [tipY]) pointing along ([dx], [dy]) points at, and how far ahead of the
     * tip its line enters it: the one the tip is in (0), else the first its line reaches within [REACH].
     */
    @JvmStatic
    fun arrowTarget(anchors: List<Anchor>, tipX: Float, tipY: Float, dx: Float, dy: Float): Pair<Anchor, Float>? {
        // The smallest element holding the tip: a button on the shelf before the shelf's other drawing.
        anchors.filter { it.contains(tipX, tipY) }.minByOrNull { area(it.desk) }?.let { return it to 0f }
        var best: Anchor? = null
        var bestT = Float.MAX_VALUE
        for (a in anchors) {
            val t = enter(a.desk, tipX, tipY, dx, dy, SLACK) ?: continue
            if (t <= REACH && (t < bestT - 0.5f || (t < bestT + 0.5f && best != null && area(a.desk) < area(best.desk)))) {
                best = a
                bestT = t
            }
        }
        return best?.let { it to bestT }
    }

    /** Where along the ray from ([x], [y]) along ([dx], [dy]) it enters [r] grown by [pad]; null when it misses. */
    private fun enter(r: FloatArray, x: Float, y: Float, dx: Float, dy: Float, pad: Float): Float? {
        var t0 = 0f
        var t1 = Float.MAX_VALUE
        for (axis in 0..1) {
            val o = if (axis == 0) x else y
            val d = if (axis == 0) dx else dy
            val lo = r[axis] - pad
            val hi = r[axis + 2] + pad
            if (Math.abs(d) < 1e-6f) {
                if (o < lo || o > hi) return null
            } else {
                var a = (lo - o) / d
                var b = (hi - o) / d
                if (a > b) { val s = a; a = b; b = s }
                t0 = maxOf(t0, a)
                t1 = minOf(t1, b)
                if (t0 > t1) return null
            }
        }
        return t0
    }

    private fun area(r: FloatArray) = (r[2] - r[0]) * (r[3] - r[1])

    private fun overlap(a: FloatArray, b: FloatArray): Float {
        val w = minOf(a[2], b[2]) - maxOf(a[0], b[0])
        val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
        return if (w > 0 && h > 0) w * h else 0f
    }

    /**
     * The touch place of an outline over [r] (desktop x0, y0, x1, y1): the elements it covers at least half of (or that
     * cover at least half of it), of the kind it covers most; each corner moved with the element it is nearest.
     */
    private fun mapRect(anchors: List<Anchor>, r: FloatArray): Mapped? {
        val covered = anchors.filter { val o = overlap(it.desk, r); o > 0 && o >= 0.5f * minOf(area(it.desk), area(r)) }
        if (covered.isEmpty()) return null
        val kind = covered.groupBy { it.kind }.maxByOrNull { (_, list) -> list.sumOf { overlap(it.desk, r).toDouble() } }!!.key
        val list = covered.filter { it.kind == kind }
        fun nearest(x: Float, y: Float) = list.minByOrNull { a ->
            val cx = x.coerceIn(a.desk[0], a.desk[2]) - x
            val cy = y.coerceIn(a.desk[1], a.desk[3]) - y
            cx * cx + cy * cy
        }!!
        val a0 = nearest(r[0], r[1])
        val a1 = nearest(r[2], r[3])
        val t = floatArrayOf(a0.tx(r[0]), a0.ty(r[1]), a1.tx(r[2]), a1.ty(r[3]))
        val u = list.map { it.touch() }
        val ut = floatArrayOf(u.minOf { it[0] }, u.minOf { it[1] }, u.maxOf { it[2] }, u.maxOf { it[3] })
        return Mapped(t, list.joinToString("+") { it.key }, ut)
    }

    /** For the checks: [mapRect] on its own. */
    @JvmStatic
    fun mapRectForTest(anchors: List<Anchor>, r: FloatArray): FloatArray? = mapRect(anchors, r)?.r
}
