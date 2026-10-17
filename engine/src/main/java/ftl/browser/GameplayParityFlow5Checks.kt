package ftl.browser

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ResourceSet
import xyz.znix.xftl.sys.GameContainer

/**
 * Round-5b flow residues: the starting anaerobic crew empty their rooms' oxygen (ShipManager::AddCrewMember 0x4a24a0
 * with the starting flag, OxygenSystem::EmptyOxygen 0x62cbe0), event rewards floor the counts at zero (0x615930,
 * ModifyScrapCount 0x49ba70, ModifyMissileCount 0x49fa70, ModifyDroneCount 0x49f2c0), and augment rewards: a
 * duplicate becomes the AUGMENT_FULL message with 25 scrap, a fourth goes to the overflow augment slot.
 */
internal object GameplayParityFlow5Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val oxygen = laniusStartOxygen(main, container)
            val floor = rewardFloor(main, container)
            val augments = augmentRewards(main, container)
            val drive = startDrive(main, container)
            return "flow5 ok: $oxygen, $floor, $augments, $drive"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * Lanius A: the rooms the two Lanius start in are empty of air and the other 14 full, so the HUD reads 88% at the
     * opening event, as the original (live: rooms 7 and 9 at 0, the rest at 100). A Lanius recruited later keeps the
     * room's air.
     */
    private fun laniusStartOxygen(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC")
        val ship = state.player
        val laniusRooms = ship.crew.filter { it.codename == "anaerobic" }.map { it.room }.toSet()
        check(laniusRooms.isNotEmpty()) { "Lanius A has no Lanius crew" }
        val empty = ship.rooms.filter { it.oxygen < 0.02f }.toSet()
        check(empty == laniusRooms) { "empty rooms ${empty.map { it.id }}, Lanius rooms ${laniusRooms.map { it.id }}" }
        check(ship.rooms.filter { it !in laniusRooms }.all { it.oxygen > 0.98f }) { "other rooms lost air at the start" }

        val kestrel = BrowserGameplayParityDiagnostics.fresh(main, container)
        val recruit = kestrel.player.addCrewMember(
            LivingCrewInfo.generateRandom(kestrel.blueprintManager["anaerobic"] as CrewBlueprint, kestrel), false
        )
        check(recruit.room.oxygen > 0.98f) { "a recruited Lanius emptied its room (${recruit.room.oxygen})" }
        return "lanius air ${ship.rooms.size - empty.size}/${ship.rooms.size}"
    }

    /** An event loss larger than the stock leaves zero (DISTRESS_ENGI_REBEL_LIST2 at 10 scrap: 0, then +25 is 25). */
    private fun rewardFloor(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        ship.scrap = 10
        ship.fuelCount = 1
        ship.missilesCount = 1
        ship.dronesCount = 1
        state.givePlayerResources(ResourceSet().apply { scrap = -40; fuel = -2; missiles = -2; droneParts = -2 })
        check(ship.scrap == 0 && ship.fuelCount == 0 && ship.missilesCount == 0 && ship.dronesCount == 0) {
            "loss not floored: scrap ${ship.scrap} fuel ${ship.fuelCount} missiles ${ship.missilesCount} drones ${ship.dronesCount}"
        }
        state.givePlayerResources(ResourceSet().apply { scrap = 25 })
        check(ship.scrap == 25) { "scrap after the floor ${ship.scrap}" }
        return "reward floor"
    }

    /** NANO_MEDBAY twice: one installed, no overflow; three more fill the slots and the fourth waits in overflow. */
    private fun augmentRewards(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val nano = state.blueprintManager["NANO_MEDBAY"] as AugmentBlueprint
        check(ship.addBlueprint(nano, true) && ship.addBlueprint(nano, true)) { "augment reward refused" }
        check(ship.augments == listOf(nano) && ship.cargoBlueprints[5] == null) { "duplicate kept: ${ship.augments.map { it.name }}" }

        for (name in listOf("SCRAP_COLLECTOR", "O2_MASKS", "AUTO_COOLDOWN"))
            ship.addBlueprint(state.blueprintManager[name] as AugmentBlueprint, true)
        check(ship.augments.size == 3 && ship.cargoBlueprints[5]?.name == "AUTO_COOLDOWN") {
            "overflow ${ship.augments.map { it.name }} / ${ship.cargoBlueprints[5]?.name}"
        }
        return "augment rewards"
    }

    /**
     * Round 5c: a new run's drive is empty under the opening event (the game is paused, nothing charges) and fills
     * on the first frames after it closes, since nothing is hostile (WorldManager::OnLoop 0x61b930 -> 0x49b5b0). Live:
     * Kestrel A and Lanius A at 0.07/85 under the event, 85/85 0.1 s after it.
     */
    private fun startDrive(main: MainGame, container: GameContainer): String {
        for (ship in listOf("PLAYER_SHIP_HARD", "PLAYER_SHIP_ANAEROBIC")) {
            main.startDiagnosticGame(ship, xyz.znix.xftl.game.Difficulty.NORMAL)
            repeat(10) { main.update(container, .05f) }
            val state = main.currentState as InGameState
            check(state.shipUI.diagnosticDialogue != null) { "$ship opening event not open" }
            check(state.player.ftlChargeProgress < .01f) { "$ship drive ${state.player.ftlChargeProgress} under the opening event" }
            state.shipUI.diagnosticDialogue?.selectOption(0)
            repeat(3) { main.update(container, .05f) }
            check(state.player.isFtlCharged) { "$ship drive ${state.player.ftlChargeProgress} after the opening event" }
        }
        return "start drive"
    }
}
