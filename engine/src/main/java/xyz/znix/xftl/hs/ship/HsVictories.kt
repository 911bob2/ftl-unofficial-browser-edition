package xyz.znix.xftl.hs.ship

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/ShipUnlocks.cpp (ParseVictoryAchievements,
// CustomShipUnlocks::SetupVictoryAchievement / SetupVictoryAchievementText, CustomVictoryAchievement::
// SetupVictoryAchievement / SetupVictoryAchievementText / SetupVictoryAchievementIcon / SecretLocked) and
// src/game/UserInterface/CustomShipSelect.cpp (AddShipVictoryFilter). This engine is AGPL-3.0-or-later.

/**
 * The `<victories>` node of hyperspace.xml: victory types (`flagship` by default, MV's `noFlagship` drops it), their
 * name/icon/secret variants, and the hangar's victory filters (`<filter/>`). A victory achievement is one icon per ship
 * (or ship layout) and type, drawn by the hangar and the ship list; [achievement] builds that view from the profile.
 */
object HsVictories {
    class Victory(@JvmField val type: String) {
        @JvmField var name: GameText = GameText.localised("victory_ach_title")
        @JvmField var description: GameText = GameText.localised("victory_ach")
        @JvmField var secretName: GameText? = null
        @JvmField var secretDescription: GameText? = null
        /** `achievements/<icon>_on.png` / `_off.png`; empty for HS's implicit `flagship`. */
        @JvmField var icon = ""
        @JvmField var secretIcon = ""
        @JvmField var color: Colour? = null
        @JvmField var quiet = false
        @JvmField var secret = false

        /** CustomVictoryAchievement::SecretLocked: no ship has this ending yet. */
        fun secretLocked(): Boolean = HsShipUnlocks.customShipVictories[type].isNullOrEmpty()
    }

    /** `customVictoryTypes`: definition order. */
    @JvmField val types = ArrayList<String>()
    @JvmField val victories = LinkedHashMap<String, Victory>()

    /** `shipVictoryFilters`: the types the ship list shows on each card (set by `<filter/>`, then by clicks). */
    @JvmField val shipFilters = ArrayList<String>()

    /** `CustomShipSelect::selectedVictoryFilter` is UI state: see [HsHangar]. */

    fun load(config: HsConfig?) {
        types.clear(); victories.clear(); shipFilters.clear()
        config ?: return
        for (node in config.nodesOf("victories")) {
            try {
                parse(node)
            } catch (e: Exception) {
                HS.unsupported("parse", HS.HYPERSPACE_XML, "<victories>: ${e.message ?: e}")
            }
        }
    }

    private fun text(c: Element, into: GameText?): GameText? {
        var t = into
        c.getAttributeValue("id")?.let { t = GameText.localised(it) }
        if (c.text.isNotEmpty()) t = GameText.literal(c.text)
        return t
    }

    private fun parse(node: Element) {
        val noFlagship = node.getAttributeValue("noFlagship")?.let { HsConfig.bool(it) } ?: false
        if (!noFlagship) {
            if ("flagship" !in victories) types += "flagship"
            victories["flagship"] = Victory("flagship").also { it.quiet = true }
        }
        for (v in node.children) {
            val vName = v.name
            if (vName !in victories) types += vName
            val ach = Victory(vName)
            for (c in v.children) {
                val value = c.text
                when (c.name) {
                    "name" -> text(c, ach.name)?.let { ach.name = it }
                    "description" -> text(c, ach.description)?.let { ach.description = it }
                    "icon" -> ach.icon = value
                    "secretName" -> ach.secretName = text(c, ach.secretName)
                    "secretDescription" -> ach.secretDescription = text(c, ach.secretDescription)
                    "secretIcon" -> ach.secretIcon = value
                    "color" -> {
                        val col = Colour(1f, 1f, 1f, 1f)
                        c.getAttributeValue("r")?.toFloatOrNull()?.let { col.r = it / 255f }
                        c.getAttributeValue("g")?.toFloatOrNull()?.let { col.g = it / 255f }
                        c.getAttributeValue("b")?.toFloatOrNull()?.let { col.b = it / 255f }
                        c.getAttributeValue("a")?.toFloatOrNull()?.let { col.a = it }
                        ach.color = col
                    }
                    "quiet" -> ach.quiet = true
                    "secret" -> ach.secret = true
                    "filter" -> shipFilters += vName
                }
            }
            victories[vName] = ach
        }
    }

    /** The types the hangar shows: a secret one only after any ship achieved it (the `victoryTypes` of CustomShipSelect). */
    @JvmStatic
    fun shown(): List<String> = types.filter { val v = victories[it]; v != null && (!v.secret || !v.secretLocked()) }

    /** `shipVictoryFilters` without the secret ones nothing achieved yet. */
    @JvmStatic
    fun shownFilters(): List<String> = shipFilters.filter { val v = victories[it]; v != null && (!v.secret || !v.secretLocked()) }

    /** The victory icon's file name (a locked secret ending uses its `secretIcon` when it has one). */
    @JvmStatic
    fun iconName(type: String): String {
        val v = victories[type] ?: return ""
        return if (v.secretLocked() && v.secretIcon.isNotEmpty()) v.secretIcon else v.icon
    }

    /** The name the hangar's filter tooltip shows (`secretName` while locked and given). */
    @JvmStatic
    fun filterName(type: String, text: (GameText) -> String): String {
        val v = victories[type] ?: return ""
        val sn = v.secretName
        return if (v.secretLocked() && sn != null && sn.hsData.isNotEmpty()) text(sn) else text(v.name)
    }

    /** `ToggleFilter` from ShipSelect::MouseClick: at most three filters, the oldest dropped; true when switched on. */
    @JvmStatic
    fun toggleFilter(type: String): Boolean {
        val enabled = ArrayList(shownFilters())
        val on: Boolean
        if (enabled.remove(type)) {
            on = false
        } else {
            if (enabled.size >= 3) enabled.removeAt(0)
            enabled += type
            on = true
        }
        shipFilters.clear(); shipFilters += enabled
        return on
    }

    /**
     * One victory achievement icon (`CAchievement` of SetupVictoryAchievement): the plain `S_V` trophy of a ship when
     * [type] is null, else the icon of that ending. [difficulty] is -1 when locked; [layouts] the per-layout values
     * (A, B, C) of a multi-layout ship; [multiLayout] is `gap_ex_custom & 1` (the tooltip lists the layouts).
     */
    class VictoryView(
        @JvmField val type: String?,
        @JvmField val ship: String,
        @JvmField val name: String,
        @JvmField val description: String,
        @JvmField val icon: String,
        @JvmField val iconLocked: String,
        @JvmField val unlocked: Boolean,
        @JvmField val difficulty: Int,
        @JvmField val layouts: IntArray,
        @JvmField val multiLayout: Boolean,
    )

    /**
     * `GetVictoryAchievement(shipName)` of CustomShipUnlocks ([type] null) or of a CustomVictoryAchievement. [shipClass]
     * resolves a blueprint name to its class text (`bp->shipClass`), for the `\1` of name and description.
     */
    @JvmStatic
    fun achievement(type: String?, shipName: String, shipClass: (String) -> String, text: (GameText) -> String): VictoryView {
        val v = if (type != null) victories[type] else null
        val cls = shipClass(shipName)
        val locked = v?.secretLocked() ?: false
        val nameText = if (v == null) text(GameText.localised("victory_ach_title"))
        else if (locked && v.secretName?.hsData?.isNotEmpty() == true) text(v.secretName!!) else text(v.name)
        val descText = if (v == null) text(GameText.localised("victory_ach"))
        else if (locked && v.secretDescription?.hsData?.isNotEmpty() == true) text(v.secretDescription!!) else text(v.description)
        val icon: String
        val iconLocked: String
        if (v == null) {
            icon = "achievements/S_V_on.png"; iconLocked = "achievements/S_V_off.png"
        } else {
            val n = if (locked && v.secretIcon.isNotEmpty()) v.secretIcon else v.icon
            icon = "achievements/${n}_on.png"; iconLocked = "achievements/${n}_off.png"
        }
        fun check(name: String): Int = if (type == null) HsShipUnlocks.checkCustomShipVictory(name) else HsShipUnlocks.checkCustomShipVictory(name, type)
        val layouts = intArrayOf(-1, -1, -1)
        var difficulty = -1
        var unlocked: Boolean
        var multi = false
        val button = HsHangar.button(shipName)?.takeIf { shipName == it.name || HsHangar.variantOf(shipName)?.first === it }
        if (button != null) {
            if (button.splitVictoryAchievement) {
                difficulty = check(shipName)
                unlocked = difficulty > -1
            } else {
                layouts[0] = check(button.name); layouts[1] = check(button.name + "_2"); layouts[2] = check(button.name + "_3")
                unlocked = layouts.any { it > -1 }
                if (listOf(0, 1, 2).count { button.variantExists(it) } > 1) multi = true
                else difficulty = layouts.max()
            }
        } else {
            layouts[0] = check(shipName); layouts[1] = check(shipName + "_2"); layouts[2] = check(shipName + "_3")
            unlocked = layouts.any { it > -1 }
            multi = true
        }
        return VictoryView(type, shipName, nameText.replace("\\1", cls), descText.replace("\\1", cls), icon, iconLocked, unlocked, difficulty, layouts, multi)
    }
}
