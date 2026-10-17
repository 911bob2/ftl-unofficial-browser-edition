package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipWindow
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint

/** Reads live engine state. Owned by the isolated session, so combat reloads
 * replace the observed ship without resetting the instruction controller. */
class BrowserTutorialAdapter(definition: BrowserTutorialDefinition) {
    val controller = BrowserTutorialController(definition)
    val entryEffects = BrowserTutorialEntryEffects(this)
    val events = BrowserTutorialEvents(this)
    val visuals = BrowserTutorialVisuals(controller)

    fun observation(game: InGameState): BrowserTutorialController.Observation {
        val ship = game.player
        val engines = ship.engines
        val weapons = ship.hardpoints.mapNotNull { it.weapon }
        val fireRoom = ship.rooms.singleOrNull { it.id == 9 }
        return BrowserTutorialController.Observation(
            enginePower = engines?.powerSelected ?: 0,
            selectedCrew = game.shipUI.tutorialSelectedCrewCount,
            engineCrew = engines?.room?.crew?.size ?: 0,
            engineHealth = engines?.let { it.energyLevels - it.damagedEnergyLevels } ?: 0,
            engineCapacity = engines?.energyLevels ?: 0,
            airlockOpen = fireRoom?.doors?.any { it.isAirlock && it.open } == true,
            roomFires = fireRoom?.fires?.count { it != null } ?: 0,
            pilotCrew = ship.piloting?.room?.crew?.size ?: 0,
            mapOpen = game.shipUI.tutorialMapOpen,
            playerPaused = game.isPlayerPaused,
            nonMissilePowered = weapons.any { it.type !is MissileBlueprint && it.isPowered },
            nonMissileFired = weapons.any { it.type !is MissileBlueprint && (it.browserEncounterVolleys ?: 0) > 0 },
            missileFired = weapons.any { it.type is MissileBlueprint && (it.browserEncounterVolleys ?: 0) > 0 },
            // A crew-defeated ship can remain visible for boarder recovery;
            // it is no longer the player's active combat target.
            enemyPresent = game.getEnemyOf(ship) != null,
            shipInfoOpen = game.shipUI.tutorialShipWindow != null,
            beamEquipped = weapons.any { it.type is BeamBlueprint },
            upgradesOpen = game.shipUI.tutorialShipWindow?.tutorialTab == ShipWindow.Tab.UPGRADES,
            pendingWeaponUpgrades = game.shipUI.tutorialShipWindow?.pendingWeaponUpgrades ?: 0,
            beamPowered = weapons.any { it.type is BeamBlueprint && it.isPowered }
        )
    }

    fun update(game: InGameState): BrowserTutorialController.Transition? {
        events.update(game)
        if (controller.completed || game.isDebugConsoleVisible || game.shipUI.tutorialOverlayOpen) return null
        applyEntry(game)
        if (game.shipUI.tutorialDialogueOpen) return null
        if (controller.step.id == "INVENTORY_1") game.shipUI.tutorialShipWindow?.selectTutorialEquipment()
        val wasEnabled = controller.canContinue
        val transition = controller.observe(observation(game))
        if (!wasEnabled && controller.canContinue)
            BrowserDebug.event("tutorial", "continue.enabled", "step", controller.step.id)
        return record(game, transition)
    }

    fun continueStep(game: InGameState): BrowserTutorialController.Transition? {
        if (controller.completed || game.isDebugConsoleVisible || game.shipUI.tutorialOverlayOpen || game.shipUI.tutorialDialogueOpen) return null
        applyEntry(game)
        return record(game, controller.continueStep())
    }

    fun acceptShipWindow(game: InGameState): Boolean {
        val state = observation(game)
        return controller.equipmentAcceptAllowed(state) && controller.upgradeAcceptAllowed(state)
    }

    private fun applyEntry(game: InGameState) {
        if (!controller.completed && entryEffects.supports(controller.step.id)) entryEffects.apply(game, controller.step.id)
        events.enter(game)
    }

    private fun record(game: InGameState, transition: BrowserTutorialController.Transition?): BrowserTutorialController.Transition? {
        if (transition != null) applyEntry(game)
        if (transition != null) BrowserDebug.record("tutorial", "step.changed",
            "{\"previous\":${BrowserDebug.quote(transition.previous)},\"next\":${BrowserDebug.quote(transition.next)},\"sequence\":${transition.sequence}}")
        return transition
    }
}
