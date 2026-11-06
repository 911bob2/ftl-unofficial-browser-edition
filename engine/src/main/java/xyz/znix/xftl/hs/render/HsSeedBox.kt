// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/Seeds.cpp (SeedInputBox, the ShipBuilder
// hooks) and src/game/UserInterface/CustomShipSelect.cpp (ShipBuilder::OnRender's seed box and "unlocks disabled"
// overlay); the browser engine is AGPL-3.0, see docs/HYPERSPACE.md.
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.f
import xyz.znix.xftl.hangar.HangarLayout
import xyz.znix.xftl.hangar.SelectShipState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input

/**
 * The hangar's seed field (`SeedInputBox::seedInput`, a `TextInput(10, ALLOW_ANY)` at (1079, 71), 158x21). Typing a
 * seed there seeds the run (StarMap::NewGame hook, [HsSeeds.newGame]); the START button sits 13 px higher than in
 * the vanilla hangar (ShipBuilder::Open hook: `startButton.position.y = 15`). Gated on [HS.active] by the caller.
 */
class HsSeedBox(private val state: SelectShipState) {
    var text = ""
        private set
    var active = false
        private set
    private var cursor = 0

    /** `SeedInputBox::firstClick`: true while the prompt text ("Seed") is still shown in the empty field. */
    private var firstClick = true

    /** TextInput's blink timer: 1 s loop, restarted whenever the cursor moves (as the name field's). */
    private var blink = 0f
    private var blinkCursor = -1

    private val digitsFont = NativeFonts.font(state::getFont, 0)
    private val promptFont = NativeFonts.font(state::getFont, 14)

    val enabled get() = HsSeeds.enabled

    private val prompt: String
        get() = state.translator[if (enabled) "seed_prompt" else "seed_prompt_disabled"]

    private val left get() = X + HangarLayout.startDx
    private val top get() = Y + HangarLayout.startDy

    /** The seed typed, or null for a random run (`customSeed = !(str == "" || unk)`). */
    fun seedOrNull(): Long? = if (enabled && text.isNotEmpty()) text.toLong() else null

    fun update(delta: Float) {
        if (cursor != blinkCursor) { blinkCursor = cursor; blink = 0f }
        blink += delta
        if (blink > 1f) blink = 0f
    }

    /**
     * ShipBuilder::MouseClick hook: a click in the field starts typing, any other click stops it. Returns true when
     * the click goes no further (the field is active; the hook returns before the original).
     */
    /** The field is at ([x], [y]) (TouchRouter: a tap there starts typing). */
    fun hits(x: Int, y: Int): Boolean = enabled && x > left && x < left + WIDTH && y > top && y < top + HEIGHT

    fun click(x: Int, y: Int): Boolean {
        if (!enabled) return false
        val wasActive = active
        if (x > left && x < left + WIDTH && y > top && y < top + HEIGHT) {
            if (firstClick) firstClick = false
            active = true
            cursor = text.length
            blinkCursor = -1
        } else {
            active = false
        }
        if (HangarLayout.touch) {
            xyz.znix.xftl.game.TouchMode.keyboardWanted = active
            xyz.znix.xftl.game.TouchMode.keyboardFocusY = top + HEIGHT
        }
        if (active) return true
        if (text.isEmpty()) firstClick = true
        // The field swallows mouse moves while active, so the original's hover state is stale when the click that
        // stops it reaches the buttons: it acts on nothing (the oracle driver clicks empty space first).
        return wasActive
    }

    /**
     * CApp::OnTextInput / OnTextEvent hooks while the field is active: digits are typed (a value above 4294967295
     * becomes 4294967295, at most ten characters), the editing keys act on the field and everything else is swallowed
     * (ShipBuilder::OnKeyDown returns, so Escape does not leave the hangar). Returns true when the key was consumed.
     */
    fun key(key: Int, c: Char): Boolean {
        if (!active) return false
        when (key) {
            Input.KEY_ENTER, Input.KEY_ESCAPE -> stop()
            Input.KEY_LEFT -> cursor = (cursor - 1).coerceAtLeast(0)
            Input.KEY_RIGHT -> cursor = (cursor + 1).coerceAtMost(text.length)
            Input.KEY_HOME -> cursor = 0
            Input.KEY_END -> cursor = text.length
            Input.KEY_BACK -> if (cursor > 0) { text = text.removeRange(cursor - 1, cursor); cursor-- }
            Input.KEY_DELETE -> if (cursor < text.length) text = text.removeRange(cursor, cursor + 1)
            else -> if (c in '0'..'9' && text.length < MAX_LENGTH) {
                text = text.substring(0, cursor) + c + text.substring(cursor)
                cursor++
                if (text.toLong() > MAX_SEED) { text = MAX_SEED.toString(); cursor = text.length }
            }
        }
        return true
    }

    private fun stop() {
        active = false
        if (HangarLayout.touch) xyz.znix.xftl.game.TouchMode.keyboardWanted = false
        if (text.isEmpty()) firstClick = true
    }

    /** CustomShipSelect::OnRender: the "unlocks disabled" overlay with a seed typed, then the field's text or prompt. */
    fun draw(g: Graphics) {
        if (text.isNotEmpty()) {
            if (!HS.cfg().seedsAllowUnlocks) {
                val img = state.getImgOrNull("img/customizeUI/unlocks_disabled.png")
                if (img != null) img.draw((1106 + HangarLayout.startDx - img.width / 2).f, 104f + HangarLayout.startDy)
            }
            drawLine(g, digitsFont, text, top + 5)
        } else {
            drawLine(g, promptFont, if (firstClick) prompt else "", top - 6)
        }
    }

    /** TextInput::OnRender: the text centred on the field (x + 2 + width/2) from [top], the cursor bar while active. */
    private fun drawLine(g: Graphics, font: NativeFonts.NativeFont, value: String, top: Int) {
        val centre = left + 2 + WIDTH / 2
        if (value.isNotEmpty()) font.printCenter(centre.f, top.f, value, Colour.white)
        // TextInput::OnRender (0x004ff2b0): a 2px (255,255,50) bar ending at the cursor, 10% to 90% of the line height.
        if (active && blink < 0.5f) {
            val left = centre - font.width(value) / 2f
            val h = font.lineHeight
            g.colour = Colour(255, 255, 50)
            g.fillRect(left + font.advance(value.take(cursor)) - 2f, top + 0.1f * h, 2f, 0.8f * h)
        }
    }

    companion object {
        const val X = 1079
        const val Y = 71
        const val WIDTH = 158
        const val HEIGHT = 21
        private const val MAX_LENGTH = 10
        private const val MAX_SEED = 4294967295L

        /** `startButton.position.y = 15` (ShipBuilder::Open hook, Seeds.cpp) in place of the vanilla 28. */
        const val START_Y = 15
    }
}
