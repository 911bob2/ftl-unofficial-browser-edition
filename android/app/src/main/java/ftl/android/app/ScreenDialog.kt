package ftl.android.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ftl.android.app.game.ScreenFit
import ftl.android.app.ui.Badge
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Tick
import ftl.android.app.ui.Txt

@Composable
private fun FitOption(title: String, text: String, chosen: Boolean, badge: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, if (chosen) Ftl.accent else Ftl.line).selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Ftl.label.copy(color = if (chosen) Ftl.accent else Ftl.ink))
            badge?.let { Badge(it, Ftl.accent) }
            Spacer(Modifier.weight(1f))
            if (chosen) Tick()
        }
        Txt(text, Ftl.small)
    }
}

/** Settings > Screen: how the game fills this device's screen (ScreenFit, docs/ANDROID.md). */
@Composable
fun ScreenDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var fit by remember { mutableStateOf(ScreenFit.load(context)) }
    val screen = remember { ScreenFit.screenSize(context) }
    val ratios = remember { ScreenFit.customRatios(screen.first, screen.second) }
    val original = ScreenFit.original(screen.first, screen.second)
    val shapeMatches = kotlin.math.abs(screen.first / screen.second.toDouble() / original.value - 1) < 0.01
    fun choose(next: ScreenFit) {
        fit = next
        next.save(context)
    }
    FtlDialog("Screen", onClose, { FtlButton("Done", onClose, kind = ButtonKind.PRIMARY) }) {
        Txt("How the game fills this screen, from the next time a game starts. Saves and mods are not affected.", Ftl.small)
        FitOption("Default", "The game's own ${original.label} picture" + if (shapeMatches) ", which is this screen's shape." else ", with borders where this screen differs.",
            fit.mode == ScreenFit.Mode.DEFAULT) { choose(fit.copy(mode = ScreenFit.Mode.DEFAULT)) }
        FitOption("Stretch", if (shapeMatches) "Fills the screen, distorted. On this screen it looks the same as Default." else "Fills the screen, distorted.",
            fit.mode == ScreenFit.Mode.STRETCH) { choose(fit.copy(mode = ScreenFit.Mode.STRETCH)) }
        FitOption("Custom", "Another shape: the scenery covers it and the controls stay in the middle. Mod screens made for fixed positions can look wrong.",
            fit.mode == ScreenFit.Mode.CUSTOM, badge = "Experimental") {
            choose(fit.copy(mode = ScreenFit.Mode.CUSTOM, ratio = fit.ratio?.takeIf { it in ratios } ?: ratios.last()))
        }
        if (fit.mode == ScreenFit.Mode.CUSTOM) {
            val shown = (ratios + listOfNotNull(fit.ratio)).distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in shown) Box(Modifier.border(1.dp, if (r == fit.ratio) Ftl.accent else Ftl.line)
                    .clickable { choose(fit.copy(mode = ScreenFit.Mode.CUSTOM, ratio = r)) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Txt(r.label, Ftl.label.copy(color = if (r == fit.ratio) Ftl.accent else Ftl.ink))
                }
            }
        }
        if (screen.second >= ScreenFit.SHARP_FROM) {
            Txt("Resolution", Ftl.label)
            FitOption("Sharp", "Crisper text and art, more work for the device.", fit.sharp) { choose(fit.copy(sharp = true)) }
            FitOption("Standard", "Softer, lighter. Try it if the game runs slowly.", !fit.sharp) { choose(fit.copy(sharp = false)) }
        }
    }
}
