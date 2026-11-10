package ftl.browser

import xyz.znix.xftl.Constants
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input
import kotlin.math.max

/**
 * Tutorial instruction overlay rendering native dialogue panels, continue controls,
 * and directional arrows using private original assets. Panel anchors and widths
 * follow native 0x00416930. Font ID12 maps to JustinFont11Bold in 0x005c38d0.
 */
class BrowserTutorialOverlay(private val main: MainGame) {

    private var cachedStepId: String? = null
    private var cachedLines: List<String> = emptyList()
    private var cachedHeight: Int = MIN_BOX_HEIGHT

    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Int, py: Int) = px in x until x + width && py in y until y + height
    }

    fun panelBounds(game: InGameState): Bounds {
        val step = main.tutorialAdapter!!.controller.step
        val (x, y) = getBoxPosition(step.id)
        return Bounds(x, y, BOX_WIDTH, getWrappedLinesAndHeight(game, step).second)
    }

    fun continueBounds(game: InGameState): Bounds {
        val panel = panelBounds(game)
        return Bounds(panel.x + CONTINUE_OFFSET_X, panel.y + panel.height + CONTINUE_OFFSET_Y,
            CONTINUE_WIDTH, CONTINUE_HEIGHT)
    }

    /**
     * Suppress overlay rendering and interaction while the debug console, pause menu,
     * or options dialog is open over the gameplay viewport.
     */
    fun isSuppressed(game: InGameState): Boolean {
        if (!main.isTutorialRunning) return true
        if (xyz.znix.xftl.hs.tutorial.HsTutorial.disabled) return true // Hyperspace <disableDefaultTutorial>: no lesson UI
        val adapter = main.tutorialAdapter ?: return true
        if (adapter.controller.completed) return true
        return game.isDebugConsoleVisible || game.shipUI.tutorialOverlayOpen || game.shipUI.tutorialDialogueOpen
    }

    /**
     * Hit-test for root wiring. Returns true if (x, y) falls inside the instruction box
     * or the continue button (if active).
     */
    fun hitTest(game: InGameState, canvasX: Int, y: Int): Boolean {
        if (isSuppressed(game)) return false
        val x = canvasX - hudOffset
        val adapter = main.tutorialAdapter ?: return false
        val controller = adapter.controller
        if (controller.completed) return false

        val step = controller.step
        val (boxX, boxY) = getBoxPosition(step.id)
        val boxWidth = BOX_WIDTH
        val (_, boxHeight) = getWrappedLinesAndHeight(game, step)

        // Panel bounds
        if (!TouchMode.enabled && x in boxX until (boxX + boxWidth) && y in boxY until (boxY + boxHeight)) {
            return true
        }

        // Continue button bounds
        if (controller.canContinue) {
            val continueX = boxX + CONTINUE_OFFSET_X
            val continueY = boxY + boxHeight + CONTINUE_OFFSET_Y
            if (x in continueX until (continueX + CONTINUE_WIDTH) && y in continueY until (continueY + CONTINUE_HEIGHT)) {
                return true
            }
        }

        return false
    }

    /** Overload for hit testing using current active InGameState if available. */
    fun hitTest(x: Int, y: Int): Boolean {
        val game = main.currentState as? InGameState ?: return false
        return hitTest(game, x, y)
    }

    /**
     * Consumes all clicks inside the instruction panel and continue button so clicks
     * cannot target weapons, rooms, or crew beneath. Dispatches the continue action.
     */
    fun click(game: InGameState, button: Int, canvasX: Int, y: Int): Boolean {
        if (isSuppressed(game)) return false
        val x = canvasX - hudOffset
        val adapter = main.tutorialAdapter ?: return false
        val controller = adapter.controller
        if (controller.completed) return false

        val step = controller.step
        val (boxX, boxY) = getBoxPosition(step.id)
        val boxWidth = BOX_WIDTH
        val (_, boxHeight) = getWrappedLinesAndHeight(game, step)

        // On touch the panel takes no finger: only its Continue does.
        val insidePanel = !TouchMode.enabled && x in boxX until (boxX + boxWidth) && y in boxY until (boxY + boxHeight)

        val continueX = boxX + CONTINUE_OFFSET_X
        val continueY = boxY + boxHeight + CONTINUE_OFFSET_Y
        val insideContinue = controller.canContinue &&
                x in continueX until (continueX + CONTINUE_WIDTH) &&
                y in continueY until (continueY + CONTINUE_HEIGHT)

        if (!insidePanel && !insideContinue) {
            return false
        }

        // Consume click and dispatch buttons on left mouse press
        if (button == Input.MOUSE_LEFT_BUTTON && insideContinue) {
            adapter.continueStep(game)
            if (controller.completed) main.endTutorialSession()
        }

        return true
    }

    /** Overload for clicking using current active InGameState if available. */
    fun click(button: Int, x: Int, y: Int): Boolean {
        val game = main.currentState as? InGameState ?: return false
        return click(game, button, x, y)
    }

    /**
     * Renders tutorial instruction box, original continue button, and animated arrows.
     * Drawing is free of simulation RNG and gameplay mutations.
     */
    fun draw(game: InGameState, g: Graphics) {
        if (isSuppressed(game)) return
        val adapter = main.tutorialAdapter ?: return
        val controller = adapter.controller
        if (controller.completed) return

        g.pushTransform()
        g.translate(hudOffset.toFloat(), 0f)
        val step = controller.step
        val (boxX, boxY) = getBoxPosition(step.id)
        val boxWidth = BOX_WIDTH
        val (lines, boxHeight) = getWrappedLinesAndHeight(game, step)
        val font = game.content.getFont(INSTRUCTION_FONT_NAME)

        // 1. Draw tiled instruction window frame
        val windowRenderer = game.content.windowRenderer
        // UpdateDescBox builds the WindowFrame 7px right of the panel anchor.
        windowRenderer.render(boxX + FRAME_OFFSET_X, boxY, boxWidth, boxHeight)

        // 2. Render wrapped instruction text
        // Native font metrics supply a 12px baseline and 20px line spacing.
        // The SIL file's 19px nominal height is neither the text baseline nor
        // the adjusted line spacing used by native font ID12.
        var textY = boxY + TEXT_PADDING_Y.toFloat() + TEXT_BASELINE_OFFSET
        val textX = boxX + TEXT_PADDING_X.toFloat()
        for (line in lines) {
            font.drawString(textX, textY, line, Colour.white)
            textY += TEXT_LINE_SPACING
        }

        // Mouse hover queries
        val mouseX = (game.input?.mouseX ?: -1) - hudOffset
        val mouseY = game.input?.mouseY ?: -1
        if (hitTest(game, mouseX + hudOffset, mouseY)) g.tooltip = null

        // 3. Render Continue button if enabled by native gate latch
        if (controller.canContinue) {
            val continueX = boxX + CONTINUE_OFFSET_X
            val continueY = boxY + boxHeight + CONTINUE_OFFSET_Y
            val continueHovered = mouseX in continueX until (continueX + CONTINUE_WIDTH) &&
                    mouseY in continueY until (continueY + CONTINUE_HEIGHT)

            val continueBase = game.content.getImg("img/tutorial/button_continue_base.png")
            // The original texture includes a glow outside its 170x32 hitbox.
            continueBase.draw((continueX - CONTINUE_IMAGE_OFFSET_X).toFloat(),
                (continueY - CONTINUE_IMAGE_OFFSET_Y).toFloat())

            // TutorialManager::OnInit (0x004141b0): a font 63 TextButton (inset 4), whose
            // label falls back to font 62 when the translation is wider than the button.
            val continueText = game.content.translator["tutorial_continue"]
            var continueFont = NativeFonts.font({ game.content.getFont(it) }, 63)
            if (continueFont.measure(continueText) > CONTINUE_WIDTH)
                continueFont = NativeFonts.font({ game.content.getFont(it) }, 62)
            g.colour = if (continueHovered) xyz.znix.xftl.hs.render.HsColors.tbSelected() else xyz.znix.xftl.hs.render.HsColors.tbActive()
            Buttons.drawChamfer(g, continueX, continueY, CONTINUE_WIDTH, CONTINUE_HEIGHT, 4)
            continueFont.printButtonLabel(continueX, continueY, CONTINUE_WIDTH, CONTINUE_HEIGHT, continueText,
                xyz.znix.xftl.hs.render.HsColors.tbText())
        }

        // 4. Render arrows relative to screen or upgrades window. In INVENTORY_2 the original drops its arrows once
        // the beam is in a weapon slot, before the window is closed.
        val inventoryDone = step.id == "INVENTORY_2" && adapter.observation(game).beamEquipped
        if (step.arrows.isNotEmpty() && !inventoryDone) {
            val arrowImg = game.content.getImg("img/tutorial/arrow.png")
            val arrow2Img = game.content.getImg("img/tutorial/arrow2.png")
            val arrowW = ARROW_WIDTH.toFloat()
            val arrowH = ARROW_HEIGHT.toFloat()

            // Native arrows use the same 0.75-second triangular alpha curve
            // as power flashes, with their own session clock and no gameplay RNG.
            val overlayAlpha = adapter.visuals.arrowAlpha

            for (arrow in step.arrows) {
                val hud = game.shipUI.touch
                // The enemy's arrow points into the box, so it waits for the box at full size.
                if (arrow.anchor == BrowserTutorialDefinition.Anchor.ENEMY && hud != null && hud.focusAmount > 0f) continue
                val upgradeOffset = when (arrow.anchor) {
                    BrowserTutorialDefinition.Anchor.UPGRADES -> {
                        val win = game.shipUI.tutorialShipWindow
                        if (win != null) win.position.x - hudOffset to win.position.y else 0 to 0
                    }
                    BrowserTutorialDefinition.Anchor.TARGET_BOX, BrowserTutorialDefinition.Anchor.ENEMY -> {
                        val box = if (game.enemy != null) hud?.targetBox(game.enemy.isUsingBossUI) else null
                        if (box != null) box[0] to box[1] else 0 to 0
                    }
                    else -> 0 to 0
                }

                val ax = arrow.x + upgradeOffset.first
                val ay = arrow.y + upgradeOffset.second
                val cx = ax + arrowW / 2f
                val cy = ay + arrowH / 2f

                g.pushTransform()
                if (arrow.angle != 0) {
                    g.rotate(cx, cy, arrow.angle.toFloat())
                }
                arrowImg.draw(ax.toFloat(), ay.toFloat())
                arrow2Img.draw(ax.toFloat(), ay.toFloat(), Colour(1f, 1f, 1f, overlayAlpha))
                g.popTransform()
            }
        }
        // The hand shows until the lesson's action is done (as on the iPad, it goes when Continue is enabled).
        if (TouchMode.enabled && !controller.canContinue) drawHand(game, step.id)
        g.popTransform()
    }

    private val hudOffset get() = if (TouchMode.enabled) TouchMode.hudX else 0

    // The hand that shows a drag: it slides, rests half a second, hides half a second and starts over.
    private var handStep: String? = null
    private var handX = 0f
    private var handY = 0f
    private var handTimer = -1f

    private fun drawHand(game: InGameState, stepId: String) {
        val path = HAND_PATHS[stepId] ?: return
        if (handStep != stepId) {
            handStep = stepId
            handX = path[0]
            handY = path[1]
            handTimer = -1f
        }
        val dt = game.renderingDeltaTime
        if (handTimer <= 0f) {
            val dx = path[2] - handX
            val dy = path[3] - handY
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val speed = if (d < 30f) max(0.05f, d / 30f * 0.25f) * 5f else 1.25f
            val move = speed * dt * 16f
            if (d <= move) {
                handX = path[2]
                handY = path[3]
                if (handTimer < 0f) handTimer = 1f
            } else {
                handX += dx / d * move
                handY += dy / d * move
            }
        } else {
            handTimer -= dt
            if (handTimer < 0f) {
                handX = path[0]
                handY = path[1]
            }
        }
        if (handTimer <= 0f || handTimer >= 0.5f) game.content.getImg("img/ipad/hand.png").draw(handX, handY)
    }

    /**
     * Native panel coordinates from Reference_00416930.c:
     * - STAR_MAP_1: (50, 200)
     * - UPGRADES_1, UPGRADES_1_2: (0, 230)
     * - INVENTORY_2: (0, 185)
     * - COMBAT_*: (520, 3)
     * - Default: (900, 50)
     */
    fun getBoxPosition(stepId: String): Pair<Int, Int> = if (TouchMode.enabled) touchBoxPosition(stepId) else when {
        stepId == "STAR_MAP_1" -> 50 to 200
        stepId == "UPGRADES_1" || stepId == "UPGRADES_1_2" -> 0 to 230
        stepId == "INVENTORY_2" -> 0 to 185
        stepId.startsWith("COMBAT_") && stepId != "COMBAT_5" -> 520 to 3
        else -> 900 to 50
    }

    /** The iPad's places: top right by default, below the ship and the equipment on the steps that use those screens. */
    private fun touchBoxPosition(stepId: String): Pair<Int, Int> = when {
        stepId == "UPGRADES_1" -> 643 to 504
        stepId == "UPGRADES_1_2" || stepId == "INVENTORY_2" -> 643 to 552
        stepId == "INVENTORY_1" || stepId == "COMBAT_5" -> 665 to 50
        stepId.startsWith("COMBAT_") -> 352 to 504
        else -> 644 to 50
    }

    private fun getWrappedLinesAndHeight(game: InGameState, step: BrowserTutorialDefinition.Step): Pair<List<String>, Int> {
        if (step.id == cachedStepId) {
            return cachedLines to cachedHeight
        }
        val font = game.content.getFont(INSTRUCTION_FONT_NAME)
        val rawText = game.content.translator[step.text]
        val lines = font.wrapString(rawText, TEXT_WRAP_WIDTH)
        val textHeight = lines.size * TEXT_LINE_SPACING
        // TutorialManager::UpdateDescBox (0x00410930): text height + 22, at least 212. The iPad game's panel has the
        // same rule (its text measured with the same font and width, at least 212 high), so touch keeps it too.
        val height = max(MIN_BOX_HEIGHT, textHeight + 22)

        cachedStepId = step.id
        cachedLines = lines
        cachedHeight = height
        return lines to height
    }

    companion object {
        private val HAND_PATHS = mapOf(
            "POWER_SYSTEM_2" to floatArrayOf(215f, 700f, 215f, 630f),
            "IPAD_TOOLTIPS" to floatArrayOf(235f, 671f, 300f, 671f),
            // As on the iPad: a drag across the crew where the lesson adds them.
            "DAMAGE_SYSTEM_2" to floatArrayOf(635f, 320f, 712f, 320f),
        )

        const val BOX_WIDTH = 323
        const val FRAME_OFFSET_X = 7
        const val TEXT_WRAP_WIDTH = 308
        const val MIN_BOX_HEIGHT = 212
        const val TEXT_PADDING_X = 18
        const val TEXT_PADDING_Y = 15
        const val TEXT_BASELINE_OFFSET = 12
        const val TEXT_LINE_SPACING = 20
        const val CONTINUE_OFFSET_X = 83
        const val CONTINUE_OFFSET_Y = 3
        const val CONTINUE_WIDTH = 170
        const val CONTINUE_HEIGHT = 32
        const val CONTINUE_IMAGE_OFFSET_X = 23
        const val CONTINUE_IMAGE_OFFSET_Y = 7
        const val ARROW_WIDTH = 164
        const val ARROW_HEIGHT = 38
        const val INSTRUCTION_FONT_NAME = "JustinFont11Bold"
    }
}
