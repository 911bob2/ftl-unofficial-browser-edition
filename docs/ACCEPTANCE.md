# Acceptance tracking

The target remains all official vanilla and Advanced Edition gameplay, complete runs, desktop Chrome/Edge/Firefox, native engine save format, password authentication, private owner-supplied assets, and a single Linux amd64 application container behind an existing Nginx Proxy Manager. Mods, mobile controls, vanilla save interchange, multiplayer, and offline launch are outside scope.

## UI fidelity review of Gemini's branch — 2026-10-05

Review branch `codex/ui-fidelity-review` preserves Gemini's commits and unrelated
security drafts. Corrected native button state colors/glyph centering, hangar
heading baselines, crew Customize font/hit bounds, empty equipment art, grid
frame/tab geometry, weapon-frame stretching/wrapping and desktop autofire
art/anchor/hover states/tooltips. See [all-UI inventory and evidence](UI_PARITY.md)
for the confirmed behavior and remaining surfaces. No original executable was
launched after the user's headless constraint.

The accepted image passes all seven packaged groups: six in the broad run and
engine in a corrected isolated invocation covering all 22 modes. Menus has 84
named checks (28 each in Chrome/Edge/Firefox); all 33 tutorial lessons and the
launcher/diagnostics UI checks pass in each browser. Actual rendered colors,
glyph rows, grid frame and pointer bounds are checked; final hangar/grid/HUD
screenshots were inspected. Native HUD pixel equality, audible A/B, complete
weapon/drone states and the wider UI inventory remain unverified.

Image `53d127df275171a0d0f0c3177d7f9b8efca785e305fd29a340eddb0e98cd1ad9`
is deployed as `ftl-browser-native:local` on healthy loopback 8080; PUBLIC_ORIGIN
is `http://localhost:8080`. Served engine SHA-256:
`8d6b204ee053d3413a4de4be1e8269bae2fba38c3aa408754f9a82fcf5f3f764`.
Home/readiness return 200; anonymous asset/save APIs return 401 and Tests 403.
The existing data volume remains mounted, with non-root/read-only/capability
restrictions intact. Read-only pre/post deployment comparisons match all
account/settings/save/snapshot/key hashes and SQLite integrity is ok. The live
save had advanced before deployment; its current state was preserved. Original
FTL saves and unrelated security drafts match their initial hashes. No reset or
compatibility bump occurred. Rollback tag:
`ftl-browser-native:before-ui-fidelity-review-20261005`.

Ignored evidence: `.build/ui-fidelity-review/packaged-final/results.log`,
`engine-final/results.log`, screenshots under `packaged-final/.build/`,
`deployment-http.json` and `preservation-deploy.json`.

## Earlier main menu and hangar presentation — 2026-10-05

Original-art menu states/geometry/logo pulse and hangar panels/background
animations, family/layout selection, names/colours, equipment information,
lock explanations and progression indicators are implemented. Ship display names
persist as optional attributes independently of blueprint identity; legacy saves
retain their defaults. Native references support anchors, font mapping, input
limit and layout-preserving arrows. See [reference boundaries](HANGAR.md).

The final image passes all75 combined menu checks (25 each in Chrome/Edge/Firefox),
including actual naming/cancellation, Start/difficulty hover samples, tooltips,
AE/Options/Rooms retention and fullscreen-safe Back. The existing AE diagnostic
renders all18 vanilla/28 AE layouts, checks profile/RNG isolation, locked input,
layout-preserving arrows and named/legacy save/restore/restart. Final screenshots
of the menu, hangar, grid and information panel were inspected.

All seven packaged groups are covered across the broad run and affected final
rerun: unit/security/diagnostics/storage/production/engine pass the broad run;
Menus' tutorial/launcher/diagnostics UI components pass there, and its corrected
75-case menu matrix passes on the final image. All22 configured engine modes,
34 launcher checks,13 diagnostics UI checks and all33 tutorial lessons in all
three browsers pass. Initial stale button coordinates, queued-input timing and
physical Escape/fullscreen assumptions are resolved. A headless Firefox attempt
lacked WebGL2; its supported windowed display run passes.

Image sha256:b4a38cd0df54151bf8e210eabb5dac8722bad57bb967f55c8bbd859bab6ecded
is deployed as ftl-browser-native:local on healthy localhost8080. Engine SHA-256:
2ab931ec44767265cd33b45a8651b2b97f57a1b1664f229904c85cc81650f78f.
Account/security/save/snapshot/key hashes match exactly, SQLite integrity is ok,
and the original FTL save hash is unchanged. Readiness/resource protections,
non-root/read-only/loopback/capability restrictions and cleanup pass. No reset
or compatibility bump occurred; unrelated security drafts remain unchanged.

Evidence stays ignored under .build/menu-hangar/: final-tests.log, menus-final.log,
focused-final.log, focused-firefox-final.log, live-{before,predeploy,after}.json,
live-http.json, deployment.json and browser screenshots below .build/.
Exact native typography, preview geometry, grid decoration, editing semantics,
tooltip placement and audible/live original comparison remain required fidelity
work. Functional coverage does not certify complete native equality.

## Corrected security hardening — 2026-10-05

The reviewed patch is corrected on the actual .NET10/EF10 service. Conditional
ticket updates cannot recreate revoked sessions, and expired cleanup cannot delete
a concurrent refresh. Failed-password lockout preserves established sessions.
Empty proxy trust disables forwarded headers; invalid trust configuration fails
startup. Same-cookie tabs require explicit lease takeover. Save revision/snapshot
writes remain transactional, and only SQLite contention maps to retryable503.
Pinned diagnostic chunks survive concurrent deletion/eviction, remain charged
against quotas, and release on cancellation. Export concurrency, duration and
checkpoint scan limits bound resource use. XML and filename validation reject
DTD/entity/deep-document and traversal/ADS/encoded-component cases. Packaged job
starts are limited per account. See [security behavior](SECURITY_HARDENING.md).

All40 backend checks pass on Windows and Linux; all13 HTTP security checks pass
on the final image. Unit, storage-failure and production groups also pass on that
image. The broad run passes menus, diagnostics and the other four groups on the
same functional code before LF normalization:63 menu checks,34 launcher checks,
13 diagnostics UI checks, and all33 tutorial lessons in Chrome/Edge/Firefox.
The final-image engine rerun passes all22 packaged Chromium modes. This establishes
all seven groups across the broad run and affected final reruns, rather than a
single uninterrupted seven-group result. The ad-hoc harness initially omitted
writable HOME and the engine fixture origin; both failures are resolved using
the configuration normally supplied by LauncherTests.cs.

Final candidate image: sha256:5982d347813c2b76f160a5c40e7f6e5033b4bcc5e296b6e6e798f23e562bc6f2.
Evidence remains ignored under .build/security-fixes-{backend,package-final,
normalized-validation,engine-final}.txt and .build/security-fixes-engine-output/.
These checks exercise controlled races and representative real browser saves;
fleet-scale load, every possible legitimate XML document and the deployed proxy
configuration are not exhaustively verified. Main8080 and fixture8084 retain their
previous accepted image and data. No deployment, schema migration or reset ran.

## Diagnostics workspace and browser timing — 2026-10-05

Main8080 serves the accepted Tests workspace with independent configuration,
preview and results panes. Passed cases are summarized by default; attention/all/
passed/failed filters, case details, keyboard tabs, bounded Activity with follow
scrolling and full JSON export keep results from moving the engine preview.
Short/narrow windows use normal document flow and locally scrolling result panes.

All 13 focused diagnostics UI checks pass in Windows Chrome, Edge and isolated
Playwright Firefox: 137 real encounters per browser preserve preview/page geometry
during execution and inspection; deliberate errors, long logs, filters, details,
exports, 320px layout, Stop and hidden-tab cancellation pass. The three Unlimited
Firefox flagship phases draw exactly once per supplied callback. All 34 standalone
launcher checks pass; measured 30 FPS drawing has about 59 callbacks/s. Synthetic
sampling covers eight refresh rates through 500 Hz and keeps callbacks independent
of drawings, warmup and cancellation. These are callback/workload measurements,
not a physical 500 Hz performance claim.

Both Windows monitors are currently configured 60 Hz. The primary advertises 500 Hz;
installed Firefox 157.0's exact source selects primary-monitor global vsync.
The native Firefox UI path was blocked by the computer-use runtime's unsupported
URL-policy enforcement; no UI input or configuration changes occurred. Native
high-refresh/move/restart measurements remain open. Source links and instructions
are in [performance](PERFORMANCE.md).

The first packaged unit+menus API run passes unit, launcher 34 and diagnostics UI 13,
but broader menus expose two earlier assumptions. Opening a controlled delayed
dialogue on actual input arrival fixes the early-mouse wall-clock race; all three
focused options reruns pass. Tutorial map tests now consider exposed points within
the actual 12px beacon hit radius and log RNG/panel/neighbours if all are covered.
The final authenticated API rerun passes both affected groups, with all 122 checks:
unit 3, menus 63, real-time tutorial 9, launcher 34 and diagnostics UI 13. All 33
tutorial steps complete in each packaged browser; no inaccessible map is skipped.
Other unchanged packaged groups retain the preceding release's evidence. These
three random-map traversals do not establish native layout fidelity or accessibility
for every possible RNG state. Main deployment preserves exact account/security/
run/snapshot/encryption-key hashes; SQLite integrity, readiness, private-resource
protection and container constraints pass. Temporary test workers/job directories
are removed. The isolated8084 fixture serves the same accepted image. Engine JS
is unchanged; no progress reset, compatibility bump or browser/display setting
changes occurred. Evidence is ignored `.build/diagnostics-workspace-*` and
`.build/live-{before,after}-diagnostics-workspace.json`.

## Gameplay options and Colorblind WIP notice release — 2026-10-04

Main8080 serves the accepted options update. Focus pause, three physical event
number-key modes and hovered beacon paths use native defaults and reference
consumers. Float32 0.3-second boundaries, retained event-chain clocks and saved
settings have focused coverage. User explicitly deferred colorblind rendering;
the Colorblind option opens a Work in progress notice instead.

Final WIP candidate passes six engine modes per Chrome/Edge/Firefox and all63
combined browser-menu checks. Nine popup checks cover title, hangar and run:
modal input, visible Back, toolbar cancellation, keyboard acknowledgment,
unchanged settings/RNG and fullscreen. Popup screenshot was inspected. Current
rendering is not a claim of full native visual parity.

Before the WIP-only delta, all36 outer launcher checks and all seven packaged
groups passed, including all22 engine modes and complete real-time33-step
tutorials in each packaged browser. Final WIP and benchmark-sampling changes
have the affected three-browser menu/engine reruns above; unchanged packaged
groups retain the immediately preceding image's evidence.

The first packaged job failed because RNG24055526093 placed the tutorial test's
first beacon beneath its instruction panel. A visible-neighbour selection and
actual jump/fuel assertion fix it; that seed with initial blur is now a regression.
The probe uses delayed mouse choices and restores focus. Combined fixtures also
retain Continue during populated statistics, reopen Options before testing Tab,
and guard fullscreen exit after reload. The newly created fixture exposed a
credential read before readiness, now reordered. Firefox under concurrent load
also exposed an invalid Unlimited>1.2x30FPS assumption; acceptance now compares
drawings with available animation frames over the same one-second window.
That pacing check opts out of warmup; normal Benchmark retains its2s warmup.
All identified failures are resolved in their affected acceptance.

Read-only before/after identity/security/run/snapshot/encryption-key hashes match;
SQLite integrity, live readiness/private resource protections/security headers
and container constraints pass. Progress reset and compatibility bump did not
run. Main image sha256:fa5e7879bc6b2646ab68fea0ee92346fd72c16beefd133bdb91339ac3eef90c4;
engine JS fd23205be41d9b2e6650eda6cf8469049d368c43630842fe782b0c444bf18a61.
Evidence: .build/gameplay-options-{launcher,server}-final-results.json,
.build/gameplay-options-wip-menu-final-{check.log,results.json},
.build/gameplay-options-wip-{chrome,edge,firefox}-{check.log,results.json} and
.build/live-{before,after}-gameplay-options.json. Original game/saves untouched;
private executable/reference exports remain excluded from Git.

## Menu and stranded-crew warnings release — 2026-10-04

Main 8080 now serves the native-referenced confirmation update. Five focused
engine modes pass in Chrome, Edge and Firefox, and twelve actual browser-input
cases cover No/Back preservation, pointer/hotkey boarding warnings, accepted
restart/hangar and saved title New Game. Cancel preserves exact serialized run,
profile and checkpoint buffers; mouse Back keeps fullscreen. Ownership,
mind-control, dead crew, drive readiness, repeated answers and restored finished
scores have focused positive/negative coverage. Original menu textures/fonts and
geometry are used; exact native visual/input timing remains unverified.

All 36 launcher checks and seven packaged groups pass, including complete
real-time tutorial traversals in each packaged browser. Quick checks now includes
the warning probe (14 checks). The final server-only selection update separately
passes all 21 packaged engine modes. The initial fixture boarded with the pilot
and correctly failed readiness; it now uses a non-pilot. Actual pointer testing
also exposed a newly opened PauseWindow remaining at the origin until rendering;
shared windows now position before input. Both failures are resolved.

Read-only before/after hashes match for accounts, security settings, saved runs,
recovery snapshots and encryption keys; SQLite integrity, readiness, resource
protection, container security and worker/job cleanup pass. No reset or
compatibility bump. Evidence: `.build/menu-warnings-*` and
`.build/live-{before,after}-menu-warnings.json`. Native reverse-engineering
artifacts remain private; original game/saves were not used or changed.

## Tutorial menu activation release — 2026-10-04

Tutorial is active on main 8080. Chrome, Edge and Firefox each complete all 33
lessons through actual browser pointer/keyboard input and the normal frame clock.
Crew repair/venting, FTL charge/jump, authored enemy fire, normal combat/rewards,
cargo drag and paid upgrades use no engine probes, grants, invulnerability or
accelerated time. Each browser verifies visible Exit/fullscreen/reload, exact
ordinary run/profile buffers and Continue ship/AE/difficulty/hull. Nine direct
checks/99 lesson visits pass without page/engine errors. Eight focused modes pass
per browser; all 36 launcher checks/seven packaged groups pass, including a second
full real-time traversal in each packaged browser. Native exact presentation and
human play remain unverified. Captured text has edge crowding requiring native
word-wrap/glyph comparison; compilation/automation does not establish1:1.

Real-time acceptance exposed and fixed stale-render hangar Start/difficulty hit
tests and a depowered weapon retaining its primed-shot room reference. Focused
cancel/restore/re-power and no-draw locked/off-target/right-click cases pass.
Tutorial save paths skip serialization before their publication barrier.
Firefox pending launch/input audio resume rejection is consumed on context close;
live failures report and can retry, with shared Node/browser regression cases.
Fixture failures from asynchronous initialization, fullscreen reset, incomplete
opening dialogues and a panel-covered beacon are corrected without changing RNG
or simulation state. Earlier failed runs are retained as private historical artifacts.

Main deployment retains account/security/run/snapshot/key hashes and SQLite
integrity. Readiness/private-resource protections, non-root/read-only/container
security and job/worker cleanup pass. No reset/save-compatibility bump. Evidence:
`.build/tutorial-menu-*`, `.build/live-{before,after}-tutorial-menu.json`.

## Tutorial typography release — 2026-10-04

Nine focused modes pass in Chrome, Edge and Firefox with native 11Bold instruction
font,12px baseline/20px line spacing and HL2 Continue font/hitbox/glow. Actual
private instructions for all 33 panels fit the native wrap width and leave the Exit
row clear. All27 browser menu checks pass; overlay/Exit and normal-mechanics
completion retain actual fullscreen and ordinary progress. The initial fullscreen
fixture called the limited JS wrapper; corrected global export calls pass.
Final all 36 launcher checks/seven packaged groups pass. Main 8080 deployment
preserves all account/security/run/snapshot/key hashes and SQLite integrity;
readiness/protections and worker/job cleanup pass. Evidence:
`.build/tutorial-typography-*`, `.build/live-{before,after}-tutorial-typography.json`.
Exact native word-wrap/hover/sound/frame-edge timing and complete human/native
presentation remain unverified. Actual menu/pointer play is next; menu inactive.

## Tutorial highlights release — 2026-10-04

Nine focused modes pass in Chrome, Edge and Firefox on the final image, including
all 33 overlay panels, native slot selection, empty-box clock progression,
triangular alpha samples and equal-duration 30/60/120/240-FPS clocks. Render/UI
updates preserve serialized gameplay and simulation RNG. Captured panels show
red power/arrow cues and a separate Exit control. All 36 launcher checks and
seven packaged groups pass, including their broad engine matrices. Read-only
pre/post account/security/run/snapshot/key hashes match; SQLite integrity,
readiness/protections and worker/job cleanup pass. Main 8080 serves this image.
Evidence: `.build/tutorial-highlights-*`, `.build/live-{before,after}-tutorial-highlights.json`.
The first pulse fixture compared unequal durations at 30 FPS; a full second per
rate fixed the fixture. Exact native frame-edge timing, typography/fullscreen
and human played acceptance remain; Tutorial is inactive. Assembly confirms the
next typography correction: instruction font ID12 is JustinFont11Bold, not 12Bold.

## Tutorial encounters release — 2026-10-04

Eleven focused modes pass in Chrome, Edge and Firefox, including native start/map
selection, jump arrival, once-only/deferred missile events, replenishment, hull/crew
reward routes and save restoration. A separate normal-mechanics traversal covers
all 33 lessons, real repair/venting, an authored enemy laser volley, normal firing
and rewards, equipment drag, paid upgrades and isolated exit. It uses time-stepped
automation, not human play or original presentation comparison. Nine broad modes
pass: 138 layout/difficulty cases, 137 encounters and twelve accelerated campaigns.
Evidence: `.build/tutorial-encounters-*`. All 36 launcher checks and seven packaged groups pass; main 8080 is deployed
with unchanged identity/security/progress/key hashes and passing SQLite integrity,
readiness/protections and cleanup. The initial cancellation failure was an async Playwright polling bug;
awaited HTTP polling now checks actual completion. The explicit packaged engine
selection includes all eight tutorial suites. Main serves the validated tutorial-encounters image.

## Current direct fork — 2026-10-04

The preceding validated image `ftl-browser-native:tutorial-navigation` is deployed on main
8080. Six focused modes pass per Chrome/Edge/Firefox (gates, adapter, overlay,
entry, isolation, interaction). All 30 launcher checks and seven packaged groups
pass, including ordinary menu/fullscreen checks, 138 layout/difficulty cases,
137 encounters and twelve accelerated campaign fixtures. Jump and Ship buttons/
hotkeys share native lesson opening permissions; actual early pointer/hotkey
rejections, disabled-button hit consumption, permitted lessons and existing
equipment/upgrade windows are covered. Initial synthetic Jump clicks missed its
offset hitbox; corrected interior points and actual toolbar checks pass. Read-only
pre/post database/account/security/run/snapshot/key hashes match; readiness,
anonymous protections, security configuration and job/worker cleanup pass.
Evidence: `.build/tutorial-navigation-*` and
`.build/live-{before,after}-tutorial-navigation.json`. Native map/encounters,
full played tutorial and exact presentation remain incomplete; Tutorial is inactive.

The preceding validated tutorial-entry release passed the following checks on main
8080. Nine focused modes pass in each Chrome/Edge/Firefox and nine additional
broad modes pass, including 138 layout/difficulty, 137 encounter and twelve
accelerated campaign fixtures. All 30 website launcher checks and seven packaged
groups pass. New Tutorial setup coverage tests native entry timing, power,
hull/system/crew damage, two localized human crew in room 1, random room-9 fire
attempts with replacement, actual repair/venting and duplicate/stale/restart/save
guards. Loading performs a zero-time update; the fixture settles the same derived
crew actions before exact full-save comparison. Ordinary saves/RNG/profile/mode
remain protected. Damage rendering at 30/60/120/240 FPS now preserves simulation
and saved RNG; the fixture exposed screen shake consuming gameplay RNG, fixed
with a separate cosmetic generator. This is no whole-engine determinism claim.
Deployment preserves all account/security/run/snapshot/key hashes and database
integrity; readiness/protections and worker/job cleanup pass. Evidence:
`.build/tutorial-entry-*`, `.build/live-{before,after}-tutorial-entry.json`.
Native encounter/map/navigation setup, full playable traversal, fullscreen exit
and exact presentation remain pending. Tutorial stays inactive.

The preceding validated tutorial-ui release passed the following checks.
Seven focused tutorial/statistics/achievement probes per browser and ten broad
modes passed before the final tutorial-only hover fix. Final overlay/isolation
checks pass in Chrome/Edge/Firefox, and all 27 launcher checks and seven packaged
integration groups pass on the deployed image. The overlay covers all 33 panels,
original arrows, Continue/Exit, tutorial tab locking, modal/tooltip/cursor priority,
captured press/drag/release across combat reload and unchanged render state/RNG.
A profile save triggered in the tutorial exit frame is now suppressed; pending
ordinary changes remain dirty and save on the next normal frame. Deployment
preserves account/security/run/snapshot/key hashes; readiness/protections and
worker/job cleanup pass. Evidence: `.build/tutorial-ui-*` and
`.build/live-{before,after}-tutorial-ui.json`. Native setup, full playable traversal,
fullscreen tutorial exit and exact presentation remain pending. The Tutorial
menu is inactive. These fixtures do not establish complete tutorial acceptance.

The preceding tutorial-isolation release passed the following checks.
Expanded data/isolation/statistics/achievement probes pass in Chrome/Edge/Firefox,
plus ten other affected modes: interaction, mechanics, drones, unlocks, AE,
combat, vanilla, AE layouts, encounters and accelerated campaign fixtures.
Eighteen launcher checks and all seven packaged groups pass. Exit releases held
input; save/profile/delete/completion/restart/Continue/initialization-failure/
shutdown fixtures preserve ordinary state. No worker or temporary job directory
remains. Deployment preserves account/security/run/snapshot/key hashes and
readiness/protections. Evidence: `.build/tutorial-isolation-*` and
`.build/live-{before,after}-tutorial-isolation.json`. The Tutorial button is still
inactive: the isolated simulation foundation is verified; instruction flow is
not implemented.

The preceding tutorial-data release passed the following checks.
The official desktop tutorial data check passes in Chrome, Edge and Firefox:
33 steps, 14 manual continuations, 19 action gates, translated PC/default
instructions and PC/shared arrows, with unchanged ordinary run/profile/mode/RNG.
Launcher selection/export checks pass in all three browsers and all seven
packaged integration groups pass. A final assertion-only rebuild tightened
translation verification and reran the three-browser tutorial probe. Worker and
temporary-job cleanup pass. Account/security/run/snapshot/key hashes match across
deployment; readiness and anonymous protections pass. Evidence:
`.build/tutorial-data-*`, `.build/live-{before,after}-tutorial-data.json`.
The Tutorial menu is still inactive; this verifies script data, not interactive
tutorial behavior. No reset or save-compatibility change occurred.

The preceding statistics release passed the evidence below. Normal builds compile
maintained sources directly; transformed-source equivalence and the direct-fork
baseline passed before changes.

- Five native crew-record counters pass actual repair/combat, tie/rename/ownership,
  cloning/retraining, ordinary fire/breach negatives, legacy exclusion and full
  counter XML restoration in Chrome, Edge and Firefox. All 24 menu checks and
  eleven affected regression modes pass. Deployment preserves account/security,
  saved-run, snapshot and encryption-key hashes; integrity/readiness/protection
  checks pass. Evidence is `.build/crew-records-*` and
  `.build/live-{before,after}-records.json`.

- Native run/lifetime counters and original split panels pass expanded
  Chrome/Edge/Firefox cases: new/abandoned/loaded/completed game counts, initial
  crew/recruitment, actual clone queues and teleport/load exclusions, unique
  visits, four maxima, five overall/four layout scores and reached-sector saves.
  All original crew portrait variants render without progression/RNG changes.
  Mixed-mode fixtures cover AE-only score/layout displays with vanilla selected.
  All 24 populated menu checks and eleven broad modes pass, including the 138
  layout/difficulty cases, 137 encounters and 12 accelerated campaigns. Main
  deployment preserves account/security/save/snapshot/key hashes and readiness/
  resource protections. Exact typography/decorations, custom ship names,
  restart/abandon record submission and exhaustive native timing remain pending.
  Evidence is `.build/statistics-*` and `.build/live-{before,after}-statistics.json`.

- Six destruction achievement predicates pass positive/negative/save cases in installed Chrome, installed Edge and Firefox: Energy Shields, Fed Patience, One Volley, Only Drones, Rock Missiles and Stealth Destroy. Actual weapon emission, pending-shot/mid-burst/active-beam saves, self-targeted bombs and post-jump encounter reset pass; notification deduplication and unknown legacy history are checked. Native predicate quirks are documented in `ORIGINAL_BEHAVIOR.md`.
- Six additional impact/boarding predicates pass in installed Chrome, installed Edge and Firefox: Bad Dodging, Stealth Avoid, Slice and Dice, Slug Bio, Crystal Shard and Invade Ship. Streak/cloak/room/active-beam history survives saves; negative thresholds, interrupted streaks, separate shots/cloaks, shields, families, legacy history and friendly crew at home are checked. A pending projectile exactly at its target no longer becomes NaN or impacts during zero-time restoration. All eleven affected regression modes pass, including the 138 layout/difficulty cases, 137 encounters and 12 accelerated campaign fixtures. These diagnostic setups are not legitimate played victories.
- Boarding Drone, Mantis Slaughter and Rock Fire now pass focused Chrome/Edge/Firefox earning, negative and restoration cases. Coverage includes native prior-target/sabotage goals, pawn identity, power toggles, unknown old histories, arrival hull/count/deaths, repaired/replacement equality, clone queues, drone deaths, pending death animations, burning combat/fire kills and home/family exclusions. All 51 official achievements have automatic hooks. The eleven affected regression modes pass on the crew image; exhaustive native achievement/mechanics equivalence remains unverified.
- The combat-achievements candidate passes interaction, mechanics, drones, statistics, unlocks, AE, combat, all 138 layout/difficulty cases, 137 encounters and 12 accelerated campaign fixtures. Deployment preserves all account/security, saved-run, recovery-snapshot and encryption-key checksums; readiness, anonymous resource protections and security headers pass. Private evidence is under `.build/achievements-*` and `.build/live-{before,after}-achievements.json`.

- `tests/launcher.mjs` passes Chrome's 13 quick checks, three 30-FPS flagship benchmark phases, cancellation/export/navigation, script whitelist, owner isolation, single-job limit and reload/resume. All seven packaged integration groups pass, including Chrome/Edge/Firefox menus and launcher behavior, complete engine matrices, security, diagnostic recovery, unavailable storage and HTTPS Production. Installed Edge and Firefox also pass module/export, benchmark Stop, navigation and narrow-layout checks. Cancellation after a browser starts and cleanup of all job/profile directories pass; no worker processes remain. Pending audio-resume cancellation has a shared Node/browser regression.
- Main deployment passes readiness, Tests navigation, anonymous/private-resource denial and browser security headers. It retains UID/GID 10001, read-only root, loopback binding, cap-drop and no-new-privileges. Read-only checksums match for all eleven accounts/roles/security settings, the saved run, ten recovery snapshots and persistent encryption keys; both database snapshots report integrity OK. This release does not reset progression.
- Combat regressions cover cyclic/disconnected/identity shortest paths and seed-35 final-sector generation. A map with no right-most flagship position connected to the base is regenerated; generation is bounded, and the selected flagship's route to the base is asserted. The controlled reproducer and complete native-engine matrices pass.

- `tests/baseline.mjs` passes unlock, drone, desktop interaction, AE, mechanics, progress and combat probes; 54 vanilla and 84 AE layout/difficulty cases; 137 encounters; and 12 accelerated campaign fixtures. The current campaign fixture traverses sectors 1–7, enters sector 8 and places flagship encounters for all phase transitions/save checks. Independent combat probes verify base-countdown defeat. Neither traversal is legitimate gameplay or full native parity evidence.
- Unlock tests cover fresh-profile availability, locked-run rejection before mutation, Kestrel sector-5 Engi availability, two-of-three achievements for B, positive/negative AE/B/sector-8 C conditions, the five-A Lanius threshold, every victory-chain successor, Crystal A/B victory eligibility, quest-family flags, maximum difficulty and deterministic profile reload/repeated completion. Actual debug commands can grant ordinary progression. Full quest paths and exhaustive native achievement equivalence remain unfinished.
- The achievement probe covers thirteen additional automatic conditions: sector-five/eight restricted-action milestones, Zoltan reactor and Federation weapon restrictions, Lanius scrap/oxygen and lifetime scrap/defeated ships. Positive/negative boundaries, durable disqualifications, actual player/enemy weapon and crew-loss actions, successful versus rejected drone deployment, real store repair/resource purchases, upgrade Undo, pending-purchase autosaves and repeated profile/run restoration pass. Popup duplicate suppression, difficulty upgrades, display expiry, disabled-display earning and preference persistence pass. Native font mapping, difficulty decorations, layout naming, cloning/offensive-drone edge comparisons and live presentation remain unverified.
- Eleven further live-state conditions pass focused tests: Kestrel distinct races, installed systems and one-hull repair; Engi powered drones and enemy ion locks; Zoltan supplied power; three active Lanius AE systems; all enemy cells burning; hostile crewed enemy oxygen; Slug room vision; and Crystal lockdown. Tests cover boundary negatives, wrong-family cases, actual power/AE activation, hull-minimum save/reset and lockdown on either ship with inactive/expired/three-occupant negatives and save restoration. Native lockdown barrier/shard timing and complete mixed-state equivalence remain unverified.
- Seven further conditions have focused coverage: Slug nebula jumps, Federation crew blue choices, Mantis crew victories/survivor, Crystal Rock hull victories, Stealth dangerous-environment history and Rock hidden-sector entry. Counters persist and distinguish rejected choices, intruders, crew versus hull victories, sector boundaries and repeated loads. Victory-count boundary fixtures seed earlier history and exercise the final real defeat handler; they are not twenty/ten played encounters. Automatic hooks now cover 36 of 51 official achievements.
- The supplied DAT's secret-sector operation now dispatches to a separate Crystal map without consuming fuel or advancing the ordinary sector level. Open-event save/Continue, hidden start-event quest creation, saves during fly-in and quest arrival, Crystal unlock, outbound ordinary route and retained past-sector references pass. Full stasis-pod/Ruwen/portal traversal, exact map geometry, exit selection and native transition presentation remain unverified.
- FPS settings provide a 30–360 slider and Unlimited with default 60. Chrome/Edge/Firefox checks cover immediate changes, one-FPS arrow adjustment, finite/Unlimited cloud reload persistence, run settings/fullscreen and continued simulation updates at a 30 FPS drawing cap. The interaction probe checks equal real-door animation progress at 30/60/120/240 drawings per second with more frequent updates. `tests/frame-pacer.mjs` covers 60/75/120/144/165/240/360 Hz with finite/unlimited caps, live changes, stalls and reset. The browser continues to schedule through `requestAnimationFrame`; actual high-refresh hardware behavior is not established by synthetic timestamps.
- `tests/menus.mjs` passes the focused menu/input matrix in installed Chrome, installed Edge and Firefox: title/hangar/run Options and Controls, sliders and persistence, credits/backers/return, nested Back, fullscreen retention and console input. The engine AE probe also traverses the full credits, checks the reference-backed pause and confirms unchanged ordinary progress. These checks do not cover complete gameplay, native font/timing fidelity or every browser failure mode.
- `tests/diagnostics.mjs` passes ordered append acknowledgements and retry deduplication; credential-field redaction; anonymous/account isolation; time/severity/subsystem/marker filters; ZIP metadata, structured logs and complete run/profile checkpoint files; bounded segment continuation and concurrent instance limits; console checkpoint save/load; offline export/marker recovery and interrupted finish retry; recording continuity after reload; public license delivery/private asset denial; and daisyUI Logs actions. `tests/diagnostic-export.mjs` separately validates ZIP supplements and checkpoint assembly. Offline archives explicitly distinguish unavailable server history from pending browser records.
- `tests/security.mjs` passes ten checks against a fresh disposable server of the current candidate. Authentication/rotation, roles, CSRF/origins, cookie revocation, saves/revisions/XML, leases, account isolation/disable, lockout and browser headers remain protected after the rewrite. The runner additionally verifies whitelist rejection and owner-only job access/cancellation. Full account/admin browser workflows remain required.
- `tests/debug-storage.mjs` passes with diagnostic storage deliberately unavailable: the application stays healthy, diagnostic creation reports 503, and authentication, mandatory rotation, private asset access, leases and run saves still work.
- Anti-personnel/shield drone probes cover native constants, deployment, power, shield layers/cooldowns, repair/fire/breach follow-ups, destruction cooldown and save restoration. Original AI priorities and presentation remain unverified.

Evidence is private under `.build/`: `fork-current-results.json`, `debug-results.json`, `debug-storage-results.json` and `menu-results.json`. Current behavior and remaining gaps are tracked in [the parity matrix](parity/README.md).

## Earlier release evidence

- Mixed Java/Kotlin engine compiles to JavaScript with TeaVM 0.15.0.
- Linux Docker application image builds. Local listener binds only `127.0.0.1:8080`.
- Original FTL 1.6.14 DAT parses: 3,219 original entries; all 119 original XML files validate with external entities disabled. DAT SHA-256 is `d7c4744f91f96303c0342b30c2dcb63843050e0f489426200c683d02fb8abc7d`.
- Isolated API integration checks pass for private asset/save access, CSRF and origin checks, eleven bootstrap accounts, forced rotation, administrator authorization, save revision conflicts, XML entity rejection, recovery snapshot format, play leases, account isolation, immediate disable revocation, account lockout, and browser security headers.
- Native WebGL2 rendering, mouse selection, keyboard shortcuts, jump-map navigation, a real beacon jump, and account cloud-save continuation work in the in-app Chromium browser.
- All 18 vanilla layouts across three difficulties pass new-run/render/save/restore checks (54 cases); all 28 AE layouts pass the same checks (84 cases). These also verify crew, hull, weapons, persisted stun, and eight subsequent RNG values after restore.
- All 137 AE encounter ship specifications pass combat-update/render/save/restore stress checks. This exercises native simulation; it does not validate every event choice or numerical balance against the original binary.
- Twelve seeded, invulnerable campaign traversals pass: vanilla and AE, all three difficulties, two seeds each. Each traverses eight sectors, saves/reloads between sectors, and advances through all three flagship phases to the victory transition. The latest matrix also verifies sector 5/8 and victory achievement earning, a completed run ledger, and counting flagship defeats. This is not evidence of twelve legitimate played victories or original-game balance.
- Focused mechanics checks pass for Crystal lockdown/cooldown/save, ion-intruder boarding and ion pulses/save, stun persistence, ASB hull damage/breach/shield bypass, equipment overflow/save/jump clearing, and deletion of ended-run saves.
- Original title and battle OGG music, WAV effects, and an AE OGG effect decode successfully in Web Audio; play/pause/resume/stop work. Audio clock regression checks cover pitch continuity and seeking.
- Production settings were exercised in disposable local containers: trusted forwarded HTTPS, plain-HTTP and unexpected-host rejection, HSTS, secure host cookies, mandatory rotation, cross-origin rejection, and disabled diagnostics. No VPS deployment was performed.
- The actual local container is healthy, runs as UID/GID 10001, uses a read-only root filesystem, and publishes only `127.0.0.1:8080`. Recreating it preserves all eleven accounts. Its pre-update SQLite backup passes `integrity_check` and includes the persistent encryption keys.
- Client files use a content fingerprint independent of save-format compatibility. Local cookie names include the port so isolated fixtures cannot overwrite the main application's cookies.
- A normal browser flight survives a 115-second isolated-server outage: it pauses, retains an unsynchronized Artemis power change, reacquires the expired lease without takeover, and uploads that change after reconnect. Cloud revision and native XML confirm the preserved weapon state and beacon.
- Native run scoring and profile statistics pass focused regressions: unique beacons, base scrap with arm/store exemptions, hull and crew victories without double-counting, run/profile save restoration, legacy partial-history exclusion, top-five retention, and idempotent completion. The statistics screen renders and returns to the ended run. These diagnostics use disposable in-memory runs.
- The deployed image passes focused targeting/final-sector checks: Easy/Normal/Hard probability boundaries, system priorities and distinct volley targets, RNG restoration, fleet warning/capture persistence, and base countdown/save/reset/defeat. Additional checks verify friendly stationary waiting without resetting charge/fuel/history, fleet capture and flagship arrival during waiting, captured-encounter restoration, hostile wait prevention, stale queued hacking targets and active-hack cooldown cleanup. The rendered Wait control also advances the map. [Original executable evidence](ORIGINAL_BEHAVIOR.md).
- The latest twelve campaign traversals also pass save restoration between flagship phases after their destruction, exercising detached hacking and boarding-drone serialization.
- The latest image is healthy on main port 8080 with loopback binding and the existing security settings. Pre/post deployment backups pass SQLite integrity checks; all eleven account credentials and flags, saves and encryption keys are preserved. Actual main admin/user sign-ins and mandatory first-login rotation pass, and anonymous original-asset/save/admin requests remain rejected.
- Ordinary authenticated launch, Continue, Save now, full browser refresh, relaunch and Continue work on the same release image in the isolated port 8081 fixture. Existing main accounts retain their first-login password requirement; fixture test credentials were not copied into the main database.
- An earlier candidate completes an unthrottled three-phase AE/Hard flagship combat benchmark on a powerful desktop: about 60 FPS, update/render work p95 3.9–4.5 ms, one interval over 33.34 ms out of 1,817 frames. The user reports about 5 FPS at Chrome 20x slowdown. Actual weak-laptop performance remains untested. [Method and results](PERFORMANCE.md).

## Known omissions

- Ship/layout eligibility and reference-backed unlock predicates now have focused coverage. Milestone action history, lifetime totals and progression feedback have focused tests. Remaining automatic combat/crew/ship-specific achievements, popup fidelity and full quest-path validation remain unfinished.
- Main-menu Options and Controls work in title, hangar and run contexts. Credits and the interactive tutorial work; all 33 lessons now pass real-time menu/browser input with isolated progress. Exact native tutorial/credit presentation and human play remain open. Scores, run/lifetime counters, five crew records and original split statistics panels have focused browser coverage. Ship/crew naming now works with save/legacy checks. Exact visual details and exhaustive action/timing comparison remain; restart/abandon submission has focused coverage in the warning release.
- Difficulty targeting and final-sector fleet/base rules have focused regression coverage and are deployed locally; broader original-game comparisons remain outstanding.
- Crystal barrier hit points and some ASB visuals/timing are approximations, not validated constants from the original executable.

## Required before full compatibility can be claimed

- Original-game comparisons for encounters, event choices, sectors, stores, upgrades, weapons, crew, boarding, drones, systems, hazards, and simulation timing.
- Legitimate complete runs, victory/death/restart and the complete unlock/achievement/statistics implementation.
- Deterministic future simulation across rendering rates and full combat save/restore sequences; the direct RNG restoration check alone is insufficient.
- Original-game comparisons for the new mechanics and waiting without fuel.
- Reconnect/pending-save recovery, conflicting devices, authentication expiry, browser refresh, and import/export validation.
- Complete desktop Chrome/Edge/Firefox gameplay/failure matrix, full restore-from-backup recovery, and validation through the eventual actual Nginx Proxy Manager configuration. The focused menu matrix passes on all three browsers.

Evidence lives in ignored `.build/` test results. This list must be updated from observed results; a feature is not verified merely because upstream contains its code.
