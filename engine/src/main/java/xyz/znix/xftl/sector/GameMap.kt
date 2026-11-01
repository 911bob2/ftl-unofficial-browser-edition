package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InGameState.GameContent
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import ftl.browser.NativeRandom

/**
 * Represents the overall map of the game. This loads and parses the sector data (which defines all the
 * different types of sectors) and generates a random set of them to be used in the sector map.
 */
class GameMap private constructor(df: Datafile, private val eventManager: EventManager, enableAE: Boolean) {

    /**
     * The seed this map's sector tree was generated from (StarMap +0x9a4).
     * StarMap::NewGame (0x5f9e00) takes it from the generator and every
     * later LoadGame (0x5fb9a0) regenerates the identical tree from it; it
     * is also the vanilla save's `sectorTreeSeed`.
     */
    var generationSeed: Int = 0
        private set

    private val sectorClasses = HashMap<SectorClass, List<SectorType>>()
    private val sectorTypes = HashMap<String, SectorType>()
    private val descriptionElements = HashMap<String, Element>()

    /** Hyperspace: every `<sectorType>` list by name and the `<sectorMap>` generators (null in vanilla). */
    private var hsSectorLists: Map<String, List<String>>? = null
    private var hsSectorMap: xyz.znix.xftl.hs.sector.HsSectorMap? = null
    private val hsDlc = enableAE

    /** Hyperspace infinite enabled (Infinite.cpp g_infiniteMode, StarMap::bInfiniteMode): no FINAL, columns keep coming. */
    val hsInfinite: Boolean = eventManager.hs != null && xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().infiniteMode

    private val specialEvents = SpecialEvents(
        eventManager["START_GAME"],
        eventManager["NEUTRAL"],
        eventManager["FINISH_BEACON"],
        eventManager["FINISH_BEACON_NEBULA"],
        eventManager["FEDERATION_BASE"]
    )

    val sectors: List<List<SectorInfo>> = ArrayList()
    private val secretSectors = ArrayList<SectorInfo>()

    /** The Crystal detour replaces the current map, retaining the normal route. */
    @JvmOverloads
    fun createSecretSector(origin: SectorInfo, typeName: String = "CRYSTAL_HOME"): SectorInfo {
        require(!origin.isSecretSector)
        // Hyperspace <secretSector>NAME</secretSector> (StarMap::StartSecretSector): GetSpecificSector(NAME).
        val type = sectorTypes[typeName] ?: sectorTypes["CRYSTAL_HOME"] ?: error("Missing official CRYSTAL_HOME sector")
        val secret = SectorInfo(origin.columnNumber, origin.columnIndex, type, SectorClass.CIVILIAN)
        secret.secretOrigin = origin
        secret.nextSectors.addAll(origin.nextSectors)
        secretSectors.add(secret)
        return secret
    }

    init {
        val xml = df.parseXML(df["data/sector_data.xml"])

        val namedSectorTypes = HashMap<String, List<String>>()

        // GenerateSectorTree (0x5f0ef0) parses data/sector_data.xml on every tree, through EventsParser::ProcessBaseNode;
        // HS hooks that (ParseVanillaBaseNode) to build its CustomSector for each sectorDescription.
        val hs = eventManager.hs
        for (elem in xml.rootElement.children) {
            if (!enableAE && (elem.getAttributeValue("name") == "LANIUS_SECTOR" || elem.getAttributeValue("name")?.startsWith(BlueprintManager.AE_PREFIX) == true)) continue
            if (hs != null) hs.parseVanillaBaseNode(elem)
            when (elem.name) {
                "sectorType" -> parseSectorType(elem, namedSectorTypes)
                "sectorDescription" -> parseSectorDescription(elem)
                "sectorMap" -> if (hs == null) println("[warn] Unknown node type '${elem.name}' in sector_data.xml")
                    else (hsSectorMap ?: xyz.znix.xftl.hs.sector.HsSectorMap().also { hsSectorMap = it }).parse(elem)
                else -> println("[warn] Unknown node type '${elem.name}' in sector_data.xml")
            }
        }

        // The first sector draws its events from STANDARD_SPACE but is named
        // as the civilian sector (GenerateSectorTree, 0x5f0ef0).
        val standardSpace = descriptionElements["STANDARD_SPACE"]
        val civilian = sectorTypes["CIVILIAN_SECTOR"]
        if (standardSpace != null && civilian != null)
            sectorTypes["STANDARD_SPACE"] = SectorType(eventManager, standardSpace, civilian)

        if (hs != null) hsSectorLists = namedSectorTypes

        val sectorClassesAE = HashMap<SectorClass, List<SectorType>>()
        outer@ for ((name, sectors) in namedSectorTypes) {
            val sectorClass = when (name.removePrefix(BlueprintManager.AE_PREFIX)) {
                "CIVILIAN" -> SectorClass.CIVILIAN
                "HOSTILE" -> SectorClass.HOSTILE
                "NEBULA" -> SectorClass.NEBULA

                // Skip unknown sector classes
                else -> continue@outer
            }

            // Keep the AE stuff separate, so we can override
            // the non-AE versions later.
            val classes = if (name.startsWith(BlueprintManager.AE_PREFIX)) sectorClassesAE else sectorClasses

            classes[sectorClass] = sectors.map {
                sectorTypes[it] ?: error("Missing sector $it specificed in category $name")
            }
        }

        // Apply the AE overrides
        if (enableAE) {
            sectorClasses.putAll(sectorClassesAE)
        }
    }

    /**
     * Create the game map the original generates from [treeSeed]:
     * GenerateSectorTree (0x5f0ef0) reseeds the generator with it and builds
     * the eight columns with the column builder (0x5f0180). The caller draws
     * [treeSeed] from the global stream for a new game (NewGame 0x5f9e00) or
     * reads it from the save.
     */
    constructor(df: Datafile, eventManager: EventManager, enableAE: Boolean, treeSeed: Int)
            : this(df, eventManager, enableAE) {
        generationSeed = treeSeed
        generateTree(treeSeed)
    }

    /**
     * A node of the tree under construction; the sector description is
     * drawn once its column is complete.
     */
    private class TreeNode(var sectorClass: SectorClass, val column: Int) {
        /** The drawn name's index in the type's names (SectorInfo.nameIndex). */
        var nameIndex = -1
        /** Hyperspace's Sector::type after a `<sectorMap>` recolour (4 = unknown); -1 when not recoloured. */
        var hsType = -1
        val kids = ArrayList<TreeNode>()
        lateinit var type: SectorType
        /** The name GetSectorDescription drew (SectorDescription::name / shortName). */
        var name: SectorType.SectorName? = null
    }

    private fun generateTree(treeSeed: Int) {
        // This require lets us modify the sectors array in the constructor
        require(sectors is ArrayList)

        NativeRandom.seed(treeSeed)

        // The description state (used unique sectors, the names still
        // available) is reloaded from sector_data.xml for every tree.
        treeUsed.clear()
        treeNames.clear()

        // The first draw makes the start node, whose class is overwritten
        // with CIVILIAN straight afterwards (0x5f0ef0).
        SectorClass.random(NativeRandom)

        val startType = sectorTypes["STANDARD_SPACE"] ?: error("Cannot find STANDARD_SPACE for first sector")
        val start = TreeNode(SectorClass.CIVILIAN, 0)
        start.type = startType
        val columns = ArrayList<List<TreeNode>>()
        columns.add(listOf(start))

        // GenerateSectorMap (1.6.9 0x5cb6f0): six columns, or ten in infinite mode (StarMap::bInfiniteMode).
        for (columnNum in 1..(if (hsInfinite) 10 else 6)) {
            val previous = columns.last()
            val newColumn = buildColumn(previous, columnNum)
            describeColumn(newColumn, columnNum)
            columns.add(newColumn)
        }

        // The final column: one node whose class draw is discarded, as
        // FINAL is always hostile. Infinite mode has none.
        if (!hsInfinite) {
            SectorClass.random(NativeRandom)
            val finalType = sectorTypes["FINAL"] ?: error("Cannot find FINAL sector for the boss fight")
            val finalNode = TreeNode(SectorClass.HOSTILE, 7)
            finalNode.type = finalType
            for (node in columns.last())
                node.kids.add(finalNode)
            columns.add(listOf(finalNode))
        }

        // Convert to SectorInfo objects.
        val infos = HashMap<TreeNode, SectorInfo>()
        for (column in columns) {
            val list = ArrayList<SectorInfo>()
            for ((index, node) in column.withIndex()) {
                val info = SectorInfo(node.column, index, node.type, node.sectorClass)
                info.nameIndex = node.nameIndex
                info.hsType = node.hsType
                info.pickedName = node.name
                infos[node] = info
                list.add(info)
            }
            sectors.add(list)
        }
        for (column in columns) {
            for (node in column) {
                infos.getValue(node).nextSectors.addAll(node.kids.map { infos.getValue(it) })
            }
        }

        // Infinite.cpp's GenerateSectorMap hook: the column seed is the next draw after the tree (made in every
        // Hyperspace run, infinite or not).
        if (eventManager.hs != null) hsInfiniteSeed = NativeRandom.next31()
    }

    /** The description state of the tree, kept for infinite mode's later columns. */
    private val treeUsed = HashSet<SectorType>()
    private val treeNames = HashMap<SectorType, ArrayList<SectorType.SectorName>>()

    /** Infinite.cpp infiniteSectorMapSeed: PushSectorColumn reseeds with it and draws the next. */
    var hsInfiniteSeed = 0
        private set

    /** Columns pushed so far: every push moves the map 66 px left (PushSectorColumn). */
    var hsInfinitePushes = 0
        private set

    /** The lowest level still in StarMap::sectors (a push drops those below the new sector's level - 4). */
    var hsInfiniteFirstLevel = 0
        private set

    /**
     * GetSectorDescription (0x5da010) for each node of a new column, in order, then (Hyperspace) the AddSectorColumn
     * recolour.
     */
    private fun describeColumn(newColumn: List<TreeNode>, columnNum: Int) {
        var hsRecolour = false
        for (node in newColumn) {
            val type: SectorType
            if (hsSectorLists != null) {
                // HS GetSectorDescription (CustomSectors.cpp, replaces the original): a sectorMap list for the
                // level is drawn first and replaces the node's class list.
                val mapped = hsSectorMap?.getSectorType(columnNum)
                if (mapped != null) hsRecolour = true
                val list = mapped ?: node.sectorClass.name
                type = xyz.znix.xftl.hs.sector.HsSectorMap.pickDescription(list, columnNum, hsSectorLists!!, sectorTypes,
                    treeUsed, hsDlc) { eventManager.hs?.getCustomSector(it)?.maxSector ?: -1 }
                    ?: sectorTypes["STANDARD_SPACE"]!!
            } else {
                val allTypes = sectorClasses[node.sectorClass] ?: error("No sectors for sector class ${node.sectorClass}")
                val candidates = allTypes.filter { columnNum >= it.minSector && !treeUsed.contains(it) }
                type = candidates[NativeRandom.nextInt(candidates.size)]
            }
            if (type.unique)
                treeUsed.add(type)

            // The name draw is always made; the name leaves the shared
            // list while two or more remain.
            val names = treeNames.getOrPut(type) { ArrayList(type.names) }
            val nameIndex = NativeRandom.nextInt(names.size)
            node.name = names[nameIndex]
            // Sector::description.name: the drawn name's index in the type's names (SectorInfo.nameIndex)
            node.nameIndex = type.names.indexOf(names[nameIndex])
            if (names.size >= 2)
                names.removeAt(nameIndex)

            node.type = type
        }
        // HS AddSectorColumn: after a sectorMap pick, every node of the column takes the class its description
        // is listed under (CIVILIAN, HOSTILE, NEBULA, else unknown).
        if (hsRecolour) for (node in newColumn) {
            val lists = hsSectorLists!!
            val cls = SectorClass.values().firstOrNull {
                xyz.znix.xftl.hs.sector.HsSectorMap.sectorIsType(node.type.name, it.name, lists, hsDlc)
            }
            if (cls != null) node.sectorClass = cls
            node.hsType = cls?.ordinal ?: xyz.znix.xftl.hs.sector.HsSectorMap.TYPE_UNKNOWN
        }
    }

    /**
     * StarMap::SelectNewSector (1.6.9 0x5cb5d0): in infinite mode a sector past level 2 pushes a column (Infinite.cpp
     * also pushes when the secret sector is left for one). Call before the sector's map is generated.
     */
    fun hsSelectSector(info: SectorInfo) {
        if (!hsInfinite || info.isSecretSector || info.columnNumber <= 2) return
        // PushSectorColumn (0x5cb4e0) under Infinite.cpp's hook: srandom32(infiniteSectorMapSeed), the sectors below
        // the new one's level - 4 leave the map and the rest move 66 px left, AddSectorColumn, the next seed.
        NativeRandom.seed(hsInfiniteSeed)
        hsInfiniteFirstLevel = maxOf(hsInfiniteFirstLevel, info.columnNumber - 4)
        hsInfinitePushes++
        val previous = sectors.last()
        val level = previous[0].columnNumber + 1
        val prevNodes = previous.map { TreeNode(it.sectorClass, it.columnNumber) }
        val newColumn = buildColumn(prevNodes, level)
        describeColumn(newColumn, level)
        val infos = newColumn.mapIndexed { i, n ->
            SectorInfo(level, i, n.type, n.sectorClass).also { it.hsType = n.hsType; it.pickedName = n.name }
        }
        val byNode = newColumn.zip(infos).toMap()
        for ((k, p) in prevNodes.withIndex()) previous[k].nextSectors.addAll(p.kids.map { byNode.getValue(it) })
        (sectors as ArrayList).add(infos)
        hsInfiniteSeed = NativeRandom.next31()
    }

    /**
     * The column builder (StarMap 0x5f0180): the number of nodes is drawn
     * until it differs from the previous column's (two after the single
     * start node), then each previous node is linked to the carried node
     * of its predecessor and to the nodes created for it. The 4->2 and 2->4
     * steps are special cases (pairs of previous nodes share a node, resp.
     * one previous node feeds two).
     */
    private fun buildColumn(previous: List<TreeNode>, columnNum: Int): ArrayList<TreeNode> {
        val numPrevious = previous.size
        var count: Int
        do {
            count = NativeRandom.nextInt(3) + 2
        } while (count == numPrevious)
        if (numPrevious == 1)
            count = 2

        val column = ArrayList<TreeNode>()
        if (numPrevious == 4 && count == 2) {
            for (k in 0 until 2) {
                val node = TreeNode(SectorClass.random(NativeRandom), columnNum)
                previous[2 * k].kids.add(node)
                previous[2 * k + 1].kids.add(node)
                column.add(node)
            }
            return column
        }
        if (numPrevious == 2 && count == 4) {
            for (k in 0 until 4) {
                val node = TreeNode(SectorClass.random(NativeRandom), columnNum)
                previous[k / 2].kids.add(node)
                column.add(node)
            }
            return column
        }

        var carry: TreeNode? = null
        var i = 0
        while (true) {
            carry?.let { previous[i].kids.add(it) }
            if (i == numPrevious - 1 && count <= numPrevious)
                break

            val node = TreeNode(SectorClass.random(NativeRandom), columnNum)
            previous[i].kids.add(node)
            column.add(node)
            if (i == 0 && numPrevious < count) {
                val extra = TreeNode(SectorClass.random(NativeRandom), columnNum)
                previous[0].kids.add(extra)
                column.add(extra)
            }
            carry = previous[i].kids.last()
            i++
            if (i >= numPrevious)
                break
        }
        return column
    }

    /** StarMap::sectors: the tree in creation order (start, each column in order, FINAL), from the first kept level. */
    fun hsVisibleSectors(): List<SectorInfo> =
        if (hsInfiniteFirstLevel == 0) sectors.flatten() else sectors.flatten().filter { it.columnNumber >= hsInfiniteFirstLevel }

    /** A sector description by name, for diagnostics. */
    fun sectorType(name: String): SectorType? = sectorTypes[name]

    private fun parseSectorType(elem: Element, namedSectorTypes: HashMap<String, List<String>>) {
        val name = elem.requireAttributeValue("name")
        if (namedSectorTypes.containsKey(name)) {
            println("[WARN] Overwriting sector type '$name'")
        }
        // Every child element's text is a sector name, whatever the element is called: the original takes the value of
        // each child node (Old Guard adds its sectors to these lists as `<name>` entries).
        namedSectorTypes[name] = elem.children.map { it.textTrim }
    }

    private fun parseSectorDescription(elem: Element) {
        val name = elem.requireAttributeValue("name")

        // Specialcase a couple of unused sectors which don't have names set, and should never appear otherwise anyway
        // Note that abandoned sector doesn't refer to the lanius-filled in-game sector - that's LANIUS_SECTOR
        if (name == "ABANDONED_SECTOR" || name == "DEEP_SPACE_SECTOR") return

        // Sectors can be overwritten by mods
        if (sectorTypes.containsKey(name)) {
            println("Overwriting existing sector $name")
        }

        val sector = SectorType(eventManager, elem)
        check(name == sector.name)
        sectorTypes[name] = sector
        descriptionElements[name] = elem
    }

    /**
     * Generate the beacon map of a sector. [storedLayoutSeed] is the layout
     * seed saved with a loaded game; without it a fresh seed is drawn from
     * the global stream (StarMap::GenerateMap, 0x5f8ca0).
     */
    @JvmOverloads
    fun generateSector(sectorInfo: SectorInfo, game: InGameState, storedLayoutSeed: Int? = null): Sector {
        val tutorial = game.mainGame?.isTutorialRunning == true
        val selectedEvents = if (tutorial) SpecialEvents(eventManager["TUTORIAL_START"],
            eventManager["TUTORIAL_ENEMY"], specialEvents.exit, specialEvents.exitNebula, specialEvents.fedBase) else specialEvents

        // The crew names the generator avoids are the player's.
        game.eventCreator.refreshUsedNames()

        // StarMap::GenerateMap (0x5f8ca0) and GenerateEvents (0x5f6540).
        val hs = if (eventManager.hs != null) game else null
        var layoutSeed = storedLayoutSeed
        // Diagnostics: a native capture's layout seed (BrowserEngine.newRunLayout), used once for the first sector.
        if (layoutSeed == null && !tutorial) { layoutSeed = ftl.browser.NativeRandom.layoutSeedOverride; ftl.browser.NativeRandom.layoutSeedOverride = null }
        if (hs != null && layoutSeed == null && !tutorial && xyz.znix.xftl.hs.sector.HsSeeds.enabled)
            layoutSeed = hsNextSectorSeed(sectorInfo)
        val layout = NativeSectorGenerator(sectorInfo, game.eventCreator, selectedEvents, game.difficulty, tutorial, hs)
            .generate(layoutSeed)
        val sector = Sector(sectorInfo, game.difficulty, layout)

        // StarMap::GenerateMap (1.6.9 0x5d4590, HS StarMap.cpp rewrite) after GenerateEvents: at ROCK_HOME with the
        // crystal alien found, the first location whose event is ROCK_CRYSTAL_BEACON becomes a quest location.
        if (!tutorial) markCrystalBeacon(game, sector)

        // The end of GenerateMap: the quests that didn't fit the previous
        // sector are placed with the layout stream still running.
        game.placeDelayedQuests(sector)
        // Hyperspace's GenerateMap hook: custom exit beacons, onGenerate load lists, renamed beacons.
        if (hs != null) xyz.znix.xftl.hs.sector.HsGeneration.afterGenerateMap(game, sector, tutorial)

        layout.flagshipStart?.let { sector.bosses.add(FlagshipBoss(sector, game, it)) }

        return sector
    }

    /**
     * AchievementTracker's "ruwen" flag: ShipManager::AddCrewMember (1.6.9 0x49c4f0) sets it when a crew blueprint named
     * name_Ruwen joins, the way an event's or a store's crew does ([noteBlueprintCrew]); a script's
     * AddCrewMemberFromString does not (oracle `wp6-map-rock-try3`). Saved with the map.
     */
    var browserRuwen = false

    /**
     * SpaceManager's current backdrop (+0xb8) and planet (+0xbc) as the last scene left them: SwitchBackground and
     * SwitchPlanet pick again while the pick equals them, so the next arrival's draws depend on them. A pulsar sets the
     * backdrop to the dull star field.
     */
    var spaceBack: String? = null
    var spacePlanet: String? = null

    /** A crew member joined from a blueprint (event reward, store hire): the "ruwen" flag when it is named name_Ruwen. */
    fun noteBlueprintCrew(game: InGameState, info: xyz.znix.xftl.crew.LivingCrewInfo) {
        if (info.name == game.translator["name_Ruwen"]) browserRuwen = true
    }

    /**
     * StarMap::crystalAlienFound (+0xa08): WorldManager::OnLoop (1.6.9 0x5f6520) sets it every frame when the player
     * has a crystal crew member and the "ruwen" flag is set.
     */
    private fun crystalAlienFound(game: InGameState): Boolean {
        val player: xyz.znix.xftl.Ship = game.player ?: return false
        val crew = player.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().filter { it.ownerShip === player }
        return browserRuwen && crew.any { it.blueprint.name == "crystal" }
    }

    /** X29 (both engines): the ROCK_CRYSTAL_BEACON quest label at the Rock homeworld (StarMap::GenerateMap). */
    private fun markCrystalBeacon(game: InGameState, sector: Sector) {
        val found = crystalAlienFound(game)
        if (sector.type.name != "ROCK_HOME" || !found) return
        val event = eventManager.getOrNull("ROCK_CRYSTAL_BEACON")?.resolve()
        val beacon = sector.beacons.firstOrNull {
            if (eventManager.hs != null) it.event.hsName == "ROCK_CRYSTAL_BEACON" else it.event === event
        } ?: return
        beacon.hasQuest = true
    }

    /**
     * Hyperspace ReplaceSector(sector, list) (CustomSectors.cpp): srandom32(GenerateLocationSeed(level, location.y) ^
     * currentSectorSeed), then the sector's description from [listIn]: "" or RANDOM is its own class list, ALL the
     * level's `<sectorMap>` list (else 4/4/2 in 10 CIVILIAN/HOSTILE/NEBULA), a class list (CIVILIAN, HOSTILE, NEBULA,
     * UNKNOWN) through GetSectorDescriptionCustom (description, then name draw), anything else that sector by name
     * (GetSpecificSector). A class list also sets the sector's colour (Sector::type). The unique descriptions in use
     * are the tree's (HS keeps the generator's flags; the name lists are not kept between the two).
     */
    fun hsReplaceSector(info: SectorInfo, listIn: String, sectorSeed: Int) {
        val lists = hsSectorLists ?: return
        val level = info.columnNumber
        val y = (4 - sectors[level].size) * 25 + info.columnIndex * 50
        NativeRandom.seed((((level + y) * (level + y + 1)) / 2 + y) xor sectorSeed)
        val currentType = if (info.hsType >= 0) info.hsType else info.sectorClass.ordinal
        var list = listIn
        if (list.isEmpty() || list == "RANDOM") list = when (currentType) {
            0 -> "CIVILIAN"; 1 -> "HOSTILE"; 2 -> "NEBULA"; else -> "UNKNOWN"
        }
        val custom = list == "ALL" || list == "CIVILIAN" || list == "HOSTILE" || list == "NEBULA" || list == "UNKNOWN"
        if (list == "ALL") {
            list = hsSectorMap?.getSectorType(level) ?: run {
                val rng = NativeRandom.next31() % 10
                if (rng < 4) "CIVILIAN" else if (rng < 8) "HOSTILE" else "NEBULA"
            }
        }
        val type: SectorType? = if (custom) {
            val used = sectors.flatten().map { it.type }.filter { it.unique }.toSet()
            val picked = xyz.znix.xftl.hs.sector.HsSectorMap.pickDescription(list, level, lists, sectorTypes, used, hsDlc) {
                eventManager.hs?.getCustomSector(it)?.maxSector ?: -1
            }
            if (picked != null) {
                info.pickedName = picked.names[NativeRandom.nextInt(picked.names.size)] // the name draw
                picked
            } else sectorTypes["STANDARD_SPACE"]
        } else sectorTypes[list]
        if (type != null) { if (!custom) info.pickedName = null; info.type = type }
        when (list) {
            "CIVILIAN" -> info.hsType = 0
            "HOSTILE" -> info.hsType = 1
            "NEBULA" -> info.hsType = 2
            "UNKNOWN" -> info.hsType = xyz.znix.xftl.hs.sector.HsSectorMap.TYPE_UNKNOWN
        }
    }

    /**
     * Hyperspace seeded runs (Seeds.cpp): StarMap::AdvanceWorldLevel draws worldRng once, then GenerateMap takes
     * `worldRng.current + Sector::location.y` as the sector seed (a secret sector: secretRng ^ CRC-32 of its name)
     * instead of drawing one. location.y is AddSectorColumn's (0x5f0180): (4 - column size) * 25 + index * 50.
     */
    private fun hsNextSectorSeed(info: SectorInfo): Int {
        val seeds = xyz.znix.xftl.hs.sector.HsSeeds
        seeds.onAdvanceWorldLevel()
        val origin = info.secretOrigin
        if (origin != null) return seeds.nextSectorSeed(0, info.type.names.firstOrNull()?.full?.hsData ?: info.type.name)
        val column = sectors[info.columnNumber]
        return seeds.nextSectorSeed((4 - column.size) * 25 + info.columnIndex * 50, null)
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        // Optional: the seed the tree can be regenerated from.
        elem.setAttribute("browserTreeSeed", generationSeed.toString())
        if (browserRuwen) elem.setAttribute("browserRuwen", "true")
        spaceBack?.let { elem.setAttribute("browserSpaceBack", it) }
        spacePlanet?.let { elem.setAttribute("browserSpacePlanet", it) }
        // Hyperspace infinite mode: the column seed, the pushes and the tree's description state for later columns.
        if (hsInfinite) {
            elem.setAttribute("hsInfiniteSeed", hsInfiniteSeed.toString())
            elem.setAttribute("hsInfinitePushes", hsInfinitePushes.toString())
            elem.setAttribute("hsInfiniteFirstLevel", hsInfiniteFirstLevel.toString())
            elem.setAttribute("hsTreeUsed", treeUsed.joinToString(",") { it.name })
            elem.setAttribute("hsTreeNames", treeNames.entries.joinToString(";") { (t, left) ->
                t.name + ":" + left.joinToString(",") { t.names.indexOf(it).toString() } })
        }

        // Register all the sectors now, since they reference each other.
        for (column in sectors) {
            for (sector in column) {
                refs.register(sector, "sectorInfo")
            }
        }
        for (sector in secretSectors) refs.register(sector, "sectorInfo")

        for (column in sectors) {
            val columnElem = Element("column")
            for (sector in column) {
                val sectorElem = Element("sectorInfo")
                sector.saveToXML(sectorElem, refs)
                columnElem.addContent(sectorElem)
            }
            elem.addContent(columnElem)
        }
        for (sector in secretSectors) {
            val secretElem = Element("secretSectorInfo")
                .setAttribute("column", sector.columnNumber.toString())
                .setAttribute("row", sector.columnIndex.toString())
            sector.saveToXML(secretElem, refs)
            elem.addContent(secretElem)
        }
    }

    /**
     * Deserialise a game map from XML.
     */
    constructor(content: GameContent, elem: Element, refs: RefLoader)
            : this(content.datafile, content.eventManager, content.enableAdvancedEdition) {

        // This assertion lets us modify the sectors array in the constructor
        require(sectors is ArrayList)

        generationSeed = elem.getAttributeValue("browserTreeSeed")?.toIntOrNull() ?: 0
        browserRuwen = elem.getAttributeValue("browserRuwen") == "true"
        spaceBack = elem.getAttributeValue("browserSpaceBack")
        spacePlanet = elem.getAttributeValue("browserSpacePlanet")
        if (hsInfinite) {
            hsInfiniteSeed = elem.getAttributeValue("hsInfiniteSeed")?.toIntOrNull() ?: 0
            hsInfinitePushes = elem.getAttributeValue("hsInfinitePushes")?.toIntOrNull() ?: 0
            hsInfiniteFirstLevel = elem.getAttributeValue("hsInfiniteFirstLevel")?.toIntOrNull() ?: 0
            elem.getAttributeValue("hsTreeUsed")?.split(',')?.forEach { n -> sectorTypes[n]?.let { treeUsed += it } }
            elem.getAttributeValue("hsTreeNames")?.split(';')?.forEach { e ->
                val t = sectorTypes[e.substringBefore(':')] ?: return@forEach
                treeNames[t] = ArrayList(e.substringAfter(':').split(',').mapNotNull { it.toIntOrNull()?.let { i -> t.names.getOrNull(i) } })
            }
        }

        val postLoad = ArrayList<(GameMap) -> Unit>()

        for ((columnNumber, columnElem) in elem.getChildren("column").withIndex()) {
            val column = ArrayList<SectorInfo>()
            for ((columnIndex, sectorElem) in columnElem.getChildren("sectorInfo").withIndex()) {
                val sector = deserialiseSectorInfo(sectorElem, refs, columnNumber, columnIndex, postLoad)
                column += sector
            }
            sectors += column
        }
        for (secretElem in elem.getChildren("secretSectorInfo")) {
            val column = secretElem.requireAttributeValue("column").toInt()
            val row = secretElem.requireAttributeValue("row").toInt()
            val sector = deserialiseSectorInfo(secretElem, refs, column, row, postLoad)
            sector.secretOrigin = sectors[column][row]
            require(sector.isSecretSector)
            secretSectors.add(sector)
        }

        // This is required to link up all the neighbouring sectors, since they're
        // specified by their index in the next column.
        for (fn in postLoad) {
            fn(this)
        }
    }

    class SpecialEvents(
        /**
         * The very first event that appears in the first beacon of the game.
         */
        val startGame: IEvent,

        /**
         * The event to be used when we've run out of events, but still have more beacons
         * to be filled. After modifying the vanilla FTL event XMLs, this is hardcoded
         * to the 'NEUTRAL' event/eventList, so we should obviously behave likewise.
         *
         * Note that I haven't confirmed these should *only* be used after running out of
         * other events - it's perfectly possible the base game peppers these in on a
         * random chance.
         *
         * The filler will be resolved once per element, so if it's an [EventList] each beacon
         * will a random (though certainly not guaranteed to be unique) event.
         */
        val filler: IEvent,

        /**
         * The event to show on regular (non-nebula-covered) exit beacons. This seems
         * to be the same for all sectors.
         *
         * Whether this or [exitNebula] is used depends on whether the exit beacon
         * is situated in a nebula itself, not whether the sector type is a nebula.
         */
        val exit: IEvent,

        val exitNebula: IEvent,

        /**
         * This is the event the player sees when they jump to the federation base
         * in the last stand, without the flagship present.
         */
        val fedBase: IEvent
    )

    /**
     * This stores the information about an available sector as it appears
     * on the sector map.
     */
    inner class SectorInfo(
        /**
         * The 0-7 index of this column throughout the game - this sets
         * the sector's X position.
         */
        val columnNumber: Int,

        /**
         * The index of this sector within it's column - this sets it's Y position.
         *
         * Row doesn't seem like the appropriate word since they're not
         * in a grid, but it's pretty much that.
         */
        val columnIndex: Int,

        /**
         * The exact type of sector.
         */
        var type: SectorType,

        val sectorClass: SectorClass
    ) : ISerialReferencable {
        /**
         * The sectors the player can jump to from this one.
         */
        val nextSectors = ArrayList<SectorInfo>()

        /** The index in [SectorType.names] of the name GetSectorDescription drew for this sector (-1: the type's name). */
        var nameIndex = -1

        /** Sector::description.name. */
        val descriptionName: xyz.znix.xftl.GameText
            get() = if (type.displayAsName) type.nameTextId else type.names.getOrNull(nameIndex)?.full ?: type.nameTextId

        /** Hyperspace: Sector::type after a `<sectorMap>` recolour (4 = unknown), -1 otherwise. */
        var hsType = -1

        /**
         * The name GetSectorDescription (0x5da010) drew for this sector (SectorDescription::name / shortName); null
         * for the sectors named without a draw (the start, FINAL, a secret sector: the description's first name).
         */
        var pickedName: SectorType.SectorName? = null

        /** SectorDescription::name: the drawn name, else the description's first ([SectorType.nameTextId]). */
        val displayName: xyz.znix.xftl.GameText get() = hsName ?: pickedName?.full ?: type.nameTextId
        val displayShortName: xyz.znix.xftl.GameText get() = hsShortName ?: pickedName?.short ?: type.shortTextId

        /** Hyperspace: SectorDescription::name and shortName written by a script. */
        @JvmField var hsName: xyz.znix.xftl.GameText? = null
        @JvmField var hsShortName: xyz.znix.xftl.GameText? = null

        /** Hyperspace: per-sector generation state (the description's event counts HS edits, see hs.sector). */
        @JvmField var hsState: Any? = null
        val isSecretSector: Boolean get() = type.name == "CRYSTAL_HOME" || secretOrigin != null
        var secretOrigin: SectorInfo? = null
            internal set

        // Note we don't store the actual Sector object here - that's
        // only needed when the player is inside it, so it can be stored
        // separately and discarded when not in use to save memory.

        fun saveToXML(elem: Element, refs: ObjectRefs) {
            SaveUtil.addObjectId(elem, refs, this)
            elem.setAttribute("type", type.name)
            elem.setAttribute("class", sectorClass.name)
            if (nameIndex >= 0) elem.setAttribute("name", nameIndex.toString())
            pickedName?.let { n -> type.names.indexOf(n).takeIf { it > 0 }?.let { elem.setAttribute("nameIndex", it.toString()) } }
            if (hsType != -1) elem.setAttribute("hsType", hsType.toString())

            // There's a  lot of SectorInfo objects, so put the next sectors
            // in attributes to save space.
            require(nextSectors.size <= 2)
            SaveUtil.addAttrInt(elem, "next1", nextSectors.getOrNull(0)?.columnIndex)
            SaveUtil.addAttrInt(elem, "next2", nextSectors.getOrNull(1)?.columnIndex)
        }
    }

    // Since the sector map relies on the identity of SectorInfo objects,
    // other classes shouldn't be deserialising their own copies.
    private fun deserialiseSectorInfo(
        elem: Element, refs: RefLoader,
        columnNumber: Int, columnIndex: Int,
        postLoad: ArrayList<(GameMap) -> Unit>
    ): SectorInfo {
        val typeName = elem.getAttributeValue("type")
        val sectorClass = SectorClass.valueOf(elem.getAttributeValue("class"))

        // A sector of a mod that left the save's list (Saves > Advanced) becomes plain space.
        val type = sectorTypes[typeName] ?: run {
            xyz.znix.xftl.savegame.MissingContent.note(typeName)
            sectorTypes["STANDARD_SPACE"] ?: error("Missing sector type '$typeName'")
        }
        val sector = SectorInfo(columnNumber, columnIndex, type, sectorClass)
        sector.nameIndex = elem.getAttributeValue("name")?.toIntOrNull() ?: -1
        sector.pickedName = elem.getAttributeValue("nameIndex")?.toIntOrNull()?.let { type.names.getOrNull(it) }
        sector.hsType = elem.getAttributeValue("hsType")?.toIntOrNull() ?: -1

        for (i in 1..2) {
            // The index in the column of the next beacon is specified
            val index = SaveUtil.getAttrIntOrNull(elem, "next$i") ?: continue
            postLoad += {
                val nextColumn = it.sectors[columnNumber + 1]
                sector.nextSectors += nextColumn[index]
            }
        }

        SaveUtil.registerObjectId(elem, refs, sector)
        return sector
    }

    /**
     * The broad types of sector, which determines the colour of the sector on the map.
     */
    enum class SectorClass {
        CIVILIAN,
        HOSTILE,
        NEBULA;

        companion object {
            // The sector column builder (0x5f0180): rand() % 10 of 0-1 is a
            // nebula, 6-9 hostile and 2-5 civilian (20/40/40).
            fun random(rand: kotlin.random.Random): SectorClass {
                val roll = rand.nextInt(10)
                return when {
                    roll < 2 -> NEBULA
                    roll > 5 -> HOSTILE
                    else -> CIVILIAN
                }
            }
        }
    }
}
