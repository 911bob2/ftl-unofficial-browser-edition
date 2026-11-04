package ftl.android.app

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ftl.android.app.mods.ModLibrary
import ftl.android.app.net.ApiError
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Txt
import ftl.saves.CatalogueFile
import ftl.saves.objOrNull
import ftl.saves.arr
import ftl.saves.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// "Hyperspace mod without Hyperspace" (docs/MODS.md, mods/HyperspaceRule.kt): the warning before a start, the way to
// Hyperspace.ftl (asking before it downloads) and, for a save that exists, the way to its Advanced mods. The website asks
// the same with the same words (web/mods/hs-needed.mjs).

const val HS_WARNING_TITLE = "Hyperspace mod without Hyperspace"

fun hsWarningText(mod: ModLibrary.Mod) = "${mod.label}${mod.file.version.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""} needs Hyperspace.ftl, " +
    "Hyperspace's own data, loaded first. Without it some Hyperspace images and text are missing."

/** The warning: Enable Hyperspace, Run anyway or Cancel. save: the name of the save that is starting, or null for a new one. */
@Composable
fun HyperspaceWarning(mod: ModLibrary.Mod, save: String?, onEnable: () -> Unit, onRun: () -> Unit, onCancel: () -> Unit) {
    FtlDialog(HS_WARNING_TITLE, onCancel, {
        FtlButton("Cancel", onCancel, kind = ButtonKind.GHOST)
        FtlButton("Run anyway", onRun)
        FtlButton("Enable Hyperspace", onEnable, kind = ButtonKind.PRIMARY)
    }) {
        save?.let { Txt(it, Ftl.label) }
        Txt(hsWarningText(mod))
        Txt(if (save != null) "This save's mods change only under ⋯ > Advanced. Enable Hyperspace takes you there, or to a new save."
            else "Enable Hyperspace puts Hyperspace.ftl first in this save's mods.", Ftl.small)
    }
}

/** A save that exists: its own mods (Advanced, where the change is confirmed) or a new save with Hyperspace. */
@Composable
fun HyperspaceForSave(save: String, onAdvanced: () -> Unit, onNewSave: () -> Unit, onCancel: () -> Unit) {
    FtlDialog("Hyperspace for $save", onCancel, {
        FtlButton("Cancel", onCancel, kind = ButtonKind.GHOST)
        FtlButton("New save with Hyperspace", onNewSave)
        FtlButton("Change this save's mods", onAdvanced, kind = ButtonKind.PRIMARY)
    }) {
        Txt("A save is locked to its mods. Add Hyperspace.ftl to this save's mods under Advanced, where you confirm the change, or start a new save with Hyperspace and these mods.")
    }
}

private sealed class Get {
    object Looking : Get()
    object SignIn : Get()
    object Unreachable : Get()
    class NotOffered(val checking: Boolean) : Get()
    object Ask : Get()
    class Downloading(val percent: Int) : Get()
    class Failed(val why: String) : Get()
}

/**
 * Hyperspace.ftl on this device, asking before anything downloads: its size and source come from our server's catalogue
 * (Hyperspace may be hosted; Multiverse never is). Without an account, offline, or when the server does not offer it, the
 * dialog says what to do instead. onDone gets the file, or null when the player gave up.
 */
@Composable
fun GetHyperspace(onSignIn: () -> Unit, onDone: (ModLibrary.Mod?) -> Unit) {
    val app = FtlApplication.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entry: CatalogueFile = app.mods.hyperspace ?: return LaunchedEffect(Unit) { onDone(null) }
    var state by remember { mutableStateOf<Get>(Get.Looking) }
    var tries by remember { mutableStateOf(0) }
    val what = "${entry.title} ${entry.version} (${entry.file}, ${"%.1f".format(entry.size / 1048576.0)} MB)"
    LaunchedEffect(tries) {
        app.mods.get(entry.sha256)?.let { onDone(it); return@LaunchedEffect }
        val api = app.account.api
        state = if (api == null) Get.SignIn else withContext(Dispatchers.IO) {
            runCatching {
                val files = api.request("GET", "/api/mods/catalogue").json.objOrNull()?.get("files").arr()
                val server = files.firstNotNullOfOrNull { f -> f.objOrNull()?.takeIf { it["id"].str() == entry.id }?.get("server").objOrNull() }
                when (server?.get("reason").str()) {
                    "ready" -> Get.Ask
                    "sign-in" -> Get.SignIn
                    "verifying" -> Get.NotOffered(true)
                    else -> Get.NotOffered(false)
                }
            }.getOrElse { if (it is ApiError && it.status == 401) Get.SignIn else Get.Unreachable }
        }
    }
    fun download() {
        val api = app.account.api ?: run { state = Get.SignIn; return }
        state = Get.Downloading(0)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { app.mods.download(api, entry) { done, total -> state = Get.Downloading(if (total > 0) (done * 100 / total).toInt() else 0) } }
            }
            result.onSuccess { onDone(it) }.onFailure { state = Get.Failed(it.message ?: "The download failed.") }
        }
    }
    val cancel = @Composable { FtlButton("Cancel", { onDone(null) }, kind = ButtonKind.GHOST) }
    val page = @Composable { FtlButton("Official page", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.mods.hyperspacePage))) } }) }
    val elsewhere = @Composable { Txt("Or get Hyperspace.ftl from its official release page (${app.mods.hyperspacePage}) and add it, or the release zip, under Mods.", Ftl.small) }
    when (val s = state) {
        Get.Looking -> FtlDialog("Hyperspace", { onDone(null) }, { cancel() }) { Txt("Looking for Hyperspace on the server…", Ftl.small) }
        Get.SignIn -> FtlDialog("Sign in to download Hyperspace", { onDone(null) }, { cancel(); page(); FtlButton("Sign in", { onDone(null); onSignIn() }, kind = ButtonKind.PRIMARY) }) {
            Txt("$what is not on this device. Signed in, you can download it from this server.")
            elsewhere()
        }
        Get.Unreachable -> FtlDialog("Hyperspace is not on this device", { onDone(null) }, { cancel(); page(); FtlButton("Try again", { tries++ }, kind = ButtonKind.PRIMARY) }) {
            Txt("The server cannot be reached right now (offline?).")
            elsewhere()
        }
        is Get.NotOffered -> FtlDialog(if (s.checking) "Hyperspace is not ready on this server" else "This server does not offer Hyperspace", { onDone(null) }, {
            cancel(); page(); if (s.checking) FtlButton("Try again", { tries++ }, kind = ButtonKind.PRIMARY)
        }) {
            Txt(if (s.checking) "This server is still checking its copy of $what. Try again in a moment." else "$what is not on this device, and this server does not offer it.")
            elsewhere()
        }
        Get.Ask -> FtlDialog("Download Hyperspace?", { onDone(null) }, { cancel(); FtlButton("Download ${"%.1f".format(entry.size / 1048576.0)} MB", { download() }, kind = ButtonKind.PRIMARY) }) {
            Txt("$what is not on this device. Download it from this server (${app.account.user?.server ?: ""})? It is the file our mod catalogue pins, from Hyperspace's own release (GPL-3.0-or-later).")
            Txt("Source: ${app.mods.hyperspacePage}", Ftl.small)
        }
        is Get.Downloading -> FtlDialog("Downloading Hyperspace", {}, {}) { Txt("Downloading $what… ${s.percent}%", Ftl.small) }
        is Get.Failed -> FtlDialog("Hyperspace did not download", { onDone(null) }, { cancel(); page(); FtlButton("Try again", { download() }, kind = ButtonKind.PRIMARY) }) {
            Txt(s.why)
            elsewhere()
        }
    }
}
