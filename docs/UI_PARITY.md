# Desktop FTL UI parity audit

Scope: every in-game UI surface in official desktop FTL 1.6.14, with vanilla and
Advanced Edition where applicable. This expands the earlier hangar-only plan.
The surrounding account/launcher website remains separate and uses daisyUI.
Passing input tests or reducing a screenshot difference does not establish 1:1.

## Acceptance and evidence

Use actual function boundaries and call sites in the private native reference,
existing canonical original captures, and isolated browser captures. The user
has requested no further launches of original FTL; use read-only references.
Native print coordinates and this engine's text baselines must be reconciled
before copying numbers. Compare like-for-like state, scale, hover and animation
frame; separate background/ship/crew differences from the control under test.
Verify art bounds, glyph bounds, hit bounds, all states, wrapping, pointer edges,
animations, sound identity and preserved navigation. Keep private evidence under
ignored `.build/ui-fidelity-review/`; do not add assets/decompiler output to Git.

| Surface | Audit targets | Current boundary |
| --- | --- | --- |
| Rendering and input transforms |1280x720 coordinates, fonts, image sampling, letterbox/fullscreen, pointer conversion | Existing scale tests; native top/baseline mapping and state rendering under review. |
| Main menu | All slots, disabled Continue, promotion, logo, tooltips and transitions | Earlier named tests pass; shared button corrections now have three-browser checks. Complete visual/audio comparison remains. |
| Hangar and ship grid | All panels/cards, preview, naming, equipment, layouts, difficulty, AE, indicators | See HANGAR.md; current glyph/frame/crew checks pass. Previous 1:1 claim exceeds sampled evidence. |
| Player HUD | Hull/shields/resources, oxygen/evasion, crew/status/skills/stations, jump/ship/store, reactor and all system/subsystem controls (including artillery, teleporter, cloaking, battery, hacking, mind control and clone bay) | Static native/source inventory begun; complete visual acceptance remains. |
| Weapons and drones | Slot/art/text scale, power/charge/number bars, empty/selected/disabled/hacked/charged states, drag feedback, autofire | Native slot geometry traced; autofire art/states/anchor and frame stretch corrected. Full weapon/drone visual matrix remains. |
| Combat overlays | Target cursors/slot numbers, beam endpoints, reticles/drag feedback, enemy hull/systems/crew, sensor visibility, nebula/asteroids/pulsar/ASB/sun warnings and pause | Existing mechanics tests do not certify presentation. |
| Tooltips and information | Every HUD/equipment/system/crew tooltip, placement, typography, wrapping and hover | Hangar sampled; combat/system tooltip matrix remains. |
| Ship windows | Upgrade, crew/customization/dismissal and equipment/inventory tabs | Navigation/mechanics covered; per-state native geometry remains. |
| Store | All pages/tabs, buy/sell, repairs, system slots, confirmation and unavailable states | Purchase mechanics covered; native presentation remains. |
| Maps | Beacon and sector maps, fleet/quest/exit/store markers, path/range/tooltips, wait/distress and confirmations | Native visual acceptance remains. |
| Events | Dialog panels, options/keyboard selection, disabled/blue choices, rewards/items and confirmations | Existing event/tutorial checks; complete wrapping/state matrix remains. |
| Pause/options/controls | All pages, sliders, buttons, remapping, confirmations and notices | Existing input/fullscreen tests; native font/art/state comparison remains. |
| Statistics/achievements | Populated scores, records, family/layout selectors, locked/earned decorations and popups | Earlier populated browser checks; native typography/decoration matrix remains. |
| Audio/animation feedback | Hover/confirm/cancel/error samples, power/autofire actions, music sources/envelopes, pulses/flashes and dialog/scene transitions | Earlier sample/source continuity checks; native timing/loudness/complete action matrix remains. |
| Tutorial/credits/endgame | Instruction/pulse/Continue, credits, victory/defeat/game-over transitions | All 33 tutorial lessons pass in three browsers; detailed visual/timing acceptance remains. |

## Review of Gemini's work

Gemini branch `codex/native-button-fidelity` contains 26ccb06 (buttons) and 7494648
(8099 deployment alias) after 6697b37. The saved follow-up plan addresses hangar
headers/crew cards only; it has no all-UI implementation. No Git remote or PR is
configured. Codex's review/corrections use `codex/ui-fidelity-review`.

The private `scratch/find_ui_layout.py` scans every byte as an instruction start.
This can decode the middle of genuine instructions and produce false matches;
it also neither identifies the constructor nor traces final screen transforms.
Use existing Ghidra functions/instructions and callers instead.

Known concerns to resolve: generic filled TextButton rendering versus call-site
outline styles; copying native top coordinates into a baseline API; crew card
alignment and empty equipment states; custom autofire label rendering and an
arbitrary vertical weapon-frame stretch. None is certified by the previous 15
hangar input checks. Test groups must cover the affected shared call sites.

No progress reset or save compatibility bump is part of this work. Protect
accounts, browser/native saves, keys and unrelated security drafts throughout.

## Confirmed corrections in review

| Item | Reference-confirmed behavior | Implementation / verification |
| --- | --- | --- |
| Shared buttons | Inactive164/171/160; idle235/245/229; hover/selected255/230/94; text25/49/51. SIMD initializer0086a040, renderer00591f00. | Corrected mapping/glyph centering; white/yellow rendered checks pass in three browsers. |
| Hangar headings/cards | Native print takes top coordinates; previous headings2px high. Crew constructor00544180: art-4/+58, fill+4/+70 (92x12), HL1 text, hit+0/+66 (100x20). Private plan numbers conflict with the constructor. Empty equipment off art. | Corrected; original heading rows checked. Final glyph/crew-bound checks pass in three browsers; images inspected. |
| Weapon geometry | Generic box95x39 with weapon charge inset8 ->87x39 visible slot, stride97. Constructors00571dc0/00506e30/0050cfd0. | Retain size; remove arbitrary8px frame stretch and set weapon wrap55. Reported undersizing is not resolved for every weapon-related element. |
| Autofire | Dedicated segmented background; width=text+8/height24/inset4; relative right edge408/311 and y63. Off gray/on yellow, hover50% toward white; hidden at2 slots. Constructor00568c70/render0056cfd0/state0056ba10 and SIMD palette sources. | Implemented; pixel/input/tooltip/state checks pass in Chrome/Edge/Firefox; HUD images inspected. Desktop uses generic tooltip_autofire with both current bindings; on/off strings are iPad-only. |
| Ship-grid tabs | Shared segmented type_button_frame.png; inner height22/inset3 and English width83/stride87, native title top118. Constructor00529940 and original grid capture. | Corrected missing frame/overlarge fills; title glyph/frame checks pass in three browsers; images inspected. |
| Origin | Server requires Host to equal PUBLIC_ORIGIN; 8099 origin rejected 8080 pages with 400. | Restored and deployed localhost:8080; home/readiness 200, anonymous private asset/save requests 401. |

New tests/ui-presentation.mjs runs inside packaged Menus, checking rendered pixels
and pointer edges in a disposable fixture and restoring progress/RNG. No original
executable runs in these checks. All-UI acceptance remains open beyond these slices.

## Maintained source entry points

- Rendering/fonts/pointer transforms: SILFontLoader.kt, rendering/Graphics.kt,
  game/Button.kt and browser adapters; font rounding/kerning needs a wider audit.
- Title/hangar/grid: game/MainMenuState.kt, hangar/HangarPresentation.kt,
  StartGameButton.kt, ShipList.kt and SelectShipState.
- Run HUD/combat/hover: game/PlayerShipUI.kt, InGameState.java,
  rendering/ITooltipProvider.kt, InfoPanel.kt and SystemButtonTooltip.kt.
- Ship management: ShipWindow.kt, ShipEquipmentPanel.kt and crew-specific panels.
- Store/travel/events: StoreWindow.kt, JumpWindow.kt, SectorMapWindow.kt,
  DialogueWindow.kt and ConfirmationWindow.kt.
- Pause/settings: PauseWindow.kt, OptionsWindow.kt, HotkeysWindow.kt,
  NoticeWindow.kt and SettingsMenuState.kt.
- Records/end states: ftl/browser/BrowserStatisticsState.kt, tutorial adapters,
  game/CreditsState.kt and GameOverWindow.kt.

Game classes above reside under engine/src/main/java/xyz/znix/xftl unless the
browser package is specified. These are audit entry points, not verified screens.
The current HUD captures freeze early arrival to isolate the controls; they do
not establish ship-flight scale/animation fidelity. No native HUD capture/audio
A/B comparison was obtained in this review. Official hotkeys and remaining
weapon/autofire input mechanics continue afterward; current bindings are used
only to fill the native tooltip template.

## Current verification and deployment

The accepted image passes all seven packaged groups. Six groups passed together
in `packaged-final/results.log`; the engine group passes separately with the
correct fixture origin and all 22 modes in `engine-final/results.log`. Menus has
84 named checks across Chrome, Edge and Firefox; all 33 tutorial lessons and
launcher/diagnostics UI checks pass in each browser. The final rendered images
were inspected. Evidence paths are beneath ignored `.build/ui-fidelity-review/`.

Image `53d127df` is healthy on loopback 8080. The served engine matches the tested
build. Read-only pre/post deployment audits match accounts, settings, current
saves, snapshots and encryption keys; original FTL saves/security drafts are
unchanged. No reset or compatibility change was made. This confirms the named
corrections and preservation, while full 1:1 acceptance remains open.
