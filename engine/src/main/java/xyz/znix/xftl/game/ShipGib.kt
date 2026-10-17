package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.VisualRandom
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import kotlin.math.cos
import kotlin.math.sin

class ShipGib(val ship: ShipBlueprint, node: Element) {
    val imgPath: String
    val imgPathGlow: String

    val velocityRange = parseRange(node.getChild("velocity"))
    val directionRange = parseRange(node.getChild("direction"))
    val angularVelocityRange = parseRange(node.getChild("angular"))
    val offset = ConstPoint(node.getChildTextTrim("x").toInt(), node.getChildTextTrim("y").toInt())

    init {
        imgPath = "img/ship/${ship.img}_${node.name}.png"
        imgPathGlow = "img/ships_glow/${ship.img}_${node.name}.png"
    }

    fun createInstance(game: InGameState): Instance {
        return Instance(game.getImgIfExists(imgPath) ?: game.getImg(imgPathGlow))
    }

    companion object {
        private fun parseRange(elem: Element): ClosedFloatingPointRange<Float> {
            val min = elem.getAttributeValue("min").toFloat()
            val max = elem.getAttributeValue("max").toFloat()
            return min..max
        }

        /** A random value in [range] in steps of 1/1000, as ExplosionAnimation::OnInit draws it. */
        private fun randomMillis(range: ClosedFloatingPointRange<Float>): Float {
            val min = (range.start * 1000).toInt()
            val max = (range.endInclusive * 1000).toInt()
            return (VisualRandom.nextInt((max + 1 - min).coerceAtLeast(1)) + min) / 1000f
        }
    }

    /**
     * This represents a gib used on a ship. In contrast to the parent class,
     * this isn't referenced by [ShipBlueprint] and thus can have mutable variables.
     *
     * ExplosionAnimation::OnInit (0x4927a0): the speed is velocity x 2.5 and the spin
     * angular x 0.5 degrees per 1/16 s; the heading is 270 - direction degrees (0 is
     * up, 90 left). ExplosionAnimation::OnRender (0x488e80) turns the gib about its
     * centre before the hull image offset is applied, so the pivot sits that offset
     * away from the gib.
     */
    inner class Instance(private val img: Image) {
        private val speed = randomMillis(velocityRange) * 2.5f
        private val heading = Math.toRadians(
            270.0 - (VisualRandom.nextInt(
                (directionRange.endInclusive.toInt() + 1 - directionRange.start.toInt()).coerceAtLeast(1)
            ) + directionRange.start.toInt())
        )
        private val spin = randomMillis(angularVelocityRange) * 0.5f

        private var moveX = 0f
        private var moveY = 0f
        private var angle = 0f

        /** [hullOffset] is where the hull image is drawn, [imageOffset] the layout's img offset within it. */
        fun draw(g: Graphics, hullOffset: IPoint, imageOffset: IPoint) {
            val cx = img.width / 2f
            val cy = img.height / 2f
            g.pushTransform()
            g.translate(
                hullOffset.x - imageOffset.x + offset.x + moveX + cx,
                hullOffset.y - imageOffset.y + offset.y + moveY + cy
            )
            g.rotate(0f, 0f, angle)
            g.translate(imageOffset.x - cx, imageOffset.y - cy)
            img.draw(0f, 0f)
            g.popTransform()
        }

        fun update(dt: Float) {
            val units = dt * 16f
            moveX += (cos(heading) * speed).toFloat() * units
            moveY += (sin(heading) * speed).toFloat() * units
            angle += spin * units
        }
    }
}
