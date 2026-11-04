// Mod browser and saves with mods (packaged Menus group, Chrome and Firefox). The fixture's test catalogue offers our F1
// archives (tests/fixtures/mods/catalogue.mjs): f1-content with the Hyperspace rule (server only), f1-override with the
// Multiverse rule and a MEGA link served by a fake MEGA (Playwright routes, bytes encrypted as MEGA stores them).
// Covers: nothing downloads by itself; server and MEGA downloads in several ranges with progress;
// errors (blocked host, MEGA quota and removed file, bad checksum, interrupted + resume, storage full); local files with
// any name (another file is kept as a Custom mod); the preset dialog; role rules in the page and on the server; the
// launch progress; save model 2 (docs/SAVES.md): one list of saves with a mods badge, New save for any setup, "No save
// for ..." when launching with mods, modded saves that sync, Rename/Delete/snapshots in the page's own dialogs, fresh
// data across tabs, vanilla launch unchanged; toggle buttons keep their width; one Launch FTL button ("with mods" when
// any is enabled); two accounts in one browser (each removes a shared file from its own list; deleting it warns and
// detaches it from the other). No native prompt, confirm or alert may appear.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash, randomUUID} from 'node:crypto';
import {chromium, firefox} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {MEGA_STORAGE, megaAttributes, megaEncrypt, testCatalogue} from './fixtures/mods/catalogue.mjs';
import {modSetHash} from '../web/mods/session.mjs';
import {zipSync} from '../web/vendor/fflate-0.8.3.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084';
assert.ok(['http://localhost:8083', 'http://localhost:8084'].includes(origin), 'Use an isolated fixture');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 80) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const selected = (process.env.MENU_BROWSERS ?? 'Chromium,Firefox').split(',');
const sha = b => createHash('sha256').update(b).digest('hex');
const {archives: {content, override}, catalogue} = testCatalogue();
const cipher = megaEncrypt(override.bytes);
const setHash = await modSetHash([sha(content.bytes), sha(override.bytes)]);

for (const [name, type, options, user] of [['Chromium', chromium, {channel: 'chromium'}, 'user03'], ['Firefox', firefox, {}, 'user04']].filter(([b]) => selected.includes(b))) {
  const browser = withOnboarding(await type.launch({headless: !(name === 'Firefox' && process.env.RUNNER_FIREFOX_WINDOWED === '1'), ...options}), origin);
  const context = await browser.newContext({viewport: {width: 1280, height: 900}});
  await context.addInitScript(() => { globalThis.ftlModDownloadChunk = 256; });
  const page = await context.newPage(), errors = [];
  page.setDefaultTimeout(30000); page.on('pageerror', e => errors.push(e.message));
  const dialogs = [];
  page.on('dialog', d => { dialogs.push(d.message()); d.dismiss(); });
  const requests = [];
  page.on('request', r => { const u = new URL(r.url()); if (/mega\.co\.nz$/.test(u.host) || u.pathname.startsWith('/api/mods/files/') || u.pathname.startsWith('/web/mods/')) requests.push({host: u.host, path: u.pathname, method: r.method()}); });
  const pass = label => console.log(`PASS ${name}: ${label}`);
  // Fake MEGA: the API answers with the file's size, attributes and a storage URL; storage serves encrypted ranges.
  let apiAnswer = null, storageAnswer = null;
  const cors = {'Access-Control-Allow-Origin': '*'};
  await page.route('https://g.api.mega.co.nz/**', route => apiAnswer ? apiAnswer(route) : route.fulfill({status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify([{s: override.bytes.length, at: megaAttributes(override.name), g: MEGA_STORAGE}])}));
  await page.route(MEGA_STORAGE + '/**', route => { const m = /\/(\d+)-(\d+)$/.exec(route.request().url()); if (storageAnswer) return storageAnswer(route, Number(m[1]), Number(m[2])); return route.fulfill({status: 200, headers: cors, contentType: 'application/octet-stream', body: cipher.subarray(Number(m[1]), Number(m[2]) + 1)}); });
  try {
    await page.goto(origin); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
    const password = 'mods-browser-password-' + user;
    const signIn = () => page.evaluate(async ({user, password, initial}) => {
      let csrf = (await (await fetch('/api/auth/csrf')).json()).token;
      const post = (p, body) => fetch('/api/auth/' + p, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify(body)});
      let r = await post('login', {username: user, password}); if (r.status === 401) r = await post('login', {username: user, password: initial}); if (!r.ok) throw Error('login ' + r.status);
      csrf = (await (await fetch('/api/auth/csrf')).json()).token;
      if ((await (await fetch('/api/auth/me')).json()).mustChangePassword) { r = await post('password', {currentPassword: initial, newPassword: password}); if (!r.ok) throw Error('rotation'); csrf = (await (await fetch('/api/auth/csrf')).json()).token; await post('login', {username: user, password}); }
    }, {user, password, initial: initial.get(user)});
    await signIn();
    const play = async () => { await page.goto(origin + '/?view=play'); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true'); };
    const openMods = async () => { await play(); await page.locator('#mods-panel > summary').click(); await page.locator('#mods-catalogue .catalogue-row').nth(1).waitFor(); };
    const row = id => page.locator(`#mods-catalogue [data-id="${id}"]`);
    const state = id => row(id).getAttribute('data-state');
    const waitState = (id, ...states) => page.waitForFunction(({id, states}) => states.includes(document.querySelector(`#mods-catalogue [data-id="${id}"]`)?.dataset.state), {id, states}, {timeout: 60000});
    const log = () => page.evaluate(() => globalThis.ftlModDownloadLog ?? []);

    // 1. The catalogue: nothing is requested before a Download button is pressed.
    await openMods();
    assert.deepEqual(await page.locator('#mods-catalogue .catalogue-row').evaluateAll(rows => rows.map(r => [r.dataset.id, r.dataset.state])), [['f1-content', 'missing'], ['f1-override', 'missing']]);
    assert.equal(await page.locator('#mods-presets [data-preset="f1"]').count(), 1);
    await page.waitForTimeout(1000);
    assert.deepEqual(requests.filter(r => r.path.startsWith('/api/mods/files/') || /mega/.test(r.host)), [], 'something downloaded without a click');
    assert.equal(await page.locator('#mods-source-select').count(), 0, 'each file has one download source');
    assert.ok((await row('f1-content').innerText()).includes('GPL-3.0-or-later'));
    pass('catalogue listed with presets and licence link; nothing downloaded without a click');

    // 2. From this server, in 256-byte ranges, with progress events.
    await page.getByRole('button', {name: 'Download F1 Content 1'}).click();
    await waitState('f1-content', 'cached');
    assert.match(await row('f1-content').innerText(), /from this server/);
    const serverRanges = requests.filter(r => r.path === '/api/mods/files/f1-content').length;
    assert.equal(serverRanges, Math.ceil(content.bytes.length / 256));
    const phases = (await log()).filter(e => e.id === 'f1-content').map(e => e.phase);
    for (const p of ['connect', 'download', 'verify', 'store']) assert.ok(phases.includes(p), p);
    const last = (await log()).filter(e => e.id === 'f1-content' && e.phase === 'download').pop();
    assert.equal(last.done, content.bytes.length); assert.equal(last.total, content.bytes.length);
    pass(`downloaded from this server in ${serverRanges} ranges with progress (connect, download, verify, store)`);

    // 3. From MEGA, decrypted per range.
    await page.getByRole('button', {name: 'Download F1 Override 1'}).click();
    await waitState('f1-override', 'cached');
    assert.match(await row('f1-override').innerText(), /from mega\.nz/);
    assert.equal(requests.filter(r => r.host === 'g.api.mega.co.nz').length, 1);
    assert.equal(requests.filter(r => r.host === new URL(MEGA_STORAGE).host).length, Math.ceil(override.bytes.length / 256));
    const stored = await page.evaluate(async h => { const {getArchive} = await import('/web/mods/store.mjs'); const a = await getArchive(h); return [...new Uint8Array(await a.file.arrayBuffer())]; }, sha(override.bytes));
    assert.equal(sha(Buffer.from(stored)), sha(override.bytes));
    pass('downloaded from (fake) MEGA, decrypted per range, stored bytes equal the pinned file');

    // 4. Errors, each with its next steps.
    const deleteFile = async (id, title) => { await page.getByRole('button', {name: `Delete ${title} 1 from this browser`}).click(); await page.locator('#mods-delete-dialog-yes').click(); await waitState(id, 'missing'); };
    const expectError = async (pattern, steps) => {
      await waitState('f1-override', 'error');
      const text = await row('f1-override').innerText();
      assert.match(text, pattern, text);
      for (const s of steps) assert.ok(await row('f1-override').getByRole('button', {name: s}).count() + await row('f1-override').getByRole('link', {name: s}).count() > 0, s + ' in ' + text);
    };
    await deleteFile('f1-override', 'F1 Override');
    apiAnswer = route => route.abort('blockedbyclient');
    await page.getByRole('button', {name: 'Download F1 Override 1'}).click();
    await expectError(/Could not connect to mega\.nz\..*(firewall|offline)/s, ['Retry', 'Choose a local file', 'Official page']);
    apiAnswer = route => route.fulfill({status: 200, headers: cors, contentType: 'application/json', body: '[-17]'});
    await row('f1-override').getByRole('button', {name: 'Retry'}).click();
    await expectError(/transfer quota/, ['Retry']);
    apiAnswer = route => route.fulfill({status: 200, headers: cors, contentType: 'application/json', body: '[-9]'});
    await row('f1-override').getByRole('button', {name: 'Retry'}).click();
    await expectError(/no longer on MEGA/, ['Choose a local file']);
    apiAnswer = null;
    const tampered = Buffer.from(override.bytes); tampered[100] ^= 1; const tamperedCipher = megaEncrypt(tampered);
    storageAnswer = (route, a, b) => route.fulfill({status: 200, headers: cors, body: tamperedCipher.subarray(a, b + 1)});
    await row('f1-override').getByRole('button', {name: 'Retry'}).click();
    await expectError(/does not match the pinned checksum/, ['Retry']);
    assert.equal(await page.evaluate(async h => (await (await import('/web/mods/store.mjs')).getDownload(h)) ?? null, sha(override.bytes)), null, 'a bad download is discarded');
    pass('blocked host, MEGA quota, file removed and checksum mismatch each explain the problem and offer next steps');

    // Interrupted at the third range, then resumed from there.
    storageAnswer = (route, a, b) => a >= 512 ? route.abort('connectionreset') : route.fulfill({status: 200, headers: cors, body: cipher.subarray(a, b + 1)});
    await row('f1-override').getByRole('button', {name: 'Retry'}).click();
    await expectError(/stopped at 0\.0 MB of 0\.0 MB/, ['Resume', 'Restart']);
    assert.equal((await page.evaluate(async h => (await (await import('/web/mods/store.mjs')).getDownload(h)), sha(override.bytes))).done, 512);
    storageAnswer = null; const before = requests.length;
    await row('f1-override').getByRole('button', {name: 'Resume'}).click();
    await waitState('f1-override', 'cached');
    const resumed = requests.slice(before).filter(r => r.host === new URL(MEGA_STORAGE).host);
    assert.equal(resumed.length, Math.ceil((override.bytes.length - 512) / 256), 'resume fetched only the missing ranges');
    pass('an interrupted download keeps its finished ranges and resumes after them');

    // Storage full.
    await deleteFile('f1-override', 'F1 Override');
    await page.evaluate(() => { const real = navigator.storage.estimate.bind(navigator.storage); window.__estimate = real; navigator.storage.estimate = async () => ({quota: 1000, usage: 990}); });
    await page.getByRole('button', {name: 'Download F1 Override 1'}).click();
    await expectError(/Not enough browser storage/, ['Manage cached files', 'Choose a local file']);
    await page.evaluate(() => { navigator.storage.estimate = window.__estimate; });
    pass('a full browser storage is reported with the space needed');

    // 5. A local file: another file than the catalogue's is kept as a Custom mod of its own; the right one is accepted
    // (Verified) whatever its name.
    const choose = async (title, file) => { const [chooser] = await Promise.all([page.waitForEvent('filechooser'), row('f1-override').getByRole('button', {name: `Choose a local file for ${title}`, exact: true}).click()]); await chooser.setFiles(file); };
    const mine = Buffer.from(zipSync({'mod-appendix/metadata.xml': [new TextEncoder().encode('<?xml version="1.0"?>\n<metadata><title>Not It</title><version>2</version></metadata>\n'), {level: 6}], 'data/text_misc.xml.append': [new TextEncoder().encode('<text name="not_it">x</text>'), {level: 6}]}, {mtime: new Date(Date.UTC(2026, 0, 1))}));
    await choose('F1 Override 1', {name: 'not-it.zip', mimeType: 'application/zip', buffer: mine});
    await expectError(/not-it\.zip is not F1 Override 1: .*It was added to your mods as a Custom file\./s, ['Choose a local file']);
    const customRow = page.locator('#mods-panel ol[aria-label="Mod load order"] .mod-row', {has: page.locator('[data-badge="custom"]')});
    await customRow.waitFor();
    await customRow.getByRole('button', {name: /^Remove /}).click(); await page.locator('#mods-remove-dialog-yes').click(); await customRow.waitFor({state: 'detached'});
    await choose('F1 Override 1', {name: 'renamed-copy.bin', mimeType: 'application/octet-stream', buffer: Buffer.from(override.bytes)});
    await waitState('f1-override', 'cached');
    assert.match(await row('f1-override').innerText(), /from a local file/);
    pass('local files: another file is kept as a Custom mod (and can be removed); the pinned file is accepted under any name');

    // 6. Preset dialog: which files, how much, which source (MEGA named), then enabled in order.
    await deleteFile('f1-content', 'F1 Content'); await deleteFile('f1-override', 'F1 Override');
    const presetButton = page.getByRole('button', {name: 'Enable preset F1 pair'});
    const width = l => l.evaluate(b => [b.textContent, Math.round(b.getBoundingClientRect().width * 10) / 10]);
    const [offLabel, offWidth] = await width(presetButton);
    await page.getByRole('button', {name: 'Enable preset F1 pair'}).click();
    await page.locator('#mods-preset-dialog[open]').waitFor();
    const dialogText = await page.locator('#mods-preset-dialog').innerText();
    assert.match(dialogText, /2 of 2 files to download/); assert.match(dialogText, /F1 Content 1 · 0\.0 MB · from this server/);
    assert.match(dialogText, /F1 Override 1 · 0\.0 MB · from mega\.nz/); assert.match(dialogText, /connects your browser to mega\.nz/);
    for (const id of ['mods-preset-download', 'mods-preset-choose', 'mods-preset-cancel']) assert.equal(await page.locator('#' + id).count(), 1);
    await page.locator('#mods-preset-cancel').click();
    assert.equal(await state('f1-content'), 'missing', 'Cancel downloads nothing');
    await page.getByRole('button', {name: 'Enable preset F1 pair'}).click();
    await page.locator('#mods-preset-download').click();
    await page.waitForFunction(() => !document.getElementById('mods-preset-dialog').open, {}, {timeout: 60000});
    const order = () => page.evaluate(() => [...document.querySelectorAll('#mods-panel ol[aria-label="Mod load order"] .mod-row')].map(r => [r.querySelector('strong').textContent, r.dataset.enabled]));
    assert.deepEqual(await order(), [['F1 Content', 'true'], ['F1 Override', 'true']]);
    assert.equal(await page.locator('#mods-set').textContent(), 'Id ' + setHash.slice(0, 12));
    pass('the preset dialog lists the missing files, their size and source (MEGA named); Download enables the preset in order');
    const [onLabel, onWidth] = await width(presetButton);
    assert.deepEqual([offLabel, onLabel], ['Enable', 'Enabled']); assert.equal(onWidth, offWidth, 'Enable and Enabled have the same width');
    assert.equal(await page.locator('#mods-launch').count(), 0, 'no second launch button');
    assert.equal(await page.locator('#launch-label').textContent(), 'Launch FTL with mods');
    const launchWidth = await page.locator('#launch').evaluate(b => b.getBoundingClientRect().width);
    const setMods = async on => { for (const n of ['F1 Content', 'F1 Override']) await page.getByRole('checkbox', {name: 'Enable ' + n})[on ? 'check' : 'uncheck'](); await page.waitForFunction(t => document.getElementById('launch-label').textContent === t, on ? 'Launch FTL with mods' : 'Launch FTL'); };
    await setMods(false);
    assert.equal(await page.locator('#launch').evaluate(b => b.getBoundingClientRect().width), launchWidth, 'the Launch button keeps its width');
    assert.equal((await width(presetButton))[1], offWidth);
    pass(`toggle buttons keep their width (preset ${offWidth}px both ways, Launch unchanged); one Launch button reads "Launch FTL with mods" only while mods are enabled`);

    // 7. Role rules in the page and on the server: an External user gets f1-content from the server, f1-override only from MEGA.
    const outsider = await browser.newContext({viewport: {width: 1280, height: 900}}), ext = await outsider.newPage();
    await ext.goto(origin); await ext.waitForFunction(() => document.body.dataset.sessionReady === 'true');
    const extName = 'modsui' + randomUUID().slice(0, 8);
    await ext.evaluate(async ({u, p}) => { const csrf = (await (await fetch('/api/auth/csrf')).json()).token; const r = await fetch('/api/auth/register', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify({username: u, password: p})}); if (!r.ok) throw Error('register ' + r.status); }, {u: extName, p: 'mods-browser-external-' + randomUUID()});
    await ext.goto(origin + '/?view=play'); await ext.waitForFunction(() => document.body.dataset.sessionReady === 'true');
    assert.deepEqual(await ext.evaluate(async () => [(await fetch('/api/mods/files/f1-override')).status, (await fetch('/api/mods/files/f1-content')).status]), [403, 200]);
    await ext.locator('#mods-panel > summary').click(); await ext.locator('#mods-catalogue [data-id="f1-override"]').waitFor();
    assert.equal(await ext.locator('#mods-source-select').count(), 0);
    assert.equal(await ext.getByRole('button', {name: 'Download F1 Override 1'}).textContent(), 'Download from mega.nz');
    assert.equal(await ext.getByRole('button', {name: 'Download F1 Content 1'}).textContent(), 'Download');
    await outsider.close();
    pass('External user: Hyperspace-rule file from this server, Multiverse-rule file only from MEGA (the server refuses it: 403)');

    // 8. Launch progress and a vanilla run (nothing to ask: Launch FTL plays the current save, the first is made at once),
    // then launching with mods that have no save yet: it asks, and creates nothing until Create save (or Use a save without mods).
    await play();
    await page.evaluate(() => { window.__details = []; new MutationObserver(() => window.__details.push(document.getElementById('loading-detail').textContent)).observe(document.getElementById('loading-detail'), {childList: true, characterData: true, subtree: true}); });
    await page.locator('#launch').click();
    await page.waitForFunction(() => !!window.ftlEngine || !!document.querySelector('dialog[open]'), {}, {timeout: 180000});
    assert.equal(await page.evaluate(() => !!document.querySelector('dialog[open]')), false, 'a vanilla launch asks nothing');
    const details = await page.evaluate(() => window.__details);
    assert.ok(details.some(t => /MB of \d+ MB/.test(t)), 'game file download progress: ' + details.slice(0, 5).join(' | '));
    await page.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await page.waitForTimeout(1000);
    const first = await page.evaluate(() => window.ftlSaveDurably());
    assert.deepEqual([first.backend, first.synced], ['device', true]);
    const cloudSave = id => page.evaluate(id => fetch('/api/saves/' + id).then(r => r.json()), id);
    const vanilla = await cloudSave(first.id); assert.ok(vanilla.run); assert.equal(vanilla.name, 'Save 1'); assert.equal(vanilla.setup.key, 'vanilla');
    pass(`vanilla launch showed game-file progress (${details.filter(t => /MB of/.test(t)).length} updates) and saved a run in "Save 1", synced`);

    const deviceSaves = h => page.evaluate(async h => { const {deviceStore} = await import('/web/device-store.mjs'); return (await deviceStore(document.body.dataset.account).list()).filter(s => !h || s.setup.key === h); }, h);
    const hasModSave = async h => (await deviceSaves(h)).length > 0;
    // The game started, or what stopped it: the page message, a save row's note or a question left open.
    const engineUp = async () => {
      const why = () => page.evaluate(() => [document.getElementById('message').hidden ? '' : document.getElementById('message').textContent, ...[...document.querySelectorAll('.slot-note')].map(n => n.textContent), ...[...document.querySelectorAll('dialog[open]')].map(d => 'dialog: ' + d.innerText)].filter(Boolean).join(' | ')).catch(e => e.message);
      await page.waitForFunction(() => !!window.ftlEngine || !document.getElementById('message').hidden && document.getElementById('message').textContent || [...document.querySelectorAll('.slot-note')].some(n => n.textContent), {}, {timeout: 180000})
        .catch(async e => { await page.screenshot({path: '.build/mods-browser-no-game-' + name + '.png'}).catch(() => {}); throw Error('no game after 180 s (' + await why() + ')', {cause: e}); });
      assert.equal(await page.evaluate(() => !!window.ftlEngine), true, await why());
    };

    // Mods enabled, no save: the dialog; Cancel creates nothing; Use a save without mods (mods without Hyperspace) copies the run.
    await openMods(); await setMods(true);
    await page.locator('#launch').click();
    await page.locator('#no-save-dialog[open]').waitFor({timeout: 60000});
    assert.equal(await page.locator('#no-save-title').textContent(), 'No save for F1 pair');
    assert.equal(await page.locator('#no-save-dialog p').first().textContent(), 'Create a save for these mods?');
    assert.deepEqual(await page.locator('#no-save-dialog button').allTextContents(), ['Create save', 'Use a save without mods', 'Cancel']);
    assert.equal(await page.locator('#no-save-warning').textContent(), 'It may not work with these mods.');
    await page.locator('#no-save-dialog').screenshot({path: '.build/mods-browser-no-save-' + name + '.png'}).catch(() => {});
    assert.equal(await hasModSave(setHash), false, 'the dialog creates nothing');
    await page.locator('#no-save-cancel').click(); await page.waitForFunction(() => !document.getElementById('no-save-dialog'));
    assert.equal(await hasModSave(setHash), false, 'Cancel creates nothing');
    assert.equal(await page.evaluate(() => !!window.ftlEngine), false); assert.equal(await page.locator('#launch').isEnabled(), true);
    // The vanilla save as this browser holds it now (leaving the game page saves it once more).
    const deviceRun = id => page.evaluate(async id => (await (await import('/web/device-store.mjs')).deviceStore(document.body.dataset.account).get(id))?.run ?? null, id);
    const vanillaRun = await deviceRun(first.id);
    assert.ok(vanillaRun);
    await page.locator('#launch').click();
    await page.locator('#no-save-copy').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), setHash);
    const [copy] = await deviceSaves(setHash);
    // The copy's first version (uploaded before the game started; the modded engine has saved over it since).
    assert.equal(copy.name, 'Copy of Save 1');
    assert.equal((await page.evaluate(id => fetch('/api/saves/' + id + '/snapshots/1').then(r => r.json()), copy.id)).run, vanillaRun, 'the copy of the vanilla run is the mods\' first save');
    const moddedSaved = await page.evaluate(() => window.ftlSaveDurably());
    assert.deepEqual([moddedSaved.backend, moddedSaved.id, moddedSaved.synced], ['device', copy.id, true]);
    assert.equal((await cloudSave(copy.id)).setup.key, setHash, 'the modded save syncs with its mod setup');
    let vanillaCloud;
    for (let i = 0; i < 40 && (vanillaCloud = await cloudSave(first.id)).run !== vanillaRun; i++) await page.waitForTimeout(500);
    assert.equal(vanillaCloud.run, vanillaRun, 'the vanilla save is untouched');
    assert.equal(vanillaCloud.setup.key, 'vanilla');
    pass('launch with mods and no save: "No save for F1 pair" (nothing created by the dialog or by Cancel); "Use a save without mods" copied the vanilla run into the mods\' first save, which syncs; the original is untouched');

    // Mods off: Launch FTL plays the current save without mods, asks nothing and loads no mod code.
    await openMods(); await setMods(false);
    requests.length = 0;
    await play();
    assert.equal(await page.locator('#launch-label').textContent(), 'Launch FTL');
    await page.locator('#launch').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession ?? null), null);
    assert.equal(await page.locator('dialog[open]').count(), 0);
    assert.deepEqual(requests.filter(r => r.path.startsWith('/web/mods/')), [], 'a vanilla launch loads no mod modules');
    pass('vanilla launch with a modded save present: no question, no mod session, no mod module requested');

    // 9. One list of all saves: Play, New save with each setup, Rename, Delete, the Current badge, snapshots.
    const saves = async () => { await page.goto(origin + '/?view=saves'); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true'); await page.locator('#saves-list .save-row').first().waitFor(); };
    const listed = () => page.locator('#saves-list .save-row').evaluateAll(rs => rs.map(r => ({id: r.dataset.id, setup: r.dataset.setup, current: r.dataset.current === 'true', name: r.querySelector('strong').textContent, badges: [...r.querySelectorAll('.save-title .badge')].map(b => b.textContent), buttons: [...r.querySelectorAll('.actions > button')].map(b => b.textContent), line: r.querySelector('.save-line').textContent})));
    const rowOf = id => page.locator(`#saves-list .save-row[data-id="${id}"]`);
    const menu = async (id, action) => { const r = rowOf(id); await r.locator('summary').click(); await r.locator(`[data-action="${action}"]`).click(); };
    await saves();
    let rows = await listed();
    assert.deepEqual(rows.map(r => [r.setup === 'vanilla' ? 'vanilla' : 'mods', r.name, r.badges.join('/'), r.buttons.join('/')]), [['vanilla', 'Save 1', 'No mods/Current', 'Play'], ['mods', 'Copy of Save 1', 'F1 pair', 'Play']]);
    assert.match(rows[0].line, /Kestrel|PLAYER_SHIP_HARD/); assert.equal(rows[1].setup, setHash);
    await page.locator('#saves-list').screenshot({path: '.build/mods-browser-saves-' + name + '.png'}).catch(() => {});
    pass('Saves: one list, each save with its setup badge ("No mods", "F1 pair"), Current on the save Play uses, Play and a ⋯ menu');

    const openNew = async () => { await page.locator('#new-save').click(); await page.locator('#new-save-dialog[open]').waitFor(); };
    const shownMods = () => page.locator('#new-save-mods li').allTextContents();
    // New save without mods: prefilled with the next free name, becomes the current save without mods, the first keeps its run.
    const firstRun = await deviceRun(first.id); assert.ok(firstRun);
    await openNew();
    assert.deepEqual(await shownMods(), ['No mods: the game as it ships.']);
    assert.equal(await page.locator('#new-save-mods input:not([disabled])').count(), 0);
    assert.equal(await page.locator('#new-save-name').inputValue(), 'Save 2');
    await page.locator('#new-save-dialog').screenshot({path: '.build/mods-browser-new-save-' + name + '.png'}).catch(() => {});
    await page.locator('#new-save-create').click(); await page.waitForFunction(() => !document.getElementById('new-save-dialog'));
    await page.waitForFunction(() => [...document.querySelectorAll('#saves-list .save-row')].find(r => r.querySelector('strong').textContent === 'Save 2')?.dataset.current === 'true');
    const save2 = (await listed()).find(r => r.name === 'Save 2');
    assert.equal(save2.buttons[0], 'Start'); assert.equal(await deviceRun(first.id), firstRun, 'the first run is kept');
    // New save with a preset that already has a save.
    await openMods(); await setMods(true);
    await page.waitForFunction(() => /F1 pair/.test(document.getElementById('launch-save-text').textContent));
    await saves(); await openNew();
    assert.equal(await page.locator('#new-save-mods li').count(), 2, 'the enabled mods are listed');
    assert.equal(await page.locator('#new-save-mods input:not([disabled])').count(), 0, 'and cannot be changed here');
    assert.equal(await page.locator('#new-save-name').inputValue(), 'Save 3');
    await page.locator('#new-save-create').click(); await page.waitForFunction(() => !document.getElementById('new-save-dialog'));
    await page.waitForFunction(() => [...document.querySelectorAll('#saves-list .save-row strong')].some(s => s.textContent === 'Save 3'));
    assert.equal((await deviceSaves(setHash)).length, 2);
    await openMods(); await setMods(false);
    pass('New save: "No mods" and a preset with saves each add an empty save (the next free name); the other saves keep their runs');
    // New save with the enabled mods when they are not a preset: the first save of that mod setup, nothing downloaded.
    const hash1 = await modSetHash([sha(content.bytes)]);
    await openMods(); await page.getByRole('checkbox', {name: 'Enable F1 Content'}).check(); await page.getByRole('checkbox', {name: 'Enable F1 Override'}).uncheck();
    await page.waitForFunction(() => document.getElementById('launch-save-text').textContent === 'Save: none · F1 Content 1');
    const downloads = requests.filter(r => r.path.startsWith('/api/mods/files/') || /mega/.test(r.host)).length;
    await saves(); await openNew();
    assert.equal(await page.locator('#new-save-mods li').count(), 1);
    assert.match((await shownMods())[0], /F1 Content/);
    await page.locator('#new-save-name').fill('Content only'); await page.locator('#new-save-create').click(); await page.waitForFunction(() => !document.getElementById('new-save-dialog'));
    await page.waitForFunction(h => document.querySelector(`#saves-list .save-row[data-setup="${h}"]`)?.dataset.current === 'true', hash1);
    assert.equal(await hasModSave(hash1), true);
    assert.equal(requests.filter(r => r.path.startsWith('/api/mods/files/') || /mega/.test(r.host)).length, downloads, 'creating a save downloads nothing');
    const contentOnly = (await listed()).find(r => r.name === 'Content only');
    assert.deepEqual(contentOnly.badges.slice(0, 2), ['F1 Content 1', 'Current']);
    pass('New save with the enabled mods (not a preset): the first save of that mod setup, current at once, nothing downloaded');
    await openMods(); await page.getByRole('checkbox', {name: 'Enable F1 Content'}).uncheck();
    await page.waitForFunction(() => document.getElementById('launch-label').textContent === 'Launch FTL');

    // Rename and Delete in the ⋯ menu, in the page's own dialogs.
    await saves();
    await menu(save2.id, 'rename');
    await page.locator('#save-rename-dialog-input').fill('Second voyage'); await page.locator('#save-rename-dialog-ok').click();
    await page.waitForFunction(id => document.querySelector(`#saves-list .save-row[data-id="${id}"] strong`)?.textContent === 'Second voyage', save2.id);
    await menu(contentOnly.id, 'delete');
    assert.match(await page.locator('#save-delete-dialog').innerText(), /deleted in this browser and in your account|deleted from this browser/);
    await page.locator('#save-delete-dialog-yes').click();
    await rowOf(contentOnly.id).waitFor({state: 'detached'});
    assert.equal(await hasModSave(hash1), false);
    pass('Rename and Delete in the ⋯ menu ask in the page\'s own dialogs');

    // Recovery snapshots of a save: Restore makes an older version the newest one.
    await menu(first.id, 'snapshots');
    await page.locator('#save-snapshots-dialog .snapshot-row').first().waitFor();
    const snapshotRows = await page.locator('#save-snapshots-dialog .snapshot-row').evaluateAll(rs => rs.map(r => [r.dataset.revision, [...r.querySelectorAll('button')].map(b => b.textContent).join('/')]));
    assert.ok(snapshotRows.length >= 2, JSON.stringify(snapshotRows));
    assert.equal(snapshotRows[0][1], 'Download', 'the current version offers no Restore');
    const oldest = snapshotRows[snapshotRows.length - 1][0], revisionBefore = (await cloudSave(first.id)).revision;
    await page.locator(`#save-snapshots-dialog .snapshot-row[data-revision="${oldest}"] button`, {hasText: 'Restore'}).click();
    await page.locator('#save-restore-dialog-yes').click();
    await page.waitForFunction(() => /^Restored /.test(document.getElementById('message').textContent));
    const restored = await cloudSave(first.id);
    assert.equal(restored.revision, revisionBefore + 1); assert.equal(restored.run, (await page.evaluate(({id, r}) => fetch(`/api/saves/${id}/snapshots/${r}`).then(x => x.json()), {id: first.id, r: oldest})).run);
    pass('Recovery snapshots: one line each, the current one without Restore; Restore makes the chosen version the newest');

    // Play on a modded row: the enabled mods become that setup and its save launches.
    await saves();
    await rowOf(copy.id).locator('[data-action="play"]').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), setHash);
    assert.deepEqual(await page.evaluate(a => JSON.parse(localStorage.getItem('ftl-mods-enabled:' + a)), await page.evaluate(() => document.body.dataset.account)), [sha(content.bytes), sha(override.bytes)], 'the enabled mods are now that setup');
    pass('Play on a modded row: the enabled mods become that save\'s mods, its run loads in the mod set, no extra confirmation');

    // Play tab: "Save: <name> · <mods>" with Change (the saves of the enabled mods only); Launch FTL with mods uses it without asking.
    await play();
    await page.waitForFunction(() => /^Save: Copy of Save 1 · F1 pair$/.test(document.getElementById('launch-save-text').textContent));
    assert.equal(await page.locator('#launch-save-change').textContent(), 'Change');
    await page.locator('#launch-save-change').click(); await page.locator('#save-manager[open]').waitFor();
    await page.locator('#save-manager .save-row').first().waitFor();
    assert.deepEqual((await page.locator('#save-manager .save-row').evaluateAll(rs => rs.map(r => r.dataset.setup === 'vanilla' ? 'vanilla' : 'mods'))), ['mods', 'mods'], 'only the enabled setup');
    await page.locator('#save-manager').screenshot({path: '.build/mods-browser-change-' + name + '.png'}).catch(() => {});
    await page.locator('#save-manager-all').click(); await page.waitForFunction(() => document.body.dataset.panel === 'saves-panel');
    await play();
    await page.locator('#launch').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), setHash);
    assert.equal(await page.locator('#no-save-dialog').count(), 0, 'mods with a save launch without a question');
    pass('Play tab: the Save line names the save and mods, Change lists the enabled setup\'s saves, "Show all saves" opens Saves; Launch FTL with mods plays it without asking');

    // Fresh data across tabs: a save created and one renamed in another tab show up without a reload.
    await saves();
    await page.evaluate(() => { window.__sameDocument = true; });
    const second = await context.newPage(); second.on('dialog', d => { dialogs.push(d.message()); d.dismiss(); });
    await second.goto(origin + '/?view=saves'); await second.waitForFunction(() => document.body.dataset.sessionReady === 'true'); await second.locator('#saves-list .save-row').first().waitFor();
    await second.locator('#new-save').click(); await second.locator('#new-save-dialog[open]').waitFor();
    await second.locator('#new-save-name').fill('From tab two'); await second.locator('#new-save-create').click(); await second.waitForFunction(() => !document.getElementById('new-save-dialog'));
    await page.bringToFront();
    await page.waitForFunction(() => /From tab two/.test(document.getElementById('saves-list').innerText), {}, {timeout: 15000});
    assert.equal(await page.evaluate(() => window.__sameDocument), true, 'the page did not reload');
    await page.locator('#play-button').click(); await page.waitForFunction(() => document.body.dataset.panel === 'launch-panel');
    await second.bringToFront();
    const rename = second.locator('#saves-list .save-row', {hasText: 'From tab two'}); await rename.locator('summary').click(); await rename.locator('[data-action="rename"]').click();
    await second.locator('#save-rename-dialog-input').fill('Renamed in tab two'); await second.locator('#save-rename-dialog-ok').click();
    await page.bringToFront();
    await page.waitForFunction(() => /^Save: Renamed in tab two/.test(document.getElementById('launch-save-text').textContent), {}, {timeout: 15000});
    await second.close();
    assert.equal(await page.evaluate(() => window.__sameDocument), true);
    pass('fresh data: a save created in another tab and a rename there show in this tab (Saves list, Play line) without a reload');

    // Play on a vanilla row while mods are enabled: the mods turn off and the vanilla save launches.
    await saves();
    await rowOf(save2.id).locator('[data-action="play"]').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession ?? null), null);
    assert.deepEqual(await page.evaluate(a => JSON.parse(localStorage.getItem('ftl-mods-enabled:' + a)), await page.evaluate(() => document.body.dataset.account)), [], 'the mods are off');
    await play(); await page.waitForFunction(() => /^Save: Second voyage · No mods$/.test(document.getElementById('launch-save-text').textContent));
    pass('Play on a save without mods: the enabled mods turn off, that save becomes the current one and launches (no mod session)');

    // 10. Two accounts in one browser: the files are shared, the lists are per account.
    const accountA = await page.evaluate(() => document.body.dataset.account);
    await play(); await page.locator('#mods-panel > summary').click(); await page.locator('#mods-catalogue .catalogue-row').nth(1).waitFor();
    await setMods(true);
    await page.evaluate(async () => { const csrf = (await (await fetch('/api/auth/csrf')).json()).token; await fetch('/api/auth/logout', {method: 'POST', headers: {'X-CSRF-TOKEN': csrf}}); });
    await page.goto(origin); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
    const nameB = 'modsb' + randomUUID().slice(0, 8);
    await page.evaluate(async ({u, p}) => { const csrf = (await (await fetch('/api/auth/csrf')).json()).token; const r = await fetch('/api/auth/register', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify({username: u, password: p})}); if (!r.ok) throw Error('register ' + r.status); }, {u: nameB, p: 'mods-browser-b-' + randomUUID()});
    await openMods();
    const accountB = await page.evaluate(() => document.body.dataset.account);
    assert.notEqual(accountB, accountA);
    assert.deepEqual([await state('f1-content'), await state('f1-override')], ['cached', 'cached'], 'B sees the files A downloaded');
    assert.equal(await page.locator('#mods-panel [aria-label="Mod load order"] .mod-row').count(), 0, 'B has its own (empty) list');
    assert.equal(await page.locator('#launch-label').textContent(), 'Launch FTL');
    // B: New save with the preset, whose files B has not enabled: the first save of that mod setup, nothing downloaded.
    const fetched = requests.filter(r => r.path.startsWith('/api/mods/files/') || /mega/.test(r.host)).length;
    await openMods(); await page.getByRole('button', {name: 'Enable preset F1 pair'}).click();
    await page.waitForFunction(() => document.querySelectorAll('#mods-panel [aria-label="Mod load order"] .mod-row').length === 2);
    await page.goto(origin + '/?view=saves'); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
    await page.locator('#new-save').click(); await page.locator('#new-save-dialog[open]').waitFor();
    assert.equal(await page.locator('#new-save-name').inputValue(), 'Save 1');
    await page.locator('#new-save-create').click(); await page.waitForFunction(() => !document.getElementById('new-save-dialog'));
    await page.waitForFunction(h => document.querySelector(`#saves-list .save-row[data-setup="${h}"]`), setHash);
    assert.equal(await hasModSave(setHash), true); assert.equal(requests.filter(r => r.path.startsWith('/api/mods/files/') || /mega/.test(r.host)).length, fetched);
    await openMods(); await setMods(false);
    await openMods();
    const lists = () => page.evaluate(async ids => { const {getList} = await import('/web/mods/store.mjs'); return Promise.all(ids.map(async id => (await getList(id)).map(e => e.sha256))); }, [accountA, accountB]);
    await page.getByRole('button', {name: 'Enable preset F1 pair'}).click();
    await page.waitForFunction(() => document.querySelectorAll('#mods-panel [aria-label="Mod load order"] .mod-row').length === 2);
    await page.getByRole('button', {name: 'Remove F1 Content'}).click();
    await page.locator('#mods-remove-dialog[open]').waitFor();
    assert.match(await page.locator('#mods-remove-dialog').innerText(), /Another account in this browser also uses this file, so it stays/);
    await page.locator('#mods-remove-dialog-yes').click();
    await page.waitForFunction(() => document.querySelectorAll('#mods-panel [aria-label="Mod load order"] .mod-row').length === 1);
    assert.equal(await state('f1-content'), 'cached', 'removing from B\'s list keeps the file A uses');
    assert.deepEqual(await lists(), [[sha(content.bytes), sha(override.bytes)], [sha(override.bytes)]]);
    await page.getByRole('button', {name: 'Delete F1 Override 1 from this browser'}).click();
    await page.locator('#mods-delete-dialog[open]').waitFor();
    assert.match(await page.locator('#mods-delete-dialog').innerText(), /Another account in this browser also has it in its mods \(one account\); it is removed from their mods too/);
    await page.locator('#mods-delete-dialog-yes').click();
    await waitState('f1-override', 'missing');
    assert.deepEqual(await lists(), [[sha(content.bytes)], []]);
    assert.equal(await page.evaluate(h => import('/web/mods/store.mjs').then(m => m.getArchive(h)), sha(override.bytes)), undefined, 'the file is gone');
    assert.deepEqual(await page.evaluate(a => JSON.parse(localStorage.getItem('ftl-mods-enabled:' + a)), accountA), [sha(content.bytes)], 'A\'s Launch button follows');
    await page.evaluate(async () => { const csrf = (await (await fetch('/api/auth/csrf')).json()).token; await fetch('/api/auth/logout', {method: 'POST', headers: {'X-CSRF-TOKEN': csrf}}); });
    await page.goto(origin); await page.waitForFunction(() => document.body.dataset.sessionReady === 'true'); await signIn();
    await openMods();
    assert.deepEqual(await page.locator('#mods-panel [aria-label="Mod load order"] .mod-row strong').allTextContents(), ['F1 Content']);
    assert.equal(await page.locator('#launch-label').textContent(), 'Launch FTL with mods');
    pass('two accounts in one browser: B removes a shared file from its own list (kept for A), B deletes it with a warning and it leaves A\'s list too');

    // 11. "Hyperspace mod without Hyperspace" (web/mods/hs-needed.mjs): a mod that patches hyperspace.xml, enabled without
    // the catalogue's Hyperspace file (F1 Content stands in for it: mod "hyperspace"). One warning per start; Run anyway
    // carries on; Enable Hyperspace asks before downloading (sign-in, unreachable catalogue and a failed download each say
    // what to do), puts it first in the picker and carries on; an existing save changes its mods only in Advanced.
    const shots = (process.env.MODS_SHOTS ?? '.build') + '/hs-warning-' + name + '-'; // MODS_SHOTS: a mounted folder to keep them
    const usesHs = Buffer.from(zipSync({'mod-appendix/metadata.xml': [new TextEncoder().encode('<?xml version="1.0"?>\n<metadata><title>Uses HS</title><version>1</version></metadata>\n'), {level: 6}],
      'data/hyperspace.xml.append': [new TextEncoder().encode('<hullBars><maxHull>20</maxHull></hullBars>'), {level: 6}]}, {mtime: new Date(Date.UTC(2026, 0, 2))}));
    await openMods();
    await deleteFile('f1-content', 'F1 Content');
    await page.locator('#mods-panel input[aria-label="Choose mod archives"]').setInputFiles({name: 'uses-hs.zip', mimeType: 'application/zip', buffer: usesHs});
    await page.locator('#mods-panel [data-hint="hyperspace-missing"]').waitFor();
    assert.equal(await page.locator('#mods-panel [data-hint="hyperspace-missing"]').textContent(), 'Uses HS uses Hyperspace, but Hyperspace.ftl is not enabled: some Hyperspace images and text will be missing. Enable Hyperspace.ftl above it.');
    await page.locator('#mods-panel').screenshot({path: shots + '1-hint.png'}).catch(() => {});
    const hsShas = [sha(content.bytes), sha(usesHs)], hsSet = await modSetHash(hsShas), usesSet = await modSetHash([sha(usesHs)]);
    const warned = () => page.locator('#hs-missing-dialog[open]');
    let warnings = 0;
    // Every warning shown, across page loads.
    await page.exposeFunction('__hsWarned', () => { warnings++; });
    const watch = () => { const go = () => new MutationObserver(ms => { for (const m of ms) for (const n of m.addedNodes) if (n.id === 'hs-missing-dialog') window.__hsWarned(); }).observe(document.body, {childList: true}); if (document.body) go(); else document.addEventListener('DOMContentLoaded', go); };
    await context.addInitScript(watch); await page.evaluate(watch);
    // Cancel: nothing starts.
    await page.locator('#launch').click(); await warned().waitFor();
    assert.equal(await page.locator('#hs-missing-dialog-title').textContent(), 'Hyperspace mod without Hyperspace');
    assert.match(await warned().innerText(), /Uses HS 1 needs Hyperspace\.ftl, Hyperspace's own data, loaded first\. Without it some Hyperspace images and text are missing\./);
    assert.deepEqual(await warned().locator('button').allTextContents(), ['Cancel', 'Run anyway', 'Enable Hyperspace']);
    await warned().screenshot({path: shots + '2-warning.png'}).catch(() => {});
    await page.locator('#hs-missing-cancel').click(); await warned().waitFor({state: 'detached'});
    assert.equal(await page.locator('dialog[open]').count(), 0); assert.equal(await hasModSave(usesSet), false);
    // Run anyway: the start goes on (to "No save for ...", once), with the set as it is.
    await page.locator('#launch').click(); await page.locator('#hs-missing-run').click();
    await page.locator('#no-save-dialog[open]').waitFor();
    assert.equal(warnings, 2, 'one warning per start');
    await page.locator('#no-save-cancel').click(); await page.waitForFunction(() => !document.getElementById('no-save-dialog'));
    pass('a mod using Hyperspace without Hyperspace.ftl: a quiet hint in the picker, the warning once per start (named mod, Enable Hyperspace / Run anyway / Cancel); Cancel starts nothing, Run anyway carries on');

    // Enable Hyperspace while signed out of the catalogue, then with the catalogue unreachable: a next step each, nothing downloads.
    const fetchedBefore = requests.filter(r => r.path.startsWith('/api/mods/files/')).length;
    const signedOut = route => route.fetch().then(async r => { const c = await r.json(); for (const f of c.files) f.server = {available: false, reason: 'sign-in'}; return route.fulfill({response: r, json: c}); });
    await page.route('**/api/mods/catalogue', signedOut); await play();
    await page.locator('#launch').click(); await page.locator('#hs-missing-enable').click();
    await page.locator('#hs-get-dialog[open]').waitFor();
    assert.equal(await page.locator('#hs-get-dialog-title').textContent(), 'Sign in to download Hyperspace');
    assert.match(await page.locator('#hs-get-dialog').innerText(), /F1 Content 1 \(f1-content\.ftl, 0\.0 MB\) is not in this browser\. Signed in, you can download it from this server\./);
    assert.equal(await page.locator('#hs-get-dialog a').getAttribute('href'), 'https://example.invalid/f1-content.ftl');
    assert.deepEqual(await page.locator('#hs-get-dialog button').allTextContents(), ['Cancel', 'Sign in']);
    await page.locator('#hs-get-dialog').screenshot({path: shots + '3-signin.png'}).catch(() => {});
    await page.locator('#hs-get-cancel').click();
    await page.unroute('**/api/mods/catalogue', signedOut);
    const unreachable = route => route.abort('internetdisconnected');
    await page.route('**/api/mods/catalogue', unreachable); await play();
    await page.locator('#launch').click(); await page.locator('#hs-missing-enable').click();
    await page.locator('#hs-get-dialog[open]').waitFor();
    assert.equal(await page.locator('#hs-get-dialog-title').textContent(), 'Hyperspace is not in this browser');
    assert.match(await page.locator('#hs-get-dialog').innerText(), /cannot be reached right now/);
    await page.locator('#hs-get-dialog').screenshot({path: shots + '4-offline.png'}).catch(() => {});
    await page.unroute('**/api/mods/catalogue', unreachable);
    await page.locator('#hs-get-retry').click();
    await page.waitForFunction(() => document.getElementById('hs-get-dialog-title')?.textContent === 'Download Hyperspace?');
    const asked = await page.locator('#hs-get-dialog').innerText();
    assert.match(asked, /F1 Content 1 \(f1-content\.ftl, 0\.0 MB\) is not in this browser\. Download it from this server\?/); assert.match(asked, /Source: https:\/\/example\.invalid\/f1-content\.ftl/);
    assert.deepEqual(await page.locator('#hs-get-dialog button').allTextContents(), ['Cancel', 'Download 0.0 MB']);
    await page.locator('#hs-get-dialog').screenshot({path: shots + '5-download.png'}).catch(() => {});
    await page.locator('#hs-get-cancel').click();
    assert.equal(requests.filter(r => r.path.startsWith('/api/mods/files/')).length, fetchedBefore, 'nothing downloaded without Download');
    pass('Enable Hyperspace without the file: signed out it offers Sign in and the official page; with the catalogue unreachable Try again; then it asks before downloading (size and source); Cancel downloads nothing');

    // The download fails once (Try again), then Hyperspace is first in the picker and the start goes on with it.
    const failing = route => route.abort('connectionreset');
    await page.route('**/api/mods/files/f1-content', failing);
    await page.locator('#launch').click(); await page.locator('#hs-missing-enable').click();
    await page.locator('#hs-get-download').click();
    await page.waitForFunction(() => document.getElementById('hs-get-dialog-title')?.textContent === 'Hyperspace did not download', {}, {timeout: 60000});
    assert.deepEqual(await page.locator('#hs-get-dialog button').allTextContents(), ['Cancel', 'Try again']);
    await page.locator('#hs-get-dialog').screenshot({path: shots + '6-failed.png'}).catch(() => {});
    await page.unroute('**/api/mods/files/f1-content', failing);
    await page.locator('#hs-get-retry').click();
    await page.locator('#no-save-dialog[open]').waitFor({timeout: 60000});
    assert.deepEqual(await page.locator('#mods-panel ol[aria-label="Mod load order"] .mod-row').evaluateAll(rs => rs.filter(r => r.dataset.enabled === 'true').map(r => r.querySelector('strong').textContent)), ['F1 Content', 'Uses HS']);
    assert.equal(await page.locator('#mods-panel .mod-row[data-highlight="true"] strong').textContent(), 'F1 Content');
    assert.equal(await page.locator('#mods-panel [data-hint="hyperspace-missing"]').count(), 0);
    await page.screenshot({path: shots + '7-picker.png'}).catch(() => {});
    await page.locator('#no-save-create').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), hsSet);
    assert.equal(warnings, 5, 'still one warning per start');
    pass('Enable Hyperspace: a failed download offers Try again; then F1 Content is enabled first and shown in the picker, and the start goes on with it ("No save for", Create save, the game)');

    // An existing save of the set without Hyperspace: Enable Hyperspace leads to its Advanced mods, never changing them by itself.
    await openMods(); await page.getByRole('checkbox', {name: 'Enable F1 Content'}).uncheck();
    await page.waitForFunction(() => document.getElementById('launch-save-text').textContent === 'Save: none · uses-hs.zip');
    await page.locator('#launch').click(); await page.locator('#hs-missing-run').click();
    await page.locator('#no-save-create').click();
    await engineUp();
    const [plainSave] = await deviceSaves(usesSet);
    assert.ok(plainSave, 'Run anyway made the save of the set as it is');
    await saves();
    await rowOf(plainSave.id).locator('[data-action="play"]').click();
    await warned().waitFor();
    assert.match(await warned().innerText(), new RegExp(plainSave.name + '[\\s\\S]*Saves > Advanced: mods', 'i')); // the save's name is the eyebrow (upper case)
    await page.locator('#hs-missing-enable').click();
    await page.locator('#hs-save-dialog[open]').waitFor();
    assert.deepEqual(await page.locator('#hs-save-dialog button').allTextContents(), ['Cancel', 'New save with Hyperspace', 'Change this save\'s mods']);
    await page.locator('#hs-save-dialog').screenshot({path: shots + '8-existing.png'}).catch(() => {});
    await page.locator('#hs-save-advanced').click();
    await page.locator('#save-mods-dialog[open]').waitFor();
    assert.deepEqual(await page.locator('#save-mods-list .mod-row strong').allTextContents(), ['F1 Content', 'uses-hs.zip']);
    assert.equal(await page.locator('#save-mods-added').textContent(), 'F1 Content is added first. Change the mods to keep it.');
    assert.equal((await deviceSaves(usesSet)).length, 1, 'nothing changed before the player confirms');
    await page.locator('#save-mods-dialog').screenshot({path: shots + '9-advanced.png'}).catch(() => {});
    await page.locator('#save-mods-apply').click();
    await engineUp();
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), hsSet);
    assert.equal((await page.evaluate(async id => (await (await import('/web/device-store.mjs')).deviceStore(document.body.dataset.account).get(id)).setup.key, plainSave.id)), hsSet);
    pass('an existing save without Hyperspace: Enable Hyperspace offers its Advanced mods (Hyperspace added first, applied only on confirm) or a new save; the save then starts with Hyperspace');

    // New save: the warning at Create; Enable Hyperspace shows the change in the dialog, Create goes on.
    await openMods(); await page.getByRole('checkbox', {name: 'Enable F1 Content'}).uncheck();
    await page.waitForFunction(() => document.getElementById('launch-save-text').textContent === 'Save: none · uses-hs.zip');
    await saves(); await openNew();
    await page.locator('#new-save-name').fill('With HS'); await page.locator('#new-save-create').click();
    await warned().waitFor(); await page.locator('#hs-missing-enable').click();
    await page.waitForFunction(() => /Hyperspace\.ftl is enabled now/.test(document.getElementById('new-save-note')?.textContent));
    assert.deepEqual((await shownMods()).map(t => t.trim()), ['F1 Content 1', 'uses-hs.zip']);
    await page.locator('#new-save-dialog').screenshot({path: shots + '10-new-save.png'}).catch(() => {});
    await page.locator('#new-save-create').click(); await page.waitForFunction(() => !document.getElementById('new-save-dialog'));
    assert.ok((await deviceSaves(hsSet)).some(s => s.name === 'With HS'));
    pass('New save: the warning at Create; Enable Hyperspace puts it first (shown in the dialog) and Create makes the save with it');
    assert.deepEqual(dialogs, [], 'no native prompt, confirm or alert');
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
}
