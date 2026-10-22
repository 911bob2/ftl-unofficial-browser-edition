package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.drones.BoardingDrone
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/** Crew earning fixtures use normal death, fighting, sabotage and XML paths. */
object BrowserCrewAchievementDiagnostics {
    fun run(main: MainGame, container: GameContainer) {
        val profile = main.profile
        fun earned(id: String) = profile.getAchievement(main.gameContent.achievements[id]) != null
        fun reset(ship: String = "PLAYER_SHIP_MANTIS"): InGameState {
            profile.load(SaveProfile.createBlank().save())
            main.startDiagnosticGame(ship, Difficulty.NORMAL)
            repeat(90) {
                main.update(container, .05f)
                if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                    BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                    BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                }
            }
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun spawn(state: InGameState): Ship {
            var seed = 71
            do {
                state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
                check(seed < 180) { "BrowserCrewAchievementDiagnostics.kt:35" }
            // A ship that can hack is left out: with its power spare at the start (built power, ShipSystem ctor
            // 0x642370) its AI hacks the drone system, which holds the pod (the fixture needs it to land).
            } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated || state.enemy!!.rooms.none { it.cellCount >= 4 } ||
                state.enemy!!.hacking != null)
            return state.enemy!!
        }
        fun reload(state: InGameState): InGameState {
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun newCrew(state: InGameState, ship: Ship = state.enemy!!): LivingCrew =
            ship.addCrewMember(LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), false)
        fun kill(state: InGameState, crew: LivingCrew = newCrew(state)) {
            crew.dealDamage(SuffocationDamage(1000f))
            crew.update(.05f)
            check(crew.health == 0f && crew.currentAction == AbstractCrew.Action.DYING) { "BrowserCrewAchievementDiagnostics.kt:48" }
            state.browserAchievements.updateEnemy()
        }
        fun kills(state: InGameState, count: Int) { repeat(count) { kill(state) } }
        fun drone(state: InGameState): AbstractIndoorsDrone.Pawn {
            val drones = state.player.drones!!
            drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
            state.player.purchasedReactorPower = 25
            drones.energyLevels = 8
            state.player.updateAvailablePower()
            state.player.dronesCount = 100
            drones.drones[0] = Drones.DroneInfo(state.blueprintManager["BOARDER"] as DroneBlueprint)
            check(drones.setDronePower(0, true)) { "BrowserCrewAchievementDiagnostics.kt:60" }
            val instance = drones.drones[0]!!.instance as BoardingDrone
            // The fight fixtures leave the enemy with only dying crew; CompleteShip::DeadCrew (0x40a2f0) then ends
            // the fight and the pod is recalled, so give the ship a living defender before launching.
            if (state.enemy!!.crew.none { it.health > 0f && it.mode == AbstractCrew.SlotType.CREW }) newCrew(state)
            state.debugFlags.noCrewDamage.set = true
            repeat(600) { if (instance.pawn == null) state.updateGameState(.05f) }
            val pawn = checkNotNull(instance.pawn) { "Boarding drone did not arrive" }
            repeat(90) { pawn.update(.05f) }
            state.debugFlags.noCrewDamage.set = false
            return pawn
        }
        fun fight(state: InGameState, attacker: AbstractCrew, victim: LivingCrew, clearFire: Boolean = true) {
            val room = attacker.room.ship.rooms.first { it.cellCount >= 4 }
            room.ship.crew.filter { it !== attacker && it !== victim }.toList().forEach { it.removeFromShip() }
            if (clearFire) room.fires.indices.forEach { room.fires[it] = null }
            check(attacker.setTargetRoom(room, ConstPoint.ZERO)) { "BrowserCrewAchievementDiagnostics.kt:73" }; check(victim.setTargetRoom(room, ConstPoint.ZERO)) { "BrowserCrewAchievementDiagnostics.kt:73" }
            attacker.jumpTo(room, ConstPoint.ZERO)
            victim.jumpTo(room, ConstPoint.ZERO)
            attacker.nextTargetPos = null; victim.nextTargetPos = null
            room.updateCrewInRoom(); room.ship.updateCrewReservedSlots()
            victim.health = .01f
            // A drone body shoots, and a crew laser deals its damage when it lands (0x4881c0), so the ship's crew
            // shots fly too.
            repeat(240) {
                if (victim.health > 0f) {
                    attacker.update(.05f)
                    for (shot in room.ship.crewShots.toList()) {
                        if (!shot.update(.05f)) room.ship.crewShots.remove(shot)
                    }
                }
            }
            check(victim.health == 0f) { "Crew fixture never delivered its combat killing blow: action=${attacker.currentAction}, path=${attacker.pathingTarget}, health=${attacker.health}, target=${victim.health}, powered=${(attacker as? AbstractIndoorsDrone.Pawn)?.drone?.isPowered}" }
            victim.update(.05f)
        }
        fun burningFight(state: InGameState, home: Boolean = false): Pair<LivingCrew, LivingCrew> {
            val victim = newCrew(state)
            val attacker = state.player.crew.filterIsInstance<LivingCrew>().first()
            val room = (if (home) state.player else state.enemy!!).rooms.first { it.cellCount >= 4 }
            val moving = if (home) victim else attacker
            moving.teleportAnimatedTo(room)
            state.debugFlags.noCrewDamage.set = true
            repeat(40) { moving.update(.05f) }
            room.ship.crew.filter { it !== attacker && it !== victim }.toList().forEach { it.removeFromShip() }
            check(attacker.setTargetRoom(room, ConstPoint.ZERO)) { "BrowserCrewAchievementDiagnostics.kt:94" }; check(victim.setTargetRoom(room, ConstPoint.ZERO)) { "BrowserCrewAchievementDiagnostics.kt:94" }
            attacker.jumpTo(room, ConstPoint.ZERO); victim.jumpTo(room, ConstPoint.ZERO)
            attacker.nextTargetPos = null; victim.nextTargetPos = null
            room.updateCrewInRoom(); room.ship.updateCrewReservedSlots()
            repeat(10) { attacker.update(.05f); victim.update(.05f) }
            check(victim.currentAction.isAttackAction && victim.enemyToAttack === attacker) { "BrowserCrewAchievementDiagnostics.kt:99" }
            state.debugFlags.noCrewDamage.set = false
            room.spawnFireAt(0)
            return attacker to victim
        }

        println("TEST achievement Mantis arrival history")
        var state = reset(); spawn(state); kills(state, 4)
        check(!earned("ACH_MANTIS_SLAUGHTER") && state.browserAchievements.beaconCrewKills == 4) { "BrowserCrewAchievementDiagnostics.kt:107" }
        state = reload(state)
        check(state.browserAchievements.beaconCrewKills == 4) { "Death animation counted again on reload" }
        kill(state)
        check(earned("ACH_MANTIS_SLAUGHTER")) { "Five deaths with unchanged hull/crew did not earn" }
        state = reset(); spawn(state); state.player.health--; kills(state, 5)
        check(!earned("ACH_MANTIS_SLAUGHTER")) { "BrowserCrewAchievementDiagnostics.kt:113" }
        state = reload(state); state.player.health++; state.browserAchievements.update()
        check(earned("ACH_MANTIS_SLAUGHTER")) { "Native repaired-hull equality was not honored" }
        state = reset(); spawn(state)
        val lost = state.player.crew.filterIsInstance<LivingCrew>().first()
        kill(state, lost); kills(state, 5)
        check(!earned("ACH_MANTIS_SLAUGHTER")) { "Lost player crew did not disqualify" }
        newCrew(state, state.player); state.browserAchievements.update()
        check(earned("ACH_MANTIS_SLAUGHTER")) { "Native replacement-crew count equality was not honored" }
        state = reset(); spawn(state); newCrew(state, state.player); kills(state, 5)
        check(!earned("ACH_MANTIS_SLAUGHTER")) { "Extra crew count did not disqualify" }
        state = reset(); spawn(state); kills(state, 4); spawn(state); kill(state)
        check(earned("ACH_MANTIS_SLAUGHTER")) { "Replacing an enemy reset per-location history" }
        state = reset(); spawn(state); kills(state, 4)
        state.currentBeacon = state.currentBeacon.neighbours.first()
        spawn(state); kill(state)
        check(state.browserAchievements.beaconCrewKills == 1 && !earned("ACH_MANTIS_SLAUGHTER")) { "BrowserCrewAchievementDiagnostics.kt:129" }
        state = reset(); spawn(state); kills(state, 4)
        val old = state.saveGameState()
        old.rootElement.getChild("browserAchievements").removeAttribute("beaconCrewKills")
        main.loadSavedGame(old); state = main.currentState as InGameState
        kill(state); check(!earned("ACH_MANTIS_SLAUGHTER")) { "Unknown legacy death history earned" }
        state = reset("PLAYER_SHIP_HARD"); spawn(state); kills(state, 5)
        check(!earned("ACH_MANTIS_SLAUGHTER")) { "BrowserCrewAchievementDiagnostics.kt:136" }
        state = reset(); spawn(state)
        val pending = newCrew(state); pending.dealDamage(SuffocationDamage(1000f))
        state = reload(state)
        check(state.browserAchievements.beaconCrewKills == 1) { "Unstarted death was lost during load" }
        state = reload(state); check(state.browserAchievements.beaconCrewKills == 1) { "BrowserCrewAchievementDiagnostics.kt:141" }
        state = reset("PLAYER_SHIP_MANTIS_3"); spawn(state)
        val clone = state.player.crew.filterIsInstance<LivingCrew>().first()
        kill(state, clone)
        repeat(90) { if (state.player.crew.contains(clone)) clone.update(.05f) }
        check(state.player.clonebay!!.queue.contains(clone)) { "BrowserCrewAchievementDiagnostics.kt:146" }
        state = reload(state); kills(state, 5)
        check(earned("ACH_MANTIS_SLAUGHTER")) { "Clone-ready owned crew were excluded from the native count" }
        state = reset("PLAYER_SHIP_MANTIS_2"); spawn(state)
        val dyingPawn = drone(state)
        kills(state, 4)
        dyingPawn.dealDamage(SuffocationDamage(1000f)); dyingPawn.update(.05f)
        state.browserAchievements.update()
        check(state.browserAchievements.beaconCrewKills == 4 && !earned("ACH_MANTIS_SLAUGHTER")) { "Drone death counted as crew" }

        println("TEST achievement boarding drone goal and identity")
        state = reset("PLAYER_SHIP_MANTIS_2"); spawn(state)
        var pawn = drone(state)
        // One pawn, normal combat kills; no system sabotage contributes here.
        pawn.browserTargetsDestroyed = 0
        repeat(4) { fight(state, pawn, newCrew(state)) }
        check(!earned("ACH_BOARDING_DRONE") && pawn.browserTargetsDestroyed == 4) { "BrowserCrewAchievementDiagnostics.kt:162" }
        check(state.player.drones!!.setDronePower(0, false)) { "BrowserCrewAchievementDiagnostics.kt:163" }
        check(state.player.drones!!.setDronePower(0, true)) { "BrowserCrewAchievementDiagnostics.kt:164" }
        check(pawn.browserTargetsDestroyed == 4) { "BrowserCrewAchievementDiagnostics.kt:165" }
        state = reload(state); pawn = (state.player.drones!!.drones[0]!!.instance as BoardingDrone).pawn!!
        fight(state, pawn, newCrew(state))
        check(earned("ACH_BOARDING_DRONE") && pawn.browserTargetsDestroyed == 5) { "BrowserCrewAchievementDiagnostics.kt:168" }
        state = reset("PLAYER_SHIP_MANTIS_2"); spawn(state); pawn = drone(state)
        pawn.browserTargetsDestroyed = 0
        val system = state.enemy!!.systems.first { it.room != null && it.energyLevels > 0 }
        check(pawn.setTargetRoom(system.room!!, ConstPoint.ZERO)) { "BrowserCrewAchievementDiagnostics.kt:172" }
        pawn.jumpTo(system.room!!, ConstPoint.ZERO)
        pawn.nextTargetPos = null
        state.enemy!!.crew.filter { it !== pawn }.toList().forEach { it.removeFromShip() }
        // Rooms recount their occupants every frame; without this the removed defender still stands in the
        // room's list and the boarder keeps fighting it instead of sabotaging.
        state.enemy!!.rooms.forEach { it.update(0f) }
        system.damagedEnergyLevels = system.energyLevels - 1
        repeat(350) { if (!system.broken) pawn.update(.05f) }
        check(system.broken && pawn.browserTargetsDestroyed == 1) { "Completed sabotage did not count as a boarding goal" }
        repeat(4) { fight(state, pawn, newCrew(state)) }
        check(earned("ACH_BOARDING_DRONE")) { "BrowserCrewAchievementDiagnostics.kt:180" }
        state = reset("PLAYER_SHIP_MANTIS_2"); spawn(state); pawn = drone(state)
        pawn.browserTargetsDestroyed = 0
        repeat(2) { fight(state, pawn, newCrew(state)) }
        state.player.drones!!.drones[0]!!.instance!!.destroy()
        pawn = drone(state); pawn.browserTargetsDestroyed = 0
        repeat(3) { fight(state, pawn, newCrew(state)) }
        check(!earned("ACH_BOARDING_DRONE")) { "Different drone pawns shared a goal" }
        pawn.browserTargetsDestroyed = null
        state = reload(state); pawn = (state.player.drones!!.drones[0]!!.instance as BoardingDrone).pawn!!
        repeat(5) { fight(state, pawn, newCrew(state)) }
        check(!earned("ACH_BOARDING_DRONE") && pawn.browserTargetsDestroyed == null) { "Unknown legacy pawn history earned" }
        state = reset("PLAYER_SHIP_MANTIS_2"); spawn(state)
        val defense = xyz.znix.xftl.drones.AntiPersonnelDrone(state.blueprintManager["BATTLE"] as DroneBlueprint)
        defense.init(state.player); defense.isPowered = true
        pawn = defense.pawn!!
        repeat(90) { pawn.update(.05f) }
        repeat(5) {
            val victim = (state.blueprintManager["human"] as CrewBlueprint).spawn(state.player.rooms.first(), AbstractCrew.SlotType.INTRUDER) as LivingCrew
            victim.ownerShip = state.enemy; state.player.crew.add(victim)
            fight(state, pawn, victim)
        }
        check(pawn.browserTargetsDestroyed == 5 && !earned("ACH_BOARDING_DRONE")) { "Home defense pawn earned boarding achievement" }

        println("TEST achievement Rock fire combat and health paths")
        state = reset("PLAYER_SHIP_ROCK"); spawn(state)
        var pair = burningFight(state)
        pair.second.health = .001f
        pair.second.update(.05f)
        check(earned("ACH_ROCK_FIRE")) { "Fire killing blow while fighting did not earn" }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state); pair = burningFight(state)
        state = reload(state)
        val restoredVictim = state.enemy!!.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.enemy }
        restoredVictim.health = .001f; restoredVictim.update(.05f)
        check(earned("ACH_ROCK_FIRE")) { "Saved burning fight did not earn" }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state); pair = burningFight(state)
        fight(state, pair.first, pair.second, false)
        check(earned("ACH_ROCK_FIRE")) { "Combat killing blow against burning crew did not earn" }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state)
        val alone = newCrew(state)
        alone.room.spawnFireAt(0); kill(state, alone)
        check(!earned("ACH_ROCK_FIRE")) { "Unopposed fire death earned" }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state); pair = burningFight(state)
        pair.second.room.fires.indices.forEach { pair.second.room.fires[it] = null }
        fight(state, pair.first, pair.second)
        check(!earned("ACH_ROCK_FIRE")) { "Unlit combat earned" }
        state = reset("PLAYER_SHIP_HARD"); spawn(state); pair = burningFight(state)
        pair.second.health = .001f; pair.second.update(.05f)
        check(!earned("ACH_ROCK_FIRE")) { "Wrong ship family earned Rock fire" }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state); pair = burningFight(state, true)
        pair.second.health = .001f; pair.second.update(.05f)
        check(!earned("ACH_ROCK_FIRE")) { "Home-ship burning kill earned Rock fire" }
    }
}
