package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.lua.HsLua

/**
 * r27-powers sweep, oracle twin (`BrowserEngine.r27PowersLua`): the oracle session `r27-powers.json` replayed here. A new
 * PLAYER_SHIP_MVKESTREL run, the start-beacon chain answered with [keys], the tracer's authored events and blueprints
 * ([append], `<probe><blueprints>..</blueprints><events>..</events></probe>`) loaded into the run, then the item lists
 * ([lists], generated outside Git) and the driver ([code], tools/hs-oracle/tracer/data/hs_oracle/r27-powers.lua):
 * `R27.setup()` opens the R27P_FIGHT box, key 1 closes it, [pre] (a slice: `R27.from=..`) and `R27.run(parts)` run,
 * stepping 1/60 s frames until `r27_done` (or [maxFrames]). Returns the driver's `R27J` lines and the Lua errors.
 * [reloadAt]: scenario mark tags (`<scenario>:<tag>`) after whose dump the run is saved and loaded again; the driver
 * then dumps `<tag>:presave` and `<tag>:reloaded`.
 */
object BrowserR27PowersDiagnostics {
    private fun q(s: String) = BrowserDebug.quote(s)

    fun luaRun(main: MainGame, append: String, lists: String, code: String, keys: String, pre: String, parts: String,
               maxFrames: Int, reloadAt: String): String {
        val reloads = reloadAt.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        var pendingReload: String? = null
        val reloadInfo = ArrayList<String>()
        val lines = ArrayList<String>()
        val errors = ArrayList<String>()
        var done = false
        val sink: (Int, String) -> Unit = { kind, text ->
            if (text.startsWith("R27J ")) {
                val line = text.substring(5).trimEnd()
                lines += line
                if (line.contains("\"k\":\"r27_mark\"")) {
                    val sc = Regex("\"sc\":\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    val tag = Regex("\"tag\":\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    val key = "$sc:$tag"
                    if (key in reloads) { pendingReload = key; reloads.remove(key) }
                }
                if (line.contains("\"k\":\"r27_done\"")) done = true
            } else if (kind >= 2 && errors.size < 200) errors += text.take(500)
        }
        val rng = BrowserRandom.snapshot()
        val picked = ArrayList<String>()
        fun step() {
            BrowserEngine.step(1f / 60f)
            (main.currentState as? InGameState)?.shipUI?.prepareResourceMessages()
        }
        fun press(c: Char) {
            BrowserEngine.key(c.code, c.code, true); BrowserEngine.step(0f)
            BrowserEngine.key(c.code, c.code, false); BrowserEngine.step(0f)
        }
        fun unpause() {
            val st = main.currentState as InGameState
            if (st.isPaused && !st.shipUI.isWindowOpen) st.toggleQuickPause()
        }
        var frames = 0
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            var state = main.currentState as InGameState
            for (k in keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                var n = 0
                while (!state.shipUI.hsChoiceBoxOpen() && n < 400) { step(); n++ }
                if (!state.shipUI.hsChoiceBoxOpen()) { picked += "no box for key $k"; break }
                picked += (state.shipUI.hsOpenEventName() ?: "?") + "=" + k
                press(k[0])
                repeat(30) { step() }
            }
            state = main.currentState as InGameState
            unpause()
            if (append.isNotEmpty()) {
                val fixture = BrowserXml.parse(append).rootElement
                fixture.getChild("blueprints")?.let { b ->
                    val root = org.jdom2.Element("FTL"); for (c in b.children.toList()) root.addContent(c.clone())
                    state.blueprintManager.diagnosticAppend(state.eventManager.df, root)
                }
                fixture.getChild("events")?.let { ev ->
                    val root = org.jdom2.Element("FTL"); for (c in ev.children.toList()) root.addContent(c.clone())
                    state.eventManager.diagnosticAppend(org.jdom2.Document(root))
                }
            }
            HsLua.sink = sink
            HsLua.runString("r27p-lists.lua", lists)?.let { errors += "lists: $it" }
            HsLua.runString("r27-powers.lua", code)?.let { errors += "load: $it" }
            HsLua.runString("r27-setup", "R27.setup()")?.let { errors += "setup: $it" }
            var n = 0
            while (!state.shipUI.hsChoiceBoxOpen() && n < 120) { step(); n++ }
            if (state.shipUI.hsChoiceBoxOpen()) {
                picked += (state.shipUI.hsOpenEventName() ?: "?") + "=1"
                press('1')
            } else picked += "no R27P_FIGHT box"
            repeat(120) { step() }
            unpause()
            if (pre.isNotEmpty()) HsLua.runString("r27-pre", pre)?.let { errors += "pre: $it" }
            HsLua.runString("r27-run", "R27.run('$parts')")?.let { errors += "run: $it" }
            while (!done && frames < maxFrames) {
                step(); frames++
                val t = pendingReload ?: continue
                pendingReload = null
                HsLua.runString("r27-presave", "R27.dump('${t.substringAfterLast(':')}:presave')")?.let { errors += "presave: $it" }
                val st = main.currentState as InGameState
                val xml = BrowserXml.write(st.saveGameState())
                main.loadSavedGame(BrowserXml.parse(xml))
                reloadInfo += "$t: ${xml.length} chars"
                unpause()
                step(); frames++
                HsLua.runString("r27-reloaded", "R27.dump('${t.substringAfterLast(':')}:reloaded')")?.let { errors += "reloaded: $it" }
            }
            if (!done) errors += "no r27_done after $frames frames"
        } catch (e: Throwable) {
            errors += "exception: $e " + e.stackTrace.take(8).joinToString(" < ")
        } finally {
            HsLua.sink = null
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
        val sb = StringBuilder("{\"done\":$done,\"frames\":$frames,\"reloads\":[" + reloadInfo.joinToString(",") { q(it) } + "],\"picked\":[")
        sb.append(picked.joinToString(",") { q(it) }).append("],\"errors\":[")
        sb.append(errors.joinToString(",") { q(it) }).append("],\"lines\":[")
        sb.append(lines.joinToString(",")).append("]}")
        return sb.toString()
    }
}
