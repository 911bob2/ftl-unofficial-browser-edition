package ftl.android.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Telling the player when this device is short of memory (docs/ANDROID.md, "Memory"). Before a large modded save starts,
 * on a device too small for it; and after the game ran out, whichever way that ended: an OutOfMemoryError on the game's own
 * thread is shown on the spot, one on another thread ends the process, and Android may kill a process that is using too
 * much without a word. The last two leave the marks read here at the next start.
 */
object MemoryNotice {
    /** A phone sold as 2 GB reports about 1.8 GB, one sold as 3 GB about 2.8. */
    const val LOW_RAM_BYTES = 2500L shl 20

    /** The Multiverse run was measured to fit a 192 MB Java heap and not a 128 MB one. */
    const val LOW_HEAP_BYTES = 192L shl 20

    /** By size, so that a large mod is treated alike under any name: Multiverse is 700 MB, Hyperspace alone 10. */
    const val LARGE_MODS_BYTES = 100L shl 20

    const val TOO_SMALL_TITLE = "This phone may run out of memory"
    const val TOO_SMALL_TEXT = "These mods are large. Multiverse needs a phone with 3 GB of memory or more; on this one it may " +
        "stop partway through a run. Your last save stays on this device if that happens.\n\nThe unmodded game and Hyperspace on its own fit."
    const val RAN_OUT_TITLE = "Out of memory"
    const val RAN_OUT_TEXT = "The game ran out of this phone's memory and had to stop. Your last save is kept on this device.\n\n" +
        "Multiverse needs a phone with 3 GB of memory or more. Closing other apps can help; the unmodded game and Hyperspace on its own fit on 2 GB."

    fun tooSmall(totalRam: Long, heapLimit: Long, modBytes: Long): Boolean =
        modBytes >= LARGE_MODS_BYTES && (totalRam < LOW_RAM_BYTES || heapLimit < LOW_HEAP_BYTES)

    fun totalRam(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }

    fun tooSmallFor(context: Context, modBytes: Long): Boolean = tooSmall(totalRam(context), Runtime.getRuntime().maxMemory(), modBytes)

    /** Warned once per save: after that the player has chosen. */
    fun warnedAlready(context: Context, saveId: String): Boolean = prefs(context).getBoolean("warned-$saveId", false)

    fun markWarned(context: Context, saveId: String) = prefs(context).edit().putBoolean("warned-$saveId", true).apply()

    fun isOutOfMemory(e: Throwable?): Boolean {
        var t = e
        repeat(16) {
            if (t == null) return false
            if (t is OutOfMemoryError) return true
            t = t?.cause
        }
        return false
    }

    /** From here until [left] the game is on screen; a process that dies in between did not close normally. */
    fun playing(context: Context) { runCatching { playingFile(context).createNewFile() } }

    fun left(context: Context) { playingFile(context).delete() }

    /** Records an OutOfMemoryError that is about to end the process, without allocating much. */
    fun install(context: Context) {
        val mark = ranOutFile(context)
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            if (isOutOfMemory(e)) runCatching { mark.createNewFile() }
            next?.uncaughtException(thread, e)
        }
    }

    /** Whether the last game ended by running out of memory and nobody has been told yet. Answers true once. */
    fun takeRanOut(context: Context): Boolean {
        val ran = ranOutFile(context).delete()
        val wasPlaying = playingFile(context).delete()
        return ran || (wasPlaying && killedForMemory(context))
    }

    private fun killedForMemory(context: Context): Boolean {
        // Before Android 11 the system does not say why; on a small phone, memory is the likely reason for dying on screen.
        if (Build.VERSION.SDK_INT < 30) return totalRam(context) < LOW_RAM_BYTES
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return false
        return last.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            (last.reason == ApplicationExitInfo.REASON_SIGNALED && last.status == 9)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("memory", Context.MODE_PRIVATE)
    private fun ranOutFile(context: Context) = File(context.filesDir, "ran-out-of-memory")
    private fun playingFile(context: Context) = File(context.filesDir, "game-on-screen")
}
