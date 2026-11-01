package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.save.HsSave

/**
 * `CustomShipUnlocks`: how Hyperspace hangar entries unlock (the `<unlock>` children of `<ships><ship>`) and the
 * profile state behind it (unlocked custom layouts, unlock-quest and victory difficulties per layout), stored in the
 * modded profile through [HsSave] (key "ships.unlocks"; HS keeps it in hs_mv_unlocks.sav).
 *
 * Unlock achievements and their popups are recorded in [recentlyUnlocked] for the hangar/achievement UI.
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/ShipUnlocks.cpp (ParseUnlockNode, GetCustomShipUnlocked,
// UnlockShip, CheckMultiUnlocks, CheckMultiVictoryUnlocks, CheckSectorUnlocks, CheckBasicUnlock, SetVictoryAchievement,
// UnlockAllShips, WipeProfile, Save/LoadVersionTwo).
object HsShipUnlocks {
    enum class Type { DEFEAT_FLAGSHIP, REACH_SECTOR, VICTORY_ANY, UNLOCK_OTHER, EVENT, VICTORY_CUSTOM }

    class ShipUnlock(
        @JvmField val unlocksShip: String,
        @JvmField val type: Type?,
        @JvmField val otherRequiredShips: List<String>,
        @JvmField val value: Int,
        @JvmField val shipReq: String,
        @JvmField val silent: Boolean,
    )

    /** Ship (layout) name -> its unlock methods; any one unlocks it. Insertion order. */
    private val customShipUnlocks = LinkedHashMap<String, MutableList<ShipUnlock>>()

    @JvmField val customUnlockedShips = ArrayList<String>()
    @JvmField val customUnlockQuestShips = LinkedHashMap<String, Int>()
    @JvmField val shipVictories = LinkedHashMap<String, Int>()
    @JvmField val customShipVictories = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    /** `CustomShipUnlocks::setCustomVictoryType` (set by events' victory types; "flagship" by default). */
    @JvmField var setCustomVictoryType = "flagship"

    /** Layouts unlocked with a popup since the UI last looked (name, quest?). */
    @JvmField val recentlyUnlocked = ArrayList<Pair<String, Boolean>>()

    /** A victory achievement's popup: its type (null for the plain ship victory) and the ship it is shown for. */
    @JvmField var victoryPopup: (String?, String) -> Unit = { _, _ -> }

    /** Hook for vanilla ships named by an unlock (`CheckVanillaUnlocks` -> ScoreKeeper::UnlockShip). */
    @JvmField var vanillaUnlock: ((String, Int) -> Boolean)? = null

    /** Profile save hook (ScoreKeeper::Save after a change); set by the profile owner. */
    @JvmField var saveProfile: (() -> Unit)? = null

    const val SAVE_KEY = "ships.unlocks"

    /** The profile block; registered by [HsShips.registerCapabilities] at HS start, before the profile loads. */
    @JvmStatic
    fun register() = HsSave.register(SAVE_KEY, HsSave.Scope.PROFILE, ::writeProfile, ::readProfile)

    /** Rebuild the unlock table from [HsShips.unlockNodes] (after HsShips.build). */
    @JvmStatic
    fun load() {
        customShipUnlocks.clear()
        for ((ship, nodes) in HsShips.unlockNodes) for (n in nodes) {
            try {
                parseUnlockNode(n, ship)
            } catch (e: Exception) {
                HS.unsupported("parse", HS.HYPERSPACE_XML, "<unlock> of $ship: ${e.message ?: e}")
            }
        }
        quietMultiUnlocks()
    }

    private fun quietMultiUnlocks() {
        val save = saveProfile
        saveProfile = null
        try {
            checkMultiUnlocks()
            checkMultiVictoryUnlocks()
        } finally {
            saveProfile = save
        }
        recentlyUnlocked.clear()
    }

    private fun parseUnlockNode(node: Element, shipBlueprint: String) {
        var variant = 0
        when (node.getAttributeValue("variant")) { "b" -> variant = 1; "c" -> variant = 2 }
        val shipName = HsShips.variantName(shipBlueprint, variant)
        var type: Type? = null
        var shipReq = ""
        var value = 0
        var silent = false
        val others = ArrayList<String>()
        for (c in node.children) {
            val v = c.text
            when (c.name) {
                "type" -> type = Type.values().getOrNull(v.toInt())
                "shipReq" -> shipReq = v
                "value" -> value = v.toInt()
                "silent" -> silent = HsConfig.bool(v)
                "otherUnlocks" -> for (s in c.children) if (s.name == "ship") others += s.text
                "victories" -> for (s in c.children) if (s.name == "victory") {
                    val t = s.getAttributeValue("type")
                    others += if (t != null) s.text + " " + t else s.text
                }
            }
        }
        customShipUnlocks.getOrPut(shipName) { ArrayList() } += ShipUnlock(shipName, type, others, value, shipReq, silent)
    }

    fun unlocksOf(name: String): List<ShipUnlock> = customShipUnlocks[name] ?: emptyList()

    @JvmStatic
    fun customShipHasUnlock(name: String): Boolean = customShipUnlocks.containsKey(name)

    /** `GetCustomShipUnlocked`: a layout without an unlock method is always available. */
    @JvmStatic
    @JvmOverloads
    fun isUnlocked(name: String, variant: Int = 0): Boolean {
        val n = HsShips.variantName(name, variant)
        return !customShipHasUnlock(n) || customUnlockedShips.contains(n)
    }

    @JvmStatic
    fun checkCustomShipVictory(name: String): Int = shipVictories[name] ?: -1

    @JvmStatic
    fun checkCustomShipVictory(name: String, type: String): Int = customShipVictories[type]?.get(name) ?: -1

    @JvmStatic
    fun checkCustomShipUnlockQuest(name: String): Int = customUnlockQuestShips[name] ?: -1

    /**
     * `SeedInputBox::seedsAllowUnlocks || !Global::IsSeededRun()` (Seeds.cpp): a seeded run unlocks no ships unless
     * `<seeds allowUnlocks="true">`.
     */
    @JvmStatic
    fun unlocksAllowed(): Boolean =
        !xyz.znix.xftl.hs.HS.active || xyz.znix.xftl.hs.HS.cfg().seedsAllowUnlocks || !xyz.znix.xftl.hs.sector.HsSeeds.isSeededRun

    /** `UnlockShip(ship, silent, checkMultiUnlocks, isEvent)`; seeded runs: unlocks need allowUnlocks, quests allowAchievements. */
    @JvmStatic
    @JvmOverloads
    fun unlockShip(ship: String, silent: Boolean, checkMultiUnlocks: Boolean = true, isEvent: Boolean = false, difficulty: Int = 0) {
        if (unlocksAllowed() && !isUnlocked(ship)) {
            if (!silent) recentlyUnlocked += Pair(ship, false)
            customUnlockedShips += ship
            if (checkMultiUnlocks) checkMultiUnlocks()
        }
        if (HsAchievements.achievementsAllowed() && isEvent && checkCustomShipUnlockQuest(ship) < difficulty) {
            customUnlockQuestShips[ship] = difficulty
            if (!silent) recentlyUnlocked += Pair(ship, true)
        }
        saveProfile?.invoke()
    }

    private fun validUnlock(u: ShipUnlock, currentShip: String, type: Type) =
        type == u.type && (u.shipReq.isEmpty() || u.shipReq == currentShip)

    private fun checkVanillaUnlocks(u: ShipUnlock): Boolean {
        val base = u.unlocksShip.removeSuffix("_2").removeSuffix("_3")
        if (base !in HsShips.VANILLA_SHIP_NAMES) return false
        val variant = if (u.unlocksShip.endsWith("_2")) 1 else if (u.unlocksShip.endsWith("_3")) 2 else 0
        vanillaUnlock?.invoke(base, variant)
        customUnlockedShips += u.unlocksShip
        checkMultiUnlocks()
        return true
    }

    @JvmStatic
    fun checkMultiUnlocks() {
        for ((ship, list) in customShipUnlocks.entries.toList()) for (u in list) {
            if (u.type != Type.UNLOCK_OTHER) continue
            if (u.otherRequiredShips.all { isUnlocked(it) }) unlockShip(ship, u.silent, false)
        }
    }

    @JvmStatic
    fun checkMultiVictoryUnlocks() {
        for ((ship, list) in customShipUnlocks.entries.toList()) for (u in list) {
            if (u.type != Type.VICTORY_CUSTOM) continue
            val ok = u.otherRequiredShips.all { check ->
                val s = check.split(' ')
                if (s.size >= 2) checkCustomShipVictory(s[0], s[1]) != -1 else checkCustomShipVictory(check) != -1
            }
            if (ok) unlockShip(ship, u.silent, false)
        }
    }

    /** StarMap::AdvanceWorldLevel: `sector + 1 >= value` for REACH_SECTOR methods of the current ship. */
    @JvmStatic
    fun checkSectorUnlocks(currentShip: String, sector: Int) {
        for ((ship, list) in customShipUnlocks.entries.toList()) for (u in list) {
            if (validUnlock(u, currentShip, Type.REACH_SECTOR) && sector + 1 >= u.value) {
                if (!checkVanillaUnlocks(u)) unlockShip(ship, u.silent)
            }
        }
    }

    @JvmStatic
    fun checkBasicUnlock(currentShip: String, type: Type) {
        for ((ship, list) in customShipUnlocks.entries.toList()) for (u in list) {
            if (validUnlock(u, currentShip, type)) {
                if (!checkVanillaUnlocks(u)) unlockShip(ship, u.silent)
            }
        }
    }

    /** AchievementTracker::SetVictoryAchievement under HS (after the vanilla victory). */
    @JvmStatic
    fun victory(currentShip: String, difficulty: Int) {
        if (setCustomVictoryType == "flagship") checkBasicUnlock(currentShip, Type.DEFEAT_FLAGSHIP)
        if (HsAchievements.achievementsAllowed()) {
            setVictory(currentShip, difficulty)
            if (setCustomVictoryType.isNotEmpty()) setVictory(currentShip, setCustomVictoryType, difficulty)
        } else {
            // A seeded run records no victory, but a single specific ending can still unlock.
            checkMultiVictoryUnlocks()
        }
        setCustomVictoryType = "flagship"
        saveProfile?.invoke()
    }

    /** CommandGui::Victory: VICTORY_ANY methods. */
    @JvmStatic
    fun anyVictory(currentShip: String) = checkBasicUnlock(currentShip, Type.VICTORY_ANY)

    private fun setVictory(ship: String, difficulty: Int) {
        if (HsShips.isCustomShip(ship) && checkCustomShipVictory(ship) < difficulty) {
            shipVictories[ship] = difficulty
            HsHangar.button(ship)?.let { victoryPopup(null, if (it.splitVictoryAchievement) ship else it.name) }
            checkMultiVictoryUnlocks()
        }
    }

    private fun setVictory(ship: String, type: String, difficulty: Int) {
        if (checkCustomShipVictory(ship, type) < difficulty) {
            customShipVictories.getOrPut(type) { LinkedHashMap() }[ship] = difficulty
            val victory = HsVictories.victories[type]
            if (victory != null && !victory.quiet) {
                val button = HsHangar.button(ship)
                val shown = if (button == null) ship.removeSuffix("_2").removeSuffix("_3")
                else if (button.splitVictoryAchievement) ship else button.name
                victoryPopup(type, shown)
            }
            checkMultiVictoryUnlocks()
        }
    }

    /** `CustomShipUnlocks::RemoveShipUnlock(name)` (console SHIP_CUSTOM_LOCK): the ship is locked again, the profile saved. */
    @JvmStatic
    fun removeShipUnlock(name: String) {
        customUnlockedShips.remove(name)
        saveProfile?.invoke()
    }

    @JvmStatic
    fun unlockAllShips() {
        for (ship in customShipUnlocks.keys.toList()) unlockShip(ship, true, false)
    }

    @JvmStatic
    fun wipeProfile() {
        customUnlockedShips.clear(); customUnlockQuestShips.clear(); shipVictories.clear(); customShipVictories.clear()
    }

    private fun writeProfile(e: Element) {
        for (s in customUnlockedShips) e.addContent(Element("unlocked").setAttribute("ship", s))
        for ((s, d) in customUnlockQuestShips) e.addContent(Element("quest").setAttribute("ship", s).setAttribute("difficulty", d.toString()))
        for ((s, d) in shipVictories) e.addContent(Element("victory").setAttribute("ship", s).setAttribute("difficulty", d.toString()))
        for ((t, m) in customShipVictories) for ((s, d) in m)
            e.addContent(Element("victory").setAttribute("type", t).setAttribute("ship", s).setAttribute("difficulty", d.toString()))
    }

    /** LoadVersionTwo (entries of -1 are dropped), then ScoreKeeper::LoadVersionFour's multi-unlock checks. */
    private fun readProfile(e: Element?) {
        wipeProfile()
        if (e != null) for (c in e.children) {
            val ship = c.getAttributeValue("ship") ?: continue
            val d = c.getAttributeValue("difficulty")?.toIntOrNull() ?: -1
            when (c.name) {
                "unlocked" -> customUnlockedShips += ship
                "quest" -> if (d != -1) customUnlockQuestShips[ship] = d
                "victory" -> if (d != -1) {
                    val t = c.getAttributeValue("type")
                    if (t == null) shipVictories[ship] = d else customShipVictories.getOrPut(t) { LinkedHashMap() }[ship] = d
                }
            }
        }
        quietMultiUnlocks()
    }
}
