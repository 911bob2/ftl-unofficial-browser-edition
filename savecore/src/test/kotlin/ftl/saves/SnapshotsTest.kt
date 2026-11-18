package ftl.saves

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotsTest {
    @Test
    fun theListReadsNewestFirstAndSkipsWhatDoesNotRead() {
        val list = Snapshots.parse(Json.parse("""[{"revision":3,"createdUtc":"2026-11-04T10:00:00Z","kind":"auto"},
            {"revision":5,"createdUtc":"2026-11-04T11:00:00","kind":"restore"},{"createdUtc":"x"},7,
            {"revision":4,"createdUtc":"2026-11-04T10:30:00+01:00","kind":"manual"}]"""))
        assertEquals(listOf(5L, 4L, 3L), list.map { it.revision })
        assertEquals("2026-11-04T11:00:00Z", list[0].createdUtc, "a time without a zone is UTC")
        assertEquals("2026-11-04T10:30:00+01:00", list[1].createdUtc)
        assertEquals(emptyList(), Snapshots.parse(Json.parse("{}")))
        assertEquals(emptyList(), Snapshots.parse(null))
    }

    @Test
    fun everySnapshotButTheCurrentOneRestoresWhenTheSaveIsNotPlayed() {
        val list = listOf(Snapshot(5, "", "auto"), Snapshot(4, "", "auto"))
        assertEquals(6L, Snapshots.current(list, 6))
        assertEquals(5L, Snapshots.current(list, null))
        assertNull(Snapshots.current(emptyList(), null))
        assertTrue(Snapshots.canRestore(list[0], 6, playing = false))
        assertFalse(Snapshots.canRestore(list[0], 5, playing = false))
        assertFalse(Snapshots.canRestore(list[1], 6, playing = true))
        assertEquals("save", Snapshots.kindLabel(""))
    }

    @Test
    fun aRestoreReplacesUnsyncedProgressAndClearsATakeover() {
        val local = LocalSave("s", "Mine", Setup.VANILLA, "2026-01-01T00:00:00Z", SaveMeta(), base = 4, dirty = true, hasRun = true, run = "<xftlSaveGame/>",
            overridden = Overridden("Laptop", "browser", "2026-11-04T09:00:00Z"))
        val cloud = CloudSave.fromJson(Json.parse("""{"id":"s","name":"Cloud","setup":{"key":"vanilla","mods":[]},"createdUtc":"2026-01-01T00:00:00Z",
            "updatedUtc":"2026-11-04T10:00:00Z","revision":7,"meta":{},"hasRun":true}"""))
        val restored = Snapshots.restored(local, cloud, "<xftlSaveGame old=\"1\"/>", null)
        assertEquals(7, restored.base)
        assertFalse(restored.dirty)
        assertNull(restored.overridden)
        assertEquals("<xftlSaveGame old=\"1\"/>", restored.run)
        assertEquals("Cloud", restored.name)
        assertEquals("Mine", Snapshots.restored(local.copy(nameDirty = true), cloud, null, null).name, "a rename not yet synced stays")
        assertEquals(7, Snapshots.restored(null, cloud, null, null).base)
    }

    @Test
    fun aSnapshotDownloadsAsTheWebsitesSaveFile() {
        val run = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftlSaveGame difficulty=\"NORMAL\"><playerShip displayName=\"Kestrel\" shipId=\"PLAYER_SHIP_HARD\" health=\"20\"><resources SCRAP=\"55\"/></playerShip><gameMap/><currentSector/><visitedSectors><sectorInfo/></visitedSectors></xftlSaveGame>"
        val payload = obj("name" to "Kestrel run", "setup" to Setup.VANILLA.toJson(), "meta" to obj("engineBuild" to "b1",
            "playedOn" to obj("platform" to "web", "device" to "Laptop", "deviceId" to "d-1", "at" to "2026-11-04T10:00:00Z")),
            "run" to run, "profile" to null, "revision" to 7)
        val text = Snapshots.file(payload, "2026-11-18T12:00:00Z")!!
        val o = Json.parse(text).objOrNull()!!
        assertEquals(3, o["schema"].long()?.toInt())
        assertEquals("ftl-save", o["kind"].str())
        assertEquals("Kestrel run", o["name"].str())
        assertEquals(run, o["run"].str())
        assertEquals("2026-11-18T12:00:00Z", o["savedUtc"].str())
        assertEquals("b1", o["meta"].objOrNull()?.get("engineBuild").str())
        // The file goes anywhere, so the device that played it stays out, as for an exported save.
        assertNull(o["meta"].objOrNull()?.get("playedOn").objOrNull())
        // It imports back as a save.
        val parsed = SaveFile.parse(text)
        assertEquals("Kestrel run", parsed.name)
        assertEquals(run, parsed.run)
        assertEquals("ftl-snapshot-7.json", Snapshots.fileName(7))
        assertNull(Snapshots.file(Json.parse("""{"error":"x"}"""), "t"))
        assertNull(Snapshots.file(null, "t"))
    }
}
