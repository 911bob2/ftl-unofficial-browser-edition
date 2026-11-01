package ftl.android.app.sync

import android.content.Context
import ftl.android.app.BuildConfig
import ftl.android.app.Identity
import ftl.android.app.net.Api
import ftl.android.app.net.ApiError
import ftl.android.app.net.SecureStore

/**
 * The signed-in account, if any. "Offline" players have none: their saves stay on the device until they sign in,
 * when every device save uploads (docs/SAVES.md, "Enable cloud saves").
 */
class Account(context: Context, private val identity: Identity) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    data class User(val server: String, val id: String, val username: String, val role: String, val serverAssets: Boolean)

    @Volatile var user: User? = load()
        private set
    @Volatile var api: Api? = user?.let { u -> Api(u.server, identity, secure).also { it.accountId = u.id } }
        private set

    val server: String get() = prefs.getString("server", null) ?: BuildConfig.DEFAULT_SERVER

    private fun load(): User? {
        val id = prefs.getString("id", null) ?: return null
        return User(prefs.getString("server", BuildConfig.DEFAULT_SERVER)!!, id, prefs.getString("username", "")!!,
            prefs.getString("role", "")!!, prefs.getBoolean("serverAssets", false))
    }

    /** Signs in; the same rules and limits as the website's sign-in. Blocking. */
    fun signIn(server: String, username: String, password: String): User {
        val base = server.trim().trimEnd('/')
        require(base.startsWith("https://") || (BuildConfig.DEBUG && base.startsWith("http://"))) { "Use the server's https:// address." }
        val api = Api(base, identity, secure)
        val mustChange = api.login(username.trim(), password)
        if (mustChange) {
            api.logout()
            throw IllegalStateException("This account needs a new password. Sign in on the website once to set it, then sign in here.")
        }
        val me = api.me()
        val user = User(base, me.id, me.username, me.role, me.serverAssets)
        api.accountId = user.id
        remember(user)
        this.user = user
        this.api = api
        return user
    }

    private fun remember(user: User) {
        prefs.edit().putString("server", user.server).putString("id", user.id).putString("username", user.username)
            .putString("role", user.role).putBoolean("serverAssets", user.serverAssets).apply()
    }

    /**
     * Blocking; online only. The server's word on this account: a changed role takes effect here, and an ended session or
     * deleted account signs this device out. True when anything changed (FtlApplication.accountChanged follows).
     */
    fun refresh(): Boolean {
        val current = user ?: return false
        val api = api ?: return false
        val me = try {
            api.me()
        } catch (e: ApiError) {
            if (e.status != 401) return false
            api.signedOutLocally()
            forget()
            return true
        } catch (_: java.io.IOException) {
            return false
        }
        val now = current.copy(username = me.username, role = me.role, serverAssets = me.serverAssets)
        if (now == current) return false
        remember(now)
        user = now
        return true
    }

    fun signOut() {
        api?.logout()
        forget()
    }

    private fun forget() {
        prefs.edit().remove("id").remove("username").remove("role").remove("serverAssets").apply()
        user = null
        api = null
    }
}
