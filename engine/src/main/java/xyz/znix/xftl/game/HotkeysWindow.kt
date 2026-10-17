package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input

/**
 * The desktop ControlsScreen, opened from Options. Laid out by its OnInit
 * (0x005898c0) and drawn by ControlsScreen::OnRender (0x0058b110): a WindowFrame at
 * (130,112) 1089x500, four page tabs, and per page the native Settings hotkey lists
 * as 105x25 boxes in two columns from (480,150), 440 apart and 40px per row, with
 * their labels right-aligned 5px left of the box. Hotkeys the port has no action for
 * (event font size, power by system slot) open a Work in progress notice instead.
 */
class HotkeysWindow(val game: SettingsContext, val close: () -> Unit) : Window() {
    constructor(game: InGameState, close: () -> Unit) : this(SettingsContext(game), close)

    override val size: IPoint = ConstPoint(1089, 500)
    override val windowCentreOffset: IPoint = ConstPoint(130 - (1280 - 1089) / 2, 112 - (720 - 500) / 2)

    private val labelFont = NativeFonts.font(game::getFont, 13)
    private val keyFont = NativeFonts.font(game::getFont, 12)
    private val tabFont = NativeFonts.font(game::getFont, 62)
    private val buttonFont = NativeFonts.font(game::getFont, 63)
    private val titleTab = game.getImg("img/box_options_configure_tab.png")
    private val tabOff = game.getImg("img/optionsUI/tab_page_off.png")
    private val tabOn = game.getImg("img/optionsUI/tab_page_on.png")
    private val tabHover = game.getImg("img/optionsUI/tab_page_select2.png")
    private val defaultsArt = game.getImg("img/optionsUI/defaults_base.png")
    private val closeArt = game.getImg("img/storeUI/store_close_base.png")

    private val title = game.translator["configure_controls_tab"]
    private val defaultsLabel = game.translator["button_defaults"]
    private val closeLabel = game.translator["button_close"]
    private val closeWidth = maxOf(buttonFont.measure(closeLabel) + 6, 0x67)

    /** Settings' hotkey pages, in the original's order (read from the live game). */
    private val pages: List<List<String>> = listOf(
        (1..8).map { "crew$it" } + listOf(
            "crew_all", "loadPositions", "savePositions", "lockdown", "pause", "jump", "ship_info",
            "ship_inv", "store", "ship_crew", "options", "increase_font", "decrease_font"
        ),
        listOf(
            "open", "close", "activate_cloak", "send_tele", "ret_tele", "start_hacking", "mindControl",
            "activate_battery", "drone1", "drone2", "drone3", "weapon1", "weapon2", "weapon3", "weapon4",
            "autofire", "force_autofire"
        ),
        SYSTEMS + SYSTEMS.map { "un_$it" },
        (1..8).map { "power_$it" } + (1..8).map { "un_power_$it" },
    )

    private val hotkeys = game.hotkeyManager.groups.flatMap { it.hotkeys }.associateBy { it.uiName }

    private inner class ControlBox(val name: String, val x: Int, val y: Int) {
        val hotkey: Hotkey? = hotkeys["hotkey_$name"]
        val label = game.translator["hotkey_$name"]
        val labelWidth = labelFont.measure(label)

        fun contains(lx: Int, ly: Int) = lx > x && lx < x + 105 && ly > y && ly < y + 25

        fun keyText(): String {
            if (hotkey == null) return NATIVE_ONLY_KEYS[name] ?: UNBOUND
            val button = game.reverseHotkeyBindings[hotkey] ?: return UNBOUND
            return game.translator[button.text].uppercase()
        }
    }

    private val boxes: List<List<ControlBox>> = pages.mapIndexed { page, names ->
        var split = (names.size + 1) / 2
        if (page == 1) split--
        names.mapIndexed { i, name ->
            val right = i >= split
            ControlBox(name, 480 + if (right) 440 else 0, 150 + (if (right) i - split else i) * 40)
        }
    }

    private var page = 0
    private var hovered: ControlBox? = null
    private var listening: ControlBox? = null
    private var hoveredTab = -1
    private var defaultsHovered = false
    private var closeHovered = false
    private var confirmation: ConfirmationWindow? = null
    private var notice: NoticeWindow? = null
    private var mouseX = 0
    private var mouseY = 0

    init {
        game.updateHotkeyBindings()
    }

    // Native coordinates are screen positions while the frame sits at (130,112).
    private val ox get() = position.x - 130
    private val oy get() = position.y - 112

    override fun draw(g: Graphics) {
        val sceneTint = Graphics.tint
        if (confirmation != null) Graphics.tint = Colour(sceneTint.r * .5f, sceneTint.g * .5f, sceneTint.b * .5f, sceneTint.a)

        game.getWindowRenderer().render(position.x, position.y, size.x, size.y)

        val titleWidth = buttonFont.measure(title)
        val tabTop = (oy + 70).f
        val tabBottom = tabTop + titleTab.height
        titleTab.draw((ox + 123).f, tabTop, (ox + 162).f, tabBottom, 0f, 0f, 39f, titleTab.height.f)
        titleTab.draw((ox + 162).f, tabTop, (ox + 146 + titleWidth).f, tabBottom, 39f, 0f, 40f, titleTab.height.f)
        titleTab.draw((ox + 146 + titleWidth).f, tabTop, (ox + 197 + titleWidth).f, tabBottom, 40f, 0f, 91f, titleTab.height.f)
        buttonFont.print((ox + 154).f, (oy + 81).f, title, FRAME_TEXT)

        for (box in boxes[page]) drawBox(g, box)

        defaultsArt.draw((ox + 160 - 23).f, (oy + 619 - 7).f)
        drawTextButton(g, ox + 160, oy + 619, 165, defaultsLabel, defaultsHovered)

        for (i in 0 until 4) {
            val x = ox + 939 + 66 * i
            val img = when {
                i == page -> tabOff
                i == hoveredTab -> tabHover
                else -> tabOn
            }
            img.draw(x.f, (oy + 83).f)
            tabFont.printCenter((x + 37).f, (oy + 90).f, (i + 1).toString(), FRAME_TEXT)
        }

        if (page >= 2)
            keyFont.print((ox + 467).f, (oy + 625).f, game.translator["power_note"], Colour.white)

        closeArt.draw((ox + 1190 - closeWidth - 23).f, (oy + 619 - 7).f)
        drawTextButton(g, ox + 1190 - closeWidth, oy + 619, closeWidth, closeLabel, closeHovered)

        Graphics.tint = sceneTint
        confirmation?.draw(g)
        notice?.let {
            g.colour = Colour(0f, 0f, 0f, .65f)
            g.fillRect(0, 0, game.mainGame.gameContainer.width, game.mainGame.gameContainer.height)
            it.draw(g)
        }
    }

    /** ControlButton's render (0x00584040). */
    private fun drawBox(g: Graphics, box: ControlBox) {
        val x = ox + box.x
        val y = oy + box.y
        val active = box === listening
        g.colour = if (active) Colour(125, 125, 125, 128) else Colour(0.1961f, 0.1961f, 0.1961f, .5f)
        g.fillRect(x, y, 105, 25)
        g.colour = if (active || box === hovered) HOVER_OUTLINE else Colour.white
        g.fillRect(x, y, 105, 2)
        g.fillRect(x, y + 23, 105, 2)
        g.fillRect(x, y, 2, 25)
        g.fillRect(x + 103, y, 2, 25)
        val keyText = if (active) game.translator["press_key"] else box.keyText()
        keyFont.printCenter((x + 105 / 2 + 1).f, (y + 6).f, keyText, Colour.white)
        labelFont.print((x - box.labelWidth - 5).f, (y + 6).f, box.label, Colour.white)
    }

    private fun drawTextButton(g: Graphics, x: Int, y: Int, w: Int, label: String, hovered: Boolean) {
        g.colour = if (hovered) Constants.TEXT_BUTTON_HOVER else Constants.TEXT_BUTTON_NORMAL
        Buttons.drawChamfer(g, x, y, w, 32, 4)
        buttonFont.printButtonLabel(x, y, w, 32, label, Constants.TEXT_BUTTON_TEXT)
    }

    private fun tabAt(lx: Int, ly: Int) =
        (0 until 4).firstOrNull { lx >= 944 + 66 * it && lx < 944 + 66 * it + 64 && ly >= 83 && ly < 83 + 29 } ?: -1

    private fun inDefaults(lx: Int, ly: Int) = lx in 160 until 160 + 165 && ly in 619 until 619 + 32
    private fun inClose(lx: Int, ly: Int) = lx in 1190 - closeWidth until 1190 && ly in 619 until 619 + 32

    override fun updateUI(x: Int, y: Int) {
        mouseX = x
        mouseY = y
        notice?.let { it.updateUI(x, y); return }
        confirmation?.let { it.updateUI(x, y); return }
        val lx = x - ox
        val ly = y - oy
        val box = boxes[page].firstOrNull { it.contains(lx, ly) }
        val tab = tabAt(lx, ly).takeIf { it != page } ?: -1
        val defaults = inDefaults(lx, ly)
        val closing = inClose(lx, ly)
        if ((box != null && box !== hovered) || (tab >= 0 && tab != hoveredTab) ||
            (defaults && !defaultsHovered) || (closing && !closeHovered))
            game.mainGame.gameContent.sounds.getSample("hoverBeep").play()
        hovered = box
        hoveredTab = tab
        defaultsHovered = defaults
        closeHovered = closing
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        notice?.let { it.mouseClick(button, x, y); return }
        confirmation?.let { it.mouseClick(button, x, y); return }
        if (button != Input.MOUSE_LEFT_BUTTON) return
        val lx = x - ox
        val ly = y - oy
        val tab = tabAt(lx, ly)
        if (tab >= 0 && tab != page) {
            listening = null
            page = tab
            hovered = null
            return
        }
        if (inClose(lx, ly)) { escapePressed(); return }
        if (inDefaults(lx, ly)) {
            listening = null
            confirmation = ConfirmationWindow(game, "confirm_reset_keys", 300, accept = {
                confirmation = null
                for (hotkey in hotkeys.values) game.mainGame.profile.resetHotkeyToDefault(hotkey)
                game.updateHotkeyBindings()
            }, cancel = { confirmation = null }).also {
                it.centre(game.mainGame.gameContainer.width, game.mainGame.gameContainer.height)
            }
            return
        }
        val box = boxes[page].firstOrNull { it.contains(lx, ly) } ?: return
        if (box.hotkey == null) {
            showWorkInProgress(box.label, box.name)
            return
        }
        listening = box
    }

    private fun showWorkInProgress(title: String, feature: String) {
        listening = null
        notice = NoticeWindow(game, title, "Work in progress.") { notice = null }.also {
            it.position = ConstPoint(
                (game.mainGame.gameContainer.width - it.size.x) / 2,
                (game.mainGame.gameContainer.height - it.size.y) / 2)
        }
        ftl.browser.BrowserDebug.event("ui", "notice.open", "feature", feature)
    }

    override fun escapePressed() {
        notice?.let { it.escapePressed(); return }
        confirmation?.let { it.escapePressed(); return }
        if (listening != null) { listening = null; return }
        close()
    }

    /** ControlsScreen's key handler (0x0058ada0): Escape cancels, shift keys are ignored. */
    override fun onTextInput(key: Int, c: Char): Boolean {
        notice?.let { return it.onTextInput(key, c) }
        confirmation?.let { return it.onTextInput(key, c) }
        val box = listening ?: return false
        if (key == Input.KEY_ESCAPE) {
            listening = null
            return true
        }
        if (key == Input.KEY_LSHIFT || key == Input.KEY_RSHIFT) return true
        val button = HotkeyButton.BY_KEY_ID[key] ?: return true
        game.mainGame.profile.setKeybind(box.hotkey!!, button)
        listening = null
        game.updateHotkeyBindings()
        return true
    }

    companion object {
        private val SYSTEMS = listOf(
            "shields", "engines", "oxygen", "medbay", "teleporter", "cloaking", "mind", "hacking",
            "artillery", "weapons", "drones"
        )

        /** Native hotkeys the port does not implement keep their default keys on show. */
        private val NATIVE_ONLY_KEYS = mapOf("increase_font" to "-", "decrease_font" to "=")
        private const val UNBOUND = "----"
        private val FRAME_TEXT = Colour(25, 49, 51)
        private val HOVER_OUTLINE = Colour(255, 255, 50)
    }
}
