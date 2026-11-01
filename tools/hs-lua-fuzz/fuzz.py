#!/usr/bin/env python3
"""Builds the Hyperspace Lua fuzz target with the NDK and runs it on the emulator. See README.md.

  python tools/hs-lua-fuzz/fuzz.py all --minutes 30      generate, build both variants, push, start in the background
  python tools/hs-lua-fuzz/fuzz.py gen|build|push        one step
  python tools/hs-lua-fuzz/fuzz.py start --variant asan --minutes 30 [--name asan2] [--fork 2]
  python tools/hs-lua-fuzz/fuzz.py status                coverage lines and crash files on the device, processes
  python tools/hs-lua-fuzz/fuzz.py pull                  logs, crash files and corpus into .build/android/hs-lua-fuzz
  python tools/hs-lua-fuzz/fuzz.py repro FILE [--variant asan]   run one input, symbolize the report
  python tools/hs-lua-fuzz/fuzz.py min FILE [--variant asan]     minimise a crash input
  python tools/hs-lua-fuzz/fuzz.py stop                  stop every fuzzer on the device
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import struct
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / '.build/android/hs-lua-fuzz'
HOME = Path.home()
SDK = HOME / 'AppData/Local/Android/Sdk'
ADB = str(SDK / 'platform-tools/adb.exe')
TOOLCHAIN = SDK / 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64'
DEVICE = '/data/local/tmp/hsfuzz'
LUA = 'third_party/lua-5.3.6/src'
LUA_CORE = 'lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject lopcodes lparser lstate lstring ltable ltm lundump lzio'.split()
LUA_LIBS = 'lauxlib lbaselib lbitlib lmathlib lstrlib ltablib lutf8lib'.split()
SOURCES = [f'{LUA}/{f}.c' for f in LUA_CORE + LUA_LIBS] + ['hs-lua/hslua-lvm.c', 'hs-lua/linit.c', 'hs-lua/gen/hs_api.c', 'tools/hs-lua-fuzz/hslua_fuzz.c']
# As android/engine/src/main/jni/Android.mk, module hslua, minus JNI.
DEFINES = ['-std=gnu99', '-DLUA_COMPAT_5_2', '-include', 'hs-lua/hsconf.h', '-DLUA_USE_POSIX', '-Wno-unknown-attributes', '-Wno-string-plus-int']
SANITIZE = ['--target=x86_64-linux-android26', '-fsanitize=fuzzer,address', '-O1', '-g', '-fno-omit-frame-pointer']
VARIANTS = {'asan': [], 'check': ['-include', 'tools/hs-lua-fuzz/check.h']}
# Instruction caps, failure rates and so on per input: see the header comment of hslua_fuzz.c.
SEED_HEADS = [bytes([0x02, 0x00, 0, 1]), bytes([0x57, 0x00, 0, 2]), bytes([0xBE, 0x10, 0, 3]), bytes([0xFF, 0x35, 0, 4]), bytes([0x6B, 0x0A, 0, 5]), bytes([0x96, 0x2E, 0, 6])]


def run(cmd, **kw):
    return subprocess.run(cmd, cwd=ROOT, check=kw.pop('check', True), **kw)


def adb(*args, check=True, capture=False):
    r = subprocess.run([ADB, *args], check=check, capture_output=capture, text=capture)
    return r.stdout if capture else None


def shell(script, check=True, capture=False):
    return adb('shell', script, check=check, capture=capture)


# ---------------------------------------------------------------- seeds and dictionary
def tokens():
    ids = json.loads((ROOT / 'hs-lua/gen/hs_ids.json').read_text(encoding='utf8'))
    words = set()
    for name in ids['attrs']:
        words.add(name.split('.')[-1])
    for name in ids['types']:
        words.add(name)
    classes = sorted(ids['types'], key=len, reverse=True)
    for name in ids['methods']:
        for c in classes:
            if name.startswith(c + '_'):
                name = name[len(c) + 1:]
                break
        words.add(name)
    words |= set('''and break do else elseif end false for function goto if in local nil not or repeat return then true until while
        pcall xpcall error assert tostring tonumber type next pairs ipairs select print log setmetatable getmetatable rawlen rawequal unpack
        string.rep string.format string.gsub string.gmatch string.find string.pack string.unpack string.dump string.byte string.char string.sub
        table.sort table.concat table.insert table.remove table.unpack table.move utf8.char utf8.codes utf8.codepoint utf8.offset math.random
        math.randomseed math.floor math.huge math.maxinteger math.mininteger bit32.band bit32.lshift _G _ENV
        __gc __mode __index __newindex __call __tostring __eq __lt __le __len __concat __add __unm __pairs __metatable __disown __getitem __setitem
        .get .set .fn .hs .type .bases .instance .static table swig_type swig_equals
        script.on_load script.on_init script.on_game_event script.on_render_event script.on_internal_event
        Hyperspace Graphics Defines RapidXML InternalEvents RenderEvents Chain CONTINUE HALT PREEMPT Hyperspace.Point Hyperspace.Global.GetInstance
        H G D X E R C V U E.ON_TICK E.ON_KEY_DOWN R.MOUSE_CONTROL C.HALT C.PREEMPT'''.split())
    return sorted(w for w in words if 1 <= len(w) <= 60)


def lua_blocks():
    """Lua from the tests: the semantics script and its checks, and the strings semantics.mjs runs (the benchmark's only spins)."""
    kt = (ROOT / 'engine/src/main/java/xyz/znix/xftl/hs/lua/LuaSemantics.kt').read_text(encoding='utf8')
    out = []
    for name in ('SCRIPT', 'CHECK'):
        m = re.search(r'// %s BEGIN.*?"""(.*?)""".*?// %s END' % (name, name), kt, re.S)
        if m:
            out.append(m.group(1))
    mjs = (ROOT / 'tests/hs-lua/semantics.mjs').read_text(encoding='utf8')
    out += re.findall(r"'((?:[^'\n]*(?:function|while)[^'\n]*))'", mjs)
    return out


def generate():
    seeds = OUT / 'seeds'
    seeds.mkdir(parents=True, exist_ok=True)
    for old in seeds.glob('*'):
        old.unlink()
    (OUT / 'hslua.dict').write_text(''.join('"%s"\n' % w.replace('\\', '\\\\').replace('"', '\\"') for w in tokens()), encoding='ascii', newline='\n')
    chunks = {}
    for path in sorted((HERE / 'seeds').glob('*.lua')):
        text = path.read_text(encoding='utf8')
        if path.stem == 'walker':
            for k in range(16):
                chunks[f'walker{k:02d}'] = f'K,N={k},16\n{text}'
        else:
            parts = text.split('\n--@\n')
            for i, part in enumerate(parts):
                chunks[f'{path.stem}{i}' if len(parts) > 1 else path.stem] = part
    # the test scripts together: registration first, then the checks
    blocks = lua_blocks()
    chunks['tests'] = '\0'.join(blocks)
    for i, b in enumerate(blocks):
        chunks[f'test{i:02d}'] = b
    count = 0
    for name, text in chunks.items():
        data = text.encode('utf8')
        # the walker shards are slow: two headers each; the collector stress seed gets many PRNG seeds
        heads = SEED_HEADS[:2] if name.startswith('walker') else SEED_HEADS
        if name == 'gc2':
            heads = heads + [bytes([0x02, 0x10, 1, k]) for k in range(40)]
        for i, head in enumerate(heads):
            (seeds / f'{name}-{i}').write_bytes(head + data)
            count += 1
    print(f'{count} seeds, {len(tokens())} dictionary words in {OUT}')


# ---------------------------------------------------------------- build
def compile_one(cc, flags, src, obj):
    obj.parent.mkdir(parents=True, exist_ok=True)
    deps = [ROOT / src, ROOT / 'hs-lua/hslua.c', HERE / 'hslua_fuzz.c', HERE / 'check.h'] if 'hslua_fuzz' in src else [ROOT / src, HERE / 'check.h']
    if obj.exists() and all(obj.stat().st_mtime >= d.stat().st_mtime for d in deps):
        return
    cmd = [cc, *SANITIZE, *flags, *DEFINES, f'-I{LUA}', '-Ihs-lua', '-c', src, '-o', str(obj)]
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f'{src}:\n{r.stdout}{r.stderr}')


def build(variants):
    cc = str(TOOLCHAIN / 'bin/clang.exe')
    for variant in variants:
        objdir = OUT / f'obj-{variant}'
        sources = SOURCES + (['tools/hs-lua-fuzz/check.c'] if variant == 'check' else [])
        objs = [objdir / (s.replace('/', '_').replace('.c', '.o')) for s in sources]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda p: compile_one(cc, VARIANTS[variant], p[0], p[1]), zip(sources, objs)))
        binary = OUT / f'hsfuzz-{variant}'
        run([str(TOOLCHAIN / 'bin/clang++.exe'), *SANITIZE, '-static-libstdc++', *map(str, objs), '-lm', '-o', str(binary)])
        print('built', binary)


def asan_so():
    return glob.glob(str(TOOLCHAIN / 'lib/clang/*/lib/linux/libclang_rt.asan-x86_64-android.so'))[0]


def push():
    shell(f'mkdir -p {DEVICE}/seeds {DEVICE}/tmp')
    for f in [asan_so(), OUT / 'hsfuzz-asan', OUT / 'hsfuzz-check', OUT / 'hslua.dict']:
        if Path(f).exists():
            adb('push', '-q', str(f), DEVICE + '/')
    adb('push', '-q', str(OUT / 'seeds') + '/.', DEVICE + '/seeds/')
    shell(f'chmod 755 {DEVICE}/hsfuzz-*')


# ---------------------------------------------------------------- run
ASAN_ENV = 'ASAN_OPTIONS=symbolize=0:quarantine_size_mb=64:detect_leaks=0:abort_on_error=0:detect_stack_use_after_return=0:allocator_may_return_null=1'


def start(variant, minutes, fork, name=None):
    """In the background on the device. Without fork mode a loop restarts the fuzzer after a crash, until the time is up."""
    name = name or variant
    corpus = f'corpus-{variant}'
    secs = int(minutes * 60)
    shell(f'mkdir -p {DEVICE}/{corpus} {DEVICE}/art-{name}')
    forkflags = f'-fork={fork} -ignore_crashes=1 -ignore_ooms=1 -ignore_timeouts=1' if fork else '-reload=1'
    fuzz = (f'TMPDIR={DEVICE}/tmp LD_LIBRARY_PATH=. {ASAN_ENV} ./hsfuzz-{variant} -rss_limit_mb=1500 -timeout=20 -max_len=65536 '
            f'-dict=hslua.dict -artifact_prefix=art-{name}/ -print_final_stats=1 -detect_leaks=0 -report_slow_units=100000 {forkflags}')
    script = '\n'.join([
        f'cd {DEVICE}',
        f'end=$(($(cut -d. -f1 /proc/uptime)+{secs}))',
        'while :; do',
        ' left=$((end-$(cut -d. -f1 /proc/uptime)))',
        ' [ "$left" -le 0 ] && break',
        f' {fuzz} -max_total_time=$left {corpus} seeds >> log-{name}.txt 2>&1 < /dev/null',
        ' sleep 2',
        'done',
        ''])
    local = OUT / f'hsfuzz-run-{name}.sh'
    local.write_bytes(script.encode('ascii'))
    adb('push', '-q', str(local), DEVICE + '/')
    shell(f'cd {DEVICE}; nohup sh hsfuzz-run-{name}.sh > /dev/null 2>&1 < /dev/null &')
    print('started', name)


def status():
    print(shell(f'''cd {DEVICE} 2>/dev/null && for f in log-*.txt; do echo "== $f"; grep -E "cov:" "$f" | head -1 | cut -c1-120; grep -E "cov:" "$f" | tail -1 | cut -c1-120; tail -2 "$f" | cut -c1-160; done; ls art-*/ 2>/dev/null | head -40; ls corpus-*/ 2>/dev/null | wc -l; ps -A -o PID,RSS,TIME,ARGS | grep hsfuzz | grep -v grep | cut -c1-120''', check=False, capture=True))


def pull():
    OUT.mkdir(parents=True, exist_ok=True)
    listing = shell(f'cd {DEVICE} && ls -d log-* art-* corpus-* 2>/dev/null', check=False, capture=True).split()
    for item in listing:
        adb('pull', '-q', f'{DEVICE}/{item}', str(OUT) + '/', check=False)
    print('pulled', len(listing), 'items into', OUT)


def symbolize(report, variant):
    binary = str(OUT / f'hsfuzz-{variant}')
    sym = str(TOOLCHAIN / 'bin/llvm-symbolizer.exe')
    out = []
    for line in report.splitlines():
        m = re.search(r'\(.*?hsfuzz-\w+\+0x([0-9a-f]+)\)', line)
        if m:
            r = subprocess.run([sym, '-e', binary, '--inlining=true', '--relativenames', '0x' + m.group(1)], capture_output=True, text=True)
            parts = [p for p in r.stdout.strip().splitlines() if p]
            frames = ' < '.join(f'{parts[i]} {parts[i + 1]}' for i in range(0, len(parts) - 1, 2))
            line = line[:m.start()] + frames
        out.append(line)
    return '\n'.join(out)


def repro(path, variant, extra=''):
    remote = f'{DEVICE}/repro.bin'
    adb('push', '-q', path, remote)
    r = subprocess.run([ADB, 'shell', f'cd {DEVICE} && TMPDIR={DEVICE}/tmp LD_LIBRARY_PATH=. {ASAN_ENV} ./hsfuzz-{variant} -rss_limit_mb=1500 -timeout=20 -detect_leaks=0 {extra} repro.bin 2>&1'], capture_output=True, text=True, errors='replace')
    print(symbolize(r.stdout, variant))


def minimize(path, variant):
    remote = f'{DEVICE}/min-in.bin'
    adb('push', '-q', path, remote)
    r = subprocess.run([ADB, 'shell', f'cd {DEVICE} && TMPDIR={DEVICE}/tmp LD_LIBRARY_PATH=. {ASAN_ENV} ./hsfuzz-{variant} -rss_limit_mb=1500 -timeout=20 -detect_leaks=0 -minimize_crash=1 -max_total_time=120 -exact_artifact_path=min-out.bin min-in.bin 2>&1'], capture_output=True, text=True, errors='replace')
    print(r.stdout[-1500:])
    dest = str(OUT / (Path(path).name + '.min'))
    adb('pull', '-q', f'{DEVICE}/min-out.bin', dest, check=False)
    print('minimised:', dest)


def stop():
    shell("pids=$(ps -A -o PID,ARGS | grep hsfuzz- | grep -v grep | awk '{print $1}'); [ -n \"$pids\" ] && kill $pids; sleep 1; ps -A -o PID,ARGS | grep hsfuzz- | grep -v grep; true", check=False)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('command', choices='all gen build push start status pull repro min stop'.split())
    ap.add_argument('file', nargs='?')
    ap.add_argument('--variant', default='asan', choices=VARIANTS)
    ap.add_argument('--name')
    ap.add_argument('--minutes', type=float, default=30)
    ap.add_argument('--fork', type=int, default=0)
    a = ap.parse_args()
    if a.command in ('all', 'gen'):
        generate()
    if a.command in ('all', 'build'):
        build(['asan', 'check'] if a.command == 'all' else [a.variant])
    if a.command in ('all', 'push'):
        push()
    if a.command == 'all':
        start('asan', a.minutes, a.fork)
        start('asan', a.minutes, a.fork, 'asan2')
        start('check', a.minutes, 1)
    if a.command == 'start':
        start(a.variant, a.minutes, a.fork, a.name)
    if a.command == 'status':
        status()
    if a.command == 'pull':
        pull()
    if a.command == 'repro':
        repro(a.file, a.variant)
    if a.command == 'min':
        minimize(a.file, a.variant)
    if a.command == 'stop':
        stop()


if __name__ == '__main__':
    main()
