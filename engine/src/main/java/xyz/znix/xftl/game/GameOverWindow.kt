package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.NativeFonts

/**
 * This is the window that is displayed when the game ends, either
 * through success or failure.
 *
 * Laid out like GameOver::OpenText (0x004d68e0) and drawn like
 * GameOver::OnRender (0x004d5750): the frame widens in 10px steps from 471 to
 * fit its title and buttons, centred on (640,360); the four bottom buttons
 * spread the spare width a third, then a half, then the rest between them.
 */
class GameOverWindow(private val game: InGameState, val outcome: Outcome) : Window() {
    private val image: Image = game.getImg("img/scoreUI/gameover_main.png")
    private val buttonFont = NativeFonts.font(game::getFont, 62)
    private val titleFont = NativeFonts.font(game::getFont, 64)
    private val bodyFont = NativeFonts.font(game::getFont, 14)
    private val scoreFont = NativeFonts.font(game::getFont, 24)
    private val highScoreFont = NativeFonts.font(game::getFont, 7)

    private val title = game.translator[if (outcome == Outcome.WIN) "gameover_title_victory" else "gameover_title_gameover"]
    // Hyperspace GameOver::OpenText: a replaced game over text (a <win>/<lose> event, a <finalBoss>) is shown once.
    // CommandGui::CheckGameover (0x52c900): a loss in the tutorial reads gameover_tutorial whatever its cause.
    private val body = (if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.events.HsFinalBoss.takeGameOverText() else null)
        ?: game.translator[when {
        outcome != Outcome.WIN && game.mainGame?.isTutorialRunning == true -> "gameover_tutorial"
        else -> when (outcome) {
            Outcome.WIN -> "gameover_win"
            Outcome.LOOSE_HULL -> "gameover_blowup"
            Outcome.LOOSE_CREW -> "gameover_crew"
            Outcome.LOOSE_BASE_DESTROYED -> "gameover_fedbase"
        }
    }]

    private val bottomLabels = listOf(
        "gameover_button_restart" to ::restartClicked,
        "gameover_button_hangar" to ::hangarClicked,
        "gameover_button_mainmenu" to ::mainMenuClicked,
        "gameover_button_quit" to ::quitClicked,
    ).map { (key, cb) -> game.translator[key] to cb }

    // TextButton::SetAutoWidth with a margin of 3.
    private fun autoWidth(label: String) = buttonFont.measure(label) + 6

    private val bottomWidths = bottomLabels.map { autoWidth(it.first) }
    private val statsLabel = game.translator["gameover_button_stats"]

    private val width: Int
    private val gaps: IntArray

    init {
        val buttonsWidth = bottomWidths.sumOf { it + 22 } + 10
        val wanted = maxOf(0x1e1, autoWidth(statsLabel) + 0x195, titleFont.measure(title) + 0xe0, buttonsWidth)
        width = (wanted - 0x1ce) / 10 * 10 + 0x1d7

        var spare = width - buttonsWidth
        val first = (spare + 1) / 3
        spare -= first
        val second = (spare + 1) / 2
        gaps = intArrayOf(first, second, spare - second)
    }

    private val frameX = 0x280 - width / 2
    private val frameY = 0x168 - image.height / 2

    override val size: IPoint = ConstPoint(width, image.height)
    override val windowCentreOffset: IPoint =
        ConstPoint(frameX - (1280 - width) / 2, frameY - (720 - image.height) / 2)

    private val bodyLines: List<String> = bodyFont.font.wrapString(body, width - 0x51)

    private val score = game.browserProgress.score
    // GameOver::OnRender (0x004d5750): new_high while the scorekeeper's last submission made the top list.
    private val newHighScore: Boolean = game.mainGame.profile.browserNewTopIndex != -1

    private val buttonPositions = ArrayList<Int>()

    init {
        var x = 16
        for ((i, w) in bottomWidths.withIndex()) {
            buttonPositions += x
            buttons += GameOverButton(bottomLabels[i].first, ConstPoint(x, 0x11b), w, bottomLabels[i].second)
            x += w + 22 + gaps.getOrElse(i) { 0 }
        }
        buttons += GameOverButton(statsLabel, ConstPoint(0xca, 0xf7), width - 0x195, ::statsClicked)
    }

    /** A GameOver TextButton: font 62, corner inset 4, 24px tall. */
    private inner class GameOverButton(val label: String, pos: ConstPoint, width: Int, val cb: () -> Unit) :
        Button(game, pos, ConstPoint(width, 24)) {
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

    /**
     * GameOver::SetVictory (0x004d6630, from 0x00530c50): a victory first rolls the credits, thanking the ship
     * and its crew (CrewMemberFactory's player crew, 0x00467c90), until they finish (GameOver::OnLoop
     * 0x004d8790) or Escape skips them (0x004d51a0). The roll moves as it renders, as Credits' render does.
     */
    var victoryCredits: CreditsRoll? = if (outcome != Outcome.WIN) null else CreditsRoll(
        game.content, CreditsRoll.Victory(
            game.player.displayName ?: game.player.type.shipTitle?.let { game.translator[it] } ?: game.player.name,
            game.playerCrew.filter { it.health > 0f }.map { it.info.name }
        )
    )
        private set

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (victoryCredits == null)
            super.mouseClick(button, x, y)
    }

    // GameOver::OnLoop (0x4d8790) adds a sixteenth of the speed factor to the
    // window's clock and switches a victory to the title playlist at 25. It
    // runs twice a frame (CommandGui::OnLoop calls it at 0x536f39 and again
    // through the focus-window loop at 0x536ae4), so the switch comes 12.5 s
    // after the window opens (OpenText resets the clock).
    private var openTime = 0f

    fun tick(dt: Float) {
        if (outcome != Outcome.WIN || openTime >= VICTORY_MUSIC_TIME)
            return
        openTime += dt
        if (openTime >= VICTORY_MUSIC_TIME)
            // Hyperspace (Credits.cpp's GameOver::OnLoop): the victory event's music instead of the title's
            game.sounds.getTrackOrNull(xyz.znix.xftl.hs.render.HsCredits.creditsMusic())?.let { game.sounds.switchMusicList(listOf(it)) }
    }

    /** The statistics opened from here draw this game behind them without the window. */
    var hiddenBehindScores = false

    override fun draw(g: Graphics) {
        if (hiddenBehindScores) return
        // The music clock runs from the victory itself, credits or not (GameOver::OnLoop runs throughout).
        tick(game.renderingDeltaTime)
        victoryCredits?.let { credits ->
            credits.advance(game.renderingDeltaTime)
            if (credits.isDone) {
                victoryCredits = null
            } else {
                credits.render(g)
                return
            }
        }

        // GameOver::OnRender hook (CustomShipSelect.cpp): the run's seed under the window.
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.sector.HsSeeds.enabled) {
            val box = game.getImg("img/optionsUI/info_seed.png")
            box.draw(640 - box.width / 2, 524)
            xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 13)
                .printCenter(640f, 540f, game.translator["menu_status_seed"], Constants.TEXT_BUTTON_NORMAL)
            buttonFont.printCenter(640f, 564f, xyz.znix.xftl.hs.render.HsPauseMenu.seedText(), Constants.TEXT_BUTTON_TEXT)
        }

        val x = position.x
        val y = position.y
        drawFrame(x.f, y.f)

        for (button in buttons) {
            button.draw(g)
        }

        val centreX = x + width / 2
        titleFont.printCenter(centreX + 1f, y + 15f, title, Constants.TEXT_BUTTON_TEXT)
        bodyFont.printCenter(centreX.f, y + 75f, bodyLines.joinToString("\n"), Colour.white)

        if (!game.mainGame.isTutorialRunning) {
            // The browser marks scores rebuilt from an incomplete run history.
            val scoreText = game.translator["gameover_score"].replace("\\1", score.toString()) +
                    if (game.browserProgress.historyComplete) "" else " (PARTIAL)"
            scoreFont.printCenter(centreX.f, y + 179f, scoreText, Colour.white)
        }
        if (newHighScore)
            highScoreFont.printCenter(centreX.f, y + 220f, game.translator["new_high"], Colour.white)
    }

    /**
     * The frame art: its left 230 columns, 10px slices of columns 230-240, then the
     * right 241; under that a 47px strip woven around the bottom buttons from 26px
     * caps, column 26 behind each button, and 21px joints (107-128, 128-149) either
     * side of column 128 stretched across each gap.
     */
    private fun drawFrame(x: Float, y: Float) {
        val img = image
        val top = 276f
        img.draw(x, y, x + 230f, y + top, 0f, 0f, 230f, top)
        var sx = x + 230f
        while (sx < x + width - 241f) {
            img.draw(sx, y, sx + 10f, y + top, 230f, 0f, 240f, top)
            sx += 10f
        }
        img.draw(sx, y, sx + 241f, y + top, 240f, 0f, img.width.f, top)

        val by = y + top
        val bh = img.height.f
        fun strip(left: Float, w: Float, u0: Float, u1: Float) =
            img.draw(left, by, left + w, y + bh, u0, top, u1, bh)

        var bx = x
        strip(bx, 26f, 0f, 26f)
        bx += 26f
        for ((i, w) in bottomWidths.withIndex()) {
            strip(bx, w - 20f, 26f, 27f)
            bx += w - 20f
            if (i == bottomWidths.lastIndex) break
            strip(bx, 21f, 107f, 128f)
            bx += 21f
            strip(bx, gaps[i].f, 128f, 129f)
            bx += gaps[i]
            strip(bx, 21f, 128f, 149f)
            bx += 21f
        }
        strip(bx, 26f, img.width - 26f, img.width.f)
    }

    override fun escapePressed() {
        // Escape only skips the victory credits: the player can't dismiss this window, nor open the pause screen.
        victoryCredits = null
    }

    private fun restartClicked() {
        game.mainGame.restartGame()
    }

    private fun statsClicked() {
        game.mainGame.setCurrentState(ftl.browser.BrowserStatisticsState(game.mainGame, game))
    }

    private fun quitClicked() {
        game.mainGame.quitGame()
    }

    private fun mainMenuClicked() {
        // The run save was deleted when this screen appeared, so Continue
        // won't offer the dead run.
        game.mainGame.switchToMainMenu()
    }

    private fun hangarClicked() {
        game.mainGame.switchToShipSelect()
    }

    enum class Outcome {
        WIN,
        LOOSE_HULL,
        LOOSE_CREW,
        LOOSE_BASE_DESTROYED,
    }

    companion object {
        /** Seconds before a victory window starts the title music: 25 (0x87a95c) at two clock ticks a frame. */
        const val VICTORY_MUSIC_TIME: Float = 12.5f
    }
}
