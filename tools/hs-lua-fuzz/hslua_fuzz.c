/*
 * libFuzzer target for the Hyperspace Lua runtime (hs-lua/hslua.c with Lua 5.3.6), built with ASan for the Android
 * emulator: see README.md. Includes the runtime as android/engine/src/main/jni/hslua_jni.c does and answers its js_*
 * imports with a fake engine. The natives below (n_*) keep the error boundaries and stack checks of hslua_jni.c.
 *
 * Input: 4 header bytes, then Lua source chunks separated by NUL bytes.
 *   byte 0: bits 0-1 instruction caps, 2-3 engine failure rate, 4-5 wrong object rate, 6-7 engine reentrancy rate
 *   byte 1: bits 0-1 allocation failure rate, 2-3 heap cap, 4 aggressive collector, 5 profiling
 *   bytes 2-3: PRNG seed for everything the engine answers
 * A chunk that does not parse only loses itself; the events still fire on what the others registered.
 */
#include "hslua.c"
#include "ldo.h"

#define SBUF_MAX (1 << 26)
#define HEAP_CAP ((size_t)64 << 20)
#define MAX_CHUNKS 8
#define MAX_DEPTH 6
#define REENTER_BUDGET 48

/* ---------------------------------------------------------------- input-driven PRNG and rates */
static uint64_t prng = 1;
static uint32_t rnd(void) { prng ^= prng << 13; prng ^= prng >> 7; prng ^= prng << 17; return (uint32_t)(prng >> 16); }
static int one_in(int n) { return n > 0 && rnd() % (unsigned)n == 0; }
static int rateErr, rateWrong, rateReenter, rateAllocFail, armed, depth, budget;
static volatile unsigned sink;
static void touch(const char *p, int n) { unsigned s = 0; for (int i = 0; i < n; i++) s += (unsigned char)p[i]; sink += s; }

/* ---------------------------------------------------------------- Lua heap cap (hslua_jni.c), with failure injection */
static size_t heapUsed, heapCap = HEAP_CAP;
static void *capped_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
  (void)ud;
  size_t old = ptr ? osize : 0;
  if (nsize == 0) { free(ptr); heapUsed -= old; return NULL; }
  if (nsize > old && nsize - old > heapCap - heapUsed) return NULL;
  if (nsize > old && armed && one_in(rateAllocFail)) return NULL;
  void *p = realloc(ptr, nsize);
  if (p) heapUsed = heapUsed - old + nsize;
  return p;
}
static void init_state(void) {
  hs_init();
  heapUsed = (size_t)lua_gc(L0, LUA_GCCOUNT, 0) * 1024 + (size_t)lua_gc(L0, LUA_GCCOUNTB, 0);
  lua_setallocf(L0, capped_alloc, NULL);
}

/* ---------------------------------------------------------------- engine calls and error boundaries (hslua_jni.c) */
typedef struct HostCall { struct HostCall *prev; char *err; int len; } HostCall;
static HostCall *hostCall;
#define LIT(s) s, sizeof s - 1
static void fail(HostCall *hc, const char *s, size_t n) {
  if (hc->err) return;
  if (n > 65536) n = 65536;
  char *p = malloc(n + 1);
  if (!p) return;
  memcpy(p, s, n); hc->err = p; hc->len = (int)n;
}
static void enter(HostCall *hc) { hc->prev = hostCall; hc->err = 0; hc->len = 0; hostCall = hc; }
static int leave(HostCall *hc) { hostCall = hc->prev; return hc->err != 0; }
static int failure_to_sbuf(HostCall *hc) {
  int n = hc->len;
  if (hs_sbuf_grow(n + 1)) memcpy(sbuf, hc->err, n); else n = 0;
  free(hc->err); hc->err = 0;
  return n;
}
static void failure_raise(HostCall *hc) { hs_raise(failure_to_sbuf(hc)); }
static void failure_log(HostCall *hc) { touch(hc->err, hc->len); free(hc->err); hc->err = 0; }
static int to_sbuf(const char *p, int n, int off) {
  if ((long long)off + n + 1 > SBUF_MAX || !hs_sbuf_grow(off + n + 1)) return -1;
  memcpy(sbuf + off, p, n);
  return n;
}
typedef struct { int a, b, c, r; } Op;
static void defer_error(lua_State *L, const char *why) {
  size_t n = 0;
  const char *m = lua_type(L, -1) == LUA_TSTRING ? lua_tolstring(L, -1, &n) : NULL;
  if (!m) { m = why; n = strlen(why); }
  if (hostCall) fail(hostCall, m, n);
  else logf_(2, "Lua error outside a callback: %.*s\n", (int)n, m);
}
static int protect(Pfunc f, Op *o, int delta) {
  lua_State *L = L0;
  int top = lua_gettop(L);
  if (luaD_pcall(L, f, o, savestack(L, L->top), 0) == LUA_OK) return 0;
  defer_error(L, "error object is not a string");
  if (delta > 0) { lua_settop(L, top); lua_pushnil(L); }
  else lua_settop(L, top + delta);
  return 1;
}
static void fail_now(const char *m) {
  if (hostCall) fail(hostCall, m, strlen(m));
  else logf_(2, "Lua error outside a callback: %s\n", m);
}
static int room(int n) {
  if (lua_checkstack(L0, n)) return 1;
  fail_now("Lua stack overflow");
  return 0;
}

static void op_fire(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_fire(o->a, o->b, o->c); }
static void op_fire_chain(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_fire_chain(o->a, o->b, o->c); }
static void op_render_pre(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_render_pre(o->a, o->b); }
static void op_render_post(lua_State *L, void *u) { (void)L; Op *o = u; hs_render_post(o->a, o->b, o->c); }
static void op_on_load(lua_State *L, void *u) { (void)L; (void)u; hs_on_load(); }
static void op_on_init(lua_State *L, void *u) { (void)L; Op *o = u; hs_on_init(o->a); }
static void op_game_event(lua_State *L, void *u) { (void)L; Op *o = u; hs_game_event(o->a, o->b); }
static void op_run(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_run(o->a, o->b, o->c); }
static void op_profile(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_profile(o->a); }
static void op_drop_table(lua_State *L, void *u) { (void)L; Op *o = u; hs_drop_table(o->a); }
static void op_full_gc(lua_State *L, void *u) { (void)L; (void)u; hs_full_gc(); }
static void op_gc_step(lua_State *L, void *u) { (void)L; Op *o = u; hs_gc_step(o->a); }
static void op_tostring(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_tostring(o->a); }
static void op_tohandle(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_tohandle(o->a); }
static void op_toobjtype(lua_State *L, void *u) { (void)L; Op *o = u; o->r = hs_toobjtype(o->a); }
static void op_pushstring(lua_State *L, void *u) { (void)L; Op *o = u; hs_pushstring(o->a); }
static void op_pushobj(lua_State *L, void *u) { (void)L; Op *o = u; hs_pushobj(o->a, o->b); }
static void op_newtable(lua_State *L, void *u) { (void)L; (void)u; hs_newtable(); }
static void op_rawseti(lua_State *L, void *u) { (void)L; Op *o = u; hs_rawseti(o->a, o->b); }
static void op_setfield(lua_State *L, void *u) { (void)L; Op *o = u; hs_setfield(o->a, o->b); }

static int n_fire(int id, int nArg, int nRet) { Op o = {id, nArg, nRet, 0}; if (!room(nArg + nRet + 2) || protect(op_fire, &o, 0)) return 0; return o.r; }
static int n_fire_chain(int id, int nArg, int nRet) { Op o = {id, nArg, nRet, 0}; if (!room(nArg + nRet + 3) || protect(op_fire_chain, &o, 0)) return 0; return o.r != 0; }
static int n_render_pre(int id, int nArg) { Op o = {id, nArg, 0, 0}; if (!room(nArg + 3) || protect(op_render_pre, &o, 0)) return 0; return o.r; }
static void n_render_post(int id, int idx, int nArg) { Op o = {id, idx, nArg, 0}; if (room(nArg + 2)) protect(op_render_post, &o, 0); }
static void n_on_load(void) { Op o = {0, 0, 0, 0}; if (room(2)) protect(op_on_load, &o, 0); }
static void n_on_init(int b) { Op o = {b, 0, 0, 0}; if (room(3)) protect(op_on_init, &o, 0); }
static void n_drop_table(int t) { Op o = {t, 0, 0, 0}; if (room(3)) protect(op_drop_table, &o, 0); }
static void n_full_gc(void) { Op o = {0, 0, 0, 0}; if (room(3)) protect(op_full_gc, &o, 0); }
static void n_gc_step(int kb) { Op o = {kb, 0, 0, 0}; if (room(3)) protect(op_gc_step, &o, 0); }
static int n_tohandle(int i) { Op o = {i, 0, 0, 0}; if (!room(3) || protect(op_tohandle, &o, 0)) return 0; return o.r; }
static int n_toobjtype(int i) { Op o = {i, 0, 0, 0}; if (!room(3) || protect(op_toobjtype, &o, 0)) return 0; return o.r; }
static void n_pushnil(void) { if (room(1)) hs_pushnil(); }
static void n_pushnumber(double v) { if (room(1)) hs_pushnumber(v); }
static void n_pushinteger(double v) { if (room(1)) hs_pushinteger(v); }
static void n_pushboolean(int b) { if (room(1)) hs_pushboolean(b); }
static void n_pushobj(int h, int t) { Op o = {h, t, 0, 0}; if (room(5)) protect(op_pushobj, &o, 1); }
static void n_pushvalue(int i) { if (room(1)) hs_pushvalue(i); }
static void n_newtable(void) { Op o = {0, 0, 0, 0}; if (room(1)) protect(op_newtable, &o, 1); }
static void n_rawseti(int t, int i) { Op o = {t, i, 0, 0}; protect(op_rawseti, &o, -1); }
static void n_pushstring(const char *p, int len) {
  if (!room(1)) return;
  int n = to_sbuf(p, len, 0);
  if (n < 0) { fail_now("string too long"); hs_pushnil(); return; }
  Op o = {n, 0, 0, 0};
  protect(op_pushstring, &o, 1);
}
static void n_setfield(int t, const char *p, int len) {
  int n = to_sbuf(p, len, 0);
  if (n < 0) { fail_now("field name too long"); hs_pop(1); return; }
  Op o = {t, n, 0, 0};
  if (!room(2)) { hs_pop(1); return; }
  protect(op_setfield, &o, -1);
}
static void n_tostring(int i) {
  Op o = {i, 0, 0, 0};
  if (!protect(op_tostring, &o, 0) && o.r >= 0) touch(sbuf, o.r);
}
static void n_game_event(const char *p, int len, int loading) {
  int n = to_sbuf(p, len, 0);
  if (n < 0) { fail_now("event name too long"); return; }
  Op o = {n, loading, 0, 0};
  if (room(2)) protect(op_game_event, &o, 0);
}
static void n_profile(int reset) {
  Op o = {reset, 0, 0, 0};
  if (!room(4) || protect(op_profile, &o, 0) || o.r < 0) return;
  touch(sbuf, o.r);
}
static void n_run(const char *name, const char *code, int len, int console) {
  int nl = to_sbuf(name, (int)strlen(name), 0), cl = nl < 0 ? -1 : to_sbuf(code, len, nl);
  if (cl < 0) return;
  Op o = {nl, cl, console, 0};
  if (!room(3) || protect(op_run, &o, 0)) return;
  if (o.r) touch(sbuf, hs_errlen());
}

/* ---------------------------------------------------------------- fake engine: handles (Handles.kt, fewer slots) */
#define SLOT_BITS 24
#define SLOT_MASK ((1 << SLOT_BITS) - 1)
#define GEN_MASK 0x7f
#define NSLOT 512
static unsigned char gens[NSLOT], live[NSLOT];
static int tableIds[NSLOT], freeSlots[NSLOT], freeCount, nextSlot = 1, nextTable = 1, fresh;
static void handles_reset(void) {
  memset(gens, 0, sizeof gens); memset(live, 0, sizeof live); memset(tableIds, 0, sizeof tableIds);
  freeCount = 0; nextSlot = 1; nextTable = 1; fresh = 0;
}
static int is_live(int h) {
  int s = h & SLOT_MASK;
  return s > 0 && s < nextSlot && live[s] && gens[s] == (unsigned)(h >> SLOT_BITS);
}
/* A new handle; 0 with a failure on the engine call in progress when the slots really ran out. */
static int expose_new(void) {
  if (++fresh >= 16) { fresh = 0; n_gc_step(1); }
  if (freeCount == 0 && nextSlot >= NSLOT) {
    n_full_gc();
    if (freeCount == 0) { fail_now("too many engine objects held by Lua scripts"); return 0; }
  }
  int s = freeCount > 0 ? freeSlots[--freeCount] : nextSlot++;
  int g = (gens[s] + 1) & GEN_MASK;
  if (g == 0) g = 1;
  gens[s] = (unsigned char)g; live[s] = 1; tableIds[s] = 0;
  return (g << SLOT_BITS) | s;
}
static int any_live(void) {
  if (nextSlot <= 1) return 0;
  int s = 1 + rnd() % (unsigned)(nextSlot - 1);
  return live[s] ? (gens[s] << SLOT_BITS) | s : 0;
}
void js_release(int h) {
  int s = h & SLOT_MASK;
  if (s == 0 || s >= nextSlot || !live[s] || gens[s] != (unsigned)(h >> SLOT_BITS)) return;
  live[s] = 0; gens[s] = (gens[s] + 1) & GEN_MASK;
  freeSlots[freeCount++] = s;
}
int js_tableid(int h) {
  int s = h & SLOT_MASK;
  if (!is_live(h)) return 0;
  if (!tableIds[s]) tableIds[s] = one_in(32) ? 0x7fff0000 + (int)(rnd() & 0xffff) : nextTable++;
  return tableIds[s];
}
/* Handles.invalidate: existing userdata go stale, the slot stays taken, the object's table is dropped. */
static void invalidate_one(void) {
  int h = any_live();
  if (!h) return;
  int s = h & SLOT_MASK, t = tableIds[s];
  live[s] = 0; gens[s] = (gens[s] + 1) & GEN_MASK;
  if (t) n_drop_table(t);
}

/* ---------------------------------------------------------------- fake engine: values */
static char scratch[400000];
/* text in scratch: error messages and string values, some long, some not UTF-8 */
static int odd_text(void) {
  static const char *const fixed[] = {"x", "stale or invalid object passed to a Hyperspace method", "SWIG_IndexError:vector index out of range",
    "SWIG_ValueError:", "%s%s%n%d %p %", "", "SWIG_", "player", "ON_TICK"};
  switch (rnd() % 8) {
    case 0: case 1: case 2: case 3: { const char *s = fixed[rnd() % (sizeof fixed / sizeof fixed[0])]; int n = (int)strlen(s); memcpy(scratch, s, n); return n; }
    case 4: case 5: { int n = rnd() % 64; for (int i = 0; i < n; i++) scratch[i] = (char)rnd(); return n; }
    case 6: { int n = 1000 + rnd() % 8000; memset(scratch, 'L', n); return n; }
    default: {
      int n = one_in(16) ? 300000 : 66000 + rnd() % 8000;
      memset(scratch, 0xC3, n);
      return n;
    }
  }
}
static double odd_number(void) {
  static const double fixed[] = {0, 1, -1, 0.5, 1e308, -1e308, 9007199254740992.0, 9223372036854775808.0, -9223372036854775808.0, 4294967296.0, 2147483648.0};
  switch (rnd() % 8) {
    case 0: return 0.0 / 0.0 * (one_in(2) ? 1 : -1);
    case 1: return (one_in(2) ? 1 : -1) * (1.0 / 0.0);
    case 2: case 3: return fixed[rnd() % (sizeof fixed / sizeof fixed[0])];
    case 4: case 5: return (double)(int32_t)rnd();
    default: return (double)(rnd() % 100);
  }
}
static int class_type(void) {
  for (int i = 0; i < 16; i++) { int t = 1 + rnd() % (unsigned)hs_ntypes; if (hs_types[t].isClass) return t; }
  return 1;
}
/* An object answer: type near the declared one, sometimes wrong; a live, new, stale or made-up handle. */
static void pick_object(int plausible, int *ph, int *pt) {
  int t = plausible > 0 ? plausible : class_type();
  if (one_in(rateWrong)) t = one_in(8) ? hs_ntypes + 1 + (int)(rnd() % 2000) : 1 + (int)(rnd() % (unsigned)hs_ntypes);
  int h = 0;
  switch (rnd() % 8) {
    case 0: case 1: h = any_live(); if (h) break; /* fall through */
    case 2: case 3: case 4: case 5: h = expose_new(); break;
    case 6: if (one_in(rateWrong)) { h = any_live(); if (h) h = ((h + (1 << SLOT_BITS)) & 0x7fffffff); break; } h = expose_new(); break;
    default: if (one_in(rateWrong)) { h = (int)(rnd() & (one_in(32) ? 0x7fffffffu : 0x7f00ffffu)); if (one_in(8)) h = 0; break; } h = expose_new(); break;
  }
  *ph = h; *pt = t;
}
static void push_odd_value(void) {
  switch (rnd() % 9) {
    case 0: n_pushnil(); break;
    case 1: case 2: n_pushnumber(odd_number()); break;
    case 3: n_pushinteger((double)(int32_t)rnd()); break;
    case 4: n_pushboolean(rnd() & 1); break;
    case 5: { int n = odd_text(); n_pushstring(scratch, n); break; }
    case 6: {
      /* The engine builds no tables today (LuaJs.newtable has no caller); a failed one leaves nil, which rawseti cannot take. */
      n_newtable();
      if (hs_type(-1) != LUA_TTABLE) break;
      n_pushnumber(odd_number()); n_rawseti(-2, 1); n_pushboolean(1); n_setfield(-2, "ok", 2);
      break;
    }
    default: { int h, t; pick_object(0, &h, &t); n_pushobj(h, t); break; }
  }
}
static int pick_event(int size, const CbList *lists) {
  if (one_in(8)) { static const int odd[] = {0, -1, NINT, NINT + 1, NREN, NREN + 1, 0x7fffffff, (int)0x80000000}; return odd[rnd() % 8]; }
  int start = 1 + rnd() % (unsigned)(size - 1);
  for (int i = 0; i < size - 1; i++) { int id = 1 + (start + i - 1) % (size - 1); if (lists[id].n) return id; }
  return start;
}
static void act_fire(void) {
  int id = pick_event(NINT, intList), nArg = rnd() % 8, nRet = rnd() % 5;
  for (int i = 0; i < nArg; i++) push_odd_value();
  int n = n_fire(id, nArg, nRet);
  hs_pop(nArg + n);
}
static void act_fire_chain(void) {
  int id = pick_event(NINT, intList), nArg = rnd() % 8, nRet = rnd() % (nArg + 1);
  if (nRet > 4) nRet = 4;
  for (int i = 0; i < nArg; i++) push_odd_value();
  n_fire_chain(id, nArg, nRet);
  hs_pop(nArg);
}
static void act_render(void) {
  int id = pick_event(NREN, renList), nArg = rnd() % 4;
  for (int i = 0; i < nArg; i++) push_odd_value();
  int i = n_render_pre(id, nArg);
  n_render_post(id, i < 0 ? -i : i, nArg);
  hs_pop(nArg);
}
static void act_game_event(void) {
  if (ngev > 0 && !one_in(8)) {
    GameEv *g = &gevs[rnd() % (unsigned)ngev];
    int n = (int)strlen(g->name); if (n > 8000) n = 8000;
    memcpy(scratch, g->name, n);
    n_game_event(scratch, n, g->loading);
  } else { int n = odd_text(); n_game_event(scratch, n, rnd() & 1); }
}
/* What the engine can do while it answers a call from Lua: collect (Handles.expose), fire events, destroy objects. */
static void engine_reenter(void) {
  if (!one_in(rateReenter) || depth >= MAX_DEPTH || budget <= 0) return;
  depth++; budget--;
  switch (rnd() % 8) {
    case 0: n_gc_step(1 + rnd() % 64); break;
    case 1: n_full_gc(); break;
    case 2: case 3: act_fire(); break;
    case 4: act_fire_chain(); break;
    case 5: act_render(); break;
    case 6: act_game_event(); break;
    default: invalidate_one(); break;
  }
  depth--;
}

/* ---------------------------------------------------------------- the js_* imports */
void js_log(int kind, const char *p, int len) { (void)kind; touch(p, len); }
int js_rand32(void) { return (int)rnd(); }
void js_srand32(unsigned int seed) { (void)seed; }
double js_now(void) { static double t; return t += 0.25; }
void js_registered(int kind, int id) { (void)kind; (void)id; }

static void getter_failure(HostCall *hc) { int n = odd_text(); fail(hc, scratch, n); }
double js_getn(int h, int attr) {
  (void)h; (void)attr;
  HostCall hc; enter(&hc);
  double v = 0;
  engine_reenter();
  if (one_in(rateErr)) getter_failure(&hc); else v = odd_number();
  if (leave(&hc)) { failure_raise(&hc); v = 0; }
  return v;
}
int js_gets(int h, int attr) {
  (void)h; (void)attr;
  HostCall hc; enter(&hc);
  int n = -1;
  engine_reenter();
  if (one_in(rateErr)) getter_failure(&hc);
  else if (!one_in(8)) n = odd_text();
  if (!leave(&hc) && n >= 0) {
    n = to_sbuf(scratch, n, 0);
    if (n < 0) fail(&hc, LIT("string too long"));
  }
  if (hc.err) { failure_raise(&hc); n = -1; }
  return n;
}
double js_geto(int h, int attr) {
  (void)h;
  HostCall hc; enter(&hc);
  double v = 0;
  engine_reenter();
  if (one_in(rateErr)) getter_failure(&hc);
  else if (!one_in(8)) {
    int oh, ot;
    pick_object(attr > 0 && attr <= hs_nattrs ? hs_attrs[attr].objType : 0, &oh, &ot);
    v = ot * 4294967296.0 + (uint32_t)oh;
  }
  if (leave(&hc)) { failure_raise(&hc); v = 0; }
  return v;
}
static int set_result(HostCall *hc, int failed, int textLen) {
  int n = 0;
  if (!leave(hc) && failed) {
    n = to_sbuf(scratch, textLen, 0);
    if (n < 0) fail(hc, LIT("error text too long"));
  }
  if (hc->err) return failure_to_sbuf(hc) + 1;
  return failed ? n + 1 : 0;
}
static int setter(void) {
  HostCall hc; enter(&hc);
  engine_reenter();
  int failed = one_in(rateErr), n = failed ? odd_text() : 0;
  return set_result(&hc, failed, n);
}
int js_setn(int h, int attr, double v) { (void)h; (void)attr; (void)v; return setter(); }
int js_sets(int h, int attr, int len) { (void)h; (void)attr; touch(sbuf, len); return setter(); }
int js_seto(int h, int attr, int h2, int t2) { (void)h; (void)attr; (void)h2; (void)t2; return setter(); }

/* The type a method's result plausibly has: a constructor or GetInstance gives its class, others the receiver's. */
static short *methodResult;
static int type_by_name(const char *p, int n) {
  for (int t = 1; t <= hs_ntypes; t++) if (hs_types[t].name && !strncmp(hs_types[t].name, p, n) && !hs_types[t].name[n]) return t;
  return 0;
}
static void build_method_results(void) {
  methodResult = calloc((size_t)hs_nmethods + 1, sizeof(short));
  for (int m = 1; m <= hs_nmethods; m++) {
    const char *l = hs_methods[m].label;
    if (!l) continue;
    const char *dot = strchr(l, '.'), *paren = strchr(l, '(');
    if (paren && !dot) methodResult[m] = (short)type_by_name(l, (int)(paren - l));
    else if (dot && strstr(dot, "GetInstance")) methodResult[m] = (short)type_by_name(l, (int)(dot - l));
  }
}
int js_call(int mid, int nargs) {
  HostCall hc; enter(&hc);
  int r = 0, errLen = -1, base = hs_gettop();
  /* the adapter reads its arguments */
  for (int i = 1; i <= nargs && i <= 8; i++) {
    switch (rnd() % 8) {
      case 0: (void)hs_type(i); break;
      case 1: sink += (unsigned)hs_tonumber(i) & 1; break;
      case 2: sink += (unsigned)hs_tointeger(i) & 1; break;
      case 3: sink += (unsigned)hs_toboolean(i); break;
      case 4: sink += (unsigned)(hs_isnumber(i) + hs_isinteger(i) + hs_isstring(i)); break;
      case 5: sink += (unsigned)n_toobjtype(i); break;
      case 6: n_tostring(i); break;
      default: { int h = n_tohandle(i); if (i == 1 && !is_live(h) && one_in(4)) { memcpy(scratch, LIT("stale or invalid object passed to a Hyperspace method")); errLen = 53; } break; }
    }
  }
  engine_reenter();
  if (errLen < 0 && one_in(rateErr)) errLen = odd_text();
  if (errLen >= 0) r = -1;
  else {
    int want = rnd() % 4 == 0 ? 0 : 1 + (one_in(4) ? rnd() % 4 : 0);
    if (one_in(64)) want = 39;
    if (one_in(256)) want = 60;
    int plausible = mid > 0 && mid <= hs_nmethods ? methodResult[mid] : 0;
    if (!plausible && nargs >= 1 && one_in(2)) plausible = n_toobjtype(1);
    for (int i = 0; i < want; i++) {
      if (rnd() % 2 == 0) { int h, t; pick_object(plausible, &h, &t); n_pushobj(h, t); }
      else if (one_in(8) && nargs >= 1) n_pushvalue(1 + rnd() % (unsigned)nargs);
      else push_odd_value();
      r++;
    }
    if (one_in(32) && nargs >= 1 && r > 0) { hs_replace(1 + rnd() % (unsigned)nargs); r--; }
    (void)base;
  }
  int n = 0, failedSoFar = leave(&hc);
  if (!failedSoFar && r < 0) {
    n = to_sbuf(scratch, errLen, 0);
    if (n < 0) fail(&hc, LIT("error text too long"));
  }
  if (hc.err) return -failure_to_sbuf(&hc) - 1;
  return r >= 0 ? r : -n - 1;
}

/* ---------------------------------------------------------------- top-level driver */
static const char PRELUDE[] =
  "H,G,D,X=Hyperspace,Graphics,Defines,RapidXML E,R,C=D.InternalEvents,D.RenderEvents,D.Chain\n"
  "V={0,1,-1,0.5,0/0,1/0,-1/0,2^53,2^63,math.mininteger,math.maxinteger,'','x',('y'):rep(300),'\\xff\\xfe\\0z',true,false,{},{{}},print,"
  "function() end,setmetatable({},{__index=function(t,k) return k end,__call=function(s,...) return ... end})}\n"
  "function U(k) local ok,o=pcall(k or H.Global.GetInstance) if ok and type(o)=='userdata' then return o end\n"
  " ok,o=pcall(H.Point,1,2) return o end\n";

static void act_any(void) {
  switch (rnd() % 16) {
    case 0: case 1: case 2: case 3: act_fire(); break;
    case 4: case 5: act_fire_chain(); break;
    case 6: case 7: act_render(); break;
    case 8: n_on_load(); break;
    case 9: n_on_init(rnd() & 1); break;
    case 10: act_game_event(); break;
    case 11: n_full_gc(); break;
    case 12: n_gc_step((int)(rnd() % 4096) - 8); break;
    case 13: n_drop_table(one_in(2) ? (int)(rnd() % 64) : (int)rnd()); break;
    case 14: n_profile(rnd() & 1); sink += (unsigned)hs_has((int)(rnd() % 6), (int)rnd()) + (unsigned)hs_has(0, (int)(rnd() % NINT)); break;
    default: { int h, t; pick_object(0, &h, &t); n_pushobj(h, t); sink += (unsigned)n_tohandle(-1) + (unsigned)n_toobjtype(-1); n_tostring(-1); hs_pop(1); break; }
  }
  if (hs_gettop() != 0) __builtin_trap();   /* the stack keeps the height each operation promised */
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
  static const int capsCb[] = {2000, 10000, 50000, 50000}, capsLoad[] = {100000, 500000, 2000000, 6000000};
  static const int rates[] = {0, 256, 32, 8};
  static const int reenter[] = {0, 64, 8, 2};
  static const int allocFail[] = {0, 0, 20000, 2000};
  static const size_t heaps[] = {HEAP_CAP, HEAP_CAP, 1 << 20, 1 << 22};
  uint8_t head[4] = {0, 0, 0, 0};
  if (size >= 4) { memcpy(head, data, 4); data += 4; size -= 4; }
  if (!methodResult) build_method_results();

  armed = 0; depth = 0; budget = REENTER_BUDGET; hostCall = 0;
  prng = (((uint64_t)head[2] << 8 | head[3]) + 1) * 0x9E3779B97F4A7C15ull | 1;
  rateErr = rates[head[0] >> 2 & 3]; rateWrong = rates[head[0] >> 4 & 3]; rateReenter = reenter[head[0] >> 6 & 3];
  rateAllocFail = allocFail[head[1] & 3];
  handles_reset();
  hs_set_caps(50000000, 500000000);
  heapCap = HEAP_CAP;
  init_state();
#if defined(HARDSTACKTESTS)
  hs_set_caps(capsCb[head[0] & 3] / 8, capsLoad[head[0] & 3] / 8);   /* the stack moves at every check: far slower */
#else
  hs_set_caps(capsCb[head[0] & 3], capsLoad[head[0] & 3]);
#endif
  hs_set_profiling(head[1] >> 5 & 1);
  /* Aggressive collector: a step, and so finalizers, about every 600 bytes allocated. */
  if (head[1] >> 4 & 1) lua_gc(L0, LUA_GCSETSTEPMUL, 1000);

  n_run("prelude", PRELUDE, sizeof PRELUDE - 1, 0);
  /* The heap cap counts from what the API took, so a low one hits inside the bridge's own allocations. */
  heapCap = heapUsed + heaps[head[1] >> 2 & 3];
  if (heapCap > HEAP_CAP) heapCap = HEAP_CAP;
  armed = 1;

  size_t pos = 0;
  for (int c = 0; c < MAX_CHUNKS && pos < size; c++) {
    const uint8_t *z = memchr(data + pos, 0, size - pos);
    size_t len = z ? (size_t)(z - (data + pos)) : size - pos;
    char name[16]; snprintf(name, sizeof name, "f%d.lua", c);
    if (len) n_run(name, (const char *)data + pos, (int)len, one_in(16));
    pos += len + 1;
    if (one_in(2)) act_any();
    if (hs_gettop() != 0) __builtin_trap();
  }
  int actions = 2 + rnd() % 14;
  for (int i = 0; i < actions; i++) act_any();

  armed = 0;
  reset_all();
  handles_reset();
  return 0;
}
