package ftl.android.app

import ftl.android.app.game.ConsoleButton
import org.jdom2.Element
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.znix.xftl.devutil.Area
import xyz.znix.xftl.devutil.CommandCompleter
import xyz.znix.xftl.devutil.ConsoleCheats
import xyz.znix.xftl.devutil.ConsoleGate
import xyz.znix.xftl.devutil.ConsoleItems
import xyz.znix.xftl.devutil.ConsoleReference
import xyz.znix.xftl.devutil.ConsoleRegistry
import xyz.znix.xftl.devutil.ConsoleScroller
import xyz.znix.xftl.devutil.DebugConsole
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.ClipRect
import xyz.znix.xftl.sys.Input

/**
 * The one debug console (docs/CONSOLE.md): its command list holds every command of the port's development console and of
 * Hyperspace's (CommandConsole::RunCommand plus the original's CommandGui::RunCommand), names clash nowhere, lookups take
 * any case, the Hyperspace-only ones answer so in vanilla, and the gating rule. Running the commands in a game is
 * tests/console.mjs; the >_ button on the emulator is tools/android-touch (docs/TESTS.md).
 */
class ConsoleTest {
    /** A console over a game that was never set up: building the list and running the refusals never touch the game. */
    private fun console(): DebugConsole {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val game = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, InGameState::class.java) as InGameState
        return DebugConsole(game)
    }

    @Before
    @After
    fun vanilla() {
        HS.active = false
        HS.config = null
        ConsoleGate.setting = false
    }

    private fun hyperspace(console: String) {
        HS.config = HsConfig.parse(Element("FTL").addContent(Element("console").also { e ->
            for (a in console.split(' ').filter { it.contains('=') }) e.setAttribute(a.substringBefore('='), a.substringAfter('=').trim('"'))
        }))
        HS.active = true
    }

    /** HS 1.23.2 CommandConsole.cpp's command words, and the original 1.6.14 ones it passes on (CommandGui::RunCommand). */
    private val hsWords = listOf(
        "SQUISHY", "STORE", "FORCE_STORE", "EXIT", "KILL", "SKILL", "DELETECREW", "COOLSWORDBRO", "SPEED", "LUA", "SYS",
        "DAMAGESYS", "REMOVESYS", "DEBUG", "SHIP", "SHIP_CUSTOM", "SHIP_CUSTOM_LOCK", "RICH", "POOR", "FLEET", "LOADEVENT",
        "CREDITS", "VICTORY", "VARIABLE", "VAR", "METAVARIABLE", "METAVAR", "MVAR", "ACH", "ACH_LOCK", "SWITCHALL", "SWITCH",
        "WEAPON",
    )
    private val originalWords = listOf("SCRAP", "FUEL", "MISSILES", "DRONES", "HULL", "DRONE", "AUG", "CREW", "GOD", "DELETE", "EVENT", "LOAD")

    /** The port's development commands before the merge. */
    private val ourNames = listOf(
        "rich", "poor", "scrap", "drones", "missiles", "fuel", "weapon", "drone", "aug", "store", "event", "fix", "cld", "crew",
        "ecrew", "boarder", "skills", "kill", "killcrew", "sectors", "boss", "kill-ecrew", "system", "spawn-ship", "enemy-weapon",
        "upall", "downall", "set", "damage", "breach", "ion", "wreck", "autofire", "hurt", "skill", "force-hack", "super-shield",
        "env", "dump-save", "save-load", "gc", "save", "load", "download", "import", "inspect", "mark", "gameover", "ach-set",
        "ach-del", "ship-unlock", "ship-lock", "layout-unlock", "reset-ftl", "reload-console", "reload-flags", "help",
    )

    @Test
    fun everyCommandOfBothConsolesIsInTheOneListOnce() {
        val commands = console().commands
        val names = commands.map { it.name.uppercase() }
        assertEquals("no two commands share a name in any case", names.size, names.toSet().size)
        for (w in hsWords + originalWords) assertNotNull("Hyperspace/original $w", ConsoleRegistry.find(commands, w)?.raw)
        for (n in ourNames) {
            val now = ConsoleRegistry.RENAMED[n] ?: n
            val cmd = ConsoleRegistry.find(commands, now)
            assertNotNull("ours: $n (now $now)", cmd)
            // a folded one is the Hyperspace/original command of that name now
            // help reads its whole line now (its search opens the reference)
            if (n in ConsoleRegistry.FOLDED || n == "help") assertNotNull(n, cmd!!.raw) else if (n !in ConsoleRegistry.RENAMED) assertNull(n, cmd!!.raw)
        }
        for (c in commands) assertTrue("help line for ${c.name}", c.helpText.isNotBlank())
    }

    @Test
    fun aClashKeepsTheHyperspaceCommandUnderItsNameAndOursMoves() {
        val commands = console().commands
        assertEquals(listOf("rich", "poor", "weapon", "drone", "aug", "store", "event", "kill", "skill", "load"), ConsoleRegistry.RENAMED.keys.toList())
        for ((old, new) in ConsoleRegistry.RENAMED) {
            assertNotNull("$old is Hyperspace's or the original's", ConsoleRegistry.find(commands, old)?.raw)
            val ours = ConsoleRegistry.find(commands, new)
            assertNotNull(new, ours)
            assertNull("$new is ours", ours!!.raw)
            assertTrue(new, new.startsWith("dev-"))
        }
        assertEquals(listOf("scrap", "fuel", "missiles", "drones", "crew"), ConsoleRegistry.FOLDED)
        for (n in ConsoleRegistry.FOLDED) assertNull("$n has no dev- twin", ConsoleRegistry.find(commands, "dev-$n"))
    }

    @Test
    fun namesMatchInAnyCase() {
        val commands = console().commands
        assertEquals("RICH", ConsoleRegistry.find(commands, "rich")?.name)
        assertEquals("RICH", ConsoleRegistry.find(commands, "Rich")?.name)
        assertEquals("dev-rich", ConsoleRegistry.find(commands, "DEV-RICH")?.name)
        assertEquals("upall", ConsoleRegistry.find(commands, "UPALL")?.name)
        assertNull(ConsoleRegistry.find(commands, "nosuchcommand"))
    }

    @Test
    fun hyperspaceOnlyCommandsSaySoInVanilla() {
        val c = console()
        val hsOnly = c.commands.filter { it.needsHs }.map { it.name }
        assertEquals(listOf("FORCE_STORE", "LUA", "REMOVESYS", "SHIP_CUSTOM", "SHIP_CUSTOM_LOCK", "VAR", "VARIABLE", "METAVAR",
            "METAVARIABLE", "MVAR", "ACH_LOCK", "SWITCHALL", "SWITCH"), hsOnly)
        for (n in hsOnly) {
            c.execute(n.lowercase() + " x")
            assertEquals(ConsoleRegistry.needsHsMessage(n), c.outputText().last())
        }
        c.execute("nosuchcommand 1")
        assertEquals("Unknown command 'nosuchcommand', see the help command", c.outputText().last())
    }

    @Test
    fun theLineEditsAsHyperspacesTextInput() {
        val c = console()
        for (ch in "hello World 12") c.type(ch)
        repeat(3) { c.keyPressed(Input.KEY_LEFT, 0.toChar()) }
        c.keyPressed(Input.KEY_BACK, 0.toChar())
        c.keyPressed(Input.KEY_DELETE, 0.toChar())
        c.keyPressed(Input.KEY_HOME, 0.toChar())
        c.type('X')
        c.keyPressed(Input.KEY_END, 0.toChar())
        c.type('Y')
        // what HS's box holds after the same keys (the oracle session wp7-ui-console)
        assertEquals("Xhello Worl12Y", c.input)
        // Delete at the end clears the line
        c.keyPressed(Input.KEY_DELETE, 0.toChar())
        assertEquals("", c.input)
        // the grave key types ~ shifted, never its `
        c.keyPressed(Input.KEY_GRAVE, '`')
        c.keyPressed(Input.KEY_GRAVE, '~')
        c.type('=')
        assertEquals("~=", c.input)
        c.keyPressed(Input.KEY_DELETE, 0.toChar())
        // a mod's invertCaps swaps the case of typed letters
        hyperspace("enabled=\"true\" invertCaps=\"true\"")
        for (ch in "Ship all") c.type(ch)
        assertEquals("sHIP ALL", c.input)
        c.closed()
        assertEquals("", c.input)
        // at most 480 characters
        HS.active = false
        repeat(500) { c.type('a') }
        assertEquals(DebugConsole.MAX_LENGTH, c.input.length)
    }

    @Test
    fun theGatingRule() {
        val grave = Input.KEY_GRAVE
        val backslash = Input.KEY_HASH
        // vanilla, setting off: closed
        assertFalse(ConsoleGate.allowed())
        assertFalse(ConsoleGate.opens(grave, "dev_console", true))
        assertFalse(ConsoleGate.opens(backslash, null, true))
        // setting on: both keys, in every mode
        ConsoleGate.setting = true
        assertTrue(ConsoleGate.opens(grave, null, false))
        assertTrue(ConsoleGate.opens(backslash, null, false))
        assertTrue(ConsoleGate.opens(Input.KEY_F, "dev_console", false))
        hyperspace("enabled=\"false\"")
        assertTrue("the setting opens it whatever the mod says", ConsoleGate.opens(grave, "speed", false))
        // setting off: a mod's <console enabled="false"/> keeps it closed
        ConsoleGate.setting = false
        assertFalse(ConsoleGate.allowed())
        assertFalse(ConsoleGate.opens(backslash, "console", true))
        // <console enabled="true"/>: HS's console hotkey, when HS would open it; ` stays HS's speed key
        hyperspace("enabled=\"true\"")
        assertTrue(ConsoleGate.allowed())
        assertTrue(ConsoleGate.opens(backslash, "console", true))
        assertFalse("a focus window is open", ConsoleGate.opens(backslash, "console", false))
        assertFalse(ConsoleGate.opens(grave, "speed", true))
    }

    // ---- get and unlock, the glow, the reference (round 20)

    @Test
    fun getAndUnlockAreTheOnlyNewOnesAndGlow() {
        val commands = console().commands
        assertEquals(listOf("get", "unlock"), commands.filter { it.featured }.map { it.name })
        assertEquals("the new ones come first in the list", listOf("get", "unlock"), commands.take(2).map { it.name })
        for (c in commands.filter { it.featured }) {
            assertNotNull("${c.name} reads its whole line", c.raw)
            assertEquals(ConsoleRegistry.Groups.GET, c.group)
        }
        assertTrue("no old command is in the new group", commands.filter { !it.featured }.none { it.group == ConsoleRegistry.Groups.GET })
    }

    @Test
    fun everyCommandHasAGroupAndItsExamplesRunIt() {
        for (c in console().commands) {
            assertTrue("${c.name} has a group of the reference", c.group in ConsoleRegistry.Groups.ORDER)
            for (e in c.examples) assertTrue("${c.name}: $e", e.substringBefore(' ').equals(c.name, ignoreCase = true))
        }
    }

    /** docs/CONSOLE.md's command tables are the reference generated from the definitions: they cannot go stale. */
    @Test
    fun theDocsHoldTheReferenceGeneratedFromTheDefinitions() {
        val file = java.io.File("../../docs/CONSOLE.md").takeIf { it.exists() } ?: java.io.File("docs/CONSOLE.md")
        val doc = file.readText().replace("\r\n", "\n")
        val begin = "<!-- generated: ConsoleReference.markdown, see ConsoleTest -->\n"
        val end = "<!-- end of the generated reference -->"
        val generated = ConsoleReference.markdown(console().commands)
        val i = doc.indexOf(begin)
        val j = doc.indexOf(end)
        assertTrue("CONSOLE.md has the generated section", i >= 0 && j > i)
        if (System.getenv("CONSOLE_DOCS_WRITE") == "1") {
            file.writeText(doc.substring(0, i + begin.length) + "\n" + generated + "\n" + doc.substring(j))
            return
        }
        assertEquals("CONSOLE.md is regenerated with CONSOLE_DOCS_WRITE=1", generated.trim(), doc.substring(i + begin.length, j).trim())
    }

    @Test
    fun helpSearchesTheReference() {
        val c = console()
        c.execute("help unlock")
        assertEquals("the reference stays open on the search", "help unlock", c.input)
        val out = c.outputText()
        assertTrue(out.any { it.startsWith("unlock <ship|achievement> [name]") })
        assertTrue(out.any { it.contains("unlock ship The_Kestrel_B") })
        assertEquals(listOf("get"), ConsoleReference.matching(c.commands, "get").take(1).map { it.name })
        assertEquals("SWITCHALL", ConsoleReference.matching(c.commands, "SWITCHALL").first().name)
        assertTrue(ConsoleReference.matching(c.commands, "no such words here").isEmpty())
        // an empty search: every command, the new ones first (the line per command in the output is tests/console.mjs)
        assertEquals(c.commands.size, ConsoleReference.matching(c.commands, "").size)
        assertEquals(listOf("get", "unlock"), ConsoleReference.matching(c.commands, "").take(2).map { it.name })
    }

    private fun item(id: String, english: String, local: String = english, kind: ConsoleItems.Kind = ConsoleItems.Kind.WEAPON, aliases: List<String> = emptyList()) =
        ConsoleItems.Item(kind, id, english, local, emptyList(), aliases = aliases)

    @Test
    fun namesAreTheEnglishOnesWithUnderscores() {
        assertEquals("Burst_Laser_II", ConsoleItems.keyOf(" Burst  Laser II "))
        val items = listOf(item("LASER_BURST_2", "Burst Laser II", "Rafale laser II", aliases = listOf("Burst II")),
            item("LASER_BURST_1", "Burst Laser I"), item("MV_A", "Twin"), item("MV_B", "Twin"))
        ConsoleItems.assignKeys(items)
        assertEquals(listOf("Burst_Laser_II", "Burst_Laser_I", "Twin_MV_A", "Twin_MV_B"), items.map { it.key })
        fun one(q: String) = ConsoleItems.resolve(items, q, false).one?.id
        assertEquals("LASER_BURST_2", one("Burst_Laser_II"))
        assertEquals("any case, spaces too", "LASER_BURST_2", one("burst laser ii"))
        assertEquals("the id", "LASER_BURST_1", one("laser_burst_1"))
        assertEquals("the current language's name", "LASER_BURST_2", one("Rafale_laser_II"))
        assertEquals("the short name", "LASER_BURST_2", one("Burst_II"))
        assertEquals("a shared name: the key ends in the id", "MV_B", one("Twin_MV_B"))
        assertEquals("the id alone still works", "MV_B", one("MV_B"))
        assertTrue(ConsoleItems.sharesName(items[3]))
        assertFalse(ConsoleItems.sharesName(items[0]))
        assertNull("a shared name alone is ambiguous", one("Twin"))
        assertEquals(2, ConsoleItems.resolve(items, "Twin", false).items.size)
        assertEquals("words of the name", "LASER_BURST_2", one("rafale_ii"))
        assertNull(one("nothing like it"))
        assertTrue("no wildcard where it is not allowed", ConsoleItems.resolve(items, "*", false).items.isEmpty())
        // a name that meets another item's id (Multiverse: an untitled blueprint is named by its id), ids apart in case only
        val clash = listOf(item("FOO", "Bar"), item("BAR", "BAR"), item("baz", "Q"), item("BAZ", "R"), item("Z", "baz"))
        ConsoleItems.assignKeys(clash)
        assertEquals(clash.size, clash.map { it.key.lowercase() }.toSet().size)
        assertEquals(listOf("Bar_FOO", "BAR_BAR"), clash.take(2).map { it.key })
        for (i in clash) assertEquals(i.key, i.id, ConsoleItems.resolve(clash, i.key, false).one?.id)
    }

    /** Every key the pickers and completion fill in names exactly one item, also where items share a name or a short name. */
    @Test
    fun sharedNamesGetKeysThatTellThemApart() {
        val ev = listOf(ConsoleItems.TAG_EVENT_ONLY)
        // vanilla's pairs: the two Artemis Missiles (both event only), the Fire Beam and the drone's Fire Beam, two Drone Lasers
        val items = listOf(
            ConsoleItems.Item(ConsoleItems.Kind.WEAPON, "MISSILES_2", "Artemis Missiles", "Artemis Missiles", ev, aliases = listOf("Artemis")),
            ConsoleItems.Item(ConsoleItems.Kind.WEAPON, "MISSILES_2_PLAYER", "Artemis Missiles", "Artemis Missiles", ev, aliases = listOf("Artemis")),
            ConsoleItems.Item(ConsoleItems.Kind.WEAPON, "BEAM_FIRE", "Fire Beam", "Fire Beam", emptyList(), aliases = listOf("Fire Beam")),
            ConsoleItems.Item(ConsoleItems.Kind.WEAPON, "DRONE_FIREBEAM", "Fire Beam", "Fire Beam", ev, aliases = listOf("Fire Beam")),
            ConsoleItems.Item(ConsoleItems.Kind.WEAPON, "DRONE_LASER", "Drone Laser", "Drone Laser", ev),
            ConsoleItems.Item(ConsoleItems.Kind.WEAPON, "ANTI_DRONE_ION", "Drone Laser", "Drone Laser", ev),
        )
        ConsoleItems.assignKeys(items)
        assertEquals(listOf("Artemis_Missiles_MISSILES_2", "Artemis_Missiles_MISSILES_2_PLAYER", "Fire_Beam_BEAM_FIRE",
            "Fire_Beam_DRONE_FIREBEAM", "Drone_Laser_DRONE_LASER", "Drone_Laser_ANTI_DRONE_ION"), items.map { it.key })
        for (i in items) assertEquals("${i.key} is one item", i.id, ConsoleItems.resolve(items, i.key, false).one?.id)
        for (i in items) assertEquals("${i.key} any case", i.id, ConsoleItems.resolve(items, i.key.lowercase(), false).one?.id)
        // the whole shared name: the one a store sells when there is one, else ambiguous (the keys tell them apart)
        assertEquals("BEAM_FIRE", ConsoleItems.resolve(items, "Fire_Beam", false).preferred?.id)
        assertNull(ConsoleItems.resolve(items, "Artemis", false).preferred)
        assertEquals(2, ConsoleItems.resolve(items, "Artemis", false).items.size)
        assertEquals("a name that is also an id is that item", "DRONE_LASER", ConsoleItems.resolve(items, "Drone_Laser", false).preferred?.id)
        // the picker's search keeps every item of a shared name: nothing is hidden
        assertEquals(2, ConsoleItems.resolve(items, "fire beam", false).items.size)
    }

    /**
     * Full equipment (user, 2026-11-05), as the original's event rewards: one item waits in an over-capacity box at a
     * time (WorldManager::ModifyResources, Equipment::IsCompletelyFull), crew past the limit in the crew screen; while one
     * waits another get of that kind is refused; Hyperspace's multipleOverCapacity keeps any number.
     */
    @Test
    fun aFullShipRefusesAnotherGetWhileOneWaits() {
        val k = ConsoleItems.Kind.entries
        fun r(kind: ConsoleItems.Kind, room: Boolean = false, item: String? = null, aug: String? = null, multiple: Boolean = false, crew: Boolean = false) =
            ConsoleCheats.refusal(kind, room, item, aug, multiple, crew)
        for (kind in k) assertNull("$kind: nothing waits", r(kind))
        assertEquals("Cargo full: sell or discard the pending Burst Laser Mark II first (equipment screen)",
            r(ConsoleItems.Kind.WEAPON, item = "Burst Laser Mark II"))
        assertTrue(r(ConsoleItems.Kind.DRONE, item = "Combat Drone Mark I")!!.startsWith("Cargo full: sell or discard the pending Combat Drone Mark I"))
        assertNull("a free slot or cargo place takes it", r(ConsoleItems.Kind.WEAPON, room = true, item = "Burst Laser Mark II"))
        assertNull("the item box does not hold augments", r(ConsoleItems.Kind.AUGMENT, item = "Burst Laser Mark II"))
        assertEquals("Augments full: sell or discard the pending Scrap Recovery Arm first (equipment screen)",
            r(ConsoleItems.Kind.AUGMENT, aug = "Scrap Recovery Arm"))
        assertNull("the augment box does not hold weapons", r(ConsoleItems.Kind.WEAPON, aug = "Scrap Recovery Arm"))
        assertNull("multipleOverCapacity keeps every item", r(ConsoleItems.Kind.WEAPON, item = "x", multiple = true))
        assertNull(r(ConsoleItems.Kind.AUGMENT, aug = "x", multiple = true))
        assertEquals("Crew full: dismiss the extra crew member first (crew screen)", r(ConsoleItems.Kind.CREW, crew = true))
        assertEquals("the crew screen holds one extra whatever the mod", "Crew full: dismiss the extra crew member first (crew screen)",
            r(ConsoleItems.Kind.CREW, room = true, multiple = true, crew = true))
        assertNull(r(ConsoleItems.Kind.SYSTEM, item = "x", aug = "y", crew = true))
    }

    @Test
    fun aWildcardStandsForAnyPartOfAName() {
        val k = ConsoleItems.Kind.UNLOCK_SHIP
        val ships = listOf(item("PLAYER_SHIP_HARD", "The Kestrel A", kind = k), item("PLAYER_SHIP_HARD_2", "The Kestrel B", kind = k),
            item("PLAYER_SHIP_HARD_3", "The Kestrel C", kind = k), item("PLAYER_SHIP_CIRCLE", "The Torus A", kind = k))
        ConsoleItems.assignKeys(ships)
        assertEquals("The_Kestrel_B", ships[1].key)
        fun ids(q: String) = ConsoleItems.resolve(ships, q, true).items.map { it.id }
        assertEquals(listOf("PLAYER_SHIP_HARD", "PLAYER_SHIP_HARD_2", "PLAYER_SHIP_HARD_3"), ids("The_Kestrel_*"))
        assertEquals(4, ids("*").size)
        assertEquals(listOf("PLAYER_SHIP_HARD", "PLAYER_SHIP_CIRCLE"), ids("*_A"))
        assertEquals(listOf("PLAYER_SHIP_HARD_2"), ids("the_kestrel_b"))
        // whole words before parts of words: "kestrel c" is the C layout, not every Kestrel_Cruiser_<letter> alias
        val withClass = ships.map { item(it.id, it.english, kind = k, aliases = listOf("Kestrel Cruiser " + it.english.last())) }
        ConsoleItems.assignKeys(withClass)
        assertEquals("PLAYER_SHIP_HARD_3", ConsoleItems.resolve(withClass, "kestrel c", false).one?.id)
        assertEquals("PLAYER_SHIP_HARD_3", ConsoleItems.resolve(withClass, "Kestrel_C", false).one?.id)
        assertTrue(ConsoleItems.resolve(ships, "*_A", true).wildcard)
        assertTrue(ConsoleItems.globMatches("a*c*e", "abcde"))
        assertFalse(ConsoleItems.globMatches("a*c*e", "abde"))
        assertFalse(ConsoleItems.globMatches("ab*ba", "aba"))
        // where '*' makes sense: unlocking ships and achievements, SYS ALL; not one item into slots or a ship switch
        assertEquals(setOf(ConsoleItems.Kind.UNLOCK_SHIP, ConsoleItems.Kind.ACHIEVEMENT, ConsoleItems.Kind.SYSTEM),
            ConsoleItems.Kind.entries.filter { ConsoleItems.wildcardAllowed(it) }.toSet())
    }

    @Test
    fun getAndUnlockWithoutANameOpenThePicker() {
        val c = console()
        assertEquals(ConsoleItems.Kind.WEAPON, ConsoleCheats.kindOf("get", "WEAPON"))
        assertEquals(ConsoleItems.Kind.DRONE_PARTS, ConsoleCheats.kindOf("get", "drones"))
        assertEquals(ConsoleItems.Kind.UNLOCK_SHIP, ConsoleCheats.kindOf("unlock", "ship"))
        assertNull(ConsoleCheats.kindOf("unlock", "weapon"))
        c.execute("get")
        assertEquals("get ", c.input)
        c.execute("unlock")
        assertEquals("unlock ", c.input)
        c.execute("get weapon")
        assertEquals("the picker: the input keeps the kind", "get weapon ", c.input)
        c.execute("get gizmo x")
        assertTrue(c.outputText().last().startsWith("get: no such kind 'gizmo'"))
        // the ship switch is gone (user, 2026-11-05): unlock the ship and start a new run; SWITCH/SWITCHALL stay
        assertNull(ConsoleCheats.kindOf("get", "ship"))
        assertTrue(ConsoleItems.GET_KINDS.none { it.word == "ship" })
        c.execute("get ship The_Torus_B")
        assertTrue(c.outputText().last().startsWith("get ship is gone: unlock the ship"))
        assertTrue("help has no get ship", c.commands.first { it.name == "get" }.let { g -> g.examples.none { it.startsWith("get ship") } && !g.helpText.contains("SWITCHALL") })
        c.execute("get scrap lots")
        assertTrue(c.outputText().last().contains("'lots' is not a number"))
        c.execute("get weapon Burst_*")
        assertTrue("one weapon at a time", c.outputText().last().contains("takes one name"))
    }

    @Test
    fun theOpenConsoleClosesOnEscapeAndItsKeysButTypesShiftedOnes() {
        val none = 0.toChar()
        assertTrue(ConsoleGate.closes(Input.KEY_ESCAPE, none, null, false))
        assertTrue(ConsoleGate.closes(Input.KEY_ESCAPE, none, null, true))
        assertTrue(ConsoleGate.closes(Input.KEY_GRAVE, '`', Input.KEY_GRAVE, false))
        assertTrue(ConsoleGate.closes(Input.KEY_GRAVE, none, null, false))
        // Shift+` is ~ (Lua's ~=): typed, as Hyperspace's console box types it; known by Shift or by the character
        assertFalse(ConsoleGate.closes(Input.KEY_GRAVE, '`', Input.KEY_GRAVE, true))
        assertFalse(ConsoleGate.closes(Input.KEY_GRAVE, '~', Input.KEY_GRAVE, false))
        assertTrue(ConsoleGate.closes(Input.KEY_F, 'f', Input.KEY_F, false))
        assertFalse(ConsoleGate.closes(Input.KEY_F, 'F', Input.KEY_F, false))
        assertFalse(ConsoleGate.closes(Input.KEY_F, 'f', Input.KEY_F, true))
        assertFalse(ConsoleGate.closes(Input.KEY_HASH, '\\', Input.KEY_GRAVE, false))
    }

    @Test
    fun theButtonStaysOnTheScreenAndKeepsItsPlace() {
        val size = 96
        // default: the right edge, a little below the middle
        assertEquals(Pair(1920 - size, ((1080 - size) * 0.6f).toInt()), ConsoleButton.position(ConsoleButton.DEFAULT_X, ConsoleButton.DEFAULT_Y, 1920, 1080, size))
        // a drag past an edge stops at it
        assertEquals(Pair(0f, 1080f - size), ConsoleButton.clamp(-50f, 5000f, 1920, 1080, size))
        // the place kept as fractions comes back on another screen size, still on it
        val (fx, fy) = ConsoleButton.fraction(912f, 300f, 1920, 1080, size)
        assertEquals(Pair(912, 300), ConsoleButton.position(fx, fy, 1920, 1080, size))
        val (x, y) = ConsoleButton.position(fx, fy, 1280, 720, size)
        assertTrue(x in 0..1280 - size && y in 0..720 - size)
        assertEquals(Pair(1280 - size, 720 - size), ConsoleButton.position(7f, 7f, 1280, 720, size))
    }

    // ---- the look and the feel (docs/CONSOLE.md "Scrolling", "Keys"): pixel scrolling, flings, the clip

    private fun frames(s: ConsoleScroller, n: Int, dt: Float = 1f / 60): List<Float> = (0 until n).map { s.update(dt); s.pos }

    @Test
    fun theWheelGlidesByPixelsAndStopsAtTheEnds() {
        val s = ConsoleScroller()
        s.setExtent(1000, 300)
        assertEquals(700f, s.max)
        s.wheel(96f)
        val glide = frames(s, 40)
        assertEquals("a notch ends exactly 96 pixels down", 96f, s.pos)
        val steps = glide.zipWithNext { a, b -> b - a }
        assertTrue("never back, never past", steps.all { it >= 0f } && glide.all { it <= 96f })
        assertTrue("a glide over several frames, not a jump: $steps", steps.first() < 96f * 0.4f && glide.count { it < 96f } >= 4)
        // a trackpad's small steps add up to their sum
        repeat(10) { s.wheel(3f); s.update(1f / 120) }
        frames(s, 30)
        assertEquals(126f, s.pos)
        // far past the end: it stops there and stays still
        s.wheel(5000f)
        val end = frames(s, 60)
        assertEquals(700f, s.pos)
        assertTrue(end.all { it <= 700f })
        assertEquals(listOf(700f, 700f, 700f), frames(s, 3))
        s.wheel(-5000f); frames(s, 60)
        assertEquals(0f, s.pos)
    }

    @Test
    fun aDragFollowsTheFingerAndFlingsOnEasedToAStop() {
        val s = ConsoleScroller()
        s.setExtent(5000, 400)
        s.press()
        // a steady upward drag: 12 pixels a frame at 60 frames a second, 720 pixels a second
        repeat(10) { s.drag(-12f); s.update(1f / 60) }
        assertEquals("the content went with the finger", 120f, s.pos)
        s.release()
        assertTrue("the fling has the drag's speed: ${s.velocity}", s.velocity in 650f..790f)
        val fling = frames(s, 240)
        val steps = fling.zipWithNext { a, b -> b - a }
        assertTrue("it slows evenly: each step a little smaller", steps.zipWithNext { a, b -> b <= a + 0.001f }.all { it })
        assertTrue("and stops", s.velocity == 0f && !s.moving)
        // v / FRICTION pixels on, less what the stop takes off
        assertTrue("about 180 pixels on: ${s.pos - 120f}", s.pos - 120f in 160f..185f)
        val rest = frames(s, 5)
        assertTrue("then stays", rest.all { it == s.pos })
    }

    @Test
    fun aFlingStopsDeadAtTheEndWithNoJitter() {
        val s = ConsoleScroller()
        s.setExtent(1000, 400)
        s.jumpTo(540f)
        s.press()
        repeat(5) { s.drag(-12f); s.update(1f / 60) }
        assertEquals(600f, s.pos)
        s.release()
        assertEquals("no overshoot to spring back from", listOf(600f, 600f, 600f, 600f), frames(s, 4))
        assertEquals(0f, s.velocity)
        // a drag past the top stops there too
        s.press(); s.drag(2000f); s.update(1f / 60)
        assertEquals(0f, s.pos)
        s.release()
        assertEquals(listOf(0f, 0f), frames(s, 2))
    }

    @Test
    fun aPressStopsTheListWhereItWasDrawnAndHeldStillMeansNoFling() {
        val s = ConsoleScroller()
        s.setExtent(5000, 400)
        s.press()
        repeat(10) { s.drag(-20f); s.update(1f / 60) }
        s.release()
        frames(s, 10)
        val drawnAt = s.pos
        assertTrue(s.moving)
        // a tap while it moves: it stops at once where the last frame drew it, so the tap lands on what it hit
        s.press()
        assertEquals(drawnAt, s.pos)
        assertEquals(listOf(drawnAt, drawnAt), frames(s, 2))
        s.release()
        assertEquals("a tap is not a fling", 0f, s.velocity)
        // a drag held still before it is let go does not fling either
        s.press()
        repeat(10) { s.drag(-12f); s.update(1f / 60) }
        frames(s, 8)
        s.release()
        assertEquals(0f, s.velocity)
        // a step with no time (the hosts run one after each input event) moves nothing
        s.wheel(100f)
        val p = s.pos
        s.update(0f)
        assertEquals(p, s.pos)
    }

    @Test
    fun theClipMapsGameUnitsToFramebufferPixelsAndNests() {
        // the Android frame at two pixels a unit: 1280x720 units on a 2560x1440 framebuffer, rows from the bottom
        val r = ClipRect.fromScreen(100f, 50f, 300f, 150f, 1280, 720, 0, 0, 2560, 1440)
        assertEquals(ClipRect(200, 1140, 400, 200), r)
        // the website: one pixel a unit
        assertEquals(ClipRect(18, 720 - 700, 1244, 572), ClipRect.fromScreen(18f, 128f, 1262f, 700f, 1280, 720, 0, 0, 1280, 720))
        // a clip inside another is what both cover; one beside it covers nothing
        val inner = ClipRect.fromScreen(250f, 100f, 400f, 300f, 1280, 720, 0, 0, 2560, 1440)
        assertEquals(ClipRect(500, 1140, 100, 100), r.intersect(inner))
        assertEquals(0, r.intersect(ClipRect(0, 0, 10, 10)).w)
        // console areas: the same rules for hits
        assertEquals(Area(10, 20, 5, 5), Area(0, 0, 15, 25).intersect(Area(10, 20, 100, 100)))
        assertTrue(Area(0, 0, 10, 10).intersect(Area(20, 20, 5, 5)).empty)
    }

    @Test
    fun theKeysMoveASelectionThroughTheSuggestions() {
        val c = console()
        val list = CommandCompleter(c)
        list.update("s", 0)
        assertFalse(list.hasSelection)
        assertNull("Up with nothing selected is the history's", list.navigate(Input.KEY_UP))
        assertNull("Left too: it moves the cursor", list.navigate(Input.KEY_LEFT))
        assertNotNull(list.navigate(Input.KEY_DOWN))
        val first = list.entries[0].name
        assertTrue(list.hasSelection)
        list.navigate(Input.KEY_DOWN)
        list.navigate(Input.KEY_UP)
        assertTrue(list.activate())
        assertEquals("Enter takes the selection as a click does", "$first ", c.input)
        list.update("s", 0)
        list.navigate(Input.KEY_UP)
        assertTrue("Up from the top drops the selection", !list.hasSelection)
        list.navigate(Input.KEY_DOWN)
        assertTrue(list.clearSelection())
        assertFalse("Escape dropped it", list.hasSelection)
        // a new search drops it too
        list.navigate(Input.KEY_DOWN)
        list.update("he", 0)
        assertFalse(list.hasSelection)
    }
}
