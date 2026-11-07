// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/StatBoost.cpp, StatBoostManager::ParseStatBoostNode
package xyz.znix.xftl.hs.stat

import org.jdom2.Element
import xyz.znix.xftl.hs.crew.ActivatedPowerDefinition
import xyz.znix.xftl.hs.crew.CustomCrewManager
import xyz.znix.xftl.hs.crew.ExplosionDefinition
import xyz.znix.xftl.hs.crew.HsDamage

/**
 * The stat boost definitions of the session: [defs] in parse order (`realBoostId`, used by saves), the `save="..."`
 * names, and the `maxStacks id` table. Reset by [reset] when HS content is (re)loaded.
 */
object StatBoostDefs {
    @JvmField val defs = ArrayList<StatBoostDefinition>()
    @JvmField val saved = HashMap<String, StatBoostDefinition>()
    private var nextStackId = 0
    private val stackIdMap = HashMap<String, Int>()

    /** Engine hook: the names in a `<blueprintList>` (BlueprintManager::GetBlueprintList; empty if none). */
    @JvmField var blueprintList: (String) -> List<String> = { emptyList() }

    /** Engine hook: custom system ids (CustomUserSystems, systems workstream) for `all` system lists. */
    @JvmField var customSystemIds: () -> List<Int> = { emptyList() }

    /** Engine hook: custom system names to ids (CustomUserSystems::NameToSystemId); null = not custom. */
    @JvmField var customSystemId: (String) -> Int? = { null }

    /** Definitions Lua made (`Hyperspace.StatBoostDefinition()`, realBoostId set by the script), by realBoostId. */
    @JvmField val luaDefs = HashMap<Int, StatBoostDefinition>()

    fun reset() {
        defs.clear(); saved.clear(); nextStackId = 0; stackIdMap.clear(); luaDefs.clear()
        weaponEnd = 0; legacyWeaponOrder = emptyList(); legacy1 = emptyArray(); legacy2 = emptyArray()
    }

    /** The number of definitions the weapon blueprints gave (HsWeapons.loadContent); hyperspace.xml's follow. */
    @JvmField var weaponEnd = 0

    /** The weapons' definitions in the order the browser's earlier saves numbered them (the blueprint map's order). */
    @JvmField var legacyWeaponOrder: List<StatBoostDefinition> = emptyList()

    /** Saved realBoostId -> definition for the browser's earlier numberings: no `ids` (crew first), `ids` 2. */
    private var legacy1: Array<StatBoostDefinition?> = emptyArray()
    private var legacy2: Array<StatBoostDefinition?> = emptyArray()

    /**
     * HS gives realBoostIds in its parse order: the weapon blueprints (ProcessWeaponBlueprint) before hyperspace.xml's
     * crew and augments (oracle sweep-crew2: zoltan_devotee's power boost is 560, the weapons have 541). The weapons
     * are parsed first (HsWeapons.loadContent), in the original's blueprint order, so every realBoostId is HS's and
     * the same on every platform. Called once hyperspace.xml's crew and augments are parsed: builds the tables for
     * saves made with the browser's earlier numberings, hyperspace.xml first (no `ids`) and the weapons first in the
     * blueprint map's order (`ids` 2).
     */
    @JvmStatic
    fun weaponsFirst() {
        val crew = defs.subList(weaponEnd.coerceAtMost(defs.size), defs.size).toList()
        legacy1 = (crew + legacyWeaponOrder).toTypedArray()
        legacy2 = (legacyWeaponOrder + crew).toTypedArray()
    }

    /** A saved realBoostId: today's numbering when the save says so (`ids` = 3), else an earlier one. */
    @JvmStatic
    fun bySavedId(id: Int, ids: String?): StatBoostDefinition? = when (ids) {
        "3" -> byRealId(id)
        "2" -> legacy2.getOrNull(id) ?: byRealId(id)
        else -> legacy1.getOrNull(id) ?: byRealId(id)
    }

    /** StatBoostDefinition::statBoostDefs[realBoostId] (saves). */
    @JvmStatic
    fun byRealId(id: Int): StatBoostDefinition? = defs.getOrNull(id)?.takeIf { it.realBoostId == id } ?: luaDefs[id]

    private fun giveStackId(): Int = ++nextStackId
    private fun giveStackId(name: String): Int = stackIdMap.getOrPut(name) { ++nextStackId }

    /** ShipSystem::NameToSystemId (0x63db50) with HS's temporal (20) and custom systems. */
    @JvmStatic
    fun systemId(name: String): Int = when (name) {
        "temporal" -> 20
        else -> customSystemId(name) ?: VANILLA_SYSTEM_IDS[name] ?: -1
    }

    private val VANILLA_SYSTEM_IDS = mapOf(
        "shields" to 0, "teleporter" to 9, "sensors" to 7, "engines" to 1, "oxygen" to 2, "medbay" to 5, "pilot" to 6,
        "doors" to 8, "weapons" to 3, "drones" to 4, "reactor" to 17, "cloaking" to 10, "artillery" to 11,
        "random" to 18, "room" to 19, "battery" to 12, "clonebay" to 13, "mind" to 14, "hacking" to 15, "all" to 16
    )

    private fun shipTarget(v: String, current: StatBoostDefinition.ShipTarget): StatBoostDefinition.ShipTarget = when (v) {
        "PLAYER_SHIP" -> StatBoostDefinition.ShipTarget.PLAYER_SHIP
        "ENEMY_SHIP" -> StatBoostDefinition.ShipTarget.ENEMY_SHIP
        "CURRENT_ALL" -> StatBoostDefinition.ShipTarget.CURRENT_ALL
        "CURRENT_ROOM" -> StatBoostDefinition.ShipTarget.CURRENT_ROOM
        "OTHER_ALL" -> StatBoostDefinition.ShipTarget.OTHER_ALL
        "ORIGINAL_SHIP" -> StatBoostDefinition.ShipTarget.ORIGINAL_SHIP
        "ORIGINAL_OTHER_SHIP" -> StatBoostDefinition.ShipTarget.ORIGINAL_OTHER_SHIP
        "ALL" -> StatBoostDefinition.ShipTarget.ALL
        "CREW_TARGET" -> StatBoostDefinition.ShipTarget.CREW_TARGET
        "TARGETS_ME" -> StatBoostDefinition.ShipTarget.TARGETS_ME
        else -> current
    }

    private fun nameList(child: Element, initial: List<String>?): ArrayList<String> {
        val list = ArrayList<String>(initial ?: emptyList())
        for (c in child.children) {
            if (c.name == "blueprintList" && c.getAttributeValue("load") != null) list.addAll(blueprintList(c.getAttributeValue("load")))
            else list.add(c.name)
        }
        return list
    }

    /** StatBoostManager::ParseStatBoostNode. */
    @JvmStatic
    fun parse(node: Element, boostSource: StatBoostDefinition.BoostSource, isRoomBased: Boolean): StatBoostDefinition {
        var def: StatBoostDefinition
        var newDef = false
        val load = node.getAttributeValue("load")
        if (load != null) {
            val savedDef = saved[load]
            if (savedDef != null) {
                if (node.getAttributeValue("name") != null || HsParse.value(node).isNotEmpty() || node.children.isNotEmpty()) {
                    def = savedDef.copy()
                    newDef = true
                } else {
                    def = savedDef
                    if (boostSource != def.boostSource || isRoomBased != def.isRoomBased) newDef = true // mismatched source needs a new def
                }
            } else {
                xyz.znix.xftl.hs.HS.unsupported("parse", xyz.znix.xftl.hs.HS.HYPERSPACE_XML, "Unable to load stat boost: $load")
                def = StatBoostDefinition()
                newDef = true
            }
        } else {
            def = StatBoostDefinition()
            newDef = true
        }

        if (newDef) {
            def.realBoostId = defs.size
            defs.add(def)
            def.boostSource = boostSource
            def.isRoomBased = isRoomBased
            val stat = CrewStat.byXmlName(node.getAttributeValue("name"))
                ?: throw HsInvalidArgument("Unrecognized stat boost stat name: ${node.getAttributeValue("name")}")
            def.stat = stat
            if (stat == CrewStat.POWER_EFFECT) def.amount = -1f // default amount for count of abilities to replace, negative = unlimited
            for (child in node.children) {
                val name = child.name
                val v = HsParse.value(child)
                when (name) {
                    "statBoost" -> def.providedStatBoosts.add(parse(child, StatBoostDefinition.BoostSource.CREW, false))
                    "boostType" -> def.boostType = when (v) {
                        "MULT" -> StatBoostDefinition.BoostType.MULT
                        "FLAT", "ADD" -> StatBoostDefinition.BoostType.FLAT
                        "SET" -> StatBoostDefinition.BoostType.SET
                        "FLIP" -> StatBoostDefinition.BoostType.FLIP
                        "MIN" -> StatBoostDefinition.BoostType.MIN
                        "MAX" -> StatBoostDefinition.BoostType.MAX
                        "REPLACE_GROUP" -> StatBoostDefinition.BoostType.REPLACE_GROUP
                        "REPLACE_POWER" -> StatBoostDefinition.BoostType.REPLACE_POWER
                        else -> def.boostType
                    }
                    "amount" -> def.amount = HsParse.float(v)
                    "value" -> def.value = HsParse.bool(v)
                    "dangerRating" -> def.dangerRating = HsParse.float(v)
                    "duration" -> def.duration = HsParse.float(v)
                    "jumpClear" -> def.jumpClear = HsParse.bool(v)
                    "cloneClear" -> def.cloneClear = HsParse.bool(v)
                    "priority" -> def.priority = HsParse.int(v)
                    "boostAnim" -> def.boostAnim = v
                    "roomAnim" -> def.roomAnim = child
                    "affectsSelf" -> def.affectsSelf = HsParse.bool(v)
                    "shipTarget" -> def.shipTarget = shipTarget(v, def.shipTarget)
                    "systemRoomTarget" -> when (v) {
                        "ALL" -> def.systemRoomTarget = StatBoostDefinition.SystemRoomTarget.ALL
                        "NONE" -> def.systemRoomTarget = StatBoostDefinition.SystemRoomTarget.NONE
                    }
                    "crewTarget" -> when (v) {
                        "ALLIES" -> def.crewTarget = StatBoostDefinition.CrewTarget.ALLIES
                        "ENEMIES" -> def.crewTarget = StatBoostDefinition.CrewTarget.ENEMIES
                        "ALL" -> def.crewTarget = StatBoostDefinition.CrewTarget.ALL
                        "CURRENT_ALLIES" -> def.crewTarget = StatBoostDefinition.CrewTarget.CURRENT_ALLIES
                        "CURRENT_ENEMIES" -> def.crewTarget = StatBoostDefinition.CrewTarget.CURRENT_ENEMIES
                        "ORIGINAL_ALLIES" -> def.crewTarget = StatBoostDefinition.CrewTarget.ORIGINAL_ALLIES
                        "ORIGINAL_ENEMIES" -> def.crewTarget = StatBoostDefinition.CrewTarget.ORIGINAL_ENEMIES
                        "SELF" -> { def.crewTarget = StatBoostDefinition.CrewTarget.SELF; def.affectsSelf = true }
                        "NONE" -> def.crewTarget = StatBoostDefinition.CrewTarget.SELF
                    }
                    "droneTarget" -> when (v) {
                        "DRONES" -> def.droneTarget = StatBoostDefinition.DroneTarget.DRONES
                        "CREW" -> def.droneTarget = StatBoostDefinition.DroneTarget.CREW
                        "ALL" -> def.droneTarget = StatBoostDefinition.DroneTarget.ALL
                    }
                    "functionalTarget" -> def.functionalTarget = HsParse.bool(v)
                    "whiteList" -> def.whiteList = nameList(child, child.getAttributeValue("load")?.let(blueprintList) ?: def.whiteList)
                    "blackList" -> def.blackList = nameList(child, child.getAttributeValue("load")?.let(blueprintList) ?: def.blackList)
                    "healthReq" -> minMax(child, def.healthReq)
                    "healthFractionReq" -> minMax(child, def.healthFractionReq)
                    "oxygenReq" -> minMax(child, def.oxygenReq)
                    "fireCount" -> {
                        child.getAttributeValue("min")?.let { def.fireCount[0] = HsParse.int(it) }
                        child.getAttributeValue("max")?.let { def.fireCount[1] = HsParse.int(it) }
                    }
                    "extraConditions" -> {
                        val list = if (child.getAttributeValue("type") == "or") def.extraOrConditions else def.extraConditions
                        CustomCrewManager.parseExtraConditions(child, list)
                    }
                    "systemList" -> for (c in child.children) def.systemList.add(c.name)
                    "systemPowerDependency" -> for (c in child.children) {
                        when (c.name) {
                            "all" -> {
                                for (i in 0 until 15) def.systemPowerScaling.add(i)
                                def.systemPowerScaling.add(20)
                                def.systemPowerScaling.addAll(customSystemIds())
                            }
                            "reactorMax" -> def.systemPowerScaling.add(16)
                            "reactorCurrent" -> def.systemPowerScaling.add(17)
                            else -> def.systemPowerScaling.add(systemId(c.name))
                        }
                    }
                    "systemPowerScaling" -> {
                        var noSys = false
                        var hackedSys = false
                        for (c in child.children) {
                            when (c.name) {
                                "noSys" -> { noSys = true; def.powerScalingNoSys = HsParse.float(HsParse.value(c)) }
                                "hackedSys" -> { hackedSys = true; def.powerScalingHackedSys = HsParse.float(HsParse.value(c)) }
                                else -> def.powerScaling.add(HsParse.float(HsParse.value(c)))
                            }
                        }
                        if (def.powerScaling.isNotEmpty()) {
                            if (!noSys) def.powerScalingNoSys = def.powerScaling[0]
                            if (!hackedSys) def.powerScalingHackedSys = def.powerScaling[0]
                        }
                    }
                    "powerScalingShipTarget" -> {
                        if (v == "CREW_TARGET") throw HsInvalidArgument("CREW_TARGET is an invalid option for powerScalingShipTarget!")
                        if (v == "TARGETS_ME") throw HsInvalidArgument("TARGETS_ME is an invalid option for powerScalingShipTarget!")
                        def.powerScalingShipTarget = shipTarget(v, def.powerScalingShipTarget)
                    }
                    "maxStacks" -> {
                        if (v.isNotEmpty()) def.maxStacks = HsParse.int(v)
                        val id = child.getAttributeValue("id")
                        def.stackId = if (id != null) giveStackId(id) else giveStackId()
                    }
                    "deathEffect" -> {
                        val e = ExplosionDefinition()
                        e.damage = HsDamage(ownerId = -1, selfId = -1)
                        if (def.boostType == StatBoostDefinition.BoostType.MULT) {
                            e.damage = HsDamage(1, 1, 1, 1, 1, 1, 1, 1, true, -1, -1, true, false, true, 1)
                            e.shipFriendlyFire = true
                            e.transformRaceDeathSound = true
                            e.transformRaceHealth = 1f
                        } else if (def.boostType == StatBoostDefinition.BoostType.FLAT) {
                            e.transformRaceHealthFraction = 0f
                        }
                        def.deathEffectChange = e
                        CustomCrewManager.parseDeathEffect(child, e)
                    }
                    "powerEffect" -> def.powerChange = CustomCrewManager.parseAbilityEffect(child)
                    "powerWhitelist" -> for (c in child.children) {
                        if (c.name == "powerEffect") def.powerWhitelist.add(CustomCrewManager.parseAbilityEffect(c))
                        else if (c.name == "powerResource") def.powerResourceWhitelist.add(CustomCrewManager.parseAbilityResource(c))
                    }
                    "powerBlacklist" -> for (c in child.children) {
                        if (c.name == "powerEffect") def.powerBlacklist.add(CustomCrewManager.parseAbilityEffect(c))
                        else if (c.name == "powerResource") def.powerResourceBlacklist.add(CustomCrewManager.parseAbilityResource(c))
                    }
                    "powerGroupWhitelist" -> for (c in child.children) def.powerGroupWhitelist.add(CustomCrewManager.replaceGroup(c.name))
                    "powerGroupBlacklist" -> for (c in child.children) def.powerGroupBlacklist.add(CustomCrewManager.replaceGroup(c.name))
                }
                // These two only apply to transformRace boosts (HS checks the stat inside the loop).
                if (name == "race" && def.stat == CrewStat.TRANSFORM_RACE) def.stringValue = v
                if (name == "permanent" && def.stat == CrewStat.TRANSFORM_RACE) def.value = HsParse.bool(v)
            }
        }
        node.getAttributeValue("save")?.let { saved[it] = def }

        // post process
        if (def.boostType == StatBoostDefinition.BoostType.REPLACE_GROUP && def.powerGroupWhitelist.isEmpty()) {
            // REPLACE_GROUP is alias for REPLACE_POWER that includes the group of the replacement power
            val change: ActivatedPowerDefinition = def.powerChange
                ?: throw HsInvalidArgument("Stat Boost REPLACE_GROUP but no powerEffect specified.")
            def.powerGroupWhitelist.add(change.replaceGroupIndex)
            def.boostType = StatBoostDefinition.BoostType.REPLACE_POWER
        }
        def.hasPowerList = def.powerWhitelist.isNotEmpty() || def.powerResourceWhitelist.isNotEmpty() ||
            def.powerGroupWhitelist.isNotEmpty() || def.powerBlacklist.isNotEmpty() ||
            def.powerResourceBlacklist.isNotEmpty() || def.powerGroupBlacklist.isNotEmpty()
        return def
    }

    private fun minMax(child: Element, range: FloatArray) {
        child.getAttributeValue("min")?.let { range[0] = HsParse.float(it) }
        child.getAttributeValue("max")?.let { range[1] = HsParse.float(it) }
        child.getAttributeValue("above")?.let { range[0] = nextAfter(HsParse.float(it), true) }
        child.getAttributeValue("below")?.let { range[1] = nextAfter(HsParse.float(it), false) }
    }

    /** std::nextafterf(x, +/-HUGE_VAL). */
    @JvmStatic
    fun nextAfter(x: Float, up: Boolean): Float {
        if (x.isNaN() || (x.isInfinite() && (x > 0) == up)) return x
        if (x == 0f) return if (up) java.lang.Float.intBitsToFloat(1) else -java.lang.Float.intBitsToFloat(1)
        val bits = java.lang.Float.floatToRawIntBits(x)
        return java.lang.Float.intBitsToFloat(if ((x > 0) == up) bits + 1 else bits - 1)
    }
}
