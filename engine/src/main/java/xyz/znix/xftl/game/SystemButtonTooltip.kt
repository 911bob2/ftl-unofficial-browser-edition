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
            power <= 0 -> game.translator[system.codename + "_off"]
            else -> {
                val name = system.info.getLevelName(power - 1, game.translator)

                game.translator["level"]
                    .replaceArg(power)
                    .replaceArg(name, 2)
            }
        }

        val lines = ArrayList<String>()
        lines += game.translator["tooltip_" + system.codename]
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
            0 -> "unpowered"
            else -> if (system is MainSystem) "partial_powered" else "partial_functional"
        }
        lines += "-" + game.translator[powerStateKey]

        if (power > 0 && system.info.canBeManned && system.manningCrew == null) {
            lines += "-" + game.translator["unmanned"]
        }

        // TODO hacking

        if (system is MainSystem) {
            lines += ""
            // The power key of the system; with no separate remove key bound
            // the original names Shift + that key (remove_power_alt).
            val id = if (system is Medbay || system is Clonebay) VanillaHotkeys.SYS_POWER_MEDICAL else "sys_power_${system.codename}"
            val onKey = game.getButtonNameForHotkey(id, "")
            val offKey = game.getButtonNameForHotkey(id + "_off", "")
            lines += game.translator["add_power"].replace("\\1", onKey)
            lines += if (offKey.isNotEmpty()) game.translator["remove_power"].replace("\\1", offKey)
            else game.translator["remove_power_alt"].replace("\\1", onKey)
        }

        return lines.joinToString("\n")
    }
}
