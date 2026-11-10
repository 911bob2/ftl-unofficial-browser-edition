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

    /** The boarders came aboard when this event was loaded, ahead of its box. */
    var boardersAboard: Boolean = false

    /** The choices, in template order. */
    val choices = ArrayList<CreatedChoice>()

    /**
     * Hyperspace Lua: members of LocationEvent / ResourceEvent that the engine has no field for, as a script last
     * wrote them (keyed by the Lua attribute id, offset by the view's slot). Not saved: nothing in the engine reads them.
     */
    @JvmField var luaExtra: HashMap<Int, Any?>? = null

    /**
     * Hyperspace Lua: the extra text CreateChoiceBox has appended to this event's text (the quest and ship unlock
     * lines) while PRE_CREATE_CHOICEBOX runs; a script that writes the text replaces it, and an empty value here then
     * drops it from the box. Null outside that call.
     */
    @JvmField var hsExtraText: String? = null

    /**
     * Hyperspace (CustomEvents.cpp's WorldManager::CreateLocation / UpdateLocation hooks, priority 100): the event was
     * shown while the player's ship held none of its own crew (CountCrew(false) 0), so its crew loss is 0
     * (LocationEvent::stuff.crew and reward.crew), nobody is picked and its box has no crew line. Not saved.
     */
    @JvmField var hsCrewLossCleared: Boolean = false

    /**
     * The status effects this event applies in place of its template's (`LocationEvent::statusEffects` as the
     * creator left them); null keeps the template's. Hyperspace's BossShip::GetEvent hook (CustomBoss.cpp) clears the
     * list of the flagship's events.
     */
    @JvmField var statusOverride: List<EventStatus>? = null

    /** Hyperspace Lua: a script removed the implicit "continue" choice an event without choices has. */
    @JvmField var hsContinueRemoved = false

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
    fun toResourceSet(game: InGameState, pickCrew: Boolean = true): ResourceSet {
        val rs = ResourceSet()
        rs.fuel = fuel
        rs.missiles = missiles
        rs.droneParts = droneParts
        rs.scrap = scrap
        weapon?.let { rs.items += it }
        drone?.let { rs.items += it }
        augment?.let { rs.items += it }
        crew?.let { rs.crew += it.toInfo(game) }
        template.applyRuntimeEffects(game, rs, boarders, pickCrew)
        rs.boardersAboard = boardersAboard
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
        if (boardersAboard) elem.setAttribute("boardersAboard", "true")
        crew?.let {
            val crewElem = Element("crew")
            it.saveToXML(crewElem)
            elem.addContent(crewElem)
        }
        for ((index, choice) in choices.withIndex()) {
            val choiceElem = Element("choice")
            choiceElem.setAttribute("index", if (choice.added) "-1" else template.choices.indexOf(choice.origin).toString())
            if (choice.ownsTemplate) {
                val t = choice.template
                choiceElem.setAttribute("own", "true")
                t.req?.let { choiceElem.setAttribute("req", it) }
                t.minLevel?.let { choiceElem.setAttribute("lvl", it.toString()) }
                t.maxLevel?.let { choiceElem.setAttribute("max_lvl", it.toString()) }
                t.maxGroup?.let { choiceElem.setAttribute("max_group", it.toString()) }
                choiceElem.setAttribute("blue", t.blue.toString())
                choiceElem.setAttribute("hidden", t.hidden.toString())
            }
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
            // A reward whose mod left the save's list: the event stays, without that reward.
            fun savedItem(name: String): Blueprint? =
                game.blueprintManager.getOrNull(name) as? Blueprint ?: run { xyz.znix.xftl.savegame.MissingContent.note(name); null }
            val template = game.eventManager.getSavedEvent(elem.getAttributeValue("eventId"))
            val created = CreatedEvent(template, elem.getAttributeValue("level")?.toInt() ?: 0)
            created.text = loadText(elem, "text", game)
            created.fuel = elem.getAttributeValue("fuel")?.toInt() ?: 0
            created.missiles = elem.getAttributeValue("missiles")?.toInt() ?: 0
            created.droneParts = elem.getAttributeValue("droneParts")?.toInt() ?: 0
            created.scrap = elem.getAttributeValue("scrap")?.toInt() ?: 0
            created.weapon = elem.getAttributeValue("weapon")?.let(::savedItem)
            created.drone = elem.getAttributeValue("drone")?.let(::savedItem)
            created.augment = elem.getAttributeValue("augment")?.let(::savedItem)
            created.shipSeed = elem.getAttributeValue("shipSeed")?.toInt() ?: 0
            created.boarders = elem.getAttributeValue("boarders")?.toInt() ?: 0
            created.boardersAboard = elem.getAttributeValue("boardersAboard") == "true"
            elem.getChild("crew")?.let { created.crew = CreatedCrew.loadFromXML(it, game) }
            for (choiceElem in elem.getChildren("choice")) {
                val index = choiceElem.getAttributeValue("index").toInt()
                val child = choiceElem.getChild("event")?.let { loadFromXML(it, game) }
                val text = loadText(choiceElem, "text", game) ?: EventText("")
                // Other mods can have given the event's name fewer choices (Saves > Advanced): the rest goes.
                val origin = if (index < 0) null else template.choices.getOrNull(index) ?: continue
                val choice = if (origin == null) CreatedChoice.added(text, child!!) else CreatedChoice(origin, text, child)
                if (choiceElem.getAttributeValue("own") == "true") {
                    val t = choice.ownTemplate()
                    t.req = choiceElem.getAttributeValue("req")
                    t.minLevel = choiceElem.getAttributeValue("lvl")?.toInt()
                    t.maxLevel = choiceElem.getAttributeValue("max_lvl")?.toInt()
                    t.maxGroup = choiceElem.getAttributeValue("max_group")?.toInt()
                    t.blue = choiceElem.getAttributeValue("blue")?.toBoolean() ?: false
                    t.hidden = choiceElem.getAttributeValue("hidden")?.toBoolean() ?: false
                }
                created.choices += choice
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
class CreatedChoice(template: Choice, var text: EventText, var event: CreatedEvent?) {
    /** The template's own choice, whatever [template] was replaced with. */
    val origin: Choice = template

    /**
     * The choice's definition. A script (Hyperspace Lua) that changes the requirement or the hidden flag gets its own
     * copy first ([ownTemplate]), so the shared template stays as the XML has it.
     */
    var template: Choice = template
        private set

    /** True once [template] is this instance's own copy (saved with the event). */
    var ownsTemplate = false
        private set

    /** True for a choice a script added (LocationEvent:AddChoice): it has no template of the event. */
    var added = false
        internal set

    /** Hyperspace Lua: a script wrote the requirement's `blue` (until then it reads true, as the native default). */
    @JvmField var blueSet = false

    /** Hyperspace Lua: the implicit "continue" choice of an event without choices (a view only; no engine choice). */
    @JvmField var isContinueChoice = false

    fun ownTemplate(): Choice {
        if (!ownsTemplate) { template = template.copy(); ownsTemplate = true }
        return template
    }

    /** An added choice: [event] is the event it leads to; the requirement is set on the returned definition. */
    companion object {
        @JvmStatic
        fun added(text: EventText, event: CreatedEvent): CreatedChoice {
            val def = Choice(text, lazyOf(event.template), org.jdom2.Element("choice"), "")
            def.blue = false
            val c = CreatedChoice(def, text, event)
            c.ownsTemplate = true
            c.added = true
            return c
        }
    }
}

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
