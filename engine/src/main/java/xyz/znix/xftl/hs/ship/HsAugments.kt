package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.systems.MainSystem

/**
 * `CustomAugmentManager`: the `<augments>` definitions of hyperspace.xml and the HS rules for augment counts and
 * values, which include hidden augments (`augList["HIDDEN " + name]`) and augment functions (an augment that also
 * works as another one). Only reached while [HS.active] (Ship.hasAugment / getAugmentValueOrNull route here).
 *
 * The Lua chain events HAS_AUGMENTATION and GET_AUGMENTATION_VALUE run on the result in HS; the lua workstream can
 * set [hasAugmentationHook] / [augmentationValueHook] to run them.
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomAugments.cpp (ParseCustomAugmentNode, AugmentFunction::Functional,
// UpdateAugments, ShipObject::HasAugmentation / HasEquipment / GetAugmentationValue hooks).
object HsAugments {
    class AugmentFunction(
        @JvmField val value: Float,
        @JvmField val preferHigher: Boolean,
        @JvmField val useForReqs: Boolean,
        @JvmField val warning: Boolean,
        /** System name that must be installed, unhacked and powered; null = always. */
        @JvmField val sys: String?,
        @JvmField val modifyChoiceTextScrap: Boolean,
    )

    class AugmentDefinition(@JvmField val name: String) {
        /** function name -> functions (unordered_multimap in HS; insertion order here). */
        @JvmField val functions = ArrayList<Pair<String, AugmentFunction>>()
        @JvmField var locked = false
        @JvmField var icon = ""
        @JvmField var iconShipId = -1
        /** `<superShield>`, `<crystalShard>`, `<statBoosts>`: raw, for the systems/weapons/statcrew workstreams. */
        @JvmField var superShield: Element? = null
        @JvmField val crystalShards = ArrayList<Element>()
        @JvmField val statBoosts = ArrayList<Element>()
    }

    private val defs = HashMap<String, AugmentDefinition>()
    /** function name -> (augment name, function), all functions / those used for event requirements. */
    private val byFunction = HashMap<String, ArrayList<Pair<String, AugmentFunction>>>()
    private val byReq = HashMap<String, ArrayList<Pair<String, AugmentFunction>>>()
    @JvmField var loaded = false

    /** Lua HAS_AUGMENTATION(ship, name, count) -> count; null = no Lua. */
    @JvmField var hasAugmentationHook: ((Ship, String, Int) -> Int)? = null
    /** Lua GET_AUGMENTATION_VALUE(ship, name, value) -> value; null = no Lua. */
    @JvmField var augmentationValueHook: ((Ship, String, Float) -> Float)? = null

    /** `CreateChoiceBox` sets `useAugmentReq` while it evaluates choice requirements (events workstream). */
    @JvmField var useAugmentReq = false

    fun definition(name: String): AugmentDefinition? = defs[name]

    @JvmStatic
    fun isLocked(name: String): Boolean = defs[name]?.locked == true

    /** Parse every `<augments>` node of hyperspace.xml once per session (main pass, needs the blueprints for values). */
    @JvmStatic
    fun ensureLoaded(blueprintValue: (String) -> Float) {
        if (loaded) return
        loaded = true
        defs.clear(); byFunction.clear(); byReq.clear()
        for (node in HS.config?.nodesOf("augments") ?: emptyList()) {
            try {
                parse(node, blueprintValue)
            } catch (e: Exception) {
                HS.unsupported("parse", HS.HYPERSPACE_XML, "<augments>: ${e.message ?: e}")
            }
        }
    }

    private fun parse(node: Element, blueprintValue: (String) -> Float) {
        for (child in node.children) {
            if (child.name != "aug") continue
            val augName = child.getAttributeValue("name") ?: throw IllegalStateException("<aug> without name")
            val def = AugmentDefinition(augName)
            for (f in child.children) {
                when (f.name) {
                    "function" -> {
                        val fn = f.getAttributeValue("name") ?: throw IllegalStateException("<function> without name")
                        if (fn == augName) continue
                        var value = blueprintValue(fn)
                        f.getAttributeValue("value")?.let { value = it.toFloat() }
                        val func = AugmentFunction(
                            value,
                            f.getAttributeValue("preferHigher")?.let(HsConfig::bool) ?: true,
                            f.getAttributeValue("useForReqs")?.let(HsConfig::bool) ?: true,
                            f.getAttributeValue("warning")?.let(HsConfig::bool) ?: true,
                            f.getAttributeValue("sys"),
                            f.getAttributeValue("modifyChoiceTextScrap")?.let(HsConfig::bool) ?: false,
                        )
                        def.functions += Pair(fn, func)
                        byFunction.getOrPut(fn) { ArrayList() } += Pair(augName, func)
                        if (func.useForReqs) byReq.getOrPut(fn) { ArrayList() } += Pair(augName, func)
                    }
                    "superShield" -> def.superShield = f
                    "crystalShard" -> def.crystalShards += f
                    "locked" -> def.locked = true
                    "statBoosts" -> def.statBoosts += f
                    "icon" -> {
                        def.icon = f.text
                        when (f.getAttributeValue("ship")) { "player" -> def.iconShipId = 0; "enemy" -> def.iconShipId = 1 }
                    }
                }
            }
            defs[augName] = def
        }
    }

    /** `augListWithHidden`: slotted augments by name plus hidden ones (same name summed). */
    @JvmStatic
    fun augListWithHidden(ship: Ship): Map<String, Int> {
        val st = ship.hs ?: return emptyMap()
        st.augListCache?.let { return it }
        val list = HashMap<String, Int>()
        for (a in ship.augments) list[a.name] = (list[a.name] ?: 0) + 1
        for ((n, c) in st.hiddenAugs) list[n] = (list[n] ?: 0) + c
        st.augListCache = list
        return list
    }

    /** `UpdateAugments`: called when the ship's augments change. */
    @JvmStatic
    fun updateAugments(ship: Ship) {
        ship.hs?.augListCache = null
    }

    /** `AugmentFunction::Functional`: no system named, or that system is installed, not hack-pulsed and powered. */
    @JvmStatic
    fun functional(ship: Ship, f: AugmentFunction): Boolean {
        val sysName = f.sys ?: return true
        val system = ship.systems.firstOrNull { it.codename == sysName } ?: return false
        if (system.isHackActive) return false
        val power = if (system is MainSystem) system.powerSupplied else system.undamagedEnergy
        return power > 0
    }

    /** Ship.getAugmentValueOrNull under HS: null when the count is 0, else [value]. */
    @JvmStatic
    fun valueOrNull(ship: Ship, name: String): Float? {
        // HS runs GET_AUGMENTATION_VALUE on the result even when the ship has none (0): a script's value counts then too
        // (CustomAugments.cpp ShipObject::GetAugmentationValue hook; oracle ps-m1-ev ret|augv 2.5)
        if (count(ship, name) <= 0) return augmentationValueHook?.invoke(ship, name, 0f)?.takeIf { it != 0f }
        return value(ship, name, ship.sys.blueprintManager.getOrNull(name) as? AugmentBlueprint)
    }

    /**
     * Ship.cargoUpdated under HS: the engine's per-blueprint value table (which drives the augment classes' effects)
     * gets every slotted or hidden augment and every function target with its HS value. Function conditions are
     * evaluated now; the by-name queries ([count], [value]) evaluate them on each call as HS does.
     */
    @JvmStatic
    fun fillValues(ship: Ship, into: MutableMap<AugmentBlueprint, Float>) {
        updateAugments(ship)
        val manager = ship.sys.blueprintManager
        val names = LinkedHashSet<String>()
        val list = augListWithHidden(ship)
        names.addAll(list.keys)
        for ((fn, fs) in byFunction) if (fs.any { list.containsKey(it.first) }) names += fn
        for (n in names) {
            val bp = manager.getOrNull(n) as? AugmentBlueprint ?: continue
            if (count(ship, n) <= 0) continue
            into[bp] = value(ship, n, bp)
        }
    }

    /** `ShipObject::HasAugmentation` (count). */
    @JvmStatic
    fun count(ship: Ship, name: String): Int {
        val list = augListWithHidden(ship)
        var count = list[name] ?: 0
        byFunction[name]?.let { fs -> for ((aug, f) in fs) { val c = list[aug] ?: continue; if (functional(ship, f)) count += c } }
        return hasAugmentationHook?.invoke(ship, name, count) ?: count
    }

    /** HasEquipment's augment-function part alone (events counts the "HIDDEN x" entries itself). */
    @JvmStatic
    fun functionCount(ship: Ship, name: String): Int {
        ship.hs ?: return 0
        var count = 0
        val list = augListWithHidden(ship)
        val fs = if (useAugmentReq) byReq[name] else byFunction[name]
        fs?.let { for ((aug, f) in it) { val c = list[aug] ?: continue; if (functional(ship, f)) count += c } }
        return count
    }

    /** `ShipObject::HasEquipment`'s augment part: hidden copies + functions (requirement set while a choice box is built). */
    @JvmStatic
    fun equipmentCount(ship: Ship, name: String): Int {
        val st = ship.hs ?: return 0
        var count = st.hiddenAugs[name] ?: 0
        val list = augListWithHidden(ship)
        val fs = if (useAugmentReq) byReq[name] else byFunction[name]
        fs?.let { for ((aug, f) in it) { val c = list[aug] ?: continue; if (functional(ship, f)) count += c } }
        return count
    }

    /**
     * WorldManager::CreateChoiceBox hook (CustomAugments.cpp): the scrap fraction the choice box texts add to a reward. With the
     * showScrapCollectorScrap option it is the SCRAP_COLLECTOR augmentation value, without it only the augments whose
     * SCRAP_COLLECTOR function has `modifyChoiceTextScrap` count.
     */
    @JvmStatic
    fun choiceTextScrap(ship: Ship): Float {
        val bp = ship.sys.blueprintManager.getOrNull("SCRAP_COLLECTOR") as? AugmentBlueprint
        if (xyz.znix.xftl.hs.HS.config?.showScrapCollectorScrap == true) return value(ship, "SCRAP_COLLECTOR", bp)
        val list = augListWithHidden(ship)
        var aug = 0f
        var highest = 0f
        byFunction["SCRAP_COLLECTOR"]?.let { fs ->
            for ((name, f) in fs) {
                val c = list[name] ?: continue
                if (!f.modifyChoiceTextScrap || !functional(ship, f)) continue
                aug += f.value * c
                if ((f.preferHigher && f.value > highest) || (!f.preferHigher && f.value < highest)) highest = f.value
            }
        }
        return if (bp?.stackable != true) highest else aug
    }

    /**
     * `ShipObject::GetAugmentationValue`: the blueprint value times its count, plus each functional function
     * (value times the count of the augment carrying it); stacking blueprints sum, others take the highest
     * (or lowest with `preferHigher="false"`) single value. 0 when the count is 0.
     */
    @JvmStatic
    fun value(ship: Ship, name: String, blueprint: AugmentBlueprint?): Float {
        val list = augListWithHidden(ship)
        var count = list[name] ?: 0
        val base = blueprint?.nativeValue ?: 0f
        var value = base * count
        var highest = value
        byFunction[name]?.let { fs ->
            for ((aug, f) in fs) {
                val c = list[aug] ?: continue
                if (!functional(ship, f)) continue
                count += c
                value += f.value * c
                if ((f.preferHigher && f.value > highest) || (!f.preferHigher && f.value < highest)) highest = f.value
            }
        }
        val ret = if (count <= 0) 0f else if (blueprint?.stackable == true) value else highest
        return augmentationValueHook?.invoke(ship, name, ret) ?: ret
    }
}
