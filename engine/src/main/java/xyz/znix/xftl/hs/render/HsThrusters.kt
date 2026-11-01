// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomShips.cpp (Ship::OnInit <thrusters>,
// Ship::RenderEngineAnimation, Ship::OnRenderBase)
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.rendering.Colour

/**
 * A ship layout's `<thrusters sync="..."><thruster x y rotate irotate mirror>anim</thruster>...</thrusters>` in
 * `data/<layout>.xml` (Ship::OnInit): each thruster is the named animation (empty: the vanilla
 * `effects/thrusters_on.png`, 4 frames of 22x70 over 0.5 s) at (x, y) from the hull image, looping from a random
 * point (all from the start with `sync`), mirrored with `mirror`, turned +90 degrees (`rotate`) or -90 (`irotate`).
 * Any thruster turns the ship's engines on (bShowEngines). Without `<thrusters>` the vanilla engines stay as they
 * are. Everything is drawn inside the SHIP_ENGINES render event (args Ship, showEngines, alpha).
 */
object HsThrusters {
    class Thruster(@JvmField val anim: String, @JvmField val x: Float, @JvmField val y: Float,
                   @JvmField val rot: Int, @JvmField val mirror: Boolean)

    class Layout(@JvmField val sync: Boolean, @JvmField val thrusters: List<Thruster>)

    /** Per ship: the layout's thrusters and each one's loop time. */
    private class State(val layout: Layout?, val specs: List<AnimationSpec?>, val times: FloatArray)

    private var cacheFor: HsConfig? = null
    private val layouts = HashMap<String, Layout?>()

    private val NO_LAYOUT = State(null, emptyList(), FloatArray(0))
    private val tint = Colour(1f, 1f, 1f, 1f)

    /** The `<thrusters>` of `data/<layout>.xml`, or null when the file or the element is absent. */
    @JvmStatic
    fun layout(ship: Ship, name: String): Layout? {
        val cfg = HS.cfg()
        if (cacheFor !== cfg) { layouts.clear(); cacheFor = cfg }
        if (layouts.containsKey(name)) return layouts[name]
        val df = ship.sys.content.datafile
        val file = df.getOrNull("data/$name.xml")
        val l = file?.let {
            xyz.znix.xftl.hs.HsLoad.item("data/$name.xml", { "thrusters" }) { parse(df.parseXML(it).rootElement) }
        }
        layouts[name] = l
        return l
    }

    /** Ship::OnInit's scan: the root's (or the wrapped `<FTL>`'s) `<thrusters>` children, later ones appended. */
    @JvmStatic
    fun parse(root: org.jdom2.Element): Layout? {
        var has = false
        var sync = false
        val list = ArrayList<Thruster>()
        val nodes = if (root.name == "FTL") root.children else listOf(root)
        for (node in nodes) {
            if (node.name != "thrusters") continue
            has = true
            node.getAttributeValue("sync")?.let { sync = HsConfig.bool(it) }
            for (c in node.children) {
                if (c.name != "thruster") continue
                var rot = 0
                if (c.getAttributeValue("rotate")?.let { HsConfig.bool(it) } == true) rot = 1
                if (c.getAttributeValue("irotate")?.let { HsConfig.bool(it) } == true) rot = -1
                list += Thruster(c.text, c.getAttributeValue("x")?.trim()?.toFloat() ?: 0f,
                    c.getAttributeValue("y")?.trim()?.toFloat() ?: 0f, rot,
                    c.getAttributeValue("mirror")?.let { HsConfig.bool(it) } ?: false)
            }
        }
        return if (has) Layout(sync, list) else null
    }

    private fun state(ship: Ship): State {
        (ship.hsRender as? State)?.let { return it }
        val l = layout(ship, ship.type.layout)
        val st = if (l == null) NO_LAYOUT else State(l,
            l.thrusters.map { t -> if (t.anim.isEmpty()) null else ship.sys.animations.getOrNull(t.anim) },
            // Animation::RandomStart, presentation only (as the engine's vanilla thrusters): not the game's RNG
            FloatArray(l.thrusters.size) { if (l.sync) 0f else Math.random().toFloat() })
        ship.hsRender = st
        return st
    }

    /**
     * Ship::RenderEngineAnimation inside OnRenderBase (after the hull and cloak): [vanillaEngines] is the engine's
     * bShowEngines without `<thrusters>`, [vanilla] draws the vanilla thrusters.
     */
    @JvmStatic
    fun render(ship: Ship, g: xyz.znix.xftl.rendering.Graphics, vanillaEngines: Boolean, alpha: Float, vanilla: () -> Unit) {
        val st = state(ship)
        val layout = st.layout
        val show = if (layout != null) layout.thrusters.isNotEmpty() else vanillaEngines
        val idx = if (HS.luaActive) HsRenderEvents.preShipEngines(ship, show, alpha) else 0
        if (show && idx >= 0) {
            if (layout == null) vanilla() else draw(ship, g, st, layout, alpha)
        }
        if (HS.luaActive) HsRenderEvents.postShipEngines(ship, idx)
    }

    /** Ship::OnLoop: each thruster's loop runs with the game. True when the layout has its own thrusters. */
    @JvmStatic
    fun update(ship: Ship, dt: Float): Boolean {
        val st = state(ship)
        val layout = st.layout ?: return false
        val view = ship.hsEngineAnim
        for ((i, _) in layout.thrusters.withIndex()) {
            val total = st.specs[i]?.totalTime ?: 0.5f
            if (i == 0 && view != null) st.times[0] = view.time()
            st.times[i] = if (total > 0f) (st.times[i] + dt) % total else 0f
            if (i == 0) view?.updated(st.times[0])
        }
        return true
    }

    /** Where the layout's first thruster is drawn, when it has one. */
    @JvmStatic
    fun firstPos(ship: Ship): xyz.znix.xftl.math.ConstPoint? {
        if (!HS.active) return null
        val t = state(ship).layout?.thrusters?.firstOrNull() ?: return null
        return xyz.znix.xftl.math.ConstPoint(ship.hullOffset.x + t.x.toInt(), ship.hullOffset.y + t.y.toInt())
    }

    /** The layout's first thruster's animation. */
    @JvmStatic
    fun firstName(ship: Ship): String? = if (!HS.active) null else state(ship).layout?.thrusters?.firstOrNull()?.anim

    /** The layout's first thruster's loop time. */
    @JvmStatic
    fun firstTime(ship: Ship): Float? = if (!HS.active) null else state(ship).times.firstOrNull()

    private fun draw(ship: Ship, g: xyz.znix.xftl.rendering.Graphics, st: State, layout: Layout, alpha: Float) {
        val sheet = ship.sys.getImg("img/effects/thrusters_on.png")
        tint.a = alpha
        val hx = ship.hullOffset.x.toFloat()
        val hy = ship.hullOffset.y.toFloat()
        for ((i, t) in layout.thrusters.withIndex()) {
            val spec = st.specs[i]
            val frames = spec?.length ?: 4
            val total = spec?.totalTime ?: 0.5f
            val view = if (i == 0) ship.hsEngineAnim else null
            val frame = view?.frame()?.coerceIn(0, frames - 1) ?: if (total > 0f) (st.times[i] / total * frames).toInt().coerceIn(0, frames - 1) else 0
            val img = if (spec != null) spec.spriteAt(ship.sys, frame) else sheet.getSubImage(frame * 22, 0, 22, 70)
            val w = img.width.toFloat(); val h = img.height.toFloat()
            val px = view?.x()?.toFloat() ?: (hx + t.x); val py = view?.y()?.toFloat() ?: (hy + t.y)
            g.pushTransform()
            when (t.rot) {
                // GL_Rotate(+90) at (-(y), x) with x moved back by the frame width: the frame's x axis points up
                1 -> { g.translate(px, py + w); g.rotate(0f, 0f, -90f) }
                // GL_Rotate(-90) at (y, -x) with y moved back by the frame height: the x axis points down
                -1 -> { g.translate(px + h, py); g.rotate(0f, 0f, 90f) }
                else -> g.translate(px, py)
            }
            if (t.mirror) img.draw(0f, 0f, w, h, w, 0f, 0f, h, 1f, tint)
            else img.draw(0f, 0f, tint)
            g.popTransform()
        }
    }

    @JvmStatic
    fun capabilityNames() = arrayOf("thrusters", "thruster")

    /** For the report: SHIP_ENGINES' Lua event id. */
    const val EVENT = A.RE_SHIP_ENGINES
}
