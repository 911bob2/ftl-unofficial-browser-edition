package ftl.browser

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Constants
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.sys.*

/** Independent score/ship and statistics/achievement panels from the original. */
class BrowserStatisticsState(private val main: MainGame, private val previous: InGameState?) : MainGame.GameState(),
    xyz.znix.xftl.game.TouchRouter.LiftTargets {
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
    // Hyperspace (CustomScoreKeeper.cpp): the ship best panel walks the HS hangar entries (vanilla first unless the
    // first page is hidden); a layout counts when its variant exists and is unlocked.
    private val hs = xyz.znix.xftl.hs.HS.active
    private fun available(id: String) = profile.isShipUnlocked(id) &&
        (!hs || (xyz.znix.xftl.hs.ship.HsHangar.variantExists(id) && blueprints.getOrNull(id) != null))
    private val families = (if (hs) xyz.znix.xftl.hs.ship.HsHangar.familyTable(content.shipFamilies,
        xyz.znix.xftl.hangar.SelectShipState.NATIVE_ORDER) else content.shipFamilies).families
        .filter { family -> family.ships.any { available(it) } }
    private val currentFamily get() = families[familyIndex]
    private val ship get() = currentFamily.ships[layoutIndex]
    val diagnosticTab get() = if (achievements) "ACHIEVEMENTS" else "STATISTICS"
    val diagnosticScoresTab get() = if (shipBest) "SHIP_BEST" else "TOP_SCORES"
    val diagnosticShip get() = ship
    val diagnosticShipList get() = shipList
    private data class HitAchievement(val achievement: Achievement, val x: Int, val y: Int, val size: Int)
    private val achievementHits = ArrayList<HitAchievement>()

    // Hyperspace custom achievement pages (CustomScoreKeeper::currentAchievementPage; 0 = the vanilla page).
    private val hsAch = xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.ship.HsAchievements.pages.isNotEmpty()
    private var hsPage = 0
    val diagnosticAchievementPage get() = hsPage
    private data class HitCustom(val ach: xyz.znix.xftl.hs.ship.HsAchievements.CustomAchievement, val x: Int, val y: Int)
    private val customHits = ArrayList<HitCustom>()

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
            img(if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsTitleScreen.imagePath("img/main_menus/main_base2.png").removePrefix("img/") else "main_menus/main_base2.png").draw(0, 0)
            g.colour = Colour(0f, 0f, 0f, .85f); g.fillRect(0, 0, 1280, 720)
        }
        img("scoreUI/score_main_left_${if (shipBest) 2 else 1}.png").draw(0, 0)
        if (!(achievements && hsPage > 0 && drawHsPageBackground(645, 0)))
            img("scoreUI/score_main_right_${if (achievements) 2 else 1}.png").draw(645, 0)
        tab("topscores", 43, 302, !shipBest)
        tab("shipbest", 338, 291, shipBest)
        tab("stats", 651, 193, !achievements)
        tab("achieve", 838, 387, achievements)
        drawTabLabels()
        achievementHits.clear(); customHits.clear(); drawScores(g)
        if (achievements && hsPage > 0) drawHsPage(g) else if (achievements) drawAchievements(g) else drawStatistics()
        if (achievements && hsAch) drawHsPageButtons()
        // ScoreKeeper::OnInit (0x0044eb10): CLOSE is a TextButton at (1086, 627), 138 x 46, corners 8, font 64,
        // in score_close_box 15 px up and left.
        img("scoreUI/score_close_box.png").draw(1071, 612)
        val closeHovered = !shipList && mouseX in 1086 until 1086 + 138 && mouseY in 627 until 627 + 46
        g.colour = if (closeHovered) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
        Buttons.drawChamfer(g, 1086, 627, 138, 46, 8)
        nf(64).printButtonLabel(1086, 627, 138, 46, label("button_close"), xyz.znix.xftl.hs.render.HsColors.tbText())
        if (shipList) nativeList?.draw(container, g)
        drawAchievementTooltip(g)
        drawHsTooltip(g)
    }

    // ---- Hyperspace: CustomAchievementPage / Section rendering and the page buttons (CustomScoreKeeper OnRender) ----
    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomAchievements.cpp and CustomScoreKeeper.cpp.

    private fun hsImg(path: String): Image? = if (content.datafile.getOrNull("img/$path") != null) img(path) else null

    /** CustomAchievementPage::RenderBackground: score_main_right_2a with 2b behind each visible section's box. */
    private fun drawHsPageBackground(x: Int, y: Int): Boolean {
        val tex1 = hsImg("scoreUI/score_main_right_2a.png") ?: return false
        val tex2 = hsImg("scoreUI/score_main_right_2b.png")
        if (tex2 == null) { tex1.draw(x, y); return true }
        val w = tex1.width.toFloat(); val h = tex1.height.toFloat()
        fun part(t: Image, px: Float, py: Float, pw: Float, ph: Float, u0: Float, u1: Float, v0: Float, v1: Float) =
            t.draw(px, py, px + pw, py + ph, u0 * t.width, v0 * t.height, u1 * t.width, v1 * t.height)
        part(tex1, x.toFloat(), y.toFloat(), 36f, h, 0f, 36f / w, 0f, 1f)
        part(tex1, x + 564f, y.toFloat(), w - 564f, h, 564f / w, 1f, 0f, 1f)
        var yy = 100f; var y0 = 0f
        val page = xyz.znix.xftl.hs.ship.HsAchievements.pages[hsPage - 1]
        for (section in page.sections) {
            if (!section.visible()) continue
            var y1 = yy + 53f
            part(tex1, x + 36f, y0, 528f, y1 - y0, 36f / w, 564f / w, y0 / h, y1 / h)
            y0 = y1
            yy += section.height()
            y1 = yy - 23f
            part(tex2, x + 36f, y0, 528f, y1 - y0, 36f / w, 564f / w, y0 / h, y1 / h)
            y0 = y1
        }
        part(tex1, x + 36f, y0, 528f, h - y0, 36f / w, 564f / w, y0 / h, 1f)
        return true
    }

    /** CustomAchievementPage::OnRender at (645, 0): sections from y 100 down, each its heading, box and rows of seven. */
    private fun drawHsPage(g: Graphics) {
        val tracker = xyz.znix.xftl.hs.ship.HsAchievements
        val page = tracker.pages.getOrNull(hsPage - 1) ?: return
        var y = 100
        val x = 645
        for (section in page.sections) {
            if (!section.visible()) continue
            val heading = if (section.unlockedCount() < 1 && section.secretHeading != null) section.secretHeading!! else section.heading
            nf(24).print(x + 60f, y.toFloat(), heading.get(content.translator), tracker.textColour)
            val height = section.height()
            val border = hsImg("scoreUI/score_main_right_border.png")
            if (border != null) {
                val w2 = border.width / 2; val h2 = border.height / 2
                val x0 = x + 300 - w2; val y0 = y + 95 - h2; val y1 = y + height - 65 + h2
                val half = h2.toFloat()
                border.draw(x0.toFloat(), y0.toFloat(), (x0 + border.width).toFloat(), (y0 + h2).toFloat(), 0f, 0f, border.width.toFloat(), half)
                border.draw(x0.toFloat(), (y0 + h2).toFloat(), (x0 + border.width).toFloat(), (y0 + h2 + height - 160).toFloat(), 0f, half, border.width.toFloat(), border.height - half)
                border.draw(x0.toFloat(), (y1 - h2).toFloat(), (x0 + border.width).toFloat(), y1.toFloat(), 0f, border.height - half, border.width.toFloat(), border.height.toFloat())
            } else {
                g.colour = tracker.outlineColour
                for (t in 0 until 4) g.drawRect(x + 36 + t, y + 53 + t, 528 - 1 - 2 * t, height - 76 - 1 - 2 * t)
            }
            var i = -1; var j = 0
            for (ach in section.achievements) {
                if (ach.hidden && !ach.unlocked) continue
                i++
                if (i >= 7) { i = 0; j++ }
                drawCustomAchievement(g, ach, x + 59 + 70 * i, y + 63 + 70 * j)
            }
            y += height
        }
    }

    /** CAchievement::OnRender for a custom achievement: its icon, and while locked the overlay and lock image. */
    private fun drawCustomAchievement(g: Graphics, ach: xyz.znix.xftl.hs.ship.HsAchievements.CustomAchievement, x: Int, y: Int) {
        if (ach.icon.isNotEmpty()) hsImg(ach.icon)?.draw(x, y)
        if (!ach.unlocked) {
            g.colour = Colour(0f, 0f, 0f, 0.75f); g.fillRect(x, y, 64, 64)
            (if (ach.lockImage.isNotEmpty()) hsImg(ach.lockImage) else null)?.draw(x, y) ?: img("customizeUI/box_lock_on.png").draw(x + 24, y + 21)
        }
        val over = !shipList && mouseX > x && mouseX < x + 64 && mouseY > y && mouseY < y + 64
        g.colour = if (over) Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT else Constants.ACHIEVEMENT_OUTLINE
        g.drawRect(x, y, 63, 63); g.drawRect(x + 1, y + 1, 61, 61)
        customHits += HitCustom(ach, x, y)
    }

    /** The page buttons (customizeUI/button_arrow at (1152,95) and, mirrored, (1192,95)) and "page/pages" right-aligned at (1150,84). */
    private fun drawHsPageButtons() {
        val tracker = xyz.znix.xftl.hs.ship.HsAchievements
        for ((bx, mirror) in listOf(1152 to false, 1192 to true)) {
            val base = img("customizeUI/button_arrow_on.png")
            val hovered = !shipList && mouseX in bx until bx + base.width && mouseY in 95 until 95 + base.height
            val im = if (hovered) img("customizeUI/button_arrow_select2.png") else base
            if (mirror) im.draw((bx + im.width).toFloat(), 95f, bx.toFloat(), 95f + im.height, 0f, 0f, im.width.toFloat(), im.height.toFloat())
            else im.draw(bx, 95)
        }
        nf(24).printRight(1150f, 84f, "${hsPage + 1}/${tracker.pages.size + 1}", tracker.textColour)
    }

    private fun hsPageButtonAt(x: Int, y: Int): Int? {
        if (!hsAch || !achievements) return null
        val base = img("customizeUI/button_arrow_on.png")
        if (y !in 95 until 95 + base.height) return null
        return when (x) { in 1152 until 1152 + base.width -> -1; in 1192 until 1192 + base.width -> 1; else -> null }
    }

    private fun drawHsTooltip(g: Graphics) {
        if (shipList) return
        val hit = customHits.firstOrNull { mouseX > it.x && mouseX < it.x + 64 && mouseY > it.y && mouseY < it.y + 64 } ?: return
        val a = hit.ach
        val difficulty = if (a.unlocked && a.multiDifficulty) "\n\n" + content.translator["ach_" + listOf("easy", "normal", "hard").getOrElse(a.difficulty) { "easy" }] else ""
        val progress = if (!a.unlocked && a.progressMax > 0) "\n\n${a.progress}/${a.progressMax}" else ""
        NativeTooltip.draw(g, tooltipTitleFont, tooltipFont, mouseX, mouseY, a.shownName?.get(content.translator) ?: a.nameId,
            (a.shownDescription?.get(content.translator) ?: "") + progress + difficulty)
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
            val custom = if (hs) xyz.znix.xftl.hs.ship.HsHangar.variantOf(ship) else null
            if (custom != null) {
                // SetupTopShip hook: up to three of the entry's ship achievements, centred on 515.
                val list = xyz.znix.xftl.hs.ship.HsAchievements.shipAchievements(custom.first.name, custom.second, false).take(3)
                for ((i, ach) in list.withIndex()) drawCustomAchievement(g, ach, 515 - 35 * list.size + 70 * i, 118)
            } else
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
    // HS CheckTypes: the arrows work while more than one entry has the current layout unlocked.
    private val canCycle get() = if (hs) families.count { f -> f.ships.getOrNull(layoutIndex)?.let(::available) == true } > 1 else families.size > 1
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
        g.colour = if (hovered == ShipBestControl.LIST) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
        Buttons.drawChamfer(g, listX, 157, listWidth, 28, 3)
        nf(62).printButtonLabel(listX, 157, listWidth, 28, listLabel, xyz.znix.xftl.hs.render.HsColors.tbText())
        for ((i, id) in currentFamily.ships.withIndex()) {
            val unlocked = available(id)
            g.colour = when {
                !unlocked -> xyz.znix.xftl.hs.render.HsColors.tbInactive()
                i == layoutIndex -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
                hovered == ShipBestControl.entries[3 + i] -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
                else -> xyz.znix.xftl.hs.render.HsColors.tbActive()
            }
            Buttons.drawChamfer(g, 250 + i * 45, 155, 36, 34, 7)
            nf(63).printButtonLabel(250 + i * 45, 155, 36, 34, label("typebutton_${'a' + i}"), xyz.znix.xftl.hs.render.HsColors.tbText())
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
        if (hs) {
            // CycleShipNext / CycleShipPrevious: the next entry with the current layout unlocked; the layout stays.
            val step = if (index < familyIndex) -1 else 1
            var i = familyIndex
            for (n in families.indices) {
                i = (i + step + families.size) % families.size
                if (families[i].ships.getOrNull(layoutIndex)?.let(::available) == true) { familyIndex = i; return }
            }
            return
        }
        familyIndex = (index + families.size) % families.size
        layoutIndex = currentFamily.ships.indexOfFirst { profile.isShipUnlocked(it) }.coerceAtLeast(0)
    }
    fun close() { if (previous != null) main.setCurrentState(previous) else main.switchToMainMenu() }
    override fun keyPressed(key: Int, c: Char) {
        when (key) {
            Input.KEY_ESCAPE -> if (shipList) shipList = false else close()
            // HS ScoreKeeper::KeyDown swallows the arrow keys.
            Input.KEY_LEFT -> if (shipBest && !shipList && !hs) selectFamily(familyIndex - 1)
            Input.KEY_RIGHT -> if (shipBest && !shipList && !hs) selectFamily(familyIndex + 1)
        }
        if (shipList && key != Input.KEY_ESCAPE) nativeList?.keyPressed(c)
        when (key) {
        }
    }
    /** What a finger taps (TouchRouter): a tab, Back, a page arrow, a ship control, the ship list's. */
    override fun liftTarget(x: Int, y: Int): Any? {
        if (shipList) return nativeList?.liftTarget(x, y)
        if (y in 38 until 91) when (x) {
            in 43 until 338 -> return "tab-scores"
            in 338 until 629 -> return "tab-ships"
            in 651 until 838 -> return "tab-stats"
            in 838 until 1225 -> return "tab-achievements"
        }
        if (y in 627 until 673 && x in 1086 until 1224) return "close"
        hsPageButtonAt(x, y)?.let { return listOf("page", it) }
        return if (shipBest) shipBestControlAt(x, y) else null
    }

    /** ScoreKeeper::MouseClick: on the press (CApp::OnLButtonDown, MainMenu::MouseClick); the release does nothing. */
    override fun mousePressed(button: Int, x: Int, y: Int) {
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
        hsPageButtonAt(x, y)?.let { d ->
            val t = xyz.znix.xftl.hs.ship.HsAchievements
            hsPage = if (d < 0) t.prevPage(hsPage) else t.nextPage(hsPage)
        }
        if (shipBest) when (val control = shipBestControlAt(x, y)) {
            ShipBestControl.PREVIOUS -> if (canCycle) selectFamily(familyIndex - 1)
            ShipBestControl.NEXT -> if (canCycle) selectFamily(familyIndex + 1)
            ShipBestControl.LIST -> openShipList()
            null -> {}
            else -> {
                val layout = control.ordinal - 3
                if (available(currentFamily.ships[layout])) layoutIndex = layout
            }
        }
    }
}
