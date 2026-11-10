// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** HS singletons that have no engine object of their own. */
object LuaGlobal : LuaTyped { override val luaType get() = A.T_Global }
object LuaApp : LuaTyped { override val luaType get() = A.T_CApp }
object LuaWorld : LuaTyped { override val luaType get() = A.T_WorldManager }
/** HS's `Ship` (the geometry/hull part of a ShipManager): a separate object so `ship.ship ~= ship` as in HS. */
class LuaShipGeom(@JvmField val ship: Ship) : LuaTyped { override val luaType get() = A.T_Ship }

/**
 * Hand-written adapters from Hyperspace's Lua API (ids in [LuaApi]) onto engine objects. Members without an adapter
 * raise "not available" and are recorded in HS.capability. Other workstreams add their features' members through
 * [LuaReg] in their own packages (see docs/HS_LUA.md).
 */
object LuaAdapters {
    @JvmStatic
    fun install() {
        core()
        vectors()
        values()
        ship()
        crew()
        LuaAdaptersMore.install()
    }

    @JvmStatic fun inGame(): InGameState? {
        (HsLua.game?.currentState as? InGameState)?.let { return it }
        val s = startingGame ?: return null
        // A run is built within one engine step. One whose construction threw (a mod's missing event) never became
        // the state: from the next step on there is no game, as at the menu (World.space.projectiles is empty there).
        if (s !== startingSeen) { startingSeen = s; startingTick = LuaEvents.ticks }
        if (LuaEvents.ticks != startingTick) { startingGame = null; startingSeen = null; return null }
        return s
    }
    private var startingSeen: InGameState? = null
    private var startingTick = 0

    /** HsEvents.newGame: [game] is the run being built in this engine step. */
    @JvmStatic fun starting(game: InGameState) { startingGame = game; startingSeen = game; startingTick = LuaEvents.ticks }

    /**
     * A new run while its InGameState is being built (hs.events sets it in HsEvents.newGame, MainGame.setCurrentState
     * clears it): the first beacon's choice box opens inside the constructor, and HS's CreateChoiceBox callbacks
     * already see the world and the player ship there.
     */
    @JvmField var startingGame: InGameState? = null

    /** Global::GetShipManager: player 0, the current enemy 1, null outside a run (hangar ship: not modelled yet). */
    @JvmStatic fun shipManager(id: Int): Ship? {
        val s = inGame() ?: return null
        return when (id) { 0 -> s.player; 1 -> s.browserRetainedEnemy ?: escaped(s); else -> null }
    }

    /**
     * The enemy that escaped at this beacon. WorldManager keeps playerShip->enemyShip until the location changes, so
     * Global::GetShipManager(1) still answers with it, but no longer loops it; its crew are gone (KillEveryone) and
     * bDestroyed and bJumping are set (oracle ps-m1-ev esc and loop rows). The engine drops the ship; only Lua sees it.
     */
    private var escapedEnemy: Ship? = null
    private var escapedAt: Any? = null
    @JvmStatic fun enemyEscaped(game: InGameState, ship: Ship) { escapedEnemy = ship; escapedAt = game.currentBeacon }
    private fun escaped(s: InGameState): Ship? {
        if (escapedEnemy != null && escapedAt !== s.currentBeacon) { escapedEnemy = null; escapedAt = null }
        return escapedEnemy
    }
    @JvmStatic fun isEscaped(o: Any?): Boolean = o != null && o === escapedEnemy

    /** ShipManager::iShipId: 0 for the run's player ship object, 1 otherwise (by identity, not by blueprint name). */
    @JvmStatic fun shipId(ship: Ship): Int = xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(ship)

    /** CrewMember::iShipId: the owning ship's id (crew drones through their drone), as the stat code uses it. */
    @JvmStatic fun crewShipId(c: AbstractCrew): Int = xyz.znix.xftl.hs.crew.HsCrew.shipId(c)

    private fun core() {
        LuaReg.method(A.M_Global_GetInstance, LuaMethod { ret.obj(LuaGlobal, A.T_Global) })
        LuaReg.method(A.M_Global_GetShipManager, LuaMethod { ret.obj(shipManager(arg.int(2)), A.T_ShipManager) })
        LuaReg.method(A.M_Global_GetCApp, LuaMethod { ret.obj(LuaApp, A.T_CApp) })
        // hyperspace.i: %rename("setWindowTitle") sys_graphics_set_window_title (SDL window caption)
        LuaReg.method(A.M_setWindowTitle, LuaMethod { LuaJs.setWindowTitle(arg.str(1) ?: ""); 0 })
        LuaReg.obj(A.A_module_App, ObjGet { LuaApp })
        LuaReg.obj(A.A_CApp_world, ObjGet { LuaWorld })
        LuaReg.bool(A.A_WorldManager_bStartedGame, { inGame() != null })
        LuaReg.bool(A.A_WorldManager_bLoadingGame, { false })
    }

    private fun vectors() {
        val v = A.vectors()
        var i = 0
        while (i + 3 < v.size) {
            val size = v[i + 1]; val get = v[i + 2]; val el = v[i + 3]
            if (size != 0) LuaReg.method(size, LuaMethod { ret.num((arg.obj(1) as ListView).size()) })
            if (get != 0) LuaReg.method(get, LuaMethod {
                val list = arg.obj(1) as ListView
                val e = list.get(arg.int(2))
                when {
                    el != 0 -> ret.obj(e, el)
                    e is Number -> ret.num(e.toDouble())
                    e is Boolean -> ret.bool(e)
                    else -> ret.str(e?.toString())
                }
            })
            i += 4
        }
    }

    private fun values() {
        // pairs: views over owner fields
        LuaReg.num(A.A_pair_int_int_first, NumGet { (it as IntPairView).first.toDouble() }, NumSet { o, v -> (o as IntPairView).first = v.toInt() })
        LuaReg.num(A.A_pair_int_int_second, NumGet { (it as IntPairView).second.toDouble() }, NumSet { o, v -> (o as IntPairView).second = v.toInt() })
        LuaReg.num(A.A_pair_float_float_first, NumGet { (it as FloatPairView).first.toDouble() }, NumSet { o, v -> (o as FloatPairView).first = v.toFloat() })
        LuaReg.num(A.A_pair_float_float_second, NumGet { (it as FloatPairView).second.toDouble() }, NumSet { o, v -> (o as FloatPairView).second = v.toFloat() })
        // Point / Pointf / GL_Color values
        LuaReg.num(A.A_Pointf_x, NumGet { (it as PointfValue).x.toDouble() }, NumSet { o, v -> (o as PointfValue).x = v.toFloat(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_Pointf_y, NumGet { (it as PointfValue).y.toDouble() }, NumSet { o, v -> (o as PointfValue).y = v.toFloat(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_Point_x, NumGet { (it as PointValue).x.toDouble() }, NumSet { o, v -> (o as PointValue).x = v.toInt(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_Point_y, NumGet { (it as PointValue).y.toDouble() }, NumSet { o, v -> (o as PointValue).y = v.toInt(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_GL_Color_r, NumGet { (it as GlColorValue).r.toDouble() }, NumSet { o, v -> (o as GlColorValue).r = v.toFloat(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_GL_Color_g, NumGet { (it as GlColorValue).g.toDouble() }, NumSet { o, v -> (o as GlColorValue).g = v.toFloat(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_GL_Color_b, NumGet { (it as GlColorValue).b.toDouble() }, NumSet { o, v -> (o as GlColorValue).b = v.toFloat(); (o as? LuaWriteThrough)?.written() })
        LuaReg.num(A.A_GL_Color_a, NumGet { (it as GlColorValue).a.toDouble() }, NumSet { o, v -> (o as GlColorValue).a = v.toFloat(); (o as? LuaWriteThrough)?.written() })
        // constructors (overloads already chosen by SWIG's dispatch rules in the runtime)
        LuaReg.method(A.M_new_Pointf__SWIG_0, LuaMethod { ret.obj(PointfValue(0f, 0f), A.T_Pointf) })
        LuaReg.method(A.M_new_Pointf__SWIG_1, LuaMethod { ret.obj(PointfValue(arg.float(1), arg.float(2)), A.T_Pointf) })
        LuaReg.method(A.M_new_Pointf__SWIG_2, LuaMethod { val p = arg.obj(1) as PointValue; ret.obj(PointfValue(p.x.toFloat(), p.y.toFloat()), A.T_Pointf) })
        // SWIG numbers Point(int, int) as _0 and Point() as _1 (Pointf the other way round)
        LuaReg.method(A.M_new_Point__SWIG_1, LuaMethod { ret.obj(PointValue(0, 0), A.T_Point) })
        LuaReg.method(A.M_new_Point__SWIG_0, LuaMethod { ret.obj(PointValue(arg.int(1), arg.int(2)), A.T_Point) })
        LuaReg.method(A.M_new_GL_Color__SWIG_0, LuaMethod { ret.obj(GlColorValue(arg.float(1), arg.float(2), arg.float(3), arg.float(4)), A.T_GL_Color) })
        LuaReg.method(A.M_new_GL_Color__SWIG_1, LuaMethod { ret.obj(GlColorValue(0f, 0f, 0f, 0f), A.T_GL_Color) })
    }

    private fun ship() {
        // ShipObject::iShipId: ShipManager (the engine Ship) and Ship (its geometry view, as render events pass it)
        LuaReg.num(A.A_ShipObject_iShipId, NumGet { shipId(if (it is LuaShipGeom) it.ship else it as Ship).toDouble() })
        LuaReg.obj(A.A_ShipManager_ship, ObjGet { LuaViews.cached(it!!, A.A_ShipManager_ship) { o -> LuaShipGeom(o as Ship) } })
        // ShipManager::spaceDrones: the ship's own space drones (ShipManager::CreateSpaceDrone pushes each one it makes),
        // i.e. the drone system's spawned external drones; Ship.externalDrones are the drones around a ship instead
        LuaReg.obj(A.A_ShipManager_spaceDrones, ObjGet { LuaViews.cached(it!!, A.A_ShipManager_spaceDrones) { o ->
            ListView(A.T_vector_SpaceDrone, A.T_SpaceDrone) {
                (o as Ship).drones?.drones?.mapNotNull { d -> d?.instance as? xyz.znix.xftl.drones.AbstractExternalDrone } ?: emptyList()
            }
        } })
        LuaReg.obj(A.A_Drone_blueprint, ObjGet { (it as AbstractDrone).type })
        LuaReg.obj(A.A_ShipManager_vCrewList, ObjGet { LuaViews.cached(it!!, A.A_ShipManager_vCrewList) { o -> ListView(A.T_vector_CrewMember, A.T_CrewMember) { if (isEscaped(o)) emptyList() else (o as Ship).crew } } })
        LuaReg.num(A.A_ShipManager_currentScrap, NumGet { (it as Ship).scrap.toDouble() }, NumSet { o, v -> (o as Ship).scrap = v.toInt() })
        LuaReg.bool(A.A_ShipManager_bDestroyed, { (it as Ship).nativeDestroyed || isEscaped(it) })
        LuaReg.bool(A.A_ShipManager_bJumping, { (it as Ship).browserJumping })
        LuaReg.obj(A.A_Ship_hullIntegrity, ObjGet { o ->
            LuaViews.cached((o as LuaShipGeom).ship, A.A_Ship_hullIntegrity) { s ->
                val ship = s as Ship
                IntPairView(A.T_pair_int_int, IGet { ship.health }, ISet { ship.health = it }, IGet { ship.maxHealth }, ISet { ship.browserMaxHealth = it })
            }
        })
        LuaReg.num(A.A_Drone_iShipId, NumGet { shipId((it as AbstractDrone).ownerShip).toDouble() })
        LuaReg.bool(A.A_Drone_powered, { (it as AbstractDrone).isPowered })
    }

    private fun crew() {
        LuaReg.obj(A.A_CrewMember_health, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewMember_health) { x ->
                val c = x as AbstractCrew
                // health.second: UpdateHealth writes the stat back every frame.
                lateinit var view: FloatPairView
                view = FloatPairView(A.T_pair_float_float, FGet { c.health }, FSet { c.health = it }, FGet { c.maxHealth },
                    FSet { LuaStored.frame(view, A.A_pair_float_float_second, it.toDouble()) })
                view
            }
        })
        // CrewMember::iShipId: the ship the crew member belongs to (not the one it stands on)
        LuaReg.num(A.A_CrewMember_iShipId, NumGet { crewShipId(it as AbstractCrew).toDouble() })
        // CrewMember::iRoomId: the room the crew member is in (the stat code's CrewExtend.roomId)
        LuaReg.num(A.A_CrewMember_iRoomId, NumGet { (it as AbstractCrew).room.id.toDouble() })
        LuaReg.bool(A.A_CrewMember_bMindControlled, { (it as? LivingCrew)?.isMindControlled == true })
    }

}
