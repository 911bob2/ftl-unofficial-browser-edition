package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader

class Engines(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.ENGINES

    val evasion: Int
        get() {
            if (powerSupplied == 0)
                return 0

            // EngineSystem dodge (0x63f8e0): the table (0x885ee0) covers the
            // eight power levels; a ninth (battery) level adds nothing to it.
            val base = EVASIONS.getOrNull(powerSupplied - 1) ?: 0
            val bonus = getBoostSkillLevel(Skill.ENGINES)?.let { SKILL_BONUSES[it.ordinal] } ?: 0

            return base + bonus
        }

    /**
     * EngineSystem's FTL charge per speed-factor unit (0x63f580):
     * (FTL_BOOSTER + 1) * ((power - 1) * 0.35 + 1.25) / 16, tripled by the
     * hostile-environment boost, times 1.1/1.17/1.25 when manned, at most
     * 1.2. The speed factor is 16 per second and the drive fills at 85.
     */
    fun ftlChargePerUnit(boost: Boolean): Float {
        if (powerSupplied == 0)
            return 0f

        val booster = ship.getAugmentValue(AugmentBlueprint.FTL_BOOSTER)
        var rate = ((powerSupplied - 1) * 0.35f + 1.25f) * 0.0625f * (booster + 1f)
        if (boost)
            rate *= 3f
        getBoostSkillLevel(Skill.ENGINES)?.let { rate *= MANNED_FTL_RATES[it.ordinal] }
        return rate.coerceAtMost(1.2f)
    }

    val evasionMultiplier: Float
        get() {
            // If engines is off or hacked, you get no evasion
            if (powerSupplied == 0 || isHackActive)
                return 0f

            return 1f
        }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.ENGINES))
    }

    // Nothing to serialise
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    companion object {
        // ShipSystem::GetEngineDodgeFactor (0x63d970) reads the dodge table
        // (0x885ee0), indexed by effective power - 1.
        val EVASIONS: Array<Int> = arrayOf(5, 10, 15, 20, 25, 28, 31, 35)
        private val MANNED_FTL_RATES = listOf(1.1f, 1.17f, 1.25f)

        /** ShipManager's FTL charge target (jump timer second, 85). */
        const val FTL_CHARGE_MAX = 85f
        val SKILL_BONUSES = listOf(5, 7, 10)

        val INFO: SystemInfo = EngineInfo
    }
}

private object EngineInfo : SystemInfo("engines") {
    override val canBeManned: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Engines(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val evasion = Engines.EVASIONS[level]

        // Note the visible charge rate is wrong!
        val chargeRate = 100 + 25 * level
        val chargeRateStr = UIUtils.formatStringFTL(chargeRate)

        return translator["engine"].replaceArg(evasion).replaceArg(chargeRateStr, 2)
    }
}
