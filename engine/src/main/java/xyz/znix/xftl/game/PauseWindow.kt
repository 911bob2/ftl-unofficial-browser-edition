package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.NativeFonts

class PauseWindow(val game: InGameState, val close: () -> Unit) : Window() {
    private val menuImage: Image = game.getImg(if (TouchMode.enabled) "img/ipad/optionsUI/escape_main.png" else "img/optionsUI/escape_main.png")
    private val difficultyImage: Image = game.getImg("img/optionsUI/info_difficulty.png")
    private val dlcImage: Image = game.getImg("img/optionsUI/info_dlc.png")
    private val achievementsImage: Image = game.getImg("img/optionsUI/info_achievements.png")
    private val lockIcon: Image = game.getImg("img/customizeUI/box_lock_on.png")
    private val buttonFont = NativeFonts.font(game::getFont, 62)
    private val titleFont = NativeFonts.font(game::getFont, 13)
    private val mousePos = Point(0, 0)
    private val shipFamily: ShipFamily? = game.content.shipFamilies.byShipId[game.player.type.name]
    private val achievements: List<Achievement> =
        shipFamily?.achievements?.map { game.content.achievements[it] } ?: emptyList()

    private val difficultyTitle = game.translator["menu_status_difficulty"]
    private val difficultyValue = game.translator[game.difficulty.startButtonKey]
    private val dlcTitle = game.translator["menu_status_ae_content"]
    private val dlcValue = game.translator[
        if (game.content.enableAdvancedEdition) "advanced_on_button" else "advanced_off_button"]

    // MenuScreen's constructor (0x0050f610) stacks its TextButtons 18px below the art,
    // then 51px for the first gap and 37px for the rest.
    private val entries: MutableList<Triple<String, Int, () -> Unit>> = mutableListOf<Triple<String, Int, () -> Unit>>(
        Triple(game.translator["escape_continue"], 18, ::continueClicked),
        Triple(game.translator["escape_mainmenu"], 69, ::mainMenuClicked),
        Triple(game.translator["escape_hangar"], 106, ::hangarClicked),
        Triple(game.translator["escape_reset"], 143, ::restartClicked),
        Triple(game.translator["escape_options"], 180, ::optionsClicked),
        Triple(game.translator["escape_controls"], 217, ::controlsClicked),
    ).also { if (!TouchMode.enabled) it += Triple(game.translator["escape_save"], 254, ::quitClicked) }

    // MenuScreen layout (0x0050e210): the art widens to fit the longest label plus 40,
    // the two info boxes to their text, and their combined width is rounded to tens
    // by stretching the achievements box.
    private val menuWidth = maxOf(menuImage.width, entries.maxOf { buttonFont.measure(it.first) + 40 })
    private val difficultyWidth: Int
    private val dlcWidth: Int
    private val achievementPadding: Int
    private val achievementsWidth: Int

    init {
        fun infoWidth(title: String, value: String, image: Image) =
            maxOf(buttonFont.measure(value) + 30, titleFont.measure(title) + 34, image.width)
        var diff = infoWidth(difficultyTitle, difficultyValue, difficultyImage)
        var dlc = infoWidth(dlcTitle, dlcValue, dlcImage)
        var padding = diff + 3 + dlc - achievementsImage.width
        if (padding % 10 != 0) {
            val extra = 10 - padding % 10
            padding += extra
            diff += extra shr 1
            dlc += extra - extra / 2
        }
        difficultyWidth = diff
        dlcWidth = dlc
        achievementPadding = padding
        achievementsWidth = achievementsImage.width + padding
    }

    private val tutorial = game.mainGame.isTutorialRunning

    /** Hyperspace's MenuScreen hooks: the achievements panel of a Hyperspace ship and the seed box; null in vanilla. */
    private val hs: xyz.znix.xftl.hs.render.HsPauseMenu? =
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsPauseMenu(game.player) else null

    /**
     * MenuScreen +0x2c0 (bShowControls): the CONTROLS button (command 10, MenuScreen::MouseClick 0x511d10) shows the
     * controls help instead of the menu (MenuScreen::OnRender 0x5109e0). Any key closes it again and is consumed
     * (MenuScreen::KeyDown 0x50e0f0, called first by CommandGui::KeyDown 0x534820); MouseMove (0x511ab0) is ignored
     * meanwhile, so a click only presses the still-hovered CONTROLS button again and the help stays (captures
     * vn-win4 vz-help*, HS oracle vn-menu o-help*).
     */
    var helpOpen = false
        private set
    private val helpTitleFont = NativeFonts.font(game::getFont, 63)
    private val helpTextFont = NativeFonts.font(game::getFont, 10)
    private val helpNoteFont = NativeFonts.font(game::getFont, 12)

    // Centred on x=660 with the info panel 38px to its right, on y=360. While the
    // tutorial runs, MenuScreen::OnRender (0x005109e0) shifts it 150px right and
    // hides the info panel.
    private val menuX = 0x294 - (achievementsWidth + 0x26 + menuWidth) / 2 + if (tutorial) 150 else 0
    private val menuY = 0x168 - menuImage.height / 2

    override val size: IPoint = ConstPoint(menuWidth, menuImage.height)
    override val windowCentreOffset: IPoint =
        if (TouchMode.enabled) ConstPoint(481 + (if (tutorial) 150 else 0) - (TouchMode.HUD_W - menuWidth) / 2, 420 - (TouchMode.HUD_H - menuImage.height) / 2)
        else ConstPoint(menuX - (1280 - menuWidth) / 2, menuY - (720 - menuImage.height) / 2)

    init {
        // Save transfer buttons are only offered when the platform can't
        // expose the save directory directly (Android's SAF); on desktop the
        // continue.sav sits next to the other saves anyway. Vanilla-format
        // exports are only meaningful at a sector boundary: the importing
        // engine regenerates the beacon layout, so only the sector's entry
        // beacon is a stable landing point.
        val transfer = xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.saveTransfer
        if (transfer != null && atSectorStart())
            entries += Triple("EXPORT SAVE", 291, ::exportSaveClicked)
        if (transfer != null)
            entries += Triple("IMPORT SAVE", 328, ::importSaveClicked)

        for ((label, y, cb) in entries)
            buttons += MenuButton(label, ConstPoint(17, y), cb)
    }

    /** A MenuScreen TextButton: font 62, corner inset 4, as wide as the art less 34. */
    private inner class MenuButton(val label: String, pos: ConstPoint, val cb: () -> Unit) :
        Button(game, pos, ConstPoint(menuWidth - 0x22, 24)) {
        override fun draw(g: Graphics) {
            g.colour = if (hovered) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
            Buttons.drawChamfer(g, pos.x, pos.y, size.x, size.y, 4)
            buttonFont.printButtonLabel(pos.x, pos.y, size.x, size.y, label, xyz.znix.xftl.hs.render.HsColors.tbText())
        }

        override fun click(button: Int) {
            if (button == xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON)
                cb()
        }
    }

    override fun draw(g: Graphics) {
        if (helpOpen) {
            drawHelp(g)
            return
        }
        val x = position.x
        val y = position.y
        val h = menuImage.height.f

        // The art's left 81 columns, column 81 stretched, then its right 80 columns.
        menuImage.draw(x.f, y.f, x + 81f, y + h, 0f, 0f, 81f, h)
        menuImage.draw(x + 81f, y.f, x + menuWidth - 80f, y + h, 81f, 0f, 82f, h)
        menuImage.draw(x + menuWidth - 80f, y.f, x + menuWidth.f, y + h, 82f, 0f, 162f, h)

        for (button in buttons)
            button.draw(g)

        if (!tutorial)
            drawInfoPanel(g, x + menuWidth + 0x26, y + 30)
    }

    private fun drawInfoPanel(g: Graphics, x: Int, y: Int) {
        drawInfoBox(difficultyImage, x, y, difficultyWidth)
        drawInfoBox(dlcImage, x + difficultyWidth + 3, y, dlcWidth)

        titleFont.printCenter((x + (difficultyWidth + 1) / 2).f, y + 16f, difficultyTitle, Constants.TEXT_BUTTON_NORMAL)
        titleFont.printCenter((x + difficultyWidth + 3 + (dlcWidth + 1) / 2).f, y + 16f, dlcTitle, Constants.TEXT_BUTTON_NORMAL)
        buttonFont.printCenter((x + (difficultyWidth + 1) / 2).f, y + 40f, difficultyValue, Constants.TEXT_BUTTON_TEXT)
        buttonFont.printCenter((x + difficultyWidth + 3 + (dlcWidth + 1) / 2).f, y + 40f, dlcValue, Constants.TEXT_BUTTON_TEXT)

        // MenuScreen::OnRender hook: a Hyperspace ship without the panel gets the seed box under the two boxes instead.
        if (hs != null && !hs.panel) {
            drawSeedBox(x + 66, y + 72)
            return
        }

        // The achievements box: its left 132 columns, 10px slices of columns 132-142
        // across the padding, then the rest of the art.
        val img = achievementsImage
        val boxY = y + 74f
        val boxH = img.height.f
        img.draw(x.f, boxY, x + 132f, boxY + boxH, 0f, 0f, 132f, boxH)
        var sliceX = x + 132f
        var filled = 0
        while (filled < achievementPadding + 10) {
            img.draw(sliceX, boxY, sliceX + 10f, boxY + boxH, 132f, 0f, 142f, boxH)
            sliceX += 10f
            filled += 10
        }
        img.draw(sliceX, boxY, sliceX + img.width - 142f, boxY + boxH, 142f, 0f, img.width.f, boxH)

        titleFont.printCenter((x + (achievementsWidth + 1) / 2).f, y + 90f,
            game.translator["menu_status_achievements"], Constants.TEXT_BUTTON_NORMAL)

        if (hs != null) {
            drawSeedBox(x + 66, y + 205)
            if (hs.custom) {
                // Its numbers are the desktop layout's; the touch layout has the panel elsewhere.
                val shift = TouchMode.enabled
                val shiftX = if (shift) x - (menuX + menuWidth + 0x26) else 0
                val shiftY = if (shift) y + 0x77 - 362 else 0
                g.pushTransform()
                g.translate(shiftX.f, shiftY.f)
                drawHsAchievements(g, hs, mousePos.x - shiftX, mousePos.y - shiftY)
                g.popTransform()
                return
            }
        }

        // CAchievement::OnRender at the slots MenuScreen's layout assigns: the ship's
        // three 64px icons, then victory below and the quest above at the right.
        val achY = y + 0x77
        for ((i, achievement) in achievements.take(3).withIndex()) {
            val achX = x + 22 + (achievementPadding + 3) / 6 + i * ((achievementPadding + 1) / 3 + 71)
            drawAchievement(g, achievement, achX, achY)
        }
        val sideX = x + 245 + achievementPadding
        val family = shipFamily ?: return
        val profile = game.mainGame.profile
        val wins = family.ships.map { profile.getLayoutVictory(it) }
        val won = wins.any { it != null }
        game.getImg("img/achievements/S_V_${if (won) "on" else "off"}.png").draw(sideX, achY + 34)
        if (won) {
            val dotX = sideX + if (family.ships.size < 3) 6 else 1
            wins.forEachIndexed { i, win ->
                game.getImg("img/achievements/S_V_dot_${if (win != null) "on" else "off"}.png").draw(dotX + i * 10, achY + 58)
            }
        }
        if (family.hasQuest) {
            val earned = profile.getShipUnlock(family) != null
            game.getImg("img/achievements/S_Q_${if (earned) "on" else "off"}.png").draw(sideX, achY)
        }
    }

    /** MenuScreen::OnRender hook (CustomShipSelect.cpp): the seed box, title and the run's seed ("-" with seeds disabled). */
    private fun drawSeedBox(x: Int, y: Int) {
        game.getImg("img/optionsUI/info_seed.png").draw(x, y)
        titleFont.printCenter(x + 81f, y + 16f, game.translator["menu_status_seed"], Constants.TEXT_BUTTON_NORMAL)
        buttonFont.printCenter(x + 81f, y + 40f, xyz.znix.xftl.hs.render.HsPauseMenu.seedText(), Constants.TEXT_BUTTON_TEXT)
    }

    /**
     * A Hyperspace ship's achievements (MenuScreen::Open hook): up to three 64 px icons centred on x = 727.5 at y 362,
     * "No Ship Achievements" at (742, 387) without any, the victory icon at (859, 396) and the quest icon at (859, 362).
     */
    private fun drawHsAchievements(g: Graphics, hs: xyz.znix.xftl.hs.render.HsPauseMenu, mouseX: Int, mouseY: Int) {
        // The original skips the line while the restart/hangar prompt is open (confirmDialog.bOpen).
        if (hs.achievements.isEmpty() && game.shipUI.isPauseWindowOnTop(this))
            titleFont.printCenter(742f, 387f, game.translator["hangar_no_ship_achievements"], Constants.TEXT_BUTTON_NORMAL)
        for ((i, ach) in hs.achievements.withIndex()) {
            val x = (727.5f - 30.5f * hs.achievements.size + 71 * i).toInt()
            if (ach.icon.isNotEmpty()) game.getImg("img/achievements/${ach.icon}.png").draw(x, 362)
            if (!ach.unlocked) {
                g.colour = Colour(0f, 0f, 0f, 0.75f)
                g.fillRect(x, 362, 64, 64)
                lockIcon.draw(x + 24, 362 + 21)
            }
            val hovering = mouseX in x until x + 64 && mouseY in 362 until 362 + 64
            g.colour = if (hovering) Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT else Constants.ACHIEVEMENT_OUTLINE
            g.drawRect(x, 362, 63, 63)
            g.drawRect(x + 1, 362 + 1, 61, 61)
        }
        game.getImg("img/achievements/S_V_${if (hs.victoryOn) "on" else "off"}.png").draw(859, 396)
        hs.victoryLayouts?.let { layouts ->
            if (hs.victoryOn) {
                val dotX = 859 + if (layouts.size < 3) 6 else 1
                layouts.forEachIndexed { i, win ->
                    game.getImg("img/achievements/S_V_dot_${if (win) "on" else "off"}.png").draw(dotX + i * 10, 396 + 24)
                }
            }
        }
        if (hs.hasQuest)
            game.getImg("img/achievements/S_Q_${if (hs.questOn) "on" else "off"}.png").draw(859, 362)
    }

    /** FUN_005b3920: the info art stretched across [width] through its middle column. */
    private fun drawInfoBox(img: Image, x: Int, y: Int, width: Int) {
        val h = img.height.f
        if (width == img.width) {
            img.draw(x, y)
            return
        }
        val half = img.width / 2
        img.draw(x.f, y.f, (x + half).f, y + h, 0f, 0f, half.f, h)
        img.draw((x + half).f, y.f, (x + width - half).f, y + h, half.f, 0f, half + 1f, h)
        img.draw((x + width - half).f, y.f, (x + width).f, y + h, half.f, 0f, img.width.f, h)
    }

    private fun drawAchievement(g: Graphics, achievement: Achievement, x: Int, y: Int) {
        game.getImg(achievement.img).draw(x, y)
        if (game.mainGame.profile.getAchievement(achievement) == null) {
            // CAchievement::lockOverlay is black at 0.75 alpha.
            g.colour = Colour(0f, 0f, 0f, 0.75f)
            g.fillRect(x, y, 64, 64)
            lockIcon.draw(x + 24, y + 21)
        }
        val hovering = mousePos.x in x until x + 64 && mousePos.y in y until y + 64
        g.colour = if (hovering) Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT else Constants.ACHIEVEMENT_OUTLINE
        g.drawRect(x, y, 63, 63)
        g.drawRect(x + 1, y + 1, 61, 61)
    }

    /**
     * The controls help (MenuScreen::OnRender 0x5109e0 with +0x2c0 set): help_screen_overlay.png at the origin, then
     * the table at 0x87c5e0: titles in font 63 centred at (x, y) in the button text colour, texts in font 10 wrapped
     * at their width and centred on x in the normal button colour, vertically centred on y (a pair 8 px apart), and
     * help_customize in font 12 centred at (640, 680).
     */
    private fun drawHelp(g: Graphics) {
        if (TouchMode.enabled) {
            g.colour = Colour(0f, 0f, 0f, 1f)
            g.fillRect(0f, 0f, game.mainGame.gameContainer.width.f, game.mainGame.gameContainer.height.f)
            g.pushTransform()
            g.translate(TouchMode.hudX.f, 0f)
            game.getImg("img/ipad/help_screen_overlay.png").draw(0f, 0f)
        } else game.getImg("img/help_screen_overlay.png").draw(0f, 0f)
        val t = game.translator
        val titles = if (TouchMode.enabled) TOUCH_HELP_TITLES else HELP_TITLES
        val texts = if (TouchMode.enabled) TOUCH_HELP_TEXTS else HELP_TEXTS
        for ((x, y, key) in titles)
            helpTitleFont.printCenter(x.f, y.f, t[key], Constants.TEXT_BUTTON_TEXT)
        fun wrapped(key: String, width: Int) = t[key].replace("\\n", "\n").split('\n')
            .flatMap { if (it.isEmpty()) listOf("") else helpTextFont.font.wrapString(it, width) }
        for (text in texts) {
            val first = wrapped(text.key, text.width)
            val firstHeight = first.size * helpTextFont.lineHeight
            val second = text.second?.let { wrapped(it, text.width) }
            if (second == null) {
                helpTextFont.printCenter(text.x.f, (text.y - firstHeight / 2).f, first.joinToString("\n"), Constants.TEXT_BUTTON_NORMAL)
                continue
            }
            val top = text.y - (second.size * helpTextFont.lineHeight + firstHeight + text.gap) / 2
            helpTextFont.printCenter(text.x.f, top.f, first.joinToString("\n"), Constants.TEXT_BUTTON_NORMAL)
            helpTextFont.printCenter(text.x.f, (top + text.gap + firstHeight).f, second.joinToString("\n"), Constants.TEXT_BUTTON_NORMAL)
        }
        if (TouchMode.enabled) {
            helpNoteFont.printCenter(834f, 713f, t["help_tap_close"], Constants.TEXT_BUTTON_NORMAL)
            g.popTransform()
        } else helpNoteFont.printCenter(640f, 680f, t["help_customize"], Constants.TEXT_BUTTON_NORMAL)
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        // A finger closes the help when it lifts, as the press that opened it has already ended.
        if (TouchMode.enabled && helpOpen && !helpJustOpened) {
            helpOpen = false
            return
        }
        helpJustOpened = false
        super.mouseReleased(button, x, y)
    }

    private var helpJustOpened = false

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (helpOpen) return
        super.mouseClick(button, x, y)
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        if (!helpOpen) return false
        helpOpen = false
        return true
    }

    override fun updateUI(x: Int, y: Int) {
        if (helpOpen) return
        super.updateUI(x, y)

        mousePos.set(x, y)
    }

    override fun escapePressed() {
        if (helpOpen) helpOpen = false else close()
    }

    // Vanilla-format exports are only meaningful at a sector boundary: the
    // importing engine regenerates the beacon layout, so only the sector's
    // entry beacon is a stable landing point (the loader snaps to the same
    // beacon on import, and the writer pins its export order around it).
    private fun atSectorStart() =
        game.currentBeacon === game.currentBeacon.sector.startBeacon

    // Button handlers
    private fun exportSaveClicked() {
        val transfer = xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.saveTransfer ?: return
        if (!atSectorStart()) return
        val path = game.mainGame.getVanillaSavePath()
        val bytes = if (java.nio.file.Files.exists(path)) {
            java.nio.file.Files.readAllBytes(path)
        } else {
            // No vanilla save yet - write one from the current state.
            game.mainGame.writeRunSave()
            java.nio.file.Files.readAllBytes(path)
        }
        transfer.exportSave(bytes)
    }

    private fun importSaveClicked() {
        val transfer = xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.saveTransfer ?: return
        transfer.importSave { bytes ->
            if (bytes != null) {
                val path = game.mainGame.getVanillaSavePath()
                java.nio.file.Files.createDirectories(path.parent)
                java.nio.file.Files.write(path, bytes)
                // Switch to the imported run (also handles parse errors).
                game.mainGame.continueVanillaSave()
            }
        }
    }

    private fun continueClicked() {
        close()
    }

    private fun mainMenuClicked() {
        game.mainGame.quitGame()
    }

    private fun hangarClicked() {
        game.shipUI.confirmEndRun { game.mainGame.abandonRunToHangar() }
    }

    private fun restartClicked() {
        game.shipUI.confirmEndRun { game.mainGame.restartGame() }
    }

    private fun optionsClicked() {
        game.shipUI.showOptionsWindow()
    }

    private fun controlsClicked() {
        helpJustOpened = true
        helpOpen = true
    }

    private fun quitClicked() {
        game.mainGame.quitGame()
    }

    private class HelpText(val x: Int, val y: Int, val width: Int, val key: String, val gap: Int = 0, val second: String? = null)

    companion object {
        private val TOUCH_HELP_TITLES = listOf(
            Triple(159, 104, "help_crew_title"), Triple(481, 30, "help_pause_title"),
            Triple(481, 468, "help_power_title"), Triple(834, 103, "help_weapons_title"),
        )
        private val TOUCH_HELP_TEXTS = listOf(
            HelpText(159, 287, 300, "help_crew_text_ipad_select"),
            HelpText(159, 466, 300, "help_crew_text_ipad_move"),
            HelpText(159, 641, 300, "help_crew_text_ipad_bottom"),
            HelpText(481, 200, 307, "help_pause_text_ipad_1", 8, "help_pause_text_ipad_2"),
            HelpText(481, 395, 307, "help_pause_text_ipad_auto"),
            HelpText(481, 692, 307, "help_power_text_ipad_1", 8, "help_power_text_ipad_2"),
            HelpText(834, 152, 355, "help_weapons_text_top"),
            HelpText(712, 312, 999, "help_weapons_text_ipad_power"),
            HelpText(834, 312, 999, "help_weapons_text_ipad_activate"),
            HelpText(956, 312, 999, "help_weapons_text_ipad_target"),
            HelpText(758, 414, 197, "help_weapons_text_ipad_autofire"),
            HelpText(894, 574, 226, "help_weapons_text_ipad_bottom1", 16, "help_weapons_text_ipad_bottom2"),
        )

        // MenuScreen's help table (0x87c5e0, 17 entries of x, y, wrap width, title flag, text, gap, second text).
        private val HELP_TITLES = listOf(
            Triple(227, 135, "help_crew_title"), Triple(646, 70, "help_pause_title"),
            Triple(646, 410, "help_power_title"), Triple(1060, 135, "help_weapons_title"),
        )
        private val HELP_TEXTS = listOf(
            HelpText(227, 189, 400, "help_crew_text_top"),
            HelpText(133, 349, 184, "help_crew_text_pc_select"),
            HelpText(323, 349, 184, "help_crew_text_pc_move"),
            HelpText(227, 568, 400, "help_crew_text_pc_bottom"),
            HelpText(646, 306, 359, "help_pause_text_pc_1", 8, "help_pause_text_pc_2"),
            HelpText(646, 469, 359, "help_power_text_top"),
            HelpText(559, 629, 158, "help_power_text_pc_add"),
            HelpText(735, 629, 158, "help_power_text_pc_remove"),
            HelpText(1060, 186, 400, "help_weapons_text_top"),
            HelpText(934, 346, 999, "help_weapons_text_pc_power"),
            HelpText(1064, 346, 999, "help_weapons_text_pc_activate"),
            HelpText(1194, 346, 999, "help_weapons_text_pc_target"),
            HelpText(1060, 559, 400, "help_weapons_text_pc_bottom1", 8, "help_weapons_text_pc_bottom2"),
        )
    }
}
