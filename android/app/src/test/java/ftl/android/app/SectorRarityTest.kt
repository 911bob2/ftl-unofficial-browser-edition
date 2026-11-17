package ftl.android.app

import org.jdom2.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.IBlueprint
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.LootPool
import xyz.znix.xftl.sector.EventManager
import xyz.znix.xftl.sector.SectorType
import kotlin.random.Random

/**
 * A sector's rarity list as the original takes it (player report MV101: the jump into Multiverse's hacked Union sector,
 * whose list gives mantis_free the rarity 6, ended the run with "Invalid rarity 6").
 * - EventsParser (1.6.14 0x5e8510) reads the first rarityList, every child of it whatever its name, the rarity through
 *   atoi; BlueprintManager::SetRarity (0x60b8e0) stores the value as it is.
 * - The random pick (0x764be0 and its three siblings) takes every blueprint whose rarity is not 0 and weighs it 6 minus
 *   the rarity, with no upper limit: a rarity of 6 is in the list with no weight and is never picked.
 * The same shared code runs in the browser; there the gameplay-parity mode builds the pool of every sector of the
 * loaded data.
 */
class SectorRarityTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    @Suppress("UNCHECKED_CAST")
    private fun <T> blank(c: Class<T>): T = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c) as T

    private fun augment(name: String, rarity: Int?): AugmentBlueprint {
        val e = Element("augBlueprint").setAttribute("name", name)
        if (rarity != null) e.addContent(Element("rarity").setText(rarity.toString()))
        return AugmentBlueprint(e)
    }

    /** A blueprint manager holding just [items] (no game data: only its map is read). */
    private fun manager(vararg items: AugmentBlueprint): BlueprintManager {
        val m = blank(BlueprintManager::class.java)
        val map = HashMap<String, IBlueprint>()
        for (i in items) map[i.name] = i
        BlueprintManager::class.java.getDeclaredField("blueprints").apply { isAccessible = true }.set(m, map)
        return m
    }

    private fun sector(rarities: String): SectorType {
        val e = Element("sectorDescription").setAttribute("name", "TEST_SECTOR").setAttribute("unique", "true")
        e.addContent(Element("nameList").addContent(Element("name").setText("---Un-on--- [!]")))
        e.addContent(Element("trackList").addContent(Element("track").setText("cosmos")))
        val list = Element("rarityList")
        for (entry in rarities.split(';').filter { it.isNotEmpty() }) {
            val (tag, name, rarity) = entry.split(',')
            list.addContent(Element(tag).setAttribute("name", name).setAttribute("rarity", rarity))
        }
        e.addContent(list)
        return SectorType(blank(EventManager::class.java), e)
    }

    @Test
    fun aRarityAboveFiveStaysInTheListAndIsNeverPicked() {
        val a = augment("AUG_A", 2); val b = augment("AUG_B", 3); val c = augment("AUG_C", 1)
        val type = sector("blueprint,AUG_B,6")
        assertEquals(6, type.rarityOverrides["AUG_B"])
        val pool = LootPool(manager(a, b, c), type)
        assertEquals("every blueprint with a rarity other than 0, in name order", listOf("AUG_A", "AUG_B", "AUG_C"), pool.pool.map { it.name })
        assertEquals(6, pool.rarityOf(b))
        val rand = Random(1)
        val seen = HashSet<String>()
        repeat(2000) { seen += pool.getAugment(rand).name }
        assertEquals("a rarity of 6 weighs nothing", setOf("AUG_A", "AUG_C"), seen)
        // Several picks: the two with a weight, then nothing is left to pick.
        assertEquals(setOf("AUG_A", "AUG_C"), pool.getManyRandom(AugmentBlueprint::class.java, rand, 3).map { it.name }.toSet())
    }

    @Test
    fun theWeightsAreSixMinusTheRarity() {
        // Rarity 1 weighs 5, rarity 5 weighs 1: about five picks in six go to the first.
        val pool = LootPool(manager(augment("AUG_A", 1), augment("AUG_B", 5), augment("AUG_C", 0), augment("AUG_D", null)), null)
        assertEquals("rarity 0 and no rarity stay out", listOf("AUG_A", "AUG_B"), pool.pool.map { it.name })
        val rand = Random(7)
        var first = 0
        repeat(6000) { if (pool.getAugment(rand).name == "AUG_A") first++ }
        assertTrue("AUG_A picked $first times of 6000", first in 4800..5200)
    }

    @Test
    fun aSectorMayTakeABlueprintOutOrBringItIn() {
        val a = augment("AUG_A", 2); val b = augment("AUG_B", 0)
        val pool = LootPool(manager(a, b), sector("blueprint,AUG_A,0;blueprint,AUG_B,4"))
        assertEquals(listOf("AUG_B"), pool.pool.map { it.name })
        // Only blueprints of rarity 6 or 0: nothing can be picked.
        val none = LootPool(manager(a, b), sector("blueprint,AUG_A,6"))
        assertEquals(listOf("AUG_A"), none.pool.map { it.name })
        assertNull(none.getRandom(Random(3)) { true })
    }

    @Test
    fun theListIsReadAsTheOriginalReadsIt() {
        // Any child counts, the rarity is atoi's (leading number, 0 for text), the last entry of a name wins.
        val type = sector("blueprint,AUG_A,7x;item,AUG_B, 4;blueprint,AUG_C,many;blueprint,AUG_D,-1;blueprint,AUG_A,6")
        assertEquals(mapOf("AUG_A" to 6, "AUG_B" to 4, "AUG_C" to 0, "AUG_D" to -1), type.rarityOverrides)
        // A second rarityList is not read (first_node), and does not stop the sector from loading.
        val e = Element("sectorDescription").setAttribute("name", "TWO_LISTS").setAttribute("unique", "false")
        e.addContent(Element("nameList").addContent(Element("name").setText("Two")))
        e.addContent(Element("trackList").addContent(Element("track").setText("cosmos")))
        e.addContent(Element("rarityList").addContent(Element("blueprint").setAttribute("name", "AUG_A").setAttribute("rarity", "5")))
        e.addContent(Element("rarityList").addContent(Element("blueprint").setAttribute("name", "AUG_A").setAttribute("rarity", "1")))
        val two = SectorType(blank(EventManager::class.java), e)
        assertEquals(mapOf("AUG_A" to 5), two.rarityOverrides)
        assertNotNull(two.names.single())
        assertFalse(two.unique)
    }

    @Test
    fun aDescriptionWithoutUniqueOrTracksLoads() {
        // 0x5e8510: unique starts false and is only set from the attribute, a missing trackList is the one track
        // "civilian", minSector goes through atoi.
        val e = Element("sectorDescription").setAttribute("name", "BARE").setAttribute("minSector", "3rd")
        e.addContent(Element("nameList").addContent(Element("name").setText("Bare")))
        val bare = SectorType(blank(EventManager::class.java), e)
        assertFalse(bare.unique)
        assertEquals(listOf("civilian"), bare.soundtracks)
        assertEquals(3, bare.minSector)
        assertTrue(bare.rarityOverrides.isEmpty())
    }
}
