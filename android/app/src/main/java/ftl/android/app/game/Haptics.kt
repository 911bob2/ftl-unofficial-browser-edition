package ftl.android.app.game

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ftl.android.app.BuildConfig
import xyz.znix.xftl.game.GameHaptics
import xyz.znix.xftl.game.TouchHaptics

/**
 * Settings > Haptics: the touch controls' ticks (engine TouchHaptics) and the game's moments (engine GameHaptics) on
 * this device's vibrator. The device's setting, in its own preferences file as Screen is; it never reaches a save.
 * Neither FTL on iPad nor on Windows has haptics, so there is no native setting to follow (docs/ANDROID.md).
 *
 * Every [Moment] has its own strength ([Settings.levels]) and its own kind of vibration ([Settings.kinds], a
 * [Pattern]: its own until the player picks another); the overall strength ([Settings.master]) scales them all, and
 * Off there turns everything off. A [Preset] is a set of strengths with every moment's own kind; changing one strength
 * or kind makes it Custom. A pattern is made the best way the vibrator allows ([Kind]). Touch ticks are touch feedback
 * and game moments media vibration, so Android's own Touch feedback and Media vibration settings apply on top.
 */
class Haptics private constructor(
    private val vibrator: Vibrator?,
    val kind: Kind,
    /** Whether the vibrator takes an amplitude, for the patterns longer than a click. */
    private val amplitude: Boolean,
    val settings: Settings,
) {
    enum class Strength(val label: String) { OFF("Off"), LIGHT("Light"), MEDIUM("Medium"), STRONG("Strong") }

    /** What this vibrator can do, best first. */
    enum class Kind { PRIMITIVES, AMPLITUDE, ON_OFF, NONE }

    /**
     * The kinds of vibration a moment can have: the name it is saved under (never change one), the name in the list and
     * what it feels like. [finer]: what it plays as on a motor that is only on or off, when it needs amplitudes to be
     * itself (null: its own rhythm is still recognisable there).
     */
    enum class Pattern(val key: String, val title: String, val text: String, val finer: Pattern? = null) {
        TICK("tick", "Tick", "a short click"),
        DOUBLE("double", "Double click", "two clicks"),
        TRIPLE("triple", "Triple click", "three clicks"),
        SOFT("soft", "Soft tap", "a light, rounder touch", finer = TICK),
        RISE("rise", "Rising double", "two clicks, the second firmer", finer = DOUBLE),
        THUD("thud", "Knock", "a knock"),
        DOUBLE_KNOCK("double-knock", "Double knock", "two knocks"),
        HEAVY("heavy", "Hard knock", "a hard knock that rings off"),
        HEARTBEAT("heartbeat", "Heartbeat", "two beats, twice"),
        BUZZ("buzz", "Flutter", "a quick flutter"),
        HUM("hum", "Short buzz", "a steady buzz of a tenth of a second"),
        LONG("long", "Long vibration", "a steady vibration of nearly half a second"),
        RUMBLE("rumble", "Low rumble", "a low shake"),
        SWELL("swell", "Quick rise", "a quick rise that cuts off", finer = HUM),
        FADE("fade", "Fade-out", "a knock that dies away", finer = HUM),
        LONG_SWELL("long-swell", "Slow rise and fall", "a long rise and fall");

        companion object {
            private val byKey = entries.associateBy { it.key }
            fun of(key: String?): Pattern? = byKey[key]
        }
    }

    enum class Group(val title: String, val text: String) {
        TOUCH("Touch controls", "Feedback for what your fingers do."),
        SHIP("Your ship", "Damage to your hull, shields and systems."),
        WEAPONS("Your weapons", "Firing, charging and what became of the shot."),
        DEFENCE("Defence and drones", "Dodges, shots shot down, drones and the cloak."),
        CREW("Crew and boarding", "Your crew's fate, intruders, teleporter, mind control and hacking."),
        ENEMY("The enemy ship", "What your attack does to it, and how the fight ends."),
        TRAVEL("Travel and hazards", "The FTL drive, the jump, and what a beacon throws at you."),
    }

    /**
     * Everything that can vibrate: the engine's number for it (TouchHaptics, GameHaptics), the name it is saved under
     * (never change one), its group, title, its own kind of vibration ([pattern]; the player may pick another), and its
     * strength in the Everything and Light presets.
     */
    enum class Moment(val id: Int, val key: String, val group: Group, val title: String, val pattern: Pattern, val full: Strength, val light: Strength = Strength.OFF) {
        POWER(TouchHaptics.POWER, "power", Group.TOUCH, "System power", Pattern.TICK, Strength.STRONG, Strength.STRONG),
        MAGNIFIER(TouchHaptics.MAGNIFIER, "magnifier", Group.TOUCH, "Magnifier", Pattern.TICK, Strength.STRONG, Strength.STRONG),

        HULL_HIT(GameHaptics.HULL_HIT, "hull-hit", Group.SHIP, "Hull hit", Pattern.THUD, Strength.STRONG, Strength.MEDIUM),
        SHIELD_HIT(GameHaptics.SHIELD_HIT, "shield-hit", Group.SHIP, "Shot stopped by the shields", Pattern.TICK, Strength.MEDIUM),
        SHIELD_DOWN(GameHaptics.SHIELD_DOWN, "shield-down", Group.SHIP, "Shield layer lost", Pattern.THUD, Strength.MEDIUM, Strength.LIGHT),
        SHIELD_UP(GameHaptics.SHIELD_UP, "shield-up", Group.SHIP, "Shield layer back", Pattern.RISE, Strength.LIGHT),
        SYSTEM_DAMAGED(GameHaptics.SYSTEM_DAMAGED, "system-damaged", Group.SHIP, "System damaged", Pattern.THUD, Strength.MEDIUM),
        SYSTEM_DESTROYED(GameHaptics.SYSTEM_DESTROYED, "system-destroyed", Group.SHIP, "System destroyed", Pattern.HEAVY, Strength.STRONG, Strength.MEDIUM),
        SYSTEM_REPAIRED(GameHaptics.SYSTEM_REPAIRED, "system-repaired", Group.SHIP, "System bar repaired", Pattern.RISE, Strength.LIGHT),
        SYSTEM_IONISED(GameHaptics.SYSTEM_IONISED, "system-ionised", Group.SHIP, "System ionised", Pattern.BUZZ, Strength.MEDIUM),
        BREACH(GameHaptics.BREACH, "breach", Group.SHIP, "Hull breach", Pattern.HEAVY, Strength.MEDIUM),
        FIRE(GameHaptics.FIRE, "fire", Group.SHIP, "Fire breaks out", Pattern.RUMBLE, Strength.MEDIUM),
        WARN_HULL(GameHaptics.WARN_HULL, "warn-hull", Group.SHIP, "Hull low warning", Pattern.DOUBLE, Strength.STRONG, Strength.MEDIUM),
        WARN_OXYGEN(GameHaptics.WARN_OXYGEN, "warn-oxygen", Group.SHIP, "Oxygen low warning", Pattern.DOUBLE, Strength.MEDIUM),
        SHIP_DESTROYED(GameHaptics.SHIP_DESTROYED, "ship-destroyed", Group.SHIP, "Your ship is destroyed", Pattern.LONG_SWELL, Strength.STRONG, Strength.STRONG),

        FIRE_LASER(GameHaptics.FIRE_LASER, "fire-laser", Group.WEAPONS, "Laser fired", Pattern.TICK, Strength.MEDIUM),
        FIRE_ION(GameHaptics.FIRE_ION, "fire-ion", Group.WEAPONS, "Ion weapon fired", Pattern.BUZZ, Strength.LIGHT),
        FIRE_MISSILE(GameHaptics.FIRE_MISSILE, "fire-missile", Group.WEAPONS, "Missile launched", Pattern.THUD, Strength.MEDIUM),
        FIRE_BEAM(GameHaptics.FIRE_BEAM, "fire-beam", Group.WEAPONS, "Beam fired", Pattern.RUMBLE, Strength.MEDIUM),
        FIRE_BOMB(GameHaptics.FIRE_BOMB, "fire-bomb", Group.WEAPONS, "Bomb sent", Pattern.SWELL, Strength.LIGHT),
        FIRE_FLAK(GameHaptics.FIRE_FLAK, "fire-flak", Group.WEAPONS, "Flak fired", Pattern.HEAVY, Strength.MEDIUM),
        WEAPON_CHARGED(GameHaptics.WEAPON_CHARGED, "weapon-charged", Group.WEAPONS, "Weapon charged", Pattern.RISE, Strength.LIGHT),
        SHOT_MISSED(GameHaptics.SHOT_MISSED, "shot-missed", Group.WEAPONS, "Your shot missed", Pattern.TICK, Strength.LIGHT),
        SHOT_INTERCEPTED(GameHaptics.SHOT_INTERCEPTED, "shot-intercepted", Group.WEAPONS, "Your shot was shot down", Pattern.TICK, Strength.LIGHT),

        DODGED(GameHaptics.DODGED, "dodged", Group.DEFENCE, "Enemy shot dodged", Pattern.TICK, Strength.LIGHT),
        INTERCEPTED(GameHaptics.INTERCEPTED, "intercepted", Group.DEFENCE, "Incoming shot shot down", Pattern.TICK, Strength.MEDIUM),
        DRONE_LAUNCHED(GameHaptics.DRONE_LAUNCHED, "drone-launched", Group.DEFENCE, "Drone launched", Pattern.TICK, Strength.LIGHT),
        DRONE_DESTROYED(GameHaptics.DRONE_DESTROYED, "drone-destroyed", Group.DEFENCE, "Drone destroyed", Pattern.THUD, Strength.MEDIUM),
        CLOAK_ON(GameHaptics.CLOAK_ON, "cloak-on", Group.DEFENCE, "Cloak engaged", Pattern.SWELL, Strength.MEDIUM),
        CLOAK_OFF(GameHaptics.CLOAK_OFF, "cloak-off", Group.DEFENCE, "Cloak ends", Pattern.TICK, Strength.LIGHT),

        CREW_HURT(GameHaptics.CREW_HURT, "crew-hurt", Group.CREW, "Crew member badly hurt", Pattern.DOUBLE, Strength.MEDIUM),
        CREW_DIED(GameHaptics.CREW_DIED, "crew-died", Group.CREW, "Crew member dies", Pattern.HEAVY, Strength.STRONG, Strength.MEDIUM),
        CREW_CLONED(GameHaptics.CREW_CLONED, "crew-cloned", Group.CREW, "Crew member cloned", Pattern.RISE, Strength.MEDIUM),
        INTRUDERS(GameHaptics.INTRUDERS, "intruders", Group.CREW, "Intruders aboard", Pattern.DOUBLE, Strength.STRONG, Strength.MEDIUM),
        TELEPORT(GameHaptics.TELEPORT, "teleport", Group.CREW, "Your teleporter", Pattern.SWELL, Strength.MEDIUM),
        MIND_CONTROL(GameHaptics.MIND_CONTROL, "mind-control", Group.CREW, "Your mind control takes hold", Pattern.BUZZ, Strength.LIGHT),
        MIND_CONTROLLED(GameHaptics.MIND_CONTROLLED, "mind-controlled", Group.CREW, "Enemy mind control takes hold", Pattern.BUZZ, Strength.MEDIUM),
        HACK(GameHaptics.HACK, "hack", Group.CREW, "Your hacking pulse", Pattern.BUZZ, Strength.LIGHT),
        HACKED(GameHaptics.HACKED, "hacked", Group.CREW, "Enemy hacking pulse", Pattern.BUZZ, Strength.MEDIUM),

        ENEMY_HULL_HIT(GameHaptics.ENEMY_HULL_HIT, "enemy-hull-hit", Group.ENEMY, "Enemy hull hit", Pattern.THUD, Strength.LIGHT),
        ENEMY_SHIELD_HIT(GameHaptics.ENEMY_SHIELD_HIT, "enemy-shield-hit", Group.ENEMY, "Shot stopped by its shields", Pattern.TICK, Strength.LIGHT),
        ENEMY_SHIELD_DOWN(GameHaptics.ENEMY_SHIELD_DOWN, "enemy-shield-down", Group.ENEMY, "Enemy shield layer lost", Pattern.TICK, Strength.MEDIUM),
        ENEMY_SYSTEM_DESTROYED(GameHaptics.ENEMY_SYSTEM_DESTROYED, "enemy-system-destroyed", Group.ENEMY, "Enemy system destroyed", Pattern.THUD, Strength.MEDIUM),
        ENEMY_BREAKING(GameHaptics.ENEMY_BREAKING, "enemy-breaking", Group.ENEMY, "Enemy ship breaking apart", Pattern.RUMBLE, Strength.MEDIUM, Strength.LIGHT),
        ENEMY_DESTROYED(GameHaptics.ENEMY_DESTROYED, "enemy-destroyed", Group.ENEMY, "Enemy ship explodes", Pattern.LONG_SWELL, Strength.STRONG, Strength.MEDIUM),
        ENEMY_SURRENDERS(GameHaptics.ENEMY_SURRENDERS, "enemy-surrenders", Group.ENEMY, "Enemy offers to surrender", Pattern.DOUBLE, Strength.LIGHT),
        ENEMY_ESCAPING(GameHaptics.ENEMY_ESCAPING, "enemy-escaping", Group.ENEMY, "Enemy charges its FTL to escape", Pattern.DOUBLE, Strength.MEDIUM),
        ENEMY_GONE(GameHaptics.ENEMY_GONE, "enemy-gone", Group.ENEMY, "Enemy jumps away", Pattern.SWELL, Strength.MEDIUM),
        ENEMY_CREW_DIED(GameHaptics.ENEMY_CREW_DIED, "enemy-crew-died", Group.ENEMY, "Enemy crew member dies", Pattern.THUD, Strength.LIGHT),
        ENEMY_CLOAK(GameHaptics.ENEMY_CLOAK, "enemy-cloak", Group.ENEMY, "Enemy cloaks", Pattern.SWELL, Strength.LIGHT),

        FTL_CHARGED(GameHaptics.FTL_CHARGED, "ftl-charged", Group.TRAVEL, "FTL drive charged", Pattern.RISE, Strength.MEDIUM, Strength.LIGHT),
        JUMP(GameHaptics.JUMP, "jump", Group.TRAVEL, "Jump", Pattern.LONG_SWELL, Strength.MEDIUM, Strength.LIGHT),
        ARRIVE(GameHaptics.ARRIVE, "arrive", Group.TRAVEL, "Arrival", Pattern.THUD, Strength.LIGHT),
        SOLAR_FLARE(GameHaptics.SOLAR_FLARE, "solar-flare", Group.TRAVEL, "Solar flare", Pattern.LONG_SWELL, Strength.MEDIUM),
        ION_PULSE(GameHaptics.ION_PULSE, "ion-pulse", Group.TRAVEL, "Pulsar's ion pulse", Pattern.BUZZ, Strength.MEDIUM),
        LIGHTNING(GameHaptics.LIGHTNING, "lightning", Group.TRAVEL, "Lightning in an ion storm", Pattern.TICK, Strength.LIGHT),
        EVENT_DAMAGE(GameHaptics.EVENT_DAMAGE, "event-damage", Group.TRAVEL, "An event damages the ship", Pattern.THUD, Strength.MEDIUM, Strength.LIGHT),
        DEFENCE_FIRE(GameHaptics.DEFENCE_FIRE, "defence-fire", Group.TRAVEL, "Planetary defence fires", Pattern.HEAVY, Strength.MEDIUM);

        /**
         * How much the moment matters when two come together: by what it is (its own kind of vibration: a click 1, a
         * warning 2, a knock 3, a hard knock or a rise 4, an ending 5), never by the kind the player picked for it.
         */
        val weight: Int get() = when (pattern) {
            Pattern.TICK, Pattern.RISE -> 1
            Pattern.DOUBLE -> 2
            Pattern.THUD, Pattern.BUZZ, Pattern.RUMBLE -> 3
            Pattern.HEAVY, Pattern.SWELL -> 4
            Pattern.LONG_SWELL -> 5
            else -> 3
        }

        /**
         * Warnings (two clicks of their own) and endings (the long rise and fall) come right behind the hit that causes
         * them, so they would nearly always find it playing: they wait for it instead of being dropped.
         */
        val patient: Boolean get() = pattern == Pattern.DOUBLE || pattern == Pattern.LONG_SWELL

        companion object {
            private val byId = entries.associateBy { it.id }
            fun of(id: Int): Moment? = byId[id]
        }
    }

    /** A set of strengths, one per moment; each moment has its own kind of vibration in every preset. */
    enum class Preset(val key: String, val title: String, val text: String) {
        TOUCH("touch", "Touch controls only", "Ticks under your fingers and nothing else: what the app did before."),
        LIGHT("light", "Light", "The touch ticks, and the moments that matter most, gently: hull hits, lost systems, warnings, explosions, the jump."),
        COMBAT("combat", "Combat", "Everything in a fight: your ship, weapons, defence, crew and the enemy. No touch ticks, no travel."),
        FULL("full", "Everything", "Every moment in the list, each at its own strength.");

        fun level(m: Moment): Strength = when (this) {
            TOUCH -> if (m.group == Group.TOUCH) m.full else Strength.OFF
            LIGHT -> m.light
            COMBAT -> if (m.group == Group.TOUCH || m.group == Group.TRAVEL) Strength.OFF else m.full
            FULL -> m.full
        }

        val levels: Map<Moment, Strength> get() = Moment.entries.associateWith { level(it) }
    }

    /**
     * What the player chose: the overall strength, each moment's own strength, and for the moments that do not play
     * their own kind of vibration the kind picked instead ([kinds] holds only those).
     */
    data class Settings(val master: Strength, val levels: Map<Moment, Strength>, val kinds: Map<Moment, Pattern> = emptyMap()) {
        fun level(m: Moment): Strength = levels[m] ?: Strength.OFF

        /** The kind of vibration the moment plays. */
        fun kind(m: Moment): Pattern = kinds[m] ?: m.pattern

        /** Whether the player picked another kind than the moment's own. */
        fun changed(m: Moment): Boolean = kind(m) != m.pattern

        /** The preset these strengths are, with every moment's own kind, or null: Custom. */
        val preset: Preset? get() = if (Moment.entries.any { changed(it) }) null else Preset.entries.firstOrNull { p -> Moment.entries.all { level(it) == p.level(it) } }

        /** A group's strength when all its moments share one, else null: mixed. */
        fun level(g: Group): Strength? = Moment.entries.filter { it.group == g }.map { level(it) }.distinct().singleOrNull()

        /** The kind every moment of the group was set to, when they share one that is not each one's own; else null. */
        fun kind(g: Group): Pattern? = Moment.entries.filter { it.group == g }.let { ms -> if (ms.none { changed(it) }) null else ms.map { kind(it) }.distinct().singleOrNull() }

        /** How many of the group's moments play another kind than their own. */
        fun changed(g: Group): Int = Moment.entries.count { it.group == g && changed(it) }

        /** Whether anything of the game itself (not the touch controls) can vibrate: the engine reports moments only then. */
        val gameMoments: Boolean get() = master != Strength.OFF && Moment.entries.any { it.group != Group.TOUCH && level(it) != Strength.OFF }

        fun with(m: Moment, s: Strength) = copy(levels = levels + (m to s))
        fun with(g: Group, s: Strength) = copy(levels = levels + Moment.entries.filter { it.group == g }.associateWith { s })

        /** Another kind for the moment; its own kind (or null) puts it back. */
        fun with(m: Moment, k: Pattern?) = copy(kinds = if (k == null || k == m.pattern) kinds - m else kinds + (m to k))

        /** The same kind for every moment of the group, as if each row were set; null puts each back to its own. */
        fun with(g: Group, k: Pattern?) = Moment.entries.filter { it.group == g }.fold(this) { s, m -> s.with(m, k) }

        /** A preset sets every strength and puts every kind back to the moment's own. */
        fun with(p: Preset) = copy(levels = p.levels, kinds = emptyMap())

        /**
         * As the preferences file holds it: a kind only where the player picked another than the moment's own. A build
         * from before the kinds reads the strengths as they are and passes over the kinds, so it plays each moment's own.
         */
        fun stored(): Map<String, String> = mapOf("strength" to master.name, MODEL to "2") + Moment.entries.associate { "level-${it.key}" to level(it).name } +
            kinds.filter { (m, k) -> k != m.pattern }.map { (m, k) -> "kind-${m.key}" to k.key }

        /** The names the file must no longer hold: the kinds of the moments that play their own. */
        fun unstored(): List<String> = Moment.entries.filter { !changed(it) }.map { "kind-${it.key}" }

        companion object {
            private const val MODEL = "model"
            val DEFAULT = Settings(Strength.MEDIUM, Preset.TOUCH.levels)

            /**
             * From what the preferences file holds. A file from before the moments had strengths has the overall strength
             * and a switch per touch action, each on until switched off: that is the Touch controls only preset, less
             * what was switched off, so an update adds no vibration. A moment a later version adds starts off the same way.
             * A file from before the kinds (2.2.0) has none: every moment plays its own; so does a kind that does not
             * read (one a later version added).
             */
            fun fromStored(all: Map<String, *>): Settings {
                val master = (all["strength"] as? String)?.let { s -> Strength.entries.firstOrNull { it.name == s } } ?: DEFAULT.master
                val old = all[MODEL] == null
                return Settings(master, Moment.entries.associateWith { m ->
                    val saved = (all["level-${m.key}"] as? String)?.let { s -> Strength.entries.firstOrNull { it.name == s } }
                    when {
                        saved != null -> saved
                        old && m.group == Group.TOUCH && all["action-${m.key}"] == false -> Strength.OFF
                        else -> Preset.TOUCH.level(m)
                    }
                }, Moment.entries.mapNotNull { m -> Pattern.of(all["kind-${m.key}"] as? String)?.takeIf { it != m.pattern }?.let { m to it } }.toMap())
            }
        }
    }

    /**
     * What the vibrator is asked to do: [clicks] click primitives at [scale], [gapMs] apart, or a waveform of [timings]
     * (ms) with an amplitude each (0: off; empty [amplitudes]: off and on in turn, starting off).
     */
    data class Plan(val kind: Kind, val clicks: Int = 0, val scale: Float = 1f, val gapMs: Int = 0,
                    val timings: List<Long> = emptyList(), val amplitudes: List<Int> = emptyList()) {
        val durationMs: Long get() = if (clicks > 0) CLICK_MS + (clicks - 1L) * (gapMs + CLICK_MS) else timings.sum()
    }

    /**
     * One at a time: while a moment plays, and for a short gap after it, only a heavier one takes over; so a volley is
     * felt as its knocks, not as one long buzz. A moment that cannot play is dropped, never queued; only a warning or an
     * ending (see [Moment.patient]) waits its turn, once, for at most [PATIENCE_MS].
     *
     * What is heavier is the moment's own importance ([priority]), never the kind of vibration picked for it. A kind
     * longer than the moment's own holds lighter moments off for as long as it plays, and a heavier one still cuts it at
     * once. A warning or an ending behind it waits for it to end when that is within [PATIENCE_MS]; when the picked kind
     * lasts longer than that, the warning takes its turn when the moment's own kind would have ended ([naturalMs]). So a
     * long vibration picked for a hit neither delays a heavier hit nor swallows the warning behind it.
     */
    class Gate(private val gapMs: Long = GATE_GAP_MS) {
        private var busyUntil = 0L
        private var yieldAt = 0L
        private var playing = 0

        /**
         * Whether a moment of [priority] lasting [durationMs] may start at [now]; if so it is the one playing.
         * [naturalMs]: how long the moment's own kind lasts, when the picked one is longer.
         */
        @Synchronized
        fun admit(now: Long, durationMs: Long, priority: Int, naturalMs: Long = durationMs): Boolean {
            if (now < busyUntil && priority <= playing) return false
            busyUntil = now + durationMs + gapMs
            yieldAt = now + minOf(durationMs, naturalMs) + gapMs
            playing = priority
            return true
        }

        /**
         * For a moment that may wait: 0 when it starts now (it is then the one playing), the ms after which to ask again
         * ([admitWaited]) when what plays ends within [patienceMs] or, being a longer kind than its moment's own, gives
         * way within that time; or -1: dropped.
         */
        @Synchronized
        fun admitOrWait(now: Long, durationMs: Long, priority: Int, patienceMs: Long, naturalMs: Long = durationMs): Long = when {
            admit(now, durationMs, priority, naturalMs) -> 0L
            busyUntil - now <= patienceMs -> busyUntil - now
            yieldAt - now <= 0 -> { take(now, durationMs, priority, naturalMs); 0L }
            yieldAt - now <= patienceMs -> yieldAt - now
            else -> -1L
        }

        /** The turn of a moment that waited: it plays unless a heavier one began meanwhile. */
        @Synchronized
        fun admitWaited(now: Long, durationMs: Long, priority: Int, naturalMs: Long = durationMs): Boolean {
            if (now < yieldAt && priority <= playing) return false
            take(now, durationMs, priority, naturalMs)
            return true
        }

        private fun take(now: Long, durationMs: Long, priority: Int, naturalMs: Long) {
            busyUntil = now + durationMs + gapMs
            yieldAt = now + minOf(durationMs, naturalMs) + gapMs
            playing = priority
        }

        /** [admit] or [admitOrWait] for a game moment as [turn] describes it: 0 plays now, more waits that long, -1 is dropped. */
        fun offer(now: Long, turn: Turn): Long = when {
            turn.patient -> admitOrWait(now, turn.plan.durationMs, turn.priority, PATIENCE_MS, turn.naturalMs)
            admit(now, turn.plan.durationMs, turn.priority, turn.naturalMs) -> 0L
            else -> -1L
        }

        /** [admitWaited] for a moment that [offer] made wait. */
        fun offerWaited(now: Long, turn: Turn): Boolean = admitWaited(now, turn.plan.durationMs, turn.priority, turn.naturalMs)

        /** A touch tick always plays, and no game moment cuts it off. */
        @Synchronized
        fun take(now: Long, durationMs: Long) {
            busyUntil = maxOf(busyUntil, now + durationMs)
            yieldAt = busyUntil
            playing = Int.MAX_VALUE
        }
    }

    /**
     * A game moment about to play: what the vibrator gets ([plan]: the picked kind at the moment's strength), how long the
     * moment's own kind would last, its rank among moments that come together and whether it waits for its turn.
     */
    data class Turn(val plan: Plan, val naturalMs: Long, val priority: Int, val patient: Boolean)

    val strength: Strength get() = settings.master

    /** False while the app is in the background: nothing of the game vibrates then. */
    @Volatile var foreground = true
    private val gate = Gate()

    /**
     * Plays a TouchHaptics kind ([count] ticks) or a GameHaptics moment ([count]: how much of it, as hull points), or
     * [SAMPLE]. Called on the engine's game thread; a game moment reaches the vibrator from a thread of its own.
     */
    fun play(what: Int, count: Int) {
        val moment = Moment.of(what)
        val level = if (what == SAMPLE) Strength.STRONG else moment?.let { settings.level(it) } ?: Strength.OFF
        val pattern = moment?.let { settings.kind(it) } ?: Pattern.TICK
        val touch = moment == null || moment.group == Group.TOUCH
        val plan = if (touch || foreground) planFor(what, count, settings, kind, amplitude) else null
        if (plan == null) {
            if (BuildConfig.DEBUG && count > 0 && moment != null && level == Strength.OFF) Log.i(TAG, "skip kind=$what ${moment.key} count=$count: off")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (touch) {
            gate.take(now, plan.durationMs)
            if (BuildConfig.DEBUG) Log.i(TAG, "play kind=$what ${moment?.key ?: "sample"} count=$count strength=$strength level=$level feel=${pattern.key} $plan")
            vibrate(plan, true)
            return
        }
        val turn = turn(moment, count, settings, kind, amplitude) ?: return
        val wait = gate.offer(now, turn)
        if (wait < 0) {
            if (BuildConfig.DEBUG) Log.i(TAG, "drop kind=$what ${moment.key} count=$count: a heavier one is playing")
            return
        }
        if (wait > 0) {
            // A warning or an ending right behind the hit that caused it: after the hit, if it is its turn then.
            if (BuildConfig.DEBUG) Log.i(TAG, "wait kind=$what ${moment.key} count=$count: $wait ms")
            worker.postDelayed({
                if (foreground && gate.offerWaited(SystemClock.elapsedRealtime(), turn)) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "play kind=$what ${moment.key} count=$count strength=$strength level=$level feel=${pattern.key} $plan (waited)")
                    vibrate(plan, false)
                } else if (BuildConfig.DEBUG) Log.i(TAG, "drop kind=$what ${moment.key} count=$count: a heavier one is playing (waited)")
            }, wait)
            return
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "play kind=$what ${moment.key} count=$count strength=$strength level=$level feel=${pattern.key} $plan")
        worker.post { if (SystemClock.elapsedRealtime() - now <= STALE_MS) vibrate(plan, false) }
    }

    /** Settings: what [pattern] feels like at [level] (for [moment], or for a group's shortcut), whatever is saved. */
    fun sample(moment: Moment?, pattern: Pattern, level: Strength) {
        val plan = plan(pattern, settings.master, level, kind, amplitude, 1) ?: return
        if (BuildConfig.DEBUG) Log.i(TAG, "sample ${moment?.key ?: "group"} strength=$strength level=$level feel=${pattern.key} $plan")
        vibrate(plan, moment?.group == Group.TOUCH)
    }

    /** What [pattern] plays as on this device when it cannot be itself here (a motor that is only on or off), else null. */
    fun playsAs(pattern: Pattern): Pattern? = playsAs(pattern, kind, amplitude)

    private fun vibrate(plan: Plan, touch: Boolean) {
        val v = vibrator ?: return
        val effect = when {
            plan.clicks > 0 -> if (Build.VERSION.SDK_INT >= 30) VibrationEffect.startComposition().apply {
                repeat(plan.clicks) { i -> addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, plan.scale, if (i == 0) 0 else plan.gapMs) }
            }.compose() else return
            plan.timings.isEmpty() -> return
            plan.amplitudes.isNotEmpty() -> VibrationEffect.createWaveform(plan.timings.toLongArray(), plan.amplitudes.toIntArray(), -1)
            else -> VibrationEffect.createWaveform(plan.timings.toLongArray(), -1)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                v.vibrate(effect, VibrationAttributes.createForUsage(if (touch) VibrationAttributes.USAGE_TOUCH else VibrationAttributes.USAGE_MEDIA))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder()
                .setUsage(if (touch) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION else AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "vibrate failed: $it") }
    }

    companion object {
        const val TAG = "FTL-Haptic"
        private const val PREFS = "haptics"
        private const val GAP_MS = 45
        private const val CLICK_MS = 20L
        const val GATE_GAP_MS = 60L
        /** The longest a warning or an ending waits for its turn. */
        const val PATIENCE_MS = 500L
        /** A moment the vibrator's thread could not start within this long is not played late. */
        private const val STALE_MS = 150L
        /** A flick moves a whole bar at once: at most this many ticks. */
        private const val MAX_TICKS = 16
        val DEFAULT = Strength.MEDIUM

        /** The tick Settings plays when a strength is chosen: no moment of its own, so nothing switched off holds it back. */
        const val SAMPLE = -1

        private val worker by lazy { Handler(HandlerThread("ftl-haptics").apply { start() }.looper) }

        /**
         * Which of two moments at once is felt: the one that matters more ([Moment.weight]; what happens to your own ship
         * counts two steps heavier, to your crew one: a hull hit is felt through your own flak), then the stronger
         * setting. The kind of vibration picked for a moment does not count.
         */
        fun priority(moment: Moment, level: Strength): Int =
            moment.weight * 10 + level.ordinal + when (moment.group) { Group.SHIP -> 20; Group.CREW -> 10; else -> 0 }

        /** What [pattern] plays as on a vibrator that is only on or off (or takes no amplitudes), when it cannot be itself there; else null. */
        fun playsAs(pattern: Pattern, kind: Kind, amplitude: Boolean): Pattern? = pattern.finer?.takeIf { kind == Kind.ON_OFF || !amplitude }

        /** [moment] as the [Gate] sees it with these [settings], or null when it plays nothing. */
        fun turn(moment: Moment, count: Int, settings: Settings, kind: Kind, amplitude: Boolean): Turn? {
            val plan = planFor(moment.id, count, settings, kind, amplitude) ?: return null
            // How long the moment's own kind would last: a longer one picked for it does not make the others wait longer.
            val natural = if (settings.changed(moment)) planFor(moment.id, count, settings.with(moment, null), kind, amplitude)?.durationMs ?: plan.durationMs else plan.durationMs
            return Turn(plan, natural, priority(moment, settings.level(moment)), moment.patient)
        }

        /** The share of the vibrator's full strength: the overall strength's (as the ticks always had it) times the moment's own. */
        fun intensity(master: Strength, level: Strength): Float {
            if (master == Strength.OFF || level == Strength.OFF) return 0f
            return floatArrayOf(0.35f, 0.65f, 1f)[master.ordinal - 1] * floatArrayOf(0.5f, 0.75f, 1f)[level.ordinal - 1]
        }

        private fun lerp(e: Float, points: List<Pair<Float, Float>>): Float {
            val hi = points.indexOfFirst { e <= it.first }.let { if (it < 0) points.lastIndex else it.coerceAtLeast(1) }
            val (x0, y0) = points[hi - 1]
            val (x1, y1) = points[hi]
            return y0 + (y1 - y0) * ((e - x0) / (x1 - x0)).coerceIn(0f, 1f)
        }

        /** The amplitude (1..255) for a share of full strength; 90, 170 and 255 at the three overall strengths. */
        fun amplitude(e: Float): Int = Math.round(lerp(e, listOf(0f to 0f, 0.35f to 90f, 0.65f to 170f, 1f to 255f))).coerceIn(1, 255)

        private fun tickMs(e: Float): Long = Math.round(lerp(e, listOf(0f to 8f, 0.35f to 10f, 0.65f to 15f, 1f to 20f))).toLong()

        /**
         * What to play for a moment of [pattern] at the overall strength [master] and its own [level] on a vibrator of
         * [kind] ([amplitude]: it takes amplitudes); null: nothing. [count] is the number of ticks for a tick, and how much
         * of it there was for the others (hull points: each one more makes the knock a little firmer and longer).
         */
        fun plan(pattern: Pattern, master: Strength, level: Strength, kind: Kind, amplitude: Boolean, count: Int): Plan? {
            val base = intensity(master, level)
            if (base <= 0f || kind == Kind.NONE || count <= 0) return null
            // On or off only: a kind that needs amplitudes to be itself plays as the nearest that does not.
            playsAs(pattern, kind, amplitude)?.let { return plan(it, master, level, kind, amplitude, if (it == Pattern.TICK) 1 else count) }
            val more = if (pattern == Pattern.TICK) 0 else (count - 1).coerceIn(0, 5)
            val e = (base * (1f + 0.12f * more)).coerceIn(0.15f, 1f)
            if (pattern == Pattern.TICK || pattern == Pattern.DOUBLE || pattern == Pattern.TRIPLE || pattern == Pattern.RISE) {
                val n = when (pattern) { Pattern.TICK -> count.coerceAtMost(MAX_TICKS); Pattern.TRIPLE -> 3; else -> 2 }
                val gap = if (pattern == Pattern.TICK) GAP_MS else 90
                return when {
                    // RISE's two strengths need amplitudes; a vibrator with clicks only plays it as two clicks.
                    kind == Kind.PRIMITIVES && (pattern != Pattern.RISE || !amplitude) -> Plan(kind, clicks = n, scale = e, gapMs = gap)
                    kind == Kind.AMPLITUDE || kind == Kind.PRIMITIVES -> {
                        // Off, then a pulse and a gap per tick.
                        val ms = tickMs(e)
                        Plan(Kind.AMPLITUDE,
                            timings = List(n * 2) { if (it == 0) 0L else if (it % 2 == 1) ms else gap.toLong() },
                            amplitudes = List(n * 2) { i -> if (i % 2 == 0) 0 else if (pattern == Pattern.RISE && i == 1) amplitude(e * 0.5f) else amplitude(e) })
                    }
                    else -> Plan(Kind.ON_OFF, timings = List(n * 2) { if (it == 0) 0L else if (it % 2 == 1) 20L else gap.toLong() })
                }
            }
            if (kind == Kind.ON_OFF || !amplitude) {
                // On or off only: the pattern's rhythm, off and on in turn.
                val onOff: List<Long> = when (pattern) {
                    Pattern.THUD -> listOf(0L, 30L + 5 * more)
                    Pattern.DOUBLE_KNOCK -> listOf(0L, 40L, 110L, 40L)
                    Pattern.HEAVY -> listOf(0L, 70L)
                    Pattern.HEARTBEAT -> listOf(0L, 30L, 70L, 45L, 260L, 30L, 70L, 45L)
                    Pattern.BUZZ -> listOf(0L) + List(11) { 12L }
                    Pattern.HUM -> listOf(0L, 120L)
                    Pattern.LONG -> listOf(0L, 400L)
                    Pattern.RUMBLE -> listOf(0L, 25L, 45L, 25L, 45L, 25L)
                    else -> listOf(0L, 60L, 40L, 250L)
                }
                return Plan(Kind.ON_OFF, timings = onOff)
            }
            // Steps of (ms, share of this moment's strength).
            val steps: List<Pair<Long, Float>> = when (pattern) {
                Pattern.SOFT -> listOf(30L to 0.45f)
                Pattern.THUD -> listOf(35L + 8 * more to 1f)
                Pattern.DOUBLE_KNOCK -> listOf(35L to 1f, 110L to 0f, 35L to 1f)
                Pattern.HEAVY -> listOf(60L to 1f, 40L to 0.5f, 30L to 0.25f)
                Pattern.HEARTBEAT -> listOf(35L to 0.7f, 70L to 0f, 45L to 1f, 260L to 0f, 35L to 0.7f, 70L to 0f, 45L to 1f)
                Pattern.BUZZ -> List(15) { 12L to if (it % 2 == 0) 0.7f else 0f }
                Pattern.HUM -> listOf(120L to 0.8f)
                Pattern.LONG -> listOf(400L to 0.85f)
                Pattern.RUMBLE -> List(10) { 30L to if (it % 2 == 0) 0.6f else 0.3f }
                Pattern.SWELL -> List(6) { 30L to 0.2f + 0.16f * it }
                Pattern.FADE -> List(7) { 40L to floatArrayOf(1f, 0.8f, 0.62f, 0.46f, 0.32f, 0.2f, 0.1f)[it] }
                else -> List(8) { 40L to 0.15f + 0.12f * it } + (120L to 1f) + List(4) { 50L to 0.7f - 0.17f * it }
            }
            return Plan(Kind.AMPLITUDE, timings = steps.map { it.first }, amplitudes = steps.map { (_, share) -> if (share <= 0f) 0 else amplitude(e * share) })
        }

        /** What [count] of a TouchHaptics kind, a GameHaptics moment or [SAMPLE] plays with these [settings]; null: nothing. */
        fun planFor(what: Int, count: Int, settings: Settings, kind: Kind, amplitude: Boolean): Plan? {
            val moment = Moment.of(what)
            val level = if (what == SAMPLE) Strength.STRONG else moment?.let { settings.level(it) } ?: Strength.OFF
            val pattern = moment?.let { settings.kind(it) } ?: Pattern.TICK
            // The count is a number of ticks for a moment that is a tick of its own, and how much of it there was for the
            // others (hull points): it goes to a picked kind of the same sort, and any other plays once.
            val own = moment?.pattern ?: Pattern.TICK
            val n = if ((own == Pattern.TICK) == (pattern == Pattern.TICK)) count else count.coerceAtMost(1)
            return plan(pattern, settings.master, level, kind, amplitude, n)
        }

        /** The overall strengths the setting offers on a vibrator of [kind]: none, on or off, or each strength. */
        fun choices(kind: Kind): List<Strength> = when (kind) {
            Kind.NONE -> emptyList()
            Kind.ON_OFF -> listOf(Strength.OFF, DEFAULT)
            else -> Strength.entries
        }

        /** How the chosen strength reads on a vibrator of [kind]. */
        fun label(strength: Strength, kind: Kind): String = when {
            kind == Kind.NONE -> "Not available"
            strength == Strength.OFF -> "Off"
            kind == Kind.ON_OFF -> "On"
            else -> strength.label
        }

        /** Settings' Haptics row: the overall strength and what vibrates. */
        fun summary(settings: Settings, kind: Kind): String {
            val label = label(settings.master, kind)
            if (kind == Kind.NONE || settings.master == Strength.OFF) return label
            return "$label · " + (settings.preset?.title ?: "Custom")
        }

        private fun vibratorOf(context: Context): Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }.getOrNull()?.takeIf { it.hasVibrator() }

        private fun kindOf(context: Context, vibrator: Vibrator?): Kind {
            if (BuildConfig.DEBUG) testKind(context)?.let { return it }
            return when {
                vibrator == null -> Kind.NONE
                Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> Kind.PRIMITIVES
                vibrator.hasAmplitudeControl() -> Kind.AMPLITUDE
                else -> Kind.ON_OFF
            }
        }

        /** Debug builds: the console's `hapticdevice` pretends a weaker vibrator, for the setting's other texts. */
        private fun testKind(context: Context): Kind? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("kind-for-tests", null)?.let { k -> Kind.entries.firstOrNull { it.name == k } }

        fun setTestKind(context: Context, kind: Kind?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("kind-for-tests", kind?.name).apply()
        }

        fun saved(context: Context): Settings = Settings.fromStored(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all)

        fun save(context: Context, settings: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                for ((k, v) in settings.stored()) putString(k, v)
                for (k in settings.unstored()) remove(k)
            }.apply()
        }

        /** This device's haptics with the saved settings, or with [settings] (a sample in Settings). */
        fun load(context: Context, settings: Settings = saved(context)): Haptics {
            val vibrator = vibratorOf(context)
            val kind = kindOf(context, vibrator)
            val amplitude = when (kind) {
                Kind.AMPLITUDE -> true
                Kind.PRIMITIVES -> vibrator?.hasAmplitudeControl() ?: true
                else -> false
            }
            return Haptics(vibrator, kind, amplitude, settings)
        }
    }
}
