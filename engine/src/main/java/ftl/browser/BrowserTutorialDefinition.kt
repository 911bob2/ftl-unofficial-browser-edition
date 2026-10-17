package ftl.browser

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.GameText
import xyz.znix.xftl.getGameTextChild

/** Reads the official desktop script; instruction text remains in the private DAT. */
class BrowserTutorialDefinition(datafile: Datafile) {
    enum class Anchor { SCREEN, UPGRADES }
    data class Arrow(val x: Int, val y: Int, val angle: Int, val anchor: Anchor)
    data class Step(val id: String, val text: GameText, val continueButton: Boolean, val arrows: List<Arrow>)
    val steps: List<Step>

    init {
        val root = datafile.parseXML(datafile["data/tutorial.xml"]).rootElement
        val order = root.getChildren("order").single { it.getAttributeValue("type") == "PC" }
            .getChildren("step").map { it.textTrim }
        require(order.isNotEmpty() && order.distinct().size == order.size) { "Invalid desktop tutorial order" }
        val definitions = root.getChildren("step").associateBy { it.getAttributeValue("name") }
        steps = order.map { id ->
            val elem = definitions[id] ?: error("Missing official desktop tutorial step $id")
            val text = elem.getGameTextChild("PC") ?: elem.getGameTextChild("default")
                ?: error("Missing desktop tutorial instruction $id")
            val continueButton = when (elem.getChildTextTrim("continue")) {
                "true" -> true
                "false" -> false
                else -> error("Missing tutorial continuation rule $id")
            }
            val arrows = elem.getChildren("arrow").filter { it.getAttributeValue("type") in listOf(null, "PC") }.map { arrow ->
                val anchor = when (arrow.getAttributeValue("relative")) {
                    null -> Anchor.SCREEN
                    "upgrades" -> Anchor.UPGRADES
                    else -> error("Unsupported desktop tutorial arrow anchor in $id")
                }
                val angle = arrow.getChildTextTrim("angle").toInt()
                require(angle in listOf(0, 90, 180, 270)) { "Unsupported tutorial arrow angle in $id" }
                Arrow(arrow.getChildTextTrim("x").toInt(), arrow.getChildTextTrim("y").toInt(), angle, anchor)
            }
            Step(id, text, continueButton, arrows)
        }
    }
}
