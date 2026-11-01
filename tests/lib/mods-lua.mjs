// ftlman Lua patch scripts against ftlman 0.7.4 ground truth (our own fixture content only):
// 1. the API cases (tests/fixtures/mods/lua-cases.mjs) as `ftlman append` ran them (lua-expected.json `cases`);
// 2. L1: a merge with patch scripts (tests/fixtures/mods/l1.mjs) against `ftlman patch` (lua-expected.json `l1`).
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {PRELUDE, cases} from '../fixtures/mods/lua-cases.mjs';
import {l1Archives, l1Base} from '../fixtures/mods/l1.mjs';
import {applyOneXml, mergeMods} from '../../web/mods/merge.mjs';
import {createPatchRuntime} from '../../web/mods/lua.mjs';
import {bytesSource, openZip} from '../../web/mods/zip.mjs';

export const expected = JSON.parse(readFileSync(new URL('../fixtures/mods/lua-expected.json', import.meta.url), 'utf8'));
const sha = b => createHash('sha256').update(b).digest('hex');

// ftlman's error values are mlua errors: "runtime error: <message>" plus a traceback. Ours are the message.
export function normaliseErrors(text) {
  return text.replace(/(<e k="[^"]*" ok="false">)([^]*?)(<\/e>)/g, (_, a, m, b) => a + m.replace(/^runtime error: /, '').replace(/\nstack traceback:[^]*$/, '').replace(/\n$/, '') + b);
}
// Rows whose difference comes from Lua 5.3 (ours) against ftlman's Lua 5.4, documented in docs/MODS.md.
const KNOWN = {};

export async function runLuaCases() {
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
  return {passed, failures};
}

export async function runL1() {
  const want = expected.l1;
  assert.ok(want?.ok, 'L1 ground truth missing');
  const base = l1Base(), archives = [];
  for (const a of l1Archives()) archives.push({name: a.name, zip: await openZip(bytesSource(a.bytes))});
  const result = await mergeMods({baseNames: [...base.keys()], readBase: n => base.get(n), archives});
  const merged = new Map(base); for (const n of result.removed) merged.delete(n); for (const [n, b] of result.files) merged.set(n, b);
  for (const [n, h] of Object.entries(want.files)) {
    const b = merged.get(n);
    assert.ok(b, 'L1 missing ' + n);
    if (sha(b) !== h) assert.equal(new TextDecoder().decode(b), want.text[n] ?? '(binary)', 'L1 ' + n);
  }
  for (const n of merged.keys()) assert.ok(n in want.files, 'L1 extra file ' + n);
  return {files: Object.keys(want.files).length, scripts: result.lua.scripts, warnings: result.warnings};
}
