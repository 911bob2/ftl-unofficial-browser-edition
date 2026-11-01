package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.cos
import kotlin.math.sin

class BombBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    override val explosion: String = super.explosion ?: "explosion_random"

    override fun buildInstance(ship: Ship): AbstractWeaponInstance {
        return BombInstance(ship)
    }

    /**
     * The factory's bomb: a native BombProjectile created off-screen in the space opposite its target (HS
     * CreateBomb: space 1 - targetSpace), owned by [owner]; the body itself decides a miss or a super shield
     * contact when it reaches the target.
     */
    fun makeBomb(owner: Ship, target: Room, superShieldBypass: Boolean, fireLocation: xyz.znix.xftl.math.IPoint? = null): BombProjectile {
        val targetId = xyz.znix.xftl.hs.weapons.HsWeapons.shipId(target.ship)
        // A weapon's bomb is built at its fire location in the owner's space (BombProjectile(fireLocation,
        // currentShipId, targetSpace, target); A's capture: (402, 97)); without one (a save), off-screen opposite.
        val start = fireLocation?.let { ConstFPoint(it.x.toFloat(), it.y.toFloat()) } ?: BombProjectile.OFF_SCREEN
        val context = if (fireLocation != null) xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(start,
            target.pixelCentre, owner, xyz.znix.xftl.hs.weapons.HsWeapons.shipId(owner),
            xyz.znix.xftl.hs.weapons.HsWeapons.shipId(owner), targetId)
        else xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(start,
            target.pixelCentre, target.ship.sys.getEnemyOf(target.ship) ?: owner,
            xyz.znix.xftl.hs.weapons.HsWeapons.shipId(owner), 1 - targetId, targetId)
        val bomb = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
            BombProjectile(this@BombBlueprint, target, superShieldBypass)
        }
        bomb.hsOwnerId = xyz.znix.xftl.hs.weapons.HsWeapons.shipId(owner)
        if (!xyz.znix.xftl.hs.HS.active) bomb.setInitialPath(start, target.pixelCentre)
        return bomb
    }

    inner class BombInstance(ship: Ship) : AbstractWeaponInstance(this, ship), IRoomTargetingWeapon, HsProjectileQueue {
        /** Hyperspace: the bomb is constructed when the weapon fires and leaves at the fire frame (queuedProjectiles). */
        override val hsQueue = ArrayList<AbstractProjectile>()
        override var hsFiredOnce: Boolean = false
        private var hsFireShot = false
        private var hsStartPending = false
        /** ProjectileFactory::currentEntryAngle (degrees, random32() % 360 at Fire); a bomb teleports, so only Lua reads it. */
        internal var hsEntryAngle = 0f

        private var firingAnimationTimer: Float = 0f
        private var target: Room? = null
        private var hasFired = false
        /** Where an artillery's bombs start, and the rooms left in its volley without Hyperspace (its queue holds them with it). */
        private var artilleryOrigin: xyz.znix.xftl.math.IPoint? = null
        private val artilleryRooms = ArrayList<Room>()

        override val isFiring: Boolean get() = target != null
        override val canTargetOwnShip: Boolean get() = true

        // Hyperspace <fireTime> (WeaponAnimation::SetFireTime, every non-beam weapon).
        internal var bombFireTime: Float = type.hs?.fireTime?.takeIf { it != 0f } ?: Animations.BOMB_FIRE_TIME

        private val fireAnimationFrame: Int
            get() {
                require(target != null)
                return animation.fireIndex(firingAnimationTimer, bombFireTime)
            }

        override fun render(g: Graphics) {
            if (target != null) {
                val frame = animation.spriteAt(ship.sys, fireAnimationFrame)
                frame.draw()
            } else {
                super.render(g)
            }
        }

        override fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
            super.update(dt, chargeTime, canCharge)

            val target = this.target ?: return

            if (!isPowered) {
                // Instantly stop the firing animation if we're depowered
                // The player has already been charged a missile part, so that's
                // lost, which is fine as it matches vanilla.
                stopFiring()
                return
            }

            // Hyperspace: the fire frame set bFireShot last frame and ShipManager drains the factory now; the
            // animation started this frame does not advance yet (StartFire after WeaponAnimation::Update).
            if (hsFireShot) hsDrain(target)
            if (hsStartPending) hsStartPending = false
            else firingAnimationTimer += dt

            if (!hasFired && fireAnimationFrame >= animation.fireFrame) {
                if (xyz.znix.xftl.hs.HS.active) {
                    browserRecordVolley(target.ship)
                    hsFireShot = true
                } else doBombFire(target)
                hasFired = true
            }

            if (firingAnimationTimer >= bombFireTime) {
                if (hsFireShot) hsDrain(target)
                // An artillery volley: the next bomb's animation starts straight away.
                if (hsQueue.isNotEmpty() || artilleryRooms.isNotEmpty()) {
                    if (artilleryRooms.isNotEmpty()) this.target = artilleryRooms.removeAt(0)
                    firingAnimationTimer -= bombFireTime
                    hasFired = false
                } else stopFiring()
            }
        }

        private fun hsDrain(target: Room) {
            while (true) {
                val bomb = hsGetProjectile() ?: break
                if (bomb !in target.ship.projectiles) target.ship.projectiles += bomb
            }
        }

        private fun stopFiring() {
            hsStartPending = false
            target = null
            hasFired = false
            firingAnimationTimer = 0f
            // ProjectileFactory::Update unpowered deletes the queued projectiles (a jump leaves them behind too).
            hsQueue.clear()
            hsFireShot = false
            artilleryRooms.clear()
            if (HsProjectileQueue.simultaneousFireWeapon === this) HsProjectileQueue.simultaneousFireWeapon = null
        }

        /**
         * ProjectileFactory::GetProjectile for a bomb (native 0x40d120: FireShot() pops the back without a launch
         * sound, FiredWeapon on the cloak when the queue holds numShots, bFiredOnce; HS: simultaneousFire keeps
         * popping, then PROJECTILE_FIRE).
         */
        override fun hsGetProjectile(): AbstractProjectile? {
            val fireShot = hsFireShot
            hsFireShot = false
            var ret: AbstractProjectile? = null
            if (fireShot && hsQueue.isNotEmpty()) {
                if (hsNumShots == hsQueue.size) damageCloak()
                ret = hsQueue.removeAt(hsQueue.lastIndex)
                hsFiredOnce = true
            }
            if (hsQueue.isNotEmpty()) {
                if (fireShot && type.hs?.simultaneousFire == true) HsProjectileQueue.simultaneousFireWeapon = this
                if (HsProjectileQueue.simultaneousFireWeapon === this && !fireShot && ret == null)
                    ret = hsQueue.removeAt(hsQueue.lastIndex)
            } else if (HsProjectileQueue.simultaneousFireWeapon === this) HsProjectileQueue.simultaneousFireWeapon = null
            if (ret != null && xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.weapons.HsWeaponsLua.projectileFire(ret, this)
            return ret
        }

        /** The weapon's fire location on its hardpoint (WeaponMount + WeaponAnimation fire point), if mounted. */
        private fun bombFireLocation(): xyz.znix.xftl.math.IPoint? {
            artilleryOrigin?.let { return it }
            val weapons = ship.weapons ?: return null
            if (ship.hardpoints.none { it.weapon === this }) return null
            return weapons.getProjectileSpawnPos(this, animation.firePoint)
        }

        private fun doBombFire(target: Room): AbstractProjectile {
            browserRecordVolley(target.ship)
            // Zoltan Shield Bypass teleports bombs through a super shield (BombProjectile::superShieldBypass).
            val bypass = ship.hasAugment(xyz.znix.xftl.augments.AugmentBlueprint.ZOLTAN_BYPASS)
            val bomb = makeBomb(ship, target, bypass, bombFireLocation())
            target.ship.projectiles += bomb
            // No PROJECTILE_FIRE here: Hyperspace raises it in ProjectileFactory::GetProjectile only (the weapon's
            // own shots, hsGetProjectile above); a drone's bomb and a surge barrage's bombs are made directly.
            return bomb
        }

        override fun fire(targetSource: () -> Room) {
            fire()
            val target = targetSource()
            this.target = target

            if (xyz.znix.xftl.hs.HS.active) {
                // ProjectileFactory::Update (HS rewrite): the bomb is constructed now and plays its launch sound;
                // GetProjectile drains the cloak at the fire frame.
                hsQueue.clear()
                hsEntryAngle = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360).toFloat()
                hsQueue += makeBomb(ship, target, ship.hasAugment(xyz.znix.xftl.augments.AugmentBlueprint.ZOLTAN_BYPASS),
                    bombFireLocation())
                type.launchSounds?.get()?.play()
                hsStartPending = true
                hsSpendMissiles()
                return
            }

            type.launchSounds?.get()?.play()

            // Firing from the weapons system drains our cloak, like any
            // other weapon (GitHub issue #54). Drones' bombs don't - only
            // weapons actually sitting on our hardpoints do.
            damageCloak()
        }

        override fun onJump() {
            super.onJump()

            // A bomb mid-firing-animation when the ship jumps would otherwise
            // resume and hit us (or the left-behind ship) after arriving.
            stopFiring()
        }

        /** Hyperspace super barrage (CustomBoss.cpp): a bomb at [target] at once, no charge or missile used. */
        fun fireBarrage(target: Room): AbstractProjectile = doBombFire(target)

        /**
         * An artillery bomb volley: all its bombs are built when the artillery fires, each aimed at its own room while
         * rooms last, and leave one per firing animation.
         */
        fun fireFromArtillery(possibleTargets: List<Room>, origin: xyz.znix.xftl.math.IPoint) {
            val remaining = ArrayList(possibleTargets)
            val rooms = List(maxOf(1, hsNumShots)) {
                if (remaining.isEmpty()) remaining += possibleTargets
                remaining.removeAt(ftl.browser.BrowserRandom.nextInt(remaining.size))
            }
            fire()
            target = rooms[0]
            type.launchSounds?.get()?.play()
            artilleryOrigin = origin
            if (xyz.znix.xftl.hs.HS.active) {
                val bypass = ship.hasAugment(xyz.znix.xftl.augments.AugmentBlueprint.ZOLTAN_BYPASS)
                hsQueue.clear()
                hsEntryAngle = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360).toFloat()
                for (room in rooms.asReversed()) hsQueue += makeBomb(ship, room, bypass, origin)
                hsStartPending = true
                hsSpendMissiles()
            } else {
                artilleryRooms.clear()
                artilleryRooms += rooms.drop(1)
            }
        }

        override fun fireFromDrone(drone: CombatDrone, target: Room) {
            // Should we be subtracting a bomb when used on a drone?
            fire()

            doBombFire(target)
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addTagFloat(elem, "firingAnimationTimer", firingAnimationTimer, 0f)
            SaveUtil.addTagBoolIfTrue(elem, "hasFired", hasFired)
            if (target != null) {
                SaveUtil.addRoomRef(elem, "target", refs, target!!)
            }
            for (projectile in hsQueue) {
                val queuedElem = Element("hsQueued")
                SaveUtil.addAttr(queuedElem, "loadType", projectile.serialisationType)
                projectile.saveToXML(queuedElem, refs)
                elem.addContent(queuedElem)
            }
            SaveUtil.addTagBoolIfTrue(elem, "hsFiredOnce", hsFiredOnce)
        }

        override fun loadFromXML(elem: Element, refs: RefLoader) {
            super.loadFromXML(elem, refs)

            firingAnimationTimer = SaveUtil.getOptionalTagFloat(elem, "firingAnimationTimer") ?: 0f
            hasFired = SaveUtil.getOptionalTagBool(elem, "hasFired") ?: false

            val oldHsSave = xyz.znix.xftl.hs.HS.active && !hasFired && elem.getChildren("hsQueued").isEmpty()
            if (elem.getChild("target") != null) {
                SaveUtil.getRoomRef(elem, "target", refs) {
                    target = it
                    // A save from before the factory queue: the bomb is constructed again.
                    if (oldHsSave && it != null) hsQueue += makeBomb(ship, it, ship.hasAugment(xyz.znix.xftl.augments.AugmentBlueprint.ZOLTAN_BYPASS))
                }
            }
            hsFiredOnce = SaveUtil.getOptionalTagBool(elem, "hsFiredOnce") ?: false
            hsQueue.clear()
            for (queuedElem in elem.getChildren("hsQueued"))
                IProjectile.loadFromXML(ship.sys, queuedElem, refs, SaveUtil.getAttr(queuedElem, "loadType")) { hsQueue += it as AbstractProjectile }
        }
    }

    companion object {
        /** The bomb of saves written before the native bomb body (a teleported, already placed bomb). */
        const val SERIALISATION_TYPE = "firedBomb"

        /** Restores such a bomb as an arrived native body: its saved spot, miss and super shield outcome. A bomb
         * whose explosion was already playing is not restored (it had dealt its damage). */
        fun loadProjectileFromXML(game: InGameState, elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            val type = game.blueprintManager[SaveUtil.getAttr(elem, "type")] as BombBlueprint
            val targetShip = SaveUtil.getAttr(elem, "targetShip")
            val targetRoomId = SaveUtil.getAttrInt(elem, "targetRoomId")
            if (SaveUtil.getOptionalTagFloat(elem, "explosionTimer") != null) return
            refs.asyncResolve(Ship::class.java, targetShip) {
                val room = it!!.rooms[targetRoomId]
                val owner = room.ship.sys.getEnemyOf(room.ship) ?: room.ship
                val bomb = type.makeBomb(owner, room, false)
                bomb.nativeRestoreArrived(SaveUtil.getPoint(elem, "position"),
                    SaveUtil.getOptionalTagBool(elem, "missed") ?: false,
                    SaveUtil.getOptionalTagBool(elem, "hitSuperShield") ?: false,
                    SaveUtil.getAttrFloat(elem, "animationTimer"))
                callback(bomb)
            }
        }
    }
}
