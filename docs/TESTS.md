# Launcher tests and benchmarks

For a busy development machine, `LAUNCHER_SKIP_FPS_CHECK=1` explicitly defers the launcher's measured
FPS/throughput acceptance. The runner prints `SKIP`, while still checking benchmark completion, the selected
limit, callback accounting, cancellation and save preservation. Default runs retain the original FPS bounds.
Record this waiver separately from passing functional checks; rerun without the flag for performance acceptance.

The Tests page stores verified boot resources and requested audio in a private,
origin-local browser cache, reused across suites, runs and page reloads. Each engine run
first retrieves a fresh authorized manifest; cached resources do not enable an
offline launch or bypass login. The manifest's `bootHash` identifies the actual
bundle (including engine resources), separately from DAT/save compatibility.
Obsolete resources are pruned and audio downloads stay lazy. Browser storage
failures fall back to network loading. Loading remains outside benchmark timing.
Normal game launch is unchanged. Disposable automated browser jobs retain fresh
profiles and do not share this cache between jobs. Clear site data in the browser
to remove the resource cache manually.

`node tests/resource-cache.mjs` verifies invalidation, retries, integrity, storage
failures and independent audio buffers. The packaged menu group additionally
runs `resource-cache-ui.mjs` in Chrome, Edge and Firefox: HTTP caching is disabled
to prove Cache Storage reuse across real suites, benchmark runs and reloads, with
unchanged cloud progress and a real logout check. `MENU_BROWSERS=Firefox` (or
`Chrome`/`Edge`) also selects a targeted cache UI check on a disposable fixture.
Packaged Firefox cache checks use a fixed temporary quota so the fixture's small
tmpfs can exercise bundle reuse. The physical filesystem limit still applies;
storage-failure fallback has separate coverage. This preference affects only the
disposable automated browser, not visitors' browser settings.

Sign in, open **Tests** in the website navigation or **Tests & benchmarks** beside
Launch FTL. The page is included in the normal Docker application, including
Production. A disabled account or one awaiting password rotation cannot run it.

External users must choose a matching `ftl.dat` in **Local game files** before
running engine checks or benchmarks. Until then, the preview shows an upload
prompt and engine Run controls are disabled. Browser modules and **Check browser
rate** remain available without game files. A cached matching file restores engine
controls; removing, replacing or losing it updates the requirement. Failed startup
shows the error in the preview instead of leaving a Loading message behind.

`tests/diagnostics-assets-ui.mjs` covers these states in Chrome, Edge and Firefox
and is included in the packaged Menus group. Cached readiness/eviction uses small
metadata fixtures; set `FTL_DAT_PATH` to a private matching file for additional
actual import/reload/removal checks. Tests never upload game files to the server.
Only administrators see or request server integration jobs. Normal and External
user accounts receive browser tests and benchmarks; **All tests** means all
checks available to that account. External users choose a matching local DAT
and use the browser asset provider described in [website and accounts](WEBSITE.md).
They do not request the private server manifest, boot bundle or audio.

The packaged Security group runs deterministic .NET session/export/proxy/XML/path,
profile-merge and save-migration regressions and HTTP cookie/save/lock tests against
fresh disposable fixtures (`saves-v2.mjs` covers the save API; its fixture lowers the
account storage limit with `FTL_SAVE_BYTES_LIMIT`, honoured only in Development).
See [security hardening](SECURITY_HARDENING.md) for the tested behavior and limits.
Server-job starts allow five requests per account per five minutes; the existing
single active job limit also applies.

- **Quick checks** selects ten focused engine probes, the seed-35 combat/map
  regression, original audio playback and the shared browser module tests.
- **All tests** selects every engine matrix and, for administrators, the packaged
  server/browser integration checks. Accelerated campaign fixtures use invulnerability and
  shortcuts; they do not establish legitimate victories or original-game parity.
- **Benchmark** measures three flagship phases in the current browser on your
  PC. Choose 5, 10 or 30 seconds per phase, plus a drawing limit or Unlimited.
  Each phase warms up for two seconds. The choice affects only the temporary
  benchmark profile. Keep the page visible.

The page's engine runs stand for the reference captures' setup (`window.ftlTestRuns`, read by `LanguageJs`): without
it a player's browser, which has no `navigator.webdriver`, opened the first-start language list instead of the main menu,
so on 2.0.1 **Menu & jump warnings** stopped at once on a cast (its error had no message, shown as `Running…`) and
**Tutorial isolation** failed its main-menu check (`BrowserTutorialDiagnostics.kt:110`). Playwright sets
`navigator.webdriver`, so the packaged runs never saw it; `tests/diagnostics-ui.mjs` now hides it. A case's error is the
exception's message or, without one, its text; both probes name the state they found.

Between two categories the page shows its loading screen over the preview for one second before the next engine starts
(not between the cases of a category, such as encounters). The results pane and the case details are resized by their
bottom right corner; the case list keeps at least 8rem (about four rows) when the details are open.

- **Browser modules** also run the save sync rules (`web/self-tests-saves.mjs`, shared with `tests/saves-client.mjs`:
  the page's own `savecore.js` over an in-memory device store and account), the mod merge against ftlman's answers and
  the ftlman Lua patch scripts (`web/self-tests-mods.mjs`, shared with `tests/mods-merge.mjs`; the fixtures are served
  as `/web/test-vectors/mods/`). Nothing touches the browser's saves, account, mod library or storage.
- **Hyperspace core / gameplay** and **Multiverse core, weapons & drones, systems, crew, hazards, Lua, gameplay** run when
  this browser's mod library holds the catalogue preset's files (Hyperspace 1.23.2; Hyperspace plus both Multiverse
  files), found by SHA-256; nothing is downloaded or added. Without them they are disabled with a note and left out of
  All tests. The merge is the Play page's (`prepareModdedLaunch`, its cache included). Core is `hsCoreProbe` (with
  Multiverse also its `hyperspace.xml` values), weapons/systems/crew/hazards the parts of `tests/hs-weapons.mjs`,
  `hs-systems.mjs`, `hs-crewui.mjs` and `hs-wp1.mjs` that need nothing but the mod files, Lua `hsLuaProbe`, and gameplay
  the vanilla probes again on the mod's data, each on a fresh engine (the packaged `p-F2-*`/`p-F3-*` modes). In a
  packaged fixture the preloaded mod session (`mod-session-preload.mjs`) stands for the library.
  Each engine the page starts gets its own Lua runtime (`hs-lua.mjs` keeps one per page, bound to the engine it was
  made with): reusing it sent a later engine's scripts into the stopped one, which failed the Lua parts and kept every
  earlier engine in memory until Multiverse core stalled the tab. Multiverse core checks the profile and the mod set, not
  the run round trip, which holds the page for minutes on Multiverse (`tests/hs-core.mjs` with `HS_EXPECT=mv` has it).

Use the checkboxes for a custom selection. Run starts the selection; Stop cancels
between synchronous engine cases and immediately interrupts an active benchmark
or server job. The desktop workspace has separate configuration, engine preview
and result panes. Cases, Benchmark and Activity tabs own their scrolling; completed
cases do not move the preview. Needs attention shows running/failed/stopped cases;
All cases and Passed reveal successful checks. Select a case to inspect its full
output. Activity has phase markers and optional follow scrolling. Display output is
bounded; the available full case/server reports remain exportable.
Export report downloads JSON containing build/browser information,
case outputs, starting engine snapshots/RNG identifiers, benchmark samples and
the available server report. A stopped or failed run is labelled explicitly.
Benchmark tables show the selected drawing limit, game FPS and independently
sampled browser callbacks/s. **Check browser rate** runs a lightweight 3-second
callback measurement without the engine; Stop and hiding the tab cancel it.
Repeated timing samples are included in exports. See [performance](PERFORMANCE.md)
for interpreting refresh limits and the Firefox/Windows investigation.

Local engine probes execute synchronously on the browser's main thread. The
runner yields between cases, but a long case blocks page rendering and input,
including Stop, until it returns. This architecture predates the workspace
redesign; it does not indicate that all CPU cores are occupied. For example,
the recorded Quick run took about 5.5 seconds for Hangar & AE and 4.9 seconds for
Achievements. Keeping the page responsive throughout these probes remains open.
Benchmarks yield through animation frames, and packaged integration suites run
in separate processes while this page polls their progress asynchronously.

`tests/diagnostics-ui.mjs` is included in the normal packaged menus group. It runs
137 actual encounters in each desktop browser, checks unchanged preview/page bounds
during execution, result filters/details, keyboard tabs, bounded activity, error
visibility, JSON exports, Stop/visibility cancellation and 320px layouts. Its
deliberate server failure uses intercepted fixture responses; ordinary server
security/cancellation/reload tests remain in `tests/launcher.mjs`. Firefox's
Unlimited flagship phases compare actual drawings with supplied callbacks.
These checks do not assert input responsiveness within a long synchronous probe.

Tests create temporary engine runs and profiles with a repeatable scenario seed.
They do not acquire a play lease or write account saves. Opening Tests from an
active flight first pauses and synchronizes that flight. A blocked save prevents
navigation until recovery. Original game saves are not involved.

The Run statistics check covers native score/history restoration and five crew
records: completed repairs, combat kills, piloted evasions, survived jumps and
first-earned skill milestones. It exercises actual repair/combat actions,
ownership, ties/renames, cloning/retraining, full counter XML restoration,
unknown older history and profile idempotence. Run/lifetime cases include initial
and recruited crew, actual clone-queue completion after restoration, teleporting,
enemy/intruder exclusions, unique visits, abandoned/new/completed runs, four
record maxima, sector persistence and five overall/four layout high scores.
Ordinary breach/fire completion does not gain a system repair credit. All nine
original crew portrait variants render without changing progression or RNG.
Mixed-mode checks show AE-only scores and unlocked layouts after choosing vanilla
without changing that preference or gameplay content. Browser menu checks use
populated panels, independent score/statistics tabs and a nested ship list;
visible Back/Close preserves fullscreen and navigation leaves progression intact.
These are focused fixtures, not exhaustive native presentation, action or timing
verification. Set `MENU_BROWSERS=Chrome`, `Edge` or `Firefox` for a targeted menu
check; the default includes all three. Failure output captures engine/page status
and a local screenshot.

The Achievements check includes destruction predicates, per-weapon volley counts,
pending-shot/mid-burst/active-beam save restoration, self-targeted bombs and
encounter resets. It also covers failed dodge streaks, single-cloak damage,
strictly timed beam room coverage, single-beam crew kills, Crystal shard impacts
and invasion victories. Crew cases cover the native boarding goal/sabotage
boundary, pawn identity and power toggles, arrival hull/crew/death snapshots,
clones, drone deaths, pending death animations, burning combat/fire kills and
home/family/unknown-history exclusions. Positive/negative and saved-history cases
include the zero-time projectile restoration regression. `tests/baseline.mjs` can run focused isolated checks with
`BASELINE_ORIGIN=http://localhost:8084`, `BASELINE_MODES=achievements` and
`BASELINE_BROWSER=chromium`, `chrome`, `edge` or `firefox`. Chromium is the
default; Chrome and Edge use their installed channels. These fixture setups
do not count as legitimate played victories.

**Tutorial data** loads all 33 original PC steps from the private DAT, their
localized instructions, 14 manual continuation rules and 19 action gates, and
the PC/shared highlight arrows. It excludes iPad-only steps/arrows and verifies
that loading the script changes neither ordinary run/profile/mode nor simulation
RNG. The check is available individually and in All tests; its exported result
explicitly distinguishes data coverage from native presentation/human acceptance. It verifies
data coverage, not playing the tutorial or isolating a live tutorial simulation.

**Tutorial isolation** uses temporary tutorial simulations in both ordinary AE
modes. Existing Continue saves survive save/delete/quit attempts, temporary
earnings, settings changes, combat restoration and restart. Profile identity,
normal Games played, RNG and AE mode are restored; repeated exit, missing
blueprint initialization and shutdown exercise cleanup. Held input is released
on exit. The test is separate from Tutorial data, included in All tests and the
packaged engine runner. Instruction flow is tested separately; native presentation/human acceptance remains.

**Tutorial gates** traverses the controller's 33 states using native reference
conditions: fifteen automatic gates, four conditions that latch Continue, and
fourteen initially manual steps. Wrong actions and premature Continue are
rejected; equipment/upgrade acceptance and single completion are checked. It
does not exercise a gameplay adapter or instruction UI.

**Tutorial actions** checks the validated live gameplay adapter.
It now covers native map/ship opening permissions across all 33 steps, premature
hotkeys, actual toolbar clicks, disabled-button hit consumption and opening at
the permitted lessons. The instruction fixture opens windows before advancing
to their explanations, preserving native restrictions on already-open windows.
It uses normal crew orders/repair, airlock opening/fire extinction, map and ship
windows, explicit pause, actual weapon firing, combat restoration and pending
upgrade/Undo actions. It checks wrong actions, cargo versus equipped weapons,
modal/console focus and Accept/Escape restrictions, while protecting ordinary
profile/run/RNG. Fixture setup supplies encounters/items; original setup and
instruction rendering are not covered. It is included in All tests and the
normal packaged engine runner. Focused Chrome/Edge/Firefox checks and affected
broad engine regressions, 24 launcher checks and all seven packaged groups passed
for its deployed adapter release, preserving account/progress/key hashes.

**Tutorial instructions** renders all 33 panels and original arrows with explicit
controller observations. It checks Continue/Exit, modal priority, captured pointer
press/drag/release across combat reload, tutorial tab locking, tooltip/cursor
priority and unchanged simulation/RNG. Exit/completion cannot publish a save in
the tutorial frame; pending ordinary changes still save on the next normal frame.
The normal build includes the check individually and in All tests. The native triangular 0.75-second arrow/power pulse is covered, including
slot selection, missing-slot safety and render/RNG behavior. Exact presentation,
native frame-edge pulse timing and human play remain unverified. Instructions now
use the native 11Bold/12px baseline/20px line-spacing mapping. Actual private text
for every panel is checked against wrapping width308 and the separate Exit row.
Continue has the native170×32 hitbox, separate glow and HL2 font mapping. Browser
menu tests run overlay/Exit and the normal-mechanics traversal in fullscreen,
checking fullscreen retention and unchanged ordinary progress in all browsers.

**Tutorial setup** tests the four confirmed non-event entry effects in the normal
build, individually and in All tests. Engine power drops on entering its lesson;
engine-room damage includes the native hull/system/crew contribution; localized
male/female humans start in room 1 without career recruitment; fire attempts use
random tiles with replacement. The check uses actual crew repair and airlock
venting. Repeated updates, replacement saves, restart, unsupported IDs and stale/
ordinary simulations cover idempotence and isolation. Exact save comparisons
settle the same zero-time ship update that loading performs. Encounter/event setup has its own check below. Full human/native presentation
acceptance remains pending.

**Tutorial encounters** checks the native starting/filler events, an actual map
jump/arrival, once-only missile delivery deferred behind other windows, ammunition
replenishment, both pirate reward routes and restoration without duplicate grants.
Its controlled encounter setup is labelled explicitly; it is not played acceptance.

**Tutorial traversal** follows all 33 lessons with normal time-stepped mechanics:
real repairs and airlock venting, FTL charging/map jump, an enemy laser volley,
ordinary player weapons/rewards, equipment drag and paid upgrades. No direct grants,
forced deaths, fabricated controller facts, invulnerability or charge overrides are
used. Final press/release returns to the menu without clicking through; ordinary
profile/run/RNG/mode are restored. Human/native presentation comparison remains
pending. Both checks are individual, in All tests and in the packaged engine runner.

The Desktop input check also verifies that damage rendering at 30/60/120/240 FPS
does not consume simulation RNG or alter serialized gameplay state. Screen shake
has a separate cosmetic random source. These cases exposed an actual dependence
of gameplay RNG on drawing frequency, now fixed.

## Saves from the original game

`tests/native-import.mjs` (in the image, through `.build/hs/prod-int/t.sh <label> vanilla|F2|F3 native-import.mjs -e
NATIVE_DIR=/native -e NATIVE_EXPECT=/native/expect.json -v <dir>:/native:ro`): Saves > Import from FTL (the guide, refusals of
garbage and truncated files, the found files, the setup they ask for, the new save, the missing mods of a modded one), without
mods the save played through Continue and saved in the engine's format, then for every file of the session's game
(`nativeImportProbe`) the converted run and the run saved from it and loaded again against what the original saved: ship,
name, hull, fuel, missiles, drone parts, scrap, sector, beacons, crew (name, race, health), systems, weapons, drones,
augments, cargo, and the profile's layouts, achievements, scores and games.

The files are real saves of the original (never in Git): vanilla from `tools/native-capture/` sessions, Hyperspace and
Multiverse from `tools/hs-oracle/` sessions that play a few beacons and Save and Quit. `expect.json` comes from an independent
Python reader of the format and, for Hyperspace, the oracle tracer's last `state` line. `NATIVE_SAVES=<dir> ./gradlew
:savecore:test` identifies and checks every original file below `<dir>`. The 2026-11 set and its sessions are in
`.build/hs/save-tools/` (report: `.build/hs/reports/save-tools.md`).

## Engine modes on a modded fixture

Memory: `HS_FULLRUN_PROBE="reload-handles 30" tools/hs-fullrun/run.sh <seed>` loads the same run 30 times on Multiverse data
and fails when the handles Lua holds grow with the loads, which is what kept every game behind a Continue alive. The heap
after `gc()` inside the full-run probe is not a leak figure: the probe is one long job of the page, so what waits for the end
of a job (a `WeakRef` read, the queued saves) stays until it returns. Measure leaks with the page yielding between steps.

The diagnostics modes also run with Hyperspace (F2) and Hyperspace + Multiverse (F3) data. There they check the rules, not
vanilla's data: `gameplay-parity` holds back its steps that compare with captures of native vanilla (seeds, draw counts,
pools, maps, stores) and reports them as not run, and the rule checks that hold vanilla's numbers or classes follow the
mod's. They do not establish parity with native Hyperspace; the full runs and `hs-*` suites do. Multiverse has about 1,600
ship specifications and 340 player ships, so `BASELINE_MODE_TIMEOUT_MS` raises the 180 s limit per mode for `encounters`,
`ae`, `vanilla` and `campaign`. `BASELINE_ENCOUNTER_BATCH=<n>` runs `encounters` in slices on a fresh page and
`BASELINE_ENCOUNTER_RANGE=<first>,<end>` limits it to part of the sorted list; a slice with failures does not stop the next.

`gameplay-parity` also runs `GameplayWeapons17Checks` in every mode (vanilla, F2, F3). It covers a weapon armed by touch
that loses its power (ion, damage, or another weapon taking the bar) being disarmed as in WeaponControl::OnLoop, so the next
tap powers it; weapons taking power again after an ion runs out, after a repair and in an ion storm; shots queued while
charging (burst laser, missile, chain laser, beam, by desktop and by touch) firing once charged, also across a reload; ship
unlock popups for ships without a blueprint (none shown), vanilla variants (Lanius A/B, Stealth C, Crystal B) and an
F3 unlock whose icon file is missing (text only); and every vanilla `<ship hostile="false">` event, plus a surrender and a
crew kill, staying neutral and present across a reload. `hs-systems` checks that five Lua `IncreasePower(1, true)` calls on
shields in one frame add five bars, as `CustomShips.cpp` relies on.

`GameplayWeaponBarChecks` (also in `gameplay-parity`) checks the weapon charge bars against the original's box frame:
fully charged weapons of three charge times, unmanned and manned, have bars of `33 * cooldown / the longest cooldown *
cooldown modifier` pixels on desktop and in the touch slot (22 instead of 33 in the closed touch column, as the iPad's
icon view draws it), the touch frame is as tall as that bar (so a full bar fills it), and the bar takes the box's colour unless the weapon is aimed. It also checks that a save and load keep each
weapon's charge, charged state and power, and that weapons charge with no ship at the beacon. The native numbers come from
the Multiverse oracle (`.build/hs/reports/weapons-reload.md`): Burst I 27 px and Defense Laser 20 px when manned.

`gameplay-parity`'s `overflowAugmentStore` step runs under mods too: a fourth augment on a full ship goes to the over-capacity
box, and the store's buy and sell tabs draw with the mouse and touch layouts. `tests/hs-store-overflow.mjs` (F3) plays the
reported case: three augments, Multiverse's `SYLVAN_DESTRUCTION_2_SHIELD` (Merchant's Shield), a store, its sell tab. The
original shows the same over-capacity box there with the sell box below it (oracle session `bt-store-over`).
`tests/hs-mv-tutorial.mjs` (F3) compares Multiverse's tutorial with the original's, see [TUTORIAL.md](TUTORIAL.md#multiverse-tutorial).
`tests/diagnostics-full.mjs` runs the Tests page's All tests as a player would (no `navigator.webdriver`), optionally with
Hyperspace and Multiverse added through the Mods panel (`DIAG_MOD_FILES`, `DIAG_ONLY=<ids>` for a part); it is not in a
suite and, with Multiverse, takes several GB, so it runs alone.

## Debug console

[CONSOLE.md](CONSOLE.md). `ConsoleTest` (`android :app:testDebugUnitTest`): every command of both consoles is in the
one list once, the clashes keep Hyperspace's command under the name (ours under `dev-`), names match in any case, the
Hyperspace-only ones answer so in vanilla, the line edits as HS's TextInput (cursor keys, 480 characters, invertCaps),
the gating rule, the `>_` button's place arithmetic; `get` and `unlock` (the only new commands, the only ones that
glow), every command's group and examples, the reference generated into CONSOLE.md (rewrite it with
`CONSOLE_DOCS_WRITE=1`), help's search, the names (English, underscores, ids for shared names, the current language,
short names, words), where `*` is allowed and what it matches, and `get`/`unlock` without a name or with a wrong kind;
round 2: no `get ship`, keys `<name>_<ID>` for shared names (each key one item, the shared name the one a store gives),
the full-equipment refusals (`ConsoleCheats.refusal`: one item and one augment waiting at a time, crew over the limit,
none under multipleOverCapacity), Escape and the unshifted `` ` ``/hotkey close the console, Shift+`` ` `` types `~`;
round 22 (the look and the feel): the scroll (`ConsoleScroller`: a wheel notch glides to exactly its pixels, never
back or past, a trackpad's small steps add up at once, the ends stop dead; a drag follows the finger and its fling
slows evenly to a stop; a press stops a moving list where it was drawn; held still, no fling; no time, no move), the
clip (`ClipRect`: game units to framebuffer pixels at two pixels a unit and one, rows from the bottom, nested clips
intersect) and the keys in a list (Down selects, Up from the top drops it, Enter takes it, a new search drops it).
`tests/console.mjs` in the image, vanilla and on a modded
fixture (`sh $D/t.sh console-vanilla vanilla console.mjs`, `console-F3 F3 console.mjs`, in `hsq.sh`): the setting off
(vanilla closed; Hyperspace `\` only, ``` ` ``` its speed key, `<console enabled="false"/>` closed), the Logs page's
setting (Console button, both keys), typing with Left/Home/End, Up, `help`, then the Hyperspace and original commands
run with their effects read back (`consoleProbe`) and the Hyperspace-only ones; `get` and `unlock`: each picker's
items against the names read straight from the run's blueprint files (`consoleItems`), the items by name through the old
commands (each answers "Granted: ..." and where), a shared name (`Fire_Beam`, `Artemis`), the art sweep of every picker
(vanilla: only ARTILLERY_FED, ARTILLERY_FED_C and CRYSTAL_1 lack art; shared names end in the id; Multiverse's hidden
augments listed), the full equipment in a new run (a weapon and an augment past capacity wait in the over-capacity
box with the equipment screen open, a second is refused in vanilla and kept under Multiverse's multipleOverCapacity,
a crew member past the limit then a refused one), a wildcard unlock, every picker on screen (a click gets one, the wheel scrolls, typing searches), the
suggestions' scroll, help's reference, and all of it after Save and close and Continue with the unlocked layout in the
hangar's list; round 22: at 1280x720 the weapon picker takes most of the screen with three rows and more, its tiles
centred; scrolled so a row is cut 60 pixels into its art, that row is drawn from the view's top edge exactly as it
looked unscrolled (read from the canvas), the window's background shows where the gap between rows is, the header
above the view is unchanged (the clip); the wheel scrolls by the event's pixels and stops at the ends; a mouse drag of
120 pixels scrolls 120 and gets nothing; the scroll bar's thumb drags and its track pages; Page Up/Down; Down and
Enter get the selected crew member with the picker staying, Escape drops the selection first; a 2560x1440 window;
every picker and help scrolled half a row; frame strips of a wheel and a drag (`console-*-strip-*.png`, with the
engine's per-frame scroll, `consoleProbe().trace`, logged as steps, frame times and pixels per millisecond, checked
for one direction and no step over half the way); round 23: an empty picker says why, inside the panel (`get weapon`
then ` zzqx`: "Nothing matches 'zzqx'."; under plain Hyperspace, `console-F2 F2 console.mjs -e CONSOLE_PART=empty`, which
runs only up to this part: `unlock achievement` has none of Hyperspace's own, "This run has none."; the picker's grid
probe gives the message and its place as `emptyAt`); screenshots in `/out`. The Android
switch and button on the emulator: `.build/hs/reports/console-android/` (one finger through `adb input`); round 22's
emulator checks (phone and tablet shapes, flings, a tap on a moving list, frame strips from `screenrecord`) are in
`.build/hs/reports/console-polish.md`, the debug build's `console-panel` and `console-trace` broadcasts log the panel
and the scroll of the last frames.

## Over-capacity boxes

`tests/overcap.mjs` (runner jobs `hs-overcap-vanilla`, `hs-overcap-F2`, `hs-overcap-F3`; `sh $D/t.sh overcap-F2 F2
overcap.mjs`): a new run, the debug console's get past three augments, past full cargo (both boxes in use) and past the
crew limit, with screenshots of each. `BrowserEngine.overcapProbe(touch, action)` lays the open ship window out for the
mouse or the touch layout and gives its boxes (`ShipWindow.diagnosticOverCapacity`). Mouse layout: the item and augment
boxes at the original's place (Equipment::SetPosition, (-275, 100), both at once as the original draws them; one under
Multiverse's multipleOverCapacity), the crew DropBox at CrewManifest's (-275, 100), Hyperspace's extra crew box at x 146
right below it (CustomCrewManifest::OnInit; oracle session `overcap.json` `oc-hs`). Touch layout: one box at a time, the
pager's two arrows and its other page, every box on the 1024 x 768 HUD block and clear of the window and Accept. Both: a
box and a dismiss button for every crew member, none under the over-capacity box, the touch layout's dismiss
confirmation on the screen, and the extra one dismissed with its button and Yes. On the emulator:
`tools/android-touch/overcap.mjs` ([ANDROID.md](ANDROID.md#testing)); round 21's runs and frames: `.build/hs/reports/overcap.md`.

## Window places

`tests/window-places.mjs` (runner jobs `hs-winpos-vanilla`, `hs-winpos-F2`, `hs-winpos-F3`; `sh $D/t.sh winpos-F2 F2
window-places.mjs`): a new run on the Kestrel opens the ship window (upgrades and equipment tabs), a store (`STORE`), the
star map, the pause menu and the options, an event (`EVENT ASTEROID_EXPLORE`), the sector map (`EXIT`) and the game over
(the crew killed), takes a screenshot of each and reads its rectangle (`debugSnapshot().windowRect`). `consoleProbe().screens`
gives CommandGui::OnLanguageChange's system levels box widths and the ship and store windows' x
([HS_UI.md](HS_UI.md)): checked against the rule and against the original's 335 (vanilla, plain Hyperspace) and 325
(Multiverse) from the oracle (session `winpos.json`; round 23's frames and numbers: `.build/hs/reports/hs-offset.md`),
the equipment tab's item box (`consoleProbe().screens.equipmentInfo`: x + 600, top 134, 323 wide, the first weapon
hovered), and every other window at the original's place (their frames' edges measured equal to the oracle's).

## Android update check

`UpdateCheckTest` (`android :app:testDebugUnitTest`): version parsing and comparison, the once-a-day rule (failures
count), Not now per version, the ETag and a 304, the setting off making no request, which releases are offered, and
GitHub's real answer shape. On the emulator, a debug build against a stub instead of GitHub
([ANDROID.md](ANDROID.md#updates)):

```
py -3 tools/android-update/stub.py --tag v2.0.2          # 127.0.0.1:8765, the emulator's 10.0.2.2; prints every request
adb shell am broadcast -n dev.ftl911bob.unofficial/ftl.android.app.debug.ConsoleReceiver --es cmd "'updateurl http://10.0.2.2:8765/latest'"
adb shell am broadcast -n dev.ftl911bob.unofficial/ftl.android.app.debug.ConsoleReceiver --es cmd "'updatereset'"
curl http://127.0.0.1:8765/set/v2.0.1                     # or /set/noapk/<tag>, /set/status/403
```

Reopening the launcher (`am force-stop`, then `monkey -p dev.ftl911bob.unofficial -c android.intent.category.LAUNCHER 1`)
runs the automatic check when it is due; `updatereset` makes it due. Check: the banner for a newer tag, Download opening
the stub's page in the browser, Not now still hidden after a restart, nothing when up to date or offline
(`cmd connectivity airplane-mode enable`), Settings > Check for updates in both states, the stub's log showing only
`Accept`, `X-GitHub-Api-Version`, `User-Agent` and from the second request `If-None-Match` (a 304), and `adb logcat -s
FTL-Update`. Chrome on the read-only AVD starts with its first-run screens each boot; to see the page,
`adb shell am set-debug-app --persistent com.android.chrome` and `echo '_ --disable-fre --no-first-run' >
/data/local/tmp/chrome-command-line` (undo with `clear-debug-app`). Phone and tablet shapes through `wm size` and `wm density` (`reset` after). Screenshots:
`.build/hs/reports/update-check/`.

## Hyperspace More Info

- Web: `tests/hs-crewui.mjs`'s `tooltip` part runs `BrowserHsMoreInfoDiagnostics` under Hyperspace (runner job
  `hs-crewui-F3`): the mode waits for CommandGui::OnLoop; the button's hover tooltip, a click and the right Alt hotkey
  toggle it; on, the crew tooltip of an own crew member and of a Lua-added boarder lists the race's powers and the
  weapon slot tooltip is the stat list; off, both follow hyperspace.xml; a save and load keeps the mode.
- Android: `MoreInfoTest` (unit) and `tools/android-touch/moreinfo.mjs` on the emulator ([ANDROID.md](ANDROID.md#testing)).
- Native: oracle sessions `tools/hs-oracle/sessions/info-button.json` (`ib-mv` on Multiverse, `ib-hs` on Hyperspace
  alone; driver `tracer/data/hs_oracle/info-button.lua`, step `movetrace`): every place on and off, and the hangar's
  reactor box before and after a run.

## Android multi-touch on the emulator

`adb shell input` has one finger, and a Google Play image refuses `adb root` and `sendevent`. The emulator's own gRPC
`sendTouch` needs neither: it writes multi-touch protocol B to the guest's virtio touchscreen, so real fingers go through
the kernel, EventHub, InputReader and InputDispatcher into the app. `tools/android-touch/` calls it (Node only, no packages).

```
emulator -avd blindboard_api36 -read-only -gpu host -no-window -port 5570 -grpc 8570
cd android && ./gradlew --offline :app:assembleDebug && adb -s emulator-5570 install -r app/build/outputs/apk/debug/app-debug.apk
node tools/android-touch/run.mjs --serial emulator-5570 --grpc 8570 --out <dir> [--only name,name] [--list]
```

It needs `ftl.dat` already imported on the device, opens the game itself, and starts each scenario with a new run and a
spawned enemy. The debug-only `TouchProbe` answers with the game's state, and `<dir>` gets frames with the fingers shown
(`show_touches`); look at them, a passing check does not say the picture is right. Exit code 1 on any failure. Beam, odd
cases, cancels, rotation, border fingers, doors and hold tooltips take about 8 minutes. Keep the screen in its default fit.
`getevent -lt /dev/input/event2` on the device shows the raw events. The touch HUD's desktop elements (TouchExtras) have
their own one-finger script, `tools/android-touch/extras.mjs` ([ANDROID.md](ANDROID.md#testing)): places, taps, the
Multiverse storage crate's rule, the shelf's alignment and frame-to-frame stability. `tools/android-touch/tutorial.mjs`
checks a Lua tutorial's arrows and outlines on the touch HUD (Multiverse's lessons, [ANDROID.md](ANDROID.md#testing)),
and `tools/android-touch/reorder.mjs` the touch crew reorder (a held box slid to another place; a tap, a hold lifted in
place and a quick slide still select or paint as before). `TouchRouterTest` (`:app:testDebugUnitTest`) is the
quick check of finger routing, at the engine's call only.

## Packaged integration runner

The app image includes Node and Playwright's Chromium, Chrome, Edge and Firefox.
It runs the checked-in scripts against disposable loopback server instances,
fresh account databases and separate encryption keys under `/tmp`. Only prepared
game assets are shared, read-only. Firefox uses a private virtual display in the
container; it does not open a window on the user's desktop. Container browser
checks are separate from the current-PC performance measurement.

| Group | Scripts |
| --- | --- |
| Browser modules | `audio.mjs`, `frame-pacer.mjs`, `diagnostic-export.mjs`, `resource-cache.mjs`, `dat.mjs`, `mods-merge.mjs` (mod merge against ftlman ground truth, see [mods](MODS.md); also the Hyperspace check's rule and picker hint), `mods-mega.mjs`, `saves-client.mjs` (the page's syncer over the real `savecore.js` and an in-memory account: offline then sync, conflicts, locks and overrides, tabs, deletions, limits, profiles; see [saves](SAVES.md)) |
| Security | `server-security/Ftl.SecurityTests.csproj`, `security.mjs`, `accounts.mjs`, `account-recovery.mjs`, `onboarding.mjs` (consent and mode cookies, registration, persistent sessions, account deletion, single bootstrap admin), `mods-security.mjs`, `saves-v2.mjs` (save model 2 API: revisions, locks and overrides, snapshots, tombstones, profiles, limits, account isolation) |
| Browser menus and launcher | `saves-softlock.mjs` (a save locked to its mod files by hash; `SOFTLOCK_HS_FILES=<files>` runs it on real ones, `SOFTLOCK_FILL_QUOTA=1` measures a real quota refusal in Chromium and stops there), `menus.mjs`, `tutorial-ui.mjs`, `launcher.mjs`, `diagnostics-ui.mjs`, `resource-cache-ui.mjs`, `local-mode.mjs` (first visit, local-only saves, move to account, account panel, Sign out's question: Cancel and Sign out, the unsynced line, the other pages' header), `mods-ui.mjs` (mod library, modded launch and saves, unchanged vanilla launch), `mods-browser.mjs` (catalogue downloads, local files, saves with mods, the "Hyperspace mod without Hyperspace" warning: both choices, the download permission with sign-in, unreachable and failed cases, an existing save through Advanced, New save), `saves-browser.mjs` (save model 2 in the page: offline play then sync, missing content, two tabs, another device's override, both conflict choices, Verified/Custom mods and "missing mod", migration of the old browser stores) |
| Diagnostic recording and recovery | `diagnostics.mjs` |
| Unavailable diagnostic storage | `debug-storage.mjs` |
| HTTPS production behavior | `production.mjs` |
| Complete engine matrices | `baseline.mjs` |

Each script receives a fresh fixture where needed, so login rate limits, accounts,
leases and saves do not leak between suites. A new fixture has only `admin`; the
runner creates `user01`..`user10` with `create-accounts`, and UI tests start as a
returning visitor (`tests/lib/onboarding.mjs` sets the consent and cloud cookies). The launcher UI check inside the
runner cannot recursively start another runner. Script names are fixed; the API
does not accept arbitrary commands or paths.

One server job runs at a time per application instance. Its owner alone can read
or stop it. Running jobs continue if their page closes; reopening Tests resumes
the owner's latest running job. Server jobs have a twenty-minute ceiling. The
latest sixteen job reports and bounded logs live in application memory, surviving
page reloads but not container restarts; export results to retain them. Browser
case results are local to the page. Temporary fixture files are removed after
completion or cancellation. Browser profiles and caches use the same job-scoped
temporary directory, including when a running browser is killed. Container
shutdown also terminates a running job.

The Compose `/tmp` limit is 512 MiB for temporary browsers and fixtures. Set
`FTL_TEST_RUNNER_ENABLED=false` to disable integration jobs; in-browser checks and
benchmarks remain available. The packaged browsers increase image size.

## Developer verification

Node unit entry points call the same functions used by the web page. Existing CLI
scripts retain their isolated default ports. `DEBUG_FIXTURE_DIR` selects the
temporary port-8084 fixture's credential directory. The packaged runner supplies
it and `RUNNER_FIREFOX_WINDOWED=1`; ordinary desktop execution stays headless.

`LAUNCHER_TEST_JOBS=1 node tests/launcher.mjs` verifies the complete launcher and
packaged runner against a disposable port-8084 app with the runner enabled. It
tests whitelist rejection, account isolation, single-job enforcement, Stop,
reload/resume and unchanged caller saves. Never use fixture test passwords on
the main account database.

## Real-time tutorial browser test

The packaged menu group also runs `tests/tutorial-ui.mjs`. It starts Tutorial
from the actual main menu, dismisses the opening dialogue chain, uses visible
Exit and reloads, then verifies that ordinary Continue restores a previously
saved ship, AE mode, difficulty and hull. Chrome, Edge and Firefox complete all
33 lessons using
actual pointer/keyboard input and the normal animation-frame clock. Crew repair,
venting, FTL charge/jump, enemy volleys, weapons, rewards, cargo dragging and a
paid upgrade run without engine probes, console grants, fabricated observations,
invulnerability or accelerated time steps. Each browser also runs the entry, Exit, fullscreen, reload and Continue cases.
This remains automated browser
acceptance; it does not establish human play or complete native presentation.

`TUTORIAL_UI_BROWSERS=Chrome,Edge,Firefox` selects browsers.
`TUTORIAL_UI_FULL_BROWSERS=Chrome,Edge,Firefox` selects full traversals; an empty value runs
only entry/exit/continuation cases. Results and failure screenshots/state are
written under ignored `.build/`, and packaged fixtures never touch live data.

## Main menu and hangar

The packaged Menus group includes `tests/hangar-ui.mjs`: real family/list/layout
selection, locked Start, preview RNG/profile isolation, ship/crew naming and
colour, original hover samples, tooltips, Rooms, AE and Options retention in
Chrome, Edge and Firefox. Visible Back cancels a name edit while retaining
fullscreen; physical Escape is checked outside browser fullscreen. The existing
Hangar & AE engine diagnostic also renders all 18 vanilla/28 AE layouts and
checks layout-preserving arrows, custom/legacy name saves and restart.

`tests/ui-presentation.mjs` also runs in Menus: actual white/yellow Start pixels,
heading/title glyph rows, grid frame, crew Customize pointer edges, weapon-slot
dimensions/spacing, autofire off/on/hover pixels and click edges, bound-key desktop tooltip, three/four-slot HUD
captures and absence of autofire on the two-slot tutorial. Temporary test unlocks/
runs stay in the fixture; its original progress and gameplay RNG are restored.

`MENU_HANGAR_ONLY=1` selects focused checks in the isolated menu harness. Firefox
needs the packaged display and `RUNNER_FIREFOX_WINDOWED=1` for WebGL2 in this
container. These functional checks do not establish exact native presentation
or audible comparison. See [implementation and reference boundaries](HANGAR.md).

## One click acts once

Every screen acts on the press, and a press that closes or replaces a window or screen keeps its release; a finger taps
a button only when it lifts on the button it went down on ([ORIGINAL_BEHAVIOR.md](ORIGINAL_BEHAVIOR.md#one-click-one-receiver-2026-11-05)).
`tests/click-through.mjs` (vanilla and a modded fixture: `sh $D/t.sh click-vanilla vanilla click-through.mjs`,
`click-F3 F3 click-through.mjs`, runner jobs `hs-click-vanilla`, `hs-click-F3`) uses the browser's own mouse: the title
menu's Close clicked where a menu button lies under it (vanilla: Tutorial under Close's top right corner) and Close held
and let go on each menu button; Statistics and its Back, and the hangar's List, each pressed and let go elsewhere (they
act on the press); in a run, the pause menu's Continue let go over an enemy room with a weapon armed (no shot aimed), a
right click on the pause menu (the weapon stays armed), the debug console closed while its press is held over the Ship
button; Game Over's Main Menu and Hangar held and let go on the next screen's buttons. The places come from the debug
snapshot (`buttons` in the main menu, `close` in the title options, `clickTargets` in a run). `ClickThroughTest` and
`TouchTapTest` (`:app:testDebugUnitTest`) check `HostInput`, the hosts' input, and the touch router's taps (lift on the
same button, slid off or onto another, a second finger, a cancel, Android's mouse, a tap that closes a screen). On the
emulator, `node tools/android-touch/click-through.mjs --serial <serial> --out <dir> [--shape phone|tablet]` (one finger
through `adb input`, from the main menu): Close tapped over a menu button, Close slid onto each menu button (nothing
acts), Statistics tapped, slid off and slid onto another button, Game Over's Main Menu slid off and tapped, Hangar tapped,
the hangar's List slid off and tapped. `tools/android-touch/moreinfo.mjs` taps More Info over the open ship screen and
under the pause menu ([ANDROID.md](ANDROID.md#testing)).

## Menu and stranded-crew confirmations

The `warnings` engine selection covers native restart/hangar/title confirmation,
right/outside-click rejection, consumed hotkeys, No/Escape state/save preservation,
positive-score abandonment without victory, duplicate answers/ended-save restore,
Main Menu/Save+Quit preservation and saved/fresh New Game. Boarding fixtures use
a non-pilot, preserve ownership through mind control, exclude enemy-owned/dead
crew and reject an unready drive. Both jump routes warn before opening the map.

`tests/warnings-ui.mjs` is included by `tests/menus.mjs` in the packaged Menus
group. Chrome, Edge and Firefox use real pointer/keyboard/toolbar input with the
normal frame clock and an explicit boarding setup. All four warning paths, exact
No/Back run/profile/checkpoint preservation, accepted transitions and fullscreen
retention are checked. `MENU_WARNINGS_ONLY=1 node tests/menus.mjs` runs just these
focused interface cases against isolated8084; omit it for full menu acceptance.
Controlled setup and automated campaign traversal are not played victories.

## Gameplay options

The **Gameplay options** engine selection checks native defaults and legacy/
malformed profile fallback, all three event-number-key modes, independent weapon
bindings, disabled/early/invalid input, mouse/key delay boundaries, event-chain
and modal-save clocks, and focus pause restoration. Render pacing does not
throttle the event clock. Focus pause does not change explicit pause or open a
menu, and its transient flag is excluded from the saved simulation.

`tests/options-ui.mjs` runs in the packaged Menus group: actual pointer/keyboard
controls, fullscreen Back, real canvas blur/focus, all three event modes, WebGL
pixels for hovered beacon connections, and cloud-save/reload persistence. Its
controlled run/dialogue/map setup is separate from subsequent real input.
`MENU_OPTIONS_ONLY=1 node tests/menus.mjs` selects focused option interface checks
on isolated8084 (`MENU_ORIGIN=http://localhost:8083` with its matching
DEBUG_FIXTURE_DIR also supports a separate disposable fixture). Unlimited UI
acceptance compares drawn frames with available browser animation frames; it
does not assume a minimum display refresh or idle-PC throughput. That one-second
pacing comparison opts out of benchmark warmup; the Benchmark preset retains its
normal two-second warmup. The Colorblind option is a user-requested WIP notice; its
modal input, Back/acknowledgment, fullscreen and unchanged settings/RNG are
tested from title, hangar and run Options. Native video/language options remain separate.

Set `FTL_SKIP_PERFORMANCE=1` when performance testing is deferred. Browser suites then skip timed FPS/throughput,
browser-timing measurements and flagship benchmark runs (including cache/session benchmark runs), logging each
skip explicitly. Frame-limit controls, persistence, functional engine checks and browser coverage still run.
Keep test jobs and browsers sequential. Unset the variable or set it to `0` to restore performance coverage;
a run with it enabled does not establish performance acceptance.
