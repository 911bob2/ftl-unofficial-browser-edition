// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i: StarMap::currentSector,
// Sector, SectorDescription, CrewMember::IsCrew/IsDrone, CommandGui::spaceStatus, SpaceStatus::RenderWarningText,
// SpaceManager::gamePaused, random32/srandom32, Ship::shipImage, the ShipObject::HasEquipment %extend), not from the
// 1.23.2 tag. Native semantics from FTL 1.6.14 (addresses below).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsReq
import xyz.znix.xftl.sector.GameMap
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Hyperspace.Settings (SettingValues): the options MV reads. */
object LuaSettings : LuaTyped { override val luaType get() = A.T_SettingValues }

/** CustomAugmentManager::GetInstance(). */
object LuaAugmentManager : LuaTyped { override val luaType get() = A.T_CustomAugmentManager }

/** CustomAugmentManager::GetShipAugments(id): augListWithHidden of that ship, read when a member is used. */
class LuaShipAugments(@JvmField val shipId: Int) : LuaTyped {
    override val luaType get() = A.T_unordered_map_string_int
    fun map(): Map<String, Int> = LuaAdapters.shipManager(shipId)?.let { xyz.znix.xftl.hs.ship.HsAugments.augListWithHidden(it) } ?: emptyMap()
}

/** CommandGui::spaceStatus (the hazard icons and warning text, PlayerShipUI's SpaceStatus part). */
object LuaSpaceStatus : LuaTyped { override val luaType get() = A.T_SpaceStatus }

/** Sector::description (SectorDescription) of a sector on the map. */
class LuaSectorDescription(@JvmField val info: GameMap.SectorInfo) : LuaTyped {
    override val luaType get() = A.T_SectorDescription
}

/**
 * Members Multiverse 5.5.1 reads every tick or loop that the first full runs reported missing (fullrun D5):
 * events.lua (currentSector), instant-clone-heal.lua (IsCrew/IsDrone), environments/icons.lua (spaceStatus),
 * core.lua (gamePaused), hull-anims.lua (random32, shipImage) and ENTER_DYNASTY_UNIQUE (HasEquipment).
 */
object LuaRuntimeApi {
    fun install() {
        // StarMap::currentSector: the sector the player is in (nil before the map exists, as HS's null pointer).
        // Sector::level is the column (0..7); SectorDescription::type the sector type name, CIVILIAN_SECTOR for the
        // first sector (the description GetSectorDescription builds, hs.events' descriptionType).
        LuaReg.obj(A.A_StarMap_currentSector, ObjGet { LuaAdapters.inGame()?.currentBeacon?.sector })
        LuaReg.num(A.A_Sector_level, NumGet { (it as Sector).sectorNumber.toDouble() })
        LuaReg.obj(A.A_Sector_description, ObjGet {
            LuaViews.cached((it as Sector).info, A.A_Sector_description) { o -> LuaSectorDescription(o as GameMap.SectorInfo) }
        })
        LuaReg.str(A.A_SectorDescription_type, StrGet { HsEvents.descriptionType((it as LuaSectorDescription).info) })

        // CrewMember::IsCrew (vtable +0x2c, 0x7557f0) is !IsDrone(); IsDrone (+0x30) is false for CrewMember
        // (0x755830) and true for CrewDrone (0x775cd0), the engine's drone pawns.
        LuaReg.method(A.M_CrewMember_IsDrone, LuaMethod { ret.bool(arg.obj(1) is AbstractIndoorsDrone.Pawn) })
        LuaReg.method(A.M_CrewMember_IsCrew, LuaMethod { ret.bool(arg.obj(1) !is AbstractIndoorsDrone.Pawn) })

        // CrewMember::CanSuffocate: HS's vtable entry (CrewVTable.cpp _HS_CanSuffocate) is the race's CAN_SUFFOCATE
        // stat; a drone without a race definition cannot suffocate (CrewDrone::CanSuffocate).
        LuaReg.method(A.M_CrewMember_CanSuffocate, LuaMethod {
            val crew = arg.obj(1) as xyz.znix.xftl.crew.AbstractCrew
            val ex = xyz.znix.xftl.hs.crew.HsCrew.ex(crew)
            val def = ex.definition()
            ret.bool(if (crew is AbstractIndoorsDrone.Pawn && def == null) false
                     else ex.calculateBool(xyz.znix.xftl.hs.stat.CrewStat.CAN_SUFFOCATE, def))
        })

        // CommandGui::spaceStatus and SpaceStatus::RenderWarningText(effect, textOffset) (0x501210): the hazard
        // warning text (green allies text for effect 9), drawn only while a render callback runs.
        LuaReg.obj(A.A_CommandGui_spaceStatus, ObjGet { if (LuaAdapters.inGame() != null) LuaSpaceStatus else null })
        LuaReg.method(A.M_SpaceStatus_RenderWarningText, LuaMethod {
            val ui = LuaAdapters.inGame()?.shipUI
            if (ui != null && HsLua.graphics != null) {
                LuaGraphics.flushQuads()
                ui.drawSpaceWarningText(arg.int(2) == 9, arg.int(3))
            }
            0
        })

        // SpaceManager::SwitchPlanet(name) (0x418280): the current beacon's planet (AbstractEnvironment.hsSwitchPlanet;
        // MV's base-star-like.lua hides it with "NONE"). The returned ImageDesc is not built (MV ignores it): nil.
        LuaReg.method(A.M_SpaceManager_SwitchPlanet, LuaMethod {
            val game = LuaAdapters.inGame()
            if (game != null) game.currentBeacon.getEnvironment(game).hsSwitchPlanet(arg.str(2) ?: "")
            0
        })

        // SpaceManager::gamePaused (+0x360): the pause flag the background tint reads (SpaceManager 0x4194c0), which
        // the engine keeps as InGameState.isShadePaused.
        LuaReg.bool(A.A_SpaceManager_gamePaused, { LuaAdapters.inGame()?.isShadePaused == true })

        // Hyperspace.random32 is FTL's random32 (0x6569f0), the generator math.random uses too (LuaScriptInit.cpp):
        // the same LuaRng stream (NativeRandom while a generation-time callback runs, BrowserRandom otherwise).
        // Hyperspace.setRandomSeed is srandom32 on the same stream (MV does not call it; the oracle probes do).
        LuaReg.method(A.M_random32, LuaMethod { ret.num(LuaRng.rand32()) })
        LuaReg.method(A.M_setRandomSeed, LuaMethod { LuaRng.srand32(arg.int(1)); 0 })

        // Ship::shipImage (ImageDesc): the hull image's offset from the layout's <img x y> (FTL draws the hull at
        // shipImage + ShipGraph::shipBox, the engine at Ship.hullOffset = shipBox origin + that offset) and its size.
        LuaReg.obj(A.A_Ship_shipImage, ObjGet { o ->
            val ship = (o as LuaShipGeom).ship
            val d = LuaViews.cached(ship, A.A_Ship_shipImage) { LuaStructs.create(A.T_ImageDesc) }
            d.values[A.A_ImageDesc_x] = ship.type.hullOffset.x.toDouble()
            d.values[A.A_ImageDesc_y] = ship.type.hullOffset.y.toDouble()
            d.values[A.A_ImageDesc_w] = (ship.hullImage?.width ?: 0).toDouble()
            d.values[A.A_ImageDesc_h] = (ship.hullImage?.height ?: 0).toDouble()
            d
        })

        // ShipObject::HasEquipment(name[, checkCargo]) as HS's %extend: the advanced checks (advancedCheckEquipment[7])
        // with g_checkCargo set to the argument, both restored afterwards.
        LuaReg.method(A.M_ShipObject_HasEquipment__SWIG_0, hasEquipment)
        LuaReg.method(A.M_ShipObject_HasEquipment__SWIG_1, hasEquipment)
        second()
    }

    /** Members whose first full-run use only showed up once the ones above worked (seed 2 rerun). */
    private fun second() {
        // Location::event is a plain LocationEvent pointer: MV's left-side quest swap (events.lua:220/240) exchanges
        // two locations' events. The getter is hs.events' (HsEventsLua); a nil or foreign value leaves it as it is.
        val getEvent = LuaReg.objGet[A.A_Location_event]
        if (getEvent != null && LuaReg.objSet[A.A_Location_event] == null) {
            LuaReg.obj(A.A_Location_event, getEvent, ObjSet { o, v ->
                if (v is xyz.znix.xftl.sector.CreatedEvent) (o as xyz.znix.xftl.sector.Beacon).createdEvent = v
            })
        }

        // ActivatedPowerRequirements (hs.crew's model, CustomCrew.h): the members instant-clone-heal.lua's
        // can_use_power reads from a power's playerReq. The condition vectors are read-only views (MV calls :empty()).
        fun rq(o: Any?) = o as xyz.znix.xftl.hs.crew.ActivatedPowerRequirements
        val flags: Map<Int, (xyz.znix.xftl.hs.crew.ActivatedPowerRequirements) -> Boolean> = mapOf(
            A.A_ActivatedPowerRequirements_playerShip to { r -> r.playerShip },
            A.A_ActivatedPowerRequirements_enemyShip to { r -> r.enemyShip },
            A.A_ActivatedPowerRequirements_checkRoomCrew to { r -> r.checkRoomCrew },
            A.A_ActivatedPowerRequirements_enemyInRoom to { r -> r.enemyInRoom },
            A.A_ActivatedPowerRequirements_friendlyInRoom to { r -> r.friendlyInRoom },
            A.A_ActivatedPowerRequirements_systemInRoom to { r -> r.systemInRoom },
            A.A_ActivatedPowerRequirements_systemDamaged to { r -> r.systemDamaged },
            A.A_ActivatedPowerRequirements_hasClonebay to { r -> r.hasClonebay },
            A.A_ActivatedPowerRequirements_aiDisabled to { r -> r.aiDisabled },
            A.A_ActivatedPowerRequirements_outOfCombat to { r -> r.outOfCombat },
            A.A_ActivatedPowerRequirements_inCombat to { r -> r.inCombat },
            A.A_ActivatedPowerRequirements_requiredSystemFunctional to { r -> r.requiredSystemFunctional },
        )
        for ((a, f) in flags) if (LuaReg.numGet[a] == null) LuaReg.bool(a, { f(rq(it)) })
        if (LuaReg.numGet[A.A_ActivatedPowerRequirements_requiredSystem] == null)
            LuaReg.num(A.A_ActivatedPowerRequirements_requiredSystem, NumGet { rq(it).requiredSystem.toDouble() })
        if (LuaReg.objGet[A.A_ActivatedPowerRequirements_minHealth] == null) {
            LuaReg.obj(A.A_ActivatedPowerRequirements_minHealth, ObjGet { rq(it).minHealth })
            LuaReg.obj(A.A_ActivatedPowerRequirements_maxHealth, ObjGet { rq(it).maxHealth })
        }
        if (LuaReg.objGet[A.A_ActivatedPowerRequirements_extraConditions] == null) {
            LuaReg.obj(A.A_ActivatedPowerRequirements_extraConditions, ObjGet {
                LuaViews.cached(it!!, A.A_ActivatedPowerRequirements_extraConditions) { o ->
                    ListView(A.T_vector_pair_CrewExtraCondition_bool, A.T_pair_CrewExtraCondition_bool) { rq(o).extraConditions }
                }
            })
            LuaReg.obj(A.A_ActivatedPowerRequirements_extraOrConditions, ObjGet {
                LuaViews.cached(it!!, A.A_ActivatedPowerRequirements_extraOrConditions) { o ->
                    ListView(A.T_vector_pair_CrewExtraCondition_bool, A.T_pair_CrewExtraCondition_bool) { rq(o).extraOrConditions }
                }
            })
        }

        // CustomAugmentManager (CustomAugments.h/.cpp): GetShipAugments(id) is &augListWithHidden[id] (an empty map for
        // a missing ship), IsAugment / GetAugmentDefinition look up hyperspace.xml's <aug> definitions (hs.ship), whose
        // statBoosts are the AUGMENT stat boosts hs.stat parsed.
        LuaReg.method(A.M_CustomAugmentManager_GetInstance, LuaMethod { ret.obj(LuaAugmentManager, A.T_CustomAugmentManager) })
        LuaReg.method(A.M_CustomAugmentManager_GetShipAugments, LuaMethod {
            val id = arg.int(2)
            ret.obj(if (id == 0 || id == 1) shipAugments[id] else LuaShipAugments(id), A.T_unordered_map_string_int)
        })
        LuaReg.method(A.M_CustomAugmentManager_IsAugment, LuaMethod {
            ret.bool(xyz.znix.xftl.hs.ship.HsAugments.definition(arg.str(2) ?: "") != null)
        })
        LuaReg.method(A.M_CustomAugmentManager_GetAugmentDefinition, LuaMethod {
            ret.obj(xyz.znix.xftl.hs.ship.HsAugments.definition(arg.str(2) ?: ""), A.T_AugmentDefinition)
        })
        fun ad(o: Any?) = o as xyz.znix.xftl.hs.ship.HsAugments.AugmentDefinition
        if (LuaReg.strGet[A.A_AugmentDefinition_name] == null) {
            LuaReg.str(A.A_AugmentDefinition_name, StrGet { ad(it).name })
            LuaReg.bool(A.A_AugmentDefinition_locked, { ad(it).locked })
            LuaReg.num(A.A_AugmentDefinition_iconShipId, NumGet { ad(it).iconShipId.toDouble() })
            LuaReg.obj(A.A_AugmentDefinition_statBoosts, ObjGet {
                LuaViews.cached(it!!, A.A_AugmentDefinition_statBoosts) { o ->
                    ListView(A.T_vector_StatBoostDefinition, A.T_StatBoostDefinition) {
                        xyz.znix.xftl.hs.stat.AugmentStatBoosts.defs[ad(o).name] ?: emptyList()
                    }
                }
            })
        }
        // std::unordered_map<std::string, int> (SWIG's std_map extension: keys() is a new vector, get/[] raise on a
        // missing key)
        fun sm(o: Any?) = (o as LuaShipAugments).map()
        LuaReg.method(A.M_unordered_map_string_int_size, LuaMethod { ret.num(sm(arg.obj(1)).size) })
        LuaReg.method(A.M_unordered_map_string_int_empty, LuaMethod { ret.bool(sm(arg.obj(1)).isEmpty()) })
        LuaReg.method(A.M_unordered_map_string_int_has_key, LuaMethod { ret.bool(sm(arg.obj(1)).containsKey(arg.str(2) ?: "")) })
        LuaReg.method(A.M_unordered_map_string_int_keys, LuaMethod {
            ret.obj(ListView(A.T_vector_string, 0, ArrayList<Any?>(sm(arg.obj(1)).keys)), A.T_vector_string)
        })
        val mapGet = LuaMethod { ret.num(sm(arg.obj(1))[arg.str(2) ?: ""] ?: throw LuaError("SWIG_IndexError:key not found")) }
        LuaReg.method(A.M_unordered_map_string_int_get, mapGet)
        LuaReg.method(A.M_unordered_map_string_int___getitem, mapGet)

        // ShipManager::GetOxygenPercentage (0x49f420, the AI's oxygen test): 0 without an oxygen system (slot 2 is -1),
        // else the rounded percentage of the ship's average oxygen (FUN_007458d0), as BrowserEnemyTargeting reads it.
        // Hyperspace oxygenWithoutSystem (OxygenWithoutSystem.cpp): the dummy oxygen system of a ship without one
        // answers with the rooms' average too (oracle hg-o2 o2t rows).
        LuaReg.method(A.M_ShipManager_GetOxygenPercentage, LuaMethod {
            val ship = arg.obj(1) as Ship
            val dummy = ship.oxygen == null && xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().oxygenWithoutSystem
            ret.num(if (ship.oxygen == null && !dummy) 0 else Math.floor((ship.averageOxygen * 100f + 0.5f).toDouble()).toInt())
        })

        // ShipManager::GetRoomCenter / Ship::GetRoomCenter (inline in HS's zhl): ShipGraph::GetShipInfo(iShipId)'s
        // GetRoomCenter, i.e. the same as LuaShipGraph's (MV's blue giant flare aims DamageArea with it).
        fun roomCenter(ship: Ship, id: Int): Int {
            val r = ship.rooms.firstOrNull { it.id == id }
            val p = if (r == null) PointfValue(-1f, -1f)
                    else PointfValue((r.offsetX + r.pixelWidth / 2).toFloat(), (r.offsetY + r.pixelHeight / 2).toFloat())
            return ret.obj(p, A.T_Pointf)
        }
        LuaReg.method(A.M_ShipManager_GetRoomCenter, LuaMethod { roomCenter(arg.obj(1) as Ship, arg.int(2)) })
        LuaReg.method(A.M_Ship_GetRoomCenter, LuaMethod { roomCenter((arg.obj(1) as LuaShipGeom).ship, arg.int(2)) })

        // Hyperspace.Settings: frameLimit is the browser draw cap (on unless Unlimited), vsync and lowend are the
        // options the engine shows as Off and dynamic background On (OptionsWindow).
        LuaReg.obj(A.A_module_Settings, ObjGet { LuaSettings })
        LuaReg.bool(A.A_SettingValues_frameLimit, { (HsLua.game?.profile?.frameRateLimit ?: 0) != 0 })
        LuaReg.bool(A.A_SettingValues_vsync, { false })
        LuaReg.bool(A.A_SettingValues_lowend, { false })
    }

    private val shipAugments = arrayOf(LuaShipAugments(0), LuaShipAugments(1))

    private val hasEquipment = LuaMethod {
        val ship = arg.obj(1) as Ship
        val name = arg.str(2) ?: ""
        val cargo = !arg.isNil(3) && arg.bool(3)
        val game = LuaAdapters.inGame() ?: return@LuaMethod ret.num(0)
        val oldCargo = HsEvents.checkCargo
        HsEvents.checkCargo = cargo
        var result = 0
        try {
            HsEvents.withCheck(7) { result = HsReq.hasEquipment(game, ship, name) }
        } finally {
            HsEvents.checkCargo = oldCargo
        }
        ret.num(result)
    }
}
