// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSuperShield.cpp
// (CustomAugmentManager::GetSuperShieldValue, Shields::AddSuperShield / InstantCharge / Jump / OnLoop hooks, the
// player's super shield bars in ShipStatus::RenderShields) and src/game/Ship/CustomAugments.cpp (the <superShield>
// augment node). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * Augment super shields (`<aug><superShield>` in hyperspace.xml `<augments>`): a ship's super shield can hold
 * `value` points (the highest of its augments) plus `add` per augment copy, at least five with a Zoltan shield;
 * `<color>` / `<shieldImage>` ask for a custom look. The engine keeps the maximum in [Ship.maxSuperShield]
 * (native shields.power.super.second) and the points in [Ship.superShield]. Only used while HS is active, and only
 * for ships whose augments define a super shield.
 */
object HsSuperShield {
    class Def(@JvmField val value: Int, @JvmField val add: Int, @JvmField val color: Colour?, @JvmField val image: Array<String?>)

    /**
     * Lua's AugmentDefinition::superShield (AugmentSuperShield, CustomAugments.h): present only with a `<superShield>`
     * node; the colour starts at HS's default GL_Color(100/255, 1, 100/255, 1), the textures at "".
     */
    class LuaDef(
        @JvmField val present: Boolean, @JvmField val value: Int, @JvmField val add: Int, @JvmField val customRender: Boolean,
        @JvmField val color: xyz.znix.xftl.hs.lua.GlColorValue, @JvmField val texture: Array<String>,
    ) : xyz.znix.xftl.hs.lua.LuaTyped {
        override val luaType: Int get() = xyz.znix.xftl.hs.lua.LuaApi.T_AugmentSuperShield
    }

    private val luaDefs = HashMap<String, LuaDef>()
    private val absent = HashMap<String, LuaDef>()

    /** The AugmentSuperShield of augment [name] (an absent one, present = false, for augments without the node). */
    @JvmStatic
    fun luaDef(name: String): LuaDef {
        ensure()
        luaDefs[name]?.let { return it }
        return absent.getOrPut(name) { LuaDef(false, 0, 0, false, xyz.znix.xftl.hs.lua.GlColorValue(DEFAULT_R, 1f, DEFAULT_R, 1f), arrayOf("", "")) }
    }

    /**
     * A script's `def.superShield.shieldTexture:assign(v)`: the native std::string shieldTexture[0] it points at,
     * which the player's super shield bubble reads (CustomSuperShield.cpp copies shieldTexture[shipId] each frame).
     */
    @JvmStatic
    fun assignTexture(def: LuaDef, v: String) {
        def.texture[0] = v
        for ((name, d) in luaDefs) if (d === def) defs[name]?.image?.set(0, v)
    }

    private const val DEFAULT_R = 0.392156862f

    private var parsedFor: HsConfig? = null
    private val defs = HashMap<String, Def>()

    /** Ships whose super shield maximum HS has set (shields.power.super.second >= 1), for OnLoop's first fill. */
    private val seen = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, Boolean>())

    private fun ensure() {
        val cfg = HS.config
        if (cfg === parsedFor) return
        parsedFor = cfg
        defs.clear()
        luaDefs.clear()
        absent.clear()
        for (node in cfg?.nodesOf("augments") ?: emptyList()) {
            for (aug in node.getChildren("aug")) {
                val name = aug.getAttributeValue("name") ?: continue
                val ss = aug.getChild("superShield") ?: continue
                try {
                    val d = parse(ss)
                    defs[name] = d
                    val c = d.color
                    luaDefs[name] = LuaDef(true, d.value, d.add, c != null,
                        if (c != null) xyz.znix.xftl.hs.lua.GlColorValue(c.r, c.g, c.b, c.a) else xyz.znix.xftl.hs.lua.GlColorValue(DEFAULT_R, 1f, DEFAULT_R, 1f),
                        arrayOf(d.image[0] ?: "", d.image[1] ?: ""))
                } catch (e: Exception) {
                    HS.unsupported("parse", HS.HYPERSPACE_XML, "<superShield> of $name: ${e.message ?: e}")
                }
            }
        }
    }

    private fun parse(ss: Element): Def {
        var value = 0
        var add = 0
        var color: Colour? = null
        val image = arrayOfNulls<String>(2)
        var custom = false
        for (c in ss.children) {
            when (c.name) {
                "value" -> value = c.textTrim.toInt()
                "add" -> add = c.textTrim.toInt()
                "color" -> {
                    custom = true
                    // the unset channels keep AugmentSuperShield's default GL_Color(100/255, 1, 100/255, 1)
                    val col = color ?: Colour(DEFAULT_R, 1f, DEFAULT_R, 1f)
                    c.getAttributeValue("r")?.let { col.r = it.toFloat() / 255f }
                    c.getAttributeValue("g")?.let { col.g = it.toFloat() / 255f }
                    c.getAttributeValue("b")?.let { col.b = it.toFloat() / 255f }
                    c.getAttributeValue("a")?.let { col.a = it.toFloat() }
                    color = col
                }
                "shieldImage" -> { custom = true; image[0] = c.textTrim; image[1] = c.textTrim }
                "playerImage" -> { custom = true; image[0] = c.textTrim }
                "enemyImage" -> { custom = true; image[1] = c.textTrim }
            }
        }
        // customRender without <color> keeps AugmentSuperShield's default GL_Color(100/255, 1, 100/255, 1)
        // (CustomAugments.h; GetSuperShieldValue copies it as the render colour).
        if (custom && color == null) color = Colour(DEFAULT_R, 1f, DEFAULT_R, 1f)
        return Def(value, add, color, image)
    }

    /** The custom look of [ship]'s super shield (the custom augment with the highest value), or null. */
    @JvmStatic
    fun renderDef(ship: Ship): Def? {
        ensure()
        var best: Def? = null
        var bestValue = -1
        for (aug in ship.augments) {
            val d = defs[aug.name] ?: continue
            if (d.color != null && d.value > bestValue) { bestValue = d.value; best = d }
        }
        return best
    }

    /** CustomAugmentManager::GetSuperShieldValue: 0 when no augment of [ship] defines a super shield. */
    @JvmStatic
    fun value(ship: Ship): Int {
        ensure()
        if (defs.isEmpty()) return 0
        var value = 0
        var add = 0
        var custom = false
        var energy = false
        // the augment counts: one entry per installed copy
        val counted = HashMap<String, Int>()
        for (aug in ship.augments) counted[aug.name] = (counted[aug.name] ?: 0) + 1
        for ((name, count) in counted) {
            val d = defs[name]
            if (d != null) {
                custom = true
                if (d.value > value) value = d.value
                if (d.add > 0) add += d.add * count
            } else if (name == "ENERGY_SHIELD") energy = true
        }
        if (!custom) return 0
        if (energy && value < 5) value = 5
        return value + add
    }

    /** Shields::AddSuperShield (a shield drone's charge): one point up to max(custom, 5), the maximum raised to it. */
    @JvmStatic
    fun addSuperShield(ship: Ship): Boolean {
        val custom = value(ship)
        if (custom <= 0) return false
        val cap = maxOf(custom, 5)
        ship.maxSuperShield = maxOf(ship.maxSuperShield, cap)
        ship.superShield = minOf(ship.superShield + 1, cap)
        seen[ship] = true
        return true
    }

    /** Shields::InstantCharge and Shields::Jump: a ship with a custom super shield starts it full. */
    @JvmStatic
    fun fill(ship: Ship) {
        val custom = value(ship)
        if (custom <= 0) return
        ship.maxSuperShield = custom
        ship.superShield = custom
        seen[ship] = true
    }

    /**
     * ShipManager::CreateSystems (CustomSystems.cpp): FTL builds the Shields object when the blueprint lists shields
     * (also unbought ones), HS also with shieldWithoutSystem; without an installed shield system its InstantCharge
     * still fills an augment super shield (oracle systems-render: PLAYER_SHIP_CEL starts with its full bubble).
     */
    @JvmStatic
    fun detachedInstantCharge(ship: Ship) {
        if (ship.shields != null) return
        if (!hasShieldObject(ship)) return
        fill(ship)
    }

    /** CreateSystems also constructs Shields for an unbought slot (e.g. vanilla Stealth C), or with HS's option. */
    @JvmStatic
    fun hasShieldObject(ship: Ship): Boolean = ship.shields != null ||
        ship.systemSlots.any { it.system.name == "shields" } || (HS.active && HS.cfg().shieldWithoutSystem)

    /** Shields::OnLoop: the maximum follows the augments; a ship that had no super shield gets it full at once. */
    @JvmStatic
    fun onLoop(ship: Ship) {
        val custom = value(ship)
        if (custom <= 0) return
        val noSuper = seen[ship] != true && ship.superShield <= 0 && ship.maxSuperShield <= 5
        ship.maxSuperShield = maxOf(custom, minOf(ship.maxSuperShield, 5))
        if (noSuper) ship.superShield = custom
        seen[ship] = true
    }

    private val DEFAULT_BAR = Colour(100, 255, 100)

    /** The custom look for [ship]'s super shield bubble (GetSuperShieldValue > 0 and a custom render), or null. */
    @JvmStatic
    fun bubble(ship: Ship): Def? = if (value(ship) > 0) renderDef(ship) else null

    /**
     * CombatControl::RenderShipStatus for an enemy super shield: the 5-point box (max 5), the 12-point box (max 12),
     * otherwise a half-black box with a white 2px outline, `max * 10 + 6` wide; filled 10 px per point.
     */
    @JvmStatic
    fun drawEnemyBar(g: Graphics, ship: Ship, x: Int, y: Int, box5: xyz.znix.xftl.rendering.Image, box12: xyz.znix.xftl.rendering.Image) {
        val max = maxOf(ship.maxSuperShield, 0)
        when (max) {
            5 -> box5.draw(x, y)
            12 -> box12.draw(x, y)
            else -> {
                g.colour = Colour(0f, 0f, 0f, 0.5f)
                g.fillRect(x.toFloat(), y.toFloat(), (max * 10 + 6).toFloat(), 13f)
                g.colour = Colour.white
                // drawRect takes the width and height minus one: a 2 px outline of the (max*10+6) x 13 box
                g.drawRect(x.toFloat(), y.toFloat(), (max * 10 + 5).toFloat(), 12f)
                g.drawRect(x.toFloat() + 1f, y.toFloat() + 1f, (max * 10 + 3).toFloat(), 10f)
            }
        }
        val c = renderDef(ship)?.color
        g.colour = if (c != null) Colour(c.r, c.g, c.b, 1f) else DEFAULT_BAR
        g.fillRect(x + 3f, y + 3f, ship.superShield * 10f, 7f)
    }

    /**
     * ShipStatus::RenderShields: with a maximum other than five or a custom look, the player's super shield shows
     * [Ship.maxSuperShield] bars across the 92 pixels from x 33.5 (gap 2, 1 above ten bars) at [y]. Returns false to
     * keep the vanilla drawing.
     */
    @JvmStatic
    fun drawPlayerBars(g: Graphics, ship: Ship, y: Float): Boolean {
        val max = ship.maxSuperShield
        val look = renderDef(ship)
        if (ship.superShield <= 0 || (max == 5 && look == null) || max <= 0) return false
        val gap = if (max > 10) 1f else 2f
        val space = (92f + gap) / max
        val width = space - gap
        val c = look?.color
        g.colour = if (c != null) Colour(c.r, c.g, c.b, 1f) else DEFAULT_BAR
        var x = 33.5f
        for (i in 0 until max) {
            val x1 = x.toInt()
            val x2 = (x + width).toInt()
            if (i < ship.superShield) g.fillRect(x1.toFloat(), y, (x2 - x1).toFloat(), 6f)
            x += space
        }
        return true
    }
}
