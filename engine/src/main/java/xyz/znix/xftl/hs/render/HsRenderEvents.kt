// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/RenderEvents.cpp, src/game/Ship/CustomShips.cpp
// (SHIP_ENGINES / SHIP_HULL), src/game/UserInterface/CustomSpaceStatus.cpp (SPACE_STATUS) and lua/LuaLibScript.cpp
// (call_on_render_event_pre/post_callbacks).
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaGraphics
import xyz.znix.xftl.hs.lua.LuaJs
import xyz.znix.xftl.hs.lua.LuaPush
import xyz.znix.xftl.hs.lua.LuaShipGeom
import xyz.znix.xftl.hs.lua.LuaViews
import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * The render-stage contract of `script.on_render_event` (docs/HS_RENDER.md): every stage is a pre call before the
 * engine's equivalent of the hooked FTL function and a post call after it; a negative pre result (PREEMPT) skips the
 * original drawing. Call sites read `HS.luaActive` first; each method returns at once without callbacks.
 *
 * Nesting: stages nest as in FTL (SHIP_STATUS inside GUI_CONTAINER, ...). A matrix a pre callback pushes stays on
 * the stack for the original drawing and its post callback (MV's GUI_CONTAINER screen shake); after a stage's post
 * callbacks only the pushes made since that stage's pre are undone, so an outer stage's push survives.
 *
 * Frames: a stage's Lua drawing happens in FTL's coordinate frame for it. Where the engine's frame differs (ship
 * stages: [shipFrameX]/[shipFrameY]), the Lua calls run with that offset added and the original drawing keeps the
 * engine's frame, so a pre callback's transform applies to the original drawing as in FTL.
 */
object HsRenderEvents {
    /** LuaGraphics.pushed at each open stage's pre, innermost last. */
    private val bases = IntArray(64)
    private var depth = 0

    @JvmStatic
    fun has(ev: Int): Boolean = HsLua.hasRen[ev]

    private fun begin() {
        if (depth < bases.size) bases[depth] = LuaGraphics.pushed
        depth++
    }

    private fun end() {
        depth--
        val base = if (depth in bases.indices) bases[depth] else 0
        LuaGraphics.flushQuads()
        val g = HsLua.graphics
        while (LuaGraphics.pushed > base) {
            g?.popTransform(); LuaGraphics.pushed--
        }
    }

    /** A stage without arguments: the pre callbacks; >= 0 runs the original. Pair every call with [post]. */
    @JvmStatic
    fun pre(ev: Int): Int {
        if (!HsLua.hasRen[ev]) return 0
        begin()
        val i = LuaJs.renderPre(ev, 0)
        LuaGraphics.flushQuads()
        return i
    }

    @JvmStatic
    fun post(ev: Int, idx: Int) {
        if (!HsLua.hasRen[ev]) return
        LuaJs.renderPost(ev, if (idx < 0) -idx else idx, 0)
        end()
    }

    /**
     * CommandGui::RenderStatic's first three layers: SpaceManager::OnRenderBackground (LAYER_BACKGROUND),
     * OnRenderForeground (LAYER_FOREGROUND: planet, sun, fleet) and OnRenderAsteroids(3) (LAYER_ASTEROIDS), each
     * drawn by its own pass of the environment's renderBackground ([AbstractEnvironment.hsPhase]).
     */
    @JvmStatic
    fun spaceLayers(env: xyz.znix.xftl.environment.AbstractEnvironment, gc: xyz.znix.xftl.sys.GameContainer, g: xyz.znix.xftl.rendering.Graphics) {
        try {
            for (phase in 1..3) {
                val ev = when (phase) { 1 -> A.RE_LAYER_BACKGROUND; 2 -> A.RE_LAYER_FOREGROUND; else -> A.RE_LAYER_ASTEROIDS }
                env.hsPhase = phase
                val i = pre(ev)
                if (i >= 0) env.renderStack(gc, g)
                // SpaceManager's OnRenderBackground / OnRenderForeground / OnRenderAsteroids(3) hooks
                // (CustomBackgroundObject.cpp), after the original's part
                HsBackgroundObjects.renderSpace(env.game, g, phase, env.pauseShade(env.game))
                post(ev, i)
            }
        } finally {
            env.hsPhase = 0
        }
    }

    /**
     * CommandGui::RenderStatic's last space pass, SpaceManager::OnRenderAsteroids(1) after the player ship while the
     * jump zoom is above zero, under the same scale about the hull centre: LAYER_FRONT, and the front layer's
     * background objects (CustomBackgroundObject.cpp, the OnRenderAsteroids hook with layers != 3).
     */
    @JvmStatic
    fun layerFront(game: xyz.znix.xftl.game.InGameState, g: xyz.znix.xftl.rendering.Graphics) {
        g.pushTransform()
        game.applyJumpScene(g)
        val i = pre(A.RE_LAYER_FRONT)
        HsBackgroundObjects.renderSpace(game, g, 4, game.currentBeacon.getEnvironment(game).pauseShade(game))
        post(A.RE_LAYER_FRONT, i)
        g.popTransform()
    }

    // ---- stages with arguments: the arguments stay on the Lua stack from pre to post (HS pops them after post)

    private fun argc(ev: Int) = when (ev) {
        A.RE_SHIP_MANAGER, A.RE_SHIP_ENGINES -> 3
        A.RE_SHIP_FLOOR, A.RE_SHIP_HULL, A.RE_SHIP_JUMP -> 2
        else -> 1
    }

    /** Native `Ship` (ShipManager::ship) as Lua sees it. */
    @JvmStatic
    fun shipGeom(ship: Ship): Any = LuaViews.cached(ship, A.A_ShipManager_ship) { o -> LuaShipGeom(o as Ship) }

    /**
     * FTL's ship frame relative to the engine's (the ship's render origin): FTL places rooms and the hull in
     * pixels from the ship's own origin; see docs/HS_RENDER.md for the measurement against the oracle.
     */
    @JvmStatic
    fun shipFrameX(ship: Ship): Float = 0f

    @JvmStatic
    fun shipFrameY(ship: Ship): Float = 0f

    private fun shipPre(ev: Int, n: Int, ship: Ship): Int {
        begin()
        val g = HsLua.graphics
        val dx = shipFrameX(ship); val dy = shipFrameY(ship)
        // Lua draws in FTL's ship frame: engine origin = FTL origin + (dx, dy)
        g?.translate(-dx, -dy)
        val i = LuaJs.renderPre(ev, n)
        LuaGraphics.flushQuads()
        g?.translate(dx, dy)
        return i
    }

    private fun shipPost(ev: Int, idx: Int, ship: Ship) {
        val g = HsLua.graphics
        val dx = shipFrameX(ship); val dy = shipFrameY(ship)
        g?.translate(-dx, -dy)
        val n = argc(ev)
        LuaJs.renderPost(ev, if (idx < 0) -idx else idx, n)
        LuaGraphics.flushQuads()
        g?.translate(dx, dy)
        LuaJs.pop(n)
        end()
    }

    /** ShipManager::OnRender(showInterior, doorControlMode): args (ShipManager, bool, bool). */
    @JvmStatic
    fun preShipManager(ship: Ship, showInterior: Boolean, doorControlMode: Boolean): Int {
        if (!HsLua.hasRen[A.RE_SHIP_MANAGER]) return 0
        LuaPush.obj(ship, A.T_ShipManager); LuaJs.pushboolean(showInterior); LuaJs.pushboolean(doorControlMode)
        return shipPre(A.RE_SHIP_MANAGER, 3, ship)
    }

    @JvmStatic
    fun postShipManager(ship: Ship, idx: Int) {
        if (!HsLua.hasRen[A.RE_SHIP_MANAGER]) return
        shipPost(A.RE_SHIP_MANAGER, idx, ship)
    }

    /** Ship::OnRenderFloor(experimental) (SHIP_FLOOR, args Ship, bool), Ship::OnRenderSparks / OnRenderBreaches (Ship). */
    @JvmStatic
    fun preShip(ev: Int, ship: Ship): Int {
        if (!HsLua.hasRen[ev]) return 0
        LuaPush.obj(shipGeom(ship), A.T_Ship)
        var n = 1
        if (ev == A.RE_SHIP_FLOOR) { LuaJs.pushboolean(false); n = 2 }
        return shipPre(ev, n, ship)
    }

    @JvmStatic
    fun postShip(ev: Int, ship: Ship, idx: Int) {
        if (!HsLua.hasRen[ev]) return
        shipPost(ev, idx, ship)
    }

    /** Ship::OnRenderJump(progress) (SHIP_JUMP, args Ship, number). */
    @JvmStatic
    fun preShipJump(ship: Ship, progress: Float): Int {
        if (!HsLua.hasRen[A.RE_SHIP_JUMP]) return 0
        LuaPush.obj(shipGeom(ship), A.T_Ship)
        LuaJs.pushnumber(progress.toDouble())
        return shipPre(A.RE_SHIP_JUMP, 2, ship)
    }

    @JvmStatic
    fun postShipJump(ship: Ship, idx: Int) {
        if (!HsLua.hasRen[A.RE_SHIP_JUMP]) return
        shipPost(A.RE_SHIP_JUMP, idx, ship)
    }

    /** Ship::RenderEngineAnimation(showEngines, alpha) (SHIP_ENGINES) and the hull (SHIP_HULL, args Ship, alpha). */
    @JvmStatic
    fun preShipEngines(ship: Ship, showEngines: Boolean, alpha: Float): Int {
        if (!HsLua.hasRen[A.RE_SHIP_ENGINES]) return 0
        LuaPush.obj(shipGeom(ship), A.T_Ship)
        LuaJs.pushnumber(if (showEngines) 1.0 else 0.0)
        LuaJs.pushnumber(alpha.toDouble())
        return shipPre(A.RE_SHIP_ENGINES, 3, ship)
    }

    @JvmStatic
    fun postShipEngines(ship: Ship, idx: Int) {
        if (!HsLua.hasRen[A.RE_SHIP_ENGINES]) return
        shipPost(A.RE_SHIP_ENGINES, idx, ship)
    }

    @JvmStatic
    fun postShipHull(ship: Ship, idx: Int) {
        if (!HsLua.hasRen[A.RE_SHIP_HULL]) return
        shipPost(A.RE_SHIP_HULL, idx, ship)
    }

    @JvmStatic
    fun preShipHull(ship: Ship, alpha: Float): Int {
        if (!HsLua.hasRen[A.RE_SHIP_HULL]) return 0
        LuaPush.obj(shipGeom(ship), A.T_Ship)
        LuaJs.pushnumber(alpha.toDouble())
        return shipPre(A.RE_SHIP_HULL, 2, ship)
    }

    /** A stage with one object argument of Lua type [type] (CHOICE_BOX: ChoiceBox, TABBED_WINDOW: TabbedWindow). */
    @JvmStatic
    fun preObj(ev: Int, o: Any?, type: Int): Int {
        if (!HsLua.hasRen[ev]) return 0
        LuaPush.obj(o, type)
        begin()
        val i = LuaJs.renderPre(ev, 1)
        LuaGraphics.flushQuads()
        return i
    }

    @JvmStatic
    fun postObj(ev: Int, idx: Int) {
        if (!HsLua.hasRen[ev]) return
        LuaJs.renderPost(ev, if (idx < 0) -idx else idx, 1)
        LuaJs.pop(1)
        end()
    }

    /**
     * TABBED_WINDOW (CustomTabbedWindow.cpp): one string argument, the tab's name; the pre callbacks run in the window's frame
     * ([dx], [dy] is where the original translates to). The post callbacks run in that frame only when a pre callback pre-empted.
     */
    @JvmStatic
    fun preString(ev: Int, s: String, dx: Float, dy: Float): Int {
        if (!HsLua.hasRen[ev]) return 0
        LuaJs.pushstring(s)
        begin()
        val g = HsLua.graphics
        g?.translate(dx, dy)
        val i = LuaJs.renderPre(ev, 1)
        LuaGraphics.flushQuads()
        g?.translate(-dx, -dy)
        return i
    }

    @JvmStatic
    fun postString(ev: Int, idx: Int, dx: Float, dy: Float) {
        if (!HsLua.hasRen[ev]) return
        val g = HsLua.graphics
        if (idx < 0) g?.translate(dx, dy)
        LuaJs.renderPost(ev, if (idx < 0) -idx else idx, 1)
        LuaGraphics.flushQuads()
        if (idx < 0) g?.translate(-dx, -dy)
        LuaJs.pop(1)
        end()
    }
}
