package ftl.android.app

import ftl.android.app.game.Zoom
import ftl.android.app.game.Zoom.Companion.CANCEL
import ftl.android.app.game.Zoom.Companion.DOWN
import ftl.android.app.game.Zoom.Companion.MOVE
import ftl.android.app.game.Zoom.Companion.UP
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Pinch to zoom (Zoom): which fingers reach the game and when, the zoom a pinch sets, the point a finger on the
 * zoomed picture stands for, what GameRenderer copies, and the minimap. On the emulator: tools/android-touch/zoom.mjs.
 */
class ZoomTest {
    // A 2400x1080 phone with the picture filling it; one unit is 10.8 px, so the slop is 27 px.
    private val w = 2400
    private val h = 1080

    private class Got(val action: Int, val id: Int, val x: Float, val y: Float, val time: Long) {
        override fun toString() = "$action/$id ($x,$y) @$time"
    }

    private val got = ArrayList<Got>()
    private val sink = Zoom.Sink { a, id, x, y, t -> got += Got(a, id, x, y, t) }

    private fun zoom(settings: Zoom.Settings = Zoom.Settings(), placed: (Float, Float) -> Unit = { _, _ -> }) =
        Zoom(settings, placed).also { it.resize(w, h) }

    private fun Zoom.at(action: Int, x: Float, y: Float, t: Long, id: Int = 0) = touch(action, id, x, y, t, sink)

    /** A pinch of fingers 1 and 2 from [d0] apart to [d1] apart around ([cx], [cy]), then lifted; times from [t]. */
    private fun Zoom.pinch(cx: Float, cy: Float, d0: Float, d1: Float, t: Long = 1000) {
        at(DOWN, cx - d0 / 2, cy, t, 1)
        at(DOWN, cx + d0 / 2, cy, t + 20, 2)
        for (i in 1..10) {
            val d = d0 + (d1 - d0) * i / 10
            at(MOVE, cx - d / 2, cy, t + 20 + i * 16L, 1)
            at(MOVE, cx + d / 2, cy, t + 20 + i * 16L, 2)
        }
        at(UP, cx - d1 / 2, cy, t + 400, 1)
        at(UP, cx + d1 / 2, cy, t + 400, 2)
    }

    @Test
    fun offByDefaultAndThenThereIsNone() {
        assertFalse(Zoom.DEFAULT_ON)
        assertNull(Zoom.forGame(false))
        assertNotNull(Zoom.forGame(true))
        val z = zoom()
        assertEquals(1f, z.scale)
        assertFalse(z.zoomed)
        assertNull(z.minimap())
        // At 1x a point is itself.
        assertEquals(1234f, z.unzoomX(1234f))
        assertEquals(567f, z.unzoomY(567f))
    }

    @Test
    fun aQuickTapReachesTheGameWhenItLiftsWithItsOwnPlacesAndTimes() {
        val z = zoom()
        z.at(DOWN, 500f, 400f, 1000)
        z.at(MOVE, 505f, 402f, 1030)
        z.tick(1100, sink)
        assertTrue("nothing before the lift: a second finger could still make it a pinch", got.isEmpty())
        z.at(UP, 505f, 402f, 1120)
        assertEquals(listOf("1/0 (500.0,400.0) @1000", "2/0 (505.0,402.0) @1030", "3/0 (505.0,402.0) @1120"), got.map { it.toString() })
    }

    @Test
    fun aHeldFingerReachesTheGameAfterTheHoldAndThenAtOnce() {
        val z = zoom()
        z.at(DOWN, 500f, 400f, 1000)
        z.tick(1000 + Zoom.HOLD_MS - 1, sink)
        assertTrue(got.isEmpty())
        z.tick(1000 + Zoom.HOLD_MS, sink)
        assertEquals(1, got.size)
        assertEquals(DOWN, got[0].action)
        assertEquals(1000L, got[0].time)
        z.at(MOVE, 501f, 400f, 1200)
        z.at(UP, 501f, 400f, 1300)
        assertEquals(listOf(DOWN, MOVE, UP), got.map { it.action })
    }

    @Test
    fun aDragReachesTheGameAtOnceWithItsWholePath() {
        val z = zoom()
        z.at(DOWN, 500f, 400f, 1000)
        z.at(MOVE, 510f, 400f, 1010)
        assertTrue(got.isEmpty())
        z.at(MOVE, 530f, 400f, 1020) // 30 px > 27 px
        assertEquals(listOf(500f, 510f, 530f), got.map { it.x })
        // A later finger is the game's too (the beam's second finger slides the line).
        z.at(DOWN, 1500f, 800f, 1300, id = 1)
        z.at(MOVE, 560f, 400f, 1310)
        z.at(UP, 1500f, 800f, 1400, id = 1)
        z.at(UP, 560f, 400f, 1500)
        assertEquals(listOf(DOWN, MOVE, MOVE, DOWN, MOVE, UP, UP), got.map { it.action })
        assertEquals(1f, z.scale)
    }

    @Test
    fun aSecondFingerWhileTheFirstIsHeldIsAPinchAndTheGameGetsNeither() {
        val z = zoom()
        z.pinch(1200f, 540f, 400f, 800f)
        assertTrue("$got", got.isEmpty())
        assertEquals(2f, z.scale, 0.001f)
        // The point between the fingers stayed under them.
        assertEquals(1200f, z.unzoomX(1200f), 0.5f)
        assertEquals(540f, z.unzoomY(540f), 0.5f)
        assertNotNull(z.minimap())
    }

    @Test
    fun aPinchMovesThePictureWithTheFingersAndKeepsItOnTheScreen() {
        val z = zoom()
        z.pinch(1200f, 540f, 200f, 600f)
        assertEquals(3f, z.scale, 0.001f)
        // Moving both fingers left by 300 px moves the picture with them.
        z.at(DOWN, 1000f, 540f, 3000, 1)
        z.at(DOWN, 1400f, 540f, 3010, 2)
        val before = z.unzoomX(1200f)
        for (i in 1..10) {
            z.at(MOVE, 1000f - i * 30, 540f, 3010 + i * 16L, 1)
            z.at(MOVE, 1400f - i * 30, 540f, 3010 + i * 16L, 2)
        }
        assertEquals(before, z.unzoomX(900f), 0.5f)
        z.at(UP, 700f, 540f, 3400, 1)
        z.at(UP, 1100f, 540f, 3400, 2)
        // Far to the right, then far to the left: the picture's edge stops at the view's, the zoom stays.
        for ((dx, edge) in listOf(300f to 0f, -600f to w - 3f * w)) {
            z.at(DOWN, 1000f, 540f, 4000, 1)
            z.at(DOWN, 1400f, 540f, 4010, 2)
            for (i in 1..10) {
                z.at(MOVE, 1000f + i * dx, 540f, 4010 + i * 16L, 1)
                z.at(MOVE, 1400f + i * dx, 540f, 4010 + i * 16L, 2)
            }
            assertEquals(edge, z.panX, 0.01f)
            assertEquals(3f, z.scale, 0.001f)
            z.at(UP, 0f, 540f, 4400, 1)
            z.at(UP, 0f, 540f, 4400, 2)
        }
        assertTrue(got.isEmpty())
    }

    @Test
    fun zoomStaysBetweenOneAndTheLimitAndAPinchEndingNearOneIsOne() {
        val z = zoom()
        z.pinch(1200f, 540f, 100f, 2000f)
        assertEquals(Zoom.MAX, z.scale, 0.001f)
        z.pinch(1200f, 540f, 2000f, 100f, t = 5000)
        assertEquals(1f, z.scale)
        z.pinch(1200f, 540f, 1000f, 1080f, t = 9000)
        assertEquals("1.08 snaps back", 1f, z.scale)
        assertEquals(0f, z.panX)
        assertTrue(got.isEmpty())
    }

    @Test
    fun aTwoFingerTapZoomsInThereAndAnotherGoesBackToOne() {
        val z = zoom()
        z.at(DOWN, 600f, 300f, 1000, 1)
        z.at(DOWN, 700f, 300f, 1050, 2)
        z.at(UP, 600f, 300f, 1150, 1)
        z.at(UP, 700f, 300f, 1160, 2)
        assertEquals(Zoom.TAP_ZOOM, z.scale)
        assertEquals(650f, z.unzoomX(650f), 0.5f)
        z.at(DOWN, 1600f, 800f, 2000, 1)
        z.at(DOWN, 1700f, 800f, 2050, 2)
        z.at(UP, 1600f, 800f, 2150, 1)
        z.at(UP, 1700f, 800f, 2160, 2)
        assertEquals(1f, z.scale)
        // Held too long it is no tap.
        z.at(DOWN, 600f, 300f, 3000, 1)
        z.at(DOWN, 700f, 300f, 3050, 2)
        z.at(UP, 600f, 300f, 3000 + Zoom.TAP_MS + 1, 1)
        z.at(UP, 700f, 300f, 3000 + Zoom.TAP_MS + 1, 2)
        assertEquals(1f, z.scale)
        assertTrue(got.isEmpty())
    }

    @Test
    fun zoomedAFingerStandsForThePointUnderIt() {
        val z = zoom()
        z.set(2f, 1800f, 270f, 1200f, 540f) // the point (1800, 270) of the 1x picture in the view's middle
        assertEquals(1800f, z.unzoomX(1200f), 0.01f)
        assertEquals(270f, z.unzoomY(540f), 0.01f)
        // A tap there reaches the game in view pixels; GameRenderer.gameX/gameY take them through unzoomX/unzoomY.
        z.at(DOWN, 1200f, 540f, 1000)
        z.at(UP, 1200f, 540f, 1050)
        assertEquals(listOf(1200f, 1200f), got.map { it.x })
        // And the point drawn there is that point: the copy maps frame pixel (1800, 270) x 2 onto the view's middle.
        val r = z.blit(1365, 768, 2, 0f, 0f, w / 1365f, h / 768f)
        val fx = 1800f / (w / 1365f) * 2
        val viewX = r[4] + (fx - r[0]) * (r[6] - r[4]) / (r[2] - r[0])
        assertEquals(1200f, viewX, 2f)
    }

    @Test
    fun theCopyIsTheWholeFrameAtOneAndThePartInViewZoomed() {
        val z = zoom()
        fun near(want: IntArray, got: IntArray) {
            for (i in want.indices) assertEquals("$i of ${got.toList()}", want[i].toFloat(), got[i].toFloat(), 2f)
        }
        assertArrayEquals(intArrayOf(0, 0, 2730, 1536, 0, 0, 2400, 1080), z.blit(1365, 768, 2, 0f, 0f, w / 1365f, h / 768f))
        z.set(2f, 0f, 0f, 0f, 0f) // the top left quarter
        // Source: x 0..1365, rows 768..1536 from the bottom (the top half); destination: the whole view (a pixel's rounding).
        near(intArrayOf(0, 768, 1365, 1536, 0, 0, 2400, 1080), z.blit(1365, 768, 2, 0f, 0f, w / 1365f, h / 768f))
        // The 1280x720 menu frame with bars on a 4:3 view, zoomed on its middle: the middle of the frame fills the view.
        val z2 = Zoom().also { it.resize(1600, 1200) }
        z2.set(2f, 800f, 600f, 800f, 600f)
        near(intArrayOf(320, 120, 960, 600, 0, 0, 1600, 1200), z2.blit(1280, 720, 1, 0f, 150f, 1.25f, 1.25f))
    }

    @Test
    fun theMinimapShowsWhileZoomedAndItsFingersNeverReachTheGame() {
        var placed: Pair<Float, Float>? = null
        val z = zoom { x, y -> placed = x to y }
        z.set(2f, 600f, 270f, 1200f, 540f)
        val m = z.minimap()!!
        // Top right by default, the medium size: 30 units tall, the view's shape.
        assertEquals(324f, m.map.h, 0.5f)
        assertEquals(720f, m.map.w, 0.5f)
        assertEquals(w - 21.6f - 720f, m.box.x, 0.5f)
        assertEquals(21.6f, m.box.y, 0.5f)
        // The framed part is the view's half on each side.
        assertEquals(m.map.w / 2, m.seen.w, 0.5f)
        // A tap on the map's centre puts the view's centre on the picture's centre.
        val cx = m.map.x + m.map.w / 2
        val cy = m.map.y + m.map.h / 2
        z.at(DOWN, cx, cy, 1000)
        z.at(UP, cx, cy, 1050)
        assertEquals(1200f, z.unzoomX(1200f), 0.5f)
        assertEquals(540f, z.unzoomY(540f), 0.5f)
        // Dragging the bar moves the box and keeps its place.
        val bx = m.bar.x + 30
        val by = m.bar.y + m.bar.h / 2
        z.at(DOWN, bx, by, 2000)
        for (i in 1..10) z.at(MOVE, bx - i * 100f, by + i * 40f, 2000 + i * 16L)
        z.at(UP, bx - 1000f, by + 400f, 2300)
        val moved = z.minimap()!!
        assertEquals(m.box.x - 1000f, moved.box.x, 0.5f)
        assertEquals(m.box.y + 400f, moved.box.y, 0.5f)
        assertNotNull(placed)
        // The 1x button goes back to 1x; at 1x there is no minimap and that place is the game's again.
        val rx = moved.reset.x + moved.reset.w / 2
        val ry = moved.reset.y + moved.reset.h / 2
        z.at(DOWN, rx, ry, 3000)
        z.at(UP, rx, ry, 3050)
        assertEquals(1f, z.scale)
        assertNull(z.minimap())
        assertTrue("$got", got.isEmpty())
        z.at(DOWN, rx, ry, 4000)
        z.at(UP, rx, ry, 4050)
        assertEquals(listOf(DOWN, UP), got.map { it.action })
    }

    @Test
    fun withTheMinimapSwitchedOffItsPlaceIsTheGamesWhileZoomed() {
        val z = zoom(Zoom.Settings(minimap = false))
        z.set(2f, 600f, 270f, 1200f, 540f)
        assertNull(z.minimap())
        z.at(DOWN, 2200f, 100f, 1000)
        z.at(UP, 2200f, 100f, 1050)
        assertEquals(2, got.size)
    }

    @Test
    fun theSizesAndThePlaceSetTheBox() {
        for (size in Zoom.Size.entries) {
            val z = zoom(Zoom.Settings(size = size, placeX = 0f, placeY = 1f))
            z.set(2f, 0f, 0f, 0f, 0f)
            val m = z.minimap()!!
            assertEquals(h * size.share / 100, m.map.h, 0.5f)
            assertEquals(21.6f, m.box.x, 0.5f)
            assertEquals(h - 21.6f, m.box.y + m.box.h, 0.5f)
        }
        assertEquals("Medium, top right", Zoom.describe(Zoom.Settings()))
        assertEquals("Small, bottom left", Zoom.describe(Zoom.Settings(size = Zoom.Size.SMALL, placeX = 0f, placeY = 1f)))
        assertEquals("Off", Zoom.describe(Zoom.Settings(minimap = false)))
    }

    @Test
    fun cancelledFingersEndForTheGameOnlyWhereItHasThem() {
        val z = zoom()
        z.at(DOWN, 500f, 400f, 1000)
        z.cancelAll(1050, sink)
        assertTrue("held back: the game never had it", got.isEmpty())
        z.at(DOWN, 500f, 400f, 2000)
        z.tick(2200, sink)
        z.cancelAll(2300, sink)
        assertEquals(listOf(DOWN, CANCEL), got.map { it.action })
        // A system cancel of a finger still held back is nothing to the game, and the next finger starts afresh.
        got.clear()
        z.at(DOWN, 500f, 400f, 3000)
        z.at(CANCEL, 500f, 400f, 3050)
        z.at(DOWN, 600f, 400f, 3100)
        z.at(UP, 600f, 400f, 3150)
        assertEquals(listOf(DOWN, UP), got.map { it.action })
        assertEquals(600f, got[0].x)
    }

    @Test
    fun theRendererMapsAFingerAsBeforeWhenOffOrAtOneAndThroughTheZoomWhenZoomed() {
        fun renderer(z: Zoom?) = ftl.android.app.game.GameRenderer(ftl.android.app.game.ScreenFit(), h, {}, {}, {}, { _, _ -> }, {}, zoom = z).apply {
            frameLeft = 0f; frameTop = 0f; frameScaleX = w / 1365f; frameScaleY = h / 768f
        }
        val off = renderer(null)
        val one = renderer(zoom())
        for (x in 0..w step 7) assertEquals(((x / (w / 1365f))).toInt(), off.gameX(x.toFloat()))
        for (x in 0..w step 7) assertEquals(off.gameX(x.toFloat()), one.gameX(x.toFloat()))
        for (y in 0..h step 5) assertEquals(off.gameY(y.toFloat()), one.gameY(y.toFloat()))
        // Zoomed 2x on the canvas point (1000, 500): the view's middle is that point.
        val z = zoom()
        val two = renderer(z)
        z.set(2f, 1000f * w / 1365f, 500f * h / 768f, w / 2f, h / 2f)
        assertEquals(1000f, two.gameX(w / 2f).toFloat(), 1f)
        assertEquals(500f, two.gameY(h / 2f).toFloat(), 1f)
        // One view pixel is half a canvas pixel's distance there.
        assertEquals(1050f, two.gameX(w / 2f + 100 * w / 1365f).toFloat(), 1f)
    }

    @Test
    fun aResizeKeepsTheZoomedPointInTheMiddle() {
        val z = zoom()
        z.set(2f, 1800f, 540f, 1200f, 540f)
        z.resize(1200, 540)
        assertEquals(900f, z.unzoomX(600f), 1f)
        assertEquals(2f, z.scale)
    }
}
