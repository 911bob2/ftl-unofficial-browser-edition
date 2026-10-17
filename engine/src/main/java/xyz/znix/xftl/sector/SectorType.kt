package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.mapChildrenText
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.requireAttributeValueInt

class SectorType(private val eventManager: EventManager, elem: Element, displayAs: SectorType? = null) {
    val name: String = elem.requireAttributeValue("name")
    val soundtracks: List<String> = elem.getChild("trackList").mapChildrenText("track")
    // GenerateMap (0x5f8ca0) creates this for the start beacon. The final
    // sector names BOSS_NEUTRAL, which it then replaces with LAST_STAND_START.
    val startEventName: String = elem.getChildTextTrim("startEvent") ?: "START_BEACON"
    val startEvent: IEvent get() = startEventName.let { eventManager[it] }

    /**
     * The (zero-indexed) earliest sector this is allowed to occur at.
     */
    val minSector: Int = elem.getAttributeValue("minSector")?.toInt() ?: -1

    val unique: Boolean = elem.getAttributeValue("unique")!!.toBoolean()

    val events: List<EventInfo> = elem.getChildren("event").map {
        val name = it.requireAttributeValue("name")
        val count = it.requireAttributeValueInt("min")..it.requireAttributeValueInt("max")
        // The sector event entries go through the same min/max/chance parser
        // as item_modify (0x5e1cd0), so an absent chance is -1.
        val chance = it.getAttributeValue("chance")?.toFloat() ?: -1f
        EventInfo(name, count, eventManager[name], chance)
    }

    /**
     * The sector names (`<nameList>`): GetSectorDescription (0x5da010) draws
     * one of them per sector and drops it from the list while two or more
     * remain.
     */
    val names: List<SectorName>

    val nameTextId: GameText
    val shortTextId: GameText

    val rarityOverrides: Map<String, Int>

    init {
        val nameList = elem.getChild("nameList")
        check(nameList.children.isNotEmpty())
        names = nameList.children.map { nameElem ->
            // In vanilla, both of these are specified as localisation keys for
            // all sector types. However, mods don't have to.
            // I haven't checked if the short text behaviour is correct, this is
            // just a guess.
            // This is reproduced with the Insurrection mod.
            val full = GameText.parse(nameElem)
            SectorName(full, nameElem.getAttributeValue("short")?.let { GameText.localised(it) } ?: full)
        }

        // NewGame's first sector draws its events from STANDARD_SPACE but is
        // named and labelled as the civilian sector (0x5f0ef0).
        nameTextId = displayAs?.nameTextId ?: names[0].full
        shortTextId = displayAs?.shortTextId ?: names[0].short

        // Load the rarity overrides
        check(elem.getChildren("rarityList").size <= 1)
        rarityOverrides = elem.getChild("rarityList")?.children?.map { e ->
            check(e.name == "blueprint")
            check(e.children.size == 0)
            Pair(e.requireAttributeValue("name"), e.requireAttributeValueInt("rarity"))
        }?.toMap() ?: emptyMap()
    }

    class EventInfo(val name: String, val count: IntRange, val event: IEvent, val chance: Float = -1f)

    class SectorName(val full: GameText, val short: GameText)
}
