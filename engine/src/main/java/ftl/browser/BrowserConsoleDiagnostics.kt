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
            ",\"gate\":${xyz.znix.xftl.devutil.ConsoleGate.allowed()}}"
    }
}
