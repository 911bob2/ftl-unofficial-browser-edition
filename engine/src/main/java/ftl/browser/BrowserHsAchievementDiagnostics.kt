package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.save.HsSave
import xyz.znix.xftl.hs.ship.HsAchievements
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Hyperspace custom achievements (`hsShipsProbe("ach <name> <variable> <amount> <variableAchievement>")`): the parsed
 * definitions and pages (tests/hs-ships.mjs compares them with its own reading), an unlock that survives a profile
 * save/load and not a blank profile, a variable achievement outside a run (easy), and the statistics screen's
 * achievement tab turned to the first custom page and drawn. Restores the profile.
 */
object BrowserHsAchievementDiagnostics {
    private val q = BrowserDebug::quote

    private fun blankProfile(): org.jdom2.Document {
        val doc = xyz.znix.xftl.game.SaveProfile.createBlank().snapshot()
        doc.rootElement.getChild(HsSave.ELEMENT)?.let { hs ->
            hs.getChildren("data").filter { it.getAttributeValue("key") == HsAchievements.SAVE_KEY }.forEach { hs.removeContent(it) }
        }
        return doc
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, name: String, variable: String, amount: Int, varAch: String): String {
        check(HS.active) { "Hyperspace is not active" }
        val profileXml = BrowserXml.write(main.profile.snapshot())
        try {
            main.profile.restore(blankProfile())
            val defs = HsAchievements.customAchievements
            val pages = HsAchievements.pages.joinToString(",") { p ->
                "[" + p.sections.joinToString(",") { s -> "${s.achievements.size}" } + "]"
            }
            val before = HsAchievements.getAchievementStatus(name)
            HsAchievements.setAchievement(name, true, 1)
            val after = HsAchievements.getAchievementStatus(name)
            val saved = BrowserXml.write(main.profile.snapshot())
            main.profile.restore(blankProfile())
            val wiped = HsAchievements.getAchievementStatus(name)
            main.profile.restore(BrowserXml.parse(saved))
            val reloaded = HsAchievements.getAchievementStatus(name)
            HsAchievements.updateVariableAchievements(variable, amount - 1, false)
            val varBefore = HsAchievements.getAchievementStatus(varAch)
            HsAchievements.updateVariableAchievements(variable, amount, false)
            val varAfter = HsAchievements.getAchievementStatus(varAch)

            // The popup: a fresh unlock with popups queues one notification, drawn like the original's CAchievement.
            HsAchievements.bindProfile(main.profile)
            main.profile.clearNotifications()
            HsAchievements.removeAchievement(name)
            HsAchievements.setAchievement(name, false, 1)
            val popups = main.profile.pendingNotificationCount
            val notices = BrowserProgressNotifications(main)
            notices.update(0.1f)
            val shown = notices.active
            notices.render(graphics)
            main.profile.clearNotifications()

            // The statistics screen: achievements tab, right arrow to the first custom page, drawn.
            val stats = BrowserStatisticsState(main, null)
            stats.render(container, graphics)
            stats.mousePressed(Input.MOUSE_LEFT_BUTTON, 900, 60)
            stats.render(container, graphics)
            stats.mousePressed(Input.MOUSE_LEFT_BUTTON, 1200, 100)
            stats.update(container, 0f)
            stats.render(container, graphics)
            val page = stats.diagnosticAchievementPage
            stats.shutdown()
            return "{\"count\":${defs.size},\"pages\":[$pages],\"unlock\":{\"before\":$before,\"after\":$after,\"wiped\":$wiped," +
                "\"reloaded\":$reloaded,\"saved\":${saved.contains(HsAchievements.SAVE_KEY)}},\"variable\":[$varBefore,$varAfter],\"popups\":$popups,\"popupShown\":$shown," +
                "\"tab\":${q(stats.diagnosticTab)},\"page\":$page,\"names\":[${defs.keys.sorted().joinToString(",") { q(it) }}]}"
        } finally {
            main.profile.restore(BrowserXml.parse(profileXml))
        }
    }
}
