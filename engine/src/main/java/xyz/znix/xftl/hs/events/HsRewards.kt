// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomRewards.cpp and CustomRewards.h
// (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.LootDropGenerator
import xyz.znix.xftl.game.RewardTier
import xyz.znix.xftl.game.RewardType
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sector.CreatedEvent

/**
 * Hyperspace's `<rewards>` (CustomRewardsManager): resource ranges and scrap scaling that replace the original's
 * GetValue (0x5dfc90) tables, and named reward types for `<autoReward>` (GenerateReward hook).
 */
object HsRewards {
    class ResourceRange(@JvmField var min: Int = 0, @JvmField var max: Int = 0) {
        fun copy() = ResourceRange(min, max)

        /** CustomResourceReward::GetReward. */
        fun roll(): Int = min + NativeRandom.next31() % (max - min + 1)

        /** CustomResourceReward::SetDefault: the original's tables. */
        fun setDefault(type: String, level: Int) {
            val t = when (type) {
                "scrap" -> arrayOf(500 to 700, 800 to 1300, 1300 to 1550)
                "fuel" -> arrayOf(1 to 2, 2 to 4, 3 to 6)
                "missiles" -> arrayOf(1 to 2, 2 to 4, 4 to 8)
                "droneparts" -> arrayOf(1 to 1, 1 to 1, 1 to 2)
                else -> return
            }
            if (level in 0..2) { min = t[level].first; max = t[level].second }
        }
    }

    class ScrapScaling {
        @JvmField var baseAmount = 15.0f
        @JvmField var sectorAmount = 6.0f
        @JvmField var difficultyAmount = 0
        @JvmField val amounts = ArrayList<Float>()

        fun copy() = ScrapScaling().also {
            it.baseAmount = baseAmount; it.sectorAmount = sectorAmount; it.difficultyAmount = difficultyAmount
            it.amounts.addAll(amounts)
        }

        /** CustomScrapScaling::GetReward (`resourceAmount / 1000.0` is a double, the rest float). */
        fun reward(worldLevelIn: Int, resourceAmount: Float): Int {
            val worldLevel = maxOf(0, worldLevelIn + difficultyAmount)
            val r = resourceAmount.toDouble() / 1000.0
            return when {
                amounts.isEmpty() -> (r * f32(f32(sectorAmount * worldLevel) + baseAmount)).toInt()
                worldLevel >= amounts.size -> (r * f32(amounts.last() + f32(sectorAmount * (worldLevel - amounts.size + 1)))).toInt()
                else -> (r * amounts[worldLevel]).toInt()
            }
        }

        /** Single precision (TeaVM computes floats as doubles). */
        private fun f32(v: Float): Float = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(v))

        fun setDefault(difficulty: Int) {
            baseAmount = 15.0f; sectorAmount = 6.0f; difficultyAmount = 1 - difficulty; amounts.clear()
        }
    }

    class ResourceRewards {
        @JvmField val scrap = HashMap<Int, ResourceRange>()
        @JvmField val fuel = HashMap<Int, ResourceRange>()
        @JvmField val missiles = HashMap<Int, ResourceRange>()
        @JvmField val drones = HashMap<Int, ResourceRange>()
        @JvmField val scrapScaling = HashMap<Int, ScrapScaling>()

        fun range(type: String, level: Int): ResourceRange? = when (type) {
            "scrap" -> scrap[level]
            "fuel" -> fuel[level]
            "missiles" -> missiles[level]
            "droneparts" -> drones[level]
            else -> null
        }
    }

    class Generator {
        /** Resource name -> override level (-1 = the reward's level). */
        @JvmField val rewards = HashMap<String, Int>()
        /** The non-scrap resources in file order (duplicates kept, as HS's vector). */
        @JvmField val resources = ArrayList<String>()
        @JvmField var minResources = 3
        @JvmField var maxResources = 3
        @JvmField var bonusRoll = 0
        @JvmField val bonus = ArrayList<Pair<String, Int>>()
    }

    class RewardDef {
        @JvmField val rewards = ResourceRewards()
        @JvmField val scaling = HashMap<Int, ScrapScaling>()
        @JvmField var defaultGenerator = Generator()
        @JvmField val generators = HashMap<Int, Generator>()
        @JvmField var skipIfWeapon = false
        @JvmField var skipIfDrone = false
        @JvmField var skipIfAugment = false
        @JvmField var weapon = false
        @JvmField var drone = false
        @JvmField var augment = false

        fun customScrapScaling(difficulty: Int, level: Int): ScrapScaling? {
            rewards.scrapScaling[level]?.let { return it.copy().also { s -> s.difficultyAmount += 1 - difficulty } }
            scaling[difficulty]?.let { return it.scrap() }
            scaling[1]?.let { return it.scrap().also { s -> s.difficultyAmount += 1 - difficulty } }
            return null
        }
    }

    private fun ScrapScaling.scrap() = copy()

    private val levels = HashMap<String, Int>()
    private var nextLevel = 4
    @JvmField val defaultRewards = ResourceRewards()
    @JvmField val defaultScaling = HashMap<Int, ScrapScaling>()
    @JvmField val rewards = HashMap<String, RewardDef>()

    /** GenerateReward_LocalType: the named reward being generated. */
    private var localType: RewardDef? = null

    /** True once a `<rewards>` node was read: HS's GetValue hook then applies to every reward. */
    @JvmField var loaded = false

    init { reset() }

    fun reset() {
        levels.clear(); levels["LOW"] = 0; levels["MED"] = 1; levels["HIGH"] = 2; levels["RANDOM"] = 3; nextLevel = 4
        defaultRewards.scrap.clear(); defaultRewards.fuel.clear(); defaultRewards.missiles.clear(); defaultRewards.drones.clear()
        defaultRewards.scrapScaling.clear(); defaultScaling.clear(); rewards.clear(); localType = null; loaded = false
    }

    /** CustomRewardsManager::GetRewardLevel: unknown names get new levels from 4 on. */
    fun rewardLevel(s: String): Int {
        val key = s.uppercase()
        return levels[key] ?: nextLevel.also { levels[key] = it; nextLevel++ }
    }

    private fun float(s: String?): Float? = s?.let { HsEventParser.parseFloat(it) }
    private fun int(s: String?): Int? = s?.let { HsEventParser.parseInt(it) }

    // ------------------------------------------------------------------ parsing (ParseRewardsNode)

    fun parse(node: Element) {
        loaded = true
        try {
            for (child in node.children) {
                when (child.name) {
                    "resourceRewards" -> parseResourceRewards(child, defaultRewards)
                    "rewardScaling" -> {
                        val s = ScrapScaling()
                        defaultScaling[parseRewardScaling(child, s)] = s
                    }
                    "reward" -> {
                        val name = child.getAttributeValue("name") ?: error("reward without name")
                        val def = RewardDef()
                        for (c in child.children) when (c.name) {
                            "skipIfWeapon" -> def.skipIfWeapon = true
                            "skipIfDrone" -> def.skipIfDrone = true
                            "skipIfAugment" -> def.skipIfAugment = true
                            "weapon" -> def.weapon = true
                            "drone" -> def.drone = true
                            "augment" -> def.augment = true
                            "resourceRewards" -> parseResourceRewards(c, def.rewards)
                            "rewardScaling" -> {
                                val s = ScrapScaling()
                                def.scaling[parseRewardScaling(c, s)] = s
                            }
                            "reward" -> parseGenerator(c, def)
                        }
                        rewards[name] = def
                    }
                }
            }
        } catch (e: RuntimeException) {
            // "Error parsing <rewards> in hyperspace.xml": HS keeps what it read so far.
            HS.unsupported("parse", "hyperspace.xml rewards", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseResourceRewards(node: Element, into: ResourceRewards) {
        for (child in node.children) {
            val level = child.getAttributeValue("level")?.let { rewardLevel(it) } ?: -1
            val r = ResourceRange()
            var valid = false
            int(child.getAttributeValue("min"))?.let { r.min = it; valid = true }
            int(child.getAttributeValue("max"))?.let { r.max = it; valid = true }
            if (valid) when (child.name) {
                "scrap" -> into.scrap[level] = r
                "fuel" -> into.fuel[level] = r
                "missiles" -> into.missiles[level] = r
                "droneparts" -> into.drones[level] = r
            }
            if (child.name == "scrap") {
                val s = ScrapScaling()
                if (parseScrapScaling(child, s)) into.scrapScaling[level] = s
            }
        }
    }

    private fun parseRewardScaling(node: Element, into: ScrapScaling): Int {
        val difficulty = int(node.getAttributeValue("difficulty")) ?: 1
        for (child in node.children) {
            if (child.name == "scrap") {
                val s = ScrapScaling()
                if (parseScrapScaling(child, s)) {
                    into.baseAmount = s.baseAmount; into.sectorAmount = s.sectorAmount
                    into.difficultyAmount = s.difficultyAmount; into.amounts.clear(); into.amounts.addAll(s.amounts)
                }
            }
        }
        return difficulty
    }

    private fun parseScrapScaling(node: Element, s: ScrapScaling): Boolean {
        var valid = false
        float(node.getAttributeValue("baseAmount"))?.let { s.baseAmount = it; valid = true }
        float(node.getAttributeValue("sectorAmount"))?.let { s.sectorAmount = it; valid = true }
        int(node.getAttributeValue("difficultyAmount"))?.let { s.difficultyAmount = it; valid = true }
        for (child in node.children) {
            valid = true
            s.amounts += float(child.textTrim) ?: 0f
        }
        return valid
    }

    private fun parseGenerator(node: Element, def: RewardDef) {
        val level = node.getAttributeValue("level")?.let { rewardLevel(it) } ?: -1
        val g = Generator()
        int(node.getAttributeValue("minResources"))?.let { g.minResources = it }
        int(node.getAttributeValue("maxResources"))?.let { g.maxResources = it }
        for (child in node.children) {
            when (child.name) {
                "resource" -> {
                    val name = child.getAttributeValue("name") ?: error("resource without name")
                    g.rewards[name] = child.getAttributeValue("level")?.let { rewardLevel(it) } ?: -1
                    if (name != "scrap") g.resources += name
                }
                "bonus" -> {
                    int(child.getAttributeValue("roll"))?.let { g.bonusRoll = it }
                    for (b in child.children) g.bonus += Pair(b.name, int(b.getAttributeValue("chance")) ?: 1)
                }
            }
        }
        if (level == -1) def.defaultGenerator = g else def.generators[level] = g
    }

    // ------------------------------------------------------------------ GetValue hook

    /** The values a reward writes: GetValue replaces one field of the ResourceEvent at a time. */
    class Values {
        @JvmField var scrap: Int? = null
        @JvmField var fuel: Int? = null
        @JvmField var missiles: Int? = null
        @JvmField var droneParts: Int? = null
    }

    private fun difficulty(game: InGameState): Int = game.difficulty.ordinal

    private fun customRange(type: String, level: Int): ResourceRange? =
        localType?.rewards?.range(type, level)?.copy() ?: defaultRewards.range(type, level)?.copy()

    private fun customScaling(difficulty: Int, level: Int): ScrapScaling? {
        localType?.customScrapScaling(difficulty, level)?.let { return it }
        defaultRewards.scrapScaling[level]?.let { return it.copy().also { s -> s.difficultyAmount += 1 - difficulty } }
        defaultScaling[difficulty]?.let { return it.copy() }
        defaultScaling[1]?.let { return it.copy().also { s -> s.difficultyAmount += 1 - difficulty } }
        return null
    }

    /**
     * HS's GetValue hook: the value for [type] ("scrap", "fuel", "missiles", "droneparts") at reward [level] and
     * [worldLevel] (the reward level, difficulty already applied), or null when nothing custom applies (the
     * original's table is used, [LootDropGenerator]).
     */
    @JvmStatic
    fun getValue(game: InGameState, type: String, level: Int, worldLevel: Int): Int? {
        val diff = difficulty(game)
        var range = customRange(type, level)
        val scaling = if (type == "scrap") customScaling(diff, level) else null
        if (range == null && scaling == null) return null
        if (range == null) range = ResourceRange().also { it.setDefault(type, level) }
        return if (type == "scrap") {
            val s = scaling ?: ScrapScaling().also { it.setDefault(diff) }
            val randomScrap = range.roll().toFloat()
            s.reward(worldLevel, randomScrap)
        } else {
            range.roll()
        }
    }

    // ------------------------------------------------------------------ GenerateReward hook (named rewards)

    fun has(name: String): Boolean = loaded && rewards.containsKey(name)

    /**
     * GenerateReward for a named HS reward: writes into [created] like the original's ResourceEvent (only the fields
     * a GetValue call wrote, and the item slots it fills). [worldLevel] is the reward level (difficulty applied).
     */
    fun generate(game: InGameState, created: CreatedEvent, name: String, tier: RewardTier, worldLevel: Int) {
        val def = rewards[name] ?: return
        localType = def
        try {
            var level = tier.ordinal
            if (level == 3) level = NativeRandom.next31() % 3
            val bonus = typeReward(game, def, created, level, worldLevel)
            if (bonus.isNotEmpty()) generateAny(game, created, bonus, level, worldLevel)
        } finally {
            localType = null // HS resets GenerateReward_LocalType to nullptr, also after a nested call
        }
    }

    /** GenerateReward(resourceEvent, {bonus, level}) from a bonus roll: a named reward or an original one. */
    private fun generateAny(game: InGameState, created: CreatedEvent, name: String, level: Int, worldLevel: Int) {
        if (rewards.containsKey(name)) {
            generate(game, created, name, RewardTier.values()[level.coerceIn(0, 3)], worldLevel)
            return
        }
        val type = if (name.equals("scrap", true)) RewardType.SCRAP_ONLY
        else RewardType.values().firstOrNull { it.name == name.uppercase() }
        if (type == null) {
            HS.unsupported("reward", "bonus", name)
            return
        }
        // The original GenerateReward with the custom type still the local one (HS resets it after this call).
        val explicit = created.weapon != null || created.drone != null || created.augment != null
        val r = LootDropGenerator.generateRewardsAtLevel(game, NativeRandom, level.coerceIn(0, 2), type, worldLevel, explicit)
        if (r.fuel != 0) created.fuel = r.fuel
        if (r.missiles != 0) created.missiles = r.missiles
        if (r.droneParts != 0) created.droneParts = r.droneParts
        if (r.scrap != 0) created.scrap = r.scrap
        for (item in r.items) when (item) {
            is xyz.znix.xftl.weapons.AbstractWeaponBlueprint -> created.weapon = item
            is xyz.znix.xftl.weapons.DroneBlueprint -> created.drone = item
            is xyz.znix.xftl.augments.AugmentBlueprint -> created.augment = item
        }
    }

    /** CustomRewardType::GetReward. */
    private fun typeReward(game: InGameState, def: RewardDef, created: CreatedEvent, level: Int, worldLevel: Int): String {
        if (def.skipIfWeapon && created.weapon != null) return ""
        if (def.skipIfDrone && created.drone != null) return ""
        if (def.skipIfAugment && created.augment != null) return ""
        val g = def.generators[level] ?: def.defaultGenerator
        val bonus = generatorReward(game, g, created, level, worldLevel)
        if (def.weapon) created.weapon = game.lootPool.getWeapon(NativeRandom)
        if (def.drone) created.drone = game.lootPool.getDrone(NativeRandom)
        if (def.augment) created.augment = game.lootPool.getAugment(NativeRandom)
        return bonus
    }

    private fun write(game: InGameState, created: CreatedEvent, type: String, level: Int, worldLevel: Int) {
        val v = getValue(game, type, level, worldLevel) ?: LootDropGenerator.originalValue(NativeRandom, type, level, worldLevel)
        when (type) {
            "scrap" -> created.scrap = v
            "fuel" -> created.fuel = v
            "missiles" -> created.missiles = v
            "droneparts" -> created.droneParts = v
        }
    }

    /** CustomRewardGenerator::GetReward. */
    private fun generatorReward(game: InGameState, g: Generator, created: CreatedEvent, level: Int, worldLevel: Int): String {
        val remaining = ArrayList(g.resources)
        var num = g.minResources
        if (g.maxResources > g.minResources) num = g.minResources + NativeRandom.next31() % (g.maxResources - g.minResources + 1)
        g.rewards["scrap"]?.let { o -> write(game, created, "scrap", if (o != -1) o else level, worldLevel) }
        for (i in 0 until num) {
            if (remaining.isEmpty()) break
            val res = remaining.removeAt(NativeRandom.next31() % remaining.size)
            g.rewards[res]?.let { o -> write(game, created, res, if (o != -1) o else level, worldLevel) }
        }
        if (g.bonusRoll != 0) {
            var rng = NativeRandom.next31() % g.bonusRoll
            for ((n, chance) in g.bonus) {
                rng -= chance
                if (rng < 0) return n
            }
        }
        return ""
    }
}
