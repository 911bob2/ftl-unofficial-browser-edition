// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i CrewMember, CrewControl,
// CrewMemberFactory, CrewBlueprint, ActivatedPowerDefinition, PowerResourceDefinition, ActivatedPowerRequirements,
// CrewDefinition, CrewPlacementDefinition; src/game/Ship/CrewVTable.cpp for the replaced virtuals), not from the
// 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.GameText
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.hs.crew.ActivatedPowerDefinition
import xyz.znix.xftl.hs.crew.ActivatedPowerRequirements
import xyz.znix.xftl.hs.crew.CrewDefinition
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.HsColour
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.HsCrewTooltips
import xyz.znix.xftl.hs.crew.HsText
import xyz.znix.xftl.hs.crew.PowerReadyState
import xyz.znix.xftl.hs.crew.PowerResourceDefinition
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** CommandGui::crewControl: the player's crew selection (PlayerShipUI). */
object LuaCrewControl : LuaTyped { override val luaType get() = A.T_CrewControl }

/** Hyperspace.CrewFactory / Global::GetCrewFactory(). */
object LuaCrewFactory : LuaTyped { override val luaType get() = A.T_CrewMemberFactory }

/**
 * CrewMember::blueprint (a CrewBlueprint held by value in the crew member): the race blueprint's Blueprint fields and
 * the crew member's own name, sex and skills.
 */
class LuaCrewBlueprint(@JvmField val crew: AbstractCrew) : LuaTyped { override val luaType get() = A.T_CrewBlueprint }

/**
 * WP3 adapters (crew, crew control, the clone queue's factory and the crew power definitions). Members follow
 * hyperspace.i; the virtuals HS replaces (CrewVTable.cpp) read the crew stats as HS does.
 */
object LuaAdaptersWP3 {
    private fun c(o: Any?): AbstractCrew = o as AbstractCrew
    private fun d(b: Boolean) = if (b) 1.0 else 0.0
    private fun stat(o: AbstractCrew, s: CrewStat): Float { val ex = HsCrew.ex(o); return ex.calculateStat(s, ex.definition()) }
    private fun flag(o: AbstractCrew, s: CrewStat): Boolean { val ex = HsCrew.ex(o); return ex.calculateBool(s, ex.definition()) }
    private fun dying(o: AbstractCrew) = o.health <= 0f || o.currentAction == AbstractCrew.Action.DYING

    /** Skill ids 0..5: piloting, engines, shields, weapons, repair, combat (the order of Skill). */
    private fun skill(id: Int): Skill? = Skill.entries.getOrNull(id)

    private fun goal(o: AbstractCrew) = o.pathingTarget ?: o.standingPosition ?: o.findNearestRoomPos()

    private fun clonebayOf(o: AbstractCrew): Clonebay? {
        val g = LuaAdapters.inGame() ?: return null
        for (s in listOfNotNull(g.player, g.enemy)) { val cb = s.clonebay ?: continue; if (o in cb.queue) return cb }
        return null
    }

    @JvmStatic
    fun install() {
        crewMember()
        crewControl()
        factory()
        blueprint()
        powerDefinitions()
        requirements()
        crewDefinition()
    }

    private fun crewMember() {
        LuaReg.num(A.A_CrewMember_x, NumGet { c(it).hsCentreX.toDouble() })
        LuaReg.num(A.A_CrewMember_y, NumGet { c(it).hsCentreY.toDouble() })
        LuaReg.num(A.A_CrewMember_goal_x, NumGet { (goal(c(it)).offsetX + 17).toDouble() })
        LuaReg.num(A.A_CrewMember_goal_y, NumGet { (goal(c(it)).offsetY + 17).toDouble() })
        // iManningId / bActiveManning: the system manned (SystemType id) while at its console
        LuaReg.num(A.A_CrewMember_iManningId, NumGet { o ->
            val x = c(o)
            val sys = x.room.system
            if (x.currentAction == AbstractCrew.Action.MANNING) xyz.znix.xftl.ai.nativeSystemId(sys).toDouble()
            // a system's room: the crew standing there keep its id, manning or not (oracle sweep-world-y1, ph-ci)
            else if (sys != null && x.mode == AbstractCrew.SlotType.CREW &&
                x.pathingTarget == null) xyz.znix.xftl.ai.nativeSystemId(sys).toDouble() else -1.0
        })
        LuaReg.bool(A.A_CrewMember_bActiveManning, { c(it).currentAction == AbstractCrew.Action.MANNING })
        LuaReg.num(A.A_CrewMember_iRepairId, NumGet { o ->
            val x = c(o)
            if (x.currentAction == AbstractCrew.Action.REPAIRING) xyz.znix.xftl.ai.nativeSystemId(x.room.system).toDouble() else 0.0
        })
        LuaReg.bool(A.A_CrewMember_bFighting, { c(it).currentAction == AbstractCrew.Action.FIGHTING })
        LuaReg.bool(A.A_CrewMember_bFrozen, { false })
        LuaReg.bool(A.A_CrewMember_bFrozenLocation, { false })
        LuaReg.bool(A.A_CrewMember_bOutOfGame, { false })
        // CrewMember::bResisted (+0x6f4): ShipManager's crew drawing pops up the magenta RESIST 20 pixels left of the
        // crew member and clears it (GetResisted, 0x4a4059), so it reads false after the next frame (MV
        // mind-control.lua sets it for crew that resist a mind-control weapon). Shown at once here.
        LuaReg.bool(A.A_CrewMember_bResisted, { false }) { o, v ->
            val crew = c(o)
            if (v) {
                val at = crew.getPixelPositionCentre()
                crew.room.ship.showDamageTextAt(xyz.znix.xftl.math.ConstPoint(at.x - 20, at.y), "text_resist",
                    xyz.znix.xftl.rendering.Colour(255, 0, 255))
            }
        }
        // CrewMember::currentSlot: the slot it stands in or walks to (a copy)
        LuaReg.obj(A.A_CrewMember_currentSlot, ObjGet { o -> slot(goal(c(o))) })
        LuaReg.obj(A.A_CrewMember_ship, ObjGet { o -> xyz.znix.xftl.hs.render.HsRenderEvents.shipGeom(c(o).room.ship) })
        LuaReg.obj(A.A_CrewMember_currentSystem, ObjGet { o -> c(o).room.system })
        LuaReg.obj(A.A_CrewMember_blueprint, ObjGet { o -> LuaViews.cached(o!!, A.A_CrewMember_blueprint) { x -> LuaCrewBlueprint(x as AbstractCrew) } })
        // the clone queue (CloneSystem / CrewMemberFactory::GetCloneReadyList)
        LuaReg.bool(A.A_CrewMember_clone_ready, { clonebayOf(c(it)) != null })
        LuaReg.num(A.A_CrewMember_iDeathNumber, NumGet { o -> (clonebayOf(c(o))?.queue?.indexOf(o) ?: -1).toDouble() })
        LuaReg.num(A.A_CrewMember_fCloneDying, NumGet { o ->
            val cb = clonebayOf(c(o)) ?: return@NumGet 0.0
            if (cb.queue.lastOrNull() === o) cb.dyingProgress.toDouble() else 0.0
        })

        LuaReg.method(A.M_CrewMember_GetPosition, LuaMethod { val x = c(arg.obj(1)); ret.obj(PointValue(x.hsCentreX.toInt(), x.hsCentreY.toInt()), A.T_Point) })
        LuaReg.method(A.M_CrewMember_GetLocation, LuaMethod { val x = c(arg.obj(1)); ret.obj(PointValue(x.hsCentreX.toInt(), x.hsCentreY.toInt()), A.T_Point) })
        LuaReg.method(A.M_CrewMember_GetFinalGoal, LuaMethod { val x = c(arg.obj(1)); val g = goal(x); ret.obj(if (x.pathingTarget == null) PointValue(-1, -1) else PointValue(g.offsetX + 17, g.offsetY + 17), A.T_Point) })
        LuaReg.method(A.M_CrewMember_InsideRoom, LuaMethod { ret.bool(c(arg.obj(1)).room.id == arg.int(2)) })
        LuaReg.method(A.M_CrewMember_GetIntruder, LuaMethod { ret.bool(HsCrew.ex(c(arg.obj(1))).intruder) })
        LuaReg.method(A.M_CrewMember_OutOfGame, LuaMethod { ret.bool(false) })
        LuaReg.method(A.M_CrewMember_Functional, LuaMethod { ret.bool(HsCrew.ex(c(arg.obj(1))).functional) })
        LuaReg.method(A.M_CrewMember_IsDead, LuaMethod { val x = c(arg.obj(1)); ret.bool(x.health <= 0f && x !in x.room.ship.crew) })
        LuaReg.method(A.M_CrewMember_GetControllable, LuaMethod { ret.bool(c(arg.obj(1)).playerControllable) })
        // CrewVTable.cpp: _HS_CanFight/CanRepair/CanSabotage/CanMan/CanTeleport/CanBurn/IsTelepathic/IsAnaerobic
        LuaReg.method(A.M_CrewMember_CanFight, LuaMethod { ret.bool(flag(c(arg.obj(1)), CrewStat.CAN_FIGHT)) })
        LuaReg.method(A.M_CrewMember_CanRepair, LuaMethod { val x = c(arg.obj(1))
            ret.bool(!HsCrew.ex(x).intruder && !dying(x) && flag(x, CrewStat.CAN_REPAIR)) })
        LuaReg.method(A.M_CrewMember_CanSabotage, LuaMethod { val x = c(arg.obj(1))
            ret.bool(HsCrew.ex(x).intruder && flag(x, CrewStat.CAN_SABOTAGE)) })
        LuaReg.method(A.M_CrewMember_CanMan, LuaMethod { val x = c(arg.obj(1))
            ret.bool(!HsCrew.ex(x).intruder && x.stunTime == 0f && !dying(x) && flag(x, CrewStat.CAN_MAN)) })
        LuaReg.method(A.M_CrewMember_CanTeleport, LuaMethod { val x = c(arg.obj(1)); ret.bool(!dying(x) && flag(x, CrewStat.CAN_TELEPORT)) })
        LuaReg.method(A.M_CrewMember_CanBurn, LuaMethod { ret.bool(flag(c(arg.obj(1)), CrewStat.CAN_BURN)) })
        LuaReg.method(A.M_CrewMember_IsTelepathic, LuaMethod { ret.bool(flag(c(arg.obj(1)), CrewStat.IS_TELEPATHIC)) })
        LuaReg.method(A.M_CrewMember_IsAnaerobic, LuaMethod { ret.bool(flag(c(arg.obj(1)), CrewStat.IS_ANAEROBIC)) })
        LuaReg.method(A.M_CrewMember_ProvidesPower, LuaMethod { ret.bool(CustomCrewManager.definition(c(arg.obj(1)).hsSpecies).providesPower) })
        LuaReg.method(A.M_CrewMember_ProvidesVision, LuaMethod { ret.bool(c(arg.obj(1)).providesPlayerVision) })
        LuaReg.method(A.M_CrewMember_GetMaxHealth, LuaMethod { ret.num(c(arg.obj(1)).maxHealth.toInt()) })
        LuaReg.method(A.M_CrewMember_GetIntegerHealth, LuaMethod { ret.num(c(arg.obj(1)).health.toInt()) })
        LuaReg.method(A.M_CrewMember_GetMoveSpeedMultiplier, LuaMethod { ret.num(stat(c(arg.obj(1)), CrewStat.MOVE_SPEED_MULTIPLIER)) })
        LuaReg.method(A.M_CrewMember_GetRepairSpeed, LuaMethod { ret.num(stat(c(arg.obj(1)), CrewStat.REPAIR_SPEED_MULTIPLIER)) })
        LuaReg.method(A.M_CrewMember_GetDamageMultiplier, LuaMethod { ret.num(stat(c(arg.obj(1)), CrewStat.DAMAGE_MULTIPLIER)) })
        LuaReg.method(A.M_CrewMember_GetFireRepairMultiplier, LuaMethod { ret.num(stat(c(arg.obj(1)), CrewStat.FIRE_REPAIR_MULTIPLIER)) })
        LuaReg.method(A.M_CrewMember_GetSuffocationMultiplier, LuaMethod { ret.num(stat(c(arg.obj(1)), CrewStat.SUFFOCATION_MODIFIER)) })
        LuaReg.method(A.M_CrewMember_HasSpecialPower, LuaMethod { ret.bool(HsCrew.ex(c(arg.obj(1))).hasSpecialPower) })
        // _HS_PowerReady / _HS_GetPowerCooldown: the first enabled power
        LuaReg.method(A.M_CrewMember_PowerReady, LuaMethod { val ex = HsCrew.ex(c(arg.obj(1)))
            ret.bool(ex.hasSpecialPower && ex.firstCrewPower()?.powerReady() == PowerReadyState.READY) })
        LuaReg.method(A.M_CrewMember_GetPowerCooldown, LuaMethod {
            val ex = HsCrew.ex(c(arg.obj(1)))
            val p = if (ex.hasSpecialPower) ex.firstCrewPower() else null
            var a = p?.powerCooldown?.first ?: 0f; var b = p?.powerCooldown?.second ?: 1f
            ret.obj(FloatPairView(A.T_pair_float_float, { a }, { a = it }, { b }, { b = it }), A.T_pair_float_float)
        })
        LuaReg.method(A.M_CrewMember_GetSpecies, LuaMethod { ret.str(c(arg.obj(1)).hsSpecies) })
        // GetName: blueprint.crewName, the ShortenName form (one word when the whole name is too wide; oracle
        // sweep-crew4 name|S4N control = control); GetLongName: crewNameLong
        // A crew drone's body answers with its drone's names (AbstractIndoorsDrone.hsCrewName)
        LuaReg.method(A.M_CrewMember_GetName, LuaMethod {
            val o = arg.obj(1)
            ret.str((o as? LivingCrew)?.info?.shortName ?: (o as? AbstractIndoorsDrone.Pawn)?.drone?.hsCrewName(false, LuaAdaptersMore::translate) ?: "")
        })
        LuaReg.method(A.M_CrewMember_GetLongName, LuaMethod {
            val o = arg.obj(1)
            ret.str((o as? LivingCrew)?.info?.name ?: (o as? AbstractIndoorsDrone.Pawn)?.drone?.hsCrewName(true, LuaAdaptersMore::translate) ?: "")
        })
        // SetName(TextString name, bool force): the text of the TextString. A drone's body (CrewMember::SetName):
        // forced, both names are the text; otherwise the long one is, the short one its ShortenName
        LuaReg.method(A.M_CrewMember_SetName, LuaMethod {
            val o = arg.obj(1)
            if (o is AbstractIndoorsDrone.Pawn) {
                val text = textOf(arg.obj(2)) ?: return@LuaMethod 0
                o.drone.hsNameOverride = (if (arg.bool(3)) text else shortCrewName(o.room.ship.sys, text)) to text
                return@LuaMethod 0
            }
            val x = o as? LivingCrew ?: return@LuaMethod 0
            // a living crew member alike (oracle sweep-ui11 sw5-tip ln-0/ln-1)
            textOf(arg.obj(2))?.let { x.info.hsSetName(it, arg.bool(3)) }
            0
        })
        LuaReg.method(A.M_CrewMember_AtFinalGoal, LuaMethod { val x = c(arg.obj(1)); ret.bool(x.pathingTarget == null && x.standingPosition != null) })
        LuaReg.method(A.M_CrewMember_AtGoal, LuaMethod { val x = c(arg.obj(1)); ret.bool(x.nextTargetPos == null && x.standingPosition != null) })
        // ContainsPoint(x, y): the 35 px square around the centre, strictly (0x47db30)
        LuaReg.method(A.M_CrewMember_ContainsPoint, LuaMethod {
            val p = c(arg.obj(1)).getPixelPositionCentre(); val x = arg.int(2); val y = arg.int(3)
            ret.bool(x > p.x - 17 && x < p.x + 17 && y > p.y - 17 && y < p.y + 17)
        })
        LuaReg.method(A.M_CrewMember_GetTooltip, LuaMethod {
            val x = c(arg.obj(1)); val g = LuaAdapters.inGame() ?: return@LuaMethod ret.str("")
            ret.str(if (HsCrewTooltips.advanced()) HsCrewTooltips.text(g, x) else HsCrewTooltips.vanillaText(g, x))
        })
        LuaReg.method(A.M_CrewMember_Repairing, LuaMethod { val a = c(arg.obj(1)).currentAction
            ret.bool(a == AbstractCrew.Action.REPAIRING || a == AbstractCrew.Action.REPAIRING_BREACH || a == AbstractCrew.Action.FIRE_FIGHTING) })
        LuaReg.method(A.M_CrewMember_RepairingSystem, LuaMethod { ret.bool(c(arg.obj(1)).currentAction == AbstractCrew.Action.REPAIRING) })
        LuaReg.method(A.M_CrewMember_RepairingFire, LuaMethod { ret.bool(c(arg.obj(1)).currentAction == AbstractCrew.Action.FIRE_FIGHTING) })
        LuaReg.method(A.M_CrewMember_Sabotaging, LuaMethod { ret.bool(c(arg.obj(1)).currentAction == AbstractCrew.Action.SABOTAGE) })
        // skills: GetSkillLevel (0/1/2), GetSkillProgress (experience, maximum), IncreaseSkill, MasterSkill
        LuaReg.method(A.M_CrewMember_GetSkillLevel, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = skill(arg.int(2))
            ret.num(if (x == null || s == null) 0 else when (x.getSkillLevel(s)) { SkillLevel.BASE -> 0; SkillLevel.PARTIAL -> 1; SkillLevel.MAX -> 2 })
        })
        LuaReg.method(A.M_CrewMember_GetSkillProgress, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = skill(arg.int(2))
            var max = if (x != null && s != null) s.actionsPerLevel(x.blueprint) * 2 else 0
            var xp = if (x != null && s != null) Math.round(x.info.skills.getValue(s) * max) else 0
            ret.obj(IntPairView(A.T_pair_int_int, { xp }, { xp = it }, { max }, { max = it }), A.T_pair_int_int)
        })
        LuaReg.method(A.M_CrewMember_IncreaseSkill, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = skill(arg.int(2)); if (x != null && s != null) x.addSkillPoint(s); 0 })
        LuaReg.method(A.M_CrewMember_MasterSkill, LuaMethod {
            val x = arg.obj(1) as? LivingCrew; val s = skill(arg.int(2)); if (x != null && s != null) x.info.skills[s] = 1f; 0 })
        // ModifyHealth(amount): the health change of DirectModifyHealth without its result
        LuaReg.method(A.M_CrewMember_ModifyHealth, LuaMethod {
            val x = c(arg.obj(1)); val amount = arg.float(2)
            if (x is xyz.znix.xftl.hs.crew.HsCrewMember) x.directModifyHealth(amount) else x.health = (x.health + amount).coerceIn(0f, x.maxHealth)
            0
        })
        // MoveToRoom(roomId, slotId, forceMove): on the ship it stands on; -1 picks a slot; without force an order
        // for the room its slot is already in does nothing (0x4809b0)
        LuaReg.method(A.M_CrewMember_MoveToRoom, LuaMethod {
            val x = c(arg.obj(1)); val roomId = arg.int(2); val slotId = arg.int(3); val force = arg.bool(4)
            val room = x.room.ship.rooms.getOrNull(roomId) ?: return@LuaMethod ret.bool(false)
            val moved = when {
                slotId == -1 && !force -> x.commandMoveRoom(room)
                slotId == -1 -> room.firstFreeSlot(x.mode)?.let { x.setTargetRoom(room, it) } ?: false
                slotId in 0 until room.cellCount -> (force || goal(x).room !== room) && x.setTargetRoom(room, room.slotToPoint(slotId))
                else -> false
            }
            ret.bool(moved)
        })
    }

    private fun slot(p: xyz.znix.xftl.math.RoomPoint): LuaStruct {
        val s = LuaStructs.create(A.T_Slot)
        s.values[A.A_Slot_roomId] = p.room.id.toDouble()
        s.values[A.A_Slot_slotId] = p.room.pointToSlot(p).toDouble()
        (LuaStructs.member(s, A.A_Slot_worldLocation) as? LuaStruct)?.let { LuaStructs.setFields(it, (p.offsetX + 17).toDouble(), (p.offsetY + 17).toDouble()) }
        return s
    }

    /** The text of a TextString argument (a LuaStruct, a LuaTextRef or our text view): literal text or the translated id. */
    /** CrewBlueprint::ShortenName as LivingCrewInfo measures it (font 6, the English glyphs for an ignoreLanguage name). */
    private fun shortCrewName(game: xyz.znix.xftl.game.InGameState, name: String): String {
        if (game.isRunningAutomatedTest) return name
        val font = game.getFont(xyz.znix.xftl.game.PlayerShipUI.CREW_NAME_FONT).let {
            if (xyz.znix.xftl.rendering.NativeFonts.nameIgnoresLanguage(name)) xyz.znix.xftl.rendering.NativeFonts.english(it) else it
        }
        return game.nameManager.shorten(name) { font.getExtentWidth(it) }
    }

    private fun textOf(o: Any?): String? = when (o) {
        is LuaTextRef -> o.text()
        is LuaStruct -> {
            val data = o.values[A.A_TextString_data] as? String ?: ""
            if (o.values[A.A_TextString_isLiteral] as? Boolean != false) data else LuaAdaptersMore.translate(data)
        }
        else -> null
    }

    private fun textRef(t: () -> HsText, set: (HsText) -> Unit) =
        LuaTextRef({ val h = t(); if (h.isLiteral) GameText.literal(h.data) else GameText.localised(h.data) },
            { g -> set(HsText(g.hsData, g.hsIsLiteral)) })

    private fun crewControl() {
        LuaReg.obj(A.A_CommandGui_crewControl, ObjGet { if (LuaAdapters.inGame() != null) LuaCrewControl else null })
        LuaReg.obj(A.A_CrewControl_selectedCrew, ObjGet {
            LuaViews.cached(LuaCrewControl, A.A_CrewControl_selectedCrew) {
                ListView(A.T_vector_CrewMember, A.T_CrewMember) { LuaAdapters.inGame()?.shipUI?.luaSelectedCrew ?: ArrayList() }
            }
        })
        LuaReg.obj(A.A_CrewControl_potentialSelectedCrew, ObjGet {
            LuaViews.cached(LuaCrewControl, A.A_CrewControl_potentialSelectedCrew) {
                ListView(A.T_vector_CrewMember, A.T_CrewMember) { LuaAdapters.inGame()?.shipUI?.luaHoveredCrew ?: ArrayList() }
            }
        })
        LuaReg.obj(A.A_CrewControl_selectedDoor, ObjGet { LuaAdapters.inGame()?.player?.doors?.firstOrNull { it.isHovered } })
    }

    private fun factory() {
        LuaReg.obj(A.A_module_CrewFactory, ObjGet { LuaCrewFactory })
        LuaReg.method(A.M_Global_GetCrewFactory, LuaMethod { ret.obj(LuaCrewFactory, A.T_CrewMemberFactory) })
        // crewMembers: every crew member of both ships, then the clone queues
        LuaReg.obj(A.A_CrewMemberFactory_crewMembers, ObjGet {
            LuaViews.cached(LuaCrewFactory, A.A_CrewMemberFactory_crewMembers) {
                ListView(A.T_vector_CrewMember, A.T_CrewMember) {
                    val g = LuaAdapters.inGame() ?: return@ListView emptyList<Any?>()
                    val ships = listOfNotNull<Ship>(g.player, g.enemy)
                    ships.flatMap { it.crew } + ships.flatMap { it.clonebay?.queue ?: emptyList() }
                }
            }
        })
        fun ready(player: Boolean): List<Any?> {
            val g = LuaAdapters.inGame() ?: return emptyList()
            val s = if (player) g.player else g.enemy
            return ArrayList<Any?>(s?.clonebay?.queue ?: emptyList())
        }
        // GetCloneReadyList(bool player) -> vector (hyperspace.i %extend); (vector&, bool player) fills the vector
        LuaReg.method(A.M_CrewMemberFactory_GetCloneReadyList__SWIG_1, LuaMethod {
            ret.obj(ListView(A.T_vector_CrewMember, A.T_CrewMember, ready(arg.bool(2)).toMutableList()), A.T_vector_CrewMember)
        })
        LuaReg.method(A.M_CrewMemberFactory_GetCloneReadyList__SWIG_0, LuaMethod {
            val v = arg.obj(2) as? ListView ?: throw LuaError("GetCloneReadyList: expected a vector")
            for (x in ready(arg.bool(3))) v.add(x)
            0
        })
    }

    private fun blueprint() {
        fun cb(o: Any?) = (o as LuaCrewBlueprint)
        // Blueprint::name / desc on the crew's blueprint: the race's, in front of the engine-blueprint adapters
        val nameGet = LuaReg.strGet[A.A_Blueprint_name]
        LuaReg.str(A.A_Blueprint_name, StrGet { o -> if (o is LuaCrewBlueprint) o.crew.blueprint.name else nameGet?.get(o) ?: throw LuaError("Blueprint.name") },
            LuaReg.strSet[A.A_Blueprint_name])
        val descGet = LuaReg.objGet[A.A_Blueprint_desc]
        LuaReg.obj(A.A_Blueprint_desc, ObjGet { o -> if (o is LuaCrewBlueprint) descGet?.get(o.crew.blueprint) else descGet?.get(o) },
            LuaReg.objSet[A.A_Blueprint_desc])
        LuaReg.obj(A.A_CrewBlueprint_crewNameLong, ObjGet { o ->
            val x = cb(o).crew as? LivingCrew
            val drone = (cb(o).crew as? AbstractIndoorsDrone.Pawn)?.drone
            LuaViews.cached(cb(o), A.A_CrewBlueprint_crewNameLong) {
                LuaTextRef({ GameText.literal(x?.info?.name ?: drone?.hsCrewName(true, LuaAdaptersMore::translate) ?: "") }, { g ->
                    if (x != null) x.info.name = g.hsData
                    else if (drone != null) drone.hsNameOverride = drone.hsCrewName(false, LuaAdaptersMore::translate) to g.hsData
                })
            }
        })
        LuaReg.bool(A.A_CrewBlueprint_male, { o -> (cb(o).crew as? LivingCrew)?.info?.isFemale != true })
        // GetNameShort: crewName, CrewMember::GetName's (oracle sweep-ui11 sw5-tip ln-0, sw5-dname)
        LuaReg.method(A.M_CrewBlueprint_GetNameShort, LuaMethod {
            val c = cb(arg.obj(1)).crew
            ret.str((c as? LivingCrew)?.info?.shortName ?: (c as? AbstractIndoorsDrone.Pawn)?.drone?.hsCrewName(false, LuaAdaptersMore::translate) ?: "")
        })
        LuaReg.method(A.M_CrewBlueprint_GetType, LuaMethod { ret.str(cb(arg.obj(1)).crew.blueprint.name) })
        LuaReg.obj(A.A_CrewBlueprint_powers, ObjGet { o ->
            LuaViews.cached(cb(o), A.A_CrewBlueprint_powers) { x ->
                ListView(A.T_vector_TextString, A.T_TextString) {
                    val race = (x as LuaCrewBlueprint).crew.blueprint
                    race.powerStringIds.map { t -> LuaTextRef({ t }, { }) }
                }
            }
        })
    }

    /** A GL_Color member: a copy of the colour (writes do not reach the definition). */
    private fun colour(c: () -> HsColour) = c().let { GlColorValue(it.r, it.g, it.b, it.a) }

    private fun powerDefinitions() {
        fun ad(o: Any?) = o as ActivatedPowerDefinition
        LuaReg.num(A.A_ActivatedPowerDefinition_jumpCooldown, NumGet { ad(it).jumpCooldown.toDouble() }, NumSet { o, v -> ad(o).jumpCooldown = v.toInt() })
        LuaReg.num(A.A_ActivatedPowerDefinition_disabledCooldown, NumGet { ad(it).disabledCooldown.toDouble() }, NumSet { o, v -> ad(o).disabledCooldown = v.toInt() })
        LuaReg.num(A.A_ActivatedPowerDefinition_initialCooldownFraction, NumGet { ad(it).initialCooldownFraction.toDouble() }, NumSet { o, v -> ad(o).initialCooldownFraction = v.toFloat() })
        LuaReg.num(A.A_ActivatedPowerDefinition_onDeath, NumGet { ad(it).onDeath.toDouble() }, NumSet { o, v -> ad(o).onDeath = v.toInt() })
        LuaReg.num(A.A_ActivatedPowerDefinition_onHotkey, NumGet { ad(it).onHotkey.toDouble() }, NumSet { o, v -> ad(o).onHotkey = v.toInt() })
        LuaReg.num(A.A_ActivatedPowerDefinition_disabledCharges, NumGet { ad(it).disabledCharges.toDouble() }, NumSet { o, v -> ad(o).disabledCharges = v.toInt() })
        LuaReg.num(A.A_ActivatedPowerDefinition_animFrame, NumGet { ad(it).animFrame.toDouble() }, NumSet { o, v -> ad(o).animFrame = v.toInt() })
        LuaReg.bool(A.A_ActivatedPowerDefinition_shipFriendlyFire, { ad(it).shipFriendlyFire }, { o, v -> ad(o).shipFriendlyFire = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_hideCooldown, { ad(it).hideCooldown }, { o, v -> ad(o).hideCooldown = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_hideCharges, { ad(it).hideCharges }, { o, v -> ad(o).hideCharges = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_hideButton, { ad(it).hideButton }, { o, v -> ad(o).hideButton = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_soundsEnemy, { ad(it).soundsEnemy }, { o, v -> ad(o).soundsEnemy = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_effectSoundsEnemy, { ad(it).effectSoundsEnemy }, { o, v -> ad(o).effectSoundsEnemy = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_followCrew, { ad(it).followCrew }, { o, v -> ad(o).followCrew = v })
        LuaReg.bool(A.A_ActivatedPowerDefinition_activateReadyEnemies, { ad(it).activateReadyEnemies }, { o, v -> ad(o).activateReadyEnemies = v })
        LuaReg.str(A.A_ActivatedPowerDefinition_effectAnim, StrGet { ad(it).effectAnim }, StrSet { o, v -> ad(o).effectAnim = v })
        LuaReg.str(A.A_ActivatedPowerDefinition_effectPostAnim, StrGet { ad(it).effectPostAnim }, StrSet { o, v -> ad(o).effectPostAnim = v })
        LuaReg.obj(A.A_ActivatedPowerDefinition_sounds, ObjGet { o -> LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_sounds) { x ->
            @Suppress("UNCHECKED_CAST") ListView(A.T_vector_string, 0, ad(x).sounds as MutableList<Any?>) } })
        LuaReg.obj(A.A_ActivatedPowerDefinition_effectSounds, ObjGet { o -> LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_effectSounds) { x ->
            @Suppress("UNCHECKED_CAST") ListView(A.T_vector_string, 0, ad(x).effectSounds as MutableList<Any?>) } })
        LuaReg.obj(A.A_ActivatedPowerDefinition_tooltip, ObjGet { o -> LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_tooltip) { x ->
            textRef({ ad(x).tooltip }, { ad(x).tooltip = it }) } })
        LuaReg.obj(A.A_ActivatedPowerDefinition_buttonLabel, ObjGet { o -> LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_buttonLabel) { x ->
            textRef({ ad(x).buttonLabel }, { ad(x).buttonLabel = it }) } })
        LuaReg.obj(A.A_ActivatedPowerDefinition_cooldownColor, ObjGet { o -> LuaViews.cached(o!!, A.A_ActivatedPowerDefinition_cooldownColor) { x ->
            colour { ad(x).cooldownColour } } })
        LuaReg.method(A.M_ActivatedPowerDefinition_GetPowerByName, LuaMethod {
            ret.obj(CustomCrewManager.powerDefs.firstOrNull { it.name == arg.str(1) }, A.T_ActivatedPowerDefinition)
        })

        fun rd(o: Any?) = o as PowerResourceDefinition
        LuaReg.str(A.A_PowerResourceDefinition_name, StrGet { rd(it).name })
        LuaReg.num(A.A_PowerResourceDefinition_groupIndex, NumGet { rd(it).groupIndex.toDouble() })
        LuaReg.num(A.A_PowerResourceDefinition_sortOrder, NumGet { rd(it).sortOrder.toDouble() }, NumSet { o, v -> rd(o).sortOrder = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_cooldown, NumGet { rd(it).cooldown.toDouble() }, NumSet { o, v -> rd(o).cooldown = v.toFloat() })
        LuaReg.num(A.A_PowerResourceDefinition_jumpCooldown, NumGet { rd(it).jumpCooldown.toDouble() }, NumSet { o, v -> rd(o).jumpCooldown = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_disabledCooldown, NumGet { rd(it).disabledCooldown.toDouble() }, NumSet { o, v -> rd(o).disabledCooldown = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_initialCooldownFraction, NumGet { rd(it).initialCooldownFraction.toDouble() }, NumSet { o, v -> rd(o).initialCooldownFraction = v.toFloat() })
        LuaReg.num(A.A_PowerResourceDefinition_onDeath, NumGet { rd(it).onDeath.toDouble() }, NumSet { o, v -> rd(o).onDeath = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_powerCharges, NumGet { rd(it).powerCharges.toDouble() }, NumSet { o, v -> rd(o).powerCharges = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_initialCharges, NumGet { rd(it).initialCharges.toDouble() }, NumSet { o, v -> rd(o).initialCharges = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_chargesPerJump, NumGet { rd(it).chargesPerJump.toDouble() }, NumSet { o, v -> rd(o).chargesPerJump = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_respawnCharges, NumGet { rd(it).respawnCharges.toDouble() }, NumSet { o, v -> rd(o).respawnCharges = v.toInt() })
        LuaReg.num(A.A_PowerResourceDefinition_disabledCharges, NumGet { rd(it).disabledCharges.toDouble() }, NumSet { o, v -> rd(o).disabledCharges = v.toInt() })
        LuaReg.bool(A.A_PowerResourceDefinition_hideCooldown, { rd(it).hideCooldown }, { o, v -> rd(o).hideCooldown = v })
        LuaReg.bool(A.A_PowerResourceDefinition_hideCharges, { rd(it).hideCharges }, { o, v -> rd(o).hideCharges = v })
        LuaReg.bool(A.A_PowerResourceDefinition_showTemporaryBars, { rd(it).showTemporaryBars }, { o, v -> rd(o).showTemporaryBars = v })
        LuaReg.bool(A.A_PowerResourceDefinition_showLinkedCooldowns, { rd(it).showLinkedCooldowns }, { o, v -> rd(o).showLinkedCooldowns = v })
        LuaReg.bool(A.A_PowerResourceDefinition_showLinkedCharges, { rd(it).showLinkedCharges }, { o, v -> rd(o).showLinkedCharges = v })
        LuaReg.obj(A.A_PowerResourceDefinition_cooldownColor, ObjGet { o -> LuaViews.cached(o!!, A.A_PowerResourceDefinition_cooldownColor) { x ->
            colour { rd(x).cooldownColour } } })
        LuaReg.obj(A.A_PowerResourceDefinition_chargeReq, ObjGet { rd(it).chargeReq })
        LuaReg.method(A.M_PowerResourceDefinition_GetByName, LuaMethod {
            ret.obj(CustomCrewManager.resourceDefs.firstOrNull { it.name == arg.str(1) }, A.T_PowerResourceDefinition)
        })
    }

    private fun requirements() {
        fun rq(o: Any?) = o as ActivatedPowerRequirements
        LuaReg.num(A.A_ActivatedPowerRequirements_type, NumGet { rq(it).type.ordinal.toDouble() })
        @Suppress("UNCHECKED_CAST")
        fun list(a: Int, f: (ActivatedPowerRequirements) -> ArrayList<String>) =
            LuaReg.obj(a, ObjGet { o -> LuaViews.cached(o!!, a) { x -> ListView(A.T_vector_string, 0, f(rq(x)) as MutableList<Any?>) } })
        list(A.A_ActivatedPowerRequirements_whiteList) { it.whiteList }
        list(A.A_ActivatedPowerRequirements_friendlyWhiteList) { it.friendlyWhiteList }
        list(A.A_ActivatedPowerRequirements_friendlyBlackList) { it.friendlyBlackList }
        list(A.A_ActivatedPowerRequirements_enemyWhiteList) { it.enemyWhiteList }
        list(A.A_ActivatedPowerRequirements_enemyBlackList) { it.enemyBlackList }
        LuaReg.obj(A.A_ActivatedPowerRequirements_extraOrConditionsTooltip, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ActivatedPowerRequirements_extraOrConditionsTooltip) { x ->
                textRef({ rq(x).extraOrConditionsTooltip }, { rq(x).extraOrConditionsTooltip = it }) } })
    }

    private fun crewDefinition() {
        fun cd(o: Any?) = o as CrewDefinition
        @Suppress("UNCHECKED_CAST")
        fun list(a: Int, f: (CrewDefinition) -> MutableList<String>) =
            LuaReg.obj(a, ObjGet { o -> LuaViews.cached(o!!, a) { x -> ListView(A.T_vector_string, 0, f(cd(x)) as MutableList<Any?>) } })
        list(A.A_CrewDefinition_deathSounds) { it.deathSounds }
        list(A.A_CrewDefinition_deathSoundsFemale) { it.deathSoundsFemale }
        list(A.A_CrewDefinition_shootingSounds) { it.shootingSounds }
        list(A.A_CrewDefinition_repairSounds) { it.repairSounds }
    }
}
