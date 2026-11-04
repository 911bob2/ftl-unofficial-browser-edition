// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/TriggeredEvents.cpp (TriggeredEventBox,
// TriggeredEventGui, TriggeredEvent::OnRender/RenderAll) and CustomEvents.h (the box definition)
// (the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.game.GlowColour
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.WarningFlasher
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.rendering.StandardTooltip

/**
 * The on-screen part of triggered events: the timer / jump boxes (`<triggeredEventBox>`) in the top bar, over the
 * scrap counter, at the top right and beside the enemy ship's box, and each event's looping warning message.
 *
 * `TriggeredEventGui::CreateBoxes` lays the boxes out (rebuilt when [HsTriggeredEvents.guiReset] is set);
 * `ShipStatus::OnRender` draws the default, scrap and top-right boxes then every warning, and
 * `CombatControl::RenderTarget` the combat boxes translated by [normalBoxPos] / [bossBoxPos].
 */
object HsTriggeredGui {
    /** TriggeredEventGui::normalBoxPos / bossBoxPos (`<combatTimerPosition>` / `<combatBossTimerPosition>` overrides). */
    @JvmField var normalBoxX = 1259
    @JvmField var normalBoxY = 110
    @JvmField var bossBoxX = 1266
    @JvmField var bossBoxY = 72

    /** A TriggeredEventBox: the event, its definition and the box's top-left corner. */
    class Box(@JvmField val event: HsTriggeredEvent, @JvmField val def: TriggeredEventBoxDefinition, @JvmField val x: Int, @JvmField val y: Int) {
        val width get() = def.w
        val height get() = def.h
        val textX get() = x + def.textX
        val textY get() = y + def.textY
    }

    private val boxes = ArrayList<Box>()
    private var scrapBox: Box? = null
    private val topRightBoxes = ArrayList<Box>()
    private val combatBoxes = ArrayList<Box>()

    /** TriggeredEventGui::flashTracker: a 1 s loop, high for the first half. */
    private var flashTime = 0f
    private var lastFrame = -1L

    private val warnings = java.util.IdentityHashMap<HsTriggeredEvent, WarningFlasher>()
    private val tooltips = HashMap<String, StandardTooltip>()

    fun reset() {
        boxes.clear(); scrapBox = null; topRightBoxes.clear(); combatBoxes.clear()
        warnings.clear()
        flashTime = 0f
        HsTriggeredEvents.guiReset = true
    }

    /** TriggeredEventGui::CreateBoxes. */
    private fun createBoxes() {
        boxes.clear(); scrapBox = null; topRightBoxes.clear(); combatBoxes.clear()
        val default = ArrayList<HsTriggeredEvent>()
        val scrap = ArrayList<HsTriggeredEvent>()
        val topRight = ArrayList<HsTriggeredEvent>()
        val combat = ArrayList<HsTriggeredEvent>()
        var n = HsTriggeredEvents.eventList.first
        while (n != null) {
            val ev = n.value
            val box = ev.def.box
            if (box != null) when (box.boxPosition) {
                "SCRAP" -> scrap += ev
                "TOPRIGHT" -> topRight += ev
                "COMBAT" -> combat += ev
                else -> default += ev
            }
            n = n.next()
        }
        // std::sort on a few elements is an insertion sort: stable
        val byPriority = Comparator<HsTriggeredEvent> { a, b -> b.def.box!!.priority.compareTo(a.def.box!!.priority) }
        default.sortWith(byPriority); scrap.sortWith(byPriority); topRight.sortWith(byPriority); combat.sortWith(byPriority)

        var x = 132
        var y = 82
        for ((i, ev) in default.withIndex()) {
            val d = ev.def.box!!
            if (i > 0) x += d.left
            boxes += Box(ev, d, x, y)
            x += d.w + d.right
        }

        for (ev in scrap) {
            val d = ev.def.box!!
            x = (889 - d.w) / 2
            y = 50 + d.top
            scrapBox = Box(ev, d, x, y)
            break
        }

        x = 1277
        y = 3
        for ((i, ev) in topRight.withIndex()) {
            val d = ev.def.box!!
            if (i > 0) x -= d.right
            x -= d.w
            topRightBoxes += Box(ev, d, x, y)
            x -= d.left
        }

        y = 0
        for ((i, ev) in combat.withIndex()) {
            val d = ev.def.box!!
            if (i > 0) y += d.top
            combatBoxes += Box(ev, d, -d.right - d.w, y)
            y += d.h + d.bottom
        }
        HsTriggeredEvents.guiReset = false
    }

    private fun clock(t: Int): String {
        fun two(v: Int) = if (v < 10) "0$v" else "$v"
        return if (t >= 3600) "${t / 3600}:${two((t / 60) % 60)}:${two(t % 60)}" else "${t / 60}:${two(t % 60)}"
    }

    /** `%.1f` of a float. */
    private fun seconds(t: Float): String {
        val v = Math.round(t.toDouble() * 10.0) / 10.0
        val s = v.toString()
        return if (s.contains('.')) s else "$s.0"
    }

    private fun colour(c: FloatArray) = Colour(c[0], c[1], c[2], c[3])

    private fun image(game: InGameState, name: String) =
        "img/$name".let { path -> if (game.content.datafile.getOrNull(path) != null) game.getImg(path) else null }

    private var fontGame: InGameState? = null
    private var font0: NativeFonts.NativeFont? = null
    private fun numFont(game: InGameState): NativeFonts.NativeFont {
        if (fontGame !== game || font0 == null) { font0 = NativeFonts.font(game::getFont, 0); fontGame = game }
        return font0!!
    }

    /** TriggeredEventBox::OnRender; [offX], [offY] is the GL translation the box is drawn under. */
    private fun renderBox(game: InGameState, g: Graphics, b: Box, flash: Boolean, offX: Int, offY: Int) {
        val d = b.def
        val ev = b.event
        var useIcon2 = (ev.triggerTimer != null && ev.timeLeft < d.warningTime) || (ev.triggerJumps <= d.warningJumps)
        if (d.flash) useIcon2 = useIcon2 && flash

        val bgX = (b.x - d.x + offX).toFloat()
        val bgY = (b.y - d.y + offY).toFloat()
        val textColour: Colour
        if (useIcon2) {
            if (d.image2.isNotEmpty()) image(game, d.image2)?.draw(bgX, bgY, colour(d.imageColor2))
            textColour = colour(d.textColor2)
        } else {
            if (d.image.isNotEmpty()) image(game, d.image)?.draw(bgX, bgY, colour(d.imageColor))
            textColour = colour(d.textColor)
        }
        if (d.imageFront.isNotEmpty()) image(game, d.imageFront)?.draw((b.x + d.frontX + offX).toFloat(), (b.y + d.frontY + offY).toFloat(), colour(d.imageColorFront))

        val text = when (d.textType) {
            "JUMPS" -> ev.triggerJumps.toString()
            "REQ" -> ev.reqLvl.toString()
            "REQ_PROGRESS" -> "${ev.reqLvl}/${ev.reqMinLvl}"
            else -> {
                val t = ev.timeLeft
                if (d.textType == "TIME_CLOCK" || t >= 60f) clock(t.toInt()) else seconds(t)
            }
        }
        numFont(game).printCenter((b.textX + offX).toFloat(), (b.textY + offY).toFloat(), text, textColour, offX.toFloat())
    }

    /** TriggeredEventBox::MouseMove: a tooltip shown at once while the pointer is strictly inside the box. */
    private fun hover(game: InGameState, g: Graphics, b: Box, mx: Int, my: Int) {
        val tip = b.def.tooltip
        if (tip.isEmpty) return
        if (mx > b.x && mx < b.x + b.width && my > b.y && my < b.y + b.height) {
            val text = tip.text { game.translator[it] }
            g.tooltip = tooltips.getOrPut(text) {
                object : StandardTooltip(game) { override fun getText() = text }
            }
        }
    }

    private fun flashHigh(game: InGameState): Boolean {
        val now = System.nanoTime()
        if (lastFrame >= 0) {
            flashTime += ((now - lastFrame) / 1e9).toFloat().coerceAtMost(0.25f)
            while (flashTime >= 1f) flashTime -= 1f
        }
        lastFrame = now
        return flashTime < 0.5f
    }

    /** ShipStatus::OnRender (after the original): TriggeredEventGui::OnRender, then TriggeredEvent::RenderAll. */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, mouseX: Int, mouseY: Int) {
        if (HsTriggeredEvents.guiReset) createBoxes()
        val flash = flashHigh(game)
        for (b in boxes) renderBox(game, g, b, flash, 0, 0)
        scrapBox?.let { renderBox(game, g, it, flash, 0, 0) }
        for (b in topRightBoxes) renderBox(game, g, b, flash, 0, 0)

        for (b in boxes) hover(game, g, b, mouseX, mouseY)
        scrapBox?.let { hover(game, g, it, mouseX, mouseY) }
        for (b in topRightBoxes) hover(game, g, b, mouseX, mouseY)

        renderWarnings(game, g)
    }

    /**
     * One part of [render] (the touch layout places them apart): [PART_DEFAULT] the boxes of the top bar, [PART_SCRAP] the
     * box over the scrap counter, [PART_TOP_RIGHT] the top-right boxes, [PART_WARNINGS] the warning messages.
     */
    @JvmStatic
    fun renderPart(game: InGameState, g: Graphics, part: Int, mouseX: Int, mouseY: Int) {
        if (HsTriggeredEvents.guiReset) createBoxes()
        val list: List<Box> = when (part) {
            PART_DEFAULT -> boxes
            PART_SCRAP -> listOfNotNull(scrapBox)
            PART_TOP_RIGHT -> topRightBoxes
            else -> { renderWarnings(game, g); return }
        }
        if (list.isEmpty()) return
        val flash = flashHigh(game)
        for (b in list) renderBox(game, g, b, flash, 0, 0)
        for (b in list) hover(game, g, b, mouseX, mouseY)
    }

    const val PART_DEFAULT = 1
    const val PART_SCRAP = 2
    const val PART_TOP_RIGHT = 3
    const val PART_WARNINGS = 4

    /** CombatControl::RenderTarget (after the original): the combat boxes beside the enemy ship's box. */
    @JvmStatic
    fun renderCombat(game: InGameState, g: Graphics, boss: Boolean, mouseX: Int, mouseY: Int) {
        if (HsTriggeredEvents.guiReset) createBoxes()
        if (combatBoxes.isEmpty()) return
        val flash = flashTime < 0.5f
        val ox = if (boss) bossBoxX else normalBoxX
        val oy = if (boss) bossBoxY else normalBoxY
        for (b in combatBoxes) renderBox(game, g, b, flash, ox, oy)
        for (b in combatBoxes) hover(game, g, Box(b.event, b.def, b.x + ox, b.y + oy), mouseX, mouseY)
    }

    /** TriggeredEvent::RenderAll: each event's WarningMessage loop and render. */
    private fun renderWarnings(game: InGameState, g: Graphics) {
        var n = HsTriggeredEvents.eventList.first
        while (n != null) {
            val ev = n.value
            val w = ev.def.warning
            if (w != null) {
                val flasher = warnings.getOrPut(ev) { createWarning(game, w) }
                flasher.loopWhile(ev.warningActive)
                flasher.draw(g)
            }
            n = n.next()
        }
    }

    private fun createWarning(game: InGameState, w: TriggeredEventWarningDefinition): WarningFlasher {
        val text = if (w.text.isLiteral) w.text.data else game.translator[w.text.data]
        // WarningMessage::InitText takes the colour in 0-255 units; the glow is the red one for HS's default colour
        // (253, 84, 70) and the white one for any other (the oracle: a green, a blue and a pure red warning glow white).
        val c = w.textColor
        val isDefault = c[0] == 253f && c[1] == 84f && c[2] == 70f
        val colour = if (isDefault) GlowColour.RED else GlowColour.WHITE
        val textColour = if (isDefault) null else xyz.znix.xftl.rendering.Colour(
            c[0].toInt().coerceIn(0, 255), c[1].toInt().coerceIn(0, 255), c[2].toInt().coerceIn(0, 255)
        )
        val f = WarningFlasher(
            game, ConstPoint(w.x, WarningFlasher.nativeCentreY(w.y, text.split("\n").size, w.useWarningLine)), "warning_warning",
            w.useWarningLine, colour = colour, textColour = textColour, animated = w.flash, underline = w.useWarningLine, centred = w.centerText,
            sound = w.sound.ifEmpty { null }
        )
        f.setLiteral(text)
        return f
    }
}
