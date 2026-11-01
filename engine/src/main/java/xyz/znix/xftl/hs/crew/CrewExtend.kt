// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/StatBoost.cpp (CrewMember_Extend::CheckExtraCondition, BoostCheck,
// CalculateMaxHealth, CalculateStat, CalculatePowerScaling, CalculateDangerRating), CrewMember_Extend.h,
// CrewVTable.cpp (GetPowerOwner)
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.CxxSort
import xyz.znix.xftl.hs.stat.StatBoost
import xyz.znix.xftl.hs.stat.StatBoostDefinition
import xyz.znix.xftl.hs.stat.StatBoostDefinition.BoostSource
import xyz.znix.xftl.hs.stat.StatBoostDefinition.BoostType
import xyz.znix.xftl.hs.stat.StatBoostDefinition.CrewTarget
import xyz.znix.xftl.hs.stat.StatBoostDefinition.ShipTarget
import xyz.znix.xftl.hs.stat.StatBoostManager
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.SubSystem

/**
 * HS `CrewMember_Extend`: the Hyperspace state of one crew member (any crew while HS is active: [HsCrewMember]
 * races, vanilla-class crew of races without a definition, and drones), created lazily by [HsCrew.ex].
 */
class CrewExtend(@JvmField val crew: AbstractCrew) {
    /** HS `selfId` (Globals::GetNextSpaceId), kept in saves. */
    @JvmField var selfId = HsCrew.nextSelfId()

    @JvmField val crewPowers = ArrayList<ActivatedPower>()
    @JvmField val powerResources = ArrayList<ActivatedPowerResource>()
    @JvmField val powerResourceMap = HashMap<Int, ActivatedPowerResource>()
    @JvmField var hasSpecialPower = false

    /** CrewMember_Extend::canPhaseThroughDoors (the stat result CrewMember::GetNewGoal caches), lastRoom and lastShipId (HS never writes them). */
    @JvmField var canPhaseThroughDoors = false
    @JvmField var lastRoom = -1
    @JvmField var lastShipId = -1
    @JvmField var tempAddedPowerResource = false
    @JvmField var powerChange = ArrayList<ActivatedPowerDefinition?>()

    @JvmField var deathEffectChange = ExplosionDefinition()
    @JvmField var hasDeathExplosion = false
    @JvmField var exploded = false
    @JvmField var triggerExplosion = false

    @JvmField var isIonDrone = false
    @JvmField var isAbilityDrone = false
    @JvmField var noSlot = false
    @JvmField var noClone = false

    @JvmField var isHealing = false
    @JvmField var passiveHealTimer: xyz.znix.xftl.hs.stat.HsTimer? = null
    @JvmField var deathTimer: xyz.znix.xftl.hs.stat.HsTimer? = null
    @JvmField var prevStun = 0f
    @JvmField var prevCanMove = true
    @JvmField var extraMedbay = 0f
    /** CrewMember::UpdateHealth (CustomCrew.cpp) added its 1e-10 to fMedbay this frame (an HS heal stat healed). */
    @JvmField var healMark = false
    /** fMedbay as the crew loop set it this frame (before the heal mark); -1 until the first loop. */
    @JvmField var medbayRate = -1f

    /** Passive + temporary-ability boosts this crew member gives out (rebuilt by UpdateAbilityStatBoosts). */
    @JvmField val outgoingStatBoosts = ArrayList<StatBoost>()
    /** Timed (and persistent) boosts applied to this crew member, by stat. */
    @JvmField val timedStatBoosts = LinkedHashMap<CrewStat, ArrayList<StatBoost>>()
    /** CrewAnimation_Extend::boostAnim: one looping animation per stat boost definition with a boostAnim. */
    @JvmField val boostAnims = LinkedHashMap<xyz.znix.xftl.hs.stat.StatBoostDefinition, xyz.znix.xftl.FTLAnimation>()

    @JvmField var originalColorRace = ""
    @JvmField var originalRace = ""
    @JvmField var transformRace = ""

    @JvmField var teleporting = false

    /** HS statCache: value and the stat frame it was computed in. */
    private val cacheValue = FloatArray(CrewStat.CACHED)
    private val cacheFrame = IntArray(CrewStat.CACHED)

    /** The stats cached this frame no longer hold (what a stat depends on changed before the next frame). */
    fun clearStatCache() = cacheFrame.fill(-1)

    // ----- the crew member as HS's fields see it -----

    /** CrewMember::iShipId: 0 for the player's crew, 1 for everyone else (owner-less event boarders included). */
    val shipId: Int get() = HsCrew.shipId(crew)
    /** CrewMember::currentShipId: the ship it stands on. */
    val currentShipId: Int get() = HsCrew.currentShipId(crew)
    val roomId: Int get() = crew.room.id
    val species: String get() = crew.hsSpecies
    val mindControlled: Boolean get() = (crew as? LivingCrew)?.isMindControlled == true
    val isDrone: Boolean get() = crew !is LivingCrew
    /** CrewMember::intruder: standing on a ship other than its own. */
    val intruder: Boolean get() = currentShipId != shipId

    /** CrewMember::Functional = !IsDead(): bDead for crew, unpowered for drones. */
    val functional: Boolean
        get() {
            if (crew.health <= 0f || crew.currentAction == AbstractCrew.Action.DYING) return false
            val pawn = crew as? AbstractIndoorsDrone.Pawn
            return pawn == null || pawn.drone.isPowered
        }

    /** CrewMember::GetPowerOwner: a mind-controlled crew member's powers belong to the controller with MIND_ORDER. */
    val powerOwner: Int
        get() {
            val id = shipId
            if (mindControlled) {
                val controller = HsCrew.ship(crew, if (id == 0) 1 else 0)
                if (controller != null && controller.hasAugment("MIND_ORDER")) return HsCrew.shipIdOf(controller)
            }
            return id
        }

    /** The definition used for stats (HS GetDefinition: ion drones use `boarder_ion` when defined). */
    fun definition(): CrewDefinition? {
        if (isIonDrone && CustomCrewManager.isRace("boarder_ion")) return CustomCrewManager.definitionOrNull("boarder_ion")
        return CustomCrewManager.definitionOrNull(species)
    }

    fun firstCrewPower(): ActivatedPower? = crewPowers.firstOrNull { it.enabled }

    /** CrewMember_Extend::CalculatePowerDef. */
    fun calculatePowerDef() = CrewPowerLogic.calculatePowerDef(this)

    /** CrewMember_Extend::UpdateAbilityStatBoosts. */
    fun updateAbilityStatBoosts(def: CrewDefinition? = definition()) = CrewPowerLogic.updateAbilityStatBoosts(this, def)

    /** CrewMember::_HS_ResetPower, on an FTL jump. */
    fun resetPower() = CrewPowerLogic.resetPower(this)

    /** CrewMember_Extend::TransformRace. */
    fun transformRaceTo(species: String): Boolean = HsCrew.transformRace(this, species)

    fun isInvulnerable(): Boolean = crewPowers.any { it.temporaryPowerActive && it.def.tempPower.invulnerable }

    // ----- BoostCheck -----

    /** CrewMember_Extend::CheckExtraCondition. */
    fun checkExtraCondition(c: CrewExtraCondition): Boolean {
        val a = crew.currentAction
        return when (c) {
            CrewExtraCondition.BURNING -> crew.room.fires.any { it != null }
            CrewExtraCondition.SUFFOCATING -> crew.room.isOxygenCritical
            CrewExtraCondition.MIND_CONTROLLED -> mindControlled
            CrewExtraCondition.STUNNED -> crew.stunTime > 0f
            CrewExtraCondition.REPAIRING -> crew.mode == AbstractCrew.SlotType.CREW &&
                (a == AbstractCrew.Action.REPAIRING || a == AbstractCrew.Action.REPAIRING_BREACH || a == AbstractCrew.Action.FIRE_FIGHTING)
            CrewExtraCondition.REPAIRING_SYSTEM -> a == AbstractCrew.Action.REPAIRING
            CrewExtraCondition.REPAIRING_BREACH -> crew.mode == AbstractCrew.SlotType.CREW && a == AbstractCrew.Action.REPAIRING_BREACH
            CrewExtraCondition.SABOTAGING -> a == AbstractCrew.Action.SABOTAGE
            CrewExtraCondition.FIGHTING -> a == AbstractCrew.Action.FIGHTING
            CrewExtraCondition.SHOOTING -> crew.hsShooting && crew.stunTime <= 0f
            CrewExtraCondition.MOVING -> a != AbstractCrew.Action.TELEPORTING && crew.health > 0f && a == AbstractCrew.Action.MOVING
            CrewExtraCondition.IDLE -> a != AbstractCrew.Action.TELEPORTING && crew.health > 0f && a == AbstractCrew.Action.IDLE
            CrewExtraCondition.MANNING -> a == AbstractCrew.Action.MANNING
            CrewExtraCondition.FIREFIGHTING -> a == AbstractCrew.Action.FIRE_FIGHTING
            CrewExtraCondition.DYING -> a == AbstractCrew.Action.DYING
            CrewExtraCondition.TELEPORTING -> crew.hsTeleporting // customTele.teleporting (HsCustomTeleport)
        }
    }

    /** CrewMember_Extend::BoostCheck. */
    fun boostCheck(b: StatBoost, checkRoomIdIn: Int = -1): Boolean {
        val checkRoomId = if (checkRoomIdIn == -1) roomId else checkRoomIdIn
        val d = b.def
        val ownerShip: Int
        val cur = currentShipId
        val src = b.crewSource as? AbstractCrew
        val srcEx = src?.let { HsCrew.ex(it) }
        when (d.boostSource) {
            BoostSource.CREW -> {
                if (srcEx != null) {
                    ownerShip = srcEx.powerOwner
                    b.sourceShipId = srcEx.shipId
                } else {
                    ownerShip = b.sourceShipId
                }
                when (d.shipTarget) {
                    ShipTarget.PLAYER_SHIP -> if (cur != 0) return false
                    ShipTarget.ORIGINAL_SHIP -> if (cur != ownerShip) return false
                    ShipTarget.ORIGINAL_OTHER_SHIP -> if (cur == ownerShip) return false
                    ShipTarget.OTHER_ALL -> if (srcEx == null || cur == srcEx.currentShipId) return false
                    ShipTarget.ENEMY_SHIP -> if (cur != 1) return false
                    ShipTarget.CURRENT_ALL -> if (srcEx == null || cur != srcEx.currentShipId) return false
                    ShipTarget.CURRENT_ROOM -> if (srcEx == null || cur != srcEx.currentShipId || checkRoomId != srcEx.roomId) return false
                    ShipTarget.CREW_TARGET -> if (src == null || src.enemyToAttack !== crew) return false
                    ShipTarget.TARGETS_ME -> if (src == null || crew.enemyToAttack !== src) return false
                    ShipTarget.ALL -> {}
                }
                if (b.crewSourceId == selfId) {
                    if (!d.affectsSelf) return false
                } else {
                    val id = shipId
                    when (d.crewTarget) {
                        CrewTarget.ALLIES -> if (ownerShip != id) return false
                        CrewTarget.ENEMIES -> if (ownerShip == id) return false
                        CrewTarget.SELF -> return false
                        CrewTarget.CURRENT_ALLIES -> if (srcEx != null) {
                            if ((srcEx.shipId != id) == (srcEx.mindControlled == mindControlled)) return false
                        } else if ((ownerShip != id) != mindControlled) return false
                        CrewTarget.CURRENT_ENEMIES -> if (srcEx != null) {
                            if ((srcEx.shipId == id) == (srcEx.mindControlled == mindControlled)) return false
                        } else if ((ownerShip == id) != mindControlled) return false
                        CrewTarget.ORIGINAL_ALLIES -> if (srcEx != null) {
                            if (srcEx.shipId != id) return false
                        } else if (ownerShip != id) return false
                        CrewTarget.ORIGINAL_ENEMIES -> if (srcEx != null) {
                            if (srcEx.shipId == id) return false
                        } else if (ownerShip != id) return false // HS compares != here too
                        CrewTarget.ALL -> {}
                    }
                    if (d.whiteList.isNotEmpty() && species !in d.whiteList) return false
                    if (d.blackList.isNotEmpty() && species in d.blackList) return false
                }
            }
            BoostSource.AUGMENT -> {
                ownerShip = b.sourceShipId
                when (d.shipTarget) {
                    ShipTarget.PLAYER_SHIP -> if (cur != 0) return false
                    ShipTarget.ORIGINAL_SHIP, ShipTarget.CURRENT_ALL -> if (cur != ownerShip) return false
                    ShipTarget.ORIGINAL_OTHER_SHIP, ShipTarget.OTHER_ALL -> if (cur == ownerShip) return false
                    ShipTarget.ENEMY_SHIP -> if (cur != 1) return false
                    else -> {}
                }
                val id = shipId
                when (d.crewTarget) {
                    CrewTarget.ALLIES, CrewTarget.ORIGINAL_ALLIES -> if (ownerShip != id) return false
                    CrewTarget.ENEMIES, CrewTarget.ORIGINAL_ENEMIES -> if (ownerShip == id) return false
                    CrewTarget.SELF -> return false
                    CrewTarget.CURRENT_ALLIES -> if ((ownerShip != id) != mindControlled) return false
                    CrewTarget.CURRENT_ENEMIES -> if ((ownerShip == id) != mindControlled) return false
                    CrewTarget.ALL -> {}
                }
                if (d.whiteList.isNotEmpty() && species !in d.whiteList) return false
                if (d.blackList.isNotEmpty() && species in d.blackList) return false
            }
        }

        if (d.isRoomBased) {
            if (checkRoomId !in b.roomIds(cur)) return false
        } else if (d.systemList.isNotEmpty()) {
            val ids = b.roomIds(cur)
            when (d.systemRoomTarget) {
                StatBoostDefinition.SystemRoomTarget.ALL -> if (checkRoomId !in ids) return false
                StatBoostDefinition.SystemRoomTarget.NONE -> if (checkRoomId in ids) return false
            }
        }

        when (d.droneTarget) {
            StatBoostDefinition.DroneTarget.DRONES -> if (!isDrone) return false
            StatBoostDefinition.DroneTarget.CREW -> if (isDrone) return false
            StatBoostDefinition.DroneTarget.ALL -> {}
        }

        if (d.functionalTarget && !functional) return false

        val hp = crew.health
        if (d.healthReq[0] != -1f && hp < d.healthReq[0]) return false
        if (d.healthReq[1] != -1f && hp > d.healthReq[1]) return false
        if (d.healthFractionReq[0] != -1f && hp / crew.maxHealth < d.healthFractionReq[0]) return false
        if (d.healthFractionReq[1] != -1f && hp / crew.maxHealth > d.healthFractionReq[1]) return false
        if (d.oxygenReq[0] != -1f && (checkRoomId == -1 || HsCrew.roomOxygen(crew, checkRoomId) < d.oxygenReq[0])) return false
        if (d.oxygenReq[1] != -1f && (checkRoomId == -1 || HsCrew.roomOxygen(crew, checkRoomId) > d.oxygenReq[1])) return false
        if (d.fireCount[0] != -1 && (checkRoomId == -1 || HsCrew.fireCount(crew, checkRoomId) < d.fireCount[0])) return false
        if (d.fireCount[1] != -1 && (checkRoomId == -1 || HsCrew.fireCount(crew, checkRoomId) > d.fireCount[1])) return false

        for ((c, want) in d.extraConditions) if (checkExtraCondition(c) != want) return false
        if (d.extraOrConditions.isNotEmpty()) {
            for ((c, want) in d.extraOrConditions) if (checkExtraCondition(c) == want) return true
            return false
        }
        return true
    }

    // ----- CalculateStat -----

    /** CrewMember_Extend::CalculateMaxHealth: also rescales the current health when the maximum changed. */
    fun calculateMaxHealth(def: CrewDefinition): Int {
        val maxHealth = calculateStat(CrewStat.MAX_HEALTH, def).toInt()
        (crew as? HsCrewMember)?.applyMaxHealth(maxHealth)
        return maxHealth
    }

    private inline fun <T> base(base: T, temp: (TemporaryPowerDefinition) -> Toggle<T>): T {
        for (p in crewPowers) {
            if (p.temporaryPowerActive) {
                val t = temp(p.def.tempPower)
                if (t.enabled) return t.value
            }
        }
        return base
    }

    /** Float result of [calculateStat] for a stat; [boolResult] holds a boolean stat's value. */
    @JvmField var boolResult = false

    fun calculateBool(stat: CrewStat, def: CrewDefinition?): Boolean {
        calculateStat(stat, def)
        return boolResult
    }

    fun calculateStat(stat: CrewStat): Float = calculateStat(stat, definition())

    /**
     * CrewMember_Extend::CalculateStat. Boolean stats leave their value in [boolResult] (HS's `bool*` out
     * parameter) and return it as 0/1. [def] may be HS's default-inserted definition.
     */
    fun calculateStat(stat: CrewStat, defIn: CrewDefinition?): Float {
        val def = defIn ?: CustomCrewManager.definition(species)
        val idx = stat.ordinal
        if (idx < CrewStat.CACHED && cacheFrame[idx] == StatBoostManager.statCacheFrame) {
            boolResult = cacheValue[idx] != 0f
            return cacheValue[idx]
        }

        val personal = ArrayList<StatBoost>()
        StatBoostManager.statBoosts[stat]?.let { list ->
            for (b in list) if (boostCheck(b) && (b.def.duration == -1f || b.timer.running)) personal.add(b.copy())
        }
        timedStatBoosts[stat]?.let { list ->
            for (b in list) if (b.def.duration == -1f || b.timer.running) personal.add(b.copy())
        }

        var finalStat = 0f
        var bool = false
        var isBool = false
        var isEffect = false
        val functional = functional

        when (stat) {
            CrewStat.MAX_HEALTH -> finalStat = base(def.maxHealth) { it.maxHealth }.toFloat()
            CrewStat.STUN_MULTIPLIER -> finalStat = base(def.stunMultiplier) { it.stunMultiplier }
            CrewStat.MOVE_SPEED_MULTIPLIER -> finalStat = base(def.moveSpeedMultiplier) { it.moveSpeedMultiplier }
            CrewStat.REPAIR_SPEED_MULTIPLIER -> finalStat = base(def.repairSpeed) { it.repairSpeed }
            CrewStat.DAMAGE_MULTIPLIER -> finalStat = base(def.damageMultiplier) { it.damageMultiplier }
            CrewStat.RANGED_DAMAGE_MULTIPLIER -> finalStat = base(def.rangedDamageMultiplier) { it.rangedDamageMultiplier }
            CrewStat.DOOR_DAMAGE_MULTIPLIER -> finalStat = base(def.doorDamageMultiplier) { it.doorDamageMultiplier }
            CrewStat.FIRE_REPAIR_MULTIPLIER -> finalStat = base(def.fireRepairMultiplier) { it.fireRepairMultiplier }
            CrewStat.SUFFOCATION_MODIFIER -> finalStat = base(def.suffocationModifier) { it.suffocationModifier }
            CrewStat.FIRE_DAMAGE_MULTIPLIER -> finalStat = base(def.fireDamageMultiplier) { it.fireDamageMultiplier }
            CrewStat.OXYGEN_CHANGE_SPEED -> finalStat = base(def.oxygenChangeSpeed) { it.oxygenChangeSpeed }
            CrewStat.DAMAGE_TAKEN_MULTIPLIER -> finalStat = base(def.damageTakenMultiplier) { it.damageTakenMultiplier }
            CrewStat.CLONE_SPEED_MULTIPLIER -> finalStat = base(def.cloneSpeedMultiplier) { it.cloneSpeedMultiplier }
            CrewStat.PASSIVE_HEAL_AMOUNT -> finalStat = if (functional) base(def.passiveHealAmount) { it.passiveHealAmount } else 0f
            CrewStat.TRUE_PASSIVE_HEAL_AMOUNT -> finalStat = if (functional) base(def.truePassiveHealAmount) { it.truePassiveHealAmount } else 0f
            CrewStat.TRUE_HEAL_AMOUNT -> finalStat = if (functional) base(def.trueHealAmount) { it.trueHealAmount } else 0f
            CrewStat.ACTIVE_HEAL_AMOUNT -> finalStat = if (functional) base(def.healAmount) { it.healAmount } else 0f
            CrewStat.PASSIVE_HEAL_DELAY -> finalStat = base(def.passiveHealDelay) { it.passiveHealDelay }.toFloat()
            CrewStat.SABOTAGE_SPEED_MULTIPLIER -> finalStat = base(def.sabotageSpeedMultiplier) { it.sabotageSpeedMultiplier }
            CrewStat.ALL_DAMAGE_TAKEN_MULTIPLIER -> finalStat = base(def.allDamageTakenMultiplier) { it.allDamageTakenMultiplier }
            CrewStat.HEAL_SPEED_MULTIPLIER -> finalStat = base(def.healSpeed) { it.healSpeed }
            CrewStat.HEAL_CREW_AMOUNT -> finalStat = base(def.healCrewAmount) { it.healCrewAmount }
            CrewStat.DAMAGE_ENEMIES_AMOUNT -> finalStat = base(def.damageEnemiesAmount) { it.damageEnemiesAmount }
            CrewStat.BONUS_POWER -> finalStat = base(def.bonusPower) { it.bonusPower }.toFloat()
            CrewStat.POWER_DRAIN -> finalStat = base(def.powerDrain) { it.powerDrain }.toFloat()
            CrewStat.ESSENTIAL -> finalStat = def.essential
            CrewStat.DEFAULT_SKILL_LEVEL -> finalStat = def.defaultSkillLevel.toFloat()
            CrewStat.POWER_RECHARGE_MULTIPLIER -> finalStat = base(def.powerRechargeMultiplier) { it.powerRechargeMultiplier }
            CrewStat.CREW_SLOTS -> finalStat = def.crewSlots
            CrewStat.CAN_FIGHT -> { bool = base(def.canFight) { it.canFight }; isBool = true }
            CrewStat.CAN_REPAIR -> { bool = base(def.canRepair) { it.canRepair }; isBool = true }
            CrewStat.CAN_SABOTAGE -> { bool = base(def.canSabotage) { it.canSabotage }; isBool = true }
            CrewStat.CAN_MAN -> { bool = base(def.canMan) { it.canMan }; isBool = true }
            CrewStat.CAN_TELEPORT -> { bool = base(def.canTeleport) { it.canTeleport }; isBool = true }
            CrewStat.CAN_SUFFOCATE -> { bool = base(def.canSuffocate) { it.canSuffocate }; isBool = true }
            CrewStat.CONTROLLABLE -> { bool = base(def.controllable) { it.controllable }; isBool = true }
            CrewStat.CAN_BURN -> { bool = base(def.canBurn) { it.canBurn }; isBool = true }
            CrewStat.IS_TELEPATHIC -> { bool = base(def.isTelepathic) { it.isTelepathic }; isBool = true }
            CrewStat.RESISTS_MIND_CONTROL -> { bool = base(def.resistsMindControl) { it.resistsMindControl }; isBool = true }
            CrewStat.IS_ANAEROBIC -> { bool = base(def.isAnaerobic) { it.isAnaerobic }; isBool = true }
            CrewStat.CAN_PHASE_THROUGH_DOORS -> { bool = base(def.canPhaseThroughDoors) { it.canPhaseThroughDoors }; isBool = true }
            CrewStat.DETECTS_LIFEFORMS -> { bool = base(def.detectsLifeforms) { it.detectsLifeforms }; isBool = true }
            CrewStat.CLONE_LOSE_SKILLS -> { bool = def.cloneLoseSkills; isBool = true }
            CrewStat.POWER_DRAIN_FRIENDLY -> { bool = base(def.powerDrainFriendly) { it.powerDrainFriendly }; isBool = true }
            CrewStat.HACK_DOORS -> { bool = base(def.hackDoors) { it.hackDoors }; isBool = true }
            CrewStat.NO_CLONE -> { bool = base(def.noClone) { it.noClone }; isBool = true }
            CrewStat.NO_SLOT -> { bool = noSlot; isBool = true }
            CrewStat.NO_AI -> { bool = base(def.noAI) { it.noAI }; isBool = true }
            CrewStat.VALID_TARGET -> { bool = base(def.validTarget) { it.validTarget }; isBool = true }
            CrewStat.CAN_MOVE -> { bool = base(def.canMove) { it.canMove }; isBool = true }
            CrewStat.TELEPORT_MOVE -> { bool = base(def.teleportMove) { it.teleportMove }; isBool = true }
            CrewStat.TELEPORT_MOVE_OTHER_SHIP -> { bool = base(def.teleportMoveOtherShip) { it.teleportMoveOtherShip }; isBool = true }
            CrewStat.SILENCED -> { bool = base(def.silenced) { it.silenced }; isBool = true }
            CrewStat.DEATH_EFFECT -> { deathEffectChange = def.explosionDef.copy(); hasDeathExplosion = def.hasDeathExplosion; isEffect = true }
            CrewStat.POWER_EFFECT -> { powerChange = ArrayList(def.powerDefs); isEffect = true }
            CrewStat.POWER_MAX_CHARGES -> {
                for (p in crewPowers) if (p.enabled || p.def.disabledCharges == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                    p.modifiedPowerCharges = if (p.def.powerCharges == -1) Float.POSITIVE_INFINITY else p.def.powerCharges.toFloat()
                for (r in powerResources) if (r.enabled || r.def.disabledCharges == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                    r.modifiedPowerCharges = if (r.def.powerCharges == -1) Float.POSITIVE_INFINITY else r.def.powerCharges.toFloat()
                isEffect = true
            }
            CrewStat.POWER_CHARGES_PER_JUMP -> {
                for (p in crewPowers) if (p.enabled || p.def.disabledCharges == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                    p.modifiedChargesPerJump = p.def.chargesPerJump.toFloat()
                for (r in powerResources) if (r.enabled || r.def.disabledCharges == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                    r.modifiedChargesPerJump = r.def.chargesPerJump.toFloat()
                isEffect = true
            }
            CrewStat.POWER_COOLDOWN -> {
                for (p in crewPowers) if (p.enabled || p.def.disabledCooldown == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                    p.modifiedPowerCooldown = p.def.cooldown
                for (r in powerResources) if (r.enabled || r.def.disabledCooldown == PowerConst.DISABLED_COOLDOWN_CONTINUE)
                    r.modifiedPowerCooldown = r.def.cooldown
                isEffect = true
            }
            CrewStat.ACTIVATE_WHEN_READY -> {
                for (p in crewPowers) if (p.enabled)
                    p.activateWhenReady = p.def.activateWhenReady && (!p.def.activateReadyEnemies || powerOwner == 1)
                isEffect = true
            }
            CrewStat.TRANSFORM_RACE -> { transformRace = originalRace; isEffect = true }
            CrewStat.LOW_HEALTH_THRESHOLD -> finalStat = base(def.lowHealthThreshold) { it.lowHealthThreshold }
            CrewStat.NO_WARNING -> { bool = base(def.noWarning) { it.noWarning }; isBool = true }
            CrewStat.PERS_DAMAGE_MULTIPLIER -> finalStat = base(def.persDamageMultiplier) { it.persDamageMultiplier }
            CrewStat.PERS_HEAL_MULTIPLIER -> finalStat = base(def.persHealMultiplier) { it.persHealMultiplier }
            CrewStat.STAT_BOOST -> {}
        }

        CxxSort.sort(personal) { a, b -> a.def.priority < b.def.priority }

        // Stack limits, highest priority first (HS iterates each stack id's list in reverse).
        var stackIds: HashMap<Int, ArrayList<StatBoost>>? = null
        for (b in personal) if (b.def.stackId != 0) {
            if (stackIds == null) stackIds = HashMap()
            stackIds.getOrPut(b.def.stackId) { ArrayList() }.add(b)
        }
        stackIds?.values?.forEach { list ->
            var stacks = 0
            for (i in list.indices.reversed()) {
                val b = list[i]
                b.iStacks = minOf(b.iStacks, maxOf(b.def.maxStacks - stacks, 0))
                stacks += b.iStacks
            }
        }

        // CALCULATE_STAT_PRE(crew, stat, def, value, bool): rewrites value/bool; a PREEMPT skips the boosts and POST
        var preempt = false
        if (xyz.znix.xftl.hs.HS.luaActive) {
            preempt = xyz.znix.xftl.hs.lua.LuaCrewEvents.calculateStat(false, crew, idx, def, finalStat, bool, isBool)
            finalStat = xyz.znix.xftl.hs.lua.LuaCrewEvents.value; if (isBool) bool = xyz.znix.xftl.hs.lua.LuaCrewEvents.bool
        }
        if (!preempt) {
            for (b in personal) {
                val d = b.def
                if (d.stat != stat) continue
                for (i in 0 until b.iStacks) {
                    val scaling = calculatePowerScaling(b)
                    if (isBool) {
                        if (scaling != 0f) {
                            if (d.boostType == BoostType.SET) bool = d.value
                            else if (d.boostType == BoostType.FLIP) bool = !bool
                        }
                    } else if (isEffect) {
                        applyEffect(stat, b, scaling)
                    } else {
                        when (d.boostType) {
                            BoostType.MULT -> finalStat = if (d.powerScaling.isNotEmpty()) finalStat * (1 + (d.amount - 1) * scaling) else finalStat * d.amount
                            BoostType.FLAT -> finalStat += d.amount * scaling
                            BoostType.SET -> finalStat = d.amount * scaling
                            BoostType.MIN -> finalStat = minOf(finalStat, d.amount * scaling)
                            BoostType.MAX -> finalStat = maxOf(finalStat, d.amount * scaling)
                            else -> {}
                        }
                    }
                }
            }
            if (xyz.znix.xftl.hs.HS.luaActive) {
                xyz.znix.xftl.hs.lua.LuaCrewEvents.calculateStat(true, crew, idx, def, finalStat, bool, isBool)
                finalStat = xyz.znix.xftl.hs.lua.LuaCrewEvents.value; if (isBool) bool = xyz.znix.xftl.hs.lua.LuaCrewEvents.bool
            }
        }

        if (idx < CrewStat.CACHED) {
            if (isBool) {
                cacheValue[idx] = if (bool) 1f else 0f
                cacheFrame[idx] = StatBoostManager.statCacheFrame
            } else if (!isEffect) {
                cacheValue[idx] = finalStat
                cacheFrame[idx] = StatBoostManager.statCacheFrame
            }
        }
        // HS returns finalStat on this path: 0 for a boolean stat (only a cache hit returns the cached 1/0), and the
        // OUTPUT boolean of a numeric stat is left unset (false here).
        boolResult = isBool && bool
        return finalStat
    }

    private fun applyNumber(type: BoostType, value: Float, d: StatBoostDefinition, scaling: Float): Float = when (type) {
        BoostType.MULT -> if (d.powerScaling.isNotEmpty()) value * (1 + (d.amount - 1) * scaling) else value * d.amount
        BoostType.FLAT -> value + d.amount * scaling
        BoostType.SET -> d.amount * scaling
        BoostType.MIN -> minOf(value, d.amount * scaling)
        BoostType.MAX -> maxOf(value, d.amount * scaling)
        else -> value
    }

    private fun applyEffect(stat: CrewStat, b: StatBoost, scaling: Float) {
        val d = b.def
        when (stat) {
            CrewStat.POWER_EFFECT -> if (scaling != 0f) when (d.boostType) {
                BoostType.SET -> { powerChange.clear(); d.powerChange?.let { powerChange.add(it) } }
                BoostType.FLAT -> d.powerChange?.let { powerChange.add(it) }
                BoostType.REPLACE_POWER -> {
                    var n = (d.amount * scaling).toInt()
                    if (n != 0) {
                        var i = 0
                        while (i < powerChange.size) {
                            val p = powerChange[i]
                            if (p != null && d.isTargetPower(p)) {
                                if (d.powerChange != null) {
                                    powerChange[i] = d.powerChange
                                    if (--n == 0) break
                                } else {
                                    powerChange.removeAt(i)
                                    if (--n == 0) break
                                    continue
                                }
                            }
                            i++
                        }
                    }
                }
                else -> {}
            }
            CrewStat.POWER_MAX_CHARGES -> {
                for (p in crewPowers) {
                    if (!p.enabled && p.def.disabledCharges != PowerConst.DISABLED_COOLDOWN_CONTINUE) continue
                    if (!d.isTargetPower(p.def)) continue
                    p.modifiedPowerCharges = applyNumber(d.boostType, p.modifiedPowerCharges, d, scaling)
                }
                for (r in powerResources) {
                    if (!r.enabled && r.def.disabledCharges != PowerConst.DISABLED_COOLDOWN_CONTINUE) continue
                    if (!d.isTargetPower(r.def)) continue
                    r.modifiedPowerCharges = applyNumber(d.boostType, r.modifiedPowerCharges, d, scaling)
                }
            }
            CrewStat.POWER_CHARGES_PER_JUMP -> {
                for (p in crewPowers) {
                    if (!p.enabled && p.def.disabledCharges != PowerConst.DISABLED_COOLDOWN_CONTINUE) continue
                    if (!d.isTargetPower(p.def)) continue
                    p.modifiedChargesPerJump = applyNumber(d.boostType, p.modifiedChargesPerJump, d, scaling)
                }
                for (r in powerResources) {
                    if (!r.enabled && r.def.disabledCharges != PowerConst.DISABLED_COOLDOWN_CONTINUE) continue
                    if (!d.isTargetPower(r.def)) continue
                    r.modifiedChargesPerJump = applyNumber(d.boostType, r.modifiedChargesPerJump, d, scaling)
                }
            }
            CrewStat.POWER_COOLDOWN -> {
                for (p in crewPowers) {
                    if (!p.enabled && p.def.disabledCooldown != PowerConst.DISABLED_COOLDOWN_CONTINUE) continue
                    if (!d.isTargetPower(p.def)) continue
                    p.modifiedPowerCooldown = applyNumber(d.boostType, p.modifiedPowerCooldown, d, scaling)
                }
                for (r in powerResources) {
                    if (!r.enabled && r.def.disabledCooldown != PowerConst.DISABLED_COOLDOWN_CONTINUE) continue
                    if (!d.isTargetPower(r.def)) continue
                    r.modifiedPowerCooldown = applyNumber(d.boostType, r.modifiedPowerCooldown, d, scaling)
                }
            }
            CrewStat.ACTIVATE_WHEN_READY -> if (scaling != 0f) {
                for (p in crewPowers) {
                    if (!p.enabled || !d.isTargetPower(p.def)) continue
                    if (d.boostType == BoostType.SET) p.activateWhenReady = d.value
                    else if (d.boostType == BoostType.FLIP) p.activateWhenReady = !p.activateWhenReady
                }
            }
            CrewStat.DEATH_EFFECT -> applyDeathEffect(d, scaling)
            CrewStat.TRANSFORM_RACE -> if (scaling != 0f && d.boostType == BoostType.SET) {
                transformRace = d.stringValue
                if (d.value) originalRace = transformRace
            }
            else -> {}
        }
    }

    private fun scaled(v: Int, mult: Int, s: Float): Int = (v * (1f + (mult - 1f) * s)).toInt()

    private fun applyDeathEffect(d: StatBoostDefinition, s: Float) {
        val c = d.deathEffectChange
        val e = deathEffectChange
        val on = s != 0f
        when (d.boostType) {
            BoostType.MULT -> if (c != null) {
                hasDeathExplosion = true
                val dm = e.damage; val cd = c.damage
                e.shipFriendlyFire = e.shipFriendlyFire and (c.shipFriendlyFire || !on)
                dm.iDamage = scaled(dm.iDamage, cd.iDamage, s); dm.iShieldPiercing = scaled(dm.iShieldPiercing, cd.iShieldPiercing, s)
                dm.fireChance = scaled(dm.fireChance, cd.fireChance, s); dm.breachChance = scaled(dm.breachChance, cd.breachChance, s)
                dm.stunChance = scaled(dm.stunChance, cd.stunChance, s); dm.iIonDamage = scaled(dm.iIonDamage, cd.iIonDamage, s)
                dm.iSystemDamage = scaled(dm.iSystemDamage, cd.iSystemDamage, s); dm.iPersDamage = scaled(dm.iPersDamage, cd.iPersDamage, s)
                dm.bHullBuster = dm.bHullBuster and (cd.bHullBuster || !on)
                dm.bLockdown = dm.bLockdown and (cd.bLockdown || !on)
                dm.bFriendlyFire = dm.bFriendlyFire and (cd.bFriendlyFire || !on)
                dm.iStun = scaled(dm.iStun, cd.iStun, s)
                e.transformRaceHealth *= 1f + (c.transformRaceHealth - 1f) * s
                e.transformRaceHealthFraction *= 1f + (c.transformRaceHealthFraction - 1f) * s
            }
            BoostType.FLAT -> if (c != null) {
                hasDeathExplosion = true
                val dm = e.damage; val cd = c.damage
                e.shipFriendlyFire = e.shipFriendlyFire or (c.shipFriendlyFire && on)
                dm.iDamage = (dm.iDamage + cd.iDamage * s).toInt(); dm.iShieldPiercing = (dm.iShieldPiercing + cd.iShieldPiercing * s).toInt()
                dm.fireChance = (dm.fireChance + cd.fireChance * s).toInt(); dm.breachChance = (dm.breachChance + cd.breachChance * s).toInt()
                dm.stunChance = (dm.stunChance + cd.stunChance * s).toInt(); dm.iIonDamage = (dm.iIonDamage + cd.iIonDamage * s).toInt()
                dm.iSystemDamage = (dm.iSystemDamage + cd.iSystemDamage * s).toInt(); dm.iPersDamage = (dm.iPersDamage + cd.iPersDamage * s).toInt()
                dm.bHullBuster = dm.bHullBuster or (cd.bHullBuster && on)
                dm.bLockdown = dm.bLockdown or (cd.bLockdown && on)
                dm.bFriendlyFire = dm.bFriendlyFire or (cd.bFriendlyFire && on)
                dm.iStun = (dm.iStun + cd.iStun * s).toInt()
                e.transformRaceHealth += c.transformRaceHealth * s
                e.transformRaceHealthFraction += c.transformRaceHealthFraction * s
                if (on) {
                    e.statBoosts.addAll(c.statBoosts)
                    e.roomStatBoosts.addAll(c.roomStatBoosts)
                    e.crewSpawns.addAll(c.crewSpawns)
                    if (c.transformRace.isNotEmpty()) {
                        e.transformRace = c.transformRace
                        e.transformRaceHealth = c.transformRaceHealth * s
                        e.transformRaceHealthFraction = c.transformRaceHealthFraction * s
                        e.transformRaceDeathSound = c.transformRaceDeathSound
                    }
                    if (c.event[0].isNotEmpty()) { e.event[0] = c.event[0]; e.eventNodes[0] = c.eventNodes[0] }
                    if (c.event[1].isNotEmpty()) { e.event[1] = c.event[1]; e.eventNodes[1] = c.eventNodes[1] }
                }
            }
            BoostType.SET -> if (on) {
                if (c != null) { hasDeathExplosion = true; deathEffectChange = c.copy() } else hasDeathExplosion = false
            }
            else -> {}
        }
    }

    /** CrewMember_Extend::CalculatePowerScaling. */
    fun calculatePowerScaling(b: StatBoost): Float {
        val d = b.def
        val src = (b.crewSource as? AbstractCrew)?.let { HsCrew.ex(it) }
        fun ship(shipId: Int, mustBeCurrentRoom: Boolean = false): Float {
            var numPower = 0
            var scaling = if (d.systemPowerScaling.isEmpty()) 1f else d.powerScalingNoSys
            var systemExists = false
            val ship = HsCrew.shipById(crew, shipId)
            for (system in d.systemPowerScaling) {
                if (system == 16) { // SYS_ALL: reactor max
                    systemExists = true
                    numPower += ship?.reactorPower ?: 0
                } else if (system == 17) { // SYS_REACTOR: reactor in use
                    systemExists = true
                    numPower += ship?.let { it.reactorPower - it.powerAvailable } ?: 0
                } else if (ship != null) {
                    val sys = xyz.znix.xftl.hs.lua.LuaShipApi.system(ship, system) ?: continue
                    val room = sys.room ?: continue
                    if (!mustBeCurrentRoom || roomId == room.id) {
                        if (sys.isHackActive) {
                            scaling = d.powerScalingHackedSys
                            systemExists = false
                            break
                        }
                        systemExists = true
                        numPower += HsCrew.effectivePower(sys)
                    }
                }
            }
            if (systemExists) {
                scaling = if (d.powerScaling.isNotEmpty()) d.powerScaling[if (numPower < d.powerScaling.size) numPower else d.powerScaling.size - 1]
                else if (numPower > 0) 1f else 0f
            }
            return scaling
        }
        val crewSrc = d.boostSource == BoostSource.CREW && src != null
        return when (d.powerScalingShipTarget) {
            ShipTarget.ALL -> ship(0) + ship(1)
            ShipTarget.PLAYER_SHIP -> ship(0)
            ShipTarget.ENEMY_SHIP -> ship(1)
            ShipTarget.CURRENT_ALL -> if (crewSrc) ship(src!!.currentShipId) else ship(if (crewSrc) src!!.powerOwner else b.sourceShipId)
            ShipTarget.ORIGINAL_SHIP -> ship(if (crewSrc) src!!.powerOwner else b.sourceShipId)
            ShipTarget.OTHER_ALL -> if (crewSrc) ship(1 - src!!.currentShipId) else ship(1 - (1 - b.sourceShipId))
            ShipTarget.ORIGINAL_OTHER_SHIP -> ship(1 - (if (crewSrc) 1 - src!!.powerOwner else 1 - b.sourceShipId))
            ShipTarget.CURRENT_ROOM -> if (crewSrc) ship(src!!.currentShipId, true) else d.powerScalingNoSys
            else -> d.powerScalingNoSys
        }
    }

    /** CrewMember_Extend::CalculateDangerRating (CrewAI::DangerRating hook). */
    fun calculateDangerRating(health: Float, roomId: Int): Int {
        val personal = ArrayList<StatBoost>()
        for (b in StatBoostManager.dangerBoosts) if (boostCheck(b, roomId) && (b.def.duration == -1f || b.timer.running)) personal.add(b.copy())
        for (list in timedStatBoosts.values) for (b in list)
            if (b.def.dangerRating != -1f && (b.def.duration == -1f || b.timer.running)) personal.add(b.copy())
        CxxSort.sort(personal) { a, b -> a.def.priority < b.def.priority }
        val stackIds = LinkedHashMap<Int, ArrayList<StatBoost>>()
        for (b in personal) {
            if (b.def.stackId != 0) stackIds.getOrPut(b.def.stackId) { ArrayList() }.add(b)
            else if (b.iStacks > 0 && b.def.dangerRating > health) return 0
        }
        for (list in stackIds.values) {
            var stacks = 0
            for (i in list.indices.reversed()) {
                val b = list[i]
                b.iStacks = minOf(b.iStacks, maxOf(b.def.maxStacks - stacks, 0))
                stacks += b.iStacks
                if (b.iStacks > 0 && b.def.dangerRating > health) return 0
            }
        }
        return 1
    }
}

/** Static helpers the stat code uses to read the engine (HS's G_->GetShipManager etc.). */
object HsCrew {
    private var nextSpaceId = 0

    /** g_forceNoSlot: CrewSpawn::SpawnCrew's noSlot for the crew member being created. */
    @JvmField var forceNoSlot = false

    /** Globals::GetNextSpaceId: ++id from 100 (SpaceId.cpp: ShipBuilder::Open sets 100); drones and other SpaceId users
     * should draw from here too so ids match HS. */
    @JvmStatic
    fun nextSelfId(): Int = ++nextSpaceId

    @JvmStatic
    fun resetIds(next: Int = 100) { nextSpaceId = next }

    /** Save a deterministic probe's input counter without consuming an entity id. */
    fun spaceIdState(): Int = nextSpaceId

    @JvmStatic
    fun observeId(id: Int) { if (id > nextSpaceId) nextSpaceId = id }

    /** The crew member's HS state; created on first use (HS creates it with the crew member). */
    @JvmStatic
    fun ex(crew: AbstractCrew): CrewExtend = crew.hsExtend ?: CrewExtend(crew).also { crew.hsExtend = it }

    @JvmStatic
    fun shipId(crew: AbstractCrew): Int {
        val owner = when (crew) {
            is LivingCrew -> crew.ownerShip
            // A pawn built while its drone loads (AbstractIndoorsDrone.loadFromXML) has no owner yet: the ship it
            // stands on until the references resolve (its health comes from the save right after).
            is AbstractIndoorsDrone.Pawn -> crew.drone.ownerShipOrNull ?: crew.room.ship
            else -> null
        }
        return if (owner != null) shipIdOf(owner) else 1
    }

    @JvmStatic
    fun currentShipId(crew: AbstractCrew): Int = if (crew.hsInTeleportLimbo) -1 else shipIdOf(crew.room.ship)

    /**
     * ShipManager::iShipId: 0 for the player's ship. By identity, not the engine's name-based
     * [Ship.isPlayerShip] (HS player ships such as ELITE_SHIP_* do not start with PLAYER_SHIP_); the name is
     * only used before the game has a player ship.
     */
    @JvmStatic
    fun shipIdOf(ship: Ship): Int {
        val player = ship.sys.player ?: return if (ship.isPlayerShip) 0 else 1
        return if (ship === player) 0 else 1
    }

    @JvmStatic
    fun shipById(crew: AbstractCrew, id: Int): Ship? {
        val game = crew.room.ship.sys
        return if (id == 0) game.player else game.enemy
    }

    @JvmStatic
    fun ship(crew: AbstractCrew, id: Int): Ship? = shipById(crew, id)

    @JvmStatic
    fun roomOxygen(crew: AbstractCrew, roomId: Int): Float {
        val ship = shipById(crew, currentShipId(crew)) ?: return 0f
        return (ship.rooms.getOrNull(roomId)?.oxygen ?: 0f) * 100f
    }

    @JvmStatic
    fun fireCount(crew: AbstractCrew, roomId: Int): Int {
        val ship = shipById(crew, currentShipId(crew)) ?: return 0
        return ship.rooms.getOrNull(roomId)?.fires?.count { it != null } ?: 0
    }

    /** ShipSystem::GetEffectivePower. */
    @JvmStatic
    fun effectivePower(sys: xyz.znix.xftl.AbstractSystem): Int = when (sys) {
        is MainSystem -> sys.powerSupplied
        is SubSystem -> sys.effectivePower
        else -> 0
    }
}
