package ftl.android.app.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import ftl.android.app.BuildConfig
import ftl.android.app.R
import kotlin.random.Random

/** One app icon: its number in the icon set, its name in the chooser, its launcher entry and its image. */
data class AppIcon(val number: String, val title: String, val alias: String, val image: Int)

/** What decides the icon, free of Android, so it can be tested. */
object IconRules {
    const val DEFAULT = "02"

    /** A different icon from [current], so that Random is seen to change. */
    fun nextRandom(current: String, numbers: List<String>, random: Random = Random.Default): String {
        val others = numbers.filter { it != current }
        return if (others.isEmpty()) current else others[random.nextInt(others.size)]
    }

    /**
     * Which entry counts as the one chosen, from those enabled: normally there is exactly one. A switch cut short below
     * Android 13, where it takes two calls, can leave two: the one being switched to wins. None at all falls back to
     * the default.
     */
    fun chosen(enabled: List<String>, wanted: String?): String = when {
        enabled.size == 1 -> enabled[0]
        wanted != null && wanted in enabled -> wanted
        else -> enabled.firstOrNull { it != DEFAULT } ?: DEFAULT
    }

    /**
     * Whether to offer a choice at all. Every Android this app runs on has the calls, so it is not a question of version.
     * Devices without an ordinary phone or tablet home screen have nothing to show the choice on, and a device that once
     * refused the switch (an enterprise policy, a changed system) stays on the default.
     */
    fun supported(hasHomeScreen: Boolean, tv: Boolean, watch: Boolean, car: Boolean, refused: Boolean): Boolean =
        hasHomeScreen && !tv && !watch && !car && !refused

    /** Home screen apps seen on a device to keep a placed icon through a change (docs/ANDROID.md, "App icon"). */
    private val TESTED_HOMES = setOf("com.google.android.apps.nexuslauncher")

    fun homeTested(homePackage: String?): Boolean = homePackage in TESTED_HOMES
}

/**
 * The launcher icon. Android has no call to set an app's icon: each icon is an activity-alias with its own image and one is
 * enabled at a time. A launcher sees that as one app entry going and another coming, and may take an icon the player placed
 * on the home screen with it (docs/ANDROID.md, "App icon"), so a change happens only when the player has been told or asked
 * for it, and never while a game is open.
 *
 * Android's record of what is enabled is the truth; [PREFS] holds only the Random choice and the switch under way.
 */
class AppIcons(context: Context) {
    private val app = context.applicationContext
    private val pm = app.packageManager
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val all: List<AppIcon> get() = ICONS

    private fun component(icon: AppIcon) = ComponentName(app.packageName, "ftl.android.app.${icon.alias}")

    private fun isEnabled(icon: AppIcon) = when (pm.getComponentEnabledSetting(component(icon))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.number == IconRules.DEFAULT
        else -> false
    }

    val supported: Boolean get() = IconRules.supported(
        hasHomeScreen = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0) != null,
        tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
        watch = pm.hasSystemFeature(PackageManager.FEATURE_WATCH),
        car = pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
        refused = prefs.getBoolean(KEY_REFUSED, false),
    )

    private val homePackage: String? get() = prefs.getString(KEY_HOME_FOR_TESTS, null).takeIf { BuildConfig.DEBUG }
        ?: pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName

    val homeTested: Boolean get() = IconRules.homeTested(homePackage)

    /** The player has been told once that this home screen app may drop a placed icon. */
    var warned: Boolean
        get() = prefs.getBoolean(KEY_WARNED, false)
        set(value) { prefs.edit().putBoolean(KEY_WARNED, value).apply() }

    val current: AppIcon get() = byNumber(IconRules.chosen(ICONS.filter { isEnabled(it) }.map { it.number }, prefs.getString(KEY_WANTED, null)))

    var random: Boolean
        get() = prefs.getBoolean(KEY_RANDOM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_RANDOM, value).putInt(KEY_BOOT, Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)).apply()
            // No process starts at boot for players who do not use it.
            pm.setComponentEnabledSetting(ComponentName(app, RandomIconReceiver::class.java),
                if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }

    /** Makes [icon] the launcher entry. False when Android refused, after going back to the default. Does not kill the app. */
    fun set(icon: AppIcon): Boolean {
        val before = current
        if (before == icon && ICONS.count { isEnabled(it) } == 1) return true
        return try {
            prefs.edit().putString(KEY_WANTED, icon.number).commit()
            apply(icon)
            prefs.edit().remove(KEY_WANTED).apply()
            check(launcherEntries() == listOf(component(icon).className)) { "launcher entries: ${launcherEntries()}" }
            Log.i(TAG, "App icon ${before.number} -> ${icon.number}")
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "App icon switch refused", e)
            prefs.edit().putBoolean(KEY_REFUSED, true).putBoolean(KEY_RANDOM, false).remove(KEY_WANTED).apply()
            runCatching { apply(byNumber(IconRules.DEFAULT)) }
            false
        }
    }

    private fun apply(icon: AppIcon) {
        val flags = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= 33) {
            // At once, so no launcher ever sees two entries or none.
            pm.setComponentEnabledSettings(ICONS.map { other ->
                PackageManager.ComponentEnabledSetting(component(other),
                    if (other == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
            })
        } else {
            // The new one first: a process that dies in between leaves two entries, which the next start repairs, never none.
            pm.setComponentEnabledSetting(component(icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flags)
            for (other in ICONS) if (other != icon) pm.setComponentEnabledSetting(component(other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flags)
        }
    }

    /** What the launcher is offered for this app. */
    private fun launcherEntries(): List<String> = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0,
    ).map { it.activityInfo.name }

    /** At each start: a switch cut short leaves exactly one entry again. */
    fun repair() {
        if (ICONS.count { isEnabled(it) } != 1) set(current)
    }

    /** Whether this boot count is one not seen before; remembered at once. */
    fun newBoot(count: Int): Boolean {
        val seen = prefs.getInt(KEY_BOOT, -1)
        if (seen == count) return false
        prefs.edit().putInt(KEY_BOOT, count).apply()
        return true
    }

    /** Random's turn, at a safe moment: the app is leaving the screen or the device has booted, and no game is open. */
    fun rollRandom(): Boolean {
        if (!random || !supported) return false
        return set(byNumber(IconRules.nextRandom(current.number, ICONS.map { it.number })))
    }

    private fun byNumber(number: String) = ICONS.firstOrNull { it.number == number } ?: ICONS.first { it.number == IconRules.DEFAULT }

    companion object {
        private const val TAG = "FTL-Icon"
        const val PREFS = "app-icon"
        private const val KEY_RANDOM = "random"
        private const val KEY_WANTED = "wanted"
        private const val KEY_REFUSED = "refused"
        private const val KEY_WARNED = "warned"
        private const val KEY_BOOT = "boot-seen"
        private const val KEY_HOME_FOR_TESTS = "home-for-tests"

        val ICONS = listOf(
            AppIcon("02", "FTL logo", "AppIcon02", R.mipmap.ic_launcher_02),
            AppIcon("01", "Kestrel", "AppIcon01", R.mipmap.ic_launcher_01),
            AppIcon("20", "Kestrel rear", "AppIcon20", R.mipmap.ic_launcher_20),
            AppIcon("05", "Shields", "AppIcon05", R.mipmap.ic_launcher_05),
            AppIcon("10", "Engines", "AppIcon10", R.mipmap.ic_launcher_10),
            AppIcon("11", "FTL jump", "AppIcon11", R.mipmap.ic_launcher_11),
            AppIcon("15", "Asteroids", "AppIcon15", R.mipmap.ic_launcher_15),
            AppIcon("16", "Ion storm", "AppIcon16", R.mipmap.ic_launcher_16),
            AppIcon("17", "Solar flare", "AppIcon17", R.mipmap.ic_launcher_17),
        )
    }
}
