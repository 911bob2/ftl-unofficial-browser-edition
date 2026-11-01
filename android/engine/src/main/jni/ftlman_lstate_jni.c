/* lstate.c for libftlmanlua.so, with the string hash seed of web/vendor/ftlman-lua.wasm. Lua mixes addresses into the
 * seed: fixed in that wasm module (so its `pairs` order is too), random here, where hsconf.h's luai_makeseed alone would
 * not fix it. The value belongs to the wasm binary: tools/mods/ftlman-wasm-seed.mjs prints it after a rebuild, and the
 * parity vectors fail until it is updated here. */
#define lstate_c
#define LUA_CORE
#include "lprefix.h"
#include "lua.h"
#include "lstring.h"
#define luaS_hash(str, l, seed) 0xe23c5c26u
#include "lstate.c"
