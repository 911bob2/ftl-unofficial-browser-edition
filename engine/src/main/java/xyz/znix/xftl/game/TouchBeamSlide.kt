package xyz.znix.xftl.game

/** The second finger's slide of an aimed beam: the line moves as one and stays inside the target ship's bounds. */
object TouchBeamSlide {
    /**
     * The shift to apply on one axis for a line with ends [a] and [b], asked to move by [delta], inside [min]..[max].
     * A line longer than the bounds may move only while it still covers them.
     */
    fun clampAxis(delta: Int, a: Int, b: Int, min: Int, max: Int): Int {
        val toMin = min - minOf(a, b)
        val toMax = max - maxOf(a, b)
        return delta.coerceIn(minOf(toMin, toMax), maxOf(toMin, toMax))
    }
}
