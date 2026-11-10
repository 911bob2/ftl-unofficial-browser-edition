/*
 * Lua 5.3's io, os, debug and package/require for mod scripts, as the original Hyperspace has them (oracle session
 * ps-m1b-libs), on a private file system (docs/HS_LUA.md, "Files, programs and the sandbox"). hslibs.c holds the file
 * system and the libraries' set-up; Lua's own liolib.c, loadlib.c and lauxlib.c are compiled unchanged through
 * hs-liolib.c, hs-loadlib.c and hs-lauxlib.c, which point their file calls here.
 */
#ifndef HSLIBS_H
#define HSLIBS_H
#include <stdio.h>
#include "lua.h"

FILE *hs_fopen(const char *path, const char *mode);
FILE *hs_freopen(const char *path, const char *mode, FILE *f);
FILE *hs_tmpfile(void);
FILE *hs_vstd(int which);                        /* 0 stdin (empty), 1 stdout and 2 stderr (the game's log) */
FILE *hs_popen(lua_State *L, const char *cmd, const char *mode);
char *hs_strerror(int e);                        /* msvcrt's numbers and texts (oracle rows) */
int hs_pclose(lua_State *L, FILE *f);

/* hs_init: io, os, debug and package after the base libraries (hslua.c's debug wrappers come after this) */
void hs_open_libs(lua_State *L);
/* the chunk name of the nearest Lua function on L's stack, or NULL */
const char *hs_caller_source(lua_State *L);

/* hslua.c: os.exit (the engine ends the session), and the hooks of debug.sethook/gethook */
int hs_os_exit(lua_State *L);
void hs_open_debug_hooks(lua_State *L);
#endif
