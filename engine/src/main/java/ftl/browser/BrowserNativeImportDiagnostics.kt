package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.vanillasave.NativeImport
import xyz.znix.xftl.vanillasave.NativeProfile

/**
 * Test probe for saves imported from the original game (tests/native-import.mjs, `nativeImportProbe`): converts a run
 * and a profile the way Continue and the start do (NativeImport) and describes what came out, so the test compares it
 * with what the original saved. The run is also saved and loaded again, as the next Continue does. Not reachable from
 * player input; it replaces the current run of the page it runs in.
 */
object BrowserNativeImportDiagnostics {
    private fun q(s: String?) = BrowserDebug.quote(s)

    fun run(game: MainGame, runBase64: String?, profileBase64: String?, prefix: String): String {
        val out = StringBuilder("{\"prefix\":").append(q(prefix))
        try {
            if (!profileBase64.isNullOrEmpty()) {
                val file = NativeImport.File("profile", prefix, "probe", java.util.Base64.getDecoder().decode(profileBase64))
                val parsed = NativeProfile.parse(file.bytes, file.hyperspace)
                val doc = NativeImport.profileDocument(file)
                val root = doc.rootElement
                out.append(",\"profile\":{\"layouts\":").append(names(root.getChildren("layout").map { it.getAttributeValue("ship") }))
                    .append(",\"achievements\":").append(names(root.getChildren("ach").map { it.getAttributeValue("id") + ":" + it.getAttributeValue("diff") }))
                    .append(",\"victories\":").append(names(root.getChildren("layoutVictory").map { it.getAttributeValue("ship") + ":" + it.getAttributeValue("diff") }))
                    .append(",\"scores\":").append(root.getChild("browserStats").getChildren("score").size)
                    .append(",\"games\":").append(parsed.games).append(",\"victoriesTotal\":").append(parsed.victories)
                    .append(",\"stats\":[").append(parsed.stats.joinToString(",") { "[${it.first},${it.second}]" }).append("]")
                    .append(",\"customUnlocked\":").append(parsed.customUnlocked.size)
                    .append(",\"hsAchievements\":").append(parsed.hsAchievements.size)
                    .append(",\"metaVariables\":").append(parsed.metaVariables.size)
                    .append(",\"xml\":").append(BrowserXml.write(doc).length).append("}")
            }
            if (!runBase64.isNullOrEmpty()) {
                val file = NativeImport.File("run", prefix, "probe", java.util.Base64.getDecoder().decode(runBase64))
                val data = NativeImport.parseRun(file, game.gameContent)
                out.append(",\"parsed\":{\"beacons\":").append(data.beacons.size).append(",\"current\":").append(data.currentBeaconId)
                    .append(",\"sector\":").append(data.sectorNumber + 1).append(",\"tail\":").append(data.tail.size)
                    .append(",\"playerVariables\":").append(data.hs?.playerVariables?.size ?: 0)
                    .append(",\"sectorType\":").append(q(data.hs?.sectorType)).append(",\"customStores\":").append(data.hs?.customStoreBeacons?.size ?: 0)
                    .append(",\"triggered\":").append(data.hs?.triggeredEvents?.size ?: 0)
                    .append(",\"enemyShip\":").append(q(data.enemyShip?.blueprint)).append(",\"enemyHostile\":").append(data.enemyShip?.hostile == true)
                    .append(",\"crew\":[").append(data.ship.crew.joinToString(",") { "[${q(it.name)},${it.isPlayer},${it.cloneReady},${it.roomId},${it.roomSquare}]" }).append("]}")
                game.startImportedRun(file)
                val state = game.currentState as InGameState
                out.append(",\"run\":").append(describe(state))
                // What Continue does next time: the run the import just saved, loaded again.
                val saved = BrowserFiles.getRun()
                game.loadSavedGame(BrowserXml.parse(saved))
                out.append(",\"reloaded\":").append(describe(game.currentState as InGameState))
                    .append(",\"savedNative\":").append(saved.contains("nativeImport"))
            }
            return out.append("}").toString()
        } catch (e: Throwable) {
            var cause: Throwable? = e
            val chain = StringBuilder()
            while (cause != null && chain.length < 2000) { chain.append(cause.javaClass.simpleName).append(": ").append(cause.message).append(" | "); cause = cause.cause }
            return out.append(",\"error\":").append(q(chain.toString())).append("}").toString()
        }
    }

    private fun names(list: List<String?>) = list.filterNotNull().sorted().joinToString(",", "[", "]") { q(it) }

    fun describe(game: InGameState): String {
        val ship = game.player
        val crew = ship.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === ship }.sortedBy { it.info.name }.joinToString(",") { c ->
            val skills = xyz.znix.xftl.crew.Skill.entries.joinToString(",") { s -> ((c.info.skills[s] ?: 0f) * 1000).toInt().toString() }
            "{\"name\":${q(c.info.name)},\"race\":${q(c.info.race.name)},\"health\":${c.health},\"room\":${c.room.id},\"skills\":[$skills]}"
        }
        val systems = ship.systems.sortedBy { it.codename }.joinToString(",") { "{\"name\":${q(it.blueprint.name)},\"level\":${it.energyLevels},\"damage\":${it.damagedEnergyLevels}}" }
        val weapons = ship.hardpoints.mapNotNull { it.weapon?.type?.name }.joinToString(",") { q(it) }
        val drones = ship.drones?.drones?.mapNotNull { it?.type?.name }?.joinToString(",") { q(it) } ?: ""
        val augments = ship.augments.map { it.name }.sorted().joinToString(",") { q(it) }
        val cargo = ship.cargoBlueprints.mapNotNull { it?.name }.joinToString(",") { q(it) }
        val beacon = game.currentBeacon
        val sector = beacon.sector
        val hs = if (xyz.znix.xftl.hs.HS.modded) ",\"playerVariables\":${xyz.znix.xftl.hs.events.HsEvents.playerVariables.count { it.value != 0 }},\"seed\":${xyz.znix.xftl.hs.sector.HsSeeds.currentSeed}" else ""
        return "{\"ship\":${q(ship.type.name)},\"name\":${q(ship.displayName)},\"hull\":${ship.health},\"fuel\":${ship.fuelCount},\"missiles\":${ship.missilesCount}," +
            "\"droneParts\":${ship.dronesCount},\"scrap\":${ship.scrap},\"difficulty\":${q(game.difficulty.name)},\"ae\":${game.content.enableAdvancedEdition}," +
            "\"sector\":${sector.sectorNumber + 1},\"sectorType\":${q(sector.type.name)},\"beacons\":${sector.beacons.size},\"beacon\":${sector.beacons.indexOf(beacon)}," +
            "\"visited\":${sector.beacons.count { it.visited }},\"crew\":[$crew],\"systems\":[$systems],\"weapons\":[$weapons],\"drones\":[$drones]," +
            "\"augments\":[$augments],\"cargo\":[$cargo],\"breaches\":${ship.rooms.sumOf { r -> r.breaches.count { it != null } }},\"enemy\":${q(game.enemy?.type?.name)},\"enemyHostile\":${game.enemy != null && game.isEnemyHostile}$hs}"
    }
}
