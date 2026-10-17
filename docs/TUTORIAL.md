# Desktop tutorial implementation

Target: unmodified desktop FTL 1.6.14, executable hash recorded in
[original behavior notes](ORIGINAL_BEHAVIOR.md). Private instruction text and
artwork are loaded from the owner's DAT, never copied into this repository.

## Implemented foundation

`BrowserTutorialDefinition` reads the 33-step PC order, translations, initial
Continue flags and PC/shared arrows. Fourteen steps initially allow Continue;
nineteen require an action. This does not mean nineteen automatic transitions.

`MainGame.startTutorialSession()` creates an internal vanilla tutorial simulation
with a copied profile and disabled progression popups. Browser save/delete paths
ignore temporary writes, including autosaves and the engine save bridge. Exit
restores the original profile object, RNG and AE mode; existing Continue data
remains available. Restart, failed initialization and shutdown use the same
cleanup. Native Reset excludes tutorial starts from Games played; the browser
constructor now does too. Logging identifies tutorial sessions and ignored saves.

The main-menu Tutorial button is active in the validated menu release. Chrome,
Edge and Firefox each complete all 33 lessons through actual pointer/keyboard
input and the normal frame clock. Exact native presentation and human acceptance
remain open. Temporary
settings edits also stay in the copied profile and are discarded on exit.

`BrowserTutorialController` now implements the reference gate decisions,
Continue latching, completion and window-acceptance restrictions. Its standalone
probe traverses all 33 states with positive/negative observations and passes in
Chrome, Edge and Firefox. This verifies the controller. The subsequent live
adapter batch connects gameplay observations. Original transition effects now have reference-backed fixtures; complete
played/native presentation acceptance remains pending.

## Validated gameplay adapter

`BrowserTutorialAdapter` reads actual engine power, selected crew, room
occupancy/repair/fire, airlocks, explicit player pause, active targets, equipped
weapons and map/ship windows. It belongs to the isolated MainGame session, so
combat restoration replaces its observed simulation without resetting the
controller. Equipment opens on the Equipment tab. Accept and semantic Escape
share the beam-installation and pending-weapon-upgrade restrictions; Options,
focus pause and the console suspend tutorial advancement. Structured logging
exposes the current instruction, Continue enablement and each transition.

The adapter traverses all 33 live-adapter states in Chrome, Edge and Firefox. The new Tutorial actions
launcher check uses real engine actions with explicit fixture setup; it is not
a played tutorial. The first Chrome traversal found an incorrectly constructed
enemy fixture, corrected by using the ordinary ship-spawn operation. Expanded
data/isolation/gates/actions/statistics/achievement checks pass in Chrome, Edge
and Firefox; ten other broad engine modes pass. All 24 launcher checks and seven
packaged integration groups pass. Main 8080 is deployed with unchanged account,
security, run, snapshot and encryption-key hashes. No test workers remain.
Native event/setup wiring is covered in the encounter release below; the
main-menu action is now active after real-time browser and packaged acceptance;
exact native presentation/human play remains unverified.

The Antigravity Gemini 3.8 Flash audit/review is private under
`.build/agy-tutorial-{audit,review}-notes.md`. It provides audit leads, not
verification. Existing exports prove COMBAT_0 reads flag `+0x16c`; the caller/constructor audit now resolves its alias to the explicit pause
flag `009134ac` written by SetPause `0052ce20`. Field `CombatControl+0x98` must not be
treated as a room ID based on the audit. The current-executable armament renderer `0x0056fb70` now confirms weapon-box
flashing for a one-based slot, rather than a targeting restriction. Native
damage parameters are now confirmed below; exact UI metrics remain open.

Gemini supplied the new instruction-overlay file; Codex reviewed and wired it,
corrected geometry/text/button spacing and added input/save protection fixtures.
Its private report is `.build/agy-tutorial-overlay-result.json`.
Native `AllowJumping` at `0x004113b0` permits jumps during the tutorial only in
`DAMAGE_SYSTEM_7`. `AllowUpgrades` at `0x00411440` permits ship-window opening
only at INVENTORY_1 or COMBAT_5; it returns true outside the tutorial. The setup audit confirms hull=2, no additional system/personnel, fire, breach,
ion or stun damage, and friendly fire enabled. DamageArea `0x004a6100` and crew
damage helper `0x0049cf80` confirm the base hull contribution: two system damage
and 30 crew HP in that room. The original lesson damages the empty engine room.

The validated navigation batch applies both native opening permissions in shared
button/hotkey paths and disabled HUD artwork. Already-open map/equipment/upgrade
windows remain usable. Six focused modes per Chrome/Edge/Firefox pass premature
hotkeys, actual toolbar pointer rejection, disabled-button hit consumption,
opening at the permitted lessons, all 33 action states, nested Options/Console/
Exit and ordinary-run interaction. Initial fixture clicks missed the Jump
button's visual offset; the interior hit test is corrected. The candidate is
deployed on main 8080 and isolated 8084; all 30 launcher checks and seven packaged
groups pass, including ordinary-run menu/fullscreen controls. Identity/security/
progress/key hashes match before/after deployment; no workers/job directories
remain. Private evidence: `.build/tutorial-navigation-*` and
`.build/live-{before,after}-tutorial-navigation.json`.
A regression also covers a crew member deliberately placed there.
`TabbedWindow::MouseClick` at `0x00517a90` does not directly read field `+0x141`.
The subsequent loop export at `0x00518af0` does: tutorial mode disables tab
buttons, independently of the `+0x140` acceptance block. The browser now applies
that restriction. `IsStateAfter` (`0x00410f00`) and `IsStateBefore` (`0x00411520`)
confirm the combat panel override is strictly between STAR_MAP_1 and INVENTORY_1;
COMBAT_5 uses the default position. Exports remain private under
`.build/reverse/tutorial-{input,remaining}-reference/`.

## Validated instruction overlay

The internal tutorial session renders original translated instructions, tiled
panels, Continue artwork and screen/window-relative arrows. A visible Exit action
and final Continue return to the main menu, restoring ordinary state and held
input. Panel height reserves an extra row for Exit. Font metrics and arrow pulse
timing still need original-game comparison.

Pointer presses in the instruction panel are captured through release and combat
reload; they cannot select crew or commit an underlying room target. Options and
console suppress the overlay. Gameplay tooltips/cursors are suppressed over its
controls. Native tutorial tab locking is included. Completing/exiting a tutorial
inside MainGame.update cannot publish an ordinary profile save in that frame;
pending ordinary changes remain dirty and save on the next normal update.

All 33 panels, Continue/Exit, modal priority, pointer capture/reload, tab locks and
unchanged render state/RNG pass initial Chrome/Edge/Firefox probes, alongside the
other tutorial/statistics/achievement checks and ten broad modes. Final display
changes pass overlay/isolation probes in all three browsers; all 27 launcher
checks and seven packaged integration groups pass. Main 8080 is deployed with
unchanged account/security/run/snapshot/key hashes and no remaining workers. Explicit
fixture/controller observations supply setup; this is not a played tutorial.

## Verified non-event tutorial entry effects

The four non-event effects now enter through the live adapter, on arriving at a
lesson, before observing its action gate. Session-owned applied-step history
survives replacement combat states. Unsupported IDs, stale states, a different
session and ordinary runs are rejected before mutation/RNG use. Nine focused modes pass in each of Chrome, Edge and Firefox, including entry,
data/isolation/gates/actions/overlay and ordinary interaction/progression. The
expanded overlay fixture exposed gameplay RNG consumed by screen shake during
rendering. A separate cosmetic random source fixes it; ordinary damage/render
checks at 30/60/120/240 FPS preserve simulation and RNG. Nine broad modes, all 30 launcher checks and seven packaged groups pass. The
validated image is deployed on main 8080 with unchanged identity/security/
progress/key hashes. Native encounters/navigation and full acceptance remain open.

- POWER_SYSTEM_1 decreases allocated engine power twice.
- DAMAGE_SYSTEM_1 hits the engine room with the native two-point damage.
- DAMAGE_SYSTEM_2 adds the two localized humans, male then female, in room 1.
- FIRE_PC_1 makes two random fire attempts in room 9. Native CreateFire
  `0x004a1ef0` calls spread helper `0x0076cc20(room, 2)`; sampling has replacement,
  so the two attempts can land on the same tile.

Codex corrected the delegated candidate's unique-tile fire placement, default
crew placement/name fallbacks and personnel-damage suppression. The dedicated
Tutorial setup launcher fixture tests actual early transitions, repair/venting,
replacement saves, duplicate effects, restart and ordinary-run guards. It is not
a complete played tutorial. Gemini's read-only audit confirmed the damage/fire
semantics and identified the room-placement/recruitment path. Direct placement
now avoids an intermediate spawn room and does not count setup crew as career
recruits; ordinary acquisition behavior is retained.

GetEvent is now located at `0x00410a00`, not the old Hyperspace address. It consumes
pending flag `+0x1c4` and creates TUTORIAL_MISSILE once. Advance sets that flag at
COMBAT_4, then `0x0052ce20(0,0)` clears world pause flags; it does not inject the
event itself. World loop `0x0061b930` polls GetEvent while the tutorial is running and uses
the returned event. The browser candidate queues the event at COMBAT_4, clears
player pause, delivers once when no modal window is open, and retains pending/
consumed history across combat replacement. Empty missile stock adds ten on each
tutorial update, following OnLoop 0x004161d0. Existing event Continue processing
grants the weapon and rewards; it is not a direct diagnostic item grant.
Private exports are in `.build/reverse/tutorial-{setup,navigation}-reference/`.

## Native encounters and normal-mechanics traversal candidate

The new Tutorial encounters launcher probe uses actual map selection, arrival,
event choices, both pirate reward routes, ammunition replenishment, modal deferral
and combat restoration. Its controller observations, initial FTL charge and
controlled deaths are fixture setup; it does not establish played acceptance.
Initial Chrome coverage passes. Three-browser/regression/release checks remain.

A stricter blueprint assertion found that ShipGenerator cleared the tutorial
pirate's authored laser and rejected its one-power weapon as a procedural pool
candidate. Direct `blueprint` specifications now retain their authored equipment
and system levels; `auto_blueprint` remains procedural. The normal-mechanics
traversal now requires a real enemy volley, so an unarmed pirate cannot pass.
The official impossible pirate uses the same fixed-blueprint path; broad enemy
matrices must cover the change before deployment.

Tutorial traversal independently performs all 33 lessons with normal repair,
venting, FTL charge/jump, firing and combat rewards, cargo drag and a paid upgrade.
It uses time-stepped automation, with no fabricated controller observations,
health overrides, charge/resource grants or enemy-fire suppression. Initial
Chrome coverage passes, including ordinary profile/run/RNG/mode restoration.
This does not replace human play or native visual/timing comparison.

Actual final Continue release exposed click-through into the new main menu.
Input cancellation now releases without synthesizing clicks, and unmatched
physical releases cannot produce clicks. Menus/console receive their input
reference at state initialization, including immediately after combat restore.
Crew defeat also needs the active combat target predicate: a defeated hull can
remain visible for recovery while `getEnemyOf(player)` is null. Its gate
fixture passes in Chrome, Edge and Firefox. Native CheckState reads ShipManager+488;
correspondence with this browser predicate is an implementation interpretation.

## Native gate interpretation

CheckState `0x004149e0` returns false immediately when Continue is already
enabled. Some branches enable Continue and still return false; others return
true for automatic advancement. The assembly return paths distinguish these
cases. Once enabled, Continue remains available for the current step.

| Step | Required observation | Result |
| --- | --- | --- |
| POWER_SYSTEM_2 | Engine allocated power is positive. | Enable Continue. |
| DAMAGE_SYSTEM_2 | Crew selection contains at least one member. | Advance. |
| DAMAGE_SYSTEM_3 | Crew occupancy in the engine room is positive. | Advance. |
| DAMAGE_SYSTEM_4 | Engine system health reaches its full current level. | Enable Continue. |
| FIRE_PC_1 | An exterior airlock for room 9 is open or has its alternate open flag. | Advance. |
| FIRE_END | Room 9 has no remaining fires. | Enable Continue. |
| DAMAGE_SYSTEM_6 | Crew occupancy in the pilot room is positive. | Enable Continue. |
| DAMAGE_SYSTEM_7 | Star map is open. | Advance. |
| STAR_MAP_1 | Star map is closed. | Advance. |
| COMBAT_0 | The tutorial records a player pause action. | Advance. |
| COMBAT_1 | A non-missile weapon is powered. | Advance. |
| COMBAT_2 | A non-missile weapon has fired. | Advance. |
| COMBAT_4 | A missile weapon has fired. | Advance. |
| COMBAT_4_6 | The player ship has no current enemy target. | Advance. |
| INVENTORY_1 | Ship information is open; select its Equipment tab. | Advance. |
| INVENTORY_2 | Ship information is closed. | Advance. |
| COMBAT_5 | Upgrade window is open. | Advance. |
| UPGRADES_1_2 | Upgrade window is closed. | Advance. |
| UPGRADES_2 | A beam weapon is powered. | Advance. |

The live adapter now covers these observations with focused engine fixtures;
complete native equivalence is still unverified. The
door's alternate flag still needs its complete setter audit. The enemy-null
condition uses player ShipManager offset `0x488`: other callers use the same
pointer for enemy system queries and retaliatory targeting, so it is not a hull
value. `GetRoomAirlocks` (`0x0048f6f0`), `GetSystemRoom` (`0x0049e8e0`),
`GetSystem` (`0x0049e7c0`) and the weapon-list accessor (`0x004a1500`) corroborate
the gate inputs. The weapon fields are powered `0xf6`, fired `0x610`, and blueprint
type at `blueprint+0xb8` (missile 1, beam 2).

Loop `0x004161d0` also restricts window acceptance. Equipment cannot be accepted
until a beam is installed, and the upgrade step requires a pending weapon-system
upgrade before its window can be accepted. Those restrictions must apply to
mouse, keyboard and semantic cancellation, not only to the instruction button.

Entering POWER_SYSTEM_1 invokes the force-decrease helper `0x0049e690` twice
for engines. The actual helper dispatch matches ForceDecreaseSystemPower's
virtual slot (`0x84`); an older generated structure header labels those slots
incorrectly, so it must not be used as the behavioral authority. Thus the lesson
removes allocated power, rather than installing/upgrading engines. The tutorial
map generator (`0x005f6540`, tutorial branch) assigns TUTORIAL_ENEMY to empty
locations. Starting-beacon constructor `0x005f9e00` chooses TUTORIAL_START while
tutorial mode is active. The browser candidate now selects TUTORIAL_START and
TUTORIAL_ENEMY for unassigned regular beacons, preserving preassigned exits. CommandGui loop
`0x00536770` uses AllowJumping/AllowUpgrades to enable the HUD buttons, corroborating
the browser opening restrictions. The DAT's TUTORIAL_MISSILE grants MISSILES_2_PLAYER, and both pirate
defeat routes grant BEAM_2 plus fuel/missiles/scrap. These existing event paths
should supply the encounter and inventory items. Exact injection timing and
the existing native caller audit confirms once-only delivery, unpause and missile replenishment.

## Remaining implementation and acceptance

- Initial/map events, pirate combat, once-only missile delivery, beam/reward
  events and missile replenishment are implemented. Eleven focused modes pass
  in Chrome/Edge/Firefox, including a full 33-step normal-mechanics traversal;
  nine broad modes cover 138 layouts/difficulties, 137 encounters and twelve
  accelerated campaigns. All 36 launcher checks and seven packaged groups pass; main deployment
  preserves account/security/run/snapshot/key hashes and readiness/protections.
  The pirate retains its authored laser, corroborated by native direct-blueprint
  creation; complete level/crew override behavior remains unverified.
  Advance `0x00415720` applies effects on entering the next state; avoid
  reversing the timing. Native application dispatch `0x00402350` starts the
  tutorial manager before creating its world and suppresses ordinary run saving
  on tutorial exit. Stop `0x00410af0` releases tutorial window/input restrictions.
- Event and entry histories survive simulation replacement within the isolated
  session, with once-only/reload/stale/ordinary guard coverage. A full browser
  refresh returns to the ordinary saved run; tutorial state is not written over it.
- Corroborate any additional encounter restrictions and fixed-blueprint generation.
  Native map/start/filler events and existing map/ship permissions are implemented.
  Crew defeat uses the active combat target; neutral visible-hull negatives pass.
- Exact native typography/pulse/highlight comparison and human acceptance remain.
  Main-menu Tutorial, full real-time input, fullscreen Exit/reload and ordinary
  Continue now pass in Chrome/Edge/Firefox and are deployed.
- Normal-mechanics action traversal passes in Chrome/Edge/Firefox, including
  actual enemy volleys, repair/venting, rewards, cargo drag and paid upgrades.
  Complete human/native presentation and exhaustive reconnect acceptance. Observe the original with
  isolated original-game progress if timing or presentation remains unclear.

Private read-only references live in `.build/reverse/tutorial-reference/`,
`tutorial-gate-reference/` and `tutorial-world-reference/`. Decompiler output
and assembly listings stay ignored. The original game has not been launched
and its saves have not been modified for this work.

## Native highlighting release

BrowserTutorialVisuals keeps UI-only session clocks for the native triangular
0.75-second arrow/power alpha. Arrows use the original white texture tint; the
matching weapon box paints red required-power bars under ordinary status colors.
Slot selection follows native 1 at COMBAT_1 and 3 at COMBAT_5; a missing third slot
does not add capacity/equipment. Existing empty boxes still advance the native
flash clock. Ordinary/stale simulations have no tutorial flash. These clocks
never consume gameplay RNG or serialize into runs/profiles.

Nine-mode probes pass in Chrome/Edge/Firefox on the final empty-box candidate.
The initial 30-FPS comparison rounded a quarter-second to seven frames; a complete
second per rate fixes the unequal test duration. Captured panels show the red
power/arrow indication and separate Exit text. All 36 launcher checks/seven
packaged groups pass and preserved-data main deployment is verified. Exact typography, frame-edge pulse rounding and full
native presentation/human play are not established.

## Native typography and fullscreen

Instruction font ID12 maps to11Bold; native SIL metrics establish baseline12 and
line spacing20, replacing the browser's 12Bold/19px values. Continue uses font63
(HL2 3x), native170x32 hitbox and separate218x54 glow at offset-23,-7. All 33 panel
texts fit wrap width308 and the separate Exit row. Nine focused modes per browser
and all27 menu checks pass, including overlay/Exit and normal-mechanics completion
inside actual fullscreen. Screenshots inspected. All 36 launcher checks/seven
packaged groups and preserved-data main deployment pass; evidence:
`.build/tutorial-typography-*`. Real-time menu/pointer play now passes as described
below; native word-wrap,
hover/sound/frame-edge timing and complete presentation remain unverified.

## Real-time main-menu acceptance

`tests/tutorial-ui.mjs` uses the actual main-menu entry, normal dialogue choices,
pointer/keyboard input and animation-frame timing, with read-only diagnostic
coordinates for assertions. It completes all 33 lessons in Chrome/Edge/Firefox
and verifies enemy fire, repairs/venting, normal combat/rewards, cargo drag and
paid upgrades. Exit/reload retain exact ordinary run/profile buffers, and Continue
restores ship/AE/difficulty/hull; mouse Exit/completion retain fullscreen. Nine
checks/99 lesson visits pass with no page/engine errors. The menu packaged group
runs the same complete traversal in all three browsers on isolated fixtures.
All 36 launcher checks/seven groups and preserved-data deployment pass. Evidence:
`.build/tutorial-menu-*`. Native text edge crowding, exact word wrapping,
hover/sound/frame-edge timing and human play remain unverified.

These runs found direct hangar hit-test and cancelled primed-shot save bugs,
corrected with focused no-draw/depower/restore/re-power regressions. Tutorial
saves skip serialization while preserving ordinary checkpoints. Firefox
closed-context gesture/launch audio cancellation has a shared regression.
