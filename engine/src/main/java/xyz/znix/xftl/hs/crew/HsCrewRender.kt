// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewMember::OnRender,
// CrewMember::OnRenderHealth, CrewAnimation::OnRender hooks), src/game/Gameplay/World/CrewAbilities.cpp
// (PrepareTemporaryAnimation: tempEffectStrip), src/game/Ship/StatBoost.cpp (the per-crew boostAnim map).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.stat.StatBoostDefinition
import xyz.znix.xftl.hs.stat.StatBoostManager
import xyz.znix.xftl.rendering.Colour

/**
 * What Hyperspace draws on a crew member of a race: the temporary-power strips over (or instead of) the body, the
 * temporary effect and stat boost animations centred on it, and the power effect animations around the health bar.
 */
object HsCrewRender {
    /** The strips of the active temporary powers (last power first) and whether the base body is still drawn. */
    class Strips(@JvmField val baseVisible: Boolean, @JvmField val sheets: List<String>)

    /** CrewAnimation::OnRender: a null result means no active temporary power has a strip. */
    @JvmStatic
    fun strips(crew: AbstractCrew): Strips? {
        val ex = crew.hsExtend ?: return null
        if (ex.crewPowers.isEmpty()) return null
        var baseVisible = true
        var sheets = ArrayList<String>()
        var any = false
        for (i in ex.crewPowers.indices.reversed()) {
            val p = ex.crewPowers[i]
            if (!p.temporaryPowerActive) continue
            if (!p.def.tempPower.baseVisible) { baseVisible = false; sheets = ArrayList() }
            if (p.def.tempPower.animSheet.isNotEmpty()) { sheets.add(p.def.tempPower.animSheet); any = true }
        }
        return if (any || !baseVisible) Strips(baseVisible, sheets) else null
    }

    /**
     * The crew are drawn 3 px below their positions (AbstractCrew.screenY): CrewMember::OnRender's and OnRenderHealth's
     * animations translate by the crew member's x, y inside that (oracle sw5-pow: the free mantis's pheromones_f 3 px
     * under x, y - 16 at the same frame; sw4-skill's level-up icon).
     */
    private const val RENDER_DY = 3f

    /** CrewMember::PositionShift: the vertical offset of a crew member sharing a cell with an enemy. */
    private fun shift(crew: AbstractCrew): Float = crew.hsPositionShift.toFloat()

    /** CrewMember::OnRender (after the body): temporary effect animations, then stat boost animations. */
    @JvmStatic
    fun drawBody(crew: AbstractCrew) {
        val ex = crew.hsExtend ?: return
        val cx = crew.hsCentreX
        val cy = crew.hsCentreY + shift(crew) + RENDER_DY
        for (p in ex.crewPowers) {
            val a = p.tempEffectAnim ?: continue
            if (!a.isStopped) a.draw(cx - a.width / 2f, cy - a.height / 2f)
        }
        for (a in ex.boostAnims.values) {
            if (!a.isStopped) a.draw(cx - a.width / 2f, cy - a.height / 2f)
        }
    }

    /** CrewMember::OnRenderHealth (before the health bar): power effect, finish and extra animations. */
    @JvmStatic
    fun drawForeground(crew: AbstractCrew) {
        val ex = crew.hsExtend ?: return
        for (p in ex.crewPowers) {
            p.effectAnim?.let { if (!it.isStopped) it.draw(p.effectPosX, p.effectPosY + RENDER_DY) }
            p.effectFinishAnim?.let {
                if (!it.isStopped) it.draw(crew.hsCentreX - it.width / 2f, crew.hsCentreY - it.height / 2f + RENDER_DY)
            }
        }
    }

    /**
     * CrewMember::UpdateHealth's boost animation upkeep (StatBoost.cpp): each stat boost with a boostAnim that applies
     * to the crew member (BoostCheck; timed boosts always) has one looping animation, which goes with the boost.
     */
    @JvmStatic
    fun updateBoostAnims(ex: CrewExtend, dt: Float) {
        val game = ex.crew.room.ship.sys
        val keep = HashSet<StatBoostDefinition>()
        fun want(def: StatBoostDefinition) {
            keep.add(def)
            if (ex.boostAnims.containsKey(def)) return
            val spec = game.animations.getOrNull(def.boostAnim)
            if (spec == null) { xyz.znix.xftl.hs.HS.unsupported("anim", "stat boost", def.boostAnim); return }
            ex.boostAnims[def] = spec.startLooping(game)
        }
        for (b in StatBoostManager.animBoosts) if (ex.boostCheck(b)) want(b.def)
        for (list in ex.timedStatBoosts.values) for (b in list) if (b.def.boostAnim.isNotEmpty()) want(b.def)
        ex.boostAnims.keys.retainAll(keep)
        for (a in ex.boostAnims.values) a.update(dt)
    }
}
