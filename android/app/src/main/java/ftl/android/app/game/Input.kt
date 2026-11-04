package ftl.android.app.game

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import ftl.browser.BrowserEngine

/**
 * Touch, mouse and keyboard into the engine's mouse and key calls, with the codes the website sends (web/game.mjs):
 * GLFW key codes, mouse type 0 move / 1 press / 2 release / 3 wheel, buttons 0 left / 1 right / 2 middle.
 *
 * Fingers go to the engine's touch router (TouchRouter) in the current frame's units, converted on the game thread
 * because the frame can change with the screen. A mouse's left button is a finger too, because the touch HUD has no
 * hover; its other buttons and the wheel stay a mouse.
 *
 * The engine must see every finger end. Android does not always say so when the app goes away, so the fingers down are
 * tracked here and ended by hand when they cannot end any other way.
 *
 * With Settings > Magnifier on, the renderer's [Magnifier] watches the same finger events; the engine gets them unchanged.
 */
class Input(private val renderer: GameRenderer) {
    private fun post(action: () -> Unit) {
        renderer.events.add(Runnable(action))
    }

    fun onTouch(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(e)
        debugTrace?.invoke(e)
        val time = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseFingers(time)
                finger(e, e.actionIndex, 1, time)
            }
            MotionEvent.ACTION_POINTER_DOWN -> finger(e, e.actionIndex, 1, time)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) finger(e, i, 2, time)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> finger(e, e.actionIndex, 3, time)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) finger(e, i, 4, time)
        }
        return true
    }

    private fun finger(e: MotionEvent, index: Int, action: Int, time: Long) {
        val id = e.getPointerId(index)
        val rawX = e.getX(index)
        val rawY = e.getY(index)
        if (action == 3 || action == 4) down.remove(id) else down[id] = floatArrayOf(rawX, rawY)
        post {
            renderer.magnifierTouch(action, id, rawX, rawY, time)
            BrowserEngine.touch(action, id, renderer.gameX(rawX), renderer.gameY(rawY), time)
        }
    }

    private val down = LinkedHashMap<Int, FloatArray>()

    /** Ends every finger still down, as the system's cancel does. For when the app goes away with fingers on it. */
    fun releaseFingers(time: Long = android.os.SystemClock.uptimeMillis()) {
        for ((id, at) in down) post { BrowserEngine.touch(4, id, renderer.gameX(at[0]), renderer.gameY(at[1]), time) }
        down.clear()
        post { renderer.magnifier?.reset() }
    }

    // Fingers on the black border around the picture, reaching the frame instead of the view. A second finger there
    // while another is on the picture is handed to the view by Android itself and counts; this path is for a gesture
    // that began on the border. Such a finger is nothing to the game (it may just be the hand holding the phone),
    // and must not take the first-finger role from one that lands on the picture.
    private val outside = HashSet<Int>()

    fun onFrameTouch(e: MotionEvent, view: android.view.View): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        debugTrace?.invoke(e)
        val time = e.eventTime
        // The view's own coordinates, as a finger on the view would have.
        e.offsetLocation(-view.left.toFloat(), -view.top.toFloat())
        fun inside(i: Int) = e.getX(i) >= 0 && e.getY(i) >= 0 && e.getX(i) < view.width && e.getY(i) < view.height
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseFingers(time)
                outside.clear()
                if (inside(e.actionIndex)) finger(e, e.actionIndex, 1, time) else outside += e.getPointerId(e.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN ->
                if (inside(e.actionIndex)) finger(e, e.actionIndex, 1, time) else outside += e.getPointerId(e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) if (e.getPointerId(i) !in outside) finger(e, i, 2, time)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (!outside.remove(e.getPointerId(e.actionIndex))) finger(e, e.actionIndex, 3, time)
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) if (e.getPointerId(i) !in outside) finger(e, i, 4, time)
                outside.clear()
            }
        }
        e.offsetLocation(view.left.toFloat(), view.top.toFloat())
        return true
    }

    private var mouseButtons = 0
    private var mouseFinger = false
    private val MOUSE_FINGER = 1000

    fun onMouse(e: MotionEvent): Boolean {
        val x = renderer.gameX(e.x)
        val y = renderer.gameY(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> post { BrowserEngine.mouse(x, y, 0, 0) }
            MotionEvent.ACTION_MOVE -> { val moveTime = e.eventTime; post {
                if (mouseFinger) BrowserEngine.touch(2, MOUSE_FINGER, x, y, moveTime) else BrowserEngine.mouse(x, y, 0, 0)
            } }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_DOWN -> {
                val b = button(e.actionButton.takeIf { it != 0 } ?: (e.buttonState and mouseButtons.inv()))
                if (b != null) {
                    mouseButtons = e.buttonState
                    if (b == 0) {
                        mouseFinger = true
                        val time = e.eventTime
                        post { BrowserEngine.touch(1, MOUSE_FINGER, x, y, time) }
                    } else {
                        post { BrowserEngine.mouse(x, y, 0, 0) }
                        post { BrowserEngine.mouse(x, y, 1, b) }
                    }
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP -> {
                val b = button(e.actionButton.takeIf { it != 0 } ?: (mouseButtons and e.buttonState.inv()))
                mouseButtons = e.buttonState
                if (b == 0) {
                    mouseFinger = false
                    val time = e.eventTime
                    post { BrowserEngine.touch(3, MOUSE_FINGER, x, y, time) }
                } else if (b != null) post { BrowserEngine.mouse(x, y, 2, b) }
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) post { BrowserEngine.mouse(x, y, 3, if (v > 0) 120 else -120) }
            }
        }
        return true
    }

    private fun button(state: Int): Int? = when {
        state and MotionEvent.BUTTON_PRIMARY != 0 -> 0
        state and MotionEvent.BUTTON_SECONDARY != 0 -> 1
        state and MotionEvent.BUTTON_TERTIARY != 0 -> 2
        else -> null
    }

    /** Hardware keys; Back is Escape. Returns false for keys the game does not use (volume keys stay with the system). */
    fun onKey(keyCode: Int, e: KeyEvent, down: Boolean): Boolean {
        val k = glfw(keyCode) ?: return false
        if (down && e.repeatCount > 0) return true
        val c = if (down) e.unicodeChar.takeIf { it in 32..0xffff } ?: 0 else 0
        post { BrowserEngine.key(k, c, down) }
        return true
    }

    /** Text from the on-screen keyboard. */
    fun typed(text: String) {
        for (c in text) {
            val code = if (c == '\n') 257 else 0
            post { BrowserEngine.key(code, c.code, true) }
            post { BrowserEngine.key(code, 0, false) }
        }
    }

    /** A key the on-screen keyboard stands in for. */
    fun pressed(glfw: Int) {
        post { BrowserEngine.key(glfw, 0, true) }
        post { BrowserEngine.key(glfw, 0, false) }
    }

    fun escape() {
        post { BrowserEngine.key(256, 0, true) }
        post { BrowserEngine.key(256, 0, false) }
    }

    companion object {
        /** Debug builds set this to log what reaches the view. */
        @Volatile var debugTrace: ((MotionEvent) -> Unit)? = null

        fun glfw(code: Int): Int? = when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + (code - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + (code - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 290 + (code - KeyEvent.KEYCODE_F1)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 320 + (code - KeyEvent.KEYCODE_NUMPAD_0)
            KeyEvent.KEYCODE_SPACE -> 32
            KeyEvent.KEYCODE_APOSTROPHE -> 39
            KeyEvent.KEYCODE_COMMA -> 44
            KeyEvent.KEYCODE_MINUS -> 45
            KeyEvent.KEYCODE_PERIOD -> 46
            KeyEvent.KEYCODE_SLASH -> 47
            KeyEvent.KEYCODE_SEMICOLON -> 59
            KeyEvent.KEYCODE_EQUALS -> 61
            KeyEvent.KEYCODE_LEFT_BRACKET -> 91
            KeyEvent.KEYCODE_BACKSLASH -> 92
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 93
            KeyEvent.KEYCODE_GRAVE -> 96
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> 256
            KeyEvent.KEYCODE_ENTER -> 257
            KeyEvent.KEYCODE_TAB -> 258
            KeyEvent.KEYCODE_DEL -> 259
            KeyEvent.KEYCODE_INSERT -> 260
            KeyEvent.KEYCODE_FORWARD_DEL -> 261
            KeyEvent.KEYCODE_DPAD_RIGHT -> 262
            KeyEvent.KEYCODE_DPAD_LEFT -> 263
            KeyEvent.KEYCODE_DPAD_DOWN -> 264
            KeyEvent.KEYCODE_DPAD_UP -> 265
            KeyEvent.KEYCODE_PAGE_UP -> 266
            KeyEvent.KEYCODE_PAGE_DOWN -> 267
            KeyEvent.KEYCODE_MOVE_HOME -> 268
            KeyEvent.KEYCODE_MOVE_END -> 269
            KeyEvent.KEYCODE_CAPS_LOCK -> 280
            KeyEvent.KEYCODE_SCROLL_LOCK -> 281
            KeyEvent.KEYCODE_NUM_LOCK -> 282
            KeyEvent.KEYCODE_SYSRQ -> 283
            KeyEvent.KEYCODE_BREAK -> 284
            KeyEvent.KEYCODE_NUMPAD_DOT -> 330
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 331
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 332
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 333
            KeyEvent.KEYCODE_NUMPAD_ADD -> 334
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 335
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> 336
            KeyEvent.KEYCODE_SHIFT_LEFT -> 340
            KeyEvent.KEYCODE_CTRL_LEFT -> 341
            KeyEvent.KEYCODE_ALT_LEFT -> 342
            KeyEvent.KEYCODE_META_LEFT -> 343
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 344
            KeyEvent.KEYCODE_CTRL_RIGHT -> 345
            KeyEvent.KEYCODE_ALT_RIGHT -> 346
            KeyEvent.KEYCODE_META_RIGHT -> 347
            else -> null
        }
    }
}
