/*
 * JNI host of ftlman's Lua patch scripts on Android (libftlmanlua.so). The runtime is the browser's, unchanged
 * (hs-lua/ftlman.c, see hs-lua/Dockerfile); this file answers its imports with the static host methods of
 * ftl.android.app.mods.FtlmanLua, as web/mods/lua.mjs answers the wasm env imports, and exposes its exports.
 *
 * Mod scripts run natively here, outside the wasm sandbox, so this host adds what the sandbox gave the browser:
 *  - The runtime's allocations share one cap (wasm's address space caps them in the browser), also the plain malloc
 *    buffers its memory limit does not count.
 *  - A Lua error never crosses Java frames (Java never calls back into Lua), an unprotected one (the state is out of
 *    memory between two scripts) stops the runtime instead of aborting the process, and no Java exception reaches C.
 *  - A userdata a __gc handler resurrected after its own finalizer ran fails cleanly: its node pointers are gone
 *    (reading them is harmless in wasm, a crash here).
 *  - Closing the state stops every remaining finalizer at its first instruction: in the browser they never run.
 *  - The hash seed and math.random are the wasm build's (ftlman_lstate_jni.c, ftlman_conf.h): scripts see one order.
 * The state is process-global and all of it runs on the thread of one merge (FtlmanLua allows one runtime at a time).
 */
#include <jni.h>
#include <malloc.h>
#include <math.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

/* ---------------------------------------------------------------- the cap on everything the runtime allocates */
static size_t rawUsed, rawCap = (size_t)1 << 30;
static int raw_room(size_t n) { return rawUsed < rawCap && n <= rawCap - rawUsed; }
static void *raw_malloc(size_t n) {
  if (!raw_room(n)) return NULL;
  void *p = malloc(n);
  if (p) rawUsed += malloc_usable_size(p);
  return p;
}
static void *raw_realloc(void *o, size_t n) {
  size_t was = o ? malloc_usable_size(o) : 0;
  if (n > was && !raw_room(n - was)) return NULL;
  void *p = realloc(o, n);
  if (p) rawUsed = rawUsed - was + malloc_usable_size(p);
  return p;
}
static void raw_free(void *p) {
  if (p) { rawUsed -= malloc_usable_size(p); free(p); }
}

/* ---------------------------------------------------------------- the runtime, with its entry points guarded */
static void *live_check(lua_State *L, int i, const char *mt);
static void *live_test(lua_State *L, int i, const char *mt);
static int panic_jump(lua_State *L);
#define malloc raw_malloc
#define realloc raw_realloc
#define free raw_free
#define luaL_checkudata live_check
#define luaL_testudata live_test
#define lua_atpanic(L, f) lua_atpanic(L, panic_jump)
#include "ftlman.c"
#undef lua_atpanic
#undef luaL_testudata
#undef luaL_checkudata

static int collected(lua_State *L) { return fail(L, "attempt to use a value that was already collected"); }
static void *live_test(lua_State *L, int i, const char *mt) {
  UdNode *u = luaL_testudata(L, i, mt);
  return u && u->n ? u : NULL;   /* only MT_ELEMENT and MT_TEXT are tested */
}
static void *live_check(lua_State *L, int i, const char *mt) {
  void *u = luaL_checkudata(L, i, mt);
  if (!strcmp(mt, MT_ATTRS)) { if (!((UdAttrs *)u)->el) collected(L); }
  else if (!strcmp(mt, MT_KIDS) || !strcmp(mt, MT_NODES)) { UdKids *k = u; if (k->hold[0] != k->k.first) collected(L); }
  return u;
}
static jmp_buf panicJump;
static int panicArmed, panicked;
static int panic_jump(lua_State *L) {
  (void)L;
  if (panicArmed) _longjmp(panicJump, 1);
  return 0;
}

/* musl's rand (ftlman_conf.h), restarting with each runtime as a new wasm instance does. */
static uint64_t randSeed;
void ftlman_srand(unsigned s) { randSeed = s - 1; }
int ftlman_rand(void) { randSeed = 6364136223846793005ULL * randSeed + 1; return (int)(randSeed >> 33); }

/* ---------------------------------------------------------------- imports (C -> Java) */
static jclass hostClass;
static jmethodID mLog, mFs, mXmlParse, mXmlWrite, mXmlAppend;
static JNIEnv *curEnv;

void host_log(int kind, const char *p, int len) {
  JNIEnv *env = curEnv;
  jbyteArray a = len >= 0 ? (*env)->NewByteArray(env, len) : NULL;
  if (a) {
    (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *)p);
    (*env)->CallStaticVoidMethod(env, hostClass, mLog, kind, a);
    (*env)->DeleteLocalRef(env, a);
  }
  if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}
static int host_fail(int base, const char *msg) {
  size_t n = strlen(msg);
  memcpy(buf, msg, n);   /* the buffer has 64 KiB from mp_init on */
  return base - (int)n;
}
/* What the host returned: a length that fits the buffer, or an error whose message does. Anything else, or an
 * exception that got past the host's own catch, becomes an error. */
static int checked(JNIEnv *env, jint r, int base, int nothing) {
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return host_fail(base, "host call failed"); }
  if (r >= 0) return (size_t)r <= bufCap ? r : host_fail(base, "host result too long");
  if (base == -2 && r == -1) return nothing ? -1 : host_fail(base, "host result missing");
  return (uint64_t)((int64_t)base - r) <= bufCap ? r : host_fail(base, "host error too long");
}
int host_fs(int fs, int op, int pathLen, int dataLen) {
  JNIEnv *env = curEnv;
  return checked(env, (*env)->CallStaticIntMethod(env, hostClass, mFs, fs, op, pathLen, dataLen), -2, op == FS_STAT);
}
int host_xml_parse(int len, int topLevelText) {
  JNIEnv *env = curEnv;
  return checked(env, (*env)->CallStaticIntMethod(env, hostClass, mXmlParse, len, topLevelText), -1, 0);
}
int host_xml_write(int len) {
  JNIEnv *env = curEnv;
  return checked(env, (*env)->CallStaticIntMethod(env, hostClass, mXmlWrite, len), -1, 0);
}
int host_xml_append(int lowerLen, int upperLen, int kind) {
  JNIEnv *env = curEnv;
  return checked(env, (*env)->CallStaticIntMethod(env, hostClass, mXmlAppend, lowerLen, upperLen, kind), -1, 0);
}

/* ---------------------------------------------------------------- exports (Java -> C) */
#define FN(ret, name) JNIEXPORT ret JNICALL Java_ftl_android_app_mods_FtlmanLua_##name

/* Frees the state and everything else the runtime holds, so the next mp_init starts as a new wasm instance does. */
static void close_runtime(void) {
  if (L0 && !panicked) {
    killed = 1;
    lua_sethook(L0, count_hook, LUA_MASKCOUNT, 1);
    lua_close(L0);
  }
  L0 = NULL;
  for (Node *n = allNodes, *next; n; n = next) { next = n->allNext; node_free(n); }
  free(buf); buf = NULL; bufCap = 0; outLen = 0;
  free(printLine.p); printLine.p = NULL; printLine.n = printLine.cap = 0;
  free(currentFile); currentFile = NULL;
  memset(&runArgs, 0, sizeof runArgs);
  memUsed = memPeak = 0; memHit = 0; memLimit = (size_t)512 << 20;
  instrLimit = 1e9; instrUsed = 0; killed = 0; killText[0] = 0; lastInstructions = 0;
  allNodes = NULL; nodeCount = 0; fsToken = 0;
  randSeed = 0;
  if (panicked) rawUsed = 0;   /* what the stopped state holds stays allocated */
  panicked = 0;
}

FN(jint, nativeInit)(JNIEnv *env, jclass c, jdouble instructions, jdouble memory) {
  (void)c;
  curEnv = env;
  close_runtime();
  /* Below 1 GiB, buffer lengths and their sums stay in an int. */
  if (!(memory > 0 && memory <= 1 << 30)) memory = 512 << 20;
  rawCap = (size_t)memory * 2 + ((size_t)16 << 20);
  return mp_init(instructions, memory);
}
FN(void, nativeClose)(JNIEnv *env, jclass c) {
  (void)c;
  curEnv = env;
  if (!_setjmp(panicJump)) {
    panicArmed = 1;
    close_runtime();
  } else {
    panicked = 1;
    close_runtime();
  }
  panicArmed = 0;
  curEnv = NULL;
}
FN(jint, nativeRun)(JNIEnv *env, jclass c, jint codeLen, jint nameLen, jint pathLen, jint docLen, jint fsLen, jint flags) {
  (void)c;
  curEnv = env;
  if (!L0 || panicked) return put_error("the Lua runtime has stopped", 27);
  if (codeLen < 0 || nameLen < 0 || pathLen < -1 || docLen < 0 || fsLen < 0 ||
      (uint64_t)codeLen + nameLen + (pathLen > 0 ? pathLen : 0) + docLen + fsLen > bufCap) return put_error("bad arguments", 13);
  if (_setjmp(panicJump)) {
    panicArmed = 0; panicked = 1;
    return put_error("the Lua runtime ran out of memory between two scripts", 53);
  }
  panicArmed = 1;
  int r = mp_run(codeLen, nameLen, pathLen, docLen, fsLen, flags);
  panicArmed = 0;
  return r;
}
FN(jint, nativeBufCap)(JNIEnv *env, jclass c) { (void)env; (void)c; return (jint)bufCap; }
FN(jboolean, nativeBufGrow)(JNIEnv *env, jclass c, jint n) { (void)env; (void)c; return mp_buf_grow(n) != NULL; }
static int bad_range(JNIEnv *env, jint off, jint len, uint64_t cap) {
  if (off >= 0 && len >= 0 && (uint64_t)off + (uint64_t)len <= cap) return 0;
  (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException"), "patch buffer range");
  return 1;
}
FN(jbyteArray, nativeBufRead)(JNIEnv *env, jclass c, jint off, jint len) {
  (void)c;
  if (bad_range(env, off, len, bufCap)) return NULL;
  jbyteArray a = (*env)->NewByteArray(env, len);
  if (a) (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *)buf + off);
  return a;
}
FN(void, nativeBufWrite)(JNIEnv *env, jclass c, jint off, jbyteArray bytes, jint from, jint len) {
  (void)c;
  if (bad_range(env, off, len, bufCap) || bad_range(env, from, len, (uint64_t)(*env)->GetArrayLength(env, bytes))) return;
  (*env)->GetByteArrayRegion(env, bytes, from, len, (jbyte *)buf + off);
}
FN(jdouble, nativeInstructions)(JNIEnv *env, jclass c) { (void)env; (void)c; return mp_instructions(); }
FN(jdouble, nativeMemory)(JNIEnv *env, jclass c) { (void)env; (void)c; return mp_memory(); }
FN(jdouble, nativeMemoryPeak)(JNIEnv *env, jclass c) { (void)env; (void)c; return mp_memory_peak(); }
FN(jdouble, nativeNodes)(JNIEnv *env, jclass c) { (void)env; (void)c; return mp_nodes(); }

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = (*env)->FindClass(env, "ftl/android/app/mods/FtlmanLua");
  if (!cls) { (*env)->ExceptionClear(env); return JNI_ERR; }
  hostClass = (jclass)(*env)->NewGlobalRef(env, cls);
  (*env)->DeleteLocalRef(env, cls);
  mLog = (*env)->GetStaticMethodID(env, hostClass, "hostLog", "(I[B)V");
  mFs = (*env)->GetStaticMethodID(env, hostClass, "hostFs", "(IIII)I");
  mXmlParse = (*env)->GetStaticMethodID(env, hostClass, "hostXmlParse", "(II)I");
  mXmlWrite = (*env)->GetStaticMethodID(env, hostClass, "hostXmlWrite", "(I)I");
  mXmlAppend = (*env)->GetStaticMethodID(env, hostClass, "hostXmlAppend", "(III)I");
  if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return JNI_ERR; }
  return JNI_VERSION_1_6;
}
