// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDrones.cpp (CrewAI::UpdateDrones hook,
// the droneAI block of a race: fightAI, repairAI, manAI, batteryAI, returnToDroneRoom).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.ai.ExtinguishFireTask
import xyz.znix.xftl.ai.RepairBreachTask
import xyz.znix.xftl.ai.RepairTask
import xyz.znix.xftl.ai.systemOrderKey
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Weapons

/**
 * The custom drone AI of a crew drone whose race has a `<droneAI>` block (DroneAI.hasCustomAI): HS blocks the
 * vanilla orders to it (CommandCrewMoveRoom / SetTask return early) and gives it these in CrewAI::UpdateDrones order:
 * fight (the nearest room with a boarder that is not full), repair (lowest priority problem), man (a system with
 * nobody), battery (the system most short of power, else a fixed list) and, when idle, back to the drone room.
 */
object HsDroneAI {
    /** True when [pawn] runs the custom AI (a friendly, non-controlled body of a race with droneAI). */
    @JvmStatic
    fun custom(pawn: AbstractCrew): Boolean {
        val def = CustomCrewManager.definitionOrNull(pawn.hsSpecies) ?: return false
        return def.droneAI.hasCustomAI
    }

    /** One CrewAI::UpdateDrones step for [pawn]; false when the pawn's race has no custom AI (vanilla runs). */
    @JvmStatic
    fun update(drone: AbstractIndoorsDrone, pawn: AbstractCrew): Boolean {
        val def = CustomCrewManager.definitionOrNull(pawn.hsSpecies) ?: return false
        val ai = def.droneAI
        if (!ai.hasCustomAI) return false
        if (pawn.health <= 0f || pawn.currentAction == AbstractCrew.Action.DYING) return true
        val ship = pawn.room.ship
        val here = pawn.room
        val busy = pawn.currentAction != AbstractCrew.Action.IDLE && pawn.currentAction != AbstractCrew.Action.MANNING
        val walking = pawn.pathingTarget != null

        fun go(room: Room): Boolean = !room.isLockedDown && (room == here || pawn.setTargetRoom(room))

        // fightAI: the nearest room with a boarder that has a free slot (or is the drone's own room)
        if (ai.fightAI && pawn.canFight) {
            val position = pawn.getPixelPositionCentre()
            var target: Room? = null
            var best = Float.MAX_VALUE
            for (room in ship.rooms) {
                val hostile = room.crew.any {
                    it.mode != pawn.mode && it.health > 0f && it.currentAction != AbstractCrew.Action.DYING
                }
                if (!hostile) continue
                if (room != here && !room.anySlotsFree(pawn.mode, pawn)) continue
                val dx = (position.x - room.pixelCentre.x).toFloat()
                val dy = (position.y - room.pixelCentre.y).toFloat()
                val d = dx * dx + dy * dy
                if (d < best) { best = d; target = room }
            }
            if (target != null && !target.isLockedDown) {
                if ((pawn.pathingTarget?.room ?: here) != target) pawn.setTargetRoom(target)
                return true
            }
        }

        // repairAI: an idle drone that can repair takes the lowest-priority fire, breach or damaged system
        if (!busy && !walking && ai.repairAI && pawn.canRepair) {
            val crewAi = ship.crewAI
            var bestTask: xyz.znix.xftl.ai.AITask? = null
            var best = Int.MAX_VALUE
            fun consider(task: xyz.znix.xftl.ai.AITask) {
                val p = task.basePriority(pawn)
                if (p < best && go(task.room)) { best = p; bestTask = task }
            }
            for (room in ship.rooms)
                if (room.fires.any { it != null } && room.oxygen > FireInstance.OXYGEN_CUTOFF) consider(ExtinguishFireTask(crewAi, room))
            for (room in ship.rooms)
                if (room.breaches.any { it != null }) consider(RepairBreachTask(crewAi, room))
            for (room in ship.rooms.sortedBy { systemOrderKey(it.system) })
                if (room.system?.damaged == true) consider(RepairTask(crewAi, room))
            if (bestTask != null) return true
        }

        // manAI: a system nobody mans (not the medbay, oxygen or piloting)
        if (!busy && !walking && ai.manAI && pawn.canManSystem) {
            for (room in ship.rooms) {
                val sys = room.system as? MainSystem ?: continue
                if (sys is xyz.znix.xftl.systems.Medbay || sys is xyz.znix.xftl.systems.Oxygen) continue
                if (room.crew.any { it !== pawn && it.mode == pawn.mode && it.currentAction == AbstractCrew.Action.MANNING }) continue
                if (go(room)) return true
            }
        }

        // batteryAI (a race that provides power) goes where power is short; otherwise idle
        var idle = true
        if (!busy && !walking && ai.batteryAI && (def.providesPower || def.bonusPower > 0)) {
            idle = false
            var bestDiff = 0
            var target: Room? = null
            for (room in ship.rooms) {
                val sys = room.system as? MainSystem ?: continue
                if (sys is Drones || sys is xyz.znix.xftl.systems.Medbay) continue
                var diff = sys.undamagedEnergy - sys.powerSelected
                if (sys is Weapons) diff = ship.hardpoints.sumOf { it.weapon?.type?.power ?: 0 } - sys.powerSelected
                if (diff > bestDiff && diff > 0 && go(room)) { bestDiff = diff; target = room }
            }
            if (target == null) {
                // priority list 3 (weapons), 0 (shields), 13 (clone bay), 1 (engines), 11 (artillery), 10 (cloaking), 15 (hacking)
                for (id in intArrayOf(3, 0, 13, 1, 11, 10, 15)) {
                    val room = ship.rooms.firstOrNull { xyz.znix.xftl.ai.nativeSystemId(it.system) == id } ?: continue
                    if (id == 3 && ship.hardpoints.sumOf { it.weapon?.type?.power ?: 0 } == 0) continue
                    if (go(room)) { target = room; break }
                }
            }
            if (target == null) idle = true else return true
        }

        // returnToDroneRoom: nothing to do, back to the drone system's room
        if (idle && ai.returnToDroneRoom && !busy && !walking) {
            val home = ship.rooms.firstOrNull { it.system is Drones } ?: return true
            if (here != home) go(home)
        }
        return true
    }
}
