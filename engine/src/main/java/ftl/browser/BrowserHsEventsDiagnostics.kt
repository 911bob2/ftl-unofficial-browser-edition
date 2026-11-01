package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsFlow
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Events workstream check (`BrowserEngine.hsEventsProbe`): plays a seeded Hyperspace run the way an oracle session
 * does (tools/hs-oracle sessions: `pick EVENT n` presses the n-th shown choice of EVENT's box, `jump x,y` opens the
 * star map and jumps to that beacon, `sector` takes the upper next-sector node at an exit beacon; between boxes the
 * oracle autoplay's interventions run: a hostile enemy is destroyed (console DELETE, ShipManager::Blowup), boarders
 * on the player ship die (HS_ORACLE.repel), a store window closes) and returns one JSON line per choice box (event, shown choices and their types,
 * non-zero variables, resources, location), the same facts the oracle's tracer records, for
 * tools/hs-events/compare-oracle.py. Test-only; needs HS data. Leaves the engine at the main menu.
 */
object BrowserHsEventsDiagnostics {
    private const val DT = 0.05f

    /**
     * [script]: tab-separated lines; the first is `seed<TAB>ship<TAB>difficulty (0-2)[<TAB>autoplay]` (autoplay: the
     * oracle autoplay's interventions run while waiting), then `pick<TAB>EVENT<TAB>n`,
     * `jump<TAB>x,y`, `sector`, `wait<TAB>seconds` or `lua<TAB>code`.
     */
    fun run(main: MainGame, container: GameContainer, graphics: Graphics, scriptText: String): String {
        if (!HS.active) return "SKIP hs-events: Hyperspace is not active"
        val lines = scriptText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { it.split('\t') }
        val out = StringBuilder()
        val rng = BrowserRandom.snapshot()
        var boxes = 0
        var lastBox: String? = null
        // The boxes made since the last press: an oracle autoplay record also lists boxes that closed by themselves
        // (no text, nothing after them); its key press then went nowhere.
        val madeSince = HashSet<String>()
        fun line(o: Obj) { out.append(o.toString()).append('\n') }
        try {
            HsSeeds.requestedSeed = lines[0][0].toLong()
            xyz.znix.xftl.sector.NativeSectorGenerator.placementTrace = { t -> line(Obj().put("k", "gen").put("s", t)) }
            HsFlow.boxListener = { game, created, choices ->
                val o = Obj().put("k", "box").put("event", created.template.hsName ?: created.template.debugId)
                val arr = ArrayList<Any>()
                if (choices.isNullOrEmpty()) arr.add(Obj().put("text", "Continue...").put("type", 0))
                else for (c in choices) arr.add(Obj().put("text", c.choice.text.localised).put("type", c.type))
                o.put("choices", arr)
                o.put("text", created.text?.localised ?: "")
                state(game, o)
                // While a storescan traces the stream: its length and the reseeds in it so far (index:seed).
                NativeRandom.trace?.let { t ->
                    o.put("draws", t.size)
                    o.put("reseeds", t.indices.filter { t[it] == Int.MIN_VALUE && it + 1 < t.size }.map { "$it:${t[it + 1]}" })
                }
                line(o)
                boxes++
                lastBox = created.template.hsName ?: created.template.debugId
                madeSince.add(lastBox!!)
            }
            HsFlow.listTrace = { t -> line(Obj().put("k", "list").put("s", t)) }
            // AUGMENT_FULL / EQUIP_FULL: the engine shows them as synthetic events, the original as event boxes.
            xyz.znix.xftl.game.DialogueWindow.syntheticListener = { name, text ->
                val o = Obj().put("k", "box").put("event", name)
                o.put("choices", arrayListOf<Any>(Obj().put("text", "Continue...").put("type", 0)))
                o.put("text", text)
                state(main.currentState as InGameState, o)
                line(o)
                boxes++
                lastBox = name
                madeSince.add(name)
            }
            main.startDiagnosticGame(lines[0][1], Difficulty.values()[lines[0][2].toInt()])
            idle = 0
            autoplay = lines[0].getOrNull(3) == "autoplay"
            line(map(main.currentState as InGameState, Obj().put("k", "map")))
            for (step in lines.drop(1)) {
                val game = main.currentState as InGameState
                when (step[0]) {
                    "pick" -> {
                        val event = step[1]
                        val key = step[2].toInt()
                        // Wait (game time) until EVENT's box is the open one, then press its n-th choice.
                        var waited = 0
                        var closed = 0
                        while (game.shipUI.diagnosticDialogue?.hsOpenEvent != event) {
                            frame(main, container)
                            intervene(game, ::line)
                            closed = if (event in madeSince && game.shipUI.diagnosticDialogue == null) closed + 1 else 0
                            if (closed > 40) break
                            if (++waited > 2400) return finish(out, "FAILED pick $event: open box is ${game.shipUI.diagnosticDialogue?.hsOpenEvent} (last made $lastBox)", boxes)
                        }
                        // A few frames with the box open, as a player (and the oracle's key presses) takes: ON_TICK
                        // callbacks (MV's on_new_game, value trackers) run between boxes in the original too.
                        if (closed > 40) {
                            line(Obj().put("k", "skip").put("event", event))
                            madeSince.clear()
                            continue
                        }
                        repeat(4) { frame(main, container) }
                        val dialogue = game.shipUI.diagnosticDialogue!!
                        if (key > dialogue.optionCount) return finish(out, "FAILED pick $event $key: the box shows ${dialogue.optionCount} choices", boxes)
                        line(Obj().put("k", "pick").put("event", event).put("key", key))
                        madeSince.clear()
                        dialogue.selectOption(key - 1)
                        frame(main, container)
                    }
                    "jump" -> {
                        val (x, y) = step[1].split(',').map { it.toInt() }
                        var waited = 0
                        while (game.shipUI.isWindowOpen || !game.player.isFtlReady || !game.player.canChargeFTL || game.isPlayerJumping) {
                            frame(main, container)
                            intervene(game, ::line)
                            if (++waited > 6000) return finish(out, "FAILED jump $x,$y: window=${game.shipUI.isWindowOpen} ftl=${game.player.isFtlReady}", boxes)
                        }
                        val beacon = game.currentBeacon.sector.beacons.firstOrNull { it.pos.x == x && it.pos.y == y }
                            ?: return finish(out, "FAILED jump: no beacon at $x,$y", boxes)
                        game.shipUI.openJumpMap()
                        val map = game.shipUI.tutorialJumpWindow ?: return finish(out, "FAILED jump $x,$y: the map did not open (box ${game.shipUI.diagnosticDialogue?.hsOpenEvent})", boxes)
                        main.render(container, graphics)
                        val cx = map.position.x + Sector.OFFSET.x + beacon.pos.x
                        val cy = map.position.y + Sector.OFFSET.y + beacon.pos.y
                        map.updateUI(cx, cy)
                        map.mouseClick(Input.MOUSE_LEFT_BUTTON, cx, cy)
                        line(Obj().put("k", "jump").put("to", "$x,$y"))
                        var arrive = 0
                        while (game.currentBeacon !== beacon || game.isPlayerJumping) {
                            frame(main, container)
                            if (++arrive > 2400) return finish(out, "FAILED jump $x,$y: no arrival", boxes)
                        }
                        line(state(game, Obj().put("k", "arrive").put("at", "$x,$y")))
                    }
                    "sector" -> {
                        var waited = 0
                        while (game.shipUI.isWindowOpen || !game.player.isFtlReady || !game.player.canChargeFTL || game.isPlayerJumping) {
                            frame(main, container)
                            intervene(game, ::line)
                            if (++waited > 6000) return finish(out, "FAILED sector: window=${game.shipUI.isWindowOpen} ftl=${game.player.isFtlReady}", boxes)
                        }
                        val from = game.currentBeacon.sector
                        // The oracle: the star map (StarMap::Open), NEXT SECTOR, then the upper of the next sectors.
                        game.shipUI.openJumpMap()
                        game.shipUI.tutorialJumpWindow ?: return finish(out, "FAILED sector: the map did not open", boxes)
                        main.render(container, graphics)
                        game.shipUI.openSectorMap()
                        main.render(container, graphics)
                        game.shipUI.hsDiagnosticWindow as? xyz.znix.xftl.game.SectorMapWindow
                            ?: return finish(out, "FAILED sector: the sector map did not open", boxes)
                        val next = from.info.nextSectors.minByOrNull { it.columnIndex }
                            ?: return finish(out, "FAILED sector: no next sector", boxes)
                        game.shipUI.sectorChosen(next)
                        line(Obj().put("k", "sector").put("to", next.type.name).put("col", next.columnNumber)
                            .put("idx", next.columnIndex).put("colSize", game.gameMap.sectors[next.columnNumber].size))
                        var arrive = 0
                        while (game.currentBeacon.sector === from || game.isPlayerJumping) {
                            frame(main, container)
                            if (++arrive > 2400) return finish(out, "FAILED sector: no arrival", boxes)
                        }
                        line(map(game, state(game, Obj().put("k", "arrive").put("at", "${game.currentBeacon.pos.x},${game.currentBeacon.pos.y}"))))
                    }
                    // The next sectors from here, as the sector map lays them out (column, index, column size).
                    "sectorinfo" -> {
                        val arr = ArrayList<Any>()
                        for (n in game.currentBeacon.sector.info.nextSectors.sortedBy { it.columnIndex })
                            arr.add(Obj().put("type", n.type.name).put("col", n.columnNumber).put("idx", n.columnIndex)
                                .put("colSize", game.gameMap.sectors[n.columnNumber].size))
                        line(Obj().put("k", "sectorinfo").put("next", arr))
                    }
                    // The beacon's changeBackground list (Location::spaceImage) and the backdrop drawn now.
                    "bg" -> line(Obj().put("k", "bg").put("spaceImage", game.currentBeacon.hsSpaceImage ?: "")
                        .put("image", game.currentBeacon.getEnvironment(game).diagnosticBackgroundPath ?: ""))
                    // Save+Quit then Continue: the run save document, loaded as MainGame.continueSavedRun does.
                    "savereload" -> {
                        var waited = 0
                        while (game.shipUI.isWindowOpen) {
                            frame(main, container)
                            if (++waited > 2400) return finish(out, "FAILED savereload: a window stays open", boxes)
                        }
                        main.loadSavedGame(game.saveGameState())
                        repeat(4) { frame(main, container) }
                        line(state(main.currentState as InGameState, Obj().put("k", "reloaded")))
                    }
                    // Debugging aid: run a Lua chunk in the session's state (`lua<TAB>code`), record its error if any.
                    "lua" -> line(Obj().put("k", "lua").put("error", xyz.znix.xftl.hs.lua.HsLua.runString("hs-events", step[1])))
                    // Draw counts of EventGenerator::CreateEvent(name, level, ignoreUnique=true) from fixed seeds
                    // (`createdraws<TAB>level<TAB>seed,seed..<TAB>name...`, names outer, seeds inner, in one frame), as the
                    // oracle's Hyperspace.setRandomSeed + Hyperspace.Event:CreateEvent + random32 measures them. A level
                    // written `N:unique` passes ignoreUnique=false (unique events are used up, texts consumed).
                    "createdraws" -> {
                        val level = step[1].substringBefore(':').toInt()
                        val ignoreUnique = !step[1].endsWith(":unique")
                        val seeds = step[2].split(',').map { it.trim().toInt() }
                        val saved = NativeRandom.snapshot()
                        for (name in step.drop(3)) for (seed in seeds) {
                            val root = game.eventManager.getOrNull(name)
                            if (root == null) { line(Obj().put("k", "cd").put("n", name).put("s", seed).put("error", "unknown")); continue }
                            val trace = ArrayList<Int>()
                            NativeRandom.seed(seed)
                            NativeRandom.trace = trace
                            val created = try { game.eventCreator.create(root, level, ignoreUnique) } finally { NativeRandom.trace = null }
                            line(Obj().put("k", "cd").put("n", name).put("s", seed).put("draws", trace.size).put("v", NativeRandom.next31())
                                .put("event", created?.template?.hsName ?: ""))
                        }
                        NativeRandom.restore(saved)
                    }
                    // Store draw-order search: the next custom store is also generated from the stream's last
                    // reseed with 0..N draws skipped first (`storescan<TAB>N`), one `scan` line each.
                    "storescan" -> {
                        val n = step[1].toInt()
                        val trace = ArrayList<Int>()
                        NativeRandom.trace = trace
                        xyz.znix.xftl.shipgen.ShipGenerator.diagnosticMark = { m -> line(Obj().put("k", "scanMark").put("m", m).put("total", trace.size)) }
                        xyz.znix.xftl.shipgen.ShipGenerator.diagnosticCreated = { name ->
                            line(Obj().put("k", "scanShip").put("ship", name).put("total", trace.size))
                        }
                        xyz.znix.xftl.hs.ship.HsStores.diagnosticScan = { g, def, level ->
                            xyz.znix.xftl.shipgen.ShipGenerator.diagnosticCreated = null
                            xyz.znix.xftl.shipgen.ShipGenerator.diagnosticMark = null
                            xyz.znix.xftl.hs.ship.HsStores.diagnosticScan = null
                            NativeRandom.trace = null
                            val at = trace.lastIndexOf(Int.MIN_VALUE)
                            if (at < 0) line(Obj().put("k", "scanAt").put("error", "no reseed since storescan").put("draws", trace.size))
                            else {
                                val seed = trace[at + 1]
                                val saved = NativeRandom.snapshot()
                                for (off in 0..n) {
                                    NativeRandom.seed(seed)
                                    repeat(off) { NativeRandom.next31() }
                                    line(Obj().put("k", "scan").put("off", off).put("store", RawJson(BrowserHsStoreDiagnostics.json(xyz.znix.xftl.hs.ship.HsStores.generate(g, def, level)))))
                                }
                                NativeRandom.restore(saved)
                                line(Obj().put("k", "scanAt").put("store", def.id).put("level", level).put("seed", seed).put("draws", trace.size - at - 2)
                                    .put("total", trace.size).put("reseeds", trace.indices.filter { trace[it] == Int.MIN_VALUE }.map { "$it:${trace[it + 1]}" }))
                            }
                        }
                    }
                    "wait" ->repeat((step[1].toDouble() / DT).toInt()) { frame(main, container) }
                }
            }
            line(state(main.currentState as InGameState, Obj().put("k", "end")))
            return finish(out, "PASS hs-events", boxes)
        } catch (e: Throwable) {
            line(Obj().put("k", "jsStack").put("stack", BrowserJsStack.of(e)))
            return finish(out, "FAILED hs-events: ${e::class.simpleName}: ${e.message} ${e.stackTrace.take(8).joinToString(" < ")}", boxes)
        } finally {
            HsFlow.boxListener = null
            HsFlow.listTrace = null
            xyz.znix.xftl.shipgen.ShipGenerator.diagnosticCreated = null
            xyz.znix.xftl.shipgen.ShipGenerator.diagnosticMark = null
            xyz.znix.xftl.hs.ship.HsStores.diagnosticScan = null
            NativeRandom.trace = null
            xyz.znix.xftl.game.DialogueWindow.syntheticListener = null
            xyz.znix.xftl.sector.NativeSectorGenerator.placementTrace = null
            HsSeeds.requestedSeed = null
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
    }

    /** Already encoded JSON. */
    class RawJson(val text: String)

    /** A JSON object for the output lines (insertion order). */
    class Obj {
        private val m = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?): Obj { m[k] = v; return this }
        override fun toString(): String = enc(m)
        companion object {
            fun enc(v: Any?): String = when (v) {
                null -> "null"
                is String -> BrowserDebug.quote(v)
                is Number, is Boolean -> v.toString()
                is Obj -> v.toString()
                is RawJson -> v.text
                is Map<*, *> -> v.entries.joinToString(",", "{", "}") { BrowserDebug.quote(it.key.toString()) + ":" + enc(it.value) }
                is Iterable<*> -> v.joinToString(",", "[", "]") { enc(it) }
                else -> BrowserDebug.quote(v.toString())
            }
        }
    }

    /** One engine step as BrowserEngine.step makes it: the update, then the Lua ON_TICK (CApp::OnLoop's hook). */
    private fun frame(main: MainGame, container: GameContainer) {
        main.update(container, DT)
        if (HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.onTick()
    }

    private var idle = 0
    private var autoplay = false

    /**
     * The oracle autoplay's interventions while no box is open (it waits for a quiet second first): a store window
     * closes, a hostile enemy blows up (console DELETE: ShipManager::Blowup of the current target), boarders aboard
     * the player ship die (HS_ORACLE.repel: DirectModifyHealth(-9999) for each crew member with iShipId != 0).
     */
    private fun intervene(game: InGameState, line: (Obj) -> Unit) {
        if (!autoplay) return
        val ui = game.shipUI
        val window = ui.hsDiagnosticWindow
        if (window is xyz.znix.xftl.game.DialogueWindow || game.isPlayerJumping) { idle = 0; return }
        if (++idle < 20) return
        if (window is xyz.znix.xftl.game.StoreWindow) {
            // ships workstream: the store's contents, compared with the oracle's store screenshots.
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
            contents.put("sectorSeed", xyz.znix.xftl.hs.sector.HsSeeds.currentSectorSeed)
            window.escapePressed(); line(Obj().put("k", "close").put("window", "store").put("store", contents)); idle = 0; return
        }
        if (window != null) return
        val enemy = game.enemy
        if (enemy != null && !enemy.isDead && game.isEnemyHostile) {
            enemy.health = 0; line(Obj().put("k", "delete")); idle = 0; return
        }
        val player = game.player
        val boarders = player.crew.filter { it is xyz.znix.xftl.crew.LivingCrew && it.ownerShip !== player && it.health > 0f }
        if (boarders.isNotEmpty()) {
            for (c in boarders) c.health = 0f
            line(Obj().put("k", "repel").put("n", boarders.size)); idle = 0
        }
    }

    /** The sector's beacons (position and event name) and its type, as the tracer's state.map.locs. */
    private fun map(game: InGameState, o: Obj): Obj {
        val sector = game.currentBeacon.sector
        o.put("sectorType", xyz.znix.xftl.hs.events.HsEvents.descriptionType(sector.info))
        o.put("locs", sector.beacons.map { b ->
            Obj().put("at", "${b.pos.x},${b.pos.y}").put("ev", b.createdEvent.template.hsName ?: "")
        })
        return o
    }

    private fun finish(out: StringBuilder, result: String, boxes: Int): String =
        out.append(Obj().put("k", "result").put("result", result).put("boxes", boxes).toString()).append('\n').toString()

    private fun state(game: InGameState, o: Obj): Obj {
        val vars = Obj()
        for ((k, v) in HsEvents.playerVariables.toSortedMap()) if (v != 0) vars.put(k, v)
        o.put("vars", vars)
        o.put("cur", "${game.currentBeacon.pos.x},${game.currentBeacon.pos.y}")
        val p = game.player
        o.put("player", Obj().put("fuel", p.fuelCount).put("missiles", p.missilesCount).put("drones", p.dronesCount)
            .put("scrap", p.scrap).put("hull", p.health).put("weapons", p.hardpoints.mapNotNull { it.weapon?.type?.name })
            .put("augs", p.augments.map { it.name }))
        o.put("questSeed", HsSeeds.questSeed)
        // ships workstream: the current enemy, compared with the tracer's ship_gen lines (.build/ships/compare-enemies.py).
        game.enemy?.let { e ->
            o.put("enemy", Obj().put("bp", e.type.name).put("hull", e.maxHealth).put("crew", e.crew.size).put("automated", e.isAutomated)
                .put("systems", e.systems.map { "${it.blueprint.type}:${it.energyLevels}" }.sorted()))
        }
        return o
    }
}
