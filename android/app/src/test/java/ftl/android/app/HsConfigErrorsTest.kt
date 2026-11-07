package ftl.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.znix.xftl.hs.HsConfig

/**
 * hyperspace.xml errors as Hyperspace's Resources.cpp has them (docs/HYPERSPACE.md, "Errors"; checked against the original
 * with the oracle's ps-m2b sessions): a number boost::lexical_cast cannot read ends its pass with HS's box and the rest of
 * that pass is lost; a newer `<version>` is the warning box. The boxes on screen are HsErrorBox (tools/android-touch).
 */
class HsConfigErrorsTest {
    /** android.util.Xml (BrowserXml) is not in JVM tests: the JDK's SAX parser builds the same JDOM tree. */
    private fun xml(text: String) = org.jdom2.input.SAXBuilder().build(java.io.StringReader(text)).rootElement
    private fun parse(body: String) = HsConfig.parse(xml("<FTL>$body</FTL>"))
    private val cast = HsConfig.BAD_CAST

    @Test
    fun aBadNumberEndsEachPass() {
        val c = parse("<version>^1.23.2</version><scripts><script>a.lua</script></scripts><purchaseLimitNumber fontSize=\"0\" x=\"-16\" y=\"106\"/>" +
            "<events><eventFile>x</eventFile></events><defaults><checkCargo>true</checkCargo><erosion><speed>2</speed><growBreachMultiplier>x</growBreachMultiplier></erosion></defaults>" +
            "<defaults><choiceRequiresCrew>true</choiceRequiresCrew></defaults><version>^1.99.0</version>" +
            "<purchaseLimitNumber fontSize=\"abc\" x=\"1\" y=\"2\"/><scripts><script>b.lua</script></scripts><forceDlc enabled=\"true\"/>")
        assertEquals(listOf("Failed parsing hyperspace.xml\n$cast", "Error parsing hyperspace.xml\n$cast"), c.errorBoxes)
        assertEquals(4, c.earlyStop)
        assertEquals(7, c.mainStop)
        assertEquals(listOf("a.lua"), c.scripts)
        assertFalse(c.forceDlc)
        assertTrue(c.defaultCheckCargo)
        assertFalse(c.defaultChoiceRequiresCrew)
        assertEquals(1f, c.erosionGrowBreachMultiplier)
        assertTrue(c.eventFiles.isEmpty())
        assertEquals(106, c.purchaseLimitY)
        assertTrue(c.problems.toString(), c.problems.any { "'x'" in it } && c.problems.any { "'abc'" in it })
        val defaults = c.nodesOf("defaults")
        assertEquals(1, defaults.size)
        assertEquals(listOf("checkCargo", "erosion"), defaults[0].children.map { it.name })
        assertEquals(listOf("speed"), defaults[0].getChild("erosion").children.map { it.name })
    }

    @Test
    fun passRootsStopWhereThePassStopped() {
        val root = xml("<FTL><events><a/></events><defaults><erosion><time>y</time></erosion></defaults><events><b/></events></FTL>")
        val c = HsConfig.parse(root)
        assertEquals(listOf("events", "defaults"), c.passRoot(root, true)!!.children.map { it.name })
        assertTrue(c.passRoot(root, true)!!.getChild("defaults").getChild("erosion").children.isEmpty())
        assertSame(root, c.passRoot(root, false))
    }

    @Test
    fun attributesAreReadInTurn() {
        val c = parse("<version>^1.23.2</version><purchaseLimitNumber fontSize=\"3\" x=\"5\" y=\"q\"/>")
        assertEquals(listOf(3, 5, 60), listOf(c.purchaseLimitFontSize, c.purchaseLimitX, c.purchaseLimitY))
        assertEquals(1, parse("<version>^1.23.2</version><showDummyEquipmentSlots enabled=\"true\" opacity=\" 0.5\"/>").errorBoxes.size)
    }

    @Test
    fun versionBoxes() {
        assertEquals(listOf(HsConfig.WRONG_VERSION), parse("<version>^1.99.0</version>").errorBoxes)
        assertEquals(listOf(HsConfig.WRONG_VERSION), parse("").errorBoxes)
        assertTrue(parse("<version>^1.23.0</version><version>=1.23.2</version>").errorBoxes.isEmpty())
        assertTrue(parse("<version>332</version>").errorBoxes.isEmpty())
        assertEquals(listOf("Failed parsing hyperspace.xml\n$cast"), parse("<version>3x2</version>").errorBoxes)
        val syntax = parse("<version>1.23</version><defaults><checkCargo>true</checkCargo></defaults>")
        assertEquals(listOf(HsConfig.VERSION_SYNTAX), syntax.errorBoxes)
        assertEquals(0, syntax.earlyStop)
        assertFalse(syntax.defaultCheckCargo)
    }
}
