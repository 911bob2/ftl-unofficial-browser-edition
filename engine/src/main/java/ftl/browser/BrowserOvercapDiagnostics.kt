package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.TouchMode

/**
 * tests/overcap.mjs (`BrowserEngine.overcapProbe`): the open ship window's over-capacity boxes and crew boxes
 * (ShipWindow.diagnosticOverCapacity), laid out for the mouse layout or, with [touch], for the touch layout, after
 * which the window is laid out for the layout it had. Action "page" turns the touch layout's over-capacity pager first.
 * Test-only.
 */
object BrowserOvercapDiagnostics {
    fun probe(main: MainGame, touch: Boolean, action: String?): String {
        val game = main.currentState as? InGameState ?: return "{\"window\":null}"
        val window = game.shipUI.diagnosticOpenShipWindow ?: return "{\"window\":null}"
        val graphics = BrowserEngine.diagnosticGraphics()
        val was = TouchMode.enabled
        try {
            TouchMode.enabled = touch
            window.diagnosticRelayout(graphics)
            if (action == "page") {
                window.diagnosticTurnOverPage()
                window.diagnosticRelayout(graphics)
            }
            return window.diagnosticOverCapacity
        } finally {
            TouchMode.enabled = was
            window.diagnosticRelayout(graphics)
        }
    }
}
