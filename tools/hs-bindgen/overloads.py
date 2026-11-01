# overloads.py <swig dir> [engine src]: every SWIG overload id (`__SWIG_n`) an adapter registers, with the
# argument list of that SWIG wrapper, to check each adapter against the overload it really serves.
import re, glob, sys
SWIG = sys.argv[1] if len(sys.argv) > 1 else '.build/hsgen3/swig'
ENG = sys.argv[2] if len(sys.argv) > 2 else 'engine/src/main/java'
sigs = {}
for f in glob.glob(SWIG + '/*.cxx'):
    src = open(f, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'static int (_wrap_\w+__SWIG_\d+)\(lua_State\* L\) \{(.*?)\n\}\n', src, re.S):
        fn, body = m.group(1), m.group(2)
        n = re.search(r'SWIG_check_num_args\("([^"]*)",(\d+),(\d+)\)', body)
        args = re.findall(r'SWIG_fail_arg\("[^"]*",(\d+),"([^"]*)"\)', body)
        seen = {}
        for i, t in args: seen.setdefault(int(i), t)
        sigs[fn] = (n.group(1) if n else '?', n.group(2) if n else '?', n.group(3) if n else '?', [seen[k] for k in sorted(seen)])
# method names from LuaApi.kt: M_<label> -> id
api = open(ENG + '/xyz/znix/xftl/hs/lua/LuaApi.kt', encoding='utf-8').read()
ids = dict(re.findall(r'const val (M_\w+__SWIG_\d+) = (\d+)', api))
# adapters
uses = {}
for f in glob.glob(ENG + '/**/*.kt', recursive=True) + glob.glob(ENG + '/**/*.java', recursive=True):
    if f.endswith('LuaApi.kt'): continue
    for ln, line in enumerate(open(f, encoding='utf-8', errors='replace'), 1):
        for mm in re.findall(r'A\.(M_\w+__SWIG_\d+)', line):
            uses.setdefault(mm, []).append(f'{f.split("xftl/")[-1]}:{ln}: {line.strip()[:170]}')
def wrapname(mname):
    # M_new_Point__SWIG_0 -> _wrap_new_Point__SWIG_0 ; M_Class_method__SWIG_n -> _wrap_Class_method__SWIG_n
    return '_wrap_' + mname[2:]
for mname in sorted(uses):
    s = sigs.get(wrapname(mname))
    print(f'== {mname}  ->  {s}')
    for u in uses[mname]: print('   ', u)
print('unmatched wrappers for used ids:', [m for m in uses if wrapname(m) not in sigs])
print('total overload ids', len(ids), 'used', len(uses))
print('--- adapters registered on a dispatcher id (overloaded name without __SWIG suffix):')
allm = dict(re.findall(r'const val (M_\w+) = (\d+)', api))
bases = {re.sub(r'__SWIG_\d+$', '', k) for k in ids}
for f in glob.glob(ENG + '/**/*.kt', recursive=True):
    if f.endswith('LuaApi.kt'): continue
    for ln, line in enumerate(open(f, encoding='utf-8', errors='replace'), 1):
        for mm in re.findall(r'A\.(M_\w+)\b', line):
            if mm in bases: print('   ', mm, f.split('xftl')[-1], ln, line.strip()[:140])
