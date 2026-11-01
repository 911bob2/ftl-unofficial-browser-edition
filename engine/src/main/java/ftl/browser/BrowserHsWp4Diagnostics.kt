package ftl.browser

import xyz.znix.xftl.drones.ShieldDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.weapons.HsShieldDrones
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.DroneBlueprint

/** Replay authored shield blueprints and the oracle's exact input states/dt; diagnostic entry point only. */
object BrowserHsWp4Diagnostics {
    /** Replay only the receiver operation; the oracle obtains its inputs from actual BeamWeapon calls. */
    fun beamRays(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val previousSink = lua.sink
        val fps = xyz.znix.xftl.hs.lua.LuaFps.speedFactor
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            val sourceBody = fixture.getAttributeValue("sourceBody") == "true"
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL); main.update(container, 0f)
            val ship = (main.currentState as InGameState).player; val shields = ship.shields!!
            ship.browserJumping = false
            for (system in ship.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>()) repeat(12) { system.decreasePower() }
            shields.energyLevels = 4
            if (sourceBody) xyz.znix.xftl.hs.lua.LuaFps.speedFactor = fixture.getAttributeValue("dt").toFloat() * 16f
            val edge = ship.shieldOrigin.x.toFloat() - ship.shieldHalfSize.x
            val y = ship.shieldOrigin.y.toFloat()
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 beam receiver Lua error: $text" }
                if (text.startsWith("WP4_R:")) rows += text.removePrefix("WP4_R:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-ray", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(k,v) if k=='wp4_ray_row' then print('WP4_R:'..v.value) " +
                (if (sourceBody) "elseif k=='wp4_ray_source' then print('WP4_R:ray-source|'..v.mode..'|'..tostring(v.checked)..'|'..tostring(v.pierced)..'|'..v.lastDamage..'|'..v.shieldX..'|'..v.shieldY) " else "") + "end end}")
            run(fixture.getChildText("script")); run("WP4RAY.setup();WP4RAY.x=$edge;WP4RAY.y=$y")
            for ((index, node) in fixture.getChildren("case").withIndex()) {
                fun f(name: String) = node.getAttributeValue(name).toFloat()
                val mode = node.getAttributeValue("mode")
                shields.ionTimer = 0f; shields.ionPowerLimit = null
                repeat(4) { shields.increasePower() }
                shields.hsSilent = true
                try { shields.activeShields = 2 } finally { shields.hsSilent = false }
                ship.superShield = f("super").toInt()
                if (sourceBody) {
                    // The native case and its body operations are shared Lua. WP5's direct power/health writes
                    // are replaced only by the equivalent real receiver setup above.
                    run("WP4RAY.case({" + BrowserDebug.quote(mode) + ",${f("sx")},${f("sy")},${f("fx")},${f("fy")},${f("super")},${f("ion")}})")
                    continue
                }
                val damage = xyz.znix.xftl.weapons.Damage.regular(1)
                damage.ionDamage = f("ion").toInt(); damage.hsOwnerId = 1; damage.hsSelfId = 20000 + index
                run("WP4RAY.phase=" + BrowserDebug.quote(mode))
                xyz.znix.xftl.weapons.ShieldCollisionRules.moving(ship,
                    xyz.znix.xftl.math.ConstFPoint(edge + f("sx"), y + f("sy")),
                    xyz.znix.xftl.math.ConstFPoint(edge + f("fx"), y + f("fy")), damage, true) {
                    error("Raytrace must not query GetDodged")
                }
                rows += "ray-state|$mode|${shields.activeShields}|${ship.superShield}|${xyz.znix.xftl.hs.systems.HsSystemsLua.lockCount(shields)}"
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = previousSink; xyz.znix.xftl.hs.lua.LuaFps.speedFactor = fps
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Actual native14 BeamWeapon inputs, including final_end retained between scripted target changes. */
    fun nativeBeam(main: MainGame, container: GameContainer, xml: String): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
        val fixture = BrowserXml.parse(xml).rootElement
        main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        main.update(container, 0f)
        val game = main.currentState as InGameState
        val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(
            xyz.znix.xftl.math.ConstFPoint(20f, 30f), xyz.znix.xftl.math.ConstFPoint(100f, 120f), game.player, 0, 0, 0)
        val actor = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
            // The capture calls the raw native constructor with a null Targetable, not CreateBeam.
            xyz.znix.xftl.weapons.BeamProjectile(game, null, xyz.znix.xftl.math.ConstFPoint(200f, 120f), 100, 0f)
        }
        val body = actor.motion; val tip = body.tip
        val rows = ArrayList<String>()
        for (node in fixture.getChildren("case")) {
            fun f(name: String) = node.getAttributeValue(name).toFloat()
            body.checkedCollision = f("checked") != 0f
            body.lifespan = f("life"); body.length = f("length")
            tip.set(f("x"), f("y")); body.target1.set(100f, 120f); body.target2.set(f("endX"), f("endY"))
            body.dh = .5f; body.animationTimer = 1f; body.damageSuperShield = false
            actor.setRawSpeed(f("speed")); actor.setBeamHeading(90f); actor.hsDead = f("dead") != 0f
            actor.nativeUpdate(.015625f, game.player)
            val heading = actor.nativeHeading
            rows += "${node.getAttributeValue("id")}|${tip.xf}|${tip.yf}|${body.finalEnd.xf}|${body.finalEnd.yf}|" +
                "${body.lifespan}|$heading|${body.animationTimer}|${body.damageSuperShield}|${actor.nativeDead(.015625f)}"
        }
        for (node in fixture.getChildren("entry")) {
            val index = node.getAttributeValue("index").toInt()
            val p = xyz.znix.xftl.weapons.BeamMotion.entryPoint(index, node.getAttributeValue("draw").toInt())
            rows += "entry|$index|${p.xf}|${p.yf}"
        }
        if (fixture.getAttributeValue("constructor") == "true")
            rows += "raw-color|${actor.nativeColor.r}|${actor.nativeColor.g}|${actor.nativeColor.b}|${actor.nativeColor.a}"
        return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally { BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu() }
    }

    /** Native14's zero-factor GetDodged capture checks that the combat generator is untouched. */
    fun nativeDodge(main: MainGame, container: GameContainer): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val ship = (main.currentState as InGameState).player
            ship.browserJumping = false
            for (system in ship.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>())
                repeat(12) { system.decreasePower() }
            val factor = ship.dodgeFactor
            check(factor == 0 && !ship.isCloakActive)
            val before = BrowserRandom.snapshot()
            val dodged = ship.pickMissed()
            return "{\"factor\":$factor,\"cloaked\":${ship.isCloakActive},\"dodged\":$dodged,\"noDraw\":${before == BrowserRandom.snapshot()}}"
        } finally { BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu() }
    }

    /** Real native14 receiver inputs; horizontal points are rebased to this fixture ship's shield edge. */
    fun nativeShields(main: MainGame, container: GameContainer, xml: String): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            val ship = game.player; val shields = ship.shields!!
            for (system in ship.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>())
                repeat(12) { system.decreasePower() }
            shields.energyLevels = 4
            val edge = ship.shieldOrigin.x.toFloat() - ship.shieldHalfSize.x
            val y = ship.shieldOrigin.y.toFloat()
            val rows = ArrayList<String>()
            for (node in fixture.getChildren("case")) {
                fun i(name: String) = node.getAttributeValue(name).toInt()
                fun f(name: String) = node.getAttributeValue(name).toFloat()
                shields.ionTimer = 0f; shields.ionPowerLimit = null
                repeat(4) { shields.increasePower() }
                shields.hsSilent = true
                try { shields.activeShields = i("layers") } finally { shields.hsSilent = false }
                ship.superShield = i("super")
                val damage = xyz.znix.xftl.weapons.Damage.regular(i("hull"))
                damage.ionDamage = i("ion"); damage.hsShieldPiercing = i("pierce"); damage.hsSelfId = i("selfId")
                val point = xyz.znix.xftl.math.ConstFPoint(edge + f("dx"), y + f("dy"))
                val test = xyz.znix.xftl.weapons.ShieldCollisionRules.test(ship, point)
                val result = if (i("kind") == 0) xyz.znix.xftl.weapons.ShieldCollisionRules.real(ship, point, damage, i("force") != 0)
                else {
                    val start = xyz.znix.xftl.math.ConstFPoint(edge - 3f, y)
                    xyz.znix.xftl.weapons.ShieldCollisionRules.moving(ship, start, point, damage, true) { false }
                }
                val dx = if (result.type == 0) result.point.xf else result.point.xf - edge
                val dy = if (result.type == 0) result.point.yf else result.point.yf - y
                rows += "${i("id")}|${test.type}|${result.type}|$dx|$dy|${result.damage}|${result.superDamage}|" +
                    "${shields.activeShields}|${ship.superShield}|${xyz.znix.xftl.hs.systems.HsSystemsLua.lockCount(shields)}"
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally { BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu() }
    }

    /** Native real-flight inputs, replayed through the same shield operation that world simulation uses. */
    fun shieldCollisions(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            val dodge = fixture.getAttributeValue("kind") == "dodge"
            val namespace = if (dodge) "WP4DF" else "WP4SH"
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player; val shields = ship.shields!!
            // The oracle hits after startup flight finishes; GetDodged unconditionally rejects a jumping ship.
            ship.browserJumping = false
            for (system in ship.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>())
                repeat(12) { system.decreasePower() }
            shields.energyLevels = 4
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 shield Lua error: $text" }
                if (text.startsWith("WP4_S:")) rows += text.removePrefix("WP4_S:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-shields", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='" + (if (dodge) "wp4_df_row" else "wp4_shield_row") + "' then print('WP4_S:'..v.value) end end}")
            run(fixture.getChildText("script")); run("$namespace.setup()")
            for (node in fixture.getChildren("case")) {
                fun f(name: String) = node.getAttributeValue(name).toFloat()
                val mode = node.getAttributeValue("mode")
                shields.ionTimer = 0f; shields.ionPowerLimit = null
                repeat(4) { shields.increasePower() }
                shields.hsSilent = true
                try { shields.activeShields = 2 } finally { shields.hsSilent = false }
                check(shields.activeShields == 2) { "Could not establish native two-layer shield state" }
                ship.superShield = f("super").toInt()
                check(ship.shieldOrigin.x.toFloat() == f("centerX") && ship.shieldOrigin.y.toFloat() == f("centerY") &&
                    ship.shieldHalfSize.x.toFloat() == f("a") && ship.shieldHalfSize.y.toFloat() == f("b")) { "Shield geometry differs from native" }
                val finish = xyz.znix.xftl.math.ConstFPoint(f("x") + 3f, f("y"))
                val start = xyz.znix.xftl.math.ConstFPoint(f("x") + if (node.getAttributeValue("inside") == "true") 3f else -3f, f("y"))
                val target = xyz.znix.xftl.math.ConstFPoint(f("x") + 300f, f("y"))
                val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(finish, target, ship, 1)
                val p = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                    xyz.znix.xftl.weapons.LaserBlastProjectile(game.blueprintManager["LASER_BURST_2"] as xyz.znix.xftl.weapons.AbstractWeaponBlueprint,
                        ship.rooms.first())
                }
                val state = p.hsExt!!
                state.damage.hullDamage = f("hull").toInt(); state.damage.ionDamage = f("ion").toInt()
                state.damage.hsShieldPiercing = f("pierce").toInt(); state.damage.hsOwnerId = 1
                state.lastPosition.set(start)
                node.getAttributeValue("accuracy")?.let { state.accuracyMod = it.toInt() }
                run("$namespace.phase=" + BrowserDebug.quote(mode))
                xyz.znix.xftl.weapons.ProjectileRuntime.collisionCheck(p, ship)
                rows += "source|$mode|${state.startedDeath}|${state.missed}|${state.hitTarget}|${state.damage.hullDamage}|${state.damage.ionDamage}|${state.damage.hsShieldPiercing}"
                rows += "state|$mode|${shields.activeShields}|${ship.superShield}|${xyz.znix.xftl.hs.systems.HsSystemsLua.lockCount(shields)}|${ship.health}"
                p.hsKill()
                if (dodge) run("WP4DF.inCollision=false")
            }
            if (dodge) {
                for ((mode, draws) in listOf("gate-zero" to 0, "second-high" to 1, "second-zero" to 1)) {
                    val before = BrowserRandom.snapshot()
                    repeat(draws) { BrowserRandom.nextInt() }
                    val expected = BrowserRandom.snapshot()
                    BrowserRandom.restore(before)
                    run("WP4DF.directCase(" + BrowserDebug.quote(mode) + ",false)")
                    check(BrowserRandom.snapshot() == expected) { "Dodge RNG draw count differs in $mode" }
                }
                run("WP4DF.outside()")
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Oracle inputs replayed through real boarding launches and HackingSystem's owned flight body. */
    fun podCollisions(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame("PLAYER_SHIP_CIRCLE", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            game.debugSpawnShip(game.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
            game.debugFlags.noEnemyFire.set = true
            if (game.player.hacking == null) {
                val slot = game.player.systemSlots.first { it.system.name == "hacking" }
                slot.room.setSystem(slot)
            }
            val hacking = game.player.hacking!!
            game.player.dronesCount = 100
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 pod Lua error: $text" }
                if (text.startsWith("WP4_P:")) rows += text.removePrefix("WP4_P:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-pods", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='wp4_pod_row' then print('WP4_P:'..v.value) end end}")
            run(fixture.getChildText("script")); run("WP4POD.setup()")
            for (node in fixture.getChildren("case")) {
                fun b(name: String) = node.getAttributeValue(name) == "true"
                val kind = node.getAttributeValue("kind")
                val mode = node.getAttributeValue("mode")
                val boarder = if (kind == "boarder") {
                    (game.blueprintManager["BOARDER"] as DroneBlueprint).makeInstance()
                        .also { it.init(game.player); it.isPowered = b("powered") } as xyz.znix.xftl.drones.BoardingDrone
                } else null
                val body = if (boarder != null) {
                    boarder.hsSelfId = node.getAttributeValue("droneId").toInt()
                    boarder.hsPodDeployed = b("deployed"); boarder.hsPodDelivered = b("arrived")
                    boarder.hsCollisionBody!!
                } else {
                    hacking.removeProbe()
                    while (hacking.powerSelected > 0) hacking.decreasePower()
                    hacking.increasePower()
                    hacking.selectTarget(game.enemy!!.weapons!!.room!!); hacking.update(0f)
                    check(hacking.droneLaunched) { "Hacking pod did not launch" }
                    hacking.hsCollisionBody!!.also {
                        it.arrived = b("arrived")
                        if (!b("powered")) hacking.decreasePower()
                        if (!b("deployed")) hacking.removeProbe()
                    }
                }
                (body.collisionPosition as xyz.znix.xftl.math.MutFPoint).set(
                    node.getAttributeValue("x").toFloat(), node.getAttributeValue("y").toFloat())
                check(body.collisionSpaceId == node.getAttributeValue("spaceId").toInt())
                val target = xyz.znix.xftl.math.ConstFPoint(body.collisionPosition.xf + 500f, body.collisionPosition.yf)
                val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(xyz.znix.xftl.math.ConstFPoint(body.collisionPosition.xf, body.collisionPosition.yf), target, game.player, 1)
                val p = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                    xyz.znix.xftl.weapons.LaserBlastProjectile(game.blueprintManager["LASER_BURST_2"] as xyz.znix.xftl.weapons.AbstractWeaponBlueprint,
                        game.player.rooms.first())
                }
                val state = p.hsExt!!
                state.damage.hullDamage = node.getAttributeValue("hull").toInt()
                state.damage.ionDamage = node.getAttributeValue("ion").toInt()
                state.droneAccuracyMod = if (mode == "overshoot") 1 else 0
                run("WP4POD.phase=" + BrowserDebug.quote("$kind-$mode"))
                xyz.znix.xftl.weapons.ProjectileRuntime.collisionCheck(p, body)
                rows += "result|$kind-$mode|${body.collisionSpaceId}|0|${state.startedDeath}|${state.missed}|" +
                    "${body.collisionIonStun}|${body.collisionExploding}|${state.damage.hsSelfId == state.selfId}|${state.missedDrones.size}"
                p.hsKill(); boarder?.removeInstance(); hacking.removeProbe()
                game.player.projectiles.clear(); game.enemy!!.projectiles.clear()
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Same callback Lua and recorded inputs as native CollisionMoving, against actual deployed drone bodies. */
    fun droneCollisions(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 drone collision Lua error: $text" }
                if (text.startsWith("WP4_D:")) rows += text.removePrefix("WP4_D:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-drone-collision", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='wp4_drone_collision_row' then print('WP4_D:'..v.value) end end}")
            run(fixture.getChildText("script")); run("WP4D.setup()")
            for (node in fixture.getChildren("case")) {
                fun f(name: String) = node.getAttributeValue(name).toFloat()
                val drone = (game.blueprintManager["HS_ORACLE_WP4_SHIELD"] as DroneBlueprint).makeInstance()
                    as xyz.znix.xftl.drones.AbstractExternalDrone
                drone.init(game.player)
                val source = node.getAttributeValue("source") == "true"
                drone.isPowered = source || node.getAttributeValue("powered") == "true"
                drone.ionStunTimer = if (source) 0f else f("initialStun")
                drone.flightController.position.set(f("x"), f("y"))
                run("WP4D.phase=" + BrowserDebug.quote(node.getAttributeValue("phase")))
                if (source) {
                    drone.hsSelfId = node.getAttributeValue("droneId").toInt()
                    val position = xyz.znix.xftl.math.ConstFPoint(f("x"), f("y"))
                    val target = xyz.znix.xftl.math.ConstFPoint(f("x") + 500f, f("y"))
                    val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(position, target, game.player, 1)
                    val p = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                        xyz.znix.xftl.weapons.LaserBlastProjectile(game.blueprintManager["LASER_BURST_2"] as xyz.znix.xftl.weapons.AbstractWeaponBlueprint,
                            game.player.rooms.first())
                    }
                    p.hsExt!!.droneAccuracyMod = f("accuracy").toInt()
                    if (node.getAttributeValue("prefill") == "true") p.hsExt!!.missedDrones.add(drone.hsSelfId)
                    xyz.znix.xftl.weapons.ProjectileRuntime.collisionCheck(p, drone)
                    if (node.getAttributeValue("repeatHit") == "true") {
                        p.hsExt!!.droneAccuracyMod = 0
                        xyz.znix.xftl.weapons.ProjectileRuntime.collisionCheck(p, drone)
                    }
                    val state = p.hsExt!!
                    rows += "source|${node.getAttributeValue("tag")}|${state.startedDeath}|${state.missed}|${state.hitTarget}|" +
                        "${drone.ionStunTimer}|${drone.isBlowingUp}|${state.damage.hsSelfId == state.selfId}|${state.missedDrones.size}"
                    p.hsKill()
                } else {
                    val damage = xyz.znix.xftl.weapons.Damage.regular(f("hull").toInt())
                    damage.ionDamage = f("ion").toInt()
                    val response = xyz.znix.xftl.hs.weapons.HsDrones.collisionMoving(drone, drone.flightController.position, damage)
                    rows += "result|${node.getAttributeValue("tag")}|${response.type}|${drone.ionStunTimer}|${drone.isBlowingUp}|${damage.hullDamage}|${damage.ionDamage}"
                }
                drone.isPowered = false; drone.removeInstance()
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Actual deployed/landed controllable ion drone, with its regular crew and game loops. */
    fun ionControl(main: MainGame, container: GameContainer): String {
        check(HS.active)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_CIRCLE", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.debugSpawnShip(game.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
            game.debugFlags.noEnemyFire.set = true; game.debugFlags.noCrewDamage.set = true
            val drones = game.player.drones!!
            for (slot in drones.drones.indices) drones.setDronePower(slot, false)
            drones.energyLevels = 3
            drones.drones[0] = xyz.znix.xftl.systems.Drones.DroneInfo(game.blueprintManager["BOARDER_ION"] as DroneBlueprint)
            check(drones.setDronePower(0, true))
            var pulse = false
            repeat(600) {
                game.updateGameState(.05f)
                if (game.enemy?.systems?.any { it.ionTimer > 0f } == true) pulse = true
            }
            val pawn = (drones.drones[0]!!.instance as xyz.znix.xftl.drones.BoardingDrone).pawn
            return "{\"controlled\":${pawn?.playerControllable == true},\"landed\":${pawn != null},\"pulse\":$pulse}"
        } finally { BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu() }
    }

    /** Replay the inputs of actual 1.6.14 Projectile::OnUpdate and CollisionMoving calls. */
    fun nativeProjectiles(main: MainGame, container: GameContainer, xml: String): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            fun body(speed: Int) = object : xyz.znix.xftl.weapons.AbstractProjectile(game.player) {
                override val speed = speed
                override val antiDroneBP: xyz.znix.xftl.weapons.AbstractWeaponBlueprint? = null
                override val antiDroneExemption: xyz.znix.xftl.Ship? = null
                override val serialisationType = "diagnostic-native-projectile"
                override fun renderPreTranslated(g: xyz.znix.xftl.rendering.Graphics) {}
                override fun reachedTarget() {}
                override fun hitOtherProjectile(currentSpace: xyz.znix.xftl.Ship) {}
            }.also { it.hsCurrentSpace = game.player }
            val motion = ArrayList<String>()
            for (node in fixture.getChildren("motion")) {
                fun f(name: String) = node.getAttributeValue(name).toFloat()
                val body = body((f("speed") * 16).toInt())
                val before = xyz.znix.xftl.math.ConstFPoint(f("x"), f("y"))
                body.setInitialPath(before, xyz.znix.xftl.math.ConstFPoint(f("targetX"), f("targetY")))
                body.hsExt?.let { it.speedMagnitude = f("speed"); it.initialized = true }
                body.nativeMotionStep(f("dt"))
                motion += "{\"x\":${body.position.xf},\"y\":${body.position.yf}," +
                    "\"dx\":${body.position.xf - before.xf},\"dy\":${body.position.yf - before.yf}," +
                    "\"passed\":${body.nativePassedTarget},\"atTarget\":${body.nativeAtTarget(f("dt"))}}"
            }
            val contact = fixture.getChildren("contact").map { node ->
                fun f(name: String) = node.getAttributeValue(name).toFloat()
                xyz.znix.xftl.weapons.IProjectile.endpointContact(
                    xyz.znix.xftl.math.ConstFPoint(f("finishX"), f("finishY")),
                    xyz.znix.xftl.math.ConstFPoint(f("targetX"), f("targetY"))).toString()
            }
            val lifetime = ArrayList<String>()
            if (fixture.getChild("lifetime") != null) {
                val body = body(60 * 16)
                body.setInitialPath(xyz.znix.xftl.math.ConstPoint(100, 100), xyz.znix.xftl.math.ConstPoint(200, 20))
                val animation = body.deathAnimation
                animation.time = .03f; animation.running = true
                for (i in 0 until 6) {
                    val dead = if (i < 4) body.nativeDead(.015625f) else {
                        body.setInitialPath(xyz.znix.xftl.math.ConstPoint(20, 20), xyz.znix.xftl.math.ConstPoint(200, 20))
                        body.hsDead = i == 4; animation.running = i == 5
                        body.nativeMotionStep(.015625f)
                        false
                    }
                    lifetime += "{\"result\":$dead,\"dead\":${body.hsDead},\"running\":${animation.running}," +
                        "\"done\":${animation.done},\"time\":${animation.currentTime},\"x\":${body.position.xf},\"y\":${body.position.yf}}"
                }
            }
            return "{\"motion\":${motion.joinToString(",", "[", "]")},\"contact\":${contact.joinToString(",", "[", "]")}" +
                (if (lifetime.isNotEmpty()) ",\"lifetime\":${lifetime.joinToString(",", "[", "]")}" else "") + "}"
        } finally { BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu() }
    }

    /** Same Lua body and recorded counter/FPS input as the native lifecycle probe. Diagnostic starts skip hangar. */
    fun projectileHooks(main: MainGame, container: GameContainer, xml: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val counter = xyz.znix.xftl.hs.crew.HsCrew.spaceIdState()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val oldSink = lua.sink
        val fps = xyz.znix.xftl.hs.lua.LuaFps.speedFactor
        val beamEntry = xyz.znix.xftl.weapons.BeamProjectile.entryIndex()
        val rows = ArrayList<String>()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            val bodyKind = fixture.getAttributeValue("bodyKind")
            val namespace = when (bodyKind) { "beam", "cross-body" -> "WP4BEAM"; "ray-body" -> "WP4RAY"; "families" -> "WP4F"; else -> "WP4P" }
            val event = when (bodyKind) { "beam", "cross-body" -> "wp4_beam_row"; "ray-body" -> "wp4_ray_row"; "families" -> "wp4_family_row"; else -> "wp4_projectile_row" }
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            xyz.znix.xftl.hs.crew.HsCrew.resetIds(fixture.getAttributeValue("selfId").toInt() - 1)
            xyz.znix.xftl.hs.lua.LuaFps.speedFactor = fixture.getAttributeValue("dt").toFloat() * 16f
            if (bodyKind == "cross-body") xyz.znix.xftl.weapons.BeamProjectile.restoreEntryIndex(0)
            // The oracle's ship finished its arrival long ago; a fresh diagnostic game is still in its jump-in
            // (ShipManager::bJumping), which makes every DamageArea roll and miss.
            if (bodyKind == "families") game.player.browserJumping = false
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 projectile Lua error: $text" }
                if (text.startsWith("WP4_P:")) rows += text.removePrefix("WP4_P:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-projectile-probe", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='$event' then print('WP4_P:'..v.value) " +
                "elseif kind=='wp4_ray_source' then print('WP4_P:ray-source|'..v.mode..'|'..tostring(v.checked)..'|'..tostring(v.pierced)..'|'..v.lastDamage..'|'..v.shieldX..'|'..v.shieldY) " +
                "elseif kind=='wp4_beam_cross' then print('WP4_P:cross-meta|'..v.index..'|'..v.x..'|'..v.y..'|'..v.endX..'|'..v.endY..'|'..v.current..'|'..v.destination..'|'..v.owner..'|'..v.movingId..'|'..tostring(v.noTarget)..'|'..v.r..'|'..v.g..'|'..v.b..'|'..v.a) end end}")
            run(fixture.getChildText("script"))
            // Shapes' full mutable Lua surface belongs to WP5. Supply this independent receiver setup from
            // the actual ship geometry; the probe still creates and updates the real Lua BeamWeapon body.
            val argument = if (bodyKind == "ray-body") "{center={x=${game.player.shieldOrigin.x},y=${game.player.shieldOrigin.y}}," +
                "a=${game.player.shieldHalfSize.x},b=${game.player.shieldHalfSize.y}}" else ""
            run("$namespace." + (if (bodyKind == "cross-body") "crossNative" else "runNative") + "($argument)")
            if (bodyKind == "families") {
                // Part 4 over frames: drawn frames of the paused game (each after its tick, as CApp runs them),
                // then a crew fight stepped until its first crew laser.
                val graphics = BrowserEngine.diagnosticGraphics()
                run("WP4F.flashBegin()")
                repeat(40) { xyz.znix.xftl.hs.lua.LuaEvents.onTick(); main.render(container, graphics) }
                run("WP4F.flashEnd()")
                run("WP4F.crewBegin()")
                var frames = 0
                while (frames < 1200 && rows.none { it.startsWith("crew-construct|") }) {
                    main.update(container, 1f / 60f); frames++
                }
                val crewInfo = "frames=$frames paused=${game.isPaused} playerPaused=${game.isPlayerPaused} crew=" + game.player.crew.joinToString(";") {
                    "${it.javaClass.simpleName}@${it.room.id}/${it.mode}/${(it as? xyz.znix.xftl.crew.LivingCrew)?.ownerShip?.isPlayerShip}/${it.health}/${it.standingShipHostile}"
                }
                run("WP4F.crewEnd()")
                if (rows.none { it.startsWith("crew-construct|") }) run("HS_ORACLE.emit('wp4_family_row', {value='crew-debug|$crewInfo'})")
            }
            check(rows.isNotEmpty())
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            xyz.znix.xftl.hs.lua.LuaFps.speedFactor = fps
            xyz.znix.xftl.weapons.BeamProjectile.restoreEntryIndex(beamEntry)
            xyz.znix.xftl.hs.crew.HsCrew.resetIds(counter)
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Run the oracle's authored callbacks in the real Lua VM, driving the corresponding gameplay operations. */
    fun weaponHooks(main: MainGame, container: GameContainer, script: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            if (!game.isPaused) game.toggleQuickPause()
            fun run(code: String) { check(lua.runString("wp4-hooks-probe", code) == null) { "WP4 callback probe failed: $code" } }
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 callback error: $text" }
                if (text.startsWith("WP4_HOOKS:")) rows += text.removePrefix("WP4_HOOKS:").trimEnd().split('\n')
            }
            run(script); run("WP4Hooks.setup()")
            run("WP4Hooks.dodgeCase('fraction', 'keep', 37.9)")
            run("WP4Hooks.dodgeCase('preempt', 'preempt', -12.8)")
            run("WP4Hooks.systemCase('integer', 'keep', 1, 2)")
            run("WP4Hooks.systemCase('fraction', 'keep', 1, 1.5)")
            run("WP4Hooks.systemCase('zero-input', 'keep', 0, 2)")
            run("WP4Hooks.systemCase('overkill', 'keep', 1, 99)")
            run("WP4Hooks.systemCase('repair', 'keep', 1, -99)")
            run("WP4Hooks.systemCase('preempt', 'preempt', 1, 2)")
            for ((tag, mode, replacement) in listOf(Triple("normal", "keep", "0"), Triple("preempt", "preempt", "0"),
                Triple("invalid", "keep", "-1"), Triple("fraction", "keep", "0.9"))) {
                run("WP4Hooks.begin('select', '$tag', '$mode', $replacement)")
                game.shipUI.weaponHotkeyPressed(0, false)
                run("WP4Hooks.selectResult(${game.player.hardpoints[0].weapon!!.isPowered})")
            }
            run("WP4Hooks.results()")
            check(rows.isNotEmpty())
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.runString("wp4-hooks-end", "if WP4Hooks then WP4Hooks.phase=nil end")
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Replay the three native 1.6.14 actors' recorded initial slots, then the same away-save and station swap. */
    fun crewStations(main: MainGame, container: GameContainer, xml: String): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            val player = game.player
            val crew = player.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().take(3)
            check(crew.size == 3)
            val initial = fixture.getChildren("crew").map { node ->
                val room = player.rooms[node.getAttributeValue("room").toInt()]
                xyz.znix.xftl.math.RoomPoint(room, room.slotToPoint(node.getAttributeValue("slot").toInt()))
            }
            check(initial.size == 3)
            for (i in crew.indices) crew[i].jumpTo(initial[i])
            player.saveCrewPositions()
            // The native instrumented SaveCrewPositions marks the third actor away for its ClearPosition branch.
            val away = xyz.znix.xftl.Ship(player.type, game, null, null, null)
            player.crew.remove(crew[2]); away.crew.add(crew[2]); crew[2].jumpTo(away.rooms[0], away.rooms[0].slotToPoint(0))
            player.saveCrewPositions()
            check(crew[2].savedPosition == null) { "Away crew station was retained" }
            away.crew.remove(crew[2]); player.crew.add(crew[2]); crew[2].jumpTo(initial[2])
            crew[0].savedPosition = initial[1].shipPoint
            crew[1].savedPosition = initial[0].shipPoint
            player.loadCrewPositions()
            return crew.joinToString(",", "[", "]") { member ->
                val goal = member.currentSlotPoint
                val saved = member.savedPosition?.let { player.shipToRoomPos(it) }
                "{\"room\":${goal.room.id},\"slot\":${goal.room.pointToSlot(goal)}," +
                    "\"savedRoom\":${saved?.room?.id ?: -1},\"savedSlot\":${saved?.let { it.room.pointToSlot(it) } ?: -1}}"
            }
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** Same paused input sequence as wp4-controls.json; configuration/blueprints are the oracle's fixture XML. */
    fun droneControls(main: MainGame, container: GameContainer): String {
        check(HS.active)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            val player = game.player
            val ui = game.shipUI
            ui.diagnosticCloseWindow()
            if (!game.isPaused) game.toggleQuickPause()
            player.purchasedReactorPower = 25
            if (player.drones == null) xyz.znix.xftl.hs.systems.HsSystemInstall.install(player,
                player.systemSlots.first { it.system.name == "drones" })
            val drones = player.drones!!
            player.dronesCount += 20
            for ((i, name) in listOf("HS_ORACLE_WP4_CONTROL", "REPAIR").withIndex())
                drones.drones[i] = xyz.znix.xftl.systems.Drones.DroneInfo(game.blueprintManager[name] as DroneBlueprint)
            val results = ArrayList<String>()
            fun snapshot(tag: String) {
                val crew = player.crew.map { c ->
                    val slot = c.currentSlotPoint
                    val saved = c.getReportedStation()?.let { player.shipToRoomPos(it) }
                    val stored = c.savedPosition?.let { player.shipToRoomPos(it) }
                    val finalGoal = c.hsFinalGoal?.let { player.shipToRoomPos(it) }
                    "{\"species\":${BrowserDebug.quote(c.codename)},\"drone\":${c is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn}," +
                        "\"ship\":0,\"controlled\":${c.playerControllable},\"room\":${slot.room.id},\"slot\":${slot.room.pointToSlot(slot)}," +
                        "\"savedRoom\":${saved?.room?.id ?: -1},\"savedSlot\":${saved?.let { it.room.pointToSlot(it) } ?: -1}," +
                        "\"storedRoom\":${stored?.room?.id ?: -1},\"storedSlot\":${stored?.let { it.room.pointToSlot(it) } ?: -1}," +
                        "\"goalRoom\":${finalGoal?.room?.id ?: -1},\"goalSlot\":${finalGoal?.let { it.room.pointToSlot(it) } ?: -1}}"
                }
                val selected = ui.luaSelectedCrew.joinToString(",", "[", "]") { BrowserDebug.quote(it.codename) }
                results += "{\"tag\":${BrowserDebug.quote(tag)},\"crew\":${crew.joinToString(",", "[", "]")},\"selected\":$selected}"
            }
            fun select(slot: Int, tag: String) {
                ui.luaSelectedCrew.clear(); ui.luaHoveredCrew.clear()
                ui.luaSelectedCrew += player.crew.first { it is xyz.znix.xftl.crew.LivingCrew }
                ui.droneHotkeyPressed(slot, false)
                snapshot(tag)
            }
            snapshot("added unpowered"); snapshot("after inventory loop")
            select(0, "first power controlled"); select(0, "repower controlled"); select(1, "power automatic")
            player.saveCrewPositions(); snapshot("saved")
            (drones.drones[0]!!.instance as xyz.znix.xftl.drones.AbstractIndoorsDrone).pawn!!.setTargetRoom(player.rooms[0])
            snapshot("moved goal")
            player.loadCrewPositions(); snapshot("restored")
            ui.droneHotkeyPressed(0, true)
            snapshot("power off before saving")
            player.saveCrewPositions(); snapshot("saved after power off")
            return results.joinToString(",", "[", "]")
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    fun shieldDrones(main: MainGame, container: GameContainer, xml: String): String {
        val random = BrowserRandom.snapshot()
        val native = NativeRandom.snapshot()
        try {
            val fixture = BrowserXml.parse(xml).rootElement
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val blueprints = fixture.getChildren("droneBlueprint").associate { node ->
                if (HS.active) HsShieldDrones.parse(node)
                val bp = DroneBlueprint(node)
                bp.name to bp
            }
            val game = main.currentState as InGameState
            val player = game.player
            val results = ArrayList<String>()
            for (case in fixture.getChildren("case")) {
                fun field(name: String) = case.getAttributeValue(name) ?: error("missing shield case $name")
                val type = blueprints[field("name")] ?: error("missing shield blueprint")
                player.maxSuperShield = 5
                player.superShield = field("layers").toInt()
                val drone = ShieldDrone(type)
                drone.init(player)
                try {
                    drone.isPowered = field("powered").toBoolean()
                    drone.rechargeRemaining = field("cooldown").toFloat()
                    drone.glowTime = field("glow").toFloat()
                    drone.ionStunTimer = field("stun").toFloat()
                    drone.update(field("dt").toFloat())
                    results += "{\"tag\":${BrowserDebug.quote(field("tag"))}," +
                        "\"cooldown\":${drone.rechargeRemaining},\"glow\":${drone.glowTime}," +
                        "\"layers\":${player.superShield},\"speed\":${drone.shieldOrbitSpeedScale * (type.speed ?: 0)}," +
                        "\"nextCooldown\":${drone.cooldown()},\"stun\":${drone.ionStunTimer}}"
                } finally {
                    drone.removeInstance()
                }
            }
            return results.joinToString(",", "[", "]")
        } finally {
            BrowserRandom.restore(random)
            NativeRandom.restore(native)
            main.switchToMainMenu()
        }
    }
}
