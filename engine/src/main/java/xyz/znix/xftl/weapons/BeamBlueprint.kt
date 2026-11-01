package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.ShipDamage
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.BulkColourRenderer
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.SelectedTarget
import kotlin.math.*
import kotlin.random.Random
import kotlin.random.nextInt

class BeamBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    val length: Int = xml.nativeInt("length") ?: 0

    // See the note on SPEED_MULTIPLIER.
    val speedPixelsPerSecond = (speed ?: DEFAULT_SPEED) * SPEED_MULTIPLIER

    val fireDuration: Float = 1f * length / speedPixelsPerSecond

    override fun buildInstance(ship: Ship): AbstractWeaponInstance {
        return BeamInstance(ship)
    }

    /**
     * The weapon: native ProjectileFactory plus its WeaponAnimation for a beam. Firing builds the real
     * [BeamProjectile] (ProjectileFactory::Update: constructed, Initialize, SetWeaponAnimation, queued), and the
     * pre-fire frames (charged -> fire frame, 2 s / frame count each) release it into the world
     * (GetProjectile). The beam then moves, collides, damages and draws itself, and drives the launcher's frame
     * through WeaponAnimation::SetFireLevel; once it is gone the animation runs its remaining frames and the
     * weapon stops firing. Drone beams are released at once, without a weapon animation.
     */
    inner class BeamInstance(ship: Ship) : AbstractWeaponInstance(this, ship), BeamProjectile.WeaponAnimationLink {
        override val isFiring: Boolean get() = target != null && isPowered

        /** True while the released beam is in the world. */
        val isBeamOn: Boolean get() = isFiring && liveBody() != null

        // The pre-fire time, then (once the beam is gone) the time into the remaining fire frames.
        private var attackTimer: Float = 0f
        private var browserVolleyCounted = false
        private var target: SelectedTarget.BeamAim? = null

        /** The queued (not yet released) or released beam. */
        private var body: BeamProjectile? = null
        private var released = false
        /** ProjectileFactory::lastProjectileId of a released beam whose object is resolved after a load. */
        private var pendingLaunchId: Int? = null
        /** The progress of a swipe saved before the beam body existed, rebuilt once the save has loaded. */
        private var legacyResume: Float? = null
        /** The launcher frame last set by the beam (SetFireLevel), -1 before. */
        private var fireFrameIndex = -1
        private var tailFrom = -1

        // The duration and number of frames in the pre-firing animation
        private val preFireFrames: Int
        private val preFireDuration: Float
        private val frameDuration: Float

        // This is the chain count at the moment the weapon started firing (damage-chaining beams, which mods add).
        private var firingChainCount: Int = 0

        var isOnDrone: Boolean = false

        /**
         * A beam fired by [fireFromDrone]: SpaceDrone::GetNextProjectile (0x429ee0) sets the drone's additionalPause
         * to 0.5 s and moves its own beamCurrentTarget towards beamFinalTarget at the shot's speed_magnitude
         * (CombatDrone loop 0x4285b0), independently of the BeamWeapon, which has no link back to the drone. The
         * drone holds for exactly that pause; the beam lives on its own (a beam aimed at its owner's ship never
         * passes BeamWeapon::CollisionCheck's ownerId guard, so it never moves or dies).
         */
        private var droneFired = false
        private var droneHold = 0f
        private val droneBeamCurrent = MutFPoint(0f, 0f)
        private val droneBeamFinal = MutFPoint(0f, 0f)
        private var droneBeamSpeed = 0f

        init {
            // Beams set a per-frame time of 2/frameCount for frames between
            // the charged and fire frames.
            frameDuration = 2f / animation.length
            preFireFrames = animation.fireFrame - animation.chargedFrame
            preFireDuration = preFireFrames * frameDuration
        }

        /** WeaponAnimation::SetFireLevel: frame = fireFrame + (int)((frames - 1 - fireFrame) * level). */
        override fun setFireLevel(level: Float) {
            fireFrameIndex = ((animation.length - 1 - animation.fireFrame).toFloat() * level + animation.fireFrame).toInt()
        }

        override val weaponPowered: Boolean get() = isPowered

        /** ProjectileFactory::lastProjectileId: the launch id of the beam this weapon fired last, -1 before. */
        val lastProjectileId: Int get() = body?.launchId ?: pendingLaunchId ?: -1

        /** The beam this weapon has in the world (released and alive), if any. */
        val liveBeam: BeamProjectile? get() = liveBody()

        private fun liveBody(): BeamProjectile? {
            reconnect()
            val b = body ?: return null
            if (!released || b.motion.dead(b.hsDead)) return null
            val game = ship.sys
            return b.takeIf { it in game.player.projectiles || it in (game.enemy?.projectiles ?: emptyList()) }
        }

        /** ProjectileFactory::ReconnectProjectile: relink the saved beam with lastProjectileId. */
        private fun reconnect() {
            legacyResume?.let { progress ->
                legacyResume = null
                resumeLegacy(progress)
            }
            val id = pendingLaunchId ?: return
            val found = BeamProjectile.find(ship.sys, id) ?: return
            pendingLaunchId = null
            body = found
            released = true
            if (!isOnDrone) found.weaponAnimation = this
        }

        override fun render(g: Graphics) {
            if (released) {
                // The beam draws itself; the launcher shows the frame it set, then the remaining fire frames.
                val frame = if (tailFrom < 0) fireFrameIndex.coerceAtLeast(animation.fireFrame)
                    else tailFrom + (attackTimer / frameDuration).toInt()
                animation.spriteAt(ship.sys, frame.coerceIn(0 until animation.length)).draw()
            } else if (isFiring) {
                // Render the pre-beam-on animation
                // This is used by mods, for example Multiverse's LANCE_1 beam.
                val progress = if (preFireDuration <= 0f) 1f else (attackTimer / preFireDuration).coerceIn(0f, 1f)
                val frameNum = animation.chargedFrame + (preFireFrames * progress).toInt()
                animation.spriteAt(ship.sys, frameNum).draw()
            } else {
                super.render(g)
            }
        }

        /** A drone's beam is a one-space body that draws itself. */
        fun drawDroneBeam(g: Graphics, drone: CombatDrone) = Unit

        override fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
            super.update(dt, chargeTime, canCharge)
            reconnect()
            // A saved beam that is not in the world by the first update after loading has already ended.
            pendingLaunchId = null
            val aim = target ?: return
            if (droneFired) {
                // The drone's additionalPause and beamCurrentTarget, whatever the beam itself does.
                droneHold -= dt
                val dx = droneBeamFinal.xf - droneBeamCurrent.xf; val dy = droneBeamFinal.yf - droneBeamCurrent.yf
                val remaining = sqrt(dx * dx + dy * dy)
                val step = droneBeamSpeed * (dt * 16f)
                if (remaining <= step || remaining == 0f) droneBeamCurrent.set(droneBeamFinal)
                else droneBeamCurrent.set(droneBeamCurrent.xf + dx / remaining * step, droneBeamCurrent.yf + dy / remaining * step)
                if (droneHold < 0f || !isPowered) stopFiring()
                return
            }
            if (!isPowered) {
                // ProjectileFactory::Update drops queued projectiles of an unpowered weapon; a released beam kills
                // itself on its next update (WeaponAnimation::bPowered).
                stopFiring()
                return
            }
            if (!released) {
                attackTimer += dt
                if (attackTimer >= preFireDuration) release(aim)
                return
            }
            if (liveBody() != null) return
            // The beam is gone: the animation plays its remaining frames from the one the beam set last.
            if (tailFrom < 0) {
                tailFrom = fireFrameIndex.coerceAtLeast(animation.fireFrame)
                attackTimer = 0f
            } else attackTimer += dt
            if (isOnDrone || tailFrom + (attackTimer / frameDuration).toInt() >= animation.length - 1) stopFiring()
        }

        /** ProjectileFactory::GetProjectile: the queued beam enters the world (PROJECTILE_FIRE). */
        private fun release(aim: SelectedTarget.BeamAim) {
            val b = body ?: buildBody(aim).also { body = it }
            released = true
            attackTimer = preFireDuration
            if (!browserVolleyCounted) {
                browserRecordVolley(aim.targetShip)
                browserVolleyCounted = true
            }
            ProjectileRuntime.added(b, b.hsCurrentSpace ?: ship)
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.weapons.HsWeaponsLua.projectileFire(b, this)
        }

        /** The launcher's fire location in ship space (the weapon mount), else the ship's centre (artillery/boss). */
        private fun origin(): ConstFPoint {
            val weapons = ship.weapons
            if (weapons != null && ship.hardpoints.any { it.weapon === this }) {
                val p = weapons.getProjectileSpawnPos(this, animation.firePoint)
                return ConstFPoint(p.x.toFloat(), p.y.toFloat())
            }
            val minX = ship.rooms.minOf { it.offsetX }; val minY = ship.rooms.minOf { it.offsetY }
            val maxX = ship.rooms.maxOf { it.offsetX + it.pixelWidth }; val maxY = ship.rooms.maxOf { it.offsetY + it.pixelHeight }
            return ConstFPoint((minX + maxX) / 2f, (minY + maxY) / 2f)
        }

        /**
         * ProjectileFactory::Update's BEAM case: BeamWeapon(fire location, iShipId, targetId, target1, target2,
         * blueprint length, currentShipTarget, currentFiringAngle), then Initialize, heading = currentFiringAngle,
         * the damage boost and SetWeaponAnimation. [position] and [space] are a drone's (one-space, no animation).
         */
        private fun buildBody(aim: SelectedTarget.BeamAim, position: ConstFPoint = origin(), space: Ship = ship,
                              target2: IPoint? = null): BeamProjectile {
            val targetShip = aim.targetShip
            val start = ConstFPoint(aim.startShipPoint.x.toFloat(), aim.startShipPoint.y.toFloat())
            val end = target2?.let { ConstFPoint(it.x.toFloat(), it.y.toFloat()) }
                ?: ConstFPoint(start.xf + aim.length * cos(aim.angle), start.yf + aim.length * sin(aim.angle))
            val heading = if (ship.weaponFireDirection == Direction.RIGHT) 0f else 270f
            val ownerId = xyz.znix.xftl.hs.weapons.HsWeapons.shipId(ship)
            val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(position, start, space, ownerId,
                xyz.znix.xftl.hs.weapons.HsWeapons.shipId(space), xyz.znix.xftl.hs.weapons.HsWeapons.shipId(targetShip))
            val b = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                BeamProjectile(ship.sys, targetShip, end, length, heading)
            }
            b.hsOwnerId = ownerId
            b.initialize(this@BeamBlueprint)
            if (!b.motion.oneSpace) b.setBeamHeading(heading)
            if (type.boost?.type == BoostType.DAMAGE) b.damage.applyWeaponChaining((type.boost!!.perShot * firingChainCount).toInt())
            b.flightAnimation = projectile
            if (!isOnDrone) b.weaponAnimation = this
            return b
        }

        private fun stopFiring() {
            // A drone's beam is independent of the drone (no weapon animation): it is left to end by itself.
            if (!droneFired) liveBody()?.hsKill()
            droneFired = false
            attackTimer = 0f
            target = null
            body = null
            released = false
            pendingLaunchId = null
            legacyResume = null
            fireFrameIndex = -1
            tailFrom = -1
            firingChainCount = 0
        }

        fun fire(target: SelectedTarget.BeamAim) {
            browserVolleyCounted = false
            this.target = target

            type.launchSounds?.get()?.play()

            firingChainCount = chainCount
            fire()

            // Firing from the weapons system drains our cloak, like any
            // other weapon (GitHub issue #54). The hardpoint check inside
            // damageCloak excludes beams fired by combat drones.
            damageCloak()

            // Constructed and initialised now (CONSTRUCT_PROJECTILE, PROJECTILE_INITIALIZE), released at the fire frame.
            body = buildBody(target)
            if (preFireDuration <= 0f) release(target)
        }

        /**
         * SpaceDrone::GetNextProjectile's BEAM case: a one-space beam from the drone's (integer) location in its
         * space, from target1 to target2 at the blueprint's speed, without a weapon animation. [duration] is the
         * drone's additionalPause (0.5 s): the weapon stops firing after it, the beam's own length and speed end it.
         */
        /**
         * [target2]: SpaceDrone::GetNextProjectile (0x429ee0, beam case) gives the BeamWeapon constructor (0x4383b0)
         * target1 = the drone's target point and target2 = a second random targeting point itself (not a point
         * along the swipe); null keeps the aim's own end point.
         */
        fun fireFromDrone(drone: CombatDrone, target: SelectedTarget.BeamAim, duration: Float,
                          target2: IPoint? = null) {
            browserVolleyCounted = false
            this.target = target
            val p = drone.flightController.position
            val b = buildBody(target, ConstFPoint(p.xf.toInt().toFloat(), p.yf.toInt().toFloat()), target.targetShip, target2)
            body = b
            droneFired = true
            droneHold = duration
            droneBeamCurrent.set(b.motion.target1)
            droneBeamFinal.set(b.motion.target2)
            droneBeamSpeed = b.speedMagnitude
            release(target)
        }

        override fun onJump() {
            super.onJump()

            // The ship we were firing at was left behind at the previous
            // beacon (GitHub issue #55).
            stopFiring()
        }

        // For use by drones, so they can angle themselves correctly.
        fun getCurrentTargetPoint(): IPoint {
            if (!isFiring) return ConstPoint.ZERO
            if (droneFired) return ConstPoint(droneBeamCurrent.xf.toInt(), droneBeamCurrent.yf.toInt())
            val b = liveBody() ?: return ConstPoint.ZERO
            return ConstPoint(b.motion.tip.xf.toInt(), b.motion.tip.yf.toInt())
        }

        /**
         * Generate a beam swipe, as used by the drones and enemy ship AI.
         *
         * This starts from the specified room, and moving towards
         * the furthest-away room in that ship.
         */
        fun buildLongestAim(startRoom: Room): SelectedTarget.BeamAim {
            val furthestRoom = startRoom.ship.rooms.maxBy { it.pixelCentre.distToSq(startRoom.pixelCentre) }
            return buildAim(startRoom, furthestRoom, true)
        }

        fun buildAim(startRoom: Room, endRoom: Room, stopAtEnd: Boolean): SelectedTarget.BeamAim {
            val aim = SelectedTarget.BeamAim(this, -1, startRoom.ship, startRoom.pixelCentre)
            aim.angle = atan2(
                endRoom.pixelCentre.y.f - startRoom.pixelCentre.y,
                endRoom.pixelCentre.x.f - startRoom.pixelCentre.x
            )

            if (stopAtEnd) {
                // Don't swipe off the side of the ship, into empty space.
                aim.length = min(startRoom.pixelCentre.distTo(endRoom.pixelCentre), length)
            }

            aim.updateHitRooms()

            return aim
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addTagFloat(elem, "attackTimer", attackTimer, 0f)
            SaveUtil.addTagBoolIfTrue(elem, "browserVolleyCounted", browserVolleyCounted)
            SaveUtil.addTagInt(elem, "firingChainCount", firingChainCount, 0)

            if (target != null) {
                val targetElem = Element("target")
                target!!.saveToXML(targetElem, refs)
                elem.addContent(targetElem)
            }
            // The released beam is saved with the ship's projectiles; the launcher keeps its id.
            SaveUtil.addTagBool(elem, "beamReleased", released)
            val launched = if (released) liveBody() else null
            if (launched != null) SaveUtil.addTagInt(elem, "beamLaunchId", launched.launchId, null)
            SaveUtil.addTagInt(elem, "beamFireFrame", fireFrameIndex, -1)
            SaveUtil.addTagInt(elem, "beamTailFrom", tailFrom, -1)
        }

        override fun loadFromXML(elem: Element, refs: RefLoader) {
            super.loadFromXML(elem, refs)

            attackTimer = SaveUtil.getOptionalTagFloat(elem, "attackTimer") ?: 0f
            browserVolleyCounted = SaveUtil.getOptionalTagBool(elem, "browserVolleyCounted") ?: false
            firingChainCount = SaveUtil.getOptionalTagInt(elem, "firingChainCount") ?: 0
            val releasedTag = SaveUtil.getOptionalTagBool(elem, "beamReleased")
            released = releasedTag ?: false
            pendingLaunchId = SaveUtil.getOptionalTagInt(elem, "beamLaunchId")
            fireFrameIndex = SaveUtil.getOptionalTagInt(elem, "beamFireFrame") ?: -1
            tailFrom = SaveUtil.getOptionalTagInt(elem, "beamTailFrom") ?: -1
            val legacyFireDuration = SaveUtil.getOptionalTagFloat(elem, "fireDuration")

            val targetElem = elem.getChild("target")
            if (targetElem != null) {
                SelectedTarget.loadFromXML(targetElem, refs, { this }) { loaded ->
                    val aim = loaded as SelectedTarget.BeamAim
                    target = aim
                    // A save from before the beam body: rebuild the swipe in flight at its saved progress.
                    if (releasedTag == null && attackTimer >= preFireDuration) {
                        val duration = legacyFireDuration ?: (this@BeamBlueprint.length / ((speed ?: DEFAULT_SPEED) * SPEED_MULTIPLIER))
                        legacyResume = if (duration <= 0f) 0f else ((attackTimer - preFireDuration) / duration).coerceIn(0f, 1f)
                    }
                }
            }
        }

        private fun resumeLegacy(progress: Float) {
            val aim = target ?: return
            val b = buildBody(aim)
            body = b
            val distance = aim.length * progress
            val start = b.motion.target1
            val at = ConstFPoint(start.xf + distance * cos(aim.angle), start.yf + distance * sin(aim.angle))
            b.motion.tip.set(at); b.motion.finalEnd.set(at); b.motion.lastCollision.point.set(at)
            browserVolleyCounted = true
            release(aim)
        }
    }

    internal fun drawBeam(g: Graphics, game: InGameState, power: Int, src: FPoint, dst: FPoint, dstAngle: Float,
                          colour: Colour) {
        // The beam is drawn as a transparent-red-transparent gradient line
        // Find the tangent line of src-dst, and use that to find the edges of the beam
        val tangent = MutFPoint(-(src.yf - dst.yf), (src.xf - dst.xf))
        val length = sqrt(tangent.xf * tangent.xf + tangent.yf * tangent.yf)
        tangent.xf /= length
        tangent.yf /= length

        // Scale up the tangent vector based on the per-side width
        val width = 3 * power
        tangent.xf *= width
        tangent.yf *= width

        val srcA = ConstFPoint(src.xf + tangent.xf, src.yf + tangent.yf)
        val srcB = ConstFPoint(src.xf - tangent.xf, src.yf - tangent.yf)

        // The destination point has an angle, which determines the angle the
        // end of the beam is 'cut' at - this is so that it aligns cleanly with
        // the shield. Without this, when firing at a Zoltan ship (for example)
        // there's obviously a difference in angle between the end of the beam
        // and the shield, which looks rather ugly.
        // Thus dstAngle gives us the angle between the beam's tangent and
        // the shield's tangent, which we can then use to build a second tangent
        // line along the shield.
        val beamAngle = atan2(dst.yf - src.yf, dst.xf - src.xf)
        val angleDifference = dstAngle - beamAngle
        val dstWidth = width / cos(angleDifference)
        val dstTangentAngle = beamAngle - PIf / 2 + angleDifference
        val dstTangentX = cos(dstTangentAngle) * dstWidth
        val dstTangentY = sin(dstTangentAngle) * dstWidth

        val dstA = ConstFPoint(dst.xf + dstTangentX, dst.yf + dstTangentY)
        val dstB = ConstFPoint(dst.xf - dstTangentX, dst.yf - dstTangentY)

        g.drawCustomQuads { quads ->
            drawGradient(quads, srcA, srcB, dstA, dstB, colour)
        }

        if (game.debugFlags.showBeamVectors.set) {
            // This lets us check if our normal calculation is correct
            drawDebugVectorAngle(g, ConstPoint(dst.xf.toInt(), dst.yf.toInt()), dstAngle, 20f, Colour.yellow)
        }
    }

    /**
     * Draw a quad that's opaque along a centre line, and fades to the edge.
     */
    private fun drawGradient(
        quads: BulkColourRenderer,
        srcA: FPoint, srcB: FPoint,
        dstA: FPoint, dstB: FPoint,
        colour: Colour
    ) {
        // Find the middle points where the colour should be strongest
        val srcMidX = (srcA.xf + srcB.xf) / 2
        val srcMidY = (srcA.yf + srcB.yf) / 2

        val dstMidX = (dstA.xf + dstB.xf) / 2
        val dstMidY = (dstA.yf + dstB.yf) / 2

        // Fade to transparent, but without blending to another colour in the process.
        val edge = Colour(colour)
        edge.a = 0f

        // Draw the A-side - srcA,srcMid,dstA,dstMid
        quads.pushVert(srcA.xf, srcA.yf, edge)
        quads.pushVert(dstA.xf, dstA.yf, edge)
        quads.pushVert(dstMidX, dstMidY, colour)
        quads.pushVert(srcMidX, srcMidY, colour)

        // Draw the B-side - srcB,srcMid,dstB,dstMid
        quads.pushVert(dstMidX, dstMidY, colour)
        quads.pushVert(srcMidX, srcMidY, colour)
        quads.pushVert(srcB.xf, srcB.yf, edge)
        quads.pushVert(dstB.xf, dstB.yf, edge)
    }

    private fun drawDebugVectorAngle(g: Graphics, origin: IPoint, angle: Float, length: Float, colour: Colour) {
        drawDebugVector(g, origin, cos(angle) * length, sin(angle) * length, colour)
    }

    private fun drawDebugVector(g: Graphics, origin: IPoint, x: Float, y: Float, colour: Colour) {
        g.colour = colour
        g.drawLine(
            origin.x.f, origin.y.f,
            origin.x + x, origin.y + y
        )
    }

    companion object {
        // BeamWeapon::OnUpdate (0x438880): the beam tip moves by the blueprint
        // speed times the native speed factor of 16 per second.
        private const val SPEED_MULTIPLIER = 16f

        // The default speed, in the same units as the XML.
        private const val DEFAULT_SPEED = 5


    }
}
