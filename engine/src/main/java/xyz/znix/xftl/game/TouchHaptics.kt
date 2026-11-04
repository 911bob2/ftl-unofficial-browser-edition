package xyz.znix.xftl.game

/**
 * Haptic feedback of the touch layout (TouchMode), played by the Android app (AndroidHost.haptic, its Settings > Haptics);
 * the browser build has none. FTL on iPad gives no haptic feedback at all: its binary links no haptic or vibration API. So
 * this is ours, kept to what was asked for: one tick per power step a system actually gains or loses (a level; for
 * shields a layer, which is two bars). Each kind has its own switch in Settings > Haptics (the app's Haptics.Action).
 */
object TouchHaptics {
    /** A system box's power level changed (a drag, or a flick for all or nothing). */
    const val POWER = 0

    /** The Android app's magnifier came up under a held finger (played by the app itself, not the engine). */
    const val MAGNIFIER = 1

    /**
     * Moves [level] towards [goal] one [change] at a time (true = one up), as the touch HUD always did: a change the game
     * refuses (no free reactor power, ionised, at its cap, already there) ends it. Returns the steps gained or lost in the
     * end: shields move a layer (two bars) per step, so a goal between two layers goes up one and back down, which is none.
     */
    inline fun stepTowards(level: () -> Int, goal: Int, change: (Boolean) -> Unit): Int {
        var up = 0
        var down = 0
        var guard = 32
        while (level() < goal && guard-- > 0) {
            val before = level()
            change(true)
            if (level() == before) break
            up++
        }
        while (level() > goal && guard-- > 0) {
            val before = level()
            change(false)
            if (level() == before) break
            down++
        }
        return kotlin.math.abs(up - down)
    }

    /** Plays [count] ticks of [kind]; nothing for none. */
    @JvmStatic
    fun play(kind: Int, count: Int) {
        if (count > 0) ftl.browser.BrowserEngine.haptic(kind, count)
    }
}
