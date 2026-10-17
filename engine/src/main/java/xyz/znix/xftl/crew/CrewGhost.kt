package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Image

/**
 * The ghost boarders of BOARDERS_ASTEROID_GHOST and GHOST_STAND. The DAT
 * has no ghost blueprint or art: the native crew factory (0x4685a0) builds
 * a plain CrewMember with 50 health and the Ghost vtable (CanSuffocate
 * false), and CrewAnimation (0x483d70) renders the human strips without
 * colour layers at half opacity, dying with "ghost_death".
 */
class CrewGhost(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {
    override val baseMaxHealth: Float get() = 50f
    override val suffocationMultiplier: Float get() = 0f

    override val animationRace: String get() = "human"
    override val deathAnimationName: String get() = "ghost_death"

    override fun drawImage(x0: Float, y0: Float, x1: Float, y1: Float, baseFrame: Image, alpha: Float) {
        // CrewAnimation::OnRender uses 0.5 (DAT_00879120) as the ghost's alpha.
        super.drawImage(x0, y0, x1, y1, baseFrame, alpha * GHOST_ALPHA)
    }

    companion object {
        const val GHOST_ALPHA = 0.5f
    }
}
