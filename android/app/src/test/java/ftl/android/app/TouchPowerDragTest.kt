package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.znix.xftl.game.TouchPowerDrag
import kotlin.math.abs

/**
 * The touch HUD's power drag (TouchPowerDrag, used by TouchHud's system boxes): the iPad's step up to eight levels, and
 * past eight (Hyperspace systems, Multiverse's Sylvan Transport: shields 16, weapons 14, drones 13) a step that keeps the
 * finger on the bar it set. The emulator check is in docs/ANDROID.md.
 */
class TouchPowerDragTest {
    /** Where the box draws bar [k]'s top, from the bottom bar's, the way MainSystem.drawPowerBars stacks them. */
    private fun drawnOffset(k: Int, rows: Int, paired: Boolean): Float {
        var y = 0f
        for (i in 0 until k) y += 6 + if (paired && i % 2 == 1) 6 else 2
        return y * TouchPowerDrag.barScale(rows)
    }

    /** The middle of the drag (up, from 0) that asks for [level], or null if no drag asks for it. */
    private fun dragFor(level: Int, step: Float): Float? {
        val hits = (0..4000).filter { TouchPowerDrag.level(0, -it, step) == level }
        return if (hits.isEmpty()) null else (hits.first() + hits.last()) / 2f
    }

    @Test
    fun upToEightLevelsTheStepIsTheIpads() {
        for (rows in 1..8) for (paired in listOf(false, true)) {
            assertEquals(16f, TouchPowerDrag.step(rows, paired), 0f)
            assertEquals(2f, TouchPowerDrag.barScale(rows), 0f)
        }
        // A level per 16 px: start - dy/16, nothing while |dy| mod 16 is outside 2..13.
        for (dy in -100..100) {
            val rest = abs(dy) % 16
            val expected = if (rest in 2..13) 3 - dy / 16 else null
            assertEquals("dy=$dy", expected, TouchPowerDrag.level(3, dy, 16f))
        }
        assertNull(TouchPowerDrag.level(3, 0, 16f))
        assertEquals(4, TouchPowerDrag.level(3, -18, 16f))
        assertEquals(2, TouchPowerDrag.level(3, 18, 16f))
    }

    @Test
    fun pastEightTheBarsKeepTheHeightOfEight() {
        // Each level's bar and gap: eight of them at the doubled height.
        fun column(rows: Int) = TouchPowerDrag.span(rows, false) + TouchPowerDrag.DESK_BAR_STEP * TouchPowerDrag.barScale(rows)
        for (rows in 8..20) assertEquals("rows=$rows", 128f, column(rows), 0.01f)
        // The shields' pair gaps come on top: 7 of 4 px for 16 levels.
        assertEquals(148f, TouchPowerDrag.span(16, true), 0.01f)
        assertEquals(120f, TouchPowerDrag.span(16, false), 0.01f)
    }

    @Test
    fun pastEightTheFingerStaysOnTheBarItSets() {
        val systems = listOf(16 to true, 14 to false, 13 to false, 11 to false) + (9..20).flatMap { listOf(it to true, it to false) }
        for ((rows, paired) in systems) {
            val step = TouchPowerDrag.step(rows, paired)
            val first = dragFor(1, step)!!
            // Half a drawn bar: the finger is on the bar.
            val onBar = 3f * TouchPowerDrag.barScale(rows)
            for (level in 1..rows) {
                // Up from 0 and back down from the top: the finger moves as far as the bars it passes.
                val up = dragFor(level, step) ?: error("rows=$rows: no drag sets $level")
                val drawn = drawnOffset(level - 1, rows, paired) - drawnOffset(0, rows, paired)
                assertTrue("rows=$rows paired=$paired level=$level: finger ${up - first} vs bar $drawn",
                    abs((up - first) - drawn) <= onBar)
                val down = (0..4000).filter { TouchPowerDrag.level(rows, it, step) == level }
                val downMid = (down.first() + down.last()) / 2f
                val fromTop = drawnOffset(rows - 1, rows, paired) - drawnOffset(level - 1, rows, paired)
                val topMid = (0..4000).filter { TouchPowerDrag.level(rows, it, step) == rows }.let { (it.first() + it.last()) / 2f }
                assertTrue("rows=$rows paired=$paired down to $level: finger ${downMid - topMid} vs bar $fromTop",
                    abs((downMid - topMid) - fromTop) <= onBar)
            }
            // The whole column: the finger ends on the top bar.
            assertEquals(drawnOffset(rows - 1, rows, paired), step * (rows - 1), onBar)
        }
    }

    @Test
    fun sylvanTransportOnThePhone() {
        // 2400x1080 phone: 1080 / 768 = 1.40625 screen px a canvas px.
        val px = 1080f / 768f
        assertEquals(148f / 15 * px, TouchPowerDrag.step(16, true) * px, 0.01f)
        assertEquals(15 * TouchPowerDrag.step(16, true) * px, 148f * px, 0.01f)
        assertEquals(8f * 16 / 14, TouchPowerDrag.step(14, false), 0.001f)
        assertEquals(8f * 16 / 13, TouchPowerDrag.step(13, false), 0.001f)
    }
}
