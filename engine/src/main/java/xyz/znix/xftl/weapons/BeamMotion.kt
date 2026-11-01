// Native 1.6.9 BeamWeapon constructor/OnUpdate/Dead 4357c0/435c90/434f60;
// shared 1.6.14 rules checked at 4383b0/438880/437b10. HS GPL-3.0-or-later
// 1b05a07 CustomDamage.cpp wraps this virtual update with PROJECTILE_UPDATE_PRE/POST.
package xyz.znix.xftl.weapons

import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.MutFPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** BeamWeapon's derived motion state. [tip] is Projectile.target, independently of its fixed position. */
class BeamMotion(val tip: MutFPoint, origin: FPoint, end: FPoint, initialLength: Int, initialHeading: Float) {
    val subEnd = MutFPoint(0f, 0f)
    val subStart = MutFPoint(origin.xf, origin.yf)
    val shieldEnd = MutFPoint(-1f, -1f)
    val finalEnd = MutFPoint(tip.xf, tip.yf)
    val target1 = MutFPoint(tip.xf, tip.yf)
    val target2 = MutFPoint(end.xf, end.yf)
    var lifespan = 1f
    var length = initialLength.toFloat()
    var dh = 0f
    val lastCollision = ProjectileCollisionResponse().also { it.point.set(-2147483648f, -2147483648f) }
    var soundChannel = -2
    var animationTimer = -1f
    var lastDamage = 0
    var startHeading = initialHeading
    var timer = 0f
    var piercedShield = true
    var oneSpace = false
    var damageSuperShield = false
    var movingTargetId = -1
    var checkedCollision = false
    val lastSmokeAnim = MutFPoint(2147483648f, 2147483648f)

    init {
        // Native constructor: straight right for heading 0, else straight up. CreateBeam rewrites it along the
        // heading ([aimSubEnd]).
        if (initialHeading == 0f) subEnd.set(origin.xf + 2000f, origin.yf) else subEnd.set(origin.xf, origin.yf - 2000f)
    }

    /** HS SpaceManager::CreateBeam (CustomDamage.cpp1030): sub_end = position + 2000 along the given heading. */
    fun aimSubEnd(origin: FPoint, heading: Float) {
        val radians = heading * .01745329f
        subEnd.set(origin.xf + 2000f * cos(radians), origin.yf + 2000f * sin(radians))
    }

    /** Returns the actual new heading. Direct updates also run with the projectile's dead flag set. */
    fun advance(dt: Float, speed: Float, heading: Float, fireLevel: ((Float) -> Unit)? = null): Float {
        if (!checkedCollision) return heading
        animationTimer -= dt
        val nextHeading = heading + dh * (dt * 16f)
        val effectiveLength = minOf(distance(target1, target2), length)
        // Native uses the retained final_end here, even if a script changed Projectile.target independently.
        val previousProgress = distance(finalEnd, target1) / effectiveLength
        val dx = target2.xf - tip.xf
        val dy = target2.yf - tip.yf
        val remaining = sqrt(dx * dx + dy * dy)
        val step = speed * (dt * 16f)
        if (remaining <= step || remaining == 0f) tip.set(target2)
        else tip.set(tip.xf + dx / remaining * step, tip.yf + dy / remaining * step)
        finalEnd.set(tip)
        val progress = distance(finalEnd, target1) / effectiveLength
        val travelledX = finalEnd.xf - target1.xf
        val travelledY = finalEnd.yf - target1.yf
        if ((finalEnd.xf == target2.xf && finalEnd.yf == target2.yf) ||
            travelledX * travelledX + travelledY * travelledY > length * length) lifespan = -1f
        fireLevel?.invoke(progress)
        if ((progress >= .33f && previousProgress < .33f) ||
            (progress >= .8f && previousProgress < .8f && length > 20f)) damageSuperShield = true
        return nextHeading
    }

    fun dead(flag: Boolean): Boolean = lifespan < 0f || flag

    private fun distance(a: FPoint, b: FPoint): Float {
        val x = a.xf - b.xf; val y = a.yf - b.yf
        return sqrt(x * x + y * y)
    }

    companion object {
        /** Native 1.6.9 beam entry helper4541f0. The caller owns the cycling index and combat draw. */
        fun entryPoint(index: Int, draw: Int): MutFPoint = when (index) {
            0 -> MutFPoint(-400f, (draw % 800).toFloat())
            1 -> MutFPoint(800f, ((draw % 800) / 2).toFloat())
            2 -> MutFPoint((draw % 800).toFloat(), -400f)
            3 -> MutFPoint((draw % 800).toFloat(), 800f)
            else -> error("Invalid native beam entry index")
        }
    }
}
