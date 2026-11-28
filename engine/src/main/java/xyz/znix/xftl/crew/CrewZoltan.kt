package xyz.znix.xftl.crew

import xyz.znix.xftl.*
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.MainSystem

class CrewZoltan(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val baseMaxHealth: Float get() = 70f

    // EnergyAlien's animation: the death is explosion_energy_death, its own 64 px sheet (CustomDeath), which
    // AbstractCrew.draw centres on the crew member without the race's layers.
    override val customDeath: Boolean get() = true

    override fun onStartedDying() {
        super.onStartedDying()

        // EnergyAlien's death damage (vtable slot 54, 0x478c20) is one point
        // of personnel damage (15 HP) owned by the Zoltan's ship, without
        // friendly fire, applied through DamageArea at its position. So
        // DamageCrew (0x49cf80) hits every crewmember in the room that
        // belongs to the other ship - by ownership, whatever mind control
        // has done to either side.
        val zoltanSide = belongsToPlayer
        for (crew in room.crew.toList()) {
            val side = crew.belongsToPlayer
            if (side == null) {
                if (crew.mode == mode) continue
            } else if (side == zoltanSide) {
                continue
            }

            crew.dealDamage(ZoltanDeathDamage(15f, this))
        }
    }
}

object ZoltanEnergySource : EnergySource {
    override val serialisationId: String get() = "zoltan"
    override val isPerSystem: Boolean get() = true
    override val isIonProof: Boolean get() = true

    override fun adjustShipPower(ship: Ship, power: MutableMap<EnergySource, Int>) {
        // No effect on whole-ship power
    }

    override fun getSystemPower(system: MainSystem): Int {
        // Note: mind-controlled zoltan provide power to their owner ship,
        // not to whoever currently controls them. Hence check ownerShip
        // rather than the current mode.
        // Hyperspace's ShipManager::OnLoop (CustomCrew.cpp) counts every crew list member of the ship in the room, crew
        // drones too (the Multiverse Manager Drone's bonusPower 1 powers the room it stands in).
        val room = system.room!!
        return room.crew.sumOf {
            val owner = when (it) {
                is LivingCrew -> it.ownerShip
                is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn -> it.drone.ownerShipOrNull
                else -> null
            }
            if (owner == room.ship) it.bonusSystemPower else 0
        }
    }

    override fun drawSystemPowerBar(g: Graphics, system: AbstractSystem, x: Int, y: Int, width: Int, height: Int) {
        // Always show the zoltan bar, regardless of ion/hacking/etc
        drawReactorPowerBar(g, x, y, width, height)
    }

    override fun drawReactorPowerBar(g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        g.colour = Constants.SYS_ENERGY_ZOLTAN
        g.fillRect(x, y, width, height)
    }
}
