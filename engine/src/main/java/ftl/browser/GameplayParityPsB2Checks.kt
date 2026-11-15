package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.SelectedTarget
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.BeamProjectile
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.atan2

/**
 * User bug B2: beams against a Zoltan shield. Every mode (vanilla, F2, F3), both directions (the player's beam at the
 * enemy's super shield and the enemy's at the player's), a beam weapon and a beam drone:
 * - BeamWeapon::OnUpdate (1.6.14 0x438880, 1.6.9 0x435c90) sets bDamageSuperShield when the swipe passes 0.33 and,
 *   for a length over 20, 0.8; on that frame BeamWeapon::CollisionCheck (1.6.14 0x438c30, 1.6.9 0x436040) calls
 *   DamageShield with max(1, damage) and a new selfId (Globals::GetNextSpaceId), which Shields::CollisionReal
 *   (0x6336f0) takes off the super shield. A beam's own frame hits carry 0 damage and the beam's selfId, so they only
 *   move its flash. HS oracle ps-b2-p / -ew / -ed: 1 damage and 0 damage beams take 2 points each, Halberd 4, the
 *   drone's 2 per beam, at either ship.
 * The target's super shield is put back to 5 after each frame, so no beam gets through it.
 */
internal object GameplayParityPsB2Checks {
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
            case("weaponAtEnemy") { weapon(main, container, enemyTarget = true) }
            case("weaponAtPlayer") { weapon(main, container, enemyTarget = false) }
            case("droneAtEnemy") { drone(main, container, enemyTarget = true) }
            case("droneAtPlayer") { drone(main, container, enemyTarget = false) }
            // B2b (O-B2-4): the same drone from an enemy built from a player blueprint
            case("droneFromPlayerBlueprint") { drone(main, container, enemyTarget = false, enemyBlueprint = "PLAYER_SHIP_CIRCLE") }
            case("playerBlueprintEnemySide") { playerBlueprintEnemySide(main, container) }
            // B2b (O-B2-3)
            case("superShieldFill") { superShieldFill(main, container) }
            case("superShieldNativeSave") { superShieldNativeSave(main, container) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-b2 ok: " + results.joinToString(", ")
    }

    /** The super shield points one beam takes: one or two zaps of max(1, damage) (no ion in these beams). */
    private fun perBeam(bp: BeamBlueprint) = (if (bp.length > 20) 2 else 1) * maxOf(1, bp.damage)

    /** Points the target's super shield lost this frame; then it is put back to 5. */
    private fun lost(target: Ship): Int {
        val lost = 5 - target.superShield
        target.superShield = 5
        return lost
    }

    private fun giveSuperShield(target: Ship) {
        target.maxSuperShield = 5
        target.superShield = 5
    }

    /** The beams in flight at [target] (a weapon's in the shooter's list, a drone's in the target's). */
    private fun beamsAt(state: InGameState, target: Ship): List<BeamProjectile> =
        (state.player.projectiles + (state.enemy?.projectiles ?: emptyList())).filterIsInstance<BeamProjectile>()
            .filter { it.movingTarget === target }

    private fun weapon(main: MainGame, container: GameContainer, enemyTarget: Boolean): String {
        val done = ArrayList<String>()
        for (name in listOf("BEAM_1", "BEAM_FIRE")) {
            val state = BrowserGameplayParityDiagnostics.fresh(main, container)
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
            val enemy = state.enemy!!
            val shooter = if (enemyTarget) state.player else enemy
            val target = if (enemyTarget) enemy else state.player
            val weapons = checkNotNull(shooter.weapons) { "the shooter has no weapon system" }
            weapons.energyLevels = 8
            shooter.purchasedReactorPower = 25
            shooter.updateAvailablePower()
            val bp = state.blueprintManager[name] as BeamBlueprint
            val beam = bp.buildInstance(shooter) as BeamBlueprint.BeamInstance
            for (hp in shooter.hardpoints) hp.weapon?.let { if (it.isPowered) weapons.setWeaponPower(it, false) }
            shooter.hardpoints[0].weapon = beam
            shooter.cargoUpdated()
            check(weapons.setWeaponPower(beam, true)) { "$name did not power" }
            giveSuperShield(target)
            val start = target.rooms.first(); val end = target.rooms.last()
            val aim = SelectedTarget.BeamAim(beam, 0, target, start.pixelCentre)
            aim.angle = atan2((end.pixelCentre.y - start.pixelCentre.y).toFloat(), (end.pixelCentre.x - start.pixelCentre.x).toFloat())
            aim.length = bp.length
            aim.updateHitRooms()
            beam.timeCharged = beam.chargeTime
            beam.fire(aim)
            var points = 0
            var seen = false
            var frames = 0
            while (frames++ < 900) {
                main.update(container, 1f / 60f)
                points += lost(target)
                val live = beamsAt(state, target).isNotEmpty()
                if (live) seen = true
                if (seen && !live && !beam.isFiring) break
            }
            check(seen) { "$name at the ${if (enemyTarget) "enemy" else "player"}: no beam" }
            val expected = perBeam(bp)
            check(points == expected) { "$name at the ${if (enemyTarget) "enemy" else "player"}'s super shield took $points, native $expected" }
            done += "$name $points"
        }
        return (if (enemyTarget) "player beams at the enemy: " else "enemy beams at the player: ") + done.joinToString("/")
    }

    private fun drone(main: MainGame, container: GameContainer, enemyTarget: Boolean, enemyBlueprint: String = "AUTO_ASSAULT"): String {
        val beamDrone = main.gameContent.blueprintManager["COMBAT_BEAM"] as DroneBlueprint
        val bp = beamDrone.weaponBlueprint as BeamBlueprint
        val state: InGameState
        val owner: Ship
        if (enemyTarget) {
            state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
            owner = state.player
            clearDrones(owner)
            check(owner.addBlueprint(beamDrone, true)) { "COMBAT_BEAM not equipped" }
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        } else {
            state = BrowserGameplayParityDiagnostics.fresh(main, container)
            // An enemy with a drone system from the start (AUTO_ASSAULT, or the Engi cruiser's player blueprint).
            val spec = EnemyShipSpec(org.jdom2.Element("ship").setAttribute("name", "PSB2_DRONES").setAttribute("blueprint", enemyBlueprint),
                state.blueprintManager, state.eventManager)
            state.debugSpawnShip(spec, Difficulty.NORMAL, 4, 71)
            owner = state.enemy!!
            clearDrones(owner)
            check(owner.addBlueprint(beamDrone, true)) { "COMBAT_BEAM not equipped on the enemy" }
        }
        val target = if (enemyTarget) state.enemy!! else state.player
        val drones = owner.drones!!
        check(drones.setDronePower(drones.drones.indexOfFirst { it?.type === beamDrone }, true)) { "COMBAT_BEAM did not launch" }
        giveSuperShield(target)
        val beams = HashSet<BeamProjectile>()
        var points = 0
        var frames = 0
        // 30 s of the drone's beams; then the drone is powered off and the last beam ends by itself
        while (frames++ < 60 * 36) {
            main.update(container, 1f / 60f)
            points += lost(target)
            val live = beamsAt(state, target)
            beams += live
            if (frames == 60 * 30) drones.drones.indices.forEach { drones.setDronePower(it, false) }
            if (frames > 60 * 30 && live.isEmpty()) break
        }
        check(beams.size >= 2) { "COMBAT_BEAM at the ${if (enemyTarget) "enemy" else "player"}: ${beams.size} beams in 30 s" }
        val expected = beams.size * perBeam(bp)
        check(points == expected) {
            "COMBAT_BEAM at the ${if (enemyTarget) "enemy" else "player"}'s super shield: ${beams.size} beams took $points, native $expected"
        }
        return "COMBAT_BEAM at the ${if (enemyTarget) "enemy" else "player"}${if (enemyTarget) "" else " from $enemyBlueprint"}: ${beams.size} beams $points"
    }

    /**
     * B2b, O-B2-4: WorldManager::CreateShip (1.6.14 0x6149c0) gives the enemy side's ship iShipId 1 whatever its
     * blueprint (1.6.14 capture bv-td: PLAYER_SHIP_CIRCLE_3 loaded as an enemy fights the player with its beam drone; HS
     * oracle ps-b2-side: PLAYER_SHIP_STEALTH and PLAYER_SHIP_SALT_3 as enemies are iShipId 1, shoot and board the
     * player). The enemy built from a player blueprint is on the enemy side, its crew are intruders on the player's
     * ship, and it stays so across a save; the player's own blueprint is untouched.
     */
    private fun playerBlueprintEnemySide(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val spec = EnemyShipSpec(org.jdom2.Element("ship").setAttribute("name", "PSB2_PSIDE").setAttribute("blueprint", "PLAYER_SHIP_STEALTH"),
            state.blueprintManager, state.eventManager)
        state.debugSpawnShip(spec, Difficulty.NORMAL, 4, 71)
        val enemy = state.enemy!!
        check(!enemy.isPlayerShip && xyz.znix.xftl.hs.weapons.HsWeapons.shipId(enemy) == 1) { "the enemy built from PLAYER_SHIP_STEALTH is on the player's side" }
        check(state.player.isPlayerShip && xyz.znix.xftl.hs.weapons.HsWeapons.shipId(state.player) == 0) { "the player's ship left the player's side" }
        check((state.blueprintManager.getShip("PLAYER_SHIP_STEALTH")).isPlayerShip) { "the blueprint itself is no player ship any more" }
        val crew = enemy.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().filter { it.ownerShip === enemy }
        check(crew.isNotEmpty() && crew.none { it.belongsToPlayer }) { "the enemy's crew belongs to the player" }
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        val loaded = state.enemy!!
        check(loaded.name == "PLAYER_SHIP_STEALTH" && !loaded.isPlayerShip && xyz.znix.xftl.hs.weapons.HsWeapons.shipId(loaded) == 1) {
            "after a reload the enemy ${loaded.name} is on the player's side"
        }
        return "PLAYER_SHIP_STEALTH as an enemy is iShipId 1 (also after a reload)"
    }

    /**
     * B2b, O-B2-3: Shields::OnLoop (1.6.14 0x6342b0, 1.6.9 0x60e310): with ENERGY_SHIELD, super.second 0 gives
     * super.first 5 and super.second 5; Shields::Jump (0x6340e0) and saves keep super.second. So an augment gained in a
     * fight fills the shield at once, an emptied shield stays empty when the augment is removed and added again, and a
     * save does not refill it (HS oracle ps-b2-ss, 1.6.14 bv-tw / bv-td: AUG then LOAD, full in the first frame).
     */
    private fun superShieldFill(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        val player = state.player
        val aug = state.blueprintManager[xyz.znix.xftl.augments.AugmentBlueprint.ENERGY_SHIELD_NAME] as xyz.znix.xftl.augments.AugmentBlueprint
        check(!player.hasAugment(aug.name) && player.superShield == 0) { "the fixture ship has a super shield" }
        check(player.addBlueprint(aug, true)) { "ENERGY_SHIELD not added" }
        repeat(2) { main.update(container, 1f / 60f) }
        check(player.superShield == 5) { "ENERGY_SHIELD gained in a fight: super shield ${player.superShield}, native 5 at once" }
        player.superShield = 0
        repeat(2) { main.update(container, 1f / 60f) }
        check(player.superShield == 0) { "an emptied super shield came back to ${player.superShield}" }
        check(player.removeBlueprint(aug) && player.addBlueprint(aug, true)) { "ENERGY_SHIELD not removed and added again" }
        repeat(2) { main.update(container, 1f / 60f) }
        check(player.superShield == 0) { "removed and added again: ${player.superShield}, native 0 (super.second kept)" }
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        repeat(2) { main.update(container, 1f / 60f) }
        check(state.player.superShield == 0) { "after a reload the emptied super shield is ${state.player.superShield}, native 0" }
        return "ENERGY_SHIELD fills once (5), stays 0 when emptied, re-added and reloaded"
    }

    /**
     * B2b, O-B2-3, the original's save: it keeps super.first and super.second (0 on a ship that never had a super
     * shield, HS oracle ps-b2-ss a0 0|0), so an exported ship without the augment fills when it gains one, and an
     * imported hit shield (3 of 5) stays 3 (Shields::OnLoop 0x6342b0 fills only while super.second is 0).
     */
    private fun superShieldNativeSave(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val none = main.parseVanillaSaveForParity(xyz.znix.xftl.vanillasave.VanillaSaveWriter.writeToByteArray(state)).ship
        check(none.superShield == 0 && none.superShieldMax == 0) { "a ship without a super shield saves ${none.superShield}|${none.superShieldMax}, native 0|0" }
        val aug = state.blueprintManager[xyz.znix.xftl.augments.AugmentBlueprint.ENERGY_SHIELD_NAME] as xyz.znix.xftl.augments.AugmentBlueprint
        check(state.player.addBlueprint(aug, true)) { "ENERGY_SHIELD not added" }
        repeat(2) { main.update(container, 1f / 60f) }
        check(state.player.superShield == 5) { "ENERGY_SHIELD: super shield ${state.player.superShield}, native 5" }
        state.player.superShield = 3
        val bytes = xyz.znix.xftl.vanillasave.VanillaSaveWriter.writeToByteArray(state)
        val hit = main.parseVanillaSaveForParity(bytes).ship
        check(hit.superShield == 3 && hit.superShieldMax == 5) { "a hit super shield saves ${hit.superShield}|${hit.superShieldMax}, native 3|5" }
        main.startVanillaSaveForParity(bytes)
        state = main.currentState as InGameState
        repeat(2) { main.update(container, 1f / 60f) }
        check(state.player.superShield == 3) { "the imported hit super shield is ${state.player.superShield}, native 3" }
        return "the original's save keeps 0|0 without the augment and an imported 3|5"
    }

    private fun clearDrones(ship: Ship) {
        val drones = checkNotNull(ship.drones) { "the drone ship has no drone system" }
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        ship.purchasedReactorPower = 25
        drones.energyLevels = 8
        ship.updateAvailablePower()
        ship.dronesCount = 10
    }
}
