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
import xyz.znix.xftl.devutil.ConsoleGate
import xyz.znix.xftl.devutil.ConsoleRegistry
import xyz.znix.xftl.devutil.DebugConsole
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
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
    private val originalWords = listOf("SCRAP", "FUEL", "MISSILES", "DRONES", "HULL", "DRONE", "AUG", "CREW", "GOD", "DELETE", "EVENT")

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
            if (n in ConsoleRegistry.FOLDED) assertNotNull(n, cmd!!.raw) else if (n !in ConsoleRegistry.RENAMED) assertNull(n, cmd!!.raw)
        }
        for (c in commands) assertTrue("help line for ${c.name}", c.helpText.isNotBlank())
    }

    @Test
    fun aClashKeepsTheHyperspaceCommandUnderItsNameAndOursMoves() {
        val commands = console().commands
        assertEquals(listOf("rich", "poor", "weapon", "drone", "aug", "store", "event", "kill", "skill"), ConsoleRegistry.RENAMED.keys.toList())
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
}
