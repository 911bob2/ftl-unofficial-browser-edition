// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/EnemyShipIcons.cpp and the icon parts of
// src/game/Ship/CustomShips.cpp (ShipManager_Extend::Initialize) and src/game/Ship/CustomAugments.cpp (UpdateAugments)
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.ITooltipProvider
import xyz.znix.xftl.rendering.StandardTooltip

/**
 * Enemy ship icons (`<ships><shipIcons>`: `normalPos`, `bossPos` and `<shipIcon><name><tooltip>`): after
 * CombatControl::RenderTarget the target's icons stack upward from the box position, each
 * `combatUI/box_hostiles_icon.png` in the hostile box colour with `combatUI/icons/<name>.png` centred on it (+1 px
 * right); first the ship's own (`<shipIcons>` of its definition, in order), then its augments' icons
 * (`<icon ship="player|enemy">`: augments by name, hidden ones included, with a count above 0). Hovering one shows its
 * tooltip at once.
 */
object HsShipIcons {
    class Def(@JvmField val name: String, @JvmField val tooltip: String, @JvmField val tooltipId: Boolean)

    class Config(@JvmField val normalX: Int, @JvmField val normalY: Int, @JvmField val bossX: Int, @JvmField val bossY: Int,
                 @JvmField val defs: Map<String, Def>)

    private var parsedFor: HsConfig? = null
    private var config: Config? = null

    @JvmStatic
    fun config(): Config {
        val cfg = HS.cfg()
        if (parsedFor !== cfg) { config = parse(xyz.znix.xftl.hs.ship.HsShips.shipIconNodes); parsedFor = cfg }
        return config!!
    }

    /** ShipIconManager::ParseShipIconNode for each node, in order. */
    @JvmStatic
    fun parse(nodes: List<Element>): Config {
        var nx = 0; var ny = 0; var bx = 0; var by = 0
        val defs = HashMap<String, Def>()
        for (node in nodes) for (c in node.children) {
            fun f(a: String) = c.getAttributeValue(a)?.trim()?.toFloat()?.toInt() ?: 0
            when (c.name) {
                "normalPos" -> { nx = f("x"); ny = f("y") }
                "bossPos" -> { bx = f("x"); by = f("y") }
                "shipIcon" -> {
                    var name = ""; var tip = ""; var id = false
                    for (ic in c.children) {
                        when (ic.name) {
                            "name" -> name = ic.text
                            "tooltip" -> { val i = ic.getAttributeValue("id"); tip = i ?: ic.text; id = i != null }
                        }
                        defs[name] = Def(name, tip, id)
                    }
                }
            }
        }
        return Config(nx, ny, bx, by, defs)
    }

    /** The target's icons: the ship's own, then its augments' (UpdateAugments' augIconList). */
    @JvmStatic
    fun icons(ship: Ship): List<Def> {
        val c = config()
        val st = ship.hs ?: return emptyList()
        val out = ArrayList<Def>()
        for (n in st.shipIcons) c.defs[n]?.let { out += it }
        val shipId = if (ship.isPlayerShip) 0 else 1
        // UpdateAugments walks ShipInfo::augList (a std::map: hidden ones as "HIDDEN <name>", sorted with the prefix)
        // into the std::unordered_map augListWithHidden and lists the icons in that map's order (oracle r3-flag1:
        // the flagship's icons).
        val raw = java.util.TreeMap<String, Int>()
        for (a in ship.augments) raw[a.name] = (raw[a.name] ?: 0) + 1
        for ((n, cnt) in st.hiddenAugs) raw["HIDDEN $n"] = (raw["HIDDEN $n"] ?: 0) + cnt
        val hidden = xyz.znix.xftl.hs.events.HsGnuStringMap<IntArray>()
        for ((key, cnt) in raw) {
            val name = if (key.startsWith("HIDDEN ")) key.substring(7) else key
            hidden.emplace(name, IntArray(1))
            hidden[name]!![0] += cnt
        }
        for (node in hidden.entries()) {
            val name = node.key
            val count = node.value[0]
            if (count <= 0) continue
            val d = xyz.znix.xftl.hs.ship.HsAugments.definition(name) ?: continue
            if (d.icon.isEmpty() || (d.iconShipId != -1 && d.iconShipId != shipId)) continue
            c.defs[d.icon]?.let { out += it }
        }
        return out
    }

    private val tooltips = HashMap<Def, ITooltipProvider>()

    /** CombatControl::RenderTarget hook (screen space) and the CommandGui::MouseMove tooltip. */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, ship: Ship, boss: Boolean, colour: Colour, mouseX: Int, mouseY: Int) {
        val list = icons(ship)
        if (list.isEmpty()) return
        val c = config()
        val px = if (boss) c.bossX else c.normalX
        val py = if (boss) c.bossY else c.normalY
        val box = game.getImg("img/combatUI/box_hostiles_icon.png")
        for ((i, d) in list.withIndex()) {
            val y = py - box.height * (i + 1)
            box.draw(px.toFloat(), y.toFloat(), colour)
            val path = "img/combatUI/icons/${d.name}.png"
            if (game.content.datafile.getOrNull(path) != null) {
                val icon = game.getImg(path)
                icon.draw((px + box.width / 2 - icon.width / 2 + 1).toFloat(), (y + box.height / 2 - icon.height / 2).toFloat())
            }
            // ShipIcon::MouseMove: strictly inside the box column and row
            if (mouseX > px && mouseX < px + box.width && mouseY > y && mouseY < py - box.height * i)
                g.tooltip = tooltips.getOrPut(d) {
                    object : StandardTooltip(game.getFont("JustinFont11Bold")) {
                        override fun getText() = if (d.tooltipId) game.translator[d.tooltip] else d.tooltip
                    }
                }
        }
    }
}
