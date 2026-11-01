package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.render.HsMoreInfo
import xyz.znix.xftl.hs.render.HsTooltips
import xyz.znix.xftl.hs.stat.CrewStat

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp
// (CrewMember::GetTooltip, ShipManager::GetTooltip priority 9999)
/** Hyperspace's crew hover tooltips: `advancedCrewTooltips` and `insertNewlineForMultipleCrewTooltips`. */
object HsCrewTooltips {
    /** ShipManager::GetTooltip rewrite: " \n\n" between two crew members with insertNewlineForMultipleCrewTooltips. */
    @JvmStatic
    fun separator(): String = if (HS.active && HS.config?.insertNewlineForMultipleCrewTooltips == true) " \n\n" else " \n"

    @JvmStatic
    fun advanced(): Boolean = HS.active && HS.config?.advancedCrewTooltips == true

    /**
     * CrewMember::GetTooltip with advancedCrewTooltips: "-name (race):", the health line (advanced_health_tooltip;
     * death_tooltip at 0 health; `rounding` decimals while hurt), the time left of a limited-life crew member
     * (crew_death_time / crew_death_time_expired), the stun time scaled by STUN_MULTIPLIER (crew_stun_time) and,
     * with the `enemy` / `ally` attributes or the More Info mode (MoreInfoButton.cpp), the race's powers as
     * "*power" lines. Trailing whitespace is trimmed.
     */
    @JvmStatic
    fun text(game: InGameState, crew: AbstractCrew): String {
        val tr = game.translator
        val living = crew as? LivingCrew
        val ex = HsCrew.ex(crew)
        val sb = StringBuilder()
        val name = living?.info?.name ?: ""
        val race = living?.info?.race?.translateTitle(game) ?: ""
        sb.append('-').append(name).append(" (").append(race).append("):").append('\n')

        val h = crew.health
        // The More Info mode (altMode, on by default) raises the rounding to 2 and shows every crew member's powers.
        val rounding = HsMoreInfo.advancedCrewTooltipRounding()
        val maxHealth = crew.maxHealth.toInt()
        val label = tr["advanced_health_tooltip"] + ": "
        when {
            h == 0f -> sb.append(label).append(tr["death_tooltip"])
            h == crew.maxHealth -> sb.append(label).append("$maxHealth/$maxHealth (100%)")
            rounding == 0 ->
                sb.append(label).append(h.toInt()).append('/').append(maxHealth).append(" (").append((h / maxHealth * 100f).toInt()).append("%)")
            else -> sb.append(label).append(HsTooltips.fixed(h.toDouble(), rounding)).append('/')
                .append(maxHealth).append(" (").append((h / maxHealth * 100f).toInt()).append("%)")
        }

        ex.deathTimer?.let { t ->
            sb.append('\n')
            val left = t.currGoal - t.currTime
            if (left > 0f) sb.append(tr["crew_death_time"].replace("\\1", HsTooltips.fixed(left.toDouble(), 1)))
            else sb.append(tr["crew_death_time_expired"])
        }

        if (crew.stunTime != 0f) {
            sb.append('\n')
            val stun = crew.stunTime * ex.calculateStat(CrewStat.STUN_MULTIPLIER, ex.definition())
            sb.append(tr["crew_stun_time"].replace("\\1", HsTooltips.fixed(stun.toDouble(), 1)))
        }

        val shipId = HsCrew.shipId(crew)
        if ((HsMoreInfo.showEnemyPowers() && shipId == 1) || (HsMoreInfo.showAllyPowers() && shipId == 0)) {
            sb.append('\n')
            for (power in living?.info?.race?.powerStringIds ?: emptyList()) sb.append('*').append(tr[power]).append('\n')
            trimRight(sb)
        }
        sb.append('\n')
        trimRight(sb)
        return sb.toString()
    }

    /**
     * CrewMember::GetTooltip without the option (0x47f6e0): the name, then health_tooltip with the health rounded
     * down (at least 1) and the maximum, or death_tooltip while dying.
     */
    @JvmStatic
    fun vanillaText(game: InGameState, crew: AbstractCrew): String {
        val sb = StringBuilder()
        val name = (crew as? LivingCrew)?.info?.name ?: ""
        if (name.isNotEmpty()) sb.append(name).append(" \n")
        if (crew.currentAction == AbstractCrew.Action.DYING) sb.append(game.translator["death_tooltip"])
        else sb.append(game.translator["health_tooltip"].replace("\\1", maxOf(1, crew.health.toInt()).toString())
            .replace("\\2", crew.maxHealth.toInt().toString()))
        return sb.toString()
    }

    /** boost::trim_right: std::isspace in the "C" locale. */
    private fun trimRight(sb: StringBuilder) {
        while (sb.isNotEmpty() && sb[sb.length - 1].let { it == ' ' || it in '\t'..'\r' }) sb.setLength(sb.length - 1)
    }
}
