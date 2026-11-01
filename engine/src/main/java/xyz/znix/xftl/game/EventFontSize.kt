package xyz.znix.xftl.game

import xyz.znix.xftl.Languages

/**
 * The event box's text size, ChoiceBox::fontSize of CommandGui's choice box: the native font ID its text and its
 * choices print in. The box is made once with the game, so the size starts at 12 and lasts for the session (it is
 * not saved). The increase_font and decrease_font hotkeys (CommandGui::KeyDown) step it through 9, 10,
 * 12 and 13; Japanese has no 10. ChoiceBox::OnRender clamps it to 9..13 and
 * keeps the clamped value. Hyperspace scripts read and write it as ChoiceBox.fontSize.
 */
object EventFontSize {
    @JvmField
    var size = 12

    private val japanese get() = Languages.current == "ja"

    /** One size up: 10 and (Japanese) 9 go to 12, anything below 13 one up. */
    @JvmStatic
    fun increase() {
        size = when {
            size == 10 -> 12
            japanese && size == 9 -> 12
            size < 13 -> size + 1
            else -> size
        }
    }

    /** One size down: 12 goes to 10 (Japanese 9), anything above 9 one down. */
    @JvmStatic
    fun decrease() {
        size = when {
            size == 12 -> if (japanese) 9 else 10
            size > 9 -> size - 1
            else -> size
        }
    }

    /** ChoiceBox::OnRender's clamp, written back to the box. */
    @JvmStatic
    fun clamped(): Int {
        size = size.coerceIn(9, 13)
        return size
    }
}
