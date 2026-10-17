# Main-menu and hangar presentation

This audit targets the owner's official desktop FTL 1.6.14 assets and executable.
Reference-confirmed behavior is distinguished below from complete visual/audio
fidelity, which is not established by compilation or automated input tests.

| Area | Confirmed corrections / implementation | Verification boundary |
| --- | --- | --- |
| Main menu | Seven fixed slots, disabled Continue, original images, right edge1200, inset hitboxes, logo at820/60 with three-second pulse, original promotional banner/four-frame mech, hover sample and title track. | Geometry/states/pulse/banner have private native references and live observation. Three-browser navigation, final images and mech frame-change tests pass. The original Steam version footer is not a browser build identifier. |
| Preview | Native layout offset plus365/30 instead of hull centering; room rendering, crew at starting stations and half-second engine jets on the six native Kestrel/Federation hulls. | Kestrel/Engi/Zoltan observed natively; graphic initialization confirms jet eligibility/offsets. All46 vanilla/AE previews pass isolated render/identity/RNG regression; corrected three-browser images inspected. Exhaustive per-hull pixel equality and weapon launcher animation remain unverified. |
| Selection | Original ten-family grid, global A/B/C tabs with original segmented frame and filled states, locked silhouettes/white borders, unavailable Type C cards, progression arrows and localized original family/layout unlock descriptions. Family decorations/arrows persist across tabs; locked families have the native20-pixel card offset. AE-only silhouettes stay visible with default content; unavailable layouts remain unselectable. | Native grid constructor and live AE-on/off/Type-C observations support geometry/display. Three-browser arrow/decoration image comparisons and random/layout eligibility tests pass. Layout B/C copy uses the original blueprint unlock keys. Existing unavailable-layout fallback is preserved. |
| Naming | Rename appends at the end, with no select-all operation; arrows/Home/End/Backspace/Delete, sixteen-character cap, yellow blinking cursor and blank-name fallback. Enter and Escape both confirm editing. Escape from non-editing Customize returns to the main menu. Crew changes use the original Customize panel and Accept. | Native text-input/hangar input references and live Rename/Ctrl+A/Escape observation. Crew modal blocks underlying controls/Start. Tests cover physical Escape outside fullscreen and visible Back inside fullscreen, where Firefox reserves physical Escape. Native input repeat/Unicode/clipboard edge cases remain unverified. |
| Crew / equipment | Four crew placeholders, native portraits/cards, separate Customize buttons, installed-system strip in native order with power bars, weapon/drone summaries, three augment slots. Crew and equipment information panels use the native fixed right placement and300-pixel wrapping. Hangar equipment omits combat Tips and ordinary hull-derived crew-damage lines; vanilla omits AE stun chance. | Live Kestrel/Engi tooltip/card observations and private constructors. Three-browser portrait/modal/equipment images inspected and regressions pass. Normalized Easy-panel comparison confirmed and corrected a3px/4px frame/text offset and2px paragraph gap; corrected frame edges agree within about1px of resampling. Final affected three-browser checks and images pass. Detailed system information height/power presentation is not yet certified pixel-identical. |
| Progress | Original achievement borders/locks, trophy, layout dots only after a victory, quest marker and localized earned difficulty/victory copy. Removed extra difficulty stars. Grid indicators expose the same information. White tooltips use native12-bold title/11-bold body,350-pixel titled wrapping, pointer offset and edge placement. | Native achievement/tooltip renderers, grid/hangar constructors and earned-progress observation. Populated disposable-profile browser regression/screenshots pass in Chrome/Edge/Firefox. Every difficulty's native tint and quest-hover copy are not yet exhaustively compared. |
| Hover information | Immediate hangar/grid tooltips and information, including the fixed right-side difficulty panels; clear on leaving the target. | Native achievement/grid hover paths explicitly bypass the global cursor delay. Native Easy panel observed. The removed0.5-second delay was a port behavior; frame-based hover/difficulty regressions pass in all three browsers. |
| Audio / transitions | Original hover sample once per button entry; retain active SoundManager/title source across AE changes. | Reference/source identity and transport continuity tests pass in all three browsers. Firefox Tests use a private PulseAudio null sink for a real playback clock. Subjective loudness, crossfade/envelope timing and original audible A/B comparison remain unverified. |
| Browser navigation | Back/Options/Controls retain selection and fullscreen; daisyUI remains outside the canvas. | Intentional browser additions. Packaged navigation/fullscreen and saved-run AE regressions pass; final affected hangar checks pass in Chrome/Edge/Firefox. |
| Button presentation | Native45-degree chamfers; inactive gray #A4ABA0, idle white #EBF5E5, hover/selected yellow #FFE65E, text #193133. Native top coordinates are converted to engine baselines; headings move2px down. Crew Customize art at card-4/+58, inner fill+4/+70 (92x12), HL1 text; hit+0/+66 (100x20). Grid tabs use the native segmented frame and22px height. Empty equipment uses off art. Start/Difficulty retain native frame/geometry from Gemini's change. | TextButton00591800/00591f00 and SIMD palette initializer0086a040 confirm state mapping; previous Gemini palette claim was incorrect. Native crew00544180 overrides the inner paint rectangle with a broader hit rectangle, contradicting the private plan. Existing original captures support heading/empty-card corrections; rendered-state checks passed in three browsers. Final crew/grid candidate images inspected in all three browsers; prior screenshot-error reductions are not1:1 acceptance. |

Ship `displayName` is an optional additive save attribute. Blueprint identity and
progression keys remain unchanged. Older saves use the blueprint title. Restart
retains the ship name; a new crew is generated by the existing run creation path.
Preview naming/random selection use a cosmetic RNG, never gameplay RNG.

AE remains a new-run hangar preference. Continue/restore use the saved run's mode.
No compatibility bump or final progress reset belongs to this audit.

`BrowserHangarDiagnostics` checks all46 previews, profile/RNG isolation, locked
selection/Start, arrows, names/colours, AE retention, save/restore/legacy/restart.
`tests/hangar-ui.mjs` is part of the packaged Menus group and covers real
Chrome/Edge/Firefox input, fullscreen, hover samples, title-source continuity and
populated earned decorations. Only disposable fixtures are permitted.

Earlier, all seven packaged Tests groups passed in the broad isolated run, including all22
engine modes and all33 tutorial lessons in each browser. The final menu/hangar
changes were checked again on the accepted image in all three browsers. Evidence:
`.build/native-fidelity/packaged-confirmed/results.log` and
`.build/native-fidelity/focused-accepted/results.log`; screenshots accompany the
focused evidence. These checks verify the named behavior, not complete fidelity.

The current review image53d127df passes all seven packaged groups, including
all22 engine modes and84 menu checks across Chrome/Edge/Firefox. Final hangar,
grid and HUD captures were inspected; affected rendered pixels, glyph rows and
pointer edges pass. Evidence is under `.build/ui-fidelity-review/packaged-final/`
and `engine-final/`; see [all-UI review](UI_PARITY.md). Full1:1 remains open.

The accepted image is deployed locally on loopback8080. Served engine identity,
readiness and anonymous private-asset/save protection pass. Read-only database
and key comparisons prove accounts/browser progress/keys unchanged; original
native saves and unrelated security drafts also retain their baseline hashes.
No progress reset or save compatibility change was performed.

Private reference exports, original assets, isolated native progress and captures
remain ignored under `.build/reverse/` and `.build/native-fidelity/`. The native
copy changes only a same-length save-folder literal; the installed executable and
original save files are untouched. Save hashes and deployment data checks are
maintained privately.

Relevant references: main initialization004cdeb0/custom button004ca260 and
render004cbb70; hangar geometry004f1dd0; text input004ff810; crew00544180;
systems004ed4d0/004df610; achievements004ed2f0/0042b7d0;
grid00529940; tooltip004e5ed0; hover004ebea0/00527b00/0042ed30;
hangar Escape004f1bd0;
ship graphic/thruster initialization00496720. These are evidence
for the named slices, not a claim that every related presentation is complete.
