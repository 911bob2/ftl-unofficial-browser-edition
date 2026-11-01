// savecore.js (the Kotlin save core built by TeaVM, docs/SAVES.md) against the vectors the Kotlin tests use.
// Build it first: docker build --target savecore-js -o type=local,dest=.build/android/savecore-js .
// SAVECORE_JS names another build of it.
import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
import {modSetHash} from '../web/mods/session.mjs';

const path = process.env.SAVECORE_JS || new URL('../.build/android/savecore-js/savecore.js', import.meta.url).pathname.replace(/^\/(\w:)/, '$1');
assert.ok(existsSync(path), 'savecore.js not built: ' + path);
const core = {};
new Function('exports', readFileSync(path, 'utf8'))(core);

const vector = name => JSON.parse(readFileSync(new URL('../savecore/vectors/' + name, import.meta.url), 'utf8'));
const hashes = vector('hashes.json');
for (const {text, hex} of hashes.sha256) assert.equal(core.savecoreSha256(text), hex, 'sha256 of ' + text.slice(0, 20));
for (const {mods, hash} of hashes.modSets) {
  assert.equal(core.savecoreModSetHash(JSON.stringify(mods)), hash);
  assert.equal(await modSetHash(mods), hash, 'browser modSetHash');
}

const defaults = {id: 's1', name: 'Save 1', hasRun: true};
const fill = o => o == null ? null : JSON.stringify({...defaults, ...o});
const cases = vector('sync.json').cases;
for (const c of cases) {
  const got = JSON.parse(core.savecoreDecide(fill(c.local), fill(c.cloud), !!c.deleted));
  assert.deepEqual(got, c.expect, c.name);
}

// Writing, uploading and fetching keep the same rules as the Kotlin tests.
const local = JSON.parse(core.savecoreWritten(fill({base: 3, dirty: false}), 'run-a', '{}'));
assert.equal(local.dirty, true);
assert.equal(local.run, 'run-a');
const up = JSON.parse(core.savecoreUploaded(JSON.stringify({...local, run: 'run-b'}), 'run-a', null, 4));
assert.equal(up.base, 4);
assert.equal(up.dirty, true, 'progress written during the upload stays unsynced');
assert.equal(JSON.parse(core.savecoreUploaded(JSON.stringify(local), 'run-a', null, 4)).dirty, false);

// The run and the profile are one save: either one unsynced keeps it dirty.
const held = JSON.parse(core.savecoreProfileWritten(fill({base: 3, dirty: false}), 'p1'));
assert.equal(held.dirty, true);
assert.equal(held.hasProfile, true);
assert.equal(held.profile, 'p1');
assert.equal(core.savecoreProfileWritten(JSON.stringify(held), 'p1'), null);
assert.equal(JSON.parse(core.savecoreUploaded(JSON.stringify({...held, profile: 'p2'}), null, 'p1', 4)).dirty, true);
assert.equal(JSON.parse(core.savecoreUploaded(JSON.stringify(held), held.run, 'p1', 4)).dirty, false);
assert.equal(JSON.parse(core.savecoreFetched(JSON.stringify(held), fill({revision: 9, hasProfile: true}), null, 'cloud-p')).profile, 'cloud-p');

const parts = JSON.parse(core.savecoreSplitOldProfile('<xftl-profile achPopups="false" language="de" listTutorial="1"><hotkey action="a" key="b" /><ach id="X" diff="EASY" /></xftl-profile>'));
assert.equal(parts.profile, '<xftl-profile listTutorial="1"><ach id="X" diff="EASY" /></xftl-profile>');
assert.match(parts.settings, /<xftl-settings achPopups="false" language="de"><hotkey action="a" key="b" \/><\/xftl-settings>$/);

assert.equal(core.savecoreCleanName('  Run 1  '), 'Run 1');
assert.equal(core.savecoreCleanName('a\u0000b'), null);
assert.equal(core.savecoreCleanName('x'.repeat(41)), null);
assert.equal(core.savecoreNextName(JSON.stringify(['Save 1', 'Save 2'])), 'Save 3');
assert.equal(core.savecoreLockState('null', 'me'), 'FREE');
assert.equal(core.savecoreLockState(JSON.stringify({instance: 'me'}), 'me'), 'MINE');
assert.equal(core.savecoreLockState(JSON.stringify({instance: 'tab-2', device: 'Pixel 8'}), 'me'), 'OTHER');

console.log(`PASS savecore.js: ${hashes.sha256.length} sha256, ${hashes.modSets.length} mod sets (equal to the browser's), ${cases.length} sync decisions`);
