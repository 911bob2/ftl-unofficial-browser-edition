package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsCapability
import xyz.znix.xftl.hs.HsConfig

/**
 * Hyperspace custom ships: the definitions of `CustomShipSelect` (hangar entries, ship order, per-ship
 * `CustomShipDefinition`). Only used while [HS.active]; vanilla never touches this object.
 *
 * Load order as in HS: `EarlyParseShipsNode` (an unnamed `<customShip>` in `<ships>` is the default definition) in the
 * pre-pass, `ParseVanillaShipNode` for every `<shipBlueprint>` while the blueprints load, then `ParseShipsNode` in the
 * main pass. Our engine builds several blueprint managers (hangar modes, game content) from the same files, so
 * [build] recomputes everything from the blueprint elements each time; the result is the same for the same files.
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomShipSelect.cpp (EarlyParseShipsNode, ParseShipsNode,
// ParseVanillaShipNode, ParseCustomShipNode, RoomAnimDef::ParseRoomAnimNode, IsCustomShip, GetDefinition).
object HsShips {
    /** `CustomShipSelect::vanillaShipNames` (ScoreKeeper ship ids 0-9; a `<ship>` of these names is not added). */
    @JvmField
    val VANILLA_SHIP_NAMES = listOf(
        "PLAYER_SHIP_HARD", "PLAYER_SHIP_STEALTH", "PLAYER_SHIP_MANTIS", "PLAYER_SHIP_CIRCLE", "PLAYER_SHIP_FED",
        "PLAYER_SHIP_JELLY", "PLAYER_SHIP_ROCK", "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_CRYSTAL", "PLAYER_SHIP_ANAEROBIC",
    )

    /** `vanillaShipOrder`. */
    @JvmField
    val VANILLA_SHIP_ORDER = intArrayOf(0, 3, 4, 7, 2, 5, 6, 1, 9, 8)

    @JvmField var defaultDef = CustomShipDefinition()
    private val defs = HashMap<String, CustomShipDefinition>()
    @JvmField val buttons = ArrayList<ShipButtonDefinition>()
    @JvmField val customShipOrder = ArrayList<String>()
    @JvmField var hideFirstPage = false
    /** `<hangarAnims><anim x y>name`: (x, y, name). */
    @JvmField val hangarAnims = ArrayList<Triple<Int, Int, String>>()
    /** `<unlock>` children of each `<ship>` (CustomShipUnlocks::ParseUnlockNode), by ship button name. */
    @JvmField val unlockNodes = LinkedHashMap<String, MutableList<Element>>()
    /** `<shipIcons>` nodes inside `<ships>` (ShipIconManager::ParseShipIconNode; render workstream). */
    @JvmField val shipIconNodes = ArrayList<Element>()

    @JvmField var built = false

    /** Names this package implements (taken off the capability report). Called by [HS.init] before the scan. */
    @JvmStatic
    fun registerCapabilities() {
        HsCapability.implement(
            "customShip", "hiddenAug", "crewLimit", "startingScrap", "startingFuel", "autoShipForce",
            "shipOrder", "noAppend", "hideFirstShipPage", "ships@hideFirstShipPage",
        )
        HsShipUnlocks.register()
        HsStores.registerCapabilities()
        HsBoss.registerCapabilities()
        HsAchievements.register()
        HsCapability.implement("achievements", "victories", "secretIcon", "filter", "noFlagship", "victories@noFlagship")
        // WP2: the hangar / unlock / ship-screen names (frames compared with the HS oracle; docs/HYPERSPACE.md)
        HsCapability.implement("shipReq", "otherUnlocks", "splitVictoryAchievement", "splitUnlockQuestAchievement", "unlockCustomShip", "secretName", "secretDescription", "lockImage", "systemLimit", "subsystemLimit")
        // Hand-over points of hs.events: augment functions and achievements in requirements, variable achievements,
        // and the custom store an event opens.
        xyz.znix.xftl.hs.events.HsReq.augmentHook = { ship, name -> HsAugments.functionCount(ship, name) }
        // statcrew's augment stat boosts count the slotted and hidden augments (ShipInfo::augList).
        xyz.znix.xftl.hs.stat.AugmentStatBoosts.augmentCounts = { ship -> HsAugments.augListWithHidden(ship) }
        xyz.znix.xftl.hs.events.HsReq.achievementStatus = { HsAchievements.getAchievementStatus(it) }
        xyz.znix.xftl.hs.events.HsEvents.onVariableChanged = { name, value -> HsAchievements.updateVariableAchievements(name, value) }
        HsStores.eventStoreId = { game, event ->
            if (game.eventManager.hs != null) xyz.znix.xftl.hs.events.HsFlow.customFor(game, event)?.customStore else null
        }
    }

    fun definitions(): Map<String, CustomShipDefinition> = defs

    /** `GetDefinition`: the ship's definition, or the default one. */
    @JvmStatic
    fun def(name: String): CustomShipDefinition = defs[name] ?: defaultDef

    @JvmStatic
    fun hasCustomDef(name: String): Boolean = defs.containsKey(name)

    /** `IsCustomShip`: a hangar entry of this name, or of this name without its `_2`/`_3`-style suffix. */
    @JvmStatic
    fun isCustomShip(id: String): Boolean =
        buttons.any { id == it.name || (id.length > 3 && id.substring(0, id.length - 2) == it.name) }

    /** `GetVariantName`. */
    @JvmStatic
    fun variantName(name: String, variant: Int): String = when (variant) { 1 -> name + "_2"; 2 -> name + "_3"; else -> name }

    /**
     * Rebuild from the `<shipBlueprint>` elements of one blueprint load, in load order. Called by BlueprintManager
     * when [HS.active].
     */
    @JvmStatic
    fun build(shipBlueprints: List<Element>) {
        defs.clear(); buttons.clear(); customShipOrder.clear(); hangarAnims.clear(); unlockNodes.clear()
        shipIconNodes.clear()
        hideFirstPage = false
        defaultDef = CustomShipDefinition()
        HsAugments.loaded = false
        val ships = HS.config?.nodesOf("ships") ?: emptyList()
        // Pre-pass: EarlyParseShipsNode.
        for (node in ships) for (child in node.children) {
            if (child.name == "customShip" && child.getAttribute("name") == null) guarded("<ships> default customShip") {
                parseCustomShipNode(child, defaultDef)
            }
        }
        // BlueprintManager::ProcessShipBlueprint -> ParseVanillaShipNode.
        for (bp in shipBlueprints) {
            val name = bp.getAttributeValue("name") ?: continue
            val def = defaultDef.copy()
            defs[name] = def
            val custom = guarded("shipBlueprint $name") { parseCustomShipNode(bp, def) } ?: false
            if (!custom) defs.remove(name)
        }
        // Main pass: ParseShipsNode.
        for (node in ships) guarded("<ships>") { parseShipsNode(node) }
        built = true
        HsShipUnlocks.load()
        HsStores.load(HS.config)
        HsAchievements.load(HS.config)
        HsVictories.load(HS.config)
        HsBoss.load(HS.config)
        HsShipGenerator.load(HS.config)
    }

    /** HS wraps each parse in try/catch with an error box; we record the failure and carry on with what was read. */
    private inline fun <T> guarded(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        HS.unsupported("parse", HS.HYPERSPACE_XML, "$what: ${e.message ?: e}")
        null
    }

    private fun parseShipsNode(node: Element) {
        node.getAttributeValue("hideFirstShipPage")?.let { hideFirstPage = HsConfig.bool(it) }
        for (child in node.children) {
            val name = child.name
            if (name == "shipIcons") shipIconNodes += child
            if (name == "ship") {
                val shipName = child.getAttributeValue("name")
                if (shipName != null) {
                    val def = parseShipButton(child, shipName)
                    // `G_->GetScoreKeeper()->GetShipId(shipName).first == -1`: vanilla ships keep their own entry.
                    if (shipName !in VANILLA_SHIP_NAMES) buttons += def
                }
            }
            if (name == "shipOrder") {
                for (s in child.children) {
                    if (s.name == "ship") customShipOrder += s.text
                    if (s.name == "empty") customShipOrder += "empty"
                }
            }
            if (name == "hangarAnims") {
                for (a in child.children) if (a.name == "anim")
                    hangarAnims += Triple(lexInt(a.getAttributeValue("x")) ?: 0, lexInt(a.getAttributeValue("y")) ?: 0, a.text)
            }
            if (name == "customShip") {
                val shipName = child.getAttributeValue("name") ?: continue
                val exists = defs.containsKey(shipName)
                var def = defs.getOrPut(shipName) { CustomShipDefinition() }
                val load = child.getAttributeValue("load")
                if (load != null) {
                    if (defs.containsKey(load)) { def = defs.getValue(load).copy(); defs[shipName] = def }
                } else if (!exists) {
                    def = defaultDef.copy(); defs[shipName] = def
                }
                def.name = shipName
                parseCustomShipNode(child, def)
            }
        }
    }

    private fun variantAttr(child: Element, attr: String, current: Int): Int {
        val v = child.getAttributeValue(attr) ?: return current
        return if (v == "dlc") 2 else if (HsConfig.bool(v)) 1 else 0
    }

    private fun parseShipButton(child: Element, shipName: String): ShipButtonDefinition {
        val b = ShipButtonDefinition(shipName)
        b.typeA = variantAttr(child, "a", b.typeA)
        b.typeB = variantAttr(child, "b", b.typeB)
        b.typeC = variantAttr(child, "c", b.typeC)
        child.getAttributeValue("secret")?.let { b.secretCruiser = HsConfig.bool(it) }
        child.getAttributeValue("noAppend")?.let { b.noAppend = HsConfig.bool(it) }
        child.getAttributeValue("splitUnlockQuestAchievement")?.let { b.splitUnlockQuestAchievement = HsConfig.bool(it) }
        child.getAttributeValue("splitVictoryAchievement")?.let { b.splitVictoryAchievement = HsConfig.bool(it) }
        for (s in child.children) {
            when (s.name) {
                "splitUnlockQuestAchievement" -> b.splitUnlockQuestAchievement = HsConfig.bool(s.text)
                "splitVictoryAchievement" -> b.splitVictoryAchievement = HsConfig.bool(s.text)
                "showShipAchievements" -> b.showShipAchievements = HsConfig.bool(s.text)
                "achievement" -> when (s.getAttributeValue("variant")) {
                    "a" -> b.shipAchievements[0] += s
                    "b" -> b.shipAchievements[1] += s
                    "c" -> b.shipAchievements[2] += s
                    "all", null -> b.shipAchievements.forEach { it += s }
                    else -> {}
                }
                "unlock" -> unlockNodes.getOrPut(shipName) { ArrayList() } += s
                "arrow" -> b.unlockArrows += parseArrow(s, shipName)
            }
        }
        return b
    }

    private fun parseArrow(s: Element, shipName: String): UnlockArrow {
        val variant = when (s.getAttributeValue("variant")) { "a" -> 0; "b" -> 1; "c" -> 2; else -> -1 }
        var tooltip = ""
        var literal = true
        s.getAttributeValue("tooltip")?.let { tooltip = it; literal = false }
        s.getAttributeValue("tooltipText")?.let { tooltip = it; literal = true }
        val x = lexInt(s.getAttributeValue("x")) ?: 0
        val y = lexInt(s.getAttributeValue("y")) ?: 0
        var image = s.getAttributeValue("image") ?: ""
        var lockedImage = s.getAttributeValue("lockedImage") ?: ""
        val unlockedImage = s.getAttributeValue("unlockedImage") ?: ""
        val direction = s.getAttributeValue("direction") ?: ""
        var imageDirection = direction
        var rotation = 0f
        var mirror = false
        if (s.getAttributeValue("mirrorX")?.let(HsConfig::bool) == true) {
            mirror = !mirror
            imageDirection = when (imageDirection) { "left" -> "right"; "right" -> "left"; else -> imageDirection }
        }
        if (s.getAttributeValue("mirrorY")?.let(HsConfig::bool) == true) {
            mirror = !mirror
            rotation = 180f
            imageDirection = when (imageDirection) { "up" -> "down"; "down" -> "up"; else -> imageDirection }
        }
        if (image.isEmpty()) image = "arrow_v_$imageDirection.png"
        if (lockedImage.isEmpty()) lockedImage = "arrow_lock_$imageDirection.png"
        return UnlockArrow(shipName, s.text, variant, tooltip, literal, x, y, direction, image, lockedImage, unlockedImage, rotation, mirror)
    }

    /** `boost::lexical_cast<int>`: digits with an optional sign, nothing else (no whitespace). */
    private fun lexInt(s: String?): Int? {
        if (s == null) return null
        return s.toIntOrNull() ?: throw NumberFormatException("bad lexical cast: '$s'")
    }

    private fun lexFloat(s: String): Float =
        if (s.isNotEmpty() && s.trim() == s) s.toFloatOrNull() ?: throw NumberFormatException("bad lexical cast: '$s'")
        else throw NumberFormatException("bad lexical cast: '$s'")

    /** `ParseCustomShipNode`: returns whether the node set anything HS treats as custom. */
    private fun parseCustomShipNode(node: Element, def: CustomShipDefinition): Boolean {
        var isCustom = false
        for (shipNode in node.children) {
            val name = shipNode.name
            val v = shipNode.text
            when (name) {
                "hiddenAug" -> { isCustom = true; def.hiddenAugs[v] = (def.hiddenAugs[v] ?: 0) + 1 }
                "crewLimit" -> { isCustom = true; def.crewLimit = lexInt(v)!! }
                "shipIcons" -> {
                    isCustom = true
                    for (icon in shipNode.children) if (icon.name == "shipIcon") def.shipIcons += icon.text
                }
                "rooms" -> {
                    isCustom = true
                    for (roomNode in shipNode.children) {
                        if (roomNode.name == "room" && roomNode.getAttribute("id") != null) {
                            val roomId = lexInt(roomNode.getAttributeValue("id"))!!
                            val roomDef = RoomDefinition(roomId)
                            for (r in roomNode.children) {
                                when (r.name) {
                                    "roomAnim" -> roomDef.roomAnims += parseRoomAnim(r)
                                    "sensorBlind" -> roomDef.sensorBlind = HsConfig.bool(r.text)
                                    "hullDamageResistChance" -> roomDef.hullDamageResistChance = lexFloat(r.text)
                                    "sysDamageResistChance" -> roomDef.sysDamageResistChance = lexFloat(r.text)
                                    "ionDamageResistChance" -> roomDef.ionDamageResistChance = lexFloat(r.text)
                                }
                            }
                            def.roomDefs[roomId] = roomDef
                        } else if (roomNode.name == "partition" && roomNode.getChild("rooms") != null && roomNode.getChild("backups") != null) {
                            val backups = ArrayList<Pair<Int, List<Int>>>()
                            for (b in roomNode.getChild("backups").children) {
                                val slots = b.getChildren("slot").map { lexInt(it.getAttributeValue("id"))!! }
                                backups += Pair(lexInt(b.getAttributeValue("id"))!!, slots)
                            }
                            for (p in roomNode.getChild("rooms").children)
                                def.roomStationBackups[lexInt(p.getAttributeValue("id"))!!] = backups
                        }
                    }
                }
                "crew" -> {
                    isCustom = true
                    for (c in shipNode.children) {
                        def.crewList += CrewPlacement(
                            c.name, lexInt(c.getAttributeValue("room")) ?: 0, c.getAttributeValue("name") ?: "",
                            c.getAttributeValue("list")?.let(HsConfig::bool) ?: false
                        )
                    }
                }
                "noJump" -> { isCustom = true; def.noJump = true }
                "noFuelStalemate" -> { isCustom = true; def.noFuelStalemate = true }
                "hpCap" -> { isCustom = true; def.hpCap = lexInt(v)!! }
                "startingFuel" -> { isCustom = true; def.startingFuel = lexInt(v)!! }
                "startingScrap" -> { isCustom = true; def.startingScrap = lexInt(v)!! }
                "autoShipForce" -> { isCustom = true; def.forceAutomated = HsConfig.bool(v) }
                "systemLimit" -> { isCustom = true; def.systemLimit = lexInt(v)!! }
                "subsystemLimit" -> { isCustom = true; def.subsystemLimit = lexInt(v)!! }
                "customReactor" -> {
                    isCustom = true
                    shipNode.getAttributeValue("maxLevel")?.let { def.maxReactorLevel = lexInt(it)!! }
                    if (def.maxReactorLevel < 0) def.maxReactorLevel = 0
                    // `resize(ceil(maxLevel / 5 + 1), -1)`: integer division, so floor + 1.
                    if (def.maxReactorLevel > 25) {
                        val size = def.maxReactorLevel / 5 + 1
                        while (def.reactorPrices.size < size) def.reactorPrices += -1
                        while (def.reactorPrices.size > size) def.reactorPrices.removeAt(def.reactorPrices.size - 1)
                    }
                    for (r in shipNode.children) {
                        when (r.name) {
                            "baseCost" -> def.reactorPrices[0] = lexInt(r.text)!!
                            "increment" -> def.reactorPriceIncrement = lexInt(r.text)!!
                            "overrideCost" -> {
                                var column = 0
                                r.getAttributeValue("coloumn")?.let { column = lexInt(it)!! }
                                r.getAttributeValue("column")?.let { column = lexInt(it)!! }
                                def.reactorPrices[column] = lexInt(r.text)!!
                            }
                        }
                    }
                }
                "shipGenerator" -> { isCustom = true; def.shipGenerator = v }
                "artilleryGibMountFix" -> { isCustom = true; def.artilleryGibMountFix = true }
                "hideHullDuringExplosion" -> { isCustom = true; def.hideHullDuringExplosion = true }
                "augSlots" -> { isCustom = true; def.augSlots = lexInt(v)!! }
                "cargoSlots" -> { isCustom = true; def.cargoSlots = lexInt(v)!! }
                "systemList" -> {
                    // Fixes the first artillery image being used for every artillery room.
                    for (a in shipNode.getChildren("artillery")) def.artilleryRoomImages += (a.getAttributeValue("img") ?: "")
                    if (def.artilleryRoomImages.size > 1) isCustom = true
                }
                "exclusivityOverride" -> {
                    isCustom = true
                    def.hasExclusivityOverride = true
                    for (g in shipNode.getChildren("exclusivityGroup")) def.exclusivityGroups += g.children.map { it.name }
                }
            }
        }
        return isCustom
    }

    private fun animType(v: String?, current: Int): Int = when (v) {
        null -> current
        "stretch" -> 1
        "center" -> 2
        "random" -> 3
        else -> 0
    }

    /** RoomAnimDef::ParseRoomAnimNode (ship rooms; stat boosts' and erosion effects' `<roomAnim>` too). */
    @JvmStatic
    fun parseRoomAnim(node: Element): RoomAnimDef {
        // HS leaves renderLayer uninitialised without the attribute; 0 here.
        val layer = lexInt(node.getAttributeValue("renderLayer")) ?: 0
        var type = 0
        var border = 0
        var anim = ""
        var tile = ""
        var wall = ""
        if (node.children.isNotEmpty()) {
            for (c in node.children) {
                when (c.name) {
                    "anim" -> {
                        anim = c.text
                        type = animType(c.getAttributeValue("animType"), type)
                        c.getAttributeValue("animBorder")?.let { border = lexInt(it)!! }
                    }
                    "tileAnim" -> tile = c.text
                    "wallAnim" -> wall = c.text
                }
            }
        } else {
            anim = node.text
            type = animType(node.getAttributeValue("animType"), type)
            node.getAttributeValue("animBorder")?.let { border = lexInt(it)!! }
        }
        return RoomAnimDef(layer, type, border, anim, tile, wall)
    }
}
