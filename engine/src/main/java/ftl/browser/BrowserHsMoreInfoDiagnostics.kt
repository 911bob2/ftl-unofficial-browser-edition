package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.HotkeyButton
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.render.HsMoreInfo
import xyz.znix.xftl.hs.render.HsOverlay
import xyz.znix.xftl.hs.render.HsTooltips
import xyz.znix.xftl.rendering.StandardTooltip
import xyz.znix.xftl.sys.GameContainer

/**
 * Hyperspace's More Info button (MoreInfoButton.cpp), part of hs-crewui's `tooltip` part under Hyperspace, through the
 * real mouse and key paths:
 * - the mode changes the options only at the next CommandGui::OnLoop (a fresh start shows hyperspace.xml's values);
 * - the hover tooltip `tooltip_more_info`, a click on the button and the `info` hotkey (right Alt) toggle it;
 * - on: the crew tooltip lists the race's powers for an own crew member and a boarder (iShipId 1), the weapon slot
 *   tooltip is the stat list; off: as `<advancedCrewTooltips ally enemy>` and `<redesignedWeaponTooltips>` say;
 * - the mode is the process's: a save and load keeps it (HS does not save it).
 * Returns one line for the probe's output; throws on a difference.
 */
object BrowserHsMoreInfoDiagnostics {
    private const val DT = .05f

    fun run(main: MainGame, container: GameContainer, state: InGameState): String {
        val cfg = HS.cfg()
        fun frame() { main.update(container, DT); main.render(container, BrowserEngine.diagnosticGraphics()) }
        fun q(s: String) = BrowserDebug.quote(s)
        frame()
        check(HsOverlay.altMode && HsMoreInfo.applied) { "More Info at the start: altMode ${HsOverlay.altMode} applied ${HsMoreInfo.applied}" }
        // A change waits for CommandGui::OnLoop.
        HsOverlay.altMode = false; HsOverlay.altModeChanged = true
        check(HsMoreInfo.applied && HsMoreInfo.showAllyPowers()) { "the options followed the mode before OnLoop" }
        HsMoreInfo.onLoop()
        check(!HsMoreInfo.applied && HsMoreInfo.showAllyPowers() == cfg.showAllyPowers) { "OnLoop did not apply the mode" }
        HsOverlay.altMode = true; HsOverlay.altModeChanged = true; HsMoreInfo.onLoop()

        val crew = state.player.crew.filterIsInstance<LivingCrew>().firstOrNull { HsCrew.shipId(it) == 0 } ?: error("no crew")
        if (HS.luaActive) {
            // A Mantis boarder in a room no crew member is in (as the oracle's info-button.lua).
            val error = xyz.znix.xftl.hs.lua.HsLua.runString("more-info", "local sm = Hyperspace.ships.player; local busy = {}; " +
                "for i = 0, sm.vCrewList:size() - 1 do busy[sm.vCrewList[i].currentSlot.roomId] = true end; " +
                "for r = sm.ship.vRoomList:size() - 1, 0, -1 do if not busy[r] then " +
                "sm:AddCrewMemberFromString('Boarder', 'mantis', true, r, true, true); break end end")
            check(error == null) { "boarder: $error" }
        }
        val boarder = state.player.crew.filterIsInstance<LivingCrew>().firstOrNull { HsCrew.shipId(it) == 1 }
        val weapon = state.player.hardpoints.firstNotNullOfOrNull { it.weapon } ?: error("no weapon")
        val tr = state.translator
        fun tip(c: AbstractCrew) = state.shipUI.diagnosticCrewTooltip(state.player, c.getPixelPositionCentre())
        fun powers(t: String) = t.lines().count { it.startsWith("*") }
        fun weaponTip() = (weapon.weaponBarTooltip as StandardTooltip).getText()
        val crewPowers = crew.info.race.powerStringIds.size
        val boarderPowers = boarder?.info?.race?.powerStringIds?.size ?: 0

        // The button at (1207, 656), 53 px: hovered and clicked at its centre with the mouse.
        if (state.isPaused) state.toggleQuickPause()
        BrowserEngine.mouse(1233, 682, 0, 0); frame(); frame()
        val hover = HsMoreInfo.tooltip(state)?.getText() ?: error("the button shows no hover tooltip")
        check(hover.isNotEmpty() && hover.startsWith(tr["tooltip_more_info"].substringBefore("\\1"))) { "hover ${q(hover)}" }
        val onCrew = tip(crew)
        val onBoarder = boarder?.let { tip(it) }
        val onWeapon = weaponTip()
        val stats = HsTooltips.weaponBoxTooltip(weapon.type, tr)
        check(powers(onCrew) == crewPowers) { "on: own crew ${q(onCrew)} lists ${powers(onCrew)} powers, the race has $crewPowers" }
        if (onBoarder != null) check(onBoarder.startsWith("-Boarder (") && powers(onBoarder) == boarderPowers) { "on: boarder ${q(onBoarder)}" }
        check(stats != null && onWeapon.startsWith(stats)) { "on: weapon tooltip ${q(onWeapon)} is not the stat list ${q(stats ?: "")}" }
        BrowserEngine.mouse(1233, 682, 1, 0); main.update(container, 0f)
        BrowserEngine.mouse(1233, 682, 2, 0); main.update(container, 0f)
        check(!HsOverlay.altMode) { "a click on the button did not turn More Info off" }
        frame()
        check(!HsMoreInfo.applied) { "the options did not follow the click" }
        val offCrew = tip(crew)
        val offBoarder = boarder?.let { tip(it) }
        val offWeapon = weaponTip()
        check(powers(offCrew) == if (cfg.showAllyPowers) crewPowers else 0) { "off: own crew ${q(offCrew)}" }
        if (offBoarder != null) check(powers(offBoarder) == if (cfg.showEnemyPowers) boarderPowers else 0) { "off: boarder ${q(offBoarder)}" }
        check((offWeapon == onWeapon) == cfg.redesignedWeaponTooltips) { "off: weapon tooltip ${q(offWeapon)} (redesigned by default: ${cfg.redesignedWeaponTooltips})" }

        // Not saved: a save and load keeps the process's mode.
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        main.update(container, DT)
        check(!HsOverlay.altMode && !HsMoreInfo.applied) { "after a save and load More Info is ${HsOverlay.altMode}/${HsMoreInfo.applied}" }
        // The info hotkey (right Alt) turns it on again.
        val key = HotkeyButton.BY_NAME["ralt"]?.keyID ?: error("no right Alt key")
        BrowserEngine.key(key, 0, true); main.update(container, 0f); BrowserEngine.key(key, 0, false); main.update(container, DT)
        check(HsOverlay.altMode && HsMoreInfo.applied) { "the info hotkey did not turn More Info on" }
        boarder?.let { (main.currentState as InGameState).player.crew.filter { c -> HsCrew.shipId(c) == 1 }.forEach { c -> c.health = 0f } }
        return "moreinfo hover ${q(hover)}; on: crew ${powers(onCrew)} powers, boarder ${onBoarder?.let { powers(it) }}; " +
            "off: crew ${powers(offCrew)}, boarder ${offBoarder?.let { powers(it) }}, weapon ${if (offWeapon == onWeapon) "unchanged" else "plain"}; " +
            "kept over a save and load; the info hotkey"
    }
}
