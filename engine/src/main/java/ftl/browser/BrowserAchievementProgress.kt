package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.Ship
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.layout.Room
import kotlin.math.roundToInt

/** Durable facts about actions that cannot be reconstructed from the current inventory. */
class BrowserAchievementProgress(private val game: InGameState) {
    enum class Action {
        SYSTEM_UPGRADE, REACTOR_UPGRADE, WEAPON_UPGRADE, STORE_REPAIR, STORE_PURCHASE,
        FIRED_SHOT, USED_MISSILE, USED_DRONE, OFFENSIVE_DRONE, TELEPORTED, LOST_CREW, HIGH_OXYGEN, DANGEROUS_ENVIRONMENT
    }

    private val actions = HashSet<Action>()
    var historyComplete = true
        private set
    private var environmentHistoryComplete = true
    var firstJump = false
        private set
    var minimumBeaconHull: Int? = null
        private set
    var nebulaJumps = 0
        private set
    var crewBlueChoices = 0
        private set
    var crewDefeats = 0
        private set
    var rockHullDefeats = 0
        private set
    private var combatHistoryComplete = true
    private var enemyDamagedUncloaked = false
    private var hullKillRecorded = false
    private var failedDodges = 0
    private var damageAvoidedInCloak = 0
    private val beamRoomAges = HashMap<Int, Float>()
    private var beaconStartingHull: Int? = null
    private var beaconStartingCrew: Int? = null
    var beaconCrewKills: Int? = 0
        private set

    private fun owner(crew: AbstractCrew): Ship? = when (crew) {
        is LivingCrew -> crew.ownerShip
        is AbstractIndoorsDrone.Pawn -> crew.drone.ownerShip
        else -> null
    }

    private fun playerCrewCount(): Int {
        // Count owned crew anywhere, including crew still eligible for cloning.
        // The native factory excludes deaths once their death animation starts,
        // unless clone-ready. Do not use physical home-ship occupancy or allegiance.
        val crew = (game.player.crew + (game.enemy?.crew ?: emptyList()) +
            (game.player.clonebay?.queue ?: emptyList())).filterIsInstance<LivingCrew>().distinct()
        return crew.count { it.ownerShip === game.player &&
            (it.health > 0f || game.player.clonebay != null) }
    }

    fun beaconArrived() {
        if (game.isCurrentlyLoadingSave) return
        beaconStartingHull = game.player.health
        beaconStartingCrew = playerCrewCount()
        beaconCrewKills = 0
    }

    fun crewStartedDying(crew: AbstractCrew) {
        if (game.isCurrentlyLoadingSave || crew !is LivingCrew || crew.ownerShip === game.player) return
        beaconCrewKills = beaconCrewKills?.plus(1)
        beaconCrewKills?.let { recordCounter("beaconCrewKills", it) }
    }

    fun crewHealthDepleted(crew: AbstractCrew) {
        if (game.isCurrentlyLoadingSave || crew !is LivingCrew || crew.ownerShip === game.player ||
            crew.room.ship !== game.enemy || !crew.currentAction.isAttackAction || crew.enemyToAttack == null) return
        if (crew.room.fires.any { it != null } && owner(crew.enemyToAttack!!) === game.player) earn("ACH_ROCK_FIRE")
    }

    fun crewKilled(attacker: AbstractCrew, victim: AbstractCrew) {
        if (game.isCurrentlyLoadingSave || victim !is LivingCrew) return
        if (owner(attacker) === game.player && attacker.room.ship === game.enemy && victim.room.fires.any { it != null })
            earn("ACH_ROCK_FIRE")
        val previous = attacker.browserTargetsDestroyed ?: return
        // Native tests the accumulated goal before incrementing it. Completed
        // system sabotage also contributes; an untouched pawn earns on kill five.
        if (attacker is AbstractIndoorsDrone.Pawn && attacker.mode == AbstractCrew.SlotType.INTRUDER &&
            owner(attacker) === game.player && previous >= 4) earn("ACH_BOARDING_DRONE")
        attacker.browserTargetsDestroyed = previous + 1
    }

    private fun checkCrewSlaughter() {
        if ((beaconCrewKills ?: 0) >= 5 && beaconStartingHull == game.player.health &&
            beaconStartingCrew == playerCrewCount()) earn("ACH_MANTIS_SLAUGHTER")
    }

    fun advanceTime(dt: Float) {
        if (game.isCurrentlyLoadingSave) return
        beamRoomAges.keys.toList().forEach { id ->
            val age = beamRoomAges.getValue(id) + dt
            if (age >= 5f) beamRoomAges.remove(id) else beamRoomAges[id] = age
        }
    }

    fun dodgeRolled(ship: Ship, missed: Boolean) {
        if (game.isCurrentlyLoadingSave || ship !== game.player) return
        val engines = ship.engines
        val pilot = ship.piloting
        val functioning = engines != null && pilot != null && engines.powerSupplied >= 8 &&
            !engines.isHackActive && pilot.undamagedEnergy > 0 && !pilot.isHackActive
        failedDodges = if (!missed && functioning) failedDodges + 1 else 0
        recordCounter("failedDodges", failedDodges)
        if (failedDodges >= 5) earn("ACH_BAD_DODGING")
    }

    fun cloakStarted() {
        if (!game.isCurrentlyLoadingSave) damageAvoidedInCloak = 0
    }

    fun damageDodged(ship: Ship, hullDamage: Int) {
        if (game.isCurrentlyLoadingSave || ship !== game.player || !ship.isCloakActive) return
        damageAvoidedInCloak += hullDamage.coerceAtLeast(0)
        recordCounter("damageAvoidedInCloak", damageAvoidedInCloak)
        if (damageAvoidedInCloak >= 9) earn("ACH_STEALTH_AVOID")
    }

    fun beamRoomHit(room: Room) {
        if (game.isCurrentlyLoadingSave || room.ship !== game.enemy) return
        beamRoomAges[room.id] = 0f
        if (room.ship.rooms.isNotEmpty() && room.ship.rooms.all { beamRoomAges.containsKey(it.id) }) earn("ACH_SLICE_DICE")
    }

    fun beamCrewKilled(kills: Int) {
        if (!game.isCurrentlyLoadingSave && kills >= 3) earn("ACH_SLUG_BIO")
    }

    fun crystalShardHit(ship: Ship, previousHull: Int) {
        if (!game.isCurrentlyLoadingSave && ship === game.enemy && previousHull > 0 && ship.health == 0)
            earn("ACH_CRYSTAL_SHARD")
    }

    fun enemyBecamePeaceful() {
        if (game.isCurrentlyLoadingSave || game.enemy == null) return
        // Native GetCrewCount(false) counts live non-drone, non-intruder crew
        // physically at home, including the current mind-control allegiance.
        if (game.player.crew.none { it is LivingCrew && it.health > 0f && it.mode == AbstractCrew.SlotType.CREW })
            earn("ACH_INVADE_SHIP")
    }

    private fun normalWeapons(ship: Ship) = ship.hardpoints.mapNotNull { it.weapon }
    private fun artilleryWeapons(ship: Ship) = ship.systems.filterIsInstance<Artillery>().map { it.browserWeapon }

    fun enemyChanged() {
        if (game.isCurrentlyLoadingSave) return
        combatHistoryComplete = true
        enemyDamagedUncloaked = false
        hullKillRecorded = false
        beamRoomAges.clear()
        for (ship in listOfNotNull(game.player, game.enemy))
            (normalWeapons(ship) + artilleryWeapons(ship)).forEach { it.browserResetEncounterVolleys() }
    }

    fun hullChanged(ship: Ship, previous: Int, current: Int) {
        if (game.isCurrentlyLoadingSave || ship !== game.enemy) return
        checkCloakedDamage(ship)
        // Capture conditions at hull destruction, before the gib animation can
        // expire a cloak or consume the remaining Zoltan shield.
        if (previous > 0 && current == 0 && !game.browserCanWait() && !hullKillRecorded) {
            hullKillRecorded = true
            enemyBecamePeaceful()
            if (game.player.superShield > 0) earn("ACH_ENERGY_SHIELDS")
            if (combatHistoryComplete && !enemyDamagedUncloaked) earn("ACH_STEALTH_DESTROY")
            checkHullWeapons(ship)
        }
    }

    private fun checkCloakedDamage(enemy: Ship) {
        if (enemy.health == enemy.maxHealth) enemyDamagedUncloaked = false
        else if (!game.player.isCloakActive) enemyDamagedUncloaked = true
    }

    private fun checkHullWeapons(enemy: Ship) {
        if (!combatHistoryComplete) return
        val normal = normalWeapons(game.player)
        val artillery = artilleryWeapons(game.player)
        val enemyNormal = normalWeapons(enemy)
        val all = normal + artillery + enemyNormal
        if (all.any { it.browserEncounterVolleys == null }) return
        fun volleys(weapons: List<AbstractWeaponInstance>) = weapons.sumOf { it.browserEncounterVolleys!! }
        val normalCount = volleys(normal)
        val artilleryCount = volleys(artillery)
        val offensiveDrone = game.player.drones?.drones?.any {
            it?.instance?.isPowered == true && it.type.type in listOf(DroneBlueprint.DroneType.COMBAT, DroneBlueprint.DroneType.BOARDER)
        } == true
        if (normal.all { it.browserEncounterVolleys!! <= 1 } && volleys(enemyNormal) == 0 && artilleryCount <= 1 &&
            game.player.getAugmentValue("WEAPON_PREIGNITE") > 0f) earn("ACH_ONE_VOLLEY")
        if (normalCount == 0 && artilleryCount == 0) earn("ACH_ONLY_DRONES")
        // The native 1.6.14 destruction branch checks normal/artillery volleys
        // and powered offensive drones, but has no hull-damage test here.
        if (normalCount == 0 && artilleryCount > 0 && !offensiveDrone) earn("ACH_FED_PATIENCE")
        val defenseDrone = enemy.drones?.drones?.any {
            it?.type?.type == DroneBlueprint.DroneType.DEFENSE && it.type.defenceTarget != "DRONES"
        } == true
        val nonMissileFired = normal.any {
            it.browserEncounterVolleys!! > 0 && it.type !is MissileBlueprint && it.type.name != "MISSILE_CHARGEGUN"
        }
        if (defenseDrone && !nonMissileFired && !offensiveDrone) earn("ACH_ROCK_MISSILES")
    }

    fun has(action: Action) = action in actions

    fun record(action: Action) {
        if (game.isCurrentlyLoadingSave || !actions.add(action)) return
        BrowserDebug.record("achievement", "run.action", "{\"action\":${BrowserDebug.quote(action.name)}}")
    }

    fun onJump() {
        if (game.isCurrentlyLoadingSave) return
        beaconArrived()
        firstJump = true
        minimumBeaconHull = game.player.health
        if (game.currentBeacon.environmentType.isNebula) nebulaJumps++
        recordCounter("nebulaJumps", nebulaJumps)
        update()
    }

    fun crewBlueChoice() {
        if (game.isCurrentlyLoadingSave) return
        crewBlueChoices++
        recordCounter("crewBlueChoices", crewBlueChoices)
        checkCounters()
    }

    fun crewDefeat() {
        if (game.isCurrentlyLoadingSave) return
        crewDefeats++
        recordCounter("crewDefeats", crewDefeats)
        val survivors = game.playerCrew.filter { it.health > 0f }
        if (survivors.count { it.room.ship === game.player } == 0 && survivors.count { it.room.ship === game.enemy } == 1)
            earn("ACH_MANTIS_SURVIVOR")
        checkCounters()
    }

    fun hullDefeat() {
        if (game.isCurrentlyLoadingSave) return
        val name = game.enemy?.type?.name ?: return
        if (listOf("ROCK_SCOUT", "ROCK_ASSAULT", "ROCK_FIGHT").any { name.contains(it) }) {
            rockHullDefeats++
            recordCounter("rockHullDefeats", rockHullDefeats)
            checkCounters()
        }
    }

    fun enterSecretSector() = earn("ACH_ROCK_CRYSTAL")

    private fun checkCounters() {
        val sector = game.currentBeacon.sector.sectorNumber
        // Native WorldManager's level is zero based. Its boundary checks are
        // inclusive of sectors five/six, and allow nebula jumps in sector eight.
        if (nebulaJumps >= 30 && sector <= 8) earn("ACH_SLUG_NEBULA")
        if (crewBlueChoices >= 4 && sector < 5) earn("ACH_FED_DIPLOMACY")
        if (crewDefeats >= 20 && sector < 6) earn("ACH_MANTIS_CREW_DEAD")
        if (rockHullDefeats >= 10) earn("ACH_CRYSTAL_CLASH")
    }

    private fun recordCounter(name: String, value: Int) {
        BrowserDebug.record("achievement", "run.counter", "{\"counter\":${BrowserDebug.quote(name)},\"value\":$value}")
    }

    fun update() {
        if (game.progressionShipName() == null || game.isCurrentlyLoadingSave) return
        checkCrewSlaughter()
        val player = game.player
        if (!player.isCloakActive) damageAvoidedInCloak = 0
        // These four native environment initializers set env_danger. ASB does
        // not share that flag; an ion storm does, despite being a nebula jump.
        if (game.currentBeacon.environmentType in listOf(Beacon.EnvironmentType.ASTEROID, Beacon.EnvironmentType.SUN,
                Beacon.EnvironmentType.PULSAR, Beacon.EnvironmentType.ION_STORM)) record(Action.DANGEROUS_ENVIRONMENT)
        checkCounters()
        minimumBeaconHull = minOf(minimumBeaconHull ?: player.health, player.health)
        if (firstJump && (player.averageOxygen * 100).roundToInt() > 20) record(Action.HIGH_OXYGEN)
        if (player.scrap >= 600) earn("ACH_LANIUS_SCRAP")
        if (minimumBeaconHull == 1 && player.health == player.maxHealth) earn("ACH_TOUGH_SHIP")
        if (player.systems.size >= 11) earn("ACH_FULL_ARSENAL")
        if (player.powerConsumed >= 29) earn("ACH_ENERGY_POWER")
        if (game.playerCrew.filter { it.health > 0f && it.playerControllable }.map { it.codename }.distinct().size >= 6)
            earn("ACH_UNITED_FEDERATION")
        if ((player.drones?.drones?.count { it?.instance?.isPowered == true } ?: 0) >= 3) earn("ACH_ROBOTIC")
        if (player.hacking?.active == true && player.mindControl?.active == true && player.backupBattery?.timeRemaining != null)
            earn("ACH_LANIUS_ADVANCED")
        checkLockdown(player)
        updateEnemy()
    }

    fun updateEnemy() {
        if (game.progressionShipName() == null || game.isCurrentlyLoadingSave) return
        checkCrewSlaughter()
        val enemy = game.enemy ?: return
        checkCloakedDamage(enemy)
        if (enemy.isDead) return
        if (enemy.rooms.isNotEmpty() && enemy.rooms.all { room -> room.fires.all { it != null } }) earn("ACH_BURNING")
        if (!enemy.isAutoScout && !enemy.isAutomated && !game.browserCanWait() && (enemy.averageOxygen * 100).roundToInt() < 5)
            earn("ACH_SUFFOCATE")
        // Native GetLocked includes ion/cooldown locks and active hacking.
        if (enemy.systems.count { it.isIonised || it.isHackActive || it.hasWhiteLockingBox } >= 4) earn("ACH_IONED")
        if (game.player.sensors?.providesEnemyVision != true && enemy.rooms.isNotEmpty() && enemy.rooms.all { it.playerHasVision })
            earn("ACH_SLUG_VISION")
        checkLockdown(enemy)
    }

    private fun checkLockdown(ship: xyz.znix.xftl.Ship) {
        val hostileMode = if (ship.isPlayerShip) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW
        if (ship.rooms.any { room -> room.isLockedDown && room.crew.count { it.health > 0f && it.mode == hostileMode } >= 4 })
            earn("ACH_CRYSTAL_LOCKDOWN")
    }

    private fun earn(id: String) {
        val main = game.mainGame ?: return
        val achievement = game.content.achievements[id]
        val ship = game.progressionShipName() ?: return
        // Automatic ship achievements apply to every layout in that family.
        if (achievement.ship != null && game.content.shipFamilies.byShipId[ship]?.ships?.contains(achievement.ship) != true) return
        main.profile.unlockAchievement(achievement, game.difficulty)
    }

    fun visit(sector: Int) {
        update()
        if (!historyComplete || game.progressionShipName() == null) return
        if (sector >= 4) {
            // AdvanceWorldLevel (0x5edec0) tests only the system_upgrade flag; a reactor upgrade does not count.
            if (!has(Action.SYSTEM_UPGRADE)) earn("ACH_NO_UPGRADES")
            if (!has(Action.STORE_REPAIR)) earn("ACH_NO_REPAIR")
            if (!has(Action.FIRED_SHOT) && !has(Action.OFFENSIVE_DRONE) && !has(Action.TELEPORTED)) earn("ACH_PACIFIST")
            if (!has(Action.REACTOR_UPGRADE)) earn("ACH_ENERGY_MANPOWER")
            if (!has(Action.WEAPON_UPGRADE)) earn("ACH_FED_UPGRADE")
        }
        if (sector >= 7) {
            if (environmentHistoryComplete && !has(Action.DANGEROUS_ENVIRONMENT)) earn("ACH_STEALTH_TACTICAL")
            if (!has(Action.USED_MISSILE)) earn("ACH_NO_MISSILES")
            if (!has(Action.USED_DRONE)) earn("ACH_NO_DRONES")
            if (!has(Action.STORE_PURCHASE)) earn("ACH_NO_BUYING")
            if (!has(Action.LOST_CREW)) earn("ACH_NO_DEATH")
            if (firstJump && !has(Action.HIGH_OXYGEN)) earn("ACH_LANIUS_OXYGEN")
        }
    }

    fun save(): Element = Element("browserAchievements").setAttribute("complete", historyComplete.toString())
        .setAttribute("firstJump", firstJump.toString()).also { elem ->
            elem.setAttribute("environmentComplete", environmentHistoryComplete.toString())
            elem.setAttribute("nebulaJumps", nebulaJumps.toString())
            elem.setAttribute("crewBlueChoices", crewBlueChoices.toString())
            elem.setAttribute("crewDefeats", crewDefeats.toString())
            elem.setAttribute("rockHullDefeats", rockHullDefeats.toString())
            elem.setAttribute("combatComplete", combatHistoryComplete.toString())
            elem.setAttribute("enemyDamagedUncloaked", enemyDamagedUncloaked.toString())
            elem.setAttribute("hullKillRecorded", hullKillRecorded.toString())
            elem.setAttribute("failedDodges", failedDodges.toString())
            elem.setAttribute("damageAvoidedInCloak", damageAvoidedInCloak.toString())
            beaconStartingHull?.let { elem.setAttribute("beaconStartingHull", it.toString()) }
            beaconStartingCrew?.let { elem.setAttribute("beaconStartingCrew", it.toString()) }
            beaconCrewKills?.let { elem.setAttribute("beaconCrewKills", it.toString()) }
            for ((room, age) in beamRoomAges.toSortedMap()) elem.addContent(Element("beamRoom")
                .setAttribute("id", room.toString()).setAttribute("age", age.toString()))
            minimumBeaconHull?.let { elem.setAttribute("minimumBeaconHull", it.toString()) }
            // An autosave preserves purchased levels even while Undo is available.
            // Include those purchases in the saved history without disabling live Undo.
            val savedActions = actions + (game.shipUI?.pendingUpgradeActions ?: emptySet())
            for (action in savedActions.sortedBy { it.name }) elem.addContent(Element("action").setAttribute("id", action.name))
        }

    fun load(elem: Element?) {
        actions.clear()
        historyComplete = elem?.getAttributeValue("complete") == "true"
        environmentHistoryComplete = elem?.getAttributeValue("environmentComplete") == "true"
        firstJump = elem?.getAttributeValue("firstJump") == "true"
        minimumBeaconHull = elem?.getAttributeValue("minimumBeaconHull")?.toIntOrNull()
        nebulaJumps = elem?.getAttributeValue("nebulaJumps")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        crewBlueChoices = elem?.getAttributeValue("crewBlueChoices")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        crewDefeats = elem?.getAttributeValue("crewDefeats")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        rockHullDefeats = elem?.getAttributeValue("rockHullDefeats")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        combatHistoryComplete = elem?.getAttributeValue("combatComplete") == "true"
        enemyDamagedUncloaked = elem?.getAttributeValue("enemyDamagedUncloaked") != "false"
        hullKillRecorded = elem?.getAttributeValue("hullKillRecorded") == "true"
        failedDodges = elem?.getAttributeValue("failedDodges")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        damageAvoidedInCloak = elem?.getAttributeValue("damageAvoidedInCloak")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        beaconStartingHull = elem?.getAttributeValue("beaconStartingHull")?.toIntOrNull()
        beaconStartingCrew = elem?.getAttributeValue("beaconStartingCrew")?.toIntOrNull()
        beaconCrewKills = elem?.getAttributeValue("beaconCrewKills")?.toIntOrNull()?.coerceAtLeast(0)
        beamRoomAges.clear()
        for (entry in elem?.getChildren("beamRoom") ?: emptyList()) {
            val id = entry.getAttributeValue("id")?.toIntOrNull() ?: continue
            val age = entry.getAttributeValue("age")?.toFloatOrNull() ?: continue
            if (age >= 0f && age < 5f) beamRoomAges[id] = age
        }
        for (entry in elem?.getChildren("action") ?: emptyList()) {
            Action.values().firstOrNull { it.name == entry.getAttributeValue("id") }?.let { actions.add(it) }
        }
    }
}
