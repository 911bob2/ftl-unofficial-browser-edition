// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (StarMap::AddQuest and the quest
// GetBaseEvent hook) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import ftl.browser.NativeRandom
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.sector.Sector

/**
 * HS's StarMap::AddQuest around the original placement ([Sector.addQuest]): the `<quest>` options restrict the
 * beacons (through the original's nebula test), seeded runs place quests with the quest seed, aggressive quests try
 * again, and the saved/delayed names carry the seed as "QUEST\t<seed>\t<event>[\tD]".
 */
object HsQuests {
    /** The CreateChoiceBox text fixes the last AddQuest asks for. */
    @JvmField var actuallyEnoughTime = false
    @JvmField var nextSectorPopped = false

    /**
     * AddQuest(name, force) for [sector] with the player at [current]. Returns whether the quest was placed;
     * a failed one may be appended to [delayed] (the original's rule: not from the last sector on).
     */
    fun addQuest(game: InGameState, sector: Sector, current: Beacon, name: String, forceIn: Boolean, delayed: MutableList<String>): Boolean {
        var force = forceIn
        val parser = game.eventManager.hs!!
        var savedSeed = 0
        var nextQuestSeed = HsSeeds.questSeed
        val numDelayed = delayed.size
        if (HsSeeds.enabled) savedSeed = NativeRandom.next31()

        val questEvent: String
        val isNextSector: Boolean
        val isSeeded: Boolean
        if (name.startsWith("QUEST\t")) {
            val parts = name.substring(6).split('\t')
            HsSeeds.questSeed = parts[0].toIntOrNull() ?: 0
            questEvent = parts.getOrElse(1) { "" }
            isNextSector = parts.size > 2 && parts[2] == "D"
            isSeeded = true
        } else {
            questEvent = name
            isNextSector = false
            isSeeded = false
        }

        val quest = parser.defaultQuest.copy()
        parser.getCustomQuest(questEvent)?.let { quest.add(it) }
        if (!quest.createNebula.enabled) quest.createNebula.value = quest.nonNebulaBeacon.value && quest.nebulaEvent.value.isEmpty()
        if (!quest.sectorEight.enabled) quest.sectorEight.value = quest.lastStand.value

        actuallyEnoughTime = false
        nextSectorPopped = false

        // The restricted beacons: HS reuses the original's nebula test.
        val restricted = HashMap<Beacon, Boolean>()
        for (b in sector.beacons) {
            var r = if (b.isMapNebula) !quest.nebulaBeacon.value else !quest.nonNebulaBeacon.value
            if (!quest.currentSector.value && !isNextSector) r = true
            if (HsFlow.customFor(game, b.createdEvent)?.preventQuest == true) r = true
            restricted[b] = r
        }

        val bossLevel = sector.type.name == "FINAL"
        var worldLevel = sector.sectorNumber
        if (quest.sectorEight.value && (worldLevel > 5 || bossLevel)) {
            worldLevel = 5
            if (quest.lastStand.value) actuallyEnoughTime = true
        }
        if (bossLevel) force = quest.lastStand.value
        if (force) for (b in sector.beacons) if (b.isOvertaken || b.browserFleetThreatened) restricted[b] = true

        // The quest event is created once (GetBaseEvent hook) and reused by every placement try.
        var questCreated: CreatedEvent? = null
        var createdOnce = false
        val create: () -> CreatedEvent? = {
            if (!createdOnce) {
                createdOnce = true
                questCreated = HsFlow.getBaseEvent(game, questEvent, sector.sectorNumber, false, -1)
            }
            questCreated
        }
        fun tryPlace(forced: Boolean): Boolean {
            if (HsSeeds.enabled) NativeRandom.seed(HsSeeds.questSeed)
            val placement = sector.addQuest(current, game.eventCreator, questEvent, forced, create) { restricted[it] == true }
            val placed = placement == Sector.QuestPlacement.PLACED
            if (!placed && placement == Sector.QuestPlacement.NO_SPACE && worldLevel < 6) delayed += questEvent
            if (HsSeeds.enabled && !isSeeded) nextQuestSeed = NativeRandom.next31()
            return placed
        }

        val before = sector.beacons.filter { it.hasQuest }.toSet()
        var ret = tryPlace(force)
        if (!ret && quest.aggressive.value != 0) {
            // StarMap::WillBeOvertaken (StarMap.cpp): inside the danger zone after its next move (GetNextDangerMove,
            // none once the centre is at x >= 60), squared float distance under the radius squared.
            val nextX = (sector.dangerZoneCentre.x + sector.getFleetAdvanceFor(current)).toFloat()
            val centreY = sector.dangerZoneCentre.y.toFloat()
            val r = Sector.DANGER_ZONE_RADIUS.toFloat()
            for (b in sector.beacons) {
                if (b.isOvertaken || b.browserFleetThreatened) { restricted[b] = true; continue }
                val dx = b.pos.x - nextX
                val dy = b.pos.y - centreY
                if (dx * dx + dy * dy < r * r) restricted[b] = true
            }
        }
        if (!ret && quest.aggressive.value == 2) {
            if (delayed.size > numDelayed) delayed.removeAt(delayed.size - 1)
            ret = tryPlace(true)
        }
        if (!ret && !quest.nextSector.value && delayed.size > numDelayed) {
            delayed.removeAt(delayed.size - 1)
            nextSectorPopped = true
        }
        if (!ret && quest.aggressive.value == 1 && delayed.size == numDelayed) ret = tryPlace(true)

        if (ret) {
            val placedAt = sector.beacons.firstOrNull { it.hasQuest && it !in before }
            if (placedAt != null && placedAt.isMapNebula) {
                if (quest.nebulaEvent.value.isNotEmpty()) {
                    if (HsSeeds.enabled) NativeRandom.seed(HsSeeds.questSeed)
                    HsFlow.getBaseEvent(game, quest.nebulaEvent.value, worldLevel, false, -1)?.let {
                        placedAt.createdEvent = it
                        placedAt.questName = quest.nebulaEvent.value
                    }
                }
                if (quest.createNebula.value) placedAt.forcedNebula = true
                if (quest.removeNebula.value) { placedAt.inNebula = false; placedAt.forcedNebula = false }
            }
        }
        if (!ret && delayed.size > numDelayed) delayed[numDelayed] = "QUEST\t${HsSeeds.questSeed}\t$questEvent\tD"

        if (HsSeeds.enabled) {
            HsSeeds.questSeed = nextQuestSeed
            NativeRandom.seed(savedSeed)
        }
        return ret
    }
}
