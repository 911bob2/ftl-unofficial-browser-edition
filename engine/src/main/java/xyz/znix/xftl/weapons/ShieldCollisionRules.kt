// Native 1.6.9 Shields::CollisionTest/Real 60d840/60d980, ShipManager::CollisionMoving 499990;
// shared rules checked in 1.6.14 Shields 6335b0/6336f0. HS GPL-3.0-or-later 1b05a07
// CustomDamage.cpp ShipManager::CollisionShield and ErosionEffect.cpp Shields::CollisionReal.
package xyz.znix.xftl.weapons

import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.weapons.HsDamage
import xyz.znix.xftl.hs.weapons.HsWeaponHooks
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.MutFPoint
import xyz.znix.xftl.rendering.Colour
import kotlin.math.sqrt

object ShieldCollisionRules {
    fun test(ship: Ship, point: FPoint, force: Boolean = false): ProjectileCollisionResponse {
        val result = ProjectileCollisionResponse()
        val layers = ship.shields?.activeShields ?: 0
        if (layers <= 0 && ship.superShield <= 0) return result
        val a = ship.shieldHalfSize.x.toFloat()
        val b = ship.shieldHalfSize.y.toFloat()
        val x = point.xf - ship.shieldOrigin.x
        val y = point.yf - ship.shieldOrigin.y
        if (!force && !(x * x / (a * a) + y * y / (b * b) < 1f)) return result
        result.type = 2; result.point.set(point)
        result.damage = layers; result.superDamage = if (ship.superShield > 0) 1 else 0
        return result
    }

    fun real(ship: Ship, point: FPoint, damage: Damage, force: Boolean = false): ProjectileCollisionResponse {
        val result = test(ship, point, force)
        if (result.type != 2) return result
        val previous = ship.beginNativeShieldHit(point, damage.hsSelfId, result.damage)
        if (previous != null) result.damage = previous
        else if (ship.superShield > 0) {
            val before = ship.superShield
            val negated = damage.ionDamage > 0 && ship.ionArmourNegates()
            val ion = if (negated) 0 else damage.ionDamage
            val absorbed = maxOf(0, damage.hullDamage + ion * 2)
            result.superDamage = minOf(before, absorbed)
            ship.nativeSetSuperShield(before - absorbed)
            if (negated) ship.showDamageTextAt(point, "text_resist", Constants.DAMAGE_COLOUR_ION)
            if (absorbed != 0) ship.showDamageTextAt(point, absorbed, Constants.DAMAGE_COLOUR_ZOLTAN)
        } else if (damage.hullDamage > 0 && damage.hsShieldPiercing < result.damage) {
            // CollisionReal handles the bubble; ShipManager::CollisionMoving applies ion afterward.
            val bubble = damage.copy(); bubble.ionDamage = 0
            ship.attackShields(bubble, null)
        }
        if (HS.active) HsDamage.shieldHit(ship, false)
        return result
    }

    private fun copyResponse(from: ProjectileCollisionResponse, into: ProjectileCollisionResponse) {
        into.type = from.type; into.point.set(from.point)
        into.damage = from.damage; into.superDamage = from.superDamage
    }

    /** Local damage/response members live throughout PRE and POST. Source damage is a separate value. */
    fun moving(ship: Ship, start: FPoint, finish: FPoint, incoming: Damage, raytrace: Boolean = false,
               getDodged: () -> Boolean): ProjectileCollisionResponse {
        if (!raytrace) return segment(ship, start, finish, incoming, false, getDodged)
        // Native ShipManager::CollisionMoving 499990: advance by five pixels, retaining the original
        // start for each shield check. Stop only when distance to finish increases; a sample may pass finish.
        val dx = finish.xf - start.xf; val dy = finish.yf - start.yf
        val length = sqrt(dx * dx + dy * dy)
        val stepX = if (length == 0f) 0f else dx / length * 5f
        val stepY = if (length == 0f) 0f else dy / length * 5f
        val point = MutFPoint(start.xf, start.yf)
        var remaining = length
        do {
            val previous = remaining
            point.set(point.xf + stepX, point.yf + stepY)
            val x = finish.xf - point.xf; val y = finish.yf - point.yf
            remaining = sqrt(x * x + y * y)
            if (previous < remaining) return ProjectileCollisionResponse()
            val result = segment(ship, start, point, incoming, true, getDodged)
            if (result.type != 0 || length == 0f) return result
        } while (true)
    }

    private fun segment(ship: Ship, start: FPoint, finish: FPoint, incoming: Damage, raytrace: Boolean,
                        getDodged: () -> Boolean): ProjectileCollisionResponse {
        val before = test(ship, start)
        val response = test(ship, finish)
        if (before.type != 0 || response.type == 0) return ProjectileCollisionResponse()
        val damage = incoming.copy()
        val view = if (HS.luaActive) HsWeaponHooks.collisionView(response) else null
        val preempt = view != null && HsWeaponHooks.shieldCollision(A.EV_SHIELD_COLLISION_PRE, ship, damage, view)
        if (!preempt) {
            if (raytrace) {
                damage.hullDamage = 0
                real(ship, finish, damage) // Native intentionally preserves CollisionTest's response for beams.
            } else if (getDodged()) {
                ship.showDamageTextAt(finish, "text_miss", Colour.white)
                copyResponse(ProjectileCollisionResponse().also { it.type = 3 }, response)
            } else copyResponse(real(ship, finish, damage), response)
        }
        if (response.type == 2 && view != null)
            HsWeaponHooks.shieldCollision(A.EV_SHIELD_COLLISION, ship, damage, view) // POST preemption is ignored.
        // This is outside CollisionShield: it uses the caller's damage, after POST, independently of PRE edits.
        if (response.type == 2 && incoming.ionDamage > 0 && response.superDamage < 1 && ship.superShield < 1)
            ship.attackShieldsIon(incoming)
        return response
    }
}
