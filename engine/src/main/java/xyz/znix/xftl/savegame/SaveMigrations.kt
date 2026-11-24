package xyz.znix.xftl.savegame

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.systems.MindControl

/**
 * The save revision of a run and the repairs of older runs (docs/SAVES.md, "Save revision and migrations").
 *
 * Every run the engine writes carries [ATTRIBUTE] = [CURRENT] on its root element (InGameState.saveGameState). A run
 * without it was written by release 2.1.1 or an earlier one and counts as revision 0. When a run is loaded
 * (InGameState's constructor for a saved run), the migrations above its revision run in order, on the loaded game,
 * before the ships get their first update. Loading rewrites nothing: the run carries [CURRENT] once the engine saves
 * it again. A run with a higher revision than [CURRENT] (written by a newer build) loads as any run does, without
 * migrations; when this build saves it, it carries this build's revision again, so the newer build repeats its
 * migrations on what this build may have left behind.
 *
 * A run imported from the original game is not a saved run of ours: it arrives as the original file wrapped in
 * `<xftlSaveGame nativeImport="1">` (vanillasave.NativeImport) and is converted by MainGame.startImportedRun, which
 * never comes here.
 *
 * To add a migration: write one function below that repairs one named defect and returns one line saying what it
 * changed, add it to [ALL] with the next number, and raise [CURRENT] to that number. A migration changes only what
 * it names and may run twice on the same run without harm.
 */
object SaveMigrations {
    /** The revision this build writes: the number of the last migration in [ALL]. */
    const val CURRENT = 2

    /** The root attribute of a run (`xftlSaveGame`) that holds the revision. */
    const val ATTRIBUTE = "saveRevision"

    /** [InGameState.loadedSaveRevision] of a run that was not loaded from a save of ours (a new run, an import). */
    const val NOT_LOADED = -1

    /** One repair: it takes a run from revision [revision] - 1 to [revision]. */
    class Migration(@JvmField val revision: Int, @JvmField val name: String, @JvmField val run: (InGameState) -> String)

    @JvmField
    val ALL: List<Migration> = listOf(
        Migration(1, "freeUnheldMindControl") { game -> freeUnheldMindControl(listOfNotNull(game.player, game.enemy)) },
        Migration(2, "freeMindControlOfShipsGone") { game -> freeUnheldMindControl(listOfNotNull(game.player, game.enemy)) },
    )

    /** The revision a run was written with: 0 for a run without the attribute (2.1.1 and earlier) or one that does not read. */
    @JvmStatic
    fun revisionOf(root: Element): Int = root.getAttributeValue(ATTRIBUTE)?.toIntOrNull()?.takeIf { it >= 0 } ?: 0

    /** Marks a run that is being written as this build's. */
    @JvmStatic
    fun stamp(root: Element) {
        root.setAttribute(ATTRIBUTE, CURRENT.toString())
    }

    /** The migrations a run of revision [saved] still needs, in order; none for the current or a newer revision. */
    @JvmStatic
    fun pending(saved: Int): List<Migration> = ALL.filter { it.revision > saved }

    /**
     * Runs the migrations a loaded run of revision [saved] needs. Each writes one line to the debug log (category
     * `persistence`, event `save.migration`); the lines are returned for InGameState.saveMigrationLines.
     */
    @JvmStatic
    fun migrate(game: InGameState, saved: Int): List<String> {
        if (saved > CURRENT) {
            log(saved, "none", "the run is of revision $saved, newer than this build's $CURRENT: loaded as it is")
            return emptyList()
        }
        return pending(saved).map { migration ->
            val line = migration.revision.toString() + " " + migration.name + ": " + migration.run(game)
            log(saved, migration.name, line)
            line
        }
    }

    private fun log(saved: Int, name: String, line: String) {
        ftl.browser.BrowserDebug.record(
            "persistence", "save.migration",
            "{\"from\":$saved,\"migration\":" + ftl.browser.BrowserDebug.quote(name) + ",\"result\":" + ftl.browser.BrowserDebug.quote(line) + "}"
        )
    }

    /**
     * Migration 1, repairs release 2.1.1 (report HS105). Under Hyperspace a crew member that died mind controlled kept
     * its flag: the clone bay brought it back on the enemy's side, no mind control system listed it, and the run was
     * saved like that. Fixed for new deaths in LivingCrew.onFinishedDying; this frees the crew members such a run
     * still holds.
     *
     * Migration 2 runs the same repair on runs of release 2.2.3 (revision 1). There, under Hyperspace, a crew member of
     * the player's that the enemy's mind control system held kept the mark when that ship was destroyed or the player's
     * ship left it behind: the system's own loop, which would let go, stopped with the ship. Fixed for new runs in
     * MindControl.shipDestroyed (MindSystem::ShipDestroyed, from ShipManager::OnLoop and WorldManager::ClearLocation).
     *
     * A crew member of [ships] (the player's ship and the ship at the beacon) that is marked as mind controlled while
     * no mind control system of those ships holds it ([MindControl.holds]) is released the way a system releases it
     * ([MindControl.setMindControl]); its side, its boosts and its picture follow in its own next update, as after
     * any release. A body waiting in a clone bay loses the mark the way a death clears it. A crew member that a
     * system does hold, or one still dying, is left alone.
     */
    @JvmStatic
    fun freeUnheldMindControl(ships: List<Ship>): String {
        val systems = ships.mapNotNull { it.mindControl }
        var aboard = 0
        var bodies = 0
        for (ship in ships) {
            for (crew in ship.crew.toList()) {
                if (crew !is LivingCrew || !crew.isMindControlled || systems.any { it.holds(crew) }) continue
                MindControl.setMindControl(crew, false)
                if (!crew.isMindControlled) aboard++
            }
            for (body in ship.clonebay?.queue ?: emptyList()) {
                if (!body.isMindControlled) continue
                body.clearMindControlOfTheDead()
                bodies++
            }
        }
        return if (aboard + bodies == 0) "no crew member was mind controlled without a system holding it"
        else "freed from mind control that no system held: $aboard aboard, $bodies waiting in a clone bay"
    }
}
