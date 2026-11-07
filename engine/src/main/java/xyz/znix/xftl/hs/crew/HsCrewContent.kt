// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (ParseCrewNode call from the
// hyperspace.xml parser)
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsCapability
import xyz.znix.xftl.hs.stat.StatBoostDefs
import xyz.znix.xftl.hs.stat.StatBoostManager

/** Loads the `<crew>` races of hyperspace.xml once per Hyperspace session (GameContent construction). */
object HsCrewContent {
    @JvmField var loaded = false

    @JvmStatic
    fun load(blueprints: BlueprintManager) {
        if (!HS.active) return
        CustomCrewManager.reset()
        StatBoostDefs.reset()
        StatBoostManager.reset()
        StatBoostDefs.blueprintList = { name -> (blueprints.getOrNull(name) as? BlueprintList)?.names ?: emptyList() }
        xyz.znix.xftl.hs.stat.AugmentStatBoosts.reset()
        xyz.znix.xftl.hs.stat.AugmentStatBoosts.clearCaches()
        // The weapon blueprints' stat boosts and crew spawns first: the original builds the blueprints before HS
        // parses hyperspace.xml (HsWeapons.loadContent).
        val powersBefore = CustomCrewManager.powerDefs.size
        val resourcesBefore = CustomCrewManager.resourceDefs.size
        xyz.znix.xftl.hs.weapons.HsWeapons.loadContent(blueprints)
        val weaponPowers = CustomCrewManager.powerDefs.size - powersBefore
        val weaponResources = CustomCrewManager.resourceDefs.size - resourcesBefore
        // ParseHyperspaceXml walks the root's children in document order, so the stat boost definitions (and their
        // realBoostIds) of <crew> and <augments> nodes come in that order.
        var nodes = HS.cfg().nodesOf("crew") + HS.cfg().nodesOf("augments")
        val parent = nodes.firstOrNull()?.parent
        if (parent != null && nodes.all { it.parent === parent }) nodes = nodes.sortedBy { parent.indexOf(it) }
        for (node in nodes) {
            if (node.name == "crew") CustomCrewManager.parseCrewNode(node)
            else xyz.znix.xftl.hs.stat.AugmentStatBoosts.parseAugmentsNode(node)
        }
        StatBoostManager.augmentBoosts = xyz.znix.xftl.hs.stat.AugmentStatBoosts::boosts
        StatBoostDefs.weaponsFirst()
        // Saved crew powers and resources name their definition by index: the weapons' few (Alder's Additions has one)
        // keep the numbers after hyperspace.xml's they had before the weapons were parsed first.
        fun <T> weaponsLast(list: ArrayList<T>, from: Int, count: Int, setIndex: (T, Int) -> Unit) {
            if (count <= 0) return
            val moved = ArrayList(list.subList(from, from + count))
            list.subList(from, from + count).clear()
            list.addAll(moved)
            for (i in from until list.size) setIndex(list[i], i)
        }
        weaponsLast(CustomCrewManager.powerDefs, powersBefore, weaponPowers) { d, i -> d.index = i }
        weaponsLast(CustomCrewManager.resourceDefs, resourcesBefore, weaponResources) { d, i -> d.index = i }
        HsCapability.implement("crew")
        StatCrewLua.shareDefinitions()
        loaded = true
    }

    /** WorldManager::PauseLoop: stat boosts and every crew member's powers are recalculated while paused. */
    @JvmStatic
    fun pauseLoop(game: xyz.znix.xftl.game.InGameState) {
        StatBoostManager.onLoop(game)
        for (ship in listOfNotNull(game.player, game.enemy)) for (c in ship.crew) {
            if (c is HsCrewMember) c.ex.calculatePowerDef()
            else if (c is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn && c.hsExtend?.definition() != null) c.hsExtend?.calculatePowerDef()
        }
    }

    /** ShipManager::JumpArrive: crew powers reset (ResetPower) and jump-cleared boosts go. */
    @JvmStatic
    fun jumpArrive(ship: xyz.znix.xftl.Ship) {
        for (c in ship.crew) c.hsExtend?.resetPower()
        StatBoostManager.jumpArrive(ship)
    }

    /**
     * BlueprintManager::GetCrewBlueprint as HS hooks it: a blueprint list gives one of its names
     * (`random32() % size` from [random]), and a name without a crew blueprint gives "human" (recorded).
     */
    @JvmStatic
    fun crewBlueprint(bm: BlueprintManager, name: String, random: kotlin.random.Random): xyz.znix.xftl.crew.CrewBlueprint =
        HsCrew.resolveRace(bm, name, random, recursive = false)

    /**
     * The transformation of an event's `<transformRace>` (CustomEvents.cpp ModifyResources hook, after the events
     * code picked [crew]): GetCrewBlueprint(species) (one list pick, `random32()` drawn from [random]) even when no
     * crew member was found, then originalRace and CrewMember_Extend::TransformRace. Returns the new species.
     */
    @JvmStatic
    fun eventTransformRace(bm: BlueprintManager, crew: xyz.znix.xftl.crew.AbstractCrew?, species: String, random: kotlin.random.Random): String {
        val newSpecies = crewBlueprint(bm, species, random).name
        if (crew != null) {
            val ex = HsCrew.ex(crew)
            ex.originalRace = newSpecies
            ex.transformRaceTo(newSpecies)
        }
        return newSpecies
    }

    /** A new run or a loaded save starts with fresh per-run stat state. */
    @JvmStatic
    fun newRun() {
        if (!HS.active) return
        StatBoostManager.reset()
        // the run keeps the hangar's ShipManager, whose crew drew their ids from the hangar's counter (a loaded save sets
        // the counter from the save afterwards)
        HsCrew.resetIds(HsHangarIds.runBase())
    }
}
