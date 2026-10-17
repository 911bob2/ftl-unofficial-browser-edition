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
 * reseeds it at every generator. This object is that
 * stream for map, event, ship and reward generation; combat and presentation
 * keep [BrowserRandom] and `VisualRandom`.
 */
object NativeRandom : Random() {
    private const val MULTIPLIER = 0x5851F42D4C957F2DL

    private var state: Long = (System.nanoTime() xor 0x1234ABCD5678L) and 0xFFFFFFFFL

    /** Diagnostics: every draw is recorded here while non-null (seeds as [Int.MIN_VALUE], seed). */
    @JvmField var trace: ArrayList<Int>? = null

    /** `srand`: reseed the stream. */
    @JvmStatic fun seed(seed: Int) {
        state = seed.toLong() and 0xFFFFFFFFL
        trace?.let { it.add(Int.MIN_VALUE); it.add(seed) }
    }

    /** `random32()`: the next 31-bit value. */
    @JvmStatic fun next31(): Int {
        state = state * MULTIPLIER + 1L
        val value = (state ushr 33).toInt()
        trace?.add(value)
        return value
    }

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

    /** Use in place of [seed] when a generator needs a throwaway seed (a fresh game or sector). */
    @JvmStatic fun freshSeed(): Int = BrowserRandom.nextInt() and Int.MAX_VALUE
}
