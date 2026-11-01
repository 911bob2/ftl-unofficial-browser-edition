// HS 1b05a07 hyperspace.i: SpaceDrone embeds Collideable as _collideable (not an inheritance conversion).
package xyz.znix.xftl.hs.lua

class LuaCollideableMember(val owner: Any) : LuaTyped {
    override val luaType get() = LuaApi.T_Collideable
}
