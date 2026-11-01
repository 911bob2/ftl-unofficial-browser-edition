// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomColors.cpp
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour

/**
 * hyperspace.xml `<colors>`: `<textButtons>` replaces the default colours of every TextButton (inactive, active,
 * selected = hovered, text; TextButton::InitPrimitives), of the autofire button (WeaponControl::SetAutofiring: off,
 * off hovered, on, on hovered) and of the FTL jump button (FTLButton::OnRender through TextButton::OnRender: no
 * engines/piloting, its text, active, hovered, text, hovered text, no fuel, its text); `<systemDetails>` replaces
 * InfoBox::detailsBarOn/Off (the level rows of the system details box). Values are 0..255 with alpha 0..1
 * (ParseColorNode). The defaults equal the vanilla engine constants, so without `<colors>` nothing changes; outside
 * Hyperspace every getter returns the vanilla constant.
 *
 * `<customChoiceColors enabled>` (ParseChoiceColorsNode, CustomColors.cpp): named colour sets (`<choiceColor name>` with
 * `normal`, `hover`, `disabled`, `blue_option`; r, g, b and a all out of 255) that a choice's `<text color="name">`
 * picks; the choice box replaces its white / hover / disabled / blue text colours with the set's, and with
 * `<syncResourceBoxColor>` the choice's resource box takes the colour too ([choiceText], [resourceBoxColour]).
 */
object HsColors {
    class Set(
        @JvmField val text: Array<Colour>,
        @JvmField val autofire: Array<Colour>,
        @JvmField val jump: Array<Colour>,
        @JvmField val detailsOn: Colour,
        @JvmField val detailsOff: Colour,
    )

    private var parsedFor: HsConfig? = null
    private var set: Set? = null

    private fun c255(r: Int, g: Int, b: Int) = Colour(r, g, b)

    /** g_defaultTextButtonColors, g_autofireButtonColors and g_jumpButtonColors. */
    @JvmStatic
    fun defaults() = Set(
        arrayOf(c255(164, 171, 160), c255(235, 245, 229), c255(255, 230, 94), c255(25, 49, 51)),
        arrayOf(c255(164, 171, 160), c255(200, 205, 198), c255(255, 230, 94), c255(255, 240, 158)),
        arrayOf(c255(164, 171, 160), c255(25, 49, 51), c255(235, 245, 0), c255(235, 245, 0),
            c255(37, 74, 77), c255(62, 125, 131), c255(253, 84, 70), c255(25, 49, 51)),
        Colour(Constants.UPGRADE_DETAILS_BG_ON), Colour(Constants.UPGRADE_DETAILS_BG_OFF),
    )

    private val TEXT = mapOf("inactive" to 0, "active" to 1, "selected" to 2, "text" to 3)
    private val AUTOFIRE = mapOf("autofireOff" to 0, "autofireOffSelected" to 1, "autofireOn" to 2, "autofireOnSelected" to 3)
    private val JUMP = mapOf("jumpNoEngines" to 0, "jumpNoEnginesText" to 1, "jumpActive" to 2, "jumpSelected" to 3,
        "jumpText" to 4, "jumpTextSelected" to 5, "jumpNoFuel" to 6, "jumpNoFuelText" to 7)

    /** ParseColorNode(color, node, divide=false): r, g, b out of 255, a as is; a missing attribute keeps its value. */
    @JvmStatic
    fun parseColour(into: Colour, n: Element) {
        n.getAttributeValue("r")?.let { into.r = it.trim().toFloat() / 255f }
        n.getAttributeValue("g")?.let { into.g = it.trim().toFloat() / 255f }
        n.getAttributeValue("b")?.let { into.b = it.trim().toFloat() / 255f }
        n.getAttributeValue("a")?.let { into.a = it.trim().toFloat() }
    }

    @JvmStatic
    fun parse(nodes: List<Element>): Set {
        val s = defaults()
        // Every <colors> node in document order (Resources: ParseCustomColorsNode per node), later values win.
        for (colors in nodes) for (parent in colors.children) {
            when (parent.name) {
                "textButtons" -> for (c in parent.children) {
                    TEXT[c.name]?.let { parseColour(s.text[it], c) }
                    AUTOFIRE[c.name]?.let { parseColour(s.autofire[it], c) }
                    JUMP[c.name]?.let { parseColour(s.jump[it], c) }
                }
                "systemDetails" -> for (c in parent.children) {
                    if (c.name == "detailsBarOn") parseColour(s.detailsOn, c)
                    if (c.name == "detailsBarOff") parseColour(s.detailsOff, c)
                }
            }
        }
        return s
    }

    @JvmStatic
    fun set(): Set {
        val cfg = HS.cfg()
        if (parsedFor !== cfg) { set = parse(cfg.nodesOf("colors")); parsedFor = cfg }
        return set!!
    }

    // ---- TextButton colours (vanilla constants outside Hyperspace) ----
    @JvmStatic fun tbInactive(): Colour = if (HS.active) set().text[0] else Constants.TEXT_BUTTON_DISABLED
    @JvmStatic fun tbActive(): Colour = if (HS.active) set().text[1] else Constants.TEXT_BUTTON_NORMAL
    @JvmStatic fun tbSelected(): Colour = if (HS.active) set().text[2] else Constants.TEXT_BUTTON_HOVER
    @JvmStatic fun tbText(): Colour = if (HS.active) set().text[3] else Constants.TEXT_BUTTON_TEXT

    /** TextButton::OnRender: inactive, hovered (selected) or idle (active). */
    @JvmStatic
    fun tbFill(disabled: Boolean, hovered: Boolean): Colour = when {
        disabled -> tbInactive()
        hovered -> tbSelected()
        else -> tbActive()
    }

    /** The autofire button's fill under Hyperspace (WeaponControl::SetAutofiring). */
    @JvmStatic
    fun autofire(on: Boolean, hovered: Boolean): Colour = set().autofire[(if (on) 2 else 0) + (if (hovered) 1 else 0)]

    /** g_jumpButtonColors[i] under Hyperspace. */
    @JvmStatic
    fun jump(i: Int): Colour = set().jump[i]

    @JvmStatic fun detailsBarOn(): Colour = if (HS.active) set().detailsOn else Constants.UPGRADE_DETAILS_BG_ON
    @JvmStatic fun detailsBarOff(): Colour = if (HS.active) set().detailsOff else Constants.UPGRADE_DETAILS_BG_OFF

    // ---- customChoiceColors ----

    private class ChoiceColour(
        val normal: Colour = Colour(1f, 1f, 1f, 1f),
        val hover: Colour = Colour(0.9529412f, 1f, 0.3137255f, 1f),
        val disabled: Colour = Colour(0.5882353f, 0.5882353f, 0.5882353f, 1f),
        val blue: Colour = Colour(0f, 0.7647059f, 1f, 1f),
    )

    private var choiceParsedFor: HsConfig? = null
    private var choiceEnabled = false
    private var syncBox = false
    private val choiceMap = HashMap<String, ChoiceColour>()

    /** ParseChoiceColorsNode's colour: r, g, b and an optional a, each divided by 255. */
    private fun choiceComponent(into: Colour, n: Element?) {
        n ?: return
        into.r = n.getAttributeValue("r").trim().toFloat() / 255f
        into.g = n.getAttributeValue("g").trim().toFloat() / 255f
        into.b = n.getAttributeValue("b").trim().toFloat() / 255f
        n.getAttributeValue("a")?.let { into.a = it.trim().toFloat() / 255f }
    }

    private fun parseChoiceColours() {
        val cfg = HS.cfg()
        if (choiceParsedFor === cfg) return
        choiceParsedFor = cfg
        choiceEnabled = false; syncBox = false; choiceMap.clear()
        for (node in cfg.nodesOf("customChoiceColors")) {
            // g_enableCustomChoiceColors is set by every node; a disabled one skips its children
            choiceEnabled = node.getAttributeValue("enabled")?.trim() == "true"
            if (!choiceEnabled) continue
            for (c in node.children) {
                if (c.name == "syncResourceBoxColor") syncBox = c.text.trim() == "true"
                if (c.name != "choiceColor") continue
                val name = c.getAttributeValue("name") ?: continue
                if (choiceMap.containsKey(name)) continue
                val cc = ChoiceColour()
                choiceComponent(cc.normal, c.getChild("normal"))
                choiceComponent(cc.hover, c.getChild("hover"))
                choiceComponent(cc.disabled, c.getChild("disabled"))
                choiceComponent(cc.blue, c.getChild("blue_option"))
                choiceMap[name] = cc
            }
        }
    }

    /** g_currentChoiceColor / g_currentChoiceIsCostumColored: persist between frames and choices, as in HS. */
    private var lastChoiceColour: Colour? = null
    private var lastChoiceCustom = false

    /**
     * easy_printAutoNewlines hook while a choice box renders: the colour for a choice whose `<text color>` is [name]
     * (null: no flag), given the colour the box would use. DecodeChoiceColorName maps the white / hover yellow /
     * disabled gray / blue values to the set's; any other colour (and an unknown name) is kept.
     */
    @JvmStatic
    fun choiceText(name: String?, current: Colour): Colour {
        if (!HS.active || name == null) return current
        parseChoiceColours()
        if (!choiceEnabled) return current
        val cc = choiceMap[name] ?: return current.also { lastChoiceColour = it; lastChoiceCustom = true }
        fun eq(r: Float, g: Float, b: Float) = Math.abs(current.r - r) < 1e-3f && Math.abs(current.g - g) < 1e-3f && Math.abs(current.b - b) < 1e-3f && Math.abs(current.a - 1f) < 1e-3f
        val out = when {
            eq(1f, 1f, 1f) -> cc.normal
            eq(0.9529412f, 1f, 0.3137255f) -> cc.hover
            eq(0.5882353f, 0.5882353f, 0.5882353f) -> cc.disabled
            eq(0f, 0.7647059f, 1f) -> cc.blue
            else -> current
        }
        lastChoiceColour = out; lastChoiceCustom = true
        return out
    }

    /**
     * ChoiceBox::PrintResourceBox hook for a choice's box: with syncResourceBoxColor, the colour of the choice text
     * printed last when that was a custom one (the flag is cleared by the box), else [default].
     */
    @JvmStatic
    fun resourceBoxColour(default: Colour): Colour {
        if (!HS.active) return default
        parseChoiceColours()
        if (!syncBox || !choiceEnabled || !lastChoiceCustom) return default
        lastChoiceCustom = false
        return lastChoiceColour ?: default
    }
}
