package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.*
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.*

/** Real firing, weapon-instance changes, destruction hooks and serialized encounter history. */
object BrowserCombatAchievementDiagnostics {
    fun run(main: MainGame, container: GameContainer) {
        val profile = main.profile
        fun earned(id: String) = profile.getAchievement(main.gameContent.achievements[id]) != null
        fun reset(ship: String): InGameState {
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
        fun spawn(state: InGameState, drones: Boolean = false): Ship {
            var seed = 71
            do {
                state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
                check(seed < 180) { "No suitable combat achievement fixture" }
            } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated || (drones && state.enemy!!.drones == null))
            return state.enemy!!.also { it.health = it.maxHealth; state.browserAchievements.updateEnemy() }
        }
        fun reload(state: InGameState): InGameState {
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun equip(ship: Ship, name: String, slot: Int = 0): AbstractWeaponInstance {
            ship.hardpoints[slot].weapon?.let { ship.weapons!!.setWeaponPower(it, false) }
            val weapon = (ship.sys.blueprintManager[name] as AbstractWeaponBlueprint).buildInstance(ship)
            ship.hardpoints[slot].weapon = weapon
            ship.weapons!!.energyLevels = 8
            ship.purchasedReactorPower = 25
            ship.updateAvailablePower()
            weapon.bindToWeaponsSystem(ship.weapons!!)
            return weapon
        }
        fun launch(weapon: AbstractWeaponInstance, target: Ship) {
            if (weapon.ship.hardpoints.any { it.weapon === weapon }) {
                check(weapon.ship.weapons!!.setWeaponPower(weapon, true)) { "Fixture ${weapon.type.name} could not obtain reactor power on ${weapon.ship.type.name}" }
            } else weapon.forceSetPowered(true)
            weapon.timeCharged = weapon.chargeTime
            when (weapon) {
                is BeamBlueprint.BeamInstance -> weapon.fire(weapon.buildLongestAim(target.rooms.first()))
                is IRoomTargetingWeapon -> weapon.fire { target.rooms.first() }
                else -> error("Unsupported fixture weapon ${weapon.type.name}")
            }
        }
        fun advance(weapon: AbstractWeaponInstance, until: () -> Boolean = { !weapon.isFiring }) {
            repeat(180) { if (!until() && weapon.isFiring) weapon.update(.05f, 0f, false) }
            check(until()) { "Weapon ${weapon.type.name} did not reach the expected firing phase" }
        }
        fun fire(weapon: AbstractWeaponInstance, target: Ship) {
            val state = weapon.ship.sys
            val old = state.debugFlags.noDmg.set
            state.debugFlags.noDmg.set = true
            launch(weapon, target)
            advance(weapon)
            state.debugFlags.noDmg.set = old
        }
        fun solo(state: InGameState, slot: Int = 0) {
            state.player.hardpoints.forEachIndexed { index, hardpoint ->
                if (index != slot) hardpoint.weapon?.let { state.player.weapons!!.setWeaponPower(it, false) }
            }
        }
        fun reloadFiring(state: InGameState, slot: Int = 0): Pair<InGameState, AbstractWeaponInstance> {
            val loaded = reload(state).also { it.debugFlags.noDmg.set = true }
            return loaded to loaded.player.hardpoints[slot].weapon!!
        }
        fun preignite(state: InGameState) =
            state.player.addBlueprint(state.blueprintManager["WEAPON_PREIGNITE"] as xyz.znix.xftl.augments.AugmentBlueprint, true)
        fun cloak(state: InGameState) {
            val system = state.player.cloaking!!
            if (system.powerSelected == 0) system.increasePower()
            system.activateCloak()
            check(system.active) { "Cloak fixture did not activate" }
        }
        fun defense(state: InGameState, name: String = "DEFENSE_1") {
            val drones = state.enemy!!.drones!!
            drones.drones.indices.forEach { index -> drones.setDronePower(index, false); drones.drones[index] = null }
            drones.drones[0] = Drones.DroneInfo(state.blueprintManager[name] as DroneBlueprint)
        }
        fun offensiveDrone(state: InGameState) {
            if (state.player.drones == null) {
                val slot = state.player.systemSlots.first { it.system.type == "drones" }
                slot.room.setSystem(slot)
            }
            val drones = state.player.drones!!
            drones.energyLevels = 4
            state.player.dronesCount = 4
            drones.drones[0] = Drones.DroneInfo(state.blueprintManager["COMBAT_1"] as DroneBlueprint)
            check(drones.setDronePower(0, true)) { "Offensive drone fixture did not power on" }
        }

        var state = reset("PLAYER_SHIP_ENERGY")
        spawn(state); state.player.superShield = 1
        state = reload(state); state.enemy!!.health = 0
        check(earned("ACH_ENERGY_SHIELDS")) { "Remaining Zoltan shield did not earn at destruction" }
        val notifications = profile.pendingNotificationCount
        state.enemy!!.health = 0; state.browserAchievements.updateEnemy()
        check(profile.pendingNotificationCount == notifications) { "Repeated destruction duplicated progression" }
        state = reset("PLAYER_SHIP_ENERGY"); spawn(state); state.player.superShield = 0; state.enemy!!.health = 0
        check(!earned("ACH_ENERGY_SHIELDS")) { "BrowserCombatAchievementDiagnostics.kt:114" }
        state = reset("PLAYER_SHIP_CIRCLE"); spawn(state); state.player.superShield = 1; state.enemy!!.health = 0
        check(!earned("ACH_ENERGY_SHIELDS")) { "Wrong family earned Zoltan achievement" }

        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); cloak(state)
        state.enemy!!.health -= 1
        state = reload(state)
        check(state.player.isCloakActive) { "BrowserCombatAchievementDiagnostics.kt:121" }
        state.enemy!!.health = 0
        check(earned("ACH_STEALTH_DESTROY")) { "Reload lost a destruction entirely within cloak" }
        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); state.enemy!!.health -= 1
        state = reload(state); cloak(state); state.enemy!!.health = 0
        check(!earned("ACH_STEALTH_DESTROY")) { "Uncloaked damage was forgotten on reload" }
        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); cloak(state); state.enemy!!.health -= 1
        state.player.cloaking!!.timeRemaining = null; state.browserAchievements.updateEnemy()
        state = reload(state); state.player.cloaking!!.timeRemaining = 5f; state.enemy!!.health = 0
        check(!earned("ACH_STEALTH_DESTROY")) { "Damage across two cloaks earned a single-cloak achievement" }
        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); state.enemy!!.health -= 1
        state.enemy!!.health = state.enemy!!.maxHealth; state.browserAchievements.updateEnemy()
        cloak(state); state.enemy!!.health = 0
        check(earned("ACH_STEALTH_DESTROY")) { "Full enemy repair did not reset native cloak predicate" }

        state = reset("PLAYER_SHIP_HARD"); spawn(state)
        state.player.addBlueprint(state.blueprintManager["WEAPON_PREIGNITE"] as xyz.znix.xftl.augments.AugmentBlueprint, true)
        var weapon = equip(state.player, "LASER_BURST_2")
        fire(weapon, state.enemy!!)
        check(weapon.browserEncounterVolleys == 1) { "Burst projectiles counted as multiple volleys" }
        check(state.player.weapons!!.swapSlots(0, 1)) { "BrowserCombatAchievementDiagnostics.kt:141" }
        state = reload(state)
        check(state.player.hardpoints[1].weapon!!.browserEncounterVolleys == 1) { "Reorder/reload lost weapon-owned volley count" }
        state.enemy!!.health = 0
        check(earned("ACH_ONE_VOLLEY")) { "BrowserCombatAchievementDiagnostics.kt:145" }
        state = reset("PLAYER_SHIP_HARD"); spawn(state)
        state.player.addBlueprint(state.blueprintManager["WEAPON_PREIGNITE"] as xyz.znix.xftl.augments.AugmentBlueprint, true)
        weapon = equip(state.player, "LASER_BURST_2")
        repeat(2) { fire(weapon, state.enemy!!) }
        state = reload(state); state.enemy!!.health = 0
        check(!earned("ACH_ONE_VOLLEY")) { "Second volley was forgotten" }
        state = reset("PLAYER_SHIP_HARD"); spawn(state)
        state.player.addBlueprint(state.blueprintManager["WEAPON_PREIGNITE"] as xyz.znix.xftl.augments.AugmentBlueprint, true)
        fire(equip(state.enemy!!, "LASER_BURST_1"), state.player)
        state = reload(state); state.enemy!!.health = 0
        check(!earned("ACH_ONE_VOLLEY")) { "Enemy firing did not disqualify pre-igniter achievement" }
        state = reset("PLAYER_SHIP_HARD"); spawn(state); state.enemy!!.health = 0
        check(!earned("ACH_ONE_VOLLEY")) { "Missing pre-igniter still earned achievement" }

        state = reset("PLAYER_SHIP_CIRCLE"); spawn(state); state = reload(state); state.enemy!!.health = 0
        check(earned("ACH_ONLY_DRONES")) { "Native no-weapon destruction predicate did not earn" }
        state = reset("PLAYER_SHIP_CIRCLE"); spawn(state)
        fire(equip(state.player, "LASER_BURST_1"), state.enemy!!)
        state = reload(state); state.enemy!!.health = 0
        check(!earned("ACH_ONLY_DRONES")) { "Normal weapon use did not disqualify drones-only achievement" }
        state = reset("PLAYER_SHIP_CIRCLE"); spawn(state)
        weapon = equip(state.player, "LASER_BURST_1"); weapon.forceSetPowered(true); weapon.timeCharged = weapon.chargeTime
        (weapon as IRoomTargetingWeapon).fire { state.enemy!!.rooms.first() }
        weapon.forceSetPowered(false); weapon.update(.05f, 0f, false)
        check(weapon.browserEncounterVolleys == 0) { "Cancelled pre-launch animation counted as a volley" }
        state.enemy!!.health = 0; check(earned("ACH_ONLY_DRONES")) { "BrowserCombatAchievementDiagnostics.kt:171" }

        state = reset("PLAYER_SHIP_FED"); spawn(state)
        val artillery = state.player.systems.filterIsInstance<Artillery>().single()
        fire(artillery.browserWeapon, state.enemy!!)
        check(artillery.browserWeapon.browserEncounterVolleys == 1) { "BrowserCombatAchievementDiagnostics.kt:176" }
        // This deliberately verifies the native predicate's difference from DAT prose.
        state.player.health -= 1
        state = reload(state); state.enemy!!.health = 0
        check(earned("ACH_FED_PATIENCE")) { "Artillery-only destruction predicate differed from original executable" }
        state = reset("PLAYER_SHIP_FED"); spawn(state)
        fire(state.player.systems.filterIsInstance<Artillery>().single().browserWeapon, state.enemy!!)
        fire(equip(state.player, "LASER_BURST_1"), state.enemy!!)
        state.enemy!!.health = 0; check(!earned("ACH_FED_PATIENCE")) { "BrowserCombatAchievementDiagnostics.kt:184" }
        state = reset("PLAYER_SHIP_FED"); spawn(state)
        fire(state.player.systems.filterIsInstance<Artillery>().single().browserWeapon, state.enemy!!)
        offensiveDrone(state); state.enemy!!.health = 0
        check(!earned("ACH_FED_PATIENCE")) { "Powered combat drone did not disqualify artillery-only achievement" }

        state = reset("PLAYER_SHIP_ROCK"); spawn(state, true); defense(state)
        fire(equip(state.player, "MISSILES_2_PLAYER"), state.enemy!!)
        state = reload(state); state.enemy!!.health = 0
        check(earned("ACH_ROCK_MISSILES")) { "Missile-only defense-drone destruction failed" }
        for (name in listOf("LASER_BURST_1", "BOMB_1")) {
            state = reset("PLAYER_SHIP_ROCK"); spawn(state, true); defense(state)
            fire(equip(state.player, name), state.enemy!!)
            state = reload(state); state.enemy!!.health = 0
            check(!earned("ACH_ROCK_MISSILES")) { "$name incorrectly qualified as a missile" }
        }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state, true); defense(state, "ANTI_DRONE")
        state.enemy!!.health = 0; check(!earned("ACH_ROCK_MISSILES")) { "Anti-drone qualified as a missile-defense drone" }
        state = reset("PLAYER_SHIP_ROCK"); spawn(state, true); defense(state)
        offensiveDrone(state); state.enemy!!.health = 0; check(!earned("ACH_ROCK_MISSILES")) { "BrowserCombatAchievementDiagnostics.kt:203" }

        // Save before launch, then mid-burst: the remaining burst belongs to the same volley.
        println("TEST achievement projectile restoration")
        state = reset("PLAYER_SHIP_HARD"); spawn(state); preignite(state)
        weapon = equip(state.player, "LASER_BURST_2"); solo(state); state.debugFlags.noDmg.set = true
        launch(weapon, state.enemy!!); weapon.update(.01f, 0f, false)
        check(weapon.isFiring && weapon.browserEncounterVolleys == 0) { "Fixture did not save before projectile launch" }
        var restored = reloadFiring(state); state = restored.first; weapon = restored.second
        check(weapon.isFiring && weapon.browserEncounterVolleys == 0) { "Reload lost a pending projectile launch: ${weapon.type.name} firing=${weapon.isFiring} powered=${weapon.isPowered} volleys=${weapon.browserEncounterVolleys}" }
        advance(weapon) { weapon.browserEncounterVolleys == 1 }
        check(weapon.isFiring) { "Burst ended before a mid-volley save could be tested" }
        restored = reloadFiring(state); state = restored.first; weapon = restored.second
        check(weapon.isFiring && weapon.browserEncounterVolleys == 1) { "Mid-volley reload lost firing state or volley count" }
        advance(weapon)
        check(weapon.browserEncounterVolleys == 1) { "Remaining burst after reload counted as another volley" }
        state.enemy!!.health = 0; check(earned("ACH_ONE_VOLLEY")) { "Mid-volley reload prevented One Volley" }

        // Beam: save during pre-fire, then while the beam is on.
        println("TEST achievement beam restoration")
        state = reset("PLAYER_SHIP_HARD"); spawn(state); preignite(state)
        weapon = equip(state.player, "BEAM_1"); solo(state); state.debugFlags.noDmg.set = true
        launch(weapon, state.enemy!!)
        check(!(weapon as BeamBlueprint.BeamInstance).isBeamOn && weapon.browserEncounterVolleys == 0) { "BrowserCombatAchievementDiagnostics.kt:226" }
        restored = reloadFiring(state); state = restored.first; weapon = restored.second
        check(weapon.isFiring && weapon.browserEncounterVolleys == 0) { "Reload lost beam pre-fire state" }
        advance(weapon) { weapon.browserEncounterVolleys == 1 }
        check((weapon as BeamBlueprint.BeamInstance).isBeamOn) { "Beam emission did not count one volley" }
        weapon.update(.05f, 0f, false)
        restored = reloadFiring(state); state = restored.first; weapon = restored.second
        check((weapon as BeamBlueprint.BeamInstance).isBeamOn && weapon.browserEncounterVolleys == 1) { "Active beam reload lost beam or volley count" }
        advance(weapon)
        check(weapon.browserEncounterVolleys == 1) { "Restored active beam counted a second volley" }
        state.enemy!!.health = 0; check(earned("ACH_ONE_VOLLEY")) { "Active-beam reload prevented One Volley" }

        // Native SetCurrentShip is reached from weapon-system target-ship changes,
        // not individual aims: a bomb at our own ship still accumulates.
        println("TEST achievement target reassignment and encounter reset")
        state = reset("PLAYER_SHIP_HARD"); spawn(state); preignite(state)
        weapon = equip(state.player, "BOMB_1"); solo(state)
        fire(weapon, state.enemy!!); fire(weapon, state.player)
        check(weapon.browserEncounterVolleys == 2) { "Self-targeted volley reset the encounter count" }
        state = reload(state)
        check(state.player.hardpoints[0].weapon!!.browserEncounterVolleys == 2) { "Reload lost reassigned-target volleys" }
        state.enemy!!.health = 0; check(!earned("ACH_ONE_VOLLEY")) { "Alternating targets hid a second volley" }
        // A new enemy target starts a new count for existing weapon instances.
        state = reset("PLAYER_SHIP_HARD"); spawn(state); preignite(state)
        weapon = equip(state.player, "LASER_BURST_2"); solo(state)
        repeat(2) { fire(weapon, state.enemy!!) }
        // Travel removes projectiles addressed to the old ship before replacement.
        state.player.resetAfterJump()
        spawn(state)
        check(weapon.browserEncounterVolleys == 0) { "New target ship did not reset volley count" }
        fire(weapon, state.enemy!!)
        state = reload(state)
        check(state.player.hardpoints[0].weapon!!.browserEncounterVolleys == 1) { "Reload after target change lost volley count" }
        state.enemy!!.health = 0; check(earned("ACH_ONE_VOLLEY")) { "Previous encounter volleys carried into a new target" }

        state = reset("PLAYER_SHIP_CIRCLE"); spawn(state)
        val legacy = state.saveGameState()
        legacy.rootElement.getChild("browserAchievements").removeAttribute("combatComplete")
        main.loadSavedGame(legacy); state = main.currentState as InGameState
        state.enemy!!.health = 0
        check(!earned("ACH_ONLY_DRONES")) { "Missing legacy history was treated as no firing" }
        spawn(state); state.enemy!!.health = 0
        check(earned("ACH_ONLY_DRONES")) { "A fresh encounter did not recover known history" }
    }
}
