package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.EventText
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sector.TextList
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.vanillasave.VanillaSaveWriter

/**
 * Checks for the second event pass (see .build/gameplay-1to1/agents/events2.md): the fleet events the fleet
 * creates when it overtakes a beacon (StarMap 0x5f4ca0, 0x5f5280, 0x5f4ed0), the crew kill seed
 * (WorldManager +0x12a0, 0x4a3000), the boarder draw order (0x408260 -> 0x4aea60), the loaded text lists
 * (0x5e2e50), the START_GAME tip (0x5f9e00), the no-fuel wait (0x5f4510) and the parts of the original's save
 * the engine has to keep (seeds, wait seed, route flags).
 *
 * The wait known answer was captured from the original (events2-wait*.json in the sessions folder).
 */
internal object GameplayParityEvents2Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val streamBefore = NativeRandom.snapshot()
        val parts = ArrayList<String>()
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        try {
            parts += overtake(state)
            parts += advanceFleet(state)
            parts += lastStand(state)
            parts += crewKillSeed(state)
            parts += boarders(state)
            parts += textLists(state)
            parts += startTip(state)
            parts += wait(state)
            parts += statuses(state)
            parts += departure(state)
            parts += nativeOvertake(state)
            parts += nativeQuest(state)
            parts += nativeLastStand(state)
            parts += saves(main, container)
        } finally {
            NativeRandom.trace = null
            state.eventCreator.usedNamesFixture = null
            state.eventManager.resetEventState()
            NativeRandom.restore(streamBefore)
        }
        return "events2: " + parts.joinToString(", ")
    }

    private fun sig(event: CreatedEvent?): String {
        if (event == null) return "null"
        return event.template.deserialisationId + "@" + (event.text?.localised ?: "") + "#" + event.choices.size
    }

    /** The beacon state the fleet changes, so a check can run twice from the same position. */
    private class BeaconMemo(val beacon: Beacon) {
        val overtaken = beacon.isOvertaken
        val fleet = beacon.hasFleetEvent
        val forced = beacon.forcedNebula
        val created = beacon.createdEvent
        val threatened = beacon.browserFleetThreatened
        val cleared = beacon.eventCleared
        fun restore() {
            beacon.eventCleared = cleared
            beacon.isOvertaken = overtaken
            beacon.hasFleetEvent = fleet
            beacon.forcedNebula = forced
            beacon.createdEvent = created
            beacon.browserFleetThreatened = threatened
        }
    }

    // ---- StarMap::OvertakeLocation (0x5f4ca0)

    private fun overtake(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val level = sector.sectorNumber
        val plain = sector.beacons.first { !it.isExit && !it.isBase && !it.isOvertaken && !it.isMapNebula && it !== state.currentBeacon }
        val memo = BeaconMemo(plain)
        val wanted = "FLEET_EASY" + (if (state.isAdvancedEdition) "_DLC" else "")
        state.eventCreator.usedNamesFixture = listOf()
        try {
            for (seed in intArrayOf(7, 8191, 1956517240)) {
                // The reference: the same event created directly from the same seed.
                state.eventManager.restoreEventState()
                state.eventCreator.refreshUsedNames()
                NativeRandom.seed(seed)
                val (direct, directDraws) = counted { state.eventCreator.create(state.eventCreator.eventFor(wanted), level) }
                state.eventManager.restoreEventState()

                memo.restore()
                state.eventCreator.refreshUsedNames()
                NativeRandom.seed(seed)
                val (_, draws) = counted { sector.overtake(state, plain) }
                check(plain.isOvertaken && plain.hasFleetEvent && !plain.browserFleetThreatened) { "Overtaken beacon flags" }
                check(sig(plain.createdEvent) == sig(direct)) { "Overtake event ${sig(plain.createdEvent)}, direct ${sig(direct)}" }
                check(draws == directDraws) { "Overtake made $draws draws, direct creation $directDraws" }
                state.eventManager.restoreEventState()
            }

            // A nebula picks the nebula fleet event.
            memo.restore()
            plain.inNebula = true
            NativeRandom.seed(5)
            sector.overtake(state, plain)
            check(plain.createdEvent.template.deserialisationId.contains("FLEET_EASY_NEBULA")) {
                "Nebula overtake gave ${sig(plain.createdEvent)}"
            }
        } finally {
            plain.inNebula = false
            memo.restore()
            state.eventManager.restoreEventState()
        }

        // The exit beacon (outside the last stand) gets the beacon variant and becomes a fleet beacon.
        val exit = sector.beacons.firstOrNull { it.isExit }
        if (exit != null && !sector.isLastStand) {
            val exitMemo = BeaconMemo(exit)
            try {
                exitMemo.restore()
                NativeRandom.seed(3)
                sector.overtake(state, exit)
                check(exit.createdEvent.template.deserialisationId.contains("FLEET_EASY_BEACON")) {
                    "Exit overtake gave ${sig(exit.createdEvent)}"
                }
            } finally {
                exitMemo.restore()
                state.eventManager.restoreEventState()
            }
        }
        return "overtake (events, draws, flags)"
    }

    // ---- StarMap::UpdateDangerZone (0x5f5280)

    private fun advanceFleet(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val memos = sector.beacons.map { BeaconMemo(it) }
        val centre = sector.dangerZoneCentre.x
        val modifier = sector.fleetAdvanceModifier
        val byX = sector.beacons.sortedBy { it.pos.x }
        val start = byX[minOf(2, byX.size - 1)].pos.x - 800
        state.eventCreator.usedNamesFixture = listOf()
        try {
            val results = ArrayList<List<String>>()
            repeat(2) {
                memos.forEach { it.restore() }
                state.eventManager.restoreEventState()
                state.eventCreator.refreshUsedNames()
                sector.dangerZoneCentre.x = start
                sector.fleetAdvanceModifier = modifier
                NativeRandom.seed(77)
                sector.advanceFleet(state, state.currentBeacon)
                // Every beacon the zone covers is taken (strictly inside the radius); a taken one has its fleet event.
                for (beacon in sector.beacons) {
                    val covered = sector.distanceToDangerZone(beacon) < 767f
                    check(!covered || beacon.isOvertaken) { "A beacon inside the zone was not taken" }
                    val before = memos.first { it.beacon === beacon }
                    check(before.overtaken || !beacon.isOvertaken || beacon.hasFleetEvent) { "A beacon taken by the fleet has no fleet event" }
                }
                results += sector.beacons.map { sig(it.createdEvent) + it.isOvertaken }
            }
            check(results[0] == results[1]) { "Advancing from the same position and stream gave different events" }
        } finally {
            memos.forEach { it.restore() }
            sector.dangerZoneCentre.x = centre
            sector.fleetAdvanceModifier = modifier
            state.eventManager.restoreEventState()
        }
        return "advanceFleet (zone, events repeat)"
    }

    // ---- Captured in play: the fleet overtakes beacons (events2-ov1, events2-nff1)

    /**
     * Native runs from one saved game: opening the beacon map drew two values (StarMap::Open 0x5ea3a0), then a wait
     * advanced the fleet and it took beacons in the order below, each with a fresh FLEET_EASY_DLC (6 draws, the
     * ship seed is the fifth), and a wait at a taken beacon made NO_FUEL_FLEET_DLC (6 draws).
     */
    private fun nativeOvertake(state: InGameState): String {
        val xs = intArrayOf(50, 91, 56, 77, 165, 151, 142)
        val ys = intArrayOf(51, 169, 275, 386, 80, 305, 353)
        val none = BooleanArray(7)
        val one = Sector.overtakeOrder(xs, ys, none, 229, -740, -676)
        check(one == listOf(2, 0)) { "Overtake order from -740 was $one" }
        val two = Sector.overtakeOrder(xs, ys, none, 229, -710, -646)
        check(two == listOf(2, 0, 1, 3)) { "Overtake order from -710 was $two" }

        // The stream the original had: seed 1877683381, 435 draws, the map's two draws, then the events.
        val sector = state.currentBeacon.sector
        val plain = sector.beacons.filter { !it.isExit && !it.isBase && !it.isOvertaken && !it.isMapNebula && it !== state.currentBeacon }.take(2)
        val memos = plain.map { BeaconMemo(it) }
        state.eventCreator.usedNamesFixture = listOf()
        try {
            state.eventManager.restoreEventState()
            state.eventCreator.refreshUsedNames()
            NativeRandom.seed(1877683381)
            repeat(435 + 2) { NativeRandom.next31() }
            for ((index, wanted) in listOf(2111268312, 1678684540).withIndex()) {
                val (_, draws) = counted { sector.overtake(state, plain[index]) }
                check(draws == 6) { "Overtake $index made $draws draws, the original 6" }
                check(plain[index].createdEvent.shipSeed == wanted) {
                    "Overtake $index ship seed ${plain[index].createdEvent.shipSeed}, the original $wanted"
                }
            }

            // The wait at a taken beacon: the wait seed, then NO_FUEL_FLEET_DLC.
            state.eventManager.restoreEventState()
            NativeRandom.seed(1678684540)
            val fleetWait = if (state.isAdvancedEdition) "NO_FUEL_FLEET_DLC" else "NO_FUEL_FLEET"
            val (event, draws) = counted { state.eventCreator.create(state.eventManager[fleetWait], 0) }
            check(event != null && draws == 6 && event.shipSeed == 1925173499) {
                "The fleet wait made $draws draws, ship seed ${event?.shipSeed}"
            }
        } finally {
            memos.forEach { it.restore() }
            state.eventManager.restoreEventState()
        }
        return "overtake order and draws (captured)"
    }

    // ---- StarMap::AddQuest (0x5f8920), captured in play (events2-aq1)

    /**
     * The console event QUEST_CREWDEAD_CONTINUE, choice 1, added a quest: 6 draws for the quest event
     * (its ship seed the fifth), then one for the placement (value % the 9 suitable beacons chose the fifth of
     * them, which the engine's own rule list reproduces from the saved map). Between map and event the original
     * had made 15 draws for a particle burst.
     */
    private fun nativeQuest(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val memos = sector.beacons.map { BeaconMemo(it) }
        val quests = sector.beacons.map { Pair(it.hasQuest, it.questName) }
        state.eventCreator.usedNamesFixture = listOf()
        try {
            state.eventManager.restoreEventState()
            state.eventCreator.refreshUsedNames()
            NativeRandom.seed(1877683381)
            repeat(245 + 15) { NativeRandom.next31() }
            val (placement, draws) = counted {
                sector.addQuest(state.currentBeacon, state.eventCreator, "QUEST_CREWDEAD")
            }
            check(placement == Sector.QuestPlacement.PLACED) { "The quest was not placed: $placement" }
            check(draws == 7) { "AddQuest made $draws draws, the original 7" }
            val placed = sector.beacons.filter { it.hasQuest }
            check(placed.size == 1 && placed[0].createdEvent.shipSeed == 1476201636) {
                "AddQuest ship seed ${placed.firstOrNull()?.createdEvent?.shipSeed}, the original 1476201636"
            }
        } finally {
            memos.forEach { it.restore() }
            sector.beacons.forEachIndexed { i, b -> b.hasQuest = quests[i].first; b.questName = quests[i].second; b.clearEnvironment() }
            state.eventManager.restoreEventState()
        }
        return "AddQuest draws (captured)"
    }

    // ---- StarMap 0x5f4ed0, captured in sector 8 (events2-ls4)

    /**
     * A wait in the last stand: the map's two draws, then the step took the beacon marked last turn (Location +0x9c)
     * with FLEET_EASY_DLC (6 draws, ship seed the fifth), then drew `% n` over the 21 beacons that are neither the
     * base nor taken, and marked that one (the 11th of them).
     */
    private fun nativeLastStand(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val memos = sector.beacons.map { BeaconMemo(it) }
        val modifier = sector.fleetAdvanceModifier
        val bosses = ArrayList(sector.bosses)
        state.eventCreator.usedNamesFixture = listOf()
        try {
            sector.bosses.clear()
            memos.forEach { it.restore() }
            sector.beacons.forEach { it.browserFleetThreatened = false }
            val open = sector.beacons.filter { !it.isBase && !it.isOvertaken }
            check(open.size > 3) { "Too few beacons for the captured last stand step" }
            val marked = open[1]
            marked.browserFleetThreatened = true
            val candidates = open.filter { it !== marked }
            state.eventManager.restoreEventState()
            state.eventCreator.refreshUsedNames()
            NativeRandom.seed(1628214628)
            repeat(1394 + 2) { NativeRandom.next31() }
            val (_, draws) = counted { BrowserLastStand.advance(state) }
            check(draws == 7) { "The last stand step made $draws draws, the original 7 (capture 6, mark 1)" }
            check(marked.isOvertaken && marked.createdEvent.shipSeed == 1900588100) {
                "The marked beacon's fleet event has ship seed ${marked.createdEvent.shipSeed}, the original 1900588100"
            }
            val next = sector.beacons.filter { it.browserFleetThreatened }
            check(next.size == 1 && next[0] === candidates[547579420 % candidates.size]) { "The new mark is not the draw's pick" }
        } finally {
            sector.bosses.addAll(bosses)
            memos.forEach { it.restore() }
            sector.fleetAdvanceModifier = modifier
            state.eventManager.restoreEventState()
        }
        return "last stand step draws (captured)"
    }

    // ---- StarMap 0x5f4ed0

    private fun lastStand(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val memos = sector.beacons.map { BeaconMemo(it) }
        val modifier = sector.fleetAdvanceModifier
        val bosses = ArrayList(sector.bosses)
        try {
            sector.bosses.clear()
            memos.forEach { it.restore() }
            sector.beacons.forEach { it.browserFleetThreatened = false }
            val candidates = sector.beacons.filter { !it.isBase && !it.isOvertaken }
            check(candidates.size > 1) { "Too few beacons for the last stand step" }
            NativeRandom.seed(21)
            val expected = NativeRandom.next31() % candidates.size
            NativeRandom.seed(21)
            val (_, draws) = counted { BrowserLastStand.advance(state) }
            // One selection step: one draw (the second needs a second flagship step).
            check(draws == 1) { "A last stand step made $draws draws" }
            val marked = sector.beacons.filter { it.browserFleetThreatened }
            check(marked.size == 1 && marked[0] === candidates[expected]) { "The marked beacon is not random32() % n" }
        } finally {
            sector.bosses.addAll(bosses)
            memos.forEach { it.restore() }
            sector.fleetAdvanceModifier = modifier
            state.eventManager.restoreEventState()
        }
        return "last stand step (one draw, mark)"
    }

    // ---- WorldManager +0x12a0

    private fun crewKillSeed(state: InGameState): String {
        val events = state.eventManager
        events.resetCrewKillSeed()
        NativeRandom.seed(5)
        val (first, firstDraws) = counted { events.crewKillSeed() }
        val (second, secondDraws) = counted { events.crewKillSeed() }
        check(first == second && firstDraws == 1 && secondDraws == 0) { "The crew kill seed is drawn once per event" }

        NativeRandom.seed(5)
        check(NativeRandom.next31() == first) { "The seed is a plain random32()" }

        // The save keeps it, and the next event starts without one.
        val root = Element("events")
        events.saveEventState(root)
        events.resetCrewKillSeed()
        events.loadEventState(root)
        NativeRandom.seed(6)
        check(events.crewKillSeed() == first) { "The crew kill seed is not kept by the save" }
        events.resetCrewKillSeed()
        NativeRandom.seed(5)
        check(events.crewKillSeed() == first) { "The seed is not drawn again after the reset" }
        events.resetCrewKillSeed()
        return "crew kill seed (lazy, shared, saved, reset)"
    }

    // ---- WorldManager 0x408260 -> 0x4aea60

    private fun boarders(state: InGameState): String {
        val factory = state.crewFactory
        for (race in listOf("random", "human")) {
            factory.setUsedNames(listOf<String>())
            NativeRandom.seed(1234)
            val one = factory.createBoarder(state, race)
            factory.setUsedNames(listOf<String>())
            NativeRandom.seed(1234)
            val two = factory.createBoarder(state, race)
            check(one.info.name == two.info.name && one.info.race === two.info.race) { "Boarder $race is not reproducible" }

            // The order: a name, the class, and for "random" a second name that is the one kept.
            factory.setUsedNames(listOf<String>())
            NativeRandom.seed(1234)
            factory.newName()
            val raceBlueprint = state.lootPool.getCrewOrRandom(NativeRandom, race)
            val expectedName = factory.newName().name
            check(raceBlueprint === two.info.race) { "Boarder $race class" }
            if (race == "random")
                check(two.info.name == expectedName) { "A random boarder keeps the second name" }
        }
        factory.setUsedNames(listOf<String>())
        return "boarders (name, class, second name)"
    }

    // ---- 0x5e2e50

    private fun textLists(state: InGameState): String {
        val tips = checkNotNull(state.eventManager.getTextList("TIPS_LIST_PC")) { "TIPS_LIST_PC" }
        check(tips.items.isNotEmpty() && tips.items.none { it is TextList }) { "A loaded text list is not flattened" }
        return "text lists (flattened)"
    }

    // ---- StarMap::NewGame (0x5f9e00)

    private fun startTip(state: InGameState): String {
        val events = state.eventManager
        state.eventCreator.usedNamesFixture = listOf()
        events.restoreEventState()
        val tipsList = checkNotNull(events.getTextList("TIPS_LIST_PC"))
        NativeRandom.seed(40)
        val expectedTip = tipsList.pick(NativeRandom, false)
        val event = checkNotNull(state.eventCreator.create(events["START_GAME"], 0))
        val base = checkNotNull(event.text).localised
        NativeRandom.seed(40)
        state.eventCreator.appendStartTip(event)
        val text = checkNotNull(event.text).localised
        check(text.startsWith("$base \n \n") && text.length > base.length + 4) { "START_GAME has no tip" }
        check(text == "$base \n \n" + (expectedTip as EventText).localised) { "START_GAME tip is not the plain pick" }
        events.restoreEventState()
        return "START_GAME tip"
    }

    // ---- StarMap::WaitAtBeacon (0x5f4510), captured from the original

    private fun wait(state: InGameState): String {
        val events = state.eventManager
        state.eventCreator.usedNamesFixture = listOf()
        events.restoreEventState()
        NativeRandom.seed(1956517240)
        val (event, draws) = counted { state.eventCreator.create(events["NO_FUEL"], 0) }
        check(event != null && draws == 42) { "The no-fuel wait made $draws draws, the original 42" }
        check(sig(event).contains("FUEL_FLEET_DELAY")) { "The no-fuel wait gave ${sig(event)}" }
        events.restoreEventState()
        return "no-fuel wait (42 draws)"
    }

    // ---- WorldManager::ClearLocation (0x614d00) -> ClearEvent (0x6119f0)

    /**
     * Leaving a beacon wipes its event's hazard (the original, captured: a beacon given an asteroid field reads 1
     * on arrival and 0 once the player has jumped on; events2-hz3 in the sessions folder). A storm stays a nebula,
     * a fleet beacon and the exit keep theirs, and a forced clear leaves the hazard that is running.
     */
    private fun departure(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val others = sector.beacons.filter { !it.isExit && !it.isBase && !it.isOvertaken && it !== state.currentBeacon }
        val a = others[0]
        val b = others[1]
        val c = others[2]
        val memos = listOf(a, b, c).map { BeaconMemo(it) }
        val events = state.eventManager
        try {
            a.event = events["AUTO_ASTEROID"].resolve()
            a.clearEnvironment()
            check(a.environmentType == Beacon.EnvironmentType.ASTEROID) { "Asteroid beacon is ${a.environmentType}" }
            a.clearEvent(false, true)
            check(a.environmentType == Beacon.EnvironmentType.NORMAL) { "Left asteroid beacon is ${a.environmentType}" }

            // A fleet beacon keeps its event unless forced.
            b.event = events["AUTO_ASTEROID"].resolve()
            b.clearEnvironment()
            b.hasFleetEvent = true
            b.clearEvent(false, true)
            check(b.environmentType == Beacon.EnvironmentType.ASTEROID) { "Fleet beacon lost its hazard" }
            b.clearEvent(true, false)
            check(b.environmentType == Beacon.EnvironmentType.NORMAL) { "Forced clear left ${b.environmentType}" }

            // A storm (a nebula) is left as a plain nebula.
            c.forcedNebula = true
            c.clearEnvironment()
            c.clearEvent(false, true)
            check(c.environmentType == Beacon.EnvironmentType.NEBULA) { "Left nebula beacon is ${c.environmentType}" }

            // Loading clears every visited beacon but the current one.
            a.eventCleared = false
            a.visited = true
            sector.clearVisitedEvents(state.currentBeacon)
            check(a.eventCleared) { "Load left a visited beacon's event" }
        } finally {
            memos.forEach { it.restore() }
            a.eventCleared = false; b.eventCleared = false; c.eventCleared = false
            a.visited = false
            events.restoreEventState()
        }
        return "departure clears the hazard"
    }

    // ---- Event status effects (WorldManager 0x613760, ShipSystem 0x63d1a0..0x63d3e0)

    private fun statuses(state: InGameState): String {
        val system = state.player.systems.first { it.energyLevels >= 2 }
        val level = system.energyLevels
        val damage = system.damagedEnergyLevels
        val limit = system.scriptedPowerLimit
        val loss = system.scriptedPowerLoss
        try {
            system.damagedEnergyLevels = 0
            system.scriptedPowerLimit = null
            system.scriptedPowerLoss = 0
            check(system.undamagedEnergy == level) { "An unaffected system lost power" }

            // The loss is its own field and comes off the level.
            system.scriptedPowerLoss = 1
            check(system.undamagedEnergy == level - 1) { "Loss 1 left ${system.undamagedEnergy} of $level" }

            // A limit and a loss stack: the lower result wins.
            system.scriptedPowerLimit = 1
            check(system.undamagedEnergy == minOf(1, level - 1)) { "Limit and loss did not take the lower" }
            system.scriptedPowerLimit = null

            // The loss adds to the damage rather than overlapping it.
            system.damagedEnergyLevels = 1
            check(system.undamagedEnergy == maxOf(level - 2, 0)) { "Damage and loss overlap" }
        } finally {
            system.damagedEnergyLevels = damage
            system.scriptedPowerLimit = limit
            system.scriptedPowerLoss = loss
        }
        return "status effects (limit, loss, stacking)"
    }

    // ---- The original's save: seeds, the wait seed and route flags

    private fun saves(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.currentBeacon.sector
        sector.waitSeed = 1956517240
        sector.dangerZoneCentre.x = 123
        sector.dangerZoneCentre.y = 45
        sector.fleetAdvanceModifier = -2
        val visited = state.gameMap.sectors.flatten().map { state.visitedSectors.contains(it) }

        val bytes = VanillaSaveWriter.writeToByteArray(state)
        val parsed = main.parseVanillaSaveForParity(bytes)
        check(parsed.sectorTreeSeed == state.gameMap.generationSeed) { "Tree seed" }
        check(parsed.sectorLayoutSeed == sector.layoutSeed) { "Layout seed" }
        check(parsed.fleetOffset == 123 && parsed.fleetFudge == 45 && parsed.pursuitMod == -2) { "Fleet offset, fudge and pursuit modifier" }
        check(parsed.waiting && parsed.waitSeed == 1956517240) { "The wait seed is not in the vanilla save" }
        check(parsed.routeFlags == visited) { "Route flags ${parsed.routeFlags} for $visited" }

        // Not waiting: -1 and no flag.
        sector.waitSeed = null
        val idle = main.parseVanillaSaveForParity(VanillaSaveWriter.writeToByteArray(state))
        check(!idle.waiting && idle.waitSeed == -1) { "An idle save has a wait seed" }

        // Importing the waiting save keeps the seeds and the wait seed.
        main.startVanillaSaveForParity(bytes)
        state = main.currentState as InGameState
        val loaded = state.currentBeacon.sector
        check(state.gameMap.generationSeed == parsed.sectorTreeSeed) { "Tree seed after import" }
        check(loaded.layoutSeed == parsed.sectorLayoutSeed) { "Layout seed after import" }
        check(loaded.waitSeed == 1956517240) { "Wait seed after import" }
        check(loaded.dangerZoneCentre.x == 123 && loaded.dangerZoneCentre.y == 45 && loaded.fleetAdvanceModifier == -2) {
            "Fleet state after import"
        }
        check(state.gameMap.sectors.flatten().map { state.visitedSectors.contains(it) } == visited) { "Route flags after import" }
        return "original's save (seeds, wait seed, fleet, route flags)"
    }

    // ---- Helpers

    /** Run [block] and count the draws it makes from the stream (seeding is not a draw). */
    private fun <T> counted(block: () -> T): Pair<T, Int> {
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        val result = try {
            block()
        } finally {
            NativeRandom.trace = null
        }
        var draws = 0
        var i = 0
        while (i < trace.size) {
            if (trace[i] == Int.MIN_VALUE) i += 2 else {
                draws++
                i++
            }
        }
        return result to draws
    }
}
