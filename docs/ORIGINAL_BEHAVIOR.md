# Original executable behavior checks

## Gameplay options reference — 2026-10-04

SettingValues construction `0x00597660`, reached from static initialization
`0x0086a100`, defaults `altPause` and `showPaths` to true and `dialogKeys` to1.
Settings save/load `0x0059ee70`/`0x005a1fd0` serialize `altpause`, `showpaths`,
`dialog_keys` and `colorblind`. The latter defaults false. Option selection
`0x005896e0` cycles dialog modes0/1/2: disabled, no delay, brief delay.

ChoiceBox mouse `0x004f6b90` and key input `0x004f6d20` both gate mode2 at
0.3seconds (float constant `0x0087b8c4`). Event numbers use physical1–9,
independent of weapon shortcuts. SetChoices `0x004fe0b0` resets `openTime` only
when reopening a closed box; branches in an open event chain retain it. Render
`0x004fc0f0` advances that UI timer while open. The browser uses elapsed UI time
independent of the render cap, preserving the timer in modal save restoration.

Focus handlers `0x00401620`/`0x00401570` capture and restore the prior explicit
pause. `altPause` permits pausing on focus loss; native fullscreen also forces it.
The browser uses the configurable focus option for its page/canvas and retains
explicit pause independently. Browser fullscreen is not a native graphics mode.
StarMap rendering `0x005fdbf0` reads `showPaths` to draw extra connections from
the hovered beacon; current-beacon connections remain. This is not crew routing.

Private exports/assembly: `.build/reverse/options-reference`. Original game/saves
remain untouched. User deferred colorblind rendering on2026-10-04 and requested
a Work in progress popup instead. Remaining video/language behavior is unaudited. Native presentation equivalence is separate from these facts.

Selected unclear rules were inspected with Ghidra in the owner's installed Windows FTL 1.6.14 executable. These are behavioral notes, not a complete reconstruction. Addresses are specific to that executable. Analysis projects and decompiler output remain private under ignored `runtime/reverse/` and `.build/reverse/`; they are excluded from the application image.

| Rule | Original function address | Browser implementation |
| --- | --- | --- |
| Total score | `0x004461d0` | Truncate the positive total `(base scrap + 10 × unique beacons + 20 × defeated ships) × difficulty multiplier`. Multipliers are 1, 1.25 and 1.5. |
| Sector score and exploration/scrap counters | `0x004461b0`, `0x00445d60`, `0x00445d90`; exploration caller `0x006183a0` | Keep a separate run ledger; revisiting a beacon does not increase exploration. Partial histories from older saves do not enter high scores. |
| Enemy weapon target selection | `0x0062bf10` | Easy selects a system room on 20 of 100 integer rolls; Normal on 33; Hard selects a priority system on 25, any system on another 25, and any room on the remaining 50. Empty pools fall back to wider pools. |
| Hard targeting priorities | `0x0062b200` | Powered shields/weapons/drones; evasion above 25 for piloting/engines; oxygen below 50%; relevant intruders/fire for doors; boarding for teleporter; available cloak; artillery less than four seconds from firing; active battery, clone queue, mind control or hacking. |
| Flagship advance and base countdown | `0x005f4ed0` | Movement alternates with a waiting turn. Arrival at the base starts the counter at one; subsequent turns increment to four, displayed as three/two/one/zero remaining. Departure resets the counter. |
| Base destruction | `0x005eee40` | Counter four ends the run when the player is elsewhere. Reaching the base permits combat instead of an immediate defeat. |
| Final-sector fleet warnings and capture | `0x005f4ed0`, `0x005f5280`, `0x005f4ca0` | Previous warnings become fleet locations. Draw one warning early on the flagship path, then two, with replacement. Exclude the base and captured beacons. Flagship occupation captures a non-base beacon. |

The score arithmetic, target-roll boundaries and countdown were read directly from decompiled control flow and executable constants. Mapping stripped fields to gameplay concepts uses call sites and existing engine/Hyperspace descriptions; it is an interpretation rather than recovered original names. Initial-beacon counting and reward-source exclusions also depend on the engine's event hooks and still need broader original-game comparisons.

These rules are maintained directly in the native Java/Kotlin sources. Regression checks cover targeting pools and RNG restoration, warning/capture persistence, countdown save/reset/defeat, friendly stationary waiting, incoming fleet/flagship encounters, and save restoration. Campaign checks cover all three flagship phases, including saves between phases.

## Desktop input and drone reference — 2026-10-02

Executable SHA-256: `216ab7ec04af5c9c5d09c9c449d409a4cf0501e6c09c8cf252f650d5674d634c`. Hyperspace 1.6.9 signatures were used only for navigation. Several signatures matched unrelated code or failed; table references, constants and call sites were checked in the unmodified 1.6.14 executable before adopting behavior.

| Rule | Verified address / interpretation | Regression |
| --- | --- | --- |
| Global autofire | `WeaponControl::SetAutofiring` at `0x0056ba10` updates weapon factories immediately; enabling it clears a pending armed selection. | Interaction probe plus pending-aim UI checks. |
| Targeting modifier | Firing assignment at `0x0056bd40` passes global autofire XOR the targeting modifier into the selected factory. | Single-shot/autofire targets, beam modifier on commit, global toggle cases. |
| Queued fire after autofire off | Factory setter `0x0040cf70` retains an already assigned target as a queued single shot when disabling autofire. | Interaction probe verifies one shot then target removal. |
| Charge weapons | Factory ready check `0x0040cfa0` requires at least one stored charge, power, ammunition and a queued/auto target; it does not require the maximum number of charges. `0x0040cc10` sets an AI charge goal and must not be interpreted as a player requirement to wait for maximum charge. | Charge, ammunition and target-retention matrix remains required. |
| Weapon swapping | `0x005683f0` updates armed visual state around a swap; `WeaponSystem::SwapWeapons` at `0x00636150` swaps weapon pointers. Release handler `0x0056fab0` uses a seven-pixel click/drag distinction. | Interaction probe verifies instance/charge/chain/power/target ownership and save state; pointer drag tests remain. |
| Anti-personnel pawn | RTTI-confirmed BattleDrone table: max-health getter `0x00757390` returns 150; damage getter `0x007573b0` loads 1.2; fight capability `0x007573c0` is enabled. Repair/man/sabotage capabilities use disabled virtuals. | Drone deployment, combat/power and save tests; original AI priority remains unverified. |
| Repair pawn | RTTI-confirmed RepairDrone max-health getter `0x00758430` returns 25; repair multiplier `0x00758440` is 2. | Drone probe and repair/fire/breach follow-ups. |
| Indoor drone speed | CrewDrone move-speed getter `0x00439930` returns 0.5 before augment effects. | Drone probe verifies 40 px/s against the engine's 80 px/s crew base. |
| Shield overcharger | RTTI-confirmed SuperShieldDrone cooldown getter `0x004217d0`, table at `0x00877180`: 8, 10, 13, 16 and 20 seconds according to existing super-shield layers. Assembly reads the third field of returned shield-power data. | Drone probe checks every layer transition, cap and save/reload. |
| Shield power loss | SuperShieldDrone update at `0x00428300` resets recharge cooldown while powered off, plays charge/activate samples and refreshes cooldown after emitting a shield layer. Glow and flight presentation still require comparison. | Power-off/reset/re-enable and save tests. |

Read-only headless analysis does not open or control FTL. The owner lifted the earlier restriction on original-game interaction on 2026-10-03; no original progress was reset. These implementation interpretations still require controlled interaction comparisons.

## Ship progression reference — 2026-10-02

The same unmodified executable/hash was inspected read-only. Hyperspace labels helped locate candidates; control flow, blueprint strings, callers and difficulty fields were checked before implementing these predicates. The browser stores layout victories separately because the native per-family `_VICTORY` records are not ordinary DAT achievement blueprints.

| Rule | Address / interpretation | Regression |
| --- | --- | --- |
| Fresh availability / layout flags | `ScoreKeeper::GetShipUnlocked` at `0x00445330` reads separate A/B/C flags. Native ship IDs are mapped through blueprint callers; fresh progress leaves Kestrel A available. | Blank profile availability and guarded normal start; failed start preserves current state and mode. |
| Engi A | Callsite `0x0061903c` in `0x006183a0` requires sector index 4 and a ship blueprint containing `PLAYER_SHIP_HARD` before unlocking Engi/native ID 3. | Kestrel sector 4 negative, sector 5 positive; other ship negative. |
| B layouts | Achievement check `0x00431740` counts the first three family achievements and unlocks B once more than one is earned. | One achievement negative; two positive; difficulty upgrade and reload. |
| C layouts | `0x004368e0` checks `ACH_SECTOR_8`, B layout marker 1 and AE enabled (`0x00597740`), excluding native IDs 8/9 (Crystal/Lanius). Unlock wrapper `0x00448410` also rejects their C variants. | A, vanilla and sector 7 negatives; eligible B/AE/sector 8 positive; no Crystal/Lanius C. |
| Victory chain | `0x00431740` applies successors from family victory records: Engi → Federation → Zoltan → Mantis → Slug → Rock → Stealth. Any layout qualifies. | Every predecessor B victory unlocks its successor A; repeated/lower-difficulty wins do not downgrade records. |
| Lanius A / all ships | Unlock method `0x004481b0` counts A flags; reaching five unlocks Lanius unless already processing Lanius. All ten A flags award `ACH_UNLOCK_ALL`. Browser reconciliation uses at least five to handle restored profiles already above the threshold. | Four/five A threshold; full availability award; repeated reconciliation/profile round trip. |
| Crystal victory route | `0x00448480` calls achievement predicate `0x00430350`, requiring A and B victory difficulty fields other than -1 for the eight original non-Crystal/non-Lanius families. C victories do not substitute. | A-only and one-missing-B negative; complete A/B positive. Quest route remains separate. |
| Layout victory records | Setter `0x004324c0` derives family from `_2`/`_3`, selects the layout difficulty field and retains maximum earned difficulty. | Maximum difficulty, deterministic profile save/reload and duplicate completion; console-triggered ordinary victory. |
| Quest unlock dispatch | Event-processing callsites `0x00618b73` and `0x0061b76c` pass the event's unlock identifier into ship unlock logic. | Profile quest flags and family eligibility pass; complete event graphs and native notification presentation remain required. |

Fresh-profile availability, B/C conditions and successor predicates now have focused positive/negative tests. This does not establish all quest-path behavior, automatic achievement earning, notifications or original hangar appearance.

## Credits reference — 2026-10-02

The same executable/hash was inspected read-only; no original-game window was opened. Translated text and backers are loaded from the owner's private DAT at runtime rather than copied into maintained sources.

| Rule | Address / interpretation | Regression |
| --- | --- | --- |
| Main-menu start | `0x005753b0` reads `data/credits.txt`; an empty ship name starts at scroll -70. The victory ship/crew introduction is a separate native path. | Credits started from the main menu; full backer count and ordinary profile/run isolation. |
| Render layout | `0x00572240` uses the dark nebula, translated role/name IDs and three backer columns at 270/640/1010, spaced 40 pixels vertically. Original coordinate constants are retained. | Headless rendering in Chrome/Edge/Firefox and full list traversal; exact font mapping and visual comparison remain outstanding. |
| Scroll / pause | Renderer subtracts speed factor × 3.5 and clamps at -720 for a three-second pause. Browser uses 56 pixels/second based on the existing native 16-unit speed-factor convention, with frame-independent pause accounting. Exact real-time pacing still needs live comparison. | Boundary/pause/resume checks and automatic completion. |
| Completion | `0x005721c0` compares scroll against `floor(backers / 3) × 40 + 4266`. | Traverse the complete list and return to the main menu. Visible Back and semantic toolbar Back preserve fullscreen. |

These checks cover main-menu credits. The native victory introduction, exact font sizes and sound/timing presentation are not established by the headless traversal.

## Achievement milestones and feedback — 2026-10-03

The same unmodified executable/hash was inspected read-only. Run flags are recorded from successful actions and serialized separately from present inventory; an old save without that history cannot earn a restricted-history milestone by assuming no disqualifying actions occurred.

| Rule | Address / interpretation | Regression |
| --- | --- | --- |
| Sector-five conditions | `0x005edec0` checks `store_repair`, `system_upgrade`, `teleported`, `offensive_drone`, `fired_shot`, `reactor_upgrade` and `weapon_upgrade`. Zoltan/Federation awards additionally use the achievement's ship-family restriction. | Positive/negative boundaries, every relevant recorded action after reload, and A/B family eligibility. |
| Sector-eight conditions | `0x004368e0` checks missile/drone use, store purchases, crew losses and `higho2`. Store repair is separate from store purchasing. | Positive/negative cases and persisted flags; real store repair/resource purchase checks. Complete cloning/dismissal/teleport/offensive-drone edge comparisons remain required. |
| Lifetime scrap / defeated ships | `0x00445d90` awards at 10,000 collected scrap; `0x00445ca0` awards at 1,000 defeated ships. Lifetime totals increment independently of current inventory and completed-score retention. | One-below/equal thresholds, profile round trip and repeated run/profile reload without counter increments. Existing arm/store score exemptions remain covered. |
| Lanius oxygen conversion | `0x0049f420` multiplies net oxygen by 100 and uses `0x007458d0` with a 0.5 rounding constant before testing greater than 20. Initial-beacon oxygen is exempt. The persistent `higho2` flag survives subsequent oxygen reduction. | 20/21 percent checks, initial exemption, later reduction and saved flag negatives. Exact floating-point half ties and native timing remain unverified. |
| Popup duration / sound | Tracker constructor `0x0042bdb0` initializes its animation duration to 3.0. Update `0x00430690` selects `achievement` or `unlock` sounds and consumes queued notices after completion. | Display/expiry, duplicate earning suppression, difficulty upgrades and disabled-display earning. |
| Popup placement | Renderer `0x00430990`: original 312×106 box at 970/0; achievement icon centered at 1024/51; heading/name at 1075/20 and 1075/41 with width 195. | Original assets and reference-backed placement. Exact font mapping, difficulty decorations, layout naming and live presentation comparison remain required. |

Upgrade Undo is handled before commitment; autosaves include pending purchases in the saved history because their upgraded levels are also serialized, while retaining live Undo. Notifications are transient and not replayed from profile loading. The popup preference controls display, not earning or ship eligibility.

These implement thirteen additional automatic milestone/lifetime conditions alongside the earlier sector/victory/all-ships conditions. Remaining official combat, crew and ship-specific achievement conditions are still required work.

## Live-state achievement predicates — 2026-10-03

The same unmodified executable/hash was inspected read-only. Several achievement names were compiled as immediate string fragments, so ordinary string references did not locate their earning hooks. The table uses confirmed control flow, including those inlined strings.

| Rule | Address / interpretation | Regression |
| --- | --- | --- |
| Kestrel crew / systems | Crew factory update `0x00467dd0` counts distinct eligible player species and awards above five. Ship update `0x004abd40` checks the installed system vector above ten. The achievement table supplies the family restriction. | Five/six species, wrong family, ten/eleven installed systems; duplicate starting humans do not count as distinct races. |
| Kestrel repair | `0x004abd40` maintains the minimum observed hull and awards when that minimum equals one and current hull equals maximum. Actual jump arrival `0x0049d490` resets the field at offset `0x8ac` to current hull at `0xcc`; `0x004b1760` is ship restart and also resets it. | Two-hull negative; one-hull positive after save/reload; previous-beacon minimum and wrong-family negatives. |
| Engi drones / ion locks | Drone update `0x00639a50` counts powered slots and awards above two. Enemy ship update `0x004abd40` checks at least four locked systems; helper `0x0063bc00` includes ion/cooldown locks and active hacking. | Two/three powered drones, three/four locks and saved lock state. Complete mixed cooldown/hack/white-lock equivalence remains unverified. |
| Zoltan power | `0x004abd40` sums effective power from systems requiring power and checks at least 29. Helper `0x0063b750` includes bonus and battery power. | Actual allocation at 28/29 with battery and Zoltan contributions. |
| Lanius AE systems | `0x004abd40` requires installed Hacking, Mind Control and Battery, then checks their active states. | Real probe landing, mind-control selection, pulse and battery activation; two-system negative and three-system positive. |
| Enemy fire / oxygen | Environment update `0x004a7040` checks every room's burning-cell count. Oxygen earning rejects automated/nonhostile ships and requires rounded net percentage below five. | One unlit cell negative/all lit positive; five/four percent boundary and automated negative. Native half ties and complete hostility-transition cases remain unverified. |
| Slug vision | `0x004a05f0` requires every enemy room visible while player sensors do not provide level-two vision. | Functioning sensors and unseen-room negatives, disabled sensors/full-vision positive. Crew/probe-driven full vision still needs expanded integration coverage. |
| Crystal lockdown | Crew update `0x004aa670` checks at least four enemies in a room on either ship. Room query `0x0048c510` checks the ship's active lockdown entries for that room. | Three/four occupants, inactive/expired negatives, own/enemy ship and room-state save/reload. |

Room lockdown is now tracked separately from individual doors and shared by Crystal abilities and lockdown bombs, so a destroyed door or a room without doors does not erase the room's active status. The existing twelve-second duration is retained; original shard animation/barrier timing and hit-point fidelity remain unverified. These eleven earning hooks do not certify those mechanics or exact native update ordering.

## Travel, crew choices and Crystal detour — 2026-10-03

The same unmodified 1.6.14 executable/hash was inspected read-only. Hyperspace signatures were navigation aids; the following predicates and transitions were checked in the executable. Level fields below use zero-based sector numbering.

| Rule | Address / interpretation | Regression |
| --- | --- | --- |
| Slug nebula travel | World loop `0x0061b930` checks `nebula` at least 30 with world level at most 8. Nebula initializer `0x0041fd00` increments the counter; ion-storm initialization `0x00420220` calls it. | Changed-beacon/revisit counts, 29/30 boundary, storm inclusion and no increment on reload or assigning the same beacon. |
| Federation crew choices | Choice completion `0x0061d590` checks membership in the crew-blue option index vector, increments `blue_alien`, then awards at least four while level is below 5. World location `0x006183a0` corroborates zero-based levels through the sector-five Engi unlock. | Non-crew/ordinary/unavailable/cost-rejected choices, owned crew versus intruders, 3/4 boundary, sector-five positive/sector-six negative and save restoration. |
| Mantis crew victories | `0x0061b930` increments `dead_crew` and awards above 19 while level is below 6. The survivor predicate requires zero owned crew on the player ship and exactly one aboard the defeated enemy. | Real crew-defeat handler, 19/20 history boundary, sector-six positive/sector-seven negative, boarder location and no duplicate after reload. Earlier victories are seeded in the boundary fixture, not played twenty times. |
| Crystal Rock victories | Hull-defeat branch of `0x0061b930` checks blueprint names containing `ROCK_SCOUT`, `ROCK_ASSAULT` or `ROCK_FIGHT`, increments `destroyed_rock` and awards at least ten. Crew defeat does not increment this counter. | Crew-kill and non-Rock hull negatives, 9/10 boundary with actual hull destruction and save/reload. Earlier victories are seeded. Immediate departure during destruction still needs comparison. |
| Stealth dangerous environments | Sector-eight predicate `0x004368e0` requires `env_danger` false. Initializers `0x00418a40`, `0x00418ae0`, `0x00418f70` and `0x00420220` set the flag; their callers identify asteroid, sun, pulsar and ion-storm environments. ASB uses separate state. | Safe sector-eight positive, each dangerous environment negative after reload, and exclusion of older saves without complete environment history. Initializer-to-environment mapping remains an interpretation from call sites. |
| Crystal event operation | Parser `0x005e52c0` recognizes `secretSector`. GUI loop `0x00536770` consumes the pending warp; `StartSecretSector` at `0x005ebbd0` selects a separate secret-sector pointer, marks secret mode and awards `ACH_ROCK_CRYSTAL`. | Official DAT operation dispatch, saving an open event before Continue, free transition and Rock achievement. |
| Separate secret map and level | Map generation `0x005f0ef0` creates separate `CRYSTAL_HOME` information. World location check `0x00619fc0` invokes new-sector generation `0x005f8ca0` without advancing the normal level for the initial warp. | Same-level Crystal map, retained ordinary outbound links, secret origin identity and visited references after leaving and restoring. Exact exit selection and geometry remain unverified. |
| Secret save state | Star-map save `0x005ed580` retains current level and the secret flag. Load `0x005fb9a0` selects the secret pointer and restores its level before generation. | Saves during entry and at the hidden unlock quest, then reload/arrival/quest completion and return to the ordinary route. |

The supplied DAT's `START_BEACON_CRYSTAL` creates the `CRYSTAL_UNLOCK` quest, which grants ship ID 8. Arrival state now persists pending beacon/elite events and fly-in time, preventing a reload during arrival from dropping the quest. These fixtures cover hidden-sector entry and its unlock quest, not the complete preceding stasis-pod/Ruwen/portal route or native transition visuals, badge text and timing.

Other systems have not all been reverse engineered. Crystal barrier hit points, some ASB timing/visuals and remaining achievement conditions still need verification. Passing the browser diagnostics demonstrates functioning transitions and persistence, not complete original-game balance or compatibility.

## Combat destruction achievements (candidate implementation)

These predicates were inspected in the same hashed 1.6.14 executable. Private
exports are under `.build/reverse/combat-reference/`. Browser fixtures are in
`BrowserCombatAchievementDiagnostics.kt`; the TeaVM build, focused three-browser
achievement probe and affected broad regressions pass. Native input/timing
comparison remains pending.

| Condition | Native evidence | Required regression |
| --- | --- | --- |
| Volley history | Factory `0x0040dc80` increments offset `0x61c` when emitting the first projectile of a volley. Setter `0x0040ccc0` is called by weapon-system target-ship setters `0x0056bcc0` and `0x0056d120`: changing the encounter target resets the counter; individually aiming a bomb at one's own ship does not. | Pending launch, mid-burst and active-beam reload, cancelled launch, weapon identity after reorder, enemy/self bombs and new encounter. |
| One Volley | Destruction branch `0x0061b930`: each current normal weapon has at most one volley, enemy normal weapons have none, total artillery volleys below two, and pre-igniter present. | Second volley, enemy fire, missing augment, reorder/reload. |
| Only Drones | Same branch: zero normal and artillery volleys. It does not separately require a drone to have fired. | Normal firing disqualifies; cancellation before launch does not. |
| Fed Patience | Same branch: artillery fired, no normal firing, and no currently powered combat/boarding drone. There is no additional player-hull damage comparison in this branch, despite the DAT description. | Artillery persistence, normal firing, powered offensive drones, description discrepancy. |
| Rock Missiles | Same branch: enemy has a defense drone whose target type is not anti-drone; no fired normal non-missile weapon and no powered offensive drone. Charge missile is explicitly exempted from the non-missile check. Drone power is not tested on the enemy side. | Missiles, bombs/lasers, anti-drone exclusion, powered combat drone. |
| Energy Shields | Same branch: positive current player super-shield. | One remaining point, zero points, family eligibility, destruction before animation completion. |
| Stealth Destroy | Getter `0x0049c4e0` reads inverse of damaged-uncloaked flag; ShipManager loop `0x004abd40` marks it while the opponent is damaged and owner uncloaked, clearing it when opponent is at full health. | Saved cloak, damage before/across cloaks, full enemy repair. |

The expanded achievement probe passes in installed Chrome, installed Edge and
Firefox. The fixtures allocate normal weapon power through the real power manager
and run actual post-jump cleanup before replacing an encounter target. Full native
input-assignment and live timing comparisons remain unfinished.
These notes do not establish full combat or achievement equivalence.

## Projectile, beam and invasion achievements (in progress)

The same hashed unmodified executable supplies these predicates. Some functions
were missing from the existing Ghidra analysis; their instruction boundaries were
recovered from the native prologues in a read-only project. Private evidence is in
`.build/reverse/remaining-combat/` and `.build/reverse/achievement-details/`.
The focused fixtures pass in installed Chrome, installed Edge and Firefox.
Affected interaction, mechanics, drone, progression, unlock, AE, combat, layout,
encounter and accelerated-campaign regression modes also pass.

| Condition | Native evidence | Browser regression |
| --- | --- | --- |
| Bad Dodging | `0x004a00d0` and its secondary-interface thunk `0x004a0350` increment failed dodges only for the player, with functioning piloting/engines and engine power above seven. A successful dodge or ineligible roll clears the streak. | Fifth failure, save after four, successful evasion and reduced engine power. |
| Stealth Avoid | DamageArea `0x004a6100` adds the incoming hull damage to the cloak counter on an evaded attack while cloaked. Ship loop `0x004abd40` clears it while uncloaked and earns at nine. | Actual projectile dodge, shield/room processing only once, save during cloak, separate cloaks and uncloaked misses. |
| Slice and Dice | DamageBeam `0x004a9d40` timestamps room entry; ship loop `0x004abd40` requires every room's nonnegative timestamp to be strictly less than five seconds old. Constant `0x00879e80` is 5.0. | Partial coverage/reload, five-second expiry, blocked beam and new encounter. |
| Slug Bio | DamageBeam `0x004a9d40` increments crew kills by beam identity; three kills qualify when the supplied damage has zero hull and positive personnel damage. It does not compare the blueprint name. | Real beam, saved active shot after two kills, separate shots, two kills and family eligibility. |
| Crystal Shard | DamageArea `0x004a6100` requires enemy hull to cross from positive to zero and the damage's Crystal shard flag. | Saved Vengeance projectile, nonlethal shard and unrelated hull destruction. |
| Invade Ship | World loop `0x0061b930` checks a previously hostile encounter ending without escape, with no live non-drone/non-intruder crew physically at home. Event application `0x0061b080` also checks hostility becoming false. Getter `0x0049c180` uses current crew allegiance. | Teleport/reload/crew victory, hull victory and a friendly crewmember remaining home. |

These are earning predicates, not proof that all projectile/beam damage, native
simulation timing or mind-control interactions have full parity.

The saved Crystal-shard fixture exposed a general restoration issue: an exact
zero-distance projectile path could divide by zero during a zero-time ship
update. Such updates now leave projectile motion and collision resolution until
simulation resumes. The fixture checks finite coordinates, unchanged hull on
load and the subsequent killing blow.

## Crew achievement predicates — focused verification

The same hashed, unmodified 1.6.14 executable was inspected read-only. Private
exports remain under `.build/reverse/crew-predicate-details/` and
`.build/reverse/boarding-counter-init/`. No original game was launched.

| Condition | Native evidence | Passing browser regression |
| --- | --- | --- |
| Boarding Drone | Crew constructor `0x00485df0` reads zero from `0x00879194` for the pawn's goal counter. Combat loop `0x00482a80` checks at least four previous targets before incrementing on a crew kill. Sabotage loop `0x0047e850` also increments when the sabotaged system is completely broken. Ship assignment `0x0047bb70` and restart `0x0047b660` do not clear the goal. | One pawn, four/five crew kills, completed sabotage plus four kills, separate pawns, power/save restoration and unknown older history. This differs from the DAT's four-crew description. |
| Mantis Slaughter | Location initialization `0x006183a0` snapshots hull, aggregate player crew count and the killed-crew flag. World loop `0x0061b930` requires five additional deaths and current hull/count equal to those snapshots. Death-animation setup `0x00482420` increments enemy deaths once and excludes drones. Factory `0x00467dd0` uses eligibility predicate `0x00755630`, including clone-ready crew; physical boarding and current allegiance do not change ownership. | Four/five deaths, hull damage and repair back to equality, lost/replacement/additional crew, enemy replacement versus new beacon, pending/started death restoration, family and unknown-history exclusions. |
| Rock Fire | Direct health modification `0x0047a2b0` accepts a burning enemy-owned non-drone aboard the enemy ship, fighting a player-owned opponent. Fire can supply the killing blow. Combat loop `0x00482a80` also accepts a player-owned attacker aboard the enemy ship killing a burning non-drone. Family eligibility supplies the Rock restriction; there is no attacker species test. | Real burning combat, fire killing blow, saved fight, nonburning/unopposed deaths, home-ship/drone victims and wrong family. |

The focused fixtures pass in installed Chrome, installed Edge and Firefox;
the eleven affected gameplay/layout/encounter/campaign modes also pass. Coverage
includes clone queues, drone deaths, power toggles and home-ship exclusions.
These checks do not certify all crew combat, cloning, fire, AI or native timing.

## Crew records — native reference and candidate verification

Read-only inspection of the same executable confirms five per-crew records.
`ScoreKeeper::UpdateCrewStat` (`0x00445130`) replaces a record only on strict
improvement, retaining name, species and gender at that moment. A tied count or
later rename leaves the old record holder unchanged. The crew loop
(`0x00482a80`) supplies completed repairs, combat crew kills, piloted evasions,
jumps survived and distinct green/yellow skill milestones.

Repair loop `0x0047e850` credits only a successful repair return. Base
`Repairable::PartialRepair` (`0x00471bf0`, Fire vtable `0x0090c740`, slot `0x20`)
compares the raw final value to exactly zero, rather than clamping an overshoot.
Ordinary fire/breach completion therefore does not produce a system-bar repair
credit. The exact-zero edge and native timestep behavior remain unverified.
Player dodge `0x004a00d0` emits event 7 outside cloaking; active owned pilots
increment evasion history. Actual arrival `0x0049d490` calls crew jump
`0x00477b70` on physical crew. Skill gain `0x00479b90` ORs milestone flags,
so cloning and retraining a previously reached level do not duplicate it.

Durable crew/profile counters and a functional Statistics tab are implemented.
Focused Chrome/Edge/Firefox action, save, ownership, tie, rename and cloning checks
pass, including ordinary fire/breach negatives and full counter XML comparison.
All 24 menu/browser checks and eleven affected regression modes pass; deployment
preserves account/security/save/snapshot/key hashes. Original panel assets,
portraits and run/lifetime metrics are implemented in the subsequent statistics
batch; exhaustive timing/eligibility equivalence remains unfinished. Private exports are
under `.build/reverse/crew-record-reference/`, `repair-record-reference/` and
`stats-record-reference/`; decompiler output is excluded from Git.

## Run/lifetime statistics and split panels

The same unmodified executable was inspected read-only. ScoreKeeper Reset
`0x00445e80` clears run metrics and increments Games played unless the native
tutorial is running (`0x00410910`). Starting a run counts even when it is later
abandoned; loading or completing that existing run does not add another game.
AddStat `0x00445c60` adds run/lifetime/last values. AddCrew `0x00445e50` is called
for each starting crew member by NewGame `0x0061a790` and on positive event
recruitment by `0x00615930`. Cloning/teleporting existing identities are not new
recruits. Save `0x00447690` updates four strict run maxima only during the
completed-record path, protected by its completion flag. Restart/abandon record
submission still needs its complete caller audit.

Overall high scores retain five entries (`0x00446220`); the per-layout wrapper
`0x00446890` trims each list to four. Browser records retain the union of both
lists and persist reached sector. Older unknown crew history cannot replace a
complete recruitment record; existing older scores keep their known arithmetic.

Initialization `0x0044eb10` and render `0x0044ab10` use original left/right
panels independently. Left selects Top scores or Ship best; right selects Stats
or Achievements. Close occupies `(1086,627)` with size `138×46`. Browser panels
use those assets/coordinates, localized labels, crew portraits, hull thumbnails,
reached sector, ship/layout/list navigation and nested semantic cancellation.
Female humans share the human portrait frame coordinates and use the separate
female sheet; no `female_portrait` animation exists. Statistics display metadata
is independent of next-run AE preference, so AE-only scores remain viewable.

Focused fixtures cover native run counters/maxima, actual clone/teleport/load
exclusions, save/completion idempotence, all crew portraits and unchanged UI
profile/RNG. Populated Chrome/Edge/Firefox menu checks cover independent tabs,
the nested ship list and fullscreen Close. Exact native typography, icon
decorations, custom ship naming and exhaustive timing remain unverified.

## Desktop tutorial reference in progress

The official DAT has 33 steps in its PC order, distinct from the iPad path.
The tutorial tests track each identifier, order and Continue availability.
Read-only native references identify Init `0x004141b0`, Start `0x004145b0`,
CheckState `0x004149e0`, Advance `0x00415720`, loop `0x004161d0` and render
`0x00416930`. Start resets its current step and running/quit flags. The loop
tests action completion, advances, and maintains tutorial-specific window state;
Continue availability comes from each step's metadata. Rendering changes panel
placement during map/inventory/upgrades steps. Private output is under
`.build/reverse/tutorial-reference/`. State effects, live gates, isolated instruction controls and encounter events
now have focused regression fixtures; complete native presentation/human acceptance
remain unfinished. A typed data loader reads the PC order/instructions/Continue flags/arrows
from the private DAT. Focused Chrome/Edge/Firefox and launcher selection/export
checks pass, including unchanged ordinary run/profile/mode/RNG. Data loading is separate from action coverage. The inventory now records the
implemented steps as unverified for complete parity, with missing weapon flashing
marked incorrect for COMBAT_1/COMBAT_5.

Native action/helper and application dispatch audits now distinguish fifteen
automatic gates from four conditions that enable Continue. A null enemy pointer
at ShipManager `0x488` is the combat-completion gate, rather than a hull value.
Application dispatch `0x00402350` starts the tutorial manager before its world
and avoids ordinary run saving on tutorial exit. Stop `0x00410af0` releases its
window/input restrictions. The detailed paraphrased gate matrix and remaining
setup/effect work are in [tutorial implementation notes](TUTORIAL.md); private
exports are in `.build/reverse/tutorial-{gate,world}-reference/`.

Internal browser tutorial sessions now isolate profile/run writes, restore the
original profile/RNG/AE and exclude tutorial starts from Games played. Focused
three-browser fixtures cover Continue preservation, earnings, autosave/delete,
combat restoration, held-input exit, restart, initialization failure and shutdown.
The Tutorial menu remains inactive pending complete presentation and played acceptance.

## Tutorial entry effects and event lookup (2026-10-04)

Read-only exports from the same verified executable confirm that Advance
`0x00415720` applies effects on entering the next lesson. Engine power is
force-decreased twice at POWER_SYSTEM_1. DAMAGE_SYSTEM_1 supplies hull=2,
zero extra system/personnel/ion/fire/breach/stun damage, and friendly fire;
DamageArea `0x004a6100` and crew helper `0x0049cf80` retain the base two system
points and 30 crew HP. DAMAGE_SYSTEM_2 calls crew factory `0x004aea60` with
localized names, human race, room 1, init=false and male=true then false.
The setup factory does not report career acquisition. FIRE_PC_1 calls
`0x004a1ef0`, whose helper `0x0076cc20` samples two tiles with replacement and
refreshes an existing fire on a duplicate.

GetEvent `0x00410a00` consumes pending flag +0x1c4 and creates TUTORIAL_MISSILE
once. World loop `0x0061b930` polls it while tutorial mode is active. The
COMBAT_4 call to `0x0052ce20(0,0)` clears pause flags; it does not inject the
event. AllowUpgrades `0x00411440` permits ship windows at INVENTORY_1 or COMBAT_5
and outside tutorial mode. The pause field setter is now resolved below. The subsequent
armament renderer audit below resolves the CombatControl+0x98 reader.

The four non-event effects now pass actual transition, repair/venting,
crew/damage/fire, restoration and isolation fixtures in Chrome, Edge and
Firefox. Full native encounter/navigation/presentation remains unfinished.
Private exports are under `.build/reverse/tutorial-{setup,navigation,
event-caller,pause}-reference/`; no decompiler text belongs in Git.

The existing private travel/secret exports further corroborate two tutorial paths:
CommandGui loop `0x00536770` uses AllowJumping/AllowUpgrades to enable HUD buttons;
starting-beacon constructor `0x005f9e00` chooses TUTORIAL_START while tutorial mode
is active. Browser button and shared hotkey opening restrictions now pass focused
Chrome/Edge/Firefox fixtures; native map/start/filler events and encounter rewards now have three-browser coverage.
The delegate's claim that every non-start beacon is replaced exceeds the confirmed
map branch (only event-null locations are assigned TUTORIAL_ENEMY).

Current-executable armament renderer `0x0056fb70`, called by weapon renderer
`0x0056cfd0`, compares the one-based box index against ArmamentControl+0x84 and
passes its +0x80 enabled flag only to the matching box's render callback. The
embedded weapon control maps these to CombatControl+0x98/+0x94. Native tutorial
loop writes slot 1 at COMBAT_1 and slot 3 at COMBAT_5, then clears both on other
steps/Stop. These are weapon-box flashing fields; this reader does not restrict
target rooms. Browser flashing and exact pulse/color comparison remain pending.
Private exports: `.build/reverse/tutorial-flash-reference/`. A broad WeaponLoop
signature matched an unrelated function and was not used as reference authority.

## Tutorial explicit pause field (2026-10-04)

Constructor caller `0x008678f5` initializes TutorialManager at `0x00913340`;
Start and loop callers use the same address. Its `+0x16c` field therefore aliases
`0x009134ac`. SetPause `0x0052ce20` writes that address from its explicit pause
argument, alongside CommandGui's `+0x2f6c`/`+0x2f6d` flags. This resolves the
COMBAT_0 reader in CheckState: the existing browser observation of explicit player
pause is appropriate; modal pause alone does not satisfy the lesson. A separate
`+0x16c=1` store at `0x005df6d9` belongs to event creation and was excluded.
Private evidence: `.build/reverse/tutorial-remaining-stores-reference/` and
`tutorial-pause-box-reference/`. No original game/save was changed.

## Tutorial pulse and authored ship equipment (2026-10-04)

WorldManager::CreateShip `0x006149c0` distinguishes direct blueprint names from
procedural auto-blueprints. The direct branch gets a blueprint (`0x0060ac50`) and
loads CompleteShip (`0x004081e0`), which initializes ShipManager (`0x004b1bf0`);
the procedural branch invokes ShipGenerator (`0x00620fd0`). This corroborates
preserving the tutorial pirate's authored LASER_BURST_1 rather than clearing its
equipment for procedural selection. It does not establish all direct-ship level,
crew override or ordinary generator behavior.

WeaponBoxRender `0x00509ee0` calls ArmamentBox rendering `0x0050ad10`. Flashing
updates a shared 0.75-second looping tracker before the empty-box check, then
paints required power indicators in red at the tracker's alpha, under ordinary
power/status colors. GetAlphaLevel `0x005bc000` is a triangular 0 → 1 → 0 curve;
Update `0x005ba8e0` uses the UI delta converted to seconds. Tutorial rendering
`0x00416930` also uses this curve for its white-tinted arrow2 overlay over the
base arrow, both 164×38. Browser cosmetic clocks/power rendering and fixtures
pass nine focused modes per Chrome/Edge/Firefox and packaged release checks. Frame-edge rounding, typography
and complete native visual comparison remain open. Private references:
`.build/reverse/tutorial-power-flash-reference/`.

## Tutorial font metrics (2026-10-04)

Instruction measure/print in `0x00416930` uses font ID12, wrapping width308
and text offset18,15. Resource font registration `0x005c38d0` maps
JustinFont11Bold to12 and JustinFont12Bold to13; filename comparison
`0x00787990` and lookup `0x005c3690` corroborate this distinction.
SIL font creation `0x00648f10` installs metrics callback `0x006487c0`,
which scales the file's nominal height and baseline. At the original 19px
nominal size, this gives a 12px baseline. Registration adds5 after
`0x005949b0` subtracts4 from line height, yielding 20px line spacing.
The native wrapped printer `0x00596150` adds ascent to its initial Y and
line spacing between lines. Browser instructions previously used 12Bold
with a 19px baseline/spacing. The candidate corrects all three values;
all 33-panel geometry, nine focused modes per browser and packaged release checks pass.

Continue initialization `0x004141b0` supplies rectangle23,7,170,32 and
font63 to TextButton initialization `0x00590740`. SetFont `0x005905c0`
stores63 and a4px centering adjustment. Font registration maps63 to HL2
at3× (24px baseline/38px line spacing); TextButton render `0x00591f00`
centers the label with a25px baseline from the32px hitbox top and reduces
to font62/2× if too wide. Its218×54 texture has offset−23,−7, distinct
from the hitbox. The candidate implements these geometry/font rules and
removes an invented hover brightness multiplier. Exact hover colors/sound
and native word-wrap equivalence remain unverified.

Native panel minimum is212px; measured text adds22px. The browser reserves
an additional row for its requested visible Exit action. Exact word wrapping,
complete native presentation remain under comparison.
Private reference: `.build/reverse/tutorial-font-reference/`. No original
game launch or save modification was required.

## Native confirmation-window reference — 2026-10-04

Read-only Ghidra export on an ephemeral project copy locates confirmation text
setup at `0x0055b240`, size calculation at `0x0055b6a0`, positioning at
`0x0055cc30`, Open at `0x0055cd40`, Render at `0x0055bc60` and mouse click
at `0x0055b110` (corroborated by vtable `0x0090b1c0`). Default labels use `confirm_yes` / `confirm_no` from the supplied
translations. The native escape-menu initializer `0x0050f610` prepares
`confirm_end_game` with minimum width300 and automatic centering. Menu click
dispatch `0x00511d10` queues destructive choices until a positive answer;
ordinary Continue and Save+Quit dispatch directly. Command GUI initialization
`0x00538710` also prepares `confirm_leave_crew` with minimum width300.

Hyperspace1.6.9 signatures are navigation aids. Some signatures produce ambiguous
or semantically incorrect matches in1.6.14; those method names are not reference
proof. Exact action mapping, abandoned-run statistics, stranded-crew eligibility
and dialog keyboard/render behavior still require investigation and regressions
before the missing browser warning actions are considered complete. Private
exports: `.build/reverse/menu-warning-reference/`. Original game and saves
remain untouched.

The title initializer/language setup `0x004ca460` prepares `confirm_new_game`
with minimum width380 and `button_confirm`/`button_cancel` labels. Its click
handler `0x004d48c0` checks for Continue before opening this warning; cancellation
retains the saved run. Confirmation deletes Continue via `0x005af260` before
opening the ship builder. A fresh profile enters the hangar directly.

Both CommandGui keyboard `0x00534820` and pointer `0x00535a20` paths check
FTL readiness before the warning. ShipManager helper `0x0049c0b0` counts
player-owned crew physically on the enemy: original ship ID0, not dead and not
a drone (CrewMember virtual offsets +0x88/+0x30). IsDead `0x00755800` reads
the native death flag. Temporary mind-control allegiance and a clone bay do
not bypass this living-crew predicate. Yes opens the map; it does not jump yet.

Application dispatch `0x00402350` submits the current run on restart/hangar via
ScoreKeeper `0x00447690` with its end-run argument enabled. The keeper has a
submission guard and omits zero scores. Main Menu/Save+Quit preserve the run;
title New Game clears Continue without this submission call. Confirmation
render `0x0055bc60` uses `img/confirm_window.png`, HL1 at2x (ID52), HL2 at2x
buttons (ID62), 24px body line spacing and a14px button gap. The maintained
ConfirmationWindow uses these source-verified paths. Candidate fixtures and
exact visual/input acceptance are tracked separately.
