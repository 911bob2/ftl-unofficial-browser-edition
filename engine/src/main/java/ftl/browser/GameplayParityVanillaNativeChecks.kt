package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Vanilla rules confirmed against FTL 1.6.14 during the Hyperspace work (docs/ORIGINAL_BEHAVIOR.md, "Vanilla native
 * fixes"): mind control canUse and the backdrop shade behind an event box and the focus windows. The combat drones' AddShip hops are known
 * answers in [GameplayParityEnemyGenChecks].
 */
internal object GameplayParityVanillaNativeChecks {
    fun run(main: MainGame, container: GameContainer): String {
        if (xyz.znix.xftl.hs.HS.active) return "vanilla native: skipped under Hyperspace"
        return "vanilla native: ${mindCanUse(main, container)}, ${shade(main, container)}, ${bossEvents(main, container)}, " +
            "${bossEnds(main, container)}, ${crewPicks(main, container)}, ${nebula(main, container)}, ${cloneTooltip(main, container)}, " +
            choiceRules(main, container)
    }

    /**
     * Choice rules (ps-w2; 1.6.14 CheckRequirements 0x6130e0, CreateChoiceBox 0x616680; native session ps-w2-van):
     * the repair station's choices against the hull damage, FUEL_ON_SLUG_CHUCKLE_LIST's stealing Continue open with no
     * scrap, and TRADER_UPGRADES_LIST's max_group (both upgrades qualify at level 1: the first, cheaper one survives and
     * moves after the decline).
     */
    private fun choiceRules(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val ui = state.shipUI
        ship.scrap = 200
        val repair = state.eventManager["REPAIR_STATION"]
        for ((damage, native) in listOf(0 to "0001", 3 to "0011", 8 to "0111", 12 to "1111")) {
            ship.health = ship.maxHealth - damage
            ui.showEventDialogue(state.eventCreator.create(repair, 0, true) ?: error("No REPAIR_STATION"))
            val box = ui.diagnosticDialogue!!
            val got = box.diagnosticChoices().joinToString("") { if (it.second) "1" else "0" }
            check(got == native) { "REPAIR_STATION at $damage hull damage: $got, native $native" }
            box.selectOption(3)
            BrowserDiagnostics.closeBoxes(main, container)
        }
        ship.health = ship.maxHealth

        // HS's LOADEVENT (CommandConsole.cpp:279-302) leaves no unique event used
        state.eventManager.hsReturnUnique(repair as xyz.znix.xftl.sector.Event)
        xyz.znix.xftl.hs.render.HsConsole.run(state, "LOADEVENT REPAIR_STATION")
        check(ui.diagnosticDialogue != null) { "LOADEVENT REPAIR_STATION opened no box" }
        BrowserDiagnostics.closeBoxes(main, container)
        check(!state.eventManager.uniqueEvents.isConsumed(repair)) { "LOADEVENT used up REPAIR_STATION" }

        ship.scrap = 0
        val chuckle = (state.eventManager["FUEL_ON_SLUG_CHUCKLE_LIST"] as xyz.znix.xftl.sector.EventList).events[0] as xyz.znix.xftl.sector.Event
        ui.showEventDialogue(state.eventCreator.create(chuckle, 0, true) ?: error("No FUEL_ON_SLUG_CHUCKLE_LIST"))
        val steal = ui.diagnosticDialogue!!.diagnosticChoices()
        check(steal.size == 1 && steal[0].second) { "A stealing Continue with no scrap: $steal" }
        BrowserDiagnostics.closeBoxes(main, container)

        ship.scrap = 200
        val trader = (state.eventManager["TRADER_UPGRADES_LIST"] as xyz.znix.xftl.sector.EventList).events[0] as xyz.znix.xftl.sector.Event
        check(ship.systems.first { it.codename == "oxygen" }.energyLevels == 1) { "The fixture's oxygen is not at level 1" }
        ui.showEventDialogue(state.eventCreator.create(trader, 0, true) ?: error("No TRADER_UPGRADES_LIST"))
        val upgrades = ui.diagnosticDialogue!!.diagnosticChoices()
        check(upgrades.size == 2 && upgrades[0].third == 0 && upgrades[1].third in -20..-15) {
            "TRADER_UPGRADES_LIST oxygen at level 1: ${upgrades.map { it.third }}, native [decline, upgrade for 15-20 scrap]"
        }
        BrowserDiagnostics.closeBoxes(main, container)
        return "choice rules"
    }

    /**
     * The clone bay's system tooltip (0x004df760, capture wp5c-lanius `l2-hover-160`, Lanius A at level 1): the jump
     * healing line sits between the description and the level line.
     */
    private fun cloneTooltip(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC")
        val clonebay = state.player.clonebay!!
        val text = xyz.znix.xftl.game.SystemButtonTooltip(state, clonebay).getText()
        // 0x4df760 ends a part before a blank line, and each bullet, with a space (" \n\n", " \n")
        val native = listOf("Clone Bay: Automatically clones any dead crewmember. ", "", "Heals all crew 8 hp every jump. ", "",
            "Level 1: 12 sec clone ", "", "Status:", "-Fully Powered ").joinToString("\n")
        check(text.startsWith(native)) { "Clone bay tooltip: " + text.replace('\n', '|') }
        clonebay.damagedEnergyLevels = 1
        val damaged = xyz.znix.xftl.game.SystemButtonTooltip(state, clonebay).getText()
        check(damaged.contains("Heals all crew 0 hp every jump. (If repaired, it will heal more)")) { "Damaged clone bay tooltip: " + damaged.replace('\n', '|') }
        clonebay.damagedEnergyLevels = 0
        return "clone bay tooltip"
    }

    /**
     * The crew an event kills (native vr: `.build/vn/native/vr-crew-*.trace.bin`, CREW_DEAD_TEST): CreateChoiceBox
     * (0x616680) draws the crew seed when the event's own box is made (ret 0x618261), SelectRandomCrew reseeds with it
     * and picks once (0x4a317a); the box shows that crew member, who dies when the box closes without another draw.
     * A choice's preview never picks (PIRATE_SLAVER's first choice kills crew).
     */
    private fun crewPicks(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val events = state.eventManager
        val ui = state.shipUI
        val created = state.eventCreator.create(state.eventManager["CREW_DEAD_TEST"], 0) ?: error("No CREW_DEAD_TEST")
        val crewBefore = state.playerCrew.size
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        try { ui.showEventDialogue(created) } finally { NativeRandom.trace = null }
        val lost = ui.diagnosticDialogue?.diagnosticResources?.lostCrew ?: emptyList()
        val seedAt = trace.indexOf(Int.MIN_VALUE)
        check(events.hasCrewKillSeed && lost.size == 1 && seedAt >= 1 && trace.size - seedAt - 2 == 1) {
            "CREW_DEAD_TEST box: seed ${events.hasCrewKillSeed}, ${lost.size} picked, trace ${trace.size} (reseed at $seedAt)"
        }
        val victim = lost[0].crew
        val (_, closeDraws) = counted { ui.diagnosticDialogue!!.selectOption(0) }
        check(closeDraws == 0 && state.playerCrew.size == crewBefore - 1 && victim !in state.playerCrew) {
            "Closing the box drew $closeDraws or killed someone else (${state.playerCrew.size} of $crewBefore)"
        }

        val parent = state.eventCreator.create(state.eventManager["PIRATE_SLAVER"], 0) ?: error("No PIRATE_SLAVER")
        if (state.enemy != null) state.browserEndHostility()
        ui.showEventDialogue(parent)
        val dialog = ui.diagnosticDialogue ?: error("No PIRATE_SLAVER box")
        val preview = dialog.diagnosticChoiceResources(0)
        check(preview != null && preview.lostCrew.isEmpty() && !events.hasCrewKillSeed) { "The preview picked the crew (seed ${events.hasCrewKillSeed})" }
        dialog.selectOption(0)
        check(events.hasCrewKillSeed && ui.diagnosticDialogue?.diagnosticResources?.lostCrew?.size == 1) { "The chosen event did not pick at its box" }
        return "crew picks (box, close, preview)"
    }

    /**
     * SpaceManager::SetNebula (0x41fd00; native vr-neb-box): ten position draws (ret 0x41fe49 x, 0x41fe87 y in the
     * order x | y x y y | y x y y | x), twelve clouds of four (0x420160 pick, 0x41ff84 scale, 0x41ffc0 threshold,
     * 0x420042 timer), then 800 OnLoopNebulas whose successors draw three each (0x41e928/0x41e96d/0x41ea15; twelve
     * in that capture); per frame only successors draw (vr-neb-3s / -13s: 1 and 15).
     */
    private fun nebula(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val still = xyz.znix.xftl.environment.NebulaEnvironment(state, state.currentBeacon, false)
        val (_, stillDraws) = counted { still.setNebula(0f) }
        check(stillDraws == 58 && still.browserCloudCount == 12) { "SetNebula at speed 0: $stillDraws draws, ${still.browserCloudCount} clouds" }
        val busy = xyz.znix.xftl.environment.NebulaEnvironment(state, state.currentBeacon, false)
        val (_, busyDraws) = counted { busy.setNebula(0.5f) }
        check(busyDraws >= 58 + 3 * 12 && (busyDraws - 58) % 3 == 0) { "SetNebula at speed 0.5: $busyDraws draws" }
        val (_, frames) = counted { repeat(600) { busy.update(1f / 60f) } }
        check(frames > 0 && frames % 3 == 0) { "Ten seconds of clouds drew $frames" }
        return "nebula clouds ($stillDraws, $busyDraws, $frames per 10 s)"
    }

    /** Run [block] and count the draws it makes from the stream (a reseed is not a draw). */
    private fun <T> counted(block: () -> T): Pair<T, Int> {
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        val result = try { block() } finally { NativeRandom.trace = null }
        return result to draws(trace)
    }

    private fun draws(trace: List<Int>): Int {
        var draws = 0
        var i = 0
        while (i < trace.size) {
            if (trace[i] == Int.MIN_VALUE) i += 2 else { draws++; i++ }
        }
        return draws
    }

    /** The last sector's flagship placed at the player's beacon, at [stage]. */
    private fun flagshipFixture(main: MainGame, container: GameContainer, stage: Int): Pair<InGameState, FlagshipBoss> {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        boss.stage = stage
        state.currentBeacon = boss.beacon!!
        return state to boss
    }

    /**
     * The flagship's arrival (native vb1: FTLGame-trace.exe, `.build/vn/native/vb1-arrive.trace.bin`): CreateLocation
     * creates BOSS_TEXT_1 at world level 0 (BossShip::GetEvent 0x407580, five draws) before CreateShip runs StartStage,
     * whose eleven humans draw two names, the animation timer and two colours each (AddCrewMemberFromString 0x4aea60).
     * The original's other construction draws between the two (ship systems, drones) are not modelled.
     */
    private fun bossEvents(main: MainGame, container: GameContainer): String {
        val (state, boss) = flagshipFixture(main, container, 1)
        if (state.enemy != null) state.browserEndHostility()
        val stages = ArrayList<Triple<String, String, Int>>()
        xyz.znix.xftl.sector.EventCreator.stageTrace = { name, stage, at -> stages += Triple(name, stage, at) }
        val trace = ArrayList<Int>()
        state.crewFactory.browserNameRetries = 0
        // Level 3 sensors, as in the native last-stand capture
        val sensors = state.player.sensors ?: error("The fixture ship has no sensors")
        sensors.energyLevels = 3
        NativeRandom.trace = trace
        try {
            check(state.browserSpawnBossHere()) { "Flagship did not spawn" }
        } finally {
            NativeRandom.trace = null
            xyz.znix.xftl.sector.EventCreator.stageTrace = null
        }
        val start = stages.firstOrNull { it.first == "BOSS_TEXT_1" && it.second == "start" }
        val end = stages.lastOrNull { it.first == "BOSS_TEXT_1" && it.second == "end" }
        check(start?.third == 0 && end?.third == 5) { "BOSS_TEXT_1 drew ${start?.third}..${end?.third}, not first and five" }
        val crew = state.enemy!!.crew.size
        // A name in use is drawn again (FUN_00605750), natively too; vb1 had no repeats.
        val retries = state.crewFactory.browserNameRetries
        check(crew == 11 && draws(trace) == 5 + crew * 5 + retries) { "Flagship arrival: ${draws(trace)} draws for $crew crew ($retries name repeats)" }
        check(state.shipUI.diagnosticDialogue?.hsOpenEvent == "BOSS_TEXT_1") { "The arrival box is not BOSS_TEXT_1" }
        // The native last-stand capture: with the arrival box open, level 3 sensors are limited to 2.
        check(sensors.scriptedPowerLimit == 2) { "Flagship arrival: sensors limit ${sensors.scriptedPowerLimit}, not 2" }
        return "flagship arrival (BOSS_TEXT_1 first, 5 draws per crew member, sensors limited to 2)"
    }

    /**
     * BossShip::GetSubEvent (0x4066d0), native vb1 / vbs / vb3b: a destroyed stage shows BOSS_ESCAPED at world level
     * 0 (23 scrap natively: the HIGH standard range at level 0 is 19..23 against 74..88 at level 7), an
     * escaping flagship BOSS_STALEMATE with its stage kept, a crew kill BOSS_AUTOMATED (5 draws; the ship stays
     * hostile), the last stage BOSS_DESTROYED (10 draws) and the victory.
     */
    private fun bossEnds(main: MainGame, container: GameContainer): String {
        run {
            val (state, boss) = flagshipFixture(main, container, 1)
            check(state.enemy != null || state.browserSpawnBossHere()) { "Flagship did not spawn" }
            state.shipUI.diagnosticDialogue?.selectOption(0)
            val flagship = state.enemy!!
            val (_, n) = counted { boss.bossShipKilled(flagship) }
            val scrap = state.shipUI.diagnosticDialogue?.diagnosticResources?.scrap
            check(boss.browserLastEvent == "BOSS_ESCAPED" && boss.stage == 2) { "Stage 1 ended with ${boss.browserLastEvent}, stage ${boss.stage}" }
            // Gates (4), scrap, the two FMD picks (redrawn while equal: 4 natively), their amounts, the item chance and the
            // boarder count: 11 draws or more.
            check(n >= 11 && scrap != null && scrap in 19..23) { "BOSS_ESCAPED drew $n with $scrap scrap (native 13, level 0: 19..23)" }
        }
        run {
            val (state, boss) = flagshipFixture(main, container, 1)
            check(state.enemy != null || state.browserSpawnBossHere()) { "Flagship did not spawn" }
            state.shipUI.diagnosticDialogue?.selectOption(0)
            val flagship = state.enemy!!
            flagship.escapeTimer = 30f
            boss.bossShipKilled(flagship)
            check(boss.browserLastEvent == "BOSS_STALEMATE" && boss.stage == 1 &&
                state.shipUI.diagnosticDialogue?.hsOpenEvent == "BOSS_STALEMATE") { "Escaping flagship: ${boss.browserLastEvent}, stage ${boss.stage}" }
        }
        run {
            val (state, boss) = flagshipFixture(main, container, 2)
            check(state.enemy != null || state.browserSpawnBossHere()) { "Flagship did not spawn" }
            state.shipUI.diagnosticDialogue?.selectOption(0)
            val flagship = state.enemy!!
            flagship.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().filter { it.ownerShip === flagship }.toList().forEach { it.removeFromShip() }
            val (_, n) = counted { state.checkCrewKillDefeat() }
            check(flagship.isAutomated && state.isEnemyHostile && boss.browserLastEvent == "BOSS_AUTOMATED" && n == 5 &&
                state.shipUI.diagnosticDialogue?.hsOpenEvent == "BOSS_AUTOMATED") { "Crew kill: ${boss.browserLastEvent}, $n draws" }
        }
        run {
            val (state, boss) = flagshipFixture(main, container, 3)
            check(state.enemy != null || state.browserSpawnBossHere()) { "Flagship did not spawn" }
            state.shipUI.diagnosticDialogue?.selectOption(0)
            val (_, n) = counted { boss.bossShipKilled(state.enemy!!) }
            check(boss.browserLastEvent == "BOSS_DESTROYED" && n == 10 && state.shipUI.gameOverWindow != null) {
                "Last stage: ${boss.browserLastEvent}, $n draws, ${state.shipUI.diagnosticWindow}"
            }
        }
        return "boss events (escaped, stalemate, automated, destroyed)"
    }

    /** ShipManager::OnLoop 0x4acefe (capture vn-lanb-run / vn-lanb-pirate): no ship, grey; a hostile crewed ship, usable. */
    private fun mindCanUse(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        state.player.purchasedReactorPower = 25
        val mind = state.player.mindControl!!
        mind.energyLevels = 3
        repeat(3) { mind.increasePower(); state.player.update(0f) }
        check(mind.powerSelected > 0) { "The mind control system has no power" }
        check(state.enemy == null && !mind.canUse && !mind.ready) { "Mind control usable without a target or intruders" }

        var seed = 71
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            check(seed < 150) { "No crewed fixture ship" }
        } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated)
        state.debugFlags.noEnemyFire.set = true
        check(mind.canUse && mind.ready) { "Mind control not usable against a hostile crewed ship" }
        return "mind control canUse"
    }

    /**
     * CommandGui +0x2f6c vs +0x2f6e: an event box keeps the backdrop at x1 (capture vn-slug), full-focus windows only
     * dim the scene by 0.15 (vn-win/vn-win2: store, upgrades, map, menu, options, restart), Space shades x0.4 and
     * Space plus a window x0.06.
     */
    private fun shade(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ui = state.shipUI
        val white = xyz.znix.xftl.rendering.Colour.white
        check(!state.isShadePaused) { "Shaded while running" }
        ui.showEventDialogue(state.eventManager["START_GAME"].resolve(), 1)
        check(state.isPaused && !state.isShadePaused && ui.sceneTint == white) {
            "An event box shades the backdrop (paused ${state.isPaused})"
        }
        ui.diagnosticCloseWindow()
        ui.showPauseWindow()
        check(state.isPaused && !state.isShadePaused && ui.sceneTint == xyz.znix.xftl.game.PlayerShipUI.FOCUS_TINT) {
            "The pause menu shades the backdrop or does not dim the scene (paused ${state.isPaused})"
        }
        ui.escapePressed()
        check(!ui.isWindowOpen) { "Escape left the pause menu open" }
        state.toggleQuickPause()
        check(state.isShadePaused) { "A Space pause does not shade the backdrop" }
        ui.showPauseWindow()
        check(state.isShadePaused && ui.sceneTint == xyz.znix.xftl.game.PlayerShipUI.FOCUS_TINT) {
            "A window over a Space pause lost the shade"
        }
        ui.escapePressed()
        state.toggleQuickPause()
        return "event box and focus window shade"
    }
}
