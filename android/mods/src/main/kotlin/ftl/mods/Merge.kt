package ftl.mods

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// Mod merge with ftlman 0.7.4 semantics: the Kotlin twin of web/mods/merge.mjs. Archives in order, entries in
// central-directory order, each file replacing, adding to or patching what earlier mods (and the base ftl.dat) left.

const val FTLMAN_VERSION = "0.7.4"
private val WRAPPER = Regex("""(<[?]xml [^>]*?[?]>\n*)|(</?FTL>)""")
private val IGNORED = Regex("""[.]DS_Store$|(^|/)thumbs[.]db$|(^|/)[.]dropbox$|^~|~$|(^|/)#[^\n\r\u2028\u2029]+#$""")
private val KNOWN_DIRS = Regex("^(audio|data|fonts|img)/")
private val SUFFIXES = listOf(".xml.append" to "append", ".append.xml" to "append", ".rawappend.xml" to "rawappend",
    ".xml.rawappend" to "rawappend", ".append.lua" to "lua")

class MergeError(message: String) : Exception(message)

private fun strictUtf8(bytes: ByteArray, from: Int, to: Int, what: String): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, from, to - from)).toString()
} catch (_: CharacterCodingException) {
    throw MergeError("$what is not valid UTF-8")
}

fun strictUtf8(bytes: ByteArray, what: String) = strictUtf8(bytes, 0, bytes.size, what)

private fun utf16(bytes: ByteArray, le: Boolean, what: String): String {
    val n = bytes.size - 2
    if (n % 2 != 0) throw MergeError("$what: UTF-16 decoding failed: partial bytepair")
    val chars = CharArray(n / 2)
    for (k in chars.indices) {
        val a = bytes[2 + 2 * k].toInt() and 0xff
        val b = bytes[3 + 2 * k].toInt() and 0xff
        chars[k] = (if (le) a or (b shl 8) else (a shl 8) or b).toChar()
    }
    var i = 0
    while (i < chars.size) {
        val c = chars[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= chars.size || !Character.isLowSurrogate(chars[i + 1])) throw MergeError("$what: invalid UTF-16")
            i += 2
            continue
        }
        if (Character.isLowSurrogate(c)) throw MergeError("$what: invalid UTF-16")
        i++
    }
    return String(chars)
}

/** read_encoded_text: UTF-16 by BOM, UTF-8 BOM stripped. Like ftlman, the first two bytes are validated as UTF-8 on
 *  their own, so a file whose second byte starts a multi-byte character fails. */
fun readEncodedText(bytes: ByteArray, what: String = "file"): String {
    if (bytes.size < 2) return strictUtf8(bytes, what)
    val a = bytes[0].toInt() and 0xff
    val b = bytes[1].toInt() and 0xff
    if (a == 0xff && b == 0xfe) return utf16(bytes, true, what)
    if (a == 0xfe && b == 0xff) return utf16(bytes, false, what)
    if (a == 0xef && b == 0xbb) {
        if (bytes.size < 3) throw MergeError("$what is not valid UTF-8")
        if ((bytes[2].toInt() and 0xff) == 0xbf) return strictUtf8(bytes, 3, bytes.size, what)
        return strictUtf8(bytes, 0, 3, what) + strictUtf8(bytes, 3, bytes.size, what)
    }
    return strictUtf8(bytes, 0, 2, what) + strictUtf8(bytes, 2, bytes.size, what)
}

fun unwrapXmlText(text: String) = WRAPPER.replace(text, "")
internal fun hadFtlRoot(text: String) = WRAPPER.findAll(text).any { it.groups[2] != null }
fun lfToCrlf(s: String) = s.replace(Regex("(?<!\r)\n"), "\r\n")

/** apply_one_xml: patch `document` (the current file text) with an .append or .rawappend script. */
fun applyOneXml(document: String, script: String, kind: String): String {
    val had = hadFtlRoot(document)
    if (kind == "rawappend") {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + (if (had) "<FTL>\n" else "") + unwrapXmlText(document) +
            "\n\n<!-- Appended by ftlman $FTLMAN_VERSION -->\n\n" + unwrapXmlText(script) + "\n" + (if (had) "</FTL>\n" else "")
    }
    val root = parseElement("<FTL>" + unwrapXmlText(document) + "</FTL>") ?: throw MergeError("XML document does not contain a root element")
    patch(root, parseScript(unwrapXmlText(script)))
    val w = Writer()
    if (had) writeElement(w, root) else writeChildren(w, root)
    return w.finish()
}

/** A plain .xml file copied from a mod: re-emitted through speedy-xml's event writer (declarations/PIs dropped,
 *  tags normalised, end tags renamed to match their start tags, unclosed elements closed). */
fun rewriteXml(text: String): String {
    val r = Reader(text, topLevelText = true, unclosed = true)
    val w = Writer()
    val stack = ArrayList<Pair<String?, String>>()
    while (true) {
        val e = r.next() ?: break
        when (e.type) {
            EventType.START, EventType.EMPTY -> {
                if (e.type == EventType.START) { stack.add(e.prefix to e.name); w.start(e.prefix, e.name) } else w.emptyTag(e.prefix, e.name)
                for (a in attributes(e.attrs)) w.rawAttribute(a)
            }
            EventType.END -> stack.removeLastOrNull()?.let { w.end(it.first, it.second) }
            else -> w.raw(e.raw)
        }
    }
    while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).let { w.end(it.first, it.second) }
    return w.finish()
}

private fun appendType(name: String): Pair<String, String>? {
    for ((suffix, kind) in SUFFIXES) if (name.endsWith(suffix)) return name.substring(0, name.length - suffix.length) to kind
    return null
}

/** One archive in load order. */
class MergeArchive(val name: String, val zip: Zip)

/** A binary copied from an archive, read when the engine asks for it. */
class ArchiveRef(val archive: Int, val entry: ZipEntry)

class MergeProgress(val archive: Int, val file: Int, val files: Int, val name: String)

class LuaStats(var scripts: Int = 0, var instructions: Double = 0.0, var maxInstructions: Double = 0.0, var memoryPeak: Double = 0.0)

/** files: changed/added files in first-write order, each ByteArray or (with lazyBinary) ArchiveRef. */
class MergeResult(val files: LinkedHashMap<String, Any>, val removed: LinkedHashSet<String>, val warnings: List<String>, val lua: LuaStats)

// A merge recurses over XML trees (and mod scripts choose how deep they are), so it gets a large stack of its own:
// an overflow there is an error of the merge. Only touched pages cost memory; the smaller sizes are for address
// spaces too crowded for the first.
private val MERGE_STACKS = longArrayOf(256L shl 20, 64L shl 20, 16L shl 20)

private fun <T> onMergeThread(body: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    val task = Runnable { try { result = body() } catch (e: Throwable) { failure = e } }
    var started: Thread? = null
    for (size in MERGE_STACKS) {
        try {
            started = Thread(null, task, "mod-merge", size).apply { start() }
            break
        } catch (_: OutOfMemoryError) {}
    }
    (started ?: throw MergeError("Not enough memory to start the merge")).join()
    when (val e = failure) {
        null -> {}
        is StackOverflowError -> throw MergeError(messageOf(e))
        is OutOfMemoryError -> throw MergeError("Not enough memory for the merge")
        else -> throw e
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * baseNames: names in the base ftl.dat in order; readBase(name): its bytes. Unsupported or failing input throws
 * MergeError/XmlError where ftlman's patch would fail. Runs on a thread of its own and calls back there.
 * Lua patch scripts: patchNative is the compiled runtime (without it they fail the merge); baseSize(name) (optional)
 * gives a base file's size without reading it; luaLimits per script / for the runtime; onLuaLog(kind, text): print and
 * pretty_print output.
 */
fun mergeMods(
    baseNames: List<String>,
    readBase: (String) -> ByteArray,
    archives: List<MergeArchive>,
    lazyBinary: Boolean = false,
    onWarning: (String) -> Unit = {},
    onProgress: (MergeProgress) -> Unit = {},
    baseSize: ((String) -> Long)? = null,
    patchNative: PatchNative? = null,
    luaLimits: LuaLimits = LuaLimits(),
    onLuaLog: (Int, String) -> Unit = { _, _ -> },
): MergeResult = onMergeThread {
    var lua: PatchRuntime? = null
    try {
        merge(baseNames, readBase, archives, lazyBinary, onWarning, onProgress, baseSize) {
            lua ?: PatchRuntime(patchNative ?: throw MergeError("This build has no Lua patch-script runtime"), ::applyOneXml, onLuaLog, luaLimits).also { lua = it }
        }
    } finally {
        lua?.close()
    }
}

private fun merge(
    baseNames: List<String>,
    readBase: (String) -> ByteArray,
    archives: List<MergeArchive>,
    lazyBinary: Boolean,
    onWarning: (String) -> Unit,
    onProgress: (MergeProgress) -> Unit,
    baseSize: ((String) -> Long)?,
    lua: () -> PatchRuntime,
): MergeResult {
    val base = baseNames.toHashSet()
    val files = LinkedHashMap<String, Any>()
    val removed = LinkedHashSet<String>()
    val warnings = ArrayList<String>()
    fun warn(text: String) { warnings.add(text); onWarning(text) }
    val lower = HashMap<String, String>()
    for (n in baseNames) lower[n.lowercase()] = n
    fun contains(n: String) = files.containsKey(n) || (n in base && n !in removed)
    fun read(n: String): ByteArray = when (val v = files[n]) {
        null -> readBase(n)
        is ByteArray -> v
        else -> throw MergeError("$n is not a text file")
    }
    fun put(n: String, bytes: Any) { removed.remove(n); files[n] = bytes }
    val luaStats = LuaStats()
    // mod.vfs.pkg: what ftl.dat holds at this point of the merge.
    fun readSync(n: String): ByteArray = when (val v = files[n]) {
        null -> readBase(n)
        is ByteArray -> v
        else -> (v as ArchiveRef).let { archives[it.archive].zip.read(it.entry) }
    }
    fun sizeOf(n: String): Long = when (val v = files[n]) {
        is ByteArray -> v.size.toLong()
        is ArchiveRef -> v.entry.size
        else -> baseSize?.invoke(n) ?: readSync(n).size.toLong()
    }
    fun pkgPaths(): List<String> {
        val out = ArrayList<String>(baseNames.size + files.size)
        for (n in baseNames) if (n !in removed && !files.containsKey(n)) out.add(n)
        out.addAll(files.keys)
        return out
    }

    for ((index, archive) in archives.withIndex()) {
        val skippedDirs = HashSet<String>()
        val entries = archive.zip.entries.filter { !it.name.endsWith("/") && !it.name.endsWith("\\") }
        for ((j, entry) in entries.withIndex()) {
            val name = entry.name.replace('\\', '/')
            if (name.startsWith("/") || name.split('/').contains("..")) throw MergeError("Unsafe path ${entry.name} in ${archive.name}")
            if (name.startsWith("mod-appendix/")) continue
            // silpkg (ftl.dat) paths are ASCII-only; ftlman fails on any other inserted name.
            val ascii = name.all { it.code < 0x80 }
            if (!KNOWN_DIRS.containsMatchIn(name)) {
                val sep = name.indexOf('/')
                if (sep >= 0) {
                    val dir = name.substring(0, sep)
                    if (skippedDirs.add(dir)) warn("${archive.name}: skipping unrecognized top-level directory $dir")
                    continue
                }
            }
            if (j % 64 == 0) onProgress(MergeProgress(index, j, entries.size, archive.name))
            if (!ascii && (KNOWN_DIRS.containsMatchIn(name) || !name.contains('/')))
                throw MergeError("$name from ${archive.name}: non-ASCII file names cannot be stored in ftl.dat")
            val what = "$name from ${archive.name}"
            fun text() = readEncodedText(archive.zip.read(entry), what)
            val append = appendType(name)
            if (append != null) {
                val (stem, kind) = append
                var target = "$stem.xml"
                if (!contains(target)) {
                    val c = lower[target.lowercase()]
                    if (c != null) { warn("${archive.name}: $target not found, using case-insensitive match $c"); target = c }
                }
                if (!contains(target)) { warn("${archive.name}: ignoring $name with non-existent base file"); continue }
                val original = strictUtf8(read(target), target)
                val script = text()
                val result: String
                if (kind == "lua") {
                    // ftlman's apply_one_lua with mod.vfs.pkg/mod; one runtime for the whole merge, as ftlman.
                    val rt = lua()
                    val fs = try {
                        listOf("pkg" to pkgFilesystem(pkgPaths(), ::sizeOf, ::readSync) { n, bytes -> put(n, bytes) }, "mod" to zipFilesystem(archive.zip))
                    } catch (e: Exception) { throw MergeError("Failed to create archive filesystem for $what: ${e.message}") }
                    result = try { rt.apply(original, script, "@$name", "/$name", fs) } catch (e: Exception) {
                        throw MergeError("Could not patch XML file $target according to $what: ${e.message}")
                    } catch (e: StackOverflowError) {
                        throw MergeError("Could not patch XML file $target according to $what: ${messageOf(e)}")
                    }
                    val st = rt.stats()
                    luaStats.scripts++
                    luaStats.instructions += st.instructions
                    luaStats.maxInstructions = maxOf(luaStats.maxInstructions, st.instructions)
                    luaStats.memoryPeak = st.memoryPeak
                } else {
                    result = try { applyOneXml(original, script, kind) } catch (e: Exception) {
                        throw MergeError("Could not patch XML file $target according to $what: ${e.message}")
                    } catch (e: StackOverflowError) {
                        throw MergeError("Could not patch XML file $target according to $what: ${messageOf(e)}")
                    }
                }
                put(target, result.encodeToByteArray())
            } else if (name.endsWith(".xml.rawclobber") || name.endsWith(".rawclobber.xml")) {
                val target = if (name.endsWith(".rawclobber.xml")) name.removeSuffix(".rawclobber.xml") + ".xml" else name.removeSuffix(".rawclobber")
                put(target, text().encodeToByteArray())
                lower[target.lowercase()] = target
            } else if (name.endsWith(".xml")) {
                val out = try { rewriteXml(text()) } catch (e: XmlError) { throw MergeError("XML parse error in $what: ${e.message}") }
                put(name, out.encodeToByteArray())
                lower[name.lowercase()] = name
            } else if (!IGNORED.containsMatchIn(name)) {
                if (name.endsWith(".txt")) {
                    put(name, lfToCrlf(text()).encodeToByteArray())
                    lower[name.lowercase()] = name
                } else put(name, if (lazyBinary) ArchiveRef(index, entry) else archive.zip.read(entry))
            } else if (contains(name)) {
                // ftlman removes the existing entry before noticing the file is ignored, so it is deleted.
                files.remove(name)
                if (name in base) removed.add(name)
            }
        }
    }
    return MergeResult(files, removed, warnings, luaStats)
}
