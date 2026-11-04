// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/EventButtons.cpp and EventButtons.h
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.EventQueueEvent
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsReq
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input

/**
 * Event buttons: `<eventButton name>` definitions (an event's child creates the button when the event is shown,
 * `<eventButton load="name"/>` reuses a named one; `<clearEventButton name>` removes buttons, all without a name).
 * Each button is an image button (`<image border>`: `img/<image>_on/_off/_select2.png`, the hit box inset by the
 * border) at `<customLocation x y>` or in the top bar (`<location>` 0: a second row at y 80; 1: before the ship
 * button; 2: after it; 3: after the store button; 4+: after the options button), moving the vanilla buttons along
 * (ShipStatus::OnLoop). It is active when `noDanger` is off or the ship button is (no danger), and its `<req>`
 * passes (an advanced HasEquipment check, `lvl`/`max_lvl`); a left click on an active one queues its `<event>`
 * (CustomEventsParser::QueueEvent, unseeded) and removes a `once` button; `jumpClear` buttons go with the jump.
 * The tooltip is `<tooltip>` or, inactive, `<inactiveTooltip>` (literal text, or a text id with `id`).
 *
 * `<text>` buttons are TextButtons (font 62, auto width, margin `border` on `<text>`) with the image as their base
 * behind them (three parts once wider than the image) and the `<color>` / `activeColor` / `inactiveColor` / `selectedColor` / `textColor` fills
 * (defaults from the `<colors><textButtons>` set when the definition is parsed); `hideInactive` takes an inactive
 * button out of the layout, the drawing and the mouse.
 */
object HsEventButtons {
    class Def(@JvmField val idx: Int) {
        @JvmField var name = ""
        @JvmField var event = ""
        @JvmField var image = ""
        @JvmField var text = ""
        @JvmField var location = 0
        @JvmField var customX = -1024
        @JvmField var customY = -1024
        @JvmField var border = 12
        @JvmField var req = ""
        @JvmField var lvl = 1
        @JvmField var maxLvl = Int.MAX_VALUE
        @JvmField var jumpClear = false
        @JvmField var once = false
        @JvmField var hideInactive = false
        @JvmField var marginText = 3
        @JvmField var inactiveColor = Colour(HsColors.set().text[0])
        @JvmField var activeColor = Colour(HsColors.set().text[1])
        @JvmField var selectedColor = Colour(HsColors.set().text[2])
        @JvmField var textColor = Colour(HsColors.set().text[3])
        @JvmField var textId = false
        @JvmField var noDanger = false
        @JvmField var tooltip = ""
        @JvmField var tooltipId = false
        @JvmField var inactiveTooltip = ""
        @JvmField var inactiveTooltipId = false
    }

    // ---- definitions (EventButtonManager::defs / namedDefs), rebuilt with the event data ----
    private val defs = ArrayList<Def>()
    private val named = HashMap<String, Def>()
    /** The parsed definition of each event's `<eventButton>` child (HsEventParser keeps the elements). */
    private val byElement = java.util.IdentityHashMap<Element, Def>()

    // ---- the run's buttons (EventButtonManager::buttons) ----
    private val buttons = ArrayList<Def>()

    /** The name of the last button clicked (for the debug probes). */
    @JvmField var lastClicked: String? = null

    /** Changes whenever the button list does; PlayerShipUI rebuilds its top bar when it differs. */
    @JvmField var version = 0

    /** Whether a game file exists (set with the definitions, from the content they are parsed from); null: assume so. */
    private var fileExists: ((String) -> Boolean)? = null

    @JvmStatic
    @JvmOverloads
    fun resetDefs(exists: ((String) -> Boolean)? = null) {
        defs.clear(); named.clear(); byElement.clear(); buttons.clear(); version++
        fileExists = exists
    }

    @JvmStatic
    fun buttons(): List<Def> = buttons

    private fun bool(s: String?) = s?.trim() == "true"

    /** EventButtonManager::ParseEventButton. [processEvent] is EventsParser::ProcessEvent for the inline `<event>`. */
    @JvmStatic
    fun parse(node: Element, processEvent: (Element, String) -> String): Def? {
        byElement[node]?.let { return it }
        node.getAttributeValue("load")?.let { load ->
            val d = named[load]
            if (d == null) HS.unsupported("event", "eventButton", "load=$load (unknown)")
            else byElement[node] = d
            return d
        }
        val d = Def(defs.size)
        defs += d
        node.getAttributeValue("name")?.let { d.name = it; named[it] = d }
        for (c in node.children) {
            when (c.name) {
                "event" -> d.event = processEvent(c, "__eventButton")
                "image" -> { d.image = c.text; c.getAttributeValue("border")?.let { d.border = it.trim().toInt() } }
                "text" -> {
                    d.text = c.getAttributeValue("id") ?: c.text; d.textId = c.getAttributeValue("id") != null
                    c.getAttributeValue("border")?.let { d.marginText = it.trim().toInt() }
                }
                "location" -> d.location = c.text.trim().toInt()
                "customLocation" -> {
                    d.location = -1
                    c.getAttributeValue("x")?.let { d.customX = it.trim().toInt() }
                    c.getAttributeValue("y")?.let { d.customY = it.trim().toInt() }
                }
                "tooltip" -> { val id = c.getAttributeValue("id"); d.tooltip = id ?: c.text; d.tooltipId = id != null }
                "inactiveTooltip" -> {
                    val id = c.getAttributeValue("id"); d.inactiveTooltip = id ?: c.text; d.inactiveTooltipId = id != null
                }
                "req" -> {
                    d.req = c.text
                    c.getAttributeValue("max_lvl")?.let { d.maxLvl = it.trim().toInt(); d.lvl = -Int.MAX_VALUE }
                    c.getAttributeValue("lvl")?.let { d.lvl = it.trim().toInt() }
                }
                "jumpClear" -> d.jumpClear = bool(c.text)
                "once" -> d.once = bool(c.text)
                "noDanger" -> d.noDanger = bool(c.text)
                "hideInactive" -> d.hideInactive = bool(c.text)
                "color", "activeColor" -> HsColors.parseColour(d.activeColor, c)
                "inactiveColor" -> HsColors.parseColour(d.inactiveColor, c)
                "selectedColor" -> HsColors.parseColour(d.selectedColor, c)
                "textColor" -> HsColors.parseColour(d.textColor, c)
            }
        }
        byElement[node] = d
        return d
    }

    /** CustomCreateLocation: clearEventButton, then the event's buttons (sorted by location, stable). */
    @JvmStatic
    fun apply(clear: List<String>, create: List<Element>) {
        if (clear.isEmpty() && create.isEmpty()) return
        for (name in clear) {
            if (name.isEmpty()) buttons.clear() else buttons.removeAll { it.name == name }
        }
        for (e in create) byElement[e]?.let { buttons += it }
        if (create.isNotEmpty()) buttons.sortBy { it.location } // stable
        version++
    }

    /** WorldManager::ClearLocation: the jumpClear buttons go. */
    @JvmStatic
    fun clearLocation() {
        if (buttons.removeAll { it.jumpClear }) version++
    }

    /** A new run (CustomEventsParser's reset). */
    @JvmStatic
    fun newGame() {
        buttons.clear(); version++
    }

    // ---- save (EventButtonManager::Save/Load: the definition indices) ----
    // The index is native's: definitions are numbered as the event files are parsed, every file at load in one order, so
    // the same mods give the same numbers in every session. A name may have several definitions (Multiverse has four
    // UI_STORAGE_CHECK_BUTTON: three at location 2 with `top_storage`, one at 0 with `top_storage_on`, which draws
    // nothing, in SPAWN_STORAGE, an event nothing loads). The save also keeps name, location and image, for a mod list
    // that changed: loading takes the definition at the index when those match (EventButtonManager::Load), else the first
    // matching all three, else the index if its name matches, else the name.
    // Saves of 2.0.1 kept only index and name and loaded by the name, which is the last definition parsed (Multiverse's
    // SPAWN_STORAGE one), and a run saved again kept that one. A loaded button that draws nothing (no image, no text) while
    // another definition of its name does is that substitution, never a native run's: it gets the first that draws.
    @JvmStatic
    fun write(elem: Element) {
        for (b in buttons) elem.addContent(Element("b").setAttribute("idx", b.idx.toString()).setAttribute("name", b.name)
            .setAttribute("loc", b.location.toString()).setAttribute("img", b.image))
    }

    @JvmStatic
    fun read(elem: Element?) {
        buttons.clear()
        elem?.getChildren("b")?.forEach { e ->
            val name = e.getAttributeValue("name") ?: ""
            val idx = e.getAttributeValue("idx")?.toIntOrNull()
            val d = if (name.isEmpty()) idx?.let { defs.getOrNull(it) } else {
                val loc = e.getAttributeValue("loc")?.toIntOrNull()
                val img = e.getAttributeValue("img")
                fun same(it: Def) = it.name == name && (loc == null || (it.location == loc && it.image == img))
                idx?.let { defs.getOrNull(it) }?.takeIf(::same)
                    ?: defs.firstOrNull { it.name == name && loc != null && it.location == loc && it.image == img }
                    ?: idx?.let { defs.getOrNull(it) }?.takeIf { it.name == name }
                    ?: named[name]
            }
            if (d != null) buttons += repaired(d)
        }
        version++
    }

    /** Whether [d] draws anything: a label, or its `_on` image in the game files. */
    private fun draws(d: Def): Boolean = d.text.isNotEmpty() || fileExists?.invoke("img/${d.image}_on.png") != false

    /** [d], or for one that draws nothing the first definition of its name that does (see the save notes above). */
    private fun repaired(d: Def): Def {
        if (d.name.isEmpty() || draws(d)) return d
        return defs.firstOrNull { it.name == d.name && draws(it) } ?: d
    }

    @JvmStatic
    fun registerSave() {
        xyz.znix.xftl.hs.save.HsSave.register("eventButtons", xyz.znix.xftl.hs.save.HsSave.Scope.RUN, ::write, ::read)
    }

    // ---- the top bar (ShipStatus::OnLoop) ----

    /**
     * Where the vanilla top-bar buttons go and where each event button's image is, from [shipX] (the vanilla
     * ship button's hit box x; its image is 7 px up and left). FTL's own button positions are image corners:
     * the upgrade button's is the hit box less 7 and `upgradeButton.hitbox.w + 15` = 77 (the vanilla ship-to-store
     * step), the store's `hitbox.w + 3` = 93 and 12 before it (the vanilla store-to-options step 105). The options
     * button's hit box width (only for location 4+, unused by Multiverse) is taken as its 41 px.
     * Inactive `hideInactive` buttons are left out ([positions] has no entry for them, ShipStatus::OnLoop skips them).
     */
    class Layout(@JvmField val shipX: Int, @JvmField val storeX: Int, @JvmField val optionsX: Int,
                 @JvmField val positions: Map<Def, ConstPoint>)

    /** The ship button's bActive: no danger (and the tutorial allows the ship window). */
    @JvmStatic
    fun shipActive(game: InGameState): Boolean =
        !(game.isInDanger || game.mainGame?.tutorialAdapter?.controller?.shipOpeningAllowed == false)

    /** EventButton's bActive (ShipStatus::OnLoop): no danger if wanted, and the requirement. */
    private fun active(game: InGameState, d: Def): Boolean = (!d.noDanger || shipActive(game)) && checkReq(game, d)

    /** Which `hideInactive` buttons are shown; the top bar is rebuilt when it changes (HS lays out every frame). */
    @JvmStatic
    fun hideSignature(game: InGameState): Int {
        var sig = 0
        for ((i, d) in buttons.withIndex()) if (d.hideInactive && active(game, d)) sig = sig or (1 shl (i and 31))
        return sig
    }

    @JvmStatic
    fun layout(game: InGameState, shipX: Int, hasStore: Boolean): Layout {
        val pos = LinkedHashMap<Def, ConstPoint>()
        var i = 0
        val list = buttons
        fun hidden(d: Def) = d.hideInactive && !active(game, d)
        while (i < list.size && list[i].location <= -1) { pos[list[i]] = ConstPoint(list[i].customX, list[i].customY); i++ }
        val x0 = shipX - 7
        var x = x0
        while (i < list.size && list[i].location <= 0) {
            if (!hidden(list[i])) { pos[list[i]] = ConstPoint(x, 80); x += width(game, list[i]) + 3 }
            i++
        }
        x = x0
        while (i < list.size && list[i].location <= 1) {
            if (!hidden(list[i])) { pos[list[i]] = ConstPoint(x, 22); x += width(game, list[i]) + 3 }
            i++
        }
        val newShipX = x + 7
        x += 77
        while (i < list.size && list[i].location <= 2) {
            if (!hidden(list[i])) { pos[list[i]] = ConstPoint(x, 22); x += width(game, list[i]) + 3 }
            i++
        }
        var storeX = -1
        if (hasStore) { x += 12; storeX = x + 7; x += 93 }
        while (i < list.size && list[i].location <= 3) {
            if (!hidden(list[i])) { pos[list[i]] = ConstPoint(x, 22); x += width(game, list[i]) + 3 }
            i++
        }
        val optionsX = x + 7
        x += 41 + 3
        while (i < list.size) {
            if (!hidden(list[i])) { pos[list[i]] = ConstPoint(x, 22); x += width(game, list[i]) + 3 }
            i++
        }
        return Layout(newShipX, storeX, optionsX, pos)
    }

    private fun image(game: InGameState, d: Def, state: String): Image? {
        val path = "img/${d.image}_$state.png"
        if (game.content.datafile.getOrNull(path) == null) return null
        return game.getImg(path)
    }

    /** A text button's base image (EventButton::OnInitText: the image name plus .png). */
    private fun baseImage(game: InGameState, d: Def): Image? {
        val path = "img/${d.image}.png"
        if (game.content.datafile.getOrNull(path) == null) return null
        return game.getImg(path)
    }

    private fun label(game: InGameState, d: Def) = if (d.textId) game.translator[d.text] else d.text

    private fun textFont(game: InGameState) = NativeFonts.font(game::getFont, 62)

    /**
     * A text button's hit box width (TextButton::UpdateAutoWidth with margin 3): the label's measured width plus
     * twice the margin, never narrower than the base image less its borders.
     */
    private fun textWidth(game: InGameState, d: Def): Int {
        val base = (baseImage(game, d)?.width ?: 0) - 2 * d.border
        return maxOf(base, textFont(game).measure(label(game, d)) + 3 * 2)
    }

    /** EventButton::dimension.x: the `_on` image's width, or a text button's hit box width plus both borders. */
    private fun width(game: InGameState, d: Def): Int =
        if (d.text.isNotEmpty()) textWidth(game, d) + 2 * d.border else image(game, d, "on")?.width ?: 0

    /** EventButton::CheckReq: an advanced HasEquipment of the player ship between lvl and max_lvl. */
    private fun checkReq(game: InGameState, d: Def): Boolean {
        if (d.req.isEmpty()) return true
        val player = game.player ?: return false
        var lvl = 0
        HsEvents.withCheck(3) { lvl = HsReq.hasEquipment(game, player, d.req) }
        return lvl >= d.lvl && lvl <= d.maxLvl
    }

    /** The engine button for [d] with its image corner at [imagePos]; [shipActive] is the ship button's state. */
    @JvmStatic
    fun button(game: InGameState, d: Def, imagePos: ConstPoint, shipActive: () -> Boolean): Button {
        val b = d.border
        if (d.text.isNotEmpty()) {
            val base = baseImage(game, d)
            val w = textWidth(game, d)
            val h = maxOf(0, (base?.height ?: 0) - 2 * b)
            return EventButton(game, d, imagePos, ConstPoint(imagePos.x + b, imagePos.y + b), ConstPoint(w, h), shipActive)
        }
        val on = image(game, d, "on")
        val w = maxOf(0, (on?.width ?: 0) - 2 * b)
        val h = maxOf(0, (on?.height ?: 0) - 2 * b)
        return EventButton(game, d, imagePos, ConstPoint(imagePos.x + b, imagePos.y + b), ConstPoint(w, h), shipActive)
    }

    /** The definition of an event button from [button], or null for any other button. */
    @JvmStatic
    fun defOf(b: Button): Def? = (b as? EventButton)?.def

    private class EventButton(game: InGameState, val def: Def, val imagePos: ConstPoint, pos: ConstPoint, size: ConstPoint,
                              val shipActive: () -> Boolean) : Button(game, pos, size) {
        private val imgOn = image(game, def, "on")
        private val imgOff = image(game, def, "off")
        private val imgSelect = image(game, def, "select2")
        private val isText = def.text.isNotEmpty()
        private val imgBase = if (isText) baseImage(game, def) else null
        private val font by lazy { textFont(game) }
        private val tooltip = object : DelayedTooltip(game) {
            override fun getText(): String {
                val active = !disabled
                val text = if (active) def.tooltip else def.inactiveTooltip
                val id = if (active) def.tooltipId else def.inactiveTooltipId
                return if (id) game.translator[text] else text
            }
        }

        // ShipStatus::OnLoop: bActive = (!noDanger || upgradeButton.bActive) && CheckReq()
        override val disabled: Boolean get() = !((!def.noDanger || shipActive()) && checkReq(game, def))

        override fun draw(g: Graphics) {
            if (isText) {
                // TextButton::OnRender: the base image behind it (InitPrimitives: native size at the button corner less the
                // border; once the button is wider than the image less its borders, its left half, one stretched column of
                // the image's middle and its right half), then the chamfered fill (inactive, selected or active colour)
                // and the label in font 62
                val b = def.border
                imgBase?.let { img ->
                    val x = imagePos.x.toFloat()
                    val y = imagePos.y.toFloat()
                    val w = img.width
                    val h = img.height.toFloat()
                    val baseWidth = w - 2 * b
                    if (size.x <= baseWidth) {
                        img.draw(x, y)
                    } else {
                        val half = w / 2
                        val extra = (size.x - baseWidth).toFloat()
                        img.draw(x, y, x + half, y + h, 0f, 0f, half.toFloat(), h)
                        img.draw(x + half, y, x + half + extra, y + h, half.toFloat(), 0f, half + 1f, h)
                        img.draw(x + half + extra, y, x + w + extra, y + h, half.toFloat(), 0f, w.toFloat(), h)
                    }
                }
                g.colour = when {
                    disabled -> def.inactiveColor
                    hovered -> def.selectedColor
                    else -> def.activeColor
                }
                xyz.znix.xftl.game.Buttons.drawChamfer(g, pos.x, pos.y, size.x, size.y, def.marginText)
                font.printButtonLabel(pos.x, pos.y, size.x, size.y, label(game, def), def.textColor)
            } else {
                // Button::OnRender: off while inactive, select2 while hovered, on otherwise
                val img = when {
                    disabled -> imgOff
                    hovered -> imgSelect
                    else -> imgOn
                }
                img?.draw(imagePos)
            }
            if (rawHovered) g.tooltip = tooltip
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON) return
            // CommandGui::LButtonDown: queue the event (todo in HS: seeded), drop a once button
            HsEvents.eventQueue.addLast(EventQueueEvent(def.event, "", -1))
            lastClicked = def.name
            if (def.once) { buttons.remove(def); version++ }
        }
    }

    @JvmStatic
    fun capabilityNames() = arrayOf("eventButton", "clearEventButton")
}
