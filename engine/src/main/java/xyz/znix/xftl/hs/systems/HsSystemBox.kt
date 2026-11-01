// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/SystemBox_Extend.cpp (CONSTRUCT_SYSTEM_BOX),
// lua/InternalEvents.cpp (SYSTEM_BOX_MOUSE_MOVE / _MOUSE_CLICK / _KEY_DOWN) and lua/RenderEvents.cpp (SYSTEM_BOX).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.*
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * The Lua face of one system box of the player's systems bar (FTL's SystemBox): location, pSystem, bPlayerUI and
 * extend. The engine draws the box as a button at [powerPos] (the icon, 19 px in from the box's corner); FTL's
 * SystemControl draws its boxes 382 px down, so [location] is the native value (main systems at y 269, subsystems
 * at y 251) and the events translate to the box's screen corner.
 */
class HsSystemBox private constructor(@JvmField val system: AbstractSystem, @JvmField val playerUI: Boolean = true) : LuaTyped {
    override val luaType: Int get() = A.T_SystemBox

    @JvmField val location = PointValue(0, 0)
    /** Where the bar put this box; [location] moved from here by a script moves the box. */
    @JvmField var baseX = 0
    @JvmField var baseY = 0
    val shiftX: Int get() = location.x - baseX
    val shiftY: Int get() = location.y - baseY
    /** SystemBox::pSystem written by a script: the system the box shows and controls. */
    @JvmField var shown: AbstractSystem? = null
    @JvmField val extend = Extend(this)

    /** The box's top-left corner on screen (bar space). */
    @JvmField var screenX = 0
    @JvmField var screenY = 0

    /** SystemBox_Extend: orig, and xOffset (not used by the 1b05a07 boxes; scripts may store a value). */
    class Extend(@JvmField val orig: HsSystemBox) : LuaTyped {
        override val luaType: Int get() = A.T_SystemBox_Extend
        @JvmField var xOffset = 0
    }

    companion object {
        /** FTL's SystemControl renders its boxes this far down (SystemBox location.y 269 for a box drawn at 651). */
        const val NATIVE_FRAME_Y = 382

        private val boxes = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<AbstractSystem, HsSystemBox>())

        /**
         * The box for [system] at [powerPos], CONSTRUCT_SYSTEM_BOX(box) when it is new. The engine rebuilds its
         * buttons often; one box per system keeps the scripts' tables (HS_MAKE_TABLE) on the same object.
         */
        @JvmStatic
        fun of(system: AbstractSystem, powerPos: IPoint): HsSystemBox? {
            if (!HS.luaActive) return null
            // SystemControl::OnLoop rebuilds every box when ShipManager is set, and clears it:
            // AddSystem sets it (oracle sweep-world-y1: one CONSTRUCT_SYSTEM_BOX round the frame after AddSystem, none
            // after a RemoveSystem, which rebuilds at once)
            val ship = system.room?.ship
            if (ship != null && ship.isPlayerShip && added.remove(ship) != null) rebuild(ship)
            var box = boxes[system]
            val fresh = box == null
            if (box == null) { box = HsSystemBox(system); boxes[system] = box }
            val dx = box.shiftX; val dy = box.shiftY
            box.screenX = powerPos.x - 19; box.screenY = powerPos.y - 19
            box.baseX = box.screenX; box.baseY = box.screenY - NATIVE_FRAME_Y
            box.location.x = box.baseX + dx; box.location.y = box.baseY + dy
            if (fresh) construct(box)
            return box
        }

        /** Ships whose AddSystem ran since their SystemControl loop last looked (ShipManager). */
        private val added = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.Ship, Boolean>())

        /** ShipManager::AddSystem: the system list changed; the player's bar rebuilds on its next loop. */
        @JvmStatic
        fun systemAdded(ship: xyz.znix.xftl.Ship) { if (HS.luaActive) added[ship] = true }

        private fun construct(box: HsSystemBox) {
            if (!HsLua.hasInt[A.EV_CONSTRUCT_SYSTEM_BOX]) return
            LuaPush.obj(box, A.T_SystemBox)
            LuaJs.fire(A.EV_CONSTRUCT_SYSTEM_BOX, 1, 0)
            LuaJs.pop(1)
        }

        /**
         * SystemControl::CreateSystemBoxes (CustomSystems.cpp) for the player's [ship]: every box is made anew (a new
         * SystemBox, so a new Lua table) and CONSTRUCT_SYSTEM_BOX runs for each, main systems in bar order then the
         * subsystems. A main box sits at (xPos + 36, 269), xPos from 22 stepping by the slots and ShipSystem_Extend::xOffset;
         * a subsystem box keeps the place the bar gave it (static layout: subSystemOffset + staticOffset, y 251).
         */
        @JvmStatic
        fun rebuild(ship: xyz.znix.xftl.Ship) {
            if (!HS.luaActive || !ship.isPlayerShip) return
            // (the AddSystem flag is left alone: an AddSystem and a RemoveSystem in one frame rebuild once more on the loop)
            var xPos = 22
            for (sys in ship.mainSystems) {
                val box = HsSystemBox(sys)
                box.location.x = xPos + 36; box.location.y = 269
                box.baseX = box.location.x; box.baseY = box.location.y
                box.screenX = box.location.x; box.screenY = box.location.y + NATIVE_FRAME_Y
                boxes[sys] = box
                construct(box)
                xPos += HsSysExt.of(sys).xOffset + when (sys) {
                    is xyz.znix.xftl.systems.Weapons -> (ship.weaponSlots ?: 0) * 97
                    is xyz.znix.xftl.systems.Drones -> (ship.droneSlots ?: 0) * 97
                    else -> 0
                }
            }
            for ((sys, x) in subLayout(ship, subSpacing).boxes) {
                val box = HsSystemBox(sys)
                box.location.x = x; box.location.y = 251
                box.baseX = box.location.x; box.baseY = box.location.y
                box.screenX = box.location.x; box.screenY = box.location.y + NATIVE_FRAME_Y
                boxes[sys] = box
                construct(box)
            }
        }

        /** SystemControl::sub_spacing as the bar last drew it (the subsystems label's extra width). */
        @JvmField var subSpacing = 0

        /** The subsystem boxes in CreateSystemBoxes order with their SystemControl x (y 251), and the row's origin. */
        class SubLayout(@JvmField val boxes: List<Pair<AbstractSystem, Int>>, @JvmField val static: Boolean, @JvmField val origin: Int)

        /**
         * CreateSystemBoxes (CustomSystems.cpp) for the subsystems of [ship] with [spacing] (sub_spacing): when every one has
         * a `staticOffset` they sit at subSystemOffset + staticOffset + i * sub_spacing in staticOffset order; otherwise
         * (a subsystem without one, e.g. a custom `subSystem="true"` system) they form a row in `position` order from x
         * 1015, moved left by what the row (the xOffsets, sub_spacing each, minus one) needs beyond the vanilla
         * 177 + 3 * sub_spacing, each next box sub_spacing + xOffset further.
         */
        @JvmStatic
        fun subLayout(ship: xyz.znix.xftl.Ship, spacing: Int): SubLayout {
            subSpacing = spacing
            // the boxes are collected by id (0..15, then temporal, then custom ids); the sorts keep that order for ties
            val subs = ship.subSystems.sortedBy { xyz.znix.xftl.ai.nativeSystemId(it) }
            val static = subs.all { HsSystems.staticOffset(xyz.znix.xftl.ai.nativeSystemId(it)) != null }
            if (static) {
                val sorted = subs.sortedBy { HsSystems.staticOffset(xyz.znix.xftl.ai.nativeSystemId(it)) ?: 0 }
                val origin = HsSystems.subSystemOffset()
                return SubLayout(sorted.mapIndexed { i, s -> s to origin + i * spacing + (HsSystems.staticOffset(xyz.znix.xftl.ai.nativeSystemId(s)) ?: 0) }, true, origin)
            }
            val sorted = subs.sortedBy { HsSystems.position(xyz.znix.xftl.ai.nativeSystemId(it)) }
            val total = sorted.sumOf { HsSysExt.of(it).xOffset } + spacing * sorted.size - 1
            val origin = 1015 - maxOf(0, total - (177 + 3 * spacing))
            var x = origin
            val out = ArrayList<Pair<AbstractSystem, Int>>()
            for (s in sorted) {
                out += s to x
                x += spacing + HsSysExt.of(s).xOffset
            }
            return SubLayout(out, false, origin)
        }

        // Plain references, let go at the run's end. Reading a WeakRef every frame keeps its target alive until the
        // browser's current job ends, so a long job that loads games would hold every game it replaced.
        private var playerShip: xyz.znix.xftl.Ship? = null

        /**
         * CommandGui::LinkShip (SystemControl::CreateSystemBoxes) when a run's player ship comes into play: its boxes are
         * made then, not when the bar is first drawn (oracle sweep-world-y1: no CONSTRUCT_SYSTEM_BOX for a handler
         * registered later until the next AddSystem). Called every frame of the run.
         */
        @JvmStatic
        fun playerFrame(game: xyz.znix.xftl.game.InGameState) {
            if (!HS.luaActive) return
            val player = game.player ?: return
            if (player === playerShip) return
            playerShip = player
            added.remove(player)
            rebuild(player)
        }

        private var enemyShip: xyz.znix.xftl.Ship? = null
        private val enemyBoxes = ArrayList<HsSystemBox>()

        /** The run was left: let go of its ships. */
        @JvmStatic
        fun endRun() { playerShip = null; enemyShip = null; enemyBoxes.clear() }

        /**
         * The target's system boxes (SystemBox with bPlayerUI false, oracle sweep-world-y1): made when a new enemy ship is
         * in play, one per system in vSystemList order at (165 + 30 * i, 515), CONSTRUCT_SYSTEM_BOX for each. Called every
         * frame of the run.
         */
        @JvmStatic
        fun enemyFrame(game: xyz.znix.xftl.game.InGameState) {
            if (!HS.luaActive) return
            val enemy = game.enemy
            if (enemy === enemyShip) return
            enemyShip = enemy
            enemyBoxes.clear()
            if (enemy == null) return
            for ((i, sys) in xyz.znix.xftl.hs.lua.HsShipLuaState.systemList(enemy).withIndex()) {
                val box = HsSystemBox(sys, false)
                box.location.x = 165 + 30 * i; box.location.y = 515
                box.screenX = box.location.x; box.screenY = box.location.y
                enemyBoxes += box
                construct(box)
            }
        }

        /** The box made for [system], if the bar shows one. */
        @JvmStatic
        fun existing(system: AbstractSystem): HsSystemBox? = if (HS.luaActive) boxes[system] else null

        /** SystemBox::MouseMove(x, y): SYSTEM_BOX_MOUSE_MOVE(box, x - location.x, y - location.y); true = pre-empted. */
        @JvmStatic
        fun mouseMove(box: HsSystemBox, x: Int, y: Int): Boolean {
            if (!HsLua.hasInt[A.EV_SYSTEM_BOX_MOUSE_MOVE]) return false
            LuaPush.obj(box, A.T_SystemBox)
            LuaJs.pushinteger((x - box.screenX - box.shiftX).toDouble())
            LuaJs.pushinteger((y - box.screenY - box.shiftY).toDouble())
            val preempt = LuaJs.fireChain(A.EV_SYSTEM_BOX_MOUSE_MOVE, 3, 0)
            LuaJs.pop(3)
            return preempt
        }

        /** SystemBox::MouseClick(shift): SYSTEM_BOX_MOUSE_CLICK(box, shift); true = pre-empted (the click fails). */
        @JvmStatic
        fun mouseClick(box: HsSystemBox, shift: Boolean): Boolean {
            if (!HsLua.hasInt[A.EV_SYSTEM_BOX_MOUSE_CLICK]) return false
            LuaPush.obj(box, A.T_SystemBox)
            LuaJs.pushboolean(shift)
            val preempt = LuaJs.fireChain(A.EV_SYSTEM_BOX_MOUSE_CLICK, 2, 0)
            LuaJs.pop(2)
            return preempt
        }

        /** SystemBox::KeyDown(key, shift) with the engine's (GLFW) key: SYSTEM_BOX_KEY_DOWN(box, SDLKey, shift). */
        @JvmStatic
        fun keyDown(box: HsSystemBox, glfwKey: Int, shift: Boolean): Boolean {
            if (!HsLua.hasInt[A.EV_SYSTEM_BOX_KEY_DOWN]) return false
            LuaPush.obj(box, A.T_SystemBox)
            LuaJs.pushinteger(LuaEvents.sdlKey(glfwKey).toDouble())
            LuaJs.pushboolean(shift)
            val preempt = LuaJs.fireChain(A.EV_SYSTEM_BOX_KEY_DOWN, 3, 0)
            LuaJs.pop(3)
            return preempt
        }

        /**
         * SystemBox::OnRender(ignoreStatus) wrapped in RenderEvents.SYSTEM_BOX(box, ignoreStatus): the callbacks draw
         * in the box's frame (translated to its corner); a negative index from the pre callbacks skips [body].
         */
        @JvmStatic
        fun render(box: HsSystemBox?, g: Graphics, ignoreStatus: Boolean, body: () -> Unit) {
            if (box == null || !HsLua.hasRen[A.RE_SYSTEM_BOX]) { body(); return }
            val prevG = HsLua.graphics
            HsLua.graphics = g
            try {
                LuaPush.obj(box, A.T_SystemBox)
                LuaJs.pushboolean(ignoreStatus)
                g.pushTransform(); g.translate(box.screenX.toFloat(), box.screenY.toFloat())
                val idx = LuaJs.renderPre(A.RE_SYSTEM_BOX, 2)
                LuaGraphics.restore()
                g.popTransform()
                if (idx >= 0) body()
                g.pushTransform(); g.translate(box.screenX.toFloat(), box.screenY.toFloat())
                LuaJs.renderPost(A.RE_SYSTEM_BOX, if (idx < 0) -idx else idx, 2)
                LuaGraphics.restore()
                g.popTransform()
                LuaJs.pop(2)
            } finally {
                HsLua.graphics = prevG
            }
        }

        fun install() {
            LuaReg.obj(A.A_SystemBox_location, ObjGet { (it as HsSystemBox).location })
            LuaReg.obj(A.A_SystemBox_pSystem, ObjGet { val b = it as HsSystemBox; b.shown ?: b.system },
                ObjSet { o, v -> (o as HsSystemBox).shown = v as? AbstractSystem })
            LuaReg.bool(A.A_SystemBox_bPlayerUI, { (it as HsSystemBox).playerUI })
            LuaReg.obj(A.A_SystemBox_extend, ObjGet { (it as HsSystemBox).extend })
            LuaReg.obj(A.A_SystemBox_Extend_orig, ObjGet { (it as Extend).orig })
            LuaReg.num(A.A_SystemBox_Extend_xOffset, NumGet { (it as Extend).xOffset.toDouble() }, NumSet { o, v -> (o as Extend).xOffset = v.toInt() })
        }
    }
}
