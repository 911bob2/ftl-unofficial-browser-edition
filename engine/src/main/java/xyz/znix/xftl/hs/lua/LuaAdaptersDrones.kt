// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/modules/hyperspace.i (Drone, SpaceDrone, DefenseDrone,
// CombatDrone, BoarderPodDrone, HackingDrone, SuperShieldDrone, ShipRepairDrone, DroneSystem, DroneControl,
// DroneBlueprint, HackingSystem::drone; ShipManager drone methods) and lua/LuaLibScript.cpp (pDroneTypes /
// pSpaceDroneTypes). Member semantics follow FTL 1.6.14 (SpaceDrone constructor 0x426930, Drone::SetDestroyed
// 0x462110, DroneSystem::OnLoop 0x639a50) and the HS oracle (wp4-drone-body).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.*
import xyz.znix.xftl.hs.weapons.HsHackingDroneCollisionBody
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.Hacking
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

object LuaAdaptersDrones {
    /** SpaceDrone::_targetable (a drone as something to aim at): Targetable's own fields live here. */
    class DroneTargetable(val drone: NativeDrone) : LuaTyped {
        override val luaType get() = A.T_Targetable
        // Targetable::type: 3 for a space drone, 6 for the hacking and boarding pods (oracle wp4-drone-body).
        @JvmField var type = if (drone.type.type == DroneBlueprint.DroneType.HACKING || drone.type.type == DroneBlueprint.DroneType.BOARDER) 6.0 else 3.0
        @JvmField var hostile = true
        @JvmField var targeted = false
    }

    /** Hyperspace.App.gui.combatControl and its droneControl (the player's drone boxes). */
    object LuaCombatControl : LuaTyped { override val luaType get() = A.T_CombatControl }
    object LuaDroneControl : LuaTyped { override val luaType get() = A.T_DroneControl }

    /** An opaque `CachedImage *` / `GL_Texture *` member: the image path, for identity only. */
    class ImageRef(override val luaType: Int, val path: String) : LuaTyped

    /** The file each CachedImage member starts from. */
    private val imageSuffix = mapOf(A.A_CombatDrone_drone_image_off to "_base.png", A.A_CombatDrone_drone_image_charging to "_charged.png", A.A_CombatDrone_drone_image_on to "_on.png",
        A.A_DefenseDrone_drone_image to "_base.png", A.A_DefenseDrone_gun_image_off to "_gun.png", A.A_DefenseDrone_gun_image_charging to "_gun_charged.png", A.A_DefenseDrone_gun_image_on to "_gun_on.png",
        A.A_SuperShieldDrone_drone_image_on to "_charged.png", A.A_SuperShieldDrone_drone_image_off to "_off.png", A.A_SuperShieldDrone_drone_image_glow to "_glow.png")
    private val imageSlots = mapOf(A.A_CombatDrone_drone_image_off to 0, A.A_CombatDrone_drone_image_charging to 1, A.A_CombatDrone_drone_image_on to 2,
        A.A_DefenseDrone_drone_image to 0, A.A_DefenseDrone_gun_image_off to 1, A.A_DefenseDrone_gun_image_charging to 2, A.A_DefenseDrone_gun_image_on to 3, A.A_DefenseDrone_engine_image to 4,
        A.A_SuperShieldDrone_drone_image_on to 0, A.A_SuperShieldDrone_drone_image_off to 1, A.A_SuperShieldDrone_drone_image_glow to 2)

    /** LuaLibScript pDroneTypes / pSpaceDroneTypes: the derived type by Drone::type; repair and battle drones stay static. */
    @JvmStatic
    fun dynamicType(o: Any, staticType: Int): Int {
        if (staticType != A.T_SpaceDrone && staticType != A.T_Drone) return staticType
        val d = when (o) { is NativeDrone -> o; is AbstractDrone -> o.luaBody; else -> return staticType }
        if (!d.isSpaceDrone) return staticType
        return when (d.nativeType) {
            0 -> A.T_DefenseDrone
            1 -> A.T_CombatDrone
            4 -> A.T_BoarderPodDrone
            5 -> A.T_ShipRepairDrone
            6 -> A.T_HackingDrone
            7 -> A.T_SuperShieldDrone
            else -> staticType
        }
    }

    private fun d(o: Any?): NativeDrone = when (o) {
        is NativeDrone -> o
        is AbstractDrone -> o.luaBody
        is HsHackingDroneCollisionBody -> DroneRuntime.canon(o) as NativeDrone
        is LuaCollideableMember -> d(o.owner)
        else -> throw LuaError("expected a Drone")
    }

    private fun ext(o: Any?) = d(o).instance as? AbstractExternalDrone
    private fun game() = LuaAdapters.inGame()

    private fun targetShip(v: Any?): Ship? = when (v) {
        null -> null
        is LuaAdaptersBeam.TargetableMember -> v.ship
        is DroneTargetable -> null
        else -> null
    }

    private fun shipTargetable(ship: Ship?): Any? =
        ship?.let { LuaViews.cached(it, A.A_BeamWeapon_movingTarget) { s -> LuaAdaptersBeam.TargetableMember(s as Ship) } }

    private fun pointArg(v: Any?): ConstFPoint = when (v) {
        is PointfValue -> ConstFPoint(v.x, v.y)
        is LuaPointfMember -> ConstFPoint(v.x, v.y)
        else -> throw LuaError("expected Pointf")
    }

    private fun pushPoint(p: FPoint) = ret.obj(PointfValue(p.xf, p.yf), A.T_Pointf)

    /** A Pointf member of a drone: a live view (writes go to the drone), one userdata per (drone, member). */
    private fun pointMember(id: Int, get: (NativeDrone) -> FPoint, set: (NativeDrone, Float, Float) -> Unit) {
        LuaReg.obj(id, ObjGet { o ->
            val b = d(o)
            LuaViews.cached(b, id) { x ->
                val body = x as NativeDrone
                LuaPointfMember(FGet { get(body).xf }, FSet { set(body, it, get(body).yf) },
                    FGet { get(body).yf }, FSet { set(body, get(body).xf, it) })
            }
        }, ObjSet { o, v -> val p = pointArg(v); set(d(o), p.xf, p.yf) })
    }

    /** A Pointf member the engine does not model: kept on the body with its constructor value. */
    private fun storedPoint(id: Int, x: Float, y: Float, live: ((NativeDrone) -> FPoint?)? = null) =
        pointMember(id, { b -> live?.invoke(b) ?: b.points.getOrPut(id) { xyz.znix.xftl.math.MutFPoint(x, y) } },
            { b, px, py -> b.points.getOrPut(id) { xyz.znix.xftl.math.MutFPoint(x, y) }.set(px, py) })

    /** Live members whose last value the native object keeps after the drone is undeployed (snapshotted then). */
    private val kept = ArrayList<Pair<Int, (NativeDrone) -> Double?>>()

    private fun storedNum(id: Int, default: (NativeDrone) -> Double, live: ((NativeDrone) -> Double?)? = null,
                          keep: Boolean = false, write: ((NativeDrone, Double) -> Boolean)? = null) {
        if (keep && live != null) kept += id to live
        LuaReg.num(id, NumGet { o -> val b = d(o); live?.invoke(b) ?: b.fields[id] ?: default(b) },
            NumSet { o, v -> val b = d(o); if (write?.invoke(b, v) != true) b.fields[id] = v })
    }

    private fun storedBool(id: Int, default: Boolean, keep: Boolean = false, live: ((NativeDrone) -> Boolean?)? = null) =
        storedNum(id, { if (default) 1.0 else 0.0 }, live?.let { f -> { b -> f(b)?.let { if (it) 1.0 else 0.0 } } }, keep = keep)

    /** The actor has run at least one frame (SpaceDrone::lastLocation leaves -FLT_MAX on the first move). */
    private fun flown(b: NativeDrone): Boolean = ext(b)?.lastLocation?.let { it.xf != NONE } == true

    private val NONE = -Float.MAX_VALUE

    /** Animation members (explosion, weapon_animation, hackSparks): one struct per body, tracker.running kept live. */
    private fun animation(id: Int, spec: String?, running: (NativeDrone) -> Boolean?) {
        LuaReg.obj(id, ObjGet { o ->
            val b = d(o)
            val an = LuaViews.cached(b, id) {
                // The drone's own explosion is the pick its constructor made (GetAnimation draws once, there).
                val own = if (spec == "explosion_random") (b.instance as? xyz.znix.xftl.drones.AbstractDrone)?.explosionSpec else null
                // A drone's weapon_animation: its weapon's shot.
                val shot = if (spec == "weapon") (b.instance as? xyz.znix.xftl.drones.AbstractDrone)?.type?.weaponBlueprint?.projectile else spec
                val s = own ?: shot?.let { game()?.animations?.getOrNull(it) }
                if (s != null) LuaAnim.create(s) else LuaStructs.create(A.T_Animation).also { if (id == A.A_HackingDrone_explosion) LuaAdaptersWritesPictures.setPos(it, -1.0, -1.0) }
            }
            // An animation a script wrote runs by what the script gave it.
            if (an.values[A.A_Animation_animName] == LuaViews.cached(b, -id) { an.values[A.A_Animation_animName] ?: "" })
                running(b)?.let { r ->
                    (LuaStructs.member(an, A.A_Animation_tracker) as LuaStruct).values[A.A_AnimationTracker_running] = r
                }
            an
        })
    }

    private fun method(vararg ids: Int, f: LuaMethod) { for (id in ids) LuaReg.method(id, f) }

    private fun loopDt(): Float = LuaFps.speedFactor / 16f

    fun install() {
        NativeDrone.onActorRemoved = { b -> for ((id, live) in kept) live(b)?.let { b.fields[id] = it } }
        identity()
        drone()
        spaceDrone()
        derived()
        system()
        hacking()
        blueprint()
        shipManager()
        controls()
    }

    // ------------------------------------------------------------------ lists and identities
    private fun identity() {
        val spaceView = ListView(A.T_vector_SpaceDrone, A.T_SpaceDrone) { spaceDronesInPlay() }
        LuaReg.obj(A.A_SpaceManager_drones, ObjGet { spaceView })
        LuaReg.obj(A.A_ShipManager_spaceDrones, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ShipManager_spaceDrones) { s -> ListView(A.T_vector_SpaceDrone, A.T_SpaceDrone) { DroneRuntime.spaceDrones(s as Ship) } }
        })
        LuaReg.obj(A.A_ShipManager__targetable, ObjGet { shipTargetable(it as Ship) })
        LuaReg.obj(A.A_SpaceDrone__targetable, ObjGet { o -> LuaViews.cached(d(o), A.A_SpaceDrone__targetable) { DroneTargetable(it as NativeDrone) } })
        val tType = LuaReg.numGet[A.A_Targetable_type]; val tTypeSet = LuaReg.numSet[A.A_Targetable_type]
        LuaReg.num(A.A_Targetable_type, NumGet { o -> if (o is DroneTargetable) o.type else tType?.get(o) ?: 0.0 },
            NumSet { o, v -> if (o is DroneTargetable) o.type = v else tTypeSet?.set(o, v) })
        val tHostile = LuaReg.numGet[A.A_Targetable_hostile]; val tHostileSet = LuaReg.numSet[A.A_Targetable_hostile]
        LuaReg.num(A.A_Targetable_hostile, NumGet { o -> if (o is DroneTargetable) (if (o.hostile) 1.0 else 0.0) else tHostile?.get(o) ?: 0.0 },
            NumSet { o, v -> if (o is DroneTargetable) o.hostile = v != 0.0 else tHostileSet?.set(o, v) })
        val tTargeted = LuaReg.numGet[A.A_Targetable_targeted]; val tTargetedSet = LuaReg.numSet[A.A_Targetable_targeted]
        LuaReg.num(A.A_Targetable_targeted, NumGet { o -> if (o is DroneTargetable) (if (o.targeted) 1.0 else 0.0) else tTargeted?.get(o) ?: 0.0 },
            NumSet { o, v -> if (o is DroneTargetable) o.targeted = v != 0.0 else tTargetedSet?.set(o, v) })
    }

    /** SpaceManager::drones: the space drones of the ships in play (hacking drones included) in arrival order. */
    private fun spaceDronesInPlay(): List<Any?> = if (game() == null) emptyList() else ArrayList<Any?>(DroneRuntime.space)

    // ------------------------------------------------------------------ Drone
    private fun drone() {
        LuaReg.num(A.A_Drone_iShipId, NumGet { o -> val b = d(o); (b.fields[A.A_Drone_iShipId] ?: b.luaOwner?.let { HsWeapons.shipId(it) }?.toDouble() ?: 0.0) },
            NumSet { o, v -> d(o).fields[A.A_Drone_iShipId] = v })
        LuaReg.num(A.A_Drone_selfId, NumGet { d(it).luaSelfId.toDouble() })
        LuaReg.bool(A.A_Drone_powered, { d(it).luaPowered }, { o, v -> d(o).luaPowered = v })
        LuaReg.num(A.A_Drone_powerRequired, NumGet { d(it).powerRequired.toDouble() }, NumSet { o, v -> d(o).powerRequired = v.toInt() })
        LuaReg.bool(A.A_Drone_deployed, { d(it).luaDeployed }, { o, v -> val b = d(o); if (v != b.luaDeployed) b.setDeployed(v) })
        LuaReg.num(A.A_Drone_type, NumGet { d(it).nativeType.toDouble() }, NumSet { o, v -> d(o).nativeType = v.toInt() })
        LuaReg.obj(A.A_Drone_blueprint, ObjGet { d(it).type })
        LuaReg.bool(A.A_Drone_bDead, { d(it).bDead }, { o, v -> d(o).bDead = v })
        LuaReg.num(A.A_Drone_iBonusPower, NumGet { d(it).iBonusPower.toDouble() }, NumSet { o, v -> d(o).iBonusPower = v.toInt() })
        LuaReg.bool(A.A_Drone_poweredAtLocation, { d(it).poweredAtLocation }, { o, v -> d(o).poweredAtLocation = v })
        LuaReg.num(A.A_Drone_destroyedTimer, NumGet { d(it).destroyedTimer.toDouble() }, NumSet { o, v -> d(o).destroyedTimer = v.toFloat() })
        LuaReg.num(A.A_Drone_iHackLevel, NumGet { d(it).iHackLevel.toDouble() }, NumSet { o, v -> d(o).iHackLevel = v.toInt() })
        LuaReg.num(A.A_Drone_hackTime, NumGet { o -> val b = d(o); (b.instance?.hackTime ?: b.fields[A.A_Drone_hackTime]?.toFloat() ?: 0f).toDouble() },
            NumSet { o, v -> val b = d(o); val a = b.instance; if (a != null) a.hackTime = v.toFloat() else b.fields[A.A_Drone_hackTime] = v })

        method(A.M_Drone_OnInit) { 0 }
        method(A.M_Drone_OnLoop, A.M_SpaceDrone_OnLoop, A.M_CombatDrone_OnLoop, A.M_DefenseDrone_OnLoop,
            A.M_BoarderPodDrone_OnLoop, A.M_HackingDrone_OnLoop, A.M_SuperShieldDrone_OnLoop) {
            val b = d(arg.obj(1)); val dt = loopDt()
            if (b !is HackingNativeDrone) b.instance?.update(dt)
            0
        }
        method(A.M_Drone_OnDestroy) { 0 }
        method(A.M_Drone_SetPowered, A.M_DefenseDrone_SetPowered, A.M_BoarderPodDrone_SetPowered) { d(arg.obj(1)).luaPowered = arg.bool(2); 0 }
        method(A.M_Drone_SetInstantPowered) { d(arg.obj(1)).luaPowered = true; 0 }
        method(A.M_Drone_GetPowered) { ret.bool(d(arg.obj(1)).luaPowered) }
        method(A.M_Drone_SetCurrentShip) { d(arg.obj(1)).fields[A.A_Drone_iShipId] = arg.int(2).toDouble(); 0 }
        method(A.M_Drone_SetDeployed, A.M_SpaceDrone_SetDeployed, A.M_DefenseDrone_SetDeployed,
            A.M_BoarderPodDrone_SetDeployed, A.M_HackingDrone_SetDeployed) {
            val b = d(arg.obj(1)); if (b !is HackingNativeDrone) b.setDeployed(arg.bool(2)); 0
        }
        method(A.M_Drone_SetDestroyed, A.M_BoarderPodDrone_SetDestroyed, A.M_HackingDrone_SetDestroyed) {
            val b = d(arg.obj(1))
            if (b is HackingNativeDrone) { if (arg.bool(2)) b.hacking.hsCollisionBody?.collisionBlowUp() }
            else b.setDestroyed(arg.bool(2), arg.bool(3))
            0
        }
        method(A.M_Drone_SetHacked, A.M_BoarderPodDrone_SetHacked) { d(arg.obj(1)).iHackLevel = arg.int(2); 0 }
        method(A.M_Drone_GetDeployed) { ret.bool(d(arg.obj(1)).luaDeployed) }
        method(A.M_Drone_NeedsRoom) { ret.bool(!d(arg.obj(1)).isSpaceDrone) }
        method(A.M_Drone_SetSlot) { 0 }
        // CrewDrone::Destroyed is true while the crew drone is not out (oracle: an equipped, undeployed repair drone).
        method(A.M_Drone_Destroyed, A.M_BoarderPodDrone_Destroyed) { val b = d(arg.obj(1)); ret.bool(b.bDead || (!b.isSpaceDrone && !b.luaDeployed)) }
        method(A.M_Drone_GetWorldLocation) {
            val b = d(arg.obj(1))
            val pawn = (b.instance as? AbstractIndoorsDrone)?.pawn
            val p = if (pawn != null) pawn.currentSlotPoint.shipPoint.let { PointValue(it.x, it.y) } else b.luaLocation().let { PointValue(it.xf.toInt(), it.yf.toInt()) }
            ret.obj(p, A.T_Point)
        }
        method(A.M_Drone_SetWorldLocation) { 0 }
        method(A.M_Drone_GetDroneSlot) {
            val b = d(arg.obj(1))
            val pawn = (b.instance as? AbstractIndoorsDrone)?.pawn
            val slot = LuaStructs.create(A.T_Slot)
            val point = pawn?.currentSlotPoint
            slot.values[A.A_Slot_roomId] = (point?.room?.id ?: -1).toDouble()
            slot.values[A.A_Slot_slotId] = (point?.let { it.room.pointToSlot(it) } ?: -1).toDouble()
            ret.obj(slot, A.T_Slot)
        }
        method(A.M_Drone_GetDroneHealth) {
            val b = d(arg.obj(1))
            // CrewDrone::GetDroneHealth is its crew health (a fresh repair drone reports its 25); space drones 1.
            val h = if (b.isSpaceDrone) 1 else ((b.instance as? AbstractIndoorsDrone)?.pawn?.health?.toInt() ?: crewMaxHealth(b))
            ret.num(h)
        }
        method(A.M_Drone_GetRequiredPower) { ret.num(d(arg.obj(1)).requiredPower) }
        method(A.M_Drone_RenderIcon) {
            val b = d(arg.obj(1)); val g = game()
            if (g != null && HsLua.graphics != null) b.type.drawIconUI(g, xyz.znix.xftl.math.ConstPoint.ZERO)
            0
        }
        method(A.M_Drone_GetName) { val b = d(arg.obj(1)); ret.str(game()?.let { b.type.translateShort(it) } ?: b.type.name) }
        method(A.M_Drone_CanBeDeployed, A.M_CombatDrone_CanBeDeployed, A.M_BoarderPodDrone_CanBeDeployed, A.M_HackingDrone_CanBeDeployed) {
            ret.bool(canBeDeployed(d(arg.obj(1))))
        }
        method(A.M_Drone_RecallOnJump, A.M_SpaceDrone_RecallOnJump, A.M_BoarderPodDrone_RecallOnJump, A.M_HackingDrone_RecallOnJump) {
            ret.bool(d(arg.obj(1)).isSpaceDrone)
        }
        method(A.M_Drone_CanBeRecovered, A.M_SpaceDrone_CanBeRecovered, A.M_BoarderPodDrone_CanBeRecovered, A.M_HackingDrone_CanBeRecovered) {
            val b = d(arg.obj(1))
            ret.bool(b.isSpaceDrone && b.type.type != DroneBlueprint.DroneType.BOARDER && b.type.type != DroneBlueprint.DroneType.HACKING)
        }
        // SaveState/LoadState write to an FTL save file descriptor; scripts have none to pass (no-ops).
        method(A.M_Drone_SaveState, A.M_SpaceDrone_SaveState, A.M_CombatDrone_SaveState, A.M_BoarderPodDrone_SaveState,
            A.M_HackingDrone_SaveState, A.M_SuperShieldDrone_SaveState) { 0 }
        method(A.M_Drone_LoadState, A.M_SpaceDrone_LoadState, A.M_CombatDrone_LoadState, A.M_BoarderPodDrone_LoadState,
            A.M_HackingDrone_LoadState, A.M_SuperShieldDrone_LoadState) { 0 }
        method(A.M_Drone_BlowUp, A.M_SpaceDrone_BlowUp, A.M_BoarderPodDrone_BlowUp) {
            val b = d(arg.obj(1)); val silent = arg.bool(2)
            when {
                b is HackingNativeDrone -> b.hacking.hsCollisionBody?.collisionBlowUp()
                b.instance != null -> b.instance!!.blowUp(silent)
                else -> b.setDestroyed(true, true)
            }
            0
        }
        method(A.M_Drone_GetStunned, A.M_SpaceDrone_GetStunned) { ret.bool(stunned(d(arg.obj(1)))) }
    }

    private fun crewMaxHealth(b: NativeDrone): Int = when (b.type.type) {
        DroneBlueprint.DroneType.REPAIR -> 25
        DroneBlueprint.DroneType.BATTLE -> 150
        else -> 1
    }

    /** Drone::GetStunned is iHackLevel > 1; SpaceDrone::GetStunned (0x756a10) adds ionStun > 0. */
    private fun stunned(b: NativeDrone): Boolean = b.iHackLevel > 1 || (b.isSpaceDrone && b.luaIonStun > 0f)

    /** CanBeDeployed: combat-type drones need their target (CombatDrone::CanBeDeployed = HasTarget), boarders a hostile ship. */
    private fun canBeDeployed(b: NativeDrone): Boolean = when (b.type.type) {
        DroneBlueprint.DroneType.COMBAT, DroneBlueprint.DroneType.SHIP_REPAIR -> hasTarget(b)
        DroneBlueprint.DroneType.BOARDER, DroneBlueprint.DroneType.HACKING -> b.luaOwner?.let { it.sys.getEnemyOf(it) } != null
        else -> true
    }

    /** SpaceDrone::HasTarget: a combat drone has a weapon target; a defence drone has something in its sights. */
    private fun hasTarget(b: NativeDrone): Boolean = when (val a = b.instance) {
        is DefenceDrone -> a.aimPoint != null
        is CombatDrone -> alive(b.weaponTarget) || (a.targetShip === a.ownerShip.sys.getEnemyOf(a.ownerShip) && alive(a.targetShip))
        else -> when (b.type.type) {
            DroneBlueprint.DroneType.COMBAT, DroneBlueprint.DroneType.SHIP_REPAIR -> alive(b.weaponTarget)
            else -> false
        }
    }

    /** A weapon target counts while its ship is in play and not destroyed (oracle wp4-drone-fight: false once bDestroyed). */
    /**
     * A target ship counts while it is in the fight, destroyed or not: oracle wp4-drone-fight-system reads
     * HasTarget/CanBeDeployed true until the drone system recalls the drone 181 ticks after the hull reached 0
     * (ShipManager.bDestroyed is set 2 ticks after it); the engine drops the ship from the fight at that point.
     */
    private fun alive(s: Ship?): Boolean = s != null && s.sys.isShipPresent(s)

    /** SpaceDrone::ValidTarget (0x422bb0): deployed, powered, not stunned and not exploding. */
    private fun validTarget(b: NativeDrone): Boolean =
        b.luaDeployed && b.luaPowered && !stunned(b) && !b.luaExploding && !b.bDead

    // ------------------------------------------------------------------ SpaceDrone
    private fun spaceDrone() {
        LuaReg.num(A.A_SpaceDrone_currentSpace, NumGet { d(it).luaCurrentSpace.toDouble() }, NumSet { o, v -> d(o).fields[A.A_SpaceDrone_currentSpace] = v })
        LuaReg.num(A.A_SpaceDrone_destinationSpace, NumGet { d(it).destinationSpaceStored.toDouble() }, NumSet { o, v -> d(o).destinationSpaceStored = v.toInt() })
        pointMember(A.A_SpaceDrone_currentLocation, { it.luaLocation() }, { b, x, y -> b.luaSetLocation(x, y) })
        pointMember(A.A_SpaceDrone_lastLocation, { b -> ext(b)?.lastLocation ?: b.points.getOrPut(A.A_SpaceDrone_lastLocation) { xyz.znix.xftl.math.MutFPoint(NONE, NONE) } },
            { b, x, y -> (ext(b)?.lastLocation ?: b.points.getOrPut(A.A_SpaceDrone_lastLocation) { xyz.znix.xftl.math.MutFPoint(NONE, NONE) }).set(x, y) })
        pointMember(A.A_SpaceDrone_destinationLocation, { b -> destination(b) }, { b, x, y -> setDestination(b, x, y) })
        storedPoint(A.A_SpaceDrone_pointTarget, NONE, NONE)
        storedPoint(A.A_SpaceDrone_targetLocation, NONE, NONE) { b ->
            if (!flown(b)) null else when (val a = b.instance) {
                is DefenceDrone -> a.aimPoint
                is CombatDrone -> a.targetRoomOrNull?.pixelCentre?.let { ConstFPoint(it.x.toFloat(), it.y.toFloat()) }
                else -> null
            }
        }
        storedPoint(A.A_SpaceDrone_targetSpeed, NONE, NONE) { b -> (b.instance as? DefenceDrone)?.takeIf { it.aimPoint != null }?.aimVelocity }
        pointMember(A.A_SpaceDrone_speedVector, { b -> ext(b)?.speedVector ?: b.points.getOrPut(A.A_SpaceDrone_speedVector) { xyz.znix.xftl.math.MutFPoint(0f, 0f) } },
            { b, x, y -> (ext(b)?.speedVector ?: b.points.getOrPut(A.A_SpaceDrone_speedVector) { xyz.znix.xftl.math.MutFPoint(0f, 0f) }).set(x, y) })
        storedPoint(A.A_SpaceDrone_beamCurrentTarget, 0f, 0f)
        storedPoint(A.A_SpaceDrone_beamFinalTarget, 0f, 0f)
        animation(A.A_SpaceDrone_explosion, "explosion_random") { it.luaExploding }
        animation(A.A_SpaceDrone_weapon_animation, "weapon") { true }
        animation(A.A_SpaceDrone_hackSparks, "stun_spark_big") { b -> b.luaDeployed && stunned(b) }
        LuaReg.obj(A.A_SpaceDrone_weaponTarget, ObjGet { shipTargetable(d(it).weaponTarget) }, ObjSet { o, v -> d(o).weaponTarget = targetShip(v) })
        LuaReg.obj(A.A_SpaceDrone_movementTarget, ObjGet { shipTargetable(d(it).movementTarget) }, ObjSet { o, v -> d(o).movementTarget = targetShip(v) })
        storedBool(A.A_SpaceDrone_poweredLastFrame, false) { b -> ext(b)?.poweredLastFrame }
        storedBool(A.A_SpaceDrone_deployedLastFrame, false) { b -> ext(b)?.deployedLastFrame }
        // bFire and pause keep their last values once the drone is undeployed (oracle: a rebuilt defence drone).
        storedBool(A.A_SpaceDrone_bFire, false, keep = true) { b -> (b.instance as? DefenceDrone)?.fireReady }
        // A drone that never stops at a destination counts its pause down to zero on its first loop.
        storedNum(A.A_SpaceDrone_pause, { -1.0 }, { b -> if (b.instance !is CombatDrone && flown(b)) 0.0 else null }, keep = true)
        // A beam drone's sweep (GetNextProjectile 0x429ee0 sets 0.5; the loop counts it down).
        storedNum(A.A_SpaceDrone_additionalPause, { 0.0 }, { b -> (b.instance as? CombatDrone)?.sweepTimer?.takeIf { it > 0f }?.toDouble() })
        // The constructor stores GetWeaponCooldown: the blueprint's cooldown, a shield drone's recharge table.
        storedNum(A.A_SpaceDrone_weaponCooldown, { b ->
            val owner = b.ownerShip
            if (b.type.type == DroneBlueprint.DroneType.SHIELD && owner != null) ShieldDrone.cooldownFor(b.type, owner).toDouble()
            else (b.type.cooldown ?: 0) / 1000.0
        }, { b ->
            when (val a = b.instance) {
                is DefenceDrone -> a.cooldown.toDouble()
                is ShieldDrone -> a.rechargeRemaining.toDouble()
                else -> null
            }
        }) { b, v ->
            when (val a = b.instance) {
                is DefenceDrone -> { a.cooldown = v.toFloat(); true }
                is ShieldDrone -> { a.rechargeRemaining = v.toFloat(); true }
                else -> false
            }
        }
        storedNum(A.A_SpaceDrone_current_angle, { b -> b.nativeAngle.toDouble() }, { b ->
            when (val fc = ext(b)?.flightController) {
                is OrbitFlightController -> Math.toDegrees(fc.theta.toDouble())
                is CombatFlightController -> Math.toDegrees(fc.currentDestAngle.toDouble())
                else -> null
            }
        })
        storedNum(A.A_SpaceDrone_aimingAngle, { 0.0 }, { b -> (b.instance as? DefenceDrone)?.let { Math.toDegrees(it.aimAngle.toDouble()) }
            ?: (b.instance as? CombatDrone)?.nativeAimingAngle?.toDouble() }, keep = true)
        storedNum(A.A_SpaceDrone_lastAimingAngle, { 0.0 }, { b -> (b.instance as? CombatDrone)?.nativeLastAimingAngle?.toDouble() }, keep = true)
        storedNum(A.A_SpaceDrone_desiredAimingAngle, { -1.0 }, { b -> (b.instance as? DefenceDrone)?.let { if (it.goalAngle < 0f) -1.0 else Math.toDegrees(it.goalAngle.toDouble()) }
            ?: (b.instance as? CombatDrone)?.nativeDesiredAimingAngle?.toDouble() }, keep = true)
        LuaReg.obj(A.A_SpaceDrone_message, ObjGet { null }, ObjSet { _, _ -> })
        // A drone without a weapon has GetWeaponBlueprint("")'s default blueprint (oracle: non-nil, name '').
        LuaReg.obj(A.A_SpaceDrone_weaponBlueprint, ObjGet { d(it).type.weaponBlueprint ?: DefaultWeaponBlueprint })
        LuaReg.num(A.A_SpaceDrone_lifespan, NumGet { d(it).lifespan.toDouble() }, NumSet { o, v -> d(o).lifespan = v.toInt() })
        storedBool(A.A_SpaceDrone_bLoadedPosition, false)
        storedBool(A.A_SpaceDrone_bDisrupted, false)
        storedNum(A.A_SpaceDrone_hackAngle, { 0.0 }, { b -> (b.instance as? CombatDrone)?.nativeHackAngle?.toDouble() }, keep = true)
        LuaReg.num(A.A_SpaceDrone_ionStun, NumGet { d(it).luaIonStun.toDouble() }, NumSet { o, v -> d(o).luaIonStun = v.toFloat() })
        storedNum(A.A_SpaceDrone_beamSpeed, { 0.0 }, { b -> (b.instance as? CombatDrone)?.nativeBeamSpeed?.toDouble() }, keep = true)

        method(A.M_SpaceDrone_PickDestination, A.M_CombatDrone_PickDestination, A.M_DefenseDrone_PickDestination,
            A.M_BoarderPodDrone_PickDestination, A.M_HackingDrone_PickDestination) { 0 }
        method(A.M_SpaceDrone_PickTarget, A.M_CombatDrone_PickTarget, A.M_DefenseDrone_PickTarget) {
            (d(arg.obj(1)).instance as? CombatDrone)?.luaPickTarget(); 0
        }
        method(A.M_SpaceDrone_HasTarget, A.M_CombatDrone_HasTarget, A.M_DefenseDrone_HasTarget, A.M_SuperShieldDrone_HasTarget) {
            ret.bool(hasTarget(d(arg.obj(1))))
        }
        method(A.M_SpaceDrone_ValidTarget) { ret.bool(validTarget(d(arg.obj(1)))) }
        method(A.M_SpaceDrone_GetWeaponCooldown, A.M_SuperShieldDrone_GetWeaponCooldown) {
            val b = d(arg.obj(1))
            ret.num((b.instance as? ShieldDrone)?.cooldown() ?: ((b.type.cooldown ?: 0) / 1000f))
        }
        method(A.M_SpaceDrone_RandomizeStartingPosition, A.M_BoarderPodDrone_RandomizeStartingPosition,
            A.M_HackingDrone_RandomizeStartingPosition) { 0 }
        method(A.M_SpaceDrone_HideUnderOwner, A.M_BoarderPodDrone_HideUnderOwner, A.M_HackingDrone_HideUnderOwner) {
            // HackingDrone::HideUnderOwner (0x75b960) and BoarderPodDrone::HideUnderOwner (0x762ce0) return true;
            // SpaceDrone's (0x756b10) false. SpaceManager's render pass (0x41a8b0) then draws them under their owner.
            val t = d(arg.obj(1)).type.type
            ret.bool(t == DroneBlueprint.DroneType.HACKING || t == DroneBlueprint.DroneType.BOARDER)
        }
        method(A.M_SpaceDrone_GetNextProjectile, A.M_SuperShieldDrone_GetNextProjectile) { ret.nil() }
        method(A.M_SpaceDrone_SetMovementTarget, A.M_DefenseDrone_SetMovementTarget, A.M_BoarderPodDrone_SetMovementTarget,
            A.M_HackingDrone_SetMovementTarget, A.M_SuperShieldDrone_SetMovementTarget) {
            d(arg.obj(1)).aimMovementAt(targetShip(arg.obj(2))); 0
        }
        method(A.M_SpaceDrone_SetWeaponTarget, A.M_CombatDrone_SetWeaponTarget, A.M_DefenseDrone_SetWeaponTarget,
            A.M_SuperShieldDrone_SetWeaponTarget) {
            d(arg.obj(1)).weaponTarget = targetShip(arg.obj(2)); 0
        }
        method(A.M_SpaceDrone_ValidTargetObject, A.M_CombatDrone_ValidTargetObject, A.M_DefenseDrone_ValidTargetObject) {
            val t = arg.obj(2)
            ret.bool(t is DroneTargetable && validTarget(t.drone) && d(arg.obj(1)).type.type == DroneBlueprint.DroneType.DEFENSE)
        }
        method(A.M_SpaceDrone_OnRender) {
            val b = d(arg.obj(1)); val g = HsLua.graphics; val a = b.instance as? AbstractExternalDrone
            if (g != null && a != null && b.luaCurrentSpace == arg.int(2) && !b.bDead) a.renderExternal(g)
            0
        }
        method(A.M_SpaceDrone_RenderDrone, A.M_CombatDrone_RenderDrone, A.M_DefenseDrone_RenderDrone, A.M_BoarderPodDrone_RenderDrone,
            A.M_HackingDrone_RenderDrone, A.M_SuperShieldDrone_RenderDrone) {
            val g = HsLua.graphics; val a = d(arg.obj(1)).instance as? AbstractExternalDrone
            if (g != null && a != null) a.renderExternal(g)
            0
        }
        method(A.M_SpaceDrone_GetTooltip, A.M_CombatDrone_GetTooltip, A.M_DefenseDrone_GetTooltip, A.M_BoarderPodDrone_GetTooltip,
            A.M_HackingDrone_GetTooltip, A.M_SuperShieldDrone_GetTooltip) {
            val b = d(arg.obj(1)); ret.str(game()?.let { b.type.translateTitle(it) } ?: "")
        }
        method(A.M_SpaceDrone_GetWorldCenterPoint) { pushPoint(d(arg.obj(1)).luaLocation()) }
        method(A.M_SpaceDrone_SetCurrentLocation, A.M_BoarderPodDrone_SetCurrentLocation, A.M_HackingDrone_SetCurrentLocation) {
            val p = pointArg(arg.obj(2)); d(arg.obj(1)).luaSetLocation(p.xf, p.yf); 0
        }
        method(A.M_SpaceDrone_MouseMove) { 0 }
        method(A.M_SpaceDrone_GetRandomTargettingPoint) { pushPoint(d(arg.obj(1)).luaLocation()) }
        method(A.M_SpaceDrone_GetShieldShape) {
            val b = d(arg.obj(1)); val e = LuaStructs.create(A.T_Ellipse)
            val loc = b.luaLocation()
            (LuaStructs.member(e, A.A_Ellipse_center) as? LuaStruct)?.let { LuaStructs.setFields(it, loc.xf.toInt().toDouble(), loc.yf.toInt().toDouble()) }
            ret.obj(e, A.T_Ellipse)
        }
        method(A.M_SpaceDrone_GetSpaceId) { ret.num(d(arg.obj(1)).luaCurrentSpace) }
        method(A.M_SpaceDrone_GetSpeed) {
            val b = d(arg.obj(1)); val cur = b.luaLocation()
            // SpaceDrone::GetSpeed (0x4212b0): the move since the last frame, zero unless deployed and powered.
            if (!b.luaDeployed || !b.luaPowered) return@method ret.obj(PointfValue(0f, 0f), A.T_Pointf)
            val last = ext(b)?.lastLocation ?: b.points[A.A_SpaceDrone_lastLocation] ?: ConstFPoint(NONE, NONE)
            ret.obj(PointfValue(cur.xf - last.xf, cur.yf - last.yf), A.T_Pointf)
        }
        method(A.M_SpaceDrone_GetOwnerId) { val b = d(arg.obj(1)); ret.num(b.luaOwner?.let { HsWeapons.shipId(it) } ?: 0) }
        method(A.M_SpaceDrone_GetSelfId) { ret.num(d(arg.obj(1)).luaSelfId) }
        // SpaceDrone::DamageBeam / DamageArea (0x7569e0 / 0x7569d0) are the Collideable defaults: no effect, false.
        method(A.M_SpaceDrone_DamageBeam, A.M_SpaceDrone_DamageArea) { ret.bool(false) }
        method(A.M_SpaceDrone_GetBoardingDrone, A.M_BoarderPodDrone_GetBoardingDrone) {
            // BoarderPodDrone::GetBoardingDrone: the pod's CrewDrone; the engine's landed pawn once it exists.
            val b = d(arg.obj(1))
            val pawn = (b.instance as? BoardingDrone)?.pawn
            if (pawn != null) ret.obj(pawn, A.T_CrewMember) else ret.obj(b.boarderCrew, A.T_Drone)
        }
        method(A.M_SpaceDrone_UpdateAimingAngle) {
            val b = d(arg.obj(1))
            ret.num(((b.instance as? DefenceDrone)?.aimAngle?.let { Math.toDegrees(it.toDouble()) } ?: b.fields[A.A_SpaceDrone_aimingAngle] ?: 0.0))
        }
    }

    private fun destination(b: NativeDrone): FPoint = when (val fc = ext(b)?.flightController) {
        is OrbitFlightController -> fc.destination
        is CombatFlightController -> ConstFPoint(fc.nextDestination.x.toFloat(), fc.nextDestination.y.toFloat())
        else -> b.destinationLocation
    }

    private fun setDestination(b: NativeDrone, x: Float, y: Float) {
        when (val fc = ext(b)?.flightController) {
            is OrbitFlightController -> fc.destination.set(x, y)
            is CombatFlightController -> fc.nextDestination = xyz.znix.xftl.math.ConstPoint(x.toInt(), y.toInt())
            else -> b.destinationLocation.set(x, y)
        }
    }

    // ------------------------------------------------------------------ derived classes
    private fun derived() {
        storedNum(A.A_DefenseDrone_currentTargetId, { -1.0 }, { b -> (b.instance as? DefenceDrone)?.currentTarget?.let { t -> targetId(t) } })
        storedNum(A.A_DefenseDrone_shotAtTargetId, { -2.0 })
        storedNum(A.A_DefenseDrone_currentSpeed, { b -> (b.type.speed ?: 0).toDouble() }, { b ->
            (b.instance as? ShieldDrone)?.let { (b.type.speed ?: 0) * it.shieldOrbitSpeedScale.toDouble() }
        })
        storedNum(A.A_DefenseDrone_currentTargetType, { 5.0 })
        for (id in intArrayOf(A.A_DefenseDrone_drone_image, A.A_DefenseDrone_gun_image_off, A.A_DefenseDrone_gun_image_charging,
            A.A_DefenseDrone_gun_image_on, A.A_DefenseDrone_engine_image, A.A_CombatDrone_drone_image_off,
            A.A_CombatDrone_drone_image_charging, A.A_CombatDrone_drone_image_on, A.A_CombatDrone_engine_image,
            A.A_SuperShieldDrone_drone_image_on, A.A_SuperShieldDrone_drone_image_off, A.A_SuperShieldDrone_drone_image_glow,
            A.A_BoarderPodDrone_flame, A.A_BoarderPodDrone_baseSheet, A.A_BoarderPodDrone_colorSheet,
            A.A_HackingDrone_droneImage_on, A.A_HackingDrone_droneImage_off, A.A_HackingDrone_lightImage,
            A.A_ShipRepairDrone_repairBeam)) {
            LuaReg.obj(id, ObjGet { o -> LuaViews.cached(d(o), id) { b -> ImageRef(A.attrType(id), "img/ship/drones/" + ((b as NativeDrone).type.droneImage ?: "") + (imageSuffix[id] ?: "")) } })
        }
        // CachedImage by value: `drone.image = other` draws the other image from then on.
        for ((id, slot) in imageSlots) {
            LuaReg.objSet[id] = ObjSet { o, v ->
                val ref = v as? ImageRef ?: return@ObjSet
                val b = d(o)
                LuaStored.put(b, id, ref)
                val img = game()?.getImg(ref.path) ?: return@ObjSet
                when (val i = b.instance) {
                    is xyz.znix.xftl.drones.CombatDrone -> i.hsSetImage(slot, img)
                    is xyz.znix.xftl.drones.DefenceDrone -> i.hsSetImage(slot, img)
                    is xyz.znix.xftl.drones.ShieldDrone -> i.hsSetImage(slot, img)
                    else -> {}
                }
            }
        }
        storedPoint(A.A_CombatDrone_lastDestination, -1f, -1f)
        storedNum(A.A_CombatDrone_progressToDestination, { 1.0 }, { b -> (b.instance as? CombatDrone)?.nativeProgress?.toDouble() }, keep = true)
        storedNum(A.A_CombatDrone_heading, { 0.0 }, { b -> (ext(b)?.flightController as? CombatFlightController)?.let { Math.toDegrees(it.rotation.toDouble()) } }, keep = true)
        storedNum(A.A_CombatDrone_oldHeading, { 0.0 })
        LuaReg.obj(A.A_ShipRepairDrone_repairBeams, ObjGet { o -> LuaViews.cached(d(o), A.A_ShipRepairDrone_repairBeams) { ListView(A.T_vector_float, 0, (d(o).instance as? xyz.znix.xftl.drones.HullRepairDrone)?.hsWaves ?: ArrayList<Any?>()) } })
        LuaReg.obj(A.A_SuperShieldDrone_shieldSystem, ObjGet { d(it).luaOwner?.shields })
        storedNum(A.A_SuperShieldDrone_glowAnimation, { 0.0 }, { b -> (b.instance as? ShieldDrone)?.glowTime?.toDouble() }) { b, v ->
            val a = b.instance as? ShieldDrone ?: return@storedNum false; a.glowTime = v.toFloat(); true
        }
        // BoarderPodDrone
        storedPoint(A.A_BoarderPodDrone_startingPosition, 0f, 0f)
        animation(A.A_BoarderPodDrone_droneImage, "battle_walk_down") { true }
        LuaReg.obj(A.A_BoarderPodDrone_boarderDrone, ObjGet {
            // BoarderPodDrone's constructor makes the crew drone; it boards when the pod lands.
            (d(it).instance as? BoardingDrone)?.let { p -> p.pawn ?: p.ownerShip.rooms.firstOrNull()?.let { r -> p.hsIdlePawn(r) } }
        })
        LuaReg.bool(A.A_BoarderPodDrone_bDeliveredDrone, { (d(it).instance as? BoardingDrone)?.hsPodDelivered == true },
            { o, value -> (d(o).instance as? BoardingDrone)?.hsPodDelivered = value })
        storedBool(A.A_BoarderPodDrone_diedInSpace, false)
        // HackingDrone (the hacking system's own drone)
        storedPoint(A.A_HackingDrone_startingPosition, 0f, 0f)
        storedPoint(A.A_HackingDrone_finalDestination, 0f, 0f)
        LuaReg.bool(A.A_HackingDrone_arrived, { o -> val b = d(o); b is HackingNativeDrone && b.hacking.hsCollisionBody?.arrived == true },
            { o, value -> (d(o) as? HackingNativeDrone)?.hacking?.hsCollisionBody?.arrived = value })
        storedBool(A.A_HackingDrone_finishedSetup, false) { b -> (b as? HackingNativeDrone)?.hacking?.droneLanded }
        LuaReg.obj(A.A_HackingDrone_flashTracker, ObjGet { o -> LuaViews.cached(d(o), A.A_HackingDrone_flashTracker) { LuaStructs.create(A.T_AnimationTracker) } })
        animation(A.A_HackingDrone_flying, "drone_hack_land") { b -> (b as? HackingNativeDrone)?.hacking?.droneInFlight }
        animation(A.A_HackingDrone_extending, "drone_hack_extend") { b -> (b as? HackingNativeDrone)?.hacking?.droneLanded }
        animation(A.A_HackingDrone_explosion, null) { it.luaExploding }
        storedNum(A.A_HackingDrone_prefRoom, { -1.0 }, { b -> (b as? HackingNativeDrone)?.hacking?.droneTargetSystem?.room?.id?.toDouble() })
    }

    private fun targetId(t: Any): Double = when (t) {
        is xyz.znix.xftl.weapons.AbstractProjectile -> t.hsExt?.selfId?.toDouble() ?: -1.0
        is AbstractDrone -> t.hsSelfId.toDouble()
        else -> -1.0
    }

    // ------------------------------------------------------------------ DroneSystem
    private fun ds(o: Any?) = o as? Drones ?: throw LuaError("expected DroneSystem")

    private fun system() {
        LuaReg.obj(A.A_DroneSystem_drones, ObjGet { o -> LuaViews.cached(ds(o), A.A_DroneSystem_drones) { s -> ListView(A.T_vector_Drone, A.T_Drone) { (s as Drones).drones.filterNotNull() } } })
        LuaReg.num(A.A_DroneSystem_drone_count, NumGet { (ds(it).attachedShip?.dronesCount ?: 0).toDouble() },
            NumSet { o, v -> ds(o).attachedShip?.dronesCount = v.toInt() })
        storedSystemNum(A.A_DroneSystem_drone_start, 0.0)
        LuaReg.obj(A.A_DroneSystem_targetShip, ObjGet { o -> ds(o).attachedShip?.let { s -> s.sys.getEnemyOf(s) }?.let { shipTargetable(it) } })
        LuaReg.obj(A.A_DroneSystem_userPowered, ObjGet { o ->
            LuaViews.cached(ds(o), A.A_DroneSystem_userPowered) { s -> ListView(A.T_vector_bool, 0) { (s as Drones).let { d -> d.drones.indices.map { i -> d.hsUserPowered[i] == true } } } }
        })
        LuaReg.num(A.A_DroneSystem_slot_count, NumGet { (ds(it).attachedShip?.droneSlots ?: ds(it).drones.size).toDouble() })
        // iStartingBatteryPower (+0x1f0): the pool PowerDrone (0x638b80) spends before the reactor.
        LuaReg.num(A.A_DroneSystem_iStartingBatteryPower, NumGet { ds(it).powerManager.hsBatteryPool.toDouble() },
            NumSet { o, v -> ds(o).powerManager.hsBatteryPool = v.toInt() })
        LuaReg.obj(A.A_DroneSystem_repowerList, ObjGet { o ->
            LuaViews.cached(ds(o), A.A_DroneSystem_repowerList) { s -> ListView(A.T_vector_bool, 0) { List(maxOf(4, (s as Drones).drones.size)) { false } } }
        })
        method(A.M_DroneSystem_DePowerDrone) {
            val s = ds(arg.obj(1)); val b = d(arg.obj(2))
            val slot = s.drones.indexOfFirst { it === b }
            ret.bool(slot >= 0 && (!b.luaPowered || s.setDronePower(slot, false)))
        }
        method(A.M_DroneSystem_RemoveDrone) { ret.obj(ds(arg.obj(1)).luaRemoveDrone(arg.int(2)), A.T_Drone) }
        // ShipSystem::ForceDecreasePower / ForceIncreasePower (WP5 owns ShipSystem; only while nothing registered them):
        // the system gives up (takes) up to n bars of reactor power; true when any bar moved.
        fun forcePower(id: Int, up: Boolean) {
            if (LuaReg.methods[id] == null) method(id) {
                val sys = arg.obj(1) as? xyz.znix.xftl.systems.MainSystem ?: throw LuaError("expected a ShipSystem")
                var moved = false
                repeat(maxOf(0, arg.int(2))) {
                    val before = sys.powerSelected
                    if (up) sys.increasePower() else sys.decreasePower()
                    if (sys.powerSelected != before) moved = true
                }
                ret.bool(moved)
            }
        }
        forcePower(A.M_ShipSystem_ForceDecreasePower, false)
        forcePower(A.M_ShipSystem_ForceIncreasePower, true)
        // DroneSystem's ShipSystem overrides share the ShipSystem adapters.
        fun alias(to: Int, from: Int) { LuaReg.methods[from]?.let { LuaReg.method(to, it) } }
        alias(A.M_DroneSystem_AddDamage, A.M_ShipSystem_AddDamage)
        alias(A.M_DroneSystem_CheckMaxPower, A.M_ShipSystem_CheckMaxPower)
        alias(A.M_DroneSystem_ForceBatteryPower, A.M_ShipSystem_ForceBatteryPower)
        alias(A.M_DroneSystem_ForceDecreasePower, A.M_ShipSystem_ForceDecreasePower)
        alias(A.M_DroneSystem_ForceIncreasePower, A.M_ShipSystem_ForceIncreasePower)
        alias(A.M_DroneSystem_RemoveBatteryPower, A.M_ShipSystem_RemoveBatteryPower)
        alias(A.M_DroneSystem_SetBonusPower, A.M_ShipSystem_SetBonusPower)
        // Shields' ShipSystem overrides (WP5 owns Shields): only where nothing registered them yet.
        fun aliasIfMissing(to: Int, from: Int) { if (LuaReg.methods[to] == null) alias(to, from) }
        aliasIfMissing(A.M_Shields_ForceDecreasePower, A.M_ShipSystem_ForceDecreasePower)
        aliasIfMissing(A.M_Shields_ForceIncreasePower, A.M_ShipSystem_ForceIncreasePower)
        aliasIfMissing(A.M_Shields_AddDamage, A.M_ShipSystem_AddDamage)
    }

    private val systemFields = xyz.znix.xftl.hs.lua.HsRunMaps.any(java.util.IdentityHashMap<Any, HashMap<Int, Double>>())
    private fun storedSystemNum(id: Int, default: Double) = LuaReg.num(id,
        NumGet { o -> systemFields[o!!]?.get(id) ?: default }, NumSet { o, v -> systemFields.getOrPut(o!!) { HashMap() }[id] = v })

    // ------------------------------------------------------------------ HackingSystem
    private fun hacking() {
        val droneBefore = LuaReg.objGet[A.A_HackingSystem_drone]
        LuaReg.obj(A.A_HackingSystem_drone, ObjGet { o -> (o as? Hacking)?.let { DroneRuntime.hackingBody(it) } ?: droneBefore?.get(o) })
        LuaReg.bool(A.A_HackingSystem_bHacking, { (it as Hacking).active })
        LuaReg.bool(A.A_HackingSystem_bArmed, { (it as Hacking).droneLaunched })
        LuaReg.bool(A.A_HackingSystem_bBlocked, { false })
        LuaReg.bool(A.A_HackingSystem_bCanHack, { (it as Hacking).droneLanded })
        LuaReg.obj(A.A_HackingSystem_currentSystem, ObjGet { (it as Hacking).droneTargetSystem })
        LuaReg.obj(A.A_HackingSystem_queuedSystem, ObjGet { (it as Hacking).droneTargetSystem })
        LuaReg.num(A.A_HackingSystem_spendDrone, NumGet { 0.0 })
        LuaReg.obj(A.A_HackingSystem_effectTimer, ObjGet { o ->
            LuaViews.cached(o!!, A.A_HackingSystem_effectTimer) { h ->
                val s = h as Hacking
                FloatPairView(A.T_pair_float_float, FGet { s.browserPulseElapsed }, FSet { s.hsWritePulseElapsed(it) }, FGet { s.hsEffectSecond }, FSet { s.hsWritePulseDuration(it) })
            }
        })
        method(A.M_HackingSystem_BlowHackingDrone) { (arg.obj(1) as Hacking).hsCollisionBody?.collisionBlowUp(); 0 }
        method(A.M_HackingSystem_StopHacking) { (arg.obj(1) as Hacking).stopHacking(); 0 }
    }

    // ------------------------------------------------------------------ DroneBlueprint
    private fun bp(o: Any?) = o as? DroneBlueprint ?: throw LuaError("expected DroneBlueprint")

    /** BlueprintManager::GetDroneBlueprint of an unknown name: a default-constructed DroneBlueprint (not nil). */
    object DefaultDroneBlueprint : LuaTyped { override val luaType get() = A.T_DroneBlueprint }

    /** BlueprintManager::GetWeaponBlueprint("") (a weaponless drone's weaponBlueprint): only its Blueprint fields answer. */
    object DefaultWeaponBlueprint : LuaTyped { override val luaType get() = A.T_WeaponBlueprint }

    private fun blueprint() {
        val getBefore = LuaReg.methods[A.M_BlueprintManager_GetDroneBlueprint]
        if (getBefore != null) LuaReg.method(A.M_BlueprintManager_GetDroneBlueprint, LuaMethod { n ->
            val name = arg.str(2) ?: ""
            if (game()?.blueprintManager?.getOrNull(name) is DroneBlueprint) getBefore.call(n) else ret.obj(DefaultDroneBlueprint, A.T_DroneBlueprint)
        })
        fun dflt(o: Any?) = o === DefaultDroneBlueprint
        LuaReg.str(A.A_DroneBlueprint_typeName, StrGet { if (dflt(it)) "" else bp(it).typeName })
        LuaReg.num(A.A_DroneBlueprint_level, NumGet { if (dflt(it)) 1.0 else bp(it).level.toDouble() })
        LuaReg.num(A.A_DroneBlueprint_targetType, NumGet { if (dflt(it)) 1.0 else bp(it).targetType.toDouble() })
        LuaReg.num(A.A_DroneBlueprint_power, NumGet { if (dflt(it)) 0.0 else bp(it).power.toDouble() })
        LuaReg.num(A.A_DroneBlueprint_cooldown, NumGet { if (dflt(it)) 0.0 else (bp(it).cooldown ?: 0) / 1000.0 })
        LuaReg.num(A.A_DroneBlueprint_speed, NumGet { if (dflt(it)) 0.0 else (bp(it).speed ?: 0).toDouble() })
        LuaReg.num(A.A_DroneBlueprint_dodge, NumGet { if (dflt(it)) 0.0 else (bp(it).dodge ?: 0).toDouble() })
        LuaReg.str(A.A_DroneBlueprint_weaponBlueprint, StrGet { if (dflt(it)) "" else bp(it).weaponBlueprintName ?: "" })
        LuaReg.str(A.A_DroneBlueprint_droneImage, StrGet { if (dflt(it)) "" else bp(it).droneImage ?: "" })
        LuaReg.str(A.A_DroneBlueprint_combatIcon, StrGet { if (dflt(it)) "" else bp(it).iconImage ?: "" })
        // Blueprint::GetType: 1 for every drone blueprint (WeaponBlueprint 0, AugmentBlueprint 2, ...).
        LuaReg.method(A.M_DroneBlueprint_GetType, LuaMethod { ret.num(1) })
        val nameBefore = LuaReg.strGet[A.A_Blueprint_name]
        LuaReg.str(A.A_Blueprint_name, StrGet { o -> if (dflt(o) || o === DefaultWeaponBlueprint) "" else nameBefore?.get(o) ?: "" }, LuaReg.strSet[A.A_Blueprint_name])
        val typeBefore = LuaReg.numGet[A.A_Blueprint_type]; val typeSetBefore = LuaReg.numSet[A.A_Blueprint_type]
        LuaReg.num(A.A_Blueprint_type, NumGet { o -> if (o is DroneBlueprint) NativeDrone.typeId(o.type).toDouble() else if (dflt(o)) -1.0 else typeBefore?.get(o) ?: 0.0 },
            NumSet { o, v -> if (o !is DroneBlueprint) typeSetBefore?.set(o, v) })
    }

    // ------------------------------------------------------------------ ShipManager
    private fun sm(o: Any?) = o as? Ship ?: throw LuaError("expected ShipManager")

    private fun shipManager() {
        // ShipManager::AddSystem (WP5 owns ShipManager; registered here only while nothing else provides it):
        // the system goes into the room the ship's blueprint has for it, at the blueprint's starting power.
        if (LuaReg.methods[A.M_ShipManager_AddSystem] == null) method(A.M_ShipManager_AddSystem) {
            val s = sm(arg.obj(1)); val name = xyz.znix.xftl.hs.systems.HsSystems.idToName(arg.int(2))
            val slot = s.systemSlots.firstOrNull { it.system.name == name && it.room.system == null }
            if (slot != null && s.systems.none { it.blueprint.name == name }) slot.room.setSystem(slot)
            0
        }
        method(A.M_ShipManager_CreateSpaceDrone) { ret.obj(DroneRuntime.createSpaceDrone(sm(arg.obj(1)), bp(arg.obj(2))), A.T_SpaceDrone) }
        method(A.M_ShipManager_AddDrone) {
            val ship = sm(arg.obj(1)); val drones = ship.drones
            if (drones != null) drones.luaAddDrone(bp(arg.obj(2)), arg.int(3))
            0
        }
        method(A.M_ShipManager_GetDroneCount) { ret.num(sm(arg.obj(1)).dronesCount) }
        method(A.M_ShipManager_ModifyDroneCount) { val s = sm(arg.obj(1)); s.dronesCount = maxOf(0, s.dronesCount + arg.int(2)); 0 }
        method(A.M_ShipManager_GetDroneList) {
            val s = sm(arg.obj(1))
            ret.obj(ListView(A.T_vector_Drone, A.T_Drone, ArrayList<Any?>(s.drones?.drones?.filterNotNull() ?: emptyList())), A.T_vector_Drone)
        }
        LuaReg.num(A.A_ShipManager_tempDroneCount, NumGet { sm(it).dronesCount.toDouble() })
        LuaReg.obj(A.A_ShipManager_superDrones, ObjGet { o ->
            LuaViews.cached(sm(o), A.A_ShipManager_superDrones) { s -> ListView(A.T_vector_SpaceDrone, A.T_SpaceDrone) { xyz.znix.xftl.hs.ship.HsBoss.superDronesOf(s as Ship).map { it.luaBody } } }
        })
        method(A.M_ShipManager_PrepareSuperDrones) {
            val s = sm(arg.obj(1)); val g = game()
            val def = xyz.znix.xftl.hs.ship.HsBoss.droneDef("")
            if (g != null && def != null) xyz.znix.xftl.hs.ship.HsBoss.prepareSuperDrones(g, s, def)
            0
        }
    }

    // ------------------------------------------------------------------ DroneControl
    private fun controls() {
        val ccBefore = LuaReg.objGet[A.A_CommandGui_combatControl]
        if (ccBefore == null) LuaReg.obj(A.A_CommandGui_combatControl, ObjGet { LuaCombatControl })
        val dcBefore = LuaReg.objGet[A.A_CombatControl_droneControl]
        LuaReg.obj(A.A_CombatControl_droneControl, ObjGet { o -> if (o === LuaCombatControl) LuaDroneControl else dcBefore?.get(o) ?: LuaDroneControl })
        method(A.M_DroneControl_SelectArmament) { game()?.shipUI?.luaSelectDrone(arg.int(2)); 0 }
        method(A.M_DroneControl_DeselectArmament) { game()?.shipUI?.luaDeselectDrone(arg.int(2)); 0 }
        method(A.M_DroneControl_SwapArmaments) { game()?.player?.drones?.swapSlots(arg.int(2), arg.int(3)); 0 }
    }
}
