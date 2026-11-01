// Native Projectile::death_animation member and AnimationTracker view. The tracker is the gameplay object's
// state: Lua mutations change motion, collision eligibility and removal. HS API: 1b05a07 hyperspace.i.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.weapons.ProjectileDeathAnimation
import xyz.znix.xftl.hs.lua.LuaApi as A

object LuaProjectileAnimation {
    private const val OWNER = -100
    private fun owner(value: Any?) = (value as? LuaStruct)?.values?.get(OWNER) as? ProjectileDeathAnimation

    fun view(animation: ProjectileDeathAnimation): LuaStruct {
        (animation.luaView as? LuaStruct)?.let { return it }
        val result = LuaStructs.create(A.T_Animation)
        result.values[OWNER] = animation
        val tracker = LuaStructs.member(result, A.A_Animation_tracker) as LuaStruct
        tracker.values[OWNER] = animation
        val info = LuaStructs.member(result, A.A_Animation_info) as LuaStruct
        info.values[A.A_AnimationDescriptor_numFrames] = animation.frameCount.toDouble()
        animation.spec?.let { spec ->
            result.values[A.A_Animation_animName] = spec.name
            info.values[A.A_AnimationDescriptor_frameWidth] = spec.sheet.frameWidth.toDouble()
            info.values[A.A_AnimationDescriptor_frameHeight] = spec.sheet.frameHeight.toDouble()
            info.values[A.A_AnimationDescriptor_imageWidth] = spec.sheet.sheetWidth.toDouble()
            info.values[A.A_AnimationDescriptor_imageHeight] = spec.sheet.sheetHeight.toDouble()
        }
        animation.luaView = result
        return result
    }

    /** An AnimationTracker member by itself (Projectile::flashTracker): the same owned tracker fields. */
    fun trackerView(animation: ProjectileDeathAnimation): LuaStruct = LuaViews.cached(animation, A.T_AnimationTracker) {
        LuaStructs.create(A.T_AnimationTracker).also { it.values[OWNER] = animation }
    }

    fun install() {
        LuaStructs.create(A.T_Animation) // Install generic fields before wrapping the owned member views.
        fun number(id: Int, get: (ProjectileDeathAnimation) -> Float, set: (ProjectileDeathAnimation, Float) -> Unit) {
            val oldGet = LuaReg.numGet[id]!!; val oldSet = LuaReg.numSet[id]!!
            LuaReg.num(id, NumGet { value -> owner(value)?.let { get(it).toDouble() } ?: oldGet.get(value) },
                NumSet { value, n -> val state = owner(value)
                    if (state == null) oldSet.set(value, n) else set(state, n.toFloat()) })
        }
        fun boolean(id: Int, get: (ProjectileDeathAnimation) -> Boolean, set: (ProjectileDeathAnimation, Boolean) -> Unit) {
            // Boolean registrations use the numeric bridge (see LuaReg.bool).
            number(id, { if (get(it)) 1f else 0f }, { o, v -> set(o, v != 0f) })
        }
        number(A.A_AnimationTracker_time, { it.time }, { o, v -> o.time = v })
        number(A.A_AnimationTracker_current_time, { it.currentTime }, { o, v -> o.currentTime = v })
        number(A.A_AnimationTracker_loopDelay, { it.loopDelay }, { o, v -> o.loopDelay = v })
        number(A.A_AnimationTracker_currentDelay, { it.currentDelay }, { o, v -> o.currentDelay = v })
        number(A.A_Animation_fScale, { it.scale }, { o, v -> o.scaleOverride = v })
        number(A.A_Animation_currentFrame, { it.currentFrame.toFloat() }, { o, v -> o.currentFrame = v.toInt() })
        boolean(A.A_AnimationTracker_running, { it.running }, { o, v -> o.running = v })
        boolean(A.A_AnimationTracker_done, { it.done }, { o, v -> o.done = v })
        boolean(A.A_AnimationTracker_loop, { it.loop }, { o, v -> o.loop = v })
        boolean(A.A_AnimationTracker_reverse, { it.reverse }, { o, v -> o.reverse = v })
        fun method(id: Int, action: (ProjectileDeathAnimation) -> Int) {
            val previous = LuaReg.methods[id]!!
            LuaReg.method(id, LuaMethod { n ->
                val animation = owner(LuaArgs.obj(1))
                if (animation == null) previous.call(n) else action(animation)
            })
        }
        method(A.M_Animation_Start) { it.start(LuaArgs.bool(2)); 0 }
        method(A.M_Animation_Update) { it.advance(LuaFps.speedFactor * .0625f); 0 }
        method(A.M_Animation_Done) { LuaPush.bool(it.done) }
        trackerMethods()
        method(A.M_Animation_SetCurrentFrame) {
            it.currentFrame = LuaArgs.int(2)
            if (it.frameCount > 0) it.currentTime = it.time * it.currentFrame / it.frameCount
            0
        }
    }

    /**
     * AnimationTracker methods (1.6.14 0x5bbed0-0x5bc000), on any tracker view: a body's owned tracker (flashTracker,
     * death_animation.tracker) or a plain struct. The fields go through the registered accessors, so both work.
     */
    private fun trackerMethods() {
        LuaStructs.register(A.T_AnimationTracker)
        fun get(o: Any?, a: Int) = LuaReg.numGet[a]!!.get(o)
        fun set(o: Any?, a: Int, v: Double) = LuaReg.numSet[a]!!.set(o, v)
        fun flag(v: Boolean) = if (v) 1.0 else 0.0
        fun f(x: Double) = x.toFloat()
        // SetLoop(loop, delay): loopDelay = delay, currentDelay = 0, loop.
        LuaReg.method(A.M_AnimationTracker_SetLoop, LuaMethod {
            val t = LuaArgs.obj(1)
            set(t, A.A_AnimationTracker_loopDelay, f(LuaArgs.num(3)).toDouble())
            set(t, A.A_AnimationTracker_currentDelay, 0.0)
            set(t, A.A_AnimationTracker_loop, flag(LuaArgs.bool(2)))
            0
        })
        // Stop(keep): running = false; current_time kept when the argument is true, else 0.
        LuaReg.method(A.M_AnimationTracker_Stop, LuaMethod {
            val t = LuaArgs.obj(1)
            set(t, A.A_AnimationTracker_running, 0.0)
            if (!LuaArgs.bool(2)) set(t, A.A_AnimationTracker_current_time, 0.0)
            0
        })
        // Start(time): done = false, running, current_time = time, forward.
        LuaReg.method(A.M_AnimationTracker_Start, LuaMethod {
            val t = LuaArgs.obj(1)
            set(t, A.A_AnimationTracker_done, 0.0)
            set(t, A.A_AnimationTracker_running, 1.0)
            set(t, A.A_AnimationTracker_current_time, f(LuaArgs.num(2)).toDouble())
            set(t, A.A_AnimationTracker_reverse, 0.0)
            0
        })
        // StartReverse(time): the same backwards; a negative time starts from the end (time).
        LuaReg.method(A.M_AnimationTracker_StartReverse, LuaMethod {
            val t = LuaArgs.obj(1)
            val time = f(LuaArgs.num(2))
            set(t, A.A_AnimationTracker_done, 0.0)
            set(t, A.A_AnimationTracker_running, 1.0)
            set(t, A.A_AnimationTracker_current_time, if (time < 0f) f(get(t, A.A_AnimationTracker_time)).toDouble() else time.toDouble())
            set(t, A.A_AnimationTracker_reverse, 1.0)
            0
        })
        // Progress(speed): 0 while stopped; current/time (at least 0) for a negative speed, else current/speed in [0, 1].
        LuaReg.method(A.M_AnimationTracker_Progress, LuaMethod {
            val t = LuaArgs.obj(1)
            val speed = f(LuaArgs.num(2))
            val current = f(get(t, A.A_AnimationTracker_current_time))
            val result = when {
                get(t, A.A_AnimationTracker_running) == 0.0 -> 0f
                speed < 0f -> maxOf(current / f(get(t, A.A_AnimationTracker_time)), 0f)
                else -> (current / speed).let { if (it <= 0f) 0f else minOf(it, 1f) }
            }
            LuaPush.num(result)
        })
        // SetProgress(p): current_time = time * p.
        LuaReg.method(A.M_AnimationTracker_SetProgress, LuaMethod {
            val t = LuaArgs.obj(1)
            set(t, A.A_AnimationTracker_current_time, (f(get(t, A.A_AnimationTracker_time)) * f(LuaArgs.num(2))).toDouble())
            0
        })
        // GetAlphaLevel(reverse) (0x5bc000): a triangle over the tracker, 0 at both ends and 1 half way; reverse: 1 - it.
        LuaReg.method(A.M_AnimationTracker_GetAlphaLevel, LuaMethod {
            val t = LuaArgs.obj(1)
            val half = f(get(t, A.A_AnimationTracker_time)) * .5f
            val a = (half - kotlin.math.abs(f(get(t, A.A_AnimationTracker_current_time)) - half)) / half
            LuaPush.num(if (LuaArgs.bool(2)) 1f - a else a)
        })
        // new AnimationTracker(): everything 0 and false except done (the native constructor's default tracker).
        LuaReg.method(A.M_new_AnimationTracker, LuaMethod {
            val t = LuaStructs.create(A.T_AnimationTracker)
            t.values[A.A_AnimationTracker_done] = true
            LuaPush.obj(t, A.T_AnimationTracker)
        })
    }
}
