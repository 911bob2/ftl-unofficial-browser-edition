# Enemy ship generation

`ShipGenerator.buildShip` is a port of the original's enemy generation (FTLGame.exe 1.6.14 AE): `ShipGenerator::CreateShip`
at 0x620fd0 and the parts of `WorldManager::CreateShip` (0x6149c0) around it. The earlier implementation was a
reconstruction from community notes with different candidate sets, order and RNG, which among other things let early
enemies reach two shield layers (four bars) in sector one.

All addresses are for the installed 1.6.14 executable. The rules below were read from the executable and then
checked against the original running headless (see "Verification"); nothing here comes from wiki values.

## Inputs

- **Level argument** (`WorldManager+0x834`): the world level, i.e. `StarMap::worldLevel` (the StarMap is embedded in the
  WorldManager at +0x3a4, `StarMap::AdvanceWorldLevel` 0x5edec0 adds one per sector). It is 0 in the first sector.
  The engine passes the 0-based sector column number (`Sector.sectorNumber`).
- **Level**: argument minus one on Easy (`-1` in Easy's first sector). `level` is used signed where it is
  noted below, otherwise clamped at zero (`level0`).
- **Blueprint list**: the ship event's `auto_blueprint` (`SHIPS_REBEL` when absent), a list or a single ship.
- **Seed**: `ShipEvent+0x428`. When it is not zero, CreateShip reseeds the global stream (`srand`) before it draws
  anything. `buildShip(sys, spec, sector, difficulty, seed)` takes this seed; null or 0 continues the stream.
- Difficulty is the global `DAT_00916d34` (0 easy, 1 normal, 2 hard); AE only changes which blueprint lists are used
  (`OVERRIDE_<name>` lists replace `<name>` in `GetBlueprintList` 0x609bb0).

All draws are `random32() % n` (0x6569f0) from `ftl.browser.NativeRandom`.

## Order of a spawn (WorldManager::CreateShip)

1. The CompleteShip is constructed first; its ShipAI constructor (0x625070) draws two defaults (surrender `r%5+1`,
   escape `r%5+1`), before CreateShip reseeds.
2. CreateShip (below).
3. Escape threshold: `RandomAmount::Roll` (0x5caf40) of the event's `<escape>` range plus `worldLevel/2`
   (ShipAI 0x6255d0 stores it and lowers it by one when it equals the ShipAI's initial surrender default);
   escape timer (`<escape timer>`, else 15 s); surrender threshold the same way from `<surrender>` (0x6255b0, which
   lowers the escape threshold by one on a collision). Ships without the elements still get `0 + worldLevel/2`.
   `RandomAmount::Roll`: with a positive `chance`, `random32() % 100 < 100 * chance` gives 0 (one draw, nothing
   else); otherwise min, plus `random32() % (max + 1 - min)` unless max is 0 (no draw). The original stores the bumped
   max (`max + 1`) in the RandomAmount it rolled, but both callers (`WorldManager::CreateChoiceBox1` +24f and
   `CheckLoadEvent` +b2d) hand `CreateShip` (0x6149c0) a fresh copy of the event's ShipEvent (copy constructor
   0x7771c0 copies the two RandomAmounts by value) and destroy it afterwards, and the escape (+0x3f0) and surrender
   (+0x3e4) amounts are separate objects: every roll starts from the event's unbumped max, so the bump is never
   cumulative and there is nothing to persist.

A direct `blueprint=` ship (the tutorial pirate) skips CreateShip: only OnInit runs, with the blueprint's crew.

## CreateShip

1. Reseed from the ShipEvent seed (if not 0).
2. `GetShipBlueprint` (0x60ac50): a name that is not a blueprint list is returned as is (an unknown name gives the
   `DEFAULT` ship). A list gathers its members, a member that is itself a list picks recursively (and draws), keeps the
   members whose `minSector <= level0 <= maxSector` (defaults 0 and INT_MAX) and picks `list[random32() % n]`; when none
   passes, `random32() % size` over all members.
3. On Easy with level == -1 the `ENERGY_SHIELD` augment is removed from the blueprint.
4. Hull: `blueprint hull + level` (signed), 20 at most (`>= 20` gives 20).
5. `ShipManager` constructor and OnInit (0x4b1bf0, 0x4b12c0): reactor = blueprint `maxPower`, every system whose
   `start` is not false is added by `AddSystem` (0x4a8370) at `max(1, power)` capped at the system's maximum (this is why a
   0-power template such as AUTO_ASSAULT's weapons starts at 1), the blueprint's explicit weapons/drones (the first
   `count`) and augments, missiles, drone parts. Weapon and drone slot counts default to **4**. The crew list is cleared
   (`crewCount class` names; the first one becomes the default race).
6. **Crew count**: `(int)((max - min) * min(level0 / 8, 1) + min)`, with `min` the summed `crewCount amount`, `max` the
   `crewCount max` (**8 when absent**). With a minimum of 0 and no crew in the event the ship is automated
   (`ShipManager+0x4d9`).
7. **GenerateSystemMaxes** (0x61f640): for system ids 0..15 that the blueprint's system list names (a system listed
   twice keeps its last element), one draw each, in id order: `random32() % m + (int)(level * 0.125 * (max - start) + start)`
   with `start` the template power and `max` its `max` attribute (-1 when absent, so nothing is ever upgraded);
   `m` is 1 when level < 0, otherwise 2 on Easy or level <= 1, else 3; the value is clamped to `[start, max]`.
   `level` is signed here.
8. Enemy scrap `(int)(25 * (level0 + 1)^1.8 + 150)` (the exe's 1.8f as a double).
9. Budgets: offensive `level0 + 1`, defensive `level + 2` (signed), general `level0 / 3 + 1` (+1 on Hard), chance
   `level0 + 1/2/3` (Easy/Normal/Hard).
10. **Optional systems**: for ids 0..15 the ship lacks and whose maximum is above 0, `random32() % 10 < chance`
    installs the system (`ShipGenerator` 0x61eb60: `AddSystem`, the reactor pays the installed level if the system
    needs power) and costs 1 offensive for weapons, drones and the teleporter, else 2 general (1 on Hard).
11. **Upgrades**, each pick `list[random32() % n]` over a list rebuilt after every pick, one bar per pick
    (0x61eb60: reactor +1 for systems that need power, i.e. everything except piloting, sensors, doors and the battery):
    - offensive (0x620630: weapons 3, drones 4, teleporter 9, artillery 11) while the list is not empty and the budget
      is at least 1;
    - defensive (0x620970: shields 0, engines 1, cloaking 10) while the budget is above 0;
    - general (0x620ca0: all) with `general + leftover offensive + leftover defensive`.
    A list holds every installed system whose power is below its maximum from step 7 (an artillery system once per
    qualifying system; the artillery upgrade picks one of them with an extra draw and a freshly installed artillery
    also gets its extra bar).
12. **Scripted weapons/drones** (`weaponOverride`, `droneOverride`, count default 1): count times pick a name
    (`random32() % remaining`, removed afterwards; a list name resolves with one more draw), add it (nothing happens
    without the system), and sum the power; then raise the system by one bar per missing power (installing it when
    missing, reactor paying).
13. **Procedural weapons** from `<weaponList load=...>` (the explicit `<weapon>` entries are not candidates):
    `GetPossibleWeaponList` (0x61f8f0) keeps the list members that
    - are not a one-power weapon as the first weapon of a system with three or more bars,
    - provide a capability still missing (bit 0 hull damage: damage != 0; bit 1 laser-like: a laser, or a missile whose
      shield piercing is below 4) or there is none missing,
    - use more than a quarter (integer) of the remaining power and fit in it,
    - and do not take the whole system unless it has one bar.
    Slots must be left. Pick `list[random32() % n]`, clear the capabilities it provides, add it, rebuild. Missing names
    in the list are inert (power 0).
14. **Procedural drones** (0x61fda0, `<droneList load=...>`): while a capability is missing only combat drones are
    candidates; no duplicates; the drone must fit (`power <= remaining` and not the whole system of four or more bars).
    A combat drone clears the missing capability. When the strict list runs empty one pass without the duplicate and
    combat rules follows (once per pick).
15. **Crew**: for each entry of the event's `<crew>` (race `type` default random, `prop`, `amount`) or the single default
    entry: the member count is `amount` when prop <= 0, else 1 when `prop * crewCount <= 1`, else `(int)(prop *
    crewCount)`. `ShipGenerator` 0x61ef00 builds the candidates (none when the crew is full at 10 or the ship is
    automated; `random` gives one weighted random crew blueprint, a race gives its blueprint, a crew list one per member),
    then per member one pick draw `random32() % n` with the candidates rebuilt after every pick. Each candidate comes from
    `GetCrewBlueprint` (0x60e020), which draws a fresh name (0x605750, up to 21 attempts). The picked members are created
    by 0x4ae2f0 / 0x4a24a0 / the crew factory 0x4685a0 once the entry's picks are made: a name draw only for
    "random" members (named races already drew theirs while building the candidates), one animation timer draw, then
    one draw per colour layer of the race (no skills are rolled); all through `NativeCrewFactory`;
    the room of member i (restarting for every crew entry) is the room of the i-th system *the ship has* in the list
    pilot, artillery, artillery, shields, weapons, engines, oxygen, sensors, doors, cloaking, medbay, drones, hacking,
    teleporter, artillery (engines and shields swapped on Hard) with no draw, artillery members taking the artillery
    rooms in turn; members beyond that list go to `random32() % room count`. 0x4ae2f0 makes this room draw first,
    before 0x4a24a0 draws the name (random members), the timer and the colours (site 0x4ae705, 14 of 14 beyond-list
    members of native samples; `ShipGenerator.spawnCrew` follows that order). The species of a "random" member is the
    weighted sampler `GetRandomCrew` (site 0x7644f5, weight 6 - rarity over the name-sorted blueprints), one draw per
    candidate build (120 of 120 native members).
16. `InstantPowerShields` (0x49d710), then drone parts: topped up to two per drone, and five when the ship has a drone
    or hacking system and no parts.

## Differences from the old implementation

- Blueprint pick: a single draw over the sector-filtered list instead of an unfiltered shuffle; sector windows apply.
- Budgets drawn in native order with `level0`/`level` as above; the system maxima are drawn first for every
  template, so the per-system ceiling no longer depends on iteration order.
- Candidate lists are rebuilt after every pick and use only the offensive/defensive/general system sets.
- Explicit weapons/drones/augments of the blueprint stay; the weapon filter is the native one (first-weapon rule, quarter
  rule, missing-capability bits); 0-power AUTO systems start at one bar through `AddSystem` only.
- Weapon slots and drone slots default to four (the engine used the layout's hardpoints, up to eight, and three drone
  slots).
- Crew: default maximum 8, native member counts, room priority, and names, colours and timer draws in native order
  through `NativeCrewFactory`.

## Not yet native

- Crew-drone bodies (repair, battle) are created natively through the crew path above at ship creation and draw a name,
  a timer and colours; the engine's drone code creates them without those draws.
- The names in use (`NativeCrewFactory.usedNames`) are the player's crew as last refreshed by event creation plus every
  name generated since; the original refreshes that list whenever it counts crew, which is not mirrored for enemy ships,
  so a name collision retry can differ after several fights.
- The ShipAI constructor's two initial thresholds are drawn from the global stream before the reseed, as natively, but
  the engine's ship AI only acts on thresholds of events that exist.

## Verification

Evidence is private (`.build/gameplay-1to1/model/enemygen`): a Python model of the algorithm, and 840 ships
generated by the original (headless, dev console `LOAD <list|ship> <level>`, which calls CreateShip with a default
ship event; every blueprint list used by events plus ZOLTAN/AUTO/artillery ships; levels 0..9; Easy/Normal/Hard;
AE on and off). For each ship the model was fed the recorded random draws of that CreateShip (from the RNG trace
fixture) and compared with the live ship in memory: blueprint, hull, scrap, reactor, the power of every system,
artillery, weapons and drones in order, drone parts, missiles, crew count, species of named races and the automated
flag. All 839 valid samples agreed and consumed exactly the recorded number of draws.

A second batch drove the same function through the event path (dev console `EVENT <event>`, so CreateShip runs with the
event's real ShipEvent): 171 ships from the pirate, rebel, mantis, rock pirate and slug events at levels 0..8, all three
difficulties, AE on and off. The model was fed the draws after the ship-event seed reseed and the event's crew list,
weapon/drone overrides and escape/surrender ranges; ships, thresholds and the number of draws all agreed (171 of 171).
The draws of crew creation were checked on the same 171 samples by counting them in the trace: one animation timer draw
per created crew member (164 of 171 samples agree exactly; the rest sit beside other timer users in the sampled
window), a name draw per candidate of a named race and per "random" member, extra name draws only where a drawn name
was already in use, and one colour draw per colour layer (two for humans, none for Engi). Crew drone bodies (repair and
battle drones) are created through the same path, with the same three draw kinds, which the drone code does not mirror.
Round 2 (native samples `stores2-*`, kept private): augment removal on Easy is real (ZOLTAN_FIGHTER and ZOLTAN_BOMBER
lose `ENERGY_SHIELD` at CreateShip level argument 0 on Easy, keep it at 1, 2 and on Normal at 0), the species of "random"
crew members (120 of 120), crew start rooms (839 of 839 samples, 14 of 14 members beyond the priority list) and the max
bump (not cumulative, see step 3) are all verified. The augment map lives at `*(0x914f80)` (enemy map at +0, player
map at +0x34), not at 0x914f80 itself.

Regressions are in `GameplayParityEnemyGenChecks.kt` (suite `gameplay-parity`): known answers computed by the model for a
seed, the sector-one shield ceiling for every ship list on every difficulty, sector windows, determinism, the Easy
first-sector Zoltan shield, the threshold roll and the room-before-name draw order of crew members.
