package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.SuffocationDamage
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.sys.GameContainer

/**
 * Crew whose race leaves out animation strips: the fight goes on, damage lands, consoles get manned, fires put out and
 * systems repaired, and nothing stops on the missing name. `hsRuntimeProbe("ma <race>")` writes the same steps as
 * tools/hs-oracle's missing-anims sessions.
 */
object BrowserMissingAnimDiagnostics {
    private const val DT = .05f

    private class Bench(val main: MainGame, val container: GameContainer, val graphics: Graphics?, var state: InGameState) {
        val player get() = state.player
        val fightRoom: Room get() = roomOf(player.shields) ?: player.rooms.first()
        val manRoom: Room get() = roomOf(player.piloting) ?: player.rooms.last()
        val repairSystem: MainSystem get() = player.engines!!

        fun roomOf(system: Any?): Room? = player.rooms.firstOrNull { it.system === system && system != null }

        fun recruit(race: String): LivingCrew =
            player.addCrewMemberInRoom(LivingCrewInfo.generateRandom(state.blueprintManager[race] as CrewBlueprint, state), fightRoom, false)

        fun intruder(race: String, room: Room): LivingCrew {
            val info = LivingCrewInfo.generateRandom(state.blueprintManager[race] as CrewBlueprint, state)
            return player.addCrewMember(info, false, true).also { it.jumpTo(player.findSpaceForCrew(room, AbstractCrew.SlotType.INTRUDER)) }
        }

        fun tick(seconds: Float, each: () -> Unit = {}) {
            val n = (seconds / DT).toInt()
            for (i in 0 until n) {
                main.update(container, DT)
                each()
                if (graphics != null && i % 40 == 0) main.render(container, graphics)
            }
        }

        /** Everyone else leaves the ship but one, who waits far away so the ship stays crewed. */
        fun clear(keep: AbstractCrew) {
            val others = player.crew.filter { it !== keep }
            val far = player.rooms.first { it.system == null || (it !== fightRoom && it !== manRoom && it.system !== repairSystem) }
            others.firstOrNull()?.jumpTo(player.findSpaceForCrew(far, AbstractCrew.SlotType.CREW))
            others.drop(1).forEach { it.dealDamage(SuffocationDamage(1000f)) }
            tick(3f) { keep.health = keep.maxHealth }
        }
    }

    private class Steps {
        var dealt = 0f
        var repaired = 0f
        val actions = LinkedHashSet<AbstractCrew.Action>()
        var blank = false
        var fires = 0
    }

    private fun fight(b: Bench, crew: LivingCrew, seconds: Float, foe: String): Steps {
        val enemy = b.intruder(foe, b.fightRoom)
        val out = Steps()
        var last = enemy.health
        b.tick(seconds) {
            crew.health = crew.maxHealth
            if (enemy.health < last) out.dealt += last - enemy.health
            if (enemy.health < enemy.maxHealth / 2) enemy.health = enemy.maxHealth
            last = enemy.health
            out.actions.add(crew.currentAction)
            out.blank = out.blank || crew.icon.spec.blank
        }
        enemy.dealDamage(SuffocationDamage(100000f))
        b.tick(2f) { crew.health = crew.maxHealth }
        return out
    }

    private fun man(b: Bench, crew: LivingCrew, seconds: Float): Steps {
        val out = Steps()
        crew.commandMoveRoom(b.manRoom)
        b.tick(seconds) {
            crew.health = crew.maxHealth
            out.actions.add(crew.currentAction)
            if (crew.currentAction == AbstractCrew.Action.MANNING) out.blank = out.blank || crew.icon.spec.blank
        }
        return out
    }

    private fun fire(b: Bench, crew: LivingCrew, seconds: Float): Steps {
        val out = Steps()
        val room = b.manRoom
        room.spawnFireAt(0)
        crew.commandMoveRoom(room)
        b.tick(seconds) {
            crew.health = crew.maxHealth
            out.actions.add(crew.currentAction)
            if (crew.currentAction == AbstractCrew.Action.FIRE_FIGHTING) out.blank = out.blank || crew.icon.spec.blank
        }
        out.fires = room.fires.count { it != null }
        room.fires.indices.forEach { room.fires[it] = null }
        return out
    }

    private fun repair(b: Bench, crew: LivingCrew, seconds: Float): Steps {
        val out = Steps()
        val system = b.repairSystem
        val room = b.roomOf(system)!!
        system.damagedEnergyLevels = 1
        val start = system.damagedEnergyLevels
        crew.commandMoveRoom(room)
        var progress = system.repairProgress
        b.tick(seconds) {
            crew.health = crew.maxHealth
            out.actions.add(crew.currentAction)
            if (crew.currentAction == AbstractCrew.Action.REPAIRING) out.blank = out.blank || crew.icon.spec.blank
            if (system.repairProgress > progress) out.repaired += system.repairProgress - progress
            progress = system.repairProgress
        }
        out.repaired += (start - system.damagedEnergyLevels).toFloat()
        system.damagedEnergyLevels = 0
        return out
    }

    /** The seconds from a fatal blow until the crew member has left the ship, or -1. */
    private fun die(b: Bench, crew: LivingCrew, limit: Float): Float {
        crew.health = 0f
        var t = 0f
        while (t < limit && crew in b.player.crew) {
            b.main.update(b.container, DT)
            t += DT
        }
        return if (crew in b.player.crew) -1f else t
    }

    /** `hsRuntimeProbe("ma <race>")`: the oracle's steps, one line each. */
    fun trace(main: MainGame, container: GameContainer, race: String): String {
        val b = Bench(main, container, null, BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL))
        val crew = b.recruit(race)
        b.clear(crew)
        val out = StringBuilder()
        fun row(name: String, s: Steps) =
            out.append("$name|dealt|${s.dealt}|repaired|${s.repaired}|fires|${s.fires}|actions|${s.actions}|blank|${s.blank}\n")
        row("fight", fight(b, crew, 22f, "human"))
        row("man", man(b, crew, 12f))
        row("fire", fire(b, crew, 15f))
        row("repair", repair(b, crew, 20f))
        out.append("die|left after|${die(b, crew, 8f)}\n")
        return out.toString()
    }

    /** All of a race's strips hidden, and with them its sheet where a race can name one. */
    private fun hidden(main: MainGame, container: GameContainer, graphics: Graphics, race: String): String {
        val first = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        @Suppress("UNCHECKED_CAST")
        val animations = first.animations.animations as MutableMap<String, xyz.znix.xftl.AnimationSpec>
        val removed = animations.filterKeys { it.startsWith("${race}_") || (race == "human" && it.startsWith("female_")) }
        val def = if (HS.active) CustomCrewManager.definitionOrNull(race) else null
        val sheets = def?.animSheet?.copyOf()
        try {
            removed.keys.forEach { animations.remove(it) }
            def?.animSheet?.fill("no_such_sheet")

            val b = Bench(main, container, graphics, first)
            val crew = b.recruit(race)
            b.clear(crew)

            val fought = fight(b, crew, 12f, "rock")
            check(fought.dealt > 0f && fought.blank && AbstractCrew.Action.FIGHTING in fought.actions) {
                "missing strips, fight: dealt ${fought.dealt} blank ${fought.blank} ${fought.actions}"
            }
            val manned = man(b, crew, 12f)
            check(AbstractCrew.Action.MOVING in manned.actions && AbstractCrew.Action.MANNING in manned.actions && manned.blank) {
                "missing strips, console: ${manned.actions} blank ${manned.blank}"
            }
            val fires = fire(b, crew, 60f)
            check(fires.fires == 0 && AbstractCrew.Action.FIRE_FIGHTING in fires.actions) { "missing strips, fire: ${fires.fires} left ${fires.actions}" }
            val fixed = repair(b, crew, 20f)
            check(fixed.repaired > 0f && AbstractCrew.Action.REPAIRING in fixed.actions) { "missing strips, repair: ${fixed.repaired} ${fixed.actions}" }

            // As a boarder: sabotage or a door, then a save and load with the strips still missing.
            val boarder = b.intruder(race, b.manRoom)
            b.tick(6f) { boarder.health = boarder.maxHealth; crew.health = crew.maxHealth }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(b.state.saveGameState())))
            b.state = main.currentState as InGameState
            BrowserDiagnostics.closeBoxes(main, container)
            if (b.state.isPaused) b.state.toggleQuickPause()
            b.tick(4f)
            main.render(container, graphics)
            val again = b.player.crew.filterIsInstance<LivingCrew>().first { it.codename == race && it.mode == AbstractCrew.SlotType.CREW }

            val left = die(b, again, 8f)
            check(left >= 0f) { "missing strips, death: still on the ship after 8 s" }
            return "fight dealt ${fought.dealt}, console, fire, repair ${fixed.repaired}, boarder, save and load, death after $left s"
        } finally {
            animations.putAll(removed)
            if (sheets != null) sheets.indices.forEach { def.animSheet[it] = sheets[it] }
        }
    }

    /** Each race whose data really leaves strips out, a few seconds of every action; found by looking, none named. */
    private fun scan(main: MainGame, container: GameContainer, graphics: Graphics): String {
        var state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
        val strips = listOf("walk_down", "punch_up", "repair", "type_down", "fire_down", "shoot_down", "death_right", "portrait", "teleport")
        val races = CustomCrewManager.races.filter { race ->
            val sheet = CustomCrewManager.definitionOrNull(race)?.animSheet?.get(1)?.ifEmpty { null } ?: race
            state.blueprintManager[race] is CrewBlueprint && strips.any { state.animations.getOrNull("${sheet}_$it") == null }
        }
        var n = 0
        for ((i, race) in races.withIndex()) {
            if (i % 8 == 0 || state.shipUI.gameOverWindow != null) state = BrowserDiagnostics.freshState(main, container, "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            val b = Bench(main, container, graphics, state)
            val crew = b.recruit(race)
            b.tick(.5f)
            fight(b, crew, 3f, "human")
            man(b, crew, 3f)
            fire(b, crew, 3f)
            repair(b, crew, 3f)
            b.intruder(race, b.manRoom).also { boarder -> b.tick(2f) { boarder.health = boarder.maxHealth } }
            main.render(container, graphics)
            die(b, crew, 4f)
            b.player.crew.filter { it.mode == AbstractCrew.SlotType.INTRUDER }.forEach { it.dealDamage(SuffocationDamage(100000f)) }
            b.tick(.5f)
            BrowserDiagnostics.closeBoxes(main, container)
            n++
        }
        return "$n races with strips left out"
    }

    fun check(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val all = hidden(main, container, graphics, "human")
        val data = if (HS.active) "; ${scan(main, container, graphics)}" else ""
        return "missing crew strips: $all$data"
    }
}
