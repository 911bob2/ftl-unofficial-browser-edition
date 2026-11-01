#!/bin/sh
# swig.sh <Hyperspace source dir> <out dir>
# Runs SWIG on Hyperspace's lua/modules/*.i in a container (image hs-lua-swig:focal, no network at run time) and
# writes <out dir>/swig/{hyperspace,graphics,defines,rapidxml}.cxx. Then generate the bridge tables:
#   node tools/hs-bindgen/bindgen.mjs <out dir>/swig <Hyperspace source dir> .
# The HS source tree stays outside the repository (a `git archive 1b05a07` of FTL-Hyperspace, GPL-3.0-or-later; e.g. .build/hs-1b05a07).
set -e
HERE=$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))
HS=$(cd "$1" && (pwd -W 2>/dev/null || pwd))
mkdir -p "$2"
OUT=$(cd "$2" && (pwd -W 2>/dev/null || pwd))
docker build -q -t hs-lua-swig:focal -f "$HERE/Dockerfile.swig" "$HERE" > /dev/null
MSYS_NO_PATHCONV=1 docker run --rm --network none -v "$HS:/hs:ro" -v "$OUT:/out" -v "$HERE:/tool:ro" \
  hs-lua-swig:focal sh /tool/run-swig.sh
