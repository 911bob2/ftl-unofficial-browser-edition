/*
** The standard libraries of this runtime: base, table, string, math, utf8 and, with LUA_COMPAT_BITLIB, bit32 (Hyperspace's
** lua/linit.c lists these, an edited copy of Lua 5.3's linit.c, but its Windows build does not compile that file and has
** them all, os, io, debug, coroutine and package too); hslua.c adds a part of os.
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
  {LUA_TABLIBNAME, luaopen_table},
  {LUA_STRLIBNAME, luaopen_string},
  {LUA_MATHLIBNAME, luaopen_math},
  {LUA_UTF8LIBNAME, luaopen_utf8},
#if defined(LUA_COMPAT_BITLIB)
  {LUA_BITLIBNAME, luaopen_bit32},
#endif
  {NULL, NULL}
};

LUALIB_API void luaL_openlibs (lua_State *L) {
  const luaL_Reg *lib;
  for (lib = loadedlibs; lib->func; lib++) {
    luaL_requiref(L, lib->name, lib->func, 1);
    lua_pop(L, 1);
  }
}
