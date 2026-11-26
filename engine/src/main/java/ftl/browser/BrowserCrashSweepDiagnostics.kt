package ftl.browser

import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.FireDamage
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ResourceSet
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.hs.systems.HsSystemInstall
import xyz.znix.xftl.hs.systems.HsSystems
import xyz.znix.xftl.hs.systems.Temporal
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import kotlin.random.Random

/**
 * Crash sweep (r26): seeded fights driven through the transitions that end what another object points at, failing on
 * any exception. Two player crashes of release 2.2.4/2.2.5 were state that outlived the ship it pointed at (a drone body
 * of a destroyed ship, the temporal system's room on a destroyed ship), found only by players; this plays such runs on
 * its own, in vanilla, Hyperspace and Multiverse, with the same frame the page runs (BrowserEngine.step and render).
 *
 * A case: a player ship (the hangar's layouts; with Hyperspace also those that list the temporal system) gets every
 * system its layout has room for, full power and a set of weapons and drones; an enemy (any ship spec, seeded) gets the
 * systems it has room for, drones and power. Then a fight with the player's and the enemy's crew teleported across,
 * drones out, hacking, mind control, temporal, cloak and a random-action phase (power, targets, drones, crew moves and
 * kills, dismissals, fires, breaches, system damage, boarders, the pointer over and clicking the screen, the touch
 * layout's taps), and one ending: the enemy destroyed, its crew killed, its surrender, its escape, its instant escape,
 * the player jumping away, a sector change mid-fight, or the player's ship destroyed. After it the run goes on, leaves
 * the beacon and comes back. Every change of the coarse state ([signature]) saves (MainGame.writeRunSave, as the
 * autosave does) and loads the run, and the case goes on with the loaded game; a load that needed a repair (a save of
 * this build naming an object it did not write) fails too.
 *
 * Diagnostics only: nothing here changes game logic. `crashSweepProbe(i)` runs case i (web/diagnostics.mjs: one record
 * per case, a fresh engine after a failed one).
 */
object BrowserCrashSweepDiagnostics {
    private const val DT = 1f / 32f
    private const val SEED0 = 26_000
    private const val FIGHT_FRAMES = 1100
    private const val AFTER_FRAMES = 360
    private const val VISIT_FRAMES = 160
    private const val MAX_TRIPS = 45

    enum class Ending { DESTROYED, CREW_KILLED, SURRENDER, ESCAPE, INSTANT_ESCAPE, PLAYER_JUMPS, SECTOR_CHANGE, PLAYER_DESTROYED, FOUGHT_OUT }

    /** Cases per run: fewer on Multiverse, whose frames and loads cost several times vanilla's. */
    /** Five rounds of every ending in vanilla, three with Hyperspace (its cases run slower): about 10 min in all. */
    fun count(main: MainGame): Int = Ending.values().size * (if (!HS.active) 5 else 3)

    /** Every third case plays by touch, shifted each round so every ending meets both input routes. */
    fun name(i: Int): String = "seed ${SEED0 + i} ${Ending.values()[i % Ending.values().size].name.lowercase()}" +
            (if (touch(i)) " touch" else "")

    private fun touch(i: Int) = (i % Ending.values().size + i / Ending.values().size) % 3 == 2

    private class Failure(message: String, cause: Throwable?) : RuntimeException(message, cause)

    private class Ctx(val main: MainGame, val container: GameContainer, val graphics: Graphics, val rnd: Random, val ending: Ending) {
        lateinit var state: InGameState
        var phase = "setup"
        var frame = 0
        var trips = 0
        var lastSig = ""
        val actions = ArrayDeque<String>()
        var playerShip = ""
        var enemySpec = ""
        val notes = ArrayList<String>()
        val luaErrors = LinkedHashSet<String>()
        fun act(a: String) {
            actions.addLast("$phase/$frame:$a")
            while (actions.size > 14) actions.removeFirst()
        }
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, index: Int): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val touchWas = TouchMode.enabled
        val seed = SEED0 + index
        val ending = Ending.values()[index % Ending.values().size]
        val c = Ctx(main, container, graphics, Random(seed), ending)
        try {
            BrowserRandom.restore(seed.toString())
            NativeRandom.seed(seed)
            TouchMode.enabled = touch(index)
            val luaBefore = if (HS.luaActive) xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() else emptySet()
            try {
                play(c, index)
            } finally {
                if (HS.luaActive) c.luaErrors += xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() - luaBefore
            }
            if (c.luaErrors.isNotEmpty()) throw Failure("an engine error inside a Lua call (${c.luaErrors.joinToString(" | ").take(600)})", null)
            return "PASS crash sweep ${name(index)}: ${c.playerShip} vs ${c.enemySpec}, ${c.trips} save/loads, " +
                    "${c.frame} frames last phase ${c.phase}" + (if (c.notes.isEmpty()) "" else "; " + c.notes.joinToString(", "))
        } catch (e: Throwable) {
            // The failed run is replaced, so the page's own save when it stops the engine does not meet it again.
            try { BrowserGameplayParityDiagnostics.fresh(main, container) } catch (ignored: Throwable) {}
            // The load's wrapper carries no stack of its own worth reading: name the exception under it.
            var root: Throwable = (e as? Failure)?.cause ?: e
            while (root is xyz.znix.xftl.savegame.RunLoadException && root.cause != null) root = root.cause!!
            throw IllegalStateException(
                "crash sweep ${name(index)} (${c.playerShip} vs ${c.enemySpec}) ${(e as? Failure)?.message ?: "in ${c.phase} frame ${c.frame}"}: " +
                        "${root::class.simpleName}: ${root.message} @ ${BrowserJsStack.of(root).lines().take(22).joinToString(" < ") { it.trim() }} " +
                        "| last actions ${c.actions.joinToString(" ")}"
            )
        } finally {
            TouchMode.enabled = touchWas
            main.currentState.let { if (it is InGameState) { it.debugFlags.noDmg.set = false; it.debugFlags.noCrewDamage.set = false } }
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    // ---- The case ---------------------------------------------------------------------------------------------------

    private fun play(c: Ctx, index: Int) {
        val players = playerShips(c.main)
        c.playerShip = players.shuffled(Random(77))[index % players.size]
        c.state = BrowserGameplayParityDiagnostics.fresh(c.main, c.container, c.playerShip)
        equipPlayer(c)
        spawnEnemy(c, index)
        c.state.debugFlags.noEnemyFire.set = false
        c.lastSig = signature(c.state)

        c.phase = "fight"
        val endAt = 300 + c.rnd.nextInt(500)
        var ended = false
        for (f in 0 until FIGHT_FRAMES) {
            c.frame = f
            if (!inRun(c)) return
            if (!ended && f == endAt) { ended = true; end(c) }
            if (f < endAt && f % 90 == 10) setupAttacks(c)
            if (c.rnd.nextInt(100) < 22) randomAction(c)
            // The case's own ending decides when the player's ship goes.
            if (c.ending != Ending.PLAYER_DESTROYED && c.state.player.health < c.state.player.maxHealth / 3) c.state.player.health = c.state.player.maxHealth
            if (c.ending == Ending.CREW_KILLED && ended) killEnemyCrew(c)
            frame(c)
            if (c.state.enemy == null && ended && f > endAt + 200) break
        }
        if (!inRun(c)) return

        c.phase = "after"
        for (f in 0 until AFTER_FRAMES) {
            c.frame = f
            if (!inRun(c)) return
            if (c.rnd.nextInt(100) < 15) randomAction(c)
            if (c.state.player.health < c.state.player.maxHealth / 3) c.state.player.health = c.state.player.maxHealth
            frame(c)
        }
        // Leave the beacon and come back to it (the destroyed or escaped ship's beacon, revisited).
        val from = c.state.currentBeacon
        for ((label, target) in listOf("leave" to null, "return" to from)) {
            if (!inRun(c)) return
            c.phase = label
            val s = c.state
            if (s.browserJumpPending() || s.isPlayerJumping) continue
            val to = target?.takeIf { it.sector === s.currentBeacon.sector && it !== s.currentBeacon }
                ?: s.currentBeacon.neighbours.firstOrNull() ?: continue
            closeWindows(c)
            s.player.fuelCount = maxOf(s.player.fuelCount, 3)
            c.act("jump")
            s.beginJumpOut(to)
            for (f in 0 until VISIT_FRAMES) {
                c.frame = f
                if (!inRun(c)) return
                if (c.rnd.nextInt(100) < 12) randomAction(c)
                if (c.state.player.health < c.state.player.maxHealth / 3) c.state.player.health = c.state.player.maxHealth
                frame(c)
            }
        }
    }

    private fun inRun(c: Ctx): Boolean {
        val s = c.main.currentState as? InGameState ?: return false.also { c.notes += "left the run in ${c.phase}" }
        if (s.browserRunEnded) {
            // The game over: a few frames of its window, then the case is over.
            if (c.phase != "over") { c.phase = "over"; repeat(40) { c.frame = it; step(c) }; c.notes += "game over" }
            return false
        }
        c.state = s
        return true
    }

    /** One frame as the page runs it, a save and load on a change of the coarse state, the event boxes answered. */
    private fun frame(c: Ctx) {
        step(c)
        val s = c.main.currentState as? InGameState ?: return
        c.state = s
        if (s.browserRunEnded) return
        val dialogue = s.shipUI.diagnosticDialogue
        if (dialogue != null && c.rnd.nextInt(100) < 20) {
            val options = dialogue.diagnosticEnabledOptions
            val pick = options[c.rnd.nextInt(options.size)]
            c.act("option$pick")
            guard(c, "answering a box") { dialogue.selectOption(pick) }
        }
        if (s.isPaused && !s.shipUI.isWindowOpen && s.shipUI.diagnosticDialogue == null) s.toggleQuickPause()
        val sig = signature(s)
        if (sig != c.lastSig || c.frame % 240 == 239) {
            c.lastSig = sig
            if (c.trips < MAX_TRIPS) trip(c)
        }
    }

    private fun step(c: Ctx) {
        guard(c, "the frame") { BrowserEngine.step(DT) }
        if (c.frame % 3 == 0) guard(c, "drawing") {
            val s = c.main.currentState
            if (s is InGameState) s.setRenderingDeltaTime(DT * 3)
            BrowserEngine.render()
        }
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

    /** Save (the autosave's way) and load; the case goes on with the loaded game. */
    private fun trip(c: Ctx) {
        val s = c.state
        c.trips++
        c.act("save")
        guard(c, "saving") { c.main.writeRunSave() }
        val xml = BrowserFiles.getRun() ?: return
        c.act("load")
        guard(c, "loading") { c.main.loadSavedGame(BrowserXml.parse(xml)) }
        val loaded = c.main.currentState as? InGameState ?: throw Failure("the load left the run in ${c.phase} frame ${c.frame}", null)
        if (loaded.loadDroppedRefs.isNotEmpty() || loaded.saveMigrationLines.isNotEmpty())
            throw Failure("a save of this build needed repairs in ${c.phase} frame ${c.frame}: ${loaded.loadDroppedRefs} ${loaded.saveMigrationLines}", null)
        loaded.debugFlags.noEnemyFire.set = s.debugFlags.noEnemyFire.set
        c.state = loaded
        c.lastSig = signature(loaded)
    }

    /** The coarse state: what a transition changes. */
    private fun signature(s: InGameState): String {
        val b = StringBuilder()
        val sector = s.currentBeacon.sector
        b.append(sector.sectorNumber).append('/').append(sector.beacons.indexOf(s.currentBeacon))
        b.append(" e=").append(s.enemy?.let { it.type.name + ":" + s.isEnemyHostile + ":" + s.isEnemyJumping + ":" + (it.health * 4 / maxOf(1, it.maxHealth)) } ?: "-")
        b.append(" j=").append(s.browserJumpPending()).append(" d=").append(s.shipUI.diagnosticDialogue != null).append(" w=").append(s.shipUI.isWindowOpen)
        for (ship in listOfNotNull(s.player, s.enemy)) {
            b.append(" |")
            b.append(ship.crew.count { it is LivingCrew && (it as LivingCrew).ownerShip === ship })
            b.append(':').append(ship.crew.count { it is LivingCrew && (it as LivingCrew).ownerShip !== ship })
            b.append(':').append(ship.crew.count { it is AbstractIndoorsDrone.Pawn })
            b.append(':').append(ship.crew.count { (it as? LivingCrew)?.mindControlledBy != null })
            b.append(" x").append(ship.externalDrones.size)
            b.append(" s").append(ship.systems.size).append(':').append(ship.systems.count { it.hackedBy != null })
            ship.hacking?.let { b.append(" h").append(it.droneLaunched).append(it.droneLanded).append(it.active) }
            ship.mindControl?.let { b.append(" m").append(it.controlledCrew != null) }
            ship.cloaking?.let { b.append(" c").append(it.active) }
            (ship.systems.firstOrNull { it is Temporal } as Temporal?)?.let { b.append(" t").append(it.currentRoom != null).append(it.queuedRoom != null) }
            ship.clonebay?.let { b.append(" q").append(it.queue.size) }
        }
        return b.toString()
    }

    // ---- Setup ------------------------------------------------------------------------------------------------------

    /** The hangar's layouts; with Hyperspace also every player ship that lists the temporal system. */
    private fun playerShips(main: MainGame): List<String> {
        val bm = main.gameContent.blueprintManager
        val hangar = main.gameContent.shipFamilies.families.take(10).flatMap { it.ships }.filter { bm.getOrNull(it) != null }
        val temporal = if (!HS.active) emptyList() else bm.blueprints.values.filterIsInstance<LazyShipBlueprint>()
            .filter { it.name.startsWith("PLAYER_SHIP_") && it.listsSystem("temporal") }.map { it.name }.sorted()
        // Interleaved, so a short run sees both kinds.
        val out = ArrayList<String>()
        for (i in 0 until maxOf(hangar.size, temporal.size)) {
            hangar.getOrNull(i)?.let { if (it !in out) out += it }
            temporal.getOrNull(i)?.let { if (it !in out) out += it }
        }
        return out
    }

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

    private fun equipPlayer(c: Ctx) {
        val s = c.state
        val p = s.player
        for (id in SYSTEM_IDS) addSystem(p, id)
        HsConsole.run(s, "GOD")
        p.dronesCount = 40
        p.missilesCount = 40
        p.fuelCount = 20
        val bm = s.blueprintManager
        for (w in listOf("BEAM_1", "LASER_BURST_2", "MISSILES_1", "BOMB_1", "ION_1", "SHOTGUN_1").shuffled(c.rnd).take(3))
            (bm.getOrNull(w) as? xyz.znix.xftl.weapons.AbstractWeaponBlueprint)?.let { p.addBlueprint(it, true, false) }
        for (d in listOf("COMBAT_1", "DEFENSE_1", "BOARDER", "ANTI_DRONE", "REPAIR", "COMBAT_BEAM").shuffled(c.rnd).take(3))
            (bm.getOrNull(d) as? DroneBlueprint)?.let { p.addBlueprint(it, true, false) }
        // Augments that act at these transitions (recovery, teleport healing, recall, clone and shield bypass).
        for (aug in listOf("DRONE_RECOVERY", "TELEPORT_HEAL", "BACKUP_DNA", "ZOLTAN_BYPASS", "TELEPORT_RECALL", "EXPLOSIVE_REPLICATOR").shuffled(c.rnd).take(2))
            if (bm.getOrNull(aug) != null) HsConsole.run(s, "AUG $aug")
        val races = bm.blueprints.values.filterIsInstance<CrewBlueprint>().filter { it.name in listOf("human", "engi", "mantis", "rock", "slug", "energy", "crystal", "anaerobic") }
        repeat(2) { races.randomOrNull(c.rnd)?.let { p.addCrewMember(LivingCrewInfo.generateRandom(it, s), false) } }
        HsConsole.run(s, "GOD")
        powerAll(p)
    }

    private fun powerAll(ship: Ship) {
        ship.updateAvailablePower()
        for (system in ship.systems) if (system is MainSystem) repeat(system.energyLevels) { system.increasePower() }
        ship.weapons?.let { w -> for (hp in ship.hardpoints) hp.weapon?.let { w.setWeaponPower(it, true) } }
        ship.update(0f)
    }

    private fun spawnEnemy(c: Ctx, index: Int) {
        val s = c.state
        val specs = s.eventManager.getShips().filter { it.name.isNotEmpty() }.sortedBy { it.name }
        // Several picks: a ship spec that builds no ship (an event-only placeholder) is not a case.
        for (attempt in 0 until 12) {
            val spec = specs[(index * 131 + attempt * 17 + c.rnd.nextInt(specs.size)) % specs.size]
            c.enemySpec = spec.name
            s.debugSpawnShip(spec, Difficulty.NORMAL, c.rnd.nextInt(7), SEED0 + index * 13 + attempt)
            val e = s.enemy ?: continue
            if (e.rooms.size < 2) continue
            for (id in SYSTEM_IDS) addSystem(e, id)
            e.purchasedReactorPower = 30
            e.dronesCount = 20
            e.missilesCount = 20
            e.drones?.let { d ->
                d.energyLevels = maxOf(d.energyLevels, 6)
                val bm = s.blueprintManager
                for ((slot, name) in listOf("BOARDER", "COMBAT_1", "DEFENSE_1").withIndex()) {
                    val type = bm.getOrNull(name) as? DroneBlueprint ?: continue
                    while (d.drones.size <= slot) d.drones.add(null)
                    if (d.drones[slot] == null) d.drones[slot] = xyz.znix.xftl.systems.Drones.DroneInfo(type)
                }
            }
            powerAll(e)
            return
        }
        throw Failure("no enemy ship built in 12 picks", null)
    }

    // ---- Actions ----------------------------------------------------------------------------------------------------

    private fun <T> List<T>.pick(r: Random): T? = if (isEmpty()) null else this[r.nextInt(size)]

    private fun enemyRooms(s: InGameState): List<Room> = s.enemy?.rooms ?: emptyList()
    private fun anyRoom(c: Ctx): Room? = (s(c).player.rooms + enemyRooms(s(c))).pick(c.rnd)
    private fun s(c: Ctx) = c.state

    /** The fight's attacks, again every few seconds: targets, drones, boarders, hacking, mind control, temporal. */
    private fun setupAttacks(c: Ctx) {
        val s = c.state
        val p = s.player
        val enemy = s.enemy ?: return
        c.act("attacks")
        guard(c, "setting up the attacks") {
            p.weapons?.let { w ->
                for ((slot, hp) in p.hardpoints.withIndex()) if (hp.weapon != null) enemy.rooms.pick(c.rnd)?.let { aim(p, slot, it, enemy.rooms.pick(c.rnd)!!) }
            }
            p.drones?.let { d -> for (i in d.drones.indices) if (d.drones[i] != null && c.rnd.nextInt(3) > 0) d.setDronePower(i, true) }
            enemy.drones?.let { d -> for (i in d.drones.indices) if (d.drones[i] != null) d.setDronePower(i, true) }
            p.hacking?.let { h -> enemy.rooms.filter { it.system != null }.pick(c.rnd)?.let { h.selectTarget(it) }; if (h.droneLanded) h.startHackingPulse() }
            p.mindControl?.let { m -> enemy.rooms.filter { r -> r.crew.isNotEmpty() }.pick(c.rnd)?.let { m.selectRoom(it) } }
            p.teleporter?.let { t -> enemy.rooms.pick(c.rnd)?.let { t.selectTeleportAction(c.rnd.nextInt(3) > 0, it) } }
            temporal(p)?.let { t -> enemy.rooms.filter { it.system != null }.pick(c.rnd)?.let { t.setArmed(1 + c.rnd.nextInt(2)); t.selectRoom(it, c.rnd.nextBoolean()) } }
            p.cloaking?.let { if (c.rnd.nextInt(3) == 0) it.activateCloak() }
            // The enemy's boarders come too: an event's intruders, as BOARDING events give them.
            if (c.rnd.nextInt(3) == 0) {
                val race = s.blueprintManager.getOrNull(listOf("human", "mantis", "rock").pick(c.rnd)!!) as? CrewBlueprint
                if (race != null) s.givePlayerResources(ResourceSet().also { it.intruders += LivingCrewInfo.generateRandom(race, s) })
            }
        }
    }

    /** The player's aim at [room] as its weapon takes it: a room, or a beam's swipe towards [end]. */
    private fun aim(p: Ship, slot: Int, room: Room, end: Room) {
        val w = p.weapons ?: return
        when (val weapon = p.hardpoints[slot].weapon) {
            is IRoomTargetingWeapon -> w.selectedTargets.targetRoom(slot, room)
            is BeamBlueprint.BeamInstance -> w.selectedTargets.targetBeam(slot, weapon.buildAim(room, end, false).also { it.weaponNumber = slot })
            else -> {}
        }
    }

    private fun temporal(ship: Ship): Temporal? = ship.systems.firstOrNull { it is Temporal } as Temporal?

    private fun randomAction(c: Ctx) {
        val s = c.state
        val p = s.player
        val r = c.rnd
        val kind = r.nextInt(23)
        c.act("a$kind")
        guard(c, "random action $kind") {
            when (kind) {
                0 -> (p.systems.filterIsInstance<MainSystem>().pick(r))?.let { if (r.nextBoolean()) it.increasePower() else it.decreasePower() }
                1 -> p.weapons?.let { w ->
                    val slot = p.hardpoints.indices.filter { p.hardpoints[it].weapon != null }.pick(r) ?: return@let
                    val weapon = p.hardpoints[slot].weapon!!
                    if (r.nextInt(3) == 0) w.setWeaponPower(weapon, !weapon.isPowered) else enemyRooms(s).pick(r)?.let { aim(p, slot, it, enemyRooms(s).pick(r)!!) }
                }
                2 -> p.drones?.let { d -> d.drones.indices.filter { d.drones[it] != null }.pick(r)?.let { i -> d.setDronePower(i, d.drones[i]?.instance == null) } }
                3 -> p.hacking?.let { h -> if (h.droneLanded && r.nextBoolean()) h.startHackingPulse() else enemyRooms(s).pick(r)?.let { h.selectTarget(it) } }
                4 -> p.mindControl?.let { m -> anyRoom(c)?.let { m.selectRoom(it) } }
                5 -> p.teleporter?.let { t -> enemyRooms(s).pick(r)?.let { t.selectTeleportAction(r.nextBoolean(), it) } }
                6 -> temporal(p)?.let { t -> anyRoom(c)?.let { t.setArmed(1 + r.nextInt(2)); t.selectRoom(it, r.nextBoolean()) } }
                7 -> { p.cloaking?.activateCloak(); p.backupBattery?.startBattery() }
                8 -> p.crew.filterIsInstance<LivingCrew>().pick(r)?.let { m -> m.room.ship.rooms.pick(r)?.let { m.setTargetRoom(it) } }
                9 -> {
                    // A crew member dies: any ship, any side (the player keeps one of its own aboard its ship).
                    val all = (p.crew + (s.enemy?.crew ?: emptyList())).filter { it.health > 0f }
                    val own = p.crew.count { it is LivingCrew && it.ownerShip === p }
                    all.filter { !(it is LivingCrew && it.ownerShip === p && own <= 2) }.pick(r)?.dealDamage(FireDamage(10_000f))
                }
                10 -> {
                    // Dismissed (ShipWindow's confirm): out of the clone queue and off the ship.
                    val own = p.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === p }
                    if (own.size > 2) own.pick(r)?.let { m -> p.clonebay?.queue?.remove(m); m.removeFromShip() }
                }
                11 -> anyRoom(c)?.let { if (r.nextBoolean()) it.spawnFire() else it.spawnBreach() }
                12 -> (p.systems + (s.enemy?.systems ?: emptyList())).pick(r)?.dealDamage(1 + r.nextInt(3), r.nextInt(2))
                13 -> pointer(c)
                14 -> pointer(c)
                15 -> if (TouchMode.enabled) tap(c) else pointer(c)
                16 -> s.enemy?.let { e ->
                    // The enemy's own active systems pointed at the player, as its AI does.
                    e.mindControl?.let { m -> p.rooms.filter { it.crew.isNotEmpty() }.pick(r)?.let { m.selectRoom(it) } }
                    e.hacking?.let { h -> p.rooms.filter { it.system != null }.pick(r)?.let { h.selectTarget(it) } }
                    e.teleporter?.let { t -> p.rooms.pick(r)?.let { t.selectTeleportAction(true, it) } }
                    temporal(e)?.let { t -> p.rooms.filter { it.system != null }.pick(r)?.let { t.setArmed(1 + r.nextInt(2)); t.selectRoom(it, r.nextBoolean()) } }
                }
                17 -> s.shipUI.let { if (it.isWindowOpen) closeWindows(c) else if (r.nextInt(4) == 0) it.openSectorMap() }
                18 -> p.drones?.let { d -> d.drones.indices.filter { d.drones[it]?.instance != null }.pick(r)?.let { d.setDronePower(it, false) } }
                19 -> s.enemy?.let { e -> e.systems.pick(r)?.dealDamage(2 + r.nextInt(4), 0) }
                20 -> {
                    // A weapon to the hold (the equipment screen, open in a fight as in the original).
                    val slot = p.hardpoints.indices.filter { p.hardpoints[it].weapon != null }.pick(r)
                    val free = (0 until 3).firstOrNull { p.cargoBlueprints[it] == null }
                    if (slot != null && free != null) xyz.znix.xftl.game.ShipEquipmentPanel(s, p).diagnosticDragWeaponToCargo(slot, free)
                }
                21 -> {
                    // An event's <remove>: a weapon or a drone the ship has goes (Ship.removeBlueprint).
                    val items = p.hardpoints.mapNotNull { it.weapon?.type } + (p.drones?.drones?.mapNotNull { it?.type } ?: emptyList())
                    items.pick(r)?.let { p.removeBlueprint(it) }
                }
                else -> {
                    // New equipment (an event's reward, the console's WEAPON/DRONE), so the fight does not run dry.
                    val bm = s.blueprintManager
                    if (r.nextBoolean()) (bm.getOrNull(listOf("LASER_BURST_1", "BEAM_1", "MISSILES_2_PLAYER", "BOMB_1").pick(r)!!) as? xyz.znix.xftl.weapons.AbstractWeaponBlueprint)?.let { p.addBlueprint(it, true, false) }
                    else (bm.getOrNull(listOf("COMBAT_1", "DEFENSE_1", "BOARDER", "ANTI_DRONE").pick(r)!!) as? DroneBlueprint)?.let { p.addBlueprint(it, true, false) }
                }
            }
        }
    }

    private fun killEnemyCrew(c: Ctx) {
        val enemy = c.state.enemy ?: return
        for (ship in listOf(c.state.player, enemy)) for (m in ArrayList(ship.crew))
            if (m is LivingCrew && m.ownerShip === enemy && m.health > 0f) m.dealDamage(FireDamage(10_000f))
    }

    /** The pointer over a random point of the screen: hovers (tooltips), sometimes a click or a right click. */
    private fun pointer(c: Ctx) {
        val r = c.rnd
        // The two ships' half of the screen mostly (crew, rooms, systems, drones), now and then anywhere.
        val x = if (r.nextInt(4) == 0) r.nextInt(1280) else 40 + r.nextInt(1200)
        val y = if (r.nextInt(4) == 0) r.nextInt(720) else 60 + r.nextInt(560)
        BrowserEngine.diagnosticMouse(x, y, 0, 0)
        if (r.nextInt(3) == 0) {
            val button = if (r.nextInt(3) == 0) 1 else 0
            BrowserEngine.diagnosticMouse(x, y, 1, button)
            BrowserEngine.diagnosticMouse(x, y, 2, button)
        }
        afterInput(c)
    }

    private var touchTime = 0L

    /** A finger tapping a random point, as the Android host routes it (TouchRouter). */
    private fun tap(c: Ctx) {
        val hud = c.state.shipUI.touch ?: return pointer(c)
        val x = c.rnd.nextInt(1280)
        val y = c.rnd.nextInt(720)
        val mouse = TouchRouter.Mouse { _, _, _, _ -> }
        touchTime += 100
        TouchRouter.route(hud, null, TouchRouter.DOWN, 0, x, y, touchTime, mouse)
        touchTime += 60 + c.rnd.nextInt(700)
        TouchRouter.route(hud, null, TouchRouter.UP, 0, x, y, touchTime, mouse)
        var n = 0
        while (TouchRouter.afterStep() && n++ < 4) Unit
        afterInput(c)
    }

    /** Input never leaves the run: a menu a click opened is closed, a pause is undone. */
    private fun afterInput(c: Ctx) {
        val s = c.main.currentState as? InGameState ?: return
        if (s.shipUI.isWindowOpen && c.rnd.nextInt(3) > 0) closeWindows(c)
    }

    private fun closeWindows(c: Ctx) {
        val s = c.main.currentState as? InGameState ?: return
        BrowserDiagnostics.closeBoxes(c.main, c.container)
        if (s.isPaused && !s.shipUI.isWindowOpen) s.toggleQuickPause()
    }

    // ---- Endings ----------------------------------------------------------------------------------------------------

    private fun end(c: Ctx) {
        val s = c.state
        val p = s.player
        c.act("end:${c.ending}")
        guard(c, "the ending ${c.ending}") {
            when (c.ending) {
                Ending.DESTROYED -> HsConsole.run(s, "DELETE")
                Ending.CREW_KILLED -> killEnemyCrew(c)
                Ending.SURRENDER -> s.enemy?.let { it.health = maxOf(1, it.surrenderHealth) }
                Ending.ESCAPE -> s.enemy?.let { e ->
                    e.health = maxOf(1, if (e.escapeHealth > 0) e.escapeHealth else e.surrenderHealth)
                    if (e.escapeTimer == null) e.escapeTimer = 2f
                }
                Ending.INSTANT_ESCAPE -> s.hsEnemyJumpLeave()
                Ending.PLAYER_JUMPS -> {
                    closeWindows(c)
                    p.fuelCount = maxOf(p.fuelCount, 3)
                    s.currentBeacon.neighbours.firstOrNull()?.let { s.beginJumpOut(it) }
                }
                Ending.SECTOR_CHANGE -> { closeWindows(c); HsConsole.run(s, "SECTOR STANDARD_SPACE") }
                Ending.PLAYER_DESTROYED -> p.health = 0
                Ending.FOUGHT_OUT -> s.enemy?.let { it.health = minOf(it.health, 3) }
            }
        }
    }
}
