package ftl.browser

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Parity sweep U2 (menus, input, console): the original's hotkey list and defaults (Settings::ResetHotkeys 0x5a01b0),
 * the power_<n> / un_power_<n> keys (SystemControl::OnKeyDown 0x4c4ca0), no keys while the ship jumps
 * (CommandGui::KeyDown 0x534820, CommandGui::SpaceBar 0x52ce50), Space over the event box (SpaceBar's event_pause),
 * and the console's FUEL floor and LANG code (CommandGui::RunCommand 0x52f7b0). Native capture: ps-u2-keys.
 */
internal object GameplayParityPsU2Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val bindings = main.profile.snapshot()
        try {
            val inventory = inventory(main, container)
            val console = console(main, container)
            val slots = powerSlots(main, container)
            val jump = jumpHold(main, container)
            val event = eventSpace(main, container)
            val rename = crewRename(main, container)
            val stations = stations(main, container)
            val wheel = if (xyz.znix.xftl.hs.HS.active) hsWheel(main, container) else "no wheel in vanilla"
            val armament = armamentKeys()
            return "ps-u2 ok: $inventory, $console, $slots, $jump, $event, $rename, $stations, $wheel, $armament"
        } finally {
            BrowserEngine.resetInput()
            main.profile.restore(bindings)
            (main.currentState as? InGameState)?.updateHotkeyBindings()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** Settings::ResetHotkeys: every native hotkey name with its default key (null: unbound). */
    private val NATIVE: List<Pair<String, String?>> =
        (1..8).map { "crew$it" to "f$it" } + listOf(
            "crew_all" to "q", "loadPositions" to "enter", "savePositions" to "stroke", "lockdown" to "p", "pause" to "space",
            "jump" to "j", "ship_info" to "u", "ship_inv" to "i", "store" to null, "ship_crew" to null, "options" to "o",
            "increase_font" to "minus", "decrease_font" to "equals",
            "open" to "z", "close" to "x", "activate_cloak" to "c", "send_tele" to "t", "ret_tele" to "r",
            "start_hacking" to "n", "mindControl" to "m", "activate_battery" to "b", "drone1" to "5", "drone2" to "6",
            "drone3" to "7", "weapon1" to "1", "weapon2" to "2", "weapon3" to "3", "weapon4" to "4", "autofire" to "v",
            "force_autofire" to "lctrl",
            "shields" to "a", "engines" to "s", "oxygen" to "f", "medbay" to "d", "teleporter" to "g", "cloaking" to "h",
            "mind" to "k", "hacking" to "l", "artillery" to "y", "weapons" to "w", "drones" to "e",
        ) + listOf("shields", "engines", "oxygen", "medbay", "teleporter", "cloaking", "mind", "hacking", "artillery",
            "weapons", "drones").map { "un_$it" to null } +
            (1..8).map { "power_$it" to null } + (1..8).map { "un_power_$it" to null }

    /**
     * A prepared fixture's boot.pack carries the engine resources of the day it was prepared, and they win over the
     * image's: an older xftl_hotkeys.xml has neither the font keys nor power_<n>. Then the checks of the list say so.
     */
    private const val OLD_FIXTURE = "skipped: the fixture's boot.pack has an older xftl_hotkeys.xml"

    /** WeaponControl / DroneControl::ArmamentHotkey and Hyperspace's hooks (AdditionalWeaponSlots.cpp), DroneControl::OnLoop. */
    private fun armamentKeys(): String {
        fun name(hs: Boolean, w: Int, d: Int, weapon: Boolean, i: Int) =
            xyz.znix.xftl.hs.render.HsHotkeys.armamentHotkeyName(hs, w, d, weapon, i)
        fun number(hs: Boolean, w: Int, d: Int, weapon: Boolean, i: Int) =
            xyz.znix.xftl.hs.render.HsHotkeys.armamentBoxNumber(hs, w, d, weapon, i)
        val cases = listOf(
            name(false, 4, 3, true, 3) to "weapon4", name(false, 4, 3, false, 2) to "drone3",
            name(true, 4, 5, false, 3) to "drone4", // oracle ps-u1-d5 d5-h3: the fourth drone box "Hotkey: 8"
            name(true, 6, 2, true, 4) to "drone1", name(true, 6, 2, true, 5) to "drone2",
            name(true, 6, 2, false, 0) to "drone3", name(true, 5, 3, false, 2) to "drone4",
            name(true, 0, 5, false, 0) to "weapon1", name(true, 0, 5, false, 4) to "drone1",
            name(true, 2, 8, false, 0) to "weapon3", name(true, 2, 8, false, 2) to "drone1",
            name(true, 4, 3, false, 0) to "drone1",
        )
        for ((got, want) in cases) check(got == want) { "Armament hotkey $got, expected $want" }
        check(number(true, 6, 2, false, 0) == 7 && number(true, 2, 8, false, 0) == 3 && number(true, 4, 3, false, 1) == 2 &&
                number(false, 4, 3, false, 1) == 2) { "Armament box numbers" }
        return "armament hotkeys past four weapons as Hyperspace"
    }

    private fun inventory(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val byUi = state.hotkeyManager.groups.flatMap { it.hotkeys }.associateBy { it.uiName }
        val old = "power_1" !in state.hotkeyManager.byID
        for ((name, key) in NATIVE) {
            if (old && (name.startsWith("power_") || name.startsWith("un_power_") || name.endsWith("_font"))) continue
            val hotkey = byUi["hotkey_$name"] ?: error("No hotkey for the original's $name")
            check(hotkey.default?.id == key) { "$name defaults to ${hotkey.default?.id}, the original's is $key" }
        }
        // Settings::SetHotkey: a key taken by another hotkey leaves that one unbound
        val window = xyz.znix.xftl.game.HotkeysWindow(state) {}
        window.diagnosticAssign("jump", Input.KEY_A)
        state.updateHotkeyBindings()
        val reverse = state.reverseHotkeyBindings
        check(reverse[byUi.getValue("hotkey_jump")]?.id == "a") { "jump not on A" }
        check(reverse[byUi.getValue("hotkey_shields")] == null) { "shields kept A: ${reverse[byUi.getValue("hotkey_shields")]?.id}" }
        return if (old) "hotkey list $OLD_FIXTURE, a taken key unbinds the other"
        else "${NATIVE.size} native hotkeys with their defaults, a taken key unbinds the other"
    }

    private fun console(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.player.fuelCount = 7
        HsConsole.run(state, "FUEL -100")
        check(state.player.fuelCount == 0) { "FUEL -100 left ${state.player.fuelCount} fuel" }
        HsConsole.run(state, "FUEL 5")
        check(state.player.fuelCount == 5) { "FUEL 5 gave ${state.player.fuelCount}" }
        // RICH: the original's RunCommand +1999 scrap and +80 fuel; Hyperspace's CommandConsole 9999999 and 800
        val scrap = state.player.scrap
        HsConsole.run(state, "RICH")
        val (richScrap, richMore) = if (xyz.znix.xftl.hs.HS.active) 9999999 to 800 else 1999 to 80
        check(state.player.scrap == scrap + richScrap && state.player.fuelCount == 5 + richMore) {
            "RICH gave ${state.player.scrap - scrap} scrap and ${state.player.fuelCount - 5} fuel"
        }
        check(HsConsole.langCode("LANG ZH-HANS") == "zh-Hans" && HsConsole.langCode("lang de") == "de" &&
                HsConsole.langCode("LANG PT") == "pt") { "LANG codes" }
        // LOAD (RunCommand, WorldManager::CheckLoadEvent): the name up to the first space, atoi of the rest only after one
        val loads = listOf("LOAD STORE_FUEL" to ("STORE_FUEL" to -1), "LOAD SHIPS_PIRATE 3" to ("SHIPS_PIRATE" to 3),
            "LOAD SHIPS_PIRATE x" to ("SHIPS_PIRATE" to 0), "LOAD SHIPS_PIRATE " to ("SHIPS_PIRATE" to 0),
            "LOAD SHIPS_PIRATE -1" to ("SHIPS_PIRATE" to -1), "LOAD SHIPS_PIRATE 4abc" to ("SHIPS_PIRATE" to 4))
        for ((line, want) in loads) check(HsConsole.loadArgs(line) == want) { "$line read as ${HsConsole.loadArgs(line)}" }
        check(HsConsole.loadArgs("LOAD  3") == null && HsConsole.loadArgs("LOAD") == null) { "LOAD without a name" }
        val autoName = state.eventManager.getShip("PIRATE").autoBlueprintName
        val before = state.enemy
        HsConsole.run(state, "LOAD $autoName 2")
        check(state.enemy != null && state.enemy !== before && state.isEnemyHostile) { "LOAD $autoName 2 gave no new hostile ship" }
        val profile = main.profile
        // Hyperspace's ScoreKeeper::UnlockShip hook (Seeds.cpp) unlocks nothing in a seeded run unless allowed
        if (!xyz.znix.xftl.hs.HS.active || xyz.znix.xftl.hs.ship.HsShipUnlocks.unlocksAllowed()) for (ship in listOf("PLAYER_SHIP_CIRCLE_2", "PLAYER_SHIP_FED_3")) {
            profile.deleteLayoutUnlock(ship)
            HsConsole.run(state, "SHIP " + ship.lowercase())
            check(!profile.isVanillaShipUnlocked(ship)) { "SHIP took $ship in lower case" }
            HsConsole.run(state, "SHIP $ship")
            check(profile.isVanillaShipUnlocked(ship)) { "SHIP $ship did not unlock it" }
        }
        profile.deleteLayoutUnlock("PLAYER_SHIP_CRYSTAL_3")
        HsConsole.run(state, "SHIP PLAYER_SHIP_CRYSTAL_3")
        check(!profile.isVanillaShipUnlocked("PLAYER_SHIP_CRYSTAL_3")) { "SHIP unlocked a Crystal C" }
        return "FUEL floor, LANG codes, LOAD, SHIP <layout>"
    }

    private fun press(main: MainGame, container: GameContainer, key: Int, shift: Boolean = false) {
        if (shift) BrowserEngine.key(Input.KEY_LSHIFT, 0, true)
        BrowserEngine.key(key, 0, true)
        main.update(container, 0f)
        BrowserEngine.key(key, 0, false)
        if (shift) BrowserEngine.key(Input.KEY_LSHIFT, 0, false)
        main.update(container, 0f)
    }

    /** power_<n> powers the n-th powered system of the bar (Shift: down), un_power_<n> takes a bar away. */
    private fun powerSlots(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val keys = state.hotkeyManager.byID
        if ("power_1" !in keys) return "power slots $OLD_FIXTURE"
        val f9 = xyz.znix.xftl.game.HotkeyButton.BY_NAME.getValue("f9")
        val f10 = xyz.znix.xftl.game.HotkeyButton.BY_NAME.getValue("f10")
        val f11 = xyz.znix.xftl.game.HotkeyButton.BY_NAME.getValue("f11")
        main.profile.setKeybind(keys.getValue("power_1"), f9)
        main.profile.setKeybind(keys.getValue("un_power_1"), f10)
        main.profile.setKeybind(keys.getValue("power_2"), f11)
        state.updateHotkeyBindings()
        state.player.purchasedReactorPower = 25
        val first = state.player.mainSystems[0]
        val second = state.player.mainSystems[1]
        val p1 = first.powerSelected
        press(main, container, Input.KEY_F10)
        check(first.powerSelected < p1) { "un_power_1 left ${first.javaClass.simpleName} at ${first.powerSelected}" }
        press(main, container, Input.KEY_F9)
        check(first.powerSelected == p1) { "power_1 gave ${first.powerSelected}, expected $p1" }
        press(main, container, Input.KEY_F9, shift = true)
        check(first.powerSelected < p1) { "Shift+power_1 did not take power away" }
        val p2 = second.powerSelected
        press(main, container, Input.KEY_F11, shift = true)
        check(second.powerSelected < p2) { "Shift+power_2 left ${second.javaClass.simpleName} at ${second.powerSelected}" }
        return "power_1/un_power_1/power_2 on ${first.codename}, ${second.codename}"
    }

    /** While the ship jumps no key acts: no weapon armed, no pause menu, no pause. */
    private fun jumpHold(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val target = state.currentBeacon!!.neighbours.first()
        state.beginJumpOut(target)
        check(state.isPlayerJumping && state.keysHeldByJump()) { "Jump did not start" }
        val paused = state.isPlayerPaused
        press(main, container, Input.KEY_SPACE)
        check(state.isPlayerPaused == paused) { "Space paused during the jump" }
        press(main, container, Input.KEY_ESCAPE)
        check(state.shipUI.diagnosticPauseWindow == null) { "Escape opened the menu during the jump" }
        press(main, container, Input.KEY_U)
        check(!state.shipUI.isWindowOpen) { "U opened the ship window during the jump: ${state.shipUI.diagnosticWindow}" }
        return "jump holds Space, Escape, U"
    }

    /** CommandGui::SpaceBar with the event box open: the pause is set (not toggled), so it stays once the box closes. */
    private fun eventSpace(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        check(!state.isPlayerPaused) { "Fixture starts paused" }
        // As tests/window-places.mjs: the console's EVENT (a unique event a sector took opens nothing, as the original)
        for (name in listOf("TRADER_CIV", "ASTEROID_EXPLORE")) {
            if (state.shipUI.tutorialDialogueOpen) break
            HsConsole.run(state, "EVENT $name")
        }
        check(state.shipUI.tutorialDialogueOpen) { "Event box not open: ${state.shipUI.diagnosticWindow}" }
        press(main, container, Input.KEY_SPACE)
        check(state.isPlayerPaused) { "Space over the event box did not set the pause" }
        press(main, container, Input.KEY_SPACE)
        check(state.isPlayerPaused) { "Space over the event box toggled the pause off" }
        BrowserDiagnostics.closeBoxes(main, container)
        check(state.isPlayerPaused) { "The pause did not outlast the event box" }
        // A store or ship window keeps the pause as it was (menu_pause without event_pause).
        state.toggleQuickPause()
        check(!state.isPlayerPaused) { "toggleQuickPause" }
        press(main, container, Input.KEY_U)
        check(state.shipUI.diagnosticOpenShipWindow != null) { "U did not open the ship window: ${state.shipUI.diagnosticWindow}" }
        press(main, container, Input.KEY_SPACE)
        check(!state.isPlayerPaused) { "Space over the ship window paused the game" }
        return "Space over the event box pauses, over the ship window not"
    }

    /** CrewEquipBox::OnTextEvent -> TextInput::OnTextEvent: the crew name edits at a cursor. */
    private fun crewRename(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        press(main, container, Input.KEY_U)
        val window = state.shipUI.diagnosticOpenShipWindow ?: error("No ship window")
        val crew = state.player.crew.filterIsInstance<xyz.znix.xftl.crew.LivingCrew>().first()
        val old = crew.info.name
        try {
            crew.info.name = "Abc"
            window.diagnosticStartCrewRename(crew)
            window.onTextInput(Input.KEY_LEFT, 0.toChar())
            window.onTextInput(Input.KEY_X, 'x')
            check(crew.info.name == "Abxc") { "Insert at the cursor gave ${crew.info.name}" }
            window.onTextInput(Input.KEY_HOME, 0.toChar())
            window.onTextInput(Input.KEY_DELETE, 0.toChar())
            check(crew.info.name == "bxc") { "Delete at the start gave ${crew.info.name}" }
            window.onTextInput(Input.KEY_END, 0.toChar())
            window.onTextInput(Input.KEY_BACK, 0.toChar())
            check(crew.info.name == "bx") { "Backspace at the end gave ${crew.info.name}" }
            window.onTextInput(Input.KEY_ENTER, 0.toChar())
        } finally {
            crew.info.name = old
        }
        return "crew name cursor"
    }

    /** Hyperspace Input.cpp: with the upgrades tab open the wheel turns the crew list page; the crew tab takes it. */
    private fun hsWheel(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val race = state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint
        while (state.player.crew.size < 12) state.player.addCrewMember(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(race, state), false)
        state.shipUI.updateButtons()
        check(state.shipUI.diagnosticHsCrewPage == 0) { "Crew page ${state.shipUI.diagnosticHsCrewPage}" }
        press(main, container, Input.KEY_U)
        check(state.shipUI.diagnosticOpenShipWindow != null) { "No ship window" }
        BrowserEngine.wheel(640, 360, -1, Float.NaN)
        main.update(container, 0f)
        check(state.shipUI.diagnosticHsCrewPage == 1) { "The wheel over the upgrades did not turn the crew page: ${state.shipUI.diagnosticHsCrewPage}" }
        BrowserEngine.wheel(640, 360, 1, Float.NaN)
        main.update(container, 0f)
        check(state.shipUI.diagnosticHsCrewPage == 0) { "The wheel back: ${state.shipUI.diagnosticHsCrewPage}" }
        return "Hyperspace wheel over the upgrades turns the crew page"
    }

    /** CommandGui::KeyDown's loadPositions: "no_stations" when RestoreCrewPositions found no saved station. */
    private fun stations(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        for (crew in state.player.crew) crew.savedPosition = null
        state.shipUI.loadCrewPositions()
        val none = state.shipUI.diagnosticCrewMessage
        state.shipUI.saveCrewPositions()
        state.shipUI.loadCrewPositions()
        val saved = state.shipUI.diagnosticCrewMessage
        // Hyperspace reports an uncontrolled crew member's goal; the Kestrel's crew are all controlled
        check(none == "no_stations" && saved == "return_stations") { "Crew messages $none / $saved" }
        return "loadPositions messages"
    }
}
