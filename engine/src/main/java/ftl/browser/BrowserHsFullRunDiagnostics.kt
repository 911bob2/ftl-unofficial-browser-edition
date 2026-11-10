package ftl.browser

import ftl.browser.BrowserHsEventsDiagnostics.Obj
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.DialogueWindow
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SectorMapWindow
import xyz.znix.xftl.game.StoreWindow
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsFlow
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Full-run verification (fullrun workstream, `BrowserEngine.hsFullRunProbe`): plays a seeded Hyperspace run on its own
 * with the same fixed policy as the oracle's `autoplay` with full=1 (tools/hs-oracle/oracle.py): the first enabled
 * choice whose text does not open MV's ship menus, the unvisited neighbour closest to the exit beacon (the flagship's
 * beacon in the last sector), NEXT SECTOR and sector 1 of the map (the upper node), a hostile enemy destroyed at once
 * (console DELETE), boarders killed (HS_ORACLE.repel), store windows closed after listing their contents, and at every
 * beacon HS_ORACLE.heal(6): hull to maximum, player crew to full health, fuel to at least 6. It writes one JSON line
 * per choice box, per beacon (state), per sector (map) and the frame times, for tools/hs-oracle/fullrun_compare.py.
 * Diagnostics only: nothing here changes game logic. Needs HS data. Leaves the engine at the main menu. A run is
 * reproducible: BrowserRandom is seeded from the run seed and the Lua frame clock advances by fixed frames.
 */
object BrowserHsFullRunDiagnostics {
    private const val DT = 0.05f

    /** One choice box of the oracle trace (r8 resync): its event and the random32 state read while it was open ([state] decimal, null if unread). */
    private class OracleBox(val index: Int, val event: String, val state: String?, val res: List<Int>?, val reseed: Int?)

    /** Names that only differ by a trailing counter ('X destroyed  21547') count as one event. */
    private fun normEvent(e: String): String = e.trimEnd { it.isDigit() || it == ' ' }
    // Also MV's menus that lead back to the same box (special ships: the toggle menu's "Nevermind", Sylvan hailing himself).
    private val MENU_PREFIXES = listOf("Do something onboard", "Perform a task", "Do some last minute", "Change your toggle",
        "(Sylvan) Use Sylvan to hail Sylvan")

    /** The oracle's `policy`: first enabled (type != 1) choice that does not open a ship menu, else the first enabled. */
    private fun policy(choices: List<Pair<String, Int>>): Int {
        if (choices.isEmpty()) return 0
        val enabled = choices.indices.filter { choices[it].second != 1 }
        val plain = enabled.filter { i -> MENU_PREFIXES.none { choices[i].first.startsWith(it, ignoreCase = true) } }
        return plain.firstOrNull() ?: enabled.firstOrNull() ?: 0
    }

    /**
     * [scriptText]: first line `seed<TAB>ship<TAB>difficulty (0-2)`, then optional `opt<TAB>key<TAB>value` lines:
     * `beacons` (default 140), `reload` (1: save and reload once per sector), `ftl` (game seconds, default 120),
     * `trace` (1: a `list` line per load-list pick and a `quest` line per quest placement), `rngfrom` (box number: `rng`
     * lines with the generation stream's reseeds and draws), `rngprobe` (event name: one probe draw per box from it on).
     */
    fun run(main: MainGame, container: GameContainer, graphics: Graphics, scriptText: String): String {
        val lines = scriptText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { it.split('\t') }
        val opts = HashMap<String, String>()
        for (l in lines.drop(1)) if (l[0] == "opt") opts[l[1]] = l[2]
        // `opt anygame 1`: a run without Hyperspace too, as a baseline for measurements (it has no oracle to compare with).
        if (!HS.active && opts["anygame"] != "1") return "SKIP hs-fullrun: Hyperspace is not active"
        val maxBeacons = opts["beacons"]?.toInt() ?: 140
        // A policy caught in a mod's menu loop (MV add-on ship menus) never ends; the run stops at this many boxes.
        val maxBoxes = opts["boxes"]?.toInt() ?: 5000
        // r8 resync: `ob<TAB>index<TAB>event<TAB>state` lines (tools/hs-fullrun/steer.py) carry the oracle's choice boxes and
        // the stream state read from its process while the box was open; `oq<TAB>box<TAB>quest<TAB>x,y` the beacon a
        // fleet-run quest landed on. `opt steer 1` turns the steering on; without it only the classification is logged.
        val steerOn = (opts["steer"] ?: "0") == "1"
        val oracleBoxes = lines.filter { it[0] == "ob" && it.size >= 3 }
            .map { OracleBox(it[1].toInt(), it[2], it.getOrNull(3)?.toLongOrNull()?.let { v -> v.toString() },
                it.getOrNull(4)?.split(",")?.mapNotNull { v -> v.toIntOrNull() }?.takeIf { v -> v.size == 4 },
                it.getOrNull(5)?.toLongOrNull()?.toInt()) }
        val oracleQuests = lines.filter { it[0] == "oq" && it.size >= 4 }.map { Triple(it[1].toInt(), it[2], it[3]) }
        val reloadMode = (opts["reload"] ?: "1").toInt()
        val reloadMid = (opts["reloadmid"] ?: "0") == "1"
        val ftlTimeout = opts["ftl"]?.toDouble() ?: 120.0
        val out = StringBuilder()
        val rng = BrowserRandom.snapshot()
        var steers = 0
        var gaps = 0
        fun line(o: Obj) { val t = o.toString(); out.append(t).append('\n'); println("FL " + t) }
        fun finish(result: String, boxes: Int): String {
            val t = Obj().put("k", "result").put("result", result).put("boxes", boxes).put("steers", steers).put("gaps", gaps).toString()
            println("FL " + t)
            return out.append(t).append('\n').toString()
        }

        var boxes = 0
        var boxSeq = 0
        var shipGap: ArrayList<Int>? = null
        var shipGapName = ""
        var rngTrace: ArrayList<Int>? = null
        val rngMarks = ArrayList<String>()
        // r8 resync: which draws are live-stream (NativeRandom.Live), and the steering against the oracle table.
        var lastSeed = 0
        var seededInInterval = false
        var drawsSinceSeed = 0
        var liveSince = 0
        var liveAny = 0   // live draws in the interval, also those before a reseed
        var firstLiveAt = -1
        val liveSites = LinkedHashMap<String, Int>()
        var oc = 0
        var boxOpen = false
        // `rngall` 1: every draw's site per interval, in order (the rngsync line's rngRuns), for tools/hs-fullrun/drawsites.py.
        var allSites = false
        val drawRuns = ArrayList<Pair<String, Int>>()
        fun drawSite(): String {
            return BrowserJsStack.here().lines().drop(2).take(24).joinToString(" < ") { fr ->
                val t = fr.trim().removePrefix("at ")
                val body = t.substringBeforeLast(")")
                val col = body.substringAfterLast(":")
                val ln = body.substringBeforeLast(":").substringAfterLast(":")
                t.substringBefore(" (") + "@" + ln + ":" + col
            }
        }
        val liveImpl = object : NativeRandom.Live {
            override fun onSeed(seed: Int) { lastSeed = seed; seededInInterval = true; drawsSinceSeed = 0; liveSince = 0; firstLiveAt = -1; liveSites.clear() }
            override fun onDraw(live: Boolean) {
                drawsSinceSeed++
                if (allSites) {
                    val key = drawSite()
                    val last = drawRuns.lastOrNull()
                    if (last != null && last.first == key) drawRuns[drawRuns.size - 1] = key to last.second + 1 else drawRuns.add(key to 1)
                }
                if (!live) return
                liveAny++
                if (liveSince++ == 0) firstLiveAt = drawsSinceSeed
                if (liveSince <= 12) {
                    val key = drawSite()
                    liveSites[key] = (liveSites[key] ?: 0) + 1
                }
            }
        }
        fun norm(e: String) = normEvent(e)
        /** The oracle box this box lines up with (the cursor, else up to 6 further on), or null. */
        fun matchOracle(event: String): OracleBox? {
            for (k in 0..6) {
                val b = oracleBoxes.getOrNull(oc + k) ?: return null
                if (norm(b.event) == norm(event)) { oc += k + 1; return b }
            }
            return null
        }
        /**
         * A box of this beacon's chain was a live-stream point (ours drew live there): a reward rolled then (at the event's creation,
         * applied by a later pick of the chain) is the oracle's roll, not ours. Set at that box, ends with the first box of the next beacon.
         */
        var prevLive = false
        var liveBeacon = ""
        var curBeacon = ""
        var lastChoices: List<Pair<String, Int>> = emptyList()
        var lastBoxEvent: String? = null
        val pendingSynthetic = ArrayList<Pair<String, String>>()
        var beaconBoxes = ArrayList<Obj>()
        var gameTime = 0.0
        val frameMs = ArrayList<Double>()
        var sectorFrames = 0
        var renderMs = ArrayList<Double>()
        var frameCount = 0

        var god = false
        fun godMode(game0: MainGame) {
            if (!god) return
            val g = game0.currentState as? InGameState ?: return
            val p = g.player
            if (p.health < p.maxHealth) p.health = p.maxHealth
            for (c in p.crew) if (c is xyz.znix.xftl.crew.LivingCrew && c.ownerShip === p && c.health > 0f && c.health < c.maxHealth) c.health = c.maxHealth
        }

        fun frame(game0: MainGame) {
            val t = BrowserClock.now()
            if (!boxOpen) NativeRandom.liveFrames++
            if (HS.luaActive) {
                // As BrowserEngine.step with scripts running: the step goes through CFPS (Hyperspace.FPS), then
                // ON_TICK. Two real 25 ms frames per harness frame: SpeedFactor 0.4, 0.025 game seconds each, so the
                // harness frame stays DT game seconds and the Lua clock is the same in every run.
                repeat(2) {
                    game0.update(container, xyz.znix.xftl.hs.lua.LuaFps.onLoop(DT / 2))
                    xyz.znix.xftl.hs.lua.LuaEvents.onTick()
                }
            } else {
                game0.update(container, DT)
            }
            godMode(game0)
            // Resource-message construction is native render work and consumes global RNG.
            // Preserve it on every synthetic frame even when the expensive graphics are elided.
            (game0.currentState as? InGameState)?.shipUI?.prepareResourceMessages()
            frameMs.add(BrowserClock.now() - t)
            gameTime += DT
            frameCount++
            if (frameCount % 40 == 0) {
                val r = BrowserClock.now()
                game0.render(container, graphics)
                renderMs.add(BrowserClock.now() - r)
            }
        }

        fun pct(l: List<Double>, q: Double): Double = if (l.isEmpty()) 0.0 else l.sorted()[((l.size - 1) * q).toInt()]
        fun perfLine(sector: Int): Obj {
            val o = Obj().put("k", "perf").put("sector", sector).put("frames", frameMs.size)
                .put("updateMean", if (frameMs.isEmpty()) 0.0 else frameMs.sum() / frameMs.size)
                .put("updateP50", pct(frameMs, 0.5)).put("updateP95", pct(frameMs, 0.95)).put("updateP99", pct(frameMs, 0.99))
                .put("updateMax", frameMs.maxOrNull() ?: 0.0)
                .put("renderSamples", renderMs.size).put("renderP50", pct(renderMs, 0.5)).put("renderP95", pct(renderMs, 0.95))
                .put("heapMB", BrowserHeap.usedAfterGc() / 1048576.0)
            frameMs.clear(); renderMs.clear()
            return o
        }

        fun stateObj(game: InGameState, o: Obj): Obj {
            val vars = Obj()
            for ((k, v) in HsEvents.playerVariables.toSortedMap()) if (v != 0) vars.put(k, v)
            o.put("vars", vars)
            val meta = Obj()
            for ((k, v) in HsEvents.metaVariables.toSortedMap()) if (v != 0) meta.put(k, v)
            o.put("meta", meta)
            o.put("cur", "${game.currentBeacon.pos.x},${game.currentBeacon.pos.y}")
            o.put("sector", game.currentBeacon.sector.sectorNumber)
            o.put("sectorType", HsEvents.descriptionType(game.currentBeacon.sector.info))
            val p = game.player
            o.put("player", Obj().put("bp", p.type.name).put("fuel", p.fuelCount).put("missiles", p.missilesCount)
                .put("drones", p.dronesCount).put("scrap", p.scrap).put("hull", p.health).put("hullMax", p.maxHealth)
                .put("crew", p.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().count { it.ownerShip === p && it.health > 0f })
                .put("weapons", p.hardpoints.mapNotNull { it.weapon?.type?.name })
                .put("augs", p.augments.map { it.name })
                // Round 7: the player's crew in InGameState.playerCrew order (the HS crew picks' list), race:room:health.
                .put("crewList", game.playerCrew.map { "${it.blueprint.name}:${it.room.id}:${it.health.toInt()}" })
                .put("cargo", p.cargoBlueprints.mapNotNull { it?.name })
                .put("boarders", p.crew.filter { it is xyz.znix.xftl.crew.LivingCrew && it.ownerShip !== p && it.health > 0f }.map { it.blueprint.name }))
            o.put("questSeed", HsSeeds.questSeed)
            // Rebel fleet (round 6): the danger zone centre, the pursuit modifier and the overtaken beacons.
            val sec = game.currentBeacon.sector
            o.put("fleet", Obj().put("x", sec.dangerZoneCentre.x).put("y", sec.dangerZoneCentre.y)
                .put("mod", sec.fleetAdvanceModifier)
                .put("taken", sec.beacons.filter { it.isOvertaken }.map { "${it.pos.x},${it.pos.y}" }.sorted()))
            game.enemy?.let { e ->
                o.put("enemy", Obj().put("bp", e.type.name).put("hull", e.maxHealth).put("crew", e.crew.count { it.health > 0f })
                    .put("automated", e.isAutomated).put("systems", e.systems.map { "${it.blueprint.type}:${it.energyLevels}" }.sorted())
                    .put("weapons", e.hardpoints.mapNotNull { it.weapon?.type?.name }).put("crewRaces", e.crew.filter { it.health > 0f }.map { it.blueprint.name }))
            }
            return o
        }

        fun diag(game: InGameState, why: String): Obj {
            val p = game.player
            val o = stateObj(game, Obj().put("k", "stuck").put("why", why))
            o.put("window", game.shipUI.hsDiagnosticWindow?.let { it::class.simpleName })
            o.put("box", game.shipUI.diagnosticDialogue?.hsOpenEvent)
            o.put("boxOptions", game.shipUI.diagnosticDialogue?.optionCount)
            o.put("boxState", game.shipUI.diagnosticDialogue?.diagnosticState())
            o.put("jumping", game.isPlayerJumping)
            o.put("paused", game.isPaused)
            o.put("enemyHostile", game.isEnemyHostile)
            o.put("enemyJumping", game.isEnemyJumping)
            o.put("ftlProgress", p.ftlChargeProgress.toDouble())
            o.put("isFtlCharged", p.isFtlCharged)
            o.put("canChargeFTL", p.canChargeFTL)
            o.put("enginesPower", p.engines?.powerSupplied)
            o.put("pilotingUndamaged", p.piloting?.undamagedEnergy)
            o.put("hasPilot", p.piloting?.hasPilot)
            o.put("enginesHacked", p.engines?.isHackActive)
            o.put("pilotingHacked", p.piloting?.isHackActive)
            o.put("systems", p.systems.map { "${it.blueprint.type}:${it.energyLevels}:${it.damagedEnergyLevels}" })
            o.put("crewAlive", p.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().count { it.ownerShip === p && it.health > 0f })
            val crewHere = ArrayList<Any>()
            for (s in listOfNotNull(game.player, game.enemy)) for (c in s.crew) {
                crewHere.add(Obj().put("ship", if (s === p) "player" else "enemy").put("race", c.blueprint.name)
                    .put("own", (c as? xyz.znix.xftl.crew.LivingCrew)?.ownerShip === p).put("room", c.room.id).put("action", c.currentAction.name)
                    .put("health", c.health.toDouble()).put("target", c.pathingTarget?.let { "${it.room.id}:${it.x},${it.y}" }))
            }
            o.put("crewState", crewHere)
            o.put("beaconEvent", game.currentBeacon.createdEvent.template.hsName ?: "")
            o.put("cargo", p.cargoBlueprints.map { it?.name })
            o.put("hardpoints", p.hardpoints.map { it.weapon?.type?.name })
            return o
        }

        fun mapObj(game: InGameState, o: Obj): Obj {
            val sector = game.currentBeacon.sector
            o.put("sector", sector.sectorNumber)
            o.put("sectorType", HsEvents.descriptionType(sector.info))
            o.put("sectorSeed", HsSeeds.currentSectorSeed)
            o.put("cur", "${game.currentBeacon.pos.x},${game.currentBeacon.pos.y}")
            o.put("locs", sector.beacons.map { b ->
                Obj().put("at", "${b.pos.x},${b.pos.y}").put("ev", b.createdEvent.template.hsName ?: "")
                    .put("exit", b.isExit).put("base", b.isBase)
                    .put("links", b.neighbours.map { "${it.pos.x},${it.pos.y}" })
            })
            o.put("bosses", sector.bosses.map { b -> b.beacon?.let { "${it.pos.x},${it.pos.y}" } ?: "" })
            return o
        }

        fun storeContents(window: StoreWindow): Obj {
            val st = window.store
            val contents = Obj()
            st.hs?.let { hs ->
                contents.put("pages", hs.pages.map { p -> p.sections.map { sec ->
                    Obj().put("category", sec.category).put("title", sec.customTitle).put("boxes", sec.storeBoxes.map { l -> l.map { b ->
                        listOf(b.blueprint?.name, b.cost, b.count, b.crew?.name)
                    } })
                } })
                contents.put("resources", hs.resources.map { listOf(it.resource.name, it.count, it.cost) }).put("repair", hs.repairPrice)
            } ?: run {
                contents.put("weapons", st.weapons.map { it?.name }).put("drones", st.drones.map { it?.name })
                    .put("augments", st.augments.map { it?.name }).put("systems", st.systems.map { it?.name })
                    .put("crew", st.crew.map { it?.let { c -> "${c.race.name}:${c.name}" } })
            }
            return contents
        }

        try {
            HsSeeds.requestedSeed = lines[0][0].toLong()
            // Reproducible runs (fullrun D8): the combat/timed generator starts from the seed instead of the page's
            // clock-seeded state, and the Lua frame clock (CFPS) from zero instead of the last real frame's values.
            BrowserRandom.restore((lines[0][0].toLong() xor 0x5DEECE66DL).toString())
            if (HS.luaActive) xyz.znix.xftl.hs.lua.LuaFps.resetClock()
            NativeRandom.live = liveImpl
            if (steerOn) {
                // Outcome steering at live-stream picks (the draw still happens; only the result is replaced).
                HsFlow.steerPick = { names, ours, live ->
                    var idx = ours
                    if (live) {
                        val want = (0..1).mapNotNull { k -> oracleBoxes.getOrNull(oc + k)?.event }.map { norm(it) }
                        val hit = want.firstNotNullOfOrNull { w -> names.indexOfFirst { norm(it) == w }.takeIf { it >= 0 } }
                        if (hit != null) idx = hit
                        line(Obj().put("k", "steer").put("kind", "list").put("box", boxes).put("ours", names[ours]).put("oracle", names[idx])
                            .put("changed", idx != ours).put("matched", hit != null).put("candidates", names).put("nextOracle", want))
                        steers++
                    }
                    idx
                }
            }
            if (steerOn && oracleQuests.isNotEmpty()) {
                xyz.znix.xftl.hs.sector.HsGeneration.steerFleet = { name, positions, ours, live ->
                    var idx = ours
                    if (live) {
                        val want = oracleQuests.firstOrNull { it.second == name && it.first >= oc && it.first <= oc + 2 }?.third
                        val hit = if (want != null) positions.indexOf(want) else -1
                        if (hit >= 0) idx = hit
                        line(Obj().put("k", "steer").put("kind", "fleet").put("box", boxes).put("event", name).put("ours", positions[ours])
                            .put("oracle", want ?: "?").put("changed", idx != ours).put("matched", hit >= 0).put("candidates", positions))
                        steers++
                    }
                    idx
                }
            }
            if (opts["trace"] == "1") {
                HsFlow.listTrace = { t -> line(Obj().put("k", "list").put("t", t)) }
                xyz.znix.xftl.sector.Sector.questDebug = { t -> line(Obj().put("k", "questDebug").put("t", t)) }
                xyz.znix.xftl.sector.Sector.questTrace = { name, suitable, at ->
                    line(Obj().put("k", "quest").put("event", name).put("at", "${at.pos.x},${at.pos.y}")
                        .put("suitable", suitable.map { "${it.pos.x},${it.pos.y}" }).put("questSeed", HsSeeds.questSeed))
                }
                // Generation-time HasEquipment calls (the oracle's HAS_EQUIPMENT hook fires for the same ones), with the
                // stream state and the sector seed the generation was seeded with.
                // Draws between GENERATOR_CREATE_SHIP_POST (the end of ShipGenerator::CreateShip) and the next choice
                // box, with the ship seed they follow (the oracle's probes measure the same window).
                xyz.znix.xftl.shipgen.ShipGenerator.diagnosticCreated = { name ->
                    if (NativeRandom.trace == null) { shipGap = ArrayList(); shipGapName = name; NativeRandom.trace = shipGap }
                }
                var stages = 0
                xyz.znix.xftl.sector.EventCreator.stageTrace = { ev, stage, n ->
                    if (n >= 0 && NativeRandom.trace !== shipGap && stages++ < 20000)
                        line(Obj().put("k", "stage").put("ev", ev).put("s", stage).put("n", n))
                }
                xyz.znix.xftl.hs.events.HsReq.trace = { name, value ->
                    if (HsEvents.advancedCheck and (1 shl 6) != 0)
                        line(Obj().put("k", "genReq").put("eq", name).put("v", value).put("state", NativeRandom.snapshot())
                            .put("sectorSeed", HsSeeds.currentSectorSeed))
                }
            }
            // `rngfrom` (box number): record the generation stream from that box on, one `rng` line per box and arrival
            // with the reseeds and draw counts since the previous line (r7b drift bisection).
            val rngFrom = opts["rngfrom"]?.toInt() ?: -1
            allSites = opts["rngall"] == "1"
            fun rngLine(at: String) {
                val t = rngTrace ?: return
                val segs = ArrayList<Any>()
                var i = 0; var count = 0; var first = 0; var last = 0
                fun flush() { if (count > 0) segs.add(listOf("d", count, first, last)); count = 0 }
                while (i < t.size) {
                    if (t[i] == Int.MIN_VALUE && i + 1 < t.size) { flush(); segs.add(listOf("s", t[i + 1])); i += 2; continue }
                    if (t[i] == Int.MIN_VALUE + 1 && i + 1 < t.size) { flush(); segs.add(listOf("m", rngMarks.getOrNull(t[i + 1]) ?: "?")); i += 2; continue }
                    if (count == 0) first = t[i]
                    last = t[i]; count++; i++
                }
                flush()
                line(Obj().put("k", "rng").put("at", at).put("box", boxes).put("segs", segs).put("state", NativeRandom.snapshot()))
                t.clear()
            }
            // `rngprobe` (event name): from the first box of that event on, one generation draw per box after the
            // PRE_CREATE_CHOICEBOX callbacks, as the oracle's probe callback (Hyperspace.random32) does.
            val probeFrom = opts["rngprobe"]
            var probing = false
            if (probeFrom != null) xyz.znix.xftl.hs.events.HsEventsLua.diagnosticPreBox = { created ->
                val name = created.template.hsName ?: created.template.debugId
                if (name == probeFrom) probing = true
                if (probing) line(Obj().put("k", "probe").put("n", name).put("v", NativeRandom.next31()))
            }
            fun flushSynthetic(game: InGameState) {
                for ((name, text) in pendingSynthetic) {
                    val o = Obj().put("k", "box").put("event", name).put("synthetic", true)
                    o.put("choices", arrayListOf<Any>(Obj().put("text", "Continue...").put("type", 0)))
                    lastChoices = listOf("Continue..." to 0)
                    o.put("text", text)
                    stateObj(game, o)
                    line(o)
                    boxes++; boxSeq++
                    lastBoxEvent = name
                }
                pendingSynthetic.clear()
            }
            // r8 resync: runs just before the policy's key press, when the oracle's process was sampled too (its box had been open
            // for a second and the game was paused): classify the draws since the previous such point, then take the oracle's state.
            fun syncBox(boxName: String) {
                val classLive = liveSince
                val classSeed: Int? = if (seededInInterval) lastSeed else null
                val classN = drawsSinceSeed
                val classFirst = firstLiveAt
                val classAny = liveAny
                val classSites = liveSites.entries.take(6).map { "${it.value}x ${it.key}" }
                val oracleBox = matchOracle(boxName)
                val oursState = NativeRandom.snapshot()
                // A live draw can become a ship/quest seed: onSeed clears liveSince, but the event tree's
                // rewards were already rolled before that reseed. Match compare.py's `live-seed` rule;
                // equal reseeds with different draw counts remain gaps, never resource-steered here.
                val liveSeed = oracleBox?.state != null && oracleBox.reseed != null && classSeed != null &&
                    oracleBox.reseed != classSeed && classAny > 0
                var rngClass = if (classLive > 0 || liveSeed) "live" else "seeded"
                if (oracleBox?.state != null) {
                    if (oursState != oracleBox.state) {
                        rngClass = if (classLive > 0 || liveSeed) "live" else "gap"
                        if (rngClass == "gap") gaps++
                        if (steerOn) {
                            line(Obj().put("k", "steer").put("kind", "state").put("box", boxes - 1).put("event", boxName).put("ours", oursState)
                                .put("oracle", oracleBox.state).put("class", rngClass).put("live", classLive).put("seed", classSeed ?: "none").put("n", classN)
                                .put("oracleSeed", oracleBox.reseed ?: "unknown").put("liveSeed", liveSeed))
                            steers++
                            NativeRandom.restore(oracleBox.state)
                        }
                    } else rngClass += "+same"
                    if (steerOn) NativeRandom.markSynced()
                } else rngClass += "+nooracle"
                line(Obj().put("k", "rngsync").put("box", boxes - 1).put("event", boxName).put("rng", oursState).put("rngClass", rngClass)
                    .put("rngSeed", classSeed ?: "none").put("rngN", classN).put("rngLive", classLive).put("rngLiveAny", classAny)
                    .put("rngFirstLive", classFirst).put("rngSites", classSites).put("oracleIdx", oracleBox?.index ?: -1)
                    .put("oracleSeed", oracleBox?.reseed ?: "unknown").put("liveSeed", liveSeed)
                    .also { o -> if (allSites && rngClass.startsWith("gap")) o.put("rngRuns", drawRuns.map { listOf(it.second, it.first) }) })
                drawRuns.clear()
                if (classLive > 0 || liveSeed) { prevLive = true; liveBeacon = curBeacon }
                liveSince = 0; firstLiveAt = -1; liveSites.clear(); seededInInterval = false; drawsSinceSeed = 0; liveAny = 0
            }
            HsFlow.boxListener = { game, created, choices ->
            boxOpen = true
            curBeacon = "${game.currentBeacon.pos.x},${game.currentBeacon.pos.y}"
                if (rngFrom >= 0 && rngTrace == null && boxes >= rngFrom && NativeRandom.trace == null) {
                    rngTrace = ArrayList(); NativeRandom.trace = rngTrace
                    // ship generation steps as markers in the stream (Int.MIN_VALUE + 1, step index)
                    xyz.znix.xftl.shipgen.ShipGenerator.diagnosticMark = { m -> rngTrace?.let { it.add(Int.MIN_VALUE + 1); it.add(rngMarks.size); rngMarks.add(m) } }
                    xyz.znix.xftl.shipgen.ShipGenerator.diagnosticCreated = { m -> rngTrace?.let { it.add(Int.MIN_VALUE + 1); it.add(rngMarks.size); rngMarks.add("post:$m") } }
                    line(Obj().put("k", "rng").put("at", "start").put("box", boxes).put("state", NativeRandom.snapshot()))
                } else rngLine("box:" + (created.template.hsName ?: created.template.debugId))
                shipGap?.let { g ->
                    if (NativeRandom.trace === g) NativeRandom.trace = null
                    line(Obj().put("k", "shipGap").put("ship", shipGapName).put("draws", g.size).put("box", created.template.hsName ?: "")
                        .put("seed", game.enemy?.generationSeed ?: 0).put("escape", game.enemy?.escapeHealth).put("surrender", game.enemy?.surrenderHealth))
                    shipGap = null
                }
                // tier 2: the live box before this one rolled a reward from the live stream (autoReward after unpaused frames):
                // the resources the oracle shows here are that roll's outcome. Replace ours and log both values.
                if (steerOn && prevLive) {
                    val name = created.template.hsName ?: created.template.debugId
                    val want = (0..6).firstNotNullOfOrNull { k -> oracleBoxes.getOrNull(oc + k)?.takeIf { b -> norm(b.event) == norm(name) } }
                    val pl = game.player
                    val ours = listOf(pl.fuelCount, pl.missilesCount, pl.dronesCount, pl.scrap)
                    if (want?.res != null && want.res != ours) {
                        line(Obj().put("k", "steer").put("kind", "resources").put("box", boxes).put("event", name)
                            .put("ours", ours.joinToString(",")).put("oracle", want.res.joinToString(",")).put("class", "live"))
                        steers++
                        pl.fuelCount = want.res[0]; pl.missilesCount = want.res[1]; pl.dronesCount = want.res[2]; pl.scrap = want.res[3]
                    }
                    if (curBeacon != liveBeacon) prevLive = false
                }
                val o = Obj().put("k", "box").put("event", created.template.hsName ?: created.template.debugId)
                val arr = ArrayList<Any>()
                if (choices.isNullOrEmpty()) arr.add(Obj().put("text", "Continue...").put("type", 0))
                else for (c in choices) arr.add(Obj().put("text", c.choice.text.localised).put("type", c.type))
                o.put("choices", arr)
                lastChoices = if (choices.isNullOrEmpty()) listOf("Continue..." to 0) else choices.map { it.choice.text.localised to it.type }
                o.put("text", created.text?.localised ?: "")
                o.put("rng", NativeRandom.snapshot())
                stateObj(game, o)
                line(o)
                boxes++; boxSeq++
                lastBoxEvent = created.template.hsName ?: created.template.debugId
            }
            // AUGMENT_FULL / EQUIP_FULL: the original shows them as event boxes (the oracle records a box), ours as synthetic
            // events in the open window: record them as boxes too.
            // Recorded once the resources are applied: the original builds that box after the rest of ModifyResources
            // (HS's variables), before the next tick (MV's Lua trackers).
            xyz.znix.xftl.game.DialogueWindow.syntheticListener = { name, text -> pendingSynthetic.add(name to text) }
            xyz.znix.xftl.game.DialogueWindow.resourcesListener = { flushSynthetic(main.currentState as InGameState) }
            xyz.znix.xftl.hs.events.HsCrew.pickTrace = { ev, before, seed, race ->
                line(Obj().put("k", "crewPick").put("event", ev).put("before", before).put("seed", seed).put("race", race)
                    .put("crew", (main.currentState as InGameState).playerCrew.map { it.blueprint.name }))
            }
            main.startDiagnosticGame(lines[0][1], Difficulty.values()[lines[0][2].toInt()])
            var game = main.currentState as InGameState
            line(mapObj(game, Obj().put("k", "map")))

            var beaconN = 0
            var answeredSeq = -1
            var boxIdle = 0
            var quiet = 0
            var deletes = 0
            var repels = 0
            var sinceBeacon = 0.0
            var reloadedSector = -1
            var reloadedBeacon = -1
            var midFightBeacon = -1
            var midEventSeq = -1
            var lastSector = 0
            var jumpFailures = 0
            var dumpedFor = -1
            var lastEnemy: xyz.znix.xftl.Ship? = null
            var awaitTarget: Beacon? = null
            var awaitFrom: Sector? = null
            var awaitFrames = 0
            var repeatPicks = 0
            var shipCloses = 0
            var ftlWaitFrames = 0

            // Save, reload through the loader and compare: the harness state before and after, and the save XML written again
            // from the reloaded game (a save must be a fixed point of save+load). Returns a failure text or null.
            fun reloadNow(tag: String): String? {
                val sec = game.currentBeacon.sector.sectorNumber
                val before = stateObj(game, Obj()).toString()
                try {
                    val xml0 = BrowserXml.write(game.saveGameState())
                    val rngBefore = NativeRandom.snapshot() + "/" + BrowserRandom.snapshot()
                    // Hyperspace's SeededRng::Import discards `counter` draws and then draws once more (an off-by-one against
                    // SeededRng::operator()), so a loaded seeded run takes different sector seeds than the one it was saved from.
                    // That is Hyperspace's own behaviour (ported 1:1); the unreloaded oracle cannot show it, so the harness puts
                    // both generators back to the exact state they had.
                    val worldBefore = HsSeeds.worldRng.seed to HsSeeds.worldRng.counter
                    val secretBefore = HsSeeds.secretRng.seed to HsSeeds.secretRng.counter
                    main.loadSavedGame(BrowserXml.parse(xml0))
                    for ((rng, st) in listOf(HsSeeds.worldRng to worldBefore, HsSeeds.secretRng to secretBefore)) {
                        rng.reset(st.first); repeat(st.second) { rng.next() }
                    }
                    val loaded = main.currentState as? InGameState ?: return "FAILED fullrun reload: not in game after loading"
                    val rngAfter = NativeRandom.snapshot() + "/" + BrowserRandom.snapshot()
                    // The save written again from the freshly loaded game (before any frame runs) must be the same text,
                    // but for the counters that legitimately move (space ids drawn while loading, event button indices).
                    fun normXml(x: String) = x.replace(Regex("<data key=\"spaceId\"[^>]*/>"), "").replace(Regex("<b idx=\"\\d+\""), "<b idx=\"\"")
                        // a store's crew boxes are made again on every load (StoreComplete::LoadStore keeps only the race)
                        .replace(Regex("(<box [^>]*><crew race=\"[^\"]*\") selectedName=\"[^\"]*\" colour=\"\\d+\""), "$1")
                        // frozen ships' crew re-plan their path in the load's zero-time update (AI decisions are not saved)
                        .replace(Regex(" action=\"[A-Z_]+\""), "").replace(Regex("<pathingTarget [^>]*/>"), "").replace(Regex("<nextTargetPos [^>]*/>"), "")
                    val n0 = normXml(xml0); val n1 = normXml(BrowserXml.write(loaded.saveGameState()))
                    repeat(4) { frame(main) }
                    // The settling frames are the harness's, not the run's: the streams go on from the loaded state (the
                    // unreloaded oracle run has no such frames).
                    val rngLoaded = rngAfter.split("/")
                    NativeRandom.restore(rngLoaded[0]); BrowserRandom.restore(rngLoaded[1])
                    val after = main.currentState as? InGameState ?: return "FAILED fullrun reload: not in game after loading"
                    val afterJson = stateObj(after, Obj()).toString()
                    var firstDiff = -1
                    run { val n = minOf(n0.length, n1.length); var i = 0; while (i < n && n0[i] == n1[i]) i++; if (i < n || n0.length != n1.length) firstDiff = i }
                    line(stateObj(after, Obj().put("k", "reloaded").put("sector", sec).put("tag", tag).put("same", before == afterJson)
                        .put("before", if (before == afterJson) "" else before)
                        .put("rngSame", rngBefore == rngAfter)
                        .put("xmlSame", firstDiff < 0).put("xmlDiffAt", firstDiff)
                        .put("xmlDiff0", if (firstDiff >= 0) n0.substring(maxOf(0, firstDiff - 1500), minOf(n0.length, firstDiff + 300)) else null)
                        .put("xmlDiff1", if (firstDiff >= 0) n1.substring(maxOf(0, firstDiff - 1500), minOf(n1.length, firstDiff + 300)) else null)))
                    game = after
                } catch (e: Throwable) {
                    line(Obj().put("k", "reloadFailed").put("sector", sec).put("tag", tag).put("error", "${e::class.simpleName}: ${e.message}")
                        .put("stack", BrowserJsStack.of(e)))
                    return "FAILED fullrun reload ($tag) at sector ${sec + 1}: ${e::class.simpleName}: ${e.message}"
                }
                return null
            }

            while (true) {
                frame(main)
                game = main.currentState as? InGameState
                    ?: return finish("FAILED fullrun: the game left InGameState for ${main.currentState}", boxes)
                if (game.browserRunEnded || game.shipUI.hsDiagnosticWindow is xyz.znix.xftl.game.GameOverWindow) {
                    line(stateObj(game, Obj().put("k", "end").put("outcome", game.browserProgress.outcome?.name)))
                    line(perfLine(game.currentBeacon.sector.sectorNumber))
                    return finish("ENDED ${game.browserProgress.outcome?.name} at sector ${game.currentBeacon.sector.sectorNumber + 1} beacon $beaconN", boxes)
                }
                if (awaitTarget != null || awaitFrom != null) {
                    awaitFrames++
                    val arrived = if (awaitTarget != null) game.currentBeacon === awaitTarget && !game.isPlayerJumping
                    else game.currentBeacon.sector !== awaitFrom && !game.isPlayerJumping
                    if (arrived) {
                        if (awaitFrom != null) line(mapObj(game, stateObj(game, Obj().put("k", "arrive"))))
                        rngLine("arrive")
                        awaitTarget = null; awaitFrom = null
                        jumpFailures = 0
                        beaconN++
                        beaconBoxes = ArrayList()
                        answeredSeq = -1; quiet = 0; deletes = 0; repels = 0; sinceBeacon = 0.0
                    } else if (awaitFrames > 2400) {
                        line(diag(game, "arrival"))
                        return finish("STOPPED no arrival after the jump from beacon $beaconN (${if (awaitTarget != null) "to ${awaitTarget!!.pos.x},${awaitTarget!!.pos.y}" else "NEXT SECTOR"})", boxes)
                    }
                }
                val en = game.enemy
                if (en !== lastEnemy) {
                    lastEnemy = en
                    if (en != null) line(Obj().put("k", "enemy").put("bp", en.type.name).put("hull", en.maxHealth).put("crew", en.crew.size)
                        .put("hostile", game.isEnemyHostile).put("beacon", beaconN).put("sector", game.currentBeacon.sector.sectorNumber))
                }
                sinceBeacon += DT
                flushSynthetic(game)
                val window = game.shipUI.hsDiagnosticWindow
                val dlg = game.shipUI.diagnosticDialogue
                boxOpen = dlg != null

                // Boxes: stable for one game second, then the policy's key (the oracle waits 0.8 + 0.4 s).
                if (dlg != null) {
                    quiet = 0
                    if (game.isPlayerJumping) { boxIdle = 0; continue }
                    boxIdle++
                    if (boxIdle < 24) continue
                    boxIdle = 0
                    if (reloadMid && midEventSeq != boxSeq && answeredSeq != boxSeq) {
                        midEventSeq = boxSeq
                        val fail = reloadNow("event")
                        if (fail != null) return finish(fail, boxes)
                        continue
                    }
                    val event = dlg.hsOpenEvent
                    var i = if (event != null) policy(lastChoices) else 0
                    if (i >= dlg.optionCount) i = 0
                    val repeat = answeredSeq == boxSeq
                    if (repeat) i = (0 until dlg.optionCount).firstOrNull { it != i } ?: 0
                    answeredSeq = boxSeq
                    repeatPicks = if (repeat) repeatPicks + 1 else 0
                    if (boxes >= maxBoxes) return finish("STOPPED box limit $maxBoxes at beacon $beaconN", boxes)
                    if (repeatPicks >= 25) {
                        line(diag(game, "box"))
                        return finish("STOPPED a box does not close: ${event ?: "(synthetic)"} (${dlg.optionCount} options, ${lastChoices.size} listed) at beacon $beaconN", boxes)
                    }
                    beaconBoxes.add(Obj().put("event", event ?: "(synthetic)").put("picked", i).put("repeat", repeat)
                        .put("text", lastChoices.getOrNull(i)?.first))
                    line(Obj().put("k", "pick").put("event", event ?: "(synthetic)").put("key", i + 1).put("repeat", repeat))
                    if (!repeat) syncBox(event ?: "(synthetic)")
                    dlg.selectOption(i)
                    continue
                }
                boxIdle = 0
                if (game.isPlayerJumping) { quiet = 0; continue }
                if (++quiet < 20) continue

                // Store windows an event opened: list the contents, close.
                if (window is StoreWindow) {
                    line(Obj().put("k", "close").put("window", "store").put("beacon", beaconN).put("store", storeContents(window)))
                    window.escapePressed(); quiet = 0; continue
                }
                if (window is xyz.znix.xftl.game.ShipWindow && shipCloses++ < 6) {
                    // the ship screen nobody asked for: Escape closes it (the oracle side has no such window to close)
                    line(Obj().put("k", "close").put("window", "ship").put("beacon", beaconN))
                    game.shipUI.escapePressed(); quiet = 0; continue
                }
                if (window != null) { quiet = 0
                    if (sinceBeacon > ftlTimeout) { /* a window nobody closes: give up below */ }
                    else continue
                }
                val enemy = game.enemy
                if (enemy != null && !enemy.isDead && game.isEnemyHostile) {
                    if (reloadMid && midFightBeacon != beaconN && deletes == 0) {
                        midFightBeacon = beaconN
                        val fail = reloadNow("fight")
                        if (fail != null) return finish(fail, boxes)
                        quiet = 0; continue
                    }
                    if (deletes >= 12) {
                        line(diag(game, "delete"))
                        return finish("STOPPED DELETE did not remove the enemy at beacon $beaconN", boxes)
                    }
                    enemy.health = 0; deletes++
                    line(Obj().put("k", "delete").put("beacon", beaconN).put("boxIdle", boxIdle).put("since", sinceBeacon).put("jumping", game.isPlayerJumping).put("win", game.shipUI.hsDiagnosticWindow?.let { it::class.simpleName }).put("dlg", game.shipUI.diagnosticDialogue?.hsOpenEvent)); quiet = 0; continue
                }
                val player = game.player
                val boarders = player.crew.filter { it is xyz.znix.xftl.crew.LivingCrew && it.ownerShip !== player && it.health > 0f }
                if (boarders.isNotEmpty() && repels < 5) {
                    for (c in boarders) c.health = 0f
                    repels++
                    line(Obj().put("k", "repel").put("n", boarders.size)
                        .put("boarders", boarders.map { c -> (c as xyz.znix.xftl.crew.LivingCrew).let { "${it.blueprint.name}:${it.info.name}:${it.health}" } })
                        .put("crew", player.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().map { "${it.blueprint.name}:${it.info.name}:${it.ownerShip === player}:${it.health}" })); quiet = 0; continue
                }
                if (!(player.isFtlReady && player.canChargeFTL)) {
                    if (++ftlWaitFrames % 100 == 0) {
                        for (sy in player.systems) sy.damagedEnergyLevels = 0
                        player.engines?.let { sy -> var n = 0; while (sy.powerSelected < sy.undamagedEnergy && n++ < 8) { val b = sy.powerSelected; sy.increasePower(); if (sy.powerSelected == b) break } }
                        // Round 7 (both sides, HS_ORACLE.fixdrive): nobody pilots, so the first living crew member on board
                        // (playerCrew order) outside the piloting room walks there.
                        val pil = player.piloting
                        val pilRoom = pil?.room
                        if (pil != null && pilRoom != null && pil.hasPilot != true) {
                            val c = game.playerCrew.firstOrNull { it.ownerShip === player && it.room.ship === player && it.health > 0f && it.room !== pilRoom }
                            if (c != null && c.setTargetRoom(pilRoom))
                                line(Obj().put("k", "restation").put("race", c.blueprint.name).put("from", c.room.id).put("to", pilRoom.id).put("beacon", beaconN))
                        }
                    }
                    if (sinceBeacon > ftlTimeout) {
                        line(diag(game, "ftl"))
                        return finish("STOPPED FTL drive not ready after ${ftlTimeout.toInt()} s at sector ${game.currentBeacon.sector.sectorNumber + 1} beacon $beaconN", boxes)
                    }
                    continue
                }

                // The beacon is done: heal (HS_ORACLE.heal(6)), dump, and leave.
                val cur = game.currentBeacon
                val sec = cur.sector.sectorNumber
                if (dumpedFor != beaconN) {
                    dumpedFor = beaconN
                    if (beaconN == 0) god = true
                    val crewNow = player.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().filter { it.ownerShip === player }
                    val heal = Obj().put("k", "heal").put("hull0", player.health).put("fuel0", player.fuelCount)
                        .put("crew0", crewNow.map { it.health })
                    val sysDmg0 = player.systems.sumOf { it.damagedEnergyLevels }
                    for (sy in player.systems) sy.damagedEnergyLevels = 0
                    val powerSys = listOfNotNull<xyz.znix.xftl.systems.MainSystem>(player.engines)
                    val pw0 = powerSys.sumOf { it.powerSelected }
                    for (sy in powerSys) { var n = 0; while (sy.powerSelected < sy.undamagedEnergy && n++ < 8) { val before = sy.powerSelected; sy.increasePower(); if (sy.powerSelected == before) break } }
                    heal.put("pw0", pw0).put("pw1", powerSys.sumOf { it.powerSelected })
                    heal.put("sysdmg0", sysDmg0).put("sysdmg1", player.systems.sumOf { it.damagedEnergyLevels })
                    if (player.health < player.maxHealth) player.health = player.maxHealth
                    if (player.fuelCount < 6) player.fuelCount = 6
                    for (c in crewNow) if (c.health > 0f && c.health < c.maxHealth) c.health = c.maxHealth
                    line(heal.put("hull1", player.health).put("fuel1", player.fuelCount).put("crew1", crewNow.map { it.health }))
                    if (sec != lastSector) {
                        line(perfLine(lastSector)); lastSector = sec
                    }
                    line(stateObj(game, Obj().put("k", "beacon").put("n", beaconN).put("deletes", deletes).put("repels", repels)
                        .put("boxes", beaconBoxes).put("elapsed", sinceBeacon).put("gameTime", gameTime)
                        .put("heapMB", BrowserHeap.used() / 1048576.0)))
                    if (beaconN >= maxBeacons) return finish("STOPPED beacon limit $maxBeacons", boxes)

                    // Save and reload: reload=1 once per sector (the third beacon dump of a sector, no window open), reload=2 at
                    // every beacon dump. (reloadmid=1 also reloads mid-fight and mid-event, see below.)
                    if ((reloadMode == 2 && reloadedBeacon != beaconN) ||
                        (reloadMode == 1 && reloadedSector != sec && cur.sector.beacons.count { it.visited } >= 3)) {
                        reloadedSector = sec
                        reloadedBeacon = beaconN
                        val fail = reloadNow(if (reloadMode == 2) "beacon" else "sector")
                        if (fail != null) return finish(fail, boxes)
                        // The reloaded game may open a box again; the loop answers it before the next jump.
                        quiet = 0
                        sinceBeacon = 0.0
                        continue
                    }
                }

                // Leave: next sector at the exit beacon, else the unvisited neighbour closest to the exit.
                val lastStand = sec >= 7
                if (cur.isExit && !lastStand) {
                    val from = cur.sector
                    game.shipUI.openJumpMap()
                    if (game.shipUI.tutorialJumpWindow == null) {
                        if (++jumpFailures > 3) return finish("STOPPED the map did not open at the exit of sector ${sec + 1}", boxes)
                        quiet = 0; continue
                    }
                    main.render(container, graphics)
                    game.shipUI.openSectorMap()
                    main.render(container, graphics)
                    game.shipUI.hsDiagnosticWindow as? SectorMapWindow
                        ?: return finish("FAILED sector map did not open at the exit of sector ${sec + 1}", boxes)
                    val next = from.info.nextSectors.minByOrNull { it.columnIndex }
                        ?: return finish("STOPPED no next sector from sector ${sec + 1}", boxes)
                    game.shipUI.sectorChosen(next)
                    line(Obj().put("k", "sector").put("to", next.type.name).put("col", next.columnNumber).put("idx", next.columnIndex))
                    awaitFrom = from; awaitTarget = null; awaitFrames = 0
                } else {
                    if (player.fuelCount < 1) return finish("STOPPED out of fuel at beacon $beaconN", boxes)
                    val target = pickTarget(game, cur, lastStand)
                        ?: return finish("STOPPED no neighbour at beacon $beaconN", boxes)
                    game.shipUI.openJumpMap()
                    val map = game.shipUI.tutorialJumpWindow
                    if (map == null) {
                        if (++jumpFailures > 3) {
                            line(diag(game, "map"))
                            return finish("STOPPED the map did not open at beacon $beaconN (box ${game.shipUI.diagnosticDialogue?.hsOpenEvent}, window ${game.shipUI.hsDiagnosticWindow?.let { it::class.simpleName }})", boxes)
                        }
                        quiet = 0; continue
                    }
                    main.render(container, graphics)
                    val cx = map.position.x + Sector.OFFSET.x + target.pos.x
                    val cy = map.position.y + Sector.OFFSET.y + target.pos.y
                    map.updateUI(cx, cy)
                    map.mouseClick(Input.MOUSE_LEFT_BUTTON, cx, cy)
                    line(Obj().put("k", "jump").put("to", "${target.pos.x},${target.pos.y}").put("beacon", beaconN))
                    awaitTarget = target; awaitFrom = null; awaitFrames = 0
                }
                quiet = 0
            }
        } catch (e: Throwable) {
            line(Obj().put("k", "jsStack").put("stack", BrowserJsStack.of(e)))
            return finish("FAILED fullrun: ${e::class.simpleName}: ${e.message} ${e.stackTrace.take(8).joinToString(" < ")}", boxes)
        } finally {
            HsFlow.boxListener = null
            NativeRandom.live = null
            HsFlow.steerPick = null
            xyz.znix.xftl.hs.sector.HsGeneration.steerFleet = null
            xyz.znix.xftl.hs.events.HsEventsLua.diagnosticPreBox = null
            xyz.znix.xftl.game.DialogueWindow.syntheticListener = null
            xyz.znix.xftl.game.DialogueWindow.resourcesListener = null
            xyz.znix.xftl.hs.events.HsCrew.pickTrace = null
            HsFlow.listTrace = null
            xyz.znix.xftl.sector.Sector.questTrace = null
            xyz.znix.xftl.sector.Sector.questDebug = null
            xyz.znix.xftl.hs.events.HsReq.trace = null
            xyz.znix.xftl.sector.EventCreator.stageTrace = null
            xyz.znix.xftl.shipgen.ShipGenerator.diagnosticCreated = null
            xyz.znix.xftl.shipgen.ShipGenerator.diagnosticMark = null
            if (NativeRandom.trace === shipGap) NativeRandom.trace = null
            if (rngTrace != null && NativeRandom.trace === rngTrace) NativeRandom.trace = null
            HsSeeds.requestedSeed = null
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
    }

    /** The oracle's rule: among the neighbours the unvisited ones (else all) closest to the exit (the boss in the last stand), ties by x then y. */
    private fun pickTarget(game: InGameState, cur: Beacon, lastStand: Boolean): Beacon? {
        val sector = cur.sector
        val goal: Beacon = (if (lastStand) sector.bosses.firstNotNullOfOrNull { it.beacon } else null) ?: sector.finishBeacon
        val cand = cur.neighbours
        val unvisited = cand.filter { !it.visited }.ifEmpty { cand }
        return unvisited.minWithOrNull(compareBy<Beacon>(
            { val dx = (it.pos.x - goal.pos.x).toLong(); val dy = (it.pos.y - goal.pos.y).toLong(); dx * dx + dy * dy },
            { it.pos.x }, { it.pos.y }))
    }
}
