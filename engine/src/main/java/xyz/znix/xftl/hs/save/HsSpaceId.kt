// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/SpaceId.cpp (ScoreKeeper::SaveGame / LoadGame hooks:
// the next space id is saved with the run and read back first, so ids drawn after a load never collide with saved ones).
package xyz.znix.xftl.hs.save

import org.jdom2.Element
import xyz.znix.xftl.hs.crew.HsCrew

/**
 * Globals::GetNextSpaceId's counter in run saves. HS appends it to ScoreKeeper::SaveGame (the Linux build writes
 * `*Globals_GetNextSpaceId_id`) and reads it in ScoreKeeper::LoadGame, "one of the first things loaded", so every object the
 * load constructs afterwards (crew, drones, projectiles, the hacking drone) draws from the saved counter.
 */
object HsSpaceId {
    const val KEY = "spaceId"

    /** Registers the run block; called with the other run keys when the content loads. */
    @JvmStatic
    fun registerSave() {
        HsSave.register(KEY, HsSave.Scope.RUN, { it.setAttribute("next", HsCrew.spaceIdState().toString()) }, { /* read early */ })
    }

    /** Before the run's objects load (InGameState's save constructor): set the counter from the save, if it has one. */
    @JvmStatic
    fun restoreEarly(root: Element) {
        val data = root.getChild(HsSave.ELEMENT)?.getChildren("data")?.firstOrNull { it.getAttributeValue("key") == KEY } ?: return
        data.getAttributeValue("next")?.toIntOrNull()?.let { HsCrew.resetIds(it) }
    }
}
