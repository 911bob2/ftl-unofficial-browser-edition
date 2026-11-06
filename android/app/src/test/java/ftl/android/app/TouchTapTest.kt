package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.newdawn.slick.util.InputAdapter
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.game.TouchRouter.CANCEL
import xyz.znix.xftl.game.TouchRouter.DOWN
import xyz.znix.xftl.game.TouchRouter.MOVE
import xyz.znix.xftl.game.TouchRouter.UP
import xyz.znix.xftl.sys.HostInput

/**
 * A finger taps a screen's buttons (TouchRouter.LiftTargets): the button acts when the finger lifts, only when it lifts
 * on the button it went down on, as the original's touch buttons do; away from buttons the finger is the mouse from its
 * press. The screens act on the mouse's press, so the tap's press and its release (a step later) both go at the lift.
 */
class TouchTapTest {
    private val log = ArrayList<String>()
    private val mouse = TouchRouter.Mouse { x, y, type, _ -> log += "$type@$x,$y" }

    /** Button A at x 0..99, B at 100..199; the rest is a slider (the mouse from the press). */
    private val buttons = TouchRouter.LiftTargets { x, _ -> when (x) { in 0..99 -> "A"; in 100..199 -> "B"; else -> null } }

    private fun send(action: Int, x: Int, y: Int = 10, id: Int = 0) = TouchRouter.route(null, buttons, action, id, x, y, 0L, mouse)

    @Before
    fun clean() {
        for (id in listOf(0, 1, TouchRouter.MOUSE_ID)) TouchRouter.route(null, CANCEL, id, -1, -1, 0L) { _, _, _, _ -> }
        while (TouchRouter.afterStep()) Unit
        TouchRouter.afterStep()
        log.clear()
    }

    @Test
    fun aButtonActsWhenTheFingerLiftsOnIt() {
        send(DOWN, 10)
        assertTrue(TouchRouter.fingerDown)
        send(MOVE, 20)
        // Down and moving it only points there (the button lights up).
        assertEquals(listOf("0@10,10", "0@20,10"), log)
        send(UP, 30)
        assertFalse(TouchRouter.fingerDown)
        assertEquals(listOf("0@10,10", "0@20,10", "0@30,10", "1@30,10"), log)
        // The release after the game has stepped over the press, then the finger hovers over nothing.
        assertTrue(TouchRouter.afterStep())
        assertFalse(TouchRouter.afterStep())
        assertEquals(listOf("0@10,10", "0@20,10", "0@30,10", "1@30,10", "2@30,10", "0@-1,-1"), log)
    }

    @Test
    fun aFingerLiftedOffItsButtonDoesNothing() {
        send(DOWN, 10)
        send(MOVE, 250)
        send(UP, 250)
        TouchRouter.afterStep()
        assertEquals(listOf("0@10,10", "0@250,10", "0@-1,-1"), log)
    }

    @Test
    fun aFingerLiftedOnAnotherButtonDoesNothing() {
        send(DOWN, 10)
        send(MOVE, 150)
        send(UP, 150)
        assertFalse(TouchRouter.afterStep())
        assertEquals(listOf("0@10,10", "0@150,10", "0@-1,-1"), log)
    }

    @Test
    fun aFingerSlidingOntoAButtonDoesNotTapIt() {
        // It came down on the slider: it is the mouse, pressed there, and its release on B is the slider's end.
        send(DOWN, 250)
        send(MOVE, 150)
        send(UP, 150)
        TouchRouter.afterStep()
        assertEquals(listOf("0@250,10", "1@250,10", "0@150,10", "2@150,10", "0@-1,-1"), log)
    }

    @Test
    fun aCancelledTapDoesNothing() {
        send(DOWN, 10)
        send(CANCEL, 10)
        TouchRouter.afterStep()
        assertEquals(listOf("0@10,10", "0@-1,-1"), log)
    }

    @Test
    fun theMouseActsOnItsPress() {
        send(DOWN, 10, id = TouchRouter.MOUSE_ID)
        assertTrue(TouchRouter.mouseDown)
        send(UP, 10, id = TouchRouter.MOUSE_ID)
        assertFalse(TouchRouter.mouseDown)
        TouchRouter.afterStep()
        assertEquals(listOf("0@10,10", "1@10,10", "2@10,10", "0@-1,-1"), log)
    }

    @Test
    fun aSecondFingerDoesNotTap() {
        send(DOWN, 10)
        send(DOWN, 120, id = 1)
        send(UP, 120, id = 1)
        send(UP, 10)
        TouchRouter.afterStep()
        assertEquals(listOf("0@10,10", "0@10,10", "1@10,10", "2@10,10"), log)
    }

    /** A screen that logs what reaches it; [onPress] runs on its press. */
    private inner class Screen(val name: String, val onPress: () -> Unit = {}) : InputAdapter() {
        override fun mousePressed(button: Int, x: Int, y: Int) { log += "$name press"; onPress() }
        override fun mouseReleased(button: Int, x: Int, y: Int) { log += "$name release" }
        override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) { log += "$name click" }
    }

    @Test
    fun aTapThatClosesAScreenKeepsItsReleaseFromTheNext() {
        val input = HostInput()
        fun show(s: InputAdapter) { input.removeAllListeners(); input.addListener(s) }
        val screens = TouchRouter.Mouse { x, y, type, button -> input.mouse(x, y, type, button) }
        val menu = Screen("menu")
        // Close at x 0..99 over the menu's Tutorial button: the options close on their press, as the original's do.
        show(Screen("options") { show(menu) })
        TouchRouter.route(null, buttons, DOWN, 0, 10, 10, 0L, screens)
        TouchRouter.route(null, buttons, UP, 0, 12, 10, 0L, screens)
        assertTrue(TouchRouter.afterStep())
        TouchRouter.afterStep()
        // The release is the options' (gone now), never the menu's.
        assertEquals(listOf("options press"), log)
        assertFalse(input.isMouseButtonDown(0))
        // The menu's own tap is whole.
        log.clear()
        TouchRouter.route(null, buttons, DOWN, 0, 12, 10, 0L, screens)
        TouchRouter.route(null, buttons, UP, 0, 12, 10, 0L, screens)
        TouchRouter.afterStep()
        assertEquals(listOf("menu press", "menu release", "menu click"), log)
    }
}
