package ftl.android.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import ftl.android.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The Android side of [UpdateCheck]: its preferences, the HTTP request, and opening a release's page. */
object Updates {
    const val TAG = "FTL-Update"
    const val PREFS = "updates"
    /** Debug builds only: the stub server that stands in for GitHub (debug console `updateurl`). */
    const val TEST_URL = "url-for-tests"
    private const val MAX_BODY = 1 shl 20

    fun create(context: Context): UpdateCheck {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = object : KeyValues {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(values: Map<String, String?>) {
                val e = prefs.edit()
                for ((k, v) in values) if (v == null) e.remove(k) else e.putString(k, v)
                e.apply()
            }
        }
        return UpdateCheck(values, ::get, BuildConfig.VERSION_NAME,
            testUrl = { if (BuildConfig.DEBUG) prefs.getString(TEST_URL, null) else null },
            log = { Log.d(TAG, it) })
    }

    /** A plain GET: no cookies, no cache of the system's, only the headers given. */
    private fun get(url: String, headers: Map<String, String>): HttpAnswer {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.useCaches = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val status = c.responseCode
            val body = if (status == 200) c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BODY) throw IOException("answer too large")
                }
                out.toString(Charsets.UTF_8.name())
            } else null
            return HttpAnswer(status, c.getHeaderField("ETag"), body)
        } finally {
            c.disconnect()
        }
    }

    /** Download: the release's page in the browser, where the APK is. The app never installs anything itself. */
    fun open(context: Context, release: Release) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No browser to open ${release.url}", Toast.LENGTH_LONG).show()
        }
    }
}
