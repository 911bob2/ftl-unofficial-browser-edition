package ftl.android.app.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ftl.android.app.FtlApplication
import ftl.android.app.ModPicker
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.ChoiceRow
import ftl.android.app.ui.Field
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Txt
import ftl.android.app.ui.Working
import ftl.saves.Limits
import ftl.saves.NativeSave
import ftl.saves.PlayedOn
import ftl.saves.SaveFile
import ftl.saves.Setup
import ftl.saves.missingMods
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves > Import from FTL (docs/SAVES.md, "Saves from the original game"): the profile and run of FTL on a computer,
 * or of Hyperspace and Multiverse, become one new save. savecore's [NativeSave] checks the files here; the engine
 * converts them the first time the save is played.
 */
object GameImportIo {
    /** The name and bytes of one chosen file, read no further than the largest file taken. */
    fun read(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray> {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: throw NativeSave.Invalid("$name could not be opened.")
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > NativeSave.RUN_BYTES) throw NativeSave.Invalid("$name is too large to be a save of FTL.")
            }
        }
        return name to out.toByteArray()
    }

    /** The chosen files as one import; throws [NativeSave.Invalid] with the reason. */
    fun pick(resolver: ContentResolver, uris: List<Uri>): NativeSave.Picked {
        if (uris.size > 2) throw NativeSave.Invalid("Choose at most two files: the profile and the run.")
        return NativeSave.pick(uris.map { val (name, bytes) = read(resolver, it); NativeSave.identify(name, bytes) })
    }

    fun add(picked: NativeSave.Picked, name: String, setup: Setup): String {
        val app = FtlApplication.app
        val unique = SaveFile.uniqueName(name, SaveFileIo.taken())
        val at = Instant.now().toString()
        app.store.put(picked.newSave(UUID.randomUUID().toString(), unique, setup, PlayedOn("android", app.identity.name, app.identity.deviceId, at)))
        app.sync.requestSync()
        return unique
    }
}

class GameImportActions(val start: () -> Unit, val dialogs: @Composable () -> Unit)

@Composable
fun rememberGameImport(onChanged: () -> Unit): GameImportActions {
    val app = FtlApplication.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var guide by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<NativeSave.Picked?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                picked = withContext(Dispatchers.IO) { GameImportIo.pick(context.contentResolver, uris) }
                guide = false
            } catch (e: NativeSave.Invalid) {
                android.util.Log.i("FTL", "Import from FTL refused: ${e.message}")
                problem = e.message
            } catch (e: IOException) {
                problem = "The file could not be read."
            } catch (e: OutOfMemoryError) {
                problem = "This file is too large for this device's memory."
            } finally {
                busy = false
            }
        }
    }
    return GameImportActions(start = { problem = null; guide = true }, dialogs = {
        if (guide) FtlDialog("Import from FTL", { guide = false }, {
            FtlButton("Cancel", { guide = false })
            FtlButton(if (busy) "Checking…" else "Choose files", { problem = null; open.launch(arrayOf("*/*")) }, kind = ButtonKind.PRIMARY,
                enabled = !busy && SaveFileIo.count() < Limits.SAVES)
        }) {
            Txt("Bring your profile and your run from FTL on a computer (Steam, GOG or another store). The files are read on this device and become a new save.", Ftl.small)
            Txt("Choose one or both files", Ftl.label)
            Txt("ae_prof.sav: your profile (unlocked ships, achievements, scores).\ncontinue.sav: the run you left with Save and Quit.\n" +
                "With Hyperspace: hs_prof.sav and hs_continue.sav. With Multiverse: hs_mv_prof.sav and hs_mv_continue.sav.", Ftl.small)
            Txt("Where they are on the computer", Ftl.label)
            for ((os, folder) in GUIDE_FOLDERS) Txt("$os: $folder", Ftl.small)
            Txt("Copy them to this device first (a USB cable, a cloud drive or a message to yourself), then choose them here. With Steam Cloud, start FTL once on the computer so the latest files are there.", Ftl.small)
            if (busy) Working()
            if (SaveFileIo.count() >= Limits.SAVES) Txt(ftl.android.app.SaveWords.words("savesFull", "count" to Limits.SAVES.toString()), Ftl.small.copy(color = Ftl.error))
            problem?.let { Txt(it, Ftl.small.copy(color = Ftl.error)) }
        }
        picked?.let { p ->
            ImportDialog(p, { picked = null }) { name, setup ->
                picked = null
                scope.launch {
                    try {
                        val made = withContext(Dispatchers.IO) { GameImportIo.add(p, name, setup) }
                        Toast.makeText(context, "Imported as $made.", Toast.LENGTH_LONG).show()
                        onChanged()
                    } catch (e: IOException) {
                        problem = "The save could not be stored. Is this device's storage full?"
                        guide = true
                    }
                }
            }
        }
    })
}

/** Where FTL keeps its saves on a computer. */
val GUIDE_FOLDERS = listOf(
    "Windows" to "Documents\\My Games\\FasterThanLight",
    "macOS" to "~/Library/Application Support/FasterThanLight",
    "Linux" to "~/.local/share/FasterThanLight (with Proton: the same Windows folder inside the game's prefix)",
)

@Composable
private fun ImportDialog(p: NativeSave.Picked, onCancel: () -> Unit, onImport: (String, Setup) -> Unit) {
    val app = FtlApplication.app
    val library = remember { app.mods.list() }
    val presets = remember { app.mods.presets.filter { it.hyperspace } }
    val suggested = remember { NativeSave.presetFor(p.prefix, presets) }
    val defaultName = p.run?.summary?.ship?.let { "FTL: $it" } ?: "FTL profile"
    var name by remember { mutableStateOf(SaveFile.uniqueName(ftl.saves.SaveNames.clean(defaultName) ?: "FTL", SaveFileIo.taken())) }
    // For a mod: the preset the prefix names, another preset, or the files the player picks.
    var chosenPreset by remember { mutableStateOf(suggested?.id) }
    var custom by remember { mutableStateOf(emptyList<String>()) }
    val vanilla = p.setup == "vanilla"
    val setup: Setup? = when {
        vanilla -> Setup.VANILLA
        chosenPreset != null -> presets.firstOrNull { it.id == chosenPreset }?.setup
        custom.isNotEmpty() -> Setup.of(custom.map { sha -> library.first { it.sha256 == sha }.file })
        else -> null
    }
    FtlDialog("Import from FTL", onCancel, {
        FtlButton("Cancel", onCancel)
        FtlButton("Import", { onImport(name, setup!!) }, kind = ButtonKind.PRIMARY, enabled = setup != null && ftl.saves.SaveNames.clean(name) != null)
    }) {
        Txt("From ${NativeSave.describe(p.prefix)}:", Ftl.small)
        p.profile?.let { Txt("Profile (${it.fileName}): unlocked ships, achievements and scores", Ftl.small) }
        p.run?.summary?.let { s ->
            Txt("Run (${p.run?.fileName}): " + listOfNotNull(s.ship, s.sector?.let { "sector $it" }, s.scrap?.let { "$it scrap" }, s.hull?.let { "hull $it" }).joinToString(" · "), Ftl.small)
        }
        if (p.profile == null) Txt("Without the profile the save starts with the default unlocks.", Ftl.small)
        if (p.run == null) Txt("Without a run the save starts in the hangar.", Ftl.small)
        Field("Name", name, { name = it })
        Txt("Mods", Ftl.label)
        if (vanilla) Txt("No mods: the game as it ships.", Ftl.small)
        else {
            if (p.setup == "other") Txt("This save prefix is not one of the mod presets. Choose the mods it was played with.", Ftl.small)
            for (preset in presets) ChoiceRow(preset.title, chosenPreset == preset.id) { chosenPreset = preset.id }
            ChoiceRow("Other mod files", chosenPreset == null) { chosenPreset = null }
            if (chosenPreset == null) ModPicker(library, custom) { custom = it }
        }
        setup?.let { s ->
            val missing = missingMods(s, library.map { it.sha256 }.toSet())
            if (missing.isNotEmpty()) Txt("This device lacks " + missing.joinToString(", ") { it.name + (it.version.takeIf { v -> v.isNotEmpty() }?.let { v -> " $v" } ?: "") } +
                ". You can import now and play once the files are added under Mods.", Ftl.small.copy(color = Ftl.error))
        }
    }
}
