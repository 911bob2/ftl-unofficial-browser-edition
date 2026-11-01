package ftl.android.app.game

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ftl.android.AndroidHost
import ftl.android.GameFiles
import ftl.android.ModSession
import ftl.android.app.FtlApplication
import ftl.android.app.MemoryNotice
import ftl.android.app.log.DebugLog
import ftl.android.audio.AudioMixer
import ftl.browser.BrowserEngine
import ftl.android.app.mods.ModLaunch
import ftl.android.app.sync.SyncEngine
import ftl.saves.Setup
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import xyz.znix.xftl.savegame.RunLoadException

/**
 * Plays one save. The engine runs on the game thread (GameView); this activity forwards input and lifecycle to it and saves on
 * every pause, so a process killed in the background loses nothing (the save is already on the device).
 */
class GameActivity : Activity() {
    private lateinit var view: GameView
    private lateinit var renderer: GameRenderer
    private lateinit var input: Input
    private lateinit var loading: TextView
    private var session: GameSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closing = false

    private val host = object : AndroidHost {
        // xml is null when the engine removed the file: a new run or a destroyed ship clears the run, as on the website.
        override fun onSave(kind: String, xml: String?) { session?.onSave(kind, xml) }
        override fun exit() { runOnUiThread { saveAndClose() } }
        override fun openUrl(url: String) { runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }
        override fun setWindowTitle(title: String) { runOnUiThread { setTitle(title) } }
        override fun luaLog(kind: Int, text: String) { Log.println(if (kind >= 2) Log.WARN else Log.INFO, "FTL-Lua", text) }
        override fun debugEvent(category: String, name: String, payload: String, severity: String) {
            if (severity != "info") Log.w("FTL", "$category/$name $payload")
        }
        override fun checkpointPut(name: String, xml: String) {
            getSharedPreferences("checkpoints", Context.MODE_PRIVATE).edit().putString(name, xml).apply()
        }
        override fun checkpointGet(name: String): String? = getSharedPreferences("checkpoints", Context.MODE_PRIVATE).getString(name, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = FtlApplication.app
        val id = intent.getStringExtra(EXTRA_SAVE) ?: return finish()
        val save = app.store.get(id) ?: return finish()
        session = GameSession(save) { id -> app.sync.written(id) }
        app.sync.playing = save.id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        AndroidHost.set(host)
        loading = TextView(this).apply {
            text = "Loading FTL…"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        frame = FrameLayout(this).apply {
            addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)
        // Back is FTL's Escape. From Android 13 it arrives here, not as a key event (predictive back).
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { if (::input.isInitialized) input.escape() }
        }
        scope.launch(Dispatchers.Main) {
            try {
                // Scripts of a Custom mod run in this process, which also holds the sign-in: the player says yes first.
                if (!save.setup.vanilla && !allowCustomScripts(save.setup)) {
                    finish()
                    return@launch
                }
                // A modded save: its archives merged over the game data first (web/mods/boot.mjs does the same).
                val launch = if (save.setup.vanilla) null else withContext(Dispatchers.IO) {
                    ModLaunch.prepare(app.filesDir, app.gameData, app.mods, save.setup) { msg -> runOnUiThread { loading.text = msg } }
                }
                modLaunch = launch
                launch?.warnings?.forEach { Log.i("FTL", "Mod merge: $it") }
                GameFiles.install(launch?.files ?: app.gameData.files())
                ModSession.current = launch?.session
                startGame()
            } catch (e: ModLaunch.MissingMods) {
                showError(e, "Mods missing")
            } catch (e: Exception) {
                Log.e("FTL", "Modded start failed", e)
                DebugLog.caught("The modded start failed", e)
                showError(e)
            } catch (e: OutOfMemoryError) {
                Log.e("FTL", "Modded start ran out of memory", e)
                DebugLog.caught("The modded start ran out of memory", e)
                showError(e)
            }
        }
    }

    private lateinit var frame: FrameLayout
    private var modLaunch: ModLaunch? = null

    private suspend fun allowCustomScripts(setup: Setup): Boolean {
        val app = FtlApplication.app
        val prefs = getSharedPreferences("mod-scripts", MODE_PRIVATE)
        val allowed = prefs.getStringSet("allowed", emptySet()).orEmpty()
        val asking = withContext(Dispatchers.IO) { app.mods.customWithScripts(setup) }.filter { it.sha256 !in allowed }
        if (asking.isEmpty()) return true
        val yes = suspendCancellableCoroutine { done ->
            AlertDialog.Builder(this)
                .setTitle("Run scripts from a Custom mod?")
                .setMessage(asking.joinToString { it.label } + (if (asking.size == 1) " is a Custom mod and contains" else " are Custom mods and contain") +
                    " Lua scripts. They are not files this site has checked, and a script can reach what this app can, including your sign-in on this device. Only run mods you trust.")
                .setPositiveButton("Run") { _, _ -> done.resume(true) }
                .setNegativeButton("Go back") { _, _ -> done.resume(false) }
                .setCancelable(false)
                .show()
        }
        if (yes) prefs.edit().putStringSet("allowed", allowed + asking.map { it.sha256 }).apply()
        return yes
    }

    private fun startGame() {
        val app = FtlApplication.app
        val fit = ScreenFit.load(this)
        renderer = GameRenderer(
            fit = fit,
            screenH = ScreenFit.screenSize(this).second,
            start = {
                val s = session!!
                val saved = app.store.get(s.id)
                val run = saved?.run ?: s.runXml
                BrowserEngine.start(run?.contains("enableAE=\"true\"") ?: true, run, saved?.profile ?: s.profileXml, app.store.settings())
            },
            onReady = { runOnUiThread { loading.visibility = View.GONE } },
            onError = { e -> runOnUiThread { showError(e) } },
            onKeyboard = { show, focusY -> runOnUiThread { view.showKeyboard(show, focusY) } },
            onDropped = { names ->
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Some content is missing")
                        .setMessage("This save names content the current game files or mods lack. It was removed from the run:\n\n" +
                            names.joinToString("\n") + "\n\nThe next save keeps the run without it.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            },
        )
        input = Input(renderer)
        view = GameView(this, renderer).apply {
            input = this@GameActivity.input
            setOnTouchListener { _, e -> this@GameActivity.input.onTouch(e) }
            setOnGenericMotionListener { _, e -> this@GameActivity.input.onMouse(e) }
        }
        // The view is the picture: what is left of the screen around it is black. A finger there is not a game finger,
        // but the frame must take it: an unclaimed first finger leaves every later one with no target in this window.
        frame.setOnTouchListener { _, e -> this@GameActivity.input.onFrameTouch(e, view) }
        val place = {
            if (frame.width > 0 && frame.height > 0) {
                val (w, h) = fit.viewSize(frame.width, frame.height)
                val now = view.layoutParams
                if (now.width != w || now.height != h) view.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            }
        }
        frame.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        place()
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> frame.post { place() } }
        AudioMixer.get().start()
        if (resumed) resumeGame()
    }

    private var heartbeat: Job? = null

    /** While playing, the lock heartbeat tells this device when another one has overridden it. */
    private fun startHeartbeat() {
        val app = FtlApplication.app
        val id = session?.id ?: return
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                when (val r = app.sync.heartbeat(id)) {
                    is SyncEngine.LockResult.Overridden -> { overridden(r.by, r.at); return@launch }
                    // Started offline and someone else holds it now: play on here; the launcher asks afterwards.
                    is SyncEngine.LockResult.Held -> return@launch
                    else -> {}
                }
                delay(60_000)
            }
        }
    }

    /** Another device took this save: stop at once; nothing this game still has is kept (docs/SAVES.md, "Locks"). */
    private suspend fun overridden(by: ftl.saves.LockInfo?, at: String?) {
        val s = session ?: return
        s.frozen = true
        withContext(Dispatchers.Main) {
            renderer.suspended = true
            view.queueEvent { if (BrowserEngine.running()) BrowserEngine.shutdown() }
            val who = by?.device?.takeIf { it.isNotBlank() } ?: "another device"
            AlertDialog.Builder(this@GameActivity)
                .setTitle("Save taken over")
                .setMessage("This save was taken over on $who. Progress and unlocks since your last sync were discarded.")
                .setPositiveButton("Back to saves") { _, _ ->
                    scope.launch { runCatching { FtlApplication.app.sync.acknowledgeOverride(s.id, at) } }
                    closing = true
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }

    private var resumed = false

    // Audio focus: a call or another app's sound silences the game until the focus returns; a short ducking request
    // is left to the system to duck.
    @Volatile private var focusLost = false
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> focusLost = false
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusLost = true
                }
                Log.i("FTL", "Audio focus change $change")
                applySilence()
            }
            .build()
    }

    private fun applySilence() = AudioMixer.get().setPaused(!resumed || focusLost)

    override fun onResume() {
        super.onResume()
        resumed = true
        MemoryNotice.playing(this)
        if (::view.isInitialized) resumeGame()
    }

    private fun resumeGame() {
        startHeartbeat()
        session?.setRunning(true)
        focusLost = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_FAILED
        applySilence()
        view.onResume()
        view.queueEvent { if (BrowserEngine.running()) BrowserEngine.focusChanged(true) }
    }

    override fun onPause() {
        resumed = false
        MemoryNotice.left(this)
        if (::view.isInitialized) {
            // As the page does when it is hidden: release input and save; the save reaches the device store before the
            // game thread pauses.
            input.releaseFingers()
            onGlThread {
                if (BrowserEngine.running()) {
                    BrowserEngine.resetInput()
                    BrowserEngine.focusChanged(false)
                    BrowserEngine.save()
                }
            }
            session?.flush()
            session?.setRunning(false)
            heartbeat?.cancel()
            applySilence()
            audioManager.abandonAudioFocusRequest(focusRequest)
            view.onPause()
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::view.isInitialized) view.queueEvent { if (BrowserEngine.running()) BrowserEngine.focusChanged(hasFocus) }
    }

    override fun onDestroy() {
        if (::view.isInitialized) {
            onGlThread { if (BrowserEngine.running()) BrowserEngine.shutdown() }
            view.shutdown()
            AudioMixer.get().reset()
            AudioMixer.get().stop()
        }
        session?.let { s ->
            s.close()
            if (FtlApplication.app.sync.playing == s.id) FtlApplication.app.sync.playing = null
            if (!s.frozen) FtlApplication.app.sync.finish(s.id)
        }
        scope.cancel()
        modLaunch?.close()
        ModSession.current = null
        AndroidHost.set(null)
        super.onDestroy()
    }

    /** Runs on the game thread and waits for it (at most two seconds). */
    private fun onGlThread(action: () -> Unit) {
        val done = CountDownLatch(1)
        view.queueEvent {
            try { action() } catch (e: Throwable) { Log.e("FTL", "GL task failed", e) } finally { done.countDown() }
        }
        done.await(2, TimeUnit.SECONDS)
    }

    /** The engine quit (its menu's Quit): save, then back to the launcher. */
    private fun saveAndClose() {
        if (closing) return
        closing = true
        session?.flush()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVE, session?.id).putExtra(EXTRA_CLOSED, true))
        finish()
    }

    // Below Android 13 Back only comes as this key; from 13 the callback in onCreate has it.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // From Android 13 the back callback (onCreate) handles it; the key event must not toggle the menu again.
            if (Build.VERSION.SDK_INT < 33 && event.action == KeyEvent.ACTION_UP) input.escape()
            return true
        }
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> input.onKey(event.keyCode, event, true)
            KeyEvent.ACTION_UP -> input.onKey(event.keyCode, event, false)
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        if (ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) input.onMouse(ev) else super.dispatchGenericMotionEvent(ev)

    private fun showError(e: Throwable, title: String = "The game stopped") {
        // A taken-over game shows only the takeover notice.
        if (isFinishing || session?.frozen == true) return
        val dialog = AlertDialog.Builder(this).setCancelable(false).setPositiveButton("Back to saves") { _, _ -> finish() }
        val id = session?.id
        when {
            MemoryNotice.isOutOfMemory(e) -> dialog.setTitle(MemoryNotice.RAN_OUT_TITLE).setMessage(MemoryNotice.RAN_OUT_TEXT)
            e is ModLaunch.MissingMods -> dialog.setTitle(title).setMessage((e.message ?: "") + "\n\nYour last save is kept on this device.")
            causedByTheRun(e) && id != null -> dialog.setTitle("This run cannot be loaded")
                .setMessage("It is damaged, or it was made for other game files or mods, so this save cannot go on from it. Your other saves are not affected.\n\nYou can keep this save for now, or delete it.")
                .setNegativeButton("Delete this save") { _, _ -> deleteAsked = id; finish() }
            else -> dialog.setTitle(title).setMessage("The game hit an error it cannot recover from. Your last save is kept on this device.\n\n" +
                "If this keeps happening, export the log from Settings, under Help, and send it with your report.\n\nDetails: " + (e.message ?: e.javaClass.simpleName))
        }
        dialog.show()
    }

    private fun causedByTheRun(e: Throwable): Boolean {
        var t: Throwable? = e
        repeat(16) {
            if (t == null) return false
            if (t is RunLoadException) return true
            t = t?.cause
        }
        return false
    }

    companion object {
        const val EXTRA_SAVE = "save"
        const val EXTRA_CLOSED = "closed"

        /** The save the player chose to delete after its run would not load; the launcher asks the usual question. */
        @Volatile var deleteAsked: String? = null

        fun intent(context: Context, saveId: String) = Intent(context, GameActivity::class.java).putExtra(EXTRA_SAVE, saveId)
    }
}
