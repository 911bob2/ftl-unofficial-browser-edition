package xyz.znix.xftl

import org.jdom2.Element
import kotlin.random.Random

/**
 * A ship event's surrender/escape range: the native RandomAmount (EventsParser::PullMinMax 0x5e1cd0 reads
 * min/max/chance, RandomAmount::Roll 0x5caf40 rolls it). [chance] defaults to -1 (no chance roll).
 */
class RandomWithChance(elem: Element) {
    val min: Int = elem.getAttributeValue("min").toInt()
    val max: Int = elem.getAttributeValue("max").toInt()

    /**
     * The chance that a zero will be rolled instead of a number between min and max.
     */
    val zeroChance: Float = elem.getAttributeValue("chance")?.toFloat() ?: -1f

    /**
     * RandomAmount::Roll (0x5caf40): with a positive chance, `random32() % 100` below `100 * chance` gives 0;
     * otherwise min, plus `random32() % (max + 1 - min)` when max is not zero (a zero max skips the draw).
     */
    fun pick(rand: Random): Int {
        if (zeroChance > 0f) {
            if (rand.nextInt(100).toFloat() < 100f * zeroChance)
                return 0
        }

        if (max == 0)
            return min

        val span = max + 1 - min
        if (span <= 0)
            return min
        return min + rand.nextInt(span)
    }
}
