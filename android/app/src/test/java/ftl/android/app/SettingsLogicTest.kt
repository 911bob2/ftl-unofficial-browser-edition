package ftl.android.app

import ftl.android.app.icons.IconRules
import ftl.android.app.log.Redact
import ftl.android.app.log.RotatingLog
import ftl.android.app.settings.GameLanguage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsLogicTest {
    @Test
    fun randomAlwaysChangesTheIconAndReachesThemAll() {
        val numbers = listOf("01", "02", "05")
        val random = Random(7)
        val seen = HashSet<String>()
        var current = "02"
        repeat(200) {
            val next = IconRules.nextRandom(current, numbers, random)
            assertTrue(next != current)
            assertTrue(next in numbers)
            seen.add(next)
            current = next
        }
        assertEquals(numbers.toSet(), seen)
        assertEquals("02", IconRules.nextRandom("02", listOf("02")))
    }

    @Test
    fun theChosenIconIsWhatIsEnabledAndAnInterruptedSwitchRepairs() {
        assertEquals("17", IconRules.chosen(listOf("17"), null))
        assertEquals("17", IconRules.chosen(listOf("17"), "05"), "one entry is the truth, whatever was wanted")
        assertEquals("05", IconRules.chosen(listOf("02", "05"), "05"), "two entries: the switch under way wins")
        assertEquals("05", IconRules.chosen(listOf("02", "05"), null), "two entries, nothing recorded: not the default")
        assertEquals(IconRules.DEFAULT, IconRules.chosen(emptyList(), null))
    }

    @Test
    fun whereIconsAreOffered() {
        assertTrue(IconRules.supported(hasHomeScreen = true, tv = false, watch = false, car = false, refused = false))
        assertFalse(IconRules.supported(hasHomeScreen = false, tv = false, watch = false, car = false, refused = false))
        assertFalse(IconRules.supported(hasHomeScreen = true, tv = true, watch = false, car = false, refused = false))
        assertFalse(IconRules.supported(hasHomeScreen = true, tv = false, watch = true, car = false, refused = false))
        assertFalse(IconRules.supported(hasHomeScreen = true, tv = false, watch = false, car = true, refused = false))
        assertFalse(IconRules.supported(hasHomeScreen = true, tv = false, watch = false, car = false, refused = true))
        assertTrue(IconRules.homeTested("com.google.android.apps.nexuslauncher"))
        assertFalse(IconRules.homeTested("com.other.launcher"))
        assertFalse(IconRules.homeTested(null))
    }

    @Test
    fun theLogKeepsNoCredentials() {
        val secrets = listOf(
            "Cookie: .ftl.auth=SECRETVALUE; other=SECRETVALUE",
            "set-cookie: ftl_session=SECRETVALUE; Path=/",
            "Set-Cookie=SECRETVALUE",
            "{\"username\":\"bob\",\"password\":\"SECRETVALUE\"}",
            "login password=SECRETVALUE&next=1",
            "pwd: SECRETVALUE",
            "X-CSRF-TOKEN: SECRETVALUE",
            "token=SECRETVALUE",
            "\"csrfToken\" : \"SECRETVALUE\"",
            "api_key=SECRETVALUE",
            "Authorization: Bearer SECRETVALUE",
            "bearer SECRETVALUE123",
            "GET https://ftl.example/api/x?code=SECRETVALUE failed",
            "open https://mega.nz/file/abc#SECRETVALUE",
            "https://bob:SECRETVALUE@example.org/x",
            "session=SECRETVALUE",
            "__Host-ftl=SECRETVALUE; Path=/",
            "sent ftl-local-5598=SECRETVALUE and more",
            "sessionId: SECRETVALUE",
        )
        for (s in secrets) assertFalse("SECRETVALUE" in Redact.line(s), Redact.line(s))
        assertFalse("bob@example.org" in Redact.line("signed in as bob@example.org"))
        val opaque = "Zm9vYmFyYmF6cXV4Zm9vYmFyYmF6cXV4Zm9vYmFyYmF6cXV4"
        assertFalse(opaque in Redact.line("value $opaque end"), "a long opaque value is a secret whatever it is called")
    }

    @Test
    fun theLogKeepsWhatHelps() {
        val sha = "a".repeat(64)
        for (s in listOf(
            "11-01 08:05:32.325  5411  5411 I FTL-Icon: App icon 02 -> 17",
            "java.lang.IllegalStateException: boom",
            "\tat ftl.android.app.game.GameRenderer.fail(GameRenderer.kt:178)",
            "Sync of 0f8fad5b-d9cb-469f-a165-70867728950e failed",
            "Missing mod: Hyperspace, $sha",
            "GET https://ftl.example/api/saves failed",
        )) assertEquals(s, Redact.line(s))
        assertEquals("a\nb", Redact.text("a\nb"))
    }

    @Test
    fun theLogIsBoundedToTwoFiles() {
        val dir: File = Files.createTempDirectory("ftl-log").toFile()
        val log = RotatingLog(File(dir, "a"), File(dir, "b"), 100)
        repeat(1000) { log.append("line $it padded out somewhat") }
        assertTrue(log.bytes <= 2 * (100 + 40), "kept ${log.bytes}")
        val out = ByteArrayOutputStream()
        log.copyTo(out)
        val text = out.toString()
        assertTrue("line 999 " in text, "the newest line is kept")
        assertFalse("line 0 " in text, "the oldest is gone")
        val lines = text.trim().lines().map { it.removePrefix("line ").substringBefore(' ').toInt() }
        assertEquals(lines.sorted(), lines, "oldest first")
        assertTrue(log.lastWritten > 0)
        log.clear()
        assertEquals(0, log.bytes)
        assertEquals(0, log.lastWritten)
        assertEquals(setOf<String>(), dir.list()!!.toSet())
    }

    @Test
    fun theLanguageIsChangedAndNothingElse() {
        val doc = "<?xml version=\"1.0\"?>\n<xftl-settings achPopups=\"true\" language=\"fr\" fpsLimit=\"60\"><hotkey action=\"a\" key=\"language=&quot;x&quot;\"/><soundVolume sfx=\"1\" music=\"0.5\"/></xftl-settings>"
        assertEquals("fr", GameLanguage.read(doc))
        val next = GameLanguage.write(doc, "de")
        assertEquals("de", GameLanguage.read(next))
        assertEquals(doc.replace("language=\"fr\"", "language=\"de\""), next)

        val none = "<xftl-settings achPopups=\"true\"><hotkey action=\"a\" key=\"b\"/></xftl-settings>"
        assertNull(GameLanguage.read(none))
        assertEquals("<xftl-settings language=\"\" achPopups=\"true\"><hotkey action=\"a\" key=\"b\"/></xftl-settings>", GameLanguage.write(none, ""))
        assertEquals("", GameLanguage.read(GameLanguage.write(none, "")))

        assertNull(GameLanguage.read(null))
        assertEquals("ja", GameLanguage.read(GameLanguage.write(null, "ja")))
        assertEquals("English", GameLanguage.label(""))
        assertEquals("English", GameLanguage.label("en"))
        assertEquals("ko", GameLanguage.label("ko"), "a mod's language shows as it is")
        assertTrue("first" in GameLanguage.label(null))
    }
}
