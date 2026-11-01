// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Utilities/Resources.cpp (Global::PreInitializeResources and
// Global::InitializeResources), src/game/Utilities/CustomOptions.h and the defaults in the files named per field.
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs

import org.jdom2.Element

/**
 * Typed `data/hyperspace.xml` settings, read the way Hyperspace reads them: one pass over the children of the
 * `<FTL>` root in file order (a later node of the same name overrides an earlier one, both `<store>` nodes are
 * kept), HS's defaults for anything absent, `EventsParser::ParseBoolean` ("true", "TRUE" or "True" only).
 *
 * The settings of feature families (ships, crew, events, augments, ...) are kept as their raw [Element]s in
 * [nodes] for the workstream that implements them; see docs/HYPERSPACE.md.
 */
class HsConfig private constructor() {
    // ---- Version (Resources.cpp PreInitializeResources) ----
    /** The `<version>` text, null when absent. */
    var versionRequirement: String? = null; private set
    /** Whether `<version>` accepts HS [HS_VERSION]; HS shows [versionMessage] when not and carries on. */
    var versionOk = false; private set
    var versionMessage: String? = null; private set
    /** `<isBasemod>` (OverhaulDetection::SetBasemodXml). */
    var isBasemod = false; private set

    // ---- <defaults> (CustomOptionsManager::Defaults, ErosionEffect.cpp) ----
    var defaultCheckCargo = false; private set
    var defaultChoiceRequiresCrew = false; private set
    var defaultBeaconTypeHideVanillaLabel = true; private set
    var defaultInstantEscapeRemoveShipBox = false; private set
    var erosionGrowBreachMultiplier = 1f; private set
    var erosionNewBreachDamage = 100f; private set
    var erosionNewBreachRequirement = 100f; private set
    var erosionCanDilate = false; private set

    // ---- Fixes and rule options: Balance.cpp, CustomCrystalShard.cpp, CustomShips.cpp, CustomCrew.cpp ----
    var hackingDroneFix = true; private set
    var hackingIonFix = false; private set
    var repairDroneRecoveryFix = true; private set
    var controllableIonDroneFix = false; private set
    var controllableIonDroneDelay = 6f; private set
    var controllableIonDroneDelayInitial = 6f; private set
    var enemyPreigniterFix = false; private set
    var crystalShardFix = true; private set
    var artilleryGibMountFix = false; private set
    var warningLightPositionFix = false; private set
    var hideHullDuringExplosion = false; private set
    var resistsMindControlStat = false; private set
    var playerHpColorFix = true; private set
    var multipleOverCapacity = false; private set
    var showDummyEquipmentSlots = false; private set
    var dummyEquipmentSlotsOpacity = 0.2f; private set
    var infiniteMode = false; private set
    var forceDlc = false; private set

    /** DefenseDroneFix (CustomDrones.cpp): index 0 player, 1 enemy. */
    var defenseDroneFix = false; private set
    val defenseDroneBoxRange = floatArrayOf(150f, 150f)
    val defenseDroneEllipseRange = floatArrayOf(50f, 50f)
    val combatDroneAlwaysTargetable = booleanArrayOf(true, true)

    // ---- CustomOptionsManager settings (defaults: Setting<T>() = false/0, except the constructor's two) ----
    var upgradeRevisitFix = false; private set
    var droneIonDodgeFix = false; private set
    var combatDroneRapidFireFix = false; internal set
    var oxygenRefillFix = false; private set
    var alternateCrewMovement = false; private set
    var rightClickDoorOpening = true; private set
    var redesignedWeaponTooltips = false; private set
    var redesignedCrewTooltips = false; private set
    var redesignedDroneTooltips = false; private set
    var redesignedAugmentTooltips = false; private set
    var eventTooltips = false; private set
    var showWeaponCooldown = false; private set
    var showReactor = false; private set
    var showMissileCount = false; private set
    var showDroneCount = false; private set
    var showCrewLimit = false; private set
    var showAllConnections = false; private set
    var showScrapCollectorScrap = false; private set
    var preIgniteChargers = false; private set
    var oxygenWithoutSystem = false; private set
    var shieldWithoutSystem = false; private set
    var altLockedMiniships = false; private set
    var altCreditSystem = false; private set
    var allowRenameInputSpecialCharacters = false; private set
    var cloakRenderFix = false; private set
    var dualMedical = false; private set
    var enhancedCloneUI = false; private set
    var scaleSlugGel = false; private set
    var splitAllNodeView = false; private set
    var multiShipFix = false; private set
    var teleporterHackFix = false; private set
    var energyBypassTeleportRecall = false; private set
    var insertNewlineForMultipleCrewTooltips = false; private set
    var disableLazyImageLoading = false; private set
    var disableDefaultTutorial = false; private set
    var alternateOxygenRendering = false; private set
    var advancedCrewTooltips = false; private set
    var showAllyPowers = false; private set
    var showEnemyPowers = false; private set
    var advancedCrewTooltipRounding = 0; private set
    var dismissSound = "airLoss"; private set
    var droneSelectHotkeys = false; private set
    var droneSaveStations = false; private set

    // ---- Other singletons' fields ----
    /** HullNumbers::enabled (static instance, zero-initialised). */
    var hullNumbers = false; private set
    /** CommandConsole (CommandConsole.h): `enabled` zero-initialised, `invertCaps = true`. */
    var consoleEnabled = false; private set
    var consoleInvertCaps = true; private set
    /** CustomShipSelect (CustomShipSelect.h). */
    var showShipAchievements = false; private set
    var shipAchievementsToggle = false; private set
    var hideMissingShipAchievements = false; private set
    var shipAchievementsTitle = "hangar_achievements_title_default"; private set
    /** SeedInputBox (Seeds.cpp). */
    var seedsEnabled = true; private set
    var seedsAllowUnlocks = false; private set
    var seedsAllowAchievements = false; private set
    var seedsAllowMetaVars = true; private set
    /** CustomUpgrades (CustomUpgrades.h). */
    var renameShipAllowed = false; private set
    var renameShipButton = false; private set
    /** CustomTextStyleManager::enabled. */
    var customTextStyle = false; private set
    /** CustomTabbedWindow::enabled (set only when `enabled` is true). */
    var customTabs = false; private set
    /** PurchaseLimitIndicatorInfo (CustomStore.cpp). */
    var purchaseLimitFontSize = 0; private set
    var purchaseLimitX = -16; private set
    var purchaseLimitY = 60; private set
    /** SystemNoPurchaseThreshold (CustomUpgrades.cpp). */
    var systemNoPurchaseThreshold = false; private set
    var systemNoPurchaseThresholdValue = 0; private set
    var systemNoPurchaseThresholdReplace = ""; private set
    /** SaveFileHandler (SaveFile.h): prefix "hs" or "hs_<prefix>", inheritMode 0 FORCE_OLD, 1 ASK_PLAYER, 2 FORCE_NEW. */
    var savePrefix = "hs"; private set
    var saveInheritMode = 0; private set
    var saveWelcomeDialog = false; private set

    /**
     * Event files HS adds (CustomEventsParser::ReadCustomEventFiles): `data/events_hyperspace.xml`, then
     * `data/events_<name>.xml` for each `<events><eventFile>` in order. Files that do not exist are skipped by HS.
     */
    val eventFiles: List<String>
        get() = listOf("data/events_hyperspace.xml") + nodesOf("events").flatMap { n ->
            n.children.filter { it.name == "eventFile" }.map { "data/events_" + it.text + ".xml" }
        }

    /** `<scripts><script>` paths in order (lua workstream runs them). */
    val scripts = ArrayList<String>()

    /** Raw nodes of feature families by top-level name, in file order (e.g. two `<store>` in MV). */
    val nodes = LinkedHashMap<String, MutableList<Element>>()

    /** Settings HS would fail on (a missing required attribute crashes HS; we keep the default and say so). */
    val problems = ArrayList<String>()

    fun node(name: String): Element? = nodes[name]?.lastOrNull()
    fun nodesOf(name: String): List<Element> = nodes[name] ?: emptyList()

    companion object {
        const val HS_MAJOR = 1
        const val HS_MINOR = 23
        const val HS_PATCH = 2
        const val HS_VERSION = "$HS_MAJOR.$HS_MINOR.$HS_PATCH"

        /** HyperspaceVersion::getDeprecatedIntegerVersion. */
        const val HS_DEPRECATED_INT = HS_MAJOR * 100 + HS_MINOR * 10 + HS_PATCH

        /** Every top-level element HS's two passes read; anything else is ignored by HS too. */
        @JvmField
        val TOP_LEVEL: Set<String> = hashSetOf(
            "version", "isBasemod", "defaults", "colors", "ships", "languages", "customCredits", "events",
            "customSystems", "customChoiceColors", "hullNumbers", "playerHpColorFix", "hullBars", "hackingDroneFix",
            "hackingIonFix", "repairDroneRecoveryFix", "controllableIonDroneFix", "upgradeRevisitFix",
            "droneIonDodgeFix", "combatDroneRapidFireFix", "oxygenRefillFix", "enemyPreigniterFix", "crystalShardFix",
            "defenseDroneFix", "artilleryGibMountFix", "warningLightPositionFix", "hideHullDuringExplosion",
            "resistsMindControlStat", "alternateCrewMovement", "rightClickDoorOpening", "redesignedWeaponTooltips",
            "redesignedCrewTooltips", "redesignedDroneTooltips", "redesignedAugmentTooltips", "eventTooltips",
            "showNumericalWeaponCooldown", "showReactor", "extraShipInfo", "showAllConnections",
            "showScrapCollectorScrap", "preIgniteChargers", "oxygenWithoutSystem", "shieldWithoutSystem",
            "altLockedMiniships", "altCreditSystem", "allowRenameInputSpecialCharacters", "targetableArtillery",
            "cloakRenderFix", "dualMedical", "enhancedCloneUI", "scaleSlugGel", "splitAllNodeView", "multiShipFix",
            "teleporterHackFix", "energyBypassTeleportRecall", "insertNewlineForMultipleCrewTooltips",
            "disableLazyImageLoading", "disableDefaultTutorial", "alternateOxygenRendering", "customTabs",
            "advancedCrewTooltips", "dismissSound", "droneSelectHotkeys", "droneSaveStations", "console", "infinite",
            "transformColorMode", "showShipAchievements", "victories", "achievements", "shipGenerators", "crew",
            "rewards", "sectorMap", "augments", "titleScreen", "forceDlc", "boss", "store", "purchaseLimitNumber",
            "drones", "seeds", "renameShipInRun", "customTextStyle", "scripts", "discord", "saveFile",
            "systemNoPurchaseThreshold", "multipleOverCapacity", "showDummyEquipmentSlots"
        )

        /** Top-level nodes whose content a feature workstream parses (kept raw in [nodes]). */
        private val RAW: Set<String> = hashSetOf(
            "colors", "ships", "languages", "customCredits", "events", "customSystems", "customChoiceColors",
            "hullNumbers", "hullBars", "targetableArtillery", "alternateOxygenRendering", "customTabs", "victories",
            "achievements", "shipGenerators", "crew", "rewards", "sectorMap", "augments", "titleScreen", "boss",
            "store", "drones", "discord", "defaults"
        )

        /** EventsParser::ParseBoolean. */
        @JvmStatic
        fun bool(s: String?): Boolean = s == "true" || s == "TRUE" || s == "True"

        /** Defaults only: what HS uses when the file is missing or unreadable. */
        @JvmStatic
        fun defaults(): HsConfig = HsConfig()

        /** Parse a hyperspace.xml root. [root] must be the document element; HS needs it to be `<FTL>`. */
        @JvmStatic
        fun parse(root: Element?): HsConfig = HsConfig().also { it.read(root) }

        /**
         * The `<version>` check of PreInitializeResources: `=` exact, `~` same major.minor and patch <= ours, `^` or
         * no operator (logged as defaulting to `^`) same major and (minor < ours or same minor and patch <= ours);
         * a value without '.' is the deprecated integer form compared with [HS_DEPRECATED_INT].
         * Returns null when accepted, else HS's message.
         */
        @JvmStatic
        fun checkVersion(value: String?): String? {
            val wrong = "Wrong version of Hyperspace detected. Please check that Hyperspace is installed correctly " +
                "and you are using the correct version of Hyperspace for all of your mods. You can continue, but mods " +
                "may function incorrectly."
            if (value == null) return wrong
            if (!value.contains('.')) return if (value.toIntOrNull() == HS_DEPRECATED_INT) null else wrong
            val op = value.firstOrNull()
            val body = if (op == '=' || op == '~' || op == '^') value.substring(1) else value
            val parts = body.split('.')
            if (parts.size != 3) return "Invalid/Unknown Hyperspace version check syntax in mod, could not validate " +
                "hyperspace version matches mod requirements.\nMods may function incorrectly."
            // boost::lexical_cast<unsigned int> throws on anything but digits: HS reports a parse failure.
            val v = parts.map { p -> p.toIntOrNull()?.takeIf { p.all(Char::isDigit) } }
            if (v.any { it == null }) return "Failed parsing hyperspace.xml\nbad lexical cast"
            val (major, minor, patch) = v.map { it!! }
            val ok = when (op) {
                '=' -> major == HS_MAJOR && minor == HS_MINOR && patch == HS_PATCH
                '~' -> major == HS_MAJOR && minor == HS_MINOR && patch <= HS_PATCH
                else -> major == HS_MAJOR && (minor < HS_MINOR || (minor == HS_MINOR && patch <= HS_PATCH))
            }
            return if (ok) null else wrong
        }
    }

    private fun Element.attr(name: String): String? = getAttributeValue(name)

    /** `first_attribute("enabled")->value()`: HS dereferences it unchecked; a missing one would crash HS. */
    private fun Element.enabled(): Boolean? {
        val v = attr("enabled")
        if (v == null) problems += "<$name> has no enabled attribute (Hyperspace would crash here)"
        return v?.let(::bool)
    }

    private fun Element.required(attr: String): String? {
        val v = getAttributeValue(attr)
        if (v == null) problems += "<$name> has no $attr attribute (Hyperspace would crash here)"
        return v
    }

    /** rapidxml `value()`: the element's own text, untrimmed. */
    private fun Element.value(): String = text

    private fun float(e: Element, s: String?): Float? {
        if (s == null) return null
        val f = s.toFloatOrNull()
        if (f == null) problems += "<${e.name}> value '$s' is not a number (Hyperspace: bad lexical cast)"
        return f
    }

    private fun int(e: Element, s: String?): Int? {
        if (s == null) return null
        val i = s.toIntOrNull()
        if (i == null) problems += "<${e.name}> value '$s' is not an integer (Hyperspace: bad lexical cast)"
        return i
    }

    private fun read(root: Element?) {
        if (root == null || root.name != "FTL") {
            versionMessage = "No parent node found in hyperspace.xml"
            return
        }
        var versionSeen: String? = null
        for (node in root.children) {
            val n = node.name
            if (n in RAW) nodes.getOrPut(n) { ArrayList() } += node
            when (n) {
                "version" -> versionSeen = node.value()
                "isBasemod" -> isBasemod = bool(node.value())
                "defaults" -> readDefaults(node)
                "playerHpColorFix" -> node.enabled()?.let { playerHpColorFix = it }
                "hullNumbers" -> if (node.attr("enabled") != null && bool(node.attr("enabled"))) hullNumbers = true
                "hackingDroneFix" -> node.enabled()?.let { hackingDroneFix = it }
                "hackingIonFix" -> node.enabled()?.let { hackingIonFix = it }
                "repairDroneRecoveryFix" -> node.enabled()?.let { repairDroneRecoveryFix = it }
                "controllableIonDroneFix" -> node.enabled()?.let {
                    controllableIonDroneFix = it
                    if (it) {
                        float(node, node.attr("ionDelay"))?.let { d ->
                            controllableIonDroneDelay = d; controllableIonDroneDelayInitial = d
                        }
                        float(node, node.attr("ionDelayInitial"))?.let { d -> controllableIonDroneDelayInitial = d }
                    }
                }
                "upgradeRevisitFix" -> node.enabled()?.let { upgradeRevisitFix = it }
                "droneIonDodgeFix" -> node.enabled()?.let { droneIonDodgeFix = it }
                "combatDroneRapidFireFix" -> node.enabled()?.let { combatDroneRapidFireFix = it }
                "oxygenRefillFix" -> node.enabled()?.let { oxygenRefillFix = it }
                "enemyPreigniterFix" -> node.enabled()?.let { enemyPreigniterFix = it }
                "crystalShardFix" -> node.enabled()?.let { crystalShardFix = it }
                "defenseDroneFix" -> node.enabled()?.let { defenseDroneFix = it; if (it) readDefenseDroneFix(node) }
                "artilleryGibMountFix" -> node.enabled()?.let { artilleryGibMountFix = it }
                "warningLightPositionFix" -> node.enabled()?.let { warningLightPositionFix = it }
                "hideHullDuringExplosion" -> node.enabled()?.let { hideHullDuringExplosion = it }
                "resistsMindControlStat" -> node.enabled()?.let { resistsMindControlStat = it }
                "alternateCrewMovement" -> node.enabled()?.let { alternateCrewMovement = it }
                "rightClickDoorOpening" -> node.enabled()?.let { rightClickDoorOpening = it }
                "redesignedWeaponTooltips" -> node.enabled()?.let { redesignedWeaponTooltips = it }
                "redesignedCrewTooltips" -> node.enabled()?.let { redesignedCrewTooltips = it }
                "redesignedDroneTooltips" -> node.enabled()?.let { redesignedDroneTooltips = it }
                "redesignedAugmentTooltips" -> node.enabled()?.let { redesignedAugmentTooltips = it }
                "eventTooltips" -> node.enabled()?.let { eventTooltips = it }
                "showNumericalWeaponCooldown" -> node.enabled()?.let { showWeaponCooldown = it }
                "showReactor" -> node.enabled()?.let { showReactor = it }
                "extraShipInfo" -> {
                    node.required("missileCount")?.let { showMissileCount = bool(it) }
                    node.required("droneCount")?.let { showDroneCount = bool(it) }
                    node.required("crewLimit")?.let { showCrewLimit = bool(it) }
                }
                "showAllConnections" -> node.enabled()?.let { showAllConnections = it }
                "showScrapCollectorScrap" -> node.enabled()?.let { showScrapCollectorScrap = it }
                "preIgniteChargers" -> node.enabled()?.let { preIgniteChargers = it }
                "oxygenWithoutSystem" -> node.enabled()?.let { oxygenWithoutSystem = it }
                "shieldWithoutSystem" -> node.enabled()?.let { shieldWithoutSystem = it }
                "altLockedMiniships" -> node.enabled()?.let { altLockedMiniships = it }
                "altCreditSystem" -> node.enabled()?.let { altCreditSystem = it }
                "allowRenameInputSpecialCharacters" -> node.enabled()?.let { allowRenameInputSpecialCharacters = it }
                "cloakRenderFix" -> node.enabled()?.let { cloakRenderFix = it }
                "dualMedical" -> node.enabled()?.let { dualMedical = it }
                "enhancedCloneUI" -> node.enabled()?.let { enhancedCloneUI = it }
                "scaleSlugGel" -> node.enabled()?.let { scaleSlugGel = it }
                "splitAllNodeView" -> node.enabled()?.let { splitAllNodeView = it }
                "multiShipFix" -> node.enabled()?.let { multiShipFix = it }
                "teleporterHackFix" -> node.enabled()?.let { teleporterHackFix = it }
                "energyBypassTeleportRecall" -> node.enabled()?.let { energyBypassTeleportRecall = it }
                "insertNewlineForMultipleCrewTooltips" -> node.enabled()?.let { insertNewlineForMultipleCrewTooltips = it }
                "disableLazyImageLoading" -> node.enabled()?.let { disableLazyImageLoading = it }
                "disableDefaultTutorial" -> node.enabled()?.let { disableDefaultTutorial = it }
                "alternateOxygenRendering" -> node.enabled()?.let { alternateOxygenRendering = it }
                "customTabs" -> node.enabled()?.let { if (it) { customTabs = true; xyz.znix.xftl.hs.render.HsTabs.parse(node) } }
                "advancedCrewTooltips" -> node.enabled()?.let {
                    advancedCrewTooltips = it
                    if (it) {
                        node.attr("ally")?.let { a -> showAllyPowers = bool(a) }
                        node.attr("enemy")?.let { a -> showEnemyPowers = bool(a) }
                        int(node, node.attr("rounding"))?.let { r -> advancedCrewTooltipRounding = r }
                    }
                }
                "dismissSound" -> node.enabled()?.let {
                    if (it) node.attr("sound")?.let { s -> dismissSound = s } else dismissSound = ""
                }
                "droneSelectHotkeys" -> node.enabled()?.let { droneSelectHotkeys = it }
                "droneSaveStations" -> node.enabled()?.let { droneSaveStations = it }
                "console" -> {
                    node.enabled()?.let { consoleEnabled = it }
                    node.attr("invertCaps")?.let { consoleInvertCaps = bool(it) }
                }
                "infinite" -> node.enabled()?.let { infiniteMode = it }
                // Resources.cpp compares node->name() (always "transformColorMode") with the mode names, so the
                // setting never changes g_transformColorMode from KEEP_INDICES; nothing to read.
                "transformColorMode" -> {}
                "showShipAchievements" -> {
                    node.attr("enabled")?.let { showShipAchievements = bool(it) }
                    node.attr("toggle")?.let { shipAchievementsToggle = bool(it) }
                    node.attr("hideIfMissing")?.let { hideMissingShipAchievements = bool(it) }
                    node.attr("id")?.let { shipAchievementsTitle = it }
                }
                "forceDlc" -> node.enabled()?.let { forceDlc = it }
                "purchaseLimitNumber" -> {
                    int(node, node.required("fontSize"))?.let { purchaseLimitFontSize = it }
                    int(node, node.required("x"))?.let { purchaseLimitX = it }
                    int(node, node.required("y"))?.let { purchaseLimitY = it }
                }
                "seeds" -> {
                    node.attr("enabled")?.let { seedsEnabled = bool(it) }
                    node.attr("allowUnlocks")?.let { seedsAllowUnlocks = bool(it) }
                    node.attr("allowAchievements")?.let { seedsAllowAchievements = bool(it) }
                    node.attr("allowMetaVars")?.let { seedsAllowMetaVars = bool(it) }
                }
                "renameShipInRun" -> {
                    node.attr("enabled")?.let { renameShipAllowed = bool(it) }
                    node.attr("allowButton")?.let { renameShipButton = bool(it) }
                }
                "customTextStyle" -> node.enabled()?.let { customTextStyle = it }
                "scripts" -> for (c in node.children) if (c.name == "script") scripts += c.value()
                "saveFile" -> readSaveFile(node)
                "systemNoPurchaseThreshold" -> node.enabled()?.let {
                    systemNoPurchaseThreshold = it
                    if (it) {
                        int(node, node.required("threshold"))?.let { t -> systemNoPurchaseThresholdValue = t }
                        node.required("replace")?.let { r -> systemNoPurchaseThresholdReplace = r }
                    }
                }
                "multipleOverCapacity" -> node.enabled()?.let { multipleOverCapacity = it }
                "showDummyEquipmentSlots" -> node.enabled()?.let {
                    showDummyEquipmentSlots = it
                    float(node, node.attr("opacity"))?.let { o -> dummyEquipmentSlotsOpacity = o }
                }
            }
        }
        versionRequirement = versionSeen
        versionMessage = checkVersion(versionSeen)
        versionOk = versionMessage == null
    }

    private fun readDefaults(node: Element) {
        for (c in node.children) when (c.name) {
            "checkCargo" -> defaultCheckCargo = bool(c.value())
            "choiceRequiresCrew" -> defaultChoiceRequiresCrew = bool(c.value())
            "beaconType_hideVanillaLabel" -> defaultBeaconTypeHideVanillaLabel = bool(c.value())
            "instantEscape_removeShipBox" -> defaultInstantEscapeRemoveShipBox = bool(c.value())
            // ErosionEffect::ParseErosionEffect reads the effect itself (systems workstream, from nodes["defaults"]).
            "erosion" -> for (e in c.children) when (e.name) {
                "growBreachMultiplier" -> float(e, e.value())?.let { erosionGrowBreachMultiplier = it }
                "newBreachDamage" -> float(e, e.value())?.let { erosionNewBreachDamage = it }
                "newBreachRequirement" -> float(e, e.value())?.let { erosionNewBreachRequirement = it }
                "canDilate" -> erosionCanDilate = bool(e.value())
            }
        }
    }

    private fun readDefenseDroneFix(node: Element) {
        fun ranges(c: Element, into: FloatArray) {
            if (c.value().isNotEmpty()) float(c, c.value())?.let { into[0] = it; into[1] = it }
            float(c, c.getAttributeValue("player"))?.let { into[0] = it }
            float(c, c.getAttributeValue("enemy"))?.let { into[1] = it }
        }
        for (c in node.children) when (c.name) {
            "boxRange" -> ranges(c, defenseDroneBoxRange)
            "ellipseRange" -> ranges(c, defenseDroneEllipseRange)
            "combatDroneAlwaysTargetable" -> {
                if (c.value().isNotEmpty()) bool(c.value()).let { combatDroneAlwaysTargetable[0] = it; combatDroneAlwaysTargetable[1] = it }
                c.getAttributeValue("player")?.let { combatDroneAlwaysTargetable[0] = bool(it) }
                c.getAttributeValue("enemy")?.let { combatDroneAlwaysTargetable[1] = bool(it) }
            }
        }
    }

    private fun readSaveFile(node: Element) {
        for (c in node.children) when (c.name) {
            "prefix" -> savePrefix = if (c.value() != "hs") "hs_" + c.value() else c.value()
            "inheritMode" -> int(c, c.value())?.let { saveInheritMode = it }
            "welcomeDialog" -> saveWelcomeDialog = bool(c.value())
        }
    }
}
