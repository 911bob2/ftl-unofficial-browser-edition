// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewMember_Extend::TransformRace,
// GetRoomDamage death explosion, ShipManager::OnLoop name transformation, GetCrewBlueprint /
// AddCrewMemberFromString race lists), src/game/Ship/CrewSpawn.cpp (CrewSpawn::SpawnCrew)
package xyz.znix.xftl.hs.crew

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.stat.HsTimer
import xyz.znix.xftl.hs.stat.StatBoost
import xyz.znix.xftl.hs.stat.StatBoostManager
import xyz.znix.xftl.weapons.Damage
import kotlin.random.Random

/** Hooks other workstreams provide (events queue inline/named events; lockdown customisation). */
object HsCrewHooks {
    /** CustomEventsParser::QueueEvent(name, -1). */
    @JvmField var queueEvent: ((String, Element?) -> Unit)? = null
}

/** `!bDead`: a crew member whose health ran out counts as dead for HS's loops. */
fun HsCrew.isDead(c: AbstractCrew): Boolean = c.health <= 0f

/** random32() for combat-time picks (sounds, door damage rolls): the simulation stream, 31 bits. */
fun HsCrew.random32(): Int = ftl.browser.BrowserRandom.nextInt() and Int.MAX_VALUE

fun HsCrew.playSound(game: InGameState, name: String) {
    if (name.isEmpty()) return
    game.sounds.getSampleOrWarn(name)?.play()
}

/** ShipManager::LockdownRoom with a custom lockdown definition (only the vanilla lockdown is supported here). */
fun HsCrew.lockdown(crew: AbstractCrew, def: LockdownDefinition) {
    xyz.znix.xftl.hs.ship.HsLockdowns.lockdown(crew.room, def, crew.hsCentreX, crew.hsCentreY)
}

/** CrewMember::DirectModifyHealth. */
fun HsCrew.directModifyHealth(crew: AbstractCrew, healthMod: Float): Boolean {
    if (healthMod == 0f) return false
    if (crew is HsCrewMember) return crew.directModifyHealth(healthMod)
    val before = crew.health
    crew.dealDamage(HsHealthChange(-healthMod))
    return before > 0f && crew.health <= 0f
}

fun HsCrew.queueEvent(name: String, node: Element?) {
    val q = HsCrewHooks.queueEvent
    if (q == null) HS.unsupported("event", "crew power", name) else q(name, node)
}

/** CrewControl::ClearCrewBoxes for a player-side crew member. */
fun HsCrew.clearCrewBoxes(ex: CrewExtend) {
    if (ex.shipId == 0 || ex.mindControlled) ex.crew.room.ship.sys.updatePlayerCrew()
}

/**
 * The race behind a crew blueprint name as HS's ShipManager::AddCrewMemberFromString resolves it: blueprint lists
 * are followed (one `random32() % size` pick per level), then the native GetCrewBlueprint falls back to "human" for
 * a name with no crew blueprint (recorded as a capability gap, not silently).
 */

fun HsCrew.resolveRace(bm: xyz.znix.xftl.BlueprintManager, name: String, random: Random, recursive: Boolean = true): CrewBlueprint {

    var actual = name
    var bp = bm.getOrNull(actual)
    while (bp is xyz.znix.xftl.BlueprintList) {
        val names = bp.names
        if (names.isEmpty()) break
        actual = names[random.nextInt(names.size)]
        bp = bm.getOrNull(actual)
        if (!recursive) break
    }
    if (bp is CrewBlueprint) return bp
    HS.unsupported("race", "crew blueprint", actual)
    return bm["human"] as CrewBlueprint
}

/** CrewMember_Extend::TransformRace. */
fun HsCrew.transformRace(ex: CrewExtend, species: String): Boolean {
    val crew = ex.crew
    val game = crew.room.ship.sys
    // BlueprintManager::GetCrewBlueprint (one list level, then the native human fallback).
    val newBp = resolveRace(game.blueprintManager, species, ftl.browser.BrowserRandom, recursive = false)
    crew.hsSetRace(newBp)
    if (crew is LivingCrew) crew.info.race = newBp
    ex.originalColorRace = newBp.name
    (crew as? HsCrewMember)?.initialize(true)
    if (ex.shipId == 0) game.updatePlayerCrew()
    StatBoostManager.statCacheFrame++
    return true
}

/** ShipManager::OnLoop: `<nameRace transformName=...>` turns a crew member with a matching name into that race. */
fun HsCrew.nameTransform(crew: LivingCrew, ex: CrewExtend) {
    val def = CustomCrewManager.definitionOrNull(crew.codename) ?: return
    if (def.transformName.isEmpty()) return
    val name = crew.info.name
    if (def.changeIfSame) {
        var counter = 0
        for (n in def.transformName) {
            if (name == n) {
                val newSpecies = def.nameRace[counter]
                ++counter
                ex.originalRace = newSpecies
                ex.transformRaceTo(newSpecies)
            }
        }
    } else {
        if (def.transformName.all { it == name }) {
            val newSpecies = def.nameRace[0]
            ex.originalRace = newSpecies
            ex.transformRaceTo(newSpecies)
        }
    }
}

/** HsDamage as the engine's [Damage] (native Damage chances are tenths). */
fun HsCrew.toDamage(d: HsDamage, owner: Ship?): Damage {
    val r = Damage()
    r.hullDamage = d.iDamage
    r.ionDamage = d.iIonDamage
    r.pureSysDamage = d.iSystemDamage
    r.pureCrewDamage = d.iPersDamage * 15
    r.emptyRoomBonus = if (d.bHullBuster) 1 else 0
    r.fireChance = d.fireChance * 10
    r.breachChance = d.breachChance * 10
    r.stunChance = d.stunChance * 10
    r.stunDuration = d.iStun.toFloat()
    r.lockdown = d.bLockdown
    r.ownerShip = owner
    r.friendlyFire = d.bFriendlyFire
    return r
}

/**
 * ShipManager::DamageArea for crew effects (death explosions, activated powers): with shipFriendlyFire false
 * (CustomCrew.cpp DamageArea hook) damage on the owner's own ship only reaches the crew of the room.
 */
fun HsCrew.damageArea(ship: Ship, room: xyz.znix.xftl.layout.Room, dmg: Damage, shipFriendlyFire: Boolean) {
    if (!shipFriendlyFire && dmg.ownerShip === ship) {
        dmg.hullDamage = 0; dmg.ionDamage = 0; dmg.pureSysDamage = 0; dmg.noSysDamage = true; dmg.noCrewDamage = true
        dmg.fireChance = 0; dmg.breachChance = 0; dmg.emptyRoomBonus = 0
        ship.crewWeaponDamage(room, dmg.effectiveCrewDamage.toFloat(), dmg)
    } else if (dmg.hullDamage != 0 || dmg.ionDamage != 0 || dmg.pureSysDamage != 0 || dmg.pureCrewDamage != 0 ||
        dmg.fireChance != 0 || dmg.breachChance != 0 || dmg.stunDuration != 0f || dmg.stunChance != 0 || dmg.lockdown
    ) {
        ship.damage(room, dmg)
    }
}

/**
 * ShipManager::UpdateCrewMembers (CustomCrew.cpp) after the crew loop: an activated power's damage
 * (ActivatedPower::GetPowerDamage: owner = power owner, selfId = the user's) hits the room at the effect position on
 * the power's ship, once (powerActivated is cleared).
 */
fun HsCrew.powerDamageArea(p: ActivatedPower) {
    if (!p.powerActivated) return
    p.powerActivated = false
    val crew = p.crew
    val current = crew.room.ship
    val ship = if (p.powerShip == shipIdOf(current)) current else shipById(crew, p.powerShip) ?: return
    val room = ship.rooms.firstOrNull { it.containsPixelInclusive(p.effectWorldPosX.toInt(), p.effectWorldPosY.toInt()) } ?: return
    val hd = p.powerDamage()
    val dmg = toDamage(hd, shipById(crew, hd.ownerId))
    dmg.hsSelfId = hd.selfId
    // DamageArea runs with currentLockdown = the power's customLockdown
    val custom = p.def.customLockdown
    if (dmg.lockdown && custom != xyz.znix.xftl.hs.ship.HsLockdowns.defaults()) {
        dmg.lockdown = false
        xyz.znix.xftl.hs.ship.HsLockdowns.lockdown(room, custom, p.effectWorldPosX, p.effectWorldPosY)
    }
    damageArea(ship, room, dmg, p.def.shipFriendlyFire)
}

/**
 * CrewMember::GetRoomDamage while dying (ShipManager::UpdateCrewmembers applies it with DamageArea at the crew
 * member's position): the death explosion and its stat boosts, spawns and event, once.
 */
fun HsCrew.deathExplosion(crew: AbstractCrew, ex: CrewExtend) {
    if (!ex.triggerExplosion || ex.exploded) return
    ex.exploded = true
    ex.calculateStat(CrewStat.DEATH_EFFECT, ex.definition())
    if (!ex.hasDeathExplosion) return
    val e = ex.deathEffectChange
    val ship = crew.room.ship
    val owner = shipById(crew, ex.powerOwner)
    damageArea(ship, crew.room, toDamage(e.damage, owner), e.shipFriendlyFire)
    if (e.statBoosts.isNotEmpty()) {
        for (id in 0..1) {
            val s = shipById(crew, id) ?: continue
            for (other in s.crew.toList()) for (sd in e.statBoosts) {
                val b = StatBoost(sd)
                b.crewSource = crew
                b.crewSourceId = ex.selfId
                b.sourceShipId = ex.shipId
                StatBoostManager.createTimedAugmentBoost(b, other)
            }
        }
    }
    for (sd in e.roomStatBoosts) StatBoostManager.createRoomStatBoost(sd, crew.room, 1, crew)
    if (e.crewSpawns.isNotEmpty()) {
        val c = crew.getPixelPositionCentre()
        for (s in e.crewSpawns) spawnCrew(s, ship, ex.currentShipId != ex.shipId, c.x.toFloat(), c.y.toFloat())
    }
    val shipId = ex.shipId
    if (e.event[shipId].isNotEmpty()) queueEvent(e.event[shipId], e.eventNodes[shipId])
}

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CrewSpawn.cpp
/**
 * CrewSpawn::SpawnCrew: [spawn] crew of a race appear at a point of [ship] (as intruders when [intruderIn]), limited
 * by the free slots of that side on the whole ship. Each new crew member stands at the point and walks to its slot:
 * the one it gets in the room of the point (ShipManager::AddCrewMemberFromString), or with [tile] (beam hits) the
 * free slot nearest the point (ShipGraph::GetClosestSlot; here: the nearest free slot of that room, else the
 * room's usual placement). They get the spawn's health fraction, lifetime and stat boosts.
 */
fun HsCrew.spawnCrew(spawn: CrewSpawn, ship: Ship, intruderIn: Boolean, x: Float, y: Float, tile: Boolean = false): List<LivingCrew> {
    val game = ship.sys
    val room = ship.rooms.firstOrNull { it.containsPixelInclusive(x.toInt(), y.toInt()) } ?: return emptyList()
    val intruder = if (spawn.enemy) !intruderIn else intruderIn
    val mode = if (intruder) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW
    // Room::GetEmptySlots(intruder) summed over the ship: the slots nobody of that side stands in or walks to.
    val free = ship.rooms.sumOf { r ->
        (0 until r.cellCount).count { i -> r.slotToPoint(i).let { p -> r.isSlotFree(p, mode) } }
    }
    val n = minOf(free, spawn.number)
    val out = ArrayList<LivingCrew>()
    // The spawned crew belong to the ship they are spawned on, or to the other ship when they are intruders.
    val owner = if (intruder) game.getEnemyOf(ship) ?: (if (ship === game.player) game.enemy else game.player) else ship
    repeat(n) {
        val closest = if (tile) closestFreeSlot(room, mode, x, y) else null
        forceNoSlot = spawn.noSlot
        // ShipManager::AddCrewMemberFromString draws the gender with random32() % 2 (combat-time, BrowserRandom).
        ftl.browser.BrowserRandom.nextInt(2)
        val race = resolveRace(game.blueprintManager, spawn.race, ftl.browser.BrowserRandom)
        val pos = closest ?: secondFreeSlot(ship.findSpaceForCrew(room, mode), mode)
        val member = race.spawn(pos.room, mode)
        member.info = if (spawn.name.isNotEmpty()) LivingCrewInfo.generateWithName(race, game, spawn.name)
        else LivingCrewInfo.generateRandom(race, game)
        forceNoSlot = false
        ship.crew.add(member)
        member.ownerShip = owner
        // crew->x/y/goal = spawnPos, then MoveToRoom(currentSlot, true) when a path leads there.
        member.hsPlaceAt(room, x, y, pos, true)
        member.health = member.maxHealth * spawn.healthPercentage
        val ex = ex(member)
        if (spawn.lifetime != -1f) ex.deathTimer = HsTimer().also { it.start(spawn.lifetime) }
        for (sd in spawn.statBoosts) {
            val b = StatBoost(sd)
            b.crewSource = member
            b.crewSourceId = ex.selfId
            b.sourceShipId = ex.shipId
            StatBoostManager.createTimedAugmentBoost(b, member)
        }
        out.add(member)
    }
    game.updatePlayerCrew()
    return out
}

/**
 * The slot a non-tile spawn walks to: AddCrewMemberFromString puts the crew member in [first] (CrewMember::SetRoom:
 * the room's first empty slot), then SpawnCrew's MoveToRoom(room, that slot, true) finds the slot taken - by the crew
 * member itself - and takes the room's next empty one (CrewMember -> Room::GetEmptySlot), emptying
 * the first. Oracle sweep-crew4-damage: the snowman of a hit on the medbay (machine in slot 0) walks to slot 2. With
 * no other empty slot it keeps [first].
 */
private fun secondFreeSlot(first: xyz.znix.xftl.math.RoomPoint, mode: AbstractCrew.SlotType): xyz.znix.xftl.math.RoomPoint {
    val room = first.room
    val computer = room.system?.configuration?.computerPoint
    if (mode == AbstractCrew.SlotType.CREW && computer != null && !(computer posEq first) && room.isSlotFree(computer, mode))
        return xyz.znix.xftl.math.RoomPoint(room, computer)
    for (i in 0 until room.cellCount) {
        val p = room.slotToPoint(i)
        if (!(p posEq first) && room.isSlotFree(p, mode)) return xyz.znix.xftl.math.RoomPoint(room, p)
    }
    return first
}

/** The free slot of [room] whose centre is nearest (x, y) (first in slot order on a tie), or null when it is full. */
private fun closestFreeSlot(room: xyz.znix.xftl.layout.Room, mode: AbstractCrew.SlotType, x: Float, y: Float): xyz.znix.xftl.math.RoomPoint? {
    var best: xyz.znix.xftl.math.RoomPoint? = null
    var bestD = Float.MAX_VALUE
    for (i in 0 until room.cellCount) {
        val p = room.slotToPoint(i)
        if (!room.isSlotFree(p, mode)) continue
        val cx = room.offsetX + p.x * 35 + 17.5f - x
        val cy = room.offsetY + p.y * 35 + 17.5f - y
        val d = cx * cx + cy * cy
        if (d < bestD) { bestD = d; best = xyz.znix.xftl.math.RoomPoint(room, p) }
    }
    return best
}

/**
 * ShipManager::OnLoop (CustomCrew.cpp): each functional HS crew member in a system room other than piloting adds
 * POWER_DRAIN to that system's additionalPowerLoss when it is an intruder or has POWER_DRAIN_FRIENDLY. HS adds it to
 * iTempPowerLoss in ShipSystem::GetMaxPower / CheckMaxPower; applying it to system power is the systems
 * workstream's (read [of]).
 */
object HsPowerDrain {
    private val additional = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.AbstractSystem, Int>())

    @JvmStatic
    fun of(sys: xyz.znix.xftl.AbstractSystem): Int = additional[sys] ?: 0

    @JvmStatic
    fun update(ship: Ship) {
        for (s in ship.systems) additional.remove(s)
        for (c in ship.crew) {
            if (!CustomCrewManager.isRace(c.codename)) continue
            val ex = HsCrew.ex(c)
            val def = ex.definition()
            val drain = if (ex.functional) ex.calculateStat(CrewStat.POWER_DRAIN, def).toInt() else 0
            val sys = c.room.system ?: continue
            if (sys is xyz.znix.xftl.systems.Piloting) continue
            if (ex.intruder || ex.calculateBool(CrewStat.POWER_DRAIN_FRIENDLY, def)) additional[sys] = (additional[sys] ?: 0) + drain
        }
    }
}

/**
 * CrewMember::fMedbay as the native ShipManager sets it before the crew loop: the medbay's power for a friendly
 * crew member in the medbay room (-2 while the medbay is hacked), the nano medbay augment's value elsewhere on a
 * ship with a working medbay, else 0; plus HS's 1e-10 when an HS heal stat healed this frame (CustomCrew.cpp
 * CrewMember::UpdateHealth, which MV's instant-clone-heal.lua reads as "being healed"). Our medbay heals through
 * its own update (systems/Medbay.kt), so this is the value scripts read; writing it changes nothing.
 */
fun HsCrew.medbayRate(c: AbstractCrew): Float {
    val ex = c.hsExtend
    // HS crew: the value their loop stored (0 before the first loop, as for a crew member added this frame)
    var rate = if (ex != null && c is HsCrewMember) maxOf(ex.medbayRate, 0f) else medbayBase(c)
    if (ex?.healMark == true) rate += 0.0000000001f
    return rate
}

/**
 * CrewMember::UpdateHealth's heal factor for a crew drone (CustomCrew.cpp): HEAL_SPEED_MULTIPLIER when HS defines its
 * race, else CrewDrone's 0.2 (MV's roomba heals 6.4 hp/s in its drone room: oracle sweep-crew4-droneai).
 */
fun HsCrew.droneHealFactor(c: AbstractCrew): Float {
    val ex = ex(c)
    val def = ex.definition() ?: return 0.2f
    return ex.calculateStat(CrewStat.HEAL_SPEED_MULTIPLIER, def)
}

/**
 * The native fMedbay before HS's heal mark: medbay power in the medbay (-2 hacked), the nano medbay elsewhere; for
 * a crew drone, 1 while it can heal in a working drone control room without intruders (ShipManager::UpdateCrewmembers
 * SetMedbay(1), as systems/Drones.kt heals it).
 */
fun HsCrew.medbayBase(c: AbstractCrew): Float {
    var rate = 0f
    val ship = c.room.ship
    val drones = ship.drones
    if (c !is LivingCrew && drones != null && c.room === drones.room && drones.powerSupplied > 0 &&
        c.mode == AbstractCrew.SlotType.CREW && c.health > 0f && c.health < c.maxHealth &&
        c.room.crew.none { it.mode == AbstractCrew.SlotType.INTRUDER }) return 1f
    val medbay = ship.medbay
    if (c is LivingCrew && medbay != null && c.currentAction != AbstractCrew.Action.DYING) {
        if (c.room === medbay.room) {
            if (c.mode != AbstractCrew.SlotType.INTRUDER)
                rate = if (medbay.isHackActive) -2f else medbay.powerSupplied.toFloat()
        } else if (medbay.powerSupplied > 0 && !medbay.isHackActive && c.ownerShip === ship) {
            rate = ship.getAugmentValueOrNull("NANO_MEDBAY") ?: 0f
        }
    }
    return rate
}

/**
 * ShipManager::AddCrewMemberFromString (Lua): HS's hook (CustomCrew.cpp) resolves blueprint lists with random32()
 * (BrowserRandom, as in [spawnCrew]); the native function then makes a crew member of the race with [name] (a
 * random one when empty) and the [male] sprite, placed in room [roomId] when it has space (elsewhere otherwise),
 * as an intruder of the other ship when [intruder].
 */
fun HsCrew.addCrewMemberFromString(ship: Ship, name: String, raceName: String, intruder: Boolean, roomId: Int,
                                   init: Boolean, male: Boolean): LivingCrew {
    val game = ship.sys
    val race = resolveRace(game.blueprintManager, raceName, ftl.browser.BrowserRandom)
    val factory = game.crewFactory
    val colours = factory.drawColours(race)
    val crewName = if (name.isNotEmpty()) name else factory.newName().name
    val info = LivingCrewInfo(game, race, crewName, factory.colourIndex(race, colours, male))
    val room = ship.rooms.getOrNull(roomId)
    if (!intruder) {
        return if (room != null) ship.addCrewMemberInRoom(info, room, false) else ship.addCrewMember(info, init)
    }
    val mode = AbstractCrew.SlotType.INTRUDER
    val pos = ship.findSpaceForCrew(room, mode)
    val member = race.spawn(pos.room, mode)
    member.info = info
    ship.crew.add(member)
    member.jumpTo(pos)
    member.ownerShip = game.getEnemyOf(ship) ?: (if (ship === game.player) game.enemy else game.player)
    game.updatePlayerCrew()
    return member
}

/**
 * Ship::OnLoop hook (CustomCrew.cpp, HACK_DOORS): every functional HS crew member aboard [ship] in a room whose
 * HACK_DOORS stat is true counts +1 (intruder) or -1 (own crew) on each door of its room, airlocks included; a door
 * with a positive balance is hacked, one with a negative balance is freed (Door.update applies it after the native
 * per-frame hack state).
 */
object HsHackDoors {
    @JvmStatic
    fun apply(ship: Ship) {
        for (door in ship.doors) door.hsHackDoors = 0
        for (c in ship.crew) {
            if (!CustomCrewManager.isRace(c.codename)) continue
            val ex = HsCrew.ex(c)
            if (!ex.functional) continue
            if (!ex.calculateBool(CrewStat.HACK_DOORS, ex.definition())) continue
            val room = c.room
            for (door in ship.doors) {
                if (door.left === room || door.right === room) door.hsHackDoors += if (ex.intruder) 1 else -1
            }
        }
    }
}
