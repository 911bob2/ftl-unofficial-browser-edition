package xyz.znix.xftl.devutil

import org.jdom2.Document
import ftl.browser.BrowserSaxBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.shipgen.ShipGenerator
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.Damage
import xyz.znix.xftl.weapons.DroneBlueprint
import java.io.IOException
import java.lang.reflect.Parameter
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.stream.Collectors
import kotlin.math.roundToInt
import kotlin.random.Random


class DebugCommands(console: DebugConsole) : ConsoleCommandProvider(console) {
    // Explicit calls retain command methods in TeaVM and avoid JVM reflection.
    // The commands that shared a name with a Hyperspace or original one are renamed (ConsoleRegistry.RENAMED); scrap,
    // fuel, missiles, drones and crew did what those do and went (ConsoleRegistry.FOLDED).
    @Suppress("UNCHECKED_CAST")
    override val commands: List<DebugConsole.Cmd> = listOf(
        DebugConsole.Cmd("dev-rich", listOf(), false, { cmdRich() }, "Set scrap to 5000 and fuel, drone parts and missiles to 99", group = ConsoleRegistry.Groups.RESOURCES),
        DebugConsole.Cmd("dev-poor", listOf(), false, { cmdPoor() }, "Set scrap, fuel, drone parts and missiles to 0", group = ConsoleRegistry.Groups.RESOURCES),
        DebugConsole.Cmd("dev-weapon", listOf(DebugConsole.CmdParameter(BlueprintTypeProcessor(AbstractWeaponBlueprint::class.java), "name")), false, { a -> cmdWeapon(a[0] as AbstractWeaponBlueprint) }, "Select a weapon, and add it to the ship's cargo area", group = ConsoleRegistry.Groups.GEAR, examples = listOf("dev-weapon LASER_BURST_2")),
        DebugConsole.Cmd("dev-drone", listOf(DebugConsole.CmdParameter(BlueprintTypeProcessor(DroneBlueprint::class.java), "drone")), false, { a -> cmdDrone(a[0] as DroneBlueprint) }, "Select a drone, and add it to the ship's cargo area", group = ConsoleRegistry.Groups.GEAR),
        DebugConsole.Cmd("dev-aug", listOf(), false, { cmdAugment() }, "Pick an augment from a list, and add it to the ship's cargo area", group = ConsoleRegistry.Groups.GEAR),
        DebugConsole.Cmd("dev-store", listOf(), false, { cmdStore() }, "Give this beacon a store (opened from the Store button)", group = ConsoleRegistry.Groups.WORLD),
        DebugConsole.Cmd("dev-event", listOf(), false, { cmdEvent() }, "Search the events, and load the one picked at this beacon", group = ConsoleRegistry.Groups.WORLD),
        DebugConsole.Cmd("fix", listOf(), true, { a -> cmdFix(a[0] as List<String>) }, "Fix the ship's hull and all systems, clearing ion damage", group = ConsoleRegistry.Groups.SHIP),
        DebugConsole.Cmd("cld", listOf(), false, { cmdClearDrones() }, "CLear all Drones - destroys all currently-deployed drone instances", group = ConsoleRegistry.Groups.ENEMY),
        DebugConsole.Cmd("ecrew", listOf(DebugConsole.CmdParameter(BlueprintTypeProcessor(CrewBlueprint::class.java), "race")), false, { a -> cmdEnemyCrew(a[0] as CrewBlueprint) }, "Spawn a new crewmember on the enemy ship, same args as 'CREW'", group = ConsoleRegistry.Groups.ENEMY, examples = listOf("ecrew mantis")),
        DebugConsole.Cmd("boarder", listOf(DebugConsole.CmdParameter(BlueprintTypeProcessor(CrewBlueprint::class.java), "race")), false, { a -> cmdBoarder(a[0] as CrewBlueprint) }, "Spawn a hostile intruder on the player ship, same args as 'CREW'", group = ConsoleRegistry.Groups.ENEMY, examples = listOf("boarder mantis")),
        DebugConsole.Cmd("skills", listOf(), false, { cmdSkills() }, "Edit the crew's skills", group = ConsoleRegistry.Groups.GEAR),
        DebugConsole.Cmd("dev-kill", listOf(), false, { cmdKill() }, "Destroy the enemy ship (100 hull damage, then 0 hull)", group = ConsoleRegistry.Groups.ENEMY),
        DebugConsole.Cmd("killcrew", listOf(), false, { cmdKillCrew() }, "Kill one all of your crewmembers", group = ConsoleRegistry.Groups.GEAR),
        DebugConsole.Cmd("sectors", listOf(), false, { cmdSectors() }, "Open the sector map, regardless of the current beacon", group = ConsoleRegistry.Groups.WORLD),
        DebugConsole.Cmd("boss", listOf(), false, { cmdBoss() }, "Spawn the sector boss (flagship) at the current beacon", group = ConsoleRegistry.Groups.ENEMY),
        DebugConsole.Cmd("kill-ecrew", listOf(), false, { cmdKillEnemyCrew() }, "Kill all crew aboard the enemy ship (including any boarding you)", group = ConsoleRegistry.Groups.ENEMY),
        DebugConsole.Cmd("system", listOf(DebugConsole.CmdParameter(SystemArgCompleter, "name")), false, { a -> cmdSystem(a[0] as String) }, "Unlock a system on the current ship, or 'list' or 'all'", group = ConsoleRegistry.Groups.SHIP, examples = listOf("system list", "system all", "system cloaking")),
        DebugConsole.Cmd("spawn-ship", listOf(DebugConsole.CmdParameter(EnemyShipSpecProcessor, "spec"), DebugConsole.CmdParameter(StringTypeProcessor, "difficulty/seed")), false, { a -> cmdSpawnShip(a[0] as EnemyShipSpec, a[1] as String) }, "Spawn an enemy ship directly from a seed", group = ConsoleRegistry.Groups.ENEMY, examples = listOf("spawn-ship PIRATE_INTERCEPTOR 3.normal")),
        DebugConsole.Cmd("enemy-weapon", listOf(), true, { a -> cmdEnemyWeapon(a[0] as List<String>) }, "Add or remove (the the remove argument) an enemy weapon", group = ConsoleRegistry.Groups.ENEMY, examples = listOf("enemy-weapon add", "enemy-weapon clear")),
        DebugConsole.Cmd("upall", listOf(), false, { cmdUpgradeAll() }, "UPgrade ALL systems on the player ship to the maximum level", group = ConsoleRegistry.Groups.SHIP),
        DebugConsole.Cmd("downall", listOf(), false, { cmdDowngradeAll() }, "Downgrade all systems on the player ship to their starting level", group = ConsoleRegistry.Groups.SHIP),
        DebugConsole.Cmd("set", listOf(DebugConsole.CmdParameter(DebugFlagProcessor, "flag")), false, { a -> cmdSet(a[0] as String) }, "Turn on or off debug flags", group = ConsoleRegistry.Groups.DEV, examples = listOf("set help", "set vis")),
        DebugConsole.Cmd("damage", listOf(DebugConsole.CmdParameter(IntTypeProcessor, "amount")), false, { a -> cmdDamage(a[0] as Int) }, "Apply a given amount of damage to the player ship (or negative to heal)", group = ConsoleRegistry.Groups.SHIP, examples = listOf("damage 5", "damage -5")),
        DebugConsole.Cmd("breach", listOf(), false, { cmdBreach() }, "Spawn a hull breach in a random room of the player ship", group = ConsoleRegistry.Groups.SHIP),
        DebugConsole.Cmd("ion", listOf(DebugConsole.CmdParameter(IntTypeProcessor, "amount")), false, { a -> cmdIon(a[0] as Int) }, "Apply ion damage to a random system of the player ship, like a weapon hit", group = ConsoleRegistry.Groups.SHIP, examples = listOf("ion 2")),
        DebugConsole.Cmd("wreck", listOf(DebugConsole.CmdParameter(SystemArgCompleter, "system")), true, { a -> cmdWreck(a[0] as String, a[1] as List<String>) }, "Break a system of the player ship ('wreck doors'), or ionise it ('wreck doors ion [bars]'). 'fix' repairs it", group = ConsoleRegistry.Groups.SHIP, examples = listOf("wreck doors", "wreck shields ion 2")),
        DebugConsole.Cmd("autofire", listOf(), true, { a -> cmdAutofire(a[0] as List<String>) }, "Toggle autofire globally, or for one weapon slot (1-4)", group = ConsoleRegistry.Groups.SHIP, examples = listOf("autofire", "autofire 1")),
        DebugConsole.Cmd("hurt", listOf(DebugConsole.CmdParameter(IntTypeProcessor, "amount")), false, { a -> cmdHurt(a[0] as Int) }, "Damage all of the player's crewmembers by a given amount (negative heals)", group = ConsoleRegistry.Groups.GEAR, examples = listOf("hurt 20")),
        DebugConsole.Cmd("dev-skill", listOf(DebugConsole.CmdParameter(StringTypeProcessor, "skill")), true, { a -> cmdSkill(a[0] as String, a[1] as List<String>) }, "Give the first player crewmember experience in a skill: 'dev-skill shields [count]'. Names: piloting/engines/shields/weapons/repairs/combat", group = ConsoleRegistry.Groups.GEAR, examples = listOf("dev-skill shields 2")),
        DebugConsole.Cmd("force-hack", listOf(DebugConsole.CmdParameter(BlueprintTypeProcessor(SystemBlueprint::class.java), "system")), false, { a -> cmdForceHack(a[0] as SystemBlueprint) }, "Forces the enemy to hack a given player system", group = ConsoleRegistry.Groups.ENEMY, examples = listOf("force-hack shields")),
        DebugConsole.Cmd("super-shield", listOf(), true, { a -> cmdSuperShield(a[0] as List<String>) }, "Give the player (or enemy) a super-shield (see help sub-cmd)", group = ConsoleRegistry.Groups.ENEMY, examples = listOf("super-shield 5", "super-shield help")),
        DebugConsole.Cmd("env", listOf(), false, { cmdEnvironment() }, "Change the environment at the current beacon", group = ConsoleRegistry.Groups.WORLD),
        DebugConsole.Cmd("dump-save", listOf(), false, { cmdDumpSave() }, "Save the game to XML, and print it to standard output", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("save-load", listOf(), false, { cmdSaveLoad() }, "Save the game to XML, and load it back in.", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("gc", listOf(), false, { cmdGC() }, "Manually trigger Java's Garbage Collector.", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("save", listOf(DebugConsole.CmdParameter(StringTypeProcessor, "name")), false, { a -> cmdSave(a[0] as String) }, "Save a named browser checkpoint: save name", group = ConsoleRegistry.Groups.DEV, examples = listOf("save before-boss")),
        DebugConsole.Cmd("load", listOf(DebugConsole.CmdParameter(StringTypeProcessor, "name")), false, { a -> cmdLoad(a[0] as String) }, "Load a named browser checkpoint: load name", group = ConsoleRegistry.Groups.DEV, examples = listOf("load before-boss")),
        DebugConsole.Cmd("download", listOf(), false, { cmdDownload() }, "Download the current run as an XML checkpoint", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("import", listOf(), false, { cmdImport() }, "Choose and import a browser XML checkpoint", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("inspect", listOf(), false, { cmdInspect() }, "Inspect current input, UI, weapons, RNG and recording state", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("mark", listOf(), false, { cmdMark() }, "Mark an issue in the active diagnostic recording", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("gameover", listOf(DebugConsole.CmdParameter(EnumTypeProcessor(GameOverWindow.Outcome::class.java), "outcome")), false, { a -> cmdGameOver(a[0] as GameOverWindow.Outcome) }, "End the game with the win/loose screen.", group = ConsoleRegistry.Groups.WORLD, examples = listOf("gameover WIN")),
        DebugConsole.Cmd("ach-set", listOf(DebugConsole.CmdParameter(AchievementProcessor, "achievement"), DebugConsole.CmdParameter(EnumTypeProcessor(Difficulty::class.java), "difficulty")), false, { a -> cmdAchSet(a[0] as Achievement, a[1] as Difficulty) }, "Set the difficulty an achievement was achieved with", group = ConsoleRegistry.Groups.PROFILE, examples = listOf("ach-set ACH_FULL_ARSENAL HARD")),
        DebugConsole.Cmd("ach-del", listOf(DebugConsole.CmdParameter(AchievementProcessor, "achievement")), false, { a -> cmdAchSet(a[0] as Achievement) }, "Remove an achievement", group = ConsoleRegistry.Groups.PROFILE),
        DebugConsole.Cmd("ship-unlock", listOf(DebugConsole.CmdParameter(ShipFamilyProcessor, "ship"), DebugConsole.CmdParameter(EnumTypeProcessor(Difficulty::class.java), "difficulty")), false, { a -> cmdUnlockShip(a[0] as ShipFamily, a[1] as Difficulty) }, "Set the difficulty a ship family's unlock quest was completed with", group = ConsoleRegistry.Groups.PROFILE, examples = listOf("ship-unlock PLAYER_SHIP_CIRCLE NORMAL")),
        DebugConsole.Cmd("ship-lock", listOf(DebugConsole.CmdParameter(ShipFamilyProcessor, "ship")), false, { a -> cmdLockShip(a[0] as ShipFamily) }, "Un-completes a ship unlock quest", group = ConsoleRegistry.Groups.PROFILE),
        DebugConsole.Cmd("layout-unlock", listOf(DebugConsole.CmdParameter(StringTypeProcessor, "ship"), DebugConsole.CmdParameter(EnumTypeProcessor(Difficulty::class.java), "difficulty")), false, { a -> cmdUnlockLayout(a[0] as String, a[1] as Difficulty) }, "Unlock an official ship layout", group = ConsoleRegistry.Groups.PROFILE, examples = listOf("layout-unlock PLAYER_SHIP_HARD_2 EASY")),
        DebugConsole.Cmd("reset-ftl", listOf(), false, { cmdResetFTL() }, "Reset the player's FTL charge timer", group = ConsoleRegistry.Groups.SHIP),
        DebugConsole.Cmd("reload-console", listOf(), false, { cmdReloadConsole() }, "Reload the console (useful with Java HotSwap)", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("reload-flags", listOf(), false, { cmdReloadFlags() }, "Reload the debug flags (useful with Java HotSwap)", group = ConsoleRegistry.Groups.DEV),
        DebugConsole.Cmd("help", listOf(DebugConsole.CmdParameter(HelpTopicProcessor, "search")), true, {}, "The command reference: every command by group, with its usage, what it does and examples. Type after help to search it; tap or click a command to fill it in", raw = { line -> cmdHelp(line.substringAfter(' ', "").trim()) }, args = "[search]", group = ConsoleRegistry.Groups.DEV, examples = listOf("help", "help get"))
    )

    @ConsoleCommand(name = "dev-rich")
    @CmdHelp("Get a huge amount of scrap, fuel, drones, and missiles")
    private fun cmdRich() {
        addLine("Resources added.")

        ship.scrap = 5000
        ship.fuelCount = 99
        ship.missilesCount = 99
        ship.dronesCount = 99
    }

    @ConsoleCommand(name = "dev-poor")
    @CmdHelp("Remove all our scrap, fuel, drones, and missiles")
    private fun cmdPoor() {
        addLine("Resources removed.")

        ship.scrap = 0
        ship.fuelCount = 0
        ship.missilesCount = 0
        ship.dronesCount = 0
    }

    @ConsoleCommand(name = "dev-weapon")
    @CmdHelp("Select a weapon, and add it to the ship's cargo area")
    private fun cmdWeapon(@ParName("name") weapon: AbstractWeaponBlueprint) {
        if (ship.addBlueprint(weapon, false)) {
            addLine("Added weapon ${weapon.translateTitle(game)} to ship inventory.")
        } else {
            addLine("No space in cargo hold, can't add weapon.")
        }
    }

    @ConsoleCommand(name = "dev-drone")
    @CmdHelp("Select a drone, and add it to the ship's cargo area")
    private fun cmdDrone(@ParName("drone") drone: DroneBlueprint) {
        if (ship.addBlueprint(drone, false)) {
            addLine("Added drone ${drone.translateTitle(game)} to ship inventory.")
        } else {
            addLine("No space in cargo hold, can't add drone.")
        }
    }

    @ConsoleCommand(name = "dev-aug")
    @CmdHelp("Select an augment, and add it to the ship's cargo area")
    private fun cmdAugment() {
        console.getAugment { augment ->
            if (ship.addBlueprint(augment, false)) {
                addLine("Added augment ${augment.translateTitle(game)} to ship inventory.")
            } else {
                addLine("No space in cargo hold, can't add augment.")
            }
        }
    }

    @ConsoleCommand(name = "dev-store")
    @CmdHelp("Create a store at this beacon")
    private fun cmdStore() {
        game.currentBeacon.hasStore = true
        game.shipUI.updateButtons()

        addLine("A store is now available at this beacon.")
    }

    @ConsoleCommand(name = "dev-event")
    @CmdHelp("Load an event at this beacon")
    private fun cmdEvent() {
        console.getEvent { event ->
            // Clear any previously-set beacon power limits, left over
            // from a previous event.
            game.currentBeacon.powerLimitEffects.clear()
            game.currentBeacon.event = event.resolve()
            game.currentBeacon.clearEnvironment()
            game.player.updateScriptedPowerLimits()

            game.shipUI.showEventDialogue(game.currentBeacon.event, ftl.browser.BrowserRandom.nextInt())
        }
    }

    @ConsoleCommand(name = "fix")
    @CmdHelp("Fix the ship's hull and all systems, clearing ion damage")
    private fun cmdFix(@CmdVarArg args: List<String>) {
        val targetShip: Ship

        if (args.isEmpty()) {
            targetShip = ship
        } else if (args.size == 1 && args[0].lowercase(Locale.UK) == "enemy") {
            targetShip = game.enemy ?: run {
                addLine("No enemy ship present.")
                return
            }
        } else {
            addLine("Invalid arguments for 'fix' - takes either one argument 'enemy' or no arguments (for the player)")
            return
        }

        for (system in targetShip.systems) {
            system.damagedEnergyLevels = 0
            system.ionTimer = 0f
            system.ionPowerLimit = null
            system.powerLimitChanged()
        }

        for (room in targetShip.rooms) {
            for (idx in room.fires.indices) {
                room.fires[idx] = null
                room.breaches[idx] = null
            }
        }

        targetShip.health = targetShip.maxHealth

        addLine("The ship has been repaired, all regular and ion damage was removed.")
    }

    @ConsoleCommand(name = "cld")
    @CmdHelp("CLear all Drones - destroys all currently-deployed drone instances")
    private fun cmdClearDrones() {
        fun clearFor(target: Ship) {
            val drones = target.drones
            if (drones != null) {
                for (info in drones.drones) {
                    info?.instance?.destroy()
                }
            }

            // Remove the visuals for drones that somehow got stuck.
            // This can happen if you spawn a new ship at the same
            // beacon while the old one had some drones deployed.
            target.externalDrones.clear()

            // Clone the crew list, since we'll be modifying it if
            // we find any drones.
            for (crew in ArrayList(target.crew)) {
                if (crew !is AbstractIndoorsDrone.Pawn)
                    continue

                crew.removeFromShip()
            }

            // Kill the hacking drone, since it's not technically a drone here
            target.hacking?.removeProbe()
        }

        clearFor(ship)
        game.enemy?.let { clearFor(it) }

        addLine("All drones have been cleared from all ships")
    }

    @ConsoleCommand(name = "ecrew")
    @CmdHelp("Spawn a new crewmember on the enemy ship, same args as 'crew'")
    private fun cmdEnemyCrew(@ParName("race") race: CrewBlueprint) {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship!")
            return
        }
        cmdCrewImpl(race, enemy)
    }

    private fun cmdCrewImpl(race: CrewBlueprint, targetShip: Ship, isIntruder: Boolean = false) {
        val info = LivingCrewInfo.generateRandom(race, game)
        targetShip.addCrewMember(info, false, isIntruder)
    }

    @ConsoleCommand(name = "boarder")
    @CmdHelp("Spawn a hostile intruder on the player ship, same args as 'crew'")
    private fun cmdBoarder(@ParName("race") race: CrewBlueprint) {
        cmdCrewImpl(race, ship, isIntruder = true)
    }

    @ConsoleCommand(name = "skills")
    @CmdHelp("Edit the crew's skills")
    private fun cmdSkills() {
        class SkillBox(val x: Int, val y: Int, val width: Int, val height: Int, val crew: LivingCrew, val skill: Skill)

        console.continued = object : DebugConsole.ContinuedCommand {
            val boxes = ArrayList<SkillBox>()
            var dragging: SkillBox? = null

            override val prompt: String get() = "ENTER TO EXIT> "

            override fun run(line: String) {
                // Do nothing, the skill customisation is graphical
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                super.render(gc, g, height)

                boxes.clear()

                val x = 20
                var y = height.roundToInt() + 5

                val nameIconWidth = 90

                val boxHeight = 30
                val boxWidth = gc.width - x * 2

                val skillWidth = (boxWidth - nameIconWidth) / 6

                // Draw the info text
                g.colour = Colour(55, 55, 55, 180)
                g.fillRect(x.f, y.f, boxWidth.f, 20f)
                console.font.drawString(
                    x + 20f,
                    y + 15f,
                    "Drag to adjust skills, right-click to toggle level",
                    Colour.white
                )
                y += 25

                for (crew in ship.crew) {
                    if (crew !is LivingCrew)
                        continue

                    g.colour = Colour(55, 55, 55, 180)
                    g.fillRect(x.f, y.f, boxWidth.f, boxHeight.f)

                    crew.drawPortrait(x, y, false, 1f)

                    console.font.drawString(x + 30f, y + 20f, crew.info.name, Colour.white)

                    for ((skillId, skill) in Skill.entries.withIndex()) {
                        val skillX = x + nameIconWidth + skillWidth * skillId

                        val icon = game.getImg(skill.iconPath)
                        icon.draw(skillX, y)

                        // We'll re-use this as a slider
                        val barX = skillX + icon.width + 5
                        val barY = y + 10
                        val barWidth = skillWidth - icon.width - 10
                        val barHeight = 8

                        crew.info.drawSkillProgressBar(g, barX, barY, barWidth, barHeight, skill)

                        boxes += SkillBox(barX, y, barWidth, boxHeight, crew, skill)
                    }

                    y += boxHeight + 5
                }
            }

            override fun mouseReleased(button: Int, x: Int, y: Int) {
                dragging = null
            }

            override fun mousePressed(button: Int, x: Int, y: Int) {
                dragging = null
                val box = boxes.firstOrNull { x in it.x..it.x + it.width && y in it.y..it.y + it.height }

                // Right-click toggles between the upper/lower levels
                if (box != null && button == Input.MOUSE_RIGHT_BUTTON) {
                    val newLevel = when (box.crew.getSkillLevel(box.skill)) {
                        SkillLevel.BASE -> 0.5f
                        else -> 0f
                    }
                    box.crew.info.skills[box.skill] = newLevel
                }

                if (button == Input.MOUSE_LEFT_BUTTON) {
                    dragging = box

                    // Make a single click change the value
                    mouseDragged(x, y, x, y)
                }
            }

            override fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
                val box = dragging ?: return

                val dragProgress = ((newX - box.x) / box.width.f).coerceIn(0f..1f)

                // Dragging doesn't switch between the green/nothing and green/yellow modes
                val oldValue = box.crew.info.skills.getValue(box.skill)
                val newValue = when {
                    oldValue < 0.5f -> (dragProgress / 2f).coerceIn(0f..0.4999f)
                    else -> 0.5f + dragProgress / 2f
                }
                box.crew.info.skills[box.skill] = newValue
            }

            override fun keyPressed(key: Int, c: Char): Boolean {
                // Handle all input to block typing, except for enter to let the user close this.
                return key != Input.KEY_ENTER
            }
        }
    }

    @ConsoleCommand(name = "dev-kill")
    @CmdHelp("Destroy the enemy ship")
    private fun cmdKill() {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship")
            return
        }

        enemy.damage(enemy.rooms.random(ftl.browser.BrowserRandom), Damage.hullOnly(100))

        // In case nodmg is set, manually set the health to zero.
        enemy.health = 0

        addLine("Added 100 points of damage to the enemy ship")
    }

    @ConsoleCommand(name = "killcrew")
    @CmdHelp("Kill one all of your crewmembers")
    private fun cmdKillCrew() {
        val options = ArrayList<Pair<String, () -> Unit>>()

        val allCrew = ship.crew.mapNotNull { it as? LivingCrew }

        if (allCrew.isEmpty()) {
            addLine("No living crew on this ship.")
            return
        }

        options += Pair("Kill all player crew") {
            for (crew in allCrew) {
                if (crew.ownerShip != ship)
                    continue
                crew.health = 0f
            }
            addLine("Killed all player crew.")
            return@Pair
        }

        if (allCrew.any { it.ownerShip != ship }) {
            options += Pair("Kill all boarders") {
                for (crew in allCrew) {
                    if (crew.ownerShip == ship)
                        continue
                    crew.health = 0f
                }
                addLine("Killed all enemy boarders on player ship.")
                return@Pair
            }
        }

        options += allCrew.map {
            var name = "${it.blueprint.name} - ${it.info.name}"

            if (it.ownerShip != ship) {
                name += " (boarder)"
            }

            Pair(name) {
                it.health = 0f
                addLine("Killed crewmember ${it.info.name} (${it.blueprint.name})")
                return@Pair
            }
        }

        console.pickFromList("KILL CREW", options) { it() }
    }

    @ConsoleCommand(name = "sectors")
    @CmdHelp("Open the sector map, regardless of the current beacon")
    private fun cmdSectors() {
        game.shipUI.openSectorMap()
        addLine("Sector map window opened.")
    }

    @ConsoleCommand(name = "boss")
    @CmdHelp("Spawn the sector boss (flagship) at the current beacon")
    private fun cmdBoss() {
        val sector = game.currentBeacon.sector
        val boss = sector.bosses.firstOrNull() as? FlagshipBoss ?: run {
            val created = FlagshipBoss(sector, game, game.currentBeacon)
            sector.bosses.add(created)
            created
        }

        boss.debugTeleportTo(game.currentBeacon)
        addLine("Flagship placed at the current beacon - jump away and back to fight it.")
    }

    @ConsoleCommand(name = "kill-ecrew")
    @CmdHelp("Kill all crew aboard the enemy ship (including any boarding you)")
    private fun cmdKillEnemyCrew() {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship")
            return
        }

        var count = 0
        for (crew in enemy.crew.mapNotNull { it as? LivingCrew }) {
            if (crew.ownerShip != enemy)
                continue

            crew.health = 0f
            count++
        }

        addLine("Killed $count enemy crewmembers")
    }

    @ConsoleCommand(name = "system")
    @CmdHelp("Unlock a system on the current ship, or 'list' or 'all'")
    private fun cmdSystem(
        @ParName("name") @ParType(SystemArgCompleter::class) systemName: String
    ) {

        if (systemName.equals("list", ignoreCase = true)) {
            addLine("Systems on the player ship:")

            val grid = ArrayList<List<String>>()
            for (room in ship.rooms) {
                val system = room.system
                if (system != null) {
                    grid += listOf(system.codename, "(purchased)")
                }

                for (slot in room.systemSlots) {
                    val installed = ship.systems.any { it.blueprint == slot.system }
                    if (!installed) {
                        grid += listOf(slot.system.type, "")
                    }
                }
            }

            console.addLineGrid(grid, 10)
            return
        }

        if (systemName.equals("all", ignoreCase = true)) {
            // Note: ignore the eight-system limit
            for (room in ship.rooms) {
                if (room.system != null)
                    continue

                // If there's multiple systems available (eg medbay and clonebay),
                // just pick the first one.
                val system = room.systemSlots.firstOrNull() ?: continue
                room.setSystem(system)
            }
            ship.instantPowerShields()
            addLine("Unlocked all systems on the player ship.")
            return
        }

        val selectedSystem = ship.systemSlots.firstOrNull { it.system.name.equals(systemName, ignoreCase = true) }

        if (selectedSystem == null) {
            addLine("No such system named '$systemName' on the current ship, try using 'system list'.")
            return
        }

        if (selectedSystem.isInstalled) {
            addLine("System $systemName is already purchased.")
            return
        }

        selectedSystem.room.setSystem(selectedSystem)
        // Like the original's console SYS command, which goes through AddSystem.
        selectedSystem.room.system?.let { ship.browserPowerNewSystem(it) }

        addLine("Unlocked system $systemName")
    }

    private object SystemArgCompleter : ArgumentTypeProcessor {
        override fun validate(param: Parameter) {
            check(param.type == String::class.java)
        }

        override fun process(value: String, console: DebugConsole): Any {
            return value
        }

        override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
            if (previous is BlueprintAndExtrasCompleter && previous.owner == this)
                return previous

            return BlueprintAndExtrasCompleter(
                debugConsole, this, SystemBlueprint::class.java,
                listOf("list", "all")
            )
        }
    }

    @ConsoleCommand(name = "spawn-ship")
    @CmdHelp("Spawn an enemy ship directly from a seed")
    private fun cmdSpawnShip(
        @ParName("spec") spec: EnemyShipSpec,
        @ParName("difficulty/seed") seedB64: String
    ) {
        if (seedB64.getOrNull(1) == '.') {
            // sector.difficulty mode, with a random seed

            // Make the sector one-indexed
            val sector = seedB64[0].toString().toInt() - 1
            if (sector < 0) {
                addLine("In sector.difficulty mode, the sector must be one-indexed.")
                return
            }

            val difficultyStr = seedB64.substring(2)
            val difficulty = Difficulty.entries.firstOrNull { it.name.equals(difficultyStr, ignoreCase = true) }

            if (difficulty == null) {
                val difficultyList = Difficulty.entries.joinToString(", ") { it.name }
                addLine("Invalid difficulty name '$difficultyStr', should be one of: $difficultyList")
                return
            }

            val seed = ftl.browser.BrowserRandom.nextInt()

            game.debugSpawnShip(spec, difficulty, sector, seed)

            val seedStr = ShipGenerator.seedToString(sector, difficulty, seed)
            addLine("Spawning ship from spec '${spec.name}' with seed '$seedStr'")
            return
        }

        val seedBytes = try {
            Base64.getDecoder().decode(seedB64)
        } catch (ex: IllegalArgumentException) {
            addLine("Invalid base64 seed '$seedB64': ${ex.localizedMessage}")
            return
        }

        if (seedBytes.size != 6) {
            addLine("Non-six-byte seed: ${seedBytes.size}")
            return
        }

        val buf = ByteBuffer.wrap(seedBytes)
        val sector = buf.get().toInt()
        val difficulty = Difficulty.entries[buf.get().toInt()]
        val seed = buf.getInt()

        addLine("Spawning ship, and setting it as hostile.")
        game.debugSpawnShip(spec, difficulty, sector, seed)
    }

    @ConsoleCommand(name = "enemy-weapon")
    @CmdHelp("Add or remove (the the remove argument) an enemy weapon")
    private fun cmdEnemyWeapon(@CmdVarArg args: List<String>) {
        if (args.size > 1) {
            addLine("Too many arguments - see the 'help' subcommand.")
            return
        }

        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship present.")
            return
        }

        if (args.isEmpty() || args[0].equals("add", ignoreCase = true)) {
            // Pick a weapon and add it to the enemy cargo
            console.getWeapon { weapon ->
                for (i in 0 until (enemy.weaponSlots ?: enemy.hardpoints.size)) {
                    val hp = enemy.hardpoints[i]

                    if (hp.weapon != null)
                        continue

                    hp.weapon = weapon.buildInstance(enemy)
                    enemy.cargoUpdated()
                    addLine("Added weapon ${weapon.name} to enemy hardpoint $i.")
                    return@getWeapon
                }

                addLine("No free hardpoints on the enemy ship")
            }
        } else if (args[0].equals("remove", ignoreCase = true)) {
            val weapons = enemy.hardpoints.mapNotNull { it.weapon }
            val namedWeapons = weapons.map { Pair(it.type.name, it) }
            console.pickFromList("TO REMOVE", namedWeapons) { toRemove ->
                for (hp in enemy.hardpoints) {
                    if (hp.weapon != toRemove)
                        continue

                    hp.weapon = null
                    enemy.cargoUpdated()
                    addLine("Removed weapon ${toRemove.type.name} from the enemy ship.")
                    return@pickFromList
                }

                addLine("The weapon has already disappeared!?")
            }
        } else if (args[0].equals("clear", ignoreCase = true)) {
            // Remove all the enemy weapons
            for (hp in enemy.hardpoints) {
                hp.weapon = null
            }
            enemy.cargoUpdated()
            addLine("Removed all enemy weapons")
            return
        } else {
            addLine("Usage: enemy-weapon [add|remove|clear]")
            addLine("The add mode (default if no arguments are set) lets you select a weapon")
            addLine("to give the enemy ship.")
            addLine("The remove mode lets you remove enemy weapons via a list.")
            addLine("The clear mode removes all the enemy weapons.")
            return
        }
    }

    @ConsoleCommand(name = "upall")
    @CmdHelp("UPgrade ALL systems on the player ship to the maximum level")
    private fun cmdUpgradeAll() {
        // The level the upgrade screen stops at (a Hyperspace ship's own `max`), not the blueprint's: Multiverse's
        // shields blueprint goes to 16 where its ships stop at 8.
        for (system in ship.systems) {
            system.energyLevels = system.maxLevel
        }
        ship.purchasedReactorPower = ship.maxReactorPower
        addLine("Upgraded all systems to maximum level")
    }

    @ConsoleCommand(name = "downall")
    @CmdHelp("Downgrade all systems on the player ship to their starting level")
    private fun cmdDowngradeAll() {
        for (system in ship.systems) {
            system.energyLevels = system.blueprint.startPower
        }
        ship.purchasedReactorPower = 5
        addLine("Downgraded all systems to their starting level")
    }

    @ConsoleCommand(name = "set")
    @CmdHelp("Turn on or off debug flags")
    private fun cmdSet(
        @ParName("flag") @ParType(DebugFlagProcessor::class) arg: String
    ) {

        val flagManager = game.debugFlags

        if (arg.equals("help", ignoreCase = true)) {
            addLine("Use 'set <name>' to enable a debug flag, or 'set !<name>' to disable it.")
            addLine("Or use 'set all' or 'set !all' to turn everything on or off.")
            addLine("Use 'set vis' or 'set !vis' to turn all debug visuals on or off.")
            addLine("Valid names:")

            val grid = ArrayList<List<String>>()
            for (flag in flagManager.all) {
                grid += listOf(flag.shortName, flag.set.toString(), flag.fullName, flag.description)
            }

            console.addLineGrid(grid, 15)
            return
        }

        // If the name starts with '!', then it means to turn the effect off
        val status = arg.getOrNull(0) != '!'
        val name = arg.trimStart('!').lowercase(Locale.UK)

        when (name) {
            "all" -> {
                for (flag in flagManager.all) {
                    flag.set = status
                }
                addLine("Set all debug flags to $status")
                return
            }

            "vis" -> {
                for (flag in flagManager.all) {
                    if (!flag.isVisual)
                        continue
                    flag.set = status
                }
                addLine("Set all debug visuals to $status")
                return
            }
        }

        for (flag in flagManager.all) {
            if (flag.shortName != name)
                continue

            flag.set = status
            addLine("Set debug flag '$name' (${flag.fullName}) to $status")
            return
        }

        addLine("Unknown debug flag name '$name', see 'set help' for more information.")
    }

    private object DebugFlagProcessor : ArgumentTypeProcessor {
        override fun validate(param: Parameter) = check(param.type == String::class.java)
        override fun process(value: String, console: DebugConsole): Any = value

        override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
            if (previous is DebugFlagCompleter && previous.owner == this)
                return previous

            return DebugFlagCompleter(debugConsole, this)
        }
    }

    private class DebugFlagCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
        BasicCompletionEngine<DebugFlagManager.DebugFlag>(console, owner) {

        private var isNegating = false

        override val items get() = console.game.debugFlags.all
        override fun getItemName(item: DebugFlagManager.DebugFlag): String = item.fullName
        override val autoCompleteSuggestion: String?
            get() = bestSuggestion?.let {
                priorCommandText + getCompletionString(it)
            }

        override fun getCompletionString(item: DebugFlagManager.DebugFlag): String {
            var result = item.shortName
            if (isNegating)
                result = "!$result"
            return result
        }

        override fun update(currentToken: String, positionInInput: Int) {
            // Strip out the ! at the start (used to disable a flag), so
            // it doesn't interfere with the search.
            val withoutNegate = currentToken.removePrefix("!")
            isNegating = currentToken.startsWith("!")

            super.update(withoutNegate, positionInInput)
        }

        override fun sortEntries(entries: ArrayList<DebugFlagManager.DebugFlag>, currentToken: String) {
            super.sortEntries(entries, currentToken)

            // Move options that match exactly to the top, if there's enough
            // characters to do so meaningfully.
            if (currentToken.length <= 2)
                return

            val exactMatches = entries.filter { it.shortName.startsWith(currentToken) }.toMutableList()
            exactMatches.sortBy { it.shortName }
            entries.removeAll(exactMatches.toSet())
            entries.addAll(0, exactMatches)
        }
    }

    @ConsoleCommand(name = "damage")
    @CmdHelp("Apply a given amount of damage to the player ship (or negative to heal)")
    private fun cmdDamage(@ParName("amount") amount: Int) {
        ship.health -= amount

        addLine("Applied $amount points of damage to the player ship")
    }

    @ConsoleCommand(name = "breach")
    @CmdHelp("Spawn a hull breach in a random room of the player ship")
    private fun cmdBreach() {
        val room = ship.rooms.random(ftl.browser.BrowserRandom)
        room.spawnBreach()

        addLine("Breached room ${ship.rooms.indexOf(room)} (${room.system?.codename ?: "no system"})")
    }

    @ConsoleCommand(name = "ion")
    @CmdHelp("Apply ion damage to a random system of the player ship, like a weapon hit")
    private fun cmdIon(@ParName("amount") amount: Int) {
        val room = ship.rooms.filter { it.system != null }.random(ftl.browser.BrowserRandom)
        val damage = Damage()
        damage.ionDamage = amount
        ship.damage(room, damage)

        addLine("Applied $amount ion damage to ${room.system!!.codename}")
    }

    @ConsoleCommand(name = "wreck")
    @CmdHelp("Break a system of the player ship ('wreck doors'), or ionise it ('wreck doors ion [bars]'). 'fix' repairs it")
    private fun cmdWreck(
        @ParName("system") @ParType(SystemArgCompleter::class) systemName: String,
        @CmdVarArg args: List<String>
    ) {
        val target = ship.systems.firstOrNull { it.codename.equals(systemName, ignoreCase = true) }

        if (target == null) {
            addLine("The player ship has no '$systemName' system - see 'system list'.")
            return
        }

        if (args.isNotEmpty() && args[0].equals("ion", ignoreCase = true)) {
            val bars = args.getOrNull(1)?.toIntOrNull() ?: 3
            target.dealDamage(0, bars)
            addLine("Ionised ${target.codename} for ${5 * bars}s - power now ${target.undamagedEnergy}/${target.energyLevels}")
            return
        }

        target.damagedEnergyLevels = target.energyLevels
        target.powerLimitChanged()
        addLine("Broke the ${target.codename} system (all bars). Use 'fix' to repair it.")
    }

    @ConsoleCommand(name = "autofire")
    @CmdHelp("Toggle autofire globally, or for one weapon slot (1-4)")
    private fun cmdAutofire(@CmdVarArg args: List<String>) {
        val weapons = ship.weapons ?: return addLine("This ship has no weapons system")

        if (args.size > 1) {
            addLine("Usage: autofire [slot 1-4]")
            return
        }

        val slot = args.getOrNull(0)?.toIntOrNull()
        when {
            slot == null -> weapons.toggleAutofire()
            slot in 1..4 && ship.hardpoints.getOrNull(slot - 1)?.weapon != null ->
                weapons.toggleAutofireSlot(slot - 1)
            else -> {
                addLine("Weapon slot $slot is empty")
                return
            }
        }

        val states = ship.hardpoints.mapIndexed { i, hp ->
            "${i + 1}: ${if (weapons.isAutofire(i)) "ON" else "off"}"
        }
        addLine("Global autofire ${if (weapons.autofire) "ON" else "off"}; weapons: ${states.joinToString(", ")}")
    }

    @ConsoleCommand(name = "hurt")
    @CmdHelp("Damage all of the player's crewmembers by a given amount (negative heals)")
    private fun cmdHurt(@ParName("amount") amount: Int) {
        var count = 0

        for (crew in ship.crew.mapNotNull { it as? LivingCrew }) {
            if (crew.ownerShip != ship)
                continue

            // Route through the engine's real damage entry point rather
            // than writing health directly, so debug damage behaves like
            // gameplay damage (low-health warning, death guard, etc).
            crew.dealDamage(FireDamage(amount.f))
            count++
        }

        addLine("Applied $amount points of damage to $count crewmembers")
    }

    @ConsoleCommand(name = "dev-skill")
    @CmdHelp("Give the first player crewmember experience in a skill: 'skill shields [count]'. Names: piloting/engines/shields/weapons/repairs/combat")
    private fun cmdSkill(@ParName("skill") skillName: String, @CmdVarArg args: List<String>) {
        val skill = Skill.entries.firstOrNull {
            it.name.equals(skillName, ignoreCase = true) || it.xmlName.equals(skillName, ignoreCase = true)
        }
        if (skill == null) {
            addLine("Unknown skill '$skillName' - try ${Skill.entries.joinToString("/") { it.xmlName }}.")
            return
        }

        val count = args.getOrNull(0)?.toIntOrNull() ?: 1
        if (count < 1 || count > 100) {
            addLine("Count must be between 1 and 100.")
            return
        }

        val crew = ship.sys.playerCrew.firstOrNull { it.ownerShip == ship }
        if (crew == null) {
            addLine("No player crewmembers.")
            return
        }

        repeat(count) {
            crew.addSkillPoint(skill)
        }

        addLine("Gave $count '$skill' experience to ${crew.info.name}")
    }

    @ConsoleCommand(name = "force-hack")
    @CmdHelp("Forces the enemy to hack a given player system")
    private fun cmdForceHack(@ParName("system") blueprint: SystemBlueprint) {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship.")
            return
        }

        val hacking = enemy.hacking
        if (hacking == null) {
            addLine("The enemy ship doesn't have a hacking system.")
            return
        }

        val system = ship.systems.firstOrNull { it.blueprint == blueprint }
        if (system == null) {
            addLine("No such player system: ${blueprint.name}")
            return
        }

        // Clear the current hacking probe, if it's already been fired.
        hacking.removeProbe()

        hacking.selectTarget(system.room!!)

        // Force the drone to launch, so the AI doesn't get a chance
        // to change the target, in case it updates before
        // the hacking system does.
        hacking.update(0f)

        addLine("Launched hacking probe at player system ${blueprint.name}")
    }

    @ConsoleCommand(name = "super-shield")
    @CmdHelp("Give the player (or enemy) a super-shield (see help sub-cmd)")
    private fun cmdSuperShield(@CmdVarArg args: List<String>) {
        var amount = 5
        var max = 5

        var target: Ship = ship

        // With no arguments, give the player a normal super-shield

        if (args.getOrNull(0).equals("help", ignoreCase = true)) {
            addLine("Usage: super-shield [amount[/max]] [player|enemy]")
            addLine("The amount should be either a single number (the shield strength), or")
            addLine("two numbers in the form amount/max to set the max super-shield level.")
            addLine("The target ship can be optionally specified, but defaults to the player ship.")
            addLine("When run without arguments, it gives the player a regular (level-5) super-shield.")
            return
        }

        if (args.isNotEmpty()) {
            val parts = args[0].split("/")

            if (parts.size > 2) {
                addLine("Invalid super-shield amount '${args[0]}' - 'see 'super-shield help'.")
                return
            }

            amount = parts[0].toIntOrNull() ?: run {
                addLine("Invalid super-shield amount '${args[0]}' - see 'super-shield help'.")
                return
            }

            if (parts.size == 2) {
                max = parts[1].toIntOrNull() ?: run {
                    addLine("Invalid super-shield max amount '${args[0]}' - see 'super-shield help'.")
                    return
                }
            }
        }

        if (args.size >= 2) {
            target = when (args[1].lowercase(Locale.UK)) {
                "player" -> ship

                "enemy" -> game.enemy ?: run {
                    addLine("No enemy ship present.")
                    return
                }

                else -> {
                    addLine("Invalid target ship '${args[1]}' (should be 'player' or 'enemy') - see 'super-shield help'")
                    return
                }
            }
        }

        // Set the max super-shield first, since the
        // normal value is clamped to it.
        target.maxSuperShield = max
        target.superShield = amount

        addLine("Added $amount/$max super-shield to ship ${target.name}.")
    }

    @ConsoleCommand(name = "env")
    @CmdHelp("Change the environment at the current beacon")
    private fun cmdEnvironment() {
        val items = Beacon.EnvironmentType.entries.map { Pair(it.name, it) }

        console.pickFromList("Environment", items) { type ->
            val beacon = game.currentBeacon
            val environment = type.create(game, beacon)
            beacon.debugSetEnvironment(environment)
        }
    }

    @ConsoleCommand(name = "dump-save")
    @CmdHelp("Save the game to XML, and print it to standard output")
    private fun cmdDumpSave() {
        val doc = try {
            game.saveGameState()
        } catch (ex: Exception) {
            addLine("Exception saving game (more in stdout): $ex")
            ex.printStackTrace()
            return
        }

        val xmlOutput = XMLOutputter(Format.getPrettyFormat())
        val xmlString = xmlOutput.outputString(doc)

        println("Savegame dump:")
        println(xmlString.trim())

        addLine("Savegame dumped to standard output.")
    }

    @ConsoleCommand(name = "save-load")
    @CmdHelp("Save the game to XML, and load it back in.")
    private fun cmdSaveLoad() {
        val successful = game.mainGame.doSaveLoadGame()
        if (!successful) {
            addLine("Failed to reload game, more details are in the console.")
        }
    }

    @ConsoleCommand(name = "gc")
    @CmdHelp("Manually trigger Java's Garbage Collector.")
    private fun cmdGC() {
        System.gc()
        addLine("Finished Java GC operation.")
    }

    @ConsoleCommand(name = "save")
    @CmdHelp("Save a named browser checkpoint: save name")
    private fun cmdSave(@ParName("name") name: String) {
        ftl.browser.BrowserCheckpoints.put(name, ftl.browser.BrowserXml.write(game.saveGameState()))
        addLine("Checkpoint saved: $name")
    }

    @ConsoleCommand(name = "load")
    @CmdHelp("Load a named browser checkpoint: load name")
    private fun cmdLoad(@ParName("name") name: String) {
        val xml = ftl.browser.BrowserCheckpoints.get(name)
        if (xml == null) { addLine("No checkpoint: $name"); return }
        game.mainGame.loadSavedGame(ftl.browser.BrowserXml.parse(xml))
    }

    @ConsoleCommand(name = "download")
    @CmdHelp("Download the current run as an XML checkpoint")
    private fun cmdDownload() { ftl.browser.BrowserCheckpoints.download(ftl.browser.BrowserXml.write(game.saveGameState())) }

    @ConsoleCommand(name = "import")
    @CmdHelp("Choose and import a browser XML checkpoint")
    private fun cmdImport() { ftl.browser.BrowserCheckpoints.importFile() }

    @ConsoleCommand(name = "inspect")
    @CmdHelp("Inspect current input, UI, weapons, RNG and recording state")
    private fun cmdInspect() { addLine(ftl.browser.BrowserDebug.snapshot(game.mainGame)) }

    @ConsoleCommand(name = "mark")
    @CmdHelp("Mark an issue in the active diagnostic recording")
    private fun cmdMark() { ftl.browser.BrowserDebug.mark() }

    @ConsoleCommand(name = "gameover")
    @CmdHelp("End the game with the win/loose screen.")
    private fun cmdGameOver(@ParName("outcome") outcome: GameOverWindow.Outcome) {
        game.shipUI.showGameOverScreen(outcome)
    }

    @ConsoleCommand(name = "ach-set")
    @CmdHelp("Set the difficulty an achievement was achieved with")
    private fun cmdAchSet(
        @ParName("achievement") ach: Achievement,
        @ParName("difficulty") difficulty: Difficulty
    ) {
        // Get rid of the achievement first, since unlockAchievement won't
        // do anything if we try to decrease the difficulty an achievement
        // was unlocked with.
        game.mainGame.profile.deleteAchievement(ach)

        game.mainGame.profile.unlockAchievement(ach, difficulty)
    }

    @ConsoleCommand(name = "ach-del")
    @CmdHelp("Remove an achievement")
    private fun cmdAchSet(@ParName("achievement") ach: Achievement) {
        game.mainGame.profile.deleteAchievement(ach)
    }

    @ConsoleCommand(name = "ship-unlock")
    @CmdHelp("Set the difficulty a ship family's unlock quest was completed with")
    private fun cmdUnlockShip(
        @ParName("ship") family: ShipFamily,
        @ParName("difficulty") difficulty: Difficulty
    ) {
        if (family.unlockId == null) {
            game.mainGame.profile.unlockLayout(family.ships.first(), difficulty)
            return
        }

        // Same as achievements, remove the old higher-difficulty unlock
        game.mainGame.profile.deleteShipUnlock(family)
        game.mainGame.profile.unlockShip(family, difficulty)
    }

    @ConsoleCommand(name = "ship-lock")
    @CmdHelp("Un-completes a ship unlock quest")
    private fun cmdLockShip(@ParName("ship") family: ShipFamily) {
        if (family.unlockId == null) {
            game.mainGame.profile.deleteLayoutUnlock(family.ships.first())
            return
        }

        // Same as achievements, remove the old higher-difficulty unlock
        game.mainGame.profile.deleteShipUnlock(family)
        game.mainGame.profile.deleteLayoutUnlock(family.ships.first())
    }

    private fun cmdUnlockLayout(ship: String, difficulty: Difficulty) {
        if (!game.content.shipFamilies.byShipId.containsKey(ship)) { addLine("No such official ship layout '$ship'"); return }
        game.mainGame.profile.unlockLayout(ship, difficulty)
    }

    @ConsoleCommand(name = "reset-ftl")
    @CmdHelp("Reset the player's FTL charge timer")
    private fun cmdResetFTL() {
        ship.ftlChargeProgress = 0f
        addLine("Reset the player's FTL charge progress.")
    }

    @ConsoleCommand(name = "reload-console")
    @CmdHelp("Reload the console (useful with Java HotSwap)")
    private fun cmdReloadConsole() {
        // This is useful for development if you add a new console command
        // and don't want to restart the game - you can use HotSwap to add
        // your changes, but the command map is only created when the debug
        // console is first opened so new commands won't show up.
        //
        // Note the new instance is not created until you re-open the console,
        // so you can use it if that's in some way useful.
        //
        // I would recommend using DCEVM, which is a set of patches to OpenJDK
        // that greatly enhances what you can hot-reload (in particular, you
        // can add, modify and remove fields). The easiest way to use it
        // is to download the JetBrans Runtime (JBR) build of OpenJDK, and
        // use it with the '-XX:+AllowEnhancedClassRedefinition' VM flag.

        game.reloadDebugConsole()

        // Don't bother adding a line, it'll immediately be lost.
    }

    @ConsoleCommand(name = "reload-flags")
    @CmdHelp("Reload the debug flags (useful with Java HotSwap)")
    private fun cmdReloadFlags() {
        // Same idea and notes as the reload-console command. Read its comment.
        game.reloadDebugFlags()
        addLine("Reloaded debug flags")
    }

    @ConsoleCommand(name = "help")
    @CmdHelp("The command reference")
    private fun cmdHelp(search: String) {
        // The reference (ConsoleReference) is the completer of help's argument: it opens with the line "help ".
        console.input = if (search.isEmpty()) "help " else "help $search"
        val shown = ConsoleReference.matching(console.commands, search)
        if (search.isNotEmpty()) {
            if (shown.isEmpty()) addLine("No command matches '$search'.")
            for (cmd in shown) {
                addLine(cmd.usage + "  -  " + cmd.helpText + if (cmd.needsHs) " (needs Hyperspace)" else "")
                if (cmd.examples.isNotEmpty()) addLine("    e.g. " + cmd.examples.joinToString("   "))
            }
            return
        }
        addLine("Commands (any case; the upper-case ones are Hyperspace's and the original game's). The reference is open below:")

        val grid = ArrayList<List<String>>()
        for (cmd in console.commands) {
            grid += listOf(cmd.name, cmd.args, cmd.helpText + if (cmd.needsHs) " (needs Hyperspace)" else "")
        }

        console.addLineGrid(grid, 15)
    }
}
