"""Render the Hyperspace compatibility matrix for docs/HYPERSPACE.md.

Usage: python -I gen-hs-catalogue.py <FTL-Hyperspace checkout (1b05a07)> <out.md> [<merged MV data dir>]

Every active hook (HOOK_METHOD/_PRIORITY, HOOK_STATIC/_PRIORITY, HOOK_GLOBAL/_PRIORITY outside comments) of HS's
src/ is listed under its file; [FILES] gives each file's features with gate, MV use, status, owner and evidence.
The script fails when a file with hooks has no [FILES] entry (or an entry has no hooks), so the matrix stays
complete when HS is updated. With the merged data dir (the F3 fixture's data/*.xml), "MV uses" counts the elements
named in a row in Multiverse 5.5.1's merged files (comments removed).

Status: verified = compared with the original game (FTL 1.6.9 + HS 1.23.2 in the oracle container,
docs/HS_ORACLE.md); implemented = ported, checked by a probe, a unit check or against the XML/source, not against
the original; partial = parts ported (the evidence says which are missing); missing = not ported;
n/a = no equivalent in the browser engine. Reports: `.build/hs/reports/<workstream>.md` (coordinator worktree).
"""
import glob, os, re, sys
from collections import Counter

hs, out = sys.argv[1:3]
data = sys.argv[3] if len(sys.argv) > 3 else None

# file -> [(feature, gate, MV uses (list of element names or text), status, owner, evidence)]
FILES = {
 'Global.cpp': [('CApp pointer capture; WorldManager::LoadGame at speed factor 0 with loadingGame set', 'always', 'every run', 'n/a', 'core', 'no equivalent state')],
 'features/crash-detection/CrashDetectionHooks.cpp': [('Crash and hang detection', 'platform', '-', 'n/a', '-', '-')],
 'features/cursor-hide/CursorHide.cpp': [('Hide the OS cursor on blur', 'platform', '-', 'n/a', '-', '-')],
 'features/discord/DiscordIntegration.cpp': [('Discord rich presence', 'platform', ['discord'], 'n/a', '-', 'browser')],
 'features/freeze-watchdog/FreezeWatchdogHooks.cpp': [('Freeze watchdog while loading', 'platform', '-', 'n/a', '-', '-')],
 'features/sdl-test/SdlTestHooks.cpp': [('SDL test harness', 'platform', '-', 'n/a', '-', '-')],
 'game/CustomBoss.cpp': [('Flagship crew per stage, surge drones and barrages, event superDrones/superBarrage', 'content', ['boss'], 'partial', 'ships',
   'hsShipsProbe boss part on 3 difficulties against the XML (not the original); barrage beams/bombs, BossShip::GetEvent status clearing and the boss system box missing (ships.md)')],
 'game/Debug/Debugging.cpp': [('Logging of event and ship creation, crash catcher', 'platform', '-', 'n/a', '-', '-')],
 'game/Gameplay/AI/TeleporterBugFixes.cpp': [
   ('GetTeleportCommand/InitiateTeleport rewrites: neutral ships stop teleporting, free-slot limit, TELEPORT_HEAL', 'always', 'every run', 'missing', 'systems', 'no workstream report covers it'),
   ('Hacked teleporter works from every room, ignores cloak', 'option teleporterHackFix', 'off', 'missing', 'systems', '-'),
   ('Crew recall through Zoltan bypass', 'option energyBypassTeleportRecall', 'off', 'missing', 'systems', '-')],
 'game/Gameplay/AugmentValues.cpp': [('Explosive Replicator roll per spent missile (ProjectileFactory::SpendMissiles)', 'always', 'every run', 'implemented', 'weapons',
   'weapons.md: replicator after freeMissileChance; probe units rate 34.4% of 20000 (KERNEL_1)')],
 'game/Gameplay/Balance.cpp': [
   ('Hacking drone explodes when hacking loses power before landing', 'option hackingDroneFix (default on)', 'on', 'implemented', 'systems', 'systems.md: source reading only'),
   ('Repair drones destroyed before a jump (no Drone Recovery pickup)', 'option repairDroneRecoveryFix (default on)', 'on', 'implemented', 'weapons', 'weapons.md: not run-tested'),
   ('Player ion boarder drones on their own timer (ionDelay/ionDelayInitial)', 'option controllableIonDroneFix', 'on (10/6)', 'implemented', 'weapons',
    'weapons.md round 2: first charge 6.00 s after landing (probe); later charges and the door rule not against the original'),
   ('Ion on a hacked system extends the lock', 'option hackingIonFix', 'off', 'missing', 'systems', 'systems.md: off in MV')],
 'game/Gameplay/CustomAchievements.cpp': [('Custom and variable achievements, stats pages, ship achievements', 'content', ['achievement'], 'partial', 'ships',
   'hsShipsProbe: 274 achievements, 12 pages, unlock + profile round trip (against the XML); unlock popups missing (ships.md)')],
 'game/Gameplay/CustomBackgroundObject.cpp': [('Background objects in space', 'content', ['backgroundObject'], 'missing', 'render', 'render.md, events.md; reported in the capability scan')],
 'game/Gameplay/CustomDamage.cpp': [('Custom damage fields (noSys/noPers, crew spawns, stat boosts, erosion on hit), Lua damage/projectile callbacks, collision and SaveState rewrites',
   'content/lua; rewrites always', ['statBoosts', 'erosion'], 'partial', 'weapons',
   'verified: DAMAGE_AREA/_HIT Damage fields = oracle (LASER_BURST_1, mv-seed-12345); hit effects through the real handlers (probe round2); tile crew spawn and beam shield erosion missing; rewrites not diffed (weapons.md)')],
 'game/Gameplay/CustomDrones.cpp': [
   ('Custom drones: crew drones of any race (54 in MV), flak/missile defence and combat drones', 'content', ['droneBlueprint', 'drones'], 'verified', 'weapons',
    'drone pawn stats 14/14 species = oracle ship dumps (tools/drone-stats.py); 175-178/178 deploy (probe); controllable drones, droneAI, tooltipName missing (weapons.md)'),
   ('Defense drone range by ship size; combat drones always targetable', 'option defenseDroneFix', 'on', 'implemented', 'weapons', 'probe units: range rule edges'),
   ('Combat drone hop direction fix', 'option combatDroneRapidFireFix', 'off', 'implemented', 'weapons', 'probe round2 with the option forced on: 18 hops in 90-270 degrees'),
   ('Drone select hotkeys; drone crew stations saved', 'options droneSelectHotkeys, droneSaveStations', 'on', 'missing', 'ui', '-')],
 'game/Gameplay/CustomFleetShips.cpp': [('Custom background fleets (customFleet, fleetDef) and their save', 'content', ['customFleet', 'fleetDef'], 'partial', 'events',
   'events.md round 2: random32 draws at CreateLocation/UpdateLocation and drawing; stencil shadow and draw order among same-priority hooks missing')],
 'game/Gameplay/CustomScoreKeeper.cpp': [('Stats page ship-best panel for custom ships, profile extension', 'content', ['customShip'], 'implemented', 'ships', 'ships.md: not against the original')],
 'game/Gameplay/CustomTutorial.cpp': [('The vanilla tutorial does nothing', 'option disableDefaultTutorial', 'on', 'missing', 'core', 'rule in the capability report')],
 'game/Gameplay/CustomWeapons.cpp': [
   ('Weapon blueprint extensions: shotLimit, freeMissileChance, fireTime, accuracyMod, iconReplace, simultaneousFire, pinpoint beams, mini projectiles, descriptions', 'content',
    ['shotLimit', 'freeMissileChance', 'fireTime', 'iconReplace', 'simultaneousFire'], 'implemented', 'weapons',
    'probe: 790/790 MV weapons parse, power and fire; shotLimit, fireTime, pinpoint (20 seeds), simultaneousFire, negative cooldown (weapons.md); hit statistics not against the original'),
   ('Pre-igniter charges player chargers fully', 'option preIgniteChargers', 'on', 'implemented', 'weapons', 'probe units'),
   ('Player artillery aimed by hand', 'option targetableArtillery / ARTILLERY_ORDER', ['targetableArtillery'], 'implemented', 'systems', 'systems.md round 2: probe on FED and FED_3; aim hotkeys missing')],
 'game/Gameplay/DroneVTable.cpp': [('Crew drone virtual table (move, health, man, fight, repair, sabotage, suffocation, fire)', 'content', ['droneBlueprint'], 'verified', 'weapons', 'pawn stats = oracle (see CustomDrones)')],
 'game/Gameplay/ProjectileVTable.cpp': [('Projectile render layer override', 'content/lua', '-', 'missing', 'weapons', 'no port found')],
 'game/Gameplay/Projectile_Extend.cpp': [('Projectile extension data', 'content/lua', '-', 'missing', 'weapons', 'no port found (Lua extend members partly via weapons adapters)')],
 'game/Gameplay/ShipUnlocks.cpp': [('Custom ship unlocks and victory unlocks, profile extension, unlock popups', 'content', ['unlockCustomShip', 'victory'], 'implemented', 'ships',
   'hsShipsProbe hangar part: sector unlock with a profile round trip (against the XML); popups missing')],
 'game/Gameplay/SpaceId.cpp': [('Space id counter from 100, saved with the run, reset in the hangar', 'always', 'every run', 'partial', 'statcrew',
   'statcrew.md round 2: counter from 100 for crew; not saved; drones and projectiles do not draw from it')],
 'game/Gameplay/World/Blueprint_Extend.cpp': [('Blueprint description extension', 'content', '-', 'missing', 'ui', 'no port found')],
 'game/Gameplay/World/CrewAbilities.cpp': [('Crew powers tick while paused', 'content', ['power'], 'implemented', 'statcrew', 'statcrew.md')],
 'game/Gameplay/World/CrewNames.cpp': [('GetUnusedCrewName: one random32() seeds a std::mt19937, names drawn with mt() >> 1', 'always', 'every run', 'implemented', 'core',
   'NativeCrewFactory.newName, Mt19937 against the standard vector; events f38fe72 crew name stream; names are not deterministic in the oracle (oracle.md)')],
 'game/Gameplay/World/CustomEvents.cpp': [
   ('Custom events: variables, req, loadEvent/loadEventList, queues, aliases, revisit, choice types, beacon types, quests, sector quests, event actions', 'content',
    ['variable', 'loadEvent', 'req', 'queueEvent', 'loadEventList', 'revisitEvent', 'beaconType', 'quest'], 'verified', 'events',
    'oracle seeds 12345, 101-505: same events, choices and resources at every box (12345 sector 1 31/31, 505 80/80, 202 63/63 ...); event counter drift +4 open; not ported: priorityNebulaFix, quest text options, REPAIR rule (events.md)'),
   ('checkCargo default for requirements', 'option defaults.checkCargo', ['checkCargo'], 'verified', 'events', 'part of the oracle box comparison'),
   ('Revisit fix, multi-ship fix, alt credit system', 'options upgradeRevisitFix, multiShipFix, altCreditSystem', 'off/off/on', 'partial', 'events', 'altCreditSystem in the capability rules; credits missing')],
 'game/Gameplay/World/CustomMap.cpp': [('StarMap::OnLoop rewrite (forced wait, sector choice), all-connections view', 'always; option showAllConnections', 'every run', 'partial', 'events',
   'StarMap waiting timer approximated (events.md); OnLoop rewrite not diffed')],
 'game/Gameplay/World/CustomRewards.cpp': [('Custom reward generation, named autoReward types', 'content', ['reward', 'autoReward'], 'verified', 'events', 'rewards by hand calculation and in the oracle boxes (resources equal)')],
 'game/Gameplay/World/CustomSectors.cpp': [('Custom sector map and descriptions', 'content', ['sectorMap', 'sectorDescription'], 'verified', 'events',
   'sector maps = oracle for seeds 202, 303, 505 sectors 1-2 and 404 sector 1 (compare-maps.py); 101 sector 2: 12/24 picks differ')],
 'game/Gameplay/World/CustomShipGenerator.cpp': [('Custom enemy ship generation', 'content', ['shipGenerator'], 'missing', 'ships', 'no port found')],
 'game/Gameplay/World/Infinite.cpp': [('Infinite mode', 'option infiniteMode', 'off', 'missing', 'core', '-')],
 'game/Gameplay/World/Seeds.cpp': [('Seeded runs: run seed (mt19937), sector/quest/boss fleet seeds, seed box, saved seeds', 'option seeds (default on)', 'on', 'verified', 'events',
   'seeded generation equals the oracle (seed 12345: 24/24 sector 1 locations; maps of seeds 101-505); seed box UI not checked')],
 'game/Gameplay/World/StarMap.cpp': [
   ('StarMap::GenerateMap/LoadGame rewrites', 'always', 'every run', 'partial', 'events',
    'GenerateMap: HsGeneration, maps = oracle (CustomSectors row); the LoadGame rewrite (reversedPath reset, loaded quests) is not reported (rule in the capability report)'),
   ('No jump while the wait timer runs', 'always', 'every run', 'partial', 'events', 'waiting timer approximated'),
   ('Split all-node view', 'option splitAllNodeView', 'off', 'missing', 'ui', '-')],
 'game/Gameplay/World/TriggeredEvents.cpp': [('Triggered events: timers, jump/death/hull/crew/req triggers, modifiers, UI, save', 'content', ['triggeredEvent'], 'implemented', 'events',
   'libstdc++ unordered_map order checked against g++ -m32; save round trip; crew counts approximated, created a frame late after a load (events.md)')],
 'game/Ship/AlternateOxygenRendering.cpp': [('Gradient oxygen rendering', 'option alternateOxygenRendering', 'off', 'missing', 'render', '-')],
 'game/Ship/Augments.cpp': [('FTL_JUMPER, SECTOR_SCANNER, TELEPORT_RECALL, boarder recall on jump', 'content', ['aug'], 'missing', 'ships', 'no report covers these augments')],
 'game/Ship/BeamFixes.cpp': [('Weapons keep their ship target in sync', 'always', 'every run', 'n/a', 'weapons', 'weapons.md: the engine re-reads targets')],
 'game/Ship/CrewMember_Extend.cpp': [('Crew extension data', 'content', ['crew'], 'implemented', 'statcrew', 'HsCrewMember (statcrew.md)')],
 'game/Ship/CrewSpawn.cpp': [('Crew spawned without a slot', 'content', '-', 'missing', 'statcrew', 'tile crew spawn not ported (weapons.md)')],
 'game/Ship/CrewVTable.cpp': [('Custom crew virtual table', 'content', ['crew'], 'implemented', 'statcrew', 'statcrew.md')],
 'game/Ship/CustomAugments.cpp': [
   ('Custom augments, hidden augments, augment functions, statBoosts on augments', 'content', ['augment', 'hiddenAug', 'function'], 'verified', 'ships',
    'starts of 312/312 hangar layouts = oracle incl. hidden augments (hsShipsProbe); augment stat boosts in the statcrew loop comparison'),
   ('Scrap collector value in choices', 'option showScrapCollectorScrap', 'off', 'missing', 'ui', '-'),
   ('Slug gel scales with ship', 'option scaleSlugGel', 'off', 'missing', 'ships', '-')],
 'game/Ship/CustomCrew.cpp': [
   ('Custom races: stats, powers, transformations, spawns, AI, mind control, teleport, damage', 'content', ['crew', 'race', 'power', 'transformRace'], 'verified', 'statcrew',
    'race stats at start 740 crew x 60 values = oracle; after the first boost loop 134 crew (11 ELITE_SHIP diffs); 9 powers in 11 scenarios equal at every mark (statcrew.md); room oxygen drain differs in one scenario'),
   ('Mind control resistance stat', 'option resistsMindControlStat', 'on', 'implemented', 'statcrew', 'CrewStat; no MV power uses mind control'),
   ('Advanced crew tooltips; newline between tooltips', 'options advancedCrewTooltips, insertNewlineForMultipleCrewTooltips', 'on', 'missing', 'ui', 'rule in the capability report'),
   ('Tile-based crew movement', 'option alternateCrewMovement', 'on', 'missing', 'statcrew', 'rule in the capability report')],
 'game/Ship/CustomCrystalShard.cpp': [('Crystal shard owner fix; custom crystalShard augments', 'always; option crystalShardFix (default on)', ['crystalShard'], 'implemented', 'weapons',
   'probe round2: CRYSTAL_ARMOR shard owned by its ship; beams/bombs/flak and iteration order missing')],
 'game/Ship/CustomLockdowns.cpp': [('Door lockdown shards per side, custom lockdown durations and door health', 'content', ['lockdown'], 'implemented', 'systems',
   'systems.md round 2 probe; colour/anims/canDilate recorded unsupported (statcrew.md)')],
 'game/Ship/CustomShips.cpp': [
   ('Custom ships: crew/system limits, rooms, crew list, reactor, noJump, noFuelStalemate, automation', 'content', ['customShip', 'crewLimit', 'noJump', 'noFuelStalemate'], 'verified', 'ships',
    '967 definitions vs the XML; starts of 312/312 hangar layouts = oracle (crew species and rooms, weapons, drones, systems, reactor, hull, resources)'),
   ('Enemy weapons start powered and pre-ignited', 'option enemyPreigniterFix', 'on', 'implemented', 'weapons', 'enemy path not run (weapons.md)'),
   ('Warning light position, artillery gib mounts, cloak render, hide hull while exploding', 'options warningLightPositionFix, artilleryGibMountFix, cloakRenderFix, hideHullDuringExplosion', 'on/on/on/off', 'missing', 'render', 'rules in the capability report')],
 'game/Ship/OxygenWithoutSystem.cpp': [('Rooms keep oxygen without an oxygen system; system accessors guard missing systems', 'option oxygenWithoutSystem; accessors always', 'on', 'partial', 'systems',
   'systems.md: the engine already runs oxygen without a system; not compared')],
 'game/Ship/Room_Extend.cpp': [('Room extension data (time dilation, erosion, stat boosts)', 'content', '-', 'implemented', 'systems', 'HsRoomSys / Room.extend (systems, weapons, statcrew)')],
 'game/Ship/ShipManager_Extend.cpp': [('ShipManager extension data', 'content', '-', 'implemented', 'ships', 'HsShipState')],
 'game/Ship/ShipSystems/ArtillerySystem.cpp': [('Targetable artillery and its hotkeys', 'option targetableArtillery', ['targetableArtillery'], 'partial', 'systems', 'aiming ported (probe); aim_artillery hotkeys missing')],
 'game/Ship/ShipSystems/CustomSuperShield.cpp': [('Augment super shields, colours, textures, bars above 5', 'content', ['superShield'], 'implemented', 'systems',
   'probe: 12 + 3 = 15, drone add, InstantCharge, save/load; images and enemy bar compile only (systems.md)')],
 'game/Ship/ShipSystems/CustomSystems.cpp': [
   ('Custom systems (temporal), levels, exclusivity, clonebay/medbay/mind variants, store replacement, system limits', 'content', ['customSystems', 'temporal'], 'verified', 'systems',
    'system start state 2657/2664 = oracle (312 layouts); dilation rates = oracle (systems-dilation session); temporal AI and box not against the original'),
   ('Shields without a shield system', 'option shieldWithoutSystem', 'off', 'missing', 'systems', '-'),
   ('Dual medical (medbay and clonebay)', 'option dualMedical', 'off', 'implemented', 'systems', 'store replacement fallback (systems.md round 2)')],
 'game/Ship/ShipSystems/ErosionEffect.cpp': [('Erosion: system integrity loss, partial breaches, repair multiplier', 'content', ['erosion'], 'implemented', 'weapons',
   'probe round2 (MODULAR_CHARGE_CHAOS_FIRE), reviewed by systems; breach slot pick not against the original; room animation missing')],
 'game/Ship/ShipSystems/HackingSystem.cpp': [('Hacking sound loop only while hacking', 'always', 'every run', 'implemented', 'systems', 'equal to the existing port (source comparison)')],
 'game/Ship/ShipSystems/OxygenSystem.cpp': [('OxygenSystem::OnLoop/GetRefillSpeed rewrite', 'always; option oxygenRefillFix', 'every run (fix off)', 'implemented', 'systems', 'equal to the existing port (source comparison)')],
 'game/Ship/ShipSystems/SystemDescriptionFixes.cpp': [('Medbay/teleporter/clonebay level descriptions', 'always', 'every run', 'implemented', 'systems', 'text only, not run')],
 'game/Ship/ShipSystems/System_Extend.cpp': [('System extension data (additionalPowerLoss)', 'content/lua', '-', 'implemented', 'systems', 'probe: Lua loss takes engines 2 -> 1')],
 'game/Ship/ShipSystems/TemporalSystem.cpp': [('Temporal system: room dilation, AI targets, box UI, save', 'content', ['temporal'], 'verified', 'systems',
   'dilation rates = oracle (x4.00 / x0.12); start state of temporal ships = oracle; AI and box UI not against the original; door lockdown timers not dilated')],
 'game/Ship/ShipSystems/TemporalSystemVTable.cpp': [('Temporal system vtable', 'content', ['temporal'], 'implemented', 'systems', 'see TemporalSystem')],
 'game/Ship/StatBoost.cpp': [('Stat boosts: every type, condition, target, priorities, stacks, timed/room boosts, per-frame evaluation', 'content', ['statBoost'], 'verified', 'statcrew',
   'boost loop comparison with the oracle (134 crew x 60 values, PLAYER ships all equal) and the power scenarios (statcrew.md)')],
 'game/UserInterface/AdditionalWeaponSlots.cpp': [('More than four weapon / drone slots', 'content', '-', 'missing', 'ui', 'no report covers it')],
 'game/UserInterface/ArtilleryBarFix.cpp': [('Artillery charge bar by power', 'always', 'every run', 'implemented', 'systems', 'compiles; not rendered/compared (systems.md)')],
 'game/UserInterface/CloneQueue.cpp': [('Clone queue UI', 'option enhancedCloneUI', 'on', 'missing', 'ui', 'rule in the capability report')],
 'game/UserInterface/CommandConsole.cpp': [('HS command console', 'option console', 'on', 'missing', 'ui', 'waits on decision U4 (debug console)')],
 'game/UserInterface/CooldownNumbers.cpp': [('Numeric weapon cooldowns', 'option showNumericalWeaponCooldown', 'on', 'verified', 'weapons', 'weapon bar text pixels = oracle frame 10-fight (weapons.md)')],
 'game/UserInterface/Credits.cpp': [('Credits music replaceable by events (GameOver::OnLoop)', 'always', ['customCredits'], 'missing', 'ui', 'custom credits missing (events.md, render.md)')],
 'game/UserInterface/CrewBox_Extend.cpp': [('Crew box extension: ability bars, power buttons, tooltips', 'content', ['power'], 'implemented', 'statcrew', 'no screenshot comparison (statcrew.md)')],
 'game/UserInterface/CrewManifestVTable.cpp': [('Crew manifest vtable', 'content', '-', 'missing', 'ui', 'no port found')],
 'game/UserInterface/CustomColors.cpp': [('UI colours: textButtons, systemDetails; choice colours', 'content', ['textButtons', 'systemDetails', 'customChoiceColors'], 'verified', 'render',
   'oracle frames 10, 11, 14: jump, autofire and pause buttons 0 px diff; systemDetails not seen on a frame; choice colours unused by MV (render.md)')],
 'game/UserInterface/CustomCommandGui.cpp': [('Command GUI extensions (more crew, drone stations)', 'content', '-', 'missing', 'ui', 'no port found')],
 'game/UserInterface/CustomCrewManifest.cpp': [('Paged crew manifest, rename input, dismiss sound', 'always (pages); options', 'every run', 'missing', 'ui', '-')],
 'game/UserInterface/CustomEquipment.cpp': [('Equipment over capacity, cargo, augment pages, dummy slots', 'options multipleOverCapacity, showDummyEquipmentSlots', 'on (dummy 0.3)', 'missing', 'ui', 'rule in the capability report')],
 'game/UserInterface/CustomHotkeys.cpp': [('Extra HS hotkeys in the controls screen', 'always', 'every run', 'missing', 'render', 'render.md')],
 'game/UserInterface/CustomReactor.cpp': [('Reactor price tables per ship', 'content', ['customReactor'], 'implemented', 'ships', 'reactor bar/cost (ships.md): not against the original')],
 'game/UserInterface/CustomShipSelect.cpp': [
   ('Hangar: custom ship pages, buttons, A/B/C variants, ship info', 'content', ['customShip'], 'implemented', 'ships', 'hsShipsProbe: 123 entries, order, 18 pages, cycling, run start (against the XML)'),
   ('Advanced Edition forced on, toggle hidden', 'option forceDlc', 'on', 'implemented', 'core', 'MainGame/HangarPresentation; hs-core MV value check'),
   ('Hangar extras: reactor/missile/drone/crew counts, ship achievements', 'options showReactor, extraShipInfo, showShipAchievements', 'on', 'missing', 'ui', 'rules in the capability report')],
 'game/UserInterface/CustomSpaceStatus.cpp': [('Space status: several hazards shown together', 'always', 'every run', 'missing', 'ui', '-')],
 'game/UserInterface/CustomStore.cpp': [('Custom stores, pages, purchase limits, arrow keys', 'content', ['customStore', 'store'], 'implemented', 'ships',
   'hsShipsProbe: 65 stores, 257 pages, 1445 boxes for seed 1, save round trip (against the XML; no oracle store dumps)')],
 'game/UserInterface/CustomStoreBox.cpp': [('System store box does not sell installed systems', 'always', 'every run', 'implemented', 'ships', 'ships.md; confirm texts missing')],
 'game/UserInterface/CustomTabbedWindow.cpp': [('Custom tabs in the ship window', 'option customTabs', 'absent', 'missing', 'ui', '-')],
 'game/UserInterface/CustomUpgrades.cpp': [('Upgrade screen: ship rename, cost threshold, second column', 'options renameShipInRun, systemNoPurchaseThreshold', 'on (999, "--")', 'missing', 'ui', 'rules in the capability report')],
 'game/UserInterface/EnemyShipIcons.cpp': [('Enemy ship icons on the target box', 'content', ['shipIcon', 'shipIcons'], 'implemented', 'render', 'oracle 10-fight has no icons on either side; an icon-bearing enemy not captured')],
 'game/UserInterface/Equipment_Extend.cpp': [('Equipment extension data', 'content', '-', 'missing', 'ui', 'no port found')],
 'game/UserInterface/EventButtons.cpp': [('Event buttons (image buttons, layout, tooltips, click queues event, save)', 'content', ['eventButton'], 'partial', 'render',
   'outline geometry = oracle frame 04; click/queue not against the original; idx drifts on reload (hscore probe note); text buttons absent from MV')],
 'game/UserInterface/EventTooltip.cpp': [('Event choice reward tooltips', 'option eventTooltips', 'on', 'implemented', 'render', 'position from source, no frame')],
 'game/UserInterface/HullNumbers.cpp': [('Hull numbers, hull bars, player HP colour fix', 'options hullNumbers, hullBars, playerHpColorFix', ['hullNumbers', 'hullBars'], 'verified', 'render', 'oracle 10-fight crops: 0 px diff')],
 'game/UserInterface/MainMenu.cpp': [('Main menu without the ITB button; title screen, logo, splash', 'always / titleScreen', ['titleScreen'], 'verified', 'render', 'oracle 01-menu: only cursor and Steam footer differ')],
 'game/UserInterface/MoreInfoButton.cpp': [('More Info toggle (altMode)', 'always', 'every run', 'implemented', 'render', 'port only')],
 'game/UserInterface/RedesignedTooltips.cpp': [('Redesigned weapon/crew/drone/augment tooltips', 'options redesigned*Tooltips', 'on (weapon/drone/augment)', 'missing', 'ui', 'render.md')],
 'game/UserInterface/RenderReactorBar.cpp': [('Reactor bar beyond 25 bars', 'always', 'every run', 'missing', 'render', 'no port found')],
 'game/UserInterface/SaveFile.cpp': [('HS save files: prefix, backup, transfer, inheritMode', 'always / saveFile', 'prefix mv, inheritMode 2', 'partial', 'core',
   'a save holds its own profile, so a new save starts fresh (inheritMode 2) by decision; 0/1 not used')],
 'game/UserInterface/ScrollingChoiceBox.cpp': [('Scrolling event choice box', 'always', 'every run', 'implemented', 'render', 'compiles; no oracle frame of a long event')],
 'game/UserInterface/Store_Extend.cpp': [('Store extension data', 'content', '-', 'implemented', 'ships', 'HsStoreState')],
 'game/UserInterface/SystemBox_Extend.cpp': [('System box extension, SystemBox Lua events', 'content/lua', '-', 'implemented', 'systems', 'probe with Lua: construct, location, mouse, key pre-empt (systems.md round 2)')],
 'game/UserInterface/TileBasedCrewMovement.cpp': [('Right-click door opening; tile-based movement', 'options rightClickDoorOpening (default on), alternateCrewMovement', 'on', 'missing', 'ui', 'rules in the capability report')],
 'game/Utilities/CustomLocalization.cpp': [('Custom languages and font overrides', 'content', ['languages'], 'missing', 'ui', 'MV has none')],
 'game/Utilities/CustomTextStyle.cpp': [('Text styles in strings', 'option customTextStyle', 'absent', 'missing', 'render', '-')],
 'game/Utilities/Input.cpp': [('Mouse event routing; right-button release to system control', 'always', 'every run', 'missing', 'ui', 'no port found')],
 'game/Utilities/LazyResourceLoading.cpp': [('Lazy image loading', 'always; option disableLazyImageLoading', 'every run', 'n/a', '-', 'our loader is lazy')],
 'game/Utilities/Renderer.cpp': [('Window setup (WINE detection)', 'platform', '-', 'n/a', '-', '-')],
 'game/Utilities/Resources.cpp': [('hyperspace.xml two-pass parse: version check, options, feature nodes', 'always', 'every run', 'implemented', 'core',
   'HsConfig; MV values asserted by tests/hs-core.mjs (HS_EXPECT=mv)')],
 'overrides/FileHelper.cpp': [('User folder and save paths', 'platform', '-', 'n/a', 'core', 'loader storage')],
}

counts = Counter()
if data:
    for f in glob.glob(os.path.join(data, '*.xml')):
        t = open(f, encoding='utf-8', errors='replace').read()
        t = re.sub(r'<!--.*?-->', '', t, flags=re.S)
        for m in re.finditer(r'<([A-Za-z_][\w.\-]*)[\s/>]', t):
            counts[m.group(1)] += 1


def mv(uses):
    if isinstance(uses, str):
        return uses
    if not data:
        return ', '.join(f'`{u}`' for u in uses)
    return ', '.join(f'`{u}` {counts.get(u, 0)}' for u in uses)


HOOK = re.compile(r'^\s*(HOOK_(?:METHOD|STATIC|GLOBAL)(?:_PRIORITY)?)\s*\((.*)$', re.M)
by_file = {}
for root, _, files in os.walk(os.path.join(hs, 'src')):
    for fn in sorted(files):
        if not fn.endswith('.cpp'):
            continue
        p = os.path.join(root, fn)
        rel = os.path.relpath(p, os.path.join(hs, 'src')).replace(os.sep, '/')
        t = open(p, encoding='utf-8', errors='replace').read()
        t = re.sub(r'/\*.*?\*/', lambda m: chr(10) * m.group(0).count(chr(10)), t, flags=re.S)
        t = re.sub(r'^\s*//.*$', '', t, flags=re.M)
        hooks = []
        for m in HOOK.finditer(t):
            parts = [a.strip() for a in m.group(2).split(',')]
            name = parts[0] if m.group(1).startswith('HOOK_GLOBAL') else parts[0] + '::' + parts[1]
            if 'PRIORITY' in m.group(1):
                name += '(' + (parts[1] if m.group(1).startswith('HOOK_GLOBAL') else parts[2]) + ')'
            hooks.append(name)
        if hooks:
            by_file[rel] = hooks

missing = sorted(set(by_file) - set(FILES))
stale = sorted(set(FILES) - set(by_file))
if missing or stale:
    sys.exit(f'catalogue out of date: missing {missing}, stale {stale}')

rows = [(f, i, r) for f in sorted(by_file) for i, r in enumerate(FILES[f])]
status = Counter(r[3] for _, _, r in rows)
def is_used(r):
    if r[1] == 'platform':
        return False
    if isinstance(r[2], str):
        return r[2] not in ('-', 'off', 'absent')
    return not data or sum(counts.get(u, 0) for u in r[2]) > 0


used = [r for _, _, r in rows if is_used(r)]
used_status = Counter(r[3] for r in used)
total = sum(len(h) for h in by_file.values())
lines = []
lines.append(f'Generated by `tools/hs-tags/gen-hs-catalogue.py` from FTL-Hyperspace 1b05a07 `src/` '
             f'({len(by_file)} files, {total} active hooks, {len(rows)} features)'
             + (' with MV 5.5.1 uses counted in the merged data' if data else '') + '.\n')
lines.append('Status of all features: ' + ', '.join(f'{k} {status[k]}' for k in ('verified', 'implemented', 'partial', 'missing', 'n/a')) + '. ')
lines.append('Features MV uses or switches on: ' + ', '.join(f'{k} {used_status[k]}' for k in ('verified', 'implemented', 'partial', 'missing', 'n/a')) + '.\n')
lines.append('| Feature | HS file | Hooks | Gate | MV uses | Status | Owner | Evidence |')
lines.append('|---|---|---|---|---|---|---|---|')
for f, i, (feature, gate, uses, st, owner, evidence) in rows:
    lines.append(f'| {feature} | `{f}` | {len(by_file[f]) if i == 0 else ""} | {gate} | {mv(uses)} | **{st}** | {owner} | {evidence} |')
lines.append('\n<details><summary>Every hook by file</summary>\n')
for f in sorted(by_file):
    lines.append(f'- `{f}`: ' + ', '.join(by_file[f]))
lines.append('\n</details>\n')
open(out, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print(f'{len(by_file)} files, {total} hooks, {len(rows)} features; ' + ', '.join(f'{k} {v}' for k, v in status.items())
      + '; MV-used: ' + ', '.join(f'{k} {v}' for k, v in used_status.items()))
