package xyz.znix.xftl.vanillasave

/**
 * The battle part of an original run's tail, read the way WorldManager::LoadGame (1.6.9 0x5f8180) reads what
 * WorldManager::SaveGame (0x5f5410) wrote after StarMap::SaveGame:
 * - ShipEvent::Save (0x5ebb60): the encounter (present, seed, the four outcome events and one more name);
 * - the location's environment name, the event box (a flag and its text), an int, and the choice path (count, indices);
 * - a nearby-ship flag; with a ship: the BossShip flag, its ShipManager::ExportShip section, ShipAI::Save (0x600910:
 *   three flags, two ints, a time, a flag, a time, two ints);
 * - SpaceManager::SaveSpace (0x4166a0): five ints (the fourth the environment), the asteroid field when there is one,
 *   two ints, a time, then each shot in flight (ProjectileFactory::SaveProjectile 0x40c8b0: its type and its own state);
 * - ShipManager::ExportBattleState (0x496a60) for the player and then the nearby ship: each drone of the drone system
 *   (deployed, powered, its state), the hacking system (target system, HackingSystem::SaveState 0x6087c0 with its
 *   drone), the mind control system (MindSystem::Save 0x609c40: two times), each weapon (count, then
 *   ProjectileFactory::SaveState 0x40c900), each artillery's weapon, the system-less drones (SaveSuperDrones 0x492750);
 * - CompleteShip::SaveState (0x408f60, one int) per ship, CombatControl::SaveState (0x55ee70, one int) and
 *   BossShip::SaveBoss (0x405e40: two ints, two times, a list of ints), which ends the file.
 *
 * Times are written as whole thousandths (FileHelper::writeFloat). The shots in flight and a drone in flight need every
 * projectile's own state layout, which this reader does not have: when SaveSpace holds shots or asteroids, the battle
 * states are found as the one place from which they, the ships' ints and SaveBoss end exactly at the end of the file.
 * Returns null when no such place exists or a structure is not understood (a weapon with a volley queued); the import
 * then starts the fight as before.
 */
object VanillaBattleTail {
    /** ProjectileFactory::SaveState of one weapon: cooldown.first (the charge, seconds) and cooldown.second. */
    class WeaponState(val charge: Float, val chargeGoal: Float)

    class Battle {
        var player: List<WeaponState> = emptyList()
        var nearby: List<WeaponState>? = null
        var shotsInFlight: Int = 0
        /** The battle states were found by the end-of-file search (shots or asteroids in SpaceManager::SaveSpace). */
        var searched = false
    }

    private class Bad(message: String) : Exception(message)

    fun read(data: ByteArray, start: Int, save: VanillaSaveData, content: NativeContent): Battle? {
        return try {
            readOrThrow(data, start, save, content)
        } catch (e: Exception) {
            null
        }
    }

    private fun readOrThrow(data: ByteArray, start: Int, save: VanillaSaveData, content: NativeContent): Battle? {
        val r = VanillaSaveReader(data)
        r.seek(start)
        // ShipEvent::Save
        r.int(); r.int()
        repeat(5) { r.string() }
        // The environment, the event box (format 11: a flag and the text), an int, the choice path.
        r.string()
        r.int(); r.string()
        r.int()
        val path = r.int()
        if (path !in 0..1000) throw Bad("path $path")
        repeat(path) { r.int() }

        val nearbyShip: VanillaSaveData.ShipData?
        if (r.bool()) {
            r.int() // BossShip flag
            val at = r.pos
            val blueprint = r.string()
            r.seek(at)
            val (tiles, doors) = content.layout(blueprint)
            nearbyShip = VanillaSaveData.ShipData().also { VanillaSaveParser.readShip(r, it, tiles, doors, content, null, false) }
            repeat(10) { r.int() } // ShipAI::Save
        } else {
            nearbyShip = null
        }

        val battle = Battle()
        // SpaceManager::SaveSpace
        repeat(3) { r.int() }
        r.int() // environment
        val asteroids = r.bool()
        var searchFrom = -1
        if (asteroids) {
            searchFrom = r.pos
        } else {
            r.int(); r.int(); r.int()
            battle.shotsInFlight = r.int()
            if (battle.shotsInFlight !in 0..1000) throw Bad("shots ${battle.shotsInFlight}")
            if (battle.shotsInFlight > 0) searchFrom = r.pos
        }

        val ships = listOfNotNull(save.ship, nearbyShip)
        fun rest(from: Int): List<List<WeaponState>>? {
            val q = VanillaSaveReader(data)
            q.seek(from)
            return try {
                val states = ships.map { battleState(q, it, content) }
                repeat(ships.size) { q.int() } // CompleteShip::SaveState
                q.int() // CombatControl::SaveState
                // BossShip::SaveBoss
                q.int(); q.int(); q.int(); q.int()
                val n = q.int()
                if (n !in 0..20) throw Bad("boss $n")
                repeat(n) { q.int() }
                if (q.remaining != 0) null else states
            } catch (e: Exception) {
                null
            }
        }

        val states: List<List<WeaponState>> = if (searchFrom < 0) {
            rest(r.pos) ?: return null
        } else {
            battle.searched = true
            (searchFrom until data.size).firstNotNullOfOrNull { rest(it) } ?: return null
        }
        battle.player = states[0]
        battle.nearby = states.getOrNull(1)
        return battle
    }

    /** ShipManager::ExportBattleState of [ship] (its systems and lists from the ship section). */
    private fun battleState(r: VanillaSaveReader, ship: VanillaSaveData.ShipData, content: NativeContent): List<WeaponState> {
        fun has(slot: String) = (ship.systems[slot]?.capacity ?: 0) > 0
        if (has("drones")) {
            for (drone in ship.drones) {
                bool(r); bool(r)
                droneState(r, content.droneType(drone.name) ?: throw Bad("drone ${drone.name}"))
            }
        }
        if (has("hacking")) {
            r.int() // target system
            bool(r); bool(r); bool(r)
            r.int(); r.int()
            bool(r)
            droneState(r, "HACKING")
        }
        if (has("mind")) {
            r.int(); r.int()
        }
        val weapons = ArrayList<WeaponState>()
        if (has("weapons")) {
            val n = r.int()
            if (n != ship.weapons.size) throw Bad("weapons $n of ${ship.weapons.size}")
            repeat(n) { weapons += factory(r) }
        }
        if (has("artillery")) {
            val n = content.artilleryCount(ship.blueprint)
            if (n < 0) throw Bad("artillery")
            repeat(n) { factory(r) }
        }
        // SaveSuperDrones: name, state, deployed, a flag, powered
        val supers = r.int()
        if (supers !in 0..20) throw Bad("super drones $supers")
        repeat(supers) {
            val name = r.string()
            droneState(r, content.droneType(name) ?: throw Bad("drone $name"))
            bool(r); bool(r); bool(r)
        }
        return weapons
    }

    /** ProjectileFactory::SaveState (0x40c900). */
    private fun factory(r: VanillaSaveReader): WeaponState {
        val charge = r.int() / 1000f
        val goal = r.int() / 1000f
        r.int(); r.int() // the sub-cooldown
        r.int(); r.int() // boost level, charge level
        repeat(2) {
            val n = r.int()
            if (n !in 0..50) throw Bad("targets $n")
            repeat(n) { r.int(); r.int() }
        }
        bool(r); bool(r) // autofire, fire when ready
        r.int() // target id
        // WeaponAnimation::SaveState (0x456da0): an Animation, its progress, two flags, an int, another Animation
        animation(r)
        r.int()
        bool(r); bool(r)
        r.int()
        animation(r)
        r.int() // the last projectile's id
        val queued = r.int()
        if (queued != 0) throw Bad("queued $queued")
        return WeaponState(charge, goal)
    }

    /** Animation::SaveState: two flags and five ints. */
    private fun animation(r: VanillaSaveReader) {
        bool(r); bool(r)
        repeat(5) { r.int() }
    }

    private fun bool(r: VanillaSaveReader) {
        val v = r.int()
        if (v != 0 && v != 1) throw Bad("flag $v")
    }

    /**
     * A drone's SaveState by its type (the layout VanillaSaveWriter writes): crew-like drones have none; the others
     * share 27 ints, an Animation and then their type's own ints.
     */
    private fun droneState(r: VanillaSaveReader, type: String) {
        if (type == "REPAIR" || type == "BATTLE") return
        repeat(27) { r.int() }
        animation(r)
        when (type) {
            "BOARDER" -> repeat(9) { r.int() }
            "HACKING" -> { repeat(4) { r.int() }; animation(r); animation(r) }
            "COMBAT", "SHIP_REPAIR" -> repeat(5) { r.int() }
            "SHIELD" -> r.int()
            "DEFENSE" -> {}
            else -> throw Bad("drone type $type")
        }
    }
}
