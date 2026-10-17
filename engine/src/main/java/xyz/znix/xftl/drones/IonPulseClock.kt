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
        timer = TIMER_LENGTH
    }

    fun save(elem: Element) {
        elem.setAttribute("browserIonTimer", timer.toString())
        elem.setAttribute("browserIonArmed", armed.toString())
        elem.setAttribute("browserIonCharge", charge.toString())
        elem.setAttribute("browserIonGlow", glow.toString())
    }

    fun load(elem: Element) {
        // Older saves only have a single cooldown, which has no equivalent.
        timer = elem.getAttributeValue("browserIonTimer")?.toFloat() ?: TIMER_LENGTH
        armed = elem.getAttributeValue("browserIonArmed")?.toBoolean() ?: false
        charge = elem.getAttributeValue("browserIonCharge")?.toFloat() ?: 0f
        glow = elem.getAttributeValue("browserIonGlow")?.toFloat() ?: 0f
    }

    companion object {
        const val TIMER_LENGTH = 6f
        const val CHARGE_TIME = 1f
        const val GLOW_TIME = 1.25f

        /** From a pulse to the next one while sabotaging throughout. */
        const val PERIOD = TIMER_LENGTH + CHARGE_TIME + GLOW_TIME
    }
}
