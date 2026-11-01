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
        // Hyperspace StarMap::UpdateBoss (Seeds.cpp, CustomEvents.cpp): a seeded run draws the whole update, the
        // fleet events of the beacons it takes included, from the boss fleet seed's stream (one global draw
        // reseeds the global stream afterwards), and <preventBossFleet>/<preventFleet> beacons are held out of
        // the candidates (BossFleetPrevention marks them as taken while the original runs).
        val hs = xyz.znix.xftl.hs.HS.active && game.eventManager.hs != null
        val seeded = hs && xyz.znix.xftl.hs.sector.HsSeeds.enabled
        var savedSeed = 0
        if (seeded) {
            savedSeed = NativeRandom.next31()
            NativeRandom.seed(xyz.znix.xftl.hs.sector.HsSeeds.bossFleetSeed)
        }
        // BossFleetPrevention::Set; a <runFromFleet> relocation during the update runs Clear and Set again.
        xyz.znix.xftl.hs.sector.HsGeneration.bossBlockedNow = if (hs) xyz.znix.xftl.hs.sector.HsGeneration.bossFleetBlocked(game, sector) else null
        // The flagship's step is part of UpdateBoss too: the fleet event of the beacon it lands on is drawn from
        // the same stream, ahead of the selection (seed 303 sector 8: the pick follows two events' draws).
        for (boss in sector.bosses) boss.advanceFleet()
        val stoodOn: Set<Beacon> = if (hs) sector.bosses.filterIsInstance<FlagshipBoss>().flatMap { it.browserStoodOn() }.toSet() else emptySet()
        // Last turn's warnings become fleet encounters; a new selection warns for next turn.
        val candidates = ArrayList<Beacon>()
        for (beacon in sector.beacons) {
            if (beacon.browserFleetThreatened) {
                capture(game, beacon)
                beacon.browserFleetThreatened = false
            } else if (!beacon.isBase && !beacon.isOvertaken && beacon !in stoodOn) {
                candidates.add(beacon)
            }
        }
        val blocked = xyz.znix.xftl.hs.sector.HsGeneration.bossBlockedNow ?: emptySet<Beacon>()
        xyz.znix.xftl.hs.sector.HsGeneration.bossBlockedNow = null
        candidates.removeAll(blocked)
        if (candidates.isNotEmpty()) {
            val selections = sector.bosses.filterIsInstance<FlagshipBoss>()
                .maxOfOrNull { it.browserFleetSelections } ?: 1
            // Original FTL draws twice from the same pool, so both draws can select one beacon.
            candidates[NativeRandom.nextInt(candidates.size)].browserFleetThreatened = true
            if (selections > 1 && candidates.size > 1)
                candidates[NativeRandom.nextInt(candidates.size)].browserFleetThreatened = true
        }
        if (seeded) {
            xyz.znix.xftl.hs.sector.HsSeeds.bossFleetSeed = NativeRandom.next31()
            NativeRandom.seed(savedSeed)
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

    /** The flagship has spent its turns at the base and the player is at another beacon. */
    @JvmStatic fun baseDestroyed(current: Beacon): Boolean =
        current.sector.bosses.filterIsInstance<FlagshipBoss>().any {
            it.beacon.isBase && it.browserArrivedAtBase >= 4 && current != it.beacon
        }

    @JvmStatic fun checkDefeat(game: InGameState, destination: Beacon): Boolean {
        if (game.browserRunEnded) return true
        val defeated = baseDestroyed(destination)
        if (defeated) game.shipUI.showGameOverScreen(GameOverWindow.Outcome.LOOSE_BASE_DESTROYED)
        return defeated
    }

    @JvmStatic fun waitAtBeacon(game: InGameState) {
        if (!game.browserCanWait()) return
        game.advanceFleet()
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

