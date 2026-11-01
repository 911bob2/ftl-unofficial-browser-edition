package ftl.android.app.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import ftl.android.app.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import kotlin.concurrent.thread

/** Keeps a growing log to [limit] bytes in two files: the current one and the one before. */
class RotatingLog(private val current: File, private val previous: File, private val limit: Long) {
    @Synchronized
    fun append(line: String) {
        if (current.length() >= limit) {
            previous.delete()
            current.renameTo(previous)
        }
        FileWriter(current, true).use { it.write(line); it.write("\n") }
    }

    val bytes: Long @Synchronized get() = current.length() + previous.length()

    /** When something was last written, 0 when nothing is kept. */
    val lastWritten: Long @Synchronized get() = maxOf(current.lastModified(), previous.lastModified())

    @Synchronized
    fun clear() {
        current.delete()
        previous.delete()
    }

    @Synchronized
    fun copyTo(out: OutputStream) {
        for (f in listOf(previous, current)) if (f.exists()) f.inputStream().use { it.copyTo(out) }
    }
}

/**
 * The player's own debug log (docs/ANDROID.md, "Debug log"), for sending after something went wrong. Works in release builds.
 *
 * - **Crash reports are always kept**: an uncaught exception, a game-stopping error, and what Android says about the last
 *   process's death (native crash, not responding, killed for memory), which nothing in the process can catch.
 * - **The log**, only while switched on: this process's lines in the system log, whatever wrote them, so the engine, the
 *   Lua runtime and the platform's own libraries are all in.
 *
 * Everything goes through [Redact] on its way in, stays in app-private files, and leaves only by the player's Export.
 */
object DebugLog {
    const val LOG_LIMIT = 1024L * 1024
    const val CRASH_LIMIT = 128L * 1024
    private const val TRACE_LIMIT = 48 * 1024
    private const val TAG = "FTL-Log"

    private lateinit var dir: File
    private lateinit var log: RotatingLog
    private lateinit var crashes: RotatingLog
    private lateinit var prefs: android.content.SharedPreferences
    private var reader: java.lang.Process? = null

    fun install(context: Context) {
        dir = File(context.filesDir, "log").apply { mkdirs() }
        log = RotatingLog(File(dir, "app.log"), File(dir, "app.1.log"), LOG_LIMIT)
        crashes = RotatingLog(File(dir, "crash.log"), File(dir, "crash.1.log"), CRASH_LIMIT)
        prefs = context.getSharedPreferences("debug-log", Context.MODE_PRIVATE)
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { caught("Crash on thread ${t.name}", e) }
            next?.uncaughtException(t, e)
        }
        thread(name = "ftl-log-start", isDaemon = true) {
            runCatching { recordExits(context) }
            if (enabled) startReading()
        }
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        @Synchronized set(value) {
            prefs.edit().putBoolean("enabled", value).commit()
            if (value) startReading() else stopReading()
        }

    /** A failure worth reporting. Kept whether the log is on or off. */
    fun caught(what: String, e: Throwable) {
        val text = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
        crashes.append("=== ${Instant.now()} $what\n" + Redact.text(text))
    }

    /** When the last failure was recorded, if one is on record. */
    val lastProblem: String? get() = crashes.lastWritten.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }

    val bytes: Long get() = log.bytes + crashes.bytes

    fun clear() {
        log.clear()
        crashes.clear()
    }

    /** The whole report: who and what is running, the crash reports, then the log. */
    fun export(context: Context, out: OutputStream) {
        out.write(header(context).encodeToByteArray())
        out.write("\n--- Crash reports ---\n".encodeToByteArray())
        crashes.copyTo(out)
        out.write("\n--- Log (${if (enabled) "on" else "off"}) ---\n".encodeToByteArray())
        log.copyTo(out)
    }

    /** No account, no device id: the model and the versions are what a bug needs. */
    private fun header(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "FTL Unofficial debug log, exported ${Instant.now()}\n" +
            "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}), engine ${BuildConfig.ENGINE_BUILD}\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}\n" +
            "Memory ${memory.totalMem shr 20} MB, Java heap limit ${Runtime.getRuntime().maxMemory() shr 20} MB\n"
    }

    /** This process's lines only, as they arrive. An app may read its own without a permission. */
    @Synchronized
    private fun startReading() {
        if (reader != null) return
        val p = try {
            ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "No system log on this device", e)
            return
        }
        reader = p
        thread(name = "ftl-log", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    for (line in lines) log.append(Redact.line(line))
                }
            }
        }
        Log.i(TAG, "Debug log on")
    }

    @Synchronized
    private fun stopReading() {
        Log.i(TAG, "Debug log off")
        reader?.destroy()
        reader = null
    }

    /** What Android recorded about earlier deaths of this process that it has not been asked about yet. */
    private fun recordExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val seen = prefs.getLong("exit-seen", 0)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8).filter { it.timestamp > seen }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("exit-seen", it).apply() }
        for (exit in exits.sortedBy { it.timestamp }) {
            val reason = when (exit.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_ANR -> "not responding"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                ApplicationExitInfo.REASON_SIGNALED -> if (exit.status == 9) "killed by the system" else "signal ${exit.status}"
                else -> continue
            }
            val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                exit.traceInputStream?.use { String(it.readNBytesCompat(TRACE_LIMIT)) }
            }.getOrNull() else null
            crashes.append("=== ${Instant.ofEpochMilli(exit.timestamp)} The last run ended: $reason. pss=${exit.pss}kB ${Redact.line(exit.description ?: "")}" +
                (trace?.let { "\n" + Redact.text(it) } ?: ""))
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
