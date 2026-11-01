package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon

class CombatDrone(type: DroneBlueprint) : AbstractExternalDrone(type, true) {
    override val flightController = CombatFlightController(this)

    /** Hyperspace Lua: CombatDrone::drone_image_off, charging, on written by a script (0, 1, 2). */
    fun hsSetImage(slot: Int, image: Image) { when (slot) { 0 -> offImage = image; 1 -> chargedImage = image; 2 -> onImage = image } }

    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var chargedImage: Image

    private lateinit var weapon: AbstractWeaponInstance

    internal var fireTimer: Float = 0f

    /**
     * Passes left before the drone leaves (SpaceDrone+0x250), for the
     * flagship's surge drones; null for ordinary drones, which never run out.
     */
    var browserPassesLeft: Int? = null

    internal lateinit var target: Room

    /**
     * The original's aiming members (Lua-visible; this engine's drawing uses the flight controller's rotation):
     * SpaceDrone aimingAngle/lastAimingAngle/desiredAimingAngle/hackAngle/beamSpeed (+0x170/+0x174/+0x178/+0x258/
     * +0x270) and CombatDrone::progressToDestination (+0x348), constructor values 0/0/-1/0/0/1. Degrees, atan2 of
     * the screen vector.
     */
    var nativeAimingAngle = 0f
    var nativeLastAimingAngle = 0f
    var nativeDesiredAimingAngle = -1f
    var nativeHackAngle = 0f
    var nativeBeamSpeed = 0f
    var nativeProgress = 1f

    /** SpaceDrone::targetLocation's room (none before the first PickTarget). */
    internal val targetRoomOrNull: Room? get() = if (this::target.isInitialized) target else null

    /** CombatDrone::PickTarget, called from Lua. */
    internal fun luaPickTarget() { if (targetShip.rooms.isNotEmpty()) pickNewTarget() }

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_base.png")
        onImage = game.getImg("img/ship/drones/${type.droneImage}_on.png")
        chargedImage = game.getImg("img/ship/drones/${type.droneImage}_charged.png")

        weapon = (type.weaponBlueprint ?: ownerShip.sys.blueprintManager.inertWeapon).buildInstance(ownerShip)

        // Tell beam weapons they're on a drone, and thus they shouldn't use
        // the normal incoming beam system
        (weapon as? BeamBlueprint.BeamInstance)?.isOnDrone = true

        flightController.onReachedDestination = this::onReachedDestination
        flightController.pauseStopTime = 0.5f

        pickNewTarget()
    }

    override fun renderExternal(g: Graphics) {
        // If we're a beam drone that's currently firing, draw
        // the beam underneath us so you can't see the point it
        // ends at.
        // We have to do it here rather than in onRender, since
        // that's called with transforms added so we draw
        // stuff relative to the drone.
        val beam = this.weapon as? BeamBlueprint.BeamInstance
        beam?.drawDroneBeam(g, this)

        super.renderExternal(g)
    }

    override fun onRender(g: Graphics) {
        val image = when {
            !isRunning -> offImage
            flightController.paused -> chargedImage
            else -> onImage
        }

        drawCentred(image)
    }

    override fun update(dt: Float) {
        super.update(dt)

        // CombatDrone's loop (0x4285b0) starts (past the explosion) with lifespan < 1 -> SetDestroyed(true, false):
        // the drone that used its last pass goes on the loop after that pass's shot.
        // (Drone::SetDestroyed 0x462110 with no rebuild timer; a drone without a body, the flagship's surge drones,
        // simply leaves.)
        if (dt > 0f && !isBlowingUp) browserPassesLeft?.let {
            if (it < 1) { val b = body; if (b != null) b.setDestroyed(true, false) else removeInstance(); return }
        }

        // Make Kotlin smart-casts work with a mutable field
        val weapon = this.weapon

        // The beam sweep (SpaceDrone::additionalPause, 0.5 s from GetNextProjectile 0x429ee0): its pass is used up
        // on the loop where the pause drops below zero.
        if (dt > 0f && isRunning && sweepTimer > 0f) {
            sweepTimer -= dt
            if (sweepTimer < 0f) {
                sweepTimer = 0f
                browserPassesLeft?.let { browserPassesLeft = it - 1 }
            }
        }

        if (dt > 0f && isRunning) updateNativeAim(weapon)

        // A drone whose target ship is gone has nothing to shoot at:
        // DroneSystem::OnLoop (0x639a50) recovers it once CanBeDeployed
        // (HasTarget, vtable 0x80) fails. Until that happens, the weapon is
        // switched off rather than firing at a ship we're no longer fighting.
        // A drone made by CreateSpaceDrone (no drone system) shoots at the weapon target its script gave it, even its
        // own ship (MV temporary drones; oracle wp4-drone-body: the drone completes a pass).
        val body = this.body
        val targetHostile = ownerShip.sys.getEnemyOf(ownerShip) == targetShip ||
            (body != null && body.system == null && body.weaponTarget === targetShip)

        // Turn the weapon on and off to match the drone.
        // This makes beams stop firing when the drone is de-powered
        // while shooting - or when the fight it was in is over.
        weapon.forceSetPowered(isRunning && targetHostile)
        weapon.update(dt, dt, weapon.isPowered)

        if (!flightController.paused || !isRunning)
            return

        if (fireTimer != 0f) {
            fireTimer -= dt
            if (fireTimer <= 0f) {
                fireTimer = 0f

                // The loop sets bFire and uses up a pass (0x4285b0); the shot itself and the next target come from
                // GetNextProjectile (0x429ee0, PickTarget 0x422ff0 at its start, called again after a shot), which
                // SpaceManager runs for every drone after the projectile loop ([nativeNextProjectile]). Measured on
                // native 1.6.14: the shot and the new target's GetRandomTargettingPoint in the same frame, after it.
                browserPassesLeft?.let { browserPassesLeft = it - 1 }
                if (targetHostile) {
                    val inSpace = body?.let { DroneRuntime.loopsInSpace(it) } == true
                    if (inSpace) firePending = true else { fire(); pickNewTarget() }
                }
            }
        }

        // Fire our beam, if it's still active.
        var firingBeam = false
        if (weapon is BeamBlueprint.BeamInstance) {
            firingBeam = weapon.isFiring

            // Match our rotation to that of the beam
            if (firingBeam) {
                val target = weapon.getCurrentTargetPoint()
                flightController.rotation = DroneFlightController.getAngleFrom(flightController.position, target)
            }
        }

        // Un-pause the drone once we've fired our weapon and (if we're
        // a beam drone) we're done firing our beam.
        if (fireTimer <= 0f && !firingBeam && !(firePending && weapon is BeamBlueprint.BeamInstance)) {
            flightController.paused = false
        }
    }

    /**
     * CombatDrone's loop (1.6.14 0x4285b0) for the aiming members: FUN_00427fb0 copies aimingAngle to hackAngle;
     * while flying, progressToDestination = |current - lastDestination| / |lastDestination - destination| and
     * UpdateAimingAngle (0x427460) eases the aim from lastAimingAngle towards the angle from the destination to
     * targetLocation by that progress; while a beam sweeps (additionalPause) it aims from the drone at the beam's
     * moving end (percentage 1). The stopping pause leaves the aim alone.
     */
    private fun updateNativeAim(weapon: AbstractWeaponInstance) {
        nativeHackAngle = nativeAimingAngle
        val fc = flightController
        val beam = weapon as? BeamBlueprint.BeamInstance
        if (beam != null && beam.isFiring) {
            val p = beam.getCurrentTargetPoint()
            nativeAim(fc.position.xf, fc.position.yf, p.x.toFloat(), p.y.toFloat(), 1f)
            return
        }
        if (fc.paused || !this::target.isInitialized) return
        val last = fc.lastDestination ?: return
        val dest = fc.nextDestination
        val span = Math.hypot((dest.x - last.x).toDouble(), (dest.y - last.y).toDouble()).toFloat()
        val done = Math.hypot((fc.position.xf - last.x).toDouble(), (fc.position.yf - last.y).toDouble()).toFloat()
        nativeProgress = done / span
        val t = target.pixelCentre
        nativeAim(dest.x.toFloat(), dest.y.toFloat(), t.x.toFloat(), t.y.toFloat(), nativeProgress)
    }

    /** UpdateAimingAngle (0x427460) with a percentage >= 0 and no forced angle. */
    private fun nativeAim(fromX: Float, fromY: Float, toX: Float, toY: Float, percentage: Float) {
        var desired = Math.toDegrees(Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())).toFloat()
        if (Math.abs(nativeAimingAngle - desired) > 180f) desired += if (desired >= 0f) -360f else 360f
        nativeDesiredAimingAngle = desired
        nativeAimingAngle = (desired - nativeLastAimingAngle) * percentage + nativeLastAimingAngle
    }

    /**
     * CombatDrone::PickTarget (0x422ff0) with targetLocation unset: the aim is folded into [-180, 180] and becomes
     * lastAimingAngle, then the new target point is taken.
     */
    private fun nativeAimNewTarget() {
        var a = nativeAimingAngle
        if (a > 180f) a -= 360f else if (a < -180f) a += 360f
        nativeAimingAngle = a
        nativeLastAimingAngle = a
    }

    /** SpaceDrone::GetNextProjectile (0x429ee0): beamSpeed = the new shot's speed_magnitude. */
    private fun nativeShotSpeed(weapon: AbstractWeaponInstance): Float {
        val s = weapon.type.speed?.takeIf { it != 0 }
        return when (weapon.type) {
            is BeamBlueprint -> (s ?: 5).toFloat()
            is xyz.znix.xftl.weapons.MissileBlueprint -> (s ?: 35).toFloat()
            is xyz.znix.xftl.weapons.BombBlueprint -> (s ?: 0).toFloat()
            else -> (s ?: 60).toFloat()
        }
    }

    /** bFire set by this frame's loop, waiting for the GetNextProjectile pass. */
    private var firePending = false

    /** SpaceDrone::additionalPause: the beam sweep still to run (seconds). */
    var sweepTimer = 0f
        private set

    /**
     * SpaceManager's GetNextProjectile pass (after the projectile loop): the shot bFire asked for, then PickTarget.
     * A beam's GetNextProjectile gives the pass back (lifespan + 1) and starts the 0.5 s sweep; the sweep's end
     * takes it (see [update]).
     */
    internal fun nativeNextProjectile() {
        if (!firePending) return
        firePending = false
        if (!isRunning) return
        fire()
        DroneRuntime.trace?.out?.append(" c")
        pickNewTarget()
        if (weapon is BeamBlueprint.BeamInstance) {
            browserPassesLeft?.let { browserPassesLeft = it + 1 }
            sweepTimer = BEAM_FIRE_TIME
        }
    }

    private fun onReachedDestination() {
        fireTimer = 0.5f
        flightController.paused = true
    }

    private fun fire() {
        // If we're cloaked, we should still pause as if we would fire, just not actually do so.
        if (targetShip.isCloakActive)
            return
        nativeBeamSpeed = nativeShotSpeed(weapon)

        when (val weapon = weapon) {
            is IRoomTargetingWeapon -> weapon.fireFromDrone(this, target)

            is BeamBlueprint.BeamInstance -> if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.weapons.HsWeapons.isPinpoint(weapon.type)) {
                // Hyperspace CombatDrone::PickTarget: a pinpoint beam drone aims at a random slot of its room.
                weapon.fireFromDrone(this, xyz.znix.xftl.hs.weapons.HsWeapons.pinpointAim(weapon, target, -1, null), BEAM_FIRE_TIME)
                weapon.type.launchSounds?.get()?.play()
            } else {
                // Beam drones take a second random room for the far end of
                // the sweep, re-rolled until it is a different point.
                val endRoom = if (targetShip.rooms.size < 2) target else {
                    var room: Room
                    do {
                        room = targetShip.rooms.random(ftl.browser.BrowserRandom)
                        DroneRuntime.trace?.out?.append(" Gb")
                    } while (room == target)
                    room
                }
                val aim = weapon.buildAim(target, endRoom, false)

                // Set the weapon to start firing. SpaceDrone::GetNextProjectile (0x429ee0): the beam's target2 is
                // the second random targeting point (ShipManager::GetRandomTargettingPoint(false) 0x49b450, a room
                // centre, re-rolled until it differs), the swipe running from the drone's target point towards it.
                weapon.fireFromDrone(this, aim, BEAM_FIRE_TIME, endRoom.pixelCentre)

                // Play the beam sound effect
                weapon.type.launchSounds?.get()?.play()
            }

            else -> error("Unsupported weapon type for drone: ${weapon.type}")
        }
    }

    private fun pickNewTarget() {
        // PickTarget (0x422ff0): the target is ShipManager::GetRandomTargettingPoint
        // (0x49b450), a uniformly random room's centre (rand % roomCount).
        target = targetShip.rooms.random(ftl.browser.BrowserRandom)
        DroneRuntime.trace?.out?.append(" G")
        nativeAimNewTarget()

        // Tell the drone where it is, so it can rotate accordingly
        flightController.nextStopTarget = target.pixelCentre
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "fireTimer", fireTimer)
        if (sweepTimer > 0f) SaveUtil.addAttrFloat(elem, "sweepTimer", sweepTimer)
        if (firePending) SaveUtil.addAttrBool(elem, "firePending", true)
        SaveUtil.addRoomRef(elem, "target", refs, target)

        val weaponElem = Element("weapon")
        weapon.saveToXML(weaponElem, refs)
        elem.addContent(weaponElem)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        fireTimer = SaveUtil.getAttrFloat(elem, "fireTimer")
        sweepTimer = elem.getAttributeValue("sweepTimer")?.toFloatOrNull() ?: 0f
        firePending = elem.getAttributeValue("firePending") == "true"
        SaveUtil.getRoomRef(elem, "target", refs) { target = it }

        // We can't load the weapon until onInit was run to initialise it.
        refs.addOnResolveFunction {
            val weaponElem = elem.getChild("weapon")
            weapon.loadFromXML(weaponElem, refs)
        }
    }

    companion object {
        // All beams fire for 0.5 seconds.
        private const val BEAM_FIRE_TIME = 0.5f
    }
}
