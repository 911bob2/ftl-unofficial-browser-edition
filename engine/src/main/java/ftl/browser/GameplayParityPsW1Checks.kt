package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep W1 (sector map, travel, rebel fleet): the fleet's move on a jump and the map's warning marks.
 *
 * - A jump runs StarMap::UpdateDangerZone's direct branch from StarMap::GetNewLocation (1.6.14 0x5f5280 / 0x5f5680,
 *   1.6.9 0x5d0f30 / 0x5d1310): the centre moves by GetNextDangerMove while it is under 60 and is then held at 60,
 *   and one pass over the beacons in list order takes those inside the zone, each with its fleet event drawn then.
 *   A wait (the animated branch) keeps the step order and has no hold at 60.
 * - StarMap::RenderLabels (1.6.9 0x5c9070) marks a beacon inside the next move's reach (Location::fleetChanging) and
 *   keeps the mark until the fleet takes it.
 * - StarMap::pursuitDelay (+0x498) belongs to the map: no sector change writes it (1.6.14 GenerateMap 0x5f8ca0,
 *   AdvanceWorldLevel 0x5edec0), so what the old sector left goes on in the next (oracle ps-w1-ops, secret warp).
 */
internal object GameplayParityPsW1Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val streamBefore = NativeRandom.snapshot()
        val parts = ArrayList<String>()
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        try {
            parts += jumpOrder(state)
            parts += hold(state)
            parts += marks(state)
            parts += currentLabels(state)
            parts += carry(state)
        } finally {
            state.eventCreator.usedNamesFixture = null
            state.eventManager.resetEventState()
            NativeRandom.restore(streamBefore)
        }
        return "ps-w1: " + parts.joinToString(", ")
    }

    private class Memo(val beacon: Beacon) {
        val overtaken = beacon.isOvertaken
        val fleet = beacon.hasFleetEvent
        val forced = beacon.forcedNebula
        val created = beacon.createdEvent
        val marked = beacon.browserFleetThreatened
        val cleared = beacon.eventCleared
        val quest = beacon.hasQuest
        fun restore() {
            beacon.isOvertaken = overtaken; beacon.hasFleetEvent = fleet; beacon.forcedNebula = forced
            beacon.createdEvent = created; beacon.browserFleetThreatened = marked; beacon.eventCleared = cleared
            beacon.hasQuest = quest
        }
    }

    private inline fun <T> keep(sector: Sector, block: () -> T): T {
        val memos = sector.beacons.map { Memo(it) }
        val x = sector.dangerZoneCentre.x
        val modifier = sector.fleetAdvanceModifier
        try {
            return block()
        } finally {
            memos.forEach { it.restore() }
            sector.dangerZoneCentre.x = x
            sector.fleetAdvanceModifier = modifier
            sector.hsJumpTarget = null
        }
    }

    /** A jump takes the beacons in list order: the same fleet events as overtaking them one by one in that order. */
    private fun jumpOrder(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val beacons = sector.beacons
        val xs = IntArray(beacons.size) { beacons[it].pos.x }
        val ys = IntArray(beacons.size) { beacons[it].pos.y }
        val cy = sector.dangerZoneCentre.y
        val taken = BooleanArray(beacons.size) { beacons[it].isOvertaken }
        val move = sector.getFleetAdvanceFor(state.currentBeacon)
        check(move == 64 || move == 32 || move == 51) { "Start beacon's fleet move $move" }
        // A start where the zone's edge reaches the beacons in an order other than the list's.
        var start = Int.MIN_VALUE
        var listOrder = emptyList<Int>()
        for (x in -1100..-200) {
            val stepped = Sector.overtakeOrder(xs, ys, taken, cy, x, x + move)
            val inside = beacons.indices.filter { !taken[it] && dist(xs[it], ys[it], x + move, cy) < 767f }
            if (stepped.size >= 2 && stepped != inside) {
                start = x; listOrder = inside; break
            }
        }
        check(start != Int.MIN_VALUE) { "No start whose jump takes beacons out of list order" }
        val target = beacons.first { it !in listOrder.map { i -> beacons[i] } && it !== state.currentBeacon }
        state.eventCreator.usedNamesFixture = listOf()

        // Overtaking each beacon in list order, the reference.
        val reference = keep(sector) {
            state.eventManager.restoreEventState()
            state.eventCreator.refreshUsedNames()
            sector.hsJumpTarget = target // Hyperspace's runFromFleet leaves the jump target out
            NativeRandom.seed(424242)
            for (i in listOrder) sector.overtake(state, beacons[i])
            listOrder.map { beacons[it].createdEvent.shipSeed to beacons[it].createdEvent.template.deserialisationId }
        }
        val (jumped, end) = keep(sector) {
            state.eventManager.restoreEventState()
            state.eventCreator.refreshUsedNames()
            sector.dangerZoneCentre.x = start
            sector.hsJumpTarget = target
            NativeRandom.seed(424242)
            sector.advanceFleet(state, state.currentBeacon)
            val got = listOrder.map { beacons[it].createdEvent.shipSeed to beacons[it].createdEvent.template.deserialisationId }
            val others = beacons.indices.filter { beacons[it].isOvertaken && !taken[it] && it !in listOrder }
            check(others.isEmpty()) { "The jump also took $others" }
            got to sector.dangerZoneCentre.x
        }
        state.eventManager.restoreEventState()
        check(end == start + move) { "Jump from $start moved the zone to $end" }
        check(jumped == reference) { "Jump from $start took ${listOrder}: events $jumped, in list order $reference" }
        return "jump takes ${listOrder.size} beacons in list order"
    }

    /** The jump holds the centre at 60; the wait does not; the pursuit modifier counts down on both. */
    private fun hold(state: InGameState): String {
        val sector = state.currentBeacon.sector
        val target = sector.beacons.first { it !== state.currentBeacon }
        val move = sector.getFleetAdvanceFor(state.currentBeacon)
        val results = ArrayList<String>()
        for ((from, jump, modifier) in listOf(Triple(30, true, 0), Triple(100, true, 0), Triple(-10, true, 0), Triple(30, false, 0),
                Triple(30, true, 2), Triple(30, true, -1))) {
            results += keep(sector) {
                sector.dangerZoneCentre.x = from
                sector.fleetAdvanceModifier = modifier
                sector.hsJumpTarget = if (jump) target else null
                NativeRandom.seed(5)
                state.eventManager.restoreEventState()
                sector.advanceFleet(state, state.currentBeacon)
                "${sector.dangerZoneCentre.x}/${sector.fleetAdvanceModifier}"
            }
        }
        state.eventManager.restoreEventState()
        // UpdateDangerZone: a jump from 30 or 100 ends at 60, from -10 at 54 (+64); a wait from 30 at 94; the modifier
        // steps towards zero; under a negative one the fleet stays.
        val wanted = listOf("60/0", "60/0", "${minOf(-10 + move, 60)}/0", "${30 + move}/0", "60/1", "30/0")
        check(results == wanted) { "Fleet moves $results, the original's $wanted" }
        return "jump holds the zone at 60"
    }

    /** A beacon marked once stays marked when the next move's reach shrinks; taking it clears the mark. */
    private fun marks(state: InGameState): String {
        val sector = state.currentBeacon.sector
        return keep(sector) {
            sector.beacons.forEach { it.browserFleetThreatened = false }
            val cy = sector.dangerZoneCentre.y
            val byX = sector.beacons.filter { !it.isOvertaken }.sortedBy { it.pos.x }
            val far = byX[byX.size / 2]
            // The zone's edge 1 px short of the beacon with a move of 128, beyond it with 64.
            val dy = (far.pos.y - cy).toFloat()
            val reach = kotlin.math.sqrt(767f * 767f - dy * dy)
            val x = kotlin.math.floor(far.pos.x - reach).toInt() - 127
            sector.dangerZoneCentre.x = x
            sector.markFleetChanging(Point(x + 128, cy))
            check(far.browserFleetThreatened) { "The beacon inside the reach was not marked" }
            sector.markFleetChanging(Point(x + 64, cy))
            check(far.browserFleetThreatened) { "A shrinking reach cleared the mark" }
            val inside = sector.beacons.filter { !it.isOvertaken && dist(it.pos.x, it.pos.y, x + 64, cy) < 767f }
            check(inside.all { it.browserFleetThreatened }) { "A beacon inside the reach was not marked" }
            state.eventManager.restoreEventState()
            NativeRandom.seed(9)
            sector.overtake(state, far)
            check(!far.browserFleetThreatened) { "The taken beacon kept its mark" }
            state.eventManager.restoreEventState()
            "marks stay until taken"
        }
    }

    /**
     * StarMap::RenderLabels has no visited test for the distress and repair labels; ClearEvent on leaving clears the
     * flags. The beacon the ship is at (visited) keeps them, a beacon left behind does not.
     */
    private fun currentLabels(state: InGameState): String {
        val here = state.currentBeacon
        check(here.visited) { "The current beacon is not visited" }
        val cleared = here.eventCleared
        try {
            here.eventCleared = false
            check(xyz.znix.xftl.game.JumpWindow.eventFlagsLive(here)) { "The current beacon's event flags count as cleared" }
            here.clearEvent(false, false)
            check(!xyz.znix.xftl.game.JumpWindow.eventFlagsLive(here)) { "A cleared event still shows its flags" }
        } finally {
            here.eventCleared = cleared
        }
        return "current beacon keeps its labels"
    }

    /** Entering the next sector keeps the pursuit modifier the old sector had left (no Distraction Buoys here). */
    private fun carry(state: InGameState): String {
        state.currentBeacon.sector.fleetAdvanceModifier = 2
        val next = state.gameMap.generateSector(state.gameMap.sectors[1].first(), state)
        state.currentBeacon = next.startBeacon
        check(next.fleetAdvanceModifier == 2) { "The next sector's pursuit ${next.fleetAdvanceModifier}, expected 2" }
        return "pursuit carried"
    }

    private fun dist(x: Int, y: Int, cx: Int, cy: Int): Float {
        val dx = (x - cx).toFloat(); val dy = (y - cy).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
