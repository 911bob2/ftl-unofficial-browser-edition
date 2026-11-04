package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.max
import xyz.znix.xftl.rendering.NativeFonts

/** Transient progression feedback; the profile itself owns durable earning and eligibility. */
class BrowserProgressNotifications(private val main: MainGame) {
    private var current: SaveProfile.ProgressNotification? = null
    private var remaining = 0f
    val active get() = current != null

    fun update(delta: Float) {
        if (main.isTutorialRunning) return
        if (!main.profile.achievementPopups) {
            current = null
            remaining = 0f
            main.profile.clearNotifications()
            return
        }
        remaining -= max(0f, delta)
        if (remaining <= 0f) {
            current = main.profile.pollNotification()
            while (current?.let(::shows) == false) current = main.profile.pollNotification()
            // The native tracker constructor initializes a three-second popup.
            remaining = if (current != null) 3f else 0f
            current?.takeIf { !it.silent }?.let {
                // Hyperspace: a custom achievement's <sound> replaces the popup sound (AchievementTracker::OnLoop hook).
                val custom = it.hsAchievement?.let { n -> xyz.znix.xftl.hs.ship.HsAchievements.customAchievements[n]?.sound }?.takeIf { s -> s.isNotEmpty() }
                main.gameContent.sounds.getSampleOrWarn(custom ?: if (it.ship != null) "unlock" else "achievement")?.play()
            }
        }
    }

    /**
     * AchievementTracker::OnRender (0x00430990): the popup box at (970, 0); the icon centred on (1024, 51), an
     * achievement's with its 2 px frame (CAchievement::OnRender 0x0042b7d0, a 64 px outline tinted 0.784); the
     * heading - the achievement's own (Ship Unlocked!) or ach_unlocked - in font 11 at (1075, 20); the name in
     * font 13 wrapped at 195 from y 41, or 50 when it fits on one line.
     */
    fun render(g: Graphics) {
        if (main.isTutorialRunning) return
        val notice = current ?: return
        val content = main.gameContent
        val name: String
        val heading: String
        val icon: xyz.znix.xftl.rendering.Image?
        var framed = true
        val achievement = notice.achievement?.let { content.achievements[it] }
        val shipId = notice.ship ?: notice.questShip
        val custom = notice.hsAchievement?.let { xyz.znix.xftl.hs.ship.HsAchievements.customAchievements[it] }
        val translator = content.translator
        val victory = notice.hsVictoryShip?.let { ship ->
            xyz.znix.xftl.hs.ship.HsVictories.achievement(notice.hsVictoryType, ship, { n ->
                shipBlueprint(content, n)?.shipClass?.let { translator[it] } ?: n
            }, { it.get(translator) })
        }
        var victoryLayouts = emptyList<Boolean>()
        if (notice.shipVictory != null) {
            icon = content.getImg("img/achievements/S_V_on.png")
            heading = translator["ach_unlocked"]
            val blueprint = shipBlueprint(content, notice.shipVictory)
            val className = blueprint?.shipClass?.let { translator[it] } ?: notice.shipVictory
            name = translator["victory_ach_title"].replace("\\1", className)
            victoryLayouts = notice.shipVictoryLayouts
        } else if (victory != null) {
            icon = content.getImg("img/" + victory.icon)
            heading = translator["ach_unlocked"]
            name = victory.name
        } else if (custom != null) {
            // A custom achievement is a CAchievement: its icon framed, ach_unlocked, its (unlocked) name.
            val path = "img/" + custom.icon
            icon = if (custom.icon.isNotEmpty() && content.datafile.getOrNull(path) != null) content.getImg(path) else return
            heading = content.translator["ach_unlocked"]
            name = custom.shownName?.let { content.translator[it] } ?: custom.nameId
        } else if (achievement != null) {
            icon = content.getImg(achievement.img)
            heading = content.translator["ach_unlocked"]
            name = content.translator[achievement.name]
        } else if (shipId != null) {
            // By name, the AE files' ships included: a vanilla game knows the Lanius and the C layouts too (the original
            // builds a popup for every layout of its ten ship types at start-up, whatever the mode).
            val blueprint = shipBlueprint(content, shipId)
            val className = blueprint?.shipClass?.let { content.translator[it] } ?: shipId
            if (notice.ship != null && xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.ship.HsShipUnlocks.customShipHasUnlock(shipId)) {
                // CustomShipUnlocks::CreateUnlockAchievements: the layout file without _2/_3 + _1, the class and the slot.
                val layout = (blueprint?.layout ?: shipId).replaceFirst("_2", "").replaceFirst("_3", "")
                icon = iconOrNull(content, "img/achievements/${layout}_1.png")
                heading = content.translator["ship_unlocked"]
                val slot = if (shipId.endsWith("_2")) "type_b" else if (shipId.endsWith("_3")) "type_c" else "type_a"
                name = className + "\n" + content.translator[slot]
                framed = false
            } else if (notice.questShip != null) {
                icon = content.getImg("img/achievements/S_Q_on.png")
                heading = content.translator["ach_unlocked"]
                name = content.translator["quest_ach_title"].replace("\\1", className)
            } else {
                // A ship's achievement shows achievements/<the A layout's image>_<layout number>.png, unframed.
                val variant = when {
                    shipId.endsWith("_2") -> 2
                    shipId.endsWith("_3") -> 3
                    else -> 1
                }
                val baseId = if (variant == 1) shipId else shipId.dropLast(2)
                val img = (shipBlueprint(content, baseId) ?: blueprint)?.img ?: return
                icon = iconOrNull(content, "img/achievements/${img}_$variant.png")
                heading = content.translator[if (variant != 1) "layout_unlocked" else "ship_unlocked"]
                name = className
                framed = false
            }
        } else return

        content.getImg("img/achievements/popup_box.png").draw(970f, 0f)
        val iconWidth = icon?.width ?: 64
        val left = 1024f - iconWidth / 2f
        val top = 51f - iconWidth / 2f
        icon?.draw(left, top)
        // CAchievement::OnRender: the layouts' dots, 24 px down, +1 (or +6 for a two-layout family).
        val dotX = left + if (victoryLayouts.size < 3) 6f else 1f
        victoryLayouts.forEachIndexed { i, won ->
            content.getImg("img/achievements/S_V_dot_" + (if (won) "on" else "off") + ".png").draw(dotX + 10 * i, top + 24f)
        }
        if (framed && icon != null && icon.width != 32) {
            g.colour = Colour(0.7843f, 0.7843f, 0.7843f, 1f)
            g.fillRect(left, top, 64f, 2f)
            g.fillRect(left, top + 62f, 64f, 2f)
            g.fillRect(left, top + 2f, 2f, 60f)
            g.fillRect(left + 62f, top + 2f, 2f, 60f)
        }

        val headingFont = NativeFonts.font(content::getFont, 11)
        val nameFont = NativeFonts.font(content::getFont, 13)
        headingFont.print(1075f, 20f, heading, Colour.white)
        val lines = nameFont.font.wrapString(name, 195)
        val nameTop = if (lines.size * nameFont.lineHeight <= 25) 50f else 41f
        nameFont.print(1075f, nameTop, lines.joinToString("\n"), Colour.white)
    }

    private fun shipBlueprint(content: xyz.znix.xftl.game.InGameState.GameContent, id: String) =
        content.blueprintManager.getOrNull(id) as? xyz.znix.xftl.LazyShipBlueprint

    /** A ship popup's icon, or null when the game files lack it (a mod's ship): the popup then shows its text only. */
    private fun iconOrNull(content: xyz.znix.xftl.game.InGameState.GameContent, path: String): xyz.znix.xftl.rendering.Image? =
        if (content.datafile.getOrNull(xyz.znix.xftl.Languages.localImage(content.datafile, path)) != null) content.getImg(path) else null

    /**
     * ScoreKeeper::UnlockShip (1.6.14 0x448410) takes a ship type index; a name that is no ship has none (GetShipId -1),
     * so nothing is unlocked and no popup or sound comes. Such a popup used to look for img/achievements/null_<n>.png,
     * which stopped the game (a missing image is an error in this loader).
     */
    private fun shows(notice: SaveProfile.ProgressNotification): Boolean {
        val ship = notice.ship ?: return true
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.ship.HsShipUnlocks.customShipHasUnlock(ship)) return true
        val content = main.gameContent
        val base = if (ship.endsWith("_2") || ship.endsWith("_3")) ship.dropLast(2) else ship
        return (shipBlueprint(content, base) ?: shipBlueprint(content, ship)) != null
    }
}
