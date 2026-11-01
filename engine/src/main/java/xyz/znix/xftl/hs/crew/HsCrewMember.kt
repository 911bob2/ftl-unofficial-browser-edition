// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CrewVTable.cpp (SetupVTable overrides),
// src/game/Ship/CustomCrew.cpp (CrewMember_Extend::Initialize, CrewMember::UpdateHealth, DirectModifyHealth,
// ApplyDamage, ShipDamage, OnLoop, GetRoomDamage, GetDeathSound, GetShootingSound, GetUniqueRepairing, SaveState,
// LoadState, TransformRace, ShipManager::UpdateEnvironment / OnLoop), src/game/Ship/StatBoost.cpp (UpdateTimed)
package xyz.znix.xftl.hs.crew

import org.jdom2.Element
import xyz.znix.xftl.Animations
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.StatBoostManager
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader

/** A health change HS makes directly (CrewMember::DirectModifyHealth): powers, heals, transformations. */
class HsHealthChange(amount: Float) : AbstractCrewDamage(amount)

/**
 * A crew member of a race defined in hyperspace.xml `<crew>`. HS builds a plain CrewMember for these
 * (CustomCrewManager::CreateCrewMember) and replaces its virtual functions with stat lookups (SetupVTable), so every
 * race-specific behaviour comes from [CrewDefinition] and stat boosts. Only created while Hyperspace is active.
 */
class HsCrewMember(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    val ex: CrewExtend get() = HsCrew.ex(this)

    /** CustomCrewManager::GetDefinition(species) (inserting, like HS). */
    val def: CrewDefinition get() = CustomCrewManager.definition(codename)

    /** CrewMember::health.second, which HS keeps up to date itself (CalculateMaxHealth, SetHealthBoost). */
    private var hsHealthMax = 0f
    private var hsReady = false

    private var lastRepairFrame = -1

    init {
        val e = ex
        if (e.originalColorRace.isEmpty()) e.originalColorRace = codename
        if (e.originalRace.isEmpty()) e.originalRace = codename
        // CrewMember constructor hook (CrewVTable.cpp): health.first = GetMaxHealth(). The native constructor has
        // already given the crew member its health, so it counts as alive (Functional) while Initialize runs.
        val statMax = e.calculateStat(CrewStat.MAX_HEALTH, def).toInt()
        hsHealthMax = statMax.toFloat()
        health = hsHealthMax
        hsReady = true
        initialize(false)
        // CrewMember constructor hook (StatBoost.cpp): not while a save loads.
        if (!game.isCurrentlyLoadingSave) StatBoostManager.crewCreated(this, ownerShip)
    }

    /** CrewMember_Extend::Initialize (the parts that are not animation objects). */
    internal fun initialize(isTransform: Boolean) {
        val e = ex
        val d = def
        if (!isTransform) e.calculatePowerDef()
        val passive = e.calculateStat(CrewStat.PASSIVE_HEAL_AMOUNT, d)
        val truePassive = e.calculateStat(CrewStat.TRUE_PASSIVE_HEAL_AMOUNT, d)
        if (passive != 0f || truePassive != 0f) {
            val delay = e.calculateStat(CrewStat.PASSIVE_HEAL_DELAY, d)
            if (delay > 0f && e.passiveHealTimer == null) {
                e.passiveHealTimer = xyz.znix.xftl.hs.stat.HsTimer().also { it.start(delay) }
            }
        }
        e.updateAbilityStatBoosts(d)
        e.noSlot = d.noSlot || HsCrew.forceNoSlot
        HsCrew.forceNoSlot = false
        applyDefaultSkillLevel()
    }

    // ----- SetupVTable -----

    override val animationRace: String
        get() {
            val d = CustomCrewManager.definitionOrNull(codename) ?: return codename
            @Suppress("SENSELESS_COMPARISON")
            val female = info != null && info.isFemale
            return d.animSheet[if (female) 0 else 1].ifEmpty { codename }
        }

    override val baseMaxHealth: Float
        get() = if (!hsReady) 100f else ex.calculateStat(CrewStat.MAX_HEALTH, def)

    override val maxHealth: Float get() = hsHealthMax

    /** CrewMember_Extend::CalculateMaxHealth: rescales the health when the maximum changed. */
    internal fun applyMaxHealth(statMax: Int) {
        val actual = statMax + mindControlHealthBoost
        if (actual != hsHealthMax) {
            health = if (hsHealthMax > 0f) actual * (health / hsHealthMax) else actual
            hsHealthMax = actual
        }
    }

    override fun onHealthBoostChanged(delta: Float) {
        hsHealthMax += delta
    }

    private fun stat(s: CrewStat): Float = ex.calculateStat(s, def)
    private fun flag(s: CrewStat): Boolean = ex.calculateBool(s, def)

    override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * stat(CrewStat.MOVE_SPEED_MULTIPLIER)
    override val repairSpeed: Float get() = super.repairSpeed * stat(CrewStat.REPAIR_SPEED_MULTIPLIER)
    override val attackDamageMult: Float get() = super.attackDamageMult * stat(CrewStat.DAMAGE_MULTIPLIER)
    override val rangedDamageMult: Float get() = stat(CrewStat.RANGED_DAMAGE_MULTIPLIER)
    override val sabotageSpeedMult: Float get() = stat(CrewStat.SABOTAGE_SPEED_MULTIPLIER)
    override val canFight: Boolean get() = flag(CrewStat.CAN_FIGHT)
    override val canRepair: Boolean get() = health > 0f && currentAction != Action.DYING && flag(CrewStat.CAN_REPAIR)
    override val canSabotage: Boolean get() = flag(CrewStat.CAN_SABOTAGE)
    override val canManSystem: Boolean
        get() = stunTime == 0f && currentAction != Action.DYING && flag(CrewStat.CAN_MAN)
    override val fireFightingSpeed: Float get() = stat(CrewStat.FIRE_REPAIR_MULTIPLIER)
    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (MindSystem::OnLoop,
    // CombatAI::UpdateMindControl) and Ship/CrewVTable.cpp (_HS_IsTelepathic): mind control asks IsTelepathic, which
    // reads resistsMindControl only with the resistsMindControlStat option on, isTelepathic otherwise.
    override val isMindControlResistant: Boolean
        get() = flag(if (HS.config?.resistsMindControlStat == true) CrewStat.RESISTS_MIND_CONTROL else CrewStat.IS_TELEPATHIC)
    override val isTelepathic: Boolean get() = flag(CrewStat.IS_TELEPATHIC)
    override val detectsLifeforms: Boolean get() = ex.functional && flag(CrewStat.DETECTS_LIFEFORMS)
    override val throwsOnShootFrame: Boolean get() = def.animBase == "rock" || def.animBase == "mantis"

    override val canPunch: Boolean
        get() {
            val p = def.canPunch
            return if (p.enabled) p.value else !xyz.znix.xftl.hs.weapons.HsDrones.shootsInMelee(animationRace)
        }

    /** CrewMember::UpdateHealth: suffocation needs CanSuffocate and !IsAnaerobic; masks times the modifier. */
    override val suffocationMultiplier: Float
        get() {
            if (!flag(CrewStat.CAN_SUFFOCATE) || flag(CrewStat.IS_ANAEROBIC)) return 0f
            return super.suffocationMultiplier * stat(CrewStat.SUFFOCATION_MODIFIER)
        }

    /** CrewMember::UpdateHealth: fire hurts only crew that CanBurn, scaled by FIRE_DAMAGE_MULTIPLIER. */
    override val fireDamageMult: Float
        get() = if (flag(CrewStat.CAN_BURN)) stat(CrewStat.FIRE_DAMAGE_MULTIPLIER) else 0f

    /**
     * CrewMember::_HS_GetControllable: not dead, and the player's own (iShipId 0, not mind controlled) or an enemy
     * mind controlled while the player has MIND_ORDER.
     */
    private val hsControllable: Boolean
        get() {
            if (health <= 0f) return false
            val id = HsCrew.shipId(this)
            val mc = isMindControlled
            if (id == 0 && !mc) return true
            if (id == 1 && mc) return room.ship.sys.player?.hasAugment("MIND_ORDER") == true
            return false
        }

    /** CrewMember::IsControlledByPlayer(0) (CrewVTable.cpp): selection. */
    override val playerControllable: Boolean
        get() = hsControllable && (flag(CrewStat.CONTROLLABLE) || def.selectable)

    /** IsControlledByPlayer(1): move orders need CONTROLLABLE and CAN_MOVE. */
    override val canBeOrderedToMove: Boolean
        get() = hsControllable && flag(CrewStat.CONTROLLABLE) && flag(CrewStat.CAN_MOVE)

    /** IsControlledByPlayer(-1): the AI skips controllable crew, NO_AI crew and rooted crew. */
    override val skippedByCrewAI: Boolean
        get() {
            if (hsControllable && flag(CrewStat.CONTROLLABLE)) return true
            if (flag(CrewStat.NO_AI)) return true
            return !flag(CrewStat.CAN_MOVE)
        }

    /** ShipManager::OnLoop (CustomCrew.cpp): BONUS_POWER from functional crew, plus one for providesPower. */
    override val bonusSystemPower: Int
        get() = (if (ex.functional) stat(CrewStat.BONUS_POWER).toInt() else 0) + (if (def.providesPower) 1 else 0)

    /**
     * ShipManager::UpdateEnvironment: OxygenSystem::ComputeAirLoss(room, -OXYGEN_CHANGE_SPEED, silent) on a ship
     * with an oxygen system. ComputeAirLoss takes 0.75^distance * speed factor * value off each room's 0..100
     * oxygen, the engine's breach drain is 0.16 per unit of that value per second.
     */
    override val anaerobicOxygenDrainRate: Float
        get() {
            if (health <= 0f || room.ship.oxygen == null) return 0f
            return -stat(CrewStat.OXYGEN_CHANGE_SPEED) * 0.16f
        }

    /** Door::ApplyDamage hook: DOOR_DAMAGE_MULTIPLIER, the fraction rolled with random32. */
    override val doorAttackMultiplier: Int
        get() {
            val damage = stat(CrewStat.DOOR_DAMAGE_MULTIPLIER)
            var i = damage.toInt()
            val f = damage - i
            if (f > 0f && HsCrew.random32() < f * 2147483648f) i++
            return i
        }

    override val shotTimerMinMs: Int get() = def.shootTimer[0].takeIf { it != -1 } ?: super.shotTimerMinMs
    override val shotTimerSpanMs: Int
        get() = if (def.shootTimer[0] != -1) def.shootTimer[1] - def.shootTimer[0] else super.shotTimerSpanMs
    override val punchTimerMinMs: Int get() = def.punchTimer[0].takeIf { it != -1 } ?: super.punchTimerMinMs
    override val punchTimerSpanMs: Int
        get() = if (def.punchTimer[0] != -1) def.punchTimer[1] - def.punchTimer[0] else super.punchTimerSpanMs

    // ----- sounds -----

    private fun animBaseDefault(): String = when (def.animBase) {
        "mantis" -> "mantisSpit"
        "rock" -> "rockThrow"
        else -> "crewLaser"
    }

    /** CrewAnimation::GetShootingSound hook: a random <shootingSound>, else the animation type's own. */
    override fun shootingSoundName(): String? {
        val s = def.shootingSounds
        if (s.isNotEmpty()) return s[HsCrew.random32() % s.size]
        return animBaseDefault() + (1..3).random(ftl.browser.BrowserRandom)
    }

    /** CrewAnimation::GetDeathSound hook: a random <deathSound> for the gender, else the generic one. */
    override fun deathSoundName(): String? {
        val female = info.isFemale
        val s = if (female) def.deathSoundsFemale else def.deathSounds
        if (s.isNotEmpty()) return s[HsCrew.random32() % s.size]
        return if (female) "femaleDeath${(1..3).random(ftl.browser.BrowserRandom)}"
        else "death${(1..2).random(ftl.browser.BrowserRandom)}"
    }

    /**
     * CrewMember::GetUniqueRepairing hook: a random <repairSound> as the repair loop, or none at all. Sounds with an
     * animSoundFrame are queued on frame N of the repair animation instead (player crew only).
     */
    override fun continueRepairSound(dt: Float) {
        val d = def
        if (d.repairSounds.isEmpty()) return
        if (d.repairSoundFrame == -1) {
            game.sounds.getLoopOrNull(d.repairSounds[HsCrew.random32() % d.repairSounds.size])?.continueLoopPlayerOnly(room.ship)
            return
        }
        val frame = icon.frame
        val stepped = frame != lastRepairFrame
        lastRepairFrame = frame
        if (ownerShip?.let { HsCrew.shipIdOf(it) } != 0) return
        if (stepped && frame == d.repairSoundFrame) {
            // Animation::Update plays one of the frame's queued sounds (rand() % count).
            game.sounds.getSampleOrWarn(d.repairSounds[ftl.browser.BrowserRandom.nextInt(d.repairSounds.size)])?.play()
        }
    }

    // ----- health -----

    override fun dealDamage(damage: AbstractCrewDamage) {
        if (game.debugFlags.noCrewDamage.set) return
        if (currentAction == Action.DYING || health == 0f) return
        var amount = damage.amount
        when (damage) {
            // CrewMember::ApplyDamage hook: crew combat.
            is CombatDamage -> amount *= stat(CrewStat.DAMAGE_TAKEN_MULTIPLIER)
            // CrewMember::ShipDamage hook: weapon personnel damage, or its healing.
            is ShipDamage -> amount *= stat(if (amount >= 0f) CrewStat.PERS_DAMAGE_MULTIPLIER else CrewStat.PERS_HEAL_MULTIPLIER)
            // CrewMember::UpdateHealth: the medbay heals at HEAL_SPEED_MULTIPLIER.
            is MedbayHealing -> amount *= stat(CrewStat.HEAL_SPEED_MULTIPLIER)
        }
        directModifyHealth(-amount)
    }

    /** CrewMember::DirectModifyHealth with HS's two hooks (priority 1000 and the 9999 rewrite). */
    fun directModifyHealth(healthModIn: Float): Boolean {
        var healthMod = healthModIn
        val e = ex
        val d = def
        if (healthMod < 0f) {
            if (e.isInvulnerable()) return false
            healthMod *= stat(CrewStat.ALL_DAMAGE_TAKEN_MULTIPLIER)
        }

        if (health > 0f && health + healthMod <= 0f) {
            e.calculateStat(CrewStat.DEATH_EFFECT, d)
            if (e.hasDeathExplosion) {
                val explosion = e.deathEffectChange
                if (explosion.transformRace.isNotEmpty()) {
                    health = 0f
                    e.triggerExplosion = true
                    HsCrew.deathExplosion(this, e)
                    if (explosion.transformRaceDeathSound) deathSoundName()?.let { game.sounds.getSampleOrWarn(it)?.play() }
                    e.originalRace = explosion.transformRace
                    e.transformRaceTo(explosion.transformRace)
                    StatBoostManager.statCacheFrame++
                    val newDef = CustomCrewManager.definition(explosion.transformRace)
                    health = e.calculateStat(CrewStat.MAX_HEALTH, newDef) * explosion.transformRaceHealthFraction +
                            explosion.transformRaceHealth
                    if (health <= 0f) health = 1.0e-9f
                    if (health > hsHealthMax) health = hsHealthMax
                    e.passiveHealTimer?.let {
                        e.isHealing = false
                        it.start(e.calculateStat(CrewStat.PASSIVE_HEAL_DELAY, newDef))
                    }
                    return false
                }
            }
        }

        val ret = baseModifyHealth(healthMod)

        if (healthMod < 0f) e.passiveHealTimer?.let {
            e.isHealing = false
            it.start(e.calculateStat(CrewStat.PASSIVE_HEAL_DELAY, d))
        }
        return ret
    }

    /** The 9999 rewrite of DirectModifyHealth (lowCrewHealth uses LOW_HEALTH_THRESHOLD). */
    private fun baseModifyHealth(healthMod: Float): Boolean {
        if (health <= 0f) return false
        var newHealth = health + healthMod
        if (newHealth < 0f) newHealth = 0f else if (newHealth > hsHealthMax) newHealth = hsHealthMax
        val original = health
        health = newHealth
        if (original > 0f && newHealth == 0f) game.browserAchievements.crewHealthDepleted(this)
        if (healthMod < 0f) {
            val d = def
            val threshold = minOf(stat(CrewStat.LOW_HEALTH_THRESHOLD), hsHealthMax * d.lowHealthThresholdPercentage)
            if (newHealth.toInt() <= threshold && original.toInt() > threshold && ownerShip?.let { HsCrew.shipIdOf(it) } == 0)
                game.sounds.getSampleOrWarn("lowCrewHealth")?.play()
        }
        return newHealth <= 0f
    }

    /** The HS heal amounts of CrewMember::UpdateHealth (medbay healing reaches [dealDamage]). */
    private fun updateHealHs(dt: Float) {
        if (health <= 0f) return
        val e = ex
        val mod = stat(CrewStat.HEAL_SPEED_MULTIPLIER)
        val passive = stat(CrewStat.PASSIVE_HEAL_AMOUNT)
        val active = stat(CrewStat.ACTIVE_HEAL_AMOUNT)
        val trueHeal = stat(CrewStat.TRUE_HEAL_AMOUNT)
        val truePassive = stat(CrewStat.TRUE_PASSIVE_HEAL_AMOUNT)
        e.healMark = false
        if (active != 0f) {
            if (active > 0f && health != hsHealthMax) { markBeingHealed(); e.healMark = true }
            directModifyHealth(dt * active * mod)
        }
        if (e.isHealing && passive != 0f && health != hsHealthMax) {
            if (passive > 0f) { markBeingHealed(); e.healMark = true }
            directModifyHealth(dt * passive * mod)
        }
        if (trueHeal != 0f) {
            if (trueHeal > 0f && health != hsHealthMax) { markBeingHealed(); e.healMark = true }
            directModifyHealth(dt * trueHeal)
        }
        if (e.isHealing && truePassive != 0f && health != hsHealthMax) {
            if (truePassive > 0f) { markBeingHealed(); e.healMark = true }
            directModifyHealth(dt * truePassive)
        }
    }

    /**
     * The rooted swap (CustomCrew.cpp CrewMember::OnLoop): the crew member of our side holding [here] leaves it for
     * another slot of the room (MoveToRoom to the taken slot), or the nearest free one, when it can move itself.
     */
    private fun bumpFromSlot(here: xyz.znix.xftl.math.RoomPoint) {
        val other = room.ship.crew.firstOrNull { o ->
            o !== this && o.mode == mode && o.pathingTarget.let { t -> t != null && t.room === here.room && (t posEq here) }
        } ?: return
        val oex = HsCrew.ex(other)
        if (!oex.calculateBool(CrewStat.CAN_MOVE, oex.definition())) return
        val alt = here.room.firstFreeSlot(mode)
        if (alt != null) other.setTargetRoom(here.room, alt)
        else runCatching { room.ship.findSpaceForCrew(here.room, mode) }.getOrNull()?.let { other.setTargetRoom(it.room, it) }
    }

    // ----- the loop -----

    override fun update(dt: Float) {
        // the whole HS step counts as this crew member's update (Lua single steps asked for meanwhile wait for it)
        hsUpdateDepth++
        try { updateHs(dt) } finally { hsUpdateDepth--; if (hsUpdateDepth == 0) hsRunPendingSteps() }
    }

    /** CrewMember::UpdateHealth (HS rewrite): fire, suffocation, the medbay and this race's heal amounts. */
    override fun hsHealthStep(dt: Float, medbay: Float) {
        super.hsHealthStep(dt, medbay)
        updateHealHs(dt)
    }

    private fun updateHs(dt: Float) {
        val e = ex
        val d = def
        e.calculateMaxHealth(d)
        // fMedbay for this frame: the medbay/nano rate while hurt, 0 at full health (oracle statcrew-powers medbay:10)
        e.medbayRate = if (health < hsHealthMax) HsCrew.medbayBase(this) else 0f
        if (!hsAnimTeleport) updateHealHs(dt) // OnLoop: no UpdateHealth in the teleport animation state

        // CrewMember::OnLoop (CustomCrew.cpp) rooted effect: a crew member that cannot move stops in the slot it
        // stands in, moving a movable crew member of the same side that holds that slot to another one, and makes
        // no moves of its own during its loop (MoveToRoom hook).
        val rooted = !flag(CrewStat.CAN_MOVE)
        if (rooted && health > 0f) {
            val here = findNearestRoomPos()
            val target = pathingTarget
            if (target != null && here.room.containsRelative(here) && (target.room != here.room || !(target posEq here))) {
                if (!here.room.isSlotFree(here, mode, this)) bumpFromSlot(here)
                setTargetRoom(here.room, here)
            }
        }
        hsMoveLock = rooted
        try {
            super.update(dt)
        } finally {
            hsMoveLock = false
        }

        // CrewMember::OnLoop (priority 1000, StatBoost.cpp): timed boosts tick and expired ones go.
        StatBoostManager.updateTimed(e, dt)
        HsCrewRender.updateBoostAnims(e, dt)

        // CrewMember::OnLoop (priority 1000) after the native loop.
        e.deathTimer?.let { t ->
            t.update(dt)
            if (t.done()) health = 0f
        }

        val delay = stat(CrewStat.PASSIVE_HEAL_DELAY)
        val timer = e.passiveHealTimer
        if (timer == null && delay != 0f) {
            e.passiveHealTimer = xyz.znix.xftl.hs.stat.HsTimer().also { it.start(delay) }
        } else if (timer != null && delay == 0f) {
            e.passiveHealTimer = null
            e.isHealing = true
        } else if (timer != null) {
            timer.update(dt)
            if (timer.done()) e.isHealing = true
        } else {
            e.isHealing = true
        }

        // Stun modifier
        if (stunTime != 0f) {
            if (stunTime < e.prevStun) {
                val mult = stat(CrewStat.STUN_MULTIPLIER)
                if (mult > 0f) {
                    stunTime = maxOf(0f, e.prevStun - ((e.prevStun - stunTime) * (1f / mult)))
                } else {
                    stunTime = 0f
                }
            }
        }
        e.prevStun = stunTime

        // Crew ability loop/activation
        e.calculatePowerDef()
        for (p in ArrayList(e.crewPowers)) p.onUpdate(dt)
        for (p in ArrayList(e.crewPowers)) HsCrew.powerDamageArea(p)
        // ShipManager::UpdateCrewmembers: damageEnemiesAmount / healCrewAmount (room auras)
        HsCrewEffects.roomAuras(this, dt)
        for (r in ArrayList(e.powerResources)) r.onUpdate(dt)

        // CrewMember::OnLoop (CustomCrew.cpp 3960): arm the death explosion while dying.
        if (currentAction == Action.DYING) {
            if (!e.exploded && !e.triggerExplosion) e.triggerExplosion = true
            HsCrew.deathExplosion(this, e)
        } else if (health > 0f) {
            e.triggerExplosion = false
            e.exploded = false
        }

        // ShipManager::OnLoop (CustomCrew.cpp): name and stat transformations.
        HsCrew.nameTransform(this, e)
        e.calculateStat(CrewStat.TRANSFORM_RACE, def)
        if (e.transformRace != codename) e.transformRaceTo(e.transformRace)
    }

    /** CrewMember::Restart with HS's hooks (StatBoost.cpp: cloneClear boosts go; CustomCrew.cpp: power charges). */
    override fun hsRestart() {
        StatBoostManager.crewRestarted(ex)
        CrewPowerLogic.restart(ex)
        super.hsRestart()
        hsHealthMax = ex.calculateStat(CrewStat.MAX_HEALTH, def) + mindControlHealthBoost
        health = hsHealthMax
    }

    /** A CrewStat of this crew member (CrewMember_Extend::CalculateStat with its definition). */
    fun hsStat(s: CrewStat): Float = stat(s)

    override fun onCloned() {
        super.onCloned()
        // CrewMember::Restart: powers and cloneClear boosts.
        CrewPowerLogic.restart(ex)
        StatBoostManager.crewRestarted(ex)
        hsHealthMax = ex.calculateStat(CrewStat.MAX_HEALTH, def) + mindControlHealthBoost
        health = hsHealthMax
    }

    // ----- skills -----

    override fun onInfoAssigned() {
        applyDefaultSkillLevel()
    }

    /** Initialize: no skill below DEFAULT_SKILL_LEVEL / 2 of its maximum. */
    private fun applyDefaultSkillLevel() {
        @Suppress("SENSELESS_COMPARISON")
        if (info == null || !hsReadyForSkills()) return
        val level = ex.calculateStat(CrewStat.DEFAULT_SKILL_LEVEL, def).toInt()
        if (level <= 0) return
        val min = minOf(1f, level / 2f)
        for (s in Skill.entries) if (info.skills.getValue(s) < min) info.skills[s] = min
    }

    private fun hsReadyForSkills(): Boolean = hsExtend != null

    // ----- saves (CrewMember::SaveState / LoadState hooks) -----

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        val hs = Element("hsCrew")
        HsCrewSave.save(this, ex, hs, hsHealthMax)
        elem.addContent(hs)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        super.loadFromXML(elem, refs)
        val hs = elem.getChild("hsCrew") ?: return
        HsCrewSave.load(this, ex, hs)?.let { (h, max) ->
            hsHealthMax = max
            health = h
        }
    }
}
