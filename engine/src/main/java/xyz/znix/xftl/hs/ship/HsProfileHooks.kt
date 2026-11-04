package xyz.znix.xftl.hs.ship

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.SaveProfile

/** SaveProfile call sites of the Hyperspace unlocks (only reached while HS is active). */
object HsProfileHooks {
    private fun bind(profile: SaveProfile) {
        HsShipUnlocks.saveProfile = { profile.markDirty() }
        // CheckVanillaUnlocks (ShipUnlocks.cpp): ScoreKeeper::UnlockShip(shipId, variant) - the named layout, not the A one.
        HsShipUnlocks.vanillaUnlock = { ship, variant -> profile.unlockLayout(HsShips.variantName(ship, variant), Difficulty.EASY); true }
        HsShipUnlocks.victoryPopup = { type, ship ->
            flush(profile)
            profile.addHsNotification(SaveProfile.ProgressNotification(hsVictoryShip = ship, hsVictoryType = type))
        }
    }

    /** ScoreKeeper::GetShipUnlocked with ShipUnlocks.cpp's hook: custom layouts by their unlocks, vanilla ships as vanilla or HS-unlocked. */
    @JvmStatic
    fun isShipUnlocked(profile: SaveProfile, ship: String): Boolean {
        if (HsHangar.isCustomLayout(ship)) return HsHangar.isCustomLayoutUnlocked(ship)
        return HsHangar.vanillaUnlocked(ship, profile.isVanillaShipUnlocked(ship))
    }

    @JvmStatic
    fun sector(profile: SaveProfile, ship: String, sector: Int, difficulty: Difficulty) {
        bind(profile)
        HsShipUnlocks.checkSectorUnlocks(ship, sector)
        flush(profile)
    }

    @JvmStatic
    fun victory(profile: SaveProfile, ship: String, difficulty: Difficulty) {
        bind(profile)
        HsShipUnlocks.victory(ship, difficulty.ordinal)
        flush(profile)
    }

    @JvmStatic
    fun victoryScreen(profile: SaveProfile, ship: String, difficulty: Difficulty) {
        bind(profile)
        HsShipUnlocks.anyVictory(ship)
        flush(profile)
    }

    /** Event `<unlockCustomShip silent>` (events workstream): UnlockShip(ship, silent, true, true). */
    @JvmStatic
    fun eventUnlock(profile: SaveProfile, ship: String, silent: Boolean, difficulty: Difficulty) {
        bind(profile)
        HsShipUnlocks.unlockShip(ship, silent, true, true, difficulty.ordinal)
        flush(profile)
    }

    /** Lua CustomShipUnlocks:UnlockShip: the popups of what it unlocked. */
    @JvmStatic
    fun flushLua() {
        val profile = xyz.znix.xftl.hs.lua.HsLua.game?.profile ?: return
        bind(profile)
        flush(profile)
    }

    /** Popups for newly unlocked layouts go to the profile's notification queue (the hangar/achievement UI). */
    private fun flush(profile: SaveProfile) {
        for ((ship, quest) in HsShipUnlocks.recentlyUnlocked)
            profile.addHsNotification(if (quest) SaveProfile.ProgressNotification(questShip = ship) else SaveProfile.ProgressNotification(ship = ship))
        HsShipUnlocks.recentlyUnlocked.clear()
        profile.markDirty()
    }
}
