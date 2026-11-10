package ftl.android.app.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import ftl.android.app.BuildConfig
import ftl.android.app.FtlApplication
import ftl.android.app.ScreenDialog
import ftl.android.app.game.Magnifier
import ftl.android.app.game.ScreenFit
import ftl.android.app.icons.AppIcon
import ftl.android.app.icons.AppIcons
import ftl.android.app.log.DebugLog
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.ChoiceRow
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.FtlSwitch
import ftl.android.app.ui.Panel
import ftl.android.app.ui.Tick
import ftl.android.app.ui.Txt
import ftl.android.app.ui.clock
import ftl.android.app.update.UpdateCheck
import ftl.android.app.update.Updates
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A row that opens something: what it is on the left, what it is set to below, an arrow on the right. */
@Composable
private fun OptionRow(title: String, value: String, onClick: () -> Unit, picture: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().border(1.dp, Ftl.line).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, Ftl.label)
            Txt(value, Ftl.small)
        }
        picture?.invoke()
        Txt("›", Ftl.h1.copy(color = Ftl.secondary), Modifier.clearAndSetSemantics {})
    }
}

/**
 * A row that turns something on or off, the whole row being the target; dimmed while it has no effect ([active]); [framed]:
 * in a frame like the option rows beside it.
 */
@Composable
internal fun SwitchRow(title: String, text: String, on: Boolean, active: Boolean = true, framed: Boolean = false, onChange: (Boolean) -> Unit) {
    val frame = if (framed) Modifier.border(1.dp, Ftl.line) else Modifier
    Row(Modifier.fillMaxWidth().then(frame).toggleable(on, role = Role.Switch, onValueChange = onChange)
        .then(if (framed) Modifier.padding(horizontal = 12.dp, vertical = 10.dp) else Modifier).alpha(if (active) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, Ftl.label)
            Txt(text, Ftl.small)
        }
        FtlSwitch(on)
    }
}

/**
 * The launcher's settings, over the screen they were opened from: a page that fits in one look, each choice a row that opens
 * its own dialog. They belong to this device, not to a save. Saves are here only as a way in: each save's own ⋯ menu exports
 * it too, and New save imports too.
 *
 * withSaves: false until the device has saves to speak of (no account or game files chosen yet); the log stays reachable,
 * since those steps can fail too.
 */
@Composable
fun SettingsPage(withSaves: Boolean, onChanged: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val page = Modifier.widthIn(max = 820.dp).fillMaxWidth()
    // The empty pointer input takes every touch, so that none reaches the screen lying underneath.
    Column(Modifier.fillMaxSize().pointerInput(Unit) {}) {
        // Outside the scrolling part, so that the way back is always on screen.
        Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.TopCenter) {
            Row(page, verticalAlignment = Alignment.CenterVertically) {
                Txt("Settings", Ftl.h1, Modifier.weight(1f))
                FtlButton("Back", onClose, kind = ButtonKind.GHOST)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 18.dp), contentAlignment = Alignment.TopCenter) {
            val twoColumns = maxWidth >= 680.dp
            val version = @Composable { Txt("FTL Unofficial ${BuildConfig.VERSION_NAME}", Ftl.small, Modifier.padding(start = 4.dp)) }
            if (twoColumns) Row(page, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    GeneralSection()
                    if (withSaves) SavesSection(onChanged, onClose)
                    version()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    UpdatesSection()
                    ModScriptsSection()
                    LogSection()
                }
            } else Column(page, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GeneralSection()
                if (withSaves) SavesSection(onChanged, onClose)
                UpdatesSection()
                ModScriptsSection()
                LogSection()
                version()
            }
        }
    }
}

@Composable
private fun GeneralSection() {
    val context = LocalContext.current
    val app = FtlApplication.app
    val icons = remember { IconState(AppIcons(context)) }
    // Not the chooser's: closing it right after a tap must not lose the answer.
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var fit by remember { mutableStateOf(ScreenFit.load(context)) }
    var language by remember { mutableStateOf(GameLanguage.read(app.store.settings())) }
    var haptics by remember { mutableStateOf(hapticsValue(context)) }
    var magnifier by remember { mutableStateOf(Magnifier.enabled(context)) }
    Panel(tab = "General") {
        val mode = when (fit.mode) {
            ScreenFit.Mode.DEFAULT -> "Default"
            ScreenFit.Mode.STRETCH -> "Stretch"
            ScreenFit.Mode.CUSTOM -> "Custom ${fit.ratio?.label ?: ""}".trim()
        }
        OptionRow("Screen", mode + if (ScreenFit.screenSize(context).second >= ScreenFit.SHARP_FROM) " · " + (if (fit.sharp) "Sharp" else "Standard") else "", { dialog = "screen" })
        OptionRow("Haptics", haptics, { dialog = "haptics" })
        SwitchRow("Magnifier", "While a finger is held on the game, shows what is under it, larger. Slide onto a small button, then lift. From the next time a game starts.", magnifier, framed = true) { on ->
            Magnifier.setEnabled(context, on)
            magnifier = on
        }
        OptionRow("Language", GameLanguage.label(language), { dialog = "language" })
        if (icons.supported) OptionRow("App icon", if (icons.random) "Random, now ${icons.current.title}" else icons.current.title, { dialog = "icon" }) { IconImage(icons.current, 36) }
    }
    when (dialog) {
        "screen" -> ScreenDialog { dialog = null; fit = ScreenFit.load(context) }
        "haptics" -> HapticsDialog { dialog = null; haptics = hapticsValue(context) }
        "language" -> FtlDialog("Language", { dialog = null }, { FtlButton("Cancel", { dialog = null }) }) {
            Txt("The game's language, from the next time a game starts. This launcher stays in English.", Ftl.small)
            val shown = GameLanguage.CHOICES + listOfNotNull(language?.takeIf { code -> code != "en" && GameLanguage.CHOICES.none { it.first == code } }?.let { it to it })
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((code, name) in shown) ChoiceRow(name, language == code || code == "" && language == "en") {
                    app.store.putSettings(GameLanguage.write(app.store.settings(), code))
                    language = code
                    dialog = null
                }
            }
        }
        "icon" -> if (icons.supported) IconDialog(icons, scope) { dialog = null }
    }
    IconQuestions(icons)
}

/** The icon, the Random choice and what a change may have to ask. Changes go one at a time, off the main thread; the screen moves at once. */
private class IconState(val icons: AppIcons) {
    var supported by mutableStateOf(icons.supported)
    var current by mutableStateOf(icons.current)
    var random by mutableStateOf(icons.random)
    var changedTo by mutableStateOf<AppIcon?>(null)
    var warning by mutableStateOf<(() -> Unit)?>(null)
    var refused by mutableStateOf(false)
}

@Composable
private fun IconDialog(state: IconState, scope: CoroutineScope, onClose: () -> Unit) {
    val icons = state.icons

    fun apply(icon: AppIcon?, wantRandom: Boolean) {
        val act = {
            state.random = wantRandom
            state.changedTo = icon
            if (icon != null) state.current = icon
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    synchronized(icons) {
                        icons.random = wantRandom
                        icon == null || icons.set(icon)
                    }
                }
                if (!ok) {
                    state.refused = true
                    state.current = icons.current
                    state.random = false
                    state.supported = icons.supported
                }
            }
        }
        // Where a home screen app has not been seen to keep its icon through a change, the player says yes first, once.
        if (icons.homeTested || icons.warned) act() else state.warning = { icons.warned = true; act() }
    }

    // One row where the screen is wide enough, so that landscape needs no scrolling; three rows of three otherwise.
    FtlDialog("App icon", onClose, { FtlButton("Done", onClose, kind = ButtonKind.PRIMARY) }, wide = true) {
        SwitchRow("Random",
            if (state.random) "A new icon each time you leave the app or restart the device, but not while a game is open."
            else "Off: the icon stays as you choose below.",
            state.random) { apply(null, it) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A tile needs room for its longest label, whole, at the player's font size.
            val tile = 66.dp * LocalDensity.current.fontScale
            val columns = listOf(icons.all.size, 3).firstOrNull { (maxWidth - 8.dp * (it - 1)) / it >= tile } ?: 2
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in icons.all.chunked(columns)) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (icon in row) IconTile(icon.title, state.current == icon, Modifier.weight(1f).fillMaxHeight(), { apply(icon, false) }) { IconImage(icon, 56) }
                }
            }
        }
        val changed = state.changedTo
        Txt(when {
            state.random -> "Showing now: ${state.current.title}. Choose an icon to keep it and turn Random off."
            changed != null -> "${changed.title} is your icon. Your home screen shows it within a few seconds."
            else -> "Tap an icon to use it. Your home screen shows it within a few seconds, and the app stays open."
        }, Ftl.small)
    }
}

/** Kept out of the chooser so that a refusal, which takes the chooser away, can still say so. */
@Composable
private fun IconQuestions(state: IconState) {
    state.warning?.let { go ->
        FtlDialog("Your home screen may lose the icon", { state.warning = null }, {
            FtlButton("Cancel", { state.warning = null })
            FtlButton("Continue", { state.warning = null; go() }, kind = ButtonKind.PRIMARY)
        }) {
            Txt("Some home screen apps remove the icon you placed when an app changes its icon. If yours does, add it again from the app list.")
            Txt("Your saves are not affected. This is asked only once.", Ftl.small)
        }
    }
    if (state.refused) FtlDialog("Icon not changed", { state.refused = false }, { FtlButton("OK", { state.refused = false }, kind = ButtonKind.PRIMARY) }) {
        Txt("This device does not let apps change their icon, so it stays on the standard one.")
    }
}

@Composable
private fun IconTile(label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit, image: @Composable () -> Unit) {
    Box(modifier.border(if (chosen) 2.dp else 1.dp, if (chosen) Ftl.accent else Ftl.line).selectable(chosen, role = Role.RadioButton, onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            image()
            Txt(label, Ftl.small.copy(fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, color = if (chosen) Ftl.accent else Ftl.ink), Modifier.fillMaxWidth())
        }
        if (chosen) Tick(Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun IconImage(icon: AppIcon, sizeDp: Int) {
    val resources = LocalResources.current
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(icon) {
        bitmap = withContext(Dispatchers.Default) { resources.getDrawable(icon.image, null)?.toBitmap(px, px)?.asImageBitmap() }
    }
    val shown = bitmap
    if (shown != null) Image(shown, null, Modifier.size(sizeDp.dp)) else Spacer(Modifier.size(sizeDp.dp))
}

@Composable
private fun SavesSection(onChanged: () -> Unit, onClose: () -> Unit) {
    // An imported save is what the player came for: back to the list, where it now is.
    val files = rememberSaveFiles { onChanged(); onClose() }
    var picking by remember { mutableStateOf(false) }
    val saves = remember { SaveFileIo.exportable() }
    Panel(tab = "Saves") {
        Txt("Keep a save as a file, or move it to another device or the website. Mods are not in the file: the other device needs the same mod files.", Ftl.small)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FtlButton("Import a save", files.import)
            FtlButton("Export a save", { saves.singleOrNull()?.let { files.export(it.first, it.second) } ?: run { picking = true } }, enabled = saves.isNotEmpty())
        }
    }
    if (picking) ExportPicker(saves, { picking = false }) { id, name -> picking = false; files.export(id, name) }
    files.dialogs()
}

/** Check for updates (now, whatever the switch) and the automatic check's switch (docs/ANDROID.md, "Updates"). */
@Composable
private fun UpdatesSection() {
    val context = LocalContext.current
    val updates = FtlApplication.app.updates
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(updates.auto) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheck.Result?>(null) }
    Panel(tab = "Updates") {
        val mine = updates.current
        OptionRow("Check for updates", when (val r = result) {
            null -> if (checking) "Version $mine · asking GitHub…" else "Version $mine · tap to check now"
            UpdateCheck.Result.UpToDate -> "Version $mine · You have the latest version"
            is UpdateCheck.Result.Available -> "Version ${r.release.version} is available (you have $mine)"
            is UpdateCheck.Result.Failed -> "Version $mine · ${r.reason}"
        }, {
            if (!checking) {
                checking = true
                result = null
                scope.launch {
                    result = withContext(Dispatchers.IO) { updates.checkNow() }
                    checking = false
                }
            }
        })
        (result as? UpdateCheck.Result.Available)?.let { a -> FtlButton("Download", { Updates.open(context, a.release) }, kind = ButtonKind.PRIMARY) }
        SwitchRow("Check for updates automatically",
            if (auto) "On. Once a day the launcher asks GitHub whether a newer version is out. Only this app's version is sent, nothing about you or this device."
            else "Off: no request is made. Check for updates asks GitHub when you tap it.",
            auto, framed = true) { on -> updates.auto = on; auto = on }
    }
}

/**
 * "Disable Lua sandboxing (dangerous)" (sweep M1b, docs/HS_LUA.md): off by default, per device. On, mods the player
 * lets run (asked again with the dangers) get real files in this app's storage and /system/bin/sh. Turning it on asks
 * twice; turning it off asks nothing.
 */
@Composable
private fun ModScriptsSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(ftl.android.app.mods.LuaSandbox.unsandboxed(context)) }
    var confirm by remember { mutableStateOf(false) }
    Panel(tab = "Mod scripts") {
        SwitchRow("Disable Lua sandboxing (dangerous)",
            if (on) "On. Mods you allow again when a game starts can use this app's files and run programs."
            else "Off: mod scripts keep their files in a private folder and cannot run programs. Only for mods you trust.",
            on, framed = true) { want -> if (want) confirm = true else { ftl.android.app.mods.LuaSandbox.setUnsandboxed(context, false); on = false } }
    }
    if (confirm) FtlDialog("Disable Lua sandboxing?", { confirm = false }, {
        FtlButton("Cancel", { confirm = false }, kind = ButtonKind.GHOST)
        FtlButton("Disable sandboxing", { ftl.android.app.mods.LuaSandbox.setUnsandboxed(context, true); on = true; confirm = false }, kind = ButtonKind.DANGER)
    }) {
        Txt("Only for mods you trust. A mod could delete your saves, read your account sign-in and send it anywhere.")
        Txt("Scripts of the mods you allow then read and write this app's own files, not a private folder, and run programs on this device as this app (/system/bin/sh), which can reach the network. Each mod that uses files or programs is asked about again when a game starts. Mods you do not allow stay sandboxed.", Ftl.small)
        Txt("You can turn it off at any time; that needs no confirmation.", Ftl.small)
    }
}

@Composable
private fun LogSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(DebugLog.enabled) }
    var tick by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val bytes = remember(tick, enabled) { DebugLog.bytes }
    val lastProblem = remember(tick) { DebugLog.lastProblem }
    // The size goes on growing while the log is on.
    LaunchedEffect(enabled) {
        while (enabled) {
            delay(2000)
            tick++
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            exporting = true
            problem = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
                        out.use { DebugLog.export(context, it) }
                    }
                    Toast.makeText(context, "Log saved. Attach that file when you report the problem.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    android.util.Log.w("FTL", "Log export failed", e)
                    problem = "The log could not be written there. Try saving it somewhere else."
                } finally {
                    exporting = false
                }
            }
        }
    }
    Panel(tab = "Help") {
        SwitchRow("Debug log",
            if (enabled) "On. Repeat the problem, then export the log. It keeps the latest 2 MB."
            else "Keeps a detailed record to help track down a problem. Leave it off otherwise.",
            enabled) { enabled = it; DebugLog.enabled = it; tick++ }
        Txt("It holds what the app and game did, and this device's model and Android version, with passwords and email addresses taken out. It stays here until you export it. A short crash report is always kept.", Ftl.small)
        Txt(listOfNotNull(if (bytes == 0L) "Nothing recorded yet" else "Recorded: " + if (bytes < 1024) "$bytes bytes" else "${bytes / 1024} KB", lastProblem?.let { "last problem ${clock(it)}" }).joinToString(" · "),
            Ftl.small.copy(color = if (lastProblem != null) Ftl.accent else Ftl.muted))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FtlButton(if (exporting) "Exporting…" else "Export log", { create.launch("ftl-debug-log-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".txt") }, enabled = !exporting && (bytes > 0 || enabled))
            FtlButton("Clear", { DebugLog.clear(); tick++ }, kind = ButtonKind.GHOST, enabled = bytes > 0)
        }
        problem?.let { Txt(it, Ftl.small.copy(color = Ftl.error)) }
        // docs/CONSOLE.md: the debug console and its floating >_ button, from the next game started
        var console by remember { mutableStateOf(ftl.android.app.game.ConsoleButton.enabled(context)) }
        SwitchRow("Enable debug console",
            if (console) "On. A >_ button over the game opens the console; drag it where it suits you. Its commands can change your profile for good, and that syncs."
            else "For testing: a console for cheats and test commands during a run. Takes effect when a game starts.",
            console) { console = it; ftl.android.app.game.ConsoleButton.setEnabled(context, it) }
    }
}
