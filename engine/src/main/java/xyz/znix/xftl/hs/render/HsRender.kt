package xyz.znix.xftl.hs.render

import xyz.znix.xftl.hs.HsCapability

/** The render workstream's Hyperspace features (taken off the capability report; docs/HS_RENDER.md). */
object HsRender {
    @JvmStatic
    fun registerImplemented() {
        HsCapability.implement(
            // hyperspace.xml
            "titleScreen", "splashImages", "splashImage", "hullNumbers", "playerText", "enemyText", "bossText",
            "hullBars", "playerHpColorFix", "colors", "textButtons", "systemDetails",
            // layouts and events
            *HsThrusters.capabilityNames(), *HsEventButtons.capabilityNames(), *HsBackgroundObjects.capabilityNames(), *HsCredits.capabilityNames(), "inactiveTooltip", "roomAnim", "tileAnim", "wallAnim", "shipIcons", "shipIcon", "normalPos", "bossPos",
        )
    }
}
