// Touch HUD checks with one finger (adb input) on a running emulator: the status bar's messages, a finger slid onto a
// crew box, and keys that must not bring up the on-screen keyboard. See docs/TESTS.md.
//   node tools/android-touch/hud.mjs --serial emulator-5570 --out <dir>
// Needs the debug build open on a vanilla save (any screen): it starts a new run itself (debug console `newrun`) and
// answers the first event with the key 1.
// Checks:
// - keys from a keyboard Android does not count as physical (adb's) reach the game and leave the system's keyboard down;
// - a finger that comes down on empty space and slides onto a crew box selects that crew member, as a tap on the box
//   does (TouchHud.crewControlRedown);
// - the hull and shields warnings sit where the touch layout puts them, with their pointer lines, and the intruders
//   warning shows over the player's ship while a boarder is aboard (TouchProbe `warnings`).
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-hud') });

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

const size = /Physical size: (\d+)x(\d+)/.exec(d.shell('wm size'));
const over = /Override size: (\d+)x(\d+)/.exec(d.shell('wm size'));
const m = over || size;
d.size = { panelW: Math.min(Number(m[1]), Number(m[2])), panelH: Math.max(Number(m[1]), Number(m[2])) };

/** Landscape screen pixels of HUD point (x, y). */
function screen(s, hx, hy) {
    const { panelW, panelH } = d.size;
    const scale = panelW / 768;
    return [Math.round((panelH - s.canvasW * scale) / 2 + (hx + s.hudX) * scale), Math.round(hy * scale)];
}

const keyboardShown = () => /mInputShown=true/.test(d.shell('dumpsys input_method'));

async function until(what, f, ms = 30000) {
    const end = Date.now() + ms;
    for (;;) {
        const s = await d.state();
        if (f(s)) return s;
        if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
        await sleep(500);
    }
}

d.console('newrun');
await sleep(3000);
let s = await until('a run in flight', (x) => x.flight);
if (s.hsActive) console.log('  (a Hyperspace save: the start events may need more than the key 1)');

console.log('keys');
let keys = 0;
let shown = false;
for (let i = 0; i < 8 && s.window; i++) {
    d.shell('input keyevent KEYCODE_1');
    keys++;
    await sleep(900);
    shown ||= keyboardShown();
    s = await d.state();
}
// Space pauses and Space again resumes (the pause hotkey): the game takes both, the keyboard stays down.
const wasPaused = (await d.state()).paused;
d.shell('input keyevent KEYCODE_SPACE');
await sleep(800);
shown ||= keyboardShown();
const pausedBySpace = (await d.state()).paused;
d.shell('input keyevent KEYCODE_SPACE');
await sleep(800);
shown ||= keyboardShown();
s = await d.state();
check(pausedBySpace !== wasPaused && s.paused === wasPaused, 'Space paused and resumed the game', JSON.stringify([wasPaused, pausedBySpace, s.paused]));
check(!s.window, `the key 1 answered the start events (${keys} keys)`, `window ${s.window}`);
check(!shown, 'the on-screen keyboard stayed down');
if (shown) d.shell('input keyevent KEYCODE_BACK');
d.frame('keys');

console.log('a finger slid onto a crew box');
s = await d.state();
if (s.crewSel) {
    // A tap on a crew box with crew selected only clears the selection.
    const [x, y] = screen(s, 53, 129);
    d.shell(`input tap ${x} ${y}`);
    await sleep(600);
    s = await d.state();
}
check(s.crewSel === 0, 'nobody selected to begin with', `${s.crewSel}`);
{
    // Down on empty space left of the ship (no crew box, no system box, no room within 40 px), slid onto the first box.
    const [x0, y0] = screen(s, 140, 430);
    const [x1, y1] = screen(s, 110, 300);
    const [x2, y2] = screen(s, 53, 129);
    d.shell(`input motionevent DOWN ${x0} ${y0}; sleep 0.2; input motionevent MOVE ${x1} ${y1}; sleep 0.1; ` +
        `input motionevent MOVE ${x2} ${y2}; sleep 0.2; input motionevent UP ${x2} ${y2}`);
    await sleep(700);
    s = await d.state();
    check(s.crewSel === 1, 'down on empty space, lifted on the first crew box: that crew member is selected', `${s.crewSel}`);
    d.frame('slide-onto-crew-box');
    const [x, y] = screen(s, 53, 129);
    d.shell(`input tap ${x} ${y}`);
    await sleep(600);
}

console.log('status bar messages');
const near = (w, x, y) => w && Math.abs(w.x - x) <= 1 && Math.abs(w.y - y) <= 1;
d.console('damage 9');
await sleep(700);
s = await d.state();
const hull = s.warnings?.hull;
check(hull?.on, 'hull below 75 %: the hull warning runs', JSON.stringify(hull));
// Its place (366, 4): WARNING! centred at x 366 + 73, its top at 4 + 48, the text 23 under its baseline.
check(near(hull, 439, 91), 'hull warning under the scrap counter', JSON.stringify(hull && [hull.x, hull.y]));
check(JSON.stringify(hull?.line) === JSON.stringify([[374, 14], [379, 14], [379, 58], [394, 73], [484, 73]]),
    'hull warning line from the hull bar past the evade box', JSON.stringify(hull?.line));
d.frame('hull-warning');

d.console('DAMAGESYS shields');
await sleep(800);
s = await d.state();
const shields = s.warnings?.shields;
check(shields?.on, 'shields broken: the shields warning runs', JSON.stringify(shields));
check(near(shields, 79, 109), 'shields warning under the shields box', JSON.stringify(shields && [shields.x, shields.y]));
check(JSON.stringify(shields?.line?.[0]) === '[20,71]', 'shields warning line from the shields box', JSON.stringify(shields?.line));

d.console('boarder mantis');
await sleep(1500);
s = await d.state();
const intruders = s.warnings?.intruders;
check(intruders?.on, 'a boarder aboard: the intruders warning runs', JSON.stringify(intruders));
check(intruders && intruders.x === 449, 'intruders warning over the Kestrel', JSON.stringify(intruders && [intruders.x, intruders.y]));
d.frame('shields-intruders');
d.console('kill-ecrew');
d.console('HULL 9');

console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
