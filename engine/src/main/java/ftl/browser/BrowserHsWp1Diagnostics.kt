package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsEventEffects
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaAdaptersWP1
import xyz.znix.xftl.hs.lua.LuaEvents
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * WP1 checks (`BrowserEngine.hsWp1Probe`), Hyperspace sessions only:
 * - `chains` (needs HS Lua): HAS_EQUIPMENT, HAS_AUGMENTATION, GET_AUGMENTATION_VALUE, DANGEROUS_ENVIRONMENT,
 *   GET_BEACON_HAZARD and GET_HAZARD_FLASH reach their engine rules with HS's argument and return handling.
 * - `crewseed` (vanilla too): WorldManager's crew-kill seed is cleared when a new event window starts (arrival, ship
 *   endings), not for the events a choice leads to.
 * - `scene`: SpaceManager::SwitchPlanet's draws and result (stream by context) and removeHazards / removeNebula keeping
 *   the scene, with the draws of HS's setter calls.
 * Returns "PASS ..." or throws; leaves the engine at the main menu.
 */
object BrowserHsWp1Diagnostics {
    private const val DT = .05f

    private const val FRAME_LUA = """
        WP1 = {}
        local IE = Defines.InternalEvents
        function WP1.mvPin(kind, t)
          WP1.pinKind, WP1.pinTime = kind, t
          if kind == nil then
            for _, d in pairs(mods.multiverse.environmentDataStar) do Hyperspace.playerVariables[d.varName] = 0; d.flashTimeCurrent = 0 end
          end
        end
        script.on_internal_event(IE.ON_TICK, function()
          if WP1.pinKind ~= nil then
            local d = mods.multiverse.environmentDataStar[WP1.pinKind]
            Hyperspace.playerVariables[d.varName] = 1
            d.flashTimeCurrent = WP1.pinTime
            d.flashTimePrevious = WP1.pinTime
          end
        end, -1000)
    """

    /**
     * `frame:<step>` leaves the game running for a screenshot of the oracle's `wp1-mv` session (MV's own star hazard
     * callbacks, pinned as `WP1.mvPin` in tracer/data/hs_oracle/wp1.lua does): `base`, `bluegiant`, `gravastar`, `off`,
     * `planetnone`, `remove` (removeHazards at the start beacon), `neighbour` (the first neighbour gets the hazard event; the result is its map position and the
     * current one); `frame:end` goes back to the menu.
     */
    private fun frame(main: MainGame, container: GameContainer, graphics: Graphics, arg: String): String {
        if (arg == "end") {
            HsLua.sink = null
            main.switchToMainMenu()
            return "PASS hs-wp1 frame end"
        }
        val lines = ArrayList<String>()
        if (main.currentState !is InGameState) {
            start(main, container, "PLAYER_SHIP_MVKESTREL")
            HsLua.runString("wp1f", FRAME_LUA)?.let { error("load: $it") }
        }
        HsLua.sink = { _, t -> if (t.startsWith("WP1")) lines += t.trim() }
        fun lua(code: String) { HsLua.runString("wp1fx", code)?.let { error("lua: $it") } }
        val state = main.currentState as InGameState
        when (arg) {
            "base" -> {}
            "bluegiant" -> lua("WP1.mvPin(nil); WP1.mvPin('blue_giant', 0.4)")
            "gravastar" -> lua("WP1.mvPin(nil); WP1.mvPin('gravastar', 0.4)")
            "off" -> lua("WP1.mvPin(nil)")
            "planetnone" -> lua("Hyperspace.App.world.space:SwitchPlanet('NONE')")
            "remove" -> HsEventEffects.removeHazards(state)
            "neighbour" -> {
                // Hyperspace.Event:CreateEvent (EventGenerator) is not ported: set the event on the Beacon directly.
                val cur = state.currentBeacon
                val n = cur.neighbours[0]
                n.event = state.eventManager["HER_HAZARDS_BLUEGIANT"].resolve()
                lines += "WP1N ${n.pos.x} ${n.pos.y} ${cur.pos.x} ${cur.pos.y}"
            }
            else -> error("unknown frame $arg")
        }
        repeat(3) { BrowserEngine.step(DT); main.render(container, graphics) }
        return "PASS hs-wp1 frame $arg danger=${state.isInDanger} hull=${state.player.health} ${lines.joinToString(" | ")}"
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, part: String): String {
        if (part.startsWith("frame:")) return frame(main, container, graphics, part.substring(6))
        val rng = BrowserRandom.snapshot()
        val out = ArrayList<String>()
        try {
            check(HS.active || part == "crewseed") { "hs-wp1 needs a Hyperspace session" }
            when (part) {
                "chains" -> chains(main, container, graphics, out)
                "scene" -> scene(main, container, out)
                "crewseed" -> crewSeed(main, container, out)
                else -> error("unknown part $part")
            }
        } catch (e: Throwable) {
            throw IllegalStateException("after [" + out.joinToString("; ") + "]: " + e + " " + e.stackTrace.take(6).joinToString(" < "), e)
        } finally {
            BrowserRandom.restore(rng)
            LuaEvents.generation = false
            NativeRandom.trace = null
            main.switchToMainMenu()
        }
        return "PASS hs-wp1 $part: " + out.joinToString("; ")
    }

    private fun start(main: MainGame, container: GameContainer, ship: String): InGameState {
        main.startDiagnosticGame(ship, Difficulty.EASY)
        // The start beacon's event chain (Multiverse has several boxes): answer each with its last choice.
        repeat(60) {
            main.update(container, DT)
            if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
            }
        }
        repeat(200) {
            val state = main.currentState as InGameState
            if (!state.shipUI.isWindowOpen) return@repeat
            val d = state.shipUI.diagnosticDialogue
            if (d != null) d.selectOption(maxOf(0, d.optionCount - 1)) else state.shipUI.escapePressed()
            main.update(container, DT)
        }
        val state = main.currentState as InGameState
        check(!state.shipUI.isWindowOpen) { "a window stays open at the start: ${state.shipUI.diagnosticWindow}" }
        if (state.isPaused) state.toggleQuickPause()
        return state
    }

    private fun chains(main: MainGame, container: GameContainer, graphics: Graphics, out: ArrayList<String>) {
        check(HS.luaActive) { "chains needs HS Lua" }
        val state = start(main, container, "PLAYER_SHIP_HARD")
        val lines = ArrayList<String>()
        HsLua.sink = { _, t -> if (t.startsWith("WP1")) lines += t.trim() }
        try {
            HsLua.runString("wp1", """
                WP1 = {flashCalls = 0}
                local ev = Defines.InternalEvents
                local cont = Defines.Chain.CONTINUE
                script.on_internal_event(ev.HAS_EQUIPMENT, function(s, name, v)
                    if name == "WP1_EQ" then return cont, v + 7 end
                    if name == "WP1_EQF" then return cont, 2.5 end
                end)
                script.on_internal_event(ev.HAS_AUGMENTATION, function(s, name, v)
                    if name == "FTL_BOOSTER" then return cont, v + 3 end
                end)
                script.on_internal_event(ev.GET_AUGMENTATION_VALUE, function(s, name, v)
                    if name == "FTL_BOOSTER" then return cont, v * 2 + 0.5 end
                end)
                script.on_internal_event(ev.DANGEROUS_ENVIRONMENT, function(res)
                    if WP1.danger ~= nil then return WP1.danger end
                end)
                script.on_internal_event(ev.GET_BEACON_HAZARD, function(loc)
                    if WP1.label ~= nil then return WP1.label end
                end)
                script.on_internal_event(ev.GET_HAZARD_FLASH, function(op)
                    WP1.flashCalls = WP1.flashCalls + 1
                    if WP1.flash then return 0.25, 0.5, 0.75, 0.5 end
                end)
            """.trimIndent())?.let { error("load: $it") }
            fun lua(code: String) { HsLua.runString("wp1x", code)?.let { error("lua: $it") } }
            fun last(): List<String> = lines.last().split(' ')

            lua("""local s = Hyperspace.Global.GetInstance():GetShipManager(0)
                log('WP1A ' .. s:HasEquipment('WP1_EQ') .. ' ' .. s:HasEquipment('WP1_EQF') .. ' ' .. s:HasAugmentation('FTL_BOOSTER') .. ' ' .. s:GetAugmentationValue('FTL_BOOSTER') .. ' ' .. s:HasEquipment('shields'))""")
            val a = last()
            check(a[1].toDouble() == 7.0) { "HAS_EQUIPMENT ${a[1]}" }
            check(a[2].toDouble() == 0.0) { "HAS_EQUIPMENT float result changed the value: ${a[2]}" }
            check(a[3].toDouble() == 3.0) { "HAS_AUGMENTATION ${a[3]}" }
            check(a[4].toDouble() == 0.5) { "GET_AUGMENTATION_VALUE ${a[4]}" }
            check(a[5].toDouble() >= 1.0) { "unregistered name changed: ${a[5]}" }
            check(state.player.hasAugment("FTL_BOOSTER")) { "engine hasAugment misses the Lua count" }
            out += "equipment ${a[1]}/${a[2]}, augmentation ${a[3]}, value ${a[4]}"

            // DANGEROUS_ENVIRONMENT: the first callback that returns a value decides (false included)
            val ds = ArrayList<String>()
            for (v in listOf("nil", "true", "false")) {
                lua("WP1.danger = $v; log('WP1D ' .. tostring(Hyperspace.App.world.space:DangerousEnvironment()))")
                ds += last()[1] + "/" + state.isInDanger
            }
            check(ds == listOf("false/false", "true/true", "false/false")) { "DANGEROUS_ENVIRONMENT $ds" }
            lua("WP1.danger = nil")
            out += "danger $ds"

            // GET_BEACON_HAZARD: a string is the hazard line, any other value only the map marker
            val beacon = state.currentBeacon
            check(LuaAdaptersWP1.beaconHazardText(beacon) == null && !LuaAdaptersWP1.beaconHazardMarker(beacon)) { "label without a callback value" }
            lua("WP1.label = 'WP1 SOLAR FLARES'")
            check(LuaAdaptersWP1.beaconHazardText(beacon) == "WP1 SOLAR FLARES") { "label text" }
            check(LuaAdaptersWP1.beaconHazardMarker(beacon)) { "marker for a string" }
            lua("WP1.label = true")
            check(LuaAdaptersWP1.beaconHazardText(beacon) == null) { "label for a boolean" }
            check(LuaAdaptersWP1.beaconHazardMarker(beacon)) { "marker for a boolean" }
            lua("WP1.label = nil")
            out += "beacon hazard text/marker"

            // GET_HAZARD_FLASH: four values replace the alpha and the colour; the overlay asks twice when it draws
            lua("WP1.flash = nil")
            check(LuaAdaptersWP1.flashOpacity(0.3f) == 0.3f && LuaAdaptersWP1.flashColour == null) { "flash without values" }
            lua("WP1.flash = true")
            check(LuaAdaptersWP1.flashOpacity(0f) == 0.5f) { "flash alpha" }
            val c = LuaAdaptersWP1.flashColour ?: error("flash colour")
            check(c.r == 0.25f && c.g == 0.5f && c.b == 0.75f && c.a == 0.5f) { "flash colour $c" }
            lua("WP1.flashCalls = 0")
            main.render(container, graphics)
            lua("log('WP1F ' .. WP1.flashCalls)")
            check(last()[1].toDouble() == 2.0) { "flash callbacks per flash frame ${last()[1]}" }
            lua("WP1.flash = nil; WP1.flashCalls = 0")
            main.render(container, graphics)
            lua("log('WP1F ' .. WP1.flashCalls)")
            check(last()[1].toDouble() == 1.0) { "flash callbacks per quiet frame ${last()[1]}" }
            out += "flash alpha 0.5 colour 0.25/0.5/0.75, 2 calls on a flash frame, 1 on a quiet one"
        } finally {
            HsLua.sink = null
        }
    }

    private fun crewSeed(main: MainGame, container: GameContainer, out: ArrayList<String>) {
        val state = start(main, container, "PLAYER_SHIP_HARD")
        val events = state.eventManager
        val ui = state.shipUI
        if (state.enemy != null) state.browserEndHostility()
        val parent = state.eventCreator.create(events["PIRATE_SLAVER"], 0) ?: error("No PIRATE_SLAVER")
        ui.showEventDialogue(parent)
        val dialog = ui.diagnosticDialogue ?: error("No PIRATE_SLAVER box")
        check(!events.hasCrewKillSeed) { "A new window kept the old seed" }
        // The box draws the seed when its own event kills crew; the events its choices lead to reuse it.
        val seed = events.crewKillSeed()
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        try { dialog.selectOption(0) } finally { NativeRandom.trace = null }
        check(events.hasCrewKillSeed && events.crewKillSeed() == seed) { "The choice's event drew a new seed" }
        out += "a choice's event keeps the seed (${trace.size} draws for its box)"
        // A new window (a ship ending, the next arrival) starts without one
        ui.diagnosticCloseWindow()
        val next = state.eventCreator.create(events["PIRATE_SLAVER"], 0) ?: error("No PIRATE_SLAVER")
        ui.showEventDialogue(next)
        check(!events.hasCrewKillSeed) { "The next window kept the seed" }
        out += "a new window clears it"
    }

    private fun scene(main: MainGame, container: GameContainer, out: ArrayList<String>) {
        val state = start(main, container, "PLAYER_SHIP_HARD")
        val beacon = state.currentBeacon
        var env = beacon.getEnvironment(state)

        // SwitchPlanet: pick (repeated while it equals the current planet), then x, y, turn, on the generation stream
        // inside a generation-time callback and on the presentation stream outside one.
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        NativeRandom.seed(77)
        LuaEvents.generation = true
        val d = env.hsSwitchPlanet("PLANET")
        LuaEvents.generation = false
        val r = trace.drop(2)
        check(r.size >= 4) { "draws $trace" }
        val n = r.size
        check(d[2] == r[n - 3] % maxOf(1, 1280 - d[0]) && d[3] == r[n - 2] % maxOf(1, 720 - d[1]) && d[4] == (if (r[n - 1] % 2 == 1) 180 else 0)) {
            "placement ${d.toList()} from $r"
        }
        // Oracle (sessions/wp1-explore.json, F3): seed 77 gives SwitchPlanet("PLANET") 280 x 280 at (11, 403), not turned,
        // and the next draw after it is the fifth (one pick, then x, y and the turn). Same list, same stream, same result.
        check(r.size == 4 && d.toList() == listOf(280, 280, 11, 403, 0)) { "SwitchPlanet from seed 77: ${d.toList()} after ${r.size} draws, the oracle has 280 280 11 403 0 after 4" }
        out += "SwitchPlanet ${r.size} draws, placed ${d.toList()} (oracle: same)"
        trace.clear()
        env.hsSwitchPlanet("PLANET")
        check(trace.isEmpty()) { "presentation context drew from the generation stream: $trace" }
        check(env.hsSwitchPlanet("NONE").all { it == 0 } && env.diagnosticPlanet == null) { "NONE" }
        check(trace.isEmpty()) { "NONE drew" }
        LuaEvents.generation = true
        env.hsSwitchPlanet("PLANET")
        LuaEvents.generation = false

        // removeHazards keeps the scene and draws HS's setter calls: 4 on the stream, 6 when no ASB or fleet is up
        // (the planet becomes a PLANET_POPULATED one at (600, 247)); removeNebula draws nothing and keeps the scene.
        val before = env.diagnosticPlanet
        val fleet = env.diagnosticHasFleet
        trace.clear()
        HsEventEffects.removeHazards(state)
        val drawn = trace.size
        env = beacon.getEnvironment(state)
        val after = env.diagnosticPlanet
        if (fleet) {
            check(drawn == 4 && after == before) { "with a fleet: $drawn draws, planet $before -> $after" }
        } else {
            check(drawn == 6 && after != null && after[0] == 600 && after[1] == 247) { "no fleet: $drawn draws, planet $before -> $after" }
        }
        trace.clear()
        HsEventEffects.removeNebula(state)
        check(trace.isEmpty() && beacon.getEnvironment(state).diagnosticPlanet == after) {
            "removeNebula: $trace ${beacon.getEnvironment(state).diagnosticPlanet}"
        }
        out += "removeHazards $drawn draws, planet $before -> $after (fleet $fleet), removeNebula 0 draws"
    }
}
