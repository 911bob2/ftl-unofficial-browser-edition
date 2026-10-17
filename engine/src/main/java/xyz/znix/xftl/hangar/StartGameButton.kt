package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input

class StartGameButton(val state: SelectShipState) {
    private val startFont = NativeFonts.font(state::getFont, START_FONT)
    private val difficultyFont = NativeFonts.font(state::getFont, DIFFICULTY_FONT)

    var selected: Difficulty = Difficulty.NORMAL
    private var hoveredControl: String? = null

    val hoveredDifficulty: Difficulty? get() {
        if (state.mousePos.x !in DIFFICULTY_X until DIFFICULTY_X + DIFFICULTY_WIDTH) return null
        return Difficulty.entries.firstOrNull { state.mousePos.y in DIFFICULTY_Y + DIFFICULTY_STRIDE * it.ordinal until DIFFICULTY_Y + DIFFICULTY_STRIDE * it.ordinal + DIFFICULTY_HEIGHT }
    }

    fun draw(g: Graphics) {
        val image = state.getImg("img/customizeUI/box_start.png")
        image.draw(FRAME_X.f, FRAME_Y.f)

        for (difficulty in Difficulty.entries) {
            drawDifficultyButton(g, difficulty)
        }

        drawStartButton(g)
    }

    private fun drawDifficultyButton(g: Graphics, difficulty: Difficulty) {
        val x = DIFFICULTY_X
        val y = DIFFICULTY_Y + DIFFICULTY_STRIDE * difficulty.ordinal
        val width = DIFFICULTY_WIDTH
        val height = DIFFICULTY_HEIGHT

        val text = state.translator[difficulty.startButtonKey]
        val isHovering = state.mousePos.x in x until x + width && state.mousePos.y in y until y + height

        val bgColour = when {
            selected == difficulty -> Constants.TEXT_BUTTON_SELECTED
            isHovering -> Constants.TEXT_BUTTON_HOVER
            else -> Constants.TEXT_BUTTON_NORMAL
        }

        drawButton(g, x, y, width, height, 4, text, difficultyFont, bgColour)
    }

    private fun drawStartButton(g: Graphics) {
        val x = START_X
        val y = START_Y
        val width = START_WIDTH
        val height = START_HEIGHT
        val text = if (state.isSelectedUnlocked) state.translator["start_button"] else "LOCKED"

        val hoveringStart = state.isSelectedUnlocked && state.mousePos.x in x until x + width && state.mousePos.y in y until y + height

        val bgColour = when {
            !state.isSelectedUnlocked -> Constants.TEXT_BUTTON_DISABLED
            hoveringStart -> Constants.TEXT_BUTTON_HOVER
            else -> Constants.TEXT_BUTTON_NORMAL
        }

        drawButton(g, x, y, width, height, 6, text, startFont, bgColour)
    }

    private fun drawButton(g: Graphics, x: Int, y: Int, width: Int, height: Int, inset: Int,
                           text: String, font: NativeFonts.NativeFont, bgColour: xyz.znix.xftl.rendering.Colour) {
        g.colour = bgColour
        Buttons.drawChamfer(g, x, y, width, height, inset)
        font.printButtonLabel(x, y, width, height, text, Constants.TEXT_BUTTON_TEXT)
    }

    fun updateHover(enabled: Boolean) {
        val x = state.mousePos.x
        val y = state.mousePos.y
        val next = if (!enabled) null else when {
            state.isSelectedUnlocked && x in START_X until START_X + START_WIDTH &&
                    y in START_Y until START_Y + START_HEIGHT -> "start"
            x in DIFFICULTY_X until DIFFICULTY_X + DIFFICULTY_WIDTH ->
                Difficulty.entries.firstOrNull { it != selected && y in DIFFICULTY_Y + DIFFICULTY_STRIDE * it.ordinal until DIFFICULTY_Y + DIFFICULTY_STRIDE * it.ordinal + DIFFICULTY_HEIGHT }?.name
            else -> null
        }
        if (next != null && next != hoveredControl) state.sounds.getSample("hoverBeep").play()
        hoveredControl = next
    }

    fun mouseClicked(button: Int, mouseX: Int, mouseY: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        for (difficulty in Difficulty.entries) {
            val y = DIFFICULTY_Y + DIFFICULTY_STRIDE * difficulty.ordinal
            if (mouseX in DIFFICULTY_X until DIFFICULTY_X + DIFFICULTY_WIDTH &&
                mouseY in y until y + DIFFICULTY_HEIGHT) selected = difficulty
        }
        if (state.isSelectedUnlocked &&
            mouseX in START_X until START_X + START_WIDTH &&
            mouseY in START_Y until START_Y + START_HEIGHT) {
            state.startGame(selected)
        }
    }

    companion object {
        // Native desktop FTL 1.6.14 layout (0x004f1dd0)
        // box_start.png: 1249 - (difficulty growth + 286), y 0, located against the original.
        private const val FRAME_X = 963
        private const val FRAME_Y = 0
        private const val START_FONT = 64
        private const val DIFFICULTY_FONT = 62

        private const val DIFFICULTY_X = 977
        private const val DIFFICULTY_Y = 14
        private const val DIFFICULTY_WIDTH = 95
        private const val DIFFICULTY_HEIGHT = 24
        private const val DIFFICULTY_STRIDE = 26

        private const val START_X = 1082
        private const val START_Y = 28
        private const val START_WIDTH = 152
        private const val START_HEIGHT = 48
    }
}
