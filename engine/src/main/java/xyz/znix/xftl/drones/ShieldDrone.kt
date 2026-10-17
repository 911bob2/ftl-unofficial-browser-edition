package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint

class ShieldDrone(type: DroneBlueprint) : AbstractExternalDrone(type, false) {
    override val flightController = OrbitFlightController(this)
    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var glowImage: Image
    var rechargeRemaining = 8f
        private set
    private var chargingSoundPlayed = false
    private var glowTime = 0f
    private var restoredFromSave = false

    private fun cooldown() = COOLDOWNS[targetShip.superShield.coerceIn(0, 4)]

    // The shield drone slows down while it charges and stops while its glow
    // plays (SuperShieldDrone loop 0x428300: speed times max(0, charge - 1.5)
    // below 2.5 s of charge left, zero during the glow).
    override val orbitSpeedScale: Float
        get() = when {
            glowTime > 0f -> 0f
            rechargeRemaining < 2.5f -> (rechargeRemaining - 1.5f).coerceAtLeast(0f)
            else -> 1f
        }

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_off.png")
        onImage = game.getImg("img/ship/drones/${type.droneImage}_charged.png")
        glowImage = game.getImg("img/ship/drones/${type.droneImage}_glow.png")

        // SetDeployed (0x4272e0) starts a shield drone at its full recharge time
        // for the current super shield level. A drone loaded from a save keeps
        // the charge it was saved with (init runs after loadFromXML).
        if (!restoredFromSave)
            rechargeRemaining = cooldown()
    }

    override fun onPowerChanged() {
        if (!initialised || isPowered) return
        // Original OnLoop resets its weapon cooldown while powered off.
        rechargeRemaining = cooldown()
        chargingSoundPlayed = false
        glowTime = 0f
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (!isRunning || !targetShip.externalDrones.contains(this)) return
        rechargeRemaining -= dt
        glowTime = (glowTime - dt).coerceAtLeast(0f)
        if (rechargeRemaining < 1.5f && !chargingSoundPlayed) {
            chargingSoundPlayed = true
            glowTime = 3f
            game.sounds.getSample("shieldDroneCharge").play()
        }
        if (rechargeRemaining > 0f) return
        val before = targetShip.superShield
        targetShip.superShield++
        game.sounds.getSample("shieldDroneActivate").play()
        ftl.browser.BrowserDebug.record("drone", "shield.recharge", "{\"ship\":" +
            ftl.browser.BrowserDebug.quote(targetShip.type.name) + ",\"before\":$before,\"after\":${targetShip.superShield}}")
        rechargeRemaining = cooldown()
        chargingSoundPlayed = false
    }

    override fun onRender(g: Graphics) {
        drawCentred(if (isRunning) onImage else offImage)
        if (isRunning && glowTime > 0f) {
            val alpha = (if (glowTime > 1.5f) (3f - glowTime) / 1.5f else glowTime / 1.5f).coerceIn(0f, 1f)
            drawCentred(glowImage, Colour(1f, 1f, 1f, alpha * alpha))
        }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addAttrFloat(elem, "shieldRecharge", rechargeRemaining)
        SaveUtil.addAttrFloat(elem, "shieldGlow", glowTime)
        SaveUtil.addAttrBool(elem, "shieldChargingSound", chargingSoundPlayed)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)
        restoredFromSave = true
        rechargeRemaining = elem.getAttributeValue("shieldRecharge")?.toFloat() ?: 8f
        glowTime = elem.getAttributeValue("shieldGlow")?.toFloat() ?: 0f
        chargingSoundPlayed = elem.getAttributeValue("shieldChargingSound")?.toBoolean() ?: false
    }

    companion object {
        // SuperShieldDrone::GetWeaponCooldown 0x004217d0; table 0x00877180.
        private val COOLDOWNS = floatArrayOf(8f, 10f, 13f, 16f, 20f)
    }
}
