# Hyperspace presentation (render workstream)

Package `xyz.znix.xftl.hs.render`. Everything is gated on `HS.active` (Lua stages on `HS.luaActive`); outside
Hyperspace every getter returns the vanilla value and no stage runs. Ported from FTL-Hyperspace (GPL-3.0-or-later)
commit 1b05a07; see each file's provenance line.

## Lua render stages (`script.on_render_event`)

`HsRenderEvents` implements HS's contract (lua/RenderEvents.cpp): push the arguments, run the pre callbacks, draw the
original unless a pre callback returned PREEMPT (negative), run the post callbacks from the same index, pop the
arguments. A matrix a pre callback pushes stays on for the original drawing and the post callbacks; after the post
callbacks only the pushes made since that stage's pre are popped, so nested stages (SHIP_STATUS inside GUI_CONTAINER,
MV's screen shake) keep the outer push.

| Stage | Wraps (port) | FTL function | Args |
| --- | --- | --- | --- |
| GUI_CONTAINER | `InGameState.renderStatic` | CommandGui::RenderStatic | - |
| LAYER_BACKGROUND / FOREGROUND / ASTEROIDS | environment `renderBackground`, one pass each (`hsPhase`) | SpaceManager::OnRenderBackground / OnRenderForeground / OnRenderAsteroids(3) | - |
| LAYER_PLAYER | `InGameState.renderPlayerShip` | CommandGui::RenderPlayerShip | - |
| SHIP_MANAGER | `Ship.renderManager` | ShipManager::OnRender | ShipManager, showInterior, doorControlMode |
| SHIP | `Ship.render` around the whole ship drawing | Ship::OnRender | Ship |
| SHIP_FLOOR | rooms loop in `Ship.drawInterior` | Ship::OnRenderFloor | Ship, experimental (false) |
| SHIP_HULL | hull, thrusters, cloak in `Ship.render` | Ship::OnRenderBase hull part | Ship, alpha |
| SHIP_BREACHES / SHIP_SPARKS | hull breach and spark loops in `Ship.render` | Ship::OnRenderBreaches / OnRenderSparks | Ship |
| SHIP_JUMP | `Ship.render` while the jump zoom runs | Ship::OnRender jump pass | Ship, alpha |
| SHIP_ENGINES | `HsThrusters.render` | Ship::RenderEngineAnimation | Ship, showEngines, alpha |
| SHIP_STATUS | `PlayerShipUI` top bar + More Info button | ShipStatus::OnRender | - |
| FTL_BUTTON | the jump button | FTLButton::OnRender | - |
| CHOICE_BOX | `DialogueWindow` render | ChoiceBox::OnRender | ChoiceBox |
| LAYER_FRONT | `HsRenderEvents.layerFront` after the player ship, with the jump scene | SpaceManager::OnRenderAsteroids(1), jump zoom above zero | - |
| SPACE_STATUS | `drawSpaceStatus` | SpaceStatus::OnRender | - |
| MOUSE_CONTROL | `InGameState.renderMouseControl` | MouseControl::OnRender | - |
| MAIN_MENU | lua workstream (`LuaEvents.mainMenu`) | MainMenu::OnRender | - |

Not wired: CREW_MEMBER_HEALTH, TABBED_WINDOW (WP7 UI). The ids HS 1.23.2 does not define (LANGUAGE_CHOOSER, SHIP_BASE,
SHIP_SYSTEM, SHIP_BLACKOUT, SHIP_WALLS, SHIP_EXPLOSION, LAYER_PLAYERFRONT, SYSTEM_CONTROL, COMBAT_CONTROL, CREW_CONTROL)
are nil in `Defines.RenderEvents` on both sides (oracle wp7r-auth `missing=`). LAYER_FRONT / SHIP_JUMP fire only while the jump
zoom is above zero (the oracle never fires them in a still frame). Oracle jump row (wp7-render9: START_BEACON, JUMP, the first
neighbour, jump-out and arrival): SHIP_JUMP 240 calls with two arguments (`Ship *`, progress, 0 on the first call), LAYER_FRONT
239 calls with none, the first SHIP_JUMP one tick before the first LAYER_FRONT; the browser reports the same arguments and
the same `missing` list (`b-jump`: SHIP_JUMP and LAYER_FRONT 58 each over the jump-out frames; the one extra first-frame
SHIP_JUMP is not matched).

Order difference: FTL draws the ship status after the FTL button and space status (their oracle markers are covered
by it); the engine draws the status first. Lua drawing in those stages lands in the same place, only the overlap
order differs.

### Frames

Measured with marker scripts against the oracle (`.build/render/sessions/markers.json`, oracle
`.build/hs/oracle-render/run2`):

- Screen stages (GUI_CONTAINER, SHIP_STATUS, FTL_BUTTON, SPACE_STATUS, MOUSE_CONTROL, LAYER_PLAYER): origin (0, 0).
- Ship stages share one frame per ship: the ship's render origin, where room pixels are `35 * (room.x + X_OFFSET)`
  and `35 * (room.y + Y_OFFSET)` on both sides. Player: (200, 170) with an enemy, (350, 170) without, (150, 170) with
  the boss, plus the layout's HORIZONTAL / VERTICAL (CommandGui::AddEnemyShip 0x5326b0, ClearLocation 0x532940).
  Vanilla layouts carry it too (Kestrel B VERTICAL -16, Federation B 27 / -17, ...) and the engine applies it for
  both (native 1.6.14 capture vf-run: Federation B drawn 27 px right and 17 px up). Enemy: (935, 130)
  for the oracle's explorer, equal on both sides.
- So points handed to Lua (room centres, damage areas) are ship-frame pixels, not screen pixels.

## Features

| Feature | Files | Verified |
| --- | --- | --- |
| `<titleScreen>` splash, logo position, hyperspace menu images; ITB banner removed | HsTitleScreen, MainMenuState | oracle 01-menu |
| `HS-1.23.2 ` label (altMode) | HsOverlay | oracle frames |
| `<hullNumbers>`, `<hullBars>`, `<playerHpColorFix>` | HsHullNumbers, PlayerShipUI, HostileShipUI | oracle 10-fight |
| More Info button, altMode options (applied at CommandGui::OnLoop), the info key (Right Alt) | HsMoreInfo, HsHotkeys | oracle r2-beacon 06-06e, ib-mv, ib-hs |
| `<thrusters>` in layout xml | HsThrusters | oracle r2-militia 11c (phase differs) |
| `<colors><textButtons>`, `<systemDetails>` | HsColors and the TextButton call sites | oracle 11 (jump), 10 (autofire), 14 (pause) |
| `<eventButton>`, `<clearEventButton>` | HsEventButtons, PlayerShipUI.updateButtons | oracle 04 (positions), r2-beacon 08/09 (jukebox and toggle clicks) |
| event tooltips | HsEventTooltip, DialogueWindow | port only |
| scrolling event box (bar, arrows, wheel) | HsScrollingChoiceBox, DialogueWindow | oracle r2-beacon 08b-08c, 09b-09c |
| `roomAnim` (anim, tileAnim, wallAnim; layers 0-4) | HsRoomAnims, Room, Ship, HsShipState | see the round 2 report (r2-ships) |
| enemy ship icons (`<shipIcons>`, augment icons), their tooltip left of the cursor | HsShipIcons, HostileShipUI, NativeTooltip | oracle r2-delta 10d-10f |
| `<bossShip>` enemies in the flagship box (CustomEvents.cpp boss_visual) | Ship.hsBossShip, HostileShipUI | oracle r2-delta 10b (ship pixel-exact) |
| `<backgroundObject>` (layers, anims, hover/inactive, tooltips, click/anim actions, req, save) | HsBackgroundObjects | oracle mv-seed-12345 05, r2-beacon 05b |
| `<backgroundObject>` `timerAction` (fires the event once), `transform` / `noTransform`, `random` animation start (`random32() % frames`), the front layer (HS swaps the `front` / `playerfront` names; `triggerActiveOnly` is parsed and unused); the pending list is saved | HsBackgroundObjects | oracle wp7r-auth bg-0..2 (timer event fires, front layer draws; the random start differs per run) |
| `<customChoiceColors>` (`color=` on a choice text: normal, hover, disabled, blue_option; state persists across frames) | HsColors, DialogueWindow | oracle wp7r-auth choices-a / choices-hover (normal, hover); blue / disabled from the source (the oracle corrupts such texts, a dangling `c_str()`) |
| `<eventButton>` text buttons (`text`, `border`, `color`, `selectedColor`, `inactiveColor`, `textColor`, `hideInactive`, tooltips); the base image drawn in three parts when wider than the image | HsEventButtons | oracle wp7r-auth btn-bar / btn-hover-a..c (base, chamfer, text, tooltip) |
| `warningLightPositionFix`, `cloakRenderFix`, `hideHullDuringExplosion` | Room, Ship, PlayerShipUI | warning lights: oracle wp7r-fix / wp7r-nofix show the flames at the same strip positions; the other two source-only |
| `artilleryGibMountFix` (global option or `<customShip>` flag): on a wrecked ship an artillery whose layout mount gib is n rides gib n (drawn in the gib's matrix under its image) and is no longer drawn in place | Ship.renderDestruction, ShipGib.draw, Artillery.renderOnGib | oracle wp7-render8 (flagship stage 1, option on / off): the artillery mount rows (gib, x, y: 4/163/6, 4/347/6, 4/428/-7, 2/82/-7) equal the browser's `projectileFactory.mount`; gib drift is random per run so the frames match in structure only (the artillery stands upright in place with the option on, the leftmost gib's turret tilts away with it off, in both). The boss's jump-out variant (Ship::OnRenderJump: the last gib's artillery, fading with the hull) rides the stage 1/2 jump-out wreck (round 14, HS_UI.md fourth pass) |
| Reactor bar beyond 25: at most 27 bars with `+N` / `+` above 29 (RenderReactorBar.cpp) | PlayerShipUI.drawReactorPower | oracle wp7r-fix reactor32 (text position) |
| `<systemPositions subSystemOffset staticOffset>` for pilot, sensors, doors, battery (the static subsystem box; `position` and the dynamic layout are not implemented) | HsSystems, PlayerShipUI | oracle wp7r-x6 (box 65 px left, doors 18 px right of the default; default frame equal) |
| `alternateOxygenRendering` (gradient, Room floor colour from lastO2) | HsAlternateOxygen, Room | oracle wp7r-alt4 / alt5 (gradient and default gradient, with altMode off: More Info on, the default, forces it off in HS too) |
| Lua render classes: CSurface drawing (lines, circles, triangles, partial blits, pixel images, colour state), Point / Pointf (operators, Distance, RelativeDistance, Slope, Normalize, RotatePoint, constructors), GL_Color.FromHSV, GL_TexVertex / GL_ColorTexVertex / GL_Line, GL_Texture, Animation / AnimationTracker (Start, StartReverse, SetProgress, SetLoop, Stop, Progress, GetAlphaLevel, RandomStart) | LuaAdaptersWP7Render, LuaAnim, LuaGraphics | oracle wp7r-cls rows: 122 of 130 equal (rest uninitialised memory, wall-clock frame time, random start) |
| redesigned weapon/drone/augment/crew tooltips, tooltip placement and scrolling (1.23.2) | HsTooltips, InfoPanel, NativeTooltip | oracle r2-beacon 07/07b |
| custom hotkeys (info, speed, console, drone 4-6, temporal, aim artillery), controls pages | HsHotkeys, HotkeyManager, HotkeysWindow | oracle r2-menu 04 (4 pages) |
| `<customCredits>` roll (altCreditSystem), credits music | HsCredits, CreditsRoll, GameOverWindow | oracle r2-menu c00-c30, r2-victory (spacing equal) |
| SpaceManager shade: no focus window (event ChoiceBox, store, upgrades, map, menu) shades the backdrop / background objects, full-focus ones only tint the scene 0.15 (vanilla too) | AbstractEnvironment.pauseShade, InGameState.isShadePaused | oracle r2-beacon 08b, mv-seed-12345 04/06/08/12, ships-rng-101 store/pause-menu; vanilla 1.6.14 captures vn-slug, vn-win, vn-win2 |
| System wires only with more than seven system boxes (HS RenderPowerBar; vanilla 1.6.14 draws them from two boxes on, 0x4c7530 compares the vector's byte size with 7, capture vn-slug); SUBSYSTEMS label in COLOR_BUTTON_TEXT; no glow for an empty hazard warning text | PlayerShipUI | oracle r2-ships (Sylvan, Rhyme) |
| Mind control button off without intruders or a target with crew (MindSystem canUse; vanilla uses the full 1.6.14 rule in MindControl.canUse) | MindControl | oracle r2-ships (Rhyme) |
| Enemy system row in the boss frame at boxPosition + (80, 535), 45 px left past twelve systems; flagship at (269 - shipBox.w / 2, 127) from the combat origin (vanilla too) | HostileShipUI | oracle r2-delta 10b/10d, r3-flag1 (stages 1-3) |
| Enemy ship augment icons in augListWithHidden order (libstdc++ unordered_map) | HsShipIcons | oracle r3-flag1 |
| Augment no-effect warning (InfoBox::warning) under the title in (255, 120, 120) | InfoPanel, HsTooltips.augmentWarning | port only |
| `SpaceManager:SwitchPlanet` (Lua): NONE clears the planet; another list picks (again while equal to the current one), then x, y, turn from the script stream; returns the ImageDesc | AbstractEnvironment.hsSwitchPlanet, LuaAdaptersWP1 | oracle wp1-explore (seed 77: 280 x 280 at (11, 403)) |
| GET_HAZARD_FLASH: a callback returning four numbers sets the flash alpha and colour on every environment (CommandGui::RenderStatic asks twice while it flashes) | AbstractEnvironment.renderOverlay, LuaAdaptersWP1.flashOpacity | oracle wp1-mv m1/m2 (floor tint equal within 1) |
| GET_BEACON_HAZARD: a string is the map's hazard line, any non-nil result the hazard ring | JumpWindow, LuaAdaptersWP1 | oracle wp1-mv m5/m6 |
| removeHazards keeps planet and fleet, sets the backdrop to bg_dullstars (SetPulsarLevel), draws six values and swaps in a PLANET_POPULATED planet at (600, 247) without ASB or fleet | HsEventEffects, AbstractEnvironment.hsRemoveHazardsFrom | oracle wp1-remove / wp1-control |

`<changeBackground>` is the events workstream's (`AbstractEnvironment.hsChangeBackground`).

Not implemented: the HS command console (pending decision U4; the console hotkey is listed but does nothing), backgroundObject `<timerAction>`, `transform`, random animations and the FRONT layer, ShieldDroneManager definitions (MV uses none of the tags; the tooltip prints HS's defaults), customTextStyle and `<choiceColors>` (both unused by MV). The `Hyperspace SWIG Lua loaded` line is not shown on screen (HS prints it before the resources load; the oracle never shows it).
