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
| C bridge | `hs-lua/hslua.c`, `linit.c`, `hsconf.h` | Userdata, metatables, `script.*`, HS's `print`/`log`/`math.random`, caps and hooks, profiling |
| Libraries | `hs-lua/hslibs.c`, `hs-liolib.c`, `hs-loadlib.c`, `hs-lauxlib.c` | io, os, debug, package on the private file system (Lua's own liolib, loadlib, lauxlib, ldblib, unchanged) |
| Folder (host) | `web/lua-files.mjs`; `hslua_jni.c` + `ftl.android.LuaLibs` | Keeps the private folder (IndexedDB; the app's storage) |
| Ask before running | `web/mods/lua-scan.mjs`, `lua-consent.mjs`; `android/mods` `LuaScan.kt`, app `LuaSandbox.kt` | The scan, the prompt, the answers, the Android setting |
| Generated tables | `hs-lua/gen/` (C), `engine/.../hs/lua/LuaApi.kt` (Kotlin ids) | From HS's SWIG wrappers, see below |
| Wasm | `web/vendor/hs-lua.wasm` | Built by `sh hs-lua/build.sh` (pinned emsdk 3.1.74 image; byte-identical rebuilds) |
| Loader | `web/hs-lua.mjs` | Instantiates the wasm, binds the engine's `hsLua*` exports, sets `window.ftlHsLua` |
| Engine side | `engine/src/main/java/xyz/znix/xftl/hs/lua/` | Handles, adapters, hooks, diagnostics probe |

Loading: HS creates its Lua state at start-up whether or not a mod has scripts (`src/Global.cpp:138`, `new
LuaScriptInit()`), so the console's `LUA` works on plain Hyperspace. Here too: `web/game.mjs` imports `hs-lua.mjs` when
the mod session is Hyperspace (`window.ftlModSession.hyperspace`, or lists scripts), before `engine.start`; vanilla never
loads it. `BrowserEngine.start` (web and Android) then calls `HsLua.startForSession`, which runs the scripts in `<scripts>` order
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
- Removed globals as in HS: `collectgarbage dofile load loadfile newproxy rawequal rawget rawset`. Libraries: every
  standard library of Lua 5.3 **without** LUA_COMPAT_5_2 (no `bit32`, no `math.pow`, `ldexp`, `cosh`, `log10`), with
  `coroutine`, `io`, `os`, `debug` and `package`/`require`, as the original has them (HS 1.23.2's own `lua/linit.c` is not
  compiled into its Windows build; oracle `ps-m1-ev` lib and glob rows). `io`, `os`, `debug` and `package` run on a
  private file system with no shell: see [Files, programs and the sandbox](#files-programs-and-the-sandbox). `os.clock`
  counts seconds from the runtime's first start, as MSVC's `clock()` counts from the process start (Multiverse's
  `tutorial.lua` pulses its outlines with it). `print` and `log` join arguments with four spaces; `print` also feeds
  `Hyperspace.PrintHelper` (on-screen text).
- `math.random`: FTL's `random32` with HS's formula; NativeRandom while generation callbacks run
  (`LuaEvents.generation`), BrowserRandom otherwise.
- Budgets: an instruction cap per callback (default 50M, `lua_sethook` count, restored for nested callbacks) and
  per script load (500M); the error names the mod file and the callback. `__gc` handlers run under the cap too, also
  outside a callback, where they share one 50M (hooks are off there in stock Lua, `hs-lua/hslua-lvm.c`). Profiling per callback (calls,
  instructions, max, errors, ms): `BrowserEngine.hsProfile(reset)` returns JSON.

## Files, programs and the sandbox

The original Hyperspace runs mod scripts with Lua 5.3.5's full libraries on the player's machine: `io` reads and writes
any file (the working folder is the game's), `os.execute`/`io.popen` run programs, `os.getenv` reads the environment,
`require` loads Lua files from the game's folder, `debug` reaches everything (oracle session `ps-m1b-libs`, probe
`tools/hs-oracle/tracer/data/hs_oracle/ps-m1b.lua`; 244 rows, `tests/hs-lua/libs-native.txt`). Here the same functions,
with the same return values and messages, run on a private file system (the user's decision, 2026-11-08):

- **Files.** One folder per account on this device, shared by every mod (as the original's game folder), kept by the
  host: IndexedDB `ftl-lua-files` in the browser (`web/lua-files.mjs`), `files/lua-files/<account>/` on Android (one
  file per name, the name hex-encoded, written to a temporary file then renamed, opened with `O_NOFOLLOW`). Not synced.
  The runtime keeps the files in memory (`hs-lua/hslibs.c`); the engine writes changes out every 120 steps and when it
  stops (`HsLua.tick`, `HsLua.shutdown`). 50 MiB at most (contents of all files, `io.tmpfile` included): a write past it
  fails as on a full disk (`nil, "No space left on device", 28`, usually at `close` or `flush`, as buffered writes do).
  At most 10 000 files. Names follow Windows: case-insensitive (case kept), `/` and `\` both separate, trailing dots
  and spaces dropped, `<>:"|?*` and control characters are "Invalid argument". The folder has no subfolders, so a path
  with one is "No such file or directory" (as natively for a folder that is not there); absolute paths (`/x`, `\x`,
  `C:...`) and `..` above the folder are "Permission denied". An open file cannot be removed or renamed and a rename
  does not replace an existing file (Windows; oracle rows `remove-open`, `rename-onto`). A write-only file reads as at
  its end, writes to a read-only file are taken and dropped (msvcrt). errno numbers and texts are msvcrt's (2, 13, 17, 22,
  28) on every build. The standard input is empty; `io.write`/`io.stdout`/`io.stderr` go to the game's log, line by line.
  Clear mod files: the Mods panel (web), Mods screen (Android).
- **Programs.** No shell, as Lua built for a system without one: `os.execute()` is `false`, `os.execute(cmd)` is
  `nil, "No such file or directory", 2`, `io.popen` raises `'popen' not supported` (Lua's own message; no mode check
  first, as Lua 5.3.5, the original's). `os.getenv` is `nil`. `os.tmpname` returns a name of the shape msvcrt's `tmpnam`
  gives (`\sXX.`, the root of the drive), which cannot be opened here either (natively it is not writable: oracle
  `tmpname-writable`). `os.setlocale` keeps "C" (`""` and `"C"` return "C", anything else `nil`).
- **os.exit**: the original closes FTL. Here the script stops (every instruction raises until its callback ends), no
  callback or script runs after it, and the game ends without another save, back to the launcher with a message naming
  the mod (`hs_exit_*`, `HsLua.tick`, `window.ftlLuaExit` in `web/app.mjs`, `AndroidHost.luaExit`).
- **os.date / os.time** as the Windows build: the Windows list of conversions (`%D`, `%e`, `%F`, ... are "invalid
  conversion specifier"), msvcrt's text (`%c` is `%m/%d/%y %H:%M:%S`, `%#c` the long form, `#` drops leading zeros),
  32-bit `time_t` (dates before 1970 or after 2038 fail as natively). `%z` and `%Z` print the zone's name (msvcrt gives
  Windows' name of the machine's zone; here the browser's or Android's long English name).
- **package/require**: `package.path`, `cpath` and `config` are the Windows build's with the game folder (`!`) as `.`;
  module names map to `\` paths; text chunks only (a compiled chunk, which `require` loads natively, is refused:
  `attempt to load a binary chunk (mode is 't')`: it could break out of the runtime on Android); no C libraries
  (`package.loadlib` and the C searchers answer as Windows does for a missing DLL).
- **debug**: Lua's library. `debug.sethook` is chained with the instruction cap: one C hook (`hs_hook`) serves every
  thread, counting the cap and calling the script's hook; the count event comes at whichever runs out first, so a
  script can neither remove nor replace the cap (also on coroutines, `debug.sethook(co)`), and a loop inside its own hook
  still counts (`hslua-lvm.c` lets the count run where Lua has hooks off). `debug.gethook` shows only the script's
  hook. What would let a script reach the runtime's own state is held back: `getregistry` returns a copy of the
  registry's named entries (`_LOADED`, `_PRELOAD`, `FILE*`, the main thread, the globals), a full userdata's metatable
  cannot change, C functions show no upvalues (`getupvalue`/`setupvalue` return nothing, `upvalueid` is an invalid
  index), locals of a C function's frame cannot be set, and `debug.debug` returns at once (there is no console). The
  runtime tells its userdata from io's files by size, not by metatable.
- **The scan and "Ask before running".** Before a mod set whose scripts use these libraries starts, the launcher reads
  the session's scripts (`web/mods/lua-scan.mjs`, `android/mods/.../LuaScan.kt`, cases `tests/hs-lua/lua-scan-cases.json`):
  names as Lua reads them, comments and strings skipped, `io.open`, `io["open"]`, a library kept in a variable,
  `_G.io`, `_G["io"]`, `require`, `package.*`, `debug.*`. Not asked about: `os.clock`, `os.time`, `os.date`,
  `os.difftime`, `os.setlocale`, `io.write`, `io.read`, `io.type`, the standard streams. Not seen: names built at run time
  (`_G["i" .. "o"]`), a library handed over by another script; those calls still run in the sandbox. Each script counts
  for the last archive that has its file. The prompt names per mod what it uses in plain words and offers Run and
  Don't run (web: `lua-consent-dialog`, Android: the launcher's AlertDialog); Don't run goes back to the launcher with
  nothing started. It is the only question about scripts, for Custom and catalogue mods alike (Android's earlier
  "Run scripts from a Custom mod?" is gone; a mod whose scripts use none of these starts without a question). Run is kept per mod version (archive SHA-256) and account (web: `localStorage`
  `ftl-lua-consent:<account>`; Android: `lua-consent` preferences) and can be taken back with Ask again in the Mods panel.
  No script can answer it or reach the setting: both live in the page or the app, and the runtime has no call for them.
- **"Disable Lua sandboxing (dangerous)"** (Android Settings, Mod scripts; per device, off by default). Turning it on
  needs a second confirm naming the dangers; off needs nothing. With it on, the prompt says what scripts can really do,
  an earlier Run given with the sandbox on does not count, and only the scripts of mods allowed with it on
  (`LuaLibs.sources`, by chunk name of the nearest Lua function on the stack) get real files (relative paths start in
  the app's own storage, which holds the saves and the sign-in) and `/system/bin/sh` (`cd <storage> && cmd`), the real
  environment and `mkstemp`. Every other script stays sandboxed. The web cannot give scripts files or programs: the
  setting is shown there disabled with that reason.

Differences from the original that a script could notice (besides the sandbox's): no CRLF translation in text mode
(msvcrt writes `\r\n` for `\n`: seek positions and binary reads of a text-written file differ; a text round trip is the
same); the Lua is 5.3.6 (the original's 5.3.5 has no `io.popen` mode check); paths in messages and `package.path` read
`.` where the original names its exe folder. Tests: `tests/hs-lua/libs.mjs` (every probe row as the original or a
listed sandbox value, then the sandbox checks), `tests/hs-lua/lua-scan.mjs`, `LuaScanTest`, `tests/mods-scripts.mjs`.

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
| HAS_EQUIPMENT (-1000), HAS_AUGMENTATION, GET_AUGMENTATION_VALUE | `LuaAdaptersWP1` chains on `HsReq.hasEquipment`, `HsAugments.count` / `value` | integer results only for the first two (`lua_isinteger`), any number for the value; the value chain runs on a ship without the augment too (from 0) |
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

Objects a script reaches beside the events (sweep M1, oracle `ps-m1-ev`):
- `Hyperspace.ships.enemy` keeps an enemy that escaped until the player leaves the beacon (WorldManager keeps
  `playerShip->enemyShip`): no longer looped, no crew, `bJumping` and `bDestroyed` set.
- `combatControl.droneControl.boxes`: one box per drone slot (DroneBox): icon `weapbox_icon_D_<iconImage>` on
  `weapbox_inner_dronebox`, hotKey 5 + i, iconColor 150 with a drone; empty: `weapbox_inner_droneoff`, hotKey -1, 255.
  Writes to a drone box's place and sizes read back but do not move the drone bar (reported unsupported).
- `RapidXML`: `xml_base` name/value and `xml_node` type are writable; the module's `parse_*` flags are its numbers.
- `SoundControl:PlaySoundMix(name, volume, loop)`: a volume above 0 is the channel's own, 0 or less (scripts pass -1)
  the sound's sounds.xml volume, as PlaySoundMix (0x5d5160) takes it (`FTLSound.playAt`; `hsLuaProbe` checks both).
- `CrewMember.iShipId` takes 0 or 1 (with an enemy there); any other number is refused (natively it is stored, and
  the original hangs at the next fight).
- `Room.bBlackedOut`, `Ship:SetRoomBlackout` and `ShipGraph:GetRoomBlackedOut` are the room's darkness to the player,
  set again every frame (ShipManager::OnLoop); a write holds until then. `ShipManager.tempVision[room] = true` lights
  that room for the next frame and is cleared, as CheckVision does (`hsLuaProbe` checks both).

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
`WEAPON_COOLDOWN_MOD` fires for artillery too; writing the pair back, `w.cooldown = c`, takes `c.first` in that scale too: ps-fu3 vol). `Ship.shipImage` moves the mounts ahead of their rotation, not the hull. `ForceDecreasePower`
takes bars (shields a layer) without changing the demand, so the system climbs back next frame while the reactor allows. A written
`currentFiringAngle` holds until the weapon fires. `ShipRepairDrone.repairBeams` holds the waves while the drone repairs: each climbs 1.2/s, a
new one starts when the last passes 0.25. `crewList[i]` is a copy; `roomDefs[i]` is not. A `ShipBlueprint` file name that isn't a file is only a
string until a ship is built from it. `weaponVisual.anim.currentFrame` follows the charge, also on a written animation.
`.build/hs-writes/members.tsv` holds each member's class; the open differences are in `.build/hs/reports/hs-writes.md`.

## Tests

- `node tests/hs-lua/semantics.mjs`: callback order, HALT/PREEMPT, rewriting, plain results, errors, registration
  checks, render pre/post, instruction cap, on_load (same script as the browser probe).
- `node tests/hs-lua/libs.mjs`: io, os, debug and package against the original (probe `ps-m1b.lua`, rows
  `libs-native.txt`) and the sandbox's checks (paths, no shell, size cap, cap with hooks, debug limits, os.exit, kept folder).
- `node tests/hs-lua/lua-scan.mjs`: the launcher's scan (`lua-scan-cases.json`, also run by Android's `LuaScanTest`).
- `node tests/hs-lua/bench.mjs 2000`: bridge cost with a JS host.
- `node tests/hs-lua/mv-load.mjs [MV data dir]`: loads MV's scripts against a lenient mock and lists members touched
  while loading that have no Kotlin adapter (mod data is read in place, never copied).
- Tests page mode `hs-lua` (`hsLuaProbe`): the bench load against real engine objects in a flagship fight (gate
  < 0.5 ms per MV-sized frame), whole frames with and without Lua, per-crossing costs, and the semantics script.

## Known differences
- Uninitialised C++ members read 0 here (HS: whatever memory held), e.g. `Hyperspace.Point()` and `Rect()`.

- Hash seed is fixed (HS's varies per run), so `pairs` order is deterministic here.
- `tostring(userdata)` prints our handle, not a C++ address.
- Rendering adapters, Button and Animation timing follow FTL's model as read from HS's headers; not yet compared
  with the original.
- Natively a SWIG userdata reads nil for every member after a failed write to an immutable member (`p.iShipId = 3`
  on a ShipManager; a fresh `Hyperspace.ships.player` works); here the object keeps working (oracle `ps-m1-ev`
  `err|break-immutable`).
- `ProjectileFactory.lastTargets` of an AI ship's weapon is empty here after its volley; natively it keeps one point
  per shot (oracle `ps-m1-ev` `lt` rows: 1 and 3).
- The `.table` of a destroyed object stays until the run ends (HS clears it in the destructor); only memory differs.
