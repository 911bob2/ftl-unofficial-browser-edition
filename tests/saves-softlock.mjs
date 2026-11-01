// A save is soft-locked to its mod files by hash (docs/SAVES.md, docs/MODS.md), in the website, Chromium and Firefox,
// against the disposable 8084 fixture. Covers: a save names its mods by SHA-256 and mod-set hash; a new save in a
// Hyperspace set asks nothing about vanilla progress and holds only the default unlocks, whatever other saves hold; with
// a mod file missing or different (same name, other bytes) Play refuses and names the missing mod, and the enabled mods
// do not touch the save; Saves > the save > Advanced: mods is the only way to change them, behind its warning, and keeps
// the save's unlocks. Without SOFTLOCK_HS_FILES a small Hyperspace archive of our own stands in for the real files
// (no mod content in Git); SOFTLOCK_HS_FILES=<file,file> runs it on real ones, SOFTLOCK_OUT=<dir> keeps screenshots.
import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash, randomBytes} from 'node:crypto';
import {chromium, firefox} from 'playwright';
import {onboardingCookies, withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError} from './lib/launch.mjs';
import {zipSync} from '../web/vendor/fflate-0.8.3.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084';
assert.equal(origin, 'http://localhost:8084', 'Use the disposable 8084 fixture');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 120) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const selected = (process.env.MENU_BROWSERS ?? 'Chromium,Firefox').split(',');
const sha = b => createHash('sha256').update(b).digest('hex');
const setHash = shas => sha(JSON.stringify({loader: 1, mods: shas}));
const enc = new TextEncoder();
const zip = entries => Buffer.from(zipSync(Object.fromEntries(Object.entries(entries).map(([k, v]) => [k, [enc.encode(v), {level: 6}]])), {mtime: new Date(Date.UTC(2026, 0, 1))}));
const meta = (title, version) => `<?xml version="1.0"?>\n<metadata><title>${title}</title><version>${version}</version></metadata>\n`;
// The player's own file, and another file under the same name.
const tweak = line => zip({'mod-appendix/metadata.xml': meta('My Tweaks', '0.1'), 'data/text_misc.xml.append': `<text name="my_tweak">${line}</text>`});
const mine = tweak('Mine'), other = tweak('Not the same bytes');
const standIn = () => [{name: 'Hyperspace.ftl', mimeType: 'application/zip', buffer: zip({'mod-appendix/metadata.xml': meta('Hyperspace', '1.23.2'), 'data/hyperspace.xml': '<FTL>\n<version>^1.0.0</version>\n</FTL>\n'})}];
const hsFiles = process.env.SOFTLOCK_HS_FILES ? await Promise.all(process.env.SOFTLOCK_HS_FILES.split(',').map(async p => ({path: p, buffer: await readFile(p)}))) : standIn();
const out = process.env.SOFTLOCK_OUT;
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
  }, {user, password: 'saves-softlock-password-' + user, initial: initial.get(user)});
}
const deviceSave = (page, id) => page.evaluate(async id => {
  const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
  return deviceStore(me.id).get(id);
}, id);
const giveProfile = (page, id, xml) => page.evaluate(async ({id, xml}) => {
  const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
  return deviceStore(me.id).update(id, cur => ({...cur, profile: xml, hasProfile: true, dirty: true}));
}, {id, xml});
const cloud = async (context, id) => (await context.request.get(origin + '/api/saves/' + id)).json();
const syncNow = page => page.evaluate(() => window.ftlSyncNow());
const savesPage = async page => { await page.goto(origin + '/?view=saves'); await ready(page); await page.locator('#saves-list li.save-row').first().waitFor(); };
const row = (page, id) => page.locator(`#saves-list li.save-row[data-id="${id}"]`);
const openDialog = (page, id) => page.locator(`dialog#${id}[open]`);
const saveAndClose = async page => { await Promise.all([page.waitForEvent('load', {timeout: 60000}), page.locator('#save-close').click()]); await ready(page); };
const engineRunning = page => page.evaluate(() => !!window.ftlEngine);
const unlocksOf = xml => (xml ?? '').match(/<(ship|layout|layoutVictory|ach)\s[^>]*>/g) ?? [];
const UNLOCKS = '<ship unlockId="engi" diff="EASY"/><layout ship="PLAYER_SHIP_FED" diff="EASY"/><ach id="ENGI_1" diff="EASY"/>';
const WITH_UNLOCKS = `<xftl-profile listTutorial="0">${UNLOCKS}</xftl-profile>`;

/** New save through its dialog; returns its id. */
async function newSave(page, name) {
  await page.locator('#new-save').click(); await openDialog(page, 'new-save-dialog').waitFor();
  await page.locator('#new-save-name').fill(name); await page.locator('#new-save-create').click();
  const made = page.locator('#saves-list li.save-row', {hasText: name}); await made.waitFor();
  return made.getAttribute('data-id');
}
/** Play on a row until the main menu; on the first start the language list answers English. */
async function play(page, id, shot = null) {
  await row(page, id).locator('[data-action="play"]').click();
  await page.waitForFunction(() => {
    const failed = !document.getElementById('message').hidden && document.getElementById('message').textContent;
    return failed || !!window.ftlEngine && JSON.parse(window.ftlEngine.debugSnapshot()).state === 'MainMenuState';
  }, {}, {timeout: 300000});
  assert.equal(await engineRunning(page), true, await page.locator('#message').textContent());
  if (JSON.parse(await page.evaluate(() => window.ftlEngine.debugSnapshot())).window === 'WelcomeChoiceBox') { await page.locator('#game').press('1'); await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).window === 'main'); }
  if (shot && out) { await page.waitForTimeout(1500); await page.screenshot({path: join(out, shot)}); }
}
async function refused(page, id, name, hash) {
  await row(page, id).locator('[data-action="play"]').click();
  await page.waitForFunction(({name, hash}) => document.getElementById('message').textContent.includes('missing mod: ' + name + ', ' + hash), {name, hash});
  assert.equal(await engineRunning(page), false, 'the game does not start');
}

for (const [name, type, options, user] of [['Chromium', chromium, {channel: 'chromium'}, 'user01'], ['Firefox', firefox, {}, 'user05']].filter(([b]) => selected.includes(b))) {
  // Real files in Chromium use a regular profile, as a player has. Playwright's usual context is off the record: its quota is
  // 2 or 3 GiB at random per browser start, and at 2 GiB it refuses the 679 MB file. The fill stays there, not on this disk.
  const profile = process.env.SOFTLOCK_HS_FILES && !process.env.SOFTLOCK_FILL_QUOTA && name === 'Chromium' ? await mkdtemp(join(tmpdir(), 'softlock-')) : null;
  const persistent = profile && await type.launchPersistentContext(profile, {headless: true, viewport: {width: 1280, height: 900}, ...options});
  if (persistent) await persistent.addCookies(onboardingCookies(origin));
  const browser = persistent ? {close: async () => { await persistent.close(); await rm(profile, {recursive: true, force: true}); }} : withOnboarding(await type.launch({headless: !(name === 'Firefox' && process.env.RUNNER_FIREFOX_WINDOWED === '1'), ...options}), origin);
  const pass = label => { checks.push({browser: name, label}); console.log(`PASS ${name}: ${label}`); };
  const errors = [], context = persistent ?? await browser.newContext({viewport: {width: 1280, height: 900}}), page = context.pages()[0] ?? await context.newPage();
  page.setDefaultTimeout(60000);
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => { errors.push('native dialog: ' + d.message()); d.dismiss(); });
  page.on('console', e => { if (e.type() === 'error' && !expectedConsoleError(e)) console.log('browser console:', e.text()); });
  try {
    await signIn(page, user);
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    const addFiles = files => page.locator('#mods-root .file-drop input[type=file]').setInputFiles(files);

    // ---- Too little browser storage: the file is refused at once, with the reason, in both ways it can happen --------
    const big = hsFiles.reduce((a, b) => (b.buffer.length > a.buffer.length ? b : a));
    const bigFile = big.buffer.length > 16 << 20 ? big.path ?? big : {name: 'too-big.zip', mimeType: 'application/zip', buffer: Buffer.from(zipSync({'mod-appendix/metadata.xml': [enc.encode(meta('Too big', '1')), {level: 0}], 'data/big.bin': [randomBytes(32 << 20), {level: 0}]}))};
    const bigSize = (bigFile.buffer ?? big.buffer).length, lowQuota = bigSize >> 1, outerPage = page;
    const refusedBig = async (how, page = outerPage) => {
      await page.evaluate(() => { document.getElementById('message').textContent = ''; });
      const started = Date.now();
      await page.locator('#mods-root .file-drop input[type=file]').setInputFiles([bigFile]);
      await page.waitForFunction(() => /not enough browser storage/i.test(document.getElementById('message').textContent) && !document.querySelector('#mods-root .file-drop[aria-busy]'), {}, {timeout: 120000});
      const ms = Date.now() - started;
      assert.equal(await page.locator('#mods-root [data-badge]').count(), 0, how + ': the refused file is listed');
      console.log(`${name}: ${how}: ${Math.round(bigSize / 1048576)} MB file refused in ${ms} ms: ${await page.locator('#message').textContent()}`);
      return ms;
    };
    // The browser's estimate says it does not fit: refused before the file is read.
    await page.evaluate(q => { navigator.storage.estimate = async () => ({quota: q, usage: 0}); }, lowQuota);
    assert.ok(await refusedBig('the estimate is too small') < 10000, 'an up-front refusal has to be immediate');
    await page.evaluate(() => { delete navigator.storage.estimate; });
    // The estimate leaves room but the browser refuses the write, as seen when Chromium's quota drops. A test cannot make a
    // browser run out on demand (Chromium's quota override does not bind in this context), so the write is made to fail
    // the way a full store fails it: the transaction aborts with QuotaExceededError.
    await page.evaluate(() => {
      const put = IDBObjectStore.prototype.put;
      IDBObjectStore.prototype.put = function (...args) { const r = put.apply(this, args); if (this.name === 'archives') queueMicrotask(() => this.transaction.abort()); return r; };
      Object.defineProperty(IDBTransaction.prototype, 'error', {configurable: true, get: () => new DOMException('refused', 'QuotaExceededError')});
    });
    // (The file is read before the write: Firefox took 36 s for 679 MB with other containers running. The old hang was 600 s.)
    assert.ok(await refusedBig('the browser refuses the write') < 90000, 'a refused write has to fail fast');
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    if (process.env.SOFTLOCK_FILL_QUOTA && name === 'Chromium') {
      // A real refusal, for a run by hand with a large file: other data fills the quota, the estimate is told to leave
      // room, and the browser itself refuses the write. The time it takes is printed, and the run stops there.
      const fillPage = page;
      fillPage.setDefaultTimeout(60000);
      await fillPage.goto(origin + '/?view=play'); await ready(fillPage);
      await fillPage.locator('#mods-panel > summary').click();
      const left = await fillPage.evaluate(async size => {
        const db = await new Promise((resolve, reject) => { const r = indexedDB.open('softlock-fill', 1); r.onupgradeneeded = () => r.result.createObjectStore('fill'); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
        const chunk = new Blob([crypto.getRandomValues(new Uint8Array(65536))]), big = new Blob(Array(1024).fill(chunk));
        for (let i = 0; ; i++) {
          const {quota, usage} = await navigator.storage.estimate();
          if (quota - usage < size * 0.8) { db.close(); return {quota, usage}; }
          const stored = await new Promise(resolve => { const t = db.transaction('fill', 'readwrite'); t.objectStore('fill').put(big, i); t.oncomplete = () => resolve(true); t.onerror = t.onabort = () => resolve(false); });
          if (!stored) { db.close(); return {quota, usage, refusedAt: i}; } // full before the estimate says so
        }
      }, bigSize);
      console.log(`${name}: filled the quota: ${JSON.stringify(left)}`);
      await fillPage.evaluate(q => { navigator.storage.estimate = async () => ({quota: q, usage: 0}); }, bigSize * 4);
      console.log(`${name}: a real refusal took ${await refusedBig('the browser itself refuses the write', fillPage)} ms`);
      pass('a real quota refusal: the mod is refused without a hang, with the reason, and nothing is listed');
      await context.close();
      continue;
    }
    pass('too little browser storage: the mod is refused without a hang, with the reason, and nothing is listed');

    await addFiles(hsFiles.map(f => f.path ?? f)); // a path: real files can pass Playwright's limit for buffers
    const added = await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length >= n || (document.getElementById('message').textContent || null), hsFiles.length, {timeout: 600000});
    assert.equal(await added.jsonValue(), true, 'adding the mods failed');
    await addFiles([{name: 'my-tweaks.zip', mimeType: 'application/zip', buffer: mine}]);
    await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length === n, hsFiles.length + 1, {timeout: 120000});
    await page.waitForFunction(() => /with mods/.test(document.getElementById('launch-label').textContent));
    const shas = [...hsFiles.map(f => sha(f.buffer)), sha(mine)], key = setHash(shas);

    // ---- Another save holds unlocks; a new save in a Hyperspace set takes none of them and asks nothing -------------
    const setAllMods = async on => {
      await page.locator('#play-button').click(); await page.evaluate(() => { document.getElementById('mods-panel').open = true; });
      await page.locator('#mods-panel [aria-label="Mod load order"] .mod-row').first().waitFor();
      for (const box of await page.getByRole('checkbox', {name: /^Enable /}).all()) await box[on ? 'check' : 'uncheck']();
      await page.waitForFunction(want => /with mods/.test(document.getElementById('launch-label').textContent) === want, on);
      await page.locator('#saves-button').click();
    };
    await setAllMods(false);
    const rich = await newSave(page, 'Rich vanilla');
    await giveProfile(page, rich, WITH_UNLOCKS);
    await setAllMods(true);
    const hsOne = await newSave(page, 'Mods one');
    const one = await deviceSave(page, hsOne);
    assert.equal(one.setup.key, key, 'the set is named by its hash');
    assert.deepEqual(one.setup.mods.map(m => m.sha256), shas, 'the save names each file by SHA-256, in order');
    assert.equal(one.profile ?? null, null, 'a new save holds no profile yet');
    await syncNow(page);
    assert.deepEqual((await cloud(context, hsOne)).setup.mods.map(m => m.sha256), shas, 'the account holds the same names');
    pass('a save names its mods by SHA-256 and the set by its hash, on the device and in the account');

    await play(page, hsOne, `${name}-new-save-menu.png`);
    const session = await page.evaluate(() => window.ftlModSession);
    assert.equal(session.hash, key); assert.equal(session.hyperspace, true, 'the set is a Hyperspace set');
    assert.equal(await page.locator('dialog[open]').count(), 0, 'nothing asks on the first start');
    assert.equal(await page.locator('#hs-inherit-dialog').count(), 0);
    assert.doesNotMatch(await page.locator('body').innerText(), /Transfer vanilla progress/);
    const saved = await page.evaluate(() => window.ftlSaveDurably());
    assert.equal(saved.saved, true);
    const profileNow = await page.evaluate(() => window.ftlEngine.profileSave()), stored = (await deviceSave(page, hsOne)).profile;
    assert.match(profileNow, /^(<\?xml[^>]*\?>\s*)?<xftl-profile/, 'the engine holds the new format');
    assert.deepEqual(unlocksOf(profileNow), [], 'the engine has the default unlocks only');
    assert.deepEqual(unlocksOf(stored), [], 'so has the save');
    await saveAndClose(page);
    pass('a new save in a Hyperspace set: no transfer question, only the default unlocks, though another save holds some');

    // ---- Each save keeps its own: unlocks in one do not reach the next new save of the set --------------------------
    await page.locator('#saves-button').click();
    await giveProfile(page, hsOne, WITH_UNLOCKS);
    const hsTwo = await newSave(page, 'Mods two');
    await play(page, hsTwo);
    assert.equal(await page.locator('dialog[open]').count(), 0);
    assert.deepEqual(unlocksOf(await page.evaluate(() => window.ftlEngine.profileSave())), [], 'the second save starts fresh too');
    await saveAndClose(page);
    assert.equal(unlocksOf((await deviceSave(page, hsOne)).profile).length, 3, 'the first keeps what it holds');
    pass('a second new save of that set starts fresh too; the first keeps its unlocks');

    // ---- Missing: Play refuses and names the file -----------------------------------------------------------------
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    await page.getByRole('button', {name: 'Remove My Tweaks'}).click();
    await page.locator('#mods-remove-dialog-yes').click();
    await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length === n, hsFiles.length);
    await savesPage(page);
    assert.ok((await row(page, hsOne).locator('.save-missing').allTextContents()).includes('missing mod: my-tweaks.zip, ' + sha(mine)), 'the row names the missing file');
    await refused(page, hsOne, 'my-tweaks.zip', sha(mine));
    pass('a missing mod file: the row names it (name and SHA-256), Play refuses and the game does not start');

    // ---- Different: the same name with other bytes is not the save's file ------------------------------------------
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    await addFiles([{name: 'my-tweaks.zip', mimeType: 'application/zip', buffer: other}]);
    await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length === n, hsFiles.length + 1);
    await savesPage(page);
    assert.ok((await row(page, hsOne).locator('.save-missing').allTextContents()).includes('missing mod: my-tweaks.zip, ' + sha(mine)), 'other bytes under the name do not count');
    await refused(page, hsOne, 'my-tweaks.zip', sha(mine));
    assert.equal((await deviceSave(page, hsOne)).setup.key, key, 'a refused Play changes nothing');
    pass('a different file under the same name does not count: the row and Play still name the save\'s file');

    // Enabling other files changes which saves Launch uses, never a save: this set has no save, and it asks, with no copy of a vanilla run.
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.waitForFunction(() => /with mods/.test(document.getElementById('launch-label').textContent));
    await page.locator('#launch').click();
    const asked = openDialog(page, 'no-save-dialog'); await asked.waitFor();
    assert.equal(await page.locator('#no-save-copy').count(), 0, 'a Hyperspace set offers no copy of a vanilla save');
    assert.doesNotMatch(await asked.innerText(), /Transfer/);
    await page.locator('#no-save-cancel').click(); await asked.waitFor({state: 'detached'});
    assert.equal(await engineRunning(page), false);
    await syncNow(page);
    assert.equal((await cloud(context, hsOne)).setup.key, key, 'other enabled mods leave the save locked to its own');
    pass('enabling other mods never changes a save: Launch asks for a save of those mods instead, with no transfer or copy');

    // ---- Advanced: mods is the way to change them, behind its warning ------------------------------------------------
    await savesPage(page);
    const open = async () => { await row(page, hsOne).locator('details.row-menu > summary').click(); await row(page, hsOne).locator('[data-action="mods"]').click(); await openDialog(page, 'save-mods-dialog').waitFor(); };
    await open();
    assert.match(await page.locator('#save-mods-dialog-warning').textContent(), /can break this run/);
    assert.match(await page.locator('#save-mods-dialog-warning').textContent(), /keeps its unlocks/);
    await page.locator('#save-mods-list').getByRole('button', {name: 'Remove my-tweaks.zip'}).click();
    await page.locator('#save-mods-cancel').click(); await openDialog(page, 'save-mods-dialog').waitFor({state: 'detached'});
    assert.equal((await deviceSave(page, hsOne)).setup.key, key, 'Cancel changes nothing');
    await open();
    await page.locator('#save-mods-list').getByRole('button', {name: 'Remove my-tweaks.zip'}).click();
    await page.locator('#save-mods-add').selectOption(sha(other)); await page.locator('#save-mods-add-button').click();
    await page.locator('#save-mods-apply').click(); await openDialog(page, 'save-mods-dialog').waitFor({state: 'detached'});
    const changedShas = [...hsFiles.map(f => sha(f.buffer)), sha(other)], changedKey = setHash(changedShas);
    const changed = await deviceSave(page, hsOne);
    assert.equal(changed.setup.key, changedKey, 'the save moves to the set it now names');
    assert.equal(unlocksOf(changed.profile).length, 3, 'and keeps its unlocks');
    await syncNow(page);
    assert.equal((await cloud(context, hsOne)).setup.key, changedKey, 'the account hears of it');
    await page.locator('#saves-list li.save-row').first().waitFor();
    await savesPage(page);
    assert.deepEqual(await row(page, hsOne).locator('.save-missing').allTextContents(), []);
    await play(page, hsOne);
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), changedKey);
    assert.equal(unlocksOf(await page.evaluate(() => window.ftlEngine.profileSave())).length, 3, 'the engine loaded the save\'s unlocks');
    await saveAndClose(page);
    pass('Saves > Advanced: mods warns; Cancel changes nothing; applying moves the save to the new hash with its unlocks, and it plays');
    assert.deepEqual(errors, []);
    await context.close();
  } catch (e) {
    if (out) await page.screenshot({path: join(out, name + '-failure.png')}).catch(() => {});
    console.log('message:', await page.locator('#message').textContent().catch(() => ''));
    throw e;
  } finally { await browser.close(); }
}
console.log(`${checks.length} saves-softlock checks passed.`);
