package xyz.znix.xftl.environment

import ftl.browser.BrowserRandom
import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
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

    private var fireAt = nextFireTime()
    private var warningAt = fireAt - WARNING_LEAD
    private var time = 0f
    private var warned = false

    // The next volley, and the shots of the current volley still to leave.
    private var volleyTimer = 2f + BrowserRandom.nextInt(200) / 100f
    private val queue = ArrayList<AsbProjectile>()

    /**
     * UpdatePDS (0x0041d5e0) starts effects/fire_smoke.png (7 frames of 34 px over 1 s, at half scale, 8.5 px
     * up and left) at the origin of every shot it launches, hit or miss: the flagship's centre with a fleet
     * (fleet_big at (550, 447), sized 2w - 100 by h + 25 by OnInit 0x0041ac40), else the planet's.
     * OnRenderProjectiles (0x0041a8b0) draws them, untinted, just before the player ship.
     */
    private val smokes = ArrayList<Float>()
    private fun shotOrigin(): ConstPoint? {
        if (hasFleet) {
            val big = game.getImg("img/ship/fleet/fleet_big.png")
            return ConstPoint(550 + (2 * big.width - 100) / 2, 447 + (big.height + 25) / 2)
        }
        return planetCentre
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        super.renderBackground(gc, g)
        val origin = shotOrigin() ?: return
        val sheet = game.getImg("img/effects/fire_smoke.png")
        val size = sheet.width / SMOKE_FRAMES
        for (age in smokes) {
            val frame = minOf(SMOKE_FRAMES - 1, (age / SMOKE_TIME * SMOKE_FRAMES).toInt())
            val x = origin.x - 8.5f
            val y = origin.y - 8.5f
            sheet.draw(x, y, x + size * 0.5f, y + sheet.height * 0.5f,
                (frame * size).toFloat(), 0f, ((frame + 1) * size).toFloat(), sheet.height.toFloat())
        }
    }

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
                queue += if (hit) AsbProjectile.hit(ship.rooms.random(BrowserRandom), game.player, origin)
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
        volleyTimer = if (queue.isEmpty()) 2f + BrowserRandom.nextInt(300) / 100f else 0.5f
    }

    // TimerHelper::Start (0x5cb0b0): a whole number of milliseconds in 20-25 s.
    private fun nextFireTime(): Float = (20000 + BrowserRandom.nextInt(5000)) / 1000f

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
    override fun loadFromXML(elem: Element) {
        time = elem.getAttributeValue("browserAsbTime").toFloat()
        warningAt = elem.getAttributeValue("browserAsbWarningAt").toFloat()
        fireAt = elem.getAttributeValue("browserAsbFireAt").toFloat()
        targetMode = elem.getAttributeValue("browserAsbTarget").toInt()
        warned = elem.getAttributeValue("browserAsbWarned").toBoolean()
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
    private val start: ConstFPoint,
    private val aim: ConstFPoint,
    /** A live shot the ship dodged: it draws numbers/text_miss.png (PDSFire +0x2bc, render 0x458e12). */
    val showsMiss: Boolean,
) : AbstractProjectile(targetShip) {
    val isHit: Boolean get() = room != null
    val startPoint: ConstFPoint get() = start
    val aimPoint: ConstFPoint get() = aim

    /** PDSFire +0x2b8, the render scale. */
    val scale: Float
        get() {
            val total = hypot(aim.xf - start.xf, aim.yf - start.yf)
            if (total <= 0f) return 1f
            return 1f + 7f * hypot(pos.xf - start.xf, pos.yf - start.yf) / total
        }

    // PDSFire +0x20, in the 16x speed units; PDS_SHOT's own speed of 20 is overwritten every frame.
    override val speed get() = ((3f * scale + 20f) * 16f).roundToInt()
    override val antiDroneBP: AbstractWeaponBlueprint? get() = null
    override val antiDroneExemption: Ship? get() = null
    override val collisionsEnabled get() = false
    override val serialisationType get() = "browserAsb"

    override fun calculateTargetPosition(): IPoint = room!!.pixelCentre

    override fun reachedTarget() {
        val room = room ?: return
        val ship = room.ship
        val type = ship.sys.blueprintManager.getOrNull("PDS_SHOT") as? AbstractWeaponBlueprint
        // Projectile::Initialize("PDS_SHOT"): 3 damage, a certain breach, explosion_random and hitHull1.
        val damage = if (type != null) Damage(type) else Damage.hullAndSys(3).also {
            it.noCrewDamage = false
            it.breachChance = 100
        }
        ship.damage(room, damage)
        if (type?.explosion != null) ship.playDamageEffect(type, room.pixelCentre)
        else ship.playCentredAnimation(ship.sys.animations["explosion1"], room.pixelCentre)
        (type?.hitShipSounds?.get() ?: ship.sys.sounds.getSample("hitHull1")).play()
    }

    override fun render(g: Graphics, currentSpace: Ship) {
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
    }

    companion object {
        private const val HEADING_DISTANCE = 4000f

        /** The quad PDSFire draws (0x458be0), relative to the shot's position: x, y, width and height. */
        fun drawBox(imageWidth: Int, imageHeight: Int, scale: Float): FloatArray {
            val width = imageWidth * scale
            val height = imageHeight * scale
            return floatArrayOf(-(width.toInt() / 2).toFloat(), -(height.toInt() / 2).toFloat(), width, height)
        }

        fun hit(room: Room, player: Ship, origin: IPoint): AsbProjectile {
            val start = ConstFPoint(origin.x.toFloat(), origin.y.toFloat())
            val centre = room.pixelCentre
            val projectile = AsbProjectile(room.ship, room, start, ConstFPoint(centre.x.toFloat(), centre.y.toFloat()), false)
            if (room.ship == player) {
                projectile.setInitialPath(origin, centre)
            } else {
                val heading = Math.toRadians((BrowserRandom.nextInt(90) + 135).toDouble())
                projectile.setInitialPath(origin, ConstPoint(
                    origin.x + (cos(heading) * HEADING_DISTANCE).roundToInt(),
                    origin.y + (sin(heading) * HEADING_DISTANCE).roundToInt()))
            }
            return projectile
        }

        fun miss(player: Ship, origin: IPoint, live: Boolean): AsbProjectile {
            val side = ConstPoint(-400, BrowserRandom.nextInt(800))
            val projectile = AsbProjectile(player, null, ConstFPoint(origin.x.toFloat(), origin.y.toFloat()),
                ConstFPoint(side.x.toFloat(), side.y.toFloat()), live)
            projectile.setInitialPath(origin, side)
            return projectile
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
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}

private const val WARNING_LEAD = 5f
private const val SMOKE_FRAMES = 7
private const val SMOKE_TIME = 1f
