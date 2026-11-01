package xyz.znix.xftl.shipgen

import ftl.browser.NativeRandom
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInstallConfiguration
import xyz.znix.xftl.drones.CrewDroneBodies
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.abs

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
        // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomShips.cpp (Ship::OnInit hook):
        // the layout's thrusters start at random points after the original's two engine animations.
        if (xyz.znix.xftl.hs.HS.active) repeat(ship.type.hsThrusterDraws) { NativeRandom.next31() }
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
            "hacking" -> spaceDrone(null)
            "artillery" -> weapon()
        }

        if (ship.isPlayerShip || slot.spec.slotNumber != null)
            return
        when (slot.spec.systemName) {
            "pilot" -> slot.overrideComputer(ConstPoint(0, 0), Direction.UP)
            "shields", "engines", "weapons", "sensors", "doors" -> drawConsole(ship, slot)
            // Hyperspace custom systems: mannable (bBoostable, which a CONSTRUCT_SHIP_SYSTEM script may clear) like these
            else -> {
                val system = slot.room.system
                if (system != null && system.info is xyz.znix.xftl.hs.systems.HsCustomSystemInfo &&
                    xyz.znix.xftl.hs.systems.HsSystemState.of(system).bBoostable) drawConsole(ship, slot)
            }
        }
    }

    private fun drawConsole(ship: Ship, slot: SystemInstallConfiguration) {
        val candidates = consoleCandidates(ship, slot.room)
        if (candidates.isNotEmpty()) {
            val (point, dir) = candidates[NativeRandom.nextInt(candidates.size)]
            slot.overrideComputer(point, dir)
        }
    }

    /** A weapon's ProjectileFactory (0x40bff0) draws once when it is created. */
    fun weapon() {
        NativeRandom.next31()
    }

    /**
     * A space drone's constructor (0x426930; 1.6.9 0x422d00) draws twice: first its current angle (SpaceDrone+0x16c),
     * `random32() % 360` degrees, which the drone targeting below starts from (ret 0x426e25 in the native trace), then
     * AnimationControl::GetAnimation for "explosion_random".
     */
    private fun spaceDrone(info: Drones.DroneInfo?) {
        val angle = NativeRandom.next31() % 360
        if (info != null) info.nativeAngle = angle
        NativeRandom.next31()
    }

    /**
     * CombatDrone::PickDestination (0x4270e0; 1.6.9 0x421260), the native stream's version of the combat drone's hop:
     * `random32() % 360`, drawn again while less than 90 degrees from the current angle (a plain difference, no
     * wrap-around). Hyperspace with combatDroneRapidFireFix (MV leaves it off) makes one draw that turns 90..270
     * degrees on (CustomDrones.cpp).
     */
    private fun pickDestination(info: Drones.DroneInfo) {
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().combatDroneRapidFireFix) {
            info.nativeAngle = (info.nativeAngle + NativeRandom.next31() % 181 + 90) % 360
            return
        }
        var angle: Int
        do {
            angle = NativeRandom.next31() % 360
        } while (abs(angle - info.nativeAngle) < 90)
        info.nativeAngle = angle
    }

    /**
     * SpaceDrone::SetMovementTarget (0x4236f0, CombatDrone vtable +0x98; 1.6.9 0x41d940), and the hull-repair drone's
     * 0x420f60 (1.6.9 0x4217f0) while it has no movement target yet: two hops.
     */
    private fun setMovementTarget(info: Drones.DroneInfo) {
        pickDestination(info)
        pickDestination(info)
    }

    /**
     * ShipManager::SetTarget (0x4a2b30; 1.6.9 0x49c230) with a ship calls SetWeaponTarget (+0x9c) and
     * SetMovementTarget (+0x98) on every space drone the ship owns (+0x838, deployed or not): only a combat drone
     * draws (two CombatDrone::PickDestination hops). A hull-repair drone already aims at its own ship, a defence or
     * anti-drone drone's PickDestination (0x426f00) does not draw, and the hacking and boarding drones' do not pick
     * one. WorldManager::AddShip (0x613d00), at the end of WorldManager::CreateShip, runs it for the new ship
     * (CompleteShip::SetEnemyShip 0x40a290, ShipAI::SetEnemyShip 0x6257a0, CombatAI::SetTarget 0x62ad10: enemy ships
     * only) and then for the player (CommandGui::AddEnemyShip 0x5326b0, CombatControl::AddEnemyShip 0x57ce70);
     * [xyz.znix.xftl.shipgen.ShipGenerator] makes the calls in that order.
     */
    fun targetShip(ship: Ship) {
        for (info in ship.drones?.drones ?: return) {
            if (info != null && info.type.type == DroneBlueprint.DroneType.COMBAT)
                setMovementTarget(info)
        }
    }

    /**
     * A drone added to the ship (Equipment::AddDrone 0x53d500): a crew drone makes its body now
     * ([CrewDroneBodies]); a space drone draws twice, and a boarding pod also makes its intruder: an
     * anti-personnel body (name, then timer), or for BOARDER_ION an ion intruder (timer, then name).
     */
    fun drone(game: InGameState, blueprint: DroneBlueprint, info: Drones.DroneInfo? = null) {
        ShipGenerator.diagnosticMark?.invoke("drone:" + blueprint.name)
        when (blueprint.type) {
            DroneBlueprint.DroneType.REPAIR, DroneBlueprint.DroneType.BATTLE -> CrewDroneBodies.drawFor(game, blueprint)
            DroneBlueprint.DroneType.BOARDER -> {
                spaceDrone(info)
                if (blueprint.name == "BOARDER_ION") {
                    NativeRandom.next31()
                    game.crewFactory.newName()
                } else {
                    game.crewFactory.newName()
                    NativeRandom.next31()
                    xyz.znix.xftl.crew.NativeCrewFactory.hsInitializeDraw(xyz.znix.xftl.hs.weapons.HsDrones.customRace(blueprint.name) ?: "battle")
                }
            }

            DroneBlueprint.DroneType.SHIP_REPAIR -> {
                spaceDrone(info)
                // ShipManager::CreateSpaceDrone (0x4b0b60, type 5; 1.6.9 0x4a5db0) aims a hull-repair drone at its own
                // ship straight away (0x420f60), two CombatDrone::PickDestination hops.
                if (info != null) setMovementTarget(info)
            }

            else -> spaceDrone(info)
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
