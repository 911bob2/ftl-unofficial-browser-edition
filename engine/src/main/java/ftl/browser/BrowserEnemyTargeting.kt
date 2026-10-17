package ftl.browser

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.ai.AIUtils
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Piloting
import xyz.znix.xftl.systems.SubSystem
import kotlin.random.Random

/**
 * The enemy's choice of what to aim at: CombatAI::ChooseSystem (0x62b200) and the room draw around it in the weapon
 * pre-aim (0x62c000). System ids are the native ones (0 shields, 1 engines, 2 oxygen, 3 weapons, 4 drones, 5 medbay,
 * 6 piloting, 7 sensors, 8 doors, 9 teleporter, 10 cloaking, 11 artillery, 12 battery, 13 clonebay, 14 mind control,
 * 15 hacking).
 */
object BrowserEnemyTargeting {
    /** CombatAI's priority vector (+0x20): shields, engines, weapons, oxygen, drones. */
    private val PRIORITY = intArrayOf(0, 1, 3, 2, 4)

    /** The player-facing system for a native system id, or null if the ship doesn't have it. */
    @JvmStatic
    fun system(ship: Ship, id: Int): AbstractSystem? = when (id) {
        0 -> ship.shields
        1 -> ship.engines
        2 -> ship.oxygen
        3 -> ship.weapons
        4 -> ship.drones
        5 -> ship.medbay
        6 -> ship.piloting
        7 -> ship.sensors
        8 -> ship.doorsSystem
        9 -> ship.teleporter
        10 -> ship.cloaking
        11 -> ship.artillery.firstOrNull()
        12 -> ship.backupBattery
        13 -> ship.clonebay
        14 -> ship.mindControl
        15 -> ship.hacking
        else -> null
    }

    /**
     * ShipSystem::GetEffectivePower (0x63b750): the power level plus the bonus power (Zoltan, battery; all of it is
     * [MainSystem.powerSupplied]) plus the manned bonus. The bonus (+0x1ad) is only ever set for the sensors and the
     * doors system (ctor 0x642370, CheckDlcEnabled 0x63d530), and [SubSystem.effectivePower] already adds it, so the
     * main systems have none.
     */
    private fun effectivePower(system: AbstractSystem?): Int = when (system) {
        is SubSystem -> system.effectivePower
        is MainSystem -> system.powerSupplied
        else -> 0
    }

    /**
     * ShipSystem vtable slot 11 (Functioning, 0x63a960): the piloting system (id 6) also needs somebody manning it,
     * everything else only needs its effective power to be positive (Powered, 0x756980).
     */
    private fun functioning(system: AbstractSystem?): Boolean {
        if (system is Piloting && !AIUtils.isManned(system))
            return false
        return effectivePower(system) > 0
    }

    /**
     * The shields override Powered (0x630850): they work from two bars (one whole layer).
     */
    private fun shieldsFunctioning(system: AbstractSystem?): Boolean =
        system != null && shieldBarsFunction(effectivePower(system))

    /** Shields::Powered (0x630850): the shields count as working from two bars (one whole layer). */
    internal fun shieldBarsFunction(bars: Int): Boolean = bars >= 2

    /**
     * The Hard-mode candidate list (the `Hard` half of 0x62b200): every system the target has whose state makes it
     * worth shooting. [own] is the shooting ship, [target] the one being shot.
     */
    @JvmStatic
    fun hardCandidates(own: Ship, target: Ship): List<Int> {
        val list = ArrayList<Int>()
        for (id in 0..15) {
            val system = system(target, id) ?: continue
            val wanted = when (id) {
                0 -> shieldsFunctioning(system)
                4 -> functioning(system)
                // GetDodgeFactor (0x49fe90) is 0 whenever the ship lacks engines (slot table +4 is -1) or piloting
                // (+0x18 is -1: no path past the piloting checks), so 'dodge > 25' needs both systems; Ship.evasion
                // dereferences both, hence the explicit test.
                1, 6 -> target.piloting != null && target.engines != null && target.evasion > 25
                // FUN_0049f420 rounds the oxygen percentage (FUN_007458d0) instead of truncating it.
                2 -> Math.floor((target.averageOxygen * 100f + 0.5f).toDouble()).toInt() <= 49
                3 -> functioning(system)
                8 -> target.intruders.isNotEmpty() || target.rooms.any { room -> room.fires.any { it != null } }
                9 -> own.intruders.isNotEmpty()
                10 -> !(system as MainSystem).isPowerLocked
                11 -> target.artillery.first().browserWeapon.timeCharged < 4f
                12 -> target.backupBattery?.timeRemaining != null
                // CloneSystem::GetTimeToClone (0x644e20) is the clone progress: it only runs while somebody is
                // being cloned with power, and resets to zero otherwise.
                13 -> (target.clonebay?.cloneProgress ?: 0f) > 0f
                14 -> target.mindControl?.active == true
                15 -> target.hacking?.droneLaunched == true
                else -> false
            }
            if (wanted)
                list.add(id)
        }
        return list
    }

    /**
     * CombatAI::ChooseSystem (0x62b200): the system to aim a shot at, or null for a random room.
     *
     * Easy and Normal aim at a system 20% / 33% of the time, taken from the fixed priority list. Hard builds a
     * candidate list and uses it a quarter of the time, otherwise a random system or a random room.
     */
    @JvmStatic
    fun chooseSystem(own: Ship, target: Ship?, difficulty: Difficulty, rand: Random = BrowserRandom): AbstractSystem? {
        if (difficulty != Difficulty.HARD) {
            val threshold = if (difficulty == Difficulty.EASY) 20 else 33
            if (rand.nextInt(100) < threshold && target != null) {
                return system(target, PRIORITY[rand.nextInt(PRIORITY.size)])
            }
            return null
        }

        // The target is always set when we're shooting.
        if (target == null)
            return null

        val candidates = hardCandidates(own, target)
        val roll = rand.nextInt(100)
        if (candidates.isEmpty() || roll >= 25) {
            if (roll < 50) {
                val systems = target.systems
                return if (systems.isEmpty()) null else systems[rand.nextInt(systems.size)]
            }
            return null
        }
        return system(target, candidates[rand.nextInt(candidates.size)])
    }

    /** One aim point of the weapon pre-aim (0x62c000): the room of the chosen system, or a random room. */
    @JvmStatic
    fun pickRoom(own: Ship, target: Ship, rand: Random = BrowserRandom): Room {
        val system = chooseSystem(own, target, own.sys.difficulty, rand)
        val randomRoom = target.rooms[rand.nextInt(target.rooms.size)]
        return system?.room ?: randomRoom
    }
}
