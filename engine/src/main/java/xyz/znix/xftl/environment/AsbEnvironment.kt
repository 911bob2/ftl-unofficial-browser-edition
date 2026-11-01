package xyz.znix.xftl.environment

import ftl.browser.BrowserRandom
import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.*
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ASB cycles continue through cloaking; projectiles bypass all shield types.
 *
 * SpaceManager::SetPlanetaryDefense (0x418ca0) runs a 20-25 s timer, and
 * SpaceManager::OnLoop's PDS branch (0x41d5e0) launches a volley every
 * 2-5 s. A volley is only live if the timer has expired (which consumes it)
 * and the ship fails its dodge roll, otherwise the shot flies past. The
 * shots of a volley leave half a second apart, the player's first.
 */
class AsbEnvironment(game: InGameState, beacon: Beacon, private var targetMode: Int = if (beacon.isOvertaken) 0 else beacon.event.browserPdsTarget) : AbstractEnvironment(game, beacon) {
    override val type get() = Beacon.EnvironmentType.PDS

    // Enemy-only ASBs shut themselves off once the enemy is gone, and are
    // not counted as a dangerous environment (SpaceManager::DangerousEnvironment).
    private var active = true
    override val isDangerous: Boolean get() = active && targetMode != 1
    override val showsFleetFlagship: Boolean get() = active
    override val hsPdsOn: Boolean get() = active

    private var fireAt = (20000 + pdsTimerDraw) / 1000f
    private var warningAt = fireAt - WARNING_LEAD
    private var time = 0f
    private var warned = false

    // The next volley, and the shots of the current volley still to leave.
    // SetPlanetaryDefense's last draw (0x418ca0): random32() % 200 on the game's stream.
    private var volleyTimer = 2f + ftl.browser.NativeRandom.next31() % 200 / 100f
    private val queue = ArrayList<AsbProjectile>()

    /**
     * UpdatePDS (0x0041d5e0) starts effects/fire_smoke.png (7 frames of 34 px over 1 s, fScale 0.5) at
     * Point(origin - 8.5) - truncated to whole pixels - in the player's space for every shot it launches, hit or
     * miss: the origin is the flagship's centre with a fleet (fleet_big at (550, 447), sized 2w - 100 by h + 25 by
     * OnInit 0x0041ac40), else the planet's. OnRenderProjectiles (0x0041a8b0) draws them for space 0, layer 2, before
     * the projectiles, untinted ([renderSmoke]).
     */
    private val smokes = ArrayList<Float>()
    private fun shotOrigin(): ConstPoint? {
        if (hasFleet) {
            val big = game.getImg("img/ship/fleet/fleet_big.png")
            return ConstPoint(550 + (2 * big.width - 100) / 2, 447 + (big.height + 25) / 2)
        }
        return planetCentre
    }

    /** The smoke's top left: Point((int)(origin.x - 8.5), (int)(origin.y - 8.5)) (0x41de2a). */
    internal fun smokeCorner(origin: IPoint): ConstPoint =
        ConstPoint((origin.x - 8.5f).toInt(), (origin.y - 8.5f).toInt())

    /** OnRenderProjectiles (0x41a8b0) for space 0, layer 2: the smokes, before the player's projectiles. */
    fun renderSmoke(g: Graphics) {
        if (smokes.isEmpty()) return
        val corner = smokeCorner(playerSpaceOrigin())
        val sheet = game.getImg("img/effects/fire_smoke.png")
        val size = sheet.width / SMOKE_FRAMES
        val x = corner.x.toFloat(); val y = corner.y.toFloat()
        for (age in smokes) {
            val frame = minOf(SMOKE_FRAMES - 1, (age / SMOKE_TIME * SMOKE_FRAMES).toInt())
            sheet.draw(x, y, x + size * SMOKE_SCALE, y + sheet.height * SMOKE_SCALE,
                (frame * size).toFloat(), 0f, ((frame + 1) * size).toFloat(), sheet.height.toFloat())
        }
    }

    /**
     * Native 1.6.14 replay (BrowserWp4BodiesDiagnostics, the bodies capture's UpdatePDSFire call): the timer at
     * [elapsed] of [goal], the volley due, then one frame of [dt].
     */
    internal fun diagnosticFire(elapsed: Float, goal: Float, dt: Float) {
        time = elapsed; fireAt = goal; warningAt = goal - WARNING_LEAD; warned = true
        volleyTimer = -1f; queue.clear(); smokes.clear()
        update(dt)
    }

    /** The volley timer (SpaceManager +0x2a8), for the replay. */
    internal val diagnosticVolleyTimer: Float get() = volleyTimer

    override fun update(dt: Float) {
        if (smokes.isNotEmpty()) {
            for (i in smokes.indices) smokes[i] += dt
            smokes.removeAll { it >= SMOKE_TIME }
        }
        if (!active)
            return

        // Enemy-only mode ends once the enemy is gone or has finished exploding.
        if (targetMode == 1) {
            val enemy = game.enemy
            if (enemy == null || enemy.isGone) {
                active = false
                return
            }
        }

        time += dt
        if (!warned && time >= warningAt) { warned = true; game.sounds.getSample("environWarning").play() }

        volleyTimer -= dt
        if (volleyTimer >= 0f)
            return

        if (queue.isEmpty()) {
            // TimerHelper::Done: true once per expiry, then it restarts.
            val live = time >= fireAt
            if (live) {
                time = 0f
                fireAt = nextFireTime()
                warningAt = fireAt - WARNING_LEAD
                warned = false
            }

            val origin = playerSpaceOrigin()
            fun addShot(ship: Ship) {
                // UpdatePDS (0x41de42..0x41e0aa) builds every shot when the volley starts: one that isn't live,
                // or that the ship dodges (ShipManager vtable +0x2c), flies past to Projectile::RandomSidePoint(0)
                // in the player's space with fleetFireLight; one that hits aims at a room of its ship with
                // fleetFireHeavy. A live dodged shot carries the "miss" text (+0x2bc).
                val hit = live && !ship.isGone && !ship.pickMissed(0, false)
                // GetRandomRoomCenter (0x49dce0): random32() % rooms.
                queue += if (hit) AsbProjectile.hit(ship.rooms[BrowserRandom.nextBits(31) % ship.rooms.size], game.player, origin)
                else AsbProjectile.miss(game.player, origin, live)
                game.sounds.getSampleOrWarn(if (hit) "fleetFireHeavy" else "fleetFireLight")?.play()
            }
            // The volley pops its shots from the back, and the player's leaves first natively (c5-ASB-0: the
            // player's shot at 23.82 s, the enemy's at 24.30 s), so the enemy's is queued first.
            if (targetMode != 0) game.enemy?.let { addShot(it) }
            if (targetMode != 1) addShot(game.player)
            if (queue.isEmpty())
                return
        }

        // Every shot of a volley, hit or miss, shakes the screen (0x0041dd77).
        game.shakeForAsbShot()
        smokes += 0f
        game.player.projectiles.add(queue.removeAt(queue.lastIndex))
        volleyTimer = if (queue.isEmpty()) 2f + BrowserRandom.nextBits(31) % 300 / 100f else 0.5f
    }

    // TimerHelper::Start (0x5cb0b0): a whole number of milliseconds in 20-25 s.
    // TimerHelper::Done (0x5cb200) when it loops: (random32() % (max - min) + min) / 1000.
    private fun nextFireTime(): Float = (20000 + BrowserRandom.nextBits(31) % 5000) / 1000f

    /**
     * Every shot starts at the planet or flagship centre, in the player ship's space: UpdatePDS takes the image
     * centre minus SpaceManager's player ship offset (+0x2a0, +0x2a4), about (640, 307) natively. The offset is
     * the one InGameState.render uses for the player ship.
     */
    private fun playerSpaceOrigin(): ConstPoint {
        // Without a planet image (only a test setup), the origin the native capture used.
        val origin = shotOrigin() ?: return ConstPoint(640, 307)
        val enemy = game.enemy
        val offsetX = if (enemy == null) 350 else if (enemy.isUsingBossUI) 150 else 200
        return ConstPoint(origin.x - offsetX, origin.y - 170)
    }

    /** The shot origin in the player ship's space, for the parity checks. */
    val diagnosticOrigin: ConstPoint get() = playerSpaceOrigin()

    /** The timer's elapsed time and goal (SpaceManager +0x2c0 / +0x2c4), for the parity checks. */
    val diagnosticTimer: FloatArray get() = floatArrayOf(time, fireAt)

    /** The shots of the current volley still to leave, the next one last. */
    val diagnosticQueue: List<AsbProjectile> get() = queue

    /** SpaceManager +0x29c: 0 targets the player, 1 the enemy, otherwise both. */
    val pdsTarget: Int get() = targetMode

    /** SpaceStatus shows ASB TARGET LOCKED! once the next shot is under 5 s away (0x005017f0). */
    val showWarning: Boolean get() = active && warned

    // The icon and the warning are SpaceStatus's, drawn by PlayerShipUI.
    override fun saveToXML(elem: Element) {
        elem.setAttribute("browserAsbTime", time.toString())
        elem.setAttribute("browserAsbWarningAt", warningAt.toString())
        elem.setAttribute("browserAsbFireAt", fireAt.toString())
        elem.setAttribute("browserAsbTarget", targetMode.toString())
        elem.setAttribute("browserAsbWarned", warned.toString())
        elem.setAttribute("browserAsbActive", active.toString())
        elem.setAttribute("browserAsbVolley", volleyTimer.toString())
    }
    /**
     * SpaceManager::LoadSpace (0x41d070) calls SetPlanetaryDefense again, which restarts the timer with a fresh 20-25 s
     * goal, then puts back the elapsed time SaveSpace (0x4180c0) wrote in whole milliseconds and the volley timer; the
     * goal itself is never saved. The warning follows the new goal (SpaceStatus shows it under 5 s, 0x005017f0).
     */
    override fun loadFromXML(elem: Element) {
        time = (elem.getAttributeValue("browserAsbTime").toFloat() * 1000f).toInt() / 1000f
        fireAt = nextFireTime()
        warningAt = fireAt - WARNING_LEAD
        warned = time >= warningAt
        targetMode = elem.getAttributeValue("browserAsbTarget").toInt()
        active = elem.getAttributeValue("browserAsbActive")?.toBoolean() ?: true
        volleyTimer = elem.getAttributeValue("browserAsbVolley")?.toFloatOrNull() ?: volleyTimer
    }
}

/**
 * A PDSFire (vtable 0x90cc2c), the planetary ASB's shot. It always starts at the volley origin in the player's space.
 *
 * - A miss flies to (-400, rand % 800) there (Projectile::RandomSidePoint(0), 0x45ce70) and on along its heading.
 * - A hit on the player homes on a room centre.
 * - A hit on the enemy leaves on a heading of rand % 90 + 135 degrees (PDSFire ctor 0x45d360), enters the enemy's
 *   space like any shot and homes on the room there.
 *
 * OnUpdate (0x456c60) grows it with the distance flown: scale = 1 + 7 * |pos - start| / |aim - start|, with the start
 * kept in the player's space even after it changes space, and speed = 3 * scale + 20 (x16 px/s), so it leaves at
 * 23 and reaches its target at 44. At the target (0x456ea0) it calls ShipManager::DamageArea with forceHit, so
 * there is no dodge roll and shields don't stop it; then it flies on until it leaves the space. CollisionMoving
 * (0x76af00) reports no collision: defence drones can't shoot it down.
 */
class AsbProjectile private constructor(
    targetShip: Ship,
    /** The room a hit is aimed at, or null for a miss. */
    val room: Room?,
    start: ConstFPoint,
    private val aim: ConstFPoint,
    /** A live shot the ship dodged: it draws numbers/text_miss.png (PDSFire +0x2bc, render 0x458e12). */
    var showsMiss: Boolean,
) : AbstractProjectile(targetShip), xyz.znix.xftl.weapons.NativeLaserBlastFields {
    val isHit: Boolean get() = room != null

    // PDSFire is a LaserBlast (GetType 6). Its own passedTarget (+0x2b4) is set when the hit lands;
    // ForceRenderLayer (1.6.14 0x4564d0, not hooked by HS) is 2 before that and 1 after.
    override val nativeType: Int get() = 6
    override var spinAngle = 0f
    override var spinSpeed = 0f
    override var movingTarget: Any? = null
    var pdsPassedTarget = false
    /** The constructor's heading draw, random32() % 90 + 135 (PDSFire +0x2c), kept for the native replay. */
    internal var drawnHeading = 0
    override fun nativeForceRenderLayer(): Int = if (pdsPassedTarget) 1 else 2
    /** PDSFire::startPoint, writable from Lua. */
    val startPoint = xyz.znix.xftl.math.MutFPoint(start.xf, start.yf)
    private val start: FPoint get() = startPoint
    val aimPoint: ConstFPoint get() = aim

    /** PDSFire +0x2b8, the render scale. */
    val scale: Float
        get() {
            val total = hypot(aim.xf - start.xf, aim.yf - start.yf)
            if (total <= 0f) return 1f
            return 1f + 7f * hypot(pos.xf - start.xf, pos.yf - start.yf) / total
        }

    /**
     * PDSFire +0x20 (a float): UpdatePDSFire's Initialize("PDS_SHOT") leaves the blueprint speed (20) for the first
     * frame; every OnUpdate (0x456c60) then sets 3 * scale + 20 after its motion (native 1.6.14 capture).
     */
    /** The blueprint Initialize took (PDS_SHOT for UpdatePDSFire, the script's weapon for HS CreatePDSFire). */
    var weapon: AbstractWeaponBlueprint? = targetShip.sys.blueprintManager.getOrNull("PDS_SHOT") as? AbstractWeaponBlueprint
        private set
    /**
     * PDSFire::explosionAnimation (+0x2c0): the constructor's GetAnimation("explosion_random"), one of
     * explosion_big2-4. It is the hit's explosion: CollisionCheck (0x456ea0) starts it at the target, OnUpdate
     * (0x456c60) advances it and stops it when done, and OnRenderSpecific (0x458be0) draws it under the shot while it
     * runs. The body's death_animation (Initialize's own pick) is not played by a hit.
     */
    var explosionSpec: xyz.znix.xftl.AnimationSpec? = null
        private set(value) {
            field = value
            if (value != null && value.length > 0 && value.totalTime > 0f) {
                explosionTracker.time = value.totalTime; explosionTracker.frameCount = value.length
            }
        }
    /** explosionAnimation's tracker: a fresh GetAnimation copy is done and not running. */
    val explosionTracker = xyz.znix.xftl.weapons.ProjectileDeathAnimation().also { it.done = true }
    /** explosionAnimation.position (+0x3c): its top left, set by the hit. */
    var explosionPosition: ConstFPoint = ConstFPoint(0f, 0f)
        private set

    /**
     * CollisionCheck's hit (0x456fd7): position = (int)(target - (int)(frame size * fScale) / 2) on each axis, then
     * Animation::Start(true). No draw.
     */
    private fun startExplosion() {
        val spec = explosionSpec ?: return
        val scale = explosionTracker.scale
        val half = { size: Int -> ((size * scale).toInt() / 2).toFloat() }
        explosionPosition = ConstFPoint((aim.xf - half(spec.sheet.frameWidth)).toInt().toFloat(),
            (aim.yf - half(spec.sheet.frameHeight)).toInt().toFloat())
        explosionTracker.start(true)
    }

    /** PDSFire::OnUpdate's explosionAnimation.Update(), then Done() -> tracker.Stop(false) (time back to 0). */
    private fun advanceExplosion(dt: Float) {
        val tracker = explosionTracker
        tracker.advance(dt)
        if (tracker.done && tracker.running) { tracker.running = false; tracker.currentTime = 0f }
    }

    // Vanilla: the body's own update; with HS the native OnUpdate (hsOnUpdate's nativeAfterMotion), which a
    // PROJECTILE_UPDATE_PRE preempt skips, as it skips PDSFire::OnUpdate.
    override fun update(dt: Float, currentSpace: Ship) {
        if (!(xyz.znix.xftl.hs.HS.active && hsNativeProjectile) && dt > 0f) advanceExplosion(dt)
        super.update(dt, currentSpace)
    }

    override fun nativeAfterMotion(dt: Float) {
        if (xyz.znix.xftl.hs.HS.active) advanceExplosion(dt)
    }
    // Initialize's death_animation = GetAnimation(explosion): PDS_SHOT's explosion_random draws its pick then.
    override val deathAnimationName: String get() = weapon?.explosion ?: "explosion_missile1"
    // The PDSFire constructor's speed is 60; Initialize keeps it for a blueprint speed of 0.
    private val shotSpeed: Float get() = weapon?.speed?.takeIf { it != 0 }?.toFloat() ?: 60f
    val pdsSpeed: Float
        get() = if (pos.xf == startPoint.xf && pos.yf == startPoint.yf) shotSpeed else 3f * scale + 20f
    override val nativeSpeedPerSecond: Float get() = pdsSpeed * 16f
    // In the 16x speed units.
    override val speed get() = (pdsSpeed * 16f).roundToInt()
    override val antiDroneBP: AbstractWeaponBlueprint? get() = null
    override val antiDroneExemption: Ship? get() = null
    override val collisionsEnabled get() = false
    override val serialisationType get() = "browserAsb"

    // The constructor's target point (a room centre for UpdatePDSFire's hits).
    override fun calculateTargetPosition(): IPoint = aim

    override fun reachedTarget() {
        // PDSFire::CollisionCheck (1.6.14 0x456ea0) hits once: only while !passedTarget.
        if (pdsPassedTarget) return
        if (xyz.znix.xftl.hs.HS.active) {
            // ShipManager::DamageArea(target, damage, forceHit) on the destination ship, with HS's DAMAGE_AREA and
            // DAMAGE_AREA_HIT hooks; a point in no room is false without a roll (the HS oracle's CreatePDSFire miss).
            pdsPassedTarget = true
            val receiver = targetShip ?: return
            val damage = hsExt?.damage?.copy() ?: weapon?.let { Damage(it) } ?: return
            val hit = hsDamageAreaWith(receiver, room, aim, true, damage, true, true)
            if (hit) { startExplosion(); hitSound(receiver)?.play() }
            return
        }
        val room = room ?: return
        pdsPassedTarget = true
        val ship = room.ship
        val type = weapon
        // Projectile::Initialize("PDS_SHOT"): 3 damage, a certain breach, explosion_random and hitHull1.
        val damage = if (type != null) Damage(type) else Damage.hullAndSys(3).also {
            it.noCrewDamage = false
            it.breachChance = 100
        }
        ship.damage(room, damage)
        startExplosion()
        hitSound(ship)?.play()
    }

    /** The hit plays hitSolidSound, Initialize's pick (Projectile +0x224), at volume -1: no draw at the hit. */
    private fun hitSound(ship: Ship) = ship.sys.sounds.getSampleOrWarn(nativeFields.hitSolidSound.ifEmpty { "hitHull1" })

    override fun render(g: Graphics, currentSpace: Ship) {
        // OnRenderSpecific (0x458be0): a running explosionAnimation first (OnRender(1, white)), then the shot.
        val spec = explosionSpec
        if (spec != null && explosionTracker.running) {
            val frame = explosionTracker.currentFrame.coerceIn(0, spec.length - 1)
            val image = spec.spriteAt(currentSpace.sys, frame)
            val scale = explosionTracker.scale
            image.draw(explosionPosition.xf, explosionPosition.yf, image.width * scale, image.height * scale)
        }
        super.render(g, currentSpace)
        if (showsMiss)
            currentSpace.sys.getImg("img/numbers/text_miss.png").draw(pos.xf.toInt().toFloat(), pos.yf.toInt().toFloat())
    }

    /**
     * PDSFire's render (0x458be0): the shot is drawn rotated about its position at its scale, offset by half its
     * scaled size truncated to whole pixels. It is no Graphics scale, so the image keeps the original's
     * nearest-neighbour filtering (a scaled draw would be smoothed).
     */
    override fun renderPreTranslated(g: Graphics) {
        val image = targetShip!!.sys.getImg("img/weapons/pds.png")
        val box = drawBox(image.width, image.height, scale)
        image.draw(box[0], box[1], box[2], box[3])
    }

    override fun hitOtherProjectile(currentSpace: Ship) {}

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        // A miss names a room of the player's ship only to find the ship again.
        SaveUtil.addRoomRef(elem, "target", refs, room ?: targetShip!!.rooms.first())
        elem.setAttribute("browserAsbHit", isHit.toString())
        elem.setAttribute("browserAsbStart", "${start.xf},${start.yf}")
        elem.setAttribute("browserAsbAim", "${aim.xf},${aim.yf}")
        elem.setAttribute("browserAsbMissText", showsMiss.toString())
        weapon?.name?.takeIf { it != "PDS_SHOT" }?.let { elem.setAttribute("browserAsbWeapon", it) }
        explosionSpec?.let { elem.setAttribute("browserAsbExplosion", it.name) }
    }

    companion object {
        private const val HEADING_DISTANCE = 4000f

        /** The quad PDSFire draws (0x458be0), relative to the shot's position: x, y, width and height. */
        fun drawBox(imageWidth: Int, imageHeight: Int, scale: Float): FloatArray {
            val width = imageWidth * scale
            val height = imageHeight * scale
            return floatArrayOf(-(width.toInt() / 2).toFloat(), -(height.toInt() / 2).toFloat(), width, height)
        }

        /**
         * The PDSFire constructor (1.6.14 0x45d360) always draws a heading of random32() % 90 + 135 degrees first;
         * it is kept for a shot that leaves for another space, and LaserBlast::ComputeHeading replaces it for one
         * that stays. Then Initialize(blueprint): the sound-name draws (0x45a430) and, with HS, its Initialize hook.
         */
        private fun construct(targetShip: Ship, room: Room?, origin: IPoint, aim: ConstFPoint, showsMiss: Boolean,
                              sameSpace: Boolean, weapon: AbstractWeaponBlueprint?, script: Boolean = false): AsbProjectile {
            val start = ConstFPoint(origin.x.toFloat(), origin.y.toFloat())
            // Projectile(x, y, 0, destination, target) (0x4598e0): owner and current space 0, the player's.
            val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(start, aim, targetShip.sys.player, 0, 0,
                xyz.znix.xftl.hs.weapons.HsWeapons.shipId(targetShip))
            // HS 1.23.2 CreatePDSFire uses the C++ `new PDSFire(pos, space, target)` of its headers, which first runs
            // the base `LaserBlast(Pointf(pos), 0, space, target)` (ProjectileVTable.cpp: this->constructor, i.e. the
            // hooked Projectile constructor: CONSTRUCT_PROJECTILE and a space id) and then the native PDSFire
            // constructor, whose own Projectile constructor call fires CONSTRUCT_PROJECTILE and takes an id again.
            // UpdatePDSFire's shots run the native constructor alone.
            val twice = script && xyz.znix.xftl.hs.HS.active
            var degrees = 0
            val projectile = if (twice) {
                xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                    AsbProjectile(targetShip, room, start, aim, showsMiss)
                }.also {
                    degrees = BrowserRandom.nextBits(31) % 90 + 135
                    it.hsExt?.selfId = xyz.znix.xftl.hs.weapons.HsWeapons.nextSpaceId()
                    xyz.znix.xftl.hs.weapons.HsWeaponHooks.constructProjectile(it)
                }
            } else {
                degrees = BrowserRandom.nextBits(31) % 90 + 135
                xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                    AsbProjectile(targetShip, room, start, aim, showsMiss)
                }
            }
            // GetAnimation("explosion_random") (0x5a6450): random32() % 3 picks explosion_big2-4.
            projectile.drawnHeading = degrees
            projectile.explosionSpec = targetShip.sys.animations.getOrNull("explosion_random")
            if (sameSpace) {
                projectile.setInitialPath(origin, aim)
            } else {
                val heading = Math.toRadians(degrees.toDouble())
                projectile.setInitialPath(origin, ConstPoint(
                    origin.x + (cos(heading) * HEADING_DISTANCE).roundToInt(),
                    origin.y + (sin(heading) * HEADING_DISTANCE).roundToInt()))
            }
            // The PDSFire part then sets ownerId -1 (0x45d360: +0x30 = -1; CONSTRUCT_PROJECTILE, fired by the base
            // constructor, still reads 0), so no ship is its owner and the hit lands on the player too.
            projectile.hsOwnerId = -1
            if (weapon != null) { projectile.weapon = weapon; projectile.hsInitialize(weapon, 60) }
            return projectile
        }

        private fun pdsShot(ship: Ship) = ship.sys.blueprintManager.getOrNull("PDS_SHOT") as? AbstractWeaponBlueprint

        /** UpdatePDSFire's hit: the room centre was drawn by the caller (GetRandomRoomCenter). */
        fun hit(room: Room, player: Ship, origin: IPoint): AsbProjectile {
            val centre = room.pixelCentre
            return construct(room.ship, room, origin, ConstFPoint(centre.x.toFloat(), centre.y.toFloat()), false,
                room.ship == player, pdsShot(player))
        }

        /**
         * Native 1.6.14 replay: a hit flying from [start] to an exact [aim] (the capture's room centre). [constructed]:
         * as the probe's PDSFire(start, 0, aim) + Initialize("PDS_SHOT"): the heading draw, the explosionAnimation
         * pick, ownerId -1, then Initialize's draws.
         */
        internal fun probe(room: Room, start: ConstFPoint, aim: ConstFPoint, constructed: Boolean = false): AsbProjectile {
            if (!constructed) return AsbProjectile(room.ship, room, start, aim, false).also { it.setInitialPath(start, aim) }
            val degrees = BrowserRandom.nextBits(31) % 90 + 135
            val projectile = AsbProjectile(room.ship, room, start, aim, false)
            projectile.drawnHeading = degrees
            projectile.explosionSpec = room.ship.sys.animations.getOrNull("explosion_random")
            projectile.setInitialPath(start, aim)
            projectile.hsOwnerId = -1
            pdsShot(room.ship)?.let { projectile.weapon = it; projectile.hsInitialize(it, 60) }
            return projectile
        }

        /** UpdatePDSFire's miss: Projectile::RandomSidePoint(0) (0x45ce70) in the player's space, then the constructor. */
        fun miss(player: Ship, origin: IPoint, live: Boolean): AsbProjectile {
            val side = ConstPoint(-400, BrowserRandom.nextBits(31) % 800)
            return construct(player, null, origin, ConstFPoint(side.x.toFloat(), side.y.toFloat()), live, true, pdsShot(player))
        }

        /**
         * HS SpaceManager::CreatePDSFire (CustomDamage.cpp): `new PDSFire(position, targetSpace, target)` from the
         * player's space, then Initialize(weapon). It hits the room of [destination] at [target], if any.
         */
        fun create(weapon: AbstractWeaponBlueprint, destination: Ship, sameSpace: Boolean, origin: IPoint, target: ConstFPoint): AsbProjectile {
            val room = destination.rooms.firstOrNull { it.containsShipSpace(ConstPoint(target.xf.toInt(), target.yf.toInt())) }
            return construct(destination, room, origin, target, false, sameSpace, weapon, script = true)
        }

        private fun parsePoint(value: String?): ConstFPoint? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 2) return null
            return ConstFPoint(parts[0].toFloatOrNull() ?: return null, parts[1].toFloatOrNull() ?: return null)
        }

        fun loadFromXML(elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                // Saves from before these attributes held a hit flying from its own space to the room.
                val hit = elem.getAttributeValue("browserAsbHit")?.toBoolean() ?: true
                val x = SaveUtil.getAttrFloat(elem, "x")
                val y = SaveUtil.getAttrFloat(elem, "y")
                val start = parsePoint(elem.getAttributeValue("browserAsbStart")) ?: ConstFPoint(x, y)
                val aim = parsePoint(elem.getAttributeValue("browserAsbAim"))
                    ?: target.pixelCentre.let { ConstFPoint(it.x.toFloat(), it.y.toFloat()) }
                val missText = elem.getAttributeValue("browserAsbMissText")?.toBoolean() ?: false
                val projectile = AsbProjectile(target.ship, if (hit) target else null, start, aim, missText)
                elem.getAttributeValue("browserAsbWeapon")?.let {
                    projectile.weapon = target.ship.sys.blueprintManager.getOrNull(it) as? AbstractWeaponBlueprint ?: projectile.weapon
                }
                elem.getAttributeValue("browserAsbExplosion")?.let { projectile.explosionSpec = target.ship.sys.animations.getOrNull(it) }
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}

private const val WARNING_LEAD = 5f
private const val SMOKE_FRAMES = 7
private const val SMOKE_TIME = 1f
private const val SMOKE_SCALE = .5f
