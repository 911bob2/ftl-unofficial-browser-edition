package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room

class CrewRock(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val baseMaxHealth: Float get() = 150f
    override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * 0.5f
    override val fireFightingSpeed: Float get() = 2f
    override val fireDamageMult: Float get() = 0f
    // RockAnimation: the rock leaves on frame 3 of the throwing strip (FireShot 0x478870).
    override val throwsOnShootFrame: Boolean get() = true
}
