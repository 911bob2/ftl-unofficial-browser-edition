// Mod merge unit tests against ftlman 0.7.4 ground truth (our own fixture content only).
// 1. append-cases: every case's ftlman `append` output (tests/fixtures/mods/append-expected.json).
// 2. F1: our fixture mods (tests/fixtures/mods/f1/) zipped deterministically and merged over a synthetic base;
//    expected file hashes come from `ftlman patch` on the same zips (f1-expected.json).
// 3. Lua patch scripts (ftlman `*.append.lua`): API cases and the L1 merge against ftlman (tests/lib/mods-lua.mjs).
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {cases} from './fixtures/mods/append-cases.mjs';
import {applyOneXml, mergeMods, readEncodedText, lfToCrlf} from '../web/mods/merge.mjs';
import {f1Archives, f1Base} from './fixtures/mods/f1.mjs';
import {bytesSource, openZip} from '../web/mods/zip.mjs';
import {modSetHash, sha256Hex} from '../web/mods/session.mjs';
import {runL1, runLuaCases} from './lib/mods-lua.mjs';

const expected = JSON.parse(readFileSync(new URL('./fixtures/mods/append-expected.json', import.meta.url), 'utf8'));
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
console.log(`PASS mods append: ${passed} cases equal ftlman 0.7.4 output (including ${Object.values(expected).filter(v => !v.ok).length} failures)`);

// Encodings and newline rules used by the per-file merge.
const enc = new TextEncoder();
assert.equal(readEncodedText(new Uint8Array([0xef, 0xbb, 0xbf, 0x61])), 'a');
assert.equal(readEncodedText(new Uint8Array([0xff, 0xfe, 0x61, 0, 0x62, 0])), 'ab');
assert.equal(readEncodedText(new Uint8Array([0xfe, 0xff, 0, 0x61])), 'a');
assert.throws(() => readEncodedText(enc.encode('aé')), /UTF-8/); // second byte starts a multi-byte character
assert.equal(lfToCrlf('a\nb\r\nc\rd\r\r\n'), 'a\r\nb\r\nc\rd\r\r\n');
assert.equal(await modSetHash(['ab']), await sha256Hex(enc.encode('{"loader":1,"mods":["ab"]}')));
console.log('PASS mods text encodings, CRLF conversion and mod-set hash');

const want = JSON.parse(readFileSync(new URL('./fixtures/mods/f1-expected.json', import.meta.url), 'utf8'));
const base = f1Base(), archives = [];
for (const a of f1Archives()) archives.push({name: a.name, zip: await openZip(bytesSource(a.bytes))});
const result = await mergeMods({baseNames: [...base.keys()], readBase: n => base.get(n), archives});
const merged = new Map(base); for (const n of result.removed) merged.delete(n); for (const [n, b] of result.files) merged.set(n, b);
const hashes = {}; for (const [n, b] of [...merged].sort(([a], [b]) => a < b ? -1 : 1)) hashes[n] = await sha256Hex(b);
for (const [n, h] of Object.entries(want.files)) assert.equal(hashes[n], h, 'F1 ' + n + (hashes[n] ? ' differs' : ' missing') + (want.text?.[n] && merged.get(n) ? '\nours:\n' + new TextDecoder().decode(merged.get(n)) + '\nftlman:\n' + want.text[n] : ''));
for (const n of Object.keys(hashes)) assert.ok(n in want.files, 'F1 extra file ' + n);
console.log(`PASS mods F1: ${Object.keys(want.files).length} files equal ftlman 0.7.4 patch output (${archives.length} archives, ${result.warnings.length} warnings)`);

const lua = await runLuaCases();
console.log(`PASS mods Lua: ${lua.passed} patch-script cases equal ftlman 0.7.4 output (including ${lua.failures} failures)`);
const l1 = await runL1();
console.log(`PASS mods L1: ${l1.files} files equal ftlman 0.7.4 patch output with ${l1.scripts} Lua patch scripts (${l1.warnings.length} warnings)`);
