package ftl.android.app

import ftl.android.app.mods.ModLibrary
import ftl.saves.ModFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The name a mod file row shows, as the website's mod list (web/mods/library.mjs: title, then version): a Custom file
 * by its metadata title and version, a Verified one by the catalogue's name and version.
 */
class ModHeadingTest {
    private val custom = ModFile("my-tweaks.zip", "", "a".repeat(64), 100, false)
    private val verified = ModFile("Hyperspace", "1.23.2", "b".repeat(64), 100, true)

    @Test
    fun aCustomFileShowsItsMetadataVersion() {
        assertEquals("My Tweaks 0.1", ModLibrary.Mod(custom, "My Tweaks", false, "local", "0.1").heading)
        assertEquals("My Tweaks", ModLibrary.Mod(custom, "My Tweaks", false, "local", "").heading)
        assertEquals("my-tweaks.zip", ModLibrary.Mod(custom, null, false, "local", null).heading)
    }

    @Test
    fun aVerifiedFileShowsTheCataloguesNameAndVersion() {
        assertEquals("Hyperspace 1.23.2", ModLibrary.Mod(verified, "Hyperspace Mod", true, "server", "1.23.2b").heading)
    }

    @Test
    fun theVersionIsKeptInTheLibrary() {
        val back = ModLibrary.Mod.fromJson(ModLibrary.Mod(custom, "My Tweaks", false, "local", "0.1").toJson())!!
        assertEquals("0.1", back.version)
        assertEquals("My Tweaks 0.1", back.heading)
        // An entry stored before the version was kept reads none; the library reads it from the archive then.
        val old = ftl.saves.obj("file" to custom.toJson(), "title" to "My Tweaks", "hyperspace" to false, "source" to "local")
        assertNull(ModLibrary.Mod.fromJson(old)!!.version)
    }
}
