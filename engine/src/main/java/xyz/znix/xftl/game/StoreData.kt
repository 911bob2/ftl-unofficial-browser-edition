package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import java.util.*
import kotlin.math.min
import kotlin.random.Random
import ftl.browser.NativeRandom

/**
 * Carries persistent information about a store, like its available items and prices.
 */
class StoreData {
    val availableResources = ResourceSet()

    val sections = ArrayList<Section>()

    /**
     * If this store has a systems section, this contains the systems, or null if they're sold out.
     */
    val systems = ArrayList<SystemBlueprint?>()

    /**
     * If this store has a weapons section, this contains the weapons, or null if they're sold out.
     */
    val weapons = ArrayList<AbstractWeaponBlueprint?>()

    /**
     * If this store has a drones section, this contains the drones, or null if they're sold out.
     */
    val drones = ArrayList<DroneBlueprint?>()

    /**
     * If this store has an augments section, this contains the augments, or null if they're sold out.
     */
    val augments = ArrayList<AugmentBlueprint?>()

    /**
     * If this store has a crew section, this is the crew, or null if they're sold out.
     */
    val crew = ArrayList<LivingCrewInfo?>()

    /**
     * The drone control variant of each system box (SystemStoreBox +0x190,
     * `rand % 3`, drawn for every system box in 0x51ce20): 0 DEFENSE_1,
     * 1 REPAIR, 2 COMBAT_1. Only a drone control box uses it: the drone is
     * given free on purchase and half its cost is added to the price. -1
     * means the box has no drone (stores saved before this was tracked).
     */
    val systemVariants = ArrayList<Int>()

    /**
     * The original's Store creation (WorldManager::CreateStore 0x613010 ->
     * Store::OnInit 0x4c3b20 -> 0x4bed80 per section -> 0x4c2710 for the
     * resources), drawing in the original's order from [rand], which is the
     * global [NativeRandom] stream in the game. The world level is the
     * current sector's column, as for events.
     */
    fun generateRandomContents(
        game: InGameState,
        rand: Random = NativeRandom,
        level: Int = game.eventCreator.currentLevel()
    ) {
        val ae = game.content.enableAdvancedEdition
        val ship = game.player
        val totalSystems = ship.systems.size

        // The names crew generated for the store avoid: the player's crew.
        game.eventCreator.refreshUsedNames()

        // 0x4c3b20: the section count, then the section ids ("slots").
        val count = rand.nextInt(3) + 2

        // Non-AE with all eleven systems: no systems section (id 4).
        val noSystemsOption = !ae && totalSystems == 11
        val idRange = if (noSystemsOption) 4 else 5

        val slots = IntArray(4) { UNUSED_SLOT }
        if (totalSystems < 11 && rand.nextInt(2) == 1) {
            slots[0] = SECTION_SYSTEMS
        } else {
            slots[0] = rand.nextInt(idRange)
        }
        for (i in 1 until count) {
            var id: Int
            do {
                id = rand.nextInt(idRange)
            } while (id in slots)
            slots[i] = id
        }

        // Without a drone system, a drones section needs a systems section
        // beside it (which then offers drone control); otherwise it becomes
        // the first of weapons, augments, crew and systems the store lacks.
        val hasDroneSystem = ship.systems.any { it is Drones }
        if (!hasDroneSystem && SECTION_SYSTEMS !in slots && SECTION_DRONES in slots) {
            val index = slots.indexOf(SECTION_DRONES)
            val replacement = intArrayOf(SECTION_WEAPONS, SECTION_AUGMENTS, SECTION_CREW, SECTION_SYSTEMS)
                .filter { it != SECTION_SYSTEMS || !noSystemsOption }
                .firstOrNull { it !in slots }
            if (replacement != null)
                slots[index] = replacement
        }

        for (i in 0 until count)
            sections += SECTION_BY_ID.getValue(slots[i])

        // 0x4bed80: each section in slot order.
        val hasDronesSection = SECTION_DRONES in slots
        for (i in 0 until count) {
            when (slots[i]) {
                SECTION_WEAPONS -> weapons += pickItems(game, rand, AbstractWeaponBlueprint::class.java)
                SECTION_DRONES -> drones += pickItems(game, rand, DroneBlueprint::class.java)
                SECTION_AUGMENTS -> augments += pickItems(game, rand, AugmentBlueprint::class.java)
                SECTION_CREW -> crew += generateCrew(game, rand, level)
                SECTION_SYSTEMS -> generateSystems(game, rand, hasDronesSection)
            }
        }

        // 0x4c2710 -> ItemStoreBox 0x51c1f0: fuel, missiles, drone parts.
        availableResources[Resource.FUEL] = rand.nextInt(5) + 3
        availableResources[Resource.MISSILES] = rand.nextInt(5) + 2
        availableResources[Resource.DRONES] = rand.nextInt(3) + 2
    }

    /**
     * The item count of an item section: 3 in AE and 2-3 without. The draw
     * is made either way (`rand % 1 + 3` in AE).
     */
    private fun itemCount(game: InGameState, rand: Random): Int {
        return if (game.content.enableAdvancedEdition) rand.nextInt(1) + 3 else rand.nextInt(2) + 2
    }

    /** A weapons, drones or augments section: the count, then that many distinct weighted picks. */
    private fun <T> pickItems(game: InGameState, rand: Random, type: Class<T>): List<T?> {
        val count = itemCount(game, rand)
        val items = ArrayList<T?>(game.lootPool.getManyRandom(type, rand, count))
        while (items.size < 3)
            items.add(null)
        return items
    }

    /** CrewStoreBox 0x51ae20: a weighted race, then the name, the colours and the skills. */
    private fun generateCrew(game: InGameState, rand: Random, level: Int): List<LivingCrewInfo?> {
        val count = itemCount(game, rand)
        val result = ArrayList<LivingCrewInfo?>()
        repeat(count) {
            val race = game.lootPool.getRandom(rand) { it is CrewBlueprint } as? CrewBlueprint ?: error("No available crew!")
            result += game.crewFactory.createStoreCrew(game, race, level)
        }
        while (result.size < 3)
            result.add(null)
        return result
    }

    /** The system section: SystemStoreBox candidates and the selection of 0x4bed80 case 4. */
    private fun generateSystems(game: InGameState, rand: Random, hasDronesSection: Boolean) {
        val ae = game.content.enableAdvancedEdition
        val ship = game.player
        val owned = ship.systems.map { it.blueprint.name }.toSet()

        fun has(id: Int) = SYSTEM_NAMES[id] in owned
        fun blueprint(id: Int) = game.blueprintManager.getOrNull(SYSTEM_NAMES[id]) as? SystemBlueprint

        val candidates = ArrayList<Int>()
        for (id in SYSTEM_NAMES.indices) {
            // Without AE only the first eleven exist.
            if (!ae && id >= 11)
                continue

            // Drone control is forced (below) when the drones section needs it.
            if (hasDronesSection && !has(4) && id == 4)
                continue

            // Medical systems are forced when the ship has none.
            if (!has(5) && (id == 5 || id == 13) && !(ae && has(13)))
                continue

            // Shields are forced when the ship has none.
            if (!has(0) && id == 0)
                continue

            if (has(id))
                continue

            // A blueprint rarity of zero (artillery) is never offered.
            val bp = blueprint(id) ?: continue
            if ((bp.rarity ?: 0) != 0)
                candidates += id
        }

        var left = min(3, candidates.size)
        val chosen = ArrayList<Int>()
        val variants = ArrayList<Int>()

        // Every box draws its drone control variant when it is made.
        fun addBox(id: Int) {
            left--
            chosen += id
            variants += rand.nextInt(3)
        }

        if (hasDronesSection && !has(4))
            addBox(4)

        if (!has(0))
            addBox(0)

        if (!has(5) && (!ae || !has(13))) {
            val medical = if (!ae) 5 else if ((rand.nextInt() and 1) != 0) 5 else 13
            addBox(medical)
        }

        // Selection sampling over the candidates in id order.
        if (candidates.isNotEmpty() && left > 0) {
            var index = 0
            while (index < candidates.size) {
                val remaining = candidates.size - index
                if (left < rand.nextInt(remaining) + 1) {
                    index++
                    continue
                }

                addBox(candidates[index])
                index++
                if (index >= candidates.size || left < 1)
                    break
            }
        }

        for ((i, id) in chosen.withIndex()) {
            systems += blueprint(id)
            systemVariants += variants[i]
        }
        while (systems.size < 3) {
            systems += null
            systemVariants += -1
        }
    }

    /**
     * What a system box costs: drone control adds half the price of its
     * free drone (SystemStoreBox 0x51ce20 / SetExtraData 0x519070).
     */
    fun systemPrice(game: InGameState, index: Int): Int {
        val system = systems.getOrNull(index) ?: return 0
        val drone = freeDrone(game, index) ?: return system.cost
        return system.cost + (drone.cost ?: 0) / 2
    }

    /** The drone given with the drone control in this box, if it is one. */
    fun freeDrone(game: InGameState, index: Int): DroneBlueprint? {
        val system = systems.getOrNull(index) ?: return null
        if (system.info != Drones.INFO)
            return null
        val name = DRONE_CONTROL_DRONES.getOrNull(systemVariants.getOrNull(index) ?: -1) ?: return null
        return game.blueprintManager.getOrNull(name) as? DroneBlueprint
    }

    fun saveToXML(elem: Element) {
        for (section in sections) {
            val sectionElem = Element("section")
            SaveUtil.addAttr(sectionElem, "id", section.name)
            elem.addContent(sectionElem)
        }

        for ((name, count) in availableResources.entries) {
            elem.setAttribute(name.name, count.toString())
        }

        saveBlueprints(elem, "drone", drones)
        saveBlueprints(elem, "weapon", weapons)
        saveBlueprints(elem, "system", systems)
        // The drone control variant of each system box (optional: older saves have none).
        val systemElems = elem.getChildren("system")
        for (systemElem in systemElems) {
            val idx = SaveUtil.getAttrInt(systemElem, "idx")
            val variant = systemVariants.getOrNull(idx) ?: continue
            if (variant >= 0)
                SaveUtil.addAttrInt(systemElem, "variant", variant)
        }
        saveBlueprints(elem, "augment", augments)

        for ((i, crew) in crew.withIndex()) {
            if (crew == null)
                continue

            val crewElem = Element("crew")
            SaveUtil.addAttrInt(crewElem, "idx", i)
            crew.saveToXML(crewElem)
            elem.addContent(crewElem)
        }
    }

    fun loadFromXML(game: InGameState, elem: Element) {
        for (sectionElem in elem.getChildren("section")) {
            val sectionName = SaveUtil.getAttr(sectionElem, "id")
            sections += Section.valueOf(sectionName)
        }

        for (resource in Resource.entries) {
            val value = elem.getAttributeValue(resource.name)?.toInt() ?: continue
            availableResources[resource] = value
        }

        loadBlueprints(game, elem, "drone", drones, DroneBlueprint::class.java)
        loadBlueprints(game, elem, "weapon", weapons, AbstractWeaponBlueprint::class.java)
        loadBlueprints(game, elem, "system", systems, SystemBlueprint::class.java)
        for (systemElem in elem.getChildren("system")) {
            val idx = SaveUtil.getAttrInt(systemElem, "idx")
            val variant = systemElem.getAttributeValue("variant")?.toIntOrNull() ?: continue
            while (systemVariants.size <= idx)
                systemVariants.add(-1)
            systemVariants[idx] = variant
        }
        loadBlueprints(game, elem, "augment", augments, AugmentBlueprint::class.java)

        for (crewElem in elem.getChildren("crew")) {
            val idx = SaveUtil.getAttrInt(crewElem, "idx")
            while (crew.size <= idx) {
                crew.add(null)
            }

            crew[idx] = LivingCrewInfo.loadFromXML(crewElem, game)
        }
    }

    private fun saveBlueprints(elem: Element, name: String, items: List<Blueprint?>) {
        for ((i, item) in items.withIndex()) {
            if (item == null)
                continue

            val itemElem = Element(name)
            SaveUtil.addAttrInt(itemElem, "idx", i)
            SaveUtil.addAttr(itemElem, "type", item.name)
            elem.addContent(itemElem)
        }
    }

    private fun <T> loadBlueprints(
        game: InGameState,
        elem: Element, name: String,
        items: MutableList<T?>,
        type: Class<T>
    ) {
        for (itemElem in elem.getChildren(name)) {
            val idx = SaveUtil.getAttrInt(itemElem, "idx")
            val itemName = SaveUtil.getAttr(itemElem, "type")

            val item = game.blueprintManager[itemName]
            require(type.isInstance(item))
            @Suppress("UNCHECKED_CAST")
            val casted: T = run { item as T } // Run block needed to suppress the warning

            // Grow the items list until it can fit the item in the correct index
            while (items.size <= idx) {
                items.add(null)
            }

            items[idx] = casted
        }
    }

    companion object {
        // The section ids of Store::OnInit (0x4c3b20).
        private const val SECTION_WEAPONS = 0
        private const val SECTION_DRONES = 1
        private const val SECTION_AUGMENTS = 2
        private const val SECTION_CREW = 3
        private const val SECTION_SYSTEMS = 4
        private const val UNUSED_SLOT = 7

        private val SECTION_BY_ID = mapOf(
            SECTION_WEAPONS to Section.WEAPONS,
            SECTION_DRONES to Section.DRONES,
            SECTION_AUGMENTS to Section.AUGMENTS,
            SECTION_CREW to Section.CREW,
            SECTION_SYSTEMS to Section.SYSTEMS
        )

        /** The system ids of 0x63dde0 and their blueprint names. */
        val SYSTEM_NAMES = listOf(
            "shields", "engines", "oxygen", "weapons", "drones", "medbay", "pilot", "sensors",
            "doors", "teleporter", "cloaking", "artillery", "battery", "clonebay", "mind", "hacking"
        )

        /** The price of hull repair: the store's world level picks it (Store 0x4c2710). */
        fun repairPrice(level: Int): Int = when {
            level < 3 -> 2
            level < 6 -> 3
            else -> 4
        }

        /** The drones drone control comes with, by the box's variant (0x51ce20). */
        val DRONE_CONTROL_DRONES = listOf("DEFENSE_1", "REPAIR", "COMBAT_1")
    }

    enum class Section {
        AUGMENTS,
        CREW,
        DRONES,
        SYSTEMS,
        WEAPONS;

        val localisationKey get() = "store_title_" + name.toLowerCase(Locale.UK)
    }
}
