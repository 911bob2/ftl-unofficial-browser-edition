package ftl.mods

import ftl.saves.Json
import ftl.saves.arr
import ftl.saves.str
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in: the Kotlin merge on real mod sets against the website's merge (which is byte-identical to ftlman 0.7.4 on
 * them, tools/mods/compare-ftlman.mjs). FTL_MODS_REAL names a folder of manifests written by
 * tools/mods/merge-manifest.mjs (the vanilla ftl.dat, the archives in order, every merged file's SHA-256). Sets with
 * Lua patch scripts need the native runtime and are compared on a device instead. Without FTL_MODS_REAL it does nothing.
 */
class RealModsTest {
    @Test
    fun realModSetsEqualWebMerge() {
        val dir = System.getenv("FTL_MODS_REAL")?.takeIf { it.isNotEmpty() } ?: return
        val manifests = File(dir).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(manifests.isNotEmpty(), "no manifests in $dir")
        for (m in manifests) {
            val manifest = Json.parse(m.readText()) as Json.Obj
            val base = Pkg(File(manifest["vanilla"].str()!!))
            val archives = manifest["mods"].arr().map { p -> File(p.str()!!).let { MergeArchive(it.name, Zip.open(FileSource(it))) } }
            try {
                val result = mergeMods(base.names, base::read, archives, lazyBinary = true)
                val want = (manifest["files"] as Json.Obj).fields
                var checked = 0
                for (name in base.names) if (name !in result.removed && name !in result.files) {
                    assertEquals(want[name]?.str(), sha256(base.read(name)), "${m.name}: $name (vanilla)"); checked++
                }
                for ((name, v) in result.files) {
                    val bytes = when (v) {
                        is ByteArray -> v
                        is ArchiveRef -> archives[v.archive].zip.read(v.entry)
                        else -> error("unexpected merge value for $name")
                    }
                    assertEquals(want[name]?.str(), sha256(bytes), "${m.name}: $name"); checked++
                }
                assertEquals(want.size, checked, "${m.name}: file count")
                println("RealModsTest ${m.name}: $checked files equal, ${result.warnings.size} warnings")
            } finally {
                for (a in archives) a.zip.close()
                base.close()
            }
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** FTL's PKG archive (ftl.dat) as tools/mods/pkg.mjs reads it: empty slots skipped, DEFLATED entries inflated. */
    private class Pkg(file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val entries = LinkedHashMap<String, LongArray>()
        val names: List<String> get() = entries.keys.toList()

        init {
            val head = ByteBuffer.wrap(bytes(0, 16))
            check(head.int == 0x504b470a && head.short.toInt() == 16 && head.short.toInt() == 20) { "not a PKG archive: $file" }
            val count = head.int; val nameSize = head.int
            val table = ByteBuffer.wrap(bytes(16, count * 20))
            val nameBytes = bytes(16L + count * 20, nameSize)
            for (i in 0 until count) {
                table.int // path hash
                val flagName = table.int; val offset = table.int.toLong() and 0xffffffffL
                val size = table.int.toLong() and 0xffffffffL; val unpacked = table.int.toLong() and 0xffffffffL
                if (offset == 0L) continue
                val start = flagName and 0xffffff
                var end = start; while (nameBytes[end] != 0.toByte()) end++
                entries[String(nameBytes, start, end - start, Charsets.ISO_8859_1)] = longArrayOf(offset, size, unpacked, (flagName ushr 24).toLong())
            }
        }

        private fun bytes(offset: Long, length: Int): ByteArray = ByteArray(length).also { raf.seek(offset); raf.readFully(it) }

        fun read(name: String): ByteArray {
            val (offset, size, unpacked, flags) = entries[name] ?: error("no $name")
            val raw = bytes(offset, size.toInt())
            if (flags == 0L) return raw
            check(flags == 1L) { "unknown PKG flags in $name" }
            val out = ByteArray(unpacked.toInt())
            Inflater().run {
                setInput(raw)
                var n = 0
                while (n < out.size && !finished()) n += inflate(out, n, out.size - n).also { if (it == 0 && needsInput()) error("short inflate of $name") }
                end()
            }
            return out
        }

        override fun close() = raf.close()
    }
}
