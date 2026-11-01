// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/StatBoost.h, src/game/Utilities/TimerHelper.cpp
package xyz.znix.xftl.hs.stat

import org.jdom2.Element
import xyz.znix.xftl.hs.crew.ActivatedPowerDefinition
import xyz.znix.xftl.hs.crew.CrewExtraCondition
import xyz.znix.xftl.hs.crew.ExplosionDefinition
import xyz.znix.xftl.hs.crew.PowerResourceDefinition

/**
 * FTL's TimerHelper as the stat code uses it: `Start(goal)` runs it from 0, [update] adds the frame time while it
 * runs, and [done] is true once, on the frame it passes the goal (TimerHelper::Done 0x5cb200: `currTime > currGoal`,
 * then a non-looping timer stops).
 */
class HsTimer {
    @JvmField var currTime = 0f
    @JvmField var currGoal = 0f
    @JvmField var running = false
    /** TimerHelper::loop: nothing here sets it; a script may. */
    @JvmField var loop = false

    fun start(goal: Float) { running = true; currTime = 0f; currGoal = goal }
    fun update(dt: Float) { if (running) currTime += dt }
    fun done(): Boolean {
        if (!running) return false
        if (currGoal <= currTime && currTime != currGoal) { running = false; return true }
        return false
    }
    fun copyFrom(o: HsTimer) { currTime = o.currTime; currGoal = o.currGoal; running = o.running }
}

/** StatBoostDefinition. Field defaults follow HS's value-initialised `new StatBoostDefinition()` (enums = first). */
class StatBoostDefinition {
    enum class BoostType { MULT, FLAT, SET, FLIP, SET_VALUE, MIN, MAX, REPLACE_GROUP, REPLACE_POWER }
    enum class BoostSource { CREW, AUGMENT }
    enum class ShipTarget {
        PLAYER_SHIP, ENEMY_SHIP, CURRENT_ALL, CURRENT_ROOM, OTHER_ALL, ORIGINAL_SHIP, ORIGINAL_OTHER_SHIP, CREW_TARGET,
        TARGETS_ME, ALL
    }
    enum class SystemRoomTarget { ALL, NONE }
    enum class CrewTarget { ALLIES, ENEMIES, SELF, ALL, CURRENT_ALLIES, CURRENT_ENEMIES, ORIGINAL_ALLIES, ORIGINAL_ENEMIES }
    enum class DroneTarget { DRONES, CREW, ALL }

    @JvmField var stat: CrewStat = CrewStat.MAX_HEALTH
    @JvmField var amount = 0f
    @JvmField var value = false
    @JvmField var stringValue = ""
    @JvmField var isBool = false
    @JvmField var priority = -1
    @JvmField var duration = -1f
    @JvmField var jumpClear = false
    @JvmField var cloneClear = true

    @JvmField var boostAnim = ""
    /** The raw `<roomAnim>` node (RoomAnimDef belongs to the render/ships workstreams). */
    @JvmField var roomAnim: Element? = null

    @JvmField var affectsSelf = false

    @JvmField var whiteList = ArrayList<String>()
    @JvmField var blackList = ArrayList<String>()
    @JvmField var systemRoomReqs = ArrayList<String>()
    @JvmField var systemList = ArrayList<String>()

    @JvmField var providedStatBoosts = ArrayList<StatBoostDefinition>()

    @JvmField var powerChange: ActivatedPowerDefinition? = null
    @JvmField var powerWhitelist = LinkedHashSet<ActivatedPowerDefinition?>()
    @JvmField var powerBlacklist = LinkedHashSet<ActivatedPowerDefinition?>()
    @JvmField var powerResourceWhitelist = LinkedHashSet<PowerResourceDefinition?>()
    @JvmField var powerResourceBlacklist = LinkedHashSet<PowerResourceDefinition?>()
    @JvmField var powerGroupWhitelist = LinkedHashSet<Int>()
    @JvmField var powerGroupBlacklist = LinkedHashSet<Int>()
    @JvmField var hasPowerList = false
    @JvmField var deathEffectChange: ExplosionDefinition? = null

    @JvmField var powerScaling = ArrayList<Float>()
    @JvmField var powerScalingNoSys = 0f
    @JvmField var powerScalingHackedSys = 0f
    @JvmField var systemPowerScaling = ArrayList<Int>()
    @JvmField var powerScalingShipTarget = ShipTarget.ORIGINAL_SHIP

    @JvmField var extraConditions = ArrayList<Pair<CrewExtraCondition, Boolean>>()
    @JvmField var extraOrConditions = ArrayList<Pair<CrewExtraCondition, Boolean>>()
    @JvmField var extraConditionsReq = false
    @JvmField var systemRoomTarget = SystemRoomTarget.ALL
    @JvmField var systemRoomReq = false
    @JvmField var isRoomBased = false
    @JvmField var boostSource = BoostSource.CREW
    @JvmField var boostType = BoostType.MULT
    @JvmField var shipTarget = ShipTarget.PLAYER_SHIP
    @JvmField var crewTarget = CrewTarget.ALLIES
    @JvmField var droneTarget = DroneTarget.ALL
    @JvmField var functionalTarget = false
    @JvmField var healthReq = floatArrayOf(-1f, -1f)
    @JvmField var healthFractionReq = floatArrayOf(-1f, -1f)
    @JvmField var oxygenReq = floatArrayOf(-1f, -1f)
    @JvmField var fireCount = intArrayOf(-1, -1)

    @JvmField var dangerRating = -1f

    @JvmField var realBoostId = -1
    @JvmField var stackId = 0
    @JvmField var maxStacks = Int.MAX_VALUE

    /** HS `new StatBoostDefinition(*saved)`: a member-wise copy (containers copied, pointers shared). */
    fun copy(): StatBoostDefinition {
        val c = StatBoostDefinition()
        c.stat = stat; c.amount = amount; c.value = value; c.stringValue = stringValue; c.isBool = isBool
        c.priority = priority; c.duration = duration; c.jumpClear = jumpClear; c.cloneClear = cloneClear
        c.boostAnim = boostAnim; c.roomAnim = roomAnim; c.affectsSelf = affectsSelf
        c.whiteList = ArrayList(whiteList); c.blackList = ArrayList(blackList); c.systemRoomReqs = ArrayList(systemRoomReqs)
        c.systemList = ArrayList(systemList); c.providedStatBoosts = ArrayList(providedStatBoosts)
        c.powerChange = powerChange; c.powerWhitelist = LinkedHashSet(powerWhitelist); c.powerBlacklist = LinkedHashSet(powerBlacklist)
        c.powerResourceWhitelist = LinkedHashSet(powerResourceWhitelist); c.powerResourceBlacklist = LinkedHashSet(powerResourceBlacklist)
        c.powerGroupWhitelist = LinkedHashSet(powerGroupWhitelist); c.powerGroupBlacklist = LinkedHashSet(powerGroupBlacklist)
        c.hasPowerList = hasPowerList; c.deathEffectChange = deathEffectChange
        c.powerScaling = ArrayList(powerScaling); c.powerScalingNoSys = powerScalingNoSys
        c.powerScalingHackedSys = powerScalingHackedSys; c.systemPowerScaling = ArrayList(systemPowerScaling)
        c.powerScalingShipTarget = powerScalingShipTarget; c.extraConditions = ArrayList(extraConditions)
        c.extraOrConditions = ArrayList(extraOrConditions); c.extraConditionsReq = extraConditionsReq
        c.systemRoomTarget = systemRoomTarget; c.systemRoomReq = systemRoomReq; c.isRoomBased = isRoomBased
        c.boostSource = boostSource; c.boostType = boostType; c.shipTarget = shipTarget; c.crewTarget = crewTarget
        c.droneTarget = droneTarget; c.functionalTarget = functionalTarget; c.healthReq = healthReq.copyOf()
        c.healthFractionReq = healthFractionReq.copyOf(); c.oxygenReq = oxygenReq.copyOf(); c.fireCount = fireCount.copyOf()
        c.dangerRating = dangerRating; c.realBoostId = realBoostId; c.stackId = stackId; c.maxStacks = maxStacks
        return c
    }

    fun isTargetPower(power: ActivatedPowerDefinition): Boolean {
        if (!hasPowerList) return true
        if (powerWhitelist.isNotEmpty() || powerGroupWhitelist.isNotEmpty()) {
            if (power !in powerWhitelist && power.replaceGroupIndex !in powerGroupWhitelist) return false
        }
        if (powerBlacklist.isNotEmpty() && power in powerBlacklist) return false
        if (powerGroupBlacklist.isNotEmpty() && power.replaceGroupIndex in powerGroupBlacklist) return false
        return true
    }

    fun isTargetPower(power: PowerResourceDefinition): Boolean {
        if (!hasPowerList) return true
        if (powerResourceWhitelist.isNotEmpty() || powerGroupWhitelist.isNotEmpty()) {
            if (power !in powerResourceWhitelist && power.groupIndex !in powerGroupWhitelist) return false
        }
        if (powerResourceBlacklist.isNotEmpty() && power in powerResourceBlacklist) return false
        if (powerGroupBlacklist.isNotEmpty() && power.groupIndex in powerGroupBlacklist) return false
        return true
    }

    override fun toString() = "StatBoost#$realBoostId(${stat.xmlName} $boostType $amount)"
}

/** One applied boost (HS `StatBoost`): a value type there, so [copy] is used wherever HS copies it. */
class StatBoost(@JvmField val def: StatBoostDefinition) {
    @JvmField var iStacks = 1
    /** The source crew (HS keeps a pointer; null once it is gone). */
    @JvmField var crewSource: Any? = null
    @JvmField var crewSourceId = 0
    @JvmField var timer = HsTimer()
    /** Room ids on the player ship (first) and the enemy ship (second). */
    @JvmField var sourceRoomIds0 = ArrayList<Int>()
    @JvmField var sourceRoomIds1 = ArrayList<Int>()
    @JvmField var sourceShipId = 0

    fun copy(): StatBoost {
        val c = StatBoost(def)
        c.iStacks = iStacks; c.crewSource = crewSource; c.crewSourceId = crewSourceId
        c.timer = HsTimer().also { it.copyFrom(timer) }
        c.sourceRoomIds0 = ArrayList(sourceRoomIds0); c.sourceRoomIds1 = ArrayList(sourceRoomIds1)
        c.sourceShipId = sourceShipId
        return c
    }

    fun roomIds(shipId: Int): List<Int> = if (shipId == 1) sourceRoomIds1 else sourceRoomIds0
}
