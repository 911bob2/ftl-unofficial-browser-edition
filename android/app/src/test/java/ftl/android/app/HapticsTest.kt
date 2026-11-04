package ftl.android.app

import ftl.android.AndroidHost
import ftl.android.app.game.Haptics
import ftl.android.app.game.Haptics.Kind
import ftl.android.app.game.Haptics.Strength
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.znix.xftl.game.TouchHaptics

/**
 * When the touch HUD's power haptic fires (TouchHaptics, called by TouchHud.setPower with the game's own power change) and
 * what the app plays for it (Haptics). On the emulator, logcat's FTL-Haptic lines show the same calls (docs/ANDROID.md).
 */
class HapticsTest {
    /** A system as the game sees it: power changes one level at a time, refused past the free reactor power, the cap or while ionised. */
    private class FakeSystem(var level: Int, val cap: Int, var free: Int, var ionised: Boolean = false) {
        fun change(up: Boolean) {
            if (ionised) return
            if (up && level < cap && free > 0) { level++; free-- }
            if (!up && level > 0) { level--; free++ }
        }
        fun to(goal: Int) = TouchHaptics.stepTowards({ level }, goal.coerceIn(0, cap)) { change(it) }
    }

    private class FakeShields(var level: Int, val cap: Int) {
        fun change(up: Boolean) { level = if (up) (level + 2).coerceAtMost(cap) else (level - 2).coerceAtLeast(0) }
    }

    private val played = ArrayList<String>()

    private fun record() = AndroidHost.set(object : AndroidHost {
        override fun haptic(kind: Int, count: Int) { played += "$kind x$count" }
    })

    @After
    fun clear() = AndroidHost.set(null)

    @Test
    fun oneTickPerLevelActuallyGainedOrLost() {
        val s = FakeSystem(level = 1, cap = 4, free = 5)
        assertEquals(1, s.to(2))
        assertEquals(2, s.to(4))
        assertEquals(3, s.to(1))
        assertEquals(1, s.to(0))
        assertEquals(0, s.level)
    }

    @Test
    fun noTickWhenTheLevelDidNotChange() {
        // At the cap: a drag further up (or the flick for all) asks for nothing.
        assertEquals(0, FakeSystem(level = 3, cap = 3, free = 4).to(5))
        // No free reactor power: the game refuses the first level.
        assertEquals(0, FakeSystem(level = 1, cap = 4, free = 0).to(3))
        // Ionised: neither way.
        assertEquals(0, FakeSystem(level = 2, cap = 4, free = 2, ionised = true).to(4))
        assertEquals(0, FakeSystem(level = 2, cap = 4, free = 2, ionised = true).to(0))
        // Already there: a finger held on the same step.
        assertEquals(0, FakeSystem(level = 2, cap = 4, free = 2).to(2))
        assertEquals(0, FakeSystem(level = 0, cap = 4, free = 2).to(-1))
        // Shields: a layer (two bars) per step. A goal between layers goes up one and back: no level gained.
        val shields = FakeShields(level = 2, cap = 8)
        assertEquals(0, TouchHaptics.stepTowards({ shields.level }, 3) { shields.change(it) })
        assertEquals(2, shields.level)
        assertEquals(1, TouchHaptics.stepTowards({ shields.level }, 4) { shields.change(it) })
        // Part of the way: only what the reactor gives.
        assertEquals(2, FakeSystem(level = 1, cap = 8, free = 2).to(8))
    }

    @Test
    fun theEngineCallsTheHostOnlyForTicks() {
        record()
        TouchHaptics.play(TouchHaptics.POWER, 0)
        TouchHaptics.play(TouchHaptics.POWER, 3)
        assertEquals(listOf("0 x3"), played)
    }

    @Test
    fun whatAStrengthPlaysOnEachKindOfVibrator() {
        assertNull(Haptics.plan(Strength.OFF, Kind.PRIMITIVES, 1))
        assertNull(Haptics.plan(Strength.STRONG, Kind.NONE, 1))
        assertNull(Haptics.plan(Strength.MEDIUM, Kind.PRIMITIVES, 0))
        assertEquals(listOf(0.35f, 0.65f, 1f), listOf(Strength.LIGHT, Strength.MEDIUM, Strength.STRONG).map { Haptics.plan(it, Kind.PRIMITIVES, 1)!!.scale })
        assertEquals(listOf(90, 170, 255), listOf(Strength.LIGHT, Strength.MEDIUM, Strength.STRONG).map { Haptics.plan(it, Kind.AMPLITUDE, 2)!!.amplitude })
        assertEquals(Haptics.Plan(Kind.ON_OFF, 3, pulseMs = 20), Haptics.plan(Strength.LIGHT, Kind.ON_OFF, 3))
        assertEquals(Haptics.plan(Strength.STRONG, Kind.ON_OFF, 3), Haptics.plan(Strength.LIGHT, Kind.ON_OFF, 3))
        assertEquals(16, Haptics.plan(Strength.MEDIUM, Kind.PRIMITIVES, 40)!!.count)
    }

    @Test
    fun eachActionPlaysOnlyWhileItsSwitchIsOn() {
        val all = Haptics.Action.entries.toSet()
        val power = TouchHaptics.POWER
        assertEquals(Haptics.Action.POWER, Haptics.Action.of(power))
        assertEquals("System power", Haptics.Action.POWER.title)
        // On (the default): it plays at every strength but Off.
        for (strength in Strength.entries) {
            val plan = Haptics.planFor(power, 2, strength, Kind.PRIMITIVES, all)
            if (strength == Strength.OFF) assertNull(plan) else assertEquals(Haptics.plan(strength, Kind.PRIMITIVES, 2), plan)
        }
        // Switched off: nothing at any strength or on any vibrator.
        for (strength in Strength.entries) for (kind in Kind.entries) assertNull(Haptics.planFor(power, 2, strength, kind, emptySet()))
        // The sample tick of a chosen strength has no switch; a kind with no switch never plays.
        assertEquals(Haptics.plan(Strength.LIGHT, Kind.AMPLITUDE, 1), Haptics.planFor(Haptics.SAMPLE, 1, Strength.LIGHT, Kind.AMPLITUDE, emptySet()))
        assertNull(Haptics.planFor(Haptics.SAMPLE, 1, Strength.OFF, Kind.AMPLITUDE, all))
        assertNull(Haptics.planFor(99, 1, Strength.STRONG, Kind.PRIMITIVES, all))
        // Every action has its own TouchHaptics kind and saved name.
        assertEquals(Haptics.Action.entries.size, Haptics.Action.entries.map { it.engineKind }.toSet().size)
        assertEquals(Haptics.Action.entries.size, Haptics.Action.entries.map { it.key }.toSet().size)
    }

    @Test
    fun theSettingsRowSaysWhichActionsAreOff() {
        val all = Haptics.Action.entries.toSet()
        assertEquals("Medium", Haptics.summary(Strength.MEDIUM, Kind.PRIMITIVES, all))
        assertEquals("Medium, every action off", Haptics.summary(Strength.MEDIUM, Kind.PRIMITIVES, emptySet()))
        assertEquals("Medium, Magnifier off", Haptics.summary(Strength.MEDIUM, Kind.PRIMITIVES, setOf(Haptics.Action.POWER)))
        assertEquals("Strong, System power off", Haptics.summary(Strength.STRONG, Kind.AMPLITUDE, setOf(Haptics.Action.MAGNIFIER)))
        assertEquals("Off", Haptics.summary(Strength.OFF, Kind.PRIMITIVES, emptySet()))
        assertEquals("On, every action off", Haptics.summary(Strength.STRONG, Kind.ON_OFF, emptySet()))
        assertEquals("Not available", Haptics.summary(Strength.MEDIUM, Kind.NONE, emptySet()))
    }

    @Test
    fun theSettingOffersWhatTheVibratorCanDo() {
        assertEquals(Strength.entries, Haptics.choices(Kind.PRIMITIVES))
        assertEquals(Strength.entries, Haptics.choices(Kind.AMPLITUDE))
        assertEquals(listOf("Off", "On"), Haptics.choices(Kind.ON_OFF).map { Haptics.label(it, Kind.ON_OFF) })
        assertEquals(emptyList<Strength>(), Haptics.choices(Kind.NONE))
        assertEquals("On", Haptics.label(Strength.STRONG, Kind.ON_OFF))
        assertEquals("Not available", Haptics.label(Strength.MEDIUM, Kind.NONE))
        assertEquals("Light", Haptics.label(Strength.LIGHT, Kind.AMPLITUDE))
    }
}
