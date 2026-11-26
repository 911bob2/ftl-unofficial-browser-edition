package ftl.android.app

import org.jdom2.input.SAXBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.znix.xftl.Animations
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.Texture
import java.io.StringReader

/**
 * A strip's frames on its sheet, as the original finds them (player crash log, 2.1.1 Android: "Failed requirement" from
 * SpriteSheetSpec.getSprite drawing Multiverse's Vampweed Foreman in the debug console's crew list; the same strip is
 * the drone walking down in a run).
 * - ImportAnimations (1.6.9 0x589de0) keeps the sheet's XML w and h and sets stripStartY = desc y * fh;
 *   Animation::InitPrimitives (0x59c5f0) samples v = 1 - (stripStartY + fh) / h to 1 - stripStartY / h. Rows count up
 *   from the declared bottom edge.
 * - drone_vamp_foreman declares h 242 for 35 px frames (6 whole rows and 32 px): its walk_down strip, desc y 6, starts
 *   3 px above the sheet and the other strips sit 32 px lower than whole rows from the top would put them.
 * The engine code is shared with the browser build.
 */
class AnimationSheetTest {
    private fun animations(sheetH: Int) = Animations(listOf(SAXBuilder().build(StringReader("""
        <FTL>
        <animSheet name="foreman" w="175" h="$sheetH" fw="35" fh="35">people/foreman.png</animSheet>
        <anim name="foreman_walk_down"><sheet>foreman</sheet><desc length="4" x="0" y="6"/><time>1.0</time></anim>
        <anim name="foreman_walk_right"><sheet>foreman</sheet><desc length="4" x="0" y="5"/><time>1.0</time></anim>
        <anim name="foreman_shoot_down"><sheet>foreman</sheet><desc length="1" x="0" y="0"/><time>1.0</time></anim>
        </FTL>
    """.trimIndent()))))

    private val image = Image(0, 0, 175, 245, Texture(256, 256, 175, 245, 0))

    private fun frame(a: Animations, anim: String, i: Int): List<Int> {
        val spec = a.animations.getValue(anim)
        val img = spec.sheet.getSprite(image, spec.x + i, spec.y)
        return listOf(img.textureOffsetX, img.textureOffsetY, img.width, img.height)
    }

    @Test
    fun aSheetThatIsNotWholeFramesCountsItsRowsFromItsDeclaredBottom() {
        val a = animations(242)
        // desc y 6: from 242 - 7 * 35 = -3 to 32; the 32 px inside the sheet are drawn
        assertEquals(listOf(0, 0, 35, 32), frame(a, "foreman_walk_down", 0))
        assertEquals(listOf(105, 0, 35, 32), frame(a, "foreman_walk_down", 3))
        // desc y 5: 242 - 6 * 35 = 32; desc y 0: 242 - 35 = 207
        assertEquals(listOf(0, 32, 35, 35), frame(a, "foreman_walk_right", 0))
        assertEquals(listOf(0, 207, 35, 35), frame(a, "foreman_shoot_down", 0))
    }

    @Test
    fun aSheetOfWholeRowsIsUnchanged() {
        val a = animations(245)
        assertEquals(listOf(0, 0, 35, 35), frame(a, "foreman_walk_down", 0))
        assertEquals(listOf(0, 35, 35, 35), frame(a, "foreman_walk_right", 0))
        assertEquals(listOf(0, 210, 35, 35), frame(a, "foreman_shoot_down", 0))
    }
}
