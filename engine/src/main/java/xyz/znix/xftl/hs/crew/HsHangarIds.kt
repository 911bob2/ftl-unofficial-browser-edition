// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/SpaceId.cpp (the ShipBuilder::Open hook that
// sets the next space id to 100) and src/game/UserInterface/CustomShipSelect.cpp (ShipBuilder::Open with
// hideFirstShipPage switching to the first custom ship). FTL-Hyperspace is GPL-3.0-or-later; this engine is
// AGPL-3.0-or-later.
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsShips

/**
 * Globals::GetNextSpaceId across the hangar. ShipBuilder::Open sets the counter to 100 and builds the shown ship as a
 * real ShipManager; every ship the hangar shows after that (cycling, a variant, hideFirstShipPage's switch to the first
 * custom ship) is another ShipManager whose crew draw ids, and the run keeps the last one, so its crew start where that
 * ship's build started (oracle sweep-world-ids, MV: Kestrel A's crew 101..103, then the first custom ship's 104..106,
 * which the run keeps). The engine's hangar shows blueprints only, so this replays the draws the builds make, and
 * [replay] builds the ShipManagers the hangar dropped once a run's game exists (their constructor events).
 */
object HsHangarIds {
    /** A hangar ShipManager build: the blueprint and the counter before it. */
    private class Build(val name: String, val base: Int)

    /** The counter before the shown ship's build; the run's crew draw from here. */
    private var base = 100
    private var opened = false
    /** ShipBuilder::currentShipId's blueprint: what the next Open builds first (Kestrel A in a new process). */
    private var current: String? = null
    /** This Open's builds, in order; the last is the shown ship. */
    private val builds = ArrayList<Build>()
    /** Builds the hangar dropped (a switch, hideFirstShipPage's Kestrel A, a hangar opened again) not yet replayed. */
    private val dropped = ArrayList<Build>()
    /** The shown ship when the run was created: the run keeps it, a loaded save drops it. */
    private var runBuild: Build? = null

    /** ShipBuilder::Open: the counter goes to 100 and the current ship is built; hideFirstShipPage then switches. */
    @JvmStatic
    fun open(blueprints: BlueprintManager) {
        if (!HS.active) return
        // a hangar opened again without a run in between: its earlier ships were all built and dropped
        dropped += builds
        builds.clear()
        HsCrew.resetIds(100)
        base = 100
        opened = true
        if (HsShips.hideFirstPage) (blueprints.getOrNull(current ?: "PLAYER_SHIP_HARD") as? xyz.znix.xftl.LazyShipBlueprint)?.let { shown(it.real) }
    }

    /** ShipBuilder::SwitchShip / CreateShip: [blueprint]'s ShipManager is built and draws its ids. */
    @JvmStatic
    fun shown(blueprint: ShipBlueprint) {
        if (!HS.active || !opened) return
        base = HsCrew.spaceIdState()
        current = blueprint.name
        builds += Build(blueprint.name, base)
        HsCrew.resetIds(base + draws(blueprint))
    }

    /** WorldManager::CreateNewGame: the counter the run's crew draw from (100 when no hangar was opened). */
    @JvmStatic
    fun runBase(): Int {
        val b = if (opened) base else 100
        if (opened && builds.isNotEmpty()) {
            dropped += builds.subList(0, builds.size - 1)
            runBuild = builds.last()
        }
        builds.clear()
        opened = false
        base = 100
        return b
    }

    /**
     * The hangar's dropped ShipManagers, built in the run's game before its player ship ([setPlayer] makes each the
     * player while it is built, as the hangar's ship is the player's): CONSTRUCT_SHIP_MANAGER, CONSTRUCT_ROOM,
     * CONSTRUCT_SHIP_SYSTEM (CreateSystems order), the weapons' factories and the blueprint crew's CONSTRUCT_CREWMEMBER
     * with the ids their hangar build drew (oracle sweep-world-ids, MV hideFirstShipPage: Kestrel A's 8 systems and
     * crew 101..103 before the first custom ship's). The builds draw nothing from the run's streams (the original's
     * run seeds its streams after the hangar) and the space id counter is left as the run had it.
     */
    @JvmStatic
    fun replay(game: xyz.znix.xftl.game.InGameState, setPlayer: java.util.function.Consumer<xyz.znix.xftl.Ship?>) {
        val list = ArrayList<Build>(dropped)
        dropped.clear()
        runBuild?.let { if (game.isCurrentlyLoadingSave) list += it }
        runBuild = null
        if (!HS.active || list.isEmpty()) return
        val counter = HsCrew.spaceIdState()
        val browser = ftl.browser.BrowserRandom.snapshot()
        try {
            ftl.browser.NativeRandom.isolated {
                for (b in list) {
                    val bp = (game.blueprintManager.getOrNull(b.name) as? xyz.znix.xftl.LazyShipBlueprint)?.real ?: continue
                    HsCrew.resetIds(b.base)
                    val ship = xyz.znix.xftl.Ship(bp, game, null, null, null)
                    setPlayer.accept(ship)
                    ship.loadDefaultContents()
                    HsDummyCrew.blueprintCrew(ship, true)
                    setPlayer.accept(null)
                }
            }
        } finally {
            setPlayer.accept(null)
            ftl.browser.BrowserRandom.restore(browser)
            HsCrew.resetIds(counter)
        }
    }

    /**
     * The ids a ShipManager build of [bp] draws: one per crew member (the blueprint's crewCount and the HS definition's
     * crew list), one per starting drone and the hacking system's two (oracle sweep-world-y1: AddSystem(hacking) drew 2).
     */
    private fun draws(bp: ShipBlueprint): Int {
        var n = bp.initialCrew.sumOf { it.amount } + HsShips.def(bp.name).crewList.size
        n += bp.initialDrones.size
        if (bp.systems.any { it.systemName == "hacking" && it.availableByDefault }) n += 2
        return n
    }
}

/**
 * StatBoostManager::CreateDummyCrew (StatBoost.cpp, the first MainMenu::Open of the process): a human CrewMember outside
 * every ship, for CalculateStatDummy. Its constructor runs CONSTRUCT_CREWMEMBER with the process's first space id, 101
 * (oracle sweep-world-y1 and -ys2: y-crewctor 101 before the first ship's systems). The engine's menu has no ship to
 * build a crew member in, so it is made in the first player ship a run builds (in no room's list) before that ship's
 * CONSTRUCT_SHIP_MANAGER (Ship's last initialiser), where the scripts see it in the same order.
 */
object HsDummyCrew {
    private var made = false

    @JvmStatic
    fun once(ship: xyz.znix.xftl.Ship) {
        if (made || !HS.luaActive || !ship.isPlayerShip) return
        made = true
        val room = ship.rooms.firstOrNull() ?: return
        val bp = ship.sys.blueprintManager.getOrNull("human") as? xyz.znix.xftl.crew.CrewBlueprint ?: return
        val counter = HsCrew.spaceIdState()
        val crew = bp.hsSpawnSilent(room, xyz.znix.xftl.crew.AbstractCrew.SlotType.CREW)
        HsCrew.ex(crew).selfId = 101
        HsCrew.resetIds(counter)
        xyz.znix.xftl.hs.lua.LuaCrewEvents.constructCrewMember(crew)
    }

    /**
     * A loaded run's player ship: ShipManager::OnInit makes the blueprint's crew (crewCount) before ImportShip replaces
     * them with the saved ones, so each draws a space id and runs CONSTRUCT_CREWMEMBER (oracle sweep-world-ys2:
     * crewctor 104..106, then the saved crew 107..109, which take their saved ids back). Made in no room's list.
     */
    @JvmStatic
    fun loadPhantoms(ship: xyz.znix.xftl.Ship) {
        if (!HS.active || !ship.isPlayerShip) return
        blueprintCrew(ship, false)
    }

    /**
     * ShipManager::OnInit's crew for [ship], made in no room's list: the blueprint's crewCount, and with [hsList] the HS
     * definition's `<crew>` list after it (its races drawn as [xyz.znix.xftl.hs.ship.HsShipState.drawStartingCrew]
     * draws them; only inside [replay], whose draws leave the streams as they were). Each draws a space id and runs
     * CONSTRUCT_CREWMEMBER.
     */
    internal fun blueprintCrew(ship: xyz.znix.xftl.Ship, hsList: Boolean) {
        val room = ship.rooms.firstOrNull() ?: return
        val manager = ship.sys.blueprintManager
        val races = ArrayList<xyz.znix.xftl.crew.CrewBlueprint>()
        for (spec in ship.type.initialCrew) {
            val bp = manager.getOrNull(spec.race) as? xyz.znix.xftl.crew.CrewBlueprint
                ?: manager.getOrNull("human") as? xyz.znix.xftl.crew.CrewBlueprint ?: continue
            repeat(spec.amount) { races += bp }
        }
        // the races only (no names or colours, which would touch the run's used-name list)
        if (hsList) for (p in HsShips.def(ship.type.name).crewList) {
            var species = p.species
            if (p.isList) {
                val list = (manager.getOrNull(p.species) as? xyz.znix.xftl.BlueprintList)?.names ?: emptyList()
                species = if (list.isNotEmpty()) list[ftl.browser.NativeRandom.nextInt(list.size)] else "human"
            }
            ftl.browser.NativeRandom.next31()
            races += HsCrewContent.crewBlueprint(manager, species, ftl.browser.NativeRandom)
        }
        for (bp in races) {
            val crew = bp.hsSpawnSilent(room, xyz.znix.xftl.crew.AbstractCrew.SlotType.CREW)
            HsCrew.ex(crew)
            if (HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.constructCrewMember(crew)
        }
    }
}
