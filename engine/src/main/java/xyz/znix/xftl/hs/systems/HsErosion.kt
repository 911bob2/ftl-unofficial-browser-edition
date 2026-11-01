// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/ErosionEffect.cpp
// (Room_Extend::StartErosion / StopErosion, ShipManager::UpdateEnvironment and ShipSystem::PartialRepair hooks),
// src/game/Ship/CustomShips.cpp (erosion save state). Written by the weapons workstream, kept in hs.systems.
package xyz.znix.xftl.hs.systems

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.weapons.HsErosion
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.layout.Room

/**
 * Room_Extend::erosion: a room being eroded loses system integrity, then opens and widens breaches.
 *
 * Units: HS works in FTL's per-frame speed factor (16 x the frame time) and in Repairable damage units
 * (0..100). The engine's system repair/damage progress is 0..1 and a breach's health is 0..1, so amounts are
 * divided by [MAX_DAMAGE]: an OuterHull's fMaxDamage, taken as 100 like the other repairables (fire, systems);
 * `newBreachDamage` defaults to that value.
 *
 * Breaches: FTL keeps one OuterHull per room tile (fDamage > 0 is a breach, fDamage < fMaxDamage a partly repaired
 * one); the engine's Room.breaches is the same per slot, so slot order stands for vOuterWalls order.
 */
object HsErosionRoom {
    const val MAX_DAMAGE = 100f

    /** Room_Extend::erosion (timer in seconds, speed per speed factor unit, amount towards a new breach). */
    class State {
        @JvmField var timer = 0f
        @JvmField var speed = 0f
        @JvmField var systemRepairMultiplier = 1f
        @JvmField var amount = 0f
        /** RoomAnimDef of the effect that started it (rendering belongs to the render workstream). */
        @JvmField var anim: Element? = null
    }

    @JvmStatic
    fun state(room: Room): State? = room.hsSys?.erosion

    @JvmStatic
    fun active(room: Room?): Boolean = room != null && (room.hsSys?.erosion?.timer ?: 0f) > 0f

    /** Room_Extend::StartErosion: time and speed grow up to the effect's maximum (or shrink for negative values). */
    @JvmStatic
    fun start(room: Room, effect: HsErosion) {
        val e = HsTemporal.roomState(room).erosion ?: State().also { HsTemporal.roomState(room).erosion = it }
        if (effect.time > 0f) {
            if (effect.timeMax > e.timer) e.timer = minOf(e.timer + effect.time, effect.timeMax)
        } else {
            e.timer = maxOf(0f, e.timer + effect.time)
        }
        if (effect.speed > 0f) {
            if (effect.speedMax > e.speed) e.speed = minOf(e.speed + effect.speed, effect.speedMax)
        } else {
            e.speed = maxOf(0f, e.speed + effect.speed)
        }
        e.systemRepairMultiplier = minOf(e.systemRepairMultiplier, effect.systemRepairMultiplier)
        if (effect.roomAnim != null && e.anim == null) { e.anim = effect.roomAnim; xyz.znix.xftl.hs.render.HsRoomAnims.erosionStarted(room, e) }
    }

    /** Room_Extend::StopErosion. */
    @JvmStatic
    fun stop(room: Room) {
        room.hsSys?.erosion = null
    }

    /** ShipSystem::PartialRepair hook: repairs in an eroding room run at systemRepairMultiplier. */
    @JvmStatic
    fun repairMultiplier(room: Room?): Float {
        val e = room?.hsSys?.erosion ?: return 1f
        return if (e.timer > 0f) e.systemRepairMultiplier else 1f
    }

    /** ShipManager::UpdateEnvironment (after the original), every room of [ship] with erosion. */
    @JvmStatic
    fun update(ship: xyz.znix.xftl.Ship, dt: Float) {
        val cfg = HS.cfg()
        for (room in ship.rooms) {
            val e = room.hsSys?.erosion ?: continue
            if (e.timer <= 0f) continue
            val speedFactor = 16f * dt * (if (cfg.erosionCanDilate) HsTemporal.factor(room) else 1f)
            var amount = e.speed * speedFactor

            val sys = room.system
            if (sys != null) amount = sys.hsErode(amount / MAX_DAMAGE) * MAX_DAMAGE

            if (amount > 0f) {
                val mult = cfg.erosionGrowBreachMultiplier
                // expand any existing partial breaches. HS: `float diff = fDamage < fMaxDamage;` is 1 or 0.
                for (breach in room.breaches) {
                    if (breach == null || breach.health <= 0f) continue
                    val diff = if (breach.health * MAX_DAMAGE < MAX_DAMAGE) 1f else 0f
                    if (diff > 0f) {
                        if (diff >= amount * mult) {
                            breach.health += amount * mult / MAX_DAMAGE
                            amount = 0f
                            break
                        } else {
                            breach.health = 1f
                            amount -= diff / mult
                        }
                    }
                }
                // check unbreached tiles
                if (amount > 0f) {
                    e.amount += amount
                    // Ship::vOuterWalls lists a room's tiles column by column, the room's slots go row by row.
                    val open = room.breaches.indices.filter { room.breaches[it].let { b -> b == null || b.health <= 0f } }
                        .sortedWith(compareBy({ room.slotToPoint(it).x }, { room.slotToPoint(it).y }))
                    if (open.isEmpty()) {
                        e.amount = 0f
                    } else if (e.amount >= cfg.erosionNewBreachRequirement) {
                        val slot = open[HsWeapons.r32() % open.size]
                        room.spawnBreachAt(slot)
                        room.breaches[slot]!!.health = minOf(cfg.erosionNewBreachDamage, MAX_DAMAGE) / MAX_DAMAGE
                        e.amount = 0f
                    }
                }
            }

            e.timer -= speedFactor * 0.0625f
            if (e.timer <= 0f) stop(room)
        }
    }

    /** CustomShips.cpp saves timer, speed and systemRepairMultiplier (and the anim); amount is not saved. */
    @JvmStatic
    fun save(room: Room, elem: Element) {
        val e = room.hsSys?.erosion ?: return
        if (e.timer <= 0f) return
        val x = Element("hsErosion")
        x.setAttribute("timer", e.timer.toString())
        x.setAttribute("speed", e.speed.toString())
        x.setAttribute("systemRepairMultiplier", e.systemRepairMultiplier.toString())
        elem.addContent(x)
    }

    @JvmStatic
    fun load(room: Room, elem: Element) {
        val x = elem.getChild("hsErosion") ?: return
        val e = State()
        e.timer = x.getAttributeValue("timer")?.toFloatOrNull() ?: 0f
        e.speed = x.getAttributeValue("speed")?.toFloatOrNull() ?: 0f
        e.systemRepairMultiplier = x.getAttributeValue("systemRepairMultiplier")?.toFloatOrNull() ?: 1f
        HsTemporal.roomState(room).erosion = e
    }
}
