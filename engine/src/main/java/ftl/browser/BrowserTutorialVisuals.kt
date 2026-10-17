package ftl.browser

import kotlin.math.abs

/** Cosmetic session state. Native GetAlphaLevel (005bc000) is a triangular
 * pulse on a looping 0.75-second tracker, independent of player pause/RNG. */
class BrowserTutorialVisuals(private val controller: BrowserTutorialController) {
    private var arrowTime = 0f
    private var weaponTime = 0f

    val weaponSlot: Int get() = if (controller.completed) -1 else when (controller.step.id) {
        "COMBAT_1" -> 0
        "COMBAT_5" -> 2
        else -> -1
    }
    val arrowAlpha: Float get() = alpha(arrowTime)
    fun weaponAlpha(slot: Int): Float = if (slot == weaponSlot && slot >= 0) alpha(weaponTime) else 0f

    fun update(seconds: Float) {
        if (controller.completed || !seconds.isFinite() || seconds <= 0f) return
        arrowTime = (arrowTime + seconds) % PERIOD
    }

    // The native shared armament tracker advances only when a box flashes.
    // A requested slot beyond the ship's capacity must not create equipment.
    fun updateWeapon(seconds: Float) {
        if (weaponSlot < 0 || !seconds.isFinite() || seconds <= 0f) return
        weaponTime = (weaponTime + seconds) % PERIOD
    }

    private fun alpha(time: Float) = (1f - abs(time - PERIOD / 2f) / (PERIOD / 2f)).coerceIn(0f, 1f)

    companion object { private const val PERIOD = .75f }
}
