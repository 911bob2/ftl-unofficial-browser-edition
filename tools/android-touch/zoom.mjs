// Settings > Pinch to zoom on a running emulator with real fingers (gRPC sendTouch; see docs/TESTS.md#android-multi-touch-on-the-emulator).
//   node tools/android-touch/zoom.mjs --serial emulator-5570 --grpc 8570 --out <dir> [--off]
// Needs the debug build with ftl.dat imported. It writes the setting itself (run-as, preferences "zoom"), restarts the
// app, opens the game and starts a new run (debug console `newrun`, the first event answered with the key 1).
// Checks, on: at 1x no minimap; a pinch's fingers never reach the game (the router has no finger mid-pinch) and it zooms;
// a finger held on the zoomed picture puts the game's mouse on the canvas point under it, at several places; a zoomed
// tap on the first crew box selects that crew member and a tap on the box's 1x place does not; a finger slid from empty
// space onto the zoomed box selects too (a drag passed on with its path); the minimap shows, its map moves the view, its
// bar moves it and the place is kept in the preferences, its 1x goes back; two-finger taps zoom in and back.
// --off: the setting off: no zoom at all, a pinch zooms nothing, the game gets the fingers.
// Frames <out>/zoom-*.png (1x and zoomed, with show_touches).
import { Device, PKG, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const off = process.argv.includes('--off');
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: Number(arg('grpc', '8554')), adbPath, outDir: arg('out', '.build/android-zoom') });

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}
const j = JSON.stringify;

async function until(what, f, ms = 20000) {
    const end = Date.now() + ms;
    for (;;) {
        const s = await d.state();
        if (f(s)) return s;
        if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
        await sleep(300);
    }
}

// ---- the setting, then the game ----
const prefs = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name="on" value="${!off}" />\n</map>\n`;
d.shell(`am force-stop ${PKG}`);
d.shell(`run-as ${PKG} mkdir -p shared_prefs`);
{
    // run-as writes it from the device's own shell (adb shell does not forward the host's stdin).
    const b64 = Buffer.from(prefs).toString('base64');
    d.shell(`echo ${b64} | base64 -d | run-as ${PKG} sh -c 'cat > shared_prefs/zoom.xml'`);
}
check(d.shell(`run-as ${PKG} cat shared_prefs/zoom.xml`).includes(`value="${!off}"`), `the setting written: ${off ? 'off' : 'on'}`);

const focused = () => /mCurrentFocus=.*GameActivity/.test(d.shell('dumpsys window | grep mCurrentFocus'));
d.shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`);
await sleep(5000);
for (let i = 0; i < 12 && !focused(); i++) {
    d.shell('uiautomator dump /sdcard/u.xml');
    const xml = d.shell('cat /sdcard/u.xml');
    const at = (text) => {
        const m = new RegExp(String.raw`text="${text}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"`).exec(xml);
        return m ? [(Number(m[1]) + Number(m[3])) >> 1, (Number(m[2]) + Number(m[4])) >> 1] : null;
    };
    const target = at('Play') ?? at('Start') ?? at('Create') ?? at('New save');
    if (target) d.adb('shell', 'input', 'tap', String(target[0]), String(target[1]));
    await sleep(3500);
}
if (!focused()) throw new Error('could not open the game from the launcher');
await sleep(6000);
d.rot = d.rotation();
d.shell('settings put system show_touches 1');

d.console('newrun');
await sleep(3000);
let s = await until('a run in flight', (x) => x.flight, 40000);
for (let i = 0; i < 8 && s.window; i++) {
    d.shell('input keyevent KEYCODE_1');
    await sleep(900);
    s = await d.state();
}
if (s.window) throw new Error(`a window stays open: ${s.window}`);
// Paused, so the pictures differ only by what the fingers did.
if (!s.paused) {
    d.shell('input keyevent KEYCODE_SPACE');
    await sleep(600);
}
s = await d.state();

// ---- geometry: canvas units <-> landscape screen pixels, through the zoom ----
const { panelW, panelH } = d.size;
const k = panelW / s.canvasH; // view pixels per canvas unit (the picture fills the height)
const viewLeft = (panelH - s.canvasW * k) / 2;
/** Screen pixels of canvas point (cx, cy) under the zoom [z] (null: 1x). */
const toScreen = (z, cx, cy) => [viewLeft + (cx * k) * (z?.scale ?? 1) + (z?.panX ?? 0), (cy * k) * (z?.scale ?? 1) + (z?.panY ?? 0)];
const viewToScreen = (vx, vy) => [viewLeft + vx, vy];
const crewBox = [53 + s.hudX, 129];

async function fingers(points) {
    return d.touch.send(points.map(([x, y], i) => ({ ...d.screenToPanel(x, y), id: 40 + i, pressure: 100 })));
}
async function lift(points) {
    return d.touch.send(points.map(([x, y], i) => ({ ...d.screenToPanel(x, y), id: 40 + i, pressure: 0 })));
}
/** Two fingers from [a0], [b0] to [a1], [b1]; [mid] runs with them still down halfway. */
async function pinch(a0, b0, a1, b1, mid) {
    await fingers([a0]);
    await sleep(30);
    await fingers([a0, b0]);
    let midState = null;
    for (let i = 1; i <= 12; i++) {
        const t = i / 12;
        await fingers([[a0[0] + (a1[0] - a0[0]) * t, a0[1] + (a1[1] - a0[1]) * t], [b0[0] + (b1[0] - b0[0]) * t, b0[1] + (b1[1] - b0[1]) * t]]);
        await sleep(25);
        if (i === 6 && mid) midState = await mid();
    }
    await lift([a1, b1]);
    await sleep(400);
    return midState;
}
async function twoFingerTap(x, y) {
    await fingers([[x, y]]);
    await sleep(20);
    await fingers([[x, y], [x + 120, y]]);
    await sleep(60);
    await lift([[x, y], [x + 120, y]]);
    await sleep(400);
}
async function tapScreen(x, y, holdMs = 60) {
    await fingers([[x, y]]);
    await sleep(holdMs);
    await lift([[x, y]]);
    await sleep(500);
}

console.log(`Pinch to zoom ${off ? 'off' : 'on'} (canvas ${s.canvasW}x${s.canvasH}, screen ${panelH}x${panelW})`);
d.frame(`zoom-${off ? 'off' : 'on'}-1x`);
const midX = panelH / 2;
const midY = panelW / 2;

if (off) {
    check(s.zoom === null || s.zoom === undefined, 'no zoom at all while the setting is off', j(s.zoom));
    const mid = await pinch([midX - 60, midY], [midX + 60, midY], [midX - 300, midY], [midX + 300, midY], () => d.state());
    check(mid.routerPrimary !== -1 && mid.routerPrimary !== null, 'the game has the fingers mid-pinch', j(mid.routerPrimary));
    d.frame('zoom-off-after-pinch');
    s = await d.state();
    check(s.zoom === null || s.zoom === undefined, 'a pinch zooms nothing');
} else {
    check(s.zoom && s.zoom.scale === 1 && s.zoom.minimap === null, 'on: at 1x, no minimap', j(s.zoom));
    // A pinch near the top left, so the crew boxes stay in view: the game never gets a finger.
    const c = toScreen(null, 330, 210);
    const mid = await pinch([c[0] - 50, c[1]], [c[0] + 50, c[1]], [c[0] - 125, c[1]], [c[0] + 125, c[1]], () => d.state());
    check(mid.routerPrimary === -1 && mid.hudPrimary === -1, 'mid-pinch the game has no finger', j([mid.routerPrimary, mid.hudPrimary]));
    s = await d.state();
    check(s.zoom.scale > 2 && s.zoom.scale <= 2.6, 'the pinch zoomed about 2.5x', j(s.zoom.scale));
    check(s.crewSel === 0, 'the pinch selected no one', j(s.crewSel));
    check(!!s.zoom.minimap, 'the minimap shows while zoomed', j(s.zoom.minimap));
    d.frame('zoom-on-zoomed');
    let z = s.zoom;

    // A held finger: the game's mouse is on the canvas point under it, across what is in view.
    const seenX = [-z.panX / z.scale / k, (s.canvasW * k - z.panX) / z.scale / k];
    const seenY = [-z.panY / z.scale / k, (s.canvasH * k - z.panY) / z.scale / k];
    for (const [fx, fy] of [[0.25, 0.3], [0.5, 0.5], [0.75, 0.7], [0.2, 0.8]]) {
        const cx = Math.round(seenX[0] + (seenX[1] - seenX[0]) * fx);
        const cy = Math.round(seenY[0] + (seenY[1] - seenY[0]) * fy);
        const p = toScreen(z, cx, cy);
        if (z.minimap && p[0] - viewLeft >= z.minimap.box[0] && p[1] <= z.minimap.box[1] + z.minimap.box[3]) continue;
        await fingers([p]);
        await sleep(400);
        const held = await d.state();
        await d.touch.send([{ ...d.screenToPanel(...p), id: 40, pressure: 0 }]);
        await sleep(300);
        check(held.mouse && Math.abs(held.mouse[0] - cx) <= 2 && Math.abs(held.mouse[1] - cy) <= 2, `a held finger on canvas (${cx}, ${cy}) is that point to the game`, j(held.mouse));
    }
    s = await d.state();
    if (s.crewSel) {
        await tapScreen(...toScreen(s.zoom, ...crewBox));
        s = await d.state();
    }
    // The first crew box at its zoomed place (it moved on the screen).
    z = s.zoom;
    const moved = toScreen(z, ...crewBox);
    const oneX = toScreen(null, ...crewBox);
    check(Math.hypot(moved[0] - oneX[0], moved[1] - oneX[1]) > 80, 'zoomed, the crew box is elsewhere on the screen', j([oneX, moved]));
    await tapScreen(...toScreen(z, ...crewBox));
    s = await d.state();
    check(s.crewSel === 1, 'a zoomed tap on the first crew box selects that crew member', j(s.crewSel));
    d.frame('zoom-on-crew-selected');
    await tapScreen(...toScreen(z, ...crewBox));
    s = await d.state();
    check(s.crewSel === 0, 'a second zoomed tap on the box clears it', j(s.crewSel));
    // Slid from empty space onto the box: a drag reaches the game with its path.
    {
        // Empty space beside the crew boxes (tools/android-touch/hud.mjs: no box and no room near it).
        const from = toScreen(z, 140 + s.hudX, 380);
        const to = toScreen(z, ...crewBox);
        await fingers([from]);
        for (let i = 1; i <= 10; i++) {
            await fingers([[from[0] + (to[0] - from[0]) * i / 10, from[1] + (to[1] - from[1]) * i / 10]]);
            await sleep(25);
        }
        await sleep(200);
        await lift([to]);
        await sleep(600);
        s = await d.state();
        check(s.crewSel === 1, 'zoomed, a finger slid from empty space onto the box selects', j(s.crewSel));
        await tapScreen(...toScreen(z, ...crewBox));
    }

    // The minimap: its map's top left corner shows the picture's top left; its bar moves it; its place is kept.
    s = await d.state();
    let m = s.zoom.minimap;
    await tapScreen(...viewToScreen(m.map[0] + 3, m.map[1] + 3));
    s = await d.state();
    check(s.zoom.panX === 0 && s.zoom.panY === 0, 'the map\'s corner shows the picture\'s corner', j([s.zoom.panX, s.zoom.panY]));
    check(s.crewSel === 0, 'a finger on the minimap reached the game', j(s.crewSel));
    m = s.zoom.minimap;
    {
        const g = viewToScreen(m.bar[0] + m.bar[2] * 0.3, m.bar[1] + m.bar[3] / 2);
        await fingers([g]);
        for (let i = 1; i <= 10; i++) {
            await fingers([[g[0] - i * 60, g[1] + i * 30]]);
            await sleep(25);
        }
        await lift([[g[0] - 600, g[1] + 300]]);
        await sleep(600);
    }
    s = await d.state();
    const box = s.zoom.minimap.box;
    check(Math.abs(box[0] - (m.box[0] - 600)) <= 3 && Math.abs(box[1] - (m.box[1] + 300)) <= 3, 'its bar moved it', j([m.box, box]));
    const kept = d.shell(`run-as ${PKG} cat shared_prefs/zoom.xml`);
    check(/name="x" value="0\.\d+"/.test(kept) && /name="y" value="0\.\d+"/.test(kept), 'the place is kept in the preferences', kept.replace(/\s+/g, ' '));
    d.frame('zoom-on-minimap-moved');
    const r = s.zoom.minimap.reset;
    await tapScreen(...viewToScreen(r[0] + r[2] / 2, r[1] + r[3] / 2));
    s = await d.state();
    check(s.zoom.scale === 1 && s.zoom.minimap === null, 'its 1x goes back to 1x', j(s.zoom));
    // Two-finger taps: 2x there, then back.
    await twoFingerTap(midX, midY);
    s = await d.state();
    check(s.zoom.scale === 2, 'a two-finger tap at 1x zooms 2x', j(s.zoom.scale));
    d.frame('zoom-on-two-finger-tap');
    await twoFingerTap(midX - 400, midY + 200);
    s = await d.state();
    check(s.zoom.scale === 1, 'another goes back to 1x', j(s.zoom.scale));
    d.frame('zoom-on-back-1x');
}

d.close();
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
