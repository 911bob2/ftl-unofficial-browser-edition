// SWIG member-pointer semantics for native Pointf fields (HS 1b05a07 lua/modules/hyperspace.i).
package xyz.znix.xftl.hs.lua

/** The member's address stays stable even when gameplay replaces the Kotlin value behind it. */
class LuaPointfMember(private val gx: FGet, private val sx: FSet, private val gy: FGet, private val sy: FSet) : LuaTyped {
    override val luaType get() = LuaApi.T_Pointf
    var x: Float get() = gx.get(); set(value) { sx.set(value) }
    var y: Float get() = gy.get(); set(value) { sy.set(value) }
}
