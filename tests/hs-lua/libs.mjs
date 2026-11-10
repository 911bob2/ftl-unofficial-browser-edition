// Lua's io, os, debug and package/require in the Hyperspace runtime (sweep M1b, docs/HS_LUA.md "Files, programs and the
// sandbox") against the original Hyperspace 1.23.2: the probe tools/hs-oracle/tracer/data/hs_oracle/ps-m1b.lua runs here as
// it ran in the HS oracle (session ps-m1b-libs; its rows are libs-native.txt). Every row is the original's or the
// sandbox's value listed in SANDBOX with its reason. Then the sandbox's own checks: no path out of the private folder,
// no shell, the size cap, the instruction cap with a script's debug hooks, the debug library's limits, os.exit, and the
// folder kept across sessions. Run: node tests/hs-lua/libs.mjs   (TZ is set to UTC for the date rows)
process.env.TZ = 'UTC';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {createRuntime} from './mock-host.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const PROBE_NAME = 'data/hs_oracle/ps-m1b.lua';
const probe = fs.readFileSync(here + '../../tools/hs-oracle/tracer/data/hs_oracle/ps-m1b.lua', 'utf8');
const native = new Map();
for (const line of fs.readFileSync(here + 'libs-native.txt', 'utf8').split('\n').filter(Boolean)) {
  const key = line.split('|').slice(0, 2).join('|');
  if (!native.has(key)) native.set(key, line);
}

// A store standing in for IndexedDB / the app's folder: account -> Map(name -> bytes).
const disk = new Map();
const store = {
  read: async account => [...(disk.get(account) ?? new Map())].map(([name, data]) => ({name, data})),
  write: async (account, changes) => {
    const m = disk.get(account) ?? new Map(); disk.set(account, m);
    for (const c of changes) if (c.kind === 2) m.delete(c.name); else m.set(c.name, c.data);
  },
};
async function runtime(account = 'acc') {
  const r = await createRuntime();
  r.methods[r.M.new_GL_Color__SWIG_0] = () => { r.ret.obj({$t: r.T.GL_Color, r: r.arg.num(1)}); return 1; };
  await r.rt.mountFiles(account, store);
  return r;
}
const lua = (r, code, name = 'test.lua') => r.rt.run(name, code);
const rowsOf = r => r.logs.filter(l => l[0] === 0 && l[1].startsWith('ROW ')).map(l => l[1].slice(4).replace(/ {4}$/, ''));   // log adds four spaces

// ---------------------------------------------------------------- the probe against the original
const searched = name => {   // require's message: the Lua paths, then the C paths (the native row stops at HS's log line length)
  const lua = String.raw`.\lua\?.lua;.\lua\?\init.lua;.\?.lua;.\?\init.lua;.\..\share\lua\5.3\?.lua;.\..\share\lua\5.3\?\init.lua;.\?.lua;.\?\init.lua`.split(';');
  const c = String.raw`.\?.dll;.\..\lib\lua\5.3\?.dll;.\loadall.dll;.\?.dll`.split(';');
  return `lb|require-missing|err|module '${name}' not found:\\n\tno field package.preload['${name}']` + [...lua, ...c].map(t => `\\n\tno file '${t.replace('?', name)}'`).join('');
};
const SANDBOX = {
  // no CRLF translation: the files hold what was written (msvcrt's text mode adds \r before \n)
  'lb|seek-cur': ['lb|seek-cur|ok|13', 'text mode: no CRLF translation'],
  'lb|seek-end': ['lb|seek-end|ok|13', 'text mode'],
  'lb|seek-end-r': ['lb|seek-end-r|ok|13', 'text mode'],
  'lb|append': ['lb|append|hello/42 1.5/more/', 'text mode'],
  'lb|text-mode-read': ['lb|text-mode-read|6|x\\0y\\r\\nz', 'text mode'],
  'lb|text-mode-write': ['lb|text-mode-write|3|a\\nb', 'text mode'],
  // programs: no shell, no environment
  'lb|execute-none': ['lb|execute-none|ok|false', 'no shell'],
  'lb|execute-exit3': ['lb|execute-exit3|ok|nil|No such file or directory|2', 'no shell'],
  'lb|execute-true': ['lb|execute-true|ok|nil|No such file or directory|2', 'no shell'],
  'lb|popen': [`lb|popen|err|[string "${PROBE_NAME}"]:188: 'popen' not supported`, 'no shell'],
  'lb|popen-mode-bad': [`lb|popen-mode-bad|err|[string "${PROBE_NAME}"]:189: 'popen' not supported`, 'no shell'],
  'lb|getenv-path-type': ['lb|getenv-path-type|ok|nil', 'no environment'],
  // the zone's name (natively Windows' name of the machine's zone; Wine's here)
  'lb|date-bad %z': [/^lb\|date-bad %z\|ok\|[A-Za-z ]+$/, 'zone name of this machine'],
  'lb|date-bad %Z': [/^lb\|date-bad %Z\|ok\|[A-Za-z ]+$/, 'zone name of this machine'],
  // package: the game folder is '.', the original's exe folder ('!' in the native rows); C libraries never load
  'lb|package.path': ['lb|package.path|.\\lua\\?.lua;.\\lua\\?\\init.lua;.\\?.lua;.\\?\\init.lua;.\\..\\share\\lua\\5.3\\?.lua;.\\..\\share\\lua\\5.3\\?\\init.lua;.\\?.lua;.\\?\\init.lua', 'game folder'],
  'lb|package.cpath': ['lb|package.cpath|.\\?.dll;.\\..\\lib\\lua\\5.3\\?.dll;.\\loadall.dll;.\\?.dll', 'game folder'],
  'lb|require': ['lb|require|ok|7|ps_m1b_mod|.\\ps_m1b_mod.lua', 'game folder'],
  'lb|require-missing': [searched('ps_m1b_none'), 'game folder; full message'],
  'lb|require-dotted': [null, 'game folder; full message (checked below)'],
  'lb|require-error': ['lb|require-error|ok|false|.\\ps_m1b_err.lua:1: in module', 'game folder'],
  'lb|require-syntax': ["lb|require-syntax|ok|false|error loading module 'ps_m1b_syn' from file '.\\ps_m1b_syn.lua':\\n\t.\\ps_m1b_syn.lua:1: unexpected symbol near '+'", 'game folder'],
  'lb|require-bytecode': ["lb|require-bytecode|err|error loading module 'ps_m1b_bin' from file '.\\ps_m1b_bin.lua':\\n\tattempt to load a binary chunk (mode is 't')", 'text chunks only (security)'],
  'lb|loadlib': ['lb|loadlib|ok|nil|The specified module could not be found.\\r\\n|open', "Windows' text (Wine says 'Module not found.')"],
  'lb|loadlib-star': ['lb|loadlib-star|ok|nil|The specified module could not be found.\\r\\n|open', "Windows' text"],
  // debug: C functions show no upvalues (the runtime's member tables are upvalues)
  'lb|getupvalue-C': ['lb|getupvalue-C|ok', 'security'],
  // os.setlocale('') natively switches the C library to the machine's locale (Wine: English_United States.1252); "C" stays here
  'lb|setlocale-empty': ['lb|setlocale-empty|ok|C', 'locale stays C'],
};
function compareRows(rows, where) {
  assert.ok(rows.at(-1) === 'lb|end', 'the probe finished: ' + rows.at(-1));
  const ours = new Map(rows.map(l => [l.split('|').slice(0, 2).join('|'), l]));
  const problems = [];
  for (const [key, want] of native) {
    const got = ours.get(key);
    const sb = SANDBOX[key];
    if (sb) {
      if (sb[0] instanceof RegExp ? !sb[0].test(got ?? '') : sb[0] !== null && got !== sb[0]) problems.push(`${key} (sandbox: ${sb[1]})\n   ours ${got}\n   want ${sb[0]}`);
    } else if (got !== want) problems.push(`${key}\n   ours   ${got}\n   native ${want}`);
  }
  for (const key of ours.keys()) if (!native.has(key)) problems.push('only here: ' + key);
  const dotted = ours.get('lb|require-dotted') ?? '';
  assert.ok(dotted.startsWith("lb|require-dotted|err|module 'ps_m1b_none.sub' not found:\\n\tno field package.preload['ps_m1b_none.sub']\\n\tno file '.\\lua\\ps_m1b_none\\sub.lua'") &&
    dotted.endsWith("no file '.\\ps_m1b_none.dll'"), 'require of a dotted name: ' + dotted);
  assert.deepEqual(problems, [], 'probe rows');
  console.log(`probe (${where}): ${native.size} rows, ${native.size - Object.keys(SANDBOX).length} as the original, ${Object.keys(SANDBOX).length} the sandbox's`);
}
// --rows <file>: the probe's rows from another build (Android: logcat lines "ROW lb|...", e.g. adb logcat -v raw -s FTL-Lua:I)
if (process.argv[2] === '--rows') {
  const rows = fs.readFileSync(process.argv[3], 'utf8').split(/\r?\n/).filter(l => l.startsWith('ROW ')).map(l => l.slice(4).replace(/ {4}$/, ''));
  compareRows(rows, process.argv[3]);
  process.exit(0);
}
{
  const r = await runtime('probe');
  assert.equal(lua(r, probe, PROBE_NAME), null, 'the probe loads');
  assert.equal(lua(r, 'PM1B.sink = function(l) log("ROW " .. l) end PM1B.run()', 'run.lua'), null, 'the probe runs');
  compareRows(rowsOf(r), 'wasm');
}

// ---------------------------------------------------------------- the sandbox's own checks
const r = await runtime('acc');
const ok = (code, what) => { const err = lua(r, code); assert.equal(err, null, what + ': ' + err); };
// 1. no path out of the private folder
ok(`
local function refused(p, mode) local f, msg, code = io.open(p, mode) assert(f == nil and code == 13, p .. ': ' .. tostring(msg)) end
for _, p in ipairs({'../x.txt', '..\\\\x.txt', 'a/../../x.txt', '/etc/passwd', '\\\\Windows\\\\win.ini', 'C:\\\\Windows\\\\win.ini', 'c:x.txt',
    '\\\\\\\\server\\\\share\\\\x.txt', './../x.txt', '.', ''}) do
  if p == '' then assert(select(3, io.open(p)) == 2) else refused(p, 'r') refused(p, 'w') end
end
assert(select(3, os.remove('../x.txt')) == 13 and select(3, os.rename('../x.txt', 'y.txt')) == 13)
local f = io.open('inside.txt', 'w') f:write('in') f:close()
assert(select(3, os.rename('inside.txt', '../outside.txt')) == 13, 'rename out of the folder')
assert(select(3, os.rename('inside.txt', '/tmp/outside.txt')) == 13)
-- '..' inside the folder and Windows' rules stay inside it
local g = assert(io.open('a\\\\..\\\\inside.txt', 'r')) assert(g:read('a') == 'in') g:close()
assert(select(3, io.open('sub/x.txt', 'w')) == 2, 'no subfolders')
assert(io.open('INSIDE.TXT. ', 'r')):close()
os.remove('inside.txt')
-- require only finds the folder's files, and a compiled chunk is refused
local w = io.open('evil.lua', 'wb') w:write(string.dump(function() return 1 end)) w:close()
local okr, e = pcall(require, 'evil') assert(not okr and e:find("binary chunk", 1, true), e) os.remove('evil.lua')
assert(not pcall(require, '..\\\\evil'))
-- package.loadlib and C searchers load nothing
assert(package.loadlib('/system/lib64/libc.so', '*') == nil)
`, 'paths');
// 2. no shell, no environment
ok(`assert(os.execute() == false) assert(os.execute('echo hi') == nil) assert(not pcall(io.popen, 'ls')) assert(not pcall(io.popen, 'ls', 'w'))
assert(os.getenv('PATH') == nil and os.getenv('HOME') == nil) assert(os.setlocale('') == 'C' and os.setlocale('de_DE') == nil)
assert(os.tmpname():sub(1, 2) == '\\\\s' and io.open(os.tmpname(), 'w') == nil)`, 'programs');
// 3. the size cap: a write past 50 MB fails as on a full disk; removing the file frees the room
assert.equal(r.rt.filesUsed(), 0);
ok(`local chunk = string.rep('x', 1048576)
local f = assert(io.open('big.bin', 'wb')) local ok, msg, code = true
for i = 1, 51 do local a, b, c = f:write(chunk) if not a then ok, msg, code = a, b, c break end end
local a, b, c = f:close()
if ok then ok, msg, code = a, b, c end
assert(ok == nil and msg == 'No space left on device' and code == 28, 'cap: ' .. tostring(msg))
local s = assert(io.open('small.txt', 'w')) local w1, w2 = s:write(string.rep('y', 2 * 1048576)) local x, y = s:close()
assert((w1 == nil and w2 == 'No space left on device') or (x == nil and y == 'No space left on device'), 'a second file is over the cap too')
assert(os.remove('small.txt')) assert(os.remove('big.bin'))
local t = assert(io.open('after.txt', 'w')) assert(t:write('fits')) assert(t:close()) os.remove('after.txt')`, 'cap');
assert.equal(r.rt.filesUsed(), 0, 'nothing left after the removals');
ok(`local t = io.tmpfile() local ok, msg = pcall(function() for i = 1, 60 do assert(t:write(string.rep('z', 1048576))) end end)
assert(not ok and tostring(msg):find('No space left on device'), 'io.tmpfile counts too') t:close()`, 'tmpfile cap');
assert.equal(r.rt.filesUsed(), 0);
// 4. the instruction cap stays with a script's hooks: it cannot be removed, replaced or outrun inside a hook
r.e.hs_set_caps(0, 2000000);
for (const [what, code] of [
  ['hook cleared', 'debug.sethook() while true do end'],
  ['hook with a huge count', "debug.sethook(function() end, '', 2^31 - 1) while true do end"],
  // (the hook loops once: a hook stays set, and the next chunk's first line would run it again, as natively)
  ['loop inside a line hook', "local once = false debug.sethook(function() if not once then once = true while true do end end end, 'l')\nlocal x = 1\nx = x + 1"],
  ['loop inside a count hook', "debug.sethook(function() while true do end end, '', 10) for i = 1, 100 do end"],
  ['coroutine hook cleared', "local co = coroutine.create(function() while true do end end) debug.sethook(co) assert(coroutine.resume(co)) error(select(2, coroutine.resume(co)))"],
  ['coroutine hook replaced', "local co = coroutine.create(function() while true do end end) debug.sethook(co, function() end, 'l') local ok, e = coroutine.resume(co) error(e)"],
  ['pcall around the loop', "debug.sethook() local ok = pcall(function() while true do end end) while true do end"],
]) {
  const err = lua(r, code, 'cap.lua');
  assert.match(String(err), /instruction limit/, what + ': ' + err);
  lua(r, 'debug.sethook()');
}
r.e.hs_set_caps(0, 500000000);
ok(`assert(debug.gethook() == nil, 'the cap hook is not the script\\'s') local n = 0 debug.sethook(function() n = n + 1 end, '', 1000)
for i = 1, 100000 do end debug.sethook() assert(n > 50, 'count hook ' .. n) assert(debug.gethook() == nil)`, 'hooks still work');
// 5. the debug library cannot reach the runtime's own state
ok(`
local reg = debug.getregistry() local keys = {}
for k in pairs(reg) do keys[#keys + 1] = tostring(k) end table.sort(keys)
assert(table.concat(keys, ' ') == '1 2 FILE* _LOADED _PRELOAD', table.concat(keys, ' '))
reg._LOADED = 5 assert(type(debug.getregistry()._LOADED) == 'table', 'a copy')
local c = Graphics.GL_Color(1, 2, 3, 4)
assert(not pcall(debug.setmetatable, c, getmetatable(io.stdout)), 'a userdata keeps its metatable')
assert(not pcall(debug.setmetatable, io.stdout, getmetatable(c)))
local idx = getmetatable(c).__index
assert(debug.getupvalue(idx, 1) == nil and debug.setupvalue(idx, 1, {}) == nil, 'C upvalues hidden')
assert(not pcall(debug.upvalueid, idx, 1) and not pcall(debug.upvaluejoin, idx, 1, function() return c end, 1))
-- the runtime's metamethods refuse other userdata (an io file posing as an engine object)
for _, k in ipairs({'__index', '__newindex', '__tostring', '__gc', '__eq'}) do pcall(getmetatable(c)[k], io.stdout, 'r', 1) end
assert(io.type(io.stdout) == 'file' and io.stdout:write('') == io.stdout, 'io.stdout intact')
assert(not pcall(getmetatable(io.stdout).__index.close, c), 'io refuses an engine object')
assert(swig_equals(c, io.stdout) == false)
debug.debug()
`, 'debug');
// 6. os.exit stops the script, the callbacks and further loading; the host reads who called it
{
  const x = await runtime('exit');
  assert.equal(lua(x, "script.on_internal_event(Defines.InternalEvents.ON_TICK, function() ticked = (ticked or 0) + 1 end)"), null);
  x.e.hs_fire(1, 0, 0);
  const err = lua(x, 'local ok = pcall(os.exit, 3) while true do end', 'data/exit-mod.lua');
  assert.match(String(err), /os\.exit/, 'the script stops, its pcall does not keep it going: ' + err);
  assert.deepEqual(x.rt.exitState(), {code: 3, source: 'data/exit-mod.lua'});
  x.e.hs_fire(1, 0, 0);
  assert.notEqual(lua(x, 'x = 1'), null, 'nothing more runs');
}
// 7. the folder is kept: across sessions of the account, per account, with renames and removals
ok(`local f = io.open('kept.txt', 'w') f:write('one') f:close() f = io.open('gone.txt', 'w') f:write('x') f:close()
os.rename('kept.txt', 'Kept2.txt') os.remove('gone.txt')`, 'persist');
await r.rt.syncFiles();
assert.deepEqual([...disk.get('acc').keys()], ['Kept2.txt']);
{
  const again = await runtime('acc');
  assert.equal(lua(again, "local f = assert(io.open('kept2.txt')) assert(f:read('a') == 'one') f:close() assert(io.open('gone.txt') == nil)"), null, 'next session reads it');
  const other = await runtime('other');
  assert.equal(lua(other, "assert(io.open('kept2.txt') == nil, 'another account has its own folder')"), null);
}
console.log('PASS hs-lua libraries (Node): probe rows as the original or the sandbox, paths, no shell, size cap, instruction cap with hooks, debug limits, os.exit, kept folder');
