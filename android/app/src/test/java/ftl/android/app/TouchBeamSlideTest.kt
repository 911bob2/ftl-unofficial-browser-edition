package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.znix.xftl.game.TouchBeamSlide

class TouchBeamSlideTest {
    private fun clamp(delta: Int, a: Int, b: Int) = TouchBeamSlide.clampAxis(delta, a, b, 0, 100)

    @Test
    fun aMoveInsideTheBoundsIsKept() {
        assertEquals(10, clamp(10, 20, 50))
        assertEquals(-20, clamp(-20, 20, 50))
    }

    @Test
    fun theNearEndStopsAtEachEdge() {
        assertEquals(50, clamp(80, 20, 50))
        assertEquals(-20, clamp(-30, 20, 50))
    }

    @Test
    fun theOrderOfTheEndsDoesNotMatter() {
        assertEquals(50, clamp(80, 50, 20))
        assertEquals(-20, clamp(-30, 50, 20))
    }

    @Test
    fun aLineOutsideIsBroughtBackIn() {
        assertEquals(20, clamp(0, -20, 30))
        assertEquals(-30, clamp(0, 90, 130))
    }

    @Test
    fun aLineOnTheEdgeCannotGoFurther() {
        assertEquals(0, clamp(5, 60, 100))
        assertEquals(0, clamp(-5, 0, 40))
    }

    @Test
    fun aLineLongerThanTheBoundsKeepsCoveringThem() {
        assertEquals(10, clamp(50, -10, 120))
        assertEquals(-20, clamp(-50, -10, 120))
        assertEquals(5, clamp(5, -10, 120))
    }

    @Test
    fun aLineAsLongAsTheBoundsCannotMove() {
        assertEquals(0, clamp(30, 0, 100))
        assertEquals(0, clamp(-30, 100, 0))
    }
}
