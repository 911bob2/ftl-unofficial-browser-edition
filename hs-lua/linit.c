/*
** The standard libraries of this runtime: base, coroutine, table, string, math and utf8. Hyperspace's lua/linit.c lists
** base, table, string, math, utf8 and bit32, an edited copy of Lua 5.3's linit.c, but its Windows build does not compile
** that file: the original has every Lua 5.3 library without LUA_COMPAT_5_2 (no bit32, no math.pow), os, io, debug,
** coroutine and package too (oracle ps-m1-ev lib rows). hslibs.c adds io, os, debug and package on a private file
** system with no shell (oracle ps-m1b-libs); hslua.c chains debug.sethook with the instruction cap.
** Copyright (C) 1994-2018 Lua.org, PUC-Rio. MIT license, see third_party/notices/Lua-LICENSE.txt.
*/
#define linit_c
#define LUA_LIB
#include <stddef.h>
#include "lua.h"
#include "lualib.h"
#include "lauxlib.h"

static const luaL_Reg loadedlibs[] = {
  {"_G", luaopen_base},
  {LUA_COLIBNAME, luaopen_coroutine},
  {LUA_TABLIBNAME, luaopen_table},
  {LUA_STRLIBNAME, luaopen_string},
  {LUA_MATHLIBNAME, luaopen_math},
  {LUA_UTF8LIBNAME, luaopen_utf8},
  {NULL, NULL}
};

LUALIB_API void luaL_openlibs (lua_State *L) {
  const luaL_Reg *lib;
  for (lib = loadedlibs; lib->func; lib++) {
    luaL_requiref(L, lib->name, lib->func, 1);
    lua_pop(L, 1);
  }
}
