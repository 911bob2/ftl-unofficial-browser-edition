package xyz.znix.xftl.shipgen

import ftl.browser.NativeRandom
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInstallConfiguration
import xyz.znix.xftl.drones.CrewDroneBodies
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * The draws building an enemy ship's objects takes from the native stream, which CreateShip's generation
 * (0x620fd0) shares: the original makes them between the blueprint pick and the first system maximum, and again
 * whenever the generation adds a system, a weapon or a drone. In ShipManager::Restart (0x4b1760) order:
 *
 * - Ship::OnInit (0x496720): an animation start (Animation::RandomStart) per room tile, three draws per explosion
 *   gib (0x4927a0), then two more animation starts (0x4963b0);
 * - CreateSystems: each starting system in blueprint order, as [addSystem];
 * - the blueprint's weapons and drones (0x4b12c0), as [weapon] and [drone];
 * - the blueprint crew (0x4ae2f0, only a ship that skips CreateShip has any);
 * - Spreader<Fire>::OnInit: per room tile a fire, an animation start then Fire 0x4997d0, as [fires].
 *
 * Measured on 1017 native ship creations (round 1 samples and round 3 arrivals); see the gen fix report.
 */
internal object ShipConstructionDraws {
    /** Ship::OnInit's draws, before any system exists. */
    fun onInit(ship: Ship) {
        repeat(tiles(ship)) { NativeRandom.next31() }
        repeat(ship.type.gibs.size * 3) { NativeRandom.next31() }
        repeat(2) { NativeRandom.next31() }
    }

    /** The systems the blueprint starts with, in blueprint order (CreateSystems 0x4a96f0). */
    fun startingSystems(ship: Ship) {
        for (slot in ship.systemSlots.filter { it.isInstalled }.sortedBy { it.spec.systemIndex })
            addSystem(ship, slot)
    }

    /**
     * ShipManager::AddSystem (0x4a8370) after [slot] was installed. The system's constructor draws first: the
     * medbay's healing animation per tile (ShipSystem 0x642370), the hacking system's drone ([spaceDrone]),
     * the artillery's weapon ([weapon]). Then an enemy system that is manned at a console and has no blueprint
     * slot gets one: the pilot slot 0 facing up, the others a random side of a tile that is a wall without a door
     * (0x4923d0, one draw when there is any).
     */
    fun addSystem(ship: Ship, slot: SystemInstallConfiguration) {
        when (slot.spec.systemName) {
            "medbay" -> repeat(slot.room.cellCount) { NativeRandom.next31() }
            "hacking" -> spaceDrone()
            "artillery" -> weapon()
        }

        if (ship.isPlayerShip || slot.spec.slotNumber != null)
            return
        when (slot.spec.systemName) {
            "pilot" -> slot.overrideComputer(ConstPoint(0, 0), Direction.UP)
            "shields", "engines", "weapons", "sensors", "doors" -> {
                val candidates = consoleCandidates(ship, slot.room)
                if (candidates.isNotEmpty()) {
                    val (point, dir) = candidates[NativeRandom.nextInt(candidates.size)]
                    slot.overrideComputer(point, dir)
                }
            }
        }
    }

    /** A weapon's ProjectileFactory (0x40bff0) draws once when it is created. */
    fun weapon() {
        NativeRandom.next31()
    }

    /** A space drone's constructor (0x426930) draws twice. */
    private fun spaceDrone() {
        NativeRandom.next31()
        NativeRandom.next31()
    }

    /**
     * A drone added to the ship (Equipment::AddDrone 0x53d500): a crew drone makes its body now
     * ([CrewDroneBodies]); a space drone draws twice, and a boarding pod also makes its intruder: an
     * anti-personnel body (name, then timer), or for BOARDER_ION an ion intruder (timer, then name).
     */
    fun drone(game: InGameState, blueprint: DroneBlueprint) {
        when (blueprint.type) {
            DroneBlueprint.DroneType.REPAIR, DroneBlueprint.DroneType.BATTLE -> CrewDroneBodies.drawFor(game, blueprint)
            DroneBlueprint.DroneType.BOARDER -> {
                spaceDrone()
                if (blueprint.name == "BOARDER_ION") {
                    NativeRandom.next31()
                    game.crewFactory.newName()
                } else {
                    game.crewFactory.newName()
                    NativeRandom.next31()
                }
            }

            else -> spaceDrone()
        }
    }

    /** Spreader<Fire>::OnInit: an animation start and a Fire draw per room tile. */
    fun fires(ship: Ship) {
        repeat(tiles(ship)) {
            NativeRandom.next31()
            NativeRandom.next31()
        }
    }

    private fun tiles(ship: Ship): Int = ship.rooms.sumOf { it.cellCount }

    /**
     * 0x4923d0: every (slot, side) of the room, columns outer and rows inner, sides down, up, right, left, whose
     * neighbour tile is outside the ship or in another room with no door between the two tiles.
     */
    private fun consoleCandidates(ship: Ship, room: Room): List<Pair<ConstPoint, Direction>> {
        val out = ArrayList<Pair<ConstPoint, Direction>>()
        for (x in 0 until room.width) {
            for (y in 0 until room.height) {
                for (dir in SIDES) {
                    val nx = room.x + x + dir.x
                    val ny = room.y + y + dir.y
                    val other = ship.rooms.firstOrNull { nx >= it.x && nx < it.x + it.width && ny >= it.y && ny < it.y + it.height }
                    if (other == room)
                        continue
                    if (other != null && room.doors.any { door ->
                            door.other(room) == other && door.roomPos(room).posEq(ConstPoint(x, y)) && door.dirFor(room) == dir
                        })
                        continue
                    out += Pair(ConstPoint(x, y), dir)
                }
            }
        }
        return out
    }

    private val SIDES = arrayOf(Direction.DOWN, Direction.UP, Direction.RIGHT, Direction.LEFT)
}
