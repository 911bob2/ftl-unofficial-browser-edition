package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.HsCloneQueue
import xyz.znix.xftl.hs.crew.HsCrewTooltips
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * WP3 crew UI checks (`BrowserEngine.hsCrewUiProbe`), for vanilla and Hyperspace sessions alike:
 * - `move`: right-click orders through PlayerShipUI's real click path. Vanilla (and HS with alternateCrewMovement
 *   off): CommandCrewMoveRoom leaves a crew member whose slot is already in the room; a right click on a door
 *   toggles it and orders nobody (CrewControl::RButton 0x502b10). HS with alternateCrewMovement (MV): the clicked
 *   tile, swaps inside a room and from another room (TileBasedCrewMovement.cpp).
 *   HS also checks the crew list reorder: the dragged order survives a save and load (native oracle ro-main, ro-sq,
 *   ro-sq2: HS 1.23.2 saves the crew in CrewMemberFactory order).
 * - `tooltip`: the crew hover tooltip (vanilla text, or HS advancedCrewTooltips / the multi-crew separator).
 * - `clone` (HS): the enhancedCloneUI queue paging, pick-up and reorder, height modifier, a rendered frame.
 * - `lua` (HS with Lua): CONSTRUCT_CREWMEMBER, CREW_CLONE and the CREW_MEMBER_HEALTH render event fire.
 * Returns "PASS ..." or throws; leaves the engine at the main menu.
 */
object BrowserHsCrewUiDiagnostics {
    private const val DT = .05f

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, part: String): String {
        val rng = BrowserRandom.snapshot()
        val out = ArrayList<String>()
        try {
            when (part) {
                "move" -> { move(main, container, out); if (HS.active) reorder(main, container, out) }
                "tooltip" -> tooltip(main, container, out)
                "clone" -> clone(main, container, graphics, out)
                "lua" -> lua(main, container, graphics, out)
                else -> error("unknown part $part")
            }
        } catch (e: Throwable) {
            throw IllegalStateException("after [" + out.joinToString("; ") + "]: " + e + " " + e.stackTrace.take(6).joinToString(" < "), e)
        } finally {
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
        return "PASS hs-crewui $part (hs=${HS.active}): " + out.joinToString("; ")
    }

    internal fun start(main: MainGame, container: GameContainer, ship: String): InGameState {
        main.startDiagnosticGame(ship, Difficulty.EASY)
        // The start beacon's event chain (Multiverse has several boxes): answer each with its last choice.
        repeat(60) {
            main.update(container, DT)
            if ((main.currentState as InGameState).shipUI.isWindowOpen) {
                BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
            }
        }
        repeat(200) {
            val state = main.currentState as InGameState
            if (!state.shipUI.isWindowOpen) return@repeat
            val d = state.shipUI.diagnosticDialogue
            if (d != null) d.selectOption(maxOf(0, d.optionCount - 1)) else state.shipUI.escapePressed()
            main.update(container, DT)
        }
        val state = main.currentState as InGameState
        check(!state.shipUI.isWindowOpen) { "a window stays open at the start: ${state.shipUI.diagnosticWindow} ${state.shipUI.diagnosticWindowBounds}" }
        if (state.isPaused) state.toggleQuickPause()
        return state
    }

    private fun settle(state: InGameState, seconds: Float = 10f) {
        var t = 0f
        while (t < seconds) { state.updateGameState(DT); t += DT }
    }

    /** A right press and release at ship render pixel (x, y), sent at its screen position like InGameState does. */
    private fun rightClick(state: InGameState, x: Int, y: Int) {
        val o = xyz.znix.xftl.math.Point(0, 0)
        state.convertScreenToPlayerShip(o)
        val offset = ConstPoint(-o.x, -o.y)
        state.shipUI.updateUI(x + offset.x, y + offset.y, offset)
        state.shipUI.mouseClick(Input.MOUSE_RIGHT_BUTTON, x + offset.x, y + offset.y, offset)
        state.shipUI.mouseUp(Input.MOUSE_RIGHT_BUTTON, x + offset.x, y + offset.y, offset)
    }

    private fun cellX(room: Room, cx: Int) = room.offsetX + cx * 35 + 17
    private fun cellY(room: Room, cy: Int) = room.offsetY + cy * 35 + 17

    private fun slotOf(c: AbstractCrew): String {
        val p = c.pathingTarget ?: c.standingPosition ?: return "moving"
        return "${p.room.id}:${p.x},${p.y}"
    }

    private fun own(state: InGameState): List<LivingCrew> =
        state.player.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === state.player && it.playerControllable }

    private fun move(main: MainGame, container: GameContainer, out: ArrayList<String>) {
        val state = start(main, container, "PLAYER_SHIP_HARD")
        val player = state.player
        val tile = HS.active && HS.config?.alternateCrewMovement == true
        val crew = own(state)
        check(crew.size >= 3) { "need three crew, have ${crew.size}" }
        val (a, b, c) = Triple(crew[0], crew[1], crew[2])
        // A 2x2 room without crew and without a console at (1,1) or (0,0).
        val r = player.rooms.firstOrNull { room ->
            room.width == 2 && room.height == 2 && player.crew.none { it.room === room || it.pathingTarget?.room === room } &&
                room.system?.configuration?.computerPoint.let { it == null }
        } ?: error("no empty 2x2 room without a console")
        out += "room ${r.id}"

        // 1. A right click on the room's (1,1) tile.
        state.shipUI.diagnosticSelectCrew(listOf(a))
        rightClick(state, cellX(r, 1), cellY(r, 1))
        settle(state)
        val want1 = if (tile) "${r.id}:1,1" else "${r.id}:0,0"
        check(slotOf(a) == want1) {
            "A after click (1,1): ${slotOf(a)} want $want1 (window ${state.shipUI.isWindowOpen}, paused ${state.isPaused}, " +
                "orderable ${a.canBeOrderedToMove}, controllable ${a.playerControllable}, tile mode $tile)"
        }
        out += "A to ${slotOf(a)}"

        // 2. B to (0,0); then A clicks B's tile: a swap inside the room (HS), nothing (vanilla: A's slot is in the room).
        state.shipUI.diagnosticSelectCrew(listOf(b))
        rightClick(state, cellX(r, 0), cellY(r, 0))
        settle(state)
        check(slotOf(b).startsWith("${r.id}:")) { "B not in the room: ${slotOf(b)}" }
        val bBefore = slotOf(b)
        if (tile) check(bBefore == "${r.id}:0,0") { "B after click (0,0): $bBefore" }
        val aBefore = slotOf(a)
        state.shipUI.diagnosticSelectCrew(listOf(a))
        val bp = (b.pathingTarget ?: b.standingPosition)!!
        rightClick(state, cellX(r, bp.x), cellY(r, bp.y))
        settle(state)
        if (tile) {
            check(slotOf(a) == bBefore && slotOf(b) == aBefore) { "swap: A ${slotOf(a)} B ${slotOf(b)} (were $aBefore / $bBefore)" }
            out += "swap in room A=${slotOf(a)} B=${slotOf(b)}"
        } else {
            check(slotOf(a) == aBefore && slotOf(b) == bBefore) { "same-room order moved: A ${slotOf(a)} B ${slotOf(b)}" }
            out += "same-room order leaves A and B"
        }

        // 3. C (outside the room) is ordered to the room twice before it arrives: the second order leaves its goal
        // (MoveToRoom(room, -1, false) with its slot already in the room; vanilla and HS).
        check(!slotOf(c).startsWith("${r.id}:")) { "C already in the room: ${slotOf(c)}" }
        state.shipUI.diagnosticSelectCrew(listOf(c))
        rightClick(state, cellX(r, 0), cellY(r, 1))
        val cGoal = slotOf(c)
        check(cGoal == "${r.id}:0,1") { "C ordered to $cGoal" }
        state.updateGameState(DT)
        rightClick(state, cellX(r, 0), cellY(r, 1))
        check(slotOf(c) == cGoal) { "second order moved C from $cGoal to ${slotOf(c)}" }
        settle(state)
        check(slotOf(c) == cGoal) { "C arrived at ${slotOf(c)} not $cGoal" }
        out += "second order keeps C's goal $cGoal"

        // 4. A (sent out of the room) clicks C's tile: C moves to the room's free slot, A takes the tile (HS).
        if (tile) {
            val away = player.rooms.first { it !== r && it.anySlotsFree(AbstractCrew.SlotType.CREW) }
            check(a.setTargetRoom(away)) { "A cannot leave" }
            settle(state)
            val cp = (c.pathingTarget ?: c.standingPosition)!!
            val cTile = slotOf(c)
            state.shipUI.diagnosticSelectCrew(listOf(a))
            rightClick(state, cellX(r, cp.x), cellY(r, cp.y))
            settle(state)
            check(slotOf(a) == cTile && slotOf(c).startsWith("${r.id}:") && slotOf(c) != cTile) {
                "swap from outside: A ${slotOf(a)} C ${slotOf(c)} (tile $cTile)"
            }
            out += "swap from outside A=${slotOf(a)} C=${slotOf(c)}"
        }

        // 5. A right click on a closed door toggles it when no crew is selected; with a selection it orders the crew
        // and leaves the door (native capture wp3-rclick).
        val door = player.doors.firstOrNull { !it.isAirlock && !it.open } ?: error("no closed inner door")
        val dx = door.offsetX + if (door.isVertical) 0 else 17
        val dy = door.offsetY + if (door.isVertical) 17 else 0
        state.shipUI.diagnosticSelectCrew(emptyList())
        rightClick(state, dx, dy)
        check(door.open) { "door ${door.position} not opened by the right click (vertical=${door.isVertical})" }
        rightClick(state, dx, dy)
        check(!door.open) { "second right click did not close the door" }
        state.shipUI.diagnosticSelectCrew(listOf(c))
        rightClick(state, dx, dy)
        check(!door.open) { "right click with a crew member selected toggled the door" }
        out += "door right click toggles without a selection, orders with one"
    }

    /** HS: a crew list reorder (MoveCrewMemberToSpot) shows at once and stays after a save and load. */
    private fun reorder(main: MainGame, container: GameContainer, out: ArrayList<String>) {
        val state = start(main, container, "PLAYER_SHIP_HARD")
        state.updatePlayerCrew()
        val names = { s: InGameState -> s.shipUI.touchBoxCrew.map { it.info.name } }
        val before = names(state)
        check(before.size >= 3) { "need three crew in the list, have $before" }
        val first = state.shipUI.touchBoxCrew[0]
        check(state.shipUI.touchCrewMoveToSpot(first, 2)) { "the first crew member did not move" }
        val moved = names(state)
        val want = listOf(before[1], before[2], before[0]) + before.drop(3)
        check(moved == want) { "reordered list $moved, want $want" }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        val loaded = main.currentState as InGameState
        loaded.updatePlayerCrew()
        val after = names(loaded)
        check(after == want) { "after a save and load the list is $after, want $want" }
        out += "reorder ${before.joinToString(",")} -> ${after.joinToString(",")}"
    }

    private fun tooltip(main: MainGame, container: GameContainer, out: ArrayList<String>) {
        val state = start(main, container, "PLAYER_SHIP_HARD")
        settle(state, 1f)
        val a = own(state).first()
        val text = state.shipUI.diagnosticCrewTooltip(state.player, a.getPixelPositionCentre())
        val name = a.info.name
        if (HS.active && HS.config?.advancedCrewTooltips == true) {
            val race = a.info.race.translateTitle(state)
            val max = a.maxHealth.toInt()
            // Player crew list their race's powers while the More Info mode is on (the default; oracle wp3-coords).
            val powers = if (xyz.znix.xftl.hs.render.HsMoreInfo.showAllyPowers())
                a.info.race.powerStringIds.joinToString("") { "\n*" + state.translator[it] }.trimEnd() else ""
            val want = "-$name ($race):\n" + state.translator["advanced_health_tooltip"] + ": $max/$max (100%)" + powers
            check(text == want) { "advanced tooltip ${q(text)} want ${q(want)}" }
            a.health = a.maxHealth / 3f
            val hurt = state.shipUI.diagnosticCrewTooltip(state.player, a.getPixelPositionCentre())
            check(hurt.contains("/$max (33%)")) { "hurt tooltip ${q(hurt)}" }
            a.stun(2f)
            val stunned = state.shipUI.diagnosticCrewTooltip(state.player, a.getPixelPositionCentre())
            check(stunned.lines().size == 3 + powers.lines().size - 1 && stunned.lines()[2].startsWith("Stunned: 2.0")) { "stunned tooltip ${q(stunned)}" }
            out += "advanced ${q(text)} / ${q(hurt)} / ${q(stunned)}"
        } else {
            check(text.startsWith("$name \n")) { "vanilla tooltip ${q(text)}" }
            out += "vanilla ${q(text)}"
        }
        val sep = HsCrewTooltips.separator()
        val wantSep = if (HS.active && HS.config?.insertNewlineForMultipleCrewTooltips == true) " \n\n" else " \n"
        check(sep == wantSep) { "separator ${q(sep)}" }
        out += "separator ${q(sep)}"
        // Hyperspace's More Info button: the toggle and the crew and weapon tooltips on and off.
        if (HS.active) out += BrowserHsMoreInfoDiagnostics.run(main, container, state)
    }

    private fun clone(main: MainGame, container: GameContainer, graphics: Graphics, out: ArrayList<String>) {
        check(HS.active && HsCloneQueue.enabled()) { "clone needs HS with enhancedCloneUI" }
        val state = start(main, container, "PLAYER_SHIP_KESTREL_3")
        val player = state.player
        val cb = player.clonebay ?: error("no clonebay")
        // Five crew in the queue (taken off the ship).
        val extra = ArrayList<LivingCrew>()
        repeat(5) {
            val info = xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint, state)
            extra += player.addCrewMemberInRoom(info, player.rooms[0], false)
        }
        for (x in extra) player.crew.remove(x)
        player.updateCrewReservedSlots()
        cb.queue.clear(); cb.queue.addAll(extra)
        check(HsCloneQueue.extraHeight(cb) == 15) { "height ${HsCloneQueue.extraHeight(cb)}" }
        check(HsCloneQueue.displayShift(cb) == 0 && cb.hsPageUp && !cb.hsPageDown) { "paging at start ${cb.hsPageDown}/${cb.hsPageUp}" }
        val power = ConstPoint(300, 600)
        val btn = HsCloneQueue.QueueButton(state, cb, power)
        val py = power.y - 19 - 8 * maxOf(cb.energyLevels, 1) - 77
        // pageUp (x + 13): one step on
        btn.update(power.x + 15, py + 5); btn.mouseDown(Input.MOUSE_LEFT_BUTTON, power.x + 15, py + 5)
        check(HsCloneQueue.displayShift(cb) == 1 && cb.hsPageDown) { "after page up shift ${cb.hsDisplayShift}" }
        btn.update(power.x + 15, py + 6); btn.mouseDown(Input.MOUSE_LEFT_BUTTON, power.x + 15, py + 6)
        check(HsCloneQueue.displayShift(cb) == 2 && !cb.hsPageUp) { "after page up 2 shift ${cb.hsDisplayShift}" }
        btn.update(power.x + 1, py + 5); btn.mouseDown(Input.MOUSE_LEFT_BUTTON, power.x + 1, py + 5)
        check(HsCloneQueue.displayShift(cb) == 1) { "after page down shift ${cb.hsDisplayShift}" }
        out += "paging 0>1>2>1"
        // Pick up the bottom shown entry (queue[1]) and move it up two boxes: iDeathNumber order puts it at 2.
        val bottom = power.y - 19 - (maxOf(cb.energyLevels, 1) - 1) * 8
        val order = cb.queue.toList()
        btn.update(power.x, bottom - 5)
        check(cb.hsHoverCrew == 1) { "hover ${cb.hsHoverCrew}" }
        check(btn.mouseDown(Input.MOUSE_RIGHT_BUTTON, power.x, bottom - 5) && cb.hsSelectedCrew === order[1]) { "pick up" }
        cb.cloneProgress = .5f
        btn.update(power.x, bottom - 23 * 2 - 5)
        // numbers: q0=0, q1 (carried)=1+2=3, q2..: from 3 on +1 -> q2=2, q3=4, q4=5
        val want = listOf(order[0], order[2], order[1], order[3], order[4])
        check(cb.queue == want) { "reorder ${cb.queue.map { order.indexOf(it) }}" }
        check(cb.cloneProgress == .5f) { "front unchanged, progress kept" }
        out += "carried 1 -> ${cb.queue.map { order.indexOf(it) }}"
        // Shown from the front, down past the bottom box (boxIdx 0 after C++'s truncating division): the carried crew
        // member goes first, the front changes and the cloning starts over.
        cb.hsDisplayShift = 0
        btn.update(power.x, bottom + 30)
        check(cb.queue.first() === order[1] && cb.cloneProgress == 0f) { "to the front ${cb.queue.map { order.indexOf(it) }} progress ${cb.cloneProgress}" }
        HsCloneQueue.rButtonUp(player)
        check(cb.hsSelectedCrew == null) { "release" }
        out += "to the front resets progress, release drops"
        main.render(container, graphics)
        out += "frame rendered"
    }

    private fun lua(main: MainGame, container: GameContainer, graphics: Graphics, out: ArrayList<String>) {
        check(HS.luaActive) { "lua needs HS Lua" }
        val state = start(main, container, "PLAYER_SHIP_KESTREL_3")
        val lines = ArrayList<String>()
        xyz.znix.xftl.hs.lua.HsLua.sink = { _, t -> if (t.startsWith("WP3")) lines += t.trim() }
        try {
            xyz.znix.xftl.hs.lua.HsLua.runString("wp3", """
                WP3 = {cc = 0, clone = 0, pre = 0, post = 0}
                script.on_internal_event(Defines.InternalEvents.CONSTRUCT_CREWMEMBER, function(c) WP3.cc = WP3.cc + 1 end)
                script.on_internal_event(Defines.InternalEvents.CREW_CLONE, function(c) WP3.clone = WP3.clone + 1 end)
                script.on_render_event(Defines.RenderEvents.CREW_MEMBER_HEALTH, function(c) WP3.pre = WP3.pre + 1 end, function(c) WP3.post = WP3.post + 1 end)
            """.trimIndent())?.let { error("load: $it") }
            val player = state.player
            // The CrewMember / CrewControl / CrewFactory members (MV auto-manning-fed.lua reads bActiveManning,
            // Functional and iManningId; her-cursor.lua clears crewControl.selectedCrew). SWIG pushes sizes as floats.
            repeat(100) { state.updateGameState(DT) }
            val crew0 = own(state)
            state.shipUI.diagnosticSelectCrew(listOf(crew0.first()))
            xyz.znix.xftl.hs.lua.HsLua.runString("wp3-members", LUA_MEMBERS)?.let { error("members: $it") }
            val m = lines.lastOrNull { it.startsWith("WP3L ") }?.split(' ') ?: error("no member dump: $lines")
            check(m[1].toInt() == player.crew.size) { "vCrewList ${m[1]} vs ${player.crew.size}" }
            check(m[2].toInt() >= 1) { "no crew manning: ${lines.last()}" }
            check(m[3].toDouble() == 1.0) { "selectedCrew ${m[3]}" }
            check(m[4].toDouble() >= player.crew.size && m[5].toDouble() == 0.0) { "factory ${m[4]} / clone list ${m[5]}" }
            check(m[6].toDouble() == 0.0) { "selectedCrew:clear() left ${m[6]}" }
            check(state.shipUI.luaSelectedCrew.isEmpty()) { "selection not cleared" }
            out += "members: " + lines.last().substring(5)
            val info = xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint, state)
            val added = player.addCrewMemberInRoom(info, player.rooms[0], false)
            main.render(container, graphics)
            val cb = player.clonebay ?: error("no clonebay")
            player.crew.remove(added); player.updateCrewReservedSlots()
            cb.queue.clear(); cb.queue.add(added)
            cb.cloneProgress = .999f
            repeat(20) { state.updateGameState(DT) }
            check(cb.queue.isEmpty()) { "not cloned" }
            xyz.znix.xftl.hs.lua.HsLua.runString("wp3-dump", "print('WP3 ' .. WP3.cc .. ' ' .. WP3.clone .. ' ' .. WP3.pre .. ' ' .. WP3.post)")?.let { error("dump: $it") }
        } finally {
            xyz.znix.xftl.hs.lua.HsLua.sink = null
        }
        val p = lines.lastOrNull()?.split(' ') ?: error("no dump")
        check(p[1].toInt() >= 1) { "CONSTRUCT_CREWMEMBER ${p[1]}" }
        check(p[2].toInt() == 1) { "CREW_CLONE ${p[2]}" }
        check(p[3].toInt() >= 1 && p[3] == p[4]) { "CREW_MEMBER_HEALTH pre ${p[3]} post ${p[4]}" }
        out += "construct ${p[1]}, clone ${p[2]}, health pre/post ${p[3]}/${p[4]}"
    }

    private val LUA_MEMBERS = """
        local ship = Hyperspace.Global.GetInstance():GetShipManager(0)
        local n, man, names = 0, 0, ""
        for i = 0, ship.vCrewList:size() - 1 do
            local c = ship.vCrewList[i]
            n = n + 1
            if c.bActiveManning and c:Functional() then man = man + 1 end
            names = names .. c:GetName() .. "/" .. c.blueprint.name .. "/" .. c:GetSkillLevel(0) .. "/" .. tostring(c:CanMan()) .. "/" .. c.iManningId .. "/" .. c.currentSlot.roomId .. ";"
        end
        local cc = Hyperspace.App.gui.crewControl
        local sel = cc.selectedCrew:size()
        cc.selectedCrew:clear()
        print('WP3L ' .. n .. ' ' .. man .. ' ' .. sel .. ' ' .. Hyperspace.CrewFactory.crewMembers:size() .. ' ' .. Hyperspace.CrewFactory:GetCloneReadyList(true):size() .. ' ' .. cc.selectedCrew:size() .. ' ' .. names)
    """.trimIndent()

    private fun q(s: String) = BrowserDebug.quote(s)
}
