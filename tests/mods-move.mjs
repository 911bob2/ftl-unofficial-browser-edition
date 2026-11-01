// A run moved between Hyperspace, Multiverse and no mods through Saves > the save > Advanced: mods, on real files, against
// the disposable 8084 fixture. MODMOVE_HS=<Hyperspace.ftl> MODMOVE_MV=<assets,data> (no mod content in Git; without them
// this skips). Each move loads the run in the new set, plays it and saves it, and the mod-set lock follows.
import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {onboardingCookies} from './lib/onboarding.mjs';
import {expectedConsoleError} from './lib/launch.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084';
assert.equal(origin, 'http://localhost:8084', 'Use the disposable 8084 fixture');
if (!process.env.MODMOVE_HS) { console.log('SKIP mods-move: MODMOVE_HS is not set'); process.exit(0); }
const hsFile = process.env.MODMOVE_HS, mvFiles = (process.env.MODMOVE_MV ?? '').split(',').filter(Boolean);
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 120) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const out = process.env.MODPLAY_OUT;
const pass = label => console.log('PASS Chromium: ' + label);
const ready = page => page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
const snapshot = page => page.evaluate(() => JSON.parse(window.ftlEngine.debugSnapshot()));
const state = (page, s) => page.waitForFunction(s => JSON.parse(window.ftlEngine.debugSnapshot()).state === s, s, {timeout: 120000});
const deviceSave = (page, id) => page.evaluate(async id => {
  const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
  return deviceStore(me.id).get(id);
}, id);
const row = (page, id) => page.locator(`#saves-list li.save-row[data-id="${id}"]`);
const openDialog = (page, id) => page.locator(`dialog#${id}[open]`);
const savesPage = async page => { await page.goto(origin + '/?view=saves'); await ready(page); await page.locator('#saves-list li.save-row').first().waitFor(); };
const saveAndClose = async page => { await Promise.all([page.waitForEvent('load', {timeout: 60000}), page.locator('#save-close').click()]); await ready(page); };
async function click(page, x, y) {
  const box = await page.locator('#game').boundingBox(), scale = Math.min(box.width / 1280, box.height / 720);
  await page.mouse.click(box.x + (box.width - 1280 * scale) / 2 + x * scale, box.y + (box.height - 720 * scale) / 2 + y * scale);
}
const engineUp = async page => {
  await page.waitForFunction(() => {
    const failed = !document.getElementById('message').hidden && document.getElementById('message').textContent;
    return failed || !!window.ftlEngine && JSON.parse(window.ftlEngine.debugSnapshot()).state === 'MainMenuState';
  }, {}, {timeout: 600000});
  assert.equal(await page.evaluate(() => !!window.ftlEngine), true, await page.locator('#message').textContent());
  if ((await snapshot(page)).window === 'WelcomeChoiceBox') { await page.locator('#game').press('1'); await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).window !== 'WelcomeChoiceBox'); }
};
/** Saves > the save > Advanced: mods: removes the rows named, adds the library files named, applies. */
async function change(page, id, remove, add, wanted) {
  await savesPage(page);
  await row(page, id).locator('details.row-menu > summary').click(); await row(page, id).locator('[data-action="mods"]').click();
  await openDialog(page, 'save-mods-dialog').waitFor();
  for (const n of remove) await page.locator('#save-mods-list').getByRole('button', {name: new RegExp('^Remove .*' + n)}).click();
  for (const label of add) {
    const value = await page.locator('#save-mods-add option', {hasText: label}).first().getAttribute('value');
    await page.locator('#save-mods-add').selectOption(value); await page.locator('#save-mods-add-button').click();
  }
  await page.locator('#save-mods-apply').click(); await openDialog(page, 'save-mods-dialog').waitFor({state: 'detached'});
  const setup = (await deviceSave(page, id)).setup;
  console.log('moved to', setup.key.slice(0, 12), setup.mods.map(m => m.name).join(', '));
  assert.equal(setup.mods.length, wanted);
  return setup;
}
/** Continue the run in its new set: loads, lists what it lost, plays, saves. Returns what was dropped. */
async function continueRun(page, id, what, setup) {
  await savesPage(page);
  assert.deepEqual(await row(page, id).locator('.save-missing').allTextContents(), []);
  await row(page, id).locator('[data-action="play"]').click(); await engineUp(page);
  assert.equal((await page.evaluate(() => window.ftlModSession?.hash)) ?? 'vanilla', setup.key, what + ': the session is the set of the save');
  await click(page, 1180, 295); await state(page, 'InGameState');
  await page.waitForTimeout(1500);
  let dropped = [];
  if (await page.locator('dialog#missing-content-dialog[open]').count()) {
    dropped = await page.locator('#missing-content-list li').allTextContents();
    await page.locator('#missing-content-dialog-ok').click();
  }
  await page.waitForTimeout(6000);
  assert.equal((await snapshot(page)).state, 'InGameState', what + ': the run plays on');
  if (out) await page.screenshot({path: join(out, 'move-' + what.replace(/\W+/g, '-') + '.png')});
  assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true, what + ': it saves');
  await saveAndClose(page);
  assert.equal((await deviceSave(page, id)).setup.key, setup.key, what + ': the save stays in the set');
  pass(`${what}: loads, plays and saves${dropped.length ? ' (dropped ' + dropped.length + ': ' + dropped.slice(0, 8).join(', ') + ')' : ''}`);
  return dropped;
}

const profile = await mkdtemp(join(tmpdir(), 'modmove-'));
const context = await chromium.launchPersistentContext(profile, {headless: true, viewport: {width: 1280, height: 900}, channel: 'chromium', args: ['--disable-dev-shm-usage']});
await context.addCookies(onboardingCookies(origin));
const page = context.pages()[0] ?? await context.newPage(), errors = [];
page.setDefaultTimeout(120000);
page.on('pageerror', e => errors.push(e.message));
page.on('console', e => { if (e.type() === 'error' && !expectedConsoleError(e)) console.log('browser console:', e.text()); });
try {
  await page.goto(origin); await ready(page);
  await page.evaluate(async ({user, password, initial}) => {
    let csrf = (await (await fetch('/api/auth/csrf')).json()).token;
    const post = (p, body) => fetch('/api/auth/' + p, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify(body)});
    let r = await post('login', {username: user, password}); if (r.status === 401) r = await post('login', {username: user, password: initial}); if (!r.ok) throw Error('login ' + r.status);
    csrf = (await (await fetch('/api/auth/csrf')).json()).token;
    if ((await (await fetch('/api/auth/me')).json()).mustChangePassword) { r = await post('password', {currentPassword: initial, newPassword: password}); if (!r.ok) throw Error('rotation'); csrf = (await (await fetch('/api/auth/csrf')).json()).token; await post('login', {username: user, password}); }
  }, {user: 'user04', password: 'mods-move-password-user04', initial: initial.get('user04')});
  await page.goto(origin + '/?view=play'); await ready(page);
  await page.locator('#mods-panel > summary').click();
  const all = [hsFile, ...mvFiles];
  await page.locator('#mods-root .file-drop input[type=file]').setInputFiles(all);
  await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length >= n, all.length, {timeout: 600000});

  // A run without mods.
  await page.locator('#saves-button').click();
  await page.locator('#new-save').click(); await openDialog(page, 'new-save-dialog').waitFor();
  await page.locator('#new-save-mods').selectOption('vanilla');
  await page.locator('#new-save-name').fill('Move me'); await page.locator('#new-save-create').click();
  const made = page.locator('#saves-list li.save-row', {hasText: 'Move me'}); await made.waitFor();
  const id = await made.getAttribute('data-id');
  await row(page, id).locator('[data-action="play"]').click(); await engineUp(page);
  await page.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await state(page, 'InGameState');
  await page.waitForTimeout(1000);
  await page.evaluate(() => window.ftlEngine.consoleExecute('rich'));
  assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
  await saveAndClose(page);

  // No mods -> Hyperspace -> no mods.
  await continueRun(page, id, 'no mods to Hyperspace', await change(page, id, [], ['Hyperspace'], 1));
  await continueRun(page, id, 'Hyperspace to no mods', await change(page, id, ['Hyperspace'], [], 0));

  if (mvFiles.length) {
    // No mods -> Multiverse; a new run of Multiverse -> Hyperspace alone -> no mods.
    await continueRun(page, id, 'no mods to Multiverse', await change(page, id, [], ['Hyperspace', 'Assets', 'Data'], 3));
    await savesPage(page);
    await row(page, id).locator('[data-action="play"]').click(); await engineUp(page);
    await page.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await state(page, 'InGameState');
    await page.waitForTimeout(3000);
    assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
    await saveAndClose(page);
    await continueRun(page, id, 'Multiverse to Hyperspace alone', await change(page, id, ['Assets', 'Data'], [], 1));
    await continueRun(page, id, 'Multiverse run to no mods', await change(page, id, ['Hyperspace'], [], 0));
  }
  assert.deepEqual(errors, []);
  console.log('mods-move checks passed.');
} catch (e) {
  if (out) await page.screenshot({path: join(out, 'move-failure.png')}).catch(() => {});
  console.log('message:', await page.locator('#message').textContent().catch(() => ''));
  throw e;
} finally { await context.close(); await rm(profile, {recursive: true, force: true}); }
