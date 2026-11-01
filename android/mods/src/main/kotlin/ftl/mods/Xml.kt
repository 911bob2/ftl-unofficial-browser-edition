package ftl.mods

// FTL's sloppy XML as ftlman 0.7.4 reads and writes it: the Kotlin twin of web/mods/xml.mjs (a port of speedy-xml
// 0.1.1 and ftlman's xmltree builder/emitter), bug-for-bug where it changes output (see docs/MODS.md).

class XmlError(message: String) : Exception(message)

private const val NONE = '\uffff'

internal fun isWs(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t'
private fun table(chars: String) = BooleanArray(128).also { for (c in chars) it[c.code] = true }
private val INVALID_NAME = table(" \n\r\t/>?\u0000:")
private val INVALID_ATTR = table(" \n\r\t/<>=?!\u0000:")
internal fun invalidName(c: Char) = c.code < 128 && INVALID_NAME[c.code]
internal fun invalidAttr(c: Char) = c.code < 128 && INVALID_ATTR[c.code]

// ---- escaping -------------------------------------------------------------------------------------------------

private fun resolveEntity(s: String, from: Int): Pair<String, Int>? {
    fun at(k: Int) = if (k < s.length) s[k] else NONE
    var i = from
    val ch: String
    when (at(i)) {
        'l' -> { if (at(i + 1) != 't') return null; ch = "<"; i += 2 }
        'g' -> { if (at(i + 1) != 't') return null; ch = ">"; i += 2 }
        'a' -> when (at(i + 1)) {
            'p' -> { if (at(i + 2) != 'o' || at(i + 3) != 's') return null; ch = "'"; i += 4 }
            'm' -> { if (at(i + 2) != 'p') return null; ch = "&"; i += 3 }
            else -> return null
        }
        'q' -> { if (at(i + 1) != 'u' || at(i + 2) != 'o' || at(i + 3) != 't') return null; ch = "\""; i += 4 }
        '#' -> {
            var code = 0L
            var radix = 10
            i++
            if (i >= s.length) return null
            if (s[i] == 'x') { radix = 16; i++ }
            while (true) {
                if (i >= s.length) return null
                val d = s[i]
                if (d == ';') break
                if (d !in '0'..'9' && d !in 'a'..'z' && d !in 'A'..'Z') return null
                val v = Character.digit(d, radix)
                if (v < 0) return null
                code = (code * radix + v) and 0xffffffffL
                i++
            }
            if (code > 0x10ffff || code in 0xd800..0xdfff) return null
            return String(Character.toChars(code.toInt())) to i + 1
        }
        else -> return null
    }
    if (at(i) != ';') return null
    return ch to i + 1
}

/** speedy_xml::escape::unescape, including its loss of text before an unresolved entity. */
fun unescape(string: String): String {
    val replaced = StringBuilder()
    var cur = 0
    while (true) {
        val a = string.indexOf('&', cur)
        val z = string.indexOf('\u0000', cur)
        val next = if (a < 0) z else if (z < 0) a else minOf(a, z)
        if (next < 0) break
        if (string[next] == '&') {
            val r = resolveEntity(string, next + 1)
            if (r != null) {
                replaced.append(string, cur, next)
                if (r.first == "\u0000") return replaced.toString()
                replaced.append(r.first)
                cur = r.second
            } else cur += 1
        } else {
            if (replaced.isEmpty()) return string
            return replaced.toString() + string.substring(cur, next)
        }
    }
    return if (replaced.isEmpty()) string else replaced.toString() + string.substring(cur)
}

fun contentEscape(s: String): String =
    if (s.indexOf('<') < 0 && s.indexOf('&') < 0) s else s.replace("&", "&amp;").replace("<", "&lt;")

fun commentEscape(s: String): String = if (s.indexOf('>') < 0) s else s.replace(">", "&gt;")

// ---- reader ---------------------------------------------------------------------------------------------------

enum class EventType { START, EMPTY, END, TEXT, CDATA, COMMENT, DOCTYPE }

/** START/EMPTY: prefix, name, attrs (raw text after the name), raw. END: prefix, name. TEXT: raw. Others: content, raw. */
class Event(
    val type: EventType,
    val prefix: String? = null,
    val name: String = "",
    val attrs: String = "",
    val raw: String = "",
    val content: String = "",
) {
    val isTag get() = type == EventType.START || type == EventType.EMPTY
}

class Reader(
    private val t: String,
    private val topLevelText: Boolean = false,
    private val unmatchedClosing: Boolean = false,
    private val unclosed: Boolean = false,
) {
    private var cur = 0
    private var depth = 0
    private var nul = -2

    private fun at(i: Int) = if (i < t.length) t[i] else NONE

    // Next NUL at or after `from`, cached: documents rarely contain one and rescanning per attribute is quadratic.
    private fun nextNul(from: Int): Int {
        if (nul == -2 || (nul != -1 && nul < from)) nul = t.indexOf('\u0000', from)
        return nul
    }

    private fun fail(message: String): Nothing {
        cur = t.length
        depth = 0
        throw XmlError(message)
    }

    private inline fun posOrEnd(start: Int, pred: (Char) -> Boolean): Int {
        for (i in start until t.length) if (pred(t[i])) return i
        return t.length
    }

    private fun skipWs() {
        var i = cur
        while (i < t.length && isWs(t[i])) i++
        cur = i
    }

    private fun skipAttributes() {
        while (true) {
            skipWs()
            val nameEnd = posOrEnd(cur, ::invalidAttr)
            if (nameEnd == cur) return
            cur = nameEnd
            skipWs()
            if (at(cur) != '=') fail("expected `=` after attribute name")
            cur++
            skipWs()
            val quote = at(cur)
            if (quote != '"' && quote != '\'') fail("expected an attribute value enclosed in either `'` or `\"`")
            cur++
            val q = t.indexOf(quote, cur)
            val z = nextNul(cur)
            val end = if (q < 0) z else if (z < 0) q else minOf(q, z)
            if (end < 0) fail("unclosed attribute value")
            if (t[end] == '\u0000') fail("attribute value contains null byte")
            cur = end + 1
        }
    }

    private fun skipDoctype() {
        while (true) {
            val g = t.indexOf('>', cur)
            val b = t.indexOf('[', cur)
            val idx = if (g < 0) b else if (b < 0) g else minOf(g, b)
            if (idx < 0) fail("unexpected end of file in <!DOCTYPE")
            if (t[idx] == '[') {
                cur = idx + 1
                var d = 1
                while (d > 0) {
                    val o = t.indexOf('[', cur)
                    val c = t.indexOf(']', cur)
                    val j = if (o < 0) c else if (c < 0) o else minOf(o, c)
                    if (j < 0) fail("unexpected end of file in <!DOCTYPE")
                    d += if (t[j] == '[') 1 else -1
                    cur = j + 1
                }
            } else {
                cur = idx + 1
                return
            }
        }
    }

    private fun prefixedName(start: Int, prefixEndDefault: Int): Pair<Int, Int> {
        val firstEnd = posOrEnd(cur, ::invalidName)
        if (firstEnd == cur) fail("expected element name")
        cur = firstEnd
        if (at(cur) == ':') {
            val secondEnd = posOrEnd(cur + 1, ::invalidName)
            cur = secondEnd
            return firstEnd to secondEnd
        }
        return (start + prefixEndDefault) to firstEnd
    }

    private fun parseNode(): Event? {
        val start = cur
        cur++
        if (cur >= t.length) fail("invalid element name")
        val c = t[cur]
        if (c == '?') {
            val end = t.indexOf("?>", cur)
            if (end < 0) fail("unclosed processing instruction")
            cur = end + 2
            return null
        }
        if (c == '!') {
            val n = at(cur + 1)
            if (n == '-' && at(cur + 2) == '-') {
                cur += 2
                val end = t.indexOf("-->", cur)
                if (end < 0) fail("unclosed comment")
                cur = end + 3
                val raw = t.substring(start, cur)
                return Event(EventType.COMMENT, raw = raw, content = if (raw.length >= 7) raw.substring(4, raw.length - 3) else "")
            }
            if (n == '[' && t.startsWith("CDATA[", cur + 2)) {
                cur += 8
                val end = t.indexOf("]]>", cur)
                if (end < 0) fail("unclosed cdata")
                cur = end + 3
                val raw = t.substring(start, cur)
                return Event(EventType.CDATA, raw = raw, content = raw.substring(9, raw.length - 3))
            }
            if (n == 'D' && t.startsWith("OCTYPE", cur + 2) && cur + 8 < t.length && isWs(t[cur + 8])) {
                cur += 9
                skipDoctype()
                val raw = t.substring(start, cur)
                return Event(EventType.DOCTYPE, raw = raw, content = raw.substring(10, raw.length - 1))
            }
            val end = t.indexOf('>', cur + 1)
            if (end < 0) fail("unclosed unknown <! tag")
            cur = end + 1
            return null
        }
        if (c == '/' && (depth > 0 || unmatchedClosing)) {
            cur++
            val (prefixEnd, nameEnd) = prefixedName(start, 1)
            skipWs()
            if (at(cur) != '>') fail("expected a `>`")
            depth = maxOf(0, depth - 1)
            cur++
            return Event(EventType.END, prefix = if (prefixEnd != start + 1) t.substring(start + 2, prefixEnd) else null,
                name = t.substring(prefixEnd + 1, nameEnd))
        }
        val (prefixEnd, nameEnd) = prefixedName(start, 0)
        skipAttributes()
        skipWs()
        val d = at(cur)
        val prefix = if (prefixEnd > start) t.substring(start + 1, prefixEnd) else null
        val name = t.substring(prefixEnd + 1, nameEnd)
        if (d == '>') {
            cur++
            depth++
            return Event(EventType.START, prefix, name, t.substring(nameEnd, cur), t.substring(start, cur))
        }
        if (d == '/') {
            if (at(cur + 1) != '>') fail("expected a `>`")
            cur += 2
            return Event(EventType.EMPTY, prefix, name, t.substring(nameEnd, cur), t.substring(start, cur))
        }
        fail("expected a `>` or `/`")
    }

    fun next(): Event? {
        while (true) {
            if (cur < t.length) {
                if (t[cur] == '<') {
                    val e = parseNode()
                    if (e != null) return e
                    continue
                }
                var end = t.indexOf('<', cur)
                if (end < 0) end = t.length
                val from = cur
                cur = end
                if (depth == 0 && !topLevelText) {
                    for (i in from until end) if (!isWs(t[i])) { cur = from; fail("top-level text is forbidden") }
                    continue
                }
                return Event(EventType.TEXT, raw = t.substring(from, end))
            }
            if (depth > 0) {
                if (unclosed) return null
                depth = 0
                throw XmlError("unclosed element")
            }
            return null
        }
    }
}

class Attribute(val name: String, val raw: String, val quote: Char)

/** speedy_xml Attributes iterator over an already validated start tag, in source order. */
fun attributes(t: String): List<Attribute> {
    val out = ArrayList<Attribute>()
    var cur = 0
    while (true) {
        while (cur < t.length && isWs(t[cur])) cur++
        var nameEnd = cur
        while (nameEnd < t.length && !invalidAttr(t[nameEnd])) nameEnd++
        if (nameEnd == cur) return out
        val name = t.substring(cur, nameEnd)
        cur = nameEnd
        while (cur < t.length && isWs(t[cur])) cur++
        cur++
        while (cur < t.length && isWs(t[cur])) cur++
        val quote = t[cur]
        cur++
        val end = t.indexOf(quote, cur)
        out.add(Attribute(name, t.substring(cur, end), quote))
        cur = end + 1
    }
}

// ---- writer ---------------------------------------------------------------------------------------------------

class Writer {
    private val out = StringBuilder()
    private var inTag = false
    private var empty = false

    private fun close() {
        if (inTag) {
            out.append(if (empty) "/>" else ">")
            inTag = false
        }
    }

    private fun checkName(name: String, what: String) {
        for (c in name) if (invalidName(c)) throw XmlError("invalid element $what")
    }

    private fun tag(prefix: String?, name: String) = if (prefix != null) "$prefix:$name" else name

    fun start(prefix: String?, name: String) {
        if (prefix != null) checkName(prefix, "prefix")
        checkName(name, "name")
        close()
        out.append('<').append(tag(prefix, name))
        inTag = true
        empty = false
    }

    fun emptyTag(prefix: String?, name: String) {
        checkName(name, "name")
        close()
        out.append('<').append(tag(prefix, name))
        inTag = true
        empty = true
    }

    fun attribute(name: String, value: String) {
        if (!inTag) throw XmlError("attributes are only allowed inside tags")
        for (c in name) if (invalidAttr(c)) throw XmlError("invalid attribute name")
        if (name.contains('\u0000') || name.contains('"')) throw XmlError("invalid attribute value")
        out.append(' ').append(name).append("=\"").append(contentEscape(value)).append('"')
    }

    fun rawAttribute(a: Attribute) {
        if (!inTag) throw XmlError("attributes are only allowed inside tags")
        out.append(' ').append(a.name).append('=').append(a.quote).append(a.raw).append(a.quote)
    }

    fun end(prefix: String?, name: String) {
        if (prefix != null) checkName(prefix, "prefix")
        checkName(name, "name")
        close()
        out.append("</").append(tag(prefix, name)).append('>')
    }

    fun text(s: String) { close(); out.append(contentEscape(s)) }

    fun cdata(s: String) {
        if (s.contains("]]>")) throw XmlError("cdata content cannot contain `]]>`")
        close()
        out.append("<![CDATA[").append(s).append("]]>")
    }

    fun comment(s: String) { close(); out.append("<!--").append(commentEscape(s)).append("-->") }
    fun raw(s: String) { close(); out.append(s) }
    fun finish(): String { close(); return out.toString() }
}

// ---- tree (ftlman xmltree) -------------------------------------------------------------------------------------

/** Element (attrs written sorted) or a Text, CData or Comment node. Elements compare by identity. */
sealed class Node
class Text(val text: String) : Node()
class CData(val text: String) : Node()
class Comment(val text: String) : Node()
class Element(var prefix: String?, val name: String, val attrs: LinkedHashMap<String, String>, var children: MutableList<Node>) : Node()

fun createElement(ev: Event): Element {
    val attrs = LinkedHashMap<String, String>()
    for (a in attributes(ev.attrs)) attrs[a.name] = unescape(a.raw)
    return Element(ev.prefix, ev.name, attrs, ArrayList())
}

private fun toNode(reader: Reader, ev: Event): Node? = when (ev.type) {
    EventType.START -> createElement(ev).also { buildUnder(reader, it) }
    EventType.EMPTY -> createElement(ev)
    EventType.TEXT -> Text(unescape(ev.raw))
    EventType.CDATA -> CData(ev.content)
    EventType.COMMENT -> Comment(ev.content)
    else -> null
}

fun buildUnder(reader: Reader, element: Element) {
    while (true) {
        val ev = reader.next()
        if (ev == null || ev.type == EventType.END) return
        toNode(reader, ev)?.let { element.children.add(it) }
    }
}

/** parse_all_with_options: every top-level node (unmatched end tags and doctypes ignored). */
fun parseAll(text: String, reader: Reader = Reader(text)): List<Node> {
    val out = ArrayList<Node>()
    while (true) {
        val ev = reader.next() ?: return out
        if (ev.type == EventType.END) continue
        toNode(reader, ev)?.let { out.add(it) }
    }
}

/** First element of the document, or null. */
fun parseElement(text: String): Element? = parseAll(text).firstOrNull { it is Element } as Element?

fun writeElement(w: Writer, el: Element) {
    val empty = el.children.isEmpty()
    if (empty) w.emptyTag(el.prefix, el.name) else w.start(el.prefix, el.name)
    for (key in el.attrs.keys.sorted()) w.attribute(key, el.attrs.getValue(key))
    if (!empty) {
        writeChildren(w, el)
        w.end(el.prefix, el.name)
    }
}

fun writeChildren(w: Writer, el: Element) {
    for (n in el.children) when (n) {
        is Element -> writeElement(w, n)
        is Text -> w.text(n.text)
        is CData -> w.cdata(n.text)
        is Comment -> w.comment(n.text)
    }
}

fun cloneNode(n: Node): Node =
    if (n is Element) Element(n.prefix, n.name, LinkedHashMap(n.attrs), n.children.mapTo(ArrayList()) { cloneNode(it) }) else n

/** Element::get_text_trim: Text children only (not CDATA), Rust whitespace trimming. */
fun textTrim(el: Element): String {
    val result = StringBuilder()
    for (n in el.children) if (n is Text) result.append(if (result.isEmpty()) trimStart(n.text) else n.text)
    return trimEnd(result.toString())
}

// Rust char::is_whitespace (Unicode White_Space): U+0085 counts, U+FEFF does not.
private fun rustWs(c: Char) = c in '\t'..'\r' || c == ' ' || c == '\u0085' || c == '\u00a0' || c == '\u1680' ||
    c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'

fun trimStart(s: String): String = s.trimStart(::rustWs)
fun trimEnd(s: String): String = s.trimEnd(::rustWs)
