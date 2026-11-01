// Native Drone/SpaceDrone object model: FTL 1.6.14 ShipManager::CreateSpaceDrone (0x4b0b60), the SpaceDrone
// constructor (0x426930), Drone::SetDestroyed (0x462110) and DroneSystem::OnLoop (0x639a50); Hyperspace
// (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDrones.cpp (CreateSpaceDrone and constructor hooks).
package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.MutFPoint
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * The native `Drone` (and, for space drones, `SpaceDrone`) object of one equipped drone part. The original creates
 * it when the drone is equipped (ShipManager::AddDrone -> CreateDrone -> CreateSpaceDrone/CreateCrewDrone) and keeps
 * it until the drone is removed; deploying only flips `deployed` on the same object. The engine instead builds a
 * fresh flying/walking actor ([instance]) at each deployment, so this object is the persistent identity (the Lua
 * userdata, `DroneSystem.drones`, `ShipManager.spaceDrones`) and holds the native fields: the ones the actor models
 * are read from it while deployed, the others are kept here with the constructor's values.
 */
open class NativeDrone(val type: DroneBlueprint, instance: AbstractDrone? = null, presetSelfId: Int? = null) :
    xyz.znix.xftl.hs.weapons.HsDroneCollisionBody {

    /** The deployed actor (flying around a ship, a boarding pod, or a crew drone's pawn); null = not deployed. */
    var instance: AbstractDrone? = instance
        set(value) {
            field = value
            if (value != null) { value.body = this; deployedRaw = true }
        }

    /** Drone::deployed (+0x14). True while [instance] exists; a dead drone without a system stays deployed. */
    @JvmField var deployedRaw: Boolean = instance != null

    /** Set when the body has joined its ship (ShipManager::spaceDrones / the arrival queue). */
    @JvmField var joined: Boolean = false

    /** Hyperspace: the names (short, long) a Lua CrewMember:SetName gave the drone's CrewDrone; it lives as long as the drone. */
    @JvmField var hsCrewNameOverride: Pair<String, String>? = null

    init { instance?.body = this }

    /** The drone system this drone is equipped in, or null for a drone made by CreateSpaceDrone (or a super drone). */
    var system: Drones? = null
        internal set

    /** The ship of a drone without a system (ShipManager::CreateSpaceDrone called from Lua, boss surges). */
    var standaloneOwner: Ship? = null

    val ownerShip: Ship? get() = system?.attachedShip ?: standaloneOwner ?: instance?.ownerShipOrNull

    /** Hyperspace: the SpaceDrone's selfId, drawn when the drone joins the ship (ShipManager::CreateSpaceDrone 0x4b0b60). */
    @JvmField var hsSelfId: Int = presetSelfId ?: if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsWeapons.nextSpaceId() else -1

    /**
     * BoarderPodDrone's constructor makes its boarding CrewDrone right after the SpaceDrone base (oracle: the next
     * space id goes to it), returned by GetBoardingDrone before the pod has landed.
     */
    @JvmField val boarderCrewSelfId: Int =
        if (presetSelfId == null && xyz.znix.xftl.hs.HS.active && type.type == DroneBlueprint.DroneType.BOARDER) xyz.znix.xftl.hs.weapons.HsWeapons.nextSpaceId() else -1
    val boarderCrew: NativeDrone? by lazy { if (type.type == DroneBlueprint.DroneType.BOARDER && presetSelfId == null) BoarderCrewBody(this) else null }

    /**
     * The native SpaceDrone's current angle in degrees (SpaceDrone+0x16c), drawn by its constructor and moved by
     * the generation-stream hops of [xyz.znix.xftl.shipgen.ShipConstructionDraws.targetShip]. Only the native
     * stream's draw count uses it; the engine's own drone flight keeps its own angle. The player's drones start at
     * 0: the original draws theirs in the hangar, before the run's seed. Not saved.
     */
    @JvmField var nativeAngle: Int = 0

    /** Drone::destroyedTimer (+0x2c): the rebuild countdown, set to 10 by SetDestroyed(true, true). */
    @JvmField var destroyedTimer: Float = 0f

    /**
     * The cooldown (counting down) for how long until the player can deploy this drone again, after it was
     * destroyed: [destroyedTimer] while it is positive (Drone::UpdateRebuilding 0x462470 stops at zero, leaving
     * whatever the last frame's step went below it).
     */
    var cooldown: Float?
        get() = if (destroyedTimer > 0f) destroyedTimer else null
        set(value) { destroyedTimer = value ?: 0f }

    /** Drone::bDead (+0x20). The drone system undeploys a dead drone and clears it on its next loop. */
    @JvmField var bDead: Boolean = false

    /** Drone::powerRequired (+0x10): the blueprint's power, writable (MV's temporary drones set it to zero). */
    @JvmField var powerRequired: Int = type.power

    /** Drone::iBonusPower (+0x24); GetRequiredPower is powerRequired - iBonusPower. */
    @JvmField var iBonusPower: Int = 0

    /** Drone::poweredAtLocation (+0x28): set by the drone system's loop whenever the drone is powered. */
    @JvmField var poweredAtLocation: Boolean = false

    /** Drone::iHackLevel (+0x30): the drone system's hack effect, passed down by SetHacked each loop. */
    @JvmField var iHackLevel: Int = 0

    /** Drone::type (+0x18): the blueprint's drone type, writable as a raw field. */
    @JvmField var nativeType: Int = typeId(type.type)

    /** The `powered` flag of a drone that has no actor (a Lua write before SetDeployed applies on deployment). */
    @JvmField var storedPowered: Boolean = false

    /** SpaceDrone::lifespan (+0x250): INT_MAX, or the passes left of a temporary/surge combat drone. */
    @JvmField var storedLifespan: Int = Int.MAX_VALUE

    /** SpaceDrone::movementTarget / weaponTarget: the ship whose Targetable the drone was given (null = none). */
    var movementTarget: Ship? = null
    var weaponTarget: Ship? = null

    /** SpaceDrone::destinationSpace (+0x4c): -1 until a movement target is set. */
    @JvmField var destinationSpaceStored: Int = -1

    /** SpaceDrone::currentLocation of an undeployed drone (the constructor's -FLT_MAX until something sets it). */
    val storedLocation = MutFPoint(-Float.MAX_VALUE, -Float.MAX_VALUE)

    /** Members the engine does not model (pause, beamSpeed, hackAngle, ...): native constructor values, then Lua writes. */
    val fields = HashMap<Int, Double>()
    val points = HashMap<Int, MutFPoint>()

    /** Whether CONSTRUCT_SPACEDRONE has fired for this body. */
    @JvmField var constructed: Boolean = false

    open val isSpaceDrone: Boolean get() = isSpaceType(type.type)

    val deployed: Boolean get() = deployedRaw

    /** The actor left play (AbstractDrone.removeInstance). */
    /** SpaceDrone::currentSpace as the last deployment left it (null: never deployed, the own ship's). */
    var storedCurrentSpace: Int? = null

    /** SpaceDrone::bFire as the last deployment left it (see [deployActor]). */
    var storedFire = false

    fun actorRemoved(actor: AbstractDrone, keepDeployed: Boolean = false) {
        if (instance !== actor) return
        onActorRemoved?.invoke(this)
        // SpaceDrone::lifespan lives on the native object: the passes left outlast the deployment.
        (actor as? CombatDrone)?.browserPassesLeft?.let { storedLifespan = it }
        (actor as? DefenceDrone)?.let { storedFire = it.fireReady }
        // SpaceDrone::currentSpace is not reset by an undeploy (oracle wp4-drone-fight-system: a recalled combat drone
        // still reads the enemy's space).
        storedCurrentSpace = luaCurrentSpace
        instance = null
        if (!keepDeployed) deployedRaw = false
    }

    /**
     * Drone::SetDeployed for this body: deploying builds the engine actor (the drone flies or lands from its
     * deployment point; SpaceDrone::SetDeployed resets ionStun and randomizes the starting position), undeploying
     * takes the actor out of play. A combat drone of a drone system flies at the current enemy; a drone made by
     * CreateSpaceDrone flies around its movement target.
     */
    /**
     * Hyperspace: a crew drone's CrewDrone (repair, anti-personnel), made by ShipManager::CreateCrewDrone
     * when the drone joins the ship and kept until it leaves: it is in vCrewList from then on (room -1 while not
     * deployed), CONSTRUCT_CREWMEMBER and its selfId come with it, and every deployment puts the same body on the ship.
     * The engine keeps that body's actor here; null in vanilla and for other drone types.
     */
    var hsCrewActor: AbstractIndoorsDrone? = null
        private set

    /** Makes [hsCrewActor] and its body in the drone room of [ship] (DroneRuntime's join of a crew drone). */
    fun hsCrewBody(ship: Ship) {
        if (!xyz.znix.xftl.hs.HS.active || isSpaceDrone) return
        (instance as? AbstractIndoorsDrone)?.let { if (hsCrewActor == null) hsCrewActor = it }
        if (hsCrewActor != null) return
        if (type.type != DroneBlueprint.DroneType.REPAIR && type.type != DroneBlueprint.DroneType.BATTLE) return
        val room = ship.rooms.firstOrNull { it.system is Drones } ?: return
        val actor = (instance as? AbstractIndoorsDrone) ?: (type.makeInstance() as? AbstractIndoorsDrone) ?: return
        actor.body = this
        hsCrewActor = actor
        actor.hsIdlePawn(room)
    }

    /** A loaded deployed crew drone: its actor (and the body it loaded) is the drone's from now on. */
    internal fun hsAdoptCrewActor(actor: AbstractIndoorsDrone) {
        if (xyz.znix.xftl.hs.HS.active && !isSpaceDrone) hsCrewActor = actor
    }

    /** DroneRuntime's detach: the CrewDrone leaves with the drone. */
    internal fun hsDropCrewActor() { hsCrewActor = null }

    fun deployActor(owner: Ship): AbstractDrone? {
        instance?.let { return it }
        // Hyperspace: the crew drone's body deploys again (CrewDrone::SetDeployed on the same object)
        hsCrewActor?.let { crewActor ->
            instance = crewActor
            if (crewActor.isInitialisedActor) crewActor.hsRespawn() else crewActor.init(owner)
            if (storedPowered) crewActor.isPowered = true
            return crewActor
        }
        val actor = type.makeInstance()
        if (system == null) {
            val target = movementTarget
            if (actor is AbstractExternalDrone) {
                if (target != null) actor.presetTargetShip(target)
                else if (actor.onEnemy && owner.sys.getEnemyOf(owner) == null) { deployedRaw = true; return null }
            } else if (actor is BoardingDrone && owner.sys.getEnemyOf(owner) == null) { deployedRaw = true; return null }
        }
        instance = actor
        // BoarderPodDrone::SetDeployed (0x4227b0) takes a new space id on every launch.
        if (xyz.znix.xftl.hs.HS.active && actor is BoardingDrone) hsSelfId = xyz.znix.xftl.hs.weapons.HsWeapons.nextSpaceId()
        actor.init(owner)
        if (actor is AbstractExternalDrone) {
            movementTarget = actor.targetShip
            destinationSpaceStored = xyz.znix.xftl.hs.weapons.HsWeapons.shipId(actor.targetShip)
        }
        if (actor is CombatDrone && storedLifespan != Int.MAX_VALUE) actor.browserPassesLeft = storedLifespan
        // SpaceDrone::bFire (+0x15e) is not touched by SpaceDrone/DefenseDrone::SetDeployed (0x421af0/0x4272e0) or
        // Drone::SetDestroyed: a redeployed defence drone that was ready still is, and fires at its first target
        // without the 1 s deploy cooldown. Measured: native 1.6.14 drone-order probe 2 (bFire 1 before the destroy,
        // through the rebuild and on the first loop after the redeploy) and HS oracle wp4-drone-body.
        if (actor is DefenceDrone && storedFire) actor.fireReady = true
        if (storedPowered) actor.isPowered = true
        return actor
    }

    fun setDeployed(value: Boolean) {
        if (value) {
            if (instance == null) { val owner = ownerShip ?: return; deployActor(owner) }
        } else {
            instance?.removeInstance()
            deployedRaw = false
            fields.remove(ION_STUN)
        }
    }

    /** SpaceDrone::SetMovementTarget: a defence or shield drone not yet flying is placed on the target's shield ring. */
    fun aimMovementAt(target: Ship?) {
        movementTarget = target
        destinationSpaceStored = if (target == null) -1 else xyz.znix.xftl.hs.weapons.HsWeapons.shipId(target)
        if (target == null || instance != null) return
        if (type.type == DroneBlueprint.DroneType.SHIP_REPAIR) {
            // CombatDrone::SetMovementTarget (ShipRepairDrone derives from CombatDrone): a start point and a first
            // destination on the far sides of the target (oracle: both set); the weapon target is the ship too.
            weaponTarget = target
            val half = target.shieldHalfSize
            val a = Math.toRadians(nativeAngle.toDouble())
            storedLocation.set((target.shieldOrigin.x + half.x * Math.cos(a)).toFloat(), (target.shieldOrigin.y + half.y * Math.sin(a)).toFloat())
            destinationLocation.set((target.shieldOrigin.x - half.x * Math.cos(a)).toFloat(), (target.shieldOrigin.y - half.y * Math.sin(a)).toFloat())
        }
        if (type.type == DroneBlueprint.DroneType.DEFENSE || type.type == DroneBlueprint.DroneType.SHIELD) {
            val half = target.shieldHalfSize
            val a = Math.toRadians(nativeAngle.toDouble())
            val b = Math.toRadians(nativeAngle + 1.0)
            storedLocation.set((target.shieldOrigin.x + half.x * Math.cos(a)).toFloat(), (target.shieldOrigin.y + half.y * Math.sin(a)).toFloat())
            destinationLocation.set((target.shieldOrigin.x + half.x * Math.cos(b)).toFloat(), (target.shieldOrigin.y + half.y * Math.sin(b)).toFloat())
        }
    }

    /** SpaceDrone::destinationLocation of an undeployed drone (-1,-1 from the constructor). */
    val destinationLocation = MutFPoint(-1f, -1f)

    /** SpaceDrone::lifespan. */
    var lifespan: Int
        get() = (instance as? CombatDrone)?.browserPassesLeft ?: (instance as? HullRepairDrone)?.remainingRepairs ?: storedLifespan
        set(value) {
            storedLifespan = value
            (instance as? CombatDrone)?.browserPassesLeft = if (value == Int.MAX_VALUE) null else value
        }

    /** Drone::powered: the actor's power while deployed. */
    var powered: Boolean
        get() = instance?.isPowered ?: storedPowered
        set(value) {
            storedPowered = value
            instance?.isPowered = value
        }

    val shipId: Int get() = ownerShip?.let { xyz.znix.xftl.hs.weapons.HsWeapons.shipId(it) } ?: 0

    /** Drone::GetRequiredPower (0x76a5c0 when not overridden): powerRequired - iBonusPower. */
    val requiredPower: Int get() = powerRequired - iBonusPower

    /** Drone::SetDestroyed (0x462110): the rebuild timer starts only on the first transition to dead. */
    fun setDestroyed(dead: Boolean, setTimer: Boolean) {
        if (!bDead && dead && setTimer) destroyedTimer = 10f
        bDead = dead
        val actor = instance
        if (dead && actor != null && system == null) {
            // A drone without a system has nobody to recall it: a dead one stays deployed but is no longer drawn
            // or updated (SpaceDrone::OnRender and OnLoop skip bDead), so the engine takes its actor out of play.
            actor.removeInstance(keepDeployed = true)
        }
    }

    // ---- Lua-facing state (HackingNativeDrone overrides these for the hacking system's own drone).
    open val luaDeployed: Boolean get() = deployed
    open var luaPowered: Boolean
        get() = powered
        set(value) { powered = value }
    open val luaOwner: Ship? get() = ownerShip
    open val luaSelfId: Int get() = hsSelfId

    /** SpaceDrone::currentLocation: the flying actor's position (a boarder: its pod), else the stored point. */
    open fun luaLocation(): FPoint = when (val a = instance) {
        is AbstractExternalDrone -> a.flightController.position
        is BoardingDrone -> a.hsCollisionBody?.collisionPosition ?: storedLocation
        else -> storedLocation
    }

    open fun luaSetLocation(x: Float, y: Float) {
        when (val a = instance) {
            is AbstractExternalDrone -> a.flightController.position.set(x, y)
            else -> storedLocation.set(x, y)
        }
    }

    /** SpaceDrone::currentSpace: the space the drone flies in (its own ship's until deployed). */
    open val luaCurrentSpace: Int get() = when (val a = instance) {
        is AbstractExternalDrone -> xyz.znix.xftl.hs.weapons.HsWeapons.shipId(a.targetShip)
        is BoardingDrone -> a.hsCollisionBody?.collisionSpaceId ?: shipId
        null -> storedCurrentSpace ?: shipId
        else -> shipId
    }

    open val luaExploding: Boolean get() = when (val a = instance) {
        is AbstractExternalDrone -> a.isBlowingUp
        is BoardingDrone -> a.hsCollisionBody?.collisionExploding == true
        else -> false
    }

    open var luaIonStun: Float
        get() = collisionIonStun
        set(value) { collisionIonStun = value }

    // ---- HsDroneCollisionBody: a deployed space drone's collision state is its actor's; an undeployed one is inert.
    private val externalActor: AbstractExternalDrone? get() = instance as? AbstractExternalDrone
    override val collisionReceiver: Any get() = this
    override val collisionOwner: Ship get() = ownerShip ?: error("drone ${type.name} has no owner")
    override val collisionSpaceId: Int get() = externalActor?.collisionSpaceId ?: shipId
    override val collisionSelfId: Int get() = hsSelfId
    override val collisionPosition: FPoint get() = externalActor?.collisionPosition ?: storedLocation
    override val collisionDodge: Int get() = type.dodge ?: 0
    override val collisionPowered: Boolean get() = powered
    override val collisionEnabled: Boolean get() = externalActor?.collisionEnabled ?: false
    override val collisionExploding: Boolean get() = externalActor?.collisionExploding ?: false
    override var collisionIonStun: Float
        get() = externalActor?.collisionIonStun ?: (fields[ION_STUN]?.toFloat() ?: 0f)
        set(value) { val a = externalActor; if (a != null) a.collisionIonStun = value else fields[ION_STUN] = value.toDouble() }
    override fun collisionBlowUp() { externalActor?.collisionBlowUp() }

    companion object {
        /** Lua bridge: members the native object keeps after an undeploy are snapshotted here (LuaAdaptersDrones). */
        @JvmStatic var onActorRemoved: ((NativeDrone) -> Unit)? = null

        /** HS's DRONE_* enumeration order (DroneBlueprint::type, Drone::type): FTL's drone type ids. */
        fun typeId(t: DroneBlueprint.DroneType): Int = when (t) {
            DroneBlueprint.DroneType.DEFENSE -> 0
            DroneBlueprint.DroneType.COMBAT -> 1
            DroneBlueprint.DroneType.REPAIR -> 2
            DroneBlueprint.DroneType.BATTLE -> 3
            DroneBlueprint.DroneType.BOARDER -> 4
            DroneBlueprint.DroneType.SHIP_REPAIR -> 5
            DroneBlueprint.DroneType.HACKING -> 6
            DroneBlueprint.DroneType.SHIELD -> 7
        }

        /** ShipManager::CreateDrone: crew drones (repair, battle) are CrewDrones, the rest SpaceDrones (hacking: none). */
        fun isSpaceType(t: DroneBlueprint.DroneType): Boolean =
            t != DroneBlueprint.DroneType.REPAIR && t != DroneBlueprint.DroneType.BATTLE

        /** Key for [fields]: an attribute id of LuaApi (kept numeric so this file needs no Lua types). */
        const val ION_STUN = -1

        val NONE: FPoint = ConstFPoint(-1f, -1f)

        /** The constructor's unset location: -FLT_MAX in both coordinates (DAT_008771bc). */
        val NONE_LOCATION: FPoint = ConstFPoint(-Float.MAX_VALUE, -Float.MAX_VALUE)
    }
}

/** A boarding pod's CrewDrone before it lands (BoarderPodDrone::GetBoardingDrone): a crew-type body of the pod's blueprint. */
class BoarderCrewBody(val pod: NativeDrone) : NativeDrone(pod.type, null, pod.boarderCrewSelfId) {
    override val isSpaceDrone: Boolean get() = false
    init { standaloneOwner = pod.ownerShip; joined = true; constructed = true }
}
