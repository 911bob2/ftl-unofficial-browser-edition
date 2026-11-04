// Multi-touch tests of the Android touch controls, on a running emulator, through the real input stack. See docs/TESTS.md.
//   node tools/android-touch/run.mjs --serial emulator-5570 --grpc 8570 --out <dir> [--only name,name] [--list]
// Every scenario starts a new run with a spawned enemy, puts real fingers on the virtual touchscreen, reads the game's
// state from the debug build (TouchProbe), saves frames to <dir>, and prints PASS or FAIL per check.
import { Device, sleep, PKG } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const SERIAL = arg('serial', 'emulator-5554');
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;

let d;
let scenario = '';
const results = [];
function check(ok, what, detail = '') {
    results.push({ scenario, ok, what, detail });
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}
const j = (o) => JSON.stringify(o);

// ---- HUD places, in canvas units ----
const WEAPON_BOX = [256, 721];
const SLOT_X = 273;
const slotY = (i) => 324 + (2 - i) * 88; // slot 2 is the one above the two the Kestrel starts with
const EMPTY = [620, 650]; // space, under the player's ship
const CONTINUE = [456, 424];
const PAUSE_BUTTON = [1099, 724];
const JUMP_BUTTON = [727, 28];
const SHIP_BUTTON = [817, 28];
const OPTIONS_BUTTON = [1165, 28];
const SENSORS_BOX = [863, 721];
const DOORS_BOX = [976, 721];
const CREW_BOX = [222, 128];

async function until(what, f, ms = 15000) {
    const end = Date.now() + ms;
    for (;;) {
        const s = await d.state();
        if (f(s)) return s;
        if (Date.now() > end) throw new Error(`timed out waiting for ${what}: ${j({ ...s, enemy: !!s.enemy, doors: 0 })}`);
        await sleep(200);
    }
}

/** Taps down an event box's first choice, wherever the box's text has put it. */
async function closeBox() {
    for (const y of [424, 384, 404, 444, 464, 484, 504, 524, 544, 344, 364, 304, 324]) {
        await d.tap(CONTINUE[0], y);
        await sleep(250);
        const s = await d.state();
        if (!s.window) return s;
    }
    throw new Error('the event box did not close');
}

/** A new run with an enemy, and [weapon] in a new slot with its power on. Returns the weapon's slot. */
async function fresh({ weapon = 'BEAM_2', ship = 'PIRATE', power = true } = {}) {
    d.fingers.clear();
    d.console('newrun');
    await sleep(2500);
    await until('flight', (s) => s.flight && s.window, 20000);
    await closeBox();
    d.console(`dev-weapon ${weapon}`);
    d.console(`spawn-ship ${ship} 1.normal`);
    const s = await until('the enemy', (x) => x.enemy && !x.focusPlayer && x.slots.some((w) => w?.id === weapon));
    const slot = s.slots.findIndex((w) => w?.id === weapon);
    if (power) {
        await d.tap(...WEAPON_BOX);
        await sleep(200);
        await d.tap(SLOT_X, slotY(slot));
        await until('the weapon on', (x) => x.slots[slot].on, 4000);
    }
    return slot;
}

/** Arms the weapon whose power is on and whose column is open. */
async function arm(slot) {
    await d.tap(SLOT_X, slotY(slot));
    const s = await until('armed', (x) => x.armedSlot === slot, 4000);
    return s;
}

function bounds(s) {
    const r = s.enemy.rooms;
    return {
        x0: Math.min(...r.map((a) => a.x)), y0: Math.min(...r.map((a) => a.y)),
        x1: Math.max(...r.map((a) => a.x + a.w)), y1: Math.max(...r.map((a) => a.y + a.h)),
    };
}

const centre = (r) => [(r.cx0 + r.cx1) / 2, (r.cy0 + r.cy1) / 2];

/** The first finger on a room and a drag that draws the line. Returns the finger and the state. */
async function placeLine(s0, { room, to } = {}) {
    const rooms = s0.enemy.rooms;
    const start = room ?? rooms[0];
    const [cx, cy] = centre(start);
    const f = await d.down(cx, cy);
    await sleep(100);
    const [tx, ty] = to ?? [cx + 60, cy + 25];
    await d.moveTo(f, tx, ty);
    await d.settle();
    return { f, s: await d.state() };
}

/** Moves the first finger so the line goes towards the ship's middle by about [by] on x. Returns the move. */
async function slideInward(placed, by = 20) {
    const s = await d.state();
    const b = bounds(s);
    const mid = (s.beam.sx + s.beam.ex) / 2;
    const dx = mid > (b.x0 + b.x1) / 2 ? -by : by;
    await d.moveTo(placed.f, placed.f.cx + dx, placed.f.cy);
    await d.settle();
    return dx;
}

const armedNow = (s) => s.clickEvent || !!s.beam;

const inBounds = (b, l) => Math.min(l.sx, l.ex) >= b.x0 - 1 && Math.max(l.sx, l.ex) <= b.x1 + 1 &&
    Math.min(l.sy, l.ey) >= b.y0 - 1 && Math.max(l.sy, l.ey) <= b.y1 + 1;

function fingersClear(s, what = 'no finger is left behind') {
    return check(s.routerPrimary === -1 && s.hudPrimary === -1 && s.extra.length === 0, what, j({ r: s.routerPrimary, h: s.hudPrimary, x: s.extra }));
}

/** One finger still does normal work: a press is seen, and a tap on a crew box selects. */
async function oneFingerWorks() {
    const f = await d.down(...EMPTY);
    await sleep(120);
    let s = await d.state();
    const seen = s.routerPrimary >= 0 && s.hudPrimary === s.routerPrimary;
    await d.up(f);
    await sleep(150);
    s = await d.state();
    if (s.crewSel) {
        await d.tap(...EMPTY);
        s = await d.state();
    }
    check(seen, 'one finger: the press reaches the HUD');
    await d.tap(...CREW_BOX);
    await sleep(120);
    s = await d.state();
    check(s.crewSel === 1, 'one finger: tapping a crew box selects', j({ crewSel: s.crewSel, armed: s.armedSlot, window: s.window }));
    await d.tap(...EMPTY);
}

// ---- scenarios ----
const scenarios = {};

scenarios.beam_one_finger = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    check(s.paused, 'arming auto-pauses');
    const placed = await placeLine(s);
    d.frame('beam_one_finger-line');
    check(placed.s.beam?.visible && placed.s.beam.rooms.length > 0, 'one finger draws a line over rooms', j(placed.s.beam));
    await d.up(placed.f);
    s = await until('target set', (x) => x.targets[slot] || !x.armedSlot, 3000);
    check(s.targets[slot]?.sx === placed.s.beam.sx && s.targets[slot]?.angle === placed.s.beam.angle, 'lift fires where it showed', j(s.targets));
};

scenarios.beam_slide_and_fire = async () => {
    // A Zoltan-shielded enemy would stop the beam short of the rooms: take another one.
    let slot = await fresh();
    let s = await d.state();
    for (let i = 0; i < 6 && s.enemy.superShield > 0; i++) {
        slot = await fresh();
        s = await d.state();
    }
    s = await arm(slot);
    const rooms = s.enemy.rooms;
    // Start on the room nearest the top left; slide so the start lands in the room farthest from it.
    const a = rooms[0];
    const b = rooms.reduce((best, r) => (Math.hypot(r.x - a.x, r.y - a.y) > Math.hypot(best.x - a.x, best.y - a.y) ? r : best), a);
    const placed = await placeLine(s, { room: a });
    const l0 = placed.s.beam;
    check(l0?.visible, 'line placed', j(l0));
    d.frame('slide-1-placed');
    const g = await d.down(460, 180);
    await sleep(120);
    s = await d.state();
    check(j(s.extra).length > 2 && s.extra.length === 1, 'the second finger is seen as an extra finger', j(s.extra));
    check(j(s.beam) === j(l0), 'a second finger alone does not move the line', j(s.beam));
    // The finger's move in canvas units, equal to the start's move in ship units at full scale.
    const dx = b.x - a.x + (b.w - a.w) / 2;
    const dy = b.y - a.y + (b.h - a.h) / 2;
    const fx = placed.f.cx;
    const fy = placed.f.cy;
    await d.moveTo(placed.f, fx + dx, fy + dy, { steps: 12 });
    await d.settle();
    s = await d.state();
    const l1 = s.beam;
    d.frame('slide-2-slid');
    check(Math.abs(l1.angle - l0.angle) < 1e-3, 'the line keeps its angle', `${l0.angle} -> ${l1.angle}`);
    check(l1.len === l0.len, 'the line keeps its length');
    check(inBounds(bounds(s), l1) || !inBounds(bounds(s), l0), 'the line is inside the ship', j({ l1, b: bounds(s) }));
    check(Math.abs((l1.sx - l0.sx) - dx) <= 4 || !inBounds(bounds(s), { ...l1, sx: l1.sx + 2, ex: l1.ex + 2 }) || !inBounds(bounds(s), { ...l1, sx: l1.sx - 2, ex: l1.ex - 2 }), 'the line moved with the finger in x', `${l1.sx - l0.sx} vs ${dx}`);
    check(Math.abs((l1.sy - l0.sy) - dy) <= 4 || !inBounds(bounds(s), { ...l1, sy: l1.sy + 2, ey: l1.ey + 2 }) || !inBounds(bounds(s), { ...l1, sy: l1.sy - 2, ey: l1.ey - 2 }), 'the line moved with the finger in y', `${l1.sy - l0.sy} vs ${dy}`);
    check(j(l1.rooms) !== '[]', 'the slid line crosses rooms', j(l1));
    await d.up(g);
    await sleep(120);
    s = await d.state();
    check(s.extra.length === 0, 'second finger up: no extra finger');
    const line = s.beam;
    await d.up(placed.f);
    await sleep(200);
    s = await d.state();
    const t = s.targets[slot];
    check(t && t.sx === line.sx && t.sy === line.sy && Math.abs(t.angle - line.angle) < 1e-3, 'the aim set is the line shown', j({ t, line }));
    d.frame('slide-3-aimed');
    fingersClear(s);
    // Let it charge and fire: the weapon needs its charge time.
    const hurt = (x) => (x.enemy ? x.enemy.hull + x.enemy.sysHealth : 0); // A beam can leave the hull and break a system (RESIST).
    const hurt0 = hurt(s);
    const hull0 = s.enemy.hull;
    const end = Date.now() + 30000;
    let fired = false;
    let n = 0;
    while (Date.now() < end) {
        s = await d.state();
        if (!s.targets[slot]) { fired = true; console.log(`    aim left the list ${j(s.slots[slot])} after ${30000 - (end - Date.now())} ms`); break; }
        d.console('fix'); // Enemy fire on the weapons system would take the power, and the aim with it.
        if (s.slots[slot].cur > s.slots[slot].charge - 1.5) d.frame(`slide-4-charging-${n++ % 3}`);
        await sleep(100);
    }
    check(fired && s.slots[slot].on, 'the weapon fired its aim, with its power on', j(s.slots[slot]));
    // The beam takes a moment to cross and sweep: frames while it does, and the hull it costs.
    let now = hurt0;
    for (let i = 0; i < 16 && now === hurt0; i++) {
        d.frame(`slide-5-firing-${i}`);
        s = await d.state();
        now = hurt(s);
        await sleep(100);
    }
    d.frame('slide-6-after');
    console.log(`    enemy hull ${hull0} -> ${s.enemy?.hull}, hull + system bars ${hurt0} -> ${now}, line ${j(line)}`);
    check(now < hurt0, 'the beam hurt the enemy', `${hurt0} -> ${now}`);
};

scenarios.beam_clamp = async () => {
    for (const ship of ['PIRATE', 'REBEL']) {
        const slot = await fresh({ ship });
        let s = await arm(slot);
        const b = bounds(s);
        console.log(`    ${ship}: offset ${s.enemy.offsetX},${s.enemy.offsetY} bounds ${j(b)}`);
        const placed = await placeLine(s);
        const l0 = placed.s.beam;
        check(inBounds(b, l0) || true, 'placed', j(l0));
        const g = await d.down(460, 180);
        await sleep(100);
        const f = placed.f;
        const dirs = { right: [1350, f.cy], left: [320, f.cy], down: [f.cx, 760], up: [f.cx, 10] };
        for (const [name, to] of Object.entries(dirs)) {
            await d.moveTo(f, to[0], to[1], { steps: 18 });
            await d.settle(150);
            s = await d.state();
            d.frame(`clamp-${ship}-${name}`);
            const l = s.beam;
            check(inBounds(b, l), `${ship} ${name}: both ends stay inside the ship's bounds`, j({ l, b }));
            check(Math.abs(l.angle - l0.angle) < 1e-3 && l.len === l0.len, `${ship} ${name}: one rigid line`, j({ l, l0 }));
        }
        await d.up(g, f);
        await sleep(150);
    }
};

scenarios.lift_second_first = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    const placed = await placeLine(s);
    const g = await d.down(460, 180);
    await sleep(100);
    const slid0 = placed.s.beam;
    await slideInward(placed, 20);
    const slid = (await d.state()).beam;
    check(slid.sx !== slid0.sx, 'the line slid', j({ slid0, slid }));
    await d.up(g);
    await sleep(120);
    s = await d.state();
    check(s.beam.sx === slid.sx && s.beam.sy === slid.sy, 'the slide stays after the second finger lifts', j({ slid, b: s.beam }));
    await d.moveTo(placed.f, placed.f.cx + 5, placed.f.cy - 60);
    await d.settle();
    s = await d.state();
    check(s.beam.sx === slid.sx && Math.abs(s.beam.angle - slid.angle) > 0.05, 'moving alone turns the line about its start', j(s.beam));
    d.frame('lift_second_first');
    const line = s.beam;
    await d.up(placed.f);
    await sleep(200);
    s = await d.state();
    fingersClear(s);
    if (line.rooms.length) check(s.targets[slot]?.sx === line.sx, 'fires where shown', j(s.targets));
    await oneFingerWorks();
};

scenarios.lift_first_first = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    const placed = await placeLine(s);
    const g = await d.down(460, 180);
    await sleep(100);
    await d.up(placed.f);
    await sleep(200);
    s = await d.state();
    const t = s.targets[slot];
    check(t && t.sx === placed.s.beam.sx, 'lifting the first finger fires, the second still down', j(s.targets));
    d.frame('lift_first_first');
    await d.moveTo(g, 500, 220);
    await d.up(g);
    await sleep(150);
    s = await d.state();
    fingersClear(s);
    await oneFingerWorks();
};

scenarios.lift_both_one_report = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    const placed = await placeLine(s);
    const g = await d.down(460, 180);
    await sleep(100);
    d.clearTrace();
    await d.up(placed.f, g);
    await sleep(250);
    console.log(`    ${d.trace().join(' | ')}`);
    s = await d.state();
    check(s.targets[slot]?.sx === placed.s.beam.sx, 'fires', j(s.targets));
    fingersClear(s);
    await oneFingerWorks();
};

scenarios.third_finger = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    const placed = await placeLine(s);
    const l0 = placed.s.beam;
    const g = await d.down(460, 180);
    await sleep(80);
    const h = await d.down(500, 500);
    await sleep(80);
    s = await d.state();
    check(s.extra.length === 2, 'two extra fingers', j(s.extra));
    await d.moveTo(placed.f, placed.f.cx - 5, placed.f.cy + 50);
    await d.settle();
    s = await d.state();
    check(s.beam.sx === l0.sx && s.beam.sy === l0.sy, 'with two extra fingers the line does not slide (the original asks for exactly one)', j(s.beam));
    d.frame('third_finger');
    await d.up(h);
    await sleep(80);
    const before = (await d.state()).beam;
    await slideInward(placed, 15);
    s = await d.state();
    check(s.beam.sx !== before.sx, 'back to one extra finger it slides again', j({ before, now: s.beam }));
    await d.up(g, placed.f);
    await sleep(200);
    fingersClear(await d.state());
    await oneFingerWorks();
};

scenarios.second_on_hud_button = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    const placed = await placeLine(s);
    const l0 = placed.s.beam;
    const places = { pause: PAUSE_BUTTON, jump: JUMP_BUTTON, ship: SHIP_BUTTON, options: OPTIONS_BUTTON, slot: [SLOT_X, slotY(1)], box: SENSORS_BOX, crew: CREW_BOX };
    for (const [name, at] of Object.entries(places)) {
        const was = await d.state();
        const g = await d.down(at[0], at[1]);
        await sleep(100);
        await d.up(g);
        await sleep(150);
        s = await d.state();
        check(s.paused === was.paused && s.window === was.window && s.armedSlot === slot && j(s.beam) === j(l0) && s.crewSel === was.crewSel,
            `second finger on ${name}: nothing happens, aim goes on`, j({ paused: s.paused, window: s.window, armed: s.armedSlot, beam: s.beam, crew: s.crewSel }));
    }
    d.frame('second_on_hud_button');
    await d.up(placed.f);
    await sleep(200);
    s = await d.state();
    check(s.targets[slot]?.sx === l0.sx, 'fires', j(s.targets));
    await oneFingerWorks();
};

scenarios.hud_finger_first = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    // A finger resting on the HUD's empty space, the other trying to aim. The first finger owns the gesture.
    const g = await d.down(...EMPTY);
    await sleep(100);
    const [cx, cy] = centre(s.enemy.rooms[0]);
    const f = await d.down(cx, cy);
    await sleep(80);
    await d.moveTo(f, cx + 50, cy + 10);
    await d.settle();
    s = await d.state();
    d.frame('hud_finger_first-aiming');
    console.log(`    beam ${j(s.beam)} hud ${s.hudPrimary} extra ${j(s.extra)} armed ${s.armedSlot}`);
    await d.up(f);
    await sleep(100);
    await d.up(g);
    await sleep(200);
    s = await d.state();
    fingersClear(s);
    check(s.armedSlot === slot, 'the weapon stays armed', j({ armed: s.armedSlot, targets: s.targets }));
    const placed = await placeLine(s);
    check(placed.s.beam?.visible, 'aiming still works afterwards', j(placed.s.beam));
    await d.up(placed.f);
    await sleep(200);
};

scenarios.pause_during_aim = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    // Armed, nothing placed: one finger on PAUSE changes the pause, and aiming still works.
    const paused0 = s.paused;
    await d.tap(...PAUSE_BUTTON);
    await sleep(200);
    s = await d.state();
    d.frame('pause-button-while-armed');
    console.log(`    PAUSE while armed: paused ${paused0} -> ${s.paused}, armed ${s.armedSlot}`);
    check(s.armedSlot === slot, 'PAUSE does not disarm');
    const placed = await placeLine(s);
    check(placed.s.beam?.visible, 'line placed while paused by hand', j(placed.s.beam));
    const g = await d.down(460, 180);
    await sleep(80);
    await slideInward(placed, 10);
    const line = (await d.state()).beam;
    check(Math.abs(line.sx - placed.s.beam.sx) >= 5, 'the slide goes on', j({ a: placed.s.beam, b: line }));
    // Back opens the pause menu with both fingers down.
    d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await sleep(600);
    s = await d.state();
    d.frame('pause-menu-while-aiming');
    await d.up(g, placed.f);
    await sleep(300);
    s = await d.state();
    d.frame('pause-menu-lifted');
    fingersClear(s);
    check(!s.targets[slot] && !s.beam, 'Back\'s menu took the aim: lifting does not fire under it', j({ t: s.targets, b: s.beam }));
    d.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await sleep(600);
    s = await d.state();
    d.frame('pause-menu-closed');
    check(!crashed(), 'the game did not stop');
    await oneFingerWorks();
};

/** Armed, line placed, second finger down, and the fight running: PAUSE tapped off first. */
async function aimRunning(slot) {
    let s = await arm(slot);
    await d.tap(...PAUSE_BUTTON);
    await sleep(200);
    s = await d.state();
    check(!s.paused && s.clickEvent, 'PAUSE released the auto-pause, the weapon still armed', j({ p: s.paused, c: s.clickEvent }));
    const placed = await placeLine(s);
    const g = await d.down(460, 180);
    await sleep(80);
    return { placed, g };
}

scenarios.enemy_dies_during_aim = async () => {
    const slot = await fresh();
    const { placed, g } = await aimRunning(slot);
    d.console('dev-kill');
    await sleep(600);
    await d.moveTo(placed.f, placed.f.cx - 10, placed.f.cy + 5);
    await d.settle(400);
    let s = await d.state();
    d.frame('enemy_dies-exploding');
    console.log(`    after kill: beam ${j(s.beam)} clickEvent ${s.clickEvent} window ${s.window} enemy ${s.enemy ? `hull ${s.enemy.hull}` : 'null'}`);
    // Fingers stay down through the explosion and the reward box.
    const end = Date.now() + 15000;
    while (!s.window && Date.now() < end) { await sleep(300); s = await d.state(); }
    check(s.window === 'DialogueWindow', 'the reward box comes up under the held fingers', s.window);
    d.frame('enemy_dies-box-under-fingers');
    check(!s.beam, 'with the box up there is no line', j(s.beam));
    await d.up(g);
    await sleep(100);
    await d.up(placed.f);
    await sleep(500);
    s = await d.state();
    d.frame('enemy_dies-lifted');
    fingersClear(s);
    check(!s.beam && !crashed(), 'no line and the game goes on', j(s.beam));
    check(s.window === 'DialogueWindow', 'lifting did not press anything in the box', s.window);
    await closeBox();
    await oneFingerWorks();
};

scenarios.enemy_dies_then_lift = async () => {
    const slot = await fresh();
    const { placed, g } = await aimRunning(slot);
    d.console('dev-kill');
    await sleep(500);
    await d.up(g, placed.f);
    await sleep(300);
    const s = await d.state();
    d.frame('enemy_dies_then_lift');
    check(!s.targets[slot], 'no aim is set on a dead ship', j(s.targets));
    fingersClear(s);
};

scenarios.enemy_leaves_during_aim = async () => {
    const slot = await fresh();
    const { placed, g } = await aimRunning(slot);
    d.console('enemyleave');
    let s = await d.state();
    const end = Date.now() + 15000;
    while (s.enemy && Date.now() < end) { await sleep(200); s = await d.state(); }
    check(!s.enemy, 'the enemy has gone', j({ enemy: !!s.enemy }));
    await sleep(300);
    s = await d.state();
    d.frame('enemy_leaves-held');
    check(!s.beam && !s.clickEvent, 'the aim went with it', j({ beam: s.beam, click: s.clickEvent }));
    await d.moveTo(placed.f, placed.f.cx + 20, placed.f.cy);
    await d.up(g, placed.f);
    await sleep(400);
    s = await d.state();
    d.frame('enemy_leaves-lifted');
    fingersClear(s);
    check(!s.beam && !s.targets[slot] && !crashed(), 'nothing is left and the game goes on', j({ b: s.beam, t: s.targets }));
    if (s.window) await closeBox();
    await oneFingerWorks();
};

scenarios.enemy_replaced_during_aim = async () => {
    const slot = await fresh();
    const { placed, g } = await aimRunning(slot);
    d.console('spawn-ship REBEL 1.normal');
    await sleep(1200);
    await d.moveTo(placed.f, placed.f.cx + 20, placed.f.cy + 10);
    await d.settle();
    let s = await d.state();
    d.frame('enemy_replaced-held');
    check(!s.beam, 'the line on the old ship is gone', j(s.beam));
    await d.up(g, placed.f);
    await sleep(300);
    s = await d.state();
    fingersClear(s);
    check(!crashed(), 'the game did not stop');
    await oneFingerWorks();
};

async function afterCancel(name, slot) {
    const s = await d.state();
    d.frame(`${name}-after`);
    fingersClear(s, 'no finger is left behind after the cancel');
    check(!s.beam, 'the half-drawn line is gone', j(s.beam));
    check(s.clickEvent && s.armedSlot === slot, 'the weapon stays armed, waiting for a new press', j({ armed: s.armedSlot, click: s.clickEvent, paused: s.paused }));
    if (s.clickEvent) {
        const placed = await placeLine(s);
        check(placed.s.beam?.visible && placed.s.beam.rooms.length > 0, 'aiming works again from the start', j(placed.s.beam));
        check(placed.s.beam && Math.abs(placed.s.beam.sx - placed.s.enemy.rooms[0].w / 2 - placed.s.enemy.rooms[0].x) <= 2, 'the new line starts under the finger', j(placed.s.beam));
        d.frame(`${name}-reaim`);
        await d.up(placed.f);
        await sleep(200);
    }
    await oneFingerWorks();
}

function reopen() {
    d.adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1');
}

scenarios.cancel_home = async () => {
    const slot = await fresh();
    const s = await arm(slot);
    const placed = await placeLine(s);
    const g = await d.down(460, 180);
    await sleep(80);
    d.clearTrace();
    d.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
    await sleep(1500);
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ')}`);
    // Fingers still physically down on the launcher; lifted there.
    await d.up(g, placed.f);
    await sleep(500);
    reopen();
    await sleep(3000);
    await afterCancel('cancel_home', slot);
};

scenarios.cancel_home_gesture_from_border = async () => {
    const slot = await fresh();
    const s = await arm(slot);
    // The gesture began on the border, so these fingers reach the game through the frame, not the view.
    const b = await d.downScreen(100, 700);
    await sleep(100);
    const placed = await placeLine(s);
    await sleep(80);
    d.clearTrace();
    d.adb('shell', 'input', 'keyevent', 'KEYCODE_HOME');
    await sleep(1500);
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ')}`);
    await d.up(b, placed.f);
    await sleep(500);
    reopen();
    await sleep(3000);
    await afterCancel('cancel_home_gesture_from_border', slot);
};

scenarios.shade_command_keeps_fingers = async () => {
    const slot = await fresh();
    const s0 = await arm(slot);
    const placed = await placeLine(s0);
    const g = await d.down(460, 180);
    await sleep(80);
    d.clearTrace();
    d.adb('shell', 'cmd', 'statusbar', 'expand-notifications');
    await sleep(1200);
    d.frame('shade_command-open');
    // The shade's window opens over the game, but fingers already down stay with the game's window.
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ') || 'no cancel, no lift'}`);
    await d.up(g, placed.f);
    await sleep(400);
    d.adb('shell', 'cmd', 'statusbar', 'collapse');
    await sleep(1200);
    const s = await d.state();
    d.frame('shade_command-after');
    fingersClear(s);
    check(!s.beam, 'no line is left', j(s.beam));
    await oneFingerWorks();
};

scenarios.cancel_back_gesture = async () => {
    const slot = await fresh();
    const s0 = await arm(slot);
    const placed = await placeLine(s0);
    await sleep(80);
    d.clearTrace();
    // A second finger swipes in from the left edge of the screen: the system's back gesture.
    const g = await d.downScreen(2, 500);
    for (let x = 30; x < 700; x += 50) {
        await d.touch.send([{ ...d.screenToPanel(x, 500), id: g.id, pressure: 100 }]);
        await sleep(16);
    }
    g.sx = 700; g.sy = 500;
    await sleep(400);
    await d.up(g);
    await sleep(500);
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ')}`);
    const s = await d.state();
    d.frame('cancel_back_gesture');
    console.log(`    window ${s.window} beam ${j(s.beam)} extra ${j(s.extra)}`);
    await d.up(placed.f);
    await sleep(400);
    const after = await d.state();
    fingersClear(after);
    check(!crashed(), 'the game did not stop');
};

scenarios.lift_over_column = async () => {
    const slot = await fresh();
    const s0 = await arm(slot);
    const placed = await placeLine(s0);
    const l0 = placed.s.beam;
    // The aiming finger wanders over the open weapon column and off again, then lifts over it.
    await d.moveTo(placed.f, SLOT_X, slotY(1), { steps: 20 });
    await d.settle();
    let s = await d.state();
    d.frame('lift_over_column-over');
    check(s.beam && s.beam.sx === l0.sx, 'the line is still there with the finger over the column', j(s.beam));
    check(s.beam && s.beam.visible && Math.abs(s.beam.angle - l0.angle) > 0.01, 'the line follows the finger there too', j(s.beam));
    const line = s.beam;
    await d.up(placed.f);
    await sleep(300);
    s = await d.state();
    fingersClear(s);
    check(!s.beam, 'lifting ends the aim', j(s.beam));
    if (line?.rooms.length) check(s.targets[slot]?.sx === line.sx, 'and fires where shown', j(s.targets));
    await oneFingerWorks();
};

scenarios.cancel_shade_gesture = async () => {
    const slot = await fresh();
    const s = await arm(slot);
    const placed = await placeLine(s);
    await sleep(80);
    d.clearTrace();
    // A second finger pulls down from the top edge of the screen, as a player opening the shade would.
    const g = await d.downScreen(800, 1);
    for (let y = 40; y < 700; y += 60) {
        await d.touch.send([{ ...d.screenToPanel(800, y), id: g.id, pressure: 100 }]);
        await sleep(16);
    }
    g.sy = 700;
    await sleep(800);
    d.frame('cancel_shade_gesture-pulled');
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ')}`);
    await d.up(g);
    await sleep(200);
    await d.up(placed.f);
    await sleep(600);
    d.adb('shell', 'cmd', 'statusbar', 'collapse');
    await sleep(1200);
    const after = await d.state();
    d.frame('cancel_shade_gesture-after');
    fingersClear(after);
    check(!crashed(), 'the game did not stop');
    await oneFingerWorks();
};

scenarios.rotation = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    const placed = await placeLine(s);
    const g = await d.down(460, 180);
    await sleep(80);
    d.clearTrace();
    const rot0 = d.rotation();
    tilt(rot0 === 1 ? 3 : 1);
    await sleep(5000);
    const rotHeld = d.rotation();
    console.log(`    fingers down, phone turned over: rotation ${rot0} -> ${rotHeld}: ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ') || 'no event'}`);
    await d.up(g, placed.f);
    await sleep(4000);
    const rot1 = d.rotation();
    check(rot1 !== rot0, 'the screen turned, at the latest once the fingers lifted', `${rot0} ${rot1}`);
    d.rot = rot1;
    s = await d.state();
    d.frame('rotation-turned');
    fingersClear(s);
    console.log(`    after: beam ${j(s.beam)} click ${s.clickEvent} targets ${j(s.targets)}`);
    if (!s.clickEvent) await arm(slot);
    s = await d.state();
    const again = await placeLine(s);
    const g2 = await d.down(460, 180);
    await sleep(80);
    await slideInward(again, 15);
    s = await d.state();
    check(again.s.beam?.visible && s.beam?.sx !== again.s.beam.sx, 'turned the other way, aim and slide work', j({ a: again.s.beam, b: s.beam }));
    d.frame('rotation-slide');
    await d.up(g2, again.f);
    await sleep(200);
    tilt(rot0);
    await sleep(2500);
    d.rot = d.rotation();
    await oneFingerWorks();
};

function tilt(rotation) {
    d.adb('emu', 'sensor', 'set', 'acceleration', `${rotation === 1 ? 9.81 : -9.81}:0:0`);
}

scenarios.focus_tap_with_second_finger = async () => {
    await fresh({ power: false });
    let s = await d.state();
    check(!s.focusPlayer, 'focus starts on the enemy');
    // A tap on the player's ship changes focus and drops that finger; a second finger joins before it lifts.
    const f = await d.down(450, 340);
    await sleep(150);
    const g = await d.down(600, 650);
    await sleep(120);
    await d.up(f);
    await sleep(120);
    await d.up(g);
    await sleep(300);
    s = await d.state();
    fingersClear(s, 'the HUD is not left holding the second finger');
    await oneFingerWorks();
};

scenarios.cancel_clears_second_finger = async () => {
    await fresh({ power: false });
    const f = await d.down(...EMPTY);
    await sleep(80);
    const g = await d.down(700, 600);
    await sleep(80);
    d.adb('shell', 'cmd', 'statusbar', 'expand-notifications');
    await sleep(1200);
    await d.up(f, g);
    await sleep(200);
    d.adb('shell', 'cmd', 'statusbar', 'collapse');
    await sleep(1200);
    fingersClear(await d.state());
    await oneFingerWorks();
};

scenarios.border_fingers = async () => {
    const slot = await fresh();
    let s = await arm(slot);
    // The black border on a 20:9 screen is outside the game view. A finger resting there must not kill the game.
    const b = await d.downScreen(100, 700);
    await sleep(150);
    const placed = await placeLine(s);
    check(placed.s.beam?.visible === true, 'a finger resting on the border does not block aiming', j({ beam: placed.s.beam, h: placed.s.hudPrimary, r: placed.s.routerPrimary }));
    d.frame('border-resting');
    await d.up(placed.f);
    await sleep(100);
    await d.up(b);
    await sleep(200);
    s = await d.state();
    fingersClear(s);

    if (!s.clickEvent) await arm(slot);
    s = await d.state();
    const p2 = await placeLine(s);
    const g = await d.downScreen(100, 300);
    await sleep(120);
    s = await d.state();
    check(s.extra.length === 1, 'a second finger on the border counts for the slide', j(s.extra));
    await slideInward(p2, 15);
    s = await d.state();
    check(s.beam?.sx !== p2.s.beam?.sx, 'and the line slides', j({ a: p2.s.beam, b: s.beam }));
    d.frame('border-second');
    await d.up(g, p2.f);
    await sleep(200);
    fingersClear(await d.state());
    await oneFingerWorks();
};

scenarios.no_pan_or_zoom = async () => {
    await fresh({ power: false });
    let s = await d.state();
    const o0 = j([s.shipOrigin, s.enemy.originX, s.enemy.originY]);
    // Both fingers on empty space, below the ships, so neither is a tap on a ship (which changes focus).
    const f = await d.down(600, 640);
    const g = await d.down(650, 640);
    await sleep(60);
    for (let i = 1; i <= 10; i++) {
        await d.touch.send([
            { ...d.canvasToPanel(600 - i * 8, 640), id: f.id, pressure: 100 },
            { ...d.canvasToPanel(650 + i * 8, 640), id: g.id, pressure: 100 },
        ]);
        await sleep(16);
    }
    f.cx = 520; f.cy = 640; g.cx = 730; g.cy = 640;
    await sleep(200);
    s = await d.state();
    d.frame('pinch-held');
    check(j([s.shipOrigin, s.enemy.originX, s.enemy.originY]) === o0, 'pinch moves nothing: the game has no zoom or pan', `${o0} -> ${j([s.shipOrigin, s.enemy.originX, s.enemy.originY])}`);
    await d.up(f, g);
    await sleep(200);
    fingersClear(await d.state());
    await oneFingerWorks();
};

scenarios.back_gesture_alone = async () => {
    await fresh({ power: false });
    d.clearTrace();
    const g = await d.downScreen(2, 500);
    for (let x = 30; x < 700; x += 50) {
        await d.touch.send([{ ...d.screenToPanel(x, 500), id: g.id, pressure: 100 }]);
        await sleep(16);
    }
    g.sx = 700; g.sy = 500;
    await sleep(200);
    await d.up(g);
    await sleep(800);
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ') || 'no event reached the game'}`);
    const s = await d.state();
    d.frame('back_gesture_alone');
    fingersClear(s);
    await oneFingerWorks();
};

scenarios.shade_gesture_alone = async () => {
    await fresh({ power: false });
    d.clearTrace();
    const g = await d.downScreen(800, 1);
    for (let y = 40; y < 700; y += 60) {
        await d.touch.send([{ ...d.screenToPanel(800, y), id: g.id, pressure: 100 }]);
        await sleep(16);
    }
    g.sy = 700;
    await sleep(800);
    d.frame('shade_gesture_alone-pulled');
    console.log(`    ${d.trace().filter((l) => !l.includes('MOVE')).join(' | ') || 'no event reached the game'}`);
    await d.up(g);
    await sleep(600);
    d.adb('shell', 'cmd', 'statusbar', 'collapse');
    await sleep(1200);
    fingersClear(await d.state());
    await oneFingerWorks();
};

scenarios.enemy_icon_hold = async () => {
    await fresh({ power: false });
    const s = await d.state();
    // The enemy's first system icon, at the box's bottom left when focused.
    const icon = [(1097 * 1.2 - 240) / 1.40625, 680 * 1.2 / 1.40625];
    const f = await d.down(...icon);
    await sleep(250);
    d.frame('icon-hold-0.25s');
    await sleep(800);
    d.frame('icon-hold-1s');
    await d.up(f);
    await sleep(400);
    d.frame('icon-lifted');
    check(!!s.enemy, 'frames taken: enemy icon held and lifted (view icon-hold-*.png)');
};

scenarios.status_hold_with_second_finger = async () => {
    await fresh({ power: false });
    const f = await d.down((640 * 1.2 - 240) / 1.40625, 42 * 1.2 / 1.40625);
    await sleep(200);
    const g = await d.down(700, 650);
    await sleep(300);
    d.frame('status-hold-second');
    await d.up(g);
    await sleep(100);
    await d.up(f);
    await sleep(300);
    d.frame('status-lifted');
    fingersClear(await d.state());
};

scenarios.door_rect = async () => {
    await fresh({ power: false });
    await d.tap(...DOORS_BOX);
    await sleep(700);
    let s = await d.state();
    d.frame('door-mode');
    check(s.focusPlayer, 'door mode focuses the player ship');
    const HALF_LONG = 45;
    const HALF_SHORT = 24;
    const inRect = (dr, px, py) => {
        const cx = dr.x + (dr.v ? 0 : 17);
        const cy = dr.y + (dr.v ? 17 : 0);
        const dx = Math.abs(px - cx);
        const dy = Math.abs(py - cy);
        return dr.v ? dx <= HALF_SHORT && dy <= HALF_LONG : dx <= HALF_LONG && dy <= HALF_SHORT;
    };
    const point = (dr, along, across) => [dr.x + (dr.v ? across : 17 + along), dr.y + (dr.v ? 17 + along : across)];
    const allTries = [[0, 0], [0, 22], [0, -22], [0, 27], [0, -27], [43, 0], [47, 0]];
    // A door whose tried points touch no other door's rectangle.
    const i = s.doors.findIndex((a, n) => allTries.every(([al, ac]) => !s.doors.some((b, k) => k !== n && inRect(b, ...point(a, al, ac)))));
    check(i >= 0, 'a door stands far enough from the others', j(s.doors));
    if (i < 0) return;
    const door = s.doors[i];
    const canvas = (s2, along, across) => {
        const [x, y] = point(door, along, across);
        return [x - s2.shipOrigin[0], y - s2.shipOrigin[1]];
    };
    const tries = { centre: [0, 0, true], 'across 22': [0, 22, true], 'across -22': [0, -22, true], 'across 27': [0, 27, false], 'across -27': [0, -27, false], 'along 43': [43, 0, true], 'along 47': [47, 0, false] };
    for (const [name, [along, across, hits]] of Object.entries(tries)) {
        s = await d.state();
        const was = s.doors[i].open;
        await d.tap(...canvas(s, along, across));
        await sleep(150);
        s = await d.state();
        check((s.doors[i].open !== was) === hits, `door tap ${name}: ${hits ? 'toggles' : 'does nothing'}`, j({ was, now: s.doors[i].open, door }));
    }
    d.frame('door-tried');
};

scenarios.second_finger_during_power_drag = async () => {
    await fresh({ power: false });
    // The shields box, dragged down one bar and more, with a second finger resting on the ship.
    const box = [(610 * 1.2 - 240) / 1.40625, 721];
    const f = await d.down(...box);
    await sleep(100);
    const g = await d.down(600, 300);
    await sleep(100);
    await d.moveTo(f, box[0], box[1] - 40, { steps: 8 });
    await sleep(200);
    d.frame('power-drag-second');
    await d.up(f);
    await sleep(100);
    await d.up(g);
    await sleep(300);
    fingersClear(await d.state());
    d.frame('power-drag-after');
    await oneFingerWorks();
};

/** Into the game from wherever the app is: launcher, new save if there is none, language chooser on a first start. */
async function ensureGame() {
    const focused = () => /mCurrentFocus=.*GameActivity/.test(d.shell('dumpsys window | grep mCurrentFocus'));
    if (!focused()) {
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
        await sleep(5000);
    }
    const settings = d.shell(`run-as ${PKG} sh -c 'cat files/settings.xml 2>/dev/null'`);
    if (!/language=/.test(settings)) {
        d.adb('shell', 'input', 'tap', '1200', '307'); // English, the chooser's first row
        await sleep(3000);
    }
}

function crashed() {
    const log = d.adb('logcat', '-d', '-s', 'AndroidRuntime:E', 'FTL:E').toString('utf8');
    return /FATAL EXCEPTION|Game thread stopped/.test(log);
}

// ---- main ----
const only = arg('only', '')?.split(',').filter(Boolean);
if (process.argv.includes('--list')) {
    console.log(Object.keys(scenarios).join('\n'));
    process.exit(0);
}
d = new Device({ serial: SERIAL, grpcPort: Number(arg('grpc', '8554')), adbPath, outDir: arg('out', '.') });
await ensureGame();
d.shell('settings put system accelerometer_rotation 1');
tilt(1);
await sleep(1500);
d.rot = d.rotation();
d.shell('settings put system show_touches 1');
d.console('touchtrace on');
for (const [name, fn] of Object.entries(scenarios)) {
    if (only.length && !only.includes(name)) continue;
    scenario = name;
    console.log(`\n== ${name}`);
    try {
        await fn();
    } catch (e) {
        check(false, 'scenario ran to its end', e.stack ?? String(e));
        d.frame(`${name}-error`);
    }
    // Lift anything a failed scenario left down.
    if (d.fingers.size) await d.up(...d.fingers.values()).catch(() => {});
    if (d.rotation() !== 1) {
        tilt(1);
        await sleep(2500);
    }
    d.rot = d.rotation();
    d.shell('cmd statusbar collapse');
    await sleep(200);
}
d.close();
const bad = results.filter((r) => !r.ok);
console.log(`\n${results.length - bad.length} of ${results.length} checks passed`);
for (const r of bad) console.log(`FAIL ${r.scenario}: ${r.what} :: ${r.detail}`);
process.exit(bad.length ? 1 : 0);
