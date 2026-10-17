package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.layout.Room

class CrewCrystal(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val suffocationMultiplier: Float
        get() {
            val oxygenMasks = getAugmentValue(AugmentBlueprint.OXYGEN_MASKS) ?: 1f
            return 0.5f * oxygenMasks
        }

    override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * 0.8f
    override val baseMaxHealth: Float get() = 125f
    override val fireFightingSpeed: Float get() = 1f

    /**
     * The lockdown power charge and the charge it needs (CrystalAlien +0x75c
     * / +0x760). The charge grows with time up to the maximum, and the
     * power is ready once it gets there; it's spent in full on use.
     */
    var lockdownCharge: Float = 0f
        private set
    var lockdownChargeMax: Float = LOCKDOWN_COOLDOWN
        private set

    /** Whether the native enemy start-up roll has been done (or restored from a save). */
    private var enemyPowerInit = false

    val lockdownReady: Boolean get() = lockdownChargeMax <= lockdownCharge

    /** The charge still missing until the power is ready (zero when it is). */
    val lockdownCooldown: Float get() = kotlin.math.max(0f, lockdownChargeMax - lockdownCharge)

    /**
     * CrystalAlien's constructor (0x487e70) for a crystal of the enemy ship:
     * it starts with 10 seconds of charge, and a coin flip decides whether it
     * ever gets a charge target (otherwise the target is FLT_MAX, so the
     * enemy never reaches it).
     */
    fun initEnemyPower(neverReady: Boolean) {
        enemyPowerInit = true
        lockdownCharge = ENEMY_START_CHARGE
        lockdownChargeMax = if (neverReady) Float.MAX_VALUE else LOCKDOWN_COOLDOWN
    }

    /** CrystalAlien::ResetPower (0x75ae70): fully charged. */
    fun resetLockdown() { lockdownCharge = lockdownChargeMax }

    /** The player pressing the lockdown button or hotkey. */
    fun activateLockdown(): Boolean {
        if (!playerControllable || health <= 0f || stunTime > 0f) return false
        return fireLockdown()
    }

    /**
     * The crystal's special power (0x47ef40): it only fires with a full
     * charge, which it spends in full. A lockdown shard is made in the
     * crystal's room and the lockdown sound is one of two at random.
     */
    private fun fireLockdown(): Boolean {
        if (lockdownCharge != lockdownChargeMax) return false
        lockdownCharge = 0f
        room.lockdown()
        game.sounds.getSampleOrWarn("lockdown${1 + ftl.browser.BrowserRandom.nextInt(2)}")?.play()
        return true
    }

    /**
     * CrewMember::OnLoop (0x482a80) uses the power by itself for a crystal of
     * the enemy ship (the ship id is not the player's) that is in a fight:
     * alive, with more than half its health and the power ready, in a room
     * that isn't already locked down.
     */
    override fun onFighting() {
        if (ownerShip?.isPlayerShip == true || health <= 0f) return
        if (health <= maxHealth * 0.5f || !lockdownReady || room.isLockedDown) return
        fireLockdown()
    }

    override fun update(dt: Float) {
        if (!enemyPowerInit) {
            // A crystal that wasn't made by the ship generator, such as an
            // event boarder.
            enemyPowerInit = true
            if (ownerShip?.isPlayerShip != true)
                initEnemyPower(ftl.browser.BrowserRandom.nextInt(2) == 0)
        }

        super.update(dt)

        // CrystalAlien::OnLoop (0x483cc0): the charge grows after the common loop.
        lockdownCharge = kotlin.math.min(lockdownCharge + dt, lockdownChargeMax)
    }
    override fun saveToXML(elem: org.jdom2.Element, refs: xyz.znix.xftl.savegame.ObjectRefs) {
        super.saveToXML(elem, refs)
        elem.setAttribute("browserLockdownCharge", lockdownCharge.toString())
        elem.setAttribute("browserLockdownMax", lockdownChargeMax.toString())
    }
    override fun loadFromXML(elem: org.jdom2.Element, refs: xyz.znix.xftl.savegame.RefLoader) {
        super.loadFromXML(elem, refs)
        enemyPowerInit = true
        val charge = elem.getAttributeValue("browserLockdownCharge")?.toFloat()
        if (charge != null) {
            lockdownCharge = charge
            lockdownChargeMax = elem.getAttributeValue("browserLockdownMax")?.toFloat() ?: LOCKDOWN_COOLDOWN
        } else {
            // Older saves stored the time left until the power was ready.
            val remaining = elem.getAttributeValue("browserLockdownCooldown")?.toFloat() ?: 0f
            lockdownChargeMax = LOCKDOWN_COOLDOWN
            lockdownCharge = LOCKDOWN_COOLDOWN - remaining.coerceIn(0f, LOCKDOWN_COOLDOWN)
        }
    }

    companion object {
        /** The charge the power needs (CrystalAlien +0x760 is set to 50.0 by the constructor). */
        const val LOCKDOWN_COOLDOWN = 50f
        private const val ENEMY_START_CHARGE = 10f
    }
}
