/*
 * io, os, debug and package/require for mod scripts (hslibs.h). The original Hyperspace 1.23.2 gives scripts every
 * Lua 5.3 library on the player's machine (oracle session ps-m1b-libs, probe tools/hs-oracle/tracer/data/hs_oracle/ps-m1b.lua):
 * files in the game's folder, a shell, the environment. Here the same functions run on a private file system:
 *
 *  - Files: one folder per account on this device, shared by every mod like the original's game folder, kept by the
 *    host (IndexedDB in the browser, the app's private storage on Android) and capped at VFS_CAP bytes; a write past
 *    the cap fails as on a full disk ("No space left on device"). Names follow Windows: case-insensitive, '/' and '\'
 *    both separate, trailing dots and spaces dropped, <>:"|?* invalid. The folder has no subfolders, so a path with one
 *    is "No such file or directory", as natively for a folder that is not there. Absolute paths and '..' above the
 *    folder are "Permission denied". Open files cannot be removed or renamed (Windows, oracle rows remove-open,
 *    rename-open); rename onto an existing name is "File exists" (Windows). No CRLF translation (see docs/HS_LUA.md).
 *  - Programs: no shell, as Lua built for a system without one: os.execute() is false, os.execute(cmd) fails with
 *    errno ENOENT, io.popen raises "'popen' not supported". os.getenv is nil. os.tmpname returns a name of the shape the
 *    original's msvcrt tmpnam gives ("\sXX.", in the root of the drive), which cannot be opened here either.
 *  - os.date/os.time as the original's Windows build: msvcrt's conversions (%c is "%m/%d/%y %H:%M:%S", the '#' flag),
 *    the Windows option list, 32-bit time_t (1970 to 2038).
 *  - package: Windows paths and separators with the game folder ('!') as '.', text chunks only (a compiled chunk could
 *    break out of the runtime on Android), no C libraries.
 *  - debug: Lua's library, minus what would let a script reach the runtime's own state: getregistry returns a copy of
 *    its named entries, userdata metatables cannot change, C functions show no upvalues and their locals cannot be set.
 *    sethook/gethook are hslua.c's, chained with the instruction cap.
 * With "Disable Lua sandboxing" (Android only), scripts of mods the player approved get real files under the app's own
 * storage and /system/bin/sh (hs_libs_set_real); everything else stays here.
 * Lua 5.3.6's loslib.c (os.date, os.time) is the base of the date code below. Copyright (C) 1994-2018 Lua.org, PUC-Rio.
 * MIT license, see third_party/notices/Lua-LICENSE.txt.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include "hslibs.h"
#if !defined(__wasm__)
#include <fcntl.h>
#include <dirent.h>
#include <unistd.h>
#include <sys/stat.h>
#endif

#define EXPORT(n) __attribute__((export_name(#n)))
#define IMPORT(n) __attribute__((import_module("env"), import_name(#n)))
IMPORT(log) extern void js_log(int kind, const char *p, int len);
IMPORT(now) extern double js_now(void);
IMPORT(epoch) extern double js_epoch(void);
IMPORT(tzoffset) extern int js_tzoffset(double t);                      /* minutes east of UTC at t (seconds) */
IMPORT(tzname) extern int js_tzname(int isdst, char *buf, int cap);     /* the local zone's name; its length */
extern char *hs_sbuf(void);

/* errno as msvcrt numbers it (oracle rows: 2, 13, 17, 22, 28), on every build: wasm's C library numbers them as WASI does.
 * Lua reads errno through luaL_fileresult; hs-lauxlib.c and hs-liolib.c print it with hs_strerror. */
#define W_ENOENT 2
#define W_ENOMEM 12
#define W_EACCES 13
#define W_EEXIST 17
#define W_EINVAL 22
#define W_ENOSPC 28
char *hs_strerror(int e) {
  switch (e) {
    case W_ENOENT: return "No such file or directory";
    case W_ENOMEM: return "Not enough space";
    case W_EACCES: return "Permission denied";
    case W_EEXIST: return "File exists";
    case W_EINVAL: return "Invalid argument";
    case W_ENOSPC: return "No space left on device";
    default: return strerror(e);
  }
}

/* ================================================================ private file system */
#define VFS_CAP ((size_t)50 << 20)
#define VFS_MAX_FILES 10000
#define VFS_NAME_MAX 255
typedef struct VNode {
  char *name, *key;               /* as created (case kept); lower case */
  unsigned char *data; size_t len, cap;
  int open, dirty, tmp, gone;     /* open handles; to persist; io.tmpfile; removed (freed at the last close) */
} VNode;
static VNode **nodes; static int nnodes, capnodes;
static size_t used;
static char **gone; static int ngone, capgone;   /* names removed since the last sync */

static void key_of(const char *s, char *k) { for (; *s; s++, k++) *k = (*s >= 'A' && *s <= 'Z') ? (char)(*s + 32) : *s; *k = 0; }
/* Windows path rules for the folder (no subfolders): 0 and the file name in out, or an errno; *root for the folder */
static int vfs_norm(const char *p, char *out, int *root) {
  *root = 0;
  if (!p || !*p) return W_ENOENT;
  if (p[0] == '/' || p[0] == '\\') return W_EACCES;
  if (((p[0] >= 'a' && p[0] <= 'z') || (p[0] >= 'A' && p[0] <= 'Z')) && p[1] == ':') return W_EACCES;
  const char *comp[64]; int clen[64], n = 0;
  while (*p) {
    while (*p == '/' || *p == '\\') p++;
    if (!*p) break;
    const char *s = p;
    while (*p && *p != '/' && *p != '\\') {
      unsigned char c = (unsigned char)*p;
      if (c < 32 || strchr("<>:\"|?*", c)) return W_EINVAL;
      p++;
    }
    int len = (int)(p - s);
    if (len == 1 && s[0] == '.') continue;
    if (len == 2 && s[0] == '.' && s[1] == '.') { if (n == 0) return W_EACCES; n--; continue; }
    while (len > 0 && (s[len - 1] == '.' || s[len - 1] == ' ')) len--;   /* Windows drops trailing dots and spaces */
    if (len == 0) continue;
    if (len > VFS_NAME_MAX) return W_ENOENT;
    if (n == 64) return W_ENOENT;
    comp[n] = s; clen[n] = len; n++;
  }
  if (n == 0) { *root = 1; return 0; }
  if (n > 1) return W_ENOENT;   /* a subfolder, and the folder has none */
  memcpy(out, comp[0], (size_t)clen[0]); out[clen[0]] = 0;
  return 0;
}
static VNode *vfs_find(const char *name) {
  char k[VFS_NAME_MAX + 1]; key_of(name, k);
  for (int i = 0; i < nnodes; i++) if (!strcmp(nodes[i]->key, k)) return nodes[i];
  return NULL;
}
static void gone_drop(const char *key) {
  for (int i = 0; i < ngone; i++) {
    char k[VFS_NAME_MAX + 1]; key_of(gone[i], k);
    if (!strcmp(k, key)) { free(gone[i]); gone[i] = gone[--ngone]; i--; }
  }
}
static void gone_add(const char *name) {
  if (ngone == capgone) { int c = capgone ? capgone * 2 : 16; char **g = realloc(gone, sizeof(char *) * c); if (!g) return; gone = g; capgone = c; }
  gone[ngone] = strdup(name); if (gone[ngone]) ngone++;
}
static VNode *vfs_new(const char *name, int tmp) {
  if (!tmp && nnodes >= VFS_MAX_FILES) { errno = W_ENOSPC; return NULL; }
  VNode *n = calloc(1, sizeof *n);
  if (!n) { errno = W_ENOSPC; return NULL; }
  n->tmp = tmp; n->gone = tmp;
  n->name = strdup(name); n->key = strdup(name);
  if (!n->name || !n->key) { free(n->name); free(n->key); free(n); errno = W_ENOSPC; return NULL; }
  key_of(name, n->key);
  if (!tmp) {
    if (nnodes == capnodes) {
      int c = capnodes ? capnodes * 2 : 32; VNode **a = realloc(nodes, sizeof(VNode *) * c);
      if (!a) { free(n->name); free(n->key); free(n); errno = W_ENOSPC; return NULL; }
      nodes = a; capnodes = c;
    }
    nodes[nnodes++] = n; n->dirty = 1; gone_drop(n->key);
  }
  return n;
}
static void vfs_unlist(VNode *n) {
  for (int i = 0; i < nnodes; i++) if (nodes[i] == n) { nodes[i] = nodes[--nnodes]; break; }
}
static void vfs_free(VNode *n) { used -= n->len; free(n->data); free(n->name); free(n->key); free(n); }
static int vfs_resize(VNode *n, size_t len) {   /* 0, or W_ENOSPC */
  if (len > n->len && len - n->len > VFS_CAP - used) return W_ENOSPC;
  if (len > n->cap) {
    size_t c = n->cap ? n->cap : 256; while (c < len) c = c * 2 < c ? len : c * 2;
    unsigned char *d = realloc(n->data, c);
    if (!d) return W_ENOSPC;
    n->data = d; n->cap = c;
  }
  if (len > n->len) memset(n->data + n->len, 0, len - n->len);
  used = used - n->len + len; n->len = len;
  return 0;
}

/* ---------------------------------------------------------------- the file streams */
typedef struct { VNode *n; long long pos; int rd, wr, app, std; } VFile;
static long long vf_read(VFile *f, char *buf, size_t size) {
  if (!f->rd || !f->n) return 0;                  /* a write-only file reads as at its end (msvcrt, oracle read-on-w) */
  VNode *n = f->n;
  if (f->pos >= (long long)n->len) return 0;
  size_t k = n->len - (size_t)f->pos; if (k > size) k = size;
  memcpy(buf, n->data + f->pos, k); f->pos += (long long)k;
  return (long long)k;
}
static void std_write(int which, const char *buf, size_t size);
static long long vf_write(VFile *f, const char *buf, size_t size) {
  if (f->std) { std_write(f->std, buf, size); return (long long)size; }
  if (!f->wr || !f->n) return (long long)size;   /* a read-only file takes writes and keeps nothing (msvcrt, oracle write-on-r) */
  VNode *n = f->n;
  if (f->app) f->pos = (long long)n->len;
  size_t end = (size_t)f->pos + size;
  if (end > n->len) { int e = vfs_resize(n, end); if (e) { errno = e; return -1; } }
  memcpy(n->data + f->pos, buf, size); f->pos = (long long)end;
  if (!n->tmp) n->dirty = 1;
  return (long long)size;
}
static int vf_seek(VFile *f, long long *off, int whence) {
  long long base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? f->pos : (f->n ? (long long)f->n->len : 0);
  long long p = base + *off;
  if (p < 0) { errno = W_EINVAL; return -1; }
  f->pos = p; *off = p;
  return 0;
}
static int vf_close(VFile *f) {
  VNode *n = f->n;
  if (n && --n->open == 0 && n->gone) vfs_free(n);
  free(f);
  return 0;
}
#if defined(__ANDROID__)
static int c_read(void *c, char *b, int n) { return (int)vf_read(c, b, (size_t)n); }
static int c_write(void *c, const char *b, int n) { return (int)vf_write(c, b, (size_t)n); }
static fpos64_t c_seek(void *c, fpos64_t o, int w) { long long x = o; return vf_seek(c, &x, w) ? -1 : (fpos64_t)x; }
static int c_close(void *c) { return vf_close(c); }
static FILE *vf_stream(VFile *f) { return funopen64(f, c_read, c_write, c_seek, c_close); }
#else
static ssize_t c_read(void *c, char *b, size_t n) { return (ssize_t)vf_read(c, b, n); }
static ssize_t c_write(void *c, const char *b, size_t n) { return (ssize_t)vf_write(c, b, n); }
static int c_seek(void *c, off64_t *o, int w) { long long x = *o; if (vf_seek(c, &x, w)) return -1; *o = (off64_t)x; return 0; }
static int c_close(void *c) { return vf_close(c); }
static FILE *vf_stream(VFile *f) {
  cookie_io_functions_t io = {c_read, c_write, c_seek, c_close};
  return fopencookie(f, "r+", io);
}
#endif
static FILE *vf_open(VNode *n, int rd, int wr, int app) {
  VFile *f = calloc(1, sizeof *f);
  if (!f) { errno = W_ENOMEM; return NULL; }
  f->n = n; f->rd = rd; f->wr = wr; f->app = app;
  if (n) n->open++;
  FILE *s = vf_stream(f);
  if (!s) { if (n) n->open--; free(f); errno = W_ENOMEM; return NULL; }
  return s;
}

/* stdin is empty; stdout and stderr go to the game's log line by line (natively a window program's stdout goes nowhere) */
static char stdLine[2][1024]; static int stdLen[2];
static void std_write(int which, const char *buf, size_t size) {
  int i = which - 1;
  for (size_t k = 0; k < size; k++) {
    char c = buf[k];
    if (c == '\n' || stdLen[i] == (int)sizeof stdLine[i]) { js_log(which == 1 ? 0 : 3, stdLine[i], stdLen[i]); stdLen[i] = 0; if (c == '\n') continue; }
    stdLine[i][stdLen[i]++] = c;
  }
}
static FILE *stdFiles[3];
FILE *hs_vstd(int which) {
  if (which < 0 || which > 2) which = 0;
  if (!stdFiles[which]) {
    VFile *f = calloc(1, sizeof *f);
    if (!f) return NULL;
    f->std = which; f->rd = which == 0;
    stdFiles[which] = vf_stream(f);
    if (which && stdFiles[which]) setvbuf(stdFiles[which], NULL, _IOLBF, 0);
  }
  return stdFiles[which];
}

/* ---------------------------------------------------------------- real files (Android, approved mods, setting on) */
static int libsReal;                            /* "Disable Lua sandboxing" is on */
static char realRoot[1024];                     /* the app's storage: relative paths and commands start there */
static char **realSrc; static int nrealSrc;     /* chunk names of the approved mods' scripts */
static int vfsReal;                             /* the next hs_fopen/hs_tmpfile is for an approved script */
const char *hs_caller_source(lua_State *L) {
  lua_Debug ar;
  for (int level = 0; lua_getstack(L, level, &ar); level++) {
    if (!lua_getinfo(L, "S", &ar)) break;
    if (ar.what && strcmp(ar.what, "C") != 0) return ar.source;
  }
  return NULL;
}
static int real_for(lua_State *L) {
#if defined(__wasm__)
  (void)L; return 0;
#else
  if (!libsReal || !nrealSrc) return 0;
  const char *s = hs_caller_source(L);
  if (!s) return 0;
  for (int i = 0; i < nrealSrc; i++) if (!strcmp(s, realSrc[i])) return 1;
  return 0;
#endif
}
#if !defined(__wasm__)
static const char *real_path(const char *p, char *buf, size_t cap) {
  if (p[0] == '/' || !realRoot[0]) return p;
  snprintf(buf, cap, "%s/%s", realRoot, p);
  return buf;
}
#endif

FILE *hs_fopen(const char *path, const char *mode) {
  int real = vfsReal; vfsReal = 0;
#if !defined(__wasm__)
  if (real) { char b[2048]; return fopen(real_path(path, b, sizeof b), mode); }
#else
  (void)real;
#endif
  char name[VFS_NAME_MAX + 1]; int root, e = vfs_norm(path, name, &root);
  if (e) { errno = e; return NULL; }
  if (root) { errno = W_EACCES; return NULL; }   /* the folder itself (Windows, oracle io.open-dir) */
  char m = mode[0]; int plus = strchr(mode, '+') != NULL;
  VNode *n = vfs_find(name);
  if (!n) {
    if (m == 'r') { errno = W_ENOENT; return NULL; }
    if (!(n = vfs_new(name, 0))) return NULL;
  } else if (m == 'w') { vfs_resize(n, 0); n->dirty = 1; }
  return vf_open(n, m == 'r' || plus, m != 'r' || plus, m == 'a');
}
FILE *hs_freopen(const char *path, const char *mode, FILE *f) {
  int real = vfsReal;   /* luaL_loadfilex reopens a compiled chunk in binary mode */
  if (f) fclose(f);
  vfsReal = real;
  return hs_fopen(path, mode);
}
FILE *hs_tmpfile(void) {
  int real = vfsReal; vfsReal = 0;
#if !defined(__wasm__)
  if (real) return tmpfile();
#else
  (void)real;
#endif
  VNode *n = vfs_new("", 1);
  if (!n) return NULL;
  FILE *s = vf_open(n, 1, 1, 0);
  if (!s) vfs_free(n);
  return s;
}
FILE *hs_popen(lua_State *L, const char *cmd, const char *mode) {
#if !defined(__wasm__)
  if (libsReal) {   /* reached only through io.popen's check (approved script) */
    char b[2048];
    if (realRoot[0]) { snprintf(b, sizeof b, "cd '%s' && %s", realRoot, cmd); cmd = b; }
    fflush(NULL);
    return popen(cmd, mode);
  }
#endif
  (void)cmd; (void)mode;
  luaL_error(L, "'popen' not supported");
  return NULL;
}
int hs_pclose(lua_State *L, FILE *f) {
  (void)L;
#if !defined(__wasm__)
  return pclose(f);
#else
  (void)f; return -1;
#endif
}

/* ---------------------------------------------------------------- persistence (the host keeps the folder) */
EXPORT(hs_vfs_reset) void hs_vfs_reset(void) {
  for (int i = 0; i < nnodes; i++) { VNode *n = nodes[i]; if (n->open) n->gone = 1; else vfs_free(n); }
  nnodes = 0;
  for (int i = 0; i < ngone; i++) free(gone[i]);
  ngone = 0;
}
static int vfs_put(const char *name, const unsigned char *data, size_t len) {   /* a stored file, at start */
  char n0[VFS_NAME_MAX + 1]; int root;
  if (vfs_norm(name, n0, &root) || root) return 0;
  VNode *n = vfs_find(n0);
  if (!n && !(n = vfs_new(n0, 0))) return 0;
  if (vfs_resize(n, 0) || vfs_resize(n, len)) { n->dirty = 0; return 0; }
  memcpy(n->data, data, len); n->dirty = 0;
  return 1;
}
/* sbuf: name (nameLen bytes) then the content */
EXPORT(hs_vfs_put) int hs_vfs_put(int nameLen, int dataLen) {
  char name[VFS_NAME_MAX + 1]; char *b = hs_sbuf();
  if (nameLen <= 0 || nameLen > VFS_NAME_MAX || dataLen < 0) return 0;
  memcpy(name, b, (size_t)nameLen); name[nameLen] = 0;
  return vfs_put(name, (unsigned char *)b + nameLen, (size_t)dataLen);
}
EXPORT(hs_vfs_used) double hs_vfs_used(void) { return (double)used; }
EXPORT(hs_vfs_cap) double hs_vfs_cap(void) { return (double)VFS_CAP; }
/* Pending changes for the host: removals first, then the files written since the last settle. */
typedef struct { int kind; const char *name; int nameLen; const unsigned char *data; int len; } VChange;
static VChange change;
EXPORT(hs_vfs_changes) int hs_vfs_changes(void) {
  int n = ngone;
  for (int i = 0; i < nnodes; i++) if (nodes[i]->dirty) n++;
  return n;
}
EXPORT(hs_vfs_change) VChange *hs_vfs_change(int i) {
  if (i < ngone) { change.kind = 2; change.name = gone[i]; change.nameLen = (int)strlen(gone[i]); change.data = 0; change.len = 0; return &change; }
  i -= ngone;
  for (int k = 0; k < nnodes; k++) if (nodes[k]->dirty && i-- == 0) {
    VNode *n = nodes[k];
    change.kind = 1; change.name = n->name; change.nameLen = (int)strlen(n->name); change.data = n->data; change.len = (int)n->len;
    return &change;
  }
  return NULL;
}
EXPORT(hs_vfs_settle) void hs_vfs_settle(void) {
  for (int i = 0; i < nnodes; i++) nodes[i]->dirty = 0;
  for (int i = 0; i < ngone; i++) free(gone[i]);
  ngone = 0;
}
#if !defined(__wasm__)
/* Android: the folder is a directory of the app's private storage, one file per name (hex of its UTF-8 bytes, so no
 * stored name is a path), written to a temporary file then renamed; nothing follows a symbolic link. */
static char vfsDir[1024];
static void hexname(const char *s, char *out) { static const char *h = "0123456789abcdef"; for (; *s; s++) { unsigned char c = (unsigned char)*s; *out++ = h[c >> 4]; *out++ = h[c & 15]; } *out = 0; }
static int unhex(const char *s, char *out) {
  size_t n = strlen(s);
  if (n == 0 || n % 2 || n / 2 > VFS_NAME_MAX) return 0;
  for (size_t i = 0; i < n; i += 2) {
    int a = s[i] >= '0' && s[i] <= '9' ? s[i] - '0' : s[i] >= 'a' && s[i] <= 'f' ? s[i] - 'a' + 10 : -1;
    int b = s[i + 1] >= '0' && s[i + 1] <= '9' ? s[i + 1] - '0' : s[i + 1] >= 'a' && s[i + 1] <= 'f' ? s[i + 1] - 'a' + 10 : -1;
    if (a < 0 || b < 0) return 0;
    out[i / 2] = (char)(a * 16 + b);
  }
  out[n / 2] = 0;
  return 1;
}
int hs_vfs_mount_dir(const char *dir) {
  hs_vfs_reset();
  snprintf(vfsDir, sizeof vfsDir, "%s", dir);
  mkdir(vfsDir, 0700);
  DIR *d = opendir(vfsDir);
  if (!d) return 0;
  struct dirent *e; int count = 0;
  while ((e = readdir(d)) != NULL) {
    char name[VFS_NAME_MAX + 1], path[1400];
    if (!unhex(e->d_name, name)) continue;
    snprintf(path, sizeof path, "%s/%s", vfsDir, e->d_name);
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) continue;
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && (size_t)st.st_size <= VFS_CAP) {
      unsigned char *buf = malloc((size_t)st.st_size + 1); size_t got = 0;
      if (buf) {
        while (got < (size_t)st.st_size) { ssize_t r = read(fd, buf + got, (size_t)st.st_size - got); if (r <= 0) break; got += (size_t)r; }
        if (got == (size_t)st.st_size && vfs_put(name, buf, got)) count++;
        free(buf);
      }
    }
    close(fd);
  }
  closedir(d);
  return count;
}
int hs_vfs_sync_dir(void) {
  if (!vfsDir[0]) return 0;
  int n = hs_vfs_changes(), failed = 0;
  for (int i = 0; i < n; i++) {
    VChange *c = hs_vfs_change(i);
    if (!c) break;
    char hx[2 * VFS_NAME_MAX + 1], path[1400], tmp[1500];
    hexname(c->name, hx);
    snprintf(path, sizeof path, "%s/%s", vfsDir, hx);
    if (c->kind == 2) { if (unlink(path) && errno != ENOENT) failed++; continue; }
    snprintf(tmp, sizeof tmp, "%s/.tmp-%s", vfsDir, hx);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) { failed++; continue; }
    size_t off = 0;
    while (off < (size_t)c->len) { ssize_t w = write(fd, c->data + off, (size_t)c->len - off); if (w <= 0) break; off += (size_t)w; }
    if (close(fd) || off != (size_t)c->len || rename(tmp, path)) { unlink(tmp); failed++; }
  }
  if (!failed) hs_vfs_settle();
  return failed;
}
void hs_libs_set_real(int on, const char *root) { libsReal = on; snprintf(realRoot, sizeof realRoot, "%s", root ? root : ""); }
void hs_libs_clear_sources(void) { for (int i = 0; i < nrealSrc; i++) free(realSrc[i]); free(realSrc); realSrc = NULL; nrealSrc = 0; }
void hs_libs_allow_source(const char *s) {
  char **a = realloc(realSrc, sizeof(char *) * (nrealSrc + 1));
  if (!a) return;
  realSrc = a; if ((realSrc[nrealSrc] = strdup(s)) != NULL) nrealSrc++;
}
#endif

/* ================================================================ io */
static int call_orig(lua_State *L) { return lua_tocfunction(L, lua_upvalueindex(1))(L); }
/* io.open, io.lines, io.input, io.output, io.tmpfile: real files only for an approved script's own call */
static int io_wrap(lua_State *L) { vfsReal = real_for(L); int r = call_orig(L); vfsReal = 0; return r; }
static int io_popen_hs(lua_State *L) {
  luaL_checkstring(L, 1); luaL_optstring(L, 2, "r");
  if (!real_for(L)) return luaL_error(L, "'popen' not supported");   /* Lua 5.3.5, the original's, checks no mode first */
  return call_orig(L);
}
static void wrap(lua_State *L, int t, const char *name, lua_CFunction w) {
  lua_getfield(L, t, name);
  lua_pushcclosure(L, w, 1);
  lua_setfield(L, t, name);
}

/* ================================================================ os */
static double clockBase = -1;
static int os_clock(lua_State *L) { lua_pushnumber(L, (lua_Number)((js_now() - clockBase) / 1000.0)); return 1; }

/* civil dates (proleptic Gregorian), days since 1970-01-01 */
static long long days_from_civil(long long y, int m, int d) {
  y -= m <= 2;
  long long era = (y >= 0 ? y : y - 399) / 400;
  long long yoe = y - era * 400;
  long long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  long long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return era * 146097 + doe - 719468;
}
static void civil_from_days(long long z, struct tm *tm) {
  z += 719468;
  long long era = (z >= 0 ? z : z - 146096) / 146097;
  long long doe = z - era * 146097;
  long long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  long long y = yoe + era * 400;
  long long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  long long mp = (5 * doy + 2) / 153;
  int d = (int)(doy - (153 * mp + 2) / 5 + 1);
  int m = (int)(mp < 10 ? mp + 3 : mp - 9);
  y += m <= 2;
  tm->tm_year = (int)(y - 1900); tm->tm_mon = m - 1; tm->tm_mday = d;
  tm->tm_yday = (int)(z - 719468 - days_from_civil(y, 1, 1));
}
static void split_utc(long long t, struct tm *tm) {
  long long days = t >= 0 ? t / 86400 : -((-t + 86399) / 86400);
  long long rem = t - days * 86400;
  memset(tm, 0, sizeof *tm);
  civil_from_days(days, tm);
  tm->tm_hour = (int)(rem / 3600); tm->tm_min = (int)(rem / 60 % 60); tm->tm_sec = (int)(rem % 60);
  tm->tm_wday = (int)(((days % 7) + 11) % 7);   /* 1970-01-01 was a Thursday */
}
static int std_offset(long long t) {   /* the zone's standard offset in t's year: the smaller of January's and July's */
  struct tm tm; split_utc(t, &tm);
  long long jan = days_from_civil(tm.tm_year + 1900, 1, 1) * 86400, jul = days_from_civil(tm.tm_year + 1900, 7, 1) * 86400;
  int a = js_tzoffset((double)jan), b = js_tzoffset((double)jul);
  return a < b ? a : b;
}
static void split_local(long long t, struct tm *tm) {
  int off = js_tzoffset((double)t);
  split_utc(t + (long long)off * 60, tm);
  tm->tm_isdst = off > std_offset(t);
}
/* msvcrt's 32-bit time_t: localtime and gmtime fail before 1970 (oracle date-neg); Lua checks the upper end */
static struct tm *hs_gmtime(long long t, struct tm *tm) { if (t < 0) return NULL; split_utc(t, tm); return tm; }
static struct tm *hs_localtime(long long t, struct tm *tm) { if (t < 0) return NULL; split_local(t, tm); return tm; }
static long long hs_mktime(struct tm *tm) {   /* fields may be out of range; normalised on return */
  long long mon = tm->tm_mon, y = (long long)tm->tm_year + 1900 + (mon >= 0 ? mon / 12 : -((11 - mon) / 12));
  mon -= (y - 1900 - tm->tm_year) * 12;
  long long local = (days_from_civil(y, (int)mon + 1, 1) + tm->tm_mday - 1) * 86400 + (long long)tm->tm_hour * 3600 + (long long)tm->tm_min * 60 + tm->tm_sec;
  long long t = local - (long long)js_tzoffset((double)local) * 60;
  t = local - (long long)js_tzoffset((double)t) * 60;
  if (t < 0 || t > INT32_MAX) return -1;   /* _mktime32 */
  split_local(t, tm);
  return t;
}

#define SIZETIMEFMT 250
#define L_MAXDATEFIELD (INT_MAX / 2)
/* the Windows build's options (loslib.c L_STRFTIMEWIN; oracle date-bad rows) */
#define LUA_STRFTIMEOPTIONS "aAbBcdHIjmMpSUwWxXyYzZ%" "||" "#c#x#d#H#I#j#m#M#S#U#w#W#y#Y"
static void setfield(lua_State *L, const char *key, int value) { lua_pushinteger(L, value); lua_setfield(L, -2, key); }
static void setboolfield(lua_State *L, const char *key, int value) { if (value < 0) return; lua_pushboolean(L, value); lua_setfield(L, -2, key); }
static void setallfields(lua_State *L, struct tm *stm) {
  setfield(L, "sec", stm->tm_sec); setfield(L, "min", stm->tm_min); setfield(L, "hour", stm->tm_hour);
  setfield(L, "day", stm->tm_mday); setfield(L, "month", stm->tm_mon + 1); setfield(L, "year", stm->tm_year + 1900);
  setfield(L, "wday", stm->tm_wday + 1); setfield(L, "yday", stm->tm_yday + 1); setboolfield(L, "isdst", stm->tm_isdst);
}
static int getboolfield(lua_State *L, const char *key) {
  int res = (lua_getfield(L, -1, key) == LUA_TNIL) ? -1 : lua_toboolean(L, -1);
  lua_pop(L, 1);
  return res;
}
static int getfield(lua_State *L, const char *key, int d, int delta) {
  int isnum;
  int t = lua_getfield(L, -1, key);
  lua_Integer res = lua_tointegerx(L, -1, &isnum);
  if (!isnum) {
    if (t != LUA_TNIL) return luaL_error(L, "field '%s' is not an integer", key);
    else if (d < 0) return luaL_error(L, "field '%s' missing in date table", key);
    res = d;
  } else {
    if (!(-L_MAXDATEFIELD <= res && res <= L_MAXDATEFIELD)) return luaL_error(L, "field '%s' is out-of-bound", key);
    res -= delta;
  }
  lua_pop(L, 1);
  return (int)res;
}
static const char *checkoption(lua_State *L, const char *conv, ptrdiff_t convlen, char *buff) {
  const char *option = LUA_STRFTIMEOPTIONS;
  int oplen = 1;
  for (; *option != '\0' && oplen <= convlen; option += oplen) {
    if (*option == '|') oplen++;
    else if (memcmp(conv, option, oplen) == 0) { memcpy(buff, conv, oplen); buff[oplen] = '\0'; return conv + oplen; }
  }
  luaL_argerror(L, 1, lua_pushfstring(L, "invalid conversion specifier '%%%s'", conv));
  return conv;
}
static const char *const DAYS[] = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
static const char *const MONTHS[] = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
/* msvcrt's strftime in the "C" locale for one conversion ("%X" or "%#X"); '#' drops leading zeros and makes %c, %x long */
static size_t win_strftime(char *out, size_t max, const char *cc, const struct tm *tm) {
  int alt = cc[1] == '#'; char c = cc[alt ? 2 : 1];
  char tz[128]; int n = 0;
  int h12 = tm->tm_hour % 12 == 0 ? 12 : tm->tm_hour % 12;
#define NUM(v, w) snprintf(out, max, alt ? "%d" : "%0*d", alt ? (v) : (w), (v))
  switch (c) {
    case 'a': n = snprintf(out, max, "%.3s", DAYS[tm->tm_wday]); break;
    case 'A': n = snprintf(out, max, "%s", DAYS[tm->tm_wday]); break;
    case 'b': n = snprintf(out, max, "%.3s", MONTHS[tm->tm_mon]); break;
    case 'B': n = snprintf(out, max, "%s", MONTHS[tm->tm_mon]); break;
    case 'c':
      if (alt) n = snprintf(out, max, "%s, %s %02d, %d %02d:%02d:%02d", DAYS[tm->tm_wday], MONTHS[tm->tm_mon], tm->tm_mday, tm->tm_year + 1900, tm->tm_hour, tm->tm_min, tm->tm_sec);
      else n = snprintf(out, max, "%02d/%02d/%02d %02d:%02d:%02d", tm->tm_mon + 1, tm->tm_mday, (tm->tm_year + 1900) % 100, tm->tm_hour, tm->tm_min, tm->tm_sec);
      break;
    case 'x':
      if (alt) n = snprintf(out, max, "%s, %s %02d, %d", DAYS[tm->tm_wday], MONTHS[tm->tm_mon], tm->tm_mday, tm->tm_year + 1900);
      else n = snprintf(out, max, "%02d/%02d/%02d", tm->tm_mon + 1, tm->tm_mday, (tm->tm_year + 1900) % 100);
      break;
    case 'X': n = snprintf(out, max, "%02d:%02d:%02d", tm->tm_hour, tm->tm_min, tm->tm_sec); break;
    case 'd': n = NUM(tm->tm_mday, 2); break;
    case 'H': n = NUM(tm->tm_hour, 2); break;
    case 'I': n = NUM(h12, 2); break;
    case 'j': n = NUM(tm->tm_yday + 1, 3); break;
    case 'm': n = NUM(tm->tm_mon + 1, 2); break;
    case 'M': n = NUM(tm->tm_min, 2); break;
    case 'p': n = snprintf(out, max, "%s", tm->tm_hour < 12 ? "AM" : "PM"); break;
    case 'S': n = NUM(tm->tm_sec, 2); break;
    case 'U': n = NUM((tm->tm_yday + 7 - tm->tm_wday) / 7, 2); break;
    case 'w': n = snprintf(out, max, "%d", tm->tm_wday); break;
    case 'W': n = NUM((tm->tm_yday + 7 - (tm->tm_wday + 6) % 7) / 7, 2); break;
    case 'y': n = NUM((tm->tm_year + 1900) % 100, 2); break;
    case 'Y': n = snprintf(out, max, "%d", tm->tm_year + 1900); break;
    case 'z': case 'Z': {   /* msvcrt: the zone's name for both (oracle: "Greenwich Standard Time" under Wine) */
      int k = js_tzname(tm->tm_isdst > 0, tz, (int)sizeof tz - 1);
      if (k < 0) k = 0;
      if (k > (int)sizeof tz - 1) k = (int)sizeof tz - 1;
      tz[k] = 0; n = snprintf(out, max, "%s", tz); break;
    }
    case '%': n = snprintf(out, max, "%%"); break;
  }
#undef NUM
  if (n < 0) n = 0;
  if ((size_t)n >= max) n = (int)max - 1;
  return (size_t)n;
}
static long long l_checktime(lua_State *L, int arg) {
  lua_Integer t = luaL_checkinteger(L, arg);
  luaL_argcheck(L, t >= INT32_MIN && t <= INT32_MAX, arg, "time out-of-bounds");   /* 32-bit time_t (oracle date-3000) */
  return (long long)t;
}
static int os_date(lua_State *L) {
  size_t slen;
  const char *s = luaL_optlstring(L, 1, "%c", &slen);
  long long t = lua_isnoneornil(L, 2) ? (long long)floor(js_epoch() / 1000.0) : l_checktime(L, 2);
  const char *se = s + slen;
  struct tm tmr, *stm;
  if (*s == '!') { stm = hs_gmtime(t, &tmr); s++; }
  else stm = hs_localtime(t, &tmr);
  if (stm == NULL) return luaL_error(L, "time result cannot be represented in this installation");
  if (strcmp(s, "*t") == 0) { lua_createtable(L, 0, 9); setallfields(L, stm); }
  else {
    char cc[4]; luaL_Buffer b;
    cc[0] = '%';
    luaL_buffinit(L, &b);
    while (s < se) {
      if (*s != '%') luaL_addchar(&b, *s++);
      else {
        char *buff = luaL_prepbuffsize(&b, SIZETIMEFMT);
        s++;
        s = checkoption(L, s, se - s, cc + 1);
        luaL_addsize(&b, win_strftime(buff, SIZETIMEFMT, cc, stm));
      }
    }
    luaL_pushresult(&b);
  }
  return 1;
}
static int os_time(lua_State *L) {
  long long t;
  if (lua_isnoneornil(L, 1)) t = (long long)floor(js_epoch() / 1000.0);
  else {
    struct tm ts;
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_settop(L, 1);
    memset(&ts, 0, sizeof ts);
    ts.tm_sec = getfield(L, "sec", 0, 0);
    ts.tm_min = getfield(L, "min", 0, 0);
    ts.tm_hour = getfield(L, "hour", 12, 0);
    ts.tm_mday = getfield(L, "day", -1, 0);
    ts.tm_mon = getfield(L, "month", -1, 1);
    ts.tm_year = getfield(L, "year", -1, 1900);
    ts.tm_isdst = getboolfield(L, "isdst");
    t = hs_mktime(&ts);
    if (t != -1) setallfields(L, &ts);
  }
  if (t < 0 || t > INT32_MAX) return luaL_error(L, "time result cannot be represented in this installation");
  lua_pushinteger(L, (lua_Integer)t);
  return 1;
}
static int os_difftime(lua_State *L) {
  long long t1 = l_checktime(L, 1), t2 = l_checktime(L, 2);
  lua_pushnumber(L, (lua_Number)(t1 - t2));
  return 1;
}
static int os_execute(lua_State *L) {
  const char *cmd = luaL_optstring(L, 1, NULL);
#if !defined(__wasm__)
  if (real_for(L)) {
    char b[2048];
    if (cmd && realRoot[0]) { snprintf(b, sizeof b, "cd '%s' && %s", realRoot, cmd); cmd = b; }
    int stat = system(cmd);
    if (cmd != NULL) return luaL_execresult(L, stat);
    lua_pushboolean(L, stat);
    return 1;
  }
#endif
  if (cmd == NULL) { lua_pushboolean(L, 0); return 1; }   /* no shell */
  errno = W_ENOENT;
  return luaL_execresult(L, -1);
}
static int os_getenv(lua_State *L) {
  const char *name = luaL_checkstring(L, 1);
#if !defined(__wasm__)
  if (real_for(L)) { lua_pushstring(L, getenv(name)); return 1; }
#else
  (void)name;
#endif
  lua_pushnil(L);
  return 1;
}
static int vfs_remove(const char *path) {
  char name[VFS_NAME_MAX + 1]; int root, e = vfs_norm(path, name, &root);
  if (e) return e;
  if (root) return W_EACCES;
  VNode *n = vfs_find(name);
  if (!n) return W_ENOENT;
  if (n->open) return W_EACCES;   /* Windows keeps an open file (oracle remove-open) */
  vfs_unlist(n); gone_add(n->name); vfs_free(n);
  return 0;
}
static int vfs_rename(const char *from, const char *to) {
  char a[VFS_NAME_MAX + 1], b[VFS_NAME_MAX + 1]; int ra, rb, e;
  if ((e = vfs_norm(from, a, &ra))) return e;
  if (ra) return W_EACCES;
  VNode *n = vfs_find(a);
  if (!n) return W_ENOENT;
  if (n->open) return W_EACCES;
  if ((e = vfs_norm(to, b, &rb))) return e;
  if (rb) return W_EACCES;
  VNode *m = vfs_find(b);
  if (m && m != n) return W_EEXIST;   /* Windows' rename does not replace (oracle rename-onto) */
  char *nn = strdup(b), *nk = strdup(b);
  if (!nn || !nk) { free(nn); free(nk); return W_ENOSPC; }
  key_of(b, nk);
  gone_add(n->name); free(n->name); free(n->key); n->name = nn; n->key = nk; n->dirty = 1; gone_drop(nk);
  return 0;
}
static int os_remove(lua_State *L) {
  const char *filename = luaL_checkstring(L, 1);
#if !defined(__wasm__)
  if (real_for(L)) { char b[2048]; return luaL_fileresult(L, remove(real_path(filename, b, sizeof b)) == 0, filename); }
#endif
  int e = vfs_remove(filename);
  errno = e;
  return luaL_fileresult(L, e == 0, filename);
}
static int os_rename(lua_State *L) {
  const char *fromname = luaL_checkstring(L, 1);
  const char *toname = luaL_checkstring(L, 2);
#if !defined(__wasm__)
  if (real_for(L)) { char a[2048], b[2048]; return luaL_fileresult(L, rename(real_path(fromname, a, sizeof a), real_path(toname, b, sizeof b)) == 0, NULL); }
#endif
  int e = vfs_rename(fromname, toname);
  errno = e;
  return luaL_fileresult(L, e == 0, NULL);
}
static int tmpCount;
static int os_tmpname(lua_State *L) {
#if !defined(__wasm__)
  if (real_for(L)) {
    char b[1100];
    snprintf(b, sizeof b, "%s/lua_XXXXXX", realRoot[0] ? realRoot : ".");
    int fd = mkstemp(b);
    if (fd == -1) return luaL_error(L, "unable to generate a unique filename");
    close(fd);
    lua_pushstring(L, b);
    return 1;
  }
#endif
  /* msvcrt's tmpnam: "\s" + letters + "." in the root of the drive (oracle tmpname: "\www.", not writable there) */
  static const char *b32 = "0123456789abcdefghijklmnopqrstuv";
  int c = ++tmpCount;
  char name[16];
  snprintf(name, sizeof name, "\\s%c%c.", b32[(c >> 5) & 31], b32[c & 31]);
  lua_pushstring(L, name);
  return 1;
}
static int os_setlocale(lua_State *L) {
  static const char *const catnames[] = {"all", "collate", "ctype", "monetary", "numeric", "time", NULL};
  const char *l = luaL_optstring(L, 1, NULL);
  luaL_checkoption(L, 2, "all", catnames);
  /* the "C" locale stays (a locale of the player's machine would change how numbers print) */
  if (l == NULL || l[0] == 0 || strcmp(l, "C") == 0) lua_pushliteral(L, "C");
  else lua_pushnil(L);
  return 1;
}
static const luaL_Reg os_funcs[] = {
  {"clock", os_clock}, {"date", os_date}, {"difftime", os_difftime}, {"execute", os_execute}, {"exit", hs_os_exit},
  {"getenv", os_getenv}, {"remove", os_remove}, {"rename", os_rename}, {"setlocale", os_setlocale}, {"time", os_time},
  {"tmpname", os_tmpname}, {NULL, NULL}
};
static int open_os(lua_State *L) {
  if (clockBase < 0) clockBase = js_now();
  luaL_newlib(L, os_funcs);
  return 1;
}

/* ================================================================ package */
#define PATH_SEP ';'
/* package.path, cpath and config of the original's Windows build (oracle package.*), the game folder '!' as '.' */
static const char *const PKG_PATH = ".\\lua\\?.lua;.\\lua\\?\\init.lua;.\\?.lua;.\\?\\init.lua;.\\..\\share\\lua\\5.3\\?.lua;.\\..\\share\\lua\\5.3\\?\\init.lua;.\\?.lua;.\\?\\init.lua";
static const char *const PKG_CPATH = ".\\?.dll;.\\..\\lib\\lua\\5.3\\?.dll;.\\loadall.dll;.\\?.dll";
static const char *const PKG_CONFIG = "\\\n;\n?\n!\n-\n";
static const char *const NO_DLL = "The specified module could not be found.\r\n";   /* what Windows says for a missing DLL */
static int readable(lua_State *L, const char *filename) {
  vfsReal = real_for(L);
  FILE *f = hs_fopen(filename, "r");
  vfsReal = 0;
  if (f == NULL) return 0;
  fclose(f);
  return 1;
}
static const char *pushnexttemplate(lua_State *L, const char *path) {
  const char *l;
  while (*path == PATH_SEP) path++;
  if (*path == '\0') return NULL;
  l = strchr(path, PATH_SEP);
  if (l == NULL) l = path + strlen(path);
  lua_pushlstring(L, path, l - path);
  return l;
}
static const char *searchpath(lua_State *L, const char *name, const char *path, const char *sep, const char *dirsep) {
  luaL_Buffer msg;
  luaL_buffinit(L, &msg);
  if (*sep != '\0') name = luaL_gsub(L, name, sep, dirsep);
  while ((path = pushnexttemplate(L, path)) != NULL) {
    const char *filename = luaL_gsub(L, lua_tostring(L, -1), "?", name);
    lua_remove(L, -2);
    if (readable(L, filename)) return filename;
    lua_pushfstring(L, "\n\tno file '%s'", filename);
    lua_remove(L, -2);
    luaL_addvalue(&msg);
  }
  luaL_pushresult(&msg);
  return NULL;
}
static int ll_searchpath(lua_State *L) {
  const char *f = searchpath(L, luaL_checkstring(L, 1), luaL_checkstring(L, 2), luaL_optstring(L, 3, "."), luaL_optstring(L, 4, "\\"));
  if (f != NULL) return 1;
  lua_pushnil(L); lua_insert(L, -2);
  return 2;
}
static const char *findfile(lua_State *L, const char *name, const char *pname) {
  lua_getfield(L, lua_upvalueindex(1), pname);
  const char *path = lua_tostring(L, -1);
  if (path == NULL) luaL_error(L, "'package.%s' must be a string", pname);
  return searchpath(L, name, path, ".", "\\");
}
static int checkload(lua_State *L, int stat, const char *filename) {
  if (stat) { lua_pushstring(L, filename); return 2; }
  return luaL_error(L, "error loading module '%s' from file '%s':\n\t%s", lua_tostring(L, 1), filename, lua_tostring(L, -1));
}
static int searcher_Lua(lua_State *L) {
  const char *name = luaL_checkstring(L, 1);
  const char *filename = findfile(L, name, "path");
  if (filename == NULL) return 1;
  vfsReal = real_for(L);
  int ok = luaL_loadfilex(L, filename, "t") == LUA_OK;   /* text only: a compiled chunk is refused */
  vfsReal = 0;
  return checkload(L, ok, filename);
}
static int searcher_C(lua_State *L) {
  const char *name = luaL_checkstring(L, 1);
  const char *filename = findfile(L, name, "cpath");
  if (filename == NULL) return 1;
  lua_pushstring(L, NO_DLL);
  return checkload(L, 0, filename);
}
static int searcher_Croot(lua_State *L) {
  const char *name = luaL_checkstring(L, 1);
  const char *p = strchr(name, '.');
  if (p == NULL) return 0;
  lua_pushlstring(L, name, p - name);
  const char *filename = findfile(L, lua_tostring(L, -1), "cpath");
  if (filename == NULL) return 1;
  lua_pushstring(L, NO_DLL);
  return checkload(L, 0, filename);
}
static int ll_loadlib(lua_State *L) {
  luaL_checkstring(L, 1); luaL_checkstring(L, 2);
  lua_pushnil(L); lua_pushstring(L, NO_DLL); lua_pushliteral(L, "open");
  return 3;
}
static void setup_package(lua_State *L) {
  luaL_requiref(L, "package", luaopen_package, 1);
  int p = lua_gettop(L);
  lua_pushstring(L, PKG_PATH); lua_setfield(L, p, "path");
  lua_pushstring(L, PKG_CPATH); lua_setfield(L, p, "cpath");
  lua_pushstring(L, PKG_CONFIG); lua_setfield(L, p, "config");
  lua_pushcfunction(L, ll_loadlib); lua_setfield(L, p, "loadlib");
  lua_pushcfunction(L, ll_searchpath); lua_setfield(L, p, "searchpath");
  lua_getfield(L, p, "searchers");
  lua_CFunction s[] = {searcher_Lua, searcher_C, searcher_Croot};
  for (int i = 0; i < 3; i++) { lua_pushvalue(L, p); lua_pushcclosure(L, s[i], 1); lua_rawseti(L, -2, i + 2); }
  lua_pop(L, 2);
}

/* ================================================================ debug */
/* getregistry: a copy of the registry's named Lua entries; the runtime's own tables stay out of reach */
static int db_getregistry_hs(lua_State *L) {
  static const char *const keys[] = {"_LOADED", "_PRELOAD", LUA_FILEHANDLE, NULL};
  lua_newtable(L);
  lua_rawgeti(L, LUA_REGISTRYINDEX, LUA_RIDX_MAINTHREAD); lua_rawseti(L, -2, LUA_RIDX_MAINTHREAD);
  lua_rawgeti(L, LUA_REGISTRYINDEX, LUA_RIDX_GLOBALS); lua_rawseti(L, -2, LUA_RIDX_GLOBALS);
  for (int i = 0; keys[i]; i++) { lua_getfield(L, LUA_REGISTRYINDEX, keys[i]); lua_setfield(L, -2, keys[i]); }
  return 1;
}
/* a userdata's metatable is its type: the runtime and io trust it */
static int db_setmetatable_hs(lua_State *L) {
  luaL_argcheck(L, lua_type(L, 1) != LUA_TUSERDATA, 1, "a userdata's metatable cannot be changed here");
  return call_orig(L);
}
/* C functions keep their upvalues (the runtime's member tables are upvalues) */
static int db_upvalue_hs(lua_State *L) {
  if (lua_iscfunction(L, 1)) return 0;
  return call_orig(L);
}
static int db_upvalueid_hs(lua_State *L) {
  luaL_argcheck(L, !lua_iscfunction(L, 1), 2, "invalid upvalue index");
  return call_orig(L);
}
/* locals of a C function's frame cannot be set */
static int db_setlocal_hs(lua_State *L) {
  int arg = lua_isthread(L, 1) ? 1 : 0;
  lua_State *L1 = arg ? lua_tothread(L, 1) : L;
  lua_Debug ar;
  if (lua_isinteger(L, arg + 1) && lua_getstack(L1, (int)lua_tointeger(L, arg + 1), &ar) && lua_getinfo(L1, "S", &ar) && strcmp(ar.what, "C") == 0) {
    luaL_checkinteger(L, arg + 2); luaL_checkany(L, arg + 3);
    lua_pushnil(L);
    return 1;
  }
  return call_orig(L);
}
/* debug.debug reads commands from the console: there is none (the original's stdin is empty too) */
static int db_debug_hs(lua_State *L) {
  (void)L;
  fputs("lua_debug> ", hs_vstd(2)); fputs("\n", hs_vstd(2));
  return 0;
}
static void setup_debug(lua_State *L) {
  luaL_requiref(L, "debug", luaopen_debug, 1);
  int d = lua_gettop(L);
  lua_pushcfunction(L, db_getregistry_hs); lua_setfield(L, d, "getregistry");
  wrap(L, d, "setmetatable", db_setmetatable_hs);
  wrap(L, d, "getupvalue", db_upvalue_hs);
  wrap(L, d, "setupvalue", db_upvalue_hs);
  wrap(L, d, "upvalueid", db_upvalueid_hs);
  wrap(L, d, "setlocal", db_setlocal_hs);
  lua_pushcfunction(L, db_debug_hs); lua_setfield(L, d, "debug");
  lua_pop(L, 1);
  hs_open_debug_hooks(L);
}

/* ================================================================ set-up */
void hs_open_libs(lua_State *L) {
  luaL_requiref(L, "io", luaopen_io, 1);
  int io = lua_gettop(L);
  wrap(L, io, "open", io_wrap); wrap(L, io, "lines", io_wrap); wrap(L, io, "input", io_wrap);
  wrap(L, io, "output", io_wrap); wrap(L, io, "tmpfile", io_wrap); wrap(L, io, "popen", io_popen_hs);
  lua_pop(L, 1);
  luaL_requiref(L, "os", open_os, 1); lua_pop(L, 1);
  setup_package(L);
  setup_debug(L);
}
