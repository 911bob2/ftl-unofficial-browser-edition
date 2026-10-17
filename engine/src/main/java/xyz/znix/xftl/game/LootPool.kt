package xyz.znix.xftl.game

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.sector.SectorType
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.min
import kotlin.random.Random

/**
 * The blueprints a sector's random picks can return, with the sector's
 * rarity list applied (BlueprintManager 0x60ba60 resets every rarity to the
 * blueprint's own, SetRarity applies the list, and without Advanced Edition
 * the DLC items - which this engine doesn't load - are out).
 *
 * A pick is the original's weighted draw (BlueprintManager::GetRandomWeapon
 * and its siblings, 0x764be0): the blueprints of the kind sorted by name,
 * each weighing 6 minus its rarity (rarity 0 is never picked), one
 * `random32() % total + 1` walked down an implicit tree of weight sums.
 */
class LootPool(private val bpManager: BlueprintManager, sector: SectorType?) {
    /** The picked blueprints, one entry each (the weight is 6 minus [rarityOf]). */
    val pool: List<Blueprint>

    private val rarities = HashMap<String, Int>()

    init {
        val overrides = sector?.rarityOverrides ?: emptyMap()
        val all = ArrayList<Blueprint>()

        for (bp in bpManager.blueprints.values) {
            if (bp !is Blueprint) continue

            // First check if a BP's rarity has been overridden by the sector. If not, use
            // it's standard rarity. If that's not defined then it's inaccessible.
            val rarity = overrides[bp.name] ?: bp.rarity ?: continue

            // See https://subsetgames.com/forum/viewtopic.php?t=33471
            check(rarity in 0..5) { "Invalid rarity $rarity for blueprint ${bp.name}" }
            if (rarity == 0) continue
            rarities[bp.name] = rarity
            all.add(bp)
        }

        all.sortWith { a, b -> compareNames(a.name, b.name) }
        pool = all
    }

    fun rarityOf(bp: Blueprint): Int = rarities[bp.name] ?: 0

    fun getWeapon(rand: Random) = getRandom(rand) { it is AbstractWeaponBlueprint } ?: error("No available weapons!")

    fun getDrone(rand: Random) = getRandom(rand) { it is DroneBlueprint } ?: error("No available drones!")

    fun getAugment(rand: Random) = getRandom(rand) { it is AugmentBlueprint } ?: error("No available augments!")

    fun getCrewOrRandom(rand: Random, race: String): CrewBlueprint {
        if (race != "random") {
            return bpManager[race].resolve(rand) as CrewBlueprint
        }

        return getRandom(rand) { it is CrewBlueprint } as CrewBlueprint? ?: error("No available crew!")
    }

    /** One weighted pick among the blueprints [filter] accepts; null if there are none. */
    fun getRandom(rand: Random, filter: (Blueprint) -> Boolean): Blueprint? {
        val items = pool.filter(filter)
        if (items.isEmpty()) return null
        return pickMany(items, rand, 1)[0]
    }

    /**
     * [limit] distinct picks of one kind (the sampler of the original with
     * a count: a picked blueprint's weight is removed from the tree).
     */
    fun <T> getManyRandom(type: Class<T>, rand: Random, limit: Int): List<T> {
        val items = pool.filter { type.isInstance(it) }
        @Suppress("UNCHECKED_CAST")
        return pickMany(items, rand, min(limit, items.size)) as List<T>
    }

    private fun pickMany(items: List<Blueprint>, rand: Random, count: Int): List<Blueprint> {
        val n = items.size
        val weight = IntArray(n + 1)
        val sum = IntArray(n + 1)
        for (i in 1..n) {
            weight[i] = 6 - rarityOf(items[i - 1])
            sum[i] = weight[i]
        }
        // The tree is built from the last element down to the first.
        for (i in n downTo 2)
            sum[i shr 1] += sum[i]

        val result = ArrayList<Blueprint>()
        for (pick in 0 until count) {
            if (n == 0 || sum[1] <= 0)
                break
            var value = rand.nextInt(sum[1]) + 1
            var i = 1
            while (true) {
                if (value <= weight[i])
                    break
                value -= weight[i]
                val left = i * 2
                val leftSum = if (left <= n) sum[left] else 0
                i = left
                if (leftSum < value) {
                    value -= leftSum
                    i = left + 1
                }
                if (i > n) {
                    // Unreachable with consistent sums.
                    i = n
                    break
                }
            }

            result.add(items[i - 1])

            // The pick leaves the tree.
            val removed = weight[i]
            weight[i] = 0
            var node = i
            while (node != 0) {
                sum[node] -= removed
                node = node shr 1
            }
        }
        return result
    }

    private fun compareNames(a: String, b: String): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0)
                return d
        }
        return x.size - y.size
    }
}
