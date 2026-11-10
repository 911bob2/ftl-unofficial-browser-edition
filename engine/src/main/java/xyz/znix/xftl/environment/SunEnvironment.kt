package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.Damage
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class SunEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.SUN

    private val sunImage = game.getImg("img/stars/planet_sun1.png")
    private val glowImage = game.getImg("img/stars/planet_sun2.png")

    private val warningSound = game.sounds.getSample("environWarning")
    private val flareSound = game.sounds.getSample("solarFlare")

    // Time since we jumped into the level
    private var time = 0f

    private var nextFlareTime = 0f
    private var lastFlareTime = -100f // Don't show a flare as we jump in
    private var hasPlayedSound = false
    private var hasPlayedWarning = false

    val showWarning: Boolean get() = (nextFlareTime - time) < 5f

    init {
        // SpaceManager::SetSun (native 1.6.9 0x416fb0) starts TimerHelper on the generation stream.
        prepareNextFlare(ftl.browser.NativeRandom)
    }

    /**
     * SpaceManager::OnRenderForeground (1.6.14 0x419e30): the sun and its glows take the planet's place; the beacon's
     * backdrop stays behind them (SetFireLevel 0x418ae0 leaves it) and the background fleet comes after.
     */
    override fun renderForegroundArt(g: Graphics, ps: Colour) {
        // The sun and its glows are SpaceManager's foreground, drawn at the jump's planet scale.
        browserDrawLog?.add("sun" to sunImage)
        g.pushTransform()
        game.applyJumpScene(g)

        val backGlow = alphaCycle(7f, 0.1f, 1f)
        val frontGlow = alphaCycle(10f, 0.05f, 0.4f)
        val sunOpacity = alphaCycle(5f, 0.7f, 1f)

        glowImage.draw(
            0f, 0f,
            1000f, 1000f,
            Colour(ps.r, ps.g, ps.b, backGlow)
        )

        g.pushTransform()
        g.translate(100f, 109f)
        g.rotate(sunImage.width / 2f, sunImage.height / 2f, 132f)
        sunImage.draw(
            0f, 0f,
            Colour(ps.r, ps.g, ps.b, sunOpacity)
        )
        g.popTransform()

        glowImage.draw(
            0f, 0f,
            1000f, 1000f,
            Colour(1f, 1f, 1f, frontGlow)
        )
        g.popTransform()

        if (game.debugFlags.showSunTimer.set) {
            game.getFont("HL2", 2f).drawString(
                550f, 20f,
                "Flare in " + (nextFlareTime - time),
                Colour.white
            )
        }
    }

    override fun nativeFlashOpacity(): Float {
        // Find how long it's been since/until the middle of this flare
        val timeFromLast = time - lastFlareTime
        val timeToNext = nextFlareTime - time
        val flareTimer = min(timeFromLast, timeToNext)

        // The flare is 1sec long in total, 0.5sec on either side.
        val progress = 1 - flareTimer / 0.5f
        if (progress < 0)
            return 0f

        // The alpha is the cube of how far we are from the flare
        return progress.pow(3)
    }

    override val flashFilter: Colour get() = Constants.SOLAR_FLARE_FILTER

    override fun update(dt: Float) {
        super.update(dt)

        time += dt

        val timeToFlare = nextFlareTime - time
        if (timeToFlare < 1.2f && !hasPlayedSound) {
            flareSound.play()
            hasPlayedSound = true
        }
        if (showWarning && !hasPlayedWarning) {
            warningSound.play()
            hasPlayedWarning = true
        }
        if (timeToFlare < 0f) {
            lastFlareTime = nextFlareTime

            dealFlareDamage(game.player)
            game.enemy?.let { dealFlareDamage(it) }
            prepareNextFlare()
        }
    }

    private fun alphaCycle(period: Float, min: Float, max: Float): Float {
        // Triangle wave
        val progress = time.rem(period) / period
        val changing = when {
            progress < 0.5f -> progress * 2
            else -> 2 - progress * 2
        }

        return min + changing * (max - min)
    }

    private fun prepareNextFlare(random: Random = ftl.browser.BrowserRandom) {
        // TimerHelper::ResetMinMax(28000, 34000), Start(-1): millisecond resolution, upper bound excluded.
        // Later flares follow real-time frames and use the engine's combat stream.
        nextFlareTime = time + (28000 + random.nextInt(6000)) / 1000f
        hasPlayedSound = false
        hasPlayedWarning = false
    }

    private fun dealFlareDamage(ship: Ship) {
        // ShipManager::SunDamage (0x4a4e80) returns at once for a ship in its jump (GetIsJumping, bJumping).
        if (ship.browserJumping) return
        val normalShieldUp = ship.shields?.activeShields?.let { it > 0 } ?: false
        val hasShields = ship.superShield > 0 || normalShieldUp

        var numFires = when (hasShields) {
            true -> (1..2).random(ftl.browser.BrowserRandom)
            false -> (3..6).random(ftl.browser.BrowserRandom)
        }

        // ShipManager::SunDamage (0x4a4e80): fires go out in chunks of one
        // or two (one for the last) to random rooms, counted per room.
        val firesInRoom = HashMap<Room, Int>()

        while (numFires > 0) {
            val room = ship.rooms.random(ftl.browser.BrowserRandom)

            // Start 1 or 2 fires, if we have enough remaining
            var numStarted = 1
            room.spawnFire()
            if (numFires > 1 && ftl.browser.BrowserRandom.rollChance(50)) {
                room.spawnFire()
                numStarted++
            }
            firesInRoom[room] = (firesInRoom[room] ?: 0) + numStarted

            numFires -= numStarted
        }

        // Then each room rolls once: fires * 33% for one point of system
        // damage and one of hull, applied directly (no armour or casing
        // rolls, no crew damage).
        for (room in ship.rooms) {
            val fires = firesInRoom[room] ?: continue
            if (!ftl.browser.BrowserRandom.rollChance(fires * 33))
                continue
            if (ship.sys.debugFlags.noDmg.set)
                continue

            room.system?.dealDamage(1, 0)
            ship.health -= 1
            room.showDamageText(1, Colour.white, null)
        }
    }

    override fun saveToXML(elem: Element) {
        super.saveToXML(elem)

        SaveUtil.addAttrFloat(elem, "sunTimer", time)
        SaveUtil.addAttrFloat(elem, "nextFlare", nextFlareTime)
        SaveUtil.addAttrFloat(elem, "lastFlare", lastFlareTime)
    }

    override fun loadFromXML(elem: Element) {
        super.loadFromXML(elem)

        time = SaveUtil.getAttrFloat(elem, "sunTimer")
        nextFlareTime = SaveUtil.getAttrFloat(elem, "nextFlare")
        lastFlareTime = SaveUtil.getAttrFloat(elem, "lastFlare")
        hasPlayedSound = (nextFlareTime - time) < 1.2f
        hasPlayedWarning = showWarning
    }
}
