package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep FU4 (HUD, menus, store and touch follow-ups), vanilla and modded:
 * - CommandGui::ClearLocation (1.6.14 0x532940) empties the crew selection (its CrewControl step 0x503110): the console's
 *   LOAD drops it, a jump does not (native capture ps-fu4-van fu4-sel).
 * - CrewAnimation::OnRender (1.6.14 0x477f20): a selected crew member of the player's is tinted (0.5, 1, 0.5); hovered
 *   only, it is not.
 * - ShipInfo::augList is a std::map: the augments are listed by name (AugmentEquipBox::CheckContents 1.6.9 0x52bf80).
 * - ShipSystem::RenderPowerBoxes' return (1.6.14 0x63c310): the crew-slot icon lifts the tooltip column by 16 (outline
 *   and white) or 24 (green and yellow).
 * - The armament tooltips name the settings hotkey weapon<n> / drone<n> of the box's own number; Hyperspace's
 *   ArmamentHotkey hooks move only the keys (oracle ps-fu4-w5: fifth weapon "Hotkey: None", drone boxes 5 and 6).
 * - CommandGui::MouseMove (1.6.14 0x535e00): while a target is being picked (CombatControl::UpdateTarget 0x57dd70) the
 *   system boxes and the jump, ship, store and options buttons get no mouse, so a click on them does nothing.
 * - AchievementTracker::OnLoop (1.6.9 0x42e630): a popup already queued shows after the option is switched off.
 */
internal object GameplayParityPsFu4Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val parts = listOf<() -> String>(
                { selectionTint(main, container) },
                { selectionClearedByLoad(main, container) },
                { selectionKeptByJump(main, container) },
                { augmentsByName(main, container) },
                { luaAugmentCap(main, container) },
                { tooltipColumnLift(main, container, graphics) },
                { queuedPopupsStay(main) },
                { armamentTooltipKeys(main, container) },
                { armedHudBlocked(main, container, graphics) },
            )
            val results = ArrayList<String>()
            val failures = ArrayList<String>()
            for (part in parts) {
                try {
                    results += part()
                } catch (e: Throwable) {
                    failures += e.message ?: e::class.simpleName ?: "failure"
                }
            }
            check(failures.isEmpty()) { failures.joinToString(" / ") }
            return "psFu4 ok: " + results.joinToString("; ")
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun crew(state: InGameState): List<LivingCrew> =
        state.player.crew.filterIsInstance<LivingCrew>().filter { it.playerControllable }

    private fun selectionTint(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val (a, b) = crew(state).take(2)
        state.shipUI.diagnosticSelectCrew(listOf(a))
        main.update(container, 0f)
        val tint = a.selectionSpriteTint()
        check(tint.r == .5f && tint.g == 1f && tint.b == .5f) { "selected tint ${tint.r},${tint.g},${tint.b}" }
        check(b.selectionSpriteTint() === Colour.white) { "an unselected crew member is tinted" }
        return "selected tint"
    }

    private fun selectionClearedByLoad(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.shipUI.diagnosticSelectCrew(crew(state).take(1))
        check(state.shipUI.touchSelected.isNotEmpty()) { "Set-up: no selection" }
        HsConsole.run(state, "LOAD NEBULA_EMPTY")
        repeat(4) { main.update(container, DT) }
        check(state.shipUI.touchSelected.isEmpty()) { "LOAD kept the crew selection" }
        return "LOAD drops the selection"
    }

    private fun selectionKeptByJump(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.shipUI.diagnosticSelectCrew(crew(state).take(1))
        state.beginJumpOut(state.currentBeacon!!.neighbours.first())
        var frames = 0
        while (state.isPlayerJumpingOut && frames++ < 400) main.update(container, DT)
        check(!state.isPlayerJumpingOut) { "the jump did not land" }
        check(state.shipUI.touchSelected.isNotEmpty()) { "a jump dropped the crew selection" }
        return "a jump keeps the selection"
    }

    private fun augmentsByName(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        player.augments.clear(); player.cargoUpdated()
        for (name in listOf("SYSTEM_CASING", "ADV_SCANNERS")) {
            val bp = state.blueprintManager[name] as xyz.znix.xftl.augments.AugmentBlueprint
            check(player.addBlueprint(bp, false)) { "$name did not fit" }
        }
        val names = player.augments.map { it.name }
        check(names == listOf("ADV_SCANNERS", "SYSTEM_CASING")) { "augments in order $names" }
        return "augments by name"
    }

    /**
     * Hyperspace's ShipObject::AddAugmentation hook (CustomEquipment.cpp, priority 1000): with the augment slots full the
     * Lua call returns false and adds nothing, so no augment already there moves out (oracle ps-m3-a sys|autofed: the
     * fourth, SHIP_AUTO_FED, has no effect and UPG_TELEPORTER_POWER keeps the teleporter's bonus power).
     */
    private fun luaAugmentCap(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.luaActive) return "Lua augment cap: no Lua"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        player.augments.clear(); player.cargoUpdated()
        val cap = xyz.znix.xftl.hs.ship.HsEquipment.augCapacity(player)
        check(cap == 3) { "Set-up: $cap augment slots" }
        val err = xyz.znix.xftl.hs.lua.HsLua.runString("psfu4-augcap", """
            local p = Hyperspace.ships.player
            local r = {}
            for _, n in ipairs({ 'SYSTEM_CASING', 'SCRAP_COLLECTOR', 'ADV_SCANNERS', 'AUTO_COOLDOWN' }) do
                r[#r + 1] = tostring(p:AddAugmentation(n))
            end
            error('R|' .. table.concat(r, ','))
        """.trimIndent()) ?: "no result"
        check(err.substringAfter("R|") == "true,true,true,false") { "AddAugmentation returned $err" }
        repeat(4) { main.update(container, DT) }
        val names = player.augments.map { it.name }
        check(names == listOf("ADV_SCANNERS", "SCRAP_COLLECTOR", "SYSTEM_CASING")) { "augments after a fourth Lua add: $names" }
        check(player.cargoBlueprints.none { it?.name == "AUTO_COOLDOWN" }) { "the refused augment went to the cargo" }
        player.augments.clear(); player.cargoUpdated()
        return "Lua AddAugmentation refused with the slots full"
    }

    private fun tooltipColumnLift(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (!state.isPaused) state.toggleQuickPause()
        main.render(container, graphics)
        var seen = 0
        for (sys in state.player.mainSystems) {
            if (sys.drawnPowerBarsTop == Int.MAX_VALUE || !sys.boxCrewSlotIconDrawn) continue
            if (sys !is xyz.znix.xftl.systems.Shields && sys !is xyz.znix.xftl.systems.Engines &&
                sys !is xyz.znix.xftl.systems.Weapons) continue
            val lift = sys.drawnPowerBarsTop - sys.drawnTooltipTop
            check(lift == 16 || lift == 24) { "${sys.javaClass.simpleName}: the crew slot lifts the column by $lift" }
            seen++
        }
        check(seen > 0) { "no box with a crew slot drawn" }
        return "crew slot lifts the tooltip column ($seen boxes)"
    }

    private fun queuedPopupsStay(main: MainGame): String {
        val profile = main.profile
        val saved = profile.snapshot()
        try {
            val notices = BrowserProgressNotifications(main)
            profile.clearNotifications()
            profile.achievementPopups = true
            profile.addHsNotification(xyz.znix.xftl.game.SaveProfile.ProgressNotification(achievement = "ACH_PIRATE_CLEARED"))
            check(profile.pendingNotificationCount == 1) { "Set-up: nothing queued" }
            profile.achievementPopups = false
            notices.update(.1f)
            check(notices.active) { "a popup queued before the option went off was dropped" }
        } finally {
            profile.clearNotifications()
            profile.restore(saved)
        }
        return "queued popups stay"
    }

    private fun armamentTooltipKeys(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ui = state.shipUI
        val none = state.translator["keycap_none"]
        fun key(name: String) = state.getButtonNameForHotkey(
            state.hotkeyManager.groups.flatMap { it.hotkeys }.first { it.uiName == "hotkey_$name" }.id, none)
        check(ui.armamentTooltipKey(true, 4) == none) { "fifth weapon box: Hotkey ${ui.armamentTooltipKey(true, 4)}" }
        check(ui.armamentTooltipKey(true, 0) == key("weapon1")) { "first weapon box: Hotkey ${ui.armamentTooltipKey(true, 0)}" }
        check(ui.armamentTooltipKey(false, 0) == key("drone1") && ui.armamentTooltipKey(false, 1) == key("drone2")) {
            "drone boxes: Hotkey ${ui.armamentTooltipKey(false, 0)}, ${ui.armamentTooltipKey(false, 1)}"
        }
        return "armament tooltips name weapon<n> / drone<n>"
    }

    private fun armedHudBlocked(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        if (!state.isPaused) state.toggleQuickPause()
        main.render(container, graphics)
        val ui = state.shipUI
        val engines = state.player.mainSystems.first { it is xyz.znix.xftl.systems.Engines }
        val box = ui.diagnosticSystemBox(engines) ?: error("Set-up: no engines box")
        val o = xyz.znix.xftl.math.Point(0, 0)
        state.convertScreenToPlayerShip(o)
        val offset = xyz.znix.xftl.math.ConstPoint(-o.x, -o.y)
        val x = box.pos.x + box.size.x / 2
        val y = box.pos.y + box.size.y / 2
        ui.touchChangePower(engines as xyz.znix.xftl.systems.MainSystem, false)
        val before = engines.powerSelected
        check(before < engines.undamagedEnergy) { "Set-up: the engines are at full power" }
        try {
            state.clickEvent = InGameState.RoomClickListener { _, _ -> }
            repeat(2) { ui.updateUI(x, y, offset) }
            check(!box.hovered) { "the engines box is hovered with a target being picked" }
            ui.mouseClick(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, x, y, offset)
            ui.mouseUp(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, x, y, offset)
            check(engines.powerSelected == before) { "a click on the engines box powered them with a target being picked" }
            val jump = ui.jumpButton
            if (jump != null) {
                ui.updateUI(jump.pos.x + 2, jump.pos.y + 2, offset)
                check(!jump.hovered) { "the jump button is hovered with a target being picked" }
            }
            state.clickEvent = null
            repeat(2) { ui.updateUI(x, y, offset) }
            check(box.hovered) { "the engines box is not hovered once nothing is armed" }
            ui.mouseClick(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, x, y, offset)
            ui.mouseUp(xyz.znix.xftl.sys.Input.MOUSE_LEFT_BUTTON, x, y, offset)
            check(engines.powerSelected == before + 1) { "a click on the engines box did not power them once nothing is armed" }
        } finally {
            state.clickEvent = null
        }
        return "an armed target pick blocks the HUD boxes and buttons"
    }
}
