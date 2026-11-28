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
  All tests. The merge is the Play page's (`prepareModdedLaunch`, its cache included). Core is `hsCoreProbe` (the loader rules of
  [mods](MODS.md#what-the-loaders-accept) on names of its own, the hyperspace.xml errors of [HYPERSPACE.md](HYPERSPACE.md) (also `HsConfigErrorsTest`, Android), the gate and saves; with Multiverse also its `hyperspace.xml` values), weapons/systems/crew/hazards the parts of `tests/hs-weapons.mjs`,
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

## Saves on both platforms

The save questions and the list line are one text on the website and in the Android launcher: `web/save-words.mjs` and
`SaveWords.kt`, compared by `SaveWordsTest` (Android unit tests, also the stock ship names and the list line of
`web/saves-ui.mjs`). The website's flows run in `saves-client.mjs` (unit), `saves-browser.mjs` and `mods-browser.mjs`
(menus; the latter also checks that a recovery snapshot downloads as a schema 3 backup with its profile), the server's in
`saves-v2.mjs` (security). The launcher's flows are checked on the emulator against a disposable local server container
(`docker run -p 127.0.0.1:8097:8080 -e ASPNETCORE_ENVIRONMENT=Development -e PUBLIC_ORIGIN=http://localhost:8097 ...` through
docker-slot, `adb reverse tcp:8097 tcp:8097`, server address `http://localhost:8097` in the debug build) with a host script as
the other device (log in, list, delete, lock, fill the account): Create an account, a save deleted elsewhere with unsynced
progress (Upload again), Delete while another device holds the lock (Delete anyway), Delete offline (nothing deleted), and a
new save the full account refuses ("Not synced: ..."). The 2026-11-06 rig and screenshots: `.build/hs/reports/ps-a2.md`.

## Saves from the original game

`tests/native-import.mjs` (in the image, through `.build/hs/prod-int/t.sh <label> vanilla|F2|F3 native-import.mjs -e
NATIVE_DIR=/native -e NATIVE_EXPECT=/native/expect.json -v <dir>:/native:ro`): Saves > Import from FTL (the guide, refusals of
garbage and truncated files, the found files, the setup they ask for, the new save, the missing mods of a modded one), without
mods the save played through Continue and saved in the engine's format, then for every file of the session's game
(`nativeImportProbe`) the converted run and the run saved from it and loaded again against what the original saved: ship,
name, hull, fuel, missiles, drone parts, scrap, sector, beacons, crew (name, race, health), systems, weapons, drones,
augments, cargo, and the profile's layouts, achievements, scores and games; for a save left mid-fight (`van-a1`, `van-cx`)
also each weapon's charge of both ships (`charges`, `enemyCharges`, the fraction of its charge time, within 0.01).

The files are real saves of the original (never in Git): vanilla from `tools/native-capture/` sessions, Hyperspace and
Multiverse from `tools/hs-oracle/` sessions that play a few beacons and Save and Quit. `expect.json` comes from an independent
Python reader of the format and, for Hyperspace, the oracle tracer's last `state` line. `NATIVE_SAVES=<dir> ./gradlew
:savecore:test` identifies and checks every original file below `<dir>`. The 2026-11 set and its sessions are in
`.build/hs/save-tools/` (report: `.build/hs/reports/save-tools.md`).

## Saves and Continue (engine)

The gameplay-parity mode (vanilla, F2, F3) saves and loads live games, one state at a time. `GameplayParityShell6Checks`
compares a digest of the live objects before and after (idle, an open event box, combat with shots in flight, fires,
breaches, boarders, drones, hacking, mind control, cloak, the ASB between two shots, a jump in progress: every save
completes it). `GameplaySavePsS2Checks` (parity sweep S2): an enemy saved mid-escape and mid-`<instantEscape>` goes on
leaving, an event box opening autosaves (and the box a load brings back does not), a crew member mid-teleport arrives,
a shot charged and aimed while paused leaves, an artillery burst saved between two shots finishes, two saves in a row
are one save and draw nothing, a save made while the ship breaks up ends in the game over, a save naming a missing
augment, weapon and crew race loads without them and lists them, a sun's and a pulsar's timers come back as saved,
and (Hyperspace) a projectile's custom damage comes back by its weapon's name. The clone queue is in the progress mode (`BrowserRunStatisticsDiagnostics`), a volley and a
beam at a destroyed ship in `GameplaySave8Checks`, Hyperspace runs reloaded at every beacon in `reload-F2`/`reload-F3`.
`GameplaySaveDroneBodyChecks` (release 2.2.4, "Missing object for ID: 'drone_battle2:7'"): the enemy's boarding drone
(Multiverse: Battle Mk II, `BATTLETOG2_BOARDER`) aboard and fought when its ship is destroyed or jumps away is saved
on that frame (the event box's autosave) and loaded: the body is not in the run and its fighter has no target, as in
the game going on; a body whose ship is still here keeps its fight over a reload; a run of revision 2 naming a fight
target it does not hold loads with the target dropped (migration 3). `SaveMigrationsTest` (Android) has the loader's
rule and migration 3 on their own.
`GameplaySaveRefsChecks` (release 2.2.5, "Unregistered object" from `Temporal.saveSystem`): a fight ends three ways
(destroyed; escaped; crew killed, then destroyed) and the run is saved on every frame until 40 frames after the ship
went, then the save after it went and the last one load and step. The player aims at it: a temporal dilation on one of
its rooms (where a player ship lists the system, Multiverse; it runs on after a destroyed ship went, ends at an
escape's JumpLeave, and loads off and locked), and (Lanius B) a landed hacking probe pulsing, mind control on its
crew, a teleport ordered to its rooms every frame, a boarding pod launched as the end starts, a bomb fired at its rooms
every frame. And a crew member still leaving for the enemy when the location changes at once (a jump settled by save and
close): it is lost between ships in play and after a reload of the run saved meanwhile.

Native rows: `tools/native-capture/sessions/ps-s2-native.json` (`.build/hs/sweep/vanilla/run.sh`): Continue of
`run-cx.sav` with the HUD every 0.25 s (`s2-cx`, its browser twin through `tools/ui-capture.sh`), the autosave of an
event box opened after Continue (`s2-ev`: `continue.sav` holds the event) and none without one (`s2-ld`: the load
deleted the file and nothing wrote it in 6 s).

## Crash sweep (engine mode `crash-sweep`)

`crash-sweep` (jobs `p-van-crash-sweep`, `p-F2-crash-sweep`, `p-F3-crash-sweep`; `BrowserCrashSweepDiagnostics`, one
case per record, a fresh engine after a failed one; `?crashsweep=<from>,<count>` runs a slice) plays seeded fights
through the transitions that end what another object points at, and fails on any exception in a frame, a drawing, a
save or a load, on a load of this build's own save that needed a repair (`loadDroppedRefs`, migration lines), and with
Hyperspace on any engine error a Lua call met (`HsLua.engineErrorKeys`). A case: a player ship (the hangar's layouts in
a fixed shuffle; with Hyperspace also every player ship that lists the temporal system) gets every system its layout
has room for (the console's SYS), GOD power, three weapons, three drones, two augments and two more crew; an enemy (any
ship spec, seeded) gets the systems it has room for, boarding, combat and defence drones and full power. The fight: the
player's weapons at its rooms, drones out, its crew teleported over, hacking, mind control, temporal and cloak at the
enemy, an event's boarders, the enemy's own active systems at the player, and a random-action phase (power, aims,
drones on and off, crew moves, kills on both sides, dismissals, fires, breaches, system damage and ion, a weapon to the
hold, an event's `<remove>`, new equipment, the pointer over and clicking the screen, the touch layout's taps on every
third case, shifted each round so every ending meets both input routes). 45 cases in vanilla, 27 with Hyperspace (five
and three rounds of the nine endings; about 2.5, 2 and 4 min for van, F2, F3). Then one ending per case in turn: the enemy destroyed (DELETE), its crew killed, its surrender, its
escape, its instant escape, the player jumping away mid-fight, a sector change mid-fight, the player's ship destroyed,
or the fight going on to the end. After it the run goes on, leaves the beacon and comes back to it. Every change of the
coarse state (beacon, enemy and its hostility, jumps, boxes, crew and drone bodies per ship and side, mind control,
external drones, systems and hacks, hacking, cloak, temporal, clone queue; and every 240 frames) saves through
`MainGame.writeRunSave` (as the autosave does) and loads the run, and the case goes on with the loaded game. The frame
is `BrowserEngine.step` and `render`, so Hyperspace's Lua loops run too. Its finds are held by `gameplay-parity`'s
`GameplayCrashSweepChecks`.

`gameplay-parity`'s `GameplayCrashSweepChecks` (round 26, vanilla, F2, F3): `teleportAtCloaked*` commands a teleport at
a cloaked enemy, which then escapes or is destroyed: the command is dropped (CombatControl::GetTeleportationCommand
0x57f370 waits only for the current target's cloak; CombatControl::Clear 0x57d2c0 drops the target) and the run saves
and loads. Before, the command waited on the gone ship's cloak and every save threw "Unregistered object (Ship)".
`podAtEscapedShip` and `podAtCrewKilledShip` launch the player's boarding pod (still in the player's space) as the enemy
escapes or its crew dies: in the frame the fight ends the pod is in no projectile list, and the run saves and loads.
Before, a pod at a ship that went stayed a frame (its save named that ship), and a pod that died in its owner's space
stayed listed until its next update, so a save then wrote the drone with neither pod nor body and its load threw
(BoarderPodDrone::SaveState 0x420a70, ShipManager::SetTarget 0x4a2b30).

`gameplay-parity`'s `GameplayR27UnitsChecks` (round 27 unit sweep, vanilla, F2, F3; every check runs and the failures
are listed together): crew that cannot man take no station and walk nowhere (HS PrioritizeTask prices manning 1001
without CanMan); a boarder problem goes to the enemy's crew member that cannot fight while its guard keeps its station
(CrewAI::AttemptToAssign asks no CanFight); a rock throws within 16 updates of half its shoot strip's time (its strip
stops on its end for one update, Animation::Update, so frame 3 comes every pass); a crew member killed by Lua
`Kill(true)` is not ready to clone and stays out of the clone queue (CrewMember::Kill clears clone_ready; F2/F3 only);
an enemy's boarding pod of a blueprint with `<speed>50</speed>` flies at 800 px/s, the blueprint's speed times 16 as
every space drone (oracle r27u-pod), not the vanilla pods' 18; and (F3) the enemy's Multiverse Manager Drone
(MANNING_ENEMY, race bonusPower 1), powered in the drone room, gives the drone system one bonus bar (HS ShipManager::OnLoop
counts crew drones' BONUS_POWER; oracle r27u-drones1: MANNING deployed, drone system effective power 1); a boarder
killed breaking a door aboard the enemy waits in the player's clone bay with no door in the save, and a save that names a
door past the clone bay ship's doors for it loads (unit sweep F3 case 19's die step: an enemy intruder queued in the
Free Mantis clone bay, IndexOutOfBoundsException at the load); and (F3) the Manager Drone, its one bar covered by its
own body's bonus, switches off and stays off (DroneSystem::DePowerDrone succeeds, UpdateBonusPower powers again only a
drone still covered, and the body's bonus ends with its drone; oracle r27u-drones1 MANNING_ENEMY, r27u-d2a DIRECTOR).

## Unit sweep (engine mode `unit-sweep`)

`unit-sweep` (jobs `p-van-unit-sweep`, `p-F2-unit-sweep`, `p-F3-unit-sweep-1` and `-2`; `BrowserUnitSweepDiagnostics`,
one case per record, a fresh engine after a failed one; `?unitsweep=<from>,<count>` runs a slice, and the runner's
`BASELINE_UNITSWEEP_BATCH` / `BASELINE_UNITSWEEP_RANGE` ("first,end" case indices) run a range in slices on fresh
pages; a `TEST unit sweep i/n` console line per case) puts every crew race (every
crew blueprint and, with Hyperspace, every `<race>` of hyperspace.xml) and every drone blueprint of the loaded data
through what it can meet, with a save (`MainGame.writeRunSave`) and a load of the run after every step; the case goes
on with the loaded game. The player's ship keeps one of its own crew (healed every frame, so a case never loses the
ship) and the player's hull stays whole. Crew cases (seven races each, so the crew stays at the limit of 8: over it the
game holds the dismissal window open and nothing moves): each race on the player's ship, on the enemy's ship and as an
intruder; every frame of every strip it can play (walk, type, shoot, fire in four directions, repair, punch, teleport,
death, portrait) cut from its sheet; its store tile and info box and its crew box drawn; then walk, man a system,
repair, fire, breach, suffocation, a fight with the intruders, a teleport to the enemy and back and the teleporter's
send, mind control both ways, a transform to the next race and back (Hyperspace), death with the clone bay (one
replaces the start ship's medbay, repaired and powered before the step, as an unpowered bay lets its queue die after
3 s; each clone's wait is cut to its last tenth; a crew member already dead before the step is noted, not failed), death without, dismissal. Crew a power or
a death effect spawns over the crew limit are dismissed, as a player would in the window the original opens too. Drone cases (three blueprints, four with Hyperspace): equipped in the player's slots and the
enemy's, store tile and info box; deploy, an ion hit on the drone system, power back, destroyed, depowered once rebuilt (10 s), a jump away
with the drones out, a new enemy at the next beacon. Fails on any exception in a frame, a drawing, a save or a load,
a load of this build's own save that needed a repair, a Lua engine error, NaN or infinite health or position, negative
health, a window other than an event box holding the game paused, a crew member whose race is no crew blueprint or Hyperspace race, an empty crew name, a walk goal on
the other ship than the crew member stands on, a Hyperspace crew limit that does not count each crew member's
CREW_SLOTS (noSlot crew none), a strip frame outside its sheet, a drone with an unknown weapon blueprint, or a crew member dying, teleporting or being cloned for over 16 s.
Each PASS line lists per item what it did (the actions per step, cloned, teleported, suffocates, a strip it lacks, a
missing sheet image): `.build/hs/r27-sweep/r27-units/` turns them into the coverage table.

The oracle side of the same items is the probe `tools/hs-oracle/tracer/data/hs_oracle/r27-units.lua` (sessions from
`tools/hs-oracle/sessions/gen-r27-units.py`, compared by `tools/hs-oracle/r27units_compare.py`): per race the
CalculateStat value of every CrewStat on the player's, the enemy's and an intruder, the CrewMember methods, the strips'
frame counts, the CrewDefinition and the crew blueprint; per drone its blueprint, its state deployed, after the button again, after it again, destroyed and after a new
click, and
its crew drone bodies; `R27U.fight` (gen-r27-units.py `--fight`) logs two fights per race (the race against a human
boarder, and as a boarder against a human), every fighter's health every 8 ticks. `--late` sessions log the crew rows a second
time 2 s after the spawn and ship rows (`cr`, `cr2`: the subject's room, fires, every room's oxygen, both ships' crew
count and dead) at the end and 2 s in; `--drone` sessions turn a drone off by its box's right click (DeselectArmament) and
log the fight state (`ds`: enemy crew and dead, enemy hull, fuel, scrap) at each drone step. A boarding pod's
steps wait up to 600 ticks for its delivery (`dl`: the ticks waited, the pod still flying), as the original lists the
pod's crew drone in the target's crew list from the launch and a step's kill would hit it in flight; `R27U.watch`
(ticks) logs the drone, the enemy's hull, FTL charge, fires and the DAMAGE_AREA / DAMAGE_AREA_HIT events on it, and
both ships' crew with their rooms' oxygen (`dw`); `R27U.heal` (gen-r27-units.py `--heal`) heals the enemy crew at every
drone's start and logs their health before it (`dh`), so one boarder's fight does not decide the next drone's (a
random fight left ours without enemy crew from item 35 of r27u-drones2). In a late crew session (`--heal --late`)
`R27U.heal` also holds the enemy's FTL charge at 0 and logs its destroyed flag and hull before each race (`dj`): ours'
enemy left mid-session otherwise (r27u-late, from item 45). The
hacking system's drone (DRONE_HACKING) is left out of the drone sessions: deployed from a drone slot it stops the
original's loop. The probe's
`CalculateStat` rows compare the half of the Lua return the stat uses (the number for a number stat, the bool for a
bool stat; the other half is not game state).

## Ability sweep (engine mode `ability-sweep`)

`ability-sweep` (jobs `p-van-ability-sweep`, `p-F2-ability-sweep`, `p-F3-ability-sweep`;
`BrowserAbilitySweepDiagnostics`, one record per case, a fresh engine after a failed one; `?abilitysweep=<from>,<count>`
runs a slice) uses every crew power, passive stat boost, stat boost definition and augment of the loaded content in a
fight, and fails on any exception in a frame, a drawing, a save or a load, an engine error a Lua call met, a NaN or
infinite health, stat, cooldown, duration or charge, negative health, a cooldown, duration or charge outside its range,
a living crew member whose race has no crew blueprint, a shown power button with an empty text, a sprite path with no
file or a power animation with no `<anim>` (`InGameState.MISSING_IMAGES`, `CrewPowers.anim`), and a save of this build
that needs repairs or does not bring back every subject's race, side, health and power state. Cases: `races` (with
Hyperspace, twelve races with a power or passive boosts each, on PLAYER_SHIP_MVKESTREL when the content has it): four
copies of the race as the oracle driver places them (player crew at home, an enemy boarder beside it, enemy crew at
home, a player boarder beside it, and a human of each side in each room), cooldowns full, the player's two press their
ready powers through the crew box button and the enemy's are left to the AI; 1.5 s, a save and load, one ending in turn
(none, the enemy escaping, the enemy destroyed, the player jumping away, a sector change), 1.5 s, every temporary
effect ended, then all of them killed and gone within 10 s. `augment powers`: each augment whose stat boosts give a
power, with a crew member of the race it names. `augments`: each augment blueprint on both ships, its
GetAugmentationValue and those of its Hyperspace functions finite, a save and load every tenth (still installed, same
value), then as a hidden augment. `boosts`: each stat boost definition as a timed boost on a crew member of each side,
every stat computed. A save's crew and powers are compared one frame after the load (the stat loop applies the loaded boosts first; health within 5 + 10 %, a cooldown within two frames). A window other than an event box (one an ending opened) is closed and listed in the case's notes. On Multiverse (F3) the whole sweep, 30 cases, takes about 3 min in a 3 GB container (peak 2.95 GiB, `.build/hs/int-fixes/m-r27-powers/r27p-ab8.mem`, `r27p-ab9`). The oracle comparison of the same items is the r27-powers driver (`HS_ORACLE.md`).

## Ship sweep (engine mode `ship-sweep`)

`ship-sweep` (jobs `p-van-ship-sweep`, `p-F2-ship-sweep` and the shards `p-F3-ship-sweep-<n>`; `BrowserShipSweepDiagnostics`,
one case per record, a fresh engine after a failed one; `?shipsweep=<from>,<count>` runs a slice, which
`BASELINE_SHIPSWEEP_BATCH` / `BASELINE_SHIPSWEEP_RANGE` set like the encounters' variables) exercises every ship blueprint
of the loaded game, then every hangar layout. As an enemy (all blueprints, sorted by name) the blueprint is an event ship
(`<ship auto_blueprint>`) at a world level and seed of its own against the sweep's player ship (the MV Kestrel with
Multiverse, else the Kestrel, in GOD mode); its start is checked, both ships fire and launch drones at each other for three
seconds, the run saves (MainGame.writeRunSave, as the autosave) and loads, and then one ending in turn (destroyed, its crew
killed, its escape) runs to its end with a save and load after it. As the player's ship (every hangar layout and its B/C
layouts) a new run starts, saves and loads, fights a small enemy, jumps to the next beacon and saves and loads again. A
case fails on any exception in a frame, drawing, save or load, on a load of this build's own save that needed repairs,
with Hyperspace on a new engine error inside a Lua call, and on an invalid value of the ship: a hull outside 0..max or a
max of 0, no rooms or an empty one, a system in no room of the ship, below one bar or powered past its level, crew with a
NaN or negative health or in a room of no ship, an unknown weapon or drone. A missing hull image or an unnamed crew member
is a note on the PASS line, not a failure (the original draws nothing there either). The player's own crew below half
health are healed each frame, so a boarding enemy cannot end the run (LOOSE_CREW) before the ending's save; a run is
reused for the next case (up to 40) only without an earlier enemy's boarders aboard (they stay when their ship escapes)
and with all its own crew. With
Multiverse loaded, case 0 also checks the oracle rows recorded on MV 5.5.1 (`oracleSystems` / `oracleLists`: system
levels and power at creation, crew rooms and sexes, list picks, super shields). `?shipsweeptrace=1` (`BASELINE_SHIPSWEEP_TRACE`) prints
each case's phases and every 50th frame, each loss, ship change, mind control or big hit of an own crew member, why a
run was not reused, and a Lua count hook prints the traceback of a tick that runs 1e6 instructions.

The comparison with the original (round 27) is outside the gate: `tests/hs-shipsweep.mjs` writes every blueprint built
by `ShipGenerator.buildShip` with a seed (`shipSweepDump("dump <sector> <seed0> <from> <count>")`) and every hangar
layout's start (`shipSweepDump("start <ship> [steps]")`, `SWEEP_START_FRAMES`: engine steps after the start, as the
oracle reads its starts a second into the run) in the tracer's ship_full layout; the oracle probe
`tools/hs-oracle/tracer/data/hs_oracle/r27-ships.lua` loads one generated event per blueprint in the original and reseeds
the stream at Hyperspace's GENERATOR_CREATE_SHIP (`Hyperspace.setRandomSeed`, the same seed), writing the built ship at
GENERATOR_CREATE_SHIP_POST. The probe's per-blueprint events and name list are generated from the game data and never in
Git (`.gitignore`); the oracle's waits now end as soon as the game process is gone (`game_running`).

Where a build differs, the random32 calls show where the two streams part: `SWEEP_RNG=<sector>:<seed>:<name>,...`
(`shipSweepDump("rngtrace <sector> <seed> <name>")`) writes `rng-<name>.json`, every draw with its JS stack (map it with
the image's `engine.js.map`); the oracle's `rngtrace` step on the rngtrace fixture exe hooks random32, and each `rngdump <tag>`
step appends the draws since the last one (caller and 32 stack words) to `rng-calls.jsonl`.

## Engine modes on a modded fixture

Memory: `HS_FULLRUN_PROBE="reload-handles 30" tools/hs-fullrun/run.sh <seed>` loads the same run 30 times on Multiverse data
and fails when the handles Lua holds grow with the loads, which is what kept every game behind a Continue alive. The heap
after `gc()` inside the full-run probe is not a leak figure: the probe is one long job of the page, so what waits for the end
of a job (a `WeakRef` read, the queued saves) stays until it returns. Measure leaks with the page yielding between steps.

Replaced runs: the `vanilla` and `ae` modes (every fixture) fail a layout when, after its new game and two loads, the drone
runtime's static per-ship maps still hold a ship of a run that was replaced (`DroneRuntime.otherRunEntries`; they kept each
replaced run alive until a frame of the next one ran, so the paused diagnostics page grew by a run per probe: 284 -> 2358
MiB after GC over Multiverse's `vanilla` mode). The Android unit test `ReplacedRunsTest` checks the same with weak
references and `System.gc()`. The page cannot force a collection itself, so the heap after a forced GC is measured from
outside through CDP (`.build/hs/leak/`, not part of the suites).

The diagnostics modes also run with Hyperspace (F2) and Hyperspace + Multiverse (F3) data. There they check the rules, not
vanilla's data: `gameplay-parity` holds back its steps that compare with captures of native vanilla (seeds, draw counts,
pools, maps, stores) and reports them as not run, and the rule checks that hold vanilla's numbers or classes follow the
mod's. They do not establish parity with native Hyperspace; the full runs and `hs-*` suites do. Multiverse has about 1,600
ship specifications and 340 player ships, so `BASELINE_MODE_TIMEOUT_MS` raises the 180 s limit per mode for `encounters`,
`ae`, `vanilla` and `campaign`. `BASELINE_ENCOUNTER_BATCH=<n>` runs `encounters` in slices on a fresh page and
`BASELINE_ENCOUNTER_RANGE=<first>,<end>` limits it to part of the sorted list; a slice with failures does not stop the next.
`BASELINE_CAMPAIGN=ae` or `vanilla` runs one half of `campaign` (its six AE or six vanilla traversals) on a fresh page,
so the job starts one engine: a second Multiverse engine start in the same page adds several GB within seconds (the
whole mode peaks at 6.5 GB without pixels, one half at 2.8-3.0 GB); the two halves together have the whole mode's twelve checks.
`FTL_NOPIXELS=1` (`tests/lib/nopixels.mjs`) runs the modes without pixels: every WebGL draw and clear is a no-op and
each texture gets 1x1 storage without its image (the engine keeps its own sizes and runs every update and render call),
so Chromium's software GL holds no texture memory (0.8-1.1 GB of a Multiverse mode) and spends no CPU rasterising.
`readPixels` and the game canvas's `toDataURL`/`toBlob` throw then, so a check that reads pixels fails instead of
comparing blanks; `baseline.mjs` leaves it off when a selected mode reads the canvas (`gameplay-parity`:
GameplayParityAssets7/Jump6/PsB4Checks through BrowserPixels) and prints `NOPIXELS on` or `NOPIXELS off: <mode> reads
the canvas`. `FTL_NOPIXELS_FORCE=1` applies it anyway (to show that such a mode then fails).

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

`gameplay-parity`'s `GameplayParityCombatRulesChecks` and `GameplayParityShipRulesChecks` hold power and ship-interior rules of the original (parity sweep
C1): an ion hit takes shield bars one at a time (an odd bar stays on) and the bars climb
back to the player's choice once the lock ends; damage keeps that choice until the repair; the evasion table by engine
power, with a pilot and on autopilot; the clone bay's jump heal by its level less damage, not ion; an arrival after a
safe beacon ends the ion locks of shields, engines, weapons and piloting; a fire burns out below 11% oxygen in vanilla
(a whole percent) and at 10% under Hyperspace. `hs-systems` checks that a hacking pulse keeps the temporal system from
starting and that a target ship at hull 0 keeps its dilation. The oracle sessions in
`tools/hs-oracle/sessions/ps-c1-power.json` (probe `hs_oracle/ps-c1.lua`) dump power, health, locks, evasion and
`additionalPowerLoss` per step for `sweep/twin/run.sh`.

`gameplay-parity`'s `GameplayParityCrewC5Checks` holds the crew rules of parity sweep C5: boarders left on an enemy whose
crew is dead come home to the piloting room with the CREW_STUCK box when no crew of the player's is at home and the
player's teleporter has no health (WorldManager::OnLoop), on the first loop after the crew-kill box closes; not while
the enemy is hostile, and not while the teleporter has a bar.

`gameplay-parity`'s `GameplayParityPsB1Checks` (parity sweep B1, vanilla, F2 and F3) fires one volley of Flak I, Flak II
and Adv. Flak (and, under Hyperspace, up to twelve of the mod's flak guns) from the player and Flak I from the enemy, and
wants every piece in the target's space, aimed within the blueprint radius of the room and reaching its point (under
Hyperspace all pieces but one used to fly off). It also loads NEBULA_REBEL and wants both ships' sensors capped at 0
(StatusEffect::GetNebulaEffect), their bars crossed, the icon grey and no crew-slot icon, as the original's box shows in a
nebula. The native sides: `tools/native-capture/sessions/ps-b1-van.json` (1.6.14: a Flak I volley, a nebula beacon's
system boxes) and `tools/hs-oracle/sessions/ps-b1.json` (HS and MV: probe `hs_oracle/ps-b1.lua` logs each piece's
target and the sensors' power cap in the nebula).

`gameplay-parity`'s `GameplayParityPsW5Checks` (parity sweep W5, vanilla, F2 and F3): the pulsar's first goal from a known
global-stream draw and its later goals in whole milliseconds; no flare fires and no pulse ions on a ship whose
`browserJumping` is set (both do at rest); the scene draw log (`AbstractEnvironment.browserDrawLog`) of an asteroid field
(backdrop, planet, layers), a sun (backdrop, sun), a pulsar (dull stars, pulsar), a sun with stacked asteroids, and a
nebula with stacked asteroids and sun (clouds and asteroid layer only); and a cleared beacon's statuses (an event's limits
gone, a nebula location keeping the sensors cap of 0). The native side: `tools/native-capture/sessions/ps-w5-van.json`
(1.6.14: w5-rev, a nebula beacon left and revisited, the sensors capped again; w5-rev2, TEST_EVENT2's limits; both load
their event through the console, whose copy of the event is never cleared, so the limits return there).

`gameplay-parity`'s `GameplayParityPsFu1Checks` (parity sweep follow-up FU1, vanilla, F2 and F3) holds the rule of a crew
member at a capped system: engines capped at their level stay manned and boosted, one bar lower the engineer stands idle
at the console with no evasion bonus, no crew slot on the box and no "unmanned" line; sensors capped at their level stand
idle with the ion bar, capped one above stay manned; a crew member sent to another tile of capped engines, or of the
start's unpowered weapons, walks back to the console and stands idle (and mans the engines once the cap ends); in NEBULA_REBEL the crew member at the sensors stays idle at the console. The native side:
`tools/native-capture/sessions/ps-fu1-van.json` (1.6.14: typing frames at the sensors and none in a nebula or under
TEST_EVENT2's caps, the ion bars of capped sensors and doors, engines capped below their level losing 5 % evasion and the
crew slot, a crew member walking to the capped sensors console).

`gameplay-parity`'s `GameplayParityPsC3Checks` holds the hit rules of ShipManager::DamageArea and DamageSystem (parity
sweep C3, vanilla and mods): a certain breach (breachChance 10) under a Rock Plating proc breaches, and in vanilla also
ends the proc (hull damage, its number), under Hyperspace keeps it (RESIST); a 9-in-10 breach under a proc is stopped with
RESIST, the system number shows under a proc, a System Casing proc keeps the system's bars and shows RESIST in place of the
hull number (for system damage only and on a destroyed system as each mode does), and a chain boost on personnel damage
adds whole crew units. The rock-armour statistics step counts 600 certain breaches: all hit the hull in vanilla, about 83%
under Hyperspace. `hs-weapons` units' "stat boost load order" checks that the
weapons' `<statBoosts>` load in the original's blueprint order before hyperspace.xml (no unresolved `load=`, an authored
boss-file weapon loading a dlc-file save). The oracle session `tools/hs-oracle/sessions/ps-c3.json` (`ps-c3-dmg`, probe
`hs_oracle/ps-c3.lua`) counts 300 forced DamageArea hits per case on the player's ship (hull and system hits, breaches)
with and without Rock Plating and System Casing, for `sweep/twin/run.sh`.

`gameplay-parity`'s `GameplayParityPsC4Checks` holds drone rules (parity sweep C4, vanilla and mods): the defence drone
target types (`<target>` LASERS, DRONES, and under Hyperspace ASTEROIDS, SOLID, ALL) and the Targetable classes each
accepts (DefenseDrone::ValidTargetObject and Hyperspace's hook); the ion intruder's pulse as an area hit (3 ion locks the
system 15 s, the stun reaches only crew not of the drone's ship); the player's boarding drone when the fight ends with
the other ship still there, in flight and landed (undeployed with no rebuild time, its part back under Hyperspace's
BOARDER_RECOVERY, which Multiverse ships carry); the crew drone light colours. The native frame pair session
`tools/native-capture/sessions/ps-c4-drones.json` (`c4v-d`: a repair and an anti-personnel drone on the Kestrel, paused
and running, on and off) runs with `sweep/vanilla/run.sh` against `tools/ui-capture.sh`.

`gameplay-parity`'s `GameplayParityPsU3Checks` (parity sweep U3, vanilla and mods): 1.6.14's stale hull number (a Rock
Plating proc on a hit without hull damage shows ShipManager::iLastDamage again; Hyperspace shows nothing; the player's
value lasts one frame) and the upgrade levels box's tiles (InfoBox::OnRender: two whole details_base_B in the ship window,
one in the hangar), and SwitchPlanet's planet size (the texture's, not the image list's w and h). The native strip sessions `tools/native-capture/sessions/ps-u3-van.json` (generator
`.build/ps-u3/gen/gen_v.py`; u3v-a combat save running, our weapons and the enemy blown up; b five races, idle, walking,
boarders; c Crystal and Lanius, four weapon families on autofire against a Mantis fighter; d drones, bomb, cloak on and
off; e enemy shots on full shields; f a combat and a boarding drone; j a jump) run with `sweep/vanilla/run.sh` against
`tools/ui-capture.sh`; the capture's X server has caps lock on, so the generator types each console command with its case
swapped (`CREW engi` reaches the game as written). `tools/hs-oracle/sessions/ps-u3-hs.json` `ps-u3-img` (Multiverse,
probe `hs_oracle/ps-u3.lua`, fixture event PSU3_IMG with one-image lists) compares an event's `<img>` with its twin.

`hs-statcrew` (F3) ends with `BrowserPsC6Checks` (parity sweep C6, Multiverse): an ability's `<event>` goes to the
Hyperspace event queue when the power activates (unique_tully's TULLY_CALL_AIRSTRIKE, unseeded, no label); a power's
room damage on its own ship without shipFriendlyFire takes (damage + persDamage) x 15 from each crew member of the
room (100 to 55 for damage 1, persDamage 2); a jump adds chargesPerJump as a float (an uncharged power goes from
2147483646 to -1073741826). The oracle sessions `tools/hs-oracle/sessions/ps-c6.json` (generator `gen-ps-c6.py`, probe
`hs_oracle/ps-c6.lua`, F3; the hyperspace.xml append redefines a few Multiverse races no script names, one power or
boost kind each) run scenario sets on game time and write crew, power and ship rows at time marks: `ps-c6-pw` (power
effect kinds), `ps-c6-sb` (boost types, conditions, stacking, augment boosts), `ps-c6-en` (powers aboard the enemy, the
AI's use, mind control resistance, every ship and crew target), `ps-c6-js`/`ps-c6-js2` (jump and save) and `ps-c6-ui`
(crew box frames, paused). Twin map `.build/hs/sweep/fixtures/ps-c6/map.json`; `.build/hs/sweep/jobs/ps-c6.sh` replays
pw, sb and en against the recorded oracle.

`gameplay-parity`'s `GameplayParityPsU4Checks` (parity sweep U4, vanilla and mods): PlaySoundMix's absolute volume
(explosion1 at 0.25), a loop sliding out over 500 ms of real time, a sound, loop and track the files lack (silent; the
playlist stalls on the missing track), an empty playlist fading to silence, the battle flag set with no track playing,
door sounds as the animation starts (and none for a door turned back halfway), and under Hyperspace a crew power's
effect, power and finish sounds in the original's order; and two host rules of report 106 (nothing of the original's): a
change of the sound volume reaches every playing sound, also those after an ended one, and a loop that found no free
voice starts again once one is free. `web/self-tests.mjs`' audio clock (and `tests/audio.mjs`) keeps a
missing sound or music file's source AL_INITIAL, and asks again for a sound whose load failed once when it is next
played (not for a missing one); an effect that has waited two seconds for an audio context that does not resume gives
its voice back (a loop and the music wait on). `tests/audio.mjs`' music memory check plays six sectors' cross-fades
and combat toggles with 5-minute tracks: at most 192 MiB held (as Android counts it), each version decoded once per
sector, music dropped before an effect, a playing, fading or paused track kept, a dropped track decoded again and
playing, every decoded channel handed to script (so Chromium can collect a dropped one), and a burst of track changes
decoding only its last track (fails on the unlimited audio.mjs: 202 MiB held). Android's mixer: `AudioMixerTest` ([ANDROID.md](ANDROID.md#testing)). Sound trace pairs: `BrowserEngine.soundTrace` logs what our engine plays,
`tools/native-capture/sessions/ps-u4-sound.json` runs the same sessions in `FTLGame-sound.exe` (`sweep/vanilla/run.sh`,
`EXE=sound`) and `tools/ui-capture.sh`, and `tools/native-capture/sound-pair.py <native.bin> <ui-capture log> --session ID
--sync KIND:NAME` pairs them (`--hs 1` reads the Hyperspace oracle's ring of `tools/hs-oracle/make-sound-exe.py`, session
`tools/hs-oracle/sessions/ps-u4-hs.json`). `.build/hs/sweep/jobs/ps-u4.sh` replays the recorded traces.

`gameplay-parity`'s `GameplayParityPsFu4Checks` (parity sweep FU4, vanilla and mods): a selected crew member's green
sprite tint (an unselected one none), the console's LOAD dropping the crew selection and a jump keeping it, augments
listed by name, a Lua AddAugmentation with the augment slots full returning false and moving no augment out (with Lua;
oracle ps-m3-a sys|autofed), the crew-slot icon lifting a box's tooltip column by 16 or 24, a popup queued before the option goes
off still shown, the armament tooltips naming weapon<n> / drone<n> by the box's own number (the fifth weapon box
"None"), and the system boxes and jump button getting no mouse (a click on the engines does nothing) while a target is
being picked. `tools/native-capture/sessions/ps-fu4-van.json` (1.6.14 frame and sound pairs: crew selection, a weapon
armed over the HUD, damaged and capped bar glows, selling a charging missile weapon, upgrade undo, a drone slot to the
hold, hover beeps, the main menu) and `tools/hs-oracle/sessions/ps-fu4-hs.json` (ps-fu4-w5: five weapon boxes and two
drone boxes, their numbers and tooltips) hold the native sides.

`interaction`'s probe starts with `GameplayParityPsU2Checks` (parity sweep U2, vanilla and mods): every hotkey of the
original's Settings::ResetHotkeys with its default key, a key taken from another hotkey leaving it unbound, the
power_<n> / un_power_<n> keys on the bar's n-th powered
system (Shift takes power away), no key while the ship jumps (Space, Escape, U), Space over the event box setting the
pause that outlasts the box (not over the ship window), the crew name's cursor (Left, Home, Delete, End, Backspace,
typing at the cursor), the "no stations" crew message, the console's FUEL floor at 0, RICH's amounts, LANG's code and SHIP <layout>,
the armament box hotkeys of Hyperspace's AdditionalWeaponSlots.cpp (past four weapons, without weapons, past six drones),
and under Hyperspace the wheel over the upgrades tab turning the crew list page. A prepared fixture's boot.pack holds
the engine resources (engine/...) of the day it was prepared and they replace the image's: with an xftl_hotkeys.xml
older than the power keys, the list and power-key checks print "skipped: the fixture's boot.pack has an older
xftl_hotkeys.xml" and pass; re-prepare the fixture to run them. The native session `tools/native-capture/sessions/ps-u2-keys.json` (`u2-keys`, console
exe, settings.ini binding power_1/un_power_1/power_2/power_7 to F9-F12; `u2-keys2`: Space over the event box and the
ship window, LANG de / zh-hans / en) shows the same on 1.6.14 through `sweep/vanilla/run.sh`. `tests/window-places.mjs`
opens TRADER_CIV for the event box in vanilla and plain Hyperspace: ASTEROID_EXPLORE is unique there and a run whose
sector already took it shows no box, as the original.

`GameplayHudU1Checks` (also in `gameplay-parity`, parity sweep U1) pins the desktop HUD's mouse rules found in the frame
pairs of `tools/native-capture/sessions/ps-u1-hud.json`: a right press that drops an armed weapon leaves the system box
under it alone (CommandGui::RButtonDown); crew added while paused take a box hover at once; a drone slot has the
original's tooltip (description, state, hotkey, rearrange); the system tooltip keeps the original's trailing spaces
(they widen the box); GOD while paused shows the new free reactor bars at once; a subsystem box under a drone box keeps
its tooltip over the drone's (CommandGui::MouseMove); the hull warning is one 6 s message whose timer a second fall
does not restart (ShipStatus::OnLoop); the shields box takes the hover in its icon square and shows its tooltip over its
bars' column too (SystemBox::MouseMove).

`gameplay-parity`'s `GameplayParityPsW4Checks` holds the enemy rules of parity sweep W4: the player's aim at an escaping
enemy stays through its countdown and its jump-away and the charged weapon fires at the leaving ship (WeaponControl::OnLoop
and the oracle), which plays jumpLeave once; no hacking while either ship jumps or at a dying target; the enemy's medbay is powered
for crew below a quarter of their health or on a healing task, not for hurt crew only standing in its room; and in
vanilla the flagship's stage 3 barrage leaves the ship's centre on several headings with a sound per shot. The oracle
session `tools/hs-oracle/sessions/ps-w4.json` (`ps-w4-ai`, F3, probe `hs_oracle/ps-w4.lua`, ship `PSW4_ENEMY` and events
`PSW4_*` in the tracer's append files) records the enemy's power after ion and damage, the rooms its shots aim at over
about 150 game seconds, and the escape (jump flag, the player's aims and shots, the ship gone), for `sweep/twin/run.sh`
with the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-w4.lua", "prefix": "PH:"}`.

`gameplay-parity`'s `GameplayParityPsC2Checks` holds the rules of parity sweep C2: player crew aboard an enemy that is
destroyed die (the clone bay brings them back), aboard one that escapes or when the location is cleared (a jump away
from it, the console's `LOAD`) they are lost for good, each with the lost_crew flag (WorldManager::OnLoop and
ClearLocation), while an enemy boarder aboard the player's ship stays after a `LOAD`; a mind-controlled crew member that
dies is dropped at once and the system locks without the end sound (MindSystem::OnLoop); Hyperspace's hackingIonFix
works while the hacking drone is out (bHacking); and a send while the enemy jumps away does nothing and is not kept
(CompleteShip::InitiateTeleport). The oracle sessions `tools/hs-oracle/sessions/ps-c2.json` (F3, probe
`hs_oracle/ps-c2.lua`, the `PSW4_*` ship and events) record mind-controlled crew on either ship (`ps-c2-mc`), a hacking
pulse on each player system (`ps-c2-hk`) and boarders when the enemy is destroyed, escapes or is replaced by `LOAD`
(`ps-c2-bd`, `-be`, `-bl`), for `sweep/twin/run.sh` with the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-c2.lua",
"prefix": "PH:"}`.

`gameplay-parity`'s `GameplayParityPsFu2Checks` holds the crew rules of parity sweep follow-up FU2: no crew-slot icon
above a system while boarders stand in its room (ShipSystem::BlockedBoosted's bOccupied); an enemy-side boarder that an
enemy ship brings home becomes that ship's crew (CrewMember::iShipId); under Hyperspace a dying crew member stays
functional (CrewMember::Functional is !bDead) through its death hold; a Slug lights the rooms that share a wall with its
own, door or not. The oracle sessions `tools/hs-oracle/sessions/ps-fu2.json` (F3, probe `hs_oracle/ps-fu2.lua`, the
`WP5T_*` and `PSW4_*` ships) follow every crew member's room and goal on the game clock: `ps-fu2-brd` and `-bd1-1/2`,
`-bd2-1/2` (the enemy's boarding with no, sabotage and invasion boarding AI; `-bn1`, `-bn2`, `-bnw` from the first
running frame), `ps-fu2-fb` (a crew member added to a ship that does not board), `ps-fu2-mc` (the response to its own
mind-controlled crew member, then the release), `ps-fu2-rc` (an event boarder recalled by an enemy ship), `ps-fu2-zf` (a
Zoltan's bonus power through its death hold), `ps-fu2-pl` (eight crew added to a full piloting room) and `ps-fu2-sl`
(Slug vision with the sensors broken), for `sweep/twin/run.sh` with the map `{"lua":
"tools/hs-oracle/tracer/data/hs_oracle/ps-fu2.lua", "prefix": "PH:"}`.

`gameplay-parity`'s `GameplayParityPsFu3Checks` holds the combat rules of parity sweep follow-up FU3: the HUD weapon
tooltip's crew damage (WeaponBlueprint::GetDescription: only a positive value, in health points, x15); a combat or
boarding drone equipped while a ship is at the beacon does not launch until the next ship (vanilla; under Hyperspace the
combat drone launches and the boarding drone waits); Hyperspace's enemy aim with more shots than rooms (a fresh pick per
point past the room count); a custom augment's laser crystal shard aims at a room centre. The oracle sessions
`tools/hs-oracle/sessions/ps-fu3.json` (F3, probe `hs_oracle/ps-fu3.lua`, the `PSFU3_*` ships and events and `PSW4_*`)
record the weapon outcomes per family against fixed targets (`ps-fu3-out0` unshielded, `-out2` with shields and
evasion, `-heavy` 80 Heavy Laser volleys), the enemy's volleys under a forced recharge (`-vol`, `-volq` per frame),
drones equipped in a fight (`-pdrone`, `-pdrone2`), the enemy's boarding drone through the fight's end (`-edrone`) and
its pod in flight (`-pod`), crystal shards (`-shbomb`, `-shlaser`) and erosion (`-erosion*`), for `sweep/twin/run.sh`
with the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-fu3.lua", "prefix": "PH:"}`; `sweep/jobs/ps-fu3.sh`
compares out0 and out2 with `ps-fu3-cmp.py`. `tools/native-capture/sessions/ps-fu3-van.json` (1.6.14) holds `fv-drone`
(drones equipped in a fight) and `fv-tip` (the weapon tooltip).

`gameplay-parity`'s `GameplayParityPsFu5Checks` holds the world rules of parity sweep follow-up FU5: a location left with
its hostile ship keeps its hazard (LocationEvent::ClearEvent's branch on the event's saved ship: asteroids stay, a storm
stays a storm without the nebula's sensors cap; also once the ship was destroyed on a return, on every later clear and
after a reload), without one only a nebula is left; the event's flags go either way; a new event at the beacon has no
saved ship; the asteroid field's front layer is drawn over the player's ship while the jump zoom runs, and only then;
under Hyperspace a ship naming a missing weapon gets a Basic Laser (vanilla: none); under Hyperspace an event's crew
loss with none of the own crew aboard takes nobody (rewards.crew 0); the enemy's boarding drone aboard the player's
ship explodes where it stands when the fight ends with its ship still there. The oracle sessions
`tools/hs-oracle/sessions/ps-fu5.json` (F3, probe `hs_oracle/ps-fu5.lua`, the `PSFU5_*` ships and events) record a ship
naming a missing weapon, drone and augment (`ps-fu5-miss`), an event's weaponOverride naming a missing weapon (`-ovr`),
an event's crew loss with no own crew aboard (`-nocrew`) and with one left (`-last`); `ps-fu5-b.json` (seed 505, jumps to
the beacon at 560,421 and back) a location left with its ship in asteroids (`-keep`), in a storm (`-storm`) and after
the ship was destroyed (`-clear`), the enemy's surrender accepted (`-surr`), the enemy's boarding drone at the fight's end
in frames 0.15 s apart (`-edrone`, probe `ps-fu3.lua`; `-edrone2` unpaused after the box) and the game over after
the last crew died in an event (`-last2`). Their twins run with `sweep/twin/run.sh` and the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-fu5.lua",
"prefix": "PH:", "rules": [...]}` (`.build/hs/sweep/fixtures/ps-fu5/map.json`: 360 frames for `jump_arrive`, and key 1
in the PSFU5_SURR and PSFU5_LOSE boxes, as the twin's plain pick closes a box without choosing) and `rows.py ...
--multi box,loc`.

`gameplay-parity`'s `GameplayParityPsFu6Checks` holds the crew and combat rules of parity sweep follow-up FU6: the enemy
AI's volley keeps its aim as `ProjectileFactory.lastTargets` (one point per shot, cleared when the weapon loses power);
CREW_STUCK brings home a boarder in its death hold (CountCrew counts crew until bDead) and it dies at home; a
mind-controlled crew member makes no combat task on its own ship until it fights. The oracle sessions
`tools/hs-oracle/sessions/ps-fu6.json` (F3, probe `hs_oracle/ps-fu6.lua`, written by `gen-ps-fu6.py`; ps-m1's start and
hs_options) record M1's scripted fight on a game clock (`ps-fu6-fight`: the console steps' game time, every shot, shield
hit, hit, the enemy's power, the escape and the jump), the response to the enemy's mind-controlled pilot with the
station's manned flags (`-mc`), a human_medic through its death hold (`-heal`), CREW_STUCK with a boarder dying
(`-stuck`) and AddSystem(13) with dualMedical on and off (`-dmed`, `-dmed0`), for `sweep/twin/run.sh` with the map
`{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-fu6.lua", "prefix": "PH:", "console": {"pre": 0, "post": 37,
"pick": 30}}`. The map's `console` key (twin.py, FU6) gives each console step 0.6 s and each pick 0.5 s of game time, as
the oracle's driver takes (its console pauses the game while it types); `sweep/fixtures/ps-m1/map.json` uses it, so
`hs-ps-m1` compares M1's fight, escape and jump rows.

`gameplay-parity`'s `GameplayParityPsFu7Checks` holds the rule of parity sweep follow-up FU7: the enemy's boarding
request counts its crew until bDead (ShipManager::CountCrew 0x49c180 from ShipAI::OnLoop 0x625c50), so a crew member in
its death hold still counts and one a script killed (Lua Kill) no longer does. The oracle sessions
`tools/hs-oracle/sessions/ps-fu7.json` (F3, probe `hs_oracle/ps-fu7.lua` after ps-fu3's or ps-c6's, written by
`gen-ps-fu7.py`) record the fires a laser lights on ps-fu3's fixed target, per hit and per burning cell (`ps-fu7-fire`:
180 Burst Laser II hits and 60 DamageArea hits with fireChance 10), and C6's en set, its mind control scenario then its
targets one, and the targets one alone with every crew member of both ships and the enemy teleporter on the game clock
(`-en`, `-mt`, `-t`). Their twins run with a map whose `lua` is the two probes joined (ps-fu3.lua + ps-fu7.lua; ps-c6.lua
+ ps-fu7.lua with ps-c6's rules). The `ps-fu3` job also runs `ps-fu7-fire` against its recorded oracle run (fixture
`sweep/fixtures/ps-fu3/oracle/ps-fu7-fire`), and `ps-fu3-cmp.py` compares fire counts (burning cells, one or two per
fire roll) with twice the Poisson spread.

`gameplay-parity`'s `GameplayParityPsB2Checks` holds user bug B2: a beam takes max(1, damage) off a super shield when its
swipe passes 0.33 and, for a length over 20, 0.8 (BeamWeapon::OnUpdate 0x438880, CollisionCheck's DamageShield with a
new selfId, 0x438c30), in both directions and from a beam drone (Mini Beam and Fire Beam 2 points per beam, a drone's
beam as its blueprint gives). The oracle sessions `tools/hs-oracle/sessions/ps-b2.json` (F3, probe `hs_oracle/ps-b2.lua`,
the `PSB2_*` ships and events, written by `gen-ps-b2.py`) record every zap on a super shield kept at 5: the player's
beams (1, 2, 3 and 0 damage, a pinpoint) and beam drone at an enemy's (`ps-b2-p`), the enemy's beams (`-ew`) and beam
drone (`-ed`) at the player's. Their twins run with the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-b2.lua",
"prefix": "PH:"}`; `sweep/jobs/ps-b2.sh` compares them with `ps-b2-cmp.py` against the recorded oracle runs
(`sweep/fixtures/ps-b2/oracle`). `tools/native-capture/sessions/ps-b2-van.json` (1.6.14, written by
`gen-ps-b2-van.py`) holds frames of the super shield bar: the player's beam drone at a Zoltan ship (`bv-drone`) and
the player given a Zoltan shield by AUG against LONG_ELITE_MED's Halberd and PLAYER_SHIP_CIRCLE_3's beam drone (`bv-tw`,
`bv-td`). B2b adds to the check when a Zoltan shield fills (ENERGY_SHIELD gained in a fight fills it once; emptied,
removed and added again or reloaded it stays empty; the original's save keeps 0|0 without the augment and an imported
3 of 5) and an enemy built from a player blueprint (PLAYER_SHIP_STEALTH,
PLAYER_SHIP_CIRCLE's beam drone) staying on the enemy side, and the sessions `ps-b2-ss` (super.first/second frame by
frame after events, Lua, console AUG and MV's shield augments) and `ps-b2-side1..3` (enemies from player blueprints) to
the job. In the twin the `ev` step picks the event's choice with key 1 (map rule), as the oracle's pick does.

`gameplay-parity`'s `GameplayParityPsB3Checks` holds B3 (found on the way of B2): the blueprint defaults of a tag left
out, as ProcessWeaponBlueprint (1.6.14 0x607df0) and the other parsers keep the constructors' values (a weapon's power
and cooldown 0, WeaponBlueprint::shots 0 and 2 for every beam, length -1; a drone's level 1 and `<level>` 2 as
targetType 4 in file order; an augment's, a system's and a crew's cost 0 and the rest 0 or false), and a weapon of
power 0 on the player's ship: powered with no bar, charged with the weapon system at 0 bars and, under Hyperspace, its
volley held until the system has a bar (WeaponSystem::GetPreparedProjectile 0x636a10). The oracle sessions
`tools/hs-oracle/sessions/ps-b3.json` (F3, probe `hs_oracle/ps-b3.lua`, the `PSB3_*` blueprints and events, written by
`gen-ps-b3.py`): `ps-b3-gen-e/-n/-h` build enemies with the console's `LOAD` (and one event with an auto_blueprint)
on Easy, Normal and Hard with the stream reseeded in GENERATOR_CREATE_SHIP and record the reactor, every system's
power, maximum and limit at GENERATOR_CREATE_SHIP_POST and 3 s later, and the armament; `ps-b3-arm` the blueprint
fields and the weapon without `<power>` on both ships; `ps-b3-zol` an enemy with Zoltan crew in powered rooms;
`ps-b3-flag` the flagship's stages (hr-flag's route) and each stage's wreck; B3b: `ps-b3-zw`/`-zx` ZOLTAN_BOMBER's Zoltans
(only the first Zoltan of a race hyperspace.xml leaves out is an EnergyAlien), `ps-b3-zy` and `ps-b3-zz` (MU_ZOLTAN_SCOUT)
a Zoltan walking in and out, dying, mind-controlled, boarding either way, under ion and with the reactor cut, and
`ps-b3-wk` an ordinary enemy's wreck (AUTO_ASSAULT, MU_UNION_OUTRIDER). The twins
take the session's difficulty from the probe's `difficulty` attribute (BrowserHsWp5CrewDiagnostics);
`sweep/jobs/ps-b3.sh` compares them with `ps-b3-cmp.py` against the recorded runs (`sweep/fixtures/ps-b3/oracle`);
the comparer counts as known a subsystem's power at GENERATOR_CREATE_SHIP_POST (the original still has the template's,
equal a frame later); a wreck is compared as what each kill did (a system's bars kept or dropped, the reactor's change,
the weapons and drones off, a drone that was out staying out). `GameplayParityPsB3Checks` also holds B3b's
`zoltanRegistry` (the first such Zoltan an EnergyAlien, the next a plain crew member without a bonus bar), `wreck`
(AUTO_ASSAULT's weapons and drones off with their bars kept) and `flagshipWreck` (the artillery at 0, the rest kept).
`tools/native-capture/sessions/ps-b3-van.json` (1.6.14, `gen-ps-b3-van.py`) holds frames of DRONE_LASER, a weapon
without `<power>`, given by the console (`b3v-arm`).

`gameplay-parity`'s `GameplayParityPsB4Checks` holds the B4 player reports (vanilla, F2, F3): a bomb launcher's
`<delayChargeAnim>` keeps its launcher closed until 98% of the charge (SetChargedLevel; a laser moves through its whole
charge); a dying Zoltan draws its own explosion alone, centred on its cell (pixels against the frame at 17 - 32), with no
colour strip; the lock ring starts 5 degrees past the top, and the touch HUD's system box draws it (two touch renders
that differ only in the lock timer); the touch cooldown bar shows whole segments of the iPad mask; the backup battery's
unused bars sit at the bottom of the reactor column in their (230, 110, 30) outline (on the rendered HUD); the beacon map
draws its nebula clouds before the lines and beacons; a fleet beacon holding the flagship has no ASB, after a reload too.
Android: `TouchLockCooldownTest` (the cooldown rows and ring segments). The native sides:
`tools/hs-oracle/sessions/ps-b4.json` (F2 without the tracer, which F2 cannot load (DEFAULT_TIMER); Caps Lock is
pressed first, the Lua is inline and prints `PB4` lines: `ps-b4-hud` frames of a bomb charging and firing, ion locks,
the cloak and battery bars and the reactor; `ps-b4-die` a Zoltan and a human dying on both ships; `ps-b4-flag` the last
stand's flagship moved onto a fleet beacon, the scene's environment and bPDS) and
`tools/native-capture/sessions/ps-b4-van.json` (1.6.14 twins: `b4v-hud`, `b4v-die`, `b4v-map-*`; the 1.6.14 console
has no SYS or kill command, so those twins show the bomb launcher and the map).

B4b (the same checks): a dying vanilla Zoltan in Multiverse runs its whole death (Multiverse's instant heal out of
combat calls DirectModifyHealth(9999), which does nothing at 0 health); the battery's reactor bars flash white for 2 s
after it starts (`batteryFlash`); the flagship arriving during the last stand's out-of-fuel wait ends the ASB
(`flagshipArrivesOnWait`), and a fleet beacon the flagship left has its ASB again (`flagshipNoAsb`, after reloads); a
sector without recorded clouds draws its puffs before the lines; the map markers take StarMap::Open's two angles and
turn 18 degrees a second (`mapMarkers`). Native sides: `tools/hs-oracle/sessions/ps-b4b.json` (`ps-b4b-zdie` F3 with the
tracer: Zoltans killed out of combat, healed while dying, a clone bay; `ps-b4b-flash` F2: frames after the battery
starts; `ps-b4b-flagleave` F2: the flagship's beacon, away, the flagship moved on, back).

Info boxes and the control under them (player report 202; `gameplay-parity`'s `GameplayParityInfoBoxChecks`, every
mode): on a phone's touch canvas (1707 x 768), a tablet's (1024 x 768) and the desktop's (1280 x 720; a phone held
upright on the website shows the same canvas, the Android app is landscape only) the store's buy tab lays out the info
column of every weapon, drone, augment, system and crew race of the loaded data and of a 60 line text; the sell tab and
the equipment screen, with and without the touch layout's over-capacity box, lay out the 24 longest and the 60 line
text. Each time every box is on the screen, the boxes and the BUY / SELL box do not cover each other, the control is on
the screen, text that does not scroll ends on the screen (on touch: inside its box), and text that scrolls can be moved
to its last line. The same holds for the hangar's boxes (the ship list's box at its two heights and the hangar's own
box, with every player ship's description and the 60 line text), an event's reward box (the 24 longest items and the 60
line text) and a confirmation with the 60 line text (window and buttons on the screen). Text that scrolls is cut where
its frame's art ends (report 202b), measured on the art as the game draws it: the info boxes' frame (window_outline.png,
drawn alone on black) against every scrolled box's clip, top and bottom; the confirmation's own frame (confirm_window.png,
a column left of the text: the border above, the line above the buttons below); the welcome box's frame above its text
and its choice below it. The clip may lie at most 2 rows inside that edge. The same run then switches the
rule off (`InfoStack.enabled`) and the 60 line text must break each of these layouts on every size. `tests/overcap.mjs` (`hs-overcap-*`) hovers an item with the real mouse: its boxes are on the screen; under
Multiverse the toggle recon drone's boxes end at the screen's edge and the wheel moves both texts to their end
(`overcap-<mode>-1e-info-recon.png`, `-1f-info-recon-wheel.png`).

Players' reports on 2.1.1 (`gameplay-parity`'s `GameplayParityFixContentChecks`, every mode): a sector's rarity list
(`sectorRarities`: the pool of every sector of the loaded data is made, a rarity of 6 is listed and never drawn;
`hackedSector`: Multiverse's SECTOR_HACKER_UNIQUE, whose list gives mantis_free 6, is generated and arrived at); the
over-capacity slot (`overCapacitySwap`: the waiting weapon, drone or augment and an owned one change places whichever
is carried onto the other, with the mouse and with a finger through the touch router: a tap, then a press that carries);
the beacon map (`pickedBeaconLines`: the hovered or picked beacon's connections are drawn in yellow, the ship's in
green, and under Hyperspace with every connection shown each connection of the sector is on the map; `mapMarkerPlace`:
the ship's and the flagship's marker art sits beside its beacon, level with it, in the mouse and the touch layout);
`partRowSheets`, from a 2.1.1 crash log: every strip on a sheet whose declared height is not whole frames (Multiverse's
Vampweed Foreman) gives each frame where the original's rows from the declared bottom edge put it, and the Foreman's
walk down is built; `emptyEventBox`, from a 2.2.3 crash log: an event box with neither an event nor a synthetic box
closes when drawn (mouse and touch layout) instead of stopping the game. Android unit tests `SectorRarityTest`: the rarity list as EventsParser reads it and the weights of
the pick; `AnimationSheetTest`: the frame rectangles of such a sheet and of one of whole rows. The store's
sold boxes are in `hs-ships` (the `store` probe buys the first box of every category, draws the page and draws it again
from the store's save; a bought crew member or augment ended the run). On the emulator `tools/android-touch/overcap.mjs`
carries the waiting augment and weapon onto an owned one and back with one finger.

`gameplay-parity`'s `GameplayParityFixCrewChecks` holds four crew reports of release 2.1.1 (vanilla, F2, F3):
`mindControlledDiesCloned` and `mindSystemCrewDiesCloned` (HS105: a crew member that dies mind controlled is its own
side's again when cloned, after reloads too; the second goes through a real enemy mind system); `mindControlledDoors`
(107/110: a mind-controlled crew member walks through the blast doors of its own ship, a lockdown holds it, the path
search still counts those doors as sealed) and `forcedOpenDoor` (a blast door broken down and shut again lets the
boarder through while it is forced open); `targetsSpread` (109: a new target is drawn for every shot, both sides, while
two fighters on one slot stay on each other); `boardersAfterTheFight` and `crewKillStopsSabotage` (111: boarders stop
sabotaging and fighting when the ship is not hostile any more, boarders aboard the player's ship go on). The Ai2 check
`sabotageLatch` holds the same rule for boarders aboard the player's ship. Native sides:
`tools/hs-oracle/sessions/fix-crew.json` (F3 with the tracer and the probe `data/hs_oracle/fix-crew.lua`: `fx-clone`,
`fx-doors`, `fx-spread`, `fx-sab`, `fx-jump`).

Round 24 (release 2.2.3 reports), vanilla, F2, F3: `GameplayParityPsFu6Checks`' `mindControlEndsOnLeaving` and
`mindControlEndsOnDestruction` (the enemy's mind control holds a crew member of the player's; it is the player's again
at the next beacon after a jump, and on the frame the enemy's hull reaches 0: MindSystem::ShipDestroyed from
WorldManager::ClearLocation and ShipManager::OnLoop, with ShipAI::OnLoop's DeactiveWeapons on a destroyed ship), and `GameplayParityFlow3Checks`' `noFuelBesideNeutralShip` (no
fuel beside a hostile ship locks the desktop and the touch JUMP button; beside the same ship no longer hostile both stay
active, the map opens with WAIT and the distress toggle, the toggle, the empty tank and the open button outlive a save,
the wait clears the ship that was not hostile, as a jump's new location does, and with the beacon on draws from
NO_FUEL_DISTRESS (vanilla data), fuel ends the lock). Native sides:
`tools/hs-oracle/sessions/r24-fuel.json` (F3, probe `data/hs_oracle/r24-fuel.lua`, written by `gen-r24-fuel.py`:
`r24-nofuel`, `r24-mcjump`, `r24-mcdead`).

`gameplay-parity`'s `GameplayParitySaveMigrationChecks` holds the save revision and the migration step of loading a run
([SAVES.md](SAVES.md), "Save revision and migrations"; vanilla, F2, F3): `revisionRoundTrip` (a written run carries the
current revision and keeps it over a load; a newer one loads unmigrated), `stuckCrewFreed` (a run written as release
2.1.1 wrote it, without a revision, with a crew member mind controlled and no system holding it: free after the load,
the run written again is current and is not migrated again), `stuckBodyFreed` (the same mark on a body in the clone
bay), `idleSystemDoesNotHold` (the same beside the enemy's mind system between two uses), `heldCrewStays` (a crew member the enemy's mind system holds stays with it), `currentRevisionKeeps` (the stuck
state in a current run stays), `importedOriginal` (vanilla data: a run from the original game in its wrapper does not
pass the migration step). Android: `SaveMigrationsTest` (`android :app:testDebugUnitTest`), the same shared code on a
ship's crew without game data: the revision attribute, the migrations a revision needs, the wrapper, migration 1, and
migration 2 on a run of release 2.2.3 (revision 1).

`gameplay-parity`'s `GameplayParityTemporalChecks` (vanilla, F2: no ship lists the system; F3) takes every ship
blueprint whose systemList has Hyperspace's temporal system. Each player ship (Multiverse's C.E.L. A-C, Rhyme A-C and
the Sylvan C, which starts without it and gets it installed from its slot) on the mouse layout (a click on the box's
speed up button at +(9, 9) and slow down button at +(9, 35), then on a room) and on the touch layout (fingers through
`TouchRouter`: the box, its open panel's two buttons, a room): each button arms its kind, the room starts the dilation
(speeding up or slowing down, the room's dilation set, the time left between 1 and 0 a second on), the buttons arm
nothing while it runs, a jump ends it and frees the system. Each enemy blueprint with the system is built with it and
fights 30 s; at least one uses it. The touch cases failed before the temporal panel had its two buttons (one button,
pressed at no point of the box).

`gameplay-parity`'s `GameplayParityCrewlessChecks` holds report 201, ships without a starting crew (vanilla, F2, F3):
`everyPlayerShipStarts` (player ships of the loaded data, all layouts: the run starts, two seconds on it is not over,
a ship without living crew of its own is automated, and those ships are exactly the ones the data gives no crew; with
up to 60 ships every one is started, with more, as Multiverse's 342, the ones the data gives no crew or an
`autoShipForce`, 14 of them, because `tests/hs-ships.mjs` (job `hs-ships-F3`) asks the same of each of the 342 it
starts anyway: 342 more runs in the parity page took that job to its memory cap),
`crewlessBlueprintIsAutomated` (a blueprint without crew as the player's ship, in vanilla data too),
`automatedShipHasNoDeadCrew` (an automated ship outlives its last crew member; the same ship not automated ends),
`deadCrewEndsTheRun`, `cloneBayAndCrewAway` (bodies in a working clone bay and a crew member aboard the enemy count,
a destroyed clone bay and that crew member's death end the run), `automatedShipFlies` (the manned dodge, the FTL
charge, the engines' manning level, self-repair, save and load) and `automatedShipShowsItsManning` (the crew slot of
sensors and doors, no unmanned line in the tooltips, sensors and doors one level up with their manned line, the FTL
button's pilot light, bManned for a script). Report 201b adds `dismissNeedsTwoCrew mouse` / `touch` (the first crew
box's Dismiss, and on the touch layout without Hyperspace the DISMISSAL box, open the confirmation with two crew, not
for the last one; under Hyperspace also for the last one on an automated ship and in the over-limit box) and
`autoShipRandomCrew` (Hyperspace: an automated ship without crew of its own gets the woman named by
`autoship_dismissed_crew_name` when a script asks for a random crew member, or a transformRace event while its crew is
aboard the enemy, under Multiverse also its TEMP_OBYN_JUMP_CONVERT on the Auto-Cruiser; a crew loss or a transformRace
with no crew at all adds nobody). Native side:
`tools/hs-oracle/sessions/fix-201.json` (F3 with the probe `data/hs_oracle/fix-201.lua`; the step `["ship", k, ups,
seed]` of `oracle.py` starts the hangar's k-th ship): `f201-auto`, `f201-cura`, `f201-flag`; `fix-201b.json`
(`f201b-hs`, F2, probe `fix-201b.lua`): the crew screen with two, one and one automated crew member, then the events and
a script's SelectRandomCrew on the automated ship without crew.

`gameplay-parity`'s `GameplayParityPsW1Checks` holds the fleet rules of parity sweep W1 (UpdateDangerZone, RenderLabels):
a jump takes the beacons inside the zone in list order, with the fleet events that overtaking them one by one in that
order gives, and holds the centre at 60 (a wait does not); a warning mark stays when the next move's reach shrinks and
goes when the fleet takes the beacon; the next sector starts with the pursuit modifier the old one left.
`GameplayParityGen3Checks` also holds fresh 1.6.14 runs through all eight sectors
(`w1-*` runs: every map's events and, as a second fingerprint, its places and links; the AE-off ones run only when
gameplay-parity runs with Advanced Edition off). The oracle sessions
`tools/hs-oracle/sessions/ps-w1.json` (F3, seed 505, probe `hs_oracle/ps-w1.lua`, events `PSW1_*`) record the map after
reveal_map, distressBeacon, modifyPursuit and secretSector (`ps-w1-ops`), the warning marks and a jump's fleet move
(`ps-w1-fleet`), the hold at 60 (`ps-w1-clamp`) and the split all-node view (`ps-w1-split`, frames), for
`sweep/twin/run.sh` with the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-w1.lua", "prefix": "PH:"}`.
`.build/hs/sweep/jobs/ps-w1-map.sh` replays ops, fleet and clamp against the recorded oracle (`sweep/fixtures/ps-w1-map`)
and `ps-w1-check.py` compares the rows; after a jump it compares with the oracle's last tracer state, because the
oracle's Lua rows stop at the arrival box.

`gameplay-parity`'s `GameplayParityPsW3Checks` holds the store and requirement rules of parity sweep W3 (vanilla data):
a choice's req counts each weapon, drone and augment once and never the cargo hold, between its lvl and max_lvl
(QUEST_CREWDEAD_START_2 with BOMB_FIRE in the hold, then in a slot; CreateChoiceBox with ShipObject::HasEquipment), and
the vanilla store asks before a clone bay replaces the medbay (or the reverse) and before an eighth main system
(SystemStoreBox::Activate), where Escape closes the whole store; a weapon dragged from its slot to the hold leaves no
gap (WeaponSystem::RemoveWeapon, every mode). The 1.6.14 sessions `tools/native-capture/sessions/ps-w3-van.json` (console exe; generator
`.build/ps-w3/gen/gen_v.py`) record those cases, the store's InfoBoxes at `run-store.sav`, the ship window's InfoBoxes
and equipment drags, as frame pairs with the port (`tools/ui-capture.sh`, the same console commands). The oracle
sessions `tools/hs-oracle/sessions/ps-w3-hs.json` (`ps-w3-mv` F3, `ps-w3-hs` F2: the fixed store `PSW3_STORE` of the
tracer's append files; `ps-w3-repair`: BOSS_REPAIR_STATION's map label) are their Hyperspace twins.

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
confirmation on the screen, and the extra one dismissed with its button and Yes. Touch without Hyperspace: a crew box
dragged onto another keeps the order (no reorder on the crew screen; Hyperspace's swaps). Touch without Hyperspace: the iPad's
DISMISSAL DropBox (`dismissBox`, at the iPad's (600, 500) of the crew window) on the screen and clear of the boxes (none
under Hyperspace, user FU1-C1); on the emulator a
crew box carried onto it lights it (`dismissHot`) and, lifted there, opens the dismiss confirmation; the item waiting in
the over-capacity slot and an owned one change places, carried either way (`items` lists every item box). On the emulator:
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
place and a quick slide still select or paint as before), `tools/android-touch/zoom.mjs` Settings > Pinch to zoom with two real fingers (it writes the setting itself; `--off` for
the setting off): a pinch's fingers never reach the game, a held finger on the zoomed picture is the canvas point under it,
zoomed taps and a slide on a crew box, the minimap's map, bar (kept) and 1x, two-finger taps; frames at 1x and zoomed;
and `tools/android-touch/hud.mjs` the status bar's warnings, a
finger slid from empty space onto a crew box, and adb's keys with the system keyboard staying down. `TouchRouterTest` (`:app:testDebugUnitTest`) is the
quick check of finger routing, at the engine's call only.

## Mod scripts' libraries (sweep M1b)

`node tests/hs-lua/libs.mjs` runs the probe `tools/hs-oracle/tracer/data/hs_oracle/ps-m1b.lua` in the runtime and compares
each of its rows with the original's (oracle session `ps-m1b-libs`, `tests/hs-lua/libs-native.txt`); a row that differs on
purpose is listed in the test with the sandbox's value and its reason. Then the sandbox: no path out of the folder,
no shell or environment, the 50 MiB cap, the instruction cap with a script's hooks (cleared, replaced, a loop inside a
hook, coroutines), the debug library's limits, os.exit and the folder kept per account. `node tests/hs-lua/lua-scan.mjs`
(unit suite) and Android's `LuaScanTest` run the launcher's scan on `tests/hs-lua/lua-scan-cases.json`.
`tests/mods-scripts.mjs` (menus suite; screenshots in `MODSCRIPTS_OUT`) plays a test mod in Chromium and Firefox:
the question, Don't run, Run, the script's file in IndexedDB, os.exit's message, the Mods panel's folder, Ask again,
Clear mod files and the disabled setting. Android on the emulator: [ANDROID.md](ANDROID.md#testing).

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

## Native evidence kit (parity sweep)

The parity sweep's evidence tools sit in ignored `.build/hs/sweep/` (its `README.md`): the original 1.6.14 under
`tools/native-capture/` with a fixture exe chosen and the RNG ring summarised per call site, the Hyperspace oracle's
sessions with their browser twins (one probe Lua, rows side by side), Ghidra decompiles of 1.6.14 and the oracle's 1.6.9
by ZHL name, and frame pairs with the sweep's tolerance (2/255 per channel, 1 px). `tools/native-capture/driver.py` has
two steps for the 1.6.14 fixtures: `["cmd", "text", s]` types a dev console command (`FTLGame-console.exe` and the
fixtures built on it) and `["ring", "name"]` dumps `FTLGame-trace.exe`'s RNG ring. Native sessions of the sweep are in
`tools/native-capture/sessions/ps-<id>-*.json` (`ps-k0-vanilla-kit.json`: a console `LOAD` with its draws).
`ps-u1-hud.json` is the in-flight HUD's frame-pair matrix (nine sessions on `run1.sav` and `run-cx.sav`: hovers,
tooltips, power and right clicks, a crew list box drag, ten systems, four weapons and two drones, eight crew, hull and
resource values, an enemy box, a slot drag, GOD, the system boxes' hover and tooltip areas); its browser side runs with `tools/ui-capture.sh ps-u1-hud.json`
(the port imports the same save, `parityNoBackground`, the same console commands; `down`/`up` steps hold a press across
moves). `tools/hs-oracle/sessions/ps-u1-hs.json` holds its Hyperspace part (triggered-event boxes over windows, a
five-drone-slot ship `PSU1_D5`, thirteen systems).
The runner (`.build/hs/prod-int/hsq.sh`) runs `hs-mv-tutorial.mjs` and `hs-store-overflow.mjs` on F3 and
`native-import.mjs` on vanilla, F2 and F3 with the `.build/hs/save-tools/` set (jobs `hs-mv-tutorial`,
`hs-store-overflow`, `hs-native-import-<fixture>`).

Repeatable twins (ps-twin): the twin's probe (`BrowserHsWp5CrewDiagnostics`, `hsRuntimeProbe("wp5-crew <probe ...>")`)
runs every phase synchronously in frames of 1/60 s, so the machine's speed reaches it only through what the page left
before it. `<probe rng="n">` starts both simulation streams, Hyperspace's run-seed source and the script timers' goals
from n; every probe starts a fresh Lua state with the session's scripts (after `rng`), the Lua clock as after a steady
run (`LuaFps.steady`: no SpeedFactor, frame count or RunningTime of the page's real frames or of an earlier probe) and
without the crew definitions a lookup inserted (CustomCrewManager::GetDefinition). So the same `rng` gives the same
rows alone, beside other jobs, starved (`--cpus 0.5` and busy loops) and after a wait in the main menu, as the first or
a later probe of a page. `<probe fps="f">` runs the phases at f frames per second, the same game time (frame-period
studies). `.build/hs/sweep/twin2/browser.sh` sets them (`TWIN_RNG`, `TWIN_SEEDS=auto:<k>` for a session's fixed seed
set, `TWIN_FPS`), and `sweep/jobs2/` runs each job over its seed set (`jobs2/mk.py`).

Events and choices (sweep W2): the vanilla gameplay-parity mode's `choice rules` step
(`GameplayParityVanillaNativeChecks`) holds the 1.6.14 answers of `tools/native-capture/sessions/ps-w2-van.json`: the
repair station's choices at 0/3/8/12 hull damage, a stealing Continue open with no scrap, TRADER_UPGRADES_LIST's
max_group survivor and order, and LOADEVENT leaving no unique event used. The Hyperspace side is the oracle session
`tools/hs-oracle/sessions/ps-w2.json` (`ps-w2-ops`, F3, probe `tracer/data/hs_oracle/ps-w2.lua`, test events `PSW2_*`
in `tracer/data/events.xml.append`) and its twin: `sweep/twin/run.sh tools/hs-oracle/sessions/ps-w2.json ps-w2-ops
<map>` with the map `{"lua": "tools/hs-oracle/tracer/data/hs_oracle/ps-w2.lua"}`. It compares every choice box (types,
texts, the end of the box text) and the ship after one event per operation (resources, crew, systems, weapons, cargo,
augments, boarders, the `<system>` reward and its warnings). The oracle's start chain gives LASER_BURST_1, which the twin
(started at the beacon) lacks: the `st` rows differ in that one weapon and nothing else.

Hangar, ship start and progression (sweep S1): the `unlocks` mode ends with `GameplayUnlockPsS1Checks` (vanilla, F2,
F3). It runs an event's `<unlockShip>` through the event box as `tools/native-capture/sessions/ps-s1-unlock.json` and
`ps-s1-unlock2.json` recorded it in 1.6.14: a new ship adds its unlock line, "Ship Unlocked!" and the quest
achievement; the same event again adds nothing; a ship unlocked another way (the console's SHIP) gets no line and no
ship popup, only its quest; a harder difficulty earns the quest again with its popup, an easier one nothing; with the
achievement popup option off vanilla still shows the ship popup (Hyperspace does not). Scrap Hoarder at 599/600 scrap
and on the Kestrel follows `ps-s1-ach.json`. In vanilla it then starts all 28 layouts and compares each with the
original's start (`ps-s1-starts.json`: the continue.sav written as the opening box opens, read by the save reader):
reactor, hull, resources, systems' levels and reactor power, each crew member's race, room and slot, weapons, drones,
augments. On F2/F3 it wins a run with an event's `<win>`: the ship's victory and the event's victory type, no
ACH_WIN_* and no flagship type (HS CustomEvents.cpp), and a new game resets the type. The Hangar & AE diagnostic checks that the ship list's type tabs do not hover under its tutorial
(ShipSelect::MouseMove) and which Stealth C rooms its Slug lights without sensors (native hangar frame). `tests/ui-presentation.mjs` checks that the Start box covers Crystal A's hull, as in the
original's hangar (ShipBuilder::OnRender draws it after the ship), the Kestrel A medbay image at 0.75 alpha and Rename
lit while a name is typed (`ps-s1-hstates.json` frames). `tests/hs-hangar.mjs` on F3 writes the seed box's frames (`seed-*.png`, the canvas at
1280x720) for `tools/hs-oracle/sessions/ps-s1-seed.json`.

Lua runtime (sweep M1): `tools/hs-oracle/sessions/ps-m1.json` with the probe `tracer/data/hs_oracle/ps-m1.lua`.
`ps-m1-ev` (F3) installs a callback on every InternalEvent and RenderEvent and writes, per phase (idle, mouse and
keys, returns, fight, escape, after), which fired with what argument types and in which order, then what returns do
(dodge, augments, equipment, danger, level text, bonus power, stats, crew power, a pre-empted key), the registration
and SWIG errors scripts meet, the Lua libraries and globals, the weapon and drone boxes (MV Kestrel, `PSU1_D5`,
GOD), a fight's `lastTargets` and `DamageTarget`, a Lua clone bay, and the enemy after its escape. `ps-m1-f2` (F2)
checks that plain Hyperspace starts its Lua. The twin is `sweep/twin/run.sh tools/hs-oracle/sessions/ps-m1.json
ps-m1-ev sweep/fixtures/ps-m1/map.json`; `sweep/jobs/ps-m1-cmp.py` compares the rows by key with the allow list
`sweep/fixtures/ps-m1/allow.txt`. `tests/console.mjs` checks `LUA` on F2 without mod scripts.

Multiverse end to end (sweep M3): `tools/hs-oracle/sessions/ps-m3.json` with the probe `tracer/data/hs_oracle/ps-m3.lua`
(test ships `PSM3_TARGET`, `PSM3_TEMPORAL` and events `PSM3_FIGHT`, `PSM3_TFIGHT` in the tracer's appends) puts each
Multiverse script into a state that triggers it and reads what the script changed, at fixed game times. `ps-m3-a`:
the load-time tables (mods.multiverse's names, what tag-data-read parses, armor, beam and environment tables), the
variables MV keeps, the map's links (dead-end-eliminator), bonus-power and auto-manning augments, armor and chaotic
coal on a hull hit, Sentry missiles' power limit (sys-limit), the hacking, repair, pride, conservative, stealth,
system-target and advanced-sensors weapons in a fight, artillery targeting. `ps-m3-t3`: the enemy's temporal AI on a
burning room. `ps-m3-tc`: the player's temporal box on the Rhyme cruiser (its speed-up button and a room). `ps-m3-b`:
out-of-fight healing, atmosphere breaches, the tagged weapons' tooltip text, the toggle hotkey and `gui.choiceBox`, a
surrender clearing shots, the HER sector and fight. `ps-m3-r`: the Rhyme cruiser's hull animation and map icon.
`ps-m3-e`: blue giant, gravastar and light nebula. `ps-m3-x`: astrometrics, the pro-hull beam, the pride beam's first
frames. `ps-m3-g`: a name typed into the gatling-name box (custom-gatling-name writes `gui.choiceBox.mainText`).
`ps-m3-s`: a surrender loaded after a volley (MV's destroy_all_projectiles, read from an on_game_event callback).
`ps-m3-x2` and `ps-m3-y` are diagnostic (the pro-hull beam's frames; weapon power on AddWeapon and ForceIncreasePower,
Projectile:Kill) and not in the job. The
runner job `hs-ps-m3` (`sweep/jobs/ps-m3.sh`) runs every twin against the recorded oracle traces in
`sweep/fixtures/ps-m3/oracle`; one by hand is `sweep/twin/run.sh tools/hs-oracle/sessions/ps-m3.json <id>
sweep/fixtures/ps-m3/map.json`. `sweep/jobs/ps-m3-cmp.py` pairs the rows by name and order, with
`sweep/fixtures/ps-m3/allow.txt` (each allowed difference with its reason; `rel` compares spans of a harness-delayed
timeline). The full-run policy (oracle `autoplay` and `BrowserHsFullRunDiagnostics`) also skips Multiverse's toggle
menu and Sylvan hailing himself, two choices that lead back to the same box; the oracle's `HS_ORACLE.fixdrive()` also
resumes a game a stray console line paused.

## Faster native comparisons and runs (ps-infra kit)

Beside the existing tools, in ignored `.build/hs/` (proofs and switch-over steps in `.build/hs/reports/ps-infra.md`):

- `infra/fixedstep/`: the HS oracle and the 1.6.14 capture in fixed-step mode. `fstep.py` patches the container's copy of
  FTLGame.exe (1.6.9 rolled back, or 1.6.14 and its fixture exes) so the game's two clocks are a virtual counter that only
  the end of a frame moves, by the real-time oracle's measured frame period (16.708 ms, 59.85 fps; some native results
  depend on it, e.g. flak hits), and the frame limiter's wait becomes a gate the driver
  opens frame by frame. `fast_oracle.py` runs the unchanged `oracle.py` / `driver.py` with time counted in frames
  (`sleep(s)` = s * 60 frames, xdotool delays as frames between press and release). Every frame gets the same step,
  whatever the machine's load, and frames run as fast as llvmpipe renders them. The wall-clock seeds of an unseeded run
  (the game's boot `srandom32(time(0))`, Hyperspace's `seededRng` from `system_clock`, Lua's string hash seed) come from
  one number drawn per launch and logged as `[fstep] seed N`; `FSTEP_SEED=N` repeats that run frame for frame. While the
  game stands at the gate it reads its X events (PeekMessageW, PM_NOREMOVE), each input gets 0.1 s of real time for that,
  and the X server's key autorepeat is off (a key held over the driver's real-time waits repeated).
  `run-fast.sh <sessions.json> [ids]`
  (HS) and `capture-fast.sh` (1.6.14) take the same arguments as `tools/hs-oracle/run.sh` / `sweep/vanilla/run.sh`;
  `FSTEP=0` runs the plain real-time game with timing lines. `rowspread.py --rt <traces> --fs <traces>` is the check that
  a fixed-step run gives the real-time rows (equal where real-time runs agree, inside their spread where they do not, and
  identical between two fixed-step runs).
- `tools/docker-slot2.sh`: the slot script with a first-come-first-served queue, slots weighted by measured memory
  (`slots.gb`), nested calls that reuse the caller's slot, light docker commands without a slot, and an oracle or capture
  run (which already holds `oracle.lock`) put at the front.
- `tools/par2/par2.py`: `par.py` (imported, so the same jobs and checks) starting the longest recorded job first, and,
  once `docker-slot2.sh` is the slot script, one slot per job.
- `sweep/twin2/`, `sweep/jobs2/`: the browser twins with WebGL draw and clear calls made no-ops (everything else runs),
  for the twin jobs whose rows do not read pixels.

## Two-phase test flow (flow.py): the release gate

`.build/hs/tools/flow/flow.py` runs the whole check set (it imports `tools/par/par.py`'s job list, so every check and
command stays single-sourced) in two phases. Reports and proofs: `.build/hs/reports/ps-flow.md` (sections PERF, GATE).

    cd .build/hs/tools/par
    PAR_WT=<worktree> PAR_TAG=<tag> py -3 ../flow/flow.py [--no-build]     the whole gate: functional, then performance
    ... flow.py --no-build --only '<regex>'      any subset by job name (pieces have their own names: `--list`)
    ... flow.py --no-build --failed <run dir>    only what did not pass there; a twin job's passed pieces are copied over
    ... flow.py --no-build --perf                the performance phase alone
    ... flow.py --list                           the jobs: phase, admission GB, expected minutes, dependencies, command
    FLOW_POLITE=1 ...                            "I am using the PC": 2 lanes, --cpus 6 per container (about half the cores)
    TWIN_DEPTH=full ...                          the twin jobs at their full seed counts (ps-b2 6, ps-fu3 4)

- **Functional phase**, in parallel: every check whose result must not depend on machine speed. Web tests run with
  `FTL_HANG_S` (tests/lib/hang.mjs: each positive Playwright wait and each test deadline gets at least that long; one that
  still runs out fails as `HANG after N s at <file:line>`, a hang, not a failed check), `FTL_SKIP_PERFORMANCE=1` and
  `LAUNCHER_SKIP_FPS_CHECK=1`; resource-cache-ui (no performance limit) runs whole there, benchmarks included. Long jobs
  are split so a retry costs one piece: `web-menus-<script>` (one per menus script), `p-F3-encounters-1..4` (case
  ranges), and the nine twin jobs (ps-b2, ps-fu3, ps-b3, hs-ps-m3, hs-ps-c6, hs-ps-m1, hs-ps-w1-map, hs-ps-w2-ops,
  hs-ps-fu2) as TWIN's piece groups from `sweep/jobs2/pieces.json` (`<job>-<n>`: `TWIN_ONLY=<sessions:seeds>
  sh sweep/jobs2/<job>.sh <run>/twin/<job>`) plus `<job>-collect` (`TWIN_COLLECT=1`, no container), which starts after
  its pieces and runs the comparators; a missing or failed piece fails the collect with `MISSING piece <session>:<k>`.
  Pieces and collect share one output folder and one depth (`TWIN_DEPTH`; an explicit `TWIN_SEEDS` overrides both
  and then no longer matches the groups: do not set it for the gate). A job that fails is tried once more with the
  same command.
- **Real input at any speed** (tests/lib/input.mjs; `.build/hs/reports/ps-pauses.md`): the page queues every key and
  pointer event and the engine handles the queue only in its next animation frame, so a look at the game right after
  a press can still see the game from before it. A test that drives the game by real input flushes after each input
  (`flush(page)`: two animation frames) before it looks or decides what to press next, checks the effect it needs (the
  console's line before Enter, the trace line a command logs), and takes a scenario's pauses ("wait 2 s" of an oracle
  session) as stepped game time (`steppedClock(context)`, then `gameWait(page, s)`: frame intervals of at most 50 ms,
  as web/game.mjs steps), so a slow page waits longer and plays the same game. Fixed pauses in wall-clock time are
  only for pacing that changes nothing (screenshots).
  Launcher pages lay out for a moment after `sessionReady` (the Play page's save line moves the panels below it by a
  line), so a click aimed then can land beside its target: a panel is opened with `openDetails(page, selector)`, which
  clicks its summary while it is still closed.
- **Admission by measured memory, without swap**: the Docker VM stays at 12 GB; with nothing of ours running about
  10.8 GB of it is free, so `FLOW_GB` (default 9.8) is what our containers' peaks may add up to. A job counts its
  measured cgroup peak (`flow/jobmem.json`; twin groups: pieces.json) plus `FLOW_MARGIN` (0.5), never more than the
  job's own `--memory` cap (mods 5 GB); unmeasured jobs 2.5 GB; collect jobs and the Android build (host) count
  nothing. `FLOW_LANES` (default: the Docker slots of `tools/slots.max`, at most 4) caps the jobs at once. Among the
  jobs that fit, the heavier memory class starts first and the longest expected job (`flow/jobmin.json`,
  `tools/flow/mkjobmin.py`) first within it (longest-first alone leaves the 5-8 GB jobs for the end, where they cannot
  pair). p-F3-campaign runs with `--memory 8g` (its peak is 7.7-7.9 GB). `tools/flow/flowsim.py` replays this rule
  without Docker and gives the expected minutes: about 130 min at 3 or 4 lanes (memory-bound), 160-200 polite.
- **Polite mode** (`FLOW_POLITE=1`): 2 lanes, and every functional container gets `--cpus 6` (a `docker` shim,
  `tools/flow/polite/docker`, in front of the real one; `FLOW_POLITE_CPUS` changes it), the Android build
  `--max-workers=4`. The functional checks are speed-independent, so it only takes longer. The performance phase is
  not capped (it measures this machine as it is) and runs as always.
- **Performance phase**, after the functional phase has drained: it takes every Docker slot (`tools/slots/<i>`, held by
  a small bash process that exits if flow.py dies) for its whole duration, so nothing that uses the slots starts beside
  it; each job in turn gets one slot lent, one job at a time; the slots are freed at the end. Containers that still run
  (outside the slots, e.g. the user's own) are recorded in every PERF row, with the VM's load. `perf-menus`,
  `perf-launcher` and `perf-diagnostics-ui` run their script gates-only (`FTL_PERF_ONLY=1`: sign-in and the setup the
  gates need, then only the gates; every other check of the script ran in the functional phase):
  - menus: launch, then Options' Frame Limit section (per browser: capped benchmark fps in [25, 34], simulation ticks
    more than 3 x the capped frames, unlimited |frames - browser frames| in [0, 2], the setting's persistence);
  - launcher: the Tests page's first checks, then the benchmark block (Chromium, per phase 1-3 at the 30 FPS limit: fps
    in [25, 35], frames >= 101, browser fps >= fps - 0.5, browser callbacks >= frames, and > frames when the browser
    ran above 33 fps; both browsers: Stop cancels a benchmark and keeps cloud progress);
  - diagnostics-ui: the two-category run whose results the timing export keeps, then browser timing (callbacks > 10,
    fps > 0, interval p95 > 0; Stop and a hidden tab cancel it) and Firefox's unlimited benchmark (per phase 1-3:
    frames - browser callbacks = 0, |fps - browser fps| < 0.1).
  `hs-hslua-*` run whole (one engine probe measures the 0.5 ms Lua bridge gate and runs its semantics checks). The
  phase takes about 4 min. `FLOW_PERF_MODE=whole` runs the web scripts whole with the gates on (comparisons),
  `FLOW_PERF_DOCKER_X='--cpus 1'` slows only the performance jobs (the proof that a slow machine misses the gates).
- **A PERF row**: tests print each gate as `PERF {"name","value","lo","hi","unit","margin","ok"}` (tests/lib/perf.mjs;
  `"open":true` when the bounds are exclusive, written `(lo..hi)`; `null` is no bound), hs-lua's as its engine line
  `PASS|FAIL bridge (...): mean N ms` (limit 0.5 ms, exclusive, for the drawing variants). The summary prints
  `ok|MISS <job> <gate> <value> <unit> [lo..hi] margin <m> try <n> load {load1, load5, MiB available} beside: <containers>`;
  the margin is the distance to the nearer bound, negative when missed. A script measures all its gates (`gate()`) and
  fails once at the end (`gatesChecked()`: `N performance gate(s) missed: ...`), so one miss hides no other row. A miss
  is tried once more (that job only). `tools/flow/gatecmp.py --whole <runs> --gates <runs> --func <runs>` sets
  gates-only runs against whole-script runs.
- **Summary rows** (`summary.txt`; first line: commit, image, minutes per phase, lanes, GB, polite, twin depth):
  `passed`; `FAILED <job> <exact check>` (an assertion with its test file:line, a `HANG after N s at <file:line>` with
  its step, a comparator row as `expected <oracle> | actual <ours>`, an engine slice with its case, a collect's
  `MISSING piece`): failed twice; `RETRIED-D|R|T` (failed, then passed; the kind and cause of the first failure from
  kinds.json: D a deadline, R an order race, T a timing-dependent result; listed, not a release blocker);
  `UNEXPLAINED` (failed, then passed, no known cause: counts as not passed); `PERF` (a gate missed, then within on the
  retry: counts as not passed) and `PERF-FAILED` (missed twice); then one row per gate, then every job's minutes per
  attempt. `results.json`: `not_passed` (the release gate: everything but passed and RETRIED-D/R/T) and `retried`.
- **Evidence**: every attempt in `<run>/attempts/<job>/<n>/` (runner.log, the job's logs, a collect's output folder
  without the oracle copy and pictures: cmp files, rows, browser logs); twin outputs in `<run>/twin/<job>/`. To rerun
  only what failed: `--failed <run dir>` (a failed twin piece takes its collect along; a failed collect reruns alone over
  the copied results).
- `FLOW_DOCKER_X='--cpus 1'` starves every functional job (the proof that results do not depend on speed).

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
At STAR_MAP_1 it also checks that Escape and CANCEL leave the map open
(the original's rule, [TUTORIAL.md](TUTORIAL.md#original-captures-parity-sweep-u5-2026-11-08)), and at FIRE_PC_1 that the
selected crew is dropped before the airlock click. This remains automated browser
acceptance; it does not establish human play. `BrowserTutorialAdapterDiagnostics` (web-diag) checks the same map,
door and ship-window Escape rules with fixtures, and that dying in the tutorial shows gameover_tutorial without touching
the ordinary run or profile. The desktop frames against the original are a manual sweep step:
`tools/native-capture/sessions/ps-u5-tutorial.json` on both drivers, then `.build/hs/sweep/frames.py diff`.

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

`tests/zoom-ui.mjs` (Pinch to zoom, [WEBSITE.md](WEBSITE.md#pinch-to-zoom)) is included by `tests/menus.mjs` on the main
menu: off by default with the canvas untouched and Ctrl+wheel the game's; on, Ctrl+wheel zooms on the pointer, a zoomed
click opens Options at its new place and not at its 1x one, the minimap's map, bar (kept), size, switch and 1×, and in
Chromium real touch input (DevTools): a pinch begun on Options presses nothing, a zoomed tap opens it, two-finger taps, a
held finger after the hold; then a reload keeps the setting and switching it off restores the canvas.

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
a run with it enabled does not establish performance acceptance. `FTL_PERF_ONLY=1` is its counterpart for
menus.mjs, launcher.mjs, diagnostics-ui.mjs and resource-cache-ui.mjs: only the performance part and the setup it
needs run (the two together are refused); one run with each covers every check of the script.
