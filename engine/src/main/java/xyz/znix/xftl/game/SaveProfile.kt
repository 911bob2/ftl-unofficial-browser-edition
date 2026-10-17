package xyz.znix.xftl.game

import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.JDOMException
import ftl.browser.BrowserSaxBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Represents the user's profile: achievements/ships/high scores/settings/etc.
 *
 * Unlike the game state saves, this is supposed to be durable, as it'd be
 * much, much worse to lose it than a savegame.
 */
class SaveProfile private constructor() {
    /**
     * Store the unlocked achievements by ID, rather than using [Achievement]
     * objects, so that profiles don't get broken if you add or remove a mod.
     */
    private val unlockedAchievements = HashMap<String, AchievementUnlockInfo>()

    /**
     * Stores the ships (indexed by their unlockShip id) which the player
     * has completed the unlock quest for.
     *
     * This re-uses the AchievementUnlockInfo class, since we need to track
     * the difficulty the quests were completed on to show in the UI.
     */
    private val unlockedShips = HashMap<String, AchievementUnlockInfo>()
    private val unlockedLayouts = HashMap<String, AchievementUnlockInfo>()
    private val layoutVictories = HashMap<String, AchievementUnlockInfo>()
    private var progressionFamilies: ShipFamily.FamilyTable? = null
    private var progressionAchievements: Achievement.AchievementTable? = null
    /**
     * A popup to show: an achievement, a ship or layout unlock, or ([questShip]) the quest achievement that
     * follows an event's ship unlock. [silent] skips the popup's own sound when the unlock already played one.
     */
    data class ProgressNotification(
        val achievement: String? = null, val ship: String? = null, val questShip: String? = null,
        val silent: Boolean = false
    )
    private val pendingNotifications = ArrayList<ProgressNotification>()
    private var suppressNotifications = false
    val pendingNotificationCount get() = pendingNotifications.size
    fun pollNotification(): ProgressNotification? = if (pendingNotifications.isEmpty()) null else pendingNotifications.removeAt(0)
    fun clearNotifications() { pendingNotifications.clear() }

    fun configureProgression(families: ShipFamily.FamilyTable, achievements: Achievement.AchievementTable) {
        progressionFamilies = families
        progressionAchievements = achievements
        reconcileProgression()
    }

    private fun reconcileProgression() {
        val families = progressionFamilies ?: return
        val achievements = progressionAchievements ?: return
        ShipProgression.reconcile(this, families, achievements)
    }

    fun isShipUnlocked(ship: String): Boolean {
        if (ship == ShipProgression.KESTREL || unlockedLayouts.containsKey(ship)) return true
        val family = progressionFamilies?.byShipId?.get(ship) ?: return false
        return ship == family.ships.first() && family.unlockId?.let { unlockedShips.containsKey(it) } == true
    }

    fun unlockLayout(ship: String, difficulty: Difficulty) {
        if ((unlockedLayouts[ship]?.difficulty?.ordinal ?: -1) >= difficulty.ordinal) return
        val firstUnlock = !unlockedLayouts.containsKey(ship)
        unlockedLayouts[ship] = AchievementUnlockInfo(difficulty)
        dirty = true
        if (firstUnlock && achievementPopups && !suppressNotifications) pendingNotifications.add(ProgressNotification(ship = ship))
        ftl.browser.BrowserDebug.record("progression", "layout.unlock", "{\"ship\":${ftl.browser.BrowserDebug.quote(ship)},\"difficulty\":${ftl.browser.BrowserDebug.quote(difficulty.name)}}")
    }

    fun recordLayoutVictory(ship: String, difficulty: Difficulty) {
        if ((layoutVictories[ship]?.difficulty?.ordinal ?: -1) >= difficulty.ordinal) return
        layoutVictories[ship] = AchievementUnlockInfo(difficulty)
        dirty = true
    }

    fun getLayoutVictory(ship: String): AchievementUnlockInfo? = layoutVictories[ship]
    fun deleteLayoutUnlock(ship: String) { unlockedLayouts.remove(ship); dirty = true }

    fun visitSector(ship: String, sector: Int, advanced: Boolean, difficulty: Difficulty) {
        progressionFamilies?.let { ShipProgression.visit(this, it, ship, sector, advanced, difficulty) }
        reconcileProgression()
    }

    fun recordShipVictory(ship: String, difficulty: Difficulty) {
        progressionFamilies?.let { ShipProgression.victory(this, it, ship, difficulty) }
        reconcileProgression()
    }

    /**
     * Stores all the user-modified hotkeys. This maps from the hotkey ID
     * to that of the key. It's stored here as a string mapping, as mods
     * will be able to add their own keybinds and we don't want to delete
     * those if the user loads up without said mod.
     *
     * Keys that are mapped to their default values aren't stored here,
     * which means that adding a mod won't immediately fill your profile
     * with all it's keybinds unless you modify them.
     *
     * Mapping to a null string means the key is unbound.
     */
    private val keybinds = HashMap<String, String?>()

    var browserGamesPlayed = 0
        private set
    var browserVictories = 0
        private set
    var browserLifetimeScrap = 0L
        private set
    var browserLifetimeShips = 0L
        private set
    var browserLifetimeBeacons = 0L
        private set
    var browserLifetimeCrew = 0L
        private set
    var browserMostScrap = 0
        private set
    var browserMostShips = 0
        private set
    var browserMostBeacons = 0
        private set
    var browserMostCrew = 0
        private set
    var browserLastRun: ftl.browser.BrowserRunRecord? = null
        private set
    private val browserScores = ArrayList<ftl.browser.BrowserRunRecord>()
    private val browserCrewRecords = HashMap<ftl.browser.CrewRecordKind, ftl.browser.BrowserCrewRecord>()
    fun crewRecord(kind: ftl.browser.CrewRecordKind): ftl.browser.BrowserCrewRecord? = browserCrewRecords[kind]
    // ScoreKeeper crew record flags (+0xf0 + 0x54 * kind): set by UpdateCrewStat (0x00445130) when a record is
    // beaten, cleared by Reset (0x00445e80) at a new run. Not saved.
    private val browserNewCrewRecords = HashSet<ftl.browser.CrewRecordKind>()
    fun isNewCrewRecord(kind: ftl.browser.CrewRecordKind) = kind in browserNewCrewRecords
    fun updateCrewRecords(crew: xyz.znix.xftl.crew.LivingCrewInfo) {
        if (!crew.browserCounters.complete) return
        for (kind in ftl.browser.CrewRecordKind.entries) {
            val record = ftl.browser.BrowserCrewRecord.from(kind, crew)
            // Native replaces only on a strict improvement; ties retain identity.
            if (record.value <= (browserCrewRecords[kind]?.value ?: 0)) continue
            browserCrewRecords[kind] = record
            browserNewCrewRecords += kind
            dirty = true
            ftl.browser.BrowserDebug.record("statistics", "crew.record", "{\"kind\":${ftl.browser.BrowserDebug.quote(kind.name)},\"value\":${record.value}}")
        }
    }

    fun browserHighScores(ship: String? = null): List<ftl.browser.BrowserRunRecord> =
        browserScores.filter { ship == null || it.ship == ship }.sortedByDescending { it.score }.take(if (ship == null) 5 else 4)

    fun collectBrowserScrap(amount: Int, difficulty: Difficulty) {
        if (amount <= 0) return
        browserLifetimeScrap += amount
        dirty = true
        if (browserLifetimeScrap >= 10000) progressionAchievements?.let { unlockAchievement(it["ACH_SCRAP"], difficulty) }
    }

    fun defeatBrowserShip(difficulty: Difficulty) {
        browserLifetimeShips++
        dirty = true
        if (browserLifetimeShips >= 1000) progressionAchievements?.let { unlockAchievement(it["ACH_SHIPS"], difficulty) }
    }

    fun startBrowserRun() {
        browserNewCrewRecords.clear()
        browserGamesPlayed++
        dirty = true
    }
    fun visitBrowserBeacon() { browserLifetimeBeacons++; dirty = true }
    fun recruitBrowserCrew() { browserLifetimeCrew++; dirty = true }

    /**
     * ScoreKeeper +0x2c0 / +0x2c4 / +0x2c8: where the last submitted score went in the top list and in its ship's
     * list, -1 when it didn't make it (AddTopScore 0x00446890). Not saved: OnInit (0x0044eb10) starts at -1.
     */
    var browserNewTopIndex = -1
        private set
    var browserNewShipIndex = -1
        private set
    var browserNewScoreShip: String? = null
        private set

    fun recordBrowserRun(record: ftl.browser.BrowserRunRecord) {
        if (record.outcome == GameOverWindow.Outcome.WIN) browserVictories++
        browserLastRun = record
        if (record.complete) {
            // FUN_00446220 inserts a score after every one at least as high, then trims the list (five overall,
            // four per ship); the index is -1 once trimmed away. A zero score is never submitted (Save 0x00447690).
            browserNewTopIndex = -1
            browserNewShipIndex = -1
            browserNewScoreShip = null
            if (record.score > 0) {
                val top = browserHighScores().count { it.score >= record.score }
                val ship = browserHighScores(record.ship).count { it.score >= record.score }
                browserNewTopIndex = if (top < 5) top else -1
                browserNewShipIndex = if (ship < 4) ship else -1
                browserNewScoreShip = record.ship
            }
            browserMostScrap = maxOf(browserMostScrap, record.scrap)
            browserMostShips = maxOf(browserMostShips, record.ships)
            browserMostBeacons = maxOf(browserMostBeacons, record.beacons)
            if (record.crewComplete) browserMostCrew = maxOf(browserMostCrew, record.crew)
            if (record.score > 0) browserScores.add(record)
            // Native retains five overall and four for each layout.
            val ranked = browserScores.indices.sortedByDescending { browserScores[it].score }
            val retained = ranked.take(5).toMutableSet()
            for (ship in browserScores.map { it.ship }.distinct())
                retained.addAll(ranked.filter { browserScores[it].ship == ship }.take(4))
            val scores = browserScores.filterIndexed { index, _ -> index in retained }
            browserScores.clear()
            browserScores.addAll(scores)
        }
        dirty = true
    }

    // These must be manually marked as dirty, since they can change quite
    // a lot when the slider is being moved.
    var musicVolume: Float = 1f
    var soundVolume: Float = 1f
    var lastAdvancedEdition: Boolean = true
    var achievementPopups: Boolean = true
    var autoPauseOnFocusLoss: Boolean = true
    var showBeaconPaths: Boolean = true
    var eventChoiceHotkeys: EventChoiceHotkeyMode = EventChoiceHotkeyMode.ENABLED
    /** Zero removes the browser render limit; simulation updates are unaffected. */
    var frameRateLimit: Int = 60
        set(value) { field = if (value == 0) 0 else value.coerceIn(30, 360) }
    /** ScoreKeeper's ship-list tutorial state: 0 unseen, 1 encouraged, 2 shown. */
    var listTutorialState: Int = 0

    /**
     * Set to true if this profile needs saving.
     */
    var dirty = false
        private set

    fun getAchievement(ach: Achievement): AchievementUnlockInfo? {
        return unlockedAchievements[ach.id]
    }

    /**
     * Remove an achievement, if it's unlocked. This is intended for debugging.
     */
    fun deleteAchievement(ach: Achievement) {
        unlockedAchievements.remove(ach.id)
        dirty = true
    }

    fun unlockAchievement(ach: Achievement, difficulty: Difficulty) {
        val current = unlockedAchievements[ach.id]

        // AchievementTracker::SetAchievement (0x432010): an unlocked achievement is only re-awarded when it is
        // multi-difficulty (+0x79) and was recorded below the current difficulty (+0x7c); every other
        // achievement keeps its first unlock and difficulty.
        if (current != null && (!ach.isMultiDifficulty || current.difficulty.ordinal >= difficulty.ordinal)) {
            return
        }

        unlockedAchievements[ach.id] = AchievementUnlockInfo(difficulty)
        dirty = true
        if ((current == null || ach.isMultiDifficulty) && achievementPopups && !suppressNotifications)
            pendingNotifications.add(ProgressNotification(achievement = ach.id))
        ftl.browser.BrowserDebug.record("progression", "achievement.unlock", "{\"achievement\":${ftl.browser.BrowserDebug.quote(ach.id)},\"difficulty\":${ftl.browser.BrowserDebug.quote(difficulty.name)}}")
        reconcileProgression()
    }

    fun getShipUnlock(shipFamily: ShipFamily): AchievementUnlockInfo? {
        requireNotNull(shipFamily.unlockId)
        return unlockedShips[shipFamily.unlockId]
    }

    fun deleteShipUnlock(shipFamily: ShipFamily) {
        requireNotNull(shipFamily.unlockId)
        unlockedShips.remove(shipFamily.unlockId)
        dirty = true
    }

    /**
     * Unlock a ship family's first ship. An event's unlock ([fromEvent], which plays the unlock sound itself)
     * pops up "Ship Unlocked!" and then its quest achievement, " Quest" (quest_ach_title).
     */
    fun unlockShip(shipFamily: ShipFamily, difficulty: Difficulty, fromEvent: Boolean = false) {
        requireNotNull(shipFamily.unlockId)

        val current = unlockedShips[shipFamily.unlockId]
        if (current == null && achievementPopups && !suppressNotifications) {
            val ship = shipFamily.ships.first()
            pendingNotifications.add(ProgressNotification(ship = ship, silent = fromEvent))
            if (fromEvent)
                pendingNotifications.add(ProgressNotification(questShip = ship))
        }

        // If we've already unlocked the achievement on a harder difficulty,
        // don't overwrite it with the easier unlock.
        if (current != null && current.difficulty.ordinal >= difficulty.ordinal) {
            return
        }

        unlockedShips[shipFamily.unlockId] = AchievementUnlockInfo(difficulty)
        dirty = true
        reconcileProgression()
    }

    fun getKeybinds(): Map<String, String?> {
        return keybinds
    }

    fun setKeybind(action: Hotkey, button: HotkeyButton) {
        keybinds[action.id] = button.id
        dirty = true
    }

    fun unbindKey(action: Hotkey) {
        keybinds[action.id] = null
        dirty = true
    }

    fun resetHotkeyToDefault(action: Hotkey) {
        keybinds.remove(action.id)
        dirty = true
    }

    fun save(): Document {
        val doc = Document(Element("xftl-profile"))
        val root = doc.rootElement
        root.setAttribute("achPopups", achievementPopups.toString())
        root.setAttribute("hangarAE", lastAdvancedEdition.toString())
        root.setAttribute("fpsLimit", frameRateLimit.toString())
        root.setAttribute("autoPause", autoPauseOnFocusLoss.toString())
        root.setAttribute("beaconPaths", showBeaconPaths.toString())
        root.setAttribute("dialogKeys", eventChoiceHotkeys.id.toString())
        root.setAttribute("listTutorial", listTutorialState.toString())
        for ((ship, info) in unlockedLayouts.entries.sortedBy { it.key }) root.addContent(Element("layout").setAttribute("ship", ship).setAttribute("diff", info.difficulty.name))
        for ((ship, info) in layoutVictories.entries.sortedBy { it.key }) root.addContent(Element("layoutVictory").setAttribute("ship", ship).setAttribute("diff", info.difficulty.name))

        for ((id, info) in unlockedAchievements.entries.sortedBy { it.key }) {
            val elem = Element("ach")
            root.addContent(elem)

            elem.setAttribute("id", id)
            elem.setAttribute("diff", info.difficulty.name)
        }

        for ((id, info) in unlockedShips.entries.sortedBy { it.key }) {
            val elem = Element("ship")
            root.addContent(elem)

            elem.setAttribute("unlockId", id)
            elem.setAttribute("diff", info.difficulty.name)
        }

        for ((hotkey, button) in keybinds.entries.sortedBy { it.key }) {
            val elem = Element("hotkey")
            root.addContent(elem)

            elem.setAttribute("action", hotkey)
            if (button != null) {
                elem.setAttribute("key", button)
            }
        }

        val volumes = Element("soundVolume")
        root.addContent(volumes)
        volumes.setAttribute("sfx", soundVolume.toString())
        volumes.setAttribute("music", musicVolume.toString())

        val stats = Element("browserStats").setAttribute("games", browserGamesPlayed.toString()).setAttribute("wins", browserVictories.toString())
            .setAttribute("scrap", browserLifetimeScrap.toString()).setAttribute("ships", browserLifetimeShips.toString())
            .setAttribute("beacons", browserLifetimeBeacons.toString()).setAttribute("crew", browserLifetimeCrew.toString())
            .setAttribute("mostScrap", browserMostScrap.toString()).setAttribute("mostShips", browserMostShips.toString())
            .setAttribute("mostBeacons", browserMostBeacons.toString()).setAttribute("mostCrew", browserMostCrew.toString())
        browserLastRun?.let { stats.addContent(it.save("last")) }
        for (record in browserScores) stats.addContent(record.save("score"))
        for (kind in ftl.browser.CrewRecordKind.entries) browserCrewRecords[kind]?.let { stats.addContent(it.save()) }
        root.addContent(stats)
        return doc
    }

    /**
     * Request that the profile should be saved.
     */
    fun markDirty() {
        dirty = true
    }

    fun markSaveComplete() {
        dirty = false
    }

    fun load(doc: Document) {
        val root = doc.rootElement
        check(root.name == "xftl-profile")
        clearNotifications()
        achievementPopups = root.getAttributeValue("achPopups")?.toBoolean() ?: true
        lastAdvancedEdition = root.getAttributeValue("hangarAE")?.toBoolean() ?: true
        frameRateLimit = root.getAttributeValue("fpsLimit")?.toIntOrNull() ?: 60
        autoPauseOnFocusLoss = root.getAttributeValue("autoPause")?.toBooleanStrictOrNull() ?: true
        showBeaconPaths = root.getAttributeValue("beaconPaths")?.toBooleanStrictOrNull() ?: true
        eventChoiceHotkeys = EventChoiceHotkeyMode.fromId(root.getAttributeValue("dialogKeys")?.toIntOrNull())
        listTutorialState = root.getAttributeValue("listTutorial")?.toIntOrNull()?.coerceIn(0, 2) ?: 0
        unlockedAchievements.clear()
        unlockedShips.clear()
        unlockedLayouts.clear()
        layoutVictories.clear()
        keybinds.clear()
        browserScores.clear()
        browserCrewRecords.clear()
        browserGamesPlayed = 0
        browserVictories = 0
        browserLifetimeScrap = 0
        browserLifetimeShips = 0
        browserLifetimeBeacons = 0
        browserLifetimeCrew = 0
        browserMostScrap = 0
        browserMostShips = 0
        browserMostBeacons = 0
        browserMostCrew = 0
        browserLastRun = null
        for (elem in root.getChildren("layout")) unlockedLayouts[elem.getAttributeValue("ship")] = AchievementUnlockInfo(Difficulty.valueOf(elem.getAttributeValue("diff")))
        for (elem in root.getChildren("layoutVictory")) layoutVictories[elem.getAttributeValue("ship")] = AchievementUnlockInfo(Difficulty.valueOf(elem.getAttributeValue("diff")))
        root.getChild("browserStats")?.let { stats ->
            browserGamesPlayed = stats.getAttributeValue("games").toInt().coerceAtLeast(0)
            browserVictories = stats.getAttributeValue("wins").toInt().coerceAtLeast(0)
            browserLifetimeScrap = stats.getAttributeValue("scrap")?.toLong()?.coerceAtLeast(0) ?: 0
            browserLifetimeShips = stats.getAttributeValue("ships")?.toLong()?.coerceAtLeast(0) ?: 0
            browserLifetimeBeacons = stats.getAttributeValue("beacons")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            browserLifetimeCrew = stats.getAttributeValue("crew")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            browserMostScrap = stats.getAttributeValue("mostScrap")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            browserMostShips = stats.getAttributeValue("mostShips")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            browserMostBeacons = stats.getAttributeValue("mostBeacons")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            browserMostCrew = stats.getAttributeValue("mostCrew")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            browserLastRun = stats.getChild("last")?.let { ftl.browser.BrowserRunRecord.load(it) }
            browserScores.addAll(stats.getChildren("score").map { ftl.browser.BrowserRunRecord.load(it) })
            for (elem in stats.getChildren("crewRecord")) ftl.browser.BrowserCrewRecord.load(elem)?.let { record ->
                if (record.value > (browserCrewRecords[record.kind]?.value ?: 0)) browserCrewRecords[record.kind] = record
            }
        }

        for (elem in root.getChildren("ach")) {
            val id = elem.getAttributeValue("id")
            val difficultyName = elem.getAttributeValue("diff")

            unlockedAchievements[id] = AchievementUnlockInfo(
                Difficulty.valueOf(difficultyName)
            )
        }

        for (elem in root.getChildren("ship")) {
            val id = elem.getAttributeValue("unlockId")
            val difficultyName = elem.getAttributeValue("diff")

            unlockedShips[id] = AchievementUnlockInfo(
                Difficulty.valueOf(difficultyName)
            )
        }

        for (elem in root.getChildren("hotkey")) {
            val action: String = elem.getAttributeValue("action")
            val key: String? = elem.getAttributeValue("key")
            keybinds[action] = key
        }

        val volumes = root.getChild("soundVolume")
        if (volumes != null) {
            soundVolume = volumes.getAttributeValue("sfx").toFloat()
            musicVolume = volumes.getAttributeValue("music").toFloat()
        }
        suppressNotifications = true
        try { reconcileProgression() } finally { suppressNotifications = false }
    }

    class AchievementUnlockInfo(val difficulty: Difficulty)

    companion object {
        @JvmField
        val PROFILE_NAME = "save-profile.xml"

        // Make it a bit more explicit what this is doing
        @JvmStatic
        fun createBlank(): SaveProfile {
            val profile = SaveProfile()

            // Immediately save the empty profile, so there's at least something there.
            profile.dirty = true

            return profile
        }

        @JvmStatic
        fun load(path: Path): SaveProfile? {
            val profile = SaveProfile()

            val doc: Document = try {
                Files.newBufferedReader(path).use { reader ->
                    val builder = com.pocketwormhole.android.SafeXML.builder()
                                        builder.build(reader)
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
                return null
            } catch (ex: JDOMException) {
                ex.printStackTrace()
                return null
            }

            profile.load(doc)
            return profile
        }
    }
}
