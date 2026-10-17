package ftl.browser

import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/** Executed by the authenticated development diagnostics page against real assets. */
object BrowserDiagnostics {
    fun achievementProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val original = main.profile.save()
        val profile = main.profile
        val table = main.gameContent.achievements
        fun reset(ship: String = "PLAYER_SHIP_HARD"): InGameState {
            profile.load(SaveProfile.createBlank().save())
            return fresh(main, container, ship)
        }
        fun earned(id: String) = profile.getAchievement(table[id]) != null
        fun click(button: Button, mouse: Int = xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON) {
            button.mouseDown(mouse, button.pos.x + 2, button.pos.y + 2)
        }
        try {
            var state = reset()
            state.browserAchievements.visit(3)
            for (id in listOf("ACH_NO_UPGRADES", "ACH_NO_REPAIR", "ACH_PACIFIST")) check(!earned(id)) { "BrowserDiagnostics.kt:25" }
            state.browserAchievements.visit(4)
            for (id in listOf("ACH_NO_UPGRADES", "ACH_NO_REPAIR", "ACH_PACIFIST")) check(earned(id)) { "Missing sector-five achievement $id" }
            check(!earned("ACH_ENERGY_MANPOWER") && !earned("ACH_FED_UPGRADE")) { "Wrong ship earned a family achievement" }
            state.browserAchievements.visit(6)
            for (id in listOf("ACH_NO_MISSILES", "ACH_NO_DRONES", "ACH_NO_BUYING", "ACH_NO_DEATH")) check(!earned(id)) { "BrowserDiagnostics.kt:30" }
            state.browserAchievements.visit(7)
            for (id in listOf("ACH_NO_MISSILES", "ACH_NO_DRONES", "ACH_NO_BUYING", "ACH_NO_DEATH")) check(earned(id)) { "Missing sector-eight achievement $id" }
            for ((action, id, sector) in listOf(
                Triple(BrowserAchievementProgress.Action.SYSTEM_UPGRADE, "ACH_NO_UPGRADES", 4),
                Triple(BrowserAchievementProgress.Action.STORE_REPAIR, "ACH_NO_REPAIR", 4),
                Triple(BrowserAchievementProgress.Action.FIRED_SHOT, "ACH_PACIFIST", 4),
                Triple(BrowserAchievementProgress.Action.OFFENSIVE_DRONE, "ACH_PACIFIST", 4),
                Triple(BrowserAchievementProgress.Action.TELEPORTED, "ACH_PACIFIST", 4),
                Triple(BrowserAchievementProgress.Action.USED_MISSILE, "ACH_NO_MISSILES", 7),
                Triple(BrowserAchievementProgress.Action.USED_DRONE, "ACH_NO_DRONES", 7),
                Triple(BrowserAchievementProgress.Action.STORE_PURCHASE, "ACH_NO_BUYING", 7),
                Triple(BrowserAchievementProgress.Action.LOST_CREW, "ACH_NO_DEATH", 7)
            )) {
                state = reset(); state.browserAchievements.record(action)
                main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
                state = main.currentState as InGameState
                check(state.browserAchievements.has(action)) { "Lost persisted action $action" }
                state.browserAchievements.visit(sector)
                check(!earned(id)) { "Disqualifying action $action still earned $id after reload" }
            }
            // AdvanceWorldLevel (0x5edec0) tests only the system upgrade flag: a reactor upgrade keeps ACH_NO_UPGRADES.
            state = reset(); state.browserAchievements.record(BrowserAchievementProgress.Action.REACTOR_UPGRADE)
            state.browserAchievements.visit(4); check(earned("ACH_NO_UPGRADES")) { "A reactor upgrade blocked ACH_NO_UPGRADES" }
            state = reset("PLAYER_SHIP_ENERGY_2"); state.browserAchievements.visit(4)
            check(earned("ACH_ENERGY_MANPOWER")) { "BrowserDiagnostics.kt:53" }
            state = reset("PLAYER_SHIP_ENERGY"); state.browserAchievements.record(BrowserAchievementProgress.Action.REACTOR_UPGRADE)
            state.browserAchievements.visit(4); check(!earned("ACH_ENERGY_MANPOWER")) { "BrowserDiagnostics.kt:55" }
            state = reset("PLAYER_SHIP_FED_2"); state.browserAchievements.visit(4); check(earned("ACH_FED_UPGRADE")) { "BrowserDiagnostics.kt:56" }
            state = reset("PLAYER_SHIP_FED"); state.browserAchievements.record(BrowserAchievementProgress.Action.WEAPON_UPGRADE)
            state.browserAchievements.visit(4); check(!earned("ACH_FED_UPGRADE")) { "BrowserDiagnostics.kt:58" }
            state = reset("PLAYER_SHIP_ANAEROBIC")
            state.player.scrap = 599; state.browserAchievements.update(); check(!earned("ACH_LANIUS_SCRAP")) { "BrowserDiagnostics.kt:60" }
            state.player.scrap = 600; state.browserAchievements.update(); check(earned("ACH_LANIUS_SCRAP")) { "BrowserDiagnostics.kt:61" }
            state.player.rooms.forEach { it.oxygen = .20f }; state.player.update(0f)
            state.browserAchievements.onJump(); state.browserAchievements.visit(7)
            check(earned("ACH_LANIUS_OXYGEN")) { "BrowserDiagnostics.kt:64" }
            state = reset("PLAYER_SHIP_ANAEROBIC")
            state.player.rooms.forEach { it.oxygen = .21f }; state.player.update(0f)
            state.browserAchievements.onJump()
            state.player.rooms.forEach { it.oxygen = .1f }; state.player.update(0f)
            state.browserAchievements.visit(7); check(!earned("ACH_LANIUS_OXYGEN")) { "Oxygen history was reconstructed only from current state" }
            state = reset()
            val legacy = state.saveGameState().also { it.rootElement.removeChild("browserAchievements") }
            main.loadSavedGame(legacy); state = main.currentState as InGameState
            check(!state.browserAchievements.historyComplete) { "BrowserDiagnostics.kt:73" }
            state.browserAchievements.visit(7); check(!earned("ACH_NO_DEATH")) { "Unknown legacy history earned a restricted achievement" }

            state = reset()
            // The first generated pirate (from seed 537) with crew and a room-targeting gun.
            var gunSeed = 537
            do {
                state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, gunSeed++)
                check(gunSeed < 800) { "No armed pirate fixture" }
            } while (state.enemy!!.crew.isEmpty() || state.enemy!!.hardpoints.none { it.weapon is xyz.znix.xftl.weapons.IRoomTargetingWeapon })
            state.debugFlags.noEnemyFire.set = true
            val enemy = state.enemy!!
            val enemyGun = enemy.hardpoints.mapNotNull { it.weapon }.first { it is xyz.znix.xftl.weapons.IRoomTargetingWeapon }
            enemyGun.timeCharged = enemyGun.chargeTime
            (enemyGun as xyz.znix.xftl.weapons.IRoomTargetingWeapon).fire { state.player.rooms.first() }
            enemy.crew.first().health = 0f; enemy.update(0f)
            check(!state.browserAchievements.has(BrowserAchievementProgress.Action.FIRED_SHOT) && !state.browserAchievements.has(BrowserAchievementProgress.Action.LOST_CREW)) { "Enemy actions disqualified player achievements" }
            val guns = state.player.hardpoints.mapNotNull { it.weapon }
            guns.forEach { it.timeCharged = it.chargeTime }
            (guns.first { it.type.missilesUsed == 0 } as xyz.znix.xftl.weapons.IRoomTargetingWeapon).fire { enemy.rooms.first() }
            check(state.browserAchievements.has(BrowserAchievementProgress.Action.FIRED_SHOT) && !state.browserAchievements.has(BrowserAchievementProgress.Action.USED_MISSILE)) { "BrowserDiagnostics.kt:93" }
            (guns.first { it.type.missilesUsed > 0 } as xyz.znix.xftl.weapons.IRoomTargetingWeapon).fire { enemy.rooms.first() }
            check(state.browserAchievements.has(BrowserAchievementProgress.Action.USED_MISSILE)) { "Fired missiles were not recorded" }
            state.playerCrew.first().health = 0f; state.player.update(0f)
            check(state.browserAchievements.has(BrowserAchievementProgress.Action.LOST_CREW)) { "Player crew death was not recorded" }

            state = reset(); state.player.scrap = 1000
            val window = ShipWindow(state, state.player, ShipWindow.Tab.UPGRADES) {}
            window.draw(graphics)
            val upgrade = window.buttons.first { it.basePos.x == 32 && it.basePos.y == 115 }
            val before = state.player.mainSystems.first().energyLevels
            click(upgrade)
            check(state.player.mainSystems.first().energyLevels == before + 1) { "BrowserDiagnostics.kt:105" }
            check(BrowserAchievementProgress.Action.SYSTEM_UPGRADE in window.pendingUpgradeActions) { "BrowserDiagnostics.kt:106" }
            window.draw(graphics)
            click(window.buttons.first { it.basePos.x == 32 && it.basePos.y == 115 }, xyz.znix.xftl.sys.Input.MOUSE_RIGHT_BUTTON)
            window.escapePressed()
            check(state.player.mainSystems.first().energyLevels == before && !state.browserAchievements.has(BrowserAchievementProgress.Action.SYSTEM_UPGRADE)) { "Undone upgrade disqualified the run" }
            val committed = ShipWindow(state, state.player, ShipWindow.Tab.UPGRADES) {}
            committed.draw(graphics)
            click(committed.buttons.first { it.basePos.x == 298 && it.basePos.y == 327 })
            committed.escapePressed()
            // The reactor box sets only the reactor flag (Upgrades confirm 0x546a30).
            check(!state.browserAchievements.has(BrowserAchievementProgress.Action.SYSTEM_UPGRADE) && state.browserAchievements.has(BrowserAchievementProgress.Action.REACTOR_UPGRADE)) { "BrowserDiagnostics.kt:115" }
            state = reset(); state.player.scrap = 1000
            state.shipUI.hotkeyPressed(state.hotkeyManager.byID.getValue(VanillaHotkeys.SHIP_UPGRADES), container.input)
            main.render(container, graphics)
            check(state.shipUI.diagnosticWindow == "ShipWindow") { "BrowserDiagnostics.kt:119" }
            state.shipUI.mouseClick(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON,
                (container.width - 587) / 2 + 34, (container.height - 464) / 2 + 117, xyz.znix.xftl.math.ConstPoint.ZERO)
            check(BrowserAchievementProgress.Action.SYSTEM_UPGRADE in state.shipUI.pendingUpgradeActions) { "BrowserDiagnostics.kt:122" }
            check(!state.browserAchievements.has(BrowserAchievementProgress.Action.SYSTEM_UPGRADE)) { "Pending purchase disabled live Undo" }
            main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
            check(state.browserAchievements.has(BrowserAchievementProgress.Action.SYSTEM_UPGRADE)) { "Autosave lost the history of purchased levels" }
            val store = StoreData().also { it.availableResources[Resource.FUEL] = 2 }
            val storeWindow = StoreWindow(state, state.player, store) {}
            click(storeWindow.ResourceButton(xyz.znix.xftl.math.ConstPoint.ZERO, Resource.FUEL))
            check(state.browserAchievements.has(BrowserAchievementProgress.Action.STORE_PURCHASE) && !state.browserAchievements.has(BrowserAchievementProgress.Action.STORE_REPAIR)) { "BrowserDiagnostics.kt:129" }
            state = reset(); state.player.scrap = 1000; state.player.health--
            val repairWindow = StoreWindow(state, state.player, StoreData()) {}
            repairWindow.draw(graphics)
            repairWindow.mouseClick(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, 25, 300)
            check(state.player.health == state.player.maxHealth) { "BrowserDiagnostics.kt:134" }
            check(state.browserAchievements.has(BrowserAchievementProgress.Action.STORE_REPAIR) && !state.browserAchievements.has(BrowserAchievementProgress.Action.STORE_PURCHASE)) { "Store repair counted as buying equipment" }

            state = reset(); profile.collectBrowserScrap(9999, Difficulty.EASY)
            check(!earned("ACH_SCRAP")) { "BrowserDiagnostics.kt:138" }; state.browserProgress.collectScrap(1); check(earned("ACH_SCRAP")) { "BrowserDiagnostics.kt:138" }
            repeat(999) { profile.defeatBrowserShip(Difficulty.EASY) }
            check(!earned("ACH_SHIPS")) { "BrowserDiagnostics.kt:140" }; state.browserProgress.defeatShip(); check(earned("ACH_SHIPS")) { "BrowserDiagnostics.kt:140" }
            val run = state.saveGameState(); val savedProfile = BrowserXml.parse(BrowserXml.write(profile.save()))
            repeat(3) { profile.load(savedProfile); main.loadSavedGame(run) }
            check(profile.browserLifetimeScrap == 10000L && profile.browserLifetimeShips == 1000L) { "Reload counted lifetime records again" }
            check(earned("ACH_SCRAP") && earned("ACH_SHIPS")) { "BrowserDiagnostics.kt:144" }
            profile.achievementPopups = false; main.progressNotifications.update(0f)
            profile.achievementPopups = true; profile.clearNotifications()
            profile.deleteAchievement(table["ACH_SECTOR_5"])
            profile.unlockAchievement(table["ACH_SECTOR_5"], Difficulty.NORMAL)
            profile.unlockAchievement(table["ACH_SECTOR_5"], Difficulty.NORMAL)
            check(profile.pendingNotificationCount == 1) { "Repeated achievement queued duplicate notifications" }
            main.progressNotifications.update(0f); check(main.progressNotifications.active) { "BrowserDiagnostics.kt:151" }
            main.render(container, graphics)
            main.progressNotifications.update(2f); check(main.progressNotifications.active) { "BrowserDiagnostics.kt:153" }
            main.progressNotifications.update(2f); check(!main.progressNotifications.active) { "BrowserDiagnostics.kt:154" }
            profile.unlockAchievement(table["ACH_SECTOR_5"], Difficulty.HARD)
            check(profile.pendingNotificationCount == 1) { "Difficulty upgrade omitted its notification" }
            val options = OptionsWindow(SettingsContext(main, container.input), {}, {})
            options.draw(graphics); options.mouseClick(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, 470, 162)
            check(!profile.achievementPopups) { "BrowserDiagnostics.kt:159" }
            options.escapePressed(); val preferences = profile.save(); profile.load(preferences)
            check(!profile.achievementPopups && profile.pendingNotificationCount == 0) { "BrowserDiagnostics.kt:161" }
            main.progressNotifications.update(0f); check(!main.progressNotifications.active) { "BrowserDiagnostics.kt:162" }
            profile.deleteAchievement(table["ACH_SECTOR_8"])
            profile.unlockAchievement(table["ACH_SECTOR_8"], Difficulty.HARD)
            check(earned("ACH_SECTOR_8") && profile.pendingNotificationCount == 0) { "Disabling popups disabled progression or queued unwanted feedback" }
            achievementStateProbe(main, container)
            BrowserTravelDiagnostics.run(main, container, graphics)
            BrowserCombatAchievementDiagnostics.run(main, container)
            BrowserImpactAchievementDiagnostics.run(main, container)
            BrowserCrewAchievementDiagnostics.run(main, container)
            return "PASS sector-five/eight positives and every disqualifying action after reload, ship/layout eligibility, Lanius scrap/oxygen thresholds, unknown-history exclusion, actual upgrade/Undo/pending autosave/reactor/store/repair actions, lifetime boundaries/reload idempotence, notification deduplication/timing, popup option and earning while disabled; live crew/system/power/drone/AE/fire/oxygen/ion/vision/lockdown achievements and persisted beacon hull minimum; travel, crew blue choices, crew victories, Rock hull victories, survivor and Crystal sector event/save/exit; combat destruction predicates with mid-volley, active-beam and target-reassignment restoration; dodge streaks, single-cloak avoidance, timed beam coverage, single-beam crew kills, Crystal shard and invasion with saved counters, zero-time projectile restoration and negative cases"
        } finally { profile.load(original) }
    }

    private fun achievementStateProbe(main: MainGame, container: GameContainer) {
        val profile = main.profile
        fun reset(ship: String = "PLAYER_SHIP_HARD"): InGameState {
            profile.load(SaveProfile.createBlank().save())
            return fresh(main, container, ship).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun earned(id: String) = profile.getAchievement(main.gameContent.achievements[id]) != null
        fun spawn(state: InGameState, largeRoom: Boolean = false): xyz.znix.xftl.Ship {
            var seed = 71
            do {
                state.debugSpawnShip(state.eventManager.getShip(if (largeRoom) "MANTIS_FIGHT" else "PIRATE"), Difficulty.NORMAL, 4, seed++)
                check(seed < 150) { "No suitable crewed achievement fixture (large room: $largeRoom)" }
            } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated || (largeRoom && state.enemy!!.rooms.none { it.cellCount >= 4 }))
            return state.enemy!!
        }
        fun install(state: InGameState, type: String) {
            state.player.systemSlots.first { it.system.type == type }.let { it.room.setSystem(it) }
        }
        var state = reset()
        state.player.health = 2; state.browserAchievements.update()
        state.player.health = state.player.maxHealth; state.browserAchievements.update()
        check(!earned("ACH_TOUGH_SHIP")) { "Two hull earned one-hull repair" }
        state.player.health = 1; state.browserAchievements.update()
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        check(state.browserAchievements.minimumBeaconHull == 1) { "BrowserDiagnostics.kt:199" }
        state.player.health = state.player.maxHealth; state.browserAchievements.update()
        check(earned("ACH_TOUGH_SHIP")) { "Saved beacon hull minimum did not earn repair achievement" }
        state = reset(); state.player.health = 1; state.browserAchievements.update()
        state.player.health = 2; state.browserAchievements.onJump()
        state.player.health = state.player.maxHealth; state.browserAchievements.update()
        check(!earned("ACH_TOUGH_SHIP")) { "Previous beacon hull minimum leaked into new beacon" }
        state = reset("PLAYER_SHIP_CIRCLE"); state.player.health = 1; state.browserAchievements.update()
        state.player.health = state.player.maxHealth; state.browserAchievements.update()
        check(!earned("ACH_TOUGH_SHIP")) { "Wrong ship earned Kestrel repair" }

        state = reset()
        for (race in listOf("engi", "energy", "slug", "rock"))
            state.player.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager[race] as xyz.znix.xftl.crew.CrewBlueprint, state), false)
        state.browserAchievements.update(); check(!earned("ACH_UNITED_FEDERATION")) { "BrowserDiagnostics.kt:213" }
        state.player.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager["mantis"] as xyz.znix.xftl.crew.CrewBlueprint, state), false)
        state.browserAchievements.update(); check(earned("ACH_UNITED_FEDERATION")) { "Six distinct controllable races did not earn Kestrel crew achievement" }
        state = reset("PLAYER_SHIP_CIRCLE")
        for (race in listOf("energy", "slug", "rock", "mantis"))
            state.player.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager[race] as xyz.znix.xftl.crew.CrewBlueprint, state), false)
        state.browserAchievements.update(); check(!earned("ACH_UNITED_FEDERATION")) { "Wrong ship earned Kestrel crew achievement" }
        state = reset()
        val available = state.player.rooms.filter { it.system == null }.mapNotNull { it.systemSlots.firstOrNull() }
        for (slot in available) {
            slot.room.setSystem(slot)
            if (state.player.systems.size == 10) break
        }
        check(state.player.systems.size == 10) { "Ten-system fixture failed" }
        state.browserAchievements.update(); check(!earned("ACH_FULL_ARSENAL")) { "BrowserDiagnostics.kt:227" }
        available.first { it.room.system == null }.let { it.room.setSystem(it) }
        state.browserAchievements.update(); check(earned("ACH_FULL_ARSENAL")) { "BrowserDiagnostics.kt:229" }

        state = reset("PLAYER_SHIP_CIRCLE")
        state.player.purchasedReactorPower = 25
        val drones = state.player.drones!!; drones.energyLevels = 8
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
        repeat(3) { slot ->
            drones.drones[slot] = xyz.znix.xftl.systems.Drones.DroneInfo(state.blueprintManager["REPAIR"] as xyz.znix.xftl.weapons.DroneBlueprint)
            check(drones.setDronePower(slot, true)) { "BrowserDiagnostics.kt:237" }
            state.browserAchievements.update()
            check(earned("ACH_ROBOTIC") == (slot == 2)) { "Powered drone count threshold" }
        }

        state = reset("PLAYER_SHIP_ENERGY")
        state.player.rooms.filter { it.system == null }.forEach { room -> room.systemSlots.firstOrNull()?.let { room.setSystem(it) } }
        state.player.purchasedReactorPower = 25
        val battery = state.player.backupBattery!!; battery.energyLevels = 2; battery.startBattery()
        state.player.mainSystems.forEach { system ->
            system.energyLevels = system.blueprint.maxPower
            repeat(12) { system.decreasePower() }
        }
        state.player.update(0f)
        val candidates = state.player.mainSystems.filter { it !is xyz.znix.xftl.systems.Weapons && it !is xyz.znix.xftl.systems.Drones }
            .sortedBy { if (it is xyz.znix.xftl.systems.Engines) 1 else 0 }
        for (system in candidates) {
            repeat(12) { if (state.player.powerConsumed < 28) system.increasePower() }
        }
        check(state.player.powerConsumed == 28) { "Power fixture: ${state.player.powerConsumed}" }
        state.browserAchievements.update(); check(!earned("ACH_ENERGY_POWER")) { "BrowserDiagnostics.kt:257" }
        candidates.last().increasePower()
        check(state.player.powerConsumed == 29) { "Power fixture could not add a twenty-ninth bar" }
        state.browserAchievements.update(); check(earned("ACH_ENERGY_POWER")) { "BrowserDiagnostics.kt:260" }

        state = reset("PLAYER_SHIP_ANAEROBIC_2")
        install(state, "hacking"); install(state, "battery")
        state.player.dronesCount = 3
        state.debugFlags.showEverything.set = true
        val enemy = spawn(state)
        state.debugFlags.noDmg.set = true; state.debugFlags.noCrewDamage.set = true
        val hacking = state.player.hacking!!; hacking.increasePower(); state.player.update(0f)
        hacking.selectTarget(enemy.weapons!!.room!!)
        repeat(300) { state.updateGameState(.05f) }
        check(hacking.droneLanded) { "AE achievement fixture did not land its hacking probe" }
        val mind = state.player.mindControl!!; mind.increasePower()
        enemy.rooms.forEach { it.update(0f) }
        mind.selectRoom(enemy.crew.first { it is xyz.znix.xftl.crew.LivingCrew && it.codename != "slug" }.room); mind.update(0f)
        check(mind.active) { "AE achievement fixture did not activate mind control" }
        hacking.startHackingPulse(); state.browserAchievements.update()
        check(hacking.active && !earned("ACH_LANIUS_ADVANCED")) { "Two AE systems earned three-system achievement" }
        state.player.backupBattery!!.startBattery(); state.browserAchievements.update()
        check(earned("ACH_LANIUS_ADVANCED")) { "Three active AE systems did not earn the Lanius achievement" }

        state = reset(); var target = spawn(state)
        target.rooms.forEach { room -> room.fires.indices.forEach { room.spawnFireAt(it) } }
        target.rooms.first().fires[0] = null
        state.browserAchievements.updateEnemy(); check(!earned("ACH_BURNING")) { "BrowserDiagnostics.kt:284" }
        target.rooms.first().spawnFireAt(0); state.browserAchievements.updateEnemy(); check(earned("ACH_BURNING")) { "BrowserDiagnostics.kt:285" }
        state = reset(); target = spawn(state)
        target.rooms.forEach { it.oxygen = .05f }; target.update(0f)
        state.browserAchievements.updateEnemy(); check(!earned("ACH_SUFFOCATE")) { "BrowserDiagnostics.kt:288" }
        target.rooms.forEach { it.oxygen = .04f }; target.update(0f)
        state.browserAchievements.updateEnemy(); check(earned("ACH_SUFFOCATE")) { "BrowserDiagnostics.kt:290" }
        state = reset(); target = spawn(state); target.isAutomated = true
        target.rooms.forEach { it.oxygen = 0f }; target.update(0f)
        state.browserAchievements.updateEnemy(); check(!earned("ACH_SUFFOCATE")) { "Automated ship earned suffocation" }
        state = reset("PLAYER_SHIP_CIRCLE"); target = spawn(state)
        target.systems.take(3).forEach { it.ionTimer = 5f }
        state.browserAchievements.updateEnemy(); check(!earned("ACH_IONED")) { "BrowserDiagnostics.kt:296" }
        target.systems[3].ionTimer = 5f
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        state.browserAchievements.updateEnemy(); check(earned("ACH_IONED")) { "BrowserDiagnostics.kt:299" }

        state = reset("PLAYER_SHIP_JELLY")
        install(state, "sensors"); state.player.sensors!!.energyLevels = 2
        target = spawn(state); target.rooms.forEach { it.update(0f) }
        state.browserAchievements.updateEnemy(); check(!earned("ACH_SLUG_VISION")) { "Functioning level-two sensors earned no-sensors vision" }
        state.player.sensors!!.damagedEnergyLevels = 2
        target.rooms.forEach { it.update(0f) }
        state.browserAchievements.updateEnemy(); check(!earned("ACH_SLUG_VISION")) { "Unseen rooms earned full vision" }
        state.debugFlags.showEverything.set = true; target.rooms.forEach { it.update(0f) }
        state.browserAchievements.updateEnemy(); check(earned("ACH_SLUG_VISION")) { "BrowserDiagnostics.kt:309" }
        state = reset("PLAYER_SHIP_CRYSTAL"); target = spawn(state, true)
        target.crew.toList().forEach { it.removeFromShip() }
        val room = target.rooms.first { it.cellCount >= 4 }
        repeat(3) { index ->
            val crew = target.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint, state), false)
            crew.jumpTo(room, xyz.znix.xftl.math.ConstPoint(index % room.width, index / room.width))
        }
        room.updateCrewInRoom(); state.browserAchievements.updateEnemy()
        check(!earned("ACH_CRYSTAL_LOCKDOWN")) { "Unlocked room earned lockdown" }
        room.lockdown()
        state.browserAchievements.updateEnemy(); check(!earned("ACH_CRYSTAL_LOCKDOWN")) { "Three enemies earned four-enemy lockdown" }
        val fourth = target.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint, state), false)
        fourth.jumpTo(room, xyz.znix.xftl.math.ConstPoint(3 % room.width, 3 / room.width))
        room.updateCrewInRoom(); room.update(13f)
        check(!room.isLockedDown) { "BrowserDiagnostics.kt:324" }
        state.browserAchievements.updateEnemy(); check(!earned("ACH_CRYSTAL_LOCKDOWN")) { "Expired room lockdown earned achievement" }
        room.lockdown()
        main.loadSavedGame(state.saveGameState()); state = main.currentState as InGameState
        check(state.enemy!!.rooms.first { it.id == room.id }.isLockedDown) { "Room lockdown was not saved" }
        state.browserAchievements.updateEnemy(); check(earned("ACH_CRYSTAL_LOCKDOWN")) { "BrowserDiagnostics.kt:329" }
        state = reset("PLAYER_SHIP_CRYSTAL")
        val ownRoom = state.player.rooms.first { it.cellCount >= 4 }
        repeat(4) { index ->
            val crew = state.player.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint, state), false, true)
            crew.jumpTo(ownRoom, xyz.znix.xftl.math.ConstPoint(index % ownRoom.width, index / ownRoom.width))
        }
        ownRoom.updateCrewInRoom(); ownRoom.lockdown(); state.browserAchievements.update()
        check(earned("ACH_CRYSTAL_LOCKDOWN")) { "Four intruders locked on player ship did not earn achievement" }
    }

    fun unlockProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val original = main.profile.save()
        val profile = main.profile
        val families = main.gameContent.shipFamilies
        val achievements = main.gameContent.achievements
        try {
            profile.load(SaveProfile.createBlank().save())
            profile.configureProgression(families, achievements)
            val allShips = families.families.flatMap { it.ships }
            check(allShips.filter(profile::isShipUnlocked) == listOf(ShipProgression.KESTREL)) { "Fresh profile exposes locked ships" }
            val before = main.currentState
            try { main.startNewGame("PLAYER_SHIP_ROCK", Difficulty.NORMAL, null); error("Locked ship started") }
            catch (expected: IllegalArgumentException) { check(main.currentState === before) { "BrowserDiagnostics.kt:352" } }
            profile.visitSector(ShipProgression.ENGI, 4, true, Difficulty.NORMAL)
            check(!profile.isShipUnlocked(ShipProgression.ENGI)) { "A non-Kestrel run unlocked Engi" }
            val kestrel = fresh(main, container)
            kestrel.browserProgress.visit(3)
            check(!profile.isShipUnlocked(ShipProgression.ENGI)) { "BrowserDiagnostics.kt:357" }
            kestrel.browserProgress.visit(4)
            check(profile.isShipUnlocked(ShipProgression.ENGI)) { "BrowserDiagnostics.kt:359" }
            val family = families.byShipId[ShipProgression.KESTREL]!!
            profile.unlockAchievement(achievements[family.achievements[0]], Difficulty.HARD)
            check(!profile.isShipUnlocked(family.ships[1])) { "BrowserDiagnostics.kt:362" }
            profile.unlockAchievement(achievements[family.achievements[1]], Difficulty.NORMAL)
            check(profile.isShipUnlocked(family.ships[1])) { "BrowserDiagnostics.kt:364" }
            profile.visitSector(family.ships[0], 7, true, Difficulty.HARD)
            profile.visitSector(family.ships[1], 7, false, Difficulty.HARD)
            profile.visitSector(family.ships[1], 6, true, Difficulty.HARD)
            check(!profile.isShipUnlocked(family.ships[2])) { "C unlocked without the AE/B/Sector 8 requirements" }
            val layoutB = fresh(main, container, family.ships[1], Difficulty.HARD)
            layoutB.browserProgress.visit(7)
            check(profile.isShipUnlocked(family.ships[2])) { "BrowserDiagnostics.kt:371" }
            val runBefore = main.currentState
            try { main.startNewGame(RunConfiguration(family.ships[2], Difficulty.HARD, false), null); error("AE-only layout started in vanilla") }
            catch (expected: IllegalArgumentException) { check(main.currentState === runBefore) { "BrowserDiagnostics.kt:374" } }

            // Threshold and chain tests start independently of the earlier milestones.
            profile.load(SaveProfile.createBlank().save())
            for (base in listOf("PLAYER_SHIP_FED", "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_MANTIS"))
                profile.unlockShip(families.byShipId[base]!!, Difficulty.NORMAL)
            check(!profile.isShipUnlocked(ShipProgression.LANIUS)) { "Lanius unlocked with fewer than five A layouts" }
            profile.unlockShip(families.byShipId["PLAYER_SHIP_JELLY"]!!, Difficulty.NORMAL)
            check(profile.isShipUnlocked(ShipProgression.LANIUS)) { "BrowserDiagnostics.kt:382" }
            check(!ShipProgression.existsInMode(ShipProgression.LANIUS, false, families)) { "BrowserDiagnostics.kt:383" }
            profile.load(SaveProfile.createBlank().save())
            profile.visitSector(ShipProgression.KESTREL, 4, true, Difficulty.NORMAL)
            val chain = listOf(ShipProgression.ENGI, "PLAYER_SHIP_FED", "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_MANTIS", "PLAYER_SHIP_JELLY", "PLAYER_SHIP_ROCK", "PLAYER_SHIP_STEALTH")
            for (index in 0 until chain.lastIndex) {
                profile.recordShipVictory(chain[index] + "_2", Difficulty.NORMAL)
                check(profile.isShipUnlocked(chain[index + 1])) { "Victory-chain successor missing" }
            }
            val originals = families.families.filter { it.ships.first() !in listOf(ShipProgression.LANIUS, ShipProgression.CRYSTAL) }
            for (f in originals) profile.recordShipVictory(f.ships[0], Difficulty.EASY)
            check(!profile.isShipUnlocked(ShipProgression.CRYSTAL)) { "Crystal unlocked without all A/B victories" }
            for (f in originals.dropLast(1)) profile.recordShipVictory(f.ships[1], Difficulty.NORMAL)
            check(!profile.isShipUnlocked(ShipProgression.CRYSTAL)) { "BrowserDiagnostics.kt:395" }
            profile.recordShipVictory(originals.last().ships[1], Difficulty.HARD)
            check(profile.isShipUnlocked(ShipProgression.CRYSTAL)) { "BrowserDiagnostics.kt:397" }
            profile.recordShipVictory(originals.first().ships[0], Difficulty.HARD)
            profile.recordShipVictory(originals.first().ships[0], Difficulty.EASY)
            check(profile.getLayoutVictory(originals.first().ships[0])?.difficulty == Difficulty.HARD) { "BrowserDiagnostics.kt:400" }
            check(profile.getAchievement(achievements["ACH_UNLOCK_ALL"]) != null) { "All ship A layouts did not award the unlock-all achievement" }
            val saved = BrowserXml.write(profile.save())
            profile.load(BrowserXml.parse(saved))
            check(profile.isShipUnlocked(ShipProgression.CRYSTAL) && profile.getLayoutVictory(originals.first().ships[0])?.difficulty == Difficulty.HARD) { "BrowserDiagnostics.kt:404" }
            profile.recordShipVictory(originals.last().ships[1], Difficulty.EASY)
            check(BrowserXml.write(profile.save()) == saved) { "Repeated victory changed completed progression" }
            profile.load(SaveProfile.createBlank().save())
            for (questFamily in families.families.filter { it.unlockId != null }) {
                profile.unlockShip(questFamily, Difficulty.NORMAL)
                check(profile.isShipUnlocked(questFamily.ships.first()) && !profile.isShipUnlocked(questFamily.ships[1])) { "Quest availability for ${questFamily.ships.first()}" }
            }
            profile.load(SaveProfile.createBlank().save())
            val consoleGame = fresh(main, container)
            consoleGame.debugConsole.execute("ship-unlock PLAYER_SHIP_CIRCLE HARD")
            check(profile.isShipUnlocked(ShipProgression.ENGI)) { "Console family unlock failed" }
            consoleGame.debugConsole.execute("ach-set ACH_UNITED_FEDERATION NORMAL")
            consoleGame.debugConsole.execute("ach-set ACH_FULL_ARSENAL HARD")
            check(profile.isShipUnlocked("PLAYER_SHIP_HARD_2")) { "Console achievements did not unlock B" }
            consoleGame.debugConsole.execute("layout-unlock PLAYER_SHIP_HARD_3 HARD")
            check(profile.isShipUnlocked("PLAYER_SHIP_HARD_3")) { "Console layout unlock failed" }
            consoleGame.debugConsole.execute("gameover WIN")
            check(profile.getLayoutVictory(ShipProgression.KESTREL)?.difficulty == Difficulty.NORMAL) { "Console victory did not affect progression" }
            main.switchToShipSelect()
            main.update(container, 0f); main.render(container, graphics)
            return "PASS fresh Kestrel-only availability, normal-start eligibility/mode guards, Kestrel-sector Engi, two-achievement B, AE/B-sector C, five-family Lanius, all quests/victory-chain successors, all eight A/B Crystal conditions, difficulty upgrades, idempotent profile restore and console-granted progression"
        } finally { profile.load(original) }
    }
    fun droneProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        var state = fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.debugFlags.noEnemyFire.set = true
        state.player.purchasedReactorPower = 25
        var drones = state.player.drones!!
        drones.energyLevels = 8
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
        check(!drones.setDronePower(-1, true) && !drones.setDronePower(drones.drones.size, true)) { "BrowserDiagnostics.kt:435" }
        drones.drones[0] = xyz.znix.xftl.systems.Drones.DroneInfo(state.blueprintManager["DRONE_HACKING"] as xyz.znix.xftl.weapons.DroneBlueprint)
        val beforeParts = state.player.dronesCount
        check(!drones.setDronePower(0, true) && state.player.dronesCount == beforeParts) { "Drone control consumed a part for a hacking-system blueprint" }
        check(!state.browserAchievements.has(BrowserAchievementProgress.Action.USED_DRONE)) { "Rejected deployment disqualified the run" }
        fun equip(slot: Int, id: String) {
            drones.drones[slot] = xyz.znix.xftl.systems.Drones.DroneInfo(state.blueprintManager[id] as xyz.znix.xftl.weapons.DroneBlueprint)
            check(drones.setDronePower(slot, true)) { "Drone failed to launch: $id" }
        }
        equip(0, "DRONE_SHIELD_PLAYER")
        check(state.browserAchievements.has(BrowserAchievementProgress.Action.USED_DRONE) && !state.browserAchievements.has(BrowserAchievementProgress.Action.OFFENSIVE_DRONE)) { "Shield drone counted as an offensive deployment" }
        val deployed = drones.drones[0]!!.instance
        state.shipUI.droneHotkeyPressed(0, true)
        check(deployed?.isPowered == false) { "BrowserDiagnostics.kt:448" }
        val deployedParts = state.player.dronesCount
        state.shipUI.droneHotkeyPressed(0, false)
        check(deployed?.isPowered == true && drones.drones[0]!!.instance === deployed && state.player.dronesCount == deployedParts) { "Drone hotkey redeployed an existing drone" }
        fun key(k: Int, down: Boolean) = BrowserEngine.key(k, 0, down)
        fun droneKey(modifier: Int?) {
            if (modifier != null) key(modifier, true)
            key(xyz.znix.xftl.sys.Input.KEY_5, true)
            main.update(container, 0f)
            key(xyz.znix.xftl.sys.Input.KEY_5, false)
            if (modifier != null) key(modifier, false)
        }
        droneKey(xyz.znix.xftl.sys.Input.KEY_RSHIFT)
        check(deployed?.isPowered == false) { "Right Shift plus drone hotkey did not power down" }
        droneKey(null)
        check(deployed?.isPowered == true && state.player.dronesCount == deployedParts) { "Physical drone hotkey failed or consumed an extra part" }
        state.shipUI.showOptionsWindow()
        val autofireBefore = state.player.weapons!!.autofire
        key(xyz.znix.xftl.sys.Input.KEY_V, true); main.update(container, 0f); key(xyz.znix.xftl.sys.Input.KEY_V, false)
        check(state.player.weapons!!.autofire == autofireBefore) { "Autofire hotkey leaked through an open menu" }
        droneKey(xyz.znix.xftl.sys.Input.KEY_RSHIFT)
        check(deployed?.isPowered == true) { "Drone hotkey leaked through an open menu" }
        state.cancelMenu()
        var shield = drones.drones[0]!!.instance as xyz.znix.xftl.drones.ShieldDrone
        check(state.player.superShield == 0) { "BrowserDiagnostics.kt:472" }
        repeat(159) { shield.update(.05f) }
        check(state.player.superShield == 0) { "Shield charged before eight seconds" }
        repeat(3) { shield.update(.05f) }
        check(state.player.superShield == 1 && shield.rechargeRemaining > 9.8f) { "Shield first recharge: " + BrowserDebug.snapshot(main) }
        shield.update(2f)
        val charge = shield.rechargeRemaining
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        drones = state.player.drones!!
        shield = drones.drones[0]!!.instance as xyz.znix.xftl.drones.ShieldDrone
        check(kotlin.math.abs(shield.rechargeRemaining - charge) < .0001f && state.player.superShield == 1 && shield.isPowered) { "Shield save/restore: " + BrowserDebug.snapshot(main) }
        check(drones.setDronePower(0, false)) { "Shield power off: " + BrowserDebug.snapshot(main) }
        shield.update(30f)
        check(shield.rechargeRemaining == 10f && state.player.superShield == 1) { "Unpowered shield cooldown: " + BrowserDebug.snapshot(main) }
        check(drones.setDronePower(0, true)) { "Shield power on: " + BrowserDebug.snapshot(main) }
        for (cooldown in listOf(10f, 13f, 16f, 20f)) {
            val before = state.player.superShield
            repeat((cooldown * 20).toInt() + 2) { shield.update(.05f) }
            check(state.player.superShield == (before + 1).coerceAtMost(5)) { "Shield recharge $cooldown: " + BrowserDebug.snapshot(main) }
        }
        repeat(500) { shield.update(.05f) }
        check(state.player.superShield == 5) { "Shield drone exceeded the original shield cap" }
        equip(1, "BATTLE")
        var battle = drones.drones[1]!!.instance as xyz.znix.xftl.drones.AntiPersonnelDrone
        var pawn = battle.pawn!!
        check(pawn.maxHealth == 150f && pawn.health == 150f && pawn.attackDamageMult == 1.2f) { "BrowserDiagnostics.kt:498" }
        check(!pawn.canRepair && !pawn.canManSystem && !pawn.canPunch && !pawn.playerControllable && pawn.canFight) { "BrowserDiagnostics.kt:499" }
        val targetRoom = state.player.rooms.first { it != pawn.room && it.cellCount >= 2 }
        val intruder = (state.blueprintManager["rock"] as xyz.znix.xftl.crew.CrewBlueprint).spawn(targetRoom, xyz.znix.xftl.crew.AbstractCrew.SlotType.INTRUDER)
        intruder.jumpTo(state.player.findSpaceForCrew(targetRoom, intruder.mode))
        state.player.crew.add(intruder)
        state.player.rooms.forEach { it.update(0f) }
        repeat(40) { pawn.update(.05f) }
        check(pawn.pathingTarget?.room == targetRoom || pawn.room == targetRoom) { "Battle drone failed to pursue a boarder" }
        pawn.jumpTo(state.player.findSpaceForCrew(targetRoom, pawn.mode))
        state.player.rooms.forEach { it.update(0f) }
        // A crew laser deals its damage when it lands (0x4881c0), so the ship's crew shots fly too.
        repeat(140) {
            pawn.update(.05f)
            for (shot in state.player.crewShots.toList()) {
                if (!shot.update(.05f)) { state.player.crewShots.remove(shot); shot.onLanded?.invoke() }
            }
        }
        check(intruder.health < intruder.maxHealth) { "Battle drone did not fight a boarder" }
        check(drones.setDronePower(1, false)) { "BrowserDiagnostics.kt:511" }
        val health = intruder.health
        repeat(100) { pawn.update(.05f) }
        check(intruder.health == health) { "Unpowered battle drone kept fighting" }
        pawn.health = 73f
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        drones = state.player.drones!!
        battle = drones.drones[1]!!.instance as xyz.znix.xftl.drones.AntiPersonnelDrone
        check(battle.pawn!!.health == 73f && !battle.isPowered) { "BrowserDiagnostics.kt:520" }
        battle.destroy()
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        check(state.player.drones!!.drones[1]!!.cooldown == 10f) { "Drone destruction cooldown was lost on save" }
        val repair = xyz.znix.xftl.drones.RepairDrone(state.blueprintManager["REPAIR"] as xyz.znix.xftl.weapons.DroneBlueprint)
        repair.init(state.player)
        check(repair.pawn!!.maxHealth == 25f && repair.pawn!!.movementSpeed == 40f) { "BrowserDiagnostics.kt:527" }
        main.render(container, graphics)
        return "PASS shield cooldowns/cap/power/reset/save, physical drone hotkeys/right Shift/modal consumption, hacking-blueprint rejection without resource loss, anti-personnel deployment/pursuit/combat/power/save/destruction, repair-drone health/speed"
    }
    fun interactionProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        var state = fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        var weapons = player.weapons!!
        weapons.energyLevels = 8
        player.purchasedReactorPower = 25
        val first = player.hardpoints[0].weapon!!
        val second = player.hardpoints[1].weapon!!
        check(weapons.setWeaponPower(first, true)) { "Weapon assertion 1: " + BrowserDebug.snapshot(main) }
        check(weapons.setWeaponPower(second, true)) { "Weapon assertion 2: " + BrowserDebug.snapshot(main) }
        first.timeCharged = 3.5f
        first.chainCount = 2
        first.autofireOverride = true
        second.timeCharged = 1.5f
        val target = state.enemy!!.rooms.first()
        weapons.selectedTargets.targetRoom(0, target)
        check(weapons.swapSlots(0, 1)) { "Weapon assertion 3: " + BrowserDebug.snapshot(main) }
        check(player.hardpoints[1].weapon === first && player.hardpoints[0].weapon === second) { "Weapon assertion 4: " + BrowserDebug.snapshot(main) }
        check(first.timeCharged == 3.5f && first.chainCount == 2 && first.autofireOverride == true && first.isPowered) { "Weapon assertion 5: " + BrowserDebug.snapshot(main) }
        check(weapons.selectedTargets.getTarget(1)?.weapon === first && weapons.selectedTargets.getTarget(0) == null) { "Weapon assertion 6: " + BrowserDebug.snapshot(main) }
        check(weapons.swapSlots(1, 3)) { "Weapon assertion 7: " + BrowserDebug.snapshot(main) }
        check(player.hardpoints[1].weapon == null && player.hardpoints[3].weapon === first) { "Weapon assertion 8: " + BrowserDebug.snapshot(main) }
        check(weapons.selectedTargets.getTarget(3)?.weapon === first) { "Weapon assertion 9: " + BrowserDebug.snapshot(main) }
        check(!weapons.swapSlots(1, 2) && !weapons.swapSlots(3, 4) && !weapons.swapSlots(3, 3)) { "Weapon assertion 10: " + BrowserDebug.snapshot(main) }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        weapons = state.player.weapons!!
        val restored = state.player.hardpoints[3].weapon!!
        check(state.player.hardpoints[1].weapon == null && restored.type.name == first.type.name) { "Weapon assertion 11: " + BrowserDebug.snapshot(main) }
        check(restored.timeCharged == 3.5f && restored.chainCount == 2 && restored.autofireOverride == true) { "Weapon assertion 12: " + BrowserDebug.snapshot(main) }
        check(weapons.selectedTargets.getTarget(3)?.weapon === restored) { "Weapon assertion 13: " + BrowserDebug.snapshot(main) }
        // Duplicate blueprints must still keep their targets and charge with each instance.
        val duplicate = restored.type.buildInstance(state.player)
        state.player.hardpoints[1].weapon = duplicate
        state.player.cargoUpdated()
        check(weapons.setWeaponPower(duplicate, true)) { "Weapon assertion 14: " + BrowserDebug.snapshot(main) }
        duplicate.timeCharged = 2.25f
        weapons.selectedTargets.targetRoom(1, state.enemy!!.rooms.last())
        check(weapons.swapSlots(1, 3)) { "Weapon assertion 15: " + BrowserDebug.snapshot(main) }
        check(state.player.hardpoints[1].weapon === restored && state.player.hardpoints[3].weapon === duplicate) { "Weapon assertion 16: " + BrowserDebug.snapshot(main) }
        check(weapons.selectedTargets.getTarget(1)?.weapon === restored && weapons.selectedTargets.getTarget(3)?.weapon === duplicate) { "Weapon assertion 17: " + BrowserDebug.snapshot(main) }
        check(duplicate.timeCharged == 2.25f) { "Weapon assertion 18: " + BrowserDebug.snapshot(main) }
        weapons.setAutofire(true)
        check(restored.autofireOverride == null && duplicate.autofireOverride == null) { "Weapon assertion 19: " + BrowserDebug.snapshot(main) }
        restored.timeCharged = restored.chargeTime
        duplicate.timeCharged = 0f
        weapons.selectedTargets.fireChargedWeapons()
        check(restored.isFiring && weapons.selectedTargets.getTarget(1)?.weapon === restored) { "Weapon assertion 20: " + BrowserDebug.snapshot(main) }
        check(weapons.swapSlots(1, 2) && state.player.hardpoints[2].weapon === restored && restored.isFiring) { "Weapon assertion 21: " + BrowserDebug.snapshot(main) }
        weapons.setAutofire(false)
        check(weapons.selectedTargets.getTarget(2)?.weapon === restored) { "Toggle off lost the queued shot" }
        duplicate.timeCharged = duplicate.chargeTime
        weapons.selectedTargets.fireChargedWeapons()
        check(weapons.selectedTargets.getTarget(3) == null) { "One-shot target repeated" }
        weapons.onJump()
        check(weapons.selectedTargets.getTarget(2) == null) { "Weapon assertion 22: " + BrowserDebug.snapshot(main) }
        main.update(container, 0f)
        val bindingsBefore = main.profile.save()
        val modifierAction = state.hotkeyManager.byID.getValue(VanillaHotkeys.WEAPON_AUTOFIRE_TARGET)
        try {
            main.profile.resetHotkeyToDefault(modifierAction); state.updateHotkeyBindings()
            BrowserEngine.key(xyz.znix.xftl.sys.Input.KEY_RCTRL, 0, true)
            check(state.shipUI.isTargetingModifierDown()) { "BrowserDiagnostics.kt:595" }
            BrowserEngine.key(xyz.znix.xftl.sys.Input.KEY_RCTRL, 0, false)
            main.profile.setKeybind(modifierAction, HotkeyButton.BY_NAME.getValue("lalt")); state.updateHotkeyBindings()
            BrowserEngine.key(xyz.znix.xftl.sys.Input.KEY_LCTRL, 0, true)
            check(!state.shipUI.isTargetingModifierDown()) { "Remapped autofire modifier still used Ctrl" }
            BrowserEngine.key(xyz.znix.xftl.sys.Input.KEY_LCTRL, 0, false)
            BrowserEngine.key(xyz.znix.xftl.sys.Input.KEY_LALT, 0, true)
            check(state.shipUI.isTargetingModifierDown()) { "Remapped autofire modifier was ignored" }
            main.profile.unbindKey(modifierAction); state.updateHotkeyBindings()
            check(!state.shipUI.isTargetingModifierDown()) { "Unbound autofire modifier still applied" }
        } finally { BrowserEngine.resetInput(); main.profile.load(bindingsBefore); state.updateHotkeyBindings() }
        val rng = BrowserRandom.snapshot()
        val wasLogging = BrowserDebug.enabled
        BrowserDebug.enabled = true
        repeat(10) { BrowserDebug.record("diagnostic", "rng.check", BrowserDebug.snapshot(main)) }
        BrowserDebug.enabled = wasLogging
        check(BrowserRandom.snapshot() == rng) { "Logging consumed simulation RNG" }
        main.render(container, graphics)
        state = fresh(main, container)
        state.toggleQuickPause()
        val door = state.player.doors.first { it.isAirlock }
        for (drawRate in listOf(30, 60, 120, 240)) {
            door.loadSavedOpen(false)
            door.open = true
            repeat(drawRate / 10) {
                // Updates remain more frequent than drawings. The real door
                // must reach halfway open after the same 100 ms at every cap.
                repeat(4) { main.update(container, 1f / (drawRate * 4)) }
                BrowserEngine.renderFrame(1f / drawRate)
            }
            check(kotlin.math.abs(door.animationProgress - .5f) < .001f) {
                "Drawing at $drawRate changed door animation time: ${door.animationProgress}"
            }
        }
        door.loadSavedOpen(false)
        state.debugFlags.noDmg.set = false
        state.player.damage(state.player.engines!!.room!!, xyz.znix.xftl.weapons.Damage.hullAndSys(2))
        val damaged = BrowserXml.write(state.saveGameState())
        val damageRng = BrowserRandom.snapshot()
        for (drawRate in listOf(30, 60, 120, 240)) {
            repeat(drawRate / 10) { BrowserEngine.renderFrame(1f / drawRate) }
            check(BrowserRandom.snapshot() == damageRng && BrowserXml.write(state.saveGameState()) == damaged) {
                "Damage rendering at $drawRate FPS changed simulation state/RNG"
            }
        }
        state = fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        weapons = state.player.weapons!!
        val cancelled = state.player.hardpoints[0].weapon!!
        check(weapons.setWeaponPower(cancelled, true)) { "BrowserDiagnostics.kt:645" }
        cancelled.timeCharged = cancelled.chargeTime
        weapons.selectedTargets.targetRoom(0, state.enemy!!.rooms.first())
        weapons.selectedTargets.fireChargedWeapons()
        check(cancelled.isFiring) { "BrowserDiagnostics.kt:649" }
        check(BrowserXml.write(state.saveGameState()).contains("<waitingToFireAt")) { "Fixture missed the primed shot" }
        check(weapons.setWeaponPower(cancelled, false)) { "BrowserDiagnostics.kt:651" }
        state.player.update(0f)
        val cancelledSave = BrowserXml.write(state.saveGameState())
        check(!cancelled.isFiring && !cancelledSave.contains("<waitingToFireAt")) { "Depowering retained a primed shot" }
        main.loadSavedGame(BrowserXml.parse(cancelledSave))
        state = main.currentState as InGameState
        val restoredCancelled = state.player.hardpoints[0].weapon!!
        check(!restoredCancelled.isPowered && !restoredCancelled.isFiring) { "BrowserDiagnostics.kt:658" }
        check(state.player.weapons!!.setWeaponPower(restoredCancelled, true)) { "BrowserDiagnostics.kt:659" }
        state.player.update(0f)
        check(!restoredCancelled.isFiring && (restoredCancelled.browserEncounterVolleys ?: 0) == 0) { "Power/restore revived a cancelled shot" }
        return "PASS cancelled primed-shot cleanup across depower/restore/power, existing weapon instances, empty slots, invalid moves, duplicate target ownership, charge/chain/power/override preservation, firing swap, global autofire, remapped/unbound targeting modifier, one-shot target cleanup, jump cleanup, save/restore, logging RNG isolation, consistent door animation and damage/render RNG isolation at 30/60/120/240 FPS"
    }

    fun aeProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val hangarReport = BrowserHangarDiagnostics.run(main,container,graphics)
        val profileBefore = BrowserXml.write(main.profile.save())
        val runBefore = BrowserFiles.getRun()
        var credits = CreditsState(main, main::switchToMainMenu)
        main.setCurrentState(credits)
        check(credits.backerCount > 1000 && credits.lineCount > credits.backerCount) { "BrowserDiagnostics.kt:671" }
        main.render(container, graphics)
        // MainMenu: clicks don't touch the credits (0x004d47e0), any key leaves them (0x004d1900).
        credits.mouseClicked(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, 1180, 675, 1)
        check(main.currentState === credits) { "A click left the credits" }
        credits.keyPressed(xyz.znix.xftl.sys.Input.KEY_SPACE, ' ')
        check(main.currentState is MainMenuState) { "BrowserDiagnostics.kt:674" }
        credits = CreditsState(main, main::switchToMainMenu); main.setCurrentState(credits)
        main.update(container, 650f / 56f)
        check(kotlin.math.abs(credits.scroll + 720f) < .01f) { "BrowserDiagnostics.kt:677" }
        main.update(container, 2f)
        check(kotlin.math.abs(credits.scroll + 720f) < .01f) { "Credits skipped the native pause" }
        main.update(container, 2f)
        check(kotlin.math.abs(credits.scroll + 776f) < .01f) { "Credits failed to resume scrolling" }
        repeat((credits.endPosition / 560).toInt() + 2) { main.update(container, 10f); main.render(container, graphics) }
        check(main.currentState is MainMenuState) { "Completed credits did not return to the menu" }
        check(BrowserXml.write(main.profile.save()) == profileBefore && BrowserFiles.getRun() == runBefore) { "Credits altered ordinary progress" }
        main.switchToMainMenu()
        main.changeHangarMode(true, "PLAYER_SHIP_HARD_3", Difficulty.HARD)
        check((main.currentState as xyz.znix.xftl.hangar.SelectShipState).selectedShipId == "PLAYER_SHIP_HARD_3") { "BrowserDiagnostics.kt:687" }
        repeat(3) {
            main.changeHangarMode(false, "PLAYER_SHIP_HARD_3", Difficulty.HARD)
            val hangar = main.currentState as xyz.znix.xftl.hangar.SelectShipState
            check(!hangar.advancedEdition && hangar.selectedShipId == "PLAYER_SHIP_HARD") { "BrowserDiagnostics.kt:691" }
            check(main.gameContent.blueprintManager.blueprints["PLAYER_SHIP_HARD_3"] == null) { "BrowserDiagnostics.kt:692" }
            main.update(container, 0f); main.render(container, graphics)
            main.changeHangarMode(true, "PLAYER_SHIP_HARD_3", Difficulty.HARD)
            check(main.gameContent.blueprintManager.blueprints["PLAYER_SHIP_HARD_3"] != null) { "BrowserDiagnostics.kt:695" }
            main.update(container, 0f); main.render(container, graphics)
        }
        val state = fresh(main, container, "PLAYER_SHIP_HARD_3", Difficulty.HARD)
        val saved = BrowserXml.write(state.saveGameState())
        BrowserFiles.setRun(saved)
        main.changeHangarMode(false, "PLAYER_SHIP_HARD_3", Difficulty.NORMAL)
        main.continueSavedRun()
        check((main.currentState as InGameState).content.enableAdvancedEdition) { "Continue used the hangar mode" }
        check(!main.profile.lastAdvancedEdition) { "Continue overwrote the hangar preference" }
        val profile = SaveProfile.createBlank()
        profile.load(main.profile.save())
        check(!profile.lastAdvancedEdition) { "BrowserDiagnostics.kt:707" }
        main.changeHangarMode(false, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        fresh(main, container)
        check(!(main.currentState as InGameState).content.enableAdvancedEdition) { "BrowserDiagnostics.kt:710" }
        main.loadSavedGame(BrowserXml.parse(saved))
        check((main.currentState as InGameState).content.enableAdvancedEdition) { "BrowserDiagnostics.kt:712" }
        main.render(container, graphics)
        val eligibilityProfile = main.profile.save()
        try {
            main.profile.load(SaveProfile.createBlank().save())
            main.changeHangarMode(false, "PLAYER_SHIP_HARD_2", Difficulty.NORMAL)
            val locked = main.currentState as xyz.znix.xftl.hangar.SelectShipState
            check(!locked.isSelectedUnlocked) { "BrowserDiagnostics.kt:719" }
            locked.mouseClicked(0, 1170, 50, 1)
            check(main.currentState === locked) { "Locked Start accepted a click" }
            main.changeHangarMode(false, "PLAYER_SHIP_HARD", Difficulty.HARD)
            val quick = main.currentState as xyz.znix.xftl.hangar.SelectShipState
            // No render/mouse update: difficulty and Start must use current coordinates.
            quick.mouseClicked(2, 1170, 50, 1)
            quick.mouseClicked(0, 1170, 130, 1)
            check(main.currentState === quick) { "Off-target/right-click activated Start" }
            quick.mouseClicked(0, 1050, 26, 1)
            quick.mouseClicked(0, 1170, 50, 1)
            val launched = main.currentState as? InGameState
            check(launched != null && launched.difficulty == Difficulty.EASY) { "Quick difficulty/Start required a draw" }
        } finally { main.profile.load(eligibilityProfile) }
        return "$hangarReport; repeated hangar AE switching, unavailable layout fallback, mode-specific blueprints, saved-run mode for Continue/restore, separate persistent hangar preference, quick difficulty/Start click without a draw, locked/off-target/right-click rejection and new runs in both modes"
    }
    private fun dismiss(main: MainGame, container: GameContainer) {
        if ((main.currentState as InGameState).shipUI.isWindowOpen) {
            BrowserEngine.key(49, '1'.code, true)
            BrowserEngine.step(0f)
            BrowserEngine.key(49, '1'.code, false)
            BrowserEngine.step(0f)
        }
    }
    private fun fresh(main: MainGame, container: GameContainer, ship: String = "PLAYER_SHIP_HARD", difficulty: Difficulty = Difficulty.NORMAL): InGameState {
        main.startDiagnosticGame(ship, difficulty)
        repeat(90) { main.update(container, .05f); dismiss(main, container) }
        val state = main.currentState as InGameState
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        return state
    }
    fun encounterList(main: MainGame): String = main.gameContent.eventManager.getShips().map { it.name }.sorted().joinToString("\n")
    fun encounterProbe(main: MainGame, name: String, sector: Int, container: GameContainer, graphics: Graphics): String {
        var state = fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip(name), Difficulty.NORMAL, sector, 537)
        state.debugFlags.noDmg.set = true
        state.debugFlags.noCrewDamage.set = true
        var maxProjectiles = 0
        repeat(800) { index ->
            main.update(container, .05f)
            maxProjectiles = maxOf(maxProjectiles, state.player.projectiles.size + (state.enemy?.projectiles?.size ?: 0))
            if (index % 100 == 0) main.render(container, graphics)
            if (index == 499) {
                val saved = BrowserXml.write(state.saveGameState())
                main.loadSavedGame(BrowserXml.parse(saved))
                state = main.currentState as InGameState
                state.debugFlags.noDmg.set = true
                state.debugFlags.noCrewDamage.set = true
                dismiss(main, container)
                if (state.isPaused) state.toggleQuickPause()
            }
        }
        return "PASS encounter=$name sector=${sector+1} projectiles=$maxProjectiles 40s/save/render"
    }
    fun mechanicsProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        var state = fresh(main, container, "PLAYER_SHIP_CRYSTAL")
        val crystal = state.player.crew.filterIsInstance<xyz.znix.xftl.crew.CrewCrystal>().first()
        // A new ship's crystals start uncharged (no start-up simulation), as in the original; a jump charges them.
        crystal.resetLockdown()
        check(crystal.activateLockdown()) { "BrowserDiagnostics.kt:776" }
        check(crystal.room.doors.all { it.lockdownTimer == 12f }) { "BrowserDiagnostics.kt:777" }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        val restored = state.player.crew.filterIsInstance<xyz.znix.xftl.crew.CrewCrystal>().first()
        check(restored.lockdownCooldown == 50f) { "BrowserDiagnostics.kt:781" }
        check(restored.room.doors.all { it.lockdownTimer == 12f }) { "BrowserDiagnostics.kt:782" }
        for (door in restored.room.doors) door.update(12f)
        check(restored.room.doors.all { it.lockdownTimer == 0f }) { "BrowserDiagnostics.kt:784" }
        restored.resetLockdown()
        check(restored.lockdownCooldown == 0f) { "BrowserDiagnostics.kt:786" }
        val weapon = state.blueprintManager["LASER_BURST_3"] as xyz.znix.xftl.weapons.AbstractWeaponBlueprint
        while (state.player.cargoBlueprints.take(4).any { it == null }) state.player.addBlueprint(weapon, true)
        while (state.player.hardpoints.take(state.player.weaponSlots!!).any { it.weapon == null }) state.player.addBlueprint(weapon, true)
        state.player.addBlueprint(weapon, true)
        check(state.player.cargoBlueprints[4] == weapon) { "BrowserDiagnostics.kt:791" }
        val overflowSave = BrowserXml.write(state.saveGameState())
        main.loadSavedGame(BrowserXml.parse(overflowSave))
        state = main.currentState as InGameState
        check(state.player.cargoBlueprints[4]?.name == weapon.name) { "BrowserDiagnostics.kt:795" }
        state.player.resetAfterJump()
        check(state.player.cargoBlueprints.drop(4).all { it == null }) { "BrowserDiagnostics.kt:797" }
        repeat(10) { state.player.engines!!.decreasePower() }
        state.player.superShield = 5
        val room = state.player.rooms.first()
        val asb = xyz.znix.xftl.environment.AsbProjectile.hit(room, state.player, room.pixelCentre)
        val beforeHull = state.player.health
        asb.update(.05f, state.player)
        check(state.player.health == beforeHull - 3) { "ASB hull damage" }
        check(state.player.superShield == 5) { "ASB changed Zoltan shields" }
        check(room.breaches.any { it != null }) { "ASB breach" }
        main.render(container, graphics)
        state = fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        val drones = state.player.drones!!
        for (slot in drones.drones.indices) drones.setDronePower(slot, false)
        drones.energyLevels = 3
        drones.drones[0] = xyz.znix.xftl.systems.Drones.DroneInfo(state.blueprintManager["BOARDER_ION"] as xyz.znix.xftl.weapons.DroneBlueprint)
        check(drones.setDronePower(0, true)) { "Ion intruder would not launch" }
        var ionPulse = false
        repeat(600) {
            state.updateGameState(.05f)
            if (state.enemy?.systems?.any { it.ionTimer > 0f } == true) ionPulse = true
        }
        val pawn = (drones.drones[0]!!.instance as xyz.znix.xftl.drones.BoardingDrone).pawn!!
        check(pawn.maxHealth == 125f && !pawn.canFight && pawn.canSabotage) { "BrowserDiagnostics.kt:824" }
        check(ionPulse) { "Ion intruder did not ionize a system" }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        check((state.player.drones!!.drones[0]!!.instance as xyz.znix.xftl.drones.BoardingDrone).pawn!!.maxHealth == 125f) { "BrowserDiagnostics.kt:828" }
        main.render(container, graphics)
        state.shipUI.showGameOverScreen(GameOverWindow.Outcome.LOOSE_HULL)
        BrowserEngine.save()
        check(BrowserFiles.getRun() == null) { "Autosave revived an ended run" }
        return "PASS Crystal lockdown/save/cooldown, overflow/save/jump, ASB damage/breach/shield bypass, ion intruder/boarding/save, ended-run save deletion"
    }
    fun progressProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        var state = fresh(main, container)
        check(state.browserProgress.beaconsVisited == 1 && state.browserProgress.score == 12) {
            "Initial history beacons=${state.browserProgress.beaconsVisited} scrap=${state.browserProgress.scrapCollected} score=${state.browserProgress.score}"
        }
        val rewards = ResourceSet().also { it.scrap = 100 }
        state.player.addBlueprint(state.blueprintManager["SCRAP_COLLECTOR"] as xyz.znix.xftl.augments.AugmentBlueprint, true)
        val inventoryBefore = state.player.scrap
        state.givePlayerResources(rewards)
        check(state.player.scrap - inventoryBefore == 110) { "BrowserDiagnostics.kt:844" }
        check(state.browserProgress.scrapCollected == 100 && state.browserProgress.score == 137) { "BrowserDiagnostics.kt:845" }
        state.player.addBlueprint(state.blueprintManager["REPAIR_ARM"] as xyz.znix.xftl.augments.AugmentBlueprint, true)
        state.player.health -= 5
        state.givePlayerResources(rewards)
        check(state.browserProgress.scrapCollected == 200) { "Repair arm changed score scrap" }
        state.givePlayerResources(ResourceSet().also { it.scrap = -10 })
        state.player.scrap += 30 // The store-sale path changes inventory directly.
        check(state.browserProgress.scrapCollected == 200) { "BrowserDiagnostics.kt:852" }
        val neighbour = state.currentBeacon.neighbours.first()
        state.currentBeacon = neighbour
        val visits = state.browserProgress.beaconsVisited
        state.currentBeacon = neighbour
        check(state.browserProgress.beaconsVisited == visits && visits == 2) { "BrowserDiagnostics.kt:857" }
        state = fresh(main, container)
        // The pirate pool also contains automated scouts, which cannot be crew-killed.
        var seed = 71
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, seed++)
            check(seed < 100) { "Could not find a crewed pirate fixture" }
        } while (state.enemy!!.isAutoScout || state.enemy!!.clonebay != null)
        check(state.enemy!!.crew.isNotEmpty()) { "Crew-kill fixture has no crew" }
        state.debugFlags.noDmg.set = true
        state.debugFlags.noCrewDamage.set = true
        state.enemy!!.crew.toList().forEach { it.removeFromShip() }
        state.checkCrewKillDefeat()
        check(state.browserProgress.shipsDefeated == 1) { "Crew kill not counted" }
        state.enemy!!.health = 0
        repeat(120) { state.updateGameState(.05f) }
        check(state.browserProgress.shipsDefeated == 1) { "Crew kill counted again after hull destruction" }
        dismiss(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 537)
        state.enemy!!.health = 0
        repeat(120) { state.updateGameState(.05f) }
        check(state.browserProgress.shipsDefeated == 2) { "Hull kill not counted" }
        val before = state.browserProgress.snapshot()
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        check(state.browserProgress.snapshot() == before) { "Run history changed after restoring" }
        val legacy = state.saveGameState().also { it.rootElement.removeChild("browserProgress") }
        main.loadSavedGame(legacy)
        check(!(main.currentState as InGameState).browserProgress.historyComplete) { "BrowserDiagnostics.kt:885" }
        val gamesBefore = main.profile.browserGamesPlayed
        (main.currentState as InGameState).shipUI.showGameOverScreen(GameOverWindow.Outcome.LOOSE_HULL)
        check(main.profile.browserGamesPlayed == gamesBefore) { "Finishing an existing run counted another new game" }
        check(main.profile.browserHighScores().isEmpty()) { "Legacy partial history entered high scores" }
        state = fresh(main, container, difficulty = Difficulty.HARD)
        state.givePlayerResources(ResourceSet().also { it.scrap = 101 })
        check(state.browserProgress.score == 166) { "Score did not floor the hard multiplier" }
        state.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
        state.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
        check(main.profile.browserGamesPlayed == gamesBefore + 1) { "New game or repeated completion counted incorrectly" }
        check(main.profile.browserHighScores().single().score == 166) { "BrowserDiagnostics.kt:896" }
        val normalWin = state.content.achievements["ACH_WIN_NORMAL"]
        main.profile.unlockAchievement(normalWin, Difficulty.EASY)
        check(main.profile.getAchievement(normalWin)!!.difficulty == Difficulty.HARD) { "BrowserDiagnostics.kt:899" }
        val loaded = SaveProfile.createBlank().also { it.load(BrowserXml.parse(BrowserXml.write(main.profile.save()))) }
        check(loaded.browserGamesPlayed == main.profile.browserGamesPlayed && loaded.browserVictories == 1) { "BrowserDiagnostics.kt:901" }
        check(loaded.browserHighScores() == main.profile.browserHighScores()) { "BrowserDiagnostics.kt:902" }
        check(loaded.getAchievement(normalWin)!!.difficulty == Difficulty.HARD) { "BrowserDiagnostics.kt:903" }
        val repeated = SaveProfile.createBlank()
        repeat(12) { repeated.recordBrowserRun(main.profile.browserLastRun!!) }
        check(repeated.browserHighScores().size == 5 && repeated.save().rootElement.getChild("browserStats").getChildren("score").size == 5) { "BrowserDiagnostics.kt:906" }
        check(repeated.browserHighScores(state.player.type.name).size == 4) { "Native per-layout retention is four scores" }
        val stats = BrowserStatisticsState(main, state)
        main.setCurrentState(stats)
        main.render(container, graphics)
        stats.close()
        check(main.currentState === state) { "BrowserDiagnostics.kt:912" }
        BrowserCrewRecordDiagnostics.run(main, container, graphics)
        BrowserRunStatisticsDiagnostics.run(main, container, graphics)
        return "PASS score/arm exemptions/unique beacons, hull and crew kills without double-counting, run/profile round trips, partial-save exclusion, top five retention, milestone difficulty, ended-run idempotence; crew repair/kill/evasion/jump/skill records, ties/rename/ownership/cloning/legacy history and statistics tabs/back"
    }
    /** A traversal and transition stress test, with invulnerability; not a normal played victory. */
    fun campaignProbe(main: MainGame, container: GameContainer, graphics: Graphics, difficulty: Int, seed: Int): String {
        BrowserRandom.restore(seed.toString())
        var state = fresh(main, container, difficulty = Difficulty.values()[difficulty])
        var jumps = 0
        for (column in 0..7) {
            state.debugFlags.noDmg.set = true
            state.debugFlags.noCrewDamage.set = true
            val sector = state.currentBeacon.sector
            check(sector.sectorNumber == column) { "Expected sector ${column+1}, found ${sector.sectorNumber+1}" }
            // The final-sector countdown is exercised independently by the
            // combat probe. This phase fixture starts at the flagship after
            // reaching sector eight, rather than following a fixed route that
            // can legitimately lose the base before it reaches the flagship.
            val path = if (column == 7) emptyList() else sector.findShortestPath(state.currentBeacon, sector.finishBeacon)!!
            for (beacon in path) {
                state.advanceFleet()
                state.beginJumpOut(beacon)
                repeat(90) { main.update(container, .05f); dismiss(main, container) }
                main.render(container, graphics)
                check(state.currentBeacon == beacon) { "Jump failed in sector ${column + 1}: outcome=${state.browserProgress.outcome}" }
                jumps++
            }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            state = main.currentState as InGameState
            dismiss(main, container)
            if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
            if (column < 7) {
                val next = state.currentBeacon.sector.info.nextSectors.first()
                val generated = state.gameMap.generateSector(next, state)
                state.beginJumpOut(generated.startBeacon)
                repeat(90) { main.update(container, .05f); dismiss(main, container) }
            }
        }
        var boss = state.currentBeacon.sector.bosses.first() as FlagshipBoss
        for (phase in 1..3) {
            state.currentBeacon = boss.beacon
            repeat(90) { main.update(container, .05f); dismiss(main, container) }
            state.debugFlags.noDmg.set = true
            state.debugFlags.noCrewDamage.set = true
            val enemy = state.enemy!!
            check(enemy.type.name.startsWith("BOSS_${phase}_")) { "Expected flagship phase $phase, found ${enemy.type.name}" }
            repeat(800) { state.updateGameState(.05f); if (it % 100 == 0) main.render(container, graphics) }
            enemy.health = 0
            repeat(120) { state.updateGameState(.05f) }
            if (phase < 3) {
                dismiss(main,container)
                main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
                state = main.currentState as InGameState
                state.debugFlags.noDmg.set = true; state.debugFlags.noCrewDamage.set = true
                boss = state.currentBeacon.sector.bosses.first() as FlagshipBoss
                boss.advanceFleet()
            }
        }
        check(state.browserRunEnded && state.browserProgress.outcome == GameOverWindow.Outcome.WIN) { "Flagship did not reach victory" }
        for (id in listOf("ACH_SECTOR_5", "ACH_SECTOR_8", "ACH_WIN_EASY"))
            check(main.profile.getAchievement(state.content.achievements[id]) != null) { "Missing milestone $id" }
        if (state.difficulty != Difficulty.EASY) check(main.profile.getAchievement(state.content.achievements["ACH_WIN_NORMAL"]) != null) { "BrowserDiagnostics.kt:974" }
        check(state.browserProgress.historyComplete && state.browserProgress.shipsDefeated >= 3) { "BrowserDiagnostics.kt:975" }
        BrowserEngine.save()
        check(BrowserFiles.getRun() == null) { "Victory left an active run save" }
        return "PASS traversal ${Difficulty.values()[difficulty]} seed=$seed AE=${state.content.enableAdvancedEdition}: 8 sectors, $jumps beacon jumps/save round trips, 3 flagship phases/transition/victory; invulnerability enabled"
    }
    fun combatProbe(main: MainGame, container: GameContainer, graphics: Graphics): String {
        BrowserEngine.diagnosticProgress("Combat: targeting")
        var state = fresh(main, container)
        val routeSector = state.currentBeacon.sector
        val routeNodes = routeSector.beacons.take(5).map { xyz.znix.xftl.sector.Beacon(it.pos, it.event, false, false, 71) }
        run {
            val (a, b, c, d, e) = routeNodes
            a.bindSector(routeSector, listOf(b, d))
            b.bindSector(routeSector, listOf(a, c))
            c.bindSector(routeSector, listOf(b, d))
            d.bindSector(routeSector, listOf(a, c))
            e.bindSector(routeSector, emptyList())
            check(routeSector.findShortestPath(a, c) == listOf(b, c)) { "BrowserDiagnostics.kt:992" }
            check(routeSector.findShortestPath(a, a) == emptyList<xyz.znix.xftl.sector.Beacon>()) { "BrowserDiagnostics.kt:993" }
            check(routeSector.findShortestPath(a, e) == null) { "BrowserDiagnostics.kt:994" }
            check(routeSector.findShortestPath(e, a) == null) { "BrowserDiagnostics.kt:995" }
        }
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        // The enemy targeting rules are checked in GameplayParityEnemyAiChecks.
        val xml = BrowserXml.write(state.saveGameState())
        main.loadSavedGame(BrowserXml.parse(xml)); state = main.currentState as InGameState
        BrowserEngine.diagnosticProgress("Combat: final-sector generation")
        val finalSector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        state.currentBeacon = finalSector.startBeacon
        dismiss(main, container)
        var boss = finalSector.bosses.first() as FlagshipBoss
        val doomed = finalSector.beacons.first { !it.isBase && it != boss.beacon && it != boss.nextBeacon }
        doomed.hasStore = true; doomed.hasQuest = true; doomed.browserFleetThreatened = true
        BrowserEngine.diagnosticProgress("Combat: fleet and restoration")
        state.advanceFleet()
        check(doomed.isOvertaken && !doomed.browserFleetThreatened && !doomed.hasStore && !doomed.hasQuest) { "BrowserDiagnostics.kt:1010" }
        check(finalSector.beacons.any { it.browserFleetThreatened }) { "No next-turn fleet warnings" }
        check(!finalSector.finishBeacon.isOvertaken && !finalSector.finishBeacon.browserFleetThreatened) { "BrowserDiagnostics.kt:1012" }
        val doomedIndex = finalSector.beacons.indexOf(doomed)
        val warned = finalSector.beacons.map { it.browserFleetThreatened }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        val restoredSector = state.currentBeacon.sector
        check(restoredSector.beacons[doomedIndex].isOvertaken) { "BrowserDiagnostics.kt:1018" }
        check(restoredSector.beacons.map { it.browserFleetThreatened } == warned) { "Fleet warnings were lost" }
        BrowserEngine.diagnosticProgress("Combat: base countdown")
        boss = restoredSector.bosses.first() as FlagshipBoss
        val base = restoredSector.finishBeacon
        boss.debugTeleportTo(base)
        boss.advanceFleet()
        check(boss.browserBaseTurns == 3) { "BrowserDiagnostics.kt:1025" }
        boss.advanceFleet(); check(boss.browserBaseTurns == 2) { "BrowserDiagnostics.kt:1026" }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        boss = state.currentBeacon.sector.bosses.first() as FlagshipBoss
        check(boss.browserBaseTurns == 2) { "Base countdown was lost" }
        boss.advanceFleet(); check(boss.browserBaseTurns == 1) { "BrowserDiagnostics.kt:1031" }
        boss.advanceFleet(); check(boss.browserBaseTurns == 0) { "BrowserDiagnostics.kt:1032" }
        check(!BrowserLastStand.checkDefeat(state,boss.beacon)) { "Reaching the base must allow combat" }
        boss.debugTeleportTo(state.currentBeacon)
        boss.advanceFleet()
        check(boss.browserBaseTurns == null) { "Base countdown did not reset after departure" }
        boss.debugTeleportTo(state.currentBeacon.sector.finishBeacon)
        repeat(4) { boss.advanceFleet() }
        check(BrowserLastStand.checkDefeat(state,state.currentBeacon)) { "Base destruction did not end the run" }
        check(state.browserProgress.outcome == GameOverWindow.Outcome.LOOSE_BASE_DESTROYED) { "BrowserDiagnostics.kt:1040" }
        BrowserEngine.save(); check(BrowserFiles.getRun() == null) { "BrowserDiagnostics.kt:1041" }
        // A queued hack must not launch towards a ship removed before the next tick.
        BrowserEngine.diagnosticProgress("Combat: hacking cleanup")
        state = fresh(main, container, "PLAYER_SHIP_CIRCLE_3")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val hacking = state.player.hacking!!
        hacking.increasePower()
        state.player.update(0f)
        val droneParts = state.player.dronesCount
        hacking.selectTarget(state.enemy!!.weapons!!.room!!)
        state.spawnVanillaEnemy("browser-diagnostics-empty",71)
        hacking.update(0f)
        check(!hacking.droneLaunched && state.player.dronesCount == droneParts) { "Queued hack targeted a departed ship" }
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        hacking.selectTarget(state.enemy!!.weapons!!.room!!)
        repeat(300) { state.updateGameState(.05f) }
        check(hacking.droneLanded) { "Hacking fixture did not land" }
        hacking.startHackingPulse()
        check(hacking.active) { "BrowserDiagnostics.kt:1060" }
        state.spawnVanillaEnemy("browser-diagnostics-empty",71)
        check(!hacking.active && !hacking.droneLaunched && hacking.ionTimer >= 20f) { "Detached active hack lost cooldown" }
        BrowserXml.write(state.saveGameState())
        // Waiting must preserve a friendly ship and charge, but spawn capture at this beacon.
        BrowserEngine.diagnosticProgress("Combat: stationary waiting")
        state = fresh(main, container)
        val waitSector = state.gameMap.generateSector(state.gameMap.sectors[7].first(),state)
        state.currentBeacon = waitSector.startBeacon
        repeat(90) { main.update(container,.05f); dismiss(main,container) }
        val friendlyEvent = xyz.znix.xftl.sector.Event(null, emptyList(),
            org.jdom2.Element("event").also { it.addContent(org.jdom2.Element("ship").setAttribute("load","PIRATE").setAttribute("hostile","false")) },
            "browser-neutral", "browser-neutral", { error("No images in this fixture") }, { error("No text in this fixture") })
        state.loadEventShip(friendlyEvent)
        val friendlyShip = state.enemy!!
        val waitFuel = state.player.fuelCount
        val waitVisits = state.browserProgress.beaconsVisited
        state.player.ftlChargeProgress = .5f
        BrowserLastStand.waitAtBeacon(state)
        check(state.enemy === friendlyShip && state.browserCanWait()) { "Waiting replaced or attacked a friendly ship" }
        check(state.player.fuelCount == waitFuel && state.player.ftlChargeProgress == .5f && state.browserProgress.beaconsVisited == waitVisits) { "Stationary wait reset player state" }
        state.currentBeacon.browserFleetThreatened = true
        BrowserLastStand.waitAtBeacon(state)
        check(state.currentBeacon.isOvertaken && state.enemy != null && state.enemy !== friendlyShip && !state.browserCanWait()) { "Capture during wait did not spawn the fleet encounter" }
        dismiss(main,container)
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        check(state.currentBeacon.isOvertaken && state.enemy != null && !state.browserCanWait()) { "Captured wait encounter was lost on restore" }
        val blockedWarnings = state.currentBeacon.sector.beacons.map { it.browserFleetThreatened }
        BrowserLastStand.waitAtBeacon(state)
        check(state.currentBeacon.sector.beacons.map { it.browserFleetThreatened } == blockedWarnings) { "Hostile combat allowed waiting" }
        state.spawnVanillaEnemy("browser-diagnostics-empty",71)
        boss = state.currentBeacon.sector.bosses.first() as FlagshipBoss
        boss.debugTeleportTo(state.currentBeacon)
        BrowserLastStand.waitAtBeacon(state)
        check(state.enemy?.boss === boss && !state.browserCanWait()) { "Flagship arrival during wait was not spawned" }
        // Leave a safe final-sector map open for the visible Wait-button check.
        BrowserEngine.diagnosticProgress("Combat: jump-map preview")
        state = fresh(main, container)
        val previewSector = state.gameMap.generateSector(state.gameMap.sectors[7].first(),state)
        val previewBoss = previewSector.bosses.single() as FlagshipBoss
        check(previewBoss.beacon.gridPos.x >= 4 && previewSector.findShortestPath(previewBoss.beacon, previewSector.finishBeacon) != null) {
            "Final-sector flagship cannot reach the base"
        }
        state.currentBeacon = previewSector.startBeacon
        repeat(90) { main.update(container,.05f); dismiss(main,container) }
        state.spawnVanillaEnemy("browser-diagnostics-empty",71)
        state.shipUI.openJumpMap()
        main.render(container,graphics)
        return "PASS cyclic/disconnected/identity shortest paths, reachable flagship placement; targeting Easy20/Normal33/Hard25+25, priority thresholds, unique volleys/RNG save; final-sector fleet warnings/capture/save, base 3/2/1 countdown/save/reset/reaching-base exception/defeat; friendly wait/charge preservation, capture and flagship wait/save, hostile wait guard; hacking queued target/active cooldown cleanup, ended-run deletion"
    }
    fun performancePreset(main: MainGame, container: GameContainer, phase: Int) {
        val state = fresh(main,container,difficulty=Difficulty.HARD)
        state.debugFlags.noDmg.set = true
        state.debugFlags.noCrewDamage.set = true
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(),state)
        val boss = sector.bosses.first() as FlagshipBoss
        boss.debugTeleportTo(sector.finishBeacon)
        for (stage in 1..phase) {
            state.currentBeacon = sector.finishBeacon
            repeat(90) { main.update(container,.05f); dismiss(main,container) }
            if (stage < phase) {
                state.enemy!!.health = 0
                repeat(120) { state.updateGameState(.05f); dismiss(main,container) }
                boss.advanceFleet(); boss.debugTeleportTo(sector.finishBeacon)
            }
        }
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
    }
    fun shipList(main: MainGame): String = main.gameContent.blueprintManager.blueprints.values
        .filterIsInstance<LazyShipBlueprint>().filter { it.name.startsWith("PLAYER_SHIP_") && it.name != "PLAYER_SHIP_EASY" && it.name != "PLAYER_SHIP_TUTORIAL" }
        .map { it.name }.sorted().joinToString("\n")

    fun probe(main: MainGame, ship: String, difficulty: Int, container: GameContainer, graphics: Graphics): String {
        main.startDiagnosticGame(ship, Difficulty.values()[difficulty])
        main.update(container, 0f)
        main.render(container, graphics)
        val before = main.currentState as InGameState
        val crewCount = before.player.crew.size
        val hull = before.player.health
        val weapons = before.player.hardpoints.map { it.weapon?.type?.name }
        val doc = before.saveGameState()
        val xml = BrowserXml.write(doc)
        val future = List(8) { BrowserRandom.nextInt() }
        main.loadSavedGame(BrowserXml.parse(xml))
        check(List(8) { BrowserRandom.nextInt() } == future) { "Random state was not restored" }
        val after = main.currentState as InGameState
        check(after.player.crew.size == crewCount) { "Crew count changed" }
        check(after.player.health == hull) { "Hull changed" }
        check(after.player.hardpoints.map { it.weapon?.type?.name } == weapons) { "Weapons changed" }
        main.update(container, 0f)
        main.render(container, graphics)
        val crew = after.player.crew.firstOrNull()
        if (crew != null) {
            crew.stun(2f)
            val stunnedXml = BrowserXml.write(after.saveGameState())
            main.loadSavedGame(BrowserXml.parse(stunnedXml))
            check((main.currentState as InGameState).player.crew.first().stunTime == 2f) { "Stun state was not restored" }
        }
        return "PASS $ship ${Difficulty.values()[difficulty]} crew=$crewCount xml=${xml.length} RNG/save/render"
    }
}
