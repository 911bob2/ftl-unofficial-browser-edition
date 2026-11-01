package ftl.saves

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveCoreTest {
    private val vectors = File(System.getProperty("vectors") ?: "../savecore/vectors")

    private fun vector(name: String): Json.Obj = Json.parse(File(vectors, name).readText()) as Json.Obj

    @Test
    fun aRunMovedToOtherModsNamesTheNewSet() {
        val key = "a".repeat(64)
        val modded = "<?xml version=\"1.0\"?>\n<run a=\"1\"><ship/><hyperspace modSet=\"${"b".repeat(64)}\" hs=\"1.23.2\"><data key=\"x\"><hyperspace/></data></hyperspace><tail/></run>\n"
        assertEquals("<?xml version=\"1.0\"?>\n<run a=\"1\"><ship/><hyperspace modSet=\"$key\" /><tail/></run>\n", Sync.runForSetup(modded, key))
        assertEquals("<?xml version=\"1.0\"?>\n<run a=\"1\"><ship/><tail/></run>\n", Sync.runForSetup(modded, Setup.VANILLA_KEY))
        val empty = "<run><hyperspace modSet=\"${"b".repeat(64)}\" /></run>"
        assertEquals("<run><hyperspace modSet=\"$key\" /></run>", Sync.runForSetup(empty, key))
        val vanilla = "<run><ship/></run>"
        assertEquals("<run><ship/><hyperspace modSet=\"$key\" /></run>", Sync.runForSetup(vanilla, key))
        assertEquals(vanilla, Sync.runForSetup(vanilla, Setup.VANILLA_KEY))
    }

    @Test
    fun sha256MatchesNode() {
        for (case in vector("hashes.json")["sha256"].arr()) {
            val o = case as Json.Obj
            assertEquals(o["hex"].str(), Sha256.hex(o["text"].str()!!), "sha256 of " + o["text"].str()!!.take(20))
        }
    }

    @Test
    fun modSetHashMatchesBrowser() {
        for (case in vector("hashes.json")["modSets"].arr()) {
            val o = case as Json.Obj
            assertEquals(o["hash"].str(), modSetHash(o["mods"].arr().map { it.str()!! }))
        }
    }

    private fun local(j: Json?): LocalSave? {
        val o = j.objOrNull() ?: return null
        val m = LinkedHashMap<String, Json>(mapOf("id" to Json.Str("s1"), "name" to Json.Str("Save 1"), "hasRun" to Json.Bool(true)))
        m.putAll(o.fields)
        return LocalSave.fromJson(Json.Obj(m))
    }

    private fun cloud(j: Json?): CloudSave? {
        val o = j.objOrNull() ?: return null
        val m = LinkedHashMap<String, Json>(mapOf("id" to Json.Str("s1"), "name" to Json.Str("Save 1"), "hasRun" to Json.Bool(true)))
        m.putAll(o.fields)
        return CloudSave.fromJson(Json.Obj(m))
    }

    @Test
    fun syncDecisions() {
        for (case in vector("sync.json")["cases"].arr()) {
            val o = case as Json.Obj
            val d = Sync.decide(local(o["local"]), cloud(o["cloud"]), o["deleted"].bool() ?: false)
            val e = o["expect"].objOrNull()!!
            val name = o["name"].str()
            assertEquals(e["status"].str(), d.status.name, name)
            assertEquals(e["action"].str(), d.action.name, name)
            assertEquals(e["rename"].bool(), d.rename, name)
        }
    }

    @Test
    fun jsonRoundTripAndEscaping() {
        val text = """{"a":1,"b":[true,false,null],"c":"q\"\\\n\u0001é","d":1.5,"e":{}}"""
        assertEquals(text.replace("\\u0001", "\\u0001"), Json.write(Json.parse(text)))
        assertEquals("{\"n\":12345678901}", Json.write(obj("n" to 12345678901L)))
    }

    @Test
    fun uploadKeepsLaterProgressDirty() {
        val s = LocalSave("s1", "Save 1", Setup.VANILLA, "", SaveMeta(), 3, true, true, "run-a", profile = "p1", hasProfile = true)
        assertFalse(Sync.uploaded(s, "run-a", "p1", 4).dirty)
        assertTrue(Sync.uploaded(s.copy(run = "run-b"), "run-a", "p1", 4).dirty)
        assertTrue(Sync.uploaded(s.copy(profile = "p2"), "run-a", "p1", 4).dirty, "unlocks earned during the upload stay unsynced")
        assertEquals(4, Sync.uploaded(s, "run-a", "p1", 4).base)
    }

    @Test
    fun aSaveHoldsItsProfile() {
        val empty = LocalSave("s1", "Save 1", Setup.VANILLA, "", SaveMeta(), 3, false, false)
        val written = Sync.profileWritten(empty, "p1")!!
        assertTrue(written.dirty && written.hasProfile)
        assertEquals("p1", written.profile)
        assertEquals(null, Sync.profileWritten(written.copy(dirty = false), "p1"), "the same profile again changes nothing")
        val round = LocalSave.fromJson(Json.parse(Json.write(written.toJson())))
        assertEquals("p1", round.profile)
        assertEquals(null, LocalSave.fromJson(Json.parse(Json.write(written.toJson(withData = false)))).profile)
        val cloud = CloudSave("s1", "Save 1", Setup.VANILLA, "", "", 9, SaveMeta(), false, 0, null, true, 2)
        val fetched = Sync.fetched(written, cloud, null, "cloud-p")
        assertEquals("cloud-p", fetched.profile, "the cloud's copy replaces the whole save, unlocks too")
        assertFalse(fetched.dirty)
    }

    @Test
    fun oldProfilesLoseTheirSettingsOnce() {
        val decl = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
        val old = decl + "<xftl-profile achPopups=\"false\" fpsLimit=\"90\" listTutorial=\"2\" language=\"de\"><layout ship=\"S\" diff=\"EASY\" />" +
            "<hotkey action=\"jump\" key=\"j\" /><hotkey action=\"map\" /><soundVolume sfx=\"0.5\" music=\"1.0\" /><browserStats games=\"1\" />" +
            "<hyperspace modSet=\"x\"><data key=\"k\"><hotkey keep=\"yes\" /></data></hyperspace></xftl-profile>"
        val split = OldProfile.split(old)
        assertEquals(decl + "<xftl-profile listTutorial=\"2\"><layout ship=\"S\" diff=\"EASY\" /><browserStats games=\"1\" />" +
            "<hyperspace modSet=\"x\"><data key=\"k\"><hotkey keep=\"yes\" /></data></hyperspace></xftl-profile>", split.profile)
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftl-settings achPopups=\"false\" fpsLimit=\"90\" language=\"de\"><hotkey action=\"jump\" key=\"j\" />" +
            "<hotkey action=\"map\" /><soundVolume sfx=\"0.5\" music=\"1.0\" /></xftl-settings>", split.settings)
        assertEquals(OldProfile.Split(split.profile, null), OldProfile.split(split.profile), "a converted profile stays as it is")
        assertEquals("<xftl-profile />", OldProfile.split("<xftl-profile achPopups=\"true\" />").profile)
        assertEquals("<b language=\"de\"/>", OldProfile.choose(listOf("<a/>", "<b language=\"de\"/>")))
        assertEquals("<a/>", OldProfile.choose(listOf("<a/>", "<c/>")))
    }

    @Test
    fun locks() {
        val lock = LockInfo("tab-1", "dev", "Chrome on Windows", "browser", "", "")
        assertEquals(LockState.FREE, Locks.state(null, "tab-1"))
        assertEquals(LockState.MINE, Locks.state(lock, "tab-1"))
        assertEquals(LockState.OTHER, Locks.state(lock, "tab-2"))
    }

    @Test
    fun names() {
        assertEquals("Save 2", SaveNames.next(listOf("Save 1", "Save 3")))
        assertNull(SaveNames.clean("   "))
        assertNull(SaveNames.clean("a".repeat(41)))
        assertNull(SaveNames.clean("bad\u0007"))
        assertEquals("Kestrel", SaveNames.clean("  Kestrel "))
    }

    @Test
    fun catalogueClassifiesLocalFiles() {
        val cat = listOf(CatalogueFile("hyperspace-1.23.2", "Hyperspace", "1.23.2", "Hyperspace.ftl", 9950190,
            "fa8205d5be603a4f01e1fc081097d1cb24b5a13588e403e65e6ff3a4f5173fa8"))
        val v = Catalogue.classify(cat, "C:\\mods\\hs.zip", "fa8205d5be603a4f01e1fc081097d1cb24b5a13588e403e65e6ff3a4f5173fa8", 9950190)
        assertTrue(v.verified)
        assertEquals("Hyperspace", v.name)
        val c = Catalogue.classify(cat, "/x/My Mod.ftl", "a".repeat(64), 10)
        assertFalse(c.verified)
        assertEquals("My Mod.ftl", c.name)
        assertEquals(listOf(c), missingMods(Setup.of(listOf(v, c)), setOf(v.sha256)))
        assertEquals(modSetHash(listOf(v.sha256, c.sha256)), Setup.of(listOf(v, c)).key)
    }

    @Test
    fun runSummary() {
        val run = """<?xml version="1.0"?><!-- c --><xftlSaveGame difficulty="NORMAL"><playerShip shipId="PLAYER_SHIP_HARD" displayName="The Kestrel &amp; Co" health="27" browserMaxHealth="30"><resources FUEL="16" SCRAP="112"><item name="x"/></resources></playerShip>
            <visitedSectors><sectorInfo a="1"/><sectorInfo a="2"/><sectorInfo a="3"/></visitedSectors></xftlSaveGame>"""
        val s = RunSummary.read(run, 95)
        assertEquals(Summary("The Kestrel & Co", "PLAYER_SHIP_HARD", 3, 112, 27, 30, 95), s)
        assertEquals(Summary(playSeconds = 4), RunSummary.read("<broken", 4))
        assertEquals(Summary(), RunSummary.read(null))
    }

    @Test
    fun metaKeepsUnknownFields() {
        val meta = SaveMeta.fromJson(Json.parse("""{"migratedFrom":"slot 2","engineBuild":"b","summary":{"sector":4}}"""))
        assertEquals("b", meta.engineBuild)
        assertEquals(4, meta.summary.sector)
        assertEquals("slot 2", meta.toJson()["migratedFrom"].str())
    }
}
