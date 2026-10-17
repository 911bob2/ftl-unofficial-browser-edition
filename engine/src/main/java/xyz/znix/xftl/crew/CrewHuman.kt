package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Image

class CrewHuman(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override fun drawImage(x0: Float, y0: Float, x1: Float, y1: Float, baseFrame: Image, alpha: Float) {
        // For humans, enemies always draw without colouring. The original
        // keeps the sex flag (+0x9c4) and the layer colour list (+0x9c8) on the
        // CrewAnimation, and CrewAnimation::SetupStrips (0x480dc0) reads both
        // when it builds the strips; the sex is still taken from the crew info.
        val skipColours = ownerShip?.isPlayerShip != true

        info.drawImage(x0, y0, x1, y1, baseFrame, alpha, skipColours)
    }
}
