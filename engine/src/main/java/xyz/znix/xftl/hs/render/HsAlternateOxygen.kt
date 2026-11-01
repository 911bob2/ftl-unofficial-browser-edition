// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/AlternateOxygenRendering.cpp
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour

/**
 * `<alternateOxygenRendering enabled="true"><gradient><color r g b oxygen/>...`: rooms are filled with a colour
 * from the gradient by their oxygen level (0 to 100) instead of the white-to-red floor. Colour points without an
 * `oxygen` are spread evenly between their neighbours (the ends are 0 and 100); without a gradient the default
 * runs from (0, 0, 219) to (230, 226, 219). The More Info toggle switches it (HsMoreInfo.alternateOxygenRendering).
 */
object HsAlternateOxygen {
    private class Point(var oxygen: Float, val r: Float, val g: Float, val b: Float)

    private var parsedFor: HsConfig? = null
    private var gradient: List<Point> = emptyList()

    private fun channel(e: Element, name: String) = e.getAttributeValue(name)?.trim()?.toFloat()?.div(255f) ?: 0f

    /** ParseGradientNode and SetMissingGradientPoints. */
    private fun parseGradient(node: Element): List<Point> {
        val list = ArrayList<Point>()
        for (c in node.children) {
            if (c.name != "color") continue
            val ox = c.getAttributeValue("oxygen")?.trim()?.toFloat() ?: -1f
            list += Point(ox, channel(c, "r"), channel(c, "g"), channel(c, "b"))
        }
        if (list.isEmpty()) return list
        if (list.first().oxygen == -1f) list.first().oxygen = 0f
        if (list.last().oxygen == -1f) list.last().oxygen = 100f
        var a = 0
        for (i in 1 until list.size) {
            if (list[i].oxygen != -1f) {
                for (j in a + 1 until i) {
                    list[j].oxygen = list[a].oxygen * (i - j) / (i - a) + list[i].oxygen * (j - a) / (i - a)
                }
                a = i
            }
        }
        return list
    }

    private fun load(): List<Point> {
        val cfg = HS.cfg()
        if (parsedFor !== cfg) {
            var g: List<Point> = emptyList()
            // Resources::ParseHyperspaceFile: only an enabled node parses its gradient (the last one wins)
            for (node in cfg.nodesOf("alternateOxygenRendering")) {
                if (node.getAttributeValue("enabled")?.trim() != "true") continue
                val parsed = node.getChild("gradient")?.let { parseGradient(it) } ?: emptyList()
                if (parsed.isNotEmpty()) g = parsed
                if (g.isEmpty()) g = listOf(Point(0f, 0f, 0f, 219f / 255f), Point(100f, 230f / 255f, 226f / 255f, 219f / 255f))
            }
            gradient = g
            parsedFor = cfg
        }
        return gradient
    }

    /** AlternateOxygenManager::CalculateGradient for a room's oxygen level in percent. */
    @JvmStatic
    fun colour(oxygenPercent: Float): Colour {
        val g = load()
        if (g.isEmpty()) return Colour(1f, 1f, 1f, 1f)
        for (i in g.indices.reversed()) {
            val it = g[i]
            if (oxygenPercent >= it.oxygen) {
                if (i == g.size - 1) return Colour(it.r, it.g, it.b, 1f)
                val next = g[i + 1]
                val x = (oxygenPercent - it.oxygen) / (next.oxygen - it.oxygen)
                return Colour(it.r * (1 - x) + next.r * x, it.g * (1 - x) + next.g * x, it.b * (1 - x) + next.b * x, 1f)
            }
        }
        return Colour(g[0].r, g[0].g, g[0].b, 1f)
    }
}
