package ftl.mods

import ftl.saves.Json
import ftl.saves.objOrNull
import ftl.saves.str
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The MEGA reader against the website's fixture (tests/mods-mega.mjs): a file encrypted as MEGA stores it, no network. */
class MegaTest {
    private val v = (Json.parse(File(System.getProperty("vectors")).readText()) as Json.Obj)["mega"].objOrNull()!!
    private fun s(key: String) = v[key].str()!!
    private fun bytes(key: String) = Base64.getDecoder().decode(s(key))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private val storage = "https://gfs1n1.userstorage.mega.co.nz/dl/f1-fixture"

    @Test
    fun linksAndKeys() {
        val link = Mega.parseLink(s("link"))
        assertEquals(s("handle"), link.handle)
        val keys = Mega.fileKey(link.key)
        assertEquals(s("aes"), hex(keys.aes))
        assertEquals(s("nonce"), hex(keys.nonce))
        assertEquals(s("handle"), Mega.parseLink("https://mega.nz/#!${s("handle")}!${s("link").substringAfter('#')}").handle)
        for (bad in listOf("https://mega.nz/file/short#x", "http://mega.nz/file/F1fixtur#" + "a".repeat(43),
            "https://evil.example/file/F1fixtur#" + "a".repeat(43), "https://mega.nz/folder/F1fixtur#" + "a".repeat(22)))
            assertEquals("bad-link", assertFailsWith<MegaError>(bad) { Mega.parseLink(bad) }.kind)
    }

    @Test
    fun contentAndAttributes() {
        val keys = Mega.fileKey(Mega.parseLink(s("link")).key)
        val cipher = bytes("cipher")
        for (size in listOf(16, 160, 256, 1024, cipher.size + 16)) {
            val out = java.io.ByteArrayOutputStream()
            var o = 0
            while (o < cipher.size) {
                out.write(Mega.decrypt(keys, cipher.copyOfRange(o, minOf(cipher.size, o + size)), o.toLong()))
                o += size
            }
            assertEquals(s("sha256"), sha(out.toByteArray()), "range size $size")
        }
        assertContentEquals(bytes("plain"), Mega.decrypt(keys, cipher, 0))
        val pattern = ByteArray(70000) { ((it * 31 + 7) and 255).toByte() }
        val patternCipher = bytes("patternCipher")
        assertContentEquals(pattern, Mega.decrypt(keys, patternCipher, 0))
        assertContentEquals(pattern.copyOfRange(65536, pattern.size), Mega.decrypt(keys, patternCipher.copyOfRange(65536, patternCipher.size), 65536))
        assertEquals("crypto", assertFailsWith<MegaError> { Mega.decrypt(keys, cipher.copyOfRange(5, 21), 5) }.kind)

        assertEquals(s("name"), Mega.decryptAttributes(s("attributes"), keys.aes))
        assertNull(Mega.decryptAttributes(s("attributes").dropLast(2), keys.aes))
    }

    private fun json(text: String) = Mega.Response(200, text.toByteArray())

    @Test
    fun apiAndStorage() {
        val cipher = bytes("cipher")
        val requests = mutableListOf<Pair<String, String?>>()
        val api = { answer: () -> Mega.Response -> Mega.Http { url, post, _ -> requests += url to post; answer() } }
        val info = Mega.fileInfo(s("link"), api { json("""[{"s":${cipher.size},"at":"${s("attributes")}","g":"$storage"}]""") })
        assertEquals(cipher.size.toLong(), info.size)
        assertEquals(s("name"), info.name)
        assertEquals(storage, info.url)
        val (url, post) = requests.single()
        assertEquals(true, url.startsWith(Mega.API + "?id="))
        assertEquals("""[{"a":"g","g":1,"ssl":2,"p":"${s("handle")}"}]""", post)

        val served = Mega.Http { u, _, _ ->
            val (a, b) = Regex("/(\\d+)-(\\d+)$").find(u)!!.destructured
            Mega.Response(200, cipher.copyOfRange(a.toInt(), b.toInt() + 1))
        }
        assertEquals(s("sha256"), sha(Mega.decrypt(info.keys, Mega.range(info.url, 0, cipher.size - 1L, served), 0)))

        for ((answer, kind) in listOf<Pair<() -> Mega.Response, String>>(
            { json("[-9]") } to "removed", { json("[-16]") } to "removed", { json("[-17]") } to "quota", { json("[-4]") } to "rate",
            { json("""{"err":-11}""") } to "denied", { json("""[{"s":1,"g":"$storage","tl":600}]""") } to "quota",
            { json("""[{"s":1,"g":"https://evil.example/dl/x"}]""") } to "api",
            { Mega.Response(402, ByteArray(0)) { if (it == "X-Hashcash") "1:1:1:x" else null } } to "busy",
            { throw IOException("Failed to connect") } to "network", { Mega.Response(403, "nope".toByteArray()) } to "http",
        )) assertEquals(kind, assertFailsWith<MegaError>(kind) { Mega.fileInfo(s("link"), api(answer), attempts = 1) }.kind)
        assertEquals(600L, assertFailsWith<MegaError> { Mega.fileInfo(s("link"), api { json("""[{"s":1,"g":"$storage","tl":600}]""") }) }.retryAfter)
        var tries = 0
        val retried = Mega.fileInfo(s("link"), api { if (++tries < 2) json("[-3]") else json("""[{"s":5,"g":"$storage"}]""") }, sleep = {})
        assertEquals(5L, retried.size)
        assertEquals(2, tries)

        for ((response, kind) in listOf(Mega.Response(509, ByteArray(0)) to "quota", Mega.Response(404, ByteArray(0)) to "expired",
            Mega.Response(200, ByteArray(3)) to "network"))
            assertEquals(kind, assertFailsWith<MegaError> { Mega.range(storage, 0, 15, { _, _, _ -> response }) }.kind)
    }

    /**
     * MEGA's proof of work, as its web client and SDK answer it (tests/mods-mega.mjs has the same vector): a fixed 48-byte
     * token at easiness 160 whose smallest answer (nonce 271, "AAABDw") an independent hashlib run of the SDK's
     * algorithm found; the request is sent again with "X-Hashcash: 1:<token>:<answer>"; a fourth challenge stops.
     */
    @Test
    fun proofOfWork() {
        val token = "CzBVep_E6Q4zWH2ix-wRNluApcrvFDleg6jN8hc8YYar0PUaP2SJrtP4HUJnjLHW"
        assertEquals(listOf(8L, 1016L, 1024L, 74752L, 8519680L, 16777216L, 127L shl 24), listOf(0, 63, 64, 100, 160, 192, 255).map { Mega.hashcashThreshold(it) })
        for (bad in listOf(null, "", "2:160:1:$token", "1:256:1:$token", "1:-1:1:$token", "1:160:1:" + token.drop(1), "1:160:$token", "1:1:1:x"))
            assertNull(Mega.hashcashChallenge(bad), bad)
        val challenge = Mega.hashcashChallenge(" 1:160:1731410499:$token\n")!!
        assertEquals(160, challenge.easiness)
        assertEquals("AAABDw", Mega.solveHashcash(challenge, threads = 1))
        assertEquals(true, Mega.verifyHashcash(challenge, "AAABDw"))
        assertEquals(false, Mega.verifyHashcash(challenge, "AAABDg"))
        val four = Mega.solveHashcash(challenge, threads = 4)
        assertEquals(true, Mega.verifyHashcash(challenge, four), four)
        assertEquals("busy", assertFailsWith<MegaError> { Mega.solveHashcash(Mega.hashcashChallenge("1:0:1:$token")!!, threads = 1, budgetMs = 300) }.kind)

        val sent = mutableListOf<Pair<String, String?>>()
        val proofs = mutableListOf<Int>()
        val challenged = Mega.Http { url, _, headers ->
            sent += url to headers["X-Hashcash"]
            val cash = headers["X-Hashcash"] ?: return@Http Mega.Response(402, ByteArray(0)) { if (it == "X-Hashcash") "1:160:1731410499:$token" else null }
            val (v, t, answer) = cash.split(':')
            assertEquals("1", v); assertEquals(token, t); assertEquals(true, Mega.verifyHashcash(challenge, answer))
            json("""[{"s":5,"g":"$storage"}]""")
        }
        assertEquals(5L, Mega.fileInfo(s("link"), challenged, onProof = { proofs += it }).size)
        assertEquals(2, sent.size)
        assertEquals(sent[0].first, sent[1].first, "the same request again")
        assertEquals(listOf(160), proofs)
        var asked = 0
        val always = Mega.Http { _, _, _ -> asked++; Mega.Response(402, ByteArray(0)) { if (it == "X-Hashcash") "1:160:1:$token" else null } }
        assertEquals("busy", assertFailsWith<MegaError> { Mega.fileInfo(s("link"), always, attempts = 1) }.kind)
        assertEquals(4, asked, "three answers, then it stops")
    }
}
