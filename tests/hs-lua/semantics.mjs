// Script API semantics of the Hyperspace Lua runtime in Node (same Lua and expectations as the engine's
// LuaSemantics, read from its source between the SCRIPT/CHECK markers). Run: node tests/hs-lua/semantics.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {createRuntime} from './mock-host.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const kt = fs.readFileSync(here + '../../engine/src/main/java/xyz/znix/xftl/hs/lua/LuaSemantics.kt', 'utf8');
const block = name => kt.slice(kt.indexOf('// ' + name + ' BEGIN'), kt.indexOf('// ' + name + ' END')).replace(/^[\s\S]*?"""/, '').replace(/"""[\s\S]*$/, '');
const SCRIPT = block('SCRIPT'), CHECK = block('CHECK');
const ON_KEY_DOWN = 7, ON_KEY_UP = 8, SET_BONUS_POWER = 24, DANGEROUS_ENVIRONMENT = 3, MOUSE_CONTROL = 23;

const r = await createRuntime();
const {e, rt} = r;
const base = e.hs_gettop();
assert.equal(rt.run('semantics.lua', SCRIPT), null);
const failures = [];
const key = (ev, k) => { e.hs_pushinteger(k); const p = e.hs_fire_chain(ev, 1, 0) !== 0; e.hs_pop(1); return p; };
const step = (name, setup, action, expect) => {
  rt.run('setup', 'trace = {} mode = {} ' + setup);
  const got = action();
  if (expect !== undefined && got !== expect) failures.push(`${name} returned ${got}, want ${expect}`);
  rt.run('take', name + ' = take()');
};
step('R1', '', () => key(ON_KEY_DOWN, 1), false);
step('R2', "mode.b = 'halt'", () => key(ON_KEY_DOWN, 2), false);
step('R3', "mode.b = 'preempt'", () => key(ON_KEY_DOWN, 3), true);
step('R4', "mode.b = 'seven'", () => key(ON_KEY_DOWN, 4), false);
step('R5', "mode.b = 'string'", () => key(ON_KEY_DOWN, 5), false);
step('R6', "mode.d = 'error'", () => key(ON_KEY_DOWN, 6), false);
step('R7', '', () => { e.hs_pushnil(); e.hs_pushinteger(10); e.hs_fire_chain(SET_BONUS_POWER, 2, 1); const a = e.hs_tonumber(-1); e.hs_pop(2); if (a !== 15) failures.push('rewrite final ' + a); });
step('R8', '', () => { e.hs_pushboolean(0); const n = e.hs_fire(DANGEROUS_ENVIRONMENT, 1, 1); const v = n === 1 ? !!e.hs_toboolean(-1) : null; e.hs_pop(n === 1 ? 2 : 1); if (n !== 1 || v !== true) failures.push(`plain ${n}/${v}`); });
const render = () => { const i = e.hs_render_pre(MOUSE_CONTROL, 0); e.hs_render_post(MOUSE_CONTROL, Math.abs(i), 0); return i; };
step('R9', '', render, 3);
step('R10', 'mode.render = 5', render, 2);
step('R11', 'mode.renderP = 10', render, -1);
e.hs_set_caps(100000, 0);
step('R12', '', () => key(ON_KEY_UP, 3), false);
e.hs_set_caps(50000000, 0);
step('R13', '', () => { e.hs_on_load(); });
const err = rt.run('check.lua', CHECK);
if (err) failures.push(err);
if (e.hs_gettop() !== base) failures.push('stack not balanced');
// errors were logged as HS logs them
const errs = r.logs.filter(l => l[0] === 2).map(l => l[1]);
assert.ok(errs.some(t => t.includes('Failed to call the callback for InternalEvent ON_KEY_DOWN!') && t.includes('boom d')), 'callback error logged');
assert.ok(errs.some(t => /instruction limit \(100000\) exceeded in semantics\.lua, callback InternalEvent ON_KEY_UP/.test(t)), 'cap error names file and callback: ' + errs.join(' | '));
assert.ok(errs.some(t => t.includes('Failed to call the callback!') && t.includes('load failure')), 'on_load error logged');
e.hs_set_caps(0, 1000000);
for (const code of ['while true do pcall(function() while true do end end) end', 'while true do xpcall(function() while true do end end, function(m) return m end) end', 'local function f() return pcall(f) end while true do f() end'])
  assert.match(String(rt.run('loop.lua', code)), /instruction limit \(1000000\) exceeded while running loop\.lua/, 'pcall cannot swallow the cap: ' + code);
e.hs_set_caps(0, 500000000);
assert.equal(rt.run('after.lua', 'return 1'), null, 'the runtime works after the cap');
// Hooks are off inside __gc in stock Lua: a finalizer's loop must still reach the cap, in a collection and in a script.
const FINALIZER_LOOP = 'setmetatable({}, {__gc = function() while true do end end})';
const FINALIZER_LOOP_PCALL = 'setmetatable({}, {__gc = function() while true do pcall(function() while true do end end) end end})';
for (const code of [FINALIZER_LOOP, FINALIZER_LOOP_PCALL]) {
  assert.equal(rt.run('gc.lua', code), null, 'the script itself ends: ' + code);
  r.logs.length = 0;
  e.hs_full_gc();
  assert.ok(r.logs.some(l => l[0] === 2 && /instruction limit/.test(l[1])), 'a collection stops at the cap: ' + code);
  r.logs.length = 0;
  e.hs_full_gc();
  assert.ok(!r.logs.some(l => /instruction limit/.test(l[1])), 'the finalizer ran once: ' + code);
}
e.hs_set_caps(0, 1000000);
assert.match(String(rt.run('gc-loop.lua', FINALIZER_LOOP + ' for i = 1, 1e8 do local t = {i} end')), /instruction limit/, 'a finalizer during a script stops at its cap');
e.hs_set_caps(0, 500000000);
assert.equal(rt.run('after-gc.lua', 'kept = ' + FINALIZER_LOOP + ' return 1'), null, 'the runtime works after a stopped finalizer');
// A callback registering new game event names moves the runtime's event table while the event is being dispatched.
assert.equal(rt.run('gev.lua', `gev = {}
script.on_game_event('gev', false, function() gev[#gev + 1] = 'a'
  for i = 1, 400 do script.on_game_event('gev' .. i, false, function() end) end
  script.on_game_event('gev', false, function() gev[#gev + 1] = 'late' end) end)
script.on_game_event('gev', false, function() gev[#gev + 1] = 'b' end)`), null);
rt.gameEvent('gev', false);
assert.equal(rt.run('gev-check.lua', "if table.concat(gev, ',') ~= 'a,b,late' then error(table.concat(gev, ','), 0) end"), null, 'every callback of the event runs');
// The libraries of HS 1.23.2's Lua (oracle ps-m1-ev lib rows): Lua 5.3 without LUA_COMPAT_5_2 (no bit32, no math.pow),
// coroutine there; io, os, debug and package as HS 1.23.2 has them, on the private file system (sweep M1b; tests/hs-lua/libs.mjs).
assert.equal(rt.run('libs.lua', `local got = table.concat({type(bit32), type(math.pow), type(math.ldexp), type(math.cosh), type(math.log10),
  type(coroutine), type(coroutine.wrap), type(utf8), type(string.pack), type(table.unpack), type(unpack), type(load), type(collectgarbage),
  type(rawget), type(rawlen), type(io), type(debug), type(package), type(require), type(os.clock), type(os.time), type(os.date)}, ',')
local want = 'nil,nil,nil,nil,nil,table,function,table,function,function,nil,nil,nil,nil,function,table,table,table,function,function,function,function'
if got ~= want then error(got, 0) end
local co = coroutine.wrap(function(a) local b = coroutine.yield(a + 1); return b * 2 end)
if co(1) ~= 2 or co(5) ~= 10 then error('coroutine', 0) end`), null, 'libraries as HS 1.23.2');
// a coroutine's runaway loop still stops at the cap of the code that resumes it
e.hs_set_caps(0, 1000000);
assert.match(String(rt.run('co-loop.lua', 'local co = coroutine.wrap(function() while true do end end) co()')), /instruction limit/, 'a coroutine stops at the cap');
e.hs_set_caps(0, 500000000);
assert.deepEqual(failures, []);
console.log('PASS hs-lua semantics (Node): priority order, HALT/PREEMPT, rewriting, plain results, errors, registration checks, render pre/post, instruction cap, on_load, libraries');
