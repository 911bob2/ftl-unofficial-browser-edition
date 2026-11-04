package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.znix.xftl.game.ShieldSquares

/**
 * The player's shield squares past four layers (ShieldSquares, drawn the same by the desktop and touch HUDs),
 * against the game's own drawing: tools/hs-oracle frames of a 16-bar shield, see docs/HS_UI.md.
 */
class ShieldSquaresTest {
    private fun kinds(charged: Int, layers: Int, hacked: Boolean) =
        ShieldSquares.draws(charged, layers, hacked).joinToString(" ") {
            if (it.image != null) "${it.square}:" + it.image!!.substringAfter("top_shieldsquare1_").removeSuffix(".png")
            else "bar${it.segment}"
        }

    @Test
    fun upToFourLayersEachSquareOnce() {
        assertEquals("0:on 1:on 2:off 3:off", kinds(2, 4, false))
        assertEquals("0:hacked_charged 1:hacked", kinds(1, 2, true))
        assertEquals("", kinds(0, 0, false))
    }

    @Test
    fun layersPastFourDrawOverTheFirstSquares() {
        // eight charged: four charged squares, then the uncharged image over each
        assertEquals("0:on 1:on 2:on 3:on 0:off 1:off 2:off 3:off", kinds(8, 8, false))
        // one of eight charged: the uncharged squares, then the hacked image over all four
        assertEquals("0:on 1:off 2:off 3:off 0:hacked 1:hacked 2:hacked 3:hacked", kinds(1, 8, false))
        // five of eight: the fifth charged layer over square 1, the uncharged rest as hacked over squares 2 to 4
        assertEquals("0:on 1:on 2:on 3:on 0:off 1:hacked 2:hacked 3:hacked", kinds(5, 8, false))
    }

    @Test
    fun hackedLayersPastFour() {
        // hacked and uncharged: the hacked-and-charged image over the squares
        assertEquals("0:hacked 1:hacked 2:hacked 3:hacked 0:hacked_charged 1:hacked_charged", kinds(0, 6, true))
        // hacked and charged: the fifth layer draws nothing, the sixth on are the super-shield bar segments
        assertEquals("0:hacked_charged 1:hacked_charged 2:hacked_charged 3:hacked_charged bar0 bar1 bar2",
            kinds(8, 8, true))
        assertEquals(listOf(0, 19, 38, 56, 75), (0..4).map { ShieldSquares.segmentX(it) })
    }
}
