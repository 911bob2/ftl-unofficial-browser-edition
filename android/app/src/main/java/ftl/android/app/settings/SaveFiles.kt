package ftl.android.app.settings

import android.content.ContentResolver
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ftl.android.app.FtlApplication
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.ChoiceRow
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Txt
import ftl.android.app.ui.Working
import ftl.saves.Limits
import ftl.saves.PlayedOn
import ftl.saves.SaveFile
import ftl.saves.missingMods
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A save file to and from the device: the reading and writing, with the rules in savecore's [SaveFile]. Blocking. */
object SaveFileIo {
    /** A save that cannot be had just now; the message is for the player. */
    class Unavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** The file's text, read no further than the limit. Refuses what is not UTF-8 text. */
    fun read(resolver: ContentResolver, uri: Uri): String {
        val bytes = ByteArrayOutputStream()
        val stream = try {
            resolver.openInputStream(uri)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: throw SaveFile.Invalid("The file could not be opened.")
        stream.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes.write(buffer, 0, n)
                if (bytes.size() > SaveFile.MAX_FILE_BYTES) throw SaveFile.Invalid(SaveFile.TOO_LARGE)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: CharacterCodingException) {
            throw SaveFile.Invalid(SaveFile.NOT_A_SAVE)
        }
    }

    /** The file for one save: this device's copy, or what the account has when this device lacks it, as on the website. */
    fun exportText(id: String): String {
        val app = FtlApplication.app
        val local = app.store.get(id)
        val cloud = app.sync.state.value.cloud[id]
        val now = Instant.now().toString()
        if (local != null && (local.hasRun || cloud?.hasRun != true) && (local.hasProfile || cloud?.hasProfile != true))
            return SaveFile.write(local.name, local.setup, local.meta, local.run, local.profile, now)
        val api = app.account.api ?: throw Unavailable(NOT_HERE)
        val data = try {
            api.save(id)
        } catch (e: IOException) {
            throw Unavailable(NOT_HERE, e)
        }
        return SaveFile.write(data.cloud.name, data.cloud.setup, data.cloud.meta, data.run, data.profile, now)
    }

    private const val NOT_HERE = "This save has not reached this device yet. Connect to the internet and try again."

    fun write(resolver: ContentResolver, uri: Uri, text: String) {
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("The file could not be written.")
        out.use { it.write(text.encodeToByteArray()) }
    }

    /** Adds the file as a new save, never over an existing one. Sync creates it in the account like any new save. */
    fun addToStore(parsed: SaveFile.Parsed): String {
        val app = FtlApplication.app
        val taken = taken()
        val name = SaveFile.uniqueName(parsed.name, taken)
        val at = Instant.now().toString()
        val save = SaveFile.newSave(parsed, UUID.randomUUID().toString(), name, PlayedOn("android", app.identity.name, app.identity.deviceId, at))
        app.store.put(save)
        app.sync.requestSync()
        return name
    }

    fun taken(): List<String> {
        val app = FtlApplication.app
        return (app.store.list().map { it.name } + app.sync.state.value.cloud.values.map { it.name }).distinct()
    }

    /** Every save there is to export, by id and name. */
    fun exportable(): List<Pair<String, String>> {
        val app = FtlApplication.app
        val locals = app.store.list().map { it.id to it.name }
        return locals + app.sync.state.value.cloud.values.filter { c -> locals.none { it.first == c.id } }.map { it.id to it.name }
    }

    fun count(): Int {
        val app = FtlApplication.app
        return (app.store.list().map { it.id } + app.sync.state.value.cloud.keys).distinct().size
    }
}

/** Starts a save's export or an import and shows what they ask or answer. One on each screen that offers them. */
class SaveFileActions(val import: () -> Unit, val export: (id: String, name: String) -> Unit, val dialogs: @Composable () -> Unit)

@Composable
fun rememberSaveFiles(onChanged: () -> Unit): SaveFileActions {
    val app = FtlApplication.app
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<SaveFile.Parsed?>(null) }
    var problem by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) {
            busy = "Writing the file…"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { SaveFileIo.write(context.contentResolver, uri, SaveFileIo.exportText(id)) }
                    Toast.makeText(context, "Save exported.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    android.util.Log.w("FTL", "Save export failed", e)
                    problem = "Not exported" to ((e as? SaveFileIo.Unavailable)?.message ?: "The file could not be written there. Try saving it somewhere else.")
                } finally {
                    busy = null
                }
            }
        }
    }
    /** Nothing is overwritten and the save can be deleted, so this needs no yes unless something about it would surprise. */
    suspend fun add(parsed: SaveFile.Parsed) {
        busy = "Importing…"
        try {
            val name = withContext(Dispatchers.IO) { SaveFileIo.addToStore(parsed) }
            android.util.Log.i("FTL", "Save imported as a new save")
            Toast.makeText(context, "Imported as $name.", Toast.LENGTH_LONG).show()
            onChanged()
        } catch (e: IOException) {
            android.util.Log.w("FTL", "Save import failed", e)
            problem = "Not imported" to "The save could not be stored. Is this device's storage full?"
        } finally {
            busy = null
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = "Checking the file…"
            scope.launch {
                try {
                    val parsed = withContext(Dispatchers.IO) { SaveFile.parse(SaveFileIo.read(context.contentResolver, uri), app.mods.catalogue) }
                    when {
                        SaveFileIo.count() >= Limits.SAVES -> problem = "Not imported" to ftl.android.app.SaveWords.words("savesFull", "count" to Limits.SAVES.toString())
                        missingMods(parsed.setup, app.mods.list().map { it.sha256 }.toSet()).isNotEmpty() -> importing = parsed
                        else -> add(parsed)
                    }
                } catch (e: SaveFile.Invalid) {
                    android.util.Log.i("FTL", "Save import refused: ${e.message}")
                    problem = "Not imported" to e.message!!
                } catch (e: IOException) {
                    android.util.Log.w("FTL", "Save import failed", e)
                    problem = "Not imported" to "The file could not be read."
                } catch (e: OutOfMemoryError) {
                    problem = "Not imported" to "This file is too large for this device's memory."
                } finally {
                    if (busy == "Checking the file…") busy = null
                }
            }
        }
    }
    return SaveFileActions(
        import = { open.launch(arrayOf("*/*")) },
        export = { id, name ->
            exporting = id
            create.launch(SaveFile.fileName(name))
        },
        dialogs = {
            busy?.let { BusyDialog(it) }
            problem?.let { (title, text) ->
                FtlDialog(title, { problem = null }, { FtlButton("OK", { problem = null }, kind = ButtonKind.PRIMARY) }) { Txt(text) }
            }
            importing?.let { parsed -> MissingModsDialog(parsed, { importing = null }) {
                importing = null
                scope.launch { add(parsed) }
            } }
        },
    )
}

/** Shown only if the work is still going a moment later, so that a quick one does not flash a dialog. */
@Composable
private fun BusyDialog(text: String) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        show = true
    }
    if (show) FtlDialog(text, {}, {}) { Working() }
}

@Composable
private fun MissingModsDialog(parsed: SaveFile.Parsed, onCancel: () -> Unit, onImport: () -> Unit) {
    val app = FtlApplication.app
    val name = remember { SaveFile.uniqueName(parsed.name, SaveFileIo.taken()) }
    val missing = remember { missingMods(parsed.setup, app.mods.list().map { it.sha256 }.toSet()) }
    FtlDialog("This save needs mods you do not have", onCancel, {
        FtlButton("Cancel", onCancel)
        FtlButton("Import anyway", onImport, kind = ButtonKind.PRIMARY)
    }) {
        Txt(name, Ftl.h2)
        for (m in missing) Txt(m.name + (m.version.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""), Ftl.small.copy(color = Ftl.error))
        Txt("You can import it now and play it once these files are added under Mods.", Ftl.small)
    }
}

/** Settings > Export a save, when there is more than one: which one. */
@Composable
fun ExportPicker(saves: List<Pair<String, String>>, onCancel: () -> Unit, onPick: (String, String) -> Unit) {
    FtlDialog("Export which save?", onCancel, { FtlButton("Cancel", onCancel) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, name) in saves) ChoiceRow(name, false) { onPick(id, name) }
        }
    }
}
