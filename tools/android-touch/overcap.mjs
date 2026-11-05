// The over-capacity boxes on touch (round 21), on a running emulator with one finger (adb input). See docs/TESTS.md.
//   node tools/android-touch/overcap.mjs --serial emulator-5570 --out <dir> [--name phone] [--multiple]
// Needs the debug build in flight (a new run, any mode) with no window open. The debug console's get (through the
// receiver's hscmd) fills the augments, then the cargo, then the crew; the receiver's overcap answers with the open
// ship window's boxes (ShipWindow.diagnosticOverCapacity, relative to the window's origin, in canvas units).
// Checks: one over-capacity box at a time, on the screen and clear of the window, the Accept button and the pager's
// arrows; with both boxes in use the pager's arrow (a tap) shows the other one (--multiple, Multiverse's
// multipleOverCapacity: Hyperspace's own pager of the two waiting items); every crew member has a box and a
// dismiss button on the screen, none under the crew screen's over-capacity box; a tap on the extra crew member's
// dismiss button and then on Yes dismisses them. Frames: <dir>/<name>-*.png.
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-overcap') });
const name = arg('name', 'phone');
const multiple = process.argv.includes('--multiple');

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

/** The open ship window's boxes, or {window: null}. */
async function boxes() {
    d.adb('logcat', '-c');
    d.console('overcap');
    for (let i = 0; i < 60; i++) {
        const line = d.adb('logcat', '-d', '-s', 'FTL-Overcap:I').toString('utf8').split('\n').find((l) => l.includes('{'));
        if (line) return JSON.parse(line.slice(line.indexOf('{')));
        await sleep(100);
    }
    throw new Error('no overcap answer (is the debug build in flight?)');
}

const get = async (kind, item) => { d.console(`hscmd get ${kind} ${item}`); await sleep(700); };
const overlap = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
const inside = (r, s) => r.x >= s.x && r.y >= s.y && r.x + r.w <= s.x + s.w && r.y + r.h <= s.y + s.h;

/** The landscape screen (the `wm size` override for a tablet shape, else the panel). */
function landscape() {
    const out = d.shell('wm size');
    const m = /Override size: (\d+)x(\d+)/.exec(out) ?? /Physical size: (\d+)x(\d+)/.exec(out);
    const a = Number(m[1]), b = Number(m[2]);
    return { w: Math.max(a, b), h: Math.min(a, b) };
}

/** A tap on the middle of a rect relative to the window's origin [o], in canvas units (768 high, centred). */
async function tapRect(s, o, r) {
    const { w, h } = landscape();
    const scale = h / 768;
    const cx = o.x + r.x + r.w / 2, cy = o.y + r.y + r.h / 2;
    d.shell(`input tap ${Math.round((w - s.canvasW * scale) / 2 + cx * scale)} ${Math.round(cy * scale)}`);
    await sleep(700);
}

function checkEquipment(b, what, kinds) {
    check(b.tab === 'EQUIPMENT', `${what}: the equipment screen is open`, b.tab);
    check(b.over.length === 1, `${what}: one over-capacity box shows`, JSON.stringify(b.over));
    check(b.overPages === (multiple ? 1 : kinds), `${what}: ${multiple ? 1 : kinds} box(es) in use`, String(b.overPages));
    check(b.overArrows.length === (kinds > 1 ? 2 : 0), `${what}: the pager's arrows`, JSON.stringify(b.overArrows));
    for (const box of b.over) {
        check(inside(box, b.screen), `${what}: the ${box.kind} box is on the screen`, JSON.stringify(box));
        if (box.slot) check(inside(box.slot, box), `${what}: its item is inside it`, JSON.stringify(box.slot));
        for (const o of [...b.others, ...b.overArrows.map((rect) => ({ name: 'arrow', rect }))])
            check(!overlap(box, o.rect), `${what}: the ${box.kind} box is clear of the ${o.name}`, JSON.stringify(o.rect));
    }
}

const s = await d.state();
console.log(`canvas ${s.canvasW} wide, screen ${JSON.stringify(landscape())}`);

// ---- augments until the fourth waits in its box
// by id: Multiverse renames some (Scrap Arm [M]) and shares others' names
const augments = ['SCRAP_COLLECTOR', 'AUTO_COOLDOWN', 'ADV_SCANNERS', 'DRONE_RECOVERY', 'SHIELD_RECHARGE', 'WEAPON_PREIGNITE',
    'FTL_JUMPER', 'EXPLOSIVE_REPLICATOR', 'NANO_MEDBAY'];
let b = await boxes();
for (const a of augments) {
    await get('augment', a);
    b = await boxes();
    if (b.over?.some((x) => x.kind === 'augment')) break;
}
d.frame(`${name}-1-augment`);
checkEquipment(b, 'augment', 1);

// ---- weapons until one waits in the item box: both in use
for (let i = 0; i < 12; i++) {
    await get('weapon', 'LASER_BURST_2');
    b = await boxes();
    if (multiple ? b.overArrows.length === 2 : b.overPages === 2) break;
}
d.frame(`${name}-2-both`);
checkEquipment(b, 'both', 2);
const first = b.over[0]?.kind;
if (b.overArrows.length) {
    await tapRect(s, b.origin, b.overArrows[1]);
    b = await boxes();
    d.frame(`${name}-3-both-other-page`);
    check(b.over.length === 1 && b.over[0].kind !== first, `the pager's arrow shows the other box (${first} -> ${b.over[0]?.kind})`);
    checkEquipment(b, 'both, other page', 2);
}

// ---- crew until one is past the limit: the crew screen
const accept = b.others.find((o) => o.name === 'accept')?.rect;
if (accept) await tapRect(s, b.origin, accept);
for (let i = 0; i < 12; i++) {
    await get('crew', 'human');
    b = await boxes();
    if (b.tab === 'CREW' && b.drop) break;
}
await sleep(500);
d.frame(`${name}-4-crew`);
check(b.tab === 'CREW' && !!b.drop, 'the crew screen opens with its over-capacity box', JSON.stringify(b).slice(0, 300));
const withCrew = b.crew.filter((c) => c.crew);
console.log(`  ${withCrew.length} crew boxes: ${withCrew.map((c) => c.crew).join(', ')}`);
check(inside(b.drop, b.screen), 'the over-capacity box is on the screen', JSON.stringify(b.drop));
for (const o of b.others) check(!overlap(b.drop, o.rect), `the over-capacity box is clear of the ${o.name}`, JSON.stringify(o.rect));
for (const c of b.crew) {
    check(inside(c, b.screen), `crew box ${c.id} on the screen`, JSON.stringify(c));
    check(!overlap(c, b.drop), `crew box ${c.id} (${c.crew}) clear of the over-capacity box`, JSON.stringify(c));
    if (c.crew) check(!!c.dismiss && inside(c.dismiss, b.screen) && !overlap(c.dismiss, b.drop), `${c.crew}'s dismiss button is free`);
}
// the extra one: Hyperspace's own box, else the grid's ninth
const extra = b.crew.find((c) => c.over && c.crew) ?? withCrew.at(-1);
if (extra) {
    await tapRect(s, b.origin, extra.dismiss);
    b = await boxes();
    d.frame(`${name}-5-dismiss`);
    if (check(!!b.confirmYes, `the dismiss confirmation for ${extra.crew} opens`) &&
        check(inside(b.confirm, b.screen), 'the confirmation is on the screen', JSON.stringify(b.confirm))) {
        await tapRect(s, b.origin, b.confirmYes);
        await sleep(500);
        b = await boxes();
        d.frame(`${name}-6-dismissed`);
        const left = b.window === null ? null : b.crew.filter((c) => c.crew);
        check(left === null || (left.length === withCrew.length - 1 && !left.some((c) => c.crew === extra.crew)),
            `${extra.crew} is dismissed`, JSON.stringify(left?.map((c) => c.crew)));
    }
}
console.log(failed ? `FAILED ${failed}` : 'PASS overcap touch');
process.exit(failed ? 1 : 0);
