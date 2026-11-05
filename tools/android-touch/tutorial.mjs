// A Lua tutorial's pointers on the touch HUD (engine TouchPointers), on a running emulator (adb only, no gRPC). See
// docs/TESTS.md.
//   node tools/android-touch/tutorial.mjs --serial emulator-5570 --out <dir> [--fight]
// Needs the debug build in Multiverse 5.5.1's tutorial (main menu, Tutorial), in flight with no window open. Each lesson
// that draws a red arrow or outline is started through the debug console (the script's own TutorialLuaManager), and every
// pointer it draws must have been moved to the touch place of the HUD element it marks on the desktop: an arrow's tip
// within TouchPointers.GAP of the element, an outline around it. A screenshot of each lesson goes to --out.
// --fight also checks the fight lesson's outline on the target box's icons (needs a hostile ship).
import { Device, sleep } from './device.mjs';

const arg = (name, dflt) => {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : dflt;
};
const adbPath = process.env.ANDROID_HOME
    ? `${process.env.ANDROID_HOME}/platform-tools/adb`
    : `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const d = new Device({ serial: arg('serial', 'emulator-5554'), grpcPort: 0, adbPath, outDir: arg('out', '.build/android-tutorial') });

let failed = 0;
function check(ok, what, detail = '') {
    if (!ok) failed++;
    console.log(`  ${ok ? 'PASS' : 'FAIL'} ${what}${ok || !detail ? '' : ` :: ${detail}`}`);
    return ok;
}

// TouchPointers.GAP, and half a canvas pixel for the rounding of the touch state.
const GAP = 10.5;

// The lessons with pointers (data/mv_scripts/tutorial.lua): what each draws, and which element of the touch HUD it marks.
const lessons = [
    { name: 'tutorial_crew_power', want: [['arrow', 'crew:0'], ['rect', 'crew:0']] },
    { name: 'tutorial_toggle_1', want: [['arrow', 'piece:event:UI_TOGGLE_BUTTON'], ['rect', 'piece:event:UI_TOGGLE_BUTTON']] },
    { name: 'tutorial_info_button', want: [['arrow', 'piece:more-info'], ['rect', 'piece:more-info']] },
    { name: 'tutorial_crew_order', want: [['arrow', 'crew:0'], ['rect', 'crew:0+crew:1+crew:2']] },
    { name: 'tutorial_tile_control', want: [['arrow', 'ship:player'], ...Array(4).fill(['rect', 'ship:player'])] },
    { name: 'tutorial_storage_initial', want: [['arrow', 'piece:event:UI_STORAGE_CHECK_BUTTON'], ['rect', 'piece:event:UI_STORAGE_CHECK_BUTTON']] },
    { name: 'tutorial_fight_start', want: [['arrow', 'button:jump']] },
    { name: 'tutorial_map_open', want: [['arrow', 'button:jump']] },
];
if (process.argv.includes('--fight')) lessons.push({ name: 'tutorial_fight_icon', want: [['arrow', 'target'], ['rect', 'target']] });

/** Distance from (x, y) to the rectangle r (0 inside). */
const dist = (x, y, r) => Math.hypot(Math.max(r[0] - x, 0, x - r[2]), Math.max(r[1] - y, 0, y - r[3]));

async function pointersOf(want) {
    // A pointer is listed from the frame after the lesson draws it.
    const end = Date.now() + 6000;
    for (;;) {
        const s = await d.state();
        if ((s.pointers || []).length >= want.length || Date.now() > end) return s;
        await sleep(300);
    }
}

let s = await d.state();
if (!s.flight || !s.hsActive) throw new Error('needs the game in flight in the Multiverse tutorial');
for (const lesson of lessons) {
    console.log(lesson.name);
    d.console(`hscmd LUA mods.multiverse.TutorialLuaManager.new():start_event("${lesson.name}")`);
    await sleep(1500);
    s = await pointersOf(lesson.want);
    d.frame(lesson.name);
    const got = s.pointers || [];
    check(got.length === lesson.want.length, `${lesson.want.length} pointer(s) moved to the touch HUD`, JSON.stringify(got));
    lesson.want.forEach(([kind, anchor], i) => {
        const p = got[i];
        if (!check(p && p.kind === kind && p.anchor.startsWith(anchor), `${kind} ${i} marks ${anchor}`, JSON.stringify(p))) return;
        if (kind === 'arrow') {
            check(dist(p.at[0], p.at[1], p.anchorAt) <= GAP, `its tip is within ${GAP} of the element's touch place`, JSON.stringify(p));
        } else {
            const cx = (p.at[0] + p.at[2]) / 2, cy = (p.at[1] + p.at[3]) / 2;
            check(dist(cx, cy, p.anchorAt) === 0 && p.at[0] < p.at[2] && p.at[1] < p.at[3], "it is drawn on the element's touch place", JSON.stringify(p));
        }
    });
}
console.log(failed ? `FAILED ${failed}` : 'OK');
process.exit(failed ? 1 : 0);
