package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.Cloaking
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * Playtest C1, cloaking. A cloaked ship hides its interior (ShipManager::OnRender 0x4a3c00) and none of its rooms can
 * be picked (CombatControl hover 0x57dd70, SelectRoom 0x57eba0) unless the player has crew aboard: no highlight, the
 * red crosshair, and a click places no target while the weapon stays armed. The enemy AI aims no charged weapon at a
 * cloaked player (0x62c000) and fires once the cloak drops; the cloak adds a flat 60 to the dodge chance (0x4a0080).
 */
internal object GameplayParityCloak7Checks {
    fun run(main: MainGame, container: GameContainer, graphics: xyz.znix.xftl.rendering.Graphics): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val aim = playerAim(main, container, graphics)
            val ai = enemyAim(main, container)
            return "cloak7 ok: $ai, $aim"
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** The screen point over [room], found through the same conversion InGameState's hover pass uses. */
    private fun screenPointOver(state: InGameState, enemy: Ship, room: Room): Point {
        for (y in 2 until 720 step 3) for (x in 2 until 1280 step 3) {
            val p = Point(x, y)
            state.convertScreenToEnemyShip(p)
            enemy.screenPosToShipPos(p)
            if (enemy.shipToRoomPos(p)?.room === room) return Point(x, y)
        }
        error("No screen point over enemy room ${room.id}")
    }

    private fun frame(main: MainGame, container: GameContainer, graphics: xyz.znix.xftl.rendering.Graphics) {
        main.render(container, graphics)
        main.update(container, 0f)
    }

    private fun powerCloak(cloak: Cloaking) {
        var guard = 8
        while (cloak.powerSupplied == 0 && guard-- > 0) cloak.increasePower()
        check(cloak.powerSupplied > 0) { "Fixture cloak has no power" }
    }

    private fun playerAim(main: MainGame, container: GameContainer, graphics: xyz.znix.xftl.rendering.Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("ROCK_UNLOCK1"), Difficulty.NORMAL, 4, 71)
        val enemy = state.enemy ?: error("No enemy spawned")
        val cloak = enemy.cloaking ?: error("Fixture enemy has no cloak")
        val player = state.player
        val weapons = player.weapons!!
        weapons.energyLevels = 8
        player.purchasedReactorPower = 25
        val laser = (state.blueprintManager["LASER_BURST_3"] as AbstractWeaponBlueprint).buildInstance(player)
        player.hardpoints[0].weapon = laser
        player.cargoUpdated()
        check(weapons.setWeaponPower(laser, true)) { "Fixture weapon did not power" }
        if (!state.isPaused) state.toggleQuickPause()
        check(state.isPaused) { "Fixture game is not paused" }

        frame(main, container, graphics)
        val room = enemy.rooms.first { it.system != null }
        val at = screenPointOver(state, enemy, room)
        val validCursor = state.getCursor("img/mouse/mouse_crosshairs.png,img/mouse/mouse_crosshairs2_1.png")
        val redCursor = state.getCursor("img/mouse/mouse_crosshairs_valid.png,img/mouse/mouse_crosshairs2_1.png")

        state.shipUI.weaponHotkeyPressed(0, false)
        check(state.clickEvent != null) { "Weapon did not arm" }
        BrowserEngine.mouse(at.x, at.y, 0, 0)
        frame(main, container, graphics)
        check(state.isEnemyInteriorVisible) { "Uncloaked enemy interior hidden" }
        check(state.hoveredRoom === room) { "Uncloaked enemy room not hovered: ${state.hoveredRoom?.id}" }
        check(state.shipUI.getCurrentCursor() === validCursor) { "Uncloaked room doesn't show the valid crosshair" }

        powerCloak(cloak)
        cloak.activateCloak()
        check(cloak.active) { "Fixture cloak did not start" }
        frame(main, container, graphics)
        check(!state.isEnemyInteriorVisible) { "Cloaked enemy interior still drawn" }
        check(state.hoveredRoom == null) { "Cloaked enemy room ${state.hoveredRoom?.id} hovered" }
        check(state.shipUI.getCurrentCursor() === redCursor) { "Cloaked ship doesn't show the red crosshair" }
        check(!enemy.roomsSelectableByPlayer) { "Cloaked enemy rooms selectable" }

        BrowserEngine.mouse(at.x, at.y, 1, Input.MOUSE_LEFT_BUTTON)
        frame(main, container, graphics)
        BrowserEngine.mouse(at.x, at.y, 2, Input.MOUSE_LEFT_BUTTON)
        frame(main, container, graphics)
        check(weapons.selectedTargets.getTarget(0) == null) { "A click on the cloaked ship placed a target" }
        check(state.clickEvent != null) { "A click on the cloaked ship disarmed the weapon" }

        // A player crewmember aboard (ShipManager +0x7ee) shows the interior and lets rooms be picked again.
        val info = LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state)
        val boarder = enemy.addCrewMember(info, false, true)
        boarder.ownerShip = player
        val spot = enemy.rooms.first { it !== room && it.crew.isEmpty() }
        boarder.jumpTo(enemy.findSpaceForCrew(spot, AbstractCrew.SlotType.INTRUDER))
        check(enemy.hasPlayerPresence && enemy.roomsSelectableByPlayer) { "Boarder gives no presence" }
        frame(main, container, graphics)
        check(state.isEnemyInteriorVisible) { "Cloaked enemy with a boarder hides its interior" }
        check(state.hoveredRoom === room) { "Cloaked enemy room with a boarder not hovered" }

        // The dying ship (+0x499) offers no room either, presence or not.
        cloak.timeRemaining = null
        check(enemy.roomsSelectableByPlayer) { "Uncloaked enemy rooms not selectable" }
        enemy.health = 0
        check(!enemy.roomsSelectableByPlayer) { "Dead enemy rooms selectable" }
        return "cloaked rooms hidden and unselectable, click keeps the weapon armed, boarder reveals"
    }

    private fun enemyAim(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_STEALTH")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        val enemy = state.enemy ?: error("No enemy spawned")
        val player = state.player
        val cloak = player.cloaking ?: error("Stealth ship has no cloak")
        state.debugFlags.noEnemyFire.set = false
        state.debugFlags.noDmg.set = true

        val ai = ShipAI(enemy, player)
        ai.update(0f)
        val weapon = enemy.hardpoints.mapNotNull { it.weapon }.firstOrNull { it.isPowered && it.maxTotalCharges == 1 }
            ?: error("Fixture enemy has no powered single-charge weapon")

        // Evasion: a flat +60 on top of engines and piloting.
        val plain = player.evasion
        powerCloak(cloak)
        cloak.activateCloak()
        check(cloak.active) { "Player cloak did not start" }
        check(player.evasion == plain + 60) { "Cloaked evasion ${player.evasion}, uncloaked $plain" }

        weapon.timeCharged = weapon.chargeTime
        check(weapon.totalReadyCharges >= 1) { "Fixture weapon not charged" }
        repeat(3) { ai.update(0f) }
        check(weapon.totalReadyCharges >= 1 && !weapon.isFiring) { "The AI fired at the cloaked player" }

        cloak.timeRemaining = null
        ai.update(0f)
        check(weapon.totalReadyCharges == 0 || weapon.isFiring) { "The AI did not fire once the cloak dropped" }
        return "evasion +60, AI holds charged weapons on a cloaked player"
    }
}
