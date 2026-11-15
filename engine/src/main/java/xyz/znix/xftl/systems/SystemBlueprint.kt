package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.weapons.nativeInt
import xyz.znix.xftl.SystemInfo

class SystemBlueprint(xml: Element) : Blueprint(xml) {
    val type: String = xml.getChildTextTrim("type") ?: ""

    // ProcessSystemBlueprint (1.6.9 0x5e5470) keeps the constructor's 0 powers, no upgrade costs and Description's 0 cost
    // for a tag that is not there.
    val startPower: Int = xml.nativeInt("startPower") ?: 0
    val maxPower: Int = xml.nativeInt("maxPower") ?: 0
    private val xmlCost: Int = xml.nativeInt("cost") ?: 0
    override val cost: Int get() = hsCost ?: xmlCost

    val upgradeCost: List<Int> = xml.getChild("upgradeCost")?.getChildren("level")?.map { it.text.toInt() } ?: emptyList()

    val onIconPath = "img/icons/s_${type}_green1.png"
    val roomIconPath: String = "img/icons/s_${type}_overlay.png"
    val roomIconOutlinePath: String = "img/icons/s_${type}_overlay2.png"

    // No idea what the 'locked' flag does.

    // Hyperspace sessions add the temporal system (HsSystems.info is null in vanilla).
    val info: SystemInfo? = BY_NAME[type] ?: xyz.znix.xftl.hs.systems.HsSystems.info(type)

    init {
        if (info == null) {
            // Must be a modded system.
            println("Warning: unimplemented system $type")
        }
    }

    fun createInstance(): AbstractSystem? {
        return info?.create(this)
    }

    companion object {
        /**
         * Padding inside the icon image file, which contains the glow
         */
        const val ICON_GLOW = 19

        val ALL_SYSTEMS = listOf(
            Doors.INFO,
            Engines.INFO,
            Medbay.INFO,
            Oxygen.INFO,
            Piloting.INFO,
            Sensors.INFO,
            Shields.INFO,
            Cloaking.INFO,
            Weapons.INFO,
            Drones.INFO,
            Teleporter.INFO,
            Artillery.INFO,

            // AE-only
            MindControl.INFO,
            Hacking.INFO,
            Clonebay.INFO,
            BackupBattery.INFO
        )

        val BY_NAME = ALL_SYSTEMS.associateBy { it.name }
    }
}
