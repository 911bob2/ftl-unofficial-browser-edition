package xyz.znix.xftl.environment

import ftl.browser.NativeRandom
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

    /** Diagnostics: the number of clouds. */
    val browserCloudCount: Int get() = clouds.size

    /** Whether [setNebula] has made the clouds (SpaceManager +0x33d set with its cloud vector, +0x340). */
    var isSet = false
        private set

    /** Diagnostics: the draws the last [setNebula] and the cloud loops made on the global stream. */
    @JvmField var browserSetDraws = 0
    @JvmField var browserLoopDraws = 0

    /**
     * SpaceManager::SetNebula (1.6.14 0x41fd00, 1.6.9 0x41d360), on the original's global stream: twelve clouds on a
     * 4 x 3 grid from (100, 200), 350 by 230 apart. The middle row is shifted by `random32() % 131 - 65` (drawn when
     * the row starts), and each point of the middle two columns by `random32() % 81 - 40` in y: ten draws. The
     * points are then taken in random order (`random32() % left`), each cloud drawing its scale, fade threshold and
     * lightning timer (four draws each), and 800 OnLoopNebulas follow with the speed factor of the frame
     * ([nebulaSpeedFactor]): every cloud that starts to fade there spawns a successor (three draws), so that part depends
     * on the frame time, as natively. Called where the original applies the environment (WorldManager::
     * ModifyEnvironment in CreateLocation/UpdateLocation, see [ensureSet]).
     */
    fun setNebula(sf: Float = nebulaSpeedFactor(game.renderingDeltaTime)) {
        isSet = true
        clouds.clear()
        val before = drawCount()
        val points = ArrayList<IPoint>()
        for (column in 0 until 4) {
            var shiftX = 0
            for (row in 0 until 3) {
                val shiftY = if (column == 1 || column == 2) NativeRandom.next31() % 81 - 40 else 0
                points += ConstPoint(100 + column * 350 + shiftX, 200 + row * 230 + shiftY)
                shiftX = if (row == 0) NativeRandom.next31() % 131 - 65 else 0
            }
        }
        while (points.isNotEmpty())
            clouds += Cloud(points.removeAt(NativeRandom.next31() % points.size), ionStorm)
        repeat(800) { onLoopNebulas(sf) }
        browserSetDraws = drawCount() - before
    }

    /** [setNebula] unless the clouds are made already (the environment of the beacon's current scene). */
    fun ensureSet() {
        if (!isSet) setNebula()
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        if (!hsBackdrop) return
        browserDrawLog?.add("clouds" to clouds.size)
        for (cloud in clouds) {
            g.pushTransform()
            g.translate(cloud.centre.x.f, cloud.centre.y.f)
            g.scale(cloud.scale, cloud.scale)

            val lightning = cloud.flashing && ionStorm
            val cloudImage = when {
                lightning -> stormFlashImage
                ionStorm -> stormImage
                else -> normalImage
            }

            val filter = pauseShade(game)
            filter.a = cloud.alpha.coerceIn(0f, 1f)
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
        // A scene made without an event applying it (a loaded game, a debug environment) sets itself up now.
        ensureSet()
        // SpaceManager::OnLoop (1.6.14 0x41f3c0) runs OnLoopNebulas once a frame while the game runs.
        val before = drawCount()
        onLoopNebulas(nebulaSpeedFactor(dt))
        browserLoopDraws += drawCount() - before
    }

    /**
     * SpaceManager::OnLoopNebulas (1.6.14 0x41e1e0, 1.6.9 0x41c4d0): each cloud is updated, and one that starts to
     * fade gets a successor inserted in front of it at its point; the loop then moves on to the cloud it was on,
     * which is updated a second time in the same frame. A cloud whose alpha dropped below zero is removed.
     */
    private fun onLoopNebulas(sf: Float) {
        var i = 0
        while (i < clouds.size) {
            val cloud = clouds[i]
            cloud.update(sf)
            if (cloud.alpha < cloud.threshold && !cloud.spawned && cloud.alphaStep < 0f) {
                cloud.spawned = true
                clouds.add(i, Cloud(cloud.centre, ionStorm))
            }
            if (clouds[i].alpha < 0f) {
                clouds.removeAt(i)
                i--
            }
            i++
        }
    }

    /**
     * One cloud (SpaceManager's 0x44-byte NebulaInfo). Its constructor draws the scale (3 or 4), the alpha at which it
     * hands over to a successor (0.6..0.9) and the lightning timer (100..300 speed-factor units).
     */
    private inner class Cloud(val centre: IPoint, val storm: Boolean) {
        var alpha = 0f
        var scale = (NativeRandom.next31() % 2 + 3).toFloat()
        var alphaStep = ALPHA_RISE
        val threshold = f32((NativeRandom.next31() % 301 + 600) / 1000f)
        var timer = (NativeRandom.next31() % 201 + 100).toFloat()
        var spawned = false

        /** The flash AnimationTracker (+0x20, 0.25 s): started by a strike, the timer is drawn again once it ends. */
        var flashRunning = false
        var flashTime = 0f
        val flashing: Boolean get() = flashRunning && flashTime < FLASH_TIME
        var lightningRotation = 0f

        /** 1.6.14 0x41e1e0's per-cloud part (1.6.9 0x41a7d0). */
        fun update(sf: Float) {
            val step = if (sf != 0f) sf else ZERO_FRAME_MOTION
            alpha = f32(f32(step * alphaStep) + alpha)
            scale = f32(f32(step * SCALE_STEP) + scale)
            if (alpha <= 1f) {
                if (alpha < threshold && alphaStep < 0f) alphaStep = FADE_FAST
            } else {
                alphaStep = FADE_SLOW
                alpha = 1f
                if (1f < threshold) alphaStep = FADE_FAST
            }
            timer -= sf
            if (flashRunning) flashTime += sf / SPEED_PER_SECOND
            if (timer < 0f && storm) {
                lightningRotation = (NativeRandom.next31() % 4) * (PIf / 2f)
                flashRunning = true
                flashTime = 0f
                timer = 1_000_000f
                val volume = (NativeRandom.next31() % 101 + 900) / 1000f
                val sound = game.sounds.getSampleOrWarn("lightning${NativeRandom.next31() % 4 + 1}")
                sound?.playAt(volume)
            }
            if (flashRunning && flashTime >= FLASH_TIME) {
                timer = (NativeRandom.next31() % 201 + 100).toFloat()
                flashRunning = false
            }
        }
    }

    private fun drawCount(): Int = NativeRandom.trace?.size ?: 0

    override fun adjustShipPower(ship: Ship, powerAvailableTypes: HashMap<EnergySource, Int>) {
        if (!ionStorm)
            return

        var reactorPower = powerAvailableTypes[ReactorEnergySource] ?: 0
        val toDeduct = reactorPower / 2 // Rounds down
        reactorPower -= toDeduct
        powerAvailableTypes[ReactorEnergySource] = reactorPower
    }

    // The clouds are not saved; a loaded game makes them again (setNebula), so their draws restart there.
}

private const val FLASH_TIME = 0.25f

/** CFPS::OnLoop (1.6.14 0x5ba020, 1.6.9 0x59a4e0): the frame's seconds x (7 x speedLevel + 16), at most 0.5. */
private const val SPEED_PER_SECOND = 16f
private const val MAX_SPEED_FACTOR = 0.5f

/** The cloud update moves by 0.05 on a frame whose speed factor is 0 (0x876eac). */
private const val ZERO_FRAME_MOTION = 0.05f
private const val ALPHA_RISE = 0.05f
private const val SCALE_STEP = 0.003f
private const val FADE_SLOW = -0.002f
private const val FADE_FAST = -0.01f

internal fun nebulaSpeedFactor(seconds: Float): Float = min(f32(seconds.coerceAtLeast(0f) * SPEED_PER_SECOND), MAX_SPEED_FACTOR)

// The original computes in single precision; TeaVM's floats are doubles.
private fun f32(value: Float): Float = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(value))
