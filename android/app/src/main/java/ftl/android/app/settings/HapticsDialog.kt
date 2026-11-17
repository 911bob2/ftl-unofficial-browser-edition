package ftl.android.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ftl.android.app.game.Haptics
import ftl.android.app.game.Haptics.Strength
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.ChoiceRow
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Tick
import ftl.android.app.ui.Txt

/** What Settings > Haptics reads on this device: "Medium · Touch controls only", "Strong · Custom", "Off" or "Not available". */
fun hapticsValue(context: android.content.Context): String = Haptics.load(context).let { Haptics.summary(it.settings, it.kind) }

/**
 * Settings > Haptics: the overall strength (off, or a strength; only on or off where the vibrator has one), then what
 * vibrates: a preset, or the player's own strengths from Advanced. Choosing a strength plays a sample tick.
 */
@Composable
fun HapticsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(Haptics.saved(context)) }
    var advanced by remember { mutableStateOf(false) }
    val kind = remember { Haptics.load(context).kind }
    val change = { next: Haptics.Settings ->
        settings = next
        Haptics.save(context, next)
    }
    if (advanced) {
        HapticsAdvancedDialog(settings, kind, change) { advanced = false }
        return
    }
    FtlDialog("Haptics", onClose, {
        if (kind != Haptics.Kind.NONE) FtlButton("Advanced", { advanced = true })
        FtlButton("Done", onClose, kind = ButtonKind.PRIMARY)
    }) {
        Txt("Vibration for the touch controls and for what happens in the game. From the next time a game starts; saves are not affected.", Ftl.small)
        when (kind) {
            Haptics.Kind.NONE -> Txt("This device cannot vibrate.", Ftl.body)
            Haptics.Kind.ON_OFF -> Txt("This device cannot vary the strength of its vibration, so haptics are on or off.", Ftl.small)
            else -> {}
        }
        if (kind != Haptics.Kind.NONE) Txt("Strength", Ftl.label)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in Haptics.choices(kind)) ChoiceRow(Haptics.label(s, kind), Haptics.label(settings.master, kind) == Haptics.label(s, kind)) {
                change(settings.copy(master = s))
                Haptics.load(context, settings).play(Haptics.SAMPLE, 1)
            }
        }
        if (kind != Haptics.Kind.NONE) {
            val off = settings.master == Strength.OFF
            Txt("What vibrates", Ftl.label, Modifier.padding(top = 4.dp))
            Column(Modifier.selectableGroup().alpha(if (off) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in Haptics.Preset.entries) PresetRow(p.title, p.text, settings.preset == p) { change(settings.with(p)) }
                if (settings.preset == null) PresetRow("Custom", "Your own strengths, set under Advanced.", true) { advanced = true }
            }
            if (off) Txt("Haptics are off, so nothing vibrates.", Ftl.small)
            Txt("Advanced has a strength for every group and every single moment. Android's Touch feedback and Media vibration settings (Sound and vibration) apply too.", Ftl.small)
        }
    }
}

/** One preset: its name, what it covers, and the mark of the chosen one. */
@Composable
private fun PresetRow(title: String, text: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, if (chosen) Ftl.accent else Ftl.line)
        .selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, Ftl.label.copy(color = if (chosen) Ftl.accent else Ftl.ink))
            Txt(text, Ftl.small)
        }
        if (chosen) Tick()
    }
}

/**
 * Settings > Haptics > Advanced: the overall strength, a strength for each group (it sets all of the group's moments) and,
 * in the group that is open, one for each moment. Choosing a strength plays the moment at it; Try plays it again (a
 * moment that is off plays as it would at Medium). Any change away from a preset reads Custom; the buttons at the top
 * go back to one.
 */
@Composable
private fun HapticsAdvancedDialog(settings: Haptics.Settings, kind: Haptics.Kind, change: (Haptics.Settings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Haptics.Group?>(null) }
    val steps = Haptics.choices(kind)
    val feel = { with: Haptics.Settings, m: Haptics.Moment, level: Strength ->
        Haptics.load(context, with).sample(m, if (level == Strength.OFF) Strength.MEDIUM else level)
    }
    FtlDialog("Haptics: advanced", onBack, { FtlButton("Back", onBack, kind = ButtonKind.PRIMARY) }, wide = true) {
        Txt("Now: " + (settings.preset?.title ?: "Custom") + ". Choosing a strength plays it; Try plays it again. From the next time a game starts.", Ftl.small)
        LevelRow("Overall strength", "Scales everything below. Off turns all haptics off.", settings.master, steps, kind, strong = true,
            onTry = { Haptics.load(context, settings).play(Haptics.SAMPLE, 1) }) { s ->
            val next = settings.copy(master = s)
            change(next)
            Haptics.load(context, next).play(Haptics.SAMPLE, 1)
        }
        Txt("Reset to a preset", Ftl.label, Modifier.padding(top = 4.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in Haptics.Preset.entries) FtlButton(p.title, { change(settings.with(p)) }, kind = if (settings.preset == p) ButtonKind.PRIMARY else ButtonKind.NORMAL)
        }
        Column(Modifier.alpha(if (settings.master == Strength.OFF) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (g in Haptics.Group.entries) {
                val moments = Haptics.Moment.entries.filter { it.group == g }
                val shown = open == g
                val level = settings.level(g)
                Column(Modifier.fillMaxWidth().border(1.dp, if (shown) Ftl.headerLine else Ftl.line).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LevelRow(g.title, g.text + " " + moments.size + (if (moments.size == 1) " moment" else " moments") + (if (level == null) ", each at its own strength." else "."),
                        level, steps, kind, strong = true, expanded = shown, onExpand = { open = if (shown) null else g }) { s -> change(settings.with(g, s)) }
                    if (shown) for (m in moments) LevelRow(m.title, m.pattern.text.replaceFirstChar { it.uppercase() }, settings.level(m), steps, kind,
                        onTry = { feel(settings, m, settings.level(m)) }) { s ->
                        val next = settings.with(m, s)
                        change(next)
                        if (s != Strength.OFF) feel(next, m, s)
                    }
                }
            }
        }
    }
}

/**
 * A name with its strength: the steps in a row, the chosen one lit (none lit: a group whose moments differ). Wide enough,
 * it is one line; on a phone held upright the steps go under the name. [onTry]: a Try button; [onExpand]: the row is a
 * group's and a tap on its name opens or closes it.
 */
@Composable
private fun LevelRow(title: String, text: String, level: Strength?, steps: List<Strength>, kind: Haptics.Kind, strong: Boolean = false,
                     expanded: Boolean = false, onExpand: (() -> Unit)? = null, onTry: (() -> Unit)? = null, onPick: (Strength) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val name: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier.then(if (onExpand != null) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Close" else "Open", onClick = onExpand) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onExpand != null) Txt(if (expanded) "▾" else "▸", Ftl.h2.copy(color = Ftl.secondary), Modifier.clearAndSetSemantics {})
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(title, if (strong) Ftl.label else Ftl.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                    Txt(text, Ftl.small)
                }
            }
        }
        val picker: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in steps) {
                    val chosen = level != null && Haptics.label(level, kind) == Haptics.label(s, kind)
                    Box(Modifier.weight(1f).heightIn(min = 40.dp).background(if (chosen) Ftl.accent else Ftl.base200).border(1.dp, if (chosen) Ftl.accent else Ftl.line)
                        .selectable(chosen, role = Role.RadioButton) { onPick(s) }.semantics { contentDescription = title + ": " + Haptics.label(s, kind) }
                        .padding(horizontal = 2.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Txt(Haptics.label(s, kind), Ftl.small.copy(color = if (chosen) Ftl.accentContent else Ftl.ink, textAlign = TextAlign.Center), Modifier.clearAndSetSemantics {})
                    }
                }
            }
        }
        val tryButton: @Composable () -> Unit = { if (onTry != null) FtlButton("Try", onTry, description = "Try $title") }
        if (maxWidth >= 560.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            name(Modifier.weight(1f))
            tryButton()
            picker(Modifier.width(300.dp))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                name(Modifier.weight(1f))
                tryButton()
            }
            picker(Modifier.fillMaxWidth())
        }
    }
}
