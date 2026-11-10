package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.HsCrewMember
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.StatBoostDefs
import xyz.znix.xftl.hs.stat.StatBoostManager

/**
 * statcrew checks (`BrowserEngine.hsStatCrewProbe`): the races of hyperspace.xml, then for each listed player ship a
 * new run and every starting crew member's HS stats (CalculateStat for each CrewStat), health and skill maxima, in the
 * shape of the oracle's per-ship start dumps (.build/hs/oracle/ships). Leaves the engine at the main menu.
 */
object BrowserStatCrewDiagnostics {
    private fun q(s: String) = BrowserDebug.quote(s)
    private fun n(v: Float): String = if (v.isNaN() || v.isInfinite()) q(v.toString()) else v.toString()

    fun run(main: MainGame, ships: String): String {
        val sb = StringBuilder()
        sb.append("{\"hyperspace\":").append(HS.active)
        sb.append(",\"races\":").append(CustomCrewManager.races.size)
        sb.append(",\"powerDefs\":").append(CustomCrewManager.powerDefs.size)
        sb.append(",\"resourceDefs\":").append(CustomCrewManager.resourceDefs.size)
        sb.append(",\"statBoostDefs\":").append(StatBoostDefs.defs.size)
        sb.append(",\"ships\":[")
        val rng = BrowserRandom.snapshot()
        try {
            var first = true
            for (ship in ships.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                if (!first) sb.append(',')
                first = false
                sb.append("{\"bp\":").append(q(ship))
                try {
                    main.startDiagnosticGame(ship, Difficulty.NORMAL)
                    val state = main.currentState as InGameState
                    if (HS.active) {
                        sb.append(",\"augments\":{")
                        var fa = true
                        for ((n, c) in xyz.znix.xftl.hs.stat.AugmentStatBoosts.augmentCounts(state.player)) {
                            if (!fa) sb.append(',')
                            fa = false
                            sb.append(q(n)).append(':').append(c)
                        }
                        sb.append("},\"augmentBoosts\":{")
                        var fb = true
                        for ((n, _) in xyz.znix.xftl.hs.stat.AugmentStatBoosts.augmentCounts(state.player)) {
                            val d = xyz.znix.xftl.hs.stat.AugmentStatBoosts.defs[n] ?: continue
                            if (!fb) sb.append(',')
                            fb = false
                            sb.append(q(n)).append(":[")
                            for ((i, b) in d.withIndex()) {
                                if (i > 0) sb.append(',')
                                sb.append("{\"stat\":").append(q(b.stat.name)).append(",\"amount\":").append(n(b.amount))
                                    .append(",\"white\":").append(b.whiteList.size).append(",\"black\":").append(b.blackList.size)
                                    .append(",\"duration\":").append(n(b.duration)).append('}')
                            }
                            sb.append(']')
                        }
                        sb.append('}')
                    }
                    sb.append(",\"systems\":[")
                    for ((i, sys) in state.player.systems.withIndex()) {
                        if (i > 0) sb.append(',')
                        sb.append("{\"id\":").append(xyz.znix.xftl.ai.nativeSystemId(sys))
                            .append(",\"power\":").append(HsCrew.effectivePower(sys))
                            .append(",\"room\":").append(sys.room?.id ?: -1).append('}')
                    }
                    // "crew": as the oracle reads it in script.on_init (before the first WorldManager::OnLoop, so
                    // no passive/room boosts are gathered yet); "crewLoop": after one StatBoostManager::OnLoop.
                    sb.append("],\"crew\":[")
                    crewList(sb, state)
                    if (HS.active) {
                        StatBoostManager.onLoop(state)
                        sb.append("],\"crewLoop\":[")
                        crewList(sb, state)
                        sb.append("],\"c6\":").append(BrowserPsC6Checks.run(state))
                        sb.append(",\"powerSmoke\":[")
                        powerSmoke(sb, state)
                    }
                    sb.append("]")
                } catch (e: Throwable) {
                    sb.append(",\"error\":").append(q(e.toString()))
                }
                sb.append("}")
            }
        } finally {
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
        sb.append("]")
        // parity sweep C6: a win power ends a run of its own
        if (HS.active) sb.append(",\"c6win\":").append(BrowserPsC6Checks.win(main))
        sb.append("}")
        return sb.toString()
    }

    /**
     * The oracle's statcrew-powers session replayed here: a new PLAYER_SHIP_MVKESTREL run, the start-beacon chain
     * answered with the session's keys, then [code] (tools/hs-oracle/tracer/data/hs_oracle/statcrew.lua) and
     * `SC.runAll()` through the HS Lua runtime, stepping 1/60 s frames until `sc_done` (or [maxFrames]). Returns the
     * script's `SCJ` lines as a JSON array plus the Lua errors seen.
     */
    fun luaScenario(main: MainGame, code: String, keys: String, maxFrames: Int, reloadAt: String = ""): String {
        // Tags (scenario:mark) after whose dump the run is saved and loaded again (save/reload mid-effect): the
        // driver dumps "<tag>:presave" before and "<tag>:reloaded" after, and the scenario goes on in the loaded game.
        val reloads = reloadAt.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        var pendingReload: String? = null
        val reloadInfo = ArrayList<String>()
        val lines = ArrayList<String>()
        val errors = ArrayList<String>()
        var done = false
        // frames the scenario asks for (`sc_shot` with a tag): drawn and read back after the frame (PNG data URLs)
        val shots = LinkedHashMap<String, String>()
        var pendingShot: String? = null
        val sink: (Int, String) -> Unit = { kind, text ->
            if (text.startsWith("SCJ ")) {
                lines += text.substring(4).trimEnd()
                if (text.contains("\"k\":\"sc_shot\"")) pendingShot = Regex("\"tag\":\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "shot${shots.size}"
                for (t in reloads) if (text.contains("\"tag\":\"$t\"")) { pendingReload = t; reloads.remove(t); break }
                if (text.contains("\"k\":\"sc_done\"")) done = true
            } else if (kind >= 2 && errors.size < 50) errors += text.take(500)
        }
        val rng = BrowserRandom.snapshot()
        val picked = ArrayList<String>()
        // One 1/60 s frame. The frames are not drawn, but ShipStatus::RenderResources' resource messages are made in the
        // frame's render (PlayerShipUI.prepareResourceMessages), so that part runs every frame as in a drawn game.
        fun step() {
            BrowserEngine.step(1f / 60f)
            (main.currentState as? InGameState)?.shipUI?.prepareResourceMessages()
        }
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            var state = main.currentState as InGameState
            // The start-beacon chain: wait for each box, answer it with the session's key.
            for (k in keys.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                var n = 0
                while (!state.shipUI.hsChoiceBoxOpen() && n < 400) { step(); n++ }
                if (!state.shipUI.hsChoiceBoxOpen()) { picked += "no box for key $k"; break }
                picked += (state.shipUI.hsOpenEventName() ?: "?") + "=" + k
                val c = k[0]
                BrowserEngine.key(c.code, c.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(c.code, c.code, false); BrowserEngine.step(0f)
                repeat(30) { step() }
            }
            state = main.currentState as InGameState
            if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
            // `--@console <command>` lines of the scenario: HS console commands before it starts (as the oracle session's
            // console steps), each followed by half a second
            for (line in code.lines()) {
                if (!line.startsWith("--@console ")) continue
                xyz.znix.xftl.hs.render.HsConsole.run(main.currentState as InGameState, line.removePrefix("--@console ").trim())
                repeat(30) { step() }
            }
            state = main.currentState as InGameState
            if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
            xyz.znix.xftl.hs.lua.HsLua.sink = sink
            xyz.znix.xftl.hs.lua.HsLua.runString("statcrew.lua", code)?.let { errors += "load: $it" }
            xyz.znix.xftl.hs.lua.HsLua.runString("statcrew-run", "SC.runAll()")?.let { errors += "run: $it" }
            var frames = 0
            while (!done && frames < maxFrames) {
                step(); frames++
                pendingShot?.let { tag ->
                    pendingShot = null
                    BrowserEngine.renderFrame(0f)
                    shots[tag] = BrowserCanvasShot.png()
                    xyz.znix.xftl.hs.lua.HsLua.runString("statcrew-unshot", "if SC.unshot then SC.unshot() end")?.let { errors += "unshot: $it" }
                }
                val t = pendingReload ?: continue
                pendingReload = null
                xyz.znix.xftl.hs.lua.HsLua.runString("statcrew-presave", "SC.dump('$t:presave')")?.let { errors += "presave: $it" }
                val st = main.currentState as InGameState
                val before = xyz.znix.xftl.hs.stat.StatBoostManager.roomBoosts.values.sumOf { it.size }
                val xml = BrowserXml.write(st.saveGameState())
                main.loadSavedGame(BrowserXml.parse(xml))
                val loaded = main.currentState as InGameState
                val after = xyz.znix.xftl.hs.stat.StatBoostManager.roomBoosts.values.sumOf { it.size }
                reloadInfo += "$t: roomBoosts $before -> $after, saved ${Regex("hsRoomBoost").findAll(xml).count()}"
                if (loaded.isPaused && !loaded.shipUI.isWindowOpen) loaded.toggleQuickPause()
                // one frame so the stat boost lists are rebuilt from the loaded state (StatBoostManager::OnLoop)
                step(); frames++
                xyz.znix.xftl.hs.lua.HsLua.runString("statcrew-reloaded", "SC.dump('$t:reloaded')")?.let { errors += "reloaded: $it" }
            }
            if (!done) errors += "no sc_done after $frames frames"
        } catch (e: Throwable) {
            errors += "exception: $e " + e.stackTrace.take(6).joinToString(" < ")
        } finally {
            xyz.znix.xftl.hs.lua.HsLua.sink = null
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
        val sb = StringBuilder("{\"done\":$done,\"reloads\":[" + reloadInfo.joinToString(",") { q(it) } + "],\"picked\":[")
        sb.append(picked.joinToString(",") { q(it) }).append("],\"errors\":[")
        sb.append(errors.joinToString(",") { q(it) }).append("],\"lines\":[")
        sb.append(lines.joinToString(",")).append("],\"shots\":{")
        sb.append(shots.entries.joinToString(",") { q(it.key) + ":" + q(it.value) }).append("}}")
        return sb.toString()
    }

    /**
     * Smoke test of the player's power use: every player crew power is made ready (cooldown full), used through
     * the crew box button path (HsCrewUi.clickButton) when PowerReady says READY, then the world runs 2 s.
     */
    private fun powerSmoke(sb: StringBuilder, state: InGameState) {
        class Rec(val crew: String, val power: String, val ready: Int, val p: xyz.znix.xftl.hs.crew.ActivatedPower, val c: xyz.znix.xftl.crew.AbstractCrew)
        val recs = ArrayList<Rec>()
        val errors = ArrayList<String>()
        for (c in state.player.crew.toList()) {
            if (!CustomCrewManager.isRace(c.codename)) continue
            val ex = HsCrew.ex(c)
            for (p in ex.crewPowers.toList()) {
                if (!p.enabled) continue
                var ready = -1
                try {
                    p.powerCooldown.first = p.powerCooldown.second
                    for (r in p.powerResources) r.powerCooldown.first = r.powerCooldown.second
                    ready = p.powerReady()
                    if (ready == xyz.znix.xftl.hs.crew.PowerReadyState.READY) xyz.znix.xftl.hs.crew.HsCrewUi.clickButton(c, p)
                } catch (e: Throwable) {
                    errors += c.codename + "/" + p.def.name + ": " + e + " " + (e.stackTrace.take(4).joinToString(" < "))
                }
                recs += Rec(c.codename, p.def.name, ready, p, c)
            }
        }
        var error: String? = null
        try {
            repeat(64) { state.updateGameState(1f / 32f) }
        } catch (e: Throwable) {
            error = e.toString() + " " + e.stackTrace.take(4).joinToString(" < ")
        }
        if (error == null && errors.isNotEmpty()) error = errors.joinToString(" | ")
        for ((i, r) in recs.withIndex()) {
            if (i > 0) sb.append(',')
            val nowPowers = xyz.znix.xftl.hs.crew.HsCrew.ex(r.c).crewPowers
            sb.append("{\"crew\":").append(q(r.crew)).append(",\"after\":").append(q(r.c.codename))
                .append(",\"samePower\":").append(nowPowers.any { it === r.p })
                .append(",\"power\":").append(q(r.power))
                .append(",\"ready\":").append(r.ready)
                .append(",\"cooldown\":[").append(n(r.p.powerCooldown.first)).append(',').append(n(r.p.powerCooldown.second)).append(']')
                .append(",\"temp\":").append(r.p.temporaryPowerActive)
                .append(",\"enabled\":").append(r.p.enabled).append(",\"done\":").append(r.p.powerDone)
                .append(",\"defNow\":").append(q(r.p.def.name)).append(",\"hasTemp\":").append(r.p.def.hasTemporaryPower)
                .append(",\"hp\":").append(n(r.c.health))
                .append(",\"charges\":[").append(r.p.powerCharges.first).append(',').append(r.p.powerCharges.second).append("]}")
        }
        if (error != null) {
            if (recs.isNotEmpty()) sb.append(',')
            sb.append("{\"error\":").append(q(error)).append('}')
        }
    }

    private fun crewList(sb: StringBuilder, state: InGameState) {
        for ((i, c) in state.player.crew.withIndex()) {
            if (i > 0) sb.append(',')
            crew(sb, c)
        }
    }

    private fun crew(sb: StringBuilder, c: xyz.znix.xftl.crew.AbstractCrew) {
        sb.append("{\"species\":").append(q(c.codename))
        sb.append(",\"class\":").append(q(c::class.simpleName ?: "?"))
        sb.append(",\"room\":").append(c.room.id)
        sb.append(",\"hp\":").append(n(c.health)).append(",\"hpMax\":").append(n(c.maxHealth))
        if (c is LivingCrew) {
            sb.append(",\"male\":").append(!c.info.isFemale)
            sb.append(",\"skills\":[")
            for ((i, s) in Skill.entries.withIndex()) {
                if (i > 0) sb.append(',')
                val max = s.actionsPerLevel(c.blueprint) * 2
                sb.append('[').append(Math.round(c.info.skills.getValue(s) * max)).append(',').append(max).append(']')
            }
            sb.append(']')
        }
        if (HS.active) {
            val ex = HsCrew.ex(c)
            sb.append(",\"selfId\":").append(ex.selfId)
            sb.append(",\"powers\":[")
            for ((i, p) in ex.crewPowers.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append("{\"name\":").append(q(p.def.name)).append(",\"enabled\":").append(p.enabled)
                sb.append(",\"cooldown\":[").append(n(p.powerCooldown.first)).append(',').append(n(p.powerCooldown.second)).append(']')
                sb.append(",\"charges\":[").append(p.powerCharges.first).append(',').append(p.powerCharges.second).append("]}")
            }
            sb.append("],\"stats\":{")
            val def = ex.definition()
            for ((i, s) in CrewStat.entries.withIndex()) {
                if (i > 0) sb.append(',')
                val v = ex.calculateStat(s, def ?: CustomCrewManager.definition(c.codename))
                sb.append(q(s.name)).append(":{\"v\":").append(n(v)).append(",\"b\":").append(ex.boolResult).append('}')
            }
            sb.append('}')
            sb.append(",\"hs\":").append(c is HsCrewMember)
        }
        sb.append('}')
    }
}
