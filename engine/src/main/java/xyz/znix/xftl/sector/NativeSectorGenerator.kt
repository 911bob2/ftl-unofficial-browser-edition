package xyz.znix.xftl.sector

import ftl.browser.NativeRandom
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import kotlin.math.sqrt

/**
 * The original's beacon map generation: StarMap::GenerateMap (0x5f8ca0),
 * the grid fill (0x5f7ad0), StarMap::ConnectLocations (0x5f7880),
 * StarMap::GenerateEvents (0x5f6540) and the nebula clouds (0x5f5b40).
 *
 * Every draw comes from [NativeRandom], reseeded with the layout seed (a
 * fresh draw from the global stream, or the one stored in a save), so the
 * same seed regenerates the same beacons, links, events and event seeds.
 */
class NativeSectorGenerator(
    private val info: GameMap.SectorInfo,
    private val creator: EventCreator,
    private val specialEvents: GameMap.SpecialEvents,
    private val difficulty: Difficulty,
    private val tutorial: Boolean
) {
    private val rand = NativeRandom

    /** StarMap +0x490: the events are created with the world level (the sector's column). */
    private val level: Int = info.columnNumber

    private class Location(val pos: ConstPoint, val cell: ConstPoint) {
        val neighbours = ArrayList<Location>()
        var event: CreatedEvent? = null
        var isExit = false
        var nebula = false

        /** An existing event moved into a nebula cloud keeps its event but becomes a nebula. */
        var forcedNebula = false
        var flagship = false

        /** Location +0x1c: the fleet has taken the beacon over (0x5f4ca0). */
        var overtaken = false

        /** Location +0x9c: the fleet takes the beacon at its next last-stand step (0x5f4ed0). */
        var marked = false
    }

    class Layout(
        val beacons: List<Beacon>,
        val neighbours: Map<Beacon, List<Beacon>>,
        val start: Beacon,
        val finish: Beacon,
        val flagshipStart: Beacon?,
        val dangerZoneCentre: Point,
        val nebulaClouds: List<Sector.NebulaCloud>,

        /** The seed the map was generated from (StarMap +0x9a8). */
        val layoutSeed: Int
    )

    private val isLastStand: Boolean get() = info.type.name == "FINAL"

    private lateinit var locations: ArrayList<Location>
    private lateinit var cells: HashMap<ConstPoint, ArrayList<Location>>
    private var cellAttempts = 0
    private var cellsSkipped = 0
    private val clouds = ArrayList<Sector.NebulaCloud>()

    /**
     * Generate the map. [storedLayoutSeed] is the seed saved with a loaded
     * game; null draws a new one from the global stream like GenerateMap does.
     */
    fun generate(storedLayoutSeed: Int?): Layout {
        val layoutSeed = storedLayoutSeed ?: rand.next31()
        rand.seed(layoutSeed)

        // The fleet starts off the left of the map, at a random height.
        val dangerZone = Point(-959, rand.nextInt(0xfa) + 0x32)

        // The sector's rarity list applies from here on (BlueprintManager
        // 0x60ba60 resets every rarity, then SetRarity for each entry).
        creator.setSectorRarity(info.type)

        while (true) {
            // Fill the 6x4 grid, regenerating until every beacon is connected.
            do {
                // StarMap::ClearLocations and EventGenerator 0x5db020: the
                // unique events and the used texts are returned.
                creator.clearEvents()
                populateGrid()
            } while (countReachable(locations[0]) != locations.size)

            val startRow = rand.nextInt(4)
            val startCell = cells[ConstPoint(0, startRow)]
            val start = if (startCell.isNullOrEmpty()) locations[0] else startCell[0]

            // The start beacon gets the sector's start event. NewGame
            // (0x5f9e00) then replaces the text of a run's first beacon with
            // START_GAME's (TUTORIAL_START in the tutorial).
            start.event = creator.create(info.type.startEvent, level)

            // Up to 16 attempts at an exit with the right path length, then
            // the whole map is generated again.
            var attempts = 0
            var exit: Location? = null
            while (true) {
                var maxPath: Int
                var minPath: Int
                var candidate: Location?
                do {
                    val row = rand.nextInt(4)
                    var column = rand.nextInt(2) + 4
                    maxPath = 100
                    minPath = 4
                    if (isLastStand) {
                        if (difficulty == Difficulty.HARD) {
                            column = rand.nextInt(2) + 3
                            maxPath = 7
                            minPath = 4
                        } else {
                            column = rand.nextInt(2) + 2
                            maxPath = 5
                            minPath = 3
                        }
                    }
                    candidate = cells[ConstPoint(column, row)]?.firstOrNull()
                } while (candidate == null)

                // The path includes both ends.
                val pathLength = pathLength(start, candidate)
                attempts++
                if (pathLength in (minPath + 1) until maxPath) {
                    exit = candidate
                    break
                }
                if (attempts == 16)
                    break
            }
            if (exit == null)
                continue

            exit.isExit = true
            exit.event = creator.create(specialEvents.exit, level)
            generateEvents()

            var flagship: Location? = null
            if (isLastStand) {
                dangerZone.x = 60
                dangerZone.y = 200

                // A random beacon is marked (Location +0x9c) unless it's the exit:
                // the first last-stand step overtakes it.
                val marked = locations[rand.nextInt(locations.size)]
                if (!marked.isExit)
                    marked.marked = true

                // The flagship's starting beacon: the attempts continue
                // from the exit's count.
                var found = false
                do {
                    var candidate: Location?
                    do {
                        val row = rand.nextInt(4)
                        val column = rand.nextInt(2) + 4
                        candidate = cells[ConstPoint(column, row)]?.firstOrNull()
                    } while (candidate == null)

                    // A flagship path of 4 to 6 beacons, both ends included.
                    val pathLength = pathLength(candidate, exit)
                    attempts++
                    if (pathLength in 4..6) {
                        flagship = candidate
                        found = true
                        break
                    }
                } while (attempts < 16)
                if (!found)
                    continue

                flagship!!.flagship = true
                fleetOvertake(flagship)
                exit.event = creator.create(specialEvents.fedBase, level)
                start.event = creator.create(creator.eventFor("LAST_STAND_START"), level)
            }

            return buildLayout(start, exit, flagship, dangerZone, layoutSeed)
        }
    }

    private fun populateGrid() {
        locations = ArrayList()
        cells = HashMap()
        cellAttempts = 0
        cellsSkipped = 0
        clouds.clear()

        for (x in 0 until 6) {
            for (y in 0 until 4) {
                populateCell(ConstPoint(x, y))
            }
        }
    }

    private fun populateCell(cell: ConstPoint) {
        if (cells.containsKey(cell))
            return

        // A one-in-five chance of leaving the cell empty, as long as no more
        // than a fifth of the cells so far have been skipped.
        var place = true
        if (rand.next31() % 5 == 0) {
            cellsSkipped++
            if (cellsSkipped < 1 || cellAttempts / cellsSkipped > 4) {
                cells[cell] = ArrayList()
                place = false
            }
        }

        if (place) {
            val list = ArrayList<Location>()
            cells[cell] = list

            // The Location constructor (0x5eabf0) draws its animation phase.
            rand.next31()
            val x = cell.x * 110 + 10 + rand.next31() % 90
            var y = (cell.y * 110 + 10 + rand.next31() % 90).coerceAtMost(415)
            if (cell.x > 3 && cell.y == 0 && y < 30)
                y = 30

            val location = Location(ConstPoint(x, y), cell)
            locations.add(location)
            list.add(location)

            for (dx in -1..1) {
                for (dy in -1..1) {
                    connect(location, ConstPoint(cell.x + dx, cell.y + dy))
                }
            }
        }

        cellAttempts++
    }

    // ConnectLocations links beacons in neighbouring cells within 165px.
    private fun connect(location: Location, otherCell: ConstPoint) {
        val others = cells[otherCell] ?: return
        for (other in others) {
            if (other === location)
                break
            if (distance(location.pos, other.pos) > 165f)
                continue
            location.neighbours.add(other)
            other.neighbours.add(location)
        }
    }

    private fun countReachable(from: Location): Int {
        val seen = HashSet<Location>()
        val stack = ArrayList<Location>()
        stack.add(from)
        while (stack.isNotEmpty()) {
            val loc = stack.removeAt(stack.size - 1)
            if (!seen.add(loc))
                continue
            stack.addAll(loc.neighbours)
        }
        return seen.size
    }

    /** The unit-weight shortest path (0x5f81d0), counting both ends; zero if there isn't one. */
    private fun pathLength(from: Location, to: Location): Int {
        val previous = HashMap<Location, Location?>()
        val queue = ArrayDeque<Location>()
        previous[from] = null
        queue.add(from)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == to) {
                var length = 0
                var step: Location? = to
                while (step != null) {
                    length++
                    step = previous[step]
                }
                return length
            }
            for (next in current.neighbours) {
                if (previous.containsKey(next))
                    continue
                previous[next] = current
                queue.add(next)
            }
        }
        return 0
    }

    /**
     * FUN_005f4ca0: the fleet takes a beacon over (the flagship's start
     * beacon in the last stand) and its event becomes a fleet encounter.
     */
    private fun fleetOvertake(loc: Location) {
        loc.overtaken = true
        if (loc.event == null)
            return

        val name = when {
            !loc.isExit || isLastStand -> when {
                loc.nebula -> "FLEET_EASY_NEBULA"
                else -> "FLEET_EASY" + (if (creator.dlcEnabled) "_DLC" else "")
            }

            else -> "FLEET_EASY_BEACON" + (if (difficulty != Difficulty.EASY) "_DLC" else "")
        }
        loc.event = creator.create(creator.eventFor(name), level)
    }

    // StarMap::GenerateEvents
    private fun generateEvents() {
        if (tutorial) {
            val enemy = creator.eventFor("TUTORIAL_ENEMY")
            for (loc in locations) {
                if (loc.event == null)
                    loc.event = creator.create(enemy, level)
            }
            return
        }

        // Entries naming a nebula event become nebula clouds first.
        val entries = ArrayList(info.type.events)
        val nebulaNames = ArrayList<SectorType.EventInfo>()
        var index = 0
        while (index < entries.size) {
            val entry = entries[index]
            if (!entry.name.contains("NEBULA")) {
                index++
                continue
            }
            val count = rollCount(entry)
            for (i in 0 until count)
                nebulaNames.add(entry)
            entries.removeAt(index)
        }
        if (nebulaNames.isNotEmpty())
            placeNebulas(nebulaNames)

        // Each remaining entry claims random beacons in turn; beacons that
        // already have an event are used up without counting.
        val pool = ArrayList(locations)
        entries@ for (entry in entries) {
            val count = rollCount(entry)
            if (count > 0) {
                if (pool.isEmpty())
                    break
                var placed = 0
                while (true) {
                    val loc = pool.removeAt(rand.nextInt(pool.size))
                    if (loc.event == null) {
                        placed++
                        loc.event = creator.create(entry.event, level)
                    }
                    if (placed >= count)
                        break
                    if (pool.isEmpty())
                        break@entries
                }
            }
            if (pool.isEmpty())
                break
        }

        val filler = creator.eventFor("NEUTRAL")
        for (loc in pool) {
            if (loc.event == null)
                loc.event = creator.create(filler, level)
        }
    }

    /**
     * The count roll of an entry (0x5caf40): a chance above zero is an
     * outright miss on `rand % 100 < 100 * chance`; otherwise the minimum
     * plus `rand % (max + 1 - min)`, or just the minimum when the maximum
     * is zero.
     */
    private fun rollCount(entry: SectorType.EventInfo): Int = EventCreator.rangeRoll(rand, entry.count.first, entry.count.last, entry.chance)

    private fun placeNebulas(names: ArrayList<SectorType.EventInfo>) {
        val images = if (names.size < 6) SMALL_CLOUDS else LARGE_CLOUDS
        var image = images[rand.nextInt(images.size)]

        val open = ArrayList(locations.filter { !it.nebula })
        val requested = names.size
        val free = open.size

        // Leave at least four beacons outside the nebulas.
        while (open.size - names.size < 4 && names.isNotEmpty())
            names.removeAt(rand.nextInt(names.size))
        var placedNames = 0
        val kept = names.size

        var anchor = open[rand.nextInt(open.size)]
        var rectX = (anchor.pos.x - (image.width / 2).toFloat()).toInt()
        var rectY = (anchor.pos.y - (image.height / 2).toFloat()).toInt()
        var failures = 0

        do {
            var placedAny = false
            var i = 0
            while (i < open.size) {
                val loc = open[i]
                val x = loc.pos.x
                val y = loc.pos.y
                if (rectX + 5 < x && x < image.width - 10 + rectX + 5 && rectY + 5 < y && y < image.height - 10 + rectY + 5) {
                    loc.nebula = true
                    if (loc.event == null) {
                        var event: IEvent = creator.eventFor("NEBULA")
                        if (names.isNotEmpty()) {
                            event = names.removeAt(rand.nextInt(names.size)).event
                            placedNames++
                        }
                        loc.event = creator.create(event, level)
                    } else {
                        if (loc.isExit)
                            loc.event = creator.create(specialEvents.exitNebula, level)
                        loc.forcedNebula = true
                    }
                    open.removeAt(i)
                    placedAny = true
                    continue
                }
                i++
            }

            if (!placedAny) {
                failures++
            } else {
                clouds.add(Sector.NebulaCloud(image.path, rectX, rectY, image.width, image.height))
            }

            if (failures < 21) {
                // Grow the nebula next to one of its clouds.
                val existing = clouds[rand.nextInt(clouds.size)]
                image = images[rand.nextInt(images.size)]
                rectX = existing.x + rand.nextInt(existing.width + image.width) - image.width
                rectY = rand.nextInt(existing.height + image.height) + existing.y - image.height
            } else {
                failures = 0
                if (open.isEmpty())
                    break
                anchor = open[rand.nextInt(open.size)]
                rectX = (anchor.pos.x - (image.width / 2).toFloat()).toInt()
                rectY = (anchor.pos.y - (image.height / 2).toFloat()).toInt()
            }
        } while (names.isNotEmpty())
        lastNebulaCap = NebulaCap(requested, free, kept, placedNames)
    }

    private fun buildLayout(start: Location, exit: Location, flagship: Location?, dangerZone: Point, layoutSeed: Int): Layout {
        val beacons = HashMap<Location, Beacon>()
        for ((index, loc) in locations.withIndex()) {
            val beacon = Beacon(
                loc.pos, loc.event!!,
                loc.isExit && !isLastStand,
                loc.isExit && isLastStand,
                deriveBeaconSeed(layoutSeed, index)
            )
            beacon.inNebula = loc.nebula
            beacon.forcedNebula = loc.forcedNebula
            beacon.browserFleetThreatened = loc.marked
            beacons[loc] = beacon
        }
        val neighbours = locations.associate { loc -> beacons.getValue(loc) to loc.neighbours.map { beacons.getValue(it) } }
        return Layout(
            locations.map { beacons.getValue(it) },
            neighbours,
            beacons.getValue(start),
            beacons.getValue(exit),
            flagship?.let { beacons.getValue(it) },
            dangerZone,
            ArrayList(clouds),
            layoutSeed
        )
    }

    private class CloudImage(val path: String, val width: Int, val height: Int)

    companion object {
        /**
         * Diagnostics hook: what the last placeNebulas call did with the named nebula events. The cap keeps
         * [free] - [kept] at four or more (unnamed filler events are not capped).
         */
        class NebulaCap(val requested: Int, val free: Int, val kept: Int, val placed: Int)

        @JvmStatic var lastNebulaCap: NebulaCap? = null

        // The StarMap constructor's cloud lists: the small set is used for
        // fewer than six nebula events.
        private val SMALL_CLOUDS = listOf(
            CloudImage("img/map/nebula_0.png", 119, 63),
            CloudImage("img/map/nebula_1.png", 67, 110),
            CloudImage("img/map/nebula_2.png", 89, 67),
            CloudImage("img/map/nebula_3.png", 117, 108),
        )
        private val LARGE_CLOUDS = listOf(
            CloudImage("img/map/nebula_large_0.png", 250, 234),
            CloudImage("img/map/nebula_large_1.png", 200, 250),
            CloudImage("img/map/nebula_large_2.png", 250, 200),
        )

        private fun distance(a: ConstPoint, b: ConstPoint): Float {
            val dx = (a.x - b.x).toFloat()
            val dy = (a.y - b.y).toFloat()
            return sqrt(dx * dx + dy * dy)
        }

        /**
         * The per-beacon master seed (environment, store and encounter seeds).
         * The original has no such value - its stores and hazards draw from the
         * global stream when they are built - so it is derived from the layout
         * seed here instead of consuming draws the generator doesn't make.
         */
        fun deriveBeaconSeed(layoutSeed: Int, index: Int): Int {
            var z = (layoutSeed.toLong() shl 32) xor (index.toLong() * -7046029254386353131L + 1442695040888963407L)
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            z = z xor (z ushr 31)
            return (z ushr 16).toInt()
        }
    }
}
