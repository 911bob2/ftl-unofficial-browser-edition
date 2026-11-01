// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/System_Extend.cpp
// (CONSTRUCT_SHIP_SYSTEM, the system box xOffset defaults) and lua/modules/hyperspace.i (ShipSystem / Repairable members),
// with the native field initialisers of FTL 1.6.9's ShipSystem / Repairable constructors and ShipSystem::OnLoop.
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaJs
import xyz.znix.xftl.hs.lua.LuaPush
import xyz.znix.xftl.hs.lua.LuaStruct
import xyz.znix.xftl.hs.lua.LuaStructs
import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * The native ShipSystem fields the engine keeps no state for, as HS's Lua reads and writes them. Persistent ones hold
 * the 1.6.9 constructor's values (the system id switch of ShipSystem::constructor); per-frame ones ([frame]) are a
 * script's write that holds until the system's next loop, as ShipManager::OnLoop and ShipSystem::OnLoop recompute or
 * clear the native field every frame (bOccupied, bFriendlies, bManned, damagedLastFrame, ...).
 */
class HsSystemState(@JvmField val system: AbstractSystem) {
    private val id = nativeSystemId(system)

    /** Repairable::fDamage / fMaxDamage: the constructor passes 150 (Repairable(150.0, roomId, shipId)). */
    @JvmField var fDamage = 0f
    @JvmField var fMaxDamage = 150f

    /** Selectable::selectedState (SetSelected / GetSelected). */
    @JvmField var selected = 0

    /** The native constructor's powerState.first / level while CONSTRUCT_SHIP_SYSTEM runs (null = the engine's values). */
    @JvmField var ctorPower: Int? = null
    @JvmField var ctorLevel: Int? = null

    /** The ship of a Shields object outside the rooms ([HsDetachedShields]); null for an installed system. */
    @JvmField var detachedShip: xyz.znix.xftl.Ship? = null
    /** Set while [HsDetachedShields.take] hands this object to a room (its initialise is not a constructor). */
    @JvmField var adopted = false

    /** ShipSystem::name written by a script (SetName or the attribute); null = the blueprint's type name. */
    @JvmField var name: String? = null

    /** Repairable::pLoc written by SetLocation (null = the room's top-left corner) and the roomId SetRoomId derives. */
    @JvmField var pLocX: Int? = null
    @JvmField var pLocY: Int? = null
    @JvmField var roomId: Int? = null

    /** Constructor values: switch (systemId) in ShipSystem::constructor (1.6.9 0x61bab0). */
    @JvmField var bNeedsManned = id == 6
    /** A script's bBoostable write; the artillery's value depends on the owner (iShipId != 0), so it is read late. */
    @JvmField var boostableWrite: Boolean? = null
    var bBoostable: Boolean
        get() = boostableWrite ?: when (id) {
            0, 1, 3, 6, 7, 8 -> true
            11 -> !system.isPlayerOwned()
            2, 4, 5, 9, 10, 12, 13, 14, 15 -> false
            else -> true
        }
        set(v) { boostableWrite = v }
    @JvmField var bLevelBoostable = id == 7 || id == 8
    @JvmField var iRequiredPower = when (id) { 0 -> 2; 3, 4 -> 0; else -> 1 }
    /** HS (System_Extend.cpp): a custom system id needs power unless it is a subsystem. */
    @JvmField var bNeedsPower = when (id) {
        6, 7, 8, 12 -> false
        in 0..15 -> true
        else -> !HsSystems.isSubsystemId(id)
    }
    /** ShipSystem::originalPower: the constructor's startingPower (ShipManager makes the shield system at 0). */
    @JvmField var originalPower = if (id == 0) 0 else system.energyLevels
    @JvmField var iRepairCount = 0
    @JvmField var bpCost = 0
    @JvmField var bTriggerIon = false
    @JvmField var bExploded = false
    /** A script's ShipSystem::interiorImageName write (null = the layout's image). */
    @JvmField var interiorImageName: String? = null

    /**
     * ShipSystem::flashTracker: the 0.75 s looping AnimationTracker the native box flash runs on (current_time is not
     * advanced: the engine draws no such flash).
     */
    @JvmField val flashTracker: LuaStruct = LuaStructs.create(A.T_AnimationTracker).also {
        it.values[A.A_AnimationTracker_time] = 0.75
        it.values[A.A_AnimationTracker_loop] = true
        it.values[A.A_AnimationTracker_running] = true
        it.values[A.A_AnimationTracker_done] = false
        it.values[A.A_AnimationTracker_loopDelay] = 0.0
        it.values[A.A_AnimationTracker_currentDelay] = 0.0
        it.values[A.A_AnimationTracker_current_time] = 0.0
    }

    /** Per-frame native fields a script wrote, by attribute id, until the next ShipSystem::OnLoop. */
    @JvmField val frame = HashMap<Int, Double>()

    /** ShipSystem::bUnderAttack written by a script (null = the engine's hacking drone attached); StopHacking clears it. */
    @JvmField var underAttack: Boolean? = null

    /** ShipSystem::lockTimer.currGoal / minTime / maxTime written by a script. */
    @JvmField var lockGoal: Float? = null
    @JvmField var lockMin = 5000
    @JvmField var lockMax = 5000
    @JvmField var lockLoop = true

    /** damagedLastFrame / repairedLastFrame set by PartialDamage / PartialRepair this frame. */
    @JvmField var damagedLastFrame = false
    @JvmField var repairedLastFrame = false

    /** ShipSystem::OnLoop: clears the frame flags; the scripts' per-frame writes end here. */
    fun onLoop() {
        damagedLastFrame = false
        repairedLastFrame = false
        if (frame.isNotEmpty()) frame.clear()
    }

    companion object {
        /** The system's state, made on first use. */
        @JvmStatic
        fun of(s: AbstractSystem): HsSystemState = s.hsState ?: HsSystemState(s).also { s.hsState = it }

        /**
         * ShipSystem::constructor (System_Extend.cpp hook): the state with the constructor's values, the system box
         * offset default, and CONSTRUCT_SHIP_SYSTEM(system) with the base type while scripts run.
         */
        @JvmStatic
        fun constructed(s: AbstractSystem) {
            // AddSystem(0) installing the Shields object CreateSystems made: no new constructor
            if (s.hsState?.let { it.adopted } == true) {
                s.hsState!!.adopted = false
                s.room?.ship?.let { xyz.znix.xftl.hs.lua.HsShipLuaState.noteAdded(it, s) }
                return
            }
            val st = HsSystemState(s)
            s.hsState = st
            HsSysExt.of(s).xOffset = HsSysExt.defaultOffset(nativeSystemId(s))
            if (creatingDepth > 0) { creatingList += s; return }
            // AddSystem pushes the new system to the end of vSystemList
            s.room?.ship?.let { xyz.znix.xftl.hs.lua.HsShipLuaState.noteAdded(it, s) }
            if (!HS.luaActive || !HsLua.hasInt[A.EV_CONSTRUCT_SHIP_SYSTEM]) return
            if (deferDepth > 0) pending += s else { firePending(); fire(s, true) }
        }

        /** Systems made inside [creating], in the engine's (room) order. */
        private val creatingList = ArrayList<AbstractSystem>()
        private var creatingDepth = 0

        /**
         * ShipManager::CreateSystems (HS CustomSystems.cpp) for [ship]'s starting systems, which [block]
         * installs room by room: the native call makes the Shields object first (also for a blueprint whose shields are
         * not installed, and with `shieldWithoutSystem` for one without, in room -1), then AddSystem for each id of the
         * blueprint's system list. So vSystemList takes the systems in the list's order and CONSTRUCT_SHIP_SYSTEM fires
         * for the shields first, then in that order (oracle sweep-world-y1 y-sysctor, player and generated enemy).
         */
        @JvmStatic
        fun <T> creating(ship: xyz.znix.xftl.Ship, block: () -> T): T {
            if (!HS.active) return block()
            creatingDepth++
            val result = try { block() } finally { creatingDepth-- }
            if (creatingDepth > 0) return result
            val names = ship.type.systems.map { it.systemName }
            val made = creatingList.sortedBy { names.indexOf(it.blueprint.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            creatingList.clear()
            for (s in made) xyz.znix.xftl.hs.lua.HsShipLuaState.noteAdded(ship, s)
            val shields = made.firstOrNull { it is xyz.znix.xftl.systems.Shields } ?: HsDetachedShields.create(ship)
            if (!HS.luaActive || !HsLua.hasInt[A.EV_CONSTRUCT_SHIP_SYSTEM]) return result
            if (shields != null) fire(shields, true)
            for (s in made) if (s !== shields) fire(s, true)
            return result
        }

        /** Systems constructed inside [deferConstruct], waiting for their CONSTRUCT_SHIP_SYSTEM. */
        private val pending = ArrayList<AbstractSystem>()
        private var deferDepth = 0

        /**
         * Runs [block] (an install) with CONSTRUCT_SHIP_SYSTEM held back: the native ShipSystem constructor powers the new
         * system before HS's hook fires the event (oracle sweep-world-ex1 ex-ctor: powerState 1/1), while the engine
         * powers it after the install; [xyz.znix.xftl.Ship.browserPowerNewSystem] then calls [firePending].
         */
        @JvmStatic
        fun <T> deferConstruct(block: () -> T): T {
            deferDepth++
            try { return block() } finally { deferDepth-- }
        }

        /** CONSTRUCT_SHIP_SYSTEM for every system [deferConstruct] held back, in construction order. */
        @JvmStatic
        fun firePending() {
            while (pending.isNotEmpty() && deferDepth == 0) fire(pending.removeAt(0), false)
        }

        /** Reactor bars left per ship while its systems are made (ShipManager::CreateSystems, one constructor after another). */
        private val creationReactor = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.Ship, IntArray>())

        private fun fire(s: AbstractSystem, creation: Boolean) {
            val st = of(s)
            val id = nativeSystemId(s)
            val customSub = id >= HsSystems.SYS_CUSTOM_FIRST && HsSystems.isSubsystemId(id)
            // HS's constructor hook blocks IncreasePower for a custom subsystem until after the event (power 0)
            if (customSub) st.ctorPower = 0
            if (creation) {
                // the ShipSystem constructor's values (oracle sweep-world-y1 y-sysctor): ShipManager makes the shield system
                // at level 0; the others at the blueprint's power, at least one bar; a reactor system takes bars one at a
                // time towards its target while the reactor has some left: the player's target is one bar, an AI ship's
                // the level; weapons and drones none; a vanilla subsystem runs at its level
                val ship = s.room?.ship
                val level = if (s is xyz.znix.xftl.systems.Shields) 0 else maxOf(1, s.configuration.spec.startingPower)
                st.ctorLevel = level
                if (!customSub) st.ctorPower = when {
                    s !is xyz.znix.xftl.systems.MainSystem -> level
                    s is xyz.znix.xftl.systems.Weapons || s is xyz.znix.xftl.systems.Drones || ship == null -> 0
                    else -> {
                        val left = creationReactor.getOrPut(ship) { intArrayOf(ship.type.startingReactorPower) }
                        val bars = minOf(if (ship.isPlayerShip) minOf(1, level) else level, left[0])
                        left[0] -= bars
                        bars
                    }
                }
            }
            try {
                LuaPush.obj(s, A.T_ShipSystem)
                LuaJs.fire(A.EV_CONSTRUCT_SHIP_SYSTEM, 1, 0)
                LuaJs.pop(1)
            } finally {
                st.ctorPower = null
                st.ctorLevel = null
            }
        }
    }
}

private fun AbstractSystem.isPlayerOwned(): Boolean = room?.ship?.let { xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(it) == 0 } ?: true
