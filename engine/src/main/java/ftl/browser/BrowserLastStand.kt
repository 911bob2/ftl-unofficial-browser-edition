package ftl.browser

import xyz.znix.xftl.game.*
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector

/** Final-sector rules checked against the owner's FTL 1.6.14 StarMap functions. */
object BrowserLastStand {
    /** StarMap::OvertakeLocation (0x5f4ca0) on a non-base beacon: a fleet event is created now. */
    @JvmStatic fun capture(game: InGameState, beacon: Beacon) {
        if (beacon.isBase) return
        beacon.ship = null
        beacon.sector.overtake(game, beacon)
    }

    /**
     * StarMap 0x5f4ed0 after the flagship has stepped (the bosses): the beacons marked last turn are overtaken in
     * list order; then the candidates (not the exit, not overtaken) are counted and one is marked with
     * `random32() % n`, and a second with another draw only when `n > 1` and the flagship has made a second
     * step or is running away. Both draws use the global stream.
     */
    @JvmStatic fun advance(game: InGameState) {
        val sector = game.currentBeacon.sector
        for (boss in sector.bosses) boss.advanceFleet()
        // Last turn's warnings become fleet encounters; a new selection warns for next turn.
        val candidates = ArrayList<Beacon>()
        for (beacon in sector.beacons) {
            if (beacon.browserFleetThreatened) {
                capture(game, beacon)
                beacon.browserFleetThreatened = false
            } else if (!beacon.isBase && !beacon.isOvertaken) {
                candidates.add(beacon)
            }
        }
        if (candidates.isNotEmpty()) {
            val selections = sector.bosses.filterIsInstance<FlagshipBoss>()
                .maxOfOrNull { it.browserFleetSelections } ?: 1
            // Original FTL draws twice from the same pool, so both draws can select one beacon.
            candidates[NativeRandom.nextInt(candidates.size)].browserFleetThreatened = true
            if (selections > 1 && candidates.size > 1)
                candidates[NativeRandom.nextInt(candidates.size)].browserFleetThreatened = true
        }
        sector.fleetAdvanceModifier += when {
            sector.fleetAdvanceModifier < 0 -> 1
            sector.fleetAdvanceModifier > 0 -> -1
            else -> 0
        }
        // UpdateDangerZone's flagship branch (0x5f5630) then force-clears the current location's event
        // (ClearEvent 0x6119f0, forced) unless the fleet has taken it; the hazard already running stays.
        val here = game.currentBeacon
        if (!here.isOvertaken) here.clearEvent(true, false)
    }

    @JvmStatic fun checkDefeat(game: InGameState, destination: Beacon): Boolean {
        if (game.browserRunEnded) return true
        val defeated = destination.sector.bosses.filterIsInstance<FlagshipBoss>().any {
            it.beacon.isBase && it.browserArrivedAtBase >= 4 && destination != it.beacon
        }
        if (defeated) game.shipUI.showGameOverScreen(GameOverWindow.Outcome.LOOSE_BASE_DESTROYED)
        return defeated
    }

    @JvmStatic fun waitAtBeacon(game: InGameState) {
        if (!game.browserCanWait()) return
        game.advanceFleet()
        if (checkDefeat(game, game.currentBeacon)) return
        // Waiting is stationary: preserve charge, cargo and a friendly encounter.
        // Fleet capture still replaces that encounter, and the flagship takes precedence.
        if (!game.browserSpawnBossHere() && game.currentBeacon.isOvertaken && game.currentBeacon.hasFleetEvent) {
            game.shipUI.showEventDialogue(game.currentBeacon.createdEvent)
        } else if (!game.browserSpawnBossHere() && game.currentBeacon.isOvertaken) {
            val beacon = game.currentBeacon
            val eventName = if (beacon.isExit) {
                "FLEET_EASY_BEACON" + if (game.difficulty != Difficulty.EASY) "_DLC" else ""
            } else {
                "FLEET_EASY" + when {
                    beacon.environmentType == Beacon.EnvironmentType.NEBULA -> "_NEBULA"
                    game.content.enableAdvancedEdition -> "_DLC"
                    else -> ""
                }
            }
            game.shipUI.showEventDialogue(game.eventManager[eventName].resolve(), BrowserRandom.nextInt())
        }
        game.mainGame.writeRunSave()
    }
}
