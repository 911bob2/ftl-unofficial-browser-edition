package xyz.znix.xftl.hs.lua

import ftl.browser.BrowserFiles
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** rapidxml's node types (rapidxml::node_type). */
object XmlType { const val DOCUMENT = 0; const val ELEMENT = 1; const val DATA = 2; const val CDATA = 3 }

/** A rapidxml node or attribute. Not [LuaTyped]: SWIG pushes these with the static type of each call. */
open class XmlBase(@JvmField var name: String, @JvmField var value: String) { @JvmField var parent: XmlNode? = null }

class XmlAttribute(name: String, value: String) : XmlBase(name, value)

open class XmlNode(@JvmField val type: Int, name: String, value: String) : XmlBase(name, value) {
    @JvmField val children = ArrayList<XmlNode>()
    @JvmField val attributes = ArrayList<XmlAttribute>()
}

class XmlDocument : XmlNode(XmlType.DOCUMENT, "", "")

class XmlParseError(message: String) : RuntimeException(message)

/**
 * rapidxml 1.13 with parse_default, as HS's xml_document parses MV's files: element values are the first data
 * child, data keeps its whitespace (whitespace-only runs between tags make no data node), character and the five
 * named entities are expanded in data and attribute values, comments/declarations/doctype/processing instructions
 * are skipped, CDATA becomes a cdata node, closing tag names are not checked.
 */
class RapidXmlParser(private val s: String) {
    private var i = 0

    fun parse(doc: XmlDocument) {
        if (s.startsWith("﻿")) i = 1
        while (true) {
            skipWs()
            if (i >= s.length) break
            if (s[i] != '<') throw XmlParseError("expected <")
            i++
            parseNode()?.let { append(doc, it) }
        }
    }

    private fun append(p: XmlNode, c: XmlNode) { c.parent = p; p.children.add(c) }
    private fun ws(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t'
    private fun skipWs() { while (i < s.length && ws(s[i])) i++ }
    private fun at(k: Int): Char = if (k < s.length) s[k] else '\u0000'

    private fun skipPast(end: String, error: String) {
        val k = s.indexOf(end, i)
        if (k < 0) throw XmlParseError(error)
        i = k + end.length
    }

    private fun parseNode(): XmlNode? {
        when (at(i)) {
            '?' -> { i++; skipPast("?>", "unexpected end of data"); return null }
            '!' -> {
                if (at(i + 1) == '-' && at(i + 2) == '-') { i += 3; skipPast("-->", "unexpected end of data"); return null }
                if (s.startsWith("[CDATA[", i + 1)) {
                    i += 8
                    val k = s.indexOf("]]>", i)
                    if (k < 0) throw XmlParseError("unexpected end of data")
                    val n = XmlNode(XmlType.CDATA, "", s.substring(i, k)); i = k + 3; return n
                }
                if (s.startsWith("DOCTYPE", i + 1)) {
                    i += 8
                    var depth = 0
                    while (true) {
                        when (at(i)) {
                            '[' -> depth++
                            ']' -> depth--
                            '>' -> if (depth == 0) { i++; return null }
                            '\u0000' -> throw XmlParseError("unexpected end of data")
                        }
                        i++
                    }
                }
                i++
                while (at(i) != '>') { if (at(i) == '\u0000') throw XmlParseError("unexpected end of data"); i++ }
                i++
                return null
            }
            else -> return parseElement()
        }
    }

    private fun nameChar(c: Char) = !(ws(c) || c == '/' || c == '>' || c == '?' || c == '\u0000')
    private fun attrNameChar(c: Char) = !(ws(c) || c == '/' || c == '<' || c == '>' || c == '=' || c == '?' || c == '!' || c == '\u0000')

    private fun parseElement(): XmlNode {
        val start = i
        while (nameChar(at(i))) i++
        if (i == start) throw XmlParseError("expected element name")
        val node = XmlNode(XmlType.ELEMENT, s.substring(start, i), "")
        skipWs()
        while (attrNameChar(at(i))) {
            val ns = i
            i++
            while (attrNameChar(at(i))) i++
            val name = s.substring(ns, i)
            skipWs()
            if (at(i) != '=') throw XmlParseError("expected =")
            i++
            skipWs()
            val q = at(i)
            if (q != '\'' && q != '"') throw XmlParseError("expected ' or \"")
            i++
            val vs = i
            while (at(i) != q) { if (at(i) == '\u0000') throw XmlParseError("unexpected end of data"); i++ }
            val a = XmlAttribute(name, expand(s.substring(vs, i)))
            a.parent = node
            node.attributes.add(a)
            i++
            skipWs()
        }
        when {
            at(i) == '>' -> { i++; parseContents(node) }
            at(i) == '/' -> { i++; if (at(i) != '>') throw XmlParseError("expected >"); i++ }
            else -> throw XmlParseError("expected >")
        }
        return node
    }

    private fun parseContents(node: XmlNode) {
        while (true) {
            val contentsStart = i
            skipWs()
            when (at(i)) {
                '<' -> {
                    if (at(i + 1) == '/') {
                        i += 2
                        while (nameChar(at(i))) i++
                        skipWs()
                        if (at(i) != '>') throw XmlParseError("expected >")
                        i++
                        return
                    }
                    i++
                    parseNode()?.let { append(node, it) }
                }
                '\u0000' -> throw XmlParseError("unexpected end of data")
                else -> {
                    i = contentsStart
                    val k = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    val text = expand(s.substring(i, k))
                    i = k
                    append(node, XmlNode(XmlType.DATA, "", text))
                    if (node.value.isEmpty()) node.value = text
                }
            }
        }
    }

    /** rapidxml's character reference expansion: the five named entities and &#N; / &#xN; (others stay). */
    private fun expand(t: String): String {
        if (t.indexOf('&') < 0) return t
        val b = StringBuilder(t.length)
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c != '&') { b.append(c); k++; continue }
            fun named(n: String, ch: Char): Boolean = if (t.startsWith(n, k + 1)) { b.append(ch); k += n.length + 1; true } else false
            if (named("amp;", '&') || named("apos;", '\'') || named("quot;", '"') || named("lt;", '<') || named("gt;", '>')) continue
            if (at2(t, k + 1) == '#') {
                var p = k + 2
                val hex = at2(t, p) == 'x'
                if (hex) p++
                var code = 0L
                val ds = p
                while (p < t.length) {
                    val d = Character.digit(t[p], if (hex) 16 else 10)
                    if (d < 0) break
                    code = code * (if (hex) 16 else 10) + d; p++
                }
                if (at2(t, p) == ';' && p > ds) { b.appendCodePoint(code.toInt()); k = p + 1; continue }
                throw XmlParseError("expected ;")
            }
            b.append(c); k++
        }
        return b.toString()
    }

    private fun at2(t: String, k: Int): Char = if (k < t.length) t[k] else '\u0000'
}

/** RapidXML module members (xml_document, xml_node, xml_attribute, xml_base). */
object LuaXml {
    private fun load(doc: XmlDocument, path: String) {
        doc.children.clear(); doc.attributes.clear()
        if (!BrowserFiles.contains(path)) throw LuaError("rapidxml: file not found in resources: $path")
        try { RapidXmlParser(String(BrowserFiles.read(path), Charsets.UTF_8)).parse(doc) }
        catch (e: XmlParseError) { throw LuaError("rapidxml parse error in $path: ${e.message}") }
    }

    private fun parseString(doc: XmlDocument, text: String) {
        doc.children.clear(); doc.attributes.clear()
        try { RapidXmlParser(text).parse(doc) } catch (e: XmlParseError) { throw LuaError("rapidxml parse error: ${e.message}") }
    }

    private fun pushAs(o: Any?, type: Int): Int {
        if (o == null) { LuaJs.pushnil(); return 1 }
        LuaJs.pushobj(Handles.expose(o), type)
        return 1
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** rapidxml's name matching: `name` with `name_size` (0 = whole string), optionally ASCII case-insensitive. */
    private fun matcher(n: Int): ((String) -> Boolean)? {
        if (n < 2 || arg.isNil(2)) return null
        var name = arg.str(2) ?: return null
        if (n >= 3) { val size = arg.int(3); if (size > 0 && size < name.length) name = name.substring(0, size) }
        val cs = n < 4 || arg.bool(4)
        return { x -> if (cs) x == name else x.equals(name, ignoreCase = true) }
    }

    private fun sibling(node: XmlNode, step: Int, n: Int): XmlNode? {
        val p = node.parent ?: return null
        val m = matcher(n)
        var k = p.children.indexOf(node) + step
        while (k in p.children.indices) { val c = p.children[k]; if (m == null || m(c.name)) return c; k += step }
        return null
    }

    private fun child(node: XmlNode, last: Boolean, n: Int): XmlNode? {
        val m = matcher(n)
        val l = node.children
        val range = if (last) l.indices.reversed() else l.indices
        for (k in range) if (m == null || m(l[k].name)) return l[k]
        return null
    }

    private fun attribute(node: XmlNode, last: Boolean, n: Int): XmlAttribute? {
        val m = matcher(n)
        val l = node.attributes
        val range = if (last) l.indices.reversed() else l.indices
        for (k in range) if (m == null || m(l[k].name)) return l[k]
        return null
    }

    private fun nextAttribute(a: XmlAttribute, step: Int, n: Int): XmlAttribute? {
        val p = a.parent ?: return null
        val m = matcher(n)
        var k = p.attributes.indexOf(a) + step
        while (k in p.attributes.indices) { val c = p.attributes[k]; if (m == null || m(c.name)) return c; k += step }
        return null
    }

    private fun docOf(b: XmlBase): XmlDocument? {
        var x: XmlBase? = b
        while (x != null) { if (x is XmlDocument) return x; x = x.parent }
        return null
    }

    fun install() {
        LuaReg.method(A.M_new_xml_document__SWIG_0, LuaMethod { pushAs(XmlDocument(), A.T_xml_document) })
        LuaReg.method(A.M_new_xml_document__SWIG_1, LuaMethod { val d = XmlDocument(); load(d, arg.str(1) ?: ""); pushAs(d, A.T_xml_document) })
        LuaReg.method(A.M_xml_document_parse_file, LuaMethod { load(arg.obj(1) as XmlDocument, arg.str(2) ?: ""); 0 })
        LuaReg.method(A.M_xml_document_parse_string, LuaMethod { parseString(arg.obj(1) as XmlDocument, arg.str(2) ?: ""); 0 })
        LuaReg.method(A.M_xml_document_clear, LuaMethod { val d = arg.obj(1) as XmlDocument; d.children.clear(); d.attributes.clear(); 0 })
        // xml_base
        LuaReg.method(A.M_xml_base_name__SWIG_0, LuaMethod { ret.str((arg.obj(1) as XmlBase).name) })
        LuaReg.method(A.M_xml_base_value__SWIG_0, LuaMethod { ret.str((arg.obj(1) as XmlBase).value) })
        LuaReg.method(A.M_xml_base_name_size, LuaMethod { ret.num(bytes((arg.obj(1) as XmlBase).name)) })
        LuaReg.method(A.M_xml_base_value_size, LuaMethod { ret.num(bytes((arg.obj(1) as XmlBase).value)) })
        LuaReg.method(A.M_xml_base_parent, LuaMethod { pushAs((arg.obj(1) as XmlBase).parent, A.T_xml_node) })
        // xml_node
        LuaReg.method(A.M_xml_node_document, LuaMethod { pushAs(docOf(arg.obj(1) as XmlBase), A.T___xml_document) })
        LuaReg.method(A.M_xml_node_type__SWIG_0, LuaMethod { ret.num((arg.obj(1) as XmlNode).type) })
        for ((ids, f) in listOf(
            intArrayOf(A.M_xml_node_first_node__SWIG_3, A.M_xml_node_first_node__SWIG_2, A.M_xml_node_first_node__SWIG_1, A.M_xml_node_first_node__SWIG_0) to { n: Int -> pushAs(child(arg.obj(1) as XmlNode, false, n), A.T_xml_node) },
            intArrayOf(A.M_xml_node_last_node__SWIG_3, A.M_xml_node_last_node__SWIG_2, A.M_xml_node_last_node__SWIG_1, A.M_xml_node_last_node__SWIG_0) to { n: Int -> pushAs(child(arg.obj(1) as XmlNode, true, n), A.T_xml_node) },
            intArrayOf(A.M_xml_node_next_sibling__SWIG_3, A.M_xml_node_next_sibling__SWIG_2, A.M_xml_node_next_sibling__SWIG_1, A.M_xml_node_next_sibling__SWIG_0) to { n: Int -> pushAs(sibling(arg.obj(1) as XmlNode, 1, n), A.T_xml_node) },
            intArrayOf(A.M_xml_node_previous_sibling__SWIG_3, A.M_xml_node_previous_sibling__SWIG_2, A.M_xml_node_previous_sibling__SWIG_1, A.M_xml_node_previous_sibling__SWIG_0) to { n: Int -> pushAs(sibling(arg.obj(1) as XmlNode, -1, n), A.T_xml_node) },
            intArrayOf(A.M_xml_node_first_attribute__SWIG_3, A.M_xml_node_first_attribute__SWIG_2, A.M_xml_node_first_attribute__SWIG_1, A.M_xml_node_first_attribute__SWIG_0) to { n: Int -> pushAs(attribute(arg.obj(1) as XmlNode, false, n), A.T_xml_attribute) },
            intArrayOf(A.M_xml_node_last_attribute__SWIG_3, A.M_xml_node_last_attribute__SWIG_2, A.M_xml_node_last_attribute__SWIG_1, A.M_xml_node_last_attribute__SWIG_0) to { n: Int -> pushAs(attribute(arg.obj(1) as XmlNode, true, n), A.T_xml_attribute) },
            intArrayOf(A.M_xml_attribute_next_attribute__SWIG_3, A.M_xml_attribute_next_attribute__SWIG_2, A.M_xml_attribute_next_attribute__SWIG_1, A.M_xml_attribute_next_attribute__SWIG_0) to { n: Int -> pushAs(nextAttribute(arg.obj(1) as XmlAttribute, 1, n), A.T_xml_attribute) },
            intArrayOf(A.M_xml_attribute_previous_attribute__SWIG_3, A.M_xml_attribute_previous_attribute__SWIG_2, A.M_xml_attribute_previous_attribute__SWIG_1, A.M_xml_attribute_previous_attribute__SWIG_0) to { n: Int -> pushAs(nextAttribute(arg.obj(1) as XmlAttribute, -1, n), A.T_xml_attribute) },
        )) for (id in ids) LuaReg.method(id, LuaMethod { n -> f(n) })
        LuaReg.method(A.M_xml_attribute_document, LuaMethod { pushAs(docOf(arg.obj(1) as XmlBase), A.T___xml_document) })
    }
}
