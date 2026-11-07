package ftl.android.app

import ftl.saves.Summary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The save questions use the website's words (web/save-words.mjs), and the save list's line the website's rules
 * (web/saves-ui.mjs summaryLine, shipLabel). The website's dialogs are checked by tests/saves-browser.mjs, the launcher's
 * on the emulator (docs/ANDROID.md).
 */
class SaveWordsTest {
    private fun web(name: String) = File("../../web/$name").readText()

    @Test
    fun everyQuestionHasTheWebsitesWords() {
        val js = web("save-words.mjs")
        val block = js.substringAfter("export const SAVE_WORDS = {").substringBefore("\n};")
        val webWords = Regex("""^\s+(\w+): (['"])(.*)\2,$""", RegexOption.MULTILINE).findAll(block)
            .associate { it.groupValues[1] to it.groupValues[3].replace("\\'", "'") }
        assertEquals("the same keys", webWords.keys.sorted(), SaveWords.WORDS.keys.sorted())
        for ((k, v) in webWords) assertEquals(k, v, SaveWords.WORDS[k])
    }

    /** Android's regex engine (ICU) refuses a literal "}" left bare after an escaped "{", which the JVM here accepts. */
    @Test
    fun launcherPatternsAreAndroidSafe() {
        val bare = Regex("""\\\{[^"]*?[^\\]\}""")
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Regex("Regex\\(\"\"\"(.*?)\"\"\"").findAll(f.readText()).map { it.groupValues[1] }.filter { bare.containsMatchIn(it) }.map { "${f.name}: $it" }
        }.toList()
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun fieldsFillIn() {
        assertEquals("Save 2 changed in two places", SaveWords.words("conflictTitle", "name" to "Save 2"))
        assertEquals("In use on Pixel 8", SaveWords.lockLine("Pixel 8", null))
        assertEquals("This save was taken over on another device. Progress and unlocks since your last sync were discarded.", SaveWords.overridden("", null))
        // The takeover notice names the time when the server gives it, as on the website (docs/SAVES.md, "Locks").
        val at = Instant.now().toString()
        assert(SaveWords.overridden("Pixel 8", at).startsWith("This save was taken over on Pixel 8 at ")) { SaveWords.overridden("Pixel 8", at) }
    }

    @Test
    fun theListLineIsTheWebsites() {
        val now = Instant.parse("2026-11-06T12:00:00Z")
        val s = Summary("PLAYER_SHIP_HARD_2", "PLAYER_SHIP_HARD_2", 3, 120, 25, 30, 3900)
        assertEquals("The Kestrel · sector 3 · 120 scrap · hull 25/30 · 1 h 5 min · 2 h ago", SaveWords.summaryLine(true, s, "2026-11-06T10:00:00Z", now))
        assertEquals("Empty", SaveWords.summaryLine(false, s, null, now))
        assertEquals("Run in progress", SaveWords.summaryLine(true, Summary(), null, now))
        assertEquals("My ship · 40 s · just now", SaveWords.summaryLine(true, Summary(ship = "My ship", playSeconds = 40), "2026-11-06T11:59:30Z", now))
        assertEquals("3 d ago", ftl.android.app.ui.ago("2026-11-03T12:00:00Z", now))
    }

    @Test
    fun stockShipNamesAreTheWebsites() {
        val js = web("saves-ui.mjs").substringAfter("const STOCK_SHIPS = {").substringBefore("};")
        val webShips = Regex("""(PLAYER_SHIP_\w+): '([^']+)'""").findAll(js).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(10, webShips.size)
        for ((id, name) in webShips) {
            assertEquals(id, name, ftl.android.app.ui.shipLabel(id))
            assertEquals(id, name, ftl.android.app.ui.shipLabel(id + "_3"))
        }
        assertEquals("PLAYER_SHIP_MOD", ftl.android.app.ui.shipLabel("PLAYER_SHIP_MOD"))
    }
}
