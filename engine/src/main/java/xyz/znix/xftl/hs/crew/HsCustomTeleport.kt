// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewMember_Extend::CanTeleportMove,
// CompleteShip::CheckTeleportMovement, the CombatControl::GetTeleportationCommand, CrewControl::RButton and
// ShipManager::CommandCrewMoveRoom hooks). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.layout.Room

/**
 * Hyperspace custom teleport movement (`teleportMove` / `teleportMoveOtherShip` crew stats): a crew member with
 * teleportMove teleports to its goal instead of walking, and the player can right-click a room (on the other ship
 * with teleportMoveOtherShip) to arm a teleport there. The armed teleport is [AbstractCrew.hsTeleShip] / hsTeleRoom /
 * hsTeleSlot (customTele); the teleport itself is [AbstractCrew.hsStartTeleportMove].
 */
object HsCustomTeleport {
    private fun dying(crew: AbstractCrew) = crew.currentAction == AbstractCrew.Action.DYING

    /** CrewAnimation status 6: a teleport is playing (either half). */
    private fun teleportAnim(crew: AbstractCrew) = crew.isTeleporting

    /** CrewMember::AtFinalGoal. */
    private fun atFinalGoal(crew: AbstractCrew) = crew.pathingTarget == null && crew.standingPosition != null

    /** CrewMember::GetControllable (_HS_GetControllable): alive and on the player's side. */
    private fun controllable(crew: AbstractCrew): Boolean {
        if (crew.health <= 0f) return false
        val id = HsCrew.shipId(crew)
        val mc = (crew as? LivingCrew)?.isMindControlled == true
        if (id == 0 && !mc) return true
        if (id == 1 && mc) return crew.room.ship.sys.player?.hasAugment("MIND_ORDER") == true
        return false
    }

    /** CrewMember_Extend::IsController(false): controllable and CONTROLLABLE. */
    private fun isController(crew: AbstractCrew): Boolean {
        val ex = HsCrew.ex(crew)
        return controllable(crew) && ex.functional && ex.calculateBool(CrewStat.CONTROLLABLE, ex.definition())
    }

    private fun flag(crew: AbstractCrew, stat: CrewStat): Boolean {
        val ex = HsCrew.ex(crew)
        return ex.calculateBool(stat, ex.definition())
    }

    /** CrewMember_Extend::CanTeleportMove. */
    @JvmStatic
    fun canTeleportMove(crew: AbstractCrew, toOtherShip: Boolean): Boolean {
        if (dying(crew)) return false
        if (!toOtherShip) return flag(crew, CrewStat.TELEPORT_MOVE)
        val ex = HsCrew.ex(crew)
        // Anti-kidnapping: the player's own crew under mind control on the player ship.
        if (ex.mindControlled && ex.shipId == 0 && ex.currentShipId == 0) return false
        if (crew is AbstractIndoorsDrone.Pawn) return false
        if (!flag(crew, CrewStat.CAN_TELEPORT)) return false
        if (!flag(crew, CrewStat.TELEPORT_MOVE_OTHER_SHIP)) return false
        val sys = crew.room.ship.sys
        val owner = ex.powerOwner
        val enemy = sys.enemy?.takeIf { sys.isShipPresent(it) }
        val enemyHostile = enemy != null && sys.player?.let { sys.getEnemyOf(it) } === enemy
        if (ex.currentShipId == owner && !enemyHostile) return false
        val other = (if (owner == 0) enemy else sys.player) ?: return true
        if (other.isCloakActive) return false
        if ((other === sys.player || enemyHostile) && other.superShield > 0) {
            val crewShip = (if (owner == 0) sys.player else enemy) ?: return false
            return xyz.znix.xftl.hs.events.HsReq.hasEquipment(sys, crewShip, AugmentBlueprint.ZOLTAN_BYPASS) > 0
        }
        return true
    }

    /** CustomCrew.cpp InitiateTeleport: arm the teleport and start leaving. */
    private fun initiate(crew: AbstractCrew, ship: Ship, room: Room, slot: Int) {
        crew.hsTeleShip = HsCrew.shipIdOf(ship)
        crew.hsTeleRoom = room.id
        crew.hsTeleSlot = slot
        crew.hsStartTeleportMove(room)
    }

    private fun slotOf(p: xyz.znix.xftl.math.RoomPoint): Int = p.room.pointToSlot(p)

    /**
     * The GetTeleportationCommand hook: CheckTeleportMovement on the player's ship and then on the enemy's, once a
     * frame from the player ship's loop (the teleporter's timing).
     */
    @JvmStatic
    fun onLoop(game: InGameState) {
        if (!HS.active) return
        game.player?.let { check(game, it) }
        game.enemy?.takeIf { game.isShipPresent(it) }?.let { check(game, it) }
    }

    /** CompleteShip::CheckTeleportMovement: the crew standing on [ship]. */
    private fun check(game: InGameState, ship: Ship) {
        var teleports = false
        for (crew in ship.crew.toList()) {
            if (crew.hsTeleShip == -1) {
                // No armed teleport: a teleportMove crew member teleports to the slot it would walk to.
                if (crew.health > 0f && HsCrew.ex(crew).functional && crew.stunTime <= 0f && !crew.hsTeleporting &&
                    !teleportAnim(crew) && !atFinalGoal(crew) && canTeleportMove(crew, false)
                ) {
                    val goal = crew.currentSlotPoint
                    var go = crew.room != goal.room
                    if (!go) {
                        val ex = HsCrew.ex(crew)
                        go = !flag(crew, CrewStat.CAN_MOVE) || ex.calculateStat(CrewStat.MOVE_SPEED_MULTIPLIER) == 0f
                    }
                    if (go) {
                        initiate(crew, ship, goal.room, slotOf(goal))
                        teleports = true
                    }
                }
                continue
            }
            if ((crew.hsTeleporting || teleportAnim(crew)) && crew.isTeleportLeaving) continue
            val currentId = HsCrew.shipIdOf(ship)
            if (crew.health <= 0f || !HsCrew.ex(crew).functional || !canTeleportMove(crew, currentId != crew.hsTeleShip)) {
                crew.hsTeleShip = -1
                continue
            }
            if (crew.stunTime > 0f) continue
            val target = (if (crew.hsTeleShip == 0) game.player else game.enemy) ?: run { crew.hsTeleShip = -1; null } ?: continue
            if (crew.hsTeleRoom == -1) crew.hsTeleRoom = HsCrew.random32() % target.rooms.size
            val room = target.rooms.getOrNull(crew.hsTeleRoom) ?: run { crew.hsTeleShip = -1; null } ?: continue
            // An AI crew member's teleport within its ship: its slot when the room matches, else the slot FindSlot gives
            // in the teleport room (Ship::GetAvailableRoomSlot: the first free one), picked before leaving.
            if (!controllable(crew) && crew.hsTeleShip == currentId && crew.hsTeleRoom != -1) {
                if (room == crew.currentSlotPoint.room) crew.hsTeleSlot = slotOf(crew.currentSlotPoint)
                else (0 until room.cellCount).firstOrNull { room.isSlotFree(room.slotToPoint(it), crew.mode) }?.let { crew.hsTeleSlot = it }
            }
            crew.hsStartTeleportMove(room)
            teleports = true
        }
        if (teleports) game.sounds.getSample("teleport").play()
    }

    /**
     * The CrewControl::RButton hook, after the room order: the selected crew that may teleport to [room] of [ship]
     * get an armed teleport there, the clicked [tile] (alternateCrewMovement, else -1) first, then the room's console
     * slot, then its free slots, then the others.
     */
    @JvmStatic
    fun rButton(ship: Ship, room: Room, tile: Int, selected: List<AbstractCrew>) {
        if (!HS.active || selected.isEmpty()) return
        val game = ship.sys
        val shipId = HsCrew.shipIdOf(ship)
        val teleporting = ArrayList<AbstractCrew>()
        for (crew in selected) {
            if (!isController(crew)) continue
            if (crew.hsTeleporting && crew.isTeleportLeaving) continue
            crew.hsTeleShip = -1
            val currentId = HsCrew.currentShipId(crew)
            if (!canTeleportMove(crew, currentId != shipId)) continue
            if (currentId != shipId || crew.room != room) {
                teleporting += crew
            } else if (!flag(crew, CrewStat.CAN_MOVE) || HsCrew.ex(crew).calculateStat(CrewStat.MOVE_SPEED_MULTIPLIER) == 0f) {
                teleporting += crew
            }
        }
        if (teleporting.isEmpty()) return

        val size = room.cellCount
        val tileIdx = if (tile in 0 until size) tile else -1
        val filled = BooleanArray(size)
        val all = ArrayList<AbstractCrew>()
        game.player?.let { all += it.crew }
        game.enemy?.takeIf { it !== game.player }?.let { all += it.crew }
        for (crew in all) {
            val mc = (crew as? LivingCrew)?.isMindControlled == true
            if (crew.health <= 0f || HsCrew.shipId(crew) != (if (mc) 1 else 0)) continue
            if ((crew.hsTeleporting && crew.isTeleportLeaving) || crew.hsTeleShip != shipId || crew.hsTeleRoom != room.id ||
                crew.hsTeleSlot == -1) continue
            if (crew.hsTeleSlot == tileIdx) {
                crew.hsTeleSlot = -1
                teleporting += crew
            } else if (crew.hsTeleSlot in 0 until size) {
                filled[crew.hsTeleSlot] = true
            }
        }
        for (crew in teleporting) {
            val slot = crew.currentSlotPoint
            if (HsCrew.currentShipId(crew) == shipId && slot.room == room && !filled[slotOf(slot)]) {
                crew.hsTeleShip = shipId
                crew.hsTeleRoom = room.id
                crew.hsTeleSlot = slotOf(slot)
                filled[slotOf(slot)] = true
            }
        }
        val order = ArrayList<Int>()
        if (tileIdx != -1 && !filled[tileIdx]) { order += tileIdx; filled[tileIdx] = true }
        val primary = room.system?.configuration?.computerPoint?.let { room.pointToSlot(it) } ?: -1
        if (primary != -1 && !filled[primary]) { order += primary; filled[primary] = true }
        // Room::slots[iShipId]: the slots of the player's side on that ship.
        val side = if (shipId == 0) AbstractCrew.SlotType.CREW else AbstractCrew.SlotType.INTRUDER
        for (i in 0 until size) {
            if (room.isSlotFree(room.slotToPoint(i), side) && !filled[i]) { order += i; filled[i] = true }
        }
        for (i in 0 until size) if (!filled[i]) order += i
        var i = 0
        for (crew in teleporting) {
            if (crew.hsTeleShip != -1) continue
            crew.hsTeleShip = shipId
            crew.hsTeleRoom = room.id
            crew.hsTeleSlot = order.getOrElse(i) { -1 }
            i++
        }
    }

    /**
     * The ShipManager::CommandCrewMoveRoom hook for AI orders: no orders while teleporting out within the ship (the slot
     * stays reserved), and a teleportMove crew member that cannot walk to [room] arms a teleport there instead.
     */
    @JvmStatic
    fun aiMove(crew: AbstractCrew, room: Room): Boolean {
        if (!HS.active) return crew.setTargetRoom(room)
        val currentId = HsCrew.currentShipId(crew)
        if ((crew.hsTeleporting || teleportAnim(crew)) && crew.isTeleportLeaving && crew.hsTeleShip == currentId) return false
        if (crew.setTargetRoom(room)) return true
        if (crew.health <= 0f || room == crew.room) return false
        if (crew.hsTeleporting || teleportAnim(crew)) return false
        if (!HsCrew.ex(crew).functional || crew.stunTime > 0f) return false
        if ((0 until room.cellCount).none { room.isSlotFree(room.slotToPoint(it), crew.mode) }) return false
        if (!canTeleportMove(crew, false)) return false
        crew.hsTeleShip = HsCrew.shipIdOf(room.ship)
        crew.hsTeleRoom = room.id
        crew.hsTeleSlot = -1
        return true
    }
}
