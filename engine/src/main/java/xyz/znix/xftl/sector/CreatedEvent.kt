package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ResourceSet

/**
 * An event as EventGenerator::CreateEvent (0x5df690) hands it out: the base
 * [template] plus everything the creation drew - the text picked from a
 * list, the resources, the item, crew member and ship seed - and, created
 * with it, every event its choices lead to.
 */
class CreatedEvent(val template: Event, val level: Int) {
    /** The text picked for this event; null if the template has none. */
    var text: EventText? = null

    var fuel: Int = 0
    var missiles: Int = 0
    var droneParts: Int = 0
    var scrap: Int = 0

    var weapon: Blueprint? = null
    var drone: Blueprint? = null
    var augment: Blueprint? = null

    /** The crew member the event gives (LocationEvent +0x4a0..+0x5a8), if any. */
    var crew: CreatedCrew? = null

    /** The ship event seed (LocationEvent +0x444); zero when the event has no ship. */
    var shipSeed: Int = 0

    /** The rolled number of boarders (LocationEvent +0x880). */
    var boarders: Int = 0

    /** The choices, in template order. */
    val choices = ArrayList<CreatedChoice>()

    /**
     * False for an event that only names its template (older saves, the
     * console): its contents are rolled when it is shown.
     */
    var eager: Boolean = true
        private set

    /**
     * The resources this event gives, as shown and applied: the created
     * numbers and items and crew, plus what the event applies when it
     * closes (damage, upgrades, pursuit, crew loss, boarders).
     */
    fun toResourceSet(game: InGameState): ResourceSet {
        val rs = ResourceSet()
        rs.fuel = fuel
        rs.missiles = missiles
        rs.droneParts = droneParts
        rs.scrap = scrap
        weapon?.let { rs.items += it }
        drone?.let { rs.items += it }
        augment?.let { rs.items += it }
        crew?.let { rs.crew += it.toInfo(game) }
        template.applyRuntimeEffects(game, rs, boarders)
        return rs
    }

    fun saveToXML(elem: Element) {
        elem.setAttribute("eventId", template.deserialisationId)
        elem.setAttribute("level", level.toString())
        text?.let { saveText(elem, "text", it) }
        if (fuel != 0) elem.setAttribute("fuel", fuel.toString())
        if (missiles != 0) elem.setAttribute("missiles", missiles.toString())
        if (droneParts != 0) elem.setAttribute("droneParts", droneParts.toString())
        if (scrap != 0) elem.setAttribute("scrap", scrap.toString())
        weapon?.let { elem.setAttribute("weapon", it.name) }
        drone?.let { elem.setAttribute("drone", it.name) }
        augment?.let { elem.setAttribute("augment", it.name) }
        if (shipSeed != 0) elem.setAttribute("shipSeed", shipSeed.toString())
        if (boarders != 0) elem.setAttribute("boarders", boarders.toString())
        crew?.let {
            val crewElem = Element("crew")
            it.saveToXML(crewElem)
            elem.addContent(crewElem)
        }
        for ((index, choice) in choices.withIndex()) {
            val choiceElem = Element("choice")
            choiceElem.setAttribute("index", index.toString())
            saveText(choiceElem, "text", choice.text)
            choice.event?.let {
                val child = Element("event")
                it.saveToXML(child)
                choiceElem.addContent(child)
            }
            elem.addContent(choiceElem)
        }
    }

    companion object {
        /** Wrap a template without creating anything (saves and tools that name an event directly). */
        @JvmStatic
        fun fromTemplate(template: Event): CreatedEvent {
            val created = CreatedEvent(template, 0)
            created.eager = false
            return created
        }

        fun loadFromXML(elem: Element, game: InGameState): CreatedEvent {
            val template = game.eventManager.getByDeserialisationId(elem.getAttributeValue("eventId"))
            val created = CreatedEvent(template, elem.getAttributeValue("level")?.toInt() ?: 0)
            created.text = loadText(elem, "text", game)
            created.fuel = elem.getAttributeValue("fuel")?.toInt() ?: 0
            created.missiles = elem.getAttributeValue("missiles")?.toInt() ?: 0
            created.droneParts = elem.getAttributeValue("droneParts")?.toInt() ?: 0
            created.scrap = elem.getAttributeValue("scrap")?.toInt() ?: 0
            created.weapon = elem.getAttributeValue("weapon")?.let { game.blueprintManager[it] as Blueprint }
            created.drone = elem.getAttributeValue("drone")?.let { game.blueprintManager[it] as Blueprint }
            created.augment = elem.getAttributeValue("augment")?.let { game.blueprintManager[it] as Blueprint }
            created.shipSeed = elem.getAttributeValue("shipSeed")?.toInt() ?: 0
            created.boarders = elem.getAttributeValue("boarders")?.toInt() ?: 0
            elem.getChild("crew")?.let { created.crew = CreatedCrew.loadFromXML(it, game) }
            for (choiceElem in elem.getChildren("choice")) {
                val index = choiceElem.getAttributeValue("index").toInt()
                val child = choiceElem.getChild("event")?.let { loadFromXML(it, game) }
                created.choices += CreatedChoice(template.choices[index], loadText(choiceElem, "text", game) ?: EventText(""), child)
            }
            return created
        }

        private fun saveText(elem: Element, name: String, text: EventText) {
            // Texts that come from a text id are saved by it so they follow the language.
            if (text.id != null)
                elem.setAttribute(name + "Id", text.id)
            else
                elem.setAttribute(name + "Literal", text.localised)
        }

        private fun loadText(elem: Element, name: String, game: InGameState): EventText? {
            elem.getAttributeValue(name + "Id")?.let {
                val images = game.eventManager.textImages[it]
                return EventText(game.translator[it], it, images?.first, images?.second)
            }
            elem.getAttributeValue(name + "Literal")?.let { return EventText(it) }
            return null
        }
    }
}

/** A choice of a [CreatedEvent]: the text drawn for it and the event it leads to. */
class CreatedChoice(val template: Choice, val text: EventText, val event: CreatedEvent?)

/** The crew member an event hands out, with the draws that made it. */
class CreatedCrew(
    val race: CrewBlueprint,
    val name: String,
    val colour: Int,
    val skills: Map<Skill, Float>
) {
    fun toInfo(game: InGameState): LivingCrewInfo {
        val info = LivingCrewInfo(game, race, name, colour)
        info.skills.putAll(skills)
        return info
    }

    fun saveToXML(elem: Element) {
        elem.setAttribute("race", race.name)
        elem.setAttribute("name", name)
        elem.setAttribute("colour", colour.toString())
        for ((skill, value) in skills) {
            if (value == 0f) continue
            elem.addContent(Element("skill").setAttribute("id", skill.name).setAttribute("value", value.toString()))
        }
    }

    companion object {
        fun loadFromXML(elem: Element, game: InGameState): CreatedCrew {
            val skills = LinkedHashMap<Skill, Float>()
            for (skill in elem.getChildren("skill"))
                skills[Skill.valueOf(skill.getAttributeValue("id"))] = skill.getAttributeValue("value").toFloat()
            return CreatedCrew(
                game.blueprintManager[elem.getAttributeValue("race")] as CrewBlueprint,
                elem.getAttributeValue("name"), elem.getAttributeValue("colour").toInt(), skills
            )
        }
    }
}
