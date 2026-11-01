package ftl.mods

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// ftlman 0.7.4 Lua patch scripts (`X.append.lua`) for the mod merge: the Kotlin twin of web/mods/lua.mjs, the host side
// of hs-lua/ftlman.c. This module is pure JVM, so the compiled runtime comes in through PatchNative (Android:
// ftl.android.app.mods.FtlmanLua, libftlmanlua.so). Interface: docs/MODS.md, "Lua patch scripts".

class LuaLimits(val instructions: Double = 1e9, val memory: Double = 512.0 * 1024 * 1024)

/** What the compiled runtime exports: mp_init, mp_run, the stats and its shared buffer. One runtime at a time. */
interface PatchNative {
    fun init(instructions: Double, memory: Double, host: PatchHost): Int
    fun run(codeLen: Int, nameLen: Int, pathLen: Int, docLen: Int, fsLen: Int, flags: Int): Int
    fun bufCap(): Int
    fun bufGrow(n: Int): Boolean
    fun bufRead(offset: Int, length: Int): ByteArray
    fun bufWrite(offset: Int, bytes: ByteArray, from: Int, length: Int)
    fun instructions(): Double
    fun memory(): Double
    fun memoryPeak(): Double
    fun nodes(): Double
    fun close()
}

/** What the runtime imports. Results follow hs-lua/ftlman.c; no method lets an exception out. */
interface PatchHost {
    fun log(kind: Int, bytes: ByteArray)
    fun fs(fs: Int, op: Int, pathLen: Int, dataLen: Int): Int
    fun xmlParse(len: Int, topLevelText: Boolean): Int
    fun xmlWrite(len: Int): Int
    fun xmlAppend(lowerLen: Int, upperLen: Int, kind: Int): Int
}

class LuaError(message: String) : Exception(message)
class IoError(message: String) : Exception(message)

internal fun messageOf(e: Throwable): String = if (e is StackOverflowError) "Maximum call stack size exceeded" else e.message ?: e.toString()

// TextDecoder's defaults: a leading BOM is dropped, malformed bytes become U+FFFD per maximal subpart.
internal fun decodeUtf8(b: ByteArray, start: Int = 0, end: Int = b.size): String {
    var from = start
    if (end - from >= 3 && b[from] == 0xef.toByte() && b[from + 1] == 0xbb.toByte() && b[from + 2] == 0xbf.toByte()) from += 3
    try {
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, from, end - from)).toString()
    } catch (_: CharacterCodingException) {}
    val out = StringBuilder(end - from)
    var i = from
    while (i < end) {
        val c = b[i++].toInt() and 0xff
        if (c < 0x80) { out.append(c.toChar()); continue }
        var needed: Int
        var cp: Int
        var lower = 0x80
        var upper = 0xbf
        when (c) {
            in 0xc2..0xdf -> { needed = 1; cp = c and 0x1f }
            in 0xe0..0xef -> { needed = 2; cp = c and 0x0f; if (c == 0xe0) lower = 0xa0; if (c == 0xed) upper = 0x9f }
            in 0xf0..0xf4 -> { needed = 3; cp = c and 0x07; if (c == 0xf0) lower = 0x90; if (c == 0xf4) upper = 0x8f }
            else -> { out.append('�'); continue }
        }
        while (needed > 0 && i < end) {
            val d = b[i].toInt() and 0xff
            if (d < lower || d > upper) break
            lower = 0x80
            upper = 0xbf
            cp = (cp shl 6) or (d and 0x3f)
            i++
            needed--
        }
        if (needed == 0) out.appendCodePoint(cp) else out.append('�')
    }
    return out.toString()
}

// ---- tree events (hs-lua/ftlman.c): 'S' prefix name attrs ... 'E', 'T' text, 'D' cdata, 'C' comment --------------

internal class Bytes(size: Int = 1 shl 16) {
    private var b = ByteArray(size)
    var n = 0
        private set

    private fun need(k: Int) {
        if (n.toLong() + k <= b.size) return
        var c = b.size.toLong() * 2
        while (c < n.toLong() + k) c *= 2
        if (c > Int.MAX_VALUE - 8) c = Int.MAX_VALUE - 8L
        if (c < n.toLong() + k) throw OutOfMemoryError("event buffer")
        b = b.copyOf(c.toInt())
    }

    fun byte(v: Int) { need(1); b[n++] = v.toByte() }
    fun u32(v: Int) {
        need(4)
        b[n] = v.toByte(); b[n + 1] = (v ushr 8).toByte(); b[n + 2] = (v ushr 16).toByte(); b[n + 3] = (v ushr 24).toByte()
        n += 4
    }
    fun str(s: String) {
        val e = s.toByteArray(Charsets.UTF_8)
        u32(e.size)
        need(e.size)
        e.copyInto(b, n)
        n += e.size
    }
    fun bytes(): ByteArray = b.copyOf(n)
}

private const val S = 0x53
private const val E = 0x45
private const val T = 0x54
private const val D = 0x44
private const val C = 0x43

internal fun encodeNode(out: Bytes, node: Node) {
    when (node) {
        is Text -> { out.byte(T); out.str(node.text) }
        is CData -> { out.byte(D); out.str(node.text) }
        is Comment -> { out.byte(C); out.str(node.text) }
        is Element -> {
            out.byte(S)
            val prefix = node.prefix
            if (prefix == null) out.u32(-1) else out.str(prefix)
            out.str(node.name)
            out.u32(node.attrs.size)
            for ((k, v) in node.attrs) { out.str(k); out.str(v) }
            for (c in node.children) encodeNode(out, c)
            out.byte(E)
        }
    }
}

/** Events -> text through speedy-xml's writer (empty elements as `<a/>`). */
internal fun writeEvents(bytes: ByteArray): String {
    val w = Writer()
    val stack = ArrayList<Pair<String?, String>>()
    var p = 0
    fun peek32() = (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or
        ((bytes[p + 2].toInt() and 0xff) shl 16) or ((bytes[p + 3].toInt() and 0xff) shl 24)
    fun u32(): Int { val v = peek32(); p += 4; return v }
    fun str(): String { val n = u32(); val s = decodeUtf8(bytes, p, p + n); p += n; return s }
    while (p < bytes.size) {
        val t = bytes[p++].toInt()
        when (t) {
            S -> {
                val prefix = if (peek32() == -1) { p += 4; null } else str()
                val name = str()
                val n = u32()
                val attrs = ArrayList<Pair<String, String>>(n)
                for (i in 0 until n) { val k = str(); attrs.add(k to str()) }
                if (p < bytes.size && bytes[p].toInt() == E) {
                    p++
                    w.emptyTag(prefix, name)
                    for ((k, v) in attrs) w.attribute(k, v)
                } else {
                    w.start(prefix, name)
                    for ((k, v) in attrs) w.attribute(k, v)
                    stack.add(prefix to name)
                }
            }
            E -> { val (prefix, name) = stack.removeLastOrNull() ?: throw XmlError("bad tree event"); w.end(prefix, name) }
            T -> w.text(str())
            D -> w.cdata(str())
            C -> w.comment(str())
            else -> throw XmlError("bad tree event")
        }
    }
    return w.finish()
}

// ---- filesystems -------------------------------------------------------------------------------------------------

class FsStat(val dir: Boolean, val length: Long = 0)
class FsEntry(val name: String, val dir: Boolean)

interface PatchFilesystem {
    fun stat(path: String): FsStat?
    fun ls(path: String): List<FsEntry>
    fun read(path: String): ByteArray
    fun write(path: String, data: ByteArray)
}

// Rust's String order (UTF-8 bytes = code points).
internal val byCodePoint = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    var r = 0
    while (r == 0 && i < a.length && j < b.length) {
        val x = a.codePointAt(i)
        val y = b.codePointAt(j)
        if (x != y) r = if (x < y) -1 else 1
        i += if (x > 0xffff) 2 else 1
        j += if (y > 0xffff) 2 else 1
    }
    if (r != 0) r else a.length - b.length
}

/** ftlman's VirtualFileTree: directories from path components (split on / and \), lookups split on /. */
class VirtualFileTree(paths: Iterable<String>, what: String = "pkg archive") {
    private class Dir : HashMap<String, Any>()
    private object File

    private val root = Dir()

    init {
        for (path in paths) {
            val parts = path.split('/', '\\')
            var cur = root
            for (c in parts.dropLast(1)) {
                cur = when (val next = cur[c]) {
                    null -> Dir().also { cur[c] = it }
                    is Dir -> next
                    else -> throw IllegalArgumentException("Invalid file tree in $what: file overlaps directory")
                }
            }
            val last = parts.last()
            if (cur.containsKey(last)) throw IllegalArgumentException("Invalid file tree in $what: duplicate file or directory overlaps file")
            cur[last] = File
        }
    }

    private fun traverse(path: String): Any? {
        val parts = path.split('/')
        var cur = root
        for (c in parts.dropLast(1)) cur = cur[c] as? Dir ?: return null
        return cur[parts.last()]
    }

    fun stat(path: String, onFile: () -> Long): FsStat? = when (traverse(path)) {
        is Dir -> FsStat(true)
        null -> null
        else -> FsStat(false, onFile())
    }

    fun ls(path: String): List<FsEntry> = when (val n = traverse(path)) {
        is Dir -> n.keys.sortedWith(byCodePoint).map { FsEntry(it, n[it] is Dir) }
        null -> throw IoError("entity not found")
        else -> throw IoError("not a directory")
    }

    fun read(path: String, onFile: () -> ByteArray): ByteArray = when (traverse(path)) {
        is Dir -> throw IoError("is a directory")
        null -> throw IoError("entity not found")
        else -> onFile()
    }

    fun write(path: String, onFile: () -> Unit) {
        val parts = path.split('/')
        var cur = root
        for (c in parts.dropLast(1)) {
            cur = when (val next = cur[c]) {
                null -> Dir().also { cur[c] = it }
                is Dir -> next
                else -> throw IoError("not a directory")
            }
        }
        val last = parts.last()
        if (cur[last] is Dir) throw IoError("is a directory")
        onFile()
        cur[last] = File
    }
}

/** mod.vfs.pkg (LuaPkgFS): the game files as the merge has them now. */
fun pkgFilesystem(paths: Iterable<String>, size: (String) -> Long, read: (String) -> ByteArray, write: (String, ByteArray) -> Unit): PatchFilesystem {
    val tree = VirtualFileTree(paths)
    return object : PatchFilesystem {
        override fun stat(path: String) = tree.stat(path) { size(path) }
        override fun ls(path: String) = tree.ls(path)
        override fun read(path: String) = tree.read(path) { read(path) }
        override fun write(path: String, data: ByteArray) = tree.write(path) { write(path, data) }
    }
}

/** mod.vfs.mod (LuaZipFS): the archive's entries by exact name, read-only. */
fun zipFilesystem(zip: Zip): PatchFilesystem {
    val tree = VirtualFileTree(zip.entries.map { it.name }.distinct(), "zip file")
    fun entry(path: String) = zip.get(path) ?: throw IoError("specified file not found in archive")
    return object : PatchFilesystem {
        override fun stat(path: String) = tree.stat(path) { entry(path).size }
        override fun ls(path: String) = tree.ls(path)
        override fun read(path: String) = tree.read(path) { zip.read(entry(path)) }
        override fun write(path: String, data: ByteArray) = throw IoError("read-only filesystem or storage medium")
    }
}

// ---- runtime -----------------------------------------------------------------------------------------------------

class PatchStats(val instructions: Double, val memory: Double, val memoryPeak: Double, val nodes: Double)

/**
 * One Lua runtime for a whole merge (ftlman's ModLuaRuntime: scripts share the global table, each gets its own
 * environment). append(lower, upper, kind) is applyOneXml (mod.xml.append); onLog(kind, text) gets print (0) and
 * mod.debug.pretty_print (1) output and Lua warnings (2).
 */
class PatchRuntime(
    private val native: PatchNative,
    private val append: (String, String, String) -> String,
    private val onLog: (Int, String) -> Unit = { _, _ -> },
    limits: LuaLimits = LuaLimits(),
) : AutoCloseable {
    private var filesystems: List<PatchFilesystem>? = null

    private fun bytesAt(length: Int, offset: Int = 0) = native.bufRead(offset, length)
    private fun put(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        val need = offset.toLong() + length
        if (need > native.bufCap() && (need > Int.MAX_VALUE || !native.bufGrow(need.toInt()))) throw IoError("not enough memory")
        native.bufWrite(offset, bytes, 0, length)
        return length
    }
    private fun putError(e: Throwable, base: Int): Int {
        val b = messageOf(e).toByteArray(Charsets.UTF_8)
        val n = minOf(b.size, 4096)
        native.bufWrite(0, b, 0, n)
        return base - n
    }

    private val host = object : PatchHost {
        override fun log(kind: Int, bytes: ByteArray) {
            try { onLog(kind, decodeUtf8(bytes)) } catch (_: Throwable) { /* the script goes on */ }
        }
        override fun fs(fs: Int, op: Int, pathLen: Int, dataLen: Int): Int = try {
            val target = filesystems?.getOrNull(fs) ?: throw IoError("no such filesystem")
            val path = decodeUtf8(bytesAt(pathLen))
            when (op) {
                0 -> target.stat(path)?.let { put((if (it.dir) "d" else "f${it.length}").toByteArray()) } ?: -1
                1 -> {
                    val out = Bytes()
                    for (e in target.ls(path)) { out.byte(if (e.dir) 0x64 else 0x66); out.str(e.name) }
                    put(out.bytes())
                }
                2 -> put(target.read(path))
                else -> { target.write(path, bytesAt(dataLen, pathLen)); 0 }
            }
        } catch (e: Throwable) { putError(e, -2) }
        override fun xmlParse(len: Int, topLevelText: Boolean): Int = try {
            val text = unwrapXmlText(decodeUtf8(bytesAt(len)))
            val out = Bytes()
            for (n in parseAll(text, Reader(text, topLevelText = topLevelText))) encodeNode(out, n)
            put(out.bytes())
        } catch (e: Throwable) { putError(e, -1) }
        override fun xmlWrite(len: Int): Int = try {
            put(writeEvents(bytesAt(len)).toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) { putError(e, -1) }
        override fun xmlAppend(lowerLen: Int, upperLen: Int, kind: Int): Int = try {
            val result = append(decodeUtf8(bytesAt(lowerLen)), decodeUtf8(bytesAt(upperLen, lowerLen)), if (kind != 0) "rawappend" else "append")
            put(result.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) { putError(e, -1) }
    }

    init {
        try {
            val r = native.init(limits.instructions, limits.memory, host)
            if (r < 0) throw LuaError("Lua runtime: " + decodeUtf8(bytesAt(-1 - r)))
        } catch (e: Throwable) {
            native.close()
            throw e
        }
    }

    private fun run(code: String, document: String?, chunkName: String, metaPath: String?, fs: List<Pair<String, PatchFilesystem>>?): String? {
        var had = false
        var doc: Bytes? = null
        if (document != null) {
            had = hadFtlRoot(document)
            val root = try { parseElement("<FTL>" + unwrapXmlText(document) + "</FTL>") } catch (e: Exception) {
                throw LuaError("Could not parse XML document: " + messageOf(e))
            } catch (e: StackOverflowError) {
                throw LuaError("Could not parse XML document: " + messageOf(e))
            } ?: throw LuaError("XML document does not contain a root element")
            doc = Bytes().also { encodeNode(it, root) }
        }
        val names = Bytes()
        fs?.forEach { names.str(it.first) }
        val c = code.toByteArray(Charsets.UTF_8)
        val n = chunkName.toByteArray(Charsets.UTF_8)
        val m = metaPath?.toByteArray(Charsets.UTF_8)
        var off = put(c)
        off += put(n, off)
        if (m != null) off += put(m, off)
        if (doc != null) off += put(doc.bytes(), off)
        put(names.bytes(), off)
        filesystems = fs?.map { it.second } ?: emptyList()
        val r = try {
            native.run(c.size, n.size, m?.size ?: -1, doc?.n ?: 0, names.n, (if (had) 0 else 1) or (if (doc != null) 0 else 4))
        } finally { filesystems = null }
        if (r < 0) throw LuaError(decodeUtf8(bytesAt(-1 - r)))
        return if (doc != null) writeEvents(bytesAt(r)) else null
    }

    /** apply_one_lua: runs `code` on `document` (the current file text); returns the patched text or throws.
     *  fs: (name, filesystem) for mod.vfs (a merge passes pkg, mod); chunkName "@data/x.append.lua". */
    fun apply(document: String, code: String, chunkName: String, metaPath: String? = null, fs: List<Pair<String, PatchFilesystem>>? = null): String =
        run(code, document, chunkName, metaPath, fs)!!

    /** A script without a document (ftlman's `lua-run`). */
    fun run(code: String, chunkName: String, metaPath: String? = null, fs: List<Pair<String, PatchFilesystem>>? = null) {
        run(code, null, chunkName, metaPath, fs)
    }

    fun stats() = PatchStats(native.instructions(), native.memory(), native.memoryPeak(), native.nodes())

    /** The runtime's state lives until here (the wasm instance is dropped with its worker). */
    override fun close() = native.close()
}
