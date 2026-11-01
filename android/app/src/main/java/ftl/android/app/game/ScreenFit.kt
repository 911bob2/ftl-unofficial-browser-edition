package ftl.android.app.game

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the game's picture fills the screen. The device's setting, kept apart from saves and settings.xml, so it never
 * reaches a save or a mod hash.
 *
 * - [Mode.DEFAULT]: the game's own shape with borders. The game has two: 16:9 (its art and its desktop layout) and 4:3
 *   (its iPad layout). The one giving this screen the larger picture is used.
 * - [Mode.STRETCH]: that same picture pulled to the whole screen, unevenly.
 * - [Mode.CUSTOM]: a canvas of the chosen shape, in even scale with borders if it differs from the screen. The HUD stays
 *   a centred 1024 wide block and the scene covers the rest.
 *
 * The game's view is sized to the picture ([viewSize]), so the borders are not part of it and fingers on them reach
 * nothing. Only [Mode.STRETCH] has a view whose shape differs from the canvas; GameRenderer maps x and y apart for it.
 *
 * [sharp]: the frame is drawn at twice the canvas's pixels and scaled down to the screen, instead of drawn at the canvas's
 * pixels and scaled up. The game's nearest-filtered art keeps hard edges that way. Layout and touch do not change.
 */
data class ScreenFit(val mode: Mode = Mode.DEFAULT, val ratio: Ratio? = null, val sharp: Boolean = true) {
    enum class Mode { DEFAULT, STRETCH, CUSTOM }

    /** A screen shape, width to height, written "19.5:9". */
    data class Ratio(val label: String) {
        val value: Double = label.split(":").let { it[0].toDouble() / it[1].toDouble() }
    }

    /** The picture's width over height on a screen of this size. */
    fun shape(screenW: Int, screenH: Int): Double = when (mode) {
        Mode.CUSTOM -> ratio?.value?.coerceAtLeast(ORIGINALS.last().value) ?: original(screenW, screenH).value
        else -> original(screenW, screenH).value
    }

    /** The width of the canvas the touch layout draws on; its height is always 768. */
    fun canvasWidth(screenW: Int, screenH: Int): Int =
        (CANVAS_H * shape(screenW, screenH)).roundToInt().coerceAtLeast(MIN_CANVAS_W)

    /** The game view's size, width then height, centred on a screen of this size. */
    fun viewSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        if (mode == Mode.STRETCH || screenW <= 0 || screenH <= 0) return screenW to screenH
        val r = shape(screenW, screenH)
        return if (screenW / screenH.toDouble() > r) (screenH * r).roundToInt() to screenH
        else screenW to (screenW / r).roundToInt()
    }

    /** Pixels drawn per canvas unit on a screen this tall: doubling only helps where the frame is scaled up. */
    fun renderScale(screenH: Int): Int = if (sharp && screenH >= SHARP_FROM) 2 else 1

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode.name).putString("ratio", ratio?.label).putBoolean("sharp", sharp).apply()
    }

    companion object {
        private const val PREFS = "screen"
        private const val CANVAS_H = 768
        private const val MIN_CANVAS_W = 1024
        const val SHARP_FROM = 900

        /** Below this shape a screen counts as a tablet's. */
        private const val PHONE_FROM = 1.7

        /** The game's own shapes, widest first. */
        val ORIGINALS = listOf(Ratio("16:9"), Ratio("4:3"))
        val PHONE_RATIOS = listOf("16:9", "18:9", "19.5:9", "20:9", "21:9").map(::Ratio)
        val TABLET_RATIOS = listOf("4:3", "3:2", "16:10").map(::Ratio)

        fun original(screenW: Int, screenH: Int): Ratio = ORIGINALS.maxBy { r ->
            min(screenW.toDouble(), screenH * r.value) * min(screenH.toDouble(), screenW / r.value)
        }

        /**
         * The shapes worth offering on a landscape screen of this size: those of its kind (phone or tablet) that fit it.
         * A shape wider than the screen only shrinks the picture. The screen's own shape is added when none matches it.
         */
        fun customRatios(screenW: Int, screenH: Int): List<Ratio> {
            val screen = max(screenW, screenH) / min(screenW, screenH).coerceAtLeast(1).toDouble()
            val fits = (if (screen >= PHONE_FROM) PHONE_RATIOS else TABLET_RATIOS).filter { it.value <= screen * 1.01 }
            val own = Ratio(String.format(Locale.ROOT, "%.2f:9", screen * 9))
            return when {
                screen < ORIGINALS.last().value -> listOf(ORIGINALS.last())
                fits.any { it.value >= screen * 0.99 } -> fits
                else -> fits + own
            }
        }

        fun load(context: Context): ScreenFit {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = Mode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: Mode.DEFAULT
            val ratio = runCatching { prefs.getString("ratio", null)?.let(::Ratio) }.getOrNull()
            val sharp = prefs.getBoolean("sharp", true)
            return if (mode == Mode.CUSTOM && ratio == null) ScreenFit(sharp = sharp) else ScreenFit(mode, ratio, sharp)
        }

        /** The whole display in landscape, bars and cutouts included: the game hides the bars and draws beside the cutout. */
        @Suppress("DEPRECATION")
        fun screenSize(context: Context): Pair<Int, Int> {
            val p = Point()
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(p)
            return max(p.x, p.y) to min(p.x, p.y)
        }
    }
}
