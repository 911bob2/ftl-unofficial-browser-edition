// A plain Slipstream data mod, without Hyperspace, played in the engine, in the website, Chromium and Firefox, against the
// disposable 8084 fixture. Our own archive changes a stock weapon's stats and adds a weapon and a ship (no game content in
// it); MODPLAY_FILES=<file,file> adds real mods in the same run (a mod that adds a choice to START_BEACON, as the examples
// shipped with Slipstream do, has to be in that box and pay). Covers: the set starts and is not a Hyperspace set; the
// new stats and blueprints reach the game; a run holding the mod's things plays, saves and loads; Saves > the save >
// Advanced: mods takes the mod away from that run for real: what the run held is dropped and listed, the run plays on, and
// the save is locked to the set it now names; a start ship naming things that do not exist shows in the hangar and starts.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {basename, join} from 'node:path';
import {createHash} from 'node:crypto';
import {chromium, firefox} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError} from './lib/launch.mjs';
import {zipSync} from '../web/vendor/fflate-0.8.3.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084';
assert.equal(origin, 'http://localhost:8084', 'Use the disposable 8084 fixture');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 120) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const selected = (process.env.MENU_BROWSERS ?? 'Chromium,Firefox').split(',');
const out = process.env.MODPLAY_OUT;
const sha = b => createHash('sha256').update(b).digest('hex');
const setHash = shas => sha(JSON.stringify({loader: 1, mods: shas}));
const enc = new TextEncoder();
const zip = entries => Buffer.from(zipSync(Object.fromEntries(Object.entries(entries).map(([k, v]) => [k, [enc.encode(v), {level: 6}]])), {mtime: new Date(Date.UTC(2026, 0, 1))}));
const meta = (title, version) => `<?xml version="1.0"?>\n<metadata><title>${title}</title><version>${version}</version></metadata>\n`;

const SHIP_TEXTS = '<destroyed><text>d</text></destroyed><deadCrew><text>d</text></deadCrew><gotaway><text>d</text></gotaway>';
const dataMod = zip({
  'mod-appendix/metadata.xml': meta('Play Test Fleet', '1.0'),
  'data/blueprints.xml.append': `
<mod:findName type="weaponBlueprint" name="LASER_BURST_1" panic="true">
  <mod-overwrite:damage>5</mod-overwrite:damage>
  <mod-overwrite:sp>5</mod-overwrite:sp>
  <mod-overwrite:cooldown>15</mod-overwrite:cooldown>
</mod:findName>
<weaponBlueprint name="MOD_PLAY_LASER">
  <type>LASER</type><title>Play laser</title><short>Play</short><tip>tip_laser</tip><desc>A test laser.</desc><tooltip>A test laser.</tooltip>
  <damage>2</damage><shots>1</shots><sp>0</sp><fireChance>1</fireChance><breachChance>0</breachChance><cooldown>4</cooldown>
  <power>1</power><cost>20</cost><bp>2</bp><rarity>0</rarity><image>laser_light1</image>
  <launchSounds><sound>lightLaser1</sound></launchSounds><hitShipSounds><sound>hitHull2</sound></hitShipSounds>
  <hitShieldSounds><sound>hitShield1</sound></hitShieldSounds><missSounds><sound>miss</sound></missSounds>
  <weaponArt>laser_burst_1</weaponArt><iconImage>laser</iconImage>
</weaponBlueprint>
<shipBlueprint name="MOD_PLAY_SHIP" layout="auto_assault" img="auto_assault">
  <class>Play ship</class>
  <systemList><pilot power="1" max="3" room="2"/><shields power="2" max="9" room="3"/><engines power="1" max="5" room="4"/><weapons power="3" max="6" room="1"/></systemList>
  <weaponList missiles="5" count="1"><weapon name="LASER_BURST_1"/></weaponList>
  <health amount="30"/><maxPower amount="20"/><crewCount amount="0" max="0" class="human"/><cloakImage>auto_assault</cloakImage>
</shipBlueprint>`,
  'data/events.xml.append': `<ship name="MOD_PLAY_ENEMY" auto_blueprint="MOD_PLAY_SHIP">${SHIP_TEXTS}</ship>`
});
const keptMod = zip({'mod-appendix/metadata.xml': meta('Play Test Texts', '1.0'), 'data/text_misc.xml.append': '<text name="mod_play_text">Kept</text>'});
// The start ship names things that do not exist: a weapon, a drone, an augment and a crew class.
const NOT_THERE = 'MOD_PLAY_NOT_THERE';
const badShipMod = zip({
  'mod-appendix/metadata.xml': meta('Play Test Bad Names', '1.0'),
  'data/blueprints.xml.append': `
<mod:findName type="shipBlueprint" name="PLAYER_SHIP_HARD" panic="true">
  <mod-overwrite:weaponList count="2" missiles="4"><weapon name="${NOT_THERE}"/><weapon name="LASER_BURST_1"/></mod-overwrite:weaponList>
  <mod-append:aug name="${NOT_THERE}"/>
  <mod-append:crewCount amount="1" class="${NOT_THERE}"/>
</mod:findName>`
});
const realFiles = process.env.MODPLAY_FILES ? await Promise.all(process.env.MODPLAY_FILES.split(',').map(async p => ({name: basename(p), buffer: await readFile(p), mimeType: 'application/zip'}))) : [];
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
  }, {user, password: 'mods-play-password-' + user, initial: initial.get(user)});
}
const snapshot = page => page.evaluate(() => JSON.parse(window.ftlEngine.debugSnapshot()));
const state = (page, s) => page.waitForFunction(s => JSON.parse(window.ftlEngine.debugSnapshot()).state === s, s, {timeout: 60000});
const deviceSave = (page, id) => page.evaluate(async id => {
  const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
  return deviceStore(me.id).get(id);
}, id);
const savesPage = async page => { await page.goto(origin + '/?view=saves'); await ready(page); await page.locator('#saves-list li.save-row').first().waitFor(); };
const row = (page, id) => page.locator(`#saves-list li.save-row[data-id="${id}"]`);
const openDialog = (page, id) => page.locator(`dialog#${id}[open]`);
const saveAndClose = async page => { await Promise.all([page.waitForEvent('load', {timeout: 60000}), page.locator('#save-close').click()]); await ready(page); };
const engineUp = async page => {
  await page.waitForFunction(() => {
    const failed = !document.getElementById('message').hidden && document.getElementById('message').textContent;
    return failed || !!window.ftlEngine && JSON.parse(window.ftlEngine.debugSnapshot()).state === 'MainMenuState';
  }, {}, {timeout: 300000});
  assert.equal(await page.evaluate(() => !!window.ftlEngine), true, await page.locator('#message').textContent());
  if ((await snapshot(page)).window === 'WelcomeChoiceBox') { await page.locator('#game').press('1'); await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).window !== 'WelcomeChoiceBox'); }
};
async function click(page, x, y) {
  const box = await page.locator('#game').boundingBox(), scale = Math.min(box.width / 1280, box.height / 720);
  await page.mouse.click(box.x + (box.width - 1280 * scale) / 2 + x * scale, box.y + (box.height - 720 * scale) / 2 + y * scale);
}
/** Close the box of the first beacon. */
async function closeBox(page) {
  await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).dialogue?.ready === true, {}, {timeout: 30000});
  for (let i = 0; i < 6 && (await snapshot(page)).dialogue; i++) { await page.locator('#game').press('1'); await page.waitForTimeout(500); }
}
/** The engine's own player: a run of [beacons] jumps, answering every box, saving and loading once per sector. One JSON line per box. */
const playRun = (page, beacons) => page.evaluate(n => window.hsFullRunProbe(`537\tPLAYER_SHIP_HARD\t1\nopt\tanygame\t1\nopt\treload\t1\nopt\tbeacons\t${n}`), beacons).then(o => o.trim().split('\n').map(l => JSON.parse(l)));

for (const [name, type, options, user] of [['Chromium', chromium, {channel: 'chromium'}, 'user03'], ['Firefox', firefox, {}, 'user07']].filter(([b]) => selected.includes(b))) {
  const browser = withOnboarding(await type.launch({headless: !(name === 'Firefox' && process.env.RUNNER_FIREFOX_WINDOWED === '1'), ...options}), origin);
  const pass = label => { checks.push({browser: name, label}); console.log(`PASS ${name}: ${label}`); };
  const errors = [], context = await browser.newContext({viewport: {width: 1280, height: 900}}), page = await context.newPage();
  page.setDefaultTimeout(60000);
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => { errors.push('native dialog: ' + d.message()); d.dismiss(); });
  page.on('console', e => { if (e.type() === 'error' && !expectedConsoleError(e)) console.log('browser console:', e.text()); });
  try {
    await signIn(page, user);
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    const files = [...realFiles, {name: 'play-test-fleet.ftl', mimeType: 'application/zip', buffer: dataMod}, {name: 'play-test-texts.ftl', mimeType: 'application/zip', buffer: keptMod}];
    await page.locator('#mods-root .file-drop input[type=file]').setInputFiles(files);
    await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length === n, files.length, {timeout: 120000});
    await page.waitForFunction(() => /with mods/.test(document.getElementById('launch-label').textContent));
    const shas = files.map(f => sha(f.buffer)), key = setHash(shas);

    // ---- The set starts, without Hyperspace ------------------------------------------------------------------------------
    await page.locator('#saves-button').click();
    await page.locator('#new-save').click(); await openDialog(page, 'new-save-dialog').waitFor();
    await page.locator('#new-save-name').fill('Modded run'); await page.locator('#new-save-create').click();
    const made = page.locator('#saves-list li.save-row', {hasText: 'Modded run'}); await made.waitFor();
    const id = await made.getAttribute('data-id');
    assert.equal((await deviceSave(page, id)).setup.key, key);
    await row(page, id).locator('[data-action="play"]').click(); await engineUp(page);
    const session = await page.evaluate(() => window.ftlModSession);
    assert.equal(session.hash, key); assert.equal(session.hyperspace, false, 'a data mod is not a Hyperspace set');
    pass('a plain Slipstream data mod set starts, and is not a Hyperspace set');

    // ---- It plays: a run through sectors, with what a real mod adds at their start -----------------------------------------
    const lines = await playRun(page, 45), result = lines.at(-1);
    assert.equal(result.k, 'result'); assert.doesNotMatch(result.result, /FAILED/, result.result);
    const beacons = lines.filter(l => l.k === 'beacon').length, sectors = lines.filter(l => l.k === 'sector').length, reloads = lines.filter(l => l.k === 'reloaded').length;
    assert.ok(beacons >= 20 && sectors >= 2 && reloads >= 1, `the run goes on: ${beacons} beacons, ${sectors} sectors, ${reloads} reloads (${result.result})`);
    pass(`played: ${beacons} beacons over ${sectors} sectors with a save and load per sector (${result.result})`);

    // ---- The mod's weapon and ship, the changed stats -------------------------------------------------------------------------
    await page.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await state(page, 'InGameState');
    await closeBox(page);
    if (realFiles.length) {
      // The sector start a real mod adds a choice to: the game's own box, and the choice pays.
      assert.match(await page.evaluate(() => window.hsRuntimeProbe('mn-show START_BEACON')), /^ok/);
      await page.waitForFunction(() => { const d = JSON.parse(window.ftlEngine.debugSnapshot()).dialogue; return d?.ready === true && d.choices.length >= 2; }, {}, {timeout: 30000});
      const before = await snapshot(page);
      assert.ok(before.dialogue.choices.length >= 2, `the real mod's choice is in the box (${before.dialogue.choices.length} choices)`);
      await page.locator('#game').press('2');
      await page.waitForTimeout(1000);
      if (out) await page.screenshot({path: join(out, name + '-real-mod-reward.png')});
      for (let i = 0; i < 6 && (await snapshot(page)).dialogue; i++) { await page.locator('#game').press('1'); await page.waitForTimeout(500); }
      assert.ok((await snapshot(page)).scrap > before.scrap, 'the reward is paid');
      pass('a real mod\'s choice is in the sector start box and pays its reward');
    }
    await page.evaluate(() => { window.ftlEngine.consoleExecute('dev-weapon MOD_PLAY_LASER'); window.ftlEngine.consoleExecute('spawn-ship MOD_PLAY_ENEMY 1.EASY'); });
    assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
    assert.match(await page.evaluate(() => window.ftlEngine.runSave()), /MOD_PLAY_SHIP/, 'the new ship is here');
    assert.match(await page.evaluate(() => window.ftlEngine.runSave()), /MOD_PLAY_LASER/, 'the new weapon is on board');
    const hull = (await snapshot(page)).hull;
    await page.waitForFunction(h => { const s = JSON.parse(window.ftlEngine.debugSnapshot()); if (s.paused) window.ftlEngine.pause?.(false); return s.hull <= h - 5; }, hull, {timeout: 180000});
    const lost = hull - (await snapshot(page)).hull;
    assert.equal(lost % 5, 0, `the changed weapon hits for 5 (lost ${lost})`);
    pass('the mod\'s weapon and ship are in the game, and the stock weapon it changed hits with the new stats');
    if (out) await page.screenshot({path: join(out, name + '-modded-fight.png')});
    await page.evaluate(() => window.ftlEngine.consoleExecute('fix'));

    // ---- It saves and loads with the mod's things in it ------------------------------------------------------------------
    const saved = await page.evaluate(() => window.ftlSaveDurably());
    assert.equal(saved.saved, true); assert.equal(saved.id, id);
    await saveAndClose(page);
    await savesPage(page);
    await row(page, id).locator('[data-action="play"]').click(); await engineUp(page);
    await click(page, 1180, 295); await state(page, 'InGameState');
    assert.equal(await page.locator('dialog#missing-content-dialog[open]').count(), 0, 'with the mods present nothing is dropped');
    assert.match(await page.evaluate(() => window.ftlEngine.runSave()), /MOD_PLAY_LASER/);
    await page.waitForTimeout(3000);
    assert.equal((await snapshot(page)).state, 'InGameState');
    await saveAndClose(page);
    pass('the run saves and loads with the mod\'s things in it and plays on');

    // ---- Advanced: mods takes the mod away from this run ------------------------------------------------------------------
    await savesPage(page);
    await row(page, id).locator('details.row-menu > summary').click(); await row(page, id).locator('[data-action="mods"]').click();
    await openDialog(page, 'save-mods-dialog').waitFor();
    assert.match(await page.locator('#save-mods-dialog-warning').textContent(), /can break this run/);
    await page.locator('#save-mods-list').getByRole('button', {name: 'Remove play-test-fleet.ftl'}).click();
    await page.locator('#save-mods-apply').click(); await openDialog(page, 'save-mods-dialog').waitFor({state: 'detached'});
    const newShas = files.filter(f => f.buffer !== dataMod).map(f => sha(f.buffer)), newKey = setHash(newShas);
    assert.equal((await deviceSave(page, id)).setup.key, newKey, 'the save moves to the set it now names');
    await savesPage(page);
    assert.deepEqual(await row(page, id).locator('.save-missing').allTextContents(), []);
    await row(page, id).locator('[data-action="play"]').click(); await engineUp(page);
    assert.equal(await page.evaluate(() => window.ftlModSession.hash), newKey);
    await click(page, 1180, 295); await state(page, 'InGameState');
    await openDialog(page, 'missing-content-dialog').waitFor();
    const dropped = await page.locator('#missing-content-list li').allTextContents();
    assert.ok(dropped.includes('MOD_PLAY_LASER'), 'the page lists what was dropped: ' + dropped.join(', '));
    await page.locator('#missing-content-dialog-ok').click();
    if (out) await page.screenshot({path: join(out, name + '-after-change.png')});
    const run = await page.evaluate(() => window.ftlEngine.runSave());
    assert.doesNotMatch(run, /MOD_PLAY_LASER/, 'the run no longer holds it');
    await page.waitForTimeout(5000);
    assert.equal((await snapshot(page)).state, 'InGameState', 'the run plays on');
    assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
    await saveAndClose(page);
    pass('Change mods applied: the save moves to the new set, what the run held of the removed mod is dropped and listed, and it plays and saves');

    // ---- The soft lock holds the new set, not the old one -----------------------------------------------------------------
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    await page.getByRole('button', {name: 'Remove Play Test Texts'}).click();
    await page.locator('#mods-remove-dialog-yes').click();
    await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length === n, files.length - 1);
    await savesPage(page);
    assert.ok((await row(page, id).locator('.save-missing').allTextContents()).includes('missing mod: play-test-texts.ftl, ' + sha(keptMod)), 'the row names the file of the new set');
    await row(page, id).locator('[data-action="play"]').click();
    await page.waitForFunction(s => document.getElementById('message').textContent.includes('missing mod: play-test-texts.ftl, ' + s), sha(keptMod));
    assert.equal(await page.evaluate(() => !!window.ftlEngine), false, 'the game does not start');
    pass('afterwards the soft lock holds the new set: its file missing, Play refuses and names it');

    // ---- A run on the ship of a mod that left: it says so, and with the ship back the run continues ------------------------
    await savesPage(page);
    await page.locator('#new-save').click(); await openDialog(page, 'new-save-dialog').waitFor();
    await page.locator('#new-save-name').fill('Mod ship'); await page.locator('#new-save-create').click();
    const shipRow = page.locator('#saves-list li.save-row', {hasText: 'Mod ship'}); await shipRow.waitFor();
    const shipId = await shipRow.getAttribute('data-id');
    await row(page, shipId).locator('[data-action="play"]').click(); await engineUp(page);
    await page.evaluate(() => window.ftlEngine.newRun('PLAYER_SHIP_HARD', 1)); await state(page, 'InGameState');
    await page.waitForTimeout(1000);
    assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
    await saveAndClose(page);
    const swapShip = (from, to) => page.evaluate(async ({id, from, to}) => {
      const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json();
      return !!await deviceStore(me.id).update(id, cur => ({...cur, run: cur.run.replace('shipId="' + from + '"', 'shipId="' + to + '"'), dirty: true}));
    }, {id: shipId, from, to});
    assert.equal(await swapShip('PLAYER_SHIP_HARD', NOT_THERE), true);
    await savesPage(page);
    await row(page, shipId).locator('[data-action="play"]').click(); await engineUp(page);
    await click(page, 1180, 295);
    await page.waitForFunction(n => (document.getElementById('connection-message')?.textContent ?? '').includes(n + ', came from a mod'), NOT_THERE, {timeout: 30000});
    await page.goto(origin + '/?view=saves'); await ready(page);
    assert.equal(await swapShip(NOT_THERE, 'PLAYER_SHIP_HARD'), true);
    await savesPage(page);
    await row(page, shipId).locator('[data-action="play"]').click(); await engineUp(page);
    await click(page, 1180, 295); await state(page, 'InGameState');
    assert.equal((await snapshot(page)).ship, 'PLAYER_SHIP_HARD');
    await saveAndClose(page);
    pass('a run on the ship of a mod that left says which ship it needs, and continues with the ship back');

    // ---- A start ship that names things that do not exist: the hangar shows it and the run starts ----------------------------
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    await page.locator('#mods-root .file-drop input[type=file]').setInputFiles([{name: 'play-test-bad.ftl', mimeType: 'application/zip', buffer: badShipMod}]);
    await page.waitForFunction(n => document.querySelectorAll('#mods-root [data-badge]').length === n, files.length);
    await page.locator('#saves-button').click();
    await page.locator('#new-save').click(); await openDialog(page, 'new-save-dialog').waitFor();
    await page.locator('#new-save-name').fill('Bad names'); await page.locator('#new-save-create').click();
    const bad = page.locator('#saves-list li.save-row', {hasText: 'Bad names'}); await bad.waitFor();
    await bad.locator('[data-action="play"]').click(); await engineUp(page);
    await click(page, 1180, 353); await state(page, 'SelectShipState');
    await page.waitForTimeout(2000);
    const hangar = await snapshot(page);
    assert.equal(hangar.ship, 'PLAYER_SHIP_HARD');
    assert.equal(hangar.state, 'SelectShipState', 'the hangar draws');
    if (out) await page.screenshot({path: join(out, name + '-bad-hangar.png')});
    await click(page, 1170, 50); await state(page, 'InGameState');
    await page.waitForTimeout(3000);
    assert.equal((await snapshot(page)).state, 'InGameState');
    assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
    pass('a start ship naming things that do not exist shows in the hangar, starts a run, plays and saves');
    assert.deepEqual(errors, []);
    await context.close();
  } catch (e) {
    if (out) await page.screenshot({path: join(out, name + '-failure.png')}).catch(() => {});
    console.log('message:', await page.locator('#message').textContent().catch(() => ''));
    throw e;
  } finally { await browser.close(); }
}
console.log(`${checks.length} mods-play checks passed.`);
