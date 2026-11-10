/*
 * JNI host of the Hyperspace Lua runtime on Android (libhslua.so). The runtime is the browser's, unchanged
 * (hs-lua/hslua.c with Lua 5.3.6, see hs-lua/Dockerfile); this file answers its imports with the engine's static host
 * methods in xyz.znix.xftl.hs.lua.LuaJs, as web/hs-lua.mjs answers the wasm env imports, and implements LuaJs's
 * natives over the runtime's exports. Strings cross as UTF-8 byte arrays through the runtime's shared buffer (sbuf).
 *
 * Lua runs natively here, outside the wasm sandbox, so this host adds what the sandbox gave the browser:
 *  - No Lua error unwinds through Java frames. Every native that can raise (an allocation, a GC step that runs a
 *    script's __gc, a __newindex) runs under its own error boundary (luaD_pcall, in the caller's frame, so stack
 *    indices keep their meaning). Inside an engine call made from Lua the error is deferred and raised in Lua when
 *    that call returns; at the top level it is logged. The stack keeps the height the operation promised.
 *  - No Java exception reaches Lua: it is cleared, logged and becomes the failing engine call's Lua error.
 *  - The Lua heap is capped (wasm's address space caps it in the browser), which also keeps every string that can
 *    reach sbuf below the size its doubling growth can handle.
 *  - Natives that push check the Lua stack's room first.
 * All of it runs on the one thread that drives the engine.
 */
#include <jni.h>
#include <time.h>
#include <android/log.h>

/* The runtime itself, in this translation unit so the host reaches its state (L0) and buffer (sbuf). */
#include "hslua.c"
#include "ldo.h"   /* luaD_pcall, savestack */

#define TAG "hslua"
#define SBUF_MAX (1 << 30)               /* hs_sbuf_grow doubles an int capacity: stay well below 2^31 */
#define LUA_HEAP_CAP ((size_t)1 << 30)   /* bytes */

static JavaVM *jvm;
static jclass host;   /* xyz.znix.xftl.hs.lua.LuaJs (global ref) */
static jmethodID mLog, mRand32, mSrand32, mGetn, mGets, mGeto, mSetn, mSets, mSeto, mCall, mError, mRelease, mTableid,
    mRegistered, mThrowableText, mTzName;

/* ---------------------------------------------------------------- Lua heap cap */
static size_t heapUsed;
static void *capped_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
  (void)ud;
  size_t old = ptr ? osize : 0;   /* osize is a type tag when ptr is NULL */
  if (nsize == 0) { free(ptr); heapUsed -= old; return NULL; }
  if (nsize > old && nsize - old > LUA_HEAP_CAP - heapUsed) return NULL;   /* Lua collects, then raises "not enough memory" */
  void *p = realloc(ptr, nsize);
  if (p) heapUsed = heapUsed - old + nsize;
  return p;
}
/* hs_init and the switch to the capped allocator (blocks made before it are counted by Lua's own total) */
static void init_state(void) {
  hs_init();
  heapUsed = (size_t)lua_gc(L0, LUA_GCCOUNT, 0) * 1024 + (size_t)lua_gc(L0, LUA_GCCOUNTB, 0);
  lua_setallocf(L0, capped_alloc, NULL);
}
/* The wasm module runs hs_init when it is instantiated; here the first native call does. */
static inline void ready(void) { if (!L0) init_state(); }

/* ---------------------------------------------------------------- engine calls (C -> Java) */
typedef struct HostCall { struct HostCall *prev; char *err; int len; } HostCall;
static HostCall *hostCall;   /* innermost engine call in progress, NULL at the top level */

#define LIT(s) s, sizeof s - 1
static void fail(HostCall *hc, const char *s, size_t n) {   /* the first failure of an engine call wins */
  if (hc->err) return;
  if (n > 65536) n = 65536;
  char *p = malloc(n + 1);
  if (!p) return;
  memcpy(p, s, n); hc->err = p; hc->len = (int)n;
}
/* A pending Java exception: cleared and logged; its text becomes the engine call's failure (hc may be NULL). */
static int java_exception(JNIEnv *env, HostCall *hc) {
  if (!(*env)->ExceptionCheck(env)) return 0;
  jthrowable t = (*env)->ExceptionOccurred(env);
  (*env)->ExceptionClear(env);
  jstring s = t ? (jstring)(*env)->CallObjectMethod(env, t, mThrowableText) : NULL;
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); s = NULL; }
  const char *text = s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
  const char *msg = text ? text : "Java exception";
  __android_log_print(ANDROID_LOG_ERROR, TAG, "engine call failed: %s", msg);
  if (hc) fail(hc, msg, strlen(msg));
  if (text) (*env)->ReleaseStringUTFChars(env, s, text);
  if (s) (*env)->DeleteLocalRef(env, s);
  if (t) (*env)->DeleteLocalRef(env, t);
  return 1;
}
static JNIEnv *enter(HostCall *hc) {
  hc->prev = hostCall; hc->err = 0; hc->len = 0; hostCall = hc;
  JNIEnv *env = 0;
  if (!jvm || (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || !env) {
    fail(hc, LIT("no JNI environment on this thread"));
    return 0;
  }
  return env;
}
/* Ends the engine call; nonzero when it failed (Java exception or a deferred Lua error), text in hc->err. */
static int leave(JNIEnv *env, HostCall *hc) {
  hostCall = hc->prev;
  if (env) java_exception(env, hc);
  return hc->err != 0;
}
/* The failure text into sbuf; returns its length. */
static int failure_to_sbuf(HostCall *hc) {
  int n = hc->len;
  if (hs_sbuf_grow(n + 1)) memcpy(sbuf, hc->err, n); else n = 0;
  free(hc->err); hc->err = 0;
  return n;
}
/* A getter failed: raised as a Lua error when the getter returns (hslua.c's pendingErr), as LuaJs.raise does. */
static void failure_raise(HostCall *hc) { hs_raise(failure_to_sbuf(hc)); }
/* Calls that cannot report a failure to Lua (logging, GC, registration notices): logcat only. */
static void failure_log(HostCall *hc, const char *what) {
  __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: %.*s", what, hc->len, hc->err);
  free(hc->err); hc->err = 0;
}
static jbyteArray to_java(JNIEnv *env, const char *p, int n) {
  jbyteArray a = (*env)->NewByteArray(env, n);
  if (a && n > 0) (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *)p);
  return a;
}
/* byte[] into sbuf at offset off (room for a NUL after it); its length, or -1 when it does not fit */
static int from_java(JNIEnv *env, jbyteArray a, int off) {
  jsize n = (*env)->GetArrayLength(env, a);
  if ((long long)off + n + 1 > SBUF_MAX || !hs_sbuf_grow(off + n + 1)) return -1;
  (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)sbuf + off);
  return n;
}

void js_log(int kind, const char *p, int len) {
  HostCall hc; JNIEnv *env = enter(&hc);
  if (env) {
    jbyteArray a = to_java(env, p, len);
    if (a) { (*env)->CallStaticVoidMethod(env, host, mLog, (jint)kind, a); (*env)->DeleteLocalRef(env, a); }
  }
  if (leave(env, &hc)) failure_log(&hc, "log");
}
int js_rand32(void) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jint r = env ? (*env)->CallStaticIntMethod(env, host, mRand32) : 0;
  if (leave(env, &hc)) { failure_log(&hc, "rand32"); r = 0; }
  return r;
}
void js_srand32(unsigned int seed) {
  HostCall hc; JNIEnv *env = enter(&hc);
  if (env) (*env)->CallStaticVoidMethod(env, host, mSrand32, (jint)seed);
  if (leave(env, &hc)) failure_log(&hc, "srand32");
}
double js_now(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}
double js_epoch(void) {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}
/* os.date's zone (hs-lua/hslibs.c): the device's offset, and its name as Java has it (msvcrt prints a long name) */
int js_tzoffset(double t) {
  time_t tt = (time_t)t; struct tm tm;
  if (!localtime_r(&tt, &tm)) return 0;
  return (int)(tm.tm_gmtoff / 60);
}
int js_tzname(int isdst, char *buf, int cap) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jbyteArray a = env ? (jbyteArray)(*env)->CallStaticObjectMethod(env, host, mTzName, (jboolean)(isdst != 0)) : NULL;
  int n = 0;
  if (leave(env, &hc)) failure_log(&hc, "tzname");
  else if (a) { n = (*env)->GetArrayLength(env, a); if (n > cap) n = cap; (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)buf); }
  if (a) (*env)->DeleteLocalRef(env, a);
  return n;
}
double js_getn(int h, int attr) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jdouble v = env ? (*env)->CallStaticDoubleMethod(env, host, mGetn, (jint)h, (jint)attr) : 0;
  if (leave(env, &hc)) { failure_raise(&hc); v = 0; }
  return v;
}
int js_gets(int h, int attr) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jbyteArray a = env ? (jbyteArray)(*env)->CallStaticObjectMethod(env, host, mGets, (jint)h, (jint)attr) : NULL;
  int n = -1;
  if (!leave(env, &hc) && a) {
    n = from_java(env, a, 0);
    if (n < 0) fail(&hc, LIT("string too long"));
  }
  if (a) (*env)->DeleteLocalRef(env, a);
  if (hc.err) { failure_raise(&hc); n = -1; }
  return n;
}
double js_geto(int h, int attr) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jdouble v = env ? (*env)->CallStaticDoubleMethod(env, host, mGeto, (jint)h, (jint)attr) : 0;
  if (leave(env, &hc)) { failure_raise(&hc); v = 0; }
  return v;
}
/* Setter result: 0, or 1 + length of the error text in sbuf. */
static int set_result(JNIEnv *env, HostCall *hc, jbyteArray r) {
  int n = 0;
  if (!leave(env, hc) && r) {
    n = from_java(env, r, 0);
    if (n < 0) fail(hc, LIT("error text too long"));
  }
  if (r) (*env)->DeleteLocalRef(env, r);
  if (hc->err) return failure_to_sbuf(hc) + 1;
  return r ? n + 1 : 0;
}
int js_setn(int h, int attr, double v) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jbyteArray r = env ? (jbyteArray)(*env)->CallStaticObjectMethod(env, host, mSetn, (jint)h, (jint)attr, (jdouble)v) : NULL;
  return set_result(env, &hc, r);
}
int js_sets(int h, int attr, int len) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jbyteArray r = NULL;
  if (env) {
    jbyteArray v = to_java(env, sbuf, len);   /* copied before Java can reuse sbuf */
    if (v) {
      r = (jbyteArray)(*env)->CallStaticObjectMethod(env, host, mSets, (jint)h, (jint)attr, v);
      (*env)->DeleteLocalRef(env, v);
    }
  }
  return set_result(env, &hc, r);
}
int js_seto(int h, int attr, int h2, int t2) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jbyteArray r = env ? (jbyteArray)(*env)->CallStaticObjectMethod(env, host, mSeto, (jint)h, (jint)attr, (jint)h2, (jint)t2) : NULL;
  return set_result(env, &hc, r);
}
/* >= 0 results pushed, or -(length + 1) with the error text in sbuf */
int js_call(int mid, int nargs) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jint r = -1;
  jbyteArray e = NULL;
  if (env) {
    r = (*env)->CallStaticIntMethod(env, host, mCall, (jint)mid, (jint)nargs);
    if (r < 0 && !(*env)->ExceptionCheck(env)) e = (jbyteArray)(*env)->CallStaticObjectMethod(env, host, mError);
  }
  int n = 0;
  if (!leave(env, &hc) && r < 0 && e) {
    n = from_java(env, e, 0);
    if (n < 0) fail(&hc, LIT("error text too long"));
  }
  if (e) (*env)->DeleteLocalRef(env, e);
  if (hc.err) return -failure_to_sbuf(&hc) - 1;
  return r >= 0 ? r : -n - 1;
}
void js_release(int h) {
  HostCall hc; JNIEnv *env = enter(&hc);
  if (env) (*env)->CallStaticVoidMethod(env, host, mRelease, (jint)h);
  if (leave(env, &hc)) failure_log(&hc, "release");
}
int js_tableid(int h) {
  HostCall hc; JNIEnv *env = enter(&hc);
  jint r = env ? (*env)->CallStaticIntMethod(env, host, mTableid, (jint)h) : 0;
  if (leave(env, &hc)) { failure_log(&hc, "tableid"); r = 0; }
  return r;
}
void js_registered(int kind, int id) {
  HostCall hc; JNIEnv *env = enter(&hc);
  if (env) (*env)->CallStaticVoidMethod(env, host, mRegistered, (jint)kind, (jint)id);
  if (leave(env, &hc)) failure_log(&hc, "registered");
}

/* ---------------------------------------------------------------- protected runtime calls (Java -> C) */
typedef struct { int a, b, c, r; } Op;

/* A Lua error that no Lua frame between here and Java can catch: deferred to the engine call in progress (raised in
   Lua when it returns) or, at the top level, logged like the runtime's other errors. Error object on the top. */
static void defer_error(lua_State *L, const char *why) {
  size_t n = 0;
  const char *m = lua_type(L, -1) == LUA_TSTRING ? lua_tolstring(L, -1, &n) : NULL;
  if (!m) { m = why; n = strlen(why); }
  if (hostCall) fail(hostCall, m, n);
  else logf_(2, "Lua error outside a callback: %.*s\n", (int)n, m);
}
/* Runs f(L0, o) in the caller's frame under an error boundary. delta is the stack effect f promises; after an error
   the stack gets that height (a promised value is nil). Returns 0, or 1 after an error. */
static int protect(Pfunc f, Op *o, int delta) {
  lua_State *L = L0;
  int top = lua_gettop(L);
  if (luaD_pcall(L, f, o, savestack(L, L->top), 0) == LUA_OK) return 0;
  defer_error(L, "error object is not a string");
  if (delta > 0) { lua_settop(L, top); lua_pushnil(L); }
  else lua_settop(L, top + delta);
  return 1;
}
static void fail_now(const char *m) {   /* the runtime cannot take an argument (a string too long, no stack room) */
  if (hostCall) fail(hostCall, m, strlen(m));
  else logf_(2, "Lua error outside a callback: %s\n", m);
}
/* Room for n more stack slots: the engine pushes outside any Lua C function (lua_checkstack raises nothing). */
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

#define FN(ret, name) JNIEXPORT ret JNICALL Java_xyz_znix_xftl_hs_lua_LuaJs_##name

/* ---- callbacks */
FN(jint, fire)(JNIEnv *env, jclass c, jint id, jint nArg, jint nRet) {
  (void)env; (void)c; ready();
  Op o = {id, nArg, nRet, 0};
  if (!room(nArg + nRet + 2) || protect(op_fire, &o, 0)) return 0;
  return o.r;
}
FN(jboolean, fireChain)(JNIEnv *env, jclass c, jint id, jint nArg, jint nRet) {
  (void)env; (void)c; ready();
  Op o = {id, nArg, nRet, 0};
  if (!room(nArg + nRet + 3) || protect(op_fire_chain, &o, 0)) return JNI_FALSE;
  return o.r != 0;
}
FN(jint, renderPre)(JNIEnv *env, jclass c, jint id, jint nArg) {
  (void)env; (void)c; ready();
  Op o = {id, nArg, 0, 0};
  if (!room(nArg + 3) || protect(op_render_pre, &o, 0)) return 0;
  return o.r;
}
FN(void, renderPost)(JNIEnv *env, jclass c, jint id, jint idx, jint nArg) {
  (void)env; (void)c; ready();
  Op o = {id, idx, nArg, 0};
  if (room(nArg + 2)) protect(op_render_post, &o, 0);
}
FN(jint, has)(JNIEnv *env, jclass c, jint kind, jint id) { (void)env; (void)c; return hs_has(kind, id); }
FN(void, onLoad)(JNIEnv *env, jclass c) {
  (void)env; (void)c; ready();
  Op o = {0, 0, 0, 0};
  if (room(2)) protect(op_on_load, &o, 0);
}
FN(void, onInit)(JNIEnv *env, jclass c, jboolean newGame) {
  (void)env; (void)c; ready();
  Op o = {newGame ? 1 : 0, 0, 0, 0};
  if (room(3)) protect(op_on_init, &o, 0);
}
FN(void, setCaps)(JNIEnv *env, jclass c, jint perCallback, jint perScript) { (void)env; (void)c; hs_set_caps(perCallback, perScript); }
FN(void, setProfiling)(JNIEnv *env, jclass c, jboolean on) { (void)env; (void)c; hs_set_profiling(on ? 1 : 0); }
FN(void, dropTable)(JNIEnv *env, jclass c, jint tid) {
  (void)env; (void)c; ready();
  Op o = {tid, 0, 0, 0};
  if (room(3)) protect(op_drop_table, &o, 0);
}
FN(jint, gcKiB)(JNIEnv *env, jclass c) { (void)env; (void)c; ready(); return hs_gc_count(); }
/* Finalizers run in here, and a script's __gc can raise. */
FN(void, fullGc)(JNIEnv *env, jclass c) {
  (void)env; (void)c; ready();
  Op o = {0, 0, 0, 0};
  if (room(3)) protect(op_full_gc, &o, 0);
}
FN(void, gcStep)(JNIEnv *env, jclass c, jint kb) {
  (void)env; (void)c; ready();
  Op o = {kb, 0, 0, 0};
  if (room(3)) protect(op_gc_step, &o, 0);
}

/* ---- stack */
FN(jint, gettop)(JNIEnv *env, jclass c) { (void)env; (void)c; ready(); return hs_gettop(); }
FN(void, settop)(JNIEnv *env, jclass c, jint i) {
  (void)env; (void)c; ready();
  if (i > 0 && i > hs_gettop() && !room(i - hs_gettop())) return;
  hs_settop(i);
}
FN(void, pop)(JNIEnv *env, jclass c, jint n) { (void)env; (void)c; ready(); hs_pop(n); }
FN(jint, type)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_type(i); }
FN(jboolean, isnumber)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_isnumber(i) != 0; }
FN(jboolean, isinteger)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_isinteger(i) != 0; }
FN(jboolean, isstring)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_isstring(i) != 0; }
FN(jdouble, tonumber)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_tonumber(i); }
FN(jdouble, tointeger)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_tointeger(i); }
FN(jboolean, toboolean)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); return hs_toboolean(i) != 0; }
FN(jint, tohandle)(JNIEnv *env, jclass c, jint i) {
  (void)env; (void)c; ready();
  Op o = {i, 0, 0, 0};
  if (!room(3) || protect(op_tohandle, &o, 0)) return 0;
  return o.r;
}
FN(jint, toobjtype)(JNIEnv *env, jclass c, jint i) {
  (void)env; (void)c; ready();
  Op o = {i, 0, 0, 0};
  if (!room(3) || protect(op_toobjtype, &o, 0)) return 0;
  return o.r;
}
FN(void, pushnil)(JNIEnv *env, jclass c) { (void)env; (void)c; ready(); if (room(1)) hs_pushnil(); }
FN(void, pushnumber)(JNIEnv *env, jclass c, jdouble v) { (void)env; (void)c; ready(); if (room(1)) hs_pushnumber(v); }
FN(void, pushinteger)(JNIEnv *env, jclass c, jdouble v) { (void)env; (void)c; ready(); if (room(1)) hs_pushinteger(v); }
FN(void, pushboolean)(JNIEnv *env, jclass c, jboolean b) { (void)env; (void)c; ready(); if (room(1)) hs_pushboolean(b ? 1 : 0); }
FN(void, pushobj)(JNIEnv *env, jclass c, jint handle, jint t) {
  (void)env; (void)c; ready();
  Op o = {handle, t, 0, 0};
  if (room(5)) protect(op_pushobj, &o, 1);
}
FN(void, pushvalue)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); if (room(1)) hs_pushvalue(i); }
FN(void, replace)(JNIEnv *env, jclass c, jint i) { (void)env; (void)c; ready(); hs_replace(i); }
FN(void, newtable)(JNIEnv *env, jclass c) {
  (void)env; (void)c; ready();
  Op o = {0, 0, 0, 0};
  if (room(1)) protect(op_newtable, &o, 1);
}
FN(void, rawseti)(JNIEnv *env, jclass c, jint t, jint i) {
  (void)env; (void)c; ready();
  Op o = {t, i, 0, 0};
  protect(op_rawseti, &o, -1);
}

/* ---- private natives behind LuaJs's String methods */
FN(void, nInit)(JNIEnv *env, jclass c) { (void)env; (void)c; init_state(); }

/* ---- the private folder, "Disable Lua sandboxing" and os.exit (hs-lua/hslibs.c; no Lua code runs in these) */
int hs_vfs_mount_dir(const char *dir);
int hs_vfs_sync_dir(void);
double hs_vfs_used(void);
void hs_libs_set_real(int on, const char *root);
void hs_libs_clear_sources(void);
void hs_libs_allow_source(const char *s);
static char *c_string(JNIEnv *env, jbyteArray a) {   /* NULL for null or on failure */
  if (!a) return NULL;
  jsize n = (*env)->GetArrayLength(env, a);
  char *s = malloc((size_t)n + 1);
  if (!s) return NULL;
  (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)s);
  s[n] = 0;
  if (strlen(s) != (size_t)n) { free(s); return NULL; }   /* an embedded NUL */
  return s;
}
FN(jint, nVfsMount)(JNIEnv *env, jclass c, jbyteArray dir) {
  (void)c; char *d = c_string(env, dir);
  int n = d ? hs_vfs_mount_dir(d) : 0;
  free(d); return n;
}
FN(jint, nVfsSync)(JNIEnv *env, jclass c) { (void)env; (void)c; return hs_vfs_sync_dir(); }
FN(jdouble, nVfsUsed)(JNIEnv *env, jclass c) { (void)env; (void)c; return hs_vfs_used(); }
FN(void, nLibsReal)(JNIEnv *env, jclass c, jboolean on, jbyteArray root) {
  (void)c; char *r = c_string(env, root);
  hs_libs_set_real(on && r != NULL, r);
  free(r);
}
FN(void, nLibsAllow)(JNIEnv *env, jclass c, jbyteArray source) {
  (void)c;
  if (!source) { hs_libs_clear_sources(); return; }
  char *s = c_string(env, source);
  if (s) hs_libs_allow_source(s);
  free(s);
}
FN(jboolean, nExitRequested)(JNIEnv *env, jclass c) { (void)env; (void)c; return hs_exit_state() ? JNI_TRUE : JNI_FALSE; }
FN(jbyteArray, nExitSource)(JNIEnv *env, jclass c) { (void)c; int n = hs_exit_source(); return to_java(env, sbuf, n); }
FN(jbyteArray, nRun)(JNIEnv *env, jclass c, jbyteArray name, jbyteArray code, jboolean console) {
  (void)c; ready();
  int nl = from_java(env, name, 0), cl = nl < 0 ? -1 : from_java(env, code, nl);
  if (cl < 0) return to_java(env, LIT("chunk too large"));
  Op o = {nl, cl, console ? 1 : 0, 0};   /* hs_run reads name then code from sbuf; code ends at its first NUL, as HS */
  if (!room(3)) return to_java(env, LIT("Lua stack overflow"));
  if (protect(op_run, &o, 0)) return to_java(env, LIT("Lua error outside the chunk's protection"));
  return o.r ? to_java(env, sbuf, hs_errlen()) : NULL;
}
FN(void, nGameEvent)(JNIEnv *env, jclass c, jbyteArray name, jboolean loading) {
  (void)c; ready();
  int n = from_java(env, name, 0);
  if (n < 0) { fail_now("event name too long"); return; }
  Op o = {n, loading ? 1 : 0, 0, 0};
  if (room(2)) protect(op_game_event, &o, 0);
}
FN(jbyteArray, nProfile)(JNIEnv *env, jclass c, jboolean reset) {
  (void)c; ready();
  Op o = {reset ? 1 : 0, 0, 0, 0};
  if (!room(4) || protect(op_profile, &o, 0) || o.r < 0) return NULL;
  return to_java(env, sbuf, o.r);
}
FN(void, nRaise)(JNIEnv *env, jclass c, jbyteArray message) {
  (void)c; ready();
  int n = from_java(env, message, 0);
  if (n < 0) { static const char m[] = "error text too long"; memcpy(sbuf, m, sizeof m - 1); n = sizeof m - 1; }   /* sbuf holds >= 64 KiB */
  hs_raise(n);
}
FN(jbyteArray, nTostring)(JNIEnv *env, jclass c, jint i) {
  (void)c; ready();
  Op o = {i, 0, 0, 0};
  if (protect(op_tostring, &o, 0) || o.r < 0) return NULL;
  return to_java(env, sbuf, o.r);
}
FN(void, nPushstring)(JNIEnv *env, jclass c, jbyteArray s) {
  (void)c; ready();
  if (!room(1)) return;
  int n = from_java(env, s, 0);
  if (n < 0) { fail_now("string too long"); hs_pushnil(); return; }
  Op o = {n, 0, 0, 0};
  protect(op_pushstring, &o, 1);
}
FN(void, nSetfield)(JNIEnv *env, jclass c, jint t, jbyteArray key) {
  (void)c; ready();
  int n = from_java(env, key, 0);
  if (n < 0) { fail_now("field name too long"); hs_pop(1); return; }
  Op o = {t, n, 0, 0};
  if (!room(2)) { hs_pop(1); return; }
  protect(op_setfield, &o, -1);
}

/* ---------------------------------------------------------------- loading */
static jmethodID static_method(JNIEnv *env, const char *name, const char *sig) {
  jmethodID m = (*env)->GetStaticMethodID(env, host, name, sig);
  if (!m) { (*env)->ExceptionClear(env); __android_log_print(ANDROID_LOG_ERROR, TAG, "LuaJs.%s%s missing", name, sig); }
  return m;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass c = (*env)->FindClass(env, "xyz/znix/xftl/hs/lua/LuaJs");
  jclass t = (*env)->FindClass(env, "java/lang/Throwable");
  if (!c || !t) { (*env)->ExceptionClear(env); return JNI_ERR; }
  host = (jclass)(*env)->NewGlobalRef(env, c);
  mThrowableText = (*env)->GetMethodID(env, t, "toString", "()Ljava/lang/String;");
  (*env)->DeleteLocalRef(env, c); (*env)->DeleteLocalRef(env, t);
  mLog = static_method(env, "hostLog", "(I[B)V");
  mRand32 = static_method(env, "hostRand32", "()I");
  mSrand32 = static_method(env, "hostSrand32", "(I)V");
  mGetn = static_method(env, "hostGetn", "(II)D");
  mGets = static_method(env, "hostGets", "(II)[B");
  mGeto = static_method(env, "hostGeto", "(II)D");
  mSetn = static_method(env, "hostSetn", "(IID)[B");
  mSets = static_method(env, "hostSets", "(II[B)[B");
  mSeto = static_method(env, "hostSeto", "(IIII)[B");
  mCall = static_method(env, "hostCall", "(II)I");
  mError = static_method(env, "hostError", "()[B");
  mRelease = static_method(env, "hostRelease", "(I)V");
  mTableid = static_method(env, "hostTableid", "(I)I");
  mRegistered = static_method(env, "hostRegistered", "(II)V");
  mTzName = static_method(env, "hostTzName", "(Z)[B");
  if (!host || !mThrowableText || !mLog || !mRand32 || !mSrand32 || !mGetn || !mGets || !mGeto || !mSetn || !mSets ||
      !mSeto || !mCall || !mError || !mRelease || !mTableid || !mRegistered || !mTzName) {
    (*env)->ExceptionClear(env);
    return JNI_ERR;   /* System.loadLibrary throws; LuaJs.available() is false */
  }
  jvm = vm;
  return JNI_VERSION_1_6;
}
