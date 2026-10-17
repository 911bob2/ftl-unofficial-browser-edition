package ftl.browser
import kotlin.random.Random

/** Stable 48-bit LCG: persistent simulation RNG, separate from decorative draws. */
object BrowserRandom : Random() {
    private const val MASK = (1L shl 48) - 1
    private var state = (System.nanoTime() xor 0x5DEECE66DL) and MASK
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        state = (state * 0x5DEECE66DL + 11) and MASK
        return (state ushr (48 - bitCount)).toInt()
    }
    @JvmStatic fun snapshot(): String = state.toString()
    @JvmStatic fun restore(value: String?) { if (value != null) state = value.toLong() and MASK }
}
