package ftl.android.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ftl.android.app.game.Haptics
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.ChoiceRow
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Txt

/** What Settings > Haptics reads on this device: "Medium", "On", "Off" or "Not available", and any action switched off. */
fun hapticsValue(context: android.content.Context): String = Haptics.load(context).let { Haptics.summary(it.strength, it.kind, it.actions) }

/**
 * Settings > Haptics: off, or a strength (only on or off where the vibrator has one), then a switch per action. Choosing a
 * strength plays a sample tick. Off turns every action off; an action switched off plays nothing at any strength.
 */
@Composable
fun HapticsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(Haptics.saved(context)) }
    var actions by remember { mutableStateOf(Haptics.savedActions(context)) }
    val kind = remember { Haptics.load(context).kind }
    FtlDialog("Haptics", onClose, { FtlButton("Done", onClose, kind = ButtonKind.PRIMARY) }) {
        Txt("Ticks on the touch controls, for each action switched on below. From the next time a game starts; saves are not affected.", Ftl.small)
        when (kind) {
            Haptics.Kind.NONE -> Txt("This device cannot vibrate.", Ftl.body)
            Haptics.Kind.ON_OFF -> Txt("This device cannot vary the strength of its vibration, so haptics are on or off.", Ftl.small)
            else -> {}
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in Haptics.choices(kind)) ChoiceRow(Haptics.label(s, kind), Haptics.label(chosen, kind) == Haptics.label(s, kind)) {
                chosen = s
                Haptics.save(context, s)
                Haptics.load(context, s).play(Haptics.SAMPLE, 1)
            }
        }
        if (kind != Haptics.Kind.NONE) {
            val off = chosen == Haptics.Strength.OFF
            Txt("Actions", Ftl.label, Modifier.padding(top = 4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in Haptics.Action.entries) SwitchRow(a.title, a.text, a in actions, active = !off) { on ->
                    actions = if (on) actions + a else actions - a
                    Haptics.saveAction(context, a, on)
                }
            }
            if (off) Txt("Haptics are off, so none of these plays.", Ftl.small)
            Txt("Android's Touch feedback setting (Sound and vibration) applies too.", Ftl.small)
        }
    }
}
