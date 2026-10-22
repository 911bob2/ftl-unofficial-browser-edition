package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.game.BossManager
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import ftl.browser.NativeRandom
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Represents an in-game sector. Handles the placement of the beacons within it.
 */
class Sector {
    val browserDifficulty: Difficulty
    val info: GameMap.SectorInfo

    /**
     * The zero-indexed position of this sector in the sector map. Zero is the starting
     * sector, 7 is the last stand.
     */
    val sectorNumber: Int get() = info.columnNumber

    val type: SectorType get() = info.type

    val isLastStand: Boolean get() = info.type.name == "FINAL"

    val beacons = ArrayList<Beacon>()

    // The start and finish beacon. In the final sector, the finish
    // beacon represents the location of the rebel base.
    val startBeacon: Beacon
    val finishBeacon: Beacon

    // The position of the centre of the danger zone circle.
    // See doc/sector-map (heading 'Rebel fleet advance') for more
    // information about this.
    val dangerZoneCentre: Point

    /**
     * This is added to by the <modifyPursuit> event tag.
     *
     * A positive number means the pursuit is doubled for that many
     * jumps, while a negative number means there's no pursuit for
     * that many jumps.
     */
    var fleetAdvanceModifier: Int = 0

    /**
     * If true, all beacons are displayed with their full information
     * visible (environmental hazards, ship presence, store/distress
     * labels, etc).
     *
     * This is set by events with the <reveal_map/> tag.
     */
    var mapRevealed: Boolean = false

    /**
     * The map nebula clouds (StarMap +0x69c), drawn behind the beacons.
     * Empty for sectors generated before clouds were recorded.
     */
    val nebulaClouds = ArrayList<NebulaCloud>()

    class NebulaCloud(val image: String, val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The boss ships present in this sector.
     *
     * Without mods this is either empty or contains the flagship, but mods can
     * theoretically add multiple bosses.
     */
    val bosses = ArrayList<BossManager>()

    /**
     * The seed this sector's beacon map was generated from (StarMap +0x9a8).
     * Regenerating from it gives the same beacons, links and events, as the
     * original's LoadGame does; it is also the vanilla save's layout seed.
     */
    var layoutSeed: Int = 0
        private set

    /**
     * The seed of the no-fuel wait (StarMap +0x9ac), or null when the player
     * isn't waiting. It's the vanilla save's wait_event_seed.
     */
    var waitSeed: Int? = null

    // Note there's another constructor for deserialising a sector
    // from XML down at the bottom of the class.

    /**
     * Build a newly generated sector from [NativeSectorGenerator]'s layout.
     */
    constructor(info: GameMap.SectorInfo, difficulty: Difficulty, layout: NativeSectorGenerator.Layout) {
        this.info = info
        browserDifficulty = difficulty
        layoutSeed = layout.layoutSeed

        beacons += layout.beacons
        for (beacon in beacons)
            beacon.bindSector(this, layout.neighbours.getValue(beacon))

        startBeacon = layout.start
        finishBeacon = layout.finish
        dangerZoneCentre = layout.dangerZoneCentre
        nebulaClouds += layout.nebulaClouds

        // The flagship is set up by GameMap.generateSector
    }

    /**
     * StarMap::GetNextDangerMove (0x5edbf0): the fleet stops once its centre
     * reaches x = 60; otherwise 64 px, 32 from a nebula beacon (51 if the
     * sector is a nebula), doubled or stopped by the pursuit modifier.
     */
    fun getFleetAdvanceFor(beacon: Beacon): Int {
        if (dangerZoneCentre.x >= 60)
            return 0

        val isNebula = beacon.isMapNebula

        val base = when {
            // Non-nebula beacons use normal advance
            !isNebula -> DANGER_ZONE_ADVANCE

            // Nebula beacons in normal sectors use half the normal advance
            info.sectorClass != GameMap.SectorClass.NEBULA -> DANGER_ZONE_ADVANCE / 2

            // In nebula beacons, they use 80% of the normal advance.
            else -> (DANGER_ZONE_ADVANCE * 0.8f).toInt()
        }

        return when {
            fleetAdvanceModifier < 0 -> 0
            fleetAdvanceModifier > 0 -> base * 2
            else -> base
        }
    }

    /**
     * StarMap::UpdateDangerZone (0x5f5280, the animated branch used in play) for one jump or wait: the danger zone
     * centre moves by [getFleetAdvanceFor] (no clamp), and every beacon the zone's edge (strictly under 767 px,
     * float distance) passes over on the way is overtaken by [overtake], in the order [overtakeOrder] gives.
     * The pursuit modifier then moves towards zero.
     */
    fun advanceFleet(game: InGameState, current: Beacon) {
        val start = dangerZoneCentre.x
        val end = start + getFleetAdvanceFor(current)
        val order = overtakeOrder(
            IntArray(beacons.size) { beacons[it].pos.x }, IntArray(beacons.size) { beacons[it].pos.y },
            BooleanArray(beacons.size) { beacons[it].isOvertaken }, dangerZoneCentre.y, start, end
        )
        dangerZoneCentre.x = end
        for (index in order)
            overtake(game, beacons[index])

        // The modifier is a counter of how long we apply no or double pursuit.
        if (fleetAdvanceModifier < 0)
            fleetAdvanceModifier++
        else if (fleetAdvanceModifier > 0)
            fleetAdvanceModifier--
    }

    /**
     * StarMap::LoadGame (0x5fb9a0): every visited beacon other than the current one has its event cleared
     * (ClearEvent unforced, 0x6119f0), so a hazard only survives where the player is.
     */
    fun clearVisitedEvents(current: Beacon) {
        for (beacon in beacons) {
            if (beacon.visited && beacon !== current)
                beacon.clearEvent(false, true)
        }
    }

    /** Float distance from the danger zone centre to the beacon (Location::GetDistance 0x5a4b00). */
    fun distanceToDangerZone(beacon: Beacon): Float {
        val dx = (beacon.pos.x - dangerZoneCentre.x).toFloat()
        val dy = (beacon.pos.y - dangerZoneCentre.y).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * StarMap::OvertakeLocation (0x5f4ca0): the fleet takes the beacon. It is marked overtaken, its warning mark
     * goes, and its event is replaced by a fresh fleet encounter created now, from the global stream:
     * FLEET_EASY_NEBULA in a nebula, FLEET_EASY[_DLC] otherwise (the last stand's exit never gets here),
     * or FLEET_EASY_BEACON[_DLC unless easy] for the exit beacon, which also becomes a fleet beacon (and a
     * nebula if it lies in a cloud). The new event's own environment is the beacon's hazard from then on
     * ([Beacon.environmentType]).
     */
    fun overtake(game: InGameState, beacon: Beacon) {
        beacon.isOvertaken = true
        beacon.browserFleetThreatened = false
        beacon.clearEnvironment()

        val creator = game.eventCreator
        val exitBeacon = beacon.isExit && !isLastStand
        val name = when {
            exitBeacon -> "FLEET_EASY_BEACON" + (if (browserDifficulty != Difficulty.EASY) "_DLC" else "")
            beacon.isMapNebula -> "FLEET_EASY_NEBULA"
            else -> "FLEET_EASY" + (if (game.isAdvancedEdition) "_DLC" else "")
        }
        // The fresh event drops a nebula forced on the old one; only the exit in a cloud gets its own (+0x604 = 3).
        beacon.forcedNebula = exitBeacon && beacon.isMapNebula

        val created = creator.create(creator.eventFor(name), sectorNumber) ?: return
        beacon.createdEvent = created
        beacon.hasFleetEvent = true
    }

    /**
     * StarMap::AddQuest (0x5f8920): create the quest event, then try to put
     * it on a beacon of this sector.
     *
     * This may fail if it can't find space for the event, there isn't
     * anywhere to put it where the player can reach it before the fleet
     * overruns it, etc. The event's draws are made either way.
     *
     * If [ignoreDistance] is not set, then beacons that the player can't
     * reach before they're overtaken will not be used.
     */
    fun addQuest(currentBeacon: Beacon, creator: EventCreator, questName: String, ignoreDistance: Boolean = false): QuestPlacement {
        val quest = creator.create(creator.eventFor(questName), sectorNumber) ?: return QuestPlacement.NO_EVENT

        // The placement (0x5f8920): an unvisited beacon outside nebulas, the
        // exit, the fleet and other quests, without a store or distress
        // call, reachable in fewer beacons (both ends counted) than
        // trunc((distance to the fleet centre - 767) / 64 - 1). Delayed
        // quests only need a path.
        val suitable = beacons.filter {
            if (it.visited || it.isMapNebula || it == currentBeacon)
                return@filter false
            if (it.isExit || it.hasQuest || it.hasStore || it.event.isDistressBeacon)
                return@filter false
            if (it.isOvertaken)
                return@filter false

            val path = findShortestPath(currentBeacon, it) ?: return@filter false
            val pathLength = path.size + 1

            if (!ignoreDistance) {
                val dx = (it.pos.x - dangerZoneCentre.x).toFloat()
                val dy = (it.pos.y - dangerZoneCentre.y).toFloat()
                val turnsLeft = ((kotlin.math.sqrt(dx * dx + dy * dy) - 767f) * 0.015625f - 1f).toInt()
                if (pathLength < 2 || pathLength >= turnsLeft)
                    return@filter false
            }

            return@filter true
        }

        if (suitable.isEmpty())
            return QuestPlacement.NO_SPACE

        val beacon = suitable[NativeRandom.nextInt(suitable.size)]
        beacon.hasQuest = true
        beacon.questName = questName
        beacon.createdEvent = quest
        beacon.clearEnvironment()

        return QuestPlacement.PLACED
    }

    enum class QuestPlacement {
        PLACED,

        /** The quest event exists but no beacon can take it. */
        NO_SPACE,

        /** The event can't be created, so nothing is placed or delayed. */
        NO_EVENT,
    }

    /**
     * Find the shortest path between the two beacons. This contains
     * each beacon the ship must jump to on the way - that is, when
     * there's a path between two different beacons [start] and [end],
     * the path won't contain [start] but will contain [end].
     *
     * If there is no suitable path, null is returned.
     */
    fun findShortestPath(start: Beacon, end: Beacon): List<Beacon>? {
        // Visit each beacon once, including cyclic and disconnected graphs.
        val previous = HashMap<Beacon, Beacon?>()
        val queue = ArrayDeque<Beacon>()
        previous[start] = null
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == end) {
                val path = ArrayList<Beacon>()
                var step = end
                while (step != start) {
                    path.add(step)
                    step = previous.getValue(step)!!
                }
                path.reverse()
                return path
            }
            for (next in current.neighbours) {
                if (previous.containsKey(next)) continue
                previous[next] = current
                queue.add(next)
            }
        }
        return null
    }
    fun saveToXML(elem: Element, globalRefs: ObjectRefs): ObjectRefs {
        // External stuff shouldn't be able to reference beacons
        val refs = ObjectRefs(globalRefs)

        // Register all the beacons, as they reference each other
        for (beacon in beacons) {
            refs.register(beacon, "beacon")
        }

        SaveUtil.addAttrRef(elem, "sectorInfo", refs, info)

        SaveUtil.addAttrInt(elem, "fleetAdvanceModifier", fleetAdvanceModifier)
        SaveUtil.addAttrBool(elem, "mapRevealed", mapRevealed)

        // Optional: the seeds the map can be regenerated from.
        elem.setAttribute("browserLayoutSeed", layoutSeed.toString())
        waitSeed?.let { elem.setAttribute("browserWaitSeed", it.toString()) }
        SaveUtil.addPoint(elem, "dangerZoneCentre", dangerZoneCentre)

        SaveUtil.addAttrRef(elem, "startBeacon", refs, startBeacon)
        SaveUtil.addAttrRef(elem, "finishBeacon", refs, finishBeacon)

        for (cloud in nebulaClouds) {
            val cloudElem = Element("browserNebulaCloud")
            cloudElem.setAttribute("image", cloud.image)
            SaveUtil.addAttrInt(cloudElem, "x", cloud.x)
            SaveUtil.addAttrInt(cloudElem, "y", cloud.y)
            SaveUtil.addAttrInt(cloudElem, "w", cloud.width)
            SaveUtil.addAttrInt(cloudElem, "h", cloud.height)
            elem.addContent(cloudElem)
        }

        for (boss in bosses) {
            val bossElem = Element("boss")
            SaveUtil.addAttr(bossElem, "type", boss.serialisationType)
            // Note we don't register the boss with ObjectRefs - that's done by InGameState,
            // since it needs the object ID if it's saving the boss ship layout before
            // this sector is serialised.
            SaveUtil.addObjectId(bossElem, globalRefs, boss)
            boss.saveToXML(bossElem, refs)
            elem.addContent(bossElem)
        }

        // Serialise all the beacons
        for (beacon in beacons) {
            val beaconElem = Element("beacon")
            beacon.saveToXML(beaconElem, refs)
            elem.addContent(beaconElem)
        }

        // Serialise all the neighbour connections between beacons. Since there's
        // a huge number of these, we process them here rather than inside the
        // beacons themselves. This lets us use a much more compact (particularly
        // in terms of number of lines) format, which makes the pretty-printed
        // save state easier to read.
        val indexes = HashMap<Beacon, Int>()
        for ((i, beacon) in beacons.withIndex()) {
            indexes[beacon] = i
        }

        val serialisedNeighbours = StringBuilder()
        for ((i, beacon) in beacons.withIndex()) {
            // Only include neighbours that come later in the beacons
            // list - if they come earlier, we'll already have specified
            // this pair of neighbours when serialising the other one.
            val neighbours = beacon.neighbours.filter { i < indexes.getValue(it) }

            if (neighbours.isEmpty())
                continue

            serialisedNeighbours.append(i)
            for (neighbour in neighbours) {
                serialisedNeighbours.append(',')
                serialisedNeighbours.append(indexes.getValue(neighbour))
            }

            serialisedNeighbours.append(' ')
        }

        val neighboursElem = Element("neighbours")
        serialisedNeighbours.trim()
        neighboursElem.addContent(serialisedNeighbours.toString())
        elem.addContent(neighboursElem)

        // The game needs to make a reference to the player's current beacon.
        return refs
    }

    /**
     * Deserialise this sector from XML.
     */
    constructor(elem: Element, refs: RefLoader, game: InGameState, mapRefs: RefLoader) {
        browserDifficulty = game.difficulty
        info = SaveUtil.getAttrRefImmediate(elem, "sectorInfo", mapRefs, GameMap.SectorInfo::class.java)!!

        fleetAdvanceModifier = SaveUtil.getAttrInt(elem, "fleetAdvanceModifier")
        mapRevealed = SaveUtil.getAttrBool(elem, "mapRevealed")
        layoutSeed = elem.getAttributeValue("browserLayoutSeed")?.toIntOrNull() ?: 0
        waitSeed = elem.getAttributeValue("browserWaitSeed")?.toIntOrNull()
        dangerZoneCentre = Point(SaveUtil.getPoint(elem, "dangerZoneCentre"))

        // Use a separate RefLoader to find the start and finish beacons
        val beaconRefs = RefLoader()

        // Load all the beacons
        val neighboursFor = HashMap<Beacon, ArrayList<Beacon>>()
        for (beaconElem in elem.getChildren("beacon")) {
            val beacon = Beacon.loadFromXML(beaconElem, refs, game)
            SaveUtil.registerObjectId(beaconElem, beaconRefs, beacon)
            beacons += beacon
            neighboursFor[beacon] = ArrayList()
        }

        // Calculate all the neighbours for each beacon
        val neighbourTuples = elem.getChildTextTrim("neighbours").split(' ', '\t')
        for (part in neighbourTuples) {
            // Each entry in the neighbours list of a comma-separated list of beacon indexes.
            // The first beacon in each entry is then connected to all the subsequent beacons.
            val partBeacons = part.split(',').map { beacons[it.toInt()] }
            val mainBeacon = partBeacons[0]
            val neighbours = partBeacons.subList(1, partBeacons.size)

            val mainNeighbours = neighboursFor.getValue(mainBeacon)

            for (neighbour in neighbours) {
                val neighbourNeighbours = neighboursFor.getValue(neighbour)

                // Make sure this connection is unique
                require(!mainNeighbours.contains(neighbour))
                require(!neighbourNeighbours.contains(mainBeacon))

                mainNeighbours.add(neighbour)
                neighbourNeighbours.add(mainBeacon)
            }
        }

        for (beacon in beacons) {
            val neighbours = neighboursFor.getValue(beacon)
            beacon.bindSector(this, neighbours)
        }

        beaconRefs.switchToResolveMode()
        startBeacon = SaveUtil.getAttrRefImmediate(elem, "startBeacon", beaconRefs, Beacon::class.java)!!
        finishBeacon = SaveUtil.getAttrRefImmediate(elem, "finishBeacon", beaconRefs, Beacon::class.java)!!

        for (cloudElem in elem.getChildren("browserNebulaCloud")) {
            nebulaClouds += NebulaCloud(
                cloudElem.getAttributeValue("image"),
                SaveUtil.getAttrInt(cloudElem, "x"), SaveUtil.getAttrInt(cloudElem, "y"),
                SaveUtil.getAttrInt(cloudElem, "w"), SaveUtil.getAttrInt(cloudElem, "h")
            )
        }

        for (bossElem in elem.getChildren("boss")) {
            val type = SaveUtil.getAttr(bossElem, "type")
            val boss = BossManager.createBySerialisationType(type, this, game)
            SaveUtil.registerObjectId(bossElem, refs, boss)
            boss.loadFromXML(bossElem, refs)
            bosses.add(boss)
        }
    }

    companion object {
        /**
         * The order the fleet takes beacons while its centre (integer x, as the original truncates it) moves from
         * [start] to [end] along [centreY]. The original steps the centre with the jump animation and, every
         * frame, tests all beacons in list order (distance strictly under 767, float, not yet taken), so two
         * beacons that fall inside on the same step go in list order. Captured natively (events2-ov1, nff1): from
         * -710 the fleet took beacons (56,275), (50,51), (91,169) and then (77,386), though (77,386) is reached
         * 0.1 px earlier. Returns indices into the arrays.
         */
        fun overtakeOrder(xs: IntArray, ys: IntArray, taken: BooleanArray, centreY: Int, start: Int, end: Int): List<Int> {
            val order = ArrayList<Int>()
            val seen = taken.copyOf()
            val radius = DANGER_ZONE_RADIUS.toFloat()
            for (x in start..maxOf(start, end)) {
                for (i in xs.indices) {
                    if (seen[i])
                        continue
                    val dx = (xs[i] - x).toFloat()
                    val dy = (ys[i] - centreY).toFloat()
                    if (kotlin.math.sqrt(dx * dx + dy * dy) < radius) {
                        seen[i] = true
                        order.add(i)
                    }
                }
            }
            return order
        }

        val OFFSET = ConstPoint(45, 40)
        var GRID_SIZE = ConstPoint(6, 4)
        val CELL_SIZE = ConstPoint(110, 110)
        const val PADDING = 10
        const val CONNECTION_DISTANCE = 165

        const val DANGER_ZONE_RADIUS = 767
        const val DANGER_ZONE_RADIUS_SQUARED = DANGER_ZONE_RADIUS * DANGER_ZONE_RADIUS

        // The amount the x position of the danger zone increases by
        // per jump in a normal sector.
        const val DANGER_ZONE_ADVANCE = 64
    }
}
