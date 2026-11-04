package ftl.android.app

import androidx.compose.runtime.Composable
import ftl.android.app.ui.ButtonKind
import ftl.android.app.ui.Ftl
import ftl.android.app.ui.FtlButton
import ftl.android.app.ui.FtlDialog
import ftl.android.app.ui.Txt
import ftl.saves.LocalSave

/**
 * Sign out always asks first, with the website's words (web/sign-out.mjs): Cancel or Sign out, and a stronger line when
 * a save of this account has progress the account does not have yet. What signing out does is the launcher's.
 */
object SignOut {
    const val TITLE = "Sign out?"
    const val TEXT = "Sign in again to play this account's saves here."
    const val UNSYNCED = "Your latest progress is saved on this device but not in your account yet. It syncs the next time you sign in here."
    const val YES = "Sign out"
    const val NO = "Cancel"

    /** True when one of these saves has progress or a new name the account does not have yet. */
    fun unsynced(saves: List<LocalSave>): Boolean = saves.any { it.dirty || it.nameDirty }
}

@Composable
fun SignOutDialog(unsynced: Boolean, onSignOut: () -> Unit, onCancel: () -> Unit) {
    FtlDialog(SignOut.TITLE, onCancel, {
        FtlButton(SignOut.NO, onCancel, kind = ButtonKind.GHOST)
        FtlButton(SignOut.YES, onSignOut, kind = ButtonKind.PRIMARY)
    }) {
        Txt(SignOut.TEXT)
        if (unsynced) Txt(SignOut.UNSYNCED, Ftl.small.copy(color = Ftl.accent))
    }
}
