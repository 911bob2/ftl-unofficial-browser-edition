/*
 * Hyperspace Lua runtime for the browser engine: Lua 5.3.6 (third_party/lua-5.3.6, unmodified) plus this bridge,
 * compiled to WebAssembly (hs-lua/Dockerfile). It ports what FTL-Hyperspace (GPL-3.0-or-later, commit 1b05a07) sets up in LuaScriptInit.cpp,
 * LuaLibScript.cpp and the SWIG 4.0.1 runtime, with engine objects living on the engine side:
 *
 *  - Engine objects cross as int32 handles (engine: xyz.znix.xftl.hs.lua.Handles). A userdata holds {handle, type}.
 *    Same (handle, type) -> same userdata while it lives (weak cache); the last __gc of a handle's userdata releases
 *    the handle on the engine side.
 *  - Class layout as SWIG builds it: per-class metatable with .type/.bases/.get/.set/.fn, __index/__newindex with
 *    SWIG's lookup order (own attributes, own methods, bases, then __getitem), __eq on the handle, __tostring.
 *    Lookups go through a flattened per-class table built from the same rules, so a field read is one table lookup
 *    plus one call into the engine.
 *  - Method calls check arity and argument types exactly as SWIG's wrappers (tables in gen/hs_api.c, generated from
 *    SWIG's own output) and then call the engine with the method id; the engine reads arguments from and pushes
 *    results onto this Lua stack through the hs_* exports below.
 *  - script.on_* registration and dispatch follow LuaLibScript.cpp (stable priority sort, Chain values, argument
 *    rewriting); each callback runs under an instruction cap and is profiled per callback.
 * No filesystem, clock, os/io/package/debug/coroutine libraries; math.random draws from the engine.
 */
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include "lstate.h"   /* read-only use of L->hookcount for instruction counts */
#include "hs_api.h"

#define EXPORT(n) __attribute__((export_name(#n)))
#define IMPORT(n) __attribute__((import_module("env"), import_name(#n)))

/* ---------------------------------------------------------------- engine imports */
IMPORT(log) extern void js_log(int kind, const char *p, int len);          /* 0 log, 1 print, 2 error, 3 warning */
IMPORT(rand32) extern int js_rand32(void);
IMPORT(srand32) extern void js_srand32(unsigned int seed);
IMPORT(now) extern double js_now(void);
IMPORT(getn) extern double js_getn(int h, int attr);                      /* numbers and booleans */
IMPORT(gets) extern int js_gets(int h, int attr);                         /* string into sbuf, -1 = nil */
IMPORT(geto) extern double js_geto(int h, int attr);                      /* packed type * 2^32 + handle, 0 = nil */
IMPORT(setn) extern int js_setn(int h, int attr, double v);               /* 0 ok, else 1 + length of the error text in sbuf */
IMPORT(sets) extern int js_sets(int h, int attr, int len);
IMPORT(seto) extern int js_seto(int h, int attr, int h2, int t2);
IMPORT(call) extern int js_call(int mid, int nargs);                      /* >=0 results pushed, -1 error text in sbuf */
IMPORT(release) extern void js_release(int h);
IMPORT(tableid) extern int js_tableid(int h);
IMPORT(registered) extern void js_registered(int kind, int id);           /* a callback list changed */

/* ---------------------------------------------------------------- shared string buffer */
static char *sbuf; static int sbufCap;
EXPORT(hs_sbuf) char *hs_sbuf(void) { return sbuf; }
EXPORT(hs_sbuf_cap) int hs_sbuf_cap(void) { return sbufCap; }
EXPORT(hs_sbuf_grow) char *hs_sbuf_grow(int n) {
  if (n > sbufCap) { int c = sbufCap; while (c < n) c *= 2; char *p = realloc(sbuf, c); if (!p) return 0; sbuf = p; sbufCap = c; }
  return sbuf;
}
static void logf_(int kind, const char *fmt, ...) {
  char tmp[2048]; va_list ap; va_start(ap, fmt); int n = vsnprintf(tmp, sizeof tmp, fmt, ap); va_end(ap);
  if (n < 0) return; if (n >= (int)sizeof tmp) n = sizeof tmp - 1;
  js_log(kind, tmp, n);
}
/* lua_writestring & co. (luaconf overrides in hsconf.h) */
void hs_writestring(const char *s, size_t l) { js_log(0, s, (int)l); }
void hs_writeerror(const char *fmt, const char *p) { logf_(2, fmt, p); }

static lua_State *L0;

#if !defined(__wasm__)
/* Native builds (Android) run outside the wasm sandbox. Scripts reach the metamethods below through getmetatable and
   may call them with any first argument; refuse one they would misread as a userdata or a namespace table. */
static int bad_self(lua_State *L) { return luaL_error(L, "bad argument #1 to a Hyperspace metamethod"); }
static int ns_self_ok(lua_State *L, const char *a, const char *b) {   /* arg 1 has a metatable whose a and b are tables */
  if (!lua_getmetatable(L, 1)) return 0;
  int ok = 1;
  if (a) { lua_pushstring(L, a); ok = lua_rawget(L, -2) == LUA_TTABLE; lua_pop(L, 1); }
  if (ok && b) { lua_pushstring(L, b); ok = lua_rawget(L, -2) == LUA_TTABLE; lua_pop(L, 1); }
  lua_pop(L, 1);
  return ok;
}
#endif

/* ---------------------------------------------------------------- userdata and types */
typedef struct { int32_t h; int32_t t; int32_t tid; } HsUd;
enum { REG_CACHE = 1, REG_MT, REG_FLAT, REG_FLATSET, REG_TABLES, REG_GETITEM, REG_SETITEM, REG_CACHE2, REG_PLAIN, REG_SENTINEL, REG_LAST };
static int regRef[REG_LAST];
#define PUSHREG(L, i) lua_rawgeti(L, LUA_REGISTRYINDEX, regRef[i])
static int ntypes;
static const char *TYPE_STR(int t) { return (t > 0 && t <= hs_ntypes) ? hs_types[t].str : "void*"; }
/* is userdata type `have` usable where `want` is expected (same or derived, SWIG_TypeCheckStruct) */
static int isa(int have, int want) {
  if (have == want) return 1;
  if (have <= 0 || have > hs_ntypes || !hs_types[have].isClass) return 0;
  int off = hs_classes[have].baseOff;
  if (!off) return 0;
  for (; hs_bases[off]; off++) if (isa(hs_bases[off], want)) return 1;
  return 0;
}
static HsUd *toud(lua_State *L, int i) {
  i = lua_absindex(L, i);
  HsUd *u = (HsUd *)lua_touserdata(L, i);
  if (!u || lua_type(L, i) != LUA_TUSERDATA) return 0;
  /* only our userdata carry one of our metatables; a plain check of the size is not possible, so check the mt */
  if (!lua_getmetatable(L, i)) {
    /* a non-class pointer (SWIG gives it no metatable): ours when REG_PLAIN (weak keys) lists it */
    PUSHREG(L, REG_PLAIN); lua_pushvalue(L, i); int plain = lua_rawget(L, -2) == LUA_TBOOLEAN; lua_pop(L, 2);
    return plain ? u : 0;
  }
  lua_pushliteral(L, ".hs"); int ok = lua_rawget(L, -2) == LUA_TBOOLEAN; lua_pop(L, 2);
  return ok ? u : 0;
}
static const char *swig_typename(lua_State *L, int i) {   /* SWIG_Lua_typename */
  if (lua_isuserdata(L, i)) { HsUd *u = toud(L, i); if (u) return TYPE_STR(u->t); return "userdata (unknown type)"; }
  return lua_typename(L, lua_type(L, i));
}
static void pushferr(lua_State *L, const char *fmt, ...) {   /* SWIG_Lua_pushferrstring */
  va_list ap; va_start(ap, fmt); luaL_where(L, 1); lua_pushvfstring(L, fmt, ap); va_end(ap); lua_concat(L, 2);
}
/* A setter's SWIG_fail_arg: SWIG calls the .set wrapper from its C __newindex, so luaL_where(L, 1) names a C
   function (no location), and the value is the wrapper's arg 2 (arg 1 for a static/module attribute). */
static void pushferr_set(lua_State *L, const char *fn, int vi, const char *ctype) {
  lua_pushfstring(L, "Error in %s (arg %d), expected '%s' got '%s'", fn, vi == 1 ? 1 : 2, ctype, swig_typename(L, vi));
}
static void push_error_location(lua_State *L) {            /* hyperspace.i: first non-empty level */
  int level = 0; size_t len = 0;
  for (;;) { luaL_where(L, level++); lua_tolstring(L, -1, &len); if (len == 0 && level < 50) lua_pop(L, 1); else break; }
}

/* Userdata per engine handle, by the handle's slot (its low HS_SLOT_BITS bits; Handles.kt packs handles the same
   way). One handle per object, possibly several userdata (static types differ, or a collected userdata whose finalizer
   has not run yet while a new one is made); the last finalizer releases the handle. A C array, so this bookkeeping
   costs no Lua heap and no collector work: scripts that make thousands of short-lived objects per frame
   (Hyperspace.Point(x, y) in a loop, room.rect) keep the collector as fast as with Hyperspace's plain pointers. */
#define HS_SLOT_BITS 24
#define HS_SLOT_MASK ((1 << HS_SLOT_BITS) - 1)
static int32_t *udCount; static int udCountCap; static int closing;
static int ud_retain(int h) {
  int s = h & HS_SLOT_MASK;
  if (s >= udCountCap) {
    int c = udCountCap ? udCountCap : 4096; while (c <= s) c *= 2;
    int32_t *p = (int32_t *)realloc(udCount, (size_t)c * sizeof(int32_t));
    if (!p) return 0;
    memset(p + udCountCap, 0, (size_t)(c - udCountCap) * sizeof(int32_t));
    udCount = p; udCountCap = c;
  }
  udCount[s]++;
  return 1;
}
static void ud_unretain(int h) {
  int s = h & HS_SLOT_MASK;
  if (closing || s >= udCountCap || udCount[s] <= 0) return;
  if (--udCount[s] == 0) js_release(h);
}
static int64_t ckey(int h, int t) { return ((int64_t)(uint32_t)h << 10) | (t & 1023); }
/* push engine object (handle h, type t); nil for h == 0. An object pushed repeatedly gets the same userdata while it
   lives: REG_CACHE[slot] (weak values; slots are dense, so this is the table's array part) holds the userdata of the
   slot's handle, REG_CACHE2[handle, type] (weak values) the rarer ones of the same handle with another static type.
   (Hyperspace makes a new userdata on every push, so scripts cannot depend on either.) */
static void push_obj(lua_State *L, int h, int t) {
  if (h == 0 || t <= 0) { lua_pushnil(L); return; }
  int s = h & HS_SLOT_MASK, second = 0;
  PUSHREG(L, REG_CACHE);
  if (lua_rawgeti(L, -1, s) == LUA_TUSERDATA) {
    HsUd *c = (HsUd *)lua_touserdata(L, -1);
    if (c->h == h && c->t == t) { lua_remove(L, -2); return; }
    if (c->h == h) {
      second = 1;
      lua_pop(L, 2); PUSHREG(L, REG_CACHE2);
      if (lua_rawgeti(L, -1, ckey(h, t)) == LUA_TUSERDATA) { lua_remove(L, -2); return; }
    }
  }
  lua_pop(L, 1);
  /* Only an object pushed again while an earlier userdata of it is alive goes into the cache: the ones made per
     access (value copies, constructor results) are pushed once, and keeping them out leaves the weak tables (which the
     collector walks every cycle) as small as the set of objects scripts actually reuse. */
  int cache = s < udCountCap && udCount[s] > 0;
  /* Counted before lua_newuserdata: that allocation can run a GC step which finalizes an older userdata of the same
     handle, and with the count still at that one userdata its __gc would release h under the new userdata (a crew
     member pushed by CREW_LOOP read as "stale object" in full MV runs). rawgeti/rawseti run no GC step. */
  if (!ud_retain(h)) { lua_pop(L, 1); lua_pushnil(L); return; }
  HsUd *u = (HsUd *)lua_newuserdata(L, sizeof(HsUd)); u->h = h; u->t = t; u->tid = 0;
  PUSHREG(L, REG_MT); lua_rawgeti(L, -1, t); lua_remove(L, -2);
  if (lua_istable(L, -1)) lua_setmetatable(L, -2);
  else {   /* no class metatable, as SWIG_Lua_NewPointerObj for a type without clientdata: swig_type still names it */
    lua_pop(L, 1); PUSHREG(L, REG_PLAIN); lua_pushvalue(L, -2); lua_pushboolean(L, 1); lua_rawset(L, -3); lua_pop(L, 1);
    /* Such a userdata has no metatable, so no finalizer of its own: a hidden user value with one releases the handle
       when the pointer is collected (scripts cannot reach user values: there is no debug library). */
    HsUd *g = (HsUd *)lua_newuserdata(L, sizeof(HsUd)); g->h = h; g->t = 0; g->tid = 0;
    PUSHREG(L, REG_SENTINEL); lua_setmetatable(L, -2);
    lua_setuservalue(L, -2);
  }
  if (cache) { lua_pushvalue(L, -1); lua_rawseti(L, -3, second ? ckey(h, t) : s); }
  lua_remove(L, -2);
}
static void push_packed(lua_State *L, double packed) {
  if (packed == 0) { lua_pushnil(L); return; }
  uint64_t v = (uint64_t)packed; push_obj(L, (int)(uint32_t)(v & 0xffffffffu), (int)(v >> 32));
}
static int ud_gc(lua_State *L) {
  HsUd *u = (HsUd *)lua_touserdata(L, 1);
  if (u && u->h) { int h = u->h; u->h = 0; ud_unretain(h); }
  return 0;
}

/* ---------------------------------------------------------------- attributes */
/* a getter failed on the engine side (hs_raise): raise it as a Lua error once the getter returned */
static char *pendingErr; static int pendingLen;
EXPORT(hs_raise) void hs_raise(int len) {
  free(pendingErr); pendingErr = malloc(len + 1); memcpy(pendingErr, sbuf, len); pendingLen = len;
}
static int raise_pending(lua_State *L) {
  luaL_where(L, 1);   /* can run a finalizer, whose failing getter raises and clears the pending error itself */
  if (pendingErr) { lua_pushlstring(L, pendingErr, pendingLen); lua_concat(L, 2); free(pendingErr); pendingErr = 0; }
  return lua_error(L);
}
static int get_attr(lua_State *L, int h, int a) {
  const HsAttr *at = &hs_attrs[a];
  switch (at->kind) {
    case HS_K_NUM: { double v = js_getn(h, a); if (pendingErr) return raise_pending(L); lua_pushnumber(L, (lua_Number)v); return 1; }
    case HS_K_BOOL: { double v = js_getn(h, a); if (pendingErr) return raise_pending(L); lua_pushboolean(L, v != 0); return 1; }
    case HS_K_STR: { int n = js_gets(h, a); if (pendingErr) return raise_pending(L); if (n < 0) lua_pushnil(L); else lua_pushlstring(L, sbuf, n); return 1; }
    default: { double v = js_geto(h, a); if (pendingErr) return raise_pending(L); push_packed(L, v); return 1; }
  }
}
/* setter: value at index vi; owner handle h; name for errors */
static int set_attr(lua_State *L, int h, int a, int vi) {
  const HsAttr *at = &hs_attrs[a];
  if (at->setCheck < 0) {                                   /* SWIG_Lua_set_immutable_new */
    lua_settop(L, vi - 1); push_error_location(L); lua_pushliteral(L, "This variable is immutable"); lua_concat(L, 2); return lua_error(L);
  }
  /* SWIG_fail_arg names the member ("CFPS::speedLevel"), SWIG_fail_ptr the wrapper ("Animation_info_set") */
  const char *fn = at->swigName && *at->swigName ? at->swigName : at->name;
  const char *fp = at->setFailName && *at->setFailName ? at->setFailName : fn;
  int r;
  switch (at->setCheck) {
    case HS_CHECK_NUM:
      if (!lua_isnumber(L, vi)) { pushferr_set(L, fn, vi, at->setCtype); return lua_error(L); }
      if (at->setUnsigned && lua_tonumber(L, vi) < 0) { push_error_location(L); lua_pushfstring(L, "%s:%s", "SWIG_ValueError", "number must not be negative"); lua_concat(L, 2); return lua_error(L); }
      r = js_setn(h, a, lua_tonumber(L, vi)); break;
    case HS_CHECK_BOOL:
      if (!lua_isboolean(L, vi)) { pushferr_set(L, fn, vi, at->setCtype); return lua_error(L); }
      r = js_setn(h, a, lua_toboolean(L, vi)); break;
    case HS_CHECK_NSTR: case HS_CHECK_STR: {
      if (!(lua_isstring(L, vi) || (at->setCheck == HS_CHECK_NSTR && lua_isnil(L, vi)))) { pushferr_set(L, fn, vi, at->setCtype); return lua_error(L); }
      size_t n = 0; const char *s = lua_tolstring(L, vi, &n); if (!s) n = 0;
      if (!hs_sbuf_grow((int)n + 1)) return luaL_error(L, "out of memory");
      if (s) memcpy(sbuf, s, n);
      r = js_sets(h, a, (int)n); break;
    }
    case HS_CHECK_PTR: case HS_CHECK_UD: {
      HsUd *u = toud(L, vi);
      if (at->setCheck == HS_CHECK_UD ? !lua_isuserdata(L, vi) : !(lua_isuserdata(L, vi) || lua_isnil(L, vi))) { pushferr_set(L, fn, vi, at->setCtype); return lua_error(L); }
      if (!lua_isnil(L, vi) && (!u || (at->setType && !isa(u->t, at->setType)))) { pushferr_set(L, fp, vi, TYPE_STR(at->setType)); return lua_error(L); }
      r = js_seto(h, a, u ? u->h : 0, u ? u->t : 0); break;
    }
    default: r = js_setn(h, a, lua_tonumber(L, vi)); break;
  }
  if (r) { lua_pushlstring(L, sbuf, r - 1); return lua_error(L); }   /* r = error length + 1 */
  return 0;
}

/* ---------------------------------------------------------------- methods */
static int check_arg(lua_State *L, const HsArg *a) {
  int i = a->i;
  switch (a->check) {
    case HS_CHECK_NUM: return lua_isnumber(L, i);
    case HS_CHECK_BOOL: return lua_isboolean(L, i);
    case HS_CHECK_NSTR: return lua_isstring(L, i) || lua_isnil(L, i);
    case HS_CHECK_STR: return lua_isstring(L, i);
    case HS_CHECK_PTR: return lua_isuserdata(L, i) || lua_isnil(L, i);
    case HS_CHECK_UD: return lua_isuserdata(L, i);
    case HS_CHECK_FN: return lua_isfunction(L, i);
    case HS_CHECK_TAB: return lua_istable(L, i);
    default: return 1;
  }
}
static int convert_ok(lua_State *L, int i, int typeId, int nonull) {   /* SWIG_ConvertPtr succeeds */
  if (lua_isnil(L, i)) return !nonull;
  HsUd *u = toud(L, i);
  if (!u) return 0;
  return !typeId || isa(u->t, typeId);
}
static int call_engine(lua_State *L, int mid) {
  int n = lua_gettop(L);
  lua_checkstack(L, 40);
  int r = js_call(mid, n);
  if (r < 0) {
    /* an engine-side SWIG_exception ("SWIG_IndexError:..."): hyperspace.i prefixes the first non-empty location */
    if (-r - 1 > 5 && memcmp(sbuf, "SWIG_", 5) == 0) { push_error_location(L); lua_pushlstring(L, sbuf, -r - 1); lua_concat(L, 2); }
    else lua_pushlstring(L, sbuf, -r - 1);
    return lua_error(L);
  }
  return r;
}
static int invoke(lua_State *L, int mid) {
  const HsMethod *m = &hs_methods[mid];
  if (m->isDispatch) {
    int argc = lua_gettop(L);
    for (int c = 0; c < m->nargs; c++) {
      const HsCand *cd = &hs_cands[m->aoff + c];
      if (cd->argc != argc) continue;
      int ok = 1;
      for (int k = 0; k < cd->n && ok; k++) {
        const HsArg *a = &hs_args[cd->aoff + k];
        if (a->check == HS_CHECK_UD || a->check == HS_CHECK_PTR) {
          ok = (a->check == HS_CHECK_UD ? lua_isuserdata(L, a->i) : (lua_isuserdata(L, a->i) || lua_isnil(L, a->i))) && convert_ok(L, a->i, a->typeId, a->nonull);
        } else ok = check_arg(L, a);
      }
      if (ok) return invoke(L, cd->target);
    }
    luaL_where(L, 1); lua_pushstring(L, m->swigName); lua_concat(L, 2);   /* SWIG_Lua_pusherrstring */
    return lua_error(L);
  }
  int n = lua_gettop(L);
  if (n < m->nmin || n > m->nmax) { pushferr(L, "Error in %s expected %d..%d args, got %d", m->swigName, m->nmin, m->nmax, n); return lua_error(L); }
  for (int k = 0; k < m->nargs; k++) {
    const HsArg *a = &hs_args[m->aoff + k];
    if (a->i > n) continue;
    if (!check_arg(L, a)) { pushferr(L, "Error in %s (arg %d), expected '%s' got '%s'", m->swigName, a->i, a->ctype, swig_typename(L, a->i)); return lua_error(L); }
  }
  for (int k = 0; k < m->nargs; k++) {
    const HsArg *a = &hs_args[m->aoff + k];
    if (a->i > n) continue;
    if (a->typeId && (a->check == HS_CHECK_PTR || a->check == HS_CHECK_UD) && !convert_ok(L, a->i, a->typeId, 0)) {
      pushferr(L, "Error in %s (arg %d), expected '%s' got '%s'", a->failName && *a->failName ? a->failName : m->failName, a->i, TYPE_STR(a->typeId), swig_typename(L, a->i));
      return lua_error(L);
    }
    if (a->isUnsigned && lua_tonumber(L, a->i) < 0) { push_error_location(L); lua_pushfstring(L, "%s:%s", "SWIG_ValueError", "number must not be negative"); lua_concat(L, 2); return lua_error(L); }
  }
  return call_engine(L, mid);
}
static int method_cl(lua_State *L) { return invoke(L, (int)lua_tointeger(L, lua_upvalueindex(1))); }
static int getter_cl(lua_State *L) {   /* .get entry: (self) -> value; static attributes get no self */
  int a = (int)lua_tointeger(L, lua_upvalueindex(1));
  if (lua_toboolean(L, lua_upvalueindex(2))) return get_attr(L, 0, a);
  HsUd *u = toud(L, 1); return get_attr(L, u ? u->h : 0, a);
}
static int setter_cl(lua_State *L) {   /* .set entry: (self, value) or (value) for static */
  int a = (int)lua_tointeger(L, lua_upvalueindex(1));
  if (lua_toboolean(L, lua_upvalueindex(2))) return set_attr(L, 0, a, 1);
  HsUd *u = toud(L, 1); return set_attr(L, u ? u->h : 0, a, 2);
}

/* per-object Lua table (HS_MAKE_TABLE): registry-like table keyed by a permanent object id from the engine */
static int table_get(lua_State *L, HsUd *u) {
  if (!u->tid) u->tid = js_tableid(u->h);
  if (!u->tid) { lua_pushnil(L); return 1; }
  PUSHREG(L, REG_TABLES);
  if (lua_rawgeti(L, -1, u->tid) != LUA_TTABLE) { lua_pop(L, 1); lua_newtable(L); lua_pushvalue(L, -1); lua_rawseti(L, -3, u->tid); }
  lua_remove(L, -2); return 1;
}
/* `.instance['.get'].table`: HS's hs_Userdata_table_get (hyperspace.i, script_add_native_member): (userdata) -> the
   object's table; an argument that is not one of our userdata is an argument error as luaL_argcheck gives */
static int table_getter_cl(lua_State *L) {
  HsUd *u = toud(L, 1);
  luaL_argcheck(L, u != 0, 1, "Expected argument of type 'Userdata'");
  return table_get(L, u);
}
EXPORT(hs_drop_table) void hs_drop_table(int tid) { PUSHREG(L0, REG_TABLES); lua_pushnil(L0); lua_rawseti(L0, -2, tid); lua_pop(L0, 1); }

/* __index: flattened lookup. FLAT[t] maps name -> integer attribute id (getter), function (method), or true ("table") */
static int ud_index(lua_State *L) {
#if !defined(__wasm__)
  if (lua_type(L, 1) != LUA_TUSERDATA) return bad_self(L);   /* every full userdata scripts can reach is an HsUd */
#endif
  HsUd *u = (HsUd *)lua_touserdata(L, 1);
  lua_pushvalue(L, 2);
  if (lua_rawget(L, lua_upvalueindex(1)) != LUA_TNIL) {
    if (lua_isinteger(L, -1)) { int a = (int)lua_tointeger(L, -1); lua_pop(L, 1); return get_attr(L, u->h, a); }
    if (lua_isboolean(L, -1)) { lua_pop(L, 1); return table_get(L, u); }
    return 1;                                                /* method closure */
  }
  lua_pop(L, 1);
  /* SWIG_Lua_class_do_get_item: __getitem of the class or a base */
  if (lua_isfunction(L, lua_upvalueindex(2))) {
    lua_pushvalue(L, lua_upvalueindex(2)); lua_pushvalue(L, 1); lua_pushvalue(L, 2); lua_call(L, 2, 1); return 1;
  }
  return 0;
}
static int ud_newindex(lua_State *L) {
#if !defined(__wasm__)
  if (lua_type(L, 1) != LUA_TUSERDATA) return bad_self(L);
#endif
  HsUd *u = (HsUd *)lua_touserdata(L, 1);
  lua_pushvalue(L, 2);
  if (lua_rawget(L, lua_upvalueindex(1)) != LUA_TNIL) {    /* FLATSET: name -> attribute id */
    int a = (int)lua_tointeger(L, -1); lua_pop(L, 1); return set_attr(L, u->h, a, 3);
  }
  lua_pop(L, 1);
  if (lua_isfunction(L, lua_upvalueindex(2))) {
    lua_pushvalue(L, lua_upvalueindex(2)); lua_pushvalue(L, 1); lua_pushvalue(L, 2); lua_pushvalue(L, 3); lua_call(L, 3, 0); return 0;
  }
  pushferr(L, "Assignment not possible. No setter/member with this name. For custom assignments implement __setitem method.");
  return lua_error(L);
}
static int ud_tostring(lua_State *L) {
#if !defined(__wasm__)
  if (lua_type(L, 1) != LUA_TUSERDATA) return bad_self(L);
#endif
  HsUd *u = (HsUd *)lua_touserdata(L, 1);
  lua_pushfstring(L, "<userdata of type '%s' at %p>", TYPE_STR(u->t), (void *)(uintptr_t)(u ? (uint32_t)u->h : 0));
  return 1;
}
static int ud_eq(lua_State *L) {   /* SWIG_Lua_class_equal: same object, type ignored */
  if (!lua_isuserdata(L, 1) || !lua_isuserdata(L, 2)) return 0;
  HsUd *a = (HsUd *)lua_touserdata(L, 1), *b = (HsUd *)lua_touserdata(L, 2);
  lua_pushboolean(L, a->h == b->h); return 1;
}
static int l_swig_type(lua_State *L) { lua_pushstring(L, swig_typename(L, 1)); return 1; }
static int ud_disown(lua_State *L) { (void)L; return 0; }   /* SWIG_Lua_class_disown: ownership stays with the engine */
static char finished[1024];

/* ---------------------------------------------------------------- class and namespace registration (SWIG layout) */
static void add_const(lua_State *L, const HsConst *k) {
  lua_pushstring(L, k->name);
  if (k->isString) lua_pushstring(L, k->s); else lua_pushinteger(L, (lua_Integer)k->n);
  lua_rawset(L, -3);
}
static void push_method(lua_State *L, int mid) { lua_pushinteger(L, mid); lua_pushcclosure(L, method_cl, 1); }
static void push_getter(lua_State *L, int a, int isStatic) { lua_pushinteger(L, a); lua_pushboolean(L, isStatic); lua_pushcclosure(L, getter_cl, 2); }
static void push_setter(lua_State *L, int a, int isStatic) { lua_pushinteger(L, a); lua_pushboolean(L, isStatic); lua_pushcclosure(L, setter_cl, 2); }
static int ns_get(lua_State *L) {   /* SWIG_Lua_namespace_get */
#if !defined(__wasm__)
  if (!ns_self_ok(L, ".get", ".fn")) return bad_self(L);
#endif
  lua_getmetatable(L, 1);
  lua_pushliteral(L, ".get"); lua_rawget(L, -2); lua_pushvalue(L, 2); lua_rawget(L, -2); lua_remove(L, -2);
  if (lua_iscfunction(L, -1)) { lua_call(L, 0, 1); return 1; }
  lua_pop(L, 1);
  lua_pushliteral(L, ".fn"); lua_rawget(L, -2); lua_pushvalue(L, 2); lua_rawget(L, -2); lua_remove(L, -2);
  if (lua_isfunction(L, -1)) return 1;
  return 0;
}
static int ns_set(lua_State *L) {   /* SWIG_Lua_namespace_set */
#if !defined(__wasm__)
  if (!lua_istable(L, 1) || !ns_self_ok(L, 0, 0)) return bad_self(L);   /* falls back to lua_rawset(L, 1) */
#endif
  lua_getmetatable(L, 1);
  lua_pushliteral(L, ".set"); lua_rawget(L, -2);
  if (lua_istable(L, -1)) {
    lua_pushvalue(L, 2); lua_rawget(L, -2);
    if (lua_iscfunction(L, -1)) { lua_pushvalue(L, 3); lua_call(L, 1, 0); return 0; }
    lua_pop(L, 1);
  }
  lua_pop(L, 2);
  lua_settop(L, 3); lua_rawset(L, 1);
  return 0;
}
static void new_namespace(lua_State *L) {   /* pushes table with SWIG namespace metatable */
  lua_newtable(L); lua_newtable(L);
  lua_pushliteral(L, ".get"); lua_newtable(L); lua_rawset(L, -3);
  lua_pushliteral(L, ".set"); lua_newtable(L); lua_rawset(L, -3);
  lua_pushliteral(L, ".fn"); lua_newtable(L); lua_rawset(L, -3);
  lua_pushliteral(L, "__index"); lua_pushcfunction(L, ns_get); lua_rawset(L, -3);
  lua_pushliteral(L, "__newindex"); lua_pushcfunction(L, ns_set); lua_rawset(L, -3);
  lua_setmetatable(L, -2);
}
static void ns_add_attr(lua_State *L, int a) {   /* namespace table on top */
  lua_getmetatable(L, -1);
  lua_pushliteral(L, ".get"); lua_rawget(L, -2); lua_pushstring(L, hs_attrs[a].name); push_getter(L, a, 1); lua_rawset(L, -3); lua_pop(L, 1);
  lua_pushliteral(L, ".set"); lua_rawget(L, -2); lua_pushstring(L, hs_attrs[a].name); push_setter(L, a, 1); lua_rawset(L, -3); lua_pop(L, 1);
  lua_pop(L, 1);
}
static void class_static_details(lua_State *L, int t) {   /* SWIG_Lua_add_class_static_details: bases first */
  const HsClass *c = &hs_classes[t];
  if (c->baseOff) for (int o = c->baseOff; hs_bases[o]; o++) class_static_details(L, hs_bases[o]);
  for (int i = 0; i < c->nconst; i++) add_const(L, &hs_consts[c->constOff + i]);
  for (int i = 0; i < c->nsmeth; i++) { const HsMember *m = &hs_members[c->smethOff + i]; lua_pushstring(L, m->name); push_method(L, m->id); lua_rawset(L, -3); }
  for (int i = 0; i < c->nsattr; i++) ns_add_attr(L, hs_members[c->sattrOff + i].id);
}
static int ctor_proxy(lua_State *L) {   /* _proxy__wrap_new_X: drop the class table, call the constructor */
#if !defined(__wasm__)
  if (lua_gettop(L) < 1) return bad_self(L);   /* lua_remove on an empty stack */
#endif
  lua_remove(L, 1); return invoke(L, (int)lua_tointeger(L, lua_upvalueindex(1)));
}
static void register_class(lua_State *L, int t);
static void register_class_static(lua_State *L, int t) {   /* parent namespace table on top */
  const HsClass *c = &hs_classes[t];
  new_namespace(L);
  if (c->ctor) { lua_getmetatable(L, -1); lua_pushliteral(L, "__call"); lua_pushinteger(L, c->ctor); lua_pushcclosure(L, ctor_proxy, 1); lua_rawset(L, -3); lua_pop(L, 1); }
  class_static_details(L, t);
  /* nested classes of the static part (flatnested enums etc.) */
  int so = hs_sclass_off[t];
  if (so) for (; hs_sclasses[so]; so++) register_class(L, hs_sclasses[so]);
  /* link .instance / .static */
  lua_getmetatable(L, -1); lua_pushliteral(L, ".instance"); PUSHREG(L, REG_MT); lua_rawgeti(L, -1, t); lua_remove(L, -2);
  lua_pushliteral(L, ".static"); lua_pushvalue(L, -5); lua_rawset(L, -3);
  lua_rawset(L, -3); lua_pop(L, 1);
  lua_pushstring(L, hs_types[t].name); lua_pushvalue(L, -2); lua_rawset(L, -4);
  lua_pop(L, 1);
}
static void register_class_instance(lua_State *L, int t) {
  PUSHREG(L, REG_MT);
  if (lua_rawgeti(L, -1, t) != LUA_TNIL) { lua_pop(L, 2); return; }
  lua_pop(L, 1);
  const HsClass *c = &hs_classes[t];
  if (c->baseOff) for (int o = c->baseOff; hs_bases[o]; o++) register_class_instance(L, hs_bases[o]);
  lua_newtable(L);
  lua_pushliteral(L, ".hs"); lua_pushboolean(L, 1); lua_rawset(L, -3);
  lua_pushliteral(L, ".type"); lua_pushstring(L, hs_types[t].name); lua_rawset(L, -3);
  lua_pushliteral(L, ".bases"); lua_newtable(L);
  if (c->baseOff) { int k = 1; for (int o = c->baseOff; hs_bases[o]; o++) { lua_rawgeti(L, -4, hs_bases[o]); lua_rawseti(L, -2, k++); } }
  lua_rawset(L, -3);
  lua_pushliteral(L, ".get"); lua_newtable(L);
  for (int i = 0; i < c->nattr; i++) { int a = hs_members[c->attrOff + i].id; lua_pushstring(L, hs_attrs[a].name); push_getter(L, a, 0); lua_rawset(L, -3); }
  if (c->hasTable) { lua_pushliteral(L, "table"); lua_pushcfunction(L, table_getter_cl); lua_rawset(L, -3); }
  lua_rawset(L, -3);
  lua_pushliteral(L, ".set"); lua_newtable(L);
  for (int i = 0; i < c->nattr; i++) { int a = hs_members[c->attrOff + i].id; lua_pushstring(L, hs_attrs[a].name); push_setter(L, a, 0); lua_rawset(L, -3); }
  lua_rawset(L, -3);
  lua_pushliteral(L, ".fn"); lua_newtable(L);
  lua_pushliteral(L, "__disown"); lua_pushcfunction(L, ud_disown); lua_rawset(L, -3);
  for (int i = 0; i < c->nmeth; i++) { const HsMember *m = &hs_members[c->methOff + i]; lua_pushstring(L, m->name); push_method(L, m->id); lua_rawset(L, -3); }
  lua_rawset(L, -3);
  for (int i = 0; i < c->nmeta; i++) { const HsMember *m = &hs_members[c->metaOff + i]; lua_pushstring(L, m->name); push_method(L, m->id); lua_rawset(L, -3); }
  lua_pushliteral(L, "__gc"); lua_pushcfunction(L, ud_gc); lua_rawset(L, -3);
  lua_rawseti(L, -2, t);
  lua_pop(L, 1);
}
/* inheritable metamethods (SWIG_Lua_add_class_user_metamethods): proxies resolve to the nearest base definition */
static const char *const INHERIT_MM[] = {"__add", "__sub", "__mul", "__div", "__mod", "__pow", "__unm", "__len", "__concat", "__eq", "__lt", "__le", "__call", "__tostring", 0};
static int find_meta(lua_State *L, int t, const char *name, int skipSelf) {   /* pushes function or returns 0 */
  if (!skipSelf) {
    PUSHREG(L, REG_MT); lua_rawgeti(L, -1, t); lua_remove(L, -2);
    lua_pushstring(L, name); lua_rawget(L, -2); lua_remove(L, -2);
    if (!lua_isnil(L, -1)) return 1;
    lua_pop(L, 1);
  }
  const HsClass *c = &hs_classes[t];
  if (c->baseOff) for (int o = c->baseOff; hs_bases[o]; o++) if (find_meta(L, hs_bases[o], name, 0)) return 1;
  return 0;
}
/* build FLAT/FLATSET by SWIG's search order: own .get, own .fn, then bases in order; __getitem/__setitem after */
static void flatten_into(lua_State *L, int t, int flat, int flatset) {
  const HsClass *c = &hs_classes[t];
  if (c->baseOff) {   /* earlier bases win: insert later bases first */
    int n = 0; while (hs_bases[c->baseOff + n]) n++;
    for (int k = n - 1; k >= 0; k--) flatten_into(L, hs_bases[c->baseOff + k], flat, flatset);
  }
  for (int i = 0; i < c->nmeth; i++) { const HsMember *m = &hs_members[c->methOff + i]; lua_pushstring(L, m->name); push_method(L, m->id); lua_rawset(L, flat); }
  for (int i = 0; i < c->nattr; i++) { int a = hs_members[c->attrOff + i].id; lua_pushstring(L, hs_attrs[a].name); lua_pushinteger(L, a); lua_rawset(L, flat); }
  if (c->hasTable) { lua_pushliteral(L, "table"); lua_pushboolean(L, 1); lua_rawset(L, flat); }
  lua_pushliteral(L, "__disown"); lua_pushcfunction(L, ud_disown); lua_rawset(L, flat);
  for (int i = 0; i < c->nattr; i++) { int a = hs_members[c->attrOff + i].id; lua_pushstring(L, hs_attrs[a].name); lua_pushinteger(L, a); lua_rawset(L, flatset); }
}
static void finish_class(lua_State *L, int t) {
  PUSHREG(L, REG_MT); lua_rawgeti(L, -1, t); lua_remove(L, -2);
  int mt = lua_gettop(L);
  for (int i = 0; INHERIT_MM[i]; i++) {
    lua_pushstring(L, INHERIT_MM[i]); lua_rawget(L, mt);
    int has = !lua_isnil(L, -1); lua_pop(L, 1);
    if (!has && find_meta(L, t, INHERIT_MM[i], 1)) { lua_pushstring(L, INHERIT_MM[i]); lua_insert(L, -2); lua_rawset(L, mt); }
  }
  lua_pushliteral(L, "__tostring"); lua_rawget(L, mt);
  if (lua_isnil(L, -1)) { lua_pushliteral(L, "__tostring"); lua_pushcfunction(L, ud_tostring); lua_rawset(L, mt); } lua_pop(L, 1);
  lua_pushliteral(L, "__eq"); lua_rawget(L, mt);
  if (lua_isnil(L, -1)) { lua_pushliteral(L, "__eq"); lua_pushcfunction(L, ud_eq); lua_rawset(L, mt); } lua_pop(L, 1);
  lua_newtable(L); int flat = lua_gettop(L);
  lua_newtable(L); int flatset = lua_gettop(L);
  flatten_into(L, t, flat, flatset);
  /* __index(ud,key) with upvalues FLAT, __getitem */
  lua_pushliteral(L, "__index"); lua_pushvalue(L, flat);
  if (!find_meta(L, t, "__getitem", 0)) lua_pushnil(L);
  lua_pushcclosure(L, ud_index, 2); lua_rawset(L, mt);
  lua_pushliteral(L, "__newindex"); lua_pushvalue(L, flatset);
  if (!find_meta(L, t, "__setitem", 0)) lua_pushnil(L);
  lua_pushcclosure(L, ud_newindex, 2); lua_rawset(L, mt);
  lua_settop(L, mt - 1);
}
static void register_class(lua_State *L, int t) { register_class_instance(L, t); register_class_static(L, t); }
static void register_namespace(lua_State *L, const HsNamespace *ns) {   /* sets global ns->name */
  lua_pushglobaltable(L);
  new_namespace(L);
  for (int i = 0; i < ns->nconst; i++) add_const(L, &hs_consts[ns->constOff + i]);
  for (int i = 0; i < ns->nmeth; i++) { const HsMember *m = &hs_members[ns->methOff + i]; lua_pushstring(L, m->name); push_method(L, m->id); lua_rawset(L, -3); }
  for (int i = 0; i < ns->nattr; i++) ns_add_attr(L, hs_members[ns->attrOff + i].id);
  for (int i = 0; i < ns->nclass; i++) register_class(L, hs_members[ns->classOff + i].id);
  lua_pushstring(L, ns->name); lua_pushvalue(L, -2); lua_rawset(L, -4);
  lua_pop(L, 2);
}

/* ---------------------------------------------------------------- Lua state setup (LuaScriptInit.cpp) */
static int hslua_print_common(lua_State *L, int kind, const char *what) {
  luaL_Buffer b; int n = lua_gettop(L);
  lua_getglobal(L, "tostring");
  luaL_buffinit(L, &b);
  for (int i = 1; i <= n; i++) {
    lua_pushvalue(L, n + 1); lua_pushvalue(L, i); lua_call(L, 1, 1);
    size_t l; const char *s = lua_tolstring(L, -1, &l);
    if (!s) return luaL_error(L, "'tostring' must return a string to '%s'", what);
    luaL_addvalue(&b); luaL_addstring(&b, "    ");
  }
  luaL_pushresult(&b);
  size_t l; const char *s = lua_tolstring(L, -1, &l);
  js_log(kind, s, (int)l);
  return 0;
}
static int l_log(lua_State *L) { return hslua_print_common(L, 0, "log"); }
static int l_print(lua_State *L) { return hslua_print_common(L, 1, "print"); }
/* lmathlib.c math_random with FTL's random32 (LuaScriptInit.cpp) */
static int math_random(lua_State *L) {
  lua_Integer low, up;
  double r = (double)js_rand32() * (1.0 / ((double)2147483647 + 1.0));
  switch (lua_gettop(L)) {
    case 0: lua_pushnumber(L, (lua_Number)r); return 1;
    case 1: low = 1; up = luaL_checkinteger(L, 1); break;
    case 2: low = luaL_checkinteger(L, 1); up = luaL_checkinteger(L, 2); break;
    default: return luaL_error(L, "wrong number of arguments");
  }
  luaL_argcheck(L, low <= up, 1, "interval is empty");
  luaL_argcheck(L, low >= 0 || up <= LUA_MAXINTEGER + low, 1, "interval too large");
  r *= (double)(up - low) + 1.0;
  lua_pushinteger(L, (lua_Integer)r + low);
  return 1;
}
static int math_randomseed(lua_State *L) {
  js_srand32((unsigned int)(lua_Integer)luaL_checknumber(L, 1));
  (void)js_rand32();
  return 0;
}
static void remove_dangerous(lua_State *L) {
  static const char *const names[] = {"collectgarbage", "dofile", "load", "loadfile", "newproxy", "rawequal", "rawget", "rawset", 0};
  for (int i = 0; names[i]; i++) { lua_pushnil(L); lua_setglobal(L, names[i]); }
  lua_getglobal(L, "math");
  lua_pushcfunction(L, math_random); lua_setfield(L, -2, "random");
  lua_pushcfunction(L, math_randomseed); lua_setfield(L, -2, "randomseed");
  lua_pop(L, 1);
}

/* ---------------------------------------------------------------- callbacks (LuaLibScript.cpp) */
#define NINT 128   /* > InternalEvents::UNKNOWN_MAX (81) */
#define NREN 64    /* > RenderEvents::UNKNOWN_MAX (25) */
typedef struct { int ref, ref2, prio, src, kind, ev; long long calls, instr, errors; double ms; int maxInstr; } Cb;
static Cb *cbs; static int ncbs, capcbs;
typedef struct { int *ix; int n, cap; } CbList;
static CbList intList[NINT], renList[NREN], loadList, initList;
static int evInfoArgs[NINT]; static char evInfoVar[NINT]; static const char *evInfoSig[NINT]; static const char *evName[NINT]; static const char *renName[NREN];
static int maxInt = 0, maxRen = 0;
/* sources (chunk names) of registered callbacks */
static char **srcs; static int nsrc;
static int src_id(const char *s) {
  for (int i = 0; i < nsrc; i++) if (!strcmp(srcs[i], s)) return i;
  srcs = realloc(srcs, sizeof(char *) * (nsrc + 1)); srcs[nsrc] = strdup(s); return nsrc++;
}
static int new_cb(lua_State *L, int ref, int ref2, int prio, int kind, int ev, int fnIndex) {
  if (ncbs == capcbs) { capcbs = capcbs ? capcbs * 2 : 64; cbs = realloc(cbs, sizeof(Cb) * capcbs); }
  Cb *c = &cbs[ncbs]; memset(c, 0, sizeof *c);
  c->ref = ref; c->ref2 = ref2; c->prio = prio; c->kind = kind; c->ev = ev;
  lua_Debug ar; lua_pushvalue(L, fnIndex); lua_getinfo(L, ">S", &ar); c->src = src_id(ar.source ? ar.source : "?");
  return ncbs++;
}
static void list_insert_sorted(CbList *l, int cb) {   /* push_back + std::stable_sort by priority desc */
  if (l->n == l->cap) { l->cap = l->cap ? l->cap * 2 : 8; l->ix = realloc(l->ix, sizeof(int) * l->cap); }
  int p = cbs[cb].prio, i = l->n;
  while (i > 0 && cbs[l->ix[i - 1]].prio < p) { l->ix[i] = l->ix[i - 1]; i--; }
  l->ix[i] = cb; l->n++;
}
static void list_push(CbList *l, int cb) {
  if (l->n == l->cap) { l->cap = l->cap ? l->cap * 2 : 8; l->ix = realloc(l->ix, sizeof(int) * l->cap); }
  l->ix[l->n++] = cb;
}
/* game events: name -> callbacks in registration order (std::multimap keeps insertion order per key) */
typedef struct { char *name; int loading; CbList l; } GameEv;
static GameEv *gevs; static int ngev;
static CbList *gev_list(const char *name, int loading, int create) {
  for (int i = 0; i < ngev; i++) if (gevs[i].loading == loading && !strcmp(gevs[i].name, name)) return &gevs[i].l;
  if (!create) return 0;
  gevs = realloc(gevs, sizeof(GameEv) * (ngev + 1)); memset(&gevs[ngev], 0, sizeof(GameEv));
  gevs[ngev].name = strdup(name); gevs[ngev].loading = loading; return &gevs[ngev++].l;
}

static int l_on_load(lua_State *L) {
  luaL_argcheck(L, lua_isfunction(L, 1), 1, "function expected!");
  int ref = luaL_ref(L, LUA_REGISTRYINDEX);   /* pops the top value: with extra args this is not the function (as HS) */
  lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
  list_push(&loadList, new_cb(L, ref, 0, 0, 2, 0, -1)); lua_pop(L, 1);
  js_registered(2, 0);
  return 0;
}
static int l_on_init(lua_State *L) {
  luaL_argcheck(L, lua_isfunction(L, 1), 1, "function expected!");
  int ref = luaL_ref(L, LUA_REGISTRYINDEX);
  lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
  list_push(&initList, new_cb(L, ref, 0, 0, 3, 0, -1)); lua_pop(L, 1);
  js_registered(3, 0);
  return 0;
}
static int l_on_game_event(lua_State *L) {
  luaL_argcheck(L, lua_isstring(L, 1), 1, "string expected!");
  luaL_argcheck(L, lua_isboolean(L, 2), 2, "boolean expected!");
  luaL_argcheck(L, lua_isfunction(L, 3), 3, "function expected!");
  const char *name = lua_tostring(L, 1); int onLoad = lua_toboolean(L, 2);
  lua_pushvalue(L, 3); int ref = luaL_ref(L, LUA_REGISTRYINDEX);
  list_push(gev_list(name, onLoad, 1), new_cb(L, ref, 0, 0, 4, 0, 3));
  js_registered(4, 0);
  return 0;
}
static int l_on_render_event(lua_State *L) {
  luaL_argcheck(L, lua_isinteger(L, 1), 1, "integer expected!");
  luaL_argcheck(L, lua_isfunction(L, 2), 2, "function expected!");
  luaL_argcheck(L, lua_isfunction(L, 3), 3, "function expected!");
  int id = (int)lua_tointeger(L, 1);
  luaL_argcheck(L, id > 0 && id < maxRen, 1, "Unknown RenderEvent!");
  lua_pushvalue(L, 2); int before = luaL_ref(L, LUA_REGISTRYINDEX);
  lua_pushvalue(L, 3); int after = luaL_ref(L, LUA_REGISTRYINDEX);
  int prio = 0;
  if (lua_gettop(L) >= 4) { luaL_argcheck(L, lua_isinteger(L, 4), 4, "integer expected!"); prio = (int)lua_tointeger(L, 4); }
  list_insert_sorted(&renList[id], new_cb(L, before, after, prio, 1, id, 2));
  js_registered(1, id);
  return 0;
}
static int l_on_internal_event(lua_State *L) {
  luaL_argcheck(L, lua_isinteger(L, 1), 1, "integer expected!");
  luaL_argcheck(L, lua_isfunction(L, 2), 2, "function expected!");
  int id = (int)lua_tointeger(L, 1);
  luaL_argcheck(L, id > 0 && id < maxInt, 1, "Unknown InternalEvent!");
  lua_pushvalue(L, 2); int ref = luaL_ref(L, LUA_REGISTRYINDEX);
  int prio = 0;
  if (lua_gettop(L) >= 3) { luaL_argcheck(L, lua_isinteger(L, 3), 3, "integer expected!"); prio = (int)lua_tointeger(L, 3); }
  lua_Debug ar; lua_rawgeti(L, LUA_REGISTRYINDEX, ref); lua_getinfo(L, ">u", &ar);
  if (ar.nparams > evInfoArgs[id]) {
    char buf[1024];
    snprintf(buf, sizeof buf, "Error: Callback function for InternalEvent %s has the wrong number of arguments! Expected %u, got %u\nExpected function of the form: %s\n",
             evName[id], (unsigned)evInfoArgs[id], (unsigned)ar.nparams, evInfoSig[id]);
    logf_(2, "%s", buf);
    return luaL_error(L, buf);
  } else if (ar.isvararg != evInfoVar[id]) {
    char buf[1024];
    snprintf(buf, sizeof buf, "Error: Provided function for InternalEvent %s %s!\nExpected function of the form: %s\n",
             evName[id], evInfoVar[id] ? "Should be a variable argument function" : "Should not be a variable argument function", evInfoSig[id]);
    logf_(2, "%s", buf);
    return luaL_error(L, buf);
  }
  int cb = new_cb(L, ref, 0, prio, 0, id, 2);
  list_insert_sorted(&intList[id], cb);
  js_registered(0, id);
  return 0;
}

/* instruction cap and profiling around each callback */
static int capInstr = 50000000, capLoad = 500000000;
static int profiling = 1;
static int curCb = -1;
static const char *capWhat = "";
static void count_hook(lua_State *L, lua_Debug *ar) {
  (void)ar;
  /* From here on every instruction raises it again, until the guard ends: a pcall in the script cannot swallow the cap. */
  lua_sethook(L, count_hook, LUA_MASKCOUNT, 1);
  if (curCb >= 0) {
    const Cb *c = &cbs[curCb];
    luaL_error(L, "instruction limit (%d) exceeded in %s, callback %s %s", capInstr, srcs[c->src],
               c->kind == 0 ? "InternalEvent" : c->kind == 1 ? "RenderEvent" : c->kind == 2 ? "on_load" : c->kind == 3 ? "on_init" : "on_game_event",
               c->kind == 0 ? evName[c->ev] : c->kind == 1 ? renName[c->ev] : "");
  } else luaL_error(L, "instruction limit (%d) exceeded while running %s", capLoad, capWhat);
}
typedef struct { int prevCb; int prevBase; int prevCount; double t0; int cap; } Guard;
static int guardDepth;
/* Outside a guard only finalizers run Lua code: they share one cap until the next guard ends. */
static void idle_hook(lua_State *L) {
  capWhat = "a finalizer";
  lua_sethook(L, count_hook, LUA_MASKCOUNT, capInstr);
}
static void guard_enter(lua_State *L, Guard *g, int cb, int cap) {
  guardDepth++;
  g->prevCb = curCb; g->prevBase = L->basehookcount; g->prevCount = L->hookcount;
  curCb = cb; g->cap = cap;
  lua_sethook(L, count_hook, LUA_MASKCOUNT, cap);
  g->t0 = profiling ? js_now() : 0;
}
static void guard_leave(lua_State *L, Guard *g, int failed) {
  if (curCb >= 0) {
    Cb *c = &cbs[curCb];
    int used = g->cap - L->hookcount; if (used < 0) used = g->cap;
    c->calls++; c->instr += used; if (used > c->maxInstr) c->maxInstr = used; if (failed) c->errors++;
    if (profiling) c->ms += js_now() - g->t0;
  }
  curCb = g->prevCb;
  if (--guardDepth == 0) idle_hook(L);
  else { lua_sethook(L, count_hook, LUA_MASKCOUNT, g->prevBase); L->hookcount = g->prevCount; }
}
static int pcall_cb(lua_State *L, int cb, int nArg, int nRet) {
  Guard g; guard_enter(L, &g, cb, capInstr);
  int r = lua_pcall(L, nArg, nRet, 0);
  guard_leave(L, &g, r != 0);
  return r;
}

/* LuaLibScript::call_on_internal_event_callbacks: args on the stack top; returns nRet if a callback returned non-nil */
EXPORT(hs_fire) int hs_fire(int id, int nArg, int nRet) {
  lua_State *L = L0;
  if (id <= 0 || id >= NINT) return 0;
  CbList *l = &intList[id];
  for (int i = 0; i < l->n; i++) {
    int cb = l->ix[i];
    lua_rawgeti(L, LUA_REGISTRYINDEX, cbs[cb].ref);
    for (int k = 0; k < nArg; k++) lua_pushvalue(L, -1 - nArg);
    if (pcall_cb(L, cb, nArg, nRet) != 0) {
      logf_(2, "Failed to call the callback for InternalEvent %s!\n %s\n", evName[id], lua_tostring(L, -1));
      lua_pop(L, 1); continue;
    }
    if (nRet > 0) {
      if (!lua_isnil(L, -nRet)) return nRet;
      lua_pop(L, nRet);
    }
  }
  return 0;
}
/* call_on_internal_chain_event_callbacks: returns 1 when PREEMPT was requested */
EXPORT(hs_fire_chain) int hs_fire_chain(int id, int nArg, int nRet) {
  lua_State *L = L0;
  if (id <= 0 || id >= NINT) return 0;
  CbList *l = &intList[id];
  for (int i = 0; i < l->n; i++) {
    int cb = l->ix[i];
    lua_rawgeti(L, LUA_REGISTRYINDEX, cbs[cb].ref);
    for (int k = 0; k < nArg; k++) lua_pushvalue(L, -1 - nArg);
    if (pcall_cb(L, cb, nArg, nRet + 1) != 0) {
      logf_(2, "Failed to call the callback for InternalEvent %s!\n %s\n", evName[id], lua_tostring(L, -1));
      lua_pop(L, 1); continue;
    }
    for (int k = 0; k < nRet; k++) {
      if (!lua_isnil(L, -1)) lua_replace(L, -nRet - 2);
      else lua_pop(L, 1);
    }
    int chain = lua_isinteger(L, -1) ? (int)lua_tointeger(L, -1) : 0;
    lua_pop(L, 1);
    if (chain != 0) return chain == 2;
  }
  return 0;
}
/* render pre/post (call_on_render_event_pre/post_callbacks) */
EXPORT(hs_render_pre) int hs_render_pre(int id, int nArg) {
  lua_State *L = L0;
  if (id <= 0 || id >= NREN) return 0;
  CbList *l = &renList[id];
  int idx;
  for (idx = 0; idx < l->n; idx++) {
    int cb = l->ix[idx];
    lua_rawgeti(L, LUA_REGISTRYINDEX, cbs[cb].ref);
    for (int k = 0; k < nArg; k++) lua_pushvalue(L, -1 - nArg);
    if (pcall_cb(L, cb, nArg, 1) != 0) {
      logf_(2, "Failed to call the before callback for RenderEvent %s!\n %s\n", renName[id], lua_tostring(L, -1));
      lua_pop(L, 1); continue;
    }
    int chain = lua_isinteger(L, -1) ? (int)lua_tointeger(L, -1) : 0;
    lua_pop(L, 1);
    if (chain != 0) { int ret = idx + 1; return chain == 2 ? -ret : ret; }
  }
  return idx;
}
EXPORT(hs_render_post) void hs_render_post(int id, int idx, int nArg) {
  lua_State *L = L0;
  if (id <= 0 || id >= NREN) return;
  CbList *l = &renList[id];
  for (--idx; idx >= 0; --idx) {
    int cb = l->ix[idx];
    lua_rawgeti(L, LUA_REGISTRYINDEX, cbs[cb].ref2);
    for (int k = 0; k < nArg; k++) lua_pushvalue(L, -1 - nArg);
    if (pcall_cb(L, cb, nArg, 0) != 0) {
      logf_(2, "Failed to call the after callback for RenderEvent %s!\n %s\n", renName[id], lua_tostring(L, -1));
      lua_pop(L, 1); continue;
    }
  }
}
EXPORT(hs_has) int hs_has(int kind, int id) {
  if (kind == 0) return id > 0 && id < NINT ? intList[id].n : 0;
  if (kind == 1) return id > 0 && id < NREN ? renList[id].n : 0;
  if (kind == 2) return loadList.n;
  if (kind == 3) return initList.n;
  return ngev;
}
/* on_load / on_init: stop at the first failure, as HS does */
static void run_list(CbList *l, int nArg, int argBool, const char *what) {
  lua_State *L = L0;
  logf_(0, "Fetching %u %s callbacks\n", (unsigned)l->n, what);
  for (int i = 0; i < l->n; i++) {
    int cb = l->ix[i];
    lua_rawgeti(L, LUA_REGISTRYINDEX, cbs[cb].ref);
    if (nArg) lua_pushboolean(L, argBool);
    if (pcall_cb(L, cb, nArg, 0) != 0) {
      logf_(2, "Failed to call the callback!\n %s\n", lua_tostring(L, -1));
      lua_pop(L, 1); return;
    }
  }
}
EXPORT(hs_on_load) void hs_on_load(void) { run_list(&loadList, 0, 0, "on_load"); }
EXPORT(hs_on_init) void hs_on_init(int newGame) { run_list(&initList, 1, newGame, "on_init"); }
/* event name in sbuf[0..len) */
EXPORT(hs_game_event) void hs_game_event(int len, int isLoading) {
  lua_State *L = L0;
  char name[512]; if (len > 511) len = 511; memcpy(name, sbuf, len); name[len] = 0;
  CbList *l = gev_list(name, isLoading, 0);
  logf_(0, "Fetching %u on_game_event callbacks for %s\n", (unsigned)(l ? l->n : 0), name);
  if (!l) return;
  for (int i = 0; ; i++) {
    l = gev_list(name, isLoading, 0);   /* a callback registering a new event name moves gevs */
    if (i >= l->n) break;
    int cb = l->ix[i];
    lua_rawgeti(L, LUA_REGISTRYINDEX, cbs[cb].ref);
    if (pcall_cb(L, cb, 0, 0) != 0) {
      logf_(2, "Failed to call the callback for %s!\n %s\n", name, lua_tostring(L, -1));
      lua_pop(L, 1); return;
    }
  }
}

/* run a chunk: name in sbuf[0..nameLen), code in sbuf[nameLen..nameLen+codeLen). Returns 0 or 1 (error logged and
 * left in sbuf, length via hs_errlen). Mirrors runLuaFileFromDat ("t" mode, LUA_MULTRET, errors logged). */
static int errlen;
EXPORT(hs_errlen) int hs_errlen(void) { return errlen; }
EXPORT(hs_run) int hs_run(int nameLen, int codeLen, int console) {
  lua_State *L = L0;
  char *name = malloc(nameLen + 2); memcpy(name, sbuf, nameLen); name[nameLen] = 0;
  char *code = malloc(codeLen + 1); memcpy(code, sbuf + nameLen, codeLen); code[codeLen] = 0;
  int top = lua_gettop(L);
  /* HS loads with strlen(code): content after an embedded NUL is ignored */
  int err = luaL_loadbufferx(L, code, strlen(code), name, "t");
  int failed = 0;
  if (err) {
    const char *m = lua_tostring(L, -1);
    if (console) logf_(2, "Failed to load lua code from in-game console. Error: '%s'\n", m);
    else logf_(2, "Failed to load lua code from file '%s'. Error: '%s'\n", name, m);
    failed = 1;
  } else {
    Guard g; capWhat = name; guard_enter(L, &g, -1, capLoad);
    err = lua_pcall(L, 0, LUA_MULTRET, 0);
    guard_leave(L, &g, err != 0);
    if (err) {
      const char *m = lua_tostring(L, -1);
      if (console) logf_(2, "in-game console lua execution failed. Error: '%s'\n", m);
      else logf_(2, "Lua execution failed in file '%s'. Error: '%s'\n", name, m);
      failed = 1;
    }
  }
  if (failed) {
    size_t n = 0; const char *m = lua_tolstring(L, -1, &n);
    if (m && hs_sbuf_grow((int)n + 1)) { memcpy(sbuf, m, n); errlen = (int)n; } else errlen = 0;
  }
  lua_settop(L, top);
  free(name); free(code);
  return failed;
}

/* ---------------------------------------------------------------- stack access for the engine */
EXPORT(hs_gettop) int hs_gettop(void) { return lua_gettop(L0); }
EXPORT(hs_settop) void hs_settop(int i) { lua_settop(L0, i); }
EXPORT(hs_pop) void hs_pop(int n) { lua_pop(L0, n); }
EXPORT(hs_type) int hs_type(int i) { return lua_type(L0, i); }
EXPORT(hs_isnumber) int hs_isnumber(int i) { return lua_isnumber(L0, i); }
EXPORT(hs_isinteger) int hs_isinteger(int i) { return lua_isinteger(L0, i); }
EXPORT(hs_isstring) int hs_isstring(int i) { return lua_isstring(L0, i); }
EXPORT(hs_tonumber) double hs_tonumber(int i) { return lua_tonumber(L0, i); }
EXPORT(hs_tointeger) double hs_tointeger(int i) { return (double)lua_tointeger(L0, i); }
EXPORT(hs_toboolean) int hs_toboolean(int i) { return lua_toboolean(L0, i); }
EXPORT(hs_tostring) int hs_tostring(int i) {   /* lua_tostring into sbuf; -1 when not a string/number */
  size_t n; const char *s = lua_tolstring(L0, i, &n);
  if (!s) return -1;
  if (!hs_sbuf_grow((int)n + 1)) return -1;
  memcpy(sbuf, s, n); return (int)n;
}
EXPORT(hs_tohandle) int hs_tohandle(int i) { HsUd *u = toud(L0, i); return u ? u->h : 0; }
EXPORT(hs_toobjtype) int hs_toobjtype(int i) { HsUd *u = toud(L0, i); return u ? u->t : 0; }
EXPORT(hs_isfunction) int hs_isfunction(int i) { return lua_isfunction(L0, i); }
EXPORT(hs_pushnil) void hs_pushnil(void) { lua_pushnil(L0); }
EXPORT(hs_pushnumber) void hs_pushnumber(double v) { lua_pushnumber(L0, v); }
EXPORT(hs_pushinteger) void hs_pushinteger(double v) { lua_pushinteger(L0, (lua_Integer)v); }
EXPORT(hs_pushboolean) void hs_pushboolean(int b) { lua_pushboolean(L0, b); }
EXPORT(hs_pushstring) void hs_pushstring(int len) { lua_pushlstring(L0, sbuf, len); }
EXPORT(hs_pushobj) void hs_pushobj(int h, int t) { push_obj(L0, h, t); }
EXPORT(hs_pushvalue) void hs_pushvalue(int i) { lua_pushvalue(L0, i); }
EXPORT(hs_replace) void hs_replace(int i) { lua_replace(L0, i); }
EXPORT(hs_newtable) void hs_newtable(void) { lua_newtable(L0); }
EXPORT(hs_rawseti) void hs_rawseti(int t, int i) { lua_rawseti(L0, t, i); }
EXPORT(hs_setfield) void hs_setfield(int t, int len) { char k[256]; if (len > 255) len = 255; memcpy(k, sbuf, len); k[len] = 0; lua_setfield(L0, t, k); }
/* pushes a reference (luaL_ref) back, e.g. a function argument the engine keeps */
EXPORT(hs_ref) int hs_ref(int i) { lua_pushvalue(L0, i); return luaL_ref(L0, LUA_REGISTRYINDEX); }
EXPORT(hs_unref) void hs_unref(int r) { luaL_unref(L0, LUA_REGISTRYINDEX, r); }
EXPORT(hs_pushref) void hs_pushref(int r) { lua_rawgeti(L0, LUA_REGISTRYINDEX, r); }
/* pcall a value on the stack with n args, errors logged with context in sbuf */
EXPORT(hs_pcall) int hs_pcall(int nArg, int nRet) {
  Guard g; capWhat = "engine call"; guard_enter(L0, &g, -1, capInstr);
  int r = lua_pcall(L0, nArg, nRet, 0);
  guard_leave(L0, &g, r != 0);
  if (r) { logf_(2, "Lua call failed: %s\n", lua_tostring(L0, -1)); lua_pop(L0, 1); }
  return r;
}
EXPORT(hs_gc_count) int hs_gc_count(void) { return lua_gc(L0, LUA_GCCOUNT, 0); }
/* a full collection, which finalizes every userdata nothing references (the engine asks when it runs short of handles) */
static int gc_call(lua_State *L) { lua_gc(L, (int)lua_tointeger(L, 1), (int)lua_tointeger(L, 2)); return 0; }
/* A script's __gc can raise, or run into the cap: that ends the collection, the rest runs at the next one. */
static void gc_guarded(int what, int data) {
  if (!lua_checkstack(L0, 3)) return;
  lua_pushcfunction(L0, gc_call); lua_pushinteger(L0, what); lua_pushinteger(L0, data);
  /* Inside a guard the finalizers spend what the running callback has left. */
  if (guardDepth > 0) {
    if (lua_pcall(L0, 2, 0, 0)) { logf_(2, "Lua collection stopped: %s\n", lua_tostring(L0, -1)); lua_pop(L0, 1); }
  } else if (hs_pcall(2, 0)) logf_(2, "Lua collection stopped\n");
}
EXPORT(hs_full_gc) void hs_full_gc(void) { if (L0 && !closing) gc_guarded(LUA_GCCOLLECT, 0); }
/* collector work for memory the engine holds for Lua (one handle per pushed object), as if [kb] were allocated here */
EXPORT(hs_gc_step) void hs_gc_step(int kb) { if (L0 && !closing && kb > 0) gc_guarded(LUA_GCSTEP, kb); }
EXPORT(hs_set_caps) void hs_set_caps(int perCallback, int perScript) { if (perCallback > 0) capInstr = perCallback; if (perScript > 0) capLoad = perScript; }
EXPORT(hs_set_profiling) void hs_set_profiling(int on) { profiling = on; }

/* profile as JSON into sbuf; returns length */
static void jesc(luaL_Buffer *b, const char *s) {
  luaL_addchar(b, '"');
  for (; *s; s++) {
    unsigned char ch = (unsigned char)*s;
    if (ch == '"' || ch == '\\') { luaL_addchar(b, '\\'); luaL_addchar(b, ch); }
    else if (ch < 0x20) { char t[8]; snprintf(t, sizeof t, "\\u%04x", ch); luaL_addstring(b, t); }
    else luaL_addchar(b, ch);
  }
  luaL_addchar(b, '"');
}
EXPORT(hs_profile) int hs_profile(int reset) {
  lua_State *L = L0; luaL_Buffer b; char t[256];
  luaL_buffinit(L, &b);
  snprintf(t, sizeof t, "{\"luaKiB\":%d,\"instructionCap\":%d,\"callbacks\":[", lua_gc(L, LUA_GCCOUNT, 0), capInstr); luaL_addstring(&b, t);
  for (int i = 0; i < ncbs; i++) {
    Cb c = cbs[i];   /* a copy: growing the buffer can run a finalizer that registers a callback and moves cbs */
    if (i) luaL_addchar(&b, ',');
    luaL_addstring(&b, "{\"source\":"); jesc(&b, srcs[c.src]);
    luaL_addstring(&b, ",\"kind\":"); jesc(&b, c.kind == 0 ? "internal" : c.kind == 1 ? "render" : c.kind == 2 ? "load" : c.kind == 3 ? "init" : "game_event");
    luaL_addstring(&b, ",\"event\":"); jesc(&b, c.kind == 0 ? evName[c.ev] : c.kind == 1 ? renName[c.ev] : "");
    snprintf(t, sizeof t, ",\"priority\":%d,\"calls\":%lld,\"instructions\":%lld,\"maxInstructions\":%d,\"errors\":%lld,\"ms\":%.4f}", c.prio, c.calls, c.instr, c.maxInstr, c.errors, c.ms);
    luaL_addstring(&b, t);
    if (reset) { Cb *now = &cbs[i]; now->calls = 0; now->instr = 0; now->errors = 0; now->ms = 0; now->maxInstr = 0; }
  }
  luaL_addstring(&b, "]}");
  luaL_pushresult(&b);
  size_t n; const char *s = lua_tolstring(L, -1, &n);
  int len = -1;
  if (hs_sbuf_grow((int)n + 1)) { memcpy(sbuf, s, n); len = (int)n; }
  lua_pop(L, 1);
  return len;
}

static int at_panic(lua_State *L) { logf_(2, "PANIC: unprotected error in call to Lua API (%s)\n", lua_tostring(L, -1)); return 0; }

static const luaL_Reg script_funcs[] = {
  {"on_load", l_on_load}, {"on_init", l_on_init}, {"on_game_event", l_on_game_event},
  {"on_render_event", l_on_render_event}, {"on_internal_event", l_on_internal_event}, {0, 0}
};
static int open_script(lua_State *L) { luaL_newlib(L, script_funcs); return 1; }

/* event tables from the Defines module constants (InternalEvents / RenderEvents classes) */
static void load_event_names(void) {
  for (int t = 1; t <= hs_ntypes; t++) {
    if (!hs_types[t].isClass) continue;
    int isInt = !strcmp(hs_types[t].name, "InternalEvents"), isRen = !strcmp(hs_types[t].name, "RenderEvents");
    if (!isInt && !isRen) continue;
    const HsClass *c = &hs_classes[t];
    for (int i = 0; i < c->nconst; i++) {
      const HsConst *k = &hs_consts[c->constOff + i];
      if (k->isString || k->n < 0) continue;
      if (isInt && k->n < NINT) { evName[k->n] = k->name; if (!strcmp(k->name, "UNKNOWN_MAX")) maxInt = (int)k->n; }
      if (isRen && k->n < NREN) { renName[k->n] = k->name; if (!strcmp(k->name, "UNKNOWN_MAX")) maxRen = (int)k->n; }
    }
  }
}
#include "gen/hs_events.h"   /* InternalEvents::GetEventInfo: {signature, argCount, isVariableArgs} */

/* drop the previous state and every registration (engine restart, diagnostics) */
static void reset_all(void) {
  /* lua_close runs every finalizer; the engine has reset its handles already, so they release nothing */
  if (L0) { closing = 1; curCb = -1; guardDepth = 0; idle_hook(L0); lua_close(L0); L0 = 0; closing = 0; }
  free(udCount); udCount = 0; udCountCap = 0;
  for (int i = 0; i < NINT; i++) { free(intList[i].ix); memset(&intList[i], 0, sizeof(CbList)); }
  for (int i = 0; i < NREN; i++) { free(renList[i].ix); memset(&renList[i], 0, sizeof(CbList)); }
  free(loadList.ix); memset(&loadList, 0, sizeof loadList); free(initList.ix); memset(&initList, 0, sizeof initList);
  for (int i = 0; i < ngev; i++) { free(gevs[i].name); free(gevs[i].l.ix); }
  free(gevs); gevs = 0; ngev = 0;
  for (int i = 0; i < nsrc; i++) free(srcs[i]);
  free(srcs); srcs = 0; nsrc = 0;
  free(cbs); cbs = 0; ncbs = capcbs = 0;
  memset(finished, 0, sizeof finished);
  free(pendingErr); pendingErr = 0; curCb = -1;
}
EXPORT(hs_init) int hs_init(void) {
  reset_all();
  if (!sbuf) { sbufCap = 65536; sbuf = malloc(sbufCap); }
  lua_State *L = luaL_newstate();
  L0 = L;
  idle_hook(L);
  lua_atpanic(L, at_panic);
  luaL_openlibs(L);                                   /* hs-lua/linit.c: HS's library set */
  for (int i = 1; i < REG_LAST; i++) { lua_newtable(L); regRef[i] = luaL_ref(L, LUA_REGISTRYINDEX); }
  PUSHREG(L, REG_CACHE); lua_newtable(L); lua_pushliteral(L, "v"); lua_setfield(L, -2, "__mode"); lua_setmetatable(L, -2); lua_pop(L, 1);
  PUSHREG(L, REG_CACHE2); lua_newtable(L); lua_pushliteral(L, "v"); lua_setfield(L, -2, "__mode"); lua_setmetatable(L, -2); lua_pop(L, 1);
  PUSHREG(L, REG_SENTINEL); lua_pushcfunction(L, ud_gc); lua_setfield(L, -2, "__gc"); lua_pop(L, 1);
  PUSHREG(L, REG_PLAIN); lua_newtable(L); lua_pushliteral(L, "k"); lua_setfield(L, -2, "__mode"); lua_setmetatable(L, -2); lua_pop(L, 1);
  load_event_names();
  for (int i = 0; i < NINT && i < (int)(sizeof hs_event_info / sizeof hs_event_info[0]); i++) {
    evInfoSig[i] = hs_event_info[i].sig; evInfoArgs[i] = hs_event_info[i].args; evInfoVar[i] = (char)hs_event_info[i].vararg;
  }
  lua_register(L, "log", l_log);                     /* hsluaglobal_register */
  lua_register(L, "print", l_print);
  luaL_requiref(L, "script", open_script, 1); lua_pop(L, 1);   /* LuaLibScript constructor */
  /* SWIG_init for each module: swig_type/swig_equals globals, classes, namespace, %init, %luacode */
  lua_pushcfunction(L, l_swig_type); lua_setglobal(L, "swig_type");
  lua_pushcfunction(L, ud_eq); lua_setglobal(L, "swig_equals");
  ntypes = hs_ntypes;
  for (int n = 0; n < hs_nnamespaces; n++) {
    register_namespace(L, &hs_namespaces[n]);
    for (int t = 1; t <= hs_ntypes; t++) if (hs_types[t].isClass && !finished[t]) {   /* finish classes registered so far */
      PUSHREG(L, REG_MT); int has = lua_rawgeti(L, -1, t) == LUA_TTABLE; lua_settop(L, 0);
      if (has) { finish_class(L, t); finished[t] = 1; }
    }
    const char *code = hs_namespaces[n].luacode;
    if (code && *code) {                               /* SWIG_Lua_dostring: chunk name = the code itself */
      if (luaL_loadstring(L, code) || lua_pcall(L, 0, LUA_MULTRET, 0)) { logf_(2, "%s\n", lua_tostring(L, -1)); }
      lua_settop(L, 0);
    }
  }
  remove_dangerous(L);
  lua_gc(L, LUA_GCSETPAUSE, 100);
  lua_settop(L, 0);
  return 0;
}
