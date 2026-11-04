// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1.23.2 (lua/ script API and .i members, src/ files named below),
// rapidxml 1.13 (xml_node mutators) and the oracle rows of tools/hs-oracle/tracer/data/hs_oracle/wp7-ui.lua.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.GameText
import xyz.znix.xftl.hs.events.HsEventsLua
import xyz.znix.xftl.hs.tutorial.HsTutorial
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** A pointer of a type SWIG wraps but the engine has nothing behind (GL_Texture, ShipStatus, ...): members are not available. */
class LuaPtr(override val luaType: Int) : LuaTyped

/** `Hyperspace.App.gui.ftlButton` (the JUMP button) and `Hyperspace.Tutorial.shipInfo` (the ship window). */
object LuaFtlButton : LuaTyped { override val luaType get() = A.T_FTLButton }
object LuaTabbedWindow : LuaTyped { override val luaType get() = A.T_TabbedWindow }

/** A TimerHelper made by `Hyperspace.TimerHelper()`: the native fields as they are, advanced by Update and Done. */
/** A TimerHelper the crew code runs (CrewMember_Extend's timers), as the timer methods see it. */
class LuaHsTimerView(private val t: xyz.znix.xftl.hs.stat.HsTimer) : LuaTimerView() {
    override var maxTime = 0
    override var minTime = 0
    override var currTime: Float get() = t.currTime; set(v) { t.currTime = v }
    override var currGoal: Float get() = t.currGoal; set(v) { t.currGoal = v }
    override var loop = false
    override var running: Boolean get() = t.running; set(v) { t.running = v }
}

class LuaOwnTimer(override var maxTime: Int, override var minTime: Int) : LuaTimerView() {
    override var currTime = 0f
    override var currGoal = 0f
    override var loop = false
    override var running = true
}

/**
 * WP7 slice B Lua classes: rapidxml's tree mutators, TimerHelper, and the members and methods of the GUI classes
 * (MouseControl, CommandGui, CombatControl, ArmamentControl, FocusWindow, TabbedWindow, GenericButton and its
 * subclasses, TutorialManager, WindowFrame, Description, HyperspaceVersion).
 *
 * The native fields the engine has no counterpart for keep their values in a hidden [LuaStruct] per object (the
 * "shadow"), so a script reads what it wrote, as it does from the native field; members the engine does have stay
 * with the adapters that were registered before. The values a field starts with are the oracle's.
 */
object LuaAdaptersWP7Ui {
    @JvmStatic
    fun install() {
        shadows.clear(); descriptions.clear(); version = null
        xmlMutators()
        shadowObjects()
        timers()
        description()
        windowFrame()
        constructors()
        versionObject()
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private val attrIds: HashMap<String, Int> by lazy {
        HashMap<String, Int>().also { m -> for (a in 1..A.ATTR_COUNT) m[A.attrName(a)] = a }
    }
    private fun attr(name: String): Int = attrIds[name] ?: error("no attribute $name")

    private val methodIds: HashMap<String, ArrayList<Int>> by lazy {
        HashMap<String, ArrayList<Int>>().also { m -> for (i in 1..A.METHOD_COUNT) m.getOrPut(A.methodName(i)) { ArrayList() }.add(i) }
    }

    /** Registers [f] for every overload of method [name] (`Class.Method`). */
    private fun method(name: String, f: LuaMethod) {
        for (id in methodIds[name] ?: error("no method $name")) LuaReg.method(id, f)
    }

    /** Registers [f] for the owners [mine] accepts, in front of whatever was registered for [name]. */
    private fun methodFor(name: String, mine: (Any?) -> Boolean, f: LuaMethod) {
        for (id in methodIds[name] ?: error("no method $name")) {
            val prev = LuaReg.methods[id]
            LuaReg.method(id, LuaMethod { n ->
                if (mine(arg.obj(1))) f.call(n)
                else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: $name")).call(n)
            })
        }
    }

    private val immutable = "This variable is immutable"
    private fun missing(a: Int): Nothing = throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))

    private fun typed(o: Any?, type: Int) = o is LuaTyped && o.luaType == type

    // ------------------------------------------------------------------------------------------------- shadow state

    private val shadows = xyz.znix.xftl.hs.lua.HsRunMaps.any(java.util.IdentityHashMap<Any, LuaStruct>())
    private fun shadowOf(o: Any?): LuaStruct? = if (o == null || o is LuaStruct) null else shadows[o]
    private fun shadowAny(o: Any?, type: Int): LuaStruct? = if (o == null || o is LuaStruct) null else shadows.getOrPut(o) { LuaStructs.create(type) }

    private fun set(s: LuaStruct, name: String, v: Any?) { s.values[attr(name)] = v }
    private fun getStruct(s: LuaStruct, name: String): Any? = LuaStructs.member(s, attr(name))
    private fun point(s: LuaStruct, name: String, x: Int, y: Int) { LuaStructs.setFields(getStruct(s, name) as LuaStruct, x.toDouble(), y.toDouble()) }
    private fun rect(s: LuaStruct, name: String, x: Int, y: Int, w: Int, h: Int) {
        LuaStructs.setFields(getStruct(s, name) as LuaStruct, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
    }

    /**
     * Makes the attribute [a] of the owners [shadow] resolves read and write that owner's shadow struct, in front of
     * the adapter registered before (which keeps serving every other owner). A read-only one ([ro]) is SWIG's
     * "This variable is immutable" on a write.
     */
    private fun wrap(a: Int, ro: Boolean, shadow: (Any?) -> LuaStruct?) {
        when (A.attrKind(a)) {
            1, 2 -> {
                val g = LuaReg.numGet[a] ?: missing(a); val s = LuaReg.numSet[a] ?: missing(a)
                LuaReg.numGet[a] = NumGet { o -> g.get(shadow(o) ?: o) }
                LuaReg.numSet[a] = NumSet { o, v -> val sh = shadow(o); if (sh != null && ro) throw LuaError(immutable); s.set(sh ?: o, v) }
            }
            3 -> {
                val g = LuaReg.strGet[a] ?: missing(a); val s = LuaReg.strSet[a] ?: missing(a)
                LuaReg.strGet[a] = StrGet { o -> g.get(shadow(o) ?: o) }
                LuaReg.strSet[a] = StrSet { o, v -> val sh = shadow(o); if (sh != null && ro) throw LuaError(immutable); s.set(sh ?: o, v) }
            }
            else -> {
                val g = LuaReg.objGet[a] ?: missing(a); val s = LuaReg.objSet[a] ?: missing(a)
                LuaReg.objGet[a] = ObjGet { o -> g.get(shadow(o) ?: o) }
                LuaReg.objSet[a] = ObjSet { o, v -> val sh = shadow(o); if (sh != null && ro) throw LuaError(immutable); s.set(sh ?: o, v) }
            }
        }
    }

    private fun wrapNames(names: List<String>, ro: Set<String> = emptySet(), shadow: (Any?) -> LuaStruct? = ::shadowOf) {
        for (n in names) wrap(attr(n), n in ro, shadow)
    }

    private fun wrapType(type: Int, skip: Set<String> = emptySet(), ro: Set<String> = emptySet()) {
        for (a in LuaStructs.attrsOf(type)) {
            val n = A.attrName(a)
            if (n !in skip) wrap(a, n in ro, ::shadowOf)
        }
    }

    // -------------------------------------------------------------------------------------------- GUI classes

    /** MouseControl::tooltipTimer: game seconds the mouse has rested (MouseControl::OnLoop; a write sets it). */
    private var timer = 0f
    private fun tooltipTimer() = timer.toDouble()

    /**
     * The mouse moved (CApp::OnMouseMove): MouseControl::SetPosition marks bMoving when the position
     * changed, which the next MouseControl::OnLoop turns into a restarted timer.
     */
    @JvmStatic
    fun mouseMoved(changed: Boolean) {
        if (changed) shadows[LuaMouse]?.let { set(it, "MouseControl.bMoving", true) }
        LuaAdaptersSweepUi.mouseMoved()
    }

    // ------------------------------------------------------------------------------- MouseControl every frame

    private fun mBool(m: LuaStruct, name: String): Boolean = m.values[attr(name)].let { it == true || (it is Number && it.toDouble() != 0.0) }
    private fun mNum(m: LuaStruct, name: String): Double = (m.values[attr(name)] as? Number)?.toDouble() ?: 0.0
    private fun mStr(m: LuaStruct, name: String): String = m.values[attr(name)] as? String ?: ""
    private fun mPoint(m: LuaStruct, name: String): Pair<Int, Int> = when (val v = getStruct(m, name)) {
        is LuaStruct -> LuaStructs.fields(v).let { Pair(it.getOrElse(0) { 0.0 }.toInt(), it.getOrElse(1) { 0.0 }.toInt()) }
        is PointValue -> Pair(v.x, v.y)
        else -> Pair(0, 0)
    }

    /** MouseControl::Reset. */
    private fun resetMouse(m: LuaStruct) {
        for (n in listOf("valid", "newHover", "bSellingStuff", "bHideMouse", "bForceTooltip")) set(m, "MouseControl.$n", false)
        for (n in listOf("animateDoor", "iTeleporting", "iMindControlling", "aiming_required", "iHacking")) set(m, "MouseControl.$n", 0.0)
        set(m, "MouseControl.tooltip", ""); set(m, "MouseControl.tooltipTitle", "")
        set(m, "MouseControl.overrideTooltipWidth", -1.0)
        setPoint(m, "MouseControl.staticTooltip", -1, -1)
    }

    private fun setPoint(m: LuaStruct, name: String, x: Int, y: Int) {
        val v = m.values[attr(name)]
        if (v is PointValue) { v.x = x; v.y = y } else point(m, name, x, y)
    }

    /** MouseControl::OnLoop: the rest timer grows while the mouse stays; a move restarts it unless a shown tooltip holds it. */
    private fun loopMouse(m: LuaStruct, seconds: Float) {
        if (!mBool(m, "MouseControl.bMoving")) timer += seconds
        else if (!(timer >= 0.2f && mStr(m, "MouseControl.tooltip").isNotEmpty())) timer = 0f
        set(m, "MouseControl.bMoving", false)
    }

    /** CApp::OnLoop: MouseControl::Reset, then MouseControl::OnLoop, before the frame's game loop and scripts. */
    @JvmStatic
    fun frame(seconds: Float) {
        val m = shadows[LuaMouse] ?: return
        resetMouse(m)
        loopMouse(m, seconds)
    }

    /** MouseControl::SetTooltip / LoadTooltip: a text replaces the tooltip; the one shown last frame forces it. */
    private fun setTooltip(m: LuaStruct, text: String) {
        if (text.isNotEmpty()) set(m, "MouseControl.tooltip", text)
        if (text == mStr(m, "MouseControl.lastTooltipText")) set(m, "MouseControl.bForceTooltip", true)
    }

    private fun tooltipFont(name: String) = HsLua.game?.gameContent?.getFont(name)

    /**
     * MouseControl::RenderTooltip as RedesignedTooltips.cpp rewrites it: the tooltip and title at [x], [y], wrapped at
     * overrideTooltipWidth (275, 350 with a title); not [staticPos]: 10 px lower while hacking or mind controlling,
     * kept on screen against the mouse.
     */
    private fun drawTooltip(m: LuaStruct, g: xyz.znix.xftl.rendering.Graphics, x: Int, y: Int, staticPos: Boolean) {
        val body = tooltipFont("JustinFont11Bold") ?: return
        val title = mStr(m, "MouseControl.tooltipTitle").ifEmpty { null }
        val width = mNum(m, "MouseControl.overrideTooltipWidth").toInt().takeIf { it >= 1 }
        val dy = if (!staticPos && (mNum(m, "MouseControl.iHacking") != 0.0 || mNum(m, "MouseControl.iMindControlling") != 0.0)) 10 else 0
        LuaGraphics.flushQuads()
        xyz.znix.xftl.rendering.NativeTooltip.draw(g, if (title == null) null else tooltipFont("JustinFont12Bold"), body,
            LuaEvents.mouseX, LuaEvents.mouseY, title, mStr(m, "MouseControl.tooltip"), width, x, y + dy, staticPos)
    }

    /**
     * MouseControl::OnRender's tooltip (AdditionalWeaponSlots.cpp's rewrite): after the mouse rested 0.15 s,
     * or at once with bForceTooltip, the frame's tooltip hangs 22 px under the mouse or at the queued static point,
     * and becomes lastTooltipText. Under bHideMouse nothing is drawn at all. True when a script's tooltip (or the
     * hidden mouse) takes the place of the engine's own tooltip this frame.
     */
    @JvmStatic
    fun renderTooltip(g: xyz.znix.xftl.rendering.Graphics): Boolean {
        val m = shadows[LuaMouse] ?: return false
        if (mBool(m, "MouseControl.bHideMouse")) return true
        val tip = mStr(m, "MouseControl.tooltip")
        if ((!mBool(m, "MouseControl.bForceTooltip") && timer <= 0.15f) || tip.isEmpty()) {
            set(m, "MouseControl.lastTooltipText", ""); return false
        }
        set(m, "MouseControl.lastTooltipText", tip)
        val (sx, sy) = mPoint(m, "MouseControl.staticTooltip")
        if (sx != -1 && sy != -1) drawTooltip(m, g, sx, sy, false) else drawTooltip(m, g, LuaEvents.mouseX, LuaEvents.mouseY + 22, false)
        return true
    }

    /**
     * The pointer MouseControl::OnRender picks from the fields a script set this frame (AdditionalWeaponSlots.cpp):
     * mind control, teleport, hacking, then an armed weapon's crosshair, else validPointer or invalidPointer.
     * [engineValid] is whether the engine's own pointer is the valid one. Null: nothing of a script's to show.
     */
    private fun pointerPath(m: LuaStruct, engineValid: Boolean, onlyScript: Boolean): String? {
        val valid = engineValid || mBool(m, "MouseControl.valid")
        val aim = mNum(m, "MouseControl.aiming_required").toLong()
        val tele = mNum(m, "MouseControl.iTeleporting").toInt()
        fun tex(name: String): String? = (m.values[attr(name)] as? LuaTexture)?.path?.let { "img/$it" }
        return when {
            mNum(m, "MouseControl.iMindControlling") != 0.0 -> if (valid) "img/mouse/mouse_mind.png" else "img/mouse/mouse_mind_valid.png"
            tele == 2 -> if (valid) "img/mouse/mouse_teleport_in1.png" else "img/mouse/mouse_teleport_in2.png"
            tele != 0 -> if (valid) "img/mouse/mouse_teleport_out1.png" else "img/mouse/mouse_teleport_out2.png"
            mNum(m, "MouseControl.iHacking") != 0.0 -> if (valid) "img/mouse/mouse_hacking.png" else "img/mouse/mouse_hacking_valid.png"
            mBool(m, "MouseControl.bSellingStuff") -> tex("MouseControl.selling") ?: if (onlyScript) null else "img/mouse/pointerValid.png"
            aim != 0L -> {
                val cross = if (valid) "img/mouse/mouse_crosshairs.png" else if (aim < 0) "img/mouse/mouse_crosshairs_valid2.png" else "img/mouse/mouse_crosshairs_valid.png"
                val n = when (aim) { 2147483647L -> "2_fallback"; -2147483647L -> "3_fallback"; else -> if (aim < 0) "3_${-aim}" else "2_$aim" }
                "img/mouse/mouse_crosshairs$n.png,$cross"
            }
            else -> tex(if (valid) "MouseControl.validPointer" else "MouseControl.invalidPointer")
                ?: if (onlyScript && !mBool(m, "MouseControl.valid")) null else if (valid) "img/mouse/pointerValid.png" else "img/mouse/pointerInvalid.png"
        }
    }

    /**
     * The cursor for the frame: the engine's [engine] cursor, or what a script's MouseControl fields of this frame make
     * MouseControl::OnRender draw: nothing under bHideMouse, a cursor its flags pick, or its own validPointer /
     * invalidPointer texture in place of the engine's plain pointer.
     */
    @JvmStatic
    fun cursor(st: xyz.znix.xftl.game.InGameState, engine: xyz.znix.xftl.rendering.Cursor?): xyz.znix.xftl.rendering.Cursor? {
        val m = shadows[LuaMouse] ?: return engine
        if (mBool(m, "MouseControl.bHideMouse")) return st.getHiddenCursor()
        val engineValid = engine === st.getCursor("img/mouse/pointerValid.png")
        val enginePlain = engineValid || engine === st.getCursor("img/mouse/pointerInvalid.png")
        if (!enginePlain) {
            // the engine's own special pointer (targeting, doors, teleport) stands unless a script's flag outranks it
            val flagged = mNum(m, "MouseControl.iMindControlling") != 0.0 || mNum(m, "MouseControl.iTeleporting") != 0.0 ||
                mNum(m, "MouseControl.iHacking") != 0.0
            if (!flagged) return engine
        }
        val path = pointerPath(m, engineValid, true) ?: return engine
        return try { st.getCursor(path) } catch (e: Exception) { engine }
    }

    /** Lua MouseControl:OnRender: the pointer image and the tooltip, drawn where the script's transform puts them. */
    private fun luaOnRender(m: LuaStruct) {
        val g = HsLua.graphics ?: return
        if (mBool(m, "MouseControl.bHideMouse")) return
        renderTooltip(g)
        val path = pointerPath(m, false, false) ?: return
        val game = LuaAdapters.inGame() ?: return
        LuaGraphics.flushQuads()
        for (p in path.split(",")) try { game.getImg(p).draw(LuaEvents.mouseX.toFloat(), LuaEvents.mouseY.toFloat(), 32f, 32f) } catch (e: Exception) {}
    }

    private fun isButtonType(t: Int) = t == A.T_Button || t == A.T_FTLButton || t == A.T_TextButton || t == A.T_GenericButton

    private fun shadowObjects() {
        // the owners and the values their fields start with (the oracle's reads on a freshly started game)
        val mouse = LuaStructs.create(A.T_MouseControl)
        shadows[LuaMouse] = mouse
        set(mouse, "MouseControl.overrideTooltipWidth", -1.0)
        val invalid = LuaPtr(A.T_GL_Texture)
        set(mouse, "MouseControl.invalidPointer", invalid); set(mouse, "MouseControl.lastIcon", invalid)
        set(mouse, "MouseControl.validPointer", LuaPtr(A.T_GL_Texture)); set(mouse, "MouseControl.selling", LuaPtr(A.T_GL_Texture))

        val gui = LuaStructs.create(A.T_CommandGui)
        shadows[LuaGui] = gui
        set(gui, "CommandGui.shipStatus", LuaPtr(291)); set(gui, "CommandGui.sysControl", LuaPtr(294)) // the opaque types "ShipStatus *", "SystemControl *"
        set(gui, "CommandGui.choiceBox", LuaPtr(A.T_ChoiceBox)); set(gui, "CommandGui.ftlButton", LuaFtlButton)
        set(gui, "CommandGui.jumpComplete", true); set(gui, "CommandGui.mapId", 3.0)

        val combat = LuaStructs.create(A.T_CombatControl)
        shadows[LuaAdaptersArmament.CombatControlView] = combat; shadows[LuaAdaptersDrones.LuaCombatControl] = combat
        set(combat, "CombatControl.selectedRoom", -1.0); set(combat, "CombatControl.selectedSelfRoom", -1.0)
        set(combat, "CombatControl.invalidBeamTouch", true); set(combat, "CombatControl.movingBeam", true); set(combat, "CombatControl.open", true)
        // CombatControl::position: the target window's corner (the oracle: 747, 0; a script may move it, as targetPosition)
        point(combat, "CombatControl.position", 747, 0)

        val weapons = LuaStructs.create(A.T_WeaponControl)
        shadows[LuaAdaptersArmament.WeaponControlView] = weapons
        set(weapons, "ArmamentControl.systemId", 3.0)
        val drones = LuaStructs.create(A.T_DroneControl)
        shadows[LuaAdaptersDrones.LuaDroneControl] = drones
        set(drones, "ArmamentControl.systemId", 4.0)

        val tutorial = LuaStructs.create(A.T_TutorialManager)
        shadows[LuaTutorial] = tutorial
        set(tutorial, "TutorialManager.tracker", LuaStructs.create(A.T_AnimationTracker))
        val tabbed = LuaStructs.create(A.T_TabbedWindow)
        shadows[LuaTabbedWindow] = tabbed
        set(tabbed, "FocusWindow.bFullFocus", true) // the oracle: TabbedWindow starts with bFullFocus set

        val upgrade = LuaStructs.create(A.T_Button)
        shadows[LuaUpgradeButton] = upgrade
        point(upgrade, "GenericButton.position", 625, 22); rect(upgrade, "GenericButton.hitbox", 631, 25, 62, 49)
        val ftl = LuaStructs.create(A.T_FTLButton)
        shadows[LuaFtlButton] = ftl
        for (k in shadows.keys) LuaRunScope.keep(k)

        // MouseControl: every member except `position` (the engine's pointer); the timer is OnLoop's (see frame)
        wrapType(A.T_MouseControl, skip = setOf("MouseControl.position", "MouseControl.tooltipTimer"), ro = emptySet())
        val tt = attr("MouseControl.tooltipTimer")
        val tg = LuaReg.numGet[tt]; val ts = LuaReg.numSet[tt]
        LuaReg.num(tt, NumGet { o -> if (typed(o, A.T_MouseControl)) tooltipTimer() else (tg ?: missing(tt)).get(o) },
            NumSet { o, v -> if (typed(o, A.T_MouseControl)) { timer = v.toFloat() } else (ts ?: missing(tt)).set(o, v) })
        // tooltip: what a script set this frame, else the text of the engine's own tooltip (the element under the
        // mouse sets MouseControl's tooltip in HS, so a script reads, and can rewrite, that text)
        val tipA = attr("MouseControl.tooltip"); val tipG = LuaReg.strGet[tipA] ?: missing(tipA)
        LuaReg.strGet[tipA] = StrGet { o ->
            val v = tipG.get(o)
            if (o === LuaMouse && v.isNullOrEmpty())
                try { (HsLua.graphics?.tooltip as? xyz.znix.xftl.rendering.StandardTooltip)?.getText() ?: "" } catch (e: Exception) { "" }
            else v
        }
        val lp = attr("MouseControl.lastPosition")
        val lastPos = PointValue(0, 0)
        LuaReg.obj(lp, ObjGet { lastPos.x = LuaEvents.mouseX; lastPos.y = LuaEvents.mouseY; lastPos })
        mouseMethods()

        // CommandGui: the members nothing else serves
        wrapNames(listOf("CommandGui.shipStatus", "CommandGui.sysControl", "CommandGui.ftlButton", "CommandGui.pauseTextLoc",
            "CommandGui.shipPosition", "CommandGui.choiceBox", "CommandGui.outOfFuel", "CommandGui.bHideUI", "CommandGui.dangerLocation",
            "CommandGui.jumpComplete", "CommandGui.mapId", "CommandGui.secretSector"),
            ro = setOf("CommandGui.shipStatus", "CommandGui.sysControl", "CommandGui.ftlButton", "CommandGui.choiceBox", "CommandGui.outOfFuel",
                "CommandGui.dangerLocation", "CommandGui.jumpComplete", "CommandGui.mapId", "CommandGui.secretSector", "CommandGui.pauseTextLoc", "CommandGui.shipPosition"))
        // outOfFuel: the player has no fuel to jump with
        val oof = attr("CommandGui.outOfFuel"); val oofg = LuaReg.numGet[oof]!!
        LuaReg.numGet[oof] = NumGet { o ->
            if (o === LuaGui) { val g = LuaAdapters.inGame(); if (g != null && g.player.fuelCount <= 0) 1.0 else 0.0 } else oofg.get(o)
        }

        // CombatControl
        wrapNames(listOf("CombatControl.playerShipPosition", "CombatControl.position", "CombatControl.selectedRoom", "CombatControl.selectedSelfRoom",
            "CombatControl.targetPosition", "CombatControl.open", "CombatControl.potentialAiming", "CombatControl.aimingPoints",
            "CombatControl.mouseDown", "CombatControl.isAimingTouch", "CombatControl.movingBeam", "CombatControl.beamMoveLast",
            "CombatControl.invalidBeamTouch", "CombatControl.boss_visual"),
            // HS: only boss_visual is immutable; playerShipPosition, position and targetPosition take a script's write
            // (oracle hs-gaps pos rows)
            ro = setOf("CombatControl.boss_visual"))

        // ArmamentControl / WeaponControl: the armed slot follows the engine until a script writes it
        wrapNames(listOf("ArmamentControl.systemId", "ArmamentControl.location", "ArmamentControl.bDragging", "WeaponControl.autoFiring"),
            ro = setOf("WeaponControl.autoFiring", "ArmamentControl.location"))
        val slotAttr = attr("WeaponControl.armedSlot")
        LuaReg.num(slotAttr, NumGet { o ->
            val sh = shadowOf(o)
            val v = sh?.values?.get(slotAttr)
            if (v is Double) v else (LuaAdapters.inGame()?.shipUI?.hsArmedSlot ?: -1).toDouble()
        }, NumSet { o, v -> shadowOf(o)?.values?.set(slotAttr, v.toInt().toDouble()) })
        LuaReg.obj(attr("WeaponControl.armedWeapon"), ObjGet { o ->
            val sh = shadowOf(o)
            val g = LuaAdapters.inGame()
            val slot = (sh?.values?.get(slotAttr) as? Double)?.toInt() ?: g?.shipUI?.hsArmedSlot ?: -1
            if (slot < 0) null else g?.player?.hardpoints?.getOrNull(slot)?.weapon
        })
        val isArm = { o: Any? -> typed(o, A.T_WeaponControl) || typed(o, A.T_DroneControl) }
        methodFor("ArmamentControl.IsDragging", isArm, LuaMethod { ret.bool(shadowOf(arg.obj(1))?.values?.get(attr("ArmamentControl.bDragging")) == true) })
        methodFor("ArmamentControl.DeselectArmament", isArm, LuaMethod { shadowOf(arg.obj(1))?.values?.set(slotAttr, -1.0); 0 })
        methodFor("WeaponControl.DeselectArmament", isArm, LuaMethod { shadowOf(arg.obj(1))?.values?.set(slotAttr, -1.0); 0 })
        // SelectArmament / SwapArmaments / Fire change nothing here: the oracle's SelectArmament(0) left the slot at -1
        methodFor("ArmamentControl.SelectArmament", isArm, LuaMethod { 0 })
        methodFor("WeaponControl.SelectArmament", isArm, LuaMethod { 0 })
        methodFor("ArmamentControl.SwapArmaments", isArm, LuaMethod { 0 })
        methodFor("WeaponControl.SwapArmaments", isArm, LuaMethod { 0 })
        methodFor("WeaponControl.Fire", isArm, LuaMethod { 0 })

        // FocusWindow: any window; bOpen stays with the adapters that know the window, a script's write overrides it
        wrapNames(listOf("FocusWindow.bFullFocus", "FocusWindow.bCloseButtonSelected"), shadow = { o -> shadowAny(o, A.T_FocusWindow) })
        val open = attr("FocusWindow.bOpen")
        val og = LuaReg.numGet[open]; val os = LuaReg.numSet[open]
        LuaReg.num(open, NumGet { o ->
            val v = if (o == null || o is LuaStruct) null else shadows[o]?.values?.get(open)
            if (v is Boolean) (if (v) 1.0 else 0.0)
            else if (o === LuaTabbedWindow) (if (LuaAdapters.inGame()?.shipUI?.isWindowOpen == true) 1.0 else 0.0)
            else (og ?: missing(open)).get(o)
        }, NumSet { o, v -> if (o is LuaStruct) (os ?: missing(open)).set(o, v) else shadowAny(o, A.T_FocusWindow)?.values?.set(open, v != 0.0) })
        wrapNames(listOf("TabbedWindow.bBlockClose", "TabbedWindow.bTutorialMode", "TabbedWindow.bWindowLock"), shadow = { o -> shadowAny(o, A.T_TabbedWindow) })

        // TutorialManager
        wrapNames(listOf("TutorialManager.bGamePaused", "TutorialManager.bQuitTutorial", "TutorialManager.tracker"),
            ro = setOf("TutorialManager.tracker"))
        // hyperspace.i: bAllowJumping / bAllowUpgrades are CustomTutorialState::allowJumping / allowUpgrades, which
        // TutorialManager::AllowJumping / AllowUpgrades return under <disableDefaultTutorial> (Multiverse's tutorial.lua
        // opens the map and the ship window with them).
        LuaReg.bool(A.A_TutorialManager_bAllowJumping, { HsTutorial.allowJumping }, { _, v -> HsTutorial.allowJumping = v })
        LuaReg.bool(A.A_TutorialManager_bAllowUpgrades, { HsTutorial.allowUpgrades }, { _, v -> HsTutorial.allowUpgrades = v })
        val tm = LuaTutorial
        LuaReg.obj(attr("TutorialManager.playerShip"), ObjGet { if (it === tm) LuaAdapters.inGame()?.player else null })
        LuaReg.obj(attr("TutorialManager.gui"), ObjGet { LuaGui })
        LuaReg.obj(attr("TutorialManager.starMap"), ObjGet { HsEventsLua.StarMapRef })
        LuaReg.obj(attr("TutorialManager.combatControl"), ObjGet { LuaAdaptersArmament.CombatControlView })
        LuaReg.obj(attr("TutorialManager.shipInfo"), ObjGet { LuaTabbedWindow })
        val running = attr("TutorialManager.bRunning")
        val rg = LuaReg.numGet[running]
        LuaReg.num(running, NumGet { o -> shadowOf(o)?.values?.get(running)?.let { if (it == true) 1.0 else 0.0 } ?: (rg ?: missing(running)).get(o) },
            NumSet { o, v -> shadowOf(o)?.values?.set(running, v != 0.0) })

        buttons(upgrade)
    }

    private fun mouseMethods() {
        fun mouse(): LuaStruct = shadowOf(arg.obj(1)) ?: throw LuaError("MouseControl expected")
        fun put(name: String, v: Any?) { set(mouse(), name, v) }
        method("MouseControl.SetTooltip", LuaMethod { setTooltip(mouse(), arg.str(2) ?: ""); 0 })
        // SetTooltipTitle: only while the frame has no title yet
        method("MouseControl.SetTooltipTitle", LuaMethod { if (mStr(mouse(), "MouseControl.tooltipTitle").isEmpty()) put("MouseControl.tooltipTitle", arg.str(2) ?: ""); 0 })
        method("MouseControl.SetDoor", LuaMethod { put("MouseControl.animateDoor", arg.int(2).toDouble()); 0 })
        method("MouseControl.ResetArmed", LuaMethod { put("MouseControl.valid", false); put("MouseControl.aiming_required", 0.0); 0 })
        method("MouseControl.Reset", LuaMethod { resetMouse(mouse()); 0 })
        // MouseControl::LoadTooltip: the text `tooltip_<id>`; one that is missing reads "Could not find: <key>"
        method("MouseControl.LoadTooltip", LuaMethod { setTooltip(mouse(), LuaAdaptersMore.translate("tooltip_" + (arg.str(2) ?: ""))); 0 })
        method("MouseControl.QueueStaticTooltip", LuaMethod {
            val p = arg.obj(2) as? PointValue ?: throw LuaError("Point expected")
            setPoint(mouse(), "MouseControl.staticTooltip", p.x, p.y); 0
        })
        // InstantTooltip: bForceTooltip, the frame's tooltip shows without the rest delay
        method("MouseControl.InstantTooltip", LuaMethod { put("MouseControl.bForceTooltip", true); 0 })
        method("MouseControl.OnLoop", LuaMethod { loopMouse(mouse(), LuaFps.speedFactor * 0.0625f); 0 })
        method("MouseControl.OnRender", LuaMethod { luaOnRender(mouse()); 0 })
        // RenderTooltip(Point, staticPos): the frame's tooltip drawn now at the point
        method("MouseControl.RenderTooltip", LuaMethod {
            val m = mouse(); val p = arg.obj(2) as? PointValue ?: throw LuaError("Point expected")
            HsLua.graphics?.let { drawTooltip(m, it, p.x, p.y, arg.bool(3)) }; 0
        })
        // MeasureTooltip(width): the size of the text block the current tooltip and title make when wrapped at [width]
        method("MouseControl.MeasureTooltip", LuaMethod {
            val s = mouse()
            val g = LuaAdapters.inGame() ?: throw LuaError("MeasureTooltip needs a running game")
            val title = s.values[attr("MouseControl.tooltipTitle")] as? String ?: ""
            val body = s.values[attr("MouseControl.tooltip")] as? String ?: ""
            val size = xyz.znix.xftl.rendering.NativeTooltip.measure(
                if (title.isEmpty()) null else g.getFont("JustinFont12Bold"), g.getFont("JustinFont11Bold"),
                if (title.isEmpty()) null else title, body, arg.int(2)
            )
            // MouseControl::MeasureTooltip returns the text block only: RenderTooltip adds 27 and 18 (23 with a title) around it
            // (RedesignedTooltips.cpp: rect_w = size.x + 27, rect_h = size.y + 18 or 23; oracle ui3-measure 134x42)
            ret.obj(PointValue(size.first - 27, size.second - (if (title.isEmpty()) 18 else 23)), A.T_Point)
        })
    }

    // ------------------------------------------------------------------------------------------------- buttons

    /**
     * GenericButton / Button / FTLButton / TextButton: the shadow struct (or the struct of a button made with `new`)
     * holds the fields; `bActive` of the upgrade button is the engine's until a script writes it.
     */
    private fun buttons(upgrade: LuaStruct) {
        val buttonTypes = listOf(A.T_Button, A.T_FTLButton)
        val bAct = attr("GenericButton.bActive")
        // A new button starts active; this one reads the engine's state until a script writes it.
        upgrade.values.remove(bAct)
        for (t in buttonTypes) wrapType(t, skip = setOf("GenericButton.bActive"),
            ro = setOf("Button.bMirror", "Button.imageSize", "GenericButton.flashing"))
        val g = LuaReg.numGet[bAct]!!; val s = LuaReg.numSet[bAct]!!
        LuaReg.numGet[bAct] = NumGet { o ->
            if (o === LuaUpgradeButton) { val v = upgrade.values[bAct]; if (v is Boolean) (if (v) 1.0 else 0.0) else g.get(o) }
            else g.get(shadowOf(o) ?: o)
        }
        LuaReg.numSet[bAct] = NumSet { o, v -> val sh = shadowOf(o); if (sh != null) sh.values[bAct] = v != 0.0 else s.set(o, v) }

        fun target(o: Any?): LuaStruct? = shadowOf(o) ?: (o as? LuaStruct)?.takeIf { isButtonType(it.luaType) }
        fun mine(o: Any?) = target(o) != null
        fun btn() = target(arg.obj(1))!!
        fun hitbox(b: LuaStruct) = LuaStructs.member(b, attr("GenericButton.hitbox")) as LuaStruct
        fun position(b: LuaStruct) = LuaStructs.member(b, attr("GenericButton.position")) as LuaStruct
        for (cls in listOf("GenericButton", "Button", "FTLButton", "TextButton")) {
            val has = { m: String -> methodIds.containsKey("$cls.$m") }
            if (has("SetActive")) methodFor("$cls.SetActive", ::mine, LuaMethod { btn().values[bAct] = arg.bool(2); 0 })
            if (has("SetLocation")) methodFor("$cls.SetLocation", ::mine, LuaMethod {
                val b = btn(); val p = arg.obj(2) as? PointValue ?: throw LuaError("Point expected")
                val pos = LuaStructs.fields(position(b)); val hit = hitbox(b)
                // the hit box keeps its offset from the position
                val h = LuaStructs.fields(hit)
                LuaStructs.setFields(hit, h[0] + (p.x - pos[0]), h[1] + (p.y - pos[1]), h[2], h[3])
                LuaStructs.setFields(position(b), p.x.toDouble(), p.y.toDouble())
                0
            })
            if (has("SetHitBox")) methodFor("$cls.SetHitBox", ::mine, LuaMethod {
                val r = LuaStructs.fields(arg.obj(2) as? LuaStruct ?: throw LuaError("Rect expected"))
                LuaStructs.setFields(hitbox(btn()), r[0], r[1], r[2], r[3]); 0
            })
            // GenericButton::Reset: not hovered, activated or selected, no active touch (oracle hs-gaps tb-reset)
            if (has("Reset")) methodFor("$cls.Reset", ::mine, LuaMethod {
                val b = btn()
                b.values[attr("GenericButton.bHover")] = false; b.values[attr("GenericButton.bActivated")] = false
                b.values[attr("GenericButton.bSelected")] = false; b.values[attr("GenericButton.activeTouch")] = 0.0; 0
            })
            if (has("OnTouch")) methodFor("$cls.OnTouch", ::mine, LuaMethod { ret.bool(onTouch(btn(), arg.int(2), arg.int(3), arg.int(4), arg.int(5))) })
            if (has("MouseMove")) methodFor("$cls.MouseMove", ::mine, LuaMethod {
                val b = btn(); val r = LuaStructs.fields(hitbox(b)); val x = arg.int(2); val y = arg.int(3)
                b.values[attr("GenericButton.bHover")] = x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]; 0
            })
            // OnLoop advances the flashing tracker (no visible field: oracle tb-loop); OnClick / OnRightClick do nothing to
            // a script's button (tb-click, tb-rclick); ResetPrimitives only frees cached drawing
            for (m in listOf("OnLoop", "OnRender", "OnClick", "OnRightClick", "ResetPrimitives", "SetImageBase", "SetActiveImage", "SetInactiveImage"))
                if (has(m)) methodFor("$cls.$m", ::mine, LuaMethod { 0 })
        }
    }

    /**
     * GenericButton::OnTouch(action, id, x, y, initialX, initialY) (1 down, 2 move, 3 up, 4 cancel): activation is
     * cleared first. Unless allowAnyTouch, only a down or the button's own touch is taken (there are no live touches
     * here, so an old activeTouch never blocks a down). A down inside the hit box hovers it and takes the touch; a move
     * hovers while inside; an up activates an active button released inside and drops the touch, and with
     * touchSelectable selects it (a second activating up on a selected button only activates it); a cancel drops the
     * touch. With touchSelectable a button not active and hovered loses its selection. Returns whether the touch is
     * held, else the hover, else the activation.
     */
    private fun onTouch(b: LuaStruct, action: Int, id: Int, x: Int, y: Int): Boolean {
        fun flag(n: String) = b.values[attr(n)].let { it == true || (it is Number && it.toDouble() != 0.0) }
        fun put(n: String, v: Boolean) { b.values[attr(n)] = v }
        val r = LuaStructs.fields(LuaStructs.member(b, attr("GenericButton.hitbox")) as LuaStruct)
        fun inside() = r[0] < x && x < r[0] + r[2] && r[1] < y && y < r[1] + r[3]
        var touch = (b.values[attr("GenericButton.activeTouch")] as? Number)?.toInt() ?: 0
        put("GenericButton.bActivated", false)
        val selectable = flag("GenericButton.touchSelectable")
        fun deselect() { if (!flag("GenericButton.bActive") || !flag("GenericButton.bHover")) put("GenericButton.bSelected", false) }
        fun result(): Boolean {
            b.values[attr("GenericButton.activeTouch")] = touch.toDouble()
            return touch != 0 || flag("GenericButton.bHover") || flag("GenericButton.bActivated")
        }
        if (!flag("GenericButton.allowAnyTouch") && action != 1 && touch != id) return false
        when (action) {
            1 -> if (inside()) { put("GenericButton.bHover", true); touch = id }
            2 -> put("GenericButton.bHover", inside())
            3 -> {
                put("GenericButton.bActivated", flag("GenericButton.bActive") && inside())
                put("GenericButton.bHover", false); touch = 0
                if (!selectable) return result()
                if (!flag("GenericButton.bActivated")) { deselect(); return result() }
                if (!flag("GenericButton.bSelected")) { put("GenericButton.bActivated", false); put("GenericButton.bSelected", true) }
                return result()
            }
            4 -> { put("GenericButton.bHover", false); touch = 0 }
        }
        if (selectable) deselect()
        return result()
    }

    /** `new FocusWindow()`, `new TextButton()` and friends: struct objects with their constructors' starting values. */
    private fun constructors() {
        LuaReg.method(A.M_new_FocusWindow, LuaMethod { ret.obj(LuaStructs.create(A.T_FocusWindow), A.T_FocusWindow) })
        val textButton = LuaMethod {
            val b = LuaStructs.create(A.T_TextButton)
            // TextButton() leaves the position at INT_MIN and an empty hit box, and takes any touch (oracle hs-gaps tb0)
            point(b, "GenericButton.position", Int.MIN_VALUE, Int.MIN_VALUE); rect(b, "GenericButton.hitbox", -1, -1, 0, 0)
            b.values[attr("GenericButton.allowAnyTouch")] = true
            ret.obj(b, A.T_TextButton)
        }
        LuaReg.method(A.M_new_TextButton, textButton)
    }

    // -------------------------------------------------------------------------------------------- TimerHelper

    /**
     * TimerHelper::TimerHelper() (the oracle: the fields start as they happen to be in memory; a running timer, no loop),
     * SetMaxTime (the goal), ResetMinMax(min, max), Start(int goal), Update (one frame of time) and Done's new random
     * goal for a looping timer.
     */
    private fun timers() {
        val random = java.util.Random()
        val make = LuaMethod { n ->
            val t = if (n >= 2) LuaOwnTimer(arg.int(2), arg.int(1)) else LuaOwnTimer(0, 0)
            ret.obj(t, A.T_TimerHelper)
        }
        LuaReg.method(A.M_new_TimerHelper, make); LuaReg.method(A.M_new_TimerHelper__SWIG_0, make); LuaReg.method(A.M_new_TimerHelper__SWIG_1, make)
        fun timer(name: String, f: (LuaTimerView, Int) -> Int) {
            val ids = methodIds[name] ?: error("no method $name")
            for (id in ids) {
                val prev = LuaReg.methods[id]
                LuaReg.method(id, LuaMethod { n ->
                    val t = arg.obj(1).let { if (it is xyz.znix.xftl.hs.stat.HsTimer) LuaHsTimerView(it) else it }
                    if (t is LuaTimerView) f(t, n) else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: $name")).call(n)
                })
            }
        }
        timer("TimerHelper.SetMaxTime") { t, _ -> t.currGoal = arg.int(2).toFloat(); 0 }
        timer("TimerHelper.ResetMinMax") { t, _ -> t.minTime = arg.int(2); t.maxTime = arg.int(3); 0 }
        timer("TimerHelper.Start") { t, n -> if (n >= 2) t.currGoal = arg.int(2).toFloat(); t.currTime = 0f; t.running = true; 0 }
        timer("TimerHelper.Update") { t, _ -> if (t.running) t.currTime += LuaFps.speedFactor / 16f; 0 }
        timer("TimerHelper.Done") { t, _ ->
            val done = t.running && t.currTime >= t.currGoal
            if (done) {
                if (t.loop) {
                    t.currTime = 0f
                    // a looping timer draws its next goal between min and max (in thousandths of a second)
                    if (t is LuaOwnTimer) {
                        val span = t.maxTime - t.minTime
                        t.currGoal = (t.minTime + if (span > 0) random.nextInt(span) else 0) / 1000f
                    }
                } else t.running = false
            }
            ret.bool(done)
        }
    }

    // -------------------------------------------------------------------------------------------- Description

    private val descriptions = xyz.znix.xftl.hs.lua.HsRunMaps.any(java.util.IdentityHashMap<LuaDescription, HashMap<Int, Any?>>())
    private fun overlay(d: LuaDescription) = descriptions.getOrPut(d) { HashMap() }

    /**
     * Blueprint::desc's other members (Description): cost, rarity, baseRarity, tip, tooltip, locked and bp. They read the
     * blueprint's `<cost>`, `<rarity>`, `<tip>`, `<tooltip>` and `<bp>`; a script's write is kept on the description (the
     * engine's own price and rarity are not changed by it).
     */
    private fun description() {
        fun d(o: Any?) = o as? LuaDescription
        // the placeholder blueprint of a name the data does not define is no engine Blueprint: everything reads as 0 or empty
        fun real(x: LuaDescription): Blueprint? = LuaDescriptionApi.asBlueprint(x.bp)
        fun num(name: String, def: (Blueprint) -> Double) {
            val a = attr(name)
            val g = LuaReg.numGet[a]; val s = LuaReg.numSet[a]
            LuaReg.num(a, NumGet { o -> val x = d(o); if (x == null) (g ?: missing(a)).get(o) else (overlay(x)[a] as Double?) ?: real(x)?.let(def) ?: 0.0 },
                NumSet { o, v -> val x = d(o); if (x == null) (s ?: missing(a)).set(o, v) else overlay(x)[a] = v.toInt().toDouble() })
        }
        num("Description.cost") { (it.cost ?: 0).toDouble() }
        num("Description.rarity") { (it.rarity ?: 0).toDouble() }
        num("Description.baseRarity") { (it.xmlRarity ?: 0).toDouble() }
        // cost and rarity writes reach the engine's blueprint (store prices, loot pools); a placeholder blueprint keeps the overlay
        for ((name, write) in listOf<Pair<String, (Blueprint, Int) -> Unit>>(
            "Description.cost" to { bp, v -> bp.hsCost = v }, "Description.rarity" to { bp, v -> bp.rarity = v })) {
            val a = attr(name)
            val g = LuaReg.numGet[a]!!; val s = LuaReg.numSet[a]!!
            LuaReg.num(a, NumGet { o ->
                val x = d(o); val bp = if (x == null) null else real(x)
                if (bp == null) g.get(o) else (if (name == "Description.cost") (bp.cost ?: 0) else (bp.rarity ?: 0)).toDouble()
            }, NumSet { o, v ->
                val x = d(o); val bp = if (x == null) null else real(x)
                if (bp == null) s.set(o, v) else write(bp, v.toInt())
            })
        }
        num("Description.bp") { it.hsBp.toDouble() }
        val locked = attr("Description.locked")
        val lg = LuaReg.numGet[locked]; val ls = LuaReg.numSet[locked]
        LuaReg.num(locked, NumGet { o -> val x = d(o); if (x == null) (lg ?: missing(locked)).get(o) else if (overlay(x)[locked] == true) 1.0 else 0.0 },
            NumSet { o, v -> val x = d(o); if (x == null) (ls ?: missing(locked)).set(o, v) else overlay(x)[locked] = v != 0.0 })
        val tip = attr("Description.tip")
        val tg = LuaReg.strGet[tip]; val ts = LuaReg.strSet[tip]
        LuaReg.str(tip, StrGet { o -> val x = d(o); if (x == null) (tg ?: missing(tip)).get(o) else (overlay(x)[tip] as String?) ?: real(x)?.tip?.hsData ?: "" },
            StrSet { o, v -> val x = d(o); if (x == null) (ts ?: missing(tip)).set(o, v) else overlay(x)[tip] = v })
        val tooltip = attr("Description.tooltip")
        val og = LuaReg.objGet[tooltip]; val os = LuaReg.objSet[tooltip]
        LuaReg.obj(tooltip, ObjGet { o ->
            val x = d(o)
            if (x == null) (og ?: missing(tooltip)).get(o)
            else LuaViews.cached(x.bp, tooltip) { LuaTextRef({ (overlay(x)[tooltip] as GameText?) ?: GameText.literal(real(x)?.hsTooltip ?: "") }, { t -> overlay(x)[tooltip] = t }) }
        }, ObjSet { o, v -> if (d(o) == null) (os ?: missing(tooltip)).set(o, v) })
        // BlueprintManager::GetWeaponBlueprint of a name the data does not define: a default-constructed blueprint (oracle: not nil)
        val getWeapon = LuaReg.methods[A.M_BlueprintManager_GetWeaponBlueprint]
        if (getWeapon != null) LuaReg.method(A.M_BlueprintManager_GetWeaponBlueprint, LuaMethod { n ->
            val found = LuaAdapters.inGame()?.blueprintManager?.getOrNull(arg.str(2) ?: "")
            if (found is xyz.znix.xftl.weapons.AbstractWeaponBlueprint) getWeapon.call(n)
            else ret.obj(LuaAdaptersDrones.DefaultWeaponBlueprint, A.T_WeaponBlueprint)
        })
    }

    // ------------------------------------------------------------------------------------------- WindowFrame

    /** `Hyperspace.WindowFrame(x, y, w, h)`: a frame with its rect and the three primitives FTL builds for it (drawing is not exposed). */
    private fun windowFrame() {
        val make = LuaMethod {
            val f = LuaStructs.create(A.T_WindowFrame)
            rect(f, "WindowFrame.rect", arg.int(1), arg.int(2), arg.int(3), arg.int(4))
            for (n in listOf("mask", "outline", "pattern")) set(f, "WindowFrame.$n", LuaPtr(A.T_GL_Primitive))
            ret.obj(f, A.T_WindowFrame)
        }
        LuaReg.method(A.M_new_WindowFrame, make)
    }

    // ----------------------------------------------------------------------------------------- HyperspaceVersion

    private var version: LuaStruct? = null

    /** `Hyperspace.version`: 1.23.2 (the oracle's HS build; the integer version is 332). */
    private fun versionObject() {
        val v = LuaStructs.create(A.T_HyperspaceVersion)
        set(v, "HyperspaceVersion.major", 1.0); set(v, "HyperspaceVersion.minor", 23.0); set(v, "HyperspaceVersion.patch", 2.0)
        version = v
        LuaReg.obj(A.A_module_version, ObjGet { version })
        method("HyperspaceVersion.toVersionString", LuaMethod { ret.str("1.23.2") })
        method("HyperspaceVersion.toIdentifierString", LuaMethod { ret.str("1.23.2 ") })
        method("HyperspaceVersion.getDeprecatedIntegerVersion", LuaMethod { ret.num(332) })
        method("HyperspaceVersion.isFeatureBuild", LuaMethod { ret.bool(false) })
        method("HyperspaceVersion.getFeatureName", LuaMethod { ret.str("") })
        method("HyperspaceVersion.__tostring", LuaMethod { ret.str("1.23.2") })
    }

    // -------------------------------------------------------------------------------------------- rapidxml

    private fun node(i: Int): XmlNode = arg.obj(i) as XmlNode
    private fun nodeOrNull(i: Int): XmlNode? = if (arg.isNil(i)) null else arg.obj(i) as XmlNode
    private fun attrOrNull(i: Int): XmlAttribute? = if (arg.isNil(i)) null else arg.obj(i) as XmlAttribute

    /** A node or attribute that is already in a tree leaves it first (rapidxml would leave the old tree dangling). */
    private fun detach(c: XmlNode) { c.parent?.let { p -> p.children.remove(c) }; c.parent = null }
    private fun detach(a: XmlAttribute) { a.parent?.let { p -> p.attributes.remove(a) }; a.parent = null }

    /**
     * xml_node::prepend_node / append_node / insert_node / remove_* and the attribute versions (rapidxml.hpp): the
     * removed node or attribute loses its parent; insert_node(where, child) puts child before where (at the end for nil).
     */
    private fun xmlMutators() {
        LuaReg.method(A.M_xml_node_prepend_node, LuaMethod { val p = node(1); val c = node(2); detach(c); c.parent = p; p.children.add(0, c); 0 })
        LuaReg.method(A.M_xml_node_append_node, LuaMethod { val p = node(1); val c = node(2); detach(c); c.parent = p; p.children.add(c); 0 })
        LuaReg.method(A.M_xml_node_insert_node, LuaMethod {
            val p = node(1); val where = nodeOrNull(2); val c = node(3)
            detach(c)
            val at = if (where == null) -1 else p.children.indexOf(where)
            c.parent = p
            if (at < 0) p.children.add(c) else p.children.add(at, c)
            0
        })
        LuaReg.method(A.M_xml_node_remove_first_node, LuaMethod { val p = node(1); if (p.children.isNotEmpty()) p.children.removeAt(0).parent = null; 0 })
        LuaReg.method(A.M_xml_node_remove_last_node, LuaMethod { val p = node(1); if (p.children.isNotEmpty()) p.children.removeAt(p.children.size - 1).parent = null; 0 })
        LuaReg.method(A.M_xml_node_remove_node, LuaMethod { val p = node(1); val c = nodeOrNull(2); if (c != null && p.children.remove(c)) c.parent = null; 0 })
        LuaReg.method(A.M_xml_node_remove_all_nodes, LuaMethod { val p = node(1); for (c in p.children) c.parent = null; p.children.clear(); 0 })

        LuaReg.method(A.M_xml_node_prepend_attribute, LuaMethod { val p = node(1); val a = attrOrNull(2) ?: return@LuaMethod 0; detach(a); a.parent = p; p.attributes.add(0, a); 0 })
        LuaReg.method(A.M_xml_node_append_attribute, LuaMethod { val p = node(1); val a = attrOrNull(2) ?: return@LuaMethod 0; detach(a); a.parent = p; p.attributes.add(a); 0 })
        LuaReg.method(A.M_xml_node_insert_attribute, LuaMethod {
            val p = node(1); val where = attrOrNull(2); val a = attrOrNull(3) ?: return@LuaMethod 0
            detach(a)
            val at = if (where == null) -1 else p.attributes.indexOf(where)
            a.parent = p
            if (at < 0) p.attributes.add(a) else p.attributes.add(at, a)
            0
        })
        LuaReg.method(A.M_xml_node_remove_first_attribute, LuaMethod { val p = node(1); if (p.attributes.isNotEmpty()) p.attributes.removeAt(0).parent = null; 0 })
        LuaReg.method(A.M_xml_node_remove_last_attribute, LuaMethod { val p = node(1); if (p.attributes.isNotEmpty()) p.attributes.removeAt(p.attributes.size - 1).parent = null; 0 })
        LuaReg.method(A.M_xml_node_remove_attribute, LuaMethod { val p = node(1); val a = attrOrNull(2); if (a != null && p.attributes.remove(a)) a.parent = null; 0 })
        LuaReg.method(A.M_xml_node_remove_all_attributes, LuaMethod { val p = node(1); for (a in p.attributes) a.parent = null; p.attributes.clear(); 0 })
    }
}
