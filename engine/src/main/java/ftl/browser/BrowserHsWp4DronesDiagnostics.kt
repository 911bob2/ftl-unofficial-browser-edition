package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/**
 * WP4 drone-body replays (agent B): the same authored Lua probe the HS oracle ran (tools/hs-oracle/tracer/data/
 * hs_oracle/wp4-drone-*.lua), phase by phase, with real game frames between phases. Diagnostics only.
 */
object BrowserHsWp4DronesDiagnostics {
    /** `drone-body <probe ship? enemy? prefix?><script>lua</script><phase lua="WP4DB.phase1()" frames="60"/>...</probe>` */
    fun run(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>(); val errors = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame(fixture.getAttributeValue("ship") ?: "PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            // `enemy="NAME"`: a fight against that event ship (the oracle's fight is a real beacon), enemy fire off.
            fixture.getAttributeValue("enemy")?.let { name ->
                game.debugSpawnShip(game.eventManager.getShip(name), Difficulty.NORMAL, 0, 71)
                game.debugFlags.noEnemyFire.set = true
                main.update(container, 0f)
            }
            val prefix = fixture.getAttributeValue("prefix") ?: "WP4DB:"
            lua.sink = { kind, text ->
                if (kind == 2) errors += text
                if (text.startsWith(prefix)) rows += text.removePrefix(prefix).trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-drones", code); if (error != null) errors += error }
            run("HS_ORACLE={emit=function(kind,v) if v and v.value then print(" + BrowserDebug.quote(prefix) + "..v.value) end end}")
            run(fixture.getChildText("script"))
            for (phase in fixture.getChildren("phase")) {
                // `reload="true"`: save and load the game, as the oracle's save-and-quit then Continue (a fresh Lua
                // state there, so the probe script is run again).
                if (phase.getAttributeValue("reload") == "true") {
                    main.loadSavedGame(BrowserXml.parse(BrowserXml.write((main.currentState as InGameState).saveGameState())))
                    (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    run("HS_ORACLE={emit=function(kind,v) if v and v.value then print(" + BrowserDebug.quote(prefix) + "..v.value) end end}")
                    run(fixture.getChildText("script"))
                    continue
                }
                val code = phase.getAttributeValue("lua")
                val frames = phase.getAttributeValue("frames")?.toInt() ?: 0
                if (frames == 0) { code?.let { run(it) }; continue }
                // A frame as BrowserEngine.step runs it: the game update, then CApp::OnLoop's ON_TICK. The oracle's
                // "lua" step is a console LUA command: CApp::OnLoop 0x402350 runs WorldManager::OnLoop, then
                // CommandGui::OnLoop 0x536770 (RunCommand 0x52f7b0), then HS's ON_TICK, so the phase's Lua runs in
                // its first frame between the update and ON_TICK (a hull set to 0 there is seen by the ships one
                // frame later: bDestroyed 2 ticks after the kill row, as the oracle reads).
                repeat(frames) { i ->
                    main.update(container, 1f / 60f)
                    if (i == 0) code?.let { run(it) }
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
