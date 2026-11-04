// Mod merge checks against ftlman 0.7.4 ground truth (our own fixture content only), shared by tests/mods-merge.mjs (Node)
// and the Tests page. The fixtures are tests/fixtures/mods (served to the page as /web/test-vectors/mods/):
// 1. append-cases: every case's ftlman `append` output (append-expected.json);
// 2. F1: our fixture mods zipped deterministically and merged over a synthetic base, against `ftlman patch` (f1-expected.json);
// 3. Lua patch scripts (ftlman `*.append.lua`): the API cases as `ftlman append` ran them and the L1 merge with patch
//    scripts against `ftlman patch` (lua-expected.json `cases` and `l1`).
// Everything is merged in memory: the browser's mod library and merge cache are not used.
import {applyOneXml, mergeMods, readEncodedText, lfToCrlf} from './mods/merge.mjs';
import {bytesSource, openZip} from './mods/zip.mjs';
import {modSetHash, sha256Hex} from './mods/session.mjs';
import {createPatchRuntime} from './mods/lua.mjs';

// ftlman's error values are mlua errors: "runtime error: <message>" plus a traceback. Ours are the message.
export function normaliseErrors(text) {
  return text.replace(/(<e k="[^"]*" ok="false">)([^]*?)(<\/e>)/g, (_, a, m, b) => a + m.replace(/^runtime error: /, '').replace(/\nstack traceback:[^]*$/, '').replace(/\n$/, '') + b);
}
// Rows whose difference comes from Lua 5.3 (ours) against ftlman's Lua 5.4, documented in docs/MODS.md.
const KNOWN = {};

async function merged(base, list) {
  const archives = [];
  for (const a of list) archives.push({name: a.name, zip: await openZip(bytesSource(a.bytes))});
  const result = await mergeMods({baseNames: [...base.keys()], readBase: n => base.get(n), archives});
  const files = new Map(base); for (const n of result.removed) files.delete(n); for (const [n, b] of result.files) files.set(n, b);
  return {result, files, archives};
}

/** The append cases (part 1). */
export async function appendChecks({assert, load}) {
  const [{cases}, expected] = await Promise.all([load('append-cases.mjs'), load('append-expected.json')]);
  let passed = 0;
  for (const [name, base, script, kind = 'append'] of cases) {
    const want = expected[name];
    assert.ok(want, 'missing ftlman result for ' + name);
    let got, error;
    try { got = applyOneXml(base, script, kind); } catch (e) { error = e; }
    if (want.ok) { assert.equal(error, undefined, `${name}: ftlman succeeded, we failed: ${error?.message}`); assert.equal(got, want.output, name); }
    else assert.ok(error, `${name}: ftlman failed (${want.error}), we produced output`);
    passed++;
  }
  // Encodings and newline rules used by the per-file merge.
  const enc = new TextEncoder();
  assert.equal(readEncodedText(new Uint8Array([0xef, 0xbb, 0xbf, 0x61])), 'a');
  assert.equal(readEncodedText(new Uint8Array([0xff, 0xfe, 0x61, 0, 0x62, 0])), 'ab');
  assert.equal(readEncodedText(new Uint8Array([0xfe, 0xff, 0, 0x61])), 'a');
  assert.throws(() => readEncodedText(enc.encode('aé')), /UTF-8/); // second byte starts a multi-byte character
  assert.equal(lfToCrlf('a\nb\r\nc\rd\r\r\n'), 'a\r\nb\r\nc\rd\r\r\n');
  assert.equal(await modSetHash(['ab']), await sha256Hex(enc.encode('{"loader":1,"mods":["ab"]}')));
  return `PASS mods append: ${passed} cases equal ftlman 0.7.4 output (including ${Object.values(expected).filter(v => !v.ok).length} failures); text encodings, CRLF conversion and mod-set hash`;
}

/** The F1 merge (part 2). */
export async function f1Checks({assert, load}) {
  const [{f1Archives, f1Base}, want] = await Promise.all([load('f1.mjs'), load('f1-expected.json')]);
  const {result, files, archives} = await merged(f1Base(), f1Archives());
  const hashes = {}; for (const [n, b] of [...files].sort(([a], [b]) => a < b ? -1 : 1)) hashes[n] = await sha256Hex(b);
  for (const [n, h] of Object.entries(want.files)) assert.equal(hashes[n], h, 'F1 ' + n + (hashes[n] ? ' differs' : ' missing') + (want.text?.[n] && files.get(n) ? '\nours:\n' + new TextDecoder().decode(files.get(n)) + '\nftlman:\n' + want.text[n] : ''));
  for (const n of Object.keys(hashes)) assert.ok(n in want.files, 'F1 extra file ' + n);
  return `PASS mods F1: ${Object.keys(want.files).length} files equal ftlman 0.7.4 patch output (${archives.length} archives, ${result.warnings.length} warnings)`;
}

/** The Lua patch-script cases (part 3). */
export async function luaCaseChecks({assert, load}) {
  const [{PRELUDE, cases}, expected] = await Promise.all([load('lua-cases.mjs'), load('lua-expected.json')]);
  const logs = [];
  const rt = await createPatchRuntime({append: applyOneXml, onLog: (kind, text) => { if (kind === 1) logs.push(text + '\n'); }});
  let passed = 0, failures = 0;
  for (const [name, base, script] of cases) {
    const want = expected.cases[name];
    assert.ok(want, 'missing ftlman result for ' + name);
    logs.length = 0;
    let got, error;
    try { got = rt.apply(base, PRELUDE + script, {chunkName: '@base.xml'}); } catch (e) { error = e; }
    if (want.ok) {
      assert.equal(error, undefined, `${name}: ftlman succeeded, we failed: ${error?.message}`);
      let ours = normaliseErrors(logs.join('') + got), theirs = normaliseErrors(want.output);
      for (const label of KNOWN[name] ?? []) { const row = new RegExp(`<[re] k="${label}"[^]*?</[re]>|<e k="${label}"[^>]*/>`); ours = ours.replace(row, ''); theirs = theirs.replace(row, ''); }
      assert.equal(ours, theirs, name);
    } else {
      assert.ok(error, `${name}: ftlman failed (${want.error.split('\n')[0]}), we produced output`);
      if (want.code === 137) assert.match(error.message, /sibling links form a loop/, name);
      failures++;
    }
    passed++;
  }
  return `PASS mods Lua: ${passed} patch-script cases equal ftlman 0.7.4 output (including ${failures} failures)`;
}

/** The L1 merge with Lua patch scripts (part 3). */
export async function l1Checks({assert, load}) {
  const [{l1Archives, l1Base}, expected] = await Promise.all([load('l1.mjs'), load('lua-expected.json')]);
  const want = expected.l1;
  assert.ok(want?.ok, 'L1 ground truth missing');
  const {result, files} = await merged(l1Base(), l1Archives());
  for (const [n, h] of Object.entries(want.files)) {
    const b = files.get(n);
    assert.ok(b, 'L1 missing ' + n);
    if (await sha256Hex(b) !== h) assert.equal(new TextDecoder().decode(b), want.text[n] ?? '(binary)', 'L1 ' + n);
  }
  for (const n of files.keys()) assert.ok(n in want.files, 'L1 extra file ' + n);
  return `PASS mods L1: ${Object.keys(want.files).length} files equal ftlman 0.7.4 patch output with ${result.lua.scripts} Lua patch scripts (${result.warnings.length} warnings)`;
}
