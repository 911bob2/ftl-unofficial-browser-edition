package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.game.touchCooldownMaskTop

/**
 * The touch system box's timer pieces (parity sweep B4, a player's report on 2.1.0):
 * - the cooldown bar (cloak, battery, hacking, mind control...) shows whole segments of the iPad's mask
 *   (`ipad/systemUI/bar_<n>_mask.png`, 20n + 21 rows high, five segments of three rows a level, the lowest ending at
 *   row 20n + 9): the mask from row 20n + 10 - 4k down, k whole segments. It used to stop five rows above the picture's
 *   bottom and start six rows low, cutting segments.
 * - the lock ring's segments, the same for the desktop and touch boxes.
 */
class TouchLockCooldownTest {
    private fun segmentRow(level: Int, row: Int): Boolean {
        // Segment j (1 from the bottom) covers rows 20n + 11 - 4j .. 20n + 13 - 4j.
        for (j in 1..5 * level) if (row in 20 * level + 11 - 4 * j..20 * level + 13 - 4 * j) return true
        return false
    }

    @Test
    fun cooldownShowsWholeSegments() {
        for (n in 1..4) {
            val height = 20 * n + 21
            val segments = 5 * n
            assertEquals(-1, touchCooldownMaskTop(n, 0f, height))
            for (k in 1..segments) {
                val fraction = (k + 0.5f) / segments
                val top = touchCooldownMaskTop(n, fraction.coerceAtMost(1f), height)
                val shown = (top until height).count { segmentRow(n, it) }
                assertEquals("level $n, $k segments", 3 * minOf(k, segments), shown)
                assertEquals("level $n, $k: the first row shown is a gap, not a cut segment", false, segmentRow(n, top))
            }
        }
    }

    @Test
    fun lockRingSegments() {
        assertEquals(12, AbstractSystem.lockRingSegments(5f))
        assertEquals(12, AbstractSystem.lockRingSegments(10f))
        assertEquals(12, AbstractSystem.lockRingSegments(4.9f))
        assertEquals(6, AbstractSystem.lockRingSegments(2.5f))
        assertEquals(1, AbstractSystem.lockRingSegments(0.3f))
    }
}
