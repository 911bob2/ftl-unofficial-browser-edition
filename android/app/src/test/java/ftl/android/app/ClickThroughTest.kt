package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.newdawn.slick.util.InputAdapter
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.sys.HostInput

/**
 * One click, one screen (HostInput, the hosts' input on Android and the website): a press that replaces the screen
 * keeps its drags and its release with the screen that took it, so the next one never clicks what is under the pointer
 * (the title menu's options closed on Close, then the release clicked Tutorial under it). Through the touch router too,
 * as a finger reaches a screen without touch controls of its own. The website's check is tests/click-through.mjs.
 */
class ClickThroughTest {
    private val input = HostInput()
    private val log = ArrayList<String>()

    /** A screen that logs what reaches it; [onPress] runs on its press. */
    private inner class Screen(val name: String, val onPress: () -> Unit = {}) : InputAdapter() {
        override fun mousePressed(button: Int, x: Int, y: Int) { log += "$name press $button@$x,$y"; onPress() }
        override fun mouseReleased(button: Int, x: Int, y: Int) { log += "$name release $button@$x,$y" }
        override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) { log += "$name click $button@$x,$y" }
        override fun mouseDragged(oldx: Int, oldy: Int, newx: Int, newy: Int) { log += "$name drag $newx,$newy" }
    }

    /** MainGame.setCurrentState's swap of listeners. */
    private fun show(screen: InputAdapter) {
        input.removeAllListeners()
        input.addListener(screen)
    }

    @Before
    fun clean() {
        for (id in listOf(0, 1)) TouchRouter.route(null, TouchRouter.CANCEL, id, -1, -1, 0L) { _, _, _, _ -> }
    }

    @Test
    fun aClickOnOneScreenStaysWhole() {
        show(Screen("menu"))
        input.mouse(10, 20, 1, 0)
        assertTrue(input.isMouseButtonDown(0))
        input.mouse(15, 25, 0, 0)
        input.mouse(15, 25, 2, 0)
        assertFalse(input.isMouseButtonDown(0))
        assertEquals(listOf("menu press 0@10,20", "menu drag 15,25", "menu release 0@15,25", "menu click 0@15,25"), log)
    }

    @Test
    fun aPressThatChangesTheScreenKeepsItsReleaseFromTheNext() {
        val menu = Screen("menu")
        show(Screen("options") { show(menu) })
        input.mouse(100, 600, 1, 0)
        // The menu came up under the held button: it is not down there, and the drags and the release are not its.
        assertFalse(input.isMouseButtonDown(0))
        input.mouse(101, 601, 0, 0)
        input.mouse(101, 601, 2, 0)
        assertEquals(listOf("options press 0@100,600"), log)
        // Its own next click is whole.
        log.clear()
        input.mouse(101, 601, 1, 0)
        input.mouse(101, 601, 2, 0)
        assertEquals(listOf("menu press 0@101,601", "menu release 0@101,601", "menu click 0@101,601"), log)
    }

    @Test
    fun eachButtonBelongsToTheScreenOfItsOwnPress() {
        val menu = Screen("menu")
        show(Screen("options"))
        input.mouse(5, 5, 1, 1)
        show(menu)
        input.mouse(5, 5, 1, 0)
        assertTrue(input.isMouseButtonDown(0))
        assertFalse(input.isMouseButtonDown(1))
        input.mouse(6, 6, 2, 1)
        input.mouse(6, 6, 2, 0)
        assertEquals(listOf("options press 1@5,5", "menu press 0@5,5", "menu release 0@6,6", "menu click 0@6,6"), log)
    }

    @Test
    fun resetReleasesToTheScreenThatHasTheButton() {
        val menu = Screen("menu")
        val options = Screen("options")
        show(options)
        input.mouse(1, 1, 1, 0)
        input.reset()
        show(menu)
        input.mouse(2, 2, 1, 0)
        show(options)
        input.reset()
        assertEquals(listOf("options press 0@1,1", "options release 0@1,1", "menu press 0@2,2"), log)
        assertFalse(input.isMouseButtonDown(0))
    }

    @Test
    fun aFingerThatClosesAScreenDoesNotTapTheOneUnderIt() {
        // A finger outside flight is the left mouse button (TouchRouter); the options close on the finger's press.
        val mouse = TouchRouter.Mouse { x, y, type, button -> input.mouse(x, y, type, button) }
        val menu = Screen("menu")
        show(Screen("options") { show(menu) })
        TouchRouter.route(null, TouchRouter.DOWN, 0, 300, 400, 0L, mouse)
        TouchRouter.route(null, TouchRouter.MOVE, 0, 302, 401, 0L, mouse)
        TouchRouter.route(null, TouchRouter.UP, 0, 302, 401, 0L, mouse)
        TouchRouter.afterStep()
        assertEquals(listOf("options press 0@300,400"), log)
        // The next tap is the menu's, acting on the lift as before.
        log.clear()
        TouchRouter.route(null, TouchRouter.DOWN, 0, 302, 401, 0L, mouse)
        TouchRouter.route(null, TouchRouter.UP, 0, 302, 401, 0L, mouse)
        TouchRouter.afterStep()
        assertEquals(listOf("menu press 0@302,401", "menu release 0@302,401", "menu click 0@302,401"), log)
    }
}
