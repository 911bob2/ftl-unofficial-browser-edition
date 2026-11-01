// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomAugments.cpp (the CreateChoiceBox hook that
// fires PRE/POST_CREATE_CHOICEBOX) and lua/LuaLibScript.cpp (the UpdateLocation/CreateLocation hooks that fire
// script.on_game_event) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.hs.lua.LuaBeaconLoc
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaAdapters
import xyz.znix.xftl.hs.lua.LuaError
import xyz.znix.xftl.hs.lua.LuaEvents
import xyz.znix.xftl.hs.lua.LuaJs
import xyz.znix.xftl.hs.lua.LuaMethod
import xyz.znix.xftl.hs.lua.LuaReg
import xyz.znix.xftl.hs.lua.LuaStructs
import xyz.znix.xftl.hs.lua.LuaTyped
import xyz.znix.xftl.hs.lua.LuaViews
import xyz.znix.xftl.hs.lua.ListView
import xyz.znix.xftl.hs.lua.NumGet
import xyz.znix.xftl.hs.lua.ObjGet
import xyz.znix.xftl.hs.lua.ObjSet
import xyz.znix.xftl.hs.lua.PointfValue
import xyz.znix.xftl.hs.lua.StrGet
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.CreatedEvent
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * The events side of the Lua bridge: the choice box and game-event hooks, and the members MV's event scripts read
 * (WorldManager.starMap, StarMap, Location, LocationEvent, ChoiceBox.mainText, CustomEventsParser, CustomEvent,
 * TriggeredEventDefinition). Engine objects: StarMap is [StarMapRef], Location a [Beacon], LocationEvent a
 * [CreatedEvent], ChoiceBox the open DialogueWindow (its [ChoiceBoxText.mainText]).
 */
object HsEventsLua {
    object StarMapRef : LuaTyped { override val luaType get() = A.T_StarMap }
    object ParserRef : LuaTyped { override val luaType get() = A.T_CustomEventsParser }
    object AsteroidGeneratorRef : LuaTyped { override val luaType get() = A.T_AsteroidGenerator }
    class SystemInfoView(@JvmField val bp: xyz.znix.xftl.game.ShipBlueprint) : LuaTyped { override val luaType get() = A.T_map_int_SystemTemplate }

    /** What a ChoiceBox exposes: the box's main text. */
    interface ChoiceBoxText {
        var mainText: String
        val choiceEntries: List<ChoiceEntry>
        val potentialChoice: Int
    }

    /** A ChoiceText: the option's type (0 normal, 1 unavailable, 2 blue), its text and the event it leads to. */
    interface ChoiceEntry {
        var type: Int
        var text: String
        val created: CreatedEvent?
        /** The choice hides its rewards (`hidden="true"`): ChoiceText.rewards reads empty. */
        val hidden: Boolean
    }

    private fun game(): InGameState? = LuaAdapters.inGame()

    private fun name(e: CreatedEvent?): String = e?.template?.hsName ?: ""

    /** Diagnostics (full-run harness): runs after the PRE_CREATE_CHOICEBOX callbacks, where an oracle probe callback runs. */
    @JvmField var diagnosticPreBox: ((CreatedEvent) -> Unit)? = null

    /** The event whose choice box is being made or open (ChoiceBox.rewards). */
    @JvmField var currentBoxEvent: CreatedEvent? = null

    /** CreateChoiceBox, before the original (and the other priority-0 hooks' super chain). */
    @JvmStatic
    fun preChoiceBox(created: CreatedEvent) {
        currentBoxEvent = created
        if (!HS.luaActive || !HsLua.hasInt[A.EV_PRE_CREATE_CHOICEBOX]) return
        val prev = LuaEvents.generation
        LuaEvents.generation = true
        try {
            ret.obj(created, A.T_LocationEvent)
            LuaJs.fire(A.EV_PRE_CREATE_CHOICEBOX, 1, 0)
            LuaJs.pop(1)
        } finally { LuaEvents.generation = prev }
    }

    /** CreateChoiceBox, after the original: (choiceBox, event). */
    @JvmStatic
    fun postChoiceBox(box: ChoiceBoxText?, created: CreatedEvent) {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_POST_CREATE_CHOICEBOX]) return
        val prev = LuaEvents.generation
        LuaEvents.generation = true
        try {
            ret.obj(box, A.T_ChoiceBox)
            ret.obj(created, A.T_LocationEvent)
            LuaJs.fire(A.EV_POST_CREATE_CHOICEBOX, 2, 0)
            LuaJs.pop(2)
        } finally { LuaEvents.generation = prev }
    }

    /** UpdateLocation / CreateLocation, before the original: script.on_game_event callbacks for the event's name. */
    @JvmStatic
    fun gameEvent(created: CreatedEvent?, loading: Boolean) {
        if (!HS.luaActive) return
        val n = name(created)
        if (n.isNotEmpty()) LuaEvents.gameEvent(n, loading)
    }

    /** Called by LuaAdaptersMore.install. */
    @JvmStatic
    fun install() {
        // Seeds.cpp: Global::IsSeededRun (isCustomSeed) and Global::currentSeed (static members)
        LuaReg.method(A.M_Global_IsSeededRun, LuaMethod { ret.bool(xyz.znix.xftl.hs.sector.HsSeeds.isSeededRun) })
        LuaReg.num(A.A_Global_currentSeed, NumGet { xyz.znix.xftl.hs.sector.HsSeeds.currentSeed.toDouble() })

        // SpaceManager hazards (MV's value trackers turn them into loc_environment_* event variables after each box);
        // kept to what the current beacon's environment says, and left to their owners when already registered.
        fun env(): List<Beacon.EnvironmentType> = game()?.currentBeacon?.environmentTypes ?: emptyList()
        if (LuaReg.objGet[A.A_SpaceManager_asteroidGenerator] == null) {
            LuaReg.obj(A.A_SpaceManager_asteroidGenerator, ObjGet { AsteroidGeneratorRef })
            LuaReg.bool(A.A_AsteroidGenerator_bRunning, { Beacon.EnvironmentType.ASTEROID in env() })
        }
        if (LuaReg.numGet[A.A_SpaceManager_sunLevel] == null) LuaReg.bool(A.A_SpaceManager_sunLevel, { Beacon.EnvironmentType.SUN in env() })
        if (LuaReg.numGet[A.A_SpaceManager_pulsarLevel] == null) LuaReg.bool(A.A_SpaceManager_pulsarLevel, { Beacon.EnvironmentType.PULSAR in env() })
        if (LuaReg.numGet[A.A_SpaceManager_bPDS] == null) LuaReg.bool(A.A_SpaceManager_bPDS, { Beacon.EnvironmentType.PDS in env() })
        if (LuaReg.numGet[A.A_SpaceManager_envTarget] == null) LuaReg.num(A.A_SpaceManager_envTarget, NumGet {
            val b = game()?.currentBeacon
            (if (b == null || b.isOvertaken) 0 else b.event.browserPdsTarget).toDouble()
        })
        if (LuaReg.numGet[A.A_SpaceManager_bNebula] == null) LuaReg.bool(A.A_SpaceManager_bNebula, { env().any { it.isNebula } })

        // ShipBlueprint::systemInfo (std::map<int, SystemTemplate>): MV's value trackers copy each maxPower (the
        // blueprint's `max`, -1 without one) into the *_cap event variables. Left to its owner when registered.
        if (LuaReg.objGet[A.A_ShipBlueprint_systemInfo] == null) {
            LuaReg.obj(A.A_ShipBlueprint_systemInfo, ObjGet {
                LuaViews.cached(it!!, A.A_ShipBlueprint_systemInfo) { o -> SystemInfoView(o as xyz.znix.xftl.game.ShipBlueprint) }
            })
            // A whole map written: ships built later take the other blueprint's systems.
            LuaReg.objSet[A.A_ShipBlueprint_systemInfo] = ObjSet { o, v ->
                (v as? SystemInfoView)?.let { (o as xyz.znix.xftl.game.ShipBlueprint).systems = it.bp.systems }
            }
            fun sys(): xyz.znix.xftl.game.ShipBlueprint.ParsedSystem? {
                val id = arg.int(2)
                return (arg.obj(1) as SystemInfoView).bp.systems.lastOrNull { systemId(it.systemName) == id }
            }
            fun template(s: xyz.znix.xftl.game.ShipBlueprint.ParsedSystem): Any = LuaViews.cached(s, A.T_SystemTemplate) { o ->
                val p = o as xyz.znix.xftl.game.ShipBlueprint.ParsedSystem
                LuaStructs.create(A.T_SystemTemplate).also { t ->
                    t.values[A.A_SystemTemplate_systemId] = systemId(p.systemName).toDouble()
                    t.values[A.A_SystemTemplate_powerLevel] = p.startingPower.toDouble()
                    t.values[A.A_SystemTemplate_maxPower] = (p.aiMaxPower ?: -1).toDouble()
                    // A ship built later starts from the written levels.
                    t.onWrite = {
                        p.startingPower = (t.values[A.A_SystemTemplate_powerLevel] as? Double ?: 0.0).toInt()
                        p.aiMaxPower = (t.values[A.A_SystemTemplate_maxPower] as? Double ?: -1.0).toInt().takeIf { it >= 0 }
                    }
                }
            }
            LuaReg.method(A.M_map_int_SystemTemplate_has_key, LuaMethod { ret.bool(sys() != null) })
            val get = LuaMethod { val s = sys() ?: throw LuaError("SWIG_IndexError:key not found"); ret.obj(template(s), A.T_SystemTemplate) }
            LuaReg.method(A.M_map_int_SystemTemplate_empty, LuaMethod { ret.bool((arg.obj(1) as SystemInfoView).bp.systems.isEmpty()) })
            LuaReg.method(A.M_map_int_SystemTemplate_keys, LuaMethod {
                val ids = (arg.obj(1) as SystemInfoView).bp.systems.map { systemId(it.systemName) }.distinct().sorted()
                ret.obj(ListView(A.T_vector_int, 0, ArrayList<Any?>(ids.map { it.toDouble() })), A.T_vector_int)
            })
            // operator[]: a key the blueprint lacks reads as a default template
            LuaReg.method(A.M_map_int_SystemTemplate___getitem, LuaMethod {
                ret.obj(sys()?.let { template(it) } ?: LuaStructs.create(A.T_SystemTemplate), A.T_SystemTemplate)
            })
            LuaReg.method(A.M_map_int_SystemTemplate_get, get)
            LuaReg.method(A.M_map_int_SystemTemplate_size, LuaMethod { ret.num((arg.obj(1) as SystemInfoView).bp.systems.map { it.systemName }.toSet().size) })
        }

        // WorldManager::starMap is a member, not a pointer: never nil, also in the main menu (where every StarMap field
        // reads as an empty map).
        LuaReg.obj(A.A_WorldManager_starMap, ObjGet { StarMapRef })

        // StarMap
        // the jump's target from the click on (oracle hg-nav: currentLoc changes with the fuel, before the jump-out ends)
        LuaReg.obj(A.A_StarMap_currentLoc, ObjGet { game()?.let { g -> g.jumpOutTarget?.takeIf { it.sector === g.currentBeacon.sector } ?: g.currentBeacon } })
        LuaReg.obj(A.A_StarMap_locations, ObjGet {
            LuaViews.cached(StarMapRef, A.A_StarMap_locations) { _ ->
                ListView(A.T_vector_location, A.T_Location) { game()?.currentBeacon?.sector?.beacons ?: emptyList() }
            }
        })
        LuaReg.num(A.A_StarMap_worldLevel, NumGet { (game()?.currentBeacon?.sector?.sectorNumber ?: 0).toDouble() })
        LuaReg.bool(A.A_StarMap_bMapRevealed, { game()?.currentBeacon?.sector?.mapRevealed == true })
        // StarMap.ship/shipNoFuel/mapsBottom: LuaStarMap

        // Location
        LuaReg.obj(A.A_Location_event, ObjGet { (it as Beacon).createdEvent })
        // Location::visited counts the arrivals.
        LuaReg.num(A.A_Location_visited, NumGet { (it as Beacon).hsVisits.toDouble() })
        LuaReg.str(A.A_Location_spaceImage, StrGet { spaceImage(it as Beacon) })
        LuaReg.obj(A.A_Location_loc, ObjGet {
            LuaViews.cached(it!!, A.A_Location_loc) { o -> LuaBeaconLoc(o as Beacon) }
        })
        LuaReg.obj(A.A_Location_connectedLocations, ObjGet {
            LuaViews.cached(it!!, A.A_Location_connectedLocations) { o ->
                ListView(A.T_vector_location, A.T_Location) { (o as Beacon).neighbours }
            }
        })

        // LocationEvent
        LuaReg.str(A.A_LocationEvent_eventName, StrGet { name(it as CreatedEvent) })
        LuaReg.str(A.A_LocationEvent_spaceImage, StrGet { (it as CreatedEvent).let { e -> e.text?.back ?: e.template.backImg?.name ?: "" } })
        LuaReg.str(A.A_LocationEvent_planetImage, StrGet { (it as CreatedEvent).let { e -> e.text?.planet ?: e.template.planetImg?.name ?: "" } })
        LuaReg.obj(A.A_LocationEvent_text, ObjGet {
            LuaViews.cached(it!!, A.A_LocationEvent_text) { o ->
                LuaStructs.create(A.T_TextString).also { t ->
                    t.values[A.A_TextString_data] = (o as CreatedEvent).text?.localised ?: ""
                    t.values[A.A_TextString_isLiteral] = true
                }
            }
        })

        // ChoiceBox
        LuaReg.str(A.A_ChoiceBox_mainText, StrGet { (it as ChoiceBoxText).mainText })

        // CustomEventsParser
        LuaReg.method(A.M_CustomEventsParser_GetInstance, LuaMethod { ret.obj(ParserRef, A.T_CustomEventsParser) })
        val getCustom = LuaMethod {
            val g = game()
            val key = (arg.obj(2) as? Beacon)?.createdEvent?.let { name(it) } ?: arg.str(2)
            ret.obj(g?.eventManager?.hs?.getCustomEvent(key), A.T_CustomEvent)
        }
        LuaReg.method(A.M_CustomEventsParser_GetCustomEvent__SWIG_0, getCustom)
        LuaReg.method(A.M_CustomEventsParser_GetCustomEvent__SWIG_1, getCustom)
        // LoadEvent(world, name, ignoreUnique, seed[, parent]) and LoadEvent(world, list, seed[, parent])
        val byName = LuaMethod {
            val g = game() ?: return@LuaMethod 0
            val parent = if (arg.isNil(6)) null else arg.obj(6) as? CustomEvent
            HsFlow.loadEvent(g, g.shipUI.hsHost, arg.str(3) ?: "", arg.bool(4), arg.int(5), parent)
            0
        }
        LuaReg.method(A.M_CustomEventsParser_LoadEvent__SWIG_2, byName)
        LuaReg.method(A.M_CustomEventsParser_LoadEvent__SWIG_3, byName)
        val byList = LuaMethod {
            val g = game() ?: return@LuaMethod 0
            val list = arg.obj(3) as? EventLoadList ?: return@LuaMethod 0
            val parent = if (arg.isNil(5)) null else arg.obj(5) as? CustomEvent
            HsFlow.loadEvent(g, g.shipUI.hsHost, list, arg.int(4), parent)
            0
        }
        LuaReg.method(A.M_CustomEventsParser_LoadEvent__SWIG_0, byList)
        LuaReg.method(A.M_CustomEventsParser_LoadEvent__SWIG_1, byList)

        // CustomEvent.triggeredEvents (vector<int>: indices into TriggeredEventDefinition.defs)
        LuaReg.obj(A.A_CustomEvent_triggeredEvents, ObjGet {
            LuaViews.cached(it!!, A.A_CustomEvent_triggeredEvents) { o -> ListView(A.T_vector_int, 0) { (o as CustomEvent).triggeredEvents } }
        })
        // TriggeredEventDefinition.defs
        val defs = ObjGet {
            LuaViews.cached(ParserRef, A.A_TriggeredEventDefinition_defs) { _ ->
                ListView(A.T_vector_TriggeredEventDefinition, A.T_TriggeredEventDefinition) { game()?.eventManager?.hs?.triggeredDefs ?: emptyList() }
            }
        }
        LuaReg.obj(A.A_TriggeredEventDefinition_defs, defs)
        LuaReg.obj(A.A_module_TriggeredEventDefinition_defs, defs)
        LuaReg.str(A.A_TriggeredEventDefinition_event, StrGet { (it as TriggeredEventDefinition).event })
        LuaReg.str(A.A_TriggeredEventDefinition_name, StrGet { (it as TriggeredEventDefinition).name })
    }

    /**
     * Location::spaceImage: the image list the beacon's background came from (the picked text's back list, else the
     * event's <img back>); "" when the beacon uses the default list.
     */
    private fun spaceImage(b: Beacon): String = b.hsSpaceImage ?: b.createdEvent.text?.back ?: b.event.backImg?.name ?: ""
}

/** ShipSystem::NameToSystemId: a `<systemList>` element name to the system id (-1 unknown). */
internal fun systemId(name: String): Int = when (name) {
    "shields" -> 0; "engines" -> 1; "oxygen" -> 2; "weapons" -> 3; "drones" -> 4; "medbay" -> 5; "pilot" -> 6
    "sensors" -> 7; "doors" -> 8; "teleporter" -> 9; "cloaking" -> 10; "artillery" -> 11; "battery" -> 12
    "clonebay" -> 13; "mind" -> 14; "hacking" -> 15; "temporal" -> 20; else -> -1
}
