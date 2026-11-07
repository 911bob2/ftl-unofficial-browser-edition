package ftl.android.app.net

import ftl.android.app.Identity
import ftl.saves.CloudSave
import ftl.saves.Json
import ftl.saves.LockInfo
import ftl.saves.obj
import ftl.saves.str
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** An answer the server refused, with its status and `code` (docs/SAVES.md lists the save codes). */
class ApiError(val status: Int, val code: String?, message: String, val body: Json.Obj?) : IOException(message)

/**
 * The website's API with the website's session: the same sign-in, cookie, CSRF header and rules
 * (docs/SECURITY_HARDENING.md). Every call sends the instance and device headers the save locks use.
 * Blocking; call from a background thread.
 */
class Api(val base: String, private val identity: Identity, private val secure: SecureStore) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private var csrf: String? = null
    private val origin = URI(base)

    /** The account this session belongs to: the server refuses a request of a session that became another account's. */
    @Volatile var accountId: String? = null

    init {
        secure.get("cookies:$base")?.let { saved ->
            runCatching {
                for (line in saved.lines()) if (line.isNotBlank()) HttpCookie.parse("Set-Cookie2:$line").forEach { cookies.cookieStore.add(origin, it) }
            }
        }
    }

    private fun persistCookies() {
        val lines = cookies.cookieStore.get(origin).filter { !it.hasExpired() }.joinToString("\n") { c ->
            buildString {
                append(c.name).append("=\"").append(c.value).append('"')
                append(";Path=\"").append(c.path ?: "/").append('"')
                if (c.secure) append(";Secure")
                if (c.maxAge > 0) append(";Max-Age=").append(c.maxAge)
                append(";Version=1")
            }
        }
        secure.put("cookies:$base", lines.ifEmpty { null })
    }

    fun signedOutLocally() {
        cookies.cookieStore.removeAll()
        csrf = null
        secure.put("cookies:$base", null)
    }

    fun request(method: String, path: String, body: Json? = null, headers: Map<String, String> = emptyMap(), retried: Boolean = false): Response {
        val mutation = method != "GET"
        if (mutation && csrf == null && path != "/api/auth/csrf") csrf = token()
        val url = URL(base + path)
        val c = url.openConnection() as HttpURLConnection
        try {
            // Android's HttpURLConnection (OkHttp inside) accepts PATCH.
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-FTL-Instance", identity.instanceId)
            // The device name goes only where a save records it.
            if (mutation && path.startsWith("/api/saves")) c.setRequestProperty("X-FTL-Device", identity.header)
            if (!path.startsWith("/api/auth")) accountId?.let { c.setRequestProperty("X-FTL-Account", it) }
            val cookieHeader = cookies.cookieStore.get(url.toURI()).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookieHeader.isNotEmpty()) c.setRequestProperty("Cookie", cookieHeader)
            if (mutation) csrf?.let { c.setRequestProperty("X-CSRF-TOKEN", it) }
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                val bytes = Json.write(body).encodeToByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            } else if (method != "DELETE" && mutation) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val status = c.responseCode
            c.headerFields["Set-Cookie"]?.forEach { header -> runCatching { HttpCookie.parse(header).forEach { cookies.cookieStore.add(url.toURI(), it) } } }
            persistCookies()
            val text = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() } ?: ""
            val json = text.takeIf { it.isNotBlank() && (c.contentType ?: "").contains("json") }?.let { runCatching { Json.parse(it) }.getOrNull() }
            if (status >= 400) {
                val o = json as? Json.Obj
                if (status == 400 && o?.get("error").str() == "Invalid CSRF token" && !retried) {
                    csrf = token()
                    return request(method, path, body, headers, true)
                }
                throw ApiError(status, o?.get("code").str(), o?.get("error").str() ?: "Request failed ($status)", o)
            }
            return Response(status, json, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    class Response(val status: Int, val json: Json?, val etag: String?)

    /** Streams a file (server game assets) to `target`; progress is bytes so far. */
    fun download(path: String, target: java.io.File, progress: (Long) -> Unit) {
        val url = URL(base + path)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity")
            accountId?.let { c.setRequestProperty("X-FTL-Account", it) }
            val cookieHeader = cookies.cookieStore.get(url.toURI()).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookieHeader.isNotEmpty()) c.setRequestProperty("Cookie", cookieHeader)
            if (c.responseCode != 200) throw ApiError(c.responseCode, null, "Download failed (${c.responseCode})", null)
            val tmp = java.io.File(target.parentFile, target.name + ".part")
            var done = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Could not store ${target.name}")
        } finally {
            c.disconnect()
        }
    }

    private fun token(): String = (request("GET", "/api/auth/csrf").json as Json.Obj)["token"].str() ?: throw IOException("No CSRF token")

    // ---- account ----

    data class Me(val id: String, val username: String, val role: String, val serverAssets: Boolean, val mustChangePassword: Boolean)

    fun login(username: String, password: String): Boolean {
        val body = obj("username" to username, "password" to password)
        val r = try {
            request("POST", "/api/auth/login", body)
        } catch (e: ApiError) {
            // A stale session cookie: the server clears it and asks to sign in again.
            if (e.status != 409) throw e
            csrf = null
            request("POST", "/api/auth/login", body)
        }
        csrf = null
        return ((r.json as? Json.Obj)?.get("mustChangePassword") as? Json.Bool)?.value ?: false
    }

    /** Public registration (POST /api/auth/register): an External user, signed in by the answer. */
    fun register(username: String, password: String) {
        val body = obj("username" to username, "password" to password)
        try {
            request("POST", "/api/auth/register", body)
        } catch (e: ApiError) {
            // A stale session cookie: the server clears it and asks to try again, as for sign-in.
            if (e.status != 409) throw e
            csrf = null
            request("POST", "/api/auth/register", body)
        }
        csrf = null
    }

    fun me(): Me {
        val o = request("GET", "/api/auth/me").json as Json.Obj
        val caps = o["capabilities"]
        val serverAssets = when (caps) {
            is Json.Obj -> (caps["serverAssets"] as? Json.Bool)?.value ?: false
            is Json.Arr -> caps.items.any { it.str() == "serverAssets" }
            else -> false
        }
        return Me(o["id"].str()!!, o["username"].str() ?: "", o["role"].str() ?: "", serverAssets, (o["mustChangePassword"] as? Json.Bool)?.value ?: false)
    }

    fun logout() {
        runCatching { request("POST", "/api/auth/logout") }
        signedOutLocally()
    }

    fun config(): Json.Obj = request("GET", "/api/game/config").json as Json.Obj

    // ---- saves (docs/SAVES.md) ----

    class SaveList(val saves: List<CloudSave>, val deleted: Set<String>)

    fun saves(): SaveList {
        val o = request("GET", "/api/saves").json as Json.Obj
        val saves = (o["saves"] as? Json.Arr)?.items.orEmpty().map { CloudSave.fromJson(it) }
        val deleted = (o["deleted"] as? Json.Arr)?.items.orEmpty().mapNotNull { (it as? Json.Obj)?.get("id").str() }.toSet()
        return SaveList(saves, deleted)
    }

    /** A save as the cloud has it: its metadata, run and profile. */
    data class SaveData(val cloud: CloudSave, val run: String?, val profile: String?)

    fun save(id: String): SaveData {
        val o = request("GET", "/api/saves/$id").json as Json.Obj
        return SaveData(CloudSave.fromJson(o), o["run"].str(), o["profile"].str())
    }

    /** Creates (revision 0) or updates a save; the new revision. */
    fun putSave(id: String, revision: Long, body: Json.Obj): Long {
        val r = request("PUT", "/api/saves/$id", body, mapOf("If-Match" to "\"$revision\""))
        return ((r.json as Json.Obj)["revision"] as Json.Num).value.toLong()
    }

    fun rename(id: String, name: String) {
        request("PATCH", "/api/saves/$id", obj("name" to name))
    }

    fun deleteSave(id: String, override: Boolean = false) {
        request("DELETE", "/api/saves/$id" + if (override) "?override=true" else "")
    }

    /** acknowledge: the player has seen this instance's takeover notice; take the lock if it is free. */
    fun lock(id: String, override: Boolean, acknowledge: Boolean = false): LockInfo =
        LockInfo.fromJson(request("POST", "/api/saves/$id/lock", obj("override" to override, "acknowledge" to acknowledge)).json)!!

    fun unlock(id: String) {
        request("POST", "/api/saves/$id/unlock")
    }

    /** The save's recovery snapshots, newest first. */
    fun snapshots(id: String): List<ftl.saves.Snapshot> = ftl.saves.Snapshots.parse(request("GET", "/api/saves/$id/snapshots").json)

    /** Makes a snapshot the save's newest revision (this device must hold the lock); the new revision. */
    fun restore(id: String, revision: Long, current: Long): Long {
        val r = request("POST", "/api/saves/$id/snapshots/$revision/restore", null, mapOf("If-Match" to "\"$current\""))
        return ((r.json as Json.Obj)["revision"] as Json.Num).value.toLong()
    }
}
