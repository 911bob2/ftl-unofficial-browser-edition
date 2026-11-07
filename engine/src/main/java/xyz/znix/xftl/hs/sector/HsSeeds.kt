// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/Seeds.cpp and Seeds.h (GPL-3.0-or-later; the browser
// engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.sector

import ftl.browser.NativeRandom
import org.jdom2.Element

/**
 * `std::mt19937` (default `uint32` seeding). Hyperspace's own streams use it: SeededRng (worldRng/secretRng), the run
 * seed and the crew-name generator. Outputs are the full 32 bits in an Int.
 */
class HsMt19937(seed: Int) {
    private val mt = IntArray(N)
    private var index = N

    init { reseed(seed) }

    fun reseed(seed: Int) {
        mt[0] = seed
        for (i in 1 until N) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        index = N
    }

    fun next(): Int {
        if (index >= N) twist()
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }

    private fun twist() {
        for (i in 0 until N) {
            val y = (mt[i] and UPPER) or (mt[(i + 1) % N] and LOWER)
            var v = mt[(i + M) % N] xor (y ushr 1)
            if (y and 1 != 0) v = v xor MATRIX
            mt[i] = v
        }
        index = 0
    }

    companion object {
        private const val N = 624
        private const val M = 397
        private const val MATRIX = 0x9908b0df.toInt()
        private const val UPPER = 0x80000000.toInt()
        private const val LOWER = 0x7fffffff
    }
}

/** Seeds.h SeededRng: an mt19937 with the seed, a draw counter and the last output (saved as seed + counter). */
class HsSeededRng(seed: Int) {
    private val engine = HsMt19937(seed)
    var seed = seed; private set
    var counter = 0; private set
    var current = seed; private set

    /** `operator()`. */
    fun next(): Int {
        counter++
        current = engine.next()
        return current
    }

    fun reset(s: Int) {
        seed = s; counter = 0
        engine.reseed(s)
        current = s
    }

    /** Import: rebuild from seed and counter (discard counter-1, then one draw is current). */
    fun restore(s: Int, c: Int) {
        reset(s)
        counter = c
        if (c >= 2) {
            repeat(c) { engine.next() }
            current = engine.next()
        } else if (c == 1) {
            current = engine.next()
        }
    }
}

/**
 * Seeds.cpp: Hyperspace's seeded runs (`<seeds enabled>` in hyperspace.xml, true by default). The run seed (typed or
 * drawn) seeds the sector tree and the first sector; later sectors take `worldRng.current + the sector's row`.
 * The quest seed keeps seeded variable rolls and quest placement apart from the map stream.
 */
object HsSeeds {
    @JvmField var enabled = true
    @JvmField var allowMetaVars = true

    /** Global::currentSeed / isCustomSeed. */
    @JvmField var currentSeed = 0
    @JvmField var isCustomSeed = false

    /** StarMap::currentSectorSeed (+ the HS-seeded first value). */
    @JvmField var currentSectorSeed = 0

    @JvmField var questSeed = 0
    @JvmField var bossFleetSeed = 0

    val worldRng = HsSeededRng(0)
    val secretRng = HsSeededRng(0)

    /** The seed the player typed for the next run (ShipBuilder's seed box), null for a random one. */
    @JvmField var requestedSeed: Long? = null

    /** `Global::seededRng` (clock-seeded in HS): the source of a run seed nobody typed. */
    private var seededRng: HsMt19937? = null

    /**
     * hyperspace.xml `<seeds enabled allowMetaVars>`. Global::InitializeResources (Resources.cpp) reads every `<seeds>`
     * node in file order, each attribute it has overriding the earlier value, through EventsParser::ParseBoolean
     * ("true", "TRUE" or "True").
     */
    fun readConfig(hyperspaceRoot: Element?) {
        enabled = true; allowMetaVars = true
        for (node in hyperspaceRoot?.getChildren("seeds") ?: return) {
            node.getAttributeValue("enabled")?.let { enabled = xyz.znix.xftl.hs.HsConfig.bool(it) }
            node.getAttributeValue("allowMetaVars")?.let { allowMetaVars = xyz.znix.xftl.hs.HsConfig.bool(it) }
        }
    }

    /**
     * StarMap::NewGame (Seeds.cpp, priority -500): the run seed. HS seeds `Global::seededRng` from the clock; this
     * engine seeds it once from the generation stream instead (it is not part of the game's seeded state).
     */
    fun newGame() {
        val custom = requestedSeed
        var customSeed = custom != null
        var seed = custom?.toInt() ?: (seededRng ?: HsMt19937(NativeRandom.next31()).also { seededRng = it }).next()
        // Seeds.cpp: GET_RUN_SEED(customSeed, seed) lets Lua intercept the seed; an (non-nil) result replaces both.
        if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.HsLua.hasInt[xyz.znix.xftl.hs.lua.LuaApi.EV_GET_RUN_SEED]) {
            xyz.znix.xftl.hs.lua.LuaJs.pushboolean(customSeed)
            xyz.znix.xftl.hs.lua.LuaJs.pushinteger(seed.toDouble()) // `int seed` in HS
            if (xyz.znix.xftl.hs.lua.LuaJs.fire(xyz.znix.xftl.hs.lua.LuaApi.EV_GET_RUN_SEED, 2, 2) == 2) {
                customSeed = xyz.znix.xftl.hs.lua.LuaJs.toboolean(-2)
                seed = xyz.znix.xftl.hs.lua.LuaJs.tonumber(-1).toLong().toInt()
                xyz.znix.xftl.hs.lua.LuaJs.pop(4)
            } else xyz.znix.xftl.hs.lua.LuaJs.pop(2)
        }
        isCustomSeed = customSeed
        currentSeed = seed
        worldRng.reset(currentSeed)
        secretRng.reset(currentSeed)
        questSeed = 0
        bossFleetSeed = 0
    }

    /** `Global::IsSeededRun()` (custom seed and a started game). */
    val isSeededRun: Boolean get() = isCustomSeed

    /** GenerateLocationSeed(loc, seed). */
    @JvmStatic
    fun locationSeed(x: Int, y: Int, seed: Int): Int = ((((x + y) * (x + y + 1)) / 2 + y) xor seed) and 0x7fffffff

    /** The quest seed after NewGame / GetNewLocation: the location's seed with the sector seed and 0x282b2048. */
    fun questSeedFor(x: Int, y: Int): Int = ((((x + y) * (x + y + 1)) / 2 + y) xor currentSectorSeed xor 0x282b2048) and 0x7fffffff

    /** The sector seed before the last [nextSectorSeed]: the one StarMap::GetNewLocation sees on a sector jump. Not saved. */
    @JvmField var previousSectorSeed = 0

    /**
     * Seeds.cpp StarMap::GetNewLocation on a jump to the next sector (round 6): the hook runs when the jump starts,
     * with currentLoc already the map's dummyNewSector location (-500, -500) and currentSectorSeed still the sector
     * being left (GenerateMap runs after the jump), so the new sector's start beacon begins with that quest seed.
     * Nothing in Hyperspace sets it again until the next jump (the NewGame hook only runs at the start of a run).
     * A secret sector (StarMap::StartSecretSector) does not go through GetNewLocation: the quest seed carries over.
     */
    fun onSectorJump(secret: Boolean) {
        if (secret) return
        questSeed = ((((-500 + -500) * (-500 + -500 + 1)) / 2 + -500) xor previousSectorSeed xor 0x282b2048) and 0x7fffffff
    }

    /** GenerateMap (priority 1000): the boss fleet seed of a freshly generated sector. */
    fun onGenerateMap() {
        bossFleetSeed = (currentSectorSeed xor 0x46157fab) and 0x7fffffff
    }

    /**
     * GenerateMap (Seeds.cpp): the seed of the next sector, from worldRng (the secret sector's from secretRng and the
     * CRC-32 of its name). [row] is Sector::location.y.
     */
    fun nextSectorSeed(row: Int, secretName: String?): Int {
        previousSectorSeed = currentSectorSeed
        currentSectorSeed = if (secretName != null) {
            secretRng.next() xor crc32(secretName)
        } else {
            val s = worldRng.current + row
            secretRng.reset(s)
            s
        }
        return currentSectorSeed
    }

    /** AdvanceWorldLevel (Seeds.cpp): one worldRng draw per sector. */
    fun onAdvanceWorldLevel() {
        worldRng.next()
    }

    fun save(elem: Element) {
        elem.setAttribute("currentSeed", currentSeed.toString())
        elem.setAttribute("customSeed", isCustomSeed.toString())
        elem.setAttribute("sectorSeed", currentSectorSeed.toString())
        elem.setAttribute("questSeed", questSeed.toString())
        elem.setAttribute("bossFleetSeed", bossFleetSeed.toString())
        elem.setAttribute("worldRng", "${worldRng.seed},${worldRng.counter}")
        elem.setAttribute("secretRng", "${secretRng.seed},${secretRng.counter}")
    }

    fun load(elem: Element) {
        currentSeed = elem.getAttributeValue("currentSeed")?.toIntOrNull() ?: 0
        isCustomSeed = elem.getAttributeValue("customSeed") == "true"
        currentSectorSeed = elem.getAttributeValue("sectorSeed")?.toIntOrNull() ?: 0
        questSeed = elem.getAttributeValue("questSeed")?.toIntOrNull() ?: 0
        bossFleetSeed = elem.getAttributeValue("bossFleetSeed")?.toIntOrNull() ?: 0
        fun rng(r: HsSeededRng, v: String?) {
            val p = v?.split(',') ?: return
            r.restore(p[0].toInt(), p[1].toInt())
        }
        rng(worldRng, elem.getAttributeValue("worldRng"))
        rng(secretRng, elem.getAttributeValue("secretRng"))
    }

    /** boost::crc_32_type over the name's bytes. */
    fun crc32(text: String): Int {
        var crc = -1
        for (b in text.toByteArray(Charsets.UTF_8)) {
            crc = crc xor (b.toInt() and 0xff)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        }
        return crc.inv()
    }
}
