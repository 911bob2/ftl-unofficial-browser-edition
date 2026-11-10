package ftl.browser

import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Parity sweep follow-up FU3 (weapons, drones and combat): the HUD weapon tooltip's crew damage
 * (WeaponBlueprint::GetDescription), drones equipped while a ship is at the beacon (ShipManager::CreateSpaceDrone and
 * SetTarget, DroneSystem::PowerDrone's CanBeDeployed test), and Hyperspace's enemy aim with more shots than rooms
 * (CombatAI::UpdateWeapons rewrite).
 */
internal object GameplayParityPsFu3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val tip = tooltip(main, container)
            val drones = dronesEquippedInFight(main, container)
            val aims = aimPoints()
            val shard = crystalShardLaser(main, container)
            return "ps-fu3 ok: $tip, $drones, $aims, $shard"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun tooltipText(weapon: AbstractWeaponInstance): String =
        (weapon.weaponBarTooltip as AbstractWeaponInstance.WeaponButtonTooltip).getText()

    /**
     * WeaponBox::GenerateTooltip (1.6.9 0x4f5b80) is WeaponBlueprint::GetDescription (1.6.14 0x605a80): the crew damage
     * line only for a positive persDamage, times 15 (Small Bomb 2 -> 30; the healing bomb's -10 has none).
     */
    private fun tooltip(main: MainGame, container: GameContainer): String {
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.render.HsMoreInfo.redesignedWeaponTooltips())
            return "weapon tooltip (redesigned: not this path)"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val tr = state.translator
        val line = { v: Int -> tr["personnel_damage"].replace("\\1", v.toString()) }
        val bomb = (state.blueprintManager["BOMB_1"] as AbstractWeaponBlueprint)
        check(bomb.personnelDamage == 2) { "BOMB_1 persDamage ${bomb.personnelDamage}" }
        val bombText = tooltipText(bomb.buildInstance(state.player))
        check(bombText.contains(line(30))) { "Small Bomb tooltip without '${line(30)}': $bombText" }
        check(!bombText.contains(line(2))) { "Small Bomb tooltip shows the raw crew damage: $bombText" }
        val heal = state.blueprintManager.getOrNull("BOMB_HEAL") as? AbstractWeaponBlueprint
        if (heal != null) {
            check(heal.personnelDamage < 0) { "BOMB_HEAL persDamage ${heal.personnelDamage}" }
            val healText = tooltipText(heal.buildInstance(state.player))
            val head = tr["personnel_damage"].substringBefore("\\1")
            check(!healText.contains(head)) { "Healing bomb tooltip has a crew damage line: $healText" }
        }
        return "weapon tooltip crew damage"
    }

    /**
     * A combat or boarding drone equipped while another ship is at the beacon: vanilla's CreateSpaceDrone gives it no
     * target and SetTarget only runs when a ship comes, so DroneSystem::PowerDrone refuses it until the next ship
     * (1.6.14: ps-fu3-van fv-drone). A drone equipped before the ship came, and any drone once the next ship is
     * there, launches. Hyperspace's CreateSpaceDrone hook aims a new combat drone at the current target, so it
     * launches; a boarding drone still waits (HS oracle ps-fu3-pdrone, -pdrone2).
     */
    private fun dronesEquippedInFight(main: MainGame, container: GameContainer): String {
        val hs = xyz.znix.xftl.hs.HS.active
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        val player = state.player
        val drones = checkNotNull(player.drones) { "PLAYER_SHIP_CIRCLE has no drone system" }
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        player.purchasedReactorPower = 25
        drones.energyLevels = 8
        player.updateAvailablePower()
        player.dronesCount = 10
        check(drones.drones.size >= 3) { "Need three drone slots, have ${drones.drones.size}" }
        val combat = state.blueprintManager["COMBAT_1"] as DroneBlueprint
        val boarder = state.blueprintManager["BOARDER"] as DroneBlueprint

        // Equipped at an empty beacon, then a ship comes: it has its target.
        check(player.addBlueprint(combat, true)) { "COMBAT_1 not equipped" }
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        check(drones.setDronePower(0, true)) { "A drone equipped before the ship came did not launch" }

        // Equipped during the fight.
        check(player.addBlueprint(boarder, true) && player.addBlueprint(combat, true)) { "Drones not equipped" }
        val launched = listOf(1, 2).map { drones.setDronePower(it, true) }
        if (hs) check(launched == listOf(false, true)) { "Hyperspace: boarder waits, combat drone launches; got $launched" }
        else check(launched == listOf(false, false)) { "Vanilla: a drone equipped in the fight launched: $launched" }
        check(player.dronesCount == if (hs) 8 else 9) { "Drone parts ${player.dronesCount}" }

        // The next ship: every drone has a target again.
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 72)
        val waiting = if (hs) listOf(1) else listOf(1, 2)
        val again = waiting.map { drones.setDronePower(it, true) }
        check(again.all { it }) { "The drones equipped in the last fight did not launch at the next ship: $again" }
        return "drones equipped in a fight (${if (hs) "Hyperspace: combat launches, boarder waits" else "vanilla: wait for the next ship"})"
    }

    /**
     * More aim points than rooms: vanilla stops at one per room (the native loop would not end), Hyperspace adds a
     * fresh pick for every point past the room count; below it a repeat replaces the earlier point.
     */
    private fun aimPoints(): String {
        val seq = intArrayOf(0, 1, 0, 2, 1, 1, 2, 0)
        fun picks(): () -> Int { var i = 0; return { seq[i++ % seq.size] } }
        check(ShipAI.aimPoints(2, 3, false, picks()) == listOf(0, 1)) { "Two of three rooms" }
        check(ShipAI.aimPoints(5, 3, false, picks()) == listOf(1, 0, 2)) { "Vanilla past the rooms" }
        check(ShipAI.aimPoints(5, 3, true, picks()) == listOf(1, 0, 2, 1, 1)) { "Hyperspace past the rooms" }
        check(ShipAI.aimPoints(3, 3, true, picks()) == listOf(1, 0, 2)) { "Hyperspace at the room count" }
        return "aim points past the room count"
    }

    /**
     * A custom augment's laser crystal shard (Hyperspace ShipManager_CheckCustomCrystalAugment): LaserBlast(pos, ship,
     * targetId, GetRandomRoomCenter()) with a random heading. Its target is that room's centre from the start, so in
     * the target's space it flies at the room (HS oracle ps-fu3-shlaser: target 70,122, a miss at 186,168).
     */
    private fun crystalShardLaser(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "crystal shard laser (Hyperspace only)"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 73)
        val player = state.player
        val enemy = checkNotNull(state.enemy) { "No enemy for the crystal shard" }
        val before = player.projectiles.size
        xyz.znix.xftl.hs.weapons.HsCrystalShards.fire(player, enemy, "LASER_BURST_1", 1f, player.rooms.first().pixelCentre)
        val shots = player.projectiles.drop(before).filterIsInstance<xyz.znix.xftl.weapons.AbstractWeaponProjectile>()
        check(shots.isNotEmpty()) { "No laser shard" }
        for (shot in shots) {
            val t = shot.targetPos
            check(enemy.rooms.any { it.pixelCentre.x == t.x && it.pixelCentre.y == t.y }) { "Laser shard aimed at ${t.x},${t.y}, not a room centre" }
        }
        return "crystal shard laser aims at a room centre"
    }
}
