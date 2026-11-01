# Fuzzing the Hyperspace Lua runtime

libFuzzer + ASan target for `hs-lua/hslua.c` (with `hs-lua/gen/hs_api.c`, Lua 5.3.6, `hs-lua/hslua-lvm.c`), run on the
x86_64 Android emulator. Lua runs natively on Android, outside the wasm sandbox (security review, finding M3).

Needs NDK 28.2.13676358, Python and a running x86_64 emulator; no Docker, nothing to install. Everything generated
(objects, seeds, logs, corpus, crash files) stays in ignored `.build/android/hs-lua-fuzz/`; on the device
`/data/local/tmp/hsfuzz/`. The emulator and the installed app are not touched.

```
python tools/hs-lua-fuzz/fuzz.py all --minutes 30    # seeds, both builds, push, start both fuzzers in the background
python tools/hs-lua-fuzz/fuzz.py status              # cov: at start and now, crash files, processes
python tools/hs-lua-fuzz/fuzz.py pull                # logs, corpus, crash files into .build/android/hs-lua-fuzz
python tools/hs-lua-fuzz/fuzz.py repro FILE [--variant check]    # one input, report with symbols (host llvm-symbolizer)
python tools/hs-lua-fuzz/fuzz.py min FILE            # minimise a crash input
python tools/hs-lua-fuzz/fuzz.py stop                # stop every fuzzer on the device
```

Single steps: `gen`, `build [--variant asan|check]`, `push`, `start --variant V --minutes M [--name N]`. `all` runs two
`asan` processes sharing a corpus and one `check`; a loop restarts each after a crash, and crash files land in `art-<name>/`
on the device. `--fork N` uses libFuzzer's fork mode instead (lower throughput, but it survives timeouts: `all` uses it for `check`,
whose moving stack makes the deep recursion seeds time out).

- `hslua_fuzz.c`: the target. Includes the runtime as `android/engine/src/main/jni/hslua_jni.c` does and keeps that file's
  error boundaries and stack checks (`n_*`); `hslua_jni.c` itself is not compiled, so JNI lookups and `byte[]` copies are
  not covered. The `js_*` imports are a fake engine with a handle table like `Handles.kt`, driven by a PRNG from the input
  header: object answers of plausible and wrong types, stale and made-up handles, long and non-UTF-8 strings, failures, and
  reentry while answering (collections, nested events, destroyed objects). Input format: top of that file.
- Fresh `lua_State` per input, closed at the end. About 20 ms of an input goes to `hs_init`, so expect tens of runs/s.
- `asan` build: the Android.mk flags plus ASan and coverage. `check` build (`check.h`, `check.c`): also Lua's API and
  internal assertions, and a Lua stack that moves at every stack check; several times slower.
- `seeds/`: Lua seeds (a `--@` line splits a file into several). `fuzz.py gen` adds the 4-byte headers, 16 shards of
  `walker.lua`, the Lua of `LuaSemantics.kt` and `tests/hs-lua/semantics.mjs`, and a dictionary from `hs-lua/gen/hs_ids.json`.
- A timeout is expected from loops inside a C function (`string.find` with backtracking patterns), which the instruction
  cap cannot stop; check the stack before treating one as a finding.
