package xyz.znix.xftl.hs

/**
 * `std::mt19937` (32-bit Mersenne Twister, default seeding `seed_seq`-free constructor), which Hyperspace uses for
 * its own streams: crew names (CrewNames.cpp), seeded runs (Seeds.cpp `SeededRng`, `Global::seededRng`).
 * [next] returns the full 32-bit output as an Int (use `ushr`/`toUInt` for unsigned arithmetic).
 */
class Mt19937(seed: Int) {
    private val mt = IntArray(N)
    private var index = N

    init {
        mt[0] = seed
        for (i in 1 until N) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
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

    /** `engine.discard(n)`. */
    fun discard(n: Int) {
        repeat(n) { next() }
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

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX = 0x9908b0df.toInt()
        const val UPPER = 0x80000000.toInt()
        const val LOWER = 0x7fffffff
    }
}
