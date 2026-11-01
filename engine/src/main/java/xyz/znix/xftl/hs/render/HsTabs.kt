package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.hs.HS

/**
 * Hyperspace's customTabs (UserInterface/CustomTabbedWindow.cpp, GPL; FTL-Hyperspace 1.23.2): `<customTabs enabled="true">`
 * with `<tab name windowPath hasUndo/>` children adds tabs after Upgrades, Crew and Equipment to the ship window. Each tab
 * is a FocusWindow whose background is `img/<windowPath>.png`; a tab with hasUndo shows an UNDO button. Scripts draw the
 * tab (RenderEvents.TABBED_WINDOW) and learn about it through TABBED_WINDOW_CONFIRM / TABBED_WINDOW_UNDO.
 * [ShipWindow] draws and switches the tabs.
 */
object HsTabs {
    /** CustomTabbedWindow::Tab: [butX] is `xPos` when the tab was parsed (240 + 77 per earlier tab), the button's y is -7. */
    class Tab(val name: String, val windowPath: String?, val hasUndo: Boolean, val butX: Int)

    /** CustomTabbedWindow::hitboxWidth. */
    const val HITBOX_WIDTH = 77

    /** CustomTabbedWindow::xPos at its start. */
    private const val FIRST_X = 240

    @JvmField val tabs = ArrayList<Tab>()

    /** CustomTabbedWindow::enabled: set by `<customTabs enabled="true">`. */
    @JvmStatic
    val enabled: Boolean get() = HS.active && HS.cfg().customTabs

    /** The tab number of custom tab [index] in the window (the three vanilla tabs come first). */
    fun windowIndex(index: Int) = 3 + index

    /** CustomTabbedWindow::ParseWindowNode. */
    @JvmStatic
    fun parse(node: Element) {
        tabs.clear()
        var x = FIRST_X
        for (t in node.getChildren("tab")) {
            val name = t.getAttributeValue("name") ?: ""
            val path = t.getAttributeValue("windowPath")
            // EventsParser::ParseBoolean: "true" (any case) only
            val undo = t.getAttributeValue("hasUndo")?.equals("true", ignoreCase = true) ?: false
            tabs.add(Tab(name, path, undo, x))
            x += HITBOX_WIDTH
        }
    }
}
