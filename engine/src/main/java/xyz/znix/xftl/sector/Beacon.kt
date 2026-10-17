package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.VisualRandom
import xyz.znix.xftl.environment.*
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.random.Random

/**
 * Represents a single beacon on the star map.
 */
class Beacon(
    /**
     * The beacon's position on the sector map
     */
    val pos: ConstPoint,

    /**
     * The event at this beacon, as the sector generation created it. Even
     * empty beacons have events, which set stuff like the background and
     * flavour text.
     */
    createdEvent: CreatedEvent,

    /**
     * True if this is the exit beacon to progress to the next sector.
     */
    val isExit: Boolean,

    /**
     * True if this is the location of the rebel base, in the last stand.
     */
    val isBase: Boolean,

    /**
     * The seed used for this beacon to generate the seeds for everything
     * else (environment, store, etc).
     */
    private val masterSeed: Int,
) : ISerialReferencable {

    /** A beacon whose event is only known by its template (saves from before events were created eagerly). */
    constructor(pos: ConstPoint, event: Event, isExit: Boolean, isBase: Boolean, masterSeed: Int) :
            this(pos, CreatedEvent.fromTemplate(event), isExit, isBase, masterSeed)

    /**
     * The event at this beacon. Setting it replaces the created event with
     * the bare template (console and test paths, vanilla imports).
     */
    var createdEvent: CreatedEvent = createdEvent

    var event: Event
        get() = createdEvent.template
        set(value) {
            createdEvent = CreatedEvent.fromTemplate(value)
        }

    /**
     * This beacon's position on the 6x4 grid that all the sectors fit within.
     */
    val gridPos: ConstPoint = pos.divideTruncate(Sector.CELL_SIZE).const

    /**
     * Without long-range scanners, what should this beacon be displayed as?
     *
     * This stores the state of the beacon as the player left it, so they can see if they've previously visited
     * a beacon and if so whether they jumped out of a fight.
     */
    val state: State
        get() = when {
            isOvertaken -> State.OVERTAKEN
            !visited -> State.UNVISITED
            ship != null -> State.VISITED_DANGER
            else -> State.VISITED_CLEAR
        }

    /**
     * StarMap location flag +0x1e: the beacon lies inside a map nebula
     * cloud. The fleet slows down there and quests aren't placed there.
     */
    var inNebula: Boolean = false

    /**
     * A cloud covered a beacon that already had its event (the start, exit
     * or a quest); the original then sets that event's environment to a
     * nebula (0x5f5b40).
     */
    var forcedNebula: Boolean = false

    /**
     * WorldManager::ClearEvent (0x6119f0) wiped the beacon's event: the hazard is gone (a nebula or ion storm
     * is left as a plain nebula), as are the store/reveal/distress/repair flags. The original does it when the
     * player jumps away (ClearLocation 0x614d00, via 0x619fc8) and on every load for visited beacons other
     * than the current one (StarMap::LoadGame 0x5fb9a0), and the flagship's step forces it at the current
     * beacon (0x5f5630). Fleet encounters and the exit beacon keep their event.
     */
    var eventCleared: Boolean = false

    /**
     * ClearEvent (0x6119f0) for a beacon whose LocationEvent is [force]d or not: a fleet event (+0x610) survives
     * an unforced clear, and the exit beacon (+0x614) keeps its hazard. [dropLive] also drops the running
     * environment (the player left); the flagship's step leaves the one already active.
     */
    fun clearEvent(force: Boolean, dropLive: Boolean) {
        if (hasFleetEvent && !force)
            return
        if (isExit)
            return
        eventCleared = true
        if (dropLive)
            actualEnvironment = null
    }

    /** Nebula for map purposes: in a cloud, or a nebula event (older saves). */
    val isMapNebula: Boolean get() = inNebula || environmentType.isNebula

    val environmentType: EnvironmentType
        get() {
            actualEnvironment?.let { return it.type }
            var type = if (forcedNebula) EnvironmentType.NEBULA else event.environment ?: EnvironmentType.NORMAL
            if (eventCleared)
                type = if (type.isNebula) EnvironmentType.NEBULA else EnvironmentType.NORMAL
            return when {
                !isOvertaken -> type

                // If this beacon has been overtaken, clear any special events,
                // though preserving the nebula-ness of this beacon.
                type == EnvironmentType.NEBULA || type == EnvironmentType.ION_STORM -> EnvironmentType.NEBULA
                isExit && sector.browserDifficulty == xyz.znix.xftl.game.Difficulty.EASY -> EnvironmentType.NORMAL
                else -> EnvironmentType.PDS
            }
        }

    /**
     * The sector this beacon resides within.
     *
     * It can only be set once, via [bindSector], and will throw an exception if used before it is set.
     */
    lateinit var sector: Sector
        private set

    /**
     * The beacons connected this beacon on the star map, to which the player can jump from here.
     */
    lateinit var neighbours: List<Beacon>
        private set

    val environmentSeed: Int
    val storeSeed: Int
    val eventSeed: Int

    /**
     * The ship remaining here. Either the player jumped off while fighting it, or it was/became
     * neutral from an event. Currently only the former is supported, when coming back the ship
     * will be gone.
     */
    var ship: Ship? = null
        set(value) {
            field = value

            // Bosses aren't saved at a beacon, since if you jump away and back again they're re-created.
            require(value?.boss == null) { "Cannot save the flagship at a beacon!" }
        }

    /**
     * Has this beacon been previously visited?
     */
    var visited: Boolean = false

    /**
     * Is a store available at this beacon? This isn't the same as event.isStore, as events
     * where a store becomes available as a result of player actions (eg a quest to save
     * a store from the rebels, or saving a merchant that was under attack by pirates) can
     * reveal a store.
     */
    var hasStore: Boolean = event.isStore

    private var internalStore: StoreData? = null

    /**
     * True if the event at this beacon was created by an event.
     *
     * This is used to display the quest label in the map screen,
     * if this beacon is unvisited.
     */
    var hasQuest: Boolean = false

    /** The name AddQuest (0x5f8920) was given for this beacon's quest event, saved to vanilla saves. */
    var questName: String? = null

    /**
     * For all the status effects applied at this beacon, this states what
     * the final power limit is for each system by name.
     *
     * Enemy ship effects are left in place and never cleared, while the player
     * can jump between beacons and these need to be preserved.
     */
    val powerLimitEffects = HashMap<String, Int>()

    /** The `loss` statuses (ShipSystem +0x120) applied at this beacon, by system name; kept apart from the limits. */
    val powerLossEffects = HashMap<String, Int>()

    /**
     * This is a random value between 0-1 that's used to offset
     * the timings of the 'this beacon will be overtaken' flash
     * animation on the beacon map, to ensure all the beacons
     * aren't flashing in sync.
     */
    val overtakeFlashAnimationOffset: Float = VisualRandom.nextFloat()

    var browserFleetThreatened = false

    /**
     * The event is the fleet encounter StarMap::OvertakeLocation (0x5f4ca0) created when the fleet took this
     * beacon; arriving shows it. False for beacons overtaken in older saves, which build one on arrival.
     */
    var hasFleetEvent = false

    var isOvertaken: Boolean = false
        set(value) {
            field = value
            if (value) {
                hasStore = false
                hasQuest = false
            }
        }

    private var actualEnvironment: AbstractEnvironment? = null

    init {
        val rand = Random(masterSeed)
        environmentSeed = rand.nextInt()
        storeSeed = rand.nextInt()
        eventSeed = rand.nextInt()
    }

    /**
     * Set the store contents directly - used by the vanilla save loader,
     * which restores a store's stock from the save.
     */
    fun setStoreData(data: StoreData?) {
        internalStore = data
    }

    /**
     * WorldManager::CreateStore (0x613010): the store is made when an event
     * with a store is applied, drawing from the global stream, and stays
     * with the beacon; a revisit finds the same store.
     */
    fun createStore(game: InGameState) {
        if (!hasStore || internalStore != null)
            return

        internalStore = StoreData().also { it.generateRandomContents(game) }
    }

    /** The store, made now (from the global stream) if the event that carries it never created it. */
    fun getStore(game: InGameState): StoreData? {
        createStore(game)
        return internalStore
    }

    fun bindSector(sector: Sector, neighbours: List<Beacon>) {
        synchronized(this) {
            check(!this::sector.isInitialized) { "Sector already set!" }
            this.sector = sector
            this.neighbours = neighbours
        }
    }

    /**
     * Whether the beacon's hazard is dangerous right now, without creating
     * the environment if it doesn't exist yet.
     */
    val isEnvironmentDangerous: Boolean
        get() = actualEnvironment?.isDangerous ?: environmentType.isDangerous

    fun getEnvironment(game: InGameState): AbstractEnvironment {
        actualEnvironment?.let { return it }

        val env = environmentType.create(game, this)
        actualEnvironment = env
        return env
    }

    /**
     * Remove the current environment associated with the beacon, so it'll be
     * re-created next time it's used.
     *
     * This should be called after changing the beacon's event, so it reflects
     * the new event's environment.
     */
    fun clearEnvironment() {
        actualEnvironment = null
    }

    /**
     * Put back the scene the ship is in after the fleet's step ([Sector.overtake] rebuilds a beacon's scene):
     * StarMap::OvertakeLocation (0x5f4ca0) never touches the SpaceManager scene, so the location being left
     * keeps its ambience and hazard until ClearLocation (0x614d00) ends the jump-out.
     */
    fun restoreEnvironment(environment: AbstractEnvironment) {
        actualEnvironment = environment
    }

    /**
     * FOR USE WITH THE DEBUG CONSOLE ONLY!
     */
    fun debugSetEnvironment(environment: AbstractEnvironment) {
        actualEnvironment = environment
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addObjectId(elem, refs, this)

        SaveUtil.addAttrInt(elem, "x", pos.x)
        SaveUtil.addAttrInt(elem, "y", pos.y)

        SaveUtil.addAttrBool(elem, "visited", visited)
        if (inNebula)
            elem.setAttribute("browserInNebula", "true")
        if (forcedNebula)
            elem.setAttribute("browserForcedNebula", "true")
        if (eventCleared)
            elem.setAttribute("browserEventCleared", "true")
        elem.setAttribute("browserOvertaken", isOvertaken.toString())
        elem.setAttribute("browserFleetThreatened", browserFleetThreatened.toString())
        if (hasFleetEvent)
            elem.setAttribute("browserFleetEvent", "true")
        SaveUtil.addTagBoolIfTrue(elem, "isExit", isExit)
        SaveUtil.addTagBoolIfTrue(elem, "isBase", isBase)
        SaveUtil.addTagBoolIfTrue(elem, "hasStore", hasStore)
        SaveUtil.addTagBoolIfTrue(elem, "hasQuest", hasQuest)
        questName?.let { elem.setAttribute("browserQuestName", it) }

        // Save the event by name - events are immutable and are
        // loaded from the game's XML, so we only need to uniquely
        // identify them, not store all their data. What its creation drew
        // goes in a child element.
        SaveUtil.addAttr(elem, "eventId", event.deserialisationId)
        if (createdEvent.eager) {
            val createdElem = Element("createdEvent")
            createdEvent.saveToXML(createdElem)
            elem.addContent(createdElem)
        }

        // Save the master seed, so we can regenerate all the other seeds
        // without storing them separately.
        SaveUtil.addAttrInt(elem, "seed", masterSeed)

        // This both marks the set environment, and whether the environment
        // object needs to be deserialised.
        if (actualEnvironment != null) {
            SaveUtil.addAttr(elem, "env", actualEnvironment!!.type.name)
            actualEnvironment!!.saveToXML(elem)
        }

        if (internalStore != null) {
            val storeElem = Element("storeData")
            internalStore!!.saveToXML(storeElem)
            elem.addContent(storeElem)
        }

        // Save the power limits
        for ((system, limit) in powerLimitEffects) {
            val powerLimit = Element("powerLimit")
            powerLimit.setAttribute("system", system)
            powerLimit.setAttribute("limit", limit.toString())
            elem.addContent(powerLimit)
        }
        for ((system, loss) in powerLossEffects) {
            val powerLoss = Element("powerLoss")
            powerLoss.setAttribute("system", system)
            powerLoss.setAttribute("loss", loss.toString())
            elem.addContent(powerLoss)
        }

        // If there's a ship at this beacon, save it.
        // This is how the ship the player is fighting is loaded.
        if (ship != null) {
            val shipElem = Element("enemyShip")
            ship!!.saveToXML(shipElem, refs)
            elem.addContent(shipElem)
        }

        // Note we don't save the neighbours - the sector does that using a more
        // compact encoding than what we can easily use.
    }

    companion object Deserialiser {
        fun loadFromXML(elem: Element, refs: RefLoader, game: InGameState): Beacon {
            // To create our beacon, we need to load a few things first.
            // Everything else goes in mutable variables, so we can set them later.
            val eventId = SaveUtil.getAttr(elem, "eventId")
            val seed = SaveUtil.getAttrInt(elem, "seed")
            val event = game.eventManager.getByDeserialisationId(eventId)
            val isExit = SaveUtil.getOptionalTagBool(elem, "isExit") ?: false
            val isBase = SaveUtil.getOptionalTagBool(elem, "isBase") ?: false

            val pos = ConstPoint(
                SaveUtil.getAttrInt(elem, "x"),
                SaveUtil.getAttrInt(elem, "y")
            )

            // This gets us all the information we need to create and start
            // populating our beacon.
            val beacon = Beacon(pos, event, isExit, isBase, seed)
            elem.getChild("createdEvent")?.let { beacon.createdEvent = CreatedEvent.loadFromXML(it, game) }
            SaveUtil.registerObjectId(elem, refs, beacon)

            beacon.visited = SaveUtil.getAttrBool(elem, "visited")
            beacon.inNebula = elem.getAttributeValue("browserInNebula") == "true"
            beacon.forcedNebula = elem.getAttributeValue("browserForcedNebula") == "true"
            beacon.eventCleared = elem.getAttributeValue("browserEventCleared") == "true"
            beacon.hasStore = SaveUtil.getOptionalTagBool(elem, "hasStore") ?: false
            beacon.hasQuest = SaveUtil.getOptionalTagBool(elem, "hasQuest") ?: false
            beacon.questName = elem.getAttributeValue("browserQuestName")
            beacon.isOvertaken = elem.getAttributeValue("browserOvertaken") == "true"
            beacon.browserFleetThreatened = elem.getAttributeValue("browserFleetThreatened") == "true"
            beacon.hasFleetEvent = elem.getAttributeValue("browserFleetEvent") == "true"

            // Load environment-specific data, if we've visited this beacon.
            val envName = elem.getAttributeValue("env")
            val envType = envName?.let { EnvironmentType.valueOf(it) }
            if (envType != null) {
                beacon.actualEnvironment = envType.create(game, beacon)
                beacon.actualEnvironment!!.loadFromXML(elem)
            }

            val storeElem = elem.getChild("storeData")
            if (storeElem != null) {
                beacon.internalStore = StoreData()
                beacon.internalStore!!.loadFromXML(game, storeElem)
            }

            // Deserialise the enemy ship, if present
            val shipElem = elem.getChild("enemyShip")
            beacon.ship = shipElem?.let { game.deserialiseSingleShip(it, refs, null) }

            for (powerLimit in elem.getChildren("powerLimit")) {
                val system = powerLimit.getAttributeValue("system")
                val limit = powerLimit.getAttributeValue("limit").toInt()
                beacon.powerLimitEffects[system] = limit
            }
            for (powerLoss in elem.getChildren("powerLoss")) {
                beacon.powerLossEffects[powerLoss.getAttributeValue("system")] = powerLoss.getAttributeValue("loss").toInt()
            }

            return beacon
        }
    }

    enum class State {
        UNVISITED,
        VISITED_CLEAR,
        VISITED_DANGER,

        /**
         * Overtaken by the rebel fleet.
         */
        OVERTAKEN,
    }

    enum class EnvironmentType(
        val isDangerous: Boolean,
        val create: (InGameState, Beacon) -> AbstractEnvironment
    ) {
        NORMAL(false, ::NormalEnvironment),
        ASTEROID(true, ::AsteroidEnvironment),
        SUN(true, ::SunEnvironment),
        PULSAR(true, ::PulsarEnvironment),
        NEBULA(false, { game, beacon -> NebulaEnvironment(game, beacon, false) }),
        ION_STORM(false, { game, beacon -> NebulaEnvironment(game, beacon, true) }),
        PDS(true, { game, beacon -> xyz.znix.xftl.environment.AsbEnvironment(game, beacon) });

        // TODO how should we represent PDS/ABSes, given they can be targed at the player or enemy (or both?)

        val isNebula: Boolean get() = this == NEBULA || this == ION_STORM
    }
}
