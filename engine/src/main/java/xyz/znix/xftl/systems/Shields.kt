package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.max

class Shields(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.SHIELD
    override val minimumPoweredLevel: Int get() = 2

    val selectedShieldBars: Int get() = powerSelected / 2

    /**
     * The bubbles this system is actually running at: half the supplied
     * power, rounded down (a lone bar runs nothing). It drops below
     * [selectedShieldBars] when damage or a departing Zoltan takes power away,
     * popping the excess bubbles until it comes back.
     */
    val suppliedShieldBars: Int get() = powerSupplied / 2

    var activeShields: Int = 0
        set(value) {
            val old = field
            field = value.coerceIn(0..selectedShieldBars)

            if (value < old) {
                shieldsDownSound.play()
            }
        }

    var rechargeTimer: Float = 0f
        private set

    // Used for hacking - the charge bar runs backwards
    // and then drops a shield level when it hits zero.
    var discharging: Boolean = false
        private set

    val rechargeDelay: Float get() = 2f

    val hackDrainTime: Float get() = 2f

    private val shieldsUpSound by onInit { it.sounds.getSample("shieldsUp") }
    private val shieldsDownSound by onInit { it.sounds.getSample("shieldsDown") }

    override fun update(dt: Float) {
        super.update(dt)

        if (isHackActive) {
            updateHacked(dt)
            return
        }

        // Check if our power has been reduced - damage, or an ion storm
        // cutting the reactor's output.
        if (activeShields > selectedShieldBars) {
            activeShields = selectedShieldBars
        }
        if (activeShields > suppliedShieldBars) {
            activeShields = suppliedShieldBars
        }

        if (activeShields >= minOf(selectedShieldBars, suppliedShieldBars)) {
            rechargeTimer = 0f
            return
        }

        // Leave discharge mode immediately without
        // resetting the progress or anything like that.
        if (discharging) {
            discharging = false

            // If we were very, very close to discharging
            // this shield, assume it was an off-by-one-frame
            // error. Without this, a level-1 hack can't
            // take down two shields.
            if (rechargeTimer < dt * 2 + 0.02) {
                rechargeTimer = 0f
                activeShields--
            }
        }

        // Shields::OnLoop (0x6342b0): the charge speeds up by a sixth for
        // each layer beyond the first already up, times the charge booster
        // and the manning bonus.
        val boosterRate = 1f + ship.getAugmentValue(AugmentBlueprint.SHIELD_CHARGE_BOOSTER)
        val manningBonus = getBoostSkillLevel(Skill.SHIELDS)?.let { SKILL_BONUSES[it.ordinal] } ?: 0
        val manningRate = 1f + manningBonus / 100f
        val layerRate = 1f + (activeShields - 1).coerceAtLeast(0) / 6f
        val chargeRate = boosterRate * manningRate * layerRate

        rechargeTimer += dt * chargeRate
        if (rechargeTimer < rechargeDelay)
            return

        if (activeShields == 0) {
            shieldsUpSound.play()
        }

        rechargeTimer = 0f
        activeShields++
    }

    private fun updateHacked(dt: Float) {
        // Note that if we were recharging a shield layer,
        // then we have to undo that progress before we can
        // get started on the first bubble.
        // This happens (which is intended) because rechargeTimer
        // starts at a non-zero value in that case, and we have
        // to drain it before we switch to discharge mode.

        if (activeShields == 0) {
            rechargeTimer = 0f
            return
        }

        rechargeTimer = max(rechargeTimer - dt, 0f)
        if (rechargeTimer <= 0f && activeShields > 0) {
            rechargeTimer = hackDrainTime

            // This implements the above-mentioned system,
            // and is what sets rechargeTimer when we're
            // hacking fully-charged shields.
            if (!discharging) {
                discharging = true
                return
            }

            activeShields--

            // Without this, rechargeTimer would jump back to 2s for
            // one frame, which is very visible on the shield charge bar.
            if (activeShields == 0) {
                rechargeTimer = 0f
            }
        }
    }

    /**
     * Shields::InstantCharge (0x631870): every bubble the powered system can
     * run is up at once, and a Zoltan shield is topped up to five.
     *
     * The original only calls this through ShipManager::InstantPowerShields
     * (0x49d710), see [xyz.znix.xftl.Ship.instantPowerShields].
     */
    fun instantCharge() {
        discharging = false
        rechargeTimer = 0f
        activeShields = minOf(selectedShieldBars, suppliedShieldBars)
        if (ship.hasAugment(AugmentBlueprint.ENERGY_SHIELD_NAME)) {
            ship.superShield = 5
        }
    }

    fun popShieldLayer() {
        if (activeShields == 0)
            return

        activeShields--

        // The shield skill is trained from Ship.skillEvents (event 8), which
        // Ship.damage raises when a bubble is lost; see Ship.checkSkills.
    }

    override fun increasePower() {
        if (isPowerLocked)
            return

        setSystemPower(selectedShieldBars * 2 + 2)
        targetPower = powerSelected
    }

    override fun decreasePower() {
        if (isPowerLocked)
            return

        // Clamp the power at 0, in case we have an odd number of power bars.
        setSystemPower((selectedShieldBars * 2 - 2).coerceAtLeast(0))
        targetPower = powerSelected
    }

    override fun updateCachedSelectedPower() {
        super.updateCachedSelectedPower()

        // Shield bars are only ever selected in whole bubbles. The cached
        // demand can still end up odd - damage clamping the allocation, or
        // the accounting around a Zoltan bar arriving or leaving - and an unpaired bar would keep drawing power while
        // doing nothing, since half a bubble primes nothing. Round down to
        // the whole-bubble count.
        // (ShipSystem::CheckForRepower1 0x63ac40 leaves the odd total a battery or Zoltan loss produced as it is.)
        // A Zoltan bar that came on top of an even reactor share is the odd one, and natively it stays (SetBonusPower
        // 0x63b180 leaves the reactor power alone while it fits).
        if (powerSelected.mod(2) == 1 && powerSelected != keptPowerAfterLoss && bonusOnTop == 0)
            powerSelected -= 1
    }

    /**
     * CheckForRepower1 (0x63ac40) repowers shields with a step of 1 from an odd total and 2 from an even one, and
     * stops when the room under the cap is smaller than the step. With an odd cap (a damaged level) it therefore
     * stops one short, at the last even total, and never takes the bar that would sit idle.
     */
    override fun repowerReach(requested: Int): Int {
        if (requested >= undamagedEnergy && undamagedEnergy % 2 == 1)
            return (undamagedEnergy - 1).coerceAtLeast(0)
        return requested
    }

    override fun getPowerBarSpacing(powerLevel: Int): Int {
        // Group the power bars into pairs
        if (powerLevel.mod(2) == 1) {
            return 6
        }
        return 2
    }

    override fun dealDamage(damage: Int, ionDamage: Int) {
        super.dealDamage(damage, ionDamage)

        // If the player shoots a beam from shields to another room, the shields
        // should go down immediately. We can't afford to wait for the next
        // update, in case the frame-time is too high as the user could hit
        // another room on the same frame.
        if (activeShields > selectedShieldBars) {
            activeShields = selectedShieldBars
        }
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.SHIELDS))
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrInt(elem, "active", activeShields)
        SaveUtil.addTagFloat(elem, "rechargeTimer", rechargeTimer, 0f)
        SaveUtil.addTagBoolIfTrue(elem, "discharging", discharging)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        activeShields = SaveUtil.getAttrInt(elem, "active")
        rechargeTimer = SaveUtil.getOptionalTagFloat(elem, "rechargeTimer") ?: 0f
        discharging = SaveUtil.getOptionalTagBool(elem, "discharging") ?: false
    }

    companion object {
        val INFO: SystemInfo = ShieldsInfo
        val SKILL_BONUSES = listOf(10, 20, 30)
    }
}

private object ShieldsInfo : SystemInfo("shields") {
    override val canBeManned: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Shields(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0, 2, 4, 6 -> ""
            1 -> translator["shields_0"]
            3 -> translator["shields_1"]
            5 -> translator["shields_2"]
            7 -> translator["shields_3"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}
