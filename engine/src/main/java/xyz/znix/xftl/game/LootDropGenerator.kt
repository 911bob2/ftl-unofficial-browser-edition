package xyz.znix.xftl.game

import kotlin.random.Random

object LootDropGenerator {
    /**
     * The scrap reward roll (0x5dfc90): an integer per-mille multiplier
     * from the tier's range, times 15 + 6 per reward level.
     */
    fun generateScrap(rand: Random, tier: Int, level: Int): Int {
        val range = SCRAP_DROP_MULTS[tier]
        val low = f32(f32(range.start.toFloat()) * 1000f).toInt()
        val high = f32(f32(range.endInclusive.toFloat()) * 1000f).toInt()
        val roll = rand.nextInt(high + 1 - low) + low
        return f32(f32(roll.toFloat() / 1000f) * (level * 6 + 15).toFloat()).toInt()
    }

    // The original computes in single precision; TeaVM's floats are doubles.
    private fun f32(value: Float): Float = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(value))

    /**
     * The reward level: the 0-based sector, plus one on Easy and minus one
     * (not below zero) on Hard, as the autoReward generator (0x5dff60) does.
     */
    fun rewardLevel(sector: Int, difficulty: Difficulty): Int = when (difficulty) {
        Difficulty.EASY -> sector + 1
        Difficulty.NORMAL -> sector
        Difficulty.HARD -> (sector - 1).coerceAtLeast(0)
    }

    /**
     * The autoReward generator (0x5dff60). Each scrap or resource roll
     * replaces the previous value of that resource, so a standard/stuff
     * reward that turns into an item ends with the item's scrap roll.
     * An event that already names a weapon, drone or augment gets no
     * random item. [sector] is the 0-based sector.
     */
    fun generateRewards(
        game: InGameState, rand: Random, tierSpec: RewardTier, type: RewardType,
        sector: Int, difficulty: Difficulty, hasExplicitItem: Boolean
    ): ResourceSet {
        val resources = ResourceSet()
        val level = rewardLevel(sector, difficulty)
        val tier = tierSpec.resolve(rand)
        var remainingType = type

        fun scrap(t: Int) {
            resources.scrap = generateScrap(rand, t, level)
        }

        fun resource(res: Resource, t: Int) {
            resources[res] = when (res) {
                Resource.FUEL -> FUEL_DROPS[t].random(rand)
                Resource.MISSILES -> MISSILE_DROPS[t].random(rand)
                Resource.DRONES -> DRONE_DROPS[t].random(rand)
                Resource.SCRAP -> generateScrap(rand, t, level)
            }
        }

        // 0x5dfea0: a random one of fuel/missiles/droneparts, other than [exclude].
        fun randomResource(exclude: Resource?): Resource {
            while (true) {
                val res = FMD[rand.nextInt(3)]
                if (res != exclude)
                    return res
            }
        }

        when (remainingType) {
            RewardType.STANDARD -> {
                scrap(tier)
                val first = randomResource(null)
                val second = randomResource(first)
                resource(first, RewardTier.LOW.ordinal)
                resource(second, RewardTier.LOW.ordinal)
                if (rand.nextInt(100) < 3) remainingType = RewardType.ITEM
            }

            RewardType.STUFF -> {
                scrap(RewardTier.LOW.ordinal)
                val first = randomResource(null)
                val second = randomResource(first)
                resource(first, tier)
                resource(second, tier)
                if (rand.nextInt(100) < 6) remainingType = RewardType.ITEM
            }

            RewardType.SCRAP_ONLY -> scrap(tier)

            RewardType.FUEL, RewardType.MISSILES, RewardType.DRONEPARTS -> {
                scrap(tier)
                resource(RewardType.ONLY_RESOURCE_MAPPINGS.getValue(RewardType.valueOf(remainingType.name + "_ONLY")), tier)
            }

            RewardType.FUEL_ONLY, RewardType.MISSILES_ONLY, RewardType.DRONEPARTS_ONLY ->
                resource(RewardType.ONLY_RESOURCE_MAPPINGS.getValue(remainingType), tier)

            else -> Unit
        }

        if (hasExplicitItem)
            return resources

        if (remainingType == RewardType.ITEM) {
            remainingType = RewardType.ITEMS[rand.nextInt(RewardType.ITEMS.size)]
        }

        if (RewardType.ITEMS.contains(remainingType)) {
            resources.items += when (remainingType) {
                RewardType.WEAPON -> game.lootPool.getWeapon(rand)
                RewardType.DRONE -> game.lootPool.getDrone(rand)
                RewardType.AUGMENT -> game.lootPool.getAugment(rand)
                else -> error("Unknown autoReward type: $remainingType")
            }
            scrap(tier)
        }

        return resources
    }

    private val FMD = listOf(Resource.FUEL, Resource.MISSILES, Resource.DRONES)

    // The resource tables at 0x8820e8 (missiles), 0x882100 (drone parts),
    // 0x882118 (fuel) and the scrap multipliers at 0x882130.
    val FUEL_DROPS = listOf(1..3, 2..4, 3..6)
    val MISSILE_DROPS = listOf(1..2, 2..4, 4..8)
    val DRONE_DROPS = listOf(1..1, 1..1, 1..2)
    val SCRAP_DROP_MULTS = listOf(0.5..0.7, 0.8..1.3, 1.3..1.55)
}

enum class RewardTier {
    LOW,
    MEDIUM,
    HIGH,
    RANDOM {
        // 0x5dff60 rolls rand() % 3 once for the whole reward.
        override fun resolve(rand: Random): Int = rand.nextInt(3)
    };

    open fun resolve(rand: Random): Int = ordinal

    companion object {
        /**
         * The event parser 0x5e52c0 compares the raw level attribute with
         * "LOW", "MED" and "HIGH" (case-sensitive); anything else, such as
         * "low" or "MEDIUM", is RANDOM.
         */
        fun fromName(name: String?): RewardTier = when (name) {
            "LOW" -> LOW
            "MED" -> MEDIUM
            "HIGH" -> HIGH
            else -> RANDOM
        }
    }
}

// See https://www.ftlwiki.com/wiki/Events_file_structure
// Note that many reward types give differenet levels for different items - eg,
// the STANDARD reward type gives the specified level in scrap and two resources
// at a low level.
// Thus each reward is specified with 'low', 'medium' or 'high' for a fixed
// reward, or 'spec' if it should use the tier specified by autoReward.
// Also note that 'FMD' stands for Fuel, Missile and Drone - the standard
// resources except scrap.
enum class RewardType {
    STANDARD, // spec scrap, two low FMD resources, 3% item chance
    STUFF, // low scrap, two spec FMD resources, 6% item chance

    // Spec their respective resource, spec scrap
    FUEL,
    MISSILES,
    DRONEPARTS,

    // Spec their respective resource
    FUEL_ONLY,
    MISSILES_ONLY,
    DRONEPARTS_ONLY,
    SCRAP_ONLY,

    // A random item (weapon, augment, drone schematic)
    ITEM,

    // An item of their respective types, spec scrap
    WEAPON,
    AUGMENT,
    DRONE; // (schematic)

    companion object {
        val ONLY_RESOURCE_MAPPINGS = mapOf(
            Pair(FUEL_ONLY, Resource.FUEL),
            Pair(MISSILES_ONLY, Resource.MISSILES),
            Pair(DRONEPARTS_ONLY, Resource.DRONES),
            Pair(SCRAP_ONLY, Resource.SCRAP)
        )

        // The item table at 0x919b60: weapon, drone, augment.
        val ITEMS = listOf(WEAPON, DRONE, AUGMENT)
    }
}
