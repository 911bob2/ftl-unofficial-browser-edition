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

    /** One HTTP exchange: a GET, or a POST of `post` as text/plain. Tests inject their own. */
    class Response(val status: Int, val body: ByteArray, val header: (String) -> String? = { null })
    fun interface Http {
        fun send(url: String, post: String?): Response
    }

    val http = Http { url, post ->
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

    private var sequence = Random.nextLong(1_000_000_000)
    private val STORAGE = Regex("^https://[\\w.-]+\\.userstorage\\.mega\\.co\\.nz/")

    /** Asks MEGA for a temporary download address. Errors are MegaError with a kind the launcher turns into a next step. */
    fun fileInfo(link: String, http: Http = this.http, attempts: Int = 3, sleep: (Long) -> Unit = Thread::sleep): Info {
        val parsed = parseLink(link)
        val keys = fileKey(parsed.key)
        val request = Json.write(Json.Arr(listOf(Json.Obj(mapOf("a" to Json.Str("g"), "g" to Json.Num(1.0), "ssl" to Json.Num(2.0),
            "p" to Json.Str(parsed.handle))))))
        var last: MegaError? = null
        for (attempt in 0 until attempts) {
            if (attempt > 0) sleep(1000L shl attempt)
            val response = try {
                http.send("$API?id=${sequence++}", request)
            } catch (e: IOException) {
                throw MegaError("network", "Could not connect to mega.nz.")
            }
            if (response.status == 402 && response.header("X-Hashcash") != null)
                throw MegaError("busy", "MEGA asks this connection for a proof-of-work challenge (it is under load). Try again later.")
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
            http.send("$url/$start-$end", null)
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
