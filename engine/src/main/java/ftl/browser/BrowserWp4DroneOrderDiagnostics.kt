package ftl.browser

import xyz.znix.xftl.drones.DroneRuntime
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * WP4 drone world order (agent B): the browser side of the native 1.6.14 drone-order probe
 * (.build/wp4-drones/native/make-native-droneorder.py). The same setup as the native session: the Engi cruiser,
 * extra drones, an enemy loaded as the console LOAD does (a new location), every drone launched, the enemy's hull
 * zeroed at a fixed frame. Returns the per-frame event letters, each drone shot's first move and the transitions
 * of every drone and of the enemy after the kill. Diagnostics only.
 */
object BrowserWp4DroneOrderDiagnostics {
    /**
     * `drone-order <second drone,third drone> <frames before the kill> <frames after> [enemy drone] [redeploy]`.
     * With `redeploy` (native drone-order probe 2, `E/native/order4.json`): counted in the player's deployed defence
     * drone's loops, at `before` it is SetDestroyed(true, true) and the player's combat drones get lifespan 3, it is
     * powered again once rebuilt, and at `after` loops the enemy hull is zeroed; then 1300 more frames.
     */
    fun run(main: MainGame, container: GameContainer, args: String): String {
        val parts = args.trim().split(' ')
        val extra = parts.getOrNull(0)?.split(',')?.filter { it.isNotEmpty() } ?: listOf("DEFENSE_1")
        val before = parts.getOrNull(1)?.toInt() ?: 600
        val after = parts.getOrNull(2)?.toInt() ?: 600
        val enemyDrone = parts.getOrNull(3) ?: "COMBAT_1"
        val redeploy = parts.getOrNull(4) == "redeploy"
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val graphics = BrowserEngine.diagnosticGraphics()
        val trace = DroneRuntime.Trace()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_CIRCLE", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val player = game.player
            player.purchasedReactorPower = 25
            player.dronesCount = 20
            val drones = player.drones!!
            drones.energyLevels = 8
            for ((i, name) in extra.withIndex()) drones.drones[i + 1] = Drones.DroneInfo(game.blueprintManager[name] as DroneBlueprint)
            repeat(30) { main.update(container, 1f / 60f) }
            // The native session's enemy (console LOAD ships_rebel) has a drone system with one drone: take the first
            // generated rebel/pirate ship with a drone system and give it [enemyDrone], powered.
            var spawned = 0
            for (seed in 71 until 171) {
                for (name in listOf("REBEL", "PIRATE")) {
                    if (game.enemy?.drones != null) break
                    game.debugSpawnShip(game.eventManager.getShip(name), Difficulty.NORMAL, 1, seed); spawned++
                }
                if (game.enemy?.drones != null) break
            }
            val enemy0 = game.enemy!!
            val enemyDrones = enemy0.drones
            if (enemyDrones != null) {
                enemy0.purchasedReactorPower = 25
                enemy0.dronesCount = 20
                enemyDrones.energyLevels = 8
                for (i in enemyDrones.drones.indices) enemyDrones.drones[i] = null
                // `none`: the enemy keeps its drone system but no drone (native probe 3's rebel had none).
                if (enemyDrone != "none") {
                    val info = Drones.DroneInfo(game.blueprintManager[enemyDrone] as DroneBlueprint)
                    if (enemyDrones.drones.isEmpty()) enemyDrones.drones.add(info) else enemyDrones.drones[0] = info
                }
            }
            game.debugFlags.noEnemyFire.set = true
            DroneRuntime.forceNewLocation()
            main.update(container, 0f)
            DroneRuntime.trace = trace
            val events = ArrayList<String>()
            val last = HashMap<String, String>()
            fun sample(frame: Int) {
                val enemy = game.enemy
                val e = when { enemy == null -> "none"; enemy.isDead -> "destroyed"; else -> "alive" }
                if (last["enemy"] != e) { last["enemy"] = e; events += "$frame enemy $e" }
                for ((who, list) in listOf("p" to drones.drones, "e" to (enemyDrones?.drones ?: emptyList<Drones.DroneInfo?>()))) {
                    for ((i, info) in list.withIndex()) {
                        if (info == null) continue
                        val s = "${info.deployed},${info.powered},${info.bDead},${info.destroyedTimer > 0f}"
                        if (last["$who$i"] != s) { last["$who$i"] = s; events += "$frame $who ${info.type.name} $s" }
                    }
                }
            }
            var frame = 0
            fun step() {
                // A surrender offer or the reward box would pause the game; the native console ship has neither.
                if (game.shipUI.isWindowOpen) { events += "$frame window"; game.shipUI.diagnosticCloseWindow() }
                main.update(container, 1f / 60f)
                if (graphics != null) main.render(container, graphics)
                frame++; sample(frame)
            }
            step()
            for (slot in drones.drones.indices) if (drones.drones[slot] != null) drones.setDronePower(slot, true)
            enemyDrones?.let { if (it.drones.getOrNull(0) != null) it.setDronePower(0, true) }
            events += "0 enemy ${enemy0.type.name} spawns $spawned drones ${enemyDrones?.drones?.getOrNull(0)?.type?.name}"
            if (parts.getOrNull(4) == "stun") {
                stunAndJump(game, drones, enemyDrones, before, after, parts.drop(5).map { it.toInt() }, events, ::step, { frame })
            } else if (!redeploy) {
                repeat(before) { step() }
                events += "$frame kill"
                game.enemy?.health = 0
                repeat(after) { step() }
            } else {
                val defenceSlot = drones.drones.indexOfFirst { it?.type?.type == DroneBlueprint.DroneType.DEFENSE }
                val defence = drones.drones[defenceSlot]!!
                val combats = drones.drones.filterNotNull().filter { it.type.type == DroneBlueprint.DroneType.COMBAT }
                var loops = 0; var killFrame = -1; var destroyed = false
                val shown = HashMap<Any, String>()
                fun watch() {
                    val d = defence.instance as? xyz.znix.xftl.drones.DefenceDrone
                    val ds = "${defence.deployed},${defence.bDead},${defence.powered},${d?.fireReady ?: defence.storedFire},${defence.destroyedTimer > 0f}"
                    if (shown[defence] != ds) { shown[defence] = ds; events += "$frame DEF dep,dead,pow,bFire,timer $ds" }
                    for (c in combats) {
                        val a = c.instance as? xyz.znix.xftl.drones.CombatDrone
                        val cs = "${c.deployed},${c.bDead},${c.lifespan},${(a?.sweepTimer ?: 0f) > 0f}"
                        if (shown[c] != cs) { shown[c] = cs; events += "$frame ${c.type.name} dep,dead,life,sweep $cs" }
                    }
                }
                val drift = ArrayList<String>()
                var lastPos: String? = null; var lastMove = -1; var lastXY: FloatArray? = null
                while (true) {
                    step(); watch()
                    if (defence.deployed && defence.instance != null) loops++
                    if (!destroyed && loops == before) {
                        destroyed = true
                        events += "$frame destroy"
                        defence.setDestroyed(true, true)
                        for (c in combats) if (c.lifespan == Int.MAX_VALUE) c.lifespan = 3
                    }
                    if (destroyed && !defence.deployed && defence.destroyedTimer <= 0f && !defence.bDead) drones.setDronePower(defenceSlot, true)
                    if (killFrame < 0 && loops == after) { killFrame = frame; events += "$frame kill"; game.enemy?.health = 0 }
                    if (killFrame >= 0) {
                        val e = (enemyDrones?.drones?.getOrNull(0)?.instance as? xyz.znix.xftl.drones.AbstractExternalDrone)
                        val fp = e?.flightController?.position
                        val pos = fp?.let { "${it.xf},${it.yf}" }
                        if (pos != null && pos != lastPos) { lastMove = frame - killFrame }
                        // per-frame movement (px) for the first 100 frames after the kill
                        if (fp != null && frame - killFrame <= 100) {
                            val q = lastXY
                            if (q != null) drift += "${frame - killFrame} ${Math.round(Math.hypot((fp.xf - q[0]).toDouble(), (fp.yf - q[1]).toDouble()) * 10000) / 10000.0}"
                            lastXY = floatArrayOf(fp.xf, fp.yf)
                        }
                        lastPos = pos
                        if (frame - killFrame >= 1300) break
                    }
                    if (frame > 20000) break
                }
                events += "enemy drone last move at kill+$lastMove"
                events += drift.joinToString(" ", "drift ")
            }
            return result(trace, events)
        } finally {
            DroneRuntime.trace = null
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * `stun` (native drone-order probe 3, `E/native/order5.json`): counted in the player's deployed defence drone's
     * loops, at [stunAt] every deployed defence/combat drone of both ships gets an ion stun of 2 s; at [jumpAt] the
     * player's drones are recalled as by DroneSystem::Jump (0x637d40; taken off the ship as a jump does, without
     * leaving the beacon); then `rest` = end, (frames after the jump, slot)...: the drones are powered again at those
     * frames (the native session's clicks) and the run ends `end` frames after the jump.
     */
    private fun stunAndJump(
        game: InGameState, drones: Drones, enemyDrones: Drones?, stunAt: Int, jumpAt: Int, rest: List<Int>,
        events: ArrayList<String>, step: () -> Unit, frame: () -> Int
    ) {
        val defence = drones.drones.first { it?.type?.type == DroneBlueprint.DroneType.DEFENSE }!!
        // The native session's enemy fires (console god keeps the player alive): lasers for the defence drone.
        game.debugFlags.noEnemyFire.set = false
        game.debugFlags.noCrewDamage.set = true; game.debugFlags.noDmg.set = true
        val all = (drones.drones + (enemyDrones?.drones ?: emptyList())).filterNotNull()
        val end = rest.getOrElse(0) { 600 }
        val clicks = rest.drop(1).chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
        var loops = 0; var stunFrame = -1; var jumpFrame = -1
        val last = HashMap<Any, FloatArray>(); val drift = HashMap<Any, StringBuilder>(); val shown = HashMap<Any, String>()
        while (true) {
            step()
            val f = frame()
            if (defence.deployed && defence.instance != null) loops++
            for (d in all) {
                val a = d.instance as? xyz.znix.xftl.drones.AbstractExternalDrone
                val who = (if (d in drones.drones) "p" else "e") + " " + d.type.name
                val fire = (a as? xyz.znix.xftl.drones.DefenceDrone)?.fireReady ?: d.storedFire
                val st = "${d.deployed},${d.bDead},${d.powered},$fire,${d.lifespan},${d.destroyedTimer > 0f}"
                if (jumpFrame >= 0 && shown[d] != st) events += "$f $who dep,dead,pow,bFire,life,timer $st"
                shown[d] = st
                val p = a?.flightController?.position
                if (p != null && stunFrame >= 0 && f - stunFrame in -3..140) {
                    val q = last[d]
                    if (q != null) drift.getOrPut(d) { StringBuilder("drift $who") }
                        .append(" ${f - stunFrame}:${Math.round(Math.hypot((p.xf - q[0]).toDouble(), (p.yf - q[1]).toDouble()) * 10000) / 10000.0}/st${Math.round(a.ionStunTimer * 1000) / 1000.0}")
                }
                if (p != null) last[d] = floatArrayOf(p.xf, p.yf) else last.remove(d)
            }
            if (stunFrame < 0 && loops == stunAt) {
                stunFrame = f; events += "$f stun"
                for (d in all) {
                    val a = d.instance as? xyz.znix.xftl.drones.AbstractExternalDrone ?: continue
                    if (d.deployed && (d.type.type == DroneBlueprint.DroneType.DEFENSE || d.type.type == DroneBlueprint.DroneType.COMBAT))
                        a.ionStunTimer = 2f
                }
            }
            if (jumpFrame < 0 && loops == jumpAt) {
                jumpFrame = f; events += "$f jump"
                for (d in drones.drones) {
                    val a = d?.instance as? xyz.znix.xftl.drones.AbstractExternalDrone ?: continue
                    a.targetShip.externalDrones.remove(a)
                }
            }
            if (jumpFrame >= 0) {
                for ((at, slot) in clicks) if (f - jumpFrame == at) events += "$f power slot $slot ${drones.setDronePower(slot, true)}"
                if (f - jumpFrame >= end) break
            }
            if (f > 20000) break
        }
        for (s in drift.values) events += s.toString()
    }

    private fun result(trace: DroneRuntime.Trace, events: List<String>): String =
        "{\"frames\":" + BrowserDebug.quote(trace.out.toString()) +
            ",\"firstMoves\":" + trace.firstMoves.joinToString(",", "[", "]") { BrowserDebug.quote(it) } +
            ",\"beams\":" + trace.beams.joinToString(",", "[", "]") { BrowserDebug.quote(it) } +
            ",\"starts\":" + trace.starts.joinToString(",", "[", "]") { BrowserDebug.quote(it) } +
            ",\"events\":" + events.joinToString(",", "[", "]") { BrowserDebug.quote(it) } + "}"
}
