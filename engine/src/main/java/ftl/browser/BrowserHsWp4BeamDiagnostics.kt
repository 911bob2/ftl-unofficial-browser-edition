package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sys.GameContainer

/** WP4 beam slice diagnostics (authored probes replayed in the real engine); diagnostic entry points only. */
object BrowserHsWp4BeamDiagnostics {
    val commands = setOf("beam-damage", "beam-damage-native", "armament-surface", "beam-render", "factory-queue", "beam-frame",
        "beam-frame-vanilla", "power-save")

    fun run(main: MainGame, container: GameContainer, command: String, argument: String): String = when (command) {
        "beam-damage" -> beamDamage(main, container, argument)
        "beam-damage-native" -> nativeDamageBeam(main, container, argument)
        "armament-surface" -> armamentSurface(main, container, argument)
        "beam-render" -> beamRender(main, container)
        "factory-queue" -> factoryQueue(main, container, argument)
        "beam-frame" -> beamFrame(main, container, argument)
        "beam-frame-vanilla" -> beamFrameVanilla(main, container, argument)
        "power-save" -> powerSave(main, container)
        else -> error("unknown beam diagnostic $command")
    }

    /**
     * Draws a real one-space beam body (SpaceManager:CreateBeam, enemy-owned, onto the player ship without shields)
     * on black with an identity transform and reads the pixels back: nothing before its first CollisionCheck; then
     * the blueprint's GL_Color at the centre line of sub_start -> final_end, black 5 px to the side (6 px wide at
     * damage 1) and black past the beam's tip.
     */
    private fun beamRender(main: MainGame, container: GameContainer): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua
        val graphics = lua.graphics ?: error("no graphics")
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player
            ship.browserJumping = false
            ship.superShield = 0
            ship.shields?.let { it.damagedEnergyLevels = it.energyLevels; it.activeShields = 0 }
            game.debugFlags.noDmg.set = true
            val error = lua.runString("wp4-beam-render", "WP4RENDER=Hyperspace.App.world.space:CreateBeam(" +
                "Hyperspace.Blueprints:GetWeaponBlueprint('BEAM_1'),Hyperspace.Pointf(40,640),0,1," +
                "Hyperspace.Pointf(300,220),Hyperspace.Pointf(700,220),0,400,0)")
            check(error == null) { error!! }
            val beam = ship.projectiles.filterIsInstance<xyz.znix.xftl.weapons.BeamProjectile>().last()
            fun draw(): (Int, Int) -> IntArray {
                org.lwjgl.opengl.GL11.glClearColor(0f, 0f, 0f, 1f)
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT)
                graphics.markCurrentImageTransformSource()
                graphics.pushTransform()
                try { graphics.loadIdentityMatrix(); beam.render(graphics, ship) } finally { graphics.popTransform() }
                return { x, y -> BrowserPixels.read(x, y, 1, 1).let { p -> IntArray(4) { p[it].toInt() and 255 } } }
            }
            fun black(p: IntArray) = p[0] < 8 && p[1] < 8 && p[2] < 8
            val before = draw()
            val m = beam.motion
            check(!m.checkedCollision && black(before(500, 220)) && black(before(170, 430))) { "beam drew before CollisionCheck" }
            repeat(120) { xyz.znix.xftl.weapons.ProjectileRuntime.advanceFrame(game, 1f / 60f) }
            check(m.checkedCollision && m.piercedShield && beam in ship.projectiles) { "beam did not reach CollisionCheck" }
            val pixel = draw()
            val ax = m.subStart.xf; val ay = m.subStart.yf; val bx = m.finalEnd.xf; val by = m.finalEnd.yf
            val length = kotlin.math.hypot(bx - ax, by - ay)
            check(length > 60f) { "short beam segment $ax,$ay -> $bx,$by" }
            // A quarter along from sub_start, clear of the contact animations at final_end - (24, 33).
            val sx = ax + (bx - ax) * .25f; val sy = ay + (by - ay) * .25f
            val nx = -(by - ay) / length; val ny = (bx - ax) / length
            // The brightest pixel across the centre line (the gradient peaks at the exact centre).
            val centre = (-6..6).map { k -> pixel((sx + nx * k * .5f).roundToInt(), (sy + ny * k * .5f).roundToInt()) }
                .maxBy { it[0] + it[1] + it[2] }
            val side = pixel((sx + nx * 6f).roundToInt(), (sy + ny * 6f).roundToInt())
            val past = pixel(((m.target2.xf + bx) / 2f).roundToInt().coerceIn(0, 1279), ((m.target2.yf + by) / 2f).roundToInt())
            val c = beam.nativeColor
            fun near(p: IntArray) = kotlin.math.abs(p[0] - c.r) <= 40 && kotlin.math.abs(p[1] - c.g) <= 40 && kotlin.math.abs(p[2] - c.b) <= 40
            check(near(centre)) { "centre ${centre.toList()} not the beam colour ${c.r},${c.g},${c.b}" }
            check(black(side)) { "beam wider than 6 px: ${side.toList()}" }
            check(black(past)) { "beam drawn past its tip: ${past.toList()}" }
            return "PASS beam render: sub_start ${ax.roundToInt()},${ay.roundToInt()} -> final_end ${bx.roundToInt()},${by.roundToInt()} " +
                "centre ${centre.toList()} (GL_Color ${c.r.roundToInt()},${c.g.roundToInt()},${c.b.roundToInt()}), side ${side.toList()}, past tip ${past.toList()}"
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    private fun Float.roundToInt() = kotlin.math.round(this).toInt()

    /**
     * Save and reload with the backup battery on and weapons powered from it: the same weapons stay powered, with
     * the same weapon system power and spare reactor (ShipManager::ImportShip 0x4b3ce6 powers each saved-powered
     * weapon again with the saved battery bars as its pool). The Zoltan C, the only start ship with a battery.
     */
    private fun powerSave(main: MainGame, container: GameContainer): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val out = ArrayList<String>()
        try {
            for (name in listOf("PLAYER_SHIP_ENERGY_3")) {
                val state = BrowserGameplayParityDiagnostics.fresh(main, container, name)
                val ship = state.player
                val battery = ship.systems.filterIsInstance<xyz.znix.xftl.systems.BackupBattery>().firstOrNull() ?: continue
                val weapons = ship.weapons ?: continue
                val list = ship.hardpoints.mapNotNull { it.weapon }
                if (list.isEmpty()) continue
                // The reactor goes to the other systems first, then the battery runs and the weapons take its bars.
                for (w in list) weapons.setWeaponPower(w, false)
                for (sys in ship.mainSystems) if (sys !== weapons) repeat(8) { sys.increasePower() }
                battery.startBattery()
                repeat(3) { main.update(container, 1f / 60f) }
                for (w in list) weapons.setWeaponPower(w, true)
                repeat(3) { main.update(container, 1f / 60f) }
                fun snap(s: xyz.znix.xftl.Ship): String {
                    val b = s.systems.filterIsInstance<xyz.znix.xftl.systems.BackupBattery>().first()
                    return s.hardpoints.mapNotNull { it.weapon }.joinToString("") { if (it.isPowered) "1" else "0" } +
                        " power ${s.weapons!!.powerSupplied} spare ${s.powerAvailable} battery ${b.timeRemaining != null}"
                }
                fun detail(s: xyz.znix.xftl.Ship) =
                    s.mainSystems.joinToString(",") { "${it.javaClass.simpleName}=${it.powerSupplied}" }
                val before = snap(ship)
                check(list.any { it.isPowered }) { "$name: no weapon powered ($before)" }
                val xml = BrowserXml.write(state.saveGameState())
                main.loadSavedGame(BrowserXml.parse(xml))
                val loaded = (main.currentState as InGameState).player
                // Right after the load (its update(0f)) and three frames later: the Zoltan bars count from the first
                // update, so the weapon held by Zoltan and battery bars stays on.
                val atLoad = snap(loaded)
                val atLoadDetail = detail(loaded)
                repeat(3) { main.update(container, 1f / 60f) }
                val after = snap(loaded)
                check(before == atLoad && before == after) {
                    "$name: before $before (${detail(ship)}), at load $atLoad ($atLoadDetail), 3 frames later $after"
                }
                out += "$name $before"
            }
            check(out.isNotEmpty()) { "no ship with a battery and a weapon" }
            return "PASS power save/reload with the battery on: " + out.joinToString("; ")
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * The vanilla counterpart of the frame probe's BEAM_1 case, matching the native 1.6.14 capture
     * (.build make-native-beam-frame.py): on the vanilla start ship, 120 frames after the start, the beam HS's
     * CreateBeam would make (BEAM_1 from (40,640) at (300,220)..(700,220), owner 1, length 400, heading 0) runs
     * [argument] frames in the player's space, then is taken out of the loop (frozen) and drawn alone on black at the
     * identity transform. Returns the beam's points and the pixels that are not black ([x, y, r, g, b]).
     */
    private fun beamFrameVanilla(main: MainGame, container: GameContainer, argument: String): String {
        val frames = argument.trim().toIntOrNull() ?: 30
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val graphics = BrowserEngine.diagnosticGraphics()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player
            ship.browserJumping = false
            game.debugFlags.noDmg.set = true
            repeat(120) { main.update(container, 1f / 60f) }
            val bp = main.gameContent.blueprintManager.getOrNull("BEAM_1") as xyz.znix.xftl.weapons.AbstractWeaponBlueprint
            val position = xyz.znix.xftl.math.ConstFPoint(40f, 640f)
            val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(position,
                xyz.znix.xftl.math.ConstFPoint(300f, 220f), ship, 1, 0, 0)
            val beam = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                xyz.znix.xftl.weapons.BeamProjectile(game, ship, xyz.znix.xftl.math.ConstFPoint(700f, 220f), 400, 0f).also {
                    it.hsOwnerId = 1
                    it.computeBeamHeading()
                    it.motion.aimSubEnd(position, 0f)
                    it.initialize(bp)
                }
            }
            ship.projectiles += beam
            repeat(frames) { main.update(container, 1f / 60f) }
            ship.projectiles -= beam
            val m = beam.motion
            org.lwjgl.opengl.GL11.glClearColor(0f, 0f, 0f, 1f)
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT)
            graphics.markCurrentImageTransformSource()
            graphics.pushTransform()
            try { graphics.loadIdentityMatrix(); beam.renderSpecific(graphics, ship) } finally { graphics.popTransform() }
            val px = BrowserPixels.read(0, 0, 1280, 720)
            val pixels = StringBuilder()
            for (y in 0 until 720) for (x in 0 until 1280) {
                val i = (y * 1280 + x) * 4
                val r = px[i].toInt() and 255; val g = px[i + 1].toInt() and 255; val b = px[i + 2].toInt() and 255
                if (r > 8 || g > 8 || b > 8) {
                    if (pixels.isNotEmpty()) pixels.append(',')
                    pixels.append('[').append(x).append(',').append(y).append(',').append(r).append(',').append(g).append(',').append(b).append(']')
                }
            }
            fun p(v: xyz.znix.xftl.math.FPoint) = "${v.xf},${v.yf}"
            return "{\"tip\":[${p(m.tip)}],\"origin\":[${p(beam.position)}],\"subStart\":[${p(m.subStart)}],\"shieldEnd\":[${p(m.shieldEnd)}]," +
                "\"finalEnd\":[${p(m.finalEnd)}],\"pierced\":${m.piercedShield},\"lastDamage\":${m.lastDamage}," +
                "\"pixels\":[$pixels]}"
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * The oracle's authored beam frame probe (wp4-beam-frame.lua) on the MV start ship: each beam is advanced frame
     * by frame until the probe freezes it, then drawn alone on black at the identity transform through the same Lua
     * OnRenderSpecific(0). Returns the probe rows and, per beam, the pixels that are not black ([x, y, r, g, b]).
     */
    private fun beamFrame(main: MainGame, container: GameContainer, script: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val graphics = lua.graphics ?: error("no graphics")
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            game.player.browserJumping = false
            game.debugFlags.noDmg.set = true
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 beam frame Lua error: $text" }
                if (text.startsWith("WP4_BF:")) rows += text.removePrefix("WP4_BF:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-beam-frame", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='wp4_bf_row' then print('WP4_BF:'..v.value) end end}")
            run(script)
            run("WP4BF.setup()")
            val frames = StringBuilder()
            // The probe's cases in its session order: shields up (30 frames), then shields held at 0 (40 frames).
            for ((name, call) in listOf("BEAM_1" to "'BEAM_1',30", "DRONE_BEAM" to "'DRONE_BEAM',30",
                "BEAM_1#pierced" to "'BEAM_1',40,true", "DRONE_BEAM#pierced" to "'DRONE_BEAM',40,true")) {
                run("WP4BF.beam($call)")
                var n = 0
                while (rows.none { it.startsWith("frozen|$name") } && n < 600) {
                    main.update(container, 1f / 60f); xyz.znix.xftl.hs.lua.LuaEvents.onTick(); n++
                }
                check(rows.any { it.startsWith("frozen|$name") }) { "beam $name never froze" }
                org.lwjgl.opengl.GL11.glClearColor(0f, 0f, 0f, 1f)
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT)
                graphics.markCurrentImageTransformSource()
                graphics.pushTransform()
                try { graphics.loadIdentityMatrix(); run("WP4BF.p:OnRenderSpecific(0)") } finally { graphics.popTransform() }
                xyz.znix.xftl.hs.lua.LuaGraphics.flushQuads()
                val px = BrowserPixels.read(0, 0, 1280, 720)
                if (frames.isNotEmpty()) frames.append(',')
                frames.append(BrowserDebug.quote(name)).append(":[")
                var first = true
                for (y in 0 until 720) for (x in 0 until 1280) {
                    val i = (y * 1280 + x) * 4
                    val r = px[i].toInt() and 255; val g = px[i + 1].toInt() and 255; val b = px[i + 2].toInt() and 255
                    if (r > 8 || g > 8 || b > 8) {
                        if (!first) frames.append(',')
                        first = false
                        frames.append('[').append(x).append(',').append(y).append(',').append(r).append(',').append(g).append(',').append(b).append(']')
                    }
                }
                frames.append(']')
            }
            return "{\"rows\":" + rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) } + ",\"pixels\":{" + frames + "}}"
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * The oracle's authored factory-queue probe (wp4-queue.lua) in the real Lua VM against a spawned enemy. The oracle
     * session presses the weapon's power hotkey after each `case` row; here the slot is powered the same second.
     */
    private fun factoryQueue(main: MainGame, container: GameContainer, script: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            game.debugSpawnShip(game.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
            game.debugFlags.noEnemyFire.set = true; game.debugFlags.noCrewDamage.set = true
            var powerAt = -1
            var frame = 0
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 queue Lua error: $text" }
                if (text.startsWith("WP4_Q:")) {
                    val row = text.removePrefix("WP4_Q:").trimEnd()
                    rows += row
                    if (row.startsWith("case|")) powerAt = frame + 60
                }
            }
            fun run(code: String) { val error = lua.runString("wp4-queue", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='wp4_queue_row' then print('WP4_Q:'..v.value) end end}")
            run(script)
            run("WP4Q.start()")
            while (rows.none { it == "done" } && frame < 30000) {
                if (frame == powerAt) {
                    val weapon = game.player.hardpoints[0].weapon
                    if (weapon != null && !weapon.isPowered) game.player.weapons!!.setWeaponPower(weapon, true)
                }
                main.update(container, 1f / 60f)
                xyz.znix.xftl.hs.lua.LuaEvents.onTick()
                frame++
            }
            check(rows.any { it == "done" }) { "queue probe did not finish: ${rows.takeLast(3)}" }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /** The oracle's authored launcher-surface probe (wp4-armament.lua) on the MV start ship, in the real Lua VM. */
    private fun armamentSurface(main: MainGame, container: GameContainer, script: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            // The native probe runs at the MV start beacon with every weapon unpowered and slid in.
            for (hp in game.player.hardpoints) hp.weapon?.let {
                if (it.isPowered) game.player.weapons!!.setWeaponPower(it, false)
                it.slide = 0f
            }
            lua.sink = { kind, text ->
                check(kind != 2) { "WP4 armament Lua error: $text" }
                if (text.startsWith("WP4_ARM:")) rows += text.removePrefix("WP4_ARM:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-armament", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='wp4_arm_row' then print('WP4_ARM:'..v.value) end end}")
            run(script)
            run("WP4ARM.run()")
            check(rows.isNotEmpty())
            // Browser-only exercise of the stateful members (not in the native probe): every call must succeed.
            run("""
                local ship=Hyperspace.ships.player; local sys=ship.weaponSystem; local w=sys.weapons[0]
                local v=w.weaponVisual
                assert(v.anim.info.numFrames>0 and v.slideTracker.time>0 and v.explosionAnim~=nil and v.hackSparks~=nil)
                v:SetFireTime(1); assert(w.flight_animation~=nil and w.localPosition.x==0)
                w:SetCooldownModifier(0.5); assert(math.abs(w.cooldownModifier-0.5)<1e-6); w.cooldownModifier=1
                assert(w:IsChargedGoal()==false and w:FireNextShot()==false and w.fireWhenReady==false)
                w.iAmmo=5; assert(w.iAmmo==5); w.bFiredOnce=true; assert(w.bFiredOnce)
                w:SetCurrentShip(nil); w:SetHacked(0)
                assert(sys.userPowered:size()==sys.slot_count and sys.repowerList:size()==0 and sys.target.x==0)
                sys.shot_count=2; assert(sys.shot_count==2)
                assert(sys:ForceDecreasePower(1)==false)
                local trash=sys.weaponsTrashList:size()
                local removed=sys:RemoveWeapon(0); assert(removed~=nil and sys.weaponsTrashList:size()==trash)
                local p=Hyperspace.App.world.space:CreateBeam(Hyperspace.Blueprints:GetWeaponBlueprint('BEAM_1'),
                    Hyperspace.Pointf(20,30),0,0,Hyperspace.Pointf(100,120),Hyperspace.Pointf(200,120),0,100,0)
                p:SetMovingTarget(p.movingTarget); p:OnRenderSpecific(0); p:SaveProjectile(0); p:LoadProjectile(0)
                assert(p.contactAnimations:size()==0 and p.smokeAnims:size()==0); p:Kill()
                print('WP4_ARM:exercise|ok')
            """.trimIndent())
            check(rows.removeAt(rows.lastIndex) == "exercise|ok")
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * The oracle's authored direct ShipManager:DamageBeam probe (wp4-beam-damage.lua) in the real Lua VM. The room
     * geometry argument is the same vRoomList choice the native probe makes (the first room larger than one tile and
     * the following room), read from the engine because Ship.vRoomList belongs to WP5.
     */
    private fun beamDamage(main: MainGame, container: GameContainer, script: String): String {
        check(HS.luaActive)
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val lua = xyz.znix.xftl.hs.lua.HsLua; val oldSink = lua.sink
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player
            ship.browserJumping = false // the native probe runs at a beacon, not during the start jump
            // MV's fix-pro-hull-beam.lua indexes the (nil) projectile of a direct call; native HS logs the same
            // callback error for every unpreempted DAMAGE_BEAM chain and continues. Any other error is fatal.
            var mvErrors = 0
            lua.sink = { kind, text ->
                if (kind == 2 && "fix-pro-hull-beam.lua" in text && "local 'projectile'" in text) mvErrors++
                else check(kind != 2) { "WP4 beam damage Lua error: $text" }
                if (text.startsWith("WP4_BD:")) rows += text.removePrefix("WP4_BD:").trimEnd()
            }
            fun run(code: String) { val error = lua.runString("wp4-beam-damage", code); check(error == null) { error!! } }
            run("HS_ORACLE={emit=function(kind,v) if kind=='wp4_beam_damage_row' then print('WP4_BD:'..v.value) end end}")
            run(script)
            val rooms = ship.rooms.sortedBy { it.id }
            val room = rooms.first { it.pixelWidth > 35 || it.pixelHeight > 35 }
            val other = rooms[(rooms.indexOf(room) + 1) % rooms.size]
            run("WP4BDMG.runNative({id=${room.id},x=${room.offsetX},y=${room.offsetY},w=${room.pixelWidth},h=${room.pixelHeight}," +
                "other={x=${other.offsetX},y=${other.offsetY},w=${other.pixelWidth},h=${other.pixelHeight}}})")
            check(rows.isNotEmpty())
            rows += "callback-errors|fix-pro-hull-beam|$mvErrors"
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            lua.sink = oldSink
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
    /**
     * Replays the native 1.6.14 DamageBeam fixture's cases (make-native-dbeam.py: name, current, last, iDamage,
     * iSystemDamage, bHullBuster, special) on the player ship through the shared BeamDamageRules operation. The
     * first row is the engine's room geometry, which the comparator checks against the native ShipGraph dump.
     */
    private fun nativeDamageBeam(main: MainGame, container: GameContainer, xml: String): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val rows = ArrayList<String>()
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player
            ship.browserJumping = false
            rows += "geometry|" + ship.rooms.joinToString(";") { r ->
                "${r.id},${r.offsetX},${r.offsetY},${r.pixelWidth},${r.pixelHeight},${r.system != null}" }
            for ((index, node) in BrowserXml.parse(xml).rootElement.getChildren("case").withIndex()) {
                fun num(name: String) = node.getAttributeValue(name).toFloat()
                val name = node.getAttributeValue("name")
                val current = xyz.znix.xftl.math.ConstFPoint(num("cx"), num("cy"))
                val last = xyz.znix.xftl.math.ConstFPoint(num("lx"), num("ly"))
                val damage = xyz.znix.xftl.weapons.Damage()
                damage.hullDamage = num("damage").toInt(); damage.pureSysDamage = num("system").toInt()
                damage.emptyRoomBonus = num("buster").toInt(); damage.hsOwnerId = 1; damage.hsSelfId = 7000 + index
                val special = node.getAttributeValue("special")
                // The player's Targetable.hostile is not a separate engine state: the fixture forces it off natively.
                if (special == "peace") { rows += "$name|skipped"; continue }
                ship.health = 20; ship.nativeLastDamage = 0xdeadbeef.toInt()
                val system = if (name.startsWith("buster-system") || name.startsWith("system"))
                    xyz.znix.xftl.weapons.BeamDamageRules.roomAt(ship, current)?.system else null
                // The fixture restores healthState.first = second before each system case.
                system?.damagedEnergyLevels = 0
                val before = system?.let { it.energyLevels - it.damagedEnergyLevels } ?: 0
                if (special == "jump") ship.browserJumping = true
                val result = xyz.znix.xftl.weapons.BeamDamageRules.damageBeam(ship, current, last, damage)
                ship.browserJumping = false
                val after = system?.let { it.energyLevels - it.damagedEnergyLevels } ?: 0
                fun id(p: xyz.znix.xftl.math.FPoint) = xyz.znix.xftl.weapons.BeamDamageRules.roomAt(ship, p)?.id ?: -1
                rows += "$name|$result|${ship.health}|${ship.nativeLastDamage}|${id(current)}|${id(last)}|$before|$after"
            }
            return rows.joinToString(",", "[", "]") { BrowserDebug.quote(it) }
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
