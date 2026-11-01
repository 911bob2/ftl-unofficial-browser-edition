#!/bin/sh
# Rebuilds web/vendor/hs-lua.wasm and web/vendor/ftlman-lua.wasm (mod patch scripts) in Docker (pinned emsdk).
# Run from anywhere: sh hs-lua/build.sh
# Regenerate hs-lua/gen first when the Hyperspace pin changes: sh tools/hs-bindgen/swig.sh <hs-src> <out>; node tools/hs-bindgen/bindgen.mjs ...
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
docker build -f "$ROOT/hs-lua/Dockerfile" --target out -o "type=local,dest=$ROOT/web/vendor" "$ROOT"
sha256sum "$ROOT/web/vendor/hs-lua.wasm" "$ROOT/web/vendor/ftlman-lua.wasm"
