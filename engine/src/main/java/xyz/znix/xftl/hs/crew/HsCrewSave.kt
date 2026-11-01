// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewMember::SaveState / LoadState
// hooks), src/game/Ship/StatBoost.cpp (StatBoost::Save / LoadStatBoost)
package xyz.znix.xftl.hs.crew

import org.jdom2.Element
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.stat.HsTimer
import xyz.znix.xftl.hs.stat.StatBoost
import xyz.znix.xftl.hs.stat.StatBoostDefs

/**
 * The per-crew Hyperspace state HS appends to CrewMember::SaveState, kept as an `<hsCrew>` child of the crew
 * element (only Hyperspace crew write it, so vanilla saves are unchanged).
 */
object HsCrewSave {
    private fun timer(e: Element, name: String, t: HsTimer?) {
        if (t != null) e.setAttribute(name, "${t.currGoal},${t.currTime},${t.running}")
    }

    private fun readTimer(e: Element, name: String): HsTimer? {
        val v = e.getAttributeValue(name) ?: return null
        val p = v.split(',')
        return HsTimer().also { it.start(p[0].toFloat()); it.currTime = p[1].toFloat(); it.running = p[2].toBoolean() }
    }

    fun save(crew: AbstractCrew, ex: CrewExtend, e: Element, healthMax: Float) {
        e.setAttribute("selfId", ex.selfId.toString())
        e.setAttribute("health", "${crew.health},$healthMax")
        e.setAttribute("noSlot", ex.noSlot.toString())
        timer(e, "passiveHeal", ex.passiveHealTimer)
        timer(e, "death", ex.deathTimer)
        e.setAttribute("isHealing", ex.isHealing.toString())
        e.setAttribute("prevStun", ex.prevStun.toString())
        e.setAttribute("originalColorRace", ex.originalColorRace)
        e.setAttribute("originalRace", ex.originalRace)
        e.setAttribute("exploded", "${ex.triggerExplosion},${ex.exploded}")
        for (p in ex.crewPowers) e.addContent(Element("power").also { p.save(it) })
        for (r in ex.powerResources) e.addContent(Element("resource").also { r.save(it) })
        for (list in ex.timedStatBoosts.values) for (b in list) saveBoost(b, "timedBoost")?.let { e.addContent(it) }
    }

    /** StatBoost::Save: the definition by realBoostId, stacks, source, timer and source rooms; null if unregistered. */
    fun saveBoost(b: StatBoost, name: String): Element? {
        val id = b.def.realBoostId
        if (id < 0) {
            xyz.znix.xftl.hs.HS.unsupported("save", "stat boost", "unregistered definition")
            return null
        }
        val be = Element(name)
        be.setAttribute("def", id.toString())
        be.setAttribute("ids", "2") // HS's numbering (StatBoostDefs.weaponsFirst)
        be.setAttribute("stacks", b.iStacks.toString())
        be.setAttribute("source", "${b.crewSourceId},${b.sourceShipId}")
        timer(be, "timer", b.timer)
        be.setAttribute("rooms0", b.sourceRoomIds0.joinToString(","))
        be.setAttribute("rooms1", b.sourceRoomIds1.joinToString(","))
        return be
    }

    /** StatBoost::LoadStatBoost. */
    fun loadBoost(be: Element): StatBoost? {
        val def = StatBoostDefs.bySavedId(be.getAttributeValue("def").toInt(), be.getAttributeValue("ids") == "2") ?: return null
        val b = StatBoost(def)
        b.iStacks = be.getAttributeValue("stacks").toInt()
        be.getAttributeValue("source").split(',').let { b.crewSourceId = it[0].toInt(); b.sourceShipId = it[1].toInt() }
        b.timer = readTimer(be, "timer") ?: HsTimer()
        be.getAttributeValue("rooms0").takeIf { it.isNotEmpty() }?.split(',')?.forEach { b.sourceRoomIds0.add(it.toInt()) }
        be.getAttributeValue("rooms1").takeIf { it.isNotEmpty() }?.split(',')?.forEach { b.sourceRoomIds1.add(it.toInt()) }
        return b
    }

    /**
     * ShipManager::ExportShip / ImportShip (CustomShips.cpp): each room's stat boosts (praetor spores etc.) as
     * `<hsRoomBoost>` children of the room element.
     */
    @JvmStatic
    fun saveRoomBoosts(room: xyz.znix.xftl.layout.Room, elem: Element) {
        for (rb in xyz.znix.xftl.hs.stat.StatBoostManager.roomBoosts[room] ?: return)
            saveBoost(rb.statBoost, "hsRoomBoost")?.let { elem.addContent(it) }
    }

    @JvmStatic
    fun hasRoomBoosts(room: xyz.znix.xftl.layout.Room): Boolean =
        xyz.znix.xftl.hs.stat.StatBoostManager.roomBoosts[room]?.isNotEmpty() == true

    @JvmStatic
    fun loadRoomBoosts(room: xyz.znix.xftl.layout.Room, elem: Element) {
        for (be in elem.getChildren("hsRoomBoost")) {
            val b = loadBoost(be) ?: continue
            xyz.znix.xftl.hs.stat.StatBoostManager.roomBoosts.getOrPut(room) { ArrayList() }
                .add(xyz.znix.xftl.hs.stat.RoomStatBoost(b, room))
        }
    }

    /** Restores the state; returns the saved (health, maximum) for the caller to apply after its own load. */
    fun load(crew: AbstractCrew, ex: CrewExtend, e: Element): Pair<Float, Float>? {
        ex.selfId = e.getAttributeValue("selfId").toInt()
        HsCrew.observeId(ex.selfId)
        ex.noSlot = e.getAttributeValue("noSlot").toBoolean()
        ex.passiveHealTimer = readTimer(e, "passiveHeal")
        ex.deathTimer = readTimer(e, "death")
        ex.isHealing = e.getAttributeValue("isHealing")?.toBoolean() ?: false
        ex.prevStun = e.getAttributeValue("prevStun")?.toFloat() ?: 0f
        ex.originalColorRace = e.getAttributeValue("originalColorRace") ?: crew.codename
        ex.originalRace = e.getAttributeValue("originalRace") ?: crew.codename
        e.getAttributeValue("exploded")?.split(',')?.let { ex.triggerExplosion = it[0].toBoolean(); ex.exploded = it[1].toBoolean() }

        ex.crewPowers.clear(); ex.powerResources.clear(); ex.powerResourceMap.clear()
        ex.hasSpecialPower = false
        for (pe in e.getChildren("power")) {
            val p = ActivatedPower.load(pe, ex)
            ex.crewPowers.add(p)
            if (p.enabled) ex.hasSpecialPower = true
        }
        for (re in e.getChildren("resource")) {
            val r = ActivatedPowerResource.load(re, ex)
            ex.powerResources.add(r)
            ex.powerResourceMap[r.def.index] = r
        }
        for (p in ex.crewPowers) p.linkPowerResources()
        ex.updateAbilityStatBoosts()

        ex.timedStatBoosts.clear()
        for (be in e.getChildren("timedBoost")) {
            val b = loadBoost(be) ?: continue
            ex.timedStatBoosts.getOrPut(b.def.stat) { ArrayList() }.add(b)
        }
        // stats computed earlier in this frame (crew construction) must not outlive the restored powers
        xyz.znix.xftl.hs.stat.StatBoostManager.statCacheFrame++

        val h = e.getAttributeValue("health")?.split(',') ?: return null
        return h[0].toFloat() to h[1].toFloat()
    }
}
