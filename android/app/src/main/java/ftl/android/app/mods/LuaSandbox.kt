package ftl.android.app.mods

import android.content.Context
import ftl.saves.Sha256
import java.io.File

/**
 * Mod scripts' io, os, debug and package on this device (docs/HS_LUA.md, "Files, programs and the sandbox"):
 *  - the private folder per account (the app's storage, never synced), with its use and "Clear mod files";
 *  - "Ask before running": the mods the player let run scripts that use these libraries, per mod version (archive
 *    SHA-256) and account; an answer given with the sandbox off ("real") is the only one that lets a script out;
 *  - "Disable Lua sandboxing (dangerous)", per device, off by default.
 * Nothing here is reachable from a script: the runtime has no call that reads or writes these settings.
 */
object LuaSandbox {
    private const val PREFS = "lua-sandbox"

    fun accountKey(context: Context): String {
        val user = ftl.android.app.FtlApplication.app.account.user
        return if (user == null) "local" else Sha256.hex(user.server + "|" + user.id).take(32)
    }

    fun folder(context: Context, account: String = accountKey(context)) = File(context.filesDir, "lua-files/$account")

    fun used(context: Context): Long = folder(context).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sumOf { it.length() } ?: 0L

    fun clear(context: Context) { folder(context).deleteRecursively() }

    fun unsandboxed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("unsandboxed", false)

    fun setUnsandboxed(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("unsandboxed", on).apply()

    private fun consents(context: Context) = context.getSharedPreferences("lua-consent", Context.MODE_PRIVATE)

    /** "run" (asked with the sandbox on), "real" (asked with it off), or null. */
    fun answer(context: Context, sha256: String, account: String = accountKey(context)): String? = consents(context).getString("$account|$sha256", null)

    fun setAnswer(context: Context, sha256: String, value: String?, account: String = accountKey(context)) {
        val e = consents(context).edit()
        if (value == null) e.remove("$account|$sha256") else e.putString("$account|$sha256", value)
        e.apply()
    }

    /** Whether a mod's scripts may run as asked: with the sandbox off only a "real" answer counts. */
    fun allowed(context: Context, sha256: String, real: Boolean): Boolean = when (answer(context, sha256)) {
        "real" -> true
        "run" -> !real
        else -> false
    }

    /** The account's answers: sha256 -> answer. */
    fun answers(context: Context): Map<String, String> {
        val prefix = accountKey(context) + "|"
        return consents(context).all.mapNotNull { (k, v) -> if (k.startsWith(prefix) && v is String) k.removePrefix(prefix) to v else null }.toMap()
    }
}
