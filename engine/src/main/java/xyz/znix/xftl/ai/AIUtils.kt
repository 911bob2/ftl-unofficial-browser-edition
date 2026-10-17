package xyz.znix.xftl.ai

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.*

object AIUtils {
    /**
     * CrewAI::PrioritizeSystem (0x627390): the lower the number the more urgent the system. The medbay is
     * more urgent while a hurt crewmember can't use it, and the teleporter while boarders are being sent.
     */
    fun systemPriority(system: AbstractSystem?, ai: FriendlyCrewAI): Int {
        return when (system) {
            is Shields -> 0
            is Weapons -> 1
            is Clonebay -> 1
            is Artillery -> 2
            is Oxygen -> 3
            is Engines, is Drones, is Piloting -> 4
            is Medbay -> if (ai.medbayBusy) 1 else 2
            is Teleporter -> if (ai.teleporterIdle) 3 else 1
            is Cloaking, is MindControl, is Hacking -> 5
            is Sensors, is BackupBattery -> 6
            is Doors -> 7
            else -> 8
        }
    }

    /**
     * Whether CrewAI::OnLoop (0x629d80) runs this update. ShipAI::OnLoop (0x625c50) only takes its hostile half for a
     * ship whose hostile flag (ShipManager +0x10, read by CompleteShip::OnLoop 0x409ee0) is set: Restart sets it for
     * the player's ship (id 0), and WorldManager::CreateShip (0x6149c0) copies it from the encounter for an enemy.
     * That flag is the engine's "enemy is hostile", so a ship that isn't fighting leaves its crew alone.
     */
    fun crewAiRuns(ship: Ship): Boolean = ship.isPlayerShip || ship.sys.getEnemyOf(ship) != null

    /**
     * ShipSystem +0x45: a crewmember is manning the system. ShipManager::OnLoop (0x4aa670) resets it every frame to
     * the automated flag (ShipManager +0x4d9) and CrewMember 0x47b190 sets it while manning.
     */
    fun isManned(system: AbstractSystem): Boolean {
        val ship = system.room?.ship ?: return system.manningCrew != null
        return ship.isAutoScout || ship.isAutomated || system.manningCrew != null
    }

    @Suppress("RedundantIf")
    fun isDangerous(crew: AbstractCrew, room: Room): Boolean {
        // CrewAI's room safety check (0x626e70): the room is dangerous:
        // - If a room is an airlock and an airlock door is open, it's dangerous
        // - If health<25% and oxygen<10% it's dangerous, if we can suffocate (ie, except lanius)
        // - If fireCount>0 and health<20%, it's dangerous unless we're immune to fire
        // - If fireCount>=4 then it's dangerous unless we're immune to fire
        // - This room is the medbay, and it's being hacked

        val healthFraction = crew.health / crew.maxHealth
        if (healthFraction < 0.25f && room.oxygen < 0.10f && crew.suffocationMultiplier > 0f) {
            return true
        }

        // Whether this is required is a bit dubious, as with the exception of
        // boarding onto a player ship with an airlock in a system, this won't
        // change anything - and even then, this behaviour is a bit odd.
        if (room.doors.any { it.open && it.isAirlock }) {
            return true
        }

        // Unless the crew is fireproof, fire is dangerous if we're <20%
        // health, and it's always dangerous if there's four fires in a room.
        val fireCount = room.fires.count { it != null }
        if (fireCount > 0 && crew.fireDamageMult > 0f && (fireCount >= 4 || healthFraction < 0.20f)) {
            return true
        }

        // CrewAI's room safety check (0x626e70) also calls a room with a hull breach unsafe when
        // every cell of it is on fire.
        if (fireCount > 0 && fireCount == room.cellCount && room.breaches.any { it != null }) {
            return true
        }

        // A hacked medbay is, unsurprisingly, dangerous
        val medbay = room.system as? Medbay
        if (medbay?.isHackActive == true) {
            return true
        }

        return false
    }
}
