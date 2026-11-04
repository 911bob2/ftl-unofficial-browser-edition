package ftl.android.app

import ftl.android.app.update.Fetch
import ftl.android.app.update.HttpAnswer
import ftl.android.app.update.KeyValues
import ftl.android.app.update.UpdateCheck
import ftl.android.app.update.Versions
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private class Values : KeyValues {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(values: Map<String, String?>) {
            for ((k, v) in values) if (v == null) map.remove(k) else map[k] = v
        }
    }

    /** A stand-in for GitHub: answers with [release] (a 304 when the request's If-None-Match is its ETag) or throws when [offline]. */
    private class Server : Fetch {
        var release: String? = release("v2.0.1")
        var status = 200
        var offline = false
        val requests = ArrayList<Map<String, String>>()
        val urls = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>): HttpAnswer {
            requests.add(headers)
            urls.add(url)
            if (offline) throw IOException("offline")
            if (status != 200) return HttpAnswer(status, null, null)
            val tag = "W/\"" + release.hashCode() + "\""
            return if (headers["If-None-Match"] == tag) HttpAnswer(304, tag, null) else HttpAnswer(200, tag, release)
        }
    }

    private class Clock { var t = 1_000_000_000L }

    private fun check(values: Values, server: Server, clock: Clock, current: String = "2.0.1", test: String? = null) =
        UpdateCheck(values, server, current, testUrl = { test }, now = { clock.t })

    @Test
    fun versionsCompareByNumericParts() {
        assertEquals(listOf(2, 0, 1), Versions.parse("v2.0.1"))
        assertEquals(listOf(2, 0, 1), Versions.parse("2.0.1"))
        assertEquals(listOf(2, 0, 1), Versions.parse(" V2.0.1 "))
        assertFalse(Versions.isNewer("v2.0.1", "2.0.1"), "equal")
        assertTrue(Versions.isNewer("v2.0.2", "2.0.1"), "newer")
        assertTrue(Versions.isNewer("v2.1", "2.0.9"))
        assertTrue(Versions.isNewer("v10.0.0", "9.9.9"), "numeric, not text")
        assertTrue(Versions.isNewer("2.0.1.1", "2.0.1"))
        assertFalse(Versions.isNewer("v2.0.0", "2.0.1"), "older")
        assertFalse(Versions.isNewer("v1.9", "2.0.1"))
        assertEquals(0, Versions.compare(Versions.parse("2.0")!!, Versions.parse("2.0.0")!!), "a missing part is 0")
        assertFalse(Versions.isNewer("v2.0", "2.0.0"))
        assertFalse(Versions.isNewer("v2.0.0", "2.0"))
        for (junk in listOf(null, "", "v", "latest", "2.0-beta", "v2..1", "2.0.", ".2", "2.x", "v2.0.1-rc1", "٢.٠", "+2.0", "-1.0", "1".repeat(12)))
            assertNull(Versions.parse(junk), "junk: $junk")
        assertFalse(Versions.isNewer("v3.0-beta", "2.0.1"), "a tag that does not parse never prompts")
        // The app's own version may carry a suffix: a build before that release.
        assertTrue(Versions.isNewer("v2.0.1", "2.0.0-spoofed"))
        assertTrue(Versions.isNewer("v2.0.1", "2.0.1-beta"), "the release beats its own pre-release build")
        assertTrue(Versions.isNewer("v2.0.1", "2.0.1+local"))
        assertFalse(Versions.isNewer("v2.0.0", "2.0.1-beta"))
        assertFalse(Versions.isNewer("v2.0.1", "-2.0.1"))
        assertTrue(Versions.isNewer("v3.0", "2.0.1-debug"), "a suffixed version name is still that version")
        assertFalse(Versions.isNewer("v3.0", "latest"), "a version name that does not parse never prompts")
    }

    @Test
    fun aNewerReleaseIsOfferedAndUpToDateShowsNothing() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow())
        assertNull(c.banner())
        server.release = release("v2.0.2")
        val r = c.checkNow()
        assertIs<UpdateCheck.Result.Available>(r)
        assertEquals("2.0.2", r.release.version)
        assertEquals(PAGE + "v2.0.2", c.banner()?.url)
        // The app updated to that version: nothing to offer, from the same stored answer.
        assertNull(check(values, server, clock, current = "2.0.2").banner())
        server.release = release("v1.9.0")
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow(), "an older latest is not offered")
        assertNull(c.banner())
    }

    @Test
    fun theRequestCarriesOnlyTheAppVersion() {
        val server = Server()
        check(Values(), server, Clock()).checkNow()
        assertEquals(UpdateCheck.GITHUB, server.urls.single())
        assertEquals("https://api.github.com/repos/911bob2/ftl-unofficial-browser-edition/releases/latest", UpdateCheck.GITHUB)
        assertEquals(setOf("Accept", "X-GitHub-Api-Version", "User-Agent"), server.requests.single().keys)
        assertEquals("FTL-Unofficial-Android/2.0.1", server.requests.single()["User-Agent"])
    }

    @Test
    fun theAutomaticCheckRunsAtMostOnceADay() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        assertTrue(c.due())
        assertIs<UpdateCheck.Result.UpToDate>(c.checkIfDue())
        assertNull(c.checkIfDue(), "not again the same day")
        clock.t += UpdateCheck.DAY_MS - 1
        assertNull(c.checkIfDue())
        assertEquals(1, server.requests.size)
        clock.t += 1
        assertTrue(c.due())
        c.checkIfDue()
        assertEquals(2, server.requests.size)
        // A failure counts as the day's attempt: offline, rate-limited or an error is not retried until the next day.
        clock.t += UpdateCheck.DAY_MS
        server.offline = true
        assertIs<UpdateCheck.Result.Failed>(c.checkIfDue())
        repeat(5) { assertNull(c.checkIfDue()) }
        server.offline = false
        server.status = 403
        clock.t += UpdateCheck.DAY_MS
        assertEquals(UpdateCheck.BUSY, (c.checkIfDue() as UpdateCheck.Result.Failed).reason)
        assertNull(c.checkIfDue())
        server.status = 500
        clock.t += UpdateCheck.DAY_MS
        assertIs<UpdateCheck.Result.Failed>(c.checkIfDue())
        assertEquals(5, server.requests.size)
        // Check now asks whatever the last check was.
        server.status = 200
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow())
        assertEquals(6, server.requests.size)
        // A clock set back does not stop the checks for good.
        clock.t -= 10 * UpdateCheck.DAY_MS
        assertTrue(c.due())
    }

    @Test
    fun failuresKeepWhatWasSeenAndStaySilent() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        server.release = release("v2.0.2")
        c.checkNow()
        server.offline = true
        assertEquals(UpdateCheck.OFFLINE, (c.checkNow() as UpdateCheck.Result.Failed).reason)
        assertEquals("v2.0.2", c.banner()?.tag, "the last answer stays")
        server.offline = false
        server.status = 429
        assertIs<UpdateCheck.Result.Failed>(c.checkNow())
        server.status = 200
        server.release = "<html>not json"
        assertIs<UpdateCheck.Result.Failed>(c.checkNow())
        server.release = "{\"message\":\"Not Found\"}"
        assertIs<UpdateCheck.Result.Failed>(c.checkNow())
        assertEquals("v2.0.2", c.banner()?.tag)
    }

    @Test
    fun notNowHidesThatVersionUntilANewerOne() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        server.release = release("v2.0.2")
        c.checkNow()
        c.dismiss(c.banner()!!)
        assertNull(c.banner())
        assertNull(check(values, server, clock).banner(), "after a restart too")
        assertIs<UpdateCheck.Result.Available>(c.checkNow(), "Check now still says so")
        assertNull(c.banner())
        server.release = release("v2.0.3")
        c.checkNow()
        assertEquals("v2.0.3", c.banner()?.tag, "a newer release than the one put off shows again")
        c.dismiss(c.banner()!!)
        server.release = release("v2.0.2")
        c.checkNow()
        assertNull(c.banner(), "nor does an older one after a newer was put off")
    }

    @Test
    fun aRepeatCheckSendsTheETagAndA304KeepsTheRelease() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        server.release = release("v2.0.2")
        c.checkNow()
        assertNull(server.requests[0]["If-None-Match"], "nothing to send the first time")
        val etag = "W/\"" + server.release.hashCode() + "\""
        val r = c.checkNow()
        assertEquals(etag, server.requests[1]["If-None-Match"])
        assertIs<UpdateCheck.Result.Available>(r, "a 304 answers with the stored release")
        assertEquals("v2.0.2", r.release.tag)
        // A changed release has another ETag: a 200 with the new one.
        server.release = release("v2.0.3")
        assertEquals("v2.0.3", (c.checkNow() as UpdateCheck.Result.Available).release.tag)
        assertEquals(etag, server.requests[2]["If-None-Match"])
        c.checkNow()
        assertEquals("W/\"" + server.release.hashCode() + "\"", server.requests[3]["If-None-Match"], "the new ETag replaces the old")
        // The ETag belongs to the address it came from: a test server gets none of GitHub's.
        check(values, server, clock, test = "http://10.0.2.2:8765/latest").checkNow()
        assertEquals("http://10.0.2.2:8765/latest", server.urls.last())
        assertNull(server.requests.last()["If-None-Match"])
    }

    @Test
    fun theSettingOffMeansNoRequestButCheckNow() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        assertTrue(c.auto, "on by default")
        c.auto = false
        assertFalse(check(values, server, clock).auto, "kept")
        repeat(3) {
            assertFalse(c.due())
            assertNull(c.checkIfDue())
            clock.t += 2 * UpdateCheck.DAY_MS
        }
        assertTrue(server.requests.isEmpty())
        c.checkNow()
        assertEquals(1, server.requests.size, "Check now asks with the setting off")
        c.auto = true
        clock.t += UpdateCheck.DAY_MS
        assertTrue(c.due())
    }

    @Test
    fun onlyAReleaseWithAnApkAndAGithubPageIsOffered() {
        val values = Values(); val server = Server(); val clock = Clock()
        val c = check(values, server, clock)
        server.release = release("v2.0.2", assets = "[]")
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow(), "no APK asset: silent")
        assertNull(c.banner())
        server.release = release("v2.0.2", assets = "[{\"name\":\"FTL.Unofficial.zip\",\"content_type\":\"application/zip\"}]")
        assertNull(c.checkNow().let { c.banner() })
        server.release = release("v2.0.2", assets = "[{\"name\":\"app\",\"content_type\":\"${UpdateCheck.APK_TYPE}\"}]")
        assertIs<UpdateCheck.Result.Available>(c.checkNow(), "an APK by its type")
        server.release = release("v2.0.3", page = "https://evil.example/releases/v2.0.3")
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow(), "a page off github.com is never opened")
        server.release = release("v2.0.3", page = "http://github.com/x")
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow())
        server.release = release("v2.0.3", prerelease = true)
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow(), "a pre-release is never offered")
        server.release = release("v3.0-beta")
        assertIs<UpdateCheck.Result.UpToDate>(c.checkNow(), "nor a tag that does not parse")
        assertNull(c.banner())
        // The debug build's stub server serves its own pages.
        server.release = release("v2.0.3", page = "http://10.0.2.2:8765/release")
        val stub = check(values, server, clock, test = "http://10.0.2.2:8765/latest")
        assertEquals("http://10.0.2.2:8765/release", (stub.checkNow() as UpdateCheck.Result.Available).release.url)
    }

    @Test
    fun theRealAnswerReads() {
        // The shape of GitHub's answer for v2.0.1 (fields trimmed).
        val body = """{"url":"https://api.github.com/repos/911bob2/ftl-unofficial-browser-edition/releases/1","html_url":"${PAGE}v2.0.1",
            "id":1,"tag_name":"v2.0.1","name":"FTL: Unofficial Browser Edition 2.0.1","draft":false,"prerelease":false,
            "assets":[{"name":"FTL.Unofficial.apk","content_type":"application/vnd.android.package-archive","size":7386718,
            "browser_download_url":"https://github.com/911bob2/ftl-unofficial-browser-edition/releases/download/v2.0.1/FTL.Unofficial.apk"}],
            "body":"Notes é \"quoted\""}"""
        val server = Server().apply { release = body }
        val c = check(Values(), server, Clock(), current = "2.0.0")
        val r = c.checkNow() as UpdateCheck.Result.Available
        assertEquals("v2.0.1", r.release.tag)
        assertEquals("${PAGE}v2.0.1", r.release.url)
        assertIs<UpdateCheck.Result.UpToDate>(check(Values(), server, Clock()).checkNow())
    }

    private companion object {
        const val PAGE = "https://github.com/911bob2/ftl-unofficial-browser-edition/releases/tag/"

        fun release(tag: String, page: String = PAGE + tag, prerelease: Boolean = false,
                    assets: String = "[{\"name\":\"FTL.Unofficial.apk\",\"content_type\":\"${UpdateCheck.APK_TYPE}\"}]") =
            "{\"tag_name\":\"$tag\",\"html_url\":\"$page\",\"draft\":false,\"prerelease\":$prerelease,\"assets\":$assets}"
    }
}
