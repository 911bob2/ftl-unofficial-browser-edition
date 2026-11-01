package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.layout.LockdownShard
import xyz.znix.xftl.sys.GameContainer
import kotlin.math.abs

/**
 * The crystal lockdown's shards (Ship::LockdownRoom 0x494f50, LockdownShard 0x489af0 / Update 0x48b330), against the
 * native 1.6.14 capture `.build/wp5-ship/native/out/ld1` (Crystal cruiser A, a crystal crew member's lockdown of room
 * 17 from (507, 87); core c0 after 50 frames, c1 at lifeTime 7.05 s, c2 with no shards left):
 * 26 shards, 3 per 35 px of each wall in top/bottom then left/right pairs, then one per door, every one from the
 * origin at `speed` pixels a frame; all there and arrived at 7.05 s, gone after 12 s.
 */
internal object GameplayParityLockdownShardChecks {
    // c0: goal x, goal y, speed, position after 50 frames (x, y), per shard in the native vector's order
    private val NATIVE = arrayOf(
        floatArrayOf(465f, 70f, 1.652f, 465f, 70f), floatArrayOf(459f, 140f, 1.764f, 459f, 140f),
        floatArrayOf(478f, 70f, 1.253f, 478f, 70f), floatArrayOf(469f, 140f, 1.283f, 469.620544f, 139.134506f),
        floatArrayOf(489f, 70f, 1.437f, 489f, 70f), floatArrayOf(488f, 140f, 1.159f, 488f, 140f),
        floatArrayOf(497f, 70f, 1.106f, 497f, 70f), floatArrayOf(499f, 140f, 1.008f, 499.477631f, 136.835526f),
        floatArrayOf(508f, 70f, 1.436f, 508f, 70f), floatArrayOf(510f, 140f, 1.962f, 510f, 140f),
        floatArrayOf(519f, 70f, 1.274f, 519f, 70f), floatArrayOf(522f, 140f, 1.670f, 522f, 140f),
        floatArrayOf(455f, 72f, 1.839f, 455f, 72f), floatArrayOf(525f, 75f, 1.073f, 525f, 75f),
        floatArrayOf(455f, 88f, 1.609f, 455f, 88f), floatArrayOf(525f, 83f, 1.289f, 525f, 83f),
        floatArrayOf(455f, 103f, 1.761f, 455f, 103f), floatArrayOf(525f, 102f, 1.691f, 525f, 102f),
        floatArrayOf(455f, 112f, 1.649f, 455f, 112f), floatArrayOf(525f, 115f, 1.899f, 525f, 115f),
        floatArrayOf(455f, 128f, 1.728f, 455f, 128f), floatArrayOf(525f, 122f, 1.953f, 525f, 122f),
        floatArrayOf(455f, 131f, 1.477f, 455f, 131f), floatArrayOf(525f, 141f, 1.802f, 525f, 141f),
        floatArrayOf(455f, 87f, 1.394f, 455f, 87f), floatArrayOf(455f, 122f, 1.502f, 455f, 122f),
    )
    private const val FRAME = 1f / 60f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            var state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CRYSTAL")
            val ship = state.player
            val room = ship.rooms[17]
            check(room.offsetX == 455 && room.offsetY == 70 && room.pixelWidth == 70 && room.pixelHeight == 70) {
                "Room 17 at ${room.offsetX},${room.offsetY} ${room.pixelWidth}x${room.pixelHeight}"
            }
            ship.lockdownShards.clear()
            ship.lockdownRoom(room, 507f, 87f)
            val made = ArrayList(ship.lockdownShards)
            check(made.size == NATIVE.size) { "Lockdown made ${made.size} shards, native ${NATIVE.size}" }
            // the random offsets: each goal on the native shard's wall, in the same 12 px step
            for ((i, s) in made.withIndex()) {
                val n = NATIVE[i]
                // Hyperspace's door shards freeze (Ship.lockdownRoom).
                check(s.superFreeze == (i >= 24 && BrowserGameplayParityDiagnostics.modded) && s.lockingRoom == 17 && s.x == 507f && s.y == 87f && s.speed in 1f..2f) { "Shard $i fields" }
                val door = i >= 24
                val ok = when {
                    door -> s.goalX == n[0] && s.goalY == n[1]
                    i < 12 -> s.goalY == n[1] && ((s.goalX - 455f) / 12f).toInt() == ((n[0] - 455f) / 12f).toInt()
                    else -> s.goalX == n[0] && ((s.goalY - 70f) / 12f).toInt() == ((n[1] - 70f) / 12f).toInt()
                }
                check(ok) { "Shard $i goal ${s.goalX},${s.goalY}, native ${n[0]},${n[1]}" }
            }
            // the native draws replayed: 50 frames from the origin give c0's positions
            ship.lockdownShards.clear()
            for (n in NATIVE) ship.lockdownShards += LockdownShard(ship, 17, 507f, 87f, n[0].toInt(), n[1].toInt(), "crystal_1", n[2], false)
            repeat(50) { for (s in ship.lockdownShards) s.update(FRAME) }
            for ((i, s) in ship.lockdownShards.withIndex()) {
                val n = NATIVE[i]
                check(abs(s.x - n[3]) < 1e-3f && abs(s.y - n[4]) < 1e-3f) { "Shard $i at ${s.x},${s.y} after 50 frames, native ${n[3]},${n[4]}" }
                check(s.arrived == (s.x == n[0] && s.y == n[1])) { "Shard $i arrived ${s.arrived}" }
            }
            // a save keeps them
            state = BrowserGameplayParityDiagnostics.reload(main, state)
            val loaded = state.player.lockdownShards
            check(loaded.size == NATIVE.size && loaded.withIndex().all { (i, s) -> abs(s.x - NATIVE[i][3]) < 1e-3f && s.goalY == NATIVE[i][1] }) {
                "Shards after a save: ${loaded.size}"
            }
            // c1: all there and arrived at 7.05 s; gone at 12 s (the reverse ends with lifeTime 12)
            var t = 50 * FRAME
            while (t < 7.05f) { for (s in loaded) s.update(FRAME); t += FRAME }
            check(loaded.all { it.arrived && !it.done }) { "Shards at 7.05 s: ${loaded.count { it.done }} done" }
            val doneAt = FloatArray(loaded.size) { -1f }
            while (t < 13f) {
                for ((i, s) in loaded.withIndex()) { if (!s.done) { s.update(FRAME); if (s.done) doneAt[i] = s.lifeTime } }
                t += FRAME
            }
            check(doneAt.all { it in 12f - 1e-3f..12f + 2 * FRAME }) { "Shards done at ${doneAt.joinToString()}" }
            return "lockdown shards (26, goals, 50-frame positions, save, 12 s)"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }
}
