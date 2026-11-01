// Collideable::DamageBeam for a ship.
// Vanilla: native ShipManager::DamageBeam 1.6.14 0x4a9d40 (DamageSystem 0x4a4ab0, hull strike 0x48c750,
// TranslateToGrid 0x464d30, grid Valid 0x464f00, ResistDamage 0x49c770, CheckCrystalAugment 0x4a5df0).
// Hyperspace (GPL-3.0-or-later, 1b05a07): CustomCrew.cpp4276-4425 replaces the native body (priority 9990),
// CustomDamage.cpp139-283 wraps it (DAMAGE_BEAM and the custom-damage effects), and CustomDamage.cpp299
// wraps DamageSystem (DAMAGE_SYSTEM, noSysDamage) over native 1.6.9 ShipManager::DamageSystem 0x4991d0.
package xyz.znix.xftl.weapons

import ftl.browser.BrowserRandom
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.weapons.HsDamage
import xyz.znix.xftl.hs.weapons.HsWeaponsLua
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour

object BeamDamageRules {
    const val SAME_TILE = 0
    const val NEW_TILE = 1
    const val NEW_ROOM = 2

    /** ShipGraph::TranslateToGrid: (int) truncation, then truncating integer division (not floor). */
    fun grid(p: FPoint): ConstPoint = ConstPoint(p.xf.toInt() / 35, p.yf.toInt() / 35)

    /**
     * ShipManager::GetSelectedRoomId(x, y, true) with (int) coordinates: ShipGraph::GetSelectedRoom (1.6.14
     * 0x464ab0) returns the first room in id order whose Room::Contains (0x476430) accepts the point, and with
     * walls included that test is inclusive on all four edges.
     */
    fun roomAt(ship: Ship, p: FPoint): Room? {
        val x = p.xf.toInt()
        val y = p.yf.toInt()
        return ship.rooms.firstOrNull {
            x >= it.offsetX && x <= it.offsetX + it.pixelWidth && y >= it.offsetY && y <= it.offsetY + it.pixelHeight
        }
    }

    /** ShipGraph::Valid: unsigned comparison against the 40x40 grid. */
    private fun valid(g: IPoint) = g.x in 0..39 && g.y in 0..39

    /**
     * Collideable::DamageBeam(current, last, Damage) on a ship. [damage] is passed by value: the callee works on a
     * copy, so a DAMAGE_BEAM callback's edits never reach the caller's object. The result is the native return
     * (vanilla: true on a new room of a hostile ship; Hyperspace: false unless DAMAGE_BEAM pre-empts a NEW_ROOM,
     * because CustomCrew.cpp4300 shadows the returned variable inside its room block).
     */
    fun damageBeam(ship: Ship, current: FPoint, last: FPoint, damage: Damage): Boolean =
        if (HS.active) hsDamageBeam(ship, current, last, damage.copy()) else body(ship, current, last, damage.copy(), false)

    /** CustomDamage.cpp139: DAMAGE_BEAM may change the hit type (moving `last`) or pre-empt the whole operation. */
    private fun hsDamageBeam(ship: Ship, current: FPoint, last: FPoint, dmg: Damage): Boolean {
        val grid1 = grid(current)
        val grid2 = grid(last)
        val room1 = roomAt(ship, current)
        val room2 = roomAt(ship, last)
        val realNewTile = grid1 != grid2
        val oldType = when {
            room1 !== room2 -> NEW_ROOM
            realNewTile -> NEW_TILE
            else -> SAME_TILE
        }
        var type = oldType
        // The callbacks receive pointers to this hook's own location1 and Damage copies.
        val location1 = MutFPoint(current)
        val location2 = MutFPoint(last)
        if (HS.luaActive) {
            type = xyz.znix.xftl.hs.lua.LuaAdaptersBeam.damageBeamEvent(ship, HsDamage.currentProjectile,
                location1, dmg, realNewTile, oldType)
            if (xyz.znix.xftl.hs.lua.LuaAdaptersBeam.damageBeamPreempted) return type == NEW_ROOM
            if (type != oldType) when (type) {
                NEW_ROOM -> location2.set(-2147483648f, -2147483648f)
                NEW_TILE -> if (room1 != null) {
                    val x = grid1.x * 35
                    val y = grid1.y * 35
                    if (x == room1.offsetX && y == room1.offsetY) { // location1 is the room's first tile
                        when {
                            room1.pixelWidth > 35 -> location2.set(x + 52f, y + 17f)
                            room1.pixelHeight > 35 -> location2.set(x + 17f, y + 52f)
                            else -> location2.set(location1) // one-tile room: fall back to SAME_TILE
                        }
                    } else location2.set(x + 17f, y + 17f)
                }
                SAME_TILE -> location2.set(location1)
                else -> type = oldType
            }
        }
        hullResist(ship, location1, location2, dmg)
        val result = body(ship, location1, location2, dmg, true)
        // CustomDamage.cpp240: crew spawns on a new tile or room, room stat boosts / erosion on a new room,
        // from the original location's grid and room.
        if (type != SAME_TILE) HsDamage.beamHit(ship, room1, ConstPoint(grid1.x * 35 + 17, grid1.y * 35 + 17),
            type == NEW_ROOM, HsDamage.current?.sourceShipId ?: -1)
        return result
    }

    /**
     * CustomShips.cpp ShipManager::DamageBeam (Room_Extend hullDamageResistChance, from the ship definition's
     * `<rooms><room id>`, on the room's Room_Extend [xyz.znix.xftl.hs.systems.HsRoomSys]): a hull-damaging beam entering a new
     * room rolls random32() % 100 against the chance of location2's room (else location1's); on a resist the hull
     * damage becomes system and crew damage and location1's room shows RESIST.
     */
    private fun hullResist(ship: Ship, location1: FPoint, location2: FPoint, dmg: Damage) {
        val room1 = roomAt(ship, location1)
        val room2 = roomAt(ship, location2)
        if (dmg.hullDamage <= 0 || room1 === room2 || (room1 == null && room2 == null)) return
        val room = room2 ?: room1!!
        if (hsHullResist(room, dmg) && room1 != null) ship.showDamageTextAt(room1.pixelCentre, "text_resist", Colour.white)
    }

    /** The hull resist roll of [room] (random32() % 100 < hullDamageResistChance): hull damage becomes system and
     * crew damage. The callers check iDamage > 0 first, as HS does before rolling. */
    @JvmStatic
    fun hsHullResist(room: Room, dmg: Damage): Boolean {
        val chance = xyz.znix.xftl.hs.systems.HsTemporal.roomState(room).hullDamageResistChance
        if (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 100 >= chance) return false
        dmg.pureSysDamage += dmg.hullDamage
        dmg.pureCrewDamage += dmg.hullDamage * 15
        dmg.hullDamage = 0
        return true
    }

    /**
     * CustomShips.cpp ShipManager::DamageSystem: the room's sysDamageResistChance (iSystemDamage > -iDamage: the
     * system damage becomes -iDamage, none in effect, white RESIST) and ionDamageResistChance (ion damage 0, RESIST
     * in 40/210/230); both rolls are always made, before the native body.
     */
    @JvmStatic
    fun hsSystemResist(ship: Ship, room: Room, dmg: Damage) {
        val ext = xyz.znix.xftl.hs.systems.HsTemporal.roomState(room)
        if (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 100 < ext.sysDamageResistChance && dmg.pureSysDamage > -dmg.hullDamage) {
            dmg.pureSysDamage = -dmg.hullDamage
            ship.showDamageTextAt(room.pixelCentre, "text_resist", Colour.white)
        }
        if (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 100 < ext.ionDamageResistChance && dmg.ionDamage > 0) {
            dmg.ionDamage = 0
            ship.showDamageTextAt(room.pixelCentre, "text_resist", Colour(40, 210, 230))
        }
    }

    /** The native body (vanilla 1.6.14) and its Hyperspace rewrite; they differ only where [hs] is tested. */
    private fun body(ship: Ship, current: FPoint, last: FPoint, damage: Damage, hs: Boolean): Boolean {
        if (!hostile(ship) || ship.browserJumping) return false
        val grid = grid(current)
        val lastGrid = grid(last)
        val room = roomAt(ship, current)
        val lastRoom = roomAt(ship, last)
        if (!valid(grid) || room == null) return false

        val resistHull = ship.rollRockArmour()
        var result = false
        if (room !== lastRoom) {
            val system = room.system
            // Titanium System Casing. Native also requires a system that isn't completely destroyed; the HS
            // rewrite only tests for a system (and rolls only then).
            val casing = if (hs) system != null && resistDamage(ship, AugmentBlueprint.SYSTEM_CASING) &&
                (damage.pureSysDamage > 0 || damage.hullDamage > 0)
            else {
                val destroyed = system != null && system.damagedEnergyLevels >= system.energyLevels
                val roll = resistDamage(ship, AugmentBlueprint.SYSTEM_CASING)
                system != null && roll && !destroyed && (damage.hullDamage > 0 || damage.pureSysDamage > 0)
            }
            val systemDamage = damage.copy()
            if (casing) {
                systemDamage.pureSysDamage = 0
                systemDamage.hullDamage = 0
            }
            // DamageSystem runs before the hull strike, also for a room without a system.
            damageSystem(ship, room, systemDamage)

            val hullDamage = if (system == null && damage.emptyRoomBonus != 0) damage.hullDamage * 2 else damage.hullDamage
            val textPos = ConstPoint(current.xf.toInt(), current.yf.toInt())
            if (!resistHull || hullDamage <= 0) {
                strikeHull(ship, hullDamage)
                ship.nativeLastDamage = hullDamage
                if (hullDamage > 0) ship.checkCrystalAugment(textPos)
                if (casing) ship.showDamageTextAt(textPos, "text_resist", Colour.white)
                else if (hullDamage > 0) ship.showDamageTextAt(textPos, hullDamage, Colour.white)
            } else {
                strikeHull(ship, 0)
                ship.nativeLastDamage = 0
                ship.showDamageTextAt(textPos, "text_resist", Colour.white)
            }
            // ShipManager::hitByBeam[room] = the unpaused time (the Slice and Dice achievement reads it).
            ship.sys.browserAchievements.beamRoomHit(room)
            result = !hs
        }

        if (grid != lastGrid) {
            // FireSpreader::StartInGrid / Ship::BreachSpecificHull act on the grid cell. With inclusive room edges
            // the cell can belong to a neighbouring room (or to none, then nothing happens).
            val cell = ConstPoint(grid.x - ship.offset.x, grid.y - ship.offset.y)
            val cellRoom = ship.rooms.firstOrNull { it.containsAbsolute(cell) }
            val slot = cellRoom?.pointToSlot(cell - cellRoom.position) ?: -1
            // random32() % 10 < chance (chances in tenths); the combat stream keeps only the odds.
            if (BrowserRandom.nextInt(10) < damage.fireChance / 10 && cellRoom != null) cellRoom.spawnFireAt(slot)
            val breachChance = damage.breachChance / 10
            if (BrowserRandom.nextInt(10) < breachChance && (!resistHull || breachChance == 10) && cellRoom != null)
                cellRoom.spawnBreachAt(slot)
            for (crew in ship.crew.toList()) {
                if (grid != ConstPoint(crew.hsCentreX.toInt() / 35, crew.hsCentreY.toInt() / 35)) continue
                if (!ship.damageCrewMember(crew, damage.effectiveCrewDamage.toFloat(), damage)) continue
                // killedByBeam[damage.selfId]: three kills by one beam, without hull damage, earn ACH_SLUG_BIO.
                val kills = (ship.nativeBeamKills[damage.hsSelfId] ?: 0) + 1
                ship.nativeBeamKills[damage.hsSelfId] = kills
                if (kills >= 3 && damage.hullDamage == 0 && damage.pureCrewDamage > 0)
                    ship.sys.browserAchievements.beamCrewKilled(kills)
            }
        }
        return result
    }

    /** Targetable.hostile: the player's ship always; an enemy while it is hostile. */
    private fun hostile(ship: Ship) = ship.isPlayerShip || ship.sys.isEnemyHostile

    /** ShipManager::ResistDamage(augment): no roll without the augment, else rand%100+1 <= value*100. */
    private fun resistDamage(ship: Ship, augment: String): Boolean {
        val value = ship.getAugmentValueOrNull(augment) ?: return false
        return BrowserRandom.nextInt(100) + 1 <= (value * 100).toInt()
    }

    /** Ship::... 0x48c750: hull -= damage for a valid room (no clamp natively; the engine clamps hull to 0..max). */
    private fun strikeHull(ship: Ship, damage: Int) {
        if (ship.sys.debugFlags.noDmg.set || damage == 0) return
        ship.health -= damage
        if (ship.sys.player === ship) ship.sys.shakeForHullDamage(damage)
    }

    /**
     * ShipManager::DamageSystem(room, Damage) with Hyperspace's wrappers: DAMAGE_SYSTEM may pre-empt, then the
     * custom noSysDamage subtracts the hull damage. Native 1.6.9 0x4991d0 / 1.6.14 0x4a4ab0: Reverse Ion Field
     * rolls before the system lookup, then every system in the room takes iSystemDamage + max(0, iDamage) and the
     * ion damage, and shows the ion and pure system damage numbers at the room's centre.
     */
    fun damageSystem(ship: Ship, room: Room, incoming: Damage) {
        val damage = incoming.copy()
        var sysDamage = damage.pureSysDamage
        if (HS.active) {
            if (HS.luaActive && HsWeaponsLua.damageSystem(ship, room.id, damage)) return
            hsSystemResist(ship, room, damage)
            sysDamage = damage.pureSysDamage
            if (HsDamage.current?.def?.noSysDamage == true) sysDamage -= damage.hullDamage
        }
        var ion = damage.ionDamage
        if (ion > 0) {
            val armour = ship.getAugmentValueOrNull(AugmentBlueprint.ION_ARMOR)
            if (armour != null && BrowserRandom.nextInt(10) + 1 <= (armour * 10f).toInt()) {
                room.showDamageText("text_resist", Constants.DAMAGE_COLOUR_ION)
                ion = 0
            }
        }
        val system = room.system ?: return
        if (ship.sys.debugFlags.noDmg.set) return
        system.dealDamage(sysDamage + maxOf(0, damage.hullDamage), ion)
        if (ion > 0) room.showDamageText(ion, Constants.DAMAGE_COLOUR_ION)
        if (sysDamage > 0) room.showDamageText(sysDamage, Constants.DAMAGE_COLOUR_SYSTEM)
    }
}
