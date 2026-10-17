package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.GameMap
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer

/**
 * Diagnostics only: the sector tree, the beacon map and every created event
 * for given seeds, as JSON in the shape of the native extractor
 * (`.build/gameplay-1to1/tools/native_extract.py`), so a harness can diff
 * this engine against the original's memory for the same seeds.
 *
 * Input (all numbers are ints):
 *   {"treeSeed":N,"layoutSeed":N,"difficulty":0|1|2,"sector":0,"usedNames":["..."]}
 * `sector` is the index into the tree in creation order (0 is the start
 * sector). The engine's Advanced Edition mode is the one it was started in
 * and is reported as "ae". `usedNames` are the crew names the event crew
 * must avoid (the player's crew when the original generated the map).
 *
 * With `"storeSeed":N` (and `"storeLevel":L`, default 0) the output also has
 * a "store" object: the store this engine makes for the current player ship
 * from that stream state (sections in slot order, the boxes of each, the
 * resources, the repair price and the number of draws), for comparing with
 * the original's Store memory.
 */
object BrowserParityGeneration {
    fun run(main: MainGame, container: GameContainer, json: String): String {
        val treeSeed = int(json, "treeSeed")
        val layoutSeed = int(json, "layoutSeed")
        val difficulty = Difficulty.values()[int(json, "difficulty", 1)]
        val sectorIndex = int(json, "sector", 0)
        val usedNames = strings(json, "usedNames")
        val storeSeed = if (raw(json, "storeSeed") != null) int(json, "storeSeed") else null
        val storeLevel = int(json, "storeLevel", 0)

        val state = main.currentState as? InGameState
            ?: BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_HARD", difficulty)
        val rng = NativeRandom.snapshot()
        try {
            val result = generate(state, treeSeed, layoutSeed, difficulty, sectorIndex, usedNames)
            if (storeSeed == null)
                return result
            return result.dropLast(1) + ",\"store\":" + describeStore(state, storeSeed, storeLevel) + "}"
        } finally {
            state.eventCreator.usedNamesFixture = null
            NativeRandom.restore(rng)
        }
    }

    /** Also used by the focused regressions. */
    internal fun generate(
        state: InGameState, treeSeed: Int, layoutSeed: Int, difficulty: Difficulty,
        sectorIndex: Int, usedNames: List<String>
    ): String {
        state.setDifficulty(difficulty)
        state.eventManager.resetEventState()
        state.eventCreator.usedNamesFixture = usedNames

        val map = GameMap(state.content.datafile, state.eventManager, state.content.enableAdvancedEdition, treeSeed)
        val flat = map.sectors.flatten()
        val info = flat[sectorIndex]
        val sector = map.generateSector(info, state, layoutSeed)
        return describe(state, map, flat, sector)
    }

    /** A store for a stream seed: the original's Store creation on the current player ship. */
    private fun describeStore(state: InGameState, seed: Int, level: Int): String {
        state.eventCreator.refreshUsedNames()
        NativeRandom.seed(seed)
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        val store = StoreData()
        try {
            store.generateRandomContents(state, NativeRandom, level)
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

        fun names(items: List<xyz.znix.xftl.Blueprint?>) = items.joinToString(",", "[", "]") { BrowserDebug.quote(it?.name ?: "") }
        val sb = StringBuilder("{\"seed\":").append(seed).append(",\"level\":").append(level)
        sb.append(",\"draws\":").append(draws)
        sb.append(",\"systems\":").append(state.player.systems.joinToString(",", "[", "]") { BrowserDebug.quote(it.blueprint.name) })
        sb.append(",\"sections\":[")
        for ((n, section) in store.sections.withIndex()) {
            if (n > 0) sb.append(',')
            sb.append("{\"type\":").append(BrowserDebug.quote(section.name)).append(",\"boxes\":")
            when (section) {
                StoreData.Section.WEAPONS -> sb.append(names(store.weapons))
                StoreData.Section.DRONES -> sb.append(names(store.drones))
                StoreData.Section.AUGMENTS -> sb.append(names(store.augments))
                StoreData.Section.CREW -> sb.append(store.crew.joinToString(",", "[", "]") { member ->
                    if (member == null) "null" else {
                        val skills = xyz.znix.xftl.crew.Skill.values().joinToString(",", "[", "]") { (((member.skills[it] ?: 0f) * 2f).toInt()).toString() }
                        "{\"race\":${BrowserDebug.quote(member.race.name)},\"name\":${BrowserDebug.quote(member.name)},\"colour\":${member.colour},\"skills\":$skills}"
                    }
                })
                StoreData.Section.SYSTEMS -> sb.append(store.systems.withIndex().joinToString(",", "[", "]") { (idx, system) ->
                    if (system == null) "null" else
                        "{\"name\":${BrowserDebug.quote(system.name)},\"price\":${store.systemPrice(state, idx)},\"variant\":${store.systemVariants[idx]}}"
                })
            }
            sb.append('}')
        }
        val res = store.availableResources
        sb.append("],\"fuel\":").append(res.fuel).append(",\"missiles\":").append(res.missiles)
        sb.append(",\"drones\":").append(res.droneParts).append(",\"repair\":").append(StoreData.repairPrice(level)).append('}')
        return sb.toString()
    }

    private fun describe(state: InGameState, map: GameMap, flat: List<GameMap.SectorInfo>, sector: Sector): String {
        val sb = StringBuilder()
        sb.append("{\"ae\":").append(state.content.enableAdvancedEdition)
        sb.append(",\"treeSeed\":").append(map.generationSeed)
        sb.append(",\"layoutSeed\":").append(sector.layoutSeed)
        sb.append(",\"worldLevel\":").append(sector.sectorNumber)
        sb.append(",\"tree\":[")
        for ((i, node) in flat.withIndex()) {
            if (i > 0) sb.append(',')
            // The start sector draws from STANDARD_SPACE but is named as the civilian sector.
            val type = if (node.type.name == "STANDARD_SPACE") "CIVILIAN_SECTOR" else node.type.name
            sb.append("{\"col\":").append(node.columnNumber)
            sb.append(",\"class\":").append(node.sectorClass.ordinal)
            sb.append(",\"type\":").append(BrowserDebug.quote(type))
            sb.append(",\"kids\":").append(node.nextSectors.map { flat.indexOf(it) }.joinToString(",", "[", "]"))
            sb.append('}')
        }
        sb.append("],\"locations\":[")
        for ((i, beacon) in sector.beacons.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"i\":").append(i)
            sb.append(",\"x\":").append(beacon.pos.x).append(",\"y\":").append(beacon.pos.y)
            sb.append(",\"exit\":").append(if (beacon.isExit || beacon.isBase) 1 else 0)
            sb.append(",\"nebula\":").append(if (beacon.inNebula) 1 else 0)
            sb.append(",\"nbrs\":").append(beacon.neighbours.map { sector.beacons.indexOf(it) }.joinToString(",", "[", "]"))
            sb.append(",\"event\":")
            describeEvent(sb, beacon.createdEvent)
            sb.append('}')
        }
        sb.append("],\"start\":").append(sector.beacons.indexOf(sector.startBeacon))
        sb.append(",\"danger\":[").append(sector.dangerZoneCentre.x).append(',').append(sector.dangerZoneCentre.y).append(']')
        sb.append(",\"clouds\":").append(sector.nebulaClouds.joinToString(",", "[", "]") {
            "[${it.x},${it.y},${it.width},${it.height}]"
        })
        sb.append('}')
        return sb.toString()
    }

    private fun describeEvent(sb: StringBuilder, event: CreatedEvent) {
        sb.append("{\"name\":").append(BrowserDebug.quote(event.template.debugId))
        sb.append(",\"text\":").append(BrowserDebug.quote(event.text?.let { it.id ?: it.localised } ?: ""))
        sb.append(",\"res\":{\"missiles\":").append(event.missiles)
        sb.append(",\"fuel\":").append(event.fuel)
        sb.append(",\"drones\":").append(event.droneParts)
        sb.append(",\"scrap\":").append(event.scrap)
        sb.append(",\"crew\":").append(if (event.crew != null) 1 else 0).append('}')
        sb.append(",\"seed\":").append(event.shipSeed)
        sb.append(",\"boarders\":").append(event.boarders)
        sb.append(",\"weapon\":").append(BrowserDebug.quote(event.weapon?.name ?: ""))
        sb.append(",\"drone\":").append(BrowserDebug.quote(event.drone?.name ?: ""))
        sb.append(",\"aug\":").append(BrowserDebug.quote(event.augment?.name ?: ""))
        event.crew?.let { crew ->
            sb.append(",\"crewMember\":{\"race\":").append(BrowserDebug.quote(crew.race.name))
            sb.append(",\"name\":").append(BrowserDebug.quote(crew.name))
            sb.append(",\"colour\":").append(crew.colour)
            sb.append(",\"skills\":").append(xyz.znix.xftl.crew.Skill.values().joinToString(",", "[", "]") {
                // Half a bar is one step of the skill's maximum.
                (crew.skills[it]?.times(2f)?.toInt() ?: 0).toString()
            })
            sb.append('}')
        }
        sb.append(",\"choices\":[")
        for ((i, choice) in event.choices.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"text\":").append(BrowserDebug.quote(choice.text.id ?: choice.text.localised))
            sb.append(",\"event\":")
            if (choice.event != null) describeEvent(sb, choice.event) else sb.append("null")
            sb.append('}')
        }
        sb.append("]}")
    }

    // A small reader for the flat input objects (numbers, bools, strings, string arrays).
    private fun raw(json: String, key: String): String? {
        val marker = "\"$key\""
        val at = json.indexOf(marker)
        if (at < 0) return null
        var i = json.indexOf(':', at + marker.length) + 1
        while (i < json.length && json[i] == ' ') i++
        return json.substring(i)
    }

    private fun int(json: String, key: String, default: Int? = null): Int {
        val text = raw(json, key) ?: return default ?: error("Missing $key")
        var end = 0
        while (end < text.length && (text[end] == '-' || text[end].isDigit())) end++
        return text.substring(0, end).toInt()
    }

    private fun strings(json: String, key: String): List<String> {
        val text = raw(json, key) ?: return emptyList()
        if (!text.startsWith("[")) return emptyList()
        val out = ArrayList<String>()
        var i = 1
        while (i < text.length && text[i] != ']') {
            if (text[i] == '"') {
                val sb = StringBuilder()
                i++
                while (text[i] != '"') {
                    if (text[i] == '\\') i++
                    sb.append(text[i])
                    i++
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }
}
