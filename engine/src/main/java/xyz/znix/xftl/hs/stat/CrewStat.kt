// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/StatBoost.h / StatBoost.cpp
package xyz.znix.xftl.hs.stat

/**
 * Hyperspace's `CrewStat`, in HS's order (the ordinal is the Lua `Hyperspace.CrewStat` value and the index into the
 * per-crew stat cache). [xmlName] is the `<statBoost name="...">` spelling (HS `crewStats`). The first
 * [CACHED] entries are cached once per stat frame.
 */
enum class CrewStat(val xmlName: String) {
    MAX_HEALTH("maxHealth"),
    STUN_MULTIPLIER("stunMultiplier"),
    MOVE_SPEED_MULTIPLIER("moveSpeedMultiplier"),
    REPAIR_SPEED_MULTIPLIER("repairSpeed"),
    DAMAGE_MULTIPLIER("damageMultiplier"),
    RANGED_DAMAGE_MULTIPLIER("rangedDamageMultiplier"),
    DOOR_DAMAGE_MULTIPLIER("doorDamageMultiplier"),
    FIRE_REPAIR_MULTIPLIER("fireRepairMultiplier"),
    SUFFOCATION_MODIFIER("suffocationModifier"),
    FIRE_DAMAGE_MULTIPLIER("fireDamageMultiplier"),
    OXYGEN_CHANGE_SPEED("oxygenChangeSpeed"),
    DAMAGE_TAKEN_MULTIPLIER("damageTakenMultiplier"),
    CLONE_SPEED_MULTIPLIER("cloneSpeedMultiplier"),
    PASSIVE_HEAL_AMOUNT("passiveHealAmount"),
    TRUE_PASSIVE_HEAL_AMOUNT("truePassiveHealAmount"),
    TRUE_HEAL_AMOUNT("trueHealAmount"),
    PASSIVE_HEAL_DELAY("passiveHealDelay"),
    ACTIVE_HEAL_AMOUNT("healAmount"),
    SABOTAGE_SPEED_MULTIPLIER("sabotageSpeedMultiplier"),
    ALL_DAMAGE_TAKEN_MULTIPLIER("allDamageTakenMultiplier"),
    HEAL_SPEED_MULTIPLIER("healSpeed"),
    HEAL_CREW_AMOUNT("healCrewAmount"),
    DAMAGE_ENEMIES_AMOUNT("damageEnemiesAmount"),
    BONUS_POWER("bonusPower"),
    POWER_DRAIN("powerDrain"),
    ESSENTIAL("essential"),
    CAN_FIGHT("canFight"),
    CAN_REPAIR("canRepair"),
    CAN_SABOTAGE("canSabotage"),
    CAN_MAN("canMan"),
    CAN_TELEPORT("canTeleport"),
    CAN_SUFFOCATE("canSuffocate"),
    CONTROLLABLE("controllable"),
    CAN_BURN("canBurn"),
    IS_TELEPATHIC("isTelepathic"),
    RESISTS_MIND_CONTROL("resistsMindControl"),
    IS_ANAEROBIC("isAnaerobic"),
    CAN_PHASE_THROUGH_DOORS("canPhaseThroughDoors"),
    DETECTS_LIFEFORMS("detectsLifeforms"),
    CLONE_LOSE_SKILLS("cloneLoseSkills"),
    POWER_DRAIN_FRIENDLY("powerDrainFriendly"),
    DEFAULT_SKILL_LEVEL("defaultSkillLevel"),
    POWER_RECHARGE_MULTIPLIER("powerRechargeMultiplier"),
    HACK_DOORS("hackDoors"),
    NO_CLONE("noClone"),
    NO_SLOT("noSlot"),
    NO_AI("noAI"),
    VALID_TARGET("validTarget"),
    CAN_MOVE("canMove"),
    TELEPORT_MOVE("teleportMove"),
    TELEPORT_MOVE_OTHER_SHIP("teleportMoveOtherShip"),
    SILENCED("silenced"),
    LOW_HEALTH_THRESHOLD("lowHealthThreshold"),
    NO_WARNING("noWarning"),
    // non-cached stats
    CREW_SLOTS("crewSlots"),
    ACTIVATE_WHEN_READY("activateWhenReady"),
    STAT_BOOST("statBoost"),
    DEATH_EFFECT("deathEffect"),
    POWER_EFFECT("powerEffect"),
    POWER_MAX_CHARGES("powerCharges"),
    POWER_CHARGES_PER_JUMP("chargesPerJump"),
    POWER_COOLDOWN("powerCooldown"),
    TRANSFORM_RACE("transformRace"),
    PERS_DAMAGE_MULTIPLIER("persDamageMultiplier"),
    PERS_HEAL_MULTIPLIER("persHealMultiplier");

    val cached: Boolean get() = ordinal < CACHED

    companion object {
        /** HS `numCachedStats`: MAX_HEALTH .. NO_WARNING. */
        @JvmField val CACHED = NO_WARNING.ordinal + 1
        @JvmField val ALL: Array<CrewStat> = values()

        /** HS's `std::find(crewStats...)`: null for a name HS does not know (it throws "Unrecognized stat boost"). */
        @JvmStatic
        fun byXmlName(name: String?): CrewStat? = ALL.firstOrNull { it.xmlName == name }
    }
}
