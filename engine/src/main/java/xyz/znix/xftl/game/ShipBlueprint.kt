package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.ai.FriendlyCrewAI
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Rectangle
import java.util.*

class ShipBlueprint @JvmOverloads constructor(
    private val elem: Element, private val df: Datafile,
    /** Hyperspace: the player's ship built from any blueprint (console SWITCH; the original decides by iShipId 0). */
    private val hsForcePlayer: Boolean = false
) : Blueprint(elem) {
    /** The blueprint's XML (Hyperspace Lua ShipBlueprint members read the raw tags). */
    val sourceElement: Element get() = elem

    var layout: String = elem.getAttributeValue("layout")
        private set
    var img: String = elem.getAttributeValue("img")
        private set

    // TODO do this properly, to avoid potentially breaking mods
    val isFlagship: Boolean = name.startsWith("BOSS_")

    // Hyperspace: every hangar layout is a player ship (the original decides by iShipId 0; the engine by blueprint),
    // so MV's ELITE_SHIP_* layouts get the player's start power, shields and weapon list rules.
    val isPlayerShip: Boolean = hsForcePlayer || name.startsWith("PLAYER_SHIP_") ||
        (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.ship.HsHangar.variantOf(name) != null)

    /**
     * The localisation key of this type of ship, shown on the right-hand
     * side of the target panel for enemies.
     */
    var shipClass: GameText? = elem.getGameTextChild("class")

    /**
     * The localisation key of the ship, defining the in-hangar title
     * for player ships (eg 'The Kestrel').
     */
    var shipTitle: GameText? = elem.getGameTextChild("name")

    var unlockHint: GameText? = elem.getGameTextChild("unlock")

    var weaponSlots: Int? = elem.getChildTextTrim("weaponSlots")?.toInt()
    var droneSlots: Int? = elem.getChildTextTrim("droneSlots")?.toInt()

    val isAutoScout = elem.getChild("crewCount")?.getAttributeValue("amount")?.trim() == "0"

    var maxHealth = elem.getChild("health").getAttributeValue("amount").toInt()

    var startingReactorPower: Int = elem.getChild("maxPower").getAttributeValue("amount").toInt()

    var boardingStrategy: FriendlyCrewAI.BoardingStrategy =
        when (val name = elem.getChildTextTrim("boardingAI")) {
            "invasion" -> FriendlyCrewAI.BoardingStrategy.INVASION
            "sabotage" -> FriendlyCrewAI.BoardingStrategy.SABOTAGE
            null -> FriendlyCrewAI.BoardingStrategy.NONE
            else -> error("Invalid boardingAI tag '$name'")
        }

    // These are the paths to the relevant images, to keep this class lightweight.
    // For hullImage, there's multiple possible image paths - try them
    // and use the first one that exists.
    var hullImage: List<String>
        private set
    var floorImage: String?
        private set
    var cloakImage: String?
        private set

    var shieldImage: String
        private set

    // Note that hullOffset doesn't match up with Ship.hullOffset, since it's one
    // has the room offset (times ROOM_SIZE) added to it.
    var hullOffset: ConstPoint
        private set
    var floorOffset: ConstPoint
        private set
    var cloakOffset: ConstPoint
        private set

    var gibs: List<ShipGib>
        private set
    var hardpoints: List<ParsedHardpoint>
        private set

    /** The mount of an artillery past the end of the layout's mounts: empty, at the layout's origin. */
    var emptyHardpoint: ParsedHardpoint
        private set
    var systems: List<ParsedSystem>
        internal set

    var initialWeapons: List<String>
    var initialDrones: List<String>
    var initialAugments: List<String>

    /** ShipInfo::augList is a std::map, so the hangar lists augments by id. */
    var hangarAugments: List<String>

    /**
     * A list of crew specifications, which can each spawn
     * a variable number of crew.
     */
    var initialCrew: List<InitialCrewSpec>

    var initialMissiles: Int
    var initialDroneParts: Int

    /** `<droneList count>` (ShipBlueprint +0x724 in OnInit's drone loop); null when absent. */
    /** ShipBlueprint::originalCrewCount written by a script: the crew a ship built later starts with. */
    var hsCrewCount: Int? = null
    /** ShipBlueprint::maxCrew written by a script. */
    var hsMaxCrew: Int? = null

    /** `<weaponList count>`: a script-written count; null keeps the list as the constructor took it. */
    var weaponListCount: Int? = null

    var droneListCount: Int? = elem.getChild("droneList")?.getAttributeValue("count")?.toIntOrNull()

    // Layout-related stuff
    var rooms: List<ParsedRoom> = ArrayList()
        private set
    var doors: List<ParsedDoor> = ArrayList()
        private set
    lateinit var shieldEllipse: Rectangle
        private set
    lateinit var roomOffset: ConstPoint
        private set

    /**
     * The layout's HORIZONTAL/VERTICAL pixel shift (Ship::horizontal_shift and
     * vertical_shift); ShipBuilder::OnRender adds it to the hangar ship position.
     */
    var layoutShift: ConstPoint = ConstPoint.ZERO
        private set

    /**
     * Hyperspace's Ship::OnInit hook (CustomShips.cpp): every `<thruster>` of the layout's `<thrusters>` nodes is
     * started with Animation::RandomStart (one draw each) unless `sync` is true (Start, no draw). Used only under HS.
     */
    var hsThrusterDraws: Int = 0
        private set

    init {
        val layoutName = elem.getAttributeValue("layout")
        val layoutElem = df.parseXML(df["data/$layoutName.xml"]).rootElement
        run {
            var sync = false
            var count = 0
            for (node in layoutElem.getChildren("thrusters")) {
                node.getAttributeValue("sync")?.let { sync = it.trim().equals("true", ignoreCase = true) }
                count += node.getChildren("thruster").size
            }
            hsThrusterDraws = if (sync) 0 else count
        }

        hullImage = listOf(
            "img/ship/${img}_base.png",
            "img/ships_glow/${img}_base.png"
        )
        hullOffset = Utils.parsePosElem(layoutElem.getChild("img"))

        // Note that the stage-2 and stage-3 boss layouts don't have
        // an offsets tag, but everything else does.
        val offsets: Element? = layoutElem.getChild("offsets")

        val floorImageName = elem.getChildTextTrim("floorImage") ?: img
        floorImage = validatePath(df, "img/ship/${floorImageName}_floor.png")
        floorOffset = offsets?.getChild("floor")?.let { Utils.parsePosElem(it) } ?: ConstPoint.ZERO

        // Load the cloak image - if one is set by name use that, otherwise
        // guess based on the ship name (this is required on the Kestrel, for example).
        val customCloakName = elem.getChildTextTrim("cloakImage")
        cloakImage = validatePath(df, "img/ship/${customCloakName ?: img}_cloak.png")
        cloakOffset = offsets?.getChild("cloak")?.let { Utils.parsePosElem(it) } ?: ConstPoint.ZERO

        val overrideShieldImage = elem.getChildTextTrim("shieldImage")
        shieldImage = when (isPlayerShip) {
            true -> "img/ship/${overrideShieldImage ?: img}_shields1.png"
            false -> "img/ship/enemy_shields.png"
        }

        // Load the room/door layout, as it's required for loading the systems.
        parseLayoutTxt(df.readString(df["data/$layout.txt"]))

        gibs = layoutElem.getChild("explosion").children.map { ShipGib(this, it) }

        // Load the hardpoints
        emptyHardpoint = ParsedHardpoint(hullOffset + roomOffset * Constants.ROOM_SIZE, false, false, -1, null)
        hardpoints = ArrayList()

        for (node in layoutElem.getChild("weaponMounts").children) {
            // In rebel_long.xml the hardpoint direction is missing for some testing stuff.
            // Artillery (used in the boss and federation cruiser) has slide set to 'no'
            val dirName = node.getAttributeValue("slide")?.toUpperCase(Locale.UK) ?: continue

            // To get this into the proper ship space, we have to use the ship's
            // hullOffset, which includes the room offset. This is a bit ugly, but it'll do.
            val pos = Utils.parsePosElem(node) + hullOffset + roomOffset * Constants.ROOM_SIZE

            val dir = if (dirName == "NO") null else dirName.let(Direction::valueOf)
            hardpoints += ParsedHardpoint(
                pos,
                node.getAttributeValue("rotate")!!.toBoolean(),
                node.getAttributeValue("mirror")!!.toBoolean(),
                node.getAttributeValue("gib").toInt(),
                dir
            )
        }

        // Parse the systems
        systems = ArrayList()
        // ShipManager::AddSystem (0x4a8370) reads one systemInfo per system id: a blueprint listing a system several
        // times (the flagship's artillery) adds a room and weapon per entry, but the power and AI maximum are the
        // last entry's, shared by every copy (BOSS_1's 4/4/3/3 artillery all start at 3).
        val lastOfType = elem.getChild("systemList").children.associateBy { it.name }
        for ((index, systemElem) in elem.getChild("systemList").children.withIndex()) {
            val room = rooms[systemElem.getAttributeValue("room").toInt()]

            val slotElem: Element? = systemElem.getChild("slot")
            val slotDirection: Direction? = slotElem?.getChildTextTrim("direction")
                ?.let { Direction.valueOf(it.toUpperCase(Locale.UK)) }
            val slotNumber: Int? = slotElem?.getChildTextTrim("number")?.toInt()

            systems += ParsedSystem(room, slotNumber, slotDirection, index, systemElem, isPlayerShip, lastOfType.getValue(systemElem.name))
        }

        // Load the starting equipment

        // Load all the weapon blueprints
        val weaponsElem: Element? = elem.getChild("weaponList")
        if (weaponsElem != null) {
            val names = weaponsElem.children.map { it.getAttributeValue("name") }
            // A player ship starts with the first `count` weapons (the Rock Type B
            // lists three but the original hangar and run give it two).
            val count = weaponsElem.getAttributeValue("count")?.toIntOrNull()
            initialWeapons = if (isPlayerShip && count != null) names.take(count) else names
        } else {
            initialWeapons = emptyList()
        }
        initialMissiles = weaponsElem?.getAttributeValue("missiles")?.toInt() ?: 0

        // Load all the drone blueprints
        val dronesElem: Element? = elem.getChild("droneList")
        if (dronesElem != null) {
            initialDrones = dronesElem.children.map { it.getAttributeValue("name") }
        } else {
            initialDrones = emptyList()
        }
        initialDroneParts = dronesElem?.getAttributeValue("drones")?.toInt() ?: 0

        // Load any augments
        initialAugments = elem.getChildren("aug").map { it.getAttributeValue("name") }
        hangarAugments = initialAugments.sorted()
        require(xyz.znix.xftl.hs.HS.active || initialAugments.size <= Ship.MAX_AUGMENTS) { "Ship $name has too many augments: $initialAugments" }

        // Load the initial crew
        initialCrew = elem.getChildren("crewCount").map {
            val amount = it.requireAttributeValueInt("amount")
            val max = it.getAttributeValue("max")?.toInt()
            val race = it.getAttributeValue("class") ?: "human"
            InitialCrewSpec(amount, race, max)
        }
    }

    /**
     * Hyperspace: a script wrote the blueprint's file names. A ship built later loads them, so the parsed layout and image paths
     * are rebuilt from the names; everything else a script may have written to this blueprint stays.
     */
    fun hsSetFiles(layout: String? = null, img: String? = null, cloak: String? = null, shield: String? = null, floor: String? = null) {
        val e = elem.clone()
        fun child(tag: String, v: String) { (e.getChild(tag) ?: Element(tag).also { e.addContent(it) }).setText(v) }
        layout?.let { hsFileLayout = it }; img?.let { hsFileImg = it }; cloak?.let { hsFileCloak = it }; shield?.let { hsFileShield = it }; floor?.let { hsFileFloor = it }
        hsFileLayout?.let { e.setAttribute("layout", it) }
        hsFileImg?.let { e.setAttribute("img", it) }
        hsFileCloak?.let { child("cloakImage", it) }
        hsFileShield?.let { child("shieldImage", it) }
        hsFileFloor?.let { child("floorImage", it) }
        val o = ShipBlueprint(e, df, hsForcePlayer)
        this.layout = o.layout; this.img = o.img
        hullImage = o.hullImage; floorImage = o.floorImage; cloakImage = o.cloakImage; shieldImage = o.shieldImage
        hullOffset = o.hullOffset; floorOffset = o.floorOffset; cloakOffset = o.cloakOffset
        gibs = o.gibs; hardpoints = o.hardpoints; emptyHardpoint = o.emptyHardpoint; systems = o.systems; rooms = o.rooms; doors = o.doors
        shieldEllipse = o.shieldEllipse; roomOffset = o.roomOffset; layoutShift = o.layoutShift; hsThrusterDraws = o.hsThrusterDraws
    }
    private var hsFileLayout: String? = null
    private var hsFileImg: String? = null
    private var hsFileCloak: String? = null
    private var hsFileShield: String? = null
    private var hsFileFloor: String? = null

    private fun parseLayoutTxt(layout: String) {
        // Use this trick so we can mutate the arrays here, but nowhere else (without a cast).
        val rooms = this.rooms as ArrayList
        val doors = this.doors as ArrayList

        val l = layout.replace("\r\n", "\n").split('\n')
        var i = 0

        var foundOffsetX = 0
        var foundOffsetY = 0

        var foundVertical = 0
        var foundHorizontal = 0

        fun roomByIdOrNull(id: Int): ParsedRoom? = if (id == -1) null else rooms[id]

        while (i < l.size) {
            val line = l[i++]
            when (line) {
                "X_OFFSET" -> foundOffsetX = l[i++].toInt()
                "Y_OFFSET" -> foundOffsetY = l[i++].toInt()
                "HORIZONTAL" -> foundHorizontal = l[i++].toInt()
                "VERTICAL" -> foundVertical = l[i++].toInt()
                "ELLIPSE" -> {
                    val size = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    val pos = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    shieldEllipse = Rectangle(pos, size)
                }

                "ROOM" -> {
                    val id = l[i++].toInt()
                    check(id == rooms.size)
                    val pos = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    val size = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    rooms += ParsedRoom(id, pos, size)
                    check(rooms[id].id == id)
                }

                "DOOR" -> {
                    val x = l[i++].toInt()
                    val y = l[i++].toInt()
                    val left = roomByIdOrNull(l[i++].toInt())
                    val right = roomByIdOrNull(l[i++].toInt())
                    val vertical = l[i++].toInt() == 1
                    doors += ParsedDoor(ConstPoint(x, y), left, right, vertical)
                }

                "" -> {
                }

                else -> error("Unknown line '$line'")
            }
        }

        roomOffset = ConstPoint(foundOffsetX, foundOffsetY)
        layoutShift = ConstPoint(foundHorizontal, foundVertical)

        require(this::shieldEllipse.isInitialized) { "Shield ellipse not specified!" }
    }

    // Return the given path if valid, else null.
    private fun validatePath(df: Datafile, path: String): String? {
        if (df.getOrNull(path) == null)
            return null
        return path
    }

    // TODO remove this, after sorting out how the weaponList tags are
    //  loaded for both ShipGenerator and for player ships.
    @Deprecated("Load element attributes in ShipBlueprint directly")
    fun loadElem(): Element {
        return elem
    }

    class ParsedRoom(val id: Int, val pos: IPoint, val size: IPoint)
    class ParsedDoor(val pos: ConstPoint, val left: ParsedRoom?, val right: ParsedRoom?, val isVertical: Boolean)

    class ParsedHardpoint(
        val position: IPoint,
        val rotate: Boolean,
        val mirror: Boolean,
        val gib: Int,
        val slide: Direction?
    )

    class InitialCrewSpec(val amount: Int, val race: String, val max: Int?)

    class ParsedSystem(
        val room: ParsedRoom,

        override val slotNumber: Int?,
        override val slotDirection: Direction?,
        override val systemIndex: Int,

        elem: Element,
        useDefaultImage: Boolean,
        powerElem: Element = elem
    ) : ISystemConfiguration {
        // The system name comes from the name of the element declaring it
        override val systemName: String = elem.name

        override var startingPower = powerElem.getAttributeValue("power").toInt()

        // Note that if not specified, the system is included by default. This
        // is commonly found with enemy ships.
        override val availableByDefault = elem.getAttributeValue("start")?.toBoolean() != false

        override var aiMaxPower: Int? = powerElem.getAttributeValue("max")?.toInt()

        override val interiorImage: String?

        override val weapon: String? = elem.getAttributeValue("weapon")

        init {
            var imagePath = elem.getAttributeValue("img")?.let { "img/ship/interior/$it.png" }

            // Use the default image, eg for Engi A's pilot and weapons.
            if (imagePath == null && useDefaultImage && systemName != "clonebay" && systemName != "teleporter") {
                imagePath = "img/ship/interior/room_$systemName.png"
            }

            interiorImage = imagePath
        }
    }
}
