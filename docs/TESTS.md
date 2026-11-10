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
augments, cargo, and the profile's layouts, achievements, scores and games.

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

Native rows: `tools/native-capture/sessions/ps-s2-native.json` (`.build/hs/sweep/vanilla/run.sh`): Continue of
`run-cx.sav` with the HUD every 0.25 s (`s2-cx`, its browser twin through `tools/ui-capture.sh`), the autosave of an
event box opened after Continue (`s2-ev`: `continue.sav` holds the event) and none without one (`s2-ld`: the load
deleted the file and nothing wrote it in 6 s).

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
effect, power and finish sounds in the original's order. `web/self-tests.mjs`' audio clock (and `tests/audio.mjs`) keeps a
missing sound or music file's source AL_INITIAL. Sound trace pairs: `BrowserEngine.soundTrace` logs what our engine plays,
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
crew box carried onto it lights it (`dismissHot`) and, lifted there, opens the dismiss confirmation. On the emulator:
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
place and a quick slide still select or paint as before), and `tools/android-touch/hud.mjs` the status bar's warnings, a
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
