package xyz.znix.xftl.game

/** Official ship/layout rules, verified against the pinned desktop executable. */
object ShipProgression {
    const val KESTREL = "PLAYER_SHIP_HARD"
    const val ENGI = "PLAYER_SHIP_CIRCLE"
    const val LANIUS = "PLAYER_SHIP_ANAEROBIC"
    const val CRYSTAL = "PLAYER_SHIP_CRYSTAL"

    private val victoryChain = mapOf(
        ENGI to "PLAYER_SHIP_FED", "PLAYER_SHIP_FED" to "PLAYER_SHIP_ENERGY",
        "PLAYER_SHIP_ENERGY" to "PLAYER_SHIP_MANTIS", "PLAYER_SHIP_MANTIS" to "PLAYER_SHIP_JELLY",
        "PLAYER_SHIP_JELLY" to "PLAYER_SHIP_ROCK", "PLAYER_SHIP_ROCK" to "PLAYER_SHIP_STEALTH"
    )

    fun existsInMode(ship: String, advanced: Boolean, families: ShipFamily.FamilyTable): Boolean {
        val family = families.byShipId[ship] ?: return false
        return advanced || (family.ships.first() != LANIUS && family.ships.indexOf(ship) < 2)
    }

    fun reconcile(profile: SaveProfile, families: ShipFamily.FamilyTable, achievements: Achievement.AchievementTable) {
        for (family in families.families) {
            val earned = family.achievements.mapNotNull { achievements.achievements[it]?.let(profile::getAchievement) }
            if (earned.size >= 2) profile.unlockLayout(family.ships[1], earned.sortedByDescending { it.difficulty.ordinal }[1].difficulty)
        }
        if (families.families.count { profile.isShipUnlocked(it.ships.first()) } >= 5)
            profile.unlockLayout(LANIUS, Difficulty.EASY)
        if (families.families.all { profile.isShipUnlocked(it.ships.first()) })
            profile.unlockAchievement(achievements["ACH_UNLOCK_ALL"], Difficulty.EASY)
    }

    fun visit(profile: SaveProfile, families: ShipFamily.FamilyTable, ship: String, sector: Int, advanced: Boolean, difficulty: Difficulty) {
        val family = families.byShipId[ship] ?: return
        if (sector >= 4 && family.ships.first() == KESTREL) profile.unlockLayout(ENGI, difficulty)
        if (sector >= 7 && advanced && family.ships.indexOf(ship) == 1)
            family.ships.getOrNull(2)?.let { profile.unlockLayout(it, difficulty) }
    }

    fun victory(profile: SaveProfile, families: ShipFamily.FamilyTable, ship: String, difficulty: Difficulty) {
        val family = families.byShipId[ship] ?: return
        profile.recordLayoutVictory(ship, difficulty)
        victoryChain[family.ships.first()]?.let { profile.unlockLayout(it, difficulty) }
        val crystalFamilies = families.families.filter { it.ships.first() !in listOf(CRYSTAL, LANIUS) }
        if (crystalFamilies.all { f -> f.ships.take(2).all { profile.getLayoutVictory(it) != null } })
            profile.unlockLayout(CRYSTAL, difficulty)
    }
}
