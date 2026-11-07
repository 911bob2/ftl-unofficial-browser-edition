package ftl.android.app

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import xyz.znix.xftl.Constants
import xyz.znix.xftl.game.hullBarColour
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig

/**
 * The touch status bar's hull colour is the desktop bar's: ShipStatus::RenderHealth's point steps (above 19 green,
 * above 9 yellow), and under Hyperspace its playerHpColorFix by the hull fraction (HullNumbers.cpp: above 0.65 green,
 * above 0.32 yellow). Before, the touch bar split the maximum in thirds, which differs from both on hulls other than 30.
 */
class TouchStatusTest {
    private var wasActive = false
    private var wasConfig: HsConfig? = null

    @Before
    fun save() {
        wasActive = HS.active; wasConfig = HS.config
    }

    @After
    fun restore() {
        HS.active = wasActive; HS.config = wasConfig
    }

    @Test
    fun vanillaUsesThePointSteps() {
        HS.active = false
        for (hull in 0..30) {
            val want = when {
                hull > 19 -> Constants.SHIP_HEALTH_HIGH
                hull > 9 -> Constants.SHIP_HEALTH_MED
                else -> Constants.SHIP_HEALTH_LOW
            }
            assertSame("hull $hull", want, hullBarColour(hull, 30))
        }
    }

    @Test
    fun hyperspaceUsesTheFraction() {
        HS.active = true
        HS.config = HsConfig.defaults()
        for (max in listOf(25, 30, 40, 60)) for (hull in 0..max) {
            val r = hull.toFloat() / max
            val want = when {
                r > 0.65f -> Constants.SHIP_HEALTH_HIGH
                r > 0.32f -> Constants.SHIP_HEALTH_MED
                else -> Constants.SHIP_HEALTH_LOW
            }
            assertSame("hull $hull of $max", want, hullBarColour(hull, max))
        }
        // a value the old thirds coloured differently: 16 of 25 is 0.64, yellow (the thirds gave green)
        assertSame(Constants.SHIP_HEALTH_MED, hullBarColour(16, 25))
    }
}
