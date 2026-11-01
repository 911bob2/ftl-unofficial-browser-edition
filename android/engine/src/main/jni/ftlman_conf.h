/* Forced include of libftlmanlua.so: math.random as in ftlman-lua.wasm (musl's rand), not the platform libc's. */
#ifndef FTLMAN_CONF_H
#define FTLMAN_CONF_H
int ftlman_rand(void);
void ftlman_srand(unsigned s);
#define l_rand() ftlman_rand()
#define l_srand(x) ftlman_srand(x)
#define L_RANDMAX 2147483647
#endif
