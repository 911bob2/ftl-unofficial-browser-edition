package xyz.znix.xftl.hs.render

import xyz.znix.xftl.hs.HS

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomUpgrades.cpp / CustomUpgrades.h
// (SystemNoPurchaseThreshold, UpgradeBoxSet page sizes, the ship rename input). This engine is AGPL-3.0-or-later.

/** The Hyperspace parts of the upgrade screen that do not need the window's own state. */
object HsUpgrades {
    /** Main-system boxes per page (`UpgradeBoxSet<8>`) and subsystem boxes per page (`UpgradeBoxSet<4>`). */
    const val SYSTEMS_PER_PAGE = 8
    const val SUBSYSTEMS_PER_PAGE = 4

    /** The ship name field of the rename input (`TextInput(16, ...)`). */
    const val NAME_LENGTH = 16

    /** `SystemNoPurchaseThreshold::to_string`: a cost at or above the threshold shows the replacement text. */
    @JvmStatic
    fun priceText(cost: Int): String {
        if (!HS.active) return cost.toString()
        val c = HS.cfg()
        if (!c.systemNoPurchaseThreshold || cost < c.systemNoPurchaseThresholdValue) return cost.toString()
        return c.systemNoPurchaseThresholdReplace
    }

    /** Number of pages for [total] boxes (HS adds a page when the previous one is full). */
    @JvmStatic
    fun pages(total: Int, perPage: Int): Int = maxOf(1, (total + perPage - 1) / perPage)
}
