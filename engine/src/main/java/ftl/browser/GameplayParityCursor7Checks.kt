package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * Playtest fixes after round 6: the FTL drive's status text needs working engines and piloting (FTLButton::OnRender
 * 0x559650 writes "ready" or "charging" only while both systems function), and an open window resets the mouse
 * (CommandGui::RenderStatic 0x535070 calls MouseControl::ResetArmed, CommandGui::MouseMove 0x535e00 gives the ship
 * (-1,-1)), so a door or weapon cursor hovered when an event pops up does not stay.
 */
internal object GameplayParityCursor7Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val ftl = ftlStatus(main, container)
            val door = doorUnderWindow(main, container)
            val weapon = weaponUnderWindow(main, container)
            return "cursor7 ok: $ftl, $door, $weapon"
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun ftlStatus(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val button = state.shipUI.jumpButton ?: error("No FTL button")
        player.ftlChargeProgress = 1f
        check(button.statusKey == "ftl_ready") { "Charged drive with a pilot shows ${button.statusKey}" }

        // The pilot leaves the cockpit: the pullout shows the red pilot icon and no READY.
        val cockpit = player.piloting!!.room!!
        val other = player.rooms.first { it !== cockpit && it.system == null }
        for (crew in cockpit.crew.toList()) crew.jumpTo(player.findSpaceForCrew(other, crew.mode))
        for (room in player.rooms) room.updateCrewInRoom()
        check(!player.piloting!!.hasPilot && !player.canChargeFTL) { "Cockpit still manned" }
        check(button.statusKey == null) { "Charged drive without a pilot shows ${button.statusKey}" }
        player.ftlChargeProgress = .5f
        check(button.statusKey == null) { "Charging drive without a pilot shows ${button.statusKey}" }
        return "no status text without a pilot"
    }

    private fun openEvent(state: InGameState) {
        val event = state.eventCreator.create(state.eventManager["STALEMATE_SURRENDER"], 0) ?: error("No surrender event")
        state.shipUI.showEventDialogue(event)
        check(state.shipUI.isWindowOpen) { "Event window did not open" }
        // The mouse has not moved; the next frame's UI update runs with the window open.
        state.shipUI.updateUI(1, 1, ConstPoint.ZERO)
    }

    private fun doorUnderWindow(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val normal = state.getCursor("img/mouse/pointerInvalid.png")
        val door = state.player.doors.first { it.left != null && it.right != null }
        door.updateMouseHover(door.pixelCentre.x, door.pixelCentre.y)
        check(door.isHovered) { "Fixture door is not hovered" }
        check(state.shipUI.getCurrentCursor() !== normal) { "Hovered door shows the plain pointer" }

        openEvent(state)
        check(!door.isHovered) { "Door kept its hover under the event window" }
        check(state.shipUI.getCurrentCursor() === normal) { "Door cursor stayed under the event window" }
        return "door cursor reset"
    }

    private fun weaponUnderWindow(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val weapons = state.player.weapons!!
        weapons.energyLevels = 8
        state.player.purchasedReactorPower = 25
        val laser = (state.blueprintManager["LASER_BURST_3"] as AbstractWeaponBlueprint).buildInstance(state.player)
        state.player.hardpoints[0].weapon = laser
        state.player.cargoUpdated()
        check(weapons.setWeaponPower(laser, true)) { "Fixture weapon did not power" }
        val normal = state.getCursor("img/mouse/pointerInvalid.png")
        state.shipUI.weaponHotkeyPressed(0, false)
        check(state.clickEvent != null) { "Weapon did not arm" }
        check(state.shipUI.getCurrentCursor() !== normal) { "Armed weapon shows the plain pointer" }

        openEvent(state)
        check(state.shipUI.getCurrentCursor() === normal) { "Crosshair stayed under the event window" }
        return "crosshair reset"
    }
}
