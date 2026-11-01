/* The Lua 5.3.6 VM (third_party/lua-5.3.6/src/lvm.c, included unchanged) for the Hyperspace runtime. Lua switches hooks
 * off inside __gc, so a finalizer's loop would never reach the instruction cap: the count hook runs there too. */
#define lvm_c
#define LUA_CORE
#include "lprefix.h"
#include "lua.h"
#include "lobject.h"
#include "lstate.h"
#include "ldebug.h"

static void trace_with_hooks(lua_State *L) {
  lu_byte allow = L->allowhook;
  L->allowhook = 1;
  luaG_traceexec(L);
  L->allowhook = allow;
}
#define luaG_traceexec trace_with_hooks
#include "lvm.c"
