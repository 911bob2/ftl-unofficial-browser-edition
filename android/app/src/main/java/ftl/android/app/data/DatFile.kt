package ftl.android.app.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * An FTL `ftl.dat` (PKG format, big-endian): the index the website's web/dat.mjs builds, with the same checks
 * (uncompressed entries only, names safe and unique, extents inside the file). Reads are positional, so any thread
 * may read at once.
 */
class DatFile(val file: File) : AutoCloseable {
    class Entry(val offset: Long, val length: Int)

    val entries: Map<String, Entry>
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    init {
        entries = try { index(file) } catch (e: Exception) { channel.close(); throw e }
    }

    fun read(name: String): ByteArray? {
        val e = entries[name] ?: return null
        val out = ByteBuffer.allocate(e.length)
        var pos = e.offset
        while (out.hasRemaining()) {
            val n = channel.read(out, pos)
            if (n < 0) throw java.io.EOFException("Truncated game file: $name")
            pos += n
        }
        return out.array()
    }

    override fun close() = channel.close()

    companion object {
        private const val MAGIC = 0x504b470a

        fun index(file: File): Map<String, Entry> {
            RandomAccessFile(file, "r").use { f ->
                if (f.length() < 16) throw InvalidDat("The file is truncated. Choose an unmodified ftl.dat.")
                if (f.readInt() != MAGIC || f.readUnsignedShort() != 16 || f.readUnsignedShort() != 20)
                    throw InvalidDat("Unsupported file format. Choose an unmodified ftl.dat.")
                val count = f.readInt()
                val nameSize = f.readInt()
                if (count < 1 || count > 100000 || nameSize < 1 || nameSize > 10000000) throw InvalidDat("Invalid game file index.")
                val end = 16L + count * 20L + nameSize
                if (end > f.length()) throw InvalidDat("Invalid game file index.")
                val table = ByteArray(count * 20)
                val names = ByteArray(nameSize)
                f.readFully(table)
                f.readFully(names)
                val t = ByteBuffer.wrap(table)
                val out = LinkedHashMap<String, Entry>(count * 2)
                for (i in 0 until count) {
                    t.int
                    val flagName = t.int
                    val offset = t.int.toLong() and 0xffffffffL
                    val compressed = t.int
                    val length = t.int
                    val start = flagName and 0xffffff
                    if ((flagName ushr 24) != 0 || compressed != length) throw InvalidDat("Compressed entries are unsupported.")
                    if (offset < end || length < 0 || offset + length > f.length() || start >= nameSize) throw InvalidDat("Invalid resource bounds.")
                    var stop = start
                    while (stop < nameSize && names[stop].toInt() != 0) stop++
                    if (stop == nameSize) throw InvalidDat("Invalid resource name.")
                    val name = String(names, start, stop - start, Charsets.UTF_8)
                    if (name.isEmpty() || name.startsWith("/") || name.contains("..") || name.contains('\\') || name.any { it < ' ' } || name in out)
                        throw InvalidDat("Unsafe or duplicate resource name.")
                    out[name] = Entry(offset, length)
                }
                return out
            }
        }
    }
}

class InvalidDat(message: String) : Exception(message)

fun isAudio(name: String) = name.endsWith(".ogg") || name.endsWith(".wav")
