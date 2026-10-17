package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.requireAttributeValue
import kotlin.math.absoluteValue
import kotlin.random.Random

// Many of the comments in this file came from ftlwiki.com (now unfortunately
// offline, but accessable via the wayback machine):
// https://ftlwiki.com/wiki/Events_file_structure

/**
 * Represents something that can be used as an event. This is either an Event or
 * an EventList.
 *
 * When this is to be used, [resolve] is called which provides a concrete event.
 */
interface IEvent {
    fun resolve(): Event

    val debugId: String
}

/** Tracks which `unique="true"` events have already been created. */
interface UniqueEventRegistry {
    fun isConsumed(event: Event): Boolean
    fun consume(event: Event)

    /** EventGenerator 0x5db020: every consumed unique event is available again. */
    fun restoreAll()
}

/**
 * The `<min>/<max>/chance` triple of the original's range parser (0x5e1cd0):
 * the chance defaults to -1 (never a miss) when the attribute is absent.
 */
class RangeSpec(val min: Int, val max: Int, val chance: Float) {
    companion object {
        /** The block of an event without `<item_modify>`: all zero, no chance. */
        val NONE = RangeSpec(0, 0, 0f)

        fun parse(elem: Element) = RangeSpec(
            elem.getAttributeValue("min")?.toInt() ?: 0,
            elem.getAttributeValue("max")?.toInt() ?: 0,
            elem.getAttributeValue("chance")?.toFloat() ?: -1f
        )
    }
}

class Event(
    val text: IEventText?, val choices: List<Choice>, elem: Element, override val debugId: String,

    /**
     * A unique ID that can be used to index all the game's events,
     * and load this exact event.
     *
     * This has to handle any event, even unnamed ones that can
     * only be accessed via choices, as it's how the game remembers
     * what event the player had open when they closed the game.
     */
    val deserialisationId: String,

    imageFinder: (String) -> ImageList, loadText: (Element) -> IEventText,

    private val uniqueRegistry: UniqueEventRegistry? = null
) : IEvent {
    /**
     * `unique="true"` (event+0x15d): EventGenerator::CreateEvent (0x5dce90)
     * takes a unique event out of the base-event map the first time it
     * creates it, so it is produced at most once per session.
     */
    val unique: Boolean = elem.getAttributeValue("unique")?.trim()?.toBoolean() ?: false

    val isConsumedUnique: Boolean get() = unique && uniqueRegistry?.isConsumed(this) == true

    val isDistressBeacon: Boolean = elem.getChild("distressBeacon") != null
    val isStore: Boolean = elem.getChild("store") != null

    val itemsModifySteal: Boolean
    val itemsModify: Map<Resource, IntRange>

    /** `<item_modify>`'s own min/max: how many of the resources may be given (0x5e1ed0). */
    val resourceLimit: RangeSpec

    /** The range of each resource; all of them [RangeSpec.NONE] without `<item_modify>`. */
    val resourceRanges: Map<Resource, RangeSpec>

    /** The weapon, drone and augment the event names (`RANDOM` or a blueprint or list name). */
    val weaponName: String? = elem.getChild("weapon")?.getAttributeValue("name")
    val droneName: String? = elem.getChild("drone")?.getAttributeValue("name")
    val augmentName: String? = elem.getChild("augment")?.getAttributeValue("name")

    /** The event has a `<ship>` element (also the load-less `<ship hostile="true"/>`). */
    val hasShip: Boolean = elem.getChild("ship") != null
    val addedCrew: List<AddCrew>
    val removedCrew: List<RemoveCrew>
    val autoRewards: Pair<RewardType, RewardTier>?
    val blueprintRewards: List<String>
    val hullDamage: List<EventHullDamage>
    val systemUpgrades: List<EventSystemUpgrade>

    val modifyPursuit: Int = elem.getChild("modifyPursuit")?.getAttributeValue("amount")?.toInt() ?: 0
    val unlockShip: String? = elem.getChild("unlockShip")?.getAttributeValue("id")
    val secretSector: Boolean = elem.getChild("secretSector") != null

    val boarderRace: String?
    val boarderCount: IntRange

    /** `<boarders breach="true">`: they cut in through the hull instead of teleporting. */
    val boarderBreach: Boolean

    val statuses: List<EventStatus>
    val removedStuff: List<String>

    /**
     * If this event triggers a quest, this is its event name.
     */
    val questName: String? = elem.getChild("quest")?.getAttributeValue("event")

    val revealMap: Boolean = elem.getChild("reveal_map") != null
    val isLastStandRepair: Boolean = elem.getChild("repair") != null

    val fleetBackground: FleetBackground? = elem.getChildTextTrim("fleet")
        ?.let { name -> FleetBackground.values().first { it.xmlName == name } }

    init {
        // Initialise these in the constructor so we can mutate them,
        // which isn't otherwise possible as they're declared as List.
        addedCrew = ArrayList()
        removedCrew = ArrayList()

        val modElem = elem.getChild("item_modify")
        if (modElem != null) {
            itemsModifySteal = modElem.getAttributeValue("steal")?.toBoolean() ?: false
            val items = HashMap<Resource, IntRange>()
            itemsModify = items

            val ranges = HashMap<Resource, RangeSpec>()
            for (res in Resource.values())
                ranges[res] = RangeSpec.NONE
            for (child in modElem.children) {
                check(child.name == "item")
                val type = Resource.byName(child.getAttributeValue("type")) ?: continue
                val min = child.getAttributeValue("min").toInt()
                val max = child.getAttributeValue("max").toInt()
                items[type] = min..max
                ranges[type] = RangeSpec.parse(child)
            }
            resourceRanges = ranges
            resourceLimit = RangeSpec.parse(modElem)
        } else {
            itemsModifySteal = false
            itemsModify = emptyMap()
            resourceRanges = Resource.values().associateWith { RangeSpec.NONE }
            resourceLimit = RangeSpec.NONE
        }

        val crewElem = elem.getChild("crewMember")
        if (crewElem != null) {
            val count = crewElem.getAttributeValue("amount").toInt()

            val race = crewElem.getAttributeValue("class")

            // Although it's not used by vanilla, a literal name
            // can be inserted as the element's text.
            // This is used by Insurrection+, you can check the
            // DISTRESS_STATION_FIRE_RESCUE event to test it.
            val nameId = crewElem.getAttributeValue("id")
            val nameLiteral = crewElem.textTrim
            val name: GameText? = when {
                nameId != null -> GameText.localised(nameId)
                nameLiteral.isNotEmpty() -> GameText.literal(nameLiteral)
                else -> null
            }

            val skills = HashMap<Skill, Float>()
            crewElem.getAttributeValue("all_skills")?.toInt()?.let { level ->
                for (skill in Skill.values()) {
                    // Use 0.5 times so that 1 is fully green, and 2 is fully yellow.
                    skills[skill] = 0.5f * level
                }
            }
            for (skill in Skill.values()) {
                crewElem.getAttributeValue(skill.xmlName)?.toInt()?.let { level ->
                    skills[skill] = 0.5f * level
                }
            }

            for (i in 0 until count) {
                addedCrew.add(AddCrew(race, name, skills))
            }

            // Special-case the crew-removing event used in STATION_SICK
            if (count < 0) {
                for (i in 0 until -count) {
                    // Using an empty clone message hides the dialogue window
                    val emptyText = EventText("")

                    removedCrew.add(RemoveCrew(false, emptyText, null, race == "traitor", this))
                }
            }
        }

        val killCrew = elem.getChild("removeCrew")
        if (killCrew != null) {
            // The race is an ATTRIBUTE of <removeCrew>, e.g.
            // <removeCrew class="engi"> (ENGI_VIRUS, QUEST_CONSTRUCTIONYARD).
            // Fall back to a child element for mods using that format.
            val race = killCrew.getAttributeValue("class")
                ?: killCrew.getChildText("class")
            val clone = killCrew.getChildText("clone")!!.toBoolean()
            val cloneText = loadText(killCrew.getChild("text"))
            removedCrew.add(RemoveCrew(clone, cloneText, race, false, this))
        }

        val boardersElem = elem.getChild("boarders")
        if (boardersElem != null) {
            // TODO what's the default race?
            boarderRace = boardersElem.getAttributeValue("class") ?: "random"
            val min = boardersElem.getAttributeValue("min").toInt()
            val max = boardersElem.getAttributeValue("max").toInt()
            boarderCount = min..max
            boarderBreach = boardersElem.getAttributeValue("breach")?.toBoolean() ?: false
        } else {
            boarderRace = null
            boarderCount = 0..0
            boarderBreach = false
        }

        val auto = elem.getChild("autoReward")
        autoRewards = if (auto != null) {
            val type = if (auto.textTrim.equals("scrap", true)) RewardType.SCRAP_ONLY else RewardType.valueOf(auto.textTrim.toUpperCase())
            val tier = RewardTier.fromName(auto.getAttributeValue("level"))
            Pair(type, tier)
        } else {
            null
        }

        blueprintRewards = ArrayList()
        for (e in listOf("weapon", "drone", "augment").flatMap(elem::getChildren)) {
            val name = e.requireAttributeValue("name")
            check(e.children.size == 0)
            blueprintRewards += if (name == "RANDOM") "xftl_rand_${e.name.toLowerCase()}" else name
        }
        blueprintRewards.trimToSize()

        hullDamage = ArrayList()
        for (damageElem in elem.getChildren("damage")) {
            val amount = damageElem.getAttributeValue("amount")!!.toInt()
            val system: String? = damageElem.getAttributeValue("system")

            // The parser (0x5e52c0) maps fire/breach/all/random to 1/2/3/4.
            val effect: String? = damageElem.getAttributeValue("effect")
            val isFire = effect == "fire" || effect == "all"
            val isBreach = effect == "breach" || effect == "all"
            val isRandom = effect == "random"

            hullDamage.add(EventHullDamage(amount, system, isFire, isBreach, isRandom))
        }

        systemUpgrades = ArrayList()
        for (upgradeElem in elem.getChildren("upgrade")) {
            val amount = upgradeElem.getAttributeValue("amount")!!.toInt()
            val system = upgradeElem.getAttributeValue("system")!!
            systemUpgrades.add(EventSystemUpgrade(amount, system))
        }

        statuses = ArrayList()
        statusLoop@ for (statusElem in elem.getChildren("status")) {
            val op = when (val type = statusElem.getAttributeValue("type")) {
                "limit" -> EventStatus.Operation.LIMIT
                "clear" -> EventStatus.Operation.CLEAR
                "divide" -> EventStatus.Operation.DIVIDE
                "loss" -> EventStatus.Operation.LOSS
                else -> {
                    println("Warning: unimplemented status type '$type' in event '$debugId'")
                    continue@statusLoop
                }
            }

            val target = when (val tgt = statusElem.getAttributeValue("target")) {
                "player" -> EventStatus.Target.PLAYER
                "enemy" -> EventStatus.Target.ENEMY
                // The original (0x5e52c0) reads any other target text as 2: both ships.
                null -> {
                    println("Warning: status without a target in event '$debugId'")
                    continue@statusLoop
                }

                else -> EventStatus.Target.BOTH
            }

            val system = statusElem.getAttributeValue("system")

            // In the case of type=clear, no amount is needed.
            val amount = statusElem.getAttributeValue("amount")?.toInt() ?: 0

            statuses.add(EventStatus(system, op, target, amount))
        }

        removedStuff = ArrayList()
        for (e in elem.getChildren("remove")) {
            removedStuff += e.requireAttributeValue("name")
        }
    }

    val loadShipName: String?
    val loadShipHostile: Boolean?

    init {
        val ship = elem.getChild("ship")
        if (ship != null) {
            check(ship.children.isEmpty()) { "Inline ship specs not yet supported" }
            loadShipName = ship.getAttributeValue("load")
            loadShipHostile = ship.getAttributeValue("hostile")?.toBoolean()
        } else {
            loadShipName = null
            loadShipHostile = null
        }
    }

    val backImg: ImageList?
    val planetImg: ImageList?

    init {
        val img = elem.getChild("img")
        backImg = img?.getAttributeValue("back")?.let(imageFinder)
        planetImg = img?.getAttributeValue("planet")?.let(imageFinder)
    }

    val browserPdsTarget: Int = when (elem.getChild("environment")?.getAttributeValue("target")) { "enemy", "1" -> 1; "all", "both", "2" -> 2; else -> 0 }
    val environment: Beacon.EnvironmentType?

    init {
        environment = when (val env = elem.getChild("environment")?.requireAttributeValue("type")) {
            "asteroid" -> Beacon.EnvironmentType.ASTEROID
            "nebula" -> Beacon.EnvironmentType.NEBULA
            "pulsar" -> Beacon.EnvironmentType.PULSAR
            "storm" -> Beacon.EnvironmentType.ION_STORM
            "sun" -> Beacon.EnvironmentType.SUN
            "PDS" -> Beacon.EnvironmentType.PDS
            null -> null
            else -> error("Unknown environment $env")
        }
    }

    /**
     * The event itself. An event created from here consumes its unique
     * flag at the end of its creation (EventGenerator 0x5dce90), not here.
     */
    override fun resolve(): Event = this

    internal fun consumeUnique() {
        if (unique)
            uniqueRegistry?.consume(this)
    }

    /**
     * Evaluate all the random values in this event's rewards, and pack them into a ResourceSet
     */
    fun resolveResources(game: InGameState, rand: Random): ResourceSet {
        // The plain resources (fuel, missiles, etc)
        val resourcesGained = ResourceSet(itemsModify.mapValues { it.value.random(rand) })

        // Add all the blueprints
        resourcesGained.items += blueprintRewards.map { name ->
            when (name) {
                "xftl_rand_weapon" -> game.lootPool.getWeapon(rand)
                "xftl_rand_drone" -> game.lootPool.getDrone(rand)
                "xftl_rand_augment" -> game.lootPool.getAugment(rand)
                else -> game.blueprintManager[name].resolve()
            }
        }

        // Pick names for the new crewmembers
        for (crew in addedCrew) {
            val race = if (crew.race != null) {
                game.blueprintManager[crew.race] as CrewBlueprint
            } else {
                game.lootPool.getRandom(rand) { it is CrewBlueprint } as CrewBlueprint
            }

            val info = if (crew.name != null) {
                // Specifically named by the event (eg Slocknog)
                val name = game.translator[crew.name]
                LivingCrewInfo.generateWithName(race, game, name)
            } else {
                LivingCrewInfo.generateRandom(race, game)
            }

            // Without authored skills, event crew get CreateEvent's
            // random skills for the world level (0x5dce90).
            if (crew.skills.isEmpty())
                LivingCrewInfo.applyRandomSkills(info, game.currentBeacon.sector.sectorNumber, rand)
            info.skills.putAll(crew.skills)

            resourcesGained.crew.add(info)
        }

        // Select crewmembers to kill
        for (info in removedCrew)
            pickCrewToKill(game, info)?.let { resourcesGained.lostCrew.add(RemoveCrewEval(it, info)) }

        // Boarders: their number is rolled now; the race, names and room are drawn when they come aboard
        if (boarderRace != null) {
            resourcesGained.boarderCount = boarderCount.random(ftl.browser.NativeRandom)
            resourcesGained.boarderRace = boarderRace
            resourcesGained.intrudersBreach = boarderBreach
        }

        // For hull damage, we don't need to resolve it - the rooms and systems
        // that are damaged aren't displayed, we only have to evaluate that
        // when the damage is actually applied.
        resourcesGained.damage += hullDamage

        // System upgrades aren't randomised, so we can just copy them over as-is.
        resourcesGained.upgrades += systemUpgrades

        resourcesGained.modifyPursuit = modifyPursuit

        // Add the standard type/tier rewards - these are the standard results and most commonly used
        // eg destroying a ship usually gives STANDARD/MEDIUM rewards.
        if (autoRewards != null) {
            val sector = game.currentBeacon.sector.sectorNumber
            val rewards = LootDropGenerator.generateRewards(
                game, rand, autoRewards.second, autoRewards.first, sector, game.difficulty, blueprintRewards.isNotEmpty()
            )
            resourcesGained += rewards
        }

        return resourcesGained
    }

    /**
     * ShipManager::KillRandomCrew (0x4ae0b0) -> 0x4a3000: the stream is reseeded with the event's crew-kill seed
     * (WorldManager +0x12a0, drawn once per event and shared by every preview and the final application), then
     * `random32() % count` picks among the player's crew that aren't drones, of the named class if any are (if
     * none is, the class is dropped). Null when the ship has no crew.
     */
    internal fun pickCrewToKill(game: InGameState, info: RemoveCrew): LivingCrew? {
        val allCrew = game.player.crew
            .filterIsInstance(LivingCrew::class.java)
            .filter { it.ownerShip == game.player }

        if (allCrew.isEmpty()) {
            println("Warning: Cannot remove crewmember via event, no crew left.")
            return null
        }

        var candidates = if (info.race == null) allCrew else allCrew.filter { it.blueprint.name == info.race }
        if (candidates.isEmpty())
            candidates = allCrew

        ftl.browser.NativeRandom.seed(game.eventManager.crewKillSeed())
        return candidates[ftl.browser.NativeRandom.nextInt(candidates.size)]
    }

    /**
     * What an eagerly created event applies when it closes and leaves to
     * chance at that point: the crew it kills, the boarders (their number
     * was rolled with the event; their race and names are drawn here), the
     * hull damage, the system upgrades and the pursuit change.
     */
    internal fun applyRuntimeEffects(game: InGameState, resourcesGained: ResourceSet, boarderCountRolled: Int) {
        // Select crewmembers to kill
        for (info in removedCrew)
            pickCrewToKill(game, info)?.let { resourcesGained.lostCrew.add(RemoveCrewEval(it, info)) }

        // Spawn boarders: the race, names and room are drawn when they come aboard (0x408260)
        if (boarderRace != null) {
            resourcesGained.boarderCount = boarderCountRolled
            resourcesGained.boarderRace = boarderRace
            resourcesGained.intrudersBreach = boarderBreach
        }

        resourcesGained.damage += hullDamage
        resourcesGained.upgrades += systemUpgrades
        resourcesGained.modifyPursuit = modifyPursuit
    }

    init {
        // Check for and print out warnings for unknown elements
        for (child in elem.children) {
            if (child.name in KNOWN_TAGS)
                continue

            println("Warning: Unknown element '${child.name}' in event '$debugId'")
        }
    }

    companion object {
        private val KNOWN_TAGS = setOf(
            "distressBeacon", "store", "item_modify",
            "crewMember", "removeCrew", "boarders",
            "autoReward", "weapon", "drone", "augment",
            "ship", "img", "environment", "damage",
            "upgrade", "modifyPursuit", "status",
            "quest", "reveal_map", "remove", "fleet",
            "unlockShip", "repair", "secretSector",

            // Used by the code loading the event
            "text", "choice"
        )
    }
}

class EventList(val name: String, events: List<Lazy<IEvent>>) : IEvent {
    val events by lazy { events.map { it.value } }

    override fun resolve(): Event {
        // EventGenerator::CreateEvent (0x5df690) repeats GetEvent until it
        // produces an event: a pick that lands on an already-created unique
        // event yields nothing and the list is rolled again from the top.
        var picked = pick()
        var attempts = 1
        while (picked.isConsumedUnique && attempts < MAX_ROLLS) {
            picked = pick()
            attempts++
        }
        return picked
    }

    /**
     * GetEvent's list loop (0x5dce90): while the name is a list, pick one of
     * its entries with `random32() % size`, so an entry naming its own list
     * (NEBULA_REBEL) just rolls again.
     */
    fun pick(): Event {
        var current: IEvent = this
        while (current is EventList)
            current = current.pickEntry()
        return current as Event
    }

    fun pickEntry(): IEvent = events[ftl.browser.NativeRandom.next31() % events.size]

    override val debugId: String get() = name

    companion object {
        // The original loops forever if every entry is a used unique event.
        private const val MAX_ROLLS = 1000
    }
}

class AddCrew(val race: String?, val name: GameText?, val skills: Map<Skill, Float>)

class RemoveCrew(
    /**
     * If true, this crew member will be saved by a clone bay.
     */
    val clone: Boolean,

    /**
     * If the ship has a clone bay, this is the message that either
     * says your crew has been recovered or explains why they haven't.
     *
     * Note: this is empty for the CRYSTAL_HELP_DIG events, we
     * shouldn't display a popup for those.
     */
    val cloneText: IEventText,

    /**
     * If this event removes a crewmember of a specific race, for
     * example the Engi Virus, this specifies it.
     */
    val race: String?,

    /**
     * If true, this crew member is left on the ship and converted
     * to an enemy, which the infectious space station event uses.
     *
     * This is actually set by <crewMember> not <removeCrew>.
     */
    val turnHostile: Boolean,

    /**
     * The event this object belongs to.
     *
     * This is used to uniquely identify a RemoveCrew object during deserialisation.
     */
    val event: Event
)

class EventHullDamage(
    val amount: Int,

    /**
     * The system this damage should be inflicted to.
     *
     * Null means hull damage only, "random" means a random system, and
     * "room" means a random room (regardless of whether it has a system
     * in it or not).
     */
    val system: String?,

    val effectFire: Boolean,
    val effectBreach: Boolean,

    /** `effect="random"`: either a fire or a breach, with equal chance. */
    val effectRandom: Boolean = false
)

class EventSystemUpgrade(val amount: Int, val system: String)

/**
 * Defines some status effect (or removal thereof) for a ship at the current beacon.
 *
 * This is what's used to limit systems during events, for example
 * the slugs that hack your medbay or oxygen.
 */
class EventStatus(
    /**
     * The name of the system to affect.
     */
    val system: String,

    /**
     * The type of effect to place on the system.
     */
    val op: Operation,

    /**
     * Who is this status applying to?
     */
    val target: Target,

    val amount: Int
) {
    enum class Operation {
        CLEAR, // Remove any prior status effect
        DIVIDE, // Divide the system power by a set amount
        LIMIT, // Limit the system power to at most a set amount
        LOSS, // Subtract the given amount from the system power
    }

    enum class Target { PLAYER, ENEMY, BOTH }
}

class Choice(val text: IEventText, lazyEvent: Lazy<IEvent>, elem: Element, val deserialisationId: String) {
    /**
     * Event to be triggered when this choice is taken. This element is required. One of:
     * A complete event, as detailed above (which can again contain choices).
     * Empty with just the load attribute given: loads the event with the given id.
     * Completely empty (<element />), if you want nothing to happen at all.
     */
    val event by lazyEvent

    /**
     * true/false, false if omitted. When set to false or omitted, it causes rewards (fuel, drone parts, scrap, etc)
     * in the choice's event (specified by <item_modify> or <autoReward>) to appear next to the choice's text.
     * See req for behavior of hidden when used alongside req.
     */
    val hidden: Boolean = elem.getAttributeValue("hidden")?.toBoolean() ?: false

    /**
     * The name of any race, weapon, drone, augmentation or system/subsystem.
     *
     * When hidden is set to true, the choice will be visible, able to be selected, and will be a blue choice
     *   (unless blue is set to false), if and only if the player has whatever is specified by the req, otherwise
     *   it will not be shown (hidden).
     * When hidden is omitted or set to false and the player does not have the listed req, the player will be able
     *   to see the choice but will be unable to select it (it will be grayed out).
     */
    val req: String? = elem.getAttributeValue("req")

    /**
     * The minimum level of a system required by [req].
     */
    val minLevel: Int? = elem.getAttributeValue("lvl")?.toInt()

    /**
     * The maximum level of a system required by [req].
     *
     * Note this is usually used with (or replaced by) [maxGroup].
     */
    val maxLevel: Int? = elem.getAttributeValue("max_lvl")?.toInt()

    /**
     * This defines a group of choices, only one of which can be displayed.
     *
     * If multiple choices with the same max_group attribute are available, all
     * except the last one (in the order defined in the XML) will be hidden.
     *
     * This behaviour doesn't seem to be documented anywhere, and is largely
     * guessed from the XML.
     */
    val maxGroup: Int? = elem.getAttributeValue("max_group")?.toInt()

    /**
     * true/false, true if omitted. Determines whether the choice will appear as (literally) a blue choice.
     * Only has meaning when used alongside req, as only req can make the choice blue in the first place.
     */
    val blue: Boolean = elem.getAttributeValue("blue")?.toBoolean() ?: (req != null)
}

interface IEventText {
    fun resolve(rand: Random): String
}

class TextList(val name: String, val items: List<IEventText>) : IEventText {
    // CreateEvent removes the chosen entry while two or more remain, so a
    // list's texts don't repeat until only one is left. The removed entries
    // wait in [used] until the next map generation inserts them back at the
    // front of the list, in removal order (EventGenerator 0x5db020,
    // vector range-insert at begin() from 0x5db0b8).
    private val remaining = ArrayList(items)
    private val used = ArrayList<IEventText>()

    /** `random32() % size`; with [remove] the entry leaves the list while two or more remain. */
    fun pick(rand: Random, remove: Boolean): IEventText {
        if (remaining.isEmpty())
            return EventText("")
        val index = rand.nextInt(remaining.size)
        val text = remaining[index]
        if (remove && remaining.size >= 2) {
            remaining.removeAt(index)
            used.add(text)
        }
        return text
    }

    fun restoreUsed() {
        remaining.addAll(0, used)
        used.clear()
    }

    /** Save support: the current order and what is waiting to come back, as indices into [items]. */
    fun stateToString(): String? {
        if (used.isEmpty() && remaining.size == items.size && remaining.indices.all { remaining[it] === items[it] })
            return null
        return remaining.joinToString(",") { items.indexOf(it).toString() } + ";" + used.joinToString(",") { items.indexOf(it).toString() }
    }

    fun loadState(state: String?) {
        remaining.clear()
        used.clear()
        if (state == null) {
            remaining.addAll(items)
            return
        }
        val parts = state.split(';')
        for (i in parts[0].split(',')) if (i.isNotEmpty()) remaining.add(items[i.toInt()])
        for (i in parts.getOrElse(1) { "" }.split(',')) if (i.isNotEmpty()) used.add(items[i.toInt()])
    }

    override fun resolve(rand: Random): String {
        if (remaining.isEmpty())
            return ""
        return pick(rand, true).resolve(rand)
    }
}

/**
 * A picked text. [planet] and [back] are the image list names of the text's own `planet` and `back` attributes
 * (EventGenerator text entry 0x5e2e50); they replace the event's images when this is the event's text.
 */
class EventText(
    val localised: String,
    val id: String? = null,
    val planet: String? = null,
    val back: String? = null
) : IEventText {
    override fun resolve(rand: Random): String = localised
}

class ImageList(val name: String, val images: List<EnvironmentImage>) {
    // This makes sense in the context of Beacon.getEnvironmentImages
    fun getRandom(seed: Int): EnvironmentImage? {
        if (this == NONE)
            return null

        if (images.isEmpty())
            return null

        return images[seed.absoluteValue % images.size]
    }

    companion object {
        // The special image list 'NONE' can be specified to remove an inherited image
        val NONE = ImageList("NONE", emptyList())
    }
}

/**
 * Represents an image specified in an <imageList>, used for a background or planet.
 */
class EnvironmentImage(val path: String, val width: Int? = null, val height: Int? = null) {
    fun getImg(game: InGameState): Image {
        return game.getImg(path)
    }
}

enum class FleetBackground(val xmlName: String) {
    REBEL("rebel"),
    FEDERATION("fed"),
    BOTH("battle")
}
