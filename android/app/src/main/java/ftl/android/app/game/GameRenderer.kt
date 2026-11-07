package ftl.android.app.game

import ftl.android.app.log.DebugLog
import android.opengl.GLES30
import android.util.Log
import ftl.browser.BrowserEngine
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.rendering.DrawQueue
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The browser's frame loop (web/game.mjs) on the game thread (GameView): queued input events (each followed by a zero step),
 * one step of at most 0.05 s, then a render. The engine draws its frame (1280×720, or the touch layout's canvas in
 * flight: TouchMode) into an offscreen framebuffer, which is scaled onto the game view: evenly with bars when the frame
 * differs from the view (a 1280×720 screen on a 4:3 canvas), pulled to fit under ScreenFit's Stretch.
 */
class GameRenderer(
    private val fit: ScreenFit,
    private val screenH: Int,
    private val start: () -> Unit,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onKeyboard: (Boolean, Int) -> Unit,
    private val onDropped: (List<String>) -> Unit,
    /** Settings > Magnifier, or null when it is off (then nothing here looks at the fingers); [onMagnifier]: it came up. */
    val magnifier: Magnifier? = null,
    private val onMagnifier: () -> Unit = {},
) {
    private var lastDropped: List<String> = xyz.znix.xftl.savegame.MissingContent.dropped
    val events = ConcurrentLinkedQueue<Runnable>()
    @Volatile var suspended = false
    /** Hyperspace's start-up error box is up (HsErrorBox): no step and no frame, the screen stays black. */
    @Volatile var held = false
    /** Frames drawn, for the debug console. */
    @Volatile var frames = 0L

    private var started = false
    private var failed = false
    private var readyReported = false
    private var menuFrame: Frame? = null
    private var flightFrame: Frame? = null
    private var keyboardShown = false
    private var keyboardFocus = 0
    private var frame: Frame? = null
    private var last = 0L
    /** Frame pixels per game unit (ScreenFit.renderScale). */
    private var pixels = 1
    private var viewW = 1
    private var viewH = 1

    /** The frame on the view, in view pixels (left, top, scales), for mapping touches into game units. */
    @Volatile var frameLeft = 0f
    @Volatile var frameTop = 0f
    @Volatile var frameScaleX = 1f
    @Volatile var frameScaleY = 1f

    /** Once, when the game thread's context exists (before any window): the engine starts. */
    fun onContextCreated() {
        active = this
        TouchMode.enabled = true
        // The engine keeps its shader programmes across games in this process; they belong to the previous context.
        DrawQueue.onContextRecreated()
        val sizes = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, sizes, 0)
        pixels = if (fit.renderScale(screenH) * MAX_CANVAS <= sizes[0]) fit.renderScale(screenH) else 1
        menuFrame = Frame(WIDTH, HEIGHT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        try {
            useFrame(menuFrame!!)
            start()
            BrowserEngine.render()
            started = true
        } catch (e: Throwable) {
            fail(e)
        }
        last = System.nanoTime()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewW = width
        viewH = height
        if (TouchMode.enabled) TouchMode.canvasW = fit.canvasWidth(width, height)
        layOut(frame?.w ?: WIDTH, frame?.h ?: HEIGHT)
    }

    private fun layOut(w: Int, h: Int) {
        val even = minOf(viewW / w.toFloat(), viewH / h.toFloat())
        val stretch = fit.mode == ScreenFit.Mode.STRETCH
        frameScaleX = if (stretch) viewW / w.toFloat() else even
        frameScaleY = if (stretch) viewH / h.toFloat() else even
        frameLeft = (viewW - w * frameScaleX) / 2
        frameTop = (viewH - h * frameScaleY) / 2
    }

    private fun useFrame(f: Frame) {
        frame = f
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f.id)
        // through the engine's wrapper, which keeps it: Graphics.pushClip maps its clip rectangles to these pixels
        org.lwjgl.opengl.GL11.glViewport(0, 0, f.w * pixels, f.h * pixels)
    }

    /** The frame the current screen draws in; a new one when the screen's canvas changed. */
    private fun frameForScreen(): Frame {
        val w = BrowserEngine.canvasWidth()
        val h = BrowserEngine.canvasHeight()
        val menu = menuFrame!!
        if (w == menu.w && h == menu.h) return menu
        val f = flightFrame
        if (f != null && f.w == w && f.h == h) return f
        f?.delete()
        return Frame(w, h).also { flightFrame = it }
    }

    fun onDrawFrame() {
        if (failed || !started) return
        if (held) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewW, viewH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (!readyReported) {
                readyReported = true
                onReady()
            }
            last = System.nanoTime()
            return
        }
        frames++
        val now = System.nanoTime()
        val dt = ((now - last).coerceAtLeast(0) / 1e9).coerceAtMost(0.05).toFloat()
        last = now
        try {
            frame?.let { useFrame(it) }
            // Suspended (taken over) or shut down: the last frame stays on screen and the engine is not touched again.
            if (!suspended && BrowserEngine.running()) {
                while (true) {
                    val e = events.poll() ?: break
                    e.run()
                    BrowserEngine.step(0f)
                }
                BrowserEngine.step(dt)
                if (TouchMode.keyboardWanted != keyboardShown || TouchMode.keyboardFocusY != keyboardFocus) {
                    keyboardShown = TouchMode.keyboardWanted
                    keyboardFocus = TouchMode.keyboardFocusY
                    onKeyboard(keyboardShown, keyboardFocus)
                }
                val dropped = xyz.znix.xftl.savegame.MissingContent.dropped
                if (dropped !== lastDropped) {
                    lastDropped = dropped
                    if (dropped.isNotEmpty()) onDropped(dropped)
                }
                val f = frameForScreen()
                if (f !== frame) {
                    useFrame(f)
                    layOut(f.w, f.h)
                }
                BrowserEngine.renderFrame(dt)
                // The driver's way of running out: images would go missing from the screen without a word.
                if (frames % GL_ERROR_CHECK_FRAMES == 0L && graphicsMemoryGone()) throw OutOfMemoryError("Graphics memory")
            } else events.clear()
        } catch (e: Throwable) {
            fail(e)
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        val shown = frame!!
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, shown.id)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val l = frameLeft.toInt()
        val b = (viewH - frameTop - shown.h * frameScaleY).toInt()
        GLES30.glBlitFramebuffer(0, 0, shown.w * pixels, shown.h * pixels, l, b, l + (shown.w * frameScaleX).toInt(), b + (shown.h * frameScaleY).toInt(),
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        magnifier?.let { m ->
            val lens = m.lens(android.os.SystemClock.uptimeMillis(), area(shown))
            if (m.appeared(lens)) {
                if (ftl.android.app.BuildConfig.DEBUG) Log.i("FTL-Magnifier", "shown at ${lens!!.markX.toInt()},${lens.markY.toInt()} lens ${lens.x.toInt()},${lens.y.toInt()} ${lens.size.toInt()}")
                onMagnifier()
            }
            if (lens != null) drawLens(lens, shown)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shown.id)
        if (!readyReported) {
            readyReported = true
            onReady()
        }
    }

    /** Where the frame lies on the view. */
    private fun area(f: Frame) = Magnifier.Area(frameLeft, frameTop, f.w * frameScaleX, f.h * frameScaleY)

    /** A finger event for the magnifier, in view pixels (game thread, before the engine gets it). */
    fun magnifierTouch(action: Int, id: Int, x: Float, y: Float, time: Long) {
        val m = magnifier ?: return
        val f = frame ?: return
        m.touch(action, id, x, y, time, area(f))
    }

    /** The lens over the picture on the view: a frame, the frame's pixels around the finger twice as large, the touch mark. */
    private fun drawLens(lens: Magnifier.Lens, shown: Frame) {
        fun fill(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float) {
            GLES30.glScissor(x.toInt(), (viewH - y - h).toInt(), w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            GLES30.glClearColor(r, g, b, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val e = lens.border
        fill(lens.x - e, lens.y - e, lens.size + 2 * e, lens.size + 2 * e, 0.55f, 0.85f, 0.95f)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        // The picture's pixels around the finger: view pixels to the frame's (rows counted from the bottom in GL).
        val fx = (lens.srcX - frameLeft) / frameScaleX * pixels
        val fy = (lens.srcY - frameTop) / frameScaleY * pixels
        val fw = lens.srcSize / frameScaleX * pixels
        val fh = lens.srcSize / frameScaleY * pixels
        val fullH = shown.h * pixels
        GLES30.glBlitFramebuffer(fx.toInt(), (fullH - fy - fh).toInt(), (fx + fw).toInt(), (fullH - fy).toInt(),
            lens.x.toInt(), (viewH - lens.y - lens.size).toInt(), (lens.x + lens.size).toInt(), (viewH - lens.y).toInt(),
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        // The touch point: a dark-edged amber square ring.
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val r = lens.markSize / 2
        val t = (lens.border * 0.6f).coerceAtLeast(2f)
        for ((grow, colour) in listOf(t to floatArrayOf(0f, 0f, 0f), 0f to floatArrayOf(1f, 0.85f, 0.3f))) {
            val x0 = lens.markX - r - grow
            val y0 = lens.markY - r - grow
            val s = 2 * (r + grow)
            val w = t + grow
            fill(x0, y0, s, w, colour[0], colour[1], colour[2])
            fill(x0, y0 + s - w, s, w, colour[0], colour[1], colour[2])
            fill(x0, y0, w, s, colour[0], colour[1], colour[2])
            fill(x0 + s - w, y0, w, s, colour[0], colour[1], colour[2])
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun graphicsMemoryGone(): Boolean {
        var gone = false
        while (true) {
            val error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) return gone
            if (error == GLES30.GL_OUT_OF_MEMORY) gone = true
        }
    }

    /** The game thread itself failed (EGL). */
    fun onFatal(e: Throwable) { if (!failed) fail(e) }

    private fun fail(e: Throwable) {
        failed = true
        Log.e("FTL", "Engine stopped", e)
        DebugLog.caught("The game stopped", e)
        onError(e)
    }

    private inner class Frame(val w: Int, val h: Int) {
        val id: Int
        private val buffers = IntArray(2)

        init {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            id = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id)
            GLES30.glGenRenderbuffers(2, buffers, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, buffers[0])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w * pixels, h * pixels)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, buffers[0])
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, buffers[1])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, w * pixels, h * pixels)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, buffers[1])
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("Framebuffer incomplete: $status")
        }

        fun delete() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(id), 0)
            GLES30.glDeleteRenderbuffers(2, buffers, 0)
        }
    }

    /** View pixels to game units. */
    fun gameX(x: Float) = ((x - frameLeft) / frameScaleX).toInt()
    fun gameY(y: Float) = ((y - frameTop) / frameScaleY).toInt()

    companion object {
        /** The running game's renderer (only one at a time). */
        @Volatile var active: GameRenderer? = null
        const val GL_ERROR_CHECK_FRAMES = 64L
        const val WIDTH = 1280
        const val HEIGHT = 720
        /** The widest canvas the setting offers (21:9), for checking the driver can hold the doubled frame. */
        private const val MAX_CANVAS = 1792
    }
}
