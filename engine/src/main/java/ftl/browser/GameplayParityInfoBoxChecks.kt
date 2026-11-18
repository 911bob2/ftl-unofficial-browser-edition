package ftl.browser

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InfoPanel
import xyz.znix.xftl.game.InfoStack
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ShipWindow
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.game.StoreWindow
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Player report 202 (Android, Multiverse): a drone's long description and its tip filled the right side of the store's
 * sell tab and the SELL box under them was off the screen. InfoStack is the one rule for such columns: the boxes of
 * text share the screen above the control, and a box lower than its text scrolls.
 *
 * Every mode (vanilla, F2, F3), on a phone's touch canvas (1707 x 768), a tablet's (1024 x 768) and the desktop's
 * (1280 x 720, also what a phone held upright shows on the website): the store's buy tab lays out the info column of
 * every weapon, drone, augment, system and crew race of the loaded data and of a very long text; the sell tab and the
 * equipment screen (with and without the touch layout's over-capacity box) lay out the longest of them and the very
 * long text. Each time: every box is on the screen, the boxes and the control do not cover each other, the control is
 * on the screen, text that does not scroll ends on the screen, and text that scrolls can be moved to its last line.
 * With the rule switched off the same very long text must break these: the check sees what it is for.
 */
internal object GameplayParityInfoBoxChecks {
    private class Size(val name: String, val touch: Boolean, val w: Int, val h: Int)
    private class Rect(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val content: Int = 0, val view: Int = 0, val last: Int = 0)

    private val sizes = listOf(Size("phone", true, 1707, 768), Size("tablet", true, 1024, 768), Size("desktop", false, 1280, 720))
    private val graphics: Graphics get() = BrowserEngine.diagnosticGraphics()

    /** The rule's state while the sweep runs (the build without the rule, made for the negative run, has false here). */
    private const val RULE_ON = true

    private val longText: String = (1..60).joinToString("\n") { "Line $it of a very long text a mod could give an item, long enough to pass the edge of any screen." }

    /**
     * Where a frame's art ends inside its box, measured on the art as the game draws it: [top] rows below the box's top
     * and [bottom] rows above its bottom the frame (its border and the glow inside it) no longer shows.
     */
    private class Inner(val top: Int, val bottom: Int)

    /** The info boxes' frame (WindowRenderer: img/window_outline.png of the loaded data), measured once per run. */
    private var windowInner: Inner? = null

    /** Scrolled text is cut at most this far inside the frame's inner edge (no gap where text vanishes in mid-box). */
    private const val CLIP_SLACK = 2

    /** Clears to black, draws with [block] under the identity transform and reads the [w] x [h] rectangle at [x], [y]. */
    private fun render(x: Int, y: Int, w: Int, h: Int, block: (Graphics) -> Unit): ByteArray {
        val g = graphics
        val savedTint = Graphics.tint
        org.lwjgl.opengl.GL11.glClearColor(0f, 0f, 0f, 1f)
        org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT)
        g.markCurrentImageTransformSource()
        g.pushTransform()
        try {
            Graphics.tint = xyz.znix.xftl.rendering.Colour.white
            g.loadIdentityMatrix()
            block(g)
        } finally {
            g.popTransform()
            Graphics.tint = savedTint
        }
        return BrowserPixels.read(x, y, w, h)
    }

    /**
     * In a column of pixels ([px], one per row, rows 0 until [h]), the rows around [mid] that look like [mid]'s (no
     * channel more than 4 apart): the first such row from the top and the first row below them.
     */
    private fun sameAs(px: ByteArray, h: Int, mid: Int): Pair<Int, Int> {
        fun c(r: Int, i: Int) = px[r * 4 + i].toInt() and 255
        fun same(r: Int) = (0..2).all { kotlin.math.abs(c(r, it) - c(mid, it)) <= 4 }
        var top = mid
        while (top > 0 && same(top - 1)) top--
        var bottom = mid
        while (bottom < h && same(bottom)) bottom++
        return top to bottom
    }

    /** The info boxes' frame drawn alone on black (a box 121 high): how far inside the box its art shows. */
    private fun measureWindowInner(state: xyz.znix.xftl.game.InGameState): Inner {
        val x = 100; val y = 100; val w = 200; val h = 121
        val px = render(x + w / 2, y, 1, h) { _ -> state.windowRenderer.drawOutline(x, y, w, h) }
        val (top, bottom) = sameAs(px, h, h / 2)
        check(top in 3..30 && h - bottom in 3..30) { "the info box frame's art measures $top / ${h - bottom} rows: no frame drawn?" }
        return Inner(top, h - bottom)
    }

    /** What is wrong when text cut to rows [clipTop] until [clipBottom] of a box [boxTop] until [boxBottom] crosses its frame [inner]. */
    private fun clipAgainstFrame(clipTop: Int, clipBottom: Int, boxTop: Int, boxBottom: Int, inner: Inner): String? {
        val top = clipTop - boxTop
        val bottom = boxBottom - clipBottom
        return if (top < inner.top || bottom < inner.bottom || top > inner.top + CLIP_SLACK || bottom > inner.bottom + CLIP_SLACK)
            "its scrolled text is cut $top below the box's top and $bottom above its bottom, where the frame's art ends ${inner.top} / ${inner.bottom} inside"
        else null
    }

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val canvasW = TouchMode.canvasW
        val failures = ArrayList<String>()
        val notes = ArrayList<String>()
        val results = ArrayList<String>()
        InfoStack.enabled = RULE_ON
        try {
            // One game for the three sizes (a Multiverse game is some hundred megabytes; the windows and panels of a
            // size are made for it and dropped after it; no list holds laid out boxes: each layout is judged from its
            // line of numbers and forgotten). The reward box's panel, which holds its game, is let go at the end.
            val state = BrowserGameplayParityDiagnostics.fresh(main, container)
            try {
                val inner = measureWindowInner(state)
                windowInner = inner
                for (size in sizes) results += sweep(main, container, state, size, failures, notes)
                results += "info box frame's art ends ${inner.top} / ${inner.bottom} inside"
            } finally {
                xyz.znix.xftl.hs.render.HsEventTooltip.diagnosticReset()
            }
        } finally {
            windowInner = null
            InfoStack.enabled = true
            InfoStack.screenOverride = null
            TouchMode.enabled = false
            TouchMode.canvasW = canvasW
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { "${failures.size} broken: " + failures.take(8).joinToString(" | ") }
        return "info boxes ok: " + results.joinToString("; ") + (if (notes.isEmpty()) "" else "; notes: " + notes.distinct().take(6).joinToString(" | "))
    }

    private fun parse(layout: String): List<Rect> = layout.split('\n').filter { it.isNotBlank() }.map { line ->
        val f = line.trim().split(' ')
        val n = f.drop(1).map { it.toInt() }
        when (f[0]) {
            "floor" -> Rect("floor", 0, n[0], 0, 0)
            "control" -> Rect("control", n[0], n[1], n[2], n[3])
            else -> Rect(f[0], n[0], n[1], n[2], n[3], n.getOrElse(4) { 0 }, n.getOrElse(5) { 0 }, n.getOrElse(6) { 0 })
        }
    }

    /** What is wrong with one laid out column ([what] names it), added to [failures]; off the sides goes to [notes]. */
    private fun judge(what: String, size: Size, layout: String, panel: InfoPanel, failures: MutableList<String>, notes: MutableList<String>) {
        val rects = parse(layout)
        val boxes = rects.filter { it.name != "floor" && it.name != "control" }
        val floor = rects.firstOrNull { it.name == "floor" }?.y ?: size.h
        if (boxes.isEmpty()) { failures += "$what: no box drawn"; return }
        for (b in boxes) {
            if (b.y < 0 || b.y + b.h > floor) failures += "$what: the ${b.name} box (${b.y}..${b.y + b.h}) leaves 0..$floor"
            if (b.x < 0 || b.x + b.w > size.w) notes += "$what: the ${b.name} box (${b.x}..${b.x + b.w}) leaves the width ${size.w}"
            if (b.name != "description" && b.name != "tip") continue
            if (b.content == 0) {
                if (b.y + b.last + 4 > size.h) failures += "$what: the ${b.name}'s text ends at ${b.y + b.last}, below the screen, and does not scroll"
                if (size.touch && b.last + InfoPanel.TEXT_BOTTOM > b.h) failures += "$what: the ${b.name}'s text (${b.last}) runs out of its box (${b.h}) and does not scroll"
            } else {
                val scroller = if (b.name == "tip") panel.tipScroll else panel.descScroll
                scroller.scrollBy(1_000_000)
                if (b.content <= b.view || scroller.offset != b.content - b.view)
                    failures += "$what: the ${b.name}'s text (${b.content} in ${b.view}) stops at ${scroller.offset}"
                scroller.scrollBy(-1_000_000)
                // report 202b: the scrolled text disappears at the frame's inner edge, not over its border
                val c = scroller.clip
                clipAgainstFrame(c[1], c[1] + c[3], b.y, b.y + b.h, windowInner ?: error("the frame is not measured"))
                    ?.let { failures += "$what: the ${b.name} box: $it" }
            }
        }
        val sorted = boxes.sortedBy { it.y }
        for (i in 1 until sorted.size) if (sorted[i].y < sorted[i - 1].y + sorted[i - 1].h)
            failures += "$what: the ${sorted[i].name} box (${sorted[i].y}) starts inside the ${sorted[i - 1].name} box (..${sorted[i - 1].y + sorted[i - 1].h})"
        rects.firstOrNull { it.name == "control" }?.let { c ->
            if (c.y < 0 || c.y + c.h > size.h || c.x < 0 || c.x + c.w > size.w)
                failures += "$what: the control (${c.x},${c.y} ${c.w}x${c.h}) leaves the screen ${size.w}x${size.h}"
            for (b in boxes) if (c.y < b.y + b.h && b.y < c.y + c.h && c.x < b.x + b.w && b.x < c.x + c.w)
                failures += "$what: the ${b.name} box (${b.y}..${b.y + b.h}) covers the control (${c.y}..${c.y + c.h})"
        }
    }

    private fun textLength(state: xyz.znix.xftl.game.InGameState, b: Blueprint): Int {
        val t = state.translator
        var n = (b.desc?.get(t)?.length ?: 0)
        if (b is AbstractWeaponBlueprint) n += b.tip?.let { t[it].length } ?: 0
        if (b is DroneBlueprint) n += b.tip?.let { t[it].length } ?: 0
        return n
    }

    private fun sweep(main: MainGame, container: GameContainer, state: xyz.znix.xftl.game.InGameState, size: Size, failures: MutableList<String>, notes: MutableList<String>): String {
        TouchMode.enabled = size.touch
        TouchMode.canvasW = if (size.touch) size.w else TouchMode.HUD_W
        InfoStack.screenOverride = intArrayOf(size.w, size.h)
        xyz.znix.xftl.hs.render.HsEventTooltip.diagnosticReset()
        val ship = state.player
        val all = state.blueprintManager.blueprints.values
        val items = all.filter { it is AbstractWeaponBlueprint || it is DroneBlueprint || it is AugmentBlueprint }.map { it as Blueprint }
        val systems = all.filterIsInstance<SystemBlueprint>()
        val races = all.filterIsInstance<CrewBlueprint>()
        val longest = items.sortedByDescending { textLength(state, it) }.take(24)
        val g = graphics
        val tag = size.name

        val store = StoreWindow(state, ship, StoreData()) {}
        store.placeIn(size.w, size.h)
        val storePanel = store.diagnosticInfoPanel
        var crew = 0
        var crewSkipped = 0
        // every item of the data: laid out only (Multiverse has thousands; drawing each would fill the page's memory)
        storePanel.diagnosticDry = true
        try {
            for ((i, b) in items.withIndex()) {
                judge("$tag buy ${b.name}", size, store.diagnosticInfo(g, b, false), storePanel, failures, notes)
                if (i % 50 == 49) g.flushAll()
            }
            for (b in systems) judge("$tag buy system ${b.name}", size, store.diagnosticInfo(g, b, false), storePanel, failures, notes)
            for (race in races) {
                val layout = try { store.diagnosticInfo(g, LivingCrewInfo(state, race, "Name", 0), false) } catch (e: Throwable) { crewSkipped++; continue }
                crew++
                judge("$tag buy crew ${race.name}", size, layout, storePanel, failures, notes)
            }
        } finally {
            storePanel.diagnosticDry = false
            g.flushAll()
        }
        for (b in longest) {
            judge("$tag sell ${b.name}", size, store.diagnosticInfo(g, b, true), storePanel, failures, notes)
            g.flushAll()
        }
        // the very long text: as a text box of its own height (Hyperspace's SetText), and in place of an item's texts
        judge("$tag buy long text", size, store.diagnosticInfo(g, Pair("A long title", longText), false), storePanel, failures, notes)
        val weapon = items.firstOrNull { it is AbstractWeaponBlueprint } ?: error("no weapon in the data")
        // the hangar's boxes: the ship list's box with every player ship's description (its two heights) and the
        // hangar's own box, at the hangar's places (the touch block's on touch) with the hangar's widths
        val hangar = InfoPanel(state)
        hangar.diagnosticDry = true
        fun hangarBox(title: String, text: String, list: Boolean, height: Int): String {
            hangar.frame()
            hangar.position = if (list) xyz.znix.xftl.hangar.HangarPresentation.shipListInfoPlace(size.touch) else xyz.znix.xftl.hangar.HangarPresentation.infoPlace(size.touch)
            hangar.customWidth = if (list) xyz.znix.xftl.hangar.HangarPresentation.SHIP_LIST_INFO_WIDTH else 323
            hangar.drawDescriptionBox(xyz.znix.xftl.GameText.literal(title), xyz.znix.xftl.GameText.literal(text), null, emptyList(), height)
            return hangar.drawnBoxes.joinToString("\n")
        }
        var ships = 0
        for (ship in all.filterIsInstance<xyz.znix.xftl.LazyShipBlueprint>().filter { it.name.startsWith("PLAYER_SHIP") }) {
            val text = ship.desc?.get(state.translator) ?: continue
            val title = ship.shipClass?.get(state.translator) ?: ship.name
            ships++
            judge("$tag hangar list ${ship.name}", size, hangarBox(title, text, true, 135 - 14), hangar, failures, notes)
            judge("$tag hangar list (locked height) ${ship.name}", size, hangarBox(title, text, true, 176 - 14), hangar, failures, notes)
            judge("$tag hangar ${ship.name}", size, hangarBox(title, text, false, InfoPanel.INFO_HEIGHT_SYSTEM), hangar, failures, notes)
        }
        judge("$tag hangar list long text", size, hangarBox("A long title", longText, true, 135 - 14), hangar, failures, notes)
        judge("$tag hangar long text", size, hangarBox("A long title", longText, false, InfoPanel.INFO_HEIGHT_SYSTEM), hangar, failures, notes)

        // an event's reward box (Hyperspace's event tooltip), with the longest items and the very long text
        val reward = xyz.znix.xftl.hs.render.HsEventTooltip.diagnosticPanel(state)
        xyz.znix.xftl.hs.render.HsEventTooltip.screenBottom = null
        fun rewardBox(item: Blueprint): String {
            xyz.znix.xftl.hs.render.HsEventTooltip.render(state, g, item, false)
            return reward.drawnBoxes.joinToString("\n")
        }
        reward.diagnosticDry = true
        try {
            for (b in longest) judge("$tag reward box ${b.name}", size, rewardBox(b), reward, failures, notes)
            reward.diagnosticText = longText
            judge("$tag reward box ${weapon.name} with the long text", size, rewardBox(weapon), reward, failures, notes)
        } finally {
            reward.diagnosticText = null
            reward.diagnosticDry = false
        }

        // a confirmation with the very long text: the window and its buttons stay on the screen, the text scrolls
        fun confirmation(): String? {
            val w = xyz.znix.xftl.game.ConfirmationWindow(xyz.znix.xftl.game.SettingsContext(state), "confirm_dismiss", 300, accept = {}, cancel = {}, literalMessage = longText)
            val f = w.diagnosticLayout.split(' ').map { it.toInt() }
            return when {
                f[0] > size.h -> "the window is ${f[0]} high on a screen of ${size.h}"
                f[1] + 24 > f[0] -> "the buttons (${f[1]}) are outside the window (${f[0]})"
                f[2] <= f[3] -> "the text (${f[2]}) does not need to scroll in ${f[3]}: not a long text"
                else -> null
            }
        }
        confirmation()?.let { failures += "$tag confirmation with the long text: $it" }

        // the welcome and ship list tutorial box (a text and its Continue row) with the very long text: the row stays
        // inside the frame
        fun choiceBox(): String? {
            val box = xyz.znix.xftl.game.NativeChoiceBox(state::getFont, state.windowRenderer, longText, listOf(xyz.znix.xftl.game.NativeChoiceBox.Choice("Continue...")))
            box.draw(g)
            g.flushAll()
            val f = box.diagnosticLayout.split(' ').map { it.toInt() }
            return when {
                f[1] > f[0] -> "the choice ends at ${f[1]}, below the frame (${f[0]})"
                f[2] <= f[3] -> "the text (${f[2]}) fits its room (${f[3]}): not a long text"
                else -> null
            }
        }
        choiceBox()?.let { failures += "$tag welcome box with the long text: $it" }

        // report 202b: scrolled text disappears at the frame's inner edge. The confirmation's frame is its own art
        // (confirm_window.png), measured where the game draws it, in a column left of the text (a window of a 300 high
        // screen, so that it lies on the page's canvas); the welcome box has the info boxes' frame above its text and
        // its choice below it.
        fun confirmationClip(): String? {
            InfoStack.screenOverride = intArrayOf(size.w, 300)
            try {
                val w = xyz.znix.xftl.game.ConfirmationWindow(xyz.znix.xftl.game.SettingsContext(state), "confirm_dismiss", 300,
                    accept = {}, cancel = {}, literalMessage = (1..60).joinToString("\n") { "." })
                w.position = xyz.znix.xftl.math.ConstPoint(100, 100)
                val f = w.diagnosticLayout.split(' ').map { it.toInt() }
                val px = render(140, 100, 1, f[0]) { gg -> w.draw(gg) }
                val (top, bottom) = sameAs(px, f[0], 17 + f[3] / 2)
                val clip = w.diagnosticClip.split(' ').map { it.toInt() }
                if (clip[1] == 0) return "its text does not scroll"
                return clipAgainstFrame(clip[0], clip[1], 0, f[0], Inner(top, f[0] - bottom))
            } finally {
                InfoStack.screenOverride = intArrayOf(size.w, size.h)
                g.flushAll()
            }
        }
        confirmationClip()?.let { failures += "$tag confirmation with the long text: $it" }
        fun choiceClip(): String? {
            val box = xyz.znix.xftl.game.NativeChoiceBox(state::getFont, state.windowRenderer, longText, listOf(xyz.znix.xftl.game.NativeChoiceBox.Choice("Continue...")))
            box.draw(g)
            g.flushAll()
            val (frameTop, clipTop, clipBottom, row) = box.diagnosticClip.split(' ').map { it.toInt() }
            val inner = windowInner ?: error("the frame is not measured")
            return when {
                clipTop == 0 -> "its text does not scroll"
                clipTop - frameTop < inner.top || clipTop - frameTop > inner.top + CLIP_SLACK ->
                    "its scrolled text is cut ${clipTop - frameTop} below the frame's top, where the frame's art ends ${inner.top} inside"
                clipBottom > row -> "its scrolled text runs down to $clipBottom, over the choice at $row"
                else -> null
            }
        }
        choiceClip()?.let { failures += "$tag welcome box with the long text: $it" }

        // a tooltip taller than the screen: it scrolls, by the wheel on the desktop layout and by the holding finger's
        // way on touch, to its end. Hyperspace's own weapon box tooltips of the loaded data are measured for the report.
        val tipFont = state.getFont("JustinFont11Bold")
        val scroll = xyz.znix.xftl.rendering.NativeTooltip.HsScroll
        fun tallTooltip(): String? {
            scroll.tooltip = ""; scroll.amount = 0f; scroll.shown = ""
            scroll.diagnosticFinger = false
            scroll.hold.update(false, 0, false) {}
            try {
                xyz.znix.xftl.rendering.NativeTooltip.draw(g, null, tipFont, 400, 600, null, longText)
                if (scroll.shown.isEmpty() || scroll.max <= 0f) return "it does not scroll (it is ${xyz.znix.xftl.rendering.NativeTooltip.measure(null, tipFont, null, longText).second} high)"
                if (size.touch) {
                    scroll.diagnosticFinger = true
                    xyz.znix.xftl.rendering.NativeTooltip.draw(g, null, tipFont, 400, 600, null, longText)
                    xyz.znix.xftl.rendering.NativeTooltip.draw(g, null, tipFont, 400, 600 - 100_000, null, longText)
                } else {
                    var n = 0
                    while (n++ < 400 && scroll.amount < scroll.max) if (!scroll.wheel(-1)) break
                }
                return if (scroll.amount == scroll.max) null else "its text stops at ${scroll.amount} of ${scroll.max}"
            } finally {
                scroll.diagnosticFinger = null
                scroll.tooltip = ""; scroll.amount = 0f; scroll.shown = ""
                scroll.hold.update(false, 0, false) {}
                g.flushAll()
            }
        }
        tallTooltip()?.let { failures += "$tag tooltip with the long text: $it" }
        var tallTips = 0
        var tallestTip = 0
        if (xyz.znix.xftl.hs.HS.active) for (b in items) {
            if (b !is AbstractWeaponBlueprint) continue
            val text = xyz.znix.xftl.hs.render.HsTooltips.weaponBoxTooltip(b, state.translator) ?: continue
            val h = xyz.znix.xftl.rendering.NativeTooltip.measure(null, tipFont, null, text).second
            if (h > 620) tallTips++
            tallestTip = maxOf(tallestTip, h)
        }

        // without the rule the same text must break each of these layouts: the check sees what it is for
        fun mustBreak(name: String, layout: () -> Pair<String, InfoPanel>) {
            val off = ArrayList<String>()
            val (l, panel) = layout()
            judge("$tag $name", size, l, panel, off, ArrayList())
            if (off.isEmpty()) failures += "$tag: with the rule off the very long text breaks nothing in $name: the check does not see it"
        }
        InfoStack.enabled = false
        try {
            mustBreak("buy long text") { store.diagnosticInfo(g, Pair("A long title", longText), false) to storePanel }
            storePanel.diagnosticText = longText
            mustBreak("sell with the long text") { store.diagnosticInfo(g, weapon, true) to storePanel }
            mustBreak("hangar list long text") { hangarBox("A long title", longText, true, 135 - 14) to hangar }
            mustBreak("hangar long text") { hangarBox("A long title", longText, false, InfoPanel.INFO_HEIGHT_SYSTEM) to hangar }
            reward.diagnosticText = longText
            reward.diagnosticDry = true
            mustBreak("reward box with the long text") { rewardBox(weapon) to reward }
            if (confirmation() == null) failures += "$tag: with the rule off the confirmation with the very long text still fits: the check does not see it"
            if (choiceBox() == null) failures += "$tag: with the rule off the welcome box with the very long text keeps its choice in the frame: the check does not see it"
            // Hyperspace scrolls its tooltip by the wheel itself: there only the touch layout's hold is the rule's
            if ((size.touch || !xyz.znix.xftl.hs.HS.active) && tallTooltip() == null)
                failures += "$tag: with the rule off the tooltip with the very long text still scrolls to its end: the check does not see it"
        } finally {
            storePanel.diagnosticText = null
            reward.diagnosticText = null
            reward.diagnosticDry = false
            InfoStack.enabled = RULE_ON
        }
        g.flushAll()
        storePanel.diagnosticText = longText
        try {
            judge("$tag buy ${weapon.name} with the long text", size, store.diagnosticInfo(g, weapon, false), storePanel, failures, notes)
            judge("$tag sell ${weapon.name} with the long text", size, store.diagnosticInfo(g, weapon, true), storePanel, failures, notes)
            systems.firstOrNull()?.let { judge("$tag buy system ${it.name} with the long text", size, store.diagnosticInfo(g, it, false), storePanel, failures, notes) }
        } finally {
            storePanel.diagnosticText = null
        }

        // the equipment screen, then again with an item waiting in the over-capacity box (the touch layout has that box
        // under the info boxes)
        for (overflow in listOf(false, true)) {
            if (overflow) {
                var n = 0
                while (n++ < 12 && ship.cargoBlueprints[4] == null) {
                    ship.addBlueprint(weapon, true)
                    BrowserDiagnostics.closeBoxes(main, container)
                }
                check(ship.cargoBlueprints[4] != null) { "$tag: nothing went to the over-capacity box" }
            }
            val window = ShipWindow(state, ship, ShipWindow.Tab.EQUIPMENT) {}
            window.placeIn(size.w, size.h)
            window.diagnosticRelayout(g)
            val panel = window.diagnosticInfoPanel
            val where = "$tag equipment" + if (overflow) " over capacity" else ""
            for (b in longest) {
                judge("$where ${b.name}", size, window.diagnosticEquipmentInfo(g, b), panel, failures, notes)
                g.flushAll()
            }
            panel.diagnosticText = longText
            try {
                judge("$where ${weapon.name} with the long text", size, window.diagnosticEquipmentInfo(g, weapon), panel, failures, notes)
            } finally {
                panel.diagnosticText = null
            }
        }
        // the next size starts without the waiting item
        ship.cargoBlueprints[4] = null
        BrowserDiagnostics.closeBoxes(main, container)
        return "$tag ${items.size} items, ${systems.size} systems, $ships player ships in the hangar's boxes, weapon box tooltips: tallest $tallestTip, $tallTips over 620, $crew crew races" + (if (crewSkipped > 0) " ($crewSkipped without art skipped)" else "") +
            ", ${longest.size} longest in the sell tab and the equipment screen"
    }
}
