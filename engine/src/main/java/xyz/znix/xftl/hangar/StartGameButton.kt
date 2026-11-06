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

    /**
     * ShipBuilder's layout sizes the difficulty buttons to the widest of the three labels: the widest
     * easy_measurePrintLines(62) width (rounded, at least 89) less 80, truncated to tens, widens them (and the
     * box_start frame) and moves them left by as much, so their right edge stays put.
     */
    private val growth: Int get() {
        var widest = 0x59
        for (d in Difficulty.entries) {
            val text = state.translator[d.startButtonKey]
            widest = maxOf(widest, text.split('\n').maxOf { difficultyFont.measure(it) })
        }
        return (widest - 80) / 10 * 10
    }
    private val difficultyX get() = DIFFICULTY_X + HangarLayout.startDx - growth
    private val difficultyY get() = DIFFICULTY_Y + HangarLayout.startDy
    private val startX get() = START_X + HangarLayout.startDx
    private val difficultyWidth get() = DIFFICULTY_WIDTH + growth

    val hoveredDifficulty: Difficulty? get() {
        if (state.mousePos.x !in difficultyX until difficultyX + difficultyWidth) return null
        return Difficulty.entries.firstOrNull { state.mousePos.y in difficultyY + DIFFICULTY_STRIDE * it.ordinal until difficultyY + DIFFICULTY_STRIDE * it.ordinal + DIFFICULTY_HEIGHT }
    }

    /** Hyperspace's ShipBuilder::Open hook puts the START button at y 15 (Seeds.cpp), 13 px above the vanilla one. */
    private val startY get() = (if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsSeedBox.START_Y else START_Y) + HangarLayout.startDy

    fun draw(g: Graphics) {
        // box_start.png: its first 42 columns, then columns 42..52 once per ten px of growth, then columns 42 on.
        val image = state.getImg("img/customizeUI/box_start.png")
        val grow = growth
        var x = (FRAME_X + HangarLayout.startDx - grow).f
        val h = image.height.f
        val frameY = FRAME_Y + HangarLayout.startDy
        image.draw(x, frameY.f, x + 42f, frameY + h, 0f, 0f, 42f, h)
        x += 42f
        repeat(grow / 10) {
            image.draw(x, frameY.f, x + 10f, frameY + h, 42f, 0f, 52f, h)
            x += 10f
        }
        image.draw(x, frameY.f, x + image.width - 42f, frameY + h, 42f, 0f, image.width.f, h)

        for (difficulty in Difficulty.entries) {
            drawDifficultyButton(g, difficulty)
        }

        drawStartButton(g)
    }

    private fun drawDifficultyButton(g: Graphics, difficulty: Difficulty) {
        val x = difficultyX
        val y = difficultyY + DIFFICULTY_STRIDE * difficulty.ordinal
        val width = difficultyWidth
        val height = DIFFICULTY_HEIGHT

        val text = state.translator[difficulty.startButtonKey]
        val isHovering = state.mousePos.x in x until x + width && state.mousePos.y in y until y + height

        val bgColour = when {
            selected == difficulty -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
            isHovering -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
            else -> xyz.znix.xftl.hs.render.HsColors.tbActive()
        }

        drawButton(g, x, y, width, height, 4, text, difficultyFont, bgColour)
    }

    private fun drawStartButton(g: Graphics) {
        val x = startX
        val y = startY
        val width = START_WIDTH
        val height = START_HEIGHT
        val text = if (state.isSelectedUnlocked) state.translator["start_button"] else "LOCKED"

        val hoveringStart = state.isSelectedUnlocked && state.mousePos.x in x until x + width && state.mousePos.y in y until y + height

        val bgColour = when {
            !state.isSelectedUnlocked -> xyz.znix.xftl.hs.render.HsColors.tbInactive()
            hoveringStart -> xyz.znix.xftl.hs.render.HsColors.tbSelected()
            else -> xyz.znix.xftl.hs.render.HsColors.tbActive()
        }

        drawButton(g, x, y, width, height, 6, text, startFont, bgColour)
    }

    private fun drawButton(g: Graphics, x: Int, y: Int, width: Int, height: Int, inset: Int,
                           text: String, font: NativeFonts.NativeFont, bgColour: xyz.znix.xftl.rendering.Colour) {
        g.colour = bgColour
        Buttons.drawChamfer(g, x, y, width, height, inset)
        font.printButtonLabel(x, y, width, height, text, xyz.znix.xftl.hs.render.HsColors.tbText())
    }

    fun updateHover(enabled: Boolean) {
        val x = state.mousePos.x
        val y = state.mousePos.y
        val next = if (!enabled) null else when {
            state.isSelectedUnlocked && x in startX until startX + START_WIDTH &&
                    y in startY until startY + START_HEIGHT -> "start"
            x in difficultyX until difficultyX + difficultyWidth ->
                Difficulty.entries.firstOrNull { it != selected && y in difficultyY + DIFFICULTY_STRIDE * it.ordinal until difficultyY + DIFFICULTY_STRIDE * it.ordinal + DIFFICULTY_HEIGHT }?.name
            else -> null
        }
        if (next != null && next != hoveredControl) state.sounds.getSample("hoverBeep").play()
        hoveredControl = next
    }

    /** What a finger taps (TouchRouter): a difficulty, Start; else null. */
    fun liftTarget(x: Int, y: Int): Any? {
        if (x in difficultyX until difficultyX + difficultyWidth)
            Difficulty.entries.firstOrNull { y in difficultyY + DIFFICULTY_STRIDE * it.ordinal until difficultyY + DIFFICULTY_STRIDE * it.ordinal + DIFFICULTY_HEIGHT }?.let { return it }
        if (state.isSelectedUnlocked && x in startX until startX + START_WIDTH && y in startY until startY + START_HEIGHT) return "start"
        return null
    }

    fun mouseClicked(button: Int, mouseX: Int, mouseY: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        for (difficulty in Difficulty.entries) {
            val y = difficultyY + DIFFICULTY_STRIDE * difficulty.ordinal
            if (mouseX in difficultyX until difficultyX + difficultyWidth &&
                mouseY in y until y + DIFFICULTY_HEIGHT) selected = difficulty
        }
        if (state.isSelectedUnlocked &&
            mouseX in startX until startX + START_WIDTH &&
            mouseY in startY until startY + START_HEIGHT) {
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
