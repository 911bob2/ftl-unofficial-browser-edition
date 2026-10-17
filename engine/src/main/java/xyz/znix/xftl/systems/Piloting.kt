package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader

class Piloting(blueprint: SystemBlueprint) : SubSystem(blueprint) {
    /**
     * Get the manned evasion bonus, in percent.
     */
    val evasion: Int
        // Note that the crew member's bonus is only applied when they're
        // actually manning the system - being in the room isn't enough.
        get() = getBoostSkillLevel(Skill.PILOTING)?.let { SKILL_BONUSES[it.ordinal] } ?: 0

    override val sortingType = SortingType.PILOTING

    val evasionMultiplier: Float
        get() {
            // If piloting is broken or hacked, you get no evasion
            if (undamagedEnergy == 0 || isHackActive)
                return 0f

            // Any crew able to man it anywhere in the cockpit gives the full evasion; only the skill bonus
            // ([evasion]) needs the pilot at the console.
            if (hasPilot || ship.isAutoScout)
                return 1f

            // At this point, the system is not broken and we don't have a pilot.
            // ShipManager::GetDodgeFactor (0x49fe90) runs the autopilot on an
            // unmanned piloting system of power 2 or more: half the evasion at
            // exactly power 2 (0x879e04), 0.8 of it from power 3 (0x879df8).
            return when (undamagedEnergy) {
                0, 1 -> 0f
                2 -> 0.5f
                else -> 0.8f
            }
        }

    /**
     * ShipSystem +0x45 (bManned): ShipManager::OnLoop (0x4aa670) resets it to the automated flag, then the crew loop
     * (0x47b190) sets it for every crew member in the system's room that CrewMember::CanMan - alive, not dying, not
     * stunned - wherever in the room they stand. GetDodgeFactor (0x49fe90) and the FTL charge read this flag.
     */
    val hasPilot: Boolean
        get() = !ship.browserMannedStale && room!!.crew.any {
            it.mode == AbstractCrew.SlotType.CREW && it.canManSystem && it.stunTime <= 0f && it.health > 0f
                    && it.currentAction != AbstractCrew.Action.DYING
        }

    override fun isMannableBy(crew: AbstractCrew): Boolean {
        if (ship.engines!!.powerSelected == 0) {
            return false
        }

        return super.isMannableBy(crew)
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.PILOTING))
    }

    // Nothing to serialise
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    companion object {
        val INFO: SystemInfo = PilotingInfo

        val SKILL_BONUSES = listOf(5, 7, 10)
    }
}

private object PilotingInfo : SystemInfo("pilot") {
    override val canBeManned: Boolean get() = true
    override val isSubSystem: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Piloting(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["pilot_1"]
            1 -> translator["pilot_2"]
            2 -> translator["pilot_3"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}
