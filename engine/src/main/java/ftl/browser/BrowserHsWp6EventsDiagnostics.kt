package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * WP6 events probe (`BrowserEngine.hsWp6EventsProbe`), Hyperspace sessions only, played through the real UI by
 * tests/hs-wp6events.mjs next to the oracle's `wp6-events.lua`. Commands `name:arg`; the ones not listed here go to
 * [BrowserHsWp6MapDiagnostics] (driver, options, box, state, choices, pick, answer, savereload, load, ...).
 * - `append:<xml>`: the authored `<FTL>` events/blueprints document of the oracle's tracer data, loaded into the run.
 * - `lua:<code>`: run Lua; the result is the error or "PASS" and then the rows (`WP6A:` print lines) written since the
 *   last call, one per line.
 * - `shim`: the HS_ORACLE.emit stand-in that turns the driver's rows into `WP6A:` print lines.
 */
object BrowserHsWp6EventsDiagnostics {
    private val rows = ArrayList<String>()
    private var sinkInstalled = false

    private fun install() {
        if (sinkInstalled) return
        sinkInstalled = true
        val previous = HsLua.sink
        HsLua.sink = { kind, text ->
            previous?.invoke(kind, text)
            if (text.startsWith("WP6A:")) rows += text.removePrefix("WP6A:").trimEnd()
            else if (kind == 2) rows += "ERROR " + text.trimEnd()
        }
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, cmd: String): String {
        val name = cmd.substringBefore(':')
        val arg = cmd.substringAfter(':', "")
        return try {
            check(HS.active) { "hs-wp6events needs a Hyperspace session" }
            install()
            when (name) {
                "shim" -> HsLua.runString("wp6a-shim", "HS_ORACLE={emit=function(kind,v) if v and v.value then print('WP6A:'..v.value) end end}") ?: "PASS"
                "append" -> {
                    val game = main.currentState as InGameState
                    val fixture = BrowserXml.parse(arg).rootElement
                    fixture.getChild("blueprints")?.let { b ->
                        val root = org.jdom2.Element("FTL"); for (c in b.children.toList()) root.addContent(c.clone())
                        game.blueprintManager.diagnosticAppend(game.eventManager.df, root)
                    }
                    fixture.getChild("events")?.let { ev ->
                        val root = org.jdom2.Element("FTL"); for (c in ev.children.toList()) root.addContent(c.clone())
                        game.eventManager.diagnosticAppend(org.jdom2.Document(root))
                    }
                    "PASS"
                }
                "drain" -> {
                    // the rows scripts printed since the last command (hook callbacks between two `lua` calls)
                    val out = "PASS" + rows.joinToString("") { "\n" + it }
                    rows.clear()
                    out
                }
                "lua" -> {
                    rows.clear()
                    val error = HsLua.runString("wp6a", arg)
                    // a frame so deferred work (an opened box) is done before the next command
                    ((error ?: "PASS") + rows.joinToString("") { "\n" + it }).also { rows.clear() }
                }
                else -> BrowserHsWp6MapDiagnostics.run(main, container, graphics, cmd)
            }
        } catch (e: Throwable) {
            "FAILED $name: $e " + e.stackTrace.take(6).joinToString(" < ")
        }
    }
}
