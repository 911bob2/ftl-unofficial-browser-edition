/* Forced include (-include hsconf.h) for the Lua build: deterministic and without stdio.
 * The string hash seed and the table.sort pivot normally mix in time(); HS's value differs per run, ours is fixed. */
#ifndef HSCONF_H
#define HSCONF_H
#include <stddef.h>
#define luai_makeseed() 0u
#define l_randomizePivot() 0u
void hs_writestring(const char *s, size_t l);
void hs_writeerror(const char *fmt, const char *p);
#define lua_writestring(s, l) hs_writestring((s), (l))
#define lua_writeline() hs_writestring("\n", 1)
#define lua_writestringerror(s, p) hs_writeerror((s), (p))
#endif
