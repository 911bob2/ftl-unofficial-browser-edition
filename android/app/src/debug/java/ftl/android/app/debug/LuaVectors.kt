package ftl.android.app.debug

import ftl.android.app.mods.FtlmanLua
import ftl.mods.ArchiveRef
import ftl.mods.BytesSource
import ftl.mods.FsEntry
import ftl.mods.FsStat
import ftl.mods.LuaLimits
import ftl.mods.MergeArchive
import ftl.mods.PatchFilesystem
import ftl.mods.PatchRuntime
import ftl.mods.Zip
import ftl.mods.applyOneXml
import ftl.mods.mergeMods
import ftl.mods.pkgFilesystem
import ftl.mods.zipFilesystem
import ftl.saves.Json
import ftl.saves.arr
import ftl.saves.bool
import ftl.saves.int
import ftl.saves.str
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The native Lua patch runtime against the vectors of tools/mods/kotlin-vectors.mjs: lua-cases and L1 (ftlman's ground
 * truth, as tests/lib/mods-lua.mjs), parity (the website's runtime and merge), security (tests/lib/mods-lua-security.mjs)
 * and native (bombs). JVM unit tests cannot load the .so, so this runs on the device:
 *   adb shell CLASSPATH=<base.apk> app_process / ftl.android.app.debug.LuaVectors <vectors.json> <libftlmanlua.so> [section]
 * See docs/ANDROID.md.
 */
object LuaVectors {
    private var passed = 0
    private var failed = 0
    private val SMALL = LuaLimits(2e6, 64.0 * 1024 * 1024)
    private const val CHUNK = "@data/x.append.lua"
    private const val META = "/data/x.append.lua"

    private fun bytes(v: Json?) = Base64.getDecoder().decode(v.str()!!)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun obj(v: Json?) = v as Json.Obj

    private fun check(name: String, block: () -> String?) {
        val t0 = System.currentTimeMillis()
        val problem = try { block() } catch (e: Throwable) { "threw $e" }
        val ms = System.currentTimeMillis() - t0
        if (problem == null) { passed++; println("PASS $name (${ms} ms)") } else { failed++; println("FAIL $name (${ms} ms): ${problem.take(2000)}") }
        System.out.flush()
    }

    private fun firstDifference(a: String?, b: String?): String {
        if (a == null || b == null) return "ours=${a?.take(300)} theirs=${b?.take(300)}"
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return "differs at $i: ours=...${a.substring(maxOf(0, i - 40), minOf(a.length, i + 160))} theirs=...${b.substring(maxOf(0, i - 40), minOf(b.length, i + 160))}"
    }

    private val ERROR_ROW = Regex("""(<e k="[^"]*" ok="false">)([\s\S]*?)(</e>)""")
    private val TRACEBACK = Regex("""\nstack traceback:[\s\S]*\z""")
    private val FINAL_NL = Regex("""\n\z""")
    private fun normaliseErrors(text: String) = ERROR_ROW.replace(text) { m ->
        m.groupValues[1] + FINAL_NL.replaceFirst(TRACEBACK.replaceFirst(m.groupValues[2].removePrefix("runtime error: "), ""), "") + m.groupValues[3]
    }

    private fun luaCases(lua: Json.Obj) {
        val logs = StringBuilder()
        val prelude = lua["prelude"].str()!!
        PatchRuntime(FtlmanLua, ::applyOneXml, { kind, text -> if (kind == 1) logs.append(text).append('\n') }).use { rt ->
            for (c in lua["cases"].arr()) {
                c as Json.Obj
                val name = c["name"].str()!!
                val want = obj(c["expected"])
                check("case $name") {
                    logs.setLength(0)
                    var got: String? = null
                    var error: Throwable? = null
                    try { got = rt.apply(c["base"].str()!!, prelude + c["script"].str()!!, "@base.xml") } catch (e: Exception) { error = e }
                    if (want["ok"].bool() == true) {
                        if (error != null) "ftlman succeeded, we failed: ${error.message}"
                        else {
                            val ours = normaliseErrors(logs.toString() + got)
                            val theirs = normaliseErrors(want["output"].str()!!)
                            if (ours == theirs) null else firstDifference(ours, theirs)
                        }
                    } else when {
                        error == null -> "ftlman failed (${want["error"].str()?.lineSequence()?.first()}), we produced output"
                        want["code"].int() == 137 && !error.message!!.contains("sibling links form a loop") -> "expected the sibling loop message, got ${error.message}"
                        else -> null
                    }
                }
            }
        }
    }

    private fun zips(archives: Json?) = archives.arr().map { a -> a as Json.Obj; MergeArchive(a["name"].str()!!, Zip.open(BytesSource(bytes(a["zip"])))) }

    private fun l1(lua: Json.Obj, label: String) = check(label) {
        val l1 = obj(lua["l1"])
        val base = LinkedHashMap<String, ByteArray>()
        for (f in l1["base"].arr()) { f as Json.Obj; base[f["name"].str()!!] = bytes(f["bytes"]) }
        val result = mergeMods(base.keys.toList(), { base.getValue(it) }, zips(l1["archives"]), patchNative = FtlmanLua)
        val merged = LinkedHashMap(base)
        for (n in result.removed) merged.remove(n)
        for ((n, v) in result.files) merged[n] = v as ByteArray
        val files = obj(l1["files"])
        val text = obj(l1["text"])
        var problem: String? = null
        for ((n, h) in files.fields) {
            val b = merged[n]
            if (b == null) problem = problem ?: "missing $n"
            else if (sha(b) != h.str()) problem = problem ?: "$n: " + firstDifference(b.decodeToString(), text[n].str() ?: "(binary)")
        }
        for (n in merged.keys) if (files[n] == null) problem = problem ?: "extra file $n"
        println("  L1: ${files.fields.size} files, ${result.lua.scripts} scripts, ${result.warnings.size} warnings, peak ${result.lua.memoryPeak.toLong()} bytes")
        problem
    }

    private fun parity(lua: Json.Obj) {
        val p = obj(lua["parity"])
        val logs = ArrayList<Pair<Int, String>>()
        PatchRuntime(FtlmanLua, ::applyOneXml, { kind, text -> logs.add(kind to text) }).use { rt ->
            for (c in p["scripts"].arr()) {
                c as Json.Obj
                check("parity ${c["name"].str()}") {
                    logs.clear()
                    var output: String? = null
                    var error: String? = null
                    try { output = rt.apply(DOC, c["code"].str()!!, CHUNK, META) } catch (e: Exception) { error = e.message }
                    val wantLogs = c["logs"].arr().map { (it.arr()[0].int()!!) to it.arr()[1].str()!! }
                    when {
                        output != c["output"].str() -> firstDifference(output, c["output"].str())
                        error != c["error"].str() -> firstDifference(error, c["error"].str())
                        logs != wantLogs -> "logs: $logs, expected $wantLogs"
                        else -> null
                    }
                }
            }
        }
        val base = LinkedHashMap<String, ByteArray>()
        for (f in p["base"].arr()) { f as Json.Obj; base[f["name"].str()!!] = bytes(f["bytes"]) }
        for (m in p["merges"].arr()) {
            m as Json.Obj
            check("parity merge ${m["name"].str()}") {
                val zips = zips(m["archives"])
                val logged = ArrayList<Pair<Int, String>>()
                try {
                    val r = mergeMods(base.keys.toList(), { base.getValue(it) }, zips, lazyBinary = true, baseSize = { base.getValue(it).size.toLong() },
                        patchNative = FtlmanLua, onLuaLog = { k, t -> logged.add(k to t) })
                    val files = LinkedHashMap<String, String>()
                    for ((n, v) in r.files) files[n] = sha(if (v is ArchiveRef) zips[v.archive].zip.read(v.entry) else v as ByteArray)
                    val want = obj(m["files"] ?: return@check "the website's merge failed: ${m["error"].str()}")
                    when {
                        r.files.keys.toList() != m["order"].arr().map { it.str() } -> "order ${r.files.keys}"
                        files.any { (n, h) -> want[n].str() != h } || files.size != want.fields.size -> "files $files, expected ${want.fields.mapValues { it.value.str() }}"
                        r.removed.toList() != m["removed"].arr().map { it.str() } -> "removed ${r.removed}"
                        r.warnings != m["warnings"].arr().map { it.str() } -> "warnings ${r.warnings}"
                        r.lua.scripts != m["scripts"].int() -> "scripts ${r.lua.scripts}"
                        logged != m["logs"].arr().map { (it.arr()[0].int()!!) to it.arr()[1].str()!! } -> "logs $logged"
                        else -> null
                    }
                } catch (e: Exception) {
                    if (e.message == m["error"].str()) null else firstDifference(e.message, m["error"].str())
                }
            }
        }
    }

    private var DOC = ""
    private val ROW = Regex("<r>([^<]*)</r>")

    private fun scripted(section: String, list: List<Json>, report: String, modZip: Zip) {
        var rt: PatchRuntime? = null
        var small = false
        val written = ArrayList<String>()
        val bad = object : PatchFilesystem {
            override fun stat(path: String): FsStat? = null
            override fun ls(path: String) = emptyList<FsEntry>()
            override fun read(path: String): ByteArray = when (path) {
                "oom" -> throw OutOfMemoryError("test")
                "overflow" -> throw StackOverflowError()
                else -> throw RuntimeException("test")
            }
            override fun write(path: String, data: ByteArray) {}
        }
        for (c in list) {
            c as Json.Obj
            val wantSmall = c["small"].bool() == true
            if (rt == null || wantSmall != small) {
                rt?.close()
                rt = PatchRuntime(FtlmanLua, ::applyOneXml, { _, _ -> }, if (wantSmall) SMALL else LuaLimits())
                small = wantSmall
            }
            val runtime = rt
            check("$section ${c["name"].str()}") {
                written.clear()
                val fs = if (c["fs"].bool() == true) listOf("pkg" to pkgFilesystem(listOf("data/a.txt", "img/b.png"), { 1L }, { "A".toByteArray() }, { n, _ -> written.add(n) }), "mod" to zipFilesystem(modZip)) +
                    (if (c["throwing"].bool() == true) listOf("bad" to bad) else emptyList()) else null
                val code = (if (c["bare"].bool() == true) "" else report) + c["code"].str()!!
                var out: String? = null
                var error: String? = null
                try { out = runtime.apply(DOC, code, if (wantSmall) CHUNK else "@data/blueprints.append.lua", if (wantSmall) null else "/data/blueprints.append.lua", fs) } catch (e: Exception) { error = e.message }
                val rows = out?.let { o -> ROW.findAll(o).map { it.groupValues[1] }.toList() }
                val wantError = c["error"].str()
                val wantResults = c["results"]?.arr()?.map { it.str() }
                when {
                    c["anyEnd"].bool() == true -> { println("  ended with: ${error?.take(200) ?: rows}"); null }
                    wantError != null -> if (error != null && Regex(wantError).containsMatchIn(error)) null else "expected error /$wantError/, got ${error ?: "a result"}"
                    error != null -> "unexpected error: $error"
                    wantResults != null && rows != wantResults -> "rows $rows, expected $wantResults"
                    c["result1"] != null && rows?.getOrNull(1) != c["result1"].str() -> "rows ${rows?.take(3)}"
                    c["contains"] != null && !out!!.contains(c["contains"].str()!!) -> "output lacks ${c["contains"].str()}"
                    c["written"] != null && written != c["written"].arr().map { it.str() } -> "written $written"
                    else -> null
                }
            }
        }
        rt?.close()
    }

    private fun bombs(lua: Json.Obj) {
        val sec = obj(lua["security"])
        for (m in sec["merges"].arr()) {
            m as Json.Obj
            check("security ${m["name"].str()}") {
                val base = mapOf("data/blueprints.xml" to DOC.toByteArray())
                val zip = Zip.open(BytesSource(zipOf("data/blueprints.append.lua" to m["script"].str()!!)))
                try {
                    mergeMods(base.keys.toList(), { base.getValue(it) }, listOf(MergeArchive("bad.zip", zip)), patchNative = FtlmanLua, luaLimits = LuaLimits(2e6))
                    "the merge succeeded"
                } catch (e: ftl.mods.MergeError) {
                    if (Regex(m["error"].str()!!).containsMatchIn(e.message!!)) null else "got ${e.message}"
                }
            }
        }
        fun bomb(n: Int) = "<a>".repeat(n) + "</a>".repeat(n)
        fun merged(entry: String, text: String, base: String) = run {
            val zip = Zip.open(BytesSource(zipOf(entry to text)))
            val b = mapOf("data/x.xml" to base.toByteArray())
            mergeMods(b.keys.toList(), { b.getValue(it) }, listOf(MergeArchive("bomb.zip", zip)), patchNative = FtlmanLua)
        }
        // A nesting bomb through the real merge thread: an error of the merge, not a crash. The heap can run out first.
        val cleanly = Regex("Could not patch XML file data/x\\.xml according to .*|Not enough memory for the merge")
        for ((name, entry, text, base) in listOf(
            listOf("merge-bomb-document", "data/x.append.lua", "document.root:append('x')", bomb(3000000)),
            listOf("merge-bomb-append-target", "data/x.xml.append", "<y/>", bomb(3000000)),
            listOf("merge-bomb-append-script", "data/x.xml.append", bomb(3000000), "<FTL/>"),
        )) check("native $name") {
            try { merged(entry, text, base); "the merge succeeded" } catch (e: ftl.mods.MergeError) {
                println("  ${e.message?.take(160)}")
                if (cleanly.containsMatchIn(e.message!!)) null else "got ${e.message}"
            }
        }
        check("native merge-deep-document-ok") {
            val r = merged("data/x.append.lua", "document.root:append('x')", bomb(20000))
            if ((r.files["data/x.xml"] as ByteArray).decodeToString().contains("x")) null else "output"
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> for ((n, t) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    /** Every runtime starts as a new wasm instance: no globals, the same random sequence, the same memory. */
    private fun restarts() = check("native runtime-restarts-clean") {
        fun one(): Triple<String, String, Double> {
            PatchRuntime(FtlmanLua, ::applyOneXml).use { rt ->
                val before = rt.stats().memory
                val out = rt.apply(DOC, "document.root:append(tostring(leftover) .. ' ' .. tostring(math.random())) load('leftover = 1')()", CHUNK)
                val second = rt.apply(DOC, "document.root:append(tostring(leftover))", CHUNK)
                return Triple(out, second, before)
            }
        }
        val a = one()
        val b = one()
        when {
            a != b -> "first $a, then $b"
            !a.first.contains("nil 0.") || !a.second.contains("1") -> "first run ${a.first} ${a.second}"
            else -> null
        }
    }

    private fun runAll(args: Array<String>) {
        val vectors = Json.parse(File(args[0]).readText()) as Json.Obj
        FtlmanLua.load(args[1])
        val lua = obj(vectors["lua"])
        val sec = obj(lua["security"])
        DOC = sec["doc"].str()!!
        val report = sec["report"].str()!!
        val modZip = Zip.open(BytesSource(bytes(sec["mod"])))
        val only = args.getOrNull(2)
        fun wanted(s: String) = only == null || only == s
        if (wanted("cases")) luaCases(lua)
        if (wanted("l1")) { l1(lua, "L1 merge"); l1(lua, "L1 merge again in this process") }
        if (wanted("parity")) parity(lua)
        if (wanted("security")) scripted("security", sec["cases"].arr(), report, modZip)
        if (wanted("native")) { scripted("native", lua["native"].arr(), report, modZip); restarts() }
        if (wanted("bombs")) bombs(lua)
        // The smallest stack a merge falls back to (Merge.kt): the same scripts, a stack overflow in the host's parser.
        if (wanted("smallstack")) {
            val t = Thread(null, { scripted("16MiB", lua["native"].arr(), report, modZip) }, "small-stack", 16L shl 20)
            t.start()
            t.join()
        }
        println("TOTAL passed=$passed failed=$failed")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var failure: Throwable? = null
        val t = Thread(null, { try { runAll(args) } catch (e: Throwable) { failure = e } }, "vectors", 256L shl 20)
        t.start()
        t.join()
        failure?.let { println("ABORTED $it"); it.printStackTrace(System.out) }
        System.out.flush()
        Runtime.getRuntime().halt(if (failed == 0 && failure == null) 0 else 1)
    }
}
