// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/RedesignedTooltips.cpp, with the
// FTL-Hyperspace 1.23.2 behaviour where the files differ (1b05a07 adds a defense_drone_orbit_speed line to shield
// drones' stats; 1.23.2 has none)
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaJs
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaPush as ret
import xyz.znix.xftl.weapons.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * Hyperspace's tooltip texts (RedesignedTooltips.cpp): the weapon stat list (GetWeaponStatsString) that replaces the
 * weapon bar tooltip with `redesignedWeaponTooltips`, the weapon description of the info boxes
 * (WeaponBlueprint::GetDescription: HS's own wording of the original's list, or with the option the description,
 * description_stats, the stat list and scrap_value), the drone description with `redesignedDroneTooltips` and the
 * augment description with `redesignedAugmentTooltips` (scrap value; warning list of augments the augment's
 * functions make useless). Numbers follow C++ stream formatting (`<<` a float: 6 significant digits, trailing zeros
 * dropped; `setprecision(2)`: 2 significant digits; `fixed`: decimals).
 */
object HsTooltips {
    // ---- C++ number formatting ----

    /** `ostream << v` with precision [p] (the default float field: %g without trailing zeros). */
    @JvmStatic
    fun g(v: Double, p: Int = 6): String {
        if (v == 0.0) return "0"
        if (v.isNaN()) return "nan"
        if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
        val bd = BigDecimal(v).round(MathContext(maxOf(1, p), RoundingMode.HALF_EVEN))
        val exp = bd.precision() - bd.scale() - 1
        return if (exp < -4 || exp >= p) {
            val mant = bd.movePointLeft(exp).stripTrailingZeros().toPlainString()
            mant + "e" + (if (exp < 0) "-" else "+") + String.format(Locale.ROOT, "%02d", kotlin.math.abs(exp))
        } else {
            val s = bd.setScale(maxOf(0, p - 1 - exp), RoundingMode.HALF_EVEN).toPlainString()
            if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
        }
    }

    /** `std::fixed << std::setprecision(n) << v`. */
    @JvmStatic
    fun fixed(v: Double, n: Int): String = String.format(Locale.ROOT, "%.${n}f", v)

    private fun String.r(i: Int, v: Any) = replace("\\$i", v.toString())
    private fun StringBuilder.trimRight() { while (isNotEmpty() && this[length - 1].isWhitespace()) setLength(length - 1) }

    private fun level(tr: Translator, chance: Int) = when {
        chance >= 7 -> tr["chance_high"]
        chance >= 4 -> tr["chance_medium"]
        else -> tr["chance_low"]
    }

    private fun type(bp: AbstractWeaponBlueprint) = when (bp) {
        is LaserBlueprint -> 0; is MissileBlueprint -> 1; is BeamBlueprint -> 2; is BombBlueprint -> 3; is FlakBlueprint -> 4; else -> -1
    }

    private fun miniCount(bp: AbstractWeaponBlueprint) = (bp as? FlakBlueprint)?.projectileCount ?: 0
    private fun radius(bp: AbstractWeaponBlueprint) = (bp as? FlakBlueprint)?.radius ?: 0
    private fun length(bp: AbstractWeaponBlueprint) = (bp as? BeamBlueprint)?.length ?: 0
    private fun dlc(): Boolean = dlcEnabled()

    /**
     * Settings::GetDlcEnabled (settings.bDlcEnabled): the hangar's Advanced Edition toggle sets it, a run started or a
     * save loaded carries it, so in a run it is the run's mode and outside one the hangar's saved choice.
     */
    @JvmStatic
    fun dlcEnabled(): Boolean = xyz.znix.xftl.hs.lua.LuaAdapters.inGame()?.isAdvancedEdition
        ?: xyz.znix.xftl.hs.lua.HsLua.game?.profile?.lastAdvancedEdition ?: true

    /** The chance lines HS adds after the damage (erosion, crew/room stat boosts, crew spawns). */
    private fun customChances(sb: StringBuilder, tr: Translator, d: xyz.znix.xftl.hs.weapons.HsDamageDef) {
        if (d.erosionChance > 0) {
            sb.append(tr["erosion_chance"].r(1, d.erosionChance * 10).r(2, level(tr, d.erosionChance))).append('\n')
            val e = d.erosion
            sb.append(tr["erosion_effect"].r(1, g((e.speed * e.time * 0.16f).toDouble(), 2)).r(2, g(e.time.toDouble(), 2))
                .r(3, g(e.speed.toDouble(), 2))).append('\n')
        }
        if (d.statBoostChance > 0) sb.append(tr["crew_statboost_chance"].r(1, d.statBoostChance * 10).r(2, level(tr, d.statBoostChance))).append('\n')
        if (d.roomStatBoostChance > 0) sb.append(tr["room_statboost_chance"].r(1, d.roomStatBoostChance * 10).r(2, level(tr, d.roomStatBoostChance))).append('\n')
        if (d.crewSpawnChance > 0) sb.append(tr["crew_spawn_chance"].r(1, d.crewSpawnChance * 10).r(2, level(tr, d.crewSpawnChance))).append('\n')
    }

    /** GetWeaponStatsString(bp, drone, dronePower, droneSpeed), then the WEAPON_STATBOX Lua chain. */
    @JvmStatic
    fun weaponStats(bp: AbstractWeaponBlueprint, tr: Translator, drone: Boolean = false, dronePower: Int = 0, droneSpeed: Int = 0): String {
        val sb = StringBuilder()
        val def = bp.hs ?: xyz.znix.xftl.hs.weapons.HsWeaponDef(bp.name)
        val cd = def.damage
        val type = type(bp)
        val cooldown = bp.chargeTime
        if (drone) {
            sb.append("\n\n").append(tr["description_stats"]).append('\n')
            sb.append(tr["required_power"].r(1, dronePower)).append('\n')
            sb.append(tr["drone_required"]).append("\n\n")
            sb.append(tr["drone_speed"].r(1, droneSpeed)).append("\n\n")
        } else {
            when {
                bp.power > 0 -> sb.append(tr["required_power"].r(1, bp.power)).append('\n')
                bp.power == 0 -> sb.append(if (cooldown >= 0) tr["requires_no_power"] else tr["cannot_fire"]).append('\n')
                else -> sb.append(tr["provides_bonus_power"]).append(": ").append(-bp.power).append('\n')
            }
            if (cooldown >= 0) sb.append(tr["charge_time"].r(1, g(cooldown.toDouble()))).append('\n')
            else if (bp.power != 0) sb.append(tr["cannot_fire"]).append('\n')
        }
        if (cooldown >= 0) {
            if (bp.missilesUsed > 0) sb.append(tr["ammo_consumption"].r(1, bp.missilesUsed)).append('\n')
            if (def.freeMissileChance > 0) sb.append(tr["free_missile_chance"].r(1, def.freeMissileChance)).append('\n')
            if (cd.accuracyMod != 0) sb.append(tr["accuracy_modifier"].r(1, cd.accuracyMod)).append('\n')
            if (def.shotLimit >= 0) sb.append(tr["shot_limit"].r(1, def.shotLimit)).append('\n')
            val boost = bp.boost
            if (boost != null && boost.maxCount > 0) {
                val amount = boost.perShot.toDouble()
                val count = boost.maxCount
                if (boost.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
                    val key = if (amount > 0) "redesigned_boost_power_damage_positive" else "redesigned_boost_power_damage_negative"
                    sb.append(tr[key].r(1, g(amount, 2))).append('\n')
                    val dmg = if (bp.ionDamage <= 0) bp.damage else bp.ionDamage
                    val cap = g((count * boost.perShot + dmg).toDouble())
                    sb.append(tr["redesigned_damage_cap"].r(1, cap).r(2, g(count.toDouble(), 2))).append('\n')
                } else if (amount > 0) {
                    sb.append(tr["redesigned_boost_power_speed_positive"].r(1, g(amount, 4))).append('\n')
                    // int speedCap = cooldown - count * amount (truncated)
                    val capF = cooldown - count * boost.perShot
                    if (capF.toInt() >= 0) {
                        sb.append(tr["redesigned_speed_cap"].r(1, g(capF.toDouble())).r(2, g(count.toDouble(), 4))).append('\n')
                    } else {
                        sb.append(tr["max_shots"].r(1, g(kotlin.math.ceil(cooldown / boost.perShot).toDouble())))
                    }
                } else {
                    sb.append(tr["redesigned_boost_power_speed_negative"].r(1, g(amount, 4))).append('\n')
                    sb.append(tr["redesigned_speed_cap"].r(1, g((cooldown - count * boost.perShot).toDouble())).r(2, g(count.toDouble(), 4))).append('\n')
                }
            }
            sb.trimRight(); sb.append("\n\n")

            if (def.angularRadius != -1f) sb.append(tr["shot_radius_angular"].r(1, fixed(def.angularRadius.toDouble(), 2))).append('\n')
            else if (radius(bp) > 0) sb.append(tr["shot_radius"].r(1, radius(bp))).append('\n')

            if ((type == 0 || type == 1 || type == 3 || type == 4) && !drone) {
                when {
                    type == 0 || type == 1 || (bp.shots == 1 && type != 4) -> sb.append(tr["shots"].r(1, bp.shots)).append('\n')
                    type == 3 -> sb.append(tr["bomb_shots"].r(1, bp.shots)).append('\n')
                    else -> sb.append(tr["shots"].r(1, miniCount(bp) * bp.shots)).append('\n')
                }
            }
            val levels = bp.chargeLevels ?: 0
            if (levels > 1) sb.append(tr["charge"].r(1, levels)).append('\n')
            if (type == 2) {
                val len = length(bp)
                sb.append(tr["beam_length"].r(1, if (len > 1) len.toString() else tr["pinpoint"])).append('\n')
                val speed = bp.speed ?: 0
                sb.append(tr["swipe_speed"].r(1, if (speed != 0) fixed(speed.toDouble(), 0) else "5")).append('\n')
            }
            sb.trimRight(); sb.append("\n\n")

            if (bp.hullBust != 0) sb.append(tr["redesigned_double_damage"]).append('\n')
            val dmg = bp.damage
            if (dmg > 0) sb.append(tr["hull_damage"].r(1, dmg)).append('\n')
            else if (dmg < 0) sb.append(tr["hull_damage_negative"].r(1, -dmg)).append('\n')
            val sys: Int; val pers: Int
            if (dmg >= 0) {
                sys = bp.sysDamage + if (!cd.noSysDamage) dmg else 0
                pers = bp.personnelDamage + if (!cd.noPersDamage) dmg else 0
            } else {
                sys = bp.sysDamage; pers = bp.personnelDamage
            }
            if (sys > 0) sb.append(tr["system_damage"].r(1, sys)).append('\n')
            else if (sys < 0) sb.append(tr["system_damage_negative"].r(1, -sys)).append('\n')
            if (pers > 0) sb.append(tr["personnel_damage"].r(1, pers * 15)).append('\n')
            else if (pers < 0) sb.append(tr["personnel_damage_negative"].r(1, -pers * 15)).append('\n')

            if (bp.ionDamage != 0) sb.append(tr["ion_damage"].r(1, bp.ionDamage)).append('\n')
            val sp = bp.shieldPiercing
            if (sp != 0) {
                val v = when {
                    type != 2 || dmg > 0 -> sp
                    dmg == 0 && sp - 1 > 0 -> sp - 1
                    else -> sp + dmg - 1
                }
                sb.append(tr["shield_piercing"].r(1, v)).append('\n')
            }
            sb.trimRight(); sb.append("\n\n")

            if (bp.browserLockdown) sb.append(tr["weapon_lockdown"]).append('\n')
            if (bp.fireChance > 0) sb.append(tr["fire_chance"].r(1, bp.fireChance * 10).r(2, level(tr, bp.fireChance))).append('\n')
            if (bp.breachChance > 0) sb.append(tr["breach_chance"].r(1, bp.breachChance * 10)
                .r(2, bp.breachChance * 10 - bp.fireChance * bp.breachChance).r(3, level(tr, bp.breachChance))).append('\n')
            if (bp.stun > 0 && dlc()) {
                sb.append(tr["stun_chance"].r(1, 100)).append(" (").append(bp.stun).append(' ').append(tr["stun_length"]).append(")\n")
            } else if (bp.stunChance > 0 && dlc()) {
                val lvl = level(tr, bp.stunChance) + " (3 " + tr["stun_length"]
                sb.append((tr["stun_chance"].r(1, bp.stunChance * 10) + " (3 " + tr["stun_length"] + ")").r(2, lvl)).append('\n')
            }
            customChances(sb, tr, cd)
        }
        sb.trimRight()
        return xyz.znix.xftl.hs.weapons.HsWeaponsLua.statBox(bp, sb.toString())
    }

    private fun scrapValue(tr: Translator, cost: Int) = tr["scrap_value"].r(1, cost).r(2, cost / 2)

    /**
     * WeaponBlueprint::GetDescription(false) under Hyperspace: the advanced or plain override, else the redesigned
     * text with the option, else HS's list; then the WEAPON_DESCBOX Lua chain ("" when pre-empted).
     */
    @JvmStatic
    fun weaponDescription(bp: AbstractWeaponBlueprint, tr: Translator): String {
        val def = bp.hs ?: xyz.znix.xftl.hs.weapons.HsWeaponDef(bp.name)
        val redesigned = HsMoreInfo.redesignedWeaponTooltips()
        val text = fun(t: xyz.znix.xftl.hs.weapons.HsText?): String? =
            if (t == null || t.data.isEmpty()) null else (if (t.isLiteral) t.data else tr[t.data]).replace("\\n", "\n")
        val desc = bp.desc?.let { tr[it] } ?: ""
        val ret: String = when {
            redesigned && text(def.advancedDescriptionOverride) != null -> text(def.advancedDescriptionOverride)!!
            text(def.descriptionOverride) != null -> text(def.descriptionOverride)!!
            redesigned -> desc + "\n\n" + tr["description_stats"] + "\n" + weaponStats(bp, tr) + "\n\n" + scrapValue(tr, bp.cost ?: 0) + "\n"
            else -> plainDescription(bp, tr, desc, def)
        }
        return descBox(bp, ret)
    }

    /** GetDescription's list without the option (HS's wording of the original's). */
    private fun plainDescription(bp: AbstractWeaponBlueprint, tr: Translator, desc: String, def: xyz.znix.xftl.hs.weapons.HsWeaponDef): String {
        val sb = StringBuilder(desc).append("\n\n")
        val cd = def.damage
        val type = type(bp)
        sb.append(tr["required_power"].r(1, bp.power)).append('\n')
        sb.append(tr["charge_time"].r(1, g(bp.chargeTime.toDouble()))).append('\n')
        val boost = bp.boost
        if (boost != null && boost.maxCount > 1) {
            if (boost.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
                sb.append(tr["boost_power_damage"]).append('\n')
                val dmg = if (bp.ionDamage <= 0) bp.damage else bp.ionDamage
                sb.append(tr["damage_cap"].r(1, g((boost.maxCount * boost.perShot + dmg).toDouble()))).append('\n')
            } else {
                sb.append(tr["boost_power_speed"]).append('\n')
                sb.append(tr["speed_cap"].r(1, g((bp.chargeTime - boost.maxCount * boost.perShot).toDouble()))).append('\n')
            }
        }
        if (bp.missilesUsed > 0) sb.append(tr["requires_missiles"]).append('\n')
        if ((type == 0 || type == 1) && bp.shots > 0) sb.append(tr["shots"].r(1, bp.shots)).append('\n')
        if (type == 4) sb.append(tr["shots"].r(1, miniCount(bp))).append('\n')
        val levels = bp.chargeLevels ?: 0
        if (levels > 1) sb.append(tr["charge"].r(1, levels)).append('\n')
        sb.append(tr[if (type == 2) "damage_room" else "damage_shot"].r(1, bp.damage)).append('\n')
        if (bp.shieldPiercing != 0) sb.append(tr["shield_piercing"].r(1, bp.shieldPiercing)).append('\n')
        if (bp.fireChance > 0) sb.append(tr["fire_chance"].r(1, bp.fireChance * 10).r(2, level(tr, bp.fireChance))).append('\n')
        if (bp.breachChance > 0) sb.append(tr["breach_chance"].r(1, bp.breachChance * 10)
            .r(2, bp.breachChance * 10 - bp.fireChance * bp.breachChance).r(3, level(tr, bp.breachChance))).append('\n')
        if (bp.ionDamage != 0) sb.append(tr["ion_damage"].r(1, bp.ionDamage)).append('\n')
        if (bp.stun > 0 && dlc()) sb.append(tr["stun_damage"].r(1, bp.stun)).append('\n')
        else if (bp.stunChance > 0 && dlc()) sb.append(tr["stun_chance"].r(1, bp.stunChance * 10).r(2, level(tr, bp.stunChance))).append('\n')
        customChances(sb, tr, cd)
        var pers = bp.personnelDamage
        if (cd.noPersDamage) pers -= bp.damage
        if (pers != 0) sb.append(tr["personnel_damage"].r(1, pers * 15)).append('\n')
        var sys = bp.sysDamage
        if (cd.noSysDamage) sys -= bp.damage
        if (sys != 0) sb.append(tr["system_damage"].r(1, sys)).append('\n')
        if (bp.hullBust != 0) sb.append(tr["double_damage"]).append('\n')
        if (def.freeMissileChance > 0) sb.append(tr["free_missile_chance"].r(1, def.freeMissileChance)).append('\n')
        if (cd.accuracyMod != 0) sb.append(tr["accuracy_modifier"].r(1, cd.accuracyMod)).append('\n')
        if (def.shotLimit >= 0) sb.append(tr["shot_limit"].r(1, def.shotLimit)).append('\n')
        return sb.toString()
    }

    /** WEAPON_DESCBOX(bp, text): a callback may change the text; a pre-empt gives "". */
    private fun descBox(bp: AbstractWeaponBlueprint, text: String): String {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_WEAPON_DESCBOX]) return text
        ret.obj(bp, A.T_WeaponBlueprint)
        LuaJs.pushstring(text)
        val preempt = LuaJs.fireChain(A.EV_WEAPON_DESCBOX, 2, 1)
        val s = if (LuaJs.isstring(-1)) LuaJs.tostring(-1) else text
        LuaJs.pop(2)
        return if (preempt) "" else s ?: text
    }

    /** WeaponBox::GenerateTooltip: the override texts, or with the option the stat list; null keeps the original's. */
    @JvmStatic
    fun weaponBoxTooltip(bp: AbstractWeaponBlueprint, tr: Translator): String? {
        xyz.znix.xftl.hs.weapons.HsWeapons.tooltipOverride(bp, tr)?.let { return it }
        return if (HsMoreInfo.redesignedWeaponTooltips()) weaponStats(bp, tr) else null
    }

    // ---- drones ----

    /** DroneBlueprint::targetType: 1 LASERS (solid), 3 DRONES; HS's extra targets are not modelled by the port. */
    private fun targetType(bp: DroneBlueprint): Int = when (bp.defenceTarget) { "LASERS" -> 1; "DRONES" -> 3; else -> 0 }

    /**
     * InfoBox::SetBlueprint(drone) with `redesignedDroneTooltips`: the description with the drone's stats and scrap
     * value (the original's required_power / drone_required lines then read empty); null without the option.
     */
    @JvmStatic
    fun droneDescription(bp: DroneBlueprint, tr: Translator): String? {
        if (!HsMoreInfo.redesignedDroneTooltips()) return null
        val sb = StringBuilder(bp.desc?.let { tr[it] } ?: "")
        val wbp = bp.weaponBlueprint
        if (wbp != null && HsMoreInfo.redesignedWeaponTooltips()) {
            if (bp.type == DroneBlueprint.DroneType.COMBAT) {
                sb.append(weaponStats(wbp, tr, true, bp.power, bp.speed ?: 0))
            } else {
                sb.append("\n\n").append(tr["description_stats"]).append('\n')
                sb.append(tr["required_power"].r(1, bp.power)).append('\n')
                sb.append(tr["drone_required"]).append("\n\n")
                val target = targetType(bp)
                sb.append(tr[when (target) {
                    1 -> "defense_drone_projectile_target_solid"; 2 -> "defense_drone_projectile_target_asteroid"
                    3 -> "defense_drone_drone_target_all"; 4 -> "defense_drone_projectile_target_all"
                    5 -> "defense_drone_drone_target_solid_projectiles"; 6 -> "defense_drone_drone_target_all_projectiles"
                    else -> "defense_drone_projectile_target_unknown"
                }]).append('\n')
                sb.append(tr["defense_drone_reload_speed"].r(1, fixed((bp.cooldown ?: 0).toDouble(), 0))).append('\n')
                val ws = wbp.speed ?: 0
                sb.append(tr["defense_drone_projectile_speed"].r(1, fixed(if (ws != 0) ws.toDouble() else 60.0, 0))).append('\n')
                val dam = wbp.hs?.damage
                if (target == 3 && dam != null && dam.droneAccuracyMod != 0) sb.append(tr["accuracy_modifier"].r(1, dam.droneAccuracyMod)).append('\n')
                sb.append(tr["defense_drone_orbit_speed"].r(1, fixed((bp.speed ?: 0).toDouble(), 0)))
                if (wbp is FlakBlueprint) {
                    sb.append("\n\n")
                    val ang = wbp.hs?.angularRadius ?: -1f
                    if (ang != -1f) sb.append(tr["shot_radius_angular"].r(1, fixed(ang.toDouble(), 2))).append('\n')
                    else if (wbp.radius > 0) sb.append(tr["shot_radius"].r(1, wbp.radius)).append('\n')
                    sb.append(tr["shots"].r(1, wbp.projectileCount))
                }
            }
        } else {
            sb.append("\n\n").append(tr["description_stats"]).append('\n')
            sb.append(tr["required_power"].r(1, bp.power)).append('\n')
            sb.append(tr["drone_required"])
            if (bp.type == DroneBlueprint.DroneType.SHIELD) {
                // ShieldDroneManager definitions also drive the redesigned tooltip (1.23.2 has no orbit speed line).
                val definition = xyz.znix.xftl.hs.weapons.HsShieldDrones.definition(bp.name)
                val cooldowns = if (definition.cooldowns.isEmpty()) "8/10/13/16/20"
                    else definition.cooldowns.joinToString("/") { g(it.toDouble()) }
                sb.append("\n\n")
                sb.append(tr["shield_drone_layers_per_charge"].r(1, definition.layers)).append('\n')
                sb.append(tr["shield_drone_cooldowns"].r(1, cooldowns))
            }
        }
        sb.append("\n\n").append(scrapValue(tr, bp.cost ?: 0))
        return sb.toString()
    }

    // ---- augments ----

    /** InfoBox::SetBlueprint(augment): the description (with the option: plus the scrap value). */
    @JvmStatic
    fun augmentDescription(bp: AugmentBlueprint, tr: Translator): String {
        val base = bp.desc?.let { tr[it] } ?: ""
        return if (HsMoreInfo.redesignedAugmentTooltips()) base + "\n\n" + scrapValue(tr, bp.cost) else base
    }

    /** The augment_no_effect warning: augments this one's warning functions make useless (or ""). */
    @JvmStatic
    fun augmentWarning(bp: AugmentBlueprint, tr: Translator, blueprints: xyz.znix.xftl.BlueprintManager): String {
        val def = xyz.znix.xftl.hs.ship.HsAugments.definition(bp.name) ?: return ""
        val sb = StringBuilder(tr["augment_no_effect"]).append('\n')
        var counter = 0
        var last = ""
        for ((name, f) in def.functions) {
            if (!f.warning || name == last) continue
            val other = blueprints.getOrNull(name) as? AugmentBlueprint ?: continue
            if (((f.preferHigher && other.value <= f.value) || (!f.preferHigher && other.value >= f.value)) && !other.stackable) {
                last = name
                sb.append(other.title?.let { tr[it] } ?: "").append('\n')
                counter++
            }
        }
        return if (counter > 0) sb.toString() else ""
    }
}
