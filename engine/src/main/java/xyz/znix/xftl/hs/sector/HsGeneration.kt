// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (StarMap::GenerateMap and GenerateEvents
// hooks, GenerateNebulas) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.sector

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.CustomSector
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsFlow
import xyz.znix.xftl.hs.events.HsReq
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.GameMap
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sector.SectorType

/** A sector's HS generation state: its description's event counts, which GenerateEvents edits in place. */
class HsSectorState(@JvmField val eventCounts: ArrayList<SectorType.EventInfo>)

/**
 * The HS hooks around the map generation (NativeSectorGenerator): priority events and sector quests before the
 * events are placed, the nebula name cap, and the exit beacon / onGenerate / rename passes once the map is done.
 */
object HsGeneration {
    /** GenerateMap (not loading): sector quests are added once per generated sector. */
    private var needSectorQuests = false

    fun customSector(game: InGameState, info: GameMap.SectorInfo): CustomSector? =
        game.eventManager.hs?.getCustomSector(HsEvents.customSectorName(info))

    /** GenerateMap (CustomEvents.cpp), before the original: the per-map HS lists restart. */
    fun beforeGenerateMap(info: GameMap.SectorInfo) {
        HsEvents.regeneratedBeacons.clear()
        HsEvents.renamedBeacons.clear()
        needSectorQuests = true
        HsEvents.generatingSector = info
        HsFlow.lastLocationEvent = null
    }

    /**
     * GenerateEvents (CustomEvents.cpp): the priority events whose req holds go before (priority >= 0) or after the
     * description's event counts, which keep them (the list is edited in place), and the sector quests (req held)
     * go to the front of the delayed quests as "QUEST\t-1\t<event>". Returns the counts the original then places.
     */
    fun eventCounts(game: InGameState, info: GameMap.SectorInfo, delayedQuests: MutableList<String>): List<SectorType.EventInfo> {
        val state = info.hsState as? HsSectorState ?: HsSectorState(ArrayList(info.type.events)).also { info.hsState = it }
        val sec = customSector(game, info) ?: return state.eventCounts
        val high = ArrayList<SectorType.EventInfo>()
        val low = ArrayList<SectorType.EventInfo>()
        HsEvents.savedPriorityEventReq.clear()
        for (p in sec.priorityEventCounts) {
            if (p.req.isNotEmpty()) {
                // The original's player ship exists by now; here the ship is made after the first map, so the
                // first sector's reqs see only the names that need no ship (variables, sector, difficulty...).
                var level = 0
                HsEvents.withCheck(6) { level = HsReq.hasEquipment(game, game.player, p.req) }
                val met = level >= p.lvl && level <= p.maxLvl
                HsEvents.savedPriorityEventReq += met
                if (!met) continue
            }
            val event = game.eventManager.getOrNull(p.name)
            if (event == null) {
                HS.unsupported("missing-event", "priorityEvent", p.name)
                continue
            }
            val entry = SectorType.EventInfo(p.name, p.min..p.max, event)
            if (p.priority >= 0) high += entry else low += entry
        }
        if (high.isNotEmpty()) state.eventCounts.addAll(0, high)
        if (low.isNotEmpty()) state.eventCounts.addAll(low)

        if (needSectorQuests) {
            needSectorQuests = false
            val added = ArrayList<String>()
            for (q in sec.sectorQuests) {
                var met = q.req.isEmpty()
                if (!met) {
                    var level = 0
                    HsEvents.withCheck(6) { level = HsReq.hasEquipment(game, game.player, q.req) }
                    met = level >= q.lvl && level <= q.maxLvl
                }
                if (met) added += "QUEST\t-1\t${q.event}"
            }
            if (added.isNotEmpty()) delayedQuests.addAll(0, added)
        }
        return state.eventCounts
    }

    /** GenerateNebulas (CustomEvents.cpp): at most one name per beacon (a sector with priorityNebulaFix skips it, see [priorityNebulaFix]). */
    fun capNebulaNames(game: InGameState, info: GameMap.SectorInfo, names: MutableList<SectorType.EventInfo>, locations: Int) {
        while (names.size > locations) names.removeAt(names.size - 1)
    }

    /** The sector has `<priorityNebulaFix/>`: GenerateNebulas runs the HS rewrite. */
    fun priorityNebulaFix(game: InGameState, info: GameMap.SectorInfo): Boolean =
        customSector(game, info)?.priorityNebulaFix == true

    /**
     * The count of non-nebula priority events (their minimum counts) whose req holds, as both GenerateNebulas hooks of a
     * `<priorityNebulaFix/>` sector count it (`G_->GetShipManager(0)->HasEquipment`, no advanced-check flag).
     */
    fun priorityEventCount(game: InGameState, info: GameMap.SectorInfo): Int {
        val sec = customSector(game, info) ?: return 0
        var total = 0
        for (p in sec.priorityEventCounts) {
            if (p.name.startsWith("NEBULA")) continue
            if (p.req.isNotEmpty()) {
                val level = HsReq.hasEquipment(game, game.player, p.req)
                if (level < p.lvl || level > p.maxLvl) continue
            }
            total += p.min
        }
        return total
    }

    /**
     * The default-priority GenerateNebulas hook (CustomEvents.cpp), which runs before the priority-9998 rewrite of a
     * `<priorityNebulaFix/>` sector and hands it its names: at most one per beacon, the missing minimum of each nebula
     * priority event put back at the end, and (when the beacons left for the priority events are too few) nebulas taken
     * off the end of the list - first those above their `<event>` minimum, then any that is not a priority event.
     */
    fun priorityNebulaNames(game: InGameState, info: GameMap.SectorInfo, names: MutableList<SectorType.EventInfo>, locations: Int) {
        val sec = customSector(game, info) ?: return
        while (names.size > locations) names.removeAt(names.size - 1)
        val counts = (info.hsState as? HsSectorState)?.eventCounts ?: info.type.events
        val pEventCount = priorityEventCount(game, info)
        for (p in sec.priorityEventCounts) {
            if (!p.name.startsWith("NEBULA")) continue
            val missing = p.min - names.count { it.name == p.name }
            val entry = counts.firstOrNull { it.name == p.name } ?: continue
            for (i in 0 until missing) names.add(entry)
        }
        val nonNebulaLocations = locations - 2 - names.size
        if (nonNebulaLocations >= pEventCount) return
        var toRemove = pEventCount - nonNebulaLocations
        val priorityNames = sec.priorityEventCounts.map { it.name }.toSet()
        for (pass in 1..2) {
            var last = names.size - 1
            while (last >= 0 && toRemove > 0) {
                val name = names[last].name
                var first = last - 1
                while (first >= 0 && names[first].name == name) first--
                if (name !in priorityNames) {
                    val current = last - first
                    val limit = if (pass == 1) counts.firstOrNull { it.name == name }?.count?.first else 0
                    if (limit != null && (pass == 2 || current > limit)) {
                        val remove = minOf(toRemove, current - limit)
                        for (k in 0 until remove) names.removeAt(last - k)
                        toRemove -= remove
                    }
                }
                last = first
            }
        }
    }

    /** GenerateNebulas (after the original): removeFirstBeaconNebula takes the start beacon out of its cloud. */
    fun removeFirstBeaconNebula(game: InGameState, info: GameMap.SectorInfo): Boolean =
        customSector(game, info)?.removeFirstBeaconNebula == true

    /**
     * GenerateMap (CustomEvents.cpp), after the original (and its delayed quests): the exit beacon takes the custom
     * sector's exit event (rebel/nebula variants), onGenerate load lists replace beacon events (seed
     * `(int)(x + y) ^ sector seed`), and renamed beacons keep their names.
     */
    fun afterGenerateMap(game: InGameState, sector: Sector, tutorial: Boolean) {
        val info = sector.info
        HsEvents.generatingSector = info
        try {
            val custom = customSector(game, info)
            val bossLevel = sector.bosses.isNotEmpty() || info.type.name == "FINAL"
            if (!tutorial && !bossLevel && custom != null) {
                val exit = custom.exitBeacons
                for (beacon in sector.beacons) {
                    if (!beacon.isExit) continue
                    val name = when {
                        beacon.isOvertaken && exit.rebelEvent.isNotEmpty() -> exit.rebelEvent
                        beacon.inNebula && exit.nebulaEvent.isNotEmpty() -> exit.nebulaEvent
                        exit.event.isNotEmpty() -> exit.event
                        else -> ""
                    }
                    if (name.isNotEmpty()) {
                        val root = game.eventManager.getOrNull(name)
                        val created = root?.let { game.eventCreator.create(it, sector.sectorNumber) }
                        if (created != null) {
                            beacon.createdEvent = created
                            if (beacon.inNebula) beacon.forcedNebula = true
                        } else if (root == null) HS.unsupported("missing-event", "exitBeacon", name)
                    }
                    if (custom.noExit) HS.unsupported("sector", info.type.name, "noExit")
                }
                if (custom.nebulaSector.enabled) HS.unsupported("sector", info.type.name, "nebulaSector")
            }

            val parser = game.eventManager.hs ?: return
            for ((index, beacon) in sector.beacons.withIndex()) {
                var custom2 = HsFlow.customFor(game, beacon.createdEvent)
                while (custom2 != null && custom2.eventLoadList?.onGenerate == true) {
                    val list = custom2.eventLoadList!!
                    val seed = if (list.seeded) (beacon.pos.x + beacon.pos.y) xor HsSeeds.currentSectorSeed else -1
                    var created: xyz.znix.xftl.sector.CreatedEvent? = null
                    HsEvents.withCheck(6) { created = HsFlow.getEvent(game, list, seed) }
                    val newEvent = created ?: break
                    beacon.createdEvent = newEvent
                    HsEvents.regeneratedBeacons[index] = Pair(newEvent.template.hsName ?: "", seed)
                    custom2 = parser.getCustomEvent(newEvent.template.hsName)
                }
            }
            for ((index, name) in HsEvents.renamedBeacons) sector.beacons.getOrNull(index)?.hsName = name
        } finally {
            HsEvents.generatingSector = null
        }
    }

    /**
     * StarMap::TurnIntoFleetLocation (CustomEvents.cpp, a replacement of the original): the fleet's event for a beacon it
     * takes. The base name is FLEET_EASY_BEACON for the exit (not in the last stand), else FLEET_EASY_NEBULA in a nebula,
     * else FLEET_EASY, with `_DLC` appended when the DLC is on and the beacon is not a nebula. Then the custom sector's
     * exit `rebelEvent` replaces it on the exit (not in the last stand), and its `<rebelBeacon>` event (nebulaEvent in a
     * nebula) on any other beacon (every beacon in the last stand). Each is GetBaseEvent(name, level, false, -1), so
     * every replaced event's draws are made too. Returns the event and whether it is a nebula exit (+0x604 = 3).
     */
    fun fleetEvent(game: InGameState, creator: xyz.znix.xftl.sector.EventCreator, info: GameMap.SectorInfo, exit: Boolean,
                   nebula: Boolean, bossLevel: Boolean, level: Int, dlc: Boolean): Pair<xyz.znix.xftl.sector.CreatedEvent?, Boolean> {
        val exitBeacon = exit && !bossLevel
        var name = when {
            exitBeacon -> "FLEET_EASY_BEACON"
            nebula -> "FLEET_EASY_NEBULA"
            else -> "FLEET_EASY"
        }
        if (dlc && !nebula) name += "_DLC"
        fun create(n: String): xyz.znix.xftl.sector.CreatedEvent? {
            val root = game.eventManager.getOrNull(n) ?: run { HS.unsupported("missing-event", "TurnIntoFleetLocation", n); return null }
            return creator.create(root, level, false)
        }
        var event = create(name)
        var nebulaExit = exitBeacon && nebula
        val custom = customSector(game, info)
        if (custom != null) {
            if (!bossLevel && exitBeacon && custom.exitBeacons.rebelEvent.isNotEmpty()) {
                create(custom.exitBeacons.rebelEvent)?.let { event = it; if (nebula) nebulaExit = true }
            }
            if (!exitBeacon) {
                val fleet = custom.fleetBeacons
                val n = if (nebula && fleet.nebulaEvent.isNotEmpty()) fleet.nebulaEvent else fleet.event
                if (n.isNotEmpty()) create(n)?.let { event = it }
            }
        }
        return Pair(event, nebulaExit)
    }

    /**
     * Hyperspace's outer StarMap::TurnIntoFleetLocation hook (CustomEvents.cpp, the plain HOOK_METHOD that wraps the
     * priority-9999 replacement): an unvisited beacon whose event has `<preventFleet/>` is not taken at all, and one
     * with `<runFromFleet/>` first moves its event to another beacon (the global stream: `random32() % n` picks the
     * beacon, the next `random32()` seeds the new GetBaseEvent). Returns false when the fleet must leave the beacon
     * alone. [jumpTarget] is StarMap::potentialLoc, the beacon the jump is going to (null on a wait).
     */
    fun beforeOvertake(game: InGameState, sector: Sector, loc: Beacon, jumpTarget: Beacon?): Boolean {
        val custom = HsFlow.customFor(game, loc.createdEvent) ?: return true
        if (custom.preventFleet && !loc.visited) return false
        if (!loc.visited && custom.runFromFleet != 0) runFromFleet(game, sector, loc, custom.runFromFleet == 1, jumpTarget)
        return true
    }

    /**
     * Diagnostics (r8 resync, null in production): replaces the destination index of a runFromFleet relocation. Arguments:
     * the event name, the candidate beacons as "x,y", our index, whether the draw followed unpaused frames since the last reseed.
     */
    @JvmField var steerFleet: ((String, List<String>, Int, Boolean) -> Int)? = null

    private fun runFromFleet(game: InGameState, sector: Sector, loc: Beacon, requireClosest: Boolean, jumpTarget: Beacon?) {
        val current = game.currentBeacon
        // Location::known (verified against the oracle): visited, or next to a visited beacon.
        fun known(b: Beacon) = b.visited || b.neighbours.any { it.visited }
        // StarMap::Dijkstra(loc, to, false) walks known beacons only; its path is empty when either end is unknown or
        // no known route joins them, and the size counts the beacons on the route.
        fun pathSize(to: Beacon): Int {
            if (!known(loc) || !known(to)) return 0
            val previous = HashMap<Beacon, Beacon?>()
            val queue = ArrayDeque<Beacon>()
            previous[loc] = null; queue.add(loc)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (c === to) {
                    var n = 0; var step: Beacon? = to
                    while (step != null && step !== loc) { n++; step = previous[step] }
                    return n + 1
                }
                for (next in c.neighbours) {
                    if (previous.containsKey(next) || !known(next)) continue
                    previous[next] = c; queue.add(next)
                }
            }
            return 0
        }

        // The last stand's flagship leaves a danger zone behind it, the beacon it started on included.
        val flagshipTrail = sector.bosses.filterIsInstance<xyz.znix.xftl.game.FlagshipBoss>().flatMap { it.browserStoodOn() }.toSet()
        var closest = 9999
        val allowed = ArrayList<Beacon>()
        for (i in sector.beacons) {
            if (i.isExit || i.visited || i.isOvertaken || i.hasQuest || i.browserFleetThreatened || i in flagshipTrail) continue
            if (i.isMapNebula != loc.isMapNebula || i === current || i === jumpTarget) continue
            if (i.hasStore || i.event.isLastStandRepair) continue
            if (HsFlow.customFor(game, i.createdEvent)?.preventQuest == true) continue
            if (requireClosest) {
                val size = pathSize(i)
                if (size < closest) { allowed.clear(); closest = size }
                if (size <= closest) allowed.add(i)
            } else {
                allowed.add(i)
            }
        }
        if (allowed.isEmpty()) return

        val name = loc.createdEvent.template.hsName ?: return
        loc.hasQuest = false
        val live = steerFleet != null && ftl.browser.NativeRandom.isLive()
        var destIndex = ftl.browser.NativeRandom.nextInt(allowed.size)
        steerFleet?.let { destIndex = it(name, allowed.map { b -> "${b.pos.x},${b.pos.y}" }, destIndex, live) }
        val dest = allowed[destIndex]
        val seed = ftl.browser.NativeRandom.next31()
        val created = HsFlow.getBaseEvent(game, name, sector.sectorNumber, false, seed) ?: return
        dest.createdEvent = created
        dest.hasQuest = true
        dest.questName = name
        dest.clearEnvironment()
        if (bossBlockedNow != null) bossBlockedNow = bossFleetBlocked(game, sector)
    }

    /** The last stand's update in progress: the beacons BossFleetPrevention holds out (null outside it). */
    @JvmField var bossBlockedNow: Set<Beacon>? = null

    /**
     * BossFleetPrevention::Set (CustomEvents.h): the beacons the last stand's fleet may not take this turn. Every
     * beacon that is not taken, not threatened and not the base is either visited (a vulnerable one) or has
     * `<preventBossFleet>` (1 or, with forever, 2) or `<preventFleet>` (2); one with a flag is held back while any
     * vulnerable beacon exists, or always for flag 2.
     */
    fun bossFleetBlocked(game: InGameState, sector: Sector): Set<Beacon> {
        val flags = HashMap<Beacon, Int>()
        var vulnerable = false
        for (b in sector.beacons) {
            if (b.isOvertaken || b.browserFleetThreatened || b.isBase) continue
            if (b.visited) { flags[b] = 0; vulnerable = true; continue }
            val custom = HsFlow.customFor(game, b.createdEvent)
            if (custom != null && (custom.preventBossFleet != 0 || custom.preventFleet)) {
                flags[b] = if (custom.preventFleet) 2 else custom.preventBossFleet
            } else {
                flags[b] = 0; vulnerable = true
            }
        }
        val blocked = HashSet<Beacon>()
        for ((b, flag) in flags) if (flag != 0 && (vulnerable || flag == 2)) blocked.add(b)
        return blocked
    }

    /** CustomEventsParser::LocationRemoveNebula for a generated beacon. */
    fun removeNebula(beacon: Beacon) {
        beacon.inNebula = false
        beacon.forcedNebula = false
    }
}
