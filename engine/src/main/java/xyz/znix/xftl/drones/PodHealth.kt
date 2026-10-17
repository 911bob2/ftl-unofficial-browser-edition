package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.IProjectile
import xyz.znix.xftl.weapons.ProjectileContact
import kotlin.math.floor
import kotlin.math.max

/**
 * The SpaceDrone hit rules for a drone pod that is flying towards its target
 * (the boarder pod and the hacking pod), which the original handles with the
 * same code as the drones that orbit a ship.
 *
 * - SpaceDrone::CollisionMoving (0x420c90): a dodge roll for every shot in
 *   range, of which only a powered drone makes use; ion damage stuns a
 *   powered drone for five seconds per point; hull damage blows it up.
 * - SpaceDrone::BlowUp (0x4215f0): the pod freezes, shows an explosion and
 *   only goes away when the animation has finished (0x428010).
 * - Drone::OnLoop (0x462220): a stunned drone rolls rand%20 < 3 each time
 *   its stun time passes a whole second beyond two, and blows up on a hit.
 */
class PodHealth(private val game: InGameState, private val dodge: Int) {
    var ionTimer: Float = 0f
        private set

    private var stunTotalTimer: Float = 0f

    private var explosion: FTLAnimation? = null

    val isBlowingUp: Boolean get() = explosion != null

    /**
     * Resolve a shot touching the pod (HackingDrone::CollisionMoving 0x455bb0
     * and BoarderPodDrone's 0x4213d0 both defer to SpaceDrone's).
     *
     * The pod itself is never removed by this, see [update].
     */
    fun contact(
        proj: IProjectile,
        owner: Ship,
        space: Ship,
        position: FPoint,
        powered: Boolean
    ): ProjectileContact {
        if (isBlowingUp)
            return ProjectileContact.IGNORED

        // Ships don't shoot down their own drones, and only shots that
        // carry a weapon's effect can hit a drone.
        if (proj.antiDroneExemption == owner)
            return ProjectileContact.IGNORED
        val weapon = proj.antiDroneBP ?: return ProjectileContact.IGNORED

        val dodged = ftl.browser.BrowserRandom.nextInt(10) < dodge
        if (dodged && powered) {
            space.showDamageTextAt(position, "text_miss", Colour.white)
            return ProjectileContact.IGNORED
        }

        if (weapon.ionDamage > 0) {
            if (powered)
                ionTimer = 5f * weapon.ionDamage
            return ProjectileContact.SURVIVED
        }

        if (weapon.damage > 0)
            blowUp()

        return ProjectileContact.SURVIVED
    }

    fun blowUp() {
        if (explosion != null)
            return

        explosion = game.animations["explosion_random"].startSingle(game)
        game.sounds.getSample("smallExplosion").play()
    }

    /**
     * @param ownerStunned True if the pod's owner has had its drone system
     * hacked, which stuns every drone it has out.
     * @param powered False while the pod is unpowered (Drone::OnLoop skips it).
     * @return True on the update where the explosion animation finishes.
     */
    fun update(dt: Float, ownerStunned: Boolean, powered: Boolean = true): Boolean {
        // SpaceDrone::OnLoop (0x421250) runs Drone::OnLoop's roll first and only then counts the ion timer down, so
        // a five second stun still rolls as it passes five; an unpowered pod does not roll.
        if (powered && (ownerStunned || ionTimer > 0f)) {
            val previous = stunTotalTimer
            stunTotalTimer += dt
            if (floor(stunTotalTimer) > floor(previous) && stunTotalTimer > 2f &&
                ftl.browser.BrowserRandom.nextInt(20) < 3
            ) {
                blowUp()
            }
        } else {
            stunTotalTimer = 0f
        }
        ionTimer = max(0f, ionTimer - dt)

        val explosion = explosion ?: return false
        explosion.update(dt)
        if (explosion.isStopped) {
            this.explosion = null
            return true
        }
        return false
    }

    /**
     * Draw the explosion centred on the origin, if there is one.
     *
     * @return True if the pod should not draw itself.
     */
    fun renderExplosion(): Boolean {
        val frame = explosion?.currentFrame ?: return false
        frame.draw(-(frame.width / 2).toFloat(), -(frame.height / 2).toFloat())
        return true
    }

    fun saveToXML(elem: Element) {
        SaveUtil.addTagFloat(elem, "podIonTimer", ionTimer, 0f)
        SaveUtil.addTagFloat(elem, "podStunTimer", stunTotalTimer, 0f)
        explosion?.let { SaveUtil.addTagFloat(elem, "podExplosion", it.timer) }
    }

    fun loadFromXML(elem: Element) {
        ionTimer = SaveUtil.getOptionalTagFloat(elem, "podIonTimer") ?: 0f
        stunTotalTimer = SaveUtil.getOptionalTagFloat(elem, "podStunTimer") ?: 0f
        SaveUtil.getOptionalTagFloat(elem, "podExplosion")?.let { timer ->
            explosion = game.animations["explosion_random"].startSingle(game).also { it.timer = timer }
        }
    }
}
