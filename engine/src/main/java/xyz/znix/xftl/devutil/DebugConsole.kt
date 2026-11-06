package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.*
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The debug console (docs/CONSOLE.md): one input line, the output above it and the command history on Up and Down. It
 * runs the port's development commands and Hyperspace's console commands alike ([ConsoleRegistry]); the line edits as
 * Hyperspace's TextInput does (Left, Right, Home, End, Backspace, Delete; at most 480 characters; a mod's
 * `<console invertCaps="true"/>` swaps the case of typed letters).
 */
class DebugConsole(var game: InGameState) {
    private val history = ArrayList<String>()
    private var historyCursor: Int = -1

    var continued: ContinuedCommand? = null

    /** The line being typed; setting it puts the cursor at its end. */
    var input: String = ""
        set(value) { field = value; cursor = value.length }

    /** Where typing goes in [input]. */
    var cursor: Int = 0
        private set

    private var completion: AutoCompleter? = null
    private val parameterHints = ArrayList<String>()
    private val overlay: Overlay? get() = continued ?: completion

    private val lines = ArrayList<ILine>()

    val font by lazy { game.getFont("c&c") }

    private var flashTimer: Float = 0f


    // Used for event searches - put them here so they persiste between
    // searches.
    var eventSearchIds = true
    var eventSearchMessageText = true
    var eventSearchOptions = true

    val commands: List<Cmd> = ConsoleRegistry.build(this)

    /** For the diagnostics probes: the line and the cursor. */
    fun debugState(open: Boolean): String = "open=$open text=$currentLine cursor=${if (historyCursor == -1) cursor else currentLine.length}"

    /** For the diagnostics probes: what the panel shows (its class, a picker's kind), its taps, height and scroll. */
    fun debugPanel(): String {
        val o = overlay ?: return "none"
        val kind = (o as? ItemPicker)?.let { " kind=${it.kind.name} tab=${it.layoutTab} shown=${it.entries.size}" } ?: ""
        val v = panelView
        val grid = (o as? ItemPicker)?.gridInfo?.let { " $it" } ?: ""
        return "${o.javaClass.simpleName}$kind hits=${uiCache?.hits?.size ?: 0} height=$panelHeight scroll=${panelScroller.drawn} touch=${uiCache?.touch}" +
            " view=${v?.let { "${it.x},${it.y},${it.w},${it.h}" }} max=${panelScroller.max.roundToInt()} target=${panelScroller.target.roundToInt()}" +
            " moving=${panelScroller.moving} selected=${o is ConsolePanel && o.hasSelection}" +
            " thumb=${barTrack?.let { t -> v?.let { ConsoleUi.thumbArea(t, panelScroller.pos, it.h, panelHeight) } }?.let { "${it.x},${it.y},${it.w},${it.h}" }}$grid"
    }

    /** The output lines as text, oldest first (grid lines joined by tabs). */
    fun outputText(): List<String> = lines.map { it.text }

    private val prompt: String get() = continued?.prompt ?: PROMPT

    private val currentLine: String
        get() = if (historyCursor == -1) input
        else history[historyCursor]

    // ---- drawing (docs/CONSOLE.md, "The window"): the desktop console over the game, or the touch layout's full screen

    private var uiCache: ConsoleUi? = null

    /** The look for this screen: the touch layout under TouchMode (Android), the desktop one otherwise. */
    fun ui(): ConsoleUi {
        val touch = xyz.znix.xftl.game.TouchMode.enabled
        uiCache?.let { if (it.touch == touch && it.game === game) return it }
        return ConsoleUi(game, touch).also { uiCache = it }
    }

    /** The scrolls, in pixels (ConsoleScroller): the panel's (suggestions, picker, reference), the output's, the recent commands'. */
    private val panelScroller = ConsoleScroller()
    private val outputScroller = ConsoleScroller()
    private val chipScroller = ConsoleScroller()
    private var panelKey: Pair<Int, Any?>? = null
    /** As last drawn: the panel's window, its scrolled view, its scroll bar's track (null: all of it fits). */
    private var panelArea: Area? = null
    private var panelView: Area? = null
    private var barTrack: Area? = null
    private var panelHeight = 0
    private var outputArea: Area? = null
    private var chipArea: Area? = null
    /** The picker or reference the touch layout put the keyboard away for. */
    private var keyboardHidFor: ConsolePanel? = null
    private var screenH = 720
    private val titleTab by lazy { game.getImg("img/map/side_beaconmap.png") }

    /** For the checks: each drawn frame's step time (ms) and scroll (panel, output), the last [TRACE_FRAMES]. */
    private val trace = ArrayDeque<String>()
    private var lastDt = 0f

    fun render(gc: GameContainer, g: Graphics) {
        val ui = ui()
        ui.time = flashTimer
        ui.mouseX = gc.input.mouseX
        ui.mouseY = gc.input.mouseY
        ui.pressing = pressed && !dragging && dragTarget != DRAG_BAR
        ui.pressX = pressX
        ui.pressY = pressY
        ui.startFrame()
        screenH = gc.height

        // Cut down the history so it doesn't get too crazy.
        while (lines.size > 1000) {
            lines.removeAt(0)
        }

        // Make sure we're using a suitable auto-completion engine
        updateAutoCompleter()

        if (ui.touch) renderTouch(gc, g, ui) else renderDesktop(gc, g, ui)

        trace.addLast("${(lastDt * 1000).roundToInt()}:${panelScroller.drawn}:${outputScroller.drawn}")
        while (trace.size > TRACE_FRAMES) trace.removeFirst()
    }

    /** The scroll of the last frames, oldest first: `step ms:panel px:output px` each, for the frame-strip checks. */
    fun debugTrace(): String = trace.joinToString(" ")

    /**
     * The desktop: the output and the input line over the top of the screen, a panel's window under them over the
     * dimmed game. A picker or the reference takes most of the screen (the console's top part shrinks to a few lines
     * of output); suggestions the lower half.
     */
    private fun renderDesktop(gc: GameContainer, g: Graphics, ui: ConsoleUi) {
        val w = gc.width
        val h = gc.height
        val panel = overlay as? ConsolePanel
        val topH = if (panel?.big == true) DESKTOP_BIG_TOP else h / 2

        if (panel != null) {
            g.colour = Colour(0, 0, 0, 160)
            g.fillRect(0f, topH.f, w.f, h - topH.f)
        }
        g.colour = ConsoleUi.BACKDROP
        g.fillRect(0f, 0f, w.f, topH.f)
        g.colour = Constants.TEXT_BUTTON_DISABLED
        g.fillRect(0f, topH - 1f, w.f, 1f)

        val field = Area(10, topH - 25, w - 20, 19)
        ui.field(g, field)
        drawInputLine(ui, font, field.x + 6f, ui.baseline(field.y, field.h).f, field.w - 12f)

        outputArea = Area(0, 0, w, field.y - 2)
        drawOutput(g, ui, Area(12, 2, w - 24, field.y - 4))

        if (panel != null) {
            drawPanel(g, ui, panel, Area(18, topH + 16, w - 36, h - topH - 26))
        } else {
            clearPanel()
            overlay?.render(gc, g, topH.f)
        }
    }

    private fun clearPanel() {
        panelArea = null
        panelView = null
        barTrack = null
    }

    /** The prompt and the line, the cursor, the completion's grey rest and the parameter hints; the new commands glow. */
    private fun drawInputLine(ui: ConsoleUi, f: xyz.znix.xftl.SILFontLoader, x0: Float, y: Float, maxWidth: Float) {
        val line = currentLine
        val full = prompt + line
        val shownCursor = if (historyCursor == -1) cursor.coerceIn(0, line.length) else line.length
        val suggestion = overlay?.autoCompleteSuggestion?.takeIf { it.startsWith(line) }?.removePrefix(line) ?: ""
        val hints = parameterHints.joinToString("") { "  $it" }
        // a line wider than its box shows its end
        val cursorX = f.getWidth(prompt + line.take(shownCursor)).f
        val tail = f.getWidth("__")
        val x = if (cursorX + tail > maxWidth) x0 - (cursorX + tail - maxWidth) else x0

        f.drawString(x + f.getWidth(full) + ui.scale, y, suggestion, Colour.lightGray)
        f.drawString(x + f.getWidth(full + suggestion) + ui.scale, y, hints, Colour.lightGray)

        val word = line.substringBefore(' ')
        if (continued == null && word.isNotEmpty() && ConsoleUi.featured(commands, word)) {
            f.drawString(x, y, prompt, Colour.white)
            ConsoleUi.rainbow(f, x + f.getWidth(prompt), y, word, ui.time)
            f.drawString(x + f.getWidth(prompt + word), y, line.substring(word.length), Colour.white)
        } else f.drawString(x, y, full, Colour.white)
        if (flashTimer.rem(FLASH_TIME) > FLASH_TIME / 2) {
            f.drawString(x + cursorX, y, "_", Colour.white)
        }
    }

    /**
     * A panel in the game's window frame (window_base, with its title tab when it has a title): the fixed header, the
     * content scrolled in a view that cuts it at its edges, the scroll bar beside it, the fixed footer.
     */
    private fun drawPanel(g: Graphics, ui: ConsoleUi, panel: ConsolePanel, area: Area) {
        val key = Pair(System.identityHashCode(panel), panel.contentKey)
        if (key != panelKey) {
            if (panelKey?.first != key.first) panelHeight = 0
            panelKey = key
            panelScroller.reset()
        }
        panelArea = area
        val title = panel.title()
        val renderer = game.windowRenderer
        val inner = area.inset(ui.pad + 2)
        var headerArea = inner
        var minHeader = 0
        if (title != null) {
            renderer.renderWithTitleTab(g, titleTab, ui.titleFont, area.x, area.y, area.w, area.h, title)
            // the tab covers the top left corner: the header's first row goes beside it, the content under it
            val tabRight = area.x - 7 + 20 + ui.titleFont.getWidth(title) + 38
            headerArea = Area(tabRight + ui.pad, inner.y, inner.right - tabRight - ui.pad, inner.h)
            minHeader = area.y - 7 + titleTab.height + 4 - inner.y
        } else renderer.render(area.x, area.y, area.w, area.h)

        val headerH = maxOf(minHeader, panel.drawHeader(g, ui, headerArea))
        val footerH = panel.footerHeight(ui)
        val view = Area(inner.x, inner.y + headerH, inner.w - ui.barHitW, inner.h - headerH - footerH)
        panelView = view
        panelScroller.setExtent(panelHeight, view.h)
        ui.clipped(g, view) { panelHeight = panel.draw(g, ui, view, panelScroller.drawn) }
        panelScroller.setExtent(panelHeight, view.h)

        val track = Area(inner.right - ui.barW, view.y, ui.barW, view.h)
        barTrack = if (panelHeight > view.h) track else null
        val barHot = !ui.touch && barHitArea(track).contains(ui.mouseX, ui.mouseY)
        ui.scrollBar(g, track, panelScroller.pos, view.h, panelHeight, barHot, pressed && dragTarget == DRAG_BAR)
        if (footerH > 0) panel.drawFooter(g, ui, Area(inner.x, view.bottom, inner.w, footerH))
    }

    private fun barHitArea(track: Area) = Area(track.right - ui().barHitW, track.y, ui().barHitW, track.h)

    /**
     * The touch layout (Android): the whole screen, a little transparent. At the top the input line and its buttons,
     * which the host keeps above its keyboard (TouchMode.keyboardFocusY); under them the recent commands to tap and
     * the last output line; the rest, down to the keyboard, holds the suggestions, a picker, the reference or the output.
     */
    private fun renderTouch(gc: GameContainer, g: Graphics, ui: ConsoleUi) {
        val w = gc.width
        val keyboard = xyz.znix.xftl.game.TouchMode.keyboardTop
        val bottom = if (xyz.znix.xftl.game.TouchMode.keyboardWanted && keyboard in 1 until gc.height) keyboard else gc.height
        g.colour = ConsoleUi.BACKDROP
        g.fillRect(0f, 0f, w.f, gc.height.f)

        // the bar: the line and the buttons
        val barH = TOUCH_BAR_H
        val labels = listOf("Clear", "Tab", "Run", "Help", "Keys", "Close")
        var bx = w - 10
        // a narrow screen (4:3) keeps room for the line with smaller labels
        val buttonFont = if (w >= 1400) ui.font else ui.small
        val buttonAreas = ArrayList<Area>()
        for (label in labels.reversed()) {
            val bw = buttonFont.getWidth(label) + ui.pad * 4
            bx -= bw
            buttonAreas.add(0, Area(bx, 8, bw, barH - 16))
            bx -= 8
        }
        for ((label, a) in labels.zip(buttonAreas)) ui.button(g, a, label, on = label == "Keys" && bottom < gc.height, f = buttonFont) { touchButton(label) }
        val box = Area(10, 8, bx - 10, barH - 16)
        ui.field(g, box)
        ui.clipped(g, box.inset(2)) {
            drawInputLine(ui, ui.font, box.x + ui.pad.f, ui.baseline(box.y, box.h).f, box.w - ui.pad * 2f)
        }
        ui.hit(box) { showKeyboard() }

        // A picker or the reference needs the room: the keyboard goes away when one opens (a tap on the line brings it
        // back to search); with the keyboard up the recent commands make way.
        val opened = overlay as? ConsolePanel
        val big = opened?.big == true
        if (big && opened !== keyboardHidFor) { keyboardHidFor = opened; hideKeyboard() }
        if (!big) keyboardHidFor = null
        val compact = bottom < 420

        // recent commands to tap, scrolled sideways with a drag
        val chipsTop = barH
        val chipH = if (compact) 0 else TOUCH_CHIP_H
        val recent = if (compact) emptyList() else history.asReversed().distinct().take(30)
        if (!compact) {
            val label = if (recent.isEmpty()) "Recent commands show here" else "Recent:"
            ui.small.drawString(12f, ui.baseline(chipsTop, chipH, ui.small).f, label, ConsoleUi.DIM)
            val chipsLeft = 24 + ui.small.getWidth(label)
            val strip = Area(chipsLeft, chipsTop, w - chipsLeft - 10, chipH)
            chipArea = strip
            var width = 0
            for (cmd in recent) width += ui.small.getWidth(cmd) + ui.pad * 3 + 8
            chipScroller.setExtent(width, strip.w)
            ui.clipped(g, strip) {
                var cx = chipsLeft - chipScroller.drawn
                for (cmd in recent) {
                    val cw = ui.small.getWidth(cmd) + ui.pad * 3
                    val a = Area(cx, chipsTop + 6, cw, chipH - 12)
                    if (a.right > strip.x && a.x < strip.right) {
                        ui.rowFill(g, a, ui.pressed(a))
                        val word = cmd.substringBefore(' ')
                        val tx = a.x + ui.pad * 1.5f
                        val ty = ui.baseline(a.y, a.h, ui.small).f
                        if (ConsoleUi.featured(commands, word)) {
                            ConsoleUi.rainbow(ui.small, tx, ty, word, ui.time, 0f)
                            ui.small.drawString(tx + ui.small.getWidth(word), ty, cmd.substring(word.length), Colour.white)
                        } else ui.small.drawString(tx, ty, cmd, Colour.white)
                        ui.hit(a) { historyCursor = -1; input = cmd }
                    }
                    cx += cw + 8
                }
            }
        } else chipArea = null

        // the last output line, in view while a panel covers the output
        val statusTop = chipsTop + chipH
        val statusH = if (compact && opened == null) 0 else TOUCH_STATUS_H
        if (statusH > 0) {
            g.colour = Colour(53, 75, 89, 150)
            g.fillRect(0f, statusTop.f, w.f, statusH.f)
            lines.lastOrNull()?.let { last ->
                ui.small.drawStringTruncated(12f, ui.baseline(statusTop, statusH, ui.small).f, w - 24f, last.text.replace('\t', ' '), Colour.white)
            }
        }

        val top = statusTop + statusH + if (statusH > 0 || chipH > 0) 14 else 8
        val area = Area(12, top, w - 24, bottom - top - 10)
        val panel = overlay as? ConsolePanel
        when {
            continued != null -> { clearPanel(); outputArea = null; continued?.render(gc, g, top - 10f) }
            panel != null -> { outputArea = null; drawPanel(g, ui, panel, area) }
            overlay != null -> { clearPanel(); outputArea = null; overlay?.render(gc, g, top - 10f) }
            else -> {
                clearPanel()
                outputArea = area
                drawOutput(g, ui, area)
            }
        }
    }

    /** The output, newest at the bottom, cut at the area's edges; the wheel or a drag scrolls it back, by pixels. */
    private fun drawOutput(g: Graphics, ui: ConsoleUi, area: Area) {
        val step = ui.lineH
        outputScroller.setExtent(lines.size * step + ui.pad, area.h)
        val s = outputScroller.drawn
        ui.clipped(g, area) {
            // the baseline of the newest line; a line's glyphs reach textH above it and a little below
            var y = area.bottom - ui.pad + s
            for (i in lines.size - 1 downTo 0) {
                if (y - ui.textH - 4 > area.bottom) { y -= step; continue }
                if (y + 4 < area.y) break
                lines[i].draw(ui.font, ui.scale, area.x, y, ui.time)
                y -= step
            }
        }
        val offset = (outputScroller.pos / step).toInt()
        if (offset != 0) {
            g.colour = Colour.red
            g.drawLine(area.x.f, area.bottom.f, area.right.f, area.bottom.f)
            ui.small.drawStringLeftAligned(area.right.f, area.y + ui.smallH + 4f, "$offset lines scrolled past", Colour.red)
        }
        if (lines.isEmpty()) ui.font.drawString(area.x.f, area.bottom - ui.pad.f, if (ui.touch) "Type a command, or tap Help." else "Type a command; help lists them all.", Colour.lightGray)
    }

    private fun touchButton(label: String) {
        when (label) {
            "Clear" -> { historyCursor = -1; input = "" }
            "Tab" -> (overlay as? AutoCompleter)?.applyAutoCompletion()
            "Run" -> { selectHistory(); runCommand() }
            "Help" -> { historyCursor = -1; input = "help " }
            "Keys" -> if (xyz.znix.xftl.game.TouchMode.keyboardTop < screenH) hideKeyboard() else showKeyboard()
            "Close" -> game.cancelMenu()
        }
    }

    /** Puts the host's keyboard away, also when the host thinks it is away already. */
    private fun hideKeyboard() {
        val t = xyz.znix.xftl.game.TouchMode
        t.keyboardWanted = false
        t.keyboardFocusY = if (t.keyboardFocusY == TOUCH_FOCUS_Y) TOUCH_FOCUS_Y + 1 else TOUCH_FOCUS_Y
    }

    /** Asks the host for its keyboard again (a changed focus makes the host look again, even if it thinks it is up). */
    private fun showKeyboard() {
        val t = xyz.znix.xftl.game.TouchMode
        if (!t.enabled) return
        t.keyboardWanted = true
        t.keyboardFocusY = if (t.keyboardFocusY == TOUCH_FOCUS_Y) TOUCH_FOCUS_Y + 1 else TOUCH_FOCUS_Y
    }

    fun update(@Suppress("UNUSED_PARAMETER") gc: GameContainer, dt: Float) {
        flashTimer += dt
        panelScroller.update(dt)
        outputScroller.update(dt)
        chipScroller.update(dt)
        if (dt > 0f) lastDt = dt
    }

    /** Escape with a selection in the panel drops it (the next Escape closes the console); true when it did. */
    fun escape(): Boolean = (overlay as? ConsolePanel)?.clearSelection() == true

    fun keyPressed(key: Int, c: Char) {
        // Our GLFW-based game sends characters and keycodes separately,
        // so we have to block the backtick that comes once the console
        // is opened.
        if (c == '`' && key != Input.KEY_GRAVE)
            return

        // Check if the continuation UI wants to handle the keypress
        if (overlay?.keyPressed(key, c) == true)
            return

        if (navigationKey(key))
            return

        when (key) {
            Input.KEY_ENTER -> {
                selectHistory()
                runCommand()
            }

            Input.KEY_UP -> {
                if (historyCursor == -1) {
                    historyCursor = history.size - 1
                } else {
                    historyCursor--
                    if (historyCursor < 0)
                        historyCursor = 0
                }
            }

            Input.KEY_DOWN -> {
                if (historyCursor != -1)
                    historyCursor++
                if (historyCursor >= history.size)
                    historyCursor = -1
            }

            Input.KEY_BACK -> {
                selectHistory()
                if (cursor > 0) {
                    val at = cursor
                    input = input.removeRange(at - 1, at)
                    cursor = at - 1
                }
            }

            // Delete takes the character after the cursor (HS's TextInput). At the end of the line it clears the line:
            // on a desktop keyboard delete is very close to enter.
            Input.KEY_DELETE -> {
                selectHistory()
                if (cursor < input.length) {
                    val at = cursor
                    input = input.removeRange(at, at + 1)
                    cursor = at
                } else {
                    input = ""
                }
            }

            Input.KEY_LEFT -> { selectHistory(); cursor = (cursor - 1).coerceAtLeast(0) }
            Input.KEY_RIGHT -> { selectHistory(); cursor = (cursor + 1).coerceAtMost(input.length) }
            Input.KEY_HOME -> { selectHistory(); cursor = 0 }
            Input.KEY_END -> { selectHistory(); cursor = input.length }

            // Triggers autocompletion
            Input.KEY_TAB -> {
                (overlay as? AutoCompleter)?.applyAutoCompletion()
            }

            Input.KEY_GRAVE -> {
                // This key opens and closes the console, so its ` stays out of commands; shifted it types ~ (Lua's
                // ~=), as HS's console box takes every typed character (InputBox::TextInput).
                if (c != '`' && c in ' '..'~') type(c)
            }

            else -> {
                // Ignore characters our font doesn't support, which
                // includes any odd ASCII characters that could be
                // somehow generated.
                if (!font.supportsCharacter(c))
                    return

                type(c)
            }
        }
    }

    /**
     * The keys in a panel (docs/CONSOLE.md "Keys"): Down selects the first entry in view and moves on, Up moves back
     * (from the top row it drops the selection; with none it is the history again), Left and Right move along a
     * picker's row while something is selected, Enter takes the selection as a click would, Page Up and Page Down scroll
     * a page. Typing still goes to the line, so it searches.
     */
    private fun navigationKey(key: Int): Boolean {
        val panel = overlay as? ConsolePanel
        if (key == Input.KEY_PG_UP || key == Input.KEY_PG_DN) {
            val sign = if (key == Input.KEY_PG_UP) -1 else 1
            val view = panelView
            if (panel != null && view != null) panelScroller.glideTo(panelScroller.target + sign * view.h * 0.9f)
            else outputArea?.let { outputScroller.glideTo(outputScroller.target - sign * it.h * 0.9f) }
            return true
        }
        if (panel == null || historyCursor != -1) return false
        when (key) {
            Input.KEY_DOWN, Input.KEY_UP -> {}
            Input.KEY_LEFT, Input.KEY_RIGHT -> if (!panel.hasSelection) return false
            Input.KEY_ENTER -> return panel.hasSelection && panel.activate()
            else -> return false
        }
        val r = panel.navigate(key) ?: return false
        val view = panelView
        if (!r.isEmpty() && view != null) panelScroller.reveal(r.first, r.last, view.h)
        return true
    }

    /** A typed character goes in at the cursor (HS's TextInput::OnTextInput, with the InputBox::TextInput hook's invertCaps). */
    fun type(c: Char) {
        selectHistory()
        if (input.length >= MAX_LENGTH)
            return
        var ch = c
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.config?.consoleInvertCaps == true) {
            if (ch in 'a'..'z') ch -= 32 else if (ch in 'A'..'Z') ch += 32
        }
        val at = cursor.coerceIn(0, input.length)
        input = input.substring(0, at) + ch + input.substring(at)
        cursor = at + 1
    }

    // ---- the mouse and fingers (docs/CONSOLE.md "Scrolling"): a press and release in place taps what is under the
    // press, a drag scrolls (a finger's flings on when let go), the scroll bar's thumb drags and its track pages

    private var pressX = 0
    private var pressY = 0
    private var pressed = false
    private var dragging = false
    /** What the press started on: [DRAG_PANEL], [DRAG_OUTPUT], [DRAG_CHIPS], [DRAG_BAR] or 0. */
    private var dragTarget = 0
    /** Where on the thumb the scroll bar was taken. */
    private var barGrab = 0

    fun mousePressed(button: Int, x: Int, y: Int) {
        overlay?.mousePressed(button, x, y)
        if (button != Input.MOUSE_LEFT_BUTTON) return
        pressX = x; pressY = y; pressed = true; dragging = false
        val track = barTrack
        val view = panelView
        dragTarget = when {
            track != null && view != null && barHitArea(track).contains(x, y) -> {
                val thumb = ConsoleUi.thumbArea(track, panelScroller.pos, view.h, panelHeight)
                if (y in thumb.y until thumb.bottom) {
                    panelScroller.jumpTo(panelScroller.pos)
                    barGrab = y - thumb.y
                    DRAG_BAR
                } else {
                    // the track: a page towards the press
                    val sign = if (y < thumb.y) -1 else 1
                    panelScroller.glideTo(panelScroller.target + sign * view.h * 0.9f)
                    0
                }
            }
            view?.contains(x, y) == true -> { panelScroller.press(); DRAG_PANEL }
            panelArea?.contains(x, y) == true -> 0
            chipArea?.contains(x, y) == true -> { chipScroller.press(); DRAG_CHIPS }
            outputArea?.contains(x, y) == true -> { outputScroller.press(); DRAG_OUTPUT }
            else -> 0
        }
    }

    fun mouseReleased(button: Int, x: Int, y: Int) {
        overlay?.mouseReleased(button, x, y)
        if (button != Input.MOUSE_LEFT_BUTTON || !pressed) return
        pressed = false
        val dragged = dragging
        dragging = false
        val target = dragTarget
        dragTarget = 0
        // a finger's drag flings on; the mouse's stops where it is
        val fling = dragged && ui().touch
        when (target) {
            DRAG_PANEL -> panelScroller.release(fling)
            DRAG_OUTPUT -> outputScroller.release(fling)
            DRAG_CHIPS -> chipScroller.release(fling)
        }
        if (dragged || target == DRAG_BAR) return
        // what was drawn under the press in the last frame (a moving list stopped there when it was pressed)
        uiCache?.hits?.lastOrNull { it.area.contains(pressX, pressY) }?.action?.invoke()
    }

    fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        overlay?.mouseDragged(oldX, oldY, newX, newY)
        if (!pressed) return
        if (dragTarget == DRAG_BAR) {
            val track = barTrack ?: return
            val view = panelView ?: return
            val thumb = ConsoleUi.thumbArea(track, panelScroller.pos, view.h, panelHeight)
            val room = track.h - thumb.h
            if (room > 0) panelScroller.jumpTo((newY - barGrab - track.y).f / room * panelScroller.max)
            return
        }
        val slop = if (ui().touch) 14 else 4
        if (!dragging && (Math.abs(newX - pressX) > slop || Math.abs(newY - pressY) > slop)) dragging = true
        if (!dragging) return
        when (dragTarget) {
            DRAG_PANEL -> panelScroller.drag((newY - oldY).f)
            DRAG_OUTPUT -> outputScroller.drag(-(newY - oldY).f)
            DRAG_CHIPS -> chipScroller.drag((newX - oldX).f)
        }
    }

    /**
     * The wheel scrolls what is under the pointer by pixels: the website gives the event's own distance (a trackpad's
     * small steps, ConsoleScroller.hostWheelPixels), else a notch is [ConsoleScroller.NOTCH_PIXELS]; it glides there.
     */
    fun mouseWheelMoved(amount: Int) {
        val host = ConsoleScroller.hostWheelPixels
        val px = if (!host.isNaN()) host else -amount / 120f * ConsoleScroller.NOTCH_PIXELS
        val ui = uiCache
        val mx = ui?.mouseX ?: -1
        val my = ui?.mouseY ?: -1
        when {
            panelArea?.contains(mx, my) == true -> panelScroller.wheel(px)
            chipArea?.contains(mx, my) == true -> chipScroller.wheel(px)
            else -> outputScroller.wheel(-px)
        }
    }

    /**
     * If the user has scrolled back to a previous command, copy
     * that to the buffer.
     */
    private fun selectHistory() {
        if (historyCursor != -1) {
            input = history[historyCursor]
            historyCursor = -1
        }
    }

    /**
     * Print a line of output to the bottom of the console.
     */
    fun addLine(line: String) {
        lines += SimpleLine(line)
    }

    /**
     * This adds a bunch of lines to the output, each of which
     * is split into separate parts that are vertically aligned.
     *
     * This is how we can draw a cleanly aligned help menu with
     * a variable-width font.
     */
    fun addLineGrid(gridLines: List<List<String>>, offsetX: Int = 0) {
        if (gridLines.isEmpty())
            return

        // Transpose the input lines into columns
        val columns = ArrayList<ArrayList<String>>()

        for (i in 0 until gridLines[0].size) {
            columns.add(ArrayList())
        }

        for (line in gridLines) {
            require(line.size == columns.size)
            for ((index, part) in line.withIndex()) {
                columns[index].add(part)
            }
        }

        // Find how wide each column is
        val widths = columns.map { column -> column.map { font.getWidth(it) }.max() }

        // Build the positions
        val positions = ArrayList<Int>()
        val margin = 15
        positions += offsetX
        for (i in 1 until columns.size) {
            positions += positions[i - 1] + widths[i - 1] + margin
        }

        // Finally, write out all the lines.
        for (line in gridLines) {
            lines += GridLine(line, positions)
        }
    }

    fun execute(command: String) { historyCursor = -1; input = command; runCommand() }

    /**
     * Runs [line] from a picker: the line goes into the history and the output as if typed, then the input goes back to
     * [keep] (unless the command left something else there) with the same picker, so the next pick is one tap away.
     */
    fun runKeeping(line: String, keep: String) {
        val panel = completion
        historyCursor = -1
        input = line
        runCommand()
        if (input.isEmpty()) input = keep
        if (input == keep) completion = panel
    }

    /** Closing the console drops the half-typed line, as Escape does in Hyperspace's box. */
    fun closed() {
        historyCursor = -1
        input = ""
        continued = null
        completion = null
    }

    private fun runCommand() {
        // Jump the scroll to the bottom when the player runs something.
        outputScroller.reset()

        // Clear out the input
        val input = this.input
        ftl.browser.BrowserDebug.event("console", "command.execute", "input", input)
        this.input = ""

        // Don't add blank lines or the same command multiple times in a row
        if (history.lastOrNull() != input && input.isNotBlank())
            history.add(input)

        lines += EchoLine(prompt, input, continued == null && ConsoleUi.featured(commands, input.trimStart().substringBefore(' ')))

        // If we're part way through a command that takes multiple
        // lines of input, run that.
        continued?.let { cmd ->
            continued = null
            cmd.run(input.trim())
            return
        }

        // If there's an auto-completer active, dismiss it.
        // (it could crash if the entire line goes away)
        completion = null

        // Ignore empty commands
        if (input.isBlank())
            return

        // Build the arguments list, ignoring subsequent spaces
        val args = input.split(' ').filter { it.isNotEmpty() }
        val command = args[0]

        val cmd = ConsoleRegistry.find(commands, command)
        if (cmd == null) {
            addLine("Unknown command '${command}', see the help command")
            return
        }

        if (cmd.needsHs && !xyz.znix.xftl.hs.HS.active) {
            addLine(ConsoleRegistry.needsHsMessage(cmd.name))
            return
        }

        // A Hyperspace or original command reads the whole line itself, as RunCommand does.
        cmd.raw?.let { raw ->
            try { raw(input.trimStart()) }
            catch (ex: Exception) {
                addLine("Command failed: ${ex.message}")
                ftl.browser.BrowserDebug.event("console", "command.failed", "message", ex.toString())
            }
            return
        }

        val numArgs = args.size - 1 // Exclude the command itself

        if (cmd.isVarArg) {
            if (cmd.params.size > numArgs) {
                addLine("Command '${command}' takes at least ${cmd.params.size} arguments, but $numArgs were supplied.")
                return
            }
        } else if (cmd.params.size != numArgs) {
            addLine("Command '${command}' takes ${cmd.params.size} arguments, but $numArgs were supplied.")
            return
        }

        val parsedArgs = ArrayList<Any>(cmd.params.size)
        for (i in 0 until cmd.params.size) {
            val str = args[i + 1] // +1 to exclude the command name

            val result = cmd.params[i].type.process(str, this)
            if (result == null) {
                // Parsing failure, main message already printed
                addLine(" (error found in argument ${i + 1})")
                return
            }

            parsedArgs.add(result)
        }

        if (cmd.isVarArg) {
            val varArgList = args.subList(cmd.params.size + 1, args.size) // +1 to exclude command name
            parsedArgs.add(varArgList)
        }

        try { cmd.func(parsedArgs) }
        catch (ex: Exception) {
            addLine("Command failed: ${ex.message}")
            ftl.browser.BrowserDebug.event("console", "command.failed", "message", ex.toString())
        }
    }

    private fun updateAutoCompleter() {
        parameterHints.clear()

        // A continuation can delegate to its own auto-completer,
        // if it really needs to.
        if (continued != null) {
            completion = null
            return
        }

        // Don't auto-complete history
        if (historyCursor != -1) {
            completion = null
            return
        }

        // No auto-completion for an empty input
        if (input.isBlank()) {
            completion = null
            return
        }

        // Count the number of spaces, to find out our argument number
        // Note that idx=0 is the command name itself, so arguments need -1
        val partIdx = input.count { it == ' ' }
        val paramIdx = partIdx - 1

        val commandName = input.substringBefore(' ')
        val command = ConsoleRegistry.find(commands, commandName)

        // Add hints for all the remaining parameters
        if (command != null) {
            for (i in paramIdx + 1 until command.params.size) {
                parameterHints.add(command.params[i].name)
            }
        }

        if (partIdx == 0) {
            if (completion !is CommandCompleter)
                completion = CommandCompleter(this)
            completion!!.update(input, 0)
            return
        }

        // Unknown command?
        if (command == null) {
            completion = null
            return
        }

        // If we're past the last argument in a non-vararg function, continue
        // completing the last argument. This lets you use spaces when
        // fuzzy-searching the last argument, which is a common case for stuff
        // like the 'weapon' command.
        if (paramIdx >= command.params.size && (command.isVarArg || command.params.isEmpty())) {
            completion = null
            return
        }
        val effectiveParamIdx = paramIdx.coerceAtMost(command.params.size - 1)

        val currentParam = command.params[effectiveParamIdx]

        completion = currentParam.type.getCompleter(this, completion)

        // Find the start position for this argument. We can't use lastIndexOf,
        // since that wouldn't work for the last space-containing effective arg.
        var startPos = 0
        for (i in 0..effectiveParamIdx) {
            startPos = input.indexOf(' ', startPos) + 1
        }

        val currentToken = input.substring(startPos)
        completion?.update(currentToken, startPos)
    }

    fun getWeapon(callback: (AbstractWeaponBlueprint) -> Unit) {
        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val visibleWeapons = ArrayList<Buttons.BlueprintButton>()

            override val prompt: String get() = "WEAPON> "

            var lastLeftClick = false

            override fun run(line: String) {
                val weapon = game.blueprintManager.blueprints[line]
                if (weapon == null) {
                    addLine("No such blueprint '$line'")
                    return
                }
                if (weapon !is AbstractWeaponBlueprint) {
                    addLine("Blueprint '$line' is not a weapon - ${weapon.javaClass.name}")
                    return
                }
                callback(weapon)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                for ((i, button) in visibleWeapons.withIndex()) {
                    val x = 10 + (button.image.normal.width + 5) * i
                    val y = height.toInt() + 10

                    if (x > gc.width)
                        break

                    button.windowOffset = ConstPoint(x, y)
                    button.update(mouseX, mouseY)
                    button.draw(g)

                    if (clicking) {
                        button.mouseDown(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY)
                    }
                }
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                val searcher = FuzzySearcher(line)

                val names = game.blueprintManager.blueprints.keys.mapNotNull {
                    val score = searcher.rank(it)

                    return@mapNotNull if (score == 0) {
                        null
                    } else {
                        Pair(it, score)
                    }
                }.sortedByDescending { it.second }.map { it.first }

                val images = ButtonImageSet.select2(game, "img/storeUI/store_buy_weapons")

                // Use BlueprintButton's rendering
                class DummyButton(override val blueprint: Blueprint) :
                    Buttons.BlueprintButton(ConstPoint.ZERO, game, images) {
                    override fun click(button: Int) {
                        historyCursor = -1
                        input = blueprint.name
                        runCommand()
                    }
                }

                val weapons: List<DummyButton> = names.mapNotNull {
                    val bp = game.blueprintManager.blueprints[it]
                    val weapon = bp as? AbstractWeaponBlueprint ?: return@mapNotNull null
                    return@mapNotNull DummyButton(weapon)
                }

                visibleWeapons.clear()
                visibleWeapons.addAll(weapons)
            }
        }
    }

    fun getAugment(callback: (AugmentBlueprint) -> Unit) {
        val augmentSize = ConstPoint(235, 40)
        val margin = 5
        val augmentFont = game.getFont("JustinFont12Bold")

        val allAugments = game.blueprintManager.blueprints.values.mapNotNull { it as? AugmentBlueprint }

        class AugmentButton(val aug: Blueprint) : Button(game, ConstPoint.ZERO, augmentSize) {
            override fun click(button: Int) {
                historyCursor = -1
                input = aug.name
                runCommand()
            }

            override fun draw(g: Graphics) {
                // FIXME this is copied from ShipEquipmentPanel.

                // Draw the empty box
                g.colour = Constants.AUGMENT_EMPTY_OUTLINE
                g.fillRect(pos.x.f, pos.y.f, size.x.f, size.y.f)
                g.colour = Constants.AUGMENT_EMPTY_INSIDE
                g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                // Draw the semi-transparent augment on top of it

                // Draw the borders. Since the middle is semi-transparent, we can't
                // just fill in the whole thing twice to get our border easily.
                g.colour = when {
                    // dragPosition != null -> Constants.AUGMENT_BOX_OUTLINE
                    hovered -> Constants.AUGMENT_BOX_OUTLINE_HOVER
                    else -> Constants.AUGMENT_BOX_OUTLINE
                }
                // Left and right
                g.fillRect(pos.x + 0f, pos.y + 0f, 3f, size.y.f)
                g.fillRect(pos.x + size.x - 3f, pos.y + 0f, 3f, size.y.f)

                // Top and bottom
                g.fillRect(pos.x + 3f, pos.y + 0f, size.x - 6f, 3f)
                g.fillRect(pos.x + 3f, pos.y + size.y - 3f, size.x - 6f, 3f)

                // Fill in the background
                g.colour = Constants.AUGMENT_BOX_INSIDE
                g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                // Draw the name
                val name = aug.translateTitle(game)
                augmentFont.drawStringCentred(pos.x.f, pos.y.f + 27f, size.x.f, name, Constants.AUGMENT_NAME_TEXT)
            }
        }

        // FIXME this is partially copy-pasted from getWeapon
        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val visibleAugments = ArrayList<AugmentButton>()

            override val prompt: String get() = "AUGMENT> "

            var lastLeftClick = false

            override fun run(line: String) {
                val bp = game.blueprintManager.blueprints[line]
                if (bp == null) {
                    addLine("No such blueprint '$line'")
                    return
                }
                if (bp !is AugmentBlueprint) {
                    addLine("Blueprint '$line' is not an augment - ${bp.javaClass.name}")
                    return
                }
                callback(bp)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                var row = 0
                var column = 0

                val basePos = ConstPoint(10, height.toInt() + 10)
                val effectiveSize = augmentSize + ConstPoint(margin, margin)

                for (button in visibleAugments) {
                    var x = basePos.x + effectiveSize.x * column
                    var y = basePos.y + effectiveSize.y * row

                    column++

                    if (x + effectiveSize.x > gc.width) {
                        x = basePos.x
                        y += effectiveSize.y
                        column = 0
                        row++
                    }

                    if (y > gc.height) {
                        break
                    }

                    button.windowOffset = ConstPoint(x, y)
                    button.update(mouseX, mouseY)
                    button.draw(g)

                    if (clicking) {
                        button.mouseDown(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY)
                    }
                }
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                visibleAugments.clear()

                // If no search is entered, show all the augments alphabetically
                if (line.isBlank()) {
                    visibleAugments += allAugments.sortedBy { it.translateTitle(game) }.map { AugmentButton(it) }
                    return
                }

                val searcher = FuzzySearcher(line)

                val blueprints = allAugments.mapNotNull {
                    val score = searcher.rank(it.translateTitle(game))

                    return@mapNotNull if (score == 0) {
                        null
                    } else {
                        Pair(it, score)
                    }
                }.sortedByDescending { it.second }.map { it.first }

                visibleAugments.addAll(blueprints.map { AugmentButton(it) })
            }
        }
    }

    fun getEvent(callback: (IEvent) -> Unit) {
        data class EventInfo(val event: IEvent, val text: String?)

        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val visibleEvents = ArrayList<EventInfo>()

            override val prompt: String get() = "EVENT> "

            var lastLeftClick = false

            override fun run(line: String) {
                val names = game.eventManager.eventNames
                if (!names.contains(line)) {
                    addLine("No such event '$line'")
                    return
                }
                val event = game.eventManager[line]

                callback(event)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                val x = 30
                val blockHeight = 15
                val width = gc.width - x - 40

                val idWidth = 125

                // Draw the search category options
                val optionHeight = blockHeight + 2
                var optionX = x
                fun drawOption(value: Boolean, text: String) {
                    val boxWidth = 5 + font.getWidth(text) + 5
                    g.colour = Colour(55, if (value) 180 else 55, 55, 180)

                    val y = height + 10f
                    g.fillRect(optionX.f, y, boxWidth.f, optionHeight.f)
                    font.drawString(optionX + 5f, y + 10f, text, Colour.white)

                    optionX += boxWidth
                }
                drawOption(eventSearchIds, "Search IDs (F1)")
                drawOption(eventSearchMessageText, "Search event text (F2)")
                drawOption(eventSearchOptions, "Search choice text (F3)")

                for ((i, event) in visibleEvents.withIndex()) {
                    val y = height.toInt() + 10 + optionHeight + i * blockHeight

                    if (y > gc.height)
                        break

                    val hovering = mouseX in x until x + width && mouseY in y until y + blockHeight

                    val shade = if (hovering) 140 else 100
                    g.colour = Colour(shade, shade, shade, 180)
                    g.fillRect(x.f, y.f, width.f, blockHeight.f)

                    font.drawStringTruncated(x + 5f, y + 10f, idWidth.f, event.event.debugId, Colour.white)

                    val descriptionX = x + idWidth + 10
                    val descriptionWidth = width - descriptionX - 5f
                    val text = event.text ?: "<event list>"
                    font.drawStringTruncated(
                        descriptionX.f, y + 10f, descriptionWidth,
                        text.replace("\n", " \\n "),
                        Colour.white
                    )

                    if (clicking && hovering) {
                        // Note the debug ID matches the event name for top-level events (we don't
                        // display nested events).
                        historyCursor = -1
                        input = event.event.debugId
                        runCommand()
                    }
                }
            }

            override fun keyPressed(key: Int, c: Char): Boolean {
                val handled = when (key) {
                    Input.KEY_F1 -> {
                        eventSearchIds = !eventSearchIds
                        true
                    }

                    Input.KEY_F2 -> {
                        eventSearchMessageText = !eventSearchMessageText
                        true
                    }

                    Input.KEY_F3 -> {
                        eventSearchOptions = !eventSearchOptions
                        true
                    }

                    else -> false
                }

                // Update the search entries
                if (handled)
                    lastInput = null

                return handled
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                val searcher = FuzzySearcher(line)

                val events = game.eventManager.eventNames.mapNotNull { name ->
                    val event = game.eventManager[name]

                    var score = 0

                    // Search the name, and consider it to be more important
                    // than the body text. This makes it easier to find a specific
                    // event.
                    if (eventSearchIds)
                        score += searcher.rank(name) * 10

                    // If this is an event list, we can't search it's text
                    if (event !is Event)
                        return@mapNotNull Pair(EventInfo(event, null), score)

                    // And the body of the event text - just search the first
                    // available one, it'd get a bit unmanageable otherwise.
                    val textBody = event.text?.let(::getTextBody) ?: return@mapNotNull null
                    if (eventSearchMessageText)
                        score += searcher.rank(textBody)

                    // Rank all the options, too.
                    if (eventSearchOptions) {
                        for (choice in event.choices) {
                            val text = getTextBody(choice.text) ?: continue
                            score += searcher.rank(text)
                        }
                    }

                    Pair(EventInfo(event, textBody), score)
                }.filter { it.second != 0 }.sortedByDescending { it.second }

                visibleEvents.clear()
                visibleEvents.addAll(events.map { it.first })
            }

            private fun getTextBody(text: IEventText): String? {
                if (text is EventText)
                    return text.localised

                require(text is TextList)

                for (item in text.items) {
                    getTextBody(item)?.let { return it }
                }
                return null
            }
        }
    }

    fun <T> pickFromList(prompt: String, items: List<Pair<String, T>>, callback: (T) -> Unit) {
        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val sortedEntries = ArrayList<Pair<String, T>>()

            override val prompt: String = "$prompt> "

            var lastLeftClick = false

            override fun run(line: String) {
                val item = items.firstOrNull { it.first == line }
                if (item == null) {
                    addLine("No such option '$line'")
                    return
                }
                callback(item.second)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                val x = 30
                val blockHeight = 15
                val width = gc.width - x - 40

                for ((i, item) in sortedEntries.withIndex()) {
                    val y = height.toInt() + 10 + i * blockHeight

                    if (y > gc.height)
                        break

                    val hovering = mouseX in x until x + width && mouseY in y until y + blockHeight

                    val shade = if (hovering) 140 else 100
                    g.colour = Colour(shade, shade, shade, 180)
                    g.fillRect(x.f, y.f, width.f, blockHeight.f)

                    font.drawString(x + 5f, y + 10f, item.first, Colour.white)

                    if (clicking && hovering) {
                        historyCursor = -1
                        input = item.first
                        runCommand()
                    }
                }
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                sortedEntries.clear()
                sortedEntries.addAll(items)

                if (line.isBlank()) {
                    // If there's no search term, leave the items in their original order.
                    return
                }

                // Display all the entries - sorting only orders them
                val searcher = FuzzySearcher(line)
                sortedEntries.sortByDescending { searcher.rank(it.first) }
            }
        }
    }

    fun onFailedSaveRestore() {
        addLine("Continuous save/restore - exception during serialisation!")
        addLine("Details are in the console, the game has been paused.")
    }

    data class Cmd(
        val name: String,
        val params: List<CmdParameter>,
        val isVarArg: Boolean,
        val func: (List<Any>) -> Unit,
        val helpText: String,
        /** A Hyperspace or original command: gets the whole line instead of parsed arguments. */
        val raw: ((String) -> Unit)? = null,
        /** Answers [ConsoleRegistry.needsHsMessage] while no Hyperspace mod runs. */
        val needsHs: Boolean = false,
        /** The arguments as help shows them. */
        val args: String = params.joinToString(" ") { it.name },
        /** The command reference's group ([ConsoleRegistry.Groups]). */
        val group: String = "",
        /** Lines that run as they stand; the reference fills one in when it is tapped. */
        val examples: List<String> = emptyList(),
        /** One of the new commands: drawn in rainbow colours. */
        val featured: Boolean = false,
    ) {
        /** The command as typed: its name and its arguments. */
        val usage: String get() = if (args.isEmpty()) name else "$name $args"
    }

    data class CmdParameter(
        val type: ArgumentTypeProcessor,
        val name: String
    )

    class FuzzySearcher(query: String) {
        // Split up the words
        private val query = query.toLowerCase(Locale.UK)
        private val inputParts = query.split(" ", "_").filter { it.isNotBlank() }

        fun rank(target: String): Int {
            val lower = target.toLowerCase(Locale.UK)

            // Only match words once, as otherwise a word that's repeated a lot could
            // cause one entry to very frequently appear at the top of the list.
            val parts = lower
                .replace("_", " ")
                .split(" ")
                .toSet()

            var score = 0

            for (part in parts) {
                for (ip in inputParts) {
                    if (part == ip) {
                        score += 20 + ip.length * 3
                    } else if (part.contains(ip)) {
                        // Add a per-word weight
                        score += 10 + ip.length
                    }
                }
            }

            // Add a bonus if the search matches literally (though case-insensitively).
            if (target.contains(query)) {
                score += 4 * query.length
            }

            return score
        }
    }

    interface ContinuedCommand : Overlay {
        val prompt: String
        fun run(line: String)
    }

    interface Overlay {
        val autoCompleteSuggestion: String? get() = null

        fun render(gc: GameContainer, g: Graphics, height: Float) {}
        fun keyPressed(key: Int, c: Char): Boolean = false
        fun mousePressed(button: Int, x: Int, y: Int) = Unit
        fun mouseReleased(button: Int, x: Int, y: Int) = Unit
        fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) = Unit
    }

    // Represents a line of console output
    private interface ILine {
        val text: String
        /** [scale] is [f]'s, for the grid's columns (measured at scale 1). */
        fun draw(f: xyz.znix.xftl.SILFontLoader, scale: Int, x: Int, y: Int, time: Float)
    }

    private inner class SimpleLine(val line: String) : ILine {
        override val text get() = line
        override fun draw(f: xyz.znix.xftl.SILFontLoader, scale: Int, x: Int, y: Int, time: Float) {
            f.drawString(x.f, y.f, line, Colour.white)
        }
    }

    /** A run command echoed: the new commands' word glows as it does on the input line. */
    private inner class EchoLine(val prompt: String, val line: String, val featured: Boolean) : ILine {
        override val text get() = prompt + line
        override fun draw(f: xyz.znix.xftl.SILFontLoader, scale: Int, x: Int, y: Int, time: Float) {
            if (!featured) { f.drawString(x.f, y.f, text, Colour.white); return }
            val word = line.trimStart().substringBefore(' ')
            val lead = line.length - line.trimStart().length
            val before = prompt + line.substring(0, lead)
            f.drawString(x.f, y.f, before, Colour.white)
            ConsoleUi.rainbow(f, x + f.getWidth(before).f, y.f, word, time)
            f.drawString(x + f.getWidth(before + word).f, y.f, line.substring(lead + word.length), Colour.white)
        }
    }

    private inner class GridLine(val parts: List<String>, val positions: List<Int>) : ILine {
        override val text get() = parts.joinToString("\t")
        override fun draw(f: xyz.znix.xftl.SILFontLoader, scale: Int, x: Int, y: Int, time: Float) {
            for ((index, part) in parts.withIndex()) {
                val pos = positions[index] * scale
                if (index == 0 && ConsoleUi.featured(commands, part)) ConsoleUi.rainbow(f, x.f + pos, y.f, part, time)
                else f.drawString(x.f + pos, y.f, part, Colour.white)
            }
        }
    }

    companion object {
        private const val PROMPT = "> "

        /** The touch layout: the input bar's height, the recent commands' row, the last output line's strip. */
        const val TOUCH_BAR_H = 64
        const val TOUCH_CHIP_H = 48
        const val TOUCH_STATUS_H = 32
        /** The bottom of the touch layout's input line: the host keeps it above its keyboard. */
        const val TOUCH_FOCUS_Y = TOUCH_BAR_H

        /** The desktop console's top part while a picker or the reference is open: a few lines of output and the line. */
        const val DESKTOP_BIG_TOP = 112
        private const val DRAG_PANEL = 1
        private const val DRAG_OUTPUT = 2
        private const val DRAG_CHIPS = 3
        private const val DRAG_BAR = 4
        private const val TRACE_FRAMES = 240

        /** HS's TextInput(480, ALLOW_ANY). */
        const val MAX_LENGTH = 480
        private const val FLASH_TIME = 0.65f

        // TODO move this to somewhere in appdata (or platform equivalent) when we pick
        //  somewhere to store the regular savegames.
        @JvmField
        val DEBUG_SAVE_DIR = Paths.get("debug-saves")
    }
}
