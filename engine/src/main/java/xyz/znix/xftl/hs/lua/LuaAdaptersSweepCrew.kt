// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i: CrewMember, CrewAnimation,
// BoardingGoal, SCrewStats, CrewDesc, CrewDefinition, CrewMember_Extend, CrewBlueprint, ActivatedPower*,
// PowerResourceDefinition, TemporaryPowerDefinition, StatBoostDefinition, CustomDamageDefinition), the member layouts from
// libzhlgen/test/functions/win32/FTLGameStripped.h and the rules of src/game/Ship/CustomCrew.cpp.
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.crew.ActivatedPower
import xyz.znix.xftl.hs.crew.ActivatedPowerDefinition
import xyz.znix.xftl.hs.crew.ActivatedPowerResource
import xyz.znix.xftl.hs.crew.CrewDefinition
import xyz.znix.xftl.hs.crew.CrewExtend
import xyz.znix.xftl.hs.crew.CrewSpawn
import xyz.znix.xftl.hs.crew.HsColour
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.HsDamage
import xyz.znix.xftl.hs.crew.directModifyHealth
import xyz.znix.xftl.hs.crew.PowerResourceDefinition
import xyz.znix.xftl.hs.crew.TemporaryPowerDefinition
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.StatBoostDefinition
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * Round 14 sweep, slice A: every remaining member of CrewMember, CrewAnimation, BoardingGoal, SCrewStats, CrewDesc,
 * CrewDefinition, CrewMember_Extend, CrewBlueprint, ActivatedPower(Definition/Resource), PowerResourceDefinition,
 * TemporaryPowerDefinition, StatBoostDefinition and CustomDamageDefinition. Members the engine models read and write the
 * engine; members that are native render caches, pointers or counters with no engine counterpart (the CachedImage of the
 * health bar, the Path of a walking crew member, ...) are per-object storage that starts with the value the original
 * game shows at the same moment (oracle probe `SweepCrew`), so a script can read, write and branch on them.
 */
object LuaAdaptersSweepCrew {
    private val store = xyz.znix.xftl.hs.lua.HsRunMaps.any(java.util.IdentityHashMap<Any, HashMap<Int, Any?>>())
    private fun st(o: Any?): HashMap<Int, Any?> = store.getOrPut(o ?: throw LuaError("nil object")) { HashMap() }

    private val typeIds = HashMap<String, Int>()
    private fun tid(name: String): Int = typeIds.getOrPut(name) {
        var i = 1
        while (true) {
            val n = A.typeName(i)
            if (n == name) return@getOrPut i
            if (n.startsWith("#")) throw LuaError("unknown Hyperspace type $name")
            i++
        }
        @Suppress("UNREACHABLE_CODE") 0
    }

    /** A native pointer the engine has no object behind (render caches, paths, tasks): typed, memberless. */
    private fun opaque(o: Any?, a: Int, type: String): LuaOpaqueMember = st(o).getOrPut(a) { LuaOpaqueMember(o!!, tid(type)) } as LuaOpaqueMember

    private fun c(o: Any?): AbstractCrew = o as AbstractCrew
    private fun d(b: Boolean) = if (b) 1.0 else 0.0

    // ---- storage-backed members ----
    private fun shNum(a: Int, def: Double, writable: Boolean = true) =
        LuaReg.num(a, NumGet { (st(it)[a] as? Double) ?: def }, if (writable) NumSet { o, v -> st(o)[a] = v } else null)

    private fun shBool(a: Int, def: Boolean) =
        LuaReg.bool(a, { (st(it)[a] as? Boolean) ?: def }, { o, v -> st(o)[a] = v })

    private fun shStr(a: Int, def: String) =
        LuaReg.str(a, StrGet { (st(it)[a] as? String) ?: def }, StrSet { o, v -> st(o)[a] = v })

    /** A pointer member (nil until a script assigns one). */
    private fun shRef(a: Int) = LuaReg.obj(a, ObjGet { st(it)[a] }, ObjSet { o, v -> st(o)[a] = v })

    private fun shOpaque(a: Int, type: String) = LuaReg.obj(a, ObjGet { opaque(it, a, type) })

    /** An owned member struct created on first use (Animation, AnimationTracker, Slot, TimerHelper ...). */
    private fun shStruct(a: Int, type: Int, init: (LuaStruct) -> Unit = {}) {
        LuaStructs.register(type)
        LuaReg.obj(a, ObjGet { o -> st(o).getOrPut(a) { LuaStructs.create(type).also(init) } },
            ObjSet { o, v -> st(o)[a] = if (v is LuaStruct) LuaStructs.copy(v) else v })
    }

    private fun shPointf(a: Int, x: Float, y: Float) = LuaReg.obj(a, ObjGet { o -> st(o).getOrPut(a) { PointfValue(x, y) } },
        ObjSet { o, v -> val p = v as? PointfValue; if (p != null) st(o)[a] = PointfValue(p.x, p.y) })

    private fun shList(a: Int, vecType: Int, elemType: Int, make: () -> MutableList<Any?>) =
        LuaReg.obj(a, ObjGet { o -> st(o).getOrPut(a) { ListView(vecType, elemType, make()) } })

    private fun slot(room: Int, slot: Int, x: Int, y: Int): LuaStruct {
        val s = LuaStructs.create(A.T_Slot)
        s.values[A.A_Slot_roomId] = room.toDouble()
        s.values[A.A_Slot_slotId] = slot.toDouble()
        (LuaStructs.member(s, A.A_Slot_worldLocation) as? LuaStruct)?.let { LuaStructs.setFields(it, x.toDouble(), y.toDouble()) }
        return s
    }

    @JvmStatic
    fun install() {
        LuaStructs.register(A.T_Slot)
        crewMember()
        crewAnimation()
        small()
        extend()
        powers()
        definitions()
        statBoosts()
        setters()
        for (a in intArrayOf(A.A_CrewMember_goal_x, A.A_CrewMember_goal_y, A.A_CrewMember_bFighting, A.A_CrewMember_fMedbay,
            A.A_CrewMember_iDeathNumber, A.A_CrewMember_clone_ready)) ovNum(a)
        setNum(A.A_CrewMember_Extend_hasSpecialPower) { o, v -> (o as CrewExtend).hasSpecialPower = v != 0.0 }
        blueprintLists()
        statBoostMembers()
        nativeMethods()
        round2()
    }

    // ================================================================ CrewMember
    private fun dyingOrDead(x: AbstractCrew) = x.health <= 0f

    private fun skillOfSystem(id: Int): Int = when (id) {
        1 -> 2 // shields
        2 -> 1 // engines (HS: GetSkillFromSystem is the native table SystemId -> skill)
        3 -> 3 // weapons
        6 -> 0 // piloting
        else -> -1
    }

    private fun crewMember() {
        shNum(A.A_CrewMember_size, 4.0); shNum(A.A_CrewMember_scale, 1.0); shNum(A.A_CrewMember_width, 30.0)
        shNum(A.A_CrewMember_height, 0.0); shNum(A.A_CrewMember_speed_x, 0.0); shNum(A.A_CrewMember_speed_y, 0.0)
        shNum(A.A_CrewMember_x_destination, 0.0); shNum(A.A_CrewMember_y_destination, 0.0)
        shNum(A.A_CrewMember_moveGoal, 0.0); shNum(A.A_CrewMember_selectionState, 0.0); shNum(A.A_CrewMember_iStackId, 0.0)
        shNum(A.A_CrewMember_lastDamageTimer, 0.0); shNum(A.A_CrewMember_lastHealthChange, 0.0)
        shNum(A.A_CrewMember_lastLevelUp, 0.0); shNum(A.A_CrewMember_iOnFire, 0.0); shNum(A.A_CrewMember_usingSkill, 0.0)
        shBool(A.A_CrewMember_new_path, false); shBool(A.A_CrewMember_bSharedSpot, false); shBool(A.A_CrewMember_bCloned, false)
        shBool(A.A_CrewMember_bFrozen, false); shBool(A.A_CrewMember_bFrozenLocation, false)
        shRef(A.A_CrewMember_last_door); shRef(A.A_CrewMember_currentRepair); shRef(A.A_CrewMember_crewTarget)
        shRef(A.A_CrewMember_blockingDoor)
        shOpaque(A.A_CrewMember_path, "Path *"); shOpaque(A.A_CrewMember_task, "CrewTask *")
        shOpaque(A.A_CrewMember_healthBox, "CachedImage *"); shOpaque(A.A_CrewMember_healthBoxRed, "CachedImage *")
        shOpaque(A.A_CrewMember_healthBar, "CachedRect *"); shOpaque(A.A_CrewMember_movementTarget, "CachedImage *")
        LuaReg.obj(A.A_CrewMember_selectionImage, ObjGet { o -> st(o).getOrPut(A.A_CrewMember_selectionImage) { LuaTexture(null, "") } },
            ObjSet { o, v -> st(o)[A.A_CrewMember_selectionImage] = v })
        shStruct(A.A_CrewMember_flashHealthTracker, A.T_AnimationTracker)
        shStruct(A.A_CrewMember_levelUp, A.T_AnimationTracker)
        shStruct(A.A_CrewMember_healing, A.T_Animation); shStruct(A.A_CrewMember_stunned, A.T_Animation)
        shStruct(A.A_CrewMember_mindControlled, A.T_Animation); shStruct(A.A_CrewMember_stunIcon, A.T_Animation)
        shPointf(A.A_CrewMember_currentTarget, 0f, 0f)
        // skillsEarned: one flag list per skill; skillUp: the level-up animations (two skill-up icons, none running)
        shList(A.A_CrewMember_skillsEarned, A.T_vector_vector_bool, A.T_vector_bool) {
            MutableList(6) { ListView(A.T_vector_bool, 0, mutableListOf<Any?>(false, false)) }
        }
        shList(A.A_CrewMember_skillUp, A.T_vector_vector_AnimationTracker, A.T_vector_AnimationTracker) {
            MutableList(2) { ListView(A.T_vector_AnimationTracker, A.T_AnimationTracker, mutableListOf<Any?>()) }
        }
        // healthBoost / fMindDamageBoost are the mind-control boosts (CrewMember::SetHealthBoost / SetDamageBoost)
        LuaReg.num(A.A_CrewMember_healthBoost, NumGet { ((it as? LivingCrew)?.mindControlHealthBoost ?: 0f).toDouble() },
            NumSet { o, v -> (o as? LivingCrew)?.restoreMindControlHealthBoost(v.toFloat()) })
        LuaReg.num(A.A_CrewMember_fMindDamageBoost, NumGet { ((it as? LivingCrew)?.mindControlDamageBoost ?: 1f).toDouble() },
            NumSet { o, v -> (o as? LivingCrew)?.let { x -> x.setMindControlBoost(x.mindControlHealthBoost, v.toFloat()) } })
        LuaReg.obj(A.A_CrewMember_finalGoal, ObjGet { o ->
            val p = c(o).pathingTarget
            if (p == null) slot(-1, -1, -1, -1) else slot(p.room.id, p.room.pointToSlot(p), p.offsetX + 17, p.offsetY + 17)
        })
        LuaReg.obj(A.A_CrewMember_savedPosition, ObjGet { o ->
            val x = c(o); val p = x.getReportedStation()?.let { x.room.ship.shipToRoomPos(it) }
            if (p == null) slot(-1, -1, x.hsCentreX.toInt(), x.hsCentreY.toInt()) else slot(p.room.id, p.room.pointToSlot(p), p.offsetX + 17, p.offsetY + 17)
        })
        LuaReg.obj(A.A_CrewMember_crewAnim, ObjGet { o -> crewAnimOf(c(o)) })
        LuaReg.obj(A.A_CrewMember_boardingGoal, ObjGet { o ->
            st(o).getOrPut(A.A_CrewMember_boardingGoal) {
                LuaStructs.register(A.T_BoardingGoal)
                LuaStructs.create(A.T_BoardingGoal).also {
                    it.values[A.A_BoardingGoal_fHealthLimit] = 0.25; it.values[A.A_BoardingGoal_causedDamage] = 0.0
                    it.values[A.A_BoardingGoal_targetsDestroyed] = 0.0; it.values[A.A_BoardingGoal_target] = 1.0
                    it.values[A.A_BoardingGoal_damageType] = 0.0
                }
            }
        })
        LuaReg.obj(A.A_CrewMember_stats, ObjGet { o ->
            val x = c(o)
            val s = st(o).getOrPut(A.A_CrewMember_stats) {
                LuaStructs.register(A.T_SCrewStats)
                LuaStructs.create(A.T_SCrewStats).also { s ->
                    @Suppress("UNCHECKED_CAST") (LuaStructs.member(s, A.A_SCrewStats_stat) as ListView).let { l -> repeat(5) { l.add(0.0) } }
                }
            } as LuaStruct
            s.values[A.A_SCrewStats_species] = x.hsSpecies
            s.values[A.A_SCrewStats_name] = (x as? LivingCrew)?.info?.name ?: ""
            s.values[A.A_SCrewStats_male] = (x as? LivingCrew)?.info?.isFemale != true
            s
        })

        val m = LuaReg::method
        m(A.M_CrewMember_PositionShift, LuaMethod { ret.num(0f) })
        // ApplyDamage / ShipDamage: the original leaves a full-health crew member at its health (probe row sweep-crew cs);
        // Hyperspace multiplies the damage by DAMAGE_TAKEN_MULTIPLIER first, so only the multiplied amount reaches the body.
        m(A.M_CrewMember_ApplyDamage, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_ShipDamage, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_GetPriority, LuaMethod { ret.num(100) })
        m(A.M_CrewMember_ValidTarget, LuaMethod {
            val x = c(arg.obj(1)); val ship = arg.int(2)
            ret.bool(!dyingOrDead(x) && HsCrew.currentShipId(x) == ship && xyz.znix.xftl.hs.crew.HsCrewEffects.validTarget(x))
        })
        m(A.M_CrewMember_MultiShots, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_ExactTarget, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_IsCloned, LuaMethod { ret.bool(HsCrew.ex(c(arg.obj(1))).crew.let { false }) })
        m(A.M_CrewMember_Jump, LuaMethod { 0 })
        m(A.M_CrewMember_SaveState, LuaMethod { 0 })
        m(A.M_CrewMember_LoadState, LuaMethod { 0 })
        m(A.M_CrewMember_OnLoop, LuaMethod { 0 })
        m(A.M_CrewMember_OnRender, LuaMethod { 0 })
        m(A.M_CrewMember_OnRenderHealth, LuaMethod { 0 })
        m(A.M_CrewMember_OnRenderPath, LuaMethod { 0 })
        m(A.M_CrewMember_RenderSkillUpAnimation, LuaMethod { 0 })
        m(A.M_CrewMember_CycleColorLayer, LuaMethod { 0 })
        m(A.M_CrewMember_OnInit, LuaMethod { 0 })
        m(A.M_CrewMember_Cleanup, LuaMethod { 0 })
        m(A.M_CrewMember_InitializeSkills, LuaMethod { 0 })
        m(A.M_CrewMember_SetOutOfGame, LuaMethod { 0 })
        m(A.M_CrewMember_CountForVictory, LuaMethod { val x = c(arg.obj(1)); ret.bool(!dyingOrDead(x) || x.room.ship.clonebay?.queue?.contains(x) == true) })
        m(A.M_CrewMember_ReadyToFight, LuaMethod {
            val x = c(arg.obj(1)); ret.bool(!dyingOrDead(x) && x.stunTime <= 0f && x.currentAction != AbstractCrew.Action.TELEPORTING)
        })
        m(A.M_CrewMember_CanHeal, LuaMethod { val x = c(arg.obj(1)); ret.bool(x.health < x.maxHealth && x.health > 0f && x.stunTime <= 0f && false) })
        m(A.M_CrewMember_PermanentDeath, LuaMethod { ret.bool(true) })
        m(A.M_CrewMember_FireFightingSoundEffect, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_GetUniqueRepairing, LuaMethod { ret.str("repair") })
        m(A.M_CrewMember_ActivatePower, LuaMethod { HsCrew.ex(c(arg.obj(1))).firstCrewPower()?.activatePower(); 0 })
        m(A.M_CrewMember_ResetPower, LuaMethod { HsCrew.ex(c(arg.obj(1))).crewPowers.forEach { it.cancelPower(false) }; 0 })
        m(A.M_CrewMember_BlockRoom, LuaMethod { ret.num(-1) })
        m(A.M_CrewMember_GetRoomDamage, LuaMethod {
            val x = c(arg.obj(1))
            ret.obj(HsDamageView({ HsDamage(iSystemDamage = 0, iPersDamage = 0) }), A.T_Damage)
        })
        m(A.M_CrewMember_UpdateRepair, LuaMethod { 0 })
        m(A.M_CrewMember_CanStim, LuaMethod { ret.bool(!dyingOrDead(c(arg.obj(1)))) })
        m(A.M_CrewMember_BadAtCombat, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_CheckFighting, LuaMethod { 0 })
        m(A.M_CrewMember_CheckForTeleport, LuaMethod { 0 })
        m(A.M_CrewMember_CheckRoomPath, LuaMethod {
            val x = c(arg.obj(1)); val r = x.room.ship.rooms.getOrNull(arg.int(2))
            ret.bool(r != null && (r === x.room || x.room.ship.rooms.contains(r)))
        })
        m(A.M_CrewMember_CheckSkills, LuaMethod { 0 })
        m(A.M_CrewMember_ClearPath, LuaMethod { st(arg.obj(1))[A.A_CrewMember_new_path] = true; 0 })
        m(A.M_CrewMember_ClearTask, LuaMethod { 0 })
        m(A.M_CrewMember_Clone, LuaMethod { 0 })
        m(A.M_CrewMember_CloseDoorBehind, LuaMethod { 0 })
        m(A.M_CrewMember_EmptySlot, LuaMethod { 0 })
        m(A.M_CrewMember_FindSlot, LuaMethod {
            val x = c(arg.obj(1)); val room = arg.int(2)
            val r = x.room.ship.rooms.getOrNull(room)
            val p = r?.firstFreeSlot(x.mode)?.let { xyz.znix.xftl.math.RoomPoint(r, it) }
            ret.obj(if (p == null) slot(-1, -1, -1, -1) else slot(p.room.id, p.room.pointToSlot(p), p.offsetX + 17, p.offsetY + 17), A.T_Slot)
        })
        m(A.M_CrewMember_ForceMindControl, LuaMethod {
            (arg.obj(1) as? LivingCrew)?.let { xyz.znix.xftl.systems.MindControl.setMindControl(it, arg.bool(2)) }; 0
        })
        m(A.M_CrewMember_GetMoveSpeed, LuaMethod { ret.num(c(arg.obj(1)).movementSpeed / 58.068f) })
        m(A.M_CrewMember_GetNewGoal, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_GetNextGoal, LuaMethod {
            val x = c(arg.obj(1)); val p = x.nextTargetPos
            ret.obj(PointValue(if (p != null) p.x + 17 else x.hsCentreX.toInt(), if (p != null) p.y + 17 else x.hsCentreY.toInt()), A.T_Point)
        })
        m(A.M_CrewMember_GetRepairingId, LuaMethod {
            val x = c(arg.obj(1))
            ret.num(if (x.currentAction == AbstractCrew.Action.REPAIRING) xyz.znix.xftl.ai.nativeSystemId(x.room.system) else -1)
        })
        m(A.M_CrewMember_GetResisted, LuaMethod { ret.bool(st(arg.obj(1))[A.A_CrewMember_bResisted] as? Boolean ?: false) })
        m(A.M_CrewMember_GetSkillModifier, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = arg.int(2)
            val sk = xyz.znix.xftl.crew.Skill.entries.getOrNull(s)
            ret.num(if (x != null && sk != null) skillBonus(x, sk) else 0)
        })
        m(A.M_CrewMember_IsBusy, LuaMethod { val x = c(arg.obj(1)); ret.bool(x.currentAction != AbstractCrew.Action.IDLE && x.currentAction != AbstractCrew.Action.MANNING) })
        m(A.M_CrewMember_IsManningArtillery, LuaMethod {
            val x = c(arg.obj(1)); ret.bool(x.currentAction == AbstractCrew.Action.MANNING && x.room.system is xyz.znix.xftl.systems.Artillery)
        })
        m(A.M_CrewMember_NeedFrozenLocation, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_NeedsSlot, LuaMethod { ret.bool(false) })
        m(A.M_CrewMember_Restart, LuaMethod { 0 })
        m(A.M_CrewMember_SelectSabotageTarget, LuaMethod { ret.obj(PointfValue(0f, 0f), A.T_Pointf) })
        m(A.M_CrewMember_SetCloneReady, LuaMethod { ovPut(A.A_CrewMember_clone_ready, arg.obj(1), if (arg.bool(2)) 1.0 else 0.0); 0 })
        m(A.M_CrewMember_SetCurrentShip, LuaMethod { 0 })
        m(A.M_CrewMember_SetCurrentSystem, LuaMethod { 0 })
        m(A.M_CrewMember_SetCurrentTarget, LuaMethod { 0 })
        m(A.M_CrewMember_SetDamageBoost, LuaMethod {
            (arg.obj(1) as? LivingCrew)?.let { x -> x.setMindControlBoost(x.mindControlHealthBoost, arg.float(2)) }; 0
        })
        m(A.M_CrewMember_SetHealthBoost, LuaMethod {
            (arg.obj(1) as? LivingCrew)?.let { x -> x.setMindControlBoost(arg.int(2).toFloat(), x.mindControlDamageBoost) }; 0
        })
        m(A.M_CrewMember_SetDeathNumber, LuaMethod { ovPut(A.A_CrewMember_iDeathNumber, arg.obj(1), arg.num(2)); 0 })
        m(A.M_CrewMember_SetFrozen, LuaMethod { st(arg.obj(1))[A.A_CrewMember_bFrozen] = arg.bool(2); 0 })
        m(A.M_CrewMember_SetFrozenLocation, LuaMethod { st(arg.obj(1))[A.A_CrewMember_bFrozenLocation] = arg.bool(2); 0 })
        m(A.M_CrewMember_SetMedbay, LuaMethod { 0 })
        m(A.M_CrewMember_SetPath, LuaMethod { 0 })
        m(A.M_CrewMember_SetPosition, LuaMethod {
            val x = c(arg.obj(1)); val p = arg.obj(2)
            val px = (p as? PointValue)?.x ?: return@LuaMethod 0
            val py = p.y
            val room = x.room.ship.rooms.firstOrNull { r -> px >= r.offsetX && px < r.offsetX + r.width * 35 && py >= r.offsetY && py < r.offsetY + r.height * 35 }
            if (room != null) {
                val rp = room.slotToPoint(0)
                x.jumpTo(room, xyz.znix.xftl.math.ConstPoint(px - 17, py - 17).takeIf { true } ?: rp)
            }
            0
        })
        m(A.M_CrewMember_SetResisted, LuaMethod { st(arg.obj(1))[A.A_CrewMember_bResisted] = arg.bool(2); 0 })
        m(A.M_CrewMember_SetRoom, LuaMethod {
            val x = c(arg.obj(1)); val r = x.room.ship.rooms.getOrNull(arg.int(2))
            if (r != null && r !== x.room) r.firstFreeSlot(x.mode)?.let { x.jumpTo(r, it) }
            0
        })
        m(A.M_CrewMember_SetRoomPath, LuaMethod {
            val x = c(arg.obj(1)); val r = x.room.ship.rooms.getOrNull(arg.int(3))
            if (r != null) r.firstFreeSlot(x.mode)?.let { x.setTargetRoom(r, it) }
            0
        })
        m(A.M_CrewMember_SetSavePosition, LuaMethod { val x = c(arg.obj(1)); x.savedPosition = x.currentSlotPoint.shipPoint; 0 })
        m(A.M_CrewMember_SetSex, LuaMethod { crewAnimOf(c(arg.obj(1))).values[A.A_CrewAnimation_bMale] = arg.bool(2); 0 })
        m(A.M_CrewMember_SetSkillProgress, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val sk = xyz.znix.xftl.crew.Skill.entries.getOrNull(arg.int(2))
            if (x != null && sk != null) {
                val max = sk.actionsPerLevel(x.blueprint) * 2
                if (max > 0) x.info.skills[sk] = (arg.num(3).toFloat() / max).coerceIn(0f, 1f)
            }
            0
        })
        m(A.M_CrewMember_SetTask, LuaMethod { 0 })
        m(A.M_CrewMember_StartRepair, LuaMethod { 0 })
        m(A.M_CrewMember_StartTeleport, LuaMethod { 0 })
        m(A.M_CrewMember_StartTeleportArrive, LuaMethod { 0 })
        m(A.M_CrewMember_StopRepairing, LuaMethod {
            val x = c(arg.obj(1)); if (x.currentAction == AbstractCrew.Action.REPAIRING) x.stun(0f); 0
        })
        m(A.M_CrewMember_UpdateHealth, LuaMethod { val x = c(arg.obj(1)); x.health = x.health.coerceIn(0f, x.maxHealth); 0 })
        m(A.M_CrewMember_UpdateMovement, LuaMethod { 0 })
        m(A.M_CrewMember_WithinRect, LuaMethod {
            val p = c(arg.obj(1)).getPixelPositionCentre(); val x = arg.int(2); val y = arg.int(3); val w = arg.int(4); val h = arg.int(5)
            ret.bool(p.x >= x && p.x < x + w && p.y >= y && p.y < y + h)
        })
        m(A.M_CrewMember_GetSkillFromSystem, LuaMethod { ret.num(skillOfSystem(arg.int(1))) })
        installDamageView()
    }

    /** CrewMember::GetSkillModifier(skill): the race's SkillsDefinition value for the crew member's level of [s]. */
    private fun skillBonus(x: LivingCrew, s: xyz.znix.xftl.crew.Skill): Int {
        val def = xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull(x.hsSpecies)?.skillsDef
        val levels = def?.skills?.get(xyz.znix.xftl.hs.crew.SkillsDefinition.ORDER.getOrNull(s.ordinal) ?: "")?.levels
        return levels?.getOrNull(x.getSkillLevel(s).ordinal)?.toInt() ?: 0
    }

    // ================================================================ Damage by value (GetRoomDamage, ActivatedPowerDefinition.damage)
    class HsDamageView(val dmg: () -> HsDamage) : LuaTyped { override val luaType: Int get() = A.T_Damage }

    private fun unsupported(a: Int): Nothing =
        throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))

    private fun dmgNum(a: Int, get: (HsDamage) -> Double, set: (HsDamage, Double) -> Unit) {
        val pg = LuaReg.numGet[a]; val ps = LuaReg.numSet[a]
        LuaReg.numGet[a] = NumGet { o -> if (o is HsDamageView) get(o.dmg()) else (pg ?: unsupported(a)).get(o) }
        LuaReg.numSet[a] = NumSet { o, v -> if (o is HsDamageView) set(o.dmg(), v) else (ps ?: unsupported(a)).set(o, v) }
    }

    private fun installDamageView() {
        dmgNum(A.A_Damage_iDamage, { it.iDamage.toDouble() }, { x, v -> x.iDamage = v.toInt() })
        dmgNum(A.A_Damage_iShieldPiercing, { it.iShieldPiercing.toDouble() }, { x, v -> x.iShieldPiercing = v.toInt() })
        dmgNum(A.A_Damage_fireChance, { it.fireChance.toDouble() }, { x, v -> x.fireChance = v.toInt() })
        dmgNum(A.A_Damage_breachChance, { it.breachChance.toDouble() }, { x, v -> x.breachChance = v.toInt() })
        dmgNum(A.A_Damage_stunChance, { it.stunChance.toDouble() }, { x, v -> x.stunChance = v.toInt() })
        dmgNum(A.A_Damage_iIonDamage, { it.iIonDamage.toDouble() }, { x, v -> x.iIonDamage = v.toInt() })
        dmgNum(A.A_Damage_iSystemDamage, { it.iSystemDamage.toDouble() }, { x, v -> x.iSystemDamage = v.toInt() })
        dmgNum(A.A_Damage_iPersDamage, { it.iPersDamage.toDouble() }, { x, v -> x.iPersDamage = v.toInt() })
        dmgNum(A.A_Damage_bHullBuster, { d(it.bHullBuster) }, { x, v -> x.bHullBuster = v != 0.0 })
        dmgNum(A.A_Damage_ownerId, { it.ownerId.toDouble() }, { x, v -> x.ownerId = v.toInt() })
        dmgNum(A.A_Damage_selfId, { it.selfId.toDouble() }, { x, v -> x.selfId = v.toInt() })
        dmgNum(A.A_Damage_bLockdown, { d(it.bLockdown) }, { x, v -> x.bLockdown = v != 0.0 })
        dmgNum(A.A_Damage_crystalShard, { d(it.crystalShard) }, { x, v -> x.crystalShard = v != 0.0 })
        dmgNum(A.A_Damage_bFriendlyFire, { d(it.bFriendlyFire) }, { x, v -> x.bFriendlyFire = v != 0.0 })
        dmgNum(A.A_Damage_iStun, { it.iStun.toDouble() }, { x, v -> x.iStun = v.toInt() })
    }

    // ================================================================ CrewAnimation
    /** Struct key of the action during which a script set CrewAnimation.status. */
    private const val STATUS_SET = Int.MIN_VALUE + 1

    private fun setStatus(s: LuaStruct, v: Double) {
        s.values[A.A_CrewAnimation_status] = v
        s.values[STATUS_SET] = animOwner[s]?.currentAction
    }

    /** The crew member of each CrewAnimation struct (for its live members). */
    private val animOwner = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<LuaStruct, AbstractCrew>())

    private fun crewAnimOf(x: AbstractCrew): LuaStruct {
        LuaStructs.register(A.T_CrewAnimation)
        val s = st(x).getOrPut(A.A_CrewMember_crewAnim) {
            LuaStructs.create(A.T_CrewAnimation).also { s ->
                animOwner[s] = x
                val v = s.values
                // The crew member draws from the strips a script set (CrewAnimation::OnRender).
                s.onWrite = {
                    x.hsBaseStrip = (v[A.A_CrewAnimation_baseStrip] as? LuaTexture)?.takeIf { it.path.isNotEmpty() || it.image != null }
                    x.hsColorStrip = (v[A.A_CrewAnimation_colorStrip] as? LuaTexture)?.takeIf { it.path.isNotEmpty() || it.image != null }
                }
                v[A.A_CrewAnimation_iShipId] = HsCrew.shipId(x).toDouble()
                v[A.A_CrewAnimation_anims] = ListView(A.T_vector_vector_Animation, A.T_vector_Animation,
                    MutableList<Any?>(4) { ListView(A.T_vector_Animation, A.T_Animation, mutableListOf<Any?>()) })
                v[A.A_CrewAnimation_baseStrip] = LuaTexture(null, ""); v[A.A_CrewAnimation_colorStrip] = LuaTexture(null, "")
                v[A.A_CrewAnimation_layerStrips] = ListView(A.T_vector_p_GL_Texture, A.T_GL_Texture,
                    MutableList<Any?>(3) { LuaTexture(null, "") })
                v[A.A_CrewAnimation_direction] = 1.0; v[A.A_CrewAnimation_moveDirection] = 1.0; v[A.A_CrewAnimation_status] = 0.0
                v[A.A_CrewAnimation_bTyping] = true; v[A.A_CrewAnimation_uniqueBool1] = true; v[A.A_CrewAnimation_uniqueBool2] = true
                v[A.A_CrewAnimation_forcedAnimation] = -1.0; v[A.A_CrewAnimation_forcedDirection] = -1.0
                v[A.A_CrewAnimation_target] = PointfValue(-1f, -1f)
                v[A.A_CrewAnimation_layerColors] = ListView(A.T_vector_GL_Color, A.T_GL_Color,
                    MutableList<Any?>(4) { GlColorValue(1f, 1f, 1f, 1f) })
                v[A.A_CrewAnimation_projectileColor] = GlColorValue(100f, 255f, 100f, 1f)
                v[A.A_CrewAnimation_race] = x.hsSpecies
                v[A.A_CrewAnimation_bMale] = (x as? LivingCrew)?.info?.isFemale != true
            }
        } as LuaStruct
        s.values[A.A_CrewAnimation_currentShip] = (st(x)[CUR_SHIP] as? Pair<*, *>)?.takeIf { it.first === x.room.ship }?.let { (it.second as Int).toDouble() }
            ?: HsCrew.currentShipId(x).toDouble()
        s.values[A.A_CrewAnimation_bPlayer] = (x.belongsToPlayer ?: (HsCrew.shipId(x) == 0))
        s.values[A.A_CrewAnimation_bStunned] = x.stunTime > 0f
        s.values[A.A_CrewAnimation_lastPosition] = PointfValue(x.hsCentreX, x.hsCentreY)
        return s
    }

    private fun crewAnimation() {
        val m = LuaReg::method
        for (id in intArrayOf(A.M_CrewAnimation_OnRender, A.M_CrewAnimation_OnRenderProps, A.M_CrewAnimation_OnUpdateEffects,
            A.M_CrewAnimation_UpdateFiring, A.M_CrewAnimation_UpdateShooting, A.M_CrewAnimation_FireShot, A.M_CrewAnimation_CustomDeath,
            A.M_CrewAnimation_OnInit, A.M_CrewAnimation_OnUpdate, A.M_CrewAnimation_RenderIcon, A.M_CrewAnimation_SetupStrips))
            m(id, LuaMethod { 0 })
        m(A.M_CrewAnimation_GetFiringFrame, LuaMethod { ret.num(1) })
        m(A.M_CrewAnimation_Restart, LuaMethod {
            (arg.obj(1) as? LuaStruct)?.let { setStatus(it, 0.0) }; 0
        })
        m(A.M_CrewAnimation_GetShootingSound, LuaMethod { ret.str("crewLaser" + (ftl.browser.BrowserRandom.nextInt(3) + 1)) })
        m(A.M_CrewAnimation_GetDeathSound, LuaMethod {
            val male = (arg.obj(1) as? LuaStruct)?.values?.get(A.A_CrewAnimation_bMale) as? Boolean ?: true
            ret.str((if (male) "maleDeath" else "femaleDeath") + (ftl.browser.BrowserRandom.nextInt(4) + 1))
        })
    }

    // ================================================================ BoardingGoal, SCrewStats, CrewDesc, CrewBlueprint
    private fun small() {
        LuaStructs.register(A.T_BoardingGoal); LuaStructs.register(A.T_SCrewStats); LuaStructs.register(A.T_CrewDesc)
        // CrewDesc { string type; float prop; int amount } is value-initialised: empty type, zero prop and amount
        LuaStructs.create(A.T_CrewDesc).values.let {
            it[A.A_CrewDesc_type] = ""; it[A.A_CrewDesc_prop] = 0.0; it[A.A_CrewDesc_amount] = 0.0
        }
        LuaReg.method(A.M_CrewBlueprint_GetCurrentSkillColor, LuaMethod { ret.obj(GlColorValue(1f, 1f, 1f, 1f), A.T_GL_Color) })
        LuaReg.method(A.M_CrewBlueprint_RandomSkills, LuaMethod { 0 })
        LuaReg.method(A.M_CrewBlueprint_RenderIcon, LuaMethod { 0 })
        LuaReg.method(A.M_CrewBlueprint_RenderSkill, LuaMethod { 0 })
    }

    // ================================================================ CrewMember_Extend
    class PowerMapView(val ex: CrewExtend) : LuaTyped { override val luaType: Int get() = A.T_unordered_map_unsigned_int_ActivatedPowerResource }

    private fun extend() {
        fun ex(o: Any?) = o as CrewExtend
        LuaReg.bool(A.A_CrewMember_Extend_canPhaseThroughDoors, { ex(it).canPhaseThroughDoors }, { o, v -> ex(o).canPhaseThroughDoors = v })
        LuaReg.num(A.A_CrewMember_Extend_lastRoom, NumGet { ex(it).lastRoom.toDouble() }, NumSet { o, v -> ex(o).lastRoom = v.toInt() })
        LuaReg.num(A.A_CrewMember_Extend_lastShipId, NumGet { ex(it).lastShipId.toDouble() }, NumSet { o, v -> ex(o).lastShipId = v.toInt() })
        LuaReg.obj(A.A_CrewMember_Extend_powerResourceMap, ObjGet { o -> LuaViews.cached(o!!, A.A_CrewMember_Extend_powerResourceMap) { PowerMapView(it as CrewExtend) } })
        LuaReg.obj(A.A_CrewMember_Extend_powerChange, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewMember_Extend_powerChange) { x ->
                ListView(A.T_vector_p_ActivatedPowerDefinition, A.T_ActivatedPowerDefinition) { (x as CrewExtend).powerChange }
            }
        })
        // CrewMember_Extend::InitiateTeleport(shipId, roomId = -1, slotId = -1) (CustomCrew.cpp, CrewMember_Extend.h: the
        // dispatcher and its three SWIG overloads): the departure starts at once with customTele armed
        val initiateTeleport = LuaMethod {
            val c = ex(arg.obj(1)).crew
            c.hsInitiateTeleport(arg.int(2), if (arg.isNil(3)) -1 else arg.int(3), if (arg.isNil(4)) -1 else arg.int(4))
            0
        }
        for (id in intArrayOf(A.M_CrewMember_Extend_InitiateTeleport, A.M_CrewMember_Extend_InitiateTeleport__SWIG_0,
            A.M_CrewMember_Extend_InitiateTeleport__SWIG_1, A.M_CrewMember_Extend_InitiateTeleport__SWIG_2)) LuaReg.method(id, initiateTeleport)
        // unordered_map<unsigned int, ActivatedPowerResource*> (keyed by the resource definition's index)
        fun map(o: Any?): HashMap<Int, ActivatedPowerResource> = (o as PowerMapView).ex.powerResourceMap
        val p = "unordered_map_unsigned_int_ActivatedPowerResource"
        val names = mapOf(
            "size" to LuaMethod { ret.num(map(arg.obj(1)).size) },
            "empty" to LuaMethod { ret.bool(map(arg.obj(1)).isEmpty()) },
            "clear" to LuaMethod { map(arg.obj(1)).clear(); 0 },
            "has_key" to LuaMethod { ret.bool(map(arg.obj(1)).containsKey(arg.int(2))) },
            "get" to LuaMethod { ret.obj(map(arg.obj(1))[arg.int(2)], A.T_ActivatedPowerResource) },
            "__getitem" to LuaMethod { ret.obj(map(arg.obj(1))[arg.int(2)], A.T_ActivatedPowerResource) },
            "set" to LuaMethod { (arg.obj(3) as? ActivatedPowerResource)?.let { map(arg.obj(1))[arg.int(2)] = it }; 0 },
            "__setitem" to LuaMethod { (arg.obj(3) as? ActivatedPowerResource)?.let { map(arg.obj(1))[arg.int(2)] = it }; 0 },
            "del" to LuaMethod { map(arg.obj(1)).remove(arg.int(2)); 0 },
        )
        for (mid in 1..A.METHOD_COUNT) {
            val n = A.methodName(mid)
            if (!n.startsWith("$p.")) continue
            names[n.substring(p.length + 1)]?.let { LuaReg.method(mid, it) }
        }
    }

    // ================================================================ ActivatedPower, ActivatedPowerResource
    private fun powers() {
        fun p(o: Any?) = o as ActivatedPower
        shOpaque(A.A_ActivatedPower_effectAnim, "std::unique_ptr< Animation > *")
        shOpaque(A.A_ActivatedPower_tempEffectAnim, "std::unique_ptr< Animation > *")
        shOpaque(A.A_ActivatedPower_effectFinishAnim, "std::unique_ptr< Animation > *")
        shList(A.A_ActivatedPower_extraAnims, A.T_vector_Animation, A.T_Animation) { mutableListOf() }
        shRef(A.A_ActivatedPower_tempEffectStrip)
        shPointf(A.A_ActivatedPower_effectPos, 0f, 0f); shPointf(A.A_ActivatedPower_effectWorldPos, 0f, 0f)
        LuaReg.method(A.M_ActivatedPower_GetPowerDamage, LuaMethod { val x = p(arg.obj(1)); ret.obj(HsDamageView { x.def.damage }, A.T_Damage) })
        for (id in intArrayOf(A.M_ActivatedPower_PrepareAnimation, A.M_ActivatedPower_PrepareTemporaryAnimation, A.M_ActivatedPower_OnUpdate))
            LuaReg.method(id, LuaMethod { 0 })
        LuaReg.method(A.M_ActivatedPower_GetCrewBoxResourceWidth, LuaMethod {
            val x = p(arg.obj(1))
            ret.num(boxWidth(if (!x.def.hideCooldown && x.powerCooldown.second > 0f) 1 else 0, if (!x.def.hideCharges && x.powerCharges.second > 0) 1 else 0, arg.int(2)))
        })

        fun r(o: Any?) = o as ActivatedPowerResource
        shNum(A.A_ActivatedPowerResource_modifiedPowerCharges, Double.POSITIVE_INFINITY)
        shNum(A.A_ActivatedPowerResource_modifiedChargesPerJump, 0.0)
        LuaReg.method(A.M_ActivatedPowerResource_GetLinkedPowers, LuaMethod {
            val x = r(arg.obj(1))
            val l = x.ex.crewPowers.filter { pw -> pw.def.powerResources.any { it === x.def } }
            ret.obj(ListView(A.T_vector_ActivatedPower, A.T_ActivatedPower, l.toMutableList<Any?>()), A.T_vector_ActivatedPower)
        })
        LuaReg.method(A.M_ActivatedPowerResource_PowerReq, LuaMethod { ret.num(PowerReqNone) })
        LuaReg.method(A.M_ActivatedPowerResource_OnUpdate, LuaMethod { 0 })
        LuaReg.method(A.M_ActivatedPowerResource_GetCrewBoxResourceWidth, LuaMethod {
            val x = r(arg.obj(1))
            var cd = if (!x.def.hideCooldown && x.powerCooldown.second > 0f) 1 else 0
            var ch = if (!x.def.hideCharges && x.powerCharges.second > 0) 1 else 0
            if (x.def.showLinkedCooldowns || x.def.showLinkedCharges) for (pw in x.ex.crewPowers.filter { pw -> pw.def.powerResources.any { it === x.def } }) {
                if (x.def.showLinkedCooldowns && pw.powerCooldown.second > 0f) cd++
                if (x.def.showLinkedCharges && pw.powerCharges.second > 0) ch++
            }
            ret.num(boxWidth(cd, ch, arg.int(2)))
        })
    }

    private const val PowerReqNone = 0

    /** CrewBlueprint.skillLevel / colorLayers / colorChoices: the lists of the blueprint (the engine keeps its colours elsewhere). */
    private fun blueprintLists() {
        LuaReg.obj(A.A_CrewBlueprint_skillLevel, ObjGet { o -> st(o).getOrPut(A.A_CrewBlueprint_skillLevel) {
            ListView(A.T_vector_pair_int_int, A.T_pair_int_int, MutableList<Any?>(6) { IntPairView(A.T_pair_int_int, { 0 }, null, { 0 }, null) }) } })
        LuaReg.obj(A.A_CrewBlueprint_colorLayers, ObjGet { o -> st(o).getOrPut(A.A_CrewBlueprint_colorLayers) {
            ListView(A.T_vector_vector_GL_Color, A.T_vector_GL_Color, mutableListOf<Any?>()) } })
        LuaReg.obj(A.A_CrewBlueprint_colorChoices, ObjGet { o -> st(o).getOrPut(A.A_CrewBlueprint_colorChoices) {
            ListView(A.T_vector_int, 0, mutableListOf<Any?>(0, 0, 0)) } })
    }

    // ================================================================ definitions
    private fun colour(c: () -> HsColour) = c().let { GlColorValue(it.r, it.g, it.b, it.a) }

    private fun definitions() {
        fun ad(o: Any?) = o as ActivatedPowerDefinition
        LuaReg.obj(A.A_ActivatedPowerDefinition_damage, ObjGet { o -> LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_damage) { x -> HsDamageView { ad(x).damage } } })
        LuaReg.obj(A.A_ActivatedPowerDefinition_powerResources, ObjGet { o -> opaque(o, A.A_ActivatedPowerDefinition_powerResources, "std::vector< PowerResourceDefinition * > *") })
        LuaReg.obj(A.A_ActivatedPowerDefinition_crewSpawns, ObjGet { o -> opaque(o, A.A_ActivatedPowerDefinition_crewSpawns, "std::vector< CrewSpawn * > *") })
        LuaReg.obj(A.A_ActivatedPowerDefinition_event, ObjGet { o -> opaque(o, A.A_ActivatedPowerDefinition_event, "std::array< std::string,2 > *") })
        val m = LuaReg::method
        m(A.M_ActivatedPowerDefinition_AssignIndex, LuaMethod { xyz.znix.xftl.hs.crew.CustomCrewManager.assignIndex(ad(arg.obj(1))); 0 })
        m(A.M_ActivatedPowerDefinition_AssignName, LuaMethod { xyz.znix.xftl.hs.crew.CustomCrewManager.assignName(ad(arg.obj(1)), arg.str(2) ?: ""); 0 })
        m(A.M_ActivatedPowerDefinition_AssignActivateGroup, LuaMethod {
            ad(arg.obj(1)).activateGroupIndex = xyz.znix.xftl.hs.crew.CustomCrewManager.activateGroup(arg.str(2) ?: ""); 0
        })
        m(A.M_ActivatedPowerDefinition_AssignReplaceGroup, LuaMethod {
            ad(arg.obj(1)).replaceGroupIndex = xyz.znix.xftl.hs.crew.CustomCrewManager.replaceGroup(arg.str(2) ?: ""); 0
        })
        m(A.M_ActivatedPowerDefinition_AssignGroup, LuaMethod {
            val x = ad(arg.obj(1)); val n = arg.str(2) ?: ""
            x.activateGroupIndex = xyz.znix.xftl.hs.crew.CustomCrewManager.activateGroup(n)
            x.replaceGroupIndex = xyz.znix.xftl.hs.crew.CustomCrewManager.replaceGroup(n); 0
        })
        m(A.M_ActivatedPowerDefinition_AddNamedDefinition, LuaMethod {
            ret.obj(xyz.znix.xftl.hs.crew.CustomCrewManager.addNamedDefinition(arg.str(1) ?: "", arg.obj(2) as? ActivatedPowerDefinition), A.T_ActivatedPowerDefinition)
        })

        fun td(o: Any?) = o as TemporaryPowerDefinition
        LuaReg.bool(A.A_TemporaryPowerDefinition_baseVisible, { td(it).baseVisible }, { o, v -> td(o).baseVisible = v })
        LuaReg.bool(A.A_TemporaryPowerDefinition_soundsEnemy, { td(it).soundsEnemy }, { o, v -> td(o).soundsEnemy = v })
        LuaReg.obj(A.A_TemporaryPowerDefinition_sounds, ObjGet { o ->
            LuaViews.cached(o!!, A.A_TemporaryPowerDefinition_sounds) { x ->
                @Suppress("UNCHECKED_CAST") ListView(A.T_vector_string, 0, td(x).sounds as MutableList<Any?>)
            }
        })
        LuaReg.obj(A.A_TemporaryPowerDefinition_cooldownColor, ObjGet { o -> LuaViews.cached(o!!, A.A_TemporaryPowerDefinition_cooldownColor) { x -> colour { td(x).cooldownColour } } })

        fun rd(o: Any?) = o as PowerResourceDefinition
        m(A.M_PowerResourceDefinition_AssignIndex, LuaMethod { xyz.znix.xftl.hs.crew.CustomCrewManager.assignResourceIndex(rd(arg.obj(1))); 0 })
        m(A.M_PowerResourceDefinition_AssignName, LuaMethod { xyz.znix.xftl.hs.crew.CustomCrewManager.assignResourceName(rd(arg.obj(1)), arg.str(2) ?: ""); 0 })
        m(A.M_PowerResourceDefinition_AssignGroup, LuaMethod {
            rd(arg.obj(1)).groupIndex = xyz.znix.xftl.hs.crew.CustomCrewManager.replaceGroup(arg.str(2) ?: ""); 0
        })
        m(A.M_PowerResourceDefinition_AddNamedDefinition, LuaMethod {
            ret.obj(xyz.znix.xftl.hs.crew.CustomCrewManager.addNamedResource(arg.str(1) ?: "", arg.obj(2) as? PowerResourceDefinition), A.T_PowerResourceDefinition)
        })

        fun cd(o: Any?) = o as CrewDefinition
        LuaReg.obj(A.A_CrewDefinition_animSheet, ObjGet { o -> opaque(o, A.A_CrewDefinition_animSheet, "string") })
        LuaReg.obj(A.A_CrewDefinition_droneAI, ObjGet { o -> opaque(o, A.A_CrewDefinition_droneAI, "DroneAI *") })
        LuaReg.obj(A.A_CrewDefinition_explosionDef, ObjGet { o -> opaque(o, A.A_CrewDefinition_explosionDef, "ExplosionDefinition *") })
        LuaReg.obj(A.A_CrewDefinition_skillsDef, ObjGet { o -> opaque(o, A.A_CrewDefinition_skillsDef, "SkillsDefinition *") })
        LuaReg.obj(A.A_CrewDefinition_canPunch, ObjGet { cd(it).canPunch })
        LuaReg.obj(A.A_CrewDefinition_shootTimer, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewDefinition_shootTimer) { x ->
                val t = cd(x).shootTimer
                IntPairView(A.T_pair_int_int, { t[0] }, { t[0] = it }, { t[1] }, { t[1] = it })
            }
        })
        LuaReg.obj(A.A_CrewDefinition_punchTimer, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewDefinition_punchTimer) { x ->
                val t = cd(x).punchTimer
                IntPairView(A.T_pair_int_int, { t[0] }, { t[0] = it }, { t[1] }, { t[1] = it })
            }
        })
        LuaReg.obj(A.A_CrewDefinition_nameRace, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewDefinition_nameRace) { x -> @Suppress("UNCHECKED_CAST") ListView(A.T_vector_string, 0, cd(x).nameRace as MutableList<Any?>) }
        })
        LuaReg.obj(A.A_CrewDefinition_transformName, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewDefinition_transformName) { x -> @Suppress("UNCHECKED_CAST") ListView(A.T_vector_string, 0, cd(x).transformName as MutableList<Any?>) }
        })

        // ToggleValue<T> writes (value keeps the type of the stored number or flag)
        for (a in intArrayOf(A.A_ToggleValue_float_value, A.A_ToggleValue_int_value, A.A_ToggleValue_bool_value)) {
            val structSet = LuaReg.numSet[a] // a script-made ToggleValue (new_ToggleValue_*) is a plain struct (sweep-world toggle rows)
            LuaReg.numSet[a] = NumSet { o, v ->
                if (o !is xyz.znix.xftl.hs.crew.Toggle<*>) { structSet?.set(o, v); return@NumSet }
                @Suppress("UNCHECKED_CAST") val t = o as xyz.znix.xftl.hs.crew.Toggle<Any>
                t.value = when (t.value) { is Int -> v.toInt(); is Float -> v.toFloat(); is Boolean -> v != 0.0; else -> v.toFloat() }
                t.onWrite?.invoke()
            }
        }
        for (a in intArrayOf(A.A_ToggleValue_float_enabled, A.A_ToggleValue_int_enabled, A.A_ToggleValue_bool_enabled)) {
            val structSet = LuaReg.numSet[a]
            LuaReg.numSet[a] = NumSet { o, v -> if (o is xyz.znix.xftl.hs.crew.Toggle<*>) { o.enabled = v != 0.0; o.onWrite?.invoke() } else structSet?.set(o, v) }
        }

        // CustomDamageDefinition's parsed content
        fun dd(o: Any?) = o as xyz.znix.xftl.hs.weapons.HsDamageDef
        LuaReg.obj(A.A_CustomDamageDefinition_statBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomDamageDefinition_statBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) {
                    dd(x).statBoostDefs.let { l -> if (l is MutableList<*>) l else ArrayList(l).also { n -> dd(x).statBoostDefs = n } }
                }
            }
        })
        LuaReg.obj(A.A_CustomDamageDefinition_roomStatBoosts, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomDamageDefinition_roomStatBoosts) { x ->
                ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) {
                    dd(x).roomStatBoostDefs.let { l -> if (l is MutableList<*>) l else ArrayList(l).also { n -> dd(x).roomStatBoostDefs = n } }
                }
            }
        })
        LuaReg.obj(A.A_CustomDamageDefinition_crewSpawns, ObjGet { o -> opaque(o, A.A_CustomDamageDefinition_crewSpawns, "std::vector< CrewSpawn * > *") })
    }

    // ================================================================ writes of the definitions (SWIG members are writable)
    private fun cInt(v: Double): Int = if (v.isNaN() || v >= 2147483648.0 || v < -2147483648.0) Int.MIN_VALUE else v.toInt()
    private fun setNum(a: Int, f: (Any?, Double) -> Unit) { if (LuaReg.numSet[a] == null) LuaReg.numSet[a] = NumSet { o, v -> f(o, v) } }
    private fun setStr(a: Int, f: (Any?, String) -> Unit) { if (LuaReg.strSet[a] == null) LuaReg.strSet[a] = StrSet { o, v -> f(o, v) } }
    private fun cd(o: Any?) = o as CrewDefinition
    private fun ad(o: Any?) = o as ActivatedPowerDefinition
    private fun td(o: Any?) = o as TemporaryPowerDefinition
    private fun rd(o: Any?) = o as PowerResourceDefinition

    private fun setters() {
        setNum(A.A_CrewDefinition_repairSoundFrame) { o, v -> cd(o).repairSoundFrame = cInt(v) }
        setNum(A.A_CrewDefinition_canFight) { o, v -> cd(o).canFight = v != 0.0 }
        setNum(A.A_CrewDefinition_canRepair) { o, v -> cd(o).canRepair = v != 0.0 }
        setNum(A.A_CrewDefinition_canSabotage) { o, v -> cd(o).canSabotage = v != 0.0 }
        setNum(A.A_CrewDefinition_canMan) { o, v -> cd(o).canMan = v != 0.0 }
        setNum(A.A_CrewDefinition_canTeleport) { o, v -> cd(o).canTeleport = v != 0.0 }
        setNum(A.A_CrewDefinition_canSuffocate) { o, v -> cd(o).canSuffocate = v != 0.0 }
        setNum(A.A_CrewDefinition_controllable) { o, v -> cd(o).controllable = v != 0.0 }
        setNum(A.A_CrewDefinition_selectable) { o, v -> cd(o).selectable = v != 0.0 }
        setNum(A.A_CrewDefinition_canBurn) { o, v -> cd(o).canBurn = v != 0.0 }
        setNum(A.A_CrewDefinition_maxHealth) { o, v -> cd(o).maxHealth = cInt(v) }
        setNum(A.A_CrewDefinition_stunMultiplier) { o, v -> cd(o).stunMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_moveSpeedMultiplier) { o, v -> cd(o).moveSpeedMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_repairSpeed) { o, v -> cd(o).repairSpeed = v.toFloat() }
        setNum(A.A_CrewDefinition_damageMultiplier) { o, v -> cd(o).damageMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_cloneSpeedMultiplier) { o, v -> cd(o).cloneSpeedMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_rangedDamageMultiplier) { o, v -> cd(o).rangedDamageMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_doorDamageMultiplier) { o, v -> cd(o).doorDamageMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_providesPower) { o, v -> cd(o).providesPower = v != 0.0 }
        setNum(A.A_CrewDefinition_bonusPower) { o, v -> cd(o).bonusPower = cInt(v) }
        setNum(A.A_CrewDefinition_fireRepairMultiplier) { o, v -> cd(o).fireRepairMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_suffocationModifier) { o, v -> cd(o).suffocationModifier = v.toFloat() }
        setNum(A.A_CrewDefinition_isTelepathic) { o, v -> cd(o).isTelepathic = v != 0.0 }
        setNum(A.A_CrewDefinition_resistsMindControl) { o, v -> cd(o).resistsMindControl = v != 0.0 }
        setNum(A.A_CrewDefinition_isAnaerobic) { o, v -> cd(o).isAnaerobic = v != 0.0 }
        setNum(A.A_CrewDefinition_fireDamageMultiplier) { o, v -> cd(o).fireDamageMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_persDamageMultiplier) { o, v -> cd(o).persDamageMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_persHealMultiplier) { o, v -> cd(o).persHealMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_canPhaseThroughDoors) { o, v -> cd(o).canPhaseThroughDoors = v != 0.0 }
        setNum(A.A_CrewDefinition_oxygenChangeSpeed) { o, v -> cd(o).oxygenChangeSpeed = v.toFloat() }
        setNum(A.A_CrewDefinition_damageTakenMultiplier) { o, v -> cd(o).damageTakenMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_passiveHealAmount) { o, v -> cd(o).passiveHealAmount = v.toFloat() }
        setNum(A.A_CrewDefinition_truePassiveHealAmount) { o, v -> cd(o).truePassiveHealAmount = v.toFloat() }
        setNum(A.A_CrewDefinition_healAmount) { o, v -> cd(o).healAmount = v.toFloat() }
        setNum(A.A_CrewDefinition_trueHealAmount) { o, v -> cd(o).trueHealAmount = v.toFloat() }
        setNum(A.A_CrewDefinition_passiveHealDelay) { o, v -> cd(o).passiveHealDelay = cInt(v) }
        setNum(A.A_CrewDefinition_detectsLifeforms) { o, v -> cd(o).detectsLifeforms = v != 0.0 }
        setNum(A.A_CrewDefinition_hasCustomDeathAnimation) { o, v -> cd(o).hasCustomDeathAnimation = v != 0.0 }
        setNum(A.A_CrewDefinition_hasDeathExplosion) { o, v -> cd(o).hasDeathExplosion = v != 0.0 }
        setStr(A.A_CrewDefinition_animBase) { o, v -> cd(o).animBase = v }
        setNum(A.A_CrewDefinition_sabotageSpeedMultiplier) { o, v -> cd(o).sabotageSpeedMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_allDamageTakenMultiplier) { o, v -> cd(o).allDamageTakenMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_defaultSkillLevel) { o, v -> cd(o).defaultSkillLevel = cInt(v) }
        setNum(A.A_CrewDefinition_healSpeed) { o, v -> cd(o).healSpeed = v.toFloat() }
        setNum(A.A_CrewDefinition_cloneLoseSkills) { o, v -> cd(o).cloneLoseSkills = v != 0.0 }
        setNum(A.A_CrewDefinition_healCrewAmount) { o, v -> cd(o).healCrewAmount = v.toFloat() }
        setNum(A.A_CrewDefinition_droneMoveFromManningSlot) { o, v -> cd(o).droneMoveFromManningSlot = v != 0.0 }
        setNum(A.A_CrewDefinition_powerDrain) { o, v -> cd(o).powerDrain = cInt(v) }
        setNum(A.A_CrewDefinition_powerDrainFriendly) { o, v -> cd(o).powerDrainFriendly = v != 0.0 }
        setNum(A.A_CrewDefinition_damageEnemiesAmount) { o, v -> cd(o).damageEnemiesAmount = v.toFloat() }
        setNum(A.A_CrewDefinition_hackDoors) { o, v -> cd(o).hackDoors = v != 0.0 }
        setNum(A.A_CrewDefinition_powerRechargeMultiplier) { o, v -> cd(o).powerRechargeMultiplier = v.toFloat() }
        setNum(A.A_CrewDefinition_crewSlots) { o, v -> cd(o).crewSlots = v.toFloat() }
        setNum(A.A_CrewDefinition_noSlot) { o, v -> cd(o).noSlot = v != 0.0 }
        setNum(A.A_CrewDefinition_noClone) { o, v -> cd(o).noClone = v != 0.0 }
        setNum(A.A_CrewDefinition_noAI) { o, v -> cd(o).noAI = v != 0.0 }
        setNum(A.A_CrewDefinition_validTarget) { o, v -> cd(o).validTarget = v != 0.0 }
        setNum(A.A_CrewDefinition_canMove) { o, v -> cd(o).canMove = v != 0.0 }
        setNum(A.A_CrewDefinition_snapToSlot) { o, v -> cd(o).snapToSlot = v != 0.0 }
        setNum(A.A_CrewDefinition_teleportMove) { o, v -> cd(o).teleportMove = v != 0.0 }
        setNum(A.A_CrewDefinition_teleportMoveOtherShip) { o, v -> cd(o).teleportMoveOtherShip = v != 0.0 }
        setNum(A.A_CrewDefinition_essential) { o, v -> cd(o).essential = v.toFloat() }
        setNum(A.A_CrewDefinition_silenced) { o, v -> cd(o).silenced = v != 0.0 }
        setNum(A.A_CrewDefinition_lowHealthThreshold) { o, v -> cd(o).lowHealthThreshold = v.toFloat() }
        setNum(A.A_CrewDefinition_lowHealthThresholdPercentage) { o, v -> cd(o).lowHealthThresholdPercentage = v.toFloat() }
        setNum(A.A_CrewDefinition_noWarning) { o, v -> cd(o).noWarning = v != 0.0 }
        setNum(A.A_CrewDefinition_changeIfSame) { o, v -> cd(o).changeIfSame = v != 0.0 }
        setStr(A.A_CrewDefinition_race) { o, v -> cd(o).race = v }
        setNum(A.A_ActivatedPowerDefinition_sortOrder) { o, v -> ad(o).sortOrder = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_cooldown) { o, v -> ad(o).cooldown = v.toFloat() }
        setNum(A.A_ActivatedPowerDefinition_shipFriendlyFire) { o, v -> ad(o).shipFriendlyFire = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_hasSpecialPower) { o, v -> ad(o).hasSpecialPower = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_hasTemporaryPower) { o, v -> ad(o).hasTemporaryPower = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_jumpCooldown) { o, v -> ad(o).jumpCooldown = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_disabledCooldown) { o, v -> ad(o).disabledCooldown = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_initialCooldownFraction) { o, v -> ad(o).initialCooldownFraction = v.toFloat() }
        setNum(A.A_ActivatedPowerDefinition_onDeath) { o, v -> ad(o).onDeath = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_onHotkey) { o, v -> ad(o).onHotkey = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_powerCharges) { o, v -> ad(o).powerCharges = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_initialCharges) { o, v -> ad(o).initialCharges = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_chargesPerJump) { o, v -> ad(o).chargesPerJump = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_respawnCharges) { o, v -> ad(o).respawnCharges = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_disabledCharges) { o, v -> ad(o).disabledCharges = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_hideCooldown) { o, v -> ad(o).hideCooldown = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_hideCharges) { o, v -> ad(o).hideCharges = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_hideButton) { o, v -> ad(o).hideButton = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_soundsEnemy) { o, v -> ad(o).soundsEnemy = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_effectSoundsEnemy) { o, v -> ad(o).effectSoundsEnemy = v != 0.0 }
        setStr(A.A_ActivatedPowerDefinition_effectAnim) { o, v -> ad(o).effectAnim = v }
        setStr(A.A_ActivatedPowerDefinition_effectPostAnim) { o, v -> ad(o).effectPostAnim = v }
        setNum(A.A_ActivatedPowerDefinition_win) { o, v -> ad(o).win = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_crewHealth) { o, v -> ad(o).crewHealth = v.toFloat() }
        setNum(A.A_ActivatedPowerDefinition_enemyHealth) { o, v -> ad(o).enemyHealth = v.toFloat() }
        setNum(A.A_ActivatedPowerDefinition_selfHealth) { o, v -> ad(o).selfHealth = v.toFloat() }
        setNum(A.A_ActivatedPowerDefinition_animFrame) { o, v -> ad(o).animFrame = cInt(v) }
        setNum(A.A_ActivatedPowerDefinition_followCrew) { o, v -> ad(o).followCrew = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_activateWhenReady) { o, v -> ad(o).activateWhenReady = v != 0.0 }
        setNum(A.A_ActivatedPowerDefinition_activateReadyEnemies) { o, v -> ad(o).activateReadyEnemies = v != 0.0 }
        setStr(A.A_ActivatedPowerDefinition_transformRace) { o, v -> ad(o).transformRace = v }
        setNum(A.A_TemporaryPowerDefinition_duration) { o, v -> td(o).duration = v.toFloat() }
        setStr(A.A_TemporaryPowerDefinition_effectAnim) { o, v -> td(o).effectAnim = v }
        setStr(A.A_TemporaryPowerDefinition_effectFinishAnim) { o, v -> td(o).effectFinishAnim = v }
        setStr(A.A_TemporaryPowerDefinition_animSheet) { o, v -> td(o).animSheet = v }
        setNum(A.A_TemporaryPowerDefinition_baseVisible) { o, v -> td(o).baseVisible = v != 0.0 }
        setNum(A.A_TemporaryPowerDefinition_soundsEnemy) { o, v -> td(o).soundsEnemy = v != 0.0 }
        setNum(A.A_TemporaryPowerDefinition_invulnerable) { o, v -> td(o).invulnerable = v != 0.0 }
        setNum(A.A_TemporaryPowerDefinition_animFrame) { o, v -> td(o).animFrame = cInt(v) }
        setNum(A.A_PowerResourceDefinition_groupIndex) { o, v -> rd(o).groupIndex = cInt(v) }
        setNum(A.A_PowerResourceDefinition_sortOrder) { o, v -> rd(o).sortOrder = cInt(v) }
        setNum(A.A_PowerResourceDefinition_cooldown) { o, v -> rd(o).cooldown = v.toFloat() }
        setNum(A.A_PowerResourceDefinition_jumpCooldown) { o, v -> rd(o).jumpCooldown = cInt(v) }
        setNum(A.A_PowerResourceDefinition_disabledCooldown) { o, v -> rd(o).disabledCooldown = cInt(v) }
        setNum(A.A_PowerResourceDefinition_initialCooldownFraction) { o, v -> rd(o).initialCooldownFraction = v.toFloat() }
        setNum(A.A_PowerResourceDefinition_onDeath) { o, v -> rd(o).onDeath = cInt(v) }
        setNum(A.A_PowerResourceDefinition_powerCharges) { o, v -> rd(o).powerCharges = cInt(v) }
        setNum(A.A_PowerResourceDefinition_initialCharges) { o, v -> rd(o).initialCharges = cInt(v) }
        setNum(A.A_PowerResourceDefinition_chargesPerJump) { o, v -> rd(o).chargesPerJump = cInt(v) }
        setNum(A.A_PowerResourceDefinition_respawnCharges) { o, v -> rd(o).respawnCharges = cInt(v) }
        setNum(A.A_PowerResourceDefinition_disabledCharges) { o, v -> rd(o).disabledCharges = cInt(v) }
        setNum(A.A_PowerResourceDefinition_hideCooldown) { o, v -> rd(o).hideCooldown = v != 0.0 }
        setNum(A.A_PowerResourceDefinition_hideCharges) { o, v -> rd(o).hideCharges = v != 0.0 }
        setNum(A.A_PowerResourceDefinition_showTemporaryBars) { o, v -> rd(o).showTemporaryBars = v != 0.0 }
        setNum(A.A_PowerResourceDefinition_showLinkedCooldowns) { o, v -> rd(o).showLinkedCooldowns = v != 0.0 }
        setNum(A.A_PowerResourceDefinition_showLinkedCharges) { o, v -> rd(o).showLinkedCharges = v != 0.0 }
    }

    /** A getter the engine computes, that a script may still write: the written value holds until the engine value moves. */
    private fun ovNum(a: Int) {
        val pg = LuaReg.numGet[a] ?: return
        LuaReg.numGet[a] = NumGet { o ->
            @Suppress("UNCHECKED_CAST") val w = st(o)[-a] as? Pair<Double, Double>
            val e = pg.get(o)
            if (w != null && w.second == e) w.first else e
        }
        LuaReg.numSet[a] = NumSet { o, v -> ovPut(a, o, v) }
    }

    private fun ovPut(a: Int, o: Any?, v: Double) { st(o)[-a] = Pair(v, LuaReg.numGet[a]?.let { g -> st(o).remove(-a); g.get(o) } ?: 0.0) }

    // ================================================================ StatBoostDefinition members the engine class holds
    /** A float / int list of the engine whose elements Lua writes as numbers. */
    private class NumList(val l: MutableList<*>, val int: Boolean) : AbstractMutableList<Any?>() {
        override val size: Int get() = l.size
        override fun get(index: Int): Any? = l[index]
        @Suppress("UNCHECKED_CAST")
        private fun conv(e: Any?): Any? = (e as? Number)?.let { if (int) it.toInt() else it.toFloat() } ?: e
        @Suppress("UNCHECKED_CAST")
        override fun add(index: Int, element: Any?) { (l as MutableList<Any?>).add(index, conv(element)) }
        @Suppress("UNCHECKED_CAST")
        override fun removeAt(index: Int): Any? = (l as MutableList<Any?>).removeAt(index)
        @Suppress("UNCHECKED_CAST")
        override fun set(index: Int, element: Any?): Any? = (l as MutableList<Any?>).set(index, conv(element))
    }

    private fun sbObj(a: Int, f: (StatBoostDefinition) -> Any?) {
        val pg = LuaReg.objGet[a]
        LuaReg.objGet[a] = ObjGet { o -> if (o is StatBoostDefinition) f(o) else (pg ?: unsupported(a)).get(o) }
    }

    private fun sbBool(a: Int, get: (StatBoostDefinition) -> Boolean, set: (StatBoostDefinition, Boolean) -> Unit) {
        val pg = LuaReg.numGet[a]; val ps = LuaReg.numSet[a]
        LuaReg.numGet[a] = NumGet { o -> if (o is StatBoostDefinition) d(get(o)) else (pg ?: unsupported(a)).get(o) }
        LuaReg.numSet[a] = NumSet { o, v -> if (o is StatBoostDefinition) set(o, v != 0.0) else (ps ?: unsupported(a)).set(o, v) }
    }

    private fun statBoostMembers() {
        LuaStructs.register(tid("pair_CrewExtraCondition_bool"))
        sbObj(A.A_StatBoostDefinition_roomAnim) { x -> if (x.roomAnim == null) null else opaque(x, A.A_StatBoostDefinition_roomAnim, "RoomAnimDef *") }
        sbObj(A.A_StatBoostDefinition_powerChange) { it.powerChange }
        sbObj(A.A_StatBoostDefinition_powerWhitelist) { opaque(it, A.A_StatBoostDefinition_powerWhitelist, "std::unordered_set< ActivatedPowerDefinition * > *") }
        sbObj(A.A_StatBoostDefinition_powerBlacklist) { opaque(it, A.A_StatBoostDefinition_powerBlacklist, "std::unordered_set< ActivatedPowerDefinition * > *") }
        sbObj(A.A_StatBoostDefinition_powerResourceWhitelist) { opaque(it, A.A_StatBoostDefinition_powerResourceWhitelist, "std::unordered_set< PowerResourceDefinition * > *") }
        sbObj(A.A_StatBoostDefinition_powerResourceBlacklist) { opaque(it, A.A_StatBoostDefinition_powerResourceBlacklist, "std::unordered_set< PowerResourceDefinition * > *") }
        sbObj(A.A_StatBoostDefinition_powerGroupWhitelist) { opaque(it, A.A_StatBoostDefinition_powerGroupWhitelist, "std::unordered_set< unsigned int > *") }
        sbObj(A.A_StatBoostDefinition_powerGroupBlacklist) { opaque(it, A.A_StatBoostDefinition_powerGroupBlacklist, "std::unordered_set< unsigned int > *") }
        sbObj(A.A_StatBoostDefinition_deathEffectChange) { x -> if (x.deathEffectChange == null) null else opaque(x, A.A_StatBoostDefinition_deathEffectChange, "ExplosionDefinition *") }
        sbBool(A.A_StatBoostDefinition_hasPowerList, { it.hasPowerList }, { x, v -> x.hasPowerList = v })
        sbBool(A.A_StatBoostDefinition_extraConditionsReq, { it.extraConditionsReq }, { x, v -> x.extraConditionsReq = v })
        sbBool(A.A_StatBoostDefinition_systemRoomReq, { it.systemRoomReq }, { x, v -> x.systemRoomReq = v })
        sbObj(A.A_StatBoostDefinition_powerScaling) { x -> LuaViews.cached(x, A.A_StatBoostDefinition_powerScaling) { ListView(A.attrType(A.A_StatBoostDefinition_powerScaling), 0, NumList(x.powerScaling, false)) } }
        sbObj(A.A_StatBoostDefinition_systemPowerScaling) { x -> LuaViews.cached(x, A.A_StatBoostDefinition_systemPowerScaling) { ListView(A.attrType(A.A_StatBoostDefinition_systemPowerScaling), 0, NumList(x.systemPowerScaling, true)) } }
        for (a in intArrayOf(A.A_StatBoostDefinition_extraConditions, A.A_StatBoostDefinition_extraOrConditions)) sbObj(a) { x ->
            LuaViews.cached(x, a) {
                val list = if (a == A.A_StatBoostDefinition_extraConditions) x.extraConditions else x.extraOrConditions
                val et = tid("pair_CrewExtraCondition_bool")
                ListView(A.attrType(a), et, list.map { p ->
                    LuaStruct(et).also { s -> s.values[A.A_pair_CrewExtraCondition_bool_first] = p.first.ordinal.toDouble(); s.values[A.A_pair_CrewExtraCondition_bool_second] = p.second }
                }.toMutableList<Any?>())
            }
        }
        for (a in intArrayOf(A.A_StatBoostDefinition_healthReq, A.A_StatBoostDefinition_healthFractionReq, A.A_StatBoostDefinition_oxygenReq)) sbObj(a) { x ->
            LuaViews.cached(x, a) {
                val f = when (a) { A.A_StatBoostDefinition_healthReq -> x.healthReq; A.A_StatBoostDefinition_healthFractionReq -> x.healthFractionReq; else -> x.oxygenReq }
                FloatPairView(A.T_pair_float_float, { f[0] }, { f[0] = it }, { f[1] }, { f[1] = it })
            }
        }
        sbObj(A.A_StatBoostDefinition_fireCount) { x ->
            LuaViews.cached(x, A.A_StatBoostDefinition_fireCount) { IntPairView(A.T_pair_int_int, { x.fireCount[0] }, { x.fireCount[0] = it }, { x.fireCount[1] }, { x.fireCount[1] = it }) }
        }
    }

    /** ActivatedPower::GetCrewBoxResourceWidth(mode) (CrewAbilities.cpp): the width its cooldown and charge bars take in a crew box. */
    private val cooldownWidths = intArrayOf(4, 3, 2, 2, 1)
    private val chargesWidths = intArrayOf(3, 2, 2, 1, 1)

    private fun boxWidth(hasCooldown: Int, hasCharges: Int, mode: Int): Int {
        val m = mode.coerceIn(0, 4)
        val w = hasCooldown * (cooldownWidths[m] + 1) + hasCharges * (chargesWidths[m] + 1)
        return if (w > 0) w + 3 else 0
    }

    // ================================================================ StatBoostDefinition methods
    private fun statBoosts() {
        fun sb(o: Any?) = o as StatBoostDefinition
        // StatBoostDefinition::GiveId: realBoostId = statBoostDefs.size(); statBoostDefs.push_back(this)
        LuaReg.method(A.M_StatBoostDefinition_GiveId, LuaMethod {
            val x = sb(arg.obj(1))
            x.realBoostId = xyz.znix.xftl.hs.stat.StatBoostDefs.defs.size
            xyz.znix.xftl.hs.stat.StatBoostDefs.defs.add(x)
            0
        })
        LuaReg.method(A.M_StatBoostDefinition_TestRoomStatBoostSystem, LuaMethod {
            val x = sb(arg.obj(1)); val ship = arg.obj(2) as? xyz.znix.xftl.Ship
            val room = ship?.rooms?.getOrNull(arg.int(3))
            ret.bool(room != null && xyz.znix.xftl.hs.stat.StatBoostManager.testRoomStatBoostSystem(x, room))
        })
        LuaReg.method(A.M_StatBoostDefinition_IsTargetPower, LuaMethod {
            val power = arg.obj(2)
            ret.bool(when (power) {
                is ActivatedPowerDefinition -> sb(arg.obj(1)).isTargetPower(power)
                is PowerResourceDefinition -> sb(arg.obj(1)).isTargetPower(power)
                else -> false
            })
        })
    }

    // ================================================================ CrewMember methods, from the HS 1.6.9 code
    /** The route ShipGraph::FindPath(p1, p2, shipId) returns, as far as CrewMember::SetPath needs it. */
    class PathValue(@JvmField val shipId: Int, @JvmField val startX: Int, @JvmField val startY: Int,
                    @JvmField val finishX: Int, @JvmField val finishY: Int)

    /** ShipGraph::FindPath's Path: start, finish and the ship (the doors and distance stay native-only). */
    @JvmStatic
    fun pathOf(p1: Any?, p2: Any?, shipId: Int): PathValue? {
        val a = p1 as? PointValue ?: return null
        val b = p2 as? PointValue ?: return null
        return PathValue(shipId, a.x, a.y, b.x, b.y)
    }

    /** CrewTask {taskId, room, system} by value (CrewMember::SetTask / ClearTask; scripts only pass it on). */
    class CrewTaskValue(@JvmField var taskId: Int, @JvmField var room: Int, @JvmField var system: Int, override val luaType: Int) : LuaTyped

    private fun taskOf(o: Any?): CrewTaskValue =
        st(o).getOrPut(A.A_CrewMember_task) { CrewTaskValue(4, c(o).room.id, -1, tid("CrewTask *")) } as CrewTaskValue

    private fun hsStat(x: AbstractCrew, s: CrewStat): Float =
        if (x is xyz.znix.xftl.hs.crew.HsCrewMember && xyz.znix.xftl.hs.crew.CustomCrewManager.isRace(x.hsSpecies)) x.hsStat(s) else 1f

    /** The cell the crew member stands in burns (CrewMember::iOnFire > 0). */
    private fun onFire(x: AbstractCrew): Boolean {
        val p = x.standingPosition ?: return false
        return x.room.fires.getOrNull(x.room.pointToSlot(p)) != null
    }

    /** CrewMember::CanHeal: hurt, suffocating (not anaerobic), burning, or fighting a crew member. */
    private fun canHeal(x: AbstractCrew): Boolean =
        x.health != x.maxHealth ||
            (x.room.oxygen <= xyz.znix.xftl.systems.Oxygen.OXYGEN_CRITICAL_LEVEL && x.anaerobicOxygenDrainRate <= 0f) ||
            onFire(x) ||
            (x.currentAction.isAttackAction && x.currentAction != AbstractCrew.Action.ATTACKING_DOOR)

    /** Storage key of the SetOutOfGame flag (no HS attribute id: negative ids are the overlays, so below them). */
    private const val OUT_OF_GAME = Int.MIN_VALUE

    /** The native death counter SetOutOfGame numbers crew with. */
    private var deathNumber = 0

    private fun nativeMethods() {
        val m = LuaReg::method
        // bFrozen / bFrozenLocation: the engine's flags (frozen crew neither walk nor repair, AbstractCrew.hsFrozen)
        LuaReg.bool(A.A_CrewMember_bFrozen, { c(it).hsFrozen }, { o, v -> c(o).hsFrozen = v })
        LuaReg.bool(A.A_CrewMember_bFrozenLocation, { c(it).hsFrozenLocation }, { o, v -> c(o).hsFrozenLocation = v })
        m(A.M_CrewMember_SetFrozen, LuaMethod { c(arg.obj(1)).hsSetFrozen(arg.bool(2)); 0 })
        m(A.M_CrewMember_SetFrozenLocation, LuaMethod { c(arg.obj(1)).hsFrozenLocation = arg.bool(2); 0 })
        // NeedFrozenLocation: frozen half a pixel or more away from its slot's centre, no location yet
        m(A.M_CrewMember_NeedFrozenLocation, LuaMethod {
            val x = c(arg.obj(1))
            val p = x.currentSlotPoint
            val away = kotlin.math.abs(x.hsCentreX - (p.room.offsetX + p.x * 35 + 17)) >= 0.5f ||
                kotlin.math.abs(x.hsCentreY - (p.room.offsetY + p.y * 35 + 17)) >= 0.5f
            ret.bool(x.hsFrozen && away && !x.hsFrozenLocation)
        })
        // ModifyHealth(h) = DirectModifyHealth(h * CFPS::GetSpeedFactor)
        m(A.M_CrewMember_ModifyHealth, LuaMethod {
            HsCrew.directModifyHealth(c(arg.obj(1)), arg.float(2) * LuaFps.speedFactor); 0
        })
        // ApplyDamage(d): DirectModifyHealth(d) - a negative amount hurts - and true when that killed the
        // crew member; HS (CustomCrew.cpp) multiplies d by DAMAGE_TAKEN_MULTIPLIER for its races first.
        m(A.M_CrewMember_ApplyDamage, LuaMethod {
            val x = c(arg.obj(1))
            val d = arg.float(2) * hsStat(x, CrewStat.DAMAGE_TAKEN_MULTIPLIER)
            val before = x.health
            HsCrew.directModifyHealth(x, d)
            ret.bool(before > 0f && x.health <= 0f)
        })
        // ShipDamage(d) = DirectModifyHealth(d); HS multiplies by PERS_DAMAGE_MULTIPLIER (d <= 0) or
        // PERS_HEAL_MULTIPLIER (CrewMember / CrewDrone hooks)
        m(A.M_CrewMember_ShipDamage, LuaMethod {
            val x = c(arg.obj(1)); val d = arg.float(2)
            ret.bool(HsCrew.directModifyHealth(x, d * hsStat(x, if (d <= 0f) CrewStat.PERS_DAMAGE_MULTIPLIER else CrewStat.PERS_HEAL_MULTIPLIER)))
        })
        m(A.M_CrewMember_CanHeal, LuaMethod { ret.bool(canHeal(c(arg.obj(1)))) })
        // SetMedbay(v): fMedbay = v while the crew member can heal or v < 0, else 0
        m(A.M_CrewMember_SetMedbay, LuaMethod {
            val o = arg.obj(1); val v = arg.num(2)
            ovPut(A.A_CrewMember_fMedbay, o, if (canHeal(c(o)) || v < 0.0) v.toFloat().toDouble() else 0.0); 0
        })
        // CheckFighting: bFighting = crewTarget != null (the engine's fighting state when no script target)
        m(A.M_CrewMember_CheckFighting, LuaMethod {
            val o = arg.obj(1)
            if (st(o)[A.A_CrewMember_crewTarget] != null) ovPut(A.A_CrewMember_bFighting, o, 1.0) else st(o).remove(-A.A_CrewMember_bFighting)
            0
        })
        // CloseDoorBehind(door): last_door = door
        m(A.M_CrewMember_CloseDoorBehind, LuaMethod { st(arg.obj(1))[A.A_CrewMember_last_door] = arg.obj(2); 0 })
        // OnInit: width 30, no last door; true
        m(A.M_CrewMember_OnInit, LuaMethod {
            val o = arg.obj(1); st(o)[A.A_CrewMember_width] = 30.0; st(o)[A.A_CrewMember_last_door] = null; ret.bool(true)
        })
        m(A.M_CrewMember_IsCloned, LuaMethod { ret.bool(st(arg.obj(1))[A.A_CrewMember_bCloned] as? Boolean ?: false) })
        // FireFightingSoundEffect: not while stunned, else RepairingFire
        m(A.M_CrewMember_FireFightingSoundEffect, LuaMethod {
            val x = c(arg.obj(1)); ret.bool(x.stunTime <= 0f && x.currentAction == AbstractCrew.Action.FIRE_FIGHTING)
        })
        // BadAtCombat: GetDamageMultiplier below 1, or a maximum health below 100
        m(A.M_CrewMember_BadAtCombat, LuaMethod {
            val x = c(arg.obj(1))
            val dm = if (x is xyz.znix.xftl.hs.crew.HsCrewMember) x.hsStat(CrewStat.DAMAGE_MULTIPLIER) else 1f
            ret.bool(dm < 1f || x.maxHealth < 100f)
        })
        // ClearTask: {taskId 4 (none), its room, system -1}; SetTask(task) copies a task.
        m(A.M_CrewMember_ClearTask, LuaMethod { val o = arg.obj(1); val t = taskOf(o); t.taskId = 4; t.room = c(o).room.id; t.system = -1; 0 })
        m(A.M_CrewMember_SetTask, LuaMethod {
            val o = arg.obj(1); val src = arg.obj(2) as? CrewTaskValue
            if (src != null) taskOf(o).let { it.taskId = src.taskId; it.room = src.room; it.system = src.system }
            0
        })
        LuaReg.obj(A.A_CrewMember_task, ObjGet { o -> taskOf(o) }, ObjSet { o, v ->
            (v as? CrewTaskValue)?.let { s -> taskOf(o).let { it.taskId = s.taskId; it.room = s.room; it.system = s.system } }
        })
        // Jump: one more jump survived (SCrewStats.stat[3]), then ResetPower
        m(A.M_CrewMember_Jump, LuaMethod {
            val x = c(arg.obj(1))
            (x as? LivingCrew)?.info?.browserCounters?.increment(ftl.browser.CrewRecordKind.JUMPS)
            (st(x)[A.A_CrewMember_stats] as? LuaStruct)?.let { s ->
                val l = LuaStructs.member(s, A.A_SCrewStats_stat) as ListView
                if (l.size() > 3) l.set(3, ((l.get(3) as? Number)?.toDouble() ?: 0.0) + 1.0)
            }
            x.hsExtend?.resetPower()
            0
        })
        // Restart ( with HS's hooks): full health, standing in its slot, powers and cloneClear boosts reset
        m(A.M_CrewMember_Restart, LuaMethod { c(arg.obj(1)).hsRestart(); 0 })
        // Clone ( with HS's hooks): CREW_CLONE, alive and restarted, the clone skill loss (none for
        // cloneLoseSkills false), the clone bay level's health; the clone arrival animation plays where it stands
        m(A.M_CrewMember_Clone, LuaMethod {
            val x = arg.obj(1) as? LivingCrew ?: return@LuaMethod 0
            if (xyz.znix.xftl.hs.HS.luaActive) LuaCrewEvents.crewClone(x)
            val before = xyz.znix.xftl.hs.systems.HsClone.skillsBefore(x)
            x.stunTime = 0f
            x.hsRestart()
            x.onCloned()
            x.ownerShip?.clonebay?.let { cb -> xyz.znix.xftl.hs.systems.HsClone.afterClone(cb, x, before) }
            st(x)[A.A_CrewMember_bCloned] = false
            0
        })
        // Skills (blueprint.skillLevel[skill] = (progress, maximum)): GetSkillLevel is
        // 1, 2 from half the maximum, 3 at the maximum; GetSkillProgress is (progress within the level, maximum / 2);
        // GetSkillModifier is getSkillBonus(skill, GetSkillLevel) (oracle sweep-crew cmm rows: 1, (0, 13), 5)
        fun skillXp(x: LivingCrew, s: xyz.znix.xftl.crew.Skill): Pair<Int, Int> {
            val max = s.actionsPerLevel(x.blueprint) * 2
            return Pair(Math.round(x.info.skills.getValue(s) * max), max)
        }
        fun skillLevel(x: LivingCrew, s: xyz.znix.xftl.crew.Skill): Int {
            val (xp, max) = skillXp(x, s)
            return if (xp == max) 3 else if (xp >= max / 2) 2 else 1
        }
        m(A.M_CrewMember_GetSkillLevel, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = xyz.znix.xftl.crew.Skill.entries.getOrNull(arg.int(2))
            ret.num(if (x == null || s == null) 1 else skillLevel(x, s))
        })
        m(A.M_CrewMember_GetSkillProgress, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = xyz.znix.xftl.crew.Skill.entries.getOrNull(arg.int(2))
            var first = 0; var second = 0
            if (x != null && s != null) {
                val (xp, max) = skillXp(x, s)
                second = max / 2
                first = if (xp >= second) xp - second else xp
            }
            ret.obj(IntPairView(A.T_pair_int_int, { first }, { first = it }, { second }, { second = it }), A.T_pair_int_int)
        })
        m(A.M_CrewMember_GetSkillModifier, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val id = arg.int(2); val s = xyz.znix.xftl.crew.Skill.entries.getOrNull(id)
            ret.num(LuaAdaptersSweepWorld.skillBonus(id, if (x == null || s == null) 1 else skillLevel(x, s)))
        })
        // StartTeleport (HS: customTele.teleporting): the crew animation's teleport (status 6); an armed
        // customTele (InitiateTeleport) takes the crew member there, otherwise it is gone in place once the animation
        // ends (CheckForTeleport: currentShipId -1; oracle sweep-crew2-fx sw2_tele)
        m(A.M_CrewMember_StartTeleport, LuaMethod {
            val x = c(arg.obj(1))
            if (x.hsTeleShip != -1) x.hsTeleporting = true
            else if (!x.isTeleporting) x.hsStartTeleportInPlace()
            0
        })
        // StartTeleportArrive: the arrival half of the animation where it stands
        m(A.M_CrewMember_StartTeleportArrive, LuaMethod { val x = c(arg.obj(1)); if (!x.isTeleporting) x.hsStartTeleportArriveNow(); 0 })
        // CrewAnimation.status: 6 (teleport) while the crew member teleports or arrives from the clone bay, 3 dying,
        // 8 typing (manning); otherwise the last value set (0, standing or walking: oracle sweep-crew2-fx)
        LuaStructs.register(A.T_CrewAnimation)
        LuaReg.num(A.A_CrewAnimation_status, NumGet { o ->
            val s = o as LuaStruct
            val crew = animOwner[s]
            // a value set by a script (or Restart) holds until the crew member's action changes (the native animation
            // update sets status again)
            val set = s.values[STATUS_SET] as? AbstractCrew.Action
            if (crew != null && set === crew.currentAction) return@NumGet (s.values[A.A_CrewAnimation_status] as? Double) ?: 0.0
            when (crew?.currentAction) {
                AbstractCrew.Action.TELEPORTING, AbstractCrew.Action.CLONING -> 6.0
                AbstractCrew.Action.DYING -> 3.0
                AbstractCrew.Action.MANNING -> 8.0 // typing at the console
                // CrewAnimation::OnUpdate: repairing 2, repairing while on fire duty 4 (oracle
                // sweep-crew4-base sw4_loop / sw4_rep_frozen)
                AbstractCrew.Action.REPAIRING, AbstractCrew.Action.REPAIRING_BREACH -> 2.0
                AbstractCrew.Action.FIRE_FIGHTING -> 4.0
                // fighting (a crew member, a system or a door): 7 shooting, 1 punching in a shared cell (oracle
                // sweep-crew4-droneai: a boarder sabotaging the shields reads 7)
                AbstractCrew.Action.FIGHTING, AbstractCrew.Action.SABOTAGE, AbstractCrew.Action.ATTACKING_DOOR ->
                    if (crew.hsShooting) 7.0 else 1.0
                else -> (s.values[A.A_CrewAnimation_status] as? Double) ?: 0.0
            }
        }, NumSet { o, v -> setStatus(o as LuaStruct, v) })
        // SetCurrentTarget(target, force): a crew member that cannot fight drops its target (unless it is the
        // blocking door); otherwise the target replaces the current one when forced, when there is none, or when it
        // ranks higher (GetPriority: every crew member 100, so an unforced crew target never replaces another)
        m(A.M_CrewMember_SetCurrentTarget, LuaMethod {
            val o = arg.obj(1); val x = c(o); val t = arg.obj(2); val force = arg.bool(3)
            val cur = st(o)[A.A_CrewMember_crewTarget]
            val canFight = (x as? xyz.znix.xftl.hs.crew.HsCrewMember)?.let { it.hsStat(CrewStat.CAN_FIGHT) != 0f } ?: true
            if (!canFight && t !== st(o)[A.A_CrewMember_blockingDoor]) st(o)[A.A_CrewMember_crewTarget] = null
            else if (cur == null || force) st(o)[A.A_CrewMember_crewTarget] = t
            else if (t == null) st(o)[A.A_CrewMember_crewTarget] = null
            0
        })
        // SetOutOfGame: out of the game and dead at once, unstunned, the next death number; the ship drops
        // it without cloning (as Kill(true))
        m(A.M_CrewMember_SetOutOfGame, LuaMethod {
            val o = arg.obj(1); val x = c(o)
            deathNumber++
            ovPut(A.A_CrewMember_iDeathNumber, o, deathNumber.toDouble())
            x.stunTime = 0f
            HsCrew.ex(x).noClone = true
            x.health = 0f
            x.hsKilled = true
            st(o)[OUT_OF_GAME] = true
            0
        })
        // bOutOfGame / bDead: set at once by SetOutOfGame (oracle sweep-crew2 fl|out of game); bDead also by Kill
        // (bDead = 1 before the ship's loop drops or queues the crew member; oracle sweep-crew4-clone)
        for (a in intArrayOf(A.A_CrewMember_bDead, A.A_CrewMember_bOutOfGame)) {
            val before = LuaReg.numGet[a]
            val killed = a == A.A_CrewMember_bDead
            LuaReg.numGet[a] = NumGet { o ->
                if (st(o)[OUT_OF_GAME] == true || (killed && (o as? AbstractCrew)?.hsKilled == true)) 1.0 else before?.get(o) ?: 0.0
            }
        }
        // SetPath(path): walk the route to its finish (true); without a route nothing changes
        m(A.M_CrewMember_SetPath, LuaMethod {
            val x = c(arg.obj(1)); val p = (arg.obj(2) as? LuaOpaque)?.owner as? PathValue
                ?: return@LuaMethod ret.bool(false)
            val ship = x.room.ship
            val r = ship.rooms.firstOrNull { it.containsPixelInclusive(p.finishX, p.finishY) } ?: return@LuaMethod ret.bool(false)
            st(x)[A.A_CrewMember_new_path] = true
            st(x)[A.A_CrewMember_x_destination] = p.finishX.toDouble(); st(x)[A.A_CrewMember_y_destination] = p.finishY.toDouble()
            val cell = xyz.znix.xftl.math.ConstPoint((p.finishX - r.offsetX) / 35, (p.finishY - r.offsetY) / 35)
            if (!x.setTargetRoom(r, cell)) x.setTargetRoom(r)
            // the native walk ends on the route's exact finish point, not on a slot (oracle sw2_path: 315,210)
            x.hsExactGoal = xyz.znix.xftl.math.ConstPoint(p.finishX - 17, p.finishY - 17)
            ret.bool(true)
        })
    }

    // ================================================================ round 2: single steps, slots, manning, colours
    /** Storage keys of values that last until the crew member's next update (EmptySlot, SetCurrentSystem). */
    private const val EMPTY_SLOT = Int.MIN_VALUE + 2
    private const val CUR_SYSTEM = Int.MIN_VALUE + 3
    private const val CUR_SHIP = Int.MIN_VALUE + 4
    private const val SKILL_UP = Int.MIN_VALUE + 5

    /** One frame of game time (CFPS SpeedFactor / 16), what one native crew step advances. */
    private fun frameDt(): Float = LuaFps.speedFactor * 0.0625f

    /** A value [put] during update number [AbstractCrew.hsUpdates] that the next update replaces. */
    private class UntilUpdate(val updates: Int, val value: Any?)

    private fun untilUpdate(x: AbstractCrew, key: Int): UntilUpdate? =
        (st(x)[key] as? UntilUpdate)?.takeIf { it.updates == x.hsUpdates }

    private fun slotStruct(room: Int, slot: Int, x: Int, y: Int) = slot(room, slot, x, y)

    /** The race's colour layers (CrewBlueprint.colorLayers) and the chosen index of each (colorChoices). */
    private fun colourChoices(x: LivingCrew): MutableList<Any?> = layerIndices(x).map { it.toDouble() }.toMutableList<Any?>()

    /** The index each colour layer is drawn with (LivingCrewInfo.hsLayerColours, else the one colour index). */
    private fun layerIndices(x: LivingCrew): IntArray {
        val layers = x.blueprint.colourFilters
        x.info.hsLayerColours?.let { if (it.size == layers.size) return it }
        val own = if (x.info.isFemale) x.info.colour - x.blueprint.baseNumberOfColours else x.info.colour
        return IntArray(layers.size) { i -> val n = layers[i].size; if (n > 0) own % n else 0 }
    }

    /** The crew member's own skillUp trackers: 2 rows of 6. */
    @Suppress("UNCHECKED_CAST")
    private fun skillUpRows(x: AbstractCrew): List<List<LuaStruct>> =
        st(x).getOrPut(SKILL_UP) { List(2) { List(6) { LuaStructs.create(A.T_AnimationTracker) } } } as List<List<LuaStruct>>

    private fun luaRepairStep(x: AbstractCrew, dt: Float) {
        if (x.health <= 0f) return
        val fire = if (x.hsFrozen || x.currentAction.isAttackAction) 1f else x.fireFightingSpeed
        val target = st(x)[A.A_CrewMember_currentRepair]
        // an OuterHull (ShipManager's breach wrapper) repairs the breach in its slot
        when (val r = if (target is LuaOuterHull) target.room.breaches[target.slot] else target) {
            is xyz.znix.xftl.layout.BreachInstance ->
                r.health = maxOf(0f, r.health - xyz.znix.xftl.layout.BreachInstance.CREW_REPAIR_RATE * x.repairSpeed * dt)
            is xyz.znix.xftl.layout.FireInstance -> r.health = maxOf(0f, r.health - 0.08f * fire * x.repairSpeed * dt)
            is xyz.znix.xftl.AbstractSystem -> r.repair(x.repairSpeed * 0.08f * dt)
        }
    }

    private fun round2() {
        val m = LuaReg::method
        fun crew(i: Int) = c(arg.obj(i))
        // OnLoop / UpdateMovement / UpdateHealth / UpdateRepair (with HS's
        // rewrite): one more native step of that part this frame; asked for from inside the crew member's
        // own update it runs right after that update (AbstractCrew.hsLuaStep)
        // OnLoop's HS hook fires CREW_LOOP after the original, so a script's own OnLoop call fires it too
        m(A.M_CrewMember_OnLoop, LuaMethod {
            val x = crew(1); val dt = frameDt()
            x.hsLuaStep { x.update(dt); if (xyz.znix.xftl.hs.HS.luaActive) LuaEvents.crewLoop(x) }
            0
        })
        m(A.M_CrewMember_UpdateMovement, LuaMethod { val x = crew(1); val dt = frameDt(); x.hsLuaStep { x.hsMovementStep(dt) }; 0 })
        m(A.M_CrewMember_UpdateHealth, LuaMethod {
            val x = crew(1); val dt = frameDt()
            val medbay = (LuaReg.numGet[A.A_CrewMember_fMedbay]?.get(x) ?: 0.0).toFloat()
            x.hsLuaStep { if (x.health > 0f) x.hsHealthStep(dt, medbay) }
            0
        })
        // UpdateRepair: the repair the crew member works on (currentRepair: a breach, a fire) moves on by
        // one frame. Frozen and stun only gate OnLoop's own call; frozen drops the fire-fighting multiplier
        m(A.M_CrewMember_UpdateRepair, LuaMethod { val x = crew(1); val dt = frameDt(); x.hsLuaStep { luaRepairStep(x, dt) }; 0 })
        // StartRepair(r): only at its goal (within half a pixel), alive and able to repair: currentRepair = r
        m(A.M_CrewMember_StartRepair, LuaMethod {
            val x = crew(1); val r = arg.obj(2)
            val g = x.currentSlotPoint
            val atGoal = kotlin.math.abs(x.hsCentreX - (g.offsetX + 17)) < 0.5f && kotlin.math.abs(x.hsCentreY - (g.offsetY + 17)) < 0.5f
            if (atGoal && x.health > 0f && x.canRepair && r != null) {
                st(x)[A.A_CrewMember_currentRepair] = r
                // StartRepair ends with one UpdateRepair (oracle sweep-crew4 rep|StartRepair step)
                val dt = frameDt(); x.hsLuaStep { luaRepairStep(x, dt) }
            }
            0
        })
        m(A.M_CrewMember_StopRepairing, LuaMethod {
            val x = crew(1); st(x)[A.A_CrewMember_currentRepair] = null
            if (x.currentAction == AbstractCrew.Action.REPAIRING) x.stun(0f)
            0
        })
        // EmptySlot: the slot is given up, currentSlot -1/-1 at (-1, -1) until the next update picks one
        m(A.M_CrewMember_EmptySlot, LuaMethod { val x = crew(1); st(x)[EMPTY_SLOT] = UntilUpdate(x.hsUpdates, true); 0 })
        val slotBefore = LuaReg.objGet[A.A_CrewMember_currentSlot]
        LuaReg.obj(A.A_CrewMember_currentSlot, ObjGet { o ->
            if (untilUpdate(c(o), EMPTY_SLOT) != null) slotStruct(-1, -1, -1, -1) else slotBefore?.get(o)
        })
        // NeedsSlot: a living crew drone without a slot
        m(A.M_CrewMember_NeedsSlot, LuaMethod {
            val x = crew(1)
            ret.bool(x.health > 0f && untilUpdate(x, EMPTY_SLOT) != null && x is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn)
        })
        // CheckForTeleport: the loop's own check; the departure that ended already reads currentShipId -1
        m(A.M_CrewMember_CheckForTeleport, LuaMethod { 0 })
        // SetCurrentSystem(sys): currentSystem = sys, bActiveManning off. A crew member that can man (not an
        // intruder, not stunned, alive) given a system takes its id as iManningId (nil keeps iManningId) and mans it
        // when the system needs manning, it neither repairs nor fights, and it stands in the console slot of its own
        // room, not the given system's (oracle sweep-crew4 man|SetCurrentSystem). The next update sets them again from
        // the room it stands in (ShipManager::UpdateCrewMembers)
        m(A.M_CrewMember_SetCurrentSystem, LuaMethod {
            val x = crew(1); val sys = arg.obj(2) as? xyz.znix.xftl.AbstractSystem
            var manning = false
            var id = (LuaReg.numGet[A.A_CrewMember_iManningId]?.get(x) ?: -1.0).toInt()
            val canMan = x.canManSystem && x.mode != AbstractCrew.SlotType.INTRUDER && x.health > 0f && x.stunTime <= 0f
            if (sys != null && canMan) {
                id = xyz.znix.xftl.ai.nativeSystemId(sys)
                val cp = x.room.system?.configuration?.computerPoint
                val here = x.standingPosition
                manning = sys.info.canBeManned && cp != null && here != null && here.room === x.room && here.posEq(cp) &&
                    st(x)[A.A_CrewMember_currentRepair] == null && !x.currentAction.isAttackAction
            }
            st(x)[CUR_SYSTEM] = UntilUpdate(x.hsUpdates, Triple(sys, id, manning))
            0
        })
        val sysBefore = LuaReg.objGet[A.A_CrewMember_currentSystem]
        LuaReg.obj(A.A_CrewMember_currentSystem, ObjGet { o ->
            val v = untilUpdate(c(o), CUR_SYSTEM)?.value as? Triple<*, *, *>; if (v != null) v.first else sysBefore?.get(o)
        }, ObjSet { o, v ->
            // a plain write of the pointer (MV More Mannable Systems on JUMP_ARRIVE): iManningId and bActiveManning keep
            // what they read, and the next update sets all three again from the room, as after SetCurrentSystem
            val x = c(o)
            val id = (LuaReg.numGet[A.A_CrewMember_iManningId]?.get(x) ?: -1.0).toInt()
            val active = LuaReg.numGet[A.A_CrewMember_bActiveManning]?.get(x) == 1.0
            st(x)[CUR_SYSTEM] = UntilUpdate(x.hsUpdates, Triple(v as? xyz.znix.xftl.AbstractSystem, id, active))
        })
        val manIdBefore = LuaReg.numGet[A.A_CrewMember_iManningId]
        LuaReg.numGet[A.A_CrewMember_iManningId] = NumGet { o ->
            val v = untilUpdate(c(o), CUR_SYSTEM)?.value as? Triple<*, *, *>
            if (v != null) (v.second as Int).toDouble() else manIdBefore?.get(o) ?: -1.0
        }
        val activeBefore = LuaReg.numGet[A.A_CrewMember_bActiveManning]
        LuaReg.numGet[A.A_CrewMember_bActiveManning] = NumGet { o ->
            val v = untilUpdate(c(o), CUR_SYSTEM)?.value as? Triple<*, *, *>
            if (v != null) (if (v.third == true) 1.0 else 0.0) else activeBefore?.get(o) ?: 0.0
        }
        // CheckSkills: usingSkill from what the crew member does (manning pilot 0 / engines 1 / shields 2 /
        // weapons 3, repairing 4, fighting 5, else -1); the skill gains of this frame's events stay the loop's own
        m(A.M_CrewMember_CheckSkills, LuaMethod {
            val x = crew(1)
            val sysId = if (LuaReg.numGet[A.A_CrewMember_bActiveManning]?.get(x) == 1.0)
                ((LuaReg.objGet[A.A_CrewMember_currentSystem]?.get(x)) as? xyz.znix.xftl.AbstractSystem)?.let { xyz.znix.xftl.ai.nativeSystemId(it) } ?: -1
            else -1
            val skill = when {
                sysId == 6 -> 0; sysId == 1 -> 1; sysId == 0 -> 2; sysId == 3 -> 3
                st(x)[A.A_CrewMember_currentRepair] != null || x.currentAction == AbstractCrew.Action.REPAIRING -> 4
                x.currentAction == AbstractCrew.Action.FIGHTING -> 5
                else -> -1
            }
            st(x)[A.A_CrewMember_usingSkill] = skill.toDouble()
            0
        })
        // skillUp: the level-up animations, two rows of six. Indexing the outer vector hands out a copy of the row
        // (std::vector<std::vector<AnimationTracker>>: a write through skillUp[0][i] is lost, a read sees the current
        // tracker). InitializeSkills starts ((0): running, time 0) the first row's tracker of every
        // skill at its maximum or at least half of it (oracle sweep-crew4 skup|InitializeSkills)
        LuaStructs.register(A.T_AnimationTracker)
        LuaReg.obj(A.A_CrewMember_skillUp, ObjGet { o ->
            val rows = skillUpRows(c(o))
            ListView(A.T_vector_vector_AnimationTracker, A.T_vector_AnimationTracker) {
                rows.map { row -> ListView(A.T_vector_AnimationTracker, A.T_AnimationTracker, row.map<LuaStruct, Any?> { LuaStructs.copy(it) }.toMutableList()) }
            }
        })
        m(A.M_CrewMember_InitializeSkills, LuaMethod {
            val x = arg.obj(1) as? LivingCrew ?: return@LuaMethod 0
            val first = skillUpRows(x)[0]
            for ((i, s) in xyz.znix.xftl.crew.Skill.entries.withIndex()) {
                val max = s.actionsPerLevel(x.blueprint) * 2
                val xp = Math.round(x.info.skills.getValue(s) * max)
                if (i < first.size && (xp == max || xp >= max / 2)) {
                    first[i].values[A.A_AnimationTracker_running] = true
                    first[i].values[A.A_AnimationTracker_current_time] = 0.0
                }
            }
            0
        })
        // CrewBlueprint colorLayers (the race's colour lists per layer) and colorChoices (the index each layer is drawn
        // with: LivingCrewInfo.hsLayerColours, else the one colour index)
        LuaReg.obj(A.A_CrewBlueprint_colorLayers, ObjGet { o ->
            val x = (o as? LuaCrewBlueprint)?.crew as? LivingCrew
            ListView(A.T_vector_vector_GL_Color, A.T_vector_GL_Color, (x?.blueprint?.colourFilters ?: emptyList()).map { layer ->
                ListView(A.T_vector_GL_Color, A.T_GL_Color, layer.map { GlColorValue(it.r, it.g, it.b, it.a) }.toMutableList<Any?>())
            }.toMutableList<Any?>())
        })
        LuaReg.obj(A.A_CrewBlueprint_colorChoices, ObjGet { o ->
            val x = (o as? LuaCrewBlueprint)?.crew as? LivingCrew
            ListView(A.T_vector_int, 0, if (x == null) mutableListOf() else colourChoices(x))
        })
        // CycleColorLayer(dir): every layer with choices moves its index by dir (below 0 to the last, past
        // the last to 0) and the strips are coloured again
        m(A.M_CrewMember_CycleColorLayer, LuaMethod {
            val x = arg.obj(1) as? LivingCrew ?: return@LuaMethod 0
            val dir = arg.int(2)
            val layers = x.blueprint.colourFilters
            val idx = layerIndices(x).copyOf()
            for (i in layers.indices) {
                val n = layers[i].size
                if (n == 0) continue
                val v = idx[i] + dir
                idx[i] = if (v < 0) n - 1 else if (v >= n) 0 else v
            }
            x.info.hsLayerColours = idx
            0
        })
        // SetCurrentShip(id): only the ids change (currentShipId and crewAnim.currentShip), as in HS;
        // they hold while the crew member stays aboard the same ship
        m(A.M_CrewMember_SetCurrentShip, LuaMethod {
            setCurrentShipId(crew(1), arg.int(2))
            0
        })
        val shipBefore = LuaReg.numGet[A.A_CrewMember_currentShipId]
        LuaReg.numGet[A.A_CrewMember_currentShipId] = NumGet { o ->
            val p = st(o)[CUR_SHIP] as? Pair<*, *>
            if (p != null && p.first === c(o).room.ship) (p.second as Int).toDouble() else shipBefore?.get(o) ?: 0.0
        }
    }

    /** CrewMember::currentShipId and crewAnim.currentShip (SetCurrentShip or a write): only the ids change. */
    @JvmStatic
    fun setCurrentShipId(x: AbstractCrew, id: Int) {
        st(x)[CUR_SHIP] = Pair(x.room.ship, id)
        crewAnimOf(x).values[A.A_CrewAnimation_currentShip] = id.toDouble()
    }

}
