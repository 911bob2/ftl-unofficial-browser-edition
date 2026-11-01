package ftl.android.app.game

import android.content.Context
import android.os.Build
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.text.InputType
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * The game's surface and its own GL thread. GLSurfaceView's thread has the default 1 MiB stack, and Hyperspace's Lua
 * runs on the game thread: deep C-level recursion inside Lua (nested string.gsub, for example) needs about 2 MiB
 * before Lua's own limit stops it. This thread has [STACK] bytes. Its EGL context lives as long as the view, with a
 * 1×1 pbuffer while there is no window, so the engine keeps its textures across pauses and queued work (the save on
 * pause) runs without a window.
 */
class GameView(context: Context, private val renderer: GameRenderer) : SurfaceView(context), SurfaceHolder.Callback {
    private val lock = Object()
    private val queue = ArrayDeque<Runnable>()
    private var surface: Surface? = null
    private var surfaceChanged = false
    private var width = 0
    private var height = 0
    private var paused = true
    private var quit = false
    private var windowReleased = true
    private val thread = Thread(null, ::run, "FTL-Game", STACK)

    /** Where the on-screen keyboard's text goes. */
    var input: Input? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { _, insets ->
            keyboardHeight = insets.getInsets(WindowInsets.Type.ime()).bottom
            placeOverKeyboard()
            insets
        }
        thread.start()
    }

    private var keyboardHeight = 0
    private var keyboardFocusY = 0

    /** The game moves up as far as it takes to keep the name being typed above the keyboard. */
    private fun placeOverKeyboard() {
        val focus = top + keyboardFocusY * height / 768 + height / 40
        val above = (parent as? android.view.View)?.height ?: height
        translationY = if (keyboardHeight > 0 && keyboardFocusY > 0) -(focus - (above - keyboardHeight)).coerceAtLeast(0).toFloat() else 0f
    }

    override fun onCheckIsTextEditor() = true

    // Character by character: the game's name fields take keys, so the keyboard must not hold back words to compose.
    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        out.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                input?.typed(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { input?.pressed(259) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                input?.pressed(257)
                return true
            }
        }
    }

    fun showKeyboard(show: Boolean, focusY: Int) {
        keyboardFocusY = focusY
        placeOverKeyboard()
        if (show == keyboardShown) return
        keyboardShown = show
        val ime = context.getSystemService(InputMethodManager::class.java)
        if (show) {
            requestFocus()
            ime.restartInput(this)
            ime.showSoftInput(this, 0)
        } else ime.hideSoftInputFromWindow(windowToken, 0)
    }

    private var keyboardShown = false

    fun queueEvent(r: Runnable) = synchronized(lock) { queue.addLast(r); lock.notifyAll() }

    fun onResume() = synchronized(lock) { paused = false; lock.notifyAll() }
    fun onPause() = synchronized(lock) { paused = true; lock.notifyAll() }

    /** Ends the thread after the queued work; waits for it at most two seconds. */
    fun shutdown() {
        synchronized(lock) { quit = true; lock.notifyAll() }
        thread.join(2000)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = synchronized(lock) {
        surface = holder.surface
        width = w
        height = h
        surfaceChanged = true
        windowReleased = false
        lock.notifyAll()
    }

    // The window surface must be released before this returns.
    override fun surfaceDestroyed(holder: SurfaceHolder) = synchronized(lock) {
        surface = null
        surfaceChanged = true
        lock.notifyAll()
        val until = System.nanoTime() + 2_000_000_000L
        while (!windowReleased && thread.isAlive && System.nanoTime() < until) lock.wait(100)
    }

    private lateinit var display: EGLDisplay
    private lateinit var config: EGLConfig
    private lateinit var eglContext: EGLContext
    private lateinit var pbuffer: EGLSurface
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE

    private fun egl(what: String): Nothing = throw IllegalStateException("$what failed: EGL error 0x${Integer.toHexString(EGL14.eglGetError())}")

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) egl("eglInitialize")
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) egl("eglChooseConfig")
        config = configs[0]!!
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) egl("eglCreateContext")
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (pbuffer == EGL14.EGL_NO_SURFACE) egl("eglCreatePbufferSurface")
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)) egl("eglMakeCurrent")
    }

    private fun releaseWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
    }

    private fun run() {
        try {
            initEgl()
            renderer.onContextCreated()
            loop()
        } catch (e: Throwable) {
            Log.e("FTL", "Game thread stopped", e)
            renderer.onFatal(e)
        } finally {
            synchronized(lock) {
                if (::display.isInitialized) {
                    releaseWindow()
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (::eglContext.isInitialized) EGL14.eglDestroyContext(display, eglContext)
                    if (::pbuffer.isInitialized) EGL14.eglDestroySurface(display, pbuffer)
                    EGL14.eglReleaseThread()
                }
                windowReleased = true
                lock.notifyAll()
            }
        }
    }

    private fun loop() {
        while (true) {
            var task: Runnable? = null
            var draw = false
            synchronized(lock) {
                while (true) {
                    if (surfaceChanged) {
                        surfaceChanged = false
                        releaseWindow()
                        val s = surface
                        if (s != null && s.isValid) {
                            window = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
                            if (window == EGL14.EGL_NO_SURFACE) egl("eglCreateWindowSurface")
                            if (!EGL14.eglMakeCurrent(display, window, window, eglContext)) egl("eglMakeCurrent")
                            renderer.onSurfaceChanged(width, height)
                        } else {
                            windowReleased = true
                            lock.notifyAll()
                        }
                    }
                    task = queue.removeFirstOrNull()
                    if (task != null) break
                    if (quit) return
                    if (!paused && window != EGL14.EGL_NO_SURFACE) { draw = true; break }
                    lock.wait()
                }
            }
            task?.let { runTask(it); continue }
            if (draw) {
                renderer.onDrawFrame()
                if (!EGL14.eglSwapBuffers(display, window)) {
                    val error = EGL14.eglGetError()
                    if (error == EGL14.EGL_CONTEXT_LOST) throw IllegalStateException("The GL context was lost")
                    // The window went away under us; surfaceDestroyed follows.
                    Log.w("FTL", "eglSwapBuffers: EGL error 0x${Integer.toHexString(error)}")
                }
            }
        }
    }

    private fun runTask(r: Runnable) {
        try { r.run() } catch (e: Throwable) {
            Log.e("FTL", "Game thread task failed", e)
            if (e is OutOfMemoryError) renderer.onFatal(e)
        }
    }

    companion object {
        /** The game thread's stack: Lua's 200 nested C calls need about 2 MiB; the rest is headroom. */
        const val STACK = 16L shl 20
    }
}
