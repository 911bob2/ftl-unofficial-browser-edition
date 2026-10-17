package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InGameState.GameContent
import xyz.znix.xftl.mapChildrenText
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
    fun createSecretSector(origin: SectorInfo): SectorInfo {
        require(!origin.isSecretSector)
        val secret = SectorInfo(origin.columnNumber, origin.columnIndex,
            sectorTypes["CRYSTAL_HOME"] ?: error("Missing official CRYSTAL_HOME sector"), SectorClass.CIVILIAN)
        secret.secretOrigin = origin
        secret.nextSectors.addAll(origin.nextSectors)
        secretSectors.add(secret)
        return secret
    }

    init {
        val xml = df.parseXML(df["data/sector_data.xml"])

        val namedSectorTypes = HashMap<String, List<String>>()

        for (elem in xml.rootElement.children) {
            if (!enableAE && (elem.getAttributeValue("name") == "LANIUS_SECTOR" || elem.getAttributeValue("name")?.startsWith(BlueprintManager.AE_PREFIX) == true)) continue
            when (elem.name) {
                "sectorType" -> parseSectorType(elem, namedSectorTypes)
                "sectorDescription" -> parseSectorDescription(elem)
                // TODO sectorMap is used in Multiverse (but not vanilla), support it.
                //  (note it might come from Hyperspace)
                else -> println("[warn] Unknown node type '${elem.name}' in sector_data.xml")
            }
        }

        // The first sector draws its events from STANDARD_SPACE but is named
        // as the civilian sector (GenerateSectorTree, 0x5f0ef0).
        val standardSpace = descriptionElements["STANDARD_SPACE"]
        val civilian = sectorTypes["CIVILIAN_SECTOR"]
        if (standardSpace != null && civilian != null)
            sectorTypes["STANDARD_SPACE"] = SectorType(eventManager, standardSpace, civilian)

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
    private class TreeNode(val sectorClass: SectorClass, val column: Int) {
        val kids = ArrayList<TreeNode>()
        lateinit var type: SectorType
    }

    private fun generateTree(treeSeed: Int) {
        // This require lets us modify the sectors array in the constructor
        require(sectors is ArrayList)

        NativeRandom.seed(treeSeed)

        // The description state (used unique sectors, the names still
        // available) is reloaded from sector_data.xml for every tree.
        val usedUnique = HashSet<SectorType>()
        val remainingNames = HashMap<SectorType, ArrayList<SectorType.SectorName>>()

        // The first draw makes the start node, whose class is overwritten
        // with CIVILIAN straight afterwards (0x5f0ef0).
        SectorClass.random(NativeRandom)

        val startType = sectorTypes["STANDARD_SPACE"] ?: error("Cannot find STANDARD_SPACE for first sector")
        val start = TreeNode(SectorClass.CIVILIAN, 0)
        start.type = startType
        val columns = ArrayList<List<TreeNode>>()
        columns.add(listOf(start))

        for (columnNum in 1..6) {
            val previous = columns.last()
            val newColumn = buildColumn(previous, columnNum)

            // GetSectorDescription (0x5da010) for each new node, in order.
            for (node in newColumn) {
                val allTypes = sectorClasses[node.sectorClass] ?: error("No sectors for sector class ${node.sectorClass}")
                val candidates = allTypes.filter { columnNum >= it.minSector && !usedUnique.contains(it) }
                val type = candidates[NativeRandom.nextInt(candidates.size)]
                if (type.unique)
                    usedUnique.add(type)

                // The name draw is always made; the name leaves the shared
                // list while two or more remain.
                val names = remainingNames.getOrPut(type) { ArrayList(type.names) }
                val nameIndex = NativeRandom.nextInt(names.size)
                if (names.size >= 2)
                    names.removeAt(nameIndex)

                node.type = type
            }
            columns.add(newColumn)
        }

        // The final column: one node whose class draw is discarded, as
        // FINAL is always hostile.
        SectorClass.random(NativeRandom)
        val finalType = sectorTypes["FINAL"] ?: error("Cannot find FINAL sector for the boss fight")
        val finalNode = TreeNode(SectorClass.HOSTILE, 7)
        finalNode.type = finalType
        for (node in columns.last())
            node.kids.add(finalNode)
        columns.add(listOf(finalNode))

        // Convert to SectorInfo objects.
        val infos = HashMap<TreeNode, SectorInfo>()
        for (column in columns) {
            val list = ArrayList<SectorInfo>()
            for ((index, node) in column.withIndex()) {
                val info = SectorInfo(node.column, index, node.type, node.sectorClass)
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

    /** A sector description by name, for diagnostics. */
    fun sectorType(name: String): SectorType? = sectorTypes[name]

    private fun parseSectorType(elem: Element, namedSectorTypes: HashMap<String, List<String>>) {
        val name = elem.requireAttributeValue("name")
        if (namedSectorTypes.containsKey(name)) {
            println("[WARN] Overwriting sector type '$name'")
        }
        namedSectorTypes[name] = elem.mapChildrenText("sector")
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
        val layout = NativeSectorGenerator(sectorInfo, game.eventCreator, selectedEvents, game.difficulty, tutorial)
            .generate(storedLayoutSeed)
        val sector = Sector(sectorInfo, game.difficulty, layout)

        // The end of GenerateMap: the quests that didn't fit the previous
        // sector are placed with the layout stream still running.
        game.placeDelayedQuests(sector)

        layout.flagshipStart?.let { sector.bosses.add(FlagshipBoss(sector, game, it)) }

        return sector
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        // Optional: the seed the tree can be regenerated from.
        elem.setAttribute("browserTreeSeed", generationSeed.toString())

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
            require(sector.isSecretSector)
            sector.secretOrigin = sectors[column][row]
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
        val isSecretSector: Boolean get() = type.name == "CRYSTAL_HOME"
        var secretOrigin: SectorInfo? = null
            internal set

        // Note we don't store the actual Sector object here - that's
        // only needed when the player is inside it, so it can be stored
        // separately and discarded when not in use to save memory.

        fun saveToXML(elem: Element, refs: ObjectRefs) {
            SaveUtil.addObjectId(elem, refs, this)
            elem.setAttribute("type", type.name)
            elem.setAttribute("class", sectorClass.name)

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

        val type = sectorTypes[typeName] ?: error("Missing sector type '$typeName'")
        val sector = SectorInfo(columnNumber, columnIndex, type, sectorClass)

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
