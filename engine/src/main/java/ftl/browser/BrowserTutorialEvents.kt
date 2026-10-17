package ftl.browser

import xyz.znix.xftl.game.InGameState

/** Native tutorial world-loop behavior. Owned by the isolated session, so
 * pending and consumed events survive replacement combat states. */
class BrowserTutorialEvents(private val adapter: BrowserTutorialAdapter) {
    var pendingEventName: String? = null
        private set
    var missileDelivered = false
        private set
    private var missileQueued = false

    private fun requireCurrent(game: InGameState) {
        val main = game.mainGame
        check(main != null && main.isTutorialRunning && main.tutorialAdapter === adapter &&
            main.currentState === game) { "Tutorial events require the current isolated simulation" }
    }

    fun enter(game: InGameState) {
        requireCurrent(game)
        if (!adapter.controller.completed && adapter.controller.step.id == "COMBAT_4" && !missileQueued) {
            // AdvanceState 0x00415720 sets +1c4 and clears world pause flags.
            game.unpauseTutorial()
            pendingEventName = "TUTORIAL_MISSILE"
            missileQueued = true
            BrowserDebug.event("tutorial", "event.queued", "event", pendingEventName!!)
        }
    }

    fun update(game: InGameState) {
        requireCurrent(game)
        if (adapter.controller.completed) return
        // OnLoop 0x004161d0 adds ten whenever missile count is below one,
        // independently of the current lesson and player pause.
        if (game.player.missilesCount < 1) {
            game.player.missilesCount += 10
            BrowserDebug.event("tutorial", "missiles.replenished", "count", game.player.missilesCount.toString())
        }
        // Defer world event delivery behind any existing window/console, so a
        // diagnostic overlay or reward dialogue cannot be silently replaced.
        if (game.isDebugConsoleVisible || game.shipUI.isWindowOpen) return
        val event = pendingEventName ?: return
        // Native GetEvent 0x00410a00 consumes the pending flag before processing.
        pendingEventName = null
        missileDelivered = true
        game.deliverTutorialEvent(event)
        BrowserDebug.event("tutorial", "event.delivered", "event", event)
    }
}
