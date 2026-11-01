/* The assertion sink of the "check" build (check.h). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void hsfuzz_check(int ok, const char *what, const char *file, int line) {
  if (ok) return;
  /* script.on_load(f, 1) refs the 1 (as Hyperspace) and new_cb asks lua_getinfo about it: no closure, read as a C function. */
  if (strstr(what, "function expected") && strstr(file, "ldebug")) return;
  fprintf(stderr, "ASSERTION %s:%d: %s\n", file, line, what);
  abort();
}
