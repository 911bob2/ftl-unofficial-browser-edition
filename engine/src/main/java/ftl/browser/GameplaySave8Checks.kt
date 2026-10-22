package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractProjectile
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * Playtest crash (2026-10-21): the first shot of a Burst Laser destroyed the enemy, the reward window paused the
 * game with the rest of the volley queued, and every save then threw "Unregistered object" because the weapon and
 * its shots in flight still pointed at the removed ship. A save must drop what is aimed at a ship that is gone.
 */
internal object GameplaySave8Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            return "save8 ok: ${killMidVolley(main, container)}"
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun killMidVolley(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        val player = state.player
        val weapons = player.weapons!!
        weapons.energyLevels = 8
        player.purchasedReactorPower = 25
        val laser = (state.blueprintManager["LASER_BURST_3"] as AbstractWeaponBlueprint).buildInstance(player) as AbstractProjectileWeaponInstance
        player.hardpoints[0].weapon = laser
        player.cargoUpdated()
        check(weapons.setWeaponPower(laser, true)) { "Fixture weapon did not power" }

        // The ship explodes for Ship.DESTRUCTION_TIME (3 s) before it is removed. A volley fired at the wreck near
        // the end is still queued when the ship goes and the reward window pauses the game.
        val enemy = state.enemy!!
        enemy.health = 0
        repeat(135) { main.update(container, .02f) }
        check(state.enemy === enemy && enemy.isDead && !enemy.isGone) { "Fixture enemy is not exploding" }
        state.shipUI.weaponHotkeyPressed(0, false)
        val listener = state.clickEvent ?: error("Weapon did not arm")
        state.clickEvent = null
        listener.roomClicked(enemy.rooms.first(), container)
        laser.timeCharged = laser.chargeTime
        weapons.selectedTargets.fireChargedWeapons()
        check(laser.isFiring) { "Volley at the wreck did not start" }

        var frames = 0
        while (state.enemy === enemy && frames++ < 100) main.update(container, .02f)
        check(state.enemy !== enemy) { "Destroyed enemy was not removed" }
        val orphaned = player.projectiles.count { (it as? AbstractProjectile)?.targetShip === enemy }
        check(laser.isFiring || orphaned > 0) { "Fixture did not leave a volley or shot aimed at the destroyed ship" }

        state = BrowserGameplayParityDiagnostics.reload(main, state)
        val restored = state.player.hardpoints[0].weapon as AbstractProjectileWeaponInstance
        check(!restored.isFiring) { "Volley at the destroyed ship came back after a reload" }
        check(state.player.projectiles.none { p -> (p as? AbstractProjectile)?.targetShip.let { it != null && it !== state.player && it !== state.enemy } }) {
            "A shot at the destroyed ship came back after a reload"
        }
        return "saved after a kill mid-volley (firing=${laser.isFiring}, shots in flight=$orphaned)"
    }
}
