// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (ShipManager::UpdateCrewmembers:
// damageEnemiesAmount / healCrewAmount; CrewMember::CheckSkills: noClone; CloneSystem::OnLoop: cloneSpeedMultiplier;
// CrewMember::Clone: cloneLoseSkills), src/game/Ship/CrewVTable.cpp (ValidTarget).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.stat.CrewStat

/**
 * Crew stats whose effect is a rule of its own (the stat values come from [CrewExtend.calculateStat]): the room
 * auras (damageEnemiesAmount, healCrewAmount), the clone rules (noClone, cloneSpeedMultiplier, cloneLoseSkills) and
 * validTarget. Only called while Hyperspace is active.
 */
object HsCrewEffects {
    /**
     * ShipManager::UpdateCrewmembers (after the crew loop) for one crew member of a race: a functional crew member
     * in a room damages the other side's crew there (damageEnemiesAmount per second) and heals its own side's other
     * crew there (healCrewAmount per second times their healSpeed), both at the room's time dilation ([dt] already is
     * the crew loop's dilated step, Ship.updateCrew). The healed
     * crew member gets HS's 1e-10 on fMedbay (read as "being healed" by MV's scripts).
     */
    @JvmStatic
    fun roomAuras(crew: AbstractCrew, dt: Float) {
        val ex = HsCrew.ex(crew)
        val def = ex.definition() ?: return
        val room = crew.room
        val owner = ex.powerOwner
        val damageEnemies = ex.calculateStat(CrewStat.DAMAGE_ENEMIES_AMOUNT, def) * dt
        if (ex.functional && damageEnemies != 0f) {
            for (other in room.ship.crew.toTypedArray()) {
                if (other === crew || other.room !== room || HsCrew.shipId(other) == owner) continue
                HsCrew.directModifyHealth(other, -damageEnemies)
            }
        }
        val healCrew = ex.calculateStat(CrewStat.HEAL_CREW_AMOUNT, def)
        if (ex.functional && healCrew != 0f) {
            val heal = dt * healCrew
            for (other in room.ship.crew.toTypedArray()) {
                if (other === crew || other.room !== room || HsCrew.shipId(other) != owner) continue
                val otherEx = HsCrew.ex(other)
                val otherDef = otherEx.definition()
                val mod = if (otherDef != null) otherEx.calculateStat(CrewStat.HEAL_SPEED_MULTIPLIER, otherDef) else 1f
                if (heal * mod > 0f && other.health != other.maxHealth) otherEx.healMark = true
                HsCrew.directModifyHealth(other, heal * mod)
            }
        }
    }

    /** CrewMember::CheckSkills (noClone): a dead crew member of a race with NO_CLONE is never cloned. */
    @JvmStatic
    fun cloneBlocked(crew: AbstractCrew): Boolean {
        val ex = crew.hsExtend ?: return false
        val timer = ex.deathTimer
        if (timer != null && timer.done()) return true
        val def = CustomCrewManager.definitionOrNull(crew.hsSpecies) ?: return false
        return ex.calculateBool(CrewStat.NO_CLONE, def)
    }

    /**
     * CloneSystem::OnLoop: the first crew member waiting in the queue, when it is of a race, advances by
     * (cloneSpeedMultiplier - 1) extra seconds per second; returns the extra seconds for this frame.
     */
    @JvmStatic
    fun cloneSpeedExtra(first: AbstractCrew?, dt: Float): Float {
        if (first == null) return 0f
        val ex = first.hsExtend ?: return 0f
        val def = CustomCrewManager.definitionOrNull(first.hsSpecies) ?: return 0f
        return (ex.calculateStat(CrewStat.CLONE_SPEED_MULTIPLIER, def) - 1f) * dt
    }

    /** CrewMember::Clone (priority -9999): a race with cloneLoseSkills false takes HS's own path, with no skill loss. */
    @JvmStatic
    fun cloneKeepsSkills(crew: AbstractCrew): Boolean {
        val ex = crew.hsExtend ?: return false
        val def = CustomCrewManager.definitionOrNull(crew.hsSpecies) ?: return false
        return !ex.calculateBool(CrewStat.CLONE_LOSE_SKILLS, def)
    }

    /**
     * CrewMember::ValidTarget(shipId) (CrewVTable.cpp): false for the dead, a crew member in its death animation or
     * one on the other ship; else the VALID_TARGET stat.
     */
    @JvmStatic
    fun validTarget(crew: AbstractCrew): Boolean {
        val ex = crew.hsExtend ?: return true
        val def = CustomCrewManager.definitionOrNull(crew.hsSpecies) ?: return true
        return ex.calculateBool(CrewStat.VALID_TARGET, def)
    }

    /**
     * CrewMember::GetNewGoal (priority 2000): CAN_PHASE_THROUGH_DOORS is evaluated and cached in
     * CrewMember_Extend::canPhaseThroughDoors, and a phasing crew member ignores sealed and locked-down doors.
     */
    @JvmStatic
    fun phases(crew: AbstractCrew): Boolean {
        val ex = crew.hsExtend ?: return false
        val def = CustomCrewManager.definitionOrNull(crew.hsSpecies) ?: return false
        val v = ex.calculateBool(CrewStat.CAN_PHASE_THROUGH_DOORS, def)
        ex.canPhaseThroughDoors = v
        return v
    }
}
