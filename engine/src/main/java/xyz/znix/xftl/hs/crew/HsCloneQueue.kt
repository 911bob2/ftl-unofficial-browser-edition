package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.Clonebay

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CloneQueue.cpp
/**
 * Hyperspace's clone queue (`enhancedCloneUI`): with more than three dead crew the queue pages with
 * statusUI/button_crew_down/up over its top box (CloneBox::OnRender, SystemBox::MouseClick), a right press on a
 * shown crew member picks it up and moving the mouse puts it at the hovered place of the queue (SystemBox::MouseMove,
 * MouseRightClick; the release drops it, CApp::OnRButtonUp), and the box is 15 px taller (CloneBox::GetHeightModifier).
 * The state lives on the [Clonebay] (CloneBox_Extend). The mouse wheel does nothing: HS never calls
 * SystemControl::OnScrollWheel.
 *
 * Coordinates: `location` (the SystemBox position) is the system icon's top-left, (x - 19, y - 19) of
 * [Clonebay.drawIconAndPower].
 */
object HsCloneQueue {
    @JvmStatic
    fun enabled(): Boolean = HS.active && HS.config?.enhancedCloneUI == true

    /** CloneBox::GetHeightModifier: +15 with the page buttons. */
    @JvmStatic
    fun extraHeight(bay: Clonebay): Int = if (enabled() && bay.queue.size > 3) 15 else 0

    /** CloneBox::OnRender: the first crew member shown, clamped to the queue; 0 without the option or paging. */
    @JvmStatic
    fun displayShift(bay: Clonebay): Int {
        val size = bay.queue.size
        // displayShift > size - 3 in size_t: no clamp below three crew.
        if (size >= 3 && bay.hsDisplayShift > size - 3) bay.hsDisplayShift = size - 3
        if (!enabled() || size <= 3) {
            bay.hsDisplayShift = 0
            bay.hsPageDown = false; bay.hsPageUp = false
        } else {
            bay.hsPageDown = bay.hsDisplayShift > 0
            bay.hsPageUp = bay.hsDisplayShift < size - 3
        }
        return bay.hsDisplayShift
    }

    /** CrewMember::Clone / the queue losing a member: a carried crew member is dropped. */
    @JvmStatic
    fun removed(bay: Clonebay, crew: Any) {
        if (bay.hsSelectedCrew === crew) bay.hsSelectedCrew = null
    }

    /** CApp::OnRButtonUp -> SystemControl::RButtonUp: every box drops what it carries. */
    @JvmStatic
    fun rButtonUp(ship: Ship) {
        val bay = ship.clonebay ?: return
        bay.hsSelectedCrew = null
    }

    /** The page buttons' places: pageDown at (x - 1, top), pageUp at (x + 13, top), top = y - 19 - 8 * max(power, 1) - 77. */
    private fun pageY(bay: Clonebay, iconY: Int) = iconY - 19 - 8 * maxOf(bay.energyLevels, 1) - 77

    /** The page buttons over the top box (CloneBox::OnRender), only while they apply. */
    @JvmStatic
    fun renderButtons(game: xyz.znix.xftl.game.InGameState, bay: Clonebay, iconX: Int, iconY: Int) {
        if (!enabled() || bay.queue.size <= 3) return
        val y = pageY(bay, iconY)
        if (bay.hsPageDown)
            game.getImg(if (bay.hsPageDownHover) "img/statusUI/button_crew_down_select2.png" else "img/statusUI/button_crew_down_on.png").draw(iconX - 1, y)
        if (bay.hsPageUp)
            game.getImg(if (bay.hsPageUpHover) "img/statusUI/button_crew_up_select2.png" else "img/statusUI/button_crew_up_on.png").draw(iconX + 13, y)
    }

    /** The carried crew member at the mouse: boxSolo at (mouse - 10), the icon 27, 21 into it. */
    @JvmStatic
    fun renderCarried(bay: Clonebay, soloBox: xyz.znix.xftl.rendering.Image) {
        val crew = bay.hsSelectedCrew ?: return
        val mx = bay.hsMouseX
        val my = bay.hsMouseY
        soloBox.draw(mx - 10, my - 10)
        // Our queue draws portraits at (box + 9, box + 4) for the solo box, the same spot as HS's icon.
        crew.drawPortrait(mx - 10 + 9, my - 10 + 4, false)
    }

    /** The extra system-bar button that gets the box's mouse moves and clicks (made by Clonebay.makeExtraButtons). */
    class QueueButton(game: xyz.znix.xftl.game.InGameState, private val bay: Clonebay, powerPos: IPoint) :
        Button(game, powerPos - ConstPoint(19, 19), ConstPoint(0, 0)) {
        private val iconX = powerPos.x
        private val iconY = powerPos.y
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE

        override val makesHoverNoise: Boolean get() = false

        override fun draw(g: Graphics) {}

        override fun contains(x: Int, y: Int): Boolean = false

        override fun update(x: Int, y: Int, blockHover: Boolean) {
            super.update(x, y, blockHover)
            if (x == lastX && y == lastY) return
            lastX = x; lastY = y
            bay.hsMouseX = x; bay.hsMouseY = y
            if (!enabled()) return
            mouseMove(x, y)
        }

        /** SystemBox::MouseMove (CloneQueue.cpp). */
        private fun mouseMove(x: Int, y: Int) {
            val queue = bay.queue
            val left = iconX - 19 + 15
            val bottom = iconY - 19 - (maxOf(bay.energyLevels, 1) - 1) * 8
            val right = left + 34
            val crewToRender = minOf(queue.size, 3)
            val shift = bay.hsDisplayShift

            val boxIdx = (bottom - y + 12) / 23
            val newCrewIdx = when {
                boxIdx < 0 -> shift
                boxIdx > crewToRender -> shift + crewToRender - 1
                else -> shift + boxIdx
            }
            val lastCloning = queue.firstOrNull()

            val selected = bay.hsSelectedCrew
            if (selected != null && selected in queue) {
                // iDeathNumber: the queue's order, the carried one at newCrewIdx and everyone from there on one later.
                val number = HashMap<Any, Int>()
                for ((i, c) in queue.withIndex()) number[c] = i
                number[selected] = newCrewIdx
                for (i in newCrewIdx until queue.size) if (queue[i] !== selected) number[queue[i]] = i + 1
                val sorted = queue.sortedBy { number[it] ?: 0 }
                queue.clear(); queue.addAll(sorted)
            }
            // A different crew member at the front starts its cloning over.
            if (lastCloning !== queue.firstOrNull()) bay.cloneProgress = 0f

            bay.hsHoverCrew = if (x >= left && x <= right && y <= bottom) {
                val offset = (bottom - y) / 23
                when {
                    offset >= crewToRender -> -1
                    offset == 2 && shift + 3 < queue.size -> -1 // the "+N" box
                    else -> shift + offset
                }
            } else -1

            val py = pageY(bay, iconY)
            bay.hsPageDownHover = bay.hsPageDown && x >= iconX - 1 && x < iconX - 1 + 12 && y >= py && y < py + 14
            bay.hsPageUpHover = bay.hsPageUp && x >= iconX + 13 && x < iconX + 13 + 12 && y >= py && y < py + 14
        }

        override fun mouseDown(button: Int, x: Int, y: Int): Boolean {
            if (!enabled()) return false
            if (button == Input.MOUSE_LEFT_BUTTON) {
                // SystemBox::MouseClick: the page buttons move the shown part by one.
                var used = false
                if (bay.hsPageDownHover) { bay.hsDisplayShift -= 1; used = true }
                if (bay.hsPageUpHover) { bay.hsDisplayShift += 1; used = true }
                if (used) { bay.hsPageDownHover = false; bay.hsPageUpHover = false; displayShift(bay) }
                return used
            }
            if (button == Input.MOUSE_RIGHT_BUTTON) {
                // SystemBox::MouseRightClick: pick up the hovered crew member.
                val hover = bay.hsHoverCrew
                if (bay.hsSelectedCrew == null && hover != -1 && hover < bay.queue.size) {
                    bay.hsSelectedCrew = bay.queue[hover]
                    return true
                }
            }
            return false
        }

        override fun click(button: Int) {}
    }
}
