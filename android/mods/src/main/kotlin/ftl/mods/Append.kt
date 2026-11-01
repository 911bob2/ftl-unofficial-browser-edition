package ftl.mods

import java.util.regex.Pattern

// Slipstream-style XML append scripts as ftlman 0.7.4 parses and applies them: the Kotlin twin of web/mods/append.mjs.
// Errors inside scripts are kept as Error nodes and fail only when executed, exactly as ftlman does.

class PatchError(message: String) : Exception(message)
private class Reported : Exception()

private const val REMOVE = "_FTLMAN_INTERNAL_REMOVE_MARKER"
private val MOD_NS = setOf("mod", "mod-append", "mod-prepend", "mod-overwrite")
private val FIND_NAMES = setOf("findName", "findLike", "findWithChildLike", "findComposite")
private const val INF = Long.MAX_VALUE

// ---- string filters --------------------------------------------------------------------------------------------

private val RUST_CLASS = Regex("""\\\\|\\d|\\D|\\w|\\W|\\s|\\S""")
private val RUST_CLASSES = mapOf(
    "\\d" to "\\p{Nd}", "\\D" to "\\P{Nd}",
    "\\w" to "[\\p{IsAlphabetic}\\p{M}\\p{Nd}\\p{Pc}\\p{IsJoin_Control}]",
    "\\W" to "[^\\p{IsAlphabetic}\\p{M}\\p{Nd}\\p{Pc}\\p{IsJoin_Control}]",
    "\\s" to "\\p{IsWhite_Space}", "\\S" to "\\P{IsWhite_Space}",
)

/** Rust `regex` pattern as a Java Pattern (leading (?flags), Unicode classes, `.` and `$` by \n only). */
fun rustRegex(pattern: String): Pattern {
    var flags = Pattern.UNIX_LINES
    var p = pattern
    val m = Regex("""^\(\?([imsx]+)\)""").find(p)
    if (m != null) {
        val f = m.groupValues[1]
        if ('x' in f) throw IllegalArgumentException("verbose regex unsupported")
        if ('i' in f) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('m' in f) flags = flags or Pattern.MULTILINE
        if ('s' in f) flags = flags or Pattern.DOTALL
        p = p.substring(m.value.length)
    }
    p = RUST_CLASS.replace(p) { RUST_CLASSES[it.value] ?: it.value }
    return Pattern.compile(p, flags)
}

class StringFilter(val fixed: String?, val re: Pattern?) {
    // regex.find(value) must be the leftmost-first match and cover the whole value.
    fun matches(value: String): Boolean {
        if (re == null) return value == fixed
        val m = re.matcher(value)
        return m.find() && m.start() == 0 && m.end() == value.length
    }
}

private fun stringFilter(value: String, regex: Boolean) = if (regex) StringFilter(null, rustRegex(value)) else StringFilter(value, null)

// ---- script model ----------------------------------------------------------------------------------------------

class Selector(val name: StringFilter?, val attrs: List<Pair<String, StringFilter>>, val value: StringFilter?) {
    fun matches(el: Element): Boolean {
        if (name != null && !name.matches(el.name)) return false
        for ((key, value) in attrs) {
            val v = el.attrs[key] ?: return false
            if (!value.matches(v)) return false
        }
        if (value != null && !value.matches(textTrim(el))) return false
        return true
    }
}

class Par(val complement: Boolean, val and: Boolean, val filters: List<Find>)

sealed class Filter {
    class Sel(val selector: Selector) : Filter()
    class WithChild(val name: StringFilter?, val child: Selector) : Filter()
    class Composite(val par: Par) : Filter()
}

class Find(val reverse: Boolean, val start: Long, val limit: Long, val panic: Boolean, val filter: Filter, val commands: List<Command>)

sealed class Command {
    class FindCmd(val find: Find) : Command()
    class SetAttributes(val values: List<Pair<String, String>>) : Command()
    class RemoveAttributes(val names: List<String>) : Command()
    class SetValue(val text: String) : Command()
    object RemoveTag : Command()
    class Prepend(val el: Element) : Command()
    class Append(val el: Element) : Command()
    class Overwrite(val el: Element) : Command()
    class InsertByFind(val find: Find, val addAnyway: Boolean, val before: List<Element>, val after: List<Element>) : Command()
    object Error : Command()
}

sealed class ScriptNode {
    class FindNode(val find: Find) : ScriptNode()
    class Content(val node: Node) : ScriptNode()
    object Error : ScriptNode()
}

// ---- parser ----------------------------------------------------------------------------------------------------

/** parser_get_attr!(.., type, default): a present but unparsable value is an error, surfaced lazily. */
private class Typed<T>(val ok: T?, val err: Boolean = false)

private sealed class TryFind {
    class Ok(val find: Find) : TryFind()
    object Unrecognized : TryFind()
    object Reported : TryFind()
}

/** The first mod:selector / mod:par of the enclosing find, when that find takes one. */
private class Slots(val wantsSelector: Boolean, val regex: Boolean, val wantsPar: Boolean) {
    var selectorSet = false
    var selector: Selector? = null
    var selectorError = false
    var parSet = false
    var par: Par? = null
}

private fun parseBool(v: String): Boolean? = if (v == "true") true else if (v == "false") false else null
private const val MAX_SAFE = 9007199254740991L
// Rust integer FromStr: optional '+' (and '-' for signed), ASCII digits only.
private fun parseUsize(v: String): Long? =
    if (Regex("^\\+?[0-9]+$").matches(v)) v.removePrefix("+").trimStart('0').let { if (it.length > 16) null else (it.ifEmpty { "0" }).toLong() }?.takeIf { it <= MAX_SAFE } else null
private fun parseIsize(v: String): Long? {
    if (!Regex("^[+-]?[0-9]+$").matches(v)) return null
    val neg = v.startsWith('-')
    val abs = parseUsize(v.trimStart('+', '-')) ?: return null
    return if (neg) -abs else abs
}

private class Parser(text: String) {
    val r = Reader(text, topLevelText = true, unmatchedClosing = true, unclosed = true)

    fun skipToEnd() {
        var depth = 1
        while (depth > 0) {
            val e = r.next() ?: break
            if (e.type == EventType.START) depth++ else if (e.type == EventType.END) depth--
        }
    }

    fun skipIfNonEmpty(ev: Event) { if (ev.type == EventType.START) skipToEnd() }

    fun attr(ev: Event, name: String): String? {
        var v: Attribute? = null
        for (a in attributes(ev.attrs)) if (a.name == name) v = a
        return v?.let { unescape(it.raw) }
    }

    fun <T> typed(ev: Event, name: String, parse: (String) -> T?, dflt: T): Typed<T> {
        val v = attr(ev, name) ?: return Typed(dflt)
        val r = parse(v) ?: return Typed(null, true)
        return Typed(r)
    }

    fun textTrim(): String {
        var depth = 1
        val result = StringBuilder()
        while (true) {
            val e = r.next() ?: break
            if (e.type == EventType.START) depth++
            else if (e.type == EventType.END) { if (depth == 1) break; depth-- }
            else if (e.type == EventType.TEXT && depth == 1) {
                val c = unescape(e.raw)
                result.append(if (result.isEmpty()) trimStart(c) else c)
            }
        }
        return trimEnd(result.toString())
    }

    fun element(ev: Event): Element {
        val el = createElement(ev)
        if (ev.type == EventType.START) buildUnder(r, el)
        return el
    }

    /** A selector, or null when it is invalid. */
    fun selector(ev: Event, regex: Boolean): Selector? {
        val attrs = ArrayList<Pair<String, StringFilter>>()
        var ok = true
        for (a in attributes(ev.attrs)) {
            try { attrs.add(a.name to stringFilter(unescape(a.raw), regex)) } catch (_: Exception) { ok = false }
        }
        var value: StringFilter? = null
        if (ev.type == EventType.START) {
            val text = textTrim()
            if (text != "") {
                try { value = stringFilter(text, regex) } catch (_: Exception) { return null }
            }
        }
        return if (ok) Selector(null, attrs, value) else null
    }

    /** A mod:par, or null when it is invalid. */
    fun par(ev: Event): Par? {
        val (complement, and) = when (attr(ev, "op")) {
            "AND" -> false to true
            "OR" -> false to false
            "NOR" -> true to false
            "NAND" -> true to true
            else -> { skipIfNonEmpty(ev); return null }
        }
        val filters = ArrayList<Find>()
        while (true) {
            val e = r.next()
            if (e == null || e.type == EventType.END) break
            if (!e.isTag) continue
            if (e.prefix == "mod" && e.name == "par") {
                val inner = par(e)
                if (inner == null) { skipIfNonEmpty(e); return null }
                filters.add(Find(false, 0, INF, false, Filter.Composite(inner), emptyList()))
            } else when (val f = tryFind(e)) {
                TryFind.Unrecognized -> { skipIfNonEmpty(e); return null }
                TryFind.Reported -> return null
                is TryFind.Ok -> filters.add(f.find)
            }
        }
        return Par(complement, and, filters)
    }

    fun insertByFind(ev: Event): Command {
        val addAnyway = typed(ev, "addAnyway", ::parseBool, true)
        var unknown = false
        var find: Find? = null
        val before = ArrayList<Element>()
        val after = ArrayList<Element>()
        while (true) {
            val e = r.next()
            if (e == null || e.type == EventType.END) break
            if (!e.isTag) continue
            when (val f = tryFind(e)) {
                TryFind.Reported -> throw Reported()
                is TryFind.Ok -> { find = f.find; continue }
                TryFind.Unrecognized -> {}
            }
            if (e.prefix == "mod-before" || e.prefix == "mod-after") {
                val el = element(e)
                el.prefix = null
                (if (e.prefix == "mod-before") before else after).add(el)
                continue
            }
            unknown = true
            skipIfNonEmpty(e)
        }
        if (find == null || (before.isEmpty() && after.isEmpty()) || unknown) return Command.Error
        if (addAnyway.err) throw Reported()
        return Command.InsertByFind(find, addAnyway.ok!!, before, after)
    }

    fun commands(slots: Slots?): List<Command> {
        val out = ArrayList<Command>()
        while (true) {
            val e = r.next()
            if (e == null || e.type == EventType.END) break
            if (!e.isTag) continue
            if (e.prefix == "mod") {
                when (val f = tryFind(e)) {
                    TryFind.Reported -> { out.add(Command.Error); continue }
                    is TryFind.Ok -> { out.add(Command.FindCmd(f.find)); continue }
                    TryFind.Unrecognized -> {}
                }
                when (e.name) {
                    "selector" -> {
                        if (slots != null && slots.wantsSelector && !slots.selectorSet) {
                            slots.selectorSet = true
                            slots.selector = selector(e, slots.regex)
                            slots.selectorError = slots.selector == null
                        } else skipIfNonEmpty(e)
                    }
                    "par" -> {
                        if (slots != null && slots.wantsPar && !slots.parSet) {
                            slots.parSet = true
                            slots.par = par(e)
                        } else skipIfNonEmpty(e)
                    }
                    "setAttributes" -> { skipIfNonEmpty(e); out.add(Command.SetAttributes(attributes(e.attrs).map { it.name to unescape(it.raw) })) }
                    "removeAttributes" -> { skipIfNonEmpty(e); out.add(Command.RemoveAttributes(attributes(e.attrs).map { it.name })) }
                    "setValue" -> out.add(Command.SetValue(if (e.type == EventType.START) textTrim() else ""))
                    "removeTag" -> { skipIfNonEmpty(e); out.add(Command.RemoveTag) }
                    "insertByFind" -> {
                        val cmd = try { insertByFind(e) } catch (_: Reported) { skipIfNonEmpty(e); Command.Error }
                        out.add(cmd)
                    }
                    else -> { skipIfNonEmpty(e); out.add(Command.Error) }
                }
                continue
            }
            if (e.prefix == "mod-prepend" || e.prefix == "mod-append" || e.prefix == "mod-overwrite") {
                val el = element(e)
                val prefix = e.prefix
                el.prefix = null
                out.add(when (prefix) { "mod-prepend" -> Command.Prepend(el); "mod-append" -> Command.Append(el); else -> Command.Overwrite(el) })
                continue
            }
            skipIfNonEmpty(e)
            out.add(Command.Error)
        }
        return out
    }

    fun tryFind(ev: Event): TryFind {
        if (ev.prefix != "mod" || ev.name !in FIND_NAMES) return TryFind.Unrecognized
        val kind = ev.name
        val reverse = typed(ev, "reverse", ::parseBool, kind == "findName")
        val start = typed(ev, "start", ::parseUsize, 0L)
        val limitRaw = typed(ev, "limit", ::parseIsize, if (kind == "findName") 1L else -1L)
        val limit: Typed<Long> = when {
            limitRaw.err -> Typed(null, true)
            limitRaw.ok!! >= 0 -> Typed(limitRaw.ok)
            limitRaw.ok == -1L -> Typed(INF)
            else -> Typed(null, true)
        }
        val panicValue = attr(ev, "panic")
        val panic = !(panicValue == null || panicValue == "false")
        var commands: List<Command> = emptyList()
        fun body(slots: Slots?) {
            if (ev.type == EventType.START) {
                commands = try { commands(slots) } catch (_: Reported) { skipToEnd(); listOf(Command.Error) }
            }
        }
        fun filterAttr(name: String, asRegex: Boolean): Typed<StringFilter?> =
            try { Typed(attr(ev, name)?.let { stringFilter(it, asRegex) }) } catch (_: Exception) { Typed(null, true) }

        val filter: Filter
        when (kind) {
            "findName" -> {
                val regex = typed(ev, "regex", ::parseBool, false)
                val asRegex = regex.ok ?: false
                val name: Typed<StringFilter?> = try {
                    val n = attr(ev, "name")
                    if (n == null) Typed(null, true) else Typed(stringFilter(n, asRegex))
                } catch (_: Exception) { Typed(null, true) }
                val type = filterAttr("type", asRegex)
                body(null)
                if (regex.err || type.err || name.err) return TryFind.Reported
                filter = Filter.Sel(Selector(type.ok, listOf("name" to name.ok!!), null))
            }
            "findLike", "findWithChildLike" -> {
                val regex = typed(ev, "regex", ::parseBool, false)
                val asRegex = regex.ok ?: false
                val type = filterAttr("type", asRegex)
                val childType = if (kind == "findWithChildLike") filterAttr("child-type", asRegex) else Typed(null)
                // findLike evaluates `attr_regex?` before reading its body: an invalid regex attribute leaves the body unread.
                if (kind == "findLike" && regex.err) return TryFind.Reported
                val slots = Slots(wantsSelector = true, regex = asRegex, wantsPar = false)
                body(slots)
                if (slots.selectorError) return TryFind.Reported
                val selector = slots.selector ?: Selector(null, emptyList(), null)
                if (kind == "findLike") {
                    if (type.err) return TryFind.Reported
                    filter = Filter.Sel(Selector(type.ok, selector.attrs, selector.value))
                } else {
                    if (type.err || childType.err) return TryFind.Reported
                    filter = Filter.WithChild(type.ok, Selector(childType.ok, selector.attrs, selector.value))
                }
            }
            else -> {
                val slots = Slots(wantsSelector = false, regex = false, wantsPar = true)
                body(slots)
                val par = slots.par ?: return TryFind.Reported
                filter = Filter.Composite(par)
            }
        }
        if (reverse.err || start.err || limit.err) return TryFind.Reported
        return TryFind.Ok(Find(reverse.ok!!, start.ok!!, limit.ok!!, panic, filter, commands))
    }
}

/** append::parse: the script nodes of an append document (already unwrapped). XML errors throw XmlError. */
fun parseScript(text: String): List<ScriptNode> {
    val p = Parser(text)
    val script = ArrayList<ScriptNode>()
    while (true) {
        val e = p.r.next() ?: return script
        if (e.type == EventType.END || e.type == EventType.DOCTYPE) continue
        if (e.isTag && e.prefix == "mod") {
            when (val f = p.tryFind(e)) {
                TryFind.Unrecognized -> { p.skipIfNonEmpty(e); script.add(ScriptNode.Error) }
                TryFind.Reported -> script.add(ScriptNode.Error)
                is TryFind.Ok -> script.add(ScriptNode.FindNode(f.find))
            }
            continue
        }
        when (e.type) {
            EventType.START, EventType.EMPTY -> script.add(ScriptNode.Content(p.element(e)))
            EventType.TEXT -> script.add(ScriptNode.Content(Text(unescape(e.raw))))
            EventType.CDATA -> script.add(ScriptNode.Content(CData(e.content)))
            EventType.COMMENT -> script.add(ScriptNode.Content(Comment(e.content)))
            else -> {}
        }
    }
}

// ---- patching --------------------------------------------------------------------------------------------------

private fun filterMatches(filter: Filter, el: Element): Boolean = when (filter) {
    is Filter.Sel -> filter.selector.matches(el)
    is Filter.WithChild -> (filter.name == null || filter.name.matches(el.name)) &&
        el.children.any { it is Element && filter.child.matches(it) }
    is Filter.Composite -> throw IllegalStateException()
}

private fun findRaw(elements: List<Element>, find: Find): List<Element> {
    var result: MutableList<Element>
    val f = find.filter
    if (f is Filter.Composite) {
        val par = f.par
        // An empty mod:par returns before reverse/start/limit/panic are applied.
        if (par.filters.isEmpty()) return if (par.complement) elements.toMutableList() else mutableListOf()
        var set: MutableSet<Element> = LinkedHashSet(findRaw(elements, par.filters[0]))
        for (child in par.filters.drop(1)) {
            val r = findRaw(elements, child)
            if (par.and) {
                val c = r.toSet()
                set = set.filterTo(LinkedHashSet()) { it in c }
            } else set.addAll(r)
        }
        result = if (par.complement) elements.filterTo(ArrayList()) { it !in set } else elements.filterTo(ArrayList()) { it in set }
    } else {
        result = elements.filterTo(ArrayList()) { filterMatches(f, it) }
    }
    if (find.reverse) result.reverse()
    // result.truncate(start + limit) with usize wrap-around: limit -1 is usize::MAX, so start > 0 truncates to start - 1.
    val end = if (find.limit == INF) (if (find.start == 0L) INF else find.start - 1) else find.start + find.limit
    if (result.size > end) result = result.subList(0, end.toInt()).toMutableList()
    result = result.subList(minOf(find.start, result.size.toLong()).toInt(), result.size).toMutableList()
    if (find.panic && result.isEmpty()) throw PatchError("find tag panicked: it did not match any elements")
    return result
}

private fun findIn(context: Element, find: Find) =
    findRaw(context.children.filterIsInstance<Element>().filter { it.prefix != REMOVE }, find)

private fun runCommands(context: Element, commands: List<Command>) {
    for (c in commands) when (c) {
        is Command.FindCmd -> for (m in findIn(context, c.find)) runCommands(m, c.find.commands)
        is Command.SetAttributes -> for ((k, v) in c.values) context.attrs[k] = v
        is Command.RemoveAttributes -> for (k in c.names) context.attrs.remove(k)
        is Command.SetValue -> context.children = mutableListOf(Text(c.text))
        Command.RemoveTag -> context.prefix = REMOVE
        is Command.Prepend -> context.children.add(0, (cloneNode(c.el) as Element).also { it.prefix = null })
        is Command.Append -> context.children.add((cloneNode(c.el) as Element).also { it.prefix = null })
        is Command.Overwrite -> {
            val n = (cloneNode(c.el) as Element).also { it.prefix = null }
            val i = context.children.indexOfFirst { it is Element && it.name == n.name }
            if (i >= 0) context.children[i] = n else context.children.add(n)
        }
        is Command.InsertByFind -> {
            val results = findIn(context, c.find)
            val b = c.before.map { cloneNode(it) }
            val a = c.after.map { cloneNode(it) }
            if (results.isEmpty()) {
                if (c.addAnyway) {
                    context.children.addAll(0, b)
                    context.children.addAll(a)
                }
            } else {
                val first = context.children.indexOf(results[0])
                val last = context.children.indexOf(results[results.size - 1])
                context.children.addAll(first, b)
                context.children.addAll(last + b.size + 1, a)
            }
        }
        Command.Error -> throw PatchError("invalid mod command reached")
    }
}

private fun cleanup(el: Element) {
    if (el.prefix != null && el.prefix in MOD_NS) el.prefix = null
    val kept = ArrayList<Node>()
    for (c in el.children) {
        if (c is Element) {
            if (c.prefix == REMOVE) continue
            cleanup(c)
            kept.add(c)
        } else if (c !is Comment) kept.add(c)
    }
    el.children = kept
}

/** append::patch on the root element. */
fun patch(root: Element, script: List<ScriptNode>) {
    for (node in script) when (node) {
        is ScriptNode.FindNode -> for (m in findIn(root, node.find)) runCommands(m, node.find.commands)
        is ScriptNode.Content -> {
            val n = cloneNode(node.node)
            if (n is Element) cleanup(n)
            root.children.add(n)
        }
        ScriptNode.Error -> throw PatchError("invalid append script node reached")
    }
    cleanup(root)
}
