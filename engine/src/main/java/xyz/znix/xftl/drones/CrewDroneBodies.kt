package xyz.znix.xftl.drones

import ftl.browser.NativeRandom
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * The draws from the native stream when a crew drone's body is created.
 *
 * ShipManager::CreateDrone (0x4b0fc0) runs from Equipment::AddDrone
 * (0x53d500), so a repair or anti-personnel drone gets its body (and uses up
 * the stream) when it is added to a ship, not when it is deployed. Boarder
 * pods are space drones and create their crew when they land.
 * Neither body has colour layers. A repair drone (0x467450) starts its
 * animation timer first (TimerHelper::Start, one draw) and then takes a
 * name (0x605750, the crew name table); an anti-personnel drone (0x466d60)
 * takes its name first and starts the timer after.
 */
internal object CrewDroneBodies {
    fun drawFor(game: InGameState, blueprint: DroneBlueprint) {
        when (blueprint.type) {
            DroneBlueprint.DroneType.REPAIR -> {
                NativeRandom.next31()
                game.crewFactory.newName()
            }

            DroneBlueprint.DroneType.BATTLE -> {
                game.crewFactory.newName()
                NativeRandom.next31()
            }

            else -> {}
        }
    }
}
