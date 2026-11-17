package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.mapChildrenText
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.requireAttributeValueInt

class SectorType(private val eventManager: EventManager, elem: Element, displayAs: SectorType? = null) {
    val name: String = elem.requireAttributeValue("name")
    // The original's parser (0x5e8510) gives a description without a trackList the one track "civilian".
    val soundtracks: List<String> = elem.getChild("trackList")?.mapChildrenText("track") ?: listOf("civilian")
    // GenerateMap (0x5f8ca0) creates this for the start beacon. The final
    // sector names BOSS_NEUTRAL, which it then replaces with LAST_STAND_START.
    val startEventName: String = elem.getChildTextTrim("startEvent") ?: "START_BEACON"
    val startEvent: IEvent get() = eventManager.getOrNull(startEventName) ?: eventManager["START_BEACON"]

    /**
     * The (zero-indexed) earliest sector this is allowed to occur at.
     */
    val minSector: Int = elem.getAttributeValue("minSector")?.let { atoiCompat(it) } ?: -1

    // Not unique without the attribute (0x5e8510 starts from false and reads minSector with atoi).
    val unique: Boolean = elem.getAttributeValue("unique")?.toBoolean() ?: false

    val events: List<EventInfo> = elem.getChildren("event").mapNotNull {
        val name = it.requireAttributeValue("name")
        val count = it.requireAttributeValueInt("min")..it.requireAttributeValueInt("max")
        // The sector event entries go through the same min/max/chance parser
        // as item_modify (0x5e1cd0), so an absent chance is -1.
        val chance = it.getAttributeValue("chance")?.toFloat() ?: -1f
        // An entry naming an event that is not there places nothing.
        if (xyz.znix.xftl.hs.HS.modded && eventManager.getOrNull(name) == null) return@mapNotNull null
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
    /** NewGame's first sector names itself after another type (the description's name is that type's). */
    val displayAsName: Boolean = displayAs != null

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

        // Load the rarity overrides. The original (0x5e8510) reads the first rarityList: every child of it, whatever
        // its name, gives its `name` and atoi of its `rarity`, with no range (SetRarity 0x60b8e0 stores it as it is).
        rarityOverrides = elem.getChild("rarityList")?.children?.map { e ->
            Pair(e.requireAttributeValue("name"), atoiCompat(e.requireAttributeValue("rarity")))
        }?.toMap() ?: emptyMap()
    }

    class EventInfo(val name: String, val count: IntRange, val event: IEvent, val chance: Float = -1f)

    class SectorName(val full: GameText, val short: GameText)
}
