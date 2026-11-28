package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.ActivatedPower
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.HsCrewUi
import xyz.znix.xftl.hs.crew.PowerReadyState
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.hs.crew.luaKill
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.hs.ship.HsAugments
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.StatBoost
import xyz.znix.xftl.hs.stat.StatBoostDefs
import xyz.znix.xftl.hs.stat.StatBoostManager
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * Ability sweep (r27-powers): every crew power, passive stat boost, stat boost definition and augment of the loaded
 * content, exercised in the engine, failing on any exception, a stuck or invalid state or a save that does not load
 * back the same. The oracle comparison of the same items is the r27-powers driver (tools/hs-oracle, r27-powers.lua);
 * this is the fast exercise the gate keeps running.
 *
 * Cases (`abilitySweepProbe(i)`, one record per case in web/diagnostics.mjs):
 * - `races a-b`: per race with a power or passive stat boosts (CustomCrewManager), four copies in a fight as the
 *   oracle driver places them (P player crew at home, J enemy boarder next to it, E enemy crew at home, Q player
 *   boarder next to it, plus a human of each side in each room). Cooldowns full, P and Q press their ready powers
 *   through the crew box button (HsCrewUi.clickButton), E and J left to the AI; 1.5 s, a save and load (every
 *   power's state must come back), one transition by turn (none, the enemy escaping, destroyed, the player jumping
 *   away, a sector change), 1.5 s, every temporary effect ended, then all of them killed and gone.
 * - `augment powers`: each augment whose stat boosts give a power, with a crew member of the race it targets.
 * - `augments a-b`: each augment blueprint on the player's ship (cargo and hidden) and the enemy's, its values
 *   (GET_AUGMENTATION_VALUE of its name and its functions), its texts, a save and load with it installed.
 * - `boosts a-b`: each stat boost definition applied to a crew member of each side as a timed boost
 *   (StatBoostManager.createTimedAugmentBoost), every stat computed, two frames run.
 * Invalid: a NaN or infinite health, stat or power number, negative health, a cooldown or duration outside its range,
 * charges over their maximum, a race with no blueprint, an empty button text on a shown power, an untranslated text.
 */
object BrowserAbilitySweepDiagnostics {
    private const val DT = 1f / 60f
    private const val RACES_PER_CASE = 12
    private const val AUGS_PER_CASE = 70
    private const val BOOSTS_PER_CASE = 240

    /** Sprites and animations the content names but does not have (the original shows none either). */
    private val DATA_GAPS = setOf<String>()

    private class Failure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private sealed class Case(val label: String) {
        class Races(val races: List<String>, label: String) : Case(label)
        class AugPowers(val list: List<Pair<String, String>>) : Case("augment powers (${list.size})")
        class Augments(val names: List<String>, label: String) : Case(label)
        class Boosts(val from: Int, val to: Int) : Case("boosts $from-${to - 1}")
    }

    private var cases: List<Case>? = null
    private var casesKey: Any? = null

    private fun cases(main: MainGame): List<Case> {
        val key = Pair(HS.active, CustomCrewManager.races.size)
        cases?.let { if (casesKey == key) return it }
        val out = ArrayList<Case>()
        if (HS.active) {
            val races = CustomCrewManager.races.filter { r ->
                val d = CustomCrewManager.definition(r)
                d.powerDefs.isNotEmpty() || d.passiveStatBoosts.isNotEmpty()
            }
            for (i in races.indices step RACES_PER_CASE) {
                val part = races.subList(i, minOf(races.size, i + RACES_PER_CASE))
                out += Case.Races(part, "races ${i + 1}-${i + part.size} (${part.first()}..${part.last()})")
            }
            val augPowers = augmentPowers(augmentNames(main))
            if (augPowers.isNotEmpty()) out += Case.AugPowers(augPowers)
        }
        val augs = augmentNames(main)
        for (i in augs.indices step AUGS_PER_CASE) {
            val part = augs.subList(i, minOf(augs.size, i + AUGS_PER_CASE))
            out += Case.Augments(part, "augments ${i + 1}-${i + part.size} (${part.first()}..${part.last()})")
        }
        if (HS.active) {
            val n = StatBoostDefs.defs.size
            for (i in 0 until n step BOOSTS_PER_CASE) out += Case.Boosts(i, minOf(n, i + BOOSTS_PER_CASE))
        }
        cases = out
        casesKey = key
        return out
    }

    /** The augment blueprints of the loaded content: the blueprint manager's, by name. */
    private fun augmentNames(main: MainGame): List<String> {
        val bm = (main.currentState as? InGameState)?.blueprintManager ?: main.getHangarBlueprints(true)
        return bm.blueprints.values.filterIsInstance<AugmentBlueprint>().map { it.name }.distinct().sorted()
    }

    /** (augment, race) for every augment stat boost that gives a power: the first race of its white list, or human. */
    private fun augmentPowers(names: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (name in names) {
            val d = HsAugments.definition(name) ?: continue
            for (sb in d.statBoosts) for (b in sb.getChildren("statBoost")) {
                if (b.getAttributeValue("name") != "powerEffect" || b.getChild("powerEffect") == null) continue
                val race = b.getChildren("whiteList").flatMap { it.children }.map { it.name }.firstOrNull() ?: "human"
                out += name to race
            }
        }
        return out
    }

    fun count(main: MainGame): Int = cases(main).size

    fun name(main: MainGame, i: Int): String = cases(main)[i].label

    private class Ctx(val main: MainGame, val container: GameContainer) {
        lateinit var state: InGameState
        var item = ""
        var phase = ""
        var frames = 0
        var trips = 0
        var transitions = 0
        val notes = ArrayList<String>()
        val s get() = state
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, index: Int): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val case = cases(main)[index]
        val c = Ctx(main, container)
        try {
            BrowserRandom.restore((27_000 + index).toString())
            NativeRandom.seed(27_000 + index)
            val luaBefore = if (HS.luaActive) xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() else emptySet()
            val gapsBefore = HashSet(HS.capability.keys)
            val imagesBefore = HashSet(InGameState.MISSING_IMAGES)
            val ship = if (HS.active && main.getHangarBlueprints(true).getOrNull("PLAYER_SHIP_MVKESTREL") != null) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD"
            c.state = BrowserGameplayParityDiagnostics.fresh(main, container, ship)
            val n = when (case) {
                is Case.Races -> { for (r in case.races) race(c, r, null); case.races.size }
                is Case.AugPowers -> { for ((a, r) in case.list) race(c, r, a); case.list.size }
                is Case.Augments -> { augments(c, case.names); case.names.size }
                is Case.Boosts -> { boosts(c, case.from, case.to); case.to - case.from }
            }
            if (HS.luaActive) {
                val lua = xyz.znix.xftl.hs.lua.HsLua.engineErrorKeys() - luaBefore
                if (lua.isNotEmpty()) throw Failure("an engine error inside a Lua call (${lua.joinToString(" | ").take(600)})")
            }
            // Hyperspace features the engine reported as missing while these items ran (a power's animation, a race):
            // listed, since the data may name what the original lacks too (checked against the data by the report)
            val gaps = HS.capability.keys - gapsBefore
            // A sprite or animation the items name and the engine found nothing for: an image path with no file, or
            // a power's animation with no <anim> (CrewPowers.anim). Failed unless the loaded data lacks it as well
            // ([DATA_GAPS]: names checked against the merged Multiverse data, where the original has none either).
            // A destroyed ship's gib images missing from the data (MV's mu_fed_bomber names gib1-4 and ships none):
            // ShipGib falls back to the glow path, so the miss shows there; the data has neither file.
            val gib = Regex("""img/ships_glow/.+_gib\d+\.png""")
            val images = (InGameState.MISSING_IMAGES - imagesBefore).filter { it !in DATA_GAPS }
                .filter { !(gib.matches(it) && c.s.getImgIfExists(it.replace("img/ships_glow/", "img/ship/")) == null) }
            val anims = gaps.filter { it.startsWith("anim|") }.map { it.substringAfterLast('|') }.filter { it !in DATA_GAPS }
            if (images.isNotEmpty() || anims.isNotEmpty())
                throw Failure("missing sprites ${images.sorted().take(12)} / animations ${anims.sorted().take(12)}")
            val other = gaps.filter { !it.startsWith("anim|") }
            if (other.isNotEmpty()) c.notes += "new capability gaps: " + other.sorted().joinToString(" ; ")
            return "PASS ability sweep ${case.label}: $n items, ${c.frames} frames, ${c.trips} save/loads, ${c.transitions} transitions" +
                    (if (c.notes.isEmpty()) "" else "; " + c.notes.distinct().joinToString(", ").take(4000))
        } catch (e: Throwable) {
            println("TEST ability-sweep FAIL at ${c.item} (${c.phase}): ${e::class.simpleName}: ${e.message?.take(1500)} / ${e.cause?.let { it::class.simpleName + ": " + it.message?.take(600) }}")
            try { BrowserGameplayParityDiagnostics.fresh(main, container) } catch (ignored: Throwable) {}
            var root: Throwable = (e as? Failure)?.cause ?: e
            while (root is xyz.znix.xftl.savegame.RunLoadException && root.cause != null) root = root.cause!!
            val msg = "ability sweep ${case.label} at ${c.item} (${c.phase}): ${(e as? Failure)?.message ?: ""} " +
                    (if (e is Failure && e.cause == null) "" else "${root::class.simpleName}: ${root.message} @ ${BrowserJsStack.of(root).lines().take(18).joinToString(" < ") { it.trim() }}")
            // also as a live line: a later page crash loses the record output
            println("TEST ability-sweep FAIL " + msg.take(1500))
            throw IllegalStateException(msg)
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    // ---- frames and checks ----------------------------------------------------------------------------------------

    private inline fun guard(c: Ctx, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Failure) {
            throw e
        } catch (e: Throwable) {
            throw Failure("$what", e)
        }
    }

    private fun step(c: Ctx, n: Int = 1, check: Boolean = true) {
        repeat(n) {
            guard(c, "the frame") { BrowserEngine.step(DT) }
            c.frames++
            val st = c.main.currentState as? InGameState ?: throw Failure("left the run")
            c.state = st
            if (st.browserRunEnded) throw Failure("the run ended")
            if (st.shipUI.diagnosticDialogue != null || st.shipUI.isWindowOpen) {
                guard(c, "closing a box") { BrowserDiagnostics.closeBoxes(c.main, c.container) }
            }
            // another window (a store or map an ending opened) pauses the game too: noted and closed
            if (st.shipUI.isWindowOpen && st.shipUI.diagnosticDialogue == null && !st.shipUI.isGameOverOpen) {
                c.notes += "${c.item} ${c.phase} frame ${c.frames}: closed ${st.shipUI.diagnosticWindows}"
                st.shipUI.diagnosticCloseWindow()
            }
            if (st.isPaused && !st.shipUI.isWindowOpen) st.toggleQuickPause()
            if (st.player.health < st.player.maxHealth / 2) st.player.health = st.player.maxHealth
            if (check) checkCrew(c, false)
        }
        if (c.frames % 5 == 0) guard(c, "drawing") { BrowserEngine.render() }
    }

    private fun ships(s: InGameState): List<Ship> = listOfNotNull(s.player, s.enemy)

    private fun fin(v: Float) = !v.isNaN() && !v.isInfinite()

    private fun checkCrew(c: Ctx, stats: Boolean) {
        val bm = c.s.blueprintManager
        for (ship in ships(c.s)) for (crew in ship.crew.toList()) {
            val who = "${crew.codename} '${crewName(crew)}' on ${if (ship === c.s.player) "player" else "enemy"}"
            if (!fin(crew.health)) throw Failure("$who: health ${crew.health}")
            if (crew.health < -0.01f) throw Failure("$who: negative health ${crew.health}")
            if (crew is xyz.znix.xftl.crew.LivingCrew && bm.getOrNull(crew.codename) !is CrewBlueprint) throw Failure("$who: no crew blueprint for its race")
            if (!HS.active || !CustomCrewManager.isRace(crew.codename)) continue
            val ex = HsCrew.ex(crew)
            for (p in ex.crewPowers) checkPower(who, p)
            if (stats) {
                val def = ex.definition()
                for (stat in CrewStat.values()) {
                    val v = guard2(c, "$who stat $stat") { ex.calculateStat(stat, def) }
                    if (!fin(v)) throw Failure("$who: stat $stat = $v")
                }
            }
        }
    }

    private inline fun <T> guard2(c: Ctx, what: String, block: () -> T): T {
        try {
            return block()
        } catch (e: Failure) {
            throw e
        } catch (e: Throwable) {
            throw Failure(what, e)
        }
    }

    private fun checkPower(who: String, p: ActivatedPower) {
        val n = "$who power ${p.def.name}"
        val cd = p.powerCooldown
        if (!fin(cd.first) || !fin(cd.second)) throw Failure("$n: cooldown ${cd.first}/${cd.second}")
        if (cd.second < 0f || cd.first < -0.001f || cd.first > cd.second + 0.001f) throw Failure("$n: cooldown ${cd.first} outside 0..${cd.second}")
        val t = p.temporaryPowerDuration
        if (!fin(t.first) || !fin(t.second) || t.first < -0.001f) throw Failure("$n: duration ${t.first}/${t.second}")
        if (p.temporaryPowerActive && t.first > t.second + 0.001f) throw Failure("$n: duration ${t.first} over ${t.second}")
        val ch = p.powerCharges
        if (ch.second >= 0 && (ch.first > ch.second || ch.first < 0)) throw Failure("$n: charges ${ch.first}/${ch.second}")
        for (r in p.powerResources) {
            if (!fin(r.powerCooldown.first) || !fin(r.powerCooldown.second)) throw Failure("$n: resource cooldown ${r.powerCooldown.first}")
            if (r.powerCharges.second >= 0 && (r.powerCharges.first > r.powerCharges.second || r.powerCharges.first < 0))
                throw Failure("$n: resource charges ${r.powerCharges.first}/${r.powerCharges.second}")
        }
    }

    /** A live progress line (the baseline prints `TEST ` console lines as they come): item, phase, crew aboard. */
    private fun progress(c: Ctx) {
        val crew = ships(c.s).joinToString("/") { it.crew.size.toString() }
        println("TEST ability-sweep ${c.item} ${c.phase}: crew $crew, frame ${c.frames}")
    }

    private fun crewName(c: AbstractCrew): String = (c as? xyz.znix.xftl.crew.LivingCrew)?.info?.name ?: ""

    // ---- save and load ----------------------------------------------------------------------------------------------

    /** One crew member as a save must bring it back: race, side, health, and per power its state. */
    private class Snap(val key: String, val health: Float, val powers: List<FloatArray>, val text: String)

    /** Every R27 crew member by name (race, side), in a stable order. */
    private fun snapshot(s: InGameState): Map<String, Snap> {
        val out = java.util.TreeMap<String, Snap>()
        for (ship in ships(s)) for (crew in ship.crew) {
            val name = crewName(crew)
            if (!name.startsWith("R27")) continue
            val side = if (ship === s.player) 'p' else 'e'
            val powers = ArrayList<FloatArray>()
            val sb = StringBuilder().append(crew.codename).append(' ').append(side).append(' ').append(Math.round(crew.health * 10f) / 10f)
            if (HS.active && CustomCrewManager.isRace(crew.codename)) {
                for (p in HsCrew.ex(crew).crewPowers) {
                    powers += floatArrayOf(if (p.enabled) 1f else 0f, p.powerCooldown.first, p.powerCooldown.second,
                        p.powerCharges.first.toFloat(), p.powerCharges.second.toFloat(), if (p.temporaryPowerActive) 1f else 0f,
                        p.temporaryPowerDuration.first)
                    sb.append(" | ").append(p.def.name).append(' ').append(p.enabled).append(' ')
                        .append(Math.round(p.powerCooldown.first * 100f) / 100f).append('/').append(Math.round(p.powerCooldown.second * 100f) / 100f)
                        .append(' ').append(p.powerCharges.first).append('/').append(p.powerCharges.second)
                        .append(' ').append(p.temporaryPowerActive).append(' ')
                        .append(Math.round(p.temporaryPowerDuration.first * 100f) / 100f)
                }
            }
            var k = "$name/${crew.codename}/$side"
            var n = 0
            while (out.containsKey(k)) k = "$name/${crew.codename}/$side/${++n}"
            out[k] = Snap(k, crew.health, powers, sb.toString())
        }
        return out
    }

    /**
     * Whether [a] (before the save) and [b] (the loaded game one frame later) are the same crew and powers: the same
     * names, races and sides; health within what one frame of fighting or of a maximum-health boost settling can move
     * it (5 HP and 10%); per power the same maximum cooldown and charges, enabled, and its cooldown, charges and
     * temporary effect where that frame could have moved them (a recharge step, or the power starting).
     */
    private fun same(a: Snap, b: Snap): Boolean {
        if (Math.abs(a.health - b.health) > 5f + 0.1f * Math.max(Math.abs(a.health), Math.abs(b.health))) return false
        if (a.powers.size != b.powers.size) return false
        for (i in a.powers.indices) {
            val x = a.powers[i]; val y = b.powers[i]
            if (x[2] != y[2] || x[4] != y[4]) return false
            val started = x[5] == 0f && y[5] == 1f
            val ended = x[5] == 1f && y[5] == 0f && x[6] <= 2 * DT
            if (!started && !ended) {
                if (x[0] != y[0] || x[5] != y[5]) return false
                if (x[3] != y[3]) return false
                if (Math.abs(x[1] - y[1]) > 2 * DT + 1e-3f && !(y[1] == 0f && x[1] >= x[2] - 1e-3f)) return false
                if (x[5] == 1f && Math.abs(x[6] - y[6]) > 2 * DT + 1e-3f) return false
            }
        }
        return true
    }

    /** The saved crew members whose action is MANNING: race, position, and the rooms of either ship holding it. */
    private fun consoleCrew(s: InGameState, xml: String): List<String> {
        val out = ArrayList<String>()
        fun walk(e: org.jdom2.Element, path: String) {
            if (e.name == "crewMember" && e.getAttributeValue("action") == "MANNING") {
                val x = e.getAttributeValue("x")?.toFloatOrNull()?.toInt() ?: 0
                val y = e.getAttributeValue("y")?.toFloatOrNull()?.toInt() ?: 0
                val rooms = ships(s).mapNotNull { sh ->
                    val r = sh.rooms.firstOrNull { it.containsShipSpace(xyz.znix.xftl.math.ConstPoint(x + 17, y + 17)) } ?: return@mapNotNull null
                    "${if (sh === s.player) "p" else "e"} room ${r.id} system ${r.system?.codename}"
                }
                out += "$path: " + e.attributes.joinToString(" ") { "${it.name}=${it.value}" }.take(300) + " " + rooms +
                        " children " + e.children.joinToString(",") { c -> c.name + c.attributes.joinToString("") { " ${it.name}=${it.value}" } }.take(200)
            }
            val here = if (e.name == "crewMember") path else path + "/" + e.name + (e.getAttributeValue("displayName")?.let { "[$it]" } ?: "")
            for (c in e.children) walk(c, here)
        }
        walk(BrowserXml.parse(xml).rootElement, "")
        return out.take(8)
    }

    private fun trip(c: Ctx) {
        val before = snapshot(c.s)
        c.phase = "save"
        guard(c, "saving") { c.main.writeRunSave() }
        val xml = BrowserFiles.getRun() ?: throw Failure("no run save written")
        c.phase = "load"
        try {
            guard(c, "loading") { c.main.loadSavedGame(BrowserXml.parse(xml)) }
        } catch (f: Failure) {
            throw Failure("${f.message}; saved crew at a console: ${consoleCrew(c.s, xml)}", f.cause)
        }
        val loaded = c.main.currentState as? InGameState ?: throw Failure("the load left the run")
        if (loaded.loadDroppedRefs.isNotEmpty() || loaded.saveMigrationLines.isNotEmpty())
            throw Failure("a save of this build needed repairs: ${loaded.loadDroppedRefs} ${loaded.saveMigrationLines}")
        loaded.debugFlags.noEnemyFire.set = true
        c.state = loaded
        if (loaded.shipUI.isWindowOpen) c.notes += "${c.item} after its load: ${loaded.shipUI.diagnosticWindows}"
        c.trips++
        if (loaded.isPaused && !loaded.shipUI.isWindowOpen) loaded.toggleQuickPause()
        // one frame: the loaded crew's boosts (room boosts, maximum health) are applied by the stat loop
        step(c, 1)
        val after = snapshot(c.s)
        val diff = (before.keys + after.keys).filter { k -> val a = before[k]; val b = after[k]; a == null || b == null || !same(a, b) }
        if (diff.isNotEmpty()) {
            throw Failure("the save did not load back the same crew and powers: " +
                    diff.take(3).joinToString(" ; ") { "before ${before[it]?.text} after ${after[it]?.text}" })
        }
    }

    // ---- the fight --------------------------------------------------------------------------------------------------

    /** A room of [ship] with four slots and a system (the shields room first), else any room with a system. */
    private fun subjectRoom(ship: Ship): Room? {
        val withSystem = ship.rooms.filter { r -> ship.systems.any { it.room === r } }
        return withSystem.firstOrNull { it.width * it.height >= 4 && ship.shields?.room === it }
            ?: withSystem.firstOrNull { it.width * it.height >= 4 }
            ?: withSystem.firstOrNull() ?: ship.rooms.firstOrNull()
    }

    private fun ensureEnemy(c: Ctx) {
        val s = c.s
        if (s.enemy != null && !s.enemy!!.isDestroyed()) return
        val specs = s.eventManager.getShips().filter { it.name.isNotEmpty() }.sortedBy { it.name }
        for (attempt in 0 until 40) {
            val spec = specs[(attempt * 97 + 13) % specs.size]
            guard(c, "spawning the enemy ${spec.name}") { s.debugSpawnShip(spec, Difficulty.NORMAL, 2, 27_100 + attempt) }
            val e = s.enemy ?: continue
            val room = subjectRoom(e)
            if (room == null || room.width * room.height < 4 || e.rooms.size < 3) continue
            e.health = e.maxHealth
            step(c, 2)
            return
        }
        throw Failure("no enemy ship with a four-slot system room in 40 picks")
    }

    private fun Ship.isDestroyed(): Boolean = health <= 0

    private fun fill(crew: AbstractCrew) {
        if (!CustomCrewManager.isRace(crew.codename)) return
        for (p in HsCrew.ex(crew).crewPowers) {
            p.powerCooldown.first = p.powerCooldown.second
            for (r in p.powerResources) r.powerCooldown.first = r.powerCooldown.second
        }
    }

    private fun press(c: Ctx, crew: AbstractCrew?) {
        if (crew == null || !CustomCrewManager.isRace(crew.codename)) return
        for (p in HsCrew.ex(crew).crewPowers.toList()) {
            if (p.enabled && p.powerReady() == PowerReadyState.READY) guard(c, "pressing ${p.def.name}") { HsCrewUi.clickButton(crew, p) }
        }
    }

    private fun race(c: Ctx, race: String, aug: String?) {
        c.item = if (aug != null) "$aug/$race" else race
        c.phase = "spawn"
        progress(c)
        ensureEnemy(c)
        val s = c.s
        val p = s.player
        val e = s.enemy ?: throw Failure("no enemy")
        if (aug != null) {
            val bp = s.blueprintManager.getOrNull(aug) as? AugmentBlueprint ?: throw Failure("no augment blueprint $aug")
            p.augments.add(bp); p.cargoUpdated()
        }
        // everyone aboard before the race came: never killed (power spawns and the race's copies are). By name and
        // race: the save/load below makes new crew objects
        fun key(m: AbstractCrew) = crewName(m) + "/" + m.codename
        val base = HashSet<String>().also { b -> for (ship in ships(s)) for (m in ship.crew) b += key(m) }
        val rp = subjectRoom(p)?.id ?: 0
        val re = subjectRoom(e)?.id ?: 0
        val added = ArrayList<AbstractCrew>()
        fun add(ship: Ship, name: String, r: String, intruder: Boolean, room: Int): AbstractCrew? {
            val m = guard2(c, "adding $name") { HsCrew.addCrewMemberFromString(ship, name, r, intruder, room, true, true) }
            added += m
            return m
        }
        val P = add(p, "R27P", race, false, rp)
        add(p, "R27F", "human", false, rp)
        val J = add(p, "R27J", race, true, rp)
        add(p, "R27I", "human", true, rp)
        val E = add(e, "R27E", race, false, re)
        add(e, "R27G", "human", false, re)
        val Q = add(e, "R27Q", race, true, re)
        add(e, "R27B", "human", true, re)
        for (m in listOfNotNull(P, J, E, Q)) {
            if (!CustomCrewManager.isRace(m.codename)) continue
            for (pw in HsCrew.ex(m).crewPowers) {
                val def = pw.def
                if (!def.hideButton && text(s, def.buttonLabel).isBlank())
                    throw Failure("${m.codename}: power ${def.name} shows a button with an empty text")
            }
        }
        step(c, 1)
        checkCrew(c, true)
        c.phase = "use"
        for (m in listOfNotNull(P, J, E, Q)) fill(m)
        press(c, P); press(c, Q)
        step(c, 90)
        checkCrew(c, true)
        progress(c)
        trip(c)
        // one transition by turn: the powers' targets leave
        val turn = c.trips % 5
        c.phase = "transition $turn"
        when (turn) {
            1 -> { c.transitions++; guard(c, "the enemy escaping") { c.s.hsEnemyJumpLeave() } }
            2 -> { c.transitions++; guard(c, "the enemy destroyed") { HsConsole.run(c.s, "DELETE") } }
            3 -> {
                val to = c.s.currentBeacon.neighbours.firstOrNull()
                if (to != null) {
                    c.transitions++
                    guard(c, "the player jumping away") {
                        BrowserDiagnostics.closeBoxes(c.main, c.container)
                        c.s.player.fuelCount = maxOf(c.s.player.fuelCount, 3)
                        c.s.beginJumpOut(to)
                    }
                }
            }
            4 -> { c.transitions++; guard(c, "a sector change") { BrowserDiagnostics.closeBoxes(c.main, c.container); HsConsole.run(c.s, "SECTOR STANDARD_SPACE") } }
        }
        step(c, 90)
        checkCrew(c, true)
        c.phase = "end"
        for (ship in ships(c.s)) for (m in ship.crew.toList()) if (CustomCrewManager.isRace(m.codename)) {
            for (pw in HsCrew.ex(m).crewPowers) if (pw.temporaryPowerActive) pw.temporaryPowerDuration.first = 0.001f
        }
        step(c, 30)
        checkCrew(c, true)
        c.phase = "kill"
        val mine = HashSet<AbstractCrew>()
        for (ship in ships(c.s)) for (m in ship.crew) if (key(m) !in base) mine += m
        for (m in mine) guard(c, "killing ${m.codename}") { HsCrew.luaKill(m, true) }
        var n = 0
        while (n < 600 && ships(c.s).any { sh -> sh.crew.any { it in mine } }) { step(c, 1); n++ }
        val left = ships(c.s).flatMap { sh -> sh.crew.filter { it in mine }.map { m -> "${m.codename} ${crewName(m)} on ${if (sh === c.s.player) "p" else "e"} hp ${m.health} ${m.currentAction} updates ${m.hsUpdates}" } }
        if (left.isNotEmpty()) {
            val s = c.s
            throw Failure("killed crew still aboard after 10 s: ${left.take(4)}; paused ${s.isPaused} window ${s.shipUI.isWindowOpen} " +
                    "arrival pending ${s.browserArrivalPending()} jumping ${s.isPlayerJumping} jump pending ${s.browserJumpPending()} ${s.shipUI.diagnosticWindows}")
        }
        if (aug != null) c.s.player.augments.firstOrNull { it.name == aug }?.let { c.s.player.augments.remove(it); c.s.player.cargoUpdated() }
        step(c, 2)
    }

    private fun text(s: InGameState, t: xyz.znix.xftl.hs.crew.HsText): String = if (t.isLiteral) t.data else s.translator[t.data]

    // ---- augments ---------------------------------------------------------------------------------------------------

    private fun augments(c: Ctx, names: List<String>) {
        ensureEnemy(c)
        val bm = c.s.blueprintManager
        for ((i, name) in names.withIndex()) {
            c.item = name
            c.phase = "add"
            if (i % 10 == 0) progress(c)
            val bp = bm.getOrNull(name) as? AugmentBlueprint ?: throw Failure("no augment blueprint")
            val title = bp.title?.get(c.s.translator) ?: ""
            if (bp.title != null && title.isBlank()) c.notes += "$name: empty title"
            ensureEnemy(c)
            val p = c.s.player
            val e = c.s.enemy!!
            p.augments.add(bp); p.cargoUpdated()
            e.augments.add(bp); e.cargoUpdated()
            step(c, 3)
            values(c, p, name)
            values(c, e, name)
            if (i % 10 == 0) {
                val v = c.s.player.getAugmentValue(name)
                trip(c)
                if (c.s.player.augments.none { it.name == name }) throw Failure("the augment did not come back from the save")
                val w = c.s.player.getAugmentValue(name)
                if (v != w) throw Failure("value $v before the save, $w after")
            }
            c.phase = "remove"
            c.s.player.let { s -> s.augments.firstOrNull { it.name == name }?.let { s.augments.remove(it); s.cargoUpdated() } }
            c.s.enemy?.let { s -> s.augments.firstOrNull { it.name == name }?.let { s.augments.remove(it); s.cargoUpdated() } }
            if (HS.active) {
                c.phase = "hidden"
                c.s.player.hs?.addHidden(name)
                step(c, 2)
                values(c, c.s.player, name)
                c.s.player.hs?.removeHidden(name)
            }
            step(c, 1)
        }
    }

    private fun values(c: Ctx, ship: Ship, name: String) {
        val v = guard2(c, "GetAugmentationValue") { ship.getAugmentValue(name) }
        if (!fin(v)) throw Failure("GetAugmentationValue($name) = $v")
        if (HS.active) for ((fn, _) in HsAugments.definition(name)?.functions ?: emptyList()) {
            val f = guard2(c, "GetAugmentationValue($fn)") { ship.getAugmentValue(fn) }
            if (!fin(f)) throw Failure("GetAugmentationValue($fn) = $f")
        }
        checkCrew(c, true)
    }

    // ---- stat boost definitions -------------------------------------------------------------------------------------

    private fun boosts(c: Ctx, from: Int, to: Int) {
        ensureEnemy(c)
        val p = c.s.player
        val e = c.s.enemy!!
        val a = HsCrew.addCrewMemberFromString(p, "R27S", "human", false, subjectRoom(p)?.id ?: 0, true, true)
        val b = HsCrew.addCrewMemberFromString(e, "R27T", "human", false, subjectRoom(e)?.id ?: 0, true, true)
        step(c, 2)
        for (i in from until to) {
            val def = StatBoostDefs.defs[i]
            c.item = "boost #$i ${def.stat} ${def.boostType}"
            for (crew in listOf(a, b)) {
                val boost = StatBoost(def)
                boost.sourceShipId = HsCrew.shipIdOf(crew.room.ship)
                guard(c, "applying") { StatBoostManager.createTimedAugmentBoost(boost, crew) }
            }
            step(c, 2)
            checkCrew(c, true)
            for (crew in listOf(a, b)) HsCrew.ex(crew).timedStatBoosts.clear()
        }
        for (crew in listOf(a, b)) HsCrew.luaKill(crew, true)
        step(c, 60)
    }
}
