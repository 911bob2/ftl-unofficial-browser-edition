package ftl.android.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A dialog in the website's style: framed panel, title, text, buttons right-aligned. It takes the whole window and stays
 * inside what the bars and keyboard leave; the text scrolls between the title and the buttons, which are always in
 * reach. When even that is too tight (landscape with the keyboard open) the title scrolls with the text.
 */
@Composable
fun FtlDialog(title: String, onDismiss: () -> Unit, buttons: @Composable () -> Unit, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        val taps = remember { MutableInteractionSource() }
        BoxWithConstraints(Modifier.fillMaxSize().clickable(taps, null, onClick = onDismiss).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            val tight = maxHeight < 300.dp
            val gap = Arrangement.spacedBy(if (tight) 8.dp else 12.dp)
            Column(
                Modifier.widthIn(max = if (wide) 820.dp else 520.dp).fillMaxWidth().heightIn(max = maxHeight).clickable(taps, null) {}
                    .background(Ftl.base100).border(1.dp, Ftl.headerLine).padding(if (tight) 8.dp else 20.dp),
                verticalArrangement = gap,
            ) {
                if (!tight) Txt(title, Ftl.h2)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
                    if (tight) Txt(title, Ftl.h2)
                    content()
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    }
}

/** "14:05" today, "Oct 28 14:05" before. */
fun clock(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val t = Instant.parse(iso).atZone(ZoneId.systemDefault())
        val today = Instant.now().atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS)
        t.format(DateTimeFormatter.ofPattern(if (t.isBefore(today)) "MMM d HH:mm" else "HH:mm"))
    }.getOrDefault("")
}

/** "2 h ago" style age for the save list. */
fun ago(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val s = ChronoUnit.SECONDS.between(Instant.parse(iso), Instant.now()).coerceAtLeast(0)
        when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }.getOrDefault("")
}

fun duration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
}
