// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i TeleportSystem, MindSystem,
// CloneSystem, HackingSystem and CrewMember::SetMindControl), with the members' meaning taken from the native classes
// (FTL 1.6.14 constructors and loops named in systems/Teleporter.kt, MindControl.kt, Clonebay.kt, Hacking.kt).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.crew.CrewExtend
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.systems.Hacking
import xyz.znix.xftl.systems.MindControl
import xyz.znix.xftl.systems.Teleporter
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * WP5 crew systems: every TeleportSystem, MindSystem, CloneSystem and HackingSystem member and method of
 * hyperspace.i, readable and writable as SWIG makes them, plus CrewMember::SetMindControl. Installed after the
 * earlier packages, so the CloneSystem/MindSystem/HackingSystem members registered there are replaced by these.
 */
object LuaAdaptersWP5Crew {
    private fun tp(o: Any?) = o as Teleporter
    private fun mc(o: Any?) = o as MindControl
    private fun cb(o: Any?) = o as Clonebay
    private fun hk(o: Any?) = o as Hacking

    @JvmStatic
    fun install() {
        teleport()
        mind()
        clone()
        hacking()
        LuaReg.method(A.M_CrewMember_SetMindControl, LuaMethod {
            (arg.obj(1) as? LivingCrew)?.let { MindControl.setMindControl(it, arg.bool(2)) }
            0
        })
        // CrewMember::GetIntruder: standing on another ship than its own, inverted while mind controlled.
        LuaReg.method(A.M_CrewMember_GetIntruder, LuaMethod {
            val c = arg.obj(1) as AbstractCrew
            val ex = HsCrew.ex(c)
            ret.bool((ex.currentShipId != ex.shipId) != ex.mindControlled)
        })
        customTele()
    }

    /** CrewMember_Extend::customTele (teleportMove): the armed teleport and whether a teleport plays. */
    class CustomTeleView(val crew: AbstractCrew) : LuaTyped { override val luaType: Int get() = A.T_CustomTeleport }

    private fun ct(o: Any?) = (o as CustomTeleView).crew

    private fun customTele() {
        LuaReg.obj(A.A_CrewMember_Extend_customTele, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CrewMember_Extend_customTele) { e -> CustomTeleView((e as CrewExtend).crew) }
        })
        LuaReg.bool(A.A_CustomTeleport_teleporting, { ct(it).hsTeleporting }, { o, v -> ct(o).hsTeleporting = v })
        LuaReg.num(A.A_CustomTeleport_shipId, NumGet { ct(it).hsTeleShip.toDouble() }, NumSet { o, v -> ct(o).hsTeleShip = v.toInt() })
        LuaReg.num(A.A_CustomTeleport_roomId, NumGet { ct(it).hsTeleRoom.toDouble() }, NumSet { o, v -> ct(o).hsTeleRoom = v.toInt() })
        LuaReg.num(A.A_CustomTeleport_slotId, NumGet { ct(it).hsTeleSlot.toDouble() }, NumSet { o, v -> ct(o).hsTeleSlot = v.toInt() })
    }

    private fun teleport() {
        LuaReg.num(A.A_TeleportSystem_chargeLevel, NumGet { tp(it).hsChargeLevel.toDouble() }, NumSet { o, v -> tp(o).hsChargeLevel = v.toFloat() })
        LuaReg.bool(A.A_TeleportSystem_bCanSend, { tp(it).hsCanSend }, { o, v -> tp(o).hsCanSend = v })
        LuaReg.bool(A.A_TeleportSystem_bCanReceive, { tp(it).hsCanReceive }, { o, v -> tp(o).hsCanReceive = v })
        LuaReg.num(A.A_TeleportSystem_iArmed, NumGet { tp(it).hsArmed.toDouble() }, NumSet { o, v -> tp(o).hsArmed = v.toInt() })
        LuaReg.obj(A.A_TeleportSystem_crewSlots, ObjGet { o ->
            LuaViews.cached(o!!, A.A_TeleportSystem_crewSlots) { t ->
                @Suppress("UNCHECKED_CAST")
                ListView(A.T_vector_bool, 0, (t as Teleporter).hsCrewSlots as MutableList<Any?>)
            }
        })
        LuaReg.num(A.A_TeleportSystem_iPreparedCrew, NumGet { tp(it).hsPreparedCrew.toDouble() }, NumSet { o, v -> tp(o).hsPreparedCrew = v.toInt() })
        LuaReg.num(A.A_TeleportSystem_iNumSlots, NumGet { tp(it).hsNumSlots.toDouble() })
        LuaReg.bool(A.A_TeleportSystem_bSuperShields, { tp(it).hsSuperShields }, { o, v -> tp(o).hsSuperShields = v })

        LuaReg.method(A.M_TeleportSystem_CanSend, LuaMethod { ret.bool(tp(arg.obj(1)).hsCanSendNow()) })
        LuaReg.method(A.M_TeleportSystem_CanReceive, LuaMethod { ret.bool(tp(arg.obj(1)).hsCanReceiveNow(arg.bool(2))) })
        LuaReg.method(A.M_TeleportSystem_Charged, LuaMethod { ret.bool(tp(arg.obj(1)).hsCharged()) })
        LuaReg.method(A.M_TeleportSystem_ForceReady, LuaMethod { tp(arg.obj(1)).hsForceReady(); 0 })
        LuaReg.method(A.M_TeleportSystem_GetChargedPercent, LuaMethod { ret.num(tp(arg.obj(1)).hsChargeLevel / 100f) })
        LuaReg.method(A.M_TeleportSystem_InitiateTeleport, LuaMethod { tp(arg.obj(1)).hsInitiateSystem(); 0 })
        LuaReg.method(A.M_TeleportSystem_SetArmed, LuaMethod { tp(arg.obj(1)).hsSetArmed(arg.int(2)); 0 })
        LuaReg.method(A.M_TeleportSystem_SetHackingLevel, LuaMethod { tp(arg.obj(1)).hsSetHackingLevel(arg.int(2)); 0 })
    }

    private fun mind() {
        LuaReg.obj(A.A_MindSystem_controlTimer, ObjGet { o ->
            LuaViews.cached(o!!, A.A_MindSystem_controlTimer) { m ->
                val s = m as MindControl
                FloatPairView(A.T_pair_float_float, FGet { s.hsTimerFirst }, FSet { s.hsTimerFirst = it },
                    FGet { s.hsTimerSecond }, FSet { s.hsTimerSecond = it })
            }
        })
        LuaReg.bool(A.A_MindSystem_bCanUse, { mc(it).hsCanUse }, { o, v -> mc(o).hsCanUse = v })
        LuaReg.num(A.A_MindSystem_iArmed, NumGet { mc(it).hsArmed.toDouble() }, NumSet { o, v -> mc(o).hsArmed = v.toInt() })
        LuaReg.obj(A.A_MindSystem_controlledCrew, ObjGet { o ->
            LuaViews.cached(o!!, A.A_MindSystem_controlledCrew) { m ->
                @Suppress("UNCHECKED_CAST")
                ListView(A.T_vector_CrewMember, A.T_CrewMember, (m as MindControl).hsControlled as MutableList<Any?>)
            }
        })
        LuaReg.obj(A.A_MindSystem_queuedCrew, ObjGet { o ->
            LuaViews.cached(o!!, A.A_MindSystem_queuedCrew) { m ->
                @Suppress("UNCHECKED_CAST")
                ListView(A.T_vector_CrewMember, A.T_CrewMember, (m as MindControl).hsQueued as MutableList<Any?>)
            }
        })
        LuaReg.bool(A.A_MindSystem_bSuperShields, { mc(it).hsSuperShields }, { o, v -> mc(o).hsSuperShields = v })
        LuaReg.bool(A.A_MindSystem_bBlocked, { mc(it).hsBlocked }, { o, v -> mc(o).hsBlocked = v })
        LuaReg.num(A.A_MindSystem_iQueuedTarget, NumGet { mc(it).hsQueuedTarget.toDouble() }, NumSet { o, v -> mc(o).hsQueuedTarget = v.toInt() })
        LuaReg.num(A.A_MindSystem_iQueuedShip, NumGet { mc(it).hsQueuedShip.toDouble() }, NumSet { o, v -> mc(o).hsQueuedShip = v.toInt() })

        LuaReg.method(A.M_MindSystem_SetArmed, LuaMethod { mc(arg.obj(1)).hsSetArmed(arg.int(2)); 0 })
        LuaReg.method(A.M_MindSystem_SetHackingLevel, LuaMethod { mc(arg.obj(1)).hsSetHackingLevel(arg.int(2)); 0 })
    }

    private fun clone() {
        LuaReg.num(A.A_CloneSystem_fTimeToClone, NumGet { cb(it).hsTimeToClone.toDouble() }, NumSet { o, v -> cb(o).hsTimeToClone = v.toFloat() })
        LuaReg.obj(A.A_CloneSystem_clone, ObjGet { cb(it).hsLastClone }, ObjSet { o, v -> cb(o).hsLastClone = v as? LivingCrew })
        LuaReg.num(A.A_CloneSystem_fTimeGoal, NumGet { cb(it).cloneDuration.toDouble() }, NumSet { o, v ->
            // OnLoop rescales the time to the real goal next frame (fTimeToClone / written goal x goal): keep the share.
            val c = cb(o)
            if (v > 0.0) c.cloneProgress = c.cloneProgress * c.cloneDuration / v.toFloat()
        })
        LuaReg.num(A.A_CloneSystem_fDeathTime, NumGet { cb(it).hsDeathTime.toDouble() }, NumSet { o, v -> cb(o).hsDeathTime = v.toFloat() })
        LuaReg.num(A.A_CloneSystem_slot, NumGet { cb(it).hsSlot.toDouble() }, NumSet { o, v -> cb(o).hsSlotOverride = v.toInt() })
        LuaReg.obj(A.A_CloneSystem_bottom, ObjGet { cb(it).hsBottom ?: LuaGraphics.texture("ship/interior/clone_bottom.png") },
            ObjSet { o, v -> cb(o).hsBottom = v as? LuaTexture })
        LuaReg.obj(A.A_CloneSystem_top, ObjGet { cb(it).hsTop ?: LuaGraphics.texture("ship/interior/clone_top.png") },
            ObjSet { o, v -> cb(o).hsTop = v as? LuaTexture })
        LuaReg.obj(A.A_CloneSystem_gas, ObjGet { cb(it).hsGas ?: LuaGraphics.texture("ship/interior/clone_gas.png") },
            ObjSet { o, v -> cb(o).hsGas = v as? LuaTexture })
    }

    /**
     * HackingSystem's flags as the native sets them (1.6.14): bBlocked (+0x7a8) the target's super shield, or for the
     * enemy its own jump (ShipManager::OnLoop); bArmed (+0x7a9) the player picking a target (SetArmed); bCanHack (+0x7b8)
     * a hostile target; queuedSystem (+0x7bc) the system picked (StartHacking). The drone, bHacking, currentSystem, effectTimer and
     * spendDrone stay as WP4 registered them.
     */
    private fun hacking() {
        // bHacking (+0x1c0): the drone is out (set at the launch by HackingSystem::OnLoop 0x62dc80, cleared by StopHacking
        // 0x62db00 or the drone's loss), with or without a pulse.
        LuaReg.bool(A.A_HackingSystem_bHacking, { hk(it).droneLaunched || hk(it).hsHacking }, { o, v -> hk(o).hsSetHacking(v) })
        LuaReg.bool(A.A_HackingSystem_bBlocked, { hk(it).hsBlocked }, { o, v -> hk(o).hsBlockedValue = v })
        LuaReg.bool(A.A_HackingSystem_bArmed, { hk(it).hsArmed }, { o, v -> hk(o).hsArmed = v })
        LuaReg.bool(A.A_HackingSystem_bCanHack, { hk(it).hsCanHack }, { o, v -> hk(o).hsCanHackValue = v })
        LuaReg.obj(A.A_HackingSystem_queuedSystem, ObjGet { hk(it).hsQueuedSystem })
    }
}
