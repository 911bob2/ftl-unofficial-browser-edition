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
    class Spec(
        val name: String, val args: String, val help: String, val needsHs: Boolean = false, val hint: ArgumentTypeProcessor? = null,
        val group: String = "", val examples: List<String> = emptyList(),
    )

    private fun hs(name: String, args: String, help: String, hint: ArgumentTypeProcessor? = null, group: String = "", examples: List<String> = emptyList()) =
        Spec(name, args, help, true, hint, group, examples)

    /** The command reference's groups (help, docs/CONSOLE.md), in their order. */
    object Groups {
        const val GET = "Get and unlock"
        const val RESOURCES = "Resources"
        const val GEAR = "Weapons, drones, augments and crew"
        const val SHIP = "Ship and systems"
        const val ENEMY = "Enemies and combat"
        const val WORLD = "Map, events and stores"
        const val PROFILE = "Profile: ships and achievements"
        const val MOD = "Mods and scripting"
        const val DEV = "Saves, flags and diagnostics"
        @JvmField val ORDER = listOf(GET, RESOURCES, GEAR, SHIP, ENEMY, WORLD, PROFILE, MOD, DEV)
    }

    /** HS CommandConsole.cpp's words, then the original's (CommandGui::RunCommand 0x52f7b0) that HS leaves to it. */
    @JvmField
    val SPECS: List<Spec> = listOf(
        Spec("RICH", "", "+9999999 scrap, +800 fuel, drone parts and missiles (missiles need a weapons system)", group = Groups.RESOURCES),
        Spec("POOR", "", "-9999999 scrap, -800 fuel, drone parts and missiles (none below 0)", group = Groups.RESOURCES),
        Spec("STORE", "[sector]", "A new store here, opened at once (this sector's stock, or the given sector's)", group = Groups.WORLD, examples = listOf("STORE", "STORE 5")),
        hs("FORCE_STORE", "<store id>", "The next store is the mod's custom store <store id>", group = Groups.WORLD),
        Spec("EXIT", "", "Open the sector map and leave this beacon", group = Groups.WORLD),
        Spec("KILL", "<crew name>", "Kill the player crew member of that name", group = Groups.GEAR, examples = listOf("KILL Slocknog")),
        Spec("SKILL", "", "Master every skill of the player's crew", group = Groups.GEAR),
        Spec("DELETECREW", "", "Kill the enemy ship's crew", group = Groups.ENEMY),
        Spec("COOLSWORDBRO", "", "Same as DELETECREW", group = Groups.ENEMY),
        Spec("SPEED", "<n>", "Game speed (16 + 7n)/16: 0 normal, 2 almost double, -2 one eighth", group = Groups.MOD, examples = listOf("SPEED 2", "SPEED 0")),
        hs("LUA", "<code>", "Run Lua code in the mod's scripts", group = Groups.MOD, examples = listOf("LUA print(\"hello\")")),
        Spec("SYS", "<system>|ALL", "Add a system the ship has room for; SYS ALL adds every one", group = Groups.SHIP, examples = listOf("SYS cloaking", "SYS ALL")),
        Spec("DAMAGESYS", "<system>", "Break a player system completely", group = Groups.SHIP, examples = listOf("DAMAGESYS shields")),
        hs("REMOVESYS", "<system>", "Remove a player system", group = Groups.SHIP, examples = listOf("REMOVESYS cloaking")),
        Spec("DEBUG", "", "Does nothing (Hyperspace keeps the word)", group = Groups.MOD),
        Spec("SHIP", "ALL", "SHIP ALL: unlock every ship and layout for good (profile)", group = Groups.PROFILE, examples = listOf("SHIP ALL")),
        hs("SHIP_CUSTOM", "<ship>", "Unlock a mod ship for good (profile)", group = Groups.PROFILE),
        hs("SHIP_CUSTOM_LOCK", "<ship>", "Lock a mod ship again (profile)", group = Groups.PROFILE),
        Spec("FLEET", "<n>", "Rebel fleet: n > 0 advances twice as fast for n jumps, n < 0 stops it for -n jumps", group = Groups.WORLD, examples = listOf("FLEET 3", "FLEET -2")),
        Spec("LOADEVENT", "<event>", "Load an event here now (used events too)", group = Groups.WORLD, examples = listOf("LOADEVENT STORE_FUEL")),
        Spec("CREDITS", "", "Win the run: the victory screen", group = Groups.WORLD),
        Spec("VICTORY", "", "Same as CREDITS", group = Groups.WORLD),
        hs("VAR", "<name> [value]", "Set a mod's run variable", group = Groups.MOD, examples = listOf("VAR loc_test 1")),
        hs("VARIABLE", "<name> [value]", "Same as VAR", group = Groups.MOD),
        hs("METAVAR", "<name> [value]", "Set a mod's profile variable", group = Groups.PROFILE, examples = listOf("METAVAR prof_test 1")),
        hs("METAVARIABLE", "<name> [value]", "Same as METAVAR", group = Groups.PROFILE),
        hs("MVAR", "<name> [value]", "Same as METAVAR", group = Groups.PROFILE),
        Spec("ACH", "<achievement>", "Unlock an achievement (profile)", hint = AchievementProcessor, group = Groups.PROFILE, examples = listOf("ACH ACH_FULL_ARSENAL")),
        hs("ACH_LOCK", "<achievement>", "Lock a mod achievement again (profile)", group = Groups.PROFILE),
        hs("SWITCHALL", "<ship>", "Switch to another ship, keeping crew, gear, resources and levels", group = Groups.SHIP, examples = listOf("SWITCHALL PLAYER_SHIP_FED")),
        hs("SWITCH", "<ship>", "Switch to another ship as it starts", group = Groups.SHIP, examples = listOf("SWITCH PLAYER_SHIP_FED")),
        Spec("SQUISHY", "<text>", "Show <text> at the top right of the screen", group = Groups.MOD, examples = listOf("SQUISHY Hello")),
        Spec("WEAPON", "<weapon>", "Add a weapon: a free weapon slot, else the cargo", hint = BlueprintTypeProcessor(AbstractWeaponBlueprint::class.java), group = Groups.GEAR, examples = listOf("WEAPON LASER_BURST_2")),
        // the original's own (HS passes them on)
        Spec("SCRAP", "<n>", "Add n scrap (negative takes it away, none below 0)", group = Groups.RESOURCES, examples = listOf("SCRAP 100", "SCRAP -50")),
        Spec("FUEL", "<n>", "Add n fuel", group = Groups.RESOURCES, examples = listOf("FUEL 10")),
        Spec("MISSILES", "<n>", "Add n missiles (with a weapons system; none below 0)", group = Groups.RESOURCES, examples = listOf("MISSILES 10")),
        Spec("DRONES", "<n>", "Add n drone parts (none below 0)", group = Groups.RESOURCES, examples = listOf("DRONES 10")),
        Spec("HULL", "<n>", "Repair n hull points, up to the maximum", group = Groups.RESOURCES, examples = listOf("HULL 10")),
        Spec("DRONE", "<drone>", "Add a drone: a free drone slot, else the cargo", hint = BlueprintTypeProcessor(DroneBlueprint::class.java), group = Groups.GEAR, examples = listOf("DRONE COMBAT_1")),
        Spec("AUG", "<augment>", "Add an augment while the ship has fewer than three", hint = BlueprintTypeProcessor(AugmentBlueprint::class.java), group = Groups.GEAR, examples = listOf("AUG ADV_SCANNERS")),
        Spec("CREW", "<race>", "Add a crew member of that race", hint = BlueprintTypeProcessor(CrewBlueprint::class.java), group = Groups.GEAR, examples = listOf("CREW engi")),
        Spec("GOD", "", "Every system and the reactor to their maximum", group = Groups.SHIP),
        Spec("DELETE", "", "Destroy the enemy ship", group = Groups.ENEMY),
        Spec("EVENT", "<event>", "This beacon's event replaced by <event>, shown as on arrival", group = Groups.WORLD, examples = listOf("EVENT STORE_FUEL")),
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

    /**
     * The new commands (docs/CONSOLE.md, "get and unlock"): by in-game name, a picker without one; each goes through an
     * existing command ([ConsoleCheats]). They glow in rainbow colours in the console, its suggestions and help.
     */
    @JvmStatic
    fun featured(console: DebugConsole): List<DebugConsole.Cmd> = listOf(
        DebugConsole.Cmd(
            ConsoleCheats.GET, listOf(DebugConsole.CmdParameter(CheatKindProcessor(ConsoleCheats.GET), "kind"), DebugConsole.CmdParameter(CheatNameProcessor(ConsoleCheats.GET), "name")),
            false, {}, "Get an item by its in-game English name (spaces as _): a weapon, drone, augment, crew member, system " +
                "or resources; hidden and event-only ones too. With no name a picker opens. Says what it granted and where. With no " +
                "room it goes where an event's reward goes (the over-capacity box, the crew screen); while one waits there, it " +
                "refuses another. Goes through WEAPON, DRONE, AUG, CREW, SYS, SCRAP, FUEL, MISSILES, DRONES or HULL.",
            raw = { line -> ConsoleCheats.run(console, line) }, args = "<kind> [name|amount]",
            group = Groups.GET, featured = true,
            examples = listOf("get weapon Burst_Laser_II", "get augment Scrap_Recovery_Arm", "get crew Engi", "get drone",
                "get system Cloaking", "get system *", "get scrap 500"),
        ),
        DebugConsole.Cmd(
            ConsoleCheats.UNLOCK, listOf(DebugConsole.CmdParameter(CheatKindProcessor(ConsoleCheats.UNLOCK), "kind"), DebugConsole.CmdParameter(CheatNameProcessor(ConsoleCheats.UNLOCK), "name")),
            false, {}, "Unlock a ship layout (<ship name>_A/_B/_C, * for any part of the name) or an achievement for good (profile). " +
                "With no name a picker opens. unlock ship * is SHIP ALL; one layout is the profile's own unlock (a mod's ship: " +
                "SHIP_CUSTOM); an achievement is ACH.",
            raw = { line -> ConsoleCheats.run(console, line) }, args = "<ship|achievement> [name]",
            group = Groups.GET, featured = true,
            examples = listOf("unlock ship The_Kestrel_B", "unlock ship The_Kestrel_*", "unlock ship *", "unlock ship",
                "unlock achievement Full_Arsenal", "unlock achievement *"),
        ),
    )

    /** Every command: the new ones, then ours, then Hyperspace's and the original's. */
    @JvmStatic
    fun build(console: DebugConsole): List<DebugConsole.Cmd> = featured(console) + DebugCommands(console).commands + SPECS.map { spec ->
        DebugConsole.Cmd(
            spec.name,
            if (spec.hint != null) listOf(DebugConsole.CmdParameter(spec.hint, spec.args)) else emptyList(),
            true, {}, spec.help,
            raw = { line -> HsConsole.run(console.game, line) },
            needsHs = spec.needsHs, args = spec.args, group = spec.group, examples = spec.examples,
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

    /**
     * Whether [key] (typing [c], 0 for none) closes the open console: Escape, or ` or the "Debug console" hotkey's key
     * ([consoleKey]) pressed unshifted. Shifted ([shift], or a character other than the key's own: `~` on the ` key) it
     * types its character (`~` is Lua's `~=`): Hyperspace's console box takes every typed character
     * (InputBox::TextInput) and only a cancel closes it (InputBox::TextEvent, TEXT_CANCEL).
     */
    @JvmStatic
    fun closes(key: Int, c: Char, consoleKey: Int?, shift: Boolean): Boolean {
        if (key == xyz.znix.xftl.sys.Input.KEY_ESCAPE) return true
        if (key != xyz.znix.xftl.sys.Input.KEY_GRAVE && (consoleKey == null || key != consoleKey)) return false
        if (shift) return false
        val own = if (key == xyz.znix.xftl.sys.Input.KEY_GRAVE) '`' else key.toChar().lowercaseChar()
        return c == 0.toChar() || c == own
    }
}
