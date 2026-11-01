package xyz.znix.xftl.hs.stat

import org.jdom2.Element
import xyz.znix.xftl.hs.HsConfig

/** `boost::bad_lexical_cast`: HS's parsers stop the current item when a number does not convert. */
class HsBadCast(text: String) : RuntimeException("bad lexical cast: source type value could not be interpreted as target ('$text')")

/** `std::invalid_argument` thrown by HS's stat boost parser; it aborts the whole `<crew>` node (outer catch). */
class HsInvalidArgument(message: String) : RuntimeException(message)

/**
 * The value conversions HS's XML readers use: `boost::lexical_cast` (no surrounding whitespace, whole string) and
 * `EventsParser::ParseBoolean`, applied to rapidxml's `value()` (the element's text, untrimmed).
 */
object HsParse {
    private val FLOAT = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val INT = Regex("[+-]?\\d+")

    @JvmStatic
    fun float(s: String?): Float {
        val t = s ?: throw HsBadCast("")
        if (FLOAT.matches(t)) return t.toFloat()
        when (t.lowercase()) {
            "inf", "+inf", "infinity", "+infinity" -> return Float.POSITIVE_INFINITY
            "-inf", "-infinity" -> return Float.NEGATIVE_INFINITY
            "nan", "+nan", "-nan" -> return Float.NaN
        }
        throw HsBadCast(t)
    }

    @JvmStatic
    fun int(s: String?): Int {
        val t = s ?: throw HsBadCast("")
        if (!INT.matches(t)) throw HsBadCast(t)
        return t.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: throw HsBadCast(t)
    }

    @JvmStatic
    fun bool(s: String?): Boolean = HsConfig.bool(s)

    /** rapidxml `value()`: the element's own text (character data), untrimmed. */
    @JvmStatic
    fun value(e: Element): String = e.text

    @JvmStatic
    fun attr(e: Element, name: String): String? = e.getAttributeValue(name)
}
