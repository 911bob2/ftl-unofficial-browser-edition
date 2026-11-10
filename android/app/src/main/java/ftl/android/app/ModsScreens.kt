package ftl.android.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ftl.android.app.mods.ModLibrary
import ftl.android.app.ui.Badge
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Panel
import ftl.android.app.ui.Txt
import ftl.saves.CatalogueFile
import ftl.saves.Json
import ftl.saves.ModFile
import ftl.saves.arr
import ftl.saves.bool
import ftl.saves.long
import ftl.saves.objOrNull
import ftl.saves.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun mib(bytes: Long) = "%.1f MiB".format(bytes / 1048576.0)

/** The badges of one mod file: Verified (in the website's catalogue) or Custom, and Hyperspace. */
@Composable
fun ModBadges(file: ModFile, hyperspace: Boolean = false) {
    Badge(if (file.verified) "Verified" else "Custom", if (file.verified) Ftl.success else Ftl.accent)
    if (hyperspace) Badge("Hyperspace", Ftl.info)
}

/** A mod file being added. It outlives the Mods screen: leaving it does not stop a download, and coming back shows how far it got. */
class ModWork {
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var finished by mutableStateOf(0)
    var screenOpen = false
}

/**
 * The mod files on this device: add any local file (badged Verified or Custom), download the catalogue's files this
 * server offers or the official MEGA links, remove files. Mod files never leave the device; saves only name them.
 */
@Composable
fun ModsPanel(work: ModWork, scope: CoroutineScope, onClose: () -> Unit) {
    val app = FtlApplication.app
    val context = LocalContext.current
    val resolver = context.contentResolver
    BackHandler(onBack = onClose)
    DisposableEffect(work) {
        work.screenOpen = true
        onDispose { work.screenOpen = false }
    }
    var mods by remember(work.finished) { mutableStateOf(app.mods.list()) }
    val status = work.status
    val busy = work.busy
    var remove by remember { mutableStateOf<ModLibrary.Mod?>(null) }
    var offered by remember { mutableStateOf<List<CatalogueFile>>(emptyList()) }
    LaunchedEffect(Unit) {
        val api = app.account.api ?: return@LaunchedEffect
        offered = withContext(Dispatchers.IO) {
            runCatching {
                val o = api.request("GET", "/api/mods/catalogue").json.objOrNull()
                val ready = o?.get("files").arr().mapNotNull { f ->
                    val e = f.objOrNull() ?: return@mapNotNull null
                    if (e["server"].objOrNull()?.get("available").bool() == true) e["id"].str() else null
                }.toSet()
                app.mods.catalogue.filter { it.id in ready }
            }.getOrDefault(emptyList())
        }
    }
    fun run(label: String, task: ((Long, Long) -> Unit) -> ModLibrary.Mod) {
        work.busy = true
        work.status = label
        scope.launch {
            try {
                val mod = withContext(Dispatchers.IO) {
                    task { done, total -> work.status = if (total > 0) "$label ${done * 100 / total}%" else label }
                }
                work.status = "Added ${mod.label}."
            } catch (e: Exception) {
                work.status = e.message ?: "The mod file could not be added."
            } finally {
                work.busy = false
                work.finished++
                if (!work.screenOpen) Toast.makeText(context, work.status, Toast.LENGTH_LONG).show()
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run("Checking the file…") { p -> app.mods.import(resolver, uri, p) }
    }

    Panel(tab = "Mods") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Mod files", Ftl.h1, Modifier.weight(1f))
            FtlButton("Back to saves", onClose, kind = ButtonKind.GHOST)
        }
        Txt("Add .ftl or .zip mod files from this device. Files the website's catalogue lists are marked Verified; any other file is Custom. Mod files stay on this device: a save only names its mods, and another device needs the same files.", Ftl.small)
        FtlButton(if (busy) "Working…" else "Add a mod file", { pick.launch(arrayOf("*/*")) }, kind = ButtonKind.PRIMARY, enabled = !busy)
        status?.let { Txt(it, Ftl.small.copy(color = if (busy) Ftl.muted else Ftl.ink)) }
        if (mods.isEmpty()) Txt("No mod files on this device yet.", Ftl.small)
        for (m in mods) Row(Modifier.fillMaxWidth().background(Ftl.surface).border(1.dp, Ftl.line).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(m.label + (m.file.version.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""), Ftl.h2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ModBadges(m.file, m.hyperspace) }
                Txt("${m.file.name} · ${mib(m.file.size)} · SHA-256 ${m.sha256.take(12)}…", Ftl.small)
            }
            FtlButton("Remove", { remove = m }, kind = ButtonKind.DANGER, enabled = !busy)
        }
        val missing = offered.filter { c -> mods.none { it.sha256 == c.sha256 } }
        if (missing.isNotEmpty()) {
            Txt("From this server", Ftl.h2)
            for (c in missing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("${c.title} ${c.version} · ${mib(c.size)}", Ftl.body, Modifier.weight(1f))
                FtlButton("Download", { run("Downloading ${c.title}…") { p -> app.mods.download(app.account.api!!, c, p) } }, enabled = !busy)
            }
        }
        val fromMega = app.mods.catalogue.filter { c -> c.id in app.mods.megaLinks && mods.none { it.sha256 == c.sha256 } }
        if (fromMega.isNotEmpty()) {
            Txt("From mega.nz", Ftl.h2)
            Txt("The official links from the mod's page. Downloading connects this device to mega.nz; the file must match the catalogue.", Ftl.small)
            for (c in fromMega) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("${c.title} ${c.version} · ${mib(c.size)}", Ftl.body, Modifier.weight(1f))
                FtlButton("Download", { run("Downloading ${c.title} from mega.nz…") { p -> app.mods.downloadMega(c, p) } }, enabled = !busy)
            }
        }
    }
    ModScripts(mods)

    remove?.let { m ->
        val used = app.store.list().count { s -> s.setup.mods.any { it.sha256 == m.sha256 } }
        FtlDialog("Remove ${m.label}?", { remove = null }, {
            FtlButton("Cancel", { remove = null })
            FtlButton("Remove", { app.mods.remove(m.sha256); mods = app.mods.list(); remove = null }, kind = ButtonKind.DANGER)
        }) {
            Txt(if (used == 0) "No save on this device uses this file." else "$used save(s) on this device use this file. They cannot be played here until you add it again.")
        }
    }
}

/**
 * Chooses an ordered mod list from the device's files: tap to add or remove; files load in the order chosen
 * (Hyperspace first, then the mods built on it).
 */
@Composable
fun ModPicker(library: List<ModLibrary.Mod>, chosen: List<String>, onChange: (List<String>) -> Unit) {
    if (library.isEmpty()) {
        Txt("No mod files on this device. Add them under Mods.", Ftl.small)
        return
    }
    Txt("Tap files in the order they load: Hyperspace first, then the mods built on it.", Ftl.small)
    for (m in library) {
        val at = chosen.indexOf(m.sha256)
        Row(Modifier.fillMaxWidth().border(1.dp, if (at >= 0) Ftl.accent else Ftl.line)
            .clickable { onChange(if (at >= 0) chosen - m.sha256 else chosen + m.sha256) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(if (at >= 0) "${at + 1}." else "–", Ftl.label, Modifier.width(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(m.label + (m.file.version.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""), Ftl.body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ModBadges(m.file, m.hyperspace) }
            }
        }
    }
    // A quiet hint while the chosen files use Hyperspace without Hyperspace.ftl (HyperspaceRule); starting asks once.
    val app = FtlApplication.app
    remember(library, chosen) { app.mods.withoutHyperspace(chosen) }?.let { m ->
        Txt("${m.label} uses Hyperspace, but Hyperspace.ftl is not chosen: some Hyperspace images and text will be missing. Choose Hyperspace.ftl first.",
            Ftl.small.copy(color = Ftl.accent))
    }
}

/**
 * Saves > a save > Advanced: change the mod files a save is locked to, with a warning. first: a file put first for the
 * player to confirm (Hyperspace.ftl, from the "Hyperspace mod without Hyperspace" warning); onChanged runs once the
 * change is made.
 */
@Composable
fun AdvancedDialog(id: String, first: String? = null, onChanged: () -> Unit = {}, onClose: () -> Unit) {
    val app = FtlApplication.app
    val save = remember { app.store.get(id) }
    val library = remember { app.mods.list() }
    if (save == null) {
        FtlDialog("Advanced", onClose, { FtlButton("OK", onClose, kind = ButtonKind.PRIMARY) }) {
            Txt("This save is not on this device yet. Download it (connect and open the launcher) first.")
        }
        return
    }
    val have = library.map { it.sha256 }.toSet()
    // Files this device lacks stay in the list unless removed; they show as missing.
    var chosen by remember { mutableStateOf(save.setup.mods.map { it.sha256 }.let { m -> if (first != null && first in have) listOf(first) + (m - first) else m }) }
    val missing = save.setup.mods.filter { it.sha256 !in have }
    FtlDialog("Mods of ${save.name}", onClose, {
        FtlButton("Cancel", onClose)
        FtlButton("Change mods", {
            val files = chosen.map { sha -> library.firstOrNull { it.sha256 == sha }?.file ?: save.setup.mods.first { it.sha256 == sha } }
            app.store.update(id) { val setup = ftl.saves.Setup.of(files); it.copy(setup = setup, dirty = true, run = it.run?.let { run -> ftl.saves.Sync.runForSetup(run, setup.key) }) }
            app.sync.requestSync()
            onClose()
            onChanged()
        }, kind = ButtonKind.DANGER, enabled = chosen != save.setup.mods.map { it.sha256 })
    }) {
        if (first != null && first in have && first !in save.setup.mods.map { it.sha256 })
            Txt("${library.first { it.sha256 == first }.label} is added first. Change mods to keep it.", Ftl.small.copy(color = Ftl.accent))
        Txt("A save is locked to its mod files. Changing them can break the run: content the new files lack is removed when the run loads. The save keeps its own unlocks.", Ftl.small.copy(color = Ftl.error))
        for (m in missing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val kept = m.sha256 in chosen
            Txt("Missing mod: ${m.name}, ${m.sha256}", Ftl.small.copy(color = Ftl.error), Modifier.weight(1f))
            FtlButton(if (kept) "Remove" else "Keep", { chosen = if (kept) chosen - m.sha256 else chosen + m.sha256 })
        }
        ModPicker(library, chosen) { chosen = it }
    }
}

/**
 * Mod scripts (sweep M1b, docs/HS_LUA.md): the private folder their io and os write to (this account, this device), with
 * Clear mod files, and the mods allowed to run scripts that use files or programs, each with Ask again.
 */
@Composable
private fun ModScripts(mods: List<ModLibrary.Mod>) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val used = remember(tick) { ftl.android.app.mods.LuaSandbox.used(context) }
    val answers = remember(tick) { ftl.android.app.mods.LuaSandbox.answers(context) }
    var confirm by remember { mutableStateOf(false) }
    Panel(tab = "Mod scripts") {
        Txt("Files mod scripts wrote on this device: " + (if (used == 0L) "none" else if (used < 1024) "$used bytes" else "%.1f KiB".format(used / 1024.0)) +
            " (at most 50 MiB, not synced).", Ftl.small)
        FtlButton("Clear mod files", { confirm = true }, kind = ButtonKind.DANGER, enabled = used > 0)
        if (answers.isEmpty()) Txt("No mod has been allowed to run scripts that use files or system commands. The game asks before it starts one.", Ftl.small)
        for ((sha, answer) in answers) Row(Modifier.fillMaxWidth().background(Ftl.surface).border(1.dp, Ftl.line).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val name = mods.firstOrNull { it.sha256 == sha }?.label ?: "SHA-256 ${sha.take(12)}…"
            Column(Modifier.weight(1f)) {
                Txt(name, Ftl.h2)
                Txt((if (answer == "real") "Allowed with Lua sandboxing off: real files and programs." else "Allowed to run in the private folder.") + " SHA-256 ${sha.take(12)}…", Ftl.small)
            }
            FtlButton("Ask again", { ftl.android.app.mods.LuaSandbox.setAnswer(context, sha, null); tick++ }, kind = ButtonKind.GHOST)
        }
        Txt("Lua sandboxing is in Settings.", Ftl.small)
    }
    if (confirm) FtlDialog("Clear mod files?", { confirm = false }, {
        FtlButton("Cancel", { confirm = false })
        FtlButton("Clear", { ftl.android.app.mods.LuaSandbox.clear(context); confirm = false; tick++ }, kind = ButtonKind.DANGER)
    }) {
        Txt("Delete the files mod scripts wrote on this device for this account? Mods that keep settings or progress in files start over.")
    }
}
