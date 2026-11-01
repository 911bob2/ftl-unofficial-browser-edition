# Builds web/vendor/hs-lua.wasm: Lua 5.3.6 (third_party/lua-5.3.6, unmodified) + the Hyperspace bridge (hs-lua/), and
# web/vendor/ftlman-lua.wasm: the same Lua (with Lua 5.4's string arithmetic) and ftlman's patch-script API
# (hs-lua/ftlman.c) for the mod merge.
#   docker build -f hs-lua/Dockerfile --target out -o type=local,dest=web/vendor .
# emscripten/emsdk 3.1.74, pinned by digest.
FROM emscripten/emsdk:3.1.74@sha256:af45409f3199d88db4b1b03af0098532c8fb33a375ac257463eeb0a622870d06 AS build
WORKDIR /src
COPY third_party/lua-5.3.6/src/ lua/
COPY hs-lua/ hs-lua/
RUN mkdir -p /out && LUA_CORE="lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject lopcodes lparser lstate lstring ltable ltm lundump lzio" \
 && LUA_LIBS="lauxlib lbaselib lbitlib lmathlib lstrlib ltablib lutf8lib" \
 && SRCS="" && for f in $LUA_CORE $LUA_LIBS; do SRCS="$SRCS lua/$f.c"; done \
 && emcc -O3 -flto -std=gnu99 -DLUA_COMPAT_5_2 -include hs-lua/hsconf.h -Ilua -Ihs-lua \
      $SRCS hs-lua/hslua-lvm.c hs-lua/linit.c hs-lua/hslua.c hs-lua/gen/hs_api.c \
      -o /out/hs-lua.wasm --no-entry -sSTANDALONE_WASM=1 -sSUPPORT_LONGJMP=wasm -fwasm-exceptions \
      -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=16MB -sSTACK_SIZE=1MB -sFILESYSTEM=0 \
      -sERROR_ON_UNDEFINED_SYMBOLS=0 -sEXPORTED_FUNCTIONS=_malloc,_free \
      -Wl,--export=__heap_base && ls -la /out && sha256sum /out/hs-lua.wasm
# ftlman's runtime opens only base, table, string and math; Lua 5.4's math compatibility functions (pow, ...) as ftlman's.
# Its VM is lvm.c through hs-lua/ftlman-lvm.c (Lua 5.4's string arithmetic).
RUN LUA_CORE="lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject lopcodes lparser lstate lstring ltable ltm lundump lzio" \
 && LUA_LIBS="lauxlib lbaselib lmathlib lstrlib ltablib" \
 && SRCS="" && for f in $LUA_CORE $LUA_LIBS; do SRCS="$SRCS lua/$f.c"; done \
 && emcc -O3 -flto -std=gnu99 -DLUA_COMPAT_MATHLIB -include hs-lua/hsconf.h -Ilua -Ihs-lua \
      $SRCS hs-lua/ftlman-lvm.c hs-lua/ftlman.c \
      -o /out/ftlman-lua.wasm --no-entry -sSTANDALONE_WASM=1 -sSUPPORT_LONGJMP=wasm -fwasm-exceptions \
      -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=16MB -sMAXIMUM_MEMORY=2GB -sSTACK_SIZE=1MB -sFILESYSTEM=0 \
      -sERROR_ON_UNDEFINED_SYMBOLS=0 -sEXPORTED_FUNCTIONS=_malloc,_free \
 && sha256sum /out/ftlman-lua.wasm
FROM scratch AS out
COPY --from=build /out/hs-lua.wasm /hs-lua.wasm
COPY --from=build /out/ftlman-lua.wasm /ftlman-lua.wasm
