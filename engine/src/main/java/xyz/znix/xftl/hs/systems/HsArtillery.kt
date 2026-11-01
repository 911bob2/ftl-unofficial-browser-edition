// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/ArtillerySystem.cpp (targetable artillery),
// src/game/UserInterface/ArtilleryBarFix.cpp and src/game/UserInterface/AdditionalWeaponSlots.cpp (RenderAimingNew).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.Ship
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.SelectedTarget
import kotlin.math.floor
import kotlin.math.max

/**
 * Targetable artillery: with the ARTILLERY_ORDER augment (or `<targetableArtillery enabled="true">`) the player's
 * artillery charges as usual (cooldown modifier 1.5 - 0.25 * power) but fires only at a target the player picks with
 * the artillery button, like a weapon (armed slot -1); the ship's autofire keeps the target. Enemy artillery is
 * unchanged. Also the bar of ArtilleryBarFix.cpp (any power with an image, purple while hacked).
 */
object HsArtillery {
    const val AUGMENT = "ARTILLERY_ORDER"

    /** ArtillerySystem.cpp baseOffset: the button relative to the box's corner. */
    private const val BASE_X = 22
    private const val BASE_Y = -5

    private class Options(val enabled: Boolean, val x: Int, val y: Int, val fixedY: Boolean)

    private var parsedFor: HsConfig? = null
    private var options = Options(false, BASE_X, BASE_Y, false)

    /** ParseTargetableArtilleryNode: enabled, xOffset/yOffset added to the base offset, fixedYPos. */
    private fun options(): Options {
        val cfg = HS.cfg()
        if (parsedFor === cfg) return options
        parsedFor = cfg
        var enabled = false; var x = BASE_X; var y = BASE_Y; var fixed = false
        for (n in cfg.nodes["targetableArtillery"].orEmpty()) {
            n.getAttributeValue("enabled")?.let { enabled = HsConfig.bool(it) }
            n.getAttributeValue("xOffset")?.toIntOrNull()?.let { x += it }
            n.getAttributeValue("yOffset")?.toIntOrNull()?.let { y += it }
            n.getAttributeValue("fixedYPos")?.let { fixed = HsConfig.bool(it) }
        }
        options = Options(enabled, x, y, fixed)
        return options
    }

    /** The targetable mode is on for [ship] (the option or the augment). */
    @JvmStatic
    fun targetable(ship: Ship): Boolean = HS.active && (options().enabled || ship.hasAugment(AUGMENT))

    /** ArtillerySystem::OnLoop (priority 9998): the player's artillery runs OnLoop_HS_ManualTarget. */
    @JvmStatic
    fun manual(ship: Ship): Boolean = HS.active && ship.isPlayerShip && targetable(ship)

    private val aims = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Artillery, SelectedTarget>())

    @JvmStatic fun aim(a: Artillery): SelectedTarget? = aims[a]
    @JvmStatic fun setAim(a: Artillery, t: SelectedTarget) { aims[a] = t }
    @JvmStatic fun clearAim(a: Artillery) { aims.remove(a) }

    /**
     * OnLoop_HS_ManualTarget's checks before the factory fires: the aim is dropped when it points at a ship that is
     * gone, or (targetId 1) at an enemy that is not hostile.
     */
    @JvmStatic
    fun validAim(a: Artillery, ship: Ship): SelectedTarget? {
        val t = aims[a] ?: return null
        val enemy = ship.sys.getEnemyOf(ship)
        val ok = when {
            t.targetShip === ship -> true
            t.targetShip === enemy -> ship.sys.isEnemyHostile
            else -> false
        }
        if (!ok) { aims.remove(a); return null }
        return t
    }

    /** ProjectileFactory::autoFiring as WeaponControl::SetAutofiring sets it for the artillery: the ship's autofire. */
    @JvmStatic
    fun autofire(ship: Ship): Boolean = ship.weapons?.autofire == true

    /** ArtilleryBox::_HS_GetHeightModifier: the bar box moves up while the button shows (not with fixedYPos). */
    @JvmStatic
    fun heightModifier(ship: Ship): Int {
        val o = options()
        return if (!o.fixedY && targetable(ship)) 21 + (BASE_Y - o.y) else 0
    }

    /** The button's offset from the box corner: offset.y less 8 px per system level unless fixedYPos. */
    @JvmStatic
    fun buttonOffset(a: Artillery): Point {
        val o = options()
        return Point(o.x, if (o.fixedY) o.y else o.y - 8 * a.energyLevels)
    }

    private val hackColour = Colour(207, 70, 253)

    /**
     * ArtilleryBarFix: button_artillery_{power}.png (power at least 1) at the bar's corner and a 5 px bar from (9, 59)
     * upwards, floor(frac * 10 * (6 - power)) high, purple while a hacking pulse runs. No image for that power, no box.
     */
    @JvmStatic
    fun drawBar(game: InGameState, g: Graphics, power: Int, frac: Float, hacked: Boolean, boxX: Int, boxY: Int) {
        val p = max(1, power)
        game.getImgIfExists("img/systemUI/button_artillery_$p.png")?.draw(boxX, boxY)
        val barHeight = floor(frac * 10f * (6 - p)).toInt()
        g.colour = if (hacked) hackColour else Colour.white
        if (barHeight > 0) g.fillRect((boxX + 9).f, (boxY + 59 - barHeight).f, 5f, barHeight.f)
        else if (barHeight < 0) g.fillRect((boxX + 9).f, (boxY + 59).f, 5f, (-barHeight).f)
    }

    /** RenderAimingNew for the artillery (slot 100): the fallback crosshairs at the target, yellow with autofire. */
    @JvmStatic
    fun renderAims(target: Ship, g: Graphics) {
        val owner = target.sys.player ?: return
        if (!targetable(owner)) return
        for (a in owner.systems.filterIsInstance<Artillery>()) {
            val t = aims[a] ?: continue
            if (t.targetShip !== target) continue
            val yellow = autofire(owner)
            when (t) {
                is SelectedTarget.RoomAim -> {
                    val img = target.sys.getImg(if (yellow) "img/misc/crosshairs_placed_fallback_yellow.png" else "img/misc/crosshairs_placed_fallback.png")
                    val c = t.room.pixelCentre
                    img.draw(c.x - img.width / 2, c.y - img.height / 2)
                }
                is SelectedTarget.BeamAim -> target.renderTargetingBeam(g, t, yellow)
            }
        }
    }
}
