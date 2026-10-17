package xyz.znix.xftl.augments

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.MedbayHealing
import xyz.znix.xftl.layout.BreachInstance

/**
 * The base augment class, which is suitable for augments which are
 * implemented as part of another system (for example, reconstructive
 * teleport is implemented by the teleporter).
 *
 * In general, it's probably a good idea to implement augments here
 * to show likely pain points for modding.
 */
open class AugmentBlueprint(elem: Element) : Blueprint(elem) {
    override val cost: Int = elem.getChildTextTrim("cost").toInt()
    val stackable: Boolean = elem.getChildTextTrim("stackable")!!.toBoolean()
    val value: Float = elem.getChildTextTrim("value")?.toFloat() ?: 1f

    open fun update(ship: Ship, dt: Float, totalValue: Float) {}

    open fun onJump(ship: Ship) {}

    /**
     * Called when a ship which comes with this augment is created.
     */
    open fun onShipSpawn(ship: Ship) {}

    companion object {
        const val AUTOMATED_RELOADERS: String = "AUTO_COOLDOWN"
        const val LONG_RANGE_SCANNERS: String = "ADV_SCANNERS"
        const val ADVANCED_FTL_NAVIGATION: String = "FTL_JUMPER"
        const val RECONSTRUCTIVE_TELEPORT: String = "TELEPORT_HEAL"
        const val OXYGEN_MASKS: String = "O2_MASKS"
        const val BACKUP_DNA: String = "BACKUP_DNA"
        const val BATTERY_CHARGER: String = "BATTERY_BOOSTER"
        const val SHIELD_CHARGE_BOOSTER: String = "SHIELD_RECHARGE"
        const val STEALTH_WEAPONS: String = "CLOAK_FIRE"
        const val ROCK_ARMOR: String = "ROCK_ARMOR"
        const val SLUG_GEL: String = "SLUG_GEL"
        const val CREW_STIMS: String = "CREW_STIMS"
        const val SYSTEM_CASING: String = "SYSTEM_CASING"
        const val CRYSTAL_SHARDS: String = "CRYSTAL_SHARDS"
        const val DRONE_SPEED: String = "DRONE_SPEED"
        const val SCRAP_COLLECTOR: String = "SCRAP_COLLECTOR"
        const val ION_ARMOR: String = "ION_ARMOR"
        const val FTL_BOOSTER: String = "FTL_BOOSTER"
        const val REPAIR_ARM: String = "REPAIR_ARM"
        const val HULL_ARMOR: String = "HULL_ARMOR"
        const val ADV_HULL_ARMOR: String = "ADV_HULL_ARMOR"
        const val EXPLOSIVE_REPLICATOR: String = "EXPLOSIVE_REPLICATOR"
        const val FTL_JAMMER: String = "FTL_JAMMER"

        /** The Zoltan shield augment: five points of super shield. */
        const val ENERGY_SHIELD_NAME: String = "ENERGY_SHIELD"

        // Advanced Edition augments. Their effects live with the systems
        // they change; Fire Suppression has its own class.
        const val DEFENSE_SCRAMBLER: String = "DEFENSE_SCRAMBLER"
        const val DRONE_RECOVERY: String = "DRONE_RECOVERY"
        const val FLEET_DISTRACTION: String = "FLEET_DISTRACTION"
        const val HACKING_STUN: String = "HACKING_STUN"
        const val ZOLTAN_BYPASS: String = "ZOLTAN_BYPASS"

        /**
         * Lifeform Scanner: senses the crew aboard enemy ships through the
         * hull, the same way a living Slug does (ShipManager::OnRender crew
         * pass, 0x4a3c00, tests the "slug" crew race and LIFE_SCANNER
         * together). The visibility lives in Ship.crewVisibleThroughHull; the
         * augment itself has no per-frame behaviour (value 0.0, stackable
         * false).
         */
        const val LIFE_SCANNER: String = "LIFE_SCANNER"

        // Quest prop - granted and traded by events, no passive effect.
        const val STASIS_POD: String = "STASIS_POD"
    }
}

/**
 * Nano-Medbots (NANO_MEDBAY). The medbay's heal rate query (0x63f750) gives
 * crew outside the medbay the augment's value (0.25) and the one inside its
 * power level; ShipManager::OnLoop only asks while the medbay functions, and
 * a hacked medbay answers 0 outside the room (and drains inside it).
 */
class AugEngiMedbots(elem: Element) : AugmentBlueprint(elem) {
    override fun update(ship: Ship, dt: Float, totalValue: Float) {
        super.update(ship, dt, totalValue)

        val medbay = ship.medbay ?: return
        if (medbay.powerSupplied == 0 || medbay.isHackActive)
            return

        val healing = dt * 6.4f * totalValue

        for (crew in ship.friendlyCrew) {
            if (crew !is LivingCrew)
                continue

            // Medbots don't add extra healing in the medbay
            if (crew.room == medbay.room)
                continue

            crew.dealDamage(MedbayHealing(-healing))
        }
    }

    companion object {
        const val NAME = "NANO_MEDBAY"
    }
}

/**
 * Fire Suppression (FIRE_EXTINGUISHERS). ShipManager::OnLoop (0x4abd40)
 * repairs every burning square at the augment's value (1.0), the same as a
 * crewmember with repair speed 1 and no race fire bonus: 0.08 of a fire's
 * health per second on the engine's scale.
 */
class AugFireExtinguishers(elem: Element) : AugmentBlueprint(elem) {
    override fun update(ship: Ship, dt: Float, totalValue: Float) {
        super.update(ship, dt, totalValue)

        for (room in ship.rooms) {
            for (fire in room.fires) {
                if (fire != null && fire.health > 0f)
                    fire.health -= 0.08f * totalValue * dt
            }
        }
    }

    companion object {
        const val NAME = "FIRE_EXTINGUISHERS"
    }
}

class AugPreigniter(elem: Element) : AugmentBlueprint(elem) {
    override fun onJump(ship: Ship) {
        super.onJump(ship)

        for (hp in ship.hardpoints) {
            val weapon = hp.weapon ?: continue

            // We don't charge up unpowered weapons
            if (!weapon.isPowered)
                continue

            weapon.timeCharged = weapon.type.chargeTime
        }
    }

    companion object {
        const val NAME: String = "WEAPON_PREIGNITE"
    }
}

class AugZoltanShield(elem: Element) : AugmentBlueprint(elem) {
    override fun onJump(ship: Ship) {
        super.onJump(ship)
        ship.superShield = 5
    }

    // This is required so that enemy ships start with a super shield.
    override fun onShipSpawn(ship: Ship) {
        super.onShipSpawn(ship)
        ship.superShield = 5
    }

    companion object {
        const val NAME: String = "ENERGY_SHIELD"
    }
}

class AugSlugGel(elem: Element) : AugmentBlueprint(elem) {
    override fun update(ship: Ship, dt: Float, totalValue: Float) {
        super.update(ship, dt, totalValue)

        // Slug Repair Gel: OuterHull::OnLoop (0x63f490) calls
        // PartialRepair(0.75) on every breach of a ship with SLUG_GEL, so
        // all breaches seal at once at 75% of a crewmember's speed,
        // stacking with crew repairs. The dat's value (0.25) is unused.
        val rate = GEL_CREW_SPEED_FRACTION * BreachInstance.CREW_REPAIR_RATE * dt
        if (rate <= 0f)
            return

        for (room in ship.rooms) {
            for ((slot, breach) in room.breaches.withIndex()) {
                val b = breach ?: continue
                b.health -= rate

                // Note: no onFinishedBreachRepair here - that rewards the
                // CREW that do the repairing, and the gel has none.
                if (b.health == 0f)
                    room.breaches[slot] = null
            }
        }
    }

    companion object {
        const val NAME: String = "SLUG_GEL"

        // Vanilla gel speed as a fraction of crew breach repair.
        const val GEL_CREW_SPEED_FRACTION = 0.75f
    }
}
