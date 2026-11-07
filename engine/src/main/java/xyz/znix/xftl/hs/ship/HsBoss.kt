package xyz.znix.xftl.hs.ship

import ftl.browser.BrowserRandom
import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewCrystal
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsCapability
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.BombBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.FlakBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hyperspace `<boss>` (hyperspace.xml): the flagship's crew, its stage 2 drone surge and stage 3 barrage, plus the
 * named surges events launch from any ship (`<superDrones name=...>`, `<superBarrage name=...>`).
 *
 * The crew counts are the `CustomBoss::instance` singleton's: they live as long as the page (the native process), are
 * written into the boss save but never read back (LoadBoss reads and drops them), so a stage reached after a reload
 * starts without crew. Kept as in HS.
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/CustomBoss.cpp
object HsBoss {
    class Count(@JvmField val name: String, @JvmField val number: Int)

    /** `std::array<std::vector<DroneCount|BarrageCount>, 3>`: the entries of each difficulty. */
    class Def { @JvmField val byDifficulty = Array(3) { ArrayList<Count>() } }

    /** `initialCrewList`: (race, room) of the stage 1 crew. */
    @JvmField val initialCrew = ArrayList<Pair<String, Int>>()

    /** `currentCrewCounts`: (species, room) of each living crew member, refreshed every boss frame. */
    @JvmField val currentCrew = ArrayList<Pair<String, Int>>()

    @JvmField var surgeDrones: Def? = null
    @JvmField var barrage: Def? = null
    @JvmField val namedDrones = HashMap<String, Def>()
    @JvmField val namedBarrages = HashMap<String, Def>()

    fun registerCapabilities() {
        HsCapability.implement("boss", "surgeDrones", "surgeBarrage")
    }

    fun load(config: HsConfig?) {
        initialCrew.clear(); currentCrew.clear(); namedDrones.clear(); namedBarrages.clear()
        surgeDrones = null; barrage = null
        active.clear(); pools.clear()
        config ?: return
        for (node in config.nodesOf("boss")) parseBossNode(node)
    }

    /** Diagnostics: one more `<boss>` node, as a hyperspace.xml append after the loaded ones would give. */
    fun diagnosticAppend(node: Element) = parseBossNode(node)

    /** ParseBossNode: a bad number stops the node there (the C++ catches the lexical_cast at the node level). */
    private fun parseBossNode(node: Element) {
        // droneSurgeDef is emptied at the start of every <boss> node (the barrage is not).
        surgeDrones?.byDifficulty?.forEach { it.clear() }
        try {
            for (child in node.children) {
                when (child.name) {
                    "crew" -> for (c in child.children) initialCrew += c.name to (c.getAttributeValue("room")?.let(::lexInt) ?: 0)
                    "surgeDrones" -> {
                        val name = child.getAttributeValue("name")
                        if (name != null) namedDrones[name] = parseCounts(child)
                        else surgeDrones = parseCounts(child, surgeDrones ?: Def())
                    }
                    "surgeBarrage" -> {
                        val name = child.getAttributeValue("name")
                        if (name != null) namedBarrages[name] = parseCounts(child)
                        else barrage = parseCounts(child, barrage ?: Def())
                    }
                }
            }
        } catch (e: Exception) {
            HS.unsupported("parse", HS.HYPERSPACE_XML, "<boss>: ${e.message ?: e}")
        }
    }

    /** ParseBossDroneNode / ParseBossBarrageNode: a difficulty above 2 is skipped, none means all three. */
    private fun parseCounts(node: Element, def: Def = Def()): Def {
        for (c in node.children) {
            val diff = c.getAttributeValue("difficulty")
            if (diff != null) {
                val d = lexInt(diff)
                if (d > 2) continue
                def.byDifficulty[d] += Count(c.getAttributeValue("name") ?: error("missing name"), lexInt(c.getAttributeValue("count")))
            } else for (d in 0..2)
                def.byDifficulty[d] += Count(c.getAttributeValue("name") ?: error("missing name"), lexInt(c.getAttributeValue("count")))
        }
        return def
    }

    private fun lexInt(s: String?): Int {
        s ?: throw IllegalArgumentException("missing value")
        require(Regex("[+-]?[0-9]+").matches(s)) { "bad lexical cast: $s" }
        return s.toInt()
    }

    private fun difficultyIndex(game: InGameState) = when (game.difficulty) {
        Difficulty.EASY -> 0
        Difficulty.NORMAL -> 1
        Difficulty.HARD -> 2
    }

    // ---- the flagship's crew (BossShip::StartStage + the AddCrewMemberFromString hook) ----

    /**
     * StartStage's first AddCrewMemberFromString call (made only when the vanilla room table has a member) adds the
     * HS crew instead and every vanilla call adds nothing: stage 1 is `initialCrewList`, later stages
     * `currentCrewCounts` (rooms past the ship's room count are dropped). Each member draws `random32() % 2` (the
     * sex argument, overwritten by the name draw) before AddCrewMemberFromString's own draws
     * ([xyz.znix.xftl.crew.NativeCrewFactory.createFromString]: two names, the second kept).
     */
    fun spawnStageCrew(game: InGameState, flagship: Ship, stage: Int, vanillaCalls: Boolean) {
        if (!vanillaCalls) return
        val list = if (stage == 1) ArrayList(initialCrew) else ArrayList(currentCrew).filter { it.second < flagship.rooms.size }
        for ((race, roomId) in list) {
            NativeRandom.next31() // random32() % 2: the male argument
            // super("", race, ...): the original AddCrewMemberFromString with an empty name, as vanilla's flagship crew
            val boarder = game.crewFactory.createFromString(game, race)
            val room = flagship.rooms.getOrNull(roomId)
            if (room == null) {
                HS.unsupported("boss", "crew", "room $roomId of ${flagship.type.name}")
                continue
            }
            val member = flagship.addCrewMemberInRoom(boarder.info, room, false)
            (member as? CrewCrystal)?.initEnemyPower(boarder.crystalNeverReady)
        }
    }

    /** BossShip::OnLoop hook: unless jumping or destroyed, the boss's own living, non-drone crew aboard. */
    fun recordCrew(ship: Ship) {
        if (ship.browserJumping || ship.isDead) return
        currentCrew.clear()
        for (c in ship.crew) {
            if (c is LivingCrew && c.health > 0f && c.ownerShip === ship)
                currentCrew += c.codename to c.room.id
        }
    }

    // ---- super drones (ShipManager::PrepareSuperDrones hook) ----

    /** `ShipManager::superDrones`: the pool of drone blueprints of each ship (built once, until cleared). */
    private val pools = xyz.znix.xftl.hs.lua.HsRunMaps.run(HashMap<Ship, ArrayList<String>>())

    /** The launched super drones and their owner. */
    private val active = ArrayList<Pair<Ship, CombatDrone>>()

    /** The definition the next PrepareSuperDrones uses: the override (consumed) or the unnamed one; null = vanilla. */
    fun droneDef(override: String): Def? {
        var def = surgeDrones
        if (override.isNotEmpty()) namedDrones[override]?.let { def = it }
        return def
    }

    fun barrageDef(override: String): Def? {
        var def = barrage
        if (override.isNotEmpty()) namedBarrages[override]?.let { def = it }
        return def
    }

    /**
     * The pool (built from the definition when the ship has none) is launched at the ship's enemy: every drone
     * powered, deployed and good for two passes ([CombatDrone.browserPassesLeft], as the vanilla surge).
     */
    /** The last step of [prepareSuperDrones] (probe diagnostics). */
    @JvmField var diagnosticStep = ""

    fun prepareSuperDrones(game: InGameState, ship: Ship, def: Def) {
        diagnosticStep = "pool"
        val pool = pools.getOrPut(ship) { ArrayList() }
        if (pool.isEmpty()) {
            for (c in def.byDifficulty[difficultyIndex(game)]) repeat(c.number) { pool += c.name }
        }
        if (game.getEnemyOf(ship) == null) return
        // The same drones are deployed again: the engine replaces the instances of the last launch.
        active.removeAll { (owner, drone) -> if (owner === ship) { drone.removeInstance(); true } else false }
        for (name in pool) {
            val bp = game.blueprintManager.getOrNull(name) as? DroneBlueprint
            val drone = bp?.makeInstance() as? CombatDrone
            if (drone == null) {
                HS.unsupported("boss", "superDrones", "drone $name")
                continue
            }
            diagnosticStep = "init $name"
            drone.init(ship)
            diagnosticStep = "power $name"
            drone.isPowered = true
            drone.browserPassesLeft = 2
            active += ship to drone
        }
    }

    /**
     * `superDrones.clear()`. The clearSuperDrones event only empties the pool (the drones fly on); a destroyed enemy
     * (Ship::DestroyedDone) and WorldManager::Restart also destroy them.
     */
    fun clearSuperDrones(ship: Ship, destroy: Boolean) {
        pools.remove(ship)
        if (destroy) active.removeAll { (owner, drone) -> if (owner === ship) { drone.removeInstance(); true } else false }
    }

    /**
     * CustomEvents.cpp (CustomCreateLocation): `superDrones` (0 = the player's ship, 1 = the enemy) launches the pool,
     * `clearSuperDrones` empties it, `superBarrage` fires. The name picks a named definition (unknown: the unnamed one).
     */
    fun applyEvent(game: InGameState, where: String, drones: Int, dronesName: String, clear: Int, barrage: Int, barrageName: String) {
        fun shipOf(id: Int) = if (id == 0) game.player else game.enemy
        if (drones != -1) shipOf(drones)?.let { ship ->
            val def = droneDef(dronesName)
            if (def != null) prepareSuperDrones(game, ship, def) else HS.unsupported("event", where, "superDrones without a <boss> definition")
        }
        if (clear != -1) shipOf(clear)?.let { clearSuperDrones(it, false) }
        if (barrage != -1) shipOf(barrage)?.let { ship ->
            val def = barrageDef(barrageName)
            if (def != null) prepareSuperBarrage(game, ship, def) else HS.unsupported("event", where, "superBarrage without a <boss> definition")
        }
    }

    fun hasSuperDrones(): Boolean = active.isNotEmpty()

    /** ShipManager::superDrones of [ship]: its launched super drones (Lua view). */
    fun superDronesOf(ship: Ship): List<CombatDrone> = active.filter { it.first === ship }.map { it.second }

    /** Every frame: fly the launched drones; drop those gone (passes used up, target left) and those of a ship gone. */
    fun update(game: InGameState, dt: Float) {
        superBarrage.clear()
        // A ship that left (a new enemy, a new stage) takes its pool with it.
        if (pools.isNotEmpty()) pools.keys.removeAll { it !== game.player && it !== game.enemy }
        updateBarrageBeams(game, dt)
        if (active.isEmpty()) return
        val it = active.iterator()
        while (it.hasNext()) {
            val (owner, drone) = it.next()
            val target = game.getEnemyOf(owner)
            val present = owner === game.player || owner === game.enemy
            if (!present || target == null || !target.externalDrones.contains(drone)) {
                if (target?.externalDrones?.contains(drone) == true) drone.removeInstance()
                it.remove()
                continue
            }
            diagnosticStep = "update ${drone.type.name}"
            drone.update(dt)
        }
        // Ship::DestroyedDone: a destroyed ship's enemy loses its super drones.
        for (ship in listOfNotNull(game.player, game.enemy)) {
            if (ship.isGone) game.getEnemyOf(ship)?.let { other -> if (pools.containsKey(other) || active.any { it.first === other }) clearSuperDrones(other, true) }
        }
    }

    // ---- super barrage (ShipManager::PrepareSuperBarrage hook) ----

    /**
     * Every entry of the difficulty, `count` times: a laser or missile at a random room, a flak burst at a random
     * room, from the ship's centre on a random heading; a beam from a random room centre towards another (a pinpoint
     * beam on a random tile of that room), entering on a random angle; a bomb in every room in a shuffled order
     * (shuffled again once each room had one).
     */
    fun prepareSuperBarrage(game: InGameState, ship: Ship, def: Def) {
        val target = game.getEnemyOf(ship) ?: return
        val before = ship.projectiles.size
        val origin = shipCentre(ship)
        val bombTargets = ArrayList<xyz.znix.xftl.layout.Room>()
        var bombIndex = 0
        // ShipManager::superBarrage lists the shots in the order they were made (bombs go straight to the target
        // ship's space, the others to this ship's; a barrage beam is a weapon here and is not listed)
        val made = ArrayList<Any>()
        var seen = before
        // random32() on the combat stream, `% n` as the C++
        fun r32() = xyz.znix.xftl.hs.weapons.HsWeapons.r32()
        fun randomRoom() = target.rooms[r32() % target.rooms.size] // ShipManager::GetRandomRoomCenter
        if (target.rooms.isEmpty()) return
        for (c in def.byDifficulty[difficultyIndex(game)]) {
            val bp = game.blueprintManager.getOrNull(c.name) as? xyz.znix.xftl.weapons.AbstractWeaponBlueprint ?: continue
            repeat(c.number) {
                when (bp) {
                    is LaserBlueprint -> { val room = randomRoom(); launch(ship, bp.LaserProjectile(room), origin, r32() % 360) }
                    is MissileBlueprint -> { val room = randomRoom(); launch(ship, bp.MissileProjectile(room), origin, r32() % 360) }
                    is FlakBlueprint -> {
                        val room = randomRoom()
                        val heading = (r32() % 360).toFloat()
                        val entryAngle = (r32() % 360).toFloat()
                        (bp.buildInstance(ship) as FlakBlueprint.FlakInstance).hsBarrageVolley(room, origin, heading, entryAngle)
                    }
                    is BeamBlueprint -> fireBarrageBeam(ship, target, bp, randomRoom())
                    is BombBlueprint -> {
                        // std::random_shuffle(rooms, random32() % i), refilled when every room was used.
                        if (bombIndex >= bombTargets.size) {
                            bombTargets.clear(); bombTargets.addAll(target.rooms)
                            for (k in 1 until bombTargets.size) java.util.Collections.swap(bombTargets, k, r32() % (k + 1))
                            bombIndex = 0
                        }
                        val instance = bp.buildInstance(ship) as BombBlueprint.BombInstance
                        made += instance.fireBarrage(bombTargets[bombIndex++])
                    }
                    else -> {
                        HS.unsupported("boss", "superBarrage", "${bp.javaClass.simpleName} ${bp.name}")
                        return@repeat
                    }
                }
                for (i in seen until ship.projectiles.size) made += ship.projectiles[i]
                seen = ship.projectiles.size
                // PlaySoundMix(launchSounds[random32() % size]) after every entry
                bp.launchSounds?.sounds?.takeIf { it.isNotEmpty() }?.let { it[r32() % it.size]?.play() }
            }
        }
        // ShipManager::superBarrage holds the shots until the ship's next loop moves them into space (Lua reads it)
        superBarrage.getOrPut(ship) { ArrayList() }.addAll(made)
    }

    /** `ShipManager::superBarrage`: the shots of the last barrage of each ship, until the next frame. */
    private val superBarrage = xyz.znix.xftl.hs.lua.HsRunMaps.run(HashMap<Ship, ArrayList<Any>>())

    /** ShipManager.superBarrage (Lua view). */
    @JvmStatic
    fun superBarrageOf(ship: Ship): List<Any?> = superBarrage[ship] ?: emptyList()

    /**
     * Lua ShipManager:PrepareSuperBarrage(): the HS hook with no `barrageOverride` (only events set one): the unnamed
     * `<surgeBarrage>`; without one, the vanilla flagship barrage (not callable on an arbitrary ship here).
     */
    fun prepareSuperBarrageLua(game: InGameState, ship: Ship) {
        val def = barrageDef("")
        if (def == null) { HS.unsupported("lua", "PrepareSuperBarrage", "vanilla barrage without a <boss> <surgeBarrage>"); return }
        prepareSuperBarrage(game, ship, def)
    }

    /** The barrage's beams: free BeamInstances of the barrage ship, updated here until their sweep ends. */
    private val barrageBeams = ArrayList<BeamBlueprint.BeamInstance>()

    @JvmStatic
    val barrageBeamCount: Int get() = barrageBeams.size

    private fun fireBarrageBeam(ship: Ship, target: Ship, bp: BeamBlueprint, room: xyz.znix.xftl.layout.Room) {
        val beam = bp.buildInstance(ship) as BeamBlueprint.BeamInstance
        beam.forceSetPowered(true)
        beam.isOnDrone = true
        val aim = if (bp.length > 1) {
            // A real beam: towards another random room centre (the original redraws while it is the same point).
            fun r() = target.rooms[xyz.znix.xftl.hs.weapons.HsWeapons.r32() % target.rooms.size]
            var end = r()
            if (target.rooms.size > 1) while (end.pixelCentre == room.pixelCentre) end = r()
            beam.buildAim(room, end, false)
        } else {
            // A pinpoint beam: a random tile of the room, pointing right (finalPos = targetPos + (1, 0)).
            val cell = room.slotToPoint(xyz.znix.xftl.hs.weapons.HsWeapons.r32() % room.cellCount.coerceAtLeast(1))
            val point = ConstPoint(room.offsetX + cell.x * 35 + 17, room.offsetY + cell.y * 35 + 17)
            xyz.znix.xftl.systems.SelectedTarget.BeamAim(beam, -1, target, point).also { it.angle = 0f; it.updateHitRooms() }
        }
        xyz.znix.xftl.hs.weapons.HsWeapons.r32() // heading % 360: the beam leaves the barrage ship that way (not drawn)
        beam.fire(aim) // the entry angle
        barrageBeams += beam
    }

    /** A crystal shard beam: as a barrage's real beam whatever its length (CheckCustomCrystalAugment has no pinpoint case). */
    fun fireCrystalBeam(ship: Ship, target: Ship, bp: BeamBlueprint, room: xyz.znix.xftl.layout.Room) {
        val beam = bp.buildInstance(ship) as BeamBlueprint.BeamInstance
        beam.forceSetPowered(true)
        beam.isOnDrone = true
        fun r() = target.rooms[xyz.znix.xftl.hs.weapons.HsWeapons.r32() % target.rooms.size]
        var end = r()
        if (target.rooms.size > 1) while (end.pixelCentre == room.pixelCentre) end = r()
        val aim = beam.buildAim(room, end, false)
        xyz.znix.xftl.hs.weapons.HsWeapons.r32()
        beam.fire(aim)
        barrageBeams += beam
    }

    private fun updateBarrageBeams(game: InGameState, dt: Float) {
        if (barrageBeams.isEmpty()) return
        val it = barrageBeams.iterator()
        while (it.hasNext()) {
            val beam = it.next()
            val present = beam.ship === game.player || beam.ship === game.enemy
            if (present && beam.isFiring) beam.update(dt, 0f, false)
            if (!present || !beam.isFiring) { if (!present) beam.onJump(); it.remove() }
        }
    }

    internal fun launch(ship: Ship, shot: xyz.znix.xftl.weapons.AbstractWeaponProjectile, origin: ConstPoint, headingDegrees: Int) {
        // heading = random32() % 360: the shot leaves the ship that way.
        val heading = Math.toRadians(headingDegrees.toDouble())
        val end = ConstPoint(origin.x + (5000 * cos(heading)).toInt(), origin.y + (5000 * sin(heading)).toInt())
        shot.setInitialPath(origin, end)
        ship.projectiles.add(shot)
    }

    /** ShipGraph::center: the middle of the rooms' bounding box. */
    internal fun shipCentre(ship: Ship): ConstPoint {
        val minX = ship.rooms.minOf { it.offsetX }; val minY = ship.rooms.minOf { it.offsetY }
        val maxX = ship.rooms.maxOf { it.offsetX + it.pixelWidth }; val maxY = ship.rooms.maxOf { it.offsetY + it.pixelHeight }
        return ConstPoint((minX + maxX) / 2, (minY + maxY) / 2)
    }
}
