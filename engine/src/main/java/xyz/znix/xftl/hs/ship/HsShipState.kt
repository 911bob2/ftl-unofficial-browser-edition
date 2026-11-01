package xyz.znix.xftl.hs.ship

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.hs.HS

/**
 * Hyperspace per-ship state (`ShipManager_Extend` and the ship's part of `ShipInfo::augList`): created for every ship
 * while [HS.active] ([Ship.hs]); null in vanilla play.
 *
 * Saved inside the ship's own element as `<hsShip>` (only modded saves have it, so vanilla saves are unchanged): the
 * hidden augments are run state (events and Lua add and remove them), everything else comes from the definition.
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomShips.cpp (ShipManager_Extend::Initialize, ResetScrapLevel,
// IsCrewFull, IsCrewOverFull, CanFitCrew) and src/game/Ship/CustomAugments.cpp (the "HIDDEN " entries of augList).
class HsShipState(@JvmField val ship: Ship) {
    /** `isNewShip` = IsCustomShip(blueprint name). */
    @JvmField var isNewShip = false

    /** `augList["HIDDEN " + name]`: hidden augment counts by name (std::map order). */
    @JvmField val hiddenAugs = java.util.TreeMap<String, Int>()

    /** Room definitions of the ship's custom definition by room id (`Room_Extend` fields; systems/render use them). */
    @JvmField val roomDefs = HashMap<Int, RoomDefinition>()

    /** Ship icon names (`ShipManager_Extend::icons`, drawn by the render workstream). */
    @JvmField val shipIcons = ArrayList<String>()

    /** Extra cargo slots and the over-capacity list (CustomEquipment). */
    @JvmField val equipment = HsEquipment.State()

    /** [HsAugments.augListWithHidden] until the augments change. */
    @JvmField var augListCache: Map<String, Int>? = null

    /** Lua-only per-ship values (flags and fields HS exposes that the engine does not model). */
    @JvmField var lua: xyz.znix.xftl.hs.lua.HsShipLuaState? = null

    /** Add hidden augments (`augList["HIDDEN " + name] += count`), as events and Lua do. */
    fun addHidden(name: String, count: Int = 1) {
        hiddenAugs[name] = (hiddenAugs[name] ?: 0) + count
        ship.cargoUpdated()
    }

    /** Remove one hidden copy (`RemoveAugmentation("HIDDEN " + name)`); false if there was none. */
    fun removeHidden(name: String): Boolean {
        val n = hiddenAugs[name] ?: return false
        if (n <= 1) hiddenAugs.remove(name) else hiddenAugs[name] = n - 1
        ship.cargoUpdated()
        return true
    }

    val definition: CustomShipDefinition get() = HsShips.def(ship.type.name)

    fun save(parent: Element) {
        val e = Element(ELEMENT)
        for ((name, count) in hiddenAugs) e.addContent(Element("hiddenAug").setAttribute("name", name).setAttribute("count", count.toString()))
        HsEquipment.save(ship, e)
        parent.addContent(e)
    }

    companion object {
        const val ELEMENT = "hsShip"

        /** Default bars per crew member when no race defines `CREW_SLOTS` (statcrew may replace [crewCapacityUsed]). */
        @JvmField
        var crewCapacityUsed: ((Ship) -> Float)? = null

        /**
         * The crew slots a race takes (StatBoostManager::CalculateStatDummy(CREW_SLOTS) of its CrewDefinition), or null for a
         * race without a definition (one slot); [crewNoSlot] races always fit. Both are statcrew's to set.
         */
        @JvmField
        var crewSlots: ((String) -> Float?)? = null

        @JvmField
        var crewNoSlot: ((String) -> Boolean)? = null

        /**
         * `ShipManager_Extend::Initialize` after ShipManager::OnInit (the end of Ship.loadDefaultContents): hidden
         * augments, room definitions, ship icons, and automation. The HS crew list and the starting scrap/fuel are
         * applied by the run start ([drawStartingCrew], [placeStartingCrew], [applyStartingResources]), which owns the crew.
         */
        @JvmStatic
        fun initialize(ship: Ship) {
            ensureAugments(ship)
            val st = HsShipState(ship)
            ship.hs = st
            st.isNewShip = HsShips.isCustomShip(ship.type.name)
            val def = HsShips.def(ship.type.name)
            for ((name, count) in def.hiddenAugs) st.hiddenAugs[name] = count
            for ((id, room) in def.roomDefs) if (id < ship.rooms.size) st.roomDefs[id] = room
            xyz.znix.xftl.hs.render.HsRoomAnims.create(ship, st.roomDefs, false) // RoomAnim(def, room): render workstream
            st.shipIcons.addAll(def.shipIcons)
            def.forceAutomated?.let { ship.isAutomated = it }
            HsAugments.updateAugments(ship)
        }

        private fun ensureAugments(ship: Ship) = HsAugments.ensureLoaded { name ->
            (ship.sys.blueprintManager.getOrNull(name) as? xyz.znix.xftl.augments.AugmentBlueprint)?.value ?: 0f
        }

        /** A loaded ship (no OnInit): the saved hidden augments, the rest from the definition. */
        @JvmStatic
        fun load(ship: Ship, rootElem: Element) {
            ensureAugments(ship)
            val st = HsShipState(ship)
            ship.hs = st
            st.isNewShip = HsShips.isCustomShip(ship.type.name)
            val def = HsShips.def(ship.type.name)
            val saved = rootElem.getChild(ELEMENT)
            if (saved == null) {
                for ((name, count) in def.hiddenAugs) st.hiddenAugs[name] = count
            } else {
                for (a in saved.getChildren("hiddenAug")) {
                    val n = a.getAttributeValue("name") ?: continue
                    st.hiddenAugs[n] = a.getAttributeValue("count")?.toIntOrNull() ?: 1
                }
            }
            for ((id, room) in def.roomDefs) if (id < ship.rooms.size) st.roomDefs[id] = room
            xyz.znix.xftl.hs.render.HsRoomAnims.create(ship, st.roomDefs, true)
            st.shipIcons.addAll(def.shipIcons)
        }

        /**
         * The HS `<crew>` list of the ship's definition, after the blueprint's crew (Initialize when not revisiting):
         * `species` (a blueprint list when `list="true"`: `rand() % size`, "human" for an empty list), then
         * AddCrewMemberFromString(name, species, false, room, false, random32() % 2), which resolves any further list
         * level with `random32() % size` (CustomCrew.cpp hook). `rand()` and `random32()` both draw from
         * [NativeRandom] here (see the ships report: HS 1.6.9's CRT `rand()` stream is not modelled).
         * [drawStartingCrew] makes the draws (in the original they come right after OnInit's equipment draws, before
         * the blueprint's crew); a race the engine does not know is recorded and skipped.
         */
        @JvmStatic
        fun drawStartingCrew(shipName: String, game: xyz.znix.xftl.game.InGameState): List<HsStartingCrewMember> {
            val def = HsShips.def(shipName)
            val added = ArrayList<HsStartingCrewMember>()
            if (def.crewList.isEmpty()) return added
            val manager = game.blueprintManager
            for (p in def.crewList) {
                var species = p.species
                if (p.isList) {
                    val list = (manager.getOrNull(p.species) as? BlueprintList)?.names ?: emptyList()
                    species = if (list.isNotEmpty()) list[NativeRandom.nextInt(list.size)] else "human"
                }
                val male = NativeRandom.next31() % 2 == 1
                // ShipManager::AddCrewMemberFromString (CustomCrew.cpp) -> GetCrewBlueprint: a list gives one of its
                // races, an unknown race the human blueprint (statcrew's HsCrewContent).
                val race = xyz.znix.xftl.hs.crew.HsCrewContent.crewBlueprint(manager, species, NativeRandom)
                val factory = game.crewFactory
                val colours = factory.drawColours(race)
                val name = if (p.name.isNotEmpty()) p.name else factory.newName().name
                added += HsStartingCrewMember(LivingCrewInfo(game, race, name, factory.colourIndex(race, colours, male)), p.roomId)
            }
            return added
        }

        /**
         * ShipManager 0x4ae2f0 (the blueprint crew loop): member i of the player's crew goes to the room of the i-th
         * system of PLAYER_STATIONS the ship starts with (pilot, engines, weapons, shields, oxygen, sensors, doors,
         * cloaking, medbay, drones, hacking, teleporter, the first artillery); a member beyond that list draws
         * `random32() % room count`. The engine's vanilla placement only knows the first four stations, which is
         * all a vanilla ship needs; MV ships with a missing station or more crew need the rest (oracle: CLOAK_2's
         * fourth crew member in the oxygen room). The random draws are made here, after the crew's own draws.
         */
        @JvmStatic
        fun startingCrewRooms(bp: xyz.znix.xftl.game.ShipBlueprint, members: Int): List<Int> {
            val present = ArrayList<Int>()
            for (id in PLAYER_STATIONS) {
                val name = STATION_NAMES[id]
                val sys = bp.systems.firstOrNull { it.systemName == name && it.availableByDefault } ?: continue
                present += sys.room.id
            }
            return List(members) { i -> if (i < present.size) present[i] else NativeRandom.nextInt(bp.rooms.size) }
        }

        private val PLAYER_STATIONS = intArrayOf(6, 1, 3, 0, 2, 7, 8, 10, 5, 4, 15, 9, 11)
        private val STATION_NAMES = arrayOf("shields", "engines", "oxygen", "weapons", "drones", "medbay", "pilot",
            "sensors", "doors", "teleporter", "cloaking", "artillery", "battery", "clonebay", "mind", "hacking")

        /** Put the drawn HS crew aboard (after the blueprint's crew); any crew clears the automation flag. */
        @JvmStatic
        fun placeStartingCrew(ship: Ship, crew: List<HsStartingCrewMember>) {
            for (c in crew) {
                val room = ship.rooms.getOrNull(c.roomId) ?: ship.rooms[0]
                ship.addCrewMemberInRoom(c.info, room, false)
                ship.isAutomated = false
            }
            HsShips.def(ship.type.name).forceAutomated?.let { ship.isAutomated = it }
        }

        /** Initialize(restarting=true) / ResetScrapLevel: `startingScrap` and `startingFuel` replace the defaults. */
        @JvmStatic
        fun applyStartingResources(ship: Ship) {
            val def = HsShips.def(ship.type.name)
            if (def.startingScrap != -1) ship.scrap = def.startingScrap
            if (def.startingFuel != -1) ship.fuelCount = def.startingFuel
        }

        /** `crewLimit` of the player's definition (IsCrewFull/IsCrewOverFull; the enemy has no limit). */
        @JvmStatic
        fun crewLimit(ship: Ship): Int = HsShips.def(ship.type.name).crewLimit

        /** `CrewMemberFactory::GetCrewCapacityUsed`: one slot per crew member unless statcrew provides race slots. */
        @JvmStatic
        fun capacityUsed(ship: Ship, playerCrew: Int): Float = crewCapacityUsed?.invoke(ship) ?: playerCrew.toFloat()

        /** IsCrewFull: `capacity + 0.001 >= crewLimit` (store crew boxes use CanFitCrew instead). */
        @JvmStatic
        fun isCrewFull(ship: Ship, playerCrew: Int): Boolean = capacityUsed(ship, playerCrew) + 0.001f >= crewLimit(ship)

        /** IsCrewOverFull: `crewLimit < capacity`. */
        @JvmStatic
        fun isCrewOverFull(ship: Ship, playerCrew: Int): Boolean = crewLimit(ship) < capacityUsed(ship, playerCrew)

        /** ShipManager::CanFitCrew: what a store crew box (CrewStoreBox::CanHold hook) checks: `used + slots <= crewLimit`. */
        @JvmStatic
        fun canFitCrew(ship: Ship, playerCrew: Int, race: String): Boolean {
            if (crewNoSlot?.invoke(race) == true) return true
            val used = capacityUsed(ship, playerCrew) + (crewSlots?.invoke(race) ?: 1f)
            return used <= crewLimit(ship)
        }

        /** CommandGui::OnLoop hook: the player cannot jump away from an enemy whose definition has `<noJump>`. */
        @JvmStatic
        fun enemyNoJump(game: xyz.znix.xftl.game.InGameState, ship: Ship): Boolean {
            if (!xyz.znix.xftl.hs.HS.active) return false
            val enemy = game.getEnemyOf(ship) ?: return false
            return HsShips.def(enemy.type.name).noJump
        }
    }
}

/** One HS starting crew member drawn before the ship exists, placed by [HsShipState.placeStartingCrew]. */
class HsStartingCrewMember(@JvmField val info: LivingCrewInfo, @JvmField val roomId: Int)
