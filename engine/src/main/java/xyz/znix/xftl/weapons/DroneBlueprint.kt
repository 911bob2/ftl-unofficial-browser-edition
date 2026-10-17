package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Animations
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.drones.*
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Image

class DroneBlueprint(xml: Element) : Blueprint(xml) {
    val type: DroneType = DroneType.valueOf(xml.getChildTextTrim("type"))
    override val cost: Int? = xml.getChildTextTrim("cost")?.toInt()
    val power: Int = xml.getChildTextTrim("power").toInt()
    val speed: Int? = xml.getChildTextTrim("speed")?.toInt()
    val droneImage: String? = xml.getChildTextTrim("droneImage")
    val iconImage: String? = xml.getChildTextTrim("iconImage")

    // For defence drones (which includes anti-drones)
    val cooldown: Int? = xml.getChildTextTrim("cooldown")?.toInt() // In milliseconds
    val defenceTarget: String? = xml.getChildTextTrim("target") // Either DRONES or LASERS

    // For external drones, a 0-10 chance that this drone will dodge projectiles that hit it.
    // Nothing in vanilla uses it, but it's easy to implement.
    val dodge: Int? = xml.getChildTextTrim("dodge")?.toInt()

    private val weaponBlueprintName: String? = xml.getChildTextTrim("weaponBlueprint")
    var weaponBlueprint: AbstractWeaponBlueprint? = null
        private set

    // The size of the icon, as it should appear in the UI. This is for roughly
    // positioning them, and obviously different drones are different sizes.
    // These are mostly guessed, I couldn't find anywhere to measure them and
    // disassembling the UI code to look for this seems a bit excessive.
    val iconSize: ConstPoint = when (type) {
        // TODO find some logic behind this - is it the laser arm
        //  in defence drones that make them taller?
        // DEFENSE_1 drones use 46 pixels of Y in the event option UI, while COMBAT_1 uses 38.
        DroneType.DEFENSE -> ConstPoint(29, 37)
        DroneType.COMBAT -> ConstPoint(29, 29)

        // TODO fill in all the drones properly
        else -> ConstPoint(25, 25)
    }

    fun drawIconUI(game: InGameState, pos: IPoint) {
        drawIconUI(game.animations, pos, game::getImg)
    }

    // This version is used by the ship editor, since it doesn't have an InGameState instance.
    /**
     * DroneBlueprint::RenderIcon (0x006047a0), centred on [pos]. Indoor drones
     * show the first walk-down frame with the green layer; the others show the
     * charged hull, defence drones with their gun_on art, and defence drones and
     * COMBAT_BEAM_2 sit 4px lower.
     */
    fun drawIconUI(animations: Animations, pos: IPoint, getImg: (String) -> Image) {
        val indoor = when (type) {
            DroneType.REPAIR -> "repair"
            DroneType.BATTLE, DroneType.BOARDER -> if (name == "BOARDER_ION") "boarder_ion" else "battle"
            else -> null
        }
        if (indoor != null) {
            val anim = animations.animations["${indoor}_walk_down"] ?: return
            val path = anim.sheet.sheetPath
            val base = anim.sheet.getSprite(getImg(path), anim.x, anim.y)
            val x = pos.x - base.width / 2
            val y = pos.y - base.height / 2
            base.draw(x.f, y.f)
            val layer = anim.sheet.getSprite(getImg(path.replace("_base.png", "_layer1.png")), anim.x, anim.y)
            layer.draw(x.f, y.f, ICON_LAYER_COLOUR)
            return
        }

        // Special-case the hacking drone, though obviously it's probably
        // not great if the player is seeing this outside a debug menu.
        val base = when {
            name == "DRONE_HACKING" -> getImg("img/ship/drones/drone_hack_base.png")
            droneImage != null -> getImg("img/ship/drones/${droneImage}_charged.png")
            else -> getImg(Constants.MISSING_FILE_PATH)
        }

        val imgX = pos.x - base.width / 2
        val imgY = pos.y - base.height / 2 + if (type == DroneType.DEFENSE || name == "COMBAT_BEAM_2") 4 else 0
        base.draw(imgX.f, imgY.f)

        // Defence drones (anti-drones included) add only the unlit gun.
        if (type == DroneType.DEFENSE) {
            getImg("img/ship/drones/${droneImage}_gun_on.png").draw(imgX.f, imgY.f)
        }
    }

    fun makeInstance(): AbstractDrone {
        return when (type) {
            DroneType.REPAIR -> RepairDrone(this)
            DroneType.BOARDER -> BoardingDrone(this)
            DroneType.COMBAT -> CombatDrone(this)
            DroneType.DEFENSE -> DefenceDrone(this)
            DroneType.SHIP_REPAIR -> HullRepairDrone(this)
            DroneType.BATTLE -> AntiPersonnelDrone(this)
            DroneType.SHIELD -> ShieldDrone(this)
            DroneType.HACKING -> error("Hacking drones are launched by the hacking system, not drone control")
        }
    }

    override fun finishSetup(content: InGameState.GameContent) {
        super.finishSetup(content)

        if (weaponBlueprintName != null) {
            weaponBlueprint = content.blueprintManager[weaponBlueprintName] as AbstractWeaponBlueprint
        }
    }

    companion object {
        /** RenderIcon's tint for the indoor drones' colour layer (DAT_0091a590). */
        private val ICON_LAYER_COLOUR = Colour(100, 255, 100)
    }

    enum class DroneType(val needsHostileShip: Boolean) {
        COMBAT(true),
        SHIP_REPAIR(false),
        DEFENSE(false),
        REPAIR(false),
        BATTLE(false),
        BOARDER(true),
        HACKING(true),
        SHIELD(false),
    }

}
