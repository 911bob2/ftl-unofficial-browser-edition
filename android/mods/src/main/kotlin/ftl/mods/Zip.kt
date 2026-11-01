package ftl.mods

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

// Minimal ZIP reader for mod archives (.ftl/.zip), the Kotlin twin of web/mods/zip.mjs: random-access reads, stored
// and deflated entries, ZIP64 sizes/offsets, entries in central directory order (ftlman applies files in that order).

class ZipFormatError(message: String) : Exception(message)

interface ZipSource : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    override fun close() {}
}

class BytesSource(private val bytes: ByteArray) : ZipSource {
    override val size get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
}

class FileSource(file: File) : ZipSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0)
        val out = ByteArray(n)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

class ZipEntry(val name: String, val method: Int, val compressed: Long, val size: Long, val offset: Long, val encrypted: Boolean)

// CP437 high half, for names without the UTF-8 flag (as web/mods/zip.mjs decodes them).
private const val CP437 = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

private fun cp437(bytes: ByteArray) = buildString { for (b in bytes) { val c = b.toInt() and 0xff; append(if (c < 128) c.toChar() else CP437[c - 128]) } }

class Zip private constructor(private val source: ZipSource, val entries: List<ZipEntry>) : AutoCloseable {
    private val byName = entries.associateBy { it.name }

    /**
     * What an archive may unpack to, checked when a mod is added (web/mods/zip.mjs checks the same): a small archive of
     * huge text files would run the merge out of memory. Returns the reason it is refused, or null.
     */
    fun limitProblem(): String? {
        var total = 0L
        var text = 0L
        for (e in entries) {
            total += e.size
            if (TEXT_ENTRY.containsMatchIn(e.name)) text += e.size
            if (e.size >= RATIO_FROM && e.size / maxOf(e.compressed, 1L) > MAX_RATIO) return "${e.name} unpacks to more than $MAX_RATIO times its stored size."
        }
        if (total > MAX_TOTAL) return "This archive unpacks to more than 4 GiB, too much for a mod."
        if (text > MAX_TEXT) return "This archive has more than 512 MiB of text files, too much for a mod."
        return null
    }

    fun get(name: String): ZipEntry? = byName[name]

    fun read(entry: ZipEntry): ByteArray {
        if (entry.encrypted) throw ZipFormatError("Encrypted ZIP entries are unsupported: ${entry.name}")
        val head = le(source.read(entry.offset, 30))
        if (head.capacity() < 30 || head.getInt(0) != 0x04034b50) throw ZipFormatError("Invalid ZIP entry header: ${entry.name}")
        val start = entry.offset + 30 + u16(head, 26) + u16(head, 28)
        if (entry.compressed > Int.MAX_VALUE || entry.size > Int.MAX_VALUE) throw ZipFormatError("ZIP entry too large: ${entry.name}")
        val raw = source.read(start, entry.compressed.toInt())
        val out = when (entry.method) {
            0 -> raw
            8 -> inflate(raw, entry)
            else -> throw ZipFormatError("Unsupported ZIP compression method ${entry.method}: ${entry.name}")
        }
        if (out.size.toLong() != entry.size) throw ZipFormatError("ZIP entry size mismatch: ${entry.name}")
        return out
    }

    private fun inflate(raw: ByteArray, entry: ZipEntry): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArray(entry.size.toInt())
            var n = 0
            while (n < out.size) {
                val k = inflater.inflate(out, n, out.size - n)
                if (k == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            if (n != out.size || !inflater.finished() && inflater.inflate(ByteArray(1)) > 0) throw ZipFormatError("ZIP entry size mismatch: ${entry.name}")
            return out
        } catch (_: DataFormatException) {
            throw ZipFormatError("Invalid deflate data: ${entry.name}")
        } finally {
            inflater.end()
        }
    }

    override fun close() = source.close()

    companion object {
        const val MAX_TOTAL = 4L shl 30
        const val MAX_TEXT = 512L shl 20
        const val MAX_RATIO = 300L
        const val RATIO_FROM = 1L shl 20
        private val TEXT_ENTRY = Regex("\\.(xml|append|txt|lua|rawclobber|rawappend)$", RegexOption.IGNORE_CASE)

        private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private fun u16(b: ByteBuffer, i: Int) = b.getShort(i).toInt() and 0xffff
        private fun u32(b: ByteBuffer, i: Int) = b.getInt(i).toLong() and 0xffffffffL

        fun open(file: File): Zip {
            val source = FileSource(file)
            try { return open(source) } catch (e: Exception) { source.close(); throw e }
        }

        fun open(source: ZipSource): Zip {
            val tail = minOf(source.size, 65557L).toInt()
            val endBytes = source.read(source.size - tail, tail)
            val end = le(endBytes)
            var eocd = -1
            for (i in endBytes.size - 22 downTo 0) if (end.getInt(i) == 0x06054b50) { eocd = i; break }
            if (eocd < 0) throw ZipFormatError("Not a ZIP archive")
            var count = u16(end, eocd + 10).toLong()
            var cdSize = u32(end, eocd + 12)
            var cdOffset = u32(end, eocd + 16)
            if (count == 0xffffL || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) {
                val loc = eocd - 20
                if (loc < 0 || end.getInt(loc) != 0x07064b50) throw ZipFormatError("Invalid ZIP64 archive")
                val z64 = end.getLong(loc + 8)
                val rec = le(source.read(z64, 56))
                if (rec.capacity() < 56 || rec.getInt(0) != 0x06064b50) throw ZipFormatError("Invalid ZIP64 directory")
                count = rec.getLong(32); cdSize = rec.getLong(40); cdOffset = rec.getLong(48)
            }
            if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > source.size || cdSize > Int.MAX_VALUE) throw ZipFormatError("Truncated ZIP archive")
            val cdBytes = source.read(cdOffset, cdSize.toInt())
            val cv = le(cdBytes)
            val entries = ArrayList<ZipEntry>()
            var p = 0
            try {
                for (i in 0 until count) {
                    if (cv.getInt(p) != 0x02014b50) throw ZipFormatError("Invalid ZIP central directory")
                    val flags = u16(cv, p + 8)
                    val method = u16(cv, p + 10)
                    var compressed = u32(cv, p + 20)
                    var size = u32(cv, p + 24)
                    val nameLen = u16(cv, p + 28)
                    val extraLen = u16(cv, p + 30)
                    val commentLen = u16(cv, p + 32)
                    var offset = u32(cv, p + 42)
                    val nameBytes = cdBytes.copyOfRange(p + 46, p + 46 + nameLen)
                    val name = if (flags and 0x800 != 0) nameBytes.decodeToString() else cp437(nameBytes)
                    var e = p + 46 + nameLen
                    val stop = e + extraLen
                    while (e + 4 <= stop) {
                        val id = u16(cv, e)
                        val len = u16(cv, e + 2)
                        if (id == 1) {
                            var q = e + 4
                            if (size == 0xffffffffL) { size = cv.getLong(q); q += 8 }
                            if (compressed == 0xffffffffL) { compressed = cv.getLong(q); q += 8 }
                            if (offset == 0xffffffffL) offset = cv.getLong(q)
                        }
                        e += 4 + len
                    }
                    entries.add(ZipEntry(name, method, compressed, size, offset, flags and 1 != 0))
                    p += 46 + nameLen + extraLen + commentLen
                }
            } catch (_: IndexOutOfBoundsException) {
                throw ZipFormatError("Invalid ZIP central directory")
            }
            return Zip(source, entries)
        }
    }
}
