package ftl.mods

import ftl.saves.Json
import ftl.saves.arr
import ftl.saves.str
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** LuaScan against the website's cases (tests/hs-lua/lua-scan-cases.json, web/mods/lua-scan.mjs). */
class LuaScanTest {
    @Test
    fun casesAsTheWebsite() {
        val cases = Json.parse(File(System.getProperty("luaScanCases")).readText()).arr()
        for (c in cases) {
            val o = c as Json.Obj
            assertEquals(o["uses"].arr().map { it.str()!! }, LuaScan.scan(o["src"].str()!!), o["src"].str())
        }
    }

    @Test
    fun wording() {
        assertEquals("files (io.open) and system commands (os.execute)", LuaScan.usesText(listOf("io.open", "os.execute")))
        val m = LuaScan.ModUses("s", "Test mod", listOf("io.open", "os.execute"), listOf("data/a.lua"))
        assertEquals(listOf("Test mod: this mod's scripts use files (io.open) and system commands (os.execute).",
            "Here they run in a private folder: they cannot reach your files or run programs."), LuaScan.promptText(listOf(m), false))
        assert(LuaScan.promptText(listOf(m), true).last().startsWith("Lua sandboxing is off"))
    }

    @Test
    fun ownersAndLaunch() {
        val files = mapOf("data/a.lua" to "os.execute('x')".encodeToByteArray(), "data/b.lua" to "print(os.clock())".encodeToByteArray())
        val found = LuaScan.scanLaunch(listOf("HS", "Mod"), listOf("h", "m"), listOf("data/a.lua", "data/b.lua"), { files[it] }, mapOf("data/a.lua" to 1))
        assertEquals(listOf(LuaScan.ModUses("m", "Mod", listOf("os.execute"), listOf("data/a.lua"))), found)
    }
}
