/* Forced include of the "check" build: Lua's API and internal assertions, which report through the fuzz target, and a
 * Lua stack that moves at every stack check, so a pointer kept into it across one is caught by ASan. */
#ifndef HSFUZZ_CHECK_H
#define HSFUZZ_CHECK_H
void hsfuzz_check(int ok, const char *what, const char *file, int line);
#define lua_assert(c) hsfuzz_check((c) != 0, #c, __FILE__, __LINE__)
#define luai_apicheck(l, e) hsfuzz_check((e) != 0, #e, __FILE__, __LINE__)
#define HARDSTACKTESTS
#endif
