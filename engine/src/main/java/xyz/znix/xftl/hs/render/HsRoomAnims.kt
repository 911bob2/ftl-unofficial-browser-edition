// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/Room_Extend.h (RoomAnim) and
// src/game/Ship/CustomShips.cpp (RoomAnim::OnUpdate/OnRender, the ShipManager::OnRender, Ship::OnRenderFloor,
// OnRenderBreaches and OnRenderSparks hooks)
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.ship.RoomAnimDef
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * Room animations of custom ships (`<rooms><room id><roomAnim renderLayer>`; the data model is the ships
 * workstream's [RoomAnimDef]). Each is made when the ship is set up (ShipManager_Extend::Initialize, room by room):
 * `anim` at the room corner (DEFAULT), stretched to the room less `animBorder` (STRETCH), centred (CENTER) or at
 * `room + border + random32() % (free space + 1)` (RANDOM_POSITION, a [ftl.browser.NativeRandom] draw; a loaded ship
 * uses a presentation draw instead, HS restores the saved position), one `tileAnim` per room cell, and `wallAnim`
 * along the four walls turned to face inward. All loop from their first frame and advance with the game
 * (ShipManager::OnLoop).
 *
 * Layers: 0 after the room floor, 1 before the breaches (both skipped while the room is not visible), 2 before the
 * sparks (here: after the breaches, as the engine draws breaches and fires per room), 3 after the ship while its
 * interior shows, 4 after the ship always.
 */
object HsRoomAnims {
    class Anim(
        @JvmField val layer: Int, @JvmField val room: Room,
        @JvmField val anim: FTLAnimation?, @JvmField val x: Float, @JvmField val y: Float,
        @JvmField val w: Float, @JvmField val h: Float,
        @JvmField val tiles: List<FTLAnimation>, @JvmField val wall: FTLAnimation?,
    )

    private val byShip = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, List<Anim>>())

    /** Room_Extend::erosion.anim: made by StartErosion, gone with StopErosion (a new erosion state). */
    private val erosionAnims = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.hs.systems.HsErosionRoom.State, Anim>())

    /** RoomAnim(def, room) for a room stat boost's or an erosion effect's `<roomAnim>`. */
    @JvmStatic
    fun make(room: Room, node: org.jdom2.Element): Anim = make(room.ship, room, xyz.znix.xftl.hs.ship.HsShips.parseRoomAnim(node), false)

    @JvmStatic
    fun erosionStarted(room: Room, e: xyz.znix.xftl.hs.systems.HsErosionRoom.State) {
        val node = e.anim ?: return
        erosionAnims[e] = make(room, node)
    }

    /** RoomAnim::OnUpdate. */
    @JvmStatic
    fun update(a: Anim, dt: Float) {
        a.anim?.update(dt)
        for (t in a.tiles) t.update(dt)
        a.wall?.update(dt)
    }

    /** The room's stat boost animations (in boost order), then its erosion animation, as HS draws them. */
    private fun extra(room: Room): List<Anim> {
        val boosts = xyz.znix.xftl.hs.stat.StatBoostManager.roomBoosts[room]
        val erosion = xyz.znix.xftl.hs.systems.HsErosionRoom.state(room)?.let { erosionAnims[it] }
        if (boosts.isNullOrEmpty() && erosion == null) return emptyList()
        val out = ArrayList<Anim>()
        if (boosts != null) for (b in boosts) b.anim?.let { out += it }
        if (erosion != null) out += erosion
        return out
    }

    /** ShipManager_Extend::Initialize ([loaded]: Ship::LoadState, no generation draw). */
    @JvmStatic
    fun create(ship: Ship, defs: Map<Int, xyz.znix.xftl.hs.ship.RoomDefinition>, loaded: Boolean) {
        val list = ArrayList<Anim>()
        for (id in defs.keys.sorted()) {
            val room = ship.rooms.getOrNull(id) ?: continue
            for (d in defs[id]!!.roomAnims) list += make(ship, room, d, loaded)
        }
        if (list.isEmpty()) byShip.remove(ship) else byShip[ship] = list
    }

    private fun anim(ship: Ship, name: String): FTLAnimation? {
        if (name.isEmpty()) return null
        val spec = ship.sys.animations.getOrNull(name) ?: run {
            xyz.znix.xftl.hs.HS.unsupported("ship", ship.type.name, "roomAnim: missing animation $name"); return null
        }
        return spec.startLooping(ship.sys)
    }

    private fun make(ship: Ship, room: Room, d: RoomAnimDef, loaded: Boolean): Anim {
        val rx = room.offsetX.toFloat(); val ry = room.offsetY.toFloat()
        val rw = room.pixelWidth; val rh = room.pixelHeight
        val a = anim(ship, d.animName)
        var x = rx; var y = ry
        var w = (a?.width ?: 0).toFloat(); var h = (a?.height ?: 0).toFloat()
        if (a != null) when (d.animType) {
            1 -> { x = rx + d.animBorder; y = ry + d.animBorder; w = (rw - 2f * d.animBorder); h = (rh - 2f * d.animBorder) }
            2 -> { x = rx + (rw - a.width) / 2; y = ry + (rh - a.height) / 2 }
            3 -> {
                val fx = rw - 2 * d.animBorder - a.width + 1
                val fy = rh - 2 * d.animBorder - a.height + 1
                fun r(n: Int) = if (loaded) (Math.random() * n).toInt() else ftl.browser.NativeRandom.next31() % n
                if (fx > 0) x = rx + d.animBorder + r(fx)
                if (fy > 0) y = ry + d.animBorder + r(fy)
            }
        }
        val tiles = ArrayList<FTLAnimation>()
        if (d.tileAnim.isNotEmpty()) for (i in 0 until room.height) for (j in 0 until room.width) anim(ship, d.tileAnim)?.let { tiles += it }
        return Anim(d.renderLayer, room, a, x, y, w, h, tiles, anim(ship, d.wallAnim))
    }

    /** ShipManager::OnLoop (CustomShips.cpp): the rooms' animations and erosion animations; stat boost ones tick
     * with their boosts (StatBoostManager.updateRoomBoosts). */
    @JvmStatic
    fun update(ship: Ship, dt: Float) {
        byShip[ship]?.let { list -> for (a in list) update(a, dt) }
        if (erosionAnims.isEmpty()) return
        for (room in ship.rooms) xyz.znix.xftl.hs.systems.HsErosionRoom.state(room)?.let { erosionAnims[it] }?.let { update(it, dt) }
    }

    @JvmStatic
    fun has(ship: Ship): Boolean = byShip.containsKey(ship) || ship.rooms.any { extra(it).isNotEmpty() }

    /** The room's layer-[layer] animations (0, 1 and 2, from Room.render; the room must be visible). */
    @JvmStatic
    fun renderRoom(g: Graphics, room: Room, layer: Int) {
        byShip[room.ship]?.let { list -> for (a in list) if (a.room === room && a.layer == layer) render(g, a) }
        for (a in extra(room)) if (a.layer == layer) render(g, a)
    }

    /** After ShipManager::OnRender: layer 4, and layer 3 while the interior shows, room by room. */
    @JvmStatic
    fun renderShip(g: Graphics, ship: Ship, interiorVisible: Boolean) {
        val list = byShip[ship]
        val canSee = interiorVisible && !ship.isDead
        for (room in ship.rooms) {
            if (list != null) for (a in list) if (a.room === room && (a.layer == 4 || (canSee && a.layer == 3))) render(g, a)
            for (a in extra(room)) if (a.layer == 4 || (canSee && a.layer == 3)) render(g, a)
        }
    }

    /** RoomAnim::OnRender. */
    private fun render(g: Graphics, a: Anim) {
        a.anim?.currentFrame?.draw(a.x, a.y, a.w, a.h, Colour.white)
        a.room.let { r ->
            var k = 0
            for (i in 0 until r.height) for (j in 0 until r.width) {
                val t = a.tiles.getOrNull(k++) ?: continue
                t.currentFrame.draw(r.offsetX + 35f * j, r.offsetY + 35f * i)
            }
        }
        val wall = a.wall ?: return
        val px = a.room.offsetX.toFloat(); val py = a.room.offsetY.toFloat()
        val w = a.room.width; val h = a.room.height
        val img = wall.currentFrame
        for (i in 0 until w) { g.pushTransform(); g.translate(px + i * 35, py + h * 35 - 35); img.draw(0f, 0f); g.popTransform() }
        for (i in 0 until w) { g.pushTransform(); g.translate(px + i * 35 + 35, py + 35f); g.rotate(0f, 0f, 180f); img.draw(0f, 0f); g.popTransform() }
        for (i in 0 until h) { g.pushTransform(); g.translate(px + 35, py + i * 35f); g.rotate(0f, 0f, 90f); img.draw(0f, 0f); g.popTransform() }
        for (i in 0 until h) { g.pushTransform(); g.translate(px + w * 35 - 35f, py + i * 35f + 35f); g.rotate(0f, 0f, -90f); img.draw(0f, 0f); g.popTransform() }
    }
}
