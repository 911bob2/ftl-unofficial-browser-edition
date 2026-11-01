// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp (the custom clonebay
// rewrites: CloneSystem::OnLoop + CFPS::GetSpeedFactor, ShipManager::CloneHealing + CrewMember::DirectModifyHealth,
// CrewMember::Clone, CloneSystem::GetCloneTime/GetJumpHealth, TextLibrary::GetText for the clone texts).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.crew.ClonebayHealing
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.systems.Clonebay
import kotlin.math.ceil

/**
 * The clonebay under Hyperspace's `<customSystems><system id="clone">` levels (MV changes them). Only called while
 * HS.active.
 */
object HsClone {
    /** CustomCloneSystem::GetLevel(sys, passive=false): the effective power, or the health under a hacking pulse. */
    private fun activePower(cb: Clonebay): Int = if (cb.isHackActive) cb.energyLevels - cb.damagedEnergyLevels else cb.powerSupplied

    /** GetLevel(sys, passive=true): the system's health (healthState.first). */
    private fun passivePower(cb: Clonebay): Int = cb.energyLevels - cb.damagedEnergyLevels

    /**
     * CFPS::GetSpeedFactor inside CloneSystem::OnLoop (Windows order: the cloning progress first, then the clone
     * death). Unlocked: the cloning runs at vanillaCloneTime[power-1] / cloneSpeed (999 for cloneSpeed 0, 1 above
     * level 3) and the death at 3 / deathSpeed. With an ion lock (iLockCount > 0) the counter starts one call early:
     * the progress is not changed and the second call (the death) gets the cloning factor while powered.
     */
    @JvmStatic
    fun speedMultiplier(cb: Clonebay, cloning: Boolean): Float {
        val locked = cb.ionTimer > 0f && !cb.hasPermanentLock
        val power = cb.powerSupplied
        val level = HsSystems.cloneLevel(activePower(cb))
        val passive = HsSystems.cloneLevel(passivePower(cb))
        val cloneMul = if (level.cloneSpeed > 0f) {
            val p = power - 1
            if (p >= 0 && p < HsSystems.VANILLA_CLONE_TIME.size) HsSystems.VANILLA_CLONE_TIME[p] / level.cloneSpeed else 1f
        } else 999f
        return if (!locked) {
            if (cloning) (if (power > 0) cloneMul else 1f) else 3f / passive.deathSpeed
        } else {
            if (cloning) 1f else (if (power > 0) cloneMul else 1f)
        }
    }

    /**
     * ShipManager::CloneHealing as HS rewrites it, on the jump: every non-drone crewmember of the player aboard
     * gets jumpHP + jumpHPPercent % of their maximum health (at most the maximum), by the level of the system's
     * health; a destroyed clonebay heals nothing.
     */
    @JvmStatic
    fun jumpHeal(cb: Clonebay) {
        val ship = cb.room!!.ship
        val level = HsSystems.cloneLevel(passivePower(cb))
        for (crew in ship.crew) {
            if (crew !is LivingCrew) continue
            if (crew.ownerShip?.isPlayerShip != true) continue
            var heal = 0f
            if (level.jumpHPPercent > 0) heal += level.jumpHPPercent.toFloat() / 100f * crew.maxHealth
            if (level.jumpHP > 0) heal += level.jumpHP.toFloat()
            if (heal > crew.maxHealth) heal = crew.maxHealth
            crew.dealDamage(ClonebayHealing(-heal))
        }
    }

    /** The skill progress (in actions) before CrewMember::Clone, for [afterClone]. */
    @JvmStatic
    fun skillsBefore(crew: LivingCrew): IntArray {
        val skills = Skill.values()
        return IntArray(skills.size) { i -> actions(crew, skills[i]) }
    }

    private fun actions(crew: LivingCrew, skill: Skill): Int =
        Math.round(crew.info.skills.getValue(skill) / skill.amountPerAction(crew.blueprint))

    /**
     * CrewMember::Clone hook, after the original: the health becomes cloneHPPercent % of the maximum (at least 1;
     * by the effective power), and each skill loses skillLossPercent % of its maximum from the progress it had
     * before cloning, rounded up, at least one action, not below zero (by the system's health).
     */
    @JvmStatic
    fun afterClone(cb: Clonebay, crew: LivingCrew, before: IntArray) {
        val level = HsSystems.cloneLevel(activePower(cb))
        val passive = HsSystems.cloneLevel(passivePower(cb))
        if (level.cloneHPPercent > 0) {
            var hp = level.cloneHPPercent.toFloat() / 100f * crew.maxHealth
            if (hp < 1f) hp = 1f
            crew.health = hp
        }
        // CustomCrew's CrewMember::Clone hook (a race with cloneLoseSkills false) never reaches the clonebay's hook
        if (passive.skillLossPercent > 0 && !xyz.znix.xftl.hs.crew.HsCrewEffects.cloneKeepsSkills(crew)) {
            val skills = Skill.values()
            for (i in skills.indices) {
                val skill = skills[i]
                val max = skill.actionsPerLevel(crew.blueprint) * 2
                var progress = ceil(before[i] - max * passive.skillLossPercent.toDouble() / 100.0).toInt()
                if (progress == before[i]) progress--
                if (progress < 0) progress = 0
                crew.info.skills[skill] = (progress * skill.amountPerAction(crew.blueprint)).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Hyperspace scaleSlugGel (CustomAugments.cpp OuterHull::OnLoop rewrite): PartialRepair(multiplier * 3) with the
     * multiplier the ship's SLUG_GEL value when the option is on, else 0.25 (= the native 0.75). Vanilla keeps [vanilla].
     */
    @JvmStatic
    fun slugGelFraction(ship: xyz.znix.xftl.Ship, vanilla: Float): Float {
        if (!xyz.znix.xftl.hs.HS.active || xyz.znix.xftl.hs.HS.config?.scaleSlugGel != true) return vanilla
        return ship.getAugmentValue("SLUG_GEL") * 3f
    }

    /** CloneSystem::GetCloneTime(level) for the level texts. */
    @JvmStatic
    fun cloneTime(level: Int): Int = HsSystems.cloneLevel(level).cloneSpeed.toInt()

    /**
     * The clonebay level text (`clone_full` with \1 time and \2 heal): GetCloneTime/GetJumpHealth from the levels,
     * and with jumpHPPercent the heal reads "\2 + N%" (or "N%" without jumpHP) (TextLibrary::GetText hook).
     */
    @JvmStatic
    fun levelName(level: Int, text: String): String {
        val l = HsSystems.cloneLevel(level + 1)
        var t = text
        if (l.jumpHPPercent > 0) {
            val pos = t.indexOf("\\2")
            if (pos >= 0) t = t.substring(0, pos) + (if (l.jumpHP > 0) "\\2 + ${l.jumpHPPercent}%" else "${l.jumpHPPercent}%") + t.substring(pos + 2)
        }
        return t.replace("\\1", l.cloneSpeed.toInt().toString()).replace("\\2", l.jumpHP.toString())
    }
}
