// Save model 2 in the website (docs/SAVES.md), Chromium and Firefox, against the disposable 8084 fixture. Every browser
// context is one device (its own device store and device id); a second page in a context is another tab.
// Covers: offline play that keeps going and syncs later; the missing-content notice; two tabs (lock line, Override, the
// overridden tab stops at once); another device (lock line, Override, the overridden game's notice once, then an
// ordinary "In use" lock, then the free save taken after the notice); both conflict choices; the one-time move of the
// old browser stores with its backup; Verified and Custom local mod files, a modded save that syncs, "missing mod" on
// another device and Saves > Advanced.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {chromium, firefox} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {testCatalogue} from './fixtures/mods/catalogue.mjs';
import {zipSync} from '../web/vendor/fflate-0.8.3.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084';
assert.equal(origin, 'http://localhost:8084', 'Use the disposable 8084 fixture');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 120) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const selected = (process.env.MENU_BROWSERS ?? 'Chromium,Firefox').split(',');
const sha = b => createHash('sha256').update(b).digest('hex');
const {archives: {content}} = testCatalogue();
const enc = new TextEncoder();
// A mod file of the player's own (no catalogue entry): accepted, badged Custom.
const custom = Buffer.from(zipSync({'mod-appendix/metadata.xml': [enc.encode('<?xml version="1.0"?>\n<metadata><title>My Tweaks</title><version>0.1</version></metadata>\n'), {level: 6}],
  'data/text_misc.xml.append': [enc.encode('<text name="my_tweak">Mine</text>'), {level: 6}]}, {mtime: new Date(Date.UTC(2026, 0, 1))}));
const checks = [];

const ready = page => page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
async function signIn(page, user) {
  await page.goto(origin); await ready(page);
  await page.evaluate(async ({user, password, initial}) => {
    let csrf = (await (await fetch('/api/auth/csrf')).json()).token;
    const post = (p, body) => fetch('/api/auth/' + p, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify(body)});
    let r = await post('login', {username: user, password}); if (r.status === 401) r = await post('login', {username: user, password: initial}); if (!r.ok) throw Error('login ' + r.status);
    csrf = (await (await fetch('/api/auth/csrf')).json()).token;
    if ((await (await fetch('/api/auth/me')).json()).mustChangePassword) { r = await post('password', {currentPassword: initial, newPassword: password}); if (!r.ok) throw Error('rotation'); csrf = (await (await fetch('/api/auth/csrf')).json()).token; await post('login', {username: user, password}); }
  }, {user, password: 'saves-browser-password-' + user, initial: initial.get(user)});
}
/** A device: a browser context with its own storage; `name` sets the device name the locks show. */
async function device(browser, name = null, id = null) {
  const context = await browser.newContext({viewport: {width: 1280, height: 900}, acceptDownloads: true});
  if (name) await context.addInitScript(({id, name}) => { try { localStorage.setItem('ftl-device', JSON.stringify({id, name})); } catch { /* about:blank */ } }, {id, name});
  return context;
}
function tab(page, errors) {
  page.setDefaultTimeout(30000);
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => { errors.push('native dialog: ' + d.message()); d.dismiss(); });
  return page;
}
/** A JSON API call from the page with CSRF (and any headers); {status, body}. */
const api = (page, path, {method = 'GET', body, headers = {}} = {}) => page.evaluate(async ({path, method, body, headers}) => {
  const csrf = method === 'GET' ? null : (await (await fetch('/api/auth/csrf')).json()).token;
  const r = await fetch('/api' + path, {method, headers: {'Content-Type': 'application/json', ...(csrf ? {'X-CSRF-TOKEN': csrf} : {}), ...headers}, body: body === undefined ? undefined : JSON.stringify(body)});
  const text = await r.text(); return {status: r.status, body: text ? JSON.parse(text) : null};
}, {path, method, body, headers});
/** The cloud copy of a save, read outside the page (page routes do not apply). */
/** Polls `test` (an async check in Node) until it holds. */
async function until(test, what, timeout = 30000) { const end = Date.now() + timeout; while (!await test()) { if (Date.now() > end) throw Error('Timed out: ' + what); await new Promise(r => setTimeout(r, 200)); } }
const cloud = async (context, id) => (await context.request.get(origin + '/api/saves/' + id)).json();
const deviceSave = (page, id) => page.evaluate(async id => {
  const {deviceStore, LOCAL_ACCOUNT} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
  return deviceStore(me.local ? LOCAL_ACCOUNT : me.id).get(id);
}, id);
/** Progress made on this device that has not synced: an XML comment after the run marks it. */
const playHere = (page, id, mark) => page.evaluate(async ({id, mark}) => {
  const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
  return deviceStore(me.id).update(id, cur => ({...cur, run: cur.run.replace(/(<!--[^>]*-->)?\s*$/, '<!--' + mark + '-->'), dirty: true}));
}, {id, mark});
const syncNow = page => page.evaluate(() => window.ftlSyncNow());
const savesPage = async page => { await page.goto(origin + '/?view=saves'); await ready(page); await page.locator('#saves-list li.save-row').first().waitFor(); };
const row = (page, id) => page.locator(`#saves-list li.save-row[data-id="${id}"]`);
const engineUp = async page => {
  await page.waitForFunction(() => !!window.ftlEngine && JSON.parse(window.ftlEngine.debugSnapshot()).state === 'MainMenuState', {}, {timeout: 180000});
  if (JSON.parse(await page.evaluate(() => window.ftlEngine.debugSnapshot())).window === 'WelcomeChoiceBox') { await page.locator('#game').press('1'); await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).window === 'main'); }
};
const state = (page, s) => page.waitForFunction(s => JSON.parse(window.ftlEngine.debugSnapshot()).state === s, s, {timeout: 60000});
async function click(page, x, y) { const box = await page.locator('#game').boundingBox(), scale = Math.min(box.width / 1280, box.height / 720); await page.mouse.click(box.x + (box.width - 1280 * scale) / 2 + x * scale, box.y + (box.height - 720 * scale) / 2 + y * scale); }
/** Save and close: the page reloads on the launcher. */
const saveAndClose = async page => { await Promise.all([page.waitForEvent('load', {timeout: 60000}), page.locator('#save-close').click()]); await ready(page); };
const openDialog = (page, id) => page.locator(`dialog#${id}[open]`);

for (const [name, type, options, users] of [['Chromium', chromium, {channel: 'chromium'}, ['user01', 'user02']], ['Firefox', firefox, {}, ['user05', 'user06']]].filter(([b]) => selected.includes(b))) {
  const browser = withOnboarding(await type.launch({headless: !(name === 'Firefox' && process.env.RUNNER_FIREFOX_WINDOWED === '1'), ...options}), origin);
  const pass = label => { checks.push({browser: name, label}); console.log(`PASS ${name}: ${label}`); };
  const errors = [];
  try {
    // ---- One device: offline play, then sync --------------------------------------------------------------------------
    const laptop = await device(browser), a = tab(await laptop.newPage(), errors);
    await signIn(a, users[0]);
    await a.goto(origin); await ready(a);
    await a.locator('#launch').click(); await engineUp(a);
    await a.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await state(a, 'InGameState');
    const first = await a.evaluate(() => window.ftlSaveDurably());
    assert.deepEqual([first.saved, first.backend, first.synced], [true, 'device', true]);
    const id = first.id, laptopName = await a.evaluate(() => JSON.parse(localStorage.getItem('ftl-device')).name);
    let c = await cloud(laptop, id);
    assert.equal(c.name, 'Save 1'); assert.match(c.run, /PLAYER_SHIP_HARD/); assert.equal(c.lock.device, laptopName); assert.equal(c.lock.platform, 'browser');
    const revision = c.revision;
    // The account becomes unreachable: the game goes on and every save lands in this browser.
    await a.route('**/api/saves**', r => r.abort());
    await a.evaluate(() => window.ftlEngine.consoleExecute('rich')); await a.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).scrap === 5000);
    const offline = await a.evaluate(() => window.ftlSaveDurably());
    assert.deepEqual([offline.saved, offline.synced], [true, false]);
    assert.equal((await deviceSave(a, id)).dirty, true); assert.equal((await deviceSave(a, id)).meta.summary.scrap, 5000);
    await a.waitForTimeout(500);
    assert.equal(JSON.parse(await a.evaluate(() => window.ftlEngine.debugSnapshot())).state, 'InGameState', 'the game keeps running offline');
    assert.equal(await a.locator('dialog[open]').count(), 0, 'nothing asks while offline');
    await saveAndClose(a);
    await a.locator('#saves-button').click(); await row(a, id).waitFor();
    await a.waitForFunction(() => /Offline/.test(document.getElementById('saves-sync').textContent));
    assert.equal(await row(a, id).locator('[data-badge="pending"]').textContent(), 'Not synced');
    c = await cloud(laptop, id); assert.equal(c.revision, revision); assert.notEqual(c.meta.summary.scrap, 5000);
    await a.unroute('**/api/saves**');
    assert.equal((await syncNow(a)).online, true);
    c = await cloud(laptop, id);
    assert.equal(c.revision, revision + 1); assert.equal(c.meta.summary.scrap, 5000); assert.equal(c.lock, null, 'Save and close gave the lock back once online');
    await a.waitForFunction(id => !document.querySelector(`li.save-row[data-id="${id}"] [data-badge="pending"]`), id);
    pass('offline play goes on, saves to this browser ("Not synced"), and uploads with the lock released once the account answers');

    // ---- What a save names that the game files lack is dropped, and the page says what --------------------------------
    const local = await deviceSave(a, id);
    assert.match(local.run, /<animations/);
    await a.evaluate(async ({id, run}) => {
      const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
      await deviceStore(me.id).update(id, cur => ({...cur, run, dirty: true}));
    }, {id, run: local.run.replace('<animations', '<augment name="TEST_MOD_ONLY_AUGMENT"/><animations')});
    await savesPage(a);
    await row(a, id).locator('[data-action="play"]').click(); await engineUp(a);
    await click(a, 1180, 295); await state(a, 'InGameState');
    await openDialog(a, 'missing-content-dialog').waitFor();
    assert.deepEqual(await a.locator('#missing-content-list li').allTextContents(), ['TEST_MOD_ONLY_AUGMENT']);
    await a.locator('#missing-content-dialog-ok').click();
    assert.equal(JSON.parse(await a.evaluate(() => window.ftlEngine.debugSnapshot())).scrap, 5000, 'the rest of the run loaded');
    const after = await a.evaluate(() => window.ftlSaveDurably());
    assert.equal(after.synced, true); assert.doesNotMatch((await cloud(laptop, id)).run, /TEST_MOD_ONLY_AUGMENT/, 'the next save keeps the run without it');
    pass('a save naming content the game files lack loads without it and the page lists what was dropped');

    // ---- Two tabs of one browser ----------------------------------------------------------------------------------------
    const b = tab(await laptop.newPage(), errors);
    await savesPage(b);
    await b.waitForFunction(id => /In use on another tab of this browser/.test(document.querySelector(`li.save-row[data-id="${id}"] .save-lock`)?.textContent ?? ''), id);
    await row(b, id).locator('[data-action="play"]').click();
    await openDialog(b, 'save-lock-dialog').waitFor();
    assert.equal(await b.locator('#save-lock-dialog-title').textContent(), 'In use on another tab of this browser');
    await b.locator('#save-lock-dialog-yes').click(); await engineUp(b);
    await openDialog(a, 'save-overridden-dialog').waitFor();
    assert.match(await a.locator('#save-overridden-dialog').innerText(), /taken over in another tab of this browser/);
    await Promise.all([a.waitForURL(/view=saves/), a.locator('#save-overridden-dialog-ok').click()]); await ready(a);
    assert.equal((await b.evaluate(() => window.ftlSaveDurably())).synced, true, 'the tab that took over saves and syncs');
    await syncNow(a); await savesPage(a);
    await row(a, id).locator('[data-action="play"]').click();
    await openDialog(a, 'save-lock-dialog').waitFor();
    assert.equal(await a.locator('dialog#save-overridden-dialog').count(), 0, 'the notice is not repeated');
    await a.locator('#save-lock-dialog-no').click();
    await saveAndClose(b); await b.close();
    await syncNow(a); await row(a, id).locator('[data-action="play"]').click(); await engineUp(a);
    assert.equal(await a.locator('dialog[open]').count(), 0, 'once the other tab closed, the save plays here without a question');
    pass('two tabs: the second sees "In use on another tab", overrides, the first stops at once with its notice and later plays again without one');

    // ---- Another device -------------------------------------------------------------------------------------------------
    const phoneContext = await device(browser, 'Pixel 8', 'test-pixel-8'), p = tab(await phoneContext.newPage(), errors);
    await signIn(p, users[0]); await savesPage(p);
    await p.waitForFunction(({id, laptopName}) => new RegExp('In use on ' + laptopName + ' · last seen \\d').test(document.querySelector(`li.save-row[data-id="${id}"] .save-lock`)?.textContent ?? ''), {id, laptopName});
    await row(p, id).locator('[data-action="play"]').click();
    await openDialog(p, 'save-lock-dialog').waitFor();
    assert.equal(await p.locator('#save-lock-dialog-title').textContent(), 'In use on ' + laptopName);
    assert.match(await p.locator('#save-lock-dialog-warning').textContent(), /may have progress and unlocks that have not synced yet\. Overriding discards them; nothing is kept\./);
    await p.locator('#save-lock-dialog-yes').click(); await engineUp(p);
    const taken = await cloud(phoneContext, id);
    assert.equal(taken.lock.device, 'Pixel 8');
    // The laptop's game hears it on its next contact (its next save) and stops; nothing it wrote reaches the account.
    await a.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await state(a, 'InGameState');
    await a.evaluate(() => window.ftlSaveDurably());
    await openDialog(a, 'save-overridden-dialog').waitFor();
    assert.match(await a.locator('#save-overridden-dialog').innerText(), /This save was taken over on Pixel 8 at \d{1,2}:\d\d( [AP]M)?\. Progress and unlocks since your last sync were discarded\./);
    assert.equal((await cloud(laptop, id)).revision, taken.revision);
    await Promise.all([a.waitForURL(/view=saves/), a.locator('#save-overridden-dialog-ok').click()]); await ready(a);
    await row(a, id).locator('.save-lock').waitFor();
    assert.match(await row(a, id).locator('.save-lock').textContent(), /In use on Pixel 8/);
    await row(a, id).locator('[data-action="play"]').click();
    await openDialog(a, 'save-lock-dialog').waitFor();
    assert.equal(await a.locator('#save-lock-dialog-title').textContent(), 'In use on Pixel 8', 'after the notice: an ordinary lock with Override');
    assert.equal(await a.locator('dialog#save-overridden-dialog').count(), 0);
    await a.locator('#save-lock-dialog-no').click();
    await saveAndClose(p);
    await syncNow(a); await row(a, id).locator('[data-action="play"]').click(); await engineUp(a);
    assert.equal(await a.locator('dialog[open]').count(), 0, 'free again: taken without another notice');
    assert.equal((await cloud(laptop, id)).lock.device, laptopName);
    await saveAndClose(a);
    pass('another device: lock line with device and time, Override with its warning, the overridden game stops with "taken over on Pixel 8" once, then an ordinary lock, then the free save plays');

    // ---- Changed in two places ------------------------------------------------------------------------------------------
    await syncNow(a);
    const put = async (mark) => {
      const cur = await cloud(phoneContext, id);
      const r = await api(p, '/saves/' + id, {method: 'PUT', headers: {'If-Match': `"${cur.revision}"`, 'X-FTL-Instance': 'saves-browser-phone', 'X-FTL-Device': 'browser;test-pixel-8;Pixel%208'},
        body: {name: cur.name, setup: cur.setup, meta: cur.meta, run: cur.run.replace(/(<!--[^>]*-->)?\s*$/, '<!--' + mark + '-->'), kind: 'manual'}});
      assert.equal(r.status, 200, JSON.stringify(r.body)); return r.body.revision;
    };
    await playHere(a, id, 'laptop-1'); await put('phone-1');
    await syncNow(a); await savesPage(a);
    // The list shows the device copy at once ("Not synced") and the account's answer when the page's first pass is done.
    await a.waitForFunction(id => document.querySelector(`li.save-row[data-id="${id}"]`)?.dataset.status === 'CONFLICT', id);
    assert.equal(await row(a, id).locator('[data-badge="conflict"]').textContent(), 'Changed in two places');
    // "Use the cloud copy" needs no lock: the account's version replaces this device's and the game starts.
    await row(a, id).locator('[data-action="play"]').click();
    await openDialog(a, 'save-conflict-dialog').waitFor();
    await a.locator('#save-conflict-cloud').click(); await openDialog(a, 'save-conflict-dialog').waitFor({state: 'detached'});
    await until(async () => { const s = await deviceSave(a, id); return !s.dirty && s.run.endsWith('<!--phone-1-->'); }, 'the cloud copy replaces this device\'s');
    await engineUp(a);
    assert.match((await cloud(laptop, id)).run, /phone-1-->$/);
    await saveAndClose(a);
    // "Keep this device's copy" while the phone plays the save: the usual lock question, never a silent override.
    await playHere(a, id, 'laptop-2'); const phoneRevision = await put('phone-2');
    const phoneHolds = await api(p, '/saves/' + id + '/lock', {method: 'POST', headers: {'X-FTL-Instance': 'saves-browser-phone', 'X-FTL-Device': 'browser;test-pixel-8;Pixel%208'}, body: {override: false}});
    assert.equal(phoneHolds.status, 200, JSON.stringify(phoneHolds.body));
    await syncNow(a); await savesPage(a);
    await a.waitForFunction(id => document.querySelector(`li.save-row[data-id="${id}"]`)?.dataset.status === 'CONFLICT', id);
    const keepMine = async () => {
      await row(a, id).locator('[data-action="play"]').click();
      await openDialog(a, 'save-conflict-dialog').waitFor();
      await a.locator('#save-conflict-mine').click(); await openDialog(a, 'save-conflict-dialog').waitFor({state: 'detached'});
      await openDialog(a, 'save-lock-dialog').waitFor();
      assert.equal(await a.locator('#save-lock-dialog-title').textContent(), 'In use on Pixel 8');
    };
    await keepMine();
    c = await cloud(laptop, id);
    assert.deepEqual([c.revision, c.lock.device], [phoneRevision, 'Pixel 8'], 'nothing changed while the question is open');
    await a.locator('#save-lock-dialog-no').click(); await openDialog(a, 'save-lock-dialog').waitFor({state: 'detached'});
    await a.waitForTimeout(500);
    c = await cloud(laptop, id);
    assert.deepEqual([c.revision, c.lock.device], [phoneRevision, 'Pixel 8'], 'Cancel leaves the account\'s copy and the phone\'s lock');
    assert.match((await deviceSave(a, id)).run, /laptop-2-->$/); assert.equal(await a.evaluate(() => !!window.ftlEngine), false);
    await keepMine();
    await a.locator('#save-lock-dialog-yes').click(); await engineUp(a);
    c = await cloud(laptop, id);
    assert.equal(c.revision, phoneRevision + 1); assert.match(c.run, /laptop-2-->$/); assert.equal(c.lock.device, laptopName);
    const snapshots = (await laptop.request.get(origin + '/api/saves/' + id + '/snapshots').then(r => r.json()));
    assert.ok((snapshots.snapshots ?? snapshots).some(s => s.revision === phoneRevision), 'the replaced cloud version stays a recovery snapshot');
    await saveAndClose(a);
    assert.equal((await cloud(laptop, id)).lock, null);
    pass('changed in two places: "Use the cloud copy" takes the account\'s version and plays; "Keep this device\'s copy" while another device plays asks "In use on Pixel 8" (Cancel changes nothing), and only Override replaces the account\'s version (kept as a snapshot) and plays');
    await phoneContext.close(); await laptop.close();

    // ---- Mods: Verified and Custom files, a modded save that syncs, "missing mod" elsewhere -------------------------------
    const modContext = await device(browser), modder = tab(await modContext.newPage(), errors);
    await signIn(modder, users[1]);
    await modder.goto(origin + '/?view=play'); await ready(modder);
    await modder.locator('#mods-panel > summary').click(); await modder.locator('#mods-catalogue .catalogue-row').first().waitFor();
    await modder.locator('#mods-root .file-drop input[type=file]').setInputFiles([{name: 'renamed-content.zip', mimeType: 'application/zip', buffer: Buffer.from(content.bytes)}, {name: 'my-tweaks.zip', mimeType: 'application/zip', buffer: custom}]);
    await modder.waitForFunction(() => document.querySelectorAll('#mods-root [data-badge="verified"]').length === 1 && document.querySelectorAll('#mods-root [data-badge="custom"]').length === 1);
    await modder.waitForFunction(() => /with mods/.test(document.getElementById('launch-label').textContent));
    await modder.locator('#saves-button').click(); await modder.locator('#new-save').click();
    await openDialog(modder, 'new-save-dialog').waitFor();
    assert.equal(await modder.locator('#new-save-mods li').count(), 2, 'the enabled mods are listed');
    await modder.locator('#new-save-name').fill('Modded run'); await modder.locator('#new-save-create').click();
    const modded = modder.locator('#saves-list li.save-row', {hasText: 'Modded run'});
    await modded.waitFor();
    const moddedId = await modded.getAttribute('data-id');
    await syncNow(modder);
    const moddedCloud = await cloud(modContext, moddedId);
    assert.deepEqual(moddedCloud.setup.mods.map(m => [m.sha256, m.verified, m.size]), [[sha(content.bytes), true, content.bytes.length], [sha(custom), false, custom.length]]);
    assert.equal(moddedCloud.setup.mods[0].name, 'F1 Content', 'a Verified file carries the catalogue name');
    assert.equal(moddedCloud.setup.mods[1].name, 'my-tweaks.zip');
    pass('local mod files: the catalogue file under another name is Verified, the player\'s own is Custom; the modded save syncs with its ordered mod list');
    // Another device lists it, names what it lacks and refuses to start without the Custom file.
    const other = await device(browser, 'Desk PC', 'test-desk-pc'), d = tab(await other.newPage(), errors);
    await signIn(d, users[1]); await savesPage(d);
    const elsewhere = row(d, moddedId);
    await elsewhere.locator('.save-missing').first().waitFor();
    const lines = await elsewhere.locator('.save-missing').allTextContents();
    assert.ok(lines.includes('missing mod: my-tweaks.zip, ' + sha(custom)), lines.join(' | '));
    assert.ok(lines.includes('Downloads when played: F1 Content 1'), lines.join(' | '));
    await elsewhere.locator('[data-action="play"]').click();
    await d.waitForFunction(s => document.getElementById('message').textContent.includes('missing mod: my-tweaks.zip, ' + s), sha(custom));
    assert.equal(await d.evaluate(() => !!window.ftlEngine), false);
    pass('another device shows "missing mod: name, sha256" for a Custom file and does not start the save without it');
    // Saves > a save > Advanced: the mods of a save change only there, with a warning.
    await savesPage(modder);
    await modded.locator('details.row-menu > summary').click(); await modded.locator('[data-action="mods"]').click();
    await openDialog(modder, 'save-mods-dialog').waitFor();
    assert.match(await modder.locator('#save-mods-dialog-warning').textContent(), /can break this run/);
    await modder.locator('#save-mods-list').getByRole('button', {name: 'Remove my-tweaks.zip'}).click();
    await modder.locator('#save-mods-apply').click(); await openDialog(modder, 'save-mods-dialog').waitFor({state: 'detached'});
    await syncNow(modder);
    assert.deepEqual((await cloud(modContext, moddedId)).setup.mods.map(m => m.sha256), [sha(content.bytes)]);
    await syncNow(d); await savesPage(d);
    assert.deepEqual(await row(d, moddedId).locator('.save-missing').allTextContents(), ['Downloads when played: F1 Content 1']);
    pass('Saves > Advanced changes a save\'s mods after a warning; the change syncs to the other device');
    await other.close(); await modContext.close();

    // ---- The old browser stores move once, after a backup ------------------------------------------------------------
    const oldContext = await browser.newContextWithoutOnboarding({viewport: {width: 1280, height: 900}, acceptDownloads: true}), o = tab(await oldContext.newPage(), errors);
    const OLD_RUN = '<xftlSaveGame><playerShip shipId="PLAYER_SHIP_HARD" displayName="Old Kestrel"><resources SCRAP="77"/></playerShip></xftlSaveGame>';
    const PARKED_RUN = OLD_RUN.replace('Old Kestrel', 'Parked Kestrel').replace('77', '12');
    await o.goto(origin + '/health/ready');
    await o.evaluate(async ({run, parked}) => {
      const db = await new Promise((resolve, reject) => { const r = indexedDB.open('ftl-local-saves', 1); r.onupgradeneeded = () => { for (const s of ['current', 'slots', 'history']) r.result.createObjectStore(s); }; r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
      const at = '2026-10-20T10:00:00.000Z';
      await new Promise(resolve => { const tx = db.transaction(['current', 'slots'], 'readwrite');
        tx.objectStore('current').put({schema: 1, revision: 3, run, profile: '<xftl-profile><old/></xftl-profile>', slot: 1, savedUtc: at, engineBuild: 'old', assetVersion: 'old'}, 'save');
        tx.objectStore('slots').put({slot: 1, name: 'Old one', createdUtc: at}, 1); tx.objectStore('slots').put({slot: 2, name: 'Parked', run: parked, playedUtc: at, createdUtc: at}, 2);
        tx.oncomplete = resolve; });
      db.close();
      localStorage.setItem('ftl-local-save:v1', JSON.stringify({schema: 1, revision: 2, run: null, profile: null}));
    }, {run: OLD_RUN, parked: PARKED_RUN});
    await o.goto(origin); await ready(o);
    await o.locator('#consent-agree').click(); await o.locator('#mode-local').click();
    await o.waitForSelector('body[data-auth-state="local"][data-session-ready="true"]');
    await o.locator('#saves-button').click();
    await o.waitForFunction(() => document.querySelectorAll('#saves-list li.save-row').length === 2);
    const names = await o.locator('#saves-list li.save-row strong').allTextContents();
    assert.deepEqual(names.sort(), ['Old one', 'Parked']);
    assert.match(await o.locator('#saves-list li.save-row', {hasText: 'Old one'}).locator('.save-line').textContent(), /Old Kestrel · 77 scrap/);
    const held = await o.evaluate(async () => { const {deviceStore, LOCAL_ACCOUNT} = await import('/web/device-store.mjs'), store = deviceStore(LOCAL_ACCOUNT); return Promise.all((await store.list()).map(async l => (await store.get(l.id)).profile)); });
    assert.deepEqual(held, ['<xftl-profile><old/></xftl-profile>', '<xftl-profile><old/></xftl-profile>'], 'every save of the old slots gets a copy of the profile');
    await o.locator('#saves-old-card').waitFor();
    const [backup] = await Promise.all([o.waitForEvent('download'), o.locator('#saves-old-download').click()]);
    assert.equal(backup.suggestedFilename(), 'ftl-old-saves.json');
    const kept = JSON.parse(await readFile(await backup.path(), 'utf8'));
    assert.equal(kept.schema, 'ftl-old-saves'); assert.equal(kept.local.current[0].value.run, OLD_RUN); assert.equal(kept.local.slots.length, 2);
    assert.ok(await o.evaluate(async () => (await indexedDB.databases()).some(d => d.name === 'ftl-local-saves')), 'the old store stays until it is removed');
    await o.reload(); await ready(o); await o.locator('#saves-button').click();
    await o.waitForFunction(() => document.querySelectorAll('#saves-list li.save-row').length === 2); await o.waitForTimeout(500);
    assert.equal(await o.locator('#saves-list li.save-row').count(), 2, 'moved once');
    await o.locator('#saves-old-remove').click(); await o.locator('#old-saves-remove-dialog-yes').click();
    await o.locator('#saves-old-card').waitFor({state: 'hidden'});
    assert.equal(await o.evaluate(async () => (await indexedDB.databases()).some(d => d.name === 'ftl-local-saves')), false);
    assert.equal(await o.evaluate(() => localStorage.getItem('ftl-local-save:v1')), null);
    assert.equal(await o.locator('#saves-list li.save-row').count(), 2);
    pass('old browser saves (active and parked slot) move into the list once; a backup is kept and downloadable until the old data is removed');
    await oldContext.close();
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
}
console.log(`${checks.length} saves-browser checks passed.`);
