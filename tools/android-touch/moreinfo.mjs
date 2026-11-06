// Hyperspace's More Info button (Multiverse's magnifier) on the touch layout, on a running emulator with one finger
// (adb input, no gRPC needed). See docs/TESTS.md.
//   node tools/android-touch/moreinfo.mjs --serial emulator-5570 --out <dir> [--shape phone|tablet]
// Needs the debug build in flight on a Hyperspace save with crew aboard, no window open and no enemy ship.
// Checks: the button is placed in the dock and a tap toggles the mode, which the options follow (CommandGui::OnLoop);
// over an open window (the ship screen) it is drawn over the window and a tap still toggles it, the window getting
// nothing; under the pause menu it takes no tap (as the desktop's, whose hover the menu blocks);
// a finger held still on a crew member of the player shows the crew tooltip (on: the race's powers as "*" lines; off: none
// unless hyperspace.xml asks for them), the same for an enemy boarder; lifting hides it; each weapon slot's info drawer
// has the weapon's tooltip in both modes; the weapons column shows the cooldown numbers when the option is on.
// Frames <out>/<shape>-*.png.
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-moreinfo') });
const shape = arg('shape', 'phone');

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

/** Canvas units to landscape screen pixels (the picture fills the height, centred). */
// `wm size <w>x<h>` (the tablet shape) gives an override size, which input events use.
const wm = /Override size: (\d+)x(\d+)/.exec(d.shell('wm size'));
const size = wm ? { panelW: Number(wm[1]), panelH: Number(wm[2]) } : d.size;
function screen(s, cx, cy) {
    const { panelW, panelH } = size;
    const scale = Math.min(panelW, panelH) / s.canvasH;
    const long = Math.max(panelW, panelH);
    return [Math.round((long - s.canvasW * scale) / 2 + cx * scale), Math.round(cy * scale)];
}
const tap = (s, cx, cy) => d.shell(`input tap ${screen(s, cx, cy).join(' ')}`);
const down = (s, cx, cy) => d.shell(`input motionevent DOWN ${screen(s, cx, cy).join(' ')}`);
// The checks end a hold with CANCEL: a lift is a tap (selects the crew member, or moves the selected crew there).
const cancel = (s, cx, cy) => d.shell(`input motionevent CANCEL ${screen(s, cx, cy).join(' ')}`);

const piece = (s, key) => (s.extras || []).find((p) => p.key === key);
const centre = (p) => [(p.x0 + p.x1) / 2, (p.y0 + p.y1) / 2];
const powers = (t) => t.split('\n').filter((l) => l.startsWith('*')).length;

async function until(what, f, ms = 8000) {
    const end = Date.now() + ms;
    for (;;) {
        const s = await d.state();
        if (f(s)) return s;
        if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
        await sleep(250);
    }
}

/** A finger held still on [c] for a second: what the HUD shows then, a frame, and the state after the lift. */
async function hold(s, c, name) {
    down(s, c.x, c.y);
    await sleep(1100);
    const held = await d.state();
    d.frame(`${shape}-${name}`);
    cancel(s, c.x, c.y);
    await sleep(400);
    const after = await d.state();
    return { text: held.crewHold, after: after.crewHold };
}

let s = await d.state();
if (!s.flight || !s.hsActive || s.window || s.enemy) throw new Error('needs the game in flight on a Hyperspace save, no window open, no enemy');

// ---- the button -------------------------------------------------------------------------------------------------
console.log(`More Info button (${shape}, canvas ${s.canvasW}x${s.canvasH})`);
const info = piece(s, 'more-info');
check(info?.placed && info.zone === 'DOCK', 'the button is drawn in the dock above Pause', JSON.stringify(info));
check(info && info.x1 - info.x0 >= 40 && info.y1 - info.y0 >= 40, 'it is a finger wide');
if (!info?.placed) process.exit(1);
async function setMode(on) {
    if (s.moreInfo !== on) {
        tap(s, ...centre(info));
        s = await until(`More Info ${on ? 'on' : 'off'}`, (x) => x.moreInfo === on).catch(() => s);
    }
    s = await until('the options follow', (x) => x.moreInfoApplied === x.moreInfo).catch(() => s);
    check(s.moreInfo === on && s.moreInfoApplied === on, `More Info is ${on ? 'on' : 'off'} and the options follow it`);
}
const start = s.moreInfo;
await setMode(!start);
d.frame(`${shape}-button-${start ? 'off' : 'on'}`);
await setMode(start);
d.frame(`${shape}-button-${start ? 'on' : 'off'}`);

// ---- over an open window, and under the pause menu ----------------------------------------------------------------
const snapWindow = (x) => x.snapshot?.window;
// An injected key brings up the emulator's on-screen keyboard (no hardware keyboard), which covers the dock: Back hides
// it (the game gets that Back only when no keyboard shows).
async function key(code) {
    d.shell(`input keyevent ${code}`);
    await sleep(800);
    if (/mInputShown=true/.test(d.shell('dumpsys input_method'))) { d.shell('input keyevent KEYCODE_BACK'); await sleep(800); }
}
await key('KEYCODE_U');
s = await until('the ship screen', (x) => snapWindow(x) === 'ShipWindow').catch(() => s);
if (check(snapWindow(s) === 'ShipWindow', 'U opens the ship screen', snapWindow(s))) {
    const p = piece(s, 'more-info');
    check(p?.placed, 'the button keeps its place with the ship screen open', JSON.stringify(p));
    tap(s, ...centre(p));
    s = await until('More Info toggled over the ship screen', (x) => x.moreInfo !== start).catch(() => s);
    d.frame(`${shape}-over-window`);
    check(s.moreInfo !== start, 'tapped over the ship screen, More Info toggles');
    await sleep(500);
    s = await d.state();
    check(snapWindow(s) === 'ShipWindow', "the ship screen stays open: the tap was the button's alone", snapWindow(s));
    tap(s, ...centre(p));
    s = await until('More Info back', (x) => x.moreInfo === start).catch(() => s);
    check(s.moreInfo === start, 'tapped again, it toggles back');
    await key('KEYCODE_ESCAPE');
    s = await until('the ship screen closed', (x) => !snapWindow(x) || snapWindow(x) === 'game').catch(() => s);
}
await key('KEYCODE_ESCAPE');
s = await until('the pause menu', (x) => snapWindow(x) === 'PauseWindow').catch(() => s);
if (check(snapWindow(s) === 'PauseWindow', 'Back opens the pause menu', snapWindow(s))) {
    tap(s, ...centre(info));
    await sleep(1000);
    s = await d.state();
    d.frame(`${shape}-under-pause`);
    check(s.moreInfo === start && snapWindow(s) === 'PauseWindow', 'under the pause menu a tap there toggles nothing', `${s.moreInfo} ${snapWindow(s)}`);
    await key('KEYCODE_ESCAPE');
    s = await until('the pause menu closed', (x) => snapWindow(x) !== 'PauseWindow').catch(() => s);
}

// ---- crew tooltips by holding a finger ----------------------------------------------------------------------------
const ally = s.crew.find((c) => c.owner === 0 && c.seen);
check(!!ally, 'a crew member of the player is aboard', JSON.stringify(s.crew));
function killBoarders() {
    d.console('hscmd LUA for i = 0, Hyperspace.ships.player.vCrewList:size() - 1 do local c = Hyperspace.ships.player.vCrewList[i]; if c.iShipId == 1 then c.health.first = 0 end end');
}
// Boarders an earlier run left die first.
killBoarders();
s = await until('no boarder', (x) => !x.crew.some((c) => c.owner === 1), 15000).catch(() => s);
// An enemy boarder (an intruder: owner 1) in a room no crew member is in (the last such room).
d.console('hscmd LUA local sm = Hyperspace.ships.player; local busy = {}; for i = 0, sm.vCrewList:size() - 1 do busy[sm.vCrewList[i].currentSlot.roomId] = true end; ' +
    'for r = sm.ship.vRoomList:size() - 1, 0, -1 do if not busy[r] then sm:AddCrewMemberFromString("Boarder", "mantis", true, r, true, true); break end end');
s = await until('a boarder', (x) => x.crew.some((c) => c.owner === 1)).catch(() => s);
const result = {};
for (const on of [true, false]) {
    await setMode(on);
    const mode = on ? 'on' : 'off';
    s = await d.state();
    // The player's crew member furthest from the boarder (their squares must not overlap).
    const bo = s.crew.find((c) => c.owner === 1);
    const dist = (c) => (bo ? Math.hypot(c.x - bo.x, c.y - bo.y) : 0);
    const a = s.crew.filter((c) => c.owner === 0 && c.seen).sort((p, q) => dist(q) - dist(p))[0];
    if (a) {
        const r = await hold(s, a, `crew-ally-${mode}`);
        check(r.text.startsWith(`-${a.name} (`) && !r.text.includes('-Boarder ('), `held on ${a.name} (${mode}): its crew tooltip shows`, JSON.stringify(r.text));
        check(r.after === '', 'the finger gone: it goes away', JSON.stringify(r.after));
        result[`ally-${mode}`] = r.text;
    }
    s = await d.state();
    const b = s.crew.find((c) => c.owner === 1);
    if (check(!!b, 'the boarder is aboard', JSON.stringify(s.crew))) {
        const r = await hold(s, b, `crew-enemy-${mode}`);
        check(r.text.startsWith('-Boarder ('), `held on the boarder (${mode}): its tooltip shows`, JSON.stringify(r.text));
        result[`enemy-${mode}`] = r.text;
    }
}
check(powers(result['ally-on'] ?? '') > 0 && powers(result['enemy-on'] ?? '') > 0, 'on: both tooltips list the race powers',
    JSON.stringify([result['ally-on'], result['enemy-on']]));
// hyperspace.xml's advancedCrewTooltips ally / enemy attributes decide the off state (both false in Multiverse).
console.log(`  off: ally powers ${powers(result['ally-off'] ?? '')}, enemy powers ${powers(result['enemy-off'] ?? '')}`);
check(powers(result['ally-off'] ?? '') < powers(result['ally-on'] ?? ''), 'off: fewer lines for the ally than on (Multiverse: none)');
killBoarders();

// ---- weapons: the info drawers and the cooldown numbers -----------------------------------------------------------
await setMode(true);
d.console('touchweapons');
await sleep(800);
const tipsOn = (await d.state()).weaponTips;
d.frame(`${shape}-weapons-on`);
check(tipsOn.some((t) => t && t.length > 20), 'the weapon slots have their tooltip in the info drawer', JSON.stringify(tipsOn));
check((await d.state()).cooldownNumbers === true, 'on: the cooldown numbers show over the weapon slots');
await setMode(false);
await sleep(500);
s = await d.state();
d.frame(`${shape}-weapons-off`);
result.weaponsOn = tipsOn;
result.weaponsOff = s.weaponTips;
console.log(`  off: weapon tooltips ${JSON.stringify(s.weaponTips) === JSON.stringify(tipsOn) ? 'unchanged' : 'changed'}, cooldown numbers ${s.cooldownNumbers}`);
await setMode(start);
console.log(JSON.stringify(result));
console.log(failed ? `FAILED ${failed}` : 'PASS all');
process.exit(failed ? 1 : 0);
