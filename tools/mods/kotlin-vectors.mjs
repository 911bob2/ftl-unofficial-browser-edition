// Writes the mod merge fixtures (tests/fixtures/mods) as one JSON file for the Kotlin port's tests
// (android/mods, run by its Gradle test task): the append cases with ftlman's results, the F1 archives, the F1
// override archive encrypted as MEGA stores it with the keys web/mods/mega.mjs derives, and the Lua patch script cases
// (ftlman's results, and web/mods/lua.mjs's own results for the parity cases, for the runner in android/app/src/debug).
// Usage: node tools/mods/kotlin-vectors.mjs <out.json>
import {createHash} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';
import {cases} from '../../tests/fixtures/mods/append-cases.mjs';
import {f1Archives, f1Base} from '../../tests/fixtures/mods/f1.mjs';
import {MEGA_HANDLE, megaAttributes, megaEncrypt, megaLink, testCatalogue} from '../../tests/fixtures/mods/catalogue.mjs';
import {fileKey, parseMegaLink} from '../../web/mods/mega.mjs';
import {PRELUDE, cases as luaCases} from '../../tests/fixtures/mods/lua-cases.mjs';
import {l1Archives, l1Base} from '../../tests/fixtures/mods/l1.mjs';
import {luaDoc, luaReport, native, parityBase, parityCases, parityMerges, parityZip, security, securityMerges, securityMod} from '../../tests/fixtures/mods/lua-android.mjs';
import {applyOneXml, mergeMods} from '../../web/mods/merge.mjs';
import {VirtualFileTree, createPatchRuntime, pkgFilesystem} from '../../web/mods/lua.mjs';
import {bytesSource, openZip} from '../../web/mods/zip.mjs';

const fixture = name => JSON.parse(readFileSync(new URL('../../tests/fixtures/mods/' + name, import.meta.url), 'utf8'));
const expected = fixture('append-expected.json'), f1 = fixture('f1-expected.json'), luaExpected = fixture('lua-expected.json');
const b64 = bytes => Buffer.from(bytes).toString('base64'), hex = bytes => Buffer.from(bytes).toString('hex');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const {archives: {override}, catalogue} = testCatalogue(), megaKeys = fileKey(parseMegaLink(megaLink).key);
// Crosses many counter blocks; the Kotlin test rebuilds the same plaintext.
const pattern = Buffer.from(Array.from({length: 70000}, (_, i) => (i * 31 + 7) & 255));

// What the website's runtime and merge produce for the parity cases (tests/fixtures/mods/lua-android.mjs).
async function parityResults() {
  const logs = [], rt = await createPatchRuntime({append: applyOneXml, onLog: (kind, text) => logs.push([kind, text])});
  const scripts = parityCases.map(({name, code}) => {
    logs.length = 0;
    let output = null, error = null;
    try { output = rt.apply(luaDoc, code, {chunkName: '@data/x.append.lua', metaPath: '/data/x.append.lua'}); } catch (e) { error = e.message; }
    return {name, code, output, error, logs: [...logs]};
  });
  const enc = new TextEncoder(), base = new Map(Object.entries(parityBase).map(([n, v]) => [n, typeof v === 'string' ? enc.encode(v) : v]));
  const merges = [];
  for (const {name, archives} of parityMerges) {
    const zips = archives.map(([n, entries]) => [n, parityZip(entries)]), opened = [], logged = [];
    for (const [n, bytes] of zips) opened.push({name: n, zip: await openZip(bytesSource(bytes))});
    const m = {name, archives: zips.map(([n, bytes]) => ({name: n, zip: b64(bytes)}))};
    try {
      const r = await mergeMods({baseNames: [...base.keys()], readBase: n => base.get(n), baseSize: n => base.get(n).length, archives: opened, lazyBinary: true, onLuaLog: (k, t) => logged.push([k, t])});
      const files = {};
      for (const [n, v] of r.files) files[n] = sha(v instanceof Uint8Array ? v : await opened[v.archive].zip.read(v.entry));
      Object.assign(m, {files, order: [...r.files.keys()], removed: [...r.removed], warnings: r.warnings, scripts: r.lua.scripts, logs: logged});
    } catch (e) { m.error = e.message; }
    merges.push(m);
  }
  return {scripts, merges, base: Object.entries(parityBase).map(([name, v]) => ({name, bytes: b64(typeof v === 'string' ? enc.encode(v) : v)}))};
}
// VirtualFileTree (lua.mjs) on paths and lookups, with pkgFilesystem's callbacks answering 1, "R" and nothing.
function vfsResults() {
  const trees = [
    {paths: ['data/a.txt', 'data/b\\c.txt', 'img/x.png', 'é/z', 'é/😀', 'é/￯', 'root', 'trail/'], ops: ['', '/', 'data', 'data/', 'data/a.txt', 'data/b', 'data/b/c.txt', 'data\\a.txt', 'é', 'root', 'root/x', 'nope', 'img/x.png/y', 'trail', 'trail/']},
    {paths: []}, {paths: ['a/b', 'a/b/c']}, {paths: ['a/b/c', 'a/b']}, {paths: ['a', 'a']}, {paths: ['a\\b', 'a/b']}, {paths: ['', 'x']}, {paths: ['/x', 'y//z'], ops: ['', 'x', 'y', 'y/', 'y//z']},
  ];
  const attempt = f => { try { return f(); } catch (e) { return {error: e.message}; } };
  return trees.map(({paths, ops = []}) => {
    let fs, created = {};
    try { fs = pkgFilesystem(paths, {size: () => 1, read: () => 'R', write: () => {}}); new VirtualFileTree(paths, 'zip file'); } catch (e) { created = {error: e.message}; }
    return {paths, ...created, ops: created.error ? [] : ops.map(path => ({path, stat: attempt(() => fs.stat(path)), ls: attempt(() => fs.ls(path)), read: attempt(() => fs.read(path)), write: attempt(() => { fs.write(path + '/new', ''); fs.write(path, ''); return 'ok'; })}))};
  });
}
const out = {
  append: cases.map(([name, base, script, kind = 'append']) => ({name, base, script, kind, expected: expected[name]})),
  f1: {
    base: [...f1Base()].map(([name, bytes]) => ({name, bytes: b64(bytes)})),
    archives: f1Archives().map(a => ({name: a.name, zip: b64(a.bytes)})),
    files: f1.files,
  },
  mega: {
    link: megaLink, handle: MEGA_HANDLE, aes: hex(megaKeys.aes), nonce: hex(megaKeys.nonce),
    plain: b64(override.bytes), cipher: b64(megaEncrypt(override.bytes)), sha256: catalogue.files.find(f => f.id === 'f1-override').sha256,
    patternCipher: b64(megaEncrypt(pattern)), attributes: megaAttributes('f1-override.zip'), name: 'f1-override.zip',
  },
  lua: {
    prelude: PRELUDE,
    cases: luaCases.map(([name, base, script]) => ({name, base, script, expected: luaExpected.cases[name]})),
    l1: {
      base: [...l1Base()].map(([name, bytes]) => ({name, bytes: b64(bytes)})),
      archives: l1Archives().map(a => ({name: a.name, zip: b64(a.bytes)})),
      files: luaExpected.l1.files, text: luaExpected.l1.text ?? {},
    },
    security: {doc: luaDoc, report: luaReport, cases: security, mod: b64(securityMod), merges: securityMerges},
    native,
    parity: await parityResults(),
    vfs: vfsResults(),
  },
};
writeFileSync(process.argv[2], JSON.stringify(out));
