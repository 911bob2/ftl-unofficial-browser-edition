#!/bin/sh
# Inside hs-lua-swig:focal (see swig.sh): HS source at /hs (read-only), output in /out.
# 1. libzhlgen turns HS's function list into the FTL class headers the .i files include (ELF x86_64, FTL 1.6.13).
# 2. SWIG 4.0.1 (the version HS builds with) writes the Lua wrappers that tools/hs-bindgen/bindgen.mjs parses.
set -e
cp -r /hs /tmp/hs
cd /tmp/hs/libzhlgen/lua
mkdir -p /tmp/hs/generated
lua5.3 parsefuncs.lua ../test/functions/ELF_amd64/FTLGameStripped.h ../test/functions/ELF_amd64/1.6.13 \
  /tmp/hs/generated/FTLGameELF64.cpp /tmp/hs/generated/FTLGameELF64.h x86_64-pc-linux > /dev/null
cp /tmp/hs/generated/FTLGameELF64.h /out/   # bindgen reads the enum values (SDLK_*, ...) from it
cd /tmp/hs
mkdir -p /out/swig
INC=$(find /tmp/hs/src /tmp/hs/third_party/rapidxml -type d | sed "s/^/-I/" | tr '\n' ' ')
for m in hyperspace graphics defines rapidxml; do
  swig -c++ -lua -I/tmp/hs/lua $INC -I/tmp/hs/generated -o /out/swig/$m.cxx lua/modules/$m.i 2> /out/swig/$m.err || echo "swig $m failed"
done
swig -version | grep -i version
