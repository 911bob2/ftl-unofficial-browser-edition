package ftl.android.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.znix.xftl.game.TouchHold
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.render.HsMoreInfo
import xyz.znix.xftl.hs.render.HsOverlay

/**
 * Hyperspace's More Info mode (MoreInfoButton.cpp) and the touch layout's hold-to-show crew tooltip: the options follow
 * the mode only once CommandGui::OnLoop applied it, and a finger counts as held after resting still.
 */
class MoreInfoTest {
    private var wasActive = false
    private var wasConfig: HsConfig? = null

    @Before
    fun hyperspace() {
        wasActive = HS.active; wasConfig = HS.config
        HS.active = true
        HS.config = HsConfig.defaults() // Hyperspace's own defaults: every option off
        HsMoreInfo.reset()
    }

    @After
    fun restore() {
        HS.active = wasActive; HS.config = wasConfig
        HsMoreInfo.reset()
    }

    @Test
    fun aNewStartHasTheModeOnButTheDefaultsUntilTheFirstFlightFrame() {
        assertTrue(HsOverlay.altMode)
        assertFalse(HsMoreInfo.applied)
        assertFalse(HsMoreInfo.showReactor())
        assertFalse(HsMoreInfo.redesignedWeaponTooltips())
        HsMoreInfo.onLoop()
        assertTrue(HsMoreInfo.applied)
        assertTrue(HsMoreInfo.showReactor())
        assertTrue(HsMoreInfo.showAllyPowers())
        assertTrue(HsMoreInfo.showEnemyPowers())
        assertTrue(HsMoreInfo.showWeaponCooldown())
        assertTrue(HsMoreInfo.redesignedWeaponTooltips())
        assertTrue(HsMoreInfo.redesignedDroneTooltips())
        assertTrue(HsMoreInfo.redesignedAugmentTooltips())
    }

    @Test
    fun aToggleReachesTheOptionsAtTheNextLoopOnly() {
        HsMoreInfo.onLoop()
        HsOverlay.altMode = false; HsOverlay.altModeChanged = true
        assertTrue(HsMoreInfo.showAllyPowers())
        HsMoreInfo.onLoop()
        assertFalse(HsMoreInfo.applied)
        assertFalse(HsMoreInfo.showAllyPowers())
        assertFalse(HsMoreInfo.redesignedWeaponTooltips())
        // A loop without a change keeps it.
        HsMoreInfo.onLoop()
        assertFalse(HsMoreInfo.applied)
    }

    @Test
    fun theRoundingIsTwoUnderTheModeWhenTheDefaultIsZeroOrOne() {
        // HsConfig's default rounding (hyperspace.xml's attribute when absent)
        val d = HS.cfg().advancedCrewTooltipRounding
        assertEquals(d, HsMoreInfo.advancedCrewTooltipRounding())
        HsMoreInfo.onLoop()
        assertEquals(if (d == 0 || d == 1) 2 else d, HsMoreInfo.advancedCrewTooltipRounding())
    }

    @Test
    fun aFingerIsHeldAfterResting() {
        val h = TouchHold(0.5f, 8.0)
        assertFalse(h.held(0))
        h.down(100, 100, 0)
        assertFalse(h.held(400_000_000))
        assertTrue(h.held(500_000_000))
        // jitter inside the slop keeps the rest
        h.move(105, 103, 600_000_000)
        assertTrue(h.held(600_000_000))
        assertEquals(105, h.x)
        // a slide starts the rest again where it is
        h.move(140, 100, 700_000_000)
        assertFalse(h.held(1_100_000_000))
        assertTrue(h.held(1_200_000_000))
        h.up()
        assertFalse(h.held(2_000_000_000))
        assertFalse(h.down)
    }
}
