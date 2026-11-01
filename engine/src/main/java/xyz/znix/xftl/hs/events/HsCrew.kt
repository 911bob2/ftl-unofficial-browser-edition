// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (HS_GetEligibleCrewList,
// HS_GetRandomCrewList, HS_GetRecursiveBlueprintList) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see
// docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState

/** The crew the HS event code may pick (crew loss, transformRace, choiceRequiresCrew). */
object HsCrew {
    /** Diagnostics: (event, stream state before the seed, seed, picked race) for every crew-loss pick. */
    @JvmField var pickTrace: ((String, String, Int, String) -> Unit)? = null

    /** Whether a crew member has no crew slot (hs.crew sets this; HS: CrewMember_Extend noSlot / NO_SLOT stat). */
    @JvmField var noSlot: ((LivingCrew) -> Boolean)? = null

    /** HS_GetEligibleCrewList(0, list, includeNoSlot): the player's crew (CrewMemberFactory order) of those races. */
    fun eligible(game: InGameState, races: List<String>, includeNoSlot: Boolean): List<LivingCrew> =
        game.playerCrew.filter { crew ->
            (races.isEmpty() || crew.codename in races) && (includeNoSlot || noSlot?.invoke(crew) != true)
        }

    /** HS_GetRecursiveBlueprintList: a blueprint list's names, with nested lists' names appended (one level each). */
    fun recursiveList(game: InGameState, name: String): List<String> {
        val list = ArrayList(HsReq.blueprintList(game, name))
        var i = 0
        while (i < list.size) {
            list.addAll(HsReq.blueprintList(game, list[i]))
            i++
        }
        return list
    }

    /** HS_GetRandomCrewList. */
    fun randomCrewList(game: InGameState, racePref: String, randomRaceAllowedIn: Boolean, noSlotAllowed: Boolean, noSlotForbiddenIn: Boolean): List<LivingCrew> {
        val noSlotForbidden = noSlotForbiddenIn || noSlotAllowed
        var randomRaceAllowed = randomRaceAllowedIn
        val list = recursiveList(game, racePref)
        var eligible: List<LivingCrew> = emptyList()
        if (list.isNotEmpty()) {
            eligible = eligible(game, list, noSlotAllowed)
            if (eligible.isEmpty() && !noSlotForbidden) eligible = eligible(game, list, true)
        } else if (racePref.isNotEmpty()) {
            val races = if (racePref == "random") emptyList() else listOf(racePref)
            eligible = eligible(game, races, noSlotAllowed)
            if (eligible.isEmpty() && !noSlotForbidden) eligible = eligible(game, races, true)
        } else {
            randomRaceAllowed = true
        }
        if (eligible.isEmpty() && randomRaceAllowed) {
            eligible = eligible(game, emptyList(), noSlotAllowed)
            if (eligible.isEmpty() && !noSlotForbidden) eligible = eligible(game, emptyList(), true)
        }
        return eligible
    }

    /** CrewStat::ESSENTIAL of a crew member (hs.crew/statcrew sets this; without it every crew member counts 0). */
    @JvmField var essential: ((LivingCrew) -> Float)? = null

    /**
     * HS_ShuffleSortCrewList: libstdc++ std::random_shuffle with `random32() % i` (i = 2..n, swapping element i-1 with
     * the drawn one), then, when min < max, a stable sort by the clamped ESSENTIAL stat.
     */
    fun shuffleSort(list: MutableList<LivingCrew>, minPriority: Float, maxPriority: Float) {
        for (i in 1 until list.size) {
            val j = ftl.browser.NativeRandom.next31() % (i + 1)
            if (j != i) { val t = list[i]; list[i] = list[j]; list[j] = t }
        }
        if (minPriority < maxPriority) {
            val stat = essential ?: return
            // std::stable_sort with "max(a, min) < min(b, max)": a key-wise stable sort on the clamped stat is the
            // same order when the clamps agree; HS's comparator only orders the clamped values.
            val keys = list.associateWith { stat(it) }
            val sorted = list.sortedWith { a, b ->
                when {
                    maxOf(keys.getValue(a), minPriority) < minOf(keys.getValue(b), maxPriority) -> -1
                    maxOf(keys.getValue(b), minPriority) < minOf(keys.getValue(a), maxPriority) -> 1
                    else -> 0
                }
            }
            list.clear(); list.addAll(sorted)
        }
    }

    /**
     * ShipManager::SelectRandomCrew's HS hook (priority 100), after the original picked [original]: the eligible crew
     * for [racePref] under the current event's allowNoSlot/blockNoSlot/ignoreEssential ([custom], none outside a
     * custom event), reseeded with [seed] unless it is -1, shuffled and sorted; the first one. The original's pick
     * stands when no crew member is eligible.
     */
    fun selectRandomCrew(game: InGameState, original: LivingCrew?, seed: Int, racePref: String, custom: CustomEvent?): LivingCrew? {
        val list = ArrayList(randomCrewList(game, racePref, true, custom?.allowNoSlot ?: false, custom?.blockNoSlot ?: false))
        if (list.isEmpty()) return original
        if (seed != -1) ftl.browser.NativeRandom.seed(seed)
        shuffleSort(list, custom?.ignoreEssentialMin ?: Float.NEGATIVE_INFINITY, custom?.ignoreEssentialMax ?: Float.POSITIVE_INFINITY)
        return list[0]
    }

    /**
     * The crew pick of a `<transformRace>` (CreateChoiceBox and ModifyResources hooks): lastSelectedCrewSeed (drawn
     * if unset), the original SelectRandomCrew's draws, then HS's. The transformation itself is not ported yet
     * (reported unsupported by the caller); the draws keep the stream as HS has it.
     */
    fun transformRacePick(game: InGameState, custom: CustomEvent): LivingCrew? {
        if (game.playerCrew.isEmpty()) return null
        val seed = game.eventManager.crewKillSeed()
        val all = game.playerCrew.filter { it.ownerShip == game.player }
        var candidates = all.filter { it.codename == custom.transformRaceFrom }
        if (candidates.isEmpty()) candidates = all
        var original: LivingCrew? = null
        if (candidates.isNotEmpty()) {
            ftl.browser.NativeRandom.seed(seed)
            original = candidates[ftl.browser.NativeRandom.nextInt(candidates.size)]
        }
        return selectRandomCrew(game, original, seed, custom.transformRaceFrom, custom)
    }
}
