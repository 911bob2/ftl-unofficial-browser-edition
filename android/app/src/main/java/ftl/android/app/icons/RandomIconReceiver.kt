package ftl.android.app.icons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import ftl.android.app.FtlApplication

/**
 * Random's turn after a reboot. Enabled only while Random is chosen. Android 15 also sends this broadcast when a stopped app is
 * next opened, which is no reboot, so the device's boot count decides.
 */
class RandomIconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val icons = AppIcons(context)
        val boots = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (!icons.newBoot(boots)) return
        if (FtlApplication.app.sync.playing == null) icons.rollRandom()
    }
}
