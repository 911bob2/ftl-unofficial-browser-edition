// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.h (GPL-3.0-or-later; this engine is
// AGPL-3.0-or-later, see third_party/notices). Plain data the parser fills and the runtime reads; the Lua
// workstream wraps these as Hyperspace.CustomEventsParser / CustomEvent members.
package xyz.znix.xftl.hs.events

/** HS `TextString`: a text id looked up in the text library, or a literal. */
class HsTextString(@JvmField val data: String = "", @JvmField val isLiteral: Boolean = true) {
    fun text(lookup: (String) -> String): String = if (isLiteral) data else lookup(data)
    val isEmpty: Boolean get() = data.isEmpty()
}

/** HS `ToggleValue<T>`: a value plus whether XML set it. */
class Toggle<T>(@JvmField var value: T) {
    @JvmField var enabled = false
    fun set(v: T) { value = v; enabled = true }
    fun copyFrom(o: Toggle<T>) { value = o.value; enabled = o.enabled }
}

/** `<variable>`, `<metaVariable>`, `<tempVariable>` (VariableModifier). */
class VariableModifier {
    enum class VarType { VAR, METAVAR, TEMP }
    enum class Op { SET, ADD, MUL, DIV, MIN, MAX }

    @JvmField var vType = VarType.VAR
    @JvmField var name = ""
    @JvmField var op = Op.SET
    @JvmField var minVal = 0
    @JvmField var maxVal = 0
    @JvmField var variable = ""
    @JvmField var force = false
}

/** `<req name=..>` definitions (CustomReq). */
class CustomReq {
    @JvmField var name = ""
    @JvmField var type = TYPE_ANY
    @JvmField val blueprints = ArrayList<String>()
    @JvmField val children = ArrayList<CustomReq>()
    @JvmField var lvl = -1
    @JvmField var maxLvl = -1
    @JvmField var mult = 1
    @JvmField var constant = 0

    companion object {
        const val TYPE_ANY = 0
        const val TYPE_ALL = 1
        const val TYPE_SUM = 2
    }
}

class EventLoadListEvent {
    @JvmField var event = ""
    @JvmField var req = ""
    @JvmField var lvl = 1
    @JvmField var maxLvl = Int.MAX_VALUE
    @JvmField var maxGroup = -1
}

class EventLoadList {
    @JvmField val events = ArrayList<EventLoadListEvent>()
    @JvmField var seeded = true
    @JvmField var useFirst = false
    @JvmField var onGenerate = false
    @JvmField var ignoreUnique = false
    @JvmField var defaultEvent = ""
}

class EventAlias(@JvmField var event: String = "", @JvmField var jumpClear: Boolean = false, @JvmField var once: Boolean = false)

class EventQueueEvent(@JvmField var event: String = "", @JvmField var label: String = "", @JvmField var seed: Int = 0)

class JumpEvent(@JvmField var event: String = "", @JvmField var label: String = "", @JvmField var loop: Boolean = false, @JvmField var priority: Int = 0)

class DeathEvent(
    @JvmField var event: String = "", @JvmField var label: String = "", @JvmField var jumpClear: Boolean = false,
    @JvmField var thisFight: Boolean = false, @JvmField var priority: Int = 0
)

class BeaconType {
    @JvmField var eventName = ""
    @JvmField var colorR = 255f
    @JvmField var colorG = 255f
    @JvmField var colorB = 255f
    @JvmField var colorA = 1f
    @JvmField var global = false
    @JvmField var persistent = false
    @JvmField var hideVanillaLabel = false
    @JvmField var beaconText = HsTextString()
    @JvmField var undiscoveredTooltip = HsTextString()
    @JvmField var unvisitedTooltip = HsTextString()
    @JvmField var visitedTooltip = HsTextString()
    @JvmField var equipmentReq = ""
}

class EventGameOver {
    @JvmField var enabled = false
    @JvmField var victory = false
    @JvmField var text = ""
    @JvmField var creditsText = ""
    @JvmField var creditsBackground = ""
    @JvmField var creditsMusic = ""
    @JvmField var sound = ""
    @JvmField var ach = ""
}

class CustomQuest {
    @JvmField val nonNebulaBeacon = Toggle(true)
    @JvmField val nebulaBeacon = Toggle(false)
    @JvmField val createNebula = Toggle(true)
    @JvmField val removeNebula = Toggle(false)
    @JvmField val nebulaEvent = Toggle("")
    @JvmField val currentSector = Toggle(true)
    @JvmField val nextSector = Toggle(true)
    @JvmField val aggressive = Toggle(0)
    @JvmField val sectorEight = Toggle(false)
    @JvmField val lastStand = Toggle(false)

    fun copy(): CustomQuest = CustomQuest().also { it.add(this, true) }

    /** CustomQuest::add: every value the other quest set replaces this one's. */
    fun add(o: CustomQuest, all: Boolean = false) {
        if (all || o.nonNebulaBeacon.enabled) nonNebulaBeacon.copyFrom(o.nonNebulaBeacon)
        if (all || o.nebulaBeacon.enabled) nebulaBeacon.copyFrom(o.nebulaBeacon)
        if (all || o.createNebula.enabled) createNebula.copyFrom(o.createNebula)
        if (all || o.removeNebula.enabled) removeNebula.copyFrom(o.removeNebula)
        if (all || o.nebulaEvent.enabled) nebulaEvent.copyFrom(o.nebulaEvent)
        if (all || o.currentSector.enabled) currentSector.copyFrom(o.currentSector)
        if (all || o.nextSector.enabled) nextSector.copyFrom(o.nextSector)
        if (all || o.aggressive.enabled) aggressive.copyFrom(o.aggressive)
        if (all || o.sectorEight.enabled) sectorEight.copyFrom(o.sectorEight)
        if (all || o.lastStand.enabled) lastStand.copyFrom(o.lastStand)
    }
}

/** `<enemyDamage>`: EventDamage {system, amount, effect}; effect bits 1 fire, 2 breach, 3 both, 4 random, |8 force, |16 no hull. */
class HsEventDamage(@JvmField var system: Int = -1, @JvmField var amount: Int = 0, @JvmField var effect: Int = 0, @JvmField var systemName: String? = null)

class EventFleet {
    @JvmField var fleetDefName = ""
    @JvmField var firing = false
    @JvmField var autoDarkening = false
}

class TriggeredEventModifier {
    @JvmField var name = ""
    @JvmField var minTime = 0f
    @JvmField var maxTime = 0f
    @JvmField var minJumps = 0
    @JvmField var maxJumps = 0
}

class TriggeredEventBoxDefinition {
    @JvmField var boxPosition = "DEFAULT"
    @JvmField var priority = 0
    @JvmField var image = ""
    @JvmField var textType = "TIME_AUTO"
    @JvmField var tooltip = HsTextString()
    @JvmField var warningTime = -999f
    @JvmField var warningJumps = -999
    @JvmField var flash = false

    /** `<image>` colour (r g b are /255 on read, a is as written) and rectangle; `left right top bottom` are the gaps. */
    @JvmField var imageColor = floatArrayOf(1f, 1f, 1f, 1f)
    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var w = 0
    @JvmField var h = 0
    @JvmField var left = 0
    @JvmField var right = 0
    @JvmField var top = 0
    @JvmField var bottom = 0

    /** `<image2>`: the image and colour shown instead of `<image>` while the warning holds. */
    @JvmField var image2 = ""
    @JvmField var imageColor2 = floatArrayOf(1f, 1f, 1f, 1f)

    /** `<icon>`: the foreground image (HS leaves front_x/front_y uninitialised without x/y; 0 here). */
    @JvmField var imageFront = ""
    @JvmField var imageColorFront = floatArrayOf(1f, 1f, 1f, 1f)
    @JvmField var frontX = 0
    @JvmField var frontY = 0

    /** `<text>` and `<text2>` (the warning colour). */
    @JvmField var textColor = floatArrayOf(1f, 1f, 1f, 1f)
    @JvmField var textColor2 = floatArrayOf(1f, 1f, 1f, 1f)
    @JvmField var textX = 0
    @JvmField var textY = 0

    /** Every attribute and child as read (diagnostics). */
    @JvmField val raw = LinkedHashMap<String, String>()

    fun copy(): TriggeredEventBoxDefinition = TriggeredEventBoxDefinition().also {
        it.boxPosition = boxPosition; it.priority = priority; it.image = image; it.textType = textType
        it.tooltip = tooltip; it.warningTime = warningTime; it.warningJumps = warningJumps; it.flash = flash
        it.imageColor = imageColor.copyOf(); it.x = x; it.y = y; it.w = w; it.h = h
        it.left = left; it.right = right; it.top = top; it.bottom = bottom
        it.image2 = image2; it.imageColor2 = imageColor2.copyOf()
        it.imageFront = imageFront; it.imageColorFront = imageColorFront.copyOf(); it.frontX = frontX; it.frontY = frontY
        it.textColor = textColor.copyOf(); it.textColor2 = textColor2.copyOf(); it.textX = textX; it.textY = textY
        it.raw.putAll(raw)
    }
}

class TriggeredEventWarningDefinition {
    @JvmField var image = ""
    @JvmField var text = HsTextString()
    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var time = 5.0f
    @JvmField var centerText = true
    @JvmField var flash = false
    @JvmField var sound = ""
    @JvmField var useWarningLine = false

    /** `r g b` as written (not /255: HS default is 253, 84, 70, alpha 1). */
    @JvmField var textColor = floatArrayOf(253f, 84f, 70f, 1f)

    fun copy(): TriggeredEventWarningDefinition = TriggeredEventWarningDefinition().also {
        it.image = image; it.text = text; it.x = x; it.y = y; it.time = time; it.centerText = centerText
        it.flash = flash; it.sound = sound; it.useWarningLine = useWarningLine; it.textColor = textColor.copyOf()
    }
}

/** TriggeredEventDefinition; [idx] is its place in [HsEventParser.triggeredDefs] (saved by index, as HS does). */
class TriggeredEventDefinition {
    @JvmField var idx = -1
    @JvmField var box: TriggeredEventBoxDefinition? = null
    @JvmField var loadBox: org.jdom2.Element? = null
    @JvmField val timerSounds = ArrayList<Pair<Float, String>>()
    @JvmField var loadTimerSounds = ""
    @JvmField var warning: TriggeredEventWarningDefinition? = null
    @JvmField var loadWarning: org.jdom2.Element? = null

    @JvmField var name = ""
    @JvmField var event = ""
    @JvmField var seeded = true
    @JvmField var clearOnJump = false
    @JvmField var thisFight = false
    @JvmField var jumpType = 0
    @JvmField var minLoops = 1
    @JvmField var maxLoops = 1
    @JvmField var triggerMinTime = -1f
    @JvmField var triggerMaxTime = -1f
    @JvmField var triggerMinJumps = -1
    @JvmField var triggerMaxJumps = -1
    @JvmField var minPlayerHull = -1
    @JvmField var maxPlayerHull = -1
    @JvmField var minPlayerDamage = -1
    @JvmField var maxPlayerDamage = -1
    @JvmField var minEnemyHull = -1
    @JvmField var maxEnemyHull = -1
    @JvmField var minEnemyDamage = -1
    @JvmField var maxEnemyDamage = -1
    @JvmField var enemyHullScaling = 0f
    @JvmField var enemyDamageScaling = 0f
    @JvmField var minPlayerCrew = -1
    @JvmField var maxPlayerCrew = -1
    @JvmField var minPlayerDeaths = -1
    @JvmField var maxPlayerDeaths = -1
    @JvmField var minEnemyCrew = -1
    @JvmField var maxEnemyCrew = -1
    @JvmField var minEnemyDeaths = -1
    @JvmField var maxEnemyDeaths = -1
    @JvmField var playerCountRepairs = true
    @JvmField var playerCountNewCrew = true
    @JvmField var enemyCountRepairs = true
    @JvmField var enemyCountNewCrew = true
    @JvmField var playerCrewCountClonebay = true
    @JvmField var enemyCrewCountClonebay = true
    @JvmField var playerDeathsCountClonebay = true
    @JvmField var enemyDeathsCountClonebay = true
    @JvmField var req = ""
    @JvmField var reqMinLvlFirst = 1
    @JvmField var reqMinLvlSecond = 1
    @JvmField var reqMaxLvlFirst = Int.MAX_VALUE
    @JvmField var reqMaxLvlSecond = Int.MAX_VALUE
    @JvmField var inverseReq = false
}

/** CustomEvent: every HS tag an event can carry. */
class CustomEvent(@JvmField var eventName: String = "") {
    @JvmField var unlockShip = ""
    @JvmField var unlockShipSilent = false
    @JvmField var unlockShipReq = ""
    @JvmField var beacon: BeaconType? = null
    @JvmField var loadBeacon = ""
    @JvmField var checkCargo = false
    @JvmField var recursive = true
    @JvmField var preventQuest = false
    @JvmField var noQuestText = false
    @JvmField val triggeredEvents = ArrayList<Int>()
    @JvmField val clearTriggeredEvents = ArrayList<String>()
    @JvmField val triggeredEventModifiers = ArrayList<TriggeredEventModifier>()
    @JvmField var preventFleet = false
    @JvmField var preventBossFleet = 0
    @JvmField var runFromFleet = 0
    @JvmField var removeHazards = false
    @JvmField var removeNebula = false
    @JvmField var secretSectorWarp = ""
    @JvmField var recallBoarders = false
    @JvmField var recallBoardersShip = 0
    @JvmField var recallBoardersForce = false
    @JvmField var eventLoad = ""
    @JvmField var eventLoadSeeded = true
    @JvmField var eventLoadIgnoreUnique = false
    @JvmField var eventLoadList: EventLoadList? = null
    @JvmField var eventRevisit = ""
    @JvmField var eventRevisitSeeded = true
    @JvmField var eventRevisitIgnoreUnique = false
    @JvmField val eventAlias = ArrayList<Pair<String, EventAlias>>()
    @JvmField val queueEvents = ArrayList<EventQueueEvent>()
    @JvmField val clearQueueEvents = ArrayList<String>()
    @JvmField var restartEvent = false
    @JvmField var renameBeacon = ""
    @JvmField var gameOver = EventGameOver()
    @JvmField var achievement = ""
    @JvmField var achievementSilent = false
    @JvmField var disableScrapScore = false
    @JvmField var disableScrapAugments = false
    @JvmField var removeStore = false
    @JvmField var customStore = ""
    @JvmField val jumpEvents = ArrayList<JumpEvent>()
    @JvmField val clearJumpEvents = ArrayList<String>()
    @JvmField val deathEvents = ArrayList<DeathEvent>()
    @JvmField val clearDeathEvents = ArrayList<String>()
    @JvmField var replaceSectorTarget = ""
    @JvmField var replaceSectorList = ""
    @JvmField var resetFtl = false
    @JvmField var instantEscape = false
    @JvmField var instantEscapeRemoveShipBox = false
    @JvmField var escape = false
    @JvmField var surrender = false
    @JvmField var loadEscape = false
    @JvmField var loadSurrender = false
    @JvmField var loadEscapeForced = false
    @JvmField var loadSurrenderForced = false
    @JvmField var disableEscape = false
    @JvmField var disableSurrender = false
    @JvmField var disableEscapeForced = false
    @JvmField var disableSurrenderForced = false
    @JvmField var goToFlagship = false
    @JvmField var goToFlagshipBase = false
    @JvmField var goToFlagshipFleet = false
    @JvmField var noASBPlanet = false
    @JvmField var repairAllSystems = false
    @JvmField var killEnemyBoarders = false
    @JvmField val leftFleet = EventFleet()
    @JvmField val rightFleet = EventFleet()
    @JvmField var clearCustomFleet = false
    @JvmField val backgroundObjects = ArrayList<org.jdom2.Element>()
    @JvmField val clearBackgroundObjects = ArrayList<String>()
    @JvmField val transformBackgroundObjects = ArrayList<Pair<String, String>>()
    @JvmField val eventButtons = ArrayList<org.jdom2.Element>()
    @JvmField val clearEventButtons = ArrayList<String>()
    @JvmField val hiddenAugs = ArrayList<String>()
    @JvmField val removeItems = ArrayList<String>()
    /** (player ship?, system name). */
    @JvmField val removeSystems = ArrayList<Pair<Boolean, String>>()
    @JvmField val variables = ArrayList<VariableModifier>()
    @JvmField var playSound = ""
    @JvmField var playMusic = ""
    @JvmField var resetMusic = false
    @JvmField var changeBackground = ""
    @JvmField val enemyDamage = ArrayList<HsEventDamage>()
    @JvmField var transformRaceFrom = ""
    @JvmField var transformRaceTo = ""
    @JvmField var allowNoSlot = false
    @JvmField var blockNoSlot = false
    @JvmField var choiceRequiresCrew = false
    @JvmField var ignoreEssentialMin = Float.NEGATIVE_INFINITY
    @JvmField var ignoreEssentialMax = Float.POSITIVE_INFINITY
    @JvmField var superDrones = -1
    @JvmField var superDronesName = ""
    @JvmField var clearSuperDrones = -1
    @JvmField var superBarrage = -1
    @JvmField var superBarrageName = ""
    @JvmField var powerSuperShields = -1
    @JvmField var powerSuperShieldsSet = -1
    @JvmField var powerSuperShieldsAdd = 0

    /** C++ copy constructor (recursive=false events copy the recursive definition of the same name). */
    fun copy(): CustomEvent {
        val c = CustomEvent(eventName)
        c.unlockShip = unlockShip; c.unlockShipSilent = unlockShipSilent; c.unlockShipReq = unlockShipReq
        c.beacon = beacon; c.loadBeacon = loadBeacon; c.checkCargo = checkCargo; c.recursive = recursive
        c.preventQuest = preventQuest; c.noQuestText = noQuestText
        c.triggeredEvents += triggeredEvents; c.clearTriggeredEvents += clearTriggeredEvents
        c.triggeredEventModifiers += triggeredEventModifiers
        c.preventFleet = preventFleet; c.preventBossFleet = preventBossFleet; c.runFromFleet = runFromFleet
        c.removeHazards = removeHazards; c.removeNebula = removeNebula; c.secretSectorWarp = secretSectorWarp
        c.recallBoarders = recallBoarders; c.recallBoardersShip = recallBoardersShip; c.recallBoardersForce = recallBoardersForce
        c.eventLoad = eventLoad; c.eventLoadSeeded = eventLoadSeeded; c.eventLoadIgnoreUnique = eventLoadIgnoreUnique
        c.eventLoadList = eventLoadList; c.eventRevisit = eventRevisit; c.eventRevisitSeeded = eventRevisitSeeded
        c.eventRevisitIgnoreUnique = eventRevisitIgnoreUnique; c.eventAlias += eventAlias; c.queueEvents += queueEvents
        c.clearQueueEvents += clearQueueEvents; c.restartEvent = restartEvent; c.renameBeacon = renameBeacon
        c.gameOver = gameOver; c.achievement = achievement; c.achievementSilent = achievementSilent
        c.disableScrapScore = disableScrapScore; c.disableScrapAugments = disableScrapAugments; c.removeStore = removeStore
        c.customStore = customStore; c.jumpEvents += jumpEvents; c.clearJumpEvents += clearJumpEvents
        c.deathEvents += deathEvents; c.clearDeathEvents += clearDeathEvents; c.replaceSectorTarget = replaceSectorTarget
        c.replaceSectorList = replaceSectorList; c.resetFtl = resetFtl; c.instantEscape = instantEscape
        c.instantEscapeRemoveShipBox = instantEscapeRemoveShipBox; c.escape = escape; c.surrender = surrender
        c.loadEscape = loadEscape; c.loadSurrender = loadSurrender; c.loadEscapeForced = loadEscapeForced
        c.loadSurrenderForced = loadSurrenderForced; c.disableEscape = disableEscape; c.disableSurrender = disableSurrender
        c.disableEscapeForced = disableEscapeForced; c.disableSurrenderForced = disableSurrenderForced
        c.goToFlagship = goToFlagship; c.goToFlagshipBase = goToFlagshipBase; c.goToFlagshipFleet = goToFlagshipFleet
        c.noASBPlanet = noASBPlanet; c.repairAllSystems = repairAllSystems; c.killEnemyBoarders = killEnemyBoarders
        for ((dst, src) in listOf(c.leftFleet to leftFleet, c.rightFleet to rightFleet)) {
            dst.fleetDefName = src.fleetDefName; dst.firing = src.firing; dst.autoDarkening = src.autoDarkening
        }
        c.clearCustomFleet = clearCustomFleet; c.backgroundObjects += backgroundObjects
        c.clearBackgroundObjects += clearBackgroundObjects; c.transformBackgroundObjects += transformBackgroundObjects
        c.eventButtons += eventButtons; c.clearEventButtons += clearEventButtons; c.hiddenAugs += hiddenAugs
        c.removeItems += removeItems; c.removeSystems += removeSystems; c.variables += variables
        c.playSound = playSound; c.playMusic = playMusic; c.resetMusic = resetMusic; c.changeBackground = changeBackground
        c.enemyDamage += enemyDamage; c.transformRaceFrom = transformRaceFrom; c.transformRaceTo = transformRaceTo
        c.allowNoSlot = allowNoSlot; c.blockNoSlot = blockNoSlot; c.choiceRequiresCrew = choiceRequiresCrew
        c.ignoreEssentialMin = ignoreEssentialMin; c.ignoreEssentialMax = ignoreEssentialMax
        c.superDrones = superDrones; c.superDronesName = superDronesName; c.clearSuperDrones = clearSuperDrones
        c.superBarrage = superBarrage; c.superBarrageName = superBarrageName; c.powerSuperShields = powerSuperShields
        c.powerSuperShieldsSet = powerSuperShieldsSet; c.powerSuperShieldsAdd = powerSuperShieldsAdd
        return c
    }
}

/** CustomShipEvent: HS tags on a `<ship>` event template. */
class CustomShipEvent(@JvmField var eventName: String = "") {
    @JvmField val triggeredEvents = ArrayList<Int>()
    @JvmField val clearTriggeredEvents = ArrayList<String>()
    @JvmField val triggeredEventModifiers = ArrayList<TriggeredEventModifier>()
    @JvmField val jumpEvents = ArrayList<JumpEvent>()
    @JvmField val clearJumpEvents = ArrayList<String>()
    @JvmField val deathEvents = ArrayList<DeathEvent>()
    @JvmField val clearDeathEvents = ArrayList<String>()
    @JvmField var invincible = false
    @JvmField var deadCrewAuto = false
    @JvmField var finalBoss = EventGameOver()
    @JvmField var bossMusicDelay = -1
}

class SectorExit(@JvmField var event: String = "", @JvmField var rebelEvent: String = "", @JvmField var nebulaEvent: String = "")
class SectorFleet(@JvmField var event: String = "", @JvmField var nebulaEvent: String = "")

/** `<priorityEvent name min max priority req lvl max_lvl>` (the count pair is a RandomAmount min/max, chanceNone 0). */
class PriorityEvent {
    @JvmField var name = ""
    @JvmField var min = 0
    @JvmField var max = 0
    @JvmField var priority = 0
    @JvmField var req = ""
    @JvmField var lvl = 1
    @JvmField var maxLvl = Int.MAX_VALUE
}

class SectorQuest {
    @JvmField var event = ""
    @JvmField var req = ""
    @JvmField var lvl = 1
    @JvmField var maxLvl = Int.MAX_VALUE
}

class CustomSector(@JvmField var sectorName: String = "") {
    @JvmField var exitBeacons = SectorExit()
    @JvmField var fleetBeacons = SectorFleet()
    @JvmField var removeFirstBeaconNebula = false
    @JvmField var priorityNebulaFix = false
    @JvmField var noExit = false
    @JvmField val nebulaSector = Toggle(false)
    @JvmField val bossSector = Toggle(false)
    @JvmField var maxSector = -1
    @JvmField val priorityEventCounts = ArrayList<PriorityEvent>()
    @JvmField val sectorQuests = ArrayList<SectorQuest>()

    fun copy(): CustomSector {
        val c = CustomSector(sectorName)
        c.exitBeacons = SectorExit(exitBeacons.event, exitBeacons.rebelEvent, exitBeacons.nebulaEvent)
        c.fleetBeacons = SectorFleet(fleetBeacons.event, fleetBeacons.nebulaEvent)
        c.removeFirstBeaconNebula = removeFirstBeaconNebula; c.priorityNebulaFix = priorityNebulaFix; c.noExit = noExit
        c.nebulaSector.copyFrom(nebulaSector); c.bossSector.copyFrom(bossSector); c.maxSector = maxSector
        c.priorityEventCounts += priorityEventCounts; c.sectorQuests += sectorQuests
        return c
    }
}

class BossShipDefinition(@JvmField var shipId: String = "", @JvmField var yOffset: Int = 120)
