/* The Lua 5.3.6 VM (third_party/lua-5.3.6/src/lvm.c, included unchanged) for the Hyperspace runtime. Lua switches hooks
 * off inside __gc and inside a hook, so a loop there would never reach the instruction cap: the count hook runs there
 * too, and hslua.c's hook learns that Lua had hooks off (a script's own hook is not called there, as in Lua). */
#define lvm_c
#define LUA_CORE
#include "lprefix.h"
#include "lua.h"
#include "lobject.h"
#include "lstate.h"
#include "ldebug.h"

extern int hsHooksOff;   /* hslua.c */
static void trace_with_hooks(lua_State *L) {
  lu_byte allow = L->allowhook;
  hsHooksOff = !allow;
  L->allowhook = 1;
  luaG_traceexec(L);
  L->allowhook = allow;
}
#define luaG_traceexec trace_with_hooks
#include "lvm.c"
