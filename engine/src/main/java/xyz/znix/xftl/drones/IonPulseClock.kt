package xyz.znix.xftl.drones

import org.jdom2.Element

/**
 * The ion intruder's pulse cadence, as driven by IonDroneAnimation.
 *
 * The animation (constructor 0x43c040) owns a repeating 6.0 s TimerHelper (ResetMinMax(6000, 6000), 0x5cb160).
 * When it expires (update 0x440490) the "ionBoarderCharge" sound plays and the one second boarder_ion_ion charge
 * animation restarts. Once that animation is done a glow timer counts up, and when it reaches 1.25 s
 * IonDroneAnimation::FireShot (0x43a4c0) fires and restarts the timer (TimerHelper::Start 0x5cb0b0). While the
 * intruder isn't sabotaging (animation state 7) the animation is held at its start and the glow timer at zero.
 *
 * So after a pulse the next one comes [PERIOD] seconds later, and only if the intruder spent the last
 * [CHARGE_TIME] + [GLOW_TIME] seconds sabotaging.
 */
class IonPulseClock {
    var timer: Float = TIMER_LENGTH
        private set
    var armed: Boolean = false
        private set
    var charge: Float = 0f
        private set
    var glow: Float = 0f
        private set

    /** True once the glow has run long enough for the pulse to fire. */
    val isReady: Boolean get() = armed && glow >= GLOW_TIME

    /**
     * Advance the clock.
     *
     * @return True on the update where the timer expires, when the charge sound plays.
     */
    fun tick(dt: Float, sabotaging: Boolean): Boolean {
        var expired = false
        timer -= dt
        if (timer <= 0f) {
            timer += TIMER_LENGTH
            armed = true
            charge = 0f
            glow = 0f
            expired = true
        }

        if (!sabotaging) {
            charge = 0f
            glow = 0f
            return expired
        }

        if (charge >= CHARGE_TIME) {
            glow += dt
        } else {
            charge += dt
        }
        return expired
    }

    /** The pulse fired: disarm and restart the timer (FireShot, 0x43a4c0). */
    fun fired() {
        armed = false
        charge = 0f
        glow = 0f
        if (!hsMode) timer = TIMER_LENGTH
    }

    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/Balance.cpp
    // (controllableIonDroneFix: IonDrone constructor, BoarderPodDrone::OnLoop, CrewDrone::OnLoop and
    // IonDroneAnimation::UpdateShooting hooks).
    /** HS's controllableIonDroneFix clock is in use (a player ion intruder); [timer] is then unused. */
    var hsMode: Boolean = false
        private set
    /** CrewAnimation_Extend::ionTimer.currTime, counting up to [hsDelay] (a looping TimerHelper). */
    var hsTime: Float = 0f
        private set
    var hsDelay: Float = 0f
        private set

    /**
     * IonDrone constructor (a looping ionDelay timer) plus BoarderPodDrone::OnLoop, which holds it at
     * `max(0, ionDelay - ionDelayInitial)` until the pod has delivered the drone, so the first charge starts
     * ionDelayInitial seconds after landing.
     */
    fun hsInit(delay: Float, delayInitial: Float) {
        hsMode = true
        hsDelay = delay
        hsTime = maxOf(0f, delay - delayInitial)
        armed = false
        charge = 0f
        glow = 0f
    }

    /**
     * One frame of the HS clock. UpdateShooting: unless the drone is shooting a door, a done timer (looping, so
     * it restarts at zero) starts the charge animation and plays the sound; the vanilla shot timer is switched
     * off; out of sabotage (or on a door) the vanilla code's restart of the charge animation and glow reset is
     * undone, so the charge restarts from its first frame but stays armed and the glow is kept.
     * CrewDrone::OnLoop: the timer counts only while the charge animation isn't running (between a pulse and the
     * next charge), an order to move resets the glow, and an unpowered drone resets everything.
     *
     * @return True on the update where the charge starts, when the charge sound plays.
     */
    fun hsTick(dt: Float, sabotaging: Boolean, doorTarget: Boolean, moving: Boolean, functional: Boolean): Boolean {
        var started = false
        if (!doorTarget && hsTime > hsDelay) {
            hsTime = 0f
            armed = true
            charge = 0f
            glow = 0f
            started = true
        }
        if (sabotaging && !doorTarget) {
            if (armed) {
                if (charge >= CHARGE_TIME) glow += dt else charge += dt
            }
        } else if (armed) {
            charge = 0f
        }
        if (!armed) hsTime += dt
        if (moving) glow = 0f
        if (!functional) hsReset()
        return started
    }

    /** CrewDrone::OnLoop when the drone is not Functional(): timer, charge animation and glow all reset. */
    fun hsReset() {
        hsTime = 0f
        armed = false
        charge = 0f
        glow = 0f
    }

    fun save(elem: Element) {
        elem.setAttribute("browserIonTimer", timer.toString())
        elem.setAttribute("browserIonArmed", armed.toString())
        elem.setAttribute("browserIonCharge", charge.toString())
        elem.setAttribute("browserIonGlow", glow.toString())
        if (hsMode) {
            elem.setAttribute("hsIonTime", hsTime.toString())
            elem.setAttribute("hsIonDelay", hsDelay.toString())
        }
    }

    fun load(elem: Element) {
        // Older saves only have a single cooldown, which has no equivalent.
        timer = elem.getAttributeValue("browserIonTimer")?.toFloat() ?: TIMER_LENGTH
        armed = elem.getAttributeValue("browserIonArmed")?.toBoolean() ?: false
        charge = elem.getAttributeValue("browserIonCharge")?.toFloat() ?: 0f
        glow = elem.getAttributeValue("browserIonGlow")?.toFloat() ?: 0f
        val hsT = elem.getAttributeValue("hsIonTime")?.toFloat()
        if (hsT != null) {
            hsMode = true
            hsTime = hsT
            hsDelay = elem.getAttributeValue("hsIonDelay")?.toFloat() ?: hsDelay
        }
    }

    companion object {
        const val TIMER_LENGTH = 6f
        const val CHARGE_TIME = 1f
        const val GLOW_TIME = 1.25f

        /** From a pulse to the next one while sabotaging throughout. */
        const val PERIOD = TIMER_LENGTH + CHARGE_TIME + GLOW_TIME
    }
}
