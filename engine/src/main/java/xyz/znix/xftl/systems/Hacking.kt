package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.Constants.ROOM_SIZE
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Renderable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractProjectile
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IProjectile
import xyz.znix.xftl.weapons.ProjectileContact
import xyz.znix.xftl.drones.PodHealth
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class Hacking(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.HACKING
    override val insertButtonSpace: Boolean get() = true
    override val holdsPermanentLock: Boolean get() = timeRemaining != null
    override val hasWhiteLockingBox: Boolean get() = active

    /**
     * Is the hacking module powered up, regardless
     * of whether it's actively hacking?
     *
     * This controls passive effects like locking doors
     * and disabling a room's console. HackingSystem::OnLoop stops all
     * hacking while an enemy pulse hacks this system.
     */
    val isPoweredUp: Boolean get() = powerSupplied != 0 && !isHackActive

    /**
     * If true, the main hacking pulse is currently active.
     */
    // HackingSystem::PulseActive (0x62d820) is `lock count == -1`: an ion hit that overwrote the lock
    // (ShipSystem::IonDamage 0x63d830) ends the pulse's effect and freezes its timer; the system's own
    // OnLoop (0x62dc80) sets the hacked system back to level 1 on the next frame.
    val active: Boolean get() = timeRemaining != null && !permanentLockOverridden

    val droneLaunched: Boolean get() = projectile != null
    val droneLanded: Boolean get() = projectile?.hasLanded == true
    val droneInFlight: Boolean get() = projectile?.hasLanded == false

    private var buttonHeight: Int = 1

    private var timeRemaining: Float? = null

    // The pulse length the timer was last scaled to; zero until a pulse runs.
    private var pulseDuration: Float = 0f

    // HackingSystem's pulse lengths (0x885188): 4/7/10 seconds by power.
    private val duration get() = 1f + powerSupplied.coerceAtLeast(1) * 3f

    // If the player selects a target, the drone isn't deployed
    // until the next update, so you can cancel it.
    private var selectedTarget: Room? = null

    private var button: HackButton? = null

    private var projectile: HackingDroneProjectile? = null
        set(value) {
            field = value

            // The power button size changes when the drone lands or is killed
            updateButton()
        }

    private val launchSound by onInit { it.sounds.getSample("droneLaunch") }
    private val landSound by onInit { it.sounds.getSample("hackLand") }
    private val startSound by onInit { it.sounds.getSample("hackStart") }
    private val loopSound by onInit { it.sounds.getLoop("hackLoop") }

    override fun powerStateChanged() {
        super.powerStateChanged()
        updateButton()
    }

    private fun updateButton() {
        // If the probe hasn't landed, only show one power.
        // Otherwise use the selected power, clamped to at least one.
        val height = when (projectile) {
            null -> 1
            else -> powerSelected.coerceAtLeast(1)
        }

        // Only bother to update the UI if this is a) on the player
        // ship, and b) the power really did change.
        if (ship == ship.sys.player && buttonHeight != height) {
            buttonHeight = height

            // Null when a new game starts
            ship.sys.shipUI?.updateButtons()
        }
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        button = HackButton(buttonHeight, powerPos)
        return listOf(button!!)
    }

    override fun update(dt: Float) {
        super.update(dt)

        val remaining = timeRemaining
        if (remaining != null) {
            if (active)
                loopSound.continueLoopAnyShip()

            // HackingSystem::OnLoop (0x62dc80): the pulse ends, locking the
            // system for four units, once the drone is gone, the system is
            // unpowered or an enemy pulse hacks this system.
            if (projectile?.hasLanded != true || powerSupplied == 0 || isHackActive) {
                endPulse()
            } else if (permanentLockOverridden) {
                // An ion hit replaced the pulse's lock: the pulse stands
                // still (0x62dc80 only counts the pulse up at lock -1).
            } else {
                // A power change keeps the elapsed share of the pulse.
                val length = duration
                var left = remaining
                if (pulseDuration > 0f && pulseDuration != length)
                    left = left / pulseDuration * length
                pulseDuration = length

                left -= dt
                if (left <= 0f) endPulse() else timeRemaining = left
            }
        }

        val projectile = this.projectile
        if (projectile != null) {
            // Is the projectile gone? A drone shot down in flight leaves
            // the system free to launch another one straight away.
            if (!ship.projectiles.contains(projectile) && !projectile.target.ship.projectiles.contains(projectile)) {
                this.projectile = null
            }
            if (!ship.sys.isShipPresent(projectile.target.ship)) {
                this.projectile = null
            }
        }

        // If the player/AI commanded a launch, perform it.
        selectedTarget?.let {
            deployProbe(it)
            selectedTarget = null
        }
    }

    /**
     * Called when the user clicks on a room to launch the hacking drone,
     * or the AI has picked a room to target.
     */
    fun selectTarget(target: Room) {
        selectedTarget = target

        // deployProbe will be called when next unpaused.
    }

    private fun deployProbe(target: Room) {
        if (projectile != null)
            return

        // Block hacking while we're turned off. It's not possible
        // to select this via the player UI, unless hacking is hit
        // while you're selecting a room.
        if (powerSupplied == 0)
            return

        // Also respect the ion/hack/cooldown lock: the post-destruction
        // cooldown runs through the ion timer, so this covers both the
        // player's button and the AI's launch path.
        if (isPowerLocked)
            return

        // Block hacking empty rooms
        if (target.system == null)
            return

        // Super shields block the probe. This is for the AI, as
        // the player will be blocked from selecting a room.
        if (target.ship.superShield > 0)
            return

        // Consume a drone part
        if (ship.dronesCount <= 0)
            return
        ship.dronesCount--

        // The direction the probe flies in depends on whether this is
        // a player ship or an enemy ship, as it's supposed to go
        // forwards out of both of them.
        // Note we need to use a very large value, so it's always
        // outside of the -800 to 800 bounds - otherwise it'll think
        // it reached the enemy ship.
        val endPoint = room!!.pixelCentre + ship.weaponFireDirection * 5000

        projectile = HackingDroneProjectile(target).also {
            it.hacking = this
            it.setInitialPath(room!!.pixelCentre, endPoint)
            ship.projectiles += it
        }

        launchSound.play()
    }

    // Called by AbstractSystem to check if we're still attacking a given room.
    // This is to make the game robust to avoid a room remaining
    // hacked after a ship jumps away or something similar.
    fun checkStillAttacking(system: AbstractSystem): Boolean {
        val projectile = this.projectile ?: return false

        if (!projectile.hasLanded)
            return false

        // Check this ship hasn't been destroyed
        if (!ship.sys.isShipPresent(ship)) {
            return false
        }

        // This would normally be caught by update, but check it here
        // in case we're on a ship that has jumped away.
        if (!projectile.target.ship.projectiles.contains(projectile)) {
            this.projectile = null
            return false
        }

        return projectile.target == system.room
    }

    fun browserEnemyChanged() {
        selectedTarget?.let {
            if (!ship.sys.isShipPresent(ship) || !ship.sys.isShipPresent(it.ship)) selectedTarget = null
        }
        val probe = projectile ?: return
        if (ship.sys.isShipPresent(ship) && ship.sys.isShipPresent(probe.target.ship)) return
        ship.projectiles.remove(probe)
        probe.target.ship.projectiles.remove(probe)
        selectedTarget = null
        projectile = null
        if (timeRemaining != null) endPulse()
    }

    fun removeProbe() {
        projectile = null
    }

    /** The system the landed (or flying) probe is attached to, for the AI's pulse check (0x62d710). */
    val droneTargetSystem: AbstractSystem? get() = projectile?.target?.system

    /**
     * HackingSystem::StopHacking, as CombatAI uses it when the ship stops fighting (0x625c50): the pulse ends and
     * the probe is dropped.
     */
    fun stopHacking() {
        selectedTarget = null
        val probe = projectile
        if (probe != null) {
            ship.projectiles.remove(probe)
            probe.target.ship.projectiles.remove(probe)
            projectile = null
        }
        if (timeRemaining != null) endPulse()
    }

    private fun endPulse() {
        // 0x62dc80 only locks the system for four units when the lock is
        // still -1; after an ion hit its own lock stays.
        val hadLock = hasPermanentLock
        timeRemaining = null
        pulseDuration = 0f
        if (hadLock)
            lockUnits(PULSE_LOCK_UNITS)
    }

    // HackingSystem::Jump (0x62dc00): StopHacking, then LockSystem(0).
    override fun onJump() {
        super.onJump()
        timeRemaining = null
        pulseDuration = 0f
        selectedTarget = null
        lockUnits(0)
    }

    fun startHackingPulse() {
        if (isPowerLocked || powerSelected == 0)
            return

        if (!droneLanded)
            return

        if (active)
            return

        // A pulse stopped by an ion hit carries on with the time it had
        // reached (the native timer, +0x7b0, is not reset by a new lock).
        if (timeRemaining != null) {
            permanentLockOverridden = false
            startSound.play()
            return
        }

        // Start the hacking pulse. What it does to the hacked system is
        // up to that system (ShipSystem::SetHackingLevel).
        this@Hacking.timeRemaining = duration
        pulseDuration = duration

        startSound.play()
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        if (key.id == VanillaHotkeys.SYS_ACTION_HACKING) {
            // This is a bit hacky, but it properly shows the super-shield warning
            button?.click(Input.MOUSE_LEFT_BUTTON)
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addTagFloat(elem, "timeRemaining", timeRemaining, null)

        if (selectedTarget != null) {
            SaveUtil.addRoomRef(elem, "selectedTarget", refs, selectedTarget!!)
        }

        // Don't save any reference to the projectile, because it might be
        // in another ship's projectiles list so we can't reference it directly.
        // We can rely on the projectile linking itself to us when it's deserialised.
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        timeRemaining = SaveUtil.getOptionalTagFloat(elem, "timeRemaining")

        if (elem.getChild("selectedTarget") != null) {
            SaveUtil.getRoomRef(elem, "selectedTarget", refs) { selectedTarget = it }
        }
    }

    // Copied from Cloaking.CloakButton
    private inner class HackButton(power: Int, powerPos: IPoint) : SystemPowerButton(ship.sys, power, powerPos) {

        override val timeRemaining: Float? get() = this@Hacking.timeRemaining
        override val duration: Float get() = this@Hacking.duration
        override val isOff: Boolean
            get() = powerSelected == 0 || isPowerLocked || droneInFlight ||
                    (!droneLaunched && ship.dronesCount == 0) ||
                    // HackBox's loop (0x004bb7f0) needs HackingSystem::CanHack, whose flag ShipManager::OnLoop
                    // (0x004abd40) sets only while the ship has a target.
                    (!droneLaunched && game.getEnemyOf(ship) == null)

        override val forceHighlight: Boolean
            get() {
                if (ship != game.player)
                    return false

                return game.shipUI.isSelectingHackingTarget
            }

        private val superShieldWarning = WarningFlasher(
            game, powerPos + ConstPoint(35, -62),
            "warning_super_shield_hacking",
            false, colour = GlowColour.WHITE
        )

        public override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            // Stop hacking from being activated when it's on cooldown
            if (isPowerLocked)
                return

            if (powerSupplied == 0)
                return

            if (projectile == null) {
                // If the player previously selected a target but hasn't
                // unpaused, clear it out.
                selectedTarget = null

                val enemyShip = game.getEnemyOf(ship) ?: return

                if (enemyShip.superShield > 0) {
                    superShieldWarning.startFor(3.5f)
                    return
                }

                ship.sys.shipUI.hackSelected()
                return
            }

            startHackingPulse()
        }

        // HackBox (constructor 0x004b9e70, OnRender 0x004bba20): until a drone is out (HackingSystem::GetHacking)
        // the box shows button_hack_base, 11 px below the pulse button's base (as captured), and the
        // button_hack_drone button 26 px lower instead of the pulse button.
        private val hackBase = game.getImg("img/systemUI/button_hack_base.png")
        private val droneImages = ButtonImageSet.select2(game, "img/systemUI/button_hack_drone")

        override fun draw(g: Graphics) {
            if (droneLaunched) {
                super.draw(g)
            } else {
                val imageX = powerPos.x + 23f - 6f
                val imageY = powerPos.y + 17f - 79f - 7f + 11f
                hackBase.draw(imageX, imageY)
                val image = when {
                    isOff -> droneImages.off
                    hovered || forceHighlight -> droneImages.hover
                    else -> droneImages.normal
                }
                image.draw(imageX, imageY + 26f)
            }
            superShieldWarning.draw(g)
        }
    }

    private class HackingDroneProjectile(val target: Room) : AbstractProjectile(target.ship) {
        lateinit var hacking: Hacking

        val game: InGameState = target.ship.sys

        val blueprint = game.blueprintManager["DRONE_HACKING"] as DroneBlueprint

        val offImage = game.getImg("img/ship/drones/drone_hack_base.png")
        val onImage = game.getImg("img/ship/drones/drone_hack_on.png")
        val lightOverlay = game.getImg("img/ship/drones/drone_hack_light1.png")

        val flyAnimation = game.animations["drone_hack_fly"].startLooping(game)
        val landAnimation = game.animations["drone_hack_land"].startSingle(game)
        val extendAnimation = game.animations["drone_hack_extend"].startSingle(game)

        // The direction the drone is coming from - for example, a drone
        // flying up from the bottom of the screen would be DOWN. This is picked
        // when the drone enters the target's space, see calculateTargetPosition.
        var fromDirection = Direction.CARDINALS.first()

        // The hacking drone is a SpaceDrone in the original, so it follows the
        // drone collision rules (HackingDrone::CollisionMoving 0x455bb0).
        private val health = PodHealth(game, blueprint.dodge ?: 0)

        private val isOwnerHacked: Boolean get() = hacking.ship.drones?.isHackActive == true

        var hasLanded: Boolean = false

        // Set until we switch spaces
        override var drawUnderShip: Boolean = true
        // HackingDrone::CollisionMoving (0x455bb0) only applies while the drone
        // is deployed, not hidden under its owner, not landed and not exploding.
        override val collisionsEnabled: Boolean get() = !hasLanded && !drawUnderShip && !health.isBlowingUp

        // SpaceDrone's ten pixel contact distance, less a projectile's three.
        override val hitboxRadius: Int get() = 7

        override fun onProjectileContact(other: IProjectile): ProjectileContact {
            return health.contact(other, hacking.ship, target.ship, position, hacking.powerSupplied > 0)
        }

        // We can never collide with drones, as we're pretending to be one.
        override val antiDroneBP: AbstractWeaponBlueprint? get() = null
        override val antiDroneExemption: Ship? get() = null

        override val serialisationType: String get() = PROBE_SERIALISATION_TYPE

        override val isMissileForDD: Boolean get() = !hasLanded && !health.isBlowingUp

        var lightFlashTimer: Float = 0f

        override val speed: Int
            get() {
                // Stop once we've reached our destination
                if (hasLanded)
                    return 0

                // Stop the drone when the enemy is cloaking.
                if (target.ship.isCloakActive)
                    return 0

                // A stunned drone doesn't move (0x455da0), and neither does one
                // that is exploding.
                if (health.ionTimer > 0f || isOwnerHacked || health.isBlowingUp)
                    return 0

                // And when the system power is turned off - HackingSystem::OnLoop
                // (0x62dc80) ties the drone's powered state to the system's
                // effective power, and an unpowered drone doesn't move.
                if (hacking.powerSupplied == 0)
                    return 0

                // Standard 16x multiplier for FTL time units to seconds.
                return blueprint.speed!! * 16
            }

        private val currentImage: Renderable
            get() = when {
                // The flying animation (or single frame, as it may be)
                !hasLanded -> flyAnimation

                // The animations once we land
                !landAnimation.isStopped -> landAnimation
                !extendAnimation.isStopped -> extendAnimation

                // Once we've landed, use the powered-up image if that's true
                hacking.powerSupplied == 0 -> offImage
                else -> onImage
            }

        override fun renderPreTranslated(g: Graphics) {
            // An exploding drone is drawn as just the explosion, not turned.
            g.pushTransform()
            g.rotate(0f, 0f, -Math.toDegrees(rotation.toDouble()).toFloat())
            val exploding = health.renderExplosion()
            g.popTransform()
            if (exploding)
                return

            // 'up' in the sprite is forwards, rotate it so that is the case.
            g.rotate(0f, 0f, 90f)

            // Make sure the top of the image lines up with our true position, so
            // it doesn't clip into rooms.
            currentImage.draw(-(offImage.width / 2).f, -6f)

            // Draw on the flashing antenna lights, if appropriate
            if (currentImage == onImage) {
                // Flash with a period of one second
                var alpha = lightFlashTimer * 2
                if (alpha > 1)
                    alpha = 2 - alpha

                val colour = Colour(1f, 1f, 1f, alpha)
                lightOverlay.draw(-(offImage.width / 2).f, -6f, colour)
            }
        }

        override fun update(dt: Float, currentSpace: Ship) {
            // Has something odd happened?
            if (this != hacking.projectile) {
                dead = true
            }

            // Go away if the target is dying or the sender is dead.
            if (target.ship.isDead || !target.ship.sys.isShipPresent(hacking.ship)) {
                dead = true
            }

            super.update(dt, currentSpace)

            // The drone is gone once its explosion has finished. Unlike the
            // other drones, it isn't rebuilt (HackingDrone::SetDestroyed 0x455d60).
            if (health.update(dt, isOwnerHacked)) {
                dead = true
            }

            // Update the currently-playing animation
            val image = currentImage
            if (image is FTLAnimation) {
                image.update(dt)
            }

            // Animate the flashing antenna lights
            if (image == onImage) {
                lightFlashTimer += dt
            } else {
                lightFlashTimer = 0f
            }
            if (lightFlashTimer > 1f)
                lightFlashTimer -= 1f
        }

        override fun reachedTarget() {
            // Turn into the permanent graphic, disabling collisions.
            hasLanded = true

            // Tell the system it's being hacked
            target.system?.hackedBy = hacking

            // Update the start button size
            hacking.updateButton()

            // Play the landing sound
            hacking.landSound.play()
        }

        // The drone is never removed by touching a projectile
        // (onProjectileContact never reports DESTROYED).
        override fun hitOtherProjectile(currentSpace: Ship) {
        }

        override fun onSwitchedToTarget() {
            super.onSwitchedToTarget()

            // Figure out where we'll be starting from
            val startPos = Point(
                (fromDirection.x * 1000).coerceIn(-400..800),
                (fromDirection.y * 1000).coerceIn(-400..800)
            )

            if (fromDirection.isVertical) {
                startPos.x = targetPos.x
            } else {
                startPos.y = targetPos.y
            }

            setInitialPath(startPos, targetPos)

            // Render above the target ship, otherwise you couldn't see
            // it once it lands.
            drawUnderShip = false
        }

        override fun calculateTargetPosition(): IPoint {
            // HackingDrone::PickDestination (0x455580): the four sides are
            // tried in a random order. The first side with a path that lands
            // in the target room wins, otherwise the landing point closest
            // to the target room's centre over all four sides is used.
            val sides = ArrayList(Direction.CARDINALS)

            var bestDist = Int.MAX_VALUE
            var bestDestPos: ConstPoint? = null
            var bestSide = fromDirection

            while (sides.isNotEmpty()) {
                val side = sides.removeAt(ftl.browser.BrowserRandom.nextInt(sides.size))
                val result = scanSide(side)
                val pos = result.first ?: continue

                if (result.second) {
                    fromDirection = side
                    return pos
                }

                val dist = pos.distToSq(target.pixelCentre)
                if (dist < bestDist) {
                    bestDestPos = pos
                    bestDist = dist
                    bestSide = side
                }
            }

            requireNotNull(bestDestPos) { "Couldn't find anywhere to land the drone on the target ship!" }
            fromDirection = bestSide
            return bestDestPos
        }

        /**
         * Scan across the ship from one side, for the landing point
         * closest to the target room. The second value is true if that
         * point is in the target room.
         *
         * See doc/hacking for the logic here.
         */
        private fun scanSide(fromDirection: Direction): Pair<ConstPoint?, Boolean> {
            val rooms = target.ship.rooms

            // This is the direction we'll be flying in
            val approachDirection = fromDirection.opposite

            val maxRoomX = rooms.maxOf { it.x + it.width }
            val maxRoomY = rooms.maxOf { it.y + it.height }
            val bounds = ConstPoint(maxRoomX, maxRoomY)

            // Keep track of the best (closest to the target room)
            // position we've found.
            var bestDist = Int.MAX_VALUE
            var bestDestPos: ConstPoint? = null

            val destPixelPos = Point(0, 0)

            // This must be in bounds of maxRoomX/maxRoomY for
            // findLandingPoint to work. Note this is in room
            // coordinates, not pixel coordinates.
            val startPos = Point(0, 0)

            // Note that when we're moving in a vertical direction we scan
            // horizontally across the ship to find our landing position,
            // and vice versa.

            val iterBound: Int
            if (fromDirection.isVertical) {
                // We're coming in vertically, scan along the X axis
                startPos.y = if (fromDirection.y > 0) maxRoomY else 0
                iterBound = maxRoomX
            } else {
                startPos.x = if (fromDirection.x > 0) maxRoomX else 0
                iterBound = maxRoomY
            }

            for (i in 0..iterBound) {
                if (fromDirection.isVertical) {
                    startPos.x = i
                } else {
                    startPos.y = i
                }

                val (landingPos, room) = findLandingPoint(startPos, approachDirection, bounds) ?: continue

                // Find the top-left corner of the cell of the room we hit
                destPixelPos.x = ROOM_SIZE * (landingPos.x + target.ship.offset.x)
                destPixelPos.y = ROOM_SIZE * (landingPos.y + target.ship.offset.y)

                // Adjust to the centre of the position we hit it from - if
                // we were moving upwards, we'd hit it from below.
                if (fromDirection.isVertical) {
                    destPixelPos.x += ROOM_SIZE / 2
                    destPixelPos.y += ROOM_SIZE * max(0, fromDirection.y)
                } else {
                    destPixelPos.x += ROOM_SIZE * max(0, fromDirection.x)
                    destPixelPos.y += ROOM_SIZE / 2
                }

                // Immediately stop if we find the room directly
                if (room == target) {
                    return Pair(ConstPoint(destPixelPos), true)
                }

                // Note that we use the first available position if there's a tie.
                val dist = destPixelPos.distToSq(target.pixelCentre)
                if (dist < bestDist) {
                    // Duplicate the position as we're changing it
                    bestDestPos = ConstPoint(destPixelPos)
                    bestDist = dist
                }
            }

            return Pair(bestDestPos, false)
        }

        // HackingDrone::SetMovementTarget (0x452110): in its own ship's space the drone flies to x = 800 (the
        // player's) or y = -400 (an enemy's) and changes space when it gets there.
        override fun isOutOfBounds(currentSpace: Ship): Boolean {
            if (currentSpace == target.ship)
                return super.isOutOfBounds(currentSpace)
            return if (hacking.ship.isPlayerShip) position.xf >= 800f else position.yf <= -400f
        }

        override fun crossedShieldLine() {
            // 0x455da0: crossing into a super shield blows the drone up.
            if (target.ship.superShield > 0) {
                health.blowUp()
            }
        }

        private fun findLandingPoint(from: IPoint, movementDirection: Direction, bounds: IPoint): Pair<IPoint, Room>? {
            val rooms = target.ship.rooms

            val iter = Point(from)

            // Assume rooms can't have negative positions
            while (iter.x in 0..bounds.x && iter.y in 0..bounds.y) {
                val room = rooms.firstOrNull { it.containsAbsolute(iter) }

                if (room != null) {
                    return Pair(iter, room)
                }

                iter += movementDirection
            }

            return null
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addRoomRef(elem, "target", refs, target)

            SaveUtil.addAttrBool(elem, "hasLanded", hasLanded)
            SaveUtil.addAttrFloat(elem, "flashTimer", lightFlashTimer)
            SaveUtil.addTagBoolIfTrue(elem, "drawUnderShip", drawUnderShip)
            SaveUtil.addAttr(elem, "fromDir", fromDirection.name)

            if (hasLanded) {
                SaveUtil.addTagFloat(elem, "landAnim", landAnimation.timer, landAnimation.duration)
                SaveUtil.addTagFloat(elem, "extendAnim", extendAnimation.timer, extendAnimation.duration)
            } else {
                // This animation is just a single frame in vanilla, but it's
                // probably worth saving this in case a mod changes it.
                SaveUtil.addTagFloat(elem, "flyAnim", flyAnimation.timer)
            }

            // Save the ship that owns us, and we can easily grab it's hacking
            // system from there.
            SaveUtil.addAttrRef(elem, "owner", refs, hacking.ship)

            health.saveToXML(elem)
        }

        override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
            super.loadPropertiesFromXML(elem, refs)

            hasLanded = SaveUtil.getAttrBool(elem, "hasLanded")
            lightFlashTimer = SaveUtil.getAttrFloat(elem, "flashTimer")
            drawUnderShip = SaveUtil.getOptionalTagBool(elem, "drawUnderShip") ?: false
            fromDirection = Direction.valueOf(SaveUtil.getAttr(elem, "fromDir"))
            health.loadFromXML(elem)

            if (hasLanded) {
                landAnimation.timer = SaveUtil.getOptionalTagFloat(elem, "landAnim") ?: landAnimation.duration
                extendAnimation.timer = SaveUtil.getOptionalTagFloat(elem, "extendAnim") ?: extendAnimation.duration
            } else {
                // This animation is just a single frame in vanilla, but it's
                // probably worth saving this in case a mod changes it.
                flyAnimation.timer = SaveUtil.getTagFloat(elem, "flyAnim")
            }

            // It's safe to use immediate resolution, since we're only being called
            // after the target room has been resolved.
            val ownerShip = SaveUtil.getAttrRefImmediate(elem, "owner", refs, Ship::class.java)
            hacking = ownerShip!!.hacking!!

            // Make ourselves known to the hacking system
            hacking.projectile = this

            // Systems don't store who they're being hacked by, so we have to set it up again.
            if (hasLanded) {
                target.system!!.hackedBy = hacking
            }
        }
    }

    companion object {
        val INFO: SystemInfo = HackingInfo

        const val PROBE_SERIALISATION_TYPE = "hackingProbe"

        /** The four-unit lock HackingSystem sets when a pulse ends. */
        const val PULSE_LOCK_UNITS = 4

        fun loadProjectileFromXML(game: InGameState, elem: Element, refs: RefLoader, callback: (IProjectile) -> Unit) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                val probe = HackingDroneProjectile(target)
                probe.loadPropertiesFromXML(elem, refs)
                callback(probe)
            }
        }
    }
}

private object HackingInfo : SystemInfo("hacking") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Hacking(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val time = 4 + level * 3
        return translator["hacking_duration"].replaceArg(time)
    }
}
