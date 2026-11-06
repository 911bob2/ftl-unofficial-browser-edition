package ftl.android.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.znix.xftl.game.TouchPointers
import xyz.znix.xftl.game.TouchPointers.Anchor

/**
 * How a script's tutorial arrow and outline aimed at a desktop HUD element are moved to the element's touch place
 * (TouchPointers). The whole path on the emulator, Multiverse's tutorial walked lesson by lesson with every pointer
 * checked against its element, is tools/android-touch/tutorial.mjs.
 */
class TouchPointersTest {
    private fun r(vararg v: Float) = v

    // Three desktop crew boxes (x 10, y 155 + 30i, 86 x 27) moved to a touch list at (70, 80 + 28i).
    private val crew = (0 until 3).map { i ->
        Anchor.fit("crew:$i", r(10f, 155f + 30 * i, 96f, 182f + 30 * i), r(70f, 80f + 28 * i, 150f, 105f + 28 * i))
    }
    private val jump = Anchor.fit("button:jump", r(840f, 85f, 1092f, 140f), r(500f, 10f, 580f, 50f))

    @Test
    fun arrowInsideAnElementPointsAtTheSmallest() {
        val shelf = Anchor.shift("shelf", r(0f, 0f, 1280f, 720f), 0f, 0f)
        val hit = TouchPointers.arrowTarget(listOf(shelf, jump), 900f, 100f, 1f, 0f)
        assertNotNull(hit)
        assertEquals("button:jump", hit!!.first.key)
        assertEquals(0f, hit.second, 0f)
    }

    @Test
    fun arrowShortOfAnElementPointsAtTheFirstOnItsLine() {
        // Tip 30 px left of JUMP, pointing right: it enters JUMP (grown by the slack) 22 px ahead.
        val hit = TouchPointers.arrowTarget(listOf(jump) + crew, 810f, 110f, 1f, 0f)
        assertEquals("button:jump", hit!!.first.key)
        assertEquals(30f - TouchPointers.SLACK, hit.second, 0.01f)
        // Pointing away, or further than the reach, it points at nothing the HUD knows.
        assertNull(TouchPointers.arrowTarget(listOf(jump), 810f, 110f, -1f, 0f))
        assertNull(TouchPointers.arrowTarget(listOf(jump), 840f - TouchPointers.REACH - 20f, 110f, 1f, 0f))
    }

    @Test
    fun arrowKeepsItsDirectionWhenItCoversNothing() {
        val p = TouchPointers.placeArrow(490f, 30f, 0f, 10f, jump.touch(), 60f, 38f, emptyList(), r(-170f, 0f, 1195f, 768f))
        assertArrayEquals(r(490f, 30f, 0f), p, 0f)
    }

    @Test
    fun arrowMovesToTheSideWhereItCoversLeastOfTheTextBoxes() {
        // A text box right of JUMP's left side: from the left the arrow's body (60 long) would lie on it.
        val box = r(420f, 0f, 500f, 60f)
        val p = TouchPointers.placeArrow(490f, 30f, 0f, 10f, jump.touch(), 60f, 38f, listOf(box), r(-170f, 0f, 1195f, 768f))
        // From above leaves the canvas (JUMP is near its top), so from below, pointing up, 10 px under JUMP.
        assertArrayEquals(r(540f, 60f, 270f), p, 0f)
    }

    @Test
    fun outlineOverTheCrewListSpansTheirTouchPlaces() {
        val t = TouchPointers.mapRectForTest(crew + jump, r(8f, 153f, 98f, 244f))
        assertArrayEquals(r(70f - 2 * 80f / 86f, 80f - 2 * 25f / 27f, 150f + 2 * 80f / 86f, 161f + 2 * 25f / 27f), t, 0.01f)
    }

    @Test
    fun outlineOverNothingTheHudKnowsStays() {
        assertNull(TouchPointers.mapRectForTest(crew + jump, r(400f, 300f, 600f, 400f)))
        // A sliver over a large element (under half of either) is not about it.
        assertNull(TouchPointers.mapRectForTest(listOf(jump), r(1000f, 60f, 1200f, 90f)))
    }
}

/** The touch layout's `<name>_TOUCH` lines, then the original's `<name>_IPAD` ones (Translator). */
class TouchTextTest {
    @Test
    fun touchLineWhereTheFilesHaveOne() {
        assertEquals(true, xyz.znix.xftl.Translator.useTouchLine(true, false, false))
        assertEquals(false, xyz.znix.xftl.Translator.useTouchLine(false, false, false))
        // A language's own text for the line comes before an English-only touch line; its own touch line before both.
        assertEquals(false, xyz.znix.xftl.Translator.useTouchLine(true, true, false))
        assertEquals(true, xyz.znix.xftl.Translator.useTouchLine(true, true, true))
        assertEquals(listOf("_TOUCH", "_IPAD"), xyz.znix.xftl.Translator.TOUCH_SUFFIXES)
    }
}

/** Our touch text layers go over the exact mod archives they were written for, and change the merge cache's key. */
class TouchPatchesTest {
    private val mv = ftl.android.app.mods.TouchPatches.all.single().forArchives.single()

    @Test
    fun layersOnlyOverTheirArchives() {
        assertEquals(emptyList<Any>(), ftl.android.app.mods.TouchPatches.forMods(listOf("00".repeat(32))))
        val p = ftl.android.app.mods.TouchPatches.forMods(listOf("11".repeat(32), mv))
        assertEquals(listOf("multiverse-5.5.1"), p.map { it.id })
        assertEquals("multiverse-5.5.1@3", ftl.android.app.mods.TouchPatches.key(p))
        assertEquals("", ftl.android.app.mods.TouchPatches.key(emptyList()))
    }
}
