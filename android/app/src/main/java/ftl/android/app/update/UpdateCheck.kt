package ftl.android.app.update

import ftl.saves.Json
import ftl.saves.arr
import ftl.saves.bool
import ftl.saves.objOrNull
import ftl.saves.str
import java.io.IOException
import java.net.URI

/**
 * Release versions as the tags and `versionName` write them: numeric parts, `v` optional, a missing part is 0. The
 * app's own version may carry a suffix after `-` or `+` ("2.0.1-beta", "2.0.0-spoofed"): a build of that version that
 * comes before its release, as in semver. A tag with a suffix is not a release we offer.
 */
object Versions {
    /** The numeric parts of "v2.0.1" or "2.0", or null for anything else ("2.0-beta", "", "latest"). */
    fun parse(text: String?): List<Int>? {
        val s = text?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (s.isEmpty()) return null
        return s.split('.').map { p -> if (p.isEmpty() || p.length > 9 || p.any { it !in '0'..'9' }) return null else p.toInt() }
    }

    /** The app's own version: [parse] of its part before a `-` or `+` suffix, and whether it had one. */
    fun parseCurrent(text: String?): Pair<List<Int>, Boolean>? {
        val s = text?.trim() ?: return null
        val cut = s.indexOfFirst { it == '-' || it == '+' }
        if (cut == 0) return null
        val parts = parse(if (cut > 0) s.substring(0, cut) else s) ?: return null
        return parts to (cut > 0)
    }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Whether [tag] is a later release than [current]; never when either does not parse. */
    fun isNewer(tag: String?, current: String?): Boolean {
        val t = parse(tag) ?: return false
        val (c, suffixed) = parseCurrent(current) ?: return false
        val order = compare(t, c)
        return order > 0 || (order == 0 && suffixed)
    }

    /** "v2.0.1" as the launcher shows it: "2.0.1". */
    fun shown(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")
}

/** The latest release as last seen: its tag, its page, and whether it is one to offer (an APK, a page we open). */
data class Release(val tag: String, val url: String, val offered: Boolean) {
    val version: String get() = Versions.shown(tag)
}

/** The few stored values of the check (Android: the "updates" preferences; tests: a map). */
interface KeyValues {
    fun get(key: String): String?
    fun put(values: Map<String, String?>)
}

/** One HTTP answer: the status, the ETag header and the body (null for a 304). */
class HttpAnswer(val status: Int, val etag: String?, val body: String?)

/** A GET of [url] with [headers]; throws IOException when offline or the connection fails. */
fun interface Fetch {
    fun get(url: String, headers: Map<String, String>): HttpAnswer
}

/**
 * Whether a newer release of the app is out, asked of GitHub's releases/latest API directly (docs/ANDROID.md, "Updates"). The
 * automatic check runs at most once a day, counted from the last attempt, so that a failure is not retried before the next
 * day; a repeat check sends the last ETag, so an unchanged release is a 304, which does not count against GitHub's limit.
 * Only the app's version goes out, in the User-Agent. Drafts and pre-releases never come from /releases/latest; one that
 * does anyway is not offered, nor is a release without an APK or with a tag that does not parse.
 *
 * Blocking; call from a background thread.
 */
class UpdateCheck(
    private val values: KeyValues,
    private val fetch: Fetch,
    val current: String,
    /** The debug build's stand-in for GitHub (a local stub), or null for GitHub. */
    private val testUrl: () -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    sealed class Result {
        object UpToDate : Result()
        class Available(val release: Release) : Result()
        class Failed(val reason: String) : Result()
    }

    /** Settings > "Check for updates automatically", on by default. Off: no request but Check now's. */
    var auto: Boolean
        get() = values.get(AUTO) != "off"
        set(on) = values.put(mapOf(AUTO to if (on) null else "off"))

    /** The latest release as last seen, whatever the version. */
    val latest: Release? get() {
        val tag = values.get(TAG) ?: return null
        return Release(tag, values.get(URL) ?: "", values.get(OFFERED) == "yes")
    }

    /** The release the launcher offers: newer than this app, an APK, and not put off with Not now. */
    fun banner(): Release? = latest?.takeIf { r ->
        r.offered && Versions.isNewer(r.tag, current) && values.get(DISMISSED).let { d -> d == null || Versions.isNewer(r.tag, d) }
    }

    /** Not now: hidden until a release newer than this one appears. */
    fun dismiss(release: Release) = values.put(mapOf(DISMISSED to release.tag))

    /** Whether the automatic check would ask now: on, and a day since the last attempt (or the clock went back). */
    fun due(): Boolean {
        if (!auto) return false
        val last = values.get(LAST)?.toLongOrNull() ?: return true
        val t = now()
        return t - last >= DAY_MS || t < last
    }

    /** The automatic check: asks when [due], else does nothing. The launcher reads [banner] after it. */
    @Synchronized fun checkIfDue(): Result? = if (due()) ask() else null

    /** Settings > Check for updates: asks now, whatever the setting and the last check. Not now does not hide its answer. */
    @Synchronized fun checkNow(): Result = ask()

    private fun ask(): Result {
        val url = testUrl() ?: GITHUB
        values.put(mapOf(LAST to now().toString()))
        val headers = linkedMapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "FTL-Unofficial-Android/$current",
        )
        val etag = values.get(ETAG)?.takeIf { values.get(ETAG_URL) == url && latest != null }
        etag?.let { headers["If-None-Match"] = it }
        val answer = try {
            fetch.get(url, headers)
        } catch (e: IOException) {
            log("update check failed: ${e.javaClass.simpleName}")
            return Result.Failed(OFFLINE)
        }
        val release = when (answer.status) {
            304 -> latest ?: run {
                values.put(mapOf(ETAG to null, ETAG_URL to null))
                log("update check: 304 without a stored release")
                return Result.Failed(FAILED)
            }
            200 -> parse(answer.body, testUrl() != null) ?: run {
                log("update check: unreadable answer")
                return Result.Failed(FAILED)
            }
            403, 429 -> {
                log("update check: rate-limited (${answer.status})")
                return Result.Failed(BUSY)
            }
            else -> {
                log("update check: status ${answer.status}")
                return Result.Failed(FAILED)
            }
        }
        if (answer.status == 200) values.put(mapOf(TAG to release.tag, URL to release.url, OFFERED to if (release.offered) "yes" else null,
            ETAG to answer.etag, ETAG_URL to answer.etag?.let { url }))
        log("update check: ${answer.status}, latest ${release.tag}, offered ${release.offered}, this app $current")
        return if (release.offered && Versions.isNewer(release.tag, current)) Result.Available(release) else Result.UpToDate
    }

    /** The release in a releases/latest answer; offered only with an APK asset and a page on github.com (any http(s) page for a test server). */
    internal fun parse(body: String?, testServer: Boolean): Release? {
        val o = runCatching { Json.parse(body ?: return null) }.getOrNull().objOrNull() ?: return null
        val tag = o["tag_name"].str()?.takeIf { it.isNotBlank() } ?: return null
        val url = o["html_url"].str() ?: ""
        val apk = o["assets"].arr().any { a ->
            val asset = a.objOrNull()
            asset?.get("name").str()?.endsWith(".apk", ignoreCase = true) == true || asset?.get("content_type").str() == APK_TYPE
        }
        val page = runCatching { URI(url) }.getOrNull()
        val linkOk = page != null && if (testServer) page.scheme == "http" || page.scheme == "https"
            else page.scheme == "https" && page.host == "github.com"
        val prerelease = o["draft"].bool() == true || o["prerelease"].bool() == true
        if (!apk) log("update check: release $tag has no APK")
        if (!linkOk) log("update check: release $tag has an unexpected page")
        if (Versions.parse(tag) == null) log("update check: tag $tag does not parse")
        return Release(tag, url, apk && linkOk && !prerelease && Versions.parse(tag) != null)
    }

    companion object {
        const val GITHUB = "https://api.github.com/repos/911bob2/ftl-unofficial-browser-edition/releases/latest"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val OFFLINE = "Could not reach GitHub. Check your connection and try again."
        const val BUSY = "GitHub is not answering checks from this network for now. Try again later."
        const val FAILED = "GitHub's answer could not be read. Try again later."

        const val AUTO = "auto"
        const val LAST = "last-attempt"
        const val ETAG = "etag"
        const val ETAG_URL = "etag-url"
        const val TAG = "tag"
        const val URL = "url"
        const val OFFERED = "offered"
        const val DISMISSED = "dismissed"
    }
}
