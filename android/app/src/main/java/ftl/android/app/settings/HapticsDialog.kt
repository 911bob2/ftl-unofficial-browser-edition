package ftl.android.app.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import ftl.android.app.game.Haptics.Pattern
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
                if (settings.preset == null) PresetRow("Custom", "Your own strengths and kinds of vibration, set under Advanced.", true) { advanced = true }
            }
            if (off) Txt("Haptics are off, so nothing vibrates.", Ftl.small)
            Txt("Advanced has a strength and a kind of vibration for every single moment. Android's Touch feedback and Media vibration settings (Sound and vibration) apply too.", Ftl.small)
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
 * Settings > Haptics > Advanced: the overall strength, then each group with a strength for all its moments and, in the
 * group that is open, a kind of vibration for all of them (both just set the rows) and a strength and a kind for each
 * moment. Choosing a strength or a kind plays the moment so; Try plays it again (a moment that is off plays as it would
 * at Medium). A moment that does not play its own kind has its kind lit and Reset beside it. Any change away from a
 * preset reads Custom; the buttons at the top go back to one, strengths and kinds.
 */
@Composable
private fun HapticsAdvancedDialog(settings: Haptics.Settings, kind: Haptics.Kind, change: (Haptics.Settings) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Haptics.Group?>(null) }
    // The moment or the group whose kind is being picked.
    var picking by remember { mutableStateOf<Any?>(null) }
    val steps = Haptics.choices(kind)
    val feel = { with: Haptics.Settings, m: Haptics.Moment, level: Strength ->
        Haptics.load(context, with).sample(m, with.kind(m), if (level == Strength.OFF) Strength.MEDIUM else level)
    }
    FtlDialog("Haptics: advanced", onBack, { FtlButton("Back", onBack, kind = ButtonKind.PRIMARY) }, wide = true) {
        Txt("Now: " + (settings.preset?.title ?: "Custom") + ". Choosing a strength or a kind plays it; Try plays it again. From the next time a game starts.", Ftl.small)
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
                val other = settings.changed(g)
                Column(Modifier.fillMaxWidth().border(1.dp, if (shown) Ftl.headerLine else Ftl.line).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The kind for all is with the rows it sets: in the open group. A closed one says when kinds were picked.
                    LevelRow(g.title, g.text + " " + moments.size + (if (moments.size == 1) " moment" else " moments") + (if (level == null) ", each at its own strength" else "") +
                        (if (other > 0) ", $other with a kind you picked." else "."),
                        level, steps, kind, strong = true, expanded = shown, onExpand = { open = if (shown) null else g },
                        kindLabel = "Kind for all: " + (settings.kind(g)?.title ?: if (other == 0) "each its own" else "$other picked"), kindChanged = other > 0,
                        onKind = if (shown) ({ picking = g }) else null) { s -> change(settings.with(g, s)) }
                    if (shown) for (m in moments) LevelRow(m.title, null, settings.level(m), steps, kind,
                        kindLabel = settings.kind(m).title, kindChanged = settings.changed(m), onKind = { picking = m },
                        onReset = if (settings.changed(m)) ({ change(settings.with(m, null)) }) else null,
                        onTry = { feel(settings, m, settings.level(m)) }) { s ->
                        val next = settings.with(m, s)
                        change(next)
                        if (s != Strength.OFF) feel(next, m, s)
                    }
                }
            }
        }
    }
    when (val what = picking) {
        is Haptics.Moment -> KindDialog(what.title + ": kind", "Its own kind is " + what.pattern.title + ". Choosing one plays it at this moment's strength.", settings.kind(what), what.pattern, null,
            context, settings, if (settings.level(what) == Strength.OFF) Strength.MEDIUM else settings.level(what), what, { picking = null }) { k ->
            val next = settings.with(what, k)
            change(next)
            Haptics.load(context, next).sample(what, next.kind(what), if (next.level(what) == Strength.OFF) Strength.MEDIUM else next.level(what))
        }
        is Haptics.Group -> KindDialog(what.title + ": kind for all", "Sets every moment of this group, as if you chose it in each row.", settings.kind(what), null,
            if (settings.changed(what) == 0) "Each moment has its own kind now." else "Each its own",
            context, settings, Strength.MEDIUM, null, { picking = null }) { k ->
            val next = settings.with(what, k)
            change(next)
            if (k != null) Haptics.load(context, next).sample(null, k, Strength.MEDIUM)
        }
    }
}

/**
 * The list of kinds of vibration, for one moment or for all of a group: each with its name, what it feels like and Try;
 * a tap chooses it and plays it. [own]: the moment's own kind, marked in the list. [ownAll]: a group's first line, which
 * puts every moment back to its own kind (a sentence instead when they all are). On a motor that is only on or off a
 * kind that needs a finer one says what it plays as here.
 */
@Composable
private fun KindDialog(title: String, text: String, chosen: Pattern?, own: Pattern?, ownAll: String?, context: android.content.Context, settings: Haptics.Settings,
                       level: Strength, moment: Haptics.Moment?, onClose: () -> Unit, onPick: (Pattern?) -> Unit) {
    val device = remember { Haptics.load(context, settings) }
    FtlDialog(title, onClose, { FtlButton("Done", onClose, kind = ButtonKind.PRIMARY) }, wide = true) {
        Txt(text, Ftl.small)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A phone held upright has one kind per line; a wider screen is not as high, so two or three side by side.
            val columns = if (maxWidth >= 900.dp) 3 else if (maxWidth >= 520.dp) 2 else 1
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ownAll != null) {
                    if (ownAll.endsWith(".")) Txt(ownAll, Ftl.small)
                    else KindRow(ownAll, "Puts every moment of the group back to its own kind.", false, null, Modifier.fillMaxWidth()) { onPick(null) }
                }
                for (line in Pattern.entries.chunked(columns)) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in line) {
                        val here = device.playsAs(k)
                        KindRow(k.title + if (k == own) " (its own)" else "", k.text.replaceFirstChar { it.uppercase() } + if (here != null) ". Needs a finer motor: plays as " + here.title + " on this device" else "",
                            chosen == k, { Haptics.load(context, settings).sample(moment, k, level) }, Modifier.weight(1f).fillMaxHeight()) { onPick(k) }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One kind in the list: the whole line chooses it, Try only plays it. */
@Composable
private fun KindRow(title: String, text: String, chosen: Boolean, onTry: (() -> Unit)?, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.border(1.dp, if (chosen) Ftl.accent else Ftl.line)
        .selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, Ftl.label.copy(color = if (chosen) Ftl.accent else Ftl.ink))
            Txt(text, Ftl.small)
        }
        if (chosen) Tick()
        if (onTry != null) FtlButton("Try", onTry, description = "Try $title")
    }
}

/**
 * A name with its strength: the steps in a row, the chosen one lit (none lit: a group whose moments differ). Wide enough,
 * it is one line; on a phone held upright the steps go under the name. [onTry]: a Try button; [onExpand]: the row is a
 * group's and a tap on its name opens or closes it. [onKind]: a button with [kindLabel] that opens the list of kinds
 * (lit when [kindChanged]: not the moment's own); [onReset]: a button that puts the moment's own kind back.
 */
@Composable
private fun LevelRow(title: String, text: String?, level: Strength?, steps: List<Strength>, kind: Haptics.Kind, strong: Boolean = false,
                     expanded: Boolean = false, onExpand: (() -> Unit)? = null, kindLabel: String? = null, kindChanged: Boolean = false, onKind: (() -> Unit)? = null,
                     onReset: (() -> Unit)? = null, onTry: (() -> Unit)? = null, onPick: (Strength) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val name: @Composable (Modifier) -> Unit = { modifier ->
            Row(modifier.then(if (onExpand != null) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Close" else "Open", onClick = onExpand) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onExpand != null) Txt(if (expanded) "▾" else "▸", Ftl.h2.copy(color = Ftl.secondary), Modifier.clearAndSetSemantics {})
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(title, if (strong) Ftl.label else Ftl.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                    if (text != null) Txt(text, Ftl.small)
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
        // The kind's name as a button that opens the list; lit when it is not the moment's own.
        val kindButton: @Composable (Modifier) -> Unit = { modifier ->
            if (onKind != null && kindLabel != null) Row(modifier.heightIn(min = 40.dp).border(1.dp, if (kindChanged) Ftl.accent else Ftl.line).background(Ftl.base200)
                .clickable(role = Role.Button, onClick = onKind).semantics { contentDescription = "$title, kind: $kindLabel" + if (kindChanged) ", changed" else "" }
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(kindLabel, Ftl.small.copy(color = if (kindChanged) Ftl.accent else Ftl.ink), Modifier.weight(1f).clearAndSetSemantics {})
                Txt("▾", Ftl.small.copy(color = Ftl.secondary), Modifier.clearAndSetSemantics {})
            }
        }
        val resetButton: @Composable () -> Unit = { if (onReset != null) FtlButton("Reset", onReset, description = "$title: back to its own kind") }
        val tryButton: @Composable () -> Unit = { if (onTry != null) FtlButton("Try", onTry, description = "Try $title") }
        if (maxWidth >= 660.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            name(Modifier.weight(1f))
            resetButton()
            kindButton(Modifier.width(if (onExpand != null) 210.dp else 160.dp))
            tryButton()
            picker(Modifier.width(280.dp))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // A moment: its name, then its kind with Reset and Try, then its strength. Anything else: Try beside the name.
            val moment = onKind != null && onExpand == null
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                name(Modifier.weight(1f))
                if (!moment) tryButton()
            }
            if (onKind != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kindButton(Modifier.weight(1f))
                resetButton()
                if (moment) tryButton()
            }
            picker(Modifier.fillMaxWidth())
        }
    }
}
