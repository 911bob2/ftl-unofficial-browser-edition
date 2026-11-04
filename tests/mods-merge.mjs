// Mod merge unit tests against ftlman 0.7.4 ground truth (our own fixture content only); the checks are
// web/self-tests-mods.mjs, which the Tests page runs too.
// 1. append-cases: every case's ftlman `append` output (tests/fixtures/mods/append-expected.json).
// 2. F1: our fixture mods (tests/fixtures/mods/f1/) zipped deterministically and merged over a synthetic base;
//    expected file hashes come from `ftlman patch` on the same zips (f1-expected.json).
// 3. Lua patch scripts (ftlman `*.append.lua`): API cases and the L1 merge against ftlman (tests/lib/mods-lua.mjs).
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {appendChecks, f1Checks} from '../web/self-tests-mods.mjs';
import {runL1, runLuaCases} from './lib/mods-lua.mjs';

const load = name => name.endsWith('.json') ? JSON.parse(readFileSync(new URL('./fixtures/mods/' + name, import.meta.url), 'utf8')) : import('./fixtures/mods/' + name);
console.log(await appendChecks({assert, load}));
console.log(await f1Checks({assert, load}));
console.log(await runLuaCases());
console.log(await runL1());

// "Hyperspace mod without Hyperspace" (web/mods/hs-needed.mjs, docs/MODS.md): which sets warn, Hyperspace.ftl named as
// the catalogue names it (mod "hyperspace" by SHA-256) or by its own metadata title, and the picker's hint.
{
  const {isHyperspace, usesHyperspace, withoutHyperspace} = await import('../web/mods/hs-needed.mjs');
  const {orderHints} = await import('../web/mods/library.mjs');
  const cat = {files: [{id: 'hyperspace-1.23.2', mod: 'hyperspace', sha256: 'a'.repeat(64)}, {id: 'mv-data', mod: 'multiverse', sha256: 'b'.repeat(64)}]};
  const hs = {sha256: 'a'.repeat(64), name: 'Hyperspace.ftl', title: 'Hyperspace', marker: true};
  const mv = {sha256: 'b'.repeat(64), name: 'Big Mod', title: 'Big Mod', marker: true};
  const plain = {sha256: 'c'.repeat(64), name: 'Some ship', title: 'Some ship', marker: false};
  const renamedHs = {...hs, title: null, name: 'renamed.zip'};
  for (const n of ['data/hyperspace.xml', 'data/hyperspace.xml.append', 'data/hyperspace.append.xml', 'data\\hyperspace.xml.rawappend', 'data/Hyperspace.append.lua', 'data/hyperspace.rawclobber.xml'])
    assert.ok(usesHyperspace(['data/blueprints.xml', n]), n);
  for (const n of ['data/hyperspace.txt', 'hyperspace.xml', 'img/data/hyperspace.xml', 'data/blueprints.xml.append']) assert.ok(!usesHyperspace([n]), n);
  assert.ok(isHyperspace(renamedHs, cat), 'the catalogue file under any name');
  assert.ok(isHyperspace({sha256: 'd'.repeat(64), title: 'Hyperspace'}, cat), 'another build of it, by its metadata title');
  assert.ok(!isHyperspace({sha256: 'd'.repeat(64), name: 'Hyperspace.ftl', title: 'My mod'}, cat), 'not by file name');
  assert.equal(withoutHyperspace([mv], cat), mv);
  assert.equal(withoutHyperspace([plain, mv], cat), mv);
  assert.equal(withoutHyperspace([renamedHs, mv], cat), null);
  assert.equal(withoutHyperspace([mv, hs], cat), null, 'present, if below: the order hint says so');
  assert.equal(withoutHyperspace([plain], cat), null);
  assert.equal(withoutHyperspace([], cat), null);
  const on = items => items.map(i => ({...i, enabled: true}));
  assert.deepEqual(orderHints(on([mv]), cat), ['Big Mod uses Hyperspace, but Hyperspace.ftl is not enabled: some Hyperspace images and text will be missing. Enable Hyperspace.ftl above it.']);
  assert.deepEqual(orderHints(on([mv, renamedHs]), cat), ['Big Mod uses Hyperspace: enable Hyperspace.ftl above it.']);
  assert.deepEqual(orderHints(on([renamedHs, mv]), cat), []);
  assert.deepEqual(orderHints([{...mv, enabled: false}], cat), [], 'only while it is enabled');
  console.log('PASS mods Hyperspace check: markers, Hyperspace.ftl by catalogue or title (not file name), the warning\'s sets and the picker hint');
}
