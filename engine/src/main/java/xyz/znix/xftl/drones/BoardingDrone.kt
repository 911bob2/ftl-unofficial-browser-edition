package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.f
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.FlyingDroneProjectile
import xyz.znix.xftl.weapons.IProjectile
import xyz.znix.xftl.weapons.ProjectileContact

class BoardingDrone(type: DroneBlueprint) : AbstractIndoorsDrone(type) {
    override val occupancySlotType get() = AbstractCrew.SlotType.INTRUDER

    // The ion intruder is the BOARDER_ION blueprint: IonDrone (vtable 0x90d060)
    // with IonDroneAnimation (0x90c504) instead of BoarderDrone.
    private val isIonIntruder: Boolean get() = type.name == "BOARDER_ION"
    override val pawnCodename: String get() = if (isIonIntruder) "boarder_ion" else "battle"

    private var projectile: FlyingDrone? = null

    override fun init(ownerShip: Ship) {
        super.init(ownerShip)

        // If we're being deserialised, don't launch another drone.
        if (projectile == null && pawn == null) {
            launchDroneProjectile()
        }
    }

    private fun launchDroneProjectile() {
        val enemyShip = ownerShip.sys.getEnemyOf(ownerShip)!!
        // The pod's real target is only picked when it enters the target's space
        // (BoarderPodDrone::PickDestination 0x420b70, see FlyingDrone.calculateTargetPosition),
        // so this room only gives the projectile its ship.
        projectile = FlyingDrone(enemyShip.rooms.first())

        // Start in the centre of the drones room, per vanilla
        val startingPoint = ownerShip.drones!!.room!!.pixelCentre

        // The direction the drone flies in depends on whether this is
        // a player ship or an enemy ship, as it's supposed to go
        // forwards out of both of them.
        val endPoint = startingPoint + ownerShip.weaponFireDirection * 5000

        projectile!!.setInitialPath(startingPoint, endPoint)

        ownerShip.projectiles += projectile!!
    }

    override fun updatePawn(dt: Float) {
        val pawn = this.pawn!!

        // The ion intruder's timers run whatever the pawn is doing.
        if (isIonIntruder)
            (pawn as BoardingPawn).tickIon(dt)

        // Once the ship we're aboard is no longer at war with the ship
        // that deployed us - for example all of its crew were just killed -
        // stop looking for things to break and stand idle (engine-side
        // guard, see [AbstractCrew.standingShipHostile]).
        if (!pawn.standingShipHostile)
            return

        // Are we moving somewhere
        if (pawn.pathingTarget != null)
            return

        if (isIonIntruder) { (pawn as BoardingPawn).updateIon(dt); return }

        // Do we have unfinished business here?
        if (roomHasWorkingSystem(pawn.room))
            return
        if (roomContainsHostileCrew(pawn.room))
            return

        // Pick a random room to go and punch up the contents of.
        // It can either have a non-broken system or it can have friendly (to
        // the ship, not to the drone) crew.
        val candidates = ship.rooms.filter { roomContainsHostileCrew(it) || roomHasWorkingSystem(it) }

        // Nothing more to break and kill :(
        if (candidates.isEmpty())
            return

        // If the room is full this won't do anything, but it doesn't matter
        // as we'll just try again next update.
        pawn.setTargetRoom(candidates.random(ftl.browser.BrowserRandom))
    }

    override fun update(dt: Float) {
        super.update(dt)

        // Check if the player ship jumped away or shot down the projectile
        if (pawn != null)
            return

        val projectile = this.projectile
        if (projectile == null) {
            // No projectile and no pawn? This probably shouldn't happen.
            destroy()
            return
        }

        // Check if the enemy ship changed, for example if it was killed.
        val enemyShip = ownerShip.sys.getEnemyOf(ownerShip)
        if (enemyShip == null || enemyShip != projectile.targetShip) {
            destroy()
            return
        }

        if (!ownerShip.projectiles.contains(projectile) && !enemyShip.projectiles.contains(projectile)) {
            // The projectile has vanished, for example if it was
            // greeted by a defence drone.
            destroy()
        }
    }

    private fun roomContainsHostileCrew(room: Room): Boolean {
        return room.crew.any { it.mode == AbstractCrew.SlotType.CREW }
    }

    private fun roomHasWorkingSystem(room: Room): Boolean {
        val system = room.system ?: return false

        // Can we damage it further?
        return system.damagedEnergyLevels != system.energyLevels
    }

    override fun removeInstance() {
        super.removeInstance()

        // If a flying drone was sent out, get rid of it.
        projectile?.destroyFlying()
    }

    override fun drawPawn() {
        // TODO draw the green glow when turned on
    }

    override fun makePawn(room: Room): Pawn = BoardingPawn(room)

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        if (projectile != null) {
            val projectileElem = Element("projectile")
            SaveUtil.addRoomRef(elem, "targetRoom", refs, projectile!!.target)
            projectile!!.saveToXML(projectileElem, refs)
            elem.addContent(projectileElem)
        }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        // Load the flying projectile first, before onInit is called.
        val projectileElem = elem.getChild("projectile")
        if (projectileElem != null) {
            SaveUtil.getRoomRef(elem, "targetRoom", refs) { target ->
                projectile = FlyingDrone(target)
                projectile!!.loadPropertiesFromXML(projectileElem, refs)
                containingShip.projectiles.add(projectile!!)
            }
        }

        super.loadFromXML(elem, refs, containingShip)
    }

    /**
     * The projectile that represents the drone flying through space towards
     * the target ship.
     */
    inner class FlyingDrone(target: Room) : FlyingDroneProjectile(target.ship) {
        var target: Room = target
            private set

        // We have to use our target ship for initialisation, as we're deserialised
        // before ownerShip is set.
        private val game = target.ship.sys

        // The portrait frame of the robot, which is shown on top
        // of the thruster sprite
        val portrait = game.animations["${pawnCodename}_portrait"].spriteAt(game, 0)

        val thruster = game.getImg("img/ship/drones/boarder_engine.png")

        // Boarder pods are SpaceDrones in the original: they dodge, can be
        // ion-stunned and explode like any other drone (0x4213d0, 0x421680).
        private val health = PodHealth(game, type.dodge ?: 0)

        private val isPodStunned: Boolean
            get() = health.ionTimer > 0f || (initialised && ownerShip.drones!!.isHackActive)

        // Fished out with x32dbg as I couldn't be bothered to find
        // it via static analysis, and it's not guaranteed to be correct
        // if I got some addresses swapped.
        // Note it has a 16x multiplier, to convert it from pixels per
        // SpeedFactor to pixels per second. See doc/reveng-general.md.
        override val speed: Int
            get() = when {
                // Pause when the drone is powered off
                !isPowered -> 0

                // A stunned or exploding pod doesn't move
                isPodStunned || health.isBlowingUp -> 0

                // Pause when the target is cloaked
                target.ship.isCloakActive -> 0

                // Regular speed
                else -> 18 * 16
            }

        override var drawUnderShip: Boolean = true

        override val isMissileForDD: Boolean get() = true

        // Can't collide with drones
        override val antiDroneBP: AbstractWeaponBlueprint? get() = null
        override val antiDroneExemption: Ship? get() = null

        override val drone: AbstractDrone get() = this@BoardingDrone

        // SpaceDrone::CollisionMoving only applies once the pod is out in the
        // target's space (it is hidden under its owner until then), and not
        // while it is already exploding.
        override val collisionsEnabled: Boolean get() = !drawUnderShip && !health.isBlowingUp

        // SpaceDrone's ten pixel contact distance, less a projectile's three.
        override val hitboxRadius: Int get() = 7

        override fun onProjectileContact(other: IProjectile): ProjectileContact {
            return health.contact(other, ownerShip, target.ship, position, isPowered)
        }

        override fun update(dt: Float, currentSpace: Ship) {
            super.update(dt, currentSpace)

            // The pod is destroyed (and the drone starts its ten second
            // rebuild) once the explosion animation has finished.
            if (health.update(dt, initialised && ownerShip.drones!!.isHackActive, isPowered)) {
                destroy()
            }
        }

        override fun reachedTarget() {
            // We've hit our target room.

            destroyFlying()

            // Spawn the drone pawn in the target room
            spawn(target)
            target.spawnBreach()

            // If the drone ended up in a different room due to
            // the target being full, move it back but leave it
            // pathing to somewhere else.
            if (pawn!!.room != target) {
                pawn!!.jumpTo(target, ConstPoint.ZERO)
            }
        }

        fun destroyFlying() {
            dead = true
            projectile = null

            // If there's even a single update where both the drone and projectile
            // are alive, this will cause a great deal of trouble since the drone
            // will be serialised twice - once due to being in the projectiles list,
            // and once from being in the crew list.
            // Thus we have to remove ourselves immediately.
            target.ship.projectiles.remove(this)
        }

        override fun renderPreTranslated(g: Graphics) {
            // An exploding pod is drawn as just the explosion (0x422040).
            // Undo the pod's rotation so the animation isn't turned.
            g.pushTransform()
            g.rotate(0f, 0f, -Math.toDegrees(rotation.toDouble()).toFloat())
            val exploding = health.renderExplosion()
            g.popTransform()
            if (exploding)
                return

            // Centre the drone on the supplied x,y coordinates.
            val size = portrait.imageSize
            val offset = ConstPoint(-size.x / 2, -size.y / 2)

            // 'up' in the sprite is forwards, rotate it so that is the case.
            val angleOffset = 90 // In degrees because that's what Slick uses.

            g.rotate(0f, 0f, angleOffset.f)

            portrait.draw(offset)
            thruster.draw(offset)
        }

        override fun onSwitchedToTarget() {
            super.onSwitchedToTarget()

            drawUnderShip = false
        }

        override fun calculateTargetPosition(): IPoint {
            // BoarderPodDrone::PickDestination (0x420b70) runs when the pod enters the target's
            // space: the destination is ShipManager::GetRandomTargettingPoint(true) (0x49b450),
            // the centre of the room of a uniformly random system (rand % systemCount). The
            // original would divide by zero on a ship with no systems, so any room is used then.
            val rooms = target.ship.rooms
            val withSystem = rooms.filter { it.system != null }
            target = (withSystem.ifEmpty { rooms }).random(ftl.browser.BrowserRandom)
            return target.pixelCentre
        }

        // Pods are never removed by hitting a projectile (onProjectileContact
        // never reports DESTROYED), they explode and are removed by update.
        override fun hitOtherProjectile(currentSpace: Ship) {
        }

        override fun crossedShieldLine() {
            super.crossedShieldLine()

            // Hitting a super shield doesn't damage it, but does destroy the drone.
            if (target.ship.superShield > 0) {
                health.blowUp()
            }
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addAttrBool(elem, "drawUnderShip", drawUnderShip)
            health.saveToXML(elem)
        }

        override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
            super.loadPropertiesFromXML(elem, refs)

            drawUnderShip = SaveUtil.getAttrBool(elem, "drawUnderShip")
            health.loadFromXML(elem)
        }
    }

    private inner class BoardingPawn(room: Room) : AbstractIndoorsDrone.Pawn(room) {
        // Always fight by shooting a laser
        override val canPunch: Boolean get() = false
        override val canFight: Boolean get() = !isIonIntruder
        // CrewMember::CanSabotage is 1 for the IonDrone too, and its animation only charges its
        // pulse while sabotaging (animation state 7, IonDroneAnimation update 0x440490).
        override val canSabotage: Boolean get() = true
        override val doorAttackMultiplier: Int get() = if (isIonIntruder) 4 else 1
        override val attackDamageMult: Float get() = 1.2f

        override val baseMaxHealth: Float get() = if (isIonIntruder) 125f else 150f

        // The pulse cadence is IonDroneAnimation's, see IonPulseClock.
        private val ionClock = IonPulseClock()

        private val isSabotagingIon: Boolean
            get() {
                val system = room.system
                return pathingTarget == null && system != null && !system.broken
            }

        fun tickIon(dt: Float) {
            if (ionClock.tick(dt, isSabotagingIon))
                room.ship.sys.sounds.getSampleOrWarn("ionBoarderCharge")?.play()
        }

        fun updateIon(dt: Float) {
            val system = room.system
            if (system == null || system.broken) { moveToIonTarget(); return }
            if (!ionClock.isReady) return

            ionClock.fired()
            room.ship.sys.sounds.getSampleOrWarn("ionHit1")?.play()
            system.dealDamage(0, 3)
            for (crew in room.crew) {
                if (crew.mode != mode && !(crew is AbstractIndoorsDrone.Pawn && crew.drone is BoardingDrone)) crew.stun(6f)
            }
            room.ship.playCentredAnimation(room.ship.sys.animations["stun_spark_big"], room.pixelCentre)
            moveToIonTarget()
        }
        private fun moveToIonTarget() {
            val rooms = room.ship.rooms.filter { it != room && it.system?.broken == false }
            if (rooms.isNotEmpty()) setTargetRoom(rooms.random(ftl.browser.BrowserRandom))
        }
        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)
            ionClock.save(elem)
        }
        override fun loadFromXML(elem: Element, refs: RefLoader) {
            super.loadFromXML(elem, refs)
            ionClock.load(elem)
        }
    }
}
