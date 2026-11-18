package ftl.mods

import ftl.saves.Json
import ftl.saves.long
import ftl.saves.objOrNull
import ftl.saves.str
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** kind: network | removed | quota | rate | busy | denied | bad-link | http | crypto | api | expired, as web/mods/mega.mjs. */
class MegaError(val kind: String, message: String, val retryAfter: Long? = null, val code: Int? = null) : IOException(message)

/**
 * The website's MEGA public-file reader (web/mods/mega.mjs) for the device: link parsing, the "g" API call, AES-128-CTR
 * decryption of each range and the AES-128-CBC attributes (file name). The integrity check is the catalogue SHA-256 of
 * the decrypted file, not MEGA's chunk MACs.
 *   link  https://mega.nz/file/<handle>#<key>  (or the legacy https://mega.nz/#!<handle>!<key>)
 *   key   32 bytes = 8 big-endian uint32 k0..k7; AES key = k0^k4, k1^k5, k2^k6, k3^k7; CTR nonce = k4, k5
 */
object Mega {
    const val API = "https://g.api.mega.co.nz/cs"

    class Link(val handle: String, val key: ByteArray)
    class Keys(val aes: ByteArray, val nonce: ByteArray)
    class Info(val handle: String, val size: Long, val url: String, val name: String?, val keys: Keys)

    /** One HTTP exchange: a GET, or a POST of `post` as text/plain, with extra request [headers]. Tests inject their own. */
    class Response(val status: Int, val body: ByteArray, val header: (String) -> String? = { null })
    fun interface Http {
        fun send(url: String, post: String?, headers: Map<String, String>): Response
    }

    val http = Http { url, post, extra ->
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.useCaches = false
            c.instanceFollowRedirects = false
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                for ((k, v) in extra) c.setRequestProperty(k, v)
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            val status = c.responseCode
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
            val body = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            Response(status, body) { name -> headers[name.lowercase()]?.firstOrNull() }
        } finally {
            c.disconnect()
        }
    }

    private val CODES = mapOf(
        -1 to ("api" to "MEGA reported an internal error."), -2 to ("bad-link" to "MEGA rejected the link."),
        -3 to ("rate" to "MEGA is busy. Try again in a moment."),
        -4 to ("rate" to "MEGA is limiting requests from your connection. Wait a few minutes and retry."),
        -5 to ("api" to "MEGA could not complete the request."), -6 to ("rate" to "Too many requests to MEGA. Wait a few minutes and retry."),
        -8 to ("removed" to "The MEGA link has expired."), -9 to ("removed" to "The file is no longer on MEGA (deleted or the link changed)."),
        -11 to ("denied" to "MEGA refused access to the file."), -14 to ("bad-link" to "The MEGA link key is wrong."),
        -16 to ("removed" to "MEGA has taken this file down."), -17 to ("quota" to "Your MEGA transfer quota is used up."),
        -18 to ("busy" to "MEGA is temporarily unavailable. Try again later."),
        -19 to ("rate" to "Too many connections to MEGA. Close other MEGA downloads and retry."),
    )

    fun error(code: Int, retryAfter: Long? = null): MegaError {
        val (kind, message) = CODES[code] ?: ("api" to "MEGA returned error $code.")
        return MegaError(kind, message, retryAfter, code)
    }

    private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace(",", "").trimEnd('='))

    private val LINK = Regex("^https://mega\\.(?:nz|co\\.nz)/(?:file/([\\w-]{8})#([\\w-]{43})|#!([\\w-]{8})!([\\w-]{43}))$")

    fun parseLink(link: String): Link {
        val m = LINK.find(link.trim()) ?: throw MegaError("bad-link", "This is not a MEGA file link.")
        val key = b64(m.groupValues[2].ifEmpty { m.groupValues[4] })
        if (key.size != 32) throw MegaError("bad-link", "The MEGA link key is incomplete.")
        return Link(m.groupValues[1].ifEmpty { m.groupValues[3] }, key)
    }

    fun fileKey(key: ByteArray): Keys = Keys(ByteArray(16) { (key[it].toInt() xor key[16 + it].toInt()).toByte() }, key.copyOfRange(16, 24))

    /** The file name in the API's `at` field ("MEGA" + JSON, zero padded, AES-CBC with a zero IV); null when it does not decode. */
    fun decryptAttributes(at: String, aes: ByteArray): String? {
        val data = runCatching { b64(at) }.getOrNull() ?: return null
        if (data.isEmpty() || data.size % 16 != 0) return null
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(ByteArray(16)))
        val text = cipher.doFinal(data).decodeToString().trimEnd('\u0000')
        if (!text.startsWith("MEGA{")) return null
        return runCatching { Json.parse(text.substring(4)).objOrNull()?.get("n").str() }.getOrNull()
    }

    /** Decrypts encrypted bytes that start at `offset` (a multiple of 16) in the file. */
    fun decrypt(keys: Keys, bytes: ByteArray, offset: Long): ByteArray {
        if (offset % 16 != 0L) throw MegaError("crypto", "MEGA ranges must start on a 16-byte boundary.")
        val counter = keys.nonce.copyOf(16)
        val block = offset / 16
        for (i in 0 until 8) counter[8 + i] = (block ushr (56 - 8 * i)).toByte()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.aes, "AES"), IvParameterSpec(counter))
        return cipher.doFinal(bytes)
    }

    // ---- MEGA's proof of work (HTTP 402 with X-Hashcash), as MEGA's own public clients answer it: the web client
    // (meganz/webclient js/utils/api.js, gencash) and the SDK (meganz/sdk src/hashcash.cpp, src/posix/net.cpp); the
    // website's web/mods/mega.mjs does the same. The challenge "1:<easiness>:<time>:<token>" (token: 64 base64url
    // characters, 48 bytes) is answered by a 4-byte prefix whose SHA-256 over prefix + the token 262144 times (12 MiB)
    // starts with a big-endian 32-bit number at or below the easiness's threshold; the request goes again with
    // "X-Hashcash: 1:<token>:<base64url prefix>".

    const val HASHCASH_REPEAT = 262144
    /** The SDK gives up on a challenge after this long (its token lives 300 s; its own budget is 100-150 s). */
    const val HASHCASH_BUDGET_MS = 270_000L
    private const val HASHCASH_SOLVES = 3

    class Challenge(val token: String, val bytes: ByteArray, val easiness: Int)

    /** The challenge of an X-Hashcash answer header, or null when it is not one (the SDK's checks). */
    fun hashcashChallenge(header: String?): Challenge? {
        val p = header?.trim()?.split(':') ?: return null
        if (p.size != 4 || p[0].toIntOrNull() != 1) return null
        val easiness = p[1].toIntOrNull() ?: return null
        if (easiness !in 0..255 || p[3].length != 64 || !p[3].all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(p[3]) }.getOrNull() ?: return null
        return if (bytes.size == 48) Challenge(p[3], bytes, easiness) else null
    }

    /** The largest acceptable first word of the hash: ((e & 63) * 2 + 1) << ((e >> 6) * 7 + 3), as both clients compute it. */
    fun hashcashThreshold(easiness: Int): Long = (((easiness and 63) shl 1) + 1).toLong() shl ((easiness shr 6) * 7 + 3)

    /** The token 262144 times: the part of every attempt's input after the prefix (shared by the threads, read only). */
    private fun tokenArea(bytes: ByteArray): ByteArray {
        val area = ByteArray(HASHCASH_REPEAT * 48)
        System.arraycopy(bytes, 0, area, 0, 48)
        var filled = 48
        while (filled < area.size) { val n = minOf(filled, area.size - filled); System.arraycopy(area, 0, area, filled, n); filled += n }
        return area
    }

    private fun firstWord(digest: java.security.MessageDigest, prefix: ByteArray, area: ByteArray): Long {
        digest.update(prefix)
        digest.update(area)
        val h = digest.digest()
        return ((h[0].toLong() and 255) shl 24) or ((h[1].toLong() and 255) shl 16) or ((h[2].toLong() and 255) shl 8) or (h[3].toLong() and 255)
    }

    /** Whether [solution] (base64url of 4 bytes) answers the challenge (the SDK's validateHashcash). */
    fun verifyHashcash(challenge: Challenge, solution: String): Boolean {
        val prefix = runCatching { Base64.getUrlDecoder().decode(solution) }.getOrNull() ?: return false
        if (prefix.size != 4) return false
        return firstWord(java.security.MessageDigest.getInstance("SHA-256"), prefix, tokenArea(challenge.bytes)) <= hashcashThreshold(challenge.easiness)
    }

    /**
     * Finds the prefix on [threads] threads (thread w tries the nonces w, w + threads, ..., big-endian, as the SDK
     * does). Throws MegaError("busy") after [budgetMs], [InterruptedException] when [cancelled].
     */
    fun solveHashcash(challenge: Challenge, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                      budgetMs: Long = HASHCASH_BUDGET_MS, cancelled: () -> Boolean = { false }): String {
        val area = tokenArea(challenge.bytes)
        val threshold = hashcashThreshold(challenge.easiness)
        val end = System.currentTimeMillis() + budgetMs
        val found = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val workers = (0 until threads).map { w ->
            Thread({
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val prefix = ByteArray(4)
                var n = w.toLong()
                while (n <= 0xffffffffL && found.get() == null && !stop.get()) {
                    prefix[0] = (n ushr 24).toByte(); prefix[1] = (n ushr 16).toByte(); prefix[2] = (n ushr 8).toByte(); prefix[3] = n.toByte()
                    if (firstWord(digest, prefix, area) <= threshold) found.compareAndSet(null, prefix.copyOf())
                    n += threads
                }
            }, "mega-hashcash-$w").apply { isDaemon = true; start() }
        }
        try {
            while (workers.any { it.isAlive }) {
                if (found.get() != null) break
                if (cancelled()) throw InterruptedException("cancelled")
                if (System.currentTimeMillis() > end) throw MegaError("busy", "MEGA asks this connection for more proof-of-work than this device managed in time (it is under load). Try again later.")
                workers.first { it.isAlive }.join(50)
            }
        } finally {
            stop.set(true)
        }
        val prefix = found.get() ?: throw MegaError("busy", "MEGA's proof-of-work challenge has no answer.")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(prefix)
    }

    private var sequence = Random.nextLong(1_000_000_000)
    private val STORAGE = Regex("^https://[\\w.-]+\\.userstorage\\.mega\\.co\\.nz/")

    /** Asks MEGA for a temporary download address. Errors are MegaError with a kind the launcher turns into a next step. */
    fun fileInfo(link: String, http: Http = this.http, attempts: Int = 3, sleep: (Long) -> Unit = Thread::sleep,
                 onProof: (easiness: Int) -> Unit = {}, solve: (Challenge) -> String = { solveHashcash(it) }): Info {
        val parsed = parseLink(link)
        val keys = fileKey(parsed.key)
        val request = Json.write(Json.Arr(listOf(Json.Obj(mapOf("a" to Json.Str("g"), "g" to Json.Num(1.0), "ssl" to Json.Num(2.0),
            "p" to Json.Str(parsed.handle))))))
        // One request; a 402 challenge is answered and the request sent again with the answer (at most three times).
        fun post(): Response {
            val url = "$API?id=${sequence++}"
            var cash: String? = null
            var solved = 0
            while (true) {
                val response = try {
                    http.send(url, request, cash?.let { mapOf("X-Hashcash" to it) } ?: emptyMap())
                } catch (e: IOException) {
                    throw MegaError("network", "Could not connect to mega.nz.")
                }
                if (response.status != 402) return response
                val challenge = hashcashChallenge(response.header("X-Hashcash"))
                    ?: throw MegaError("busy", "MEGA answered 402 without a proof-of-work challenge it can be answered with. Try again later.")
                if (solved++ >= HASHCASH_SOLVES) throw MegaError("busy", "MEGA keeps asking this connection for proof-of-work (it is under load). Try again later.")
                onProof(challenge.easiness)
                cash = "1:${challenge.token}:${solve(challenge)}"
            }
        }
        var last: MegaError? = null
        for (attempt in 0 until attempts) {
            if (attempt > 0) sleep(1000L shl attempt)
            val response = post()
            if (response.status !in 200..299) {
                last = MegaError(if (response.status == 429) "rate" else "http", "MEGA answered HTTP ${response.status}.")
                if (response.status >= 500 || response.status == 429) continue
                throw last
            }
            val body = runCatching { Json.parse(response.body.decodeToString()) }.getOrElse {
                last = MegaError("api", "MEGA sent an unreadable answer.")
                continue
            }
            val r = if (body is Json.Arr) body.items.firstOrNull() else body
            val code = (r as? Json.Num ?: body as? Json.Num)?.value?.toInt()
                ?: r.objOrNull()?.let { (it["err"] ?: it["e"]).long()?.toInt() }
            if (code != null && code < 0) {
                last = error(code)
                if (code == -3) continue
                throw last
            }
            val o = r.objOrNull()
            val size = (o?.get("s") as? Json.Num)?.value?.toLong()
            val url = o?.get("g").str()
            if (o == null || size == null || url == null) {
                last = MegaError("api", "MEGA did not return a download address.")
                continue
            }
            val wait = o["tl"].long() ?: 0
            if (wait > 0) throw error(-17, wait)
            if (!STORAGE.containsMatchIn(url)) throw MegaError("api", "MEGA returned an unexpected download host.")
            val name = o["at"].str()?.let { runCatching { decryptAttributes(it, keys.aes) }.getOrNull() }
            return Info(parsed.handle, size, url, name, keys)
        }
        throw last ?: MegaError("api", "MEGA could not complete the request.")
    }

    /** Encrypted bytes [start, end] (inclusive) from a MEGA storage address. */
    fun range(url: String, start: Long, end: Long, http: Http = this.http): ByteArray {
        val response = try {
            http.send("$url/$start-$end", null, emptyMap())
        } catch (e: IOException) {
            throw MegaError("network", "The connection to mega.nz was interrupted.")
        }
        if (response.status == 509) throw error(-17, response.header("Retry-After")?.toLongOrNull())
        if (response.status == 403 || response.status == 404) throw MegaError("expired", "The MEGA download address expired.")
        if (response.status !in 200..299) throw MegaError("http", "mega.nz answered HTTP ${response.status}.")
        if (response.body.size.toLong() != end - start + 1) throw MegaError("network", "mega.nz sent an incomplete block.")
        return response.body
    }
}
