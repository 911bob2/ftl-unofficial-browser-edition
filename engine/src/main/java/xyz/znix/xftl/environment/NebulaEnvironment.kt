package xyz.znix.xftl.environment

import kotlin.math.min

import xyz.znix.xftl.*
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import kotlin.random.nextInt

class NebulaEnvironment(game: InGameState, beacon: Beacon, val ionStorm: Boolean) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType
        get() = when (ionStorm) {
            true -> Beacon.EnvironmentType.ION_STORM
            false -> Beacon.EnvironmentType.NEBULA
        }

    private val clouds = ArrayList<Cloud>()

    private val normalImage = game.getImg("img/stars/nebula_large_c.png")
    private val stormImage = game.getImg("img/stars/nebula_large_b.png")
    private val stormFlashImage = game.getImg("img/stars/nebula_large_a.png")
    private val lightningImage = game.getImg("img/stars/lightning.png")

    // SpaceManager::OnLoop (0x41f3c0) keeps the nebula ambience loop at full
    // volume at every nebula beacon, ion storms included.
    private val ambience = game.sounds.getLoop("nebula")

    init {
        for (xIdx in 0 until 5) {
            for (yIdx in 0 until 4) {
                val pos = ConstPoint(
                    xIdx * 350 + VisualRandom.nextInt(-60..60),
                    yIdx * 230 + VisualRandom.nextInt(-40..40)
                )
                clouds += Cloud(pos)
            }
        }

        // Don't use a left-to-right order, as this determines which clouds
        // are drawn on over each other.
        clouds.shuffle(ftl.browser.BrowserRandom)

        // SpaceManager's nebula setup (0x41fd00) pre-runs the clouds 800 times
        // with the speed factor of the frame that sets them up (CFPS: the frame's
        // seconds x 16, capped at 0.5), lightning sounds included: the opening
        // burst grows with that frame's length. On a frame with no time the
        // lightning timers stand still and the clouds move by the 0.05 default
        // (0x41e1e0).
        val frame = min(game.renderingDeltaTime.coerceAtLeast(0f), MAX_SPEED_FACTOR / 16f)
        for (i in 0 until 800) {
            updateClouds(frame, if (frame > 0f) frame else ZERO_FRAME_MOTION / 16f)
        }
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        for (cloud in clouds) {
            g.pushTransform()
            g.translate(cloud.centre.x.f, cloud.centre.y.f)
            g.scale(cloud.scale, cloud.scale)

            val lightning = cloud.lightingTimer > 0f && ionStorm
            val cloudImage = when {
                lightning -> stormFlashImage
                ionStorm -> stormImage
                else -> normalImage
            }

            val filter = pauseShade(game)
            filter.a = cloud.alpha
            cloudImage.draw(-cloudImage.width / 2, -cloudImage.height / 2, filter)

            // Draw the lightning image on top
            if (lightning) {
                g.rotateRadians(0f, 0f, cloud.lightningRotation)
                lightningImage.draw(-lightningImage.width / 2, -lightningImage.height / 2, filter)
            }

            g.popTransform()
        }
    }

    override fun update(dt: Float) {
        super.update(dt)
        ambience.continueLoopAnyShip()
        updateClouds(dt, dt)
    }

    private fun updateClouds(dt: Float, motionDt: Float) {
        // Use indices so we can add new clouds
        var offset = 0
        for (idx in clouds.indices) {
            val cloud = clouds[idx + offset]
            // A storm cloud's lightning plays lightning1-4 at a volume of
            // 0.9-1.0 (0x41e1e0).
            if (cloud.update(dt, motionDt) && ionStorm) {
                val sound = game.sounds.getSampleOrWarn("lightning${ftl.browser.BrowserRandom.nextInt(4) + 1}")
                sound?.play()?.volume = (ftl.browser.BrowserRandom.nextInt(101) + 900) / 1000f
            }

            // Does this cloud need to be replaced?
            if (!cloud.isFadingIn && !cloud.isReplaced && cloud.alpha < cloud.replaceThreshold) {
                cloud.isReplaced = true

                clouds.add(0, Cloud(cloud.centre))
                offset++
            }
        }

        clouds.removeIf { it.alpha == 0f && !it.isFadingIn }
    }

    override fun adjustShipPower(ship: Ship, powerAvailableTypes: HashMap<EnergySource, Int>) {
        if (!ionStorm)
            return

        var reactorPower = powerAvailableTypes[ReactorEnergySource] ?: 0
        val toDeduct = reactorPower / 2 // Rounds down
        reactorPower -= toDeduct
        powerAvailableTypes[ReactorEnergySource] = reactorPower
    }

    private class Cloud(
        val centre: IPoint
    ) {
        var alpha = 0f
        var scale = (3f..4f).random(VisualRandom)

        var isFadingIn: Boolean = true
        var isReplaced: Boolean = false

        // A cloud's lightning timer starts at 100-300 speed-factor units
        // (16 a second), and restarts at that once its 0.25 s flash ends
        // (0x41fd00, 0x41e1e0).
        var nextLightning = lightningDelay()
        var lightingTimer = 0f
        var lightningRotation = 0f

        val replaceThreshold = (0.60f..0.90f).random(VisualRandom)

        /** Returns true when a lightning strike starts. */
        fun update(dt: Float, motionDt: Float = dt): Boolean {
            var struck = false
            scale += 0.048f * motionDt

            if (isFadingIn) {
                alpha += 0.80f * motionDt
                if (alpha >= 1f) {
                    isFadingIn = false
                }
            } else if (isReplaced) {
                alpha -= 0.16f * motionDt
            } else {
                alpha -= 0.032f * motionDt
            }

            // Always run the lightning code, and just don't render it if
            // we're not in a plasma storm.
            nextLightning -= dt
            if (nextLightning <= 0) {
                struck = true
                lightingTimer = FLASH_TIME
                nextLightning = FLASH_TIME + lightningDelay()
                lightningRotation = (0..3).random(ftl.browser.BrowserRandom) * (PIf / 2f)
            }
            lightingTimer = (lightingTimer - dt).coerceAtLeast(0f)

            alpha = alpha.coerceIn(0f..1f)
            return struck
        }
    }

    // Don't bother serialising the clouds, since they're only visual.
}

private const val FLASH_TIME = 0.25f

// CFPS::OnLoop (0x5ba020) caps the speed factor at 0.5; the cloud update uses 0.05 when it is 0 (0x876eac).
private const val MAX_SPEED_FACTOR = 0.5f
private const val ZERO_FRAME_MOTION = 0.05f

private fun lightningDelay(): Float = (ftl.browser.BrowserRandom.nextInt(201) + 100) / 16f
