package xyz.znix.xftl.weapons

/** Native Projectile::death_animation tracker. Kept on the projectile while an impact plays, rather than
 * removing the body and substituting a detached effect. Native 1.6.9 Projectile::Dead 0x44f020 and
 * 1.6.14 0x4567e0 advance this tracker; OnUpdate tests running independently of Projectile::dead.
 * The same AnimationTracker model serves flight_animation and flashTracker (1.6.14 AnimationTracker::Update
 * 0x5ba8e0, StartReverse/Animation::StartReverse 0x5bbd90, Animation::Done 0x5bb5a0). */
class ProjectileDeathAnimation {
    /** The animation it runs, when known (what scripts read of it). */
    @JvmField var spec: xyz.znix.xftl.AnimationSpec? = null
    var time = .5f
    var currentTime = 0f
    var running = false
    var done = false
    var loop = false
    var reverse = false
    var loopDelay = 0f
    var currentDelay = 0f
    var frameCount = 9
    var currentFrame = 0
    /** Animation::fScale: a script's value, else the owner's (death_animation: fake flak 0.25, CrewLaser 0.5). */
    var scaleOverride: Float? = null
    var scaleSource: (() -> Float)? = null
    val scale: Float get() = scaleOverride ?: scaleSource?.invoke() ?: 1f
    /** Optional Lua member view, created only when a script asks for it. */
    var luaView: Any? = null

    fun start(reset: Boolean) {
        if (reset) currentTime = 0f
        running = true
        done = false
    }

    /** Animation::StartReverse(true): the tracker runs from its end back to zero. */
    fun startReverse(reset: Boolean) {
        done = false
        if (reset || currentTime < 0f) currentTime = time
        running = true
        reverse = true
        updateFrame()
    }

    /**
     * AnimationTracker::Update: a forward tracker finishes (done, clamped to time) only once past its time; a
     * reverse one at or below zero. A looping tracker waits out its loop delay, restarts from the end it reached
     * and never sets done.
     */
    fun advance(dt: Float) {
        if (!running) return
        if (loop) {
            if (currentDelay > 0f) { currentDelay -= dt; return }
            if (!reverse) {
                if (currentTime == time) { currentTime = 0f; currentDelay = loopDelay }
            } else if (currentTime == 0f) { currentTime = time; currentDelay = loopDelay }
        }
        if (!reverse) {
            currentTime += dt
            if (time < currentTime) {
                if (loop) currentDelay = loopDelay else done = true
                currentTime = time
            }
        } else {
            currentTime -= dt
            if (currentTime <= 0f) {
                if (loop) currentDelay = loopDelay else done = true
                currentTime = 0f
            }
        }
        updateFrame()
    }

    /** Animation::Update's frame: (int)(frames * current/time), clamped to the last frame. */
    private fun updateFrame() {
        currentFrame = if (time > 0f) (currentTime / time * frameCount).toInt().coerceIn(0, maxOf(0, frameCount - 1)) else 0
    }
}
