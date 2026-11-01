package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.LuaApi
import xyz.znix.xftl.hs.lua.LuaReg
import xyz.znix.xftl.sys.GameContainer

/**
 * Round 14 slice B probe (`hsRuntimeProbe("sweep-world <xml>")` and `hsRuntimeProbe("sweep-cov")`), Hyperspace sessions only.
 * - `sweep-cov`: every attribute and method of the HS Lua API that has no adapter registered right now (grouped by class;
 *   vector/map/pair/set classes left out), one line per class. Test-only.
 * - `sweep-world <xml>`: the oracle's `sweep-world.lua` replayed: the probe XML carries the script and the oracle's start
 *   state (`<state>`), the run is the MV Kestrel at its first beacon, paused, then `SW.run()` (and `SW.<part>()` for
 *   `<part>` children). Rows come back as a JSON array. BrowserRandom and NativeRandom are restored afterwards.
 */
object BrowserHsSweepWorldDiagnostics {
    /** Attribute names with a getter but no setter of any kind, one per line (hs_api.c says which of them SWIG lets scripts write). */
    fun setterCoverage(): String {
        check(HS.luaActive)
        val sb = StringBuilder()
        for (a in 1..LuaApi.ATTR_COUNT) {
            if (LuaReg.numSet[a] == null && LuaReg.strSet[a] == null && LuaReg.objSet[a] == null) sb.append(LuaApi.attrName(a)).append(10.toChar())
        }
        return sb.toString()
    }

    fun coverage(): String {
        check(HS.luaActive)
        val missing = java.util.TreeMap<String, ArrayList<String>>()
        fun skip(cls: String) = cls.startsWith("vector_") || cls == "string"
        for (a in 1..LuaApi.ATTR_COUNT) {
            val name = LuaApi.attrName(a)
            val cls = if (name.contains('.')) name.substringBefore('.') else "?"
            val member = if (name.contains('.')) name.substringAfter('.') else name
            if (skip(cls)) continue
            val ok = LuaReg.numGet[a] != null || LuaReg.strGet[a] != null || LuaReg.objGet[a] != null
            if (!ok) missing.getOrPut(cls) { ArrayList() }.add("A:$member")
        }
        val seen = HashSet<String>()
        for (m in 1..LuaApi.METHOD_COUNT) {
            val name = LuaApi.methodName(m)
            // SWIG overload dispatchers (the first of a run of ids with one name) are never called by the glue
            if (m < LuaApi.METHOD_COUNT && LuaApi.methodName(m + 1) == name && (m == 1 || LuaApi.methodName(m - 1) != name)) continue
            val cls = if (name.contains('.')) name.substringBefore('.') else "?"
            if (skip(cls)) continue
            val member = (if (name.contains('.')) name.substringAfter('.') else name)
            if (LuaReg.methods[m] == null && seen.add("$cls.$member")) missing.getOrPut(cls) { ArrayList() }.add("M:$member")
        }
        val sb = StringBuilder()
        for ((c, l) in missing) sb.append(c).append('\t').append(l.size).append('\t').append(l.joinToString(" ")).append('\n')
        return sb.toString()
    }

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
                if (text.startsWith("SW_R:")) rows += text.removePrefix("SW_R:").trimEnd()
            }
            fun run(code: String) { val e = lua.runString("sweep-world", code); if (e != null) rows += "RUN-ERROR|" + e.trim() }
            run("HS_ORACLE={emit=function(k,v) if k=='sw_row' then print('SW_R:'..v.value) end end}")
            run(fixture.getChildText("script"))
            // continue="true": the menu's Continue on the run a previous page saved (the oracle's fresh process + Continue);
            // the harness hands that page's run save over in <runsave> (the shell's own storage is not part of the check)
            if (fixture.getAttributeValue("continue") == "true") {
                fixture.getChildText("runsave")?.takeIf { it.isNotBlank() }?.let { BrowserFiles.setRun(it) }
                check(main.hasRunSave()) { "no run save to continue" }
                main.continueSavedRun()
            } else {
                main.switchToShipSelect()
                repeat(3) { main.update(container, 1f / 60f) }
                run("if SW.hangar then SW.hangar() end")
                main.startDiagnosticGame(fixture.getAttributeValue("ship") ?: "PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            }
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            fixture.getChild("state")?.let { applyState(game, it) }
            var frames = 0
            while ((game.player.browserJumping || !game.player.isFtlCharged) && frames++ < 60 * 300) {
                main.update(container, 1f / 60f)
                game.shipUI.diagnosticCloseWindow()
            }
            rows += "E|0|settled after $frames frames"
            if (!game.isPaused) game.toggleQuickPause()
            // Parts: Lua, or `@move x y`, `@click x y`, `@rclick x y`, `@frames n` (the oracle session's mouse steps: the
            // pointer in game coordinates, each step followed by rendered frames so render callbacks see the pointer)
            val graphics = BrowserEngine.diagnosticGraphics()
            // one engine step as BrowserEngine.step makes it: the HS frame time (CFPS) and ON_TICK after the loop
            fun frames(n: Int) = repeat(n) {
                main.update(container, if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaFps.onLoop(1f / 60f) else 1f / 60f)
                if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.onTick()
                if (graphics != null) main.render(container, graphics)
            }
            var mx = 0; var my = 0
            for (p in fixture.getChildren("part")) {
                val t = p.text.trim()
                if (!t.startsWith("@")) { run(t); continue }
                val w = t.substring(1).split(' ')
                when (w[0]) {
                    "move" -> { mx = w[1].toInt(); my = w[2].toInt(); BrowserEngine.diagnosticMouse(mx, my, 0, 0); frames(3) }
                    "click", "rclick" -> {
                        val b = if (w[0] == "click") 0 else 1
                        mx = w[1].toInt(); my = w[2].toInt(); BrowserEngine.diagnosticMouse(mx, my, 0, 0); frames(2)
                        BrowserEngine.diagnosticMouse(mx, my, 1, b); frames(2); BrowserEngine.diagnosticMouse(mx, my, 2, b); frames(3)
                    }
                    "frames" -> frames(w[1].toInt())
                    // the run saved and loaded back (the engine's save of the installed systems, custom ones included)
                    "saveload" -> rows += "saveload|" + main.doSaveLoadGame()
                    // Save and Quit's run save (MainGame.quitGame), for a later page's continue="true" probe
                    "savequit" -> {
                        main.writeRunSave(); rows += "savequit|" + main.hasRunSave()
                        BrowserFiles.getRun()?.let { rows += "runsave|$it" }
                    }
                    // whether the run's save holds a text (the oracle's binary save is searched for the same string)
                    "savehas" -> {
                        val g = main.currentState as? InGameState
                        val xml = g?.saveGameState()?.let { org.jdom2.output.XMLOutputter().outputString(it) } ?: ""
                        rows += "savehas|" + w[1] + "|" + xml.contains(w[1])
                    }
                    // the frame as a PNG data URL (the oracle session's `shot` at the same step)
                    "shot" -> { frames(1); rows += "shot|" + w[1] + "|" + BrowserCanvasShot.png() }
                    // a key press and release (engine key code, character), as the oracle's `key` / `pick` / `answer` steps
                    "key" -> {
                        BrowserEngine.key(w[1].toInt(), w.getOrNull(2)?.toInt() ?: 0, true); frames(2)
                        BrowserEngine.key(w[1].toInt(), w.getOrNull(2)?.toInt() ?: 0, false); frames(3)
                    }
                    // a hotkey by id as the key bound to it would (the oracle binds aim_artillery1/2 in settings.ini)
                    "hotkey" -> {
                        val g = main.currentState as? InGameState
                        val key = g?.hotkeyManager?.byID?.get(w[1])
                        if (g == null || key == null) rows += "E|hotkey ${w[1]} missing" else xyz.znix.xftl.hs.render.HsHotkeys.dispatch(g, key, false)
                        frames(2)
                    }
                }
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

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
