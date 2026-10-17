package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.weapons.Damage

/** Confirmed non-event effects on entering a lesson (native AdvanceState
 * 0x00415720). Session-owned history survives replacement combat states. */
class BrowserTutorialEntryEffects(private val adapter: BrowserTutorialAdapter) {
    private val appliedSteps = mutableSetOf<String>()

    fun supports(stepId: String) = stepId in SUPPORTED_STEPS
    fun isApplied(stepId: String) = stepId in appliedSteps

    /** Reject ordinary/stale simulations before consuming RNG or changing state. */
    fun apply(game: InGameState, stepId: String): Boolean {
        require(supports(stepId)) { "Unsupported tutorial entry effect: $stepId" }
        val main = game.mainGame
        check(main.isTutorialRunning && main.tutorialAdapter === adapter && main.currentState === game &&
            adapter.controller.step.id == stepId) { "Entry effects require the current isolated tutorial lesson" }
        if (stepId in appliedSteps) return false
        when (stepId) {
            "POWER_SYSTEM_1" -> {
                val engines = game.player.engines ?: error("Tutorial ship has no engines")
                // Native ForceDecreaseSystemPower(engines) is called twice.
                repeat(2) { engines.decreasePower() }
            }
            "DAMAGE_SYSTEM_1" -> {
                val room = game.player.engines?.room ?: error("Tutorial ship has no engine room")
                // Native DamageArea has hull=2, no additional system/personnel,
                // fire/breach/ion/stun, and friendly fire enabled. Base hull
                // damage still contributes 2 system damage and 30 crew HP.
                game.player.damage(room, Damage.regular(2))
            }
            "DAMAGE_SYSTEM_2" -> addCrew(game)
            "FIRE_PC_1" -> {
                val room = game.player.rooms.singleOrNull { it.id == 9 } ?: error("Tutorial room 9 is missing")
                // 0x004a1ef0 calls 0x0076cc20(room, 2): random cells are sampled
                // with replacement, so two attempts can light the same cell.
                repeat(minOf(2, room.cellCount)) { room.spawnFire() }
            }
        }
        appliedSteps.add(stepId)
        BrowserDebug.event("tutorial", "entry.applied", "step", stepId)
        return true
    }

    private fun addCrew(game: InGameState) {
        val human = game.blueprintManager["human"] as? CrewBlueprint ?: error("Human blueprint is missing")
        val room = game.player.rooms.singleOrNull { it.id == 1 } ?: error("Tutorial crew room 1 is missing")
        val names = listOf("tutorial_extra_crew_1", "tutorial_extra_crew_2").map { key ->
            game.translator[key].also { check(!it.startsWith("could not find:")) { "Missing tutorial crew name: $key" } }
        }
        // Preflight the two spawns before consuming RNG or recruiting anyone.
        check((0 until room.cellCount).count { room.isSlotFree(room.slotToPoint(it), AbstractCrew.SlotType.CREW) } >= 2) {
            "Tutorial crew room has fewer than two free slots"
        }
        for ((index, name) in names.withIndex()) {
            val colour = if (human.baseNumberOfColours == 0) 0 else
                BrowserRandom.nextInt(human.baseNumberOfColours) + index * human.baseNumberOfColours
            // Native AddCrewMemberFromString explicitly receives roomId=1,
            // init=false, with male=true then false. Its setup path does not
            // report an ordinary career recruitment.
            game.player.addCrewMemberInRoom(LivingCrewInfo(game, human, name, colour), room, recordRecruitment = false)
        }
        room.updateCrewInRoom()
    }

    companion object {
        val SUPPORTED_STEPS = setOf("POWER_SYSTEM_1", "DAMAGE_SYSTEM_1", "DAMAGE_SYSTEM_2", "FIRE_PC_1")
    }
}
