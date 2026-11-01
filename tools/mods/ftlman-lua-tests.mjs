#!/usr/bin/env node
// Developer check: ftlman 0.7.4's own Lua tests (lua/tests/*.lua of its source) on our runtime, as its runner does
// (`ftlman lua-run --fs files ./files <test>`), against the outputs recorded from the real binary
// (tests/fixtures/mods/lua-expected.json `ftlmanTests`, tools/mods/ftlman-lua-cases.mjs).
//   node tools/mods/ftlman-lua-tests.mjs <ftlman source dir>
// The `files` filesystem is ftlman's LuaDirectoryFS: paths resolve inside the directory, writes are refused.
import {readFileSync, readdirSync, realpathSync, statSync} from 'node:fs';
import {join, resolve, sep} from 'node:path';
import {applyOneXml} from '../../web/mods/merge.mjs';
import {createPatchRuntime} from '../../web/mods/lua.mjs';

const src = process.argv[2];
if (!src) throw Error('Usage: ftlman-lua-tests.mjs <ftlman source dir>');
const expected = JSON.parse(readFileSync(new URL('../../tests/fixtures/mods/lua-expected.json', import.meta.url), 'utf8')).ftlmanTests;
const ioError = message => Object.assign(new Error(message), {io: true});

function directoryFs(dir) {
  const root = realpathSync(dir);
  const resolvePath = path => {
    let full;
    try { full = realpathSync(resolve(root, path.replace(/^[/\\]+/, ''))); } catch (e) { if (e.code === 'ENOENT') return null; throw ioError(e.message); }
    if (full !== root && !full.startsWith(root + sep)) throw ioError('path is outside filesystem root');
    return full;
  };
  const need = path => { const p = resolvePath(path); if (!p) throw ioError('No such file or directory (os error 2)'); return p; };
  return {
    stat: path => { const p = resolvePath(path); if (!p) return null; const s = statSync(p); return s.isDirectory() ? {dir: true} : {length: s.size}; },
    ls: path => readdirSync(need(path), {withFileTypes: true}).map(d => ({name: d.name, dir: d.isDirectory()})),
    read: path => new Uint8Array(readFileSync(need(path))),
    write: () => { throw ioError('read-only filesystem or storage medium'); },
  };
}

// Every `{ ... }` group with its entries sorted (entries split at top-level commas, whitespace trimmed).
function unordered(text) {
  let i = 0;
  const group = () => {
    const items = []; let cur = '';
    while (i < text.length) {
      const c = text[i++];
      if (c === '{') cur += group();
      else if (c === '}') { items.push(cur.trim()); return '{' + items.filter(Boolean).sort().join(',') + '}'; }
      else if (c === ',') { items.push(cur.trim()); cur = ''; }
      else cur += c;
    }
    return '{' + cur;
  };
  let out = '';
  while (i < text.length) { const c = text[i++]; out += c === '{' ? group() : c; }
  return out;
}

const rt = await createPatchRuntime({append: applyOneXml, onLog: (kind, text) => out.push(text)});
let out = [], failed = 0;
const tests = join(src, 'lua', 'tests');
for (const name of readdirSync(tests).filter(n => n.endsWith('.lua')).sort()) {
  out = [];
  let error = null;
  try { rt.run(readFileSync(join(tests, name), 'utf8'), {chunkName: '@' + name, metaPath: name, fs: {files: directoryFs(join(src, 'lua', 'files'))}}); }
  catch (e) { error = e; }
  const want = expected?.[name];
  // Pointers differ between runs, and so does the order of table entries (pairs order follows Lua's hash: random per
  // ftlman run, fixed here); the entries themselves must be equal.
  const norm = s => unordered(s.replace(/0x[0-9a-f]+/g, 'PTR'));
  const stdout = out.map(l => l + '\n').join('');
  const same = want && norm(stdout) === norm(want.stdout) && (error === null) === (want.code === 0);
  if (!same) failed++;
  console.log(`${same ? 'PASS' : 'FAIL'} ftlman lua/tests/${name}${error ? ': ' + error.message.split('\n')[0] : ''}`);
  if (!same && want) console.log('ours:\n' + stdout + '\nftlman:\n' + want.stdout);
}
process.exitCode = failed ? 1 : 0;
