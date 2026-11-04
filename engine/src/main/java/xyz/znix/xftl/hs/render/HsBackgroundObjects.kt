// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomBackgroundObject.cpp and
// CustomBackgroundObject.h (with the CustomEvents.cpp parts: parsing, CustomCreateLocation, GenerateMap, save)
package xyz.znix.xftl.hs.render

import org.jdom2.Element
import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.EventQueueEvent
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsReq
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon

/**
 * Background objects (`<backgroundObject name>` definitions at the top level of an event file or inside an event,
 * `<backgroundObject load="name"/>` reusing one; `<clearBackgroundObject name>` and
 * `<transformBackgroundObject name>new</transformBackgroundObject>` in events). An event creates its objects when it
 * is shown (CustomCreateLocation), each at its `<spawnArea>` corner (plus a native draw in `w`/`h`), kept per beacon
 * of the sector (a new sector drops them; `jumpClear` ones go with the jump). An object plays `<anim>` (or, hovered,
 * `<hoverAnim>`; inactive, `<inactiveAnim>` or nothing with `hide`), on its `<layer>`: background (after
 * SpaceManager::OnRenderBackground), foreground (after OnRenderForeground), asteroids (after OnRenderAsteroids(3)),
 * player / playerfront (before / after the player ship, in its frame). As in HS's parser, the value "front" gives
 * the player-front layer and "playerfront" the front layer (the `strcmp` without `== 0`). Under the mouse (when no
 * targeting is armed: CombatControl::MouseMove returned false) it shows `<tooltip>` / `<inactiveTooltip>`; a left
 * click on an active one with `<clickAction>` queues its `<event>` (unseeded, not while jumping) and applies
 * activate / deactivate / toggle / destroy and `<spawnObject>`; `<animAction>` runs when a non-looping anim ends.
 * It is active while `<active>` and its `<req>` (an advanced HasEquipment check between lvl and max_lvl) hold and,
 * with `disableCombat`, no hostile ship is present.
 *
 * Also: `<timerAction>` (time or minTime/maxTime, loop, requireActive, requireHover; triggerActiveOnly is parsed and unused as in
 * HS), `<transform chance>` / `<noTransform chance>` in an action (one `random32() % total` draw per activation),
 * `random` animation starts (Animation::RandomStart) and the front layer (drawn after the player ship while the jump
 * zoom is above zero, HsRenderEvents.layerFront). Timers are saved with the object.
 */
object HsBackgroundObjects {
    const val LAYER_BACKGROUND = 0
    const val LAYER_FOREGROUND = 1
    const val LAYER_ASTEROIDS = 2
    const val LAYER_PLAYER = 3
    const val LAYER_PLAYERFRONT = 4
    const val LAYER_FRONT = 5

    class AnimDef {
        @JvmField var anim = ""
        @JvmField var play = true
        @JvmField var loop = true
        @JvmField var random = false
    }

    class Action {
        @JvmField var event = ""
        @JvmField var selfOp = OP_NONE
        @JvmField val spawnObjects = ArrayList<Pair<Def, IntArray>>()
        @JvmField var newDefChance = 0
        @JvmField val newDef = ArrayList<Pair<Def, Int>>()

        /** CustomBackgroundObjectAction::GetRandomNewDef (one random32 draw; HS divides by zero without any transform tag). */
        fun randomNewDef(): Def? {
            if (newDefChance <= 0) return null
            var rng = ftl.browser.NativeRandom.next31() % newDefChance
            for ((d, c) in newDef) {
                if (rng < c) return d
                rng -= c
            }
            return null
        }
    }

    /** CustomBackgroundObjectTimer: a timerAction. -1 means "not set" (requireActive / requireHover: any). */
    class TimerDef {
        @JvmField var action = Action()
        @JvmField var time = -1f
        @JvmField var minTime = -1
        @JvmField var maxTime = -1
        @JvmField var loop = false
        @JvmField var requireActive = -1
        @JvmField var requireHover = -1
        @JvmField var triggerActiveOnly = false
    }

    /** FTL's TimerHelper (Start 0x5cb0b0 / HS Start(float), Update 0x5cb1b0, Done 0x5cb200). */
    class BgTimer {
        @JvmField var maxTime = 0
        @JvmField var minTime = 0
        @JvmField var currTime = 0f
        @JvmField var currGoal = 0f
        @JvmField var loop = false
        @JvmField var running = false

        private fun randomGoal(): Float =
            if (maxTime != minTime) (minTime + ftl.browser.NativeRandom.next31() % (maxTime - minTime)) / 1000f
            else maxTime / 1000f

        fun start(goal: Float) {
            running = true
            currTime = 0f
            currGoal = if (goal != -1f) goal else randomGoal()
        }

        fun update(dt: Float) { if (running) currTime += dt }

        fun done(): Boolean {
            if (!running) return false
            if (currGoal <= currTime && currTime != currGoal) {
                if (!loop) { running = false; return true }
                currTime = 0f
                currGoal = randomGoal()
                return true
            }
            return false
        }
    }

    class Def(@JvmField val idx: Int) {
        @JvmField var name = ""
        @JvmField var layer = LAYER_BACKGROUND
        @JvmField var z = 0
        @JvmField val spawnArea = intArrayOf(0, 0, 0, 0)
        @JvmField val hitbox = intArrayOf(0, 0, -1, -1)
        @JvmField var centerAnim = false
        @JvmField var centerHitbox = false
        @JvmField val anim = AnimDef()
        @JvmField val hoverAnim = AnimDef()
        @JvmField val inactiveAnim = AnimDef()
        @JvmField var tooltip = ""
        @JvmField var tooltipId = false
        @JvmField var inactiveTooltip = ""
        @JvmField var inactiveTooltipId = false
        @JvmField var req = ""
        @JvmField var lvl = 1
        @JvmField var maxLvl = Int.MAX_VALUE
        @JvmField var active = true
        @JvmField var jumpClear = false
        @JvmField var hideInactive = false
        @JvmField var disableCombat = false
        @JvmField var clickAction: Action? = null
        @JvmField var animAction: Action? = null
        @JvmField val timerActions = ArrayList<TimerDef>()
    }

    private const val OP_NONE = 0
    private const val OP_ACTIVATE = 1
    private const val OP_DEACTIVATE = 2
    private const val OP_TOGGLE = 3
    private const val OP_DESTROY = 4

    private const val RESULT_NOCHANGE = 0
    private const val RESULT_RESET = 1
    private const val RESULT_DESTROYED = 2

    class Obj(@JvmField var def: Def) {
        @JvmField var x = 0
        @JvmField var y = 0
        /** The hit box (x, y, w, h), screen or (player layers) player ship frame. */
        @JvmField val hitbox = IntArray(4)
        @JvmField var reqActive = true
        @JvmField var bActive = true
        @JvmField var hover = false
        @JvmField var anim: FTLAnimation? = null
        @JvmField var animX = 0f
        @JvmField var animY = 0f
        @JvmField var hoverAnim: FTLAnimation? = null
        @JvmField var hoverX = 0f
        @JvmField var hoverY = 0f
        @JvmField var inactiveAnim: FTLAnimation? = null
        @JvmField var timers = ArrayList<BgTimer>()
        @JvmField var inactiveX = 0f
        @JvmField var inactiveY = 0f
        val hasHoverAnim get() = def.hoverAnim.anim.isNotEmpty()
        val hasInactiveAnim get() = def.inactiveAnim.anim.isNotEmpty()
    }

    // ---- definitions (CustomBackgroundObjectManager::defs / namedDefs), rebuilt with the event data ----
    private val defs = ArrayList<Def>()

    /** The definition of an index as a run file of the original names it (vanillasave.NativeImport), or null. */
    @JvmStatic
    fun defAt(idx: Int): Def? = defs.getOrNull(idx)
    private val named = HashMap<String, Def>()
    private val byElement = java.util.IdentityHashMap<Element, Def>()

    // ---- the objects of the sector's beacons (backgroundObjects, keyed by StarMap::locations index) ----
    private val objects = HashMap<Int, ArrayList<Obj>>()
    /** currentLocationIndex: the beacon CreateLocation last showed, -1 after ClearLocation. */
    private var current = -1
    /** The sector those beacon indices belong to (StarMap::GenerateMap clears the objects). */
    private var sector: Any? = null
    private var needSort = false
    private var mouseActive = false
    private var game: InGameState? = null

    @JvmStatic
    fun resetDefs() {
        defs.clear(); named.clear(); byElement.clear(); objects.clear(); current = -1; sector = null
    }

    private fun bool(s: String?) = s?.trim() == "true"
    private fun int(s: String?) = s!!.trim().toInt()

    /** CustomBackgroundObjectManager::ParseCustomBackgroundObject. [processEvent] is EventsParser::ProcessEvent. */
    @JvmStatic
    fun parse(node: Element, processEvent: (Element, String) -> String): Def? {
        byElement[node]?.let { return it }
        node.getAttributeValue("load")?.let { load ->
            val d = named[load]
            if (d == null) HS.unsupported("event", "backgroundObject", "load=$load (unknown)") else byElement[node] = d
            return d
        }
        val d = Def(defs.size)
        defs += d
        byElement[node] = d
        node.getAttributeValue("name")?.let { d.name = it; named[it] = d }
        for (c in node.children) {
            when (c.name) {
                "layer" -> {
                    // HS compares "playerfront" and "front" without `== 0`: "front" (and any other value) gives
                    // LAYER_PLAYERFRONT, "playerfront" gives LAYER_FRONT.
                    d.layer = when (c.text) {
                        "background" -> LAYER_BACKGROUND
                        "foreground" -> LAYER_FOREGROUND
                        "asteroids" -> LAYER_ASTEROIDS
                        "player", "playerback" -> LAYER_PLAYER
                        "playerfront" -> LAYER_FRONT
                        else -> LAYER_PLAYERFRONT
                    }
                    c.getAttributeValue("z")?.let { d.z = int(it) }
                }
                "spawnArea" -> {
                    c.getAttributeValue("x")?.let { d.spawnArea[0] = int(it) }
                    c.getAttributeValue("y")?.let { d.spawnArea[1] = int(it) }
                    c.getAttributeValue("w")?.let { d.spawnArea[2] = int(it) }
                    c.getAttributeValue("h")?.let { d.spawnArea[3] = int(it) }
                    c.getAttributeValue("center")?.let { d.centerAnim = bool(it) }
                }
                "hitbox" -> {
                    c.getAttributeValue("x")?.let { d.hitbox[0] = int(it) }
                    c.getAttributeValue("y")?.let { d.hitbox[1] = int(it) }
                    c.getAttributeValue("w")?.let { d.hitbox[2] = int(it) }
                    c.getAttributeValue("h")?.let { d.hitbox[3] = int(it) }
                    c.getAttributeValue("center")?.let { d.centerHitbox = bool(it) }
                }
                "anim" -> animDef(d.anim, c)
                "tooltip" -> { val id = c.getAttributeValue("id"); d.tooltip = id ?: c.text; d.tooltipId = id != null }
                "inactiveTooltip" -> {
                    val id = c.getAttributeValue("id"); d.inactiveTooltip = id ?: c.text; d.inactiveTooltipId = id != null
                }
                "req" -> {
                    d.req = c.text
                    c.getAttributeValue("max_lvl")?.let { d.maxLvl = int(it); d.lvl = -Int.MAX_VALUE }
                    c.getAttributeValue("lvl")?.let { d.lvl = int(it) }
                }
                "active" -> d.active = bool(c.text)
                "jumpClear" -> d.jumpClear = bool(c.text)
                "disableCombat" -> d.disableCombat = bool(c.text)
                "clickAction" -> d.clickAction = parseAction(c, processEvent)
                "animAction" -> d.animAction = parseAction(c, processEvent)
                "timerAction" -> {
                    val t = TimerDef()
                    t.action = parseAction(c, processEvent)
                    c.getAttributeValue("time")?.let { t.time = it.trim().toFloat() }
                    c.getAttributeValue("minTime")?.let { t.minTime = (it.trim().toFloat() * 1000).toInt() }
                    c.getAttributeValue("maxTime")?.let { t.maxTime = (it.trim().toFloat() * 1000).toInt() }
                    c.getAttributeValue("loop")?.let { t.loop = bool(it) }
                    c.getAttributeValue("requireActive")?.let { t.requireActive = if (bool(it)) 1 else 0 }
                    c.getAttributeValue("requireHover")?.let { t.requireHover = if (bool(it)) 1 else 0 }
                    c.getAttributeValue("triggerActiveOnly")?.let { t.triggerActiveOnly = bool(it) }
                    d.timerActions += t
                }
            }
        }
        for (a in arrayOf(d.hoverAnim, d.inactiveAnim)) { a.play = d.anim.play; a.loop = d.anim.loop; a.random = d.anim.random }
        for (c in node.children) {
            when (c.name) {
                "hoverAnim" -> animDef(d.hoverAnim, c)
                "inactiveAnim" -> {
                    animDef(d.inactiveAnim, c)
                    c.getAttributeValue("hide")?.let { d.hideInactive = bool(it) }
                }
            }
        }
        return d
    }

    private fun animDef(a: AnimDef, c: Element) {
        a.anim = c.text
        c.getAttributeValue("play")?.let { a.play = bool(it) }
        c.getAttributeValue("loop")?.let { a.loop = bool(it) }
        c.getAttributeValue("random")?.let { a.random = bool(it) }
    }

    private fun parseAction(node: Element, processEvent: (Element, String) -> String): Action {
        val a = Action()
        for (c in node.children) {
            when (c.name) {
                "event" -> a.event = processEvent(c, "__customBackgroundObjectAction")
                "activate" -> a.selfOp = OP_ACTIVATE
                "deactivate" -> a.selfOp = OP_DEACTIVATE
                "toggle" -> a.selfOp = OP_TOGGLE
                "destroy" -> a.selfOp = OP_DESTROY
                "transform" -> {
                    val nd = parse(c, processEvent)
                    val chance = c.getAttributeValue("chance")?.let { int(it) } ?: 1
                    if (nd != null) a.newDef += nd to chance
                    a.newDefChance += chance
                }
                "noTransform" -> a.newDefChance += c.getAttributeValue("chance")?.let { int(it) } ?: 1
                "spawnObject" -> {
                    val d = parse(c, processEvent) ?: continue
                    val r = IntArray(4)
                    c.getAttributeValue("x")?.let { r[0] = int(it) }
                    c.getAttributeValue("y")?.let { r[1] = int(it) }
                    c.getAttributeValue("w")?.let { r[2] = int(it) }
                    c.getAttributeValue("h")?.let { r[3] = int(it) }
                    a.spawnObjects += d to r
                }
            }
        }
        return a
    }

    // ---- the run ----

    private fun list(): ArrayList<Obj>? = if (current == -1) null else objects[current]

    private fun indexOf(game: InGameState, beacon: Beacon): Int = beacon.sector.beacons.indexOf(beacon)

    /**
     * WorldManager::CreateLocation: currentLocationIndex becomes the shown beacon's; StarMap::GenerateMap (not
     * while loading) dropped every object when the sector changed.
     */
    @JvmStatic
    fun createLocation(game: InGameState) {
        bind(game)
        val beacon = game.currentBeacon
        if (sector !== beacon.sector) { objects.clear(); sector = beacon.sector }
        current = indexOf(game, beacon)
    }

    /** WorldManager::ClearLocation: the location's jumpClear objects go. */
    @JvmStatic
    fun clearLocation() {
        list()?.removeAll { it.def.jumpClear }
        current = -1
    }

    /** A new run (CustomEventsParser's reset). */
    @JvmStatic
    fun newGame() {
        objects.clear(); current = -1; sector = null; pending = null
    }

    /** CustomCreateLocation: clearBackgroundObject, transformBackgroundObject, then the event's objects. */
    @JvmStatic
    fun apply(game: InGameState, clear: List<String>, transform: List<Pair<String, String>>, create: List<Element>) {
        if (clear.isEmpty() && transform.isEmpty() && create.isEmpty()) return
        if (current == -1) createLocation(game) else bind(game)
        for (name in clear) {
            val l = objects.getOrPut(current) { ArrayList() }
            if (name.isEmpty()) l.clear() else l.removeAll { it.def.name == name }
        }
        for ((from, to) in transform) {
            val d = named[to] ?: continue
            for (o in objects.getOrPut(current) { ArrayList() }) if (o.def.name == from) { o.def = d; init(game, o) }
            needSort = true
        }
        for (e in create) byElement[e]?.let { createObject(game, it, it.spawnArea) }
    }

    /** CustomBackgroundObjectManager::CreateObject: at the area's corner, plus `random32() % (w + 1)` when w > 0. */
    private fun createObject(game: InGameState, def: Def, rect: IntArray) {
        if (current == -1) return
        val o = Obj(def)
        o.x = if (rect[2] == 0) rect[0] else rect[0] + ftl.browser.NativeRandom.next31() % (rect[2] + 1)
        o.y = if (rect[3] == 0) rect[1] else rect[1] + ftl.browser.NativeRandom.next31() % (rect[3] + 1)
        o.bActive = def.active
        objects.getOrPut(current) { ArrayList() } += o
        init(game, o)
        needSort = true
    }

    private fun animation(game: InGameState, a: AnimDef): FTLAnimation? {
        if (a.anim.isEmpty()) return null
        val spec = game.animations.getOrNull(a.anim) ?: run {
            HS.unsupported("event", "backgroundObject", "missing animation ${a.anim}"); return null
        }
        // Animation::Start(true) (or RandomStart: rand() % frames of the way through) with tracker.loop from the definition
        val anim = if (a.loop) spec.startLooping(game) else spec.startSingle(game)
        if (a.random) {
            val frames = spec.length
            anim.timer = (ftl.browser.NativeRandom.next31() % frames) * anim.duration / frames
            anim.update(0f)
        }
        return anim
    }

    /** CustomBackgroundObject::OnInit. */
    private fun init(game: InGameState, o: Obj) {
        val d = o.def
        fun pos(a: FTLAnimation?, set: (Float, Float) -> Unit) {
            val w = a?.width ?: 0; val h = a?.height ?: 0
            if (d.centerAnim) set((o.x - w / 2).toFloat(), (o.y - h / 2).toFloat()) else set(o.x.toFloat(), o.y.toFloat())
        }
        o.anim = animation(game, d.anim)
        pos(o.anim) { x, y -> o.animX = x; o.animY = y }
        val fw = o.anim?.width ?: 0; val fh = o.anim?.height ?: 0
        o.hitbox[2] = if (d.hitbox[2] == -1) fw else d.hitbox[2]
        o.hitbox[3] = if (d.hitbox[3] == -1) fh else d.hitbox[3]
        o.hitbox[0] = (o.animX + d.hitbox[0]).toInt()
        o.hitbox[1] = (o.animY + d.hitbox[1]).toInt()
        if (d.centerHitbox) { o.hitbox[0] += (fw - o.hitbox[2]) / 2; o.hitbox[1] += (fh - o.hitbox[3]) / 2 }
        o.hoverAnim = if (o.hasHoverAnim) animation(game, d.hoverAnim) else null
        pos(o.hoverAnim) { x, y -> o.hoverX = x; o.hoverY = y }
        o.inactiveAnim = if (o.hasInactiveAnim) animation(game, d.inactiveAnim) else null
        pos(o.inactiveAnim) { x, y -> o.inactiveX = x; o.inactiveY = y }
        // actionTimers: one per timerAction, started here (TimerHelper::Start(time))
        o.timers = ArrayList()
        for (t in d.timerActions) {
            val timer = BgTimer()
            if (t.time != -1f) { timer.minTime = (t.time * 1000).toInt(); timer.maxTime = timer.minTime }
            else { timer.minTime = t.minTime; timer.maxTime = t.maxTime }
            timer.loop = t.loop
            timer.start(t.time)
            o.timers += timer
        }
        updateReq(game, o)
    }

    private fun updateReq(game: InGameState, o: Obj) {
        val d = o.def
        if (d.req.isEmpty()) { o.reqActive = true; return }
        val player = game.player ?: return
        var lvl = 0
        HsEvents.withCheck(3) { lvl = HsReq.hasEquipment(game, player, d.req) }
        o.reqActive = lvl >= d.lvl && lvl <= d.maxLvl
    }

    private fun isActive(game: InGameState, o: Obj) =
        o.bActive && o.reqActive && (!o.def.disableCombat || !game.isInDanger)

    /** CustomBackgroundObjectManager::Sort: stable, by layer then z. */
    private fun sort() {
        list()?.sortWith(compareBy<Obj>({ it.def.layer }, { it.def.z }))
        needSort = false
    }

    /** SpaceManager::OnLoop (after the original): each object's OnLoop; destroyed ones go. */
    @JvmStatic
    fun update(game: InGameState, dt: Float) {
        if (pending != null) bind(game)
        val l = list() ?: return
        if (l.isEmpty()) return
        if (needSort) sort()
        val it = l.iterator()
        while (it.hasNext()) if (loop(game, it.next(), dt)) it.remove()
    }

    /** CustomBackgroundObject::OnLoop: true when the object was destroyed. */
    private fun loop(game: InGameState, o: Obj, dt: Float): Boolean {
        updateReq(game, o)
        val d = o.def
        val play = when {
            !isActive(game, o) -> d.inactiveAnim.play
            o.hover -> d.hoverAnim.play
            else -> d.anim.play
        }
        if (play) {
            o.anim?.update(dt)
            o.hoverAnim?.update(dt)
            o.inactiveAnim?.update(dt)
            val action = d.animAction
            if (action != null && o.anim?.isStopped == true) {
                val status = activate(game, o, action)
                if (status == RESULT_DESTROYED) return true
                if (status == RESULT_RESET) return false
            }
        }
        for (i in o.timers.indices) {
            val timer = o.timers[i]
            val t = d.timerActions[i]
            if ((t.requireActive == -1 || (if (isActive(game, o)) 1 else 0) == t.requireActive) &&
                (t.requireHover == -1 || (if (o.hover) 1 else 0) == t.requireHover)) {
                timer.update(dt)
                if (timer.done()) {
                    val status = activate(game, o, t.action)
                    if (status == RESULT_DESTROYED) return true
                    if (status == RESULT_RESET) break
                }
            }
        }
        return false
    }

    /** CustomBackgroundObject::Activate. */
    private fun activate(game: InGameState, o: Obj, action: Action): Int {
        if (action.event.isNotEmpty() && !game.isPlayerJumping) HsEvents.eventQueue.addLast(EventQueueEvent(action.event, "", -1))
        var status = RESULT_NOCHANGE
        when (action.selfOp) {
            OP_DESTROY -> status = RESULT_DESTROYED
            OP_ACTIVATE -> o.bActive = true
            OP_DEACTIVATE -> o.bActive = false
            OP_TOGGLE -> o.bActive = !o.bActive
        }
        for ((d, r) in action.spawnObjects) createObject(game, d, r)
        if (status != RESULT_DESTROYED) {
            val nd = action.randomNewDef()
            if (nd != null) {
                status = RESULT_RESET
                o.def = nd
                init(game, o)
                needSort = true
            }
        }
        return status
    }

    /**
     * CustomBackgroundObjectManager::OnRender for [layer] (the background, foreground and asteroid layers from the
     * environment, the player ones in the player ship's frame), tinted like the space behind (SpaceManager's
     * shade: [tint]).
     */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, layer: Int, tint: Colour) {
        if (pending != null) bind(game)
        val l = list() ?: return
        for (o in l) {
            if (o.def.layer != layer) continue
            when {
                !isActive(game, o) -> when {
                    o.def.hideInactive -> {}
                    o.hasInactiveAnim -> o.inactiveAnim?.draw(o.inactiveX, o.inactiveY, tint)
                    else -> o.anim?.draw(o.animX, o.animY, tint)
                }
                o.hasHoverAnim && o.hover -> o.hoverAnim?.draw(o.hoverX, o.hoverY, tint)
                else -> o.anim?.draw(o.animX, o.animY, tint)
            }
        }
    }

    /** MouseControl::SetTooltip from the last MouseMove (after the scene, so the HUD's own tooltips win). */
    @JvmStatic
    fun renderTooltip(g: Graphics) {
        if (tooltipText != null && !list().isNullOrEmpty()) g.tooltip = tooltip
    }

    /** The environment's passes: the backdrop, the foreground (planet, fleet), the back asteroids and (4) the front layer. */
    @JvmStatic
    fun renderSpace(game: InGameState, g: Graphics, phase: Int, tint: Colour) {
        if (pending != null) bind(game)
        if (list().isNullOrEmpty()) return
        when (phase) {
            1 -> render(game, g, LAYER_BACKGROUND, tint)
            2 -> render(game, g, LAYER_FOREGROUND, tint)
            3 -> render(game, g, LAYER_ASTEROIDS, tint)
            4 -> render(game, g, LAYER_FRONT, tint)
        }
    }

    /** CommandGui::RenderPlayerShip hook: LAYER_PLAYER before the ship, LAYER_PLAYERFRONT after, in its frame. */
    @JvmStatic
    fun renderPlayer(game: InGameState, g: Graphics, front: Boolean) {
        if (pending != null) bind(game)
        if (list().isNullOrEmpty()) return
        render(game, g, if (front) LAYER_PLAYERFRONT else LAYER_PLAYER, game.currentBeacon.getEnvironment(game).pauseShade(game))
    }

    @JvmStatic
    fun hasObjects(): Boolean = !list().isNullOrEmpty()

    // ---- the mouse (CombatControl::MouseMove / MouseClick hooks) ----

    private var tooltipText: String? = null
    private val tooltip by lazy {
        object : DelayedTooltip(game!!) {
            override fun getText(): String = tooltipText ?: ""
        }
    }

    private fun objMouseMove(game: InGameState, o: Obj, x: Int, y: Int, ship: IPoint): Boolean {
        var mx = x; var my = y
        if (o.def.layer == LAYER_PLAYER || o.def.layer == LAYER_PLAYERFRONT) { mx -= ship.x; my -= ship.y }
        val h = o.hitbox
        val ret = mx > h[0] && mx < h[0] + h[2] && my > h[1] && my < h[1] + h[3]
        o.hover = ret && isActive(game, o)
        return ret
    }

    private fun textOf(game: InGameState, o: Obj): String {
        val active = isActive(game, o)
        val text = if (active) o.def.tooltip else o.def.inactiveTooltip
        val id = if (active) o.def.tooltipId else o.def.inactiveTooltipId
        return if (id) game.translator[text] else text
    }

    /** CustomBackgroundObjectManager::MouseMove: true when the mouse is on a clickable object. */
    private fun managerMouseMove(game: InGameState, x: Int, y: Int, ship: IPoint): Boolean {
        val l = list() ?: return false
        if (needSort) sort()
        var ret = false
        var i = l.size - 1
        tooltipText = null
        while (i >= 0) {
            val o = l[i]
            if (objMouseMove(game, o, x, y, ship)) {
                ret = o.hover && o.def.clickAction != null
                val text = textOf(game, o)
                if (text.isNotEmpty()) { tooltipText = text; i--; break }
                if (ret) { i--; break }
            }
            i--
        }
        if (!ret) {
            while (i >= 0) {
                val o = l[i]
                if (objMouseMove(game, o, x, y, ship) && o.hover && o.def.clickAction != null) {
                    ret = true
                    val text = textOf(game, o)
                    if (text.isNotEmpty()) tooltipText = text
                    i--
                    break
                }
                i--
            }
        }
        while (i >= 0) { l[i].hover = false; i-- }
        return ret
    }

    /**
     * The CombatControl::MouseMove hook: [combatTook] is the original's result (an armed weapon, teleporter, mind
     * control or hacking aim); otherwise the objects get the mouse.
     */
    @JvmStatic
    fun mouseMove(game: InGameState, x: Int, y: Int, engineShip: IPoint, combatTook: Boolean) {
        if (pending != null) bind(game)
        // CombatControl::playerShipPosition: the engine's, or the point a script wrote into it
        val ship = xyz.znix.xftl.hs.lua.LuaAdaptersSweepWorld.writtenShipPosition(game) ?: engineShip
        if (combatTook || list().isNullOrEmpty()) {
            if (combatTook) mouseActive = false
            tooltipText = null
            list()?.forEach { it.hover = false }
            return
        }
        mouseActive = true
        managerMouseMove(game, x, y, ship)
    }

    /** The CombatControl::MouseClick hook: the first hovered, active, clickable object acts. */
    @JvmStatic
    fun mouseClick(game: InGameState, x: Int, y: Int, engineShip: IPoint) {
        if (!mouseActive) return
        val ship = xyz.znix.xftl.hs.lua.LuaAdaptersSweepWorld.writtenShipPosition(game) ?: engineShip
        if (!managerMouseMove(game, x, y, ship)) return
        val l = list() ?: return
        for (k in l.indices) {
            val o = l[k]
            val action = o.def.clickAction
            if (o.hover && action != null && isActive(game, o)) {
                if (activate(game, o, action) == RESULT_DESTROYED) l.removeAt(k)
                break
            }
        }
        if (needSort) sort()
    }

    // ---- save (the run block: per beacon index, each object's definition, position, state and anim time) ----

    @JvmStatic
    fun write(elem: Element) {
        // A loaded block not yet made into objects (no frame has run since the load) is written back as it was read.
        pending?.let { p ->
            for (loc in p.getChildren("loc")) elem.addContent(loc.clone())
            p.getAttributeValue("current")?.let { elem.setAttribute("current", it) }
            return
        }
        for ((idx, l) in objects) {
            val loc = Element("loc").setAttribute("idx", idx.toString())
            for (o in l) {
                val e = Element("o").setAttribute("def", o.def.idx.toString()).setAttribute("name", o.def.name)
                    .setAttribute("x", o.x.toString()).setAttribute("y", o.y.toString())
                    .setAttribute("req", o.reqActive.toString()).setAttribute("active", o.bActive.toString())
                o.anim?.let { e.setAttribute("t", it.timer.toString()) }
                o.hoverAnim?.let { e.setAttribute("th", it.timer.toString()) }
                o.inactiveAnim?.let { e.setAttribute("ti", it.timer.toString()) }
                if (o.timers.isNotEmpty()) e.setAttribute("tm", o.timers.joinToString(";") { t ->
                    "${t.maxTime},${t.minTime},${t.currTime},${t.currGoal},${t.loop},${t.running}" })
                loc.addContent(e)
            }
            elem.addContent(loc)
        }
        current.takeIf { it != -1 }?.let { elem.setAttribute("current", it.toString()) }
    }

    /** A loaded run block, made into objects once the game is there (the animations need it). */
    private var pending: Element? = null

    @JvmStatic
    fun read(elem: Element?) {
        objects.clear(); needSort = false; sector = null
        current = elem?.getAttributeValue("current")?.toIntOrNull() ?: -1
        pending = elem
    }

    /** The loaded objects, made for [g] (called by every entry point that has the game). */
    @JvmStatic
    fun bind(g: InGameState) {
        game = g
        val elem = pending ?: return
        pending = null
        sector = runCatching { g.currentBeacon.sector }.getOrNull()
        for (loc in elem.getChildren("loc")) {
            val l = objects.getOrPut(loc.getAttributeValue("idx").toInt()) { ArrayList() }
            for (e in loc.getChildren("o")) {
                val name = e.getAttributeValue("name") ?: ""
                val d = (if (name.isNotEmpty()) named[name] else null) ?: e.getAttributeValue("def")?.toIntOrNull()?.let { defs.getOrNull(it) } ?: continue
                val o = Obj(d)
                o.x = e.getAttributeValue("x").toInt(); o.y = e.getAttributeValue("y").toInt()
                init(g, o)
                o.reqActive = e.getAttributeValue("req") == "true"
                o.bActive = e.getAttributeValue("active") == "true"
                e.getAttributeValue("t")?.toFloatOrNull()?.let { t -> o.anim?.timer = t }
                e.getAttributeValue("th")?.toFloatOrNull()?.let { t -> o.hoverAnim?.timer = t }
                e.getAttributeValue("ti")?.toFloatOrNull()?.let { t -> o.inactiveAnim?.timer = t }
                // actionTimers: the saved ones overwrite the freshly started (only as many as both have)
                e.getAttributeValue("tm")?.split(';')?.forEachIndexed { i, s ->
                    val p = s.split(',')
                    val t = o.timers.getOrNull(i) ?: return@forEachIndexed
                    if (p.size == 6) {
                        t.maxTime = p[0].toInt(); t.minTime = p[1].toInt(); t.currTime = p[2].toFloat(); t.currGoal = p[3].toFloat()
                        t.loop = p[4] == "true"; t.running = p[5] == "true"
                    }
                }
                l += o
            }
        }
    }

    @JvmStatic
    fun registerSave() {
        xyz.znix.xftl.hs.save.HsSave.register("backgroundObjects", xyz.znix.xftl.hs.save.HsSave.Scope.RUN, ::write, ::read)
    }

    @JvmStatic
    fun capabilityNames() = arrayOf("backgroundObject", "clearBackgroundObject", "transformBackgroundObject")
}
