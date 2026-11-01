package xyz.znix.xftl

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.augments.AugEngiMedbots
import xyz.znix.xftl.augments.AugFireExtinguishers
import xyz.znix.xftl.augments.AugPreigniter
import xyz.znix.xftl.augments.AugSlugGel
import xyz.znix.xftl.augments.AugZoltanShield
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.*
import kotlin.random.Random

class BlueprintManager {
    val blueprints: Map<String, IBlueprint>
    val itemBlueprints: Map<String, ItemBlueprint>

    /**
     * Vanilla mode only: the blueprints of the AE files. The original loads all seven blueprint files at start-up
     * whatever the mode (CApp::OnInit calls BlueprintManager::LoadBlueprints 0x60f730 for blueprints, names,
     * autoBlueprints, dlcPirateBlueprints, dlcBlueprints, dlcBlueprintsOverwrite and bosses, 0x403b08..0x403ea4), so a
     * vanilla game still resolves an AE name such as SHIPS_LANIUS; it never picks them at random. They are kept out of
     * [blueprints] (pools, hangar) and only answer lookups by name.
     */
    private val aeOnly = HashMap<String, IBlueprint>()

    private constructor(enableAE: Boolean, loader: (BlueprintManager) -> Unit) {
        blueprints = HashMap()
        itemBlueprints = HashMap()

        // A content load owns the HS shield-drone definition registry; parse duplicate nodes in file order.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsShieldDrones.reset()
        // Load all the blueprints
        loader(this)

        // If AE is enabled, rename all the OVERRIDE blueprints to
        // remove that prefix - thus they'll be used instead of the
        // original ones.
        if (enableAE) {
            // Use toList to duplicate the entries list, since we'll
            // be mutating the main blueprints map.
            for ((name, bp) in blueprints.entries.toList()) {
                if (!name.startsWith(AE_PREFIX))
                    continue
                blueprints[name.removePrefix(AE_PREFIX)] = bp
            }
        }

        // Remove the now-redundant override entries - this is probably
        // unnecessary, but it'll ensure we don't end up accidentally
        // using something from AE in non-AE mode.
        val toRemove = blueprints.keys.filter { it.startsWith(AE_PREFIX) }
        for (name in toRemove) {
            blueprints.remove(name)
        }

        if (enableAE) {
            val ionCrew = Element("crewBlueprint").setAttribute("name", "boarder_ion")
            ionCrew.addContent(Element("cost").setText("0"))
            blueprints["boarder_ion"] = xyz.znix.xftl.crew.CrewBlueprint(ionCrew)
        }

        // Ghost boarders (vanilla events) have no blueprint in the DAT; the
        // native lookup falls back to a default CrewBlueprint carrying only
        // the race name, so it has no colours, no rarity and never sells.
        if ("ghost" !in blueprints) {
            val ghost = Element("crewBlueprint").setAttribute("name", "ghost")
            ghost.addContent(Element("cost").setText("0"))
            blueprints["ghost"] = CrewBlueprint(ghost)
        }

        // Remove any blueprints from lists that don't actually exist
        for (bp in blueprints.values + aeOnly.values) {
            if (bp !is BlueprintList) continue
            bp.cleanup()
        }

        // Hyperspace: ParseVanillaShipNode for every ship blueprint (FTL's file order), then <ships> of hyperspace.xml.
        hsShipElements?.let { files ->
            xyz.znix.xftl.hs.ship.HsShips.build(HS_BLUEPRINT_ORDER.flatMap { files[it] ?: emptyList() })
            hsShipElements = null
        }
    }

    /** Hyperspace only: the `<shipBlueprint>` elements of each loaded file, for [xyz.znix.xftl.hs.ship.HsShips.build]. */
    private var hsShipElements: HashMap<String, MutableList<Element>>? = null

    constructor(df: Datafile, enableAE: Boolean) : this(enableAE, {
        it.loadFile(df, "blueprints.xml")
        it.loadFile(df, "autoBlueprints.xml")
        it.loadFile(df, "bosses.xml")
        it.loadFile(df, "dlcPirateBlueprints.xml")
        if (enableAE) {
            it.loadFile(df, "dlcBlueprints.xml")
            it.loadFile(df, "dlcBlueprintsOverwrite.xml")
            // Shared pirate definitions were already loaded above.
        } else {
            it.loadAeOnly(df, listOf("dlcPirateBlueprints.xml", "dlcBlueprints.xml", "dlcBlueprintsOverwrite.xml"))
        }
    })

    /**
     * Load the AE files into [aeOnly]: what they add under a new name (not the OVERRIDE_ entries, which only AE
     * switches to); the vanilla definitions they share a name with stay as they are.
     */
    private fun loadAeOnly(df: Datafile, files: List<String>) {
        val main = blueprints as HashMap<String, IBlueprint>
        val before = HashMap(main)
        val itemsBefore = HashMap(itemBlueprints)
        for (name in files)
            loadDocument(df, df.parseXML(df["data/$name"]).rootElement)
        for ((name, bp) in main.entries.toList()) {
            val old = before[name]
            if (old != null) {
                main[name] = old
            } else {
                main.remove(name)
                if (!name.startsWith(AE_PREFIX))
                    aeOnly[name] = bp
            }
        }
        (itemBlueprints as HashMap).clear()
        (itemBlueprints as HashMap).putAll(itemsBefore)
    }

    // Required to bind sounds to the weapons
    fun finishLoading(content: InGameState.GameContent) {
        for (bp in blueprints.values + aeOnly.values) {
            if (bp !is Blueprint)
                continue

            xyz.znix.xftl.hs.HsLoad.item("blueprints", { "finishSetup ${bp.name}" }) { bp.finishSetup(content) }
        }
    }

    operator fun get(name: String): IBlueprint = getOrNull(name) ?: error("Unknown blueprint $name")

    fun getOrNull(name: String): IBlueprint? = blueprints[name] ?: aeOnly[name]

    /** The weapon of an artillery that names none that exists: it is there and never charges. */
    val inertWeapon: xyz.znix.xftl.weapons.AbstractWeaponBlueprint by lazy {
        val elem = Element("weaponBlueprint").setAttribute("name", "")
        for ((name, value) in listOf("type" to "LASER", "damage" to "0", "shots" to "1", "cooldown" to "100000", "power" to "1", "weaponArt" to "", "image" to ""))
            elem.addContent(Element(name).setText(value))
        LaserBlueprint(elem)
    }

    /** The crew race a ship or event names: an unknown one is a human, as the original's GetCrewBlueprint gives. */
    fun crewOrHuman(name: String): xyz.znix.xftl.crew.CrewBlueprint =
        getOrNull(name) as? xyz.znix.xftl.crew.CrewBlueprint ?: this["human"] as xyz.znix.xftl.crew.CrewBlueprint

    fun getShip(name: String): ShipBlueprint {
        val blueprint = this[name] as LazyShipBlueprint
        return blueprint.real
    }


    private fun loadFile(df: Datafile, name: String) {
        val file = df["data/$name"]
        val parseXML = df.parseXML(file)
        if (name == "dlcPirateBlueprints.xml" && !ftl.browser.BrowserFiles.enableAE) {
            for (elem in parseXML.rootElement.children.toList()) if (!elem.getAttributeValue("name").endsWith("_P")) parseXML.rootElement.removeContent(elem)
        }
        if (xyz.znix.xftl.hs.HS.active) {
            val files = hsShipElements ?: HashMap<String, MutableList<Element>>().also { hsShipElements = it }
            files[name] = parseXML.rootElement.getChildren("shipBlueprint").toMutableList()
        }
        loadDocument(df, parseXML.rootElement)
    }

    /** Diagnostics: the blueprints of one more document (an oracle probe's authored append). */
    fun diagnosticAppend(df: Datafile, rootElement: Element) = loadDocument(df, rootElement)

    private fun loadDocument(df: Datafile, rootElement: Element) {
        val mutableBlueprints = blueprints as HashMap<String, IBlueprint>
        for (elem in rootElement.children) {
            // The name 'drones' conflicts with the drones system, and it's the only such name
            // conflict. Since we only need them for stores, put them in a separate list.
            if (elem.name == "itemBlueprint") {
                val item = xyz.znix.xftl.hs.HsLoad.item("blueprints", { "itemBlueprint" }) { ItemBlueprint(elem) } ?: continue
                (itemBlueprints as HashMap)[item.name] = item
                continue
            }

            val bp = xyz.znix.xftl.hs.HsLoad.item("blueprints", { "${elem.name} ${elem.getAttributeValue("name")}" }) { when (elem.name) {
                "blueprintList" -> buildList(elem)
                "weaponBlueprint" -> buildWeaponBlueprint(elem)
                "droneBlueprint" -> buildDroneBlueprint(elem)
                "systemBlueprint" -> buildSystemBlueprint(elem)
                "crewBlueprint" -> buildCrewBlueprint(elem)
                "augBlueprint" -> buildAugmentBlueprint(elem)

                // We don't load the whole ShipBlueprint now for performance
                // reasons, as it loads another XML file.
                "shipBlueprint" -> LazyShipBlueprint(elem, df)

                // Ignore unknown blueprints
                else -> null
            } } ?: continue

            val bpName = elem.requireAttributeValue("name")
            if (mutableBlueprints.containsKey(bpName)) {
                println("Warning: duplicate blueprint name '$bpName'")
            }
            mutableBlueprints[bpName] = bp
        }
    }

    private fun buildList(elem: Element): IBlueprint {
        val items = ArrayList<String>()

        for (node in elem.children) {
            check(node.name == "name")
            items += node.textTrim
        }

        return BlueprintList(items, this)
    }

    private fun buildWeaponBlueprint(elem: Element): IBlueprint {
        val type = elem.getChildTextTrim("type")

        return when (type) {
            "LASER" -> LaserBlueprint(elem)
            "MISSILES" -> MissileBlueprint(elem)
            "BEAM" -> BeamBlueprint(elem)
            "BOMB" -> BombBlueprint(elem)
            "BURST" -> FlakBlueprint(elem)
            else -> UknWeaponBlueprint(elem)
        }
    }

    private fun buildDroneBlueprint(elem: Element): IBlueprint {
        val blueprint = DroneBlueprint(elem)
        // CustomDrones.cpp ProcessDroneBlueprint catches extension parse errors after native construction.
        // Keep the valid base blueprint (and the previous custom definition) on a malformed extension.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.HsLoad.item("shield-drone", { blueprint.name }) {
            xyz.znix.xftl.hs.weapons.HsShieldDrones.parse(elem)
        }
        return blueprint
    }

    private fun buildAugmentBlueprint(elem: Element): IBlueprint {
        return when (val name = elem.getAttributeValue("name")) {
            AugEngiMedbots.NAME -> AugEngiMedbots(elem)
            AugPreigniter.NAME -> AugPreigniter(elem)
            AugFireExtinguishers.NAME -> AugFireExtinguishers(elem)
            AugZoltanShield.NAME -> AugZoltanShield(elem)

            // Augments that use the default AugmentBlueprint and are implemented
            // as part of another system should be explicitly handled here.
            AugmentBlueprint.AUTOMATED_RELOADERS -> AugmentBlueprint(elem)
            AugmentBlueprint.LONG_RANGE_SCANNERS -> AugmentBlueprint(elem)
            AugmentBlueprint.RECONSTRUCTIVE_TELEPORT -> AugmentBlueprint(elem)
            AugmentBlueprint.OXYGEN_MASKS -> AugmentBlueprint(elem)
            AugmentBlueprint.BACKUP_DNA -> AugmentBlueprint(elem)
            AugmentBlueprint.BATTERY_CHARGER -> AugmentBlueprint(elem)
            AugmentBlueprint.SHIELD_CHARGE_BOOSTER -> AugmentBlueprint(elem)
            AugmentBlueprint.STEALTH_WEAPONS -> AugmentBlueprint(elem)
            AugmentBlueprint.ROCK_ARMOR -> AugmentBlueprint(elem)
            AugmentBlueprint.SLUG_GEL -> AugSlugGel(elem)
            AugmentBlueprint.CREW_STIMS -> AugmentBlueprint(elem)
            AugmentBlueprint.SYSTEM_CASING -> AugmentBlueprint(elem)
            AugmentBlueprint.CRYSTAL_SHARDS -> AugmentBlueprint(elem)
            AugmentBlueprint.DRONE_SPEED -> AugmentBlueprint(elem)
            AugmentBlueprint.SCRAP_COLLECTOR -> AugmentBlueprint(elem)
            AugmentBlueprint.ION_ARMOR -> AugmentBlueprint(elem)
            AugmentBlueprint.FTL_BOOSTER -> AugmentBlueprint(elem)
            AugmentBlueprint.REPAIR_ARM -> AugmentBlueprint(elem)
            AugmentBlueprint.HULL_ARMOR -> AugmentBlueprint(elem)
            AugmentBlueprint.ADV_HULL_ARMOR -> AugmentBlueprint(elem)
            AugmentBlueprint.EXPLOSIVE_REPLICATOR -> AugmentBlueprint(elem)
            AugmentBlueprint.FTL_JAMMER -> AugmentBlueprint(elem)
            AugmentBlueprint.DEFENSE_SCRAMBLER -> AugmentBlueprint(elem)
            AugmentBlueprint.DRONE_RECOVERY -> AugmentBlueprint(elem)
            AugmentBlueprint.FLEET_DISTRACTION -> AugmentBlueprint(elem)
            AugmentBlueprint.HACKING_STUN -> AugmentBlueprint(elem)
            AugmentBlueprint.ZOLTAN_BYPASS -> AugmentBlueprint(elem)

            // Adv. FTL Navigation's mechanic (jumping to visited beacons)
            // is implemented by the jump map; the stasis pod is an event
            // quest prop. Neither has a passive effect to implement.
            AugmentBlueprint.ADVANCED_FTL_NAVIGATION -> AugmentBlueprint(elem)
            AugmentBlueprint.STASIS_POD -> AugmentBlueprint(elem)

            // The Lifeform Scanner's mechanic (sensing enemy crew through
            // the hull) is implemented by the vision code in Ship.
            AugmentBlueprint.LIFE_SCANNER -> AugmentBlueprint(elem)

            else -> {
                println("WARNING: Adding unknown augment '$name'")
                AugmentBlueprint(elem)
            }
        }
    }

    private fun buildSystemBlueprint(elem: Element): IBlueprint {
        return SystemBlueprint(elem)
    }

    private fun buildCrewBlueprint(elem: Element): IBlueprint {
        return CrewBlueprint(elem)
    }

    companion object {
        const val AE_PREFIX = "OVERRIDE_"

        /**
         * Create a version of [BlueprintManager] for use in automated tests,
         * loading a custom set of blueprint XML files.
         */
        /** CApp::OnInit's LoadBlueprints order (0x403b08..0x403ea4), which ParseVanillaShipNode sees. */
        private val HS_BLUEPRINT_ORDER = listOf("blueprints.xml", "autoBlueprints.xml", "dlcPirateBlueprints.xml",
            "dlcBlueprints.xml", "dlcBlueprintsOverwrite.xml", "bosses.xml")

        fun createForTests(df: Datafile, files: List<Document>): BlueprintManager {
            return BlueprintManager(true) {
                for (doc in files) {
                    it.loadDocument(df, doc.rootElement)
                }
            }
        }
    }
}

interface IBlueprint {
    fun resolve(random: Random = ftl.browser.BrowserRandom): Blueprint
    fun list(): List<Blueprint>
}

class BlueprintList(private val blueprints: ArrayList<String>, private val manager: BlueprintManager) : IBlueprint {
    /** The names of the entries, in file order. */
    val names: List<String> get() = blueprints

    override fun resolve(random: Random): Blueprint = manager[blueprints.random(random)].resolve(random)
    override fun list(): List<Blueprint> = blueprints.flatMap { manager.getOrNull(it)?.list().orEmpty() }

    fun cleanup() {
        val toRemove = blueprints.filter { manager.getOrNull(it) == null }

        for (item in toRemove) {
            println("Warning: Removing non-existant blueprint $item from blueprint list!")
            blueprints.remove(item)
        }
    }
}

open class Blueprint(elem: Element) : IBlueprint {
    val name = elem.requireAttributeValue("name")
    /** Description::rarity; var: Hyperspace scripts write it (Lua `desc.rarity`), [xmlRarity] is the file's. */
    val xmlRarity: Int? = elem.getChild("rarity")?.textTrim?.let { xyz.znix.xftl.weapons.NativeNumbers.atoi(it) }
    var rarity: Int? = xmlRarity

    /** Description::cost written by a Hyperspace script (the store price and the rest of the game read through [cost]). */
    @JvmField var hsCost: Int? = null

    // var: Hyperspace scripts rewrite Blueprint::desc's TextStrings (MV's custom gatling name)
    var title = elem.getGameTextChild("title")
    var short = elem.getGameTextChild("short")
    var desc = elem.getGameTextChild("desc")

    // Note the tip is always localised
    val tip = elem.getChildTextTrim("tip")?.let { GameText.localised(it) }

    // Description::bp (`<bp>`) and Description::tooltip (`<tooltip>`, a literal text), read by Hyperspace scripts
    val hsBp: Int = elem.getChildTextTrim("bp")?.toIntOrNull() ?: 0
    val hsTooltip: String = elem.getChildTextTrim("tooltip") ?: ""

    /**
     * Get this item's purchase price (the price you can buy it at, not the
     * price you sell it at) in scrap. Returns null if the item doesn't
     * have a price.
     */
    open val cost: Int? = null

    fun translateTitle(game: InGameState): String = title?.let { game.translator[it] } ?: "MISSING TITLE: $name"
    fun translateShort(game: InGameState): String = short?.let { game.translator[it] } ?: "MISSING SHORT: $name"

    override fun resolve(random: Random): Blueprint = this
    override fun list(): List<Blueprint> = listOf(this)

    /**
     * Finish setting up this blueprint, loading any other
     * required resources (eg, sounds).
     */
    open fun finishSetup(content: InGameState.GameContent) {
    }
}

/**
 * This is for fuel/drones/missiles, and serves to make their descriptions available.
 */
class ItemBlueprint(elem: Element) : Blueprint(elem) {
    private val xmlCost: Int = elem.getChildTextTrim("cost")!!.toInt()
    override val cost: Int get() = hsCost ?: xmlCost
}

/**
 * This is a stub that's created instead of a real ship blueprint,
 * which is significantly slower to load.
 *
 * Those can and should only be loaded when they're required.
 */
class LazyShipBlueprint(
    private val elem: Element, private val df: Datafile
) : Blueprint(elem) {

    val real: ShipBlueprint by lazy { ShipBlueprint(elem, df) }

    /** Whether the game files hold this ship's layout. */
    val hasLayout: Boolean get() = df.getOrNull("data/$layout.xml") != null && df.getOrNull("data/$layout.txt") != null

    // Load a few bits and pieces that are used in places where
    // we don't need to load the full ship.
    val layout: String = elem.getAttributeValue("layout")
    val img: String = elem.getAttributeValue("img")
    val shipClass: GameText? = elem.getGameTextChild("class")
    val shipTitle: GameText? = elem.getGameTextChild("name")

    // The sector window the blueprint may be picked in (ShipBlueprint 0x60c7a0 reads minSector/maxSector;
    // the constructor 0x760db0 defaults them to 0 and INT_MAX).
    val minSector: Int = elem.getChildTextTrim("minSector")?.toIntOrNull() ?: 0
    val maxSector: Int = elem.getChildTextTrim("maxSector")?.toIntOrNull() ?: Int.MAX_VALUE
}
