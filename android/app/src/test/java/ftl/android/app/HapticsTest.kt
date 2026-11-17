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
 * what the app plays for it (Haptics); and the game's moments (GameHaptics): the catalogue of moments and of kinds of
 * vibration, what each kind plays on each vibrator, a moment's own strength and kind, the presets, the group shortcuts, the
 * settings as stored (and as 2.2.0 stored and reads them), and the rule for moments that come together. On the emulator,
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
        val kinds = custom.with(Moment.FIRE_LASER, Pattern.LONG).with(Moment.HULL_HIT, Pattern.DOUBLE_KNOCK).with(Group.TRAVEL, Pattern.HEARTBEAT).with(Moment.POWER, Pattern.SOFT)
        for (s in Preset.entries.map { Settings(Strength.MEDIUM, it.levels) } + custom + kinds + Settings.DEFAULT.copy(master = Strength.OFF)) {
            assertEquals(s, Settings.fromStored(s.stored()))
            assertEquals(s.preset, Settings.fromStored(s.stored()).preset)
        }
        // Everything is a string under its own name; the overall strength keeps the name it always had.
        val stored = custom.stored()
        assertEquals("LIGHT", stored["strength"])
        assertEquals("OFF", stored["level-hull-hit"])
        assertEquals(Moment.entries.size + 2, stored.size)
        // A kind is stored only for a moment that does not play its own, under the kind's name.
        val withKinds = kinds.stored()
        assertEquals(stored, withKinds.filterKeys { !it.startsWith("kind-") })
        assertEquals("long", withKinds["kind-fire-laser"])
        assertEquals("double-knock", withKinds["kind-hull-hit"])
        assertEquals("soft", withKinds["kind-power"])
        assertEquals(3 + Moment.entries.count { it.group == Group.TRAVEL }, withKinds.keys.count { it.startsWith("kind-") })
        // What the file must no longer hold: the kinds of the moments that play their own (one put back, too).
        assertEquals(Moment.entries.map { "kind-${it.key}" }.toSet(), withKinds.keys.filter { it.startsWith("kind-") }.toSet() + kinds.unstored())
        assertTrue(kinds.unstored().none { it in withKinds })
        assertTrue("kind-fire-laser" in kinds.with(Moment.FIRE_LASER, null).unstored())
        assertEquals(Moment.entries.size, custom.unstored().size)
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
        // A preset has every moment's own kind: another kind for one moment is Custom too, its own kind again the preset,
        // and choosing a preset puts strengths and kinds back.
        for (p in Preset.entries) assertTrue(Moment.entries.all { now.with(p).kind(it) == it.pattern && !now.with(p).changed(it) })
        val kind = now.with(Preset.FULL).with(Moment.FIRE_FLAK, Pattern.LONG)
        assertNull(kind.preset)
        assertEquals("Strong · Custom", Haptics.summary(kind.copy(master = Strength.STRONG), Kind.PRIMITIVES))
        assertEquals(Preset.FULL, kind.with(Moment.FIRE_FLAK, Moment.FIRE_FLAK.pattern).preset)
        assertEquals(Preset.FULL, kind.with(Moment.FIRE_FLAK, null).preset)
        assertEquals(now.with(Preset.FULL), kind.with(Moment.FIRE_FLAK, null))
        // A kind for a moment that is off is Custom as well: it is what the moment plays once it is on.
        assertNull(now.with(Moment.JUMP, Pattern.FADE).preset)
        for (p in Preset.entries) assertEquals(now.with(p), kind.with(Moment.HULL_HIT, Strength.OFF).with(Group.CREW, Pattern.TRIPLE).with(p))
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
            // Whatever kind was picked for it.
            assertNull(Haptics.planFor(m.id, count, full().with(m, Pattern.LONG).with(m, Strength.OFF), k, true))
            assertNull(Haptics.turn(m, count, full(Strength.OFF).with(m, Pattern.LONG), k, true))
        }
        // And everything in the list plays something when it is on, on every vibrator that can.
        for (m in Moment.entries) for (k in listOf(Kind.PRIMITIVES, Kind.AMPLITUDE, Kind.ON_OFF)) for (amp in listOf(true, false)) {
            val plan = Haptics.planFor(m.id, 1, full(Strength.LIGHT), k, amp && k != Kind.ON_OFF)
            assertNotNull("${m.key} on $k", plan)
            assertTrue("${m.key} on $k: $plan", plan!!.durationMs in 8..800)
        }
    }

    // ---- the kinds of vibration ----

    /** The vibrators there are: click primitives with and without amplitudes, amplitudes, on or off only. */
    private val devices = listOf(Kind.PRIMITIVES to true, Kind.PRIMITIVES to false, Kind.AMPLITUDE to true, Kind.ON_OFF to false)

    private fun planOf(p: Pattern, kind: Kind, amp: Boolean = kind != Kind.ON_OFF, master: Strength = Strength.STRONG, level: Strength = Strength.STRONG, count: Int = 1) =
        Haptics.plan(p, master, level, kind, amp, count)!!

    /** The kinds 2.2.0 had (it had no choice of them: each moment played its own). */
    private val before = listOf(Pattern.TICK, Pattern.RISE, Pattern.DOUBLE, Pattern.THUD, Pattern.BUZZ, Pattern.RUMBLE, Pattern.HEAVY, Pattern.SWELL, Pattern.LONG_SWELL)

    /** What a hand tells apart: how many pulses, how long the motor runs, from first to last, how hard at most, and which way it goes. */
    private data class Feel(val pulses: Int, val onMs: Long, val spanMs: Long, val peak: Int, val shape: String)

    private fun feel(plan: Haptics.Plan): Feel {
        // On or off only: off and on in turn, starting off, each on at full.
        val steps = plan.timings.mapIndexed { i, ms -> ms to if (plan.amplitudes.isEmpty()) (if (i % 2 == 1) 255 else 0) else plan.amplitudes[i] }.filter { it.first > 0 }
        val on = steps.filter { it.second > 0 }.map { it.second }
        val pulses = steps.indices.count { steps[it].second > 0 && (it == 0 || steps[it - 1].second == 0) }
        val top = on.indexOf(on.max())
        val shape = when {
            on.distinct().size == 1 -> "flat"
            on == on.sorted() -> "rising"
            on == on.sortedDescending() -> "falling"
            on.subList(0, top + 1) == on.subList(0, top + 1).sorted() && on.subList(top, on.size) == on.subList(top, on.size).sortedDescending() -> "rise and fall"
            else -> "in waves"
        }
        return Feel(pulses, steps.filter { it.second > 0 }.sumOf { it.first }, plan.durationMs, on.max(), shape)
    }

    private fun apart(a: Feel, b: Feel): Boolean {
        fun far(x: Number, y: Number) = maxOf(x.toDouble(), y.toDouble()) >= 1.4 * minOf(x.toDouble(), y.toDouble())
        return a.pulses != b.pulses || a.shape != b.shape || far(a.onMs, b.onMs) || far(a.spanMs, b.spanMs) || far(a.peak, b.peak)
    }

    @Test
    fun theCatalogueOfKinds() {
        assertTrue(Pattern.entries.size in 12..16)
        // The names they are saved under: never change one.
        assertEquals(listOf("tick", "double", "triple", "soft", "rise", "thud", "double-knock", "heavy", "heartbeat", "buzz", "hum", "long", "rumble", "swell", "fade", "long-swell"),
            Pattern.entries.map { it.key })
        for (name in listOf<(Pattern) -> String>({ it.key }, { it.title }, { it.text })) assertEquals(Pattern.entries.size, Pattern.entries.map(name).toSet().size)
        for (p in Pattern.entries) {
            assertEquals(p, Pattern.of(p.key))
            assertTrue(p.title, p.title[0].isUpperCase() && p.text[0].isLowerCase() && !p.text.endsWith("."))
        }
        assertNull(Pattern.of("sparkle"))
        assertNull(Pattern.of(null))
        // The kinds the moments had stay, and every moment's own kind is still one of them: nothing new plays unasked.
        assertTrue(Pattern.entries.containsAll(before))
        assertEquals(before.toSet(), Moment.entries.map { it.pattern }.toSet())
        // Every kind plays something on every vibrator at every strength, none longer than 0.7 s; nothing on none.
        for (p in Pattern.entries) {
            for ((kind, amp) in devices) for (master in strengths) for (level in strengths) {
                val plan = Haptics.plan(p, master, level, kind, amp, 1)
                assertNotNull("$p on $kind", plan)
                assertTrue("$p on $kind: $plan", plan!!.durationMs in 8..700)
                if (plan.clicks > 0) assertTrue("$p on $kind: $plan", kind == Kind.PRIMITIVES && plan.scale in 0.15f..1f && plan.timings.isEmpty())
                else assertTrue("$p on $kind: $plan", plan.timings.all { it >= 0 } && (plan.amplitudes.isEmpty() || plan.amplitudes.size == plan.timings.size && plan.amplitudes.all { it in 0..255 } && plan.amplitudes.max() > 0))
            }
            assertNull(Haptics.plan(p, Strength.STRONG, Strength.STRONG, Kind.NONE, true, 1))
            assertNull(Haptics.plan(p, Strength.OFF, Strength.STRONG, Kind.AMPLITUDE, true, 1))
            assertNull(Haptics.plan(p, Strength.STRONG, Strength.OFF, Kind.AMPLITUDE, true, 1))
            assertNull(Haptics.plan(p, Strength.STRONG, Strength.STRONG, Kind.AMPLITUDE, true, 0))
        }
    }

    @Test
    fun everyKindFeelsDifferentWhereTheMotorTakesAmplitudes() {
        for ((master, level) in listOf(Strength.STRONG to Strength.STRONG, Strength.MEDIUM to Strength.MEDIUM, Strength.LIGHT to Strength.STRONG)) {
            val feels = Pattern.entries.associateWith { feel(planOf(it, Kind.AMPLITUDE, master = master, level = level)) }
            for (a in Pattern.entries) for (b in Pattern.entries) if (a.ordinal < b.ordinal) assertTrue("$a ${feels[a]} and $b ${feels[b]} at $master/$level", apart(feels[a]!!, feels[b]!!))
        }
        val feels = Pattern.entries.associateWith { feel(planOf(it, Kind.AMPLITUDE)) }
        // What the names promise.
        assertEquals(listOf(1, 2, 3), listOf(Pattern.TICK, Pattern.DOUBLE, Pattern.TRIPLE).map { feels[it]!!.pulses })
        assertEquals(listOf(1, 2), listOf(Pattern.THUD, Pattern.DOUBLE_KNOCK).map { feels[it]!!.pulses })
        assertTrue(feels[Pattern.DOUBLE_KNOCK]!!.onMs == 2 * feels[Pattern.THUD]!!.onMs && feels[Pattern.THUD]!!.onMs > feels[Pattern.TICK]!!.onMs)
        assertTrue(feels[Pattern.SOFT].toString(), feels[Pattern.SOFT]!!.pulses == 1 && feels[Pattern.SOFT]!!.peak < 128)
        assertEquals("rising", feels[Pattern.RISE]!!.shape)
        assertEquals(Feel(4, 160, 560, 255, "in waves"), feels[Pattern.HEARTBEAT])
        assertEquals("falling", feels[Pattern.HEAVY]!!.shape)
        assertEquals("falling", feels[Pattern.FADE]!!.shape)
        assertTrue(feels[Pattern.FADE]!!.onMs > 2 * feels[Pattern.HEAVY]!!.onMs)
        assertEquals("rising", feels[Pattern.SWELL]!!.shape)
        assertEquals("rise and fall", feels[Pattern.LONG_SWELL]!!.shape)
        assertEquals("in waves", feels[Pattern.RUMBLE]!!.shape)
        assertTrue(feels[Pattern.BUZZ]!!.pulses >= 6)
        for (p in listOf(Pattern.HUM, Pattern.LONG)) assertEquals("flat", feels[p]!!.shape)
        assertEquals(120L, feels[Pattern.HUM]!!.onMs)
        assertEquals(400L, feels[Pattern.LONG]!!.onMs)
        // The longest is the slow rise and fall; the peak is the strength's amplitude but for the flutter, the rumble, the
        // soft tap and the two steady ones, which are meant to be gentler.
        assertEquals(Pattern.LONG_SWELL, feels.maxByOrNull { it.value.spanMs }!!.key)
        val gentler = setOf(Pattern.BUZZ, Pattern.RUMBLE, Pattern.SOFT, Pattern.HUM, Pattern.LONG)
        for (p in Pattern.entries) if (p !in gentler) assertEquals("$p", 255, feels[p]!!.peak)
        // A weaker setting plays the same shape more softly.
        for (p in Pattern.entries) {
            val wave = planOf(p, Kind.AMPLITUDE)
            val soft = planOf(p, Kind.AMPLITUDE, master = Strength.MEDIUM, level = Strength.LIGHT)
            assertEquals(wave.timings.size, soft.timings.size)
            assertTrue("$p $soft", soft.amplitudes.max() in 1 until wave.amplitudes.max() * 6 / 10)
            assertEquals("$p", feel(wave).pulses, feel(soft).pulses)
            assertEquals("$p", feel(wave).shape, feel(soft).shape)
        }
        // Click primitives: the clicks are the vibrator's own clicks at the strength's scale, the rest are the waveforms.
        for (p in Pattern.entries) {
            val plan = planOf(p, Kind.PRIMITIVES)
            when (p) {
                Pattern.TICK, Pattern.DOUBLE, Pattern.TRIPLE -> assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = p.ordinal + 1, scale = 1f, gapMs = if (p == Pattern.TICK) 45 else 90), plan)
                else -> assertEquals(planOf(p, Kind.AMPLITUDE), plan)
            }
        }
        assertEquals(Pattern.entries.size, Pattern.entries.map { planOf(it, Kind.PRIMITIVES) }.toSet().size)
        assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 2, scale = 0.65f * 0.75f, gapMs = 90), planOf(Pattern.DOUBLE, Kind.PRIMITIVES, master = Strength.MEDIUM, level = Strength.MEDIUM))
    }

    @Test
    fun aMotorThatIsOnlyOnOrOffPlaysARhythmOrTheNearestKind() {
        // The kinds that need a finer motor, and what each plays as.
        assertEquals(mapOf(Pattern.SOFT to Pattern.TICK, Pattern.RISE to Pattern.DOUBLE, Pattern.SWELL to Pattern.HUM, Pattern.FADE to Pattern.HUM),
            Pattern.entries.filter { it.finer != null }.associateWith { it.finer })
        for (p in Pattern.entries) {
            val onOff = planOf(p, Kind.ON_OFF)
            // Off and on in turn, no amplitudes, the same at every strength.
            assertEquals(Kind.ON_OFF, onOff.kind)
            assertTrue("$p $onOff", onOff.amplitudes.isEmpty() && onOff.clicks == 0 && onOff.timings.first() == 0L && onOff.timings.size % 2 == 0)
            assertEquals(onOff, planOf(p, Kind.ON_OFF, master = Strength.LIGHT, level = Strength.LIGHT))
            val finer = p.finer
            if (finer != null) {
                // The fallback is a kind that is itself there, and it is what plays.
                assertNull("$p", finer.finer)
                assertEquals("$p", planOf(finer, Kind.ON_OFF), onOff)
                assertEquals(finer, Haptics.playsAs(p, Kind.ON_OFF, false))
                assertEquals(finer, Haptics.playsAs(p, Kind.PRIMITIVES, false))
            } else for ((kind, amp) in devices) assertNull("$p", Haptics.playsAs(p, kind, amp))
            // Where the motor takes amplitudes every kind is itself.
            assertNull(Haptics.playsAs(p, Kind.AMPLITUDE, true))
            assertNull(Haptics.playsAs(p, Kind.PRIMITIVES, true))
            // Click primitives without amplitudes: the vibrator's clicks for the clicks, the on/off rhythm for the rest.
            val clicksOnly = planOf(p, Kind.PRIMITIVES, amp = false)
            when (p) {
                Pattern.TICK, Pattern.SOFT -> assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 1, scale = 1f, gapMs = 45), clicksOnly)
                Pattern.DOUBLE, Pattern.RISE -> assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 2, scale = 1f, gapMs = 90), clicksOnly)
                Pattern.TRIPLE -> assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 3, scale = 1f, gapMs = 90), clicksOnly)
                else -> assertEquals(onOff, clicksOnly)
            }
        }
        // The kinds that are themselves there can be told apart by their rhythm.
        val own = Pattern.entries.filter { it.finer == null }
        assertEquals(12, own.size)
        val feels = own.associateWith { feel(planOf(it, Kind.ON_OFF)) }
        for (a in own) for (b in own) if (a.ordinal < b.ordinal) assertTrue("$a ${feels[a]} and $b ${feels[b]}", apart(feels[a]!!, feels[b]!!))
        assertEquals(listOf(1, 2, 3, 1, 2, 1, 4, 6, 1, 1, 3, 2), own.map { feels[it]!!.pulses })
        assertEquals(listOf(0L, 40L, 110L, 40L), planOf(Pattern.DOUBLE_KNOCK, Kind.ON_OFF).timings)
        assertEquals(listOf(0L, 400L), planOf(Pattern.LONG, Kind.ON_OFF).timings)
    }

    // ---- what 2.2.0 played ----

    /** 2.2.0's weight of a kind, which ranked the moment that had it. */
    private fun weight220(p: Pattern) = when (p) {
        Pattern.TICK, Pattern.RISE -> 1
        Pattern.DOUBLE -> 2
        Pattern.THUD, Pattern.BUZZ, Pattern.RUMBLE -> 3
        Pattern.HEAVY, Pattern.SWELL -> 4
        Pattern.LONG_SWELL -> 5
        else -> error("not in 2.2.0: $p")
    }

    /** 2.2.0's Haptics.plan, as it was. */
    private fun plan220(pattern: Pattern, master: Strength, level: Strength, kind: Kind, amplitude: Boolean, count: Int): Haptics.Plan? {
        val base = Haptics.intensity(master, level)
        if (base <= 0f || kind == Kind.NONE || count <= 0) return null
        val more = if (pattern == Pattern.TICK) 0 else (count - 1).coerceIn(0, 5)
        val e = (base * (1f + 0.12f * more)).coerceIn(0.15f, 1f)
        fun lerp(points: List<Pair<Float, Float>>): Float {
            val hi = points.indexOfFirst { e <= it.first }.let { if (it < 0) points.lastIndex else it.coerceAtLeast(1) }
            val (x0, y0) = points[hi - 1]
            val (x1, y1) = points[hi]
            return y0 + (y1 - y0) * ((e - x0) / (x1 - x0)).coerceIn(0f, 1f)
        }
        if (pattern == Pattern.TICK || pattern == Pattern.DOUBLE || pattern == Pattern.RISE) {
            val n = if (pattern == Pattern.TICK) count.coerceAtMost(16) else 2
            val gap = if (pattern == Pattern.TICK) 45 else 90
            return when {
                kind == Kind.PRIMITIVES && (pattern != Pattern.RISE || !amplitude) -> Haptics.Plan(kind, clicks = n, scale = e, gapMs = gap)
                kind == Kind.AMPLITUDE || kind == Kind.PRIMITIVES -> {
                    val ms = Math.round(lerp(listOf(0f to 8f, 0.35f to 10f, 0.65f to 15f, 1f to 20f))).toLong()
                    Haptics.Plan(Kind.AMPLITUDE,
                        timings = List(n * 2) { if (it == 0) 0L else if (it % 2 == 1) ms else gap.toLong() },
                        amplitudes = List(n * 2) { i -> if (i % 2 == 0) 0 else if (pattern == Pattern.RISE && i == 1) Haptics.amplitude(e * 0.5f) else Haptics.amplitude(e) })
                }
                else -> Haptics.Plan(Kind.ON_OFF, timings = List(n * 2) { if (it == 0) 0L else if (it % 2 == 1) 20L else gap.toLong() })
            }
        }
        if (kind == Kind.ON_OFF || !amplitude) {
            val onOff: List<Long> = when (pattern) {
                Pattern.THUD -> listOf(0L, 30L + 5 * more)
                Pattern.HEAVY -> listOf(0L, 70L)
                Pattern.BUZZ -> listOf(0L) + List(11) { 12L }
                Pattern.RUMBLE -> listOf(0L, 25L, 45L, 25L, 45L, 25L)
                Pattern.SWELL -> listOf(0L, 120L)
                else -> listOf(0L, 60L, 40L, 250L)
            }
            return Haptics.Plan(Kind.ON_OFF, timings = onOff)
        }
        val steps: List<Pair<Long, Float>> = when (pattern) {
            Pattern.THUD -> listOf(35L + 8 * more to 1f)
            Pattern.HEAVY -> listOf(60L to 1f, 40L to 0.5f, 30L to 0.25f)
            Pattern.BUZZ -> List(15) { 12L to if (it % 2 == 0) 0.7f else 0f }
            Pattern.RUMBLE -> List(10) { 30L to if (it % 2 == 0) 0.6f else 0.3f }
            Pattern.SWELL -> List(6) { 30L to 0.2f + 0.16f * it }
            else -> List(8) { 40L to 0.15f + 0.12f * it } + (120L to 1f) + List(4) { 50L to 0.7f - 0.17f * it }
        }
        return Haptics.Plan(Kind.AMPLITUDE, timings = steps.map { it.first }, amplitudes = steps.map { (_, share) -> if (share <= 0f) 0 else Haptics.amplitude(e * share) })
    }

    /** 2.2.0's gate, as it was. */
    private class Gate220 {
        private var busyUntil = 0L
        private var playing = 0
        fun admit(now: Long, durationMs: Long, priority: Int): Boolean {
            if (now < busyUntil && priority <= playing) return false
            busyUntil = now + durationMs + Haptics.GATE_GAP_MS
            playing = priority
            return true
        }
        fun admitOrWait(now: Long, durationMs: Long, priority: Int, patienceMs: Long): Long = when {
            admit(now, durationMs, priority) -> 0
            busyUntil - now <= patienceMs -> busyUntil - now
            else -> -1
        }
        fun take(now: Long, durationMs: Long) {
            busyUntil = maxOf(busyUntil, now + durationMs)
            playing = Int.MAX_VALUE
        }
    }

    @Test
    fun withEveryMomentsOwnKindTheVibratorGetsWhat220Sent() {
        // Every moment, at every pair of strengths, on every vibrator, for every count the engine sends and more.
        for (m in Moment.entries) for (master in Strength.entries) for (level in Strength.entries) for ((kind, amp) in devices + (Kind.NONE to false)) for (count in listOf(0, 1, 2, 3, 4, 6, 7, 20, 40)) {
            val settings = Settings(master, Moment.entries.associateWith { level })
            assertEquals("${m.key} $master/$level on $kind x$count", plan220(m.pattern, master, level, kind, amp, count), Haptics.planFor(m.id, count, settings, kind, amp))
        }
        // The presets, as a file from 2.2.0 holds them, play the same too (a few by hand, with their numbers).
        val everything = Settings.fromStored(mapOf("strength" to "MEDIUM", "model" to "2") + Moment.entries.associate { "level-${it.key}" to it.full.name })
        assertEquals(Preset.FULL, everything.preset)
        assertEquals(Haptics.Plan(Kind.AMPLITUDE, timings = listOf(51L), amplitudes = listOf(Haptics.amplitude(0.65f * 1.24f))), Haptics.planFor(GameHaptics.HULL_HIT, 3, everything, Kind.AMPLITUDE, true))
        assertEquals(Haptics.Plan(Kind.PRIMITIVES, clicks = 1, scale = 0.65f * 0.75f, gapMs = 45), Haptics.planFor(GameHaptics.FIRE_LASER, 1, everything, Kind.PRIMITIVES, true))
        assertEquals(Haptics.Plan(Kind.ON_OFF, timings = listOf(0L, 120L)), Haptics.planFor(GameHaptics.CLOAK_ON, 1, everything, Kind.ON_OFF, false))
        // And the same moment is the heavier one, and the same ones wait.
        for (m in Moment.entries) for (level in Strength.entries) {
            assertEquals(m.key, weight220(m.pattern) * 10 + level.ordinal + when (m.group) { Group.SHIP -> 20; Group.CREW -> 10; else -> 0 }, Haptics.priority(m, level))
            assertEquals(m.key, m.pattern == Pattern.DOUBLE || m.pattern == Pattern.LONG_SWELL, m.patient)
        }
    }

    @Test
    fun withEveryMomentsOwnKindMomentsMixAsIn220() {
        // A long random fight through both gates: what plays, waits and is dropped, and when, is the same.
        val random = java.util.Random(112)
        val game = Moment.entries.filter { it.group != Group.TOUCH }
        val settings = full()
        val gate = Haptics.Gate()
        val gate220 = Gate220()
        val waiting = ArrayList<Pair<Long, Haptics.Turn>>()
        var now = 1000L
        var played = 0
        var waited = 0
        var dropped = 0
        repeat(4000) {
            now += listOf(0L, 5L, 20L, 50L, 90L, 150L, 400L, 900L)[random.nextInt(8)]
            // The turns of the ones that waited come first.
            for (w in waiting.filter { it.first <= now }.sortedBy { it.first }) {
                val a = gate.offerWaited(w.first, w.second)
                assertEquals(gate220.admit(w.first, w.second.plan.durationMs, w.second.priority), a)
                if (a) waited++ else dropped++
            }
            waiting.removeAll { it.first <= now }
            if (random.nextInt(12) == 0) {
                gate.take(now, 20)
                gate220.take(now, 20)
                return@repeat
            }
            val m = game[random.nextInt(game.size)]
            val turn = Haptics.turn(m, 1 + random.nextInt(4), settings, Kind.AMPLITUDE, true)!!
            assertEquals(turn.plan.durationMs, turn.naturalMs)
            val wait = gate.offer(now, turn)
            val wait220 = if (m.patient) gate220.admitOrWait(now, turn.plan.durationMs, turn.priority, Haptics.PATIENCE_MS) else if (gate220.admit(now, turn.plan.durationMs, turn.priority)) 0L else -1L
            assertEquals("${m.key} at $now", wait220, wait)
            when {
                wait == 0L -> played++
                wait > 0 -> waiting += (now + wait) to turn
                else -> dropped++
            }
        }
        assertTrue("$played played, $waited after waiting, $dropped dropped", played > 300 && waited > 10 && dropped > 300)
    }

    // ---- a moment's own strength and kind ----

    @Test
    fun aMomentPlaysTheKindPickedForItAtItsOwnStrength() {
        val picked = full(Strength.MEDIUM).with(Moment.FIRE_LASER, Pattern.DOUBLE_KNOCK).with(Moment.FIRE_LASER, Strength.LIGHT)
        assertEquals(Pattern.DOUBLE_KNOCK, picked.kind(Moment.FIRE_LASER))
        assertTrue(picked.changed(Moment.FIRE_LASER))
        assertEquals(Pattern.TICK, Moment.FIRE_LASER.pattern)
        // Every kind at every strength, for a moment that is a tick of its own, for a knock and for an ending.
        for (m in listOf(Moment.FIRE_LASER, Moment.SYSTEM_DAMAGED, Moment.JUMP)) for (p in Pattern.entries) for (master in strengths) for (level in strengths) for ((kind, amp) in devices) {
            val s = full(master).with(m, p).with(m, level)
            assertEquals("${m.key} as $p", Haptics.plan(p, master, level, kind, amp, 1), Haptics.planFor(m.id, 1, s, kind, amp))
        }
        // With its numbers: two knocks of 35 ms, 110 ms apart, at Medium overall and Light for the moment.
        assertEquals(Haptics.Plan(Kind.AMPLITUDE, timings = listOf(35L, 110L, 35L), amplitudes = listOf(Haptics.amplitude(0.65f * 0.5f), 0, Haptics.amplitude(0.65f * 0.5f))),
            Haptics.planFor(GameHaptics.FIRE_LASER, 1, picked, Kind.AMPLITUDE, true))
        assertEquals(84, Haptics.amplitude(0.65f * 0.5f))
        assertEquals(Haptics.Plan(Kind.ON_OFF, timings = listOf(0L, 40L, 110L, 40L)), Haptics.planFor(GameHaptics.FIRE_LASER, 1, picked, Kind.ON_OFF, false))
        // The other moments play what they played.
        for (m in Moment.entries) if (m != Moment.FIRE_LASER) {
            assertFalse(picked.changed(m))
            assertEquals(m.key, Haptics.planFor(m.id, 1, full(Strength.MEDIUM), Kind.AMPLITUDE, true), Haptics.planFor(m.id, 1, picked, Kind.AMPLITUDE, true))
        }
        // Its own kind again, either way, is the setting as it was.
        assertEquals(full(Strength.MEDIUM).with(Moment.FIRE_LASER, Strength.LIGHT), picked.with(Moment.FIRE_LASER, null))
        assertEquals(picked.with(Moment.FIRE_LASER, null), picked.with(Moment.FIRE_LASER, Pattern.TICK))
        // The kind and the strength do not touch each other.
        assertEquals(Pattern.DOUBLE_KNOCK, picked.with(Moment.FIRE_LASER, Strength.OFF).with(Moment.FIRE_LASER, Strength.STRONG).kind(Moment.FIRE_LASER))
        assertEquals(Strength.LIGHT, picked.with(Moment.FIRE_LASER, Pattern.FADE).level(Moment.FIRE_LASER))
        // A touch tick takes a kind as well.
        val knock = Settings.DEFAULT.with(Moment.POWER, Pattern.THUD)
        assertEquals(Haptics.plan(Pattern.THUD, Strength.MEDIUM, Strength.STRONG, Kind.AMPLITUDE, true, 1), Haptics.planFor(TouchHaptics.POWER, 1, knock, Kind.AMPLITUDE, true))
        assertEquals(tick(Strength.MEDIUM, Kind.AMPLITUDE, 1), Haptics.planFor(TouchHaptics.MAGNIFIER, 1, knock, Kind.AMPLITUDE, true))
    }

    @Test
    fun theCountGoesOnlyToAKindItMeansSomethingTo() {
        fun plan(m: Moment, p: Pattern, count: Int) = Haptics.planFor(m.id, count, full().with(m, p), Kind.AMPLITUDE, true)
        fun kind(p: Pattern, count: Int, level: Strength) = Haptics.plan(p, Strength.STRONG, level, Kind.AMPLITUDE, true, count)
        // Hull points make another kind firmer too, as they do the knock; as a tick, a hit is one tick, not one per point.
        val hull = Moment.HULL_HIT.full
        assertEquals(kind(Pattern.DOUBLE_KNOCK, 3, Strength.MEDIUM), Haptics.planFor(GameHaptics.HULL_HIT, 3, full().with(Moment.HULL_HIT, Pattern.DOUBLE_KNOCK).with(Moment.HULL_HIT, Strength.MEDIUM), Kind.AMPLITUDE, true))
        assertTrue(kind(Pattern.DOUBLE_KNOCK, 3, Strength.MEDIUM)!!.amplitudes[0] > kind(Pattern.DOUBLE_KNOCK, 1, Strength.MEDIUM)!!.amplitudes[0])
        assertEquals(kind(Pattern.TICK, 1, hull), plan(Moment.HULL_HIT, Pattern.TICK, 3))
        assertEquals(kind(Pattern.TICK, 1, hull), plan(Moment.HULL_HIT, Pattern.TICK, 1))
        // Power bars moved are ticks: three for three as ever, but one knock, one double click, one long vibration.
        val power = Moment.POWER.full
        assertEquals(kind(Pattern.TICK, 3, power), plan(Moment.POWER, Pattern.TICK, 3))
        for (p in listOf(Pattern.THUD, Pattern.DOUBLE, Pattern.LONG, Pattern.SOFT)) assertEquals("$p", kind(p, 1, power), plan(Moment.POWER, p, 3))
        // Nothing for nothing, whatever the kind.
        for (p in Pattern.entries) {
            assertNull(plan(Moment.POWER, p, 0))
            assertNull(plan(Moment.HULL_HIT, p, 0))
        }
    }

    @Test
    fun aGroupsShortcutsJustSetItsRows() {
        val now = full()
        val weapons = Moment.entries.filter { it.group == Group.WEAPONS }
        // Each of its own kind: the group has none, and nothing is changed.
        for (g in Group.entries) {
            assertNull(now.kind(g))
            assertEquals(0, now.changed(g))
        }
        // One kind for all: the same as picking it in each row, and no other group's rows move.
        val all = now.with(Group.WEAPONS, Pattern.DOUBLE_KNOCK)
        assertEquals(weapons.fold(now) { s, m -> s.with(m, Pattern.DOUBLE_KNOCK) }, all)
        assertTrue(weapons.all { all.kind(it) == Pattern.DOUBLE_KNOCK })
        assertEquals(Pattern.DOUBLE_KNOCK, all.kind(Group.WEAPONS))
        assertEquals(weapons.size, all.changed(Group.WEAPONS))
        assertTrue(Moment.entries.filter { it.group != Group.WEAPONS }.none { all.changed(it) })
        assertNull(all.preset)
        // A kind some of them have of their own: those rows are not changed, the group still reads as that kind.
        val ticks = now.with(Group.WEAPONS, Pattern.TICK)
        assertEquals(Pattern.TICK, ticks.kind(Group.WEAPONS))
        assertEquals(weapons.count { it.pattern != Pattern.TICK }, ticks.changed(Group.WEAPONS))
        assertTrue(ticks.changed(Group.WEAPONS) in 1 until weapons.size)
        // One row differs afterwards: the group has no one kind, and says how many are changed.
        val mixed = all.with(Moment.FIRE_BEAM, Pattern.LONG).with(Moment.FIRE_LASER, null)
        assertNull(mixed.kind(Group.WEAPONS))
        assertEquals(weapons.size - 1, mixed.changed(Group.WEAPONS))
        // Each its own again.
        assertEquals(now, mixed.with(Group.WEAPONS, null))
        assertEquals(Preset.FULL, mixed.with(Group.WEAPONS, null).preset)
        // The strength shortcut and the kind shortcut do not touch each other.
        val both = all.with(Group.WEAPONS, Strength.LIGHT)
        assertEquals(Pattern.DOUBLE_KNOCK, both.kind(Group.WEAPONS))
        assertEquals(Strength.LIGHT, both.level(Group.WEAPONS))
        assertEquals(now.levels, all.levels)
        for (m in weapons) assertEquals(Haptics.plan(Pattern.DOUBLE_KNOCK, Strength.STRONG, Strength.LIGHT, Kind.AMPLITUDE, true, 1), Haptics.planFor(m.id, 1, both, Kind.AMPLITUDE, true))
    }

    // ---- the file: 2.2.0's, and ours in 2.2.0's hands ----

    /** How 2.2.0 reads the preferences file, as it was: the overall strength and a strength per moment. */
    private fun read220(all: Map<String, *>): Pair<Strength, Map<Moment, Strength>> {
        val master = (all["strength"] as? String)?.let { s -> Strength.entries.firstOrNull { it.name == s } } ?: Strength.MEDIUM
        val old = all["model"] == null
        return master to Moment.entries.associateWith { m ->
            val saved = (all["level-${m.key}"] as? String)?.let { s -> Strength.entries.firstOrNull { it.name == s } }
            when {
                saved != null -> saved
                old && m.group == Group.TOUCH && all["action-${m.key}"] == false -> Strength.OFF
                else -> Preset.TOUCH.level(m)
            }
        }
    }

    /** What 2.2.0 writes: strings under these names; it removes nothing, so what else the file holds stays. */
    private fun stored220(master: Strength, levels: Map<Moment, Strength>): Map<String, String> =
        mapOf("strength" to master.name, "model" to "2") + Moment.entries.associate { "level-${it.key}" to (levels[it] ?: Strength.OFF).name }

    @Test
    fun aFileFrom220ReadsWithEveryMomentsOwnKind() {
        val levels = Preset.FULL.levels + (Moment.HULL_HIT to Strength.LIGHT) + (Moment.JUMP to Strength.OFF)
        for ((master, saved) in listOf(Strength.STRONG to levels, Strength.MEDIUM to Preset.COMBAT.levels, Strength.LIGHT to Preset.TOUCH.levels, Strength.OFF to Preset.LIGHT.levels)) {
            val file = stored220(master, saved)
            val read = Settings.fromStored(file)
            assertEquals(Settings(master, saved), read)
            assertTrue(read.kinds.isEmpty() && Moment.entries.all { read.kind(it) == it.pattern })
            assertEquals(read220(file), read.master to read.levels)
            // Saved again without a change, the file is what it was: no name added, none to remove that it holds.
            assertEquals(file, read.stored())
            assertTrue(read.unstored().none { it in file })
        }
        assertEquals(Preset.COMBAT, Settings.fromStored(stored220(Strength.MEDIUM, Preset.COMBAT.levels)).preset)
        assertNull(Settings.fromStored(stored220(Strength.STRONG, levels)).preset)
    }

    @Test
    fun anOlderBuildReadsOurFileAsItsStrengths() {
        val ours = full(Strength.LIGHT).with(Moment.HULL_HIT, Strength.MEDIUM).with(Moment.FIRE_LASER, Pattern.LONG).with(Group.CREW, Pattern.HEARTBEAT).with(Moment.POWER, Pattern.SOFT)
        val file = ours.stored()
        // 2.2.0 finds every name it knows, each a string it reads; the kinds are names it never asks for.
        assertEquals(stored220(ours.master, ours.levels), file.filterKeys { !it.startsWith("kind-") })
        assertEquals("2", file["model"])
        val (master, levels) = read220(file)
        assertEquals(ours.master, master)
        assertEquals(ours.levels, levels)
        // It plays each moment's own kind at the strengths we saved, and when it saves, our kinds stay in the file:
        // back in this build they are there again.
        val after220 = file + stored220(master, levels + (Moment.JUMP to Strength.OFF))
        assertEquals(ours.with(Moment.JUMP, Strength.OFF), Settings.fromStored(after220))
        // A kind that does not read (a later build's), a kind that is not a string, the moment's own: the moment's own.
        val any: Map<String, Any> = file
        val odd = Settings.fromStored(any + ("kind-fire-laser" to "sparkle") + ("kind-hull-hit" to 7) + ("kind-jump" to "long-swell") + ("kind-no-such-moment" to "long"))
        assertEquals(ours.with(Moment.FIRE_LASER, null), odd)
        assertFalse(odd.changed(Moment.FIRE_LASER) || odd.changed(Moment.HULL_HIT) || odd.changed(Moment.JUMP))
        assertEquals(Pattern.HEARTBEAT, odd.kind(Moment.CREW_DIED))
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

    private fun turn(m: Moment, s: Settings = full(), count: Int = 1) = Haptics.turn(m, count, s, Kind.AMPLITUDE, true)!!

    @Test
    fun aVolleyIsItsKnocksNotOneBuzzAndNothingComesLate() {
        val hit = turn(Moment.HULL_HIT)
        assertEquals(35L, hit.plan.durationMs)
        // Twelve hits 20 ms apart: one knock at a time with a pause after each, so the motor rests more than it runs.
        val gate = Haptics.Gate()
        val starts = (0 until 12).map { it * 20L }.filter { gate.offer(it, hit) == 0L }
        assertEquals(listOf(0L, 100L, 200L), starts)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= hit.plan.durationMs + Haptics.GATE_GAP_MS })
        // What was refused is gone: nothing plays after the volley that was not asked for then.
        assertEquals(0L, gate.offer(400, hit))
    }

    @Test
    fun theHeavierMomentWins() {
        val gate = Haptics.Gate()
        val tick = Haptics.priority(Moment.FIRE_LASER, Strength.STRONG)
        val thud = Haptics.priority(Moment.ENEMY_HULL_HIT, Strength.LIGHT)
        val boom = Haptics.priority(Moment.ENEMY_DESTROYED, Strength.MEDIUM)
        assertTrue(tick < thud && thud < boom)
        assertTrue(Haptics.priority(Moment.ENEMY_HULL_HIT, Strength.LIGHT) < Haptics.priority(Moment.ENEMY_HULL_HIT, Strength.STRONG))
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
        assertTrue(Moment.WARN_HULL.patient && Moment.ENEMY_DESTROYED.patient && Moment.INTRUDERS.patient)
        assertFalse(Moment.HULL_HIT.patient || Moment.FIRE_LASER.patient || Moment.POWER.patient)
        val later = Haptics.Gate()
        val hit = Haptics.priority(Moment.HULL_HIT, Strength.STRONG)
        val warning = Haptics.priority(Moment.WARN_HULL, Strength.STRONG)
        val offer = Haptics.priority(Moment.ENEMY_SURRENDERS, Strength.LIGHT)
        assertTrue(warning < hit && offer < boom)
        assertEquals(0L, later.admitOrWait(5000, 35, hit, Haptics.PATIENCE_MS))
        assertEquals(35 + Haptics.GATE_GAP_MS - 2, later.admitOrWait(5002, 180, warning, Haptics.PATIENCE_MS))
        assertFalse("not before its turn", later.admitWaited(5000 + 35 + Haptics.GATE_GAP_MS - 1, 180, warning))
        assertTrue("its turn has come", later.admitWaited(5000 + 35 + Haptics.GATE_GAP_MS, 180, warning))
        assertEquals(0L, later.admitOrWait(6000, 640, boom, Haptics.PATIENCE_MS))
        assertEquals(-1L, later.admitOrWait(6010, 180, offer, Haptics.PATIENCE_MS))
        assertEquals(60 + Haptics.GATE_GAP_MS, later.admitOrWait(6580, 180, offer, Haptics.PATIENCE_MS))
        // A touch tick always plays, and no game moment cuts it off.
        gate.take(1000, 20)
        assertFalse(gate.admit(1010, 640, boom))
        assertTrue(gate.admit(1020, 640, boom))
        gate.take(1100, 20)
        assertFalse(gate.admit(1110, 35, thud))
    }

    @Test
    fun theKindPickedForAMomentDoesNotRankIt() {
        // Whatever kind a moment plays, it is as heavy as it was, waits or not as it did, and counts as lasting what its own lasts.
        for (m in Moment.entries) for (p in Pattern.entries) {
            val own = turn(m)
            val picked = turn(m, full().with(m, p))
            assertEquals("${m.key} as $p", own.priority, picked.priority)
            assertEquals("${m.key} as $p", own.patient, picked.patient)
            assertEquals("${m.key} as $p", own.plan.durationMs, picked.naturalMs)
            assertEquals("${m.key} as $p", Haptics.plan(p, Strength.STRONG, m.full, Kind.AMPLITUDE, true, 1), picked.plan)
        }
        // So a tick with a long vibration picked stays below a knock with a tick picked.
        val swapped = full().with(Moment.FIRE_LASER, Pattern.LONG).with(Moment.HULL_HIT, Pattern.TICK)
        assertTrue(turn(Moment.FIRE_LASER, swapped).priority < turn(Moment.HULL_HIT, swapped).priority)
        assertTrue(turn(Moment.FIRE_LASER, swapped).plan.durationMs > 10 * turn(Moment.HULL_HIT, swapped).plan.durationMs)
    }

    @Test
    fun aLongKindPickedForASmallMomentHoldsNothingBackThatMatters() {
        val picked = full().with(Moment.FIRE_LASER, Pattern.LONG)
        val laser = turn(Moment.FIRE_LASER, picked)
        assertEquals(400L, laser.plan.durationMs)
        assertEquals(turn(Moment.FIRE_LASER).plan.durationMs, laser.naturalMs)
        assertTrue(laser.naturalMs <= 20)
        // A hull hit 30 ms into the long vibration picked for "Laser fired" plays at once, as it would behind the tick.
        val hit = turn(Moment.HULL_HIT, picked)
        for (at in listOf(1L, 30L, 200L, 399L)) {
            val gate = Haptics.Gate()
            assertEquals(0L, gate.offer(0, laser))
            assertEquals("hull hit at $at", 0L, gate.offer(at, hit))
        }
        // So does everything else that is heavier than a laser shot; what is as light or lighter is held off while the long
        // vibration plays (one at a time) and no longer.
        for (m in Moment.entries.filter { it.group != Group.TOUCH }) {
            val gate = Haptics.Gate()
            val other = turn(m, picked)
            assertEquals(0L, gate.offer(0, laser))
            val wait = gate.offer(200, other)
            assertEquals(m.key, if (other.priority > laser.priority) 0L else -1L, wait)
        }
        val miss = turn(Moment.SHOT_MISSED, picked)
        assertTrue(miss.priority < laser.priority)
        val gate = Haptics.Gate()
        assertEquals(0L, gate.offer(0, laser))
        assertEquals(-1L, gate.offer(100, laser))
        assertEquals(-1L, gate.offer(400 + Haptics.GATE_GAP_MS - 1, miss))
        assertEquals(0L, gate.offer(400 + Haptics.GATE_GAP_MS, miss))
    }

    @Test
    fun aWarningIsNotSwallowedByALongKindPickedForTheHitBeforeIt() {
        val warning = turn(Moment.WARN_HULL)
        // The hull hit that brings the hull low, as a double knock: 180 ms instead of a knock of 35. The warning 2 ms
        // behind it waits for both knocks (and the pause), as it waits for anything that ends within half a second.
        val knocks = turn(Moment.HULL_HIT, full().with(Moment.HULL_HIT, Pattern.DOUBLE_KNOCK))
        assertEquals(180L, knocks.plan.durationMs)
        assertEquals(35L, knocks.naturalMs)
        assertTrue(warning.patient && warning.priority < knocks.priority)
        val both = Haptics.Gate()
        assertEquals(0L, both.offer(0, knocks))
        assertEquals(180 + Haptics.GATE_GAP_MS - 2, both.offer(2, warning))
        assertTrue(both.offerWaited(180 + Haptics.GATE_GAP_MS, warning))
        // As the long rise and fall it lasts 640 ms: longer than a warning waits, so by 2.2.0's rule the warning would be
        // dropped. It takes its turn when the knock (and its pause) would have ended, as behind the moment's own kind.
        val picked = full().with(Moment.HULL_HIT, Pattern.LONG_SWELL)
        val hit = turn(Moment.HULL_HIT, picked)
        assertEquals(640L, hit.plan.durationMs)
        assertEquals(35L, hit.naturalMs)
        assertTrue(hit.plan.durationMs + Haptics.GATE_GAP_MS > Haptics.PATIENCE_MS)
        val own = Haptics.Gate()
        assertEquals(0L, own.offer(0, turn(Moment.HULL_HIT)))
        val gate = Haptics.Gate()
        assertEquals(0L, gate.offer(0, hit))
        assertEquals(own.offer(2, warning), gate.offer(2, warning))
        assertEquals(35 + Haptics.GATE_GAP_MS - 2, gate.offer(2, warning))
        assertFalse(gate.offerWaited(35 + Haptics.GATE_GAP_MS - 1, warning))
        assertTrue(gate.offerWaited(35 + Haptics.GATE_GAP_MS, warning))
        // It is the one playing then: a lighter moment does not cut the warning.
        assertEquals(-1L, gate.offer(120, turn(Moment.FIRE_LASER, picked)))
        // A warning that comes when the knock would be over, and the rest still lasts too long, takes its turn at once;
        // one that comes when the rest ends within half a second waits for the end.
        val late = Haptics.Gate()
        assertEquals(0L, late.offer(0, hit))
        assertEquals(0L, late.offer(150, warning))
        val later = Haptics.Gate()
        assertEquals(0L, later.offer(0, hit))
        assertEquals(400L, later.offer(300, warning))
        assertTrue(later.offerWaited(700, warning))
        // What does not wait and is lighter is dropped for as long as the long kind plays; another hull hit too.
        val busy = Haptics.Gate()
        assertEquals(0L, busy.offer(0, hit))
        assertEquals(-1L, busy.offer(300, turn(Moment.FIRE_LASER, picked)))
        assertEquals(-1L, busy.offer(300, hit))
        assertEquals(0L, busy.offer(640 + Haptics.GATE_GAP_MS, hit))
        // A heavier moment that starts while the warning waits keeps the warning out, as it always did.
        val end = Haptics.Gate()
        assertEquals(0L, end.offer(0, hit))
        assertEquals(93L, end.offer(2, warning))
        assertEquals(0L, end.offer(50, turn(Moment.SHIP_DESTROYED, picked)))
        assertFalse(end.offerWaited(95, warning))
        // A long kind picked for the warning itself: it still waits for the knock, then plays in full.
        val longWarning = turn(Moment.WARN_HULL, full().with(Moment.WARN_HULL, Pattern.LONG))
        val third = Haptics.Gate()
        assertEquals(0L, third.offer(0, turn(Moment.HULL_HIT)))
        assertEquals(93L, third.offer(2, longWarning))
        assertTrue(third.offerWaited(95, longWarning))
        // A touch tick is never cut, and holds everything off while it plays, whatever kinds were picked.
        val touch = Haptics.Gate()
        touch.take(0, 400)
        assertEquals(-1L, touch.offer(100, hit))
        assertEquals(300L, touch.offer(100, warning))
        assertEquals(0L, touch.offer(400, hit))
    }
}
