package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.SystemButtonTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Parity sweep U1, the in-flight HUD with the mouse: the rules behind the frame pairs of
 * tools/native-capture/sessions/ps-u1-hud.json (native FTLGame 1.6.14 against the port, docs/parity/README.md).
 */
internal object GameplayHudU1Checks {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            case("rightClickDisarms") { rightClickDisarms(main, container, graphics) }
            case("pausedCrewHover") { pausedCrewHover(main, container, graphics) }
            case("droneTooltip") { droneTooltip(main, container, graphics) }
            case("systemTooltipSpaces") { systemTooltipSpaces(main, container) }
            case("godReactor") { godReactor(main, container) }
            case("subsystemTooltipWins") { subsystemTooltipWins(main, container, graphics) }
            case("hullWarningOnce") { hullWarningOnce(main, container, graphics) }
            case("systemBoxHitAreas") { systemBoxHitAreas(main, container, graphics) }
            case("subsystemsLabelTooltip") { subsystemsLabelTooltip(main, container, graphics) }
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "hud u1 ok: " + results.joinToString(", ")
    }

    private fun paused(main: MainGame, container: GameContainer, graphics: Graphics): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (!state.isPaused) state.toggleQuickPause()
        main.render(container, graphics)
        return state
    }

    private fun press(main: MainGame, container: GameContainer, x: Int, y: Int, button: Int) {
        BrowserEngine.mouse(x, y, 0, 0); main.update(container, 0f)
        BrowserEngine.mouse(x, y, 1, button); main.update(container, 0f)
        BrowserEngine.mouse(x, y, 2, button); main.update(container, 0f)
    }

    /**
     * CommandGui::RButtonDown (1.6.14 0x5334f0): a right press that drops an armed weapon (CombatControl::MouseRClick,
     * 0x581520, returned true) reaches neither SystemControl nor CrewControl. Frame pair u1v-a a-shields-down: the
     * Kestrel's shields keep their power under that press; the next right press takes a bar.
     */
    private fun rightClickDisarms(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = paused(main, container, graphics)
        val ship = state.player
        val shields = ship.shields ?: error("no shields")
        val slot = ship.hardpoints.indexOfFirst { it.weapon != null }
        check(slot >= 0) { "no weapon" }
        val weapon = ship.hardpoints[slot].weapon!!
        if (!weapon.isPowered) {
            ship.purchasedReactorPower = 25
            ship.weapons!!.energyLevels = 8
            ship.updateAvailablePower()
            check(ship.weapons!!.setWeaponPower(weapon, true) && weapon.isPowered) { "${weapon.type.name} did not power" }
        }
        state.shipUI.weaponHotkeyPressed(slot, false)
        check(state.clickEvent != null) { "weapon $slot did not arm" }
        val before = shields.powerSupplied
        check(before > 0) { "shields unpowered" }
        // The Kestrel's shields box (native frame a-h-sys-shields)
        press(main, container, 89, 683, Input.MOUSE_RIGHT_BUTTON)
        check(state.clickEvent == null) { "the right press left the weapon armed" }
        check(shields.powerSupplied == before) { "the disarming press changed the shields' power: $before -> ${shields.powerSupplied}" }
        press(main, container, 89, 683, Input.MOUSE_RIGHT_BUTTON)
        check(shields.powerSupplied < before) { "a right press with nothing armed did not depower the shields" }
        return "disarm only"
    }

    /**
     * Crew who join while paused have working boxes at once (native frame pair u1v-d d-h-crew5: the console's new crew
     * show their skills panel under the mouse before the game runs again).
     */
    private fun pausedCrewHover(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = paused(main, container, graphics)
        val before = state.player.crew.size
        repeat(4) { xyz.znix.xftl.hs.render.HsConsole.run(state, "CREW human") }
        val added = state.player.crew.drop(before)
        check(added.size == 4) { "CREW added ${added.size}" }
        main.render(container, graphics)
        val newest = added.last()
        val index = state.shipUI.touchBoxCrew.indexOfFirst { it === newest }
        check(index >= 0) { "the new crew member has no box" }
        // CrewBox spacing 30 from y 155 (native frame d-h-crew8)
        BrowserEngine.mouse(50, 168 + 30 * index, 0, 0); main.update(container, 0f)
        check(state.isPaused) { "the game ran" }
        check(state.shipUI.touchPotential.any { it === newest }) { "box $index of a crew member added while paused takes no hover" }
        return "paused crew hover"
    }

    /**
     * DroneBox's tooltip (1.6.14 0x56d960): the description, " \n\n" and the drone's state, then the hotkey and the
     * rearrange line (native frame c-h-x1).
     */
    private fun droneTooltip(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = paused(main, container, graphics)
        xyz.znix.xftl.hs.render.HsConsole.run(state, "SYS drones")
        xyz.znix.xftl.hs.render.HsConsole.run(state, "DRONE COMBAT_1")
        state.shipUI.updateButtons()
        main.render(container, graphics)
        val tr = state.translator
        val text = state.shipUI.diagnosticSlotTooltip(true, 0) ?: error("no tooltip on the first drone slot")
        val desc = (state.blueprintManager["COMBAT_1"] as xyz.znix.xftl.Blueprint).desc?.let { tr[it] } ?: error("COMBAT_1 has no description")
        val want = desc + " \n\n" + tr["undeployed"] + "\n\n" + tr["hotkey"].substringBefore("\\1")
        check(text.startsWith(want) && text.endsWith("\n" + tr["rearrange"])) { "drone tooltip: " + text.replace("\n", "|") }
        check(state.shipUI.diagnosticSlotTooltip(true, 1) == null) { "the empty drone slot has a tooltip" }
        return "drone tooltip"
    }

    /**
     * The system tooltip (0x4df760) joins its parts with " \n\n" and ends each bullet with " \n", so the box is measured
     * with those spaces (native frame a-h-sub-doors: 6 px wider than without).
     */
    private fun systemTooltipSpaces(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val tr = state.translator
        val doors = state.player.doorsSystem ?: error("no doors")
        val lines = SystemButtonTooltip(state, doors).getText().split("\n")
        check(lines[0] == tr["tooltip_doors"] + " " && lines[1] == "") { "description line ${lines[0]}|" }
        check(lines.contains(tr["status"]) && lines.contains("-" + tr["full_functional"] + " ")) { "status lines " + lines.joinToString("|") }
        return "system tooltip spaces"
    }

    /**
     * CommandGui::MouseMove (HS 1.6.9 0x5217c0) gives the mouse to CombatControl and then SystemControl, so a subsystem
     * box under a drone box keeps its hover and its tooltip wins (oracle ps-u1-d5 d5-h4). Ten systems put the Kestrel's
     * drone box over its piloting and sensors boxes (native frame u1v-b b-all).
     */
    private fun subsystemTooltipWins(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = paused(main, container, graphics)
        for (s in listOf("drones", "teleporter", "cloaking", "battery", "mind", "hacking"))
            xyz.znix.xftl.hs.render.HsConsole.run(state, "SYS $s")
        xyz.znix.xftl.hs.render.HsConsole.run(state, "DRONE COMBAT_1")
        xyz.znix.xftl.hs.render.HsConsole.run(state, "DRONE DEFENSE_1")
        state.shipUI.updateButtons()
        main.render(container, graphics)
        val (p, droneTip) = state.shipUI.diagnosticSubsystemUnderDrone() ?: error("no subsystem box under a drone box with a drone")
        BrowserEngine.mouse(p.x, p.y, 0, 0); main.update(container, 0f)
        main.render(container, graphics)
        val tip = graphics.tooltip
        check(tip != null && tip !== droneTip) { "at ${p.x},${p.y} the drone box's tooltip covers the subsystem's" }
        val text = (tip as? xyz.znix.xftl.rendering.StandardTooltip)?.getText() ?: ""
        check(text.contains(state.translator["status"])) { "the tooltip at ${p.x},${p.y} is not a system's: " + text.replace("\n", "|") }
        return "subsystem tooltip"
    }

    /**
     * One hull WarningMessage (ShipStatus::OnInit, 6 s): a second fall within its time changes the text and keeps the
     * timer (WarningMessage::Start does nothing while it runs; native frame pair u1v-e e-hull10); below a quarter it
     * loops, and a repair above a quarter stops it.
     */
    private fun hullWarningOnce(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val tr = state.translator
        ship.health = ship.maxHealth
        main.render(container, graphics)
        val hull = { n: Int -> xyz.znix.xftl.hs.render.HsConsole.run(state, "HULL $n"); main.render(container, graphics) }
        hull(-(ship.maxHealth / 3))
        val (t75, run75, stop75) = state.shipUI.diagnosticHullWarning()
        check(run75 && t75 == tr["warning_hull_75"]) { "below 75 %: $run75 ${t75.replace("\n", "|")}" }
        check(stop75 - System.nanoTime() in 5_000_000_000L..6_000_000_000L) { "the warning runs ${stop75 - System.nanoTime()} ns, not 6 s" }
        val until = System.nanoTime() + 30_000_000L
        while (System.nanoTime() < until) { /* the second fall comes later */ }
        hull(-(ship.maxHealth / 3))
        val (t50, run50, stop50) = state.shipUI.diagnosticHullWarning()
        check(run50 && t50 == tr["warning_hull_50"]) { "below 50 %: $run50 ${t50.replace("\n", "|")}" }
        check(stop50 == stop75) { "the fall below 50 % restarted the running warning" }
        hull(-(ship.health - 1))
        val (t25, run25, _) = state.shipUI.diagnosticHullWarning()
        check(run25 && t25 == tr["warning_hull_25"]) { "below 25 %: $run25 ${t25.replace("\n", "|")}" }
        hull(ship.maxHealth / 2)
        check(!state.shipUI.diagnosticHullWarning().second) { "a repair above 25 % left the warning running" }
        ship.health = ship.maxHealth
        return "hull warning"
    }

    /**
     * SystemBox::MouseMove (1.6.14 0x4e2d20): the Kestrel's shields box takes the hover (hover cursor) only in its icon
     * square, x 80..99 and y 673..692, and shows its tooltip there and over the bars' column, x 83..96 (native frame
     * pairs u1v-h and u1v-i).
     */
    private fun systemBoxHitAreas(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = paused(main, container, graphics)
        val want = state.translator["tooltip_shields"]
        fun at(x: Int, y: Int): Pair<Boolean, Boolean> {
            BrowserEngine.mouse(x, y, 0, 0); main.update(container, 0f)
            main.render(container, graphics)
            val text = (graphics.tooltip as? xyz.znix.xftl.rendering.StandardTooltip)?.getText() ?: ""
            val valid = state.shipUI.getCurrentCursor().mainFile.name.contains("pointerValid")
            return text.startsWith(want) to valid
        }
        val cases = listOf(
            Triple(78, 678, false to false), Triple(80, 678, true to true), Triple(99, 678, true to true),
            Triple(101, 678, false to false), Triple(89, 672, true to false), Triple(89, 676, true to true),
            Triple(89, 692, true to true), Triple(89, 694, false to false), Triple(82, 655, false to false),
            Triple(83, 655, true to false), Triple(96, 655, true to false), Triple(97, 655, false to false),
            Triple(89, 681, true to true))
        val wrong = cases.mapNotNull { (x, y, w) -> at(x, y).takeIf { it != w }?.let { "$x,$y: tooltip ${it.first} hover ${it.second}" } }
        check(wrong.isEmpty()) { "shields box: " + wrong.joinToString("; ") }
        return "system box hit areas"
    }

    /**
     * SystemControl::MouseMove (HS 1.6.9 0x4b87d0): the SUBSYSTEMS label shows the "subsystems" tooltip over x 1023..1171,
     * y 678..716 on the Kestrel, over the sensors box's own (native frame pairs u1v-h h-se-y680/y690, u1v-j).
     */
    private fun subsystemsLabelTooltip(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = paused(main, container, graphics)
        val tr = state.translator
        val label = tr["subsystems"]
        check(label.isNotEmpty() && label != "subsystems") { "no text for the subsystems label" }
        fun at(x: Int, y: Int): String {
            BrowserEngine.mouse(x, y, 0, 0); main.update(container, 0f)
            main.render(container, graphics)
            return (graphics.tooltip as? xyz.znix.xftl.rendering.StandardTooltip)?.getText() ?: ""
        }
        val cases = listOf(Triple(1083, 690, true), Triple(1083, 678, true), Triple(1023, 700, true),
            Triple(1171, 700, true), Triple(1100, 716, true), Triple(1021, 700, false), Triple(1173, 700, false),
            Triple(1100, 718, false), Triple(1083, 674, false))
        val wrong = cases.filter { (x, y, w) -> (at(x, y) == label) != w }.map { "${it.first},${it.second}" }
        check(wrong.isEmpty()) { "subsystems label tooltip wrong at " + wrong.joinToString("; ") }
        check(at(1083, 670).startsWith(tr["tooltip_sensors"])) { "the sensors box lost its tooltip above the label" }
        return "subsystems label"
    }

    /** GOD while paused: the reactor bars show the new free power at once (native frame u1v-b b-god). */
    private fun godReactor(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (!state.isPaused) state.toggleQuickPause()
        val ship = state.player
        xyz.znix.xftl.hs.render.HsConsole.run(state, "GOD")
        val shown = HashMap(ship.powerAvailableTypes)
        ship.updateAvailablePower()
        check(shown == ship.powerAvailableTypes) { "after GOD the reactor showed $shown, not ${ship.powerAvailableTypes}" }
        return "god reactor"
    }
}
