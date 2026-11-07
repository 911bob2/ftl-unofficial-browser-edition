// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomHotkeys.cpp (with the key
// handlers of src/game/UserInterface/CommandConsole.cpp CommandGui::KeyDown and
// src/game/Ship/ShipSystems/ArtillerySystem.cpp CombatControl::KeyDown)
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.GameText
import xyz.znix.xftl.game.Hotkey
import xyz.znix.xftl.game.HotkeyButton
import xyz.znix.xftl.game.HotkeyGroup
import xyz.znix.xftl.game.InGameState

/**
 * Hyperspace's extra hotkeys (Settings::ResetHotkeys hook): each is added to one of the four Controls pages at an
 * index (or at the end), an existing one of the same name moved there:
 *
 * | name | default | page | index | port hotkey id |
 * | --- | --- | --- | --- | --- |
 * | weapon1..4 | 1..4 | 1 | 8..11 | weapon_slot_1..4 (vanilla, moved) |
 * | drone4..6 | 8, 9, 0 | 1 | 15..17 | drone_slot_4..6 |
 * | console | \ | 0 | 21 | console (the debug console, docs/CONSOLE.md: opens it while `<console enabled>`, or with the setting) |
 * | speed | ` | 0 | 22 | speed (CFPS speedEnabled) |
 * | info | right Alt | 0 | 11 | info (More Info / altMode) |
 * | temporal / un_temporal | ; / none | 2 | 11 / end | sys_power_temporal(_off) (systems' power keys) |
 * | temporal_speed / temporal_slow | . / , | 1 | 8 / 9 | temporal_speed / temporal_slow (systems' Temporal) |
 * | aim_artillery1..4 | none | 3 | 8, 9, end, end | aim_artillery1..4 |
 *
 * ControlsScreen::OnInit then lays pages 0-2 out from (480, 130) with 12, 10 and 12 boxes in the first column
 * (page 3 keeps the original's layout). Under Hyperspace the port's own development keys give up a default key
 * an HS hotkey takes (frame step on '.'). '`' is the `speed` hotkey under Hyperspace unless the device's "Debug console"
 * setting is on, which opens the console on it in every mode (devutil/ConsoleRegistry.kt, ConsoleGate).
 */
object HsHotkeys {
    /**
     * The settings name of an armament box's hotkey (weapon1..4, drone1..6): WeaponControl / DroneControl::ArmamentHotkey,
     * and under Hyperspace their AdditionalWeaponSlots.cpp hooks: a weapon box past the fourth takes drone1.., the
     * drone boxes come after five or more weapon boxes, start on weapon1..4 without a weapons system, and past six
     * drone boxes start at max(weapons, 10 - drones). [weapons] and [drones] count the boxes (0 without the system).
     */
    @JvmStatic
    fun armamentHotkeyName(hs: Boolean, weapons: Int, drones: Int, weapon: Boolean, i: Int): String {
        if (!hs) return (if (weapon) "weapon" else "drone") + (i + 1)
        if (weapon) return if (i >= 4) "drone${i - 3}" else "weapon${i + 1}"
        val n = when {
            weapons > 4 -> return "drone${i + weapons - 4 + 1}"
            weapons == 0 -> i
            drones > 6 -> maxOf(weapons, 10 - drones) + i
            else -> return "drone${i + 1}"
        }
        return if (n < 4) "weapon${n + 1}" else "drone${n - 4 + 1}"
    }

    /** ArmamentBox::hotKey, the box's number: DroneControl::OnLoop's hook renumbers the drone boxes as above. */
    @JvmStatic
    fun armamentBoxNumber(hs: Boolean, weapons: Int, drones: Int, weapon: Boolean, i: Int): Int = when {
        !hs || weapon -> i + 1
        weapons > 4 -> weapons + i + 1
        weapons == 0 -> i + 1
        drones > 6 -> maxOf(weapons, 10 - drones) + i + 1
        else -> i + 1
    }

    private class Def(val name: String, val id: String, val default: String?, val page: Int, val index: Int)

    private val DEFS = listOf(
        Def("weapon1", "weapon_slot_1", "1", 1, 8),
        Def("weapon2", "weapon_slot_2", "2", 1, 9),
        Def("weapon3", "weapon_slot_3", "3", 1, 10),
        Def("weapon4", "weapon_slot_4", "4", 1, 11),
        Def("drone4", "drone_slot_4", "8", 1, 15),
        Def("drone5", "drone_slot_5", "9", 1, 16),
        Def("drone6", "drone_slot_6", "0", 1, 17),
        Def("console", "console", "hash", 0, 21),
        Def("speed", "speed", "backtick", 0, 22),
        Def("info", "info", "ralt", 0, 11),
        Def("temporal", "sys_power_temporal", "semicolon", 2, 11),
        Def("un_temporal", "sys_power_temporal_off", null, 2, -1),
        Def("temporal_speed", "temporal_speed", "full_stop", 1, 8),
        Def("temporal_slow", "temporal_slow", "comma", 1, 9),
        Def("aim_artillery1", "aim_artillery1", null, 3, 8),
        Def("aim_artillery2", "aim_artillery2", null, 3, 9),
        Def("aim_artillery3", "aim_artillery3", null, 3, -1),
        Def("aim_artillery4", "aim_artillery4", null, 3, -1),
    )

    /** The hotkeys the vanilla list lacks, as a group of the port's HotkeyManager (HS only). */
    @JvmStatic
    fun group(): HotkeyGroup {
        val list = DEFS.filter { !it.id.startsWith("weapon_slot_") }.map { d ->
            val key = d.default?.let { HotkeyButton.BY_NAME[it] ?: error("Invalid default keybind $it") }
            Hotkey(d.id, GameText.localised("hotkey_${d.name}"), key, "hotkey_${d.name}")
        }
        return HotkeyGroup("hyperspace", GameText.literal("Hyperspace"), 1, list)
    }

    /** Settings::ResetHotkeys: the HS names inserted into (or moved within) the vanilla pages, in order. */
    @JvmStatic
    fun pages(vanilla: List<List<String>>): List<List<String>> {
        val pages = vanilla.map { ArrayList(it) }
        for (d in DEFS) {
            val p = pages[d.page]
            p.remove(d.name)
            if (d.index == -1) p.add(d.name) else p.add(d.index, d.name)
        }
        return pages
    }

    /** ControlsScreen::OnInit hook: the boxes in the first column of pages 0-2 (page 3: null, the original's). */
    @JvmStatic
    fun perColumn(page: Int): Int? = when (page) { 0 -> 12; 1 -> 10; 2 -> 12; else -> null }

    /** ControlsScreen::OnInit hook: the top of pages 0-2. */
    const val ORIGIN_Y = 130

    /** The port's development hotkeys lose a default key an HS hotkey uses (HS has no such keys). */
    @JvmStatic
    fun devKeyFree(dev: Hotkey, key: HotkeyButton, groups: List<HotkeyGroup>): Boolean =
        groups.firstOrNull { it.id == "hyperspace" }?.hotkeys?.none { it.default === key } ?: true

    /**
     * CommandGui::KeyDown hook (speed, info; the console opens in InGameState, ConsoleGate) and CombatControl::KeyDown hook
     * (aim_artillery1..4 while the artillery is targetable); drone4..6 reach the boxes through [armamentHotkeyName]. The speed and
     * info keys come before the original CommandGui::KeyDown, the others after it, which ignores keys while the ship
     * jumps ([jumpHeld]).
     */
    @JvmStatic
    fun dispatch(game: InGameState, key: Hotkey, powerDown: Boolean, jumpHeld: Boolean = false) {
        if (jumpHeld && key.id != "speed" && key.id != "info") return
        when (key.id) {
            "speed" -> xyz.znix.xftl.hs.lua.LuaFps.speedEnabled = !xyz.znix.xftl.hs.lua.LuaFps.speedEnabled
            "info" -> HsMoreInfo.toggle(game)
            "aim_artillery1", "aim_artillery2", "aim_artillery3", "aim_artillery4" -> {
                val player = game.player ?: return
                val i = key.id.last() - '1'
                val art = player.artillery.getOrNull(i) ?: return
                if (xyz.znix.xftl.hs.systems.HsArtillery.targetable(player)) game.shipUI.hsArmArtillery(art)
            }
        }
    }
}
