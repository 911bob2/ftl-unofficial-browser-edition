/* Lua 5.3.6's liolib.c (third_party/lua-5.3.6/src, unchanged) with its file calls on the private file system (hslibs.c):
 * fopen, tmpfile and the standard files, and io.popen, which finds no shell in the sandbox ("'popen' not supported",
 * Lua's own message for a C library without popen). */
#include "lprefix.h"
#include <stdio.h>
#include <string.h>
#include "hslibs.h"
#define strerror hs_strerror
#undef stdin
#undef stdout
#undef stderr
#define stdin hs_vstd(0)
#define stdout hs_vstd(1)
#define stderr hs_vstd(2)
#define fopen hs_fopen
#define tmpfile hs_tmpfile
#define l_popen(L,c,m) hs_popen(L,c,m)
#define l_pclose(L,f) hs_pclose(L,f)
#include "liolib.c"
