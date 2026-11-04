# Hyperspace Lua runtime

Mods built for FTL Hyperspace (Multiverse and its add-ons) run Lua scripts listed in `data/hyperspace.xml`
`<scripts>`. The browser engine runs them unchanged on Lua 5.3.6 compiled to WebAssembly, with the script API of FTL-Hyperspace
(GPL-3.0-or-later) commit 1b05a07, ported from that commit, not the CC BY-SA 1.23.2 tag (its `lua/` and `.i` files are
identical), and SWIG 4.0.1 binding semantics. Nothing of this is loaded in vanilla play. ftlman's merge-time patch
scripts (`*.append.lua`) run in a separate module, `web/vendor/ftlman-lua.wasm` (docs/MODS.md, Lua patch scripts).

## Pieces

| Part | Where | What |
|---|---|---|
| Lua 5.3.6 | `third_party/lua-5.3.6/` | Unmodified sources (MIT, `third_party/notices/Lua-LICENSE.txt`) |
| C bridge | `hs-lua/hslua.c`, `linit.c`, `hsconf.h` | Userdata, metatables, `script.*`, HS's `print`/`log`/`math.random`, caps, profiling |
| Generated tables | `hs-lua/gen/` (C), `engine/.../hs/lua/LuaApi.kt` (Kotlin ids) | From HS's SWIG wrappers, see below |
| Wasm | `web/vendor/hs-lua.wasm` | Built by `sh hs-lua/build.sh` (pinned emsdk 3.1.74 image; byte-identical rebuilds) |
| Loader | `web/hs-lua.mjs` | Instantiates the wasm, binds the engine's `hsLua*` exports, sets `window.ftlHsLua` |
| Engine side | `engine/src/main/java/xyz/znix/xftl/hs/lua/` | Handles, adapters, hooks, diagnostics probe |

Loading: `web/game.mjs` imports `hs-lua.mjs` only when `window.ftlModSession.scripts` is non-empty, before
`engine.start`. `BrowserEngine.start` then calls `HsLua.startForSession`, which runs the scripts in `<scripts>` order
(`luaL_loadbufferx(code, filename, "t")`, so error chunk names read `[string "data/..."]` as in HS), then the
`script.on_load` callbacks, and sets `HS.luaActive`. Every engine hook is `if (HS.luaActive) LuaEvents.x(...)`.
The CSP allows `'wasm-unsafe-eval'` (WebAssembly compilation only; no JavaScript `eval`).

## Generating the API tables

```sh
git -C <FTL-Hyperspace clone> archive 1b05a07 lua src libzhlgen third_party/rapidxml | tar -x -C .build/hs-1b05a07
sh tools/hs-bindgen/swig.sh .build/hs-1b05a07 .build/hsgen        # SWIG 4.0.1 in hs-lua-swig:focal, no network
node tools/hs-bindgen/bindgen.mjs .build/hsgen/swig .build/hs-1b05a07 .   # writes hs-lua/gen/* and LuaApi.kt
sh hs-lua/build.sh                                                   # rebuilds web/vendor/hs-lua.wasm
```

The generator parses SWIG's own Lua wrappers (not the `.i` files), so the bridge has exactly SWIG's members: per
class `.get/.set/.fn` tables and bases, static namespaces, constants (integers) and enums, argument checks
(`"Error in %s (arg %d), expected '%s' got '%s'"`), arity errors, overload dispatch order, the unsigned checks,
immutable attributes, `__eq` on the pointer, `__tostring`, and each module's `%luacode` (run with
`luaL_loadstring`, as SWIG does). 332 types, 1732 attributes, 2252 methods, 82 internal-event rows.

## Bridge

- An engine object crosses as an int32 handle (`gen << 24 | slot`, `Handles`, 16.7 M slots), registered on first
  exposure; the userdata holds the handle and its static Lua type. An object pushed again while an earlier userdata
  of it lives gets one cached userdata (weak tables by slot, and by (handle, type) for other static types); objects
  pushed once (value copies, constructor results) stay out of the cache. SWIG makes a new userdata per push, so
  scripts cannot depend on identity; `__eq` compares handles. The runtime counts userdata per slot in a C array and
  the last `__gc` releases the handle; a non-class pointer (no metatable, as in SWIG) releases it through a hidden
  user value with a finalizer.
- Lua's collector paces itself on Lua's heap, but every new handle also holds engine memory, so `Handles.expose`
  adds collector work for it (128 bytes per new handle, a `LUA_GCSTEP` every 256 handles). Scripts that make
  thousands of short-lived objects per frame (MV Fusion: `Hyperspace.Point(x, y)` per fire cell) then keep a few
  thousand live handles instead of growing past a million. When the slots run out, a full collection runs first;
  exposing beyond that raises a Lua error.
- A run that ends (new run, load, main menu) gets a full collection: a handle keeps its object alive, so a finished
  game stays whole until Lua finalizes the last userdata naming any part of it, and Lua's small heap gave its collector
  no reason to hurry (about 1.6 MB per Continue on Android, up to 230 MB). `hsRuntimeProbe("reload-handles [n]")`
  fails when handles grow with reloads.
- Attribute reads/writes call `hsLuaGetn/Gets/Geto/Setn/Sets/Seto(handle, attrId)`; methods call
  `hsLuaCall(methodId, nArgs)`, which reads arguments from the Lua stack (`LuaArgs`) and pushes results (`LuaPush`).
  SWIG 4.0.1 pushes every C number as a Lua float (`lua_pushnumber`), so `tostring(ship.iShipId)` is "0.0"; enum and
  integer constants and HS's own event arguments (`lua_pushinteger`) are integers. Checked against the oracle
  (members, methods, `playerVariables`, constants, ON_KEY_DOWN's key). Setter type errors read like SWIG's
  (`Error in CFPS::speedLevel (arg 2), expected 'int' got 'string'`, no location), immutable members like HS's
  (`<chunk>:<line>: This variable is immutable`).
- Adapters: `LuaReg.num/bool/str/obj(attrId, get, set)` and `LuaReg.method(methodId, fn)`. A member without an
  adapter raises a Lua error and is recorded in `HS.capability` (`HS.unsupported("lua", kind, name)`).
- Member structs (`crew.health`, `ship.ship.hullIntegrity`) are views cached per (owner, attribute), so writes go
  to the owner as through SWIG's member pointers. Vectors are zero-based `ListView`s (`:size()`, `[i]`,
  `push_back`, ...).
- HS data types with no engine object (StatBoostDefinition, Rect, TextString, Button, Animation, ...) are
  `LuaStruct`s: generic accessors for all fields of the type and its bases, value-initialised like SWIG's `new T()`
  plus the C++ default member initialisers listed in `LuaStructs.defaults`.
- `ShipGraph.GetShipInfo(id)` is the graph of ship 0/1 (`LuaShipGraph`, rooms in the engine's Room frame, which is
  FTL's ShipGraph frame); GetRoomShape/GetRoomCenter/GetSelectedRoom/shipBox follow FTL 1.6.14's functions (empty
  graph for a missing enemy, `{-1,-1,0,0}` for a missing room). `Room.rect` and `shipBox` are copies (writes do
  not move rooms).
- Overloads: the generated dispatcher replays SWIG's (candidate order and checks); adapters register on the
  `__SWIG_n` ids, whose argument lists are SWIG's own (`tools/hs-bindgen/overloads.py <swig dir>` lists each one with its signature).
  Engine-side `SWIG_*Error:` messages get HS's location prefix (hyperspace.i's SWIG_exception).
- PrintHelper (on-screen `print`) renders in every state (MouseControl::OnRender) and advances 1/NumFrames per
  frame, so HS's start-up "Hyperspace SWIG Lua loaded" (printed while NumFrames is 0) goes after one frame.
- Dynamic types (SWIG DYNAMIC typemaps, `GetShipSystemType`) come from `LuaTypes.dynamic`.
- ShipSystem fields the engine keeps no state for live in `HsSystemState` (1.6.9 constructor values: fMaxDamage 150,
  bBoostable/bNeedsPower/iRequiredPower by system id, lock timer 5000 ms, flash tracker 0.75 s). Per-frame fields
  (bOccupied, bManned, computerLevel, ...) are recomputed each frame; a script's write holds until the system's next
  update, as ShipSystem::OnLoop clears them. Methods with native bodies (PartialRepair, AddLock, GetPowerCap,
  GetLevelDescription, ...) are in `LuaAdaptersWP5Systems`; the probe `tools/hs-oracle/tracer/data/hs_oracle/wp5-sys.lua`
  and `compare-wp5-sys.py` compare them with the oracle.
- Destroyed objects: HS hands Lua raw pointers (a stale read is undefined). Here a userdata keeps its object
  reachable and reads its last state; `Handles.invalidate(o)` makes it stale (reads fail with an error).

Other workstreams add members for their features through `LuaReg` in their own packages (thin adapters over their
models); `LuaAdaptersMore.statBoostDefs` and `LuaVariables.player/meta` are the hand-over points for stat boosts and
player/meta variables.

## Script API (LuaLibScript.cpp / InternalEvents.cpp / RenderEvents.cpp)

- `script.on_internal_event(id, fn[, priority])`: callbacks sorted stable by priority (higher first). Registration
  checks the parameter count and vararg flag with HS's messages. Chain events: a non-integer return is CONTINUE,
  any non-zero integer stops, PREEMPT (2) also skips the original; extra returns rewrite the arguments for later
  callbacks (non-nil only). Plain events stop at the first non-nil result. Errors are logged
  (`Failed to call the callback for InternalEvent %s!`) and the next callback runs.
- `script.on_render_event(id, before, after[, priority])`: `before` callbacks in order until HALT/PREEMPT, `after`
  callbacks in reverse from there; PREEMPT skips the original render.
- `script.on_init`, `script.on_load`, `script.on_game_event(name, onLoad, fn)`: stop at the first error, as HS.
- Removed globals as in HS: `collectgarbage dofile load loadfile newproxy rawequal rawget rawset`. Libraries:
  base, table, string, math, utf8, bit32 (LUA_COMPAT_5_2) and `os.clock`, `os.time` (no date table), `os.difftime`.
  HS 1.23.2's own `lua/linit.c` lists only the first six, but its Windows build does not compile that file: the original
  has every standard library, `os`, `io`, `debug`, `coroutine` and `package` with `require` too (oracle session
  `bt-mv-tutorial2`, `os.clock()` 36.9 s into the process). Multiverse's `tutorial.lua` reads `os.clock()` for its pulsing
  outlines, the only standard-library use in MV 5.5.1. The rest of `os`, `io`, `package` and `debug` (processes, files,
  loaders) and `coroutine` are not provided: no MV script uses them. `os.clock` counts seconds from the runtime's first
  start, as MSVC's `clock()` counts from the process start. `print` and `log` join arguments with four spaces;
  `print` also feeds `Hyperspace.PrintHelper` (on-screen text).
- `math.random`: FTL's `random32` with HS's formula; NativeRandom while generation callbacks run
  (`LuaEvents.generation`), BrowserRandom otherwise.
- Budgets: an instruction cap per callback (default 50M, `lua_sethook` count, restored for nested callbacks) and
  per script load (500M); the error names the mod file and the callback. `__gc` handlers run under the cap too, also
  outside a callback, where they share one 50M (hooks are off there in stock Lua, `hs-lua/hslua-lvm.c`). Profiling per callback (calls,
  instructions, max, errors, ms): `BrowserEngine.hsProfile(reset)` returns JSON.

## Engine hooks wired

| Event | Call site | HS hook |
|---|---|---|
| ON_TICK | `BrowserEngine.step`, after the update | CApp::OnLoop, after super |
| SHIP_LOOP | end of `Ship.update` (also for a dead ship) | ShipManager::OnLoop, after super |
| CREW_LOOP | `Ship.updateCrew`, after each `crew.update` | CrewMember::OnLoop, after super |
| JUMP_ARRIVE / JUMP_LEAVE | end of `Ship.resetAfterJump` / `Ship.browserJumpLeave` | ShipManager::JumpArrive / JumpLeave, after super |
| MAIN_MENU | end of `MainMenuState` init; once after the scripts load if the menu is open | MainMenu::Open, after super |
| ON_KEY_DOWN/UP | `BrowserEngine.key` (GLFW code -> SDL 1.2 keysym), pre-empts the engine | CApp::OnKeyDown/Up, before super |
| ON_MOUSE_MOVE, L/R/M button, SCROLL | `BrowserEngine.mouse`, pre-empts the engine | CApp::OnMouseMove / On?Button*, CEvent scroll |
| render MAIN_MENU | `MainMenuState.render` | MainMenu::OnRender |
| render LAYER_BACKGROUND | around the beacon background in `InGameState.render` | SpaceManager::OnRenderBackground |
| render MOUSE_CONTROL | around the tooltip in `InGameState.render` (PrintHelper text first) | MouseControl::OnRender |
| script.on_init | `MainGame.startConfiguredRun` (true), `MainGame.loadSavedGame` (false) | WorldManager::CreateNewGame / ScoreKeeper::LoadGame |
| CALCULATE_STAT_PRE/POST | `CrewExtend.calculateStat` (`LuaCrewEvents`) | CrewMember_Extend::CalculateStat |
| POWER_REQ, POWER_READY, PREPARE/ACTIVATE/CANCEL_POWER, POWER_(RESOURCE_)ON_UPDATE, POWER_(RESOURCE_)ENABLE_INIT | `CrewPowers.kt` | ActivatedPower(Resource) methods |
| CONSTRUCT_SHIP_SYSTEM | end of `AbstractSystem.initialise` (`HsSystemState.constructed`), as `ShipSystem` | ShipSystem::constructor (System_Extend.cpp) |
| DAMAGE_SYSTEM | `Ship.damage`, where the system damage is applied (`HsWeaponsLua.damageSystem`) | ShipManager::DamageSystem (-100) |
| (WP2 classes: CustomShipSelect, ShipButtonDefinition/List, CustomShipUnlocks, CustomAchievementTracker, CustomShipDefinition containers, CrewPlacementDefinition, Equipment adders, BlueprintManager.GetShipBlueprint, ShipBlueprint, SettingValues) | `LuaAdaptersWP2` | not events; left unregistered: GetHotkey, ShipButton members, CustomShipGenerator.CreateShip, hover selection getters (-1) |
| HAS_EQUIPMENT (-1000), HAS_AUGMENTATION, GET_AUGMENTATION_VALUE | `LuaAdaptersWP1` chains on `HsReq.hasEquipment`, `HsAugments.count` / `value` | integer results only for the first two (`lua_isinteger`), any number for the value |
| DANGEROUS_ENVIRONMENT | `InGameState.isInDanger` and `space:DangerousEnvironment()` (`LuaAdaptersWP1.dangerousEnvironment`) | the first callback returning a value decides, false included |
| GET_BEACON_HAZARD | `JumpWindow` (map marker for every beacon, hazard line of the tooltip) | a string is the line, any other non-nil value only the marker |
| GET_HAZARD_FLASH | `AbstractEnvironment.renderOverlay` (`LuaAdaptersWP1.flashOpacity`) | four numbers: alpha and flare colour; asked twice per flashing frame |

Weapon, projectile, damage, choice-box, system and ship events are fired by the feature packages (`hs.weapons`,
`hs.events`, `hs.systems`, `hs.ship`) through the same `LuaJs.fire/fireChain` calls.

`Hyperspace.FPS` (`LuaFps`) is FTL's CFPS: with scripts running, a real frame (dt > 0) goes through CFPS::OnLoop's
formula, `SpeedFactor = dt * (16 + 7 * speedLevel)` clamped to [0, 0.5], and the game advances SpeedFactor / 16
seconds (vanilla keeps `min(dt, 1/32)`); `speedEnabled` false makes speedLevel count as 0 (HS's CFPS::OnLoop hook).
ON_TICK fires once per real frame, not after each input event's zero step.

Not wired yet (registrations are accepted and listed by `hsProfile`): see `.build/hs/reports/lua.md` (Round 2) for
the list with owners. POWER_TOOLTIP fires from the crew power button tooltip (`HsCrewUi.powerTooltip`);
CONSTRUCT_CREWMEMBER fires after a crew member or boarding-drone pawn is built, CREW_CLONE when the clone bay
finishes (before the skill loss), RE_CREW_MEMBER_HEALTH around the health bar (`LuaCrewEvents`).

## Drawing

`Graphics.CSurface`, `Graphics.freetype` and `Hyperspace.Resources` draw through the engine's `Graphics` during
render callbacks (`LuaGraphics`). Consecutive `GL_DrawRect`s share one quad batch, flushed before any other Lua draw
and when the event's callbacks return (FTL issues one draw call per rectangle; the order is unchanged). Matrix
pushes left open by a script are popped after the render event. Fonts use the engine's `NativeFonts` ids.

## Writable members

`LuaAdaptersWritable`, `LuaAdaptersWrites`: a member hyperspace.i leaves writable goes through its setter into the object the
read comes from, so a write to a definition (blueprint, crew power, ship definition) reaches everything built from it, as natively.
A blueprint write reaches ships built later only: `Ship` keeps what it read when built, and `myBlueprint` is the ship's own copy.
A member natively recomputed each frame holds until the frame ends (`LuaStored.frame`). `LuaWriteThrough` and `LuaStruct.onWrite`
carry a field write of a value member (a point, a colour, a sector name) on to the engine. A member with no adapter still reads back
what was written (`LuaWritable`) with no other effect; the hs-oracle sessions `hw-*` (`sessions/gen-hs-writes.py`) compare what
writes do, `setter-cov` lists the members without an adapter.

Trackers and animations the game runs: `Shields.shieldsDown`, `shieldsUp`, `superShieldUp` and `Door.forcedOpen`, `gotHit` are
engine state (`Shields.ShieldTracker`, game seconds; paused means stopped) copied both ways with the Lua members each frame, so a
write changes the picture and an engine start reads back. A stopped `shieldsUp` hides the bubble; `forcedOpen` draws the door's third
frame for 7 s and hits do nothing meanwhile; `gotHit` has no picture. `shieldsDownPoint` and `superUpLoc` are in the member's
coordinates (around `Shields.center`). `ArmamentBox` location, xOffset, nameOffset, nameWidth, `ShipGraph.shipBox`, `Ship.engineAnim`
and `Door.doorAnim` move or change what the engine draws; `doorAnimLarge` holds with no picture. `WeaponAnimation.fireLocation`
moves where a shot starts; mount position, mirror, rotate, slide, renderPoint, fireMountVector, `currentFiringAngle`, `localPosition`,
`isArtillery` do not (hw-e-*). Drone images are the engine's pictures. `WeaponSystem:AddWeapon` follows the native list: a slot in the
list replaces (old cost returned, power released), any other slot appends while slots remain and returns 0; nothing goes to cargo.
`boostAnim`, `hackSparks`, `Fire` animations revert the next frame, `explosionAnim` becomes a random pick the next frame.
`SystemBox.location` moves the box, its clicks and its wire, `pSystem` makes it show and control that system.
`Ship.lockdowns[i]` is a copy (writes stay on that copy); `GetShards` hands out the shards themselves. `HasEquipment` counts a weapon
`AddWeapon` had no slot for. Writing `cooldown.first` up to full doesn't charge a weapon: the bar is full, it doesn't fire.
`ProjectileFactory.cooldown` reads scaled by `cooldownModifier` (manning, or 1.5 - 0.25 x power on artillery, whose charge a write moves;
`WEAPON_COOLDOWN_MOD` fires for artillery too). `Ship.shipImage` moves the mounts ahead of their rotation, not the hull. `ForceDecreasePower`
takes bars (shields a layer) without changing the demand, so the system climbs back next frame while the reactor allows. A written
`currentFiringAngle` holds until the weapon fires. `ShipRepairDrone.repairBeams` holds the waves while the drone repairs: each climbs 1.2/s, a
new one starts when the last passes 0.25. `crewList[i]` is a copy; `roomDefs[i]` is not. A `ShipBlueprint` file name that isn't a file is only a
string until a ship is built from it. `weaponVisual.anim.currentFrame` follows the charge, also on a written animation.
`.build/hs-writes/members.tsv` holds each member's class; the open differences are in `.build/hs/reports/hs-writes.md`.

## Tests

- `node tests/hs-lua/semantics.mjs`: callback order, HALT/PREEMPT, rewriting, plain results, errors, registration
  checks, render pre/post, instruction cap, on_load (same script as the browser probe).
- `node tests/hs-lua/bench.mjs 2000`: bridge cost with a JS host.
- `node tests/hs-lua/mv-load.mjs [MV data dir]`: loads MV's scripts against a lenient mock and lists members touched
  while loading that have no Kotlin adapter (mod data is read in place, never copied).
- Tests page mode `hs-lua` (`hsLuaProbe`): the bench load against real engine objects in a flagship fight (gate
  < 0.5 ms per MV-sized frame), whole frames with and without Lua, per-crossing costs, and the semantics script.

## Known differences
- Uninitialised C++ members read 0 here (HS: whatever memory held), e.g. `Hyperspace.Point()` and `Rect()`.

- Hash seed is fixed (HS's varies per run), so `pairs` order is deterministic here.
- `tostring(userdata)` prints our handle, not a C++ address.
- LUA_COMPAT_5_2 (bit32, math.pow) is assumed from HS's build; to be confirmed with the oracle.
- Rendering adapters, Button and Animation timing follow FTL's model as read from HS's headers; not yet compared
  with the original.
