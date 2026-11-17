package xyz.znix.xftl.game

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.BombBlueprint
import xyz.znix.xftl.weapons.FlakBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint

/**
 * Game moments a player can feel, reported to the Android app, which decides what vibrates and how (its Settings >
 * Haptics; docs/ANDROID.md). The original has no haptics, so none of this follows it.
 *
 * It only reports: nothing here reads a random number, changes a value or takes time, and with [on] false (the browser
 * build always, and the app when no game moment is switched on) every call returns at its first line. The calls sit
 * where the game already decides the moment, mostly beside the sound it plays for it.
 *
 * The numbers are the app's keys for its catalogue (Haptics.Moment): never reuse or renumber one.
 */
object GameHaptics {
    /** Set by the Android app when a game starts with any game moment switched on. */
    @JvmField
    var on = false

    // Your ship
    const val HULL_HIT = 100
    const val SHIELD_HIT = 101
    const val SHIELD_DOWN = 102
    const val SHIELD_UP = 103
    const val SYSTEM_DAMAGED = 104
    const val SYSTEM_DESTROYED = 105
    const val SYSTEM_REPAIRED = 106
    const val SYSTEM_IONISED = 107
    const val BREACH = 108
    const val FIRE = 109
    const val WARN_HULL = 110
    const val WARN_OXYGEN = 111
    const val SHIP_DESTROYED = 112

    // Your weapons
    const val FIRE_LASER = 120
    const val FIRE_ION = 121
    const val FIRE_MISSILE = 122
    const val FIRE_BEAM = 123
    const val FIRE_BOMB = 124
    const val FIRE_FLAK = 125
    const val WEAPON_CHARGED = 126
    const val SHOT_MISSED = 127
    const val SHOT_INTERCEPTED = 128

    // Defence and drones
    const val DODGED = 130
    const val INTERCEPTED = 131
    const val DRONE_LAUNCHED = 132
    const val DRONE_DESTROYED = 133
    const val CLOAK_ON = 134
    const val CLOAK_OFF = 135

    // Crew and boarding
    const val CREW_HURT = 140
    const val CREW_DIED = 141
    const val CREW_CLONED = 142
    const val INTRUDERS = 143
    const val TELEPORT = 144
    const val MIND_CONTROL = 145
    const val MIND_CONTROLLED = 146
    const val HACK = 147
    const val HACKED = 148

    // The enemy ship
    const val ENEMY_HULL_HIT = 150
    const val ENEMY_SHIELD_HIT = 151
    const val ENEMY_SHIELD_DOWN = 152
    const val ENEMY_SYSTEM_DESTROYED = 153
    const val ENEMY_BREAKING = 154
    const val ENEMY_DESTROYED = 155
    const val ENEMY_SURRENDERS = 156
    const val ENEMY_ESCAPING = 157
    const val ENEMY_GONE = 158
    const val ENEMY_CREW_DIED = 159
    const val ENEMY_CLOAK = 160

    // Travel and hazards
    const val FTL_CHARGED = 170
    const val JUMP = 171
    const val ARRIVE = 172
    const val SOLAR_FLARE = 173
    const val ION_PULSE = 174
    const val LIGHTNING = 175
    const val EVENT_DAMAGE = 176
    const val DEFENCE_FIRE = 177

    /** [amount] is how much of it there was where that means something (hull points lost), else 1. */
    @JvmStatic
    fun moment(id: Int, amount: Int = 1) {
        if (on) ftl.browser.BrowserEngine.haptic(id, amount)
    }

    @JvmStatic
    fun moment(id: Int) = moment(id, 1)

    private fun pick(ship: Ship, own: Int, enemy: Int) {
        if (ship.isPlayerShip) moment(own) else if (enemy != 0) moment(enemy)
    }

    // ---- hull, shields, systems (Ship, Shields, AbstractSystem, Room) ----

    fun hullDamage(ship: Ship, points: Int) {
        if (!on || points <= 0) return
        moment(if (ship.isPlayerShip) HULL_HIT else ENEMY_HULL_HIT, points)
    }

    fun shieldHit(ship: Ship) {
        if (on) pick(ship, SHIELD_HIT, ENEMY_SHIELD_HIT)
    }

    fun shieldLayerLost(ship: Ship) {
        if (on) pick(ship, SHIELD_DOWN, ENEMY_SHIELD_DOWN)
    }

    fun shieldLayerBack(ship: Ship) {
        if (on) pick(ship, SHIELD_UP, 0)
    }

    /** After the damage is applied to a system of [ship]: [points] of system damage, [ion] of ion damage. */
    fun systemDamage(ship: Ship, broken: Boolean, points: Int, ion: Int) {
        if (!on) return
        if (points > 0) {
            if (broken) pick(ship, SYSTEM_DESTROYED, ENEMY_SYSTEM_DESTROYED)
            else pick(ship, SYSTEM_DAMAGED, 0)
        }
        if (ion > 0) pick(ship, SYSTEM_IONISED, 0)
    }

    fun systemRepaired(ship: Ship) {
        if (on) pick(ship, SYSTEM_REPAIRED, 0)
    }

    fun breach(ship: Ship) {
        if (on) pick(ship, BREACH, 0)
    }

    fun fireStarted(ship: Ship) {
        if (on) pick(ship, FIRE, 0)
    }

    /** A HUD warning began (WarningFlasher): the hull, oxygen and intruders ones are moments. */
    fun warning(sound: String?, text: String) {
        if (!on || sound == null) return
        when (text) {
            HULL_WARNING -> moment(WARN_HULL)
            OXYGEN_WARNING -> moment(WARN_OXYGEN)
            INTRUDERS_WARNING -> moment(INTRUDERS)
        }
    }

    /** The warnings' text ids, set by the HUD that owns them. */
    const val HULL_WARNING = "warning_hull_25"
    const val OXYGEN_WARNING = "warning_oxygen"
    const val INTRUDERS_WARNING = "warning_intruders"

    // ---- ships ending and leaving (Ship, ShipAI) ----

    fun shipBreaking(ship: Ship) {
        if (on) pick(ship, 0, ENEMY_BREAKING)
    }

    fun shipDestroyed(ship: Ship) {
        if (on) pick(ship, SHIP_DESTROYED, ENEMY_DESTROYED)
    }

    fun shipJumpsAway(ship: Ship) {
        if (on) pick(ship, 0, ENEMY_GONE)
    }

    // ---- weapons and shots (AbstractWeaponInstance, Weapons, Ship, BrowserAchievementProgress) ----

    /** A weapon on the ship's own mounts fired a volley. */
    fun weaponFired(ship: Ship, type: AbstractWeaponBlueprint) {
        if (!on || !ship.isPlayerShip) return
        moment(
            when {
                type is BeamBlueprint -> FIRE_BEAM
                type is MissileBlueprint -> FIRE_MISSILE
                type is BombBlueprint -> FIRE_BOMB
                type is FlakBlueprint -> FIRE_FLAK
                type.ionDamage > 0 && type.damage <= 0 -> FIRE_ION
                else -> FIRE_LASER
            }
        )
    }

    /** A shot at [target] missed it. */
    fun shotMissed(target: Ship) {
        if (on) pick(target, DODGED, SHOT_MISSED)
    }

    /** Two projectiles met in the space around [ship] (a defence drone's shot and what it shot down). */
    fun shotDown(ship: Ship) {
        if (on) pick(ship, INTERCEPTED, SHOT_INTERCEPTED)
    }

    // ---- drones, cloak, teleporter, mind control, hacking ----

    fun droneLaunched(ship: Ship) {
        if (on) pick(ship, DRONE_LAUNCHED, 0)
    }

    fun droneDestroyed(owner: Ship?) {
        if (on && owner != null) pick(owner, DRONE_DESTROYED, 0)
    }

    fun cloak(ship: Ship, begins: Boolean) {
        if (on) pick(ship, if (begins) CLOAK_ON else CLOAK_OFF, if (begins) ENEMY_CLOAK else 0)
    }

    fun teleport(ship: Ship) {
        if (on) pick(ship, TELEPORT, 0)
    }

    /** [ship]'s mind control took hold of someone. */
    fun mindControl(ship: Ship) {
        if (on) pick(ship, MIND_CONTROL, MIND_CONTROLLED)
    }

    /** [ship]'s hacking pulse began. */
    fun hackPulse(ship: Ship) {
        if (on) pick(ship, HACK, HACKED)
    }

    // ---- crew ----

    fun crewBadlyHurt() = moment(CREW_HURT)

    fun crewDied(crew: AbstractCrew) {
        if (!on) return
        val owner = (crew as? xyz.znix.xftl.crew.LivingCrew)?.ownerShip ?: return
        pick(owner, CREW_DIED, ENEMY_CREW_DIED)
    }

    fun crewCloned(ship: Ship) {
        if (on) pick(ship, CREW_CLONED, 0)
    }
}
