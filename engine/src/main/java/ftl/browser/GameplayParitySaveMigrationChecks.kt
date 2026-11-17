package ftl.browser

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.savegame.SaveMigrations
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MindControl
import xyz.znix.xftl.vanillasave.NativeImport
import xyz.znix.xftl.vanillasave.VanillaSaveWriter

/**
 * The save revision and the migration step of loading a run (savegame.SaveMigrations, docs/SAVES.md):
 * - every run the engine writes carries the current revision; a loaded run keeps it over a round trip, and a run of a
 *   newer revision loads as it is;
 * - a run without a revision is one of release 2.1.1 or earlier and gets the migrations: migration 1 frees a crew
 *   member that is mind controlled while no mind control system holds it (report HS105), aboard or waiting in the
 *   clone bay, and leaves one that a system holds;
 * - the same state in a run of the current revision stays as it is;
 * - a migrated run written again carries the current revision and is not migrated again;
 * - a run imported from the original game does not pass the migration step.
 */
internal object GameplayParitySaveMigrationChecks {
    private const val DT = 0.05f
    private var stage = ""

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            stage = ""
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name" + (if (stage.isEmpty()) "" else " ($stage)") + ": " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("revisionRoundTrip") { revisionRoundTrip(main, container) }
            case("stuckCrewFreed") { stuckCrewFreed(main, container) }
            case("stuckBodyFreed") { stuckBodyFreed(main, container) }
            case("idleSystemDoesNotHold") { idleSystemDoesNotHold(main, container) }
            case("heldCrewStays") { heldCrewStays(main, container) }
            case("currentRevisionKeeps") { currentRevisionKeeps(main, container) }
            case("importedOriginal") { BrowserGameplayParityDiagnostics.vanillaOnly("imported original run") { importedOriginal(main, container) } }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "save migrations ok: " + results.joinToString(", ")
    }

    /** The run as release 2.1.1 wrote it: the same document without a revision. */
    private fun asRelease211(state: InGameState): Document =
        state.saveGameState().also { it.rootElement.removeAttribute(SaveMigrations.ATTRIBUTE) }

    private fun load(main: MainGame, doc: Document): InGameState {
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(doc)))
        return main.currentState as InGameState
    }

    private fun revision(doc: Document): String? = doc.rootElement.getAttributeValue(SaveMigrations.ATTRIBUTE)

    private fun runUntil(state: InGameState, seconds: Float, done: () -> Boolean): Boolean {
        var steps = (seconds / DT).toInt()
        while (!done() && steps-- > 0) state.updateGameState(DT)
        return done()
    }

    /** The ship's mind control system, added in the layout's own room when it has none. */
    private fun mindOf(ship: Ship): MindControl? {
        ship.mindControl?.let { return it }
        if (HS.active) {
            xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, 14)
        } else {
            val slot = ship.systemSlots.firstOrNull { it.system.name == "mind" && it.room.system == null } ?: return null
            slot.room.setSystem(slot)
            slot.room.system?.let { ship.browserPowerNewSystem(it) }
        }
        return ship.mindControl
    }

    /** A fight in which the enemy's mind control system holds a crew member of the player's; null without such a pirate. */
    private fun enemyHoldsOne(main: MainGame, container: GameContainer): Triple<InGameState, MindControl, LivingCrew>? {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var found: MindControl? = null
        var seed = 71
        while (found == null && seed < 131) {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, seed++)
            val candidate = state.enemy!!
            if (candidate.isAutomated || candidate.isAutoScout || candidate.crew.isEmpty()) continue
            found = mindOf(candidate)
        }
        val mind = found ?: return null
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        val enemy = state.enemy!!
        enemy.purchasedReactorPower = 25
        enemy.updateAvailablePower()
        if (mind.powerSelected == 0) mind.increasePower()
        enemy.update(0f)
        state.updateGameState(DT)
        if (mind.controlledCrew == null) {
            mind.selectCrew(state.player.crew.filterIsInstance<LivingCrew>().first { !it.isMindControlResistant })
            mind.update(0f)
        }
        val crew = mind.controlledCrew ?: error("Set-up: the enemy's mind system took nobody (power ${mind.powerSelected}, ready ${mind.ready})")
        check(crew.ownerShip === state.player && crew.isMindControlled && mind.holds(crew)) {
            "Set-up: the enemy's mind system does not hold a crew member of the player's"
        }
        return Triple(state, mind, crew)
    }

    /** Every run written carries the current revision, keeps it over a load, and a newer one loads as it is. */
    private fun revisionRoundTrip(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        check(state.loadedSaveRevision == SaveMigrations.NOT_LOADED && state.saveMigrationLines.isEmpty()) {
            "A new run claims to be loaded from revision ${state.loadedSaveRevision}"
        }
        val current = SaveMigrations.CURRENT.toString()
        stage = "writing"
        val written = state.saveGameState()
        check(revision(written) == current) { "A written run carries revision ${revision(written)}, not $current" }
        stage = "loading the current revision"
        val loaded = load(main, written)
        check(loaded.loadedSaveRevision == SaveMigrations.CURRENT) { "The loaded run reads revision ${loaded.loadedSaveRevision}" }
        check(loaded.saveMigrationLines.isEmpty()) { "A run of the current revision was migrated: ${loaded.saveMigrationLines}" }
        check(revision(loaded.saveGameState()) == current) { "The run written after a load carries ${revision(loaded.saveGameState())}" }

        // A newer build's run: loaded as any run, no migration; written by this build it is this build's again.
        stage = "loading a newer revision"
        val newer = loaded.saveGameState().also { it.rootElement.setAttribute(SaveMigrations.ATTRIBUTE, (SaveMigrations.CURRENT + 5).toString()) }
        val fromNewer = load(main, newer)
        check(fromNewer.loadedSaveRevision == SaveMigrations.CURRENT + 5 && fromNewer.saveMigrationLines.isEmpty()) {
            "A run of a newer revision: read as ${fromNewer.loadedSaveRevision}, migrations ${fromNewer.saveMigrationLines}"
        }
        check(revision(fromNewer.saveGameState()) == current) { "A newer run written by this build carries ${revision(fromNewer.saveGameState())}" }
        return "a run carries revision $current over a round trip"
    }

    /**
     * Report HS105 as a 2.1.1 run holds it: a crew member of the player's with the mind control mark, nobody
     * holding it, after the fight. Loading frees it; the run written afterwards is of the current revision and its
     * load migrates nothing.
     */
    private fun stuckCrewFreed(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        repeat(3) { state.updateGameState(DT) }
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
        val index = state.player.crew.indexOf(crew)
        MindControl.setMindControl(crew, true)
        check(crew.isMindControlled && crew.mode == AbstractCrew.SlotType.INTRUDER && !crew.playerControllable) {
            "Set-up: the crew member is not under control"
        }
        check(state.player.mindControl?.holds(crew) != true) { "Set-up: a system holds the crew member" }
        // It does what a boarder does for three seconds (fights its crewmates or breaks the system of its room), so
        // the run holds that too.
        repeat(60) { state.updateGameState(DT) }
        check(crew.isMindControlled && crew.health > 0f) { "Set-up: the crew member did not stay under control" }
        val doing = crew.currentAction
        val others = state.player.crew.count { it is LivingCrew && !it.isMindControlled }

        stage = "loading the 2.1.1 run"
        val old = asRelease211(state)
        check(revision(old) == null) { "Set-up: the 2.1.1 run has a revision" }
        var loaded = load(main, old)
        check(loaded.loadedSaveRevision == 0) { "A run without a revision reads as revision ${loaded.loadedSaveRevision}" }
        var freed = loaded.player.crew[index] as LivingCrew
        check(!freed.isMindControlled) { "The crew member of a 2.1.1 run is still mind controlled after loading" }
        check(loaded.saveMigrationLines.size == SaveMigrations.CURRENT && loaded.saveMigrationLines[0].contains("1 aboard, 0 waiting")) {
            "The migrations of a 2.1.1 run: ${loaded.saveMigrationLines}"
        }
        check(freed.mode == AbstractCrew.SlotType.CREW && freed.playerControllable) { "The freed crew member is on side ${freed.mode}" }
        check(freed.mindControlHealthBoost == 0f && freed.mindControlDamageBoost == 1f && freed.health <= freed.maxHealth) {
            "The freed crew member keeps a boost (${freed.mindControlHealthBoost}, ${freed.mindControlDamageBoost})"
        }
        check(loaded.player.intruders.isEmpty()) { "The freed crew member counts as a boarder on its own ship" }
        check(loaded.player.crew.count { it is LivingCrew && !it.isMindControlled } == others + 1) { "The migration changed another crew member" }
        repeat(40) { loaded.updateGameState(DT) }
        check(!freed.isMindControlled && freed.mode == AbstractCrew.SlotType.CREW &&
            freed.currentAction != AbstractCrew.Action.FIGHTING && freed.currentAction != AbstractCrew.Action.SABOTAGE && freed.sabotagingSystem == null) {
            "Two seconds on, the freed crew member is ${freed.mode}, ${freed.currentAction}"
        }
        check(loaded.player.crew.none { it.currentAction == AbstractCrew.Action.FIGHTING }) { "Two seconds on, its crewmates still fight the freed crew member" }

        // Written again it is a run of the current revision, and loading that migrates nothing.
        stage = "writing the migrated run"
        val again = loaded.saveGameState()
        check(revision(again) == SaveMigrations.CURRENT.toString()) { "The migrated run is written with revision ${revision(again)}" }
        loaded = load(main, again)
        freed = loaded.player.crew[index] as LivingCrew
        check(loaded.loadedSaveRevision == SaveMigrations.CURRENT && loaded.saveMigrationLines.isEmpty()) {
            "The migrated run was migrated again: ${loaded.saveMigrationLines}"
        }
        check(!freed.isMindControlled) { "The migrated run holds the mark again" }

        // The same migration twice changes nothing more.
        stage = "the migration twice"
        val twice = SaveMigrations.freeUnheldMindControl(listOfNotNull(loaded.player, loaded.enemy))
        check(twice.startsWith("no crew member")) { "The migration on a repaired run: $twice" }
        return "a 2.1.1 run's stuck crew member is freed (saved while $doing)"
    }

    /** The same mark on a body waiting in the clone bay, as a 2.1.1 run saved it between the death and the clone. */
    private fun stuckBodyFreed(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS_3")
        val bay = state.player.clonebay ?: return "stuck body: no clone bay under these blueprints"
        // In a fight: out of combat Multiverse's instant clone would finish the clone before the save.
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        repeat(3) { state.updateGameState(DT) }
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
        crew.health = 0f
        check(runUntil(state, 5f) { bay.queue.contains(crew) }) { "Set-up: the dead crew member never reached the clone bay" }
        crew.hsMindControlled = true
        stage = "loading the 2.1.1 run"
        val loaded = load(main, asRelease211(state))
        val body = loaded.player.clonebay!!.queue.singleOrNull() ?: error("Set-up: the loaded clone bay holds ${loaded.player.clonebay!!.queue.size} bodies")
        check(!body.isMindControlled) { "The body in the clone bay of a 2.1.1 run keeps the mark" }
        check(loaded.saveMigrationLines.firstOrNull()?.contains("0 aboard, 1 waiting") == true) { "The migrations of the run: ${loaded.saveMigrationLines}" }
        stage = "cloning"
        check(runUntil(loaded, 90f) { loaded.player.crew.contains(body) }) { "The crew member was never cloned" }
        repeat(20) { loaded.updateGameState(DT) }
        check(!body.isMindControlled && body.mode == AbstractCrew.SlotType.CREW && body.playerControllable) { "The clone is under control (${body.mode})" }
        return "a body in its clone bay too"
    }

    /**
     * The stuck crew member while the enemy that took it is still there, its mind control system between two uses:
     * loading lists every marked crew member of the other side with that system (MindSystem::Load), but a system
     * whose control period is not running holds nobody, so the crew member is freed and the system forgets it.
     */
    private fun idleSystemDoesNotHold(main: MainGame, container: GameContainer): String {
        val (state, mind, crew) = enemyHoldsOne(main, container) ?: return "idle mind system: no fixture pirate has room for a mind system"
        val index = state.player.crew.indexOf(crew)
        // The system lets go the normal way and waits out its lock, powered; the crew member keeps the mark it would
        // have kept in 2.1.1 had it died and been cloned meanwhile.
        mind.stopMindControl()
        state.updateGameState(DT)
        check(!crew.isMindControlled && mind.powerSupplied > 0) { "Set-up: the system did not release (power ${mind.powerSupplied})" }
        MindControl.setMindControl(crew, true)
        check(crew.isMindControlled && !mind.holds(crew)) { "Set-up: the idle system holds the crew member" }
        stage = "loading the 2.1.1 run"
        val loaded = load(main, asRelease211(state))
        val freed = loaded.player.crew[index] as LivingCrew
        check(!freed.isMindControlled) { "With the enemy's idle mind system aboard, the 2.1.1 run's crew member stays mind controlled" }
        repeat(5) { loaded.updateGameState(DT) }
        val loadedMind = loaded.enemy!!.mindControl!!
        check(!freed.isMindControlled && loadedMind.controlledCrew !== freed && !loadedMind.hsControlled.contains(freed)) {
            "The enemy's mind system lists the freed crew member"
        }
        return "also beside the enemy's idle mind system"
    }

    /** A crew member that a mind control system holds is left to that system, in a 2.1.1 run as in any. */
    private fun heldCrewStays(main: MainGame, container: GameContainer): String {
        val (state, _, crew) = enemyHoldsOne(main, container) ?: return "held crew: no fixture pirate has room for a mind system"
        val index = state.player.crew.indexOf(crew)
        stage = "loading the 2.1.1 run"
        val loaded = load(main, asRelease211(state))
        val held = loaded.player.crew[index] as LivingCrew
        check(held.isMindControlled) { "A crew member that the enemy's mind system holds was freed by the migration" }
        check(loaded.saveMigrationLines.firstOrNull()?.contains("no crew member") == true) { "The migrations of the run: ${loaded.saveMigrationLines}" }
        check(loaded.enemy!!.mindControl!!.holds(held)) { "The loaded mind system does not hold its crew member" }
        // The system lets go as always: when it loses its power.
        stage = "the normal release"
        val mind = loaded.enemy!!.mindControl!!
        mind.damagedEnergyLevels = mind.energyLevels
        check(runUntil(loaded, 5f) { !held.isMindControlled }) { "The system without power still holds the crew member" }
        return "a held crew member is left to its system"
    }

    /** The stuck state in a run of the current revision is not ours to repair: a mod's script may have set it. */
    private fun currentRevisionKeeps(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        repeat(3) { state.updateGameState(DT) }
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
        val index = state.player.crew.indexOf(crew)
        MindControl.setMindControl(crew, true)
        val doc = state.saveGameState()
        check(revision(doc) == SaveMigrations.CURRENT.toString()) { "Set-up: the run carries revision ${revision(doc)}" }
        val loaded = load(main, doc)
        val same = loaded.player.crew[index] as LivingCrew
        check(loaded.saveMigrationLines.isEmpty()) { "A run of the current revision was migrated: ${loaded.saveMigrationLines}" }
        check(same.isMindControlled && same.mode == AbstractCrew.SlotType.INTRUDER) {
            "A run of the current revision lost a mind control mark that no system holds"
        }
        return "the same mark in a current run stays"
    }

    /**
     * A run from the original game with a mind-controlled crew member: the original file in its wrapper (savecore
     * NativeSave) is no saved run of ours, it is converted by the importer and never passes the migration step.
     * The run the engine then writes is of the current revision.
     */
    private fun importedOriginal(main: MainGame, container: GameContainer): String {
        val (state, _, _) = enemyHoldsOne(main, container) ?: return "imported original run: no fixture pirate has room for a mind system"
        stage = "writing the original's file"
        val bytes = VanillaSaveWriter.writeToByteArray(state)
        val wrapper = Element("xftlSaveGame").setAttribute(NativeImport.ELEMENT, "1").setAttribute("prefix", "")
        wrapper.addContent(Element(NativeImport.ELEMENT).setAttribute("kind", "run").setAttribute("file", "continue.sav")
            .setAttribute("bytes", bytes.size.toString()).setText(java.util.Base64.getEncoder().encodeToString(bytes)))
        val file = NativeImport.unwrap(BrowserXml.parse(BrowserXml.write(Document(wrapper)))) ?: error("The wrapped original file reads as a run of ours")
        stage = "importing"
        val imported = InGameState(main, main.gameContent, NativeImport.parseRun(file, main.gameContent), true)
        check(imported.loadedSaveRevision == SaveMigrations.NOT_LOADED && imported.saveMigrationLines.isEmpty()) {
            "The migration step ran on a run imported from the original game (revision ${imported.loadedSaveRevision}, ${imported.saveMigrationLines})"
        }
        val controlled = imported.player.crew.count { (it as? LivingCrew)?.isMindControlled == true }
        check(revision(imported.saveGameState()) == SaveMigrations.CURRENT.toString()) { "The imported run is written with revision ${revision(imported.saveGameState())}" }
        return "an imported original run is not migrated ($controlled mind controlled after the import, as the importer leaves it)"
    }
}
