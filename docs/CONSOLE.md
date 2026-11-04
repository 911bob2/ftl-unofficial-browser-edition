# Debug console

One console for vanilla and modded runs (user decision, 2026-11-04). It holds every command of the port's
development console and of Hyperspace's command console (HS 1.23.2 `CommandConsole::RunCommand`, then the original
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

The port's console: a grey half screen, the output above one input line, Up and Down through the history, Tab
completes names and arguments. The line edits as HS's TextInput does: Left, Right, Home, End, Backspace, Delete (the
character after the cursor; at the end of the line Delete clears it), at most 480 characters, and a mod's
`<console invertCaps="true"/>` (Hyperspace.ftl's default; Multiverse turns it off) swaps the case of typed letters.
Closing drops the half-typed line. The game is paused while it is open. HS's own InputBox (the 530x260 "Command
Prompt:" box) is not drawn any more.

Names match in any case. A Hyperspace or original command gets the whole line, as RunCommand does, and prints
nothing (HS only logs); unknown arguments are ignored as HS ignores them. `help` lists every command, a line each.

## Commands

### Hyperspace and the original game

Upper case as HS spells them; "HS" = needs a Hyperspace mod (in vanilla it answers "X needs a Hyperspace mod, and this
run has none."). The others run in vanilla too, on vanilla's data where HS's code needs a mod (noted).

| Command | What it does | Vanilla |
| --- | --- | --- |
| `RICH` | +9999999 scrap, +800 fuel, drone parts and missiles (missiles need a weapons system) | yes |
| `POOR` | the same taken away, none below 0 | yes |
| `STORE [sector]` | a new store here, opened at once (this sector's stock or the given one's) | yes |
| `FORCE_STORE <id>` | the next store is the mod's custom store | HS |
| `EXIT` | the sector map, leaving this beacon | yes |
| `KILL <name>` | kills the player crew member of that name | yes |
| `SKILL` | masters every skill of the player's crew | yes |
| `DELETECREW`, `COOLSWORDBRO` | kills the enemy ship's crew | yes |
| `SPEED <n>` | game speed (16 + 7n)/16 (CFPS::OnLoop): 0 normal, 2 almost double, -2 one eighth | yes (the step goes through CFPS while n is not 0) |
| `LUA <code>` | runs Lua in the mod's scripts | HS |
| `SYS <system>`, `SYS ALL` | adds a system the ship has room for, or every one | yes (the original's AddSystem: a free room of the layout) |
| `DAMAGESYS <system>` | breaks a player system completely | yes |
| `REMOVESYS <system>` | removes a player system (HS CustomSystems) | HS |
| `DEBUG` | nothing (HS keeps the word) | yes |
| `SHIP ALL` | unlocks every ship and layout for good, without popups (CustomShipUnlocks::UnlockAllShips: the vanilla ships, then a mod's) | yes |
| `SHIP_CUSTOM <ship>`, `SHIP_CUSTOM_LOCK <ship>` | unlocks / locks a mod ship (profile) | HS |
| `FLEET <n>` | the rebel fleet: n > 0 twice as fast for n jumps, n < 0 stopped for -n jumps | yes |
| `LOADEVENT <event>` | loads an event here now, used ones too | yes (the base event, no aliases or load lists) |
| `CREDITS`, `VICTORY` | wins the run | yes |
| `VAR`/`VARIABLE <name> [value]` | a mod's run variable | HS |
| `METAVAR`/`METAVARIABLE`/`MVAR <name> [value]` | a mod's profile variable | HS |
| `ACH <achievement>` | unlocks an achievement (profile) | yes (the original's: a vanilla achievement at the run's difficulty) |
| `ACH_LOCK <achievement>` | locks a mod achievement again | HS |
| `SWITCH <ship>`, `SWITCHALL <ship>` | switches to another ship as it starts / keeping crew, gear, resources and levels | HS |
| `SQUISHY <text>` | the text at the top right of the screen | yes |
| `WEAPON <weapon>` | adds a weapon: a free slot, else the cargo | yes |
| `SCRAP`, `FUEL`, `MISSILES`, `DRONES <n>` | adds n (the original's; none below 0) | yes |
| `HULL <n>` | repairs n hull points, up to the maximum | yes |
| `DRONE <drone>` | adds a drone: a free slot, else the cargo | yes |
| `AUG <augment>` | adds an augment while the ship has fewer than three (`AUG HIDDEN <name>` under HS) | yes |
| `CREW <race>` | adds a crew member | yes |
| `GOD` | every system and the reactor to their maximum (HS: the ship definition's reactor) | yes |
| `DELETE` | destroys the enemy ship | yes |
| `EVENT <event>` | this beacon's event replaced, shown as on arrival | yes (the base event) |

The original's `LOAD`, `INDEX`, `SECTOR`, `LANG`, `LOADTEXT`, `CLEAR_ONLINE_ACH` and `SHIP <name>` are not ported.

### The port's own

| Command | What it does |
| --- | --- |
| `dev-rich`, `dev-poor` | scrap 5000 and 99 of the rest / everything 0 |
| `dev-weapon <weapon>`, `dev-drone <drone>` | into the cargo |
| `dev-aug` | an augment picked from a list, into the cargo |
| `dev-store` | gives this beacon a store (opened from the Store button) |
| `dev-event` | searches the events and loads the one picked |
| `dev-kill` | destroys the enemy ship |
| `dev-skill <skill> [count]` | skill points for the first crew member |
| `fix [enemy]`, `cld`, `ecrew`, `boarder`, `skills`, `killcrew`, `sectors`, `boss`, `kill-ecrew`, `system <name\|list\|all>` | repairs, drones cleared, crew spawned (enemy / intruder), the skill editor, crew killed, the sector map, the flagship here, enemy crew killed, systems unlocked |
| `spawn-ship <spec> <sector.difficulty\|seed>`, `enemy-weapon [add\|remove\|clear]`, `force-hack <system>`, `super-shield [n[/max]] [player\|enemy]` | enemy and combat set-ups |
| `upall`, `downall`, `damage <n>`, `breach`, `ion <n>`, `wreck <system> [ion [bars]]`, `autofire [slot]`, `hurt <n>`, `reset-ftl`, `env` | ship state |
| `set <flag\|all\|vis\|help>` | debug flags (`set !<flag>` turns one off) |
| `save <name>`, `load <name>`, `download`, `import`, `dump-save`, `save-load` | checkpoints and save round trips |
| `inspect`, `mark`, `gc`, `reload-console`, `reload-flags` | diagnostics |
| `gameover <WIN\|LOOSE_HULL\|LOOSE_CREW…>`, `ach-set <ach> <difficulty>`, `ach-del <ach>`, `ship-unlock <ship> <difficulty>`, `ship-lock <ship>`, `layout-unlock <ship> <difficulty>` | the end screen and profile progress |
| `help` | every command |

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
