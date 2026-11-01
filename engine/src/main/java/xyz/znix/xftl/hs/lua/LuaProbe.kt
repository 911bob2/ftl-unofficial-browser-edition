// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import ftl.browser.BrowserDiagnostics
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * Diagnostics mode "hs-lua" (Tests page): the Lua runtime against real engine objects in a flagship fight.
 * 1. Bridge cost: the MV-sized bench load (tests/hs-lua/bench.mjs) dispatched directly, model counts.
 * 2. In situ: whole engine frames (update + render) with the bench callbacks firing from the real hooks, compared
 *    with the same frames without Lua.
 * 3. Script API semantics (priority order, HALT/PREEMPT, argument rewriting, error handling, instruction cap).
 * Lua is switched off again at the end, so later modes run vanilla (a modded session reloads its own scripts).
 */
object LuaProbe {
    val BENCH_LUA = """
local counter = 0
for i = 1, 26 do
  script.on_internal_event(Defines.InternalEvents.ON_TICK, function()
    local ship = Hyperspace.ships.player
    if Hyperspace.App.world.bStartedGame and ship then
      local drones = ship.spaceDrones
      if drones:size() >= 8 then
        for d = 0, 7 do
          local drone = drones[d]
          if drone.iShipId == 1 and drone.powered then counter = counter + 1 end
        end
      else
        -- the fixture launches no drones: 8 crew members stand in with the same crossings (element, 2 fields)
        local list = ship.vCrewList
        local n = list:size()
        for d = 0, 7 do
          local c = list[d % n]
          if c.iShipId == 1 and c.bMindControlled then counter = counter + 1 end
        end
      end
    end
  end)
end
for i = 1, 19 do
  script.on_internal_event(Defines.InternalEvents.SHIP_LOOP, function(ship)
    if ship.ship.hullIntegrity.first < 5 or ship.iShipId > 10 then counter = counter + 1 end
  end)
end
for i = 1, 2 do
  script.on_internal_event(Defines.InternalEvents.CREW_LOOP, function(crew)
    local health = crew.health
    if health.first < health.second then health.first = health.first + 0.0001 end
  end)
end
local color = Graphics.GL_Color(1, 0.5, 0.25, 1)
for i = 1, 16 do
  script.on_render_event(Defines.RenderEvents.LAYER_FOREGROUND, function()
    for k = 1, 5 do Graphics.CSurface.GL_DrawRect(k, k, 10, 10, color) end
  end, function() end)
end
function bench_counter() return counter end
"""

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, framesArg: Int): String {
        val frames = if (framesArg > 0) framesArg else 2000
        val out = StringBuilder()
        BrowserDiagnostics.performancePreset(main, container, 1)
        val state = main.currentState as InGameState
        HsLua.start(main, graphics)
        try {
            val err = HsLua.runString("bench.lua", BENCH_LUA)
            check(err == null) { "bench script failed: $err" }
            // ---- 1. direct dispatch with the model's counts (2 ships, 16 crew each from the real crew lists)
            val ships = listOf(state.player, state.enemy!!)
            val crew = ships.map { s -> List(16) { i -> s.crew[i % s.crew.size] } }
            val frame = {
                LuaEvents.onTick()
                for (k in 0..1) {
                    LuaEvents.shipLoop(ships[k])
                    for (c in crew[k]) LuaEvents.crewLoop(c)
                }
                val idx = LuaEvents.renderPre(A.RE_LAYER_FOREGROUND)
                LuaEvents.renderPost(A.RE_LAYER_FOREGROUND, idx)
            }
            repeat(200) { frame() }
            var gateFailed = false
            // variants: profiling on/off; "bridge only" detaches the Graphics so GL_DrawRect does no GL work
            for (variant in 0 until 3) {
                val profiling = variant != 1
                val draw = variant != 2
                LuaJs.setProfiling(profiling)
                HsLua.graphics = if (draw) graphics else null
                val times = DoubleArray(frames)
                val t0 = LuaJs.now()
                for (i in 0 until frames) { val a = LuaJs.now(); frame(); times[i] = LuaJs.now() - a }
                val total = LuaJs.now() - t0
                times.sort()
                val ok = total / frames < 0.5
                if (!ok && draw) gateFailed = true
                out.append(if (ok) "PASS" else "FAIL").append(" bridge (real objects, model counts, profiling ").append(if (profiling) "on" else "off")
                    .append(if (draw) ", drawing" else ", bridge only").append("): mean ").append(fmt(total / frames)).append(" ms, p50 ")
                    .append(fmt(times[frames / 2])).append(" ms, p99 ").append(fmt(times[(frames * 99) / 100])).append(" ms over ").append(frames)
                    .append(" frames; drones ").append(state.player.externalDrones.size).append('\n')
            }
            HsLua.graphics = graphics
            LuaJs.setProfiling(true)
            // ---- 2. in situ: whole engine frames (update + render), Lua on and off alternating frame by frame
            val n = 600
            val on = DoubleArray(n / 2); val off = DoubleArray(n / 2)
            // warm-up: the first real frame after the direct bench waits for the GPU work that bench queued
            repeat(20) { i -> HS.luaActive = i % 2 == 0; main.update(container, 1f / 60f); main.render(container, graphics) }
            for (i in 0 until n) {
                val lua = i % 2 == 0
                HS.luaActive = lua
                val a = LuaJs.now()
                main.update(container, 1f / 60f); if (HS.luaActive) LuaEvents.onTick(); main.render(container, graphics)
                val dt = LuaJs.now() - a
                if (lua) on[i / 2] = dt else off[i / 2] = dt
            }
            HS.luaActive = true
            on.sort(); off.sort()
            out.append("PASS engine frame (update+render), median with bench Lua ").append(fmt(on[on.size / 2])).append(" ms vs without ")
                .append(fmt(off[off.size / 2])).append(" ms; mean ").append(fmt(on.average())).append(" vs ").append(fmt(off.average()))
                .append(" ms; p99 ").append(fmt(on[on.size * 99 / 100])).append(" vs ").append(fmt(off[off.size * 99 / 100]))
                .append(" ms; max ").append(fmt(on.last())).append(" vs ").append(fmt(off.last()))
                .append(" ms (").append(n / 2).append(" alternating frames each, real hooks; crew ")
                .append(state.player.crew.size).append('+').append(state.enemy?.crew?.size ?: 0).append(")\n")
            // ---- 3. per-crossing costs (ns per operation, loop overhead subtracted)
            out.append(micro(state)).append('\n')
            // ---- 4. semantics
            out.append(LuaSemantics.run()).append('\n')
            out.append("PROFILE ").append(HsLua.profileJson(false).take(600))
            if (gateFailed) return "FAIL gate (0.5 ms per MV-sized frame)\n$out"
        } finally {
            HsLua.stop()
            // a modded session gets its own scripts back (fresh state, as after a page load)
            if (HS.scripts.isNotEmpty()) HsLua.startForSession(main, graphics)
        }
        return out.toString()
    }

    private val MICRO = listOf(
        "loop" to "",
        "getn ship.iShipId" to "local x = ship.iShipId",
        "geto ship.ship" to "local x = ship.ship",
        "geto crew.health" to "local x = crew.health",
        "getn pair.first" to "local x = hp.first",
        "setn pair.first" to "hp.first = hp.first",
        "method vector:size()" to "local x = drones:size()",
        "method GL_DrawRect" to "Graphics.CSurface.GL_DrawRect(1, 1, 2, 2, color)",
        "module Hyperspace.ships.player" to "local x = Hyperspace.ships.player",
        "global GetShipManager(0)" to "local x = Hyperspace.Global.GetInstance():GetShipManager(0)",
    )

    /** ns per operation for each crossing kind, measured with 20,000 iterations in one chunk. */
    private fun micro(state: InGameState): String {
        val n = 20000
        HsLua.runString("micro.lua", "mship = Hyperspace.ships.player mcrew = Hyperspace.ships.player.vCrewList[0] " +
            "mcolor = Graphics.GL_Color(1, 1, 1, 1)")
        val sb = StringBuilder("PASS per-operation cost (ns, $n iterations):")
        var base = 0.0
        for ((name, body) in MICRO) {
            val code = "local ship, crew, color = mship, mcrew, mcolor local hp = crew.health local drones = ship.spaceDrones " +
                "for i = 1, $n do $body end"
            var best = Double.MAX_VALUE
            for (rep in 0 until 3) {
                val a = LuaJs.now()
                val err = LuaJs.run("micro", code, false)
                LuaGraphics.flushQuads()
                val t = LuaJs.now() - a
                if (err != null) return "FAIL micro $name: $err"
                if (t < best) best = t
            }
            val ns = best * 1e6 / n
            if (name == "loop") base = ns else sb.append(' ').append(name).append(' ').append(Math.round(ns - base))
        }
        sb.append(" (loop ").append(Math.round(base)).append(')')
        return sb.toString()
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 10000.0) / 10000.0
        return r.toString()
    }

    @Suppress("unused")
    private fun crewOf(state: InGameState): List<AbstractCrew> = state.player.crew
}
