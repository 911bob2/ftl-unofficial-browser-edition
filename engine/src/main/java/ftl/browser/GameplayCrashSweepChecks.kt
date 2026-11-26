package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.BoardingDrone
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.FlyingDroneProjectile
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem

/**
 * Round 26 crash sweep (BrowserCrashSweepDiagnostics finds them by play; these hold each one found, vanilla, F2, F3):
 *
 * - teleport at a cloaked ship that goes: the player commands a teleport at a room of the enemy while it is cloaked
 *   (the command waits for the cloak, CombatControl::GetTeleportationCommand 0x57f370), and the enemy escapes or is
 *   destroyed before its cloak ends. Natively the command names a room of the current target, CombatControl::Clear
 *   (0x57d2c0) drops the target, and the next GetTeleportationCommand, with no target to be cloaked, disarms it and
 *   CompleteShip::InitiateTeleport finds no enemy ship (HS TeleporterBugFixes.cpp): nothing is sent. Ours kept the
 *   room of the gone ship and waited on that ship's cloak, which no longer runs, so every save after it threw
 *   "Unregistered object (Ship)" from the teleporter's saved command.
 * - boarding pod at a fight that ends: the player's pod is still in its own ship's space when the enemy escapes (its
 *   jump ends a frame after the launch) or its crew dies. The original pod is a SpaceDrone of its owner's drone list,
 *   saved with it only while deployed (BoarderPodDrone::SaveState 0x420a70), and CombatControl::Clear's SetTarget(0)
 *   (0x4a2b30) takes its target at the change. Ours left a live pod aimed at the gone ship in the player's projectile
 *   list for a frame (its save named a ship the save does not hold), and a pod that died there stayed listed until its
 *   next update (taken off the target's list only), so a save in between wrote the drone with neither pod nor body
 *   and its load launched a pod at no enemy (NullPointerException).
 */
internal object GameplayCrashSweepChecks {
    private const val DT = .05f

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
            case("teleportAtCloakedEscape") { teleportAtCloaked(main, container, escaped = true) }
            case("teleportAtCloakedDestroyed") { teleportAtCloaked(main, container, escaped = false) }
            case("podAtEscapedShip") { podAtFightEnd(main, container, escaped = true) }
            case("podAtCrewKilledShip") { podAtFightEnd(main, container, escaped = false) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "crash sweep finds ok: " + results.joinToString(", ")
    }

    /** A ship with every system its layout has room for and full power (the console's SYS and GOD). */
    private fun install(state: InGameState, ship: Ship, vararg systems: String) {
        for (name in systems) {
            if (ship.systems.any { it.codename == name }) continue
            // HsConsole's SYS: vanilla's AddSystem in the layout's room for it, Hyperspace's AddSystem
            if (xyz.znix.xftl.hs.HS.active) {
                if (xyz.znix.xftl.hs.systems.HsSystemInstall.willReplace(ship, name) == null)
                    xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, xyz.znix.xftl.hs.systems.HsSystems.nameToId(name))
                continue
            }
            val slot = ship.systemSlots.firstOrNull { it.system.name == name && it.room.system == null } ?: continue
            slot.room.setSystem(slot)
            slot.room.system?.let { ship.browserPowerNewSystem(it) }
        }
        ship.purchasedReactorPower = 30
        ship.updateAvailablePower()
        for (system in ship.systems) if (system is MainSystem) repeat(system.energyLevels) { system.increasePower() }
        ship.update(0f)
    }

    private fun teleportAtCloaked(main: MainGame, container: GameContainer, escaped: Boolean): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS")
        val player = state.player
        install(state, player, "teleporter")
        val teleporter = checkNotNull(player.teleporter) { "Fixture: no teleporter on ${player.type.name}" }

        // An enemy that has a cloak (any spec, its layout's cloak installed and powered).
        val specs = state.eventManager.getShips().sortedBy { it.name }
        var enemy: Ship? = null
        for (spec in specs) {
            state.debugSpawnShip(spec, Difficulty.NORMAL, 6, 71)
            val e = state.enemy ?: continue
            install(state, e, "cloaking")
            if (e.cloaking != null && e.rooms.size > 1) { enemy = e; break }
        }
        checkNotNull(enemy) { "Fixture: no enemy layout has room for a cloak" }
        val cloak = enemy.cloaking!!
        repeat(cloak.energyLevels) { cloak.increasePower() }
        enemy.update(0f)
        cloak.activateCloak()
        state.updateGameState(DT)
        check(enemy.isCloakActive) { "Fixture: the enemy's cloak did not start (${cloak.powerSupplied} power)" }

        // A crew member on the teleporter, the teleport commanded at an enemy room: it waits for the cloak.
        val crew = player.crew.filterIsInstance<LivingCrew>().first()
        crew.jumpTo(teleporter.room!!, ConstPoint.ZERO)
        teleporter.selectTeleportAction(true, enemy.rooms[1])
        repeat(3) { state.updateGameState(DT) }
        check(teleporter.browserCommandedRoom?.ship === enemy) { "Fixture: the command did not wait for the cloak" }

        if (escaped) state.hsEnemyJumpLeave() else enemy.health = 0
        var frames = 0
        while (state.enemy === enemy && frames++ < 400) {
            if (escaped) main.update(container, DT) else state.updateGameState(DT)
        }
        check(state.enemy !== enemy) { "Fixture: the enemy did not ${if (escaped) "leave" else "go"} in $frames frames" }
        repeat(4) { main.update(container, DT) }
        val how = if (escaped) "escaped" else "destroyed"
        check(teleporter.browserCommandedRoom == null) { "The teleport commanded at the cloaked ship still waits after it $how" }

        // The save the event box's opening wrote, and one now; the loaded run goes on with every crew member aboard.
        main.writeRunSave()
        val xml = checkNotNull(BrowserFiles.getRun()) { "No run save" }
        main.loadSavedGame(BrowserXml.parse(xml))
        val loaded = main.currentState as InGameState
        check(loaded.player.teleporter?.browserCommandedRoom == null) { "The loaded run has a teleport command" }
        check(loaded.player.crew.count { it is LivingCrew && it.ownerShip === loaded.player } == player.crew.count { it is LivingCrew && it.ownerShip === player }) {
            "Crew lost over the load"
        }
        repeat(20) { main.update(container, DT) }
        return "teleport at a cloaked ship that $how: dropped, saves"
    }

    private fun pods(ship: Ship, drone: BoardingDrone) = ship.projectiles.count { it is FlyingDroneProjectile && it.drone === drone }

    private fun podAtFightEnd(main: MainGame, container: GameContainer, escaped: Boolean): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        val player = state.player
        install(state, player, "drones")
        val drones = checkNotNull(player.drones) { "Fixture: no drone system on ${player.type.name}" }
        player.dronesCount = 10
        val boarder = state.blueprintManager["BOARDER"] as DroneBlueprint
        check(player.addBlueprint(boarder, true, false)) { "Fixture: BOARDER not equipped" }
        val slot = drones.drones.indexOfFirst { it?.type === boarder }
        check(slot >= 0) { "Fixture: BOARDER in no drone slot" }

        // Any enemy (first by name; it comes after the drone, so the drone has its target, ShipManager::SetTarget).
        val spec = state.eventManager.getShips().sortedBy { it.name }.first()
        state.debugSpawnShip(spec, Difficulty.NORMAL, 6, 71)
        val enemy = checkNotNull(state.enemy) { "Fixture: no enemy" }
        state.debugFlags.noEnemyFire.set = true
        // The enemy's jump away runs in the game's own update (InGameState.update), not only the ships'.
        val frame = { if (escaped) main.update(container, DT) else state.updateGameState(DT) }
        if (escaped) {
            // The jump away takes 2 s: the pod launches in its last 0.2 s, so it is still in the player's space.
            state.hsEnemyJumpLeave()
            repeat(36) { frame() }
        }
        check(drones.setDronePower(slot, true)) { "Fixture: the BOARDER did not power" }
        var frames = 0
        var drone: BoardingDrone? = null
        while (frames++ < 4) {
            frame()
            drone = drones.drones[slot]?.instance as? BoardingDrone
            if (drone != null && pods(player, drone) > 0) break
        }
        checkNotNull(drone) { "Fixture: the BOARDER launched no pod" }
        check(pods(player, drone) == 1) { "Fixture: the pod is not in the player's space (${pods(player, drone)}, ${pods(enemy, drone)} in the enemy's)" }

        val how = if (escaped) "escaped" else "crew died"
        if (escaped) {
            frames = 0
            var before = 0
            while (state.enemy === enemy && frames++ < 60) { before = pods(player, drone); frame() }
            check(state.enemy !== enemy) { "Fixture: the enemy did not leave in $frames frames" }
            check(before == 1) { "Fixture: the pod left the player's space before the enemy left ($frames frames)" }
        } else {
            state.browserEndHostility()
            frames = 0
            while (drones.drones[slot]?.instance === drone && frames++ < 10) state.updateGameState(DT)
            check(drones.drones[slot]?.instance !== drone) { "Fixture: the pod's drone stayed out after the fight ended" }
        }
        // The frame the fight ended in: the pod is out of play, in no list a save writes.
        check(pods(player, drone) == 0) { "A pod at the ship that $how is still in the player's projectiles" }

        main.writeRunSave()
        val xml = checkNotNull(BrowserFiles.getRun()) { "No run save" }
        main.loadSavedGame(BrowserXml.parse(xml))
        val loaded = main.currentState as InGameState
        check(loaded.player.projectiles.none { it is FlyingDroneProjectile }) { "The loaded run has a pod in flight" }
        repeat(20) { main.update(container, DT) }
        return "pod at a ship whose fight ended ($how): out of play, saves"
    }
}
