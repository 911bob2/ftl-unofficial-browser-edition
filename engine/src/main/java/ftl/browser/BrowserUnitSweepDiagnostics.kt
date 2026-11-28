package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.FireDamage
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.game.StoreWindow
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.HsCrewContent
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.hs.systems.HsSystemInstall
import xyz.znix.xftl.hs.systems.HsSystems
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.random.Random

/**
 * Unit sweep (r27): every crew race and every drone blueprint of the loaded data (vanilla, Hyperspace or Multiverse)
 * spawned and put through what a race or drone can meet, with a save and a load of the run after every step, failing on
 * any exception (frame, drawing, save, load), a stuck state or an invalid value. Diagnostics only: nothing here changes
 * game logic. `unitSweepProbe(i)` runs case i (web/diagnostics.mjs `unit-sweep`, one record per case).
 *
 * Crew case ([RACES_PER_CASE] races): each race on the player's ship, on the enemy's ship and as an intruder on the
 * player's; its animation strips (every frame of walk, man, repair, punch, shoot, fire, teleport, death, portrait) cut
 * from their sheets, its store tile and info box and its crew box drawn; then walk, man a system, repair, fire, breach,
 * suffocation, a fight with the intruders, a teleport to the enemy and back (and the teleporter's send), mind control
 * both ways, a transform to the next race and back (Hyperspace), death with the clone bay, death without, dismissal.
 *
 * Drone case (up to [dronesPerCase] blueprints): each drone in the player's drone slots and in the enemy's; deploy and
 * power against the other ship, an ion hit on the drone system, power back, destroyed, depowered, a jump away with the
 * drones out, a new enemy at the next beacon; its store tile and info box drawn.
 *
 * Invalid values: NaN or infinite health, max health or position, negative health, a crew member whose race is no
 * crew blueprint (nor a Hyperspace race), an empty crew name, a drone whose weapon blueprint is unknown, a strip frame
 * outside its sheet. Stuck: a crew member dying, teleporting or being cloned for more than [STUCK_S] game seconds.
 * Notes (not failures, listed per item for the coverage table): a strip the race lacks, a sheet image that is missing,
 * the actions each race took in each step.
 */
object BrowserUnitSweepDiagnostics {
    private const val DT = 1f / 32f
    private const val RACES_PER_CASE = 7
    private const val STUCK_S = 16f
    private const val SEED0 = 27_000

    private class Items(val races: List<String>, val drones: List<String>, val perDrone: Int) {
        val crewCases = (races.size + RACES_PER_CASE - 1) / RACES_PER_CASE
        val droneCases = (drones.size + perDrone - 1) / perDrone
    }

    private var cached: Pair<Int, Items>? = null

    private fun items(main: MainGame): Items {
        val bm = main.gameContent.blueprintManager
        val key = bm.blueprints.size * 31 + (if (HS.active) 1 else 0)
        cached?.let { if (it.first == key) return it.second }
        val races = java.util.TreeSet<String>()
        for (b in bm.blueprints.values) if (b is CrewBlueprint) races += b.name
        if (HS.active) races += CustomCrewManager.races
        val drones = bm.blueprints.values.filterIsInstance<DroneBlueprint>().map { it.name }.distinct().sorted()
        return Items(races.toList(), drones, if (HS.active) 4 else 3).also { cached = key to it }
    }

    fun count(main: MainGame): Int = items(main).let { it.crewCases + it.droneCases }

    fun name(main: MainGame, i: Int): String {
        val it = items(main)
        return if (i < it.crewCases) "crew ${i + 1}/${it.crewCases} " + raceSlice(it, i).joinToString(",")
        else "drones ${i - it.crewCases + 1}/${it.droneCases} " + droneSlice(it, i - it.crewCases).joinToString(",")
    }

    private fun raceSlice(it: Items, i: Int) = it.races.subList(i * RACES_PER_CASE, minOf(it.races.size, (i + 1) * RACES_PER_CASE))
    private fun droneSlice(it: Items, i: Int) = it.drones.subList(i * it.perDrone, minOf(it.drones.size, (i + 1) * it.perDrone))

    private class Failure(message: String, cause: Throwable?) : RuntimeException(message, cause)

    private class Ctx(val main: MainGame, val container: GameContainer, val graphics: Graphics, val rnd: Random) {
        lateinit var state: InGameState
        var phase = "setup"
        var frame = 0
        var frames = 0
        var trips = 0
        var ship = ""
        var enemySpec = ""
        val keepers = ArrayList<AbstractCrew>()
        /** Per item: what it did, by step (an action set or a flag). */
        val notes = LinkedHashMap<String, LinkedHashSet<String>>()
        val stuck = HashMap<AbstractCrew, Pair<AbstractCrew.Action, Float>>()
        val luaErrors = LinkedHashSet<String>()
        fun note(item: String, what: String) { notes.getOrPut(item) { LinkedHashSet() } += what }
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, index: Int): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val seed = SEED0 + index
        val c = Ctx(main, container, graphics, Random(seed))
        val its = items(main)
        try {
            BrowserRandom.restore(seed.toString())
            NativeRandom.seed(seed)
            val luaBefore = if (HS.luaActive) xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() else emptySet()
            try {
                if (index < its.crewCases) crewCase(c, raceSlice(its, index)) else droneCase(c, droneSlice(its, index - its.crewCases))
            } finally {
                if (HS.luaActive) c.luaErrors += xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() - luaBefore
            }
            if (c.luaErrors.isNotEmpty()) throw Failure("an engine error inside a Lua call (${c.luaErrors.joinToString(" | ").take(600)})", null)
            return "PASS unit sweep ${name(main, index)}: ${c.ship} vs ${c.enemySpec}, ${c.frames} frames, ${c.trips} save/loads; " +
                c.notes.entries.joinToString("; ") { (k, v) -> k + " " + v.joinToString(",") }
        } catch (e: Throwable) {
            try { BrowserGameplayParityDiagnostics.fresh(main, container) } catch (ignored: Throwable) {}
            var root: Throwable = (e as? Failure)?.cause ?: e
            while (root is xyz.znix.xftl.savegame.RunLoadException && root.cause != null) root = root.cause!!
            throw IllegalStateException(
                "unit sweep ${name(main, index)} (${c.ship} vs ${c.enemySpec}) ${(e as? Failure)?.message ?: "in ${c.phase} frame ${c.frame}"}" +
                    (if (root !== e || e !is Failure) ": ${root::class.simpleName}: ${root.message} @ ${BrowserJsStack.of(root).lines().take(22).joinToString(" < ") { it.trim() }}" else "")
            )
        } finally {
            main.currentState.let { if (it is InGameState) { it.debugFlags.noDmg.set = false; it.debugFlags.noCrewDamage.set = false } }
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    // ---- Frames, saves, checks -------------------------------------------------------------------------------------

    private inline fun guard(c: Ctx, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Failure) {
            throw e
        } catch (e: Throwable) {
            throw Failure("$what in ${c.phase} frame ${c.frame}", e)
        }
    }

    private fun s(c: Ctx): InGameState = c.state

    /** [n] frames as the page runs them (every third drawn), [each] after each; stops early when [until] holds. */
    private fun frames(c: Ctx, n: Int, until: () -> Boolean = { false }, each: () -> Unit = {}) {
        for (f in 0 until n) {
            c.frame = f
            guard(c, "the frame") { BrowserEngine.step(DT) }
            c.frames++
            val st = c.main.currentState as? InGameState ?: throw Failure("the run left the game in ${c.phase} frame $f", null)
            c.state = st
            if (st.browserRunEnded) throw Failure("the run ended (game over) in ${c.phase} frame $f: hull ${st.player.health}, own crew ${st.player.crew.count { it.mode != AbstractCrew.SlotType.INTRUDER }}", null)
            if (f % 3 == 0) guard(c, "drawing") { st.setRenderingDeltaTime(DT * 3); BrowserEngine.render() }
            // Event boxes the step brought (the enemy's crew dead, a surrender) are answered, as a player would.
            if (st.shipUI.diagnosticDialogue != null) guard(c, "closing a box") { BrowserDiagnostics.closeBoxes(c.main, c.container) }
            // Over the crew limit (a power's or a death effect's spawn on the player's side) the original holds the crew
            // manifest open as well (ShipManager::IsCrewOverFull): the spawned crew are dismissed, as a player would.
            if (st.playerHasTooManyCrew()) {
                for (m in st.playerCrew.filter { !it.info.name.startsWith(PROBE) && !it.info.name.startsWith(KEEP) }.asReversed()) {
                    if (!st.playerHasTooManyCrew()) break
                    c.note(m.codename, "dismissed-over-limit")
                    st.player.clonebay?.queue?.remove(m)
                    if (m in m.room.ship.crew) m.removeFromShip()
                    st.updatePlayerCrew()
                }
                if (!st.playerHasTooManyCrew() && st.shipUI.isWindowOpen && st.shipUI.diagnosticDialogue == null) st.shipUI.diagnosticCloseWindow()
            }
            if (st.isPaused && !st.shipUI.isWindowOpen && st.shipUI.diagnosticDialogue == null) st.toggleQuickPause()
            if (st.isPaused && st.shipUI.isWindowOpen && st.shipUI.diagnosticDialogue == null && f > 0 && f % 64 == 0)
                throw Failure("the game stayed paused under a window in ${c.phase} frame $f: ${st.shipUI.diagnosticWindows}; the player's crew " +
                    st.player.crew.joinToString(",") { "${it.codename}/${it.mode}" + if ((it as? LivingCrew)?.ownerShip !== st.player) "(foreign)" else "" }, null)
            for (k in c.keepers) if (k.health > 0f) k.health = k.maxHealth
            // The player's hull stays whole (the drone cases fight with every other system off): a case ends by its own steps.
            if (st.player.health < st.player.maxHealth) st.player.health = st.player.maxHealth
            guard(c, "the step's own action") { each() }
            if (f % 8 == 0) check(c)
            stuck(c, DT)
            if (until()) break
        }
        check(c)
    }

    /** Save as the autosave does and load; the case goes on with the loaded game (every object reference re-found). */
    private fun trip(c: Ctx) {
        val st = c.state
        c.trips++
        check(c)
        guard(c, "saving") { c.main.writeRunSave() }
        val xml = BrowserFiles.getRun() ?: throw Failure("no run save after saving in ${c.phase}", null)
        guard(c, "loading") { c.main.loadSavedGame(BrowserXml.parse(xml)) }
        val loaded = c.main.currentState as? InGameState ?: throw Failure("the load left the run in ${c.phase}", null)
        if (loaded.loadDroppedRefs.isNotEmpty() || loaded.saveMigrationLines.isNotEmpty())
            throw Failure("a save of this build needed repairs in ${c.phase}: ${loaded.loadDroppedRefs} ${loaded.saveMigrationLines}", null)
        loaded.debugFlags.noEnemyFire.set = st.debugFlags.noEnemyFire.set
        if (loaded.isPaused && !loaded.shipUI.isWindowOpen) loaded.toggleQuickPause()
        c.state = loaded
        // The keepers are the loaded game's own objects now: the player's crew that is neither a probe nor an intruder.
        c.keepers.clear()
        c.keepers += loaded.player.crew.filter { it is LivingCrew && it.ownerShip === loaded.player && (it as LivingCrew).info.name.startsWith(KEEP) }
        c.stuck.clear()
        check(c)
    }

    private const val KEEP = "Keeper "
    private const val PROBE = "U27 "

    private fun bad(v: Float) = v.isNaN() || v.isInfinite()

    /** The invalid values: on any crew member, drone and external drone of both ships. */
    private fun check(c: Ctx) {
        val st = c.state
        val bm = st.blueprintManager
        for (ship in listOfNotNull(st.player, st.enemy)) {
            for (m in ship.crew) {
                val who = "${m.codename} on ${if (ship === st.player) "the player" else "the enemy"}"
                if (bad(m.health)) throw Failure("$who has health ${m.health} in ${c.phase} frame ${c.frame}", null)
                if (m.health < 0f) throw Failure("$who has negative health ${m.health} in ${c.phase} frame ${c.frame}", null)
                if (bad(m.maxHealth) || m.maxHealth <= 0f) throw Failure("$who has max health ${m.maxHealth} in ${c.phase} frame ${c.frame}", null)
                if (bad(m.hsCentreX) || bad(m.hsCentreY)) throw Failure("$who stands at ${m.hsCentreX},${m.hsCentreY} in ${c.phase} frame ${c.frame}", null)
                if (m.room.ship !== ship) throw Failure("$who is listed on a ship whose rooms it is not in, in ${c.phase} frame ${c.frame}", null)
                // A walk goal lies on the ship the crew member stands on (its save resolves the goal's room there).
                m.pathingTarget?.let { t ->
                    if (t.room.ship !== m.room.ship) throw Failure("$who walks to room ${t.room.id} of the other ship in ${c.phase} frame ${c.frame} " +
                        "(action ${m.currentAction}, hs teleport ${m.hsTeleporting})", null)
                }
                if (m is LivingCrew) {
                    if (bm.getOrNull(m.codename) !is CrewBlueprint && !(HS.active && CustomCrewManager.isRace(m.codename)))
                        throw Failure("$who: race ${m.codename} is no crew blueprint in ${c.phase} frame ${c.frame}", null)
                    if (m.info.name.isBlank()) throw Failure("$who has an empty name in ${c.phase} frame ${c.frame}", null)
                }
            }
            ship.drones?.drones?.forEach { info ->
                val d = info?.instance ?: return@forEach
                info.type.weaponBlueprintName?.let { w -> if (bm.getOrNull(w) == null) throw Failure("drone ${info.type.name}: weapon blueprint $w unknown", null) }
                if (d is AbstractIndoorsDrone) d.pawn?.let { p -> if (bad(p.health) || p.health < 0f) throw Failure("drone ${info.type.name}'s body has health ${p.health} in ${c.phase} frame ${c.frame}", null) }
            }
            for (x in ship.externalDrones) {
                val p = x.flightController.position
                if (bad(p.x.toFloat()) || bad(p.y.toFloat())) throw Failure("drone ${x.type.name} flies at ${p.x},${p.y} in ${c.phase} frame ${c.frame}", null)
            }
        }
        // The crew limit counts each of the player's crew members' CREW_SLOTS and leaves out the NO_SLOT ones (CustomCrew.cpp
        // CrewMemberFactory::OnLoop crewCapacityUsed), so a 0.5-slot race takes half a slot and a noSlot hologram none.
        if (HS.active) {
            val pl = st.player
            var want = 0f
            for (m in st.playerCrew) {
                if (m.ownerShip !== pl || (m.health <= 0f && pl.clonebay?.queue?.contains(m) != true)) continue
                val ex = xyz.znix.xftl.hs.crew.HsCrew.ex(m)
                val def = CustomCrewManager.definitionOrNull(m.hsSpecies)
                if (def == null) { if (!ex.noSlot) want += 1f; continue }
                if (!ex.calculateBool(xyz.znix.xftl.hs.stat.CrewStat.NO_SLOT, def)) want += ex.calculateStat(xyz.znix.xftl.hs.stat.CrewStat.CREW_SLOTS, def)
            }
            val got = xyz.znix.xftl.hs.ship.HsShipState.capacityUsed(pl, st.playerCrew.size)
            if (kotlin.math.abs(got - want) > 0.001f)
                throw Failure("the crew limit counts $got slots for crew taking $want (${st.playerCrew.joinToString(",") { it.codename }}) in ${c.phase} frame ${c.frame}", null)
        }
    }

    /** A crew member dying, teleporting or being cloned for longer than any of these lasts. */
    private fun stuck(c: Ctx, dt: Float) {
        val st = c.state
        val seen = HashSet<AbstractCrew>()
        for (ship in listOfNotNull(st.player, st.enemy)) for (m in ship.crew) {
            seen += m
            val a = m.currentAction
            if (a != AbstractCrew.Action.DYING && a != AbstractCrew.Action.TELEPORTING && a != AbstractCrew.Action.CLONING) { c.stuck.remove(m); continue }
            val was = c.stuck[m]
            val t = if (was != null && was.first == a) was.second + dt else dt
            if (t > STUCK_S) throw Failure("${m.codename} stuck in $a for ${STUCK_S.toInt()} s in ${c.phase}", null)
            c.stuck[m] = a to t
        }
        c.stuck.keys.retainAll(seen)
    }

    // ---- Setup ------------------------------------------------------------------------------------------------------

    private val SYSTEM_IDS: List<Int> get() = (0 until 16) + (if (HS.active) listOf(HsSystems.SYS_TEMPORAL) else emptyList())

    /** HsConsole's SYS: the room the layout has for the system while nothing else is there (Hyperspace: AddSystem). */
    private fun addSystem(ship: Ship, id: Int) {
        val name = HsSystems.idToName(id)
        if (name.isEmpty() || ship.systems.any { it.codename == name }) return
        if (!HS.active) {
            val slot = ship.systemSlots.firstOrNull { it.system.name == name && it.room.system == null } ?: return
            slot.room.setSystem(slot)
            slot.room.system?.let { ship.browserPowerNewSystem(it) }
            return
        }
        if (HsSystemInstall.willReplace(ship, name) != null) return
        xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, id)
    }

    /**
     * The clone phase needs a clone bay: one replaces the start ship's medbay as a bought one does (Hyperspace: the
     * AddSystem hook; vanilla: the medbay goes and the clone bay takes its slot).
     */
    internal fun cloneBay(ship: Ship) {
        if (ship.clonebay != null) return
        val slot = ship.systemSlots.firstOrNull { it.system.name == "clonebay" } ?: return
        if (HS.active) HsSystemInstall.install(ship, slot)
        else {
            ship.medbay?.room?.let { r -> r.hsClearSystem(); ship.updateAvailableSystems(); ship.updateAvailablePower() }
            if (slot.room.system == null) slot.room.setSystem(slot)
        }
        slot.room.system?.let { ship.browserPowerNewSystem(it) }
    }

    internal fun powerAll(ship: Ship) {
        ship.updateAvailablePower()
        for (system in ship.systems) if (system is MainSystem) repeat(system.energyLevels) { system.increasePower() }
        ship.update(0f)
    }

    /** The player ship of the case: the one with the most drone slots of those the mode has, every system it fits. */
    private fun start(c: Ctx) {
        val bm = c.main.gameContent.blueprintManager
        c.ship = listOf("PLAYER_SHIP_ENGI_3", "PLAYER_SHIP_CIRCLE", "PLAYER_SHIP_HARD").first { bm.getOrNull(it) != null }
        c.state = BrowserGameplayParityDiagnostics.fresh(c.main, c.container, c.ship)
        val p = s(c).player
        cloneBay(p)
        for (id in SYSTEM_IDS) addSystem(p, id)
        HsConsole.run(s(c), "GOD")
        p.dronesCount = 99
        p.missilesCount = 20
        p.fuelCount = 20
        // The ship's own crew keeps it crewed (never a probe, healed every frame): renamed so a loaded game finds them.
        // One keeper: with the case's races the crew stays at the crew limit (8; over it the game holds a dismissal
        // window open, PlayerShipUI's too-many-crew check, and nothing moves).
        for ((i, m) in p.crew.filterIsInstance<LivingCrew>().withIndex()) {
            if (i == 0) { m.info.name = KEEP + m.info.name; c.keepers += m } else m.removeFromShip()
        }
        powerAll(p)
    }

    private fun spawnEnemy(c: Ctx, seed: Int) {
        val st = s(c)
        val specs = st.eventManager.getShips().filter { it.name.isNotEmpty() }.sortedBy { it.name }
        for (attempt in 0 until 16) {
            val spec = specs[(seed * 131 + attempt * 17) % specs.size]
            c.enemySpec = spec.name
            guard(c, "spawning the enemy ${spec.name}") { st.debugSpawnShip(spec, Difficulty.NORMAL, 3, seed + attempt) }
            val e = st.enemy ?: continue
            if (e.rooms.size < 6) continue
            for (id in SYSTEM_IDS) addSystem(e, id)
            e.purchasedReactorPower = 30
            e.dronesCount = 40
            powerAll(e)
            return
        }
        throw Failure("no enemy ship built in 16 picks", null)
    }

    // ---- Per item: strips, tiles ------------------------------------------------------------------------------------

    private val DIRS = listOf("up", "down", "left", "right")

    /** Every frame of every strip a crew member of [m]'s race can play, cut from its sheet; missing ones noted. */
    private fun strips(c: Ctx, item: String, m: AbstractCrew) {
        val st = s(c)
        val anims = st.animations
        val race = m.hsAnimationRaceName
        val names = DIRS.map { "${race}_walk_$it" } + DIRS.map { "${race}_type_$it" } + DIRS.map { "${race}_shoot_$it" } +
            DIRS.map { "${race}_fire_$it" } + listOf("${race}_repair", "${race}_punch_up", "${race}_punch_down", "${race}_teleport",
            m.hsDeathAnimationName, "${race}_portrait")
        val missing = ArrayList<String>()
        for (n in names) {
            val spec = anims.getOrNull(n)
            if (spec == null) { missing += n.removePrefix(race + "_"); continue }
            if (st.getImgIfExists(spec.sheet.sheetPath) == null) c.note(item, "sheet-missing:" + spec.sheet.sheetPath)
            for (i in 0 until spec.length) guard(c, "cutting frame $i of $n") { spec.spriteAt(st, i) }
        }
        if (missing.isNotEmpty()) c.note(item, "no-strip:" + missing.joinToString("/"))
        if (race != m.codename) c.note(item, "anim:$race")
    }

    /** The race's store tile and its info box (StoreWindow, as the store and its hover draw them). */
    private fun storeTiles(c: Ctx, crew: List<LivingCrewInfo>, drones: List<DroneBlueprint>) {
        val st = s(c)
        for (part in crew.chunked(3).map { it to emptyList<DroneBlueprint>() } + drones.chunked(3).map { emptyList<LivingCrewInfo>() to it }) {
            val data = StoreData()
            if (part.first.isNotEmpty()) { data.sections += StoreData.Section.CREW; data.crew += part.first }
            if (part.second.isNotEmpty()) { data.sections += StoreData.Section.DRONES; data.drones += part.second }
            guard(c, "drawing a store of ${part.first.map { it.race.name } + part.second.map { it.name }}") {
                val window = StoreWindow(st, st.player, data) {}
                window.draw(c.graphics)
                for (info in part.first) window.diagnosticInfo(c.graphics, info, false)
                for (d in part.second) window.diagnosticInfo(c.graphics, d, false)
            }
        }
    }

    // ---- Crew case --------------------------------------------------------------------------------------------------

    private class Probe(val race: String) {
        var own: AbstractCrew? = null
        var enemyOwn: AbstractCrew? = null
        var intruder: AbstractCrew? = null
        val acts = HashMap<String, LinkedHashSet<AbstractCrew.Action>>()
    }

    private fun spawnRace(c: Ctx, ship: Ship, race: String, intruder: Boolean, room: Room?): AbstractCrew? {
        val st = s(c)
        val m: AbstractCrew = if (HS.active) {
            HsCrew.addCrewMemberFromString(ship, PROBE + race, race, intruder, room?.id ?: -1, false, c.rnd.nextBoolean())
        } else {
            val bp = st.blueprintManager.getOrNull(race) as? CrewBlueprint ?: return null
            val info = LivingCrewInfo.generateRandom(bp, st).also { it.name = PROBE + race }
            if (intruder) ship.addCrewMember(info, false, true).also { it.jumpTo(ship.findSpaceForCrew(room, AbstractCrew.SlotType.INTRUDER)) }
            else if (room != null) ship.addCrewMemberInRoom(info, room, false) else ship.addCrewMember(info, false)
        }
        return m
    }

    /** The probes as the loaded game holds them (by name and side), after a save and load. */
    private fun refind(c: Ctx, probes: List<Probe>) {
        val st = s(c)
        val all = (st.player.crew + (st.enemy?.crew ?: emptyList())).filterIsInstance<LivingCrew>()
        for (p in probes) {
            val mine = all.filter { it.info.name == PROBE + p.race }
            p.own = mine.firstOrNull { it.ownerShip === st.player && it.mode != AbstractCrew.SlotType.INTRUDER && it.room.ship === st.player }
                ?: mine.firstOrNull { it.ownerShip === st.player }
            p.enemyOwn = mine.firstOrNull { st.enemy != null && it.ownerShip === st.enemy && it.room.ship === st.enemy }
            p.intruder = mine.firstOrNull { st.enemy != null && it.ownerShip === st.enemy && it.room.ship === st.player }
        }
    }

    private fun alive(m: AbstractCrew?): AbstractCrew? = m?.takeIf { it.health > 0f && it in it.room.ship.crew }

    private fun track(c: Ctx, probes: List<Probe>, step: String) {
        for (p in probes) for (m in listOfNotNull(p.own, p.enemyOwn, p.intruder)) if (alive(m) != null)
            p.acts.getOrPut(step) { LinkedHashSet() } += m.currentAction
    }

    private fun summary(c: Ctx, probes: List<Probe>) {
        for (p in probes) for ((step, acts) in p.acts) {
            val short = acts.filter { it != AbstractCrew.Action.IDLE && it != AbstractCrew.Action.MOVING }.joinToString("+") { it.name.lowercase() }
            if (short.isNotEmpty()) c.note(p.race, "$step=$short")
        }
    }

    private fun crewCase(c: Ctx, races: List<String>) {
        c.phase = "setup"
        start(c)
        spawnEnemy(c, SEED0 + races.hashCode().and(0xffff))
        c.state.debugFlags.noEnemyFire.set = true
        val probes = races.map { Probe(it) }

        c.phase = "spawn"
        val st0 = s(c)
        val playerRooms = st0.player.rooms
        val enemyRooms = st0.enemy!!.rooms
        for ((i, p) in probes.withIndex()) {
            guard(c, "spawning ${p.race}") {
                p.own = spawnRace(c, st0.player, p.race, false, playerRooms[i % playerRooms.size])
                p.enemyOwn = spawnRace(c, st0.enemy!!, p.race, false, enemyRooms[i % enemyRooms.size])
            }
            if (p.own == null) { c.note(p.race, "unspawnable"); continue }
            c.note(p.race, "spawned")
            strips(c, p.race, p.own!!)
            if (p.own!!.codename != p.race) c.note(p.race, "became:" + p.own!!.codename)
        }
        guard(c, "the crew's tiles") {
            // A race with no title is one the engine makes for its own use (the ion boarder's body): no store sells it.
            for (p in probes) if ((p.own as? LivingCrew)?.info?.race?.title == null) c.note(p.race, "no-title")
            storeTiles(c, probes.mapNotNull { (it.own as? LivingCrew)?.info }.filter { it.race.title != null }, emptyList())
            // The crew boxes with each probe selected (the selection outline, the box's portrait and bars).
            val sel = s(c).shipUI.touchSelected
            for (p in probes) { val m = p.own ?: continue; sel.clear(); sel += m; s(c).setRenderingDeltaTime(DT); BrowserEngine.render() }
            sel.clear()
        }
        frames(c, 8)
        trip(c); refind(c, probes)

        // Walk: each to another room of its own ship (the player's orders, the enemy's crew by the same call).
        c.phase = "walk"
        for ((i, p) in probes.withIndex()) {
            alive(p.own)?.let { m -> m.room.ship.rooms.let { r -> m.setTargetRoom(r[(i * 3 + 2) % r.size]) } }
            alive(p.enemyOwn)?.let { m -> m.room.ship.rooms.let { r -> m.setTargetRoom(r[(i * 5 + 1) % r.size]) } }
        }
        // Until the player's probes stand (the enemy's own crew walk on under its AI), at most 15 s of game time.
        frames(c, 480, until = { probes.all { p -> alive(p.own)?.currentAction != AbstractCrew.Action.MOVING } }) { track(c, probes, "walk") }
        for (p in probes) alive(p.own)?.let { m -> if (m.currentAction == AbstractCrew.Action.MOVING)
            c.note(p.race, "still-walking:room${m.room.id}@${m.hsCentreX.toInt()}/${m.hsCentreY.toInt()}->${m.nextTargetPos?.let { "${it.x}/${it.y}" }}") }
        trip(c); refind(c, probes)

        // Man: one race per system room of the player's ship (the console's slot where it has one).
        c.phase = "man"
        val sysRooms = s(c).player.rooms.filter { it.system != null }
        for ((i, p) in probes.withIndex()) alive(p.own)?.let { m -> sysRooms.getOrNull(i % maxOf(1, sysRooms.size))?.let { m.setTargetRoom(it) } }
        frames(c, 160) { track(c, probes, "man") }
        trip(c); refind(c, probes)

        // Repair: every system of the player's damaged where the probes stand.
        c.phase = "repair"
        for (sys in s(c).player.systems) guard(c, "damaging ${sys.codename}") { sys.dealDamage(2, 0) }
        frames(c, 200) { track(c, probes, "repair") }
        trip(c); refind(c, probes)

        // Fire, then a breach, in every room a probe stands in.
        for (what in listOf("fire", "breach")) {
            c.phase = what
            for (p in probes) alive(p.own)?.let { m -> if (what == "fire") m.room.spawnFire() else m.room.spawnBreach() }
            frames(c, 160) { track(c, probes, what) }
            trip(c); refind(c, probes)
        }

        // Suffocation: the probes' rooms without air for four seconds.
        c.phase = "suffocate"
        val before = probes.associateWith { alive(it.own)?.health ?: 0f }
        frames(c, 128) {
            for (p in probes) alive(p.own)?.let { it.room.oxygen = 0f }
            track(c, probes, "suffocate")
        }
        for (p in probes) alive(p.own)?.let { if (it.health < (before[p] ?: 0f) - 0.5f) c.note(p.race, "suffocates") }
        for (room in s(c).player.rooms) room.oxygen = 1f
        trip(c); refind(c, probes)

        // Fight: every race as an intruder on the player's ship, in the next probe's room.
        c.phase = "fight"
        for ((i, p) in probes.withIndex()) guard(c, "boarding ${p.race}") {
            val room = alive(probes[(i + 1) % probes.size].own)?.room ?: s(c).player.rooms[i % s(c).player.rooms.size]
            p.intruder = spawnRace(c, s(c).player, p.race, true, room)
        }
        frames(c, 220) {
            // Nobody dies of the fight yet: the death steps come later.
            for (p in probes) for (m in listOfNotNull(alive(p.own), alive(p.intruder))) if (m.health < m.maxHealth / 4) m.health = m.maxHealth
            track(c, probes, "fight")
        }
        trip(c); refind(c, probes)

        // Teleport: each probe of the player's to the enemy and back (the teleport's own animation), then the
        // teleporter's send from its room.
        c.phase = "teleport"
        val enemy = s(c).enemy
        if (enemy != null) {
            for ((i, p) in probes.withIndex()) alive(p.own)?.let { m -> guard(c, "teleporting ${p.race}") { m.teleportAnimatedTo(enemy.rooms[i % enemy.rooms.size]) } }
            frames(c, 120) { track(c, probes, "teleport") }
            for (p in probes) alive(p.own)?.let { if (it.room.ship === s(c).enemy) c.note(p.race, "teleported") }
            trip(c); refind(c, probes)
            val home = s(c).player
            for ((i, p) in probes.withIndex()) alive(p.own)?.let { m -> if (m.room.ship !== home) guard(c, "teleporting ${p.race} back") { m.teleportAnimatedTo(home.rooms[i % home.rooms.size]) } }
            frames(c, 120) { track(c, probes, "teleport") }
            s(c).player.teleporter?.let { t ->
                t.room?.let { tr -> for (p in probes.take(2)) alive(p.own)?.setTargetRoom(tr) }
                frames(c, 90)
                s(c).enemy?.let { e -> guard(c, "the teleporter's send") { t.selectTeleportAction(true, e.rooms[0]) } }
                frames(c, 90)
            }
            trip(c); refind(c, probes)
        }

        // Mind control: the player's at the enemy's room with the most probes, the enemy's at the player's.
        c.phase = "mind"
        s(c).enemy?.let { e ->
            e.rooms.maxByOrNull { r -> r.crew.size }?.let { r -> s(c).player.mindControl?.selectRoom(r) }
            if (e.mindControl == null) addSystem(e, 14)
            powerAll(e)
            s(c).player.rooms.maxByOrNull { r -> r.crew.count { it in probes.mapNotNull { p -> p.own } } }?.let { r -> e.mindControl?.selectRoom(r) }
        }
        frames(c, 160) { track(c, probes, "mind") }
        trip(c); refind(c, probes)

        // Transform (Hyperspace): each probe of the player's to the next race and back.
        if (HS.active) {
            c.phase = "transform"
            val bm = s(c).blueprintManager
            for ((i, p) in probes.withIndex()) alive(p.own)?.let { m ->
                val to = probes[(i + 1) % probes.size].race
                guard(c, "transforming ${p.race} to $to") { HsCrewContent.eventTransformRace(bm, m, to, c.rnd) }
            }
            frames(c, 48)
            trip(c); refind(c, probes)
            for (p in probes) alive(p.own)?.let { m -> guard(c, "transforming back to ${p.race}") { HsCrewContent.eventTransformRace(s(c).blueprintManager, m, p.race, c.rnd) } }
            frames(c, 48)
            trip(c); refind(c, probes)
        }

        // Death with the clone bay: the probes of the player's; the clone bay brings back those it can.
        c.phase = "clone"
        // The bay working first: the repair, fire and breach steps leave systems damaged, and a clone bay without power
        // lets its queue die after 3 s (CloneSystem::OnLoop), which would test nothing of the race.
        val pl = s(c).player
        for (sys in pl.systems) sys.damagedEnergyLevels = 0
        for (room in pl.rooms) {
            room.fires.indices.forEach { room.fires[it] = null }
            room.breaches.indices.forEach { room.breaches[it] = null }
            room.oxygen = 1f
        }
        powerAll(pl)
        val deadBefore = probes.filter { alive(it.own) == null }.toSet()
        for (p in probes) alive(p.own)?.let { guard(c, "killing ${p.race}") { it.dealDamage(FireDamage(10_000f)) } }
        frames(c, 160) { track(c, probes, "clone") }
        trip(c); refind(c, probes)
        // The wait for each clone is cut to its last tenth (the clone's own completion still runs): seven probes
        // queued one after another would take over a minute of game time.
        frames(c, 600, until = { s(c).player.clonebay?.queue?.isEmpty() ?: true }) {
            track(c, probes, "clone")
            s(c).player.clonebay?.let { bay -> if (bay.queue.isNotEmpty() && bay.cloneProgress < 0.9f) bay.cloneProgress = 0.9f }
        }
        // The queued dead were in no ship's list at the load: found again now that the clone bay brought them back.
        refind(c, probes)
        if (s(c).player.clonebay == null) for (p in probes) c.note(p.race, "no-clonebay")
        for (p in probes) if (alive(p.own) != null) c.note(p.race, "cloned")
            else c.note(p.race, when {
                p in deadBefore -> "not-cloned:dead-before"
                HS.active && CustomCrewManager.definitionOrNull(p.race)?.noClone == true -> "not-cloned:noClone"
                else -> "not-cloned"
            })
        trip(c); refind(c, probes)

        // Death without: the enemy's probes and the intruders.
        c.phase = "die"
        for (p in probes) for (m in listOfNotNull(alive(p.enemyOwn), alive(p.intruder))) guard(c, "killing ${p.race}") { m.dealDamage(FireDamage(10_000f)) }
        frames(c, 160) { track(c, probes, "die") }
        trip(c); refind(c, probes)

        // Dismissal (the crew window's): the probes still aboard leave, out of the clone queue too.
        c.phase = "dismiss"
        val p0 = s(c).player
        for (p in probes) (p.own as? LivingCrew)?.let { m -> if (m in p0.crew) guard(c, "dismissing ${p.race}") { p0.clonebay?.queue?.remove(m); m.removeFromShip() } }
        frames(c, 16)
        trip(c)
        summary(c, probes)
    }

    // ---- Drone case -------------------------------------------------------------------------------------------------

    private fun droneCase(c: Ctx, names: List<String>) {
        c.phase = "setup"
        start(c)
        val bm = s(c).blueprintManager
        val types = names.mapNotNull { bm.getOrNull(it) as? DroneBlueprint }
        val p = s(c).player
        val d = p.drones ?: throw Failure("the player's ship ${c.ship} has no drone system", null)
        d.energyLevels = maxOf(d.energyLevels, 8)
        // The ship's own drones leave, so every slot is the case's.
        while (d.drones.any { it != null }) d.luaRemoveDrone(0)
        for (t in types) {
            if (t.type == DroneBlueprint.DroneType.HACKING) { c.note(t.name, "hacking-system's"); continue }
            if (d.drones.none { it == null }) { c.note(t.name, "no-slot"); continue }
            guard(c, "equipping ${t.name}") { d.luaAddDrone(t, -1) }
            c.note(t.name, "equipped")
        }
        for (t in types) {
            if (t.weaponBlueprintName != null && bm.getOrNull(t.weaponBlueprintName) == null) c.note(t.name, "weapon-unknown:" + t.weaponBlueprintName)
            t.droneImage?.let { img -> if (t.type.let { it == DroneBlueprint.DroneType.COMBAT || it == DroneBlueprint.DroneType.DEFENSE || it == DroneBlueprint.DroneType.SHIP_REPAIR || it == DroneBlueprint.DroneType.SHIELD }) {
                if (s(c).getImgIfExists("img/ship/drones/${img}_base.png") == null && s(c).getImgIfExists("img/ship/drones/$img.png") == null) c.note(t.name, "no-image:$img")
            } }
            if (t.translateTitle(s(c)).isBlank()) c.note(t.name, "no-title")
        }
        guard(c, "the drones' tiles") { storeTiles(c, emptyList(), types) }
        powerAll(p)
        spawnEnemy(c, SEED0 + names.hashCode().and(0xffff))
        // The enemy flies the same drones (its own drone system, full power), as an enemy built with them.
        s(c).enemy?.let { e ->
            if (e.drones == null) addSystem(e, 4)
            e.drones?.let { ed ->
                ed.energyLevels = maxOf(ed.energyLevels, 8)
                for ((slot, t) in types.filter { it.type != DroneBlueprint.DroneType.HACKING }.withIndex()) {
                    while (ed.drones.size <= slot) ed.drones.add(null)
                    if (ed.drones[slot] == null) ed.drones[slot] = Drones.DroneInfo(t)
                }
            }
            powerAll(e)
        }
        frames(c, 8)
        trip(c)

        fun powerOn() {
            val st = s(c)
            // The reactor's power to the drones: every other system off (GOD's levels would take it all).
            for (ship in listOfNotNull(st.player, st.enemy)) for (sys in ship.systems)
                if (sys is MainSystem && sys !is Drones && sys !is xyz.znix.xftl.systems.Oxygen) while (sys.powerSelected > 0) { val was = sys.powerSelected; sys.decreasePower(); if (sys.powerSelected == was) break }
            st.player.drones?.let { dd -> for (i in dd.drones.indices) if (dd.drones[i] != null) dd.setDronePower(i, true) }
            st.enemy?.drones?.let { dd -> for (i in dd.drones.indices) if (dd.drones[i] != null) dd.setDronePower(i, true) }
        }
        fun record(step: String) {
            s(c).player.drones?.drones?.forEach { info -> info ?: return@forEach
                c.note(info.type.name, step + "=" + (if (info.instance == null) "none" else if (info.instance!!.isPowered) "on" else "off"))
            }
        }

        c.phase = "deploy"
        powerOn()
        frames(c, 240)
        record("deploy")
        trip(c)

        c.phase = "ion"
        s(c).player.drones?.let { guard(c, "the ion hit") { it.dealDamage(0, 2) } }
        frames(c, 96)
        record("ion")
        trip(c)

        c.phase = "repower"
        frames(c, 260, until = { s(c).player.drones?.isIonised != true })
        powerOn()
        frames(c, 96)
        record("repower")
        trip(c)

        c.phase = "destroy"
        for (ship in listOfNotNull(s(c).player, s(c).enemy)) ship.drones?.drones?.forEach { info -> info?.instance?.let { guard(c, "destroying ${info.type.name}") { it.blowUp() } } }
        frames(c, 120)
        record("destroyed")
        trip(c)

        c.phase = "depower"
        // Rebuilt first (Drone::destroyedTimer, 10 s), so a deployed drone is what gets depowered.
        frames(c, 400, until = { s(c).player.drones?.drones?.all { it?.cooldown == null } ?: true })
        powerOn()
        frames(c, 48)
        s(c).player.drones?.let { dd -> for (i in dd.drones.indices) if (dd.drones[i]?.instance?.isPowered == true) dd.setDronePower(i, false) }
        frames(c, 48)
        record("depowered")
        trip(c)

        c.phase = "jump"
        powerOn()
        frames(c, 32)
        val st = s(c)
        st.player.fuelCount = maxOf(st.player.fuelCount, 3)
        BrowserDiagnostics.closeBoxes(c.main, c.container)
        st.currentBeacon.neighbours.firstOrNull()?.let { to -> guard(c, "jumping") { st.beginJumpOut(to) } }
        frames(c, 400, until = { !s(c).isPlayerJumping && !s(c).browserJumpPending() })
        record("jumped")
        trip(c)

        c.phase = "next beacon"
        if (s(c).enemy == null) spawnEnemy(c, SEED0 + 7 + names.hashCode().and(0xffff))
        powerOn()
        frames(c, 160)
        record("next")
        trip(c)
    }
}
