package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep follow-up FU2 (crew and enemy AI): rule checks for what it fixed.
 * - ShipSystem::BlockedBoosted (1.6.14 0x63b8a0) holds while bOccupied (ShipManager::OnLoop 0x4abd40: intruders in the
 *   room), and the power-bar render (0x63c310) then draws no crew-slot icon at all.
 * - CompleteShip::OnLoop's arriving party (0x409ee0) keeps CrewMember::iShipId: an enemy-side boarder that an enemy
 *   ship brings home is that ship's crew there (oracle ps-fu2-rc).
 * - Hyperspace: CrewMember::Functional is !bDead, so a dying crew member stays functional until it is gone (oracle
 *   ps-fu2-zf: a Zoltan's bonus power lasts through its death hold).
 * - A Slug aboard the player's ship lights the rooms that share a wall with its own, door or not (oracle ps-fu2-sl).
 */
internal object GameplayParityPsFu2Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val parts = listOf<() -> String>(
                { occupiedIcon(main, container) },
                { reowned(main, container) },
                { functionalUntilGone(main, container) },
                { slugVision(main, container) },
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
            return "psFu2 ok: " + results.joinToString("; ")
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun withEnemy(main: MainGame, container: GameContainer): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        return state
    }

    /** One of the enemy's crew moved aboard the player's ship as a boarder in [room]. */
    private fun board(state: InGameState, room: xyz.znix.xftl.layout.Room): LivingCrew {
        val enemy = state.enemy!!
        val crew = enemy.crew.filterIsInstance<LivingCrew>().first()
        crew.removeFromShip()
        state.player.crew.add(crew)
        crew.jumpTo(state.player.findSpaceForCrew(room, AbstractCrew.SlotType.INTRUDER))
        state.updatePlayerCrew()
        return crew
    }

    private fun occupiedIcon(main: MainGame, container: GameContainer): String {
        val state = withEnemy(main, container)
        val piloting = state.player.piloting ?: error("No piloting system")
        check(piloting.boxCrewSlotIconDrawn) { "Icon: not drawn before any boarder" }
        val boarder = board(state, piloting.room!!)
        state.updateGameState(DT)
        check(boarder.room === piloting.room && boarder.mode == AbstractCrew.SlotType.INTRUDER) { "Icon: set-up" }
        check(!piloting.boxCrewSlotIconDrawn) { "Icon: drawn with a boarder in the room" }
        return "no crew-slot icon while boarders occupy the room"
    }

    private fun reowned(main: MainGame, container: GameContainer): String {
        val state = withEnemy(main, container)
        val enemy = state.enemy!!
        val target = enemy.rooms.first { r -> r.anySlotsFree(AbstractCrew.SlotType.CREW, null) }
        // An event's boarder (no ship sent it) brought home by the enemy's teleporter.
        val boarder = board(state, state.player.rooms.first())
        boarder.ownerShip = null
        check(boarder.mode == AbstractCrew.SlotType.INTRUDER) { "Re-own: set-up" }
        // A player crew member sent the same way stays a boarder there.
        val own = state.player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
        boarder.teleportAnimatedTo(target)
        own.teleportAnimatedTo(target)
        var frames = 0
        while ((boarder.isTeleporting || own.isTeleporting) && frames++ < 400) state.updateGameState(DT)
        check(boarder.room.ship === enemy && own.room.ship === enemy) { "Re-own: never arrived" }
        check(boarder.ownerShip === enemy && boarder.mode == AbstractCrew.SlotType.CREW) {
            "Re-own: the enemy's boarder is ${boarder.mode} aboard the enemy"
        }
        check(own.ownerShip === state.player && own.mode == AbstractCrew.SlotType.INTRUDER) { "Re-own: the player's crew changed side" }
        return "an enemy-side boarder brought home by another ship is its crew"
    }

    private fun functionalUntilGone(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "functional: vanilla, not applicable"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first()
        val ex = xyz.znix.xftl.hs.crew.HsCrew.ex(crew)
        check(ex.functional) { "Functional: not before" }
        crew.health = 0f
        var frames = 0
        var dying = 0
        while (state.player.crew.contains(crew) && frames++ < 200) {
            state.updateGameState(DT)
            if (state.player.crew.contains(crew) && crew.currentAction == AbstractCrew.Action.DYING) {
                check(ex.functional) { "Functional: lost while dying (frame $frames)" }
                dying++
            }
        }
        check(dying > 10) { "Functional: no death hold ($dying frames)" }
        check(!state.player.crew.contains(crew)) { "Functional: never gone" }
        return "functional through the $dying-frame death hold"
    }

    private fun slugVision(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val sensors = player.sensors
        if (sensors != null) sensors.damagedEnergyLevels = sensors.energyLevels
        val crewRooms = player.crew.map { it.room }.toSet()
        // A room with a wall neighbour that no door joins to it, neither holding any crew.
        val pair = player.rooms.asSequence().filter { it !in crewRooms && it.doors.isNotEmpty() }.flatMap { r ->
            r.wallNeighbours.asSequence().filter { n -> n !in crewRooms && !r.connectedTo(n) }.map { r to it }
        }.firstOrNull() ?: return "slug vision: no door-less wall on this ship"
        val (room, neighbour) = pair
        val slug = state.blueprintManager["slug"] as xyz.znix.xftl.crew.CrewBlueprint
        player.addCrewMemberInRoom(LivingCrewInfo(state, slug, "S", 0), room, recordRecruitment = false)
        repeat(3) { state.updateGameState(DT) }
        if (sensors != null) check(sensors.damagedEnergyLevels > 0) { "Slug vision: sensors mended" }
        check(neighbour.playerHasVision) { "Slug vision: room ${neighbour.id} beside the slug's ${room.id} (no door) dark" }
        val far = player.rooms.firstOrNull { it !== room && it !in room.wallNeighbours && it !in crewRooms && it.crew.isEmpty() }
        if (far != null) check(!far.playerHasVision) { "Slug vision: room ${far.id} away from the slug lit" }
        return "a slug lights the rooms beside it through walls"
    }
}
