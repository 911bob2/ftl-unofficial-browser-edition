// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/StatBoost.cpp (StatBoostManager, room stat boosts, timed boosts)
package xyz.znix.xftl.hs.stat

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.crew.CrewExtend
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.layout.Room

/** One room boost (HS RoomStatBoost): its RoomAnim is made with it from the first boost's definition. */
class RoomStatBoost(@JvmField var statBoost: StatBoost, @JvmField val room: Room) {
    @JvmField val anim: xyz.znix.xftl.hs.render.HsRoomAnims.Anim? =
        statBoost.def.roomAnim?.let { xyz.znix.xftl.hs.render.HsRoomAnims.make(room, it) }
}

/**
 * HS `StatBoostManager`: once per frame (WorldManager::OnLoop/PauseLoop, before the world updates) it gathers every
 * boost in play by stat - room boosts, augment boosts, every functional crew member's outgoing boosts and the boosts
 * those provide recursively - and bumps [statCacheFrame], which invalidates every crew member's stat cache.
 */
object StatBoostManager {
    @JvmField var statCacheFrame = 1
    @JvmField val statBoosts = HashMap<CrewStat, ArrayList<StatBoost>>()
    @JvmField val animBoosts = ArrayList<StatBoost>()
    @JvmField val dangerBoosts = ArrayList<StatBoost>()

    private var playerShip: Ship? = null
    private var enemyShip: Ship? = null
    private val checkingCrewList = ArrayList<AbstractCrew>()
    private val recursiveStackCount = xyz.znix.xftl.hs.lua.HsRunMaps.run(HashMap<AbstractCrew, HashMap<Int, Int>>())

    /** Room boosts per room (HS Room_Extend::statBoosts). */
    @JvmField val roomBoosts = xyz.znix.xftl.hs.lua.HsRunMaps.run(HashMap<Room, ArrayList<RoomStatBoost>>())

    /**
     * Augment stat boosts of a ship (CustomAugmentManager, ships workstream): (definitions, stack count) for every
     * augment the ship has, in HS's map order. Default: none.
     */
    @JvmField var augmentBoosts: (Ship) -> List<Pair<List<StatBoostDefinition>, Int>> = { emptyList() }

    fun clear() {
        statBoosts.clear(); animBoosts.clear(); dangerBoosts.clear()
    }

    /** New game / load / restart (HS hooks on WorldManager::CreateNewGame/LoadGame/Restart). */
    fun reset() {
        clear()
        statCacheFrame++
        roomBoosts.clear()
    }

    private fun add(b: StatBoost) {
        statBoosts.getOrPut(b.def.stat) { ArrayList() }.add(b)
        if (b.def.boostAnim.isNotEmpty()) animBoosts.add(b)
        if (b.def.dangerRating != -1f) dangerBoosts.add(b)
    }

    private fun systemRooms(ship: Ship?, system: String, into: MutableList<Int>, all: Boolean) {
        ship ?: return
        fun room(id: Int) = xyz.znix.xftl.hs.lua.LuaShipApi.system(ship, id)?.room?.id ?: -1
        if (all) {
            for (i in 0 until 15) into.add(room(i))
            into.add(room(20))
            for (i in StatBoostDefs.customSystemIds()) into.add(room(i))
        } else {
            into.add(room(StatBoostDefs.systemId(system)))
        }
    }

    /** HS fills both ships' lists; its enemy-side `all` reads the player ship for temporal/custom systems. */
    private fun fillSystemRooms(b: StatBoost) {
        for (system in b.def.systemList) {
            val all = system == "all"
            if (playerShip != null) systemRooms(playerShip, system, b.sourceRoomIds0, all)
            if (enemyShip != null) {
                if (all) {
                    val ship = enemyShip!!
                    for (i in 0 until 15) b.sourceRoomIds1.add(xyz.znix.xftl.hs.lua.LuaShipApi.system(ship, i)?.room?.id ?: -1)
                    val p = playerShip
                    b.sourceRoomIds1.add(p?.let { xyz.znix.xftl.hs.lua.LuaShipApi.system(it, 20)?.room?.id } ?: -1)
                    for (i in StatBoostDefs.customSystemIds()) b.sourceRoomIds1.add(p?.let { xyz.znix.xftl.hs.lua.LuaShipApi.system(it, i)?.room?.id } ?: -1)
                } else {
                    systemRooms(enemyShip, system, b.sourceRoomIds1, false)
                }
            }
        }
    }

    private fun createAugmentBoost(def: StatBoostDefinition, shipId: Int, nStacks: Int) {
        val b = StatBoost(def)
        b.iStacks = nStacks
        b.sourceShipId = shipId
        b.crewSourceId = 0
        if (def.systemList.isNotEmpty()) fillSystemRooms(b)
        add(b)
        createRecursiveBoosts(b, nStacks)
    }

    fun createCrewBoost(def: StatBoostDefinition, otherCrew: AbstractCrew, ex: CrewExtend, nStacks: Int) {
        val b = StatBoost(def)
        b.iStacks = nStacks
        b.crewSource = otherCrew
        b.crewSourceId = ex.selfId
        createCrewBoost(b, otherCrew)
    }

    private fun createCrewBoost(src: StatBoost, otherCrew: AbstractCrew) {
        val b = src.copy()
        b.sourceShipId = HsCrew.shipId(otherCrew)
        if (b.def.systemList.isNotEmpty()) fillSystemRooms(b)
        add(b)
        createRecursiveBoosts(b, 1)
    }

    private fun createRecursiveBoosts(b: StatBoost, nStacks: Int, noCheck: Boolean = false) {
        if (b.def.providedStatBoosts.isEmpty()) return
        for (rc in checkingCrewList) {
            val ex = HsCrew.ex(rc)
            if (noCheck || ex.boostCheck(b)) {
                var stacks = nStacks
                if (b.def.stackId != 0) {
                    val counts = recursiveStackCount.getOrPut(rc) { HashMap() }
                    val current = counts[b.def.stackId] ?: 0
                    val max = b.def.maxStacks - current
                    if (max <= 0) continue
                    if (stacks > max) stacks = max
                    counts[b.def.stackId] = current + stacks
                }
                for (d in b.def.providedStatBoosts) createCrewBoost(d, rc, ex, stacks)
            }
        }
    }

    private fun addRoomStatBoosts(ship: Ship) {
        for (room in ship.rooms) {
            val list = roomBoosts[room] ?: continue
            for (rb in list) add(rb.statBoost)
        }
    }

    /** StatBoostManager::OnLoop. */
    @JvmStatic
    fun onLoop(game: InGameState) {
        clear()
        playerShip = game.player
        enemyShip = game.enemy
        playerShip?.let { s -> for (c in s.crew) if (!isDead(c)) checkingCrewList.add(c); addRoomStatBoosts(s) }
        enemyShip?.let { s -> for (c in s.crew) if (!isDead(c)) checkingCrewList.add(c); addRoomStatBoosts(s) }

        for (shipId in 0 until 2) {
            val ship = if (shipId == 0) playerShip else enemyShip
            ship ?: continue
            for ((defs, count) in augmentBoosts(ship)) {
                if (count <= 0) continue
                for (def in defs) if (def.duration == -1f) createAugmentBoost(def, shipId, count)
            }
        }

        for (other in checkingCrewList) {
            val ex = HsCrew.ex(other)
            if (functional(other)) for (b in ex.outgoingStatBoosts) {
                b.crewSource = other
                createCrewBoost(b, other)
            }
            // Apply recursive boosts from timed boosts
            val timed = ex.timedStatBoosts[CrewStat.STAT_BOOST] ?: continue
            for (b in timed) {
                if (b.def.providedStatBoosts.isEmpty()) continue
                var stacks = b.iStacks
                if (b.def.stackId != 0) {
                    val counts = recursiveStackCount.getOrPut(other) { HashMap() }
                    val current = counts[b.def.stackId] ?: 0
                    val max = b.def.maxStacks - current
                    if (max <= 0) continue
                    if (stacks > max) stacks = max
                    counts[b.def.stackId] = current + stacks
                }
                for (d in b.def.providedStatBoosts) createCrewBoost(d, other, ex, stacks)
            }
        }
        checkingCrewList.clear()
        recursiveStackCount.clear()
        statCacheFrame++
    }

    /**
     * HS `crew->bDead`: set when the dying animation has finished (the ship's loop then drops or queues the crew member,
     * so ours no longer lists it) or at once by a script's Kill. A dying crew member keeps giving its boosts until then
     * (oracle ps-c6-pw dc: a rock_crusader's REPLACE_POWER still holds 0.5 s after its health ran out).
     */
    private fun isDead(c: AbstractCrew) = c.hsKilled

    /**
     * CrewMember::Functional (1.6.9 0x720b00) is `!IsDead()`, so bDead for crew (not health), unpowered for a crew drone
     * (CrewDrone's IsDead); [CrewExtend.functional]'s health test is left out here.
     */
    private fun functional(c: AbstractCrew): Boolean {
        if (isDead(c)) return false
        val pawn = c as? xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn ?: return true
        return pawn.drone.isPowered
    }

    /** StatBoostManager::CreateTimedAugmentBoost: a timed (or persistent) boost on one crew member. */
    @JvmStatic
    fun createTimedAugmentBoost(src: StatBoost, crew: AbstractCrew) {
        val ex = HsCrew.ex(crew)
        if (!ex.boostCheck(src)) return
        val b = src.copy()
        b.timer = HsTimer()
        if (b.def.duration != -1f) b.timer.start(b.def.duration)
        val list = ex.timedStatBoosts.getOrPut(b.def.stat) { ArrayList() }
        if (b.def.duration == -1f) { // optimization for persistent stacking boosts
            for (o in list) if (o.def === b.def) {
                o.iStacks = minOf(o.iStacks + b.iStacks, b.def.maxStacks)
                return
            }
        }
        list.add(b)
    }

    // ----- room stat boosts (ShipManager_Extend::CreateRoomStatBoost) -----

    private fun createRoomStatBoost(b: StatBoost, room: Room) {
        if (HsCrew.shipIdOf(room.ship) == 0) b.sourceRoomIds0.add(room.id) else b.sourceRoomIds1.add(room.id)
        b.timer = HsTimer()
        if (b.def.duration != -1f) b.timer.start(b.def.duration)
        val list = roomBoosts.getOrPut(room) { ArrayList() }
        if (b.def.duration == -1f) {
            for (o in list) {
                val ob = o.statBoost
                if (ob.crewSourceId == b.crewSourceId && ob.sourceShipId == b.sourceShipId) {
                    if (ob.def === b.def) {
                        ob.iStacks = minOf(ob.iStacks + b.iStacks, b.def.maxStacks)
                        return
                    } else if (b.def.stackId == ob.def.stackId && b.def.priority > ob.def.priority) {
                        if (b.iStacks >= ob.def.maxStacks) {
                            o.statBoost = b
                            return
                        } else if (ob.def.maxStacks - b.iStacks < ob.iStacks) {
                            ob.iStacks = ob.def.maxStacks - b.iStacks
                        }
                    }
                }
            }
        } else {
            for (o in list) {
                val ob = o.statBoost
                if (ob.crewSourceId == b.crewSourceId && ob.sourceShipId == b.sourceShipId) {
                    if (ob.def === b.def) {
                        if (b.iStacks >= b.def.maxStacks) {
                            ob.iStacks = b.iStacks
                            ob.timer = HsTimer().also { it.copyFrom(b.timer) }
                            return
                        }
                    } else if (b.def.stackId == ob.def.stackId &&
                        ob.def.duration != -1f && ob.timer.currGoal - ob.timer.currTime <= b.def.duration &&
                        b.def.priority > ob.def.priority && b.iStacks >= ob.def.maxStacks) {
                        o.statBoost = b
                        return
                    }
                }
            }
        }
        list.add(RoomStatBoost(b, room))
    }

    @JvmStatic
    fun testRoomStatBoostSystem(def: StatBoostDefinition, room: Room): Boolean {
        if (def.systemList.isEmpty()) return true
        val sys = room.system
        if (sys != null) {
            val type = xyz.znix.xftl.ai.nativeSystemId(sys)
            for (name in def.systemList) {
                if (name == "all" || StatBoostDefs.systemId(name) == type) {
                    return def.systemRoomTarget == StatBoostDefinition.SystemRoomTarget.ALL
                }
            }
            return def.systemRoomTarget != StatBoostDefinition.SystemRoomTarget.ALL
        }
        return def.systemRoomTarget != StatBoostDefinition.SystemRoomTarget.ALL
    }

    /** CreateRoomStatBoost(def, room, nStacks, sourceShipId) (augment/weapon sources). */
    @JvmStatic
    fun createRoomStatBoost(def: StatBoostDefinition, room: Room, nStacks: Int, sourceShipId: Int) {
        val shipId = HsCrew.shipIdOf(room.ship)
        when (def.shipTarget) {
            StatBoostDefinition.ShipTarget.PLAYER_SHIP -> if (shipId != 0) return
            StatBoostDefinition.ShipTarget.ORIGINAL_SHIP, StatBoostDefinition.ShipTarget.CURRENT_ALL -> if (shipId != sourceShipId) return
            StatBoostDefinition.ShipTarget.ORIGINAL_OTHER_SHIP, StatBoostDefinition.ShipTarget.OTHER_ALL -> if (shipId == sourceShipId) return
            StatBoostDefinition.ShipTarget.ENEMY_SHIP -> if (shipId != 1) return
            else -> {}
        }
        if (!testRoomStatBoostSystem(def, room)) return
        val b = StatBoost(def)
        b.iStacks = nStacks
        b.sourceShipId = sourceShipId
        b.crewSourceId = 0
        createRoomStatBoost(b, room)
    }

    /** CreateRoomStatBoost(def, room, nStacks, crewSource). */
    @JvmStatic
    fun createRoomStatBoost(def: StatBoostDefinition, room: Room, nStacks: Int, crewSource: AbstractCrew) {
        val shipId = HsCrew.shipIdOf(room.ship)
        when (def.shipTarget) {
            StatBoostDefinition.ShipTarget.PLAYER_SHIP -> if (shipId != 0) return
            StatBoostDefinition.ShipTarget.ENEMY_SHIP -> if (shipId != 1) return
            else -> {}
        }
        if (!testRoomStatBoostSystem(def, room)) return
        val b = StatBoost(def)
        b.iStacks = nStacks
        b.crewSource = crewSource
        b.crewSourceId = HsCrew.ex(crewSource).selfId
        b.sourceShipId = HsCrew.shipId(crewSource)
        createRoomStatBoost(b, room)
    }

    /** ShipManager::OnLoop hook: room boost timers tick and expired ones go. */
    @JvmStatic
    fun updateRoomBoosts(ship: Ship, dt: Float) {
        for (room in ship.rooms) {
            val list = roomBoosts[room] ?: continue
            if (list.isEmpty()) continue
            for (rb in list) {
                rb.statBoost.timer.update(dt)
                rb.anim?.let { xyz.znix.xftl.hs.render.HsRoomAnims.update(it, dt) }
            }
            list.removeAll { it.statBoost.timer.done() }
        }
    }

    /** ShipManager::JumpArrive hook: jumpClear boosts go; timed augment boosts start again on the player's crew. */
    @JvmStatic
    fun jumpArrive(ship: Ship) {
        for (c in ship.crew) {
            val ex = c.hsExtend ?: continue
            for (list in ex.timedStatBoosts.values) list.removeAll { it.def.jumpClear }
        }
        for (room in ship.rooms) roomBoosts[room]?.removeAll { it.statBoost.def.jumpClear }
        if (HsCrew.shipIdOf(ship) != 0) return
        for ((defs, count) in augmentBoosts(ship)) {
            for (def in defs) {
                if (def.duration == -1f) continue
                val b = StatBoost(def)
                b.iStacks = count
                b.sourceShipId = 0
                for (c in ship.crew) createTimedAugmentBoost(b, c)
            }
        }
    }

    /** CrewMember constructor hook: a new crew member gets its ship's timed augment boosts. */
    @JvmStatic
    fun crewCreated(crew: AbstractCrew, ship: Ship?) {
        ship ?: return
        val shipId = HsCrew.shipIdOf(ship)
        for ((defs, count) in augmentBoosts(ship)) {
            for (def in defs) {
                if (def.duration == -1f) continue
                val b = StatBoost(def)
                b.iStacks = count
                b.sourceShipId = shipId
                createTimedAugmentBoost(b, crew)
            }
        }
    }

    /** CrewMember::Restart hook (cloned / drone redeployed): cloneClear boosts go. */
    @JvmStatic
    fun crewRestarted(ex: CrewExtend) {
        for (list in ex.timedStatBoosts.values) list.removeAll { it.def.cloneClear }
    }

    /** CrewMember::OnLoop(1000) hook: timed boosts tick and expired ones go. */
    @JvmStatic
    fun updateTimed(ex: CrewExtend, dt: Float) {
        for (list in ex.timedStatBoosts.values) {
            for (b in list) b.timer.update(dt)
            list.removeAll { it.timer.done() }
        }
    }
}
