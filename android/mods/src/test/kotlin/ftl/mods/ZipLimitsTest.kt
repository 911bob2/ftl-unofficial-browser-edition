package ftl.mods

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry as JavaZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZipLimitsTest {
    private fun archive(vararg files: Pair<String, ByteArray>): Zip {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in files) {
                z.putNextEntry(JavaZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return Zip.open(BytesSource(out.toByteArray()))
    }

    @Test
    fun anOrdinaryModPasses() {
        assertNull(archive("data/a.xml.append" to "<a/>".repeat(1000).toByteArray(), "img/a.png" to Random(1).nextBytes(1 shl 20)).limitProblem())
    }

    @Test
    fun aHugelyCompressedEntryIsRefused() {
        assertNotNull(archive("data/a.xml.append" to ByteArray(8 shl 20)).limitProblem())
    }

    @Test
    fun aSmallEntryMayCompressWell() {
        assertNull(archive("data/a.xml" to ByteArray(512 shl 10)).limitProblem())
    }
}
