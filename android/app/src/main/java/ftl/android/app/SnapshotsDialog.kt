package ftl.android.app

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ftl.android.app.net.ApiError
import ftl.android.app.sync.SyncEngine
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Txt
import ftl.android.app.ui.Working
import ftl.android.app.ui.clock
import ftl.saves.Snapshot
import ftl.saves.Snapshots
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves > ⋯ > Recovery snapshots (docs/SAVES.md): the account's last ten versions of a save, each with Restore, as on
 * the website. Restoring needs the network and the save's lock; the newer snapshots stay, so a restore can be undone.
 */
@Composable
fun SnapshotsDialog(id: String, name: String, onClose: (restored: String?) -> Unit) {
    val app = FtlApplication.app
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Snapshot>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Snapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            list = withContext(Dispatchers.IO) { app.sync.snapshots(id) }
        } catch (e: IOException) {
            error = e.message ?: "The recovery snapshots could not be loaded."
        }
    }
    val sync by app.sync.state.collectAsState()
    val current = Snapshots.current(list.orEmpty(), sync.cloud[id]?.revision)
    val playing = app.sync.playing == id
    confirm?.let { s ->
        val dirty = app.store.get(id)?.dirty == true
        FtlDialog(SaveWords.words("restoreTitle", "time" to clock(s.createdUtc)), { confirm = null }, {
            FtlButton("Cancel", { confirm = null }, enabled = !busy)
            FtlButton(if (busy) "Restoring…" else "Restore", {
                busy = true
                scope.launch {
                    try {
                        when (val r = withContext(Dispatchers.IO) { app.sync.restore(id, s.revision) }) {
                            is SyncEngine.LockResult.Held -> { error = SaveWords.words("restoreHeld", "device" to r.lock.device); confirm = null }
                            else -> { confirm = null; onClose(SaveWords.words("restored", "name" to name, "time" to clock(s.createdUtc))) }
                        }
                    } catch (e: ApiError) {
                        error = e.message
                        confirm = null
                    } catch (e: IOException) {
                        error = e.message ?: "Connect to the internet to restore a recovery snapshot."
                        confirm = null
                    } finally {
                        busy = false
                    }
                }
            }, kind = ButtonKind.DANGER, enabled = !busy)
        }) {
            Txt(SaveWords.words("restoreText"))
            if (dirty) Txt(SaveWords.words("restoreUnsynced"), Ftl.small.copy(color = Ftl.error))
        }
        return
    }
    FtlDialog("Recovery snapshots", { onClose(null) }, { FtlButton("Close", { onClose(null) }) }, wide = true) {
        Txt(name, Ftl.h2)
        Txt(SaveWords.words("snapshotsText"), Ftl.small)
        error?.let { Txt(it, Ftl.small.copy(color = Ftl.error)) }
        val l = list
        when {
            l == null && error == null -> Working()
            l == null -> {}
            l.isEmpty() -> Txt("No snapshots yet.", Ftl.small)
            else -> for (s in l) Row(Modifier.fillMaxWidth().border(1.dp, Ftl.line).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(listOfNotNull(clock(s.createdUtc), Snapshots.kindLabel(s.kind), "revision ${s.revision}", if (s.revision == current) "current" else null)
                    .joinToString(" · "), Ftl.small, Modifier.weight(1f))
                if (Snapshots.canRestore(s, current, playing)) FtlButton("Restore", { error = null; confirm = s })
            }
        }
    }
}
