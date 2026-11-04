package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * WP7 UI probe (`BrowserEngine.hsWp7UiProbe`), Hyperspace sessions only, played through the real UI by tests/hs-wp7ui.mjs next
 * to the oracle's `wp7-ui.lua`. Commands `name:arg`; the ones not listed here go to [BrowserHsWp6EventsDiagnostics]
 * (driver, shim, append, lua, options, box, state, ...).
 * - `xml:<nodes>`: authored hyperspace.xml nodes (the oracle's `hs_options.append`) added to the loaded config.
 * - `console`: the debug console's state (open, text, cursor) as one line.
 */
object BrowserHsWp7UiDiagnostics {
    private var original: HsConfig? = null
    private val appended = StringBuilder()

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, cmd: String): String {
        val name = cmd.substringBefore(':')
        val arg = cmd.substringAfter(':', "")
        return try {
            check(HS.active) { "hs-wp7ui needs a Hyperspace session" }
            when (name) {
                "xml" -> {
                    if (original == null) original = HS.config
                    val text = String(BrowserFiles.read(HS.HYPERSPACE_XML), Charsets.UTF_8)
                    val end = text.lastIndexOf("</")
                    check(end > 0) { "hyperspace.xml has no root end tag" }
                    appended.append(arg).append(' ')
                    HS.config = HsConfig.parse(BrowserXml.parse(text.substring(0, end) + " " + appended + " " + text.substring(end)).rootElement)
                    "PASS"
                }
                "missing" -> {
                    // attributes with no getter (g) or no setter (s) and methods with no implementation, for the class prefixes in [arg]
                    val prefixes = arg.split(',').map { it.trim() + "." }
                    val out = StringBuilder()
                    for (a in 1..xyz.znix.xftl.hs.lua.LuaApi.ATTR_COUNT) {
                        val n = xyz.znix.xftl.hs.lua.LuaApi.attrName(a)
                        if (prefixes.none { n.startsWith(it) }) continue
                        val r = xyz.znix.xftl.hs.lua.LuaReg
                        val g = r.numGet[a] != null || r.strGet[a] != null || r.objGet[a] != null
                        val st = r.numSet[a] != null || r.strSet[a] != null || r.objSet[a] != null
                        if (!g || !st) out.append('\n').append(n).append(if (!g) " -g" else "").append(if (!st) " -s" else "")
                    }
                    for (m in 1..xyz.znix.xftl.hs.lua.LuaApi.METHOD_COUNT) {
                        val n = xyz.znix.xftl.hs.lua.LuaApi.methodName(m)
                        if (prefixes.none { n.startsWith(it) }) continue
                        if (xyz.znix.xftl.hs.lua.LuaReg.methods[m] == null) out.append('\n').append(n).append(" -m")
                    }
                    "PASS$out"
                }
                "textstyle" -> "cfg=${HS.cfg().customTextStyle} flag=${xyz.znix.xftl.hs.render.HsTextStyle.hasFlag(arg)} runs=" +
                    xyz.znix.xftl.hs.render.HsTextStyle.parse(arg).joinToString("|") { "${it.text}/${it.flag}" }
                // round 14: jump out to the current beacon's first neighbour (the jump zoom's SHIP_JUMP / LAYER_FRONT calls)
                "jump" -> {
                    val st = main.currentState as InGameState
                    st.player.ftlChargeProgress = 1f
                    st.beginJumpOut(st.currentBeacon.neighbours.first())
                    "PASS"
                }
                "buttons" -> (main.currentState as InGameState).shipUI.diagnosticButtons
                "windows" -> (main.currentState as InGameState).shipUI.diagnosticWindows
                // the Hyperspace event queue checked now (HsFlow.checkEventQueue), then the windows
                "loadq" -> {
                    val st = main.currentState as InGameState
                    val before = st.shipUI.diagnosticWindows
                    xyz.znix.xftl.hs.events.HsFlow.checkEventQueue(st, st.shipUI.hsHost, st.shipUI::hsChoiceBoxOpen)
                    "$before -> ${st.shipUI.diagnosticWindows} hs=${st.eventManager.hs != null}"
                }
                // a left click straight into PlayerShipUI.mouseClick at x,y (bypasses the input polling)
                "clickui" -> {
                    val st = main.currentState as InGameState
                    val (x, y) = arg.split(',').map { it.trim().toInt() }
                    st.shipUI.mouseClick(0, x, y, xyz.znix.xftl.math.ConstPoint.ZERO)
                    st.shipUI.diagnosticWindows
                }
                // round 14: a BrowserHsRenderDiagnostics command (flagship:<stage>, unpause, ...)
                "render" -> BrowserHsRenderDiagnostics.run(main, container, arg)
                // the enemy wreck: tracker time, jump-out, gone
                "wreck" -> (main.currentState as InGameState).enemy?.let {
                    "t=${it.diagnosticDestructionTime} jumpOut=${it.destructionJumpOut} invincible=${it.invincible} gone=${it.isGone}"
                } ?: "no enemy"
                "console" -> (main.currentState as InGameState).let { it.debugConsole.debugState(it.isDebugConsoleVisible) + " paused=${it.isPaused}" }
                // the text the named event resolves to now, and the event the last location showed
                "eventtext" -> {
                    val st = main.currentState as InGameState
                    val ev = st.eventManager.getOrNull(arg)?.resolve()
                    val last = xyz.znix.xftl.hs.events.HsFlow.lastLocationEvent
                    "$arg=${ev?.let { "'" + (it.text?.resolve(kotlin.random.Random(0)) ?: "") + "' choices=" + it.choices.size } ?: "missing"}" +
                        " last=${last?.template?.debugId}"
                }
                else -> BrowserHsWp6EventsDiagnostics.run(main, container, graphics, cmd)
            }
        } catch (e: Throwable) {
            "FAILED $name: $e " + e.stackTrace.take(6).joinToString(" < ")
        }
    }
}
