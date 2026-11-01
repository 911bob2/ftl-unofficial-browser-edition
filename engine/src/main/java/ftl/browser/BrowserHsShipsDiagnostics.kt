package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.CustomShipDefinition
import xyz.znix.xftl.hs.ship.HsShips
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * ships workstream checks (`BrowserEngine.hsShipsProbe`): the parsed Hyperspace ship definitions as JSON (compared by
 * tests/hs-ships.mjs with its own reading of the merged XML), and run starts of player ships with what they start
 * with (crew, weapons, drones, augments incl. hidden ones, resources), a save/load round trip and a rendered frame.
 */
object BrowserHsShipsDiagnostics {
    private val q = BrowserDebug::quote

    private fun defJson(d: CustomShipDefinition): String {
        val sb = StringBuilder()
        sb.append("{\"name\":").append(q(d.name))
        sb.append(",\"hiddenAugs\":{").append(d.hiddenAugs.entries.joinToString(",") { "${q(it.key)}:${it.value}" }).append('}')
        sb.append(",\"crewList\":[").append(d.crewList.joinToString(",") { "[${q(it.species)},${it.roomId},${q(it.name)},${it.isList}]" }).append(']')
        sb.append(",\"noJump\":${d.noJump},\"noFuelStalemate\":${d.noFuelStalemate},\"hpCap\":${d.hpCap}")
        sb.append(",\"startingFuel\":${d.startingFuel},\"startingScrap\":${d.startingScrap},\"augSlots\":${d.augSlots},\"cargoSlots\":${d.cargoSlots}")
        sb.append(",\"forceAutomated\":${d.forceAutomated},\"crewLimit\":${d.crewLimit},\"systemLimit\":${d.systemLimit},\"subsystemLimit\":${d.subsystemLimit}")
        sb.append(",\"reactorPrices\":${d.reactorPrices},\"reactorPriceIncrement\":${d.reactorPriceIncrement},\"maxReactorLevel\":${d.maxReactorLevel}")
        sb.append(",\"shipGenerator\":").append(q(d.shipGenerator))
        sb.append(",\"shipIcons\":[").append(d.shipIcons.joinToString(",") { q(it) }).append(']')
        sb.append(",\"rooms\":{").append(d.roomDefs.entries.sortedBy { it.key }.joinToString(",") { (id, r) ->
            "\"$id\":[${r.sensorBlind},${r.hullDamageResistChance},${r.sysDamageResistChance},${r.ionDamageResistChance},${r.roomAnims.size}]"
        }).append('}')
        sb.append(",\"artilleryRoomImages\":").append(d.artilleryRoomImages.size)
        sb.append(",\"exclusivity\":").append(d.exclusivityGroups.size)
        sb.append('}')
        return sb.toString()
    }

    /** `{buttons:[...], order:[...], hideFirstPage, default, defs:{name: def}}`. */
    fun definitions(): String {
        check(HS.active) { "Hyperspace is not active" }
        check(HsShips.built) { "Hyperspace ships were not built" }
        val buttons = HsShips.buttons.joinToString(",") { b ->
            "{\"name\":${q(b.name)},\"t\":[${b.typeA},${b.typeB},${b.typeC}],\"secret\":${b.secretCruiser},\"noAppend\":${b.noAppend}," +
                "\"splitUnlock\":${b.splitUnlockQuestAchievement},\"splitVictory\":${b.splitVictoryAchievement},\"showAch\":${b.showShipAchievements}," +
                "\"arrows\":${b.unlockArrows.size},\"achievements\":[${b.shipAchievements.joinToString(",") { it.size.toString() }}]," +
                "\"unlocks\":${HsShips.unlockNodes[b.name]?.size ?: 0}}"
        }
        val defs = HsShips.definitions().entries.sortedBy { it.key }.joinToString(",") { "${q(it.key)}:${defJson(it.value)}" }
        return "{\"buttons\":[$buttons],\"order\":[${HsShips.customShipOrder.joinToString(",") { q(it) }}]," +
            "\"hideFirstPage\":${HsShips.hideFirstPage},\"hangarAnims\":${HsShips.hangarAnims.size},\"default\":${defJson(HsShips.defaultDef)},\"defs\":{$defs}}"
    }

    /** Every `PLAYER_SHIP_*` blueprint and every hangar layout (sorted). */
    fun playerShips(main: MainGame): String = main.gameContent.blueprintManager.blueprints.values
        .filterIsInstance<LazyShipBlueprint>().map { it.name }.filter { it.startsWith("PLAYER_SHIP_") || xyz.znix.xftl.hs.ship.HsHangar.variantOf(it) != null }.sorted().joinToString("\n")

    /** Start [ship] at [difficulty], check a save round trip and a frame; JSON of what it started with. */
    fun start(main: MainGame, ship: String, difficulty: Int, container: GameContainer, graphics: Graphics): String {
        val rng = BrowserRandom.snapshot()
        try {
            main.startDiagnosticGame(ship, Difficulty.values()[difficulty])
            main.update(container, 0f)
            main.render(container, graphics)
            val state = main.currentState as InGameState
            val p = state.player
            val crew = p.crew.filter { it.mode == xyz.znix.xftl.crew.AbstractCrew.SlotType.CREW }
                .map { "[${q((it as? xyz.znix.xftl.crew.LivingCrew)?.info?.race?.name ?: "drone")},${it.room.id}]" }
            val weapons = p.hardpoints.map { it.weapon?.type?.name }
            val drones = p.drones?.drones?.map { it?.type?.name } ?: emptyList()
            val json = "{\"ship\":${q(ship)},\"difficulty\":$difficulty,\"crew\":[${crew.joinToString(",")}]," +
                "\"weapons\":[${weapons.joinToString(",") { it?.let(q) ?: "null" }}],\"drones\":[${drones.joinToString(",") { it?.let(q) ?: "null" }}]," +
                "\"augments\":[${p.augments.joinToString(",") { q(it.name) }}],\"hidden\":{${p.hs?.hiddenAugs?.entries?.joinToString(",") { "${q(it.key)}:${it.value}" } ?: ""}}," +
                "\"systems\":[${p.systems.joinToString(",") { "[${q(it.codename)},${it.energyLevels}]" }}],\"reactor\":${p.purchasedReactorPower}," +
                "\"hull\":${p.health},\"scrap\":${p.scrap},\"fuel\":${p.fuelCount},\"missiles\":${p.missilesCount},\"droneParts\":${p.dronesCount}," +
                "\"automated\":${p.isAutomated},\"crewFull\":${state.isPlayerCrewFull},\"crewLimit\":${if (HS.active) HsShips.def(ship).crewLimit else 8}}"
            // Save/load round trip keeps the hidden augments and the crew.
            val xml = BrowserXml.write(state.saveGameState())
            main.loadSavedGame(BrowserXml.parse(xml))
            val after = (main.currentState as InGameState).player
            check(after.crew.size == p.crew.size) { "crew count changed over save/load" }
            check(after.hs?.hiddenAugs == p.hs?.hiddenAugs) { "hidden augments changed over save/load" }
            check(after.hardpoints.map { it.weapon?.type?.name } == weapons) { "weapons changed over save/load" }
            main.update(container, 0f)
            main.render(container, graphics)
            return json
        } finally {
            BrowserRandom.restore(rng)
        }
    }

    fun run(main: MainGame, what: String, container: GameContainer, graphics: Graphics): String {
        val parts = what.split(' ')
        return when (parts[0]) {
            "defs" -> definitions()
            "ships" -> playerShips(main)
            "start" -> start(main, parts[1], parts.getOrNull(2)?.toInt() ?: 1, container, graphics)
            "menu" -> { main.switchToMainMenu(); "ok" }
            "hangar" -> BrowserHsHangarDiagnostics.run(main, container, graphics, parts[1], parts[2], parts[3].toInt())
            "store" -> BrowserHsStoreDiagnostics.run(main, container, graphics, parts[1], parts[2].toInt(), parts.drop(3))
            "ach" -> BrowserHsAchievementDiagnostics.run(main, container, graphics, parts[1], parts[2], parts[3].toInt(), parts[4])
            "enemies" -> BrowserHsEnemyDiagnostics.run(main, container, graphics, xyz.znix.xftl.game.Difficulty.values()[parts[1].toInt()],
                parts.getOrNull(2)?.toInt() ?: 0, parts.getOrNull(3)?.toInt() ?: Int.MAX_VALUE)
            "enemyCount" -> BrowserHsEnemyDiagnostics.enemyBlueprints(main).size.toString()
            "boss" -> BrowserHsBossDiagnostics.run(main, container, graphics, xyz.znix.xftl.game.Difficulty.values()[parts[1].toInt()])
            else -> error("unknown hsShipsProbe command '$what'")
        }
    }
}

/**
 * The Hyperspace hangar with a blank profile: order, cycling, previews of every unlocked layout, the ship list pages,
 * a sector unlock that survives a profile save/load, and a run started from the hangar. Restores the profile.
 */
object BrowserHsHangarDiagnostics {
    private val q = BrowserDebug::quote

    /** A blank profile whose Hyperspace block has no ship unlocks (HsSave writes the session's current state). */
    private fun blankProfile(): org.jdom2.Document {
        val doc = xyz.znix.xftl.game.SaveProfile.createBlank().snapshot()
        doc.rootElement.getChild(xyz.znix.xftl.hs.save.HsSave.ELEMENT)?.let { hs ->
            hs.getChildren("data").filter { it.getAttributeValue("key") == xyz.znix.xftl.hs.ship.HsShipUnlocks.SAVE_KEY }.forEach { hs.removeContent(it) }
        }
        return doc
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, unlockShip: String, unlockBy: String, sector: Int): String {
        check(HS.active) { "Hyperspace is not active" }
        val storedProfile = BrowserFiles.getProfile(); val storedRun = BrowserFiles.getRun()
        val profileXml = BrowserXml.write(main.profile.snapshot())
        val rng = BrowserRandom.snapshot()
        var stage = "open"
        try {
            main.profile.restore(blankProfile())
            val order = xyz.znix.xftl.hs.ship.HsHangar.order().filter { it != "empty" }
            main.changeHangarMode(true, order.first(), Difficulty.NORMAL)
            val hangar = main.currentState as xyz.znix.xftl.hangar.SelectShipState
            main.render(container, graphics)
            val first = hangar.selectedShipId
            // Cycle right through every unlocked A layout and back to the start.
            val cycle = ArrayList<String>()
            for (i in 0 until order.size + 1) {
                stage = "cycle after " + hangar.selectedShipId
                hangar.selectNextShip(1)
                main.render(container, graphics)
                cycle += hangar.selectedShipId
                if (hangar.selectedShipId == first) break
            }
            // Preview every unlocked layout.
            val previews = ArrayList<String>()
            for (ship in hangar.ships) {
                if (!hangar.isShipUnlocked(ship.name)) continue
                stage = "preview " + ship.name
                hangar.restoreSelection(ship.name, Difficulty.NORMAL)
                main.render(container, graphics)
                check(hangar.selectedShipId == ship.name) { "could not select ${ship.name}" }
                check(hangar.hangarPresentation.crew.size == hangar.blueprint.initialCrew.sumOf { it.amount }) { "hangar crew of ${ship.name}" }
                previews += ship.name
            }
            // Ship list pages: open on the selected ship's page, then right through every page.
            hangar.restoreSelection(first, Difficulty.NORMAL)
            hangar.openShipList()
            // A blank profile shows the list tutorial first (two pages); its choice key closes it.
            var guard = 4
            while (hangar.isShipListTutorialOpen && guard-- > 0) {
                main.render(container, graphics)
                hangar.keyPressed(0, '1')
            }
            check(!hangar.isShipListTutorialOpen) { "the ship list tutorial did not close" }
            val pages = ArrayList<String>()
            repeat(xyz.znix.xftl.hs.ship.HsHangar.maxPage() + 1) {
                stage = "list page " + hangar.shipListPage
                main.render(container, graphics)
                pages += "{\"page\":${hangar.shipListPage},\"cards\":[${hangar.shipListCards.joinToString(",") { q(it) }}]}"
                hangar.mouseClicked(0, 1140, 125, 1)
            }
            hangar.mouseClicked(0, 5, 700, 1) // outside the list: close
            stage = "unlock"
            // Unlock by reaching a sector, then a profile round trip.
            val before = hangar.isShipUnlocked(unlockShip)
            main.profile.visitSector(unlockBy, sector, true, Difficulty.NORMAL)
            val after = hangar.isShipUnlocked(unlockShip)
            val saved = BrowserXml.write(main.profile.snapshot())
            main.profile.restore(blankProfile())
            val wiped = hangar.isShipUnlocked(unlockShip)
            main.profile.restore(BrowserXml.parse(saved))
            val reloaded = hangar.isShipUnlocked(unlockShip)
            // A run from the hangar's own start path.
            stage = "start " + unlockShip
            hangar.restoreSelection(unlockShip, Difficulty.NORMAL)
            hangar.startGame(Difficulty.NORMAL)
            val started = (main.currentState as? InGameState)?.player?.type?.name
            main.update(container, 0f); main.render(container, graphics)
            return "{\"first\":${q(first)},\"cycle\":[${cycle.joinToString(",") { q(it) }}],\"previews\":[${previews.joinToString(",") { q(it) }}]," +
                "\"pages\":[${pages.joinToString(",")}],\"unlock\":{\"before\":$before,\"after\":$after,\"wiped\":$wiped,\"reloaded\":$reloaded," +
                "\"saved\":${saved.contains("ships.unlocks")}},\"started\":${started?.let(q) ?: "null"}}"
        } catch (e: Throwable) {
            throw IllegalStateException("hangar probe failed at " + stage + ": " + e, e)
        } finally {
            main.profile.restore(BrowserXml.parse(profileXml))
            BrowserFiles.setProfile(storedProfile); BrowserFiles.setRun(storedRun)
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
    }
}
