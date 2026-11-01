// HS extensions ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDrones.cpp.
// Shared native loop rules: FTL 1.6.14 0x428010/0x428300, FTL 1.6.9 0x426a50/0x426db0.
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
    /** Hyperspace Lua: SuperShieldDrone::drone_image_on, off, glow written by a script (0, 1, 2). */
    fun hsSetImage(slot: Int, image: Image) { when (slot) { 0 -> onImage = image; 1 -> offImage = image; 2 -> glowImage = image } }

    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var glowImage: Image
    var rechargeRemaining = 8f
        internal set
    private var chargingSoundPlayed = false
    internal var glowTime = 0f
    private var restoredFromSave = false
    internal var shieldOrbitSpeedScale = 1f
    private val hsDefinition get() = xyz.znix.xftl.hs.weapons.HsShieldDrones.definition(type.name)

    internal fun cooldown(): Float = cooldownFor(type, targetShip)

    // DefenseDrone moves using the speed from the preceding SuperShieldDrone loop (native 1.6.9
    // 0x426a50/0x426db0, 1.6.14 0x428010/0x428300). Keep it across frames, including while stunned.
    override val orbitSpeedScale: Float get() = shieldOrbitSpeedScale

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

    override fun update(dt: Float) {
        super.update(dt)
        updateLastFrameFlags(dt)
        if (xyz.znix.xftl.hs.HS.active) {
            val def = hsDefinition
            updateShield(dt, def.slowDuration, def.pulseDuration, def.chargeSound, def.activateSound, def.layers)
        } else {
            updateShield(dt, 1f, 1.5f, "shieldDroneCharge", "shieldDroneActivate", 1)
        }
    }

    // Native DefenseDrone::OnLoop then SuperShieldDrone::OnLoop: fire strictly below zero (not at zero),
    // normalize a ready cooldown to -1, and keep the glow advancing while ion-stunned. SetPowered only changes
    // the powered flag; the next OnLoop resets charge/glow while off. Native 1.6.14 0x428010/0x428300;
    // HS 1b05a07 CustomDrones.cpp substitutes the definition's values after native 1.6.9 DefenseDrone 0x426a50.
    private fun updateShield(dt: Float, slowDuration: Float, pulseDuration: Float,
                             chargeSound: String, activateSound: String, layers: Int) {
        if (!isPowered || !targetShip.externalDrones.contains(this)) {
            rechargeRemaining = cooldown()
            glowTime = -1f
            chargingSoundPlayed = false
            return
        }
        var fire = false
        if (isRunning) {
            rechargeRemaining -= dt
            if (rechargeRemaining < 0f) {
                rechargeRemaining = -1f
                fire = true
            }
        }
        shieldOrbitSpeedScale = 1f
        if (rechargeRemaining < pulseDuration && glowTime <= 0f) {
            glowTime = 3f
            if (chargeSound.isNotEmpty()) game.sounds.getSampleOrWarn(chargeSound)?.play()
        }
        if (rechargeRemaining < slowDuration + pulseDuration) {
            shieldOrbitSpeedScale = ((rechargeRemaining - pulseDuration) / slowDuration).coerceAtLeast(0f)
        }
        if (glowTime > 0f) shieldOrbitSpeedScale = 0f
        glowTime -= dt * (1.5f / pulseDuration)
        if (glowTime < 0f) glowTime = -1f
        if (!fire) return

        val before = targetShip.superShield
        // HS guards the native shieldSystem pointer before charging (ships may omit shields entirely).
        if (!xyz.znix.xftl.hs.HS.active || xyz.znix.xftl.hs.systems.HsSuperShield.hasShieldObject(targetShip)) {
            if (!(xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.systems.HsSuperShield.addSuperShield(targetShip)))
                targetShip.superShield++
            if (xyz.znix.xftl.hs.HS.active)
                targetShip.superShield = (targetShip.superShield + layers - 1).coerceIn(0, targetShip.maxSuperShield)
        }
        // Shields::AddSuperShield: the new shield opens from the drone's place.
        targetShip.shields?.let { s -> s.superUpTracker.start(); s.superUpX = flightController.position.xf.toInt(); s.superUpY = flightController.position.yf.toInt(); s.hsOut() }
        if (activateSound.isNotEmpty()) game.sounds.getSampleOrWarn(activateSound)?.play()
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
        /** SuperShieldDrone::GetWeaponCooldown (0x4217d0): the recharge time by the ship's super-shield layers. */
        fun cooldownFor(type: DroneBlueprint, ship: xyz.znix.xftl.Ship): Float {
            if (xyz.znix.xftl.hs.HS.active) {
                val table = xyz.znix.xftl.hs.weapons.HsShieldDrones.definition(type.name).cooldowns
                if (table.isNotEmpty()) return table[ship.superShield.coerceAtMost(table.lastIndex)]
            }
            return COOLDOWNS[ship.superShield.coerceIn(0, 4)]
        }

        // SuperShieldDrone::GetWeaponCooldown 0x004217d0; table 0x00877180.
        private val COOLDOWNS = floatArrayOf(8f, 10f, 13f, 16f, 20f)
    }
}
