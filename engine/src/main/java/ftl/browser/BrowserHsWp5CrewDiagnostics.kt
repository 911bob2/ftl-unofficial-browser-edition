package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/**
 * WP5 crew-systems replays (agent A): the authored Lua probe the HS oracle ran (tools/hs-oracle/tracer/data/hs_oracle/
 * wp5-crew.lua), phase by phase with real game frames between phases, on the same native XML inputs (the session's
 * hyperspace.xml, blueprints.xml and events.xml, given before the engine starts). Diagnostics only.
 */
object BrowserHsWp5CrewDiagnostics {
    /**
     * `wp5-crew <probe ship? prefix? seed?><script>lua</script><phase lua=".." frames="n" close="true"/>...</probe>`:
     * `close` shuts the window the phase opened (the oracle's `pick` of the event's only choice); `reload` saves and
     * loads the game and runs the script again (the oracle's save-and-quit, then Continue).
     */
    fun run(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val oldSeed = xyz.znix.xftl.hs.sector.HsSeeds.requestedSeed
        val rows = ArrayList<String>(); val errors = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            // `seed`: the run seed the oracle typed into the hangar's seed box (a seeded run: the same sector map)
            fixture.getAttributeValue("seed")?.let { xyz.znix.xftl.hs.sector.HsSeeds.requestedSeed = it.trim().toLong() }
            main.startDiagnosticGame(fixture.getAttributeValue("ship") ?: "PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
            val prefix = fixture.getAttributeValue("prefix") ?: "WP5C:"
            lua.sink = { kind, text ->
                if (kind == 2) errors += text
                if (text.startsWith(prefix)) rows += text.removePrefix(prefix).trimEnd()
            }
            // through log (not print): the oracle's emit draws nothing, and HS shows print lines on the screen
            val emit = "HS_ORACLE={emit=function(kind,v) if v and v.value then log(" + BrowserDebug.quote(prefix) + "..v.value) end end}"
            fun run(code: String) { val error = lua.runString("wp5-crew", code); if (error != null) errors += error }
            run(emit)
            run(fixture.getChildText("script"))
            for (phase in fixture.getChildren("phase")) {
                if (phase.getAttributeValue("reload") == "true") {
                    main.loadSavedGame(BrowserXml.parse(BrowserXml.write((main.currentState as InGameState).saveGameState())))
                    (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    run(emit)
                    run(fixture.getChildText("script"))
                    continue
                }
                // A rendered frame (the ships' screen positions are laid out while drawing).
                if (phase.getAttributeValue("render") == "true") main.render(container, BrowserEngine.diagnosticGraphics())
                // The oracle's shot step: one rendered frame, kept as a PNG data URL row `shot|name|data`.
                phase.getAttributeValue("shot")?.let { name ->
                    main.render(container, BrowserEngine.diagnosticGraphics())
                    rows += "shot|" + name + "|" + BrowserCanvasShot.png()
                }
                // The oracle's movetrace onto a crew member of the player's ship: `movecrew="ally<i>"` or "enemy<i>" (the
                // i-th of that owner in the crew list, boarders being the enemy's), the mouse to the centre of its square.
                phase.getAttributeValue("movecrew")?.let { who ->
                    val st = main.currentState as InGameState
                    main.render(container, BrowserEngine.diagnosticGraphics())
                    val owner = if (who.startsWith("ally")) 0 else 1
                    val crew = st.player.crew.filter { xyz.znix.xftl.hs.crew.HsCrew.shipId(it) == owner }
                        .getOrNull(who.dropWhile { !it.isDigit() }.toInt()) ?: error("no crew member $who")
                    val c = crew.getPixelPositionCentre()
                    val at = st.playerShipRenderToScreen(c.x, c.y)
                    rows += "movecrew|$who|${at.x},${at.y}"
                    BrowserEngine.mouse(at.x, at.y, 0, 0)
                    main.update(container, 0f)
                }
                // The oracle's move step: the mouse moves there without a press.
                phase.getAttributeValue("move")?.let { at ->
                    val (x, y) = at.split(',').map { it.trim().toInt() }
                    main.render(container, BrowserEngine.diagnosticGraphics())
                    BrowserEngine.mouse(x, y, 0, 0)
                    main.update(container, 0f)
                }
                // The oracle's console SECTOR FINAL: the jump to the last stand sector, its arrival boxes closed.
                if (phase.getAttributeValue("final") == "true") {
                    val st = main.currentState as InGameState
                    val info = st.gameMap.sectors.flatten().first { it.type.name == "FINAL" }
                    st.shipUI.diagnosticCloseWindow()
                    st.shipUI.sectorChosen(info)
                    var n = 0
                    while (n < 900 && st.currentBeacon.sector.info !== info) { main.update(container, 1f / 60f); n++ }
                    repeat(240) { main.update(container, 1f / 60f); st.shipUI.diagnosticCloseWindow() }
                }
                // `fedbase="x,y"`: the flagship moved onto the Federation base, the player onto the base's neighbour at
                // map position x,y (what the oracle's Lua does with Location.boss and StarMap.currentLoc).
                phase.getAttributeValue("fedbase")?.let { at ->
                    val st = main.currentState as InGameState
                    val (x, y) = at.split(',').map { it.trim().toFloat().toInt() }
                    val sector = st.currentBeacon.sector
                    val base = sector.beacons.firstOrNull { it.event.hsName == "FEDERATION_BASE" } ?: sector.finishBeacon
                    sector.bosses.filterIsInstance<xyz.znix.xftl.game.FlagshipBoss>().first().debugTeleportTo(base)
                    // The map is ours, not the oracle's: the base's neighbour nearest to the oracle's.
                    val next = base.neighbours.minByOrNull { (it.pos.x - x) * (it.pos.x - x) + (it.pos.y - y) * (it.pos.y - y) } ?: error("The base has no neighbour")
                    st.setCurrentBeacon(next)
                    rows += "fedp|${base.pos.x},${base.pos.y}|${next.pos.x},${next.pos.y}"
                    repeat(240) { main.update(container, 1f / 60f); st.shipUI.diagnosticCloseWindow() }
                }
                // `fleetrow="label"`: a row of Hyperspace's custom fleet (names, ships per layer, places).
                phase.getAttributeValue("fleetrow")?.let { rows += it + "|" + xyz.znix.xftl.hs.events.HsCustomFleet.diagnosticState }
                // `jumpbase="true"`: the jump onto the Federation base (the map click on it).
                if (phase.getAttributeValue("jumpbase") == "true") {
                    val st = main.currentState as InGameState
                    val sector = st.currentBeacon.sector
                    st.beginJumpOut(sector.beacons.firstOrNull { it.event.hsName == "FEDERATION_BASE" } ?: sector.finishBeacon)
                }
                // The oracle's pick / answer of a choice box: that option of the open box (applies its resources).
                phase.getAttributeValue("choose")?.let { n ->
                    (main.currentState as InGameState).shipUI.diagnosticDialogue?.selectOption(n.trim().toInt())
                    main.update(container, 0f)
                }
                // The oracle's console step: the command box's command, run at once.
                phase.getAttributeValue("console")?.let { xyz.znix.xftl.hs.render.HsConsole.run(main.currentState as InGameState, it) }
                // The oracle's key step: a key press and release on the real input (`key="code,char"`).
                phase.getAttributeValue("key")?.let { at ->
                    val (code, char) = at.split(',').map { it.trim().toInt() }
                    BrowserEngine.key(code, char, true)
                    main.update(container, 0f)
                    BrowserEngine.key(code, char, false)
                    main.update(container, 0f)
                }
                // `jump="dx,dy"`: the jump onto the neighbour nearest to that offset from here (the oracle's map click).
                phase.getAttributeValue("jump")?.let { at ->
                    val st = main.currentState as InGameState
                    val (dx, dy) = at.split(',').map { it.trim().toInt() }
                    val here = st.currentBeacon.pos
                    val x = here.x + dx; val y = here.y + dy
                    val next = st.currentBeacon.neighbours.minByOrNull { (it.pos.x - x) * (it.pos.x - x) + (it.pos.y - y) * (it.pos.y - y) } ?: error("The beacon has no neighbour")
                    st.beginJumpOut(next)
                }
                // `overtake="true"`: the fleet takes every beacon inside its zone now (the zone was moved by a script)
                if (phase.getAttributeValue("overtake") == "true") {
                    val st = main.currentState as InGameState
                    st.currentBeacon.sector.overtakeReached(st)
                }
                // The oracle's Space key: the quick pause.
                if (phase.getAttributeValue("pause") == "toggle") (main.currentState as InGameState).toggleQuickPause()
                // The oracle's click / rclick steps (game coordinates): move, press and release on the real input.
                for ((attr, button) in listOf("click" to 0, "rclick" to 1)) {
                    val at = phase.getAttributeValue(attr) ?: continue
                    val (x, y) = at.split(',').map { it.trim().toInt() }
                    // One rendered frame first: the ships' screen positions are laid out while drawing.
                    main.render(container, BrowserEngine.diagnosticGraphics())
                    BrowserEngine.mouse(x, y, 0, 0)
                    main.update(container, 0f)
                    // InGameState polls the buttons in its update: a zero-time frame with the button held, one after.
                    BrowserEngine.mouse(x, y, 1, button)
                    main.update(container, 0f)
                    BrowserEngine.mouse(x, y, 2, button)
                    main.update(container, 0f)
                }
                val code = phase.getAttributeValue("lua")
                val frames = phase.getAttributeValue("frames")?.toInt() ?: 0
                val close = phase.getAttributeValue("close") == "true"
                // `draw`: every frame of the phase is also rendered (tooltip delays and other render-timed effects)
                val draw = phase.getAttributeValue("draw") == "true"
                if (frames == 0) {
                    code?.let { run(it) }
                    if (close) (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    continue
                }
                // As BrowserHsWp4DronesDiagnostics: the console Lua runs in the phase's first frame between the
                // update and ON_TICK.
                repeat(frames) { i ->
                    // as BrowserEngine.step: the frame's SpeedFactor first (scripts time themselves with it)
                    main.update(container, xyz.znix.xftl.hs.lua.LuaFps.onLoop(1f / 60f))
                    if (i == 0) {
                        code?.let { run(it) }
                        if (close) (main.currentState as InGameState).shipUI.diagnosticCloseWindow()
                    }
                    xyz.znix.xftl.hs.lua.LuaEvents.onTick()
                    if (draw) main.render(container, BrowserEngine.diagnosticGraphics())
                }
            }
            return "{\"rows\":" + rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) } +
                ",\"errors\":" + errors.joinToString(",", "[", "]") { BrowserDebug.quote(it) } + "}"
        } finally {
            lua.sink = oldSink
            xyz.znix.xftl.hs.sector.HsSeeds.requestedSeed = oldSeed
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
