// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i ShipGraph members),
// not from the 1.23.2 tag; the member semantics follow FTL 1.6.14's ShipGraph (addresses below).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * ShipGraph for ship id 0 (player) or 1 (enemy). The engine's Room frame is FTL's ShipGraph frame
 * (rect = 35 * (grid + ship offset)), so rects, centres and hit tests come straight from [Room].
 * [ship] null: FTL's GetShipInfo (0x465bb0) appends an empty graph for an unknown id (no rooms,
 * shipBox {-1,-1,0,0}); here that is the graph of a missing enemy.
 */
class LuaShipGraph(@JvmField val ship: Ship?) : LuaTyped {
    override val luaType get() = A.T_ShipGraph
    val rooms: List<Room> get() = ship?.rooms ?: emptyList()
}

/** ShipManager::fireSpreader, read-only: the engine keeps fires per room cell. */
class LuaFireSpreader(@JvmField val ship: Ship) : LuaTyped { override val luaType get() = A.T_Spreader_Fire }

object LuaShipGraphApi {
    private val empty = LuaShipGraph(null)

    @JvmStatic fun of(id: Int): LuaShipGraph {
        val s = LuaAdapters.shipManager(id) ?: return empty
        return LuaViews.cached(s, A.T_ShipGraph) { o -> LuaShipGraph(o as Ship) }
    }

    private fun rect(x: Int, y: Int, w: Int, h: Int): LuaStruct {
        val r = LuaStructs.create(A.T_Rect)
        LuaStructs.setFields(r, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
        return r
    }

    /** ShipGraph::GetRoomShape (0x464b70): the first room with that id, else {-1,-1,0,0}. */
    private fun shape(g: LuaShipGraph, id: Int): Room? = g.rooms.firstOrNull { it.id == id }

    /** Room hit test (0x476430): with walls the edges count, without them only the interior. */
    private fun contains(r: Room, x: Int, y: Int, walls: Boolean): Boolean {
        val x0 = r.offsetX; val y0 = r.offsetY; val x1 = x0 + r.pixelWidth; val y1 = y0 + r.pixelHeight
        return if (walls) x in x0..x1 && y in y0..y1 else x > x0 && x < x1 && y > y0 && y < y1
    }

    /** ShipGraph::shipBox (ComputeCenter): {x, y, w, h}. */
    @JvmStatic fun box(s: Ship): IntArray {
        var minX = 0x500; var minY = 0x2d0; var maxR = -1; var maxB = -1
        for (r in s.rooms) {
            if (r.offsetX < minX) minX = r.offsetX
            if (r.offsetY < minY) minY = r.offsetY
            if (r.offsetX + r.pixelWidth > maxR) maxR = r.offsetX + r.pixelWidth
            if (r.offsetY + r.pixelHeight > maxB) maxB = r.offsetY + r.pixelHeight
        }
        return intArrayOf(minX, minY, maxR - minX, maxB - minY)
    }

    fun install() {
        LuaReg.method(A.M_ShipGraph_GetShipInfo, LuaMethod { ret.obj(of(arg.int(1)), A.T_ShipGraph) })
        LuaReg.method(A.M_ShipGraph_RoomCount, LuaMethod { ret.num(arg.self<LuaShipGraph>(1).rooms.size) })
        LuaReg.method(A.M_ShipGraph_GetRoomShape, LuaMethod {
            val r = shape(arg.self(1), arg.int(2))
            ret.obj(if (r == null) rect(-1, -1, 0, 0) else rect(r.offsetX, r.offsetY, r.pixelWidth, r.pixelHeight), A.T_Rect)
        })
        // ShipGraph::GetRoomCenter: Pointf(x + w/2, y + h/2) of the room shape, integer halves
        LuaReg.method(A.M_ShipGraph_GetRoomCenter, LuaMethod {
            val r = shape(arg.self(1), arg.int(2))
            val p = if (r == null) PointfValue(-1f, -1f) else PointfValue((r.offsetX + r.pixelWidth / 2).toFloat(), (r.offsetY + r.pixelHeight / 2).toFloat())
            ret.obj(p, A.T_Pointf)
        })
        // ShipGraph::GetSelectedRoom (0x464ab0): the first room in vector order containing the point, else -1
        LuaReg.method(A.M_ShipGraph_GetSelectedRoom, LuaMethod {
            val g = arg.self<LuaShipGraph>(1); val x = arg.int(2); val y = arg.int(3); val walls = arg.bool(4)
            ret.num(g.rooms.firstOrNull { contains(it, x, y, walls) }?.id ?: -1)
        })
        LuaReg.obj(A.A_ShipGraph_rooms, ObjGet { o ->
            val g = o as LuaShipGraph
            if (g.ship == null) ListView(A.T_vector_Room, A.T_Room) { emptyList<Any?>() }
            else LuaViews.cached(g.ship, A.A_Ship_vRoomList) { s -> ListView(A.T_vector_Room, A.T_Room) { (s as Ship).rooms } }
        })
        // ShipGraph::shipBox from ComputeCenter (0x464880): {minX, minY, maxRight - minX, maxBottom - minY} over
        // the rooms, with the 1280x720 sentinels when there are none
        LuaReg.obj(A.A_ShipGraph_shipBox, ObjGet { o ->
            val g = o as LuaShipGraph
            if (g.ship == null) return@ObjGet rect(-1, -1, 0, 0)
            val b = box(g.ship)
            LuaViews.cached(g.ship, A.A_ShipGraph_shipBox) { s ->
                val ship = s as Ship
                rect(b[0], b[1], b[2], b[3]).also { r ->
                    r.onWrite = {
                        val now = box(ship)
                        ship.hsBoxShiftX = ((r.values[A.A_Rect_x] as? Double) ?: 0.0).toInt() - now[0]
                        ship.hsBoxShiftY = ((r.values[A.A_Rect_y] as? Double) ?: 0.0).toInt() - now[1]
                    }
                }
            }
        }, ObjSet { o, v ->
            val g = o as LuaShipGraph
            val ship = g.ship ?: return@ObjSet
            val dst = LuaReg.objGet[A.A_ShipGraph_shipBox]?.get(o) as? LuaStruct ?: return@ObjSet
            val src = v as? LuaStruct ?: return@ObjSet
            LuaWritable.copyInto(dst, src, A.T_Rect, 0)
            dst.onWrite?.invoke()
            ship.hashCode()
        })
        // ShipManager::fireSpreader (Spreader<Fire>): count = burning cells on the ship, roomCount[i] = those in room i
        LuaReg.obj(A.A_ShipManager_fireSpreader, ObjGet { LuaViews.cached(it as Ship, A.A_ShipManager_fireSpreader) { s -> LuaFireSpreader(s as Ship) } })
        // as of the ship's last loop (Spreader<Fire>::OnLoop recounts; SetFire does not)
        LuaReg.num(A.A_Spreader_Fire_count, NumGet { o -> (o as LuaFireSpreader).ship.hsFireCount.toDouble() })
        LuaReg.obj(A.A_Spreader_Fire_roomCount, ObjGet { o ->
            val s = (o as LuaFireSpreader).ship
            LuaViews.cached(s, A.A_Spreader_Fire_roomCount) { x -> ListView(A.T_vector_int, 0) { val sh = x as Ship; sh.rooms.indices.map { i -> sh.hsFireRoomCount.getOrElse(i) { 0 }.toDouble() } } }
        })
        // Room::iRoomId and Room::rect (a copy: writes do not move the room)
        LuaReg.num(A.A_Room_iRoomId, NumGet { (it as Room).id.toDouble() })
        LuaReg.obj(A.A_Room_rect, ObjGet { val r = it as Room; rect(r.offsetX, r.offsetY, r.pixelWidth, r.pixelHeight) })
    }
}
