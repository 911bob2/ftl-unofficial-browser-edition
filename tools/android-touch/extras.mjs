// The touch HUD's generic desktop elements (engine TouchExtras), on a running emulator with one finger (adb input, no
// gRPC needed). See docs/TESTS.md.
//   node tools/android-touch/extras.mjs --serial emulator-5570 --out <dir>
// Needs the debug build in flight on a Hyperspace save (Multiverse for the Multiverse checks), with no window open.
// A made-up mod's two event buttons are added through the debug console (modbutton): one in the top bar
// (<location>4</location>), one at a customLocation. Each must reach the touch HUD, be drawn and placed, and a tap on it
// must run its own click. On Multiverse it also checks the storage crate (after the ship button), the speed control
// (Lua drawing in FTL_BUTTON, pressed through its own ON_MOUSE_L_BUTTON_DOWN handler) and More Info.
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-extras') });

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

/** One finger tap at canvas units, through `input tap` in landscape screen pixels. */
function tap(s, cx, cy) {
    const { panelW, panelH } = d.size;
    const scale = panelW / 768;
    const sx = Math.round((panelH - s.canvasW * scale) / 2 + cx * scale);
    const sy = Math.round(cy * scale);
    d.shell(`input tap ${sx} ${sy}`);
}

const piece = (s, key) => (s.extras || []).find((p) => p.key === key);
const centre = (p) => [(p.x0 + p.x1) / 2, (p.y0 + p.y1) / 2];

async function until(what, f, ms = 8000) {
    const end = Date.now() + ms;
    for (;;) {
        const s = await d.state();
        if (f(s)) return s;
        if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
        await sleep(250);
    }
}

let s = await d.state();
if (!s.flight || !s.hsActive || s.window) throw new Error('needs the game in flight on a Hyperspace save, with no window open');

console.log('made-up mod buttons');
d.console('modbutton TOUCH_TEST_TOP 4');
d.console('modbutton TOUCH_TEST_CUSTOM 900 400');
s = await until('both pieces placed', (x) => piece(x, 'event:TOUCH_TEST_TOP')?.placed && piece(x, 'event:TOUCH_TEST_CUSTOM')?.placed);
const top = piece(s, 'event:TOUCH_TEST_TOP');
const custom = piece(s, 'event:TOUCH_TEST_CUSTOM');
check(top.zone === 'TOP_END' && top.y0 >= 0 && top.y1 <= 64, 'the top-bar button is in the top row', JSON.stringify(top));
check(custom.zone === 'DOCK' && custom.y1 <= 690, 'the customLocation button is in the dock above the pause button', JSON.stringify(custom));
check(top.x1 - top.x0 >= 40 && custom.x1 - custom.x0 >= 40, 'both are a finger wide');
d.frame('extras-buttons');
for (const [p, name] of [[top, 'TOUCH_TEST_TOP'], [custom, 'TOUCH_TEST_CUSTOM']]) {
    tap(s, ...centre(p));
    const after = await until(`${name} clicked`, (x) => x.eventButtonClicked === name).catch(() => null);
    check(after !== null, `a tap on ${name} runs its click`);
}

if (piece(s, 'event:UI_STORAGE_CHECK_BUTTON')) {
    console.log('Multiverse');
    const crate = piece(s, 'event:UI_STORAGE_CHECK_BUTTON');
    check(crate.placed && crate.zone === 'AFTER_SHIP' && crate.y0 <= 2, 'the storage crate sits in the top row after the ship button', JSON.stringify(crate));
    const speed = piece(s, 'lua:17');
    check(speed?.placed, 'the speed control (FTL_BUTTON Lua drawing) is placed', JSON.stringify(speed));
    if (speed?.placed) {
        // Variant 1: slow, normal, fast across the piece; the fast button is the right third.
        const level = s.speedLevel;
        const w = speed.x1 - speed.x0;
        tap(s, speed.x0 + w * 5 / 6, (speed.y0 + speed.y1) / 2);
        const fast = await until('speed up', (x) => x.speedLevel > 0).catch(() => null);
        check(fast !== null, 'a tap on the fast button speeds the game up', `before ${level}`);
        tap(s, speed.x0 + w / 2, (speed.y0 + speed.y1) / 2);
        const normal = await until('normal speed', (x) => x.speedLevel === 0).catch(() => null);
        check(normal !== null, 'a tap on the normal button brings it back');
    }
    const info = piece(s, 'more-info');
    if (info?.placed) {
        const was = s.moreInfo;
        tap(s, ...centre(info));
        const after = await until('More Info toggled', (x) => x.moreInfo !== was).catch(() => null);
        check(after !== null, 'a tap on More Info toggles it');
        if (after) tap(s, ...centre(info));
    }
    tap(s, ...centre(crate));
    const box = await until('storage menu', (x) => x.window === 'DialogueWindow').catch(() => null);
    check(box !== null, 'a tap on the crate opens the storage menu');
    d.frame('extras-storage');
}

console.log(failed ? `FAILED ${failed}` : 'PASS all');
process.exit(failed ? 1 : 0);
