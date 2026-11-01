package xyz.znix.xftl.layout

import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.Ship
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * One crystal shard of a lockdown (FTLGame.exe 1.6.14 LockdownShard, 0xf0 bytes in Ship::lockdowns at Ship+0x3bc).
 *
 * Ship::LockdownRoom (0x494f50) makes them: three per 35 px of each wall of the room, flying from the lockdown's origin
 * to a point along the wall, and one per door of the room flying to the door's centre. The constructor (0x489af0, the
 * door one 0x48a260) takes the animation `crystal_1` or `crystal_2` (`random32() % 2 + 1`; a door shard always 1) and
 * the speed `(random32() % 1001 + 1000) / 1000` pixels per frame.
 *
 * LockdownShard::Update (0x48b330), once per Ship::OnLoop: lifeTime counts up by the frame's seconds; the shard moves
 * by Globals::GetNextPoint (0x5ca360: `speed` pixels a frame, snapping to the goal when closer than that) and starts its
 * animation on arrival; once the animation is done it plays backwards over the rest of the 12 seconds
 * (`tracker.time = 12 - lifeTime`), and when that is done the shard goes (Ship::OnLoop 0x494af0 erases it).
 * Shards are drawn after the doors (Ship::OnRenderWalls), the frame's top left at (x - 9, y - 14).
 *
 * Hyperspace (CustomLockdowns.cpp, 1b05a07) rewrites Update: lifeTime starts at the lockdown definition's duration
 * and counts down, the reverse takes the remaining lifeTime, door shards ([superFreeze]) only fade once 0.5 s are left,
 * and the definition gives the colour and the animations.
 */
class LockdownShard(
    val ship: Ship,
    @JvmField val lockingRoom: Int,
    startX: Float, startY: Float,
    goalX: Int, goalY: Int,
    @JvmField val animName: String,
    @JvmField val speed: Float,
    @JvmField val superFreeze: Boolean,
    @JvmField var colour: Colour = Colour.white,
    /** Hyperspace: the lockdown's duration (lifeTime counts down from it); null in vanilla (counts up from 0). */
    hsDuration: Float? = null,
) {
    @JvmField var x: Float = startX
    @JvmField var y: Float = startY
    @JvmField val goalX: Float = goalX.toFloat()
    @JvmField val goalY: Float = goalY.toFloat()
    @JvmField var arrived = false
    @JvmField var done = false
    @JvmField val hs: Boolean = hsDuration != null
    @JvmField var lifeTime: Float = hsDuration ?: 0f

    /** LockdownShard_Extend::canDilate: Hyperspace's shard counts down (and animates) at its locking room's time dilation. */
    @JvmField var canDilate = true

    /** LockdownShard_Extend::door: the door a door shard locks (null for a wall shard). */
    @JvmField var door: xyz.znix.xftl.layout.Door? = null

    /** LockdownShard_Extend::health of a wall shard (unused, 0); a door shard's health is its door's lockdown health. */
    @JvmField var ownHealth = 0
    var health: Int
        get() = door?.lockdownHealth ?: ownHealth
        set(v) { val d = door; if (d != null) d.setLockdownHealth(v) else ownHealth = v }

    /** The playing animation; forward until [reversing], then backwards over the remaining lifetime. */
    private var anim: FTLAnimation = ship.sys.animations.orEmpty(animName).startSingle(ship.sys)
    @JvmField var reversing = false
    @JvmField var started = false

    fun update(frameDt: Float) {
        // TemporalSystem.cpp's LockdownShard::Update hook: the shard's time runs at its locking room's dilation if it canDilate
        val dt = if (hs && canDilate) frameDt * xyz.znix.xftl.hs.systems.HsTemporal.factor(ship.rooms.getOrNull(lockingRoom)) else frameDt
        // CFPS::GetSpeedFactor() * 0.0625: the frame's seconds
        if (hs) lifeTime -= dt else lifeTime += dt
        if (x != goalX || y != goalY) {
            // One native frame per 1/60 s (the frame limit); the step is speed pixels a frame.
            val step = speed * frameDt * 60f
            val dx = goalX - x; val dy = goalY - y
            if (dx * dx + dy * dy < step * step) {
                x = goalX; y = goalY
            } else {
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                x += dx / d * step; y += dy / d * step
            }
            if (x == goalX && y == goalY) {
                arrived = true
                started = true
                anim = ship.sys.animations.orEmpty(animName).startSingle(ship.sys)
            }
        }
        if (arrived) {
            anim.update(dt)
            val canFade = !superFreeze || (if (hs) lifeTime <= 0.5f else 12f - lifeTime <= 0.5f)
            if (anim.isStopped && canFade) {
                if (reversing) {
                    done = true
                } else {
                    reversing = true
                    val rest = if (hs) lifeTime else 12f - lifeTime
                    anim = ship.sys.animations.orEmpty(animName).startSingle(ship.sys, 1f, true)
                    anim.duration = maxOf(rest, 1e-4f)
                }
            }
        }
    }

    /** LockdownShard_Extend::color as a script holds it (`extend.color.r = ...` writes through it); null until a script reads it. */
    @JvmField var colourValue: xyz.znix.xftl.hs.lua.GlColorValue? = null

    fun render(g: Graphics, alpha: Float) {
        val c = colourValue?.let { Colour(it.r, it.g, it.b, it.a) } ?: colour
        anim.draw(x - 9f, y - 14f, Colour(c.r, c.g, c.b, c.a * alpha))
    }

    /** LockdownShard::SaveState (0x48b200): the fields, plus the animation's progress (the engine's own state). */
    fun saveToXML(): org.jdom2.Element {
        val e = org.jdom2.Element("shard")
        fun a(k: String, v: Any) { e.setAttribute(k, v.toString()) }
        a("room", lockingRoom); a("x", x); a("y", y); a("gx", goalX.toInt()); a("gy", goalY.toInt()); a("speed", speed)
        a("anim", animName); a("arrived", arrived); a("done", done); a("life", lifeTime); a("superFreeze", superFreeze)
        a("hs", hs); a("reversing", reversing); a("timer", anim.timer); a("duration", anim.duration)
        val sc = colourValue?.let { Colour(it.r, it.g, it.b, it.a) } ?: colour
        a("colour", "${sc.r},${sc.g},${sc.b},${sc.a}")
        if (!canDilate) a("noDilate", true)
        val d = door
        if (d != null) a("doorIdx", ship.doors.indexOf(d))
        if (ownHealth != 0) a("ownHealth", ownHealth)
        return e
    }

    companion object {
        fun loadFromXML(ship: Ship, e: org.jdom2.Element): LockdownShard {
            fun f(k: String) = e.getAttributeValue(k).toFloat()
            fun b(k: String) = e.getAttributeValue(k).toBoolean()
            val c = e.getAttributeValue("colour").split(',').map { it.toFloat() }
            val s = LockdownShard(ship, e.getAttributeValue("room").toInt(), f("x"), f("y"), e.getAttributeValue("gx").toInt(),
                e.getAttributeValue("gy").toInt(), e.getAttributeValue("anim"), f("speed"), b("superFreeze"),
                Colour(c[0], c[1], c[2], c[3]), if (b("hs")) f("life") else null)
            s.lifeTime = f("life"); s.arrived = b("arrived"); s.done = b("done"); s.reversing = b("reversing")
            s.anim = ship.sys.animations.orEmpty(s.animName).startSingle(ship.sys, 1f, s.reversing)
            s.anim.duration = maxOf(f("duration"), 1e-4f); s.anim.timer = f("timer")
            s.canDilate = e.getAttributeValue("noDilate") == null
            e.getAttributeValue("doorIdx")?.toInt()?.let { i -> s.door = ship.doors.getOrNull(i) }
            e.getAttributeValue("ownHealth")?.toInt()?.let { s.ownHealth = it }
            return s
        }

        /** LockdownShard's speed draw: `(random32() % 1001 + 1000) / 1000`. */
        fun speedOf(draw: Int): Float = (draw % 1001 + 1000).toFloat() / 1000f
    }
}
