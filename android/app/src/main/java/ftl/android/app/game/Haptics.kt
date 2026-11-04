package ftl.android.app.game

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ftl.android.app.BuildConfig
import xyz.znix.xftl.game.TouchHaptics

/**
 * Settings > Haptics: the touch HUD's ticks (engine TouchHaptics) on this device's vibrator. The device's setting, in its
 * own preferences file as Screen is; it never reaches a save. FTL on iPad has no haptics, so there is no native setting
 * to follow (docs/ANDROID.md).
 *
 * The strength is made the best way the vibrator allows ([Kind]): a click primitive at a scale, else a short pulse at an
 * amplitude, else a plain pulse (on or off only). The vibrations are touch feedback, so Android's own Touch feedback
 * setting applies on top. Each [Action] has its own switch under the strength, on by default; Off turns them all off.
 */
class Haptics private constructor(private val vibrator: Vibrator?, val kind: Kind, val strength: Strength, val actions: Set<Action>) {
    enum class Strength(val label: String) { OFF("Off"), LIGHT("Light"), MEDIUM("Medium"), STRONG("Strong") }

    /**
     * What plays a haptic, each with a switch in Settings > Haptics: the engine's TouchHaptics kind, the switch's saved
     * name, title and line. A new action is a TouchHaptics kind and an entry here.
     */
    enum class Action(val engineKind: Int, val key: String, val title: String, val text: String) {
        POWER(TouchHaptics.POWER, "power", "System power", "A tick for each level of power a system box gains or loses."),
        MAGNIFIER(TouchHaptics.MAGNIFIER, "magnifier", "Magnifier", "A tick when the magnifier comes up under a held finger (Settings > Magnifier).");

        companion object {
            fun of(engineKind: Int): Action? = entries.firstOrNull { it.engineKind == engineKind }
        }
    }

    /** What this vibrator can do, best first. */
    enum class Kind { PRIMITIVES, AMPLITUDE, ON_OFF, NONE }

    /** The ticks to play: [count] click primitives at [scale], or [count] pulses of [pulseMs] at [amplitude] (0: the device's own). */
    data class Plan(val kind: Kind, val count: Int, val scale: Float = 1f, val pulseMs: Long = 0, val amplitude: Int = 0)

    /** Plays [count] ticks of a TouchHaptics kind (the engine's game thread), or [SAMPLE]. */
    fun play(what: Int, count: Int) {
        val plan = planFor(what, count, strength, kind, actions)
        if (plan == null) {
            if (BuildConfig.DEBUG && count > 0 && !plays(what, actions)) Log.i(TAG, "skip kind=$what count=$count: action off")
            return
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "play kind=$what count=$count strength=$strength $plan")
        vibrate(plan)
    }

    private fun vibrate(plan: Plan) {
        val v = vibrator ?: return
        val effect = when (plan.kind) {
            Kind.PRIMITIVES -> if (Build.VERSION.SDK_INT >= 30) VibrationEffect.startComposition().apply {
                repeat(plan.count) { i -> addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, plan.scale, if (i == 0) 0 else GAP_MS.toInt()) }
            }.compose() else return
            Kind.AMPLITUDE, Kind.ON_OFF -> {
                // Off, then a pulse and a gap per tick.
                val timings = LongArray(plan.count * 2) { if (it == 0) 0 else if (it % 2 == 1) plan.pulseMs else GAP_MS }
                if (plan.kind == Kind.AMPLITUDE) VibrationEffect.createWaveform(timings, IntArray(timings.size) { if (it % 2 == 1) plan.amplitude else 0 }, -1)
                else VibrationEffect.createWaveform(timings, -1)
            }
            Kind.NONE -> return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "vibrate failed: $it") }
    }

    companion object {
        const val TAG = "FTL-Haptic"
        private const val PREFS = "haptics"
        private const val GAP_MS = 45L
        /** A flick moves a whole bar at once: at most this many ticks. */
        private const val MAX_TICKS = 16
        val DEFAULT = Strength.MEDIUM

        /** The tick Settings plays when a strength is chosen: no action of its own, so no switch holds it back. */
        const val SAMPLE = -1

        /** Whether a TouchHaptics kind plays with these [enabled] actions: the sample always, a kind with no switch never. */
        fun plays(what: Int, enabled: Set<Action>): Boolean = what == SAMPLE || Action.of(what)?.let { it in enabled } == true

        /** What to play for [count] ticks of [what] at [strength] on a vibrator of [kind] with these [enabled] actions; null: nothing. */
        fun planFor(what: Int, count: Int, strength: Strength, kind: Kind, enabled: Set<Action>): Plan? =
            if (plays(what, enabled)) plan(strength, kind, count) else null

        /** What to play for [count] ticks at [strength] on a vibrator of [kind]; null: nothing. */
        fun plan(strength: Strength, kind: Kind, count: Int): Plan? {
            if (strength == Strength.OFF || kind == Kind.NONE || count <= 0) return null
            val n = count.coerceAtMost(MAX_TICKS)
            val i = strength.ordinal - 1
            return when (kind) {
                Kind.PRIMITIVES -> Plan(kind, n, scale = floatArrayOf(0.35f, 0.65f, 1f)[i])
                Kind.AMPLITUDE -> Plan(kind, n, pulseMs = longArrayOf(10, 15, 20)[i], amplitude = intArrayOf(90, 170, 255)[i])
                else -> Plan(Kind.ON_OFF, n, pulseMs = 20)
            }
        }

        /** The choices the setting offers on a vibrator of [kind]: none, on or off, or each strength. */
        fun choices(kind: Kind): List<Strength> = when (kind) {
            Kind.NONE -> emptyList()
            Kind.ON_OFF -> listOf(Strength.OFF, DEFAULT)
            else -> Strength.entries
        }

        /** How the chosen strength reads on a vibrator of [kind]. */
        fun label(strength: Strength, kind: Kind): String = when {
            kind == Kind.NONE -> "Not available"
            strength == Strength.OFF -> "Off"
            kind == Kind.ON_OFF -> "On"
            else -> strength.label
        }

        /** Settings' Haptics row: the strength, and which actions are off while it plays. */
        fun summary(strength: Strength, kind: Kind, enabled: Set<Action>): String {
            val label = label(strength, kind)
            if (kind == Kind.NONE || strength == Strength.OFF) return label
            val off = Action.entries.filter { it !in enabled }
            return when {
                off.isEmpty() -> label
                off.size == Action.entries.size -> "$label, every action off"
                else -> "$label, " + off.joinToString(", ") { it.title } + " off"
            }
        }

        private fun vibratorOf(context: Context): Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }.getOrNull()?.takeIf { it.hasVibrator() }

        private fun kindOf(context: Context, vibrator: Vibrator?): Kind {
            if (BuildConfig.DEBUG) testKind(context)?.let { return it }
            return when {
                vibrator == null -> Kind.NONE
                Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> Kind.PRIMITIVES
                vibrator.hasAmplitudeControl() -> Kind.AMPLITUDE
                else -> Kind.ON_OFF
            }
        }

        /** Debug builds: the console's `hapticdevice` pretends a weaker vibrator, for the setting's other texts. */
        private fun testKind(context: Context): Kind? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("kind-for-tests", null)?.let { k -> Kind.entries.firstOrNull { it.name == k } }

        fun setTestKind(context: Context, kind: Kind?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("kind-for-tests", kind?.name).apply()
        }

        fun saved(context: Context): Strength = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("strength", null)
            ?.let { s -> Strength.entries.firstOrNull { it.name == s } } ?: DEFAULT

        fun save(context: Context, strength: Strength) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("strength", strength.name).apply()
        }

        /** The actions switched on: each is on until switched off. */
        fun savedActions(context: Context): Set<Action> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { p ->
            Action.entries.filter { p.getBoolean("action-${it.key}", true) }.toSet()
        }

        fun saveAction(context: Context, action: Action, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("action-${action.key}", on).apply()
        }

        /** This device's haptics with the saved strength and actions, or with [strength] (a sample in Settings). */
        fun load(context: Context, strength: Strength = saved(context)): Haptics {
            val vibrator = vibratorOf(context)
            return Haptics(vibrator, kindOf(context, vibrator), strength, savedActions(context))
        }
    }
}
