// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/TemporalSystem.cpp (room effects,
// JumpLeave, Ship::OnRenderWalls, the queued-target images, TemporalSystem_Wrapper::AISelectTarget and the
// CombatAI::OnLoop hook) and src/game/Ship/Room_Extend.h (timeDilation, timeDilationSource, the animations).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Medbay
import xyz.znix.xftl.systems.SubSystem
import kotlin.random.Random

/**
 * Room_Extend's temporal part (HS hangs it on the Room; here one field on [Room], null until used). It is also the
 * Lua `room.extend` object (Room_Extend), so further Room_Extend fields can live here.
 */
class HsRoomSys : xyz.znix.xftl.hs.lua.LuaTyped {
    override val luaType: Int get() = xyz.znix.xftl.hs.lua.LuaApi.T_Room_Extend

    /** Room_Extend::timeDilation: the signed temporal level on the room (positive speeds up). */
    @JvmField var timeDilation = 0
    /** Room_Extend::timeDilationSource */
    @JvmField var source: Temporal? = null
    @JvmField var speedAnim: FTLAnimation? = null
    @JvmField var slowAnim: FTLAnimation? = null
    /** Room_Extend::erosion (HsErosionRoom); null while the room is not eroding. */
    @JvmField var erosion: HsErosionRoom.State? = null
    /** Room_Extend hull/sys/ion DamageResistChance: copied from the ship definition's room (CustomShips.cpp). */
    @JvmField var hullDamageResistChance = 0f
    @JvmField var sysDamageResistChance = 0f
    @JvmField var ionDamageResistChance = 0f
}

/**
 * The temporal system's effect on the rooms: HS multiplies `CFPS::GetSpeedFactor` by the room's dilation strength
 * inside the loops that run "in" a room, which here scales the frame time handed to the same work:
 * - crew loops (UpdateCrewmembers: `CrewMember::OnLoop`, incl. UpdateHealth's medbay healing, fire and suffocation
 *   damage, repair, fighting, movement and crew animations),
 * - the environment (UpdateEnvironment / CheckSpreadDamage: fires' loops, burn-out and spread-start timers, oxygen
 *   used by fire, breach/airlock/anaerobic air loss of the room the loss starts from, fire damage to the system),
 * - the systems loop (ShipManager::OnLoop: the clonebay, automated repair, lockdown shards).
 * The system's own loops (ion timers, oxygen refill, the medbay system loop) are not dilated.
 * Every call site checks HS.active first.
 */
object HsTemporal {
    @JvmStatic
    fun roomState(room: Room): HsRoomSys = room.hsSys ?: HsRoomSys().also {
        room.hsSys = it
        room.ship.hs?.roomDefs?.get(room.id)?.let { def ->
            it.hullDamageResistChance = def.hullDamageResistChance
            it.sysDamageResistChance = def.sysDamageResistChance
            it.ionDamageResistChance = def.ionDamageResistChance
        }
    }

    @JvmStatic
    fun dilation(room: Room?): Int = room?.hsSys?.timeDilation ?: 0

    /** TemporalSystemParser::GetDilationStrength(dilation of [room]); 1 for an undilated room. */
    @JvmStatic
    fun factor(room: Room?): Float {
        val d = room?.hsSys?.timeDilation ?: 0
        return if (d == 0) 1f else HsSystems.dilationStrength(d)
    }

    /** g_crewDilationRooms: the room the crewmember stands in on [ship] (the ship whose crew loop runs). */
    @JvmStatic
    fun crewFactor(crew: AbstractCrew, ship: Ship): Float {
        val room = crew.room
        return if (room.ship === ship) factor(room) else 1f
    }

    /** ShipManager::JumpLeave hook: the jumping ship's rooms lose any dilation that came from a temporal system. */
    @JvmStatic
    fun jumpLeave(ship: Ship) {
        for (room in ship.rooms) {
            val ex = room.hsSys ?: continue
            if (ex.source != null) {
                ex.timeDilation = 0
                ex.source = null
            }
        }
    }

    /**
     * After ShipManager::OnLoop: the room animations advance (the hook's tail). A dilation whose source is no longer
     * running (its ship destroyed or gone without a loop) is dropped, as the source's ShipDestroyed/Jump would.
     */
    @JvmStatic
    fun afterShipUpdate(ship: Ship, dt: Float) {
        for (room in ship.rooms) {
            val ex = room.hsSys ?: continue
            val src = ex.source
            if (src != null) {
                val srcShip = src.room?.ship
                if (srcShip == null || srcShip.isDead || !ship.sys.isShipPresent(srcShip)) {
                    src.shipDestroyed()
                    if (ex.source === src) { ex.source = null; ex.timeDilation = 0 }
                }
            }
            when {
                ex.timeDilation > 0 -> ex.speedAnim?.update(dt)
                ex.timeDilation < 0 -> ex.slowAnim?.update(dt)
            }
        }
    }

    /**
     * Ship::OnRenderWalls hook (before the walls): the `room_temporal_speed`/`room_temporal_slow` animation tiled
     * along the four edges, its default orientation on the bottom edge.
     */
    @JvmStatic
    fun renderRoom(room: Room, g: Graphics) {
        val ex = room.hsSys ?: return
        val d = ex.timeDilation
        if (d == 0) return
        val game = room.ship.sys
        val anim: FTLAnimation = if (d > 0) {
            ex.speedAnim ?: game.animations["room_temporal_speed"].startLooping(game).also { ex.speedAnim = it }
        } else {
            ex.slowAnim ?: game.animations["room_temporal_slow"].startLooping(game).also { ex.slowAnim = it }
        }
        val x = room.offsetX.toFloat()
        val y = room.offsetY.toFloat()
        val w = room.pixelWidth
        val h = room.pixelHeight
        for (i in 0 until w / 35) anim.draw(x + i * 35, y + h - 35)
        for (i in 0 until w / 35) {
            g.pushTransform(); g.translate(x + i * 35 + 35, y + 35); g.rotate(0f, 0f, 180f); anim.draw(0f, 0f); g.popTransform()
        }
        for (j in 0 until h / 35) {
            g.pushTransform(); g.translate(x + 35, y + j * 35); g.rotate(0f, 0f, 90f); anim.draw(0f, 0f); g.popTransform()
        }
        for (j in 0 until h / 35) {
            g.pushTransform(); g.translate(x + w - 35, y + j * 35 + 35); g.rotate(0f, 0f, -90f); anim.draw(0f, 0f); g.popTransform()
        }
    }

    /**
     * ShipManager::RenderChargeBars / CombatControl::OnRenderSelfAiming hooks: the player's queued target on [ship]
     * (`misc/temporal_placed_speed|slow.png` centred on the room, 40x40).
     */
    @JvmStatic
    fun renderQueued(ship: Ship, g: Graphics) {
        val player = ship.sys.player ?: return
        val t = player.systems.firstOrNull { it is Temporal } as Temporal? ?: return
        val room = t.queuedRoom ?: return
        if (room.ship !== ship) return
        val img = ship.sys.getImg(if (t.queuedSpeedUp) "img/misc/temporal_placed_speed.png" else "img/misc/temporal_placed_slow.png")
        val c = room.pixelCentre
        img.draw(c.x - 20f, c.y - 20f, Colour.white)
    }

    // ---- AI (TemporalSystem_Wrapper::AISelectTarget, from CombatAI::OnLoop after the vanilla loop) ----

    private class RoomCounts {
        var crew = 0; var intruders = 0; var effectiveCrew = 0; var effectiveIntruders = 0
        var mindCrew = 0; var mindIntruders = 0; var repairing = 0; var sabotaging = 0
        var crewFighting = 0; var intrudersFighting = 0; var hurtCrew = 0
    }

    private class Target(val room: Room, val speedUp: Boolean)

    private class TargetList {
        val targets = ArrayList<Target>()
        var priority = 0
        fun add(room: Room, speedUp: Boolean, newPriority: Int) {
            if (newPriority < priority) return
            val d = dilation(room)
            if (if (speedUp) d > 0 else d < 0) return
            if (newPriority > priority) {
                priority = newPriority
                targets.clear()
            }
            targets += Target(room, speedUp)
        }
    }

    /** CombatAI::OnLoop hook: an AI ship with a ready temporal system picks a target. */
    @JvmStatic
    fun aiUpdate(self: Ship, target: Ship?, rand: Random) {
        val t = self.systems.firstOrNull { it is Temporal } as Temporal? ?: return
        if (t.isReady) aiSelectTarget(t, self, target, rand)
    }

    private fun counted(crew: AbstractCrew): Boolean =
        crew.health > 0f && crew.currentAction != AbstractCrew.Action.DYING &&
            crew.currentAction != AbstractCrew.Action.TELEPORTING

    private fun isRepairing(crew: AbstractCrew): Boolean = when (crew.currentAction) {
        AbstractCrew.Action.REPAIRING, AbstractCrew.Action.REPAIRING_BREACH, AbstractCrew.Action.FIRE_FIGHTING -> true
        else -> false
    }

    private fun mindControlled(crew: AbstractCrew) = (crew as? LivingCrew)?.isMindControlled == true

    private fun counts(ship: Ship, hurt: Boolean): Map<Room, RoomCounts> {
        val out = HashMap<Room, RoomCounts>()
        for (crew in ship.crew) {
            if (!counted(crew)) continue
            val room = crew.room
            if (room.ship !== ship) continue
            val c = out.getOrPut(room) { RoomCounts() }
            if (crew.mode == AbstractCrew.SlotType.INTRUDER) {
                c.intruders++
                if (mindControlled(crew)) c.mindIntruders++
                if (crew.currentAction == AbstractCrew.Action.FIGHTING || crew.currentAction == AbstractCrew.Action.SABOTAGE) c.intrudersFighting++
                if (crew.stunTime <= 0f) c.effectiveIntruders++
                if (crew.currentAction == AbstractCrew.Action.SABOTAGE) c.sabotaging++
            } else {
                c.crew++
                if (mindControlled(crew)) c.mindCrew++
                if (crew.currentAction == AbstractCrew.Action.FIGHTING) c.crewFighting++
                if (crew.stunTime <= 0f) c.effectiveCrew++
                if (isRepairing(crew)) c.repairing++
                if (hurt && crew.maxHealth - crew.health >= 10f) c.hurtCrew++
            }
        }
        return out
    }

    private val EMPTY = RoomCounts()

    private fun fires(room: Room) = room.fires.count { it != null }
    private fun functioning(sys: xyz.znix.xftl.AbstractSystem) = when (sys) {
        is MainSystem -> sys.powerSupplied > 0
        is SubSystem -> sys.effectivePower > 0
        else -> false
    }
    private fun effectivePower(sys: xyz.znix.xftl.AbstractSystem) = when (sys) {
        is MainSystem -> sys.powerSupplied
        is SubSystem -> sys.effectivePower
        else -> 0
    }
    private fun onFire(sys: xyz.znix.xftl.AbstractSystem) = sys.room!!.fires.any { it != null }
    private fun breached(sys: xyz.znix.xftl.AbstractSystem) = sys.room!!.breaches.any { it != null }
    /** fRepairOverTime (0-100) */
    private fun repairOverTime(sys: xyz.znix.xftl.AbstractSystem) = sys.repairProgress * 100f
    /** iLockCount > 1 || (iLockCount == 1 && lockTimer.currTime < 2.5): more than 2.5 s of an ion/cooldown lock left. */
    private fun lockedLong(sys: xyz.znix.xftl.AbstractSystem) = !sys.hasPermanentLock && sys.ionTimer > 2.5f
    private fun blastDoors(ship: Ship): Boolean = (ship.doorsSystem?.effectivePower ?: 0) >= 2

    private fun aiSelectTarget(t: Temporal, self: Ship, target: Ship?, rand: Random) {
        val list = TargetList()

        run {
            val counts = counts(self, true)
            val blast = blastDoors(self)
            for (room in self.rooms) {
                val rc = counts[room] ?: EMPTY
                val fireCount = fires(room)
                val sys = room.system
                if (sys != null) {
                    if (sys is Medbay) {
                        if (fireCount < rc.repairing) {
                            if (functioning(sys) && !sys.isHackActive && rc.hurtCrew > 0 && rc.crew * effectivePower(sys) > rc.intruders) {
                                list.add(room, true, 2); continue // speed up healing
                            }
                        }
                        if (sys.isHackActive && rc.crew > 0 && rc.intrudersFighting > 0) {
                            list.add(room, false, 2); continue // slow down medbay hack death if there are also intruders
                        }
                    } else if (sys is Clonebay) {
                        if (fireCount < rc.repairing) {
                            if (functioning(sys) && !sys.isHackActive && sys.queue.isNotEmpty() && rc.effectiveCrew >= rc.effectiveIntruders) {
                                list.add(room, true, 2); continue // speed up cloning
                            }
                        }
                    }

                    if (rc.intrudersFighting > rc.effectiveCrew) {
                        list.add(room, true, 2) // slow down intruders (HS speeds the room up here)
                    } else if (rc.repairing > 0 && rc.repairing > fireCount) {
                        list.add(room, true, if (onFire(sys) || sys.damaged) 2 else 1) // speed up repairs
                    } else if (fireCount > 1 && fireCount > rc.repairing && !sys.broken) {
                        list.add(room, false, 2) // slow down fire damage
                    } else if (list.priority > 1) {
                        continue
                    } else if (rc.mindIntruders > 0) {
                        list.add(room, false, 1) // slow down mind controlled crew
                    } else if (self.isAutomated && repairOverTime(sys) > 0f && !breached(sys) && !onFire(sys)) {
                        list.add(room, true, 1) // speed up autorepair
                    }
                } else if (blast) {
                    if (rc.intrudersFighting - rc.mindIntruders >= 2)
                        list.add(room, false, 1) // slow down enemy crew in an empty room
                }
            }
        }

        if (target != null && (target.superShield <= 0 || self.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS))) {
            // The target ship's counts have no hurt crew (HS doesn't count them on this side).
            val counts = counts(target, false)
            val blast = blastDoors(target)
            for (room in target.rooms) {
                val rc = counts[room] ?: EMPTY
                val fireCount = fires(room)
                val sys = room.system
                if (sys != null) {
                    if (sys is Medbay) {
                        if (fireCount < rc.effectiveCrew) {
                            if (functioning(sys) && !sys.isHackActive && rc.hurtCrew > 0 && rc.crew * effectivePower(sys) > rc.intruders) {
                                list.add(room, false, 2); continue // slow down healing
                            }
                        }
                        if (sys.isHackActive && rc.crew > 0) {
                            list.add(room, true, if (rc.intrudersFighting > 0 || rc.crew > 1) 2 else 1); continue // speed up medbay hack death
                        }
                    } else if (sys is Clonebay) {
                        if (sys.queue.isNotEmpty()) {
                            if (fireCount < rc.crew) {
                                if (functioning(sys) && !sys.isHackActive && rc.effectiveCrew >= rc.effectiveIntruders) {
                                    list.add(room, false, 2); continue // slow down cloning
                                } else if (!target.hasAugment(AugmentBlueprint.BACKUP_DNA)) {
                                    if (!functioning(sys) &&
                                        ((sys.broken && (rc.intruders > 0 || onFire(sys) || breached(sys) || repairOverTime(sys) < 50f)) ||
                                            lockedLong(sys))
                                    ) {
                                        list.add(room, true, 2); continue // speed up clonebay death
                                    } else if (sys.isHackActive && (self.hacking?.browserPulseTimeLeft ?: 0f) > 2.5f) {
                                        list.add(room, true, 2); continue // speed up clonebay death
                                    }
                                }
                            }
                        }
                    }
                    if (rc.intrudersFighting > rc.effectiveCrew &&
                        (sys !is Medbay || rc.crew == 0 || sys.isHackActive ||
                            (sys.broken && (rc.intruders > 0 || onFire(sys) || breached(sys) || repairOverTime(sys) < 50f)) ||
                            lockedLong(sys))
                    ) {
                        list.add(room, true, 2) // speed up intruders
                    } else if (rc.repairing > 0 && rc.repairing > fireCount) {
                        list.add(room, false, if (onFire(sys) || sys.damaged) 2 else 1) // slow down repairs
                    } else if (room.oxygen * 100f > 10f &&
                        (fireCount > 2 || fireCount > rc.effectiveCrew || (fireCount > 0 && rc.intruders > 0)) && !sys.broken
                    ) {
                        if (fireCount > 2 || (fireCount == 2 && rc.effectiveCrew == 0) || (fireCount == 1 && rc.intruders > 0))
                            list.add(room, true, 2) // speed up fires
                        else
                            list.add(room, true, 1)
                    } else if (list.priority > 1) {
                        continue
                    } else if (rc.mindIntruders > 0 && rc.intrudersFighting > 0) {
                        list.add(room, true, 1) // speed up mind controlled crew
                    } else if (target.isAutomated && repairOverTime(sys) > 0f && !breached(sys) && !onFire(sys)) {
                        list.add(room, false, 1) // slow down autorepair
                    }
                } else if (blast) {
                    if (rc.intrudersFighting - rc.mindIntruders >= 2)
                        list.add(room, true, 1) // speed up boarders in the room
                }
            }
        }

        if (list.targets.isNotEmpty()) {
            // Select(): targets[random32() % size]
            val pick = list.targets[rand.nextInt(list.targets.size)]
            t.queue(pick.room, pick.speedUp)
        }
    }
}
