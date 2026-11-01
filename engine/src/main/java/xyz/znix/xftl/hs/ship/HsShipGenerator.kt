package xyz.znix.xftl.hs.ship

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomShipGenerator.cpp (and .h):
// the `<shipGenerators>` data and the numbers CustomShipGenerator::CreateShip uses. The generation itself runs in
// xyz.znix.xftl.shipgen.ShipGenerator, which branches on these where the HS generator differs from the vanilla one.

import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.systems.HsSystems

/**
 * Hyperspace's enemy ship generator (`<shipGenerators>` in hyperspace.xml). When the node exists the
 * ShipGenerator::CreateShip hook (priority -100) replaces the vanilla CreateShip: the blueprint is picked at the
 * unadjusted sector, and the ship definition's `shipGenerator` (else the unnamed default generator) builds it; no
 * generator falls back to the vanilla CreateShip. MV names `standardEnemy` in its default `<customShip>`, so every
 * MV enemy that is not a direct blueprint goes through it.
 */
object HsShipGenerator {
    private fun f32(v: Float): Float = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(v))

    /** `SectorScaling`: a value rising linearly with the level between minValue and maxValue. */
    class SectorScaling {
        var enabled = false
        var minValue = 0f
        var maxValue = 1f
        var baseValue = 0f
        var sectorValue = 0.125f

        fun copy() = SectorScaling().also {
            it.enabled = enabled; it.minValue = minValue; it.maxValue = maxValue; it.baseValue = baseValue; it.sectorValue = sectorValue
        }

        /** `GetValue`: max(minValue, min(baseValue + level * sectorValue, maxValue)). */
        fun value(level: Int): Float = maxOf(minValue, minOf(f32(baseValue + f32(level.toFloat() * sectorValue)), maxValue))

        fun parse(node: Element) {
            enabled = true
            var minSector = 0f
            var maxSector = Float.NaN
            for (child in node.children) {
                val v = child.text
                when (child.name) {
                    "minValue" -> minValue = lexFloat(v)
                    "maxValue" -> maxValue = lexFloat(v)
                    "minSector" -> minSector = lexFloat(v)
                    "maxSector" -> maxSector = lexFloat(v)
                }
            }
            if (maxSector.isNaN()) maxSector = f32(minSector + 8f)
            sectorValue = f32(f32(maxValue - minValue) / f32(maxSector - minSector))
            baseValue = f32(minValue - f32(minSector * sectorValue))
        }
    }

    /** `SystemLevelScaling`: the random bonus bars of a system's maximum, by level. */
    class SystemLevelScaling {
        var enabled = false
        var sectorScaling = SectorScaling()
        var randomScaling = ArrayList<Pair<Int, Int>>()

        fun copy() = SystemLevelScaling().also {
            it.enabled = enabled; it.sectorScaling = sectorScaling.copy(); it.randomScaling = ArrayList(randomScaling)
        }

        /** `GetRandomScalingValue`: the value of the last entry whose sector is not above the level (the first entry always). */
        fun randomValue(level: Int): Int {
            var i = 1
            while (i < randomScaling.size) {
                if (randomScaling[i].first > level) break
                i++
            }
            return randomScaling[i - 1].second
        }

        fun parse(node: Element, difficulty: Int) {
            enabled = true
            var fresh = false
            for (child in node.children) {
                when (child.name) {
                    "sectorScaling" -> sectorScaling.parse(child)
                    "randomScaling" -> {
                        if (!fresh) { randomScaling.clear(); fresh = true }
                        val v = child.text
                        if (v.isEmpty()) continue
                        val value = lexInt(v)
                        val level = child.getAttributeValue("sector")?.let { lexInt(it) } ?: -1
                        randomScaling += Pair(level, value)
                    }
                }
            }
            if (randomScaling.isEmpty()) randomScaling = ArrayList(defaultSystemLevelScaling[difficulty].randomScaling)
        }
    }

    class Generator {
        val difficultyMod = floatArrayOf(-1f, 0f, 0f)
        val defaultSectorScaling = Array(3) { SectorScaling() }
        val systemLevelScaling = HashMap<Int, Array<SystemLevelScaling>>()

        fun copy() = Generator().also {
            difficultyMod.copyInto(it.difficultyMod)
            for (i in 0 until 3) it.defaultSectorScaling[i] = defaultSectorScaling[i].copy()
            for ((k, v) in systemLevelScaling) it.systemLevelScaling[k] = Array(3) { i -> v[i].copy() }
        }

        /** CreateShip: `int level = sector + difficultyMod[difficulty]` (a float sum, truncated). */
        fun level(sector: Int, difficulty: Difficulty): Int = f32(sector.toFloat() + difficultyMod[difficulty.ordinal]).toInt()

        /** `GetDefaultSectorScaling`: the difficulty's, else the normal one. */
        fun defaultScaling(difficulty: Difficulty): SectorScaling =
            defaultSectorScaling[difficulty.ordinal].takeIf { it.enabled } ?: defaultSectorScaling[1]

        /** `GetSectorScaling`. */
        fun sectorScaling(selected: SectorScaling?, difficulty: Difficulty): SectorScaling =
            if (selected != null && selected.enabled) selected else defaultScaling(difficulty)

        /** `GetSystemLevelScaling`: the system's entry for the difficulty, else for normal, else the same for system -1, else the defaults. */
        fun systemScaling(difficulty: Int, sysId: Int): SystemLevelScaling {
            systemLevelScaling[sysId]?.let { a ->
                if (a[difficulty].enabled) return a[difficulty]
                if (a[1].enabled) return a[1]
            }
            return if (sysId == -1) defaultSystemLevelScaling[difficulty] else systemScaling(difficulty, -1)
        }

        /** `GenerateSystemMaxes` for one template: `random32() % (bonus + 1) + scaling(level) * (max - min) + min`, clamped. */
        fun systemMax(sysId: Int, minPower: Int, maxPower: Int, level: Int, difficulty: Difficulty): Int {
            val scaling = systemScaling(difficulty.ordinal, sysId)
            val sector = sectorScaling(scaling.sectorScaling, difficulty)
            val bonus = scaling.randomValue(level)
            val draw = ftl.browser.NativeRandom.next31() % (bonus + 1).coerceAtLeast(1)
            val power = f32(f32(draw.toFloat() + f32(sector.value(level) * (maxPower - minPower).toFloat())) + minPower.toFloat()).toInt()
            return minOf(maxPower, maxOf(power, minPower))
        }
    }

    /** `CustomShipGenerator::enabled`: set once a `<shipGenerators>` node was read. */
    @JvmStatic
    var enabled = false
        private set
    private val named = HashMap<String, Generator>()
    private var defaultGenerator: Generator? = null
    private val defaultSystemLevelScaling = Array(3) { SystemLevelScaling() }

    /** `Init` + `ParseGeneratorNode` for every `<shipGenerators>` node. */
    fun load(config: HsConfig?) {
        enabled = false; named.clear(); defaultGenerator = null
        for (i in 0 until 3) defaultSystemLevelScaling[i] = SystemLevelScaling()
        val nodes = config?.nodesOf("shipGenerators") ?: emptyList()
        for (node in nodes) {
            init()
            try {
                parse(node)
            } catch (e: Exception) {
                // HS shows an error box and keeps what it parsed so far.
                HS.unsupported("parse", HS.HYPERSPACE_XML, "<shipGenerators>: ${e.message ?: e}")
            }
        }
    }

    private fun init() {
        if (enabled) return
        defaultSystemLevelScaling[0].apply { enabled = true; randomScaling = arrayListOf(Pair(-1, 0), Pair(0, 1)) }
        defaultSystemLevelScaling[1].apply { enabled = true; randomScaling = arrayListOf(Pair(-1, 0), Pair(0, 1), Pair(2, 2)) }
        defaultSystemLevelScaling[2].apply { enabled = true; randomScaling = arrayListOf(Pair(-1, 0), Pair(0, 1), Pair(2, 2)) }
        enabled = true
    }

    private fun parse(node: Element) {
        for (genNode in node.children) {
            if (genNode.name != "shipGenerator") continue
            val name = genNode.getAttributeValue("name")
            val generator: Generator
            if (name != null) {
                generator = defaultGenerator?.copy() ?: Generator()
                named[name] = generator
            } else {
                generator = Generator()
                defaultGenerator = generator
            }
            for (child in genNode.children) {
                val difficulty = child.getAttributeValue("difficulty")?.let { lexInt(it) } ?: 1
                when (child.name) {
                    "difficultyMod" -> if (difficulty != -1) generator.difficultyMod[checkIndex(difficulty)] = lexFloat(child.text)
                    "sectorScaling" -> if (difficulty != -1) generator.defaultSectorScaling[checkIndex(difficulty)].parse(child)
                    "systemLevelScaling" -> {
                        val sysId = child.getAttributeValue("system")?.let { HsSystems.nameToId(it) } ?: -1
                        if (difficulty != -1) {
                            checkIndex(difficulty)
                            // operator[] creates the system's entry first, so the copy source is looked up with it present.
                            val entry = generator.systemLevelScaling.getOrPut(sysId) { Array(3) { SystemLevelScaling() } }
                            val scaling = generator.systemScaling(difficulty, sysId).copy()
                            scaling.parse(child, difficulty)
                            entry[difficulty] = scaling
                        }
                    }
                }
            }
        }
    }

    /** `std::array::at`: out of range throws (the parse stops). */
    private fun checkIndex(i: Int): Int {
        if (i !in 0..2) throw IndexOutOfBoundsException("array::at: $i")
        return i
    }

    /**
     * The CreateShip hook's generator for a picked blueprint: the definition's `shipGenerator` by name (an unknown
     * name gives the default generator), else the default generator; null builds the ship the vanilla way.
     */
    @JvmStatic
    fun generatorFor(blueprintName: String): Generator? {
        val wanted = HsShips.def(blueprintName).shipGenerator
        if (wanted.isEmpty()) return defaultGenerator
        return named[wanted] ?: defaultGenerator
    }

    // boost::lexical_cast: the whole string must parse (no surrounding spaces).
    private fun lexInt(s: String): Int = s.toIntOrNull() ?: throw NumberFormatException("bad lexical cast: '$s'")
    private fun lexFloat(s: String): Float = s.toFloatOrNull()?.takeIf { s.isNotEmpty() && !s[0].isWhitespace() && !s.last().isWhitespace() }
        ?: throw NumberFormatException("bad lexical cast: '$s'")
}
