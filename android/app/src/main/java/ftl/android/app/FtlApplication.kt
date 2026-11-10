package ftl.android.app

import android.app.Application
import android.content.Context
import android.os.Build
import ftl.android.app.data.GameData
import ftl.android.app.icons.AppIcons
import ftl.android.app.log.DebugLog
import ftl.android.app.mods.ModLibrary
import ftl.android.app.store.DeviceStore
import ftl.android.app.sync.Account
import ftl.android.app.sync.SyncEngine
import java.util.UUID
import kotlin.concurrent.thread

/** Who this device is to the save locks: one install = one device and one game instance (docs/SAVES.md, "Locks"). */
class Identity(context: Context) {
    val deviceId: String
    val instanceId: String get() = deviceId
    val name: String

    init {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        deviceId = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
        // The model, not the phone's own name: that often holds its owner's name, and this one is stored with every save.
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
        name = model.filterNot { Character.isISOControl(it) }.trim().take(60)
            .trimEnd { Character.isHighSurrogate(it) }.ifBlank { "Android device" }
    }

    /** The X-FTL-Device header value; the name percent-encoded as encodeURIComponent does (headers are ASCII). */
    val header: String get() = "android;$deviceId;" + buildString {
        for (b in name.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.!~*'()") append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}

class FtlApplication : Application() {
    lateinit var identity: Identity
        private set
    lateinit var store: DeviceStore
        private set
    lateinit var gameData: GameData
        private set
    lateinit var mods: ModLibrary
        private set
    lateinit var account: Account
        private set
    lateinit var sync: SyncEngine
        private set
    /** Whether a newer release is out (docs/ANDROID.md, "Updates"). */
    val updates by lazy { ftl.android.app.update.Updates.create(this) }

    override fun onCreate() {
        super.onCreate()
        app = this
        MemoryNotice.install(this)
        DebugLog.install(this)
        thread(name = "ftl-icon-repair", isDaemon = true) { runCatching { AppIcons(this).repair() } }
        identity = Identity(this)
        account = Account(this, identity)
        store = DeviceStore(filesDir, scope())
        gameData = GameData(filesDir, assets) { account.user?.takeIf { it.serverAssets }?.let { "${it.server}|${it.id}" } }
        gameData.reconcile()
        mods = ModLibrary(filesDir, assets)
        // The old "Run scripts from a Custom mod?" answers: no longer asked (only file and program use is, LuaSandbox).
        deleteSharedPreferences("mod-scripts")
        sync = SyncEngine(this, account, store, identity.instanceId)
    }

    /** The store follows the account, as in the browser: each account's saves apart, play without one in its own scope. */
    private fun scope() = account.user?.let { DeviceStore.scopeOf(it.server, it.id) } ?: DeviceStore.LOCAL

    /** After sign-in or sign-out: the store and the syncer switch to that account's saves. */
    fun accountChanged() {
        store.scope = scope()
        sync.reset()
        gameData.reconcile()
    }

    companion object {
        lateinit var app: FtlApplication
            private set
    }
}
