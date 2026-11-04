package ftl.android.app

import ftl.android.app.game.Haptics
import ftl.android.app.game.Magnifier
import ftl.android.app.game.Magnifier.Companion.CANCEL
import ftl.android.app.game.Magnifier.Companion.DOWN
import ftl.android.app.game.Magnifier.Companion.MOVE
import ftl.android.app.game.Magnifier.Companion.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.znix.xftl.game.TouchHaptics

/**
 * Settings > Magnifier's rules (Magnifier): when the lens shows, where, and what it shows. It never changes a touch: the game
 * gets every finger event as without it (Input), so the touch HUD's own gestures stay. On the emulator: docs/ANDROID.md.
 */
class MagnifierTest {
    // A 2400x1080 phone with the picture filling it.
    private val area = Magnifier.Area(0f, 0f, 2400f, 1080f)

    private fun Magnifier.at(action: Int, x: Float, y: Float, t: Long, id: Int = 0) = touch(action, id, x, y, t, area)

    @Test
    fun offByDefaultAndThenNothingWatchesTheFingers() {
        assertFalse(Magnifier.DEFAULT_ON)
        assertNull(Magnifier.forGame(false))
        assertNotNull(Magnifier.forGame(true))
    }

    @Test
    fun aQuickTapNeverShowsIt() {
        val m = Magnifier()
        m.at(DOWN, 1200f, 600f, 1000)
        for (t in 1000L..1219L step 10) {
            m.at(MOVE, 1201f, 601f, t)
            assertFalse(m.appeared(m.lens(t, area)))
        }
        m.at(UP, 1201f, 601f, 1219)
        assertNull(m.lens(1300, area))
    }

    @Test
    fun aHeldFingerShowsItOnceAndTheLiftHidesIt() {
        val m = Magnifier()
        m.at(DOWN, 1200f, 600f, 1000)
        assertNull(m.lens(1219, area))
        val lens = m.lens(1220, area)
        assertNotNull(lens)
        assertTrue(m.appeared(lens))
        assertFalse(m.appeared(m.lens(1300, area)))
        m.at(UP, 1200f, 600f, 1400)
        assertFalse(m.appeared(m.lens(1400, area)))
        // A new hold comes up again (and plays its haptic again).
        m.at(DOWN, 500f, 600f, 2000)
        assertTrue(m.appeared(m.lens(2300, area)))
        m.at(CANCEL, 500f, 600f, 2400)
        assertNull(m.lens(2400, area))
    }

    @Test
    fun aFingerThatMovesFarShowsItAtOnce() {
        val m = Magnifier()
        m.at(DOWN, 1200f, 600f, 1000)
        m.at(MOVE, 1220f, 600f, 1010) // 20 px: less than 3 % of 1080
        assertNull(m.lens(1010, area))
        m.at(MOVE, 1240f, 600f, 1020) // 40 px
        assertNotNull(m.lens(1020, area))
    }

    @Test
    fun onlyTheFirstFingerCounts() {
        val m = Magnifier()
        m.at(DOWN, 1200f, 600f, 1000, id = 0)
        m.at(DOWN, 300f, 900f, 1100, id = 1)
        m.at(MOVE, 350f, 900f, 1150, id = 1)
        val lens = m.lens(1300, area)!!
        assertEquals(1200f, lens.srcX + lens.srcSize / 2, 0.01f)
        // The second finger's lift does nothing; the first one's hides it.
        m.at(UP, 350f, 900f, 1350, id = 1)
        assertNotNull(m.lens(1400, area))
        m.at(UP, 1200f, 600f, 1500, id = 0)
        assertNull(m.lens(1500, area))
    }

    @Test
    fun itSitsAboveTheFingerElseBesideItAwayFromTheNearerEdge() {
        val unit = 1080f / 100
        val m = Magnifier()
        m.at(DOWN, 1200f, 800f, 0)
        val above = m.lens(300, area)!!
        assertEquals(32 * unit, above.size, 0.01f)
        assertEquals(1200f, above.x + above.size / 2, 0.01f)
        assertEquals(800f - 10 * unit, above.y + above.size, 0.01f) // its bottom edge is the gap above the finger
        // Twice the size: the square around the finger is half the lens, and the mark is on the finger.
        assertEquals(above.size / 2, above.srcSize, 0.01f)
        assertEquals(1200f, above.srcX + above.srcSize / 2, 0.01f)
        assertEquals(above.x + above.size / 2, above.markX, 0.01f)
        assertEquals(above.y + above.size / 2, above.markY, 0.01f)
        // Near the top, beside: to the left in the right half, to the right in the left half.
        m.at(UP, 1200f, 800f, 400)
        m.at(DOWN, 2000f, 200f, 1000)
        val left = m.lens(1300, area)!!
        assertEquals(2000f - (16 + 10) * unit, left.x + left.size / 2, 0.01f)
        assertEquals(200f, left.y + left.size / 2, 0.01f)
        m.at(UP, 2000f, 200f, 1400)
        m.at(DOWN, 400f, 200f, 2000)
        val right = m.lens(2300, area)!!
        assertEquals(400f + (16 + 10) * unit, right.x + right.size / 2, 0.01f)
    }

    @Test
    fun atAnEdgeItStaysOnThePictureAndTheMarkFollowsTheFinger() {
        val unit = 1080f / 100
        val m = Magnifier()
        m.at(DOWN, 5f, 1075f, 0)
        val lens = m.lens(300, area)!!
        assertTrue(lens.x >= unit - 0.01f && lens.y >= unit - 0.01f)
        assertTrue(lens.x + lens.size <= 2400f && lens.y + lens.size <= 1080f - unit + 0.01f)
        assertEquals(0f, lens.srcX, 0.01f)
        assertEquals(1080f - lens.srcSize, lens.srcY, 0.01f)
        assertEquals(lens.x + 5f * 2, lens.markX, 0.01f)
        assertEquals(lens.y + (1075f - lens.srcY) * 2, lens.markY, 0.01f)
    }

    @Test
    fun itsHapticHasItsOwnSwitch() {
        val magnifier = Haptics.Action.of(TouchHaptics.MAGNIFIER)
        assertEquals(Haptics.Action.MAGNIFIER, magnifier)
        assertEquals("Magnifier", magnifier!!.title)
        assertNull(Haptics.planFor(TouchHaptics.MAGNIFIER, 1, Haptics.Strength.MEDIUM, Haptics.Kind.PRIMITIVES, setOf(Haptics.Action.POWER)))
        assertNotNull(Haptics.planFor(TouchHaptics.MAGNIFIER, 1, Haptics.Strength.MEDIUM, Haptics.Kind.PRIMITIVES, setOf(Haptics.Action.MAGNIFIER)))
        assertNull(Haptics.planFor(TouchHaptics.POWER, 1, Haptics.Strength.MEDIUM, Haptics.Kind.PRIMITIVES, setOf(Haptics.Action.MAGNIFIER)))
    }
}
