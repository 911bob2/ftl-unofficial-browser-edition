package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.SelectedTarget
import xyz.znix.xftl.weapons.*

/** Impact/beam fixtures execute normal evasion, damage, teleport and save paths. */
object BrowserImpactAchievementDiagnostics {
    fun run(main: MainGame, container: GameContainer) {
        val profile = main.profile
        fun earned(id: String) = profile.getAchievement(main.gameContent.achievements[id]) != null
        fun reset(ship: String = "PLAYER_SHIP_HARD"): InGameState {
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
                check(seed < 180) { "BrowserImpactAchievementDiagnostics.kt:33" }
            } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated || state.enemy!!.rooms.none { it.cellCount >= 4 } ||
                state.enemy!!.getAugmentValue("ROCK_ARMOR") > 0f)
            return state.enemy!!
        }
        fun reload(state: InGameState): InGameState {
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            return (main.currentState as InGameState).also { it.debugFlags.noEnemyFire.set = true }
        }
        fun fullEngines(state: InGameState) {
            state.player.systems.filterIsInstance<xyz.znix.xftl.systems.MainSystem>().forEach { system ->
                repeat(8) { system.decreasePower() }
            }
            state.player.engines!!.energyLevels = 8
            state.player.piloting!!.energyLevels = 3
            state.player.purchasedReactorPower = 25
            state.player.updateAvailablePower()
            repeat(8) { state.player.engines!!.increasePower() }
            check(state.player.engines!!.powerSupplied == 8) { "BrowserImpactAchievementDiagnostics.kt:51" }
        }
        fun cloak(state: InGameState) {
            state.player.cloaking!!.let {
                if (it.powerSelected == 0) it.increasePower()
                it.activateCloak()
                check(it.active) { "BrowserImpactAchievementDiagnostics.kt:57" }
            }
        }
        fun laserMiss(state: InGameState, name: String = "LASER_HEAVY_2") {
            val laser = (state.blueprintManager[name] as LaserBlueprint).LaserProjectile(state.player.rooms.first())
            laser.setInitialPath(laser.target.pixelCentre, laser.target.pixelCentre)
            // Deterministic first evasion sample, without modifying weapon blueprints.
            BrowserRandom.restore("0")
            laser.update(.05f, state.player)
        }
        fun beam(state: InGameState, room: Room, name: String = "BEAM_BIO", slot: Int? = null): BeamBlueprint.BeamInstance {
            val weapon = (state.blueprintManager[name] as BeamBlueprint).buildInstance(state.player) as BeamBlueprint.BeamInstance
            if (slot != null) {
                state.player.hardpoints[slot].weapon?.let { state.player.weapons!!.setWeaponPower(it, false) }
                state.player.hardpoints[slot].weapon = weapon
                weapon.bindToWeaponsSystem(state.player.weapons!!)
                state.player.weapons!!.energyLevels = 8
                state.player.purchasedReactorPower = 25
                state.player.updateAvailablePower()
                check(state.player.weapons!!.setWeaponPower(weapon, true)) { "BrowserImpactAchievementDiagnostics.kt:76" }
            } else weapon.forceSetPowered(true)
            weapon.timeCharged = weapon.chargeTime
            val aim = SelectedTarget.BeamAim(weapon, slot ?: -1, room.ship,
                ConstPoint(room.offsetX + 17, room.offsetY + 17))
            aim.angle = if (room.width > 1) 0f else (Math.PI / 2).toFloat()
            aim.length = ((if (room.width > 1) room.width else room.height) * 35 - 18).coerceAtMost((weapon.type as BeamBlueprint).length)
            aim.updateHitRooms()
            weapon.fire(aim)
            return weapon
        }
        fun stepBeam(state: InGameState, weapon: BeamBlueprint.BeamInstance) {
            state.browserAchievements.advanceTime(.05f)
            weapon.update(.05f, 0f, false)
        }
        fun finishBeam(state: InGameState, weapon: BeamBlueprint.BeamInstance) {
            repeat(180) { if (weapon.isFiring) stepBeam(state, weapon) }
            check(!weapon.isFiring) { "BrowserImpactAchievementDiagnostics.kt:93" }
        }
        fun clearShields(ship: Ship) {
            ship.superShield = 0
            ship.shields?.let { it.damagedEnergyLevels = it.energyLevels; it.activeShields = 0 }
        }
        fun crewOnBeam(state: InGameState, room: Room, count: Int): List<LivingCrew> {
            state.enemy!!.crew.toList().forEach { it.removeFromShip() }
            val crew = List(count) { state.enemy!!.addCrewMember(LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), false) }
            // A beam can encounter several overlapping moving crew in one cell.
            crew.forEach { it.jumpTo(room, ConstPoint.ZERO); it.health = 1f }
            room.updateCrewInRoom()
            return crew
        }
        fun board(state: InGameState, leaveHome: Int = 0) {
            val crew = state.player.crew.filterIsInstance<LivingCrew>().drop(leaveHome)
            crew.forEach { it.teleportAnimatedTo(state.enemy!!.rooms.first()) }
            repeat(40) { crew.forEach { it.update(.05f) } }
            check(crew.all { it.room.ship === state.enemy }) { "BrowserImpactAchievementDiagnostics.kt:111" }
        }
        fun crewDefeat(state: InGameState) {
            val enemy = state.enemy!!
            enemy.clonebay?.let { it.damagedEnergyLevels = it.energyLevels }
            enemy.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === enemy }.toList().forEach { it.removeFromShip() }
            state.checkCrewKillDefeat()
        }

        println("TEST achievement dodge streaks")
        var state = reset(); spawn(state); fullEngines(state)
        repeat(4) { check(!state.player.pickMissed(-100)) { "BrowserImpactAchievementDiagnostics.kt:122" } }
        check(!earned("ACH_BAD_DODGING")) { "BrowserImpactAchievementDiagnostics.kt:123" }
        state = reload(state); check(!state.player.pickMissed(-100)) { "BrowserImpactAchievementDiagnostics.kt:124" }
        check(earned("ACH_BAD_DODGING")) { "Five failed dodges or saved streak did not earn" }
        state = reset(); spawn(state); fullEngines(state)
        repeat(4) { state.player.pickMissed(-100) }; check(state.player.pickMissed(100)) { "BrowserImpactAchievementDiagnostics.kt:127" }
        state = reload(state); state.player.pickMissed(-100); check(!earned("ACH_BAD_DODGING")) { "BrowserImpactAchievementDiagnostics.kt:128" }
        state = reset(); spawn(state); fullEngines(state)
        repeat(4) { state.player.pickMissed(-100) }
        state.player.engines!!.decreasePower(); state.player.pickMissed(-100)
        state.player.engines!!.increasePower(); state.player.pickMissed(-100)
        check(!earned("ACH_BAD_DODGING")) { "Underpowered engines did not reset the streak" }
        state = reset(); spawn(state); fullEngines(state)
        repeat(4) { state.player.pickMissed(-100) }
        state.player.piloting!!.damagedEnergyLevels = 3; state.player.pickMissed(-100)
        state.player.piloting!!.damagedEnergyLevels = 0; state.player.pickMissed(-100)
        check(!earned("ACH_BAD_DODGING")) { "Broken piloting did not reset the streak" }

        println("TEST achievement cloaked damage avoidance")
        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); cloak(state)
        repeat(4) { laserMiss(state) }
        check(!earned("ACH_STEALTH_AVOID")) { "Eight damage earned the nine-damage achievement" }
        state = reload(state); laserMiss(state)
        check(earned("ACH_STEALTH_AVOID")) { "Cloaked dodges across reload were lost or double counted" }
        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); cloak(state); repeat(4) { laserMiss(state) }
        state.player.cloaking!!.timeRemaining = null; state.browserAchievements.update()
        cloak(state); laserMiss(state); check(!earned("ACH_STEALTH_AVOID")) { "Damage from two cloaks was combined" }
        state = reset("PLAYER_SHIP_STEALTH"); spawn(state); repeat(5) { laserMiss(state) }
        cloak(state); laserMiss(state); check(!earned("ACH_STEALTH_AVOID")) { "Uncloaked misses counted as cloaked damage" }
        state = reset("PLAYER_SHIP_CRYSTAL_2"); spawn(state); cloak(state); repeat(5) { laserMiss(state) }
        check(!earned("ACH_STEALTH_AVOID")) { "BrowserImpactAchievementDiagnostics.kt:152" }

        println("TEST achievement beam room coverage")
        state = reset(); var enemy = spawn(state); clearShields(enemy)
        val missing = enemy.rooms.last()
        enemy.rooms.dropLast(1).forEach { state.browserAchievements.beamRoomHit(it) }
        check(!earned("ACH_SLICE_DICE")) { "BrowserImpactAchievementDiagnostics.kt:158" }
        state = reload(state); enemy = state.enemy!!
        finishBeam(state, beam(state, enemy.rooms.first { it.id == missing.id }))
        check(earned("ACH_SLICE_DICE")) { "Real beam did not complete saved room coverage" }
        state = reset(); enemy = spawn(state); clearShields(enemy)
        enemy.rooms.dropLast(1).forEach { state.browserAchievements.beamRoomHit(it) }
        state.browserAchievements.advanceTime(5f); state = reload(state)
        finishBeam(state, beam(state, state.enemy!!.rooms.last()))
        check(!earned("ACH_SLICE_DICE")) { "Five-second-old coverage remained eligible" }
        state = reset(); enemy = spawn(state)
        enemy.superShield = 5
        val guardedRoom = enemy.rooms.last()
        enemy.rooms.dropLast(1).forEach { state.browserAchievements.beamRoomHit(it) }
        finishBeam(state, beam(state, guardedRoom))
        check(!earned("ACH_SLICE_DICE")) { "Shield-blocked beam marked a room hit" }
        state.player.resetAfterJump(); spawn(state)
        state.browserAchievements.beamRoomHit(state.enemy!!.rooms.last())
        check(!earned("ACH_SLICE_DICE")) { "Previous encounter room coverage survived a target change" }

        println("TEST achievement single beam crew kills")
        state = reset("PLAYER_SHIP_JELLY"); enemy = spawn(state); clearShields(enemy)
        var room = enemy.rooms.first { it.cellCount >= 4 }; crewOnBeam(state, room, 3)
        var weapon = beam(state, room, slot = 0)
        finishBeam(state, weapon)
        check(earned("ACH_SLUG_BIO")) { "A real anti-bio volley did not earn three crew kills" }
        state = reset("PLAYER_SHIP_JELLY"); enemy = spawn(state); clearShields(enemy)
        room = enemy.rooms.first { it.cellCount >= 4 }
        val separated = crewOnBeam(state, room, 3)
        separated.last().jumpTo(room, if (room.width > 1) ConstPoint(1, 0) else ConstPoint(0, 1))
        room.updateCrewInRoom(); weapon = beam(state, room, slot = 0)
        repeat(180) { if (separated.count { it.health == 0f } < 2) stepBeam(state, weapon) }
        check(separated.count { it.health == 0f } == 2 && !earned("ACH_SLUG_BIO")) { "Fixture did not stop after two kills" }
        val legacyBeam = state.saveGameState()
        legacyBeam.rootElement.getChild("playerShip").getChild("weapons").getChildren("weapon")
            .forEach { it.removeChild("browserCrewKills") }
        state = reload(state)
        weapon = state.player.hardpoints[0].weapon as BeamBlueprint.BeamInstance
        finishBeam(state, weapon)
        check(earned("ACH_SLUG_BIO")) { "Active beam save lost its first two kills" }
        profile.deleteAchievement(main.gameContent.achievements["ACH_SLUG_BIO"])
        main.loadSavedGame(legacyBeam); state = main.currentState as InGameState
        finishBeam(state, state.player.hardpoints[0].weapon as BeamBlueprint.BeamInstance)
        check(!earned("ACH_SLUG_BIO")) { "Unknown legacy beam history invented earlier kills" }
        state = reset("PLAYER_SHIP_JELLY"); enemy = spawn(state); clearShields(enemy)
        room = enemy.rooms.first { it.cellCount >= 4 }; crewOnBeam(state, room, 2)
        finishBeam(state, beam(state, room, slot = 0)); check(!earned("ACH_SLUG_BIO")) { "BrowserImpactAchievementDiagnostics.kt:203" }
        crewOnBeam(state, room, 1); finishBeam(state, beam(state, room, slot = 0))
        check(!earned("ACH_SLUG_BIO")) { "Kills from separate beam shots were combined" }
        state = reset("PLAYER_SHIP_JELLY"); enemy = spawn(state); clearShields(enemy)
        room = enemy.rooms.first { it.cellCount >= 4 }; crewOnBeam(state, room, 3)
        finishBeam(state, beam(state, room, "BEAM_1", 0)); check(!earned("ACH_SLUG_BIO")) { "Hull-damaging beam earned Slug Bio" }
        state = reset(); enemy = spawn(state); clearShields(enemy)
        room = enemy.rooms.first { it.cellCount >= 4 }; crewOnBeam(state, room, 3)
        finishBeam(state, beam(state, room)); check(!earned("ACH_SLUG_BIO")) { "Wrong ship family earned Slug Bio" }

        println("TEST achievement Crystal shard impacts")
        state = reset("PLAYER_SHIP_CRYSTAL"); enemy = spawn(state)
        state.debugFlags.noDmg.set = false
        enemy.engines!!.damagedEnergyLevels = enemy.engines!!.energyLevels
        enemy.piloting!!.damagedEnergyLevels = enemy.piloting!!.energyLevels
        enemy.superShield = 0; enemy.health = 1
        enemy.projectiles.add(VengeanceShardProjectile(enemy.rooms.first()).also {
            it.setInitialPath(it.target.pixelCentre, it.target.pixelCentre)
        })
        state = reload(state); enemy = state.enemy!!
        val shard = enemy.projectiles.filterIsInstance<VengeanceShardProjectile>().single()
        check(shard.position.xf.isFinite() && shard.position.yf.isFinite() && enemy.health == 1) {
            "Zero-time reload moved or resolved a pending projectile"
        }
        shard.update(.05f, enemy)
        check(earned("ACH_CRYSTAL_SHARD")) { "Restored Vengeance shard did not earn a killing blow: hull=${enemy.health}, evasion=${enemy.evasion}, superShield=${enemy.superShield}, noDmg=${state.debugFlags.noDmg.set}, armor=${enemy.getAugmentValue("HULL_ARMOR")}, advancedArmor=${enemy.getAugmentValue("ADV_HULL_ARMOR")}" }
        state = reset("PLAYER_SHIP_CRYSTAL"); enemy = spawn(state)
        state.debugFlags.noDmg.set = false
        enemy.engines!!.damagedEnergyLevels = enemy.engines!!.energyLevels
        enemy.piloting!!.damagedEnergyLevels = enemy.piloting!!.energyLevels
        enemy.health = 2
        VengeanceShardProjectile(enemy.rooms.first()).also {
            it.setInitialPath(it.target.pixelCentre, it.target.pixelCentre); it.update(.05f, enemy)
        }
        check(!earned("ACH_CRYSTAL_SHARD")) { "Nonlethal shard earned a kill" }
        enemy.health = 0; check(!earned("ACH_CRYSTAL_SHARD")) { "Unrelated hull destruction earned a shard kill" }
        state = reset(); enemy = spawn(state)
        enemy.engines!!.damagedEnergyLevels = enemy.engines!!.energyLevels
        enemy.piloting!!.damagedEnergyLevels = enemy.piloting!!.energyLevels
        enemy.superShield = 0; enemy.health = 1
        VengeanceShardProjectile(enemy.rooms.first()).also {
            it.setInitialPath(it.target.pixelCentre, it.target.pixelCentre); it.update(.05f, enemy)
        }
        check(enemy.health == 0 && !earned("ACH_CRYSTAL_SHARD")) { "Wrong family earned a shard achievement" }

        println("TEST achievement all crew aboard enemy")
        state = reset(); spawn(state); board(state); state = reload(state); crewDefeat(state)
        check(earned("ACH_INVADE_SHIP")) { "Victory with no friendly crew at home did not earn" }
        state = reset(); spawn(state); board(state, 1); state = reload(state); crewDefeat(state)
        check(!earned("ACH_INVADE_SHIP")) { "Crew remaining at home earned invasion" }
        state = reset(); spawn(state); board(state); state.enemy!!.health = 0
        check(earned("ACH_INVADE_SHIP")) { "Hull victory did not follow native invasion predicate" }
        state = reset(); spawn(state); board(state)
        val peaceful = org.jdom2.Element("event").addContent(org.jdom2.Element("ship").setAttribute("hostile", "false"))
        state.loadEventShip(xyz.znix.xftl.sector.Event(null, emptyList(), peaceful,
            "diagnostic-peace", "diagnostic-peace", { xyz.znix.xftl.sector.ImageList.NONE }, { xyz.znix.xftl.sector.EventText(it.textTrim) }))
        check(earned("ACH_INVADE_SHIP")) { "Native scripted hostility transition did not earn invasion" }
    }
}
