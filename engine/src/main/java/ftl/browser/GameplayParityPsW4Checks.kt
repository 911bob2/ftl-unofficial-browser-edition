package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.RealSoundManager
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.IRoomTargetingWeapon

/**
 * Parity sweep W4 (enemy ships, enemy AI and the flagship): rule checks for what the sweep fixed.
 * - WeaponControl::OnLoop (1.6.14 0x56c1a0, 1.6.9 0x553da0) and the oracle (ps-w4-ai): a powered weapon's aim at an
 *   escaping enemy stays through the countdown and the jump-away, and the weapon fires at the jumping ship once
 *   charged; the leaving ship plays jumpLeave (ShipManager::JumpLeave 0x49c9c0).
 * - CombatAI::OnLoop (1.6.14 0x62c510): no hacking probe or pulse while either ship is jumping or the target is dying.
 * - CrewAI::CheckForHealing (1.6.14 0x6270f0): the enemy's medbay power follows crew health and healing tasks.
 * - ShipManager::PrepareSuperBarrage (1.6.14 0x4a6a90): the vanilla flagship's barrage bursts from its centre.
 */
internal object GameplayParityPsW4Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val barrage = BrowserGameplayParityDiagnostics.vanillaOnly("barrage burst") { barrageBurst(main, container) }
            return "psW4 ok: ${aimsAtJumpingEnemy(main, container)}, ${hackingWhileJumping(main, container)}, " +
                "${medbayNeed(main, container)}, $barrage"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun spawn(state: InGameState): Ship {
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        state.debugFlags.noEnemyFire.set = true
        return state.enemy!!
    }

    private fun aimsAtJumpingEnemy(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state)
        val player = state.player
        val weapons = player.weapons!!
        player.purchasedReactorPower = 25
        weapons.energyLevels = 8
        val slot = player.hardpoints.indexOfFirst { it.weapon is IRoomTargetingWeapon }
        check(slot >= 0) { "${player.type.name} has no room-targeting weapon" }
        val weapon = player.hardpoints[slot].weapon!!
        check(weapons.setWeaponPower(weapon, true)) { "The fixture weapon took no power" }

        enemy.surrenderHealth = 0
        enemy.escapeHealth = 0
        check(enemy.canChargeFTL) { "The fixture enemy can't charge its FTL" }
        // A 1 s countdown; the weapon is full 2 s from now, halfway through the 2 s jump-away.
        enemy.escapeTimer = 1f
        weapon.timeCharged = weapon.chargeTime - 2f
        weapons.selectedTargets.targetRoom(slot, enemy.rooms.first())

        val sounds = main.gameContent.sounds as? RealSoundManager
        val played = ArrayList<String>()
        sounds?.traceSink = { kind, name -> if (kind == "play") played += name }
        var countdownFrames = 0
        var jumpFrames = 0
        var frames = 0
        var fired = false
        var firedInJump = false
        try {
            while (state.enemy === enemy && frames++ < 200) {
                main.update(container, DT)
                if (state.enemy !== enemy) break
                if (weapon.isFiring) {
                    if (!fired) firedInJump = enemy.browserJumping
                    fired = true
                }
                if (enemy.browserJumping) jumpFrames++ else countdownFrames++
                check(fired || weapons.selectedTargets.getTarget(slot) != null) {
                    "The aim went before the weapon fired (frame $frames, jumping ${enemy.browserJumping}, timer ${enemy.escapeTimer})"
                }
            }
        } finally {
            sounds?.traceSink = null
        }
        check(state.enemy !== enemy) { "The escaping enemy never left (timer ${enemy.escapeTimer})" }
        check(countdownFrames >= 10 && jumpFrames >= 10) { "Countdown $countdownFrames frames, jump $jumpFrames frames" }
        check(fired && firedInJump) { "The weapon did not fire at the jumping enemy (fired $fired, in the jump $firedInJump)" }
        check(sounds == null || played.count { it == "jumpLeave" } == 1) { "The escape played ${played.filter { it.startsWith("jump") }}" }
        return "aims kept through the countdown and the jump, the weapon fires at the leaving ship"
    }

    /**
     * ShipManager::PrepareSuperBarrage (1.6.14 0x4a6a90): the stage 3 barrage's seven heavy lasers leave from the middle
     * of the flagship, each on a heading of its own, with a launch sound each.
     */
    private fun barrageBurst(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        boss.stage = 3
        state.currentBeacon = boss.beacon!!
        if (state.enemy == null) check(state.browserSpawnBossHere()) { "Flagship did not spawn" }
        val flagship = state.enemy!!
        state.shipUI.diagnosticDialogue?.selectOption(0)
        val sounds = main.gameContent.sounds as? RealSoundManager
        val played = ArrayList<String>()
        sounds?.traceSink = { kind, name -> if (kind == "play") played += name }
        val before = flagship.projectiles.size
        try {
            boss.updateShip(flagship, 31f)
        } finally {
            sounds?.traceSink = null
        }
        val shots = flagship.projectiles.subList(before, flagship.projectiles.size)
        check(shots.size == 7) { "Barrage of ${shots.size}" }
        val centre = xyz.znix.xftl.hs.ship.HsBoss.shipCentre(flagship)
        check(shots.all { it.position.x == centre.x && it.position.y == centre.y }) {
            "Barrage shots not from the ship's centre $centre: ${shots.map { it.position }}"
        }
        check(shots.map { (it as xyz.znix.xftl.weapons.AbstractProjectile).rotation }.distinct().size > 1) { "Every barrage shot left on one heading" }
        check(sounds == null || played.size == 7) { "Barrage sounds $played" }
        return "barrage bursts from the centre, a sound a shot"
    }

    /**
     * CrewAI::CheckForHealing (1.6.14 0x6270f0): the enemy's medbay gets its bar for crew below a quarter of their
     * health (99% while calm) or on a healing task, not for hurt crew that only stand in the medbay's room.
     */
    private fun medbayNeed(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var seed = 1
        var enemy: Ship
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            enemy = state.enemy!!
            check(seed < 400) { "No crewed pirate with a medbay" }
        } while (enemy.medbay == null || enemy.isAutomated || enemy.friendlyCrew.count { it is LivingCrew } < 2)
        state.debugFlags.noEnemyFire.set = true
        val ai = ShipAI(enemy, state.player)
        val medbay = enemy.medbay!!
        val hurt = enemy.friendlyCrew.filterIsInstance<LivingCrew>().first()
        hurt.jumpTo(enemy.findSpaceForCrew(medbay.room!!, hurt.mode))
        enemy.crewAI.update()
        check(!ai.medbayWanted()) { "The medbay wanted with the crew whole" }

        // A repair job: the crew AI is no longer calm, so half health sends nobody to heal.
        enemy.engines!!.dealDamage(1, 0)
        hurt.health = hurt.maxHealth * 0.5f
        enemy.crewAI.update()
        check(!enemy.crewAI.isCalm && !enemy.crewAI.isHealing(hurt)) { "Fixture: calm ${enemy.crewAI.isCalm}" }
        check(!ai.medbayWanted()) { "Half health in the medbay's room (no healing task) powered the medbay" }
        hurt.health = hurt.maxHealth * 0.2f
        check(ai.medbayWanted()) { "Crew below a quarter of their health left the medbay off" }
        return "medbay power for the hurt or healing only"
    }

    private fun hackingWhileJumping(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state)
        val player = state.player
        check(ShipAI.hackingAllowed(enemy, player)) { "Hacking held back in a plain fight" }
        enemy.browserJumping = true
        check(!ShipAI.hackingAllowed(enemy, player)) { "Hacking allowed while the hacker jumps" }
        enemy.browserJumping = false
        player.browserJumping = true
        check(!ShipAI.hackingAllowed(enemy, player)) { "Hacking allowed while the target jumps" }
        player.browserJumping = false
        val hull = player.health
        player.health = 0
        check(!ShipAI.hackingAllowed(enemy, player)) { "Hacking allowed at a dying target" }
        player.health = hull
        return "no hacking while either ship jumps"
    }
}
