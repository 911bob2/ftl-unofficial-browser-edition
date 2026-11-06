package xyz.znix.xftl.game

/**
 * A finger held still, for the touch layout's hold-to-show tooltips: where it is and since when it has rested there.
 * A move further than [slop] from where the rest began starts the rest again at the new place, so a finger that slides
 * and stops shows what it stopped on after another [hold] seconds.
 */
class TouchHold(private val hold: Float, private val slop: Double) {
    /** Where the finger is now (the last down or move). */
    var x = 0
        private set
    var y = 0
        private set
    private var restX = 0
    private var restY = 0
    private var since = -1L

    val down: Boolean get() = since >= 0

    fun down(x: Int, y: Int, nanos: Long) {
        this.x = x; this.y = y
        restX = x; restY = y
        since = nanos
    }

    fun move(x: Int, y: Int, nanos: Long) {
        if (since < 0) return
        this.x = x; this.y = y
        if (Math.hypot((x - restX).toDouble(), (y - restY).toDouble()) > slop) {
            restX = x; restY = y
            since = nanos
        }
    }

    fun up() {
        since = -1
    }

    /** The finger has rested for [hold] seconds. */
    fun held(nanos: Long): Boolean = since >= 0 && (nanos - since) / 1e9f >= hold
}
