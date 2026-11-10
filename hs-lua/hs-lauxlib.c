/* Lua 5.3.6's lauxlib.c (third_party/lua-5.3.6/src, unchanged) for the Hyperspace runtime: luaL_loadfilex (require's
 * Lua searcher) reads from the private file system (hslibs.c). */
#include "lprefix.h"
#include <stdio.h>
#include <string.h>
#include "hslibs.h"
#define strerror hs_strerror
#undef stdin
#define stdin hs_vstd(0)
#define fopen hs_fopen
#define freopen hs_freopen
#include "lauxlib.c"
