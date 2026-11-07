package ftl.android.app.game

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hyperspace's start-up error box (docs/HYPERSPACE.md, "Errors"), the same as the website's (web/hs-error-box.mjs):
 * HS's ErrorMessage is SDL_ShowSimpleMessageBox titled "Error" with one OK button. The original shows it before the
 * main menu over a black window and waits; OK, Enter, Escape or Back closes it. Measured from the original's capture:
 * a 414-unit wide light box, the red error icon, the text and a full-width OK button; its height follows the text.
 */
object HsErrorBox {
    /** Shows [texts] one after another (UI thread), then calls [done]. */
    fun showAll(activity: Activity, texts: List<String>, done: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (texts.isEmpty()) { done(); return }
        show(activity, texts.first()) { showAll(activity, texts.drop(1), done) }
    }

    private fun show(activity: Activity, text: String, closed: () -> Unit) {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics) }
        val dialog = Dialog(activity)
        var finished = false
        val finish = { if (!finished) { finished = true; if (dialog.isShowing) dialog.dismiss(); closed() } }
        val ink = Color.rgb(0x20, 0x20, 0x20)
        val message = TextView(activity).apply {
            this.text = text
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            typeface = Typeface.SANS_SERIF
            setLineSpacing(dp(13f) - paint.fontMetrics.let { it.descent - it.ascent }, 1f)
            includeFontPadding = false
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ErrorIcon(activity), LinearLayout.LayoutParams(dp(32f).toInt(), dp(32f).toInt()).apply { topMargin = dp(4f).toInt() })
            addView(message, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f).toInt() })
        }
        val ok = TextView(activity).apply {
            this.text = "OK"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            val border = GradientDrawable().apply { setColor(Color.rgb(0xfd, 0xfd, 0xfd)); setStroke(dp(1f).toInt().coerceAtLeast(1), Color.rgb(0x8f, 0x8f, 0x8f)) }
            val focus = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1f).toInt().coerceAtLeast(1), Color.BLACK, dp(1f), dp(1f)) }
            // The focused default button's dotted rectangle, as in the original.
            background = LayerDrawable(arrayOf(border, focus)).apply { val i = dp(3f).toInt(); setLayerInset(1, i, i, i, i) }
            setOnClickListener { finish() }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xf5, 0xf5, 0xf5))
            setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(4f).toInt(), dp(5f).toInt())
            addView(body)
            addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(23f).toInt()).apply { topMargin = dp(9f).toInt() })
            contentDescription = "Error"
        }
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { finish() } // Back
        dialog.setOnKeyListener { _, code, e ->
            val key = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_ESCAPE
            if (key && e.action == KeyEvent.ACTION_UP) finish()
            key
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val width = minOf(dp(414f).toInt(), activity.window.decorView.width - dp(16f).toInt()).coerceAtLeast(dp(200f).toInt())
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Keep the game's immersive mode: the dialog's window must not bring the system bars back.
            setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        dialog.show()
        dialog.window?.apply {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        ok.requestFocus()
    }

    /** The red error icon: a shaded red disc with a white cross. */
    private class ErrorIcon(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(0x8a, 0x0a, 0x05) }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }

        override fun onDraw(canvas: Canvas) {
            val s = width / 32f
            fill.shader = RadialGradient(12.8f * s, 9.6f * s, 24f * s, intArrayOf(Color.rgb(0xff, 0x7b, 0x70), Color.rgb(0xe2, 0x23, 0x1a), Color.rgb(0xa4, 0x0d, 0x08)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            edge.strokeWidth = s
            cross.strokeWidth = 3.6f * s
            canvas.drawCircle(16f * s, 16f * s, 14.5f * s, fill)
            canvas.drawCircle(16f * s, 16f * s, 14.5f * s, edge)
            canvas.drawLine(10.5f * s, 10.5f * s, 21.5f * s, 21.5f * s, cross)
            canvas.drawLine(21.5f * s, 10.5f * s, 10.5f * s, 21.5f * s, cross)
        }
    }
}
