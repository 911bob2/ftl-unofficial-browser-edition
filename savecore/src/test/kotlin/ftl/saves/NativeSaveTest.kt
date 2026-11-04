package ftl.saves

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic files in the original game's layout, written here (no real saves in Git). */
internal class NativeBytes {
    private val out = java.io.ByteArrayOutputStream()
    fun int(v: Int) = apply { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
    fun str(s: String) = apply { val b = s.encodeToByteArray(); int(b.size); out.write(b) }
    fun bytes(): ByteArray = out.toByteArray()

    companion object {
        /** The start of a run up to the scrap, which is all savecore reads. */
        fun run(hyperspace: Boolean = false, sector: Int = 3, scrap: Int = 275, hull: Int = 29, name: String = "The Kestrel", blueprint: String = "PLAYER_SHIP_HARD") =
            NativeBytes().int(11).int(0).int(1).int(1).int(9).int(18).int(267).int(3).str(name).str(blueprint).int(sector).int(0)
                .int(1).str("env_danger").int(2)
                .apply { if (hyperspace) int(192).int(1).int(4) }
                .str(blueprint).str(name).str("kestral").int(1).str("human").str("Ohm").int(1).int(85000).int(0).int(0)
                .int(hull).int(15).int(10).int(12).int(scrap).bytes()

        /** A profile with one achievement, one victory and Kestrel A unlocked. */
        fun profile(hyperspace: Boolean = false): ByteArray {
            val b = NativeBytes().int(9).int(2).int(2).str("ACH_SECTOR_5").int(1).str("PLAYER_SHIP_HARD_VICTORY").int(1).int(1).int(-1).int(-1)
            if (hyperspace) {
                b.int(1).str("PLAYER_SHIP_MVKESTREL") // custom unlocked ships
                b.int(0).int(0) // quest ships, ship victories
                b.int(1).str("nexus").int(1).str("PLAYER_SHIP_MVKESTREL").int(0) // custom victories
                b.int(1).str("ACH_MV").int(2) // custom achievements
                b.int(0) // custom ship scores
                b.int(2).str("prof_hotkey_toggle").int(91).str("loc_x").int(0) // meta variables
            }
            repeat(24) { b.int(if (it == 0) 1 else 0) }
            b.int(1).str("The Kestrel").str("PLAYER_SHIP_HARD").int(1200).int(5).int(0).int(1).int(1) // top scores
            // Each ship's best: one list, as the original writes it (a game over with Hyperspace, st-hs-dead).
            b.int(1).str("The Kestrel").str("PLAYER_SHIP_HARD").int(1200).int(5).int(0).int(1).int(1)
            repeat(10) { b.int(it) }
            repeat(5) { b.int(it).str(if (it == 3) "Ohm" else "").str(if (it == 3) "human" else "").int(1) }
            if (hyperspace) repeat(12) { b.int(0) } // layout B unlocks
            return b.bytes()
        }
    }
}

class NativeSaveTest {
    private fun invalid(name: String, bytes: ByteArray) = assertFailsWith<NativeSave.Invalid>(name) { NativeSave.identify(name, bytes) }

    @Test
    fun namesGiveTheKindAndThePrefix() {
        assertEquals(NativeSave.Kind.RUN to "", NativeSave.nameOf("continue.sav"))
        assertEquals(NativeSave.Kind.PROFILE to "", NativeSave.nameOf("C:\\Users\\a\\Documents\\My Games\\FasterThanLight\\ae_prof.sav"))
        assertEquals(NativeSave.Kind.PROFILE to "hs", NativeSave.nameOf("hs_prof.sav"))
        assertEquals(NativeSave.Kind.RUN to "hs_mv", NativeSave.nameOf("hs_mv_continue.sav"))
        assertEquals(NativeSave.Kind.RUN to "hs_mv", NativeSave.nameOf("HS_MV_continue (2).sav"), "a browser's renamed download")
        assertNull(NativeSave.nameOf("hs_mv_prof_backup.sav"))
        assertNull(NativeSave.nameOf("hs_mv_version.sav"))
        assertNull(NativeSave.nameOf("settings.ini"))
        assertEquals("multiverse", NativeSave.setupFor("hs_mv"))
        assertEquals("hyperspace", NativeSave.setupFor("hs"))
        assertEquals("vanilla", NativeSave.setupFor(""))
        assertEquals("other", NativeSave.setupFor("hs_tcc"))
    }

    @Test
    fun aVanillaRunReadsItsSummary() {
        val f = NativeSave.identify("continue.sav", NativeBytes.run())
        assertEquals(NativeSave.Kind.RUN, f.kind)
        assertEquals("", f.prefix)
        assertEquals(Summary("The Kestrel", "PLAYER_SHIP_HARD", 3, 275, 29, null), f.summary)
    }

    @Test
    fun aHyperspaceRunHasTheExtraIdsBeforeTheShip() {
        val bytes = NativeBytes.run(hyperspace = true, blueprint = "PLAYER_SHIP_MVKESTREL", name = "Superluminal")
        val f = NativeSave.identify("hs_mv_continue.sav", bytes)
        assertEquals("hs_mv", f.prefix)
        assertEquals("Superluminal", f.summary?.ship)
        assertEquals(275, f.summary?.scrap)
        invalid("continue.sav", bytes) // read as a run without mods, the ship section does not start where it should
        assertEquals(NativeSave.UNKNOWN_PREFIX, NativeSave.identify("my run.sav", bytes).prefix, "a renamed Hyperspace run")
        assertEquals("", NativeSave.identify("my run.sav", NativeBytes.run()).prefix, "a renamed run without mods")
    }

    @Test
    fun profilesAreWalkedToTheirEnd() {
        assertEquals(NativeSave.Kind.PROFILE, NativeSave.identify("ae_prof.sav", NativeBytes.profile()).kind)
        assertEquals("hs_mv", NativeSave.identify("hs_mv_prof.sav", NativeBytes.profile(hyperspace = true)).prefix)
        invalid("ae_prof.sav", NativeBytes.profile(hyperspace = true))
        invalid("hs_prof.sav", NativeBytes.profile())
        invalid("ae_prof.sav", NativeBytes.profile() + byteArrayOf(0, 0, 0, 0))
        assertEquals(NativeSave.UNKNOWN_PREFIX, NativeSave.identify("profile.sav", NativeBytes.profile(hyperspace = true)).prefix)
        val old = NativeBytes().int(4).int(0).bytes()
        assertTrue(invalid("prof.sav", old).message!!.contains("ae_prof.sav"))
    }

    @Test
    fun garbageNeverCrashes() {
        val random = java.util.Random(7)
        for (size in listOf(0, 3, 8, 64, 1000, 20000)) {
            repeat(30) {
                val bytes = ByteArray(size).also { random.nextBytes(it) }
                if (size >= 4) { bytes[0] = (if (it % 2 == 0) 11 else 9).toByte(); bytes[1] = 0; bytes[2] = 0; bytes[3] = 0 }
                for (name in listOf("continue.sav", "ae_prof.sav", "hs_mv_continue.sav", "hs_mv_prof.sav", "x.sav"))
                    try { NativeSave.identify(name, bytes) } catch (e: NativeSave.Invalid) { /* the expected outcome */ }
            }
        }
        // Every truncation of a valid file is refused, never read past its end.
        val run = NativeBytes.run()
        for (n in 0 until run.size) invalid("continue.sav", run.copyOf(n))
        val profile = NativeBytes.profile(true)
        // Without the last 48 bytes it is a Hyperspace profile from before the layout B unlocks were saved.
        for (n in 0 until profile.size) if (n != profile.size - 48) invalid("hs_mv_prof.sav", profile.copyOf(n))
        invalid("ae_prof.sav", ByteArray(NativeSave.PROFILE_BYTES + 1).also { it[0] = 9 })
        invalid("continue.sav", ByteArray(NativeSave.RUN_BYTES + 1).also { it[0] = 11 })
        assertTrue(invalid("continue.sav", NativeBytes.profile()).message!!.startsWith("This is not a save file"), "a profile named as a run")
        assertTrue(invalid("continue.sav", NativeBytes().int(7).int(0).bytes()).message!!.contains("old version"))
    }

    @Test
    fun picksOneRunAndOneProfileOfOneGame() {
        val run = NativeSave.identify("hs_mv_continue.sav", NativeBytes.run(hyperspace = true))
        val profile = NativeSave.identify("hs_mv_prof.sav", NativeBytes.profile(hyperspace = true))
        val picked = NativeSave.pick(listOf(profile, run))
        assertEquals("hs_mv", picked.prefix)
        assertEquals("multiverse", picked.setup)
        assertEquals(run, picked.run)
        assertEquals(profile, picked.profile)
        assertEquals("hs_mv", NativeSave.pick(listOf(NativeSave.identify("prof copy.sav", NativeBytes.profile(true)), run)).prefix, "an unnamed Hyperspace file joins a named one")
        val vanilla = NativeSave.identify("continue.sav", NativeBytes.run())
        assertTrue(assertFailsWith<NativeSave.Invalid> { NativeSave.pick(listOf(vanilla, profile)) }.message!!.contains("different games"))
        assertFailsWith<NativeSave.Invalid> { NativeSave.pick(listOf(vanilla, vanilla)) }
        assertFailsWith<NativeSave.Invalid> { NativeSave.pick(emptyList()) }
        assertNull(NativeSave.pick(listOf(profile)).run)
    }

    @Test
    fun aWrappedRunSyncsAndShowsItsSummaryUntilTheEngineConvertsIt() {
        val picked = NativeSave.pick(listOf(NativeSave.identify("continue.sav", NativeBytes.run(name = "A <&> \"B\""))))
        val run = picked.wrappedRun!!
        assertNull(XmlShape.check(run, "xftlSaveGame", Limits.RUN_BYTES), "the server takes it")
        assertNull(RunShape.check(run), "a backup of it imports again")
        assertTrue(NativeSave.isWrapped(run))
        assertFalse(NativeSave.isWrapped("<xftlSaveGame difficulty=\"NORMAL\"><playerShip/></xftlSaveGame>"))
        assertEquals(Summary("A <&> \"B\"", "PLAYER_SHIP_HARD", 3, 275, 29, null, 40), RunSummary.read(run, 40))
        val profile = NativeSave.pick(listOf(NativeSave.identify("ae_prof.sav", NativeBytes.profile()))).wrappedProfile!!
        assertNull(XmlShape.check(profile, "xftl-profile", SaveFile.PROFILE_CHARS, SaveFile.PROFILE_ELEMENTS, SaveFile.PROFILE_ATTRIBUTES))
        assertTrue(NativeSave.isWrapped(profile))
        // The website's backup of an imported save reads back the same.
        val text = SaveFile.write("Imported", Setup.VANILLA, SaveMeta(), run, profile, "2026-11-03T00:00:00Z")
        val back = SaveFile.parse(text)
        assertEquals(run, back.run)
        assertEquals(profile, back.profile)
        assertEquals(275, back.summary.scrap)
    }

    @Test
    fun theNewSaveTakesTheSetupThePrefixAsksFor() {
        val catalogue = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "web/mods/catalogue.json") }.first { it.exists() }
        val presets = NativeSave.presets(catalogue.readText())
        assertEquals(listOf("multiverse", "hyperspace"), presets.map { it.id })
        assertTrue(presets.all { it.hyperspace && it.setup.mods.all { m -> m.verified } })
        assertEquals(3, presets[0].setup.mods.size)
        assertEquals("multiverse", NativeSave.presetFor("hs_mv", presets)?.id)
        assertEquals("hyperspace", NativeSave.presetFor("hs", presets)?.id)
        assertNull(NativeSave.presetFor("", presets))
        assertNull(NativeSave.presetFor("hs_other", presets))
        assertEquals(emptyList(), NativeSave.presets("not json"))
        assertEquals(emptyList(), NativeSave.presets("{\"files\":[],\"presets\":[{\"id\":\"x\",\"files\":[\"missing\"]}]}"))

        val picked = NativeSave.pick(listOf(NativeSave.identify("hs_mv_continue.sav", NativeBytes.run(hyperspace = true)),
            NativeSave.identify("prof copy.sav", NativeBytes.profile(hyperspace = true))))
        val at = "2026-11-04T10:00:00Z"
        val save = picked.newSave("id-1", "FTL: The Kestrel", presets[0].setup, PlayedOn("android", "Phone", "dev", at))
        assertEquals(presets[0].setup, save.setup)
        assertTrue(save.dirty && save.hasRun && save.hasProfile && save.base == 0L)
        assertEquals(275, save.meta.summary.scrap)
        assertEquals(Json.Str("hs_mv"), save.meta.extra["importedFrom"])
        assertTrue(save.profile!!.contains("prefix=\"hs_mv\""), "a file without the prefix in its name takes the pair's prefix")
        assertEquals(save, LocalSave.fromJson(Json.parse(Json.write(save.toJson()))), "the device store keeps it as it is")
    }

    /**
     * NATIVE_SAVES=<folder>: every profile and run below it that the original game wrote (real captures stay out of Git,
     * docs/TESTS.md) is identified and summed up. Skipped without the variable.
     */
    @Test
    fun realFilesFromTheOriginalGame() {
        val root = System.getenv("NATIVE_SAVES")?.let { java.io.File(it) } ?: return
        val files = root.walkTopDown().filter { it.isFile && NativeSave.nameOf(it.name) != null }.toList()
        assertTrue(files.isNotEmpty(), "no saves below $root")
        val failed = files.mapNotNull { f ->
            try { NativeSave.identify(f.name, f.readBytes()); null } catch (e: NativeSave.Invalid) { f.path + ": " + e.message }
        }
        println("NATIVE_SAVES: ${files.size} files, ${failed.size} refused")
        assertEquals(emptyList(), failed)
    }
}
