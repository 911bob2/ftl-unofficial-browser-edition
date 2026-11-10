// Mod scripts' io, os, debug and package in the website (sweep M1b, docs/HS_LUA.md "Files, programs and the sandbox"),
// Chromium and Firefox, against the disposable 8084 fixture. Our own Hyperspace-style test mod (no game content) lists
// one script that writes a file and asks for a shell. Covers: "Ask before running" shows before the game starts, names
// the mod and what it uses in plain words; Don't run goes back to the launcher with nothing started; Run starts it and
// is kept (no second question); the script's file lands in this browser's private folder and outlives the session; the
// sandbox holds (no shell, no path out); os.exit ends the game with a message naming the mod; the Mods panel shows the
// folder's size, Clear mod files, the allowed mod with Ask again, and "Disable Lua sandboxing" shown off and disabled with
// its reason. Screenshots: MODSCRIPTS_OUT (default .build/mods-scripts).
import assert from 'node:assert/strict';
import {mkdir, readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {chromium, firefox} from 'playwright';
import {withOnboarding} from './lib/onboarding.mjs';
import {expectedConsoleError} from './lib/launch.mjs';
import {zipSync} from '../web/vendor/fflate-0.8.3.mjs';

const origin = process.env.MENU_ORIGIN ?? 'http://localhost:8084';
assert.equal(origin, 'http://localhost:8084', 'Use the disposable 8084 fixture');
for (let i = 0; !await fetch(origin + '/health/ready').then(r => r.ok).catch(() => false); i++) { if (i > 120) throw Error('Fixture did not become ready'); await new Promise(r => setTimeout(r, 250)); }
const initial = new Map((await readFile((process.env.DEBUG_FIXTURE_DIR ?? '.build/debug-data') + '/bootstrap-credentials.txt', 'utf8')).trim().split(/\r?\n/).map(l => l.split('\t')));
const selected = (process.env.MENU_BROWSERS ?? 'Chromium,Firefox').split(',');
const out = process.env.MODSCRIPTS_OUT ?? '.build/mods-scripts';
await mkdir(out, {recursive: true});
const enc = new TextEncoder();
const zip = entries => Buffer.from(zipSync(Object.fromEntries(Object.entries(entries).map(([k, v]) => [k, [enc.encode(v), {level: 6}]])), {mtime: new Date(Date.UTC(2026, 0, 1))}));
const SCRIPT = `-- sweep M1b test script (our own)
M1B_TEST = {}
local f = io.open('m1b-note.txt', 'w')
M1B_TEST.wrote = f and f:write('written by a mod script') and f:close()
M1B_TEST.shell = os.execute()
M1B_TEST.out = select(3, io.open('../outside.txt', 'w'))
`;
const scriptMod = zip({
  'mod-appendix/metadata.xml': '<?xml version="1.0"?>\n<metadata><title>Script Test</title><version>1.0</version></metadata>\n',
  'data/hyperspace.xml': '<?xml version="1.0" encoding="UTF-8"?>\n<FTL><version>^1.0.0</version><scripts><script>data/m1b-test.lua</script></scripts></FTL>\n',
  'data/m1b-test.lua': SCRIPT,
});
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
  }, {user, password: 'mods-scripts-password-' + user, initial: initial.get(user)});
}
const openDialog = (page, id) => page.locator(`dialog#${id}[open]`);
const luaFiles = page => page.evaluate(async () => {
  const me = await (await fetch('/api/auth/me')).json();
  const {readLuaFiles} = await import('/web/lua-files.mjs');
  return (await readLuaFiles(me.id)).map(f => [f.name, new TextDecoder().decode(f.data)]);
});
/** Launch FTL with the enabled mod: the Hyperspace-without-Hyperspace warning (our mod is no real Hyperspace) gets Run
 *  anyway, "No save for these mods" gets Create save. Resolves 'lua' (the question is open), 'engine' or 'msg'. */
async function play(page) {
  await page.goto(origin + '/?view=play'); await ready(page);
  await page.waitForFunction(() => /with mods/.test(document.getElementById('launch-label').textContent), {}, {timeout: 60000});
  await page.locator('#launch').click();
  for (let i = 0; i < 6; i++) {
    const which = await (await page.waitForFunction(() => document.querySelector('dialog#hs-missing-dialog[open]') ? 'hs'
      : document.querySelector('dialog#no-save-dialog[open]') ? 'save'
      : document.querySelector('dialog#lua-consent-dialog[open]') ? 'lua' : window.ftlEngine ? 'engine'
      : (!document.getElementById('message').hidden && document.getElementById('message').textContent) ? 'msg' : null, {}, {timeout: 300000}).catch(async e => {
        await page.screenshot({path: join(out, 'stuck.png')}).catch(() => {});
        throw Error('the start did not get on: ' + await page.evaluate(() => [...document.querySelectorAll('dialog[open]')].map(d => d.id + ': ' + d.innerText.slice(0, 200)).join(' | ') + ' / ' + document.body.dataset.panel));
      })).jsonValue();
    if (which === 'hs') await page.locator('#hs-missing-run').click();
    else if (which === 'save') await page.locator('#no-save-create').click();
    else return which;
    await page.waitForTimeout(300);
  }
  throw Error('the start did not get on');
}
const engineUp = page => page.waitForFunction(() => {
  const failed = !document.getElementById('message').hidden && document.getElementById('message').textContent;
  return failed || !!window.ftlEngine && JSON.parse(window.ftlEngine.debugSnapshot()).state === 'MainMenuState';
}, {}, {timeout: 240000}).catch(async e => {
  await page.screenshot({path: join(out, 'stuck-engine.png')}).catch(() => {});
  throw Error('the game did not reach the main menu: ' + await page.evaluate(() => JSON.stringify({engine: !!window.ftlEngine,
    snapshot: window.ftlEngine?.debugSnapshot?.()?.slice(0, 300), loading: document.getElementById('loading-detail')?.textContent,
    message: document.getElementById('message')?.textContent, dialogs: [...document.querySelectorAll('dialog[open]')].map(d => d.id),
    panel: document.body.dataset.panel})) + '\nconsole: ' + seen.slice(-40).join('\n'));
});
const seen = [];

for (const [name, type, options, user] of [['Chromium', chromium, {channel: 'chromium'}, 'user03'], ['Firefox', firefox, {}, 'user07']].filter(([b]) => selected.includes(b))) {
  const browser = withOnboarding(await type.launch({headless: true, ...options}), origin);
  const pass = label => { checks.push({browser: name, label}); console.log(`PASS ${name}: ${label}`); };
  const errors = [], context = await browser.newContext({viewport: {width: 1280, height: 900}}), page = await context.newPage();
  page.setDefaultTimeout(60000);
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => { errors.push('native dialog: ' + d.message()); d.dismiss(); });
  page.on('console', e => { seen.push(e.type() + ': ' + e.text().slice(0, 300)); if (e.type() === 'error' && !expectedConsoleError(e)) console.log('browser console:', e.text()); });
  const shot = (what, locator = page) => locator.screenshot({path: join(out, `${name}-${what}.png`)}).catch(() => {});
  try {
    await signIn(page, user);
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    await page.locator('#mods-root .file-drop input[type=file]').setInputFiles([{name: 'script-test.ftl', mimeType: 'application/zip', buffer: scriptMod}]);
    await page.waitForFunction(() => /with mods/.test(document.getElementById('launch-label').textContent), {}, {timeout: 120000});
    // The Mods panel: nothing allowed yet, no files, the sandbox setting shown off and disabled with its reason.
    await page.locator('#mods-lua-allowed').waitFor();
    assert.match(await page.locator('#mods-lua-files').innerText(), /none/);
    assert.equal(await page.locator('#mods-lua-unsandboxed').isDisabled(), true);
    assert.equal(await page.locator('#mods-lua-unsandboxed').isChecked(), false);
    assert.match(await page.locator('#mods-lua').innerText(), /Not available in the browser: a web page cannot give scripts your files or programs/);
    await shot('mods-panel-setting', page.locator('#mods-lua'));
    pass('Mods panel: "Disable Lua sandboxing (dangerous)" is off and disabled, with the reason');

    // ---- Ask before running: Don't run starts nothing ---------------------------------------------------------------------
    assert.equal(await play(page), 'lua');
    const text = await openDialog(page, 'lua-consent-dialog').innerText();
    assert.match(text, /Run this mod's scripts\?/);
    assert.match(text, /Script Test: this mod's scripts use files \(io\.open\) and system commands \(os\.execute\)\./);
    assert.match(text, /Here they run in a private folder: they cannot reach your files or run programs\./);
    assert.deepEqual(await openDialog(page, 'lua-consent-dialog').locator('button').allTextContents(), ["Don't run", 'Run']);
    await shot('prompt', openDialog(page, 'lua-consent-dialog'));
    await page.locator('#lua-consent-no').click();
    await page.waitForFunction(() => /you chose not to run the scripts of Script Test/.test(document.getElementById('message').textContent));
    assert.equal(await page.evaluate(() => !!window.ftlEngine), false, 'no game started');
    assert.deepEqual(await luaFiles(page), [], 'no script ran');
    await shot('dont-run');
    pass("Don't run: back on the launcher, no game, no script ran");

    // ---- Run: the game starts, the script's files are in the private folder ------------------------------------------------
    assert.equal(await play(page), 'lua');
    await page.locator('#lua-consent-yes').click();
    await engineUp(page);
    assert.equal(await page.evaluate(() => !!window.ftlEngine), true, await page.locator('#message').textContent());
    const ran = await page.evaluate(() => { const r = window.ftlHsLua.run('check.lua', 'log("M1B " .. tostring(M1B_TEST.wrote) .. " " .. tostring(M1B_TEST.shell) .. " " .. tostring(M1B_TEST.out))'); return r; });
    assert.equal(ran, null);
    await page.evaluate(() => window.ftlHsLua.syncFiles());
    assert.deepEqual(await luaFiles(page), [['m1b-note.txt', 'written by a mod script']], 'the file is kept in this browser');
    const state = await page.evaluate(() => { window.ftlHsLua.run('state.lua', 'M1B_STATE = tostring(M1B_TEST.wrote) .. "|" .. tostring(M1B_TEST.shell) .. "|" .. tostring(M1B_TEST.out)'); return window.ftlHsLua.run('state2.lua', 'error(M1B_STATE, 0)'); });
    assert.equal(state, 'true|false|13', 'written, no shell, no way out');
    pass('Run: the game starts; the script writes its private folder, finds no shell and no path out');

    // ---- os.exit: the game ends with a message naming the mod --------------------------------------------------------------
    await page.evaluate(() => window.ftlHsLua.run('data/m1b-test.lua', 'os.exit(0)'));
    await openDialog(page, 'lua-exit-dialog').waitFor({timeout: 30000});
    assert.match(await openDialog(page, 'lua-exit-dialog').innerText(), /Script Test's script called os\.exit, which closes the game in the original\. Your last save is kept\./);
    await shot('os-exit', openDialog(page, 'lua-exit-dialog'));
    await Promise.all([page.waitForEvent('load', {timeout: 60000}), page.locator('#lua-exit-dialog-ok').click()]);
    await ready(page);
    pass('os.exit: the game ends, the launcher names the mod');

    // ---- The answer is kept; the folder too ----------------------------------------------------------------------------------
    assert.equal(await play(page), 'engine', 'no second question');
    await engineUp(page);
    assert.equal(await page.evaluate(() => window.ftlHsLua.run('read.lua', "local f = io.open('m1b-note.txt') local s = f:read('a') f:close() error(s, 0)")), 'written by a mod script');
    pass('the answer is kept per mod version; the next session reads the file');
    await page.evaluate(() => window.ftlEngine.stop?.());
    await page.goto(origin + '/?view=play'); await ready(page);
    await page.locator('#mods-panel > summary').click();
    await page.locator('#mods-lua-allowed li').first().waitFor();
    assert.match(await page.locator('#mods-lua-allowed').innerText(), /Script Test · allowed to run scripts that use files \(io\.open\) and system commands \(os\.execute\)/);
    assert.match(await page.locator('#mods-lua-files').innerText(), /0\.0 MiB|MiB/);
    await shot('mods-panel-allowed', page.locator('#mods-lua'));
    await page.locator('#mods-lua-allowed button', {hasText: 'Ask again'}).click();
    await page.waitForFunction(() => !document.querySelector('#mods-lua-allowed li'));
    await page.locator('#mods-lua-clear').click();
    await openDialog(page, 'mods-lua-clear-dialog').waitFor();
    await page.locator('#mods-lua-clear-dialog-yes').click();
    await page.waitForFunction(() => /none/.test(document.getElementById('mods-lua-files').textContent));
    assert.deepEqual(await luaFiles(page), []);
    pass('Mods panel: the allowed mod with Ask again, the folder size and Clear mod files');
    assert.deepEqual(errors.filter(e => !/ResizeObserver/.test(e)), []);
  } finally {
    await browser.close();
  }
}
console.log(`PASS mods-scripts: ${checks.length} checks`);
