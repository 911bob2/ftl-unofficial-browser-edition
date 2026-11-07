package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.save.HsSave

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomAchievements.cpp (ParseAchievements,
// ParseAchievement, ParseShipAchievement, CustomAchievement::ParseAchievement, Save/LoadVersionThree, GetAchievementStatus,
// UpdateAchievement(s), UpdateVariableAchievements, CheckShipAchievement, SetAchievement, RemoveAchievement, ResetFlags,
// WipeProfile, GetShipAchievementsCustom, NextPage/PrevPage). This engine is AGPL-3.0-or-later.

/**
 * Hyperspace custom achievements (`<achievements>` in hyperspace.xml and `<ships><ship><achievement>`): definitions, the
 * stats pages' structure, unlock state per profile (HsSave key "ships.achievements"; HS keeps it in its profile file) and
 * the calls events and Lua make (SetAchievement, UpdateVariableAchievements, GetAchievementStatus).
 *
 * Unlocks with a popup go to [recentlyUnlocked] for the UI; difficulty is the run's (0 easy, 1 normal, 2 hard).
 */
object HsAchievements {
    class CustomAchievement(@JvmField val nameId: String) {
        @JvmField var name: GameText? = null
        @JvmField var description: GameText? = null
        @JvmField var secretName: GameText? = null
        @JvmField var secretDescription: GameText? = null
        @JvmField var header: GameText? = null
        /** `achievements/<icon>.png` (CAchievement::icon), empty when unset. */
        @JvmField var icon = ""
        @JvmField var lockImage = ""
        @JvmField var sound = ""
        @JvmField var hidden = false
        @JvmField var multiDifficulty = true
        /** CAchievement::progress (current, needed); needed 0 without a `<variable>`. */
        @JvmField var progress = 0
        @JvmField var progressMax = 0
        /** The ship entry a ship achievement belongs to (empty for a general one). */
        @JvmField var ship = ""
        /** -2 misc custom achievement, -4 ship achievement (CAchievement::gap_ex_custom). */
        @JvmField var kind = -2
        @JvmField var unlocked = false
        @JvmField var difficulty = -1
        @JvmField var newAchievement = false

        /** CAchievement::name/description after UpdateAchievement: the secret texts until unlocked, when given. */
        val shownName: GameText? get() = if (unlocked || secretName == null) name else secretName
        val shownDescription: GameText? get() = if (unlocked || secretDescription == null) description else secretDescription
    }

    class Section {
        @JvmField var heading: GameText = GameText.literal("")
        @JvmField var secretHeading: GameText? = null
        @JvmField var hidden = false
        @JvmField val achievements = ArrayList<CustomAchievement>()

        private fun heightFor(n: Int) = if (n < 1) 160 else 90 + 70 * ((n + 6) / 7)

        /** GetMaxHeight: rows of seven, every achievement counted (the page split). */
        fun maxHeight(): Int = heightFor(achievements.size)

        /** GetHeight: rows of seven of the shown achievements (hidden ones count once unlocked). */
        fun height(): Int = heightFor(achievements.count { !it.hidden || it.unlocked })

        fun unlockedCount(): Int = achievements.count { it.unlocked }

        /** Visible: any achievement unlocked, or the section is not hidden. */
        fun visible(): Boolean = achievements.any { it.unlocked } || !hidden
    }

    class Page {
        @JvmField val sections = ArrayList<Section>()
        fun visible(): Boolean = sections.any { it.visible() }
    }

    @JvmField val customAchievements = LinkedHashMap<String, CustomAchievement>()
    private val variableAchievements = HashMap<String, ArrayList<CustomAchievement>>()
    @JvmField val achievementUnlocks = LinkedHashMap<String, Int>()
    @JvmField val pages = ArrayList<Page>()
    @JvmField val recentlyUnlocked = ArrayList<CustomAchievement>()

    /** `<statsTextColor>` / `<statsBorderColor>` (achievementPagesBoxTextColor / OutlineColor). */
    @JvmField var textColour = xyz.znix.xftl.rendering.Colour(1f, 1f, 1f, 1f)
    @JvmField var outlineColour = xyz.znix.xftl.rendering.Colour(235, 245, 229)

    /** The run's difficulty for SetAchievement; set by the game (Global::difficulty). */
    @JvmField var difficulty: () -> Int = { 1 }

    /** AchievementTracker::currentShip: the player's ship blueprint name, for ship achievements. */
    @JvmField var currentShip: () -> String = { "" }

    /** `SeedInputBox::seedsAllowAchievements || !Global::IsSeededRun()` (seeds: hscore). */
    @JvmField var achievementsAllowed: () -> Boolean =
        { xyz.znix.xftl.hs.HS.cfg().seedsAllowAchievements || !xyz.znix.xftl.hs.sector.HsSeeds.isSeededRun }

    /** Whether achievement popups are on (Settings::achPopups). */
    @JvmField var popups: () -> Boolean = { true }

    /** The run whose difficulty and ship SetAchievement uses (set when a run starts). */
    @JvmStatic
    fun bindGame(game: xyz.znix.xftl.game.InGameState) {
        difficulty = { game.difficulty.ordinal }
        currentShip = { game.player?.type?.name ?: "" }
        // A bare game of the automated tests has no MainGame (its getter throws there).
        if (!game.isRunningAutomatedTest) game.mainGame.profile?.let { bindProfile(it) }
    }

    /** Settings::achPopups and the popup queue of this profile. */
    fun bindProfile(profile: xyz.znix.xftl.game.SaveProfile) {
        popups = { profile.achievementPopups }
        showPopup = { profile.addHsNotification(xyz.znix.xftl.game.SaveProfile.ProgressNotification(hsAchievement = it.nameId)) }
    }

    /**
     * AchievementTracker::recentlyUnlocked: the popup queue the original shows one at a time (OnLoop plays the
     * achievement's `<sound>` instead of the default one, OnRender draws it); here the profile's notifications.
     */
    @JvmField var showPopup: (CustomAchievement) -> Unit = {}

    const val SAVE_KEY = "ships.achievements"

    fun register() = HsSave.register(SAVE_KEY, HsSave.Scope.PROFILE, ::writeProfile, ::readProfile)

    /** ParseColorNode: r, g, b (0-255) and a (0-1) attributes over the current colour. */
    private fun colour(n: Element, old: xyz.znix.xftl.rendering.Colour): xyz.znix.xftl.rendering.Colour {
        val c = xyz.znix.xftl.rendering.Colour(old)
        n.getAttributeValue("r")?.let { c.r = it.toFloat() / 255f }
        n.getAttributeValue("g")?.let { c.g = it.toFloat() / 255f }
        n.getAttributeValue("b")?.let { c.b = it.toFloat() / 255f }
        n.getAttributeValue("a")?.let { c.a = it.toFloat() }
        return c
    }

    private fun text(c: Element): GameText? {
        var t: GameText? = null
        c.getAttributeValue("id")?.let { t = GameText.localised(it) }
        if (c.text.isNotEmpty()) t = GameText.literal(c.text)
        return t
    }

    /** The definitions: `<achievements>` nodes (general ones and pages), then the ship achievements of `<ships>`. */
    fun load(config: HsConfig?) {
        customAchievements.clear(); variableAchievements.clear(); pages.clear(); recentlyUnlocked.clear()
        textColour = xyz.znix.xftl.rendering.Colour(1f, 1f, 1f, 1f); outlineColour = xyz.znix.xftl.rendering.Colour(235, 245, 229)
        config ?: return
        for (node in config.nodesOf("achievements")) {
            try {
                parseAchievements(node)
            } catch (e: Exception) {
                HS.unsupported("parse", HS.HYPERSPACE_XML, "<achievements>: ${e.message ?: e}")
            }
        }
        for (b in HsShips.buttons) {
            val seen = HashSet<Element>()
            for (list in b.shipAchievements) for (e in list) if (seen.add(e)) parseAchievement(e, b.name)
        }
        updateAchievements()
    }

    private fun parseAchievements(node: Element) {
        var height = 9999
        for (n in node.children) {
            when (n.name) {
                "achievement" -> parseAchievement(n, null)
                "statsBorderColor" -> outlineColour = colour(n, outlineColour)
                "statsTextColor" -> textColour = colour(n, textColour)
                "page" -> for (child in n.children) {
                    if (child.name != "section") continue
                    val section = Section()
                    child.getAttributeValue("id")?.let { section.heading = GameText.localised(it) }
                    child.getAttributeValue("text")?.let { section.heading = GameText.literal(it) }
                    child.getAttributeValue("secretId")?.let { section.secretHeading = GameText.localised(it) }
                    child.getAttributeValue("secretText")?.let { section.secretHeading = GameText.literal(it) }
                    child.getAttributeValue("hidden")?.let { section.hidden = HsConfig.bool(it) }
                    for (a in child.children) {
                        val name = if (a.name == "achievement" && a.getAttributeValue("name") != null) {
                            parseAchievement(a, null); a.getAttributeValue("name")
                        } else a.name
                        customAchievements[name]?.let { section.achievements += it }
                    }
                    val h = section.maxHeight()
                    if (height + h > 500) { height = 0; pages += Page() }
                    height += h
                    pages.last().sections += section
                }
            }
        }
    }

    /** ParseAchievement / ParseShipAchievement (with [ship]): `name` defines, `load` refers to one defined elsewhere. */
    private fun parseAchievement(node: Element, ship: String?): CustomAchievement {
        val define: Boolean
        val achName: String
        when {
            node.getAttributeValue("name") != null -> { define = true; achName = node.getAttributeValue("name") }
            node.getAttributeValue("load") != null -> { define = false; achName = node.getAttributeValue("load") }
            else -> { define = true; achName = "" }
        }
        val ach = customAchievements.getOrPut(achName) { CustomAchievement(achName) }
        ach.kind = if (ship != null) -4 else -2
        ach.multiDifficulty = true
        if (define) {
            if (ship != null) ach.ship = ship
            var varName = ""
            for (c in node.children) {
                when (c.name) {
                    "name" -> text(c)?.let { ach.name = it }
                    "description" -> text(c)?.let { ach.description = it }
                    "header" -> text(c)?.let { ach.header = it }
                    "secretName" -> text(c)?.let { ach.secretName = it }
                    "secretDescription" -> text(c)?.let { ach.secretDescription = it }
                    "icon" -> ach.icon = "achievements/" + c.text + ".png"
                    "lockImage" -> ach.lockImage = "achievements/" + c.text + ".png"
                    "variable" -> {
                        val n = c.getAttributeValue("name"); val amount = c.getAttributeValue("amount")
                        if (n != null && amount != null) { varName = n; ach.progressMax = amount.toInt(); ach.progress = 0 }
                    }
                    "multiDifficulty" -> ach.multiDifficulty = HsConfig.bool(c.text)
                    "hidden" -> ach.hidden = true
                    "sound" -> ach.sound = c.text
                }
            }
            if (varName.isNotEmpty()) variableAchievements.getOrPut(varName) { ArrayList() } += ach
            updateAchievement(ach)
        } else if (ship != null && ach.ship.isEmpty()) {
            ach.ship = ship
        }
        return ach
    }

    @JvmStatic
    fun getAchievementStatus(name: String): Int = achievementUnlocks[name] ?: -1

    private fun updateAchievement(ach: CustomAchievement) {
        val status = getAchievementStatus(ach.nameId)
        ach.unlocked = status != -1
        ach.difficulty = status
    }

    fun updateAchievements() = customAchievements.values.forEach(::updateAchievement)

    /** UpdateVariableAchievements: a variable reached an achievement's amount (outside a run: general ones, at easy). */
    @JvmStatic
    @JvmOverloads
    fun updateVariableAchievements(varName: String, varValue: Int, inGame: Boolean = true) {
        val list = variableAchievements[varName] ?: return
        for (ach in list) {
            ach.progress = varValue
            if (ach.progress >= ach.progressMax) {
                if (inGame) setAchievement(ach.nameId, false)
                else if (ach.ship.isEmpty()) setAchievement(ach.nameId, false, 0)
            }
        }
    }

    /** CheckShipAchievement: the achievement is in the current ship's layout list. */
    private fun checkShipAchievement(ach: CustomAchievement): Boolean {
        val (button, layout) = HsHangar.variantOf(currentShip()) ?: return false
        return button.shipAchievements[layout].any { it.getAttributeValue("name") == ach.nameId || it.getAttributeValue("load") == ach.nameId }
    }

    @JvmStatic
    @JvmOverloads
    fun setAchievement(name: String, noPopup: Boolean, newDiff: Int = difficulty()) {
        if (!achievementsAllowed()) return
        val oldDiff = getAchievementStatus(name)
        if (newDiff > oldDiff) {
            val ach = customAchievements.getOrPut(name) { CustomAchievement(name) }
            if (ach.ship.isNotEmpty() && !checkShipAchievement(ach)) return
            achievementUnlocks[name] = newDiff
            updateAchievement(ach)
            if (ach.multiDifficulty || oldDiff == -1) {
                ach.newAchievement = newDiff > -1
                if (!noPopup && popups()) { recentlyUnlocked += ach; showPopup(ach) }
            }
        }
    }

    @JvmStatic
    fun removeAchievement(name: String) {
        if (getAchievementStatus(name) != -1) {
            val ach = customAchievements.getOrPut(name) { CustomAchievement(name) }
            ach.newAchievement = false
            achievementUnlocks[name] = -1
            updateAchievement(ach)
        }
    }

    fun resetFlags() = customAchievements.values.forEach { it.newAchievement = false }

    fun wipeProfile() {
        achievementUnlocks.clear()
        updateAchievements()
        resetFlags()
    }

    /** GetShipAchievementsCustom: a layout's ship achievements, hidden locked ones left out unless [showHidden]. */
    fun shipAchievements(entry: String, layout: Int, showHidden: Boolean): List<CustomAchievement> {
        val b = HsShips.buttons.firstOrNull { it.name == entry } ?: return emptyList()
        return b.shipAchievements[layout].mapNotNull { e -> customAchievements[e.getAttributeValue("name") ?: e.getAttributeValue("load") ?: ""] }
            .filter { showHidden || it.unlocked || !it.hidden }
    }

    /** NextPage / PrevPage over the stats pages (0 = the vanilla page), skipping invisible ones. */
    fun nextPage(page: Int): Int {
        var p = page
        do { p++; if (p > pages.size) p = 0 } while (p > 0 && !pages[p - 1].visible())
        return p
    }

    fun prevPage(page: Int): Int {
        var p = page
        do { p--; if (p < 0) p = pages.size } while (p > 0 && !pages[p - 1].visible())
        return p
    }

    private fun writeProfile(e: Element) {
        for ((k, v) in achievementUnlocks) e.addContent(Element("unlock").setAttribute("name", k).setAttribute("difficulty", v.toString()))
    }

    /** LoadVersionThree: entries of -1 are dropped. */
    private fun readProfile(e: Element?) {
        achievementUnlocks.clear()
        if (e != null) for (c in e.getChildren("unlock")) {
            val v = c.getAttributeValue("difficulty")?.toIntOrNull() ?: -1
            if (v != -1) achievementUnlocks[c.getAttributeValue("name") ?: continue] = v
        }
        updateAchievements()
    }
}
