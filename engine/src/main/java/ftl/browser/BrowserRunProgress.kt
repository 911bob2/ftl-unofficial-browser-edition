package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.game.*

/** Run history is independent of inventory: spending and selling cannot change it. */
class BrowserRunProgress(private val game: InGameState) {
    var scrapCollected = 0
        private set
    var beaconsVisited = 0
        private set
    var shipsDefeated = 0
        private set
    var crewRecruited = 0
        private set
    var crewHistoryComplete = true
        private set
    var historyComplete = true
        private set
    var outcome: GameOverWindow.Outcome? = null
        private set
    var finished = false
        private set

    val score: Int get() = score(scrapCollected, beaconsVisited, shipsDefeated, game.difficulty)

    fun visit(sector: Int) {
        beaconsVisited++
        game.mainGame?.profile?.visitBrowserBeacon()
        if (sector >= 4) unlock("ACH_SECTOR_5")
        if (sector >= 7) unlock("ACH_SECTOR_8")
        game.browserAchievements.visit(sector)
        if (sector >= 4) game.progressionShipName()?.let {
            game.mainGame?.profile?.visitSector(it, sector, game.content.enableAdvancedEdition, game.difficulty)
        }
    }

    fun collectScrap(amount: Int) {
        if (amount <= 0) return
        scrapCollected += amount
        game.mainGame?.profile?.collectBrowserScrap(amount, game.difficulty)
    }
    fun defeatShip() {
        shipsDefeated++
        game.mainGame?.profile?.defeatBrowserShip(game.difficulty)
    }
    fun recruitCrew() {
        crewRecruited++
        game.mainGame?.profile?.recruitBrowserCrew()
    }

    private fun unlock(id: String) {
        val main = game.mainGame ?: return
        main.profile.unlockAchievement(game.content.achievements[id], game.difficulty)
    }

    fun finish(result: GameOverWindow.Outcome) {
        if (finished) return
        finished = true
        outcome = result
        if (result == GameOverWindow.Outcome.WIN) {
            unlock("ACH_WIN_EASY")
            if (game.difficulty != Difficulty.EASY) unlock("ACH_WIN_NORMAL")
            game.mainGame?.profile?.recordShipVictory(game.player.type.name, game.difficulty)
        }
        game.mainGame?.profile?.recordBrowserRun(snapshot())
    }

    /** Native scorekeeper Save(true) submits restart/hangar scores once. */
    fun finishAbandoned() {
        if (finished) return
        finished = true
        game.mainGame?.profile?.recordBrowserRun(snapshot())
        BrowserDebug.record("statistics", "run.abandoned", "{\"score\":$score}")
    }

    fun snapshot(): BrowserRunRecord {
        val blueprint = game.player.type
        val title = game.player.displayName ?: blueprint.shipTitle?.let { game.translator[it] } ?: blueprint.name
        return BrowserRunRecord(blueprint.name, title, game.difficulty, game.content.enableAdvancedEdition,
            scrapCollected, beaconsVisited, shipsDefeated, historyComplete, outcome, crewRecruited, crewHistoryComplete,
            game.currentBeacon.sector.sectorNumber + 1)
    }

    fun save(): Element = snapshot().save("browserProgress").setAttribute("finished", finished.toString())

    /** A run imported from the original's save carries its totals, so its score is the original's too. */
    fun importVanilla(ships: Int, beacons: Int, scrap: Int, crew: Int) {
        shipsDefeated = ships
        beaconsVisited = beacons
        scrapCollected = scrap
        crewRecruited = crew
        historyComplete = true
        crewHistoryComplete = true
    }

    fun load(elem: Element?) {
        if (elem == null) {
            // Previous engine builds did not record historical rewards or kills.
            // Do not fabricate them or admit an incomplete run to the high scores.
            historyComplete = false
            crewHistoryComplete = false
            return
        }
        val record = BrowserRunRecord.load(elem)
        scrapCollected = record.scrap
        beaconsVisited = record.beacons
        shipsDefeated = record.ships
        crewRecruited = record.crew
        crewHistoryComplete = record.crewComplete
        historyComplete = record.complete
        outcome = record.outcome
        finished = elem.getAttributeValue("finished") == "true" || outcome != null
    }

    companion object {
        fun score(scrap: Int, beacons: Int, ships: Int, difficulty: Difficulty): Int {
            // Integer arithmetic applies the vanilla multiplier and floors once.
            val quarters = when (difficulty) { Difficulty.EASY -> 4; Difficulty.NORMAL -> 5; Difficulty.HARD -> 6 }
            return ((scrap.toLong() + 10L * beacons + 20L * ships) * quarters / 4).toInt()
        }
    }
}

data class BrowserRunRecord(
    val ship: String, val title: String, val difficulty: Difficulty, val advanced: Boolean,
    val scrap: Int, val beacons: Int, val ships: Int, val complete: Boolean,
    val outcome: GameOverWindow.Outcome?, val crew: Int = 0, val crewComplete: Boolean = true,
    val sector: Int = 0
) {
    val score: Int get() = BrowserRunProgress.score(scrap, beacons, ships, difficulty)
    fun save(tag: String): Element = Element(tag)
        .setAttribute("ship", ship).setAttribute("title", title)
        .setAttribute("difficulty", difficulty.name).setAttribute("advanced", advanced.toString())
        .setAttribute("scrap", scrap.toString()).setAttribute("beacons", beacons.toString())
        .setAttribute("ships", ships.toString()).setAttribute("complete", complete.toString())
        .setAttribute("crew", crew.toString()).setAttribute("crewComplete", crewComplete.toString())
        .setAttribute("sector", sector.toString())
        .also { if (outcome != null) it.setAttribute("outcome", outcome.name) }

    companion object {
        fun load(elem: Element): BrowserRunRecord = BrowserRunRecord(
            elem.getAttributeValue("ship"), elem.getAttributeValue("title"),
            Difficulty.valueOf(elem.getAttributeValue("difficulty")), elem.getAttributeValue("advanced") == "true",
            elem.getAttributeValue("scrap").toInt().coerceAtLeast(0),
            elem.getAttributeValue("beacons").toInt().coerceAtLeast(0),
            elem.getAttributeValue("ships").toInt().coerceAtLeast(0), elem.getAttributeValue("complete") == "true",
            elem.getAttributeValue("outcome")?.let { GameOverWindow.Outcome.valueOf(it) },
            elem.getAttributeValue("crew")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            elem.getAttributeValue("crewComplete") == "true",
            elem.getAttributeValue("sector")?.toIntOrNull()?.coerceIn(0, 8) ?: 0)
    }
}
