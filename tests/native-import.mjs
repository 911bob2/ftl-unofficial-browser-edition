// Saves from the original game (docs/SAVES.md, "Saves from the original game"): Saves > Import from FTL with real files
// the original wrote, then the conversion. NATIVE_DIR holds the files and NATIVE_EXPECT what each holds, read with an
// independent reader (docs/TESTS.md); both stay in ignored .build/. Without them only the synthetic checks run.
// The session decides which files: no mods the original's, Hyperspace (F2) hs_*, Multiverse (F3) hs_mv_*.
//   [FTL_HS_MOD_SESSION=... NODE_OPTIONS=--import=<preload>] NATIVE_DIR=/native NATIVE_EXPECT=/native/expect.json node /app/tests/native-import.mjs
// Checks: the guide, refusal of garbage, the found files, the setup the files ask for, the new save (wrapped, setup,
// summary, missing mods for a modded one); without mods the save is played through Continue and saved in the engine's
// format; for every file of the session the conversion (nativeImportProbe) gives the ship, crew, systems, sector, scrap,
// unlocks the original saved, and so does the run saved from it and loaded again.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError, launchGame} from './lib/launch.mjs';

const port = 8084, origin = 'http://localhost:' + port;
const data = join(process.cwd(), '.build/fixture-native-import'); await mkdir(data, {recursive: true});
const out = process.env.NATIVE_OUT ?? data;
const env = {...process.env, ASPNETCORE_URLS: 'http://127.0.0.1:' + port, ASPNETCORE_ENVIRONMENT: 'Development', FTL_DATA_DIR: data, FTL_ASSETS_DIR: '/assets', PUBLIC_ORIGIN: origin,
  TRUSTED_PROXIES: '127.0.0.1', FTL_TEST_RUNNER_ENABLED: 'false', FTL_REGISTER_LIMIT: '200', FTL_LOGIN_LIMIT: '500', FTL_PUBLIC_LIMIT: '5000'};
const code = await new Promise(resolve => { const p = spawn('dotnet', ['/app/Ftl.Server.dll', 'create-accounts', 'user01:user'], {cwd: '/app', stdio: 'ignore', env}); p.on('error', () => resolve(1)); p.on('exit', resolve); });
assert.equal(code, 0, 'fixture accounts');
const server = spawn('dotnet', ['/app/Ftl.Server.dll'], {cwd: '/app', stdio: 'ignore', env});
const deadline = Date.now() + 180000;
while (!await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false)) { if (server.exitCode !== null || Date.now() > deadline) throw Error('fixture server did not start'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile(data + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const session = process.env.FTL_HS_MOD_SESSION ? JSON.parse(await readFile(process.env.FTL_HS_MOD_SESSION, 'utf8')) : null;
const prefix = !session ? '' : session.mods.some(m => /multiverse/i.test(m.name)) ? 'hs_mv' : 'hs';
const expectAll = process.env.NATIVE_EXPECT ? JSON.parse(await readFile(process.env.NATIVE_EXPECT, 'utf8')) : {};
const cases = Object.entries(expectAll).filter(([, e]) => e.prefix === prefix).sort(([, a], [, b]) => !!b.run - !!a.run);
const file = async name => readFile(join(process.env.NATIVE_DIR, name));
const pass = label => console.log('PASS ' + label);

const browser = withOnboarding(await chromium.launch({headless: true, args: ['--disable-dev-shm-usage']}), origin), context = await browser.newContext({viewport: {width: 1440, height: 1000}}), page = await context.newPage();
const errors = [];
page.setDefaultTimeout(120000);
page.on('pageerror', e => errors.push('PAGE ' + e.message)); page.on('crash', () => errors.push('PAGE CRASHED'));
page.on('console', e => { if (e.type() === 'error' && !expectedConsoleError(e)) errors.push('CONSOLE ' + e.text() + ' @' + e.location().url); });
const ready = () => page.waitForFunction(() => document.body.dataset.sessionReady === 'true');
const snapshot = () => page.evaluate(() => JSON.parse(window.ftlEngine.debugSnapshot()));
const deviceSave = id => page.evaluate(async id => { const {deviceStore} = await import('/web/device-store.mjs'), me = await (await fetch('/api/auth/me')).json(); return deviceStore(me.id).get(id); }, id);
async function click(x, y) {
  const box = await page.locator('#game').boundingBox(), scale = Math.min(box.width / 1280, box.height / 720);
  await page.mouse.click(box.x + (box.width - 1280 * scale) / 2 + x * scale, box.y + (box.height - 720 * scale) / 2 + y * scale);
}
/** What the conversion has to keep: the fields the independent reader gives, with health as the whole number saved. */
function compare(label, got, want) {
  const crew = c => c.map(x => ({name: x.name, race: x.race, health: Math.round(x.health)})).sort((a, b) => a.name.localeCompare(b.name));
  const systems = s => Object.fromEntries(s.map(x => [x.name, x.level]).sort());
  assert.deepEqual({ship: got.ship, name: got.name, hull: got.hull, fuel: got.fuel, missiles: got.missiles, droneParts: got.droneParts, scrap: got.scrap, sector: got.sector,
    beacons: got.beacons, crew: crew(got.crew), systems: systems(got.systems), weapons: got.weapons, drones: got.drones, augments: got.augments, cargo: got.cargo},
  {ship: want.ship, name: want.name, hull: want.hull, fuel: want.fuel, missiles: want.missiles, droneParts: want.droneParts, scrap: want.scrap, sector: want.sector,
    beacons: want.beacons, crew: crew(want.crew), systems: Object.fromEntries(Object.entries(want.systems).sort()), weapons: want.weapons, drones: want.drones,
    augments: [...want.augments].sort(), cargo: want.cargo}, label);
  if (want.breaches != null) assert.equal(got.breaches, want.breaches, label + ': hull breaches');
  if (want.enemy != null) assert.equal(got.enemy != null, want.enemy, label + ': a ship at the beacon (' + got.enemy + ')');
  if (want.enemyHostile != null) assert.equal(got.enemyHostile, want.enemyHostile, label + ': that ship is hostile');
}

let failed = false;
try {
  const me = page.waitForResponse(r => r.url().endsWith('/api/auth/me')).catch(e => e); await page.goto(origin); await me; await ready();
  await page.evaluate(async ({username, initial}) => {
    let csrf = (await (await fetch('/api/auth/csrf')).json()).token;
    const post = (p, b) => fetch('/api/auth/' + p, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf}, body: JSON.stringify(b)});
    const password = 'native-import-fixture-' + username;
    let r = await post('login', {username, password}); if (r.status === 401) r = await post('login', {username, password: initial}); if (!r.ok) throw Error('login ' + r.status);
    csrf = (await (await fetch('/api/auth/csrf')).json()).token;
    if ((await (await fetch('/api/auth/me')).json()).mustChangePassword) { r = await post('password', {currentPassword: initial, newPassword: password}); if (!r.ok) throw Error('rotation'); csrf = (await (await fetch('/api/auth/csrf')).json()).token; await post('login', {username, password}); }
  }, {username: 'user01', initial: initial.get('user01')});

  // Saves > Import from FTL: the guide, beside Import save and New save.
  await page.goto(origin + '/?view=saves'); await ready();
  assert.ok(await page.locator('#saves-panel .actions #new-save').count() && await page.locator('#saves-panel .actions #native-import').count(), 'next to New save');
  await page.locator('#native-import').click();
  const dialog = page.locator('dialog#native-import-dialog[open]'); await dialog.waitFor();
  const guide = await page.locator('#native-import-guide').innerText();
  for (const text of ['ae_prof.sav', 'continue.sav', 'hs_mv_prof.sav', 'Documents\\My Games\\FasterThanLight', '~/Library/Application Support/FasterThanLight', '~/.local/share/FasterThanLight'])
    assert.ok(guide.includes(text), 'the guide names ' + text);
  pass('the guide names the files and the folder on Windows, macOS and Linux');
  // Garbage, a truncated file and too many files are refused with a reason; nothing can be imported.
  const refusals = [];
  for (const files of [[{name: 'continue.sav', mimeType: 'application/octet-stream', buffer: Buffer.from('not a save at all, just text')}],
    [{name: 'ae_prof.sav', mimeType: 'application/octet-stream', buffer: Buffer.from([9, 0, 0, 0, 2, 0, 0, 0, 200, 0, 0, 0])}],
    [{name: 'continue.sav', mimeType: 'application/octet-stream', buffer: Buffer.from([11, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 255, 255, 255, 127])}],
    ['a', 'b', 'c'].map(n => ({name: n + '.sav', mimeType: 'application/octet-stream', buffer: Buffer.alloc(16)}))]) {
    await page.locator('#native-import-files').setInputFiles(files);
    await page.waitForFunction(() => document.getElementById('native-import-note').textContent.length > 0);
    refusals.push(await page.locator('#native-import-note').textContent());
    assert.equal(await page.locator('#native-import-create').isDisabled(), true, 'nothing to import');
  }
  assert.ok(refusals.every(t => /FTL|file|Choose/.test(t)), refusals.join(' | '));
  pass('refused with a reason: ' + refusals.map(t => JSON.stringify(t.slice(0, 60))).join(', '));

  let imported = null;
  if (cases.length) {
    const [key, want] = cases[0];
    const files = [want.run, want.profile].filter(Boolean);
    await page.locator('#native-import-files').setInputFiles(await Promise.all(files.map(async n => ({name: n.match(/(?:hs_mv_|hs_)?(?:continue|ae_prof|prof)\.sav$/)?.[0] ?? n.split('/').pop(), mimeType: 'application/octet-stream', buffer: await file(n)}))));
    await page.locator('#native-import-details:not([hidden])').waitFor();
    const found = await page.locator('#native-import-found').innerText();
    assert.match(found, /Run \(/); if (want.profile) assert.match(found, /Profile \(/);
    assert.ok(found.includes(want.name) && found.includes('sector ' + want.sector) && found.includes(want.scrap + ' scrap'), found);
    const mods = await page.locator('#native-import-mods option').first().textContent();
    assert.equal(mods, {'': 'No mods', hs: 'Hyperspace only', hs_mv: 'Multiverse 5.5.1'}[prefix], 'the setup the files ask for');
    pass(key + ': found ' + JSON.stringify(found.replace(/\s+/g, ' ').slice(0, 160)) + ', mods ' + mods);
    await page.locator('#native-import-create').click();
    await dialog.waitFor({state: 'detached'});
    assert.match(await page.locator('#message').textContent(), /^Imported as /);
    const id = await page.locator('#saves-list li.save-row').first().getAttribute('data-id');
    imported = await deviceSave(id);
    assert.equal(imported.name, 'FTL: ' + want.name);
    assert.ok(imported.run.includes('nativeImport="1"') && (!want.profile || imported.profile.includes('nativeImport="1"')), 'the original files are kept until played');
    assert.equal(imported.meta.importedFrom, prefix || 'vanilla');
    assert.equal(imported.meta.summary.scrap, want.scrap);
    const row = page.locator(`#saves-list li.save-row[data-id="${id}"]`);
    if (prefix) {
      assert.equal(imported.setup.mods.length, prefix === 'hs' ? 1 : 3);
      assert.equal(await row.locator('.save-missing').count(), imported.setup.mods.length, 'the missing mods show on the save');
      pass(key + ': a ' + (prefix === 'hs' ? 'Hyperspace' : 'Multiverse') + ' save, its mods named as missing on this browser');
    } else {
      assert.equal(imported.setup.key, 'vanilla');
      assert.match(await row.innerText(), new RegExp(want.name + '.*sector ' + want.sector));
      pass(key + ': a save without mods, listed with ' + want.name + ', sector ' + want.sector);
    }
  }

  // The game: without mods Launch FTL plays the imported save (it is the current one), Continue converts it.
  await page.goto(origin); await ready();
  await launchGame(page);
  await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).state === 'MainMenuState', {}, {timeout: 300000});
  if ((await snapshot()).window === 'WelcomeChoiceBox') { await page.locator('#game').press('1'); await page.waitForTimeout(1000); }
  if (imported && !prefix) {
    const want = cases[0][1];
    await click(1180, 295);
    await page.waitForFunction(() => JSON.parse(window.ftlEngine.debugSnapshot()).state === 'InGameState', {}, {timeout: 120000});
    await page.waitForTimeout(3000);
    assert.equal((await snapshot()).state, 'InGameState', 'the imported run plays');
    await page.screenshot({path: join(out, 'imported-run.png')});
    assert.equal((await page.evaluate(() => window.ftlSaveDurably())).saved, true);
    const saved = await deviceSave(imported.id);
    assert.ok(!saved.run.includes('nativeImport') && saved.run.includes('<playerShip'), 'saved in the engine format');
    assert.ok(!saved.profile.includes('nativeImport') && saved.profile.includes('<xftl-profile'), 'the profile too');
    assert.equal(saved.meta.summary.scrap, want.scrap); assert.equal(saved.meta.summary.sector, want.sector);
    pass(cases[0][0] + ': Continue plays the imported run; the save now holds the engine\'s run and profile (scrap ' + saved.meta.summary.scrap + ', sector ' + saved.meta.summary.sector + ')');
  }

  // The conversion of every file of this session, and of the run saved from it, against what the original saved.
  const report = {}, wrong = [];
  for (const [key, want] of cases) try {
    const b64 = async n => n ? (await file(n)).toString('base64') : '';
    const result = JSON.parse(await page.evaluate(([r, p, x]) => window.nativeImportProbe(r, p, x), [await b64(want.run), await b64(want.profile), prefix]));
    report[key] = result;
    await writeFile(join(out, 'native-import-' + (prefix || 'vanilla') + '.json'), JSON.stringify(report, null, 1));
    console.log(key + ' parsed ' + JSON.stringify(result.parsed));
    assert.equal(result.error, undefined, key + ': ' + result.error);
    if (want.run) {
      compare(key + ' converted', result.run, want);
      compare(key + ' saved and loaded again', result.reloaded, want);
      assert.equal(result.savedNative, false);
    }
    if (want.scores != null) assert.equal(result.profile.scores, want.scores, key + ': scores');
    if (want.games != null) assert.equal(result.profile.games, want.games, key + ': games played');
    if (want.layouts) for (const l of want.layouts) assert.ok(result.profile.layouts.includes(l), key + ': unlocked ' + l);
    if (want.achievements) assert.deepEqual(result.profile.achievements, [...want.achievements].sort(), key + ': achievements');
    if (!want.run) pass(`${key}: profile ${result.profile.layouts.length} layouts, ${result.profile.scores} scores, ${result.profile.games} games`);
    else pass(`${key}: ${want.ship} hull ${want.hull} scrap ${want.scrap} sector ${want.sector}, ${want.crew.length} crew, ${Object.keys(want.systems).length} systems, ${want.beacons} beacons` +
      (result.profile ? `; profile ${result.profile.layouts.length} layouts, ${result.profile.achievements.length} achievements` : ''));
  } catch (e) { wrong.push(key); console.log('FAILED ' + key + ': ' + e.message.slice(0, 3000)); }
  assert.deepEqual(wrong, [], 'conversions that differ from the original');
  // A Hyperspace file in a game without mods (and the reverse) says which mods it needs instead of failing later.
  const mismatch = JSON.parse(await page.evaluate(x => window.nativeImportProbe('CwAAAAAAAAA=', '', x), prefix ? '' : 'hs_mv'));
  assert.match(mismatch.error ?? '', prefix ? /without mods/ : /Hyperspace/);
  pass('a file of other mods is refused with the mods it needs: ' + mismatch.error.slice(0, 120));
  await writeFile(join(out, 'native-import-' + (prefix || 'vanilla') + '.json'), JSON.stringify(report, null, 1));
  assert.deepEqual(errors, [], 'browser errors');
  console.log('PASS native-import ' + (prefix || 'vanilla') + ': ' + cases.length + ' saves from the original game');
} catch (e) {
  failed = true; console.log('FAILED', e.stack || e); console.log('errors', JSON.stringify(errors.slice(0, 40), null, 1));
  await page.screenshot({path: join(out, 'failure.png')}).catch(() => {});
} finally { await browser.close(); server.kill('SIGTERM'); }
process.exitCode = failed ? 1 : 0;
