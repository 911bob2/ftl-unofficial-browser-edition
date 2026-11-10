package ftl.mods

/**
 * What a mod set's Lua scripts use of io, os, debug and package/require (sweep M1b, docs/HS_LUA.md "Files, programs and
 * the sandbox"), found before the game starts so the player can say Run or Don't run. The same rules as the website's
 * web/mods/lua-scan.mjs (cases: tests/hs-lua/lua-scan-cases.json): names read as Lua reads them (comments and strings
 * skipped), `io.open`, `io["open"]`, a library kept in a variable, `_G.io`, `_G["io"]`. Names built at run time are not
 * seen; those calls still run in the sandbox. os.clock, os.time, os.date, os.difftime, io.write, io.read and io.type are
 * not asked about.
 */
object LuaScan {
    private val LIBS = setOf("io", "os", "debug", "package")
    private val QUIET = setOf("os.clock", "os.time", "os.date", "os.difftime", "os.setlocale", "io.write", "io.read", "io.type",
        "io.stdout", "io.stderr", "io.stdin")

    data class Tok(val t: Char, val v: String)   // 'n' name, 's' string, '#' number, 'o' operator

    fun tokens(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        fun longOpen(j: Int): Int {
            if (j >= n || src[j] != '[') return -1
            var k = j + 1; var eq = 0
            while (k < n && src[k] == '=') { eq++; k++ }
            return if (k < n && src[k] == '[') eq else -1
        }
        fun longEnd(j: Int, eq: Int): Int {
            val close = "]" + "=".repeat(eq) + "]"
            val e = src.indexOf(close, j)
            return if (e < 0) n else e + close.length
        }
        fun nameStart(c: Char) = c == '_' || c in 'A'..'Z' || c in 'a'..'z'
        fun namePart(c: Char) = nameStart(c) || c in '0'..'9'
        while (i < n) {
            val c = src[i]
            if (c == '-' && i + 1 < n && src[i + 1] == '-') {
                val eq = longOpen(i + 2)
                i = if (eq >= 0) longEnd(i + 4 + eq, eq) else src.indexOf('\n', i).let { if (it < 0) n else it + 1 }
                continue
            }
            if (c.isWhitespace()) { i++; continue }
            if (nameStart(c)) { var j = i + 1; while (j < n && namePart(src[j])) j++; out.add(Tok('n', src.substring(i, j))); i = j; continue }
            if (c == '"' || c == '\'') {
                var j = i + 1; val v = StringBuilder()
                while (j < n && src[j] != c && src[j] != '\n') {
                    if (src[j] == '\\') { if (j + 1 < n) v.append(src[j + 1]); j += 2 } else v.append(src[j++])
                }
                out.add(Tok('s', v.toString())); i = j + 1; continue
            }
            if (c == '[') {
                val eq = longOpen(i)
                if (eq >= 0) { val s = i + 2 + eq; val e = longEnd(s, eq); out.add(Tok('s', src.substring(s, maxOf(s, e - 2 - eq)))); i = e; continue }
            }
            if (c in '0'..'9') { var j = i + 1; while (j < n && (namePart(src[j]) || src[j] == '.')) j++; out.add(Tok('#', src.substring(i, j))); i = j; continue }
            if (c == '.' && i + 1 < n && src[i + 1] == '.') { out.add(Tok('o', "..")); i += if (i + 2 < n && src[i + 2] == '.') 3 else 2; continue }
            out.add(Tok('o', c.toString())); i++
        }
        return out
    }

    /** The library names a script uses, sorted ("io.open", "os.execute", "require"; "io" alone: the library in a variable). */
    fun scan(src: String): List<String> {
        val t = tokens(src)
        val uses = HashSet<String>()
        fun at(k: Int) = t.getOrNull(k)
        fun op(k: Int, v: String) = at(k)?.let { it.t == 'o' && it.v == v } == true
        for (i in t.indices) {
            val a = t[i]
            if (a.t != 'n') continue
            val prevDot = i > 0 && (op(i - 1, ".") || op(i - 1, ":"))
            if (prevDot && !(i > 1 && at(i - 2)?.t == 'n' && (at(i - 2)!!.v == "_G" || at(i - 2)!!.v == "_ENV"))) continue
            if (a.v == "require") { uses.add("require"); continue }
            var lib: String? = null
            var p = i
            if (a.v == "_G" || a.v == "_ENV") {
                if (op(i + 1, "[") && at(i + 2)?.t == 's' && op(i + 3, "]")) {
                    val g = at(i + 2)!!.v
                    if (g == "require") uses.add("require")
                    if (g in LIBS) { lib = g; p = i + 3 }
                }
                if (lib == null) continue
            } else if (a.v in LIBS) lib = a.v else continue
            val nn = at(p + 2)
            when {
                (op(p + 1, ".") || op(p + 1, ":")) && nn?.t == 'n' -> uses.add(lib + "." + nn.v)
                op(p + 1, "[") && nn?.t == 's' && op(p + 3, "]") -> uses.add(lib + "." + nn.v)
                op(p + 1, "=") && !op(p + 2, "=") -> {}   // assigned, not read
                else -> uses.add(lib)
            }
        }
        return uses.filter { it !in QUIET }.sorted()
    }

    private val GROUPS: List<Triple<String, String, (String) -> Boolean>> = listOf(
        Triple("files", "files") { u -> (u == "io" || u.startsWith("io.")) && u != "io.popen" || u in setOf("os.remove", "os.rename", "os.tmpname") },
        Triple("programs", "system commands") { u -> u == "os.execute" || u == "io.popen" },
        Triple("os", "system functions") { u -> u == "os" || u.startsWith("os.") && u !in setOf("os.remove", "os.rename", "os.tmpname", "os.execute", "os.getenv", "os.exit") },
        Triple("environment", "environment variables") { u -> u == "os.getenv" },
        Triple("exit", "closing the game") { u -> u == "os.exit" },
        Triple("debug", "the debug library") { u -> u == "debug" || u.startsWith("debug.") },
        Triple("modules", "loading code from files") { u -> u == "require" || u == "package" || u.startsWith("package.") },
    )

    data class Group(val key: String, val words: String, val names: List<String>)

    fun describe(uses: List<String>): List<Group> = GROUPS.mapNotNull { (k, w, test) -> uses.filter(test).takeIf { it.isNotEmpty() }?.let { Group(k, w, it) } }

    private fun list(items: List<String>) = if (items.size < 2) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /** "files (io.open) and system commands (os.execute)" */
    fun usesText(uses: List<String>) = list(describe(uses).map { g -> "${g.words} (${g.names.take(3).joinToString(", ")}${if (g.names.size > 3) ", …" else ""})" })

    data class ModUses(val sha256: String, val name: String, val uses: List<String>, val scripts: List<String>)

    /** The prompt's paragraphs; real: "Disable Lua sandboxing" is on. */
    fun promptText(mods: List<ModUses>, real: Boolean): List<String> {
        val one = mods.size == 1
        val lines = mods.map { m -> if (one) "${m.name}: this mod's scripts use ${usesText(m.uses)}." else "${m.name}'s scripts use ${usesText(m.uses)}." }.toMutableList()
        val exit = mods.any { "os.exit" in it.uses }
        lines += if (real) "Lua sandboxing is off: these scripts can read, change and delete the files of this app, including your saves and your account sign-in, run programs on this device as this app, and send what they read anywhere."
        else "Here they run in a private folder: they cannot reach your files or run programs." + if (exit) " Closing the game ends this game and returns to the launcher." else ""
        return lines
    }

    /**
     * The mods of a launch whose scripts use the libraries, in load order. names/shas: the mods in load order; read: a
     * merged file's bytes; owners: script path -> mod index (the last archive with the file).
     */
    fun scanLaunch(names: List<String>, shas: List<String>, scripts: List<String>, read: (String) -> ByteArray?, owners: Map<String, Int>): List<ModUses> {
        val per = sortedMapOf<Int, Pair<MutableSet<String>, MutableList<String>>>()
        for (path in scripts) {
            val bytes = read(path) ?: continue
            val uses = scan(bytes.decodeToString())
            if (uses.isEmpty()) continue
            val i = owners[path] ?: (names.size - 1)
            val e = per.getOrPut(i) { HashSet<String>() to ArrayList() }
            e.first.addAll(uses); e.second.add(path)
        }
        return per.map { (i, e) -> ModUses(shas[i], names[i], e.first.sorted(), e.second) }
    }
}
