// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i Blueprint::desc,
// Description and TextString members), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.GameText
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Blueprint::desc (Description) over the engine blueprint's title, short and desc texts. */
class LuaDescription(@JvmField val bp: Blueprint) : LuaTyped { override val luaType get() = A.T_Description }

/**
 * A TextString member of an engine object: data is the text id or the literal text, isLiteral which one; writes
 * replace the engine's text (FTL writes the member in place, so everything reading the blueprint sees it).
 */
class LuaTextRef(private val read: () -> GameText?, private val write: (GameText) -> Unit) : LuaTyped {
    override val luaType get() = A.T_TextString
    val data: String get() = read()?.hsData ?: ""
    val isLiteral: Boolean get() = read()?.hsIsLiteral ?: false
    fun set(data: String, literal: Boolean) = write(if (literal) GameText.literal(data) else GameText.localised(data))
    fun text(): String { val t = read() ?: return ""; return if (t.hsIsLiteral) t.hsData else LuaAdaptersMore.translate(t.hsData) }
}

object LuaDescriptionApi {
    /** An instanceof test the compiler cannot fold away (the argument's static type is Any). */
    @JvmStatic
    fun asBlueprint(o: Any?): Blueprint? = o as? Blueprint

    fun install() {
        LuaReg.obj(A.A_Blueprint_desc, ObjGet { LuaViews.cached(it as Blueprint, A.A_Blueprint_desc) { b -> LuaDescription(b as Blueprint) } })
        // the placeholder blueprints of a name the data does not define are not engine Blueprints: their texts are empty
        fun real(b: Blueprint): Blueprint? = LuaDescriptionApi.asBlueprint(b)
        LuaReg.obj(A.A_Description_title, ObjGet { o -> val b = (o as LuaDescription).bp
            LuaViews.cached(b, A.A_Description_title) { LuaTextRef({ val r = real(b); if (r is xyz.znix.xftl.game.ShipBlueprint) r.shipClass else r?.title }, { if (real(b) != null && b !is xyz.znix.xftl.game.ShipBlueprint) b.title = it }) } })
        LuaReg.obj(A.A_Description_shortTitle, ObjGet { o -> val b = (o as LuaDescription).bp
            LuaViews.cached(b, A.A_Description_shortTitle) { LuaTextRef({ real(b)?.short }, { if (real(b) != null) b.short = it }) } })
        LuaReg.obj(A.A_Description_description, ObjGet { o -> val b = (o as LuaDescription).bp
            LuaViews.cached(b, A.A_Description_description) { LuaTextRef({ real(b)?.desc }, { if (real(b) != null) b.desc = it }) } })
        // TextString members on a LuaTextRef, in front of whatever is registered (LuaStructs chains either way)
        val dg = LuaReg.strGet[A.A_TextString_data]; val ds = LuaReg.strSet[A.A_TextString_data]
        LuaReg.str(A.A_TextString_data, StrGet { o -> if (o is LuaTextRef) o.data else dg?.get(o) ?: throw LuaError("TextString.data") },
            StrSet { o, v -> if (o is LuaTextRef) o.set(v, o.isLiteral) else (ds ?: throw LuaError("TextString.data")).set(o, v) })
        val lg = LuaReg.numGet[A.A_TextString_isLiteral]; val ls = LuaReg.numSet[A.A_TextString_isLiteral]
        LuaReg.num(A.A_TextString_isLiteral, NumGet { o -> if (o is LuaTextRef) (if (o.isLiteral) 1.0 else 0.0) else lg?.get(o) ?: throw LuaError("TextString.isLiteral") },
            NumSet { o, v -> if (o is LuaTextRef) o.set(o.data, v != 0.0) else (ls ?: throw LuaError("TextString.isLiteral")).set(o, v) })
        val structGetText = LuaReg.methods[A.M_TextString_GetText]
        LuaReg.method(A.M_TextString_GetText, LuaMethod { n ->
            val t = arg.obj(1)
            if (t is LuaTextRef) ret.str(t.text()) else (structGetText ?: throw LuaError("TextString::GetText")).call(n)
        })
    }
}
