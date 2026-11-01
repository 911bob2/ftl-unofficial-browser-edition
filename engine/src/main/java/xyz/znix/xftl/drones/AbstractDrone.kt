package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.FTLSound
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.floor
import kotlin.random.Random

abstract class AbstractDrone(val type: DroneBlueprint) {
    var isPowered: Boolean = false
        set(value) {
            val changed = value != field
            field = value
            if (changed)
                onPowerChanged()
        }

    /**
     * Like [isPowered], but also false if the drone is stunned.
     */
    open val isRunning: Boolean get() = isPowered && !isStunned

    lateinit var ownerShip: Ship
        private set

    /** [ownerShip], or null before [init] (a drone being loaded: init runs once the save's references resolve). */
    val ownerShipOrNull: Ship? get() = if (::ownerShip.isInitialized) ownerShip else null

    /**
     * The native Drone object this actor belongs to (the equipped drone's [NativeDrone], or one made for an actor
     * launched without a drone system). It is the Lua identity and keeps the state that outlives a deployment.
     */
    var body: NativeDrone? = null
        internal set

    /** [body], creating a standalone one for an actor that has none (super/surge drones, diagnostics). */
    val luaBody: NativeDrone
        get() = body ?: NativeDrone(type, this).also { it.standaloneOwner = ownerShipOrNull }

    private var ownSelfId: Int = -1

    /** Hyperspace Drone::selfId (a SpaceId): the body's id; for a body-less actor drawn on first Lua read, -1 until then. */
    var hsSelfId: Int
        get() = body?.hsSelfId ?: ownSelfId
        set(value) { val b = body; if (b != null) b.hsSelfId = value else ownSelfId = value }

    /** Drone::hackTime (+0x34): the stun counter of [update]. */
    var hackTime: Float
        get() = stunTotalTimer
        set(value) { stunTotalTimer = value }

    protected var initialised: Boolean = false
        private set

    /** [init] has run (a Hyperspace crew drone body deploys again without it: NativeDrone.deployActor). */
    val isInitialisedActor: Boolean get() = initialised

    protected lateinit var explodeAnimation: AnimationSpec

    /** The SpaceDrone's explosion: GetAnimation("explosion_random") picked once when the drone was made. */
    val explosionSpec: AnimationSpec? get() = if (this::explodeAnimation.isInitialized) explodeAnimation else null
    protected lateinit var explodeSound: FTLSound

    // A drone without a drone system behind it (Hyperspace super drones, launched by any ship) is never hacked.
    open val isStunned: Boolean get() = ownerShip.drones?.isHackActive ?: false

    /**
     * Movement speed multiplier from ship augments (Drone Reactor
     * Booster, DRONE_SPEED). Only crew drones walk faster with it: its
     * two references are both in CrewMember::GetMoveSpeed (0x47b450).
     */
    fun getSpeedMult(): Float {
        if (!::ownerShip.isInitialized)
            return 1f

        return 1f + ownerShip.getAugmentValue(AugmentBlueprint.DRONE_SPEED)
    }

    internal var stunTotalTimer: Float = 0f

    /** Hyperspace vCrewList: a drone that is not deployed (no [init], no body spawned) still names its ship. */
    internal fun hsBindOwner(ship: Ship) {
        if (!::ownerShip.isInitialized) ownerShip = ship
    }

    open fun init(ownerShip: Ship) {
        if (initialised)
            error("Cannot re-initialise drone ${type.name}")

        explodeAnimation = ownerShip.sys.animations["explosion_random"]
        explodeSound = ownerShip.sys.sounds.getSample("smallExplosion")

        this.ownerShip = ownerShip
        initialised = true
    }

    /**
     * Drone::BlowUp (0x4621c0, SpaceDrone 0x4215f0): the drone has been hit
     * by something lethal or died of its stun. Drones that show an explosion
     * override this to play it and only destroy themselves once it ends;
     * the default destroys the drone straight away.
     *
     * @param silent True to skip the explosion sound.
     */
    open fun blowUp(silent: Boolean = false) {
        destroy()
    }

    /**
     * Remove this drone instance, setting a cooldown before
     * it can be re-deployed.
     *
     * This should be called when a drone is destroyed by something,
     * for example a repair drone destroyed by boarders.
     */
    open fun destroy() {
        // Do this before calling removeInstance, otherwise we won't
        // be able to find the info instance.
        // (A Hyperspace super drone has no drone system behind it.)
        val info = body ?: ownerShip.drones?.drones?.firstOrNull { it?.instance == this }
        info?.cooldown = DRONE_DESTROYED_COOLDOWN

        removeInstance()
    }

    /**
     * Remove this instance of this drone.
     *
     * This does less than [destroy]: it doesn't set a cooldown or show
     * an explosion animation or anything like that, it only removes
     * the drone instance.
     */
    open fun removeInstance() = removeInstance(false)

    /** [keepDeployed]: the body stays `deployed` (a dead drone without a system, see NativeDrone.setDestroyed). */
    open fun removeInstance(keepDeployed: Boolean) {
        // Drones that have been moved to cargo won't have an associated
        // info any more, so we have to use firstOrNull.
        val info = body ?: ownerShip.drones?.drones?.firstOrNull { it?.instance == this }
        info?.actorRemoved(this, keepDeployed)
    }

    open fun update(dt: Float) {
        // Drone::OnLoop (0x462220) only counts for a powered drone: an
        // unpowered one starts its stun count again from zero. (Measured live against native, 35bcf09. The
        // decompiled 0x462220/SpaceDrone::GetStunned 0x756a10 test no power; the power dependence comes from
        // elsewhere in the native loop and is kept as measured.)
        if (isStunned && isPowered) {
            // Drone::OnLoop (0x462220): a stunned drone (hacking pulse or
            // ion) rolls rand%20 < 3 each time its stun timer passes a
            // whole second beyond two, and blows up on a hit.
            val previous = stunTotalTimer
            stunTotalTimer += dt
            if (floor(stunTotalTimer) > floor(previous) && stunTotalTimer > 2f &&
                ftl.browser.BrowserRandom.nextInt(20) < 3
            ) {
                blowUp()
            }

            return
        }
        stunTotalTimer = 0f
    }

    protected open fun onPowerChanged() {
    }

    /**
     * Called whenever something potentially important happens to the enemy
     * ship, such as it being destroyed or changing as we jump away.
     */
    open fun onEnemyShipUpdated() {
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttr(elem, "type", type.name)
        SaveUtil.addAttrRef(elem, "owner", refs, ownerShip)
        SaveUtil.addAttrBool(elem, "powered", isPowered)

        SaveUtil.addTagFloat(elem, "stunTotalTimer", stunTotalTimer, 0f)

        // Save a reference to the slot of our owning drones system that we're in.
        // This is how we link ourselves back up with the DroneInfo object.
        val slot = ownerShip.drones?.drones?.indexOfFirst { it?.instance == this } ?: -1
        SaveUtil.addAttrInt(elem, "slot", if (slot == -1) null else slot)
    }

    // containingShip is the ship that the drone was serialised inside of.
    // This is not always the same as ownerShip - for example, boarding drones
    // or combat drones are serialised as part of the ship they're attacking,
    // but obviously belong to the other ship.
    open fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        require(type.name == SaveUtil.getAttr(elem, "type"))

        isPowered = SaveUtil.getAttrBool(elem, "powered")

        stunTotalTimer = SaveUtil.getOptionalTagFloat(elem, "stunTotalTimer") ?: 0f

        // This runs after all the other initialisation
        val slot = SaveUtil.getAttrIntOrNull(elem, "slot")
        SaveUtil.getAttrRef(elem, "owner", refs, Ship::class.java) {
            init(it!!)

            if (slot == null) {
                return@getAttrRef
            }

            val info = it.drones!!.drones[slot]!!

            // A couple of simple checks to make sure we're not ending up in the wrong slot.
            require(info.type == type)
            require(info.instance == null)

            info.instance = this
        }
    }

    companion object {
        const val DRONE_DESTROYED_COOLDOWN: Float = 10f
    }
}
