// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i CustomShipSelect, ShipSelect,
// ShipButtonDefinition, ShipButtonList, CustomShipDefinition, CrewPlacementDefinition, CustomShipUnlocks,
// CustomAchievementTracker, Equipment, ShipBlueprint, SettingValues; src/game/UserInterface/CustomShipSelect.h and
// CustomEquipment.cpp for what the members do), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.systemId
import xyz.znix.xftl.hs.ship.CrewPlacement
import xyz.znix.xftl.hs.ship.CustomShipDefinition
import xyz.znix.xftl.hs.ship.HsAchievements
import xyz.znix.xftl.hs.ship.HsEquipment
import xyz.znix.xftl.hs.ship.HsHangar
import xyz.znix.xftl.hs.ship.HsShipUnlocks
import xyz.znix.xftl.hs.ship.HsShips
import xyz.znix.xftl.hs.ship.ShipButtonDefinition
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** `CustomShipUnlocks::instance`, `CustomAchievementTracker::instance`, the player's `Equipment` and the hangar's `ShipSelect`. */
object LuaCustomShipUnlocks : LuaTyped { override val luaType get() = A.T_CustomShipUnlocks }
object LuaAchievementTracker : LuaTyped { override val luaType get() = A.T_CustomAchievementTracker }
object LuaEquipment : LuaTyped { override val luaType get() = A.T_Equipment }
object LuaShipSelect : LuaTyped { override val luaType get() = A.T_ShipSelect }

/** `ShipButtonDefinition` (index = id - 100 in `shipButtonDefs`) and the `ShipButtonList` of one of them. */
class LuaShipButtonDefinition(@JvmField val def: ShipButtonDefinition) : LuaTyped { override val luaType get() = A.T_ShipButtonDefinition }
class LuaShipButtonList(@JvmField val index: Int) : LuaTyped { override val luaType get() = A.T_ShipButtonList }

/**
 * WP2 adapters (hangar and ship select, ship unlocks and achievements, the equipment screen, ship blueprints and the
 * settings). Members follow hyperspace.i; the hangar model is the engine's ([HsHangar]), so a list or button the engine
 * has no object for (ShipButton) is not available.
 */
object LuaAdaptersWP2 {
    private val vanillaShipNames = listOf(
        "PLAYER_SHIP_HARD", "PLAYER_SHIP_STEALTH", "PLAYER_SHIP_MANTIS", "PLAYER_SHIP_CIRCLE", "PLAYER_SHIP_FED",
        "PLAYER_SHIP_JELLY", "PLAYER_SHIP_ROCK", "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_CRYSTAL", "PLAYER_SHIP_ANAEROBIC",
    )

    /** `CustomShipSelect::shipPage`: the hangar's page; outside the hangar the value it last had (hideFirstPage starts it at 1). */
    private var lastShipPage = -1
    private fun shipPage(): Int {
        val s = HsLua.game?.currentState as? xyz.znix.xftl.hangar.SelectShipState
        if (s != null) lastShipPage = s.shipListPage
        return if (lastShipPage >= 0) lastShipPage else if (HsShips.hideFirstPage) 1 else 0
    }

    private fun d(b: Boolean) = if (b) 1.0 else 0.0
    private fun buttons() = HsShips.buttons

    /** `GetShipButtonIdFromName`: the index of the entry (a `_2`/`_3` layout name drops its suffix), -1 when unknown or "empty". */
    private fun buttonIndex(name: String): Int {
        if (name == "empty") return -1
        val base = if (name.endsWith("_2") || name.endsWith("_3")) name.substring(0, name.length - 2) else name
        return buttons().indexOfFirst { it.name == base }
    }

    private fun variantOfName(name: String): Int = when { name.endsWith("_2") -> 1; name.endsWith("_3") -> 2; else -> 0 }

    @JvmStatic
    fun install() {
        select()
        unlocks()
        achievements()
        definition()
        equipment()
        blueprint()
        settings()
    }

    // ---------------------------------------------------------------- CustomShipSelect and friends

    private fun select() {
        val order = { ArrayList<Any?>(HsHangar.order()) }
        LuaReg.obj(A.A_CustomShipSelect_customShipOrder, ObjGet { LuaViews.cached(it!!, A.A_CustomShipSelect_customShipOrder) { _ -> ListView(A.T_vector_string, 0) { order() } } })
        LuaReg.obj(A.A_CustomShipSelect_shipSelect, ObjGet { LuaShipSelect })
        // ShipSelect::selectedShip / currentType: the engine's hangar keeps no hovered-card index; -1 and the layout type.
        LuaReg.num(A.A_ShipSelect_selectedShip, NumGet { -1.0 })
        LuaReg.num(A.A_ShipSelect_currentType, NumGet {
            val s = HsLua.game?.currentState as? xyz.znix.xftl.hangar.SelectShipState
            (s?.let { HsHangar.variantOf(it.selectedShipId)?.second } ?: 0).toDouble()
        })

        LuaReg.method(A.M_CustomShipSelect_GetShipBlueprint, LuaMethod {
            val id = arg.int(2)
            ret.str(if (id >= 100) buttons().getOrNull(id - 100)?.name ?: "" else vanillaShipNames.getOrNull(id) ?: "")
        })
        // CountUnlockedShips(variant): the unlocked, existing layouts of the ordered entries (-1 = all three variants)
        LuaReg.method(A.M_CustomShipSelect_CountUnlockedShips, LuaMethod {
            val variant = if (arg.isNil(2)) -1 else arg.int(2)
            var n = 0
            for (name in HsHangar.order()) {
                val def = buttons().firstOrNull { it.name == name } ?: continue
                for (v in (if (variant != -1) listOf(variant) else listOf(0, 1, 2))) if (HsShipUnlocks.isUnlocked(def.name, v) && def.variantExists(v)) n++
            }
            ret.num(n)
        })
        // The hangar's own page and selection (the engine's ship list; page 0 is the vanilla page)
        LuaReg.method(A.M_CustomShipSelect_GetCurrentPage, LuaMethod { ret.num(shipPage()) })
        LuaReg.method(A.M_CustomShipSelect_FirstPage, LuaMethod { ret.bool(shipPage() == 0) })
        LuaReg.method(A.M_CustomShipSelect_GetSelection, LuaMethod { ret.num(-1) })
        LuaReg.method(A.M_CustomShipSelect_GetSelectedId, LuaMethod { ret.num(-1) })
        LuaReg.method(A.M_CustomShipSelect_GetLastSelected, LuaMethod { ret.num(-1) })
        LuaReg.method(A.M_CustomShipSelect_ClearSelection, LuaMethod { 0 })

        LuaReg.method(A.M_CustomShipSelect_GetShipButtonIdFromName, LuaMethod { ret.num(buttonIndex(arg.str(2) ?: "")) })
        LuaReg.method(A.M_CustomShipSelect_GetShipIdAndVariantFromName, LuaMethod {
            val name = arg.str(2) ?: ""
            val id = buttonIndex(name)
            val variant = if (name == "empty") 0 else variantOfName(name)
            ret.obj(IntPairView(A.T_pair_int_int, { id }, null, { variant }, null), A.T_pair_int_int)
        })
        LuaReg.method(A.M_CustomShipSelect_GetShipButtonDefinition, LuaMethod {
            val def = buttons().getOrNull(arg.int(2))
            ret.obj(def?.let { d -> LuaViews.cached(d, A.T_ShipButtonDefinition) { LuaShipButtonDefinition(d) } }, A.T_ShipButtonDefinition)
        })
        LuaReg.method(A.M_CustomShipSelect_GetOrderedShipButtonDefinition, LuaMethod {
            val i = arg.int(2)
            val name = HsHangar.order().getOrNull(i)
            val def = name?.let { n -> buttons().firstOrNull { it.name == n } }
            ret.obj(def?.let { d -> LuaViews.cached(d, A.T_ShipButtonDefinition) { LuaShipButtonDefinition(d) } }, A.T_ShipButtonDefinition)
        })
        LuaReg.method(A.M_CustomShipSelect_GetShipButtonOrderIndex, LuaMethod {
            val def = buttons().getOrNull(arg.int(2))
            ret.num(if (def == null) -1 else HsHangar.order().indexOf(def.name))
        })
        // GetRandomShipIndex: GetShipButtonOrderIndex(random32() % shipButtonDefs.size())
        LuaReg.method(A.M_CustomShipSelect_GetRandomShipIndex, LuaMethod {
            val n = buttons().size
            val pick = if (n == 0) 0 else LuaRng.rand32() % n
            val def = buttons().getOrNull(pick)
            ret.num(if (def == null) -1 else HsHangar.order().indexOf(def.name))
        })
        // ShipCount(type = 0): the entries that have that layout
        val shipCount = LuaMethod {
            val type = if (arg.isNil(2)) 0 else arg.int(2)
            ret.num(buttons().count { it.variantExists(type) && type in 0..2 })
        }
        LuaReg.method(A.M_CustomShipSelect_ShipCount__SWIG_0, shipCount)
        LuaReg.method(A.M_CustomShipSelect_ShipCount__SWIG_1, shipCount)
        // ShipButtonList: one per entry (page = HS page of the entry, id = 100 + index)
        LuaReg.method(A.M_CustomShipSelect_GetShipButtonLists, LuaMethod {
            ret.obj(ListView(A.T_vector_p_ShipButtonList, A.T_ShipButtonList) { buttons().indices.map { i -> LuaViews.cached(buttons()[i], A.T_ShipButtonList) { LuaShipButtonList(i) } } }, A.T_vector_p_ShipButtonList)
        })
        LuaReg.method(A.M_CustomShipSelect_GetShipButtonListFromID, LuaMethod {
            val i = arg.int(2) - 100
            ret.obj(buttons().getOrNull(i)?.let { b -> LuaViews.cached(b, A.T_ShipButtonList) { LuaShipButtonList(i) } }, A.T_ShipButtonList)
        })
        fun list(): Int = (arg.obj(1) as LuaShipButtonList).index
        LuaReg.method(A.M_ShipButtonList_GetId, LuaMethod { ret.num(list() + 100) })
        LuaReg.method(A.M_ShipButtonList_GetIndex, LuaMethod { ret.num(list()) })
        LuaReg.method(A.M_ShipButtonList_GetPage, LuaMethod { ret.num(HsHangar.pageOf(buttons()[list()].name) - 1) })
        LuaReg.str(A.A_ShipButtonDefinition_name, StrGet { (it as LuaShipButtonDefinition).def.name })
    }

    // ---------------------------------------------------------------- unlocks and achievements

    private fun unlocks() {
        LuaReg.obj(A.A_module_CustomShipUnlocks_instance, ObjGet { LuaCustomShipUnlocks })
        LuaReg.obj(A.A_CustomShipUnlocks_instance, ObjGet { LuaCustomShipUnlocks })
        val get = LuaMethod {
            val variant = if (arg.isNil(3)) 0 else arg.int(3)
            ret.bool(HsShipUnlocks.isUnlocked(arg.str(2) ?: "", variant))
        }
        LuaReg.method(A.M_CustomShipUnlocks_GetCustomShipUnlocked__SWIG_0, get)
        LuaReg.method(A.M_CustomShipUnlocks_GetCustomShipUnlocked__SWIG_1, get)
        // UnlockShip(ship, silent, checkMultiUnlocks = true, isEvent = false)
        val unlock = LuaMethod {
            val checkMulti = if (arg.isNil(4)) true else arg.bool(4)
            val isEvent = if (arg.isNil(5)) false else arg.bool(5)
            HsShipUnlocks.unlockShip(arg.str(2) ?: "", arg.bool(3), checkMulti, isEvent)
            xyz.znix.xftl.hs.ship.HsProfileHooks.flushLua()
            0
        }
        LuaReg.method(A.M_CustomShipUnlocks_UnlockShip__SWIG_0, unlock)
        LuaReg.method(A.M_CustomShipUnlocks_UnlockShip__SWIG_1, unlock)
        LuaReg.method(A.M_CustomShipUnlocks_UnlockShip__SWIG_2, unlock)
    }

    private fun achievements() {
        LuaReg.obj(A.A_module_CustomAchievementTracker_instance, ObjGet { LuaAchievementTracker })
        LuaReg.obj(A.A_CustomAchievementTracker_instance, ObjGet { LuaAchievementTracker })
        LuaReg.method(A.M_CustomAchievementTracker_GetAchievementStatus, LuaMethod { ret.num(HsAchievements.getAchievementStatus(arg.str(2) ?: "")) })
        val update = LuaMethod {
            val inGame = if (arg.isNil(4)) true else arg.bool(4)
            HsAchievements.updateVariableAchievements(arg.str(2) ?: "", arg.int(3), inGame)
            0
        }
        LuaReg.method(A.M_CustomAchievementTracker_UpdateVariableAchievements__SWIG_0, update)
        LuaReg.method(A.M_CustomAchievementTracker_UpdateVariableAchievements__SWIG_1, update)
        // SetAchievement(name, noPopup, newDiff = the run's difficulty): the HS overload without it takes three arguments here
        LuaReg.method(A.M_CustomAchievementTracker_SetAchievement, LuaMethod {
            if (arg.isNil(4)) HsAchievements.setAchievement(arg.str(2) ?: "", arg.bool(3))
            else HsAchievements.setAchievement(arg.str(2) ?: "", arg.bool(3), arg.int(4))
            0
        })
    }

    // ---------------------------------------------------------------- CustomShipDefinition containers

    class StringMapView(val map: Map<String, Int>) : LuaTyped { override val luaType get() = A.T_map_string_int }

    private fun definition() {
        fun def(o: Any?) = o as CustomShipDefinition
        LuaReg.obj(A.A_CustomShipDefinition_shipIcons, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomShipDefinition_shipIcons) { x -> ListView(A.T_vector_string, 0) { def(x).shipIcons } }
        })
        LuaReg.obj(A.A_CustomShipDefinition_reactorPrices, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomShipDefinition_reactorPrices) { x -> ListView(A.T_vector_int, 0) { def(x).reactorPrices } }
        })
        LuaReg.obj(A.A_CustomShipDefinition_crewList, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomShipDefinition_crewList) { x -> ListView(A.T_vector_CrewPlacementDefinition, A.T_CrewPlacementDefinition) { def(x).crewList } }
        })
        LuaReg.obj(A.A_CustomShipDefinition_hiddenAugs, ObjGet { o ->
            LuaViews.cached(o!!, A.A_CustomShipDefinition_hiddenAugs) { x -> StringMapView(def(x).hiddenAugs) }
        })
        // ToggleValue<bool> forceAutomated: a snapshot (value, enabled)
        LuaReg.obj(A.A_CustomShipDefinition_forceAutomated, ObjGet { o ->
            xyz.znix.xftl.hs.crew.Toggle(def(o).forceAutomated ?: false).also { it.enabled = def(o).forceAutomated != null }
        })
        fun m() = (arg.obj(1) as StringMapView).map
        LuaReg.method(A.M_map_string_int_has_key, LuaMethod { ret.bool(m().containsKey(arg.str(2) ?: "")) })
        val getItem = LuaMethod { val v = m()[arg.str(2) ?: ""] ?: throw LuaError("SWIG_IndexError:key not found"); ret.num(v) }
        LuaReg.method(A.M_map_string_int___getitem, getItem)
        LuaReg.method(A.M_map_string_int_get, getItem)
        LuaReg.method(A.M_map_string_int_size, LuaMethod { ret.num(m().size) })
        LuaReg.method(A.M_map_string_int_empty, LuaMethod { ret.bool(m().isEmpty()) })

        // CrewPlacementDefinition (CustomShipDefinition::crewList entries)
        fun c(o: Any?) = o as CrewPlacement
        LuaReg.str(A.A_CrewPlacementDefinition_species, StrGet { c(it).species })
        LuaReg.str(A.A_CrewPlacementDefinition_name, StrGet { c(it).name })
        LuaReg.num(A.A_CrewPlacementDefinition_roomId, NumGet { c(it).roomId.toDouble() })
        LuaReg.bool(A.A_CrewPlacementDefinition_isList, { c(it).isList })
    }

    // ---------------------------------------------------------------- Equipment

    private fun playerShip(): Ship? = LuaAdapters.inGame()?.player

    /** Equipment::AddWeapon/AddDrone/AddAugment/AddToCargo: an item with no room goes to the over-capacity box and Equipment::Open shows it (oracle sw2-ship u0). */
    private fun overflowOpen() { LuaAdapters.inGame()?.shipUI?.hsOpenEquipmentForOverflow() }

    private fun equipment() {
        LuaReg.obj(A.A_CommandGui_equipScreen, ObjGet { LuaEquipment })
        // Equipment::AddWeapon / AddDrone / AddAugment(blueprint, free, forceCargo): the player's equipment screen
        val add = LuaMethod {
            val bp = arg.obj(2) as? Blueprint
            val ship = playerShip()
            if (bp != null && ship != null) ship.addBlueprint(bp, true, !arg.isNil(4) && arg.bool(4) && bp !is xyz.znix.xftl.augments.AugmentBlueprint)
            overflowOpen()
            0
        }
        LuaReg.method(A.M_Equipment_AddWeapon, add)
        LuaReg.method(A.M_Equipment_AddDrone, add)
        LuaReg.method(A.M_Equipment_AddAugment, add)
        // AddToCargo(name): a weapon, drone or augment by name goes to the cargo (a weapon never to its own slot)
        LuaReg.method(A.M_Equipment_AddToCargo, LuaMethod {
            val ship = playerShip()
            val bp = LuaAdapters.inGame()?.blueprintManager?.getOrNull(arg.str(2) ?: "") as? Blueprint
            if (ship != null && bp != null && (bp is xyz.znix.xftl.weapons.AbstractWeaponBlueprint || bp is xyz.znix.xftl.weapons.DroneBlueprint ||
                    bp is xyz.znix.xftl.augments.AugmentBlueprint)) ship.addBlueprint(bp, true, true)
            overflowOpen()
            0
        })
        LuaReg.method(A.M_Equipment_GetCargoHold, LuaMethod {
            val names = ArrayList<Any?>(playerShip()?.allCargo()?.map { it.name } ?: emptyList())
            ret.obj(ListView(A.T_vector_string, 0) { names }, A.T_vector_string)
        })
    }

    // ---------------------------------------------------------------- ShipBlueprint

    private fun blueprint() {
        fun b(o: Any?) = o as ShipBlueprint
        // BlueprintManager::GetShipBlueprint(name, sector): the named ship blueprint (a blueprint list is not resolved by
        // sector here; an unknown name returns nil where FTL returns an empty blueprint)
        LuaReg.method(A.M_BlueprintManager_GetShipBlueprint, LuaMethod {
            val bm = HsLua.game?.gameContent?.blueprintManager
            val bp = bm?.getOrNull(arg.str(2) ?: "") as? xyz.znix.xftl.LazyShipBlueprint
            ret.obj(bp?.real, A.T_ShipBlueprint)
        })
        fun text(o: Any?, key: Int, read: (ShipBlueprint) -> xyz.znix.xftl.GameText?): Any =
            LuaViews.cached(o!!, key) { x -> LuaTextRef({ read(b(x)) }, { HS.unsupported("lua", "write", "ShipBlueprint text") }) }
        LuaReg.obj(A.A_ShipBlueprint_desc, ObjGet { o -> LuaViews.cached(o!!, A.A_ShipBlueprint_desc) { x -> LuaDescription(x as Blueprint) } })
        LuaReg.obj(A.A_ShipBlueprint_name, ObjGet { o -> text(o, A.A_ShipBlueprint_name) { xyz.znix.xftl.hs.render.HsSwitchShip.ownName(it) ?: it.shipTitle } })
        LuaReg.obj(A.A_ShipBlueprint_shipClass, ObjGet { o -> text(o, A.A_ShipBlueprint_shipClass) { it.shipClass } })
        LuaReg.obj(A.A_ShipBlueprint_unlock, ObjGet { o -> text(o, A.A_ShipBlueprint_unlock) { it.unlockHint } })
        fun child(o: Any?, tag: String) = b(o).sourceElement.getChildTextTrim(tag) ?: ""
        // cloakFile / floorFile default to the ship's image name (as the cloak and floor image lookups do); the oracle
        // reports mup_mvkestrel_a for both of MV's Kestrel, whose blueprint has neither tag
        LuaReg.str(A.A_ShipBlueprint_cloakFile, StrGet { child(it, "cloakImage").ifEmpty { b(it).img } })
        LuaReg.str(A.A_ShipBlueprint_shieldFile, StrGet { child(it, "shieldImage").ifEmpty { b(it).img } })
        LuaReg.str(A.A_ShipBlueprint_floorFile, StrGet { child(it, "floorImage").ifEmpty { b(it).img } })
        LuaReg.num(A.A_ShipBlueprint_health, NumGet { b(it).maxHealth.toDouble() })
        LuaReg.num(A.A_ShipBlueprint_maxPower, NumGet { b(it).startingReactorPower.toDouble() })
        LuaReg.num(A.A_ShipBlueprint_missiles, NumGet { b(it).initialMissiles.toDouble() })
        LuaReg.num(A.A_ShipBlueprint_drone_count, NumGet { b(it).initialDroneParts.toDouble() })
        // Defaults from the oracle (BOSS_3 has neither tag): 4 weapon and 4 drone slots, sectors 0 .. INT_MAX
        LuaReg.num(A.A_ShipBlueprint_weaponSlots, NumGet { (b(it).weaponSlots ?: 4).toDouble() })
        LuaReg.num(A.A_ShipBlueprint_droneSlots, NumGet { (b(it).droneSlots ?: 4).toDouble() })
        fun listCount(o: Any?, tag: String, fallback: Int) =
            (b(o).sourceElement.getChild(tag)?.getAttributeValue("count")?.trim()?.toIntOrNull() ?: fallback).toDouble()
        LuaReg.num(A.A_ShipBlueprint_weaponCount, NumGet { listCount(it, "weaponList", 0) })
        LuaReg.num(A.A_ShipBlueprint_originalWeaponCount, NumGet { listCount(it, "weaponList", 0) })
        LuaReg.num(A.A_ShipBlueprint_droneCount, NumGet { listCount(it, "droneList", 0) })
        LuaReg.num(A.A_ShipBlueprint_originalDroneCount, NumGet { listCount(it, "droneList", 0) })
        LuaReg.str(A.A_ShipBlueprint_loadWeapons, StrGet { b(it).sourceElement.getChild("weaponList")?.getAttributeValue("load") ?: "" })
        LuaReg.str(A.A_ShipBlueprint_loadDrones, StrGet { b(it).sourceElement.getChild("droneList")?.getAttributeValue("load") ?: "" })
        // boardingAI: 0 none, 1 sabotage, 2 invasion
        LuaReg.num(A.A_ShipBlueprint_boardingAI, NumGet { o ->
            when (b(o).boardingStrategy) {
                xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.NONE -> 0.0
                xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.SABOTAGE -> 1.0
                else -> 2.0
            }
        })
        LuaReg.num(A.A_ShipBlueprint_maxSector, NumGet { (b(it).sourceElement.getChildTextTrim("maxSector")?.toIntOrNull() ?: Int.MAX_VALUE).toDouble() })
        LuaReg.num(A.A_ShipBlueprint_minSector, NumGet { (b(it).sourceElement.getChildTextTrim("minSector")?.toIntOrNull() ?: 0).toDouble() })
        // The `<crewCount amount class>` entries, each class repeated `amount` times
        fun crew(o: Any?): List<String> = b(o).sourceElement.getChildren("crewCount").flatMap { e ->
            List(e.getAttributeValue("amount")?.trim()?.toIntOrNull() ?: 0) { e.getAttributeValue("class") ?: "" }
        }
        LuaReg.num(A.A_ShipBlueprint_originalCrewCount, NumGet { crew(it).size.toDouble() })
        LuaReg.obj(A.A_ShipBlueprint_defaultCrew, ObjGet { o -> LuaViews.cached(o!!, A.A_ShipBlueprint_defaultCrew) { x -> ListView(A.T_vector_string, 0) { crew(x) } } })
        // customCrew (`<crewMember>` blueprints) is not modelled: always empty
        LuaReg.obj(A.A_ShipBlueprint_customCrew, ObjGet { o -> LuaViews.cached(o!!, A.A_ShipBlueprint_customCrew) { _ -> ListView(A.T_vector_CrewBlueprint, A.T_CrewBlueprint) { emptyList<Any?>() } } })
        LuaReg.num(A.A_ShipBlueprint_maxCrew, NumGet { (b(it).sourceElement.getChildTextTrim("maxCrew")?.toIntOrNull() ?: b(it).initialCrew.lastOrNull { c -> c.max != null }?.max ?: 8).toDouble() })
        LuaReg.num(A.A_ShipBlueprint_bp_count, NumGet { 0.0 })
        fun strings(key: Int, read: (ShipBlueprint) -> List<String>) =
            LuaReg.obj(key, ObjGet { o -> LuaViews.cached(o!!, key) { x -> ListView(A.T_vector_string, 0) { read(b(x)) } } })
        fun listed(sb: ShipBlueprint, tag: String, child: String) =
            sb.sourceElement.getChild(tag)?.getChildren(child)?.mapNotNull { e -> e.getAttributeValue("name") } ?: emptyList()
        strings(A.A_ShipBlueprint_weapons) { listed(it, "weaponList", "weapon") }
        strings(A.A_ShipBlueprint_drones) { listed(it, "droneList", "drone") }
        strings(A.A_ShipBlueprint_augments) { it.initialAugments }
        LuaReg.obj(A.A_ShipBlueprint_systems, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ShipBlueprint_systems) { x -> ListView(A.T_vector_int, 0) { b(x).systems.filter { s -> s.availableByDefault }.map { s -> systemId(s.systemName) } } }
        })
    }

    // ---------------------------------------------------------------- SettingValues

    private fun settings() {
        fun prof() = HsLua.game?.profile
        fun n(a: Int, v: () -> Int) = LuaReg.num(a, NumGet { v().toDouble() })
        fun f(a: Int, v: () -> Boolean) = LuaReg.bool(a, { v() })
        n(A.A_SettingValues_fullscreen) { 0 }
        n(A.A_SettingValues_currentFullscreen) { 0 }
        n(A.A_SettingValues_lastFullscreen) { 0 }
        // The volume sliders are 0-10 in settings.ini; the engine keeps 0-1
        n(A.A_SettingValues_sound) { Math.round((prof()?.soundVolume ?: 1f) * 10f) }
        n(A.A_SettingValues_music) { Math.round((prof()?.musicVolume ?: 1f) * 10f) }
        n(A.A_SettingValues_difficulty) { LuaAdapters.inGame()?.difficulty?.ordinal ?: 1 }
        f(A.A_SettingValues_commandConsole) { HS.cfg().consoleEnabled }
        f(A.A_SettingValues_altPause) { false }
        f(A.A_SettingValues_touchAutoPause) { false }
        f(A.A_SettingValues_fbError) { false }
        LuaReg.str(A.A_SettingValues_language, StrGet { "en" })
        f(A.A_SettingValues_languageSet) { true }
        LuaReg.obj(A.A_SettingValues_screenResolution, ObjGet { PointValue(1280, 720) })
        n(A.A_SettingValues_dialogKeys) { prof()?.eventChoiceHotkeys?.id ?: 1 }
        f(A.A_SettingValues_logging) { false }
        f(A.A_SettingValues_bShowChangelog) { false }
        n(A.A_SettingValues_loadingSaveVersion) { 11 }
        f(A.A_SettingValues_achPopups) { prof()?.achievementPopups ?: true }
        f(A.A_SettingValues_manualResolution) { false }
        f(A.A_SettingValues_manualWindowed) { false }
        f(A.A_SettingValues_manualStretched) { false }
        f(A.A_SettingValues_showPaths) { prof()?.showBeaconPaths ?: true }
        f(A.A_SettingValues_swapTextureType) { false }
        f(A.A_SettingValues_colorblind) { false }
        f(A.A_SettingValues_holdingModifier) { false }
        f(A.A_SettingValues_bDlcEnabled) { xyz.znix.xftl.hs.render.HsTooltips.dlcEnabled() }
        n(A.A_SettingValues_openedList) { prof()?.listTutorialState ?: 0 }
        f(A.A_SettingValues_beamTutorial) { false }
    }
}
