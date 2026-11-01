package xyz.znix.xftl.crew

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.GameText
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour

class CrewBlueprint(elem: Element) : Blueprint(elem) {
    private val xmlCost: Int = elem.getChildTextTrim("cost").toInt()
    override val cost: Int get() = hsCost ?: xmlCost

    /**
     * A list of text ID keys corresponding to this race's special abilities.
     *
     * This is used for UI purposes only.
     */
    val powerStringIds: List<GameText> =
        elem.getChild("powerList")?.getChildren("power")?.map { GameText.parse(it) }
            ?: emptyList()

    /**
     * A list of all the colour filters that can be used as a filter
     * for rendering the layer1 (or in the case of humans, layer2) images.
     */
    val colourFilters: List<List<Colour>> = elem.getChild("colorList")
        ?.getChildren("layer")?.map { parseColourLayer(it) }
        ?: emptyList()

    /**
     * The images representing each of the layers tinted by [colourFilters].
     */
    val layerImageNames: List<String>

    /**
     * In the case of humans, this is the female version of [layerImageNames].
     *
     * For all other races, this is empty.
     */
    val femaleLayerImageNames: List<String>

    /**
     * The number of colour variations this crewmember has, not including
     * gender for humans.
     */
    val baseNumberOfColours: Int = colourFilters.maxOfOrNull { it.size } ?: 0

    /**
     * The number of colour variations this crewmember has.
     *
     * This is distinct from [colourFilters].size, as humans have male/female variants.
     */
    val numberOfColours: Int = run {
        return@run when (name) {
            "human" -> baseNumberOfColours * 2
            else -> baseNumberOfColours
        }
    }

    init {
        // Load all the layer images for this race
        layerImageNames = ArrayList()
        for (i in 1..colourFilters.size) {
            layerImageNames += "img/people/${name}_layer$i.png"
        }

        if (name == "human") {
            femaleLayerImageNames = layerImageNames.map { it.replace("human", "female") }
        } else {
            femaleLayerImageNames = emptyList()
        }
    }

    fun spawn(room: Room, mode: AbstractCrew.SlotType): LivingCrew {
        val crew = spawnRace(room, mode)
        // Hyperspace CONSTRUCT_CREWMEMBER (CrewMember::constructor, priority 900).
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.constructCrewMember(crew)
        return crew
    }

    /** The crew member as the factory makes it, without CONSTRUCT_CREWMEMBER (Hyperspace's dummy crew fires it itself). */
    internal fun hsSpawnSilent(room: Room, mode: AbstractCrew.SlotType): LivingCrew = spawnRace(room, mode)

    private fun spawnRace(room: Room, mode: AbstractCrew.SlotType): LivingCrew {
        val animations = room.ship.sys.animations

        // Hyperspace: every race of hyperspace.xml <crew> is a plain CrewMember driven by its definition
        // (CustomCrewManager::CreateCrewMember), vanilla names included.
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.crew.CustomCrewManager.isRace(name))
            return xyz.znix.xftl.hs.crew.HsCrewMember(this, animations, room, mode)

        return when (name) {
            "human" -> CrewHuman(this, animations, room, mode)
            "engi" -> CrewEngi(this, animations, room, mode)
            "mantis" -> CrewMantis(this, animations, room, mode)
            "rock" -> CrewRock(this, animations, room, mode)
            "energy" -> CrewZoltan(this, animations, room, mode)
            "slug" -> CrewSlug(this, animations, room, mode)
            "crystal" -> CrewCrystal(this, animations, room, mode)
            "anaerobic" -> CrewAnaerobic(this, animations, room, mode)
            "ghost" -> CrewGhost(this, animations, room, mode)

            // CrewMemberFactory::CreateCrewmember (0x4685a0) builds a plain
            // CrewMember from the 'human' blueprint for any race name that
            // isn't one of the nine above, so this is the original's behaviour.
            else -> {
                // Not silent in a Hyperspace session: the race is recorded as a capability gap.
                if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.HS.unsupported("race", "spawn", name)
                val human = room.ship.sys.blueprintManager["human"] as CrewBlueprint
                return human.spawnRace(room, mode)
            }
        }
    }

    companion object {
        private fun parseColourLayer(layerElem: Element): List<Colour> {
            val colourElements = layerElem.getChildren("color")

            return colourElements.map {
                // Alpha is 0-1, the others are 0-255
                Colour(
                    it.getAttributeValue("r").toInt() / 255f,
                    it.getAttributeValue("g").toInt() / 255f,
                    it.getAttributeValue("b").toInt() / 255f,
                    it.getAttributeValue("a").toFloat()
                )
            }
        }
    }
}
