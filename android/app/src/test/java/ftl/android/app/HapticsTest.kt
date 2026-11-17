package ftl.android.app

import ftl.android.AndroidHost
import ftl.android.app.game.Haptics
import ftl.android.app.game.Haptics.Group
import ftl.android.app.game.Haptics.Kind
import ftl.android.app.game.Haptics.Moment
import ftl.android.app.game.Haptics.Pattern
import ftl.android.app.game.Haptics.Preset
import ftl.android.app.game.Haptics.Settings
import ftl.android.app.game.Haptics.Strength
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.znix.xftl.game.GameHaptics
import xyz.znix.xftl.game.TouchHaptics

/**
 * When the touch HUD's power haptic fires (TouchHaptics, called by TouchHud.setPower with the game's own power change) and
 * what the app plays for it (Haptics); and the game's moments (GameHaptics): the catalogue, what each pattern plays on each
 * kind of vibrator, the presets, the settings as stored, and the rule for moments that come together. On the emulator,
 * logcat's FTL-Haptic lines show the same calls (docs/ANDROID.md).
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

    private val strengths = listOf(Strength.LIGHT, Strength.MEDIUM, Strength.STRONG)
    private fun touchOnly(master: Strength) = Settings(master, Preset.TOUCH.levels)
    private fun full(master: Strength = Strength.STRONG) = Settings(master, Preset.FULL.levels)
    private fun tick(master: Strength, kind: Kind, count: Int) = Haptics.planFor(TouchHaptics.POWER, count, touchOnly(master), kind, kind != Kind.ON_OFF)

    // ---- the touch ticks play what they always played ----

    @Test
    fun whatAStrengthPlaysOnEachKindOfVibrator() {
        assertNull(tick(Strength.OFF, Kind.PRIMITIVES, 1))
        assertNull(tick(Strength.STRONG, Kind.NONE, 1))
        assertNull(tick(Strength.MEDIUM, Kind.PRIMITIVES, 0))
        assertEquals(listOf(0.35f, 0.65f, 1f), strengths.map { tick(it, Kind.PRIMITIVES, 1)!!.scale })
        assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 3, scale = 0.65f, gapMs = 45), tick(Strength.MEDIUM, Kind.PRIMITIVES, 3))
        // A pulse and a 45 ms gap per tick: 10, 15 or 20 ms at amplitude 90, 170 or 255.
        assertEquals(listOf(listOf(0L, 10L, 45L, 10L), listOf(0L, 15L, 45L, 15L), listOf(0L, 20L, 45L, 20L)), strengths.map { tick(it, Kind.AMPLITUDE, 2)!!.timings })
        assertEquals(listOf(listOf(0, 90, 0, 90), listOf(0, 170, 0, 170), listOf(0, 255, 0, 255)), strengths.map { tick(it, Kind.AMPLITUDE, 2)!!.amplitudes })
        assertEquals(Haptics.Plan(Kind.ON_OFF, timings = listOf(0L, 20L, 45L, 20L, 45L, 20L)), tick(Strength.LIGHT, Kind.ON_OFF, 3))
        assertEquals(tick(Strength.STRONG, Kind.ON_OFF, 3), tick(Strength.LIGHT, Kind.ON_OFF, 3))
        assertEquals(16, tick(Strength.MEDIUM, Kind.PRIMITIVES, 40)!!.clicks)
        // The sample tick of a chosen strength is no moment, so nothing switched off holds it back; an unknown number never plays.
        val nothing = Settings(Strength.LIGHT, Moment.entries.associateWith { Strength.OFF })
        assertEquals(tick(Strength.LIGHT, Kind.AMPLITUDE, 1), Haptics.planFor(Haptics.SAMPLE, 1, nothing, Kind.AMPLITUDE, true))
        assertNull(Haptics.planFor(Haptics.SAMPLE, 1, nothing.copy(master = Strength.OFF), Kind.AMPLITUDE, true))
        assertNull(Haptics.planFor(99, 1, full(), Kind.PRIMITIVES, true))
    }

    // ---- the catalogue ----

    @Test
    fun everyMomentTheEngineReportsIsInTheCatalogueOnce() {
        assertEquals(Moment.entries.size, Moment.entries.map { it.id }.toSet().size)
        assertEquals(Moment.entries.size, Moment.entries.map { it.key }.toSet().size)
        assertEquals(Moment.entries.size, Moment.entries.map { it.title }.toSet().size)
        val engine = GameHaptics::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertTrue(engine.toString(), engine.size >= 50)
        for ((name, id) in engine) assertNotNull("GameHaptics.$name has no moment", Moment.of(id))
        assertEquals(Moment.POWER, Moment.of(TouchHaptics.POWER))
        assertEquals("System power", Moment.POWER.title)
        assertEquals(Group.entries.toSet(), Moment.entries.map { it.group }.toSet())
        // Only the two touch ticks are touch controls; they are the engine's TouchHaptics kinds.
        assertEquals(setOf(Moment.POWER, Moment.MAGNIFIER), Moment.entries.filter { it.group == Group.TOUCH }.toSet())
        for (m in Moment.entries) assertTrue(m.key, m.full != Strength.OFF && m.light.ordinal <= m.full.ordinal)
    }

    // ---- the settings ----

    @Test
    fun anUpdateAddsNoVibration() {
        // A first start, and a file from before the moments had strengths: what the app did then.
        assertEquals(Settings.DEFAULT, Settings.fromStored(emptyMap<String, Any>()))
        assertEquals(Strength.MEDIUM, Settings.DEFAULT.master)
        assertEquals(Preset.TOUCH, Settings.DEFAULT.preset)
        assertFalse(Settings.DEFAULT.gameMoments)
        val old = Settings.fromStored(mapOf("strength" to "LIGHT", "action-power" to true, "action-magnifier" to false))
        assertEquals(Strength.LIGHT, old.master)
        assertEquals(Strength.STRONG, old.level(Moment.POWER))
        assertEquals(Strength.OFF, old.level(Moment.MAGNIFIER))
        assertTrue(Moment.entries.filter { it.group != Group.TOUCH }.all { old.level(it) == Strength.OFF })
        assertNull(old.preset)
        assertFalse(old.gameMoments)
        assertEquals(Strength.OFF, Settings.fromStored(mapOf("strength" to "OFF")).master)
        // The ticks are what they were at each old strength (the numbers are in whatAStrengthPlaysOnEachKindOfVibrator).
        for (s in strengths) assertEquals(tick(s, Kind.PRIMITIVES, 2), Haptics.planFor(TouchHaptics.POWER, 2, Settings.fromStored(mapOf("strength" to s.name)), Kind.PRIMITIVES, true))
        // And no game moment plays with them.
        for (m in Moment.entries.filter { it.group != Group.TOUCH }) for (k in Kind.entries) assertNull(m.key, Haptics.planFor(m.id, 3, Settings.DEFAULT, k, true))
    }

    @Test
    fun theSettingsComeBackAsTheyWereStored() {
        val custom = full(Strength.LIGHT).with(Moment.HULL_HIT, Strength.OFF).with(Group.ENEMY, Strength.STRONG).with(Moment.JUMP, Strength.LIGHT)
        for (s in Preset.entries.map { Settings(Strength.MEDIUM, it.levels) } + custom + Settings.DEFAULT.copy(master = Strength.OFF)) {
            assertEquals(s, Settings.fromStored(s.stored()))
            assertEquals(s.preset, Settings.fromStored(s.stored()).preset)
        }
        // Everything is a string under its own name; the overall strength keeps the name it always had.
        val stored = custom.stored()
        assertEquals("LIGHT", stored["strength"])
        assertEquals("OFF", stored["level-hull-hit"])
        assertEquals(Moment.entries.size + 2, stored.size)
        // A value that does not read, and a moment a later version adds, are as in Touch controls only.
        val odd = Settings.fromStored(stored - "level-jump" + ("level-hull-hit" to "LOUD"))
        assertEquals(Strength.OFF, odd.level(Moment.JUMP))
        assertEquals(Strength.OFF, odd.level(Moment.HULL_HIT))
        assertEquals(Strength.STRONG, odd.level(Moment.ENEMY_HULL_HIT))
    }

    @Test
    fun thePresetsAndCustom() {
        val now = Settings(Strength.MEDIUM, Preset.TOUCH.levels)
        assertEquals(setOf(Moment.POWER, Moment.MAGNIFIER), Moment.entries.filter { Preset.TOUCH.level(it) != Strength.OFF }.toSet())
        assertTrue(Moment.entries.all { Preset.FULL.level(it) == it.full })
        assertTrue(Moment.entries.all { Preset.COMBAT.level(it) == if (it.group == Group.TOUCH || it.group == Group.TRAVEL) Strength.OFF else it.full })
        // Light: the touch ticks as they are, and a few of the game's moments, none stronger than in Everything.
        val light = Moment.entries.filter { Preset.LIGHT.level(it) != Strength.OFF }
        assertTrue(light.containsAll(listOf(Moment.POWER, Moment.HULL_HIT, Moment.ENEMY_DESTROYED, Moment.JUMP)))
        assertTrue(light.size in 10..Moment.entries.size / 2)
        // The four are different, so each reads as itself.
        for (p in Preset.entries) assertEquals(p, now.with(p).preset)
        // One row changed: Custom. Changed back: the preset again.
        val changed = now.with(Preset.FULL).with(Moment.FIRE_FLAK, Strength.STRONG)
        assertNull(changed.preset)
        assertEquals(Preset.FULL, changed.with(Moment.FIRE_FLAK, Moment.FIRE_FLAK.full).preset)
        // The overall strength is not part of a preset.
        assertEquals(Preset.COMBAT, now.with(Preset.COMBAT).copy(master = Strength.LIGHT).preset)
        // A group's strength sets all its moments; a group whose moments differ has none.
        val weapons = changed.with(Group.WEAPONS, Strength.LIGHT)
        assertEquals(Strength.LIGHT, weapons.level(Group.WEAPONS))
        assertTrue(Moment.entries.filter { it.group == Group.WEAPONS }.all { weapons.level(it) == Strength.LIGHT })
        assertEquals(changed.level(Moment.HULL_HIT), weapons.level(Moment.HULL_HIT))
        assertNull(changed.level(Group.WEAPONS))
        assertEquals(Strength.OFF, now.level(Group.SHIP))
        // The engine is asked for moments only when one of the game's can play.
        assertFalse(now.gameMoments)
        assertTrue(now.with(Moment.JUMP, Strength.LIGHT).gameMoments)
        assertFalse(now.with(Preset.FULL).copy(master = Strength.OFF).gameMoments)
    }

    @Test
    fun theSettingsRowSaysTheStrengthAndWhatVibrates() {
        assertEquals("Medium · Touch controls only", Haptics.summary(Settings.DEFAULT, Kind.PRIMITIVES))
        assertEquals("Strong · Everything", Haptics.summary(full(), Kind.AMPLITUDE))
        assertEquals("Light · Custom", Haptics.summary(full(Strength.LIGHT).with(Moment.JUMP, Strength.OFF), Kind.PRIMITIVES))
        assertEquals("Off", Haptics.summary(full(Strength.OFF), Kind.PRIMITIVES))
        assertEquals("On · Combat", Haptics.summary(Settings(Strength.STRONG, Preset.COMBAT.levels), Kind.ON_OFF))
        assertEquals("Not available", Haptics.summary(Settings.DEFAULT, Kind.NONE))
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

    // ---- from a moment to what the vibrator plays ----

    @Test
    fun offSendsNothing() {
        for (m in Moment.entries) for (k in Kind.entries) for (count in 0..3) {
            assertNull(Haptics.planFor(m.id, count, full(Strength.OFF), k, true))
            assertNull(Haptics.planFor(m.id, count, full().with(m, Strength.OFF), k, true))
            assertNull(Haptics.planFor(m.id, count, full(), Kind.NONE, true))
        }
        // And everything in the list plays something when it is on, on every vibrator that can.
        for (m in Moment.entries) for (k in listOf(Kind.PRIMITIVES, Kind.AMPLITUDE, Kind.ON_OFF)) for (amp in listOf(true, false)) {
            val plan = Haptics.planFor(m.id, 1, full(Strength.LIGHT), k, amp && k != Kind.ON_OFF)
            assertNotNull("${m.key} on $k", plan)
            assertTrue("${m.key} on $k: $plan", plan!!.durationMs in 8..800)
        }
    }

    @Test
    fun eachPatternOnEachKindOfVibrator() {
        fun plan(p: Pattern, kind: Kind, amp: Boolean = kind != Kind.ON_OFF, master: Strength = Strength.STRONG, level: Strength = Strength.STRONG, count: Int = 1) =
            Haptics.plan(p, master, level, kind, amp, count)!!
        for (p in Pattern.entries) {
            // With amplitudes: one per step, in the vibrator's range, the peak at this strength's amplitude.
            val wave = plan(p, Kind.AMPLITUDE)
            assertEquals(Kind.AMPLITUDE, wave.kind)
            assertEquals(wave.timings.size, wave.amplitudes.size)
            assertTrue("$p $wave", wave.amplitudes.all { it in 0..255 } && wave.amplitudes.max() >= 150 && wave.timings.all { it >= 0 })
            if (p != Pattern.BUZZ && p != Pattern.RUMBLE) assertEquals("$p", 255, wave.amplitudes.max())
            // Weaker settings play the same shape more softly.
            val soft = plan(p, Kind.AMPLITUDE, master = Strength.MEDIUM, level = Strength.LIGHT)
            assertEquals(wave.timings.size, soft.timings.size)
            assertTrue("$p $soft", soft.amplitudes.max() in 1 until wave.amplitudes.max() / 2)
            if (p != Pattern.BUZZ && p != Pattern.RUMBLE) assertEquals("$p", Haptics.amplitude(0.65f * 0.5f), soft.amplitudes.max())
            // On or off only: off and on in turn, no amplitudes, the same at every strength.
            val onOff = plan(p, Kind.ON_OFF)
            assertEquals(Kind.ON_OFF, onOff.kind)
            assertTrue("$p $onOff", onOff.amplitudes.isEmpty() && onOff.timings.first() == 0L && onOff.timings.size % 2 == 0)
            assertEquals(onOff, plan(p, Kind.ON_OFF, master = Strength.LIGHT, level = Strength.LIGHT))
            // Click primitives without amplitudes: clicks for the clicks, the on/off rhythm for the rest.
            val clicksOnly = plan(p, Kind.PRIMITIVES, amp = false)
            if (p == Pattern.TICK || p == Pattern.DOUBLE || p == Pattern.RISE) assertEquals(if (p == Pattern.TICK) 1 else 2, clicksOnly.clicks)
            else assertEquals(onOff, clicksOnly)
        }
        // Click primitives: a tick and a double are clicks at the strength's scale; the longer ones are waveforms.
        assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 2, scale = 0.65f * 0.75f, gapMs = 90), plan(Pattern.DOUBLE, Kind.PRIMITIVES, master = Strength.MEDIUM, level = Strength.MEDIUM))
        assertEquals(plan(Pattern.LONG_SWELL, Kind.AMPLITUDE), plan(Pattern.LONG_SWELL, Kind.PRIMITIVES))
        // RISE: the second click firmer than the first.
        plan(Pattern.RISE, Kind.PRIMITIVES).let { assertTrue("$it", it.amplitudes[1] < it.amplitudes[3]) }
        // The shapes: a knock is one step, a heavy one rings off, a swell only rises, a long swell rises to a peak and falls.
        assertEquals(1, plan(Pattern.THUD, Kind.AMPLITUDE).timings.size)
        plan(Pattern.HEAVY, Kind.AMPLITUDE).amplitudes.let { assertEquals(it.sortedDescending(), it) }
        plan(Pattern.SWELL, Kind.AMPLITUDE).amplitudes.let { assertEquals(it.sorted(), it) }
        plan(Pattern.LONG_SWELL, Kind.AMPLITUDE).amplitudes.let { a ->
            val peak = a.indexOf(a.max())
            assertTrue("$a", peak in 1 until a.lastIndex && a.subList(0, peak + 1) == a.subList(0, peak + 1).sorted() && a.subList(peak, a.size) == a.subList(peak, a.size).sortedDescending())
        }
        assertTrue(plan(Pattern.BUZZ, Kind.AMPLITUDE).amplitudes.count { it == 0 } >= 5)
        // Longer and heavier patterns last longer.
        val ms = Pattern.entries.associateWith { plan(it, Kind.AMPLITUDE).durationMs }
        assertTrue(ms.toString(), ms[Pattern.TICK]!! < ms[Pattern.THUD]!! && ms[Pattern.THUD]!! < ms[Pattern.HEAVY]!! && ms[Pattern.HEAVY]!! < ms[Pattern.RUMBLE]!! && ms[Pattern.RUMBLE]!! < ms[Pattern.LONG_SWELL]!!)
        assertTrue(ms.toString(), ms.values.all { it <= 700 })
    }

    @Test
    fun moreHullDamageIsAFirmerLongerKnock() {
        fun hit(points: Int, master: Strength = Strength.MEDIUM) = Haptics.planFor(GameHaptics.HULL_HIT, points, full(master), Kind.AMPLITUDE, true)!!
        assertEquals(Pattern.THUD, Moment.HULL_HIT.pattern)
        val one = hit(1)
        val three = hit(3)
        assertTrue("$one $three", three.amplitudes[0] > one.amplitudes[0] && three.timings[0] > one.timings[0])
        // Never past the vibrator's most, never longer than a knock.
        assertEquals(hit(6), hit(40))
        assertEquals(255, hit(40, Strength.STRONG).amplitudes[0])
        assertTrue(hit(40).timings[0] <= 80)
    }

    // ---- moments that come together ----

    @Test
    fun aVolleyIsItsKnocksNotOneBuzzAndNothingComesLate() {
        val thud = Haptics.plan(Pattern.THUD, Strength.STRONG, Strength.STRONG, Kind.AMPLITUDE, true, 1)!!
        val p = Haptics.priority(Pattern.THUD, Strength.STRONG)
        // Twelve hits 20 ms apart: one knock at a time with a pause after each, so the motor rests more than it runs.
        val gate = Haptics.Gate()
        val starts = (0 until 12).map { it * 20L }.filter { gate.admit(it, thud.durationMs, p) }
        assertEquals(listOf(0L, 100L, 200L), starts)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= thud.durationMs + Haptics.GATE_GAP_MS })
        // What was refused is gone: nothing plays after the volley that was not asked for then.
        assertTrue(gate.admit(400, thud.durationMs, p))
    }

    @Test
    fun theHeavierMomentWins() {
        val gate = Haptics.Gate()
        val tick = Haptics.priority(Pattern.TICK, Strength.STRONG)
        val thud = Haptics.priority(Pattern.THUD, Strength.LIGHT)
        val boom = Haptics.priority(Pattern.LONG_SWELL, Strength.MEDIUM)
        assertTrue(tick < thud && thud < boom)
        assertTrue(Haptics.priority(Pattern.THUD, Strength.LIGHT) < Haptics.priority(Pattern.THUD, Strength.STRONG))
        // Your own ship's moments count heavier: a hull hit is felt through your own flak and beam, a dying crew member
        // through the enemy's hull knocks; nothing but your own ship's end outranks the enemy's explosion.
        assertTrue(Haptics.priority(Moment.HULL_HIT, Strength.LIGHT) > Haptics.priority(Moment.FIRE_FLAK, Strength.STRONG))
        assertTrue(Haptics.priority(Moment.HULL_HIT, Strength.LIGHT) > Haptics.priority(Moment.FIRE_BEAM, Strength.STRONG))
        assertTrue(Haptics.priority(Moment.CREW_DIED, Strength.LIGHT) > Haptics.priority(Moment.ENEMY_HULL_HIT, Strength.STRONG))
        assertTrue(Haptics.priority(Moment.SHIP_DESTROYED, Strength.LIGHT) > Haptics.priority(Moment.ENEMY_DESTROYED, Strength.STRONG))
        assertTrue(Haptics.priority(Moment.ENEMY_DESTROYED, Strength.MEDIUM) > Haptics.priority(Moment.FIRE_LASER, Strength.STRONG))
        assertTrue(gate.admit(0, 20, tick))
        // A knock takes over from a tick at once; the explosion from the knock.
        assertTrue(gate.admit(5, 35, thud))
        assertTrue(gate.admit(10, 640, boom))
        // While the explosion plays, lighter moments are dropped, and an equal one too.
        assertFalse(gate.admit(100, 20, tick))
        assertFalse(gate.admit(300, 35, thud))
        assertFalse(gate.admit(600, 640, boom))
        assertFalse(gate.admit(10 + 640 + Haptics.GATE_GAP_MS - 1, 20, tick))
        // After it and its pause, anything plays.
        assertTrue(gate.admit(10 + 640 + Haptics.GATE_GAP_MS, 20, tick))
        // A warning or an ending behind the hit that caused it waits for the hit to end (and its pause), once; behind
        // something that lasts longer than half a second more it is dropped; nothing else waits.
        assertTrue(Haptics.patient(Moment.WARN_HULL.pattern) && Haptics.patient(Moment.ENEMY_DESTROYED.pattern) && Haptics.patient(Moment.INTRUDERS.pattern))
        assertFalse(Haptics.patient(Moment.HULL_HIT.pattern) || Haptics.patient(Moment.FIRE_LASER.pattern) || Haptics.patient(Moment.POWER.pattern))
        val later = Haptics.Gate()
        val hit = Haptics.priority(Moment.HULL_HIT, Strength.STRONG)
        val warning = Haptics.priority(Moment.WARN_HULL, Strength.STRONG)
        assertTrue(warning < hit)
        assertEquals(0L, later.admitOrWait(5000, 35, hit, Haptics.PATIENCE_MS))
        assertEquals(35 + Haptics.GATE_GAP_MS - 2, later.admitOrWait(5002, 180, warning, Haptics.PATIENCE_MS))
        assertTrue("its turn has come", later.admit(5000 + 35 + Haptics.GATE_GAP_MS, 180, warning))
        assertEquals(0L, later.admitOrWait(6000, 640, boom, Haptics.PATIENCE_MS))
        assertEquals(-1L, later.admitOrWait(6010, 180, Haptics.priority(Pattern.DOUBLE, Strength.LIGHT), Haptics.PATIENCE_MS))
        assertEquals(60 + Haptics.GATE_GAP_MS, later.admitOrWait(6580, 180, Haptics.priority(Pattern.DOUBLE, Strength.LIGHT), Haptics.PATIENCE_MS))
        // A touch tick always plays, and no game moment cuts it off.
        gate.take(1000, 20)
        assertFalse(gate.admit(1010, 640, boom))
        assertTrue(gate.admit(1020, 640, boom))
        gate.take(1100, 20)
        assertFalse(gate.admit(1110, 35, thud))
    }
}
