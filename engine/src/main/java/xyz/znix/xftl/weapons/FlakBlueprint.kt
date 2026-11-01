package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Implementation for flak-style weapons, including the swarm missiles.
class FlakBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    override val explosion: String = super.explosion ?: "explosion_missile1"
    val radius: Int get() = blueprintRadius

    private val projectileSpecs: List<ProjectileSpec>
    val projectileCount: Int

    init {
        projectileSpecs = ArrayList()
        xml.getChild("projectiles")?.getChildren("projectile")?.forEach { proj ->
            projectileSpecs += ProjectileSpec(
                projectileSpecs.size,
                NativeNumbers.atoi(proj.getAttributeValue("count") ?: ""),
                proj.textTrim,
                proj.getAttributeValue("fake") == "true"
            )
        }

        projectileCount = projectileSpecs.filter { !it.fake }.sumOf { it.count }
    }

    /** WeaponBlueprint::miniProjectiles: (image, fake) once per `count`, in the XML order. */
    val miniProjectiles: List<Pair<String, Boolean>> by lazy {
        projectileSpecs.flatMap { spec -> List(spec.count) { spec.animation to spec.fake } }
    }

    override fun buildInstance(ship: Ship): AbstractWeaponInstance = FlakInstance(ship)

    override fun loadProjectileFromXML(
        game: InGameState,
        elem: Element, refs: RefLoader,
        callback: ProjectileLoadCallback
    ) {
        val specId = SaveUtil.getAttrInt(elem, "specId")

        SaveUtil.getRoomRef(elem, "target", refs) { target ->
            val projectile = FlakProjectile(target, projectileSpecs[specId])
            projectile.loadPropertiesFromXML(elem, refs)
            callback(projectile)
        }
    }

    inner class FlakInstance(ship: Ship) : AbstractProjectileWeaponInstance(this, ship) {
        override fun buildProjectile(target: Room) = error("Building a single flak projectile isn't supported")

        override val hsMiniCount: Int get() = miniSpecs.size

        /** WeaponBlueprint::miniProjectiles as specs: one per `count`, in the XML order. */
        private val miniSpecs: List<ProjectileSpec> by lazy { projectileSpecs.flatMap { spec -> List(spec.count) { spec } } }

        /**
         * ProjectileFactory::Update (HS rewrite) for BURST: first every target gets one point per mini projectile,
         * target + (sqrt(random32() / 2^31) * radius, random32() % 360 degrees); then the idx-th point becomes a
         * LaserBlast with mini projectile idx % count's image, stopped at a random32() % frames frame
         * (Animation::SetCurrentFrame 0x5bb520), the fake ones with zeroed damage.
         */
        override fun hsConstructVolley(aims: List<Room>) {
            hsQueue.clear()
            val minis = miniSpecs
            if (minis.isEmpty()) return
            val points = ArrayList<Pair<Room, FPoint>>()
            for (room in aims) for (i in minis.indices) {
                val r = sqrt(xyz.znix.xftl.hs.weapons.HsWeapons.r32() / 2147483648f) * radius
                val theta = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360) * 0.01745329f
                points += room to ConstFPoint(r * cos(theta), r * sin(theta))
            }
            for ((idx, point) in points.withIndex()) {
                val spec = minis[idx % minis.size]
                val projectile = hsConstruct(point.first, idx) { FlakProjectile(point.first, spec, point.second) }
                stopAtRandomFrame(projectile)
                hsQueue += projectile
            }
        }

        /**
         * Hyperspace ShipManager::PrepareSuperBarrage (CustomBoss.cpp, a BURST entry of the boss barrage): every mini
         * projectile from [origin] on [heading] (degrees) with one [entryAngle], to target + (sqrt(random32() / 2^31)
         * * radius, random32() % 360 degrees) around [room]'s centre; the fake pieces with zeroed damage.
         */
        fun hsBarrageVolley(room: Room, origin: IPoint, heading: Float, entryAngle: Float) {
            val rad = Math.toRadians(heading.toDouble())
            val end = ConstPoint(origin.x + (5000 * cos(rad)).toInt(), origin.y + (5000 * sin(rad)).toInt())
            for (spec in miniSpecs) {
                val r = sqrt(xyz.znix.xftl.hs.weapons.HsWeapons.r32() / 2147483648f) * radius
                val theta = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360) * 0.01745329f
                val projectile = FlakProjectile(room, spec, ConstFPoint(r * cos(theta), r * sin(theta)))
                projectile.entryAngle = entryAngle
                projectile.setInitialPath(origin, end)
                ship.projectiles += projectile
            }
        }

        /**
         * Hyperspace SpaceDrone::GetNextProjectile (CustomDrones.cpp): a combat drone with a flak weapon sends every
         * mini projectile from its own position to a point spread around the target, using the blueprint radius
         * (or `<angularRadius>`, in degrees at the target's distance). Vanilla FTL has no flak drones; outside
         * Hyperspace this stays unsupported.
         */
        override fun fireFromDrone(drone: xyz.znix.xftl.drones.CombatDrone, target: Room) {
            if (!xyz.znix.xftl.hs.HS.active) return super.fireFromDrone(drone, target)
            val from = drone.flightController.position
            val centre = target.pixelCentre
            val angular = hs?.angularRadius ?: -1f
            val r0 = if (angular == -1f) radius.toFloat()
                else angular * sqrt(((centre.x - from.x).toFloat()).let { it * it } + ((centre.y - from.y).toFloat()).let { it * it }) * 0.01745329f
            for (spec in projectileSpecs) {
                for (i in 0 until spec.count) {
                    val r = sqrt(xyz.znix.xftl.hs.weapons.HsWeapons.r32() / 2147483648f) * r0
                    val theta = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360) * 0.01745329f
                    val projectile = FlakProjectile(target, spec, ConstFPoint(r * cos(theta), r * sin(theta)))
                    target.ship.projectiles += projectile
                    projectile.drawUnderShip = false
                    projectile.firedByDrone = true
                    projectile.setInitialPath(from, projectile.calculateTargetPosition())
                }
            }
            type.launchSounds?.get()?.play()
        }

        /**
         * The piece's flight animation: Animation::SetCurrentFrame(random32() % frames) (0x5bb520), then
         * AnimationTracker::Stop(false).
         */
        private fun stopAtRandomFrame(projectile: FlakProjectile) {
            val flight = projectile.flightTracker
            if (flight.frameCount > 0) {
                var frame = minOf(ftl.browser.BrowserRandom.nextBits(31) % flight.frameCount, flight.frameCount - 1)
                if (frame < 1) frame = 0
                flight.currentFrame = frame
                flight.currentTime = (frame.toFloat() / flight.frameCount * flight.time).coerceIn(0f, flight.time)
            }
            flight.running = false
        }

        /**
         * ProjectileFactory::Update (1.6.14 0x40dc80) BURST branch: first one point per mini projectile,
         * target + (cos, sin)(random32() % 360 degrees) * sqrt(random32() / 2^31) * radius (the angle drawn first);
         * then every mini projectile becomes a LaserBlast to its point, stopped at a random32() % frames frame.
         */
        override fun fireFrameHit() {
            val target = waitingToFireAt!!
            val minis = miniSpecs
            val offsets = minis.map {
                val theta = (ftl.browser.BrowserRandom.nextBits(31) % 360) * 0.017453292f
                val r = sqrt(ftl.browser.BrowserRandom.nextBits(31) / 2147483648f) * radius
                ConstFPoint(r * cos(theta), r * sin(theta))
            }
            for ((i, spec) in minis.withIndex()) {
                val projectile = FlakProjectile(target, spec, offsets[i])
                stopAtRandomFrame(projectile)

                // For the swarm missile this function will be called multiple
                // times, so we can't pick our own angle in this function.
                projectile.entryAngle = entryAngle

                launchProjectile(projectile)
            }

            type.launchSounds?.get()?.play()
        }

        /**
         * Diagnostics (BrowserWp4FlakDiagnostics, the native 1.6.14 flak capture): one volley of the factory at
         * [room]; per piece its target offset from the room centre, its stopped frame and its frame count.
         */
        internal fun diagnosticVolley(room: Room): List<FloatArray> {
            val before = ship.projectiles.size
            waitingToFireAt = room
            fireFrameHit()
            waitingToFireAt = null
            val centre = room.pixelCentre
            return ship.projectiles.drop(before).filterIsInstance<FlakProjectile>().map {
                val target = it.calculateTargetPosition() as FPoint
                floatArrayOf(target.xf - centre.x, target.yf - centre.y, it.flightTracker.currentFrame.toFloat(),
                    it.flightTracker.frameCount.toFloat(), if (it.spec.fake) 1f else 0f)
            }
        }

        override fun fireFromArtillery(possibleTargets: List<Room>, origin: IPoint) {
            browserRecordVolley(possibleTargets.first().ship)
            // Unlike the other projectile-based weapons, flak doesn't require
            // the target rooms are unique (it allows two projectiles to target
            // the same room).

            // Depending on whether we're the player or enemy ship, we need
            // to fly in different directions as they're angled differently.
            val endPos = origin + ship.weaponFireDirection * 5000

            // Make sure all the projectiles come in from the same angle
            val angle: Float = (ftl.browser.BrowserRandom.nextDouble() * Math.PI * 2).toFloat()

            for (spec in projectileSpecs) {
                for (i in 0 until spec.count) {
                    val projectile = FlakProjectile(possibleTargets.random(ftl.browser.BrowserRandom), spec)
                    projectile.entryAngle = angle
                    projectile.setInitialPath(origin, endPos)
                    ship.projectiles += projectile
                    if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.weapons.HsWeaponsLua.projectileFire(projectile, this)
                }
            }

            type.launchSounds?.get()?.play()
        }
    }

    /**
     * A burst piece is a native LaserBlast whose flight animation is the piece's own image (HS 1b05a07
     * SpaceManager::CreateBurstProjectile mirrors the factory: LaserBlast, Initialize(bp), flight image; a fake
     * piece has zeroed damage and a quarter-scale death animation).
     */
    private inner class FlakProjectile(room: Room, val spec: ProjectileSpec, presetOffset: FPoint? = null) :
        LaserBlastProjectile(this@FlakBlueprint, room, spec.animation) {

        /** The piece's offset from the room centre: a Pointf in both factories (the target is centre + offset). */
        private var destinationOffset: FPoint

        // A LaserBlast (Targetable type 4 from OnInit): only a defence drone that also targets lasers shoots it.

        override val collisionsEnabled: Boolean get() = !spec.fake

        override val deathAnimationScale: Float get() = if (spec.fake) 0.25f else 1f
        override val hsAppliesAreaDamage: Boolean get() = !spec.fake

        init { nativeBurstFlight() }

        init {
            if (presetOffset != null) destinationOffset = presetOffset else {
            // Pick where in the circle this bit of flack will land
            // Put the random radius value in a square root so the
            // points don't clump up around the centre.
            val angle = 2 * Math.PI * ftl.browser.BrowserRandom.nextFloat()
            val randomRadius = radius * sqrt(ftl.browser.BrowserRandom.nextFloat())
            destinationOffset = ConstFPoint(
                (randomRadius * cos(angle)).toInt().toFloat(),
                (randomRadius * sin(angle)).toInt().toFloat()
            )
            }
        }

        /**
         * Hyperspace CustomWeaponManager::ProcessMiniProjectile: a mini projectile listed in the weapon's
         * `<projectiles><projectile blueprint="X">` is re-initialised from weapon X (its damage, custom damage and
         * extend name; the flight image stays), with X's damage chain boost; a fake one has no custom damage.
         */
        private val hsOverride: AbstractWeaponBlueprint? = hs?.miniProjectileOverride?.get(spec.animation)
            ?.let { room.ship.sys.blueprintManager.getOrNull(it) as? AbstractWeaponBlueprint }

        init {
            if (hsOverride != null && !spec.fake) hsExt?.initialize(hsOverride)
            else if (spec.fake) hsExt?.let { it.def = xyz.znix.xftl.hs.weapons.HsDamageDef.DEFAULT; it.accuracyMod = 0; it.droneAccuracyMod = 0 }
            if (spec.fake) hsExt?.damage?.copyFrom(fakeDamage())
        }

        /** The burst factory's fake piece: every damage field zero, ownerId/selfId -1, friendly fire on. */
        private fun fakeDamage(): Damage = Damage.regular(0).also {
            it.hsOwnerId = -1; it.hsSelfId = -1; it.hsFriendlyFire = true; it.friendlyFire = true
        }

        override fun computeDamage(): Damage {
            if (spec.fake) return fakeDamage().also { it.ownerShip = null }
            val bp = hsOverride ?: return super.computeDamage()
            val damage = Damage(bp)
            damage.applyWeaponChaining(chainDamage)
            damage.ownerShip = ship.sys.getEnemyOf(ship)
            return damage
        }

        override fun hitShields() {
            if (!spec.fake) {
                if (xyz.znix.xftl.hs.HS.active)
                    xyz.znix.xftl.hs.weapons.HsDamage.with(hsExt, this) { ship.attackShields(computeDamage(), position, superShieldIonRoll) }
                else ship.attackShields(computeDamage(), position, superShieldIonRoll)

                // Surely there would be way too much sound if all the fake
                // bits of flak hit.
                type.hitShieldSounds?.get()?.play()
            }
        }

        override fun hitHull() {
            // Check what room (if any) we've actually hit.
            // Fake projectiles are there for the visuals only, so exclude
            // them too.
            val targetPos = calculateTargetPosition()
            val hitRoom = ship.rooms.firstOrNull { it.containsShipSpace(targetPos) }
            if (!spec.fake && hitRoom != null) {
                if (xyz.znix.xftl.hs.HS.active) hsDamageArea(ship, hitRoom, targetPos, false)
                else ship.damage(hitRoom, computeDamage())
            }
            // The impact is the body's own death animation (at its position), started by the shared impact code.
            if (!spec.fake) type.hitShipSounds?.get()?.play()
        }

        override fun calculateTargetPosition(): IPoint {
            val centre = super.calculateTargetPosition()
            return ConstFPoint(centre.x + destinationOffset.xf, centre.y + destinationOffset.yf)
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)
            SaveUtil.addRoomRef(elem, "target", refs, target)
            SaveUtil.addAttrInt(elem, "specId", spec.specId)
            flightTrackerOrNull?.let { SaveUtil.addAttrFloat(elem, "spinAnimation", it.currentTime) }

            // We only need to save this because it is (or will be) shown in the UI.
            // We only use it here when we switch to the target ship space, so the
            // UI is the only reason you could notice it changing.
            elem.addContent(Element("destOffset").setAttribute("x", destinationOffset.xf.toString())
                .setAttribute("y", destinationOffset.yf.toString()))
        }

        override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
            super.loadPropertiesFromXML(elem, refs)
            val offset = elem.getChild("destOffset") ?: error("Missing point 'destOffset'")
            destinationOffset = ConstFPoint(offset.getAttributeValue("x").toFloat(), offset.getAttributeValue("y").toFloat())
            elem.getAttributeValue("spinAnimation")?.toFloatOrNull()?.let { flightTrackerOrNull?.currentTime = it }
        }
    }

    // Corresponds to a <projectile> tag in the XML
    private class ProjectileSpec(val specId: Int, val count: Int, val animation: String, val fake: Boolean)
}
