/* Lua 5.3.6's loadlib.c (third_party/lua-5.3.6/src, unchanged): require, package.loaded, preload and searchpath, which
 * look for files on the private file system (hslibs.c). No C library loader is compiled in (neither LUA_USE_DLOPEN nor
 * LUA_DL_DLL), and hslibs.c replaces the searchers and package.loadlib (text chunks only, Windows paths). */
#include "lprefix.h"
#include <stdio.h>
#include "hslibs.h"
#define fopen hs_fopen
#include "loadlib.c"
