package ftl.android.app

import org.jdom2.Document
import org.jdom2.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewHuman
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.savegame.SaveMigrations
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.systems.MindControl
import xyz.znix.xftl.vanillasave.NativeImport

/**
 * The save revision and the migrations of older runs (savegame.SaveMigrations, docs/SAVES.md), in the engine code the
 * app runs: the revision a run carries, which migrations a revision still needs, the wrapper that marks a run from
 * the original game, and migration 1 (release 2.1.1, report HS105) on a ship's crew. The loading of whole runs is the
 * browser's check (gameplay-parity, GameplayParitySaveMigrationChecks), on the same shared code.
 */
class SaveMigrationsTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    @Suppress("UNCHECKED_CAST")
    private fun <T> blank(c: Class<T>): T = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c) as T

    private fun set(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val field = c.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    /** A game with only its player's ship, and that ship with only its crew list. */
    private fun shipOfGame(): Pair<InGameState, Ship> {
        val game = blank(InGameState::class.java)
        val ship = blank(Ship::class.java)
        set(ship, "sys", game)
        set(ship, "crew", ArrayList<Any>())
        set(game, "player", ship)
        return game to ship
    }

    /** A living crew member of [ship], aboard it, marked as mind controlled or not. */
    private fun crewOf(ship: Ship, controlled: Boolean, aboard: Boolean = true): LivingCrew {
        val crew = blank(CrewHuman::class.java)
        val room = blank(Room::class.java)
        set(room, "ship", ship)
        set(crew, "room", room)
        set(crew, "health", 100f)
        set(crew, "ownerShip", ship)
        crew.hsMindControlled = controlled
        if (aboard) ship.crew.add(crew)
        return crew
    }

    @Test
    fun aRunCarriesItsRevision() {
        assertEquals("a run of 2.1.1 has no revision", 0, SaveMigrations.revisionOf(Element("xftlSaveGame")))
        assertEquals(0, SaveMigrations.revisionOf(Element("xftlSaveGame").setAttribute(SaveMigrations.ATTRIBUTE, "soon")))
        assertEquals(0, SaveMigrations.revisionOf(Element("xftlSaveGame").setAttribute(SaveMigrations.ATTRIBUTE, "-3")))
        assertEquals(7, SaveMigrations.revisionOf(Element("xftlSaveGame").setAttribute(SaveMigrations.ATTRIBUTE, "7")))
        val written = Element("xftlSaveGame")
        SaveMigrations.stamp(written)
        assertEquals(SaveMigrations.CURRENT.toString(), written.getAttributeValue(SaveMigrations.ATTRIBUTE))
        assertEquals(SaveMigrations.CURRENT, SaveMigrations.revisionOf(written))
    }

    @Test
    fun olderRevisionsGetTheMigrationsAboveThemInOrder() {
        assertEquals("the migrations are numbered 1 to CURRENT", (1..SaveMigrations.CURRENT).toList(), SaveMigrations.ALL.map { it.revision })
        assertEquals(SaveMigrations.ALL.map { it.name }, SaveMigrations.pending(0).map { it.name })
        assertEquals("freeUnheldMindControl", SaveMigrations.pending(0).first().name)
        assertTrue("the current revision needs none", SaveMigrations.pending(SaveMigrations.CURRENT).isEmpty())
        assertTrue("a newer revision needs none", SaveMigrations.pending(SaveMigrations.CURRENT + 5).isEmpty())
    }

    @Test
    fun aRunFromTheOriginalGameIsToldByItsWrapper() {
        val wrapper = Element("xftlSaveGame").setAttribute(NativeImport.ELEMENT, "1").setAttribute("prefix", "")
        wrapper.addContent(Element(NativeImport.ELEMENT).setAttribute("kind", "run").setAttribute("file", "continue.sav")
            .setAttribute("bytes", "3").setText(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
        assertNotNull("the importer takes it", NativeImport.unwrap(Document(wrapper)))
        // A run of ours, of 2.1.1 (no revision) or of today, is not one.
        assertNull(NativeImport.unwrap(Document(Element("xftlSaveGame"))))
        assertNull(NativeImport.unwrap(Document(Element("xftlSaveGame").also { SaveMigrations.stamp(it) })))
    }

    @Test
    fun migrationOneFreesACrewMemberNoSystemHolds() {
        val (_, ship) = shipOfGame()
        val stuck = crewOf(ship, controlled = true)
        val other = crewOf(ship, controlled = false)
        assertTrue(stuck.isMindControlled)
        val line = SaveMigrations.freeUnheldMindControl(listOf(ship))
        assertFalse("the crew member a 2.1.1 run left under control is free", stuck.isMindControlled)
        assertFalse(other.isMindControlled)
        assertTrue(line, line.contains("1 aboard, 0 waiting"))
        // Twice without harm.
        val again = SaveMigrations.freeUnheldMindControl(listOf(ship))
        assertTrue(again, again.startsWith("no crew member"))
        assertFalse(stuck.isMindControlled)
    }

    @Test
    fun migrationOneLeavesACrewMemberASystemHolds() {
        val (_, ship) = shipOfGame()
        val held = crewOf(ship, controlled = false)
        val system = blank(MindControl::class.java)
        set(system, "controlledCrew", held)
        set(system, "timeRemaining", 5f)
        set(ship, "mindControl", system)
        held.mindControlledBy = system
        assertTrue(system.holds(held))
        val line = SaveMigrations.freeUnheldMindControl(listOf(ship))
        assertTrue("a crew member its system holds stays with the system", held.isMindControlled)
        assertTrue(line, line.startsWith("no crew member"))
    }

    @Test
    fun migrationOneClearsABodyInTheCloneBay() {
        val (_, ship) = shipOfGame()
        val bay = blank(Clonebay::class.java)
        val body = crewOf(ship, controlled = true, aboard = false)
        set(bay, "queue", arrayListOf(body))
        set(ship, "clonebay", bay)
        val line = SaveMigrations.freeUnheldMindControl(listOf(ship))
        assertFalse("the body waiting for its clone loses the mark", body.isMindControlled)
        assertTrue(line, line.contains("0 aboard, 1 waiting"))
    }

    @Test
    fun onlyRunsBelowTheCurrentRevisionAreMigrated() {
        val (game, ship) = shipOfGame()
        val crew = crewOf(ship, controlled = true)
        // A run of the current revision, and one of a newer build: left as they are (a mod's script may have set the mark).
        assertTrue(SaveMigrations.migrate(game, SaveMigrations.CURRENT).isEmpty())
        assertTrue(SaveMigrations.migrate(game, SaveMigrations.CURRENT + 5).isEmpty())
        assertTrue(crew.isMindControlled)
        // A run of 2.1.1.
        val lines = SaveMigrations.migrate(game, 0)
        assertEquals(SaveMigrations.CURRENT, lines.size)
        assertTrue(lines[0], lines[0].startsWith("1 freeUnheldMindControl: freed"))
        assertFalse(crew.isMindControlled)
    }
}
