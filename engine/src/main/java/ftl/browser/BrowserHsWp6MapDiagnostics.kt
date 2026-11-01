package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * WP6 map probe (`BrowserEngine.hsWp6MapProbe`), Hyperspace sessions only. The run is started and played through
 * the real UI by tests/hs-wp6map.mjs (hangar seed box, beacon clicks, key presses, as the oracle's sessions/wp6-map.json
 * does); this only loads the oracle's own driver and reads state. Commands `name:arg`:
 * - `driver:<lua>`: run tools/hs-oracle/tracer/data/hs_oracle/wp6-map.lua; its `{"k":"wp6m_..."}` log lines are kept.
 * - `options:<name,name..>`: hyperspace.xml with `<name enabled="true"/>` appended (the oracle's hs_options), parsed
 *   again; an empty list restores the session's config. Applies to runs started afterwards.
 * - `lua:<code>`: run Lua; the result is the error or "PASS", then the driver lines written since the last call.
 * - `box`: the event of the open choice box, or "". `state`: JSON (state, window, beacon, fuel, waiting, sector).
 * - `sector[:k]`: the beacon map, the sector map, then the k-th next sector from the top (the oracle: console EXIT,
 *   key k; default 1).
 * - `savereload`: the run saved and loaded again (MainGame.loadSavedGame).
 * - `tree`: the sector tree (level, index, type, next sectors by index), one line per sector.
 */
object BrowserHsWp6MapDiagnostics {
    private val lines = ArrayList<String>()
    private var original: HsConfig? = null
    private var sinkInstalled = false

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, cmd: String): String {
        val name = cmd.substringBefore(':')
        val arg = cmd.substringAfter(':', "")
        return try {
            check(HS.active) { "hs-wp6map needs a Hyperspace session" }
            install()
            when (name) {
                "driver" -> { install(); HsLua.runString("wp6-map", arg) ?: "PASS" }
                "options" -> options(arg)
                "lua" -> {
                    install()
                    val error = HsLua.runString("wp6m", arg)
                    val out = (error ?: "PASS") + lines.joinToString("") { "\n" + it }
                    lines.clear()
                    out
                }
                "box" -> (main.currentState as? InGameState)?.shipUI?.diagnosticDialogue?.hsOpenEvent ?: ""
                "state" -> state(main)
                "tree" -> (main.currentState as InGameState).gameMap.sectors.flatten().joinToString("\n") { s ->
                    "${s.columnNumber} ${s.columnIndex} ${s.type.name} " + s.nextSectors.sortedBy { it.columnIndex }.joinToString(",") { "${it.columnIndex}" } }
                "sector" -> sector(main, container, graphics, arg.toIntOrNull() ?: 1)
                "answer" -> answer(main)
                "menu" -> { main.switchToMainMenu(); "PASS" }
                // ShipManager::AddCrewMemberFromString("Ruwen", "crystal", ...) (F2 sessions run no Lua).
                // A crystal under a given name through the Lua API (no blueprint join: the "ruwen" flag stays unset).
                "crystal" -> { val g = main.currentState as InGameState; xyz.znix.xftl.hs.crew.HsCrew.addCrewMemberFromString(g.player, arg, "crystal", false, 0, true, true); "PASS crew=" + g.player.crew.size }
                "ruwen" -> {
                    val g = main.currentState as InGameState
                    xyz.znix.xftl.hs.crew.HsCrew.addCrewMemberFromString(g.player, g.translator["name_Ruwen"], "crystal", false, 0, true, true)
                    "PASS crew=" + g.player.crew.size
                }
                // F2 runs no Lua: ShipObject:AddAugmentation(name) / CustomEventsParser:LoadEvent(world, name, true, -1).
                "aug" -> { val g = main.currentState as InGameState; "PASS " + g.player.addBlueprint(g.blueprintManager[arg] as xyz.znix.xftl.Blueprint, true) }
                "load" -> { val g = main.currentState as InGameState; xyz.znix.xftl.hs.events.HsFlow.loadEvent(g, g.shipUI.hsHost, arg, true, -1, null); "PASS" }
                // The open box's choices as HsFlow made them: text|type per line.
                "choices" -> lastChoices?.joinToString("\n") { "${it.first}|${it.second}" } ?: ""
                // Press the n-th shown choice of the open box (1-based).
                "pick" -> { val d = (main.currentState as InGameState).shipUI.diagnosticDialogue ?: return "FAILED no box"; d.selectOption(arg.toInt() - 1); "PASS" }
                // The current sector's quest beacons (StarMap's questLoc marks): x,y and the event's name.
                "quests" -> {
                    val g = main.currentState as InGameState
                    g.currentBeacon.sector.beacons.filter { it.hasQuest }.joinToString(";") { "${it.pos.x},${it.pos.y}:${it.event.hsName}" } +
                        " | events " + g.currentBeacon.sector.beacons.joinToString(";") { "${it.pos.x},${it.pos.y}:${it.event.hsName}" }
                }
                // Save + Continue: the run save document, loaded as MainGame.continueSavedRun does.
                "savereload" -> { main.loadSavedGame(BrowserXml.parse(BrowserXml.write((main.currentState as InGameState).saveGameState()))); repeat(4) { main.update(container, 0.05f) }; "PASS" }
                else -> "FAILED unknown command $name"
            }
        } catch (e: Throwable) {
            "FAILED $name: $e " + e.stackTrace.take(6).joinToString(" < ")
        }
    }

    /** The open box's choices (text, type) as HsFlow made them; null for a box without choices (Continue...). */
    private var lastChoices: List<Pair<String, Int>>? = null
    private val MENU_RX = Regex("^(Do something onboard|Perform a task|Do some last minute)", RegexOption.IGNORE_CASE)

    /**
     * The oracle autoplay's policy (oracle.py `policy`): the first enabled choice (type != 1) whose text does not open
     * Multiverse's ship-management menus, else the first enabled one. Returns the event and the key, or "" with no box.
     */
    private fun answer(main: MainGame): String {
        val game = main.currentState as? InGameState ?: return ""
        val dialogue = game.shipUI.diagnosticDialogue ?: return ""
        val choices = lastChoices ?: listOf("Continue..." to 0)
        val enabled = choices.indices.filter { choices[it].second != 1 }
        val plain = enabled.filter { !MENU_RX.containsMatchIn(choices[it].first) }
        val i = (plain.firstOrNull() ?: enabled.firstOrNull() ?: 0).coerceAtMost(maxOf(0, dialogue.optionCount - 1))
        val event = dialogue.hsOpenEvent
        dialogue.selectOption(i)
        return "$event ${i + 1}"
    }

    private fun install() {
        if (sinkInstalled) return
        sinkInstalled = true
        xyz.znix.xftl.hs.events.HsFlow.boxListener = { _, _, choices ->
            lastChoices = choices?.takeIf { it.isNotEmpty() }?.map { it.choice.text.localised to it.type }
        }
        val previous = HsLua.sink
        HsLua.sink = { kind, text ->
            previous?.invoke(kind, text)
            if (text.startsWith("{\"k\":\"wp6m_")) lines += text.trimEnd()
        }
    }

    private fun options(arg: String): String {
        if (original == null) original = HS.config
        val names = arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) {
            HS.config = original
            return "PASS restored"
        }
        val text = String(BrowserFiles.read(HS.HYPERSPACE_XML), Charsets.UTF_8)
        val end = text.lastIndexOf("</")
        check(end > 0) { "hyperspace.xml has no root end tag" }
        val extra = names.joinToString("") { "\n<$it enabled=\"true\"/>\n" }
        HS.config = HsConfig.parse(BrowserXml.parse(text.substring(0, end) + extra + text.substring(end)).rootElement)
        val c = HS.cfg()
        return "PASS infinite=${c.infiniteMode} split=${c.splitAllNodeView} showAll=${c.showAllConnections}"
    }

    private fun state(main: MainGame): String {
        val o = BrowserHsEventsDiagnostics.Obj().put("state", main.currentState?.let { it::class.simpleName })
        val game = main.currentState as? InGameState ?: return o.toString()
        val window = game.shipUI.hsDiagnosticWindow
        o.put("window", window?.let { it::class.simpleName })
        o.put("at", "${game.currentBeacon.pos.x},${game.currentBeacon.pos.y}")
        o.put("fuel", game.player.fuelCount)
        o.put("hull", game.player.health)
        o.put("waiting", (window as? xyz.znix.xftl.game.JumpWindow)?.isWaiting == true)
        o.put("jumping", game.isPlayerJumping)
        o.put("ftl", game.player.isFtlReady)
        o.put("sector", game.currentBeacon.sector.info.columnNumber)
        o.put("type", game.currentBeacon.sector.info.type.name)
        o.put("box", game.shipUI.diagnosticDialogue?.hsOpenEvent)
        return o.toString()
    }

    private fun sector(main: MainGame, container: GameContainer, graphics: Graphics, key: Int): String {
        val game = main.currentState as InGameState
        val from = game.currentBeacon.sector
        game.shipUI.openJumpMap()
        main.render(container, graphics)
        game.shipUI.openSectorMap()
        main.render(container, graphics)
        game.shipUI.hsDiagnosticWindow as? xyz.znix.xftl.game.SectorMapWindow ?: return "FAILED sector: the sector map did not open"
        val next = from.info.nextSectors.sortedBy { it.columnIndex }.getOrNull(key - 1) ?: return "FAILED sector: no next sector $key"
        game.shipUI.sectorChosen(next)
        return "PASS ${next.type.name} ${next.columnNumber} ${next.columnIndex}"
    }
}
