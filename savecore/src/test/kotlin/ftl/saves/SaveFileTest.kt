package ftl.saves

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveFileTest {
    private val run = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftlSaveGame difficulty=\"NORMAL\"><playerShip displayName=\"The &amp; Kestrel\" shipId=\"PLAYER_SHIP_HARD\" health=\"20\" browserMaxHealth=\"30\"><resources SCRAP=\"55\"/></playerShip><gameMap/><currentSector/><visitedSectors><sectorInfo/><sectorInfo/></visitedSectors></xftlSaveGame>"
    private val profile = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftl-profile listTutorial=\"1\"><ship id=\"A\"/></xftl-profile>"
    private val sha = "a".repeat(64)
    private val mod = ModFile("Hyperspace", "1.23", sha, 10, false)

    private fun file(vararg extra: Pair<String, Any?>) = Json.write(obj(*(listOf("schema" to 3, "kind" to "ftl-save", "name" to "My run", "run" to run, "profile" to profile) + extra).toTypedArray()))

    private fun invalid(text: String) = assertFailsWith<SaveFile.Invalid>(text.take(60)) { SaveFile.parse(text) }

    @Test
    fun aWrittenFileReadsBackTheSameRunAndProfile() {
        val meta = SaveMeta(PlayedOn("android", "Pixel", "device-secret-id", "2026-11-01T00:00:00Z"), "build", "data", Summary(ship = "X"))
        val text = SaveFile.write("My run", Setup.of(listOf(mod)), meta, run, profile, "2026-11-01T00:00:00Z")
        assertFalse("device-secret-id" in text, "the device id stays out of a file that may go to anyone")
        val p = SaveFile.parse(text)
        assertEquals(run, p.run)
        assertEquals(profile, p.profile)
        assertEquals("My run", p.name)
        assertEquals(listOf(mod), p.setup.mods)
        assertEquals(modSetHash(listOf(sha)), p.setup.key)
        assertEquals("build", p.engineBuild)
        assertEquals("data", p.gameData)
        val played = SaveMeta(summary = Summary(playSeconds = 83))
        assertEquals(83, SaveFile.parse(SaveFile.write("n", Setup.VANILLA, played, run, null, "t")).summary.playSeconds, "the play time comes along")
        assertEquals(0, SaveFile.parse(file("meta" to obj("summary" to obj("playSeconds" to -5)))).summary.playSeconds)
    }

    @Test
    fun readsTheWebsitesFilesOfEverySchema() {
        val site = """{"schema":3,"kind":"ftl-save","name":"Site","setup":{"key":"vanilla","mods":[]},"meta":{"engineBuild":"e","gameData":"g","summary":{"ship":"S"}},"run":${Json.write(Json.Str(run))},"profile":null,"savedUtc":"2026-10-30T00:00:00.000Z"}"""
        val p = SaveFile.parse(site)
        assertEquals(Setup.VANILLA, p.setup)
        assertEquals(run, p.run)
        assertNull(p.profile)
        assertEquals(run, SaveFile.parse("""{"schema":2,"kind":"ftl-save","name":"Old","run":${Json.write(Json.Str(run))}}""").run)
        val first = SaveFile.parse("""{"schema":1,"run":${Json.write(Json.Str(run))},"engineBuild":"e","assetVersion":"g"}""")
        assertEquals("Imported save", first.name)
        assertEquals("g", first.gameData)
        assertEquals(run, SaveFile.parse("﻿" + file()).run, "an editor's byte order mark does not matter")
    }

    @Test
    fun aRunTheGameCouldNeverLoadIsRefused() {
        fun withRun(r: String) = file("run" to r)
        invalid(withRun("<xftlSaveGame><playerShip displayName=\"Odd\" health=\"NaN\"/><nonsense a=\"1\"/></xftlSaveGame>"))
        invalid(withRun(run.replace("difficulty=\"NORMAL\"", "difficulty=\"SILLY\"")))
        invalid(withRun(run.replace("difficulty=\"NORMAL\"", "")))
        for (part in listOf("<gameMap/>", "<currentSector/>")) invalid(withRun(run.replace(part, "")))
        invalid(withRun(run.replace("playerShip", "otherShip")))
        invalid(withRun(run.replace("<currentSector/>", "<x><currentSector/></x>")))
        assertEquals(run, SaveFile.parse(withRun(run)).run)
        assertNull(SaveFile.parse(file("run" to null)).run, "a save between runs has no run to check")
    }

    @Test
    fun aFileIsNotTrustedAboutItsMods() {
        val p = SaveFile.parse(file("setup" to obj("key" to "../../etc", "mods" to listOf(obj("name" to "Fake", "version" to "1", "sha256" to sha.uppercase(), "size" to 5, "verified" to true)))))
        assertEquals(sha, p.setup.mods[0].sha256)
        assertFalse(p.setup.mods[0].verified, "Verified is not the file's to claim")
        assertEquals(modSetHash(listOf(sha)), p.setup.key, "the key is worked out, not read")
        val known = SaveFile.parse(file("setup" to obj("mods" to listOf(obj("name" to "Renamed", "sha256" to sha, "size" to 5)))), listOf(CatalogueFile("hs", "Hyperspace", "1.23", "f", 5, sha)))
        assertTrue(known.setup.mods[0].verified)
        assertEquals("Hyperspace", known.setup.mods[0].name)
    }

    @Test
    fun badFilesAreRefused() {
        for (bad in listOf("", "not json", "[]", "null", "{}", "{\"schema\":3}", "{\"schema\":9,\"kind\":\"ftl-save\"}", "{\"schema\":3,\"kind\":\"other\"}", "[".repeat(200000), "{\"a\":".repeat(100000))) invalid(bad)
        assertTrue("whole account" in invalid("""{"schema":3,"kind":"ftl-saves","saves":[]}""").message!!)
        // run and profile of the wrong kind or broken
        invalid(file("run" to 5))
        invalid(file("run" to "<notASave/>"))
        invalid(file("run" to "<xftlSaveGame>"))
        invalid(file("profile" to "<xftlSaveGame/>"))
        invalid(file("run" to "<!DOCTYPE x [<!ENTITY a \"b\">]><xftlSaveGame>&a;</xftlSaveGame>"))
        // mods
        invalid(file("setup" to obj("mods" to "x")))
        invalid(file("setup" to obj("mods" to listOf(obj("name" to "M", "sha256" to "short", "size" to 1)))))
        invalid(file("setup" to obj("mods" to listOf(obj("name" to "M\u0001", "sha256" to sha, "size" to 1)))))
        invalid(file("setup" to obj("mods" to listOf(obj("name" to "M", "sha256" to sha, "size" to -1)))))
        invalid(file("setup" to obj("mods" to List(65) { obj("name" to "M", "sha256" to sha, "size" to 1) })))
    }

    @Test
    fun sizeLimitsHold() {
        val big = "<xftlSaveGame>" + " ".repeat(Limits.RUN_BYTES) + "</xftlSaveGame>"
        invalid(file("run" to big))
        val parts = "<playerShip/><gameMap/><currentSector/><visitedSectors/>"
        val fits = "<xftlSaveGame difficulty=\"EASY\">$parts" + " ".repeat(Limits.RUN_BYTES - 120) + "</xftlSaveGame>"
        assertEquals(fits, SaveFile.parse(file("run" to fits)).run)
        val profileMany = "<xftl-profile>" + "<a/>".repeat(SaveFile.PROFILE_ELEMENTS + 1) + "</xftl-profile>"
        invalid(file("profile" to profileMany))
    }

    @Test
    fun anOldProfileLosesItsSettings() {
        val old = "<xftl-profile language=\"fr\" autoPause=\"true\"><hotkey action=\"a\" key=\"b\"/><ship id=\"A\"/></xftl-profile>"
        val p = SaveFile.parse(file("profile" to old)).profile!!
        assertFalse("language" in p)
        assertFalse("hotkey" in p)
        assertTrue("<ship id=\"A\"/>" in p)
    }

    @Test
    fun anImportIsANewUnsyncedSaveUnderAFreeName() {
        assertEquals("My run", SaveFile.uniqueName("My run", listOf("Other")))
        assertEquals("My run (2)", SaveFile.uniqueName("My run", listOf("My run")))
        assertEquals("My run (3)", SaveFile.uniqueName("My run", listOf("My run", "My run (2)")))
        assertEquals("Save 2", SaveFile.uniqueName(null, listOf("Save 1")))
        val long = "x".repeat(40)
        val named = SaveFile.uniqueName(long, listOf(long))
        assertEquals(40, named.length)
        assertNotEquals(long, named)

        val parsed = SaveFile.parse(file())
        val at = "2026-11-01T00:00:00Z"
        val save = SaveFile.newSave(parsed, "id-1", "My run", PlayedOn("android", "Pixel", "dev", at))
        assertEquals(0, save.base)
        assertTrue(save.dirty)
        assertEquals(run, save.run)
        assertEquals(profile, save.profile)
        assertEquals("The & Kestrel", save.meta.summary.ship)
        assertEquals(2, save.meta.summary.sector)
        assertEquals(55, save.meta.summary.scrap)
        assertEquals(SyncAction.CREATE, Sync.decide(save, null, false).action)
    }

    @Test
    fun aHugeShipNameStaysWithinTheServersMetadataLimit() {
        val huge = "<xftlSaveGame difficulty=\"HARD\"><playerShip displayName=\"${"n".repeat(20000)}\"/><gameMap/><currentSector/><visitedSectors/></xftlSaveGame>"
        val save = SaveFile.newSave(SaveFile.parse(file("run" to huge)), "id", "n", PlayedOn("android", "P", "d", "t"))
        assertTrue(Json.write(save.meta.toJson()).length < 8 * 1024)
    }

    @Test
    fun fileNames() {
        assertEquals("ftl-save-My-run.json", SaveFile.fileName("My run"))
        assertEquals("ftl-save-a-b.json", SaveFile.fileName("a/../b"))
        assertEquals("ftl-save-save.json", SaveFile.fileName("///"))
    }

    @Test
    fun xmlShape() {
        fun ok(x: String) = assertNull(XmlShape.check(x, "r", 1 shl 20), x)
        fun bad(x: String) = assertTrue(XmlShape.check(x, "r", 1 shl 20) != null, x)
        ok("<r/>")
        ok("<?xml version=\"1.0\"?><r></r>")
        ok("<r a=\"1\" b='2'><c>x &amp; y &#65; &#x41;</c><!-- note --><![CDATA[<raw>]]><?pi x?></r>\n")
        ok("<r>" + "<a>".repeat(63) + "</a>".repeat(63) + "</r>")
        ok("<r a=\"café\" né=\"日本\"/>")
        bad("")
        bad("text")
        bad(" <?xml version=\"1.0\"?><r/>")
        bad("<x/>")
        bad("<r/><r/>")
        bad("<r/>trailing")
        bad("<r>")
        bad("<r></x>")
        bad("<r a=1/>")
        bad("<r a=\"1\" a=\"2\"/>")
        bad("<r a=\"<\"/>")
        bad("<r>&nope;</r>")
        bad("<r>&</r>")
        bad("<r>&#1;</r>")
        bad("<r>\u0001</r>")
        bad("<r>]]></r>")
        bad("<r><!-- a -- b --></r>")
        bad("<r xmlns=\"urn:x\"/>")
        bad("<p:r/>")
        bad("<r p:a=\"1\"/>")
        bad("<!DOCTYPE r><r/>")
        bad("<r a=\"1\"b=\"2\"/>")
        bad("<r>" + "<a>".repeat(64) + "</a>".repeat(64) + "</r>")
        assertTrue(XmlShape.check("<r/>", "r", 3) != null, "over the character limit")
        assertTrue(XmlShape.check("<r a=\"1\" b=\"2\"/>", "r", 100, maxAttributes = 1) != null)
        assertTrue(XmlShape.check("<r><a/></r>", "r", 100, maxElements = 1) != null)
    }
}
