package xyz.znix.xftl.hs.ship

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Resource
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsCapability
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.random.Random

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomStore.cpp (ParsePriceNode, ParseHullRepairNode,
// ParseResourceNode, ParseStoreItemNode, ParseCategoryNode, CustomStore::ParseStoreNode, GetItemPricing,
// StoreComplete::CreateCustomStoreBoxes, StoreComplete::OnInit, Save/LoadStore, the Store::CreateStoreBoxes system hook and the
// SystemStoreBox constructor freeDrones hook) and Store_Extend.h (the structures). This engine is AGPL-3.0-or-later.

/**
 * Hyperspace custom stores (`<store><customStore id>` in hyperspace.xml): the definitions, the generation of a store from one
 * (`StoreComplete::OnInit`, drawing from [NativeRandom] in HS's order) and HS's replacement of the vanilla systems section
 * (`Store::CreateStoreBoxes(4)` hook), which applies to every store while Hyperspace is active.
 *
 * Which store an event opens is the event's `customStore`/`store` id (events workstream: [eventStoreId]); the console's
 * FORCE_STORE sets [forceCustomStore]. The buy page is drawn by StoreWindow from [HsStoreState].
 */
object HsStores {
    class ItemPrice {
        @JvmField var price = -1
        @JvmField var minPrice = 0f
        @JvmField var maxPrice = 0f
        @JvmField var useMinMax = false
        @JvmField var sectorScaled = false
        @JvmField var minModifier = 0f
        @JvmField var maxModifier = 0f
        @JvmField var flatModifier = false
        @JvmField var hasModifier = false
    }

    class ResourceItem(@JvmField val type: String, @JvmField val minCount: Int, @JvmField val maxCount: Int, @JvmField val price: ItemPrice)

    class StoreItem(@JvmField val blueprint: String, @JvmField val price: ItemPrice, @JvmField val mysteryItem: Boolean)

    class StoreCategory {
        /** 0 WEAPONS, 1 DRONES, 2 AUGMENTS, 3 CREW, 4 SYSTEMS (CategoryType; HS leaves it unset without a known `type`: 0 here). */
        @JvmField var categoryType = 0
        @JvmField val items = ArrayList<StoreItem>()
        @JvmField val guaranteedSystems = ArrayList<String>()
        @JvmField var customTitle = ""
        @JvmField var chance = 100
        @JvmField var groupChance = 0
        @JvmField var allowDuplicates = false
    }

    class StoreDefinition(@JvmField val id: String) {
        /** `std::unordered_map<int, std::vector<StoreCategory>> categories`, in libstdc++'s iteration order. */
        @JvmField val categories = IntHashOrder<ArrayList<StoreCategory>>()
        @JvmField val resources = ArrayList<ResourceItem>()
        @JvmField var hullRepair: ItemPrice? = null
        @JvmField var purchaseLimit = -1
    }

    @JvmField val storeDefs = HashMap<String, StoreDefinition>()
    @JvmField val freeDrones = ArrayList<String>()

    /** `<purchaseLimitNumber fontSize x y>` (PurchaseLimitIndicatorInfo). */
    @JvmField var limitFontSize = 0
    @JvmField var limitX = -16
    @JvmField var limitY = 60

    /** `CustomStore::forceCustomStore` (console FORCE_STORE); cleared when it names no store. */
    @JvmField var forceCustomStore = ""

    /** The `customStore` id of an event (CustomEventsParser::GetCustomEvent(eventName)->customStore); set by hs.events. */
    @JvmField var eventStoreId: ((InGameState, xyz.znix.xftl.sector.CreatedEvent) -> String?)? = null

    /** Diagnostics: called once before a beacon's custom store is generated (the events probe's `storescan`). */
    @JvmField var diagnosticScan: ((InGameState, StoreDefinition, Int) -> Unit)? = null

    /** Whether the engine can sell an HS custom system (the systems workstream answers; HS's list has id 20, temporal). */
    @JvmField var customSystemSupported: ((String) -> Boolean)? = null

    fun registerCapabilities() {
        HsCapability.implement("store", "customStore", "freeDrones", "itemPurchaseLimit", "purchaseLimitNumber")
    }

    /** Hyperspace.xml's `<store>` and `<purchaseLimitNumber>` nodes, in file order (Resources.cpp ParseHyperspace). */
    fun load(config: HsConfig?) {
        storeDefs.clear(); freeDrones.clear(); limitFontSize = 0; limitX = -16; limitY = 60; forceCustomStore = ""
        config ?: return
        for (node in config.nodesOf("store")) {
            try {
                parseStoreNode(node)
            } catch (e: Exception) {
                HS.unsupported("parse", HS.HYPERSPACE_XML, "<store>: ${e.message ?: e}")
            }
        }
        // PurchaseLimitIndicatorInfo as HsConfig read it (Resources.cpp:951-956: fontSize, x, y in turn; a bad number
        // ends the main pass and keeps the ones read before it).
        limitFontSize = config.purchaseLimitFontSize; limitX = config.purchaseLimitX; limitY = config.purchaseLimitY
    }

    private fun lexInt(s: String?): Int {
        s ?: throw IllegalArgumentException("missing value")
        require(Regex("[+-]?[0-9]+").matches(s)) { "bad lexical cast: $s" }
        return s.toInt()
    }

    private fun lexFloat(s: String?): Float {
        s ?: throw IllegalArgumentException("missing value")
        require(s.isNotEmpty() && s.trim() == s && s.toFloatOrNull() != null) { "bad lexical cast: $s" }
        return s.toFloat()
    }

    private fun parsePrice(node: Element): ItemPrice {
        val def = ItemPrice()
        for (c in node.children) {
            val v = c.text
            when (c.name) {
                "price" -> {
                    val min = c.getAttributeValue("min"); val max = c.getAttributeValue("max")
                    if (min != null && max != null) {
                        def.minPrice = lexFloat(min); def.maxPrice = lexFloat(max); def.useMinMax = true
                        if (def.minPrice > def.maxPrice) def.maxPrice = def.minPrice
                    }
                    if (v.isNotEmpty()) def.price = lexInt(v)
                }
                "sectorScaled" -> def.sectorScaled = true
                "modifier" -> {
                    val min = c.getAttributeValue("min"); val max = c.getAttributeValue("max")
                    if (min != null && max != null) {
                        def.minModifier = lexFloat(min); def.maxModifier = lexFloat(max)
                        if (def.minModifier > def.maxModifier) def.maxModifier = def.minModifier
                    }
                    c.getAttributeValue("flat")?.let { def.flatModifier = HsConfig.bool(it) }
                    if (v.isNotEmpty()) { def.minModifier = lexFloat(v); def.maxModifier = def.minModifier }
                    def.hasModifier = true
                }
            }
        }
        return def
    }

    private fun parseCategory(node: Element): StoreCategory {
        val def = StoreCategory()
        node.getAttributeValue("type")?.let { t ->
            val i = listOf("WEAPONS", "DRONES", "AUGMENTS", "CREW", "SYSTEMS").indexOf(t)
            if (i >= 0) def.categoryType = i
        }
        for (c in node.children) {
            val v = c.text
            when (c.name) {
                "item" -> {
                    var bp = ""; var mystery = false
                    for (i in c.children) when (i.name) {
                        "blueprint" -> bp = i.text
                        "mysteryItem" -> mystery = HsConfig.bool(i.text)
                    }
                    def.items += StoreItem(bp, parsePrice(c), mystery)
                }
                "customTitle" -> def.customTitle = v
                "chance" -> def.chance = lexInt(v)
                "groupChance" -> def.groupChance = lexInt(v)
                "allowDuplicates" -> def.allowDuplicates = true
                "guaranteedSystems" -> for (s in c.children) def.guaranteedSystems += s.name
            }
        }
        return def
    }

    private fun parseStoreNode(node: Element) {
        for (storeNode in node.children) {
            when (storeNode.name) {
                "freeDrones" -> for (d in storeNode.children) freeDrones += d.name
                "customStore" -> {
                    val id = storeNode.getAttributeValue("id") ?: throw IllegalArgumentException("<customStore> without id")
                    val def = StoreDefinition(id)
                    for (c in storeNode.children) {
                        when (c.name) {
                            "hullRepair" -> def.hullRepair = parsePrice(c)
                            "resource" -> {
                                var type = ""; var min = 0; var max = 0
                                for (r in c.children) when (r.name) {
                                    "type" -> type = r.text
                                    "count" -> {
                                        val a = r.getAttributeValue("min"); val b = r.getAttributeValue("max")
                                        if (a != null && b != null) { min = lexInt(a); max = lexInt(b) }
                                        if (r.text.isNotEmpty()) { min = lexInt(r.text); max = min }
                                    }
                                }
                                def.resources += ResourceItem(type, min, max, parsePrice(c))
                            }
                            "category" -> {
                                val cat = parseCategory(c)
                                val group = c.getAttributeValue("group")?.let { lexInt(it) } ?: -1
                                def.categories.getOrPut(group) { ArrayList() } += cat
                            }
                            "itemPurchaseLimit" -> def.purchaseLimit = lexInt(c.text)
                        }
                    }
                    storeDefs[id] = def
                }
            }
        }
    }

    // ---- generation ----

    /**
     * WorldManager::CreateStore + Store::OnInit hooks: the definition a new store uses, if any. [event] is the event that
     * creates the store (null when the engine makes a store without one, e.g. a store on a beacon loaded without it).
     */
    @JvmStatic
    fun definitionFor(game: InGameState, event: xyz.znix.xftl.sector.CreatedEvent?): StoreDefinition? {
        if (!HS.active) return null
        val customStoreId = event?.let { eventStoreId?.invoke(game, it) } ?: ""
        if (customStoreId.isEmpty() && forceCustomStore.isEmpty()) return null
        val storeId = forceCustomStore.ifEmpty { customStoreId }
        val def = storeDefs[storeId]
        if (def == null) forceCustomStore = ""
        return def
    }

    /** GetItemPricing. */
    private fun pricing(price: ItemPrice, defaultCost: Int, worldLevel: Int, freeBlueprintCost: Int, rand: Random): Int {
        var finalPrice = price.price
        if (finalPrice == -1) finalPrice = defaultCost
        if (price.useMinMax) {
            finalPrice = if (price.sectorScaled) {
                kotlin.math.floor(f32(f32(f32(price.maxPrice - price.minPrice) * f32(worldLevel.toFloat() / 7f)) + price.minPrice)).toInt()
            } else {
                rand.nextInt(price.maxPrice.toInt() - price.minPrice.toInt() + 1) + price.minPrice.toInt()
            }
        }
        finalPrice += freeBlueprintCost / 2
        if (price.hasModifier) {
            val modifier = if (price.minModifier == price.maxModifier) price.minModifier else {
                // random32() % RAND_MAX (0x7fff on the Windows CRT).
                val rng = rand.nextInt(RAND_MAX)
                f32(f32(f32(rng.toFloat() / RAND_MAX.toFloat()) * f32(price.maxModifier - price.minModifier)) + price.minModifier)
            }
            finalPrice = if (price.flatModifier) f32(finalPrice + modifier).toInt() else f32(finalPrice * modifier).toInt()
        }
        return finalPrice
    }

    private const val RAND_MAX = 0x7fff

    /** SSE float arithmetic (HS builds with -mfpmath=sse): TeaVM keeps floats as doubles unless rounded like this. */
    private fun f32(value: Float): Float = java.lang.Float.intBitsToFloat(java.lang.Float.floatToIntBits(value))

    /** System ids of HS's checks (ShipSystem::NameToSystemId); 20 is temporal. */
    private fun systemId(name: String): Int {
        val i = StoreData.SYSTEM_NAMES.indexOf(name)
        return if (i >= 0) i else if (name == "temporal") 20 else -1
    }

    private fun systemName(id: Int) = if (id == 20) "temporal" else StoreData.SYSTEM_NAMES.getOrNull(id)

    private fun hasSystem(game: InGameState, id: Int): Boolean {
        val name = systemName(id) ?: return false
        return game.player.systems.any { it.blueprint.name == name }
    }

    /** `myBlueprint.systemInfo.find(id) != end()`: the ship has a room for the system. */
    private fun canPlace(game: InGameState, id: Int): Boolean {
        val name = systemName(id) ?: return false
        if (id == 20 && customSystemSupported?.invoke(name) != true) return false
        return game.player.systemSlots.any { it.system?.name == name }
    }

    private fun systemBlueprint(game: InGameState, id: Int): SystemBlueprint? =
        systemName(id)?.let { game.blueprintManager.getOrNull(it) as? SystemBlueprint }

    /** A SystemStoreBox: the system, and the drone given with it (empty for every system but drone control). */
    class SystemBox(@JvmField val system: SystemBlueprint?, @JvmField val freeDrone: String?) {
        fun freeDroneCost(game: InGameState): Int = freeDrone?.let { (game.blueprintManager.getOrNull(it) as? DroneBlueprint)?.cost } ?: 0
    }

    /**
     * The SystemStoreBox constructor (0x51ce20 + HS's freeDrones hook): `random32() % 3` picks DEFENSE_1, REPAIR or COMBAT_1 for
     * drone control; with `<freeDrones>` HS draws one of those names (a list resolves in GetDroneBlueprint, one level).
     */
    private fun systemBox(game: InGameState, id: Int, rand: Random): SystemBox {
        val variant = rand.nextInt(3)
        var free: String? = if (id == 4) StoreData.DRONE_CONTROL_DRONES[variant] else null
        if (id == 4 && freeDrones.isNotEmpty()) {
            val name = freeDrones[rand.nextInt(freeDrones.size)]
            val ref = game.blueprintManager.getOrNull(name)
            free = if (ref is BlueprintList) ref.names.takeIf { it.isNotEmpty() }?.let { it[rand.nextInt(it.size)] } else name
            if (free != null && game.blueprintManager.getOrNull(free) !is DroneBlueprint) free = null
        }
        return SystemBox(systemBlueprint(game, id), free)
    }

    /**
     * HS's Store::CreateStoreBoxes(4): the guaranteed shields, drone control (only when a drones section is in the store) and
     * medical system, then up to three others. A custom store picks the others at random; a vanilla one by selection sampling.
     * Entries with a null system are the sold-out placeholders.
     */
    @JvmStatic
    fun systemBoxes(game: InGameState, rand: Random, custom: Boolean, hasDronesSection: Boolean): List<SystemBox> {
        val dlc = game.content.enableAdvancedEdition
        val guaranteedShields = !hasSystem(game, 0)
        val guaranteedDrones = !hasSystem(game, 4) && hasDronesSection
        var guaranteedMedicalId = -1
        if (!hasSystem(game, 5) && (!dlc || !hasSystem(game, 13))) {
            guaranteedMedicalId = when {
                !dlc || !canPlace(game, 13) -> 5
                !canPlace(game, 5) -> 13
                (rand.nextInt() and 1) == 0 -> 5
                else -> 13
            }
        }
        val boxes = ArrayList<SystemBox>()
        if (guaranteedDrones && canPlace(game, 4)) boxes += systemBox(game, 4, rand)
        if (guaranteedShields && canPlace(game, 0)) boxes += systemBox(game, 0, rand)
        if (guaranteedMedicalId != -1 && canPlace(game, guaranteedMedicalId)) boxes += systemBox(game, guaranteedMedicalId, rand)
        val added = boxes.size

        val checkList = ArrayList((0..11).toList() + 20)
        if (dlc) checkList += listOf(12, 13, 14, 15)
        val newSystems = ArrayList<Int>()
        for (id in checkList) {
            if (id == 0 && guaranteedShields || id == 4 && guaranteedDrones || (id == 5 || id == 13) && guaranteedMedicalId > 0) continue
            val bp = systemBlueprint(game, id)
            if (!hasSystem(game, id) && bp != null && (bp.rarity ?: 0) > 0 && canPlace(game, id)) newSystems += id
        }

        if (custom) {
            for (i in 0 until 3 - added) {
                if (newSystems.isEmpty()) { boxes += SystemBox(null, null); continue }
                val idx = rand.nextInt(newSystems.size)
                boxes += systemBox(game, newSystems[idx], rand)
                newSystems.removeAt(idx)
            }
        } else {
            var toAdd = minOf(3, newSystems.size) - added
            var count = added
            for (i in newSystems.indices) {
                if (toAdd <= 0) break
                if (rand.nextInt(newSystems.size - i) < toAdd) {
                    boxes += systemBox(game, newSystems[i], rand)
                    count++; toAdd--
                }
            }
            for (i in 0 until 3 - count) boxes += SystemBox(null, null)
        }
        return boxes
    }

    /** One box of a custom store page. */
    class Box(
        /** CategoryType of the section. */
        @JvmField val category: Int,
        @JvmField val blueprint: Blueprint?,
        @JvmField val crew: LivingCrewInfo?,
        @JvmField val freeDrone: String?,
        @JvmField var cost: Int,
    ) {
        /** StoreBox::count: 1 until bought. */
        @JvmField var count = 1
        @JvmField var showSale = false
        @JvmField var originalPrice = -1
        @JvmField var mysteryItem = false
    }

    class Section(@JvmField val category: Int, @JvmField val customTitle: String) {
        @JvmField val storeBoxes = ArrayList<ArrayList<Box>>()
        @JvmField var currentSection = 0
        val headingKey: String get() = "store_title_" + listOf("weapons", "drones", "augments", "crew", "systems")[category]
    }

    class Page { @JvmField val sections = ArrayList<Section>() }

    class ResourceBox(@JvmField val resource: Resource, @JvmField var count: Int, @JvmField var cost: Int)

    private fun listNames(game: InGameState, name: String): List<String> = (game.blueprintManager.getOrNull(name) as? BlueprintList)?.names ?: emptyList()

    /** StoreComplete::CreateCustomStoreBoxes: the vanilla section is made first, then each item takes from it or names its own. */
    private fun createBoxes(game: InGameState, cat: StoreCategory, level: Int, rand: Random): List<Box> {
        val out = ArrayList<Box>()
        val used = ArrayList<String>()
        val manager = game.blueprintManager
        fun pick(list: List<String>): String? {
            val l = if (cat.allowDuplicates) list else list.filter { it !in used }
            return if (l.isEmpty()) null else l[rand.nextInt(l.size)]
        }
        when (cat.categoryType) {
            0, 1, 2 -> {
                val type: Class<out Blueprint> = when (cat.categoryType) {
                    0 -> AbstractWeaponBlueprint::class.java; 1 -> DroneBlueprint::class.java; else -> AugmentBlueprint::class.java
                }
                val vanilla = ArrayList<Blueprint>(StoreData.vanillaSectionItems(game, rand, type))
                for (item in cat.items) {
                    val bp: Blueprint? = if (item.blueprint.isEmpty()) {
                        vanilla.removeLastOrNull()
                    } else {
                        val list = listNames(game, item.blueprint)
                        val name = if (list.isEmpty()) item.blueprint else pick(list)
                        (name?.let { manager.getOrNull(it) } as? Blueprint)?.takeIf { type.isInstance(it) }
                            ?: name?.let { HS.unsupported("store", HS.HYPERSPACE_XML, "unknown store item $it"); null }
                    }
                    bp ?: continue
                    out += Box(cat.categoryType, bp, null, null, pricing(item.price, bp.cost ?: 0, level, 0, rand))
                    used += bp.name
                }
            }
            3 -> {
                val vanilla = ArrayList(StoreData.vanillaSectionCrew(game, rand, level))
                for (item in cat.items) {
                    if (item.blueprint.isEmpty()) {
                        val crew = vanilla.removeLastOrNull() ?: continue
                        out += Box(3, crew.race, crew, null, pricing(item.price, crew.race.cost, level, 0, rand))
                    } else {
                        val list = listNames(game, item.blueprint)
                        val species = if (list.isEmpty()) item.blueprint else list[rand.nextInt(list.size)]
                        val race = manager.getOrNull(species) as? CrewBlueprint
                        if (race == null) { HS.unsupported("crew", HS.HYPERSPACE_XML, "store crew $species"); continue }
                        // HS 1b05a07 CustomStore.cpp CreateCustomStoreBoxes calls GetCrewBlueprint before
                        // CrewStoreBox's own lookup. Native 1.6.9 0x5e9690 generates an unused name here
                        // (0x5e1ef0); the extra lookup consumes a name even though only its cost is read.
                        if (HS.active) game.crewFactory.newName()
                        val crew = game.crewFactory.createStoreCrew(game, race, level, namedBlueprint = true)
                        out += Box(3, race, crew, null, pricing(item.price, race.cost, level, 0, rand))
                    }
                }
            }
            4 -> {
                val vanilla = ArrayList(systemBoxes(game, rand, true, false))
                for (item in cat.items) {
                    if (item.blueprint.isEmpty()) {
                        if (vanilla.isEmpty()) continue
                        val box = vanilla.removeAt(0)
                        val system = box.system ?: continue
                        out += Box(4, system, null, box.freeDrone, pricing(item.price, system.cost, level, box.freeDroneCost(game), rand))
                        used += system.name
                    } else {
                        val list = listNames(game, item.blueprint)
                        var picked: String? = null
                        if (list.isEmpty()) {
                            val id = systemId(item.blueprint)
                            if (id >= 0 && canPlace(game, id)) picked = item.blueprint
                        } else {
                            var l = list.filter { val id = systemId(it); id >= 0 && !hasSystem(game, id) && canPlace(game, id) }
                            if (!cat.allowDuplicates) l = l.filter { it !in used }
                            if (l.isNotEmpty()) {
                                picked = l[rand.nextInt(l.size)]
                                for (g in cat.guaranteedSystems) if (g in l) picked = g
                            }
                        }
                        val id = picked?.let { systemId(it) } ?: continue
                        if (systemBlueprint(game, id) == null) continue
                        val box = systemBox(game, id, rand)
                        val system = box.system ?: continue
                        out += Box(4, system, null, box.freeDrone, pricing(item.price, system.cost, level, box.freeDroneCost(game), rand))
                        used += system.name
                    }
                }
            }
        }
        return out
    }

    /** StoreComplete::OnInit. */
    @JvmStatic
    fun generate(game: InGameState, def: StoreDefinition, level: Int, rand: Random = NativeRandom): HsStoreState {
        val s = HsStoreState(def.id)
        s.worldLevel = level
        game.eventCreator.refreshUsedNames()

        for (r in def.resources) {
            val resource = when (r.type) { "fuel" -> Resource.FUEL; "missiles" -> Resource.MISSILES; "drones" -> Resource.DRONES; else -> null }
            if (resource == null) { HS.unsupported("store", HS.HYPERSPACE_XML, "store resource ${r.type}"); continue }
            // ItemStoreBox's constructor draws the vanilla count first (missiles %5+2, fuel %5+3, drone parts %3+2).
            when (resource) { Resource.MISSILES -> rand.nextInt(5); Resource.FUEL -> rand.nextInt(5); else -> rand.nextInt(3) }
            val count = r.minCount + rand.nextInt(r.maxCount - r.minCount + 1)
            val cost = pricing(r.price, resource.getBlueprint(game)?.cost ?: 0, level, 0, rand)
            s.resources += ResourceBox(resource, count, cost)
        }

        def.hullRepair?.let { s.repairPrice = pricing(it, if (level > 5) 4 else if (level > 2) 3 else 2, level, 0, rand) }

        val chosen = ArrayList<StoreCategory>()
        def.categories[-1]?.let { list -> for (cat in list) if (rand.nextInt(100) < cat.chance) chosen += cat }
        for ((group, list) in def.categories.entries()) {
            if (group == -1) continue
            val rng = rand.nextInt(100)
            val chanced = list.filter { it.groupChance > 0 }
            val unchanced = list.filter { it.groupChance <= 0 }
            val total = chanced.sumOf { it.groupChance }
            var selected: StoreCategory? = null
            if (rng < total) {
                var cum = 0
                for (cat in chanced) {
                    if (rng > cum && rng < cum + cat.groupChance) { selected = cat; break }
                    cum += cat.groupChance
                }
            } else if (unchanced.isNotEmpty()) {
                var size = (100 - total) / unchanced.size
                if (size <= 0) size = 1
                selected = unchanced.getOrNull(rng / size)
            }
            if (selected != null && rand.nextInt(100) < selected.chance) chosen += selected
        }

        var page = Page()
        for ((i, cat) in chosen.withIndex()) {
            val sec = Section(cat.categoryType, cat.customTitle)
            val boxes = createBoxes(game, cat, level, rand)
            if (boxes.isEmpty()) continue
            for ((boxIdx, box) in boxes.withIndex()) {
                if (boxIdx % 3 == 0) sec.storeBoxes += ArrayList<Box>()
                // HS reads the item definition by box index, whatever item the box came from.
                val boxDef = cat.items.getOrNull(boxIdx)
                box.originalPrice = box.blueprint?.cost ?: 0
                box.showSale = true
                box.mysteryItem = boxDef?.mysteryItem == true
                sec.storeBoxes.last() += box
            }
            page.sections += sec
            if ((page.sections.size % 2 == 0 && page.sections.size != 1) || i == chosen.size - 1) {
                s.pages += page
                page = Page()
            }
        }
        s.itemPurchaseLimit = def.purchaseLimit
        return s
    }
}

/** A generated custom store (StoreComplete): what the buy page shows; saved with the beacon's store. */
class HsStoreState(@JvmField val id: String) {
    @JvmField val pages = ArrayList<HsStores.Page>()
    @JvmField val resources = ArrayList<HsStores.ResourceBox>()
    /** The hull repair price, or null when the store has no repair boxes. */
    @JvmField var repairPrice: Int? = null
    @JvmField var itemPurchaseLimit = -1
    @JvmField var itemsPurchased = 0
    /** The world level the store was made at (Store::LoadStore's worldLevel argument on load). */
    @JvmField var worldLevel = 0
    /** UI page (not saved: HS's StoreComplete starts on page 0 after a load). */
    @JvmField var currentPage = 0

    val limitReached: Boolean get() = itemPurchaseLimit != -1 && itemsPurchased >= itemPurchaseLimit

    fun nextPage() { currentPage++; if (currentPage >= pages.size) currentPage = 0 }
    fun previousPage() { currentPage--; if (currentPage < 0) currentPage = pages.size - 1 }

    fun save(parent: Element) {
        val e = Element("hsStore").setAttribute("id", id).setAttribute("limit", itemPurchaseLimit.toString())
            .setAttribute("purchased", itemsPurchased.toString()).setAttribute("level", worldLevel.toString())
        repairPrice?.let { e.setAttribute("repair", it.toString()) }
        for (r in resources) e.addContent(Element("resource").setAttribute("type", r.resource.name)
            .setAttribute("count", r.count.toString()).setAttribute("cost", r.cost.toString()))
        for (p in pages) {
            val pe = Element("page")
            for (sec in p.sections) {
                val se = Element("section").setAttribute("category", sec.category.toString()).setAttribute("title", sec.customTitle)
                for (list in sec.storeBoxes) {
                    val le = Element("boxes")
                    for (b in list) {
                        val be = Element("box").setAttribute("count", b.count.toString()).setAttribute("cost", b.cost.toString())
                            .setAttribute("sale", b.showSale.toString()).setAttribute("original", b.originalPrice.toString())
                            .setAttribute("mystery", b.mysteryItem.toString())
                        b.blueprint?.let { be.setAttribute("blueprint", it.name) }
                        b.freeDrone?.let { be.setAttribute("freeDrone", it) }
                        b.crew?.let { c -> val ce = Element("crew"); c.saveToXML(ce); be.addContent(ce) }
                        le.addContent(be)
                    }
                    se.addContent(le)
                }
                pe.addContent(se)
            }
            e.addContent(pe)
        }
        parent.addContent(e)
    }

    companion object {
        @JvmStatic
        fun load(game: InGameState, parent: Element): HsStoreState? {
            val e = parent.getChild("hsStore") ?: return null
            val s = HsStoreState(e.getAttributeValue("id") ?: "")
            s.itemPurchaseLimit = e.getAttributeValue("limit")?.toIntOrNull() ?: -1
            s.itemsPurchased = e.getAttributeValue("purchased")?.toIntOrNull() ?: 0
            s.worldLevel = e.getAttributeValue("level")?.toIntOrNull() ?: game.eventCreator.currentLevel()
            s.repairPrice = e.getAttributeValue("repair")?.toIntOrNull()
            for (r in e.getChildren("resource")) s.resources += HsStores.ResourceBox(Resource.valueOf(r.getAttributeValue("type")),
                r.getAttributeValue("count").toInt(), r.getAttributeValue("cost").toInt())
            for (pe in e.getChildren("page")) {
                val page = HsStores.Page()
                for (se in pe.getChildren("section")) {
                    val sec = HsStores.Section(se.getAttributeValue("category").toInt(), se.getAttributeValue("title") ?: "")
                    for (le in se.getChildren("boxes")) {
                        val list = ArrayList<HsStores.Box>()
                        for (be in le.getChildren("box")) {
                            val bp = be.getAttributeValue("blueprint")?.let { game.blueprintManager.getOrNull(it) as? Blueprint }
                            // StoreComplete::LoadStore keeps only a crew box's race: `new CrewStoreBox(nullptr, worldLevel,
                            // race)` makes a new member (name, colours, skills) on every load.
                            var crew = be.getChild("crew")?.let { LivingCrewInfo.loadFromXML(it, game) }
                            if (sec.category == 3 && bp is CrewBlueprint)
                                crew = game.crewFactory.createStoreCrew(game, bp, s.worldLevel)
                            val b = HsStores.Box(sec.category, bp, crew, be.getAttributeValue("freeDrone"), be.getAttributeValue("cost").toInt())
                            b.count = be.getAttributeValue("count").toInt()
                            b.showSale = be.getAttributeValue("sale") == "true"
                            b.originalPrice = be.getAttributeValue("original")?.toIntOrNull() ?: -1
                            b.mysteryItem = be.getAttributeValue("mystery") == "true"
                            list += b
                        }
                        sec.storeBoxes += list
                    }
                    page.sections += sec
                }
                s.pages += page
            }
            return s
        }
    }
}

/**
 * The iteration order of a libstdc++ `std::unordered_map<int, V>` (MinGW, 32-bit size_t): identity hash, prime bucket counts
 * from `_Prime_rehash_policy` (one bucket at first, then at least doubling: 2, 5, 11, ...), a new key going to the front of its
 * bucket and an empty bucket's key to the front of the list; rehashing re-inserts in list order.
 * HS 1.23.2's runtime has no "11 buckets on the first insert" rule: the original's stores show it (seeds 202/505
 * STORE_STANDARD put group 1 before group 0, seed 101 STORE_ENGI group 0 before group 3; .build/hs/oracle/ships-store-*).
 */
class IntHashOrder<V> {
    private val keys = ArrayList<Int>()
    private val values = HashMap<Int, V>()
    private var bucketCount = 1L
    private var nextResize = 0L

    private fun bucket(k: Int, n: Long): Long = (k.toLong() and 0xffffffffL) % n

    private fun insertOrdered(order: MutableList<Int>, k: Int, n: Long) {
        val b = bucket(k, n)
        val i = order.indexOfFirst { bucket(it, n) == b }
        if (i >= 0) order.add(i, k) else order.add(0, k)
    }

    operator fun get(k: Int): V? = values[k]

    fun getOrPut(k: Int, make: () -> V): V {
        values[k]?.let { return it }
        // _M_need_rehash(bucketCount, size, 1)
        val n = keys.size + 1L
        if (n > nextResize) {
            val minBkts = n.toDouble()
            if (minBkts >= bucketCount) {
                val newCount = nextBucket(maxOf(kotlin.math.floor(minBkts).toLong() + 1, bucketCount * 2))
                val old = ArrayList(keys); keys.clear()
                for (key in old) insertOrdered(keys, key, newCount)
                bucketCount = newCount
            } else {
                nextResize = kotlin.math.floor(bucketCount.toDouble()).toLong()
            }
        }
        insertOrdered(keys, k, bucketCount)
        val v = make(); values[k] = v
        return v
    }

    fun entries(): List<Pair<Int, V>> = keys.map { Pair(it, values.getValue(it)) }

    private fun nextBucket(n: Long): Long {
        val fast = intArrayOf(2, 2, 2, 3, 5, 5, 7, 7, 11, 11, 11, 11, 13, 13)
        if (n < fast.size) {
            if (n == 0L) return 1
            nextResize = fast[n.toInt()].toLong()
            return fast[n.toInt()].toLong()
        }
        val p = PRIMES.firstOrNull { it >= n } ?: error("store group count beyond the modelled bucket table")
        nextResize = p
        return p
    }

    companion object {
        /** The start of libstdc++'s __prime_list (from index 6 on, as _M_next_bkt searches it). */
        private val PRIMES = longArrayOf(17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 103, 109, 113, 127,
            137, 139, 149, 157, 167, 179, 193, 199, 211, 227, 241, 257)
    }
}
