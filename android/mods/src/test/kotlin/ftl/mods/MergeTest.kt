package ftl.mods

import ftl.saves.Json
import ftl.saves.arr
import ftl.saves.bool
import ftl.saves.str
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The Kotlin merge against ftlman 0.7.4 ground truth, the same cases tests/mods-merge.mjs runs for the website. */
class MergeTest {
    private val vectors = Json.parse(File(System.getProperty("vectors")).readText()) as Json.Obj

    @Test
    fun appendCasesEqualFtlman() {
        var n = 0
        for (c in vectors["append"].arr()) {
            c as Json.Obj
            val name = c["name"].str()!!
            val want = c["expected"] as Json.Obj
            val got = runCatching { applyOneXml(c["base"].str()!!, c["script"].str()!!, c["kind"].str()!!) }
            if (want["ok"].bool() == true) {
                got.exceptionOrNull()?.let { fail("$name: ftlman succeeded, we failed: ${it.message}") }
                assertEquals(want["output"].str(), got.getOrNull(), name)
            } else assertTrue(got.isFailure, "$name: ftlman failed (${want["error"].str()}), we produced output")
            n++
        }
        assertTrue(n > 50)
    }

    @Test
    fun encodingsAndNewlines() {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        assertEquals("a", readEncodedText(b(0xef, 0xbb, 0xbf, 0x61)))
        assertEquals("ab", readEncodedText(b(0xff, 0xfe, 0x61, 0, 0x62, 0)))
        assertEquals("a", readEncodedText(b(0xfe, 0xff, 0, 0x61)))
        assertFailsWith<MergeError> { readEncodedText("aé".encodeToByteArray()) }
        assertEquals("a\r\nb\r\nc\rd\r\r\n", lfToCrlf("a\nb\r\nc\rd\r\r\n"))
    }

    @Test
    fun hyperspaceMetadata() {
        assertEquals(listOf("data/a.lua", "data/b.lua"), hyperspaceScripts(
            "<FTL><scripts><script>data/a.lua</script><!--<script>x</script>--></scripts><scripts>\n<script> data/b.lua </script></scripts></FTL>".encodeToByteArray()))
        val meta = parseMetadata("<metadata><title><![CDATA[ Multiverse ]]></title><version>5.5.1</version></metadata>".encodeToByteArray())
        assertEquals("Multiverse", meta.title)
        assertEquals("5.5.1", meta.version)
        assertNull(parseMetadata("<metadata><title> </title></metadata>".encodeToByteArray()).title)
    }

    @Test
    fun f1EqualsFtlmanPatch() {
        val f1 = vectors["f1"] as Json.Obj
        val decoder = Base64.getDecoder()
        val base = LinkedHashMap<String, ByteArray>()
        for (e in f1["base"].arr()) base[(e as Json.Obj)["name"].str()!!] = decoder.decode(e["bytes"].str())
        val archives = f1["archives"].arr().map { a ->
            a as Json.Obj
            MergeArchive(a["name"].str()!!, Zip.open(BytesSource(decoder.decode(a["zip"].str()))))
        }
        val result = mergeMods(base.keys.toList(), { base[it] ?: ByteArray(0) }, archives)
        val merged = LinkedHashMap(base)
        for (n in result.removed) merged.remove(n)
        for ((n, v) in result.files) merged[n] = v as ByteArray
        val want = (f1["files"] as Json.Obj).fields
        for ((n, h) in want) {
            val bytes = assertNotNull(merged[n], "F1 $n missing")
            assertEquals(h.str(), sha256(bytes), "F1 $n differs:\n${bytes.decodeToString()}")
        }
        for (n in merged.keys) assertTrue(n in want, "F1 extra file $n")
        assertTrue(result.warnings.isNotEmpty())

        // The lazy form names the archive entry instead of copying the bytes.
        val lazy = mergeMods(base.keys.toList(), { base[it] ?: ByteArray(0) }, archives, lazyBinary = true)
        for ((n, v) in lazy.files) if (v is ArchiveRef) assertEquals(want[n].str(), sha256(archives[v.archive].zip.read(v.entry)), n)
    }

    @Test
    fun refusesUnsafeAndUnsupportedArchives() {
        assertFailsWith<ZipFormatError> { Zip.open(BytesSource(ByteArray(100))) }
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("../evil.xml")); z.write("<a/>".encodeToByteArray()) }
        }.toByteArray()
        assertFailsWith<MergeError> { mergeMods(emptyList(), { ByteArray(0) }, listOf(MergeArchive("evil.zip", Zip.open(BytesSource(zip))))) }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
