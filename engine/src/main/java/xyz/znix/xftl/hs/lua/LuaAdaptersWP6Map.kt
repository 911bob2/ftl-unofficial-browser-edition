// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1.23.2 / 1b05a07 lua/modules/hyperspace.i (StarMap, Location, Sector,
// SectorDescription, SpaceManager and WorldManager members and their %extend code); the browser engine is AGPL-3.0,
// see docs/HYPERSPACE.md.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.JumpWindow
import xyz.znix.xftl.game.SectorMapWindow
import xyz.znix.xftl.hs.events.HsEventsLua
import xyz.znix.xftl.hs.sector.HsStarMap
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.GameMap
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * WP6 map and sectors: the StarMap, Location, Sector / SectorDescription, SpaceManager and WorldManager members HS
 * 1.23.2 exposes that other adapters leave out (installed last, so a getter replaced here wins).
 * - StarMap: Lua's StarMap is [HsEventsLua.StarMapRef]; state lives on the current [Sector] (pursuitDelay =
 *   fleetAdvanceModifier, dangerZone = dangerZoneCentre, bMapRevealed) and the open map window (hoverLoc,
 *   potentialLoc, bChoosingNewSector); `sectors` is the tree in creation order ([GameMap.sectors] flattened).
 * - Sector objects are [GameMap.SectorInfo] (StarMap.currentSector too, so `currentSector == sectors[i]` holds).
 */
object LuaAdaptersWP6Map {
    private fun game(): InGameState? = LuaAdapters.inGame()
    private fun sector(): Sector? = game()?.currentBeacon?.sector
    private fun jumpWindow(): JumpWindow? = game()?.shipUI?.hsDiagnosticWindow as? JumpWindow
    private fun info(o: Any?): GameMap.SectorInfo = if (o is Sector) o.info else o as GameMap.SectorInfo
    fun sectorInfo(o: Any?): GameMap.SectorInfo = info(o)

    /** The tree StarMap::sectors holds (creation order: start, each column, FINAL). */
    fun sectorsOf(game: InGameState): List<GameMap.SectorInfo> = game.gameMap.hsVisibleSectors()

    fun install() {
        starMap()
        location()
        sectorMembers()
        spaceManager()
        worldManager()
    }

    private fun starMap() {
        val sm = HsEventsLua.StarMapRef
        // StarMap::currentSector as a SectorInfo, like every other Sector pointer here.
        LuaReg.obj(A.A_StarMap_currentSector, ObjGet { game()?.currentBeacon?.sector?.info })
        LuaReg.obj(A.A_StarMap_sectors, ObjGet {
            LuaViews.cached(sm, A.A_StarMap_sectors) { _ -> ListView(A.T_vector_Sector, A.T_Sector) { game()?.let { sectorsOf(it) } ?: emptyList() } }
        })
        // StarMap::bChoosingNewSector: the sector map shows. Writing true opens it (the console's EXIT does the same:
        // bOpen and bChoosingNewSector), false closes it.
        LuaReg.bool(A.A_StarMap_bChoosingNewSector, { game()?.shipUI?.hsDiagnosticWindow is SectorMapWindow }, { _, v ->
            val g = game() ?: return@bool
            val open = g.shipUI.hsDiagnosticWindow is SectorMapWindow
            if (v && !open) g.shipUI.openSectorMap() else if (!v && open) g.shipUI.sectorChosen(null)
        })
        // StarMap::bSecretSector: the current sector is a secret one (StartSecretSector).
        LuaReg.bool(A.A_StarMap_bSecretSector, { sector()?.info?.isSecretSector == true })
        // StarMap::bTutorialGenerated: GenerateMap(bTutorial): the map was made for the tutorial.
        LuaReg.bool(A.A_StarMap_bTutorialGenerated, { game()?.mainGame?.isTutorialRunning == true })
        LuaReg.bool(A.A_StarMap_bMapRevealed, { sector()?.mapRevealed == true }, { _, v -> sector()?.mapRevealed = v })
        // StarMap::dangerZone (Point): the fleet's centre on the map.
        LuaReg.obj(A.A_StarMap_dangerZone, ObjGet {
            val s = sector() ?: return@ObjGet null
            LuaViews.cached(s, A.A_StarMap_dangerZone) { o -> PointValue(0, 0).also { dangerZones[it] = o as Sector } }
                .also { it.x = s.dangerZoneCentre.x; it.y = s.dangerZoneCentre.y }
        })
        LuaReg.num(A.A_StarMap_pursuitDelay, NumGet { (sector()?.fleetAdvanceModifier ?: 0).toDouble() },
            NumSet { _, v -> sector()?.fleetAdvanceModifier = v.toInt() })
        LuaReg.obj(A.A_StarMap_hoverLoc, ObjGet { HsStarMap.hoverLoc?.takeIf { it.sector === sector() } },
            ObjSet { _, v -> HsStarMap.hoverLoc = v as? Beacon; jumpWindow()?.hovered = v as? Beacon })
        LuaReg.obj(A.A_StarMap_potentialLoc, ObjGet { jumpWindow()?.hsPotentialLoc }, ObjSet { _, v -> jumpWindow()?.hovered = v as? Beacon })
        // FocusWindow::bOpen of the StarMap: the beacon or the sector map shows (MV's tutorial waits on it).
        val prevOpen = LuaReg.numGet[A.A_FocusWindow_bOpen]
        LuaReg.num(A.A_FocusWindow_bOpen, NumGet { o ->
            if (o === sm) { val w = game()?.shipUI?.hsDiagnosticWindow; if (w is JumpWindow || w is SectorMapWindow) 1.0 else 0.0 }
            else prevOpen?.get(o) ?: 0.0
        }, LuaReg.numSet[A.A_FocusWindow_bOpen])

        // StarMap::ModifyPursuit(int): pursuitDelay += amount.
        LuaReg.method(A.M_StarMap_ModifyPursuit, LuaMethod {
            sector()?.let { it.fleetAdvanceModifier += arg.int(2) }; 0
        })
        // StarMap::PointToGrid(x, y): Point((int)(x / 110), (int)(y / 110)), one less for a negative coordinate.
        LuaReg.method(A.M_StarMap_PointToGrid, LuaMethod {
            val x = arg.float(2); val y = arg.float(3)
            var gx = (x / Sector.CELL_SIZE.x).toInt(); var gy = (y / Sector.CELL_SIZE.y).toInt()
            if (x < 0f) gx--
            if (y < 0f) gy--
            ret.obj(PointValue(gx, gy), A.T_Point)
        })
        // StarMap:ForceWaitMessage([prim]) (hyperspace.i %extend).
        val forceWait = LuaMethod { n ->
            HsStarMap.forceWaitMessage(if (n >= 2 && !arg.isNil(2)) arg.obj(2) as? LuaPrimitive else null); 0
        }
        LuaReg.method(A.M_StarMap_ForceWaitMessage, forceWait)
        LuaReg.method(A.M_StarMap_ForceWaitMessage__SWIG_0, forceWait)
        LuaReg.method(A.M_StarMap_ForceWaitMessage__SWIG_1, forceWait)

        // Point writes reach the fleet centre through its view.
        val px = LuaReg.numSet[A.A_Point_x]; val py = LuaReg.numSet[A.A_Point_y]
        LuaReg.num(A.A_Point_x, LuaReg.numGet[A.A_Point_x]!!, NumSet { o, v -> px?.set(o, v); push(o) })
        LuaReg.num(A.A_Point_y, LuaReg.numGet[A.A_Point_y]!!, NumSet { o, v -> py?.set(o, v); push(o) })
    }

    /** StarMap::dangerZone views (a Point member: writes move the sector's fleet centre). */
    private val dangerZones = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<PointValue, Sector>())

    private fun push(o: Any?) {
        val p = o as? PointValue ?: return
        val s = dangerZones[p] ?: return
        s.dangerZoneCentre.x = p.x; s.dangerZoneCentre.y = p.y
    }

    private fun location() {
        fun b(o: Any?) = o as Beacon
        LuaReg.bool(A.A_Location_beacon, { b(it).isExit }, { o, v -> b(o).isExit = v })
        LuaReg.bool(A.A_Location_known, { b(it).known }, { o, v -> b(o).hsKnown = v })
        LuaReg.num(A.A_Location_visited, NumGet { b(it).hsVisits.toDouble() }, NumSet { o, v ->
            b(o).hsVisits = v.toInt(); if (v.toInt() == 0) b(o).visited = false else if (!b(o).visited) b(o).visited = true
        })
        // Location::dangerZone: the fleet holds the beacon (StarMap::OvertakeLocation).
        LuaReg.bool(A.A_Location_dangerZone, { b(it).isOvertaken }, { o, v -> b(o).isOvertaken = v })
        // Location::nebula: inside a map nebula cloud (GenerateNebulas).
        LuaReg.bool(A.A_Location_nebula, { b(it).inNebula }, { o, v -> b(o).inNebula = v })
        // Location::boss: the flagship is there (UpdateBoss moves the flag); a script's write is kept until read.
        LuaReg.bool(A.A_Location_boss, { o -> b(o).hsBoss ?: b(o).sector.bosses.any { it.beacon === o } }, { o, v -> b(o).hsBoss = v })
        // Location::fleetChanging: the last stand location the fleet is taking (GenerateMap's random location).
        LuaReg.bool(A.A_Location_fleetChanging, { b(it).browserFleetThreatened }, { o, v -> b(o).browserFleetThreatened = v })
        // Location::spaceImage / planetImage / planet / space: "NONE" and empty until WorldManager::CreateLocation
        // stores the arrival's scene ([Beacon.hsScene]: the backdrop's image list, the planet); a changeBackground
        // replaces spaceImage, later events leave the rest (oracle wp6-map-env, wp6-map-inf).
        fun scene(o: Any?) = b(o).takeIf { it.visited }?.hsScene
        LuaReg.str(A.A_Location_spaceImage, StrGet { o -> b(o).hsSpaceImage ?: scene(o)?.list ?: "NONE" }, StrSet { o, v -> b(o).hsSpaceImage = v })
        LuaReg.str(A.A_Location_planetImage, StrGet { o -> scene(o)?.planet?.first ?: "NONE" })
        LuaReg.obj(A.A_Location_planet, ObjGet { o -> imageDesc(o!!, A.A_Location_planet, scene(o)?.planet) })
        // Location::space: the backdrop's ImageDesc (its texture and size, at 0,0).
        LuaReg.obj(A.A_Location_space, ObjGet { o -> imageDesc(o!!, A.A_Location_space, scene(o)?.back?.let { backDesc(it) }) })
    }

    /** A backdrop's ImageDesc values: its texture's size, at (0, 0), unrotated. */
    private fun backDesc(path: String): Pair<String, IntArray> {
        val t = LuaGraphics.texture(path)
        return path to intArrayOf(t.width, t.height, 0, 0, 0)
    }

    /** An ImageDesc view of (texture path, [w, h, x, y, rot]), refreshed on every read. */
    private fun imageDesc(owner: Any, key: Int, d: Pair<String?, IntArray>?): LuaStruct {
        val s = LuaViews.cached(owner, key) { LuaStructs.create(A.T_ImageDesc) }
        // A script's write stays until the engine's own image changes.
        val now = listOf(d?.first) + (d?.second?.toList() ?: emptyList())
        if (filled[s] != now) { fill(s, d); filled[s] = now }
        return s
    }

    private val filled = java.util.WeakHashMap<LuaStruct, List<Any?>>()

    private fun fill(s: LuaStruct, d: Pair<String?, IntArray>?) {
        val v = d?.second ?: IntArray(5)
        s.values[A.A_ImageDesc_tex] = d?.first?.let { LuaGraphics.texture(it) }
        s.values[A.A_ImageDesc_resId] = 0.0
        s.values[A.A_ImageDesc_w] = v[0].toDouble()
        s.values[A.A_ImageDesc_h] = v[1].toDouble()
        s.values[A.A_ImageDesc_x] = v[2].toDouble()
        s.values[A.A_ImageDesc_y] = v[3].toDouble()
        s.values[A.A_ImageDesc_rot] = v[4].toDouble()
    }

    private fun sectorMembers() {
        LuaReg.num(A.A_Sector_level, NumGet { info(it).columnNumber.toDouble() })
        LuaReg.bool(A.A_Sector_visited, { o -> game()?.visitedSectors?.contains(info(o)) == true })
        LuaReg.obj(A.A_Sector_description, ObjGet {
            LuaViews.cached(info(it), A.A_Sector_description) { o -> LuaSectorDescription(o as GameMap.SectorInfo) }
        })
        fun text(owner: Any, key: Int, t: xyz.znix.xftl.GameText): LuaStruct =
            LuaViews.cached(owner, key) {
                LuaStructs.create(A.T_TextString).also { s ->
                    s.onWrite = {
                        val data = s.values[A.A_TextString_data] as? String ?: ""
                        val g = if (s.values[A.A_TextString_isLiteral] == true) xyz.znix.xftl.GameText.literal(data) else xyz.znix.xftl.GameText.localised(data)
                        val info = (owner as LuaSectorDescription).info
                        if (key == A.A_SectorDescription_name) info.hsName = g else info.hsShortName = g
                    }
                }
            }.also {
                it.values[A.A_TextString_data] = t.hsData
                it.values[A.A_TextString_isLiteral] = t.hsIsLiteral
            }
        LuaReg.obj(A.A_SectorDescription_name, ObjGet { val d = it as LuaSectorDescription; text(d, A.A_SectorDescription_name, d.info.displayName) })
        LuaReg.obj(A.A_SectorDescription_shortName, ObjGet { val d = it as LuaSectorDescription; text(d, A.A_SectorDescription_shortName, d.info.displayShortName) })
    }

    /** SpaceManager::currentBack / currentPlanet views of the current scene. */
    private val back = Any()
    private val planet = Any()

    private fun spaceManager() {
        fun env() = game()?.let { it.currentBeacon.getEnvironment(it) }
        // SpaceManager::bPDS (an enemy-only ASB clears it without a live enemy, 0x41b920) and envTarget (2 after
        // ClearEnvironment 0x419fd0; SetPlanetaryDefense's target stays after bPDS clears).
        LuaReg.bool(A.A_SpaceManager_bPDS, { env()?.hsPdsOn == true })
        LuaReg.num(A.A_SpaceManager_envTarget, NumGet { ((env()?.layers?.firstNotNullOfOrNull { it as? xyz.znix.xftl.environment.AsbEnvironment })?.pdsTarget ?: 2).toDouble() })
        // SpaceManager::currentBack is the backdrop's GL_Texture (not an ImageDesc).
        LuaReg.obj(A.A_SpaceManager_currentBack, ObjGet { env()?.hsBackdropPath?.let { LuaGraphics.texture(it) } })
        LuaReg.obj(A.A_SpaceManager_currentPlanet, ObjGet { imageDesc(planet, A.A_SpaceManager_currentPlanet, env()?.hsPlanetDesc()) })
        // SpaceManager::SwitchBackground(name) -> ImageDesc (the new backdrop's texture, the rest 0).
        LuaReg.method(A.M_SpaceManager_SwitchBackground, LuaMethod {
            val path = env()?.hsSwitchBackground(arg.str(2) ?: "")
            val d = LuaStructs.create(A.T_ImageDesc)
            fill(d, path?.let { it to IntArray(5) })
            ret.obj(d, A.T_ImageDesc)
        })
        // SpaceManager::UpdatePlanetImage(): the planet from currentPlanet's texture, x, y and rotation.
        LuaReg.method(A.M_SpaceManager_UpdatePlanetImage, LuaMethod {
            val e = env() ?: return@LuaMethod 0
            val s = LuaViews.cached(planet, A.A_SpaceManager_currentPlanet) { LuaStructs.create(A.T_ImageDesc) }
            val tex = s.values[A.A_ImageDesc_tex] as? LuaTexture
            fun n(a: Int) = ((s.values[a] as? Double) ?: 0.0).toInt()
            e.hsUpdatePlanet(tex?.path, n(A.A_ImageDesc_x), n(A.A_ImageDesc_y), n(A.A_ImageDesc_rot))
            0
        })
    }

    private fun worldManager() {
        // WorldManager::currentDifficulty (0 easy, 1 normal, 2 hard).
        LuaReg.num(A.A_WorldManager_currentDifficulty, NumGet { (game()?.difficulty?.ordinal ?: 1).toDouble() })
        // WorldManager::playerCrewCount / killedCrew / playerHull: CreateLocation's snapshot at the arrival (the start
        // of a beacon, for the Mantis slaughter achievement): the crew count, the run's killed_crew stat and the hull.
        LuaReg.num(A.A_WorldManager_playerCrewCount, NumGet { (stats().crew ?: 0).toDouble() }, NumSet { _, v -> stats().crew = v.toInt() })
        LuaReg.num(A.A_WorldManager_killedCrew, NumGet { (stats().killed ?: 0).toDouble() }, NumSet { _, v -> stats().killed = v.toInt() })
        LuaReg.num(A.A_WorldManager_playerHull, NumGet { (stats().hull ?: 0).toDouble() }, NumSet { _, v -> stats().hull = v.toInt() })
    }

    /**
     * WorldManager's arrival snapshot (CreateLocation 1.6.9: playerCrewCount = the factory's player crew count,
     * killedCrew = the "killed_crew" flag, playerHull = the hull), taken when the ship arrives
     * ([ftl.browser.BrowserAchievementProgress.beaconArrived]), not when a script reads it; a script's write stays until
     * the next arrival. Before the run's first arrival (the start beacon) the first read takes the values.
     */
    class Stats {
        @JvmField var serial: Int = -2
        @JvmField var of: InGameState? = null
        @JvmField var crew: Int? = null
        @JvmField var killed: Int? = null
        @JvmField var hull: Int? = null
    }

    private val stats = Stats()

    private fun stats(): Stats {
        val g = game() ?: return stats
        val ach = g.browserAchievements
        val key = if (ach.arrivalHull != null) ach.arrivalSerial else -1
        if (stats.serial != key || stats.of !== g) {
            stats.serial = key; stats.of = g
            val p = g.player
            stats.crew = ach.arrivalCrew ?: (p?.crew?.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>()?.count { it.ownerShip === p && it.health > 0f } ?: 0)
            stats.hull = ach.arrivalHull ?: (p?.health ?: 0)
            stats.killed = if (ach.arrivalHull != null) ach.arrivalKilled else ach.killedCrew
        }
        return stats
    }
}
