package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.SubSystem
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

class PulsarEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.PULSAR

    private val realBackground = game.getImg("img/stars/bg_dullstars.png")

    private val sunBlack = game.getImg("img/effects/pulsar_black.png")
    private val sunWhite = game.getImg("img/effects/pulsar_white.png")
    private val waveBack = game.getImg("img/effects/pulsar_backL.png")
    private val waveFront = game.getImg("img/effects/pulsar_frontL.png")

    private val warningSound = game.sounds.getSample("environWarning")
    private val pulseSound = game.sounds.getSample("pulsar")

    // Time since we jumped into the level
    private var animationTimer = 0f

    private var timeToPulse = 0f
    private var pulseTimer: Float? = null
    private var hasPlayedSound = false
    private var hasPlayedWarning = false

    val showWarning: Boolean
        get() = timeToPulse < 5f

    /** The seconds to the next pulse, for the parity checks. */
    val diagnosticTimeToPulse: Float get() = timeToPulse

    init {
        // SetPulsarLevel starts its timer on the global stream; a loaded save restores the timer.
        prepareNextFlare(if (game.isCurrentlyLoadingSave) ftl.browser.BrowserRandom else ftl.browser.NativeRandom)
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        val ps = pauseShade(game)
        if (hsBackdrop) renderPulsar(ps)
        // SpaceManager::OnRenderForeground (0x419e30) draws the background fleet after the pulsar.
        if (hsForeground) renderFleet(g, ps)
    }

    private fun renderPulsar(ps: Colour) {
        // This draws the normal background with all its adjustments
        // (Hyperspace render layers: the pulsar is drawn with the backdrop, as the engine draws it unscaled.)
        realBackground.draw(0f, 0f, ps)
        browserDrawLog?.add("back" to realBackground)
        browserDrawLog?.add("pulsar" to sunBlack)

        val whiteAlpha = alphaCycle(7f, 0.1f, 1f)

        val centreX = 463
        val centreY = 203

        val waveAlpha = pulseTimer?.let { 1 - it }
        val waveFilter = waveAlpha?.let { Colour(ps.r, ps.g, ps.b, it) }
        val waveScale = (pulseTimer ?: 0f) * 3

        if (waveFilter != null) {
            waveBack.drawAlignedCentred(
                centreX, centreY,
                waveBack.width * waveScale,
                waveBack.height * waveScale,
                waveFilter
            )
        }

        sunBlack.drawAlignedCentred(centreX, centreY, ps)
        val filter = Colour(ps.r, ps.g, ps.b, whiteAlpha)
        sunWhite.drawAlignedCentred(centreX, centreY, filter)

        if (waveFilter != null) {
            waveFront.drawAlignedCentred(
                centreX, centreY,
                waveBack.width * waveScale,
                waveBack.height * waveScale,
                waveFilter
            )
        }

        if (game.debugFlags.showSunTimer.set) {
            game.getFont("HL2", 2f).drawString(
                550f, 20f,
                "Pulse in $timeToPulse",
                Colour.white
            )
        }
    }

    override fun nativeFlashOpacity(): Float {
        val pulseTimer = pulseTimer ?: return 0f

        // Find how long it's been since/until the middle of this flare
        val middleTimer = abs(pulseTimer - 0.5f)

        // The flare is 1sec long in total, 0.5sec on either side.
        val progress = 1 - middleTimer / 0.5f

        // The alpha is the cube of how far we are from the flare
        return progress.pow(3)
    }

    override val flashFilter: Colour get() = Constants.PULSAR_PULSE_FILTER

    override fun sceneBackdrop() = realBackground

    override fun update(dt: Float) {
        super.update(dt)

        animationTimer += dt
        timeToPulse -= dt

        val oldPT = pulseTimer
        if (oldPT != null) {
            val newPT = oldPT + dt
            if (newPT > 1f) {
                pulseTimer = null
            } else {
                pulseTimer = newPT
            }
        }

        if (timeToPulse < 1.2f && !hasPlayedSound) {
            pulseSound.play()
            hasPlayedSound = true
        }
        if (timeToPulse < 0.5f && pulseTimer == null) {
            pulseTimer = 0f
        }
        if (timeToPulse < 0f) {
            dealIonDamage(game.player)
            game.enemy?.let { dealIonDamage(it) }
            prepareNextFlare()
        }

        if (showWarning && !hasPlayedWarning) {
            warningSound.play()
            hasPlayedWarning = true
        }
    }

    private fun alphaCycle(period: Float, min: Float, max: Float): Float {
        // Triangle wave
        val progress = animationTimer.rem(period) / period
        val changing = when {
            progress < 0.5f -> progress * 2
            else -> 2 - progress * 2
        }

        return min + changing * (max - min)
    }

    /**
     * SpaceManager::SetPulsarLevel (1.6.14 0x418f70, 1.6.9 0x417440): TimerHelper::ResetMinMax(11000, 18000) and
     * Start(-1) (0x5cb0b0), a goal of `(random32() % 7000 + 11000) / 1000` seconds; TimerHelper::Done (0x5cb200)
     * draws the next goal the same way when it loops. Later pulses follow real-time frames and use the combat stream.
     */
    private fun prepareNextFlare(random: kotlin.random.Random = ftl.browser.BrowserRandom) {
        timeToPulse = (11000 + random.nextBits(31) % 7000) / 1000f
        hasPlayedSound = false
        hasPlayedWarning = false
    }

    // ShipManager's pulsar hit (0x4a36b0) and Shields::PulsarDamage (0x6329c0).
    private fun dealIonDamage(ship: Ship) {
        // ShipManager::GetIsJumping: a ship in its jump (bJumping) takes no pulse.
        if (ship.browserJumping) return
        val resistDamage = ship.ionArmourNegates()
        val shields = ship.shields

        // A Reverse Ion Field proc against raised shields negates the pulse.
        if (resistDamage && shields != null && (shields.powerSupplied > 0 || ship.superShield > 0)) {
            shields.room!!.showDamageText("text_resist", Constants.DAMAGE_COLOUR_ION)
            return
        }

        // Super-shields block all incoming damage
        if (ship.superShield > 0 && !resistDamage) {
            ship.superShield -= (3..4).random(ftl.browser.BrowserRandom)
            return
        }

        // Powered shields take the first hit, leaving one more for another
        // system; otherwise two random systems are hit, never the same one
        // twice in a row.
        var hits = 2
        var last: AbstractSystem? = null
        if (!resistDamage && shields != null && shields.powerSupplied > 0) {
            dealSystemIon(shields, false)
            last = shields
            hits = 1
        }

        repeat(hits) {
            val candidates = ship.systems.filter { it !== last }.ifEmpty { ship.systems }
            val system = candidates.random(ftl.browser.BrowserRandom)
            dealSystemIon(system, resistDamage)
            last = system
        }
    }

    private fun dealSystemIon(system: AbstractSystem, resist: Boolean) {
        val currentPower = when (system) {
            is MainSystem -> system.powerSupplied
            else -> (system as SubSystem).effectivePower
        }

        val damage = 1 + (currentPower / 2f).toInt()

        if (resist) {
            // Vanilla tints the ion-resist popup with the ion damage-number
            // blue (user-observed in 1.6.14), not white.
            system.room!!.showDamageText("text_resist", Constants.DAMAGE_COLOUR_ION)
        } else {
            system.dealDamage(0, damage)
            system.room!!.showDamageText(damage, Constants.DAMAGE_COLOUR_ION)
        }
    }

    override fun saveToXML(elem: Element) {
        super.saveToXML(elem)

        // Don't bother saving the glow animation progress
        SaveUtil.addAttrFloat(elem, "nextPulse", timeToPulse)
        SaveUtil.addTagFloat(elem, "pulseTimer", pulseTimer, null)
    }

    override fun loadFromXML(elem: Element) {
        super.loadFromXML(elem)

        timeToPulse = SaveUtil.getAttrFloat(elem, "nextPulse")
        pulseTimer = SaveUtil.getOptionalTagFloat(elem, "pulseTimer")

        hasPlayedSound = timeToPulse < 1.2f
        hasPlayedWarning = showWarning
    }
}
