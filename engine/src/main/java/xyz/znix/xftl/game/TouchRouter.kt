package xyz.znix.xftl.game

/**
 * Where a finger goes (TouchMode). The first finger down owns the gesture until it lifts. In flight the touch HUD takes
 * it and also sees later fingers; screens without touch controls of their own take it as the left mouse button, as the
 * windows inside flight do.
 */
object TouchRouter {
    const val DOWN = 1
    const val MOVE = 2
    const val UP = 3
    const val CANCEL = 4

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

    private var primary = -1
    private var toHud = false
    private var leaving: Mouse? = null

    /** After the game has seen a release: a lifted finger hovers over nothing. */
    fun afterStep() {
        leaving?.event(-1, -1, 0, 0)
        leaving = null
    }

    fun touch(game: MainGame, action: Int, id: Int, x: Int, y: Int, time: Long, mouse: Mouse) =
        route((game.currentState as? InGameState)?.let { if (it.isDebugConsoleVisible) null else it.shipUI?.touch }, action, id, x, y, time, mouse)

    /** [touch] once it knows what is on screen. */
    fun route(hud: Hud?, action: Int, id: Int, x: Int, y: Int, time: Long, mouse: Mouse) {
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
        asMouse(action, x, y, mouse)
        if (ends) {
            primary = -1
            toHud = false
        }
    }

    private fun asMouse(action: Int, x: Int, y: Int, mouse: Mouse) {
        when (action) {
            DOWN -> {
                mouse.event(x, y, 0, 0)
                mouse.event(x, y, 1, 0)
            }
            MOVE -> mouse.event(x, y, 0, 0)
            UP -> {
                mouse.event(x, y, 2, 0)
                leaving = mouse
            }
            // The system took the finger back: a release away from everything presses nothing.
            CANCEL -> {
                mouse.event(-1, -1, 0, 0)
                mouse.event(-1, -1, 2, 0)
            }
        }
    }
}
