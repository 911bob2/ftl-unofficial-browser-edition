// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomLockdowns.cpp / .h (CustomLockdownDefinition::ParseNode,
// defaultLockdown), src/game/Utilities/Resources.cpp (`<defaults><customLockdown>` parsed into defaultLockdown).
package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.LockdownDefinition
import xyz.znix.xftl.hs.stat.HsParse
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour

/** CustomLockdownDefinition: the lockdown a weapon, power or Lua call applies; [defaults] is `defaultLockdown`. */
object HsLockdowns {
    /** CustomLockdownDefinition::defaultLockdown: vanilla's 12 s / 50 hp / white / crystal_1, crystal_2 / dilatable, then `<defaults><customLockdown>`. */
    @JvmStatic
    fun defaults(): LockdownDefinition {
        val d = LockdownDefinition()
        if (HS.active) HS.cfg().node("defaults")?.getChild("customLockdown")?.let { parse(it, d) }
        return d
    }

    /** `CustomLockdownDefinition()`: a copy of the default (what the parse of a weapon, power or crew effect starts from). */
    @JvmStatic
    fun fresh(): LockdownDefinition = defaults()

    /** CustomLockdownDefinition::ParseNode. */
    @JvmStatic
    fun parse(node: Element, def: LockdownDefinition): LockdownDefinition {
        for (c in node.children) when (c.name) {
            "duration" -> def.duration = HsParse.float(HsParse.value(c))
            "health" -> def.health = HsParse.int(HsParse.value(c))
            "color" -> CustomCrewManager.parseColour(c, def.colour, true)
            "anims" -> def.anims = c.children.map { HsParse.value(it) }
            "canDilate" -> def.canDilate = HsParse.bool(HsParse.value(c))
        }
        return def
    }

    /** Ship::LockdownRoom with [def] as the current lockdown, from ship pixel ([x], [y]) (the room's centre when null). */
    @JvmStatic
    fun lockdown(room: Room, def: LockdownDefinition, x: Float? = null, y: Float? = null) {
        val c = def.colour
        room.hsLockdown(def.duration, def.health, x, y, def.anims, Colour(c.r, c.g, c.b, c.a), def.canDilate)
    }
}
