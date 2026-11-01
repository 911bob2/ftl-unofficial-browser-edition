/*
 * ftlman 0.7.4 Lua patch scripts (`X.append.lua`) for the mod merge, on Lua 5.3.6 (third_party/lua-5.3.6, unmodified;
 * its VM through ftlman-lvm.c for Lua 5.4's string arithmetic), built as its own WebAssembly module
 * (web/vendor/ftlman-lua.wasm, see hs-lua/Dockerfile) with its own Lua state.
 * Ported from ftlman 0.7.4 (GPL-2.0, github.com/afishhh/ftlman): src/lua.rs (runtime, environments, protected
 * tables), src/lua/xml.rs (the XML API), src/lua/io.rs (filesystems), src/lua/debug.rs, util.rs, meta.rs, the builtin
 * Lua in src/lua/ (util, iterutil, table, debug), and src/xmltree/dom.rs (the document tree, including its
 * sibling-link behaviour).
 *
 * The host does what needs the platform's XML reader/writer and files (docs/MODS.md, "Lua patch scripts"):
 *   env.log(kind, ptr, len)                 print (0), mod.debug.pretty_print (1), Lua warnings (2)
 *   env.fs(fs, op, pathLen, dataLen) -> r   stat (0) / ls (1) / read (2) / write (3) on mod.vfs filesystem `fs`
 *                                           (in a merge: 0 = pkg, 1 = mod); the path has its leading '/' removed
 *   env.xml_parse(len, topLevelText) -> r   text -> tree events (mod.xml.parse)
 *   env.xml_write(len) -> r                 tree events -> text (mod.xml.stringify)
 *   env.xml_append(lowerLen, upperLen, kind) -> r   ftlman's apply_one_xml (mod.xml.append)
 * Arguments and results go through one shared buffer (mp_buf). r >= 0 is a result length; r == -1 is "nothing"
 * (stat of a missing path); r <= -2 is an error whose message is the first (-2 - r) bytes of the buffer.
 * Results: stat "d" (directory) or "f<length>"; ls {type byte 'f'/'d', u32 length, name}...; read the bytes.
 * The document of a run arrives and leaves as tree events (mp_run). Events: 'S' u32 prefixLen (all bits set = none)
 * prefix u32 nameLen name u32 nAttrs {u32 len key u32 len value}..., children..., 'E'; 'T'/'D'/'C' u32 len text
 * (text, CDATA, comment). Integers are little-endian.
 *
 * Safety (not in ftlman): only base/table/string/math, `load` reads text chunks only, an instruction limit per script
 * (pcall cannot catch it; bytes allocated or written count too), a memory limit for the Lua heap and the document
 * trees, no unbounded C recursion, and a guard against the sibling loops ftlman's tree can form (ftlman then never
 * finishes). Table iteration order is fixed (hash seed 0); ftlman's Lua 5.4 seeds it randomly per run.
 */
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdarg.h>
#include <math.h>
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define EXPORT(n) __attribute__((export_name(#n)))
#define IMPORT(n) __attribute__((import_module("env"), import_name(#n)))

IMPORT(log) extern void host_log(int kind, const char *p, int len);
IMPORT(fs) extern int host_fs(int fs, int op, int pathLen, int dataLen);
IMPORT(xml_parse) extern int host_xml_parse(int len, int topLevelText);
IMPORT(xml_write) extern int host_xml_write(int len);
IMPORT(xml_append) extern int host_xml_append(int lowerLen, int upperLen, int kind);

/* ---------------------------------------------------------------- limits and memory */
static size_t memUsed, memPeak, memLimit = (size_t)512 << 20;
static int memHit;
static double instrLimit = 1e9, instrUsed;
static int killed;
#define HOOK_STEP 1000
/* Work inside library and API calls counts too: one instruction per CHARGE_BYTES bytes allocated or written, or per
 * CHARGE_BYTES tree nodes walked, so a loop over expensive calls (string building, cloning, stringify, inserting into
 * deep trees) also stops. */
#define CHARGE_BYTES 16
#define CHARGE(n) (instrUsed += (double)(n) / CHARGE_BYTES)

static int budget(size_t add) { if (add > memLimit - memUsed) { memHit = 1; return 0; } return 1; }
static void *lua_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
  (void)ud;
  size_t old = ptr ? osize : 0;
  if (nsize == 0) { free(ptr); memUsed -= old; return NULL; }
  if (nsize > old) { if (!budget(nsize - old)) return NULL; CHARGE(nsize - old); }
  void *p = realloc(ptr, nsize);
  if (!p) { memHit = 1; return NULL; }
  memUsed = memUsed - old + nsize; if (memUsed > memPeak) memPeak = memUsed;
  return p;
}
/* Tree memory: counted in the same budget, with the size kept in front of each block. */
static void *dmalloc(size_t n) {
  if (!budget(n + 8)) return NULL;
  size_t *p = malloc(n + 8); if (!p) { memHit = 1; return NULL; }
  *p = n; memUsed += n + 8; if (memUsed > memPeak) memPeak = memUsed; CHARGE(n + 8);
  return p + 1;
}
static void dfree(void *q) { if (!q) return; size_t *p = (size_t *)q - 1; memUsed -= *p + 8; free(p); }
static char *dstrdup(const char *s, size_t n) { char *p = dmalloc(n + 1); if (!p) return NULL; memcpy(p, s, n); p[n] = 0; return p; }

/* ---------------------------------------------------------------- shared buffer */
static uint8_t *buf; static size_t bufCap;
EXPORT(mp_buf) uint8_t *mp_buf(void) { return buf; }
EXPORT(mp_buf_cap) int mp_buf_cap(void) { return (int)bufCap; }
/* Grows the buffer to at least n bytes (keeping its content); 0 when that would pass the memory limit. */
EXPORT(mp_buf_grow) uint8_t *mp_buf_grow(int n) {
  if (n < 0) return 0;
  if ((size_t)n <= bufCap) return buf;
  size_t c = bufCap ? bufCap : 65536; while (c < (size_t)n) c *= 2;
  if (c - bufCap > memLimit - memUsed) { c = (size_t)n; if (!budget(c - bufCap)) return 0; }
  uint8_t *p = realloc(buf, c); if (!p) { memHit = 1; return 0; }
  memUsed += c - bufCap; buf = p; bufCap = c; if (memUsed > memPeak) memPeak = memUsed;
  return buf;
}
static size_t outLen;   /* write position while building a buffer */
static int out(const void *p, size_t n) {
  if (outLen + n > bufCap && !mp_buf_grow((int)(outLen + n))) return 0;
  memcpy(buf + outLen, p, n); outLen += n; CHARGE(n); return 1;
}
static int out32(uint32_t v) { uint8_t b[4] = {v & 255, (v >> 8) & 255, (v >> 16) & 255, v >> 24}; return out(b, 4); }
static int outStr(const char *s, size_t n) { return out32((uint32_t)n) && out(s, n); }
static uint32_t rd32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

/* ---------------------------------------------------------------- text helpers */
typedef struct { char *p; size_t n, cap; } Sb;   /* growable string (plain malloc; short-lived) */
static void sb_add(Sb *b, const char *s, size_t n) {
  if (b->n + n + 1 > b->cap) { size_t c = b->cap ? b->cap : 64; while (c < b->n + n + 1) c *= 2; char *q = realloc(b->p, c); if (!q) return; b->p = q; b->cap = c; }
  memcpy(b->p + b->n, s, n); b->n += n; b->p[b->n] = 0;
}
static void sb_s(Sb *b, const char *s) { sb_add(b, s, strlen(s)); }
static void sb_c(Sb *b, char c) { sb_add(b, &c, 1); }

/* Rust's str::from_utf8: well-formed UTF-8 only (no overlongs, surrogates or values above U+10FFFF). */
static int utf8_valid(const char *s, size_t n) {
  const unsigned char *p = (const unsigned char *)s, *e = p + n;
  while (p < e) {
    unsigned c = *p;
    if (c < 0x80) { p++; continue; }
    int len; unsigned min, cp;
    if (c >= 0xc2 && c <= 0xdf) { len = 2; min = 0x80; cp = c & 0x1f; }
    else if (c >= 0xe0 && c <= 0xef) { len = 3; min = 0x800; cp = c & 0x0f; }
    else if (c >= 0xf0 && c <= 0xf4) { len = 4; min = 0x10000; cp = c & 0x07; }
    else return 0;
    if (e - p < len) return 0;
    for (int i = 1; i < len; i++) { if ((p[i] & 0xc0) != 0x80) return 0; cp = cp << 6 | (p[i] & 0x3f); }
    if (cp < min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return 0;
    p += len;
  }
  return 1;
}
static unsigned utf8_next(const unsigned char **pp) {   /* on valid UTF-8 */
  const unsigned char *p = *pp; unsigned c = *p++;
  if (c >= 0xf0) { c = (c & 7) << 18 | (p[0] & 63) << 12 | (p[1] & 63) << 6 | (p[2] & 63); p += 3; }
  else if (c >= 0xe0) { c = (c & 15) << 12 | (p[0] & 63) << 6 | (p[1] & 63); p += 2; }
  else if (c >= 0xc0) { c = (c & 31) << 6 | (p[0] & 63); p += 1; }
  *pp = p; return c;
}
/* Characters Rust's Debug escapes as \u{..}: control, format, separator (except ' '), private use, noncharacters,
 * and the main combining-mark blocks (grapheme extenders). Rust's own tables are larger (unassigned code points,
 * every combining mark); this only shapes debug text. */
static int rust_escaped(unsigned c) {
  if (c < 0x20 || (c >= 0x7f && c <= 0xa0) || c == 0xad) return 1;
  if ((c >= 0x300 && c <= 0x36f) || (c >= 0x483 && c <= 0x489) || (c >= 0x591 && c <= 0x5bd) || (c >= 0x610 && c <= 0x61a) || c == 0x61c) return 1;
  if ((c >= 0x600 && c <= 0x605) || c == 0x6dd || c == 0x70f || c == 0x180e || c == 0x1680) return 1;
  if ((c >= 0x1ab0 && c <= 0x1aff) || (c >= 0x1dc0 && c <= 0x1dff) || (c >= 0x2000 && c <= 0x200f) || (c >= 0x2028 && c <= 0x202f)) return 1;
  if ((c >= 0x205f && c <= 0x206f) || (c >= 0x20d0 && c <= 0x20ff) || c == 0x3000 || (c >= 0xfe00 && c <= 0xfe0f) || (c >= 0xfe20 && c <= 0xfe2f)) return 1;
  if (c == 0xfeff || (c >= 0xfff9 && c <= 0xfffb) || (c >= 0xe000 && c <= 0xf8ff) || (c >= 0xfdd0 && c <= 0xfdef) || (c & 0xfffe) == 0xfffe) return 1;
  if ((c >= 0xe0000 && c <= 0xe0fff) || c >= 0xf0000) return 1;
  return 0;
}
static void rust_escape_char(Sb *b, unsigned c, int squote, int dquote) {
  char t[16];
  switch (c) {
    case 0: sb_s(b, "\\0"); return;
    case '\t': sb_s(b, "\\t"); return;
    case '\r': sb_s(b, "\\r"); return;
    case '\n': sb_s(b, "\\n"); return;
    case '\\': sb_s(b, "\\\\"); return;
    case '"': if (dquote) { sb_s(b, "\\\""); return; } break;
    case '\'': if (squote) { sb_s(b, "\\'"); return; } break;
  }
  if (rust_escaped(c)) { snprintf(t, sizeof t, "\\u{%x}", c); sb_s(b, t); return; }
  if (c < 0x80) { sb_c(b, (char)c); return; }
  unsigned char u[4]; int n;
  if (c < 0x800) { u[0] = 0xc0 | c >> 6; u[1] = 0x80 | (c & 63); n = 2; }
  else if (c < 0x10000) { u[0] = 0xe0 | c >> 12; u[1] = 0x80 | (c >> 6 & 63); u[2] = 0x80 | (c & 63); n = 3; }
  else { u[0] = 0xf0 | c >> 18; u[1] = 0x80 | (c >> 12 & 63); u[2] = 0x80 | (c >> 6 & 63); u[3] = 0x80 | (c & 63); n = 4; }
  sb_add(b, (char *)u, n);
}
/* Rust {:?} of a str (valid UTF-8; anything else is shown byte by byte as Latin-1). */
static void rust_debug_str(Sb *b, const char *s, size_t n) {
  sb_c(b, '"');
  if (utf8_valid(s, n)) { const unsigned char *p = (const unsigned char *)s, *e = p + n; while (p < e) rust_escape_char(b, utf8_next(&p), 0, 1); }
  else for (size_t i = 0; i < n; i++) rust_escape_char(b, (unsigned char)s[i], 0, 1);
  sb_c(b, '"');
}

/* Shortest digits that read back as v (what Ryu, and so Rust, print): the correctly rounded value with the fewest
 * digits that round-trips, or at that length its neighbour on the other side of v when only that one does. */
typedef struct { char d[32]; int n; int k; } Dec;   /* v = d (n digits) * 10^k */
static void dec_strip(Dec *x) { while (x->n > 1 && x->d[x->n - 1] == '0') { x->n--; x->k++; } x->d[x->n] = 0; }
static double dec_value(const Dec *x) { char t[48]; snprintf(t, sizeof t, "%.*se%d", x->n, x->d, x->k); return strtod(t, NULL); }
static void shortest(double v, Dec *out) {
  char s[48];
  for (int p = 1; p <= 17; p++) {
    snprintf(s, sizeof s, "%.*e", p - 1, v);
    Dec c; c.n = 0;
    const char *q = s;
    for (; *q && *q != 'e'; q++) if (*q >= '0' && *q <= '9') c.d[c.n++] = *q;
    c.d[c.n] = 0; c.k = atoi(q + 1) - (p - 1);
    double cv = strtod(s, NULL);
    if (cv == v) { *out = c; dec_strip(out); return; }
    Dec o = c; int i = o.n - 1;
    if (cv < v) {
      while (i >= 0 && o.d[i] == '9') o.d[i--] = '0';
      if (i < 0) { memmove(o.d + 1, o.d, o.n); o.d[0] = '1'; o.n++; } else o.d[i]++;
    } else {
      while (i >= 0 && o.d[i] == '0') o.d[i--] = '9';
      if (i < 0) continue;
      o.d[i]--;
      if (o.d[0] == '0') { if (o.n == 1) continue; memmove(o.d, o.d + 1, o.n - 1); o.n--; }
    }
    o.d[o.n] = 0;
    if (dec_value(&o) == v) { *out = o; dec_strip(out); return; }
  }
  snprintf(s, sizeof s, "%.16e", v);   /* not reached: 17 digits always round-trip */
  out->n = 0; const char *q = s; for (; *q && *q != 'e'; q++) if (*q >= '0' && *q <= '9') out->d[out->n++] = *q;
  out->k = atoi(q + 1) - 16; dec_strip(out);
}
/* ryu::Buffer::format (ftlman writes float attributes with it). */
static void ryu_format(Sb *b, double v) {
  if (isnan(v)) { sb_s(b, "NaN"); return; }
  if (isinf(v)) { sb_s(b, v < 0 ? "-inf" : "inf"); return; }
  if (signbit(v)) sb_c(b, '-');
  v = fabs(v);
  if (v == 0) { sb_s(b, "0.0"); return; }
  Dec x; shortest(v, &x);
  int n = x.n, k = x.k, kk = n + k; char t[16];
  if (k >= 0 && kk <= 16) { sb_add(b, x.d, n); for (int i = n; i < kk; i++) sb_c(b, '0'); sb_s(b, ".0"); }
  else if (kk > 0 && kk <= 16) { sb_add(b, x.d, kk); sb_c(b, '.'); sb_add(b, x.d + kk, n - kk); }
  else if (kk > -5 && kk <= 0) { sb_s(b, "0."); for (int i = 0; i < -kk; i++) sb_c(b, '0'); sb_add(b, x.d, n); }
  else if (n == 1) { sb_c(b, x.d[0]); snprintf(t, sizeof t, "e%d", kk - 1); sb_s(b, t); }
  else { sb_c(b, x.d[0]); sb_c(b, '.'); sb_add(b, x.d + 1, n - 1); snprintf(t, sizeof t, "e%d", kk - 1); sb_s(b, t); }
}
/* Rust's Display for f64 (mod.debug.pretty_*): the same digits, never an exponent, no ".0". */
static void rust_display_f64(Sb *b, double v) {
  if (isnan(v)) { sb_s(b, "NaN"); return; }
  if (isinf(v)) { sb_s(b, v < 0 ? "-inf" : "inf"); return; }
  if (signbit(v)) sb_c(b, '-');
  v = fabs(v);
  if (v == 0) { sb_c(b, '0'); return; }
  Dec x; shortest(v, &x);
  if (x.k >= 0) { sb_add(b, x.d, x.n); for (int i = 0; i < x.k; i++) sb_c(b, '0'); return; }
  int pos = x.n + x.k;
  if (pos > 0) { sb_add(b, x.d, pos); sb_c(b, '.'); sb_add(b, x.d + pos, x.n - pos); }
  else { sb_s(b, "0."); for (int i = 0; i < -pos; i++) sb_c(b, '0'); sb_add(b, x.d, x.n); }
}
/* i64::from_str_radix(s, 10) */
static int rust_parse_i64(const char *s, size_t n, lua_Integer *out) {
  size_t i = 0; int neg = 0;
  if (n && (s[0] == '+' || s[0] == '-')) { neg = s[0] == '-'; i = 1; }
  if (i == n) return 0;
  uint64_t acc = 0, lim = neg ? (uint64_t)1 << 63 : ((uint64_t)1 << 63) - 1;
  for (; i < n; i++) {
    if (s[i] < '0' || s[i] > '9') return 0;
    unsigned d = s[i] - '0';
    if (acc > (lim - d) / 10) return 0;
    acc = acc * 10 + d;
  }
  *out = neg ? (lua_Integer)(0 - acc) : (lua_Integer)acc;
  return 1;
}
static int ieq(const char *a, size_t n, const char *lit) { size_t m = strlen(lit); if (n != m) return 0; for (size_t i = 0; i < n; i++) { char c = a[i]; if (c >= 'A' && c <= 'Z') c += 32; if (c != lit[i]) return 0; } return 1; }
/* f64::from_str: [+-] (inf | infinity | nan | digits[.digits][e[+-]digits]), case-insensitive words, nothing else. */
static int rust_parse_f64(const char *s, size_t n, double *out) {
  size_t i = 0; int neg = 0;
  if (n && (s[0] == '+' || s[0] == '-')) { neg = s[0] == '-'; i = 1; }
  const char *r = s + i; size_t m = n - i;
  if (ieq(r, m, "inf") || ieq(r, m, "infinity")) { *out = neg ? -HUGE_VAL : HUGE_VAL; return 1; }
  if (ieq(r, m, "nan")) { *out = neg ? -NAN : NAN; return 1; }
  size_t j = 0, digits = 0;
  while (j < m && r[j] >= '0' && r[j] <= '9') { j++; digits++; }
  if (j < m && r[j] == '.') { j++; while (j < m && r[j] >= '0' && r[j] <= '9') { j++; digits++; } }
  if (!digits) return 0;
  if (j < m && (r[j] == 'e' || r[j] == 'E')) {
    j++; if (j < m && (r[j] == '+' || r[j] == '-')) j++;
    size_t e0 = j; while (j < m && r[j] >= '0' && r[j] <= '9') j++;
    if (j == e0) return 0;
  }
  if (j != m) return 0;
  char *t = malloc(n + 1); if (!t) return 0;
  memcpy(t, s, n); t[n] = 0; *out = strtod(t, NULL); free(t);
  return 1;
}

/* ---------------------------------------------------------------- document tree (ftlman's xmltree/dom.rs) */
enum { K_ELEMENT, K_COMMENT, K_CDATA, K_TEXT };
typedef struct { char *k; size_t kn; char *v; size_t vn; } Attr;
typedef struct Node Node;
struct Node {
  uint8_t kind, mark;
  int refs;                         /* Lua userdata (and iterators) holding this node */
  Node *prev, *next, *parent;       /* parent is an element */
  Node *first, *last;               /* element children; `last` is NULL while there is one child, as in ftlman */
  Node *allPrev, *allNext;          /* every live node, for the sweep after each script */
  char *prefix; size_t prefixN;     /* element: prefix (NULL = none), name, attributes sorted by bytes */
  char *name; size_t nameN;
  Attr *attrs; size_t nattrs, capAttrs;
  char *content; size_t contentN;   /* text, CDATA, comment */
};
static Node *allNodes; static size_t nodeCount;

static Node *node_new(int kind) {
  Node *n = dmalloc(sizeof(Node)); if (!n) return NULL;
  memset(n, 0, sizeof *n); n->kind = (uint8_t)kind;
  n->allNext = allNodes; if (allNodes) allNodes->allPrev = n; allNodes = n; nodeCount++;
  return n;
}
static void node_free(Node *n) {
  if (n->allPrev) n->allPrev->allNext = n->allNext; else allNodes = n->allNext;
  if (n->allNext) n->allNext->allPrev = n->allPrev;
  for (size_t i = 0; i < n->nattrs; i++) { dfree(n->attrs[i].k); dfree(n->attrs[i].v); }
  dfree(n->attrs); dfree(n->prefix); dfree(n->name); dfree(n->content); dfree(n); nodeCount--;
}
static Node *text_new(int kind, const char *s, size_t len) {
  Node *n = node_new(kind); if (!n) return NULL;
  n->content = dstrdup(s, len); if (!n->content) { node_free(n); return NULL; }
  n->contentN = len; return n;
}
static int bytecmp(const char *a, size_t an, const char *b, size_t bn) { int c = memcmp(a, b, an < bn ? an : bn); return c ? c : (an < bn ? -1 : an > bn); }
static size_t attr_find(const Node *e, const char *k, size_t kn, int *found) {   /* lower bound */
  size_t lo = 0, hi = e->nattrs;
  while (lo < hi) { size_t mid = (lo + hi) / 2; int c = bytecmp(e->attrs[mid].k, e->attrs[mid].kn, k, kn); if (c < 0) lo = mid + 1; else hi = mid; }
  *found = lo < e->nattrs && bytecmp(e->attrs[lo].k, e->attrs[lo].kn, k, kn) == 0;
  return lo;
}
static int attr_set(Node *e, const char *k, size_t kn, const char *v, size_t vn) {   /* BTreeMap::insert */
  int found; size_t i = attr_find(e, k, kn, &found);
  char *vv = dstrdup(v, vn); if (!vv) return 0;
  if (found) { dfree(e->attrs[i].v); e->attrs[i].v = vv; e->attrs[i].vn = vn; return 1; }
  char *kk = dstrdup(k, kn); if (!kk) { dfree(vv); return 0; }
  if (e->nattrs == e->capAttrs) {
    size_t c = e->capAttrs ? e->capAttrs * 2 : 4; Attr *a = dmalloc(c * sizeof(Attr));
    if (!a) { dfree(kk); dfree(vv); return 0; }
    if (e->nattrs) memcpy(a, e->attrs, e->nattrs * sizeof(Attr));
    dfree(e->attrs); e->attrs = a; e->capAttrs = c;
  }
  memmove(e->attrs + i + 1, e->attrs + i, (e->nattrs - i) * sizeof(Attr));
  e->attrs[i].k = kk; e->attrs[i].kn = kn; e->attrs[i].v = vv; e->attrs[i].vn = vn; e->nattrs++;
  return 1;
}
static void attr_remove(Node *e, const char *k, size_t kn) {
  int found; size_t i = attr_find(e, k, kn, &found);
  if (!found) return;
  dfree(e->attrs[i].k); dfree(e->attrs[i].v);
  memmove(e->attrs + i, e->attrs + i + 1, (e->nattrs - i - 1) * sizeof(Attr)); e->nattrs--;
}
static Node *element_new(const char *prefix, size_t pn, const char *name, size_t nn) {
  Node *e = node_new(K_ELEMENT); if (!e) return NULL;
  if (prefix) { e->prefix = dstrdup(prefix, pn); if (!e->prefix) { node_free(e); return NULL; } e->prefixN = pn; }
  e->name = dstrdup(name, nn); if (!e->name) { node_free(e); return NULL; }
  e->nameN = nn; return e;
}

/* Element::append_child / prepend_child, node_insert_before/after, NodeExt::detach, remove_children: the links are
 * updated exactly as ftlman does, which leaves a detached node's own sibling links in place. */
static void append_child(Node *self, Node *child) {
  child->parent = self;
  if (self->last) { self->last->next = child; child->prev = self->last; self->last = child; }
  else if (self->first) { self->first->next = child; child->prev = self->first; self->last = child; }
  else self->first = child;
}
static void prepend_child(Node *self, Node *child) {
  child->parent = self;
  if (self->first) { self->first->prev = child; child->next = self->first; if (!self->last) self->last = self->first; }
  self->first = child;
}
static void insert_before(Node *self, Node *node) {
  if (self->prev) { Node *p = self->prev; p->next = node; self->prev = node; node->parent = self->parent; node->prev = p; node->next = self; }
  else if (self->parent) prepend_child(self->parent, node);
}
static void insert_after(Node *self, Node *node) {
  if (self->next) { Node *x = self->next; x->prev = node; self->next = node; node->parent = self->parent; node->prev = self; node->next = x; }
  else if (self->parent) append_child(self->parent, node);
}
static void detach(Node *self) {
  Node *p = self->parent;
  if (p) {
    if (p->first == self) p->first = self->next;
    else if (p->last == self) p->last = self->prev;
    if (self->prev) self->prev->next = self->next;
    if (self->next) self->next->prev = self->prev;
    if (p->first && p->last && p->first == p->last) p->last = NULL;
  }
  self->parent = NULL;
}
/* ElementChildren: forward follows `next` to the end, backward walks `last`/`prev` until the ends meet. */
typedef struct { Node *first, *last; } Kids;
static Kids kids_of(const Node *e) { Kids k = {e->first, e->last}; return k; }
static void kids_fix(Kids *k) { if (k->first) { if (k->last == k->first) k->last = NULL; } else k->last = NULL; }
static Node *kids_next(Kids *k) { Node *n = k->first; if (!n) return NULL; k->first = n->next; kids_fix(k); return n; }
static Node *kids_back(Kids *k) {
  if (k->last) { Node *n = k->last; k->last = n->prev; kids_fix(k); return n; }
  Node *n = k->first; k->first = NULL; return n;
}
/* Walks over more nodes than could exist without a loop in the sibling links: ftlman would never finish. */
static size_t walkLimit(void) { return nodeCount * 16 + 1000000; }
static const char *LOOP_MSG = "the document's sibling links form a loop (ftlman would never finish writing it)";
static int remove_children(Node *self) {
  Kids k = kids_of(self); size_t steps = 0, lim = walkLimit(); Node *c;
  while ((c = kids_next(&k))) { c->parent = NULL; if (++steps > lim) return 0; }
  CHARGE(steps); self->first = self->last = NULL; return 1;
}
static int is_lua_node(const Node *n) { return n->kind == K_ELEMENT || n->kind == K_TEXT; }

/* ---------------------------------------------------------------- tree <-> events */
/* Builds nodes from events at buf[off, off+len); top-level nodes go to *tops (malloc'd). 0 on bad input/memory. */
static int build(size_t off, size_t len, Node ***tops, size_t *ntops) {
  const uint8_t *p = buf + off, *e = p + len;
  Node **stack = NULL; size_t depth = 0, capStack = 0, cap = 0;
  *tops = NULL; *ntops = 0;
  #define NEED(n) do { if ((size_t)(e - p) < (size_t)(n)) goto bad; } while (0)
  while (p < e) {
    uint8_t t = *p++; Node *n = NULL;
    if (t == 'S') {
      NEED(4); uint32_t pl = rd32(p); p += 4; const char *pre = NULL;
      if (pl != 0xffffffffu) { NEED(pl); pre = (const char *)p; p += pl; } else pl = 0;
      NEED(4); uint32_t nl = rd32(p); p += 4; NEED(nl); const char *nm = (const char *)p; p += nl;
      n = element_new(pre, pl, nm, nl); if (!n) goto bad;
      NEED(4); uint32_t na = rd32(p); p += 4;
      for (uint32_t i = 0; i < na; i++) {
        NEED(4); uint32_t kl = rd32(p); p += 4; NEED(kl); const char *k = (const char *)p; p += kl;
        NEED(4); uint32_t vl = rd32(p); p += 4; NEED(vl); const char *v = (const char *)p; p += vl;
        if (!attr_set(n, k, kl, v, vl)) goto bad;
      }
    } else if (t == 'E') { if (!depth) goto bad; depth--; continue; }
    else if (t == 'T' || t == 'D' || t == 'C') {
      NEED(4); uint32_t l = rd32(p); p += 4; NEED(l);
      n = text_new(t == 'T' ? K_TEXT : t == 'D' ? K_CDATA : K_COMMENT, (const char *)p, l); if (!n) goto bad; p += l;
    } else goto bad;
    if (depth) append_child(stack[depth - 1], n);
    else {
      if (*ntops == cap) { cap = cap ? cap * 2 : 8; Node **q = realloc(*tops, cap * sizeof(Node *)); if (!q) goto bad; *tops = q; }
      (*tops)[(*ntops)++] = n;
    }
    if (t == 'S') {
      if (depth == capStack) { capStack = capStack ? capStack * 2 : 32; Node **q = realloc(stack, capStack * sizeof(Node *)); if (!q) goto bad; stack = q; }
      stack[depth++] = n;
    }
  }
  #undef NEED
  free(stack);
  return 1;
bad:
  free(stack); free(*tops); *tops = NULL; *ntops = 0;
  return 0;
}
static int emit_start(const Node *n) {
  if (!out("S", 1)) return 0;
  if (n->prefix) { if (!outStr(n->prefix, n->prefixN)) return 0; } else if (!out32(0xffffffffu)) return 0;
  if (!outStr(n->name, n->nameN) || !out32((uint32_t)n->nattrs)) return 0;
  for (size_t i = 0; i < n->nattrs; i++) if (!outStr(n->attrs[i].k, n->attrs[i].kn) || !outStr(n->attrs[i].v, n->attrs[i].vn)) return 0;
  return 1;
}
static int emit_leaf(const Node *n) {
  const char *t = n->kind == K_TEXT ? "T" : n->kind == K_CDATA ? "D" : "C";
  return out(t, 1) && outStr(n->content, n->contentN);
}
/* Appends the events of `root` (or only of its children) at outLen. 1 ok, 0 memory, -1 sibling loop. */
static int emit(const Node *root, int childrenOnly) {
  if (root->kind != K_ELEMENT) return emit_leaf(root);
  typedef struct { const Node *el; Kids k; } Frame;
  size_t cap = 32, depth = 0, steps = 0, lim = walkLimit();
  Frame *st = malloc(cap * sizeof(Frame)); if (!st) return 0;
  if (!childrenOnly) {
    if (!emit_start(root)) goto oom;
    if (!root->first) { free(st); return out("E", 1); }
  }
  st[depth].el = root; st[depth].k = kids_of(root); depth++;
  while (depth) {
    Frame *f = &st[depth - 1];
    Node *c = kids_next(&f->k);
    if (!c) { depth--; if ((depth || !childrenOnly) && !out("E", 1)) goto oom; continue; }
    if (++steps > lim) { free(st); return -1; }
    if (c->kind != K_ELEMENT) { if (!emit_leaf(c)) goto oom; continue; }
    if (!emit_start(c)) goto oom;
    if (!c->first) { if (!out("E", 1)) goto oom; continue; }
    if (depth == cap) { cap *= 2; Frame *q = realloc(st, cap * sizeof(Frame)); if (!q) goto oom; st = q; }
    st[depth].el = c; st[depth].k = kids_of(c); depth++;
  }
  free(st); return 1;
oom:
  free(st); return 0;
}
/* clone_any: text-like nodes copy their content, elements their name/attributes and (deep) every child. */
static Node *clone_node(const Node *src, int deep, int *loop) {
  *loop = 0;
  if (src->kind != K_ELEMENT) return text_new(src->kind, src->content, src->contentN);
  Node *top = element_new(src->prefix, src->prefixN, src->name, src->nameN); if (!top) return NULL;
  for (size_t i = 0; i < src->nattrs; i++) if (!attr_set(top, src->attrs[i].k, src->attrs[i].kn, src->attrs[i].v, src->attrs[i].vn)) return NULL;
  if (!deep) return top;
  typedef struct { Kids k; Node *dst; } Frame;
  size_t cap = 32, depth = 0, steps = 0, lim = walkLimit();
  Frame *st = malloc(cap * sizeof(Frame)); if (!st) return NULL;
  st[0].k = kids_of(src); st[0].dst = top; depth = 1;
  while (depth) {
    Frame *f = &st[depth - 1]; Node *c = kids_next(&f->k);
    if (!c) { depth--; continue; }
    if (++steps > lim) { *loop = 1; free(st); return NULL; }
    Node *d;
    if (c->kind != K_ELEMENT) { d = text_new(c->kind, c->content, c->contentN); if (!d) break; append_child(f->dst, d); continue; }
    d = element_new(c->prefix, c->prefixN, c->name, c->nameN); if (!d) break;
    for (size_t i = 0; i < c->nattrs; i++) if (!attr_set(d, c->attrs[i].k, c->attrs[i].kn, c->attrs[i].v, c->attrs[i].vn)) { free(st); return NULL; }
    append_child(f->dst, d);
    if (depth == cap) { cap *= 2; Frame *q = realloc(st, cap * sizeof(Frame)); if (!q) break; st = q; }
    st[depth].k = kids_of(c); st[depth].dst = d; depth++;
  }
  free(st);
  return depth ? NULL : top;
}
/* Nodes no Lua value can reach any more (directly or through any link) are freed after each script. */
static void sweep(void) {
  size_t cap = 1024, n = 0; Node **st = malloc(cap * sizeof(Node *));
  if (!st) return;
  for (Node *r = allNodes; r; r = r->allNext) {
    if (r->refs <= 0 || r->mark) continue;
    r->mark = 1; st[n++] = r;
    while (n) {
      Node *x = st[--n], *links[5] = {x->prev, x->next, x->parent, x->first, x->last};
      for (int i = 0; i < 5; i++) if (links[i] && !links[i]->mark) {
        links[i]->mark = 1;
        if (n == cap) { Node **q = realloc(st, cap * 2 * sizeof(Node *)); if (!q) { free(st); for (Node *y = allNodes; y; y = y->allNext) y->mark = 0; return; } st = q; cap *= 2; }
        st[n++] = links[i];
      }
    }
  }
  free(st);
  for (Node *r = allNodes, *nx; r; r = nx) { nx = r->allNext; if (r->mark) r->mark = 0; else node_free(r); }
}

/* ---------------------------------------------------------------- Lua state and userdata */
static lua_State *L0;
/* Errors from the API carry no position (ftlman's come from Rust, through mlua). */
static int fail(lua_State *L, const char *fmt, ...) {
  va_list ap; va_start(ap, fmt); lua_pushvfstring(L, fmt, ap); va_end(ap);
  return lua_error(L);
}
static int modRef = LUA_NOREF;          /* the `mod` table */
static int fsToken;                     /* mod.vfs userdata of earlier runs stop working */
static char *currentFile;               /* mod.meta.current_path */

#define MT_ELEMENT "ftlman.Element"
#define MT_TEXT "ftlman.Text"
#define MT_DOC "ftlman.Document"
#define MT_ATTRS "ftlman.Attributes"
#define MT_KIDS "ftlman.ElementChildren"
#define MT_NODES "ftlman.ChildNodes"
#define MT_FS "ftlman.FS"
typedef struct { Node *n; } UdNode;
typedef struct { Node *el; int raw; } UdAttrs;
typedef struct { Kids k; Node *hold[2]; } UdKids;
typedef struct { int fs, token; } UdFs;

static void ref(Node *n) { if (n) n->refs++; }
static void unref(Node *n) { if (n) n->refs--; }
static void push_node(lua_State *L, Node *n) {   /* gc_into_lua: elements and texts; nothing for comments/CDATA */
  if (!n || !is_lua_node(n)) { lua_pushnil(L); return; }
  UdNode *u = lua_newuserdata(L, sizeof(UdNode)); u->n = n; ref(n);
  luaL_setmetatable(L, n->kind == K_ELEMENT ? MT_ELEMENT : MT_TEXT);
}
static int gc_node(lua_State *L) { UdNode *u = lua_touserdata(L, 1); if (u) { unref(u->n); u->n = NULL; } return 0; }
static int gc_attrs(lua_State *L) { UdAttrs *u = lua_touserdata(L, 1); if (u) { unref(u->el); u->el = NULL; } return 0; }
static int gc_kids(lua_State *L) { UdKids *u = lua_touserdata(L, 1); if (u) { unref(u->hold[0]); unref(u->hold[1]); u->hold[0] = u->hold[1] = NULL; } return 0; }

static Node *opt_node(lua_State *L, int i, int elementOnly) {
  UdNode *u = luaL_testudata(L, i, MT_ELEMENT);
  if (!u && !elementOnly) u = luaL_testudata(L, i, MT_TEXT);
  return u ? u->n : NULL;
}
static Node *self_node(lua_State *L, int elementOnly) {
  Node *n = opt_node(L, 1, elementOnly);
  if (!n) fail(L, "bad argument #1 (expected %s, got %s)", elementOnly ? "Element" : "Element or Text", luaL_typename(L, 1));
  return n;
}
static int memerr(lua_State *L) { return fail(L, "not enough memory"); }
/* A Rust String/Box<str> argument: a string (valid UTF-8) or a number in Lua's tostring form; NULL otherwise. */
static const char *rstr(lua_State *L, int i, size_t *len, const char *what) {
  i = lua_absindex(L, i);
  int t = lua_type(L, i);
  if (t != LUA_TSTRING && t != LUA_TNUMBER) {
    if (what) fail(L, "error converting Lua %s to %s", luaL_typename(L, i), what);
    return NULL;
  }
  if (t == LUA_TNUMBER) { lua_pushvalue(L, i); lua_tolstring(L, -1, NULL); lua_replace(L, i); }
  const char *s = lua_tolstring(L, i, len);
  if (!utf8_valid(s, *len)) { if (what) fail(L, "invalid utf-8 encoding converting Lua string to %s", what); return NULL; }
  return s;
}
/* validate_xml_name: appends the error to b (nothing when the name is valid; "" is valid). */
static int name_msg(Sb *b, const char *s, size_t n) {
  if (!n) return 0;
  const unsigned char *p = (const unsigned char *)s, *e = p + n;
  unsigned c = utf8_next(&p);
  if (!((c < 128 && ((c | 32) >= 'a' && (c | 32) <= 'z')) || c == '-' || c == '_')) {
    sb_c(b, '\''); rust_escape_char(b, c, 1, 0); sb_s(b, "' is not allowed at the start of an XML name"); return 1;
  }
  p = (const unsigned char *)s;
  while (p < e) {
    c = utf8_next(&p);
    if (!((c < 128 && (((c | 32) >= 'a' && (c | 32) <= 'z') || (c >= '0' && c <= '9'))) || c == '-' || c == '_')) {
      sb_c(b, '\''); rust_escape_char(b, c, 1, 0); sb_s(b, "' is not allowed in an XML name"); return 1;
    }
  }
  return 0;
}
static void name_error(lua_State *L, const char *s, size_t n) {
  Sb b = {0};
  if (!name_msg(&b, s, n)) return;
  lua_pushlstring(L, b.p, b.n); free(b.p);
  lua_error(L);
}

/* Field getters and methods of a userdata type: __index looks up getters, then methods; unknown keys are nil. */
typedef struct { const char *name; lua_CFunction f; } Fn;
static int ud_index(lua_State *L) {
  if (lua_type(L, 2) != LUA_TSTRING) return 0;
  lua_pushvalue(L, 2);
  if (lua_rawget(L, lua_upvalueindex(1)) == LUA_TFUNCTION) { lua_pushvalue(L, 1); lua_call(L, 1, 1); return 1; }
  lua_pop(L, 1); lua_pushvalue(L, 2); lua_rawget(L, lua_upvalueindex(2));
  return 1;
}
static int ud_newindex(lua_State *L) {
  if (lua_type(L, 2) == LUA_TSTRING) {
    lua_pushvalue(L, 2);
    if (lua_rawget(L, lua_upvalueindex(1)) == LUA_TFUNCTION) { lua_pushvalue(L, 1); lua_pushvalue(L, 3); lua_call(L, 2, 0); return 0; }
  }
  return fail(L, "attempt to set an unknown field '%s'", lua_type(L, 2) == LUA_TSTRING ? lua_tostring(L, 2) : luaL_typename(L, 2));
}
static void fn_table(lua_State *L, const Fn *f) { lua_newtable(L); for (; f && f->name; f++) { lua_pushcfunction(L, f->f); lua_setfield(L, -2, f->name); } }
static void new_type(lua_State *L, const char *mt, const Fn *getters, const Fn *methods, const Fn *setters, const Fn *meta) {
  luaL_newmetatable(L, mt);
  fn_table(L, getters); fn_table(L, methods); lua_pushcclosure(L, ud_index, 2); lua_setfield(L, -2, "__index");
  fn_table(L, setters); lua_pushcclosure(L, ud_newindex, 1); lua_setfield(L, -2, "__newindex");
  for (; meta && meta->name; meta++) { lua_pushcfunction(L, meta->f); lua_setfield(L, -2, meta->name); }
  lua_pushboolean(L, 0); lua_setfield(L, -2, "__metatable");
  lua_pop(L, 1);
}

/* ---- Node (element and text) */
static int g_type(lua_State *L) { Node *n = self_node(L, 0); lua_pushstring(L, n->kind == K_ELEMENT ? "element" : "text"); return 1; }
static int g_prev(lua_State *L) { Node *n = self_node(L, 0); push_node(L, n->prev); return 1; }
static int g_next(lua_State *L) { Node *n = self_node(L, 0); push_node(L, n->next); return 1; }
static int g_parent(lua_State *L) { Node *n = self_node(L, 0); push_node(L, n->parent); return 1; }
static int m_as(lua_State *L) {
  Node *n = self_node(L, 0); size_t l; const char *k = rstr(L, 2, &l, "String");
  if (l == 7 && !memcmp(k, "element", 7)) { if (n->kind == K_ELEMENT) push_node(L, n); else lua_pushnil(L); return 1; }
  if (l == 4 && !memcmp(k, "text", 4)) { if (n->kind == K_TEXT) push_node(L, n); else lua_pushnil(L); return 1; }
  return fail(L, "invalid type passed to Node:as cast");
}
static int m_detach(lua_State *L) { detach(self_node(L, 0)); return 0; }
/* NodeImplicitlyConvertible: every argument from `first` is a node or string-like, checked before any insertion. */
static void check_insert_args(lua_State *L, int first) {
  for (int i = first; i <= lua_gettop(L); i++) {
    if (opt_node(L, i, 0)) continue;
    size_t l; if (!rstr(L, i, &l, NULL)) fail(L, "error converting Lua %s to XML Node", luaL_typename(L, i));
  }
}
static Node *insert_arg(lua_State *L, int i) {
  Node *n = opt_node(L, i, 0); if (n) return n;
  size_t l; const char *s = lua_tolstring(L, i, &l);
  n = text_new(K_TEXT, s, l); if (!n) memerr(L);
  return n;
}
static void check_insertion(lua_State *L, Node *parent, Node *ins, int argn, const char *fn) {
  if (parent == ins) fail(L, "Node passed as argument #%d to %s is the parent", argn, fn);
  size_t steps = 0;
  for (Node *p = parent->parent; p; p = p->parent) {
    if (p == ins) fail(L, "Node passed as argument #%d to %s is an ancestor of the parent", argn, fn);
    if (++steps > nodeCount) break;
  }
  CHARGE(steps);
  if (ins->parent) fail(L, "Node passed as argument #%d to %s already has a parent", argn, fn);
}
static int m_before(lua_State *L) {
  Node *self = self_node(L, 0); check_insert_args(L, 2);
  if (!self->parent) return fail(L, "cannot insert nodes before orphan node");
  for (int i = 2; i <= lua_gettop(L); i++) { Node *n = insert_arg(L, i); check_insertion(L, self->parent, n, i - 1, "Element:before"); insert_before(self, n); }
  return 0;
}
static int m_after(lua_State *L) {
  Node *self = self_node(L, 0); check_insert_args(L, 2);
  if (!self->parent) return fail(L, "cannot insert nodes after orphan node");
  for (int i = lua_gettop(L); i >= 2; i--) { Node *n = insert_arg(L, i); check_insertion(L, self->parent, n, i - 1, "Element:after"); insert_after(self, n); }
  return 0;
}
static int m_clone(lua_State *L) {
  Node *n = self_node(L, 0); int deep = 1;
  if (!lua_isnoneornil(L, 2)) {
    size_t l; const char *m = rstr(L, 2, &l, "LuaString");
    if (l == 7 && !memcmp(m, "shallow", 7)) deep = 0;
    else if (!(l == 4 && !memcmp(m, "deep", 4))) return fail(L, "`%s` is not a valid clone mode", m);
  }
  int loop; Node *c = clone_node(n, deep, &loop);
  if (!c) return loop ? fail(L, "%s", LOOP_MSG) : memerr(L);
  push_node(L, c); return 1;
}
/* ---- Element */
static int g_name(lua_State *L) { Node *n = self_node(L, 1); lua_pushlstring(L, n->name, n->nameN); return 1; }
static int g_prefix(lua_State *L) { Node *n = self_node(L, 1); if (n->prefix) lua_pushlstring(L, n->prefix, n->prefixN); else lua_pushnil(L); return 1; }
static int s_name(lua_State *L) {
  Node *n = self_node(L, 1); size_t l; const char *s = rstr(L, 2, &l, "Box<str>");
  name_error(L, s, l);
  char *p = dstrdup(s, l); if (!p) return memerr(L);
  dfree(n->name); n->name = p; n->nameN = l; return 0;
}
static int s_prefix(lua_State *L) {
  Node *n = self_node(L, 1);
  if (lua_isnil(L, 2)) { dfree(n->prefix); n->prefix = NULL; n->prefixN = 0; return 0; }
  size_t l; const char *s = rstr(L, 2, &l, "Box<str>");
  name_error(L, s, l);
  char *p = dstrdup(s, l); if (!p) return memerr(L);
  dfree(n->prefix); n->prefix = p; n->prefixN = l; return 0;
}
static void push_attrs(lua_State *L, Node *el, int raw) {
  UdAttrs *u = lua_newuserdata(L, sizeof(UdAttrs)); u->el = el; u->raw = raw; ref(el);
  luaL_setmetatable(L, MT_ATTRS);
}
static int g_attrs(lua_State *L) { push_attrs(L, self_node(L, 1), 0); return 1; }
static int g_rawattrs(lua_State *L) { push_attrs(L, self_node(L, 1), 1); return 1; }
static int g_textContent(lua_State *L) {   /* every descendant Text node (not CDATA), document order */
  Node *n = self_node(L, 1);
  luaL_Buffer b; luaL_buffinit(L, &b);
  size_t cap = 32, depth = 1, steps = 0, lim = walkLimit();
  Kids *st = malloc(cap * sizeof(Kids)); if (!st) return memerr(L);
  st[0] = kids_of(n);
  while (depth) {
    Node *c = kids_next(&st[depth - 1]);
    if (!c) { depth--; continue; }
    if (++steps > lim) { free(st); return fail(L, "%s", LOOP_MSG); }
    if (c->kind == K_TEXT) luaL_addlstring(&b, c->content, c->contentN);
    else if (c->kind == K_ELEMENT) {
      if (depth == cap) { cap *= 2; Kids *q = realloc(st, cap * sizeof(Kids)); if (!q) { free(st); return memerr(L); } st = q; }
      st[depth++] = kids_of(c);
    }
  }
  free(st);
  luaL_pushresult(&b); return 1;
}
static int s_textContent(lua_State *L) {
  Node *n = self_node(L, 1); size_t l; const char *s = rstr(L, 2, &l, "String");
  Node *t = text_new(K_TEXT, s, l); if (!t) return memerr(L);
  if (!remove_children(n)) return fail(L, "%s", LOOP_MSG);
  append_child(n, t); return 0;
}
static int m_prepend(lua_State *L) {
  Node *self = self_node(L, 1); check_insert_args(L, 2);
  for (int i = lua_gettop(L); i >= 2; i--) { Node *n = insert_arg(L, i); check_insertion(L, self, n, i - 1, "Element:prepend"); prepend_child(self, n); }
  return 0;
}
static int m_append(lua_State *L) {
  Node *self = self_node(L, 1); check_insert_args(L, 2);
  for (int i = 2; i <= lua_gettop(L); i++) { Node *n = insert_arg(L, i); check_insertion(L, self, n, i - 1, "Element:append"); append_child(self, n); }
  return 0;
}
static int m_firstElementChild(lua_State *L) {
  Kids k = kids_of(self_node(L, 1)); Node *c; size_t steps = 0, lim = walkLimit();
  while ((c = kids_next(&k)) && c->kind != K_ELEMENT) if (++steps > lim) return fail(L, "%s", LOOP_MSG);
  CHARGE(steps); push_node(L, c); return 1;
}
static int m_lastElementChild(lua_State *L) {
  Kids k = kids_of(self_node(L, 1)); Node *c; size_t steps = 0, lim = walkLimit();
  while ((c = kids_back(&k)) && c->kind != K_ELEMENT) if (++steps > lim) return fail(L, "%s", LOOP_MSG);
  CHARGE(steps); push_node(L, c); return 1;
}
/* ftlman registers `firstChild` twice; the second registration (walking from the end) is the one scripts get. */
static int m_firstChild(lua_State *L) {
  Kids k = kids_of(self_node(L, 1)); Node *c; size_t steps = 0, lim = walkLimit();
  while ((c = kids_back(&k)) && !is_lua_node(c)) if (++steps > lim) return fail(L, "%s", LOOP_MSG);
  CHARGE(steps); push_node(L, c); return 1;
}
static void push_kids(lua_State *L, Node *el, const char *mt) {
  UdKids *u = lua_newuserdata(L, sizeof(UdKids)); u->k = kids_of(el); u->hold[0] = u->k.first; u->hold[1] = u->k.last;
  ref(u->hold[0]); ref(u->hold[1]); luaL_setmetatable(L, mt);
}
static int m_children(lua_State *L) { push_kids(L, self_node(L, 1), MT_KIDS); return 1; }
static int m_childNodes(lua_State *L) { push_kids(L, self_node(L, 1), MT_NODES); return 1; }
static int kids_call(lua_State *L, const char *mt, int elementsOnly) {
  UdKids *u = luaL_checkudata(L, 1, mt); Node *c; size_t steps = 0, lim = walkLimit();
  while ((c = kids_next(&u->k)) && (elementsOnly ? c->kind != K_ELEMENT : !is_lua_node(c))) if (++steps > lim) return fail(L, "%s", LOOP_MSG);
  CHARGE(steps);
  /* keep the iterator's next node alive (it may have been detached since) */
  unref(u->hold[0]); u->hold[0] = u->k.first; ref(u->hold[0]);
  push_node(L, c); return 1;
}
static int kids_call_elements(lua_State *L) { return kids_call(L, MT_KIDS, 1); }
static int kids_call_nodes(lua_State *L) { return kids_call(L, MT_NODES, 0); }
static void element_tostring(Sb *b, const Node *n) {
  sb_c(b, '<'); if (n->prefix) { sb_add(b, n->prefix, n->prefixN); sb_c(b, ':'); } sb_add(b, n->name, n->nameN);
  for (size_t i = 0; i < n->nattrs; i++) { sb_c(b, ' '); rust_debug_str(b, n->attrs[i].k, n->attrs[i].kn); sb_c(b, '='); rust_debug_str(b, n->attrs[i].v, n->attrs[i].vn); }
  if (!n->first) sb_s(b, "/>");
  else { sb_s(b, ">...</"); if (n->prefix) { sb_add(b, n->prefix, n->prefixN); sb_c(b, ':'); } sb_add(b, n->name, n->nameN); sb_c(b, '>'); }
}
static int mm_tostring_element(lua_State *L) { Sb b = {0}; element_tostring(&b, self_node(L, 1)); lua_pushlstring(L, b.p ? b.p : "", b.n); free(b.p); return 1; }
/* ---- Text */
static Node *self_text(lua_State *L) { UdNode *u = luaL_testudata(L, 1, MT_TEXT); if (!u) fail(L, "bad argument #1 (expected Text, got %s)", luaL_typename(L, 1)); return u->n; }
static int g_content(lua_State *L) { Node *n = self_text(L); lua_pushlstring(L, n->content, n->contentN); return 1; }
static int s_content(lua_State *L) {
  Node *n = self_text(L); size_t l; const char *s = rstr(L, 2, &l, "String");
  char *p = dstrdup(s, l); if (!p) return memerr(L);
  dfree(n->content); n->content = p; n->contentN = l; return 0;
}
static int mm_tostring_text(lua_State *L) { Node *n = self_text(L); Sb b = {0}; sb_s(&b, "#text "); rust_debug_str(&b, n->content, n->contentN); lua_pushlstring(L, b.p, b.n); free(b.p); return 1; }
/* ---- Document */
static int g_root(lua_State *L) { UdNode *u = luaL_checkudata(L, 1, MT_DOC); push_node(L, u->n); return 1; }
/* ---- Attributes (LuaAttributes): typed values unless raw */
static void push_attr_value(lua_State *L, const char *v, size_t n, int raw) {
  if (!raw) {
    if (n == 4 && !memcmp(v, "true", 4)) { lua_pushboolean(L, 1); return; }
    if (n == 5 && !memcmp(v, "false", 5)) { lua_pushboolean(L, 0); return; }
    lua_Integer i; double d;
    if (rust_parse_i64(v, n, &i)) { lua_pushinteger(L, i); return; }
    if (rust_parse_f64(v, n, &d)) { lua_pushnumber(L, d); return; }
  }
  lua_pushlstring(L, v, n);
}
static int attrs_iter(lua_State *L) {
  UdAttrs *u = lua_touserdata(L, lua_upvalueindex(1));
  if (lua_isnil(L, lua_upvalueindex(2)) || !u->el) { lua_pushnil(L); return 1; }
  size_t kl; const char *k = lua_tolstring(L, lua_upvalueindex(2), &kl);
  int found; size_t i = attr_find(u->el, k, kl, &found);
  if (i >= u->el->nattrs) { lua_pushnil(L); lua_replace(L, lua_upvalueindex(2)); lua_pushnil(L); return 1; }
  const Attr *a = &u->el->attrs[i];
  lua_pushlstring(L, a->k, a->kn);
  push_attr_value(L, a->v, a->vn, u->raw);
  if (i + 1 < u->el->nattrs) lua_pushlstring(L, u->el->attrs[i + 1].k, u->el->attrs[i + 1].kn); else lua_pushnil(L);
  lua_replace(L, lua_upvalueindex(2));
  return 2;
}
static int attrs_call(lua_State *L) {
  UdAttrs *u = luaL_checkudata(L, 1, MT_ATTRS);
  UdNode *e = luaL_testudata(L, 2, MT_ELEMENT);
  if (!e) return fail(L, "error converting Lua %s to userdata", luaL_typename(L, 2));
  if (e->n != u->el) return fail(L, "LuaAttributes called with mismatched self pointer");
  lua_pushvalue(L, 1);
  if (u->el->nattrs) lua_pushlstring(L, u->el->attrs[0].k, u->el->attrs[0].kn); else lua_pushnil(L);
  lua_pushcclosure(L, attrs_iter, 2);
  return 1;
}
static int attrs_index(lua_State *L) {
  UdAttrs *u = luaL_checkudata(L, 1, MT_ATTRS);
  int t = lua_type(L, 2);
  if (t != LUA_TSTRING && t != LUA_TNUMBER) return fail(L, "error converting Lua %s to string", luaL_typename(L, 2));
  if (t == LUA_TNUMBER) { lua_pushvalue(L, 2); lua_tolstring(L, -1, NULL); lua_replace(L, 2); }
  size_t kl; const char *k = lua_tolstring(L, 2, &kl);
  if (!utf8_valid(k, kl)) return 0;
  int found; size_t i = attr_find(u->el, k, kl, &found);
  if (!found) return 0;
  push_attr_value(L, u->el->attrs[i].v, u->el->attrs[i].vn, u->raw);
  return 1;
}
static int attrs_newindex(lua_State *L) {
  UdAttrs *u = luaL_checkudata(L, 1, MT_ATTRS);
  size_t kl; const char *k = rstr(L, 2, &kl, "Box<str>");
  name_error(L, k, kl);
  int t = lua_type(L, 3);
  if (t == LUA_TNIL) { attr_remove(u->el, k, kl); return 0; }
  Sb b = {0}; const char *v; size_t vl;
  if (u->raw || t == LUA_TSTRING) {
    if (t != LUA_TSTRING) return fail(L, "cannot assign %s to raw element attribute", t == LUA_TNUMBER ? (lua_isinteger(L, 3) ? "integer" : "number") : luaL_typename(L, 3));
    v = lua_tolstring(L, 3, &vl);
    if (!utf8_valid(v, vl)) return fail(L, "invalid UTF-8 assigned to attribute");
  } else if (t == LUA_TBOOLEAN) { v = lua_toboolean(L, 3) ? "true" : "false"; vl = strlen(v); }
  else if (t == LUA_TNUMBER && lua_isinteger(L, 3)) { char tmp[32]; snprintf(tmp, sizeof tmp, LUA_INTEGER_FMT, (LUAI_UACINT)lua_tointeger(L, 3)); sb_s(&b, tmp); v = b.p; vl = b.n; }
  else if (t == LUA_TNUMBER) { ryu_format(&b, lua_tonumber(L, 3)); v = b.p; vl = b.n; }
  else return fail(L, "cannot assign %s to element attribute", luaL_typename(L, 3));
  int ok = attr_set(u->el, k, kl, v, vl);
  free(b.p);
  return ok ? 0 : memerr(L);
}

/* ---- mod.vfs filesystems (ftlman's LuaFS userdata; the semantics are the host's) */
enum { FS_STAT, FS_LS, FS_READ, FS_WRITE };
static int fs_op(lua_State *L, int op) {
  UdFs *u = luaL_checkudata(L, 1, MT_FS);
  if (u->token != fsToken) return fail(L, "attempt to use a filesystem outside of its patch script");
  size_t pl; const char *path = rstr(L, 2, &pl, "String");
  size_t dl = 0; const char *data = NULL;
  if (op == FS_WRITE) { data = rstr(L, 3, &dl, NULL); if (!data) { if (lua_type(L, 3) == LUA_TSTRING) data = lua_tolstring(L, 3, &dl); else return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, 3)); } }
  if (!pl || path[0] != '/') return fail(L, "paths must start with '/'");
  if (!mp_buf_grow((int)(pl + dl))) return memerr(L);
  memcpy(buf, path + 1, pl - 1); if (dl) memcpy(buf + pl - 1, data, dl);
  int r = host_fs(u->fs, op, (int)pl - 1, (int)dl);
  if (r <= -2) { lua_pushlstring(L, (const char *)buf, (size_t)(-2 - r)); return lua_error(L); }
  if (op == FS_WRITE) return 0;
  if (op == FS_READ) { lua_pushlstring(L, (const char *)buf, (size_t)r); return 1; }
  if (op == FS_STAT) {
    if (r < 0) { lua_pushnil(L); return 1; }
    lua_newtable(L);
    if (r > 1 && buf[0] == 'f') { lua_Integer len = 0; for (int i = 1; i < r; i++) len = len * 10 + (buf[i] - '0'); lua_pushinteger(L, len); lua_setfield(L, -2, "length"); }
    lua_pushstring(L, buf[0] == 'f' ? "file" : buf[0] == 'd' ? "dir" : "other"); lua_setfield(L, -2, "type");
    return 1;
  }
  /* ls: {type byte, u32 length, name}... */
  lua_newtable(L);
  size_t p = 0; lua_Integer i = 1;
  while (p + 5 <= (size_t)r) {
    uint8_t t = buf[p]; uint32_t l = rd32(buf + p + 1); p += 5;
    if (p + l > (size_t)r) break;
    lua_createtable(L, 0, 2);
    lua_pushlstring(L, (const char *)buf + p, l); lua_setfield(L, -2, "filename");
    lua_pushstring(L, t == 'f' ? "file" : t == 'd' ? "dir" : "other"); lua_setfield(L, -2, "type");
    lua_rawseti(L, -2, i++); p += l;
  }
  return 1;
}
static int fs_stat(lua_State *L) { return fs_op(L, FS_STAT); }
static int fs_ls(lua_State *L) { return fs_op(L, FS_LS); }
static int fs_read(lua_State *L) { return fs_op(L, FS_READ); }
static int fs_write(lua_State *L) { return fs_op(L, FS_WRITE); }

/* ---------------------------------------------------------------- mod.xml */
/* BTreeMap<Box<str>, Box<str>> from a table; 0 (nothing pushed) when a key or value is not string-like. */
typedef struct { Attr *a; size_t n; } AttrList;
static void attrlist_free(AttrList *l) { for (size_t i = 0; i < l->n; i++) { free(l->a[i].k); free(l->a[i].v); } free(l->a); l->a = NULL; l->n = 0; }
static int copy_strlike(lua_State *L, int i, char **p, size_t *n) {
  int t = lua_type(L, i); if (t != LUA_TSTRING && t != LUA_TNUMBER) return 0;
  lua_pushvalue(L, i); size_t l; const char *s = lua_tolstring(L, -1, &l);
  if (!utf8_valid(s, l)) { lua_pop(L, 1); return 0; }
  *p = malloc(l + 1); if (!*p) { lua_pop(L, 1); return 0; }
  memcpy(*p, s, l); (*p)[l] = 0; *n = l; lua_pop(L, 1); return 1;
}
static int table_attrs(lua_State *L, int t, AttrList *out) {
  out->a = NULL; out->n = 0; size_t cap = 0;
  t = lua_absindex(L, t);
  lua_pushnil(L);
  while (lua_next(L, t)) {
    char *k, *v; size_t kn, vn;
    if (!copy_strlike(L, -2, &k, &kn)) { lua_pop(L, 2); attrlist_free(out); return 0; }
    if (!copy_strlike(L, -1, &v, &vn)) { free(k); lua_pop(L, 2); attrlist_free(out); return 0; }
    lua_pop(L, 1);
    size_t j = 0; while (j < out->n && bytecmp(out->a[j].k, out->a[j].kn, k, kn) < 0) j++;
    if (j < out->n && bytecmp(out->a[j].k, out->a[j].kn, k, kn) == 0) { free(out->a[j].v); free(k); out->a[j].v = v; out->a[j].vn = vn; continue; }
    if (out->n == cap) { cap = cap ? cap * 2 : 8; Attr *q = realloc(out->a, cap * sizeof(Attr)); if (!q) { free(k); free(v); lua_pop(L, 1); attrlist_free(out); return 0; } out->a = q; }
    memmove(out->a + j + 1, out->a + j, (out->n - j) * sizeof(Attr));
    out->a[j].k = k; out->a[j].kn = kn; out->a[j].v = v; out->a[j].vn = vn; out->n++;
  }
  return 1;
}
static int x_element(lua_State *L) {
  /* (prefix: Option<Box<str>>, name: Box<str>, attrs: Option<map>), else (name: Box<str>, attrs: Option<map>) */
  char *pre = NULL, *name = NULL; size_t pn = 0, nn = 0; AttrList al = {0}; int ok = 0;
  int t1 = lua_type(L, 1), t3 = lua_type(L, 3);
  if (t1 == LUA_TNIL || t1 == LUA_TNONE || copy_strlike(L, 1, &pre, &pn)) {
    if (copy_strlike(L, 2, &name, &nn)) {
      if (t3 == LUA_TNIL || t3 == LUA_TNONE) ok = 1;
      else if (t3 == LUA_TTABLE && table_attrs(L, 3, &al)) ok = 1;
    }
  }
  if (!ok) {
    free(pre); free(name); pre = name = NULL; pn = nn = 0; attrlist_free(&al);
    int t2 = lua_type(L, 2);
    if (!copy_strlike(L, 1, &name, &nn)) return fail(L, "error converting Lua %s to Box<str>", luaL_typename(L, 1));
    if (t2 != LUA_TNIL && t2 != LUA_TNONE && !(t2 == LUA_TTABLE && table_attrs(L, 2, &al))) { free(name); return fail(L, "error converting Lua %s to BTreeMap", luaL_typename(L, 2)); }
  }
  Sb msg = {0};
  if (!(pre && name_msg(&msg, pre, pn)) && !name_msg(&msg, name, nn))
    for (size_t i = 0; i < al.n; i++) if (name_msg(&msg, al.a[i].k, al.a[i].kn)) break;
  Node *e = msg.n ? NULL : element_new(pre, pn, name, nn);
  free(pre); free(name);
  if (msg.n) { attrlist_free(&al); lua_pushlstring(L, msg.p, msg.n); free(msg.p); return lua_error(L); }
  if (!e) { attrlist_free(&al); return memerr(L); }
  for (size_t i = 0; i < al.n; i++) if (!attr_set(e, al.a[i].k, al.a[i].kn, al.a[i].v, al.a[i].vn)) { attrlist_free(&al); return memerr(L); }
  attrlist_free(&al);
  push_node(L, e); return 1;
}
/* Pushes the Lua values of top-level nodes built from the events in the buffer; returns their count. */
static int push_built(lua_State *L, size_t len) {
  Node **tops; size_t n;
  if (!build(0, len, &tops, &n)) return memerr(L);
  int count = 0;
  for (size_t i = 0; i < n; i++) if (is_lua_node(tops[i])) { luaL_checkstack(L, 1, "too many nodes"); push_node(L, tops[i]); count++; }
  free(tops);
  return count;
}
static int host_error(lua_State *L, int r) { lua_pushlstring(L, (const char *)buf, (size_t)(-1 - r)); return lua_error(L); }
static int x_parse(lua_State *L) {
  size_t xl; const char *x = rstr(L, 1, &xl, NULL);
  if (!x) { if (lua_type(L, 1) == LUA_TSTRING) x = lua_tolstring(L, 1, &xl); else return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, 1)); }
  int top = 0;
  if (!lua_isnoneornil(L, 2)) {
    luaL_checktype(L, 2, LUA_TTABLE);
    lua_pushnil(L);
    while (lua_next(L, 2)) {
      lua_pushvalue(L, -2); size_t kl; const char *k = (lua_type(L, -1) == LUA_TSTRING || lua_type(L, -1) == LUA_TNUMBER) ? lua_tolstring(L, -1, &kl) : NULL;
      if (k && kl == 20 && !memcmp(k, "allow_top_level_text", 20)) {
        if (lua_type(L, -2) != LUA_TBOOLEAN) return fail(L, "`allow_top_level_text` option value must be a boolean");
        top = lua_toboolean(L, -2);
      } else return k ? fail(L, "`%s` is not a valid parse option name", k) : fail(L, "error converting Lua %s to LuaString", luaL_typename(L, -1));
      lua_pop(L, 2);
    }
  }
  if (!utf8_valid(x, xl)) return fail(L, "input XML must be valid UTF-8");
  if (!mp_buf_grow((int)xl)) return memerr(L);
  memcpy(buf, x, xl);
  int r = host_xml_parse((int)xl, top);
  if (r < 0) return host_error(L, r);
  return push_built(L, (size_t)r);
}
static int x_stringify(lua_State *L) {
  int n = lua_gettop(L);
  check_insert_args(L, 1);
  Node **nodes = malloc((size_t)(n ? n : 1) * sizeof(Node *)); if (!nodes) return memerr(L);
  for (int i = 1; i <= n; i++) {
    Node *x = opt_node(L, i, 0);
    if (!x) { size_t l; const char *s = lua_tolstring(L, i, &l); x = text_new(K_TEXT, s, l); if (!x) { free(nodes); return memerr(L); } }
    nodes[i - 1] = x;
  }
  outLen = 0;
  for (int i = 0; i < n; i++) {
    int r = emit(nodes[i], 0);
    if (r <= 0) { free(nodes); return r < 0 ? fail(L, "%s", LOOP_MSG) : memerr(L); }
  }
  free(nodes);
  int r = host_xml_write((int)outLen);
  if (r < 0) return host_error(L, r);
  lua_pushlstring(L, (const char *)buf, (size_t)r); return 1;
}
static int x_append(lua_State *L) {
  size_t ll, ul; const char *lo, *up;
  lo = (lua_type(L, 1) == LUA_TSTRING || lua_type(L, 1) == LUA_TNUMBER) ? lua_tolstring(L, 1, &ll) : NULL;
  if (!lo) return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, 1));
  up = (lua_type(L, 2) == LUA_TSTRING || lua_type(L, 2) == LUA_TNUMBER) ? lua_tolstring(L, 2, &ul) : NULL;
  if (!up) return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, 2));
  int kind = 0;
  if (!lua_isnoneornil(L, 3)) {
    luaL_checktype(L, 3, LUA_TTABLE);
    lua_pushnil(L);
    while (lua_next(L, 3)) {
      lua_pushvalue(L, -2); size_t kl; const char *k = (lua_type(L, -1) == LUA_TSTRING || lua_type(L, -1) == LUA_TNUMBER) ? lua_tolstring(L, -1, &kl) : NULL;
      if (!k) return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, -1));
      if (kl == 4 && !memcmp(k, "type", 4)) {
        int vt = lua_type(L, -2);
        if (vt != LUA_TSTRING && vt != LUA_TNUMBER) return fail(L, "`type` option value must be a string");
        lua_pushvalue(L, -2); size_t vl; const char *v = lua_tolstring(L, -1, &vl);
        if (vl == 10 && !memcmp(v, "xml.append", 10)) kind = 0;
        else if (vl == 13 && !memcmp(v, "xml.rawappend", 13)) kind = 1;
        else return fail(L, "`%s` is not a valid append type", v);
        lua_pop(L, 1);
      } else return fail(L, "`%s` is not a valid append option name", k);
      lua_pop(L, 2);
    }
  }
  if (!utf8_valid(lo, ll)) return fail(L, "Failed to decode document");
  if (!utf8_valid(up, ul)) return fail(L, "Failed to decode patch");
  if (!mp_buf_grow((int)(ll + ul))) return memerr(L);
  memcpy(buf, lo, ll); memcpy(buf + ll, up, ul);
  int r = host_xml_append((int)ll, (int)ul, kind);
  if (r < 0) return host_error(L, r);
  lua_pushlstring(L, (const char *)buf, (size_t)r); return 1;
}

/* ---------------------------------------------------------------- mod.debug (pretty printer, _compare) */
typedef struct { int recursive; int colors; const char *indent; size_t indentN; unsigned level; int seen; Sb out; int depth; } Pretty;
static const char *C_PTR = "\x1b[38;5;227m", *C_ERR = "\x1b[38;5;196m", *C_RESET = "\x1b[0m";
static const char *type_name(lua_State *L, int i) {
  switch (lua_type(L, i)) {
    case LUA_TNIL: return "nil"; case LUA_TBOOLEAN: return "boolean"; case LUA_TLIGHTUSERDATA: return "lightuserdata";
    case LUA_TNUMBER: return lua_isinteger(L, i) ? "integer" : "number"; case LUA_TSTRING: return "string";
    case LUA_TTABLE: return "table"; case LUA_TFUNCTION: return "function"; case LUA_TTHREAD: return "thread";
    case LUA_TUSERDATA: return "userdata"; default: return "other";
  }
}
static const char *start_value(lua_State *L, int i) {
  switch (lua_type(L, i)) {
    case LUA_TNIL: case LUA_TBOOLEAN: return "\x1b[38;5;165m";
    case LUA_TNUMBER: return "\x1b[38;5;33m";
    case LUA_TSTRING: return "\x1b[38;5;40m";
    case LUA_TLIGHTUSERDATA: case LUA_TFUNCTION: case LUA_TTHREAD: return C_PTR;
    default: return "";
  }
}
static void pretty_ptr(Sb *b, const char *type, const void *p) { char t[64]; snprintf(t, sizeof t, "%s@0x%lx", type, (unsigned long)(uintptr_t)p); sb_s(b, t); }
static void pretty_rec(lua_State *L, Pretty *P, int v);
static void pretty_indent(Pretty *P) { for (unsigned i = 0; i < P->level; i++) sb_add(&P->out, P->indent, P->indentN); }
static void table_pairs(lua_State *L, Pretty *P, int t, int *first) {
  lua_pushnil(L);
  while (lua_next(L, t)) {
    if (!*first) sb_c(&P->out, ','); else { sb_c(&P->out, '{'); *first = 0; P->level++; }
    if (P->indent) { sb_c(&P->out, '\n'); pretty_indent(P); } else sb_c(&P->out, ' ');
    sb_c(&P->out, '['); pretty_rec(L, P, lua_gettop(L) - 1); sb_c(&P->out, ']');
    sb_s(&P->out, " = "); pretty_rec(L, P, lua_gettop(L));
    lua_pop(L, 1);
  }
}
static void pretty_rec(lua_State *L, Pretty *P, int v) {
  v = lua_absindex(L, v);
  if (++P->depth > 200) fail(L, "value too deeply nested to print");
  luaL_checkstack(L, 8, "pretty_string");
  const void *ptr = lua_type(L, v) == LUA_TSTRING ? NULL : lua_topointer(L, v);
  Sb *b = &P->out;
  if (ptr) {
    lua_pushlightuserdata(L, (void *)ptr);
    if (lua_rawget(L, P->seen) != LUA_TNIL) {
      lua_pop(L, 1);
      if (P->colors) sb_s(b, C_PTR);
      pretty_ptr(b, type_name(L, v), ptr);
      if (P->colors) sb_s(b, C_RESET);
      P->depth--; return;
    }
    lua_pop(L, 1); lua_pushlightuserdata(L, (void *)ptr); lua_pushboolean(L, 1); lua_rawset(L, P->seen);
  }
  if (P->colors) sb_s(b, start_value(L, v));
  char t[64];
  switch (lua_type(L, v)) {
    case LUA_TNIL: sb_s(b, "nil"); break;
    case LUA_TBOOLEAN: sb_s(b, lua_toboolean(L, v) ? "true" : "false"); break;
    case LUA_TNUMBER:
      if (lua_isinteger(L, v)) { snprintf(t, sizeof t, LUA_INTEGER_FMT, (LUAI_UACINT)lua_tointeger(L, v)); sb_s(b, t); }
      else rust_display_f64(b, lua_tonumber(L, v));
      break;
    case LUA_TSTRING: { size_t l; const char *s = lua_tolstring(L, v, &l); rust_debug_str(b, s, l); break; }
    case LUA_TTABLE:
      if (!P->recursive) { if (P->colors) sb_s(b, C_PTR); pretty_ptr(b, "table", ptr); }
      else {
        int first = 1;
        table_pairs(L, P, v, &first);
        if (lua_getmetatable(L, v)) {
          lua_pushliteral(L, "__index");
          if (lua_rawget(L, -2) == LUA_TTABLE) table_pairs(L, P, lua_gettop(L), &first);
          lua_pop(L, 2);
        }
        if (first) sb_c(b, '{');
        else { P->level--; if (P->indent) { sb_c(b, '\n'); pretty_indent(P); } else sb_c(b, ' '); }
        sb_c(b, '}');
      }
      break;
    case LUA_TLIGHTUSERDATA: case LUA_TFUNCTION: case LUA_TTHREAD: pretty_ptr(b, type_name(L, v), ptr); break;
    case LUA_TUSERDATA: {
      lua_getglobal(L, "tostring");
      lua_pushvalue(L, v);
      if (lua_pcall(L, 1, 1, 0) == LUA_OK && lua_type(L, -1) == LUA_TSTRING) { size_t l; const char *s = lua_tolstring(L, -1, &l); sb_add(b, s, l); }
      else {
        pretty_ptr(&P->out, "failed to stringify user data", ptr); sb_s(b, ": ");
        if (P->colors) sb_s(b, C_ERR);
        size_t l; const char *s = lua_tolstring(L, -1, &l); rust_debug_str(b, s ? s : "", s ? l : 0);
      }
      lua_pop(L, 1);
      break;
    }
    default: pretty_ptr(b, "unknown", ptr);
  }
  if (P->colors) sb_s(b, C_RESET);
  P->depth--;
}
/* PrettyPrintOptions (serde, deny_unknown_fields): recursive (bool), colors ("ansi"), indent (string). */
static void pretty_options(lua_State *L, int o, Pretty *P, const char *fn) {
  if (lua_type(L, o) != LUA_TTABLE) fail(L, "failed to deserialize argument #2 to %s: invalid type: %s, expected struct PrettyPrintOptions", fn, luaL_typename(L, o));
  lua_pushnil(L);
  while (lua_next(L, o)) {
    const char *k = lua_type(L, -2) == LUA_TSTRING ? lua_tostring(L, -2) : NULL;
    if (k && !strcmp(k, "recursive")) {
      if (lua_type(L, -1) != LUA_TBOOLEAN) fail(L, "failed to deserialize argument #2 to %s: invalid type for `recursive`, expected a boolean", fn);
      P->recursive = lua_toboolean(L, -1);
    } else if (k && !strcmp(k, "colors")) {
      if (lua_type(L, -1) != LUA_TSTRING || strcmp(lua_tostring(L, -1), "ansi")) fail(L, "failed to deserialize argument #2 to %s: unknown variant, expected `ansi`", fn);
      P->colors = 1;
    } else if (k && !strcmp(k, "indent")) {
      if (lua_type(L, -1) != LUA_TSTRING) fail(L, "failed to deserialize argument #2 to %s: invalid type for `indent`, expected a string", fn);
      P->indent = lua_tolstring(L, -1, &P->indentN);   /* kept alive by the options table */
    } else fail(L, "failed to deserialize argument #2 to %s: unknown field `%s`", fn, k ? k : luaL_typename(L, -2));
    lua_pop(L, 1);
  }
}
static int pretty(lua_State *L, int print) {
  lua_settop(L, 2);
  Pretty P = {1, 0, NULL, 0, 0, 0, {0}, 0};
  if (lua_isnil(L, 2)) { if (print) { P.indent = "\t"; P.indentN = 1; } }
  else pretty_options(L, 2, &P, print ? "mod.debug.pretty_print" : "mod.debug.pretty_string");
  lua_newtable(L); P.seen = lua_gettop(L);
  pretty_rec(L, &P, 1);
  if (print) { host_log(1, P.out.p ? P.out.p : "", (int)P.out.n); free(P.out.p); return 0; }
  lua_pushlstring(L, P.out.p ? P.out.p : "", P.out.n); free(P.out.p);
  return 1;
}
static int d_pretty_string(lua_State *L) { return pretty(L, 0); }
static int d_pretty_print(lua_State *L) { return pretty(L, 1); }
static int compare(lua_State *L, int a, int b, int depth) {   /* debug.rs Comparer::compare */
  a = lua_absindex(L, a); b = lua_absindex(L, b);
  if (depth > 200) fail(L, "values too deeply nested to compare");
  luaL_checkstack(L, 8, "compare");
  int ta = lua_type(L, a), tb = lua_type(L, b);
  if (ta != tb) return 0;
  switch (ta) {
    case LUA_TNIL: return 1;
    case LUA_TBOOLEAN: return lua_toboolean(L, a) == lua_toboolean(L, b);
    case LUA_TNUMBER:
      if (lua_isinteger(L, a) != lua_isinteger(L, b)) return 0;
      if (lua_isinteger(L, a)) return lua_tointeger(L, a) == lua_tointeger(L, b);
      return lua_tonumber(L, a) == lua_tonumber(L, b);
    case LUA_TSTRING: return lua_rawequal(L, a, b);
    case LUA_TTABLE: {
      size_t countA = 0, compared = 0;
      lua_pushnil(L); while (lua_next(L, a)) { countA++; lua_pop(L, 1); }
      lua_pushnil(L);
      while (lua_next(L, b)) {
        lua_pushvalue(L, -2); lua_rawget(L, a);
        int eq = compare(L, -2, -1, depth + 1);
        lua_pop(L, 2);
        if (!eq) { lua_pop(L, 1); return 0; }
        compared++;
      }
      return countA == compared;
    }
    default: return lua_topointer(L, a) == lua_topointer(L, b);
  }
}
static int d_compare(lua_State *L) { lua_settop(L, 2); lua_pushboolean(L, compare(L, 1, 2, 0)); return 1; }

/* ---------------------------------------------------------------- mod.util.eval, mod.meta */
static int eval_call(lua_State *L) { lua_call(L, 0, LUA_MULTRET); return lua_gettop(L); }
static int u_eval(lua_State *L) {
  size_t cl; const char *code = (lua_type(L, 1) == LUA_TSTRING || lua_type(L, 1) == LUA_TNUMBER) ? lua_tolstring(L, 1, &cl) : NULL;
  if (!code) return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, 1));
  char name[200] = "=[eval]";
  lua_Debug ar;
  if (lua_getstack(L, 1, &ar) && lua_getinfo(L, "Sl", &ar)) snprintf(name, sizeof name, "=[eval@%s:%d]", ar.short_src, ar.currentline);
  const char *path = NULL; size_t pathN = 0;
  lua_settop(L, 2); lua_pushnil(L); lua_pushnil(L);   /* 3: env, 4: path (kept alive) */
  if (!lua_isnil(L, 2)) {
    luaL_checktype(L, 2, LUA_TTABLE);
    lua_pushnil(L);
    while (lua_next(L, 2)) {   /* key 5, value 6 */
      lua_pushvalue(L, 5); size_t kl; const char *k = (lua_type(L, 7) == LUA_TSTRING || lua_type(L, 7) == LUA_TNUMBER) ? lua_tolstring(L, 7, &kl) : NULL;
      if (!k) return fail(L, "error converting Lua %s to LuaString", luaL_typename(L, 7));
      if (kl == 3 && !memcmp(k, "env", 3)) {
        if (lua_type(L, 6) != LUA_TTABLE) return fail(L, "`env` option value must be a table");
        lua_pushvalue(L, 6); lua_replace(L, 3);
      } else if (kl == 4 && !memcmp(k, "name", 4)) {
        size_t nl; const char *n = rstr(L, 6, &nl, "LuaString");
        if (nl + 1 > 128) return fail(L, "`name` option value is too long");
        snprintf(name, sizeof name, "=%.*s", (int)nl, n);
      } else if (kl == 4 && !memcmp(k, "path", 4)) {
        path = rstr(L, 6, &pathN, "LuaString");
        if (pathN > 128) return fail(L, "`path` option value is too long");
        lua_pushvalue(L, 6); lua_replace(L, 4);
      } else return fail(L, "`%s` is not a valid eval option name", k);
      lua_pop(L, 2);
    }
  }
  if (lua_isnil(L, 3)) return fail(L, "`env` option missing");
  lua_settop(L, 4);
  /* mlua's Chunk::eval: as an expression ("return " .. code) if that compiles, else as statements */
  luaL_Buffer b; luaL_buffinit(L, &b); luaL_addstring(&b, "return "); luaL_addlstring(&b, code, cl); luaL_pushresult(&b);
  size_t el; const char *expr = lua_tolstring(L, -1, &el);
  if (luaL_loadbufferx(L, expr, el, name, "t") != LUA_OK) {
    lua_pop(L, 2);
    if (luaL_loadbufferx(L, code, cl, name, "t") != LUA_OK) return lua_error(L);
  } else lua_remove(L, -2);
  lua_pushvalue(L, 3); lua_setupvalue(L, -2, 1);
  char *saved = currentFile;
  currentFile = NULL;
  if (path && (currentFile = malloc(pathN + 1))) { memcpy(currentFile, path, pathN); currentFile[pathN] = 0; }
  int base = lua_gettop(L) - 1;
  lua_pushcfunction(L, eval_call); lua_insert(L, -2);
  int st = lua_pcall(L, 1, LUA_MULTRET, 0);
  free(currentFile); currentFile = saved;
  if (st != LUA_OK) return lua_error(L);
  return lua_gettop(L) - base;
}
static int meta_index(lua_State *L) {
  if (lua_type(L, 2) == LUA_TSTRING && !strcmp(lua_tostring(L, 2), "current_path")) { if (currentFile) lua_pushstring(L, currentFile); else lua_pushnil(L); return 1; }
  lua_pushvalue(L, 2); lua_rawget(L, lua_upvalueindex(1)); return 1;
}

/* ---------------------------------------------------------------- the runtime (ModLuaRuntime::new) */
static int prot_index(lua_State *L) { lua_pushvalue(L, 2); lua_rawget(L, lua_upvalueindex(1)); return 1; }
static int prot_newindex(lua_State *L) { return fail(L, "attempt to update a protected table"); }
static void protect(lua_State *L, int t) {   /* LuaExt::protect_table */
  t = lua_absindex(L, t);
  lua_newtable(L);
  lua_pushvalue(L, t); lua_pushcclosure(L, prot_index, 1); lua_setfield(L, -2, "__index");
  lua_pushcfunction(L, prot_newindex); lua_setfield(L, -2, "__newindex");
  lua_pushboolean(L, 1); lua_setfield(L, -2, "__metatable");
  lua_setmetatable(L, t);
}
static int overlay_newindex(lua_State *L) { lua_settop(L, 3); lua_rawset(L, lua_upvalueindex(1)); return 0; }
static void push_environment(lua_State *L) {   /* create_empty_environment: an overlay over the globals */
  lua_newtable(L);
  lua_newtable(L);
  lua_pushglobaltable(L); lua_setfield(L, -2, "__index");
  lua_pushvalue(L, -2); lua_pushcclosure(L, overlay_newindex, 1); lua_setfield(L, -2, "__newindex");
  lua_pushboolean(L, 1); lua_setfield(L, -2, "__metatable");
  lua_setmetatable(L, -2);
  lua_pushvalue(L, -1); lua_setfield(L, -2, "_G");   /* raw (the key does not exist yet, so __newindex: same effect) */
}
static char killText[160];
static void count_hook(lua_State *L, lua_Debug *ar);
/* The collection after a script. lua_sethook keeps the VM calling the hook while its finalizers run. An error in one (its
 * own, or stopped at the limit) ends that finalizer only, as in ftlman's Lua 5.4; Lua 5.3 raises it out of the collection. */
static int gc_collect(lua_State *L) { lua_gc(L, LUA_GCCOLLECT, 0); return 0; }
static void gc_bounded(lua_State *L) {
  lua_sethook(L, count_hook, LUA_MASKCOUNT, HOOK_STEP);
  for (int failed = 0;; failed++) {
    if (failed >= 1000) killed = 1;   /* finalizers that fail at once and make new ones */
    if (killed) lua_sethook(L, count_hook, LUA_MASKCOUNT, 1);
    lua_pushcfunction(L, gc_collect);
    if (lua_pcall(L, 0, 0, 0) == LUA_OK) break;
    lua_settop(L, 0);
  }
  lua_sethook(L, NULL, 0, 0);
}
static void count_hook(lua_State *L, lua_Debug *ar) {
  (void)ar;
  instrUsed += HOOK_STEP;
  if (killed || instrUsed > instrLimit) {
    if (!killed) { killed = 1; snprintf(killText, sizeof killText, "patch script exceeded the instruction limit (%.0f million instructions)", instrLimit / 1e6); }
    luaL_error(L, "%s", killText);
  }
}
/* pcall/xpcall that cannot swallow the instruction limit */
static int finish_pcall(lua_State *L, int status, int extra) {
  if (killed) { lua_pushstring(L, killText); return lua_error(L); }
  if (status != LUA_OK) { lua_pushboolean(L, 0); lua_pushvalue(L, -2); return 2; }
  return lua_gettop(L) - extra;
}
static int b_pcall(lua_State *L) {
  luaL_checkany(L, 1);
  lua_pushboolean(L, 1); lua_insert(L, 1);
  return finish_pcall(L, lua_pcall(L, lua_gettop(L) - 2, LUA_MULTRET, 0), 0);
}
static int b_xpcall(lua_State *L) {
  int n = lua_gettop(L);
  luaL_checktype(L, 2, LUA_TFUNCTION);
  lua_pushboolean(L, 1); lua_pushvalue(L, 1); lua_rotate(L, 3, 2);
  return finish_pcall(L, lua_pcall(L, n - 2, LUA_MULTRET, 2), 2);
}
static int b_load(lua_State *L) {   /* the base library's load, text chunks only */
  int n = lua_gettop(L);
  if (n < 2) lua_settop(L, 2);
  lua_pushvalue(L, lua_upvalueindex(1));
  lua_pushvalue(L, 1); lua_pushvalue(L, 2); lua_pushliteral(L, "t");
  if (n >= 4) lua_pushvalue(L, 4);
  int base = lua_gettop(L) - (n >= 4 ? 5 : 4);
  lua_call(L, n >= 4 ? 4 : 3, LUA_MULTRET);
  return lua_gettop(L) - base;
}
static int b_warn(lua_State *L) {   /* Lua 5.4's warn; ftlman installs no warning handler, so nothing is shown */
  int n = lua_gettop(L);
  luaL_checkstring(L, 1);
  for (int i = 2; i <= n; i++) luaL_checkstring(L, i);
  return 0;
}
/* ftlman's builtin Lua (src/lua/util.lua, iterutil.lua, table.lua, debug.lua), line for line: error positions match. */
static const char LIB_UTIL[] =
  "mod.util = {}\n"
  "\n"
  "function mod.util.readonly(table)\n"
  "  -- https://www.lua.org/pil/13.4.5.html\n"
  "  local proxy = {}\n"
  "  local mt = {\n"
  "    __index = table,\n"
  "    __newindex = function (t,k,v)\n"
  "      error(\"attempt to update a read-only table\", 2)\n"
  "    end\n"
  "  }\n"
  "  setmetatable(proxy, mt)\n"
  "  return proxy\n"
  "end\n";
static const char LIB_ITER[] =
  "mod.iter = {}\n"
  "\n"
  "function mod.iter.enumerate(iterator, start)\n"
  "  local i = start or 1\n"
  "  return function()\n"
  "    local values = table.pack(iterator())\n"
  "    if #values ~= 0 then\n"
  "      local tmp = i\n"
  "      i = i + 1\n"
  "      return tmp, table.unpack(values)\n"
  "    end\n"
  "  end\n"
  "end\n"
  "\n"
  "-- TODO: Don't assume fused iterators?\n"
  "--       Have a fuse() adapter maybe\n"
  "function mod.iter.zip(a, b)\n"
  "  return function()\n"
  "    local na = table.pack(a())\n"
  "    local nb = table.pack(b())\n"
  "    if #na == 0 or #nb == 0 then\n"
  "      return\n"
  "    end\n"
  "\n"
  "    return table.unpack(na), table.unpack(nb)\n"
  "  end\n"
  "end\n"
  "\n"
  "function mod.iter.collect(iterator)\n"
  "  local result = {}\n"
  "  for value in iterator do\n"
  "    result[#result + 1] = value\n"
  "  end\n"
  "  return result\n"
  "end\n"
  "\n"
  "local function pack2(...)\n"
  "  return {...}\n"
  "end\n"
  "\n"
  "function mod.iter._collectpack(iterator)\n"
  "  return mod.iter.collect(mod.iter.map(iterator, pack2))\n"
  "end\n"
  "\n"
  "function mod.iter.count(iterator)\n"
  "  local count = 0\n"
  "  for value in iterator do\n"
  "    count = count + 1\n"
  "  end\n"
  "  return count\n"
  "end\n"
  "\n"
  "function mod.iter.map(iterator, mapper)\n"
  "  return function()\n"
  "    local value = table.pack(iterator())\n"
  "    if #value ~= 0 then\n"
  "      return mapper(table.unpack(value))\n"
  "    end\n"
  "  end\n"
  "end\n";
static const char LIB_TABLE[] =
  "mod.table = {}\n"
  "\n"
  "function mod.table.iter_array(array)\n"
  "  local i = 0\n"
  "  local n = #array\n"
  "  return function()\n"
  "    i = i + 1\n"
  "    if i <= n then return array[i] end\n"
  "  end\n"
  "end\n"
  "\n"
  "function mod.table.compare_arrays(first, second)\n"
  "  local zipped = mod.iter.zip(mod.table.iter_array(first), mod.table.iter_array(second))\n"
  "  for i, a, b in mod.iter.enumerate(zipped) do\n"
  "    if a < b then\n"
  "      return -i\n"
  "    elseif a > b then\n"
  "      return i\n"
  "    end\n"
  "  end\n"
  "\n"
  "  local nfirst = #first\n"
  "  local nsecond = #second\n"
  "  if nfirst < nsecond then\n"
  "    return -(nfirst + 1)\n"
  "  elseif nfirst > nsecond then\n"
  "    return nsecond + 1\n"
  "  else\n"
  "    return 0\n"
  "  end\n"
  "end\n";
static const char LIB_DEBUG[] =
  "mod.debug = {}\n"
  "\n"
  "function mod.debug.assert_equal(a, b)\n"
  "  if type(a) ~= type(b) then\n"
  "    local message = \"assertion failed: mismatched types\"\n"
  "    message = message .. \"\\nlhs = \" .. type(a)\n"
  "    message = message .. \"\\nrhs = \" .. type(b)\n"
  "    error(message)\n"
  "  end\n"
  "\n"
  "  local function fail()\n"
  "      local message = \"assertion failed: lhs does not match rhs\"\n"
  "      message = message .. \"\\nlhs = \" .. mod.debug.pretty_string(a)\n"
  "      message = message .. \"\\nrhs = \" .. mod.debug.pretty_string(b)\n"
  "      error(message)\n"
  "  end\n"
  "\n"
  "  local equal = mod.debug._compare(a, b)\n"
  "  if not equal then\n"
  "    fail()\n"
  "  end\n"
  "end\n"
  "\n"
  "function mod.debug._assert_throws(fun)\n"
  "  local success = pcall(fun)\n"
  "  if success then\n"
  "    error(\"assertion failed: function didn't throw\")\n"
  "  end\n"
  "end\n";

static const Fn ELEMENT_GET[] = {{"type", g_type}, {"previousSibling", g_prev}, {"nextSibling", g_next}, {"parent", g_parent},
  {"name", g_name}, {"prefix", g_prefix}, {"attrs", g_attrs}, {"rawattrs", g_rawattrs}, {"textContent", g_textContent}, {NULL, NULL}};
static const Fn ELEMENT_SET[] = {{"name", s_name}, {"prefix", s_prefix}, {"textContent", s_textContent}, {NULL, NULL}};
static const Fn ELEMENT_METHODS[] = {{"as", m_as}, {"detach", m_detach}, {"before", m_before}, {"after", m_after}, {"clone", m_clone},
  {"prepend", m_prepend}, {"append", m_append}, {"firstElementChild", m_firstElementChild}, {"lastElementChild", m_lastElementChild},
  {"firstChild", m_firstChild}, {"children", m_children}, {"childNodes", m_childNodes}, {NULL, NULL}};
static const Fn ELEMENT_META[] = {{"__tostring", mm_tostring_element}, {"__gc", gc_node}, {NULL, NULL}};
static const Fn TEXT_GET[] = {{"type", g_type}, {"previousSibling", g_prev}, {"nextSibling", g_next}, {"parent", g_parent}, {"content", g_content}, {NULL, NULL}};
static const Fn TEXT_SET[] = {{"content", s_content}, {NULL, NULL}};
static const Fn TEXT_METHODS[] = {{"as", m_as}, {"detach", m_detach}, {"before", m_before}, {"after", m_after}, {"clone", m_clone}, {NULL, NULL}};
static const Fn TEXT_META[] = {{"__tostring", mm_tostring_text}, {"__gc", gc_node}, {NULL, NULL}};
static const Fn DOC_GET[] = {{"root", g_root}, {NULL, NULL}};
static const Fn GC_ONLY[] = {{"__gc", gc_node}, {NULL, NULL}};
static const Fn FS_METHODS[] = {{"stat", fs_stat}, {"ls", fs_ls}, {"read", fs_read}, {"write", fs_write}, {NULL, NULL}};
static const Fn XML_FNS[] = {{"element", x_element}, {"parse", x_parse}, {"stringify", x_stringify}, {"append", x_append}, {NULL, NULL}};
static int load_builtin(lua_State *L, const char *src, size_t len, const char *name) {
  if (luaL_loadbufferx(L, src, len, name, "t") != LUA_OK) return lua_error(L);
  lua_call(L, 0, 0); return 0;
}
/* Lua 5.4's string arithmetic (its lstrlib's string metamethods), which ftlman's Lua has: a numeric string converts
 * as the lexer reads it, so integers stay integers. ftlman-lvm.c sends string operands here. */
static int s_tonum(lua_State *L, int arg) {
  if (lua_type(L, arg) == LUA_TNUMBER) { lua_pushvalue(L, arg); return 1; }
  const char *s = lua_tolstring(L, arg, NULL);
  return s != NULL && lua_stringtonumber(L, s) == strlen(s) + 1;
}
static void s_trymt(lua_State *L, const char *mtname) {
  lua_settop(L, 2);
  if (lua_type(L, 2) == LUA_TSTRING || !luaL_getmetafield(L, 2, mtname))
    luaL_error(L, "attempt to %s a '%s' with a '%s'", mtname + 2, luaL_typename(L, -2), luaL_typename(L, -1));
  lua_insert(L, -3); lua_call(L, 2, 1);
}
static int s_arith(lua_State *L, int op, const char *mtname) {
  if (s_tonum(L, 1) && s_tonum(L, 2)) lua_arith(L, op); else s_trymt(L, mtname);
  return 1;
}
static int s_add(lua_State *L) { return s_arith(L, LUA_OPADD, "__add"); }
static int s_sub(lua_State *L) { return s_arith(L, LUA_OPSUB, "__sub"); }
static int s_mul(lua_State *L) { return s_arith(L, LUA_OPMUL, "__mul"); }
static int s_mod(lua_State *L) { return s_arith(L, LUA_OPMOD, "__mod"); }
static int s_pow(lua_State *L) { return s_arith(L, LUA_OPPOW, "__pow"); }
static int s_div(lua_State *L) { return s_arith(L, LUA_OPDIV, "__div"); }
static int s_idiv(lua_State *L) { return s_arith(L, LUA_OPIDIV, "__idiv"); }
static int s_unm(lua_State *L) { return s_arith(L, LUA_OPUNM, "__unm"); }
static const luaL_Reg STRING_ARITH[] = {
  {"__add", s_add}, {"__sub", s_sub}, {"__mul", s_mul}, {"__mod", s_mod}, {"__pow", s_pow}, {"__div", s_div},
  {"__idiv", s_idiv}, {"__unm", s_unm}, {NULL, NULL}
};
static int init_body(lua_State *L) {
  luaL_requiref(L, "_G", luaopen_base, 1); lua_pop(L, 1);
  luaL_requiref(L, LUA_TABLIBNAME, luaopen_table, 1); lua_pop(L, 1);
  luaL_requiref(L, LUA_STRLIBNAME, luaopen_string, 1); lua_pop(L, 1);
  lua_pushliteral(L, ""); lua_getmetatable(L, -1); luaL_setfuncs(L, STRING_ARITH, 0); lua_pop(L, 2);
  luaL_requiref(L, LUA_MATHLIBNAME, luaopen_math, 1); lua_pop(L, 1);
  lua_pushglobaltable(L); int g = lua_gettop(L);
  /* safety: text-only load, uncatchable limits; Lua 5.4's warn */
  lua_getfield(L, g, "load"); lua_pushcclosure(L, b_load, 1); lua_setfield(L, g, "load");
  lua_pushcfunction(L, b_pcall); lua_setfield(L, g, "pcall");
  lua_pushcfunction(L, b_xpcall); lua_setfield(L, g, "xpcall");
  lua_pushcfunction(L, b_warn); lua_setfield(L, g, "warn");
  const char *removed[] = {"dofile", "require", "collectgarbage", "loadfile", "rawset"};
  for (int i = 0; i < 5; i++) { lua_pushnil(L); lua_setfield(L, g, removed[i]); }
  const char *libs[] = {"string", "table", "math"};
  for (int i = 0; i < 3; i++) { lua_getfield(L, g, libs[i]); protect(L, -1); lua_pop(L, 1); }
  lua_pushnil(L); lua_setfield(L, g, "_G");
  new_type(L, MT_ELEMENT, ELEMENT_GET, ELEMENT_METHODS, ELEMENT_SET, ELEMENT_META);
  new_type(L, MT_TEXT, TEXT_GET, TEXT_METHODS, TEXT_SET, TEXT_META);
  new_type(L, MT_DOC, DOC_GET, NULL, NULL, GC_ONLY);
  static const Fn ATTRS_META[] = {{"__call", attrs_call}, {"__gc", gc_attrs}, {NULL, NULL}};
  luaL_newmetatable(L, MT_ATTRS);
  for (const Fn *f = ATTRS_META; f->name; f++) { lua_pushcfunction(L, f->f); lua_setfield(L, -2, f->name); }
  lua_pushcfunction(L, attrs_index); lua_setfield(L, -2, "__index");
  lua_pushcfunction(L, attrs_newindex); lua_setfield(L, -2, "__newindex");
  lua_pushboolean(L, 0); lua_setfield(L, -2, "__metatable"); lua_pop(L, 1);
  static const Fn KIDS_META[] = {{"__call", kids_call_elements}, {"__gc", gc_kids}, {NULL, NULL}};
  static const Fn NODES_META[] = {{"__call", kids_call_nodes}, {"__gc", gc_kids}, {NULL, NULL}};
  new_type(L, MT_KIDS, NULL, NULL, NULL, KIDS_META);
  new_type(L, MT_NODES, NULL, NULL, NULL, NODES_META);
  new_type(L, MT_FS, NULL, FS_METHODS, NULL, NULL);
  lua_newtable(L); int mod = lua_gettop(L);
  lua_pushvalue(L, mod); lua_rawsetp(L, LUA_REGISTRYINDEX, &modRef);
  lua_pushvalue(L, mod); lua_setfield(L, g, "mod");
  load_builtin(L, LIB_UTIL, sizeof LIB_UTIL - 1, "<BUILTIN>/util.lua");
  load_builtin(L, LIB_ITER, sizeof LIB_ITER - 1, "<BUILTIN>/iterutil.lua");
  load_builtin(L, LIB_TABLE, sizeof LIB_TABLE - 1, "<BUILTIN>/table.lua");
  load_builtin(L, LIB_DEBUG, sizeof LIB_DEBUG - 1, "<BUILTIN>/debug.lua");
  lua_pushnil(L);
  while (lua_next(L, mod)) { if (lua_type(L, -1) == LUA_TTABLE) protect(L, -1); lua_pop(L, 1); }
  fn_table(L, XML_FNS); protect(L, -1); lua_setfield(L, mod, "xml");
  lua_newtable(L); protect(L, -1);
  lua_getmetatable(L, -1); lua_pushvalue(L, -2); lua_pushcclosure(L, meta_index, 1); lua_setfield(L, -2, "__index"); lua_pop(L, 1);
  lua_setfield(L, mod, "meta");
  lua_getfield(L, mod, "debug");
  lua_pushliteral(L, "pretty_string"); lua_pushcfunction(L, d_pretty_string); lua_rawset(L, -3);
  lua_pushliteral(L, "pretty_print"); lua_pushcfunction(L, d_pretty_print); lua_rawset(L, -3);
  lua_pushliteral(L, "_compare"); lua_pushcfunction(L, d_compare); lua_rawset(L, -3);
  lua_pop(L, 1);
  lua_getfield(L, mod, "util");
  lua_pushliteral(L, "eval"); lua_pushcfunction(L, u_eval); lua_rawset(L, -3);
  lua_pop(L, 1);
  protect(L, mod);
  lua_settop(L, 0);
  return 0;
}

/* print (luaB_print through lua_writestring): whole lines to the host */
static Sb printLine;
void hs_writestring(const char *s, size_t l) {
  for (size_t i = 0; i < l; i++) {
    if (s[i] == '\n') { host_log(0, printLine.p ? printLine.p : "", (int)printLine.n); printLine.n = 0; }
    else sb_c(&printLine, s[i]);
  }
}
void hs_writeerror(const char *fmt, const char *p) { char t[1024]; int n = snprintf(t, sizeof t, fmt, p); if (n > 0) host_log(2, t, n < (int)sizeof t ? n : (int)sizeof t - 1); }

static int at_panic(lua_State *L) { const char *m = lua_tostring(L, -1); host_log(2, m ? m : "panic", m ? (int)strlen(m) : 5); return 0; }
static int put_error(const char *msg, size_t n) {
  if (!mp_buf_grow((int)n + 1)) { msg = "not enough memory"; n = strlen(msg); if (bufCap < n) return -1; }
  memcpy(buf, msg, n); return -(int)n - 1;
}

/* Creates the runtime; 0 or -(1 + error length). Limits: instructions per script, bytes for Lua and the trees. */
EXPORT(mp_init) int mp_init(double instructions, double memoryBytes) {
  if (instructions > 0) instrLimit = instructions;
  if (memoryBytes > 0) memLimit = (size_t)memoryBytes;
  if (!mp_buf_grow(65536)) return -1;
  L0 = lua_newstate(lua_alloc, NULL);
  if (!L0) return put_error("cannot create the Lua state", 27);
  lua_atpanic(L0, at_panic);
  lua_pushcfunction(L0, init_body);
  if (lua_pcall(L0, 0, 0, 0) != LUA_OK) { size_t n; const char *m = lua_tolstring(L0, -1, &n); return put_error(m ? m : "?", m ? n : 1); }
  return 0;
}

static int msgh(lua_State *L) {
  const char *m = lua_tostring(L, 1);
  if (!m) {
    if (luaL_callmeta(L, 1, "__tostring") && lua_type(L, -1) == LUA_TSTRING) m = lua_tostring(L, -1);
    else m = lua_pushfstring(L, "(error object is a %s value)", luaL_typename(L, 1));
  }
  luaL_traceback(L, L, m, 1);
  return 1;
}
typedef struct { const char *code; size_t codeN; const char *name; Node *root; const uint8_t *fs; size_t fsN; } RunArgs;
static RunArgs runArgs;
static int run_body(lua_State *L) {
  RunArgs *a = &runArgs;
  lua_pushcfunction(L, msgh); int h = lua_gettop(L);
  push_environment(L); int env = lua_gettop(L);
  if (a->root) {
    UdNode *d = lua_newuserdata(L, sizeof(UdNode)); d->n = a->root; ref(a->root); luaL_setmetatable(L, MT_DOC);
    lua_setfield(L, env, "document");
  }
  if (a->fs) {   /* mod.vfs: a protected table of filesystems, named in host order (with_filesystems) */
    lua_rawgetp(L, LUA_REGISTRYINDEX, &modRef);
    lua_pushliteral(L, "vfs"); lua_newtable(L);
    for (size_t p = 0, i = 0; p + 4 <= a->fsN; i++) {
      uint32_t l = rd32(a->fs + p); p += 4;
      if (p + l > a->fsN) break;
      lua_pushlstring(L, (const char *)a->fs + p, l); p += l;
      UdFs *u = lua_newuserdata(L, sizeof(UdFs)); u->fs = (int)i; u->token = fsToken; luaL_setmetatable(L, MT_FS);
      lua_rawset(L, -3);
    }
    protect(L, -1);
    lua_rawset(L, -3); lua_pop(L, 1);
  }
  if (luaL_loadbufferx(L, a->code, a->codeN, a->name, "t") != LUA_OK) {
    lua_pushfstring(L, "syntax error: %s", lua_tostring(L, -1)); return lua_error(L);
  }
  lua_pushvalue(L, env); lua_setupvalue(L, -2, 1);
  instrUsed = 0; killed = 0;
  lua_sethook(L, count_hook, LUA_MASKCOUNT, HOOK_STEP);
  int st = lua_pcall(L, 0, 0, h);
  lua_sethook(L, NULL, 0, 0);
  if (st != LUA_OK) {
    const char *m = lua_tostring(L, -1);
    if (st == LUA_ERRMEM || (memHit && m && strstr(m, "not enough memory"))) lua_pushfstring(L, "patch script exceeded the memory limit (%d MB)", (int)(memLimit >> 20));
    else lua_pushfstring(L, "runtime error: %s", m ? m : "?");
    return lua_error(L);
  }
  return 0;
}
static double lastInstructions;
/* Runs one patch script. The buffer holds the script, its chunk name ("@data/x.append.lua"), mod.meta.current_path
 * ("/data/x.append.lua"; pathLen -1 = none), the document's root element as events, and the names of the mod.vfs
 * filesystems ({u32 length, name}...; fsLen 0 = no mod.vfs; host fs index = position).
 * flags: 1 = return only the root's children (the document had no <FTL> root), 4 = no document (ftlman's lua-run).
 * Returns the patched root as events (length >= 0; 0 without a document) or -(1 + error length). */
EXPORT(mp_run) int mp_run(int codeLen, int nameLen, int pathLen, int docLen, int fsLen, int flags) {
  if (!L0) return put_error("runtime not initialised", 23);
  size_t pl = pathLen > 0 ? (size_t)pathLen : 0, docAt = (size_t)codeLen + (size_t)nameLen + pl;
  char *code = malloc((size_t)codeLen + 1), *name = malloc((size_t)nameLen + 1), *path = pathLen >= 0 ? malloc(pl + 1) : NULL;
  uint8_t *fs = fsLen > 0 ? malloc((size_t)fsLen) : NULL;
  int result;
  if (!code || !name || (pathLen >= 0 && !path) || (fsLen > 0 && !fs)) { result = put_error("not enough memory", 17); goto done; }
  memcpy(code, buf, (size_t)codeLen); code[codeLen] = 0;
  memcpy(name, buf + codeLen, (size_t)nameLen); name[nameLen] = 0;
  if (path) { memcpy(path, buf + codeLen + nameLen, pl); path[pl] = 0; }
  if (fs) memcpy(fs, buf + docAt + docLen, (size_t)fsLen);
  Node *root = NULL;
  if (!(flags & 4)) {
    Node **tops; size_t ntops;
    if (!build(docAt, (size_t)docLen, &tops, &ntops)) { result = put_error("not enough memory", 17); goto done; }
    for (size_t i = 0; i < ntops && !root; i++) if (tops[i]->kind == K_ELEMENT) root = tops[i];
    free(tops);
    if (!root) { result = put_error("XML document does not contain a root element", 44); goto done; }
  }
  free(currentFile); currentFile = path; path = NULL;
  runArgs.code = code; runArgs.codeN = (size_t)codeLen; runArgs.name = name; runArgs.root = root; runArgs.fs = fs; runArgs.fsN = (size_t)(fsLen > 0 ? fsLen : 0);
  memHit = 0;
  lua_settop(L0, 0);
  lua_pushcfunction(L0, run_body);
  int st = lua_pcall(L0, 0, 0, 0);
  lastInstructions = instrUsed;
  lua_rawgetp(L0, LUA_REGISTRYINDEX, &modRef); lua_pushliteral(L0, "vfs"); lua_pushnil(L0); lua_rawset(L0, -3); lua_pop(L0, 1);
  fsToken++;
  free(currentFile); currentFile = NULL;
  if (st != LUA_OK) {
    size_t n; const char *m = lua_tolstring(L0, -1, &n);
    if (!m) { m = "error"; n = 5; }
    if (st == LUA_ERRMEM) { m = "patch script exceeded the memory limit"; n = strlen(m); }
    result = put_error(m, n);
  } else if (!root) result = 0;
  else {
    outLen = 0;
    int r = emit(root, flags & 1);
    result = r > 0 ? (int)outLen : r < 0 ? put_error(LOOP_MSG, strlen(LOOP_MSG)) : put_error("not enough memory", 17);
  }
done:
  lua_settop(L0, 0);
  gc_bounded(L0);
  sweep();
  free(code); free(name); free(path); free(fs);
  return result;
}
EXPORT(mp_instructions) double mp_instructions(void) { return lastInstructions; }
EXPORT(mp_memory) double mp_memory(void) { return (double)memUsed; }
EXPORT(mp_memory_peak) double mp_memory_peak(void) { return (double)memPeak; }
EXPORT(mp_nodes) double mp_nodes(void) { return (double)nodeCount; }
