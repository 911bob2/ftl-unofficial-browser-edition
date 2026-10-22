package xyz.znix.xftl.crew

import xyz.znix.xftl.math.Direction
import kotlin.math.abs

/** How a crewmember's shooting animation aims, from the native CrewAnimation. */
object CrewAim {
    /** The secondary component of an aim: none, or which way it leans (native CrewAnimation +0x34). */
    const val NONE = -1
    const val LEAN_DOWN = 0
    const val LEAN_RIGHT = 1
    const val LEAN_UP = 2
    const val LEAN_LEFT = 3

    class Aim(val direction: Direction, val lean: Int)

    /**
     * CrewAnimation::SetDirection (0x47e550): mostly-horizontal aims (|dy| < |dx|) face right or left, everything
     * else faces up or down; the lean is the other axis' sign.
     */
    fun aim(dx: Float, dy: Float): Aim {
        if (abs(dy) < abs(dx)) {
            val lean = if (dy > 0f) LEAN_DOWN else if (dy < 0f) LEAN_UP else NONE
            return Aim(if (dx <= 0f) Direction.LEFT else Direction.RIGHT, lean)
        }
        val direction = if (dy < 0f) Direction.UP else Direction.DOWN
        val lean = if (dx > 0f) LEAN_RIGHT else if (dx < 0f) LEAN_LEFT else NONE
        return Aim(direction, lean)
    }

    /**
     * CrewAnimation::GetFiringFrame (0x477c30): the frame a laser race's shoot strip is held on - 0 straight,
     * 1/2 the two diagonals (right: down/up, left: down/up, up: right/left, down: left/right).
     */
    fun firingFrame(direction: Direction, lean: Int): Int {
        if (lean == NONE)
            return 0
        return when (direction) {
            Direction.LEFT, Direction.RIGHT -> if (lean == LEAN_UP) 2 else 1
            Direction.UP -> if (lean == LEAN_RIGHT) 1 else 2
            else -> if (lean == LEAN_RIGHT) 2 else 1
        }
    }

    /**
     * The 4-frame throw of RockAnimation (Rock and Crystal, FireShot 0x478870) and MantisAnimation (0x478a00):
     * the projectile leaves on [THROW_FRAME], once per pass of the looping strip.
     */
    const val THROW_FRAME = 3

    /** CrewAnimation shot timer (TimerHelper +0x89c, constructor 0x4853b0): 1000-1299 ms. */
    const val SHOT_TIMER_MIN_MS = 1000
    const val SHOT_TIMER_SPAN_MS = 300

    /** The punch timer (TimerHelper +0x8b0): 500-649 ms; the punch animation lasts twice that. */
    const val PUNCH_TIMER_MIN_MS = 500
    const val PUNCH_TIMER_SPAN_MS = 150

    /** Races whose crew laser is tinted by owner (CrewAnimation::SetupStrips 0x480dc0); all others draw white. */
    val TINTED_RACES = setOf("human", "anaerobic", "engi", "energy", "slug")
}
