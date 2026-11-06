package xyz.znix.xftl.game

/**
 * Where a finger goes (TouchMode). The first finger down owns the gesture until it lifts. In flight the touch HUD takes
 * it and also sees later fingers; screens without touch controls of their own take it as the left mouse button, as the
 * windows inside flight do.
 *
 * A finger is a tap, not a mouse press, on a screen's buttons: a button acts when the finger lifts, and only when it
 * lifts on the button it went down on, as the original's touch buttons do (each button keeps the finger that came down
 * inside it, and clicks when that finger lifts inside it; any other finger, or a lift outside it, does nothing). The
 * screens here act on the mouse's press, as the original does with the mouse, so a finger that comes down on a button
 * ([LiftTargets]) only moves the mouse there (the button lights up) until it lifts; lifted on that same button, the
 * whole click goes there at once, the press and, after a step of the game, the release, both to the screen that took
 * the press (HostInput), so what the click closes or opens never gets any part of it. Away from buttons the finger is
 * the mouse from the start: sliders, drags, the map, crew moved between boxes.
 *
 * The mouse's left button on Android is a finger too ([MOUSE_ID]), for the touch HUD; on the screens it stays a mouse
 * and presses at once, as on the desktop.
 */
object TouchRouter {
    const val DOWN = 1
    const val MOVE = 2
    const val UP = 3
    const val CANCEL = 4

    /** The id the Android host gives the mouse's left button. */
    const val MOUSE_ID = 1000

    fun interface Mouse {
        /** type 0 move, 1 press, 2 release; button 0 left. */
        fun event(x: Int, y: Int, type: Int, button: Int)
    }

    /** What takes fingers in flight: the touch HUD. */
    interface Hud {
        fun takesFinger(canvasX: Int, canvasY: Int): Boolean
        fun newGesture()
        fun touch(action: Int, id: Int, canvasX: Int, canvasY: Int, time: Long)
    }

    /**
     * A screen's buttons for a finger: what acts when tapped at ([x], [y]) (a button, a menu item, a choice), or null
     * where the finger is the mouse from its press (a slider, a drag, nothing). Two calls give equal values for the same
     * button.
     */
    fun interface LiftTargets {
        fun liftTarget(x: Int, y: Int): Any?
    }

    private var primary = -1
    private var toHud = false
    private var leaving: Mouse? = null

    /** The button the finger came down on, while it waits for the lift; null when the finger is the mouse. */
    private var tapTarget: Any? = null

    /** A tap's release, waiting for the game to step over its press: x, y. */
    private var tapRelease: IntArray? = null
    private var tapMouse: Mouse? = null

    /** A finger acting as the mouse is down (pressed or waiting to tap): screens show the hover under it only then. */
    @JvmStatic
    var fingerDown = false
        private set

    /** The pointer down is Android's mouse ([MOUSE_ID]), which acts on its press like the desktop's. */
    @JvmStatic
    var mouseDown = false
        private set

    /**
     * After the game has seen a mouse event: a tap's release after its press, then a lifted finger hovers over nothing.
     * True when it sent the release, for which the game has to step once more.
     */
    fun afterStep(): Boolean {
        tapRelease?.let { at ->
            tapRelease = null
            val mouse = tapMouse!!
            tapMouse = null
            mouse.event(at[0], at[1], 2, 0)
            leaving = mouse
            return true
        }
        leaving?.event(-1, -1, 0, 0)
        leaving = null
        return false
    }

    fun touch(game: MainGame, action: Int, id: Int, x: Int, y: Int, time: Long, mouse: Mouse) {
        val state = game.currentState
        val hud = (state as? InGameState)?.let { if (it.isDebugConsoleVisible) null else it.shipUI?.touch }
        route(hud, state as? LiftTargets, action, id, x, y, time, mouse)
    }

    /** [touch] once it knows what is on screen, with no buttons that wait for the lift. */
    fun route(hud: Hud?, action: Int, id: Int, x: Int, y: Int, time: Long, mouse: Mouse) =
        route(hud, null, action, id, x, y, time, mouse)

    /** [touch] once it knows what is on screen: the HUD in flight, the screen's [buttons]. */
    fun route(hud: Hud?, buttons: LiftTargets?, action: Int, id: Int, x: Int, y: Int, time: Long, mouse: Mouse) {
        val ends = id == primary && (action == UP || action == CANCEL)
        if (action == DOWN && primary == -1) {
            primary = id
            hud?.newGesture()
            toHud = hud != null && hud.takesFinger(x, y)
        }
        if (toHud && hud != null) {
            // The mouse follows the finger: beam aim and the enemy icons' tooltips read it.
            if (id == primary) mouse.event(x, y, 0, 0)
            hud.touch(action, id, x, y, time)
            if (ends) {
                mouse.event(-1, -1, 0, 0)
                primary = -1
                toHud = false
            }
            return
        }
        if (id != primary) return
        if (action == DOWN) mouseDown = id == MOUSE_ID
        asMouse(if (id == MOUSE_ID) null else buttons, action, x, y, mouse)
        if (ends) mouseDown = false
        if (ends) {
            primary = -1
            toHud = false
        }
    }

    private fun asMouse(buttons: LiftTargets?, action: Int, x: Int, y: Int, mouse: Mouse) {
        when (action) {
            DOWN -> {
                fingerDown = true
                // A release still waiting goes first: one finger's tap ends before the next finger's press.
                tapRelease?.let { at -> tapRelease = null; tapMouse = null; mouse.event(at[0], at[1], 2, 0) }
                leaving = null
                mouse.event(x, y, 0, 0)
                tapTarget = buttons?.liftTarget(x, y)
                if (tapTarget == null) mouse.event(x, y, 1, 0)
            }
            MOVE -> mouse.event(x, y, 0, 0)
            UP -> {
                fingerDown = false
                val target = tapTarget
                tapTarget = null
                if (target == null) {
                    mouse.event(x, y, 2, 0)
                    leaving = mouse
                } else if (buttons != null && buttons.liftTarget(x, y) == target) {
                    mouse.event(x, y, 0, 0)
                    mouse.event(x, y, 1, 0)
                    tapRelease = intArrayOf(x, y)
                    tapMouse = mouse
                } else leaving = mouse
            }
            // The system took the finger back: a release away from everything presses nothing.
            CANCEL -> {
                fingerDown = false
                val tapped = tapTarget != null
                tapTarget = null
                mouse.event(-1, -1, 0, 0)
                if (!tapped) mouse.event(-1, -1, 2, 0)
            }
        }
    }
}
