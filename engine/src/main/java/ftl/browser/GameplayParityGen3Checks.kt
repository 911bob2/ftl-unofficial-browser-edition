package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.LootPool
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.Resource
import xyz.znix.xftl.game.RewardTier
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.EventList
import xyz.znix.xftl.sector.EventText
import xyz.znix.xftl.sector.GameMap
import xyz.znix.xftl.sector.RangeSpec
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sector.TextList
import xyz.znix.xftl.sys.GameContainer

/**
 * Round 4 generation fixes (see .build/gameplay-1to1/fix/gen.md, findings G1-G9): native known answers for whole
 * generated sectors (first sector, later sectors in one run, the last stand with its mark), the ship ending events
 * seeded from the ship, and focused checks of the event parser rules (reward level, unknown item types, nested event
 * names) and the text list order. The enemy ship construction draws (G5) are checked by the native ship samples in
 * [GameplayParityEnemyGenChecks]. Round 5 adds native arrivals (ShipAI draws, surrender and escape thresholds), the
 * stalemate surrender, and later sectors: quest placement, waits with fleet takeovers, and store crew skills.
 */
internal object GameplayParityGen3Checks {
    /**
     * A native run: tree seed, difficulty, the player's crew names, then each generated sector as
     * `flat index:layout seed` or `T=<sector type>@<column>:<layout seed>` (a console sector jump), with the
     * fingerprint of each native map ([canonical], Java string hash). The maps come from native StarMap dumps in the
     * ignored `.build/gameplay-1to1/scan/gen/nat` folder.
     */
    private class Run(
        val ae: Boolean, val sample: String, val treeSeed: Int, val difficulty: Difficulty, val usedNames: List<String>,
        val steps: String, val hashes: IntArray,
    )

    private val RUNS = listOf(
        // G4: the first split was an explicit-class event crew member.
        Run(true, "n0-easy-ae", 1910472429, Difficulty.EASY, listOf("Hutz", "Chriz", "Bjorn"), "0:1369159221",
            intArrayOf(-29830230)),
        Run(false, "n0-easy-van", 1322646435, Difficulty.EASY, listOf("O'Regan", "Frederic", "Gumpo"), "0:1191623232",
            intArrayOf(1708924091)),
        // G6 (text lists from the second sector) and G9 (ENGI_HOME fuel station choice).
        Run(true, "gen-s1-normal-ae", 2096499169, Difficulty.NORMAL, listOf("Rebekah", "T'ten", "Frederic"),
            "0:917291178,1:224356771,3:925554884,7:1335397008,10:2010532362",
            intArrayOf(717932400, -567988762, 436889755, -366383790, 1274863975)),
        // G6 and G7 (ROCK_LOOTING level="low" in the rock sector).
        Run(false, "gen-s1-normal-van", 802815843, Difficulty.NORMAL, listOf("Aki", "Markel", "Sem"),
            "0:1754200673,1:1569169792,3:180668073,7:832571314,9:640982409",
            intArrayOf(1361167866, 2070943089, 491347795, 1608139462, 1466261015)),
        // G6 and G8 (ROCK_HOME, item type="missile").
        Run(false, "gen-s1-easy-van", 1501017936, Difficulty.EASY, listOf("Martin", "Caris", "Hector"),
            "0:1537078806,1:994523788,3:1659701449,7:2049648959,9:320101127",
            intArrayOf(-827466627, 2017477299, -1350130376, -788095158, 902819906)),
        // G2: the last stand marks beacon 15 (AE easy) and 17 (vanilla hard).
        Run(true, "gen-f0-easy-ae", 303732012, Difficulty.EASY, listOf("Liu Jun", "Malott", "Shirai"),
            "0:1311694473,T=FINAL@0:1330905339", intArrayOf(-1549552070, 338527330)),
        Run(false, "gen-f0-hard-van", 1109619866, Difficulty.HARD, listOf("Pipaluk", "Nathan", "Berko"),
            "0:416945692,T=FINAL@0:1895871370", intArrayOf(154879888, -860568246)),
    )

    /** Native ship ending events (G1): the ship-event seed reseeds the stream, at the sector's level. */
    private class Ending(
        val ae: Boolean, val ship: String, val which: String, val seed: Int, val difficulty: Difficulty,
        val text: String, val resources: String, val choices: Int,
    )

    private val ENDINGS = listOf(
        Ending(true, "PIRATE", "destroyed", 651124954, Difficulty.NORMAL, "event_DESTROYED_DEFAULT_1_text", "1,2,0,16", 0),
        Ending(true, "PIRATE", "deadCrew", 502817664, Difficulty.NORMAL, "event_DEAD_CREW_DEFAULT_1_text", "1,0,1,13", 0),
        Ending(true, "PIRATE", "surrender", 5199828, Difficulty.NORMAL, "text_PIRATE_SURRENDER_TEXT_14", "0,0,0,0", 2),
        Ending(true, "PIRATE", "gotaway", 755165176, Difficulty.NORMAL, "ship_PIRATE_gotaway_text", "0,0,0,0", 0),
        Ending(false, "PIRATE", "destroyed", 2107067703, Difficulty.HARD, "event_DESTROYED_DEFAULT_1_text", "0,3,1,14", 0),
        Ending(false, "PIRATE", "deadCrew", 1175707544, Difficulty.HARD, "event_DEAD_CREW_DEFAULT_6_text", "0,4,0,22", 0),
        Ending(false, "PIRATE", "surrender", 280825718, Difficulty.HARD, "text_PIRATE_SURRENDER_TEXT_2", "0,0,0,0", 2),
        Ending(false, "PIRATE", "escape", 2113099231, Difficulty.HARD, "event_PIRATE_ESCAPE_text", "0,0,0,0", 0),
    )

    /**
     * Native first-sector arrivals (G3): ship event, seed, difficulty, the generator state recovered from the ring
     * just before WorldManager::CreateShip (the ShipAI constructor draws at 0x6251e5 / 0x6251d8), the player's crew
     * names, and the CompleteShip thresholds the original rolled (surrender, escape).
     */
    private class Arrival(
        val ae: Boolean, val sample: String, val ship: String, val seed: Int, val difficulty: Difficulty, val state: String,
        val usedNames: List<String>, val surrender: Int, val escape: Int,
    )

    private val ARRIVALS = listOf(
        Arrival(false, "gen-n0-easy-van", "PIRATE_SLAVER", 444172667, Difficulty.EASY, "-2516789550093318098",
            listOf("O'Regan", "Frederic", "Gumpo"), 2, 0),
        Arrival(true, "gen-n0-hard-ae", "PIRATE_BRIBER", 727316529, Difficulty.HARD, "6473671078262903135",
            listOf("Tach", "Willow", "Gracie"), 3, 2),
        Arrival(false, "gen-n0-normal-van", "PIRATE_STATION_CROPS", 1819939243, Difficulty.NORMAL, "4659193027267319457",
            listOf("Rebekah", "Jack", "Kirkner"), 4, 3),
        Arrival(false, "gen-n1-easy-van", "SQUAT_REFUEL_STATION", 1547472512, Difficulty.EASY, "80674517028369760",
            listOf("Firedihm", "Markand", "Kapalka"), 0, -1),
        Arrival(false, "gen-n1-hard-van", "PIRATE", 146208842, Difficulty.HARD, "-4895029353632385593",
            listOf("Maxim", "Baars", "Remo"), 3, 0),
        Arrival(false, "gen-n1-normal-van", "REBEL_AUTO", 1151519637, Difficulty.NORMAL, "6350518281616540809",
            listOf("Maloney", "Nazia", "Breckon"), 0, -1),
    )

    /**
     * STALEMATE_SURRENDER captured live (gen-st-* in scan/native-gen: player fuel 0, the enemy ShipAI stalemate timer set
     * near two minutes): the stream state just before the creation and the seven draws CreateEvent made from it, with
     * no reseed in between (UpdateLocation1 0x61c885 passes level 0 and seed -1).
     */
    private class Stalemate(val ae: Boolean, val sample: String, val difficulty: Difficulty, val state: String, val draws: IntArray)

    private val STALEMATES = listOf(
        Stalemate(true, "gen-st-normal-ae", Difficulty.NORMAL, "5534346005933369494",
            intArrayOf(1544124734, 295254276, 433089757, 402949849, 1869057539, 983260243, 334936345)),
        Stalemate(false, "gen-st-hard-van", Difficulty.HARD, "-5495519228492232460",
            intArrayOf(9397096, 1489413825, 1503654542, 690949982, 1625666591, 497128368, 979411178)),
    )

    fun run(main: MainGame, container: GameContainer): String {
        val streamBefore = NativeRandom.snapshot()
        val parts = ArrayList<String>()
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        try {
            parts += rewardLevels()
            parts += unknownItems(state)
            parts += nestedNames(state)
            parts += textListOrder()
            parts += endings(state)
            parts += arrivals(state)
            parts += stalemates(state)
            parts += nativeMaps(state)
            parts += laterRuns(state)
            parts += laterStores(state)
            parts += missingHulls(state)
        } finally {
            NativeRandom.trace = null
            state.eventCreator.usedNamesFixture = null
            state.setDifficulty(Difficulty.NORMAL)
            state.eventManager.resetEventState()
            NativeRandom.restore(streamBefore)
        }
        return "gen3: " + parts.joinToString(", ")
    }

    // ---- G7: autoReward level, compared case-sensitively by the event parser 0x5e52c0

    private fun rewardLevels(): String {
        val expected = mapOf(
            "LOW" to RewardTier.LOW, "MED" to RewardTier.MEDIUM, "HIGH" to RewardTier.HIGH,
            "low" to RewardTier.RANDOM, "MEDIUM" to RewardTier.RANDOM, "med" to RewardTier.RANDOM,
            "RANDOM" to RewardTier.RANDOM, null to RewardTier.RANDOM,
        )
        for ((name, tier) in expected)
            check(RewardTier.fromName(name) == tier) { "Reward level '$name' gave ${RewardTier.fromName(name)}, native $tier" }
        return "reward levels"
    }

    // ---- G8: an unknown item type (ROCK_STARSHIP_MINE's type="missile") has no resource and no range roll

    private fun unknownItems(state: InGameState): String {
        check(Resource.byName("missile") == null && Resource.byName("missiles") == Resource.MISSILES) { "Item type names" }
        val elem = Element("event")
        elem.addContent(Element("text").setText("x"))
        val modify = Element("item_modify")
        modify.addContent(Element("item").setAttribute("type", "missile").setAttribute("min", "-1").setAttribute("max", "-1"))
        modify.addContent(Element("item").setAttribute("type", "scrap").setAttribute("min", "5").setAttribute("max", "9"))
        elem.addContent(modify)
        val event = state.eventManager.loadEmbeddedEvent(elem, "GEN3_UNKNOWN_ITEM").value as Event
        check(event.resourceRanges[Resource.MISSILES] === RangeSpec.NONE) { "type=\"missile\" kept a missile range" }
        check(event.resourceRanges[Resource.SCRAP]?.max == 9) { "The scrap item was lost" }
        return "unknown item types"
    }

    // ---- G9: a nested <event name="X"/> is a reference to X (lists first)

    private fun nestedNames(state: InGameState): String {
        val list = state.eventManager.getOrNull("FUELING_STATION_NORMAL_LIST") as? EventList
            ?: return "nested event names (no fuel station list)"
        val blow = state.eventManager.getOrNull("FUELING_STATION_BLOW")
        check(blow is EventList) { "FUELING_STATION_BLOW is not the list" }
        val referenced = list.events.filterIsInstance<Event>().flatMap { it.choices }.count { it.event === blow }
        check(referenced == 1) { "$referenced fuel station choices lead to the FUELING_STATION_BLOW list, native 1" }
        return "nested event names"
    }

    // ---- G6: EventGenerator 0x5db020 puts the used texts back at the front, in removal order

    private fun textListOrder(): String {
        val items = (0 until 6).map { EventText("t$it", "t$it") }
        val list = TextList("GEN3_TEXTS", items)
        NativeRandom.seed(424242)
        val removed = ArrayList<EventText>()
        val reference = ArrayList(items)
        repeat(3) {
            val picked = list.pick(NativeRandom, true) as EventText
            removed += picked
            reference.remove(picked)
        }
        list.restoreUsed()
        // The list now draws from removed + the rest, so index i of the next pick is the i-th of that order.
        val order = removed + reference
        for (i in order.indices) {
            val copy = TextList("GEN3_TEXTS", items)
            copy.loadState(list.stateToString())
            var r = 0
            // Find a seed whose first draw lands on index i.
            while (r < 10000) {
                NativeRandom.seed(r)
                val draw = NativeRandom.next31() % order.size
                if (draw == i) break
                r++
            }
            NativeRandom.seed(r)
            check(copy.pick(NativeRandom, false) === order[i]) { "Restored text list order differs at $i" }
        }
        return "text list order"
    }

    // ---- G1: ship ending events reseeded from the ship's event seed

    private fun endings(state: InGameState): String {
        var checked = 0
        for (ending in ENDINGS) {
            if (ending.ae != state.content.enableAdvancedEdition) continue
            state.setDifficulty(ending.difficulty)
            state.eventCreator.usedNamesFixture = listOf()
            state.eventCreator.refreshUsedNames()
            val spec = state.eventManager.getShip(ending.ship)
            val ship = state.content.generator.buildShip(state, spec, 0, ending.difficulty, ending.seed)
            check(ship.eventSeed == ending.seed) { "${ending.ship} event seed ${ship.eventSeed}" }
            val template = when (ending.which) {
                "destroyed" -> spec.destroyed
                "deadCrew" -> spec.deadCrew
                "surrender" -> spec.surrender
                "gotaway" -> spec.gotaway
                else -> spec.escape
            }!!
            // Whatever the stream did before, the ship's seed decides.
            NativeRandom.seed(ending.seed xor 0x5a5a5a)
            repeat(17) { NativeRandom.next31() }
            val event = state.eventCreator.createShipEnding(template, ship)!!
            val text = event.text?.let { it.id ?: it.localised } ?: ""
            val resources = "${event.missiles},${event.fuel},${event.droneParts},${event.scrap}"
            check(text == ending.text && resources == ending.resources && event.choices.size == ending.choices) {
                "${ending.ship} ${ending.which} seed ${ending.seed}: $text $resources ${event.choices.size} choices, " +
                        "native ${ending.text} ${ending.resources} ${ending.choices} choices"
            }
            checked++
        }
        return "$checked native ship endings"
    }

    // ---- G3: arriving at a ship event does not reseed before CreateShip

    private fun arrivals(state: InGameState): String {
        var checked = 0
        for (arrival in ARRIVALS) {
            if (arrival.ae != state.content.enableAdvancedEdition) continue
            state.setDifficulty(arrival.difficulty)
            state.eventCreator.usedNamesFixture = arrival.usedNames
            state.eventCreator.refreshUsedNames()

            val elem = Element("event")
            elem.addContent(Element("ship").setAttribute("load", arrival.ship).setAttribute("hostile", "false"))
            val template = state.eventManager.loadEmbeddedEvent(elem, "GEN3_ARRIVAL_${arrival.sample}").value as Event
            val created = CreatedEvent(template, 0)
            created.shipSeed = arrival.seed

            // The ShipAI constructor's two draws come from the stream as it stands, not from the ship seed.
            NativeRandom.restore(arrival.state)
            val first = NativeRandom.next31()
            val second = NativeRandom.next31()
            NativeRandom.restore(arrival.state)
            val trace = ArrayList<Int>()
            NativeRandom.trace = trace
            try {
                state.loadEventShip(created)
            } finally {
                NativeRandom.trace = null
            }
            // CreateShip itself reseeds (0x624bb0) unless the ship has a fixed blueprint.
            val spec = state.eventManager.getShip(arrival.ship)
            val reseeds = !spec.fixedBlueprint
            check(trace.size > 3 && trace[0] == first && trace[1] == second &&
                    (!reseeds || (trace[2] == Int.MIN_VALUE && trace[3] == arrival.seed))) {
                "${arrival.sample}: arrival draws ${trace.take(4)}, expected $first, $second" + if (reseeds) ", then the reseed to ${arrival.seed}" else ""
            }

            val enemy = state.enemy!!
            if (spec.surrender != null)
                check(enemy.surrenderHealth == arrival.surrender) { "${arrival.sample}: surrender ${enemy.surrenderHealth}, native ${arrival.surrender}" }
            if (spec.escape != null)
                check(enemy.escapeHealth == arrival.escape) { "${arrival.sample}: escape ${enemy.escapeHealth}, native ${arrival.escape}" }
            checked++
        }
        state.eventCreator.usedNamesFixture = null
        return "$checked native arrivals"
    }

    // ---- The stalemate surrender: level 0, the global stream

    private fun stalemates(state: InGameState): String {
        var checked = 0
        for (stalemate in STALEMATES) {
            if (stalemate.ae != state.content.enableAdvancedEdition) continue
            state.setDifficulty(stalemate.difficulty)
            state.eventCreator.usedNamesFixture = listOf()
            state.eventCreator.refreshUsedNames()
            state.eventManager.restoreEventState()
            NativeRandom.restore(stalemate.state)
            val trace = ArrayList<Int>()
            NativeRandom.trace = trace
            val event = try {
                state.eventCreator.create(state.eventManager["STALEMATE_SURRENDER"], 0)
            } finally {
                NativeRandom.trace = null
            }
            check(trace == stalemate.draws.toList()) { "${stalemate.sample}: stalemate draws $trace, native ${stalemate.draws.toList()}" }
            check(event != null && event.fuel == 2) { "${stalemate.sample}: stalemate reward ${event?.fuel}" }
            checked++
        }
        state.eventCreator.usedNamesFixture = null
        return "$checked native stalemates"
    }

    // ---- Round 5, item 4: later sectors (quest placement, waits and fleet takeovers, store crew skills)

    /**
     * A native run to a later sector (gen-l* in scan/native-gen: sectors reached by exit jumps, then a console quest
     * and waits with no fuel). The sector is generated from the native tree and layout seeds as in [Run]; [current] is
     * the player's beacon. [quest] is null for runs that went straight to the waits.
     */
    private class LaterRun(
        val ae: Boolean, val sample: String, val treeSeed: Int, val difficulty: Difficulty, val usedNames: List<String>,
        val steps: String, val current: Int, val quest: LaterQuest?, val waits: List<LaterWait>,
    )

    /**
     * StarMap::AddQuest (0x5f8920): the stream state before the quest's creation, the six creation draws and the
     * placement draw (0x5f8a1d), then the beacon that got the quest and its ship seed.
     */
    private class LaterQuest(val state: String, val quest: String, val draws: IntArray, val beacon: Int, val seed: Int)

    /**
     * A wait with no fuel: the stream state after the map window's two angle draws (StarMap::Open 0x5ea3a0), the
     * draws of the fleet takeovers (OvertakeLocation 0x5f4ca0), the wait seed (0x5f4b65) and the NO_FUEL event
     * created after the reseed. [event] and [text] are empty when the native dump of the beacon was not kept;
     * [overtaken] (every overtaken beacon after the wait) and [seeds] (their fleet ship seeds) are null then too.
     */
    private class LaterWait(
        val state: String, val draws: IntArray, val event: String, val text: String, val overtaken: IntArray?, val seeds: IntArray?,
    )

    /** A store opened by the console in a later sector: native crew for sale as `name:race:skill steps`. */
    private class LaterStore(
        val ae: Boolean, val sample: String, val state: String, val level: Int, val difficulty: Difficulty, val sectorType: String,
        val usedNames: List<String>, val crew: List<String>,
    )

    private val LATER_RUNS = listOf(
        LaterRun(true, "gen-l-normal-ae", 632305281, Difficulty.NORMAL, listOf("Emma", "Kirby", "Teldarin"), "0:861726862,1:905765673,3:1626467593", 0,
            LaterQuest("8638212680796231823", "QUEST_CREWDEAD", intArrayOf(1332733755, 1182518391, 1453680911, 1331965833, 1421692076, 1578990998, 2001432920), 14, 1421692076),
            listOf(
                LaterWait("5003789456973669776", intArrayOf(516322131, 1319281231, 355377524, 1752164440, 389054446, 2048467880, 508164365, 1174941754), "", "", null, null),
                LaterWait("-416503452289968408", intArrayOf(1377848619, 1110674568, 1292158147, 457906993, 1054879330, 1047651500, 1589691005, 1072380974), "", "", null, null),
                LaterWait("1024962970120388110", intArrayOf(979921881, 1243869818, 1042849270, 1566128668, 1236647560, 514247574, 29816071, 1801068420), "", "", null, null),
                LaterWait("5813232813365406282", intArrayOf(409449050, 1813880029, 1387932058, 1119789217, 891542347, 1057206746, 1081204159, 1372365958, 1893680997, 1458437089, 1871690508, 413483367, 220027632, 1230573028, 688143579, 1331087198, 1652837345, 1763166908, 1127500700, 1858137029), "", "", null, null),
            ),
        ),
        LaterRun(true, "gen-l2-normal-ae", 1001391509, Difficulty.NORMAL, listOf("Bovee", "Davion", "Vincent"), "0:1050257195,1:219483586,3:866585064", 0,
            LaterQuest("3928796353153491887", "QUEST_CREWDEAD", intArrayOf(614246403, 1132004891, 1197496154, 391541963, 2115567873, 1306097055, 1508801934), 10, 2115567873),
            listOf(
                LaterWait("-5078827167188113650", intArrayOf(1714644184, 487114059, 1522518578, 375719357, 868759562, 1513068707, 1983607702, 604083596), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_10", intArrayOf(), intArrayOf()),
                LaterWait("-4504597295012229321", intArrayOf(519554593, 1443690011, 2123899683, 603754634, 880355310, 1646147079, 1045293647, 754029350, 2057345966, 893003982, 2059737077, 1939468902, 1102496995, 169851324, 403861109, 1853493138, 318796607, 2115149890, 2141924585, 26724303, 62064271, 646791566, 1276710152), "FUEL_TRADER", "text_FUEL_TRADER_TEXT_LIST_4", intArrayOf(), intArrayOf()),
            ),
        ),
        LaterRun(false, "gen-l2-hard-van", 1242611705, Difficulty.HARD, listOf("Hector", "Remo", "Lanng"), "0:251783331,1:1326516423,3:1477708016", 0,
            LaterQuest("-574683821723508617", "QUEST_CREWDEAD", intArrayOf(2027150914, 756130878, 933194611, 38852749, 609720579, 1543552375, 273371140), 6, 609720579),
            listOf(
                LaterWait("-387284895303667012", intArrayOf(906994771, 2145852074, 1165316071, 903740456, 433705048, 857533844, 1743656660, 100396857), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_8", intArrayOf(), intArrayOf()),
                LaterWait("-3909526847311414319", intArrayOf(699101987, 531982953, 984994669, 1584663709, 1352153249, 330603786, 891837220, 1380345803, 988484496, 896221157, 750733160, 853598096, 156904557, 984743464, 1183256057, 1165827985, 1244033142, 611245279, 1764996421, 127863431, 1315692407, 2069415385, 367882586, 949643271, 1879980477, 500547614, 1903137779, 1378264400, 601678809, 1665468469, 1592233096, 230992047, 683107480, 522251993, 1646202093, 1780384309, 2000289785, 417367937, 606919294, 1815690056, 785353118, 2038012285, 1276841168, 1939208362, 793947497, 1928828079), "FUEL_APPROACH", "event_FUEL_APPROACH_text", intArrayOf(), intArrayOf()),
            ),
        ),
        LaterRun(true, "gen-l3-normal-ae", 204512612, Difficulty.NORMAL, listOf("James", "Shelton", "Varnas"), "0:1278357534,1:1611303090,3:1328777013,7:1821012988,9:1015820488,12:1727055061,16:1102159053", 3,
            null,
            listOf(
                LaterWait("2367271550305150163", intArrayOf(87735273, 979061955, 1967599579, 473334165, 1547180938, 470552161, 385653629, 165177136), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_1", intArrayOf(), intArrayOf()),
                LaterWait("4292948022158460524", intArrayOf(409328896, 711731970, 596832961, 690954284, 76020324, 2142282214, 1047335957, 1255880423), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_3", intArrayOf(), intArrayOf()),
                LaterWait("-6306476575662980749", intArrayOf(2065542114, 296790423, 1013092361, 1122841145, 531584808, 885535956, 1375835867, 1452526149, 1858751727, 1232662067, 1704029023, 773813997, 1621820758, 283763948, 2066519802, 502711064, 1839340431, 542223948, 1909239585), "FUEL_EXPLORE", "event_FUEL_EXPLORE_text", intArrayOf(), intArrayOf()),
                LaterWait("2070186903890233147", intArrayOf(73097097, 392332645, 996001567, 1361110166, 923394965, 2138463580, 2053170757, 2147291457), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_10", intArrayOf(), intArrayOf()),
                LaterWait("-2079550138619784081", intArrayOf(2098215156, 657687725, 2038399620, 344975573, 1622929053, 284332369, 1594161263, 1168346394, 18084872, 1390433588, 942269944, 291244669, 1835734004, 1306747071, 1494361191, 658863887, 1200948727, 1763215309, 42169110, 790705283), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_11", intArrayOf(0, 1), intArrayOf(942269944, 1622929053)),
                LaterWait("3411640188060653649", intArrayOf(447805294, 1462462825, 662773996, 1417829208, 1226723803, 638604478, 671404201, 1687537451, 1327229160, 1811854343, 1979546238, 1803444688, 244756479, 229198921, 96111694, 1943990629, 1939295590, 306566138, 100112252, 1420456830, 1431119074, 607978753, 23954857, 602188984, 1396366228), "FUEL_EXPLORE", "event_FUEL_EXPLORE_text", intArrayOf(0, 1, 2), intArrayOf(942269944, 1622929053, 1226723803)),
                LaterWait("2298669641478184586", intArrayOf(1498857084, 1957692572, 1908519723, 818391380, 183806217, 1884975980, 1995627624, 27309844, 1847095720, 1167479453, 1764320339, 1338223296, 1698535447), "NO_FUEL_FLEET_DLC", "event_NO_FUEL_FLEET_DLC_text", intArrayOf(0, 1, 2, 3), intArrayOf(942269944, 1622929053, 1226723803, 1338223296)),
            ),
        ),
        LaterRun(false, "gen-l3-hard-van", 740898086, Difficulty.HARD, listOf("Maria", "Eoin", "Zerimar"), "0:722642573,1:613260138,3:2086212207,6:299731189,8:1623708213,12:226093786,14:1170129370", 2,
            null,
            listOf(
                LaterWait("1491028280392451840", intArrayOf(1262731253, 1819519344, 2076554104, 81149572, 8587013, 85175884, 1925736364, 2034525017, 511225543, 310238994, 2143891485, 528386017, 949996541, 622638112, 885329036, 1563424983, 350821505, 1517829204, 2122676390, 2123895325, 1870979502, 53627276, 15293492), "FUEL_TRADER", "text_FUEL_TRADER_TEXT_LIST_5", intArrayOf(), intArrayOf()),
                LaterWait("-7072704463260008279", intArrayOf(1145231473, 253508215, 985516830, 265183626, 1107794478, 442410008, 1736079429, 1379112458), "FUEL_NOTHING", "text_FUEL_NOTHING_LIST_2", intArrayOf(), intArrayOf()),
                LaterWait("-4427918615301145136", intArrayOf(281845091, 1772735048, 193709971, 419416125, 1058575924, 1059316409, 1803622335, 1076656769, 1316233165, 1349570107, 472578130, 1603359517, 580666045, 1241295389, 1732049692, 2060678294, 1605447515, 2140938160, 1873046292, 1500947815, 619260883, 1890028618, 1016952374, 526652740, 1072646608, 1099337068, 530027957, 1948712414, 792133838, 2076542984, 242415969, 1241751417, 270727192, 2061102277, 368856245, 1742142562, 1818620196, 1064877723, 432159678, 787381972, 1884754845, 1732162813, 9598235, 1303761533, 346950664, 1685747165, 1798249293, 319705781, 135409178, 450312769, 1228533658, 1563278480, 1104948439, 1137550393, 1667419773), "FUEL_APPROACH", "event_FUEL_APPROACH_text", intArrayOf(), intArrayOf()),
            ),
        ),
    )
    private val LATER_STORES = listOf(
        LaterStore(true, "gen-l2-normal-ae-store0", "-4596236973647626039", 2, Difficulty.NORMAL, "ZOLTAN_SECTOR", listOf("Bovee", "Davion", "Vincent"), listOf("Aisha:human:000000", "Alex Rigopulos:energy:000000", "Artyom:engi:000000")),
        LaterStore(true, "gen-l2-normal-ae-store1", "619871660349868800", 2, Difficulty.NORMAL, "ZOLTAN_SECTOR", listOf("Bovee", "Davion", "Vincent"), listOf("Kadreal:human:000000", "Felicity:slug:000000", "Enzo Martin:mantis:000100")),
        LaterStore(true, "gen-l2-normal-ae-store2", "-3977736080076505230", 2, Difficulty.NORMAL, "ZOLTAN_SECTOR", listOf("Bovee", "Davion", "Vincent"), listOf()),
        LaterStore(true, "gen-l2-normal-ae-store3", "4201985919446898098", 2, Difficulty.NORMAL, "ZOLTAN_SECTOR", listOf("Bovee", "Davion", "Vincent"), listOf("Bryan:human:000000", "Berko:slug:001000", "AJ Hager:energy:010000")),
        LaterStore(true, "gen-l2-normal-ae-store4", "1379227894107551897", 2, Difficulty.NORMAL, "ZOLTAN_SECTOR", listOf("Bovee", "Davion", "Vincent"), listOf()),
        LaterStore(true, "gen-l2-normal-ae-store5", "2711985719988923430", 2, Difficulty.NORMAL, "ZOLTAN_SECTOR", listOf("Bovee", "Davion", "Vincent"), listOf("Matthieu:energy:000001", "Jan Magne:engi:010000", "Felicity:human:000000")),
        LaterStore(false, "gen-l2-hard-van-store0", "-3880156631578346798", 2, Difficulty.HARD, "ZOLTAN_HOME", listOf("Hector", "Remo", "Lanng"), listOf("Bryan:rock:010000", "Jon:mantis:000100")),
        LaterStore(false, "gen-l2-hard-van-store1", "386104850158567001", 2, Difficulty.HARD, "ZOLTAN_HOME", listOf("Hector", "Remo", "Lanng"), listOf("Noemi:human:000000", "Alex Rigopulos:human:001000")),
        LaterStore(false, "gen-l2-hard-van-store2", "-866902189167532206", 2, Difficulty.HARD, "ZOLTAN_HOME", listOf("Hector", "Remo", "Lanng"), listOf()),
        LaterStore(false, "gen-l2-hard-van-store3", "-3553360129931457438", 2, Difficulty.HARD, "ZOLTAN_HOME", listOf("Hector", "Remo", "Lanng"), listOf("Markus:slug:100000", "Maxwell:rock:000010", "Ken:human:000000")),
        LaterStore(false, "gen-l2-hard-van-store4", "-121497536176007245", 2, Difficulty.HARD, "ZOLTAN_HOME", listOf("Hector", "Remo", "Lanng"), listOf("Giles:energy:000100", "Stick:energy:000000", "Yevon Si:slug:000001")),
        LaterStore(false, "gen-l2-hard-van-store5", "994024970188194460", 2, Difficulty.HARD, "ZOLTAN_HOME", listOf("Hector", "Remo", "Lanng"), listOf("Tee Lek:slug:000000", "Lombard:engi:000000", "Kara:human:000000")),
        LaterStore(true, "gen-l3-normal-ae-L4-store0", "7706329244515984781", 4, Difficulty.NORMAL, "ROCK_HOME", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L4-store1", "-1397891485247045489", 4, Difficulty.NORMAL, "ROCK_HOME", listOf("James", "Shelton", "Varnas"), listOf("Scoops:rock:000010", "Feras:rock:100000", "Jon:rock:000010")),
        LaterStore(true, "gen-l3-normal-ae-L4-store2", "-7871722826257274159", 4, Difficulty.NORMAL, "ROCK_HOME", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L4-store3", "6801379630752346118", 4, Difficulty.NORMAL, "ROCK_HOME", listOf("James", "Shelton", "Varnas"), listOf("Butters:rock:010000", "Sophia:rock:010100", "Berko:rock:010100")),
        LaterStore(true, "gen-l3-normal-ae-L4-store4", "2368409981522016899", 4, Difficulty.NORMAL, "ROCK_HOME", listOf("James", "Shelton", "Varnas"), listOf("Teldarin:energy:010001", "Cremity:rock:001100", "Scoops:rock:000010")),
        LaterStore(true, "gen-l3-normal-ae-L4-store5", "-4063023863738551264", 4, Difficulty.NORMAL, "ROCK_HOME", listOf("James", "Shelton", "Varnas"), listOf("Grozoly:energy:000010", "Bigby:human:100000", "Magmaros:human:011000")),
        LaterStore(true, "gen-l3-normal-ae-L6-store0", "8961912572415509045", 6, Difficulty.NORMAL, "NEBULA_SECTOR", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L6-store1", "5069743301202148349", 6, Difficulty.NORMAL, "NEBULA_SECTOR", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L6-store2", "-7241273196217919689", 6, Difficulty.NORMAL, "NEBULA_SECTOR", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L6-store3", "3476910885179490401", 6, Difficulty.NORMAL, "NEBULA_SECTOR", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L6-store4", "5835391204588037880", 6, Difficulty.NORMAL, "NEBULA_SECTOR", listOf("James", "Shelton", "Varnas"), listOf()),
        LaterStore(true, "gen-l3-normal-ae-L6-store5", "5073018319962726207", 6, Difficulty.NORMAL, "NEBULA_SECTOR", listOf("James", "Shelton", "Varnas"), listOf("Tom Banks:slug:000001", "Rynhart:human:000120", "Scott Kletzkin:energy:001000")),
        LaterStore(false, "gen-l3-hard-van-L4-store0", "2111340725298788027", 4, Difficulty.HARD, "ZOLTAN_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Debbie:energy:100001", "Graffin:energy:010000")),
        LaterStore(false, "gen-l3-hard-van-L4-store1", "-4419526133465149513", 4, Difficulty.HARD, "ZOLTAN_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf()),
        LaterStore(false, "gen-l3-hard-van-L4-store2", "-6065538823739615608", 4, Difficulty.HARD, "ZOLTAN_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Luaan Ti:slug:100010", "Julian Rafn:human:100001")),
        LaterStore(false, "gen-l3-hard-van-L4-store3", "-6397446185315016566", 4, Difficulty.HARD, "ZOLTAN_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Ken:rock:000002", "Jason Bovee:slug:010000", "Henrik Aasted:human:010000")),
        LaterStore(false, "gen-l3-hard-van-L4-store4", "1165498685596970766", 4, Difficulty.HARD, "ZOLTAN_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf()),
        LaterStore(false, "gen-l3-hard-van-L4-store5", "-8483147928133177528", 4, Difficulty.HARD, "ZOLTAN_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Steven:energy:000010", "Jax:slug:000100", "Jason Bovee:human:000001")),
        LaterStore(false, "gen-l3-hard-van-L6-store0", "-8143797921849435476", 6, Difficulty.HARD, "ROCK_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Thomas Bloch:rock:010020", "Atreyu:energy:020001", "Ihlay Caris:rock:001000")),
        LaterStore(false, "gen-l3-hard-van-L6-store1", "-2918669038888864403", 6, Difficulty.HARD, "ROCK_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf()),
        LaterStore(false, "gen-l3-hard-van-L6-store2", "-357767824336260388", 6, Difficulty.HARD, "ROCK_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Stephen Eckman:human:100000", "Remo:human:000010", "Lisandra:human:020000")),
        LaterStore(false, "gen-l3-hard-van-L6-store3", "9177991034274885321", 6, Difficulty.HARD, "ROCK_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Turing:rock:020000", "Artyom:energy:002000")),
        LaterStore(false, "gen-l3-hard-van-L6-store4", "-7816397037404557575", 6, Difficulty.HARD, "ROCK_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf()),
        LaterStore(false, "gen-l3-hard-van-L6-store5", "6090892994336858432", 6, Difficulty.HARD, "ROCK_SECTOR", listOf("Maria", "Eoin", "Zerimar"), listOf("Sem:rock:001000", "Teldarin:human:000001")),
    )

    private fun laterRuns(state: InGameState): String {
        var quests = 0
        var waits = 0
        for (run in LATER_RUNS) {
            if (run.ae != state.content.enableAdvancedEdition) continue
            state.setDifficulty(run.difficulty)
            state.eventManager.resetEventState()
            state.eventCreator.usedNamesFixture = run.usedNames
            val map = GameMap(state.content.datafile, state.eventManager, state.content.enableAdvancedEdition, run.treeSeed)
            val flat = map.sectors.flatten()
            var sector: Sector? = null
            for (step in run.steps.split(",")) {
                val (idx, layout) = step.split(":").map { it.toInt() }
                sector = map.generateSector(flat[idx], state, layout)
            }
            sector!!
            val current = sector.beacons[run.current]

            val quest = run.quest
            if (quest != null) {
                state.eventCreator.refreshUsedNames()
                NativeRandom.restore(quest.state)
                val questTrace = ArrayList<Int>()
                NativeRandom.trace = questTrace
                try {
                    sector.addQuest(current, state.eventCreator, quest.quest)
                } finally {
                    NativeRandom.trace = null
                }
                check(questTrace == quest.draws.toList()) { "${run.sample}: quest draws $questTrace, native ${quest.draws.toList()}" }
                val placed = sector.beacons.indices.filter { sector.beacons[it].hasQuest }
                check(placed == listOf(quest.beacon)) { "${run.sample}: quest placed at $placed, native ${quest.beacon}" }
                check(sector.beacons[quest.beacon].createdEvent.shipSeed == quest.seed) {
                    "${run.sample}: quest ship seed ${sector.beacons[quest.beacon].createdEvent.shipSeed}, native ${quest.seed}"
                }
                quests++
            }

            for ((n, wait) in run.waits.withIndex()) {
                val what = "${run.sample} wait ${n + 1}"
                state.eventCreator.refreshUsedNames()
                NativeRandom.restore(wait.state)
                val trace = ArrayList<Int>()
                NativeRandom.trace = trace
                val event = try {
                    sector.advanceFleet(state, current)
                    val name = if (current.isOvertaken) (if (state.content.enableAdvancedEdition) "NO_FUEL_FLEET_DLC" else "NO_FUEL_FLEET") else "NO_FUEL"
                    val seed = NativeRandom.next31()
                    NativeRandom.seed(seed)
                    state.eventCreator.create(state.eventManager[name], sector.sectorNumber)!!
                } finally {
                    NativeRandom.trace = null
                }
                current.createdEvent = event
                // The trace marks a reseed as MIN_VALUE followed by the seed; the native trace has the draw only.
                val draws = trace.filterIndexed { i, v -> v != Int.MIN_VALUE && (i == 0 || trace[i - 1] != Int.MIN_VALUE) }
                check(draws == wait.draws.toList()) { "$what: draws $draws, native ${wait.draws.toList()}" }
                if (wait.event.isNotEmpty()) {
                    val text = event.text?.let { it.id ?: it.localised } ?: ""
                    check(event.template.debugId == wait.event && text == wait.text) {
                        "$what: event ${event.template.debugId} $text, native ${wait.event} ${wait.text}"
                    }
                }
                if (wait.overtaken != null) {
                    val overtaken = sector.beacons.indices.filter { sector.beacons[it].isOvertaken }
                    check(overtaken == wait.overtaken.toList()) { "$what: overtaken $overtaken, native ${wait.overtaken.toList()}" }
                    val seeds = overtaken.map { sector.beacons[it].createdEvent.shipSeed }
                    check(seeds == wait.seeds!!.toList()) { "$what: fleet ship seeds $seeds, native ${wait.seeds.toList()}" }
                }
                waits++
            }
        }
        state.eventCreator.usedNamesFixture = null
        return "$quests native quests, $waits native waits"
    }

    private fun laterStores(state: InGameState): String {
        var checked = 0
        for (known in LATER_STORES) {
            if (known.ae != state.content.enableAdvancedEdition) continue
            state.setDifficulty(known.difficulty)
            state.eventCreator.usedNamesFixture = known.usedNames
            val map = GameMap(state.content.datafile, state.eventManager, state.content.enableAdvancedEdition, 1)
            state.setLootPool(LootPool(state.blueprintManager, map.sectorType(known.sectorType)))
            NativeRandom.restore(known.state)
            val store = StoreData()
            store.generateRandomContents(state, NativeRandom, known.level)
            // Skill steps: the store's random skills (0x6058a0) in half-skill steps, as the native CrewBlueprint's
            // (current, max) pairs divided by max / 2.
            val crew = store.crew.filterNotNull().map { m ->
                m.name + ":" + m.race.name + ":" + Skill.values().joinToString("") { ((m.skills[it] ?: 0f) * 2f).toInt().toString() }
            }
            check(crew == known.crew) { "${known.sample}: level ${known.level} store crew $crew, native ${known.crew}" }
            checked++
        }
        state.eventCreator.usedNamesFixture = null
        return "$checked native later stores"
    }

    // ---- Round 5b: a blueprint whose hull image is missing from the data

    /**
     * LONG_ELITE_MED and LONG_ELITE_HARD name img="rebel_long_hard", which the data does not have. The original
     * builds them (console `load`, native dumps scan/native-lay/lay-*-LONG_ELITE_*.json) on the rebel_long layout
     * with no hull drawn: 7 rooms, systems as (type, room) below. The port must build them too, with no hull image.
     */
    private fun missingHulls(state: InGameState): String {
        val nativeSystems = listOf("oxygen" to 2, "pilot" to 0, "shields" to 6, "engines" to 5, "weapons" to 4).sortedBy { it.first }
        var checked = 0
        for (name in listOf("LONG_ELITE_MED", "LONG_ELITE_HARD")) {
            val elem = Element("ship").setAttribute("name", "GEN3_HULL_$name").setAttribute("auto_blueprint", name)
            val spec = xyz.znix.xftl.shipgen.EnemyShipSpec(elem, state.blueprintManager, state.eventManager)
            NativeRandom.seed(12345)
            val ship = state.content.generator.buildShip(state, spec, 1, Difficulty.NORMAL, 12345)
            check(ship.type.name == name && ship.type.layout == "rebel_long") { "$name built as ${ship.type.name} on ${ship.type.layout}" }
            check(ship.hullImage == null && ship.hullWidth == 0 && ship.hullHeight == 0) { "$name has a hull image" }
            check(ship.rooms.size == 7) { "$name has ${ship.rooms.size} rooms, native 7" }
            val systems = ship.systems.filter { it.blueprint.type in nativeSystems.map { s -> s.first } }
                .map { it.blueprint.type to (it.room?.id ?: -1) }.sortedBy { it.first }
            check(systems == nativeSystems) { "$name systems $systems, native $nativeSystems" }
            checked++
        }
        return "$checked ships without a hull image"
    }

    // ---- G2, G4, G6-G9: whole native maps

    private fun nativeMaps(state: InGameState): String {
        var maps = 0
        for (run in RUNS) {
            if (run.ae != state.content.enableAdvancedEdition) continue
            for ((n, text) in generateRun(state, run).withIndex()) {
                check(text.hashCode() == run.hashes[n]) {
                    "${run.sample} sector $n differs from the native map:\n" + text.take(1500)
                }
                maps++
            }
        }
        return "$maps native maps"
    }

    /** The run's sectors, generated in order as one game, as [canonical] strings. */
    internal fun generateRun(state: InGameState, sample: String): List<String> = generateRun(state, RUNS.first { it.sample == sample })

    private fun generateRun(state: InGameState, run: Run): List<String> {
        state.setDifficulty(run.difficulty)
        state.eventManager.resetEventState()
        state.eventCreator.usedNamesFixture = run.usedNames
        val map = GameMap(state.content.datafile, state.eventManager, state.content.enableAdvancedEdition, run.treeSeed)
        val flat = map.sectors.flatten()
        return run.steps.split(",").map { step ->
            val sector = if (step.startsWith("T=")) {
                val (spec, layout) = step.substring(2).split(":")
                val (typeName, column) = spec.split("@")
                val info = map.SectorInfo(column.toInt(), 0, map.sectorType(typeName)!!, GameMap.SectorClass.HOSTILE)
                map.generateSector(info, state, layout.toInt())
            } else {
                val (idx, layout) = step.split(":").map { it.toInt() }
                map.generateSector(flat[idx], state, layout)
            }
            canonical(sector)
        }
    }

    /** One line per beacon: index, exit, nebula, last-stand mark, then the created event tree. */
    private fun canonical(sector: Sector): String {
        val sb = StringBuilder()
        for ((i, beacon) in sector.beacons.withIndex()) {
            sb.append(i).append(',').append(if (beacon.isExit || beacon.isBase) 1 else 0)
            sb.append(',').append(if (beacon.inNebula) 1 else 0)
            sb.append(',').append(if (beacon.browserFleetThreatened) 1 else 0).append(':')
            sb.append(event(beacon.createdEvent, beacon === sector.startBeacon)).append('\n')
        }
        return sb.toString()
    }

    private fun event(e: CreatedEvent?, start: Boolean): String {
        if (e == null) return "-"
        val text = e.text?.let { it.id ?: it.localised } ?: ""
        // A lone "continue" choice without an event is the original's default choice.
        var choices: List<xyz.znix.xftl.sector.CreatedChoice> = e.choices
        if (choices.size == 1 && choices[0].event == null && (choices[0].text.id ?: choices[0].text.localised) == "continue")
            choices = emptyList()
        val empty = text.isEmpty() && choices.isEmpty() && e.missiles == 0 && e.fuel == 0 && e.droneParts == 0 &&
                e.scrap == 0 && e.crew == null && e.shipSeed == 0 && e.weapon == null && e.drone == null &&
                e.augment == null && e.boarders == 0
        if (empty) return "-"
        val crew = e.crew?.let { it.race.name + ":" + it.name.filter { c -> c.isLetterOrDigit() } } ?: ""
        val seed = e.shipSeed.toLong() and 0xffffffffL
        return (if (start) "" else text) + "|${e.missiles},${e.fuel},${e.droneParts},${e.scrap}|$seed|${e.boarders}|" +
                "${e.weapon?.name ?: ""},${e.drone?.name ?: ""},${e.augment?.name ?: ""}|$crew|[" +
                choices.joinToString(";") { (it.text.id ?: it.text.localised) + ">" + event(it.event, false) } + "]"
    }
}
