# Debug console

One console for vanilla and modded runs (user decision, 2026-11-04). It holds every command of the port's
development console, Hyperspace's command console and the new `get` and `unlock` commands (HS 1.23.2 `CommandConsole::RunCommand`, then the original
1.6.14 `CommandGui::RunCommand` that HS passes the rest on to). Code: `engine/.../devutil/ConsoleRegistry.kt` (the list
and the gate), `DebugConsole.kt` (the window), `DebugCommands.kt` (ours), `hs/render/HsConsole.kt` (Hyperspace's and the
original's commands). Tests: `ConsoleTest` (`android :app:testDebugUnitTest`) and `tests/console.mjs` ([TESTS.md](TESTS.md)).

## Opening it

| Where | How |
| --- | --- |
| Website | ``` ` ``` or `\` in a run, or the game bar's **Console** button. ``` ` ``` again, Escape or the button closes it. |
| Android | the floating `>_` button during a run (tap: open or close, with the keyboard; drag: move it), or ``` ` ``` / `\` on a hardware keyboard; Back closes it. |
| Android debug build | also `adb shell am broadcast -n dev.ftl911bob.unofficial/ftl.android.app.debug.ConsoleReceiver --es cmd "'RICH'"` (any command; `hscmd <command>` still works). |
| Tests | `window.ftlEngine.consoleExecute(line)`, `consoleOutput()`, `consoleProbe()`; not gated. |

`\` is Hyperspace's `console` hotkey where HS has it (rebindable in its Controls page); ``` ` ``` is the port's
"Debug console" hotkey (rebindable too; under Hyperspace it is HS's `speed` key by default).

## Gating rule

A device setting, **off by default**: the website's Logs page, "Enable debug console" (localStorage
`ftl-debug-console`, this browser only); Android launcher Settings, Help, "Enable debug console" (shared preferences
`debug-console`, from the next game started). Neither is part of a save or the cloud profile.

- **Setting on:** the console opens in every mode, vanilla or modded, on ``` ` ``` and `\` and the keys bound to either
  console hotkey, at any time in a run. The website shows the Console button, Android the `>_` button. Under Hyperspace
  ``` ` ``` then opens the console instead of toggling HS's speed.
- **Setting off:** vanilla: closed. Hyperspace: as HS does it: a mod's `<console enabled="true"/>` (Hyperspace.ftl's and
  Multiverse's hyperspace.xml both have it) opens it on HS's `console` hotkey only while no focus window is open and the
  ship is not jumping (CommandGui::KeyDown hook); ``` ` ``` stays HS's speed key; `<console enabled="false"/>` keeps
  it closed.

## The window

**Desktop (website):** a dark band over the top of the screen, the output above one input line, Up and Down through the
history, Tab completes names and arguments. The suggestions open in a window over the lower half. A picker or the
reference takes most of the screen: the band shrinks to a few lines of output and the line (`DESKTOP_BIG_TOP`), and the
window fills the rest, so the weapon picker shows three and more rows at 1280x720 (a larger browser window shows the
same 1280x720 layout larger). **Android (touch):** the whole screen, a little transparent (`DebugConsole.renderTouch`):
at the top the input line (tap it for the keyboard) and the buttons Clear, Tab, Run, Help, Keys (keyboard on and off)
and Close; under them the recent commands to tap and the last output line; the rest, down to the on-screen keyboard
(the host reports where it begins, `TouchMode.keyboardTop`), holds the suggestions as rows to tap, a picker, the
reference or the output. The text is three times the desktop's. While the console is open every finger goes to it, not
to the touch HUD (`TouchRouter`).

**The look** is the game's own (`ConsoleUi`): a panel sits in the window frame the game's windows use (window_base) with
the map's title tab (HL2) naming it (WEAPONS, HELP); buttons are the native TextButton (light, yellow under the
pointer, a deeper yellow while pressed or switched on); rows and chips the sector cutout's blue; the keys' selection the
crew box's selected green; the rainbow glow stays on `get` and `unlock`. In a panel the header (title, counts, the
search, the ship picker's type tabs and Unlock every ship) and the footer stay put while the content scrolls between
them. A picker's tiles keep the game's size and spacing and are centred in the window; a long key is cut with "..." on
its tile and shown whole in the footer for the tile under the pointer (desktop) or the one tapped or selected last.

**Scrolling** (`ConsoleScroller`) is by pixels, never by rows, and every panel cuts what crosses its edges
(`Graphics.pushClip`, a scissor rectangle in the WebGL and the Android renderer; nested clips intersect), so a tile or
an entry half in view is drawn half. The wheel glides to where it points (a trackpad's small steps follow at once; the
website passes the wheel event's own distance, `BrowserEngine.wheel`, else a notch is 96 pixels); a drag moves the
content with the finger or the mouse; a finger let go while moving flings on and slows evenly to a stop; both ends stop
dead (no overshoot, nothing jitters). The scroll bar shows when the content is taller than the view; on the desktop its
thumb drags and a click on its track pages. A press stops whatever moves where the last frame drew it, so a tap lands
on what it hit, also while the list is still moving; a press that moves past a few pixels is a drag and taps nothing.
The output and the recent commands scroll the same way. The scroll advances once a frame with the frame's time, at the
display's rate.

**Keys** in a picker, the reference or the suggestions: Down selects the first entry in view and moves on, Up moves back
(from the top row it drops the selection; with nothing selected Up is the history), Left and Right move along a
picker's row while something is selected, Enter takes the selection as a click does (a picker gets it and stays open;
the reference fills the command in), Page Up and Page Down scroll a page, typing still searches. Escape drops a
selection first; the next Escape closes the console.

The line edits as HS's TextInput does: Left, Right, Home, End, Backspace, Delete (the character after the cursor; at the
end of the line Delete clears it), at most 480 characters, and a mod's `<console invertCaps="true"/>` (Hyperspace.ftl's
default; Multiverse turns it off) swaps the case of typed letters. Shift+``` ` ``` types `~` (Lua's `~=`), as HS's box
takes every typed character; ``` ` ``` alone, the "Debug console" hotkey and Escape close it. Unlike HS's box it stays
open after Enter, to show the output. Closing drops the half-typed line. The game is paused while it is open. HS's own
InputBox (the 530x260 "Command Prompt:" box) is not drawn any more.

Names match in any case. A Hyperspace or original command gets the whole line, as RunCommand does, and prints nothing
(HS only logs); unknown arguments are ignored as HS ignores them.

`help` opens the **command reference** (`ConsoleReference`): every command by group with its usage, what it does and
examples; what is typed after `help` searches it, a tap or click on a command fills it into the line, on an example that
example. It still prints every command a line each into the output. `help <words>` also prints the matching entries.

## get and unlock

The two new commands, a friendlier front for existing ones: no internal ids, and in the console, its suggestions, the
recent commands and help their name glows in moving rainbow colours (`ConsoleUi.rainbow`). Code: `ConsoleCheats.kt`
(what they do), `ConsoleItems.kt` (the items), `ConsolePicker.kt` (the pickers).

- **Names** are the in-game English names, spaces as underscores: `get weapon Burst_Laser_II`. A ship is named per
  layout after its type A layout's name: `The_Kestrel_A`, `_B`, `_C` (A is unlocked from the start; the name exists).
  Any case works, so do spaces (the picker keeps searching), the current language's name, a weapon's or drone's short
  name, the internal id and a name's words (`burst_ii`); whole words come first (`kestrel c` is `The_Kestrel_C`, not
  every `Kestrel_Cruiser_<letter>`). Where items share an English name, each key ends in its id
  (`Artemis_Missiles_MISSILES_2`, `Artemis_Missiles_MISSILES_2_PLAYER`; `Fire_Beam_BEAM_FIRE`,
  `Fire_Beam_DRONE_FIREBEAM`), so every key the completion and the picker fill in is one item; the tile shows that key
  under its art with the id in white. The shared name alone means the one a store or reward gives when only one is
  not event only (`get weapon Fire_Beam` is BEAM_FIRE, and says what else has that name); else it lists the keys.
- **`*`** stands for any part of a name where the command makes sense for many items: `unlock ship The_Kestrel_*`
  (every Kestrel layout), `unlock ship *` (= `SHIP ALL`; `unlock ship all` too), `unlock achievement *`,
  `get system *` (= `SYS ALL`). Not for `get weapon`, `drone`, `augment`, `crew`: one would fill the slots and cargo
  with everything at once (the rest dropped).
- **Without a name a picker opens** in the game: the items with their own art (the store's weapon, drone, crew and system
  boxes, the equipment screen's augment box, the hangar's ship list cards with a lock while locked and the type A/B/C
  buttons, achievement icons), searched by what is typed after the kind. A tap or click gets or unlocks that item at
  once and the picker stays open; Tab fills the best name in. `get` or `unlock` alone lists the kinds. Under each tile:
  its key (what to type), then its tags.
- Everything comes from the run's data, mod items too, and nothing is filtered out: hidden and event-only items are in
  the pickers and the completion like any other, marked with a tag: **event only** (no rarity or rarity 0: LootPool
  and the stores never pick it), **hidden** (an augment a mod gives as a hidden augment: an event's or a ship's
  `<hiddenAug>`; a hidden Hyperspace achievement), **no art** (below), and the state: locked / unlocked, installed,
  no room. They sit in a row under each tile, so they cover no name. Crew blueprints without a title stay out (none in
  vanilla or Multiverse).
- **No art**: where the game has no art for an item, the tile says so instead of staying empty. The Federation
  cruiser's Artillery (ARTILLERY_FED) and Flak Artillery (ARTILLERY_FED_C) have `weapons/blank.png` as their weapon art
  (their gun is drawn on the ship's hull), so their store box shows the weapon box's icon of their kind
  (`systemUI/weapbox_icon_W_beam`/`_flak`, the icon the game's weapon boxes show) with "no art". The check
  (`ConsoleItems.hasArt`): a weapon's launcher sheet (or Hyperspace's `<iconReplace>`) is not blank.png and exists; a
  drone's icon, a crew race's portrait, a system's icon, a ship's miniship, an achievement's image exist.
- **What get says**: every get, typed or tapped, answers what it granted and where, e.g. `Granted: Burst Laser Mark II -
  to the cargo (no free weapon slot) (WEAPON LASER_BURST_3)`, or why it added nothing.
- **Full equipment** goes as an event's reward with no room goes (WorldManager::ModifyResources): a weapon or drone
  with no free slot and a full cargo, or an augment past the ship's slots, waits in the equipment screen's
  over-capacity box ("Granted: X - cargo full: it waits in the equipment screen's over-capacity box"), and the
  equipment screen opens on it; a crew member past the limit joins and the crew screen asks
  for one to be dismissed. The original never fills a box twice: while it is taken Equipment::IsCompletelyFull holds
  and the next reward turns into scrap (EQUIP_FULL). So while an item or augment waits (and there is no room) another
  get of that kind is refused, e.g. `Augments full: sell or discard the pending Scrap Recovery Arm first (equipment
  screen); Adv. FTL Navigation not added`, and while
  the crew is over its limit `get crew` is refused. Under Hyperspace's `<multipleOverCapacity enabled="true"/>`
  (Multiverse) the box has pages and keeps every item, so gets are not refused there ("(3 waiting)"). An augment the
  ship has that does not stack, past its slots, is the game's AUGMENT_FULL (25 scrap), as from an event.
- **No ship switch**: `get ship` is gone (user, 2026-11-05). To play another ship, unlock it and start a new run;
  `SWITCH` and `SWITCHALL` (Hyperspace) still switch.

| Command | Goes through | Says |
| --- | --- | --- |
| `get weapon <name>`, `get drone <name>` | `WEAPON`, `DRONE`: a free slot, else the cargo, else the over-capacity box | where it went, or why not |
| `get augment <name>` | `AUG` below three augments; past that an event's augment reward (a free HS slot, else the over-capacity box) | where it went, or why not |
| `get crew <race>` | `CREW` (past the limit the crew screen asks for one to go) | aboard, crew full, or refused while one is extra |
| `get system <name>`, `get system *` | `SYS <name>`, `SYS ALL` | added, or no room / installed |
| `get scrap\|fuel\|missiles\|drone-parts\|hull <amount>` | `SCRAP`, `FUEL`, `MISSILES`, `DRONES`, `HULL` | before and after |
| `unlock ship <ship>_<A\|B\|C>`, with `*` | the profile's layout unlock with no popup, as CustomShipUnlocks::UnlockAllShips makes it per layout; a Hyperspace mod's own layout: `SHIP_CUSTOM` | unlocked, already, or a seeded run that unlocks nothing |
| `unlock ship *` | `SHIP ALL` | layouts unlocked before and after |
| `unlock achievement <name>`, with `*` | `ACH` (HS: the mod's own achievements) | unlocked, already, or why not |

## Commands

The reference below is generated from the command definitions (`ConsoleRegistry.kt`, `DebugCommands.kt`) by
`ConsoleReference.markdown`; `ConsoleTest` fails when it differs and rewrites it with `CONSOLE_DOCS_WRITE=1`. Upper case
are Hyperspace's and the original game's (HS 1.23.2 `CommandConsole::RunCommand`, the original 1.6.14
`CommandGui::RunCommand`). "Needs Hyperspace" ones answer "X needs a Hyperspace mod, and this run has none." in vanilla;
the others run in vanilla too, on vanilla's data: `SYS` takes a free room of the layout (the original's AddSystem),
`LOADEVENT` and `EVENT` load the base event (no aliases or load lists), `ACH` unlocks a vanilla achievement at the run's
difficulty, `GOD` sets the reactor to the ship's maximum (HS: the ship definition's), `SPEED` steps through CFPS while n
is not 0. The original's `LOAD`, `INDEX`, `SECTOR`, `LANG`, `LOADTEXT`, `CLEAR_ONLINE_ACH` and `SHIP <name>` are not
ported.

<!-- generated: ConsoleReference.markdown, see ConsoleTest -->

### Get and unlock

| Command | What it does | Examples |
| --- | --- | --- |
| `get <kind> [name\|amount]` | Get an item by its in-game English name (spaces as _): a weapon, drone, augment, crew member, system or resources; hidden and event-only ones too. With no name a picker opens. Says what it granted and where. With no room it goes where an event's reward goes (the over-capacity box, the crew screen); while one waits there, it refuses another. Goes through WEAPON, DRONE, AUG, CREW, SYS, SCRAP, FUEL, MISSILES, DRONES or HULL. | `get weapon Burst_Laser_II`, `get augment Scrap_Recovery_Arm`, `get crew Engi`, `get drone`, `get system Cloaking`, `get system *`, `get scrap 500` |
| `unlock <ship\|achievement> [name]` | Unlock a ship layout (<ship name>_A/_B/_C, * for any part of the name) or an achievement for good (profile). With no name a picker opens. unlock ship * is SHIP ALL; one layout is the profile's own unlock (a mod's ship: SHIP_CUSTOM); an achievement is ACH. | `unlock ship The_Kestrel_B`, `unlock ship The_Kestrel_*`, `unlock ship *`, `unlock ship`, `unlock achievement Full_Arsenal`, `unlock achievement *` |

### Resources

| Command | What it does | Examples |
| --- | --- | --- |
| `dev-rich` | Set scrap to 5000 and fuel, drone parts and missiles to 99 |  |
| `dev-poor` | Set scrap, fuel, drone parts and missiles to 0 |  |
| `RICH` | +9999999 scrap, +800 fuel, drone parts and missiles (missiles need a weapons system) |  |
| `POOR` | -9999999 scrap, -800 fuel, drone parts and missiles (none below 0) |  |
| `SCRAP <n>` | Add n scrap (negative takes it away, none below 0) | `SCRAP 100`, `SCRAP -50` |
| `FUEL <n>` | Add n fuel | `FUEL 10` |
| `MISSILES <n>` | Add n missiles (with a weapons system; none below 0) | `MISSILES 10` |
| `DRONES <n>` | Add n drone parts (none below 0) | `DRONES 10` |
| `HULL <n>` | Repair n hull points, up to the maximum | `HULL 10` |

### Weapons, drones, augments and crew

| Command | What it does | Examples |
| --- | --- | --- |
| `dev-weapon name` | Select a weapon, and add it to the ship's cargo area | `dev-weapon LASER_BURST_2` |
| `dev-drone drone` | Select a drone, and add it to the ship's cargo area |  |
| `dev-aug` | Pick an augment from a list, and add it to the ship's cargo area |  |
| `skills` | Edit the crew's skills |  |
| `killcrew` | Kill one all of your crewmembers |  |
| `hurt amount` | Damage all of the player's crewmembers by a given amount (negative heals) | `hurt 20` |
| `dev-skill skill` | Give the first player crewmember experience in a skill: 'dev-skill shields [count]'. Names: piloting/engines/shields/weapons/repairs/combat | `dev-skill shields 2` |
| `KILL <crew name>` | Kill the player crew member of that name | `KILL Slocknog` |
| `SKILL` | Master every skill of the player's crew |  |
| `WEAPON <weapon>` | Add a weapon: a free weapon slot, else the cargo | `WEAPON LASER_BURST_2` |
| `DRONE <drone>` | Add a drone: a free drone slot, else the cargo | `DRONE COMBAT_1` |
| `AUG <augment>` | Add an augment while the ship has fewer than three | `AUG ADV_SCANNERS` |
| `CREW <race>` | Add a crew member of that race | `CREW engi` |

### Ship and systems

| Command | What it does | Examples |
| --- | --- | --- |
| `fix` | Fix the ship's hull and all systems, clearing ion damage |  |
| `system name` | Unlock a system on the current ship, or 'list' or 'all' | `system list`, `system all`, `system cloaking` |
| `upall` | UPgrade ALL systems on the player ship to the maximum level |  |
| `downall` | Downgrade all systems on the player ship to their starting level |  |
| `damage amount` | Apply a given amount of damage to the player ship (or negative to heal) | `damage 5`, `damage -5` |
| `breach` | Spawn a hull breach in a random room of the player ship |  |
| `ion amount` | Apply ion damage to a random system of the player ship, like a weapon hit | `ion 2` |
| `wreck system` | Break a system of the player ship ('wreck doors'), or ionise it ('wreck doors ion [bars]'). 'fix' repairs it | `wreck doors`, `wreck shields ion 2` |
| `autofire` | Toggle autofire globally, or for one weapon slot (1-4) | `autofire`, `autofire 1` |
| `reset-ftl` | Reset the player's FTL charge timer |  |
| `SYS <system>\|ALL` | Add a system the ship has room for; SYS ALL adds every one | `SYS cloaking`, `SYS ALL` |
| `DAMAGESYS <system>` | Break a player system completely | `DAMAGESYS shields` |
| `REMOVESYS <system>` | Remove a player system (needs Hyperspace) | `REMOVESYS cloaking` |
| `SWITCHALL <ship>` | Switch to another ship, keeping crew, gear, resources and levels (needs Hyperspace) | `SWITCHALL PLAYER_SHIP_FED` |
| `SWITCH <ship>` | Switch to another ship as it starts (needs Hyperspace) | `SWITCH PLAYER_SHIP_FED` |
| `GOD` | Every system and the reactor to their maximum |  |

### Enemies and combat

| Command | What it does | Examples |
| --- | --- | --- |
| `cld` | CLear all Drones - destroys all currently-deployed drone instances |  |
| `ecrew race` | Spawn a new crewmember on the enemy ship, same args as 'CREW' | `ecrew mantis` |
| `boarder race` | Spawn a hostile intruder on the player ship, same args as 'CREW' | `boarder mantis` |
| `dev-kill` | Destroy the enemy ship (100 hull damage, then 0 hull) |  |
| `boss` | Spawn the sector boss (flagship) at the current beacon |  |
| `kill-ecrew` | Kill all crew aboard the enemy ship (including any boarding you) |  |
| `spawn-ship spec difficulty/seed` | Spawn an enemy ship directly from a seed | `spawn-ship PIRATE_INTERCEPTOR 3.normal` |
| `enemy-weapon` | Add or remove (the the remove argument) an enemy weapon | `enemy-weapon add`, `enemy-weapon clear` |
| `force-hack system` | Forces the enemy to hack a given player system | `force-hack shields` |
| `super-shield` | Give the player (or enemy) a super-shield (see help sub-cmd) | `super-shield 5`, `super-shield help` |
| `DELETECREW` | Kill the enemy ship's crew |  |
| `COOLSWORDBRO` | Same as DELETECREW |  |
| `DELETE` | Destroy the enemy ship |  |

### Map, events and stores

| Command | What it does | Examples |
| --- | --- | --- |
| `dev-store` | Give this beacon a store (opened from the Store button) |  |
| `dev-event` | Search the events, and load the one picked at this beacon |  |
| `sectors` | Open the sector map, regardless of the current beacon |  |
| `env` | Change the environment at the current beacon |  |
| `gameover outcome` | End the game with the win/loose screen. | `gameover WIN` |
| `STORE [sector]` | A new store here, opened at once (this sector's stock, or the given sector's) | `STORE`, `STORE 5` |
| `FORCE_STORE <store id>` | The next store is the mod's custom store <store id> (needs Hyperspace) |  |
| `EXIT` | Open the sector map and leave this beacon |  |
| `FLEET <n>` | Rebel fleet: n > 0 advances twice as fast for n jumps, n < 0 stops it for -n jumps | `FLEET 3`, `FLEET -2` |
| `LOADEVENT <event>` | Load an event here now (used events too) | `LOADEVENT STORE_FUEL` |
| `CREDITS` | Win the run: the victory screen |  |
| `VICTORY` | Same as CREDITS |  |
| `EVENT <event>` | This beacon's event replaced by <event>, shown as on arrival | `EVENT STORE_FUEL` |

### Profile: ships and achievements

| Command | What it does | Examples |
| --- | --- | --- |
| `ach-set achievement difficulty` | Set the difficulty an achievement was achieved with | `ach-set ACH_FULL_ARSENAL HARD` |
| `ach-del achievement` | Remove an achievement |  |
| `ship-unlock ship difficulty` | Set the difficulty a ship family's unlock quest was completed with | `ship-unlock PLAYER_SHIP_CIRCLE NORMAL` |
| `ship-lock ship` | Un-completes a ship unlock quest |  |
| `layout-unlock ship difficulty` | Unlock an official ship layout | `layout-unlock PLAYER_SHIP_HARD_2 EASY` |
| `SHIP ALL` | SHIP ALL: unlock every ship and layout for good (profile) | `SHIP ALL` |
| `SHIP_CUSTOM <ship>` | Unlock a mod ship for good (profile) (needs Hyperspace) |  |
| `SHIP_CUSTOM_LOCK <ship>` | Lock a mod ship again (profile) (needs Hyperspace) |  |
| `METAVAR <name> [value]` | Set a mod's profile variable (needs Hyperspace) | `METAVAR prof_test 1` |
| `METAVARIABLE <name> [value]` | Same as METAVAR (needs Hyperspace) |  |
| `MVAR <name> [value]` | Same as METAVAR (needs Hyperspace) |  |
| `ACH <achievement>` | Unlock an achievement (profile) | `ACH ACH_FULL_ARSENAL` |
| `ACH_LOCK <achievement>` | Lock a mod achievement again (profile) (needs Hyperspace) |  |

### Mods and scripting

| Command | What it does | Examples |
| --- | --- | --- |
| `SPEED <n>` | Game speed (16 + 7n)/16: 0 normal, 2 almost double, -2 one eighth | `SPEED 2`, `SPEED 0` |
| `LUA <code>` | Run Lua code in the mod's scripts (needs Hyperspace) | `LUA print("hello")` |
| `DEBUG` | Does nothing (Hyperspace keeps the word) |  |
| `VAR <name> [value]` | Set a mod's run variable (needs Hyperspace) | `VAR loc_test 1` |
| `VARIABLE <name> [value]` | Same as VAR (needs Hyperspace) |  |
| `SQUISHY <text>` | Show <text> at the top right of the screen | `SQUISHY Hello` |

### Saves, flags and diagnostics

| Command | What it does | Examples |
| --- | --- | --- |
| `set flag` | Turn on or off debug flags | `set help`, `set vis` |
| `dump-save` | Save the game to XML, and print it to standard output |  |
| `save-load` | Save the game to XML, and load it back in. |  |
| `gc` | Manually trigger Java's Garbage Collector. |  |
| `save name` | Save a named browser checkpoint: save name | `save before-boss` |
| `load name` | Load a named browser checkpoint: load name | `load before-boss` |
| `download` | Download the current run as an XML checkpoint |  |
| `import` | Choose and import a browser XML checkpoint |  |
| `inspect` | Inspect current input, UI, weapons, RNG and recording state |  |
| `mark` | Mark an issue in the active diagnostic recording |  |
| `reload-console` | Reload the console (useful with Java HotSwap) |  |
| `reload-flags` | Reload the debug flags (useful with Java HotSwap) |  |
| `help [search]` | The command reference: every command by group, with its usage, what it does and examples. Type after help to search it; tap or click a command to fill it in | `help`, `help get` |

<!-- end of the generated reference -->

### Name clashes

Where both consoles had a name, Hyperspace's (or the original's) command kept it:

| Name | Hyperspace / original | Ours, now |
| --- | --- | --- |
| `rich` | +9999999 scrap, +800 of the rest | `dev-rich`: scrap 5000, the rest 99 |
| `poor` | -9999999 / -800, none below 0 | `dev-poor`: everything 0 |
| `weapon`, `drone` | a free slot first, else the cargo; no message | `dev-weapon`, `dev-drone`: the cargo, with a message |
| `aug` | `AUG <name>`, below three augments | `dev-aug`: a picker into the cargo |
| `store` | a new store, opened | `dev-store`: the beacon gets a store, not opened |
| `event` | `EVENT <name>`: ClearLocation, a new scene, the event as an arrival | `dev-event`: a search, the event shown over the beacon |
| `kill` | `KILL <crew name>` | `dev-kill`: destroys the enemy ship |
| `skill` | `SKILL`: masters every skill of every player crew member | `dev-skill <skill> [count]`: points for one |
| `scrap`, `fuel`, `missiles`, `drones`, `crew` | the original's | ours did the same (ours let resources go below 0): gone, the original's keeps our completion |

## Profile changes and the cloud

`SHIP ALL`, `SHIP_CUSTOM`, `ACH`, `METAVAR`, `ship-unlock`, `layout-unlock`, `ach-set` (and the `-lock`/`-del`
ones) change the profile for good, and a cloud profile syncs it to every device. Nothing marks or asks about that yet:
an open decision (`.build/hs/reports/console.md`).
