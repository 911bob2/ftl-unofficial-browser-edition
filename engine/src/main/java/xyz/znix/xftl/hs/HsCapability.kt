package xyz.znix.xftl.hs

import ftl.browser.BrowserHsScan

/**
 * What a Hyperspace session uses that the engine does not implement yet (PLAN.md I2): the XML scan over the merged
 * files and the HS rule changes ([RULES]) that the loaded `hyperspace.xml` switches on. Results go to
 * [HS.unsupported]; `BrowserEngine.hsCapabilityReport()` exports them.
 *
 * Feature workstreams register what they implement with [implement] (element names, `element@attribute`, or
 * `hyperspace.xml` top-level names), and mark rules done by flipping [Rule.implemented] in [RULES].
 */
object HsCapability {
    /** Owner workstreams, as in .build/hs/PLAN.md and docs/HYPERSPACE.md. */
    enum class Owner { CORE, EVENTS, STAT, CREW, SHIP, SYSTEMS, WEAPONS, RENDER, UI, LUA }

    /**
     * One HS behaviour change of the catalogue (docs/HYPERSPACE.md) that applies when [active] says so under the
     * loaded config: unconditional ones return true, option-gated ones read the option.
     */
    class Rule(
        val id: String, val hsFile: String, val owner: Owner, val implemented: Boolean,
        val active: (HsConfig) -> Boolean
    )

    private fun on(id: String, file: String, owner: Owner, implemented: Boolean = false, gate: (HsConfig) -> Boolean) =
        Rule(id, file, owner, implemented, gate)

    private fun always(id: String, file: String, owner: Owner, implemented: Boolean = false) =
        Rule(id, file, owner, implemented) { true }

    /**
     * Implemented flags follow the workstream reports of round 2 (.build/hs/reports/<name>.md, coordinator worktree);
     * the compatibility matrix in docs/HYPERSPACE.md says which of them are verified against the original.
     *
     * Rule changes HS makes beyond content. Option ids are the hyperspace.xml element names; unconditional ones
     * are named after their HS hook. Content features (customShip, statBoost, events, ...) are tracked by the XML
     * scan instead. Keep in step with the catalogue table in docs/HYPERSPACE.md.
     */
    @JvmField
    val RULES: List<Rule> = listOf(
        // --- core ---
        on("version", "Utilities/Resources.cpp", Owner.CORE, implemented = true) { true },
        on("forceDlc", "UserInterface/CustomShipSelect.cpp", Owner.CORE, implemented = true) { it.forceDlc },
        always("CrewNames.GetUnusedCrewName", "Gameplay/World/CrewNames.cpp", Owner.CORE, implemented = true),
        on("seeds", "Gameplay/World/Seeds.cpp", Owner.EVENTS, implemented = true) { it.seedsEnabled },
        always("StarMap.LoadGame/GenerateMap", "Gameplay/World/StarMap.cpp", Owner.CORE),
        always("SpaceId.SaveGame/LoadGame", "Gameplay/SpaceId.cpp", Owner.CORE, implemented = true),
        // Modded saves are stored apart per mod set (loader, PLAN.md I3): web/app.mjs starts a new set from the vanilla profile (inheritMode 0), asks (1) or starts blank (2, FORCE_NEW).
        on("saveFile.inheritMode", "UserInterface/SaveFile.cpp", Owner.CORE, implemented = true) { it.saveInheritMode != 2 },
        on("disableDefaultTutorial", "Gameplay/CustomTutorial.cpp", Owner.CORE, implemented = true) { it.disableDefaultTutorial },
        on("infinite", "Gameplay/World/Infinite.cpp", Owner.CORE, implemented = true) { it.infiniteMode },
        on("upgradeRevisitFix", "Gameplay/World/CustomEvents.cpp", Owner.EVENTS) { it.upgradeRevisitFix },
        on("console", "UserInterface/CommandConsole.cpp", Owner.UI, implemented = true) { it.consoleEnabled },
        // --- events / map ---
        on("defaults.checkCargo", "Gameplay/World/CustomEvents.cpp", Owner.EVENTS, implemented = true) { it.defaultCheckCargo },
        on("defaults.choiceRequiresCrew", "Gameplay/World/CustomEvents.cpp", Owner.EVENTS) { it.defaultChoiceRequiresCrew },
        on("multiShipFix", "Gameplay/World/CustomEvents.cpp", Owner.EVENTS, implemented = true) { it.multiShipFix },
        on("showAllConnections", "Gameplay/World/CustomMap.cpp", Owner.UI, implemented = true) { it.showAllConnections },
        on("splitAllNodeView", "Gameplay/World/StarMap.cpp", Owner.UI, implemented = true) { it.splitAllNodeView },
        on("altCreditSystem", "Gameplay/World/CustomEvents.cpp", Owner.UI) { it.altCreditSystem },
        // --- systems ---
        on("hackingDroneFix", "Gameplay/Balance.cpp", Owner.SYSTEMS, true) { it.hackingDroneFix },
        on("hackingIonFix", "Gameplay/Balance.cpp", Owner.SYSTEMS, implemented = true) { it.hackingIonFix },
        always("HackingSystem.SoundLoop", "Ship/ShipSystems/HackingSystem.cpp", Owner.SYSTEMS, true),
        always("OxygenSystem.OnLoop", "Ship/ShipSystems/OxygenSystem.cpp", Owner.SYSTEMS, true),
        on("oxygenRefillFix", "Ship/ShipSystems/OxygenSystem.cpp", Owner.SYSTEMS) { it.oxygenRefillFix },
        on("oxygenWithoutSystem", "Ship/OxygenWithoutSystem.cpp", Owner.SYSTEMS, implemented = true) { it.oxygenWithoutSystem },
        on("shieldWithoutSystem", "Ship/ShipSystems/CustomSystems.cpp", Owner.SYSTEMS, implemented = true) { it.shieldWithoutSystem },
        on("dualMedical", "Ship/ShipSystems/CustomSystems.cpp", Owner.SYSTEMS, implemented = true) { it.dualMedical },
        always("TeleporterBugFixes", "Gameplay/AI/TeleporterBugFixes.cpp", Owner.SYSTEMS, true),
        on("teleporterHackFix", "Gameplay/AI/TeleporterBugFixes.cpp", Owner.SYSTEMS, implemented = true) { it.teleporterHackFix },
        on("energyBypassTeleportRecall", "Gameplay/AI/TeleporterBugFixes.cpp", Owner.SYSTEMS, implemented = true) { it.energyBypassTeleportRecall },
        on("targetableArtillery", "Ship/ShipSystems/ArtillerySystem.cpp", Owner.SYSTEMS, implemented = true) { it.nodesOf("targetableArtillery").any { n -> HsConfig.bool(n.getAttributeValue("enabled")) } },
        always("CloneSystem.jumpClone", "Ship/ShipSystems/CustomSystems.cpp", Owner.SYSTEMS, true),
        on("resistsMindControlStat", "Ship/CustomCrew.cpp", Owner.CREW, implemented = true) { it.resistsMindControlStat },
        // --- weapons / drones ---
        on("repairDroneRecoveryFix", "Gameplay/Balance.cpp", Owner.WEAPONS, implemented = true) { it.repairDroneRecoveryFix },
        on("controllableIonDroneFix", "Gameplay/Balance.cpp", Owner.WEAPONS, implemented = true) { it.controllableIonDroneFix },
        on("defenseDroneFix", "Gameplay/CustomDrones.cpp", Owner.WEAPONS, implemented = true) { it.defenseDroneFix },
        on("droneIonDodgeFix", "Gameplay/CustomDrones.cpp", Owner.WEAPONS, implemented = true) { it.droneIonDodgeFix },
        on("combatDroneRapidFireFix", "Gameplay/CustomDrones.cpp", Owner.WEAPONS, implemented = true) { it.combatDroneRapidFireFix },
        on("droneSelectHotkeys", "Gameplay/CustomDrones.cpp", Owner.UI) { it.droneSelectHotkeys },
        on("droneSaveStations", "Gameplay/CustomDrones.cpp", Owner.UI) { it.droneSaveStations },
        on("crystalShardFix", "Ship/CustomCrystalShard.cpp", Owner.WEAPONS, implemented = true) { it.crystalShardFix },
        always("ShipManager.CheckCrystalAugment", "Ship/CustomCrystalShard.cpp", Owner.WEAPONS, implemented = true),
        always("BeamFixes", "Ship/BeamFixes.cpp", Owner.WEAPONS, implemented = true),
        always("ProjectileFactory.SpendMissiles", "Gameplay/AugmentValues.cpp", Owner.WEAPONS, implemented = true),
        on("preIgniteChargers", "Gameplay/CustomWeapons.cpp", Owner.WEAPONS, implemented = true) { it.preIgniteChargers },
        on("enemyPreigniterFix", "Ship/CustomShips.cpp", Owner.WEAPONS, implemented = true) { it.enemyPreigniterFix },
        // --- ship / crew ---
        on("artilleryGibMountFix", "Ship/CustomShips.cpp", Owner.RENDER, implemented = true) { it.artilleryGibMountFix },
        on("warningLightPositionFix", "Ship/CustomShips.cpp", Owner.RENDER, implemented = true) { it.warningLightPositionFix },
        on("hideHullDuringExplosion", "Ship/CustomShips.cpp", Owner.RENDER, implemented = true) { it.hideHullDuringExplosion },
        on("cloakRenderFix", "Ship/CustomShips.cpp", Owner.RENDER, implemented = true) { it.cloakRenderFix },
        on("scaleSlugGel", "Ship/CustomAugments.cpp", Owner.SHIP, implemented = true) { it.scaleSlugGel },
        on("multipleOverCapacity", "UserInterface/CustomEquipment.cpp", Owner.UI, implemented = true) { it.multipleOverCapacity },
        on("alternateCrewMovement", "UserInterface/TileBasedCrewMovement.cpp", Owner.CREW, implemented = true) { it.alternateCrewMovement },
        on("rightClickDoorOpening", "UserInterface/TileBasedCrewMovement.cpp", Owner.UI, implemented = true) { it.rightClickDoorOpening },
        // --- UI options ---
        on("hullNumbers", "UserInterface/HullNumbers.cpp", Owner.RENDER, implemented = true) { it.hullNumbers },
        on("playerHpColorFix", "UserInterface/HullNumbers.cpp", Owner.RENDER, implemented = true) { it.playerHpColorFix },
        on("hullBars", "UserInterface/HullNumbers.cpp", Owner.RENDER, implemented = true) { it.node("hullBars") != null },
        on("showNumericalWeaponCooldown", "UserInterface/CooldownNumbers.cpp", Owner.WEAPONS, implemented = true) { it.showWeaponCooldown },
        on("redesignedTooltips", "UserInterface/RedesignedTooltips.cpp", Owner.UI, implemented = true) {
            it.redesignedWeaponTooltips || it.redesignedCrewTooltips || it.redesignedDroneTooltips || it.redesignedAugmentTooltips
        },
        on("advancedCrewTooltips", "Ship/CustomCrew.cpp", Owner.UI, implemented = true) { it.advancedCrewTooltips },
        on("eventTooltips", "UserInterface/EventTooltip.cpp", Owner.RENDER, implemented = true) { it.eventTooltips },
        on("showReactor", "UserInterface/CustomShipSelect.cpp", Owner.UI, implemented = true) { it.showReactor },
        on("extraShipInfo", "UserInterface/CustomShipSelect.cpp", Owner.UI, implemented = true) { it.showMissileCount || it.showDroneCount || it.showCrewLimit },
        on("showShipAchievements", "UserInterface/CustomShipSelect.cpp", Owner.UI, implemented = true) { it.showShipAchievements },
        on("showDummyEquipmentSlots", "UserInterface/CustomShipSelect.cpp", Owner.UI, implemented = true) { it.showDummyEquipmentSlots },
        on("altLockedMiniships", "UserInterface/CustomShipSelect.cpp", Owner.UI, implemented = true) { it.altLockedMiniships },
        on("showScrapCollectorScrap", "Ship/CustomAugments.cpp", Owner.UI) { it.showScrapCollectorScrap },
        on("enhancedCloneUI", "UserInterface/CloneQueue.cpp", Owner.UI, implemented = true) { it.enhancedCloneUI },
        on("insertNewlineForMultipleCrewTooltips", "Ship/CustomCrew.cpp", Owner.UI, implemented = true) { it.insertNewlineForMultipleCrewTooltips },
        on("renameShipInRun", "UserInterface/CustomUpgrades.cpp", Owner.UI, implemented = true) { it.renameShipAllowed },
        on("allowRenameInputSpecialCharacters", "UserInterface/CustomCrewManifest.cpp", Owner.UI, implemented = true) { it.allowRenameInputSpecialCharacters },
        on("dismissSound", "UserInterface/CustomCrewManifest.cpp", Owner.UI, implemented = true) { it.dismissSound != "airLoss" },
        on("systemNoPurchaseThreshold", "UserInterface/CustomUpgrades.cpp", Owner.UI, implemented = true) { it.systemNoPurchaseThreshold },
        on("alternateOxygenRendering", "Ship/AlternateOxygenRendering.cpp", Owner.RENDER, implemented = true) { it.alternateOxygenRendering },
        on("customTabs", "UserInterface/CustomTabbedWindow.cpp", Owner.UI, implemented = true) { it.customTabs },
        on("customTextStyle", "Utilities/CustomTextStyle.cpp", Owner.RENDER, implemented = true) { it.customTextStyle },
        on("titleScreen", "UserInterface/MainMenu.cpp", Owner.RENDER, implemented = true) { it.node("titleScreen") != null },
        always("SystemDescriptionFixes", "Ship/ShipSystems/SystemDescriptionFixes.cpp", Owner.UI, true),
        always("ArtilleryBarFix", "UserInterface/ArtilleryBarFix.cpp", Owner.SYSTEMS, implemented = true),
        always("MoreInfoButton", "UserInterface/MoreInfoButton.cpp", Owner.RENDER, implemented = true),
        always("EnemyShipIcons", "UserInterface/EnemyShipIcons.cpp", Owner.RENDER, implemented = true),
        always("CustomMap.DrawConnection", "Gameplay/World/CustomMap.cpp", Owner.RENDER),
        always("Credits.GameOver.OnLoop", "UserInterface/Credits.cpp", Owner.UI, implemented = true),
        always("CustomHotkeys", "UserInterface/CustomHotkeys.cpp", Owner.UI, implemented = true),
        always("ScrollingChoiceBox", "UserInterface/ScrollingChoiceBox.cpp", Owner.RENDER, implemented = true),
        always("CustomScoreKeeper", "Gameplay/CustomScoreKeeper.cpp", Owner.SHIP, implemented = true),
    )

    /** Names marked implemented by the workstreams (element, `element@attribute`, or hyperspace.xml top-level). */
    private val implemented = HashSet<String>()

    @JvmStatic
    fun implement(vararg names: String) {
        implemented.addAll(names)
    }

    @JvmStatic
    fun isImplemented(name: String) = name in implemented

    /** hyperspace.xml top-level elements that are only options (their effect is a [RULES] entry, not content). */
    private val OPTION_NODES: Set<String> = HsConfig.TOP_LEVEL - setOf(
        "colors", "ships", "languages", "customCredits", "events", "customSystems", "customChoiceColors",
        "victories", "achievements", "shipGenerators", "crew", "rewards", "sectorMap", "augments", "boss", "store",
        "drones", "scripts", "discord"
    )

    /** Elements and attributes HS ignores too (neither vanilla nor HS): name -> count, for information. */
    @JvmField
    val ignored = LinkedHashMap<String, Int>()

    @JvmStatic
    fun ignore(key: String, count: Int) {
        ignored[key] = (ignored[key] ?: 0) + count
    }

    /** Run the scan for the current session; [HS.init] calls this once when HS is active. */
    @JvmStatic
    fun run(config: HsConfig) {
        ignored.clear()
        xyz.znix.xftl.hs.systems.HsSystems.registerCapabilities()
        for (rule in RULES) {
            if (!rule.implemented && rule.active(config)) HS.unsupported("rule", rule.hsFile, rule.id)
        }
        val text = BrowserHsScan.scan(
            HsTags.HS_ONLY_ELEMENTS.joinToString("\n"), HsTags.VANILLA_ELEMENTS.joinToString("\n"),
            HsTags.HS_ATTRIBUTES.joinToString("\n"), HsTags.VANILLA_ATTRIBUTE_PAIRS.joinToString("\n")
        )
        if (text.isEmpty()) return
        for (line in text.split('\n')) {
            val p = line.split('\t')
            if (p.size != 4) continue
            val file = p[0]; val kind = p[1]; val name = p[2]; val count = p[3].toIntOrNull() ?: 1
            // Only names no engine source reads (HsTagsUnread, generated) are gaps: the rest have a reader, whose fidelity
            // docs/HYPERSPACE.md tracks per feature.
            when (kind) {
                "top" -> when {
                    name !in HsConfig.TOP_LEVEL -> ignore("$file|$name", count)
                    name in OPTION_NODES || isImplemented(name) || name !in HsTagsUnread.ELEMENTS -> {}
                    else -> HS.unsupported("xml", file, name, count)
                }
                "hs" -> if (name in HsTagsUnread.ELEMENTS && !isImplemented(name)) HS.unsupported("xml", file, name, count)
                "attr" -> if (name.substringAfter('@') in HsTagsUnread.ATTRIBUTES && !isImplemented(name)) HS.unsupported("xml-attr", file, name, count)
                "other" -> ignore("$file|$name", count)
            }
        }
        for (script in config.scripts) {
            if (!HS.luaActive) HS.unsupported("lua", script, "script")
        }
    }
}
