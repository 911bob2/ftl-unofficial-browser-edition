# Hyperspace UI, text, tabs, console (WP7 slice B)

Package `xyz.znix.xftl.hs.render` (`HsTabs`, `HsTextStyle`, `HsConsole`) and `hs.lua.LuaAdaptersWP7Ui`. Everything is
gated on `HS.active`; vanilla runs are unchanged except the leading-space fix below.

## customTabs (CustomTabbedWindow.cpp)
- `<customTabs><tab name windowPath hasUndo/>` adds tabs to the ship window (`HsTabs`, `ShipWindow`). Tab buttons draw
  over the custom window art (the window sits where the next point says, custom tabs or not), the Equipment tab uses
  `equipment_main_2`.
- Tab events (`TabbedWindow` events): CONFIRM carries the tab being left, the undo click fires UNDO only, RE follows
  the new tab. Verified against oracle frames and event rows (sessions `wp7-ui-tabs1..4`).

## customTextStyle (CustomTextStyle.cpp)
- `[style[color:RRGGBB(AA)]]text[[/style]]` in any string, at least 31 characters. `SILFontLoader` measures the text
  without markup, wraps it with the markup re-applied per line and draws each run in its colour. Verified: oracle frame
  `wp7-ui-style` (colours, wrapping, translucent run).

## Leading spaces (vanilla and HS)
FTL keeps leading and trailing spaces of text-library entries and event texts; the port trimmed them. `Translator`
keeps the text as the file has it; `EventManager.keepSpaces` keeps a run of spaces at either end but drops indentation
that sits on its own line. `SILFontLoader.wrapPlain` indents the first line by a paragraph's leading spaces.
Verified in the HS oracle (`style1`, "2.   two leading spaces"). A native 1.6.14 row needs a fixture with such a
text (none of the stock events has one): Left.

## Console (CommandConsole.cpp)
One debug console for vanilla and mods since round 17: [CONSOLE.md](CONSOLE.md) (commands, keys, the gating rule).
`HsConsole` keeps HS's commands 1:1 (HULL, GOD, SYS, REMOVESYS, SQUISHY, LOADEVENT, STORE and so on); their effects match
the oracle sessions `wp7-ui-console2`. HS's input box (its frames in those sessions) gave way to the port's console window.
SWITCH / SWITCHALL (`HsSwitchShip`, `InGameState.hsRebuildPlayerShip`, WorldManager::SwitchShip / SwitchShipTransfer):
SWITCH builds the player ship again from the blueprint (its crew, weapons, systems; scrap and fuel 0 unless the HS
definition sets them; the blueprint's missiles, drone parts and reactor; cargo empty; a nameless blueprint reads
"should not be seen"); SWITCHALL keeps the crew members, weapons, drones, augments, cargo, resources, system levels,
reactor, hull percentage and name. Rows = oracle `sw2-switch`, `sw2-swone`, `sw2-swall` (round 14).

## Crew list and upgrade screen (round 14)
- CustomCommandGui: eight boxes a page; while the player has MIND_ORDER (MV: a hidden augment of every ship), enemy
  crew under the player's mind control follow the player's crew, nine on one page when the ninth is one of them, red
  boxes. Right-drag reorder: CrewMemberFactory::MoveCrewMemberToSpot (CustomCrewManifest.cpp) on the box list, kept in
  `PlayerShipUI.hsCrewOrder` (the list is rebuilt every frame); the dragged box follows the mouse without its skills
  panel. The save writes the player ship's crew in that order, so it survives Save and Quit or Main Menu, then Continue,
  as native's save follows the factory order (`PlayerShipUI.hsSaveCrewOrder`; oracle `ro-main`, `ro-sq`, `ro-sq2`).
  Touch: a 0.5 s hold on a box, then a slide ([ANDROID.md](ANDROID.md#touch-controls)). Frames = oracle `sw2-crew`, `sw2-mind`.
- Upgrades: a system over eight bars shows two columns of eight (10 px left / 11 px right, the column numbers above
  once the left column is not the first); the levels box shows eight levels scrolled to the reached one. InfoBox takes
  the ship system's maximum (SetSystem 0x563640), not the blueprint's (vanilla too). Frames = oracle `sw2-switch` s-h0.

## Lua UI classes
Singletons the engine has no counterpart for (Mouse, Gui, CombatControl, WeaponControl, DroneControl, Tutorial,
TabbedWindow, UpgradeButton, FTLButton) keep their native fields in a hidden `LuaStruct` (shadow) with the values the
oracle read on a fresh game; reads and writes of other owners go to the previously registered adapter. Read-only
members throw "This variable is immutable". Also: GenericButton/Button/TextButton/FTLButton members and methods,
`FocusWindow`, `WindowFrame`, `TimerHelper`, `Description` (cost, rarity, bp, tip, tooltip, locked), `Hyperspace.version`,
`BlueprintManager:GetWeaponBlueprint` of an unknown name (default blueprint, not nil).
State-only members (no visible effect in HS 1.23.2 either): bHideUI, bBlockClose, bTutorialMode, bWindowLock.
The Mouse shadow lives per frame as MouseControl does (sixth pass below).

## Round 14 third pass
- CrewMember render family from a script's render callback draws into that frame: OnRender(false) outline and body,
  OnRender(true) the outline only while the crew member is selected or hovered (nothing otherwise), OnRenderHealth the
  health pass, OnRenderPath the green destination box while a player crew member walks (frames = oracle sw3-crewr,
  sw3-crewr2; walking time makes the box drift a few pixels).
- TutorialArrow(Pointf, rotation): arrow/arrow2 164x38, white colours; OnRender sets arrow2's alpha from the
  tutorial tracker (0 in a normal run). Rows and frame = oracle sw3-arrow.
- Console AUG: below three augments only, an owned one again, never a box; EVENT: ClearLocation, a new scene,
  CreateLocation (the start beacon's station goes). Rows sw3-aug, frame sw2-ebtn e0.
- Weapon and drone boxes show their hotkey's key (drones 5-7 by default). Settings::GetDlcEnabled is the run's
  Advanced Edition (the hangar's choice outside a run) for tooltips and `SettingValues.bDlcEnabled`.

## Round 14 fourth pass
- Wreck (vanilla and HS; ShipManager::OnRender 0x4a3c00 draws only the explosion for a destroyed ship): weapons ride
  their layout gibs under the gib image (ExplosionAnimation::OnRender 0x488e80), no system, weapon or artillery is
  drawn in place. ShipManager::bInvincible (+0x868; BossShip::StartStage sets stage != 3, HS `<invincible/>` events and
  Lua `bInvincible` too) makes the wreck jump out: a 4 s tracker, jumpLeave at 0.75, the last gib left out from then
  on and Ship::OnRenderJump((p - 0.75) x 4) with the whole hull; BOSS_ESCAPED waits for the 4 s. artilleryGibMountFix's
  boss variant draws the last gib's artillery in that jump. Frames sw4-boss against oracle wp7-render8 (structural:
  gib drift is random).
- Level-up (CrewMember::IncreaseSkill 0x479b90 / OnRenderHealth): reaching half or full experience starts `levelUp`
  (2 s, oracle levelUp.time) and sets `lastLevelUp`; the skill's s_<skill>_overlay icon in its new colour grows to
  p x 192 px fading out (1 - 2.4p) and sits 16/30 px above the crew fading in (2.4p). Lua levelUp/lastLevelUp are the
  engine's state; RenderSkillUpAnimation(Point) draws the crew box's skill-up indicator there. Rows and frames
  sw4-skill (the icon's holes show what is behind it: the crew sprite differs between the runs).
- `vCrewList` lists anti-personnel and repair drones from the moment they are added (ShipManager::CreateCrewDrone
  0x4a7cc0): until deployed a stand-in body reads bDead, IsDead, iRoomId -1, x,y 0,0, not Functional (oracle
  sw4-cdrone d0-c3); deployed, the drone's own body (d1-c3 = oracle). The stand-in is not the deployed body's object.
- Arrival: the beacon's event (and HS's revisit path) loads at the location switch, its rewards and script callbacks
  with it; the box stays hidden, the game paused, until the fly-in is over (oracle sw4-arrive: PRE_CREATE_CHOICEBOX on
  the switch frame). Hyperspace.FPS RunningTime stops during that hold (oracle sw2-jump).
- ShipSystem.maxLevel is writable from Lua. Upgrade bars past 16: the two shown columns carry their top levels above
  them (16, 24 for a level 17-20 system of 22/24; oracle sw4-up24 u0 with an authored 24-level Torus), 70 px above the
  bottom bar's top. A level past the blueprint's price list cannot be bought (HS reads past the vector: "--" or 0).
- Ship and store window x: CommandGui::OnLanguageChange (CommandGui's constructor and a language change) measures the
  system levels box of system ids 0-15 (InfoBox::CalcWidthForSystem: the widest GetLevelDescription of the levels below
  maxPower, one more for sensors and doors, in font 10, plus 143 less details_base_A, in details_base_B pieces, at least
  one, plus A and C) and puts shipScreens at 682 less the widest (at most 340, at least 150), storeScreens likewise (at
  least 283). CommandGui's constructor runs it, so its (340, 85) never shows: English vanilla and plain Hyperspace measure
  347 (sensors' "See enemy weapon charge", 178 px) and sit at 335; Multiverse's mind control level 2 text (189 px)
  makes 357, so both windows sit 10 px further left at 325; German vanilla at 305. The window art's edge is x + 7.
  Oracle: hs-offset sessions wp-hs / wp-mv (frames), wp-lua-hs / wp-lua-mv (the level texts measured in font 10 by
  HS's Lua, equal to ours), wp-lang-hs / wp-lang-mv / wp-lang-de (a language change moves it again); also sw2-ship
  m0/q1, sw4-up24 u0, bt-store-over. `ShipWindow.shipScreensX` / `storeScreensX`, checked by tests/window-places.mjs.
  The same width goes to the upgrades window's InfoBox: a hovered system's boxes are 10 px wider under Multiverse
  (oracle wp-hs2 / wp-mv2 13-sensors-info; ours 333 for 347).
- The equipment tab's item InfoBox (and the store's sell tab, the same Equipment): Equipment::SetPosition puts it at the
  window's x + 600, y - 25 (CustomEquipment::SetPosition keeps it), 25 above the upgrades tab's levels box, and an item
  box is 323 wide (oracle overcap oc-hs 04, info-button ib-mv-eq: top 134). `ShipWindow.drawEquipmentInfo`.

## Round 14 fifth pass
- System icons (vanilla and HS): ShipManager::OnRender (0x4a3c00) draws ShipSystem::OnRender after Ship::OnRenderWalls,
  so the icon lies over breaches, sparks, fires and HS's layer 0-2 room animations; teleporter pads and the clone tub
  stay with the floor stage (oracle sw5-pow p05: the medbay cross over the praetor's layer-1 spores).
- Resource messages (vanilla and HS): DamageMessage (0x5bc970) images, not text: numbers/Text_plus or Text_minus at
  (-50,-46), Text_<d>_L from (-32,-46) 16 px apart, from ShipStatus::RenderResources' points (180,104), (247,104),
  (314,104), (465,70), random x +-10, 10 px down over 1 s (oracle sw5-pow r0/r1). They are made in the render, so a
  stepped diagnostic must make them every frame (BrowserStatCrewDiagnostics does).
- Crew power and stat boost animations are drawn 3 px under x, y, like the crew sprites (oracle sw5-pow p05/p15: the
  same pheromones_f frame, temporaryPowerDuration equal within a frame).
- CrewControl firstMouse/currentMouse/worldFirstMouse/worldCurrentMouse: (-1,-1) and INT_MIN+1 at the start; a left
  press on the game (no window, no target being picked, not on a top-bar button) sets first and current; every frame
  without a focus window sets current; ship points are the mouse less the player ship's position. Rows sw5-cc = oracle.
- DroneControl:SelectArmament/DeselectArmament act on the drone each box got in the last UI loop: in the frame of a
  Lua AddDrone nothing happens, a frame later it powers (rows sw5-dsel = oracle).
- The arrival box opened at a location switch waits hidden until the fly-in ends, whether or not the player paused;
  the run's first box and a loaded save's are shown at once. Debug snapshots report the box once it is on screen.
- Scrolled info box prices at the vanilla x (oracle sw4-up24 u-h0); a level past the price list shows the
  no-purchase text.
- Oracle note: the oracle's `lua` steps type into the console, which holds the world for a moment while ON_TICK
  goes on; scenario clocks that must follow game time count CREW_LOOP (or another world event) instead.

## Round 14 sixth pass
- MouseControl each frame (CApp::OnLoop 0x402350: Reset 0x4e73c0, then OnLoop 0x4e5bf0): the tooltip, title, flags,
  overrideTooltipWidth -1 and staticTooltip (-1,-1) start empty; tooltipTimer grows by the game step while the mouse
  rests and restarts when it moved (SetPosition 0x4e5bc0 marks bMoving). SetTooltip / LoadTooltip only replace with a
  text, and force the tooltip when it is last frame's (lastTooltipText); SetTooltipTitle only sets an empty title;
  InstantTooltip sets bForceTooltip.
- A script's tooltip of the frame is drawn as MouseControl::OnRender draws it (AdditionalWeaponSlots.cpp rewrite):
  after 0.15 s at rest or forced, 22 px under the mouse or at the queued static point, wrapped at
  overrideTooltipWidth, with its title; it then becomes lastTooltipText and takes the place of the engine's tooltip.
  bHideMouse draws neither pointer nor tooltip (and keeps lastTooltipText). RenderTooltip(Point, static) draws at once.
  Reading Mouse.tooltip gives the engine's tooltip text when no script set one. MOUSE_CONTROL also fires outside the
  game. Rows and frames sw5-tip a-e = oracle, MV's addon icon tooltip (t-addon).
- The pointer follows a script's fields of the frame: bHideMouse (none), iMindControlling / iTeleporting / iHacking,
  aiming_required, bSellingStuff, valid, and its own validPointer / invalidPointer textures in place of the plain
  pointers (MV's her cursor); the shell hides the pointer for the key "none" (rows and frames sw5-cur: hidden,
  teleport, a script's texture, the original pointer back).
- Crew names: a drone's body is named by its CrewDrone constructor (anti_person_drone, crew_repair_drone,
  boarder_drone text, or HS's `<drones>` tooltipName); SetName with force sets both names, without force the short
  one is ShortenName's, for living crew too; CrewBlueprint:GetNameShort is the short name (rows sw5-dname, sw5-tip ln).
- MouseControl:MeasureTooltip at width 60 = oracle (57|104) on the merged tree (slice D's wrapping).
- Point.DistancePath (static, 1.6.9 0x585f20): each leg's float length cut to whole pixels, summed; one point 0
  (rows sw5-misc = oracle).
- A finished levelUp tracker is done and stopped with current_time 0 (AnimationTracker::Stop(false) 0x5bbef0; sw4-skill).
- SettingValues:GetHotkeyName of a printable key is its character, upper case (the backslash key "\", not the
  engine's "# or \" label; swm hotkey rows).

## Event buttons and the touch HUD (hs-hud round)
- `<eventButton>` (EventButtons.cpp) in the browser = native: Multiverse's storage crate after the ship button (location 2),
  jukebox (338,46), toggle (105,110) and More Info at the same pixels as oracle frame `arty-ceala/02-60s`; the speed control
  (MV core.lua in FTL_BUTTON) too.
- Save and load: the index is native's (EventButtonManager::Save/Load): definitions are numbered as the event files are
  parsed, all at load in one order, so the same mods give the same numbers in every session (checked on the emulator:
  0, 9, 10 before and after an app restart). Multiverse has four `UI_STORAGE_CHECK_BUTTON`: three at location 2 with
  `top_storage` (START_BEACON_REAL, the first-run tutorial offer, the tutorial), one at 0 with `top_storage_on`, which draws
  nothing (`top_storage_on_on.png` does not exist), in SPAWN_STORAGE, which no event or script loads. The save keeps name,
  location and image too; loading takes the definition at the index when they match, else the first matching all three,
  else the index if its name matches, else the name.
- 2.0.1 saved only index and name and loaded by the name, which gave the last definition parsed, SPAWN_STORAGE's, so the
  crate vanished after a Continue and a run saved again kept that definition (2.0.2 too, with its location and image).
  A loaded button that draws nothing while another definition of its name does is that substitution, never a native run's:
  it gets the first definition of the name that draws (START_BEACON_REAL's crate). Native never substitutes (it loads by
  index), so it shows the crate from the start beacon on, active out of danger, after Save and Continue too (oracle
  sessions `hudfix-native-a`/`-b`, `.build/hs/reports/hud-fix/oracle/`).
- The touch layout's place for these and other desktop HUD elements: [ANDROID.md](ANDROID.md#touch-controls).

## More Info button (MoreInfoButton.cpp; Multiverse's magnifier)
- The button (`statusUI/moreInfoButtonOn|Off`, 1207,656) and the `info` hotkey (right Alt, Controls page 1) toggle
  CustomOptionsManager::altMode with the `moreInfoOn`/`moreInfoOff` sound; its hover tooltip is `tooltip_more_info`.
  Drawn in ShipStatus::OnRender, so it stays lit over an open window's dimming and can be clicked there (the hover
  is blocked only by the pause menu, options and game over).
  On by default and never saved: every start of the game has it on, a save and load keeps the running value
  (`HsOverlay.altMode`, reset in `HS.init`).
- The mode reaches the options only at CommandGui::OnLoop (`HsMoreInfo.onLoop`, every flight frame): until the first
  frame of a run they hold hyperspace.xml's values, so a fresh start's hangar shows no reactor box under plain
  Hyperspace; after a run the hangar shows the mode the run left. The button's image follows the applied value.
- On: showAllyPowers / showEnemyPowers (the race's powers as `*` lines in the crew tooltip), advancedCrewTooltipRounding
  2 when the default is 0 or 1, showReactor (hangar), showAllConnections (map, unless splitAllNodeView),
  showNumericalWeaponCooldown, redesignedWeaponTooltips / DroneTooltips / AugmentTooltips; alternateOxygenRendering is
  forced off. Off: each back to hyperspace.xml. The `HS-<version>` label (top right) reads altMode itself.
  Event tooltips do not depend on it: EventTooltip.cpp tests its own `g_eventTooltips`, always true.
- Under Multiverse (its hyperspace.xml turns the weapon, drone and augment tooltips, the cooldown numbers and the
  reactor box on) the button changes the crew powers, the map's connections and the label only.
- Touch: the button is a TouchExtras piece in the dock above Pause (a tap toggles, a held finger shows its tooltip). A
  finger held still for 0.5 s on a crew member on either ship shows the crew tooltip until it lifts (`TouchHold`; the
  lift still does what a tap does); the weapon slots' info drawers carry the slot tooltips; the cooldown numbers are
  drawn over each open weapon slot (`HsCooldownNumbers.drawTouch`); InfoBoxes (equipment, store, events) show under a
  held finger as on the desktop under the mouse.
- Checks: hs-crewui `tooltip` part (`BrowserHsMoreInfoDiagnostics`), Android `MoreInfoTest` and
  `tools/android-touch/moreinfo.mjs`, oracle sessions `info-button.json` (`ib-mv`, `ib-hs`; `ib-mv-eq` visits the ship screen and the map
  without a boarder, as intruders keep the ship screen shut).

## Shield squares past four layers (vanilla and HS)
- The status box has four squares; ShipStatus::RenderShields draws layer `i` from its charged/uncharged image row without
  a bound, so layers 5-8 (16 bars, Multiverse's Sylvan Transport) draw over squares 1-4 again with the next row's image
  (charged: uncharged image, uncharged: hacked image, hacked uncharged: hacked-charged image, hacked charged from the
  sixth: the super-shield bar segments). Hyperspace does not hook this. Both HUDs draw it through `ShieldSquares`
  (desktop and touch); before, the port drew squares 5-8 to the right of the box (over the fuel counter on touch).
  Evidence: oracle session `.build/hs/reports/hs-hud-android/sessions/sh16h.json` (the player's shields raised to 16 by
  Lua; the Sylvan switch itself hangs the oracle). The enemy's circles are Hyperspace's ten (CustomSuperShield.cpp).

## In-flight HUD with the mouse (parity sweep U1; vanilla and HS)
- Triggered-event boxes and warnings (TriggeredEvents.cpp ShipStatus::OnRender hook) are drawn with the status, so they
  stay lit over a window's dimming like the top bar and the More Info button (oracle `ps-u1-trig` t-ship, t-map).
- A right press that drops an armed weapon, teleporter, mind control, hacking, temporal or overclocker target goes no
  further than the weapon and drone slots (CommandGui::RButtonDown, CombatControl::MouseRClick and the two HS hooks):
  no system loses power and no crew is ordered by it.
- The subsystem holder and boxes are drawn with the system boxes, before the weapon and drone boxes (CommandGui::
  RenderStatic), so a long system row's drone box covers the subsystem icons.
- A drone slot's tooltip is the original's (DroneBox, 1.6.14 0x56d960): description, state, hotkey, rearrange line.
  Slots past three name HS's `drone4..6` keys (oracle `ps-u1-d5` d5-h3: "Hotkey: 8").
- Where a subsystem box lies under a drone box, both take the hover (CommandGui::MouseMove gives the point to
  CombatControl, then SystemControl) and the subsystem's tooltip is shown (oracle `ps-u1-d5` d5-h4: the fifth drone
  box glows, the tooltip is the sensors').
- The cursor turns to the hover arrow over system boxes and filled weapon and drone slots like the original's.
- A system box takes the hover (glow, cursor, click) in its 20 px icon square (SystemBox::MouseMove) and shows its
  tooltip there and over the 14 px column of its bars (u1v-h, u1v-i). Open: over a manning or fire icon above the bars
  the original's column reaches higher. The SUBSYSTEMS label shows its "subsystems" tooltip (SystemControl::MouseMove,
  u1v-j).
- The hull warning is one 6 s message (ShipStatus::OnInit): a fall past 75 %, 50 % or 25 % sets its text and starts it,
  a second fall while it runs changes only the text; below 25 % it loops until a repair lifts the hull above 25 %.
- The system tooltip ends parts and bullets with the original's trailing space (0x4df760), which widens the box where
  such a line is the widest. Frame pairs and evidence: `.build/hs/reports/ps-u1.md`.

## Left
Not reachable or not in HS: rapidxml's memory_pool allocators (`%ignore memory_pool` in rapidxml.i), Animation Save/LoadState (need an FTL file
descriptor; `FileHelper` is commented out in hyperspace.i), the Lua ErrorMessage dialog (nil in HS),
GL_OrthoProjection's return, primitive lineWidth and timer defaults (uninitialised memory). The upgrade box's price
past the blueprint's list (HS reads past the vector: "--" or 0, uninitialised memory; ours "--"). A looping
animation's frame on the tick it wraps (one tick).
