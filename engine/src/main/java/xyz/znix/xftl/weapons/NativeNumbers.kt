package xyz.znix.xftl.weapons

import org.jdom2.Element

/**
 * Numbers in weapon and drone blueprints, read the way FTL's blueprint loader reads them: integer fields with C's
 * atoi (the leading digits, so `1.25` is 1 and text is 0) and decimal fields with strtod (the leading number, 0 when
 * there is none). A field that is not there keeps the blueprint's default, so no blueprint is dropped for a decimal
 * in an integer field or a missing child.
 */
object NativeNumbers {
    @JvmStatic
    fun atoi(s: String): Int = xyz.znix.xftl.sector.atoiCompat(s)

    @JvmStatic
    fun atof(s: String): Float {
        var i = 0
        val n = s.length
        while (i < n && s[i].isWhitespace()) i++
        val start = i
        if (i < n && (s[i] == '+' || s[i] == '-')) i++
        var digits = 0
        while (i < n && s[i] in '0'..'9') { i++; digits++ }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') { i++; digits++ }
        }
        if (digits == 0) return 0f
        var end = i
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            if (j < n && (s[j] == '+' || s[j] == '-')) j++
            val expStart = j
            while (j < n && s[j] in '0'..'9') j++
            if (j > expStart) end = j
        }
        return s.substring(start, end).toDouble().toFloat()
    }
}

/** An integer child as FTL's loader reads it (atoi), or null when there is no such child. */
fun Element.nativeInt(name: String): Int? = getChildText(name)?.let { NativeNumbers.atoi(it) }

/** A decimal child as FTL's loader reads it (strtod), or null when there is no such child. */
fun Element.nativeFloat(name: String): Float? = getChildText(name)?.let { NativeNumbers.atof(it) }
