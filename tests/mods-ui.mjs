// Mod library browser test (packaged Menus group): import our F1 fixture archives through the real Mods panel, order
// and enable them, launch, compare the engine's file map with the Node merge of the same archives over the served
// base, check the modded save is a save of that mod setup and syncs like any other (docs/SAVES.md), relaunch from the
// merge cache, and prove a vanilla launch is unchanged (no session, identical files map, no mod module loaded); then a
// Lua patch script merged in the browser's merge worker.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {chromium, firefox} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {f1Archives} from './fixtures/mods/f1.mjs';
import {bytesSource, openZip} from '../web/mods/zip.mjs';
import {mergeMods} from '../web/mods/merge.mjs';
import {modSetHash} from '../web/mods/session.mjs';
import {zipSync} from '../web/vendor/fflate-0.8.3.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084', assets = process.env.FTL_ASSETS_DIR ?? '/assets';
assert.ok(['http://localhost:8083', 'http://localhost:8084'].includes(origin), 'Use an isolated fixture');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 80) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const selected = (process.env.MENU_BROWSERS ?? 'Chromium,Firefox').split(',');

// Expected merge: the served vanilla files (boot.pack + audio names) with the F1 archives applied by web/mods/merge.mjs.
const pack = await readFile(assets + '/boot.pack'), manifest = JSON.parse(await readFile(assets + '/manifest.json', 'utf8'));
const n = pack.readUInt32BE(4), entries = JSON.parse(pack.subarray(8, 8 + n).toString('utf8')), base = new Map();
for (const e of entries) if (!e.name.startsWith('engine/')) base.set(e.name, new Uint8Array(pack.buffer, pack.byteOffset + 8 + n + e.offset, e.length));
const archives = f1Archives(), zips = [];
for (const a of archives) zips.push({name: a.name, zip: await openZip(bytesSource(a.bytes))});
const merged = await mergeMods({baseNames: [...base.keys(), ...manifest.audio.map(a => a.name)], readBase: k => base.get(k), archives: zips});
const sha = b => createHash('sha256').update(b).digest('hex');
const expected = Object.fromEntries([...merged.files].filter(([k]) => !/\.(ogg|wav)$/.test(k)).map(([k, v]) => [k, sha(v)]));
const expectedAudio = [...merged.files.keys()].filter(k => /\.(ogg|wav)$/.test(k));
const hash = await modSetHash(archives.map(a => sha(a.bytes)));

// A Lua patch script merged in the browser's merge worker (its wasm module, synchronous reads of a base .txt and of
// the archive through FileReaderSync, mod.vfs.pkg writes) must give the Node merge's result.
const luaScript = `local rooms = mod.iter.count(string.gmatch(mod.vfs.pkg:read("/data/kestral.txt"), "ROOM%s+(%d+)"))
local extra = mod.vfs.mod:read("/data/extra.txt")
local w, h = string.match(extra, "(%d+) (%d+)")
local e = mod.xml.element("luaCheck", {rooms = rooms, area = w * h, half = w / 2, size = #mod.vfs.pkg:read("/data/blueprints.xml")})
e:append(extra)
document.root:append(e)
for bp in document.root:children() do if bp.name == "shipBlueprint" then bp.attrs.luaChecked = bp.attrs.layout end end
mod.vfs.pkg:write("/data/lua_written.txt", "from " .. mod.meta.current_path)
print("browser patch script ran")`;
const luaZip = zipSync({'data/blueprints.append.lua': new TextEncoder().encode(luaScript), 'data/extra.txt': new TextEncoder().encode('2 3')}, {mtime: new Date(Date.UTC(2026, 0, 1))});
const luaBaseNames = ['data/blueprints.xml', 'data/kestral.txt'];
const luaMerged = await mergeMods({baseNames: luaBaseNames, readBase: k => base.get(k), archives: [{name: 'lua.zip', zip: await openZip(bytesSource(luaZip))}]});
const luaExpected = Object.fromEntries([...luaMerged.files].map(([k, v]) => [k, sha(v)]));
assert.equal(luaMerged.lua.scripts, 1);

// One account per browser: a save and its lock belong to the account, so the browsers must not share one.
for (const [name, type, options, user] of [['Chromium', chromium, {channel: 'chromium'}, 'user02'], ['Firefox', firefox, {}, 'user06']].filter(([b]) => selected.includes(b))) {
  const password = 'mods-fixture-password-' + user;
  const browser = withOnboarding(await type.launch({headless: !(name === 'Firefox' && process.env.RUNNER_FIREFOX_WINDOWED === '1'), ...options}), origin);
  const page = await browser.newPage({viewport: {width: 1280, height: 900}}), errors = [];
  page.setDefaultTimeout(30000); page.on('pageerror', e => errors.push(e.message));
  const modules = []; page.on('request', r => { if (/\/web\/mods\//.test(r.url())) modules.push(new URL(r.url()).pathname); });
  const pass = label => console.log(`PASS ${name}: ${label}`);
  try {
    await page.goto(origin); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
    await page.evaluate(async ({user, password, initial}) => {
      let csrf = (await (await fetch('/api/auth/csrf')).json()).token;
      const post = (p, body) => fetch('/api/auth/' + p, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify(body)});
      let r = await post('login', {username: user, password}); if (r.status === 401) r = await post('login', {username: user, password: initial}); if (!r.ok) throw Error('login ' + r.status);
      csrf = (await (await fetch('/api/auth/csrf')).json()).token;
      if ((await (await fetch('/api/auth/me')).json()).mustChangePassword) { r = await post('password', {currentPassword: initial, newPassword: password}); if (!r.ok) throw Error('rotation'); csrf = (await (await fetch('/api/auth/csrf')).json()).token; await post('login', {username: user, password}); }
    }, {user, password, initial: initial.get(user)});
    const cloud = () => page.evaluate(() => fetch('/api/saves').then(r => r.json()));
    const fingerprint = () => page.evaluate(async () => {
      const rows = []; for (const [k, v] of window.ftlFiles) rows.push(k + ':' + v.length);
      rows.sort(); const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(rows.join('\n')));
      return {count: rows.length, digest: [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, '0')).join(''), session: window.ftlModSession ?? null, gate: JSON.parse(window.hsCapabilityReport()), audio: window.ftlAudio.buffer ? null : null};
    });
    async function launchVanilla() {
      await page.goto(origin + '/?view=play'); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
      await page.locator('#launch').click();
      await page.waitForFunction(() => !!window.ftlEngine, {}, {timeout: 120000});
    }
    async function openMods() {
      await page.goto(origin + '/?view=play'); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
      await page.locator('#mods-panel > summary').click(); await page.locator('#mods-set').waitFor({state: 'attached'});
    }
    async function launchModded() {
      // One Launch button: with mods enabled it reads "Launch FTL with mods" and starts them.
      await page.waitForFunction(() => document.getElementById('launch-label').textContent === 'Launch FTL with mods');
      await page.locator('#launch').click();
      // Mods with no save yet: the launch asks and creates nothing by itself; Create save makes "Save 1" and launches.
      await page.waitForFunction(() => !!window.ftlEngine || !!document.querySelector('#no-save-dialog[open]') || !document.getElementById('message').hidden, {}, {timeout: 180000});
      if (await page.locator('#no-save-dialog[open]').count()) await page.locator('#no-save-create').click();
      await page.waitForFunction(() => !!window.ftlEngine || !document.getElementById('message').hidden, {}, {timeout: 180000});
      const failure = await page.evaluate(() => window.ftlEngine ? null : document.getElementById('message').textContent);
      assert.equal(failure, null, 'Modded launch failed: ' + failure);
    }

    // Vanilla baseline.
    await launchVanilla();
    const vanilla = await fingerprint();
    assert.equal(vanilla.session, null); assert.equal(vanilla.gate.modded, false);
    assert.deepEqual(modules, [], 'vanilla play loaded mod modules: ' + modules.join(', '));
    pass(`vanilla launch: ${vanilla.count} files, no mod session, no mod module loaded`);

    // Import through the real panel, reorder, toggle.
    await openMods();
    await page.locator('#mods-panel input[type=file]').setInputFiles(archives.map(a => ({name: a.name, mimeType: 'application/zip', buffer: Buffer.from(a.bytes)})));
    await page.waitForFunction(() => document.querySelectorAll('#mods-panel [aria-label="Mod load order"] .mod-row').length === 2 && !document.querySelector('#mods-panel .file-drop[aria-busy]'), {}, {timeout: 60000});
    const order = () => page.evaluate(() => [...document.querySelectorAll('#mods-panel [aria-label="Mod load order"] .mod-row strong')].map(e => e.textContent));
    assert.deepEqual(await order(), ['F1 Content', 'F1 Override']);
    const label = () => page.locator('#mods-set').textContent();
    assert.equal(await label(), 'Id ' + hash.slice(0, 12));
    await page.getByRole('button', {name: 'Move down F1 Content'}).click();
    await page.waitForFunction(() => document.querySelector('#mods-panel [aria-label="Mod load order"] .mod-row strong').textContent === 'F1 Override');
    // A refresh during a press (the window regains focus) waits for the release: the row is not replaced under the
    // pointer, and the click counts.
    await page.waitForFunction(h => document.getElementById('mods-set').textContent !== 'Id ' + h, hash.slice(0, 12));
    // (The refresh that follows every change can have the button replaced at this moment.)
    const moveUp = page.getByRole('button', {name: 'Move up F1 Content'});
    let moveBox = null; while (!moveBox) { moveBox = await moveUp.boundingBox().catch(() => null); if (!moveBox) await page.waitForTimeout(50); }
    await page.mouse.move(moveBox.x + moveBox.width / 2, moveBox.y + moveBox.height / 2); await page.mouse.down();
    await page.evaluate(() => { window.__pressedRow = document.querySelector('#mods-panel [aria-label="Mod load order"] li:nth-child(2)'); });
    await page.evaluate(() => window.dispatchEvent(new Event('focus'))); await page.waitForTimeout(500);
    assert.equal(await page.evaluate(() => window.__pressedRow.isConnected), true, 'a refresh replaced the row under a pressed button');
    await page.mouse.up();
    await page.waitForFunction(() => document.querySelector('#mods-panel [aria-label="Mod load order"] .mod-row strong').textContent === 'F1 Content');
    pass('a refresh during a press waits for the release; the click counts');
    await page.getByRole('checkbox', {name: 'Enable F1 Override'}).uncheck();
    await page.waitForFunction(() => document.querySelector('#mods-panel [aria-label="Mod load order"] .mod-row[data-enabled="false"]'));
    await page.getByRole('checkbox', {name: 'Enable F1 Override'}).check();
    await page.waitForFunction(h => document.getElementById('mods-set').textContent === 'Id ' + h, hash.slice(0, 12));
    pass('imported F1 through the Mods panel; reorder and enable change the mod set');

    // Launching with mods that have no save asks and creates nothing until Create save; Cancel creates nothing.
    const savesOf = h => page.evaluate(async h => { const {deviceStore} = await import('/web/device-store.mjs'); const me = await (await fetch('/api/auth/me')).json(); return (await deviceStore(me.id).list()).filter(s => s.setup.key === h); }, h);
    const hasSave = async () => (await savesOf(hash)).length > 0;
    await openMods();
    assert.equal(await hasSave(), false);
    await page.locator('#launch').click();
    await page.locator('#no-save-dialog[open]').waitFor({timeout: 60000});
    assert.equal(await page.locator('#no-save-title').textContent(), 'No save for F1 pair');
    assert.equal(await hasSave(), false, 'the dialog creates nothing by itself');
    await page.locator('#no-save-cancel').click();
    await page.waitForFunction(() => !document.getElementById('no-save-dialog'));
    assert.equal(await hasSave(), false, 'Cancel creates nothing'); assert.equal(await page.evaluate(() => !!window.ftlEngine), false);
    assert.equal(await page.locator('#launch').isEnabled(), true);
    pass('launching with mods and no save asks "No save for ..."; neither the dialog nor Cancel creates a save');

    // Launch with mods: session, files map and audio equal the Node merge.
    const cloudBefore = await cloud();
    await launchModded();
    assert.equal(await hasSave(), true, 'Create save made the save');
    const modded = await fingerprint();
    assert.equal(modded.session.hash, hash); assert.equal(modded.session.hyperspace, false); assert.deepEqual(modded.session.mods.map(m => m.name), ['F1 Content', 'F1 Override']);
    assert.equal(modded.gate.modded, true); assert.equal(modded.gate.modSet, hash);
    const got = await page.evaluate(async names => {
      const out = {}; for (const k of names) { const v = window.ftlFiles.get(k); if (!v) { out[k] = null; continue; } const d = await crypto.subtle.digest('SHA-256', v); out[k] = [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, '0')).join(''); }
      return out;
    }, Object.keys(expected));
    assert.deepEqual(got, expected);
    for (const k of merged.removed) assert.equal(await page.evaluate(n => window.ftlFiles.has(n), k), false);
    // Mod audio is fetched lazily from the stored archive and decoded; the fixture's .ogg is not real Ogg data, so only
    // its decoder may reject it (EncodingError), never the lookup or the archive read.
    const audio = await page.evaluate(async names => { const out = {}; for (const k of names) { const id = window.ftlAudio.buffer(k); await window.ftlAudio.buffers.get(id).promise.then(b => { out[k] = b.duration > 0 ? 'decoded' : 'empty'; }, e => { out[k] = e.name; }); } return out; }, expectedAudio);
    assert.deepEqual(audio, Object.fromEntries(expectedAudio.map(k => [k, k.endsWith('.wav') ? 'decoded' : 'EncodingError'])));
    const stats = await page.evaluate(() => window.ftlModLaunchStats);
    assert.equal(stats.cached, false);
    pass(`modded launch: ${Object.keys(expected).length} merged files equal the Node merge, ${expectedAudio.length} mod audio fetched from the archive, merge ${stats.mergeMs} ms`);

    // The modded save is a save of its mod setup: in this browser first, then in the account like any other.
    await page.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1));
    await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).state === 'InGameState', {}, {timeout: 60000});
    const saved = await page.evaluate(() => window.ftlSaveDurably());
    assert.deepEqual([saved.saved, saved.backend, saved.synced], [true, 'device', true]);
    const [record] = await savesOf(hash);
    assert.equal(record.id, saved.id); assert.equal(record.hasRun, true); assert.equal(record.dirty, false);
    assert.deepEqual(record.setup.mods.map(m => [m.name, m.verified]), [['F1 Content', true], ['F1 Override', true]]);
    const listed = await cloud(), inCloud = listed.saves.find(s => s.id === saved.id);
    assert.equal(inCloud.setup.key, hash); assert.equal(inCloud.hasRun, true);
    for (const s of cloudBefore.saves) assert.equal(listed.saves.find(x => x.id === s.id)?.revision, s.revision, 'the save without mods is untouched');
    pass('modded save stored in this browser under its mod setup and synced to the account; the save without mods is untouched');

    // Reload: the merge comes from the cache and gives the same files.
    await openMods();
    // The list is read from storage after the panel opens (and the Hyperspace check reads each archive), so wait for it.
    await page.waitForFunction(() => document.querySelectorAll('#mods-panel [aria-label="Mod load order"] .mod-row').length === 2, {}, {timeout: 15000});
    await launchModded();
    const again = await fingerprint();
    assert.equal((await page.evaluate(() => window.ftlModLaunchStats)).cached, true);
    assert.equal(again.digest, modded.digest);
    pass(`reload: merge cache hit, identical files map (${again.count} files)`);

    // Vanilla again: turn the mods off (the one Launch button then reads "Launch FTL"), unchanged.
    await openMods();
    for (const n of ['F1 Content', 'F1 Override']) await page.getByRole('checkbox', {name: 'Enable ' + n}).uncheck();
    await page.waitForFunction(() => document.getElementById('launch-label').textContent === 'Launch FTL');
    modules.length = 0;
    await launchVanilla();
    assert.equal(await page.locator('#launch-label').textContent().catch(() => 'Launch FTL'), 'Launch FTL');
    const after = await fingerprint();
    assert.equal(after.session, null); assert.equal(after.gate.modded, false);
    assert.equal(after.digest, vanilla.digest); assert.equal(after.count, vanilla.count);
    assert.deepEqual(modules, []);
    pass('vanilla launch after mods: identical files map, no session, no mod module loaded');

    // (on the signed-in page, after the vanilla checks: the worker is the one the Mods launch uses)
    const b64 = b => Buffer.from(b).toString('base64');
    const luaGot = await page.evaluate(async ({zip, text, names}) => {
      const bytes = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
      const worker = new Worker('/web/mods/worker.mjs', {type: 'module'});
      const result = await new Promise((resolve, reject) => {
        worker.onmessage = ({data}) => { if (data.progress) return; worker.terminate(); if (data.error) reject(Error(data.error)); else resolve(data.result); };
        worker.onerror = e => { worker.terminate(); reject(Error('worker: ' + e.message)); };
        worker.postMessage({id: 1, action: 'merge', archives: [{name: 'lua.zip', file: new Blob([bytes(zip)])}], baseNames: names,
          baseText: text.map(([n, s]) => [n, bytes(s)]), baseSizes: text.map(([n, s]) => [n, bytes(s).length]), cached: null});
      });
      const out = {};
      for (const [k, v] of result.cache.text) out[k] = [...new Uint8Array(await crypto.subtle.digest('SHA-256', v))].map(x => x.toString(16).padStart(2, '0')).join('');
      return out;
    }, {zip: b64(luaZip), text: luaBaseNames.map(k => [k, b64(base.get(k))]), names: luaBaseNames});
    assert.deepEqual(luaGot, luaExpected);
    pass(`Lua patch script in the merge worker: ${Object.keys(luaExpected).length} files equal the Node merge`);
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
}
