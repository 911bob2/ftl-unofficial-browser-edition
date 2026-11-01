package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.game.TouchRouter.CANCEL
import xyz.znix.xftl.game.TouchRouter.DOWN
import xyz.znix.xftl.game.TouchRouter.MOVE
import xyz.znix.xftl.game.TouchRouter.UP

/** The routing of fingers by id, at the engine's call. tools/android-touch has the same gestures through the input stack. */
class TouchRouterTest {
    private class FakeHud(var takes: Boolean = true) : TouchRouter.Hud {
        val log = ArrayList<String>()
        override fun takesFinger(canvasX: Int, canvasY: Int) = takes
        override fun newGesture() { log += "new" }
        override fun touch(action: Int, id: Int, canvasX: Int, canvasY: Int, time: Long) { log += "$action:$id" }
    }

    private val mouseLog = ArrayList<String>()
    private val mouse = TouchRouter.Mouse { x, y, type, _ -> mouseLog += "$type@$x,$y" }
    private val hud = FakeHud()

    private fun send(h: TouchRouter.Hud?, action: Int, id: Int, x: Int = 10, y: Int = 20) = TouchRouter.route(h, action, id, x, y, 0L, mouse)

    @Before
    fun clean() {
        // The router is a singleton: end whatever a test left.
        for (id in listOf(0, 1, 2, 7)) send(null, CANCEL, id)
        mouseLog.clear()
    }

    @Test
    fun laterFingersReachTheHudAndTheFirstOwnsTheMouse() {
        send(hud, DOWN, 0)
        send(hud, DOWN, 1)
        send(hud, MOVE, 1, 50, 50)
        send(hud, MOVE, 0, 60, 60)
        assertEquals(listOf("new", "1:0", "1:1", "2:1", "2:0"), hud.log)
        assertEquals(listOf("0@10,20", "0@60,60"), mouseLog)
    }

    @Test
    fun aFingerLeftAfterTheFirstLiftsDoesNotReachTheHud() {
        send(hud, DOWN, 0)
        send(hud, DOWN, 1)
        send(hud, UP, 0)
        send(hud, MOVE, 1)
        send(hud, UP, 1)
        assertEquals(listOf("new", "1:0", "1:1", "3:0"), hud.log)
    }

    @Test
    fun aFingerLiftedAfterANewFirstOneStillEnds() {
        send(hud, DOWN, 0)
        send(hud, DOWN, 1)
        send(hud, UP, 0)
        send(hud, DOWN, 0)
        send(hud, UP, 1)
        send(hud, UP, 0)
        assertEquals(listOf("new", "1:0", "1:1", "3:0", "new", "1:0", "3:1", "3:0"), hud.log)
    }

    @Test
    fun cancelEndsTheGestureForTheNextOne() {
        send(hud, DOWN, 0)
        send(hud, DOWN, 1)
        send(hud, CANCEL, 0)
        send(hud, CANCEL, 1)
        hud.log.clear()
        send(hud, DOWN, 0)
        send(hud, UP, 0)
        assertEquals(listOf("new", "1:0", "3:0"), hud.log)
    }

    @Test
    fun aWindowTakesTheFirstFingerAsTheMouseAndIgnoresTheRest() {
        hud.takes = false
        send(hud, DOWN, 0)
        send(hud, DOWN, 1)
        send(hud, MOVE, 1, 90, 90)
        send(hud, UP, 1)
        send(hud, UP, 0)
        assertEquals(listOf("new"), hud.log)
        assertEquals(listOf("0@10,20", "1@10,20", "2@10,20"), mouseLog)
    }

    @Test
    fun aGestureThatEndedOutsideFlightDoesNotStayWithTheHud() {
        send(hud, DOWN, 0)
        send(null, UP, 0)
        hud.log.clear()
        send(hud, DOWN, 7)
        send(hud, UP, 7)
        assertEquals(listOf("new", "1:7", "3:7"), hud.log)
    }
}
