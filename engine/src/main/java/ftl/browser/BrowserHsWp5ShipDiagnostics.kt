package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/**
 * WP5 ship probe replay (`hsRuntimeProbe("wp5-ship <probe>")`, the oracle's `wp5-ship.lua`): the hangar part in the
 * ship select screen, then the member and method rows on the MV Kestrel at its first beacon, paused. The probe XML
 * carries the script and the oracle's start state (`<state>`: the augments and weapons the intro events gave it).
 * Diagnostics only; BrowserRandom and NativeRandom are restored afterwards.
 */
object BrowserHsWp5ShipDiagnostics {
    fun run(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            lua.sink = { kind, text ->
                if (kind == 2) rows += "LUA-ERROR|" + text.trim()
                if (text.startsWith("WP5_R:")) rows += text.removePrefix("WP5_R:").trimEnd()
                // a probe call that raised (the oracle prints nothing for it): "E|<row number>|<message>"
                if (text.startsWith("WP5_E:")) rows += "E|" + rows.count { !it.startsWith("E|") } + "|" + text.removePrefix("WP5_E:").trim()
            }
            fun run(code: String) { val e = lua.runString("wp5-ship", code); if (e != null) rows += "RUN-ERROR|" + e.trim() }
            run("HS_ORACLE={emit=function(k,v) if k=='wp5_ship_row' then print('WP5_R:'..v.value) end end}")
            run("WP5S_ERR=function(e) print('WP5_E:'..tostring(e)) end")
            run(fixture.getChildText("script"))
            // the hangar part (the oracle presses F9 in the hangar)
            main.switchToShipSelect()
            repeat(3) { main.update(container, 1f / 60f) }
            run("WP5S.hangar()")
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            fixture.getChild("state")?.let { applyState(game, it) }
            // the oracle reads the ship at its first beacon after the arrival, the FTL drive charged
            var frames = 0
            while ((game.player.browserJumping || !game.player.isFtlCharged) && frames++ < 60 * 300) {
                main.update(container, 1f / 60f)
                game.shipUI.diagnosticCloseWindow()
            }
            rows += "E|0|settled after $frames frames"
            if (!game.isPaused) game.toggleQuickPause()
            run("WP5S.run()")
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** The oracle ship's equipment after the MV intro events: `<aug name=.../>`, `<weapon name=... slot=.../>`, resources. */
    private fun applyState(game: InGameState, state: org.jdom2.Element) {
        val ship = game.player
        for (a in state.getChildren("removeAug")) ship.removeBlueprint(game.blueprintManager[a.getAttributeValue("name")] as xyz.znix.xftl.Blueprint)
        for (a in state.getChildren("aug")) ship.addBlueprint(game.blueprintManager[a.getAttributeValue("name")] as xyz.znix.xftl.Blueprint, true)
        for (w in state.getChildren("weapon")) ship.addBlueprint(game.blueprintManager[w.getAttributeValue("name")] as xyz.znix.xftl.Blueprint, true)
        state.getAttributeValue("scrap")?.let { ship.scrap = it.toInt() }
        state.getAttributeValue("fuel")?.let { ship.fuelCount = it.toInt() }
        state.getAttributeValue("missiles")?.let { ship.missilesCount = it.toInt() }
        state.getAttributeValue("drones")?.let { ship.dronesCount = it.toInt() }
    }
}
