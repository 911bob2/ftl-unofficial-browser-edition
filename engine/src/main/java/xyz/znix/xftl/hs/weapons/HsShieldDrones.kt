// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDrones.cpp/.h
package xyz.znix.xftl.hs.weapons

import org.jdom2.Element
import xyz.znix.xftl.hs.HS

/** ShieldDroneManager: blueprint definitions are replaced only when the node authors a shield-specific field. */
object HsShieldDrones {
    data class Definition(
        val chargeSound: String = "shieldDroneCharge",
        val activateSound: String = "shieldDroneActivate",
        val slowDuration: Float = 1f,
        val pulseDuration: Float = 1.5f,
        val cooldowns: List<Float> = emptyList(),
        val layers: Int = 1
    )

    private val definitions = HashMap<String, Definition>()
    private val defaultDefinition = Definition()

    fun reset() { definitions.clear() }

    fun definition(name: String): Definition =
        if (HS.active) definitions[name] ?: defaultDefinition else defaultDefinition

    fun parse(node: Element) {
        if (!HS.active || node.getChildTextTrim("type") != "SHIELD") return
        var chargeSound = defaultDefinition.chargeSound
        var activateSound = defaultDefinition.activateSound
        var slowDuration = defaultDefinition.slowDuration
        var pulseDuration = defaultDefinition.pulseDuration
        var layers = defaultDefinition.layers
        val cooldowns = ArrayList<Float>()
        var custom = false
        for (child in node.children) when (child.name) {
            "chargeSound" -> { chargeSound = child.text; custom = true }
            "activateSound" -> { activateSound = child.text; custom = true }
            "slowDuration" -> { slowDuration = child.textTrim.toFloat(); custom = true }
            "pulseDuration" -> { pulseDuration = child.textTrim.toFloat(); custom = true }
            "layers" -> { layers = child.textTrim.toInt(); custom = true }
            "cooldowns" -> for (cooldown in child.children) {
                cooldowns.add(cooldown.textTrim.toFloat())
                custom = true
            }
        }
        val name = node.getAttributeValue("name") ?: ""
        if (custom && name.isNotEmpty()) definitions[name] =
            Definition(chargeSound, activateSound, slowDuration, pulseDuration, cooldowns.toList(), layers)
    }
}
