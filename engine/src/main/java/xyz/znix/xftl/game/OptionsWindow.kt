package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input
import kotlin.math.roundToInt

/**
 * The desktop OptionsScreen. It is a ChoiceBox (0x004fc0f0, options branch) drawn on
 * optionsUI_new.png at (227,140): VIDEO choices from (+35,+87), GAMEPLAY 400px to
 * the right, 34px apart; OptionsScreen::OnRender (0x0058bf90) adds the volume bars
 * at (+330,+341/+391) and a right-anchored CLOSE TextButton, and the title menu's
 * OnInit (0x0058a5a0) a DELETE PROFILE button. Native options the browser port does
 * not implement open a Work in progress notice instead.
 */
class OptionsWindow(
    val context: SettingsContext, val close: () -> Unit, val openControls: () -> Unit,
    private val titleMenu: Boolean = false
) : Window() {
    constructor(game: InGameState, close: () -> Unit) : this(SettingsContext(game), close, game.shipUI::showKeybindWindow)

    private val art = context.getImg("img/optionsUI_new.png")
    private val closeArt = context.getImg("img/storeUI/store_close_base.png")
    private val deleteArt = context.getImg("img/optionsUI/button_deleteprofile_base.png")
    private val choiceFont = NativeFonts.font(context::getFont, 13)
    private val labelFont = NativeFonts.font(context::getFont, 13)
    private val frameFont = NativeFonts.font(context::getFont, 62)
    private val buttonFont = NativeFonts.font(context::getFont, 63)
    private val title = context.translator["options_tab"]
    private val titleFont = if (buttonFont.measure(title) > 144) frameFont else buttonFont

    private enum class Row(val column: Int) {
        FULLSCREEN(0), V_SYNC(0), FRAME_LIMIT(0), DYNAMIC_BACK(0), COLORBLIND(0), LANGUAGE(0),
        EVENT_CHOICE(1), SHOW_PATHS(1), ACHIEVEMENT_POPUP(1), WINDOW_FOCUS(1), CONTROLS(1),
    }

    private val rows = Row.entries

    /** A volume SlideBar: a 200x20 bar and a 15x30 knob kept 5px inside it. */
    private inner class VolumeBar(val y: Int, value: Float) {
        val x = 330
        val minKnob = x + 5
        val maxKnob = x + 200 - 5 - 15
        var knob = (value * (maxKnob - minKnob) + minKnob).toInt()
        var dragging = false
        private var grabX = 0
        private var grabKnob = 0
        val value get() = ((knob - minKnob).f / (maxKnob - minKnob)).coerceIn(0f, 1f)

        /** SlideBar::MouseMove (0x005829c0): a press on the bar centres the knob there. */
        fun press(mx: Int, my: Int): Boolean {
            if (mx <= x || mx >= x + 200 || my <= y || my >= y + 20) return false
            if (mx <= knob || mx >= knob + 15 || my <= y - 5 || my >= y + 25) knob = mx - 7
            dragging = true
            grabX = mx
            grabKnob = knob
            return true
        }

        fun drag(mx: Int) {
            if (dragging) knob = (mx - grabX + grabKnob).coerceIn(minKnob, maxKnob)
        }

        fun draw(g: Graphics, ox: Int, oy: Int) {
            g.colour = Colour(0f, 0f, 0f, .5f)
            g.fillRect(ox + x, oy + y, 200, 20)
            g.colour = Colour.white
            g.fillRect(ox + x, oy + y, 200, 2)
            g.fillRect(ox + x, oy + y + 18, 200, 2)
            g.fillRect(ox + x, oy + y, 2, 20)
            g.fillRect(ox + x + 198, oy + y, 2, 20)
            g.colour = if (dragging) Colour(1f, 1f, 0f, 1f) else Colour.white
            g.fillRect(ox + knob, oy + y - 5, 15, 30)
        }
    }

    private val sound = VolumeBar(341, context.mainGame.profile.soundVolume)
    private val music = VolumeBar(391, context.mainGame.profile.musicVolume)
    private val bars = listOf(sound, music)

    private val closeLabel = context.translator["button_close"]
    private val closeWidth = maxOf(buttonFont.measure(closeLabel) + 6, 0x67)
    private val closePos = ConstPoint(792 - closeWidth, 301)
    private val deleteLabel = context.translator["button_delete_profile"]
    private val deletePos = ConstPoint(24, 334)

    private var notice: NoticeWindow? = null
    val diagnosticActiveWindow: Window get() = notice ?: this
    val diagnosticNotice: String get() = notice?.diagnosticState ?: "null"
    private var hoveredRow = -1
    private var focusedRow = -1
    private var closeHovered = false
    private var deleteHovered = false

    override val size: IPoint = ConstPoint(art.width, art.height)
    override val windowCentreOffset: IPoint get() = ConstPoint(0, 140 - (720 - art.height) / 2)

    private fun text(key: String, value: String? = null): String {
        val raw = context.translator[key]
        return if (value == null) raw else raw.replace("\\1", value)
    }

    private fun onOff(on: Boolean) = context.translator[if (on) "on" else "off"]
    private fun enabled(on: Boolean) = context.translator[if (on) "enabled" else "disabled"]

    private fun rowText(row: Row): String {
        val profile = context.mainGame.profile
        return when (row) {
            Row.FULLSCREEN -> text("fullscreen", context.translator[if (ftl.browser.BrowserEngine.isFullscreen()) "fullscreen_2" else "fullscreen_0"])
            Row.V_SYNC -> text("v_sync", onOff(false))
            // The browser's draw cap stands in for the native On/Off frame limit.
            Row.FRAME_LIMIT -> text("frame_limit", if (profile.frameRateLimit == 0) "Unlimited" else profile.frameRateLimit.toString())
            Row.DYNAMIC_BACK -> text("dynamic_back", onOff(true))
            Row.COLORBLIND -> text("colorblind", enabled(false))
            Row.LANGUAGE -> text("change_language")
            Row.EVENT_CHOICE -> text("event_choice", context.translator[profile.eventChoiceHotkeys.textKey])
            Row.SHOW_PATHS -> text("show_paths", enabled(profile.showBeaconPaths))
            Row.ACHIEVEMENT_POPUP -> text("achievement_popup", enabled(profile.achievementPopups))
            Row.WINDOW_FOCUS -> text("window_focus", onOff(profile.autoPauseOnFocusLoss))
            Row.CONTROLS -> text("configure_controls")
        }
    }

    private fun rowX(row: Row) = 35 + row.column * 400
    private fun rowY(row: Row) = 87 + 34 * rows.filter { it.column == row.column }.indexOf(row)

    /** The ChoiceBox hit box: the text's extent, from 10px above it to the next row. */
    private fun rowAt(x: Int, y: Int): Int {
        val lx = x - position.x
        val ly = y - position.y
        return rows.indexOfFirst { row ->
            lx >= rowX(row) && lx < rowX(row) + choiceFont.measure(rowText(row)) &&
                    ly >= rowY(row) - 10 && ly < rowY(row) + 24
        }
    }

    private fun inClose(x: Int, y: Int) = x - position.x in closePos.x until closePos.x + closeWidth &&
            y - position.y in closePos.y until closePos.y + 32

    private fun inDelete(x: Int, y: Int) = titleMenu && x - position.x in deletePos.x until deletePos.x + 131 &&
            y - position.y in deletePos.y until deletePos.y + 60

    override fun draw(g: Graphics) {
        val ox = position.x
        val oy = position.y
        art.draw(ox.f, oy.f)

        val frameText = Colour(25, 49, 51)
        titleFont.printCenter(ox + 126f, oy + 11f, title, frameText)
        frameFont.print(ox + 25f, oy + 47f, context.translator["options_frame_video"], frameText)
        frameFont.print(ox + 423f, oy + 47f, context.translator["options_frame_gameplay"], frameText)
        frameFont.print(ox + 186f, oy + 294f, context.translator["options_frame_audio"], frameText)

        for ((i, row) in rows.withIndex()) {
            val colour = if (i == hoveredRow || i == focusedRow) OPTION_HOVER else Colour.white
            choiceFont.print((ox + rowX(row)).f, (oy + rowY(row)).f, rowText(row), colour)
        }

        labelFont.printCenter(ox + 256f, oy + 344f, context.translator["sound_volume"], Colour.white)
        labelFont.printCenter(ox + 256f, oy + 394f, context.translator["music_volume"], Colour.white)
        bars.forEach { it.draw(g, ox, oy) }

        closeArt.draw((ox + closePos.x - 23).f, (oy + closePos.y - 7).f)
        drawTextButton(g, ox + closePos.x, oy + closePos.y, closeWidth, 32, 4, closeLabel, closeHovered)
        if (titleMenu) {
            deleteArt.draw((ox + deletePos.x - 15).f, (oy + deletePos.y - 15).f)
            drawTextButton(g, ox + deletePos.x, oy + deletePos.y, 131, 60, 8, deleteLabel, deleteHovered)
        }

        notice?.let {
            g.tooltip = null
            g.colour = Colour(0f, 0f, 0f, .65f)
            g.fillRect(0, 0, context.mainGame.gameContainer.width, context.mainGame.gameContainer.height)
            it.draw(g)
        }
    }

    private fun drawTextButton(g: Graphics, x: Int, y: Int, w: Int, h: Int, inset: Int, label: String, hovered: Boolean) {
        g.colour = if (hovered) Constants.TEXT_BUTTON_HOVER else Constants.TEXT_BUTTON_NORMAL
        Buttons.drawChamfer(g, x, y, w, h, inset)
        buttonFont.printButtonLabel(x, y, w, h, label, Constants.TEXT_BUTTON_TEXT)
    }

    override fun updateUI(x: Int, y: Int) {
        notice?.let { it.updateUI(x, y); return }
        val hovered = rowAt(x, y)
        val close = inClose(x, y)
        val delete = inDelete(x, y)
        if ((hovered >= 0 && hovered != hoveredRow) || (close && !closeHovered) || (delete && !deleteHovered))
            context.mainGame.gameContent.sounds.getSample("hoverBeep").play()
        hoveredRow = hovered
        closeHovered = close
        deleteHovered = delete
        if (context.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)) {
            bars.forEach { it.drag(x - position.x) }
            if (bars.any { it.dragging }) updateVolumes()
        } else {
            releaseBars()
        }
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        notice?.let { it.mouseClick(button, x, y); return }
        if (button != Input.MOUSE_LEFT_BUTTON) return
        if (inClose(x, y)) { escapePressed(); return }
        if (inDelete(x, y)) { showWorkInProgress(deleteLabel.replace("\\n", " ").replace("\n", " "), "delete_profile"); return }
        if (bars.any { it.press(x - position.x, y - position.y) }) { updateVolumes(); return }
        val row = rowAt(x, y)
        if (row >= 0) {
            focusedRow = -1
            choose(rows[row])
        }
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) = releaseBars()

    private fun releaseBars() {
        if (bars.none { it.dragging }) return
        bars.forEach { it.dragging = false }
        updateVolumes()
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        notice?.let { return it.onTextInput(key, c) }
        if (key == Input.KEY_TAB) {
            val reverse = context.input.isKeyDown(Input.KEY_LSHIFT) || context.input.isKeyDown(Input.KEY_RSHIFT)
            focusedRow = if (focusedRow < 0) (if (reverse) rows.size - 1 else 0)
            else (focusedRow + if (reverse) rows.size - 1 else 1) % rows.size
            return true
        }
        val row = rows.getOrNull(focusedRow.takeIf { it >= 0 } ?: hoveredRow) ?: return false
        if (key !in listOf(Input.KEY_ENTER, Input.KEY_SPACE, Input.KEY_LEFT, Input.KEY_RIGHT)) return false
        choose(row, if (key == Input.KEY_LEFT) -1 else 1)
        return true
    }

    /** OptionsScreen::CheckSelection (0x005896e0). */
    private fun choose(row: Row, direction: Int = 1) {
        val profile = context.mainGame.profile
        when (row) {
            Row.FULLSCREEN -> { ftl.browser.BrowserEngine.toggleFullscreen(); return }
            Row.V_SYNC, Row.DYNAMIC_BACK, Row.COLORBLIND, Row.LANGUAGE -> {
                showWorkInProgress(rowText(row).substringBefore(':'), row.name.lowercase())
                return
            }
            Row.FRAME_LIMIT -> { cycleFrameLimit(direction); return }
            Row.CONTROLS -> { releaseBars(); openControls(); return }
            Row.EVENT_CHOICE -> profile.eventChoiceHotkeys = EventChoiceHotkeyMode.fromId((profile.eventChoiceHotkeys.id + direction + 3) % 3)
            Row.SHOW_PATHS -> profile.showBeaconPaths = !profile.showBeaconPaths
            Row.ACHIEVEMENT_POPUP -> profile.achievementPopups = !profile.achievementPopups
            Row.WINDOW_FOCUS -> { profile.autoPauseOnFocusLoss = !profile.autoPauseOnFocusLoss; context.mainGame.refreshFocusPause() }
        }
        profile.markDirty()
        ftl.browser.BrowserDebug.record("ui", "settings.gameplay",
            "{\"achievementPopups\":${profile.achievementPopups},\"autoPause\":${profile.autoPauseOnFocusLoss},\"beaconPaths\":${profile.showBeaconPaths},\"dialogKeys\":${profile.eventChoiceHotkeys.id}}")
    }

    private fun showWorkInProgress(title: String, feature: String) {
        releaseBars()
        hoveredRow = -1
        notice = NoticeWindow(context, title, "Work in progress.") { notice = null }.also {
            it.position = ConstPoint(
                (context.mainGame.gameContainer.width - it.size.x) / 2,
                (context.mainGame.gameContainer.height - it.size.y) / 2)
        }
        ftl.browser.BrowserDebug.event("ui", "notice.open", "feature", feature)
    }

    /**
     * Steps the draw cap through [FRAME_LIMITS]; a cap saved from elsewhere moves to
     * the nearest listed step in that direction.
     */
    private fun cycleFrameLimit(direction: Int) {
        val current = context.mainGame.profile.frameRateLimit
        val index = FRAME_LIMITS.indexOf(current)
        val next = when {
            index >= 0 -> FRAME_LIMITS[(index + direction + FRAME_LIMITS.size) % FRAME_LIMITS.size]
            direction > 0 -> FRAME_LIMITS.firstOrNull { it != 0 && it > current } ?: 0
            else -> FRAME_LIMITS.lastOrNull { it != 0 && it < current } ?: 0
        }
        applyFrameLimit(next)
    }

    private fun applyFrameLimit(value: Int) {
        val profile = context.mainGame.profile
        if (profile.frameRateLimit == value) return
        profile.frameRateLimit = value
        profile.markDirty()
        ftl.browser.BrowserDebug.record("ui", "settings.fps", "{\"limit\":$value}")
    }

    override fun escapePressed() {
        notice?.let { it.escapePressed(); return }
        releaseBars()
        updateVolumes()
        context.mainGame.profile.markDirty()

        close()
    }

    private fun updateVolumes() {
        context.mainGame.profile.soundVolume = sound.value
        context.mainGame.profile.musicVolume = music.value
        context.mainGame.profile.markDirty()
        context.mainGame.applyAudioSettings()
    }

    companion object {
        /** The Frame Limit row's caps in click order; 0 is Unlimited. */
        private val FRAME_LIMITS = intArrayOf(30, 60, 90, 120, 240, 360, 0)

        /** ChoiceBox::OnRender's hovered choice colour. */
        private val OPTION_HOVER = Colour(243, 255, 80)
    }
}
