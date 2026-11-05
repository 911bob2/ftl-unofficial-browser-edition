// Hyperspace's crew list reorder on touch (TouchHud.reorderTouch), on a running emulator with one finger (adb input).
// See docs/TESTS.md.
//   node tools/android-touch/reorder.mjs --serial emulator-5570 --out <dir>
// Needs the debug build in flight on a Hyperspace save with at least three crew, no window open and no crew selected.
// Checks: a quick tap on a box selects (no reorder); a finger held 1 s on a box and lifted there selects too, and the
// order stays; a finger held 1 s on the first box then slid to the third moves that crew member to the third place, the
// other two up one (CrewMemberFactory::MoveCrewMemberToSpot), and selects nobody; a slide that starts at once paints the
// selection as before and does not reorder.
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-reorder') });

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

/** Landscape screen pixels of HUD point (x, y). */
function screen(s, hx, hy) {
    const { panelW, panelH } = d.size;
    const scale = panelW / 768;
    return [Math.round((panelH - s.canvasW * scale) / 2 + (hx + s.hudX) * scale), Math.round(hy * scale)];
}

// TouchHud: crew boxes at x 10, from y 116, 30 apart, 86 x 27.
const box = (i) => [53, 116 + 30 * i + 13];

async function finger(s, points, holdMs) {
    const [x0, y0] = screen(s, ...points[0]);
    let cmd = `input motionevent DOWN ${x0} ${y0}; sleep ${holdMs / 1000}`;
    for (const p of points.slice(1)) {
        const [x, y] = screen(s, ...p);
        cmd += `; input motionevent MOVE ${x} ${y}; sleep 0.15`;
    }
    const [xe, ye] = screen(s, ...points[points.length - 1]);
    cmd += `; input motionevent UP ${xe} ${ye}`;
    d.shell(cmd);
    await sleep(600);
    return d.state();
}

async function deselect(s) {
    // With crew selected, a tap that is not on a room (here a crew box) clears the selection (TouchHud.crewControl).
    if (!s.crewSel) return s;
    const [x, y] = screen(s, ...box(0));
    d.shell(`input tap ${x} ${y}`);
    await sleep(500);
    const after = await d.state();
    if (after.crewSel) throw new Error('could not clear the selection');
    return after;
}

const size = /Physical size: (\d+)x(\d+)/.exec(d.shell('wm size'));
const over = /Override size: (\d+)x(\d+)/.exec(d.shell('wm size'));
const m = over || size;
d.size = { panelW: Math.min(Number(m[1]), Number(m[2])), panelH: Math.max(Number(m[1]), Number(m[2])) };

let s = await d.state();
s = await deselect(s);
if (!s.flight || !s.hsActive || s.window || (s.crewOrder || []).length < 3) throw new Error('needs a Hyperspace run in flight with three crew, no window open');
const start = s.crewOrder;
console.log('crew list', start.join(', '));

s = await finger(s, [box(0)], 100);
check(JSON.stringify(s.crewOrder) === JSON.stringify(start) && s.crewSel === 1, 'a tap on a box selects, nothing moves', JSON.stringify([s.crewOrder, s.crewSel]));
s = await deselect(s);

s = await finger(s, [box(1)], 1000);
check(JSON.stringify(s.crewOrder) === JSON.stringify(start) && s.crewSel === 1, 'held 1 s and lifted on its box: selected, nothing moves', JSON.stringify([s.crewOrder, s.crewSel]));
s = await deselect(s);

s = await finger(s, [box(0), [53, 150], [53, 175], box(2)], 1000);
d.frame('reorder-after');
const want = [start[1], start[2], start[0], ...start.slice(3)];
check(JSON.stringify(s.crewOrder) === JSON.stringify(want), 'held 1 s on the first box and slid to the third: it takes the third place', JSON.stringify(s.crewOrder));
check(s.crewSel === 0 && !s.crewHeld, 'and nobody is selected or held after the lift', JSON.stringify([s.crewSel, s.crewHeld]));

const before = s.crewOrder;
s = await finger(s, [box(0), [53, 150], box(2)], 100);
check(JSON.stringify(s.crewOrder) === JSON.stringify(before), 'a slide that starts at once does not reorder', JSON.stringify(s.crewOrder));
s = await deselect(s);

// Back to the order it had.
s = await finger(s, [box(2), [53, 175], [53, 150], box(0)], 1000);
check(JSON.stringify(s.crewOrder) === JSON.stringify(start), 'held on the third and slid to the first: the order it had', JSON.stringify(s.crewOrder));

console.log(failed ? `FAILED ${failed}` : 'OK');
process.exit(failed ? 1 : 0);
