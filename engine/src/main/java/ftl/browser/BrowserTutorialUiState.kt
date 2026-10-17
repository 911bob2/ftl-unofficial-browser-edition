package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.weapons.BeamBlueprint

/** Explicit diagnostic fields for tutorial input and failures. Read-only;
 * no commands, private instruction text, random draws or saved state changes. */
object BrowserTutorialUiState {
    fun snapshot(main: MainGame, game: InGameState): String {
        if (!main.isTutorialRunning || main.currentState !== game) return "null"
        val overlay = main.tutorialOverlay
        val visible = overlay != null && !overlay.isSuppressed(game)
        fun rect(bounds: BrowserTutorialOverlay.Bounds?) = bounds?.let {
            "[${it.x},${it.y},${it.width},${it.height}]"
        } ?: "null"
        val panel = if (visible) rect(overlay!!.panelBounds(game)) else "null"
        val next = if (visible) rect(overlay!!.continueBounds(game)) else "null"
        val origin = game.playerShipRenderToScreen(0, 0)
        val crew = game.playerCrew.joinToString(",") {
            val pos = game.shipUI.crewScreenPos(it, origin)
            "{\"room\":${it.room.id},\"health\":${it.health},\"x\":${pos.x + 17},\"y\":${pos.y + 17}}"
        }
        val rooms = game.player.rooms.joinToString(",") {
            val pos = game.playerShipRenderToScreen(it.pixelCentre.x, it.pixelCentre.y)
            "{\"id\":${it.id},\"system\":${BrowserDebug.quote(it.system?.codename)},\"oxygen\":${it.oxygen},\"fires\":${it.fires.count { fire -> fire != null }},\"x\":${pos.x},\"y\":${pos.y}}"
        }
        val doors = game.player.rooms.flatMap { it.doors }.distinct().joinToString(",") {
            val pos = game.playerShipRenderToScreen(it.pixelCentre.x, it.pixelCentre.y)
            "{\"left\":${it.left?.id},\"right\":${it.right?.id},\"airlock\":${it.isAirlock},\"open\":${it.open},\"x\":${pos.x},\"y\":${pos.y}}"
        }
        val enemyRooms = game.enemy?.rooms?.joinToString(",") {
            val pos = game.enemyShipRenderToScreen(it.pixelCentre.x, it.pixelCentre.y)
            "{\"id\":${it.id},\"system\":${BrowserDebug.quote(it.system?.codename)},\"x\":${pos.x},\"y\":${pos.y}}"
        } ?: ""
        val enemyVolleys = game.enemy?.hardpoints?.mapNotNull { it.weapon }
            ?.sumOf { it.browserEncounterVolleys ?: 0 } ?: 0
        val systems = game.player.mainSystems.joinToString(",") { BrowserDebug.quote(it.codename) }
        val cargo = game.player.cargoBlueprints.mapIndexed { index, item ->
            "{\"slot\":$index,\"blueprint\":${BrowserDebug.quote(item?.name)},\"beam\":${item is BeamBlueprint}}"
        }.joinToString(",")
        val map = game.shipUI.tutorialJumpWindow
        val beacons = if (map == null) "" else game.currentBeacon.neighbours.joinToString(",") {
            "{\"exit\":${it.isExit},\"x\":${map.position.x + Sector.OFFSET.x + it.pos.x},\"y\":${map.position.y + Sector.OFFSET.y + it.pos.y}}"
        }
        return "{\"panel\":$panel,\"continue\":$next,\"crew\":[$crew],\"rooms\":[$rooms],\"doors\":[$doors],\"enemyRooms\":[$enemyRooms],\"enemyVolleys\":$enemyVolleys,\"enemyPresent\":${game.getEnemyOf(game.player) != null},\"ftlReady\":${game.player.isFtlReady},\"invulnerable\":${game.debugFlags.noDmg.set},\"enemyFireDisabled\":${game.debugFlags.noEnemyFire.set},\"systems\":[$systems],\"cargo\":[$cargo],\"beacons\":[$beacons]}"
    }
}
