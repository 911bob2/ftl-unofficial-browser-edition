package xyz.znix.xftl.hs.ship

import org.jdom2.Element

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomShipSelect.h (CustomShipDefinition, RoomDefinition,
// CrewPlacementDefinition, ShipButtonDefinition, CustomUnlockArrow) and src/game/Ship/Room_Extend.h (RoomAnimDef).
// This engine is AGPL-3.0-or-later.

/** `RoomAnimDef`: one `<roomAnim>` of a `<rooms><room id>` entry (drawn by the render workstream). */
class RoomAnimDef(
    @JvmField val renderLayer: Int,
    /** 0 DEFAULT, 1 STRETCH, 2 CENTER, 3 RANDOM_POSITION. */
    @JvmField val animType: Int,
    @JvmField val animBorder: Int,
    @JvmField val animName: String,
    @JvmField val tileAnim: String,
    @JvmField val wallAnim: String,
)

/** `RoomDefinition`: per-room data of a custom ship (`<rooms><room id="n">`). */
class RoomDefinition(@JvmField var roomId: Int) {
    @JvmField val roomAnims = ArrayList<RoomAnimDef>()
    @JvmField var sensorBlind = false
    @JvmField var hullDamageResistChance = 0f
    @JvmField var sysDamageResistChance = 0f
    @JvmField var ionDamageResistChance = 0f
}

/** `CrewPlacementDefinition`: one child of `<crew>` (the element name is the species or a blueprint list). */
class CrewPlacement(
    @JvmField var species: String,
    /** HS leaves the field uninitialised when there is no `room` attribute; the engine uses 0. */
    @JvmField var roomId: Int,
    @JvmField var name: String,
    @JvmField var isList: Boolean,
) {
    fun luaCopy() = CrewPlacement(species, roomId, name, isList)
}

/**
 * `CustomShipDefinition`: what a ship blueprint (`ParseVanillaShipNode`) and `<customShip>` in hyperspace.xml
 * (`ParseShipsNode`) set beyond vanilla. Field defaults are HS's. [copy] is the C++ copy constructor (the room
 * definition objects and backup lists are shared, as HS shares the pointers).
 */
class CustomShipDefinition {
    @JvmField var name = ""
    /** std::map: iteration in name order. */
    @JvmField var hiddenAugs = java.util.TreeMap<String, Int>()
    @JvmField var crewList = ArrayList<CrewPlacement>()
    @JvmField var noJump = false
    @JvmField var noFuelStalemate = false
    @JvmField var artilleryGibMountFix = false
    @JvmField var hideHullDuringExplosion = false
    @JvmField var hpCap = 20
    @JvmField var startingFuel = -1
    @JvmField var startingScrap = -1
    @JvmField var augSlots = 3
    @JvmField var cargoSlots = 4
    @JvmField var roomDefs = HashMap<Int, RoomDefinition>()
    /** `roomStationBackups`: partition room id -> list of (backup room, slots). */
    @JvmField var roomStationBackups = HashMap<Int, List<Pair<Int, List<Int>>>>()
    @JvmField var shipIcons = ArrayList<String>()
    /** `ToggleValue<bool> forceAutomated`: null = not set. */
    @JvmField var forceAutomated: Boolean? = null
    @JvmField var crewLimit = 8
    @JvmField var systemLimit = 8
    @JvmField var subsystemLimit = 4
    @JvmField var reactorPrices = arrayListOf(15, 30, -1, -1, -1, -1)
    @JvmField var reactorPriceIncrement = 5
    @JvmField var maxReactorLevel = 25
    @JvmField var shipGenerator = ""
    @JvmField var artilleryRoomImages = ArrayList<String>()
    @JvmField var hasExclusivityOverride = false
    /** `exclusivityOverride`: the `<exclusivityGroup>` system name lists, in order (systems workstream applies them). */
    @JvmField var exclusivityGroups = ArrayList<List<String>>()

    fun copy(): CustomShipDefinition {
        val c = CustomShipDefinition()
        c.name = name
        c.hiddenAugs = java.util.TreeMap(hiddenAugs)
        c.crewList = ArrayList(crewList)
        c.noJump = noJump; c.noFuelStalemate = noFuelStalemate
        c.artilleryGibMountFix = artilleryGibMountFix; c.hideHullDuringExplosion = hideHullDuringExplosion
        c.hpCap = hpCap; c.startingFuel = startingFuel; c.startingScrap = startingScrap
        c.augSlots = augSlots; c.cargoSlots = cargoSlots
        c.roomDefs = HashMap(roomDefs); c.roomStationBackups = HashMap(roomStationBackups)
        c.shipIcons = ArrayList(shipIcons); c.forceAutomated = forceAutomated
        c.crewLimit = crewLimit; c.systemLimit = systemLimit; c.subsystemLimit = subsystemLimit
        c.reactorPrices = ArrayList(reactorPrices); c.reactorPriceIncrement = reactorPriceIncrement
        c.maxReactorLevel = maxReactorLevel; c.shipGenerator = shipGenerator
        c.artilleryRoomImages = ArrayList(artilleryRoomImages)
        c.hasExclusivityOverride = hasExclusivityOverride; c.exclusivityGroups = ArrayList(exclusivityGroups)
        return c
    }
}

/** `CustomUnlockArrow` (data only; images are resolved by the hangar). */
class UnlockArrow(
    @JvmField val ship: String,
    @JvmField val targetShip: String,
    /** -1 all, 0 a, 1 b, 2 c. */
    @JvmField val variant: Int,
    @JvmField val tooltip: String,
    @JvmField val tooltipIsLiteral: Boolean,
    @JvmField val x: Int,
    @JvmField val y: Int,
    @JvmField val direction: String,
    @JvmField val image: String,
    @JvmField val lockedImage: String,
    @JvmField val unlockedImage: String,
    @JvmField val rotation: Float,
    @JvmField val mirror: Boolean,
)

/** `ShipButtonDefinition`: one `<ship name>` of `<ships>` (a hangar entry with up to three layouts). */
class ShipButtonDefinition(@JvmField val name: String) {
    /** 0 absent, 1 present, 2 needs the DLC ("dlc"). */
    @JvmField var typeA = 1
    @JvmField var typeB = 0
    @JvmField var typeC = 0
    @JvmField var secretCruiser = false
    @JvmField var noAppend = false
    @JvmField var splitUnlockQuestAchievement = false
    @JvmField var splitVictoryAchievement = false
    @JvmField var showShipAchievements = true
    @JvmField val unlockArrows = ArrayList<UnlockArrow>()
    /** `<achievement>` children per variant (CustomAchievementTracker::ParseShipAchievement owns their meaning). */
    @JvmField val shipAchievements = arrayOf(ArrayList<Element>(), ArrayList<Element>(), ArrayList<Element>())

    fun type(variant: Int): Int = when (variant) { 0 -> typeA; 1 -> typeB; 2 -> typeC; else -> 0 }

    fun variantExists(variant: Int): Boolean = type(variant) != 0

    fun variantNeedsDlc(variant: Int): Boolean = type(variant) == 2
}
