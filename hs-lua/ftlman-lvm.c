/* The Lua 5.3.6 VM (third_party/lua-5.3.6/src/lvm.c, included unchanged) for ftlman-lua.wasm, with the rule Lua 5.4
 * (ftlman's Lua) has for strings in arithmetic: the arithmetic and bitwise instructions take numbers only, so a
 * string operand goes to the string metatable's arithmetic metamethods (ftlman.c), which keep integer strings
 * integers ("10" + 1 is 11, not 11.0). 'for' loop values and number equality convert as before (5.4 does too).
 * lvm.c's two conversion macros are chosen by the operand's name: rb and rc (instruction operands) skip strings;
 * a use under any other name fails to compile.
 * Lua switches hooks off inside __gc, so a finalizer's loop would never reach the instruction limit: the count hook runs
 * there too. */
#define lvm_c
#define LUA_CORE
#include "lprefix.h"
#include "lua.h"
#include "lobject.h"
#include "lstate.h"
#include "ldebug.h"
#include "lvm.h"

static void trace_with_hooks(lua_State *L) {
  lu_byte allow = L->allowhook;
  L->allowhook = 1;
  luaG_traceexec(L);
  L->allowhook = allow;
}
#define luaG_traceexec trace_with_hooks

#define tonumberns(o,n) (ttisfloat(o) ? (*(n) = fltvalue(o), 1) : ttisinteger(o) ? (*(n) = cast_num(ivalue(o)), 1) : 0)
#define tointegerns(o,i) (ttisinteger(o) ? (*(i) = ivalue(o), 1) : (ttisfloat(o) && luaV_tointeger(o,i,LUA_FLOORN2I)))
#define tonumber53(o,n) (ttisfloat(o) ? (*(n) = fltvalue(o), 1) : luaV_tonumber_(o,n))
#define tointeger53(o,i) (ttisinteger(o) ? (*(i) = ivalue(o), 1) : luaV_tointeger(o,i,LUA_FLOORN2I))
#undef tonumber
#undef tointeger
#define tonumber(o,n) tonumber_##o(o,n)
#define tointeger(o,i) tointeger_##o(o,i)
#define tonumber_rb tonumberns
#define tonumber_rc tonumberns
#define tonumber_obj tonumber53
#define tonumber_plimit tonumber53
#define tonumber_pstep tonumber53
#define tonumber_init tonumber53
#define tointeger_rb tointegerns
#define tointeger_rc tointegerns
#define tointeger_t1 tointeger53
#define tointeger_t2 tointeger53
#include "lvm.c"
