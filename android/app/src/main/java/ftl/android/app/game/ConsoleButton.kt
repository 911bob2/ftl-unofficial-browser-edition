package ftl.android.app.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The launcher's "Enable debug console" setting and the floating `>_` button it brings (docs/CONSOLE.md). While a run is
 * on screen the button sits over the game; a tap opens the debug console with the keyboard (another tap closes it), a
 * drag moves the button. Where it was left is kept for the next start, as fractions of the screen, and it stays on the
 * screen. Only the button's own square takes touches, so the HUD around it gets every other finger.
 */
@SuppressLint("ViewConstructor")
class ConsoleButton(context: Context, private val onTap: () -> Unit) : TextView(context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val sizePx = (SIZE_DP * resources.displayMetrics.density).roundToInt()
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        text = ">_"
        typeface = Typeface.MONOSPACE
        setTextColor(0xFFE6E6E6.toInt())
        textSize = 18f
        gravity = Gravity.CENTER
        contentDescription = "Debug console"
        background = GradientDrawable().apply {
            cornerRadius = sizePx / 6f
            setColor(0xB0202428.toInt())
            setStroke((2 * resources.displayMetrics.density).roundToInt(), 0xC0A0A8B0.toInt())
        }
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP or Gravity.START)
        visibility = View.GONE
    }

    /** Puts the button where it was left (or at its default place) within a [w] x [h] screen. */
    fun place(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val (x, y) = position(prefs.getFloat("x", DEFAULT_X), prefs.getFloat("y", DEFAULT_Y), w, h, sizePx)
        this.x = x.toFloat()
        this.y = y.toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val parent = parent as? View ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = x; startY = y; dragging = false
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                if (dragging) {
                    val (cx, cy) = clamp(startX + e.rawX - downX, startY + e.rawY - downY, parent.width, parent.height, sizePx)
                    x = cx; y = cy
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) save(parent.width, parent.height) else { performClick(); onTap() }
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                if (dragging) save(parent.width, parent.height)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun save(w: Int, h: Int) {
        val (fx, fy) = fraction(x, y, w, h, sizePx)
        prefs.edit().putFloat("x", fx).putFloat("y", fy).apply()
    }

    companion object {
        const val PREFS = "debug-console"
        const val SIZE_DP = 48

        /**
         * The default place, as fractions of the free room: the right edge, a little below the middle. The touch HUD has
         * nothing there (Jump at the top centre, Options at the top right, Pause at the bottom right).
         */
        const val DEFAULT_X = 1f
        const val DEFAULT_Y = 0.6f

        /** The launcher setting, a device setting (off by default). */
        fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        }

        /** The top left corner for fractions [fx], [fy] of the free room on a [w] x [h] screen. */
        fun position(fx: Float, fy: Float, w: Int, h: Int, size: Int): Pair<Int, Int> {
            val roomX = (w - size).coerceAtLeast(0)
            val roomY = (h - size).coerceAtLeast(0)
            return Pair((fx.coerceIn(0f, 1f) * roomX).roundToInt(), (fy.coerceIn(0f, 1f) * roomY).roundToInt())
        }

        /** A dragged corner kept on the screen. */
        fun clamp(x: Float, y: Float, w: Int, h: Int, size: Int): Pair<Float, Float> =
            Pair(x.coerceIn(0f, (w - size).coerceAtLeast(0).toFloat()), y.coerceIn(0f, (h - size).coerceAtLeast(0).toFloat()))

        /** The fractions of the free room that [position] turns back into the corner at [x], [y]. */
        fun fraction(x: Float, y: Float, w: Int, h: Int, size: Int): Pair<Float, Float> {
            val roomX = (w - size).coerceAtLeast(1)
            val roomY = (h - size).coerceAtLeast(1)
            return Pair((x / roomX).coerceIn(0f, 1f), (y / roomY).coerceIn(0f, 1f))
        }
    }
}
