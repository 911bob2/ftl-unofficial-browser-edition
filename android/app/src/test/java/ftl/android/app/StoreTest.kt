package ftl.android.app

import ftl.android.app.data.DatFile
import ftl.android.app.data.InvalidDat
import ftl.android.app.store.DeviceStore
import ftl.saves.LocalSave
import ftl.saves.SaveMeta
import ftl.saves.Setup
import ftl.saves.Sync
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val dir: File = Files.createTempDirectory("ftl-store").toFile()

    private fun save(id: String = "0f8fad5b-d9cb-469f-a165-70867728950e") =
        LocalSave(id, "Save 1", Setup.VANILLA, "2026-10-29T10:00:00Z", SaveMeta(), 0, false, false)

    @Test
    fun accountsAreKeptApartAndOfflineSavesJoinAnAccount() {
        // Files from before accounts were kept apart belong to whoever is signed in at the first start.
        File(dir, "saves").mkdirs()
        val old = save("1b4e28ba-2fa1-11d2-883f-0016d3cca427")
        DeviceStore.atomicWrite(File(dir, "saves/${old.id}.json"), ftl.saves.Json.write(old.toJson(withData = false)))
        val alice = DeviceStore.scopeOf("https://ftl.example", "alice-id")
        val store = DeviceStore(dir, alice)
        assertEquals(listOf(old.id), store.list().map { it.id })
        assertFalse(File(dir, "saves").exists())

        store.scope = DeviceStore.LOCAL
        assertTrue(store.list().isEmpty(), "play without an account sees none of the account's saves")
        val offline = save()
        store.put(Sync.profileWritten(Sync.written(offline, "<xftlSaveGame/>", SaveMeta()), "<xftl-profile/>")!!)
        store.update(offline.id) { Sync.uploaded(it, "<xftlSaveGame/>", null, 4) }

        store.scope = DeviceStore.scopeOf("https://ftl.example", "bob-id")
        assertTrue(store.list().isEmpty(), "another account sees neither")
        assertEquals(1, store.adopt(DeviceStore.LOCAL))
        val adopted = store.get(offline.id)!!
        assertEquals(0, adopted.base, "an adopted save is new to the account")
        assertTrue(adopted.dirty)
        assertEquals("<xftlSaveGame/>", adopted.run)
        assertEquals("<xftl-profile/>", adopted.profile, "the unlocks move with the save")
        assertTrue(store.savesOf(DeviceStore.LOCAL).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.scope = "../escape" }
    }

    @Test
    fun savesRoundTripWithRunsApart() {
        val store = DeviceStore(dir, DeviceStore.LOCAL)
        val s = save()
        store.put(Sync.written(s, "<xftlSaveGame/>", SaveMeta()))
        val back = store.get(s.id)!!
        assertEquals("<xftlSaveGame/>", back.run)
        assertTrue(back.dirty)
        assertNull(store.list().single().run, "the list never loads runs")
        store.update(s.id) { Sync.uploaded(it, "<xftlSaveGame/>", null, 3) }
        assertEquals(3, store.get(s.id)!!.base)
        assertFalse(store.get(s.id)!!.dirty)
        store.delete(s.id)
        assertNull(store.get(s.id))
    }

    @Test
    fun refusesUnsafeIds() {
        val store = DeviceStore(dir, DeviceStore.LOCAL)
        assertFailsWith<IllegalArgumentException> { store.put(save("../x")) }
        assertNull(store.get("../x"))
    }

    @Test
    fun aSaveHoldsItsProfileAndSettingsStayApart() {
        val store = DeviceStore(dir, DeviceStore.LOCAL)
        val s = save()
        store.put(s)
        store.update(s.id) { Sync.profileWritten(it, "<xftl-profile/>") }
        val back = store.get(s.id)!!
        assertEquals("<xftl-profile/>", back.profile)
        assertTrue(back.hasProfile && back.dirty)
        assertNull(store.list().single().profile, "the list never loads profiles")
        store.update(s.id) { Sync.uploaded(it, null, "<xftl-profile/>", 2) }
        assertNull(store.update(s.id) { Sync.profileWritten(it, "<xftl-profile/>") }?.takeIf { it.dirty }, "the same profile again leaves the save synced")

        assertNull(store.settings())
        store.putSettings("<xftl-settings language=\"de\"/>")
        store.scope = DeviceStore.scopeOf("https://ftl.example", "bob-id")
        assertEquals("<xftl-settings language=\"de\"/>", store.settings(), "settings are the device's, not the account's")
        store.scope = DeviceStore.LOCAL
        store.delete(s.id)
        assertFalse(File(dir, "accounts/${DeviceStore.LOCAL}/saves/${s.id}.profile.xml").exists())
    }

    @Test
    fun oldProfilesBecomePartOfEverySave() {
        val account = DeviceStore.scopeOf("https://ftl.example", "alice-id")
        fun folder(scope: String) = File(dir, "accounts/$scope").apply { File(this, "saves").mkdirs(); File(this, "profiles").mkdirs() }
        fun old(scope: String, s: LocalSave) = DeviceStore.atomicWrite(File(folder(scope), "saves/${s.id}.json"), ftl.saves.Json.write(s.toJson(withData = false)))
        fun profile(scope: String, key: String, current: String?, engineBase: String?, cloud: String?) = DeviceStore.atomicWrite(File(folder(scope), "profiles/$key.json"),
            ftl.saves.Json.write(ftl.saves.obj("setupKey" to key, "current" to current, "engineBase" to engineBase, "cloud" to cloud, "revision" to 3L)))
        val modded = "a".repeat(64)
        val moddedSetup = Setup(modded, listOf(ftl.saves.ModFile("M", "", modded, 1, true)))
        val first = save("10000000-0000-0000-0000-000000000001").copy(base = 5)
        val second = save("10000000-0000-0000-0000-000000000002").copy(base = 6)
        val third = save("10000000-0000-0000-0000-000000000003").copy(base = 7, setup = moddedSetup)
        val never = save("10000000-0000-0000-0000-000000000004").copy(base = 8, setup = Setup("b".repeat(64), listOf(ftl.saves.ModFile("N", "", "b".repeat(64), 1, true))))
        for (s in listOf(first, second, third, never)) old(account, s)
        profile(account, "vanilla", "<xftl-profile v=\"base\"/>", "<xftl-profile v=\"base\"/>", "<xftl-profile v=\"cloud\"/>")
        profile(account, modded, "<xftl-profile v=\"new\"/>", "<xftl-profile v=\"base\"/>", "<xftl-profile v=\"cloud\"/>")
        profile(DeviceStore.LOCAL, "vanilla", "<xftl-profile v=\"offline\" language=\"de\" achPopups=\"false\"><soundVolume sfx=\"0.5\" music=\"1.0\" /></xftl-profile>", null, null)

        val store = DeviceStore(dir, account)
        assertEquals("<xftl-profile v=\"cloud\"/>", store.get(first.id)!!.profile, "a synced profile: the account's version")
        assertEquals("<xftl-profile v=\"cloud\"/>", store.get(second.id)!!.profile, "every save of a setup gets a copy")
        assertFalse(store.get(first.id)!!.dirty)
        assertEquals("<xftl-profile v=\"new\"/>", store.get(third.id)!!.profile, "an unsynced profile stays this device's")
        assertTrue(store.get(third.id)!!.dirty, "and uploads with the save")
        assertNull(store.get(never.id)!!.profile, "a setup that never played starts fresh")
        assertEquals(4, store.list().size)
        assertFalse(File(dir, "accounts/$account/profiles").exists())

        store.scope = DeviceStore.LOCAL
        val made = store.list().single()
        assertEquals("<xftl-profile v=\"offline\"></xftl-profile>", store.get(made.id)!!.profile, "a profile with no save becomes one: its unlocks stay, without the settings")
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xftl-settings language=\"de\" achPopups=\"false\"><soundVolume sfx=\"0.5\" music=\"1.0\" /></xftl-settings>", store.settings(), "and the settings became the device's")
        assertEquals("Save 1", made.name)

        // A second start finds nothing to do: a save made since does not pick up an old profile.
        val later = save("10000000-0000-0000-0000-000000000005")
        store.put(later)
        val again = DeviceStore(dir, DeviceStore.LOCAL)
        assertNull(again.get(later.id)!!.profile)
    }

    @Test
    fun pendingReleases() {
        val store = DeviceStore(dir, DeviceStore.LOCAL)
        store.setPendingRelease("abc", true)
        store.setPendingRelease("def", true)
        store.setPendingRelease("abc", false)
        assertEquals(setOf("def"), store.pendingReleases())
    }

    private fun pkg(entries: List<Pair<String, ByteArray>>): File {
        val names = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for ((n, _) in entries) { offsets.add(names.size()); names.write(n.encodeToByteArray()); names.write(0) }
        val headerEnd = 16 + entries.size * 20 + names.size()
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(0x504b470a); d.writeShort(16); d.writeShort(20); d.writeInt(entries.size); d.writeInt(names.size())
        var offset = headerEnd
        for ((i, e) in entries.withIndex()) {
            d.writeInt(0); d.writeInt(offsets[i]); d.writeInt(offset); d.writeInt(e.second.size); d.writeInt(e.second.size)
            offset += e.second.size
        }
        d.write(names.toByteArray())
        for ((_, b) in entries) d.write(b)
        return File(dir, "test.dat").apply { writeBytes(out.toByteArray()) }
    }

    @Test
    fun readsPkgLikeTheWebsite() {
        val f = pkg(listOf("data/a.xml" to "<a/>".encodeToByteArray(), "audio/x.ogg" to byteArrayOf(1, 2, 3)))
        DatFile(f).use { dat ->
            assertEquals(setOf("data/a.xml", "audio/x.ogg"), dat.entries.keys)
            assertEquals("<a/>", dat.read("data/a.xml")!!.decodeToString())
            assertEquals(3, dat.read("audio/x.ogg")!!.size)
        }
        assertFailsWith<InvalidDat> { DatFile.index(pkg(listOf("../evil" to byteArrayOf(1)))) }
        assertFailsWith<InvalidDat> { DatFile.index(pkg(listOf("a" to byteArrayOf(1), "a" to byteArrayOf(2)))) }
        assertFailsWith<InvalidDat> { DatFile.index(File(dir, "short.dat").apply { writeBytes(ByteArray(8)) }) }
    }
}
