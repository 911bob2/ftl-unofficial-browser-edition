package xyz.znix.xftl.weapons

import xyz.znix.xftl.Ship

/**
 * Represents some amount of damage which is applied to the ship.
 */
class Damage() {
    /**
     * Create a [Damage] instance from the properties of a weapon.
     */
    constructor(type: AbstractWeaponBlueprint) : this() {
        this.hullDamage = type.damage
        this.ionDamage = type.ionDamage
        this.pureSysDamage = type.sysDamage
        this.pureCrewDamage = type.personnelDamage * 15
        this.emptyRoomBonus = type.hullBust

        this.fireChance = type.fireChance * 10
        this.breachChance = type.breachChance * 10
        this.stunChance = type.stunChance * 10
        this.stunDuration = type.stun.toFloat()

        this.lockdown = type.browserLockdown
        this.noSysDamage = type.noSysDamage
        this.noCrewDamage = type.noPersonnelDamage
    }

    /**
     * The base amount of damage this weapon does.
     */
    var hullDamage: Int = 0

    var ionDamage: Int = 0

    // The reason why pureSysDamage and pureCrewDamage are required is that
    // some stuff specifically uses them, so we need to track them to properly
    // match up with vanilla.

    /**
     * The amount of system damage this weapon does, excluding the
     * contribution from the hull damage.
     */
    var pureSysDamage: Int = 0

    /**
     * The amount of crew damage this weapon does, excluding the
     * contribution from the hull damage.
     *
     * This is in hp, not units of 15 hp that the weapon XML uses.
     */
    var pureCrewDamage: Int = 0

    // Note that negative hull damage doesn't mean negative sys/pers damage!
    val effectiveSysDamage: Int
        get() = when (noSysDamage) {
            true -> pureSysDamage
            false -> pureSysDamage + hullDamage.coerceAtLeast(0)
        }

    val effectiveCrewDamage: Int
        get() = when (noCrewDamage) {
            true -> pureCrewDamage
            false -> pureCrewDamage + hullDamage.coerceAtLeast(0) * 15
        }

    /**
     * The additional hull damage that's dealt against empty rooms.
     */
    var emptyRoomBonus: Int = 0

    // The fire/breach/stun chances, in percent
    var fireChance: Int = 0
    var breachChance: Int = 0
    var stunChance: Int = 0

    /**
     * The seconds every crew member hit is stunned for, always (the weapon's stun tag, Damage.iStun).
     * [stunChance] is separate: it stuns for three seconds, in the Advanced Edition only.
     */
    var stunDuration: Float = 0f

    // When these are set, hullDamage isn't automatically added to
    // the system and crew damage.
    var noSysDamage: Boolean = false
    var noCrewDamage: Boolean = false

    var lockdown: Boolean = false

    /**
     * The ship whose weapon dealt this damage (Damage.ownerId, native struct offset 0x24), or null for damage that
     * belongs to nobody (a hazard). ShipManager::DamageCrew (0x49cf80) skips every crew member whose own ship is
     * this one, unless [friendlyFire] is set.
     */
    var ownerShip: Ship? = null

    /**
     * Damage.bFriendlyFire (native struct offset 0x2e): hurt crew of the [ownerShip] too. Only the anti-ship battery
     * fire sets it (PDSFire, 0x4599d1).
     */
    var friendlyFire: Boolean = false

    /**
     * Apply the effect of a weapon that uses damage-chaining.
     */
    fun applyWeaponChaining(chainDamage: Int) {
        if (chainDamage <= 0)
            return

        when {
            hullDamage > 0 -> hullDamage += chainDamage
            ionDamage > 0 -> ionDamage += chainDamage
            pureCrewDamage > 0 -> pureCrewDamage += chainDamage
            pureSysDamage > 0 -> pureSysDamage += chainDamage
        }
    }

    /**
     * Returns a copy of this damage, with everything except the ion and stun
     * components removed.
     */
    fun copyIon(): Damage {
        val result = Damage()
        result.ionDamage = ionDamage
        result.stunChance = stunChance
        result.stunDuration = stunDuration
        result.ownerShip = ownerShip
        result.friendlyFire = friendlyFire
        return result
    }

    /**
     * Returns a copy of this damage, with everything except the ion, stun
     * and system components removed.
     *
     * This uses [pureSysDamage], not [effectiveSysDamage]. Thus the system
     * damage caused by this function's result is not necessarily the same
     * as the damage caused by this object.
     *
     * This function is used when applying ion damage that hits the shields
     * of a ship.
     */
    fun copyIonAndSys(): Damage {
        val result = copyIon()
        result.pureSysDamage = pureSysDamage
        return result
    }

    /**
     * Returns an identical copy of this object.
     */
    fun copy(): Damage {
        val result = Damage()

        result.hullDamage = hullDamage
        result.ionDamage = ionDamage
        result.pureSysDamage = pureSysDamage
        result.pureCrewDamage = pureCrewDamage
        result.emptyRoomBonus = emptyRoomBonus

        result.fireChance = fireChance
        result.breachChance = breachChance
        result.stunChance = stunChance
        result.stunDuration = stunDuration

        result.lockdown = lockdown
        result.noSysDamage = noSysDamage
        result.noCrewDamage = noCrewDamage

        result.ownerShip = ownerShip
        result.friendlyFire = friendlyFire

        return result
    }

    companion object {
        @JvmStatic
        fun hullOnly(amount: Int): Damage {
            val damage = Damage()
            damage.hullDamage = amount
            damage.noSysDamage = true
            damage.noCrewDamage = true
            return damage
        }

        @JvmStatic
        fun hullAndSys(amount: Int): Damage {
            val damage = Damage()
            damage.hullDamage = amount
            damage.noCrewDamage = true
            return damage
        }

        @JvmStatic
        fun regular(amount: Int): Damage {
            val damage = Damage()
            damage.hullDamage = amount
            return damage
        }
    }
}
