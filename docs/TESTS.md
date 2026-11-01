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
`getevent -lt /dev/input/event2` on the device shows the raw events. `TouchRouterTest` (`:app:testDebugUnitTest`) is the
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
| Browser modules | `audio.mjs`, `frame-pacer.mjs`, `diagnostic-export.mjs`, `resource-cache.mjs`, `dat.mjs`, `mods-merge.mjs` (mod merge against ftlman ground truth, see [mods](MODS.md)), `mods-mega.mjs`, `saves-client.mjs` (the page's syncer over the real `savecore.js` and an in-memory account: offline then sync, conflicts, locks and overrides, tabs, deletions, limits, profiles; see [saves](SAVES.md)) |
| Security | `server-security/Ftl.SecurityTests.csproj`, `security.mjs`, `accounts.mjs`, `account-recovery.mjs`, `onboarding.mjs` (consent and mode cookies, registration, persistent sessions, account deletion, single bootstrap admin), `mods-security.mjs`, `saves-v2.mjs` (save model 2 API: revisions, locks and overrides, snapshots, tombstones, profiles, limits, account isolation) |
| Browser menus and launcher | `saves-softlock.mjs` (a save locked to its mod files by hash; `SOFTLOCK_HS_FILES=<files>` runs it on real ones, `SOFTLOCK_FILL_QUOTA=1` measures a real quota refusal in Chromium and stops there), `menus.mjs`, `tutorial-ui.mjs`, `launcher.mjs`, `diagnostics-ui.mjs`, `resource-cache-ui.mjs`, `local-mode.mjs` (first visit, local-only saves, move to account, account panel), `mods-ui.mjs` (mod library, modded launch and saves, unchanged vanilla launch), `mods-browser.mjs` (catalogue downloads, local files, saves with mods), `saves-browser.mjs` (save model 2 in the page: offline play then sync, missing content, two tabs, another device's override, both conflict choices, Verified/Custom mods and "missing mod", migration of the old browser stores) |
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
