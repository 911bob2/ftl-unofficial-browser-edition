package ftl.browser

import kotlin.random.Random

/**
 * The generator the original game uses for everything it generates
 * (FTLGame.exe 1.6.14 `random32` 0x6569f0, seeder 0x656db0): a 64-bit linear
 * congruential generator,
 *
 *     state = state * 0x5851F42D4C957F2D + 1
 *     value = (int)(state >>> 33)          (31 bits, never negative)
 *
 * seeded with `state = (uint32) seed`. Every use is `value % n` on the signed
 * 31-bit value, so [nextInt] with a bound and [kotlin.ranges.IntRange.random]
 * are overridden to match instead of using Kotlin's rejection sampling.
 *
 * The original draws from one global stream, also used for animations, and
 * reseeds it at every generator (see AGENT_HANDOFF.md). This object is that
 * stream for map, event, ship and reward generation; combat and presentation
 * keep [BrowserRandom] and `VisualRandom`.
 */
object NativeRandom : Random() {
    private const val MULTIPLIER = 0x5851F42D4C957F2DL

    private var state: Long = (System.nanoTime() xor 0x1234ABCD5678L) and 0xFFFFFFFFL

    /** Diagnostics: every draw is recorded here while non-null (seeds as [Int.MIN_VALUE], seed). */
    @JvmField var trace: ArrayList<Int>? = null

    /**
     * Diagnostics (r8 resync, `BrowserHsFullRunDiagnostics`; [live] stays null and all of this is skipped in production):
     * the original draws from this one stream in real-time frames too (crew animation timers), so a draw taken after
     * unpaused frames without a reseed in between does not depend on the seeds alone. The harness bumps [liveFrames]
     * per unpaused frame; [live] is told about every reseed and draw.
     */
    interface Live {
        fun onSeed(seed: Int)
        fun onDraw(live: Boolean)
    }

    @JvmField var live: Live? = null
    @JvmField var liveFrames = 0L
    private var seededAtFrame = 0L

    /** `srand`: reseed the stream. */
    @JvmStatic fun seed(seed: Int) {
        state = seed.toLong() and 0xFFFFFFFFL
        trace?.let { it.add(Int.MIN_VALUE); it.add(seed) }
        seededAtFrame = liveFrames
        live?.onSeed(seed)
    }

    /** Test hook: draws come from this queue first (a native capture's values, see BrowserEngine.sceneReplay). */
    @JvmField var replay: IntArray? = null
    private var replayPos = 0
    @JvmStatic fun startReplay(values: IntArray?) { replay = values; replayPos = 0 }

    /** [block]'s draws leave no trace: the stream, the replay queue, [trace] and [live] are as before afterwards. */
    @JvmStatic fun <T> isolated(block: () -> T): T {
        val s = state; val r = replay; val p = replayPos; val t = trace; val l = live
        replay = null; trace = null; live = null
        try { return block() } finally { state = s; replay = r; replayPos = p; trace = t; live = l }
    }

    /** `random32()`: the next 31-bit value. */
    @JvmStatic fun next31(): Int {
        replay?.let { r -> if (replayPos < r.size) return r[replayPos++] }
        state = state * MULTIPLIER + 1L
        val value = (state ushr 33).toInt()
        trace?.add(value)
        live?.onDraw(liveFrames != seededAtFrame)
        return value
    }

    /** Diagnostics: the stream was set to a known state (the oracle's, at a box): later draws are not live until the next frame. */
    @JvmStatic fun markSynced() { seededAtFrame = liveFrames }

    /** Diagnostics: true when the next draw would follow unpaused frames since the last reseed or sync. */
    @JvmStatic fun isLive(): Boolean = liveFrames != seededAtFrame

    override fun nextBits(bitCount: Int): Int = if (bitCount <= 0) 0 else next31() ushr (31 - minOf(bitCount, 31))

    override fun nextInt(): Int = next31()

    /** `random32() % until`. */
    override fun nextInt(until: Int): Int {
        require(until > 0) { "NativeRandom bound must be positive: $until" }
        return next31() % until
    }

    /** `random32() % (until - from) + from`. */
    override fun nextInt(from: Int, until: Int): Int {
        require(until > from) { "NativeRandom range is empty: [$from, $until)" }
        return next31() % (until - from) + from
    }

    override fun nextBoolean(): Boolean = next31() % 2 == 1

    /** The full generator state, for saves. */
    @JvmStatic fun snapshot(): String = state.toString()

    @JvmStatic fun restore(value: String?) {
        if (value != null) state = value.toLong()
    }

    /** Test hook: the next sector map is generated from this layout seed (a native capture's), then it clears. */
    @JvmStatic var layoutSeedOverride: Int? = null

    /** Use in place of [seed] when a generator needs a throwaway seed (a fresh game or sector). */
    @JvmStatic fun freshSeed(): Int = BrowserRandom.nextInt() and Int.MAX_VALUE
}
