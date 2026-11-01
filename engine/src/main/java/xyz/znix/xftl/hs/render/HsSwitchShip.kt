package xyz.znix.xftl.hs.render

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomShips.cpp (WorldManager::SwitchShip,
// WorldManager::SwitchShipTransfer) and src/game/UserInterface/CommandConsole.cpp (SWITCH, SWITCHALL). This engine is
// AGPL-3.0-or-later.

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.systems.HsSystems

/** The console's ship switch: the player's ship rebuilt from another blueprint in a running game. */
object HsSwitchShip {
    private val copies = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.Ship, xyz.znix.xftl.game.ShipBlueprint>())
    private var ownCopy: xyz.znix.xftl.game.ShipBlueprint? = null
    private var ownCopyShip: xyz.znix.xftl.Ship? = null

    /** The run is over: its player ship is let go. */
    fun endRun() { ownCopy = null; ownCopyShip = null }

    /**
     * ShipManager::myBlueprint: the ship's own copy of its blueprint (a ShipManager member, not the BlueprintManager's
     * entry). The player's copy carries the ship's name (the hangar rename, SwitchShip's literal `fixname`), so Lua's
     * `myBlueprint.name` reads that name; other ships share the blueprint, whose name is their own.
     */
    @JvmStatic
    fun myBlueprint(ship: xyz.znix.xftl.Ship): xyz.znix.xftl.game.ShipBlueprint {
        val game = xyz.znix.xftl.hs.lua.LuaAdapters.inGame()
        if (game == null) return ship.type
        return copies.getOrPut(ship) {
            xyz.znix.xftl.game.ShipBlueprint(ship.type.sourceElement, game.content.datafile).also {
                // OnInit copied the blueprint when the ship was built.
                it.maxHealth = ship.builtMaxHealth
                if (game.player === ship) { ownCopy = it; ownCopyShip = ship } else it.startingReactorPower = ship.purchasedReactorPower
            }
        }
    }

    /** The name the player's copy of the blueprint holds: the ship's name as a literal, null for any other blueprint. */
    @JvmStatic
    fun ownName(bp: xyz.znix.xftl.game.ShipBlueprint): xyz.znix.xftl.GameText? =
        if (bp === ownCopy) ownCopyShip?.displayName?.let { xyz.znix.xftl.GameText.literal(it) } else null
    /** The blueprint `name` names, unless it is unknown (HS: "DEFAULT") or the player's own. */
    private fun target(game: InGameState, name: String): LazyShipBlueprint? {
        val bp = game.blueprintManager.getOrNull(name) as? LazyShipBlueprint ?: return null
        if (bp.name == "DEFAULT" || bp.name == game.player.type.name) return null
        return bp
    }

    /**
     * WorldManager::SwitchShip: ClearLocation, then the ship of the blueprint as a run start builds it (the blueprint's
     * systems, weapons, drones, augments, crew and resources), named after the blueprint.
     */
    @JvmStatic
    fun switchShip(game: InGameState, name: String): Boolean {
        val bp = target(game, name) ?: return false
        val charge = game.player.ftlChargeProgress
        game.hsRebuildPlayerShip(name)
        // the FTL drive stays as it was (oracle sw2-switch s1: JUMP ready right after the switch)
        game.player.ftlChargeProgress = charge
        // name.data = bp->name.GetText(): an enemy blueprint without a <name> reads "should not be seen" (oracle sw2-switch)
        game.player.displayName = bp.real.shipTitle?.let { game.translator[it] } ?: "should not be seen"
        return true
    }

    /**
     * WorldManager::SwitchShipTransfer(name, overrideSystem) (SWITCHALL uses 1). ShipBuilder::GetShip saves the old ship
     * into the new blueprint (ShipManager::SaveToBlueprint(true)), so the rebuilt ship keeps the crew (the same members,
     * HS's crew list is not added again), weapons, drones and augments (weapons and drones past the new slot counts go
     * to the cargo). Systems: 0 the old ones plus the blueprint's, 1 the old ones in place of the blueprint's (each where
     * the new layout has a room for it), 2 the blueprint's; their levels (0, 1) and the reactor carry over. Then the
     * cargo, scrap, fuel, missiles, drone parts, the hull as a percentage (rounded up) of the new maximum and the name.
     * Oracle sw2-swall (MV Kestrel -> PLAYER_SHIP_MANTIS): crew, weapons, cargo, resources, systems and name kept.
     */
    @JvmStatic
    fun switchShipTransfer(game: InGameState, name: String, overrideSystem: Int): Boolean {
        if (overrideSystem < 0 || overrideSystem > 2) return false
        target(game, name) ?: return false
        val old = game.player
        val systems = old.systems.map { HsSystems.nameToId(it.codename) to it.energyLevels }
        val reactor = old.purchasedReactorPower
        val cargo: List<Blueprint> = ArrayList(old.allCargo())
        val weapons = old.hardpoints.mapNotNull { h -> h.weapon?.type }
        val drones = old.drones?.drones?.mapNotNull { it?.type } ?: emptyList()
        val augments = ArrayList(old.augments)
        val crew = old.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === old && it.health > 0f }.map { it.info }
        val scrap = old.scrap
        val fuel = old.fuelCount
        val missiles = old.missilesCount
        val droneParts = old.dronesCount
        val shipName = old.displayName
        val healthRatio = kotlin.math.ceil(old.health * 100f / old.maxHealth).toInt()
        val charge = old.ftlChargeProgress

        game.hsRebuildPlayerShip(name, crew)
        val ship = game.player
        ship.ftlChargeProgress = charge

        // bp->systems: the old ship's systems where the new blueprint has a room for them (1 replaces, 0 adds to)
        if (overrideSystem < 2) {
            val keep = systems.map { it.first }.toSet()
            if (overrideSystem == 1) {
                for (sys in ship.systems.toList())
                    if (HsSystems.nameToId(sys.codename) !in keep) xyz.znix.xftl.hs.systems.HsSystemInstall.removeSystem(ship, sys)
            }
            for (id in keep) if (ship.systems.none { HsSystems.nameToId(it.codename) == id })
                xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, id)
        }
        ship.purchasedReactorPower = reactor
        if (overrideSystem < 2) for ((id, level) in systems) {
            val sys = ship.systems.firstOrNull { HsSystems.nameToId(it.codename) == id } ?: continue
            sys.energyLevels = level.coerceAtMost(sys.maxLevel)
            sys.damagedEnergyLevels = sys.damagedEnergyLevels.coerceIn(0, sys.energyLevels)
        }

        // the saved blueprint's equipment in place of the new blueprint's own
        for (w in ship.hardpoints.mapNotNull { it.weapon?.type }) ship.removeBlueprint(w)
        ship.drones?.let { d -> for (x in d.drones.mapNotNull { it?.type }) ship.removeBlueprint(x) }
        for (a in ArrayList(ship.augments)) ship.removeBlueprint(a)
        for (a in augments) ship.addBlueprint(a, true, false)
        for (bp in cargo) ship.addBlueprint(bp, true, true)
        // weapons and drones beyond the slots (or without the system) go to the cargo, after the old cargo
        for (w in weapons) ship.addBlueprint(w, true, ship.weapons == null)
        for (d in drones) ship.addBlueprint(d, true, ship.drones == null)

        ship.scrap = scrap
        ship.fuelCount = fuel
        if (ship.weapons != null) ship.missilesCount = missiles
        ship.dronesCount = droneParts
        ship.health = ship.maxHealth * healthRatio / 100
        ship.displayName = shipName
        // SaveToBlueprint lists the systems by id, so the rebuilt vSystemList runs in id order (oracle sw2-swall sa-sys)
        xyz.znix.xftl.hs.lua.HsShipLuaState.of(ship).systemOrder.let { o -> o.clear(); o += ship.systems.sortedBy { HsSystems.nameToId(it.codename) } }
        game.updatePlayerCrew()
        return true
    }
}
