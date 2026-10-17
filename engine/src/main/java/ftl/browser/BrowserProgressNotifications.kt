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
            // The native tracker constructor initializes a three-second popup.
            remaining = if (current != null) 3f else 0f
            current?.takeIf { !it.silent }?.let {
                main.gameContent.sounds.getSampleOrWarn(if (it.ship != null) "unlock" else "achievement")?.play()
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
        content.getImg("img/achievements/popup_box.png").draw(970f, 0f)
        val name: String
        val heading: String
        val icon: xyz.znix.xftl.rendering.Image
        var framed = true
        val achievement = notice.achievement?.let { content.achievements[it] }
        val shipId = notice.ship ?: notice.questShip
        if (achievement != null) {
            icon = content.getImg(achievement.img)
            heading = content.translator["ach_unlocked"]
            name = content.translator[achievement.name]
        } else if (shipId != null) {
            val blueprint = content.blueprintManager.blueprints[shipId] as? xyz.znix.xftl.LazyShipBlueprint
            val className = blueprint?.shipClass?.let { content.translator[it] } ?: shipId
            if (notice.questShip != null) {
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
                val base = content.blueprintManager.blueprints[baseId] as? xyz.znix.xftl.LazyShipBlueprint
                icon = content.getImg("img/achievements/${base?.img ?: blueprint?.img}_$variant.png")
                heading = content.translator[if (variant != 1) "layout_unlocked" else "ship_unlocked"]
                name = className
                framed = false
            }
        } else return

        val left = 1024f - icon.width / 2f
        val top = 51f - icon.width / 2f
        icon.draw(left, top)
        if (framed && icon.width != 32) {
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
}
