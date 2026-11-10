// Lua adapters for Hyperspace's crew and stat API (CrewMember.extend, CrewMember_Extend, ActivatedPower,
// CrewDefinition, StatBoostDefinition, StatBoost, StatBoostManager) as FTL-Hyperspace (GPL-3.0-or-later) 1b05a07
// lua/modules/hyperspace.i exposes them.
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.lua.*
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.HsTimer
import xyz.znix.xftl.hs.stat.StatBoost
import xyz.znix.xftl.hs.stat.StatBoostDefinition
import xyz.znix.xftl.hs.stat.StatBoostDefs
import xyz.znix.xftl.hs.stat.StatBoostManager
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Hyperspace.StatBoostManager.GetInstance(). */
object LuaStatBoostManager : LuaTyped { override val luaType get() = A.T_StatBoostManager }

object StatCrewLua {
    private fun d(b: Boolean) = if (b) 1.0 else 0.0

    /** A struct-backed object's numeric field (the [LuaStructs] fallback for types shared with Kotlin objects). */
    private fun structNum(o: LuaStruct, a: Int): Double = when (val v = o.values[a]) {
        is Double -> v; is Boolean -> d(v); else -> 0.0
    }

    /** HS's statBoostDefs vector holds the XML definitions first (realBoostId = index), Lua appends after them. */
    @JvmStatic
    fun shareDefinitions() {
        val list = LuaAdaptersMore.statBoostDefs
        list.removeAll { it is StatBoostDefinition }
        list.addAll(0, StatBoostDefs.defs)
    }

    @JvmStatic
    fun install() {
        shareDefinitions()
        extend()
        powers()
        definitions()
        statBoosts()
        crewMembers()
    }

    private fun ex(o: Any?): CrewExtend = o as CrewExtend

    private fun extend() {
        LuaReg.obj(A.A_CrewMember_extend, ObjGet { HsCrew.ex(it as AbstractCrew) })
        LuaReg.obj(A.A_CrewMember_Extend_orig, ObjGet { ex(it).crew })
        LuaReg.num(A.A_CrewMember_Extend_selfId, NumGet { ex(it).selfId.toDouble() })
        LuaReg.num(A.A_CrewMember_Extend_isHealing, NumGet { d(ex(it).isHealing) }, NumSet { o, v -> ex(o).isHealing = v != 0.0 })
        LuaReg.num(A.A_CrewMember_Extend_noSlot, NumGet { d(ex(it).noSlot) }, NumSet { o, v -> ex(o).noSlot = v != 0.0 })
        LuaReg.num(A.A_CrewMember_Extend_noClone, NumGet { d(ex(it).noClone) }, NumSet { o, v -> ex(o).noClone = v != 0.0 })
        LuaReg.num(A.A_CrewMember_Extend_hasSpecialPower, NumGet { d(ex(it).hasSpecialPower) })
        LuaReg.num(A.A_CrewMember_Extend_exploded, NumGet { d(ex(it).exploded) }, NumSet { o, v -> ex(o).exploded = v != 0.0 })
        LuaReg.num(A.A_CrewMember_Extend_triggerExplosion, NumGet { d(ex(it).triggerExplosion) },
            NumSet { o, v -> ex(o).triggerExplosion = v != 0.0 })
        LuaReg.str(A.A_CrewMember_Extend_transformRace, StrGet { ex(it).transformRace }, StrSet { o, v -> ex(o).transformRace = v })
        LuaReg.obj(A.A_CrewMember_Extend_deathTimer, ObjGet { ex(it).deathTimer })
        LuaReg.obj(A.A_CrewMember_Extend_passiveHealTimer, ObjGet { ex(it).passiveHealTimer })
        LuaReg.obj(A.A_CrewMember_Extend_crewPowers, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewMember_Extend_crewPowers) { x ->
                ListView(A.T_vector_ActivatedPower, A.T_ActivatedPower) { (x as CrewExtend).crewPowers }
            }
        })
        LuaReg.obj(A.A_CrewMember_Extend_powerResources, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewMember_Extend_powerResources) { x ->
                ListView(A.T_vector_ActivatedPowerResource, A.T_ActivatedPowerResource) { (x as CrewExtend).powerResources }
            }
        })

        // CalculateStat(stat, def, bool* OUTPUT) / (stat, def) / (stat, bool* OUTPUT) / (stat): SWIG's dispatch
        // picks the overload; the OUTPUT ones also return the boolean.
        fun calc(withDef: Boolean, withBool: Boolean): LuaMethod = LuaMethod {
            val e = ex(arg.obj(1))
            val stat = CrewStat.entries[arg.int(2)]
            val def = if (withDef) arg.obj(3) as? CrewDefinition else e.definition()
            val v = e.calculateStat(stat, def)
            ret.num(v)
            if (withBool) { ret.bool(e.boolResult); 2 } else 1
        }
        LuaReg.method(A.M_CrewMember_Extend_CalculateStat__SWIG_0, calc(true, true))
        LuaReg.method(A.M_CrewMember_Extend_CalculateStat__SWIG_1, calc(true, false))
        LuaReg.method(A.M_CrewMember_Extend_CalculateStat__SWIG_2, calc(false, true))
        LuaReg.method(A.M_CrewMember_Extend_CalculateStat__SWIG_3, calc(false, false))
        LuaReg.method(A.M_CrewMember_Extend_GetDefinition, LuaMethod { ret.obj(ex(arg.obj(1)).definition(), A.T_CrewDefinition) })

        // TimerHelper (HS's own timers on the crew)
        LuaReg.num(A.A_TimerHelper_currTime, NumGet { (it as HsTimer).currTime.toDouble() }, NumSet { o, v -> (o as HsTimer).currTime = v.toFloat() })
        LuaReg.num(A.A_TimerHelper_currGoal, NumGet { (it as HsTimer).currGoal.toDouble() }, NumSet { o, v -> (o as HsTimer).currGoal = v.toFloat() })
        LuaReg.num(A.A_TimerHelper_running, NumGet { d((it as HsTimer).running) }, NumSet { o, v -> (o as HsTimer).running = v != 0.0 })
        LuaReg.num(A.A_TimerHelper_loop, NumGet { d((it as HsTimer).loop) }, NumSet { o, v -> (o as HsTimer).loop = v != 0.0 })
    }

    private fun p(o: Any?): ActivatedPower = o as ActivatedPower

    private fun powers() {
        LuaReg.obj(A.A_ActivatedPower_crew, ObjGet { p(it).crew })
        LuaReg.obj(A.A_ActivatedPower_crew_ex, ObjGet { p(it).ex })
        LuaReg.obj(A.A_ActivatedPower_def, ObjGet { p(it).def })
        LuaReg.num(A.A_ActivatedPower_enabled, NumGet { d(p(it).enabled) }, NumSet { o, v -> p(o).enabled = v != 0.0 })
        LuaReg.num(A.A_ActivatedPower_modifiedPowerCharges, NumGet { p(it).modifiedPowerCharges.toDouble() },
            NumSet { o, v -> p(o).modifiedPowerCharges = v.toFloat() })
        LuaReg.num(A.A_ActivatedPower_modifiedChargesPerJump, NumGet { p(it).modifiedChargesPerJump.toDouble() },
            NumSet { o, v -> p(o).modifiedChargesPerJump = v.toFloat() })
        LuaReg.num(A.A_ActivatedPower_powerRoom, NumGet { p(it).powerRoom.toDouble() }, NumSet { o, v -> p(o).powerRoom = v.toInt() })
        LuaReg.num(A.A_ActivatedPower_powerShip, NumGet { p(it).powerShip.toDouble() }, NumSet { o, v -> p(o).powerShip = v.toInt() })
        LuaReg.num(A.A_ActivatedPower_powerActivated, NumGet { d(p(it).powerActivated) }, NumSet { o, v -> p(o).powerActivated = v != 0.0 })
        LuaReg.num(A.A_ActivatedPower_temporaryPowerActive, NumGet { d(p(it).temporaryPowerActive) },
            NumSet { o, v -> p(o).temporaryPowerActive = v != 0.0 })
        LuaReg.num(A.A_ActivatedPower_powerDone, NumGet { d(p(it).powerDone) }, NumSet { o, v -> p(o).powerDone = v != 0.0 })
        LuaReg.num(A.A_ActivatedPower_temporaryPowerDone, NumGet { d(p(it).temporaryPowerDone) },
            NumSet { o, v -> p(o).temporaryPowerDone = v != 0.0 })
        LuaReg.obj(A.A_ActivatedPower_powerCooldown, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPower_powerCooldown) { x ->
                val c = (x as PowerBase).powerCooldown
                FloatPairView(A.T_pair_float_float, { c.first }, { c.first = it }, { c.second }, { c.second = it })
            }
        })
        LuaReg.obj(A.A_ActivatedPower_temporaryPowerDuration, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPower_temporaryPowerDuration) { x ->
                val c = (x as ActivatedPower).temporaryPowerDuration
                FloatPairView(A.T_pair_float_float, { c.first }, { c.first = it }, { c.second }, { c.second = it })
            }
        })
        LuaReg.obj(A.A_ActivatedPower_powerCharges, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPower_powerCharges) { x ->
                val c = (x as PowerBase).powerCharges
                IntPairView(A.T_pair_int_int, { c.first }, { c.first = it }, { c.second }, { c.second = it })
            }
        })
        LuaReg.obj(A.A_ActivatedPower_powerResources, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPower_powerResources) { x ->
                ListView(A.T_vector_ActivatedPowerResource, A.T_ActivatedPowerResource) { (x as ActivatedPower).powerResources }
            }
        })
        LuaReg.method(A.M_ActivatedPower_PowerReady, LuaMethod { ret.num(p(arg.obj(1)).powerReady()) })
        LuaReg.method(A.M_ActivatedPower_PowerReq, LuaMethod { ret.num(p(arg.obj(1)).powerReq(arg.obj(2) as ActivatedPowerRequirements)) })
        LuaReg.method(A.M_ActivatedPower_PreparePower, LuaMethod { p(arg.obj(1)).preparePower(); 0 })
        LuaReg.method(A.M_ActivatedPower_ActivatePower, LuaMethod { p(arg.obj(1)).activatePower(); 0 })
        LuaReg.method(A.M_ActivatedPower_ActivateTemporaryPower, LuaMethod { p(arg.obj(1)).activateTemporaryPower(); 0 })
        LuaReg.method(A.M_ActivatedPower_TemporaryPowerFinished, LuaMethod { p(arg.obj(1)).temporaryPowerFinished(); 0 })
        LuaReg.method(A.M_ActivatedPower_CancelPower, LuaMethod { p(arg.obj(1)).cancelPower(arg.bool(2)); 0 })
        LuaReg.method(A.M_ActivatedPower_EnablePower, LuaMethod { p(arg.obj(1)).enablePower(); 0 })
        LuaReg.method(A.M_ActivatedPower_DisablePower, LuaMethod { p(arg.obj(1)).disablePower(); 0 })
        LuaReg.method(A.M_ActivatedPower_EnableInit, LuaMethod { p(arg.obj(1)).enableInit(); 0 })
        LuaReg.method(A.M_ActivatedPower_ChangePowerDef, LuaMethod { p(arg.obj(1)).changePowerDef(arg.obj(2) as ActivatedPowerDefinition); 0 })

        fun r(o: Any?) = o as ActivatedPowerResource
        LuaReg.obj(A.A_ActivatedPowerResource_crew, ObjGet { r(it).crew })
        LuaReg.obj(A.A_ActivatedPowerResource_crew_ex, ObjGet { r(it).ex })
        LuaReg.obj(A.A_ActivatedPowerResource_def, ObjGet { r(it).def })
        LuaReg.num(A.A_ActivatedPowerResource_enabled, NumGet { d(r(it).enabled) }, NumSet { o, v -> r(o).enabled = v != 0.0 })
        LuaReg.obj(A.A_ActivatedPowerResource_powerCooldown, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPowerResource_powerCooldown) { x ->
                val c = (x as PowerBase).powerCooldown
                FloatPairView(A.T_pair_float_float, { c.first }, { c.first = it }, { c.second }, { c.second = it })
            }
        })
        LuaReg.obj(A.A_ActivatedPowerResource_powerCharges, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPowerResource_powerCharges) { x ->
                val c = (x as PowerBase).powerCharges
                IntPairView(A.T_pair_int_int, { c.first }, { c.first = it }, { c.second }, { c.second = it })
            }
        })
        LuaReg.method(A.M_ActivatedPowerResource_EnablePower, LuaMethod { r(arg.obj(1)).enablePower(); 0 })
        LuaReg.method(A.M_ActivatedPowerResource_DisablePower, LuaMethod { r(arg.obj(1)).disablePower(); 0 })
        LuaReg.method(A.M_ActivatedPowerResource_EnableInit, LuaMethod { r(arg.obj(1)).enableInit(); 0 })
    }

    private fun toggleF(a: Int, get: (Any) -> Toggle<Float>) =
        LuaReg.obj(a, ObjGet { get(it!!) })

    private fun definitions() {
        // ActivatedPowerDefinition
        fun ad(o: Any?) = o as ActivatedPowerDefinition
        LuaReg.str(A.A_ActivatedPowerDefinition_name, StrGet { ad(it).name })
        LuaReg.num(A.A_ActivatedPowerDefinition_cooldown, NumGet { ad(it).cooldown.toDouble() }, NumSet { o, v -> ad(o).cooldown = v.toFloat() })
        LuaReg.num(A.A_ActivatedPowerDefinition_crewHealth, NumGet { ad(it).crewHealth.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_enemyHealth, NumGet { ad(it).enemyHealth.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_selfHealth, NumGet { ad(it).selfHealth.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_powerCharges, NumGet { ad(it).powerCharges.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_initialCharges, NumGet { ad(it).initialCharges.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_chargesPerJump, NumGet { ad(it).chargesPerJump.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_respawnCharges, NumGet { ad(it).respawnCharges.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_hasTemporaryPower, NumGet { d(ad(it).hasTemporaryPower) })
        LuaReg.num(A.A_ActivatedPowerDefinition_hasSpecialPower, NumGet { d(ad(it).hasSpecialPower) })
        LuaReg.num(A.A_ActivatedPowerDefinition_activateWhenReady, NumGet { d(ad(it).activateWhenReady) })
        LuaReg.num(A.A_ActivatedPowerDefinition_win, NumGet { d(ad(it).win) })
        LuaReg.num(A.A_ActivatedPowerDefinition_sortOrder, NumGet { ad(it).sortOrder.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_activateGroupIndex, NumGet { ad(it).activateGroupIndex.toDouble() })
        LuaReg.num(A.A_ActivatedPowerDefinition_replaceGroupIndex, NumGet { ad(it).replaceGroupIndex.toDouble() })
        LuaReg.str(A.A_ActivatedPowerDefinition_transformRace, StrGet { ad(it).transformRace })
        LuaReg.obj(A.A_ActivatedPowerDefinition_tempPower, ObjGet { ad(it).tempPower })
        LuaReg.obj(A.A_ActivatedPowerDefinition_playerReq, ObjGet { ad(it).playerReq })
        LuaReg.obj(A.A_ActivatedPowerDefinition_enemyReq, ObjGet { ad(it).enemyReq })
        LuaReg.obj(A.A_ActivatedPowerDefinition_chargeReq, ObjGet { ad(it).chargeReq })
        LuaReg.obj(A.A_ActivatedPowerDefinition_statBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_statBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) { (x as ActivatedPowerDefinition).statBoosts }
            }
        })
        LuaReg.obj(A.A_ActivatedPowerDefinition_roomStatBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_roomStatBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) { (x as ActivatedPowerDefinition).roomStatBoosts }
            }
        })

        // TemporaryPowerDefinition
        fun td(o: Any?) = o as TemporaryPowerDefinition
        LuaReg.num(A.A_TemporaryPowerDefinition_duration, NumGet { td(it).duration.toDouble() })
        LuaReg.num(A.A_TemporaryPowerDefinition_invulnerable, NumGet { d(td(it).invulnerable) })
        LuaReg.num(A.A_TemporaryPowerDefinition_animFrame, NumGet { td(it).animFrame.toDouble() })
        LuaReg.str(A.A_TemporaryPowerDefinition_effectAnim, StrGet { td(it).effectAnim })
        LuaReg.str(A.A_TemporaryPowerDefinition_effectFinishAnim, StrGet { td(it).effectFinishAnim })
        LuaReg.str(A.A_TemporaryPowerDefinition_animSheet, StrGet { td(it).animSheet })
        LuaReg.obj(A.A_TemporaryPowerDefinition_statBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_TemporaryPowerDefinition_statBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) { (x as TemporaryPowerDefinition).statBoosts }
            }
        })
        val tf: Map<Int, (TemporaryPowerDefinition) -> Toggle<*>> = mapOf(
            A.A_TemporaryPowerDefinition_maxHealth to { t -> t.maxHealth },
            A.A_TemporaryPowerDefinition_stunMultiplier to { t -> t.stunMultiplier },
            A.A_TemporaryPowerDefinition_moveSpeedMultiplier to { t -> t.moveSpeedMultiplier },
            A.A_TemporaryPowerDefinition_damageMultiplier to { t -> t.damageMultiplier },
            A.A_TemporaryPowerDefinition_rangedDamageMultiplier to { t -> t.rangedDamageMultiplier },
            A.A_TemporaryPowerDefinition_doorDamageMultiplier to { t -> t.doorDamageMultiplier },
            A.A_TemporaryPowerDefinition_repairSpeed to { t -> t.repairSpeed },
            A.A_TemporaryPowerDefinition_fireRepairMultiplier to { t -> t.fireRepairMultiplier },
            A.A_TemporaryPowerDefinition_controllable to { t -> t.controllable },
            A.A_TemporaryPowerDefinition_canFight to { t -> t.canFight },
            A.A_TemporaryPowerDefinition_canRepair to { t -> t.canRepair },
            A.A_TemporaryPowerDefinition_canSabotage to { t -> t.canSabotage },
            A.A_TemporaryPowerDefinition_canMan to { t -> t.canMan },
            A.A_TemporaryPowerDefinition_canTeleport to { t -> t.canTeleport },
            A.A_TemporaryPowerDefinition_canSuffocate to { t -> t.canSuffocate },
            A.A_TemporaryPowerDefinition_canBurn to { t -> t.canBurn },
            A.A_TemporaryPowerDefinition_oxygenChangeSpeed to { t -> t.oxygenChangeSpeed },
            A.A_TemporaryPowerDefinition_canPhaseThroughDoors to { t -> t.canPhaseThroughDoors },
            A.A_TemporaryPowerDefinition_fireDamageMultiplier to { t -> t.fireDamageMultiplier },
            A.A_TemporaryPowerDefinition_persDamageMultiplier to { t -> t.persDamageMultiplier },
            A.A_TemporaryPowerDefinition_persHealMultiplier to { t -> t.persHealMultiplier },
            A.A_TemporaryPowerDefinition_isTelepathic to { t -> t.isTelepathic },
            A.A_TemporaryPowerDefinition_resistsMindControl to { t -> t.resistsMindControl },
            A.A_TemporaryPowerDefinition_isAnaerobic to { t -> t.isAnaerobic },
            A.A_TemporaryPowerDefinition_detectsLifeforms to { t -> t.detectsLifeforms },
            A.A_TemporaryPowerDefinition_damageTakenMultiplier to { t -> t.damageTakenMultiplier },
            A.A_TemporaryPowerDefinition_cloneSpeedMultiplier to { t -> t.cloneSpeedMultiplier },
            A.A_TemporaryPowerDefinition_passiveHealAmount to { t -> t.passiveHealAmount },
            A.A_TemporaryPowerDefinition_truePassiveHealAmount to { t -> t.truePassiveHealAmount },
            A.A_TemporaryPowerDefinition_healAmount to { t -> t.healAmount },
            A.A_TemporaryPowerDefinition_trueHealAmount to { t -> t.trueHealAmount },
            A.A_TemporaryPowerDefinition_passiveHealDelay to { t -> t.passiveHealDelay },
            A.A_TemporaryPowerDefinition_sabotageSpeedMultiplier to { t -> t.sabotageSpeedMultiplier },
            A.A_TemporaryPowerDefinition_allDamageTakenMultiplier to { t -> t.allDamageTakenMultiplier },
            A.A_TemporaryPowerDefinition_healSpeed to { t -> t.healSpeed },
            A.A_TemporaryPowerDefinition_suffocationModifier to { t -> t.suffocationModifier },
            A.A_TemporaryPowerDefinition_healCrewAmount to { t -> t.healCrewAmount },
            A.A_TemporaryPowerDefinition_powerDrain to { t -> t.powerDrain },
            A.A_TemporaryPowerDefinition_powerDrainFriendly to { t -> t.powerDrainFriendly },
            A.A_TemporaryPowerDefinition_bonusPower to { t -> t.bonusPower },
            A.A_TemporaryPowerDefinition_damageEnemiesAmount to { t -> t.damageEnemiesAmount },
            A.A_TemporaryPowerDefinition_hackDoors to { t -> t.hackDoors },
            A.A_TemporaryPowerDefinition_powerRechargeMultiplier to { t -> t.powerRechargeMultiplier },
            A.A_TemporaryPowerDefinition_noClone to { t -> t.noClone },
            A.A_TemporaryPowerDefinition_noAI to { t -> t.noAI },
            A.A_TemporaryPowerDefinition_validTarget to { t -> t.validTarget },
            A.A_TemporaryPowerDefinition_canMove to { t -> t.canMove },
            A.A_TemporaryPowerDefinition_teleportMove to { t -> t.teleportMove },
            A.A_TemporaryPowerDefinition_teleportMoveOtherShip to { t -> t.teleportMoveOtherShip },
            A.A_TemporaryPowerDefinition_silenced to { t -> t.silenced },
            A.A_TemporaryPowerDefinition_lowHealthThreshold to { t -> t.lowHealthThreshold },
        )
        for ((a, f) in tf) LuaReg.obj(a, ObjGet { f(td(it)) })
        // ToggleValue<T>
        fun tv(o: Any?): Double = when (val v = (o as Toggle<*>).value) {
            is Number -> v.toDouble(); is Boolean -> d(v); else -> 0.0
        }
        for (a in intArrayOf(A.A_ToggleValue_float_value, A.A_ToggleValue_int_value, A.A_ToggleValue_bool_value))
            LuaReg.num(a, NumGet { tv(it) })
        for (a in intArrayOf(A.A_ToggleValue_float_enabled, A.A_ToggleValue_int_enabled, A.A_ToggleValue_bool_enabled))
            LuaReg.num(a, NumGet { d((it as Toggle<*>).enabled) })

        // CrewDefinition (numbers and flags)
        fun cd(o: Any?) = o as CrewDefinition
        LuaReg.str(A.A_CrewDefinition_race, StrGet { cd(it).race })
        LuaReg.str(A.A_CrewDefinition_animBase, StrGet { cd(it).animBase })
        val nums: Map<Int, (CrewDefinition) -> Double> = mapOf(
            A.A_CrewDefinition_maxHealth to { c -> c.maxHealth.toDouble() },
            A.A_CrewDefinition_stunMultiplier to { c -> c.stunMultiplier.toDouble() },
            A.A_CrewDefinition_moveSpeedMultiplier to { c -> c.moveSpeedMultiplier.toDouble() },
            A.A_CrewDefinition_repairSpeed to { c -> c.repairSpeed.toDouble() },
            A.A_CrewDefinition_damageMultiplier to { c -> c.damageMultiplier.toDouble() },
            A.A_CrewDefinition_rangedDamageMultiplier to { c -> c.rangedDamageMultiplier.toDouble() },
            A.A_CrewDefinition_doorDamageMultiplier to { c -> c.doorDamageMultiplier.toDouble() },
            A.A_CrewDefinition_cloneSpeedMultiplier to { c -> c.cloneSpeedMultiplier.toDouble() },
            A.A_CrewDefinition_bonusPower to { c -> c.bonusPower.toDouble() },
            A.A_CrewDefinition_fireRepairMultiplier to { c -> c.fireRepairMultiplier.toDouble() },
            A.A_CrewDefinition_suffocationModifier to { c -> c.suffocationModifier.toDouble() },
            A.A_CrewDefinition_fireDamageMultiplier to { c -> c.fireDamageMultiplier.toDouble() },
            A.A_CrewDefinition_persDamageMultiplier to { c -> c.persDamageMultiplier.toDouble() },
            A.A_CrewDefinition_persHealMultiplier to { c -> c.persHealMultiplier.toDouble() },
            A.A_CrewDefinition_oxygenChangeSpeed to { c -> c.oxygenChangeSpeed.toDouble() },
            A.A_CrewDefinition_damageTakenMultiplier to { c -> c.damageTakenMultiplier.toDouble() },
            A.A_CrewDefinition_passiveHealAmount to { c -> c.passiveHealAmount.toDouble() },
            A.A_CrewDefinition_truePassiveHealAmount to { c -> c.truePassiveHealAmount.toDouble() },
            A.A_CrewDefinition_healAmount to { c -> c.healAmount.toDouble() },
            A.A_CrewDefinition_trueHealAmount to { c -> c.trueHealAmount.toDouble() },
            A.A_CrewDefinition_passiveHealDelay to { c -> c.passiveHealDelay.toDouble() },
            A.A_CrewDefinition_sabotageSpeedMultiplier to { c -> c.sabotageSpeedMultiplier.toDouble() },
            A.A_CrewDefinition_allDamageTakenMultiplier to { c -> c.allDamageTakenMultiplier.toDouble() },
            A.A_CrewDefinition_defaultSkillLevel to { c -> c.defaultSkillLevel.toDouble() },
            A.A_CrewDefinition_healSpeed to { c -> c.healSpeed.toDouble() },
            A.A_CrewDefinition_healCrewAmount to { c -> c.healCrewAmount.toDouble() },
            A.A_CrewDefinition_powerDrain to { c -> c.powerDrain.toDouble() },
            A.A_CrewDefinition_damageEnemiesAmount to { c -> c.damageEnemiesAmount.toDouble() },
            A.A_CrewDefinition_powerRechargeMultiplier to { c -> c.powerRechargeMultiplier.toDouble() },
            A.A_CrewDefinition_crewSlots to { c -> c.crewSlots.toDouble() },
            A.A_CrewDefinition_essential to { c -> c.essential.toDouble() },
            A.A_CrewDefinition_lowHealthThreshold to { c -> c.lowHealthThreshold.toDouble() },
            A.A_CrewDefinition_lowHealthThresholdPercentage to { c -> c.lowHealthThresholdPercentage.toDouble() },
            A.A_CrewDefinition_repairSoundFrame to { c -> c.repairSoundFrame.toDouble() },
            A.A_CrewDefinition_canFight to { c -> d(c.canFight) },
            A.A_CrewDefinition_canRepair to { c -> d(c.canRepair) },
            A.A_CrewDefinition_canSabotage to { c -> d(c.canSabotage) },
            A.A_CrewDefinition_canMan to { c -> d(c.canMan) },
            A.A_CrewDefinition_canTeleport to { c -> d(c.canTeleport) },
            A.A_CrewDefinition_canSuffocate to { c -> d(c.canSuffocate) },
            A.A_CrewDefinition_controllable to { c -> d(c.controllable) },
            A.A_CrewDefinition_selectable to { c -> d(c.selectable) },
            A.A_CrewDefinition_canBurn to { c -> d(c.canBurn) },
            A.A_CrewDefinition_providesPower to { c -> d(c.providesPower) },
            A.A_CrewDefinition_isTelepathic to { c -> d(c.isTelepathic) },
            A.A_CrewDefinition_resistsMindControl to { c -> d(c.resistsMindControl) },
            A.A_CrewDefinition_isAnaerobic to { c -> d(c.isAnaerobic) },
            A.A_CrewDefinition_canPhaseThroughDoors to { c -> d(c.canPhaseThroughDoors) },
            A.A_CrewDefinition_detectsLifeforms to { c -> d(c.detectsLifeforms) },
            A.A_CrewDefinition_hasCustomDeathAnimation to { c -> d(c.hasCustomDeathAnimation) },
            A.A_CrewDefinition_hasDeathExplosion to { c -> d(c.hasDeathExplosion) },
            A.A_CrewDefinition_cloneLoseSkills to { c -> d(c.cloneLoseSkills) },
            A.A_CrewDefinition_powerDrainFriendly to { c -> d(c.powerDrainFriendly) },
            A.A_CrewDefinition_hackDoors to { c -> d(c.hackDoors) },
            A.A_CrewDefinition_noSlot to { c -> d(c.noSlot) },
            A.A_CrewDefinition_noClone to { c -> d(c.noClone) },
            A.A_CrewDefinition_noAI to { c -> d(c.noAI) },
            A.A_CrewDefinition_validTarget to { c -> d(c.validTarget) },
            A.A_CrewDefinition_canMove to { c -> d(c.canMove) },
            A.A_CrewDefinition_snapToSlot to { c -> d(c.snapToSlot) },
            A.A_CrewDefinition_teleportMove to { c -> d(c.teleportMove) },
            A.A_CrewDefinition_teleportMoveOtherShip to { c -> d(c.teleportMoveOtherShip) },
            A.A_CrewDefinition_silenced to { c -> d(c.silenced) },
            A.A_CrewDefinition_noWarning to { c -> d(c.noWarning) },
            A.A_CrewDefinition_changeIfSame to { c -> d(c.changeIfSame) },
            A.A_CrewDefinition_droneMoveFromManningSlot to { c -> d(c.droneMoveFromManningSlot) },
        )
        for ((a, f) in nums) LuaReg.num(a, NumGet { f(cd(it)) })
        LuaReg.obj(A.A_CrewDefinition_powerDefs, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewDefinition_powerDefs) { x ->
                ListView(A.attrType(A.A_CrewDefinition_powerDefs), A.T_ActivatedPowerDefinition) { (x as CrewDefinition).powerDefs }
            }
        })
        LuaReg.obj(A.A_CrewDefinition_passiveStatBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewDefinition_passiveStatBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) { (x as CrewDefinition).passiveStatBoosts }
            }
        })
    }

    /** Kotlin StatBoostDefinition fields (definitions from XML), struct-backed ones (Lua `new`) fall through. */
    private fun sbNum(a: Int, f: (StatBoostDefinition) -> Double) {
        LuaReg.num(a, NumGet { o -> if (o is LuaStruct) structNum(o, a) else f(o as StatBoostDefinition) },
            NumSet { o, v -> if (o is LuaStruct) o.values[a] = v else throw LuaError("This variable is immutable") })
    }

    /** Lua-created definitions become engine definitions on first use (cached by identity). */
    private val converted = java.util.IdentityHashMap<LuaStruct, StatBoostDefinition>()

    @JvmStatic
    fun definitionOf(o: Any?): StatBoostDefinition? = when (o) {
        is StatBoostDefinition -> o
        is LuaStruct -> converted.getOrPut(o) { fromStruct(o) }
        else -> null
    }

    private fun fromStruct(s: LuaStruct): StatBoostDefinition {
        fun n(a: Int): Double = structNum(s, a)
        fun b(a: Int): Boolean = n(a) != 0.0
        val d = StatBoostDefinition()
        d.stat = CrewStat.entries.getOrElse(n(A.A_StatBoostDefinition_stat).toInt()) { CrewStat.MAX_HEALTH }
        d.amount = n(A.A_StatBoostDefinition_amount).toFloat()
        d.value = b(A.A_StatBoostDefinition_value)
        d.stringValue = s.values[A.A_StatBoostDefinition_stringValue] as? String ?: ""
        d.isBool = b(A.A_StatBoostDefinition_isBool)
        d.priority = n(A.A_StatBoostDefinition_priority).toInt()
        d.duration = n(A.A_StatBoostDefinition_duration).toFloat()
        d.jumpClear = b(A.A_StatBoostDefinition_jumpClear)
        d.cloneClear = b(A.A_StatBoostDefinition_cloneClear)
        d.boostAnim = s.values[A.A_StatBoostDefinition_boostAnim] as? String ?: ""
        d.affectsSelf = b(A.A_StatBoostDefinition_affectsSelf)
        d.functionalTarget = b(A.A_StatBoostDefinition_functionalTarget)
        d.dangerRating = n(A.A_StatBoostDefinition_dangerRating).toFloat()
        d.realBoostId = n(A.A_StatBoostDefinition_realBoostId).toInt()
        d.stackId = n(A.A_StatBoostDefinition_stackId).toInt()
        d.maxStacks = n(A.A_StatBoostDefinition_maxStacks).toInt()
        d.isRoomBased = b(A.A_StatBoostDefinition_isRoomBased)
        d.boostSource = StatBoostDefinition.BoostSource.entries[n(A.A_StatBoostDefinition_boostSource).toInt()]
        d.boostType = StatBoostDefinition.BoostType.entries[n(A.A_StatBoostDefinition_boostType).toInt()]
        d.shipTarget = StatBoostDefinition.ShipTarget.entries[n(A.A_StatBoostDefinition_shipTarget).toInt()]
        d.crewTarget = StatBoostDefinition.CrewTarget.entries[n(A.A_StatBoostDefinition_crewTarget).toInt()]
        d.droneTarget = StatBoostDefinition.DroneTarget.entries[n(A.A_StatBoostDefinition_droneTarget).toInt()]
        d.systemRoomTarget = StatBoostDefinition.SystemRoomTarget.entries[n(A.A_StatBoostDefinition_systemRoomTarget).toInt()]
        d.powerScalingNoSys = n(A.A_StatBoostDefinition_powerScalingNoSys).toFloat()
        d.powerScalingHackedSys = n(A.A_StatBoostDefinition_powerScalingHackedSys).toFloat()
        (s.values[A.A_StatBoostDefinition_healthReq] as? LuaStruct)?.let { LuaStructs.fields(it).let { f -> d.healthReq[0] = f[0].toFloat(); d.healthReq[1] = f[1].toFloat() } }
        (s.values[A.A_StatBoostDefinition_healthFractionReq] as? LuaStruct)?.let { LuaStructs.fields(it).let { f -> d.healthFractionReq[0] = f[0].toFloat(); d.healthFractionReq[1] = f[1].toFloat() } }
        (s.values[A.A_StatBoostDefinition_oxygenReq] as? LuaStruct)?.let { LuaStructs.fields(it).let { f -> d.oxygenReq[0] = f[0].toFloat(); d.oxygenReq[1] = f[1].toFloat() } }
        (s.values[A.A_StatBoostDefinition_fireCount] as? LuaStruct)?.let { LuaStructs.fields(it).let { f -> d.fireCount[0] = f[0].toInt(); d.fireCount[1] = f[1].toInt() } }
        for ((attr, list) in listOf(A.A_StatBoostDefinition_whiteList to d.whiteList, A.A_StatBoostDefinition_blackList to d.blackList,
            A.A_StatBoostDefinition_systemList to d.systemList)) {
            val v = s.values[attr] as? ListView ?: continue
            for (i in 0 until v.size()) (v.get(i) as? String)?.let { list.add(it) }
        }
        (s.values[A.A_StatBoostDefinition_providedStatBoosts] as? ListView)?.let { v ->
            for (i in 0 until v.size()) definitionOf(v.get(i))?.let { d.providedStatBoosts.add(it) }
        }
        (s.values[A.A_StatBoostDefinition_systemRoomReqs] as? ListView)?.let { v ->
            for (i in 0 until v.size()) (v.get(i) as? String)?.let { d.systemRoomReqs.add(it) }
        }
        (s.values[A.A_StatBoostDefinition_powerScaling] as? ListView)?.let { v ->
            for (i in 0 until v.size()) (v.get(i) as? Number)?.let { d.powerScaling.add(it.toFloat()) }
        }
        if (d.realBoostId >= 0) StatBoostDefs.luaDefs[d.realBoostId] = d
        return d
    }

    /**
     * CrewMember fields and methods MV's scripts use that the core adapters lack (hyperspace.i CrewMember,
     * ShipManager::AddCrewMemberFromString, Ship::vDoorList and Door).
     */
    private fun crewMembers() {
        LuaReg.str(A.A_CrewMember_species, StrGet { (it as AbstractCrew).codename })
        LuaReg.num(A.A_CrewMember_currentShipId, NumGet { HsCrew.currentShipId(it as AbstractCrew).toDouble() })
        LuaReg.bool(A.A_CrewMember_intruder, { HsCrew.ex(it as AbstractCrew).intruder })
        // CrewMember::bDead: still false while the death animation plays (oracle, statcrew-powers teleport:3); the
        // crew member leaves the ship's list once it is set, so only a stale reference can read it true here.
        LuaReg.bool(A.A_CrewMember_bDead, { c -> (c as AbstractCrew).let { it.health <= 0f && it !in it.room.ship.crew } })
        // CrewMember::bSuffocating: the crew loop's suffocation (room at or below 5% oxygen, a race that suffocates)
        LuaReg.bool(A.A_CrewMember_bSuffocating, { c -> (c as AbstractCrew).let {
            it.health > 0f && it.room.oxygen <= xyz.znix.xftl.systems.Oxygen.OXYGEN_CRITICAL_LEVEL && it.suffocationMultiplier > 0f } })
        // CrewMember::type: the crew blueprint name
        LuaReg.str(A.A_CrewMember_type, StrGet { (it as AbstractCrew).codename })
        LuaReg.num(A.A_CrewMember_fStunTime, NumGet { (it as AbstractCrew).stunTime.toDouble() },
            NumSet { o, v -> (o as AbstractCrew).stunTime = v.toFloat() })
        LuaReg.num(A.A_CrewMember_fMedbay, NumGet { HsCrew.medbayRate(it as AbstractCrew).toDouble() })
        // CrewMember::DirectModifyHealth(float) -> bool (true when the crew member died of it)
        LuaReg.method(A.M_CrewMember_DirectModifyHealth, LuaMethod {
            val c = arg.obj(1) as AbstractCrew
            val amount = arg.float(2)
            val died = if (c is HsCrewMember) c.directModifyHealth(amount) else {
                val before = c.health
                c.health = (c.health + amount).coerceIn(0f, c.maxHealth)
                before > 0f && c.health <= 0f
            }
            ret.bool(died)
        })
        // CrewMember::Kill(noClone): health to 0 (the death runs in the crew loop); noClone keeps the clone bay away
        LuaReg.method(A.M_CrewMember_Kill, LuaMethod {
            HsCrew.luaKill(arg.obj(1) as AbstractCrew, arg.bool(2))
            0
        })
        LuaReg.method(A.M_ShipManager_AddCrewMemberFromString, LuaMethod {
            val ship = arg.obj(1) as xyz.znix.xftl.Ship
            ret.obj(HsCrew.addCrewMemberFromString(ship, arg.str(2) ?: "", arg.str(3) ?: "human", arg.bool(4), arg.int(5),
                arg.bool(6), arg.bool(7)), A.T_CrewMember)
        })
        // Ship::GetHullBreaches(onlyDamaged): the breaches of the ship's rooms (as Repairables; only the size and
        // identity are meaningful here)
        LuaReg.method(A.M_Ship_GetHullBreaches, LuaMethod {
            val s = (arg.obj(1) as LuaShipGeom).ship
            ret.obj(ListView(A.T_vector_Repairable, A.T_Repairable) { s.rooms.flatMap { r -> r.breaches.filterNotNull() } },
                A.T_vector_Repairable)
        })
        LuaReg.obj(A.A_Ship_vDoorList, ObjGet { o ->
            LuaViews.cached((o as LuaShipGeom).ship, A.A_Ship_vDoorList) { s ->
                // vDoorList holds the inner doors; the airlocks are Ship::vOuterAirlocks
                ListView(A.T_vector_Door, A.T_Door) { (s as xyz.znix.xftl.Ship).doors.filter { !it.isAirlock } }
            }
        })
        LuaReg.num(A.A_Door_iRoom1, NumGet { ((it as xyz.znix.xftl.layout.Door).left?.id ?: -1).toDouble() })
        LuaReg.num(A.A_Door_iRoom2, NumGet { ((it as xyz.znix.xftl.layout.Door).right?.id ?: -1).toDouble() })
        // Door::lockedDown (an AnimationTracker in the native struct): a read-only snapshot of the lockdown timer
        LuaReg.obj(A.A_Door_lockedDown, ObjGet { o ->
            val door = o as xyz.znix.xftl.layout.Door
            val t = LuaViews.cached(door, A.A_Door_lockedDown) { LuaStruct(A.T_AnimationTracker) }
            val left = door.lockdownTimer
            // the native tracker does not advance with the lockdown (oracle: time - current_time stays 12)
            t.values[A.A_AnimationTracker_time] = LOCKDOWN_TIME
            t.values[A.A_AnimationTracker_current_time] = 0.0
            t.values[A.A_AnimationTracker_running] = left > 0f
            t.values[A.A_AnimationTracker_done] = false
            t
        })
    }

    /** Door::lockdown's 12 s (layout/Door.kt). */
    private const val LOCKDOWN_TIME = 12.0

    private fun statBoosts() {
        sbNum(A.A_StatBoostDefinition_stat) { it.stat.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_amount) { it.amount.toDouble() }
        sbNum(A.A_StatBoostDefinition_value) { d(it.value) }
        sbNum(A.A_StatBoostDefinition_isBool) { d(it.isBool) }
        sbNum(A.A_StatBoostDefinition_priority) { it.priority.toDouble() }
        sbNum(A.A_StatBoostDefinition_duration) { it.duration.toDouble() }
        sbNum(A.A_StatBoostDefinition_jumpClear) { d(it.jumpClear) }
        sbNum(A.A_StatBoostDefinition_cloneClear) { d(it.cloneClear) }
        sbNum(A.A_StatBoostDefinition_affectsSelf) { d(it.affectsSelf) }
        sbNum(A.A_StatBoostDefinition_functionalTarget) { d(it.functionalTarget) }
        sbNum(A.A_StatBoostDefinition_dangerRating) { it.dangerRating.toDouble() }
        sbNum(A.A_StatBoostDefinition_realBoostId) { it.realBoostId.toDouble() }
        sbNum(A.A_StatBoostDefinition_stackId) { it.stackId.toDouble() }
        sbNum(A.A_StatBoostDefinition_maxStacks) { it.maxStacks.toDouble() }
        sbNum(A.A_StatBoostDefinition_isRoomBased) { d(it.isRoomBased) }
        sbNum(A.A_StatBoostDefinition_boostSource) { it.boostSource.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_boostType) { it.boostType.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_shipTarget) { it.shipTarget.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_crewTarget) { it.crewTarget.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_droneTarget) { it.droneTarget.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_systemRoomTarget) { it.systemRoomTarget.ordinal.toDouble() }
        sbNum(A.A_StatBoostDefinition_powerScalingNoSys) { it.powerScalingNoSys.toDouble() }
        sbNum(A.A_StatBoostDefinition_powerScalingHackedSys) { it.powerScalingHackedSys.toDouble() }
        LuaReg.str(A.A_StatBoostDefinition_stringValue, StrGet { o -> if (o is LuaStruct) o.values[A.A_StatBoostDefinition_stringValue] as? String ?: "" else (o as StatBoostDefinition).stringValue },
            StrSet { o, v -> if (o is LuaStruct) o.values[A.A_StatBoostDefinition_stringValue] = v else throw LuaError("This variable is immutable") })
        LuaReg.str(A.A_StatBoostDefinition_boostAnim, StrGet { o -> if (o is LuaStruct) o.values[A.A_StatBoostDefinition_boostAnim] as? String ?: "" else (o as StatBoostDefinition).boostAnim },
            StrSet { o, v -> if (o is LuaStruct) o.values[A.A_StatBoostDefinition_boostAnim] = v else throw LuaError("This variable is immutable") })
        // StatBoostDefinition::providedStatBoosts (StatBoost.h): the boosts a STAT_BOOST boost hands out; MV's
        // instant-clone-heal.lua recurses into it. Struct-backed definitions (Lua `new`) take the generic member.
        LuaReg.obj(A.A_StatBoostDefinition_providedStatBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_StatBoostDefinition_providedStatBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) { (x as StatBoostDefinition).providedStatBoosts }
            }
        })
        // The string lists of a definition from XML (whiteList, blackList, systemRoomReqs, systemList).
        for (attr in intArrayOf(A.A_StatBoostDefinition_whiteList, A.A_StatBoostDefinition_blackList,
            A.A_StatBoostDefinition_systemRoomReqs, A.A_StatBoostDefinition_systemList)) {
            LuaReg.obj(attr, ObjGet { o ->
                LuaViews.cached(o!!, attr) { x ->
                    ListView(A.attrType(attr), 0) {
                        val d = x as StatBoostDefinition
                        when (attr) {
                            A.A_StatBoostDefinition_whiteList -> d.whiteList
                            A.A_StatBoostDefinition_blackList -> d.blackList
                            A.A_StatBoostDefinition_systemRoomReqs -> d.systemRoomReqs
                            else -> d.systemList
                        }
                    }
                }
            })
        }

        // StatBoost(def): the boost of a definition (both constructors take the definition).
        val newBoost = LuaMethod {
            val def = definitionOf(arg.obj(1)) ?: throw LuaError("StatBoost: not a StatBoostDefinition")
            ret.obj(StatBoost(def), A.T_StatBoost)
        }
        LuaReg.method(A.M_new_StatBoost__SWIG_0, newBoost)
        LuaReg.method(A.M_new_StatBoost__SWIG_1, newBoost)
        LuaReg.method(A.M_StatBoostManager_GetInstance, LuaMethod { ret.obj(LuaStatBoostManager, A.T_StatBoostManager) })
        LuaReg.method(A.M_StatBoostManager_CreateTimedAugmentBoost, LuaMethod {
            val b = arg.obj(2) as StatBoost
            val crew = arg.obj(3) as AbstractCrew
            StatBoostManager.createTimedAugmentBoost(b, crew)
            0
        })
    }
}
