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
    private val menuImage: Image = game.getImg("img/optionsUI/escape_main.png")
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
    private val entries: MutableList<Triple<String, Int, () -> Unit>> = mutableListOf(
        Triple(game.translator["escape_continue"], 18, ::continueClicked),
        Triple(game.translator["escape_mainmenu"], 69, ::mainMenuClicked),
        Triple(game.translator["escape_hangar"], 106, ::hangarClicked),
        Triple(game.translator["escape_reset"], 143, ::restartClicked),
        Triple(game.translator["escape_options"], 180, ::optionsClicked),
        Triple(game.translator["escape_controls"], 217, ::controlsClicked),
        Triple(game.translator["escape_save"], 254, ::quitClicked),
    )

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

    // Centred on x=660 with the info panel 38px to its right, on y=360. While the
    // tutorial runs, MenuScreen::OnRender (0x005109e0) shifts it 150px right and
    // hides the info panel.
    private val menuX = 0x294 - (achievementsWidth + 0x26 + menuWidth) / 2 + if (tutorial) 150 else 0
    private val menuY = 0x168 - menuImage.height / 2

    override val size: IPoint = ConstPoint(menuWidth, menuImage.height)
    override val windowCentreOffset: IPoint =
        ConstPoint(menuX - (1280 - menuWidth) / 2, menuY - (720 - menuImage.height) / 2)

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
            g.colour = if (hovered) Constants.TEXT_BUTTON_HOVER else Constants.TEXT_BUTTON_NORMAL
            Buttons.drawChamfer(g, pos.x, pos.y, size.x, size.y, 4)
            buttonFont.printButtonLabel(pos.x, pos.y, size.x, size.y, label, Constants.TEXT_BUTTON_TEXT)
        }

        override fun click(button: Int) {
            if (button == xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON)
                cb()
        }
    }

    override fun draw(g: Graphics) {
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

        titleFont.printCenter((x + (difficultyWidth + 1) / 2).f, y + 16f, difficultyTitle, Constants.TEXT_BUTTON_NORMAL)
        titleFont.printCenter((x + difficultyWidth + 3 + (dlcWidth + 1) / 2).f, y + 16f, dlcTitle, Constants.TEXT_BUTTON_NORMAL)
        titleFont.printCenter((x + (achievementsWidth + 1) / 2).f, y + 90f,
            game.translator["menu_status_achievements"], Constants.TEXT_BUTTON_NORMAL)
        buttonFont.printCenter((x + (difficultyWidth + 1) / 2).f, y + 40f, difficultyValue, Constants.TEXT_BUTTON_TEXT)
        buttonFont.printCenter((x + difficultyWidth + 3 + (dlcWidth + 1) / 2).f, y + 40f, dlcValue, Constants.TEXT_BUTTON_TEXT)

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

    override fun updateUI(x: Int, y: Int) {
        super.updateUI(x, y)

        mousePos.set(x, y)
    }

    override fun escapePressed() {
        close()
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
        game.shipUI.showKeybindWindow()
    }

    private fun quitClicked() {
        game.mainGame.quitGame()
    }
}
