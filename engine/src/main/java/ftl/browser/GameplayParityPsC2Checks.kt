package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep C2 (hacking, mind control, cloaking, teleporter and boarding, artillery): rule checks for what the
 * sweep fixed.
 * - The player's boarders on an enemy ship that goes (WorldManager::OnLoop 1.6.14 0x61b930, ClearLocation 0x614d00,
 *   ShipManager::KillEveryone 0x4ab990, CrewMember::Kill 0x47ba00): the lost_crew flag, then a destroyed ship's
 *   boarders die and reach the clone bay, those of a ship that jumped away, of a ship left behind by the player's jump
 *   or replaced by the console's LOAD die for good. The old ship's boarders aboard the player's ship stay (LOAD).
 * - MindSystem::OnLoop (1.6.14 0x630360, release 0x62f740): a controlled crew member that starts dying leaves the list
 *   without a sound and the empty list locks the system at once.
 * - CompleteShip::InitiateTeleport (1.6.14 0x409ae0, Hyperspace's rewrite the same): no teleport, sent or brought back,
 *   while either ship is jumping; the player's command is taken all the same (GetTeleportationCommand 0x57f370).
 * - Hyperspace hackingIonFix (Balance.cpp ShipSystem::IonDamage hook) tests HackingSystem::bHacking, so a second ion hit
 *   while an earlier one holds the pulse still makes the lock four units.
 * - TeleportSystem::OnLoop (1.6.14 0x648640): an armed teleporter is disarmed while the target has a super shield.
 * - ShipSystem::BlockedBoosted (1.6.9 0x6155f0, asked by CrewMember::SetCurrentSystem): no manning while the system's
 *   hacking level (iHackEffect, which a Hyperspace script sets with SetHackingLevel) is above 1.
 */
internal object GameplayParityPsC2Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val parts = listOf<() -> String>(
                { boardersDestroyed(main, container) },
                { boardersEscaped(main, container) },
                { boardersLeftBehind(main, container) },
                { boardersLoad(main, container) },
                { mindControlledDies(main, container) },
                { hackingIonFix(main, container) },
                { teleportWhileJumping(main, container) },
                { teleportDisarmed(main, container) },
                { hackedNotManned(main, container) },
            )
            val results = ArrayList<String>()
            val failures = ArrayList<String>()
            for (part in parts) {
                try {
                    results += part()
                } catch (e: Throwable) {
                    failures += e.message ?: e::class.simpleName ?: "failure"
                }
            }
            check(failures.isEmpty()) { failures.joinToString(" / ") }
            return "psC2 ok: " + results.joinToString("; ")
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** A Mantis C (clone bay) with two of its crew aboard a fresh pirate, in a room with no system. */
    private fun boarded(main: MainGame, container: GameContainer): Pair<InGameState, List<LivingCrew>> {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS_3")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        val player = state.player
        val enemy = state.enemy!!
        val target = enemy.rooms.firstOrNull { it.system == null } ?: enemy.rooms.first()
        val boarders = player.crew.filterIsInstance<LivingCrew>().take(2)
        for (crew in boarders) {
            crew.removeFromShip()
            enemy.crew.add(crew)
            crew.jumpTo(enemy.findSpaceForCrew(target, AbstractCrew.SlotType.INTRUDER))
        }
        state.updatePlayerCrew()
        check(!state.browserAchievements.has(BrowserAchievementProgress.Action.LOST_CREW)) { "Set-up: crew already lost" }
        check(boarders.size == 2 && boarders.all { it.mode == AbstractCrew.SlotType.INTRUDER }) { "Set-up: boarders" }
        return state to boarders
    }

    private fun lost(state: InGameState, boarders: List<LivingCrew>, old: xyz.znix.xftl.Ship, what: String) {
        check(state.browserAchievements.has(BrowserAchievementProgress.Action.LOST_CREW)) { "$what: no lost_crew flag" }
        check(boarders.none { old.crew.contains(it) }) { "$what: boarders still aboard the old ship" }
        check(boarders.none { state.playerCrew.contains(it) }) { "$what: boarders still in the player's crew" }
        check(boarders.none { state.player.clonebay?.queue?.contains(it) == true }) { "$what: boarders in the clone queue" }
    }

    private fun boardersDestroyed(main: MainGame, container: GameContainer): String {
        val (state, boarders) = boarded(main, container)
        val enemy = state.enemy!!
        // Let the enemy crew come and fight them, so a boarder dies with a target aboard the ship that goes.
        var frames = 0
        while (boarders.none { it.enemyToAttack != null } && frames++ < 600) state.updateGameState(DT)
        enemy.health = 0
        frames = 0
        while (state.enemy === enemy && frames++ < 600) {
            main.update(container, DT)
            state.shipUI.diagnosticDialogue?.selectOption(0)
        }
        check(state.enemy !== enemy) { "The destroyed ship never went" }
        check(state.browserAchievements.has(BrowserAchievementProgress.Action.LOST_CREW)) { "Destroyed: no lost_crew flag" }
        check(boarders.none { enemy.crew.contains(it) }) { "Destroyed: boarders still aboard" }
        val queue = state.player.clonebay?.queue ?: return "destroyed: boarders killed (no clone bay under these blueprints)"
        check(boarders.all { queue.contains(it) || it.room.ship === state.player }) {
            "Destroyed: boarders not cloned (${boarders.map { it.room.ship === state.player }}, queue ${queue.size})"
        }
        // The save holds the queued boarders without their last fight (it pointed into the ship that is gone).
        BrowserGameplayParityDiagnostics.reload(main, state)
        return "destroyed: boarders killed and cloned, saved"
    }

    private fun boardersEscaped(main: MainGame, container: GameContainer): String {
        val (state, boarders) = boarded(main, container)
        val enemy = state.enemy!!
        enemy.surrenderHealth = 0
        enemy.escapeHealth = 0
        check(enemy.canChargeFTL) { "The fixture enemy can't charge its FTL" }
        enemy.escapeTimer = 0.1f
        var frames = 0
        while (state.enemy === enemy && frames++ < 200) main.update(container, DT)
        check(state.enemy !== enemy) { "The escaping enemy never left (timer ${enemy.escapeTimer})" }
        lost(state, boarders, enemy, "Escaped")
        return "escaped: boarders lost, no clone"
    }

    private fun boardersLeftBehind(main: MainGame, container: GameContainer): String {
        val (state, boarders) = boarded(main, container)
        val enemy = state.enemy!!
        val next = state.currentBeacon.sector.beacons.first { it !== state.currentBeacon }
        state.setCurrentBeacon(next)
        lost(state, boarders, enemy, "Left behind")
        return "left behind: boarders lost, no clone"
    }

    private fun boardersLoad(main: MainGame, container: GameContainer): String {
        val (state, boarders) = boarded(main, container)
        val enemy = state.enemy!!
        // One of the pirate's crew aboard the player's ship as a boarder.
        val intruder = enemy.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === enemy && it !in boarders }
        intruder.removeFromShip()
        state.player.crew.add(intruder)
        intruder.jumpTo(state.player.findSpaceForCrew(state.player.rooms.first(), AbstractCrew.SlotType.INTRUDER))
        state.updatePlayerCrew()
        val autoName = state.eventManager.getShip("PIRATE").autoBlueprintName
        HsConsole.run(state, "LOAD $autoName 2")
        check(state.enemy != null && state.enemy !== enemy) { "LOAD gave no new ship" }
        lost(state, boarders, enemy, "LOAD")
        // The old ship's boarder stays aboard and fighting (native capture ps-u2b u2c-ship2, ClearLocation leaves the
        // player's ship alone).
        main.update(container, DT)
        check(state.player.crew.contains(intruder) && intruder.mode == AbstractCrew.SlotType.INTRUDER &&
                state.player.intruders.contains(intruder) && intruder.standingShipHostile) { "LOAD: the old ship's boarder left" }
        return "LOAD: boarders lost, the old ship's boarder stays"
    }

    private fun mindControlledDies(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        state.debugFlags.showEverything.set = true
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val enemy = state.enemy!!
        enemy.rooms.forEach { it.update(0f) }
        state.player.purchasedReactorPower = 25
        val mind = state.player.mindControl ?: return "mind control dies: no mind system under these blueprints"
        mind.increasePower(); state.player.update(0f)
        val target = enemy.crew.filterIsInstance<LivingCrew>().first { !it.isMindControlResistant }
        mind.selectRoom(target.room); mind.update(0f)
        val controlled = mind.controlledCrew ?: error("Mind control took nobody")
        check(mind.active && controlled.isMindControlled) { "Mind control did not start" }
        val sounds = main.gameContent.sounds as? xyz.znix.xftl.game.RealSoundManager
        val played = ArrayList<String>()
        sounds?.traceSink = { kind, name -> if (kind == "play") played += name }
        var frames = 0
        try {
            controlled.health = 0f
            while (mind.active && frames++ < 60) state.updateGameState(DT)
        } finally {
            sounds?.traceSink = null
        }
        check(!mind.active && frames <= 3) { "Released $frames frames after the death started (active ${mind.active})" }
        check(mind.isPowerLocked) { "No lock after the release" }
        check("mindControlEnd" !in played) { "The release played ${played.filter { it.startsWith("mind") }}" }
        return "mind control: a dying crew member is released at once, silently, with the lock"
    }

    private fun hackingIonFix(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "hackingIonFix: Hyperspace only"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC")
        val hacking = state.player.hacking ?: return "hackingIonFix: no hacking system under these blueprints"
        val saved = xyz.znix.xftl.hs.HS.config
        val root = org.jdom2.Element("FTL").addContent(org.jdom2.Element("hackingIonFix").setAttribute("enabled", "true"))
        try {
            xyz.znix.xftl.hs.HS.config = xyz.znix.xftl.hs.HsConfig.parse(root)
            check(xyz.znix.xftl.hs.HS.config?.hackingIonFix == true) { "Set-up: hackingIonFix not read" }
            hacking.hsSetHacking(true)
            hacking.dealDamage(0, 1)
            check(hacking.hsHacking && !hacking.active && hacking.ionTimer == 20f) { "First ion: lock ${hacking.ionTimer}" }
            hacking.lockUnits(1)
            hacking.dealDamage(0, 1)
            check(hacking.ionTimer == 20f) { "Second ion while the pulse is held: lock ${hacking.ionTimer}, not 20" }
        } finally {
            xyz.znix.xftl.hs.HS.config = saved
        }
        return "hackingIonFix: four units on a held pulse too"
    }

    /** BlockedBoosted: a system at hacking level 2 is not manned, and its crew member mans it again once it ends. */
    private fun hackedNotManned(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_HARD")
        repeat(120) { state.updateGameState(DT) }
        val sys = state.player.systems.firstOrNull { it.room != null && it.manningCrew != null }
            ?: return "hacked not manned: nobody mans a system under these blueprints"
        sys.hsHackLevel = 2
        repeat(3) { state.updateGameState(DT) }
        check(sys.manningCrew == null) { "${sys.blueprint.name} still manned at hacking level 2" }
        sys.hsHackLevel = 0
        repeat(40) { state.updateGameState(DT) }
        check(sys.manningCrew != null) { "${sys.blueprint.name} not manned again after the hack" }
        return "hacked ${sys.blueprint.name} not manned"
    }

    /** TeleportSystem::OnLoop (0x648640): an armed teleporter is disarmed while the target has a super shield. */
    private fun teleportDisarmed(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        val enemy = state.enemy!!
        player.teleporter ?: return "teleporter disarmed: no teleporter under these blueprints"
        state.shipUI.teleportSelected(true)
        state.updateGameState(DT)
        check(state.shipUI.teleportMode == true) { "Set-up: the teleporter did not arm" }
        repeat(2) { enemy.nativeSetSuperShield(1); state.updateGameState(DT) }
        check(state.shipUI.teleportMode == null) { "Still armed against a super shield" }
        return "teleporter disarmed by the target's super shield"
    }

    private fun teleportWhileJumping(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        val player = state.player
        val enemy = state.enemy!!
        val teleporter = player.teleporter ?: return "teleport while jumping: no teleporter under these blueprints"
        player.purchasedReactorPower = 25
        teleporter.increasePower(); player.update(0f)
        val crew = player.crew.filterIsInstance<LivingCrew>().first()
        crew.jumpTo(player.findSpaceForCrew(teleporter.room!!, AbstractCrew.SlotType.CREW))
        player.update(0f)
        check(teleporter.isSendAvailable) { "Set-up: no send available" }
        enemy.surrenderHealth = 0
        enemy.escapeHealth = 0
        enemy.escapeTimer = 0.1f
        var frames = 0
        while (!enemy.browserJumping && frames++ < 120) main.update(container, DT)
        check(enemy.browserJumping) { "Set-up: the enemy never jumped (timer ${enemy.escapeTimer})" }
        teleporter.selectTeleportAction(true, enemy.rooms.first())
        repeat(3) { main.update(container, DT) }
        check(crew.room.ship === player && !crew.isTeleporting) { "Crew sent to a jumping ship" }
        check(!teleporter.isPowerLocked) { "The refused teleport locked the teleporter" }
        while (state.enemy === enemy && frames++ < 200) main.update(container, DT)
        check(state.enemy !== enemy && crew.room.ship === player) { "The command survived the jump" }
        return "teleport while the enemy jumps: refused, command dropped"
    }
}
