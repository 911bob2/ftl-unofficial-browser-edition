// Lua patch scripts are mod code run at merge time: what they can reach and how a failing or runaway script ends.
// Pure Node checks on web/vendor/ftlman-lua.wasm (no server); tests/mods-security.mjs runs them.
import assert from 'node:assert/strict';
import {MergeError, applyOneXml, mergeMods} from '../../web/mods/merge.mjs';
import {createPatchRuntime, pkgFilesystem, zipFilesystem} from '../../web/mods/lua.mjs';
import {bytesSource, openZip} from '../../web/mods/zip.mjs';
import {zipSync} from '../../web/vendor/fflate-0.8.3.mjs';

const enc = new TextEncoder();
const doc = '<FTL>\n<a/>\n</FTL>\n';
const results = (out, label = 'r') => [...out.matchAll(new RegExp(`<${label}>([^<]*)</${label}>`, 'g'))].map(m => m[1]);
const report = `local function R(v) local e = mod.xml.element("r") e:append(tostring(v)) document.root:append(e) end\n`;

export async function luaSecurityChecks(pass) {
  const rt = await createPatchRuntime({append: applyOneXml});
  const run = (code, options = {}) => rt.apply(doc, report + code, {chunkName: '@data/blueprints.append.lua', metaPath: '/data/blueprints.append.lua', ...options});

  // Only the patch API and base/table/string/math: no files, processes, modules, debug hooks or coroutines.
  const names = ['io', 'os', 'package', 'debug', 'require', 'dofile', 'loadfile', 'collectgarbage', 'rawset', 'coroutine', 'utf8', 'loadstring', 'module', 'arg'];
  assert.deepEqual(results(run(names.map(n => `R(type(${n}))`).join('\n'))), names.map(() => 'nil'));
  assert.deepEqual(results(run(`R(type(mod.vfs)) R(getmetatable(document)) R(getmetatable(mod.xml.element("x"))) R(pcall(setmetatable, string, {}))`)), ['nil', 'false', 'false', 'false']);
  pass('Lua patch scripts get no io/os/package/debug/require/load-file/collectgarbage/rawset; library and API metatables are locked');

  // Bytecode never loads (string.dump exists, as in ftlman, but load reads text only).
  assert.deepEqual(results(run(`local f, e = load(string.dump(function() return 1 end)) R(f) R(e ~= nil)
    local g = load(string.dump(function() return 1 end), "x", "b") R(g)
    R(load("return 5", "x", "bt")())`)), ['nil', 'true', 'nil', '5']);
  pass('Lua patch scripts cannot load bytecode (load is text-only whatever mode is asked)');

  // Runaway scripts stop at the instruction limit, and pcall/xpcall cannot swallow it.
  const small = await createPatchRuntime({append: applyOneXml, instructions: 2e6, memory: 64 * 1024 * 1024});
  const smallRun = code => small.apply(doc, code, {chunkName: '@data/x.append.lua'});
  for (const code of ['while true do end', 'while true do pcall(function() while true do end end) end',
    'while true do xpcall(function() while true do end end, function(m) return m end) end',
    'local function f() return pcall(f) end while true do f() end', 'repeat local ok = pcall(error, "x") until false',
    // few instructions, much work inside the calls: each insertion checks the whole (growing) ancestor chain
    'local e = mod.xml.element("d") for i = 1, 20000 do local c = mod.xml.element("d") e:append(c) e = c end']) {
    assert.throws(() => smallRun(code), /instruction limit/, code);
  }
  assert.equal(results(smallRun(report + 'local n = 0 for i = 1, 1000 do n = n + i end R(n)'))[0], '500500');
  pass('A runaway patch script fails at the instruction limit, through pcall and xpcall; the runtime then runs the next script');

  // Hooks are off inside __gc in stock Lua: a finalizer's loop ends at the limit too, also one left for after the script.
  assert.equal(results(smallRun(report + 'setmetatable({}, {__gc = function() while true do end end}) R("done")'))[0], 'done');
  assert.throws(() => smallRun('setmetatable({}, {__gc = function() while true do end end}) for i = 1, 1e8 do local t = {i} end'), /instruction limit/);
  assert.equal(results(smallRun(report + 'local n = 0 for i = 1, 1000 do n = n + i end R(n)'))[0], '500500');
  pass('A loop in a __gc finalizer stops at the instruction limit, during a script and after its last line');

  // Memory: the Lua heap and the document trees share one limit.
  for (const code of ['local t = {} for i = 1, 1e9 do t[i] = string.rep("x", 100) .. i end', 'local s = string.rep("x", 200 * 1024 * 1024)',
    'local e = mod.xml.element("x", {a = string.rep("y", 1e6)}) for i = 1, 1e6 do document.root:append(e:clone()) end',
    'local t = {} while true do t[#t + 1] = pcall(string.rep, "x", 1e6) and string.rep("x", 1e6) end']) {
    assert.throws(() => smallRun(code), /memory limit|not enough memory|instruction limit/, code);
  }
  assert.equal(results(smallRun(report + 'R(#string.rep("ab", 10))'))[0], '20');
  pass('A patch script that allocates without bound fails at the memory limit (Lua heap and XML trees); the runtime recovers');

  // ftlman's tree can link siblings into a loop (ftlman then never finishes); here the script fails with a message.
  assert.throws(() => run('local a = document.root:firstElementChild() local b = mod.xml.element("b") a:append(b, mod.xml.element("c")) b:detach() a:append(b)'), /sibling links form a loop/);
  // Deep trees are walked without recursion in C (no stack overflow); inserting into them (ftlman's ancestor check per
  // insertion) counts towards the instruction limit.
  const deep = results(run(`local top = mod.xml.element("d") local e = top
    for i = 1, 50000 do local c = mod.xml.element("d") e:append(c) e = c end
    e:append("leaf") document.root:append(top) R(#mod.xml.stringify(top:clone())) R(top.textContent)`));
  assert.equal(deep[1], 'leaf');
  pass('Sibling loops and 50,000-deep trees end with a result or a clear error, not a hang or a crash');

  // Filesystems: only the merge's own files, by VirtualFileTree paths; the archive cannot be written; a filesystem
  // kept from an earlier script stops working.
  const written = new Map();
  const modZip = await openZip(bytesSource(zipSync({'data/x.txt': enc.encode('X')})));
  const files = () => ({pkg: pkgFilesystem(['data/a.txt', 'img/b.png'], {size: () => 1, read: () => enc.encode('A'), write: (n, b) => written.set(n, b)}), mod: zipFilesystem(modZip)});
  assert.deepEqual(results(run(`R(mod.vfs.pkg:read("/data/a.txt")) R(pcall(mod.vfs.pkg.read, mod.vfs.pkg, "/../../etc/passwd"))
    R(pcall(mod.vfs.pkg.read, mod.vfs.pkg, "data/a.txt")) R(pcall(mod.vfs.mod.write, mod.vfs.mod, "/data/x", "y")) R(pcall(function() mod.vfs.evil = 1 end))
    R(pcall(mod.vfs.pkg.read, mod.vfs.pkg, "/data/../data/a.txt")) R(mod.vfs.mod:read("/data/x.txt")) mod.vfs.pkg:write("/data/new.txt", "N")
    load("kept = mod.vfs.pkg")()`, {fs: files()})), ['A', 'false', 'false', 'false', 'false', 'false', 'X']);
  assert.deepEqual([...written.keys()], ['data/new.txt']);
  assert.deepEqual(results(run('R(pcall(kept.read, kept, "/data/a.txt")) R(type(mod.vfs))', {fs: files()})), ['false', 'table']);
  pass('mod.vfs reaches only the merge\'s files (no host paths, no writes into the archive); a kept filesystem stops working');

  // In a merge, a failing script fails the merge with the file names and the Lua error.
  const zip = entries => zipSync(Object.fromEntries(entries.map(([n, t]) => [n, enc.encode(t)])), {mtime: new Date(Date.UTC(2026, 0, 1))});
  const base = new Map([['data/blueprints.xml', enc.encode(doc)]]);
  const merge = async script => mergeMods({baseNames: [...base.keys()], readBase: n => base.get(n),
    archives: [{name: 'bad.zip', zip: await openZip(bytesSource(zip([['data/blueprints.append.lua', script]])))}], luaLimits: {instructions: 2e6}});
  await assert.rejects(merge('local x = nil; x.y = 1'), e => e instanceof MergeError && /data\/blueprints\.xml according to data\/blueprints\.append\.lua from bad\.zip: runtime error: data\/blueprints\.append\.lua:1: attempt to index a nil value/.test(e.message));
  await assert.rejects(merge('while true do end'), e => e instanceof MergeError && /instruction limit/.test(e.message));
  await assert.rejects(merge('local = 1'), e => e instanceof MergeError && /syntax error/.test(e.message));
  pass('A failing, runaway or malformed patch script fails the merge with the target, the script, the archive and the Lua error');
}
