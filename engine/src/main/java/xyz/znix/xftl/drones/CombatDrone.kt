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

    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var chargedImage: Image

    private lateinit var weapon: AbstractWeaponInstance

    private var fireTimer: Float = 0f

    /**
     * Passes left before the drone leaves (SpaceDrone+0x250), for the
     * flagship's surge drones; null for ordinary drones, which never run out.
     */
    var browserPassesLeft: Int? = null

    private lateinit var target: Room

    init {
        requireNotNull(type.weaponBlueprint) { "Missing weapon blueprint for combat drone '${type.name}'" }
    }

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_base.png")
        onImage = game.getImg("img/ship/drones/${type.droneImage}_on.png")
        chargedImage = game.getImg("img/ship/drones/${type.droneImage}_charged.png")

        weapon = type.weaponBlueprint!!.buildInstance(ownerShip)

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

        // Make Kotlin smart-casts work with a mutable field
        val weapon = this.weapon

        // A drone whose target ship is gone has nothing to shoot at:
        // DroneSystem::OnLoop (0x639a50) recovers it once CanBeDeployed
        // (HasTarget, vtable 0x80) fails. Until that happens, the weapon is
        // switched off rather than firing at a ship we're no longer fighting.
        val targetHostile = ownerShip.sys.getEnemyOf(ownerShip) == targetShip

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

                if (targetHostile) {
                    fire()
                    pickNewTarget()
                }

                browserPassesLeft?.let {
                    browserPassesLeft = it - 1
                    if (it - 1 <= 0) {
                        removeInstance()
                        return
                    }
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
        if (fireTimer <= 0f && !firingBeam) {
            flightController.paused = false
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

        when (val weapon = weapon) {
            is IRoomTargetingWeapon -> weapon.fireFromDrone(this, target)

            is BeamBlueprint.BeamInstance -> {
                // Beam drones take a second random room for the far end of
                // the sweep, re-rolled until it is a different point.
                val endRoom = if (targetShip.rooms.size < 2) target else {
                    var room: Room
                    do {
                        room = targetShip.rooms.random(ftl.browser.BrowserRandom)
                    } while (room == target)
                    room
                }
                val aim = weapon.buildAim(target, endRoom, false)

                // Set the weapon to start firing
                weapon.fireFromDrone(this, aim, BEAM_FIRE_TIME)

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

        // Tell the drone where it is, so it can rotate accordingly
        flightController.nextStopTarget = target.pixelCentre
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "fireTimer", fireTimer)
        SaveUtil.addRoomRef(elem, "target", refs, target)

        val weaponElem = Element("weapon")
        weapon.saveToXML(weaponElem, refs)
        elem.addContent(weaponElem)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        fireTimer = SaveUtil.getAttrFloat(elem, "fireTimer")
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
