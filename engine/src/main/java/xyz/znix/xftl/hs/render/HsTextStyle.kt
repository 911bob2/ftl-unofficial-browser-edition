package xyz.znix.xftl.hs.render

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.rendering.Colour

/**
 * Hyperspace's customTextStyle (Utilities/CustomTextStyle.cpp, GPL; FTL-Hyperspace 1.23.2): inline colour markup
 * `[style[color:RRGGBB]]text[[/style]]` (an optional AA follows RRGGBB) inside any printed string, shortest valid text
 * being 31 characters. The original hooks freetype's easy_print family, caches a cell list per text and draws each
 * coloured cell with GL_SetColor; here [SILFontLoader] measures the markup-free text, wraps it with the markup
 * re-applied to each line, and draws each run in its colour.
 */
object HsTextStyle {
    /** A stretch of the markup-free text; [flag] is the flag's text (spaces removed) when it opened a style. */
    class Run(val text: String, val flag: String?, val colour: Colour?)

    /** CustomTextStyleManager::IsFlagExist, gated on the option. */
    @JvmStatic
    fun hasFlag(text: String): Boolean {
        if (text.length < 31 || !HS.active) return false
        return HS.cfg().customTextStyle && text.indexOf("[style[") >= 0
    }

    private fun hexCharToInt(ch: Char): Int = when (ch) {
        in 'A'..'F' -> ch - 'A' + 10
        in 'a'..'f' -> ch - 'a' + 10
        in '0'..'9' -> ch - '0'
        else -> 15 // undefined
    }

    /** CustomTextStyleCellPrimitive::ParseFlag. */
    private fun parseFlag(content: String): Colour? {
        if (content.length < 12 || !content.startsWith("color:")) return null
        val r = (hexCharToInt(content[6]) shl 4) or hexCharToInt(content[7])
        val g = (hexCharToInt(content[8]) shl 4) or hexCharToInt(content[9])
        val b = (hexCharToInt(content[10]) shl 4) or hexCharToInt(content[11])
        val a = if (content.length > 13) (hexCharToInt(content[12]) shl 4) or hexCharToInt(content[13]) else 255
        return Colour(r, g, b, a)
    }

    /**
     * CustomTextStyleManager::CreateCustomTextStyle step 1: split [text] into plain and flagged runs. A flag that
     * never ends loses the rest of the text, a style that never closes leaves it plain, as the original does.
     */
    @JvmStatic
    fun parse(text: String): List<Run> {
        val runs = ArrayList<Run>()
        val len = text.length
        var i = 0
        var plainStart = 0
        while (i < len) {
            if (text[i] != '[' || len - i < 9 || !text.startsWith("[style[", i)) {
                i++
                continue
            }
            val flagStart = i + 7
            var flagEnd = -1
            var j = flagStart
            while (j < len) {
                if (text[j] == ']' && len - j >= 3 && text[j + 1] == ']') {
                    flagEnd = j
                    break
                }
                j++
            }
            if (flagEnd < 0) {
                // The unterminated flag takes the rest of the text with it.
                if (i > plainStart) runs.add(Run(text.substring(plainStart, i), null, null))
                return runs
            }
            val bodyStart = flagEnd + 2
            var closeAt = -1
            j = bodyStart
            while (j < len) {
                if (text[j] == '[' && len - j >= 10 && text.startsWith("[[/style]]", j)) {
                    closeAt = j
                    break
                }
                j++
            }
            if (closeAt < 0) {
                // No close tag: the flag is dropped and its text stays plain.
                if (i > plainStart) runs.add(Run(text.substring(plainStart, i), null, null))
                runs.add(Run(text.substring(bodyStart), null, null))
                return runs
            }
            if (i > plainStart) runs.add(Run(text.substring(plainStart, i), null, null))
            val flag = text.substring(flagStart, flagEnd).replace(" ", "")
            if (closeAt > bodyStart) runs.add(Run(text.substring(bodyStart, closeAt), flag, parseFlag(flag)))
            i = closeAt + 10
            plainStart = i
        }
        if (plainStart < len) runs.add(Run(text.substring(plainStart), null, null))
        return runs
    }

    /** The text without its markup. */
    @JvmStatic
    fun strip(text: String): String {
        val sb = StringBuilder()
        for (r in parse(text)) sb.append(r.text)
        return sb.toString()
    }

    /**
     * Wrap [text] like the plain text, then give each line back the markup of the characters it holds (a style that
     * crosses a break is closed on one line and opened again on the next, as DivideSelfByLineBreaks does).
     */
    @JvmStatic
    fun wrap(font: SILFontLoader, text: String, maxWidth: Int): List<String> {
        val runs = parse(text)
        val plain = StringBuilder()
        val flagAt = ArrayList<String?>()
        for (r in runs) {
            plain.append(r.text)
            for (k in r.text.indices) flagAt.add(if (r.colour != null) r.flag else null)
        }
        val lines = font.wrapPlain(plain.toString(), maxWidth)

        val out = ArrayList<String>()
        var p = 0
        for (line in lines) {
            // The wrap drops the spaces it breaks at: skip ahead to where this line's text starts.
            while (p < plain.length && !plain.startsWith(line, p) && (plain[p] == ' ' || plain[p] == '\n' || plain[p] == '\t')) p++
            val sb = StringBuilder()
            var k = 0
            while (k < line.length) {
                val flag = flagAt.getOrNull(p + k)
                var e = k + 1
                while (e < line.length && flagAt.getOrNull(p + e) == flag) e++
                if (flag != null) sb.append("[style[").append(flag).append("]]")
                sb.append(line, k, e)
                if (flag != null) sb.append("[[/style]]")
                k = e
            }
            out.add(sb.toString())
            p += line.length
        }
        return out
    }
}
