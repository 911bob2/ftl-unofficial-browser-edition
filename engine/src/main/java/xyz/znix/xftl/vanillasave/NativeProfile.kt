package xyz.znix.xftl.vanillasave

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty

/**
 * A profile of the original game (`ae_prof.sav`, version 9) or of Hyperspace (`hs_prof.sav`, `hs_mv_prof.sav`) as the
 * engine's own profile document (`xftl-profile`, SaveProfile.load), so it loads like any other profile
 * (docs/SAVES.md, "Saves from the original game").
 *
 * Layout: the version, the ship list tutorial state, the achievements (a ship's victory achievement, `<ship>_VICTORY`,
 * has the difficulty of each layout after it), [Hyperspace: its unlocks, ship victories, achievements, ship scores and
 * meta variables], layouts A and C of twelve ship slots, the top scores, each ship's best scores, four record and
 * total pairs, games played, victories and five crew records, [Hyperspace: layout B of the twelve slots].
 */
object NativeProfile {
    /** The original's ship slots (ScoreKeeper, CustomShipSelect::vanillaShipNames); the last two are unused. */
    val SHIPS = listOf("PLAYER_SHIP_HARD", "PLAYER_SHIP_STEALTH", "PLAYER_SHIP_MANTIS", "PLAYER_SHIP_CIRCLE", "PLAYER_SHIP_FED",
        "PLAYER_SHIP_JELLY", "PLAYER_SHIP_ROCK", "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_CRYSTAL", "PLAYER_SHIP_ANAEROBIC")

    class Score(val name: String, val ship: String, val score: Int, val sector: Int, val victory: Boolean, val difficulty: Int, val advanced: Boolean)

    class Parsed {
        var listTutorial = 0
        val achievements = LinkedHashMap<String, Int>()
        /** Ship blueprint (with `_2`/`_3` for layouts B and C) to the difficulty of its first victory. */
        val layoutVictories = LinkedHashMap<String, Int>()
        val layouts = LinkedHashSet<String>()
        val scores = ArrayList<Score>()
        /** Ships defeated, beacons explored, scrap collected, crew hired: (most in a run, total). */
        val stats = Array(4) { 0 to 0 }
        var games = 0
        var victories = 0
        /** Repairs, combat kills, piloted evasions, jumps survived, skill masteries: (value, name, race, male). */
        val crewRecords = ArrayList<Pair<Int, Triple<String, String, Boolean>>>()

        // Hyperspace
        val customUnlocked = ArrayList<String>()
        val questShips = LinkedHashMap<String, Int>()
        val shipVictories = LinkedHashMap<String, Int>()
        val customVictories = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        val hsAchievements = LinkedHashMap<String, Int>()
        val metaVariables = LinkedHashMap<String, Int>()
    }

    /** Reads the file; throws IllegalArgumentException when it is not a profile of this layout. */
    @JvmStatic
    fun parse(bytes: ByteArray, hyperspace: Boolean): Parsed {
        require(bytes.size <= 1024 * 1024) { "The profile is too large." }
        val r = VanillaSaveReader(bytes)
        val p = Parsed()
        fun count(max: Int) = r.int().also { require(it in 0..max) { "Bad count $it at ${r.pos}" } }
        fun scores(into: MutableList<Score>) = repeat(count(1000)) { into += Score(r.string(), r.string(), r.int(), r.int(), r.int() == 1, r.int(), r.bool()) }
        try {
            require(r.int() == 9) { "Not a profile of FTL 1.5 or later." }
            p.listTutorial = r.int().coerceIn(0, 2)
            repeat(count(10_000)) {
                val id = r.string()
                val difficulty = r.int()
                if (id.endsWith("_VICTORY")) {
                    val ship = id.removeSuffix("_VICTORY")
                    for ((i, suffix) in listOf("", "_2", "_3").withIndex()) {
                        val d = r.int()
                        if (d in 0..2) p.layoutVictories[ship + suffix] = d
                    }
                } else if (difficulty in 0..2) p.achievements[id] = difficulty
            }
            if (hyperspace) {
                repeat(count(100_000)) { p.customUnlocked += r.string() }
                repeat(count(100_000)) { val k = r.string(); val v = r.int(); if (v != -1) p.questShips[k] = v }
                repeat(count(100_000)) { val k = r.string(); val v = r.int(); if (v != -1) p.shipVictories[k] = v }
                repeat(count(10_000)) {
                    val type = r.string()
                    val m = p.customVictories.getOrPut(type) { LinkedHashMap() }
                    repeat(count(100_000)) { val k = r.string(); val v = r.int(); if (v != -1) m[k] = v }
                }
                repeat(count(100_000)) { val k = r.string(); val v = r.int(); if (v != -1) p.hsAchievements[k] = v }
                repeat(count(100_000)) {
                    r.string() // the ship
                    repeat(count(1000)) {
                        // CustomScoreKeeper::SaveShipScores: name, blueprint, sector, score, victory, difficulty, advanced
                        val name = r.string()
                        val ship = r.string()
                        val sector = r.int()
                        val score = r.int()
                        p.scores += Score(name, ship, score, sector, r.int() == 1, r.int(), r.bool())
                    }
                }
                repeat(count(1_000_000)) { p.metaVariables[r.string()] = r.int() }
            }
            for (i in 0 until 12) {
                val a = r.int()
                val c = r.int()
                val ship = SHIPS.getOrNull(i) ?: continue
                if (a != 0) p.layouts += ship
                if (c != 0) p.layouts += ship + "_3"
            }
            scores(p.scores) // the top scores
            scores(p.scores) // each ship's best
            for (i in 0 until 4) p.stats[i] = r.int() to r.int()
            p.games = r.int()
            p.victories = r.int()
            repeat(5) { p.crewRecords += r.int() to Triple(r.string(), r.string(), r.bool()) }
            if (hyperspace && r.remaining >= 48) for (i in 0 until 12) {
                val b = r.int()
                if (b == 1) SHIPS.getOrNull(i)?.let { p.layouts += it + "_2" }
            }
            require(r.remaining == 0) { "${r.remaining} bytes after the profile" }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("The profile ends too early.")
        }
        return p
    }

    /** The engine's profile document of [p]; [modSet] is the session's mod-set hash (Hyperspace's blocks need it). */
    @JvmStatic
    fun toDocument(p: Parsed, hyperspace: Boolean, modSet: String?, hsVersion: String?): Document {
        val root = Element("xftl-profile").setAttribute("listTutorial", p.listTutorial.toString())
        fun diff(d: Int) = Difficulty.entries[d.coerceIn(0, 2)].name
        // Layouts: the hangar's unlocks carry no difficulty; Easy never hides a later harder unlock.
        for (ship in p.layouts.sorted()) if (ship != "PLAYER_SHIP_HARD") root.addContent(Element("layout").setAttribute("ship", ship).setAttribute("diff", Difficulty.EASY.name))
        for ((ship, d) in p.layoutVictories.toSortedMap()) root.addContent(Element("layoutVictory").setAttribute("ship", ship).setAttribute("diff", diff(d)))
        for ((id, d) in p.achievements.toSortedMap()) root.addContent(Element("ach").setAttribute("id", id).setAttribute("diff", diff(d)))
        val stats = Element("browserStats").setAttribute("games", p.games.coerceAtLeast(0).toString()).setAttribute("wins", p.victories.coerceAtLeast(0).toString())
            .setAttribute("ships", p.stats[0].second.coerceAtLeast(0).toString()).setAttribute("beacons", p.stats[1].second.coerceAtLeast(0).toString())
            .setAttribute("scrap", p.stats[2].second.coerceAtLeast(0).toString()).setAttribute("crew", p.stats[3].second.coerceAtLeast(0).toString())
            .setAttribute("mostShips", p.stats[0].first.coerceAtLeast(0).toString()).setAttribute("mostBeacons", p.stats[1].first.coerceAtLeast(0).toString())
            .setAttribute("mostScrap", p.stats[2].first.coerceAtLeast(0).toString()).setAttribute("mostCrew", p.stats[3].first.coerceAtLeast(0).toString())
        // A run is in the top list and in its ship's list: keep it once. The file has only a run's score, so it is
        // kept as scrap that gives the same score (the engine works the score out of scrap, beacons and ships).
        val seen = HashSet<String>()
        for (s in p.scores) {
            if (s.score <= 0 || s.ship.isEmpty()) continue
            val d = Difficulty.entries[s.difficulty.coerceIn(0, 2)]
            if (!seen.add("${s.name}|${s.ship}|${s.score}|${s.sector}|${s.victory}|${d}")) continue
            val quarters = when (d) { Difficulty.EASY -> 4; Difficulty.NORMAL -> 5; Difficulty.HARD -> 6 }
            val scrap = ((s.score.toLong() * 4 + quarters - 1) / quarters).toInt()
            stats.addContent(ftl.browser.BrowserRunRecord(s.ship, s.name, d, s.advanced, scrap, 0, 0, true,
                if (s.victory) xyz.znix.xftl.game.GameOverWindow.Outcome.WIN else xyz.znix.xftl.game.GameOverWindow.Outcome.LOOSE_HULL,
                0, true, s.sector.coerceIn(0, 99)).save("score"))
        }
        for ((i, rec) in p.crewRecords.withIndex()) {
            val kind = ftl.browser.CrewRecordKind.entries.getOrNull(i) ?: continue
            if (rec.first <= 0) continue
            stats.addContent(ftl.browser.BrowserCrewRecord(kind, rec.first, rec.second.first, rec.second.second, !rec.second.third).save())
        }
        root.addContent(stats)
        if (hyperspace) {
            val hs = Element(xyz.znix.xftl.hs.save.HsSave.ELEMENT).setAttribute("modSet", modSet ?: "")
            if (hsVersion != null) hs.setAttribute("hs", hsVersion)
            val unlocks = Element("data").setAttribute("key", xyz.znix.xftl.hs.ship.HsShipUnlocks.SAVE_KEY)
            for (s in p.customUnlocked) unlocks.addContent(Element("unlocked").setAttribute("ship", s))
            for ((s, d) in p.questShips) unlocks.addContent(Element("quest").setAttribute("ship", s).setAttribute("difficulty", d.toString()))
            for ((s, d) in p.shipVictories) unlocks.addContent(Element("victory").setAttribute("ship", s).setAttribute("difficulty", d.toString()))
            for ((t, m) in p.customVictories) for ((s, d) in m)
                unlocks.addContent(Element("victory").setAttribute("type", t).setAttribute("ship", s).setAttribute("difficulty", d.toString()))
            hs.addContent(unlocks)
            val ach = Element("data").setAttribute("key", xyz.znix.xftl.hs.ship.HsAchievements.SAVE_KEY)
            for ((k, v) in p.hsAchievements) ach.addContent(Element("unlock").setAttribute("name", k).setAttribute("difficulty", v.toString()))
            hs.addContent(ach)
            val events = Element("data").setAttribute("key", "events")
            for ((k, v) in p.metaVariables.toSortedMap()) events.addContent(Element("v").setAttribute("n", k).setAttribute("x", v.toString()))
            hs.addContent(events)
            root.addContent(hs)
        }
        return Document(root)
    }
}
