package xyz.znix.xftl.hangar

import xyz.znix.xftl.game.TouchMode

/**
 * The hangar has two layouts: the desktop's on the 1280×720 frame, and the touch layout's on a 1024×768 block, which
 * SelectShipState translates to the middle of the canvas. Groups of controls that keep their shape are moved by these
 * offsets from their desktop positions; all are zero on the desktop.
 */
object HangarLayout {
    val touch get() = TouchMode.enabled

    fun pick(desktop: Int, touch: Int) = if (this.touch) touch else desktop

    /** The achievements box and everything in it. */
    val achievementDx get() = pick(0, 1)
    val achievementDy get() = pick(0, 40)

    /** The START and difficulty buttons with their frame, and the Hyperspace seed field in it. */
    val startDx get() = pick(0, -224)
    val startDy get() = pick(0, 428)

    /** The system strip under the ship, and the Hyperspace reactor box beside it. */
    val systemDy get() = pick(0, 46)
}
