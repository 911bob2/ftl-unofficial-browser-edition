// One tap acts once, on its own button (engine TouchRouter and HostInput), on a running emulator with one finger (adb
// input, no gRPC). See docs/TESTS.md.
//   node tools/android-touch/click-through.mjs --serial emulator-5554 --out <dir>
// Needs the debug build on the main menu with no window open (a vanilla or modded setup). A button acts when the finger
// lifts on the button it went down on, as the original's touch buttons do; a finger slid off it, or onto another button,
// taps nothing; what a tap closes or opens gets nothing of it:
// - the options' Close tapped where a menu button lies under it (back on the menu, nothing tapped there), then Close
//   held and slid onto each menu button (nothing acts, the options stay);
// - the menu: Statistics tapped (open), a menu button held and slid off it, or onto another one (nothing acts);
// - Game Over (a new run, the console's gameover): Main Menu held and slid onto where the menu's button will be (Game Over
//   stays), Main Menu tapped (the menu, nothing tapped there), Hangar tapped (the hangar, its list closed, the ship kept).
// The game reads its screen from touchstate (state, snapshot: the website's debug snapshot with the buttons' places).
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-click-through') });
const shape = arg('shape', 'phone');

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

// `wm size <w>x<h>` (the tablet shape) gives an override size, which input events use.
const wm = /Override size: (\d+)x(\d+)/.exec(d.shell('wm size'));
const size = wm ? { panelW: Number(wm[1]), panelH: Number(wm[2]) } : d.size;
/** Canvas units to landscape screen pixels: the frame fitted to the screen, centred. */
function screen(s, cx, cy) {
    const long = Math.max(size.panelW, size.panelH), short = Math.min(size.panelW, size.panelH);
    const scale = Math.min(long / s.canvasW, short / s.canvasH);
    return [Math.round((long - s.canvasW * scale) / 2 + cx * scale), Math.round((short - s.canvasH * scale) / 2 + cy * scale)];
}
const tap = (s, cx, cy) => d.shell(`input tap ${screen(s, cx, cy).join(' ')}`);
/** A finger down at (x0, y0), slid to (x1, y1) and lifted there. */
const slide = (s, x0, y0, x1, y1, ms = 700) => d.shell(`input swipe ${screen(s, x0, y0).join(' ')} ${screen(s, x1, y1).join(' ')} ${ms}`);
const centre = (r) => [Math.round(r.x + r.width / 2), Math.round(r.y + r.height / 2)];

async function until(what, f, ms = 15000) {
    const end = Date.now() + ms;
    for (;;) {
        // A lost touchstate answer (logcat) is asked again.
        const s = await d.state().catch(() => null);
        if (s && f(s)) return s;
        if (Date.now() > end) throw new Error(`timed out waiting for ${what} (state ${s?.state}, window ${s?.snapshot?.window})`);
        await sleep(300);
    }
}
const settled = async () => { await sleep(1500); return until('the screen', () => true); };
const onMenu = (s) => s.state === 'MainMenuState' && s.snapshot?.window === 'main';
const inOptions = (s) => s.state === 'SettingsMenuState' && s.snapshot?.window === 'OptionsWindow';
const under = (buttons, x, y) => Object.entries(buttons).find(([, b]) => b.active && x >= b.x && x < b.x + b.width && y >= b.y && y < b.y + b.height)?.[0] ?? null;
const where = (s) => `state ${s.state}, window ${s.snapshot?.window}`;

let s = await until('the game', () => true);
if (!onMenu(s)) throw new Error(`needs the main menu with no window open (${where(s)})`);
const menu = s.snapshot.buttons;
console.log(`${shape}: canvas ${s.canvasW}x${s.canvasH}, menu ${JSON.stringify(menu)}`);
const openOptions = async () => {
    tap(s, ...centre(menu.options));
    return until('the options', (x) => inOptions(x) && x.snapshot?.close);
};

// ---- the options' Close ----------------------------------------------------------------------------------------------
let o = await openOptions();
let close = o.snapshot.close;
let point = centre(close), hit = null;
for (let y = close.y + 3; y < close.y + close.height - 3 && !hit; y += 3) for (let x = close.x + 3; x < close.x + close.width - 3; x += 3) {
    const b = under(menu, x, y);
    if (b && b !== 'quit') { point = [x, y]; hit = b; break; }
}
console.log(`Close ${JSON.stringify(close)}, tapped at ${point} over ${hit ?? 'no menu button'}`);
d.frame(`${shape}-click-options`);
tap(o, ...point);
s = await settled();
d.frame(`${shape}-click-options-closed`);
check(onMenu(s), `Close tapped ${hit ? `over ${hit} ` : ''}returns to the main menu and taps nothing under it`, where(s));
if (!onMenu(s)) throw new Error('not back on the menu');

o = await openOptions();
for (const name of ['tutorial', 'stats', 'options', 'credits', 'start', 'continue'].filter((n) => menu[n]?.active)) {
    slide(o, ...centre(o.snapshot.close), ...centre(menu[name]));
    s = await settled();
    if (!check(inOptions(s), `Close held and lifted on ${name}: nothing acts, the options stay`, where(s))) {
        d.frame(`${shape}-click-fail-${name}`);
        throw new Error('the options went');
    }
}
d.frame(`${shape}-click-options-slid-off`);
tap(o, ...centre(o.snapshot.close));
s = await settled();
check(onMenu(s), 'Close tapped: the main menu', where(s));

// ---- the menu's buttons ----------------------------------------------------------------------------------------------
const other = ['credits', 'tutorial', 'options'].find((n) => menu[n]?.active);
slide(s, ...centre(menu.stats), Math.round(s.canvasW * 0.1), Math.round(s.canvasH * 0.1));
s = await settled();
check(onMenu(s), 'Statistics held and slid off it: nothing acts', where(s));
slide(s, ...centre(menu.stats), ...centre(menu[other]));
s = await settled();
check(onMenu(s), `Statistics held and slid onto ${other}: nothing acts`, where(s));
tap(s, ...centre(menu.stats));
s = await until('the statistics', (x) => x.state === 'BrowserStatisticsState').catch(() => null) ?? await d.state();
d.frame(`${shape}-click-stats`);
check(s.state === 'BrowserStatisticsState', 'Statistics tapped: the statistics', where(s));
if (s.state === 'BrowserStatisticsState') {
    // Back, at the bottom right of the 1280x720 screen.
    tap(s, 1150, 650);
    s = await until('the menu', onMenu).catch(() => null) ?? await d.state();
    check(onMenu(s), "the statistics' Back tapped: the main menu", where(s));
}

// ---- Game Over --------------------------------------------------------------------------------------------------------
async function gameOver() {
    d.console('newrun');
    await until('a run', (x) => x.flight, 30000);
    await sleep(2000);
    d.console('gameover LOOSE_HULL');
    return until('Game Over', (x) => x.snapshot?.window === 'GameOverWindow');
}
let g = await gameOver();
d.frame(`${shape}-click-gameover`);
let buttons = g.snapshot.clickTargets.windowButtons; // restart, hangar, main menu, quit, stats
const target = ['stats', 'tutorial', 'credits', 'options'].find((n) => menu[n]?.active);
// The menu's buttons on its own canvas: Game Over is in flight, on the same touch canvas.
slide(g, ...centre(buttons[2]), ...centre(menu[target]));
s = await settled();
check(s.snapshot?.window === 'GameOverWindow', `Game Over's Main Menu held and lifted where the menu's ${target} is: nothing acts`, where(s));
tap(g, ...centre(buttons[2]));
s = await until('the menu', (x) => x.state === 'MainMenuState').catch(() => null) ?? await d.state();
s = await settled();
d.frame(`${shape}-click-gameover-menu`);
check(onMenu(s), "Game Over's Main Menu tapped: the main menu, nothing tapped there", where(s));

g = await gameOver();
buttons = g.snapshot.clickTargets.windowButtons;
tap(g, ...centre(buttons[1]));
let h = await until('the hangar', (x) => x.state === 'SelectShipState');
h = await settled();
d.frame(`${shape}-click-gameover-hangar`);
check(h.state === 'SelectShipState' && !h.snapshot.shipList && !h.snapshot.customizing,
    "Game Over's Hangar tapped: the hangar, nothing tapped there", `state ${h.state}, list ${h.snapshot?.shipList}`);
// The first run's welcome box (the touch hangar's) goes first, by its number key.
if (h.snapshot.welcome) {
    d.shell('input keyevent KEYCODE_1');
    h = await until('the welcome box gone', (x) => x.snapshot?.welcome === false);
}
const list = h.snapshot.controls.find((c) => c.id === 'list' && c.enabled);
if (list) {
    // The hangar's controls are in its 1024 wide block, centred on the canvas like the flight HUD (TouchMode.hudX).
    const hudX = (h.canvasW - 1024) / 2;
    const [lx, ly] = centre(list);
    slide(h, lx + hudX, ly, Math.round(h.canvasW * 0.5), Math.round(h.canvasH * 0.4));
    s = await settled();
    check(s.state === 'SelectShipState' && !s.snapshot.shipList, "the hangar's List held and slid off it: nothing acts", `list ${s.snapshot?.shipList}`);
    tap(h, lx + hudX, ly);
    s = await until('the ship list', (x) => x.snapshot?.shipList).catch(() => null) ?? await d.state();
    d.frame(`${shape}-click-hangar-list`);
    check(s.snapshot?.shipList === true, "the hangar's List tapped: the ship list", `list ${s.snapshot?.shipList}`);
    d.shell('input keyevent KEYCODE_BACK');
    await settled();
}

console.log(failed ? `FAILED ${failed}` : 'PASS click-through');
process.exit(failed ? 1 : 0);
