package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractProjectile
import xyz.znix.xftl.weapons.AbstractWeaponProjectile
import xyz.znix.xftl.weapons.FlakBlueprint
import java.util.IdentityHashMap
import kotlin.math.sqrt

/**
 * Parity sweep bug piece B1 (user reports). Every mode (vanilla, F2, F3):
 * - flak: a whole volley of each flak gun reaches the target ship, every piece aimed inside the blueprint radius
 *   around the room it was fired at, and every piece reaches its point. The native 1.6.14 factory (ProjectileFactory::
 *   Update 0x40dc80 BURST) aims each piece at the room centre plus its own offset; Hyperspace's GetProjectile
 *   (CustomWeapons.cpp) moves the queued pieces of a group to the first one's position and keeps their targets. Under
 *   Hyperspace our copy of the position also overwrote the target, so only one piece of a volley hit (Flak I fired
 *   "one shot"). The player's guns and an enemy's gun are both checked.
 * - nebula sensors: an event in a nebula carries StatusEffect::GetNebulaEffect (1.6.9 0x5eba80: both ships' sensors
 *   limited to 0, added by EventGenerator::CreateEvent 0x5b8940 and StarMap::GenerateNebulas 0x5d17e0), so the
 *   sensors box shows every bar capped (crossed) and the grey, unpowered icon without its crew-slot icon, as the native
 *   1.6.14 capture ps-b1-van b1v-neb shows. The touch HUD draws its boxes from the same system state.
 */
internal object GameplayParityPsB1Checks {
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
            case("flakVolleys") { flakVolleys(main, container) }
            case("nebulaSensors") { nebulaSensors(main, container) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-b1 ok: " + results.joinToString(", ")
    }

    private fun fixture(main: MainGame, container: GameContainer): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        // No crew dies under the volleys: an enemy without crew would end the fight with an event box (and a pause).
        state.debugFlags.noCrewDamage.set = true
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        state.player.weapons!!.energyLevels = 8
        state.player.purchasedReactorPower = 25
        state.player.updateAvailablePower()
        state.player.missilesCount = 99
        return state
    }

    /** The target keeps no shields, engines or drones and plenty of hull, so nothing but the aim decides where a piece goes. */
    private fun soften(ship: Ship) {
        ship.shields?.let { s -> repeat(10) { s.decreasePower() }; s.activeShields = 0 }
        ship.engines?.let { e -> repeat(10) { e.decreasePower() } }
        ship.drones?.let { d -> repeat(10) { d.decreasePower() } }
        // A volley must not end the ship: its explosion would take the pieces still flying.
        if (ship.browserMaxHealth != 500) { ship.browserMaxHealth = 500; ship.health = 500 }
        if (ship.health < 250) ship.health = 500
    }

    private class Piece(var entered: Boolean = false, var worst: Float = 0f, var last: Float = Float.MAX_VALUE, var step: Float = 0f, var seen: String = "")

    /**
     * Fires one volley of [name] from [shooter]'s first slot at [room] and follows every piece: it must enter the
     * target's space aimed within the radius of the room centre, and reach that point. Returns the piece count.
     */
    private fun volley(main: MainGame, container: GameContainer, state: InGameState, shooter: Ship, name: String, room: Room): Int {
        val target = room.ship
        val bp = state.blueprintManager[name] as FlakBlueprint
        val weapons = shooter.weapons ?: error("$name: the shooter has no weapons system")
        for (hp in shooter.hardpoints) hp.weapon?.let { weapons.setWeaponPower(it, false) }
        weapons.energyLevels = 8
        repeat(8) { weapons.increasePower() }
        val w = bp.buildInstance(shooter) as FlakBlueprint.FlakInstance
        shooter.hardpoints[0].weapon = w
        w.bindToWeaponsSystem(weapons)
        if (!weapons.setWeaponPower(w, true)) w.forceSetPowered(true)
        w.timeCharged = w.chargeTime
        w.extraCharges = w.maxExtraCharges
        shooter.projectiles.clear(); target.projectiles.clear()
        soften(target)
        if (shooter.isPlayerShip) weapons.selectedTargets.targetRoom(0, room) else w.fire { room }
        val centre = room.pixelCentre
        val pieces = IdentityHashMap<AbstractProjectile, Piece>()
        fun pieces(of: Ship) = of.projectiles.filterIsInstance<AbstractWeaponProjectile>().filter { it.type === bp }
        var fired = false
        var charges = 1
        for (i in 0 until 600) {
            soften(target)
            main.update(container, 0.05f)
            charges = maxOf(charges, w.hsVolleyCharges)
            for (p in pieces(shooter)) pieces.getOrPut(p) { Piece() }.seen = "shooter@${p.position.xf.toInt()},${p.position.yf.toInt()}" +
                " paused ${state.isPaused}"
            for (p in pieces(target)) {
                val piece = pieces.getOrPut(p) { Piece() }
                piece.seen = "target@${p.position.xf.toInt()},${p.position.yf.toInt()}"
                if (!p.hsInTargetSpace) continue
                piece.entered = true
                val t = p.targetPos
                val tx = if (t is FPoint) t.xf else t.x.toFloat()
                val ty = if (t is FPoint) t.yf else t.y.toFloat()
                val dx = tx - centre.x; val dy = ty - centre.y
                piece.worst = maxOf(piece.worst, sqrt(dx * dx + dy * dy))
                val px = p.position.xf - tx; val py = p.position.yf - ty
                piece.last = minOf(piece.last, sqrt(px * px + py * py))
                piece.step = p.speed * 0.05f + 1f
            }
            if (pieces.isNotEmpty()) fired = true
            if (fired && i > 20 && pieces(shooter).isEmpty() && pieces(target).isEmpty()) break
        }
        val who = if (shooter.isPlayerShip) "player" else "enemy"
        check(fired) { "$name ($who) never fired" }
        val expected = bp.miniProjectiles.size * maxOf(1, w.hsNumShots) * charges
        check(pieces.size == expected) { "$name ($who): ${pieces.size} pieces, want $expected" }
        val entered = pieces.values.count { it.entered }
        check(entered == expected) {
            "$name ($who): $entered of $expected pieces reached the target's space (last seen ${pieces.values.map { it.seen }}, powered ${w.isPowered})"
        }
        val off = pieces.values.filter { it.worst > bp.radius + 0.01f }
        check(off.isEmpty()) {
            "$name ($who): ${off.size} of $expected pieces aimed outside the radius ${bp.radius} (worst ${off.maxOf { it.worst }})"
        }
        // Every piece comes to its point (within one frame's flight of 0.05 s); one off every room flies on from there.
        val away = pieces.values.filter { it.last > it.step }
        check(away.isEmpty()) { "$name ($who): ${away.size} of $expected pieces never came to their point (${away.maxOf { it.last }} px)" }
        shooter.hardpoints[0].weapon?.let { weapons.setWeaponPower(it, false) }
        return expected
    }

    private fun flakVolleys(main: MainGame, container: GameContainer): String {
        // Multiverse's Flak I is SHOTGUN_1 (two pieces and a fake one); it keeps the vanilla names too.
        val vanilla = listOf("SHOTGUN", "SHOTGUN_2", "SHOTGUN_PLAYER", "SHOTGUN_1")
        val state = fixture(main, container)
        // Under Hyperspace the mod's own flak guns too: up to twelve, spread over the sorted list.
        val modded = if (!xyz.znix.xftl.hs.HS.active) emptyList() else {
            val all = state.blueprintManager.blueprints.values.filterIsInstance<FlakBlueprint>()
                .filter { it.name !in vanilla && it.miniProjectiles.isNotEmpty() && it.radius > 0 }.map { it.name }.sorted()
            if (all.size <= 12) all else List(12) { all[it * all.size / 12] }
        }
        val out = ArrayList<String>()
        // An enemy's Flak I at the player (Multiverse's is SHOTGUN_1).
        run {
            val enemy = state.enemy ?: error("no enemy")
            val enemyGun = listOf("SHOTGUN_1", "SHOTGUN").first { state.blueprintManager.getOrNull(it) is FlakBlueprint }
            val n = volley(main, container, state, enemy, enemyGun, state.player.rooms.first { it.system != null })
            out += "enemy $enemyGun $n"
        }
        for (name in vanilla + modded) {
            if (state.blueprintManager.getOrNull(name) !is FlakBlueprint) continue
            val enemy = state.enemy ?: error("the enemy left")
            val n = volley(main, container, state, state.player, name, enemy.rooms.first { it.system != null })
            out += "$name $n"
        }
        return "flak volleys whole (" + out.joinToString("; ") + ")"
    }

    private fun nebulaSensors(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sensors = state.player.systems.filterIsInstance<xyz.znix.xftl.systems.Sensors>().firstOrNull()
            ?: error("the fixture ship has no sensors")
        check(sensors.scriptedPowerLimit == null && sensors.iconColourName == "green") {
            "sensors before the nebula: limit ${sensors.scriptedPowerLimit}, icon ${sensors.iconColourName}"
        }
        xyz.znix.xftl.hs.render.HsConsole.run(state, "EVENT NEBULA_REBEL")
        repeat(10) { main.update(container, 0.05f) }
        BrowserDiagnostics.closeBoxes(main, container)
        val game = main.currentState as InGameState
        check(game.currentBeacon.environmentTypes.any { it.isNebula }) { "NEBULA_REBEL made no nebula: ${game.currentBeacon.environmentTypes}" }
        val out = ArrayList<String>()
        fun expectCapped(ship: Ship, who: String) {
            val s = ship.systems.filterIsInstance<xyz.znix.xftl.systems.Sensors>().firstOrNull() ?: return
            check(s.scriptedPowerLimit == 0 && s.scriptedLockFrom == 0 && s.effectivePower == 0) {
                "$who sensors in the nebula: limit ${s.scriptedPowerLimit}, lock from ${s.scriptedLockFrom}, power ${s.effectivePower}"
            }
            check(s.damaged || s.iconColourName == "grey") { "$who sensors icon ${s.iconColourName} in the nebula, want grey" }
            out += "$who capped"
        }
        expectCapped(game.player, "player")
        check(!sensors.isIonised && !sensors.damaged) { "the player's sensors are damaged or ionised" }
        // The crew-slot icon goes with the power (FUN_0063b8a0), on the desktop box and the touch box alike.
        check(!sensors.boxCrewSlotShown) { "the sensors' crew-slot icon is still shown in the nebula" }
        // The hover box (0x004df760): no power line for the unpowered subsystem, then temp_disabled.
        val tip = xyz.znix.xftl.game.SystemButtonTooltip(game, sensors).getText()
        val status = tip.substringAfter(game.translator["status"]).lines().map { it.trim() }.filter { it.isNotEmpty() }
        check(status.firstOrNull() == "-" + game.translator["temp_disabled"]) { "the sensors' hover box status in the nebula: $status" }
        game.enemy?.let { expectCapped(it, "enemy") }
        return "nebula sensors capped (" + out.joinToString(", ") + ")"
    }
}
