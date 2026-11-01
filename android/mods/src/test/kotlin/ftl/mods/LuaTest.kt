package ftl.mods

import ftl.saves.Json
import ftl.saves.arr
import ftl.saves.int
import ftl.saves.str
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JVM side of Lua patch scripts: VirtualFileTree against web/mods/lua.mjs, events and the runtime's host protocol
 * against a scripted native side. The compiled runtime itself: android/app/src/debug/.../LuaVectors.kt on a device.
 */
class LuaTest {
    private val vectors = Json.parse(File(System.getProperty("vectors")).readText()) as Json.Obj

    private fun stat(s: FsStat?) = if (s == null) "null" else if (s.dir) "dir" else "file:${s.length}"
    private fun attempt(f: () -> String) = try { f() } catch (e: IoError) { "error:${e.message}" }
    private fun wantStat(v: Json?): String = when (v) {
        is Json.Obj -> when {
            v["dir"] != null -> "dir"
            v["error"] != null -> "error:${v["error"].str()}"
            else -> "file:${v["length"].int()}"
        }
        else -> "null"
    }

    @Test
    fun virtualFileTreeEqualsTheWebsite() {
        var ops = 0
        for (t in (vectors["lua"] as Json.Obj)["vfs"].arr()) {
            t as Json.Obj
            val paths = t["paths"].arr().map { it.str()!! }
            val wantError = t["error"].str()
            if (wantError != null) {
                assertEquals(wantError, assertFailsWith<IllegalArgumentException> { pkgFilesystem(paths, { 1 }, { ByteArray(0) }, { _, _ -> }) }.message, paths.toString())
                continue
            }
            val fs = pkgFilesystem(paths, { 1 }, { "R".toByteArray() }, { _, _ -> })
            for (o in t["ops"].arr()) {
                o as Json.Obj
                val path = o["path"].str()!!
                assertEquals(wantStat(o["stat"]), stat(fs.stat(path)), "stat '$path' of $paths")
                val ls = o["ls"]
                val wantLs = if (ls is Json.Obj) "error:${ls["error"].str()}" else ls.arr().joinToString(",") { e -> e as Json.Obj; e["name"].str() + ":" + (e["dir"] as? Json.Bool)?.value }
                assertEquals(wantLs, attempt { fs.ls(path).joinToString(",") { "${it.name}:${it.dir}" } }, "ls '$path' of $paths")
                val rd = o["read"]
                assertEquals(if (rd is Json.Obj) "error:${rd["error"].str()}" else rd.str(), attempt { String(fs.read(path)) }, "read '$path' of $paths")
                val wr = o["write"]
                assertEquals(if (wr is Json.Obj) "error:${wr["error"].str()}" else wr.str(), attempt { fs.write("$path/new", ByteArray(0)); fs.write(path, ByteArray(0)); "ok" }, "write '$path' of $paths")
                ops++
            }
        }
        assertTrue(ops > 10)
    }

    @Test
    fun codePointOrderIsNotUtf16Order() {
        val fs = pkgFilesystem(listOf("d/￯", "d/😀", "d/z", "d/z2"), { 1 }, { ByteArray(0) }, { _, _ -> })
        assertEquals(listOf("z", "z2", "￯", "😀"), fs.ls("d").map { it.name })
    }

    @Test
    fun decodesLikeTextDecoder() {
        fun d(vararg v: Int) = decodeUtf8(ByteArray(v.size) { v[it].toByte() })
        assertEquals("a", d(0xef, 0xbb, 0xbf, 0x61))
        assertEquals("a﻿", d(0x61, 0xef, 0xbb, 0xbf))
        assertEquals("﻿a", d(0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x61))
        assertEquals("�", d(0xff))
        assertEquals("��", d(0xc0, 0x80))
        assertEquals("�a", d(0xe2, 0x82, 0x61))
        assertEquals("�", d(0xe2, 0x82))
        assertEquals("���", d(0xed, 0xa0, 0x80))
        assertEquals("�", d(0xf0, 0x9f, 0x98))
        assertEquals("��", d(0xf4, 0x90))
        assertEquals("é😀", d(0xc3, 0xa9, 0xf0, 0x9f, 0x98, 0x80))
    }

    private fun events(block: Bytes.() -> Unit) = Bytes().apply(block).bytes()
    private fun Bytes.tag(prefix: String?, name: String, vararg attrs: String) {
        byte(0x53)
        if (prefix == null) u32(-1) else str(prefix)
        str(name)
        u32(attrs.size / 2)
        for (a in attrs) str(a)
    }

    @Test
    fun encodesEvents() {
        val root = parseElement("<a z=\"1\" y=\"&lt;\"><b/>t&amp;<![CDATA[c]]><!--d--><p:q/></a>")!!
        val out = Bytes().also { encodeNode(it, root) }.bytes()
        assertContentEquals(events {
            tag(null, "a", "z", "1", "y", "<")
            tag(null, "b"); byte(0x45)
            byte(0x54); str("t&")
            byte(0x44); str("c")
            byte(0x43); str("d")
            tag("p", "q"); byte(0x45)
            byte(0x45)
        }, out)
        val b = out.copyOfRange(1, 5)
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), b)
    }

    @Test
    fun writesEvents() {
        assertEquals("<a z=\"1\" y=\"&lt;\"><b/>t&amp;<![CDATA[c]]><!--d&gt;--><p:q></p:q></a>", writeEvents(events {
            tag(null, "a", "z", "1", "y", "<")
            tag(null, "b"); byte(0x45)
            byte(0x54); str("t&")
            byte(0x44); str("c")
            byte(0x43); str("d>")
            tag("p", "q"); byte(0x54); str(""); byte(0x45)
            byte(0x45)
        }))
        assertEquals("a<b/>", writeEvents(events { byte(0x54); str("﻿a"); tag(null, "b"); byte(0x45) }))
        assertFailsWith<XmlError> { writeEvents(events { byte(0x45) }) }
        assertFailsWith<XmlError> { writeEvents(events { byte(0x51) }) }
    }

    /** The native side's protocol, played by the test: `script` plays the C code of one mp_run. */
    private class FakeNative(private val script: FakeNative.() -> Int) : PatchNative {
        var buf = ByteArray(65536)
        lateinit var host: PatchHost
        var initResult = 0
        var closed = 0
        var args = IntArray(0)
        override fun init(instructions: Double, memory: Double, host: PatchHost): Int {
            this.host = host
            if (initResult < 0) "bad".toByteArray().copyInto(buf)
            return initResult
        }
        override fun run(codeLen: Int, nameLen: Int, pathLen: Int, docLen: Int, fsLen: Int, flags: Int): Int {
            args = intArrayOf(codeLen, nameLen, pathLen, docLen, fsLen, flags)
            return script()
        }
        override fun bufCap() = buf.size
        override fun bufGrow(n: Int): Boolean { buf = buf.copyOf(maxOf(n, buf.size)); return true }
        override fun bufRead(offset: Int, length: Int) = buf.copyOfRange(offset, offset + length)
        override fun bufWrite(offset: Int, bytes: ByteArray, from: Int, length: Int) { bytes.copyInto(buf, offset, from, from + length) }
        override fun instructions() = 1.0
        override fun memory() = 2.0
        override fun memoryPeak() = 3.0
        override fun nodes() = 4.0
        override fun close() { closed++ }
        fun text(offset: Int, n: Int) = String(buf, offset, n)
        fun set(s: String): Int { val b = s.toByteArray(); bufGrow(b.size); b.copyInto(buf); return b.size }
    }

    private val noAppend: (String, String, String) -> String = { _, _, _ -> error("append") }

    @Test
    fun bufferLayoutAndResults() {
        var seen = emptyList<String>()
        val native = FakeNative {
            val (c, n, p, d, f) = args.toList()
            seen = listOf(text(0, c), text(c, n), text(c + n, maxOf(p, 0)), text(c + n + maxOf(p, 0) + d, f))
            // result: an empty element as events
            val out = Bytes().apply { byte(0x53); u32(-1); str("r"); u32(0); byte(0x45) }.bytes()
            out.copyInto(buf)
            out.size
        }
        val rt = PatchRuntime(native, noAppend)
        val fs = pkgFilesystem(emptyList(), { 0 }, { ByteArray(0) }, { _, _ -> })
        assertEquals("<r/>", rt.apply("<FTL><a/></FTL>", "code", "@name", "/path", listOf("pkg" to fs, "mod" to fs)))
        assertEquals(listOf("code", "@name", "/path", "\u0003\u0000\u0000\u0000pkg\u0003\u0000\u0000\u0000mod"), seen)
        assertEquals(0, native.args[5], "had an <FTL> root")
        rt.apply("<a/>", "c", "@n")
        assertEquals(listOf(-1, 1), listOf(native.args[2], native.args[5]), "no path, no root")
        assertEquals(0, native.args[4], "no mod.vfs")
        assertTrue(assertFailsWith<LuaError> { rt.apply("<a", "c", "@n") }.message!!.startsWith("Could not parse XML document: "))
        val st = rt.stats()
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), listOf(st.instructions, st.memory, st.memoryPeak, st.nodes))
        rt.close()
        assertEquals(1, native.closed)
    }

    @Test
    fun noDocumentAndScriptErrors() {
        var message: String? = null
        val native = FakeNative { message?.let { -1 - set(it) } ?: 0 }
        val rt = PatchRuntime(native, noAppend)
        rt.run("code", "@n")
        assertEquals(listOf(0, 5), listOf(native.args[3], native.args[5]))
        message = "boom"
        assertEquals("boom", assertFailsWith<LuaError> { rt.run("code", "@n") }.message)
    }

    @Test
    fun initFailureClosesTheNativeSide() {
        val native = FakeNative { 0 }.apply { initResult = -4 }
        assertEquals("Lua runtime: bad", assertFailsWith<LuaError> { PatchRuntime(native, noAppend) }.message)
        assertEquals(1, native.closed)
    }

    @Test
    fun hostFilesystemCalls() {
        val written = LinkedHashMap<String, String>()
        val pkg = pkgFilesystem(listOf("data/a.txt", "data/d/b"), { 7 }, { "body".toByteArray() }, { n, d -> written[n] = String(d) })
        lateinit var results: List<Any>
        val native = FakeNative {
            fun call(fs: Int, op: Int, path: String, data: String = ""): Pair<Int, String> {
                val n = set(path + data)
                val r = host.fs(fs, op, path.length, data.length)
                return r to when {
                    r >= 0 -> text(0, r)
                    r <= -2 -> text(0, -2 - r)
                    else -> ""
                }
            }
            results = listOf(
                call(0, 0, "data/a.txt"), call(0, 0, "data"), call(0, 0, "nope"),
                call(0, 2, "data/a.txt"), call(0, 2, "nope"), call(0, 2, "data"),
                call(0, 3, "data/new", "xyz"), call(0, 3, "data", "x"),
                call(1, 3, "m", "x"), call(9, 0, "x"),
            )
            set("data")
            val r = host.fs(0, 1, 4, 0)
            results = results + listOf(r to buf.copyOf(r).toList())
            0
        }
        val rt = PatchRuntime(native, noAppend)
        val mod = object : PatchFilesystem {
            override fun stat(path: String) = null
            override fun ls(path: String) = emptyList<FsEntry>()
            override fun read(path: String) = ByteArray(0)
            override fun write(path: String, data: ByteArray) = throw IoError("read-only filesystem or storage medium")
        }
        rt.run("c", "@n", null, listOf("pkg" to pkg, "mod" to mod))
        fun err(msg: String) = (-2 - msg.length) to msg
        assertEquals(listOf<Any>(
            2 to "f7", 1 to "d", -1 to "",
            4 to "body", err("entity not found"), err("is a directory"),
            0 to "", err("is a directory"),
            err("read-only filesystem or storage medium"), err("no such filesystem"),
        ), results.dropLast(1))
        assertEquals(mapOf("data/new" to "xyz"), written)
        val ls = results.last() as Pair<*, *>
        val want = Bytes().apply { byte(0x66); str("a.txt"); byte(0x64); str("d"); byte(0x66); str("new") }.bytes().toList()
        assertEquals(want, ls.second)
    }

    @Test
    fun hostXmlCallsAndErrorCap() {
        lateinit var results: List<Pair<Int, String>>
        val long = "é".repeat(3000)
        val appended = ArrayList<List<String>>()
        val native = FakeNative {
            fun out(r: Int) = r to if (r >= 0) text(0, r) else String(buf, 0, -1 - r, Charsets.UTF_8)
            val n = set("<a>")
            val failParse = out(host.xmlParse(n, false))
            val topLevel = set("text")
            val rejected = out(host.xmlParse(topLevel, false))
            val accepted = set("text")
            val parsed = host.xmlParse(accepted, true)
            val parsedEvents = buf.copyOf(parsed).toList()
            val ev = Bytes().apply { byte(0x54); str("a<") }.bytes()
            ev.copyInto(buf)
            val wrote = out(host.xmlWrite(ev.size))
            val lo = set("lowerUPPER")
            val ap = out(host.xmlAppend(5, 5, 0))
            set("lowerUPPER")
            val ra = out(host.xmlAppend(5, 5, 1))
            set("x")
            val capped = host.xmlAppend(1, 0, 0)
            results = listOf(failParse, rejected, wrote, ap, ra, capped to "")
            assertEquals(Bytes().apply { byte(0x54); str("text") }.bytes().toList(), parsedEvents)
            assertEquals(10, lo)
            0
        }
        val rt = PatchRuntime(native, { a, b, kind ->
            appended.add(listOf(a, b, kind))
            if (a == "x") throw MergeError(long)
            "ok"
        })
        rt.run("c", "@n")
        assertEquals(-1 - "unclosed element".length, results[0].first)
        assertEquals("unclosed element", results[0].second)
        assertEquals("top-level text is forbidden", results[1].second)
        assertEquals(5 to "a&lt;", results[2])
        assertEquals(2 to "ok", results[3])
        assertEquals(listOf(listOf("lower", "UPPER", "append"), listOf("lower", "UPPER", "rawappend"), listOf("x", "", "append")), appended)
        assertEquals(-1 - 4096, results[5].first, "error messages stop at 4096 bytes")
    }

    @Test
    fun hostMethodsCatchErrors() {
        lateinit var results: List<Int>
        val native = FakeNative {
            set("x")
            results = listOf(host.fs(0, 2, 1, 0), host.fs(0, 2, 1, 0))
            0
        }
        var first = true
        val fs = object : PatchFilesystem {
            override fun stat(path: String) = null
            override fun ls(path: String) = emptyList<FsEntry>()
            override fun read(path: String): ByteArray { if (first) { first = false; throw StackOverflowError() } else throw OutOfMemoryError("gone") }
            override fun write(path: String, data: ByteArray) {}
        }
        PatchRuntime(native, noAppend).run("c", "@n", null, listOf("f" to fs))
        assertEquals(-2 - "gone".length, results[1])
        assertEquals("gone", native.text(0, 4))
        assertEquals(-2 - "Maximum call stack size exceeded".length, results[0])
    }

    @Test
    fun aMergeOfScriptsNeedsTheRuntime() {
        val zip = Zip.open(BytesSource(zipOf("data/a.append.lua" to "x")))
        val base = mapOf("data/a.xml" to "<a/>".toByteArray())
        assertEquals("This build has no Lua patch-script runtime", assertFailsWith<MergeError> {
            mergeMods(base.keys.toList(), { base.getValue(it) }, listOf(MergeArchive("m.zip", zip)))
        }.message)
    }

    @Test
    fun aDeepTreeFailsTheMergeCleanly() {
        val depth = 20_000_000
        val zip = Zip.open(BytesSource(zipOf("data/a.xml.append" to "<x/>")))
        val base = mapOf("data/a.xml" to ("<a>".repeat(depth) + "</a>".repeat(depth)).toByteArray())
        val error = assertFailsWith<MergeError> { mergeMods(base.keys.toList(), { base.getValue(it) }, listOf(MergeArchive("m.zip", zip))) }
        assertTrue(error.message!!.contains("Maximum call stack size exceeded") || error.message!!.startsWith("Not enough memory"), error.message)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> for ((n, t) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
}
