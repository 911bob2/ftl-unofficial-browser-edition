package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.RandomWithChance
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer
import kotlin.math.max
import kotlin.math.min

/**
 * Enemy ship generation (ShipGenerator::CreateShip 0x620fd0 port, see docs/ENEMY_GENERATION.md): known answers from
 * native ship samples, the sector-one shield ceiling, the sector windows of blueprint lists, determinism and the
 * native threshold roll.
 */
internal object GameplayParityEnemyGenChecks {
    /**
     * A native sample: the ship event (or, for a console `SHIP <list>` sample, the blueprint list and the recovered
     * stream state before CreateShip), difficulty, world level, ship-event seed -> what the original built. [sample]
     * names the native dump in the ignored `.build/gameplay-1to1/native` / `scan/native-gen` folders. [draws] is the
     * number of generator draws the native ring shows from CreateShip (0x6251e5) to its first post-creation draw
     * (ship animation / drone flight); 0 where the ring ended first.
     */
    private class Case(
        val spec: String?, val difficulty: Difficulty, val sector: Int, val seed: Int, val sample: String, val draws: Int,
        val expected: String, val list: String? = null, val state: String? = null,
    )

    // Hull, scrap pool, reactor, system levels in native id order, weapons and drones in pick order, drone parts and
    // crew count, copied from the native ship dumps (enemy generation samples and real first-sector arrivals; crew
    // names are left out because they depend on the player's crew names). The stream includes the ship construction
    // draws (Ship::OnInit, AddSystem, the random enemy console slots, weapons, drones, fires).
    private val AE_CASES = listOf(
        Case("ROCK_PIRATE", Difficulty.EASY, 8, 1713252089, "enemygen-e-easy-ae-s027", 131,
            "ROCK_FIGHT_P_DLC hull=17 scrap=1205 reactor=23 [shields:8,engines:4,oxygen:1,weapons:7,pilot:3,teleporter:1,cloaking:1,clonebay:1] w=[ION_2,MISSILES_3,LASER_BURST_1,BOMB_1] d=[] parts=0 crew=4"),
        Case("REBEL", Difficulty.NORMAL, 1, 1492159524, "enemygen-e-normal-ae-s008", 109,
            "REBEL_SKINNY hull=11 scrap=237 reactor=9 [shields:3,engines:2,oxygen:1,weapons:3,pilot:1] w=[SHOTGUN,LASER_BURST_1] d=[] parts=0 crew=3"),
        Case("REBEL", Difficulty.NORMAL, 0, 209709959, "enemygen-e-normal-ae-s007", 116,
            "REBEL_FAT hull=9 scrap=175 reactor=10 [shields:2,engines:2,oxygen:1,weapons:2,drones:3,pilot:2] w=[MISSILES_1,LASER_BURST_1] d=[COMBAT_BEAM,ANTI_DRONE] parts=4 crew=3"),
        Case("PIRATE", Difficulty.EASY, 4, 1943381924, "enemygen-e-easy-ae-s004", 122,
            "MANTIS_FIGHTER_P hull=12 scrap=453 reactor=19 [shields:5,engines:2,oxygen:2,weapons:3,medbay:2,pilot:2,teleporter:1] w=[LASER_CHARGEGUN,ION_STUN] d=[] parts=0 crew=3"),
        Case("PIRATE_BRIBER", Difficulty.HARD, 0, 727316529, "gen-n0-hard-ae-j1", 95,
            "ROCK_SCOUT_P hull=8 scrap=175 reactor=10 [shields:2,engines:1,oxygen:1,weapons:2,pilot:2] w=[MISSILES_1,LASER_BURST_1] d=[] parts=0 crew=2"),
        Case(null, Difficulty.NORMAL, 4, 0, "enemygen-m-normal-ae-1-s064", 132,
            "ZOLTAN_BOMBER hull=11 scrap=602 reactor=21 [shields:5,engines:4,oxygen:1,weapons:5,drones:3,pilot:1,doors:1] w=[MISSILES_3,LASER_BURST_2_A] d=[COMBAT_BEAM] parts=2 crew=4", list = "SHIPS_ZOLTAN", state = "-6981595672619710477"),
        Case(null, Difficulty.NORMAL, 8, 0, "enemygen-m-normal-ae-2-s008", 0,
            "ZOLTAN_FIGHTER_P_DLC hull=15 scrap=1454 reactor=32 [shields:8,engines:5,oxygen:1,weapons:8,pilot:1,clonebay:3,mind:1] w=[BEAM_2,ION_2,BEAM_FIRE,MISSILES_1] d=[] parts=0 crew=3", list = "SHIPS_ZOLTAN_PIRATE", state = "-6879950643615434945"),
        Case(null, Difficulty.EASY, 0, 0, "enemygen-m-easy-ae-0-s036", 0,
            "ANAEROBIC_SCOUT hull=6 scrap=175 reactor=7 [shields:2,engines:1,weapons:2,pilot:1] w=[BEAM_1,LASER_HEAVY_1] d=[] parts=0 crew=2", list = "SHIPS_LANIUS", state = "1043898709630766988"),
    )

    private val VANILLA_CASES = listOf(
        Case("ROCK_PIRATE", Difficulty.NORMAL, 0, 1951816749, "enemygen-e-normal-van-s021", 91,
            "ROCK_SCOUT_P hull=8 scrap=175 reactor=14 [shields:3,engines:2,oxygen:1,weapons:3,pilot:1,teleporter:1] w=[LASER_BURST_2_A,BOMB_1] d=[] parts=0 crew=2"),
        Case("PIRATE", Difficulty.HARD, 0, 1348371972, "enemygen-e-hard-van-s000", 95,
            "MANTIS_SCOUT_P hull=7 scrap=175 reactor=12 [shields:3,engines:2,oxygen:1,weapons:2,pilot:2] w=[BEAM_1,LASER_BURST_1] d=[] parts=0 crew=3"),
        Case("PIRATE", Difficulty.EASY, 3, 352806803, "enemygen-e-easy-van-s003", 123,
            "MANTIS_FIGHTER_P hull=11 scrap=330 reactor=18 [shields:4,engines:3,oxygen:1,weapons:2,medbay:2,pilot:2,teleporter:2] w=[BEAM_1,LASER_HEAVY_1] d=[] parts=0 crew=3"),
        Case("REBEL", Difficulty.HARD, 0, 208260689, "enemygen-e-hard-van-s007", 111,
            "REBEL_FAT hull=9 scrap=175 reactor=11 [shields:3,engines:2,oxygen:2,weapons:1,drones:3,pilot:1] w=[LASER_HEAVY_1] d=[DEFENSE_2_ENEMY] parts=4 crew=3"),
        Case("MANTIS_FIGHT", Difficulty.EASY, 8, 1097785254, "enemygen-e-easy-van-s020", 141,
            "MANTIS_FIGHTER hull=16 scrap=1205 reactor=29 [shields:8,engines:4,oxygen:1,weapons:8,medbay:2,pilot:1,doors:1,teleporter:2] w=[MISSILES_BREACH,LASER_BURST_2_A,BEAM_1,LASER_BURST_2_A] d=[] parts=0 crew=4"),
        Case(null, Difficulty.NORMAL, 1, 0, "enemygen-m-normal-van-1-s001", 0,
            "REBEL_SKINNY_ELITE hull=15 scrap=237 reactor=16 [shields:4,engines:4,oxygen:2,weapons:4,medbay:1,pilot:2,teleporter:1] w=[LASER_BURST_3,LASER_BURST_3] d=[] parts=0 crew=3", list = "SHIPS_REBEL_ELITE", state = "-3017966805696122780"),
        Case(null, Difficulty.HARD, 4, 0, "enemygen-m-hard-van-0-s009", 0,
            "CIRCLE_SCOUT hull=11 scrap=602 reactor=20 [shields:5,engines:4,oxygen:1,weapons:5,drones:4,medbay:1,pilot:2,doors:1] w=[LASER_BURST_2_A,ION_4] d=[DEFENSE_1,DEFENSE_1] parts=4 crew=3", list = "SHIPS_CIRCLE", state = "7037137746426668394"),
        Case(null, Difficulty.HARD, 6, 0, "enemygen-m-hard-van-2-s004", 0,
            "ROCK_ASSAULT_ELITE hull=18 scrap=980 reactor=30 [shields:7,engines:5,oxygen:2,weapons:8,medbay:2,pilot:1,doors:1,teleporter:1,cloaking:1] w=[LASER_BURST_5,ION_2,LASER_BURST_3] d=[] parts=0 crew=4", list = "ROCK_ASSAULT_ELITE", state = "5924963859614250592"),
    )

    private val SYSTEM_ORDER = listOf(
        "shields", "engines", "oxygen", "weapons", "drones", "medbay", "pilot", "sensors", "doors", "teleporter",
        "cloaking", "artillery", "battery", "clonebay", "mind", "hacking"
    )

    fun run(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val rng = NativeRandom.snapshot()
        try {
            val known = knownAnswers(state)
            val shields = firstSectorShields(state)
            val windows = sectorWindows(state)
            val determinism = determinism(state)
            val easyZoltan = easyFirstSector(state)
            val roll = thresholdRoll()
            val rooms = crewRoomDrawOrder(state)
            return "enemygen ok: $known known ships, $shields sector-one ships within the native shield ceiling, " +
                    "$windows picks inside their sector windows, $determinism, $easyZoltan, $roll, $rooms"
        } finally {
            NativeRandom.restore(rng)
        }
    }

    private fun summary(ship: Ship): String {
        val levels = SYSTEM_ORDER.mapNotNull { name ->
            val installed = ship.systems.filter { it.blueprint.type == name }
            if (installed.isEmpty()) null else name + ":" + installed.joinToString("+") { it.energyLevels.toString() }
        }.joinToString(",")
        val weapons = ship.hardpoints.mapNotNull { it.weapon?.type?.name }.joinToString(",")
        val drones = ship.drones?.drones?.filterNotNull()?.joinToString(",") { it.type.name } ?: ""
        val crew = ship.crew.filterIsInstance<LivingCrew>().size
        return "${ship.name} hull=${ship.maxHealth} scrap=${ship.scrap} reactor=${ship.purchasedReactorPower} " +
                "[$levels] w=[$weapons] d=[$drones] parts=${ship.dronesCount} crew=$crew"
    }

    private fun knownAnswers(state: InGameState): Int {
        val cases = if (state.content.enableAdvancedEdition) AE_CASES else VANILLA_CASES
        try {
            for (case in cases) {
                state.setDifficulty(case.difficulty)
                // No names in use, as in the samples whose name draws never retried.
                state.eventCreator.usedNamesFixture = listOf()
                state.eventCreator.refreshUsedNames()
                val spec = if (case.list != null) {
                    val elem = Element("ship")
                    elem.setAttribute("name", "PARITY_LIST")
                    elem.setAttribute("auto_blueprint", case.list)
                    EnemyShipSpec(elem, state.blueprintManager, state.eventManager)
                } else {
                    state.eventManager.getShip(case.spec!!)
                }
                if (case.state != null) NativeRandom.restore(case.state)
                val trace = ArrayList<Int>()
                NativeRandom.trace = trace
                val enemy = try {
                    state.content.generator.buildShip(state, spec, case.sector, case.difficulty, if (case.state != null) null else case.seed)
                } finally {
                    NativeRandom.trace = null
                }
                // A reseed is recorded as a marker followed by the seed.
                val draws = trace.size - 2 * trace.count { it == Int.MIN_VALUE }
                check(case.draws == 0 || draws == case.draws) { "${case.sample}: $draws generator draws, native ${case.draws}" }
                val got = summary(enemy)
                check(got == case.expected) {
                    "${case.sample} ${case.spec ?: case.list} ${case.difficulty} level ${case.sector}:\n  got      $got\n  native   ${case.expected}"
                }
                // An empty crew is an automated ship (ShipManager 0x4ae2f0 sets +0x4d9)
                check(enemy.isAutomated == enemy.crew.isEmpty()) { "${case.sample} automated flag ${enemy.isAutomated}" }
            }
        } finally {
            state.eventCreator.usedNamesFixture = null
            state.setDifficulty(Difficulty.NORMAL)
        }
        return cases.size
    }

    /** The GenerateSystemMaxes ceiling for the shields of a ship at this level (0x61f640). */
    private fun shieldCeiling(ship: Ship, level: Int, difficulty: Difficulty): Int? {
        val system = ship.shields ?: return null
        val spec = system.configuration.spec
        val start = spec.startingPower
        val max = spec.aiMaxPower ?: return null
        val randomMax = when {
            level < 0 -> 0
            difficulty == Difficulty.EASY -> 1
            level <= 1 -> 1
            else -> 2
        }
        val scaled = (level.toFloat() * 0.125f * (max - start).toFloat() + start.toFloat()).toInt()
        return min(max(scaled + randomMax, start), max)
    }

    private fun firstSectorShields(state: InGameState): Int {
        // One spec per ship list, in sectors one and two (levels 0 and 1) on every difficulty: the shields never exceed
        // the native ceiling. The ceiling alone decides: a 2-bar start with a max of 10 may reach 4 bars at level 1, as
        // native samples show (JELLY_CROISSANT_DLC and CRYSTAL_BOMBER_DLC with 4 bars in hard sector two).
        val specs = state.eventManager.getShips().filter { !it.fixedBlueprint }.distinctBy { it.autoBlueprint }
        var checked = 0
        for (spec in specs) for (difficulty in Difficulty.values()) for (sector in 0..1) for (seed in 1..1) {
            state.debugSpawnShip(spec, difficulty, sector, 9000 + seed)
            val enemy = state.enemy!!
            val level = sector + difficulty.sectorOffset

            val ceiling = shieldCeiling(enemy, level, difficulty)
            val shields = enemy.shields
            if (ceiling != null && shields != null) {
                check(shields.energyLevels <= ceiling) {
                    "${enemy.name} (${spec.name}) shields ${shields.energyLevels} > $ceiling at level $level"
                }
            }

            // Slots default to four, a drone system holds at least two parts per drone, ships with a drone or hacking
            // system always carry parts.
            check(enemy.hardpoints.count { it.weapon != null } <= (enemy.type.weaponSlots ?: 4)) { "${enemy.name} weapon slots" }
            val droneCount = enemy.drones?.drones?.count { it != null } ?: 0
            check(enemy.dronesCount >= droneCount * 2) { "${enemy.name} drone parts ${enemy.dronesCount} for $droneCount drones" }
            if (enemy.drones != null || enemy.hacking != null)
                check(enemy.dronesCount >= 1) { "${enemy.name} has a drone system and no parts" }
            checked++
        }
        check(checked > 100) { "Sector-one sample too small: $checked" }
        return checked
    }

    private fun sectorWindows(state: InGameState): Int {
        // GetShipBlueprint (0x60ac50) only picks blueprints with minSector <= level <= maxSector.
        var checked = 0
        for (specName in listOf("PIRATE", "MANTIS_FIGHT", "JELLY", "REBEL", "ZOLTAN_SHIP", "ENGI_SHIP"))
            for (sector in listOf(0, 2, 5, 9)) for (seed in 1..5) {
                state.debugSpawnShip(state.eventManager.getShip(specName), Difficulty.NORMAL, sector, 300 + seed)
                val enemy = state.enemy!!
                val blueprint = state.blueprintManager.getOrNull(enemy.name) as? LazyShipBlueprint ?: continue
                check(blueprint.minSector <= sector && sector <= blueprint.maxSector) {
                    "$specName picked ${enemy.name} (sectors ${blueprint.minSector}..${blueprint.maxSector}) at level $sector"
                }
                checked++
            }
        check(checked > 80) { "Sector window sample too small: $checked" }
        return checked
    }

    private fun determinism(state: InGameState): String {
        val spec: EnemyShipSpec = state.eventManager.getShip("REBEL")
        val seen = HashSet<String>()
        for (seed in 1..12) {
            state.debugSpawnShip(spec, Difficulty.NORMAL, 3, seed)
            val first = summary(state.enemy!!)
            // The stream is reseeded from the ship-event seed, so what happened before does not matter.
            NativeRandom.seed(seed * 31)
            repeat(seed) { NativeRandom.next31() }
            state.debugSpawnShip(spec, Difficulty.NORMAL, 3, seed)
            check(summary(state.enemy!!) == first) { "Seed $seed does not reproduce its ship" }
            seen += first
        }
        check(seen.size > 3) { "Twelve seeds only made ${seen.size} distinct ships" }
        return "seeded ships reproduce (${seen.size} distinct of 12)"
    }

    private fun easyFirstSector(state: InGameState): String {
        // CreateShip drops the Zoltan shield on easy in the first sector (level -1) only.
        val spec = state.eventManager.getShip("ZOLTAN_SHIP")
        fun hasZoltanShield(difficulty: Difficulty, sector: Int, seed: Int): Boolean {
            state.debugSpawnShip(spec, difficulty, sector, seed)
            return state.enemy!!.augments.any { it.name == "ENERGY_SHIELD" }
        }
        for (seed in 1..6) {
            check(!hasZoltanShield(Difficulty.EASY, 0, seed)) { "Easy first sector kept a Zoltan shield" }
            check(hasZoltanShield(Difficulty.EASY, 1, seed)) { "Easy second sector lost the Zoltan shield" }
            check(hasZoltanShield(Difficulty.NORMAL, 0, seed)) { "Normal first sector lost the Zoltan shield" }
        }
        return "easy first-sector Zoltan shield"
    }

    /**
     * ShipManager 0x4ae2f0 chooses a crew member's room before 0x4a24a0 draws the name, the timer and the colours:
     * a member beyond the priority list takes `random32() % room count`, and the very next draw is its own name
     * (`names[random32() % names]`, sorted flat table). Over many seeds some member must satisfy both at once; the old
     * order (room after the colours) can only match by chance.
     */
    private fun crewRoomDrawOrder(state: InGameState): String {
        val names = xyz.znix.xftl.crew.CrewNameManager(state.content.datafile, "en").flatNames
        val listed = setOf(
            "pilot", "shields", "engines", "weapons", "oxygen", "sensors", "doors", "cloaking", "medbay", "drones",
            "hacking", "teleporter"
        )
        var matched = 0
        var beyond = 0
        for (specName in listOf("PIRATE", "REBEL", "LONG_ELITE", "JELLY", "MANTIS_FIGHT", "ENGI_SHIP")) {
            val spec = state.eventManager.getShip(specName)
            for (sector in listOf(0, 3, 6)) for (seed in 1..30) {
                val trace = ArrayList<Int>()
                NativeRandom.trace = trace
                try {
                    state.eventCreator.usedNamesFixture = listOf<String>()
                    state.debugSpawnShip(spec, Difficulty.NORMAL, sector, seed)
                } finally {
                    NativeRandom.trace = null
                }
                val draws = ArrayList<Int>()
                var i = 0
                while (i < trace.size) {
                    if (trace[i] == Int.MIN_VALUE) i += 2 else draws.add(trace[i++])
                }

                // Members beyond the priority list: one room per listed system type, plus up to three artillery.
                val enemy = state.enemy!!
                val crew = enemy.crew.filterIsInstance<LivingCrew>()
                val roomsInList = enemy.systems.map { it.blueprint.type }.toSet().count { it in listed } +
                        minOf(enemy.systems.count { it.blueprint.type == "artillery" }, 3)
                for ((index, member) in crew.withIndex()) {
                    if (index < roomsInList)
                        continue
                    beyond++
                    val room = enemy.rooms.indexOf(member.room)
                    val name = member.info.name
                    for (p in 0 until draws.size - 1) {
                        if (draws[p] % enemy.rooms.size == room && names[draws[p + 1] % names.size].first == name) {
                            matched++
                            break
                        }
                    }
                }
            }
        }
        // A member with a named race drew its name earlier, so it need not match; the random ones must.
        check(beyond == 0 || matched > 0) { "None of $beyond members beyond the priority list drew its room right before its name" }
        return "crew room drawn before name ($matched of $beyond members beyond the priority list show it)"
    }

    private fun thresholdRoll(): String {
        // RandomAmount::Roll (0x5caf40): chance draw `random32() % 100 < 100 * chance` gives 0, then min + draw % (max + 1 - min).
        fun amount(min: Int, max: Int, chance: String?): RandomWithChance {
            val e = Element("escape")
            e.setAttribute("min", min.toString())
            e.setAttribute("max", max.toString())
            if (chance != null)
                e.setAttribute("chance", chance)
            return RandomWithChance(e)
        }

        val withChance = amount(2, 4, "0.5")
        var zeros = 0
        for (seed in 1..40) {
            NativeRandom.seed(seed)
            val first = NativeRandom.next31()
            val second = NativeRandom.next31()
            val expected = if ((first % 100).toFloat() < 50f) 0 else 2 + second % 3
            NativeRandom.seed(seed)
            val got = withChance.pick(NativeRandom)
            check(got == expected) { "Roll for seed $seed gave $got, expected $expected" }
            if (got == 0) zeros++
        }
        check(zeros in 5..35) { "Chance roll gave $zeros zeros in 40" }

        // A zero max has no range draw.
        NativeRandom.seed(11)
        val firstDraw = NativeRandom.next31()
        NativeRandom.seed(11)
        check(amount(0, 0, null).pick(NativeRandom) == 0 && NativeRandom.next31() == firstDraw) { "Zero max drew" }

        return "threshold roll"
    }
}
