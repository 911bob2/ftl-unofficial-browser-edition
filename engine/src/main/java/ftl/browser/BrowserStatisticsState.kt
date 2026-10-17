package ftl.browser

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Constants
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.sys.*

/** Independent score/ship and statistics/achievement panels from the original. */
class BrowserStatisticsState(private val main: MainGame, private val previous: InGameState?) : MainGame.GameState() {
    private val resources = ResourceContext()
    private val content = main.gameContent
    // Scores remain visible for AE-only ships even when the next new run is
    // configured for vanilla. This metadata does not change gameplay content.
    private val blueprints = if (content.enableAdvancedEdition) content.blueprintManager else BlueprintManager(content.datafile, true)
    private val nativeFonts = HashMap<Int, NativeFonts.NativeFont>()
    private fun nf(id: Int) = nativeFonts.getOrPut(id) { NativeFonts.font(content::getFont, id) }
    private val white = Colour(1f, 1f, 1f)
    private val profile = main.profile
    private val run = previous?.browserProgress?.snapshot()
    private var shipBest = false
    private var achievements = false
    private var familyIndex = 0
    private var layoutIndex = 0
    private var shipList = false
    private var mouseX = 0
    private var mouseY = 0
    private val muted = Colour(.58f, .60f, .57f)
    // The scorekeeper's highlight colour (Victory, Advanced, New! and records set this run).
    private val highlightText = Colour(255, 255, 50)
    private val families = content.shipFamilies.families.filter { family -> family.ships.any { profile.isShipUnlocked(it) } }
    private val currentFamily get() = families[familyIndex]
    private val ship get() = currentFamily.ships[layoutIndex]
    val diagnosticTab get() = if (achievements) "ACHIEVEMENTS" else "STATISTICS"
    val diagnosticScoresTab get() = if (shipBest) "SHIP_BEST" else "TOP_SCORES"
    val diagnosticShip get() = ship
    val diagnosticShipList get() = shipList
    private data class HitAchievement(val achievement: Achievement, val x: Int, val y: Int, val size: Int)
    private val achievementHits = ArrayList<HitAchievement>()

    override fun shutdown() { resources.freeAll(); listHost?.shutdown() }
    override fun update(container: GameContainer, delta: Float) {
        mouseX = container.input.mouseX; mouseY = container.input.mouseY
        if (shipList) listHost?.let { it.mousePos.x = mouseX; it.mousePos.y = mouseY; nativeList?.update() }
        (content.sounds as? RealSoundManager)?.let { it.updateLoopedSounds(true); it.updateMusic(delta) }
    }
    private fun img(path: String) = content.getImg("img/$path")
    private fun label(id: String) = content.translator[id]
    override fun render(container: GameContainer, g: Graphics) {
        if (previous != null) {
            // ScoreKeeper::OnRender (0x0044ab10) opened from the game over screen: the game stays behind, dimmed
            // as that screen left it, without the game over box.
            val gameOver = previous.shipUI.gameOverWindow
            gameOver?.hiddenBehindScores = true
            previous.renderingDeltaTime = 0f
            previous.render(container, g)
            gameOver?.hiddenBehindScores = false
            g.tooltip = null
        } else {
            img("main_menus/main_base2.png").draw(0, 0)
            g.colour = Colour(0f, 0f, 0f, .85f); g.fillRect(0, 0, 1280, 720)
        }
        img("scoreUI/score_main_left_${if (shipBest) 2 else 1}.png").draw(0, 0)
        img("scoreUI/score_main_right_${if (achievements) 2 else 1}.png").draw(645, 0)
        tab("topscores", 43, 302, !shipBest)
        tab("shipbest", 338, 291, shipBest)
        tab("stats", 651, 193, !achievements)
        tab("achieve", 838, 387, achievements)
        drawTabLabels()
        achievementHits.clear(); drawScores(g)
        if (achievements) drawAchievements(g) else drawStatistics()
        // ScoreKeeper::OnInit (0x0044eb10): CLOSE is a TextButton at (1086, 627), 138 x 46, corners 8, font 64,
        // in score_close_box 15 px up and left.
        img("scoreUI/score_close_box.png").draw(1071, 612)
        val closeHovered = !shipList && mouseX in 1086 until 1086 + 138 && mouseY in 627 until 627 + 46
        g.colour = if (closeHovered) Constants.TEXT_BUTTON_HOVER else Constants.TEXT_BUTTON_NORMAL
        Buttons.drawChamfer(g, 1086, 627, 138, 46, 8)
        nf(64).printButtonLabel(1086, 627, 138, 46, label("button_close"), Constants.TEXT_BUTTON_TEXT)
        if (shipList) nativeList?.draw(container, g)
        drawAchievementTooltip(g)
    }
    /**
     * The selected tab is part of score_main_left/right; the other one is a button: its _on art, _select2 while
     * hovered.
     */
    private fun tab(asset: String, x: Int, width: Int, selected: Boolean) {
        if (selected) return
        // Under the open ship list the tabs keep their plain art.
        val hovered = !shipList && mouseX in x until x + width && mouseY in 38 until 91
        img("scoreUI/tab_${asset}_${if (hovered) "select2" else "on"}.png").draw(x, 38)
    }

    /**
     * ScoreKeeper::OnRender (0x0044ab10): the four tab titles share the largest of fonts 64, 63, 62 that keeps
     * each inside its tab (254, 218, 134, 324 px), centred on 188, 477, 753 and 1023 with their top at 46, 4 px
     * lower per smaller font (3 more if STATS still overflows).
     */
    private fun drawTabLabels() {
        val labels = listOf("stats_topscores", "stats_shipbest", "stats_stats", "stats_achievements").map(::label)
        val limits = listOf(254f, 218f, 134f, 324f)
        var offset = 0
        var id = 64
        while (id > 62 && labels.indices.any { nf(id).measure(labels[it]) > limits[it] }) { id--; offset += 4 }
        if (nf(id).measure(labels[2]) > limits[2]) offset += 3
        for ((i, x) in listOf(188f, 477f, 753f, 1023f).withIndex())
            nf(id).printCenter(x, 46f + offset, labels[i], Constants.TEXT_BUTTON_TEXT)
    }

    /**
     * 0x0044ab10's records: headings in font 24 at x 695, rows in font 13 at x 700 with the values centred on 1165.
     * The session records start at 150, 20 apart, under their heading at 100; the crew heading is 30 below the
     * last one and its rows 75 below that row, 21 apart; the totals heading is 26 below the last crew row and its
     * four rows 76 below it, 20 apart, then games and victories (or the final score) at +176 and +196.
     */
    private fun drawStatistics() {
        val heading = nf(24)
        heading.print(695f, 100f, label("records_heading"), white)
        val bests = listOf("defeated_stat" to profile.browserMostShips, "beacons_stat" to profile.browserMostBeacons,
            "scrap_stat" to profile.browserMostScrap, "crew_stat" to profile.browserMostCrew)
        // From the game over screen a session record row is highlighted when this run's value equals it.
        val session = run?.let { listOf(it.ships, it.beacons, it.scrap, it.crew) }
        for ((i, pair) in bests.withIndex())
            row(150f + i * 20, label("most_${pair.first}"), pair.second.toLong(), if (session?.get(i) == pair.second) highlightText else white)
        heading.print(695f, 240f, label("crew_heading"), white)
        val crewLabels = mapOf(CrewRecordKind.REPAIRS to "repairs", CrewRecordKind.KILLS to "kills",
            CrewRecordKind.EVASIONS to "evasions", CrewRecordKind.JUMPS to "jumps", CrewRecordKind.SKILLS to "masteries")
        val body = nf(13)
        for ((i, kind) in CrewRecordKind.entries.withIndex()) {
            val y = 285f + i * 21
            // A crew record beaten this run is highlighted from the game over screen.
            val colour = if (run != null && profile.isNewCrewRecord(kind)) highlightText else white
            body.print(700f, y, label("most_crew_${crewLabels.getValue(kind)}"), colour)
            val record = profile.crewRecord(kind)
            if (record != null) {
                // The holder's name centred on 1065, the portrait 30 px left of it.
                body.printCenter(1065f, y, record.name, colour)
                // Female humans share the human portrait frame coordinates.
                // There is no female_portrait animation in the original DAT.
                val animation = content.animations["${record.species}_portrait"]
                val sheetPath = if (record.female) "img/people/female_base.png" else animation.sheet.sheetPath
                val portrait = animation.sheet.getSprite(content.getImg(sheetPath), animation.x, animation.y)
                portrait.draw(1065f - body.measure(record.name) / 2f - 30f - 11f, y + 8f - 11f, 22f, 22f)
            }
            body.printCenter(1165f, y, (record?.value ?: 0).toString(), colour)
        }
        heading.print(695f, 395f, label(if (run != null) "stats_heading" else "total_heading"), white)
        val values = if (run != null) listOf(run.ships.toLong(), run.beacons.toLong(), run.scrap.toLong(), run.crew.toLong())
            else listOf(profile.browserLifetimeShips, profile.browserLifetimeBeacons, profile.browserLifetimeScrap, profile.browserLifetimeCrew)
        for ((i, key) in listOf("defeated_stat", "beacons_stat", "scrap_stat", "crew_stat").withIndex())
            row(445f + i * 20, label(if (run != null) key else "total_$key"), values[i])
        if (run == null) {
            row(545f, label("total_games"), profile.browserGamesPlayed.toLong())
            row(565f, label("total_victories"), profile.browserVictories.toLong())
        } else {
            row(545f, label("final_score"), run.score.toLong())
            if (!run.complete || !run.crewComplete) body.print(700f, 565f, "Earlier history unavailable", muted)
        }
    }
    private fun row(y: Float, name: String, value: Long, colour: Colour = white) {
        nf(13).print(700f, y, name, colour); nf(13).printCenter(1165f, y, value.toString(), colour)
    }
    private fun drawScores(g: Graphics) {
        if (shipBest) {
            val blueprint = blueprints.getShip(ship)
            val title = blueprint.shipClass?.let { content.translator[it] } ?: ship
            // 0x0044ab10: the ship's class in font 24, centred on 217 with its top at 91.
            nf(24).printCenter(217f, 91f, title, white)
            drawShipBestControls(g)
            // ScoreKeeper's ship achievements (0x004497a0): 64 px boxes from (410, 118), 70 apart.
            for ((i, id) in currentFamily.achievements.withIndex()) drawShipAchievement(g, content.achievements[id], 410 + i * 70, 118)
        }
        val scores = profile.browserHighScores(if (shipBest) ship else null)
        // ScoreKeeper::RenderTopScores (0x00442a50) from (112, 88), or 116 lower for a ship's best, 116 per row:
        // the rank in font 53 at (61, +45), the ship's miniship at the origin, its name in font 24 centred on 462
        // at the top, SCORE / SECTOR / difficulty in font 14 centred on 462 at +38, +62 and +85, and Victory (at
        // 317) and Advanced (at 542) in font 12 at +97, highlighted. An empty list draws nothing.
        // New! in font 12 at (312, +48) on the row the last submitted score took (ScoreKeeper +0x2c0; on a ship's
        // page +0x2c4, when that page shows the ship the score was for).
        val newRow = if (!shipBest) profile.browserNewTopIndex
            else if (profile.browserNewScoreShip == ship) profile.browserNewShipIndex else -1
        for ((i, score) in scores.withIndex()) {
            if (i == newRow) nf(12).print(312f, (if (shipBest) 204 else 88) + i * 116 + 48f, label("new_score"), highlightText)
            val y = (if (shipBest) 204 else 88) + i * 116
            nf(53).print(61f, y + 45f, label("stats_ranking").replace("\\1", (i + 1).toString()), white)
            val blueprint = blueprints.getShip(score.ship)
            val mini = "img/customizeUI/miniship_${blueprint.img}.png"
            if (content.datafile.getOrNull(mini) != null) content.getImg(mini).draw(112f, y.toFloat())
            nf(24).printCenter(462f, y + 1f, score.title, white)
            nf(14).printCenter(462f, y + 38f, content.translator["scorelist_score"].replace("\\1", score.score.toString()), white)
            nf(14).printCenter(462f, y + 62f, content.translator["scorelist_sector"].replace("\\1", score.sector.toString()), white)
            nf(14).printCenter(462f, y + 85f, label(score.difficulty.name.lowercase()), white)
            if (score.outcome == GameOverWindow.Outcome.WIN) nf(12).print(317f, y + 97f, label("victory"), highlightText)
            if (score.advanced) nf(12).print(542f, y + 97f, label("advanced"), highlightText)
        }
    }
    private val listLabel get() = label("ship_list_button")
    // ScoreKeeper::OnInit (0x0044eb10): LIST is a font 62 TextButton, 28 high with corners 3, auto-sized with a
    // margin of 3 about its centre at 141; the arrows (button_arrow, 32 x 28, the right one mirrored) sit 37 px
    // left of it and 5 px right of it, at y 157.
    private val listWidth get() = nf(62).measure(listLabel) + 6
    private val listX get() = 141 - listWidth / 2
    private val canCycle get() = families.size > 1
    private enum class ShipBestControl { PREVIOUS, LIST, NEXT, A, B, C }
    private fun shipBestControlAt(x: Int, y: Int): ShipBestControl? {
        if (y in 157 until 185) {
            if (x in listX - 37 until listX - 5) return ShipBestControl.PREVIOUS
            if (x in listX until listX + listWidth) return ShipBestControl.LIST
            if (x in listX + listWidth + 5 until listX + listWidth + 37) return ShipBestControl.NEXT
        }
        // The layout buttons: font 63, 36 x 34 with corners 7, from (250, 155), 45 apart.
        if (y in 155 until 189) for (i in currentFamily.ships.indices)
            if (x in 250 + i * 45 until 286 + i * 45) return ShipBestControl.entries[3 + i]
        return null
    }
    private fun drawShipBestControls(g: Graphics) {
        val hovered = if (shipList) null else shipBestControlAt(mouseX, mouseY)
        fun arrow(x: Int, mirrored: Boolean, control: ShipBestControl) {
            val state = if (!canCycle) "off" else if (hovered == control) "select2" else "on"
            val art = img("customizeUI/button_arrow_$state.png")
            if (mirrored) { g.pushTransform(); g.translate((x + art.width).toFloat(), 157f); g.scale(-1f, 1f); art.draw(0, 0); g.popTransform() }
            else art.draw(x, 157)
        }
        arrow(listX - 37, false, ShipBestControl.PREVIOUS)
        arrow(listX + listWidth + 5, true, ShipBestControl.NEXT)
        g.colour = if (hovered == ShipBestControl.LIST) Constants.TEXT_BUTTON_HOVER else Constants.TEXT_BUTTON_NORMAL
        Buttons.drawChamfer(g, listX, 157, listWidth, 28, 3)
        nf(62).printButtonLabel(listX, 157, listWidth, 28, listLabel, Constants.TEXT_BUTTON_TEXT)
        for ((i, id) in currentFamily.ships.withIndex()) {
            val unlocked = profile.isShipUnlocked(id)
            g.colour = when {
                !unlocked -> Constants.TEXT_BUTTON_DISABLED
                i == layoutIndex -> Constants.TEXT_BUTTON_SELECTED
                hovered == ShipBestControl.entries[3 + i] -> Constants.TEXT_BUTTON_HOVER
                else -> Constants.TEXT_BUTTON_NORMAL
            }
            Buttons.drawChamfer(g, 250 + i * 45, 155, 36, 34, 7)
            nf(63).printButtonLabel(250 + i * 45, 155, 36, 34, label("typebutton_${'a' + i}"), Constants.TEXT_BUTTON_TEXT)
        }
    }
    /** CAchievement::OnRender (0x0042b7d0) as the hangar draws it: locked ones under a 0.75 black lock overlay. */
    private fun drawShipAchievement(g: Graphics, achievement: Achievement, x: Int, y: Int) {
        val earned = profile.getAchievement(achievement)
        img(achievement.img.removePrefix("img/")).draw(x, y)
        if (earned == null) {
            g.colour = Colour(0f, 0f, 0f, 0.75f); g.fillRect(x, y, 64, 64)
            img("customizeUI/box_lock_on.png").draw(x + 24, y + 21)
        }
        // ScoreKeeper's MouseMove (0x0044d9d0) hovers strictly inside the box.
        val over = !shipList && mouseX > x && mouseX < x + 64 && mouseY > y && mouseY < y + 64
        g.colour = if (over) Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT else Constants.ACHIEVEMENT_OUTLINE
        g.drawRect(x, y, 63, 63); g.drawRect(x + 1, y + 1, 61, 61)
        achievementHits += HitAchievement(achievement, x, y, 64)
    }
    /**
     * 0x00449bb0 lays the achievements out in three rows of seven from (704, 163), 70 apart and 170 rows apart,
     * each drawn like the ship's; 0x0044ab10 heads them in font 24 at (705, 100), (705, 260) and (705, 430).
     */
    private fun drawAchievements(g: Graphics) {
        val rows = listOf(
            listOf("ACH_SECTOR_5", "ACH_SECTOR_8", "ACH_WIN_EASY", "ACH_WIN_NORMAL", "ACH_UNLOCK_ALL", "ACH_SCRAP", "ACH_SHIPS"),
            listOf("ACH_PACIFIST", "ACH_NO_UPGRADES", "ACH_NO_REPAIR", "ACH_NO_MISSILES", "ACH_NO_DRONES", "ACH_NO_BUYING", "ACH_NO_DEATH"),
            listOf("ACH_BURNING", "ACH_BAD_DODGING", "ACH_BOARDING_DRONE", "ACH_ONE_VOLLEY", "ACH_INVADE_SHIP", "ACH_SLICE_DICE", "ACH_SUFFOCATE"))
        for ((i, heading) in listOf("progression_heading", "distance_heading", "skill_heading").withIndex())
            nf(24).print(705f, listOf(100f, 260f, 430f)[i], label(heading), white)
        for ((row, ids) in rows.withIndex()) for ((i, id) in ids.withIndex())
            drawShipAchievement(g, content.achievements[id], 704 + i * 70, 163 + row * 170)
    }
    private val tooltipTitleFont = SILFontLoader(resources, content.datafile, content.datafile["fonts/JustinFont12Bold.font"])
    private val tooltipFont = SILFontLoader(resources, content.datafile, content.datafile["fonts/JustinFont11Bold.font"])
    /** CAchievement's tooltip: the standard mouse tooltip with its name as the title, as in the hangar. */
    private fun drawAchievementTooltip(g: Graphics) {
        if (shipList) return
        val hit = achievementHits.firstOrNull { mouseX > it.x && mouseX < it.x + it.size && mouseY > it.y && mouseY < it.y + it.size } ?: return
        val earned = profile.getAchievement(hit.achievement)
        val body = content.translator[hit.achievement.desc] +
            (earned?.let { "\n\n" + content.translator["ach_" + it.difficulty.name.lowercase()] } ?: "")
        NativeTooltip.draw(g, tooltipTitleFont, tooltipFont, mouseX, mouseY, content.translator[hit.achievement.name], body)
    }
    // ScoreKeeper's LIST opens the hangar's ShipSelect (ShipSelect::Open 0x00529940, from 0x0044dce0), tutorial
    // included; picking a ship shows its page.
    private var listHost: xyz.znix.xftl.hangar.SelectShipState? = null
    private var nativeList: xyz.znix.xftl.hangar.ShipList? = null
    private fun openShipList() {
        val host = listHost ?: xyz.znix.xftl.hangar.SelectShipState(content.datafile, main).also { listHost = it }
        val list = nativeList ?: xyz.znix.xftl.hangar.ShipList(host) { picked ->
            shipList = false
            if (picked != null) {
                val family = families.indexOfFirst { picked.name in it.ships }
                if (family >= 0) { familyIndex = family; layoutIndex = families[family].ships.indexOf(picked.name) }
            }
        }.also { nativeList = it }
        shipList = true
        list.open()
    }
    private fun selectFamily(index: Int) {
        familyIndex = (index + families.size) % families.size
        layoutIndex = currentFamily.ships.indexOfFirst { profile.isShipUnlocked(it) }.coerceAtLeast(0)
    }
    fun close() { if (previous != null) main.setCurrentState(previous) else main.switchToMainMenu() }
    override fun keyPressed(key: Int, c: Char) {
        when (key) {
            Input.KEY_ESCAPE -> if (shipList) shipList = false else close()
            Input.KEY_LEFT -> if (shipBest && !shipList) selectFamily(familyIndex - 1)
            Input.KEY_RIGHT -> if (shipBest && !shipList) selectFamily(familyIndex + 1)
        }
        if (shipList && key != Input.KEY_ESCAPE) nativeList?.keyPressed(c)
        when (key) {
        }
    }
    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON) return
        if (shipList) {
            listHost?.let { it.mousePos.x = x; it.mousePos.y = y }
            nativeList?.mouseClicked(button, x, y)
            return
        }
        if (y in 38 until 91) {
            when (x) { in 43 until 338 -> shipBest = false; in 338 until 629 -> shipBest = true;
                in 651 until 838 -> achievements = false; in 838 until 1225 -> achievements = true }
        }
        if (y in 627 until 673 && x in 1086 until 1224) close()
        if (shipBest) when (val control = shipBestControlAt(x, y)) {
            ShipBestControl.PREVIOUS -> if (canCycle) selectFamily(familyIndex - 1)
            ShipBestControl.NEXT -> if (canCycle) selectFamily(familyIndex + 1)
            ShipBestControl.LIST -> openShipList()
            null -> {}
            else -> {
                val layout = control.ordinal - 3
                if (profile.isShipUnlocked(currentFamily.ships[layout])) layoutIndex = layout
            }
        }
    }
}
