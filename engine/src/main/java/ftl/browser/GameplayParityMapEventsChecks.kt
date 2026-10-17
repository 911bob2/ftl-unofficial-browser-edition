package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.LootPool
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sector.CreatedCrew
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.EventCreator
import xyz.znix.xftl.sector.EventText
import xyz.znix.xftl.sector.GameMap
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sector.TextList
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Checks for the seeding, sector generation and eager event pass (see
 * docs/ORIGINAL_BEHAVIOR.md): the stream, the weighted item
 * picks, the crew factory, event creation in the original's draw order, the
 * sector tree and the beacon map for known seeds, and the saves.
 *
 * The known answers (at the bottom) come from the draw-exact model of the
 * original's generators, not from this engine: a failure means this engine's
 * draws differ from the original's for the same seed.
 */
internal object GameplayParityMapEventsChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val streamBefore = NativeRandom.snapshot()
        val parts = ArrayList<String>()
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val poolBefore = state.lootPool
        try {
            parts += stream()
            parts += rangeRolls()
            parts += pools(state)
            parts += names(state)
            parts += crewFactory(state)
            parts += textLists()
            parts += createdEvents(state)
            parts += uniques(state)
            parts += treeAndMap(state)
            parts += saves(main, container)
        } finally {
            NativeRandom.trace = null
            state.eventCreator.usedNamesFixture = null
            state.setLootPool(poolBefore)
            state.eventManager.resetEventState()
            NativeRandom.restore(streamBefore)
        }
        return "map/events: " + parts.joinToString(", ")
    }

    // ---- The stream

    /** `random32` (0x6569f0) and its seeder (0x656db0), and the `value % n` convention every use shares. */
    private fun stream(): String {
        for ((seed, expected) in Expected.STREAM) {
            NativeRandom.seed(seed)
            val got = List(4) { NativeRandom.next31() }
            check(got == expected) { "Seed $seed gave $got, expected $expected" }
        }

        val first = Expected.STREAM.getValue(1)
        NativeRandom.seed(1)
        check(NativeRandom.nextInt(1000) == first[0] % 1000) { "Bounded draw is not value % n" }
        check(NativeRandom.nextInt(5, 9) == first[1] % 4 + 5) { "Ranged draw is not value % (until - from) + from" }
        check(NativeRandom.nextInt() == first[2]) { "nextInt() is not the raw value" }

        // A saved stream continues where it stopped.
        NativeRandom.seed(1)
        NativeRandom.next31()
        val snapshot = NativeRandom.snapshot()
        val next = NativeRandom.next31()
        NativeRandom.next31()
        NativeRandom.restore(snapshot)
        check(NativeRandom.next31() == next && next == first[1]) { "Stream restore" }
        return "stream"
    }

    /** The range roll (0x5caf40): no draw for a zero maximum, a chance gate that yields zero. */
    private fun rangeRolls(): String {
        val first = Expected.STREAM.getValue(1)

        NativeRandom.seed(1)
        check(EventCreator.rangeRoll(NativeRandom, 3, 0, 0f) == 3) { "A zero maximum is the minimum" }
        check(NativeRandom.next31() == first[0]) { "A zero maximum must not draw" }

        NativeRandom.seed(1)
        check(EventCreator.rangeRoll(NativeRandom, 2, 5, 0f) == 2 + first[0] % 4) { "min + rand % (max + 1 - min)" }
        check(NativeRandom.next31() == first[1]) { "One draw per roll" }

        // 740882966 % 100 is 66: below 80, so the chance yields zero from that one draw.
        NativeRandom.seed(1)
        check(EventCreator.rangeRoll(NativeRandom, 2, 5, 0.8f) == 0) { "Chance gate" }
        check(NativeRandom.next31() == first[1]) { "The chance gate is one draw" }

        // ... and above 50 it falls through to the roll with a second draw.
        NativeRandom.seed(1)
        check(EventCreator.rangeRoll(NativeRandom, 2, 5, 0.5f) == 2 + first[1] % 4) { "Chance missed" }
        check(NativeRandom.next31() == first[2]) { "GameplayParityMapEventsChecks.kt:108" }
        return "range rolls"
    }

    // ---- Item pools

    /**
     * The random pick (BlueprintManager 0x764be0): the blueprints of one kind in
     * name order, weighing 6 minus their rarity, one draw walked down the
     * implicit tree of sums.
     */
    private fun pools(state: InGameState): String {
        val ae = state.content.enableAdvancedEdition
        val pool = LootPool(state.blueprintManager, null)
        val kinds: List<(Blueprint) -> Boolean> = listOf(
            { it is AbstractWeaponBlueprint }, { it is DroneBlueprint }, { it is AugmentBlueprint }, { it is CrewBlueprint }
        )

        val expected = if (ae) Expected.POOLS_AE else Expected.POOLS_VANILLA
        for ((i, kind) in kinds.withIndex()) {
            val items = pool.pool.filter(kind)
            val weight = items.sumOf { 6 - pool.rarityOf(it) }
            check(items.size == expected[i].first && weight == expected[i].second) {
                "Pool $i has ${items.size} items weighing $weight, expected ${expected[i]}"
            }
        }
        check(pool.pool.zipWithNext().all { (a, b) -> a.name < b.name }) { "Pool is not in name order" }

        for ((seed, names) in if (ae) Expected.PICKS_AE else Expected.PICKS_VANILLA) {
            NativeRandom.seed(seed)
            val got = listOf(
                pool.getWeapon(NativeRandom).name, pool.getDrone(NativeRandom).name, pool.getAugment(NativeRandom).name,
                (pool.getRandom(NativeRandom) { it is CrewBlueprint } ?: error("No crew")).name
            )
            check(got == names) { "Seed $seed picked $got, expected $names" }
        }

        // Several picks of one kind are distinct and start with the single pick.
        NativeRandom.seed(7)
        val many = pool.getManyRandom(AbstractWeaponBlueprint::class.java, NativeRandom, 4)
        val firstWeapon = (if (ae) Expected.PICKS_AE else Expected.PICKS_VANILLA).getValue(7)[0]
        check(many.size == 4 && many.toSet().size == 4 && many[0].name == firstWeapon) { "Distinct picks: ${many.map { it.name }}" }
        return "weighted item pools"
    }

    // ---- Crew names and the crew factory

    private fun names(state: InGameState): String {
        val factory = state.crewFactory
        for ((seed, expected) in Expected.NAMES) {
            NativeRandom.seed(seed)
            factory.setUsedNames(listOf<String>())
            val got = List(3) { factory.newName().name }
            check(got == expected) { "Seed $seed named $got, expected $expected" }
            check(factory.usedNames() == expected) { "Generated names are recorded as in use" }
        }

        // A name in use is drawn again.
        NativeRandom.seed(7)
        factory.setUsedNames(listOf("Lana"))
        val got = List(2) { factory.newName().name }
        check(got == Expected.NAMES_WITH_LANA_USED) { "Used name was kept: $got" }

        // At most 21 tries; the last name is taken and recorded either way.
        val all = state.nameManager.flatNames.map { it.first }
        check(all.zipWithNext().all { (a, b) -> a.encodeToByteArray().compareUnsigned(b.encodeToByteArray()) < 0 }) {
            "Name table is not sorted by bytes"
        }
        NativeRandom.seed(7)
        factory.setUsedNames(all)
        val (name, draws) = counted { factory.newName() }
        check(draws == 21) { "Name retries: $draws" }
        check(factory.usedNames().size == all.size + 1 && factory.usedNames().last() == name.name) { "GameplayParityMapEventsChecks.kt:180" }
        return "names (table order, retries)"
    }

    private fun ByteArray.compareUnsigned(other: ByteArray): Int {
        for (i in 0 until minOf(size, other.size)) {
            val d = (this[i].toInt() and 0xff) - (other[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return size - other.size
    }

    /** CrewMemberFactory: the colour of each layer, then the name, then the skills. */
    private fun crewFactory(state: InGameState): String {
        val factory = state.crewFactory
        val ae = state.content.enableAdvancedEdition
        for (known in if (ae) Expected.CREW_AE else Expected.CREW_VANILLA) {
            NativeRandom.seed(7)
            factory.setUsedNames(listOf<String>())
            val race = state.blueprintManager[known.race] as CrewBlueprint
            val (info, draws) = counted { factory.createRandom(state, race, 3) }
            check(info.name == known.name) { "${known.race} is named ${info.name}, expected ${known.name}" }
            check(draws == known.draws) { "${known.race} drew $draws times, expected ${known.draws}" }
            val skills = Skill.values().joinToString("") { (((info.skills[it] ?: 0f) * 2f).toInt()).toString() }
            check(skills == known.skills) { "${known.race} skills $skills, expected ${known.skills}" }
            val layers = known.colours.toIntArray()
            check(info.colour in setOf(factory.colourIndex(race, layers, true), factory.colourIndex(race, layers, false))) {
                "${known.race} colour ${info.colour} for layer draws ${known.colours}"
            }
        }
        return "crew factory (colours, name, skills)"
    }

    // ---- Text lists

    /**
     * A text list gives each entry once; the next map generation puts the used ones back at the front, in the order
     * they were used (EventGenerator 0x5db020, range insert at begin()).
     */
    private fun textLists(): String {
        val list = TextList("parity", listOf(EventText("a"), EventText("b"), EventText("c")))
        fun text(remove: Boolean) = (list.pick(NativeRandom, remove) as EventText).localised

        NativeRandom.seed(5)
        val v1 = NativeRandom.next31()
        val v2 = NativeRandom.next31()
        val remaining = arrayListOf("a", "b", "c")
        val first = remaining.removeAt(v1 % 3)
        val second = remaining.removeAt(v2 % 2)
        val last = remaining.single()

        NativeRandom.seed(5)
        check(text(true) == first && text(true) == second) { "Text list picks (rand % count, then removed)" }

        // The last entry is never removed, and a pick of it still draws.
        val (again, draws) = counted { text(true) }
        check(again == last && draws == 1 && text(true) == last) { "GameplayParityMapEventsChecks.kt:233" }

        // The next map generation inserts the used ones before the rest, in the order they were used.
        list.restoreUsed()
        NativeRandom.seed(5)
        check(text(false) == listOf(first, second, last)[v1 % 3]) { "Restored text list order" }
        return "text lists"
    }

    // ---- Eager events

    /**
     * CreateEvent (0x5df690) / GetEvent (0x5dce90): the whole tree for a seed,
     * in signature form (see [sig]) and the number of draws it took.
     */
    private fun createdEvents(state: InGameState): String {
        val ae = state.content.enableAdvancedEdition
        val events = state.eventManager
        val factory = state.crewFactory
        state.setLootPool(LootPool(state.blueprintManager, null))
        state.setDifficulty(Difficulty.NORMAL)

        val cases = if (ae) Expected.EVENTS_AE else Expected.EVENTS_VANILLA
        for (known in cases) {
            events.resetEventState()
            factory.setUsedNames(listOf<String>())
            NativeRandom.seed(known.seed)
            val (created, draws) = counted { state.eventCreator.create(events[known.name], known.level) }
            checkNotNull(created) { "${known.name} was not created" }

            val crew = ArrayList<CreatedCrew>()
            val got = sig(created, true, crew)
            check(got == known.sig) { "${known.name} (seed ${known.seed}) created\n  $got\nbut the original creates\n  ${known.sig}" }
            check(draws == known.draws) { "${known.name} drew $draws times, expected ${known.draws}" }

            check(crew.size == known.colours.size) { "GameplayParityMapEventsChecks.kt:268" }
            for ((member, drawn) in crew.zip(known.colours)) {
                val layers = drawn.toIntArray()
                check(member.colour in setOf(factory.colourIndex(member.race, layers, true), factory.colourIndex(member.race, layers, false))) {
                    "${known.name}: ${member.name} has colour ${member.colour} for layer draws $drawn"
                }
            }

            // The created tree survives a save.
            val elem = Element("created")
            created.saveToXML(elem)
            val loaded = CreatedEvent.loadFromXML(elem, state)
            check(fullSig(loaded) == fullSig(created)) { "${known.name} changed in the save" }
        }

        // Only a unique event and a failed pick can make a list roll again; both end in the same tree.
        return "${cases.size} created events (draw order, crew, items, rewards, ship seeds)"
    }

    private fun uniques(state: InGameState): String {
        val events = state.eventManager
        val creator = state.eventCreator
        events.resetEventState()
        val warning = events["NEBULA_AUTO_WARNING"] as Event
        check(warning.unique && !warning.isConsumedUnique) { "GameplayParityMapEventsChecks.kt:292" }

        NativeRandom.seed(11)
        state.crewFactory.setUsedNames(listOf<String>())
        check(creator.create(warning, 2) != null && warning.isConsumedUnique) { "Creation consumes a unique event" }
        val (second, draws) = counted { creator.create(warning, 2) }
        check(second == null && draws == 0) { "A consumed unique event creates nothing and draws nothing" }

        // The grid generation returns it (EventGenerator 0x5db020).
        creator.clearEvents()
        check(!warning.isConsumedUnique && creator.create(warning, 2) != null) { "GameplayParityMapEventsChecks.kt:302" }
        events.resetEventState()
        return "unique events"
    }

    /**
     * One line for a created event, comparable with the model's: root name,
     * text id, fuel/drones/missiles/scrap, crew, weapon/drone/augment, ship
     * seed, boarders and the choices (text => event).
     */
    private fun sig(e: CreatedEvent, root: Boolean, crew: MutableList<CreatedCrew>): String {
        val sb = StringBuilder("{")
        sb.append(if (root) e.template.debugId else "-").append('|')
        sb.append(e.text?.let { it.id ?: it.localised } ?: "").append('|')
        sb.append(e.fuel).append(',').append(e.droneParts).append(',').append(e.missiles).append(',').append(e.scrap).append('|')
        e.crew?.let { member ->
            crew += member
            sb.append(member.race.name).append('/').append(member.name).append('/')
            for (skill in Skill.values())
                sb.append(((member.skills[skill] ?: 0f) * 2f).toInt())
        }
        sb.append('|').append(e.weapon?.name ?: "").append(',').append(e.drone?.name ?: "").append(',').append(e.augment?.name ?: "")
        sb.append('|').append(e.shipSeed).append('|').append(e.boarders).append('|')
        sb.append(e.choices.joinToString(";") { choice ->
            (choice.text.id ?: choice.text.localised) + "=>" + (choice.event?.let { sig(it, false, crew) } ?: "null")
        })
        return sb.append('}').toString()
    }

    /** Everything a created event holds, for comparing a save with the original. */
    private fun fullSig(e: CreatedEvent?): String {
        if (e == null) return "null"
        val sb = StringBuilder("{")
        sb.append(e.template.deserialisationId).append('@').append(e.level).append('|')
        sb.append(e.text?.let { it.id ?: it.localised }).append('|')
        sb.append(e.fuel).append(',').append(e.droneParts).append(',').append(e.missiles).append(',').append(e.scrap).append('|')
        sb.append(e.weapon?.name).append(',').append(e.drone?.name).append(',').append(e.augment?.name).append('|')
        e.crew?.let { member ->
            sb.append(member.race.name).append('/').append(member.name).append('/').append(member.colour).append('/')
            for (skill in Skill.values())
                sb.append(member.skills[skill] ?: 0f).append(',')
        }
        sb.append('|').append(e.shipSeed).append('|').append(e.boarders).append('|')
        sb.append(e.choices.joinToString(";") { choice ->
            choice.template.deserialisationId + ":" + (choice.text.id ?: choice.text.localised) + "=>" + fullSig(choice.event)
        })
        return sb.append('}').toString()
    }

    // ---- Sector tree and beacon map

    private class Generated(val map: GameMap, val flat: List<GameMap.SectorInfo>, val sector: Sector)

    /** NewGame (0x5f9e00) then GenerateMap (0x5f8ca0) for a tree seed; a null layout seed is drawn from the stream. */
    private fun generate(state: InGameState, treeSeed: Int, sectorIndex: Int, layoutSeed: Int?): Generated {
        state.eventManager.resetEventState()
        val map = GameMap(state.content.datafile, state.eventManager, state.content.enableAdvancedEdition, treeSeed)
        val flat = map.sectors.flatten()
        return Generated(map, flat, map.generateSector(flat[sectorIndex], state, layoutSeed))
    }

    private fun describeTree(flat: List<GameMap.SectorInfo>): Triple<String, String, String> {
        // The start sector draws from STANDARD_SPACE but is named as the civilian sector.
        val types = flat.joinToString(",") { if (it.type.name == "STANDARD_SPACE") "CIVILIAN_SECTOR" else it.type.name }
        val classes = flat.joinToString("") { it.sectorClass.ordinal.toString() }
        val kids = flat.joinToString("|") { node -> node.nextSectors.joinToString(".") { flat.indexOf(it).toString() } }
        return Triple(types, classes, kids)
    }

    /** `x,y,exit,nebula:neighbours` per beacon. */
    private fun describeLocations(sector: Sector): String = sector.beacons.joinToString(" ") { b ->
        "${b.pos.x.toInt()},${b.pos.y.toInt()},${if (b.isExit || b.isBase) 1 else 0},${if (b.inNebula) 1 else 0}:" +
            b.neighbours.joinToString(".") { sector.beacons.indexOf(it).toString() }
    }

    /** `event|text|ship seed` per beacon; the start beacon's text is replaced by the new game's. */
    private fun describeEvents(sector: Sector): String {
        val start = sector.beacons.indexOf(sector.startBeacon)
        return sector.beacons.withIndex().joinToString(" ") { (i, b) ->
            val e = b.createdEvent
            "${e.template.debugId}|${if (i == start) "*" else e.text?.let { it.id ?: it.localised } ?: ""}|${e.shipSeed}"
        }
    }

    private fun same(label: String, got: String, expected: String) {
        check(got == expected) { "$label differs from the original's\n  got      $got\n  expected $expected" }
    }

    private fun treeAndMap(state: InGameState): String {
        val ae = state.content.enableAdvancedEdition
        val known = if (ae) Expected.MAP_AE else Expected.MAP_VANILLA
        state.setDifficulty(Difficulty.NORMAL)
        state.eventCreator.usedNamesFixture = listOf<String>()

        // The tree is built from the tree seed alone and the layout seed is the next draw.
        val run = generate(state, known.treeSeed, 0, null)
        check(run.map.generationSeed == known.treeSeed) { "GameplayParityMapEventsChecks.kt:398" }
        val (types, classes, kids) = describeTree(run.flat)
        same("Sector types", types, known.types)
        same("Sector classes", classes, known.classes)
        same("Sector links", kids, known.kids)
        check(run.sector.layoutSeed == known.layoutSeed) { "Layout seed ${run.sector.layoutSeed}, expected ${known.layoutSeed}" }
        same("Beacon positions and links", describeLocations(run.sector), known.locations)
        same("Beacon events", describeEvents(run.sector), known.events)
        check(run.sector.beacons.indexOf(run.sector.startBeacon) == known.start) { "GameplayParityMapEventsChecks.kt:406" }

        // Regenerating with the stored layout seed gives the same sector.
        val again = generate(state, known.treeSeed, 0, known.layoutSeed)
        same("Regenerated beacon positions", describeLocations(again.sector), known.locations)
        same("Regenerated beacon events", describeEvents(again.sector), known.events)

        // Other sectors of the tree are reproducible from their layout seed.
        var sectors = 1
        for (index in 1 until run.flat.size step 3) {
            val one = generate(state, known.treeSeed, index, 1000 + index)
            val two = generate(state, known.treeSeed, index, 1000 + index)
            same("Sector $index positions", describeLocations(two.sector), describeLocations(one.sector))
            same("Sector $index events", describeEvents(two.sector), describeEvents(one.sector))
            check(one.sector.layoutSeed == 1000 + index && one.sector.beacons.size in 19..24) { "Sector $index: ${one.sector.beacons.size} beacons" }
            check(one.sector.beacons.all { it.createdEvent.eager }) { "Sector $index has a beacon without a created event" }
            sectors++
        }
        return "${if (ae) "AE" else "vanilla"} tree and map for known seeds ($sectors sectors reproducible)"
    }

    // ---- Saves

    private fun saves(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val events = state.eventManager
        val beacons = state.currentBeacon.sector.beacons

        // A beacon with a created event that has crew and choices, a consumed unique event, a wait seed.
        events.restoreEventState()
        state.crewFactory.setUsedNames(listOf<String>())
        NativeRandom.seed(31)
        val battlefield = checkNotNull(state.eventCreator.create(events["NEBULA_BATTLEFIELD"], 6))
        val target = beacons.first { it !== state.currentBeacon }
        target.createdEvent = battlefield
        // A beacon that only names its template (older saves) stays that way.
        val legacy = beacons.first { it !== state.currentBeacon && it !== target }
        legacy.event = events["AUTO_ASTEROID"].resolve()
        check(!legacy.createdEvent.eager) { "GameplayParityMapEventsChecks.kt:444" }
        val warning = events["NEBULA_AUTO_WARNING"] as Event
        check(state.eventCreator.create(warning, 3) != null && warning.isConsumedUnique) { "GameplayParityMapEventsChecks.kt:446" }
        state.currentBeacon.sector.waitSeed = 4242

        val layoutSeed = state.currentBeacon.sector.layoutSeed
        val treeSeed = state.gameMap.generationSeed
        val tree = describeTree(state.gameMap.sectors.flatten())
        val index = beacons.indexOf(target)
        val legacyIndex = beacons.indexOf(legacy)
        val before = beacons.map { fullSig(it.createdEvent) }
        val streamBefore = NativeRandom.snapshot()

        // What the save holds must come back even when the engine forgot it meanwhile.
        val saved = BrowserXml.parse(BrowserXml.write(state.saveGameState()))
        events.resetEventState()
        NativeRandom.seed(999)
        check(!warning.isConsumedUnique) { "GameplayParityMapEventsChecks.kt:461" }
        main.loadSavedGame(saved)
        state = main.currentState as InGameState
        val after = state.currentBeacon.sector

        check(NativeRandom.snapshot() == streamBefore) { "The generation stream is not where the save left it" }
        check(after.layoutSeed == layoutSeed && after.waitSeed == 4242) { "Seeds in the save" }
        check(state.gameMap.generationSeed == treeSeed && describeTree(state.gameMap.sectors.flatten()) == tree) { "Sector tree after load" }
        check(after.beacons.size == beacons.size) { "GameplayParityMapEventsChecks.kt:469" }
        for ((i, beacon) in after.beacons.withIndex()) {
            if (i == legacyIndex) {
                check(!beacon.createdEvent.eager && beacon.event.deserialisationId == before[i].substringAfter('{').substringBefore('@')) { "Template-only beacon" }
            } else {
                check(fullSig(beacon.createdEvent) == before[i]) { "Beacon $i created event changed in the save" }
            }
        }
        check(fullSig(after.beacons[index].createdEvent).contains("NEBULA_BATTLEFIELD")) { "Created event moved" }
        check((state.eventManager["NEBULA_AUTO_WARNING"] as Event).isConsumedUnique) { "Consumed unique event came back with the save" }
        state.eventManager.restoreEventState()
        check(!(state.eventManager["NEBULA_AUTO_WARNING"] as Event).isConsumedUnique) { "GameplayParityMapEventsChecks.kt:480" }
        return "saves (created events, seeds, stream, unique events)"
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

    private class RandomCrew(val race: String, val name: String, val colours: List<Int>, val skills: String, val draws: Int)

    private class KnownEvent(val name: String, val seed: Int, val level: Int, val draws: Int, val sig: String, val colours: List<List<Int>>)

    private class KnownMap(
        val treeSeed: Int, val layoutSeed: Int, val start: Int,
        val types: String, val classes: String, val kids: String, val locations: String, val events: String
    )

    private object Expected {
    // ---- Known answers. Everything below was computed with the draw-exact Python model of the original's
    // generators (.build/gameplay-1to1/model), which reproduces the original's own output for the same seeds.

    /** The first draws of the stream after seeding. */
    val STREAM: Map<Int, List<Int>> = mapOf(
        1 to listOf(740882966, 1616430695, 1708849955, 1669437588),
        123456 to listOf(823995074, 919376335, 1747906362, 66229363),
        -1 to listOf(2049033599, 2025915578, 1407788582, 829275321),
    )

    // AE
    /** (count, total weight) of the weapon, drone, augment and crew pools at their own rarities. */
    val POOLS_AE = listOf(37 to 109, 14 to 41, 23 to 79, 5 to 17)
    /** Weapon, drone, augment, crew picked in a row after seeding with 7 and with 123456. */
    val PICKS_AE = mapOf(7 to listOf("ION_STUN", "ANTI_DRONE", "ADV_SCANNERS", "engi"), 123456 to listOf("ION_1", "ANTI_DRONE", "ION_ARMOR", "human"))
    /** createRandom at world level 3 after seeding with 7: race, name, colour layer draws, skill steps, draws. */
    val CREW_AE = listOf(
        RandomCrew("human", "Layla", listOf(0, 1), "001100", 8),
        RandomCrew("engi", "Lana", listOf(), "000002", 7),
        RandomCrew("mantis", "Valeriya", listOf(0), "000002", 7),
        RandomCrew("rock", "Valeriya", listOf(2), "000002", 7),
        RandomCrew("energy", "Valeriya", listOf(0), "000002", 7),
    )
    val EVENTS_AE = listOf(
        KnownEvent("NEBULA_LOST_SHIP", 2024, 4, 43,
            "{NEBULA_LOST_SHIP|event_NEBULA_LOST_SHIP_text|0,0,0,0||,,|0|0|event_NEBULA_LOST_SHIP_c1_choice=>{-|event_NEBULA_LOST_SHIP_LIST_3_text|0,0,0,0|mantis/Gumpo/000002|,,|0|0|};event_NEBULA_LOST_SHIP_c2_choice=>{-||0,0,0,0||,,|0|0|};event_NEBULA_LOST_SHIP_c3_choice=>{-|event_NEBULA_LOST_SHIP_c3_text|0,0,0,33|engi/Magmaros/010000|,,|0|0|};event_NEBULA_LOST_SHIP_c4_choice=>{-|event_NEBULA_LOST_SHIP_LIST2_1_text|0,0,0,34||,,|0|0|}}",
            listOf(listOf(2), listOf())),
        KnownEvent("FRIENDLY_SLAVER", 555, 2, 43,
            "{FRIENDLY_SLAVER|event_FRIENDLY_SLAVER_text|0,0,0,0||,,|727086785|0|event_FRIENDLY_SLAVER_c1_choice=>{-||0,0,0,-34|engi/Nikola/000010|,,|0|0|};event_FRIENDLY_SLAVER_c2_choice=>{-||0,0,0,0||,,|763331655|0|};event_FRIENDLY_SLAVER_c3_choice=>{-||0,0,0,0||,,|0|0|};event_FRIENDLY_SLAVER_c4_choice=>{-|event_FRIENDLY_SLAVER_TELEPORTER_2_text|0,0,0,0|mantis/Tach/000001|,,|1915588250|0|}}",
            listOf(listOf(), listOf(2))),
        KnownEvent("NEBULA_BATTLEFIELD", 31, 6, 63,
            "{NEBULA_BATTLEFIELD|event_NEBULA_BATTLEFIELD_text|0,0,0,0||,,|0|0|event_NEBULA_BATTLEFIELD_c1_choice=>{-|event_NEBULA_BATTLEFIELD_c1_text|0,0,0,0||,,|0|0|event_NEBULA_BATTLEFIELD_c1_c1_choice=>{-|event_BATTLEFIELD_SURVIVOR_text|0,0,0,0||,,|0|0|event_BATTLEFIELD_SURVIVOR_c1_choice=>{-|event_BATTLEFIELD_SURVIVOR_c1_text|0,0,0,0||,,|0|0|};event_BATTLEFIELD_SURVIVOR_c2_choice=>{-|event_BATTLEFIELD_SURVIVOR_c2_text|0,0,0,0|energy/Nikola/100200|,,|0|0|};event_BATTLEFIELD_SURVIVOR_c3_choice=>{-|event_BATTLEFIELD_SURVIVOR_c3_text|0,0,0,0|human/Sophia/000010|,,|0|0|}};event_NEBULA_BATTLEFIELD_c1_c2_choice=>{-||0,0,0,0||,,|0|0|}};event_NEBULA_BATTLEFIELD_c2_choice=>{-|event_BATTLEFIELD_INVESTIGAGE_2_text|0,0,0,0||,,|0|0|};event_NEBULA_BATTLEFIELD_c1_c2_choice=>{-||0,0,0,0||,,|0|0|}}",
            listOf(listOf(0), listOf(0, 0))),
        KnownEvent("DISTRESS_STATION_DISEASE", 808, 3, 71,
            "{DISTRESS_STATION_DISEASE|event_DISTRESS_STATION_DISEASE_text|0,0,0,0||,,|0|0|event_DISTRESS_STATION_DISEASE_c1_choice=>{-|event_DISTRESS_STATION_DISEASE_LIST_2_text|0,1,2,19||,,|0|0|};event_DISTRESS_STATION_DISEASE_c2_choice=>{-|event_DISTRESS_STATION_DISEASE_c2_text|0,0,0,0||,,|0|0|};event_DISTRESS_STATION_DISEASE_c3_choice=>{-|event_DISTRESS_STATION_DISEASE_c3_text|3,1,0,20||,,|0|0|};event_DISTRESS_STATION_DISEASE_c4_choice=>{-|event_DISTRESS_STATION_DISEASE_c4_text|3,1,0,32||,,DRONE_RECOVERY|0|0|};event_DISTRESS_STATION_DISEASE_c5_choice=>{-|event_DISTRESS_STATION_DISEASE_c5_text|0,0,0,0||,,|0|0|continue=>{-|event_DISTRESS_STATION_DISEASE_c5_c1_text|2,0,4,21||,,|0|0|};event_DISTRESS_STATION_DISEASE_c5_c2_choice=>{-|event_DISTRESS_STATION_DISEASE_c5_c2_text|0,0,0,48||BOMB_FIRE,,|0|0|}}}",
            listOf()),
        KnownEvent("DISTRESS_SATELLITE_DEFENSE", 12, 5, 80,
            "{DISTRESS_SATELLITE_DEFENSE|event_DISTRESS_SATELLITE_DEFENSE_text|0,0,0,0||,,|0|0|event_DISTRESS_SATELLITE_DEFENSE_c1_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_text|0,0,0,0||,,|0|0|event_DISTRESS_SATELLITE_DEFENSE_c1_c1_choice=>{-|event_SATELLITE_DEFENSE_BORING_2_text|0,0,0,0||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c2_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c2_text|0,1,1,66||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c3_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c3_text|1,1,0,28||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c4_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c4_text|0,1,2,51||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c5_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c5_text|2,1,0,67||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c6_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c6_text|3,1,0,68||,,|0|0|}};event_DISTRESS_SATELLITE_DEFENSE_c2_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c2_text|0,0,0,0||,,|0|0|}}",
            listOf()),
        KnownEvent("PIRATE", 77, 1, 7,
            "{PIRATE|text_PIRATE_5|0,0,0,0||,,|1164776700|0|}",
            listOf()),
        KnownEvent("STORE", 99, 0, 6,
            "{STORE|text_STORE_TEXT_2|0,0,0,0||,,|0|0|}",
            listOf()),
        KnownEvent("START_GAME", 5, 0, 5,
            "{START_GAME|event_START_GAME_text|0,0,0,0||,,|0|0|}",
            listOf()),
        // An explicit-class crew member draws GetCrewBlueprint's throwaway name first (0x60e020, finding G4):
        // this entry follows that native rule, checked on native first sectors (GameplayParityGen3Checks).
        KnownEvent("DISTRESS_ENGI_REACTOR", 4444, 2, 64,
            "{DISTRESS_ENGI_REACTOR|event_DISTRESS_ENGI_REACTOR_text|0,0,0,0||,,|0|0|event_DISTRESS_ENGI_REACTOR_c1_choice=>{-|event_DISTRESS_ENGI_REACTOR_LIST1_1_text|0,0,0,0||,,|0|0|};event_DISTRESS_ENGI_REACTOR_c2_choice=>{-|event_DISTRESS_ENGI_REACTOR_c2_text|0,0,0,0||,,|0|0|};event_DISTRESS_ENGI_REACTOR_c3_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_text|0,0,0,0||,,|0|0|event_DISTRESS_ENGI_REACTOR_SCAN_c1_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c1_text|0,0,0,14|engi/Maxwell/010000|,,|0|0|};event_DISTRESS_ENGI_REACTOR_SCAN_c2_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c2_text|0,0,0,14||,BOARDER,|0|0|}};event_DISTRESS_ENGI_REACTOR_c4_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_text|0,0,0,0||,,|0|0|event_DISTRESS_ENGI_REACTOR_SCAN_c1_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c1_text|0,0,0,16|engi/Cremity/000000|,,|0|0|};event_DISTRESS_ENGI_REACTOR_SCAN_c2_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c2_text|0,0,0,18||,COMBAT_BEAM,|0|0|}}}",
            listOf(listOf(), listOf())),
        KnownEvent("NEBULA_PIRATE", 3141, 2, 7,
            "{NEBULA_EMPTY|text_NEBULA_EMPTY_LIST_3|0,0,0,0||,,|0|0|}",
            listOf()),
        KnownEvent("BOARDERS_PIRATE", 2718, 3, 6,
            "{BOARDERS_SUN|event_BOARDERS_SUN_text|0,0,0,0||,,|0|2|}",
            listOf()),
    )

    // VANILLA
    /** (count, total weight) of the weapon, drone, augment and crew pools at their own rarities. */
    val POOLS_VANILLA = listOf(25 to 76, 9 to 29, 12 to 44, 5 to 17)
    /** Weapon, drone, augment, crew picked in a row after seeding with 7 and with 123456. */
    val PICKS_VANILLA = mapOf(7 to listOf("BEAM_2", "COMBAT_1", "SHIELD_RECHARGE", "engi"), 123456 to listOf("LASER_HULL_2", "REPAIR", "SCRAP_COLLECTOR", "human"))
    /** createRandom at world level 3 after seeding with 7: race, name, colour layer draws, skill steps, draws. */
    val CREW_VANILLA = listOf(
        RandomCrew("human", "Layla", listOf(0, 1), "001100", 8),
        RandomCrew("engi", "Lana", listOf(), "000002", 7),
        RandomCrew("mantis", "Valeriya", listOf(0), "000002", 7),
        RandomCrew("rock", "Valeriya", listOf(2), "000002", 7),
        RandomCrew("energy", "Valeriya", listOf(0), "000002", 7),
    )
    val EVENTS_VANILLA = listOf(
        KnownEvent("NEBULA_LOST_SHIP", 2024, 4, 43,
            "{NEBULA_LOST_SHIP|event_NEBULA_LOST_SHIP_text|0,0,0,0||,,|0|0|event_NEBULA_LOST_SHIP_c1_choice=>{-|event_NEBULA_LOST_SHIP_LIST_3_text|0,0,0,0|mantis/Gumpo/000002|,,|0|0|};event_NEBULA_LOST_SHIP_c2_choice=>{-||0,0,0,0||,,|0|0|};event_NEBULA_LOST_SHIP_c3_choice=>{-|event_NEBULA_LOST_SHIP_c3_text|0,0,0,33|engi/Magmaros/010000|,,|0|0|};event_NEBULA_LOST_SHIP_c4_choice=>{-|event_NEBULA_LOST_SHIP_LIST2_1_text|0,0,0,34||,,|0|0|}}",
            listOf(listOf(2), listOf())),
        KnownEvent("FRIENDLY_SLAVER", 555, 2, 43,
            "{FRIENDLY_SLAVER|event_FRIENDLY_SLAVER_text|0,0,0,0||,,|727086785|0|event_FRIENDLY_SLAVER_c1_choice=>{-||0,0,0,-34|engi/Nikola/000010|,,|0|0|};event_FRIENDLY_SLAVER_c2_choice=>{-||0,0,0,0||,,|763331655|0|};event_FRIENDLY_SLAVER_c3_choice=>{-||0,0,0,0||,,|0|0|};event_FRIENDLY_SLAVER_c4_choice=>{-|event_FRIENDLY_SLAVER_TELEPORTER_2_text|0,0,0,0|mantis/Tach/000001|,,|1915588250|0|}}",
            listOf(listOf(), listOf(2))),
        KnownEvent("NEBULA_BATTLEFIELD", 31, 6, 63,
            "{NEBULA_BATTLEFIELD|event_NEBULA_BATTLEFIELD_text|0,0,0,0||,,|0|0|event_NEBULA_BATTLEFIELD_c1_choice=>{-|event_NEBULA_BATTLEFIELD_c1_text|0,0,0,0||,,|0|0|event_NEBULA_BATTLEFIELD_c1_c1_choice=>{-|event_BATTLEFIELD_SURVIVOR_text|0,0,0,0||,,|0|0|event_BATTLEFIELD_SURVIVOR_c1_choice=>{-|event_BATTLEFIELD_SURVIVOR_c1_text|0,0,0,0||,,|0|0|};event_BATTLEFIELD_SURVIVOR_c2_choice=>{-|event_BATTLEFIELD_SURVIVOR_c2_text|0,0,0,0|energy/Nikola/100200|,,|0|0|};event_BATTLEFIELD_SURVIVOR_c3_choice=>{-|event_BATTLEFIELD_SURVIVOR_c3_text|0,0,0,0|human/Sophia/000010|,,|0|0|}};event_NEBULA_BATTLEFIELD_c1_c2_choice=>{-||0,0,0,0||,,|0|0|}};event_NEBULA_BATTLEFIELD_c2_choice=>{-|event_BATTLEFIELD_INVESTIGAGE_2_text|0,0,0,0||,,|0|0|};event_NEBULA_BATTLEFIELD_c1_c2_choice=>{-||0,0,0,0||,,|0|0|}}",
            listOf(listOf(0), listOf(0, 0))),
        KnownEvent("DISTRESS_STATION_DISEASE", 808, 3, 71,
            "{DISTRESS_STATION_DISEASE|event_DISTRESS_STATION_DISEASE_text|0,0,0,0||,,|0|0|event_DISTRESS_STATION_DISEASE_c1_choice=>{-|event_DISTRESS_STATION_DISEASE_LIST_2_text|0,1,2,19||,,|0|0|};event_DISTRESS_STATION_DISEASE_c2_choice=>{-|event_DISTRESS_STATION_DISEASE_c2_text|0,0,0,0||,,|0|0|};event_DISTRESS_STATION_DISEASE_c3_choice=>{-|event_DISTRESS_STATION_DISEASE_c3_text|3,1,0,20||,,|0|0|};event_DISTRESS_STATION_DISEASE_c4_choice=>{-|event_DISTRESS_STATION_DISEASE_c4_text|3,1,0,32||,,WEAPON_PREIGNITE|0|0|};event_DISTRESS_STATION_DISEASE_c5_choice=>{-|event_DISTRESS_STATION_DISEASE_c5_text|0,0,0,0||,,|0|0|continue=>{-|event_DISTRESS_STATION_DISEASE_c5_c1_text|2,0,4,21||,,|0|0|};event_DISTRESS_STATION_DISEASE_c5_c2_choice=>{-|event_DISTRESS_STATION_DISEASE_c5_c2_text|0,0,0,48||ION_1,,|0|0|}}}",
            listOf()),
        KnownEvent("DISTRESS_SATELLITE_DEFENSE", 12, 5, 80,
            "{DISTRESS_SATELLITE_DEFENSE|event_DISTRESS_SATELLITE_DEFENSE_text|0,0,0,0||,,|0|0|event_DISTRESS_SATELLITE_DEFENSE_c1_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_text|0,0,0,0||,,|0|0|event_DISTRESS_SATELLITE_DEFENSE_c1_c1_choice=>{-|event_SATELLITE_DEFENSE_BORING_2_text|0,0,0,0||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c2_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c2_text|0,1,1,66||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c3_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c3_text|1,1,0,28||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c4_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c4_text|0,1,2,51||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c5_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c5_text|2,1,0,67||,,|0|0|};event_DISTRESS_SATELLITE_DEFENSE_c1_c6_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c1_c6_text|3,1,0,68||,,|0|0|}};event_DISTRESS_SATELLITE_DEFENSE_c2_choice=>{-|event_DISTRESS_SATELLITE_DEFENSE_c2_text|0,0,0,0||,,|0|0|}}",
            listOf()),
        KnownEvent("PIRATE", 77, 1, 7,
            "{PIRATE|text_PIRATE_5|0,0,0,0||,,|1164776700|0|}",
            listOf()),
        KnownEvent("STORE", 99, 0, 6,
            "{STORE|text_STORE_TEXT_2|0,0,0,0||,,|0|0|}",
            listOf()),
        KnownEvent("START_GAME", 5, 0, 5,
            "{START_GAME|event_START_GAME_text|0,0,0,0||,,|0|0|}",
            listOf()),
        // An explicit-class crew member draws GetCrewBlueprint's throwaway name first (0x60e020, finding G4):
        // this entry follows that native rule, checked on native first sectors (GameplayParityGen3Checks).
        KnownEvent("DISTRESS_ENGI_REACTOR", 4444, 2, 64,
            "{DISTRESS_ENGI_REACTOR|event_DISTRESS_ENGI_REACTOR_text|0,0,0,0||,,|0|0|event_DISTRESS_ENGI_REACTOR_c1_choice=>{-|event_DISTRESS_ENGI_REACTOR_LIST1_1_text|0,0,0,0||,,|0|0|};event_DISTRESS_ENGI_REACTOR_c2_choice=>{-|event_DISTRESS_ENGI_REACTOR_c2_text|0,0,0,0||,,|0|0|};event_DISTRESS_ENGI_REACTOR_c3_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_text|0,0,0,0||,,|0|0|event_DISTRESS_ENGI_REACTOR_SCAN_c1_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c1_text|0,0,0,14|engi/Maxwell/010000|,,|0|0|};event_DISTRESS_ENGI_REACTOR_SCAN_c2_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c2_text|0,0,0,14||,BATTLE,|0|0|}};event_DISTRESS_ENGI_REACTOR_c4_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_text|0,0,0,0||,,|0|0|event_DISTRESS_ENGI_REACTOR_SCAN_c1_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c1_text|0,0,0,16|engi/Cremity/000000|,,|0|0|};event_DISTRESS_ENGI_REACTOR_SCAN_c2_choice=>{-|event_DISTRESS_ENGI_REACTOR_SCAN_c2_text|0,0,0,18||,COMBAT_BEAM,|0|0|}}}",
            listOf(listOf(), listOf())),
        KnownEvent("NEBULA_PIRATE", 3141, 2, 7,
            "{NEBULA_EMPTY|text_NEBULA_EMPTY_LIST_3|0,0,0,0||,,|0|0|}",
            listOf()),
        KnownEvent("BOARDERS_PIRATE", 2718, 3, 6,
            "{BOARDERS_SUN|event_BOARDERS_SUN_text|0,0,0,0||,,|0|2|}",
            listOf()),
    )

    val NAMES = mapOf(7 to listOf("Lana", "Valeriya", "Layla"), 123456 to listOf("Layla", "Narthollis", "Emma"))
    val NAMES_WITH_LANA_USED = listOf("Valeriya", "Layla")

    // t2 run: tree seed 242749265, layout seed 603969201, normal difficulty, no crew names in use
    val MAP_AE = KnownMap(242749265, 603969201, 2,
        "CIVILIAN_SECTOR,ROCK_SECTOR,ENGI_SECTOR,ZOLTAN_HOME,CIVILIAN_SECTOR,MANTIS_HOME,ZOLTAN_SECTOR,ENGI_SECTOR,ROCK_SECTOR,ROCK_HOME,LANIUS_SECTOR,ZOLTAN_SECTOR,SLUG_SECTOR,REBEL_SECTOR,ROCK_SECTOR,SLUG_SECTOR,ZOLTAN_SECTOR,SLUG_SECTOR,ROCK_SECTOR,FINAL",
        "01000100111021120211",
        "1.2|3.4|5.6|7|7|8|8|9.10|11.12|13|13|14|14|15.16|17.18|19|19|19|19|",
        "87,92,0,0:1.4.5 36,201,0,0:0.2.4.5 30,280,0,0:1.3.6.7 86,361,0,0:2.6.7 152,87,0,0:0.1.5.8.9 176,135,0,0:0.1.4.6.8.9.10 187,275,0,0:2.3.5.7.10.11 142,395,0,0:2.3.6.11 255,45,0,0:4.5.9.12 271,126,0,0:4.5.8.10.12.13 267,268,0,0:5.6.9.11.14 287,404,0,0:6.7.10.14.15 347,87,0,0:8.9.13 418,178,0,0:9.12.14.16.17.18 406,315,0,0:10.11.13.15.18.19 385,414,0,0:11.14.19 520,75,0,0:13.17.20.21 510,150,1,0:13.16.18.20.21.22 490,231,0,0:13.14.17.22 481,411,0,0:14.15.23 631,46,0,0:16.17.21 630,143,0,0:16.17.20.22 568,241,0,0:17.18.21 563,406,0,0:19",
        "AUTO_ASTEROID|event_AUTO_ASTEROID_text|1309703308 MERCENARY|text_MERCENARY_3|363919194 START_BEACON|*|0 PIRATE_BRIBER|text_PIRATE_BRIBER_1|108253816 PIRATE_CIVILIAN|text_PIRATE_CIVILIAN_6|0 AUTO_SUN|event_AUTO_SUN_text|1504436627 FRIENDLY_SLAVER|event_FRIENDLY_SLAVER_text|1745258546 PIRATE_CHOICE|event_PIRATE_CHOICE_text|1642474308 PIRATE|text_PIRATE_3|1521110958 PIRATE_CHOICE|event_PIRATE_CHOICE_text|241695459 STORE|text_STORE_TEXT_5|0 PIRATE_STATION_CROPS|event_PIRATE_STATION_CROPS_text|1235644972 REFUGEE_GHOST|event_REFUGEE_GHOST_text|0 AUTO_REFUEL_STATION|event_AUTO_REFUEL_STATION_text|1461893006 SELL_DRONES_STATION|event_SELL_DRONES_STATION_text|0 PIRATE_SUN|event_PIRATE_SUN_text|1278753841 TERRAFORMING_SCAN|event_TERRAFORMING_SCAN_text|0 FINISH_BEACON|event_FINISH_BEACON_text|0 PIRATE_CHOICE|event_PIRATE_CHOICE_text|289669919 DISTRESS_INFESTATION|event_DISTRESS_INFESTATION_text|0 STORE|text_STORE_TEXT_2|0 AUTO_DEFENSE_MAP|event_AUTO_DEFENSE_MAP_text|1707099304 NOTHING|text_NOTHING_4|0 QUEST_CREWDEAD_START|event_QUEST_CREWDEAD_START_text|0")

    // m1-normal-vanilla run: tree seed 1260742337, layout seed 473367081, normal difficulty, no crew names in use
    val MAP_VANILLA = KnownMap(1260742337, 473367081, 2,
        "CIVILIAN_SECTOR,MANTIS_SECTOR,PIRATE_SECTOR,NEBULA_SECTOR,ROCK_SECTOR,ZOLTAN_HOME,CIVILIAN_SECTOR,MANTIS_SECTOR,CIVILIAN_SECTOR,ROCK_SECTOR,CIVILIAN_SECTOR,CIVILIAN_SECTOR,ENGI_HOME,ZOLTAN_SECTOR,ROCK_SECTOR,CIVILIAN_SECTOR,ENGI_SECTOR,ZOLTAN_SECTOR,ZOLTAN_SECTOR,FINAL",
        "01121001010000100001",
        "1.2|3.4|4.5|6.7|7.8|8.9|10|10.11|11.12|12|13.14|14.15|15.16|17|17|18|18|19|19|",
        "79,49,0,0:1.4.5 46,162,0,0:0.2.4.5.6 27,276,0,0:1.3.5.6 73,394,0,0:2.6.7 121,38,0,0:0.1.5 128,176,0,0:0.1.2.4.6.10 127,284,0,0:1.2.3.5.7.10 208,370,0,0:3.6.10.11 296,59,0,0:9.12 293,171,0,0:8.10.13.14 235,281,0,0:5.6.7.9.11.14 318,399,0,0:7.10.14.15 426,44,0,1:8.13.16.17 414,190,0,0:9.12.14.16.17.18 367,302,0,0:9.10.11.13.15.18 387,408,0,0:11.14.18.19 462,39,1,1:12.13.17.20 533,156,0,0:12.13.16.20.21.22 454,308,0,0:13.14.15.19 533,381,0,0:15.18.23 592,73,0,0:16.17.21 625,168,0,0:17.20.22 644,232,0,0:17.21 615,405,0,0:19",
        "PIRATE_STATION_CROPS|event_PIRATE_STATION_CROPS_text|364497375 QUEST_ESCORT|text_QUEST_ESCORT_TEXT_2|188436447 START_BEACON|*|0 STORE|text_STORE_TEXT_4|0 NOTHING|text_NOTHING_6|0 AUTO_ASTEROID|event_AUTO_ASTEROID_text|1835423012 AUTO_DEFENSE_ITEM|event_AUTO_DEFENSE_ITEM_text|1255252175 STORE|text_STORE_TEXT_3|0 FRIENDLY_BEACON|text_FRIENDLY_BEACON_2|2065545049 SQUAT_REFUEL_STATION|event_SQUAT_REFUEL_STATION_text|294705754 FUELING_STATION|event_FUELING_STATION_text|0 PIRATE_SUN|event_PIRATE_SUN_text|173472539 STORM_ITEMS|event_STORM_ITEMS_text|0 REBEL|text_REBEL_3|148501871 PIRATE_BRIBER|text_PIRATE_BRIBER_2|252926272 MERCENARY|text_MERCENARY_3|684340006 FINISH_BEACON_NEBULA|event_FINISH_BEACON_NEBULA_text|0 NOTHING|text_NOTHING_4|0 PIRATE|text_PIRATE_2|498587260 REFUEL_STATION|event_REFUEL_STATION_text|0 SQUAT_WARNING|event_SQUAT_WARNING_text|1077956558 PIRATE_ASTEROID|event_PIRATE_ASTEROID_text|530594700 AUTO_HACKER|event_AUTO_HACKER_text|2028862435 REFUGEE_NO_DISTRESS|event_REFUGEE_NO_DISTRESS_text|0")

    }
}
