package ftl.android.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ftl.android.app.game.GameActivity
import ftl.android.app.icons.AppIcons
import ftl.android.app.settings.SettingsPage
import ftl.android.app.settings.rememberGameImport
import ftl.android.app.settings.rememberSaveFiles
import ftl.android.app.sync.SyncEngine
import ftl.android.app.update.Release
import ftl.android.app.update.Updates
import ftl.android.app.ui.Badge
import ftl.android.app.ui.Banner
import ftl.android.app.ui.SitePage
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.Field
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.FtlIconButton
import ftl.android.app.ui.gear
import ftl.android.app.ui.shipLabel
import ftl.android.app.ui.Header
import ftl.android.app.ui.Panel
import ftl.android.app.ui.Txt
import ftl.android.app.ui.ago
import ftl.android.app.ui.clock
import ftl.android.app.ui.duration
import ftl.saves.CloudSave
import ftl.saves.LocalSave
import ftl.saves.LockInfo
import ftl.saves.LockState
import ftl.saves.SaveMeta
import ftl.saves.SaveNames
import ftl.saves.Setup
import ftl.saves.missingMods
import ftl.saves.SyncAction
import ftl.saves.SyncStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlinx.coroutines.withContext

/** The launcher: account, game data, then saves (docs/ANDROID.md, "Launcher"). */
class MainActivity : ComponentActivity() {
    private val refresh = mutableStateOf(0)
    private val app get() = FtlApplication.app

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app icon over a running game brings the game back instead of stacking a second launcher on it.
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }
        Ftl.loadFonts(this)
        setContent { Launcher() }
    }

    /** The player is leaving the app (Home, Recents), as opposed to the game or a file picker opening over it. */
    private var leaving = false
    private var openingOurOwn = false

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        leaving = !openingOurOwn
    }

    @Deprecated("The base class's; every start from this activity comes through here")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // Android reports starting another activity as the user leaving too.
        openingOurOwn = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStop() {
        super.onStop()
        // Random's turn: never with a game open (the launcher stops when a game opens over it, and stays stopped under one).
        if ((leaving || isFinishing) && !isChangingConfigurations && app.sync.playing == null) thread(name = "ftl-icon-random", isDaemon = true) {
            runCatching { AppIcons(this).rollRandom() }
        }
        leaving = false
    }

    override fun onResume() {
        super.onResume()
        leaving = false
        openingOurOwn = false
        refresh.value++
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { app.account.refresh() }) {
                app.accountChanged()
                refresh.value++
            }
        }
        app.sync.requestSync().invokeOnCompletion { runOnUiThread { refresh.value++ } }
        checkForUpdate()
    }

    /**
     * The automatic update check (docs/ANDROID.md, "Updates"): from the launcher only, so never during a run, in the
     * background, and not before the notice is agreed. UpdateCheck keeps it to once a day.
     */
    private fun checkForUpdate() {
        if (!getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("agreed", false) || app.sync.playing != null || !app.updates.due()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { app.updates.checkIfDue() }
            refresh.value++
        }
    }

    @Composable
    private fun Launcher() {
        val prefs = remember { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
        var agreed by remember { mutableStateOf(prefs.getBoolean("agreed", false)) }
        var mode by remember { mutableStateOf(prefs.getString("mode", null)) }
        var user by remember { mutableStateOf(app.account.user) }
        LaunchedEffect(refresh.value) { user = app.account.user }
        val launcherScope = rememberCoroutineScope()
        val tick = refresh.value
        val data = remember(tick) { app.gameData.info }
        val offer = remember(tick) { app.updates.banner() }
        val setMode = { m: String? -> prefs.edit().putString("mode", m).apply(); mode = m }
        val firstSteps = !agreed || mode == null || mode == "cloud" && user == null
        var showSettings by rememberSaveable { mutableStateOf(false) }
        // Sign out's question while it is open: whether a save has unsynced progress.
        var signOutAsk by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val focus = LocalFocusManager.current
        signOutAsk?.let { unsynced ->
            SignOutDialog(unsynced, onSignOut = {
                signOutAsk = null
                // The logout request goes off the main thread (Android refuses network calls there).
                launcherScope.launch {
                    withContext(Dispatchers.IO) {
                        app.sync.releaseHeld()
                        app.account.signOut()
                    }
                    app.accountChanged()
                    user = null
                    setMode(null)
                }
            }, onCancel = { signOutAsk = null })
        }
        SitePage {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                // On a short screen (landscape, or the keyboard is open) the header and banner give way to the content.
                val short = maxHeight < 480.dp
                val header = @Composable {
                Header("Unofficial\nbrowser edition") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (user != null) {
                        // With little room (a large font) these give way, not the cog.
                        Badge(user!!.username, modifier = Modifier.weight(1f, fill = false))
                        FtlButton(SignOut.YES, {
                            showSettings = false
                            // It asks first (SignOut), saying so when a save has progress the account does not have yet.
                            launcherScope.launch {
                                signOutAsk = withContext(Dispatchers.IO) { SignOut.unsynced(app.store.list()) }
                            }
                        }, Modifier.weight(1f, fill = false), kind = ButtonKind.GHOST)
                    } else if (mode == "offline") FtlButton("Enable cloud saves", { showSettings = false; setMode("cloud") }, Modifier.weight(1f, fill = false), kind = ButtonKind.GHOST)
                    if (agreed) FtlIconButton(if (showSettings) "Close settings" else "Settings", {
                        focus.clearFocus()
                        showSettings = !showSettings
                        refresh.value++
                    }, kind = if (showSettings) ButtonKind.PRIMARY else ButtonKind.GHOST) { gear(it) }
                    }
                }
                }
                Column(Modifier.fillMaxSize()) {
                if (!short) header()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Settings lies over the screen it was opened from, which stays as it was: what is typed, what is under way, the place in the list.
                    Column(Modifier.fillMaxSize().then(if (showSettings) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier).verticalScroll(rememberScrollState())) {
                        if (short) header()
                        if (firstSteps) Banner(if (short) 96 else 190)
                        Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.TopCenter) {
                            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                if (agreed) offer?.let { r -> UpdateBanner(r, { Updates.open(this@MainActivity, r) }) { app.updates.dismiss(r); refresh.value++ } }
                                when {
                                    !agreed -> Notice { prefs.edit().putBoolean("agreed", true).apply(); agreed = true; checkForUpdate() }
                                    mode == null -> ModeChoice { setMode(it) }
                                    mode == "cloud" && user == null -> SignIn({ user = it; refresh.value++; app.sync.requestSync() }, { setMode("offline") })
                                    data == null -> DataImport { refresh.value++ }
                                    else -> Saves(tick) { setMode("cloud") }
                                }
                            }
                        }
                    }
                    if (showSettings) SettingsPage(!firstSteps && data != null, { refresh.value++ }) { showSettings = false; refresh.value++ }
                }
                }
            }
        }
    }

    /** A newer release is out: one quiet row above the screen's content, until Download installs it or Not now puts it off. */
    @Composable
    private fun UpdateBanner(r: Release, onDownload: () -> Unit, onNotNow: () -> Unit) {
        FlowRow(Modifier.fillMaxWidth().background(Ftl.surface).border(1.dp, Ftl.line).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("Version ${r.version} is available", Ftl.label.copy(color = Ftl.accent))
                Txt("You have ${app.updates.current}. Download opens its page on GitHub.", Ftl.small)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FtlButton("Not now", onNotNow, kind = ButtonKind.GHOST)
                FtlButton("Download", onDownload, kind = ButtonKind.PRIMARY)
            }
        }
    }

    @Composable
    private fun Notice(onAgree: () -> Unit) = Panel(tab = "Notice") {
        Txt("Unofficial fan project", Ftl.h1)
        Txt("FTL: Faster Than Light belongs to Subset Games. This app is not made, endorsed or supported by Subset Games. You need your own copy of FTL to play.")
        FtlButton("Agree", onAgree, kind = ButtonKind.PRIMARY)
    }

    @Composable
    private fun ModeChoice(onChoose: (String) -> Unit) = Panel(tab = "Saves") {
        Txt("How do you want to play?", Ftl.h1)
        Txt("Cloud saves keep your saves on your account, so you can continue them in the browser. Offline keeps everything on this device; you can turn on cloud saves later.", Ftl.small)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FtlButton("Cloud saves", { onChoose("cloud") }, kind = ButtonKind.PRIMARY)
            FtlButton("Play offline", { onChoose("offline") })
        }
    }

    @Composable
    private fun SignIn(onSignedIn: (ftl.android.app.sync.Account.User) -> Unit, onOffline: () -> Unit) {
        val scope = rememberCoroutineScope()
        var server by remember { mutableStateOf(app.account.server) }
        var name by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var showServer by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        Panel(tab = "Account") {
            Txt("Sign in", Ftl.h1)
            Txt("Use the account you play with in the browser. Your saves sync both ways; play continues offline and syncs later.", Ftl.small)
            Field("Username", name, { name = it })
            Field("Password", password, { password = it }, password = true)
            if (showServer) Field("Server", server, { server = it })
            else Txt("Server: $server (change)", Ftl.small.copy(color = Ftl.secondary), Modifier.clickable { showServer = true })
            error?.let { Txt(it, Ftl.small.copy(color = Ftl.error)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FtlButton(if (busy) "Signing in…" else "Sign in", {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val u = withContext(Dispatchers.IO) { app.account.signIn(server, name, password) }
                            password = ""
                            app.accountChanged()
                            // Saves made while playing without an account join it and upload, as "Enable cloud saves" does on the website.
                            val moved = withContext(Dispatchers.IO) { runCatching { app.sync.adoptLocal() }.getOrDefault(0) }
                            if (moved > 0) Toast.makeText(this@MainActivity, if (moved == 1) "Your save from playing offline is now in your account."
                                else "Your $moved saves from playing offline are now in your account.", Toast.LENGTH_LONG).show()
                            onSignedIn(u)
                        } catch (e: Exception) {
                            error = when ((e as? ftl.android.app.net.ApiError)?.status) {
                                401 -> "The username or password is incorrect."
                                429 -> "Too many attempts. Please wait before trying again."
                                else -> if (e is IllegalArgumentException || e is java.net.URISyntaxException || e is java.net.MalformedURLException) "That server address is not valid."
                                    else e.message ?: "Sign-in failed. Check your connection."
                            }
                        } finally {
                            busy = false
                        }
                    }
                }, kind = ButtonKind.PRIMARY, enabled = !busy && name.isNotBlank() && password.isNotEmpty())
                FtlButton("Play offline", onOffline)
            }
        }
    }

    @Composable
    private fun DataImport(onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val expected = app.account.api?.let { api -> runCatching { (api.config()["assetVersion"] as? ftl.saves.Json.Str)?.value }.getOrNull() }
                        app.gameData.import(contentResolver, uri, expected) { done, total ->
                            status = if (total > 0) "Copying and checking… ${done * 100 / total}%" else "Copying and checking…"
                        }
                    }
                    status = null
                    onDone()
                } catch (e: Exception) {
                    status = e.message ?: "The file could not be used."
                } finally {
                    busy = false
                }
            }
        }
        val server = app.account.user?.serverAssets == true
        Panel(tab = "Game files") {
            Txt("Your game files", Ftl.h1)
            if (server) {
                Txt("Your account can use this server's game files. Download them once (about 280 MB); play then works offline.")
                FtlButton(if (busy) "Working…" else "Download game files", {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                app.gameData.downloadFromServer(app.account.api!!) { done, total ->
                                    status = "Downloading… ${done / (1 shl 20)} of ${total / (1 shl 20)} MB"
                                }
                            }
                            status = null
                            onDone()
                        } catch (e: Exception) {
                            status = e.message ?: "The download failed. Check your connection and retry."
                        } finally {
                            busy = false
                        }
                    }
                }, kind = ButtonKind.PRIMARY, enabled = !busy)
                Txt("Or use your own copy:", Ftl.small)
            }
            Txt("The game's graphics, sound and text come from the ftl.dat of your own FTL 1.6 installation (Steam, GOG or Humble). Copy it to this device and choose it here. It stays on this device and is never uploaded.")
            Txt("On Windows and Linux it is in the game's install folder; on macOS inside the app bundle under Contents/Resources.", Ftl.small)
            FtlButton(if (busy) "Working…" else "Choose ftl.dat", { pick.launch(arrayOf("*/*")) }, kind = ButtonKind.PRIMARY, enabled = !busy)
            status?.let { Txt(it, Ftl.small.copy(color = if (busy) Ftl.muted else Ftl.error)) }
        }
    }

    /** One row of the list: the device copy, the cloud's, or both. */
    private class Row(val id: String, val local: LocalSave?, val cloud: CloudSave?) {
        val name get() = local?.name ?: cloud!!.name
        val setup get() = local?.setup ?: cloud!!.setup
        val meta get() = if ((cloud?.revision ?: 0) > (local?.base ?: 0) && local?.dirty != true) cloud!!.meta else local?.meta ?: cloud!!.meta
        val hasRun get() = local?.hasRun == true || cloud?.hasRun == true
        val playedAt get() = meta.playedOn?.at ?: local?.createdUtc ?: cloud?.createdUtc
    }

    private sealed class Ask {
        class Lock(val id: String, val lock: LockInfo, val keepMine: Boolean?, val hsAsked: Boolean = false) : Ask()
        class Conflict(val id: String) : Ask()
        class Overridden(val id: String, val by: String, val at: String?) : Ask()
        class Deleted(val id: String) : Ask()
        class NewSave(val prefill: List<String> = emptyList()) : Ask()
        class Advanced(val id: String, val first: String? = null) : Ask()
        /** "Hyperspace mod without Hyperspace" before a save starts, then the way to Hyperspace.ftl and to the save's mods. */
        class HsMissing(val id: String, val name: String, val mod: ftl.android.app.mods.ModLibrary.Mod, val keepMine: Boolean?) : Ask()
        class HsGet(val id: String, val name: String) : Ask()
        class HsSave(val id: String, val name: String) : Ask()
        class Snapshots(val id: String, val name: String) : Ask()
        class Rename(val id: String, val name: String) : Ask()
        class Delete(val id: String, val name: String) : Ask()
        class Message(val title: String, val text: String) : Ask()
        class LowMemory(val id: String, val keepMine: Boolean?, val hsAsked: Boolean = false) : Ask()
    }

    @Composable
    private fun Saves(tick: Int, onSignIn: () -> Unit) {
        val scope = rememberCoroutineScope()
        val sync by app.sync.state.collectAsState()
        var locals by remember(tick) { mutableStateOf(app.store.list()) }
        LaunchedEffect(tick, sync.lastSync) { locals = withContext(Dispatchers.IO) { app.store.list() } }
        var ask by remember { mutableStateOf<Ask?>(null) }
        LaunchedEffect(Unit) {
            if (withContext(Dispatchers.IO) { MemoryNotice.takeRanOut(this@MainActivity) }) ask = Ask.Message(MemoryNotice.RAN_OUT_TITLE, MemoryNotice.RAN_OUT_TEXT)
        }
        var busy by remember { mutableStateOf<String?>(null) }
        val signedIn = app.account.user != null
        val rows = (locals.map { it.id } + sync.cloud.keys).distinct().map { Row(it, locals.firstOrNull { l -> l.id == it }, sync.cloud[it]) }
            .sortedByDescending { it.playedAt ?: "" }
        val reload = { locals = app.store.list(); refresh.value++ }
        var showMods by remember { mutableStateOf(false) }
        val modWork = remember { ModWork() }
        val saveFiles = rememberSaveFiles { locals = app.store.list(); refresh.value++ }
        val gameImport = rememberGameImport { locals = app.store.list(); refresh.value++ }
        if (showMods) {
            ModsPanel(modWork, scope) { showMods = false; reload() }
            return
        }
        val have = remember(tick, modWork.finished) { app.mods.list().map { it.sha256 }.toSet() }
        // The game could not load a save's run and the player chose to delete it: the usual question, here where deleting lives.
        LaunchedEffect(tick) {
            GameActivity.deleteAsked?.let { id ->
                GameActivity.deleteAsked = null
                rows.firstOrNull { it.id == id }?.let { ask = Ask.Delete(it.id, it.name) }
            }
        }

        /** keepMine: the answer to "changed in two places", applied once this device has the lock. */
        /** hsAsked: the "Hyperspace mod without Hyperspace" warning was answered in this start (it asks once per start). */
        fun play(id: String, override: Boolean = false, keepMine: Boolean? = null, memoryAnswered: Boolean = false, hsAsked: Boolean = false) {
            busy = id
            scope.launch {
                try {
                    val outcome = withContext(Dispatchers.IO) {
                        if (signedIn) when (app.sync.prepare(id)?.action) {
                            SyncAction.ASK_CONFLICT -> if (keepMine == null) return@withContext Ask.Conflict(id)
                            SyncAction.NOTIFY_OVERRIDDEN -> return@withContext app.store.get(id)?.overridden.let { Ask.Overridden(id, it?.device ?: "another device", it?.at) }
                            SyncAction.ASK_DELETED -> return@withContext Ask.Deleted(id)
                            else -> {}
                        }
                        // Refused here, before the lock: a save this device cannot play stays free for the others.
                        val save = app.store.get(id)
                            ?: return@withContext Ask.Message("Not on this device", "This save is in the cloud and has not been downloaded here yet. Connect to the internet to download it.")
                        val missing = missingMods(save.setup, app.mods.list().map { m -> m.sha256 }.toSet())
                        if (missing.isNotEmpty()) return@withContext Ask.Message("Mods missing", "This save needs mod files this device does not have:\n" +
                            missing.joinToString("\n") { "${it.name}, ${it.sha256}" } + "\n\nAdd them under Mods, or change the save's mods under ⋯ > Advanced.")
                        if (!hsAsked) app.mods.withoutHyperspace(save.setup.mods.map { it.sha256 })?.let { return@withContext Ask.HsMissing(id, save.name, it, keepMine) }
                        if (!memoryAnswered && !MemoryNotice.warnedAlready(this@MainActivity, id) &&
                            MemoryNotice.tooSmallFor(this@MainActivity, save.setup.mods.sumOf { it.size })) return@withContext Ask.LowMemory(id, keepMine, hsAsked)
                        if (!signedIn) return@withContext null
                        when (val r = app.sync.lock(id, override)) {
                            is SyncEngine.LockResult.Held -> Ask.Lock(id, r.lock, keepMine, hsAsked)
                            is SyncEngine.LockResult.Overridden -> Ask.Overridden(id, r.by?.device ?: "another device", r.at)
                            else -> { if (keepMine != null) app.sync.resolveConflict(id, keepMine); null }
                        }
                    }
                    if (outcome != null) ask = outcome
                    else startActivity(GameActivity.intent(this@MainActivity, id))
                } catch (e: Exception) {
                    ask = Ask.Message("Could not start", e.message ?: "Try again.")
                } finally {
                    busy = null
                }
            }
        }

        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(10.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Column {
                Txt("Saves", Ftl.h1)
                Txt(when {
                    !signedIn -> "Offline: saves are on this device only."
                    sync.syncing -> "Syncing…"
                    !sync.online -> "Offline: saves stay on this device and sync later."
                    sync.error != null -> sync.error!!
                    sync.lastSync != null -> "Synced ${clock(sync.lastSync)}"
                    else -> ""
                }, Ftl.small)
                if (modWork.busy) modWork.status?.let { Txt(it, Ftl.small) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FtlButton("Mods", { showMods = true })
                FtlButton("New save", { ask = Ask.NewSave() }, kind = ButtonKind.PRIMARY, enabled = rows.size < ftl.saves.Limits.SAVES)
            }
        }
        if (rows.isEmpty()) Panel {
            Txt("No saves yet. Create one with New save, or bring one in from a file.", Ftl.small)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FtlButton("Import a save", saveFiles.import)
                FtlButton("Import from FTL", gameImport.start)
            }
        }
        for (r in rows) SaveRow(r, sync, busy == r.id, have, onPlay = { play(r.id) }, onRename = { ask = Ask.Rename(r.id, r.name) },
            onExport = { saveFiles.export(r.id, r.name) }, onAdvanced = { ask = Ask.Advanced(r.id) }, onDelete = { ask = Ask.Delete(r.id, r.name) },
            onSnapshots = if (signedIn && r.cloud != null) ({ ask = Ask.Snapshots(r.id, r.name) }) else null)
        Spacer(Modifier.height(24.dp))
        saveFiles.dialogs()
        gameImport.dialogs()

        when (val a = ask) {
            null -> {}
            is Ask.Lock -> FtlDialog("In use on ${a.lock.device}", { ask = null }, {
                FtlButton("Cancel", { ask = null })
                FtlButton("Override lock", { ask = null; play(a.id, override = true, keepMine = a.keepMine, hsAsked = a.hsAsked) }, kind = ButtonKind.DANGER)
            }) {
                Txt("${a.lock.device} has this save open · last seen ${clock(a.lock.lastSeen)}.")
                Txt("It may have progress and unlocks that have not synced yet. Overriding discards them; nothing is kept.", Ftl.small.copy(color = Ftl.error))
            }
            is Ask.Conflict -> FtlDialog("This save changed in two places", { ask = null }, {
                FtlButton("Use the cloud copy", { ask = null; play(a.id, keepMine = false) })
                FtlButton("Keep this device's copy", { ask = null; play(a.id, keepMine = true) }, kind = ButtonKind.PRIMARY)
            }) {
                Txt("The cloud has a newer version than the one this device's unsynced progress started from.")
                Txt("Use the cloud copy: this device's unsynced progress and unlocks are discarded. Keep this device's copy: it replaces the cloud version, unlocks too (a recovery snapshot of the cloud version is kept).", Ftl.small)
            }
            is Ask.Overridden -> FtlDialog("Save taken over", { ask = null }, {
                FtlButton("OK", { ask = null; scope.launch { withContext(Dispatchers.IO) { runCatching { app.sync.acknowledgeOverride(a.id, a.at) } }; reload() } }, kind = ButtonKind.PRIMARY)
            }) {
                Txt("This save was taken over on ${a.by}. Progress and unlocks on this device since its last sync were discarded.")
            }
            is Ask.Deleted -> FtlDialog("Deleted on another device", { ask = null }, {
                FtlButton("Delete here too", { ask = null; scope.launch { withContext(Dispatchers.IO) { app.sync.resolveDeleted(a.id, false) }; reload() } }, kind = ButtonKind.DANGER)
                FtlButton("Upload again", { ask = null; scope.launch { withContext(Dispatchers.IO) { app.sync.resolveDeleted(a.id, true) }; reload() } }, kind = ButtonKind.PRIMARY)
            }) {
                Txt("This save was deleted on another device, but this device has progress that never synced.")
            }
            is Ask.Advanced -> AdvancedDialog(a.id, a.first, onChanged = { if (a.first != null) play(a.id, hsAsked = true) }) { ask = null; reload() }
            is Ask.HsMissing -> HyperspaceWarning(a.mod, a.name, onEnable = {
                ask = if (app.mods.hyperspace?.let { app.mods.get(it.sha256) } != null) Ask.HsSave(a.id, a.name) else Ask.HsGet(a.id, a.name)
            }, onRun = { ask = null; play(a.id, keepMine = a.keepMine, hsAsked = true) }, onCancel = { ask = null })
            is Ask.HsGet -> GetHyperspace(onSignIn) { got -> ask = if (got != null) Ask.HsSave(a.id, a.name) else null; reload() }
            is Ask.HsSave -> HyperspaceForSave(a.name, onAdvanced = { ask = Ask.Advanced(a.id, app.mods.hyperspace?.sha256) }, onNewSave = {
                val hs = app.mods.hyperspace?.sha256
                ask = Ask.NewSave(listOfNotNull(hs) + (app.store.get(a.id)?.setup?.mods?.map { it.sha256 } ?: emptyList()).filter { it != hs })
            }, onCancel = { ask = null })
            is Ask.Snapshots -> SnapshotsDialog(a.id, a.name) { done ->
                ask = null
                reload()
                done?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
            is Ask.NewSave -> {
                var name by remember { mutableStateOf(SaveNames.next(rows.map { it.name })) }
                var library by remember { mutableStateOf(app.mods.list()) }
                var chosen by remember { mutableStateOf(a.prefill.filter { sha -> library.any { it.sha256 == sha } }) }
                // "Hyperspace mod without Hyperspace", once per Create: Enable Hyperspace puts it first here, for the player to see.
                var hsWarn by remember { mutableStateOf<ftl.android.app.mods.ModLibrary.Mod?>(null) }
                var hsGet by remember { mutableStateOf(false) }
                var hsAsked by remember { mutableStateOf(false) }
                var hsNote by remember { mutableStateOf(if (a.prefill.isNotEmpty()) "Hyperspace.ftl is added first. Create to go on." else null) }
                fun create() {
                    val clean = SaveNames.clean(name) ?: return
                    val setup = Setup.of(chosen.map { sha -> library.first { it.sha256 == sha }.file })
                    val s = LocalSave(UUID.randomUUID().toString(), clean, setup, Instant.now().toString(), SaveMeta(),
                        base = 0, dirty = true, hasRun = false)
                    app.store.put(s)
                    ask = null
                    reload()
                    app.sync.requestSync()
                }
                fun hyperspaceFirst(hs: String) {
                    library = app.mods.list()
                    chosen = listOf(hs) + chosen.filter { it != hs }
                    hsNote = "Hyperspace.ftl is added first. Create to go on."
                }
                hsWarn?.let { m ->
                    HyperspaceWarning(m, null, onEnable = {
                        hsWarn = null
                        hsAsked = true
                        val hs = app.mods.hyperspace?.sha256?.takeIf { app.mods.get(it) != null }
                        if (hs != null) hyperspaceFirst(hs) else hsGet = true
                    }, onRun = { hsWarn = null; hsAsked = true; create() }, onCancel = { hsWarn = null })
                }
                if (hsGet) GetHyperspace(onSignIn) { got -> hsGet = false; if (got != null) hyperspaceFirst(got.sha256) else hsAsked = false }
                FtlDialog("New save", { ask = null }, {
                    FtlButton("Cancel", { ask = null })
                    FtlButton("Create", {
                        if (!hsAsked) app.mods.withoutHyperspace(chosen)?.let { hsWarn = it; return@FtlButton }
                        create()
                    }, kind = ButtonKind.PRIMARY, enabled = SaveNames.clean(name) != null)
                }) {
                    Field("Name", name, { name = it })
                    Txt("A new save starts like a fresh install: only the default ships are unlocked.", Ftl.small)
                    Txt("Mods", Ftl.label)
                    Txt(if (chosen.isEmpty()) "No mods: the game as it ships." else "A save keeps its mod files; change them later only under ⋯ > Advanced.", Ftl.small)
                    ModPicker(library, chosen) { chosen = it }
                    hsNote?.let { Txt(it, Ftl.small.copy(color = Ftl.accent)) }
                    Txt("Or import a save from a file, exported from this app or the website.", Ftl.small)
                    FtlButton("Import a save", { ask = null; saveFiles.import() })
                    Txt("Or bring your profile and run from FTL on a computer, with or without Hyperspace and Multiverse.", Ftl.small)
                    FtlButton("Import from FTL", { ask = null; gameImport.start() })
                }
            }
            is Ask.Rename -> {
                var name by remember { mutableStateOf(a.name) }
                FtlDialog("Rename", { ask = null }, {
                    FtlButton("Cancel", { ask = null })
                    FtlButton("Rename", {
                        val clean = SaveNames.clean(name) ?: return@FtlButton
                        if (app.store.update(a.id) { it.copy(name = clean, nameDirty = true) } == null) {
                            sync.cloud[a.id]?.let { c -> scope.launch { withContext(Dispatchers.IO) { runCatching { app.account.api?.rename(a.id, clean) } }; app.sync.requestSync() } }
                        }
                        ask = null
                        reload()
                        app.sync.requestSync()
                    }, kind = ButtonKind.PRIMARY, enabled = SaveNames.clean(name) != null)
                }) { Field("Name", name, { name = it }) }
            }
            is Ask.Delete -> FtlDialog("Delete ${a.name}?", { ask = null }, {
                FtlButton("Cancel", { ask = null })
                FtlButton("Delete", {
                    ask = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (sync.cloud.containsKey(a.id)) app.account.api?.deleteSave(a.id)
                                app.store.delete(a.id)
                            }
                        } catch (e: Exception) {
                            ask = Ask.Message("Not deleted", e.message ?: "Try again.")
                        }
                        reload()
                        app.sync.requestSync()
                    }
                }, kind = ButtonKind.DANGER)
            }) { Txt(if (sync.cloud.containsKey(a.id)) "The save is deleted on this device and in your account, with its older copies. This cannot be undone." else "The save is deleted from this device. This cannot be undone.") }
            is Ask.LowMemory -> FtlDialog(MemoryNotice.TOO_SMALL_TITLE, { ask = null }, {
                FtlButton("Cancel", { ask = null })
                FtlButton("Play anyway", {
                    MemoryNotice.markWarned(this@MainActivity, a.id)
                    ask = null
                    play(a.id, keepMine = a.keepMine, memoryAnswered = true, hsAsked = a.hsAsked)
                }, kind = ButtonKind.PRIMARY)
            }) { Txt(MemoryNotice.TOO_SMALL_TEXT) }
            is Ask.Message -> FtlDialog(a.title, { ask = null }, { FtlButton("OK", { ask = null }, kind = ButtonKind.PRIMARY) }) { Txt(a.text) }
        }
    }

    @Composable
    private fun SaveRow(r: Row, sync: SyncEngine.State, busy: Boolean, have: Set<String>, onPlay: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onAdvanced: () -> Unit, onDelete: () -> Unit, onSnapshots: (() -> Unit)? = null) {
        val decision = sync.decisions[r.id]
        val lock = r.cloud?.lock?.takeIf { app.sync.lockState(r.cloud) == LockState.OTHER }
        var menu by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().background(Ftl.surface).border(1.dp, Ftl.line).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Txt(r.name, Ftl.h2)
                        Badge(if (r.setup.vanilla) "No mods" else if (r.setup.mods.size == 1) r.setup.mods[0].name else "${r.setup.mods.size} mods")
                        when {
                            app.account.user == null || r.cloud == null && r.local?.base == 0L -> Badge("This device", Ftl.info)
                            decision?.status == SyncStatus.CONFLICT -> Badge("Changed in two places", Ftl.error)
                            decision?.status == SyncStatus.OVERRIDDEN -> Badge("Taken over", Ftl.error)
                            decision?.status == SyncStatus.DELETED -> Badge("Deleted elsewhere", Ftl.error)
                            decision?.status == SyncStatus.PENDING || r.local?.dirty == true -> Badge("Not synced", Ftl.accent)
                            r.local == null || r.local.hasRun != true && r.cloud?.hasRun == true -> Badge("Cloud", Ftl.secondary)
                        }
                    }
                    val sum = r.meta.summary
                    Txt(if (!r.hasRun) "Empty" else listOfNotNull(shipLabel(sum.ship), sum.sector?.let { "sector $it" }, sum.scrap?.let { "$it scrap" },
                        sum.hull?.let { "hull $it" }, sum.playSeconds.takeIf { it > 0 }?.let { duration(it) }, ago(r.playedAt)).joinToString(" · "), Ftl.small)
                    r.meta.playedOn?.let { p -> Txt("Last played on ${p.device} (${p.platform})", Ftl.small) }
                    lock?.let { Txt("In use on ${it.device} · last seen ${clock(it.lastSeen)}", Ftl.small.copy(color = Ftl.accent)) }
                    for (m in missingMods(r.setup, have)) Txt("Missing mod: ${m.name}, ${m.sha256.take(12)}…", Ftl.small.copy(color = Ftl.error))
                }
                FtlButton(if (busy) "…" else if (r.hasRun) "Play" else "Start", onPlay, kind = ButtonKind.PRIMARY, enabled = !busy)
                FtlButton("⋯", { menu = !menu }, kind = ButtonKind.NORMAL, description = "More for ${r.name}")
            }
            if (menu) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FtlButton("Rename", { menu = false; onRename() })
                FtlButton("Export", { menu = false; onExport() })
                FtlButton("Advanced", { menu = false; onAdvanced() })
                onSnapshots?.let { FtlButton("Recovery snapshots", { menu = false; it() }) }
                FtlButton("Delete", { menu = false; onDelete() }, kind = ButtonKind.DANGER)
            }
        }
    }
}
