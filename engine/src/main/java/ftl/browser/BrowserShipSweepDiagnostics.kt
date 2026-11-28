package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.FireDamage
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon

/**
 * Ship sweep (r27): every ship blueprint of the loaded game, exercised on its own and compared with the original.
 *
 * Cases (`shipSweepCount/Name/Probe`, web/diagnostics.mjs mode `ship-sweep`, `?shipsweep=<from>,<count>` runs a slice):
 * first every ship blueprint as an enemy (sorted by name), then every hangar layout as the player's ship.
 * - enemy: the blueprint as an event ship (`<ship auto_blueprint=...>`) at a world level and seed of its own, against
 *   the sweep's player ship; its start checked ([validate]); a short fight with both ships' weapons and drones at each
 *   other; a save (the autosave's MainGame.writeRunSave) and a load; then one ending in turn (destroyed, its crew
 *   killed, its escape) with a save and load after it, the ship checked before and after each load.
 * - player: a new run with the layout; its start checked; a short fight against a fixed enemy, a jump to the next
 *   beacon, a save and a load, the ship checked again.
 * A case fails on any exception in a frame, drawing, save or load, a load of this build's own save that needed
 * repairs, with Hyperspace a new engine error inside a Lua call, and on an invalid value (NaN or a negative health,
 * a system in no room or above its maximum, crew in no room, an unknown blueprint, an empty name).
 *
 * `shipSweepDump(name, sector, seed)` is the comparison with the original (tools/hs-oracle probe r27-ships.lua): the
 * blueprint built by ShipGenerator.buildShip with that seed, in the oracle's `r27s` row layout.
 *
 * Diagnostics only: nothing here changes game logic.
 */
object BrowserShipSweepDiagnostics {
    private const val DT = 1f / 32f
    private const val SEED0 = 27_000
    private val q = BrowserDebug::quote

    enum class Ending { DESTROYED, CREW_KILLED, ESCAPE }

    private class Failure(message: String, cause: Throwable?) : RuntimeException(message, cause)

    // ---- Cases ------------------------------------------------------------------------------------------------------

    /** Every ship blueprint, sorted (the oracle probe's R27S_NAMES has the same order). */
    fun blueprints(main: MainGame): List<String> = main.gameContent.blueprintManager.blueprints.values
        .filterIsInstance<LazyShipBlueprint>().map { it.name }.sorted()

    /** The hangar's layouts in its order (Hyperspace: every button and its B/C layouts). */
    fun hangar(main: MainGame): List<String> {
        val bm = main.gameContent.blueprintManager
        val out = LinkedHashSet<String>()
        if (HS.active) {
            for (n in blueprints(main)) if (xyz.znix.xftl.hs.ship.HsHangar.variantOf(n) != null) out += n
        } else {
            for (f in main.gameContent.shipFamilies.families) for (s in f.ships) if (bm.getOrNull(s) != null) out += s
        }
        return out.toList()
    }

    private var cases: List<String>? = null

    private fun cases(main: MainGame): List<String> =
        cases ?: (blueprints(main).map { "enemy $it" } + hangar(main).map { "player $it" }).also { cases = it }

    fun count(main: MainGame): Int = cases(main).size
    fun name(main: MainGame, i: Int): String = cases(main)[i] + if (cases(main)[i].startsWith("enemy")) " " + Ending.values()[i % 3].name.lowercase() else ""

    private class Ctx(val main: MainGame, val container: GameContainer, val graphics: Graphics, val index: Int) {
        lateinit var state: InGameState
        var phase = "setup"
            set(v) { field = v; if (trace) println("TEST r27s case $index: $v") }
        var frame = 0
        var trips = 0
        val notes = LinkedHashSet<String>()
        val luaErrors = LinkedHashSet<String>()
    }

    /** The sweep's own run: reused between cases while it is healthy (a new run every 40 cases). */
    private var run: InGameState? = null
    private var runCases = 0
    /** The player's own crew when [run] started. */
    private var runCrew = 0

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, index: Int): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val case = cases(main)[index]
        val (role, ship) = case.split(' ', limit = 2)
        val c = Ctx(main, container, graphics, index)
        try {
            BrowserRandom.restore((SEED0 + index).toString())
            NativeRandom.seed(SEED0 + index)
            if (trace && HS.luaActive) xyz.znix.xftl.hs.lua.HsLua.runString("r27s-watchdog", LUA_WATCHDOG)
            val luaBefore = if (HS.luaActive) xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() else emptySet()
            try {
                if (role == "enemy") enemyCase(c, ship, Ending.values()[index % 3]) else playerCase(c, ship)
            } finally {
                if (HS.luaActive) c.luaErrors += xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() - luaBefore
            }
            if (c.luaErrors.isNotEmpty()) throw Failure("an engine error inside a Lua call (${c.luaErrors.joinToString(" | ").take(600)})", null)
            return "PASS ship sweep ${name(main, index)}: ${c.trips} save/loads" + (if (c.notes.isEmpty()) "" else "; " + c.notes.joinToString(", "))
        } catch (e: Throwable) {
            run = null
            try { BrowserGameplayParityDiagnostics.fresh(main, container) } catch (ignored: Throwable) {}
            var root: Throwable = (e as? Failure)?.cause ?: e
            while (root is xyz.znix.xftl.savegame.RunLoadException && root.cause != null) root = root.cause!!
            throw IllegalStateException(
                "ship sweep ${name(main, index)} ${(e as? Failure)?.message ?: "in ${c.phase} frame ${c.frame}"}" +
                        (if (root === e && e is Failure) "" else ": ${root::class.simpleName}: ${root.message} @ ${BrowserJsStack.of(root).lines().take(22).joinToString(" < ") { it.trim() }}")
            )
        } finally {
            main.currentState.let { if (it is InGameState) { it.debugFlags.noDmg.set = false; it.debugFlags.noCrewDamage.set = false } }
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** The player's ship of the enemy cases: the first hangar ship (the MV Kestrel with Multiverse). */
    private fun sweepPlayer(main: MainGame): String =
        if (main.gameContent.blueprintManager.getOrNull("PLAYER_SHIP_MVKESTREL") != null) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD"

    private fun sweepRun(c: Ctx): InGameState {
        val current = c.main.currentState as? InGameState
        val reuse = run
        if (reuse != null && current === reuse && runCases < 40 && !reuse.browserRunEnded && reuse.player.health > 0 && !reuse.isPlayerJumping) {
            // An earlier enemy's boarders stay aboard when their ship escapes (as in the game) and go on killing the
            // crew in later cases (MU_JUDGES' judges: TERRORIZE sets every crew's max health to 10, one-hit kills), which
            // ends the run LOOSE_CREW in another ship's case: such a run, or one that has lost a crew member, is not reused.
            val boarders = reuse.player.crew.filter { it is LivingCrew && it.ownerShip !== reuse.player }
            val own = reuse.player.crew.count { it is LivingCrew && it.ownerShip === reuse.player && it.health > 0f }
            if (boarders.isEmpty() && own >= runCrew) {
                runCases++
                return reuse
            }
            if (trace) println("TEST r27s case ${c.index}: a new run (${boarders.size} boarders aboard: " +
                    "${boarders.joinToString { it.codename }}; $own of $runCrew own crew)")
        }
        val s = BrowserGameplayParityDiagnostics.fresh(c.main, c.container, sweepPlayer(c.main))
        HsConsole.run(s, "GOD")
        s.player.fuelCount = 20
        s.player.missilesCount = 40
        s.player.dronesCount = 40
        run = s
        runCases = 0
        runCrew = s.player.crew.count { it is LivingCrew && it.ownerShip === s.player }
        return s
    }

    // ---- Enemy role -------------------------------------------------------------------------------------------------

    private fun spec(s: InGameState, name: String): EnemyShipSpec {
        val elem = Element("ship")
        elem.setAttribute("name", "R27S_$name")
        elem.setAttribute("auto_blueprint", name)
        return EnemyShipSpec(elem, s.blueprintManager, s.eventManager)
    }

    private fun enemyCase(c: Ctx, name: String, ending: Ending) {
        c.state = sweepRun(c)
        val level = c.index % 8
        spawn(c, name, level)
        c.phase = "fight"
        fight(c, 96)
        trip(c)
        check(c, "after the load") { c.state.enemy?.type?.name == name || c.state.enemy == null }
        c.state.enemy?.let { validate(c, it, "after the load") }
        c.phase = "ending ${ending.name.lowercase()}"
        guard(c, "the ending") { end(c, ending) }
        for (f in 0 until 200) {
            c.frame = f
            frame(c)
            if (ending == Ending.CREW_KILLED) killEnemyCrew(c.state)
            val e = c.state.enemy
            if (e == null || e.isGone || (!c.state.isEnemyHostile && f > 40)) break
        }
        c.state.enemy?.let { validate(c, it, "after the ending") }
        trip(c)
        c.state.enemy?.let { validate(c, it, "after the ending's load") }
        // The next case gets a quiet beacon: the enemy goes (DELETE, as the console does).
        if (c.state.enemy != null) guard(c, "clearing the beacon") { HsConsole.run(c.state, "DELETE") }
        repeat(8) { frame(c) }
        BrowserDiagnostics.closeBoxes(c.main, c.container)
    }

    private fun spawn(c: Ctx, name: String, level: Int) {
        val s = c.state
        c.phase = "spawn"
        guard(c, "building the ship") { s.debugSpawnShip(spec(s, name), Difficulty.NORMAL, level, SEED0 + c.index) }
        val e = s.enemy ?: throw Failure("no ship built", null)
        check(c, "the enemy is the blueprint") { e.type.name == name }
        validate(c, e, "at its start")
        s.debugFlags.noEnemyFire.set = false
        s.debugFlags.noDmg.set = false
        if (s.isPaused && !s.shipUI.isWindowOpen) s.toggleQuickPause()
    }

    private fun end(c: Ctx, ending: Ending) {
        val s = c.state
        val e = s.enemy ?: return
        when (ending) {
            Ending.DESTROYED -> HsConsole.run(s, "DELETE")
            Ending.CREW_KILLED -> killEnemyCrew(s)
            Ending.ESCAPE -> {
                e.health = maxOf(1, if (e.escapeHealth > 0) e.escapeHealth else e.surrenderHealth)
                if (e.escapeTimer == null) e.escapeTimer = 1f
            }
        }
    }

    private fun killEnemyCrew(s: InGameState) {
        val enemy = s.enemy ?: return
        for (ship in listOf(s.player, enemy)) for (m in ArrayList(ship.crew))
            if (m is LivingCrew && m.ownerShip === enemy && m.health > 0f) m.dealDamage(FireDamage(10_000f))
    }

    // ---- Player role ------------------------------------------------------------------------------------------------

    private fun playerCase(c: Ctx, name: String) {
        run = null
        c.phase = "start"
        guard(c, "starting the run") { c.state = BrowserGameplayParityDiagnostics.fresh(c.main, c.container, name) }
        val p = c.state.player
        check(c, "the player's ship is the layout") { p.type.name == name }
        validate(c, p, "at the start")
        trip(c)
        validate(c, c.state.player, "after the start's load")
        HsConsole.run(c.state, "GOD")
        // A fixed small enemy, then a jump away and a save on arrival.
        val bm = c.state.blueprintManager
        val foe = listOf("MU_REBEL_FIGHTER_START", "PIRATE_FIGHTER", "REBEL_FAT", "TUTORIAL_PIRATE").firstOrNull { bm.getOrNull(it) is LazyShipBlueprint }
        if (foe != null) {
            spawn(c, foe, 0)
            c.phase = "fight"
            fight(c, 96)
            guard(c, "clearing the enemy") { HsConsole.run(c.state, "DELETE") }
            repeat(40) { c.frame = it; frame(c) }
        }
        c.phase = "jump"
        val s = c.state
        BrowserDiagnostics.closeBoxes(c.main, c.container)
        val to = s.currentBeacon.neighbours.firstOrNull()
        if (to == null) c.notes += "no neighbour beacon" else {
            s.player.fuelCount = maxOf(s.player.fuelCount, 3)
            s.player.ftlChargeProgress = 1f
            guard(c, "jumping") { s.beginJumpOut(to) }
            for (f in 0 until 200) {
                c.frame = f
                frame(c)
                if (!c.state.isPlayerJumping && f > 40) break
            }
        }
        trip(c)
        validate(c, c.state.player, "after the jump's load")
        run = null
    }

    // ---- Fight, frames, save and load -------------------------------------------------------------------------------

    private fun fight(c: Ctx, frames: Int) {
        for (f in 0 until frames) {
            c.frame = f
            if (f % 32 == 0) attacks(c)
            val p = c.state.player
            if (p.health < p.maxHealth / 2) p.health = p.maxHealth
            frame(c)
            if (c.state.enemy == null) break
        }
    }

    /** Both ships' weapons and drones at each other (the enemy's AI aims its own; its drones are powered). */
    private fun attacks(c: Ctx) {
        val s = c.state
        val p = s.player
        val enemy = s.enemy ?: return
        guard(c, "aiming") {
            val rooms = enemy.rooms
            if (rooms.isNotEmpty()) p.weapons?.let { w ->
                for ((slot, hp) in p.hardpoints.withIndex()) {
                    val weapon = hp.weapon ?: continue
                    if (!weapon.isPowered) w.setWeaponPower(weapon, true)
                    val room = rooms[(slot + c.frame) % rooms.size]
                    when (weapon) {
                        is IRoomTargetingWeapon -> w.selectedTargets.targetRoom(slot, room)
                        is BeamBlueprint.BeamInstance -> w.selectedTargets.targetBeam(slot, weapon.buildAim(room, rooms[(slot + 1) % rooms.size], false).also { it.weaponNumber = slot })
                        else -> {}
                    }
                }
            }
            p.drones?.let { d -> for (i in d.drones.indices) if (d.drones[i] != null) d.setDronePower(i, true) }
            enemy.drones?.let { d -> for (i in d.drones.indices) if (d.drones[i] != null) d.setDronePower(i, true) }
        }
    }

    /** `shipSweepTrace(true)`: each case's phases and every 50th frame as "TEST r27s" console lines (finding a hang). */
    var trace = false

    /**
     * With [trace]: a Lua count hook (the state has the debug library, hslua.c chains it with the instruction cap) that
     * prints the Lua traceback once a single engine tick has run 1e6 Lua instructions, and raises an error at 5e6, to
     * name a script that loops.
     */
    private const val LUA_WATCHDOG = """
if debug and debug.sethook and not R27S_WD then
  R27S_WD = 0
  script.on_internal_event(Defines.InternalEvents.ON_TICK, function() R27S_WD = 0 end, 2147483647)
  debug.sethook(function()
    R27S_WD = R27S_WD + 1
    if R27S_WD == 100 or R27S_WD == 500 then
      local tb = debug.traceback('', 2)
      print('TEST r27s LUA WATCHDOG ' .. R27S_WD .. 'e4 instructions this tick: ' .. string.gsub(tb, '%s+', ' '))
      if R27S_WD == 500 then error('r27s lua watchdog: ' .. tb) end
    end
  end, '', 10000)
end
"""

    private fun frame(c: Ctx) {
        if (trace && c.frame % 50 == 0) println("TEST r27s case ${c.index}: ${c.phase} frame ${c.frame}")
        guard(c, "the frame") { BrowserEngine.step(DT) }
        if (c.frame % 8 == 0) guard(c, "drawing") {
            val s = c.main.currentState
            if (s is InGameState) s.setRenderingDeltaTime(DT * 8)
            BrowserEngine.render()
        }
        val s = c.main.currentState as? InGameState ?: throw Failure("the frame left the run in ${c.phase}", null)
        c.state = s
        // The player's own crew stay alive, as the hull stays above half: an enemy's boarders would otherwise end the
        // run (LOOSE_CREW) before the case's ending and its save and load.
        for (ship in listOfNotNull(s.player, s.enemy)) for (m in ship.crew)
            if (m is LivingCrew && m.ownerShip === s.player && m.health > 0f && m.health < m.maxHealth / 2) m.health = m.maxHealth
        if (trace) traceCrew(c, s)
        s.shipUI.diagnosticDialogue?.let { d -> guard(c, "answering a box") { d.selectOption(d.diagnosticEnabledOptions.first()) } }
        if (s.isPaused && !s.shipUI.isWindowOpen && s.shipUI.diagnosticDialogue == null) s.toggleQuickPause()
    }

    /** The player's own crew at the last traced frame: where each is (ship, room, mind control, clone queue) and its health. */
    private var crewSeen = HashMap<LivingCrew, Pair<String, Float>>()

    /** With [trace]: a "TEST r27s crew" line when one of the player's own crew moves ship, is mind controlled, goes to
     * the clone queue, leaves every list, dies, or loses a third of its health in one frame (what ends a run LOOSE_CREW). */
    private fun traceCrew(c: Ctx, s: InGameState) {
        val now = HashMap<LivingCrew, Pair<String, Float>>()
        for (ship in listOfNotNull(s.player, s.enemy)) for (m in ship.crew)
            if (m is LivingCrew && m.ownerShip === s.player)
                now[m] = Pair("${if (ship === s.player) "player" else "enemy"}${if (m.room.ship !== ship) " (room of the other ship)" else ""}" +
                        (if (m.isMindControlled) " mind controlled" else "") + " room ${m.room.id}", m.health)
        s.player.clonebay?.queue?.forEach { if (it.ownerShip === s.player) now[it] = Pair("clone queue room -1", it.health) }
        val head = "TEST r27s crew case ${c.index} ${c.phase} frame ${c.frame}:"
        for ((m, was) in crewSeen) {
            val is_ = now[m]
            when {
                is_ == null -> println("$head ${m.codename} left every list (was ${was.first}, hp ${was.second}/${m.maxHealth})")
                was.second > 0f && is_.second <= 0f -> println("$head ${m.codename} died in ${is_.first} (hp ${was.second} -> ${is_.second})")
                is_.first.substringBefore(" room") != was.first.substringBefore(" room") ->
                    println("$head ${m.codename} ${was.first} -> ${is_.first}, hp ${is_.second}/${m.maxHealth}")
                was.second - is_.second > m.maxHealth / 3 -> println("$head ${m.codename} hp ${was.second} -> ${is_.second} in ${is_.first}")
            }
        }
        for ((m, is_) in now) if (m !in crewSeen) println("$head ${m.codename} new in ${is_.first}, hp ${is_.second}/${m.maxHealth}")
        crewSeen = now
    }

    private inline fun guard(c: Ctx, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Failure) {
            throw e
        } catch (e: Throwable) {
            throw Failure("$what in ${c.phase} frame ${c.frame}", e)
        }
    }

    private inline fun check(c: Ctx, what: String, ok: () -> Boolean) {
        if (!ok()) throw Failure("$what failed in ${c.phase}", null)
    }

    /** Save (the autosave's way) and load; the case goes on with the loaded game. */
    private fun trip(c: Ctx) {
        val s = c.state
        c.trips++
        guard(c, "saving") { c.main.writeRunSave() }
        val xml = BrowserFiles.getRun() ?: run {
            // the run ended (MainGame.writeRunSave writes none then): say how, with the player's state
            val p = s.player
            val crew = p.crew.count { it is LivingCrew && it.ownerShip === p && it.health > 0f }
            throw Failure("no run save written in ${c.phase}: the run ended (${s.shipUI.gameOverWindow?.outcome}, player hull " +
                    "${p.health}/${p.maxHealth}, $crew own crew alive, enemy ${s.enemy?.type?.name})", null)
        }
        guard(c, "loading") { c.main.loadSavedGame(BrowserXml.parse(xml)) }
        val loaded = c.main.currentState as? InGameState ?: throw Failure("the load left the run in ${c.phase}", null)
        if (loaded.loadDroppedRefs.isNotEmpty() || loaded.saveMigrationLines.isNotEmpty())
            throw Failure("a save of this build needed repairs in ${c.phase}: ${loaded.loadDroppedRefs} ${loaded.saveMigrationLines}", null)
        loaded.debugFlags.noEnemyFire.set = s.debugFlags.noEnemyFire.set
        BrowserDiagnostics.closeBoxes(c.main, c.container)
        if (loaded.isPaused && !loaded.shipUI.isWindowOpen) loaded.toggleQuickPause()
        if (run === s) run = loaded
        c.state = loaded
    }

    // ---- Validation -------------------------------------------------------------------------------------------------

    /** Invalid values of a built or loaded ship. Notes (kept in the PASS line) are what the original shares. */
    private fun validate(c: Ctx, ship: Ship, at: String) {
        val bad = ArrayList<String>()
        val bm = c.state.blueprintManager
        if (ship.maxHealth <= 0) bad += "max hull ${ship.maxHealth}"
        if (ship.health < 0 || ship.health > ship.maxHealth) bad += "hull ${ship.health}/${ship.maxHealth}"
        if (ship.rooms.isEmpty()) bad += "no rooms"
        for (r in ship.rooms) if (r.width <= 0 || r.height <= 0) bad += "room ${r.id} ${r.width}x${r.height}"
        val rooms = ship.rooms.toSet()
        for (sys in ship.systems) {
            val room = sys.room
            if (room == null || room !in rooms) bad += "${sys.codename} in no room of the ship"
            if (sys.energyLevels < 1) bad += "${sys.codename} level ${sys.energyLevels}"
            if (sys is MainSystem && (sys.powerSelected < 0 || sys.powerSelected > sys.energyLevels)) bad += "${sys.codename} power ${sys.powerSelected}/${sys.energyLevels}"
            if (sys.damagedEnergyLevels < 0) bad += "${sys.codename} damage ${sys.damagedEnergyLevels}"
        }
        for (m in ship.crew) {
            if (m.health.isNaN() || m.health < 0f || m.maxHealth.isNaN() || m.maxHealth <= 0f) bad += "crew ${m.codename} health ${m.health}/${m.maxHealth}"
            if (m.room.ship !== ship && m.room.ship !== c.state.player && m.room.ship !== c.state.enemy) bad += "crew ${m.codename} in a room of no ship"
            if (m is LivingCrew && m.info.name.isEmpty()) c.notes += "crew without a name ($at)"
        }
        for (hp in ship.hardpoints) hp.weapon?.let { if (bm.getOrNull(it.type.name) == null) bad += "unknown weapon ${it.type.name}" }
        ship.drones?.drones?.forEach { d -> d?.let { if (bm.getOrNull(it.type.name) == null) bad += "unknown drone ${it.type.name}" } }
        if (ship.superShield < 0) bad += "super shield ${ship.superShield}"
        if (ship.hullImage == null) c.notes += "no hull image"
        if (bad.isNotEmpty()) throw Failure("invalid ${ship.type.name} $at: ${bad.joinToString("; ").take(800)}", null)
    }

    // ---- The comparison with the original ---------------------------------------------------------------------------

    /**
     * The oracle probe's row for [name] built at [sector] with [seed] (r27-ships.lua: ShipGenerator::CreateShip with
     * the stream reseeded at GENERATOR_CREATE_SHIP): the ship from ShipGenerator.buildShip with that seed, in the
     * tracer's ship_full layout (rooms and doors in the ship's pixels, systems with their power and level).
     */
    fun dump(main: MainGame, container: GameContainer, name: String, sector: Int, seed: Int): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val cur = main.currentState as? InGameState
            val s = if (cur != null && cur === run) cur else sweepRunFor(main, container)
            val ship = try {
                s.content.generator.buildShip(s, spec(s, name), sector, Difficulty.NORMAL, seed)
            } catch (e: Throwable) {
                return "{\"name\":${q(name)},\"seed\":$seed,\"sector\":$sector,\"error\":${q(e.toString() + " @ " + BrowserJsStack.of(e).lines().take(8).joinToString(" < ") { it.trim() })}}"
            }
            return "{\"name\":${q(name)},\"seed\":$seed,\"sector\":$sector,\"ship\":${shipJson(ship)}}"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * [dump]'s build of [name] with every draw of the generator's stream recorded: each reseed and each value with the JS
     * stack that took it (minified; tools/hs-fullrun/sourcemap.py names the frames), for the comparison with the
     * oracle's random32 call trace (session r27s-rng, make-trace-exe.py's ring).
     */
    private fun rngTrace(main: MainGame, container: GameContainer, name: String, sector: Int, seed: Int): String {
        val values = ArrayList<Int>()
        val stacks = ArrayList<String>()
        val prevTrace = NativeRandom.trace
        val prevLive = NativeRandom.live
        NativeRandom.trace = values
        NativeRandom.live = object : NativeRandom.Live {
            override fun onSeed(seed: Int) {}
            override fun onDraw(live: Boolean) { stacks += BrowserJsStack.here() }
        }
        val row = try { dump(main, container, name, sector, seed) } finally { NativeRandom.trace = prevTrace; NativeRandom.live = prevLive }
        // values: a draw (31 bits, never negative) or Int.MIN_VALUE followed by a reseed's seed
        val out = StringBuilder()
        var k = 0
        var v = 0
        while (v < values.size) {
            if (out.isNotEmpty()) out.append(',')
            if (values[v] == Int.MIN_VALUE) {
                out.append("{\"seed\":").append(values.getOrNull(v + 1)).append('}'); v += 2
            } else {
                val st = stacks.getOrNull(k++).orEmpty().lines().drop(2).take(24).joinToString(" < ") { it.trim().removePrefix("at ") }
                out.append("{\"v\":").append(values[v]).append(",\"at\":").append(q(st)).append('}'); v += 1
            }
        }
        return "{\"row\":$row,\"draws\":[$out]}"
    }

    private fun sweepRunFor(main: MainGame, container: GameContainer): InGameState {
        val s = BrowserGameplayParityDiagnostics.fresh(main, container, sweepPlayer(main))
        run = s
        runCases = 0
        runCrew = s.player.crew.count { it is LivingCrew && it.ownerShip === s.player }
        return s
    }

    /** The ship in the tracer's ship_full layout (tools/hs-oracle/tracer/data/hs_oracle/tracer.lua). */
    fun shipJson(ship: Ship): String {
        val b = StringBuilder("{")
        /** A JSON value written as it is (an object or a list built here), not quoted. */
        class Raw(val json: String)
        fun f(k: String, v: Any?) {
            if (b.length > 1) b.append(',')
            b.append(q(k)).append(':').append(
                when (v) {
                    null -> "null"
                    is Raw -> v.json
                    is String -> q(v)
                    else -> v.toString()
                }
            )
        }
        fun r(json: String) = Raw(json)
        f("bp", ship.type.name)
        f("hull", ship.health); f("hullMax", ship.maxHealth)
        f("missiles", ship.missilesCount); f("drones", ship.dronesCount)
        f("reactor", r("{\"max\":${ship.purchasedReactorPower}}"))
        f("automated", ship.isAutomated)
        f("weaponSlots", ship.weaponSlots); f("droneSlots", ship.droneSlots)
        f("systems", r(ship.systems.joinToString(",", "[", "]") { sys ->
            "{\"name\":${q(sys.codename)},\"room\":${sys.room?.id},\"level\":${sys.energyLevels},\"maxLevel\":${sys.maxLevel}," +
                    "\"power\":${(sys as? MainSystem)?.powerSelected},\"supplied\":${(sys as? MainSystem)?.powerSupplied},\"health\":${sys.energyLevels - sys.damagedEnergyLevels}," +
                    "\"subsystem\":${sys !is MainSystem}}"
        }))
        f("weapons", r(ship.hardpoints.mapNotNull { it.weapon }.joinToString(",", "[", "]") { w -> "{\"bp\":${q(w.type.name)},\"powered\":${w.isPowered}}" }))
        f("droneList", r((ship.drones?.drones ?: emptyList()).filterNotNull().joinToString(",", "[", "]") { d -> "{\"bp\":${q(d.type.name)}}" }))
        val aug = LinkedHashMap<String, Int>()
        for (a in ship.augments) aug[a.name] = (aug[a.name] ?: 0) + 1
        ship.hs?.hiddenAugs?.forEach { (k, v) -> aug["HIDDEN $k"] = (aug["HIDDEN $k"] ?: 0) + v }
        f("augments", r(aug.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${q(it.key)}:${it.value}" }))
        f("crew", r(ship.crew.joinToString(",", "[", "]") { m ->
            val lc = m as? LivingCrew
            "{\"species\":${q(lc?.info?.race?.name ?: m.codename)},\"room\":${m.room.id},\"name\":${lc?.info?.shortName?.let(q) ?: "null"},\"fullName\":${lc?.info?.name?.let(q) ?: "null"}," +
                    "\"hp\":${m.health},\"hpMax\":${m.maxHealth},\"drone\":${m is AbstractIndoorsDrone.Pawn},\"male\":${lc?.info?.male}}"
        }))
        f("rooms", r(ship.rooms.joinToString(",", "[", "]") { m -> "{\"id\":${m.id},\"tx\":${m.x},\"ty\":${m.y},\"tw\":${m.width},\"th\":${m.height}}" }))
        f("doors", r(ship.doors.filter { !it.isAirlock }.joinToString(",", "[", "]") { d -> doorJson(d) }))
        f("airlocks", r(ship.doors.filter { it.isAirlock }.joinToString(",", "[", "]") { d -> doorJson(d) }))
        f("offset", r("[${ship.offset.x},${ship.offset.y}]"))
        f("shields", r("{\"super\":${ship.superShield},\"superMax\":${ship.maxSuperShield}}"))
        f("dodge", ship.dodgeFactor)
        f("hullImage", ship.hullImage != null)
        b.append('}')
        return b.toString()
    }

    private fun doorJson(d: xyz.znix.xftl.layout.Door): String =
        "{\"r1\":${d.left?.id ?: -1},\"r2\":${d.right?.id ?: -1},\"tx\":${d.position.x},\"ty\":${d.position.y},\"vertical\":${d.isVertical}}"

    /** The start of [name] as the player's ship, in the same layout as the oracle's hangar starts (tools/hs-oracle ships). */
    fun startDump(main: MainGame, container: GameContainer, name: String, frames: Int = 0): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            run = null
            main.startDiagnosticGame(name, Difficulty.NORMAL)
            main.update(container, 0f)
            // [frames] engine steps after the start (the oracle reads its hangar starts a second into the run)
            repeat(frames) { BrowserEngine.step(DT) }
            val s = main.currentState as InGameState
            return "{\"name\":${q(name)},\"ship\":${shipJson(s.player)}}"
        } catch (e: Throwable) {
            return "{\"name\":${q(name)},\"error\":${q(e.toString() + " @ " + BrowserJsStack.of(e).lines().take(8).joinToString(" < ") { it.trim() })}}"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** `shipSweepDump("names")`, `("dump <sector> <seed0> <from> <count>")`: rows for a slice of [blueprints], one per line. */
    fun probe(main: MainGame, container: GameContainer, what: String): String {
        val p = what.split(' ')
        return when (p[0]) {
            "names" -> blueprints(main).joinToString("\n")
            "hangar" -> hangar(main).joinToString("\n")
            "dump" -> {
                val names = blueprints(main)
                val sector = p[1].toInt(); val seed0 = p[2].toInt(); val from = p[3].toInt(); val count = p[4].toInt()
                // the probe's index is 1-based (Lua), its seed seed0 + index
                (from until minOf(names.size, from + count)).joinToString("\n") { i -> dump(main, container, names[i], sector, seed0 + i + 1) }
            }
            "rngtrace" -> rngTrace(main, container, p[3], p[1].toInt(), p[2].toInt()) // `("rngtrace <sector> <seed> <name>")`
            "start" -> startDump(main, container, p[1], p.getOrNull(2)?.toInt() ?: 0)
            "checks" -> oracleChecks(main, container)
            else -> error("unknown shipSweepDump command '$what'")
        }
    }

    /**
     * Systems as the original builds them, from its r27-ships probe (sector, seed, system in its room, level, power):
     * Hyperspace's AddSystem hook raises a system above the systemBlueprint's maximum after the original powered it,
     * so those bars stay unpowered (BOSS_1_HARD_DLC, MT_BOSS_MANTIS_EXTREME); the generator's upgrades take no power
     * either (FED_SCOUT shields, MU_AUG_DESTROYER_RUINS medbay).
     */
    private val oracleSystems = listOf(
        listOf("BOSS_1_HARD_DLC", 0, 27009, "medbay", 11, 4, 3), listOf("BOSS_1_HARD_DLC", 0, 27009, "oxygen", 23, 4, 3),
        listOf("MT_BOSS_MANTIS_EXTREME", 0, 27193, "teleporter", 7, 4, 3), listOf("MT_BOSS_MANTIS_EXTREME", 0, 27193, "medbay", 8, 3, 3),
        listOf("FED_SCOUT", 0, 27092, "shields", 1, 3, 2), listOf("MU_AUG_DESTROYER_RUINS", 0, 27330, "medbay", 4, 4, 3),
        // CreateSystems powers the shields at their place in the systemList (oxygen listed first, reactor short)
        listOf("MU_UNION_REFUEL_DETECTIVE", 0, 28467, "oxygen", 18, 2, 2), listOf("MU_UNION_REFUEL_DETECTIVE", 0, 28467, "shields", 16, 6, 4),
        listOf("MU_MONK_WAT", 0, 27883, "oxygen", 6, 2, 1),
        // a hull-repair drone's lifespan draw (ShipRepairDrone 1.6.9 0x4246c0) before the system maximums
        listOf("MT_BOSS_MONK_CHAOS", 4, 47196, "shields", 19, 7, 6), listOf("MU_AUG_CRUISER", 4, 47328, "shields", 17, 6, 6),
    )

    /**
     * The original's weapons or drones where a list is cut short (the r27-ships probe): the weapon loop stops at
     * `<weaponList count>` (MT_BOSS_TUCO_CHALLENGE: count 3, four listed); DroneSystem::AddDrone drops a drone once
     * the slots are full (MTS_BOSS_OSMIAN_CHAOS: three slots, four listed); a blueprint list in the list picks where
     * its weapon or drone is added.
     */
    private val oracleLists = listOf(
        listOf("MT_BOSS_TUCO_CHALLENGE", 0, 27227, "weapons", "LASER_CHARGEGUN_2,BOMB_BREACH_1_ENEMY,SHOTGUN_2"),
        listOf("MTS_BOSS_OSMIAN_CHAOS", 0, 27128, "drones", "BATTLETOG2_BOARDER,HOLODRONE_BOARDER,BATTLETOG2_DEFEND"),
        // a list entry picks at its own place in the stream (HsShipStart.installInOrder)
        listOf("MU_ALKRAM_TRAPPER", 0, 27295, "weapons", "LASER_HULL_1_ENEMY,SHOTGUN_4,SPORE_1_ENEMY"),
        listOf("MT_ENGI_1", 0, 27245, "drones", "BATTLETOG_BOARDER,REPAIR"),
        // the crew's rooms: a second artillery entry with one artillery room goes there again
        listOf("MU_ANCIENT_LIGHT_CRUISER", 0, 27310, "crewRooms", "0,12,12,7,1"),
        // a hidden augment's super shield (customShip <hiddenAug>FLAGSHIP_SHIELD)
        listOf("MU_MFK_FLAGSHIP_NORMAL", 0, 27853, "superShield", "10/10"),
        // no crew limit for the enemy under Hyperspace: the crewCount member after a 14-member <crew> list
        listOf("MU_MORPH_CRUISER", 0, 27884, "crewRooms", "0,0,0,3,3,3,12,12,15,15,13,13,13,13,1"),
        // an enemy medbay's machine tile takes crew (AddSystem fills it on the player's ship only)
        listOf("MU_TRAVELING_MERCHANT_NORMAL", 0, 28455, "crewRooms", "11,11,11,11,6,5,12,0,5,0"),
        // the drone picked after a hull-repair drone, whose constructor draws its lifespan (1.6.9 0x4246c0)
        listOf("MU_MV_COLONIZER", 4, 47893, "drones", "SHIP_REPAIR_ENEMY,BATTLETOG_DEFEND"),
        // each member's blueprint.male, M or F: every race takes its drawn name's sex (GetCrewBlueprint 0x5e9690),
        // a <crew> member with a name the random32() % 2 argument (AddCrewMemberFromString 0x4a3ee0)
        listOf("MU_FED_SCOUT_ELITE", 4, 47627, "crewMale", "FFFFM"),
        listOf("MU_DUSK_GUARD_ELITE", 4, 47524, "crewMale", "MFFFFFM"),
        listOf("MTS_BOSS_SYLVAN_CHAOS", 4, 47140, "crewMale", "FFMFFMF"),
        listOf("MU_GB_VINZ_STASH", 0, 27667, "crewMale", "FFFFMMMFM"),
    )

    /** [name] built as the oracle row was (sector, seed), the random streams as they were before. */
    private fun build(main: MainGame, container: GameContainer, name: String, sector: Int, seed: Int): Ship {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val cur = main.currentState as? InGameState
            val s = if (cur != null && cur === run) cur else sweepRunFor(main, container)
            return s.content.generator.buildShip(s, spec(s, name), sector, Difficulty.NORMAL, seed)
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** `shipSweepDump("checks")`: throws when a ship differs from [oracleSystems] or [oracleLists] (only with the mod loaded). */
    private fun oracleChecks(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "PASS oracle checks skipped: no Hyperspace"
        val known = main.gameContent.blueprintManager.blueprints
        // the rows are Multiverse 5.5.1 recordings (its hidden FOR_MULTIVERSE augment marks the mod, as in the C4 checks)
        if (known["FOR_MULTIVERSE"] == null) return "PASS oracle checks skipped: no Multiverse"
        val bad = ArrayList<String>()
        for (c in oracleSystems) {
            val name = c[0] as String; val sys = c[3] as String; val room = c[4] as Int; val level = c[5] as Int; val power = c[6] as Int
            if (known[name] == null) { bad += "$name: no blueprint"; continue }
            val ship = build(main, container, name, c[1] as Int, c[2] as Int)
            val got = ship.systems.firstOrNull { it.codename == sys && it.room?.id == room }
            val gotPower = (got as? MainSystem)?.powerSelected
            if (got == null || got.energyLevels != level || gotPower != power)
                bad += "$name $sys room $room: level ${got?.energyLevels} power $gotPower, the original $level/$power"
        }
        for (c in oracleLists) {
            val name = c[0] as String; val what = c[3] as String; val want = c[4] as String
            if (known[name] == null) { bad += "$name: no blueprint"; continue }
            val ship = build(main, container, name, c[1] as Int, c[2] as Int)
            val got = when (what) {
                "weapons" -> ship.hardpoints.mapNotNull { it.weapon?.type?.name }.joinToString(",")
                "crewRooms" -> ship.crew.filter { it is LivingCrew }.joinToString(",") { it.room.id.toString() }
                "crewMale" -> ship.crew.filterIsInstance<LivingCrew>().joinToString("") { if (it.info.male) "M" else "F" }
                "superShield" -> "${ship.superShield}/${ship.maxSuperShield}"
                else -> (ship.drones?.drones ?: emptyList()).filterNotNull().joinToString(",") { it.type.name }
            }
            if (got != want) bad += "$name $what $got, the original $want"
        }
        check(bad.isEmpty()) { "ships differ from the original: " + bad.joinToString("; ") }
        return "PASS ${oracleSystems.size} systems and ${oracleLists.size} lists as the original"
    }
}
