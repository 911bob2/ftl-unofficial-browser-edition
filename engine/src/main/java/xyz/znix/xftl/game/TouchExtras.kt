package xyz.znix.xftl.game

import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsTriggeredGui
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaEvents
import xyz.znix.xftl.hs.render.HsEventButtons
import xyz.znix.xftl.hs.render.HsMoreInfo
import xyz.znix.xftl.hs.render.HsRenderEvents
import xyz.znix.xftl.rendering.DrawProbe
import xyz.znix.xftl.rendering.DrawQueue
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * The desktop HUD's other elements in the touch layout (TouchMode). The touch HUD has its own controls for what the iPad
 * had; whatever else the desktop HUD shows comes here, with no code of its own: every button of the desktop HUD's list
 * that the touch HUD does not draw (Hyperspace's event buttons and the crew list's page arrows among them), the Lua
 * drawing of the HUD's render stages (FTL_BUTTON, SHIP_STATUS), Hyperspace's More Info button and triggered-event boxes.
 *
 * Each element is a [Piece], drawn by its own desktop code in desktop coordinates, under a transform that puts it at its
 * touch place. What it drew is measured ([DrawProbe]) and the next frame fits that rectangle into the place; a piece not
 * measured yet is drawn off the canvas. The measure is taken through the piece's own transform, so it is settled
 * ([settle]): float noise in the mapping back never moves the piece, only a real change of what it draws. Pieces on the
 * shelf share one scale (the desktop's sizes relative to each other) and line up by their solid art ([Piece.solid]), not
 * by their images' transparent margins and glow. A finger lifted on a piece is the desktop click at the matching desktop point: a
 * button's own mouseDown, or, for Lua drawing, a mouse that rests there for a frame (the script's tick and render see it)
 * and then presses and releases. While the finger is down the piece sees the mouse there too: hover images and tooltips.
 *
 * Places ([Zone]): event buttons by their `<location>`, in the top row in Hyperspace's order around the ship and store
 * buttons; anything else by where the desktop draws it: the top bar to the top row, the status corner to the shelf under
 * the touch status, the crew corner under the crew list, the rest to the dock above the pause button. A piece drawn in
 * several far-apart places, or larger than a button, keeps the desktop layout, fitted to the canvas.
 */
class TouchExtras(private val ui: PlayerShipUI, private val game: InGameState) {
    enum class Zone { BEFORE_SHIP, AFTER_SHIP, AFTER_STORE, TOP_END, SECOND_ROW, SHELF, SHELF_RIGHT, CREW, CREW_PAGE, DOCK, OVERLAY }

    abstract inner class Piece(val key: String) {
        /** What it drew in the last measured frame, desktop coordinates: x0, y0, x1, y1. Null until measured. */
        var desk: FloatArray? = null

        /** The solid part of what it drew ([DrawProbe.solids]), desktop coordinates; [desk] when nothing showed solid. */
        var solid: FloatArray? = null

        /** The separate places it drew in (rectangles more than [CLUSTER_GAP] apart). */
        var clusters = 0

        /** Those places, desktop coordinates (x0, y0, x1, y1 each). */
        var parts: List<FloatArray> = emptyList()

        /** How often its touch place changed from one drawn frame to the next (for the checks: a settled piece stays). */
        var moves = 0
        private var lastS = Float.NaN
        private var lastOx = Float.NaN
        private var lastOy = Float.NaN

        /** Counts a move when the place differs from the last drawn frame's. */
        fun noteFrame() {
            if (!placed) return
            if (!lastS.isNaN() && (s != lastS || ox != lastOx || oy != lastOy)) moves++
            lastS = s; lastOx = ox; lastOy = oy
        }

        /** Touch place: HUD x = desktop x * [s] + [ox]. */
        var s = 1f
        var ox = OFFSCREEN
        var oy = 0f
        var placed = false
        var frame = 0

        abstract val zone: Zone

        /** Draw at desktop coordinates; [mx], [my] is the desktop mouse while a finger is on it, else -1. */
        abstract fun draw(g: Graphics, mx: Int, my: Int)

        /** The finger lifted at desktop ([dx], [dy]). */
        abstract fun tap(dx: Int, dy: Int)

        /** The finger rests at desktop ([dx], [dy]), or -1 when it left. */
        open fun hover(dx: Int, dy: Int) {}

        fun x0() = desk!![0] * s + ox
        fun y0() = desk!![1] * s + oy
        fun x1() = desk!![2] * s + ox
        fun y1() = desk!![3] * s + oy

        /** The solid part's place in HUD coordinates: x0, y0, x1, y1. */
        fun solidPlace(): FloatArray {
            val d = solid ?: desk!!
            return floatArrayOf(d[0] * s + ox, d[1] * s + oy, d[2] * s + ox, d[3] * s + oy)
        }

        fun contains(x: Int, y: Int): Boolean {
            if (!placed || desk == null) return false
            // The desktop layout fitted to the canvas takes fingers only where it drew, not between its far-apart parts.
            if (zone == Zone.OVERLAY) return parts.any { r ->
                x >= r[0] * s + ox && x < r[2] * s + ox && y >= r[1] * s + oy && y < r[3] * s + oy
            }
            // A small piece takes fingers a little around it.
            val padX = maxOf(0f, (MIN_TARGET - (x1() - x0())) / 2)
            val padY = maxOf(0f, (MIN_TARGET - (y1() - y0())) / 2)
            return x >= x0() - padX && x < x1() + padX && y >= y0() - padY && y < y1() + padY
        }

        fun toDesk(x: Int, y: Int): IntArray {
            val d = desk!!
            val dx = ((x - ox) / s).coerceIn(d[0], d[2] - 1f)
            val dy = ((y - oy) / s).coerceIn(d[1], d[3] - 1f)
            return intArrayOf(dx.toInt(), dy.toInt())
        }
    }

    /** A button of the desktop HUD's list: its draw, its hover and its own mouseDown. */
    inner class ButtonPiece(key: String, var button: Button) : Piece(key) {
        val def: HsEventButtons.Def? get() = HsEventButtons.defOf(button)

        override val zone: Zone
            get() {
                if (button is PlayerShipUI.CrewPage) return Zone.CREW_PAGE
                return zoneForLocation(def?.location) ?: zoneByPlace(this)
            }

        override fun draw(g: Graphics, mx: Int, my: Int) {
            button.update(mx, my)
            button.draw(g)
        }

        override fun hover(dx: Int, dy: Int) = button.update(dx, dy)

        override fun tap(dx: Int, dy: Int) {
            // A finger on the glow around the hit box still presses it.
            val x = if (button.contains(dx, dy)) dx else button.pos.x + button.size.x / 2
            val y = if (button.contains(dx, dy)) dy else button.pos.y + button.size.y / 2
            button.update(x, y)
            button.mouseDown(Input.MOUSE_LEFT_BUTTON, x, y)
            button.update(-1, -1)
        }
    }

    /** A render stage's Lua drawing (its pre and post callbacks; the touch HUD draws its own original). */
    inner class LuaPiece(private val ev: Int) : Piece("lua:$ev") {
        override val zone: Zone get() = when {
            ev == A.RE_FTL_BUTTON -> Zone.SHELF_RIGHT
            // A script's own window (a text box, a panel) keeps the desktop layout, fitted to the canvas.
            drewPanel -> Zone.OVERLAY
            else -> zoneByPlace(this)
        }

        /** It drew a Hyperspace.WindowFrame in the last drawn frame. */
        private var drewPanel = false

        override fun draw(g: Graphics, mx: Int, my: Int) {
            // Its pointers and highlights go to the touch places of what they point at (TouchPointers).
            TouchPointers.anchors = anchorsBut(this)
            TouchPointers.enterPiece(g, s, ox, oy, parts)
            try {
                val i = HsRenderEvents.pre(ev)
                HsRenderEvents.post(ev, i)
            } finally {
                drewPanel = TouchPointers.leavePiece()
            }
        }

        override fun tap(dx: Int, dy: Int) = luaClick(dx, dy)
    }

    /** Hyperspace's More Info button (the magnifier at the bottom right). */
    inner class MoreInfoPiece : Piece("more-info") {
        override val zone: Zone get() = Zone.DOCK
        override fun draw(g: Graphics, mx: Int, my: Int) = HsMoreInfo.render(game, g)
        override fun hover(dx: Int, dy: Int) { HsMoreInfo.mouseMove(game, null, dx, dy, dx < 0) }
        override fun tap(dx: Int, dy: Int) { HsMoreInfo.toggle(game) }
    }

    /** A part of Hyperspace's triggered-event boxes (HsTriggeredGui): timers, warnings. Nothing to press. */
    inner class TriggeredPiece(private val part: Int) : Piece("triggered:$part") {
        override val zone: Zone get() = if (part == HsTriggeredGui.PART_WARNINGS) Zone.OVERLAY else zoneByPlace(this)
        override fun draw(g: Graphics, mx: Int, my: Int) = HsTriggeredGui.renderPart(game, g, part, mx, my)
        override fun tap(dx: Int, dy: Int) {}
    }

    private val pieces = LinkedHashMap<String, Piece>()
    private var frame = 0
    private val probe = DrawProbe()
    private val point = FloatArray(2)

    /** The piece holding the finger, and where on the desktop it is. */
    private var held: Piece? = null
    private var heldX = -1
    private var heldY = -1

    /** A Lua click waiting for a frame drawn with the mouse resting on it: x, y and the drawn frame of the tap. */
    private var luaClickAt: IntArray? = null
    private var drawn = 0

    val all: Collection<Piece> get() = pieces.values

    fun piece(key: String): Piece? = pieces[key]

    /** The pieces this frame: the desktop HUD's buttons and the Hyperspace elements that are on now. */
    fun sync() {
        frame++
        for (b in ui.touchForeignButtons) {
            val key = keyOf(b)
            val p = pieces[key] as? ButtonPiece
            if (p == null) pieces[key] = ButtonPiece(key, b).also { it.frame = frame } else { p.button = b; p.frame = frame }
        }
        if (HS.luaActive) for (ev in LUA_STAGES) if (HsLua.hasRen[ev]) keep("lua:$ev") { LuaPiece(ev) }
        if (HS.active) {
            keep("more-info") { MoreInfoPiece() }
            for (part in TRIGGERED_PARTS) keep("triggered:$part") { TriggeredPiece(part) }
        }
        pieces.values.removeAll { it.frame != frame }
        if (held != null && held !in pieces.values) held = null
    }

    private inline fun keep(key: String, make: () -> Piece) {
        (pieces[key] ?: make().also { pieces[key] = it }).frame = frame
    }

    private fun keyOf(b: Button): String {
        HsEventButtons.defOf(b)?.let { return "event:" + it.name.ifEmpty { "#${it.idx}" } }
        if (b is PlayerShipUI.CrewPage) return "crew-page:" + (if (b.down) "down" else "up")
        return "button:" + b.javaClass.name + "@" + b.basePos.x + "," + b.basePos.y
    }

    private fun zoneByPlace(p: Piece): Zone = zoneForPlace(p.desk, p.clusters)

    /** The HUD elements in both layouts ([TouchPointers]): the touch HUD's own ([hudAnchors]) and the placed pieces but [except]. */
    var hudAnchors: List<TouchPointers.Anchor> = emptyList()

    private fun anchorsBut(except: Piece?): List<TouchPointers.Anchor> {
        val out = ArrayList(hudAnchors)
        for (p in pieces.values) {
            val d = p.desk ?: continue
            if (p === except || !p.placed || p.zone == Zone.OVERLAY) continue
            out += TouchPointers.Anchor("piece:" + p.key, d, p.s, p.s, p.ox, p.oy)
        }
        return out
    }

    // ---- layout --------------------------------------------------------------------------------------------------

    /** Width of a top-row piece at scale 1 (0 before it is measured). */
    fun width(p: Piece): Float = p.desk?.let { it[2] - it[0] } ?: 0f

    /** Puts the measured pieces of [zone] in a row from [x] at the top bar's height; returns the x after them. */
    fun placeTop(zone: Zone, x: Float, scale: Float, deskTop: Float = 22f, touchTop: Float = 1f): Float {
        var at = x
        for (p in pieces.values) {
            if (p.zone != zone) continue
            val d = p.desk ?: continue
            // The desktop draws top-bar buttons' images from y 22 (the second row's from 80); the touch layout's from 1.
            p.s = scale
            p.ox = at - d[0] * scale
            p.oy = touchTop - deskTop * scale
            p.placed = true
            at += (d[2] - d[0]) * scale + 3
        }
        return at
    }

    fun topWidth(zone: Zone): Float = pieces.values.filter { it.zone == zone && it.desk != null }.sumOf { (width(it) + 3).toDouble() }.toFloat()

    /**
     * Places everything but the top row. The shelf runs under the touch status from [shelfLeft] to [shelfRight] (the
     * FTL_BUTTON drawing at its right end), the crew zone under the crew list's station buttons at [crewY], the dock
     * above the pause button.
     */
    fun placeRest(crewX: Int, crewY: Int, stationsRight: Int, stationsY: Int, canvasW: Int, hudX: Int) {
        // Shelf: right end first, its solid art's right edge at the end and its middle on the shelf's.
        var right = SHELF_RIGHT.toFloat()
        for (p in zoned(Zone.SHELF_RIGHT)) {
            val sd = p.solid!!
            fit(p, SHELF_H, 1.3f)
            p.ox = right - sd[2] * p.s
            p.oy = SHELF_Y + SHELF_H / 2 - (sd[1] + sd[3]) / 2 * p.s
            right = p.solidPlace()[0] - SHELF_GAP
        }
        shelfRow(zoned(Zone.SHELF), SHELF_LEFT.toFloat(), right, SHELF_Y.toFloat(), SHELF_H, 1.25f)
        // Crew page arrows beside the station buttons, the rest under them.
        var x = stationsRight + 8f
        for (p in zoned(Zone.CREW_PAGE)) {
            val d = p.desk!!
            fit(p, 36f, 2.5f)
            p.ox = x - d[0] * p.s
            p.oy = stationsY + 18 - (d[1] + d[3]) / 2 * p.s
            x = p.x1() + 4
        }
        row(zoned(Zone.CREW), crewX.toFloat(), crewX + CREW_W, crewY.toFloat(), CREW_H, 1.25f)
        // Dock: right to left above the pause button.
        rowRight(zoned(Zone.DOCK), DOCK_LEFT, DOCK_RIGHT, DOCK_BOTTOM, DOCK_H, 1.25f)
        // The desktop layout fitted to the canvas.
        val o = overlayPlace(canvasW, hudX)
        for (p in zoned(Zone.OVERLAY)) {
            p.s = o[0]
            p.ox = o[1]
            p.oy = o[2]
        }
    }

    /** The desktop layout fitted to the canvas: scale, x and y offsets in HUD block coordinates. */
    private fun overlayPlace(canvasW: Int = TouchMode.canvasW, hudX: Int = TouchMode.hudX): FloatArray {
        val fs = minOf(canvasW / 1280f, TouchMode.HUD_H / 720f)
        return floatArrayOf(fs, (canvasW - 1280 * fs) / 2 - hudX, (TouchMode.HUD_H - 720 * fs) / 2)
    }

    private fun zoned(zone: Zone) = pieces.values.filter { it.zone == zone && it.desk != null }.onEach { it.placed = true }

    private fun fit(p: Piece, h: Float, most: Float) {
        val d = p.desk!!
        p.s = minOf(most, h / maxOf(1f, d[3] - d[1]))
    }

    private fun shelfRow(list: List<Piece>, left: Float, right: Float, top: Float, h: Float, most: Float) {
        val places = shelfLayout(list.map { it.desk!! }, list.map { it.solid!! }, left, right, top, h, most)
        for ((p, place) in list.zip(places)) {
            p.s = place[0]
            p.ox = place[1]
            p.oy = place[2]
        }
    }

    /** Left to right from [left], centred on the row; all smaller when they do not fit before [right]. */
    private fun row(list: List<Piece>, left: Float, right: Float, top: Float, h: Float, most: Float) {
        if (list.isEmpty()) return
        for (p in list) fit(p, h, most)
        val need = list.sumOf { ((it.desk!![2] - it.desk!![0]) * it.s + 4).toDouble() }.toFloat()
        val k = if (need > right - left) maxOf(0.4f, (right - left) / need) else 1f
        var x = left
        for (p in list) {
            val d = p.desk!!
            p.s *= k
            p.ox = x - d[0] * p.s
            p.oy = top + (h - (d[3] - d[1]) * p.s) / 2 - d[1] * p.s
            x = p.x1() + 4
        }
    }

    /** Right to left from [right], bottoms on [bottom]. */
    private fun rowRight(list: List<Piece>, left: Float, right: Float, bottom: Float, h: Float, most: Float) {
        if (list.isEmpty()) return
        for (p in list) fit(p, h, most)
        val need = list.sumOf { ((it.desk!![2] - it.desk!![0]) * it.s + 4).toDouble() }.toFloat()
        val k = if (need > right - left) maxOf(0.4f, (right - left) / need) else 1f
        var x = right
        for (p in list) {
            val d = p.desk!!
            p.s *= k
            p.ox = x - d[2] * p.s
            p.oy = bottom - d[3] * p.s
            x = p.x0() - 4
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    /** Before placing: nothing is placed until a zone takes it. */
    fun beginLayout() {
        for (p in pieces.values) p.placed = false
    }

    /** Draws every piece and measures it; call in HUD block coordinates. */
    fun render(g: Graphics) {
        drawn++
        TouchPointers.frameAnchors = anchorsBut(null)
        TouchPointers.overlay = overlayPlace()
        for (p in pieces.values.toList()) {
            if (!p.placed || p.desk == null) { p.ox = OFFSCREEN; p.oy = 0f; p.s = 1f }
            val mine = held === p
            g.pushTransform()
            g.translate(p.ox, p.oy)
            g.scale(p.s, p.s)
            g.flushAll()
            p.noteFrame()
            probe.reset()
            DrawQueue.probe = probe
            try {
                p.draw(g, if (mine) heldX else -1, if (mine) heldY else -1)
                g.flushAll()
            } finally {
                DrawQueue.probe = null
            }
            probe.end()
            measure(g, p)
            g.popTransform()
        }
    }

    private fun measure(g: Graphics, p: Piece) {
        if (probe.rects.isEmpty()) {
            p.desk = null
            p.solid = null
            p.clusters = 0
            p.parts = emptyList()
            p.placed = false
            return
        }
        val rects = probe.rects.map { unmapRect(g, it) }
        val groups = cluster(rects)
        val desk = settle(p.desk, union(groups))
        p.desk = desk
        p.parts = groups
        p.solid = if (probe.solids.isEmpty()) desk else settle(p.solid, union(probe.solids.map { unmapRect(g, it) }))
        p.clusters = groups.size
    }

    private fun unmapRect(g: Graphics, r: FloatArray): FloatArray {
        g.unmap(r[0], r[1], point)
        val ax = point[0]
        val ay = point[1]
        g.unmap(r[2], r[3], point)
        return floatArrayOf(minOf(ax, point[0]), minOf(ay, point[1]), maxOf(ax, point[0]), maxOf(ay, point[1]))
    }

    // ---- fingers -------------------------------------------------------------------------------------------------

    /** The topmost piece under ([x], [y]) in HUD coordinates. */
    fun at(x: Int, y: Int): Piece? {
        // A button under the edge of a script's text box still takes the finger, as the mouse reaches it on the desktop.
        val under = pieces.values.filter { it.contains(x, y) && it !is TriggeredPiece }
        return under.lastOrNull { it.zone != Zone.OVERLAY } ?: under.lastOrNull()
    }

    /** True while a piece holds the finger. */
    fun touch(action: Int, x: Int, y: Int): Boolean {
        when (action) {
            TouchRouter.DOWN -> {
                val p = at(x, y) ?: return false
                held = p
                follow(p, x, y)
                return true
            }
            TouchRouter.MOVE -> {
                val p = held ?: return false
                if (p.contains(x, y)) follow(p, x, y) else leave()
                return true
            }
            TouchRouter.UP -> {
                val p = held ?: return false
                if (p.contains(x, y)) {
                    val d = p.toDesk(x, y)
                    p.tap(d[0], d[1])
                }
                if (p !is LuaPiece) leave() else held = null
                return true
            }
            else -> {
                val had = held != null
                leave()
                return had
            }
        }
    }

    private fun follow(p: Piece, x: Int, y: Int) {
        val d = p.toDesk(x, y)
        heldX = d[0]
        heldY = d[1]
        p.hover(heldX, heldY)
        if (p is LuaPiece) luaMouse(heldX, heldY)
    }

    private fun leave() {
        held?.hover(-1, -1)
        if (held is LuaPiece && luaClickAt == null) luaMouse(-1, -1)
        held = null
        heldX = -1
        heldY = -1
    }

    /** A Lua piece's click: the mouse rests at the point for a frame, then presses and releases there. */
    private fun luaClick(dx: Int, dy: Int) {
        luaClickAt = intArrayOf(dx, dy, drawn)
        luaMouse(dx, dy)
    }

    private fun luaMouse(x: Int, y: Int) {
        if (!HS.luaActive) return
        LuaEvents.mouseDesk(x, y, LuaEvents.mouseX, LuaEvents.mouseY, 0, 0, false, false, false)
    }

    /**
     * Each update, before the game's tick: the resting mouse of a held Lua piece, and a waiting Lua click, which goes once
     * a frame has been drawn (and so ticked) since the tap.
     */
    fun update() {
        val click = luaClickAt
        if (click != null) {
            luaMouse(click[0], click[1])
            if (drawn > click[2]) {
                luaClickAt = null
                if (HS.luaActive) {
                    LuaEvents.mouseDesk(click[0], click[1], click[0], click[1], 1, 0, true, false, false)
                    LuaEvents.mouseDesk(click[0], click[1], click[0], click[1], 2, 0, false, false, false)
                }
                luaMouse(-1, -1)
            }
            return
        }
        val p = held
        if (p is LuaPiece && heldX >= 0) luaMouse(heldX, heldY)
    }

    companion object {
        private const val OFFSCREEN = -100_000f
        private const val CLUSTER_GAP = 24f
        private const val MIN_TARGET = 40f

        const val SHELF_LEFT = 8
        const val SHELF_RIGHT = 506
        const val SHELF_Y = 63
        const val SHELF_H = 46f

        /** Between two shelf pieces' solid art: room for both images' glow (5 px each on Multiverse's buttons). */
        const val SHELF_GAP = 12f
        const val CREW_W = 200f
        const val CREW_H = 46f
        const val DOCK_LEFT = 846f
        const val DOCK_RIGHT = 1020f
        const val DOCK_BOTTOM = 682f
        const val DOCK_H = 56f

        private val LUA_STAGES = intArrayOf(A.RE_FTL_BUTTON, A.RE_SHIP_STATUS)
        private val TRIGGERED_PARTS = intArrayOf(
            HsTriggeredGui.PART_DEFAULT, HsTriggeredGui.PART_SCRAP, HsTriggeredGui.PART_TOP_RIGHT, HsTriggeredGui.PART_WARNINGS
        )

        /** An event button's zone by its `<location>` (ShipStatus::OnLoop's order); null for a customLocation one. */
        @JvmStatic
        fun zoneForLocation(location: Int?): Zone? = when (location) {
            null, -1 -> null
            0 -> Zone.SECOND_ROW
            1 -> Zone.BEFORE_SHIP
            2 -> Zone.AFTER_SHIP
            3 -> Zone.AFTER_STORE
            else -> Zone.TOP_END
        }

        /**
         * Where a piece without a place of its own goes, by what it drew on the desktop ([desk]: x0, y0, x1, y1): the top
         * bar to the top row, the status corner to the shelf, the crew corner under the crew list, anything else to the
         * dock; drawn in several places or larger than a button, the desktop layout fitted to the canvas.
         */
        @JvmStatic
        fun zoneForPlace(desk: FloatArray?, clusters: Int): Zone {
            val d = desk ?: return Zone.DOCK
            if (clusters > 1 || d[2] - d[0] > 400 || d[3] - d[1] > 250) return Zone.OVERLAY
            val cx = (d[0] + d[2]) / 2
            val cy = (d[1] + d[3]) / 2
            return when {
                cy < 100 && cx >= 520 && cx < 1000 -> Zone.TOP_END
                cx < 520 && cy < 175 -> Zone.SHELF
                cx < 300 && cy < 500 -> Zone.CREW
                else -> Zone.DOCK
            }
        }

        /** How far a new measure may differ from the one in use before the piece moves: float noise, not new drawing. */
        private const val SETTLE = 0.05f

        /**
         * The measured rectangle [raw] (desktop coordinates, mapped back from the framebuffer through the piece's transform)
         * against the one in use, [old]: kept when every edge is within [SETTLE], so the mapping's float noise (529.9999 one
         * frame, 530.0001 the next) never moves the piece and so never changes the next measure; else the new one, each edge
         * on its whole pixel when that close to one.
         */
        @JvmStatic
        fun settle(old: FloatArray?, raw: FloatArray): FloatArray {
            if (old != null && (0..3).all { Math.abs(old[it] - raw[it]) < SETTLE }) return old
            return FloatArray(4) { i ->
                val r = Math.round(raw[i]).toFloat()
                if (Math.abs(raw[i] - r) < SETTLE) r else raw[i]
            }
        }

        /**
         * The shelf's places (scale, x offset, y offset each) for pieces that drew [desks] with [solids] solid: left to
         * right from [left], one scale for all (the desktop's sizes relative to each other: the largest that fits the
         * tallest image in [h], at most [most]), each one's solid art centred on the shelf's middle line and [SHELF_GAP]
         * from the next one's; all smaller when they do not fit before [right].
         */
        @JvmStatic
        fun shelfLayout(desks: List<FloatArray>, solids: List<FloatArray>, left: Float, right: Float, top: Float, h: Float,
                        most: Float): List<FloatArray> {
            if (desks.isEmpty()) return emptyList()
            val tallest = desks.maxOf { it[3] - it[1] }
            var scale = minOf(most, h / maxOf(1f, tallest))
            val art = solids.sumOf { (it[2] - it[0]).toDouble() }.toFloat()
            val room = right - left - SHELF_GAP * solids.size
            if (art * scale > room) scale = maxOf(scale * 0.4f, room / art)
            var x = left + SHELF_GAP / 2
            return solids.map { sd ->
                val place = floatArrayOf(scale, x - sd[0] * scale, top + h / 2 - (sd[1] + sd[3]) / 2 * scale)
                x += (sd[2] - sd[0]) * scale + SHELF_GAP
                place
            }
        }

        /** The rectangle around all of [list] (x0, y0, x1, y1 each). */
        @JvmStatic
        fun union(list: List<FloatArray>): FloatArray {
            val u = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (r in list) {
                u[0] = minOf(u[0], r[0]); u[1] = minOf(u[1], r[1]); u[2] = maxOf(u[2], r[2]); u[3] = maxOf(u[3], r[3])
            }
            return u
        }

        /** Joins rectangles closer than [CLUSTER_GAP] into one each. */
        @JvmStatic
        fun cluster(rects: List<FloatArray>): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            for (r in rects) out += r.copyOf()
            var merged = true
            while (merged) {
                merged = false
                loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                    val a = out[i]
                    val b = out[j]
                    if (a[0] - CLUSTER_GAP <= b[2] && b[0] - CLUSTER_GAP <= a[2] && a[1] - CLUSTER_GAP <= b[3] && b[1] - CLUSTER_GAP <= a[3]) {
                        a[0] = minOf(a[0], b[0]); a[1] = minOf(a[1], b[1]); a[2] = maxOf(a[2], b[2]); a[3] = maxOf(a[3], b[3])
                        out.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
            return out
        }
    }
}
