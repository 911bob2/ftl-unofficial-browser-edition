package ftl.browser

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.GameText
import xyz.znix.xftl.getGameTextChild

/** Reads the official desktop script; instruction text remains in the private DAT. */
class BrowserTutorialDefinition(datafile: Datafile) {
    enum class Anchor { SCREEN, UPGRADES, TARGET_BOX, ENEMY }
    data class Arrow(val x: Int, val y: Int, val angle: Int, val anchor: Anchor)
    data class Step(val id: String, val text: GameText, val continueButton: Boolean, val arrows: List<Arrow>)
    val steps: List<Step>

    init {
        // TutorialManager::LoadTutorial reads the PC order and the steps by name; a mod may patch both (Multiverse does,
        // with or without Hyperspace's <disableDefaultTutorial>). Nothing here may stop the game: a name the order lists
        // without a step is skipped, missing text is empty, a step without <continue> waits for its gate.
        val root = datafile.parseXML(datafile["data/tutorial.xml"]).rootElement
        // The touch layout reads the file's iPad lessons, which teach its controls.
        val platform = if (xyz.znix.xftl.game.TouchMode.enabled) "IPAD" else "PC"
        val order = (root.getChildren("order").firstOrNull { it.getAttributeValue("type") == platform } ?: root.getChild("order"))
            ?.getChildren("step")?.map { it.textTrim }?.distinct() ?: emptyList()
        val definitions = root.getChildren("step").associateBy { it.getAttributeValue("name") }
        steps = order.mapNotNull { id ->
            val elem = definitions[id] ?: return@mapNotNull null
            val text = elem.getGameTextChild(platform) ?: elem.getGameTextChild("default") ?: GameText.literal("")
            val continueButton = elem.getChildTextTrim("continue") == "true"
            val arrows = elem.getChildren("arrow").filter { it.getAttributeValue("type") in listOf(null, platform) }.mapNotNull { arrow ->
                val anchor = when (arrow.getAttributeValue("relative")) {
                    "upgrades" -> Anchor.UPGRADES
                    "hostilebox" -> Anchor.TARGET_BOX
                    "enemy" -> Anchor.ENEMY
                    else -> Anchor.SCREEN
                }
                val x = arrow.getChildTextTrim("x")?.toIntOrNull() ?: return@mapNotNull null
                val y = arrow.getChildTextTrim("y")?.toIntOrNull() ?: return@mapNotNull null
                Arrow(x, y, arrow.getChildTextTrim("angle")?.toIntOrNull() ?: 0, anchor)
            }
            Step(id, text, continueButton, arrows)
        }
    }
}
