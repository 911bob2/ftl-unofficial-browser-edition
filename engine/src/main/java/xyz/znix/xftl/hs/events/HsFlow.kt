// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (CustomEventsParser::GetEvent/LoadEvent,
// the WorldManager CreateLocation/UpdateLocation/CreateChoiceBox/ModifyResources/CheckRequirements hooks,
// CustomCreateLocation) and Seeds.cpp (GetBaseEvent) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see
// docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.CreatedChoice
import xyz.znix.xftl.sector.CreatedEvent

/**
 * Where the engine's event window and the original's WorldManager meet:
 *
 * - [afterCreateLocation]: WorldManager::CreateLocation (arrival at a beacon) after the original's part;
 * - [afterUpdateLocation]: WorldManager::UpdateLocation (a choice's event, a loaded event) after the original's part;
 * - [choiceBox]: WorldManager::CreateChoiceBox's choice list;
 * - [beforeModifyResources]/[afterModifyResources]: WorldManager::ModifyResources (an event's results, applied when
 *   one of its choices is taken, or at once when it has no text).
 *
 * The event window implements [Host]: UpdateLocation of a newly created event.
 */
object HsFlow {
    interface Host {
        /** WorldManager::UpdateLocation: show [event] (its original part, then [afterUpdateLocation]). */
        fun hsUpdateLocation(event: CreatedEvent)
    }

    /** Diagnostics (hs-events probe): each random load-list pick (state before the draw, value, candidates). */
    @JvmField var listTrace: ((String) -> Unit)? = null

    /**
     * Diagnostics (r8 resync, null in production): replaces the index of an unseeded load-list pick. Arguments: candidate
     * names, our index, whether the draw followed unpaused frames since the last reseed (the original draws from the same
     * stream in real-time frames, so the pick is time dependent there).
     */
    @JvmField var steerPick: ((List<String>, Int, Boolean) -> Int)? = null

    /** WorldManager::lastLocationEvent: the event UpdateLocation/CreateLocation last showed. */
    @JvmField var lastLocationEvent: CreatedEvent? = null

    private fun parser(game: InGameState): HsEventParser = game.eventManager.hs!!

    @JvmStatic
    fun customFor(game: InGameState, event: CreatedEvent?): CustomEvent? =
        event?.template?.hsName?.let { parser(game).getCustomEvent(it) }

    // ------------------------------------------------------------------ GetEvent / LoadEvent

    /** CustomEventsParser::GetEvent(list, seed). */
    fun getEvent(game: InGameState, list: EventLoadList, seedIn: Int): CreatedEvent? {
        val seed = if (list.seeded) seedIn else -1
        val player = game.player
        val usedGroups = HashSet<Int>()
        val candidates = ArrayList<CreatedEvent>()
        val draws = if (listTrace != null && seed == -1 && NativeRandom.trace == null) ArrayList<Int>().also { NativeRandom.trace = it } else null
        val perEntry = StringBuilder()
        val startState = if (draws != null) NativeRandom.snapshot() else null
        try {
        for (entry in list.events) {
            if (draws != null) perEntry.append(entry.event).append(':').append(draws.size).append(' ')
            if (entry.maxGroup != -1 && entry.maxGroup in usedGroups) continue
            if (entry.req.isNotEmpty()) {
                val result = HsReq.hasEquipment(game, player, entry.req)
                if (result < entry.lvl || result > entry.maxLvl) continue
                if (entry.maxGroup != -1) usedGroups += entry.maxGroup
            }
            val event = getEvent(game, entry.event, list.ignoreUnique, seed) ?: continue
            if (checkRequirements(game, event, false)) {
                if (list.useFirst) return event
                candidates += event
            } else {
                clearUsed(game, event)
            }
        }
        } finally {
            if (draws != null) {
                NativeRandom.trace = null
                listTrace?.invoke("draws start=$startState total=${draws.size} at " + perEntry)
            }
        }
        if (candidates.isEmpty()) {
            if (list.defaultEvent.isEmpty()) return null
            return getEvent(game, list.defaultEvent, list.ignoreUnique, seed)
        }
        if (candidates.size == 1) return candidates[0]
        if (seed != -1) NativeRandom.seed(seed)
        val before = if (listTrace != null) NativeRandom.snapshot() else null
        val live = steerPick != null && NativeRandom.isLive()
        val value = NativeRandom.next31()
        var index = value % candidates.size
        steerPick?.let { index = it(candidates.map { c -> c.template.hsName ?: c.template.debugId }, index, live) }
        val ret = candidates[index]
        listTrace?.invoke("seed=$seed state=$before value=$value pick=$index candidates=" +
            candidates.joinToString(",") { it.template.hsName ?: it.template.debugId })
        for (event in candidates) if (event !== ret) clearUsed(game, event)
        return ret
    }

    /** EventGenerator::ClearUsedEvent + LocationEvent::ClearEvent: an unused created event gives its unique back. */
    private fun clearUsed(game: InGameState, event: CreatedEvent) {
        game.eventManager.hsReturnUnique(event.template)
    }

    /** CustomEventsParser::GetEvent(name, ignoreUnique, seed): an alias, a named load list, else GetBaseEvent. */
    fun getEvent(game: InGameState, nameIn: String, ignoreUnique: Boolean, seed: Int): CreatedEvent? {
        var name = nameIn
        HsEvents.eventAliases[name]?.let { alias ->
            name = alias.event
            if (alias.once) HsEvents.eventAliases.remove(nameIn)
        }
        parser(game).getEventLoadList(name)?.let { return getEvent(game, it, seed) }
        return getBaseEvent(game, name, level(game), ignoreUnique, seed)
    }

    /**
     * `world->starMap.currentSector->level`: while a map is generated (the onGenerate pass of the GenerateMap hook) the
     * star map's current sector is already the new one, while our current beacon is still in the sector being left.
     */
    fun level(game: InGameState): Int = HsEvents.generatingSector?.columnNumber ?: game.eventCreator.currentLevel()

    /**
     * EventGenerator::GetBaseEvent with HS's hooks: a "QUEST\t<seed>\t<name>" name (a saved or delayed quest) reseeds
     * the stream with its seed when seeds are on (Seeds.cpp); then CreateEvent, reseeded with [seed] unless -1.
     */
    fun getBaseEvent(game: InGameState, nameIn: String, level: Int, ignoreUnique: Boolean, seed: Int): CreatedEvent? {
        var name = nameIn
        if (name.startsWith("QUEST\t")) {
            val parts = name.substring(6).split('\t')
            if (HsSeeds.enabled) NativeRandom.seed(parts[0].toIntOrNull() ?: 0)
            name = parts.getOrElse(1) { "" }
        }
        val root = game.eventManager.getOrNull(name)
        if (root == null) {
            if (name.isNotEmpty()) HS.unsupported("missing-event", "GetBaseEvent", name)
            return null
        }
        return game.eventCreator.createSeeded(root, level, ignoreUnique, seed)
    }

    /** CustomEventsParser::LoadEvent(list): GetEvent under the advanced check, then UpdateLocation. */
    fun loadEvent(game: InGameState, host: Host, list: EventLoadList, seed: Int, parent: CustomEvent?) {
        HsEvents.setCheckCargo(parser(game), parent)
        var event: CreatedEvent? = null
        HsEvents.withCheck(2) { event = getEvent(game, list, seed) }
        HsEvents.checkCargo = false
        event?.let { host.hsUpdateLocation(it) }
    }

    /** CustomEventsParser::LoadEvent(name). */
    fun loadEvent(game: InGameState, host: Host, name: String, ignoreUnique: Boolean, seed: Int, parent: CustomEvent?) {
        HsEvents.setCheckCargo(parser(game), parent)
        var event: CreatedEvent? = null
        HsEvents.withCheck(2) { event = getEvent(game, name, ignoreUnique, seed) }
        HsEvents.checkCargo = false
        event?.let { host.hsUpdateLocation(it) }
    }

    // ------------------------------------------------------------------ CheckRequirements

    /**
     * WorldManager::CheckRequirements ([xyz.znix.xftl.sector.NativeRequirements]: the costs unless the event steals,
     * the repair station's rule) and HS's hook: a non-stealing event's costs plus the previous event's must be covered
     * (a vanilla fix), and with choiceRequiresCrew a crew-losing event needs an eligible crew member (stealing or not).
     */
    fun checkRequirements(game: InGameState, event: CreatedEvent?, hidden: Boolean): Boolean {
        event ?: return true
        val ship = game.player ?: return true
        // the original's rule first (its costs unless the event steals, the repair station's), then HS's
        if (!xyz.znix.xftl.sector.NativeRequirements.check(ship, event.fuel, event.missiles, event.droneParts, event.scrap, event.template)) return false
        val steal = event.template.itemsModifySteal

        val last = lastLocationEvent
        if (last != null && last !== event && !steal && !last.template.itemsModifySteal) {
            val fuel = event.fuel + last.fuel
            if (fuel < 0 && ship.fuelCount < -fuel) return false
            val missiles = event.missiles + last.missiles
            if (missiles < 0 && ship.missilesCount < -missiles) return false
            val drones = event.droneParts + last.droneParts
            if (drones < 0 && ship.dronesCount < -drones) return false
            val scrap = event.scrap + last.scrap
            if (scrap < 0 && ship.scrap < -scrap) return false
        }

        val custom = customFor(game, event)
        val crewAmount = event.template.crewMemberAmount
        if (custom != null) {
            if (custom.choiceRequiresCrew) {
                if (crewAmount < 0 && HsCrew.randomCrewList(game, event.template.crewMemberClass ?: "", false, custom.allowNoSlot, custom.blockNoSlot).isEmpty()) return false
                if (custom.transformRaceTo.isNotEmpty() &&
                    HsCrew.randomCrewList(game, custom.transformRaceFrom, false, custom.allowNoSlot, custom.blockNoSlot).isEmpty()) return false
            }
        } else if (crewAmount < 0 && parser(game).defaultChoiceRequiresCrew) {
            if (HsCrew.randomCrewList(game, event.template.crewMemberClass ?: "", false, false, false).isEmpty()) return false
        }
        return true
    }

    // ------------------------------------------------------------------ CreateChoiceBox

    /**
     * CustomEvents.cpp's CreateChoiceBox hook, before the original: `<unlockCustomShip>` (CustomShipUnlocks, when the
     * current ship is the required one) and `<achievement>` (CustomAchievementTracker::SetAchievement). The quest
     * text edits (noQuestText, actuallyEnoughTime, nextSectorPopped) are not applied.
     */
    fun beforeChoiceBox(game: InGameState, event: CreatedEvent) {
        val custom = customFor(game, event) ?: return
        if (custom.unlockShip.isNotEmpty() &&
            (custom.unlockShipReq.isEmpty() || xyz.znix.xftl.hs.ship.HsAchievements.currentShip() == custom.unlockShipReq))
            xyz.znix.xftl.hs.ship.HsProfileHooks.eventUnlock(game.mainGame.profile, custom.unlockShip, custom.unlockShipSilent, game.difficulty)
        if (custom.achievement.isNotEmpty())
            xyz.znix.xftl.hs.ship.HsAchievements.setAchievement(custom.achievement, custom.achievementSilent)
    }

    /** A choice as the box shows it: 0 normal, 1 unavailable (greyed), 2 blue. */
    class BoxChoice(val choice: CreatedChoice, val type: Int)

    /**
     * WorldManager::CreateChoiceBox's choice list (0x616680) under HS: a choice with a req is kept only while
     * HasEquipment (with the advanced checks and the event's checkCargo) is within [lvl, max_lvl]; of a max_group the
     * kept choice with the strictly highest lvl survives and the groups move to the end in ascending group order;
     * req choices are blue (unless blue="false"), and a choice whose event fails CheckRequirements is greyed.
     */
    /**
     * Diagnostics: every choice box as it is made (the tracer's POST_CREATE_CHOICEBOX); [choices] null for an event
     * without text (no box in our window, HS's box shows only "Continue...").
     */
    @JvmField var boxListener: ((InGameState, CreatedEvent, List<BoxChoice>?) -> Unit)? = null

    fun choiceBox(game: InGameState, event: CreatedEvent): List<BoxChoice> {
        val parser = parser(game)
        val custom = customFor(game, event)
        HsEvents.setCheckCargo(parser, custom)
        val kept = ArrayList<Pair<CreatedChoice, Int>>()
        val groups = java.util.TreeMap<Int, Pair<CreatedChoice, Int>>()
        HsEvents.withCheck(0) {
            for (choice in event.choices) {
                val t = choice.template
                var type = 0
                val req = t.req
                if (req != null) {
                    val level = HsReq.hasEquipment(game, game.player, req)
                    val min = t.minLevel ?: 1
                    val max = t.maxLevel ?: Int.MAX_VALUE
                    if (level < min || level > max) continue
                    if (t.blue) type = 2
                    val group = t.maxGroup ?: -1
                    if (group != -1) {
                        val existing = groups[group]
                        if (existing == null || (existing.first.template.minLevel ?: 1) < min) groups[group] = choice to type
                        continue
                    }
                }
                kept += choice to type
            }
        }
        HsEvents.checkCargo = false
        kept.addAll(groups.values)
        return kept.map { (choice, type) ->
            BoxChoice(choice, if (!checkRequirements(game, choice.event, choice.template.hidden)) 1 else type)
        }
    }

    // ------------------------------------------------------------------ CreateLocation / UpdateLocation

    /**
     * CreateLocation (before the original): lastLocationEvent is the arrival event from the start (CustomStore.cpp's
     * CreateLocation rewrite), and aliases and death events marked jumpClear end with the jump.
     */
    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomStore.cpp
    fun beforeCreateLocation(event: CreatedEvent?) {
        if (event != null) lastLocationEvent = event
        HsEvents.eventAliases.entries.removeIf { it.value.jumpClear }
        HsEvents.deathEventQueue.removeAll { it.jumpClear }
        xyz.znix.xftl.hs.render.HsEventButtons.clearLocation() // WorldManager::ClearLocation (EventButtons.cpp)
        xyz.znix.xftl.hs.render.HsBackgroundObjects.clearLocation() // and CustomBackgroundObject.cpp
    }

    /**
     * CreateLocation (after the original showed the beacon's event): a revisited beacon loads its revisit event;
     * otherwise the event's HS actions, queued events and loads run.
     */
    fun afterCreateLocation(game: InGameState, host: Host, beacon: Beacon, event: CreatedEvent?, revisit: Boolean) {
        // CustomBackgroundObject.cpp's CreateLocation hook: currentLocationIndex is the shown beacon's
        xyz.znix.xftl.hs.render.HsBackgroundObjects.createLocation(game)
        customEventsCreateLocation(game, host, beacon, event, revisit)
        // CustomFleetShips.cpp's CreateLocation hook runs after CustomEvents.cpp's: an event loaded on arrival made
        // its fleet through UpdateLocation, and this clears it again unless the beacon's own event has one. A fight
        // reached through FLEET_THREAT_LOW therefore shows no custom fleet, and FLEET_ELITE's name is still set when
        // the next arrival's UpdateLocation compares it.
        // At the flagship the hook finds no event with a fleet, so a boss fight shows none whatever BOSS_TEXT_n lists.
        val bossLocation = xyz.znix.xftl.environment.BeaconScene.flagshipAt(beacon) != null
        HsCustomFleet.createLocation(if (bossLocation) null else customFor(game, event))
    }

    /** CustomEvents.cpp's CreateLocation hook. */
    private fun customEventsCreateLocation(game: InGameState, host: Host, beacon: Beacon, event: CreatedEvent?, revisit: Boolean) {
        event ?: return
        lastLocationEvent = event
        val parser = parser(game)
        val sectorSeed = HsSeeds.currentSectorSeed
        val x = beacon.pos.x
        val y = beacon.pos.y
        if (revisit) {
            if (beacon.ship != null && game.isEnemyHostile) return // an enemy ship was left behind
            var name = parser.defaultRevisit
            var seeded = parser.defaultRevisitSeeded
            var ignoreUnique = parser.defaultRevisitIgnoreUnique
            val custom = customFor(game, event)
            if (custom != null && custom.eventRevisit.isNotEmpty()) {
                name = custom.eventRevisit; seeded = custom.eventRevisitSeeded; ignoreUnique = custom.eventRevisitIgnoreUnique
            }
            if (name.isNotEmpty()) {
                val visits = beacon.hsVisits
                val seed = if (seeded) (x + y) xor (sectorSeed * visits + visits) else -1
                loadEvent(game, host, name, ignoreUnique, seed, custom)
            }
            return
        }
        val custom = customFor(game, event) ?: return
        customCreateLocation(game, event, custom)
        runLoads(game, host, custom, x, y, sectorSeed)
        if (custom.secretSectorWarp.isNotEmpty()) beacon.hsSecretWarp = custom.secretSectorWarp
    }

    /**
     * ReplaceSector(def) (CustomSectors.cpp): seeded runs reseed with the quest seed; one of the later sectors whose
     * description is [target] (`random32() % n`, in StarMap::sectors order) takes [list]; seeded runs then draw
     * the next quest seed.
     */
    fun replaceSector(game: InGameState, target: String, list: String) {
        if (HsSeeds.enabled) NativeRandom.seed(HsSeeds.questSeed)
        val map = game.gameMap
        val worldLevel = game.currentBeacon.sector.info.columnNumber
        val candidates = map.sectors.flatten().filter { it.columnNumber > worldLevel && it.type.name == target }
        if (candidates.isEmpty()) return
        val sector = candidates[NativeRandom.next31() % candidates.size]
        if (HsSeeds.enabled) HsSeeds.questSeed = NativeRandom.next31()
        map.hsReplaceSector(sector, list, HsSeeds.currentSectorSeed)
    }

    /** StarMap::StartSecretSector: the sector type the current location's event warps to, or null (Crystal). */
    fun secretSectorWarp(game: InGameState): String? {
        val beacon = game.currentBeacon
        return beacon.hsSecretWarp ?: customFor(game, beacon.createdEvent)?.secretSectorWarp?.takeIf { it.isNotEmpty() }
    }

    /** g_noASBPlanet: the event UpdateLocation is showing keeps its planet when it starts an ASB. */
    @JvmField var noAsbPlanet = false

    /**
     * CustomEvents.cpp's CreateLocation and UpdateLocation hooks (priority 100, before the original): an event that
     * takes crew while the player's ship holds none of its own crew (CountCrew(false): alive, not intruders, not
     * drones; the rest are aboard the enemy) takes none ([CreatedEvent.hsCrewLossCleared]).
     */
    @JvmStatic
    fun clearCrewLossWithoutCrew(game: InGameState, event: CreatedEvent) {
        val player = game.player ?: return
        if (event.template.removedCrew.isNotEmpty() && xyz.znix.xftl.crew.CrewStuck.countCrew(player, false) == 0)
            event.hsCrewLossCleared = true
    }

    /** UpdateLocation (before the original). */
    fun beforeUpdateLocation(game: InGameState, event: CreatedEvent) {
        if (customFor(game, event)?.noASBPlanet == true) noAsbPlanet = true
        // UpdateLocation (CustomStore.cpp's rewrite) sets lastLocationEvent before CreateChoiceBox, so the HS cost
        // rule in CheckRequirements adds this event's own costs, not the previous one's.
        lastLocationEvent = event
        // lastSelectedCrewSeed = -1: WorldManager's field is the engine's crew kill seed, which every event start
        // (DialogueWindow.loadEvent) already clears.
    }

    /** UpdateLocation (after the original): hazards, the HS actions, queued events, loads and restartEvent. */
    fun afterUpdateLocation(game: InGameState, host: Host, event: CreatedEvent) {
        lastLocationEvent = event
        val custom = customFor(game, event)
        if (custom != null) customEventsUpdateLocation(game, host, event, custom)
        noAsbPlanet = false
        // CustomFleetShips.cpp's UpdateLocation hook runs after CustomEvents.cpp's.
        if (custom != null) HsCustomFleet.updateLocation(custom)
    }

    private fun customEventsUpdateLocation(game: InGameState, host: Host, event: CreatedEvent, custom: CustomEvent) {
        if (custom.removeHazards) HsEventEffects.removeHazards(game)
        if (custom.removeNebula) HsEventEffects.removeNebula(game)
        if (custom.resetFtl) game.player?.let { HsEventEffects.resetFtl(it) }
        customCreateLocation(game, event, custom)
        val beacon = game.currentBeacon
        runLoads(game, host, custom, beacon.pos.x, beacon.pos.y, HsSeeds.currentSectorSeed)
        if (custom.restartEvent) {
            // super(currentLoc->event): the beacon's own event again, original part only.
            beacon.createdEvent.let { host.hsUpdateLocation(it) }
        }
        // currentLoc->event->eventName = loc->eventName: StartSecretSector then finds this event's secretSectorWarp
        if (custom.secretSectorWarp.isNotEmpty()) beacon.hsSecretWarp = custom.secretSectorWarp
    }

    private fun runLoads(game: InGameState, host: Host, custom: CustomEvent, x: Int, y: Int, sectorSeed: Int) {
        for (q in custom.queueEvents) {
            val seed = if (q.seed != -1) HsSeeds.locationSeed(x, y, sectorSeed) else -1
            HsEvents.eventQueue.addLast(EventQueueEvent(q.event, q.label, seed))
        }
        custom.eventLoadList?.let { list ->
            val seed = if (list.seeded) HsSeeds.locationSeed(x, y, sectorSeed) else -1
            loadEvent(game, host, list, seed, custom)
        }
        if (custom.eventLoad.isNotEmpty()) {
            val seed = if (custom.eventLoadSeeded) HsSeeds.locationSeed(x, y, sectorSeed) else -1
            loadEvent(game, host, custom.eventLoad, custom.eventLoadIgnoreUnique, seed, custom)
        }
    }

    // SoundControl::StartPlaylist with the names as given: a name sounds.xml lacks plays silence (0x5d0210).
    private fun startPlaylist(game: InGameState, names: List<String>) {
        game.sounds.switchMusicList(names.map { game.sounds.getTrack(it) })
    }

    /** CustomCreateLocation: the HS actions an event takes when it is shown. */
    fun customCreateLocation(game: InGameState, event: CreatedEvent, custom: CustomEvent) {
        val where = event.template.hsName ?: ""
        for ((name, alias) in custom.eventAlias) {
            if (alias.event.isEmpty()) HsEvents.eventAliases.remove(name)
            else HsEvents.eventAliases[name] = EventAlias(alias.event, alias.jumpClear, alias.once)
        }
        if (custom.changeBackground.isNotEmpty()) {
            // SpaceManager::SwitchBackground (the shown image), then Location::spaceImage (MV's Lua reads it).
            game.currentBeacon.getEnvironment(game).hsChangeBackground(custom.changeBackground)
            game.currentBeacon.hsSpaceImage = custom.changeBackground
        }
        xyz.znix.xftl.hs.render.HsBackgroundObjects.apply(game, custom.clearBackgroundObjects, custom.transformBackgroundObjects, custom.backgroundObjects)
        xyz.znix.xftl.hs.render.HsEventButtons.apply(custom.clearEventButtons, custom.eventButtons)
        if (custom.recallBoarders) HsEventEffects.recallBoarders(game, custom.recallBoardersShip, custom.recallBoardersForce)
        if (custom.playSound.isNotEmpty()) HsEvents.playSound(game, custom.playSound)
        // SoundControl::StartPlaylist: the sector's tracks again, or the one named track (an unknown name: silence).
        if (custom.resetMusic) startPlaylist(game, game.currentBeacon.sector.info.type.soundtracks)
        if (custom.playMusic.isNotEmpty()) startPlaylist(game, listOf(custom.playMusic))
        if (custom.removeStore) {
            // Store window closed, baseLocationEvent->store false and pStore null.
            val beacon = game.currentBeacon
            beacon.hasStore = false
            beacon.setStoreData(null)
            game.shipUI.hsRemoveStore()
        }
        if (custom.enemyDamage.isNotEmpty()) HsEventEffects.enemyDamage(game, event, custom)
        if (custom.repairAllSystems) game.player?.let { HsEventEffects.repairAllSystems(it) }
        if (custom.killEnemyBoarders) HsEventEffects.killEnemyBoarders(game)
        if (custom.instantEscape || custom.escape || custom.surrender || custom.loadEscape || custom.loadSurrender ||
            custom.disableEscape || custom.disableSurrender) HsEventEffects.enemyAi(game, custom)
        // The drone surge and the barrage: CustomBoss.cpp's definitions (hs.ship's HsBoss).
        if (custom.superDrones != -1 || custom.clearSuperDrones != -1 || custom.superBarrage != -1)
            xyz.znix.xftl.hs.ship.HsBoss.applyEvent(game, where, custom.superDrones, custom.superDronesName,
                custom.clearSuperDrones, custom.superBarrage, custom.superBarrageName)
        if (custom.powerSuperShields != -1) HsEventEffects.superShields(game, custom)
        if (custom.renameBeacon.isNotEmpty()) {
            val beacon = game.currentBeacon
            beacon.hsName = custom.renameBeacon
            val index = beacon.sector.beacons.indexOf(beacon)
            if (index >= 0) HsEvents.renamedBeacons[index] = custom.renameBeacon
        }
        for (name in custom.clearQueueEvents) {
            if (name.isNotEmpty()) HsEvents.eventQueue.removeAll { it.label == name } else HsEvents.eventQueue.clear()
        }
    }

    // ------------------------------------------------------------------ ModifyResources

    /** ModifyResources (priority -200, before the original): checkCargo and the event's hidden augments. */
    fun beforeModifyResources(game: InGameState, event: CreatedEvent) {
        val custom = customFor(game, event)
        // WorldManager::ModifyResources hook (CustomEvents.cpp): blockScrapCollected / blockScrapAugments around the original
        game.hsBlockScrapCollected = custom?.disableScrapScore == true
        game.hsBlockScrapAugments = custom?.disableScrapAugments == true
        HsEvents.setCheckCargo(parser(game), custom)
        if (custom != null) for (aug in custom.hiddenAugs) {
            val key = "HIDDEN $aug"
            HsEvents.hiddenAugs[key] = (HsEvents.hiddenAugs[key] ?: 0) + 1
            game.player?.hs?.addHidden(aug)
        }
    }

    /**
     * `<removeSystem player="...">`: ShipManager::RemoveSystem(NameToSystemId(name)) on the player or the enemy ship;
     * every system of that type goes (artillery), never the reactor or an unknown name.
     */
    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp
    private fun removeSystem(game: InGameState, player: Boolean, name: String) {
        if (systemId(name) < 0) return
        val ship = (if (player) game.player else game.enemy) ?: return
        for (sys in ship.systems.filter { it.codename == name }) xyz.znix.xftl.hs.systems.HsSystemInstall.removeSystem(ship, sys)
    }

    /** ModifyResources (after the original): items, systems, variables, game over, jump/death events, etc. */
    fun afterModifyResources(game: InGameState, event: CreatedEvent) {
        game.hsBlockScrapCollected = false
        game.hsBlockScrapAugments = false
        HsEvents.checkCargo = false
        val custom = customFor(game, event) ?: return
        val where = event.template.hsName ?: ""
        for (item in custom.removeItems) HsEventEffects.removeItem(game, item)
        for ((player, name) in custom.removeSystems) removeSystem(game, player, name)
        if (custom.variables.isNotEmpty()) HsEvents.applyVariables(game, custom.variables, game.player)
        if (custom.gameOver.enabled) HsEventEffects.gameOver(game, custom.gameOver)
        for (name in custom.clearJumpEvents) {
            if (name.isNotEmpty()) HsEvents.jumpEventQueue.removeAll { it.label == name } else HsEvents.jumpEventQueue.clear()
        }
        if (custom.jumpEvents.isNotEmpty()) {
            for (j in custom.jumpEvents) HsEvents.jumpEventQueue += j
            HsEvents.jumpEventQueue.sortByDescending { it.priority } // stable
        }
        for (name in custom.clearDeathEvents) {
            if (name.isNotEmpty()) HsEvents.deathEventQueue.removeAll { it.label == name } else HsEvents.deathEventQueue.clear()
        }
        if (custom.deathEvents.isNotEmpty()) {
            val all = ArrayList(HsEvents.deathEventQueue)
            all.addAll(custom.deathEvents)
            all.sortByDescending { it.priority }
            HsEvents.deathEventQueue.clear()
            HsEvents.deathEventQueue.addAll(all)
        }
        if (custom.goToFlagship) HS.unsupported("event", where, "goToFlagship")
        if (custom.transformRaceTo.isNotEmpty()) transformRace(game, custom, where)
        HsTriggeredEvents.apply(game, custom.clearTriggeredEvents, custom.triggeredEvents, custom.triggeredEventModifiers)
        if (custom.replaceSectorTarget.isNotEmpty()) replaceSector(game, custom.replaceSectorTarget, custom.replaceSectorList)
    }

    /** `<transformRace>`: the crew pick's draws (HsCrew.transformRacePick); the transformation is not ported. */
    /**
     * ModifyResources' `<transformRace>` (playerCrew > 0): the crew pick (HsCrew.transformRacePick), then
     * GetCrewBlueprint(to) (drawn even when FindCrew finds nobody) and the transformation (statcrew's
     * HsCrewContent.eventTransformRace: originalRace, CrewMember_Extend::TransformRace).
     */
    private fun transformRace(game: InGameState, custom: CustomEvent, @Suppress("UNUSED_PARAMETER") where: String) {
        if (game.playerCrew.isEmpty()) return
        val crew = HsCrew.transformRacePick(game, custom)
        xyz.znix.xftl.hs.crew.HsCrewContent.eventTransformRace(game.blueprintManager, crew, custom.transformRaceTo, ftl.browser.NativeRandom)
    }

    /**
     * CreateChoiceBox (CustomEvents.cpp hook, after the original): transformRace's pick for the box, with the same
     * seed and GetCrewBlueprint draw as the later transformation. The box's "crew" reward line showing the
     * transformed blueprint is not drawn (DialogueWindow shows no crew line for it).
     */
    @JvmStatic
    fun afterChoiceBox(game: InGameState, event: CreatedEvent) {
        val custom = customFor(game, event) ?: return
        if (custom.transformRaceTo.isEmpty() || game.playerCrew.isEmpty()) return
        HsCrew.transformRacePick(game, custom)
        xyz.znix.xftl.hs.crew.HsCrewContent.crewBlueprint(game.blueprintManager, custom.transformRaceTo, ftl.browser.NativeRandom)
    }

    // ------------------------------------------------------------------ CreateShip

    /**
     * WorldManager::CreateShip (after the original, not while loading a save): the ship event's jump and death events
     * and triggered events. [shipName] is the ShipEvent's name (the event's `<ship load=...>`).
     */
    fun afterCreateShip(game: InGameState, shipName: String?) {
        HsFinalBoss.shipCreated()
        val custom = parser(game).getCustomShipEvent(shipName) ?: return
        for (name in custom.clearJumpEvents) {
            if (name.isNotEmpty()) HsEvents.jumpEventQueue.removeAll { it.label == name } else HsEvents.jumpEventQueue.clear()
        }
        if (custom.jumpEvents.isNotEmpty()) {
            HsEvents.jumpEventQueue.addAll(custom.jumpEvents)
            HsEvents.jumpEventQueue.sortByDescending { it.priority } // stable
        }
        for (name in custom.clearDeathEvents) {
            if (name.isNotEmpty()) HsEvents.deathEventQueue.removeAll { it.label == name } else HsEvents.deathEventQueue.clear()
        }
        if (custom.deathEvents.isNotEmpty()) {
            val all = ArrayList(HsEvents.deathEventQueue)
            for (d in custom.deathEvents) {
                if (d.event.isEmpty() && d.label.isEmpty()) all.clear() // backwards compatibility
                else all.add(d)
            }
            all.sortByDescending { it.priority }
            HsEvents.deathEventQueue.clear()
            HsEvents.deathEventQueue.addAll(all)
        }
        HsTriggeredEvents.apply(game, custom.clearTriggeredEvents, custom.triggeredEvents, custom.triggeredEventModifiers)
        // CustomEvents.cpp WorldManager::CreateShip: the event's ship ends in an emergency jump when destroyed
        if (custom.invincible) game.enemy?.invincible = true
    }

    // ------------------------------------------------------------------ queued, jump and death events

    /**
     * CheckEventQueue (TriggeredEvents.cpp; WorldManager::OnLoop and PauseLoop while no choice box is open): queued
     * events load in order until one opens a choice box; then the triggered events (TriggerCheck).
     */
    fun checkEventQueue(game: InGameState, host: Host, choiceBoxOpen: () -> Boolean) {
        while (true) {
            val q = HsEvents.eventQueue.firstOrNull() ?: break
            loadEvent(game, host, q.event, false, q.seed, null)
            HsEvents.eventQueue.removeFirstOrNull()
            if (choiceBoxOpen()) return
        }
        // StarMap::waiting (the out-of-fuel wait) is not modelled: the engine has no such timer running here.
        if (game.player != null && !game.isPlayerJumping) HsTriggeredEvents.triggerCheck(game, host, choiceBoxOpen)
    }

    /** StarMap::Open: the jump events load in order (loop ones stay) until one opens a choice box. */
    fun openStarMap(game: InGameState, host: Host, choiceBoxOpen: () -> Boolean): Boolean {
        val it = HsEvents.jumpEventQueue.listIterator()
        while (it.hasNext()) {
            val j = it.next()
            loadEvent(game, host, j.event, false, -1, null)
            if (!j.loop) it.remove()
            if (choiceBoxOpen()) return true
        }
        return false
    }

    /** GameOver::OpenText: a queued death event replaces the game over (not after a victory). */
    fun onGameOver(game: InGameState, host: Host, victory: Boolean): Boolean {
        if (victory) return false
        val d = HsEvents.deathEventQueue.removeFirstOrNull() ?: return false
        deathEventActive = true
        loadEvent(game, host, d.event, false, -1, null)
        return true
    }

    @JvmField var deathEventActive = false
}
