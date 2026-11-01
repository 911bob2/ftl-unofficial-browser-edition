package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/**
 * WP6 save/run-flow probe replay (`hsRuntimeProbe("wp6-save <xml>")`, the oracle's `wp6-save.lua`): the Global, ScoreKeeper
 * and TopScore members read in a typed seeded run (seed 12345) at its first beacon, paused. The probe XML carries the
 * script (`<script>`) and the oracle's start state (`<state>`: resources the intro events gave). Diagnostics only;
 * BrowserRandom and NativeRandom are restored afterwards.
 */
object BrowserHsWp6SaveDiagnostics {
    fun run(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val oldSink = lua.sink
        val oldSeed = xyz.znix.xftl.hs.sector.HsSeeds.requestedSeed
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            lua.sink = { kind, text ->
                if (kind == 2) rows += "LUA-ERROR|" + text.trim()
                if (text.startsWith("WP6_R:")) rows += text.removePrefix("WP6_R:").trimEnd()
                // a probe call that raised (the oracle prints nothing for it): "E|<row number>|<message>"
                if (text.startsWith("WP6_E:")) rows += "E|" + rows.count { !it.startsWith("E|") } + "|" + text.removePrefix("WP6_E:").trim()
            }
            fun run(code: String) { val e = lua.runString("wp6-save", code); if (e != null) rows += "RUN-ERROR|" + e.trim() }
            run("HS_ORACLE={emit=function(k,v) if k=='wp6v_row' then print('WP6_R:'..v.value) end end}")
            run("WP6V_ERR=function(e) print('WP6_E:'..tostring(e)) end")
            run(fixture.getChildText("script"))
            // the oracle types the seed into the hangar's seed box
            fixture.getAttributeValue("seed")?.let { xyz.znix.xftl.hs.sector.HsSeeds.requestedSeed = it.toLong() }
            main.switchToShipSelect()
            repeat(3) { main.update(container, 1f / 60f) }
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            fixture.getChild("state")?.let { st ->
                val ship = game.player
                st.getAttributeValue("scrap")?.let { ship.scrap = it.toInt() }
                st.getAttributeValue("fuel")?.let { ship.fuelCount = it.toInt() }
                st.getAttributeValue("missiles")?.let { ship.missilesCount = it.toInt() }
                st.getAttributeValue("drones")?.let { ship.dronesCount = it.toInt() }
            }
            var frames = 0
            while ((game.player.browserJumping || !game.player.isFtlCharged) && frames++ < 60 * 300) {
                main.update(container, 1f / 60f)
                game.shipUI.diagnosticCloseWindow()
            }
            rows += "E|0|settled after $frames frames"
            if (!game.isPaused) game.toggleQuickPause()
            run("WP6V.run()")
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            xyz.znix.xftl.hs.sector.HsSeeds.requestedSeed = oldSeed
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * `hsRuntimeProbe("wp6-save-tutorial <xml>")`: the tutorial session started from the main menu with
     * `<disableDefaultTutorial>` forced on or off (`disable` attribute), twelve seconds in, then the probe's `WP6V.tutorial()` rows
     * (`tut|running`, `tut|upgradeButton`) and what the lesson did (rows `E|` are ours only: the lesson step, the overlay).
     */
    fun tutorial(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            lua.sink = { kind, text ->
                if (kind == 2) rows += "LUA-ERROR|" + text.trim()
                if (text.startsWith("WP6_R:")) rows += text.removePrefix("WP6_R:").trimEnd()
                if (text.startsWith("WP6_E:")) rows += "E|" + rows.count { !it.startsWith("E|") } + "|" + text.removePrefix("WP6_E:").trim()
            }
            fun run(code: String) { val e = lua.runString("wp6-save", code); if (e != null) rows += "RUN-ERROR|" + e.trim() }
            run("HS_ORACLE={emit=function(k,v) if k=='wp6v_row' then print('WP6_R:'..v.value) end end}")
            run("WP6V_ERR=function(e) print('WP6_E:'..tostring(e)) end")
            run(fixture.getChildText("script"))
            xyz.znix.xftl.hs.tutorial.HsTutorial.forced = fixture.getAttributeValue("disable") == "true"
            main.switchToMainMenu()
            repeat(3) { main.update(container, 1f / 60f) }
            val game = main.startTutorialSession()
            repeat(60 * 12) { main.update(container, 1f / 60f) }
            val adapter = main.tutorialAdapter
            rows += "E|1|step " + (adapter?.controller?.step?.id ?: "none") + " completed " + adapter?.controller?.completed +
                " mapAllowed " + adapter?.controller?.mapOpeningAllowed + " shipAllowed " + adapter?.controller?.shipOpeningAllowed +
                " suppressed " + (main.tutorialOverlay?.isSuppressed(game))
            run("WP6V.tutorial()")
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            xyz.znix.xftl.hs.tutorial.HsTutorial.forced = null
            BrowserRandom.restore(random); NativeRandom.restore(native)
            if (main.isTutorialRunning) main.endTutorialSession()
            main.switchToMainMenu()
        }
    }
}
