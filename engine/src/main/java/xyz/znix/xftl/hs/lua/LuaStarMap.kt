// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i StarMap::ship, shipNoFuel,
// mapsBottom and the GL_Primitive_Pointer_Array_Size_3_Wrapper), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** StarMap::mapsBottom (GL_Primitive *[3]) as SWIG's array wrapper: zero-based, three entries. */
object LuaMapsBottom : LuaTyped { override val luaType get() = A.T_GL_Primitive_Pointer_Array_Size_3_Wrapper }

/**
 * The star map art scripts may replace (StarMap's constructor 0x5f1d20 builds the defaults): ship / shipNoFuel, the
 * ship icon primitives (GL_CreateImagePrimitive(map_icon_ship[_fuel].png, -10, -32, 64, 64, 0, white), drawn around
 * the current beacon), and mapsBottom, the three sector backgrounds (zone_1..3 at (0, 0), white), drawn for
 * worldLevel % 3 under a 0.75 tint. The jump window draws a replaced one in place of its own image.
 */
object LuaStarMap {
    private val icons = arrayOfNulls<LuaPrimitive>(2)
    private val set = BooleanArray(2)
    private val iconDefaults = arrayOfNulls<LuaPrimitive>(2)
    private val bottoms = arrayOfNulls<LuaPrimitive>(3)
    private val defaults = arrayOfNulls<LuaPrimitive>(3)
    private val tint = Colour(0.75f, 0.75f, 0.75f, 1f)

    fun reset() { set.fill(false); icons.fill(null); iconDefaults.fill(null); bottoms.fill(null); defaults.fill(null) }

    private fun icon(i: Int): LuaPrimitive = iconDefaults[i] ?: LuaPrimitive(
        LuaGraphics.texture(if (i == 1) "map/map_icon_ship_fuel.png" else "map/map_icon_ship.png"),
        -10f, -32f, 64f, 64f, 0f, false, GlColorValue(1f, 1f, 1f, 1f)).also { iconDefaults[i] = it }

    private fun bottom(i: Int): LuaPrimitive? {
        bottoms[i]?.let { return it }
        // StarMap's constructor: GL_CreateImagePrimitive(zone_<i+1>, 0, 0, w, h, 0, white)
        return defaults[i] ?: LuaPrimitive(LuaGraphics.texture("map/zone_${i + 1}.png"), 0f, 0f, 0f, 0f, 0f, false,
            GlColorValue(1f, 1f, 1f, 1f)).also { defaults[i] = it }
    }

    /**
     * Draws a script's ship icon (StarMap::ship, or shipNoFuel while the distress flash shows) with its own colour
     * at ([x], [y]) in the current (rotated) frame; false when the engine draws its own. A nil one draws nothing.
     */
    @JvmStatic
    fun drawIcon(fuel: Boolean, x: Float, y: Float): Boolean {
        val i = if (fuel) 1 else 0
        if (!set[i]) return false
        val p = icons[i] ?: return true
        if (p === iconDefaults[i]) return false
        val g = HsLua.graphics ?: return false
        g.pushTransform()
        g.translate(x, y)
        LuaGraphics.renderPrimitive(p, null)
        g.popTransform()
        return true
    }

    /** Draws a script's mapsBottom[[index]] at ([x], [y]) under the 0.75 tint; false when the engine draws its own. */
    @JvmStatic
    fun drawBottom(index: Int, x: Float, y: Float): Boolean {
        val p = bottoms[index] ?: return false
        if (p === defaults[index]) return false
        val g = HsLua.graphics ?: return false
        g.pushTransform()
        g.translate(x, y)
        LuaGraphics.renderPrimitive(p, tint)
        g.popTransform()
        return true
    }

    fun install() {
        LuaReg.obj(A.A_StarMap_ship, ObjGet { if (set[0]) icons[0] else icon(0) }, ObjSet { _, v -> icons[0] = v as? LuaPrimitive; set[0] = true })
        LuaReg.obj(A.A_StarMap_shipNoFuel, ObjGet { if (set[1]) icons[1] else icon(1) }, ObjSet { _, v -> icons[1] = v as? LuaPrimitive; set[1] = true })
        LuaReg.obj(A.A_StarMap_mapsBottom, ObjGet { LuaMapsBottom })
        fun index(): Int {
            val i = arg.num(2)
            if (i < 0) throw LuaError("number must not be negative")
            if (i.toLong() >= 3) throw LuaError("SWIG_IndexError:Index out of range")
            return i.toInt()
        }
        LuaReg.method(A.M_GL_Primitive_Pointer_Array_Size_3_Wrapper___getitem, LuaMethod { ret.obj(bottom(index()), A.T_GL_Primitive) })
        LuaReg.method(A.M_GL_Primitive_Pointer_Array_Size_3_Wrapper___setitem, LuaMethod { bottoms[index()] = arg.obj(3) as? LuaPrimitive; 0 })
    }
}
