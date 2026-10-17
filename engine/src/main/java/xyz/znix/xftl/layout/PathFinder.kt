package xyz.znix.xftl.layout

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint
import kotlin.math.sqrt

class PathFinder(val ship: Ship) {
    private val mutableNodes: MutableMap<RoomPoint, Node> = HashMap()
    val nodes: Map<RoomPoint, Node> = mutableNodes

    init {
        recalculateWeights()
    }

    fun recalculateWeights() {
        val tmpRooms = ArrayList<Node>(4)

        // TODO if perf becomes an issue, clear all the neighbours for each node and only redo those
        mutableNodes.clear()

        for (room in ship.rooms) {
            tmpRooms.clear()
            for (x in 0 until room.width) {
                for (y in 0 until room.height) {
                    val point = RoomPoint(room, x, y)
                    val node = Node(point)
                    check(room.containsRelative(point))
                    mutableNodes[point] = node
                    tmpRooms += node
                }
            }

            for (n1 in tmpRooms) {
                for (n2 in tmpRooms) {
                    if (n1 == n2)
                        continue

                    val diagonal = n1.pos.x != n2.pos.x && n1.pos.y != n2.pos.y

                    // Slightly prefer going straight, so we don't needlessly go diagonally
                    n1.neighbours[n2] = if (diagonal) 1.01f else 1f
                }
            }
        }

        // Add in the doors
        for (door in ship.doors) {
            val n1 = nodes[door.leftPos]
            val n2 = nodes[door.rightPos]

            if (n1 == null || n2 == null)
                continue

            // Prefer to avoid doors
            val weight = 1.2f
            n1.neighbours[n2] = weight
            n2.neighbours[n1] = weight
        }
    }

    fun findShipPos(point: IPoint): Node? {
        for (pair in nodes) {
            if (pair.key.shipPoint posEq point)
                return pair.value
        }

        return null
    }

    /**
     * ShipGraph::FindPath (0x4643c0) / Dijkstra (0x463880): the doors [crew] walks through from [from] in [start] to
     * reach [dest], or null when it can't. The nodes are the doors. A door of the start room costs the integer pixel
     * distance from the walker to it; any other door the distance from the door before it, plus 1000 when it is
     * locked for the walker (Door 0x4705c0). The search ends at the first door taken off the queue (cheapest, the
     * lowest door index of equals) that leads into [dest]. A door's position is Door +0x240/+0x244, the middle of
     * the doorway on the wall line (Door::GetCornerPoint 0x470d40 steps 17 px back along the door), which is
     * [Door.pixelCentre].
     */
    fun doorRoute(crew: AbstractCrew, from: IPoint, start: Room, dest: Room): List<Door>? {
        if (start == dest)
            return emptyList()

        val doors = ship.doors
        val index = HashMap<Door, Int>(doors.size)
        doors.forEachIndexed { i, door -> index[door] = i }

        val cost = FloatArray(doors.size) { Float.MAX_VALUE }
        val previous = IntArray(doors.size) { -1 }
        val beyond = arrayOfNulls<Room>(doors.size)

        for (door in start.doors) {
            if (door.isAirlock)
                continue
            val i = index[door] ?: continue
            cost[i] = pixelDistance(from, door.pixelCentre).toFloat()
            beyond[i] = otherSide(door, start)
        }

        val queue = ArrayList<Int>(doors.indices.toList())
        while (queue.isNotEmpty()) {
            var bestAt = 0
            for (k in 1 until queue.size) {
                if (cost[queue[k]] < cost[queue[bestAt]])
                    bestAt = k
            }
            val current = queue.removeAt(bestAt)
            if (cost[current] == Float.MAX_VALUE)
                return null
            val room = beyond[current] ?: return null

            if (room == dest) {
                val route = ArrayList<Door>()
                var i = current
                while (i != -1) {
                    route += doors[i]
                    i = previous[i]
                }
                route.reverse()
                return route
            }

            for (door in room.doors) {
                if (door.isAirlock)
                    continue
                val i = index[door] ?: continue
                val penalty = if (door.isLockedFor(crew)) 1000 else 0
                val newCost = cost[current] + pixelDistance(doors[current].pixelCentre, door.pixelCentre) + penalty
                if (newCost < cost[i]) {
                    cost[i] = newCost
                    previous[i] = current
                    beyond[i] = otherSide(door, room)
                }
            }
        }
        return null
    }

    private fun otherSide(door: Door, room: Room): Room? = if (door.left == room) door.right else door.left

    /** Point distance (0x5a47a0): the square root of the integer squared distance, truncated. */
    private fun pixelDistance(a: IPoint, b: IPoint): Int {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt((dx * dx + dy * dy).toFloat()).toInt()
    }

    fun path(start: RoomPoint) {
        check(ship == start.room.ship)

        for (room in nodes)
            room.value.reset()

        // If the start node is not blocked, seed it
        nodes[start]?.weight = 0f

        val dirty = ArrayList<Node>(nodes.size)
        val dirtyTmp = ArrayList<Node>(dirty.size)
        dirty += nodes.values
        while (dirty.isNotEmpty()) {
            dirtyTmp.clear()
            dirtyTmp += dirty
            dirty.clear()
            for (room in dirtyTmp) {
                val changed = room.recalculate()
                if (changed)
                    dirty += room.neighbours.keys
            }
        }

        val shipPoints = HashMap<ConstPoint, Node>()
        for (room in nodes)
            shipPoints[room.key.shipPoint] = room.value
    }

    class Node(val pos: RoomPoint) {
        companion object {
            // High value we will never reach
            // Don't use MAX_VALUE as it goes weird if we do something with it
            const val MAX_WEIGHT = 10_000f
        }

        var weight: Float = MAX_WEIGHT
        var next: Node? = null

        val neighbours: MutableMap<Node, Float> = HashMap(pos.room.width * pos.room.height - 1)

        fun recalculate(): Boolean {
            var changed = false

            assert(!neighbours.containsKey(this))

            for (pair in neighbours) {
                val nWeight = pair.key.weight + pair.value
                if (nWeight < weight) {
                    weight = nWeight
                    next = pair.key
                    changed = true
                }
            }

            return changed
        }

        fun reset() {
            weight = MAX_WEIGHT
            next = null
        }
    }
}