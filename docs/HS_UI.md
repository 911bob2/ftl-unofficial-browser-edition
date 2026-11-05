# Hyperspace UI, text, tabs, console (WP7 slice B)

Package `xyz.znix.xftl.hs.render` (`HsTabs`, `HsTextStyle`, `HsConsole`) and `hs.lua.LuaAdaptersWP7Ui`. Everything is
gated on `HS.active`; vanilla runs are unchanged except the leading-space fix below.

## customTabs (CustomTabbedWindow.cpp)
- `<customTabs><tab name windowPath hasUndo/>` adds tabs to the ship window (`HsTabs`, `ShipWindow`). Tab buttons draw
  over the custom window art, the ship window sits 10 px further left (x 332) for 1-3 custom tabs, the Equipment tab
  uses `equipment_main_2`.
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
- The ship window sits 10 px left of 1.6.14's under Hyperspace with or without custom tabs (332 against 342: oracle
  sw2-ship m0/q1, sw2-switch s-h0, sw4-up24 u0).

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

## Shield squares past four layers (vanilla and HS)
- The status box has four squares; ShipStatus::RenderShields draws layer `i` from its charged/uncharged image row without
  a bound, so layers 5-8 (16 bars, Multiverse's Sylvan Transport) draw over squares 1-4 again with the next row's image
  (charged: uncharged image, uncharged: hacked image, hacked uncharged: hacked-charged image, hacked charged from the
  sixth: the super-shield bar segments). Hyperspace does not hook this. Both HUDs draw it through `ShieldSquares`
  (desktop and touch); before, the port drew squares 5-8 to the right of the box (over the fuel counter on touch).
  Evidence: oracle session `.build/hs/reports/hs-hud-android/sessions/sh16h.json` (the player's shields raised to 16 by
  Lua; the Sylvan switch itself hangs the oracle). The enemy's circles are Hyperspace's ten (CustomSuperShield.cpp).

## Left
Not reachable or not in HS: rapidxml's memory_pool allocators (`%ignore memory_pool` in rapidxml.i), Animation Save/LoadState (need an FTL file
descriptor; `FileHelper` is commented out in hyperspace.i), the Lua ErrorMessage dialog (nil in HS),
GL_OrthoProjection's return, primitive lineWidth and timer defaults (uninitialised memory). The upgrade box's price
past the blueprint's list (HS reads past the vector: "--" or 0, uninitialised memory; ours "--"). A looping
animation's frame on the tick it wraps (one tick).
