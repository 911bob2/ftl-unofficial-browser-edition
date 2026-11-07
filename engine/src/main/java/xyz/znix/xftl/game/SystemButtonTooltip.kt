package xyz.znix.xftl.game

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.systems.*

/**
 * The tooltip for the button representing each system in the player's power bar.
 */
class SystemButtonTooltip(val game: InGameState, val system: AbstractSystem) : DelayedTooltip(game) {
    // SystemBox::MouseMove (0x004e2d20) sets MouseControl's wrap width to 300.
    override val customMaxWidth: Int get() = 300

    override fun getText(): String {
        val power = when (system) {
            is MainSystem -> system.powerSupplied
            else -> system.undamagedEnergy
        }

        val levelText: String = when {
            power <= 0 -> when (system.codename) {
                // Two ids do not follow the system's name; the systems without an off text have none.
                "engines" -> game.translator["engine_off"]
                "clonebay" -> game.translator["clone_off"]
                "shields", "oxygen", "weapons", "drones", "medbay", "pilot", "sensors", "doors", "artillery" -> game.translator[system.codename + "_off"]
                else -> ""
            }
            else -> {
                // ShipSystem::GetLevelDescription (0x63fbf0) with the tooltip flag: the clone bay reads "clone" (the
                // clone time only; capture wp5c-lanius "Level 1: 12 sec clone"), not the upgrade screen's clone_full.
                val name = if (system is Clonebay) xyz.znix.xftl.hs.lua.LuaShipEvents.levelDescription(
                    xyz.znix.xftl.hs.systems.HsSystems.nameToId(system.blueprint.name), power - 1, true
                ) {
                    val time = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSystems.cloneLevel(power).cloneSpeed.toInt()
                    else Clonebay.durationForLevel(power).toInt()
                    game.translator["clone"].replaceArg(time)
                } else xyz.znix.xftl.hs.lua.LuaShipEvents.levelName(system.blueprint, power - 1, true, game.translator)

                game.translator["level"]
                    .replaceArg(power)
                    .replaceArg(name, 2)
            }
        }

        val lines = ArrayList<String>()
        val touchText = TouchMode.enabled && (system is Doors || system is Weapons)
        lines += game.translator["tooltip_" + system.codename + if (touchText) "_touch" else ""]
        if (system is Clonebay) {
            lines += ""
            lines += cloneHealthText(system)
        }
        // The system tooltip (0x004df760) has no level line for weapons and drones.
        if (system !is Weapons && system !is Drones) {
            lines += ""
            lines += levelText
        }

        // TODO make this more modding-friendly
        val skillType: Skill? = when (system) {
            is Piloting -> Skill.PILOTING
            is Engines -> Skill.ENGINES
            is Shields -> Skill.SHIELDS
            is Weapons -> Skill.WEAPONS
            else -> null
        }
        val skillLevel = skillType?.let { system.getSkillLevel(it) }
        if (skillLevel != null && power > 0) {
            val desc = skillType.getDescription(game, skillLevel)
            lines += ""
            lines += game.translator["manning_bonus"].replaceArg(desc)
        }

        lines += ""
        lines += game.translator["status"]
        val powerStateKey = when (power) {
            system.energyLevels -> if (system is MainSystem) "full_powered" else "full_functional"
            // The system tooltip (0x004df760) has no power line for a subsystem without power (a nebula's sensors).
            0 -> if (system is MainSystem) "unpowered" else null
            else -> if (system is MainSystem) "partial_powered" else "partial_functional"
        }
        if (powerStateKey != null) lines += "-" + game.translator[powerStateKey] + " "

        if (power > 0 && system.info.canBeManned && system.manningCrew == null) {
            lines += "-" + game.translator["unmanned"] + " "
        }

        // 0x004df760: "temp_disabled" while ShipSystem::GetPowerCap is below the level or GetPowerLoss is not 0 (an
        // event's limit or loss, a nebula's cap of the sensors), after the manning line (native 1.6.14 capture
        // ps-b1-van b1v-neb n-neb-h-sensors: "-Temporarily disabled by enemy or location").
        val limit = system.scriptedPowerLimit
        if ((limit != null && limit < system.energyLevels) || system.scriptedPowerLoss + (system.hsExt?.appliedLoss ?: 0) != 0) {
            lines += "-" + game.translator["temp_disabled"] + " "
        }

        // TODO hacking

        if (system is MainSystem && !TouchMode.enabled) {
            lines += ""
            // The power key of the system; with no separate remove key bound
            // the original names Shift + that key (remove_power_alt).
            val id = if (system is Medbay || system is Clonebay) VanillaHotkeys.SYS_POWER_MEDICAL else "sys_power_${system.codename}"
            val onKey = game.getButtonNameForHotkey(id, "")
            val offKey = game.getButtonNameForHotkey(id + "_off", "")
            lines += game.translator["add_power"].replace("\\1", onKey) + " "
            lines += (if (offKey.isNotEmpty()) game.translator["remove_power"].replace("\\1", offKey)
            else game.translator["remove_power_alt"].replace("\\1", onKey)) + " "
        }

        // The system tooltip (0x004df760) puts " \n\n" before each part after the description and ends each bullet
        // and power key line with " \n" ("Status:" with "\n" alone): the space widens a line's measure, so the box is
        // as wide as the original's where such a line is the widest (frame pair u1v-a a-h-sub-doors: "opening/closing
        // of doors. " makes the doors box 6 px wider).
        for (i in 0 until lines.size - 1)
            if (lines[i + 1].isEmpty() && lines[i].isNotEmpty() && !lines[i].endsWith(" ")) lines[i] = lines[i] + " "
        return lines.joinToString("\n")
    }

    /**
     * The system tooltip (0x004df760) for the clone bay: clonebay_health with CloneSystem::GetJumpHealth of the
     * system's undamaged level (0x0063c0c0), plus clonebay_damaged while some bars are broken (healthState first !=
     * second). Native capture wp5c-lanius (Lanius A, level 1): "Heals all crew 8 hp every jump." between the
     * description and the level line. Hyperspace: GetJumpHealth is the clone level's jumpHP, and TextLibrary::GetText
     * writes "\1 + P%" (or "P%") for the \1 of a level with jumpHPPercent.
     */
    private fun cloneHealthText(system: Clonebay): String {
        val level = system.energyLevels - system.damagedEnergyLevels
        val hs = xyz.znix.xftl.hs.HS.active
        val hsLevel = if (hs) xyz.znix.xftl.hs.systems.HsSystems.cloneLevel(level) else null
        fun text(key: String): String {
            val t = game.translator[key]
            if (hsLevel == null || hsLevel.jumpHPPercent <= 0) return t
            val pct = hsLevel.jumpHPPercent.toString() + "%"
            return t.replaceFirst("\\1", if (hsLevel.jumpHP > 0) "\\1 + $pct" else pct)
        }
        val hp = hsLevel?.jumpHP ?: if (level <= 0) 0 else Clonebay.PASSIVE_HEALING.getOrElse(level - 1) { Clonebay.PASSIVE_HEALING.last() }
        var line = text("clonebay_health").replaceArg(hp)
        // The native text starts with a space (" (If repaired, it will heal more)"); the engine's text library trims it.
        if (system.damagedEnergyLevels > 0) line += text("clonebay_damaged").let { if (it.startsWith(" ")) it else " $it" }
        return line
    }
}
