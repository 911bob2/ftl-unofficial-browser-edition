package ftl.android.app

import ftl.android.app.mods.HyperspaceRule
import ftl.android.app.mods.HyperspaceRule.Mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hyperspace mod without Hyperspace": the same cases as the website's (tests/mods-merge.mjs, web/mods/hs-needed.mjs). */
class HyperspaceRuleTest {
    private val hsSha = "a".repeat(64)
    private val shas = setOf(hsSha)
    private val hs = Mod(hsSha, "Hyperspace", true)
    private val renamedHs = Mod(hsSha, null, true)
    private val mv = Mod("b".repeat(64), "Big Mod", true)
    private val plain = Mod("c".repeat(64), "Some ship", false)

    @Test
    fun whatUsesHyperspace() {
        for (n in listOf("data/hyperspace.xml", "data/hyperspace.xml.append", "data/hyperspace.append.xml", "data\\hyperspace.xml.rawappend",
            "data/Hyperspace.append.lua", "data/hyperspace.rawclobber.xml")) assertTrue(n, HyperspaceRule.usesHyperspace(listOf("data/blueprints.xml", n)))
        for (n in listOf("data/hyperspace.txt", "hyperspace.xml", "img/data/hyperspace.xml", "data/blueprints.xml.append"))
            assertFalse(n, HyperspaceRule.usesHyperspace(listOf(n)))
    }

    @Test
    fun hyperspaceByTheCatalogueOrItsOwnTitleNeverByFileName() {
        assertTrue(HyperspaceRule.isHyperspace(renamedHs, shas))
        assertTrue(HyperspaceRule.isHyperspace(Mod("d".repeat(64), "Hyperspace", false), shas))
        assertFalse(HyperspaceRule.isHyperspace(Mod("d".repeat(64), "My mod", true), shas))
    }

    @Test
    fun whichSetsWarn() {
        assertEquals(mv, HyperspaceRule.without(listOf(mv), shas))
        assertEquals(mv, HyperspaceRule.without(listOf(plain, mv), shas))
        assertNull(HyperspaceRule.without(listOf(renamedHs, mv), shas))
        assertNull(HyperspaceRule.without(listOf(mv, hs), shas))
        assertNull(HyperspaceRule.without(listOf(plain), shas))
        assertNull(HyperspaceRule.without(emptyList(), shas))
    }
}
