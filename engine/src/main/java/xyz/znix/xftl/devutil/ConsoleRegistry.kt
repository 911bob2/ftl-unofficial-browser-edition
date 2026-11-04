package xyz.znix.xftl.devutil

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.render.HsConsole
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * The one debug console's commands (docs/CONSOLE.md): the port's development commands ([DebugCommands]) and Hyperspace's
 * command console (HS CommandConsole::RunCommand, then the original 1.6.14 CommandGui::RunCommand; [HsConsole]) in one
 * list. Names match whatever their case. A Hyperspace or original command gets the whole line, as RunCommand does, and
 * runs in vanilla too unless it [Spec.needsHs]. Where one of ours had a Hyperspace or original command's name, that
 * command keeps the name and ours took the [RENAMED] one; ours that did the same as that command went ([FOLDED]).
 */
object ConsoleRegistry {
    /** A Hyperspace or original command: [name] is the first word RunCommand compares. */
    class Spec(val name: String, val args: String, val help: String, val needsHs: Boolean = false, val hint: ArgumentTypeProcessor? = null)

    private fun hs(name: String, args: String, help: String, hint: ArgumentTypeProcessor? = null) = Spec(name, args, help, true, hint)

    /** HS CommandConsole.cpp's words, then the original's (CommandGui::RunCommand 0x52f7b0) that HS leaves to it. */
    @JvmField
    val SPECS: List<Spec> = listOf(
        Spec("RICH", "", "+9999999 scrap, +800 fuel, drone parts and missiles (missiles need a weapons system)"),
        Spec("POOR", "", "-9999999 scrap, -800 fuel, drone parts and missiles (none below 0)"),
        Spec("STORE", "[sector]", "A new store here, opened at once (this sector's stock, or the given sector's)"),
        hs("FORCE_STORE", "<store id>", "The next store is the mod's custom store <store id>"),
        Spec("EXIT", "", "Open the sector map and leave this beacon"),
        Spec("KILL", "<crew name>", "Kill the player crew member of that name"),
        Spec("SKILL", "", "Master every skill of the player's crew"),
        Spec("DELETECREW", "", "Kill the enemy ship's crew"),
        Spec("COOLSWORDBRO", "", "Same as DELETECREW"),
        Spec("SPEED", "<n>", "Game speed (16 + 7n)/16: 0 normal, 2 almost double, -2 one eighth"),
        hs("LUA", "<code>", "Run Lua code in the mod's scripts"),
        Spec("SYS", "<system>|ALL", "Add a system the ship has room for; SYS ALL adds every one"),
        Spec("DAMAGESYS", "<system>", "Break a player system completely"),
        hs("REMOVESYS", "<system>", "Remove a player system"),
        Spec("DEBUG", "", "Does nothing (Hyperspace keeps the word)"),
        Spec("SHIP", "ALL", "SHIP ALL: unlock every ship and layout for good (profile)"),
        hs("SHIP_CUSTOM", "<ship>", "Unlock a mod ship for good (profile)"),
        hs("SHIP_CUSTOM_LOCK", "<ship>", "Lock a mod ship again (profile)"),
        Spec("FLEET", "<n>", "Rebel fleet: n > 0 advances twice as fast for n jumps, n < 0 stops it for -n jumps"),
        Spec("LOADEVENT", "<event>", "Load an event here now (used events too)"),
        Spec("CREDITS", "", "Win the run: the victory screen"),
        Spec("VICTORY", "", "Same as CREDITS"),
        hs("VAR", "<name> [value]", "Set a mod's run variable"),
        hs("VARIABLE", "<name> [value]", "Same as VAR"),
        hs("METAVAR", "<name> [value]", "Set a mod's profile variable"),
        hs("METAVARIABLE", "<name> [value]", "Same as METAVAR"),
        hs("MVAR", "<name> [value]", "Same as METAVAR"),
        Spec("ACH", "<achievement>", "Unlock an achievement (profile)", hint = AchievementProcessor),
        hs("ACH_LOCK", "<achievement>", "Lock a mod achievement again (profile)"),
        hs("SWITCHALL", "<ship>", "Switch to another ship, keeping crew, gear, resources and levels"),
        hs("SWITCH", "<ship>", "Switch to another ship as it starts"),
        Spec("SQUISHY", "<text>", "Show <text> at the top right of the screen"),
        Spec("WEAPON", "<weapon>", "Add a weapon: a free weapon slot, else the cargo", hint = BlueprintTypeProcessor(AbstractWeaponBlueprint::class.java)),
        // the original's own (HS passes them on)
        Spec("SCRAP", "<n>", "Add n scrap (negative takes it away, none below 0)"),
        Spec("FUEL", "<n>", "Add n fuel"),
        Spec("MISSILES", "<n>", "Add n missiles (with a weapons system; none below 0)"),
        Spec("DRONES", "<n>", "Add n drone parts (none below 0)"),
        Spec("HULL", "<n>", "Repair n hull points, up to the maximum"),
        Spec("DRONE", "<drone>", "Add a drone: a free drone slot, else the cargo", hint = BlueprintTypeProcessor(DroneBlueprint::class.java)),
        Spec("AUG", "<augment>", "Add an augment while the ship has fewer than three", hint = BlueprintTypeProcessor(AugmentBlueprint::class.java)),
        Spec("CREW", "<race>", "Add a crew member of that race", hint = BlueprintTypeProcessor(CrewBlueprint::class.java)),
        Spec("GOD", "", "Every system and the reactor to their maximum"),
        Spec("DELETE", "", "Destroy the enemy ship"),
        Spec("EVENT", "<event>", "This beacon's event replaced by <event>, shown as on arrival"),
    )

    /** Ours that shared a Hyperspace or original command's name and behaved differently: old name to new name. */
    @JvmField
    val RENAMED: Map<String, String> = linkedMapOf(
        "rich" to "dev-rich", "poor" to "dev-poor", "weapon" to "dev-weapon", "drone" to "dev-drone", "aug" to "dev-aug",
        "store" to "dev-store", "event" to "dev-event", "kill" to "dev-kill", "skill" to "dev-skill",
    )

    /** Ours that did what the Hyperspace or original command of the same name does: that one stays, with our completion. */
    @JvmField
    val FOLDED: List<String> = listOf("scrap", "fuel", "missiles", "drones", "crew")

    /** Every command, ours first. */
    @JvmStatic
    fun build(console: DebugConsole): List<DebugConsole.Cmd> = DebugCommands(console).commands + SPECS.map { spec ->
        DebugConsole.Cmd(
            spec.name,
            if (spec.hint != null) listOf(DebugConsole.CmdParameter(spec.hint, spec.args)) else emptyList(),
            true, {}, spec.help,
            raw = { line -> HsConsole.run(console.game, line) },
            needsHs = spec.needsHs, args = spec.args,
        )
    }

    @JvmStatic
    fun find(commands: List<DebugConsole.Cmd>, name: String): DebugConsole.Cmd? = commands.firstOrNull { it.name.equals(name, ignoreCase = true) }

    @JvmStatic
    fun needsHsMessage(name: String) = "$name needs a Hyperspace mod, and this run has none."
}

/**
 * When the console may open from a key, the website's Console button or the Android `>_` button (docs/CONSOLE.md).
 * With the device's "Debug console" setting on it opens in every mode, on ` and \ (and the keys bound to the development
 * console or Hyperspace's console hotkey); off, only a Hyperspace mod's `<console enabled="true"/>` opens it, on HS's
 * console hotkey and only when HS would (CommandGui::KeyDown hook in CommandConsole.cpp).
 */
object ConsoleGate {
    /** The device's "Debug console" setting (the website's Logs page, the Android launcher's settings); off by default. */
    @JvmField
    var setting = false

    /** `CommandConsole::enabled`: the mod's `<console enabled>`. */
    @JvmStatic
    fun hsEnabled(): Boolean = HS.active && HS.config?.consoleEnabled == true

    /** Whether the console may open at all now. */
    @JvmStatic
    fun allowed(): Boolean = setting || hsEnabled()

    /**
     * Whether [key] opens the console; [bound] is the hotkey id the key is bound to (null if none). [hsMayOpen] is
     * HS's own condition: no write-error dialog, menu, game over screen or focus window open, the ship not jumping.
     */
    @JvmStatic
    fun opens(key: Int, bound: String?, hsMayOpen: Boolean): Boolean {
        if (setting) return key == xyz.znix.xftl.sys.Input.KEY_GRAVE || key == xyz.znix.xftl.sys.Input.KEY_HASH ||
            bound == xyz.znix.xftl.game.VanillaHotkeys.DEV_CONSOLE || bound == "console"
        return hsEnabled() && bound == "console" && hsMayOpen
    }
}
