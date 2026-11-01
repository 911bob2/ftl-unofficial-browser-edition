package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/**
 * WP5 ship probe part 2 replays (agent C; the oracle's `wp5-ship2.lua`, sessions `wp5-ship2*.json`). The phase runner
 * of [BrowserHsWp5CrewDiagnostics] (agent A) with what these sessions add: the session's difficulty, the `<boss>` node
 * the session appends to hyperspace.xml, a `move` (the oracle's mouse move: the pointer stays there) and a rendered
 * frame per frame while a pointer is set (tooltips are built while drawing). Diagnostics only.
 *
 * `wp5-ship2 <probe difficulty? ship? prefix?><script/><boss/>?<phase lua frames close reload move/>...</probe>`
 */
object BrowserHsWp5Ship2Diagnostics {
    fun run(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>(); val errors = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            val difficulty = when (fixture.getAttributeValue("difficulty")) { "0" -> Difficulty.EASY; "2" -> Difficulty.HARD; else -> Difficulty.NORMAL }
            main.startDiagnosticGame(fixture.getAttributeValue("ship") ?: "PLAYER_SHIP_MVKESTREL", difficulty)
            main.update(container, 0f)
            (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
            fixture.getChild("boss")?.let { xyz.znix.xftl.hs.ship.HsBoss.diagnosticAppend(it) }
            // the session's authored data (tracer blueprints.xml.append / events.xml.append nodes the probe uses)
            val state0 = main.currentState as InGameState
            fixture.getChild("blueprints")?.let { b ->
                val root = org.jdom2.Element("FTL"); for (c in b.children.toList()) root.addContent(c.clone())
                state0.blueprintManager.diagnosticAppend(state0.eventManager.df, root)
            }
            fixture.getChild("events")?.let { ev ->
                val root = org.jdom2.Element("FTL"); for (c in ev.children.toList()) root.addContent(c.clone())
                state0.eventManager.diagnosticAppend(org.jdom2.Document(root))
            }
            val prefix = fixture.getAttributeValue("prefix") ?: "WP5S2:"
            lua.sink = { kind, text ->
                if (kind == 2) errors += text
                if (text.startsWith(prefix)) rows += text.removePrefix(prefix).trimEnd()
            }
            val emit = "HS_ORACLE={emit=function(kind,v) if v and v.value then print(" + BrowserDebug.quote(prefix) + "..v.value) end end}"
            fun run(code: String) { val error = lua.runString("wp5-ship2", code); if (error != null) errors += error }
            run(emit)
            run(fixture.getChildText("script"))
            var pointer: Pair<Int, Int>? = null
            for (phase in fixture.getChildren("phase")) {
                if (phase.getAttributeValue("reload") == "true") {
                    main.loadSavedGame(BrowserXml.parse(BrowserXml.write((main.currentState as InGameState).saveGameState())))
                    (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    run(emit)
                    run(fixture.getChildText("script"))
                    continue
                }
                phase.getAttributeValue("move")?.let { at ->
                    val (x, y) = at.split(',').map { it.trim().toInt() }
                    main.render(container, BrowserEngine.diagnosticGraphics())
                    BrowserEngine.mouse(x, y, 0, 0)
                    main.update(container, 0f)
                    pointer = x to y
                }
                val code = phase.getAttributeValue("lua")
                val frames = phase.getAttributeValue("frames")?.toInt() ?: 0
                val close = phase.getAttributeValue("close") == "true"
                if (frames == 0) {
                    code?.let { run(it) }
                    if (close) (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    continue
                }
                repeat(frames) { i ->
                    // CFPS::OnLoop's SpeedFactor for a 1/60 s frame (Lua reads Hyperspace.FPS.SpeedFactor)
                    xyz.znix.xftl.hs.lua.LuaFps.onLoop(1f / 60f)
                    main.update(container, 1f / 60f)
                    if (i == 0) {
                        code?.let { run(it) }
                        if (close) (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    }
                    if (pointer != null) main.render(container, BrowserEngine.diagnosticGraphics())
                    xyz.znix.xftl.hs.lua.LuaEvents.onTick()
                }
            }
            return "{\"rows\":" + rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) } +
                ",\"errors\":" + errors.joinToString(",", "[", "]") { BrowserDebug.quote(it) } + "}"
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
