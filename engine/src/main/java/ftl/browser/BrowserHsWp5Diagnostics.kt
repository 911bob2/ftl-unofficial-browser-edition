package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/**
 * WP5 core-systems probe (`hsRuntimeProbe("wp5-sys <lua>")`): the oracle's authored probe script run against the MV
 * Kestrel's start state, paused. Rows are the script's `WP5_SYS:` print; Lua errors are returned as `ERR|...` rows.
 * Diagnostics only; the random streams are restored afterwards.
 */
object BrowserHsWp5Diagnostics {
    fun systems(main: MainGame, container: GameContainer, script: String, entry: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val oldSink = lua.sink
        val oldSf = xyz.znix.xftl.hs.lua.LuaFps.speedFactor
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            if (!game.isPaused) game.toggleQuickPause()
            // the probe divides frame-scaled amounts by FPS.SpeedFactor; a 60 fps frame's value
            xyz.znix.xftl.hs.lua.LuaFps.speedFactor = 0.26666668f
            lua.sink = { kind, text ->
                if (kind == 2) rows += "ERR|" + text.trimEnd().replace('\n', ' ')
                else if (text.startsWith("WP5_SYS_ERROR:")) rows += "ERR|phase|" + text.removePrefix("WP5_SYS_ERROR:").trimEnd()
                else if (text.startsWith("WP5_SYS:")) rows += text.removePrefix("WP5_SYS:").trimEnd().split('\n')
            }
            lua.runString("wp5-sys-probe", script)?.let { rows += "ERR|load|$it" }
            lua.runString("wp5-sys-run", entry)?.let { rows += "ERR|run|$it"; lua.runString("wp5-sys-results", "WP5Sys.results()") }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            xyz.znix.xftl.hs.lua.LuaFps.speedFactor = oldSf
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
