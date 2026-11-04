package ftl.android.app

import ftl.saves.LocalSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sign out asks first (SignOut, the launcher's top bar), with the website's words (web/sign-out.mjs). The website's
 * dialog is checked by tests/local-mode.mjs, the launcher's on the emulator (docs/ANDROID.md).
 */
class SignOutTest {
    private fun save(id: String, dirty: Boolean = false, nameDirty: Boolean = false) =
        LocalSave.fromJson(ftl.saves.Json.parse("""{"id":"$id","name":"$id","setup":{},"createdUtc":"","meta":{},"base":1,"dirty":$dirty,"hasRun":true,"nameDirty":$nameDirty}"""))

    @Test
    fun theStrongerLineComesWhenASaveHasNotReachedTheAccount() {
        assertFalse(SignOut.unsynced(emptyList()))
        assertFalse(SignOut.unsynced(listOf(save("a"), save("b"))))
        assertTrue(SignOut.unsynced(listOf(save("a"), save("b", dirty = true))))
        assertTrue(SignOut.unsynced(listOf(save("a", nameDirty = true))))
    }

    @Test
    fun theWordsAreTheWebsites() {
        val web = File("../../web/sign-out.mjs").readText()
        fun webText(key: String): String {
            val m = Regex("""\b$key: (['"])(.*?)\1,""").find(web) ?: error("no $key in web/sign-out.mjs")
            return m.groupValues[2]
        }
        assertEquals(SignOut.TITLE, webText("title"))
        assertEquals(SignOut.TEXT, webText("text"))
        assertEquals(SignOut.UNSYNCED, webText("unsynced"))
        assertEquals(SignOut.YES, webText("yes"))
        assertEquals(SignOut.NO, webText("no"))
    }
}
