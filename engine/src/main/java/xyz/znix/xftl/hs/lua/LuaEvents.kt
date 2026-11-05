// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * Engine hooks for Hyperspace's internal events (lua/InternalEvents.cpp). Each call site in the engine is
 * `if (HS.luaActive) LuaEvents.x(...)` at the place HS's hook runs relative to the original function (before or
 * after `super()`), and each method returns at once when no callback is registered for its event.
 */
object LuaEvents {
    /** Engine RNG context for math.random: true while a generation-time callback runs (see LuaRng). */
    @JvmField var generation = false

    /** Engine steps so far (each ends with [onTick]). */
    @JvmField var ticks = 0

    /** CApp::OnLoop, after super(): once per engine step. */
    @JvmStatic
    fun onTick() {
        ticks++
        LuaStored.endFrame()
        if (!HsLua.hasInt[A.EV_ON_TICK]) return
        LuaJs.fire(A.EV_ON_TICK, 0, 0)
        if (LuaGraphics.batching) LuaGraphics.flushQuads()
    }

    /** TabbedWindow::SetTab / Close, CommandGui::LButtonDown (CustomTabbedWindow.cpp): TABBED_WINDOW_CONFIRM / _UNDO with the tab's name. */
    @JvmStatic
    fun tabbedWindow(ev: Int, name: String) {
        if (!HsLua.hasInt[ev]) return
        LuaJs.pushstring(name)
        LuaJs.fire(ev, 1, 0)
        LuaJs.pop(1)
    }

    /** MainMenu::Open, after super(). */
    @JvmStatic
    fun mainMenu() {
        if (!HsLua.hasInt[A.EV_MAIN_MENU]) return
        LuaJs.fire(A.EV_MAIN_MENU, 0, 0)
    }

    /** ShipManager::OnLoop (priority -100), after super(). */
    @JvmStatic
    fun shipLoop(ship: Ship) {
        if (!HsLua.hasInt[A.EV_SHIP_LOOP]) return
        LuaPush.obj(ship, A.T_ShipManager)
        LuaJs.fire(A.EV_SHIP_LOOP, 1, 0)
        LuaJs.pop(1)
    }

    /**
     * The SHIP_LOOP the original fires while a generated ship is being built, after its fires: what a callback draws
     * (MV's unsynced hull animations start at random frames) comes from the global stream, ahead of the system rolls.
     */
    @JvmStatic
    fun shipLoopDuringGeneration(ship: Ship) {
        val prev = generation
        val prevShip = generatingShip
        generation = true
        generatingShip = ship
        try { shipLoop(ship) } finally { generation = prev; generatingShip = prevShip }
    }

    /** The ship [shipLoopDuringGeneration] is building: not yet the enemy, but its power manager already answers for id 1. */
    @JvmStatic
    var generatingShip: Ship? = null
        private set

    /** CrewMember::OnLoop (priority -100), after super(). */
    @JvmStatic
    fun crewLoop(crew: AbstractCrew) {
        if (!HsLua.hasInt[A.EV_CREW_LOOP]) return
        LuaPush.obj(crew, A.T_CrewMember)
        LuaJs.fire(A.EV_CREW_LOOP, 1, 0)
        LuaJs.pop(1)
    }

    /** ShipManager::JumpArrive / JumpLeave / Wait, after super(). */
    @JvmStatic fun jumpArrive(ship: Ship) = shipEvent(A.EV_JUMP_ARRIVE, ship)
    @JvmStatic fun jumpLeave(ship: Ship) = shipEvent(A.EV_JUMP_LEAVE, ship)
    @JvmStatic fun onWait(ship: Ship) = shipEvent(A.EV_ON_WAIT, ship)

    private fun shipEvent(ev: Int, ship: Ship) {
        if (!HsLua.hasInt[ev]) return
        LuaPush.obj(ship, A.T_ShipManager)
        LuaJs.fire(ev, 1, 0)
        LuaJs.pop(1)
    }

    /** BrowserEngine.key: CApp::OnKeyDown/OnKeyUp with the engine's (GLFW) key code; true when pre-empted. */
    @JvmStatic
    fun key(glfwKey: Int, down: Boolean): Boolean {
        val ev = if (down) A.EV_ON_KEY_DOWN else A.EV_ON_KEY_UP
        if (!HsLua.hasInt[ev]) return false
        return keyEvent(ev, sdlKey(glfwKey))
    }

    /**
     * BrowserEngine.mouse (type 0 move, 1 press, 2 release, 3 wheel; button 0 left, 1 right, 2 middle):
     * CApp::OnMouseMove / OnL/R/MButtonDown / OnL/RButtonUp and CEvent's scroll; true when pre-empted.
     */
    @JvmStatic
    fun mouse(x: Int, y: Int, oldX: Int, oldY: Int, type: Int, button: Int, l: Boolean, r: Boolean, m: Boolean): Boolean {
        val moved = x != mouseX || y != mouseY
        mouseX = x; mouseY = y
        // A script reads the mouse where the desktop layout has what the finger is on (TouchPointers.deskMouse).
        val desk = if (xyz.znix.xftl.game.TouchMode.enabled && !deskGiven) xyz.znix.xftl.game.TouchPointers.deskMouse(x, y) else null
        deskX = desk?.get(0) ?: x; deskY = desk?.get(1) ?: y
        if (type == 0) LuaAdaptersWP7Ui.mouseMoved(moved)
        return mouseEvent(x, y, oldX, oldY, type, button, l, r, m)
    }

    /** The last pointer position the engine received. */
    @JvmField var mouseX = 0
    @JvmField var mouseY = 0

    /** MouseControl::position, what a script reads: [mouseX], [mouseY] in the desktop layout's coordinates. */
    @JvmField var deskX = 0
    @JvmField var deskY = 0

    /** The caller's position is in desktop coordinates already (TouchExtras' pieces). */
    @JvmField var deskGiven = false

    /** [mouse] with a position in desktop coordinates. */
    @JvmStatic
    fun mouseDesk(x: Int, y: Int, oldX: Int, oldY: Int, type: Int, button: Int, l: Boolean, r: Boolean, m: Boolean): Boolean {
        deskGiven = true
        try {
            return mouse(x, y, oldX, oldY, type, button, l, r, m)
        } finally {
            deskGiven = false
        }
    }

    private fun mouseEvent(x: Int, y: Int, oldX: Int, oldY: Int, type: Int, button: Int, l: Boolean, r: Boolean, m: Boolean): Boolean = when (type) {
        0 -> mouseMove(x, y, x - oldX, y - oldY, l, r, m)
        1 -> when (button) { 0 -> mouseButton(A.EV_ON_MOUSE_L_BUTTON_DOWN, x, y); 1 -> mouseButton(A.EV_ON_MOUSE_R_BUTTON_DOWN, x, y); 2 -> mouseButton(A.EV_ON_MOUSE_M_BUTTON_DOWN, x, y); else -> false }
        2 -> when (button) { 0 -> mouseButton(A.EV_ON_MOUSE_L_BUTTON_UP, x, y); 1 -> mouseButton(A.EV_ON_MOUSE_R_BUTTON_UP, x, y); else -> false }
        3 -> scroll(button.toDouble())
        else -> false
    }

    private fun scroll(amount: Double): Boolean {
        if (!HsLua.hasInt[A.EV_ON_MOUSE_SCROLL] || amount == 0.0) return false
        LuaJs.pushnumber(amount)
        val preempt = LuaJs.fireChain(A.EV_ON_MOUSE_SCROLL, 1, 0)
        LuaJs.pop(1)
        return preempt
    }

    /** SDL 1.2's SDLKey for a GLFW key code (values as in HS's Defines.SDL), 0 (SDLK_UNKNOWN) otherwise. */
    @JvmStatic
    fun sdlKey(k: Int): Int = when (k) {
        in 65..90 -> k + 32                          // letters: SDL uses the lower-case ASCII code
        32, 39, in 44..57, 59, 61, 91, 92, 93, 96 -> k   // printable keys share ASCII
        256 -> 27; 257 -> 13; 258 -> 9; 259 -> 8; 260 -> 277; 261 -> 127
        262 -> 275; 263 -> 276; 264 -> 274; 265 -> 273; 266 -> 280; 267 -> 281; 268 -> 278; 269 -> 279
        280 -> 301; 281 -> 302; 282 -> 300; 283 -> 316; 284 -> 19
        in 290..304 -> k - 8                         // F1-F15 = 282-296
        in 320..329 -> k - 64                        // KP0-KP9 = 256-265
        330 -> 266; 331 -> 267; 332 -> 268; 333 -> 269; 334 -> 270; 335 -> 271; 336 -> 272
        340 -> 304; 341 -> 306; 342 -> 308; 343 -> 311; 344 -> 303; 345 -> 305; 346 -> 307; 347 -> 312; 348 -> 319
        else -> 0
    }

    /** CApp::OnKeyDown / OnKeyUp, before super(): true when a callback pre-empted the key. [sdlKey] is SDL 1.2's. */
    @JvmStatic fun keyDown(sdlKey: Int): Boolean = keyEvent(A.EV_ON_KEY_DOWN, sdlKey)
    @JvmStatic fun keyUp(sdlKey: Int): Boolean = keyEvent(A.EV_ON_KEY_UP, sdlKey)

    private fun keyEvent(ev: Int, key: Int): Boolean {
        if (!HsLua.hasInt[ev]) return false
        LuaJs.pushinteger(key.toDouble())
        val preempt = LuaJs.fireChain(ev, 1, 0)
        LuaJs.pop(1)
        return preempt
    }

    /** CApp::OnLButtonDown/Up, OnRButtonDown/Up, OnMButtonDown, before super(): true when pre-empted. */
    @JvmStatic
    fun mouseButton(ev: Int, x: Int, y: Int): Boolean {
        if (!HsLua.hasInt[ev]) return false
        LuaJs.pushinteger(x.toDouble()); LuaJs.pushinteger(y.toDouble())
        val preempt = LuaJs.fireChain(ev, 2, 0)
        LuaJs.pop(2)
        return preempt
    }

    /**
     * CommandGui::MouseMove hook (InternalEvents.cpp, GUI_MOUSE_MOVE chain event with (x, y)): runs for every call of
     * the original, a mouse move and each frame's CommandGui::OnLoop one; true when a callback pre-empted it.
     */
    @JvmStatic
    fun guiMouseMove(x: Int, y: Int): Boolean {
        if (!HsLua.hasInt[A.EV_GUI_MOUSE_MOVE]) return false
        LuaJs.pushinteger(x.toDouble()); LuaJs.pushinteger(y.toDouble())
        val preempt = LuaJs.fireChain(A.EV_GUI_MOUSE_MOVE, 2, 0)
        LuaJs.pop(2)
        return preempt
    }

    /** CApp::OnMouseMove, before super(). */
    @JvmStatic
    fun mouseMove(x: Int, y: Int, dx: Int, dy: Int, l: Boolean, r: Boolean, m: Boolean): Boolean {
        if (!HsLua.hasInt[A.EV_ON_MOUSE_MOVE]) return false
        LuaJs.pushinteger(x.toDouble()); LuaJs.pushinteger(y.toDouble())
        LuaJs.pushinteger(dx.toDouble()); LuaJs.pushinteger(dy.toDouble())
        LuaJs.pushboolean(l); LuaJs.pushboolean(r); LuaJs.pushboolean(m)
        val preempt = LuaJs.fireChain(A.EV_ON_MOUSE_MOVE, 7, 0)
        LuaJs.pop(7)
        return preempt
    }

    /** Render events (lua/RenderEvents.cpp): pre returns the index for [renderPost]; negative = skip the original. */
    @JvmStatic
    fun renderPre(ev: Int): Int {
        if (!HsLua.hasRen[ev]) return 0
        val i = LuaJs.renderPre(ev, 0)
        if (LuaGraphics.batching) LuaGraphics.flushQuads()
        return i
    }

    @JvmStatic
    fun renderPost(ev: Int, idx: Int) {
        if (!HsLua.hasRen[ev]) return
        LuaJs.renderPost(ev, if (idx < 0) -idx else idx, 0)
        // matrix pushes a script left open, and its queued rectangles
        LuaGraphics.restore()
    }

    /** A run was left (new run, load, main menu): the session-long tables let go of its objects. */
    @JvmStatic
    fun runEnded() {
        LuaViews.endRun()
        Handles.endRun()
        xyz.znix.xftl.hs.render.HsSwitchShip.endRun()
        xyz.znix.xftl.hs.systems.HsSystemBox.endRun()
        HsRunMaps.endRun()
        // Userdata of the run's objects hold those objects through their handles until Lua finalizes them, and Lua paces
        // itself on its own small heap, which knows nothing of a whole game behind them. So the finished run goes now.
        if (xyz.znix.xftl.hs.HS.luaActive) LuaJs.fullGc()
    }

    /** ScoreKeeper::LoadGame / WorldManager::CreateNewGame, after super(): script.on_init callbacks. */
    @JvmStatic
    fun onInit(newGame: Boolean) {
        if (!HsLua.hasInit) return
        LuaJs.onInit(newGame)
    }

    /** WorldManager::UpdateLocation / CreateLocation, before super(): script.on_game_event callbacks. */
    @JvmStatic
    fun gameEvent(name: String, loading: Boolean) {
        if (!HsLua.hasGameEvents) return
        val prev = generation
        generation = true
        try { LuaJs.gameEvent(name, loading) } finally { generation = prev }
    }
}

/** math.random / math.randomseed (HS: FTL's random32): NativeRandom while generating, BrowserRandom otherwise. */
object LuaRng {
    @JvmStatic
    fun rand32(): Int = if (LuaEvents.generation) ftl.browser.NativeRandom.next31() else ftl.browser.BrowserRandom.nextInt() and Int.MAX_VALUE

    @JvmStatic
    fun srand32(seed: Int) {
        // the native stream also feeds the runtime draws made by event actions (custom fleets), as srandom32 reseeds the one stream
        ftl.browser.NativeRandom.seed(seed)
        if (!LuaEvents.generation) ftl.browser.BrowserRandom.restore((seed.toLong() and 0xffffffffL).toString())
    }
}
