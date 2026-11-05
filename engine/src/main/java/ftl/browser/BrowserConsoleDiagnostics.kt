package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame

/**
 * tests/console.mjs (`BrowserEngine.consoleProbe`): what the debug console's commands change, as one JSON object: the
 * player's systems (level/damage), reactor, crew (race, health, skills), augments, cargo, weapons and drones, the enemy
 * (hull, living crew), the sector's fleet modifier, the open window, SPEED's level, the profile's unlocked layouts and
 * achievements, and the console's state. Test-only.
 */
object BrowserConsoleDiagnostics {
    private fun q(s: String?) = BrowserDebug.quote(s)
    private fun list(items: List<String>) = items.joinToString(",", "[", "]") { q(it) }

    fun state(main: MainGame): String {
        val game = main.currentState as? InGameState ?: return "{\"state\":${q(main.currentState?.javaClass?.simpleName)}}"
        val ship = game.player
        val enemy = game.enemy
        val crew = ship.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === ship }
        val profile = main.profile
        val layouts = game.content.shipFamilies.families.flatMap { it.ships }.filter { profile.isShipUnlocked(it) }
        val achievements = game.content.achievements.achievements.values.filter { profile.getAchievement(it) != null }.map { it.id }
        return "{\"state\":\"InGameState\"" +
            ",\"systems\":${list(ship.systems.map { "${it.codename}:${it.energyLevels}:${it.damagedEnergyLevels}" })}" +
            ",\"reactor\":${ship.purchasedReactorPower},\"maxReactor\":${ship.maxReactorPower}" +
            ",\"crew\":${list(crew.map { c -> "${c.blueprint.name}:${c.info.name}:${c.health}:" + c.info.skills.values.joinToString("/") })}" +
            ",\"augments\":${list(ship.augments.map { it.name })}" +
            ",\"cargo\":${list(ship.cargoBlueprints.mapNotNull { it?.name })}" +
            ",\"weapons\":${list(ship.hardpoints.mapNotNull { it.weapon?.type?.name })}" +
            ",\"drones\":${list(ship.drones?.drones?.mapNotNull { it?.type?.name } ?: emptyList())}" +
            ",\"enemy\":${if (enemy == null) "null" else "{\"hull\":${enemy.health},\"dead\":${enemy.isDead},\"crew\":${enemy.crew.filterIsInstance<LivingCrew>().count { it.ownerShip === enemy && it.health > 0f }}}"}" +
            ",\"fleet\":${game.currentBeacon.sector.fleetAdvanceModifier}" +
            ",\"window\":${q(game.shipUI.diagnosticWindow)}" +
            ",\"choices\":${list(game.shipUI.diagnosticDialogue?.choiceEntries?.filter { !it.hidden }?.map { it.text } ?: emptyList())}" +
            ",\"speed\":${xyz.znix.xftl.hs.lua.LuaFps.speedLevel}" +
            ",\"layouts\":${layouts.size},\"allLayouts\":${game.content.shipFamilies.families.sumOf { it.ships.size }}" +
            ",\"achievements\":${list(achievements)}" +
            ",\"console\":${q(game.debugConsole.debugState(game.isDebugConsoleVisible))}" +
            // an event of the data with two choices and no ship, for LOADEVENT and EVENT
            ",\"someEvent\":${q(game.eventManager.eventNames.sorted().firstOrNull { n ->
                val e = game.eventManager.getOrNull(n) as? xyz.znix.xftl.sector.Event
                e != null && !e.unique && e.text != null && e.choices.size >= 2 && !n.contains("SHIP") && !n.contains("STORE")
            })}" +
            ",\"gate\":${xyz.znix.xftl.devutil.ConsoleGate.allowed()}" +
            ",\"multipleOver\":${xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)}" +
            ",\"panel\":${q(game.debugConsole.debugPanel())}}"
    }

    /** The blueprint element of each item kind, as the data files hold it. */
    private val ELEMENTS = mapOf("weapon" to "weaponBlueprint", "drone" to "droneBlueprint", "augment" to "augBlueprint",
        "crew" to "crewBlueprint", "system" to "systemBlueprint")

    /**
     * `get`/`unlock` [command] [kind]: the picker's items (id, key, English and current-language name, tags), and for the
     * blueprint kinds the names read straight from the run's blueprint files (BlueprintManager's files: the base ones,
     * the AE ones with OVERRIDE_ taken off in an AE run), for tests/console.mjs to hold the two against each other.
     */
    fun items(main: MainGame, command: String, kind: String): String {
        val game = main.currentState as? InGameState ?: return "{}"
        val k = xyz.znix.xftl.devutil.ConsoleCheats.kindOf(command, kind) ?: return "{\"error\":\"no such kind\"}"
        val items = xyz.znix.xftl.devutil.ConsoleItems.items(game, k)
        val json = items.joinToString(",", "[", "]") {
            "{\"id\":${q(it.id)},\"key\":${q(it.key)},\"english\":${q(it.english)},\"local\":${q(it.local)},\"tags\":${list(it.tags)},\"layout\":${it.layout}}"
        }
        val element = ELEMENTS[kind]
        val data = if (element == null) "null" else {
            val df = game.content.datafile
            val ae = game.content.enableAdvancedEdition
            val files = listOf("blueprints.xml", "autoBlueprints.xml", "bosses.xml", "dlcPirateBlueprints.xml") +
                if (ae) listOf("dlcBlueprints.xml", "dlcBlueprintsOverwrite.xml") else emptyList()
            val names = LinkedHashSet<String>()
            for (f in files) {
                val file = df.getOrNull("data/$f") ?: continue
                for (e in df.parseXML(file).rootElement.children) {
                    if (e.name != element) continue
                    var n = e.getAttributeValue("name") ?: continue
                    if (n.startsWith(xyz.znix.xftl.BlueprintManager.AE_PREFIX)) { if (!ae) continue; n = n.removePrefix(xyz.znix.xftl.BlueprintManager.AE_PREFIX) }
                    // the picker leaves out crew without a title (drone and other internal crew: CREW makes nothing of them)
                    if (element == "crewBlueprint" && e.getChild("title") == null) continue
                    names += n
                }
            }
            list(names.toList())
        }
        return "{\"items\":$json,\"data\":$data}"
    }
}
