// FTL 1.6.14 ShipManager::CreateSpaceDrone (0x4b0b60), ShipManager::AddDrone (0x4b1020), DroneSystem::AddDrone
// (0x639640), SpaceManager::OnLoop (0x41f3c0: drone loop, then one new-drone arrival per ship per frame) and
// WorldManager::OnLoop (0x61b930: SpaceManager::OnLoop before the ships). Hyperspace (GPL-3.0-or-later) 1b05a07
// src/game/Gameplay/CustomDrones.cpp: the SpaceDrone constructor hook fires CONSTRUCT_SPACEDRONE with the base type.
package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IProjectile

object DroneRuntime {
    /**
     * Drone system slots. Putting a DroneInfo in a slot is DroneSystem::AddDrone: its body joins the ship
     * (ShipManager::spaceDrones, the arrival queue and CONSTRUCT_SPACEDRONE). Taking it out detaches it.
     */
    class SlotList(private val system: Drones) : ArrayList<Drones.DroneInfo?>() {
        override fun set(index: Int, element: Drones.DroneInfo?): Drones.DroneInfo? {
            val old = super.set(index, element)
            if (old !== element) { if (old != null && old !in this) detach(old); element?.let { attach(system, it) } }
            return old
        }

        /** Two slots trade drones (DroneSystem's swap): the bodies stay equipped. */
        fun swap(a: Int, b: Int) { val x = super.get(a); super.set(a, super.get(b)); super.set(b, x) }
        override fun add(element: Drones.DroneInfo?): Boolean { element?.let { attach(system, it) }; return super.add(element) }
        override fun add(index: Int, element: Drones.DroneInfo?) { element?.let { attach(system, it) }; super.add(index, element) }
        override fun removeAt(index: Int): Drones.DroneInfo? = super.removeAt(index)?.also { if (it !in this) detach(it) }
        override fun clear() { val old = toList(); super.clear(); old.forEach { it?.let { d -> detach(d) } } }
    }

    /** Bodies of a ship, in the order ShipManager::CreateSpaceDrone pushed them (ShipManager::spaceDrones). */
    private val shipDrones = java.util.IdentityHashMap<Ship, ArrayList<NativeDrone>>()

    /** ShipManager::newDroneArrivals: bodies not yet moved into the space's drone list. */
    private val arrivals = java.util.IdentityHashMap<Ship, ArrayList<NativeDrone>>()

    /** SpaceManager::drones: every space drone of the ships in play, deployed or not, in arrival order. */
    val space = ArrayList<NativeDrone>()

    /** Bodies without a drone system (CreateSpaceDrone from Lua): the space loop runs their actors. */
    private val standalone = ArrayList<NativeDrone>()

    fun spaceDrones(ship: Ship): List<NativeDrone> = shipDrones[ship] ?: emptyList()

    fun attach(system: Drones, info: Drones.DroneInfo) {
        if (info.system === system) return
        info.system = system
        val ship = system.attachedShip ?: return // attached in Drones.initialise once the system has a room
        join(ship, info)
    }

    /** Hyperspace, after a save loaded: the crew drones not deployed in it get their CrewDrone (deployed ones loaded theirs). */
    @JvmStatic
    fun hsCrewBodies(game: InGameState) {
        for (ship in listOfNotNull(game.player, game.enemy)) {
            for (info in ship.drones?.drones ?: continue) {
                if (info == null) continue
                (info.instance as? AbstractIndoorsDrone)?.let { info.hsAdoptCrewActor(it) }
                info.hsCrewBody(ship)
            }
        }
    }

    /** Drones.initialise: slots filled before the system was installed join the ship now. */
    fun attachPending(system: Drones) {
        val ship = system.attachedShip ?: return
        for (info in system.drones) if (info != null && info.system === system && !info.joined) join(ship, info)
    }

    private fun join(ship: Ship, body: NativeDrone) {
        body.joined = true
        // Hyperspace: a crew drone's CrewDrone is made now (CreateCrewDrone); a loaded save makes it after the load
        if (!body.isSpaceDrone) { if (!ship.sys.isCurrentlyLoadingSave) body.hsCrewBody(ship); return }
        shipDrones.getOrPut(ship) { ArrayList() } += body
        arrivals.getOrPut(ship) { ArrayList() } += body
        construct(ship, body)
    }

    fun detach(info: NativeDrone) {
        if (info.system != null && info.instance != null) info.instance?.removeInstance()
        info.hsDropCrewActor()
        info.system = null
        info.joined = false
        for (list in shipDrones.values) list.remove(info)
        for (list in arrivals.values) list.remove(info)
        space.remove(info)
    }

    /**
     * ShipManager::CreateSpaceDrone (0x4b0b60): defence, combat, boarder, hull-repair and shield drones get a body;
     * crew drones (repair, battle) and the hacking drone do not (nil). The defence drone and the hull-repair drone
     * take their own ship as movement target, the shield drone its shield system; with Hyperspace a combat drone
     * (and a boarder) aims at the ship's current target.
     */
    fun createSpaceDrone(ship: Ship, bp: DroneBlueprint): NativeDrone? {
        if (!NativeDrone.isSpaceType(bp.type) || bp.type == DroneBlueprint.DroneType.HACKING) return null
        val body = NativeDrone(bp)
        body.standaloneOwner = ship
        standalone += body
        join(ship, body)
        constructTargets(ship, body)
        return body
    }

    /** The target choices CreateSpaceDrone makes after the constructor (native, then the Hyperspace hook). */
    fun constructTargets(ship: Ship, body: NativeDrone) {
        when (body.type.type) {
            DroneBlueprint.DroneType.DEFENSE, DroneBlueprint.DroneType.SHIP_REPAIR, DroneBlueprint.DroneType.SHIELD ->
                body.aimMovementAt(ship)
            DroneBlueprint.DroneType.COMBAT, DroneBlueprint.DroneType.BOARDER -> if (xyz.znix.xftl.hs.HS.active) {
                ship.sys.getEnemyOf(ship)?.let { enemy ->
                    body.aimMovementAt(enemy)
                    if (body.type.type == DroneBlueprint.DroneType.COMBAT) body.weaponTarget = enemy
                }
            }
            else -> {}
        }
    }

    private fun construct(ship: Ship, body: NativeDrone) {
        if (body.constructed) return
        body.constructed = true
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.weapons.HsWeaponHooks.constructSpaceDrone(body)
        // ShipRepairDrone's constructor (0x4295c0, 1.6.14), after the base constructor the hook sees: lifespan =
        // rand % 3 + 3. The engine's draw stream counts that draw itself (ShipConstructionDraws); the body keeps the
        // middle value until the deployed actor rolls its own.
        if (body.type.type == DroneBlueprint.DroneType.SHIP_REPAIR && body.storedLifespan == Int.MAX_VALUE) body.storedLifespan = 4
        if (body.system != null) constructTargets(ship, body)
    }

    /**
     * SpaceManager::OnLoop (0x41f3c0) drone part, run before the projectile loop and the ships (WorldManager::OnLoop
     * 0x61b930): every space drone in SpaceManager::drones runs its OnLoop in arrival order (both ships' drones
     * interleaved); then one new arrival per ship joins the list. The shots the drones made wait in [pendingShots]
     * until the projectile loop has run ([afterProjectiles]), as SpaceManager collects them with GetNextProjectile
     * after that loop. Crew drones (and boarder pods, whose flight is a projectile here) keep their ship's update.
     */
    @JvmStatic
    fun spaceLoop(game: InGameState, dt: Float) {
        val present = listOfNotNull(game.player, game.enemy, game.browserRetainedEnemy).distinct()
        // Bodies of ships that left play (a destroyed or departed enemy, the last run's ships) are dropped.
        fun gone(ship: Ship) = ship.sys !== game || (ship in lastPresent && ship !in present)
        shipDrones.keys.removeAll { gone(it) }
        arrivals.keys.removeAll { gone(it) }
        hackingBodies.keys.removeAll { h -> h.room?.ship?.let { gone(it) } != false }
        lastPresent.clear(); lastPresent.addAll(present)
        for (body in standalone.toList()) {
            val owner = body.standaloneOwner
            if (owner == null || owner !in present) { standalone.remove(body); body.instance?.removeInstance() }
        }
        space.removeAll { body -> body.ownerShip.let { it == null || it !in present } }
        // SpaceManager::AddShip (0x41d3b0) appends all of a ship's space drones at once. A new location
        // (WorldManager::ClearLocation, then the location's ship and then the player: 0x6183a0, 0x61ab80) rebuilds
        // the list as [the enemy's drones, the player's drones]; a ship that turns up later is appended. Measured on
        // native 1.6.14 (drone-order probe: the enemy drone first, then the player's, all in the first frame).
        val beacon: Any? = game.currentBeacon
        val newLocation = beacon !== lastBeacon
        lastBeacon = beacon
        val added = present.filter { it !in shownShips }
        if (newLocation || added.isNotEmpty()) {
            val order = if (newLocation) present.filter { it !== game.player } + listOfNotNull(game.player) else added
            if (newLocation) space.clear()
            for (ship in order) {
                for (body in shipDrones[ship] ?: emptyList()) if (body !in space) space += body
                arrivals[ship]?.clear()
            }
        }
        shownShips.clear(); shownShips.addAll(present)
        if (dt <= 0f) return
        trace?.let { t -> t.frame++; t.out.append(" | S"); t.checkShots("ship") }
        // A body that is already flying while still queued (equipped and launched before its arrival turn, as a
        // loaded save restores them) joins now, so a save/load round trip does not hold a flying drone back.
        for (ship in present) arrivals[ship]?.let { queue ->
            queue.removeAll { body -> (body.instance != null).also { if (it) space += body } }
        }
        val before = present.map { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<IProjectile, Boolean>()).apply { addAll(it.projectiles) } }
        trace?.let { t -> for (body in space) t.out.append(" D").append(body.shipId) }
        for (body in space.toList()) {
            val actor = body.instance ?: continue
            // A drone system's crew-type actors (boarders' pawns, repair/battle drones) keep the system's update;
            // a body without a system (CreateSpaceDrone) has nothing else to run it.
            if ((actor is AbstractExternalDrone || body.system == null) && body in space) actor.update(dt)
            // The shots this drone made wait, with their drone, for the GetNextProjectile pass.
            present.forEachIndexed { i, ship ->
                val made = ship.projectiles.filter { it !in before[i] }
                if (made.isEmpty()) return@forEachIndexed
                ship.projectiles.removeAll { p -> made.any { it === p } }
                for (p in made) { pendingShots += Triple(body, ship, p); traceShot(p) }
            }
        }
        for (ship in present) {
            val queue = arrivals[ship] ?: continue
            if (queue.isNotEmpty()) space += queue.removeAt(0)
        }
    }

    /**
     * Diagnostics (drone-order replay): the letters the native 1.6.14 drone-order probe logs. A frame starts with S,
     * then D<ship> per body in SpaceManager::drones order, C per drone shot, M once the projectile loop is over (the
     * ships update next), L/R<ship> per render pass and drone. Each drone shot's first move is reported with the
     * phase it moved in ("projectiles": the next frame's projectile loop, as native; "ship": a ship update).
     */
    class Trace {
        val out = StringBuilder()
        var frame = 0
        private val shots = java.util.IdentityHashMap<IProjectile, Pair<Int, xyz.znix.xftl.math.ConstFPoint>>()
        val firstMoves = ArrayList<String>()
        val beams = ArrayList<String>()
        /** Drone shots at creation: frame, drone type, the shot's start and the drone's position. */
        val starts = ArrayList<String>()
        fun track(p: IProjectile) { shots[p] = frame to xyz.znix.xftl.math.ConstFPoint(p.position.xf, p.position.yf) }
        fun checkShots(phase: String) {
            val it = shots.entries.iterator()
            while (it.hasNext()) {
                val (p, made) = it.next().let { e -> e.key to e.value }
                if (p.position.xf != made.second.xf || p.position.yf != made.second.yf) {
                    firstMoves += "${frame - made.first}:$phase"; it.remove()
                }
            }
        }
    }

    @JvmField var trace: Trace? = null

    /** Diagnostics: the next space loop starts a new location (the console LOAD path's ClearLocation). */
    fun forceNewLocation() { lastBeacon = Any() }

    /** Shots the space drones made this frame, as SpaceManager::OnLoop's GetNextProjectile pass collects them. */
    private val pendingShots = ArrayList<Triple<NativeDrone, Ship, IProjectile>>()

    private fun traceShot(p: IProjectile) = trace?.let { t ->
        t.out.append(" C")
        if (p is xyz.znix.xftl.weapons.BeamProjectile) t.beams += "${t.frame} t1 ${p.motion.target1.xf},${p.motion.target1.yf} t2 ${p.motion.target2.xf},${p.motion.target2.yf}"
    }

    /** After the projectile loop: the drones' shots enter space (they first move next frame). */
    @JvmStatic
    fun afterProjectiles(game: InGameState) {
        trace?.let { t -> t.checkShots("projectiles"); t.out.append(" N"); for ((_, _, p) in pendingShots) t.track(p) }
        val present = listOfNotNull(game.player, game.enemy, game.browserRetainedEnemy).distinct()
        // SpaceManager's GetNextProjectile pass, drone by drone in SpaceManager::drones order: the shots a drone's
        // loop made (held above), then a combat drone's shot and next target (CombatDrone.nativeNextProjectile) or a
        // defence drone's laser (DefenceDrone.nativeNextProjectile).
        for (body in space.toList()) {
            val held = pendingShots.filter { it.first === body }
            for ((_, ship, p) in held) if (ship.sys === game) ship.projectiles += p
            pendingShots.removeAll { it.first === body }
            val inst = body.instance
            if (inst !is CombatDrone && inst !is DefenceDrone) continue
            val before = present.map { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<IProjectile, Boolean>()).apply { addAll(it.projectiles) } }
            if (inst is CombatDrone) inst.nativeNextProjectile() else (inst as DefenceDrone).nativeNextProjectile()
            trace?.let { t -> present.forEachIndexed { i, ship -> for (p in ship.projectiles) if (p !in before[i]) {
                traceShot(p); t.track(p)
                val dp = (inst as AbstractExternalDrone).flightController.position
                t.starts += "${t.frame} ${if (inst is DefenceDrone) "defence" else "combat"} ${p.position.xf},${p.position.yf} drone ${dp.xf},${dp.yf}"
            } } }
        }
        for ((_, ship, p) in pendingShots) if (ship.sys === game) ship.projectiles += p
        pendingShots.clear()
        trace?.out?.append(" M")
    }

    /** The drone actors the space loop runs (Drones.update leaves them out while time moves). */
    fun loopsInSpace(info: NativeDrone): Boolean = (info.instance is AbstractExternalDrone || info.system == null) && info in space

    /** SpaceManager's render pass walks SpaceManager::drones: the drones around a ship draw in arrival order. */
    @JvmStatic
    fun renderOrder(drones: List<AbstractExternalDrone>): List<AbstractExternalDrone> {
        trace?.let { t -> t.out.append(" L"); for (d in drones) t.out.append(" R").append(d.body?.shipId ?: -1) }
        if (drones.size < 2) return drones
        fun index(d: AbstractExternalDrone): Int { val b = d.body ?: return Int.MAX_VALUE; val i = space.indexOf(b); return if (i < 0) Int.MAX_VALUE else i }
        return drones.sortedBy { index(it) }
    }

    private val lastPresent = ArrayList<Ship>()
    private val shownShips = ArrayList<Ship>()
    private var lastBeacon: Any? = null

    private val hackingBodies = java.util.IdentityHashMap<xyz.znix.xftl.systems.Hacking, HackingNativeDrone>()

    /** HackingSystem::drone: the hacking system's embedded HackingDrone, one body per system. */
    @JvmStatic
    fun hackingBody(h: xyz.znix.xftl.systems.Hacking, ship: Ship? = null): HackingNativeDrone = hackingBodies.getOrPut(h) {
        val owner = ship ?: h.room!!.ship
        val bp = owner.sys.blueprintManager["DRONE_HACKING"] as DroneBlueprint
        HackingNativeDrone(h, bp).also { it.standaloneOwner = owner; it.joined = true; it.constructed = true }
    }

    /**
     * ShipManager::AddSystem for the hacking system: the system's HackingDrone (made by the HackingSystem
     * constructor, CONSTRUCT_SPACEDRONE) joins ShipManager::spaceDrones and the arrival queue.
     */
    @JvmStatic
    fun joinHacking(h: xyz.znix.xftl.systems.Hacking, ship: Ship) {
        if (!xyz.znix.xftl.hs.HS.active || h in hackingBodies) return
        val bp = ship.sys.blueprintManager["DRONE_HACKING"] as? DroneBlueprint ?: return
        val body = HackingNativeDrone(h, bp).also { it.standaloneOwner = ship }
        hackingBodies[h] = body
        join(ship, body)
    }

    /**
     * The Lua identity of a drone object: an actor is its body (made on first use for an actor without one), the
     * hacking system's collision view is the system's HackingDrone body.
     */
    @JvmStatic
    fun canon(o: Any): Any = when (o) {
        is AbstractDrone -> o.luaBody
        is xyz.znix.xftl.hs.weapons.HsHackingDroneCollisionBody -> hackingBody(o.hackingSystem)
        else -> o
    }

    /** A new game or a loaded save: nothing from the last run stays in space. */
    @JvmStatic
    fun reset() {
        shipDrones.clear(); arrivals.clear(); space.clear(); standalone.clear(); hackingBodies.clear(); pendingShots.clear()
        shownShips.clear(); lastBeacon = null
    }
}
