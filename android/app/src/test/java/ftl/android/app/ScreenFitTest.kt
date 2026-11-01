package ftl.android.app

import ftl.android.app.game.ScreenFit
import ftl.android.app.game.ScreenFit.Mode
import ftl.android.app.game.ScreenFit.Ratio
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFitTest {
    private fun custom(r: String) = ScreenFit(Mode.CUSTOM, Ratio(r))

    @Test fun defaultPicksTheOriginalWithTheLargerPicture() {
        assertEquals("16:9", ScreenFit.original(2400, 1080).label)
        assertEquals("16:9", ScreenFit.original(1920, 1080).label)
        assertEquals("16:9", ScreenFit.original(1920, 1200).label)
        assertEquals("4:3", ScreenFit.original(1800, 1200).label)
        assertEquals("4:3", ScreenFit.original(1600, 1200).label)
    }

    @Test fun defaultBordersAndCanvas() {
        val fit = ScreenFit()
        assertEquals(1365, fit.canvasWidth(2400, 1080))
        assertEquals(1920 to 1080, fit.viewSize(2400, 1080))
        assertEquals(1024, fit.canvasWidth(1600, 1200))
        assertEquals(1600 to 1200, fit.viewSize(1600, 1200))
        assertEquals(1920 to 1080, fit.viewSize(1920, 1200))
    }

    @Test fun theViewOfDefaultGivesTheSameCanvas() {
        for ((w, h) in listOf(2400 to 1080, 1600 to 1200, 1920 to 1200, 1800 to 1200)) {
            val fit = ScreenFit()
            val (vw, vh) = fit.viewSize(w, h)
            assertEquals(fit.canvasWidth(w, h), fit.canvasWidth(vw, vh))
        }
    }

    @Test fun stretchFillsWithTheDefaultCanvas() {
        val fit = ScreenFit(Mode.STRETCH)
        assertEquals(2400 to 1080, fit.viewSize(2400, 1080))
        assertEquals(1365, fit.canvasWidth(2400, 1080))
    }

    @Test fun customUsesItsShape() {
        assertEquals(1707, custom("20:9").canvasWidth(2400, 1080))
        assertEquals(2400 to 1080, custom("20:9").viewSize(2400, 1080))
        assertEquals(2160 to 1080, custom("18:9").viewSize(2400, 1080))
        assertEquals(1600 to 1200, custom("4:3").viewSize(1600, 1200))
        assertEquals(1600 to 686, custom("21:9").viewSize(1600, 1200))
        assertEquals(1024, custom("1:1").canvasWidth(1600, 1200))
    }

    @Test fun sharpDoublesOnlyWhereTheFrameIsScaledUp() {
        assertEquals(2, ScreenFit().renderScale(1080))
        assertEquals(1, ScreenFit().renderScale(720))
        assertEquals(1, ScreenFit(sharp = false).renderScale(1080))
    }

    @Test fun offeredRatiosFitTheDevice() {
        assertEquals(listOf("16:9", "18:9", "19.5:9", "20:9"), ScreenFit.customRatios(2400, 1080).map { it.label })
        assertEquals(listOf("16:9", "18:9", "19.5:9", "20:9", "21:9"), ScreenFit.customRatios(2520, 1080).map { it.label })
        assertEquals(listOf("16:9"), ScreenFit.customRatios(1920, 1080).map { it.label })
        assertEquals(listOf("4:3"), ScreenFit.customRatios(1600, 1200).map { it.label })
        assertEquals(listOf("4:3", "3:2", "16:10"), ScreenFit.customRatios(1920, 1200).map { it.label })
        assertEquals(listOf("4:3"), ScreenFit.customRatios(2200, 2000).map { it.label })
        assertEquals(listOf("16:9", "18:9", "19.00:9"), ScreenFit.customRatios(2280, 1080).map { it.label })
    }
}
