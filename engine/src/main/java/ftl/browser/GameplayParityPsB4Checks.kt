package ftl.browser

import org.lwjgl.opengl.GL11
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.sector.Beacon.EnvironmentType
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.JumpWindow
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.BatteryEnergySource
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import kotlin.math.abs

/**
 * Parity sweep bug piece B4 (a player's reports on 2.1.0, Hyperspace on Android). Every mode (vanilla, F2, F3):
 * - bomb launchers: `<delayChargeAnim>` (0.98 on every bomb launcher's weaponAnim) is read into
 *   WeaponAnimation::fDelayChargeTime (AnimationControl::ImportAnimations 1.6.9 0x589de0, copied by GetWeapon 0x588340),
 *   and SetChargedLevel (1.6.14 0x4608b0) shows frame (int)(chargedFrame * max(0, (charge - delay) / (1 - delay))): the
 *   launcher stays closed until 98% of the charge. A laser (no delay) moves through its whole charge.
 * - custom deaths: CrewAnimation::OnRender (1.6.14 0x477f20) skips the colour strip while the death animation runs and
 *   draws a CustomDeath race's own death sheet (the Zoltan's 64 px green explosion) alone, its corner at 17 - size / 2
 *   from the 35 px frame's: centred on the crew member. Checked by pixels against that placement.
 * - lock ring: SystemBox::OnRender (1.6.14 0x4d8eb0) stencils the lock timer from -85 degrees (5 past the top), so the
 *   last segment leaves the first 5 degrees clear; the touch layout's box draws the ring too (it showed the number only):
 *   checked by two renders of the touch HUD that differ only in the lock timer.
 * - touch cooldown bar: the iPad's box shows whole segments of its mask (rows from 20n + 10 - 4k down).
 * - reactor column: SystemControl::RenderPowerBar (1.6.9 0x4bb6a0) puts the battery's unused bars at the bottom, a 24 x 3
 *   fill in an (230, 110, 30) outline. Checked on the rendered HUD.
 * - beacon map: StarMap::OnRender (1.6.9 0x5d9ab0) draws the nebula clouds before the connections and beacons.
 * - flagship: WorldManager::CreateLocation (1.6.9 0x5f2be0) takes BossShip::GetEvent's event (1.6.14 0x407580, no
 *   environment) where the flagship waits, so a fleet beacon's ASB is not up during the flagship fight, after a reload too.
 */
internal object GameplayParityPsB4Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("bombChargeFrames") { bombChargeFrames(main, container) }
            case("customDeath") { customDeath(main, container) }
            case("lockRingStart") { lockRingStart(main, container) }
            case("touchLockRing") { touchLockRing(main, container) }
            case("touchCooldownSegments") { touchCooldownSegments(main, container) }
            case("reactorBattery") { reactorBattery(main, container) }
            case("batteryFlash") { batteryFlash(main, container) }
            case("mapCloudsUnderLines") { mapCloudsUnderLines(main, container) }
            case("mapMarkers") { mapMarkers(main, container) }
            case("flagshipNoAsb") { flagshipNoAsb(main, container) }
            case("flagshipArrivesOnWait") { flagshipArrivesOnWait(main, container) }
        } finally {
            xyz.znix.xftl.game.TouchMode.enabled = false
            BatteryEnergySource.flashTime = -1f
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-b4 ok: " + results.joinToString(", ")
    }

    private val graphics: Graphics get() = BrowserEngine.diagnosticGraphics()

    // ---- bomb launchers

    private fun bombChargeFrames(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val out = ArrayList<String>()
        for (name in listOf("BOMB_1", "BOMB_ION", "BOMB_FIRE", "BOMB_HEAL", "LASER_BURST_1")) {
            val bp = state.blueprintManager.getOrNull(name) as? AbstractWeaponBlueprint ?: continue
            val w = bp.buildInstance(state.player)
            val anim = w.animation
            val bomb = name.startsWith("BOMB")
            if (bomb && !BrowserGameplayParityDiagnostics.modded)
                check(abs(anim.delayChargeAnim - 0.98f) < 1e-4f) { "$name: delayChargeAnim ${anim.delayChargeAnim}, vanilla's weaponAnim has 0.98" }
            if (!bomb) check(anim.delayChargeAnim == 0f) { "$name: delayChargeAnim ${anim.delayChargeAnim} (none in its weaponAnim)" }
            val d = anim.delayChargeAnim
            for (p in floatArrayOf(0f, 0.3f, 0.5f, 0.9f, 0.979f, 0.985f, 0.99f, 0.995f, 1f)) {
                w.timeCharged = p * w.chargeTime
                val c = w.chargeProgress
                val level = if (d == 0f) c else ((c - d) / (1f - d)).coerceAtLeast(0f)
                val want = if (c < 1f) (anim.chargedFrame * level).toInt() else anim.chargedFrame
                check(w.chargeAnimFrame == want) { "$name at $p charged: frame ${w.chargeAnimFrame}, SetChargedLevel gives $want" }
            }
            if (bomb && d > 0.5f) {
                w.timeCharged = 0.9f * w.chargeTime
                check(w.chargeAnimFrame == 0) { "$name: the launcher moves at 90% charge (frame ${w.chargeAnimFrame})" }
            }
            out += "$name d=$d"
        }
        check(out.size >= 2) { "no bomb or laser blueprints found" }
        return "charge frames (${out.joinToString(" ")})"
    }

    // ---- crew death

    /** Clears to black, draws with [block] under the identity transform and reads the [w] x [h] rectangle at [x], [y]. */
    private fun render(x: Int, y: Int, w: Int, h: Int, block: (Graphics) -> Unit): ByteArray {
        val g = graphics
        val savedTint = Graphics.tint
        GL11.glClearColor(0f, 0f, 0f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        g.markCurrentImageTransformSource()
        g.pushTransform()
        try {
            Graphics.tint = Colour.white
            g.loadIdentityMatrix()
            block(g)
        } finally {
            g.popTransform()
            Graphics.tint = savedTint
        }
        return BrowserPixels.read(x, y, w, h)
    }

    private fun differing(a: ByteArray, b: ByteArray): Int {
        var n = 0
        for (i in 0 until minOf(a.size, b.size) step 4)
            if (abs(a[i].toInt() - b[i].toInt()) > 2 || abs(a[i + 1].toInt() - b[i + 1].toInt()) > 2 || abs(a[i + 2].toInt() - b[i + 2].toInt()) > 2) n++
        return n
    }

    private fun lit(a: ByteArray): Int {
        var n = 0
        for (i in a.indices step 4) if ((a[i].toInt() and 255) + (a[i + 1].toInt() and 255) + (a[i + 2].toInt() and 255) > 24) n++
        return n
    }

    private fun customDeath(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugFlags.noCrewDamage.set = false
        val race = state.blueprintManager["energy"] as CrewBlueprint
        val crew = state.player.addCrewMember(LivingCrewInfo.generateRandom(race, state), false)
        check(crew.customDeath || (BrowserGameplayParityDiagnostics.modded && xyz.znix.xftl.hs.crew.CustomCrewManager.definition("energy").hasCustomDeathAnimation.not())) {
            "the Zoltan has no CustomDeath"
        }
        if (!crew.customDeath) return "custom death: the mod's Zoltan has none"
        crew.health = 0f
        var steps = 0
        while (crew.currentAction != AbstractCrew.Action.DYING && steps++ < 20) main.update(container, 0.02f)
        check(crew.currentAction == AbstractCrew.Action.DYING) { "the Zoltan did not start dying" }
        // A few frames in, so the explosion has grown. In F3, Multiverse's instant heal out of combat
        // (instant-clone-heal.lua, DirectModifyHealth(9999) every SHIP_LOOP) must not bring the dying Zoltan back:
        // DirectModifyHealth does nothing at 0 health (1.6.9 0x46eb20).
        repeat(3) { main.update(container, 0.05f) }
        check(crew.currentAction == AbstractCrew.Action.DYING && crew.health == 0f) {
            "the Zoltan's death ended too soon: ${crew.currentAction}, health ${crew.health} (healed while dying?)"
        }
        val frame = crew.icon.currentFrame
        check(frame.width == 64 && frame.height == 64) { "the Zoltan's death frame is ${frame.width}x${frame.height}, explosion_energy_death has 64x64" }
        // A spot on screen with room for the 64 px explosion around the crew member's cell.
        val dx = 200 - crew.screenX
        val dy = 200 - crew.screenY
        val box = intArrayOf(200 - 40, 200 - 40, 120, 120)
        val drawn = render(box[0], box[1], box[2], box[3]) { g -> g.translate(dx.toFloat(), dy.toFloat()); crew.draw(g) }
        val x = 200 + AbstractCrew.customDeathOffset(frame.width)
        val y = 200 + AbstractCrew.customDeathOffset(frame.height)
        val want = render(box[0], box[1], box[2], box[3]) { _ -> frame.draw(x.toFloat(), y.toFloat()) }
        check(lit(want) > 50) { "the explosion frame drew only ${lit(want)} pixels" }
        val diff = differing(drawn, want)
        check(diff == 0) { "the dying Zoltan differs from its explosion at (17 - 32) of its cell in $diff pixels (centre off, or the colour strip drawn)" }
        return "Zoltan death centred (${lit(want)} px)"
    }

    // ---- lock ring

    private fun lockRingStart(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val timer = state.getImg("img/icons/locking/s_ring_timer.png")
        check(AbstractSystem.lockRingSegments(5f) == 12 && AbstractSystem.lockRingSegments(0.3f) == 1 && AbstractSystem.lockRingSegments(2.5f) == 6) {
            "segments ${AbstractSystem.lockRingSegments(5f)}, ${AbstractSystem.lockRingSegments(0.3f)}, ${AbstractSystem.lockRingSegments(2.5f)}"
        }
        // One segment: the pie from 5 to 35 degrees clockwise of the top. The ring's pixels just right of the top (1-4
        // degrees) stay clear, those at 20 degrees are drawn.
        val one = render(100, 100, 64, 64) { g -> AbstractSystem.drawLockRing(g, timer, 100f, 100f, 0.3f) }
        val full = render(100, 100, 64, 64) { g -> AbstractSystem.drawLockRing(g, timer, 100f, 100f, 5f) }
        fun at(px: ByteArray, deg: Double, r: Double): Boolean {
            val a = Math.toRadians(deg)
            val x = (32 + Math.sin(a) * r).toInt()
            val y = (32 - Math.cos(a) * r).toInt()
            val i = (y * 64 + x) * 4
            return (px[i].toInt() and 255) + (px[i + 1].toInt() and 255) + (px[i + 2].toInt() and 255) > 60
        }
        // The radius where the full ring is drawn at 20 degrees.
        val r = (10..31).map { it.toDouble() }.firstOrNull { at(full, 20.0, it) } ?: error("no ring pixels at 20 degrees")
        check(at(one, 20.0, r)) { "one segment: nothing at 20 degrees" }
        check(!at(one, 2.0, r)) { "one segment: drawn 2 degrees past the top (the stencil starts at -85, 5 past the top)" }
        check(!at(one, 40.0, r)) { "one segment: drawn at 40 degrees" }
        return "lock ring from 5 degrees past the top"
    }

    private fun touchLockRing(main: MainGame, container: GameContainer): String {
        val touch = xyz.znix.xftl.game.TouchMode.enabled
        try {
            xyz.znix.xftl.game.TouchMode.enabled = true
            val state = BrowserGameplayParityDiagnostics.fresh(main, container)
            check(state.shipUI.touch != null) { "no touch HUD" }
            val shields = state.player.shields ?: error("no shields")
            if (!state.isPaused) state.toggleQuickPause()
            fun frame(timer: Float): ByteArray {
                shields.ionTimer = timer
                main.render(container, graphics)
                return BrowserPixels.read(0, 0, 1280, 720)
            }
            // The same number (one lock), the ring whole or one segment.
            val whole = frame(5f)
            val again = frame(5f)
            check(differing(whole, again) == 0) { "two renders of the same state differ" }
            val one = frame(0.3f)
            val diff = differing(whole, one)
            check(diff > 20) { "the touch box's lock ring does not show the timer (frames differ in $diff pixels)" }
            shields.ionTimer = 0f
            return "touch lock ring ($diff px between a whole and a one-segment ring)"
        } finally {
            xyz.znix.xftl.game.TouchMode.enabled = touch
        }
    }

    // ---- touch cooldown bar

    private fun touchCooldownSegments(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var checked = 0
        for (n in 1..4) {
            val mask = state.getImg("img/ipad/systemUI/bar_${n}_mask.png")
            val px = render(0, 0, mask.width, mask.height) { _ -> mask.draw(0f, 0f) }
            val opaque = BooleanArray(mask.height) { y -> (0 until mask.width).any { x -> (px[(y * mask.width + x) * 4 + 1].toInt() and 255) > 40 } }
            val segments = 5 * n
            for (k in 0..segments) {
                val fraction = if (k == 0) 0f else (k + 0.5f) / segments
                val top = xyz.znix.xftl.game.touchCooldownMaskTop(n, fraction, mask.height)
                val rows = if (top < 0) 0 else (top until mask.height).count { opaque[it] }
                check(rows == 3 * k) { "level $n, $k of $segments segments: the mask from row $top shows $rows rows, ${3 * k} would be $k whole segments" }
                if (top >= 0) check(!opaque[top]) { "level $n, $k segments: the first row shown ($top) is inside a segment (a segment cut)" }
                checked++
            }
        }
        return "touch cooldown bars show whole segments ($checked cases)"
    }

    // ---- reactor column

    private fun reactorBattery(main: MainGame, container: GameContainer): String {
        check(EnergySource.REACTOR_COLUMN_ORDER.first() === BatteryEnergySource) { "the battery's bars are not first in the reactor column" }
        val bar = render(100, 100, 28, 7) { g -> BatteryEnergySource.drawReactorPowerBar(g, 100, 100, 28, 7) }
        fun rgb(x: Int, y: Int) = IntArray(3) { bar[(y * 28 + x) * 4 + it].toInt() and 255 }
        check(rgb(0, 0).contentEquals(intArrayOf(230, 110, 30))) { "battery bar outline ${rgb(0, 0).toList()}, RenderPowerBar's (230, 110, 30)" }
        check(rgb(1, 1).sum() == 0 && rgb(2, 2).sum() > 200 && rgb(25, 4).sum() > 200 && rgb(26, 4).sum() == 0) {
            "battery bar fill: ${rgb(1, 1).toList()} ${rgb(2, 2).toList()} ${rgb(25, 4).toList()} ${rgb(26, 4).toList()} (24 x 3 at 2, 2)"
        }
        // The HUD: a battery on, nothing drawing on it: the column's bottom bar is the battery's.
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.getDebugConsole().execute("SYS battery")
        val battery = state.player.backupBattery ?: error("no backup battery after SYS battery")
        battery.startBattery()
        state.player.updateAvailablePower()
        check((state.player.powerAvailableTypes[BatteryEnergySource] ?: 0) > 0) { "the battery gives no unused power" }
        if (!state.isPaused) state.toggleQuickPause()
        main.render(container, graphics)
        val y = 720 - 34
        val px = BrowserPixels.read(12, y, 28, 7)
        fun at(x: Int, yy: Int) = IntArray(3) { px[(yy * 28 + x) * 4 + it].toInt() and 255 }
        check(abs(at(0, 0)[0] - 230) <= 3 && abs(at(0, 0)[1] - 110) <= 3 && abs(at(0, 0)[2] - 30) <= 3) {
            "the reactor column's bottom bar corner is ${at(0, 0).toList()}, the battery's outline is (230, 110, 30)"
        }
        return "battery bars at the reactor column's bottom"
    }

    /**
     * B4b: turning the battery on posts event 11 (1.6.14 0x62e410); SystemControl's flashBatteryPower (2 s) and
     * flashTracker (0.66 s, looping; constructor 1.6.9 0x4b85b0) then turn the battery's reactor bars white in the second
     * half of each 0.66 s (RenderPowerBar). HS oracle ps-b4b-flash shows the white bars after the start.
     */
    private fun batteryFlash(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.getDebugConsole().execute("SYS battery")
        val battery = state.player.backupBattery ?: error("no backup battery after SYS battery")
        BatteryEnergySource.flashTime = -1f
        battery.startBattery()
        check(BatteryEnergySource.flashTime == 0f) { "starting the battery does not start the reactor bars' flash (event 11)" }
        fun fill(): IntArray {
            val bar = render(100, 100, 28, 7) { g -> BatteryEnergySource.drawReactorPowerBar(g, 100, 100, 28, 7) }
            return IntArray(3) { bar[(3 * 28 + 12) * 4 + it].toInt() and 255 }
        }
        var t = 0f
        val seen = ArrayList<String>()
        for ((at, white) in listOf(0.1f to false, 0.4f to true, 0.7f to false, 1.1f to true, 1.5f to false, 1.9f to true, 2.1f to false)) {
            BatteryEnergySource.advanceFlash(at - t)
            t = at
            val c = fill()
            val isWhite = c.all { it > 240 }
            seen += "$at:${c.toList()}"
            check(isWhite == white) { "battery bar fill at ${at}s after the start: ${c.toList()}, flashTracker gives ${if (white) "white" else "green"} ($seen)" }
        }
        return "battery bars flash white for 2 s"
    }

    // ---- beacon map
    /**
     * B4b: the ship marker on the beacon map. StarMap::Open (1.6.9 0x5c6520) picks the player's and the flagship's marker
     * angles with random32() % 360; OnRender (0x5d9ab0) takes 1.125 degrees per speed-factor unit off each frame (18 a
     * second, wrapping to 360 at 0) and draws the 64 x 64 primitive made at (-10, -32) (constructor 0x5cc560) turned by
     * that angle about the beacon. Ours turned 2 pi / 20 "degrees" a second from 0..10 (upright) and drew at (-8, -32).
     */
    private fun mapMarkers(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.player.ftlChargeProgress = 1f
        val rng = NativeRandom.snapshot()
        val ship = NativeRandom.nextInt(360)
        val boss = NativeRandom.nextInt(360)
        NativeRandom.restore(rng)
        state.shipUI.openJumpMap()
        val window = state.shipUI.diagnosticJumpWindow ?: error("the beacon map did not open")
        check(window.playerRotation == ship.toFloat() && window.bossRotation == boss.toFloat()) {
            "marker angles ${window.playerRotation}, ${window.bossRotation}; StarMap::Open's draws give $ship, $boss"
        }
        val markers = ArrayList<String>()
        window.browserMarkers = markers
        main.update(container, 0.5f)
        window.draw(graphics)
        val drawn = markers.firstOrNull { it.startsWith("ship:") }?.removePrefix("ship:")?.toFloat() ?: error("no ship marker drawn: $markers")
        val want = (ship - 9f).let { if (it <= 0f) 360f else it }
        check(abs(drawn - want) < 0.01f) { "ship marker at $drawn degrees 0.5 s after the map opened at $ship: 18 a second anticlockwise gives $want" }
        state.shipUI.diagnosticCloseWindow()
        return "map markers from StarMap::Open's angles, 18 degrees a second"
    }


    private fun mapCloudsUnderLines(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.currentBeacon.sector
        if (sector.nebulaClouds.isEmpty()) {
            val n = state.currentBeacon.neighbours.first()
            sector.nebulaClouds += Sector.NebulaCloud("img/map/nebula_0.png", (state.currentBeacon.pos.x + n.pos.x) / 2 - 50,
                (state.currentBeacon.pos.y + n.pos.y) / 2 - 50, 100, 100)
        }
        val window = JumpWindow(state, {}, {})
        val layers = ArrayList<String>()
        window.browserLayers = layers
        window.draw(graphics)
        val clouds = layers.indexOf("clouds")
        val lines = layers.indexOf("lines")
        val beacons = layers.indexOf("beacons")
        check(clouds >= 0 && lines >= 0 && beacons >= 0) { "layers drawn: $layers" }
        check(clouds < lines && lines < beacons) { "map layers in the order $layers: StarMap::OnRender draws the clouds first" }
        // B4b: a sector with no recorded clouds (an old or imported save) draws its per-beacon puffs in the clouds'
        // place, before the lines.
        val saved = ArrayList(sector.nebulaClouds)
        val nebula = state.currentBeacon.neighbours.first()
        val forced = nebula.forcedNebula
        try {
            sector.nebulaClouds.clear()
            nebula.forcedNebula = true
            val old = ArrayList<String>()
            window.browserLayers = old
            window.draw(graphics)
            val puffs = old.indexOf("puffs")
            check(puffs >= 0) { "no puffs drawn for a sector without recorded clouds: $old" }
            check(puffs < old.indexOf("lines")) { "the old-save puffs come after the lines: $old" }
        } finally {
            sector.nebulaClouds.clear(); sector.nebulaClouds += saved
            nebula.forcedNebula = forced
        }
        // A save keeps the clouds (every release since 1.0 writes browserNebulaCloud): after a reload they are the same.
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val back = loaded.currentBeacon.sector.nebulaClouds
        check(back.size == sector.nebulaClouds.size && back.zip(sector.nebulaClouds).all { (a, b) -> a.image == b.image && a.x == b.x && a.y == b.y }) {
            "the clouds after a reload: ${back.size}, before ${sector.nebulaClouds.size}"
        }
        return "map: clouds (or an old save's puffs), then lines and beacons; clouds kept by saves"
    }

    // ---- flagship

    private fun fleetEvent(state: InGameState): xyz.znix.xftl.sector.CreatedEvent {
        for (name in listOf("FLEET_EASY_DLC", "FLEET_EASY", "FLEET_HARD")) {
            val e = state.eventManager.getOrNull(name) ?: continue
            val created = state.eventCreator.create(e, state.currentBeacon.sector.sectorNumber) ?: continue
            if (created.template.environment == EnvironmentType.PDS) return created
        }
        error("no fleet event with an ASB")
    }

    private fun flagshipNoAsb(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val here = state.currentBeacon
        val target: Beacon = here.neighbours.first()
        target.createdEvent = fleetEvent(state)
        target.isOvertaken = true
        target.hasFleetEvent = true
        target.clearEnvironment()
        check(target.getEnvironment(state).type == EnvironmentType.PDS) { "control: the fleet beacon's scene is ${target.getEnvironment(state).type}" }
        target.clearEnvironment()
        val sector = here.sector
        val boss = sector.bosses.firstOrNull() as? FlagshipBoss ?: FlagshipBoss(sector, state, target).also { sector.bosses.add(it) }
        boss.debugTeleportTo(target)
        check(target.holdsFlagship) { "the flagship is not at the target" }
        state.setCurrentBeacon(target)
        repeat(10) { main.update(container, 0.05f) }
        val env = state.currentBeacon.getEnvironment(state)
        check(env.type == EnvironmentType.NORMAL) { "the flagship's fleet beacon has the scene ${env.type} (CreateLocation loads the boss event: none)" }
        check(state.enemy?.boss != null) { "no flagship at its beacon" }
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        check(loaded.currentBeacon.getEnvironment(loaded).type == EnvironmentType.NORMAL) {
            "after a reload the flagship's beacon has ${loaded.currentBeacon.getEnvironment(loaded).type}"
        }
        // B4b: the flagship moves on, the player leaves and comes back: CreateLocation runs on every arrival and the
        // location's own (fleet) event is back, its ASB with it (StarMap::UpdateBoss 1.6.9 0x5d0b80 only moves the
        // boss flag). Ours clears a location's scene when the player leaves (ClearLocation).
        val lb = loaded.currentBeacon
        val lboss = lb.sector.bosses.first() as FlagshipBoss
        val away = lb.neighbours.first()
        val elsewhere = lb.sector.beacons.first { it !== lb && it !== away && !it.isBase && it !in lb.neighbours }
        lboss.debugTeleportTo(elsewhere)
        check(!lb.holdsFlagship) { "the flagship did not move on" }
        loaded.setCurrentBeacon(away)
        repeat(5) { main.update(container, 0.05f) }
        loaded.setCurrentBeacon(lb)
        repeat(5) { main.update(container, 0.05f) }
        check(lb.getEnvironment(loaded).type == EnvironmentType.PDS) {
            "back at the fleet beacon the flagship left: ${lb.getEnvironment(loaded).type}, the fleet event's ASB"
        }
        val loaded2 = BrowserGameplayParityDiagnostics.reload(main, loaded)
        check(loaded2.currentBeacon.getEnvironment(loaded2).type == EnvironmentType.PDS) {
            "after a reload the fleet beacon the flagship left has ${loaded2.currentBeacon.getEnvironment(loaded2).type}"
        }
        return "no ASB with the flagship at a fleet beacon, ASB again once it left (and after reloads)"
    }

    /**
     * B4b: the out-of-fuel wait in the last stand brings the flagship onto the fleet beacon the player waits at: the
     * wait arrives again (CheckForNewLocation 1.6.9 0x5f4fa0, CreateLocation 0x5f2be0) and the boss event has no
     * environment, so the ASB the player waited in stops.
     */
    private fun flagshipArrivesOnWait(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        val target = boss.beacon!!
        val elsewhere = sector.beacons.first { it !== target && !it.isBase }
        boss.debugTeleportTo(elsewhere)
        target.createdEvent = fleetEvent(state)
        target.isOvertaken = true
        target.hasFleetEvent = true
        target.clearEnvironment()
        state.currentBeacon = target
        check(target.getEnvironment(state).type == EnvironmentType.PDS) { "control: the waiting beacon's scene is ${target.getEnvironment(state).type}" }
        boss.debugTeleportTo(target)
        check(state.browserSpawnBossHere()) { "the flagship did not arrive" }
        val env = target.getEnvironment(state)
        check(env.type == EnvironmentType.NORMAL) { "the flagship arrived during the wait and the scene is still ${env.type}" }
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        check(loaded.currentBeacon.getEnvironment(loaded).type == EnvironmentType.NORMAL) {
            "after a reload the wait's flagship fight has ${loaded.currentBeacon.getEnvironment(loaded).type}"
        }
        return "the flagship arriving during the wait ends the ASB (and after a reload)"
    }
}
