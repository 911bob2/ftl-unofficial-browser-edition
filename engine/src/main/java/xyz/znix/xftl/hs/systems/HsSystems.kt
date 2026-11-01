// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp (ParseSystemsNode,
// CustomMindSystem, CustomCloneSystem, CustomUserSystems, SystemExclusivityManager, NameToSystemId/SystemIdToName),
// src/game/Ship/ShipSystems/TemporalSystem.cpp (TemporalSystemParser) and src/game/Utilities/Resources.cpp (the
// `<customSystems>` passes). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsCapability
import xyz.znix.xftl.hs.HsConfig
import kotlin.math.pow

/**
 * Hyperspace's system data from `hyperspace.xml` `<customSystems>`: the temporal system's level table, the clonebay
 * and mind control level tables, the system exclusivity groups and the Lua-registered system names. Only used while
 * [HS.active]; nothing here is initialised in vanilla play (the first touch comes from HS-only call sites).
 */
object HsSystems {
    /** ShipSystem ids HS adds (Systems.h). */
    const val SYS_TEMPORAL = 20
    const val SYS_CUSTOM_FIRST = 21

    /** TemporalSystemParser::TemporalLevel. HS leaves an unset field uninitialised; we use 0. */
    class TemporalLevel(@JvmField val duration: Int, @JvmField val cooldown: Int, @JvmField val strength: Float)

    /** CustomMindSystem::MindLevel. */
    class MindLevel(
        @JvmField var damageBoost: Float, @JvmField var healthBoost: Float, @JvmField var duration: Float,
        @JvmField var lock: Int, @JvmField var count: Int
    )

    /** CustomCloneSystem::CloneLevel. */
    class CloneLevel(
        @JvmField var jumpHP: Int, @JvmField var jumpHPPercent: Int, @JvmField var cloneHPPercent: Int,
        @JvmField var skillLossPercent: Int, @JvmField var cloneSpeed: Float, @JvmField var deathSpeed: Float,
        @JvmField var count: Int
    )

    private var parsedFor: HsConfig? = null

    private val levelSpeed = HashMap<Int, TemporalLevel>()
    private val levelSlow = HashMap<Int, TemporalLevel>()

    private val mindLevels = ArrayList<MindLevel>()
    private val mindDefault = MindLevel(3f, 50f, 30f, 4, 1)
    private val cloneLevels = ArrayList<CloneLevel>()
    private val cloneDefault = CloneLevel(0, 0, 100, 10, 5f, 3f, 1)

    /** SystemExclusivityManager (global): system id -> group index. */
    private val exclusivity = HashMap<Int, Int>()

    /** CustomUserSystems: Lua-defined system names, sorted, ids from [SYS_CUSTOM_FIRST]. */
    private val customNames = ArrayList<String>()
    private val customSub = HashSet<String>()

    /** SystemPositionManager (CustomSystems.cpp): the subsystem box x (static layout) and each subsystem's static offset. */
    private var subSystemOffsetX = 1015
    private val staticOffsets = HashMap<Int, Int>()

    /** SystemPositionManager::systemPositions[].position: the order of the dynamically placed system boxes (lower is further left). */
    private val positions = HashMap<Int, Int>()
    private const val DEFAULT_POSITION = Int.MAX_VALUE - 2

    /** SystemPositionManager::GetSystemPosition(id)->position; a system nobody listed goes just before the weapon system. */
    @JvmStatic
    fun position(id: Int): Int { ensure(); return positions[id] ?: DEFAULT_POSITION }

    /** SystemPositionManager::subSystemOffset (default 1015: the x of the subsystem box, vanilla 1280 wide layout). */
    @JvmStatic
    fun subSystemOffset(): Int { ensure(); return subSystemOffsetX }

    /** The static offset of a subsystem from the box (pilot 0, sensors 36, doors 72, battery 123 by default), or null. */
    @JvmStatic
    fun staticOffset(id: Int): Int? { ensure(); return staticOffsets[id] }

    private fun ensure() {
        val cfg = HS.config ?: return
        if (parsedFor === cfg) return
        parsedFor = cfg
        levelSpeed.clear(); levelSlow.clear(); exclusivity.clear(); customNames.clear(); customSub.clear()
        subSystemOffsetX = 1015; staticOffsets.clear()
        staticOffsets[6] = 0; staticOffsets[7] = 36; staticOffsets[8] = 72; staticOffsets[12] = 123
        positions.clear()
        // SystemPositionManager::systemPositions (CustomSystems.cpp): shields .. temporal 0..10, weapons and drones last
        for ((id, p) in listOf(0 to 0, 1 to 1, 5 to 2, 13 to 3, 2 to 4, 9 to 5, 10 to 6, 11 to 7, 14 to 8, 15 to 9, SYS_TEMPORAL to 10,
            3 to Int.MAX_VALUE - 1, 4 to Int.MAX_VALUE, 6 to -1, 7 to -1, 8 to -1, 12 to -1)) positions[id] = p
        // The vanilla tables HS restates (CustomSystems.cpp DAMAGE_BOOST ... CLONE_AMOUNT).
        mindLevels.clear()
        mindLevels += MindLevel(1f, 0f, 14f, 4, 0)
        mindLevels += MindLevel(1f, 0f, 14f, 4, 1)
        mindLevels += MindLevel(1.25f, 15f, 20f, 4, 1)
        mindLevels += MindLevel(2f, 30f, 28f, 4, 1)
        cloneLevels.clear()
        cloneLevels += CloneLevel(8, 0, 100, 10, 12f, 3f, 1)
        cloneLevels += CloneLevel(16, 0, 100, 10, 9f, 3f, 1)
        cloneLevels += CloneLevel(25, 0, 100, 10, 7f, 3f, 1)
        cloneLevels += CloneLevel(36, 0, 100, 10, 0f, 3f, 1)

        val nodes = cfg.nodesOf("customSystems")
        // Resources.cpp, first pass: custom system names, then the exclusivity groups (ids are mapped by then).
        for (node in nodes) {
            for (child in node.children) if (child.name == "customSystem") parseCustomSystem(child)
            var group = exclusivityGroups
            for (child in node.children) {
                if (child.name != "exclusivityGroup") continue
                for (sys in child.children) exclusivity[nameToId(sys.name)] = group
                group++
            }
            exclusivityGroups = group
        }
        // Second pass: ParseSystemsNode.
        for (node in nodes) {
            for (child in node.children) {
                if (child.name != "system") continue
                when (child.getAttributeValue("id") ?: continue) {
                    "temporal" -> parseTemporal(child)
                    "mind" -> parseMind(child)
                    "clone" -> parseClone(child)
                }
            }
            for (child in node.children) if (child.name == "systemPositions") parsePositions(child)
        }
    }

    /**
     * SystemPositionManager::ParsePositionsNode: `subSystemOffset`, `staticOffset` (the static subsystem box), `position`
     * (the order of the main system boxes, Ship.mainSystems, and of the dynamic subsystem row a subsystem without a
     * `staticOffset` selects: HsSystemBox.subLayout).
     */
    private fun parsePositions(node: Element) {
        node.getAttributeValue("subSystemOffset")?.let { subSystemOffsetX = it.trim().toIntOrNull() ?: subSystemOffsetX }
        for (child in node.children) {
            val id = nameToId(child.name)
            if (id < 0) { HS.unsupported("xml", HS.HYPERSPACE_XML, "systemPositions/" + child.name); continue }
            val staticOffset = child.getAttributeValue("staticOffset")
            if (staticOffset != null) staticOffsets[id] = staticOffset.trim().toIntOrNull() ?: 0 else staticOffsets.remove(id)
            // the entry is replaced whole: no `position` puts the system just before weapons
            positions[id] = child.getAttributeValue("position")?.trim()?.toIntOrNull() ?: DEFAULT_POSITION
        }
        if (staticOffsets.keys.any { !isSubsystemId(it) }) HS.unsupported("xml", HS.HYPERSPACE_XML, "systemPositions/staticOffset")
    }

    /**
     * The XML names this package implements, taken off the capability report ([HsCapability.run] calls this before
     * its scan). `battery@max`, `sensors@max`, `artillery@img` and `clonebay@img` are read by the generic system
     * parsing (ShipBlueprint.ParsedSystem: AI maximum and interior image) as FTL reads them for any system.
     */
    @JvmStatic
    fun registerCapabilities() {
        HsCapability.implement(
            "customSystems", "exclusivityGroup", "temporal", "systemPositions", "subSystemOffset", "staticOffset",
            "battery@max", "sensors@max", "artillery@img", "clonebay@img"
        )
        // Stores may sell the temporal system (HS's store list has id 20) and the Lua-defined systems (HsCustomSystem).
        xyz.znix.xftl.hs.ship.HsStores.customSystemSupported = { name -> name == "temporal" || nameToId(name) >= SYS_CUSTOM_FIRST }
    }

    private var exclusivityGroups = 0

    private fun parseCustomSystem(node: Element) {
        val name = node.getAttributeValue("id") ?: return
        if (nameToId(name) != -1) {
            HS.unsupported("config", HS.HYPERSPACE_XML, "customSystem $name: the name is already taken")
            return
        }
        customNames += name
        customNames.sort()
        if (HsConfig.bool(node.getAttributeValue("subSystem"))) customSub += name
        customInfos.remove(name)
    }

    private fun intOf(s: String?): Int? = s?.trim()?.toIntOrNull()
    private fun floatOf(s: String?): Float? = s?.trim()?.toFloatOrNull()

    private fun parseTemporal(node: Element) {
        for (child in node.children) {
            for (sub in child.children) {
                if (!sub.name.startsWith("level")) continue
                val level = intOf(sub.name.substring(5)) ?: continue
                var duration = 0; var cooldown = 0; var strength = 0f
                for (v in sub.children) when (v.name) {
                    "cooldown" -> intOf(v.text)?.let { cooldown = it }
                    "duration" -> intOf(v.text)?.let { duration = it }
                    "strength" -> floatOf(v.text)?.let { strength = it }
                }
                val def = TemporalLevel(duration, cooldown, strength)
                if (child.name == "speed") levelSpeed[level] = def
                if (child.name == "slow") levelSlow[level] = def
            }
        }
    }

    private fun parseMind(node: Element) {
        var level = 0
        for (l in node.children) {
            val d = floatOf(l.getAttributeValue("damageBoost")); val h = floatOf(l.getAttributeValue("healthBoost"))
            val t = floatOf(l.getAttributeValue("duration")); val lk = intOf(l.getAttributeValue("lock"))
            val c = intOf(l.getAttributeValue("count"))
            if (level < 4) {
                val m = mindLevels[level]
                d?.let { m.damageBoost = it }; h?.let { m.healthBoost = it }; t?.let { m.duration = it }
                lk?.let { m.lock = it }; c?.let { m.count = it }
            } else {
                mindLevels += MindLevel(d ?: mindDefault.damageBoost, h ?: mindDefault.healthBoost,
                    t ?: mindDefault.duration, lk ?: mindDefault.lock, c ?: mindDefault.count)
            }
            level++
        }
    }

    private fun parseClone(node: Element) {
        var level = 0
        for (l in node.children) {
            val jhp = intOf(l.getAttributeValue("jumpHP")); val jpc = intOf(l.getAttributeValue("jumpHPPercent"))
            val chp = intOf(l.getAttributeValue("cloneHPPercent")); val sk = intOf(l.getAttributeValue("skillLossPercent"))
            val cs = floatOf(l.getAttributeValue("cloneSpeed")); val ds = floatOf(l.getAttributeValue("deathSpeed"))
            val c = intOf(l.getAttributeValue("count"))
            if (level < 4) {
                val m = cloneLevels[level]
                jhp?.let { m.jumpHP = it }; jpc?.let { m.jumpHPPercent = it }; chp?.let { m.cloneHPPercent = it }
                sk?.let { m.skillLossPercent = it }; cs?.let { m.cloneSpeed = it }; ds?.let { m.deathSpeed = it }
                c?.let { m.count = it }
            } else {
                cloneLevels += CloneLevel(jhp ?: cloneDefault.jumpHP, jpc ?: cloneDefault.jumpHPPercent,
                    chp ?: cloneDefault.cloneHPPercent, sk ?: cloneDefault.skillLossPercent,
                    cs ?: cloneDefault.cloneSpeed, ds ?: cloneDefault.deathSpeed, c ?: cloneDefault.count)
            }
            level++
        }
    }

    // ---- ids ----

    private val VANILLA_IDS = arrayOf(
        "shields", "engines", "oxygen", "weapons", "drones", "medbay", "pilot", "sensors", "doors", "teleporter",
        "cloaking", "artillery", "battery", "clonebay", "mind", "hacking"
    )

    /** ShipSystem::NameToSystemId with HS's hook: temporal is 20, custom systems from 21; -1 when unknown. */
    @JvmStatic
    fun nameToId(name: String): Int {
        if (name == "temporal") return SYS_TEMPORAL
        val c = customNames.indexOf(name)
        if (c >= 0) return SYS_CUSTOM_FIRST + c
        val v = VANILLA_IDS.indexOf(name)
        if (v >= 0) return v
        return when (name) { "all" -> 16; "reactor" -> 17; "random" -> 18; "room" -> 19; else -> -1 }
    }

    /** ShipSystem::SystemIdToName with HS's hook. */
    @JvmStatic
    fun idToName(id: Int): String {
        ensure()
        if (id == SYS_TEMPORAL) return "temporal"
        if (id >= SYS_CUSTOM_FIRST) return customNames.getOrNull(id - SYS_CUSTOM_FIRST) ?: "invalid system id"
        // the native SystemIdToName's default (oracle sweep-world-ex1 sysname rows: -1 and unused ids)
        if (id == 17) return "reactor"
        return VANILLA_IDS.getOrNull(id) ?: "invalid system id"
    }

    @JvmStatic
    fun lastSystemId(): Int { ensure(); return SYS_CUSTOM_FIRST + customNames.size - 1 }

    /** ShipSystem::IsSubsystem with HS's hook: piloting, sensors, doors, battery; custom ids by `subSystem`. */
    @JvmStatic
    fun isSubsystemId(id: Int): Boolean {
        if (id >= SYS_CUSTOM_FIRST) { ensure(); return customNames.getOrNull(id - SYS_CUSTOM_FIRST)?.let { it in customSub } ?: false }
        return id == 6 || id == 7 || id == 8 || id == 12
    }

    /** SystemExclusivityManager::AreSystemsExclusive (global manager). */
    @JvmStatic
    fun areExclusive(a: Int, b: Int): Boolean {
        ensure()
        val ga = exclusivity[a] ?: return false
        val gb = exclusivity[b] ?: return false
        if (a == b) return false
        return ga == gb
    }

    /** The SystemInfo for a system blueprint type only HS knows; null in vanilla and for unknown types. */
    @JvmStatic
    fun info(type: String): SystemInfo? {
        if (!HS.active) return null
        ensure()
        if (type == "temporal") return Temporal.INFO
        if (type !in customNames) return null
        return customInfos.getOrPut(type) { HsCustomSystemInfo(type, type in customSub) }
    }

    private val customInfos = HashMap<String, SystemInfo>()

    // ---- TemporalSystemParser ----

    /** GetDilationDuration: [level] > 0 speeds up (the speed table), otherwise the slow table; default 10. */
    @JvmStatic
    fun dilationDuration(level: Int): Int {
        ensure()
        return (if (level > 0) levelSpeed else levelSlow)[kotlin.math.abs(level)]?.duration ?: 10
    }

    /** GetDilationStrength; default 2^level. */
    @JvmStatic
    fun dilationStrength(level: Int): Float {
        ensure()
        return (if (level > 0) levelSpeed else levelSlow)[kotlin.math.abs(level)]?.strength ?: 2.0.pow(level).toFloat()
    }

    /** GetDilationCooldown (lock units); default 2. */
    @JvmStatic
    fun dilationCooldown(level: Int): Int {
        ensure()
        return (if (level > 0) levelSpeed else levelSlow)[kotlin.math.abs(level)]?.cooldown ?: 2
    }

    // ---- CustomMindSystem / CustomCloneSystem ----

    /** CustomMindSystem::GetLevel for a power (the caller picks health under a hacking pulse, else effective power). */
    @JvmStatic
    fun mindLevel(power: Int): MindLevel {
        ensure()
        return if (power > 0 && power - 1 < mindLevels.size) mindLevels[power - 1] else mindDefault
    }

    /** CustomCloneSystem::GetLevel(int). */
    @JvmStatic
    fun cloneLevel(power: Int): CloneLevel {
        ensure()
        return if (power > 0 && power - 1 < cloneLevels.size) cloneLevels[power - 1] else cloneDefault
    }

    /** CloneSystem::OnLoop's `vanillaCloneTime` (CustomSystems.cpp). */
    @JvmField
    val VANILLA_CLONE_TIME = floatArrayOf(12f, 9f, 7f)
}
