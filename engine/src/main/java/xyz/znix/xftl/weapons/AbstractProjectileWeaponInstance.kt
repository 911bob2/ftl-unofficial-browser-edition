package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Animations
import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil

abstract class AbstractProjectileWeaponInstance(type: AbstractWeaponBlueprint, ship: Ship) :
    AbstractWeaponInstance(type, ship), IRoomTargetingWeapon {

    // The list of all the rooms to fire at. This is required since artillery
    // weapons and all enemy-fired weapons aim at different rooms for each shot.
    protected val targets = ArrayList<Room>()

    // This isn't derived from the targets list, since after we fire our last
    // shot we have to keep running the firing animation until that ends.
    override var isFiring: Boolean = false

    // The number of shots we've fired on this firing cycle (from when the
    // user or AI selected the weapon to be fired until it stops shooting).
    // This is for charge weapon animations, NOT for chain weapons.
    private var shotsFired: Int = 0

    // This is the value of chainCount before it was incremented as
    // weapon started firing.
    private var firingChainCount: Int = 0

    private var firingAnimationTimer: Float = 0f
    protected var waitingToFireAt: Room? = null

    // To support weapons that fire multiple shots per charge (assuming
    // a mod adds one), we have to divide out the number of shots to get
    // the index of the charge we're currently on.
    private val chargeIndex: Int get() = shotsFired / type.shots

    private val fireAnimationFrame: Int
        get() {
            require(isFiring)

            // firingAnimationTimer resets for each shot, so we have to add
            // an extra offset to get the time in the sequence for charger weapons.
            val time = firingAnimationTimer + chargeIndex * Animations.PROJECTILE_WEAPON_FIRE_TIME

            return animation.fireIndex(time, totalAnimationTime)
        }

    private val totalAnimationTime: Float get() = Animations.PROJECTILE_WEAPON_FIRE_TIME * maxTotalCharges

    protected var entryAngle: Float = 0f

    // Where our shots leave the ship. None of this is serialised, since it's
    // worked out again each time we launch a shot: weapons can be dragged to
    // another slot, and the mount a shot leaves from is that of the slot the
    // weapon is in at that moment (ProjectileFactory::Update 0x40dc80 asks the
    // weapon's current mount for the fire location on every shot).
    // Artillery weapons aren't in a hardpoint, they're given the position.
    private var projectileSpawnPos: IPoint? = null
    private var projectileSpawnChargeOffset: IPoint = ConstPoint.ZERO
    private var artilleryOrigin: IPoint? = null

    override fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
        super.update(dt, chargeTime, canCharge)

        // We shouldn't need to check targets.isEmpty here, but do
        // so anyway just in case to avoid getting in a situation where
        // we haven't fired all of our shots.
        if (!isFiring && targets.isEmpty()) {
            return
        }

        // If we're powered off, instantly stop firing.
        if (!isPowered) {
            stopFiring()
            return
        }

        firingAnimationTimer += dt

        // Surprisingly, weapons charge while firing.

        // Calculate the current frame, without taking into account multiple charges.
        // Otherwise all but the first shot would fire instantly.
        val singleChargeFrame = animation.fireIndex(firingAnimationTimer, totalAnimationTime)

        if (waitingToFireAt != null && singleChargeFrame >= animation.fireFrame) {
            if (shotsFired == 0) browserRecordVolley(waitingToFireAt!!.ship)
            fireFrameHit()
            waitingToFireAt = null
        }

        if (firingAnimationTimer >= Animations.PROJECTILE_WEAPON_FIRE_TIME) {
            if (targets.isEmpty()) {
                stopFiring()
            } else {
                shotsFired++
                primeShot()
            }
        }
    }

    override fun render(g: Graphics) {
        if (isFiring) {
            val frame = animation.spriteAt(ship.sys, fireAnimationFrame)
            frame.draw(0f, 0f)

            // Keep rendering the chain lights even when firing
            renderChainChargeLights()
        } else {
            super.render(g)
        }
    }

    /**
     * The ship-space position a shot of the given charge leaves from, as set by
     * the hardpoint the weapon is in right now.
     */
    fun spawnPosition(charge: Int): IPoint {
        updateSpawnFromHardpoint()
        return projectileSpawnPos!! + projectileSpawnChargeOffset * charge
    }

    private fun updateSpawnFromHardpoint() {
        // Artillery keeps the position it was fired from.
        if (artilleryOrigin != null)
            return

        val weapons = ship.weapons ?: return
        if (ship.hardpoints.none { it.weapon === this })
            return

        val firePos = animation.firePoint
        val spawnPos = weapons.getProjectileSpawnPos(this, firePos)
        projectileSpawnPos = spawnPos

        // Find the change in position for each subsequent shot in a charge weapon.
        val offsetFirePos = firePos + ConstPoint(-animation.chargeOffset, 0)
        val offsetSpawn = weapons.getProjectileSpawnPos(this, offsetFirePos)
        projectileSpawnChargeOffset = offsetSpawn - spawnPos
    }

    protected open fun fireFrameHit() {
        val projectile = buildProjectile(waitingToFireAt!!)
        projectile.entryAngle = entryAngle
        launchProjectile(projectile)

        // For the chain ion, mark the projectile as doing more damage
        if (type.boost?.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
            projectile.chainDamage = (type.boost.perShot * firingChainCount).toInt()
        }

        type.launchSounds?.get()?.play()
    }

    /**
     * How far past the fire point a projectile starts, along the direction
     * the ship fires in. ProjectileFactory::Update (0x40dc80) moves missiles
     * (blueprint type MISSILES) 16 pixels that way, and nothing else.
     */
    protected open val launchAdvance: Int get() = 0

    protected fun launchProjectile(projectile: AbstractProjectile) {
        // Charge weapons shift a bit on each launch.
        val spawnPos = spawnPosition(chargeIndex) + ship.weaponFireDirection * launchAdvance

        // Depending on whether we're the player or enemy ship, we need
        // to fly in different directions as they're angled differently.
        val endPos = spawnPos + ship.weaponFireDirection * 5000

        projectile.setInitialPath(spawnPos, endPos)

        ship.projectiles += projectile
    }

    override fun fire(targetSource: () -> Room) {
        check(!isFiring) { "Cannot file while already firing!" }

        // This weapon is firing from its own hardpoint.
        artilleryOrigin = null

        for (i in 0 until totalReadyCharges * type.shots) {
            targets.add(targetSource())
        }

        // Store the current chain count, in case we need it for the ion charger.
        // (we need the pre-firing value, as it determines the projectile damage)
        firingChainCount = chainCount

        entryAngle = (ftl.browser.BrowserRandom.nextDouble() * Math.PI * 2).toFloat()
        fire()
        primeShot()
    }

    override fun fireFromDrone(drone: CombatDrone, target: Room) {
        // If this is a multi-shot weapon, only fire a single shot.
        // We don't have any way of doing more than that.

        val projectile = buildProjectile(target)
        target.ship.projectiles += projectile

        // Draw the projectile on top of the ship. By default it's
        // set to draw under the ship, as it expects to be launched
        // from one ship area to another, at which point it switches this.
        projectile.drawUnderShip = false

        // Prevent defence drones from firing on this shot.
        projectile.firedByDrone = true

        projectile.setInitialPath(drone.flightController.position, projectile.calculateTargetPosition())

        type.launchSounds?.get()?.play()
    }

    open fun fireFromArtillery(possibleTargets: List<Room>, origin: IPoint) {
        artilleryOrigin = origin
        projectileSpawnPos = origin
        projectileSpawnChargeOffset = ConstPoint.ZERO

        val remaining = ArrayList(possibleTargets)

        // Pick as many shots as required, while avoiding targeting the
        // same room with more than one projectile.
        // (Note that flak overrides this function since it doesn't
        //  have this requirement)
        for (i in 0 until type.shots) {
            val target = remaining.random(ftl.browser.BrowserRandom)
            remaining.remove(target)
            targets += target
        }

        entryAngle = (ftl.browser.BrowserRandom.nextDouble() * Math.PI * 2).toFloat()

        // This applies boost for artillery weapons
        // Eg multiverse's playable flagship.
        fire()

        primeShot()
    }

    override fun onJump() {
        super.onJump()

        // Whatever we were firing at was left behind at the previous beacon
        // (GitHub issue #55).
        stopFiring()
        waitingToFireAt = null
    }

    protected fun stopFiring() {
        isFiring = false
        targets.clear()
        // A depowered/cancelled burst cannot retain a primed shot at a ship
        // which may later be destroyed. Do not revive it after power/restore.
        waitingToFireAt = null

        // Save space in the savefile, as these aren't written if they're zero.
        entryAngle = 0f
        firingAnimationTimer = 0f
        shotsFired = 0
        firingChainCount = 0
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        if (waitingToFireAt != null) {
            val fireAtElem = Element("waitingToFireAt")
            SaveUtil.addAttr(fireAtElem, "ship", refs[waitingToFireAt!!.ship])
            SaveUtil.addAttrInt(fireAtElem, "roomId", waitingToFireAt!!.id)
            elem.addContent(fireAtElem)
        }

        SaveUtil.addTagFloat(elem, "entryAngle", entryAngle, 0f)
        SaveUtil.addTagBoolIfTrue(elem, "isFiring", isFiring)
        SaveUtil.addTagFloat(elem, "fireAnimationTimer", firingAnimationTimer, 0f)
        SaveUtil.addTagInt(elem, "shotsFired", shotsFired, 0)
        SaveUtil.addTagInt(elem, "firingChainCount", firingChainCount, 0)

        for (target in targets) {
            val targetElem = Element("target")
            SaveUtil.addAttr(targetElem, "ship", refs[target.ship])
            SaveUtil.addAttrInt(targetElem, "roomId", target.id)
            elem.addContent(targetElem)
        }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        super.loadFromXML(elem, refs)

        entryAngle = SaveUtil.getOptionalTagFloat(elem, "entryAngle") ?: 0f
        firingAnimationTimer = SaveUtil.getOptionalTagFloat(elem, "fireAnimationTimer") ?: 0f
        isFiring = SaveUtil.getOptionalTagBool(elem, "isFiring") ?: false
        shotsFired = SaveUtil.getOptionalTagInt(elem, "shotsFired") ?: 0
        firingChainCount = SaveUtil.getOptionalTagInt(elem, "firingChainCount") ?: 0

        val waitingToFireAtElem = elem.getChild("waitingToFireAt")
        if (waitingToFireAtElem != null) {
            val roomId = SaveUtil.getAttrInt(waitingToFireAtElem, "roomId")
            val shipRef = SaveUtil.getAttr(waitingToFireAtElem, "ship")
            refs.asyncResolve(Ship::class.java, shipRef) { waitingToFireAt = it!!.rooms[roomId] }
        }

        for (targetElem in elem.getChildren("target")) {
            val roomId = SaveUtil.getAttrInt(targetElem, "roomId")
            val shipRef = SaveUtil.getAttr(targetElem, "ship")
            refs.asyncResolve(Ship::class.java, shipRef) { targets += it!!.rooms[roomId] }
        }
    }

    private fun primeShot() {
        isFiring = true
        waitingToFireAt = targets.removeAt(targets.lastIndex)
        firingAnimationTimer = 0f

        // Damage the cloak when the last shot is fired
        if (targets.isEmpty()) {
            damageCloak()
        }
    }

    protected abstract fun buildProjectile(target: Room): AbstractWeaponProjectile
}
