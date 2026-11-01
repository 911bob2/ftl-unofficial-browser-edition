// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CooldownNumbers.cpp
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import kotlin.math.abs
import kotlin.math.floor

/**
 * `<showNumericalWeaponCooldown>`: WeaponBox::RenderBox (after super) prints up to three lines above a player
 * weapon box in font 51: the cooldown `charged/total` (one decimal, both divided by 1 + AUTO_COOLDOWN; the
 * `weapon_cooldown_negative` text for a negative cooldown), the boosted damage of a damage-chain weapon, and the
 * shots left under a `<shotLimit>`. Scripts may rewrite or suppress them through WEAPON_RENDERBOX.
 *
 * Placement: HS prints at `(location.x - hotKey*98 + 132, location.y - 34)`, the next lines 10 px higher. On the
 * oracle's MV screenshot (mv-seed-12345, 10-fight.png) the text of slots 0 and 2 is centred on x 300 and 492
 * (96 px per slot, the boxes are 97 apart) and its glyphs span y 607-612 with the weapon buttons at y 623: the
 * line is printed 18 px above the button origin, 2 + slot px left of the button centre.
 */
object HsCooldownNumbers {
    private val TARGETED_AUTO = Colour(255, 255, 50)
    private val TARGETED = Colour(255, 120, 120)
    private val IDLE = Colour(243, 255, 230)
    private val UNPOWERED = Colour(150, 150, 150)

    private var font: NativeFonts.NativeFont? = null

    @JvmStatic
    fun enabled(): Boolean = HS.active && xyz.znix.xftl.hs.render.HsMoreInfo.showWeaponCooldown()  // currentValue: altMode forces it on

    /** The three lines and their colour, before WEAPON_RENDERBOX. */
    class Lines(@JvmField var first: String, @JvmField var second: String, @JvmField var third: String, val colour: Colour)

    @JvmStatic
    fun lines(game: InGameState, weapon: AbstractWeaponInstance, targeted: Boolean, autofire: Boolean): Lines {
        var colour = when {
            !weapon.isPowered -> UNPOWERED
            targeted && autofire -> TARGETED_AUTO
            targeted -> TARGETED
            else -> IDLE
        }
        val (charged, total) = cooldown(weapon)
        val first = if (charged < 0f || total < 0f) {
            colour = UNPOWERED
            game.translator["weapon_cooldown_negative"]
        } else fixed1(charged) + "/" + fixed1(total)

        val type = weapon.type
        val boost = type.boost
        val second = if (boost != null && boost.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
            val level = minOf(weapon.chainCount, boost.maxCount)
            val base = baseDamage(type)
            general3(base + level * boost.perShot) + " " + game.translator["damage_word"]
        } else ""

        val limit = type.hs?.shotLimit ?: -1
        val third = if (limit >= 0) game.translator["shots_remaining"].replace("\\1", (limit - weapon.hsShotsFired).toString()) else ""
        return Lines(first, second, third, colour)
    }

    /**
     * ProjectileFactory::cooldown as HS prints it, over 1 + AUTO_COOLDOWN. FTL scales the cooldown itself by the
     * modifier SetCooldownModifier gives it (1 - the weapons manning bonus; WEAPON_COOLDOWN_MOD's value with Lua):
     * the oracle shows a 9 s Defense Laser as 8.1 when manned. The engine charges faster instead, so both numbers
     * are scaled by the modifier here.
     */
    @JvmStatic
    fun cooldown(weapon: AbstractWeaponInstance): Pair<Float, Float> {
        val div = 1f + weapon.ship.getAugmentValue(AugmentBlueprint.AUTOMATED_RELOADERS)
        val weapons = weapon.ship.weapons
        val manning = weapons?.getBoostSkillLevel(xyz.znix.xftl.crew.Skill.WEAPONS)
            ?.let { xyz.znix.xftl.systems.Weapons.SKILL_BONUSES[it.ordinal] / 100f } ?: 0f
        val mod = if (HS.luaActive && xyz.znix.xftl.hs.lua.HsLua.hasInt[xyz.znix.xftl.hs.lua.LuaApi.EV_WEAPON_COOLDOWN_MOD])
            weapon.hsCooldownMod else 1f - manning
        return Pair(weapon.timeCharged * mod / div, weapon.chargeTime * mod / div)
    }

    /** iDamage, else iIonDamage, else iPersDamage, else iSystemDamage (the first that is at least 1). */
    private fun baseDamage(type: AbstractWeaponBlueprint): Int = when {
        type.damage >= 1 -> type.damage
        type.ionDamage >= 1 -> type.ionDamage
        type.personnelDamage >= 1 -> type.personnelDamage
        else -> type.sysDamage
    }

    /**
     * Draw the lines for the weapon in [slot] whose button origin is ([x], [y]) and width [width].
     */
    @JvmStatic
    fun draw(game: InGameState, g: xyz.znix.xftl.rendering.Graphics, weapon: AbstractWeaponInstance, slot: Int,
             targeted: Boolean, autofire: Boolean, x: Int, y: Int, width: Int) {
        val l = lines(game, weapon, targeted, autofire)
        if (HS.luaActive && !HsWeaponsLua.renderBox(weapon, l)) return
        val f = font ?: NativeFonts.font(game::getFont, 51).also { font = it }
        // HS: location.x - hotKey*98 + 132 inside the box's own translation, so the lines move 96 px per slot
        // while the boxes move 97: 2 px left of the button centre in slot 0, one more per slot (oracle frame).
        // HS's x is a whole pixel (x + 42 - slot for the 87 px buttons): an odd-width line then starts on the half
        // pixel and rounds right (oracle swl-mv, Japanese "0.0/12.0").
        val cx = (x + width / 2 - 1 - slot).toFloat()
        var top = y - 18
        for (line in arrayOf(l.first, l.second, l.third)) {
            if (line.isEmpty()) continue
            f.printCenter(cx, top.toFloat(), line, l.colour)
            top -= 10
        }
    }

    /** std::fixed << setprecision(1). */
    @JvmStatic
    fun fixed1(v: Float): String {
        val tenths = Math.round(abs(v.toDouble()) * 10.0)
        val sign = if (v < 0f && tenths != 0L) "-" else ""
        return sign + (tenths / 10) + "." + (tenths % 10)
    }

    /** Default stream formatting with setprecision(3) (%.3g, trailing zeros dropped). */
    @JvmStatic
    fun general3(v: Float): String {
        val d = v.toDouble()
        if (d == floor(d) && abs(d) < 1000) return d.toLong().toString()
        if (d == 0.0) return "0"
        val exp = floor(Math.log10(abs(d))).toInt()
        val decimals = (2 - exp).coerceAtLeast(0)
        val scale = Math.pow(10.0, decimals.toDouble())
        val r = Math.round(d * scale) / scale
        var s = if (decimals == 0) r.toLong().toString() else {
            val whole = r.toLong()
            val frac = Math.round(abs(r - whole) * scale)
            (if (r < 0 && whole == 0L) "-" else "") + whole + "." + frac.toString().padStart(decimals, '0')
        }
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }
}
