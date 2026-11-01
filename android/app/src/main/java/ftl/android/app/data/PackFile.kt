package ftl.android.app.data

import ftl.saves.Json
import ftl.saves.long
import ftl.saves.str
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The server's prepared game files (tools/PrepareAssets.java `boot.pack`): "FTLB", a big-endian JSON length, the
 * JSON entry list `[{name, hash, offset, length}]`, then the data. Audio is separate (one file per sound by hash).
 */
class PackFile(file: File) : AutoCloseable {
    val entries: Map<String, DatFile.Entry>
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    init {
        entries = try {
            RandomAccessFile(file, "r").use { f ->
                if (f.length() < 8 || f.readInt() != MAGIC) throw InvalidDat("Invalid game files from the server.")
                val n = f.readInt()
                if (n < 2 || n > f.length() - 8) throw InvalidDat("Invalid game files from the server.")
                val json = ByteArray(n)
                f.readFully(json)
                val base = 8L + n
                val out = LinkedHashMap<String, DatFile.Entry>()
                for (e in (Json.parse(json.decodeToString()) as Json.Arr).items) {
                    val o = e as Json.Obj
                    val name = o["name"].str() ?: continue
                    val offset = o["offset"].long() ?: continue
                    val length = o["length"].long() ?: continue
                    if (name.startsWith("/") || name.contains("..") || offset < 0 || length < 0 || base + offset + length > f.length())
                        throw InvalidDat("Invalid game files from the server.")
                    out[name] = DatFile.Entry(base + offset, length.toInt())
                }
                out
            }
        } catch (e: Exception) {
            channel.close()
            throw e
        }
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
        const val MAGIC = 0x46544c42
    }
}
