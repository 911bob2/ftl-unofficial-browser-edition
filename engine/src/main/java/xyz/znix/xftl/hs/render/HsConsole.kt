// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CommandConsole.cpp (CommandConsole::RunCommand,
// the CommandGui::KeyDown / InputBox::StartInput / TextEvent / TextInput / OnRender hooks, PrintHelper stays in HsLua.kt)
// and the original 1.6.14 InputBox / CommandGui::RunCommand (command words read from the executable, behaviour fixed by
// oracle sessions wp7-ui-console / wp7-ui-misc); the browser engine is AGPL-3.0, see docs/HYPERSPACE.md.
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.f
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsEvents
import xyz.znix.xftl.hs.events.HsFlow
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaFps
import xyz.znix.xftl.hs.ship.HsAchievements
import xyz.znix.xftl.hs.ship.HsShipUnlocks
import xyz.znix.xftl.hs.ship.HsStores
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Hyperspace's command console (`<console enabled="true"/>`): the `console` hotkey (default `\`) opens the original's
 * InputBox (a 530x260 window frame at (356, 210), "Command Prompt:" and a left-aligned text line with a 1 px cursor),
 * which pauses the game like any focus window. Return runs the text through [run] (HS's RunCommand first, then the
 * original's own commands) in the next frame once no event box is open, Escape cancels, Up recalls the history.
 *
 * Gated as HS gates it (CommandGui::KeyDown hook): the option on, no write-error dialog / menu / game over screen /
 * jump / open focus window, and the console key not also the options key. Under Hyperspace the port's own
 * development console does not open on backtick (that is HS's `speed` hotkey).
 */
object HsConsole {
    /** InputBox::bOpen. */
    @JvmField var open = false

    /** InputBox::finished: Return was pressed and the command waits for [update]. */
    private var finished = false

    private var text = ""
    private var cursor = 0

    /** For the diagnostics probe: the box and its text. */
    @JvmStatic fun debugState(): String = "open=$open text=$text cursor=$cursor"
    private val history = ArrayList<String>()
    private var historyIndex = 0

    /** TextInput's blink timer (1 s, restarted when the cursor moves). */
    private var blink = 0f
    private var blinkCursor = -1

    private const val MAX_LENGTH = 480
    private const val X = 381
    private const val Y = 235

    private var squishy = ""

    /** `CommandConsole::enabled`. */
    @JvmStatic
    fun enabled() = HS.active && HS.cfg().consoleEnabled

    @JvmStatic
    fun reset() {
        open = false; finished = false; text = ""; cursor = 0; history.clear(); historyIndex = 0; squishy = ""
    }

    /**
     * CommandGui::KeyDown hook for the `console` hotkey: opens the box when HS would (InputBox::StartInput: the text
     * is cleared, the history gets "LOAD PIRATE" first if empty and the index points at its last entry).
     */
    @JvmStatic
    fun tryOpen(game: InGameState, optionsKey: Boolean) {
        if (!enabled() || open) return
        if (game.shipUI.isWindowOpen || game.player?.browserJumping == true || optionsKey) return
        open = true; finished = false; text = ""; cursor = 0
        if (history.isEmpty()) history += "LOAD PIRATE"
        historyIndex = history.size - 1
    }

    private fun choiceBoxOpen(game: InGameState) = game.shipUI.hsChoiceBoxOpen()

    /**
     * CEvent text events and typed characters while the box is open (CommandGui::OnTextEvent / OnTextInput ignore them
     * while an event box is open). Returns true when the key was consumed.
     */
    @JvmStatic
    fun key(game: InGameState, key: Int, c: Char): Boolean {
        if (!open) return false
        if (finished || choiceBoxOpen(game)) return true
        when (key) {
            Input.KEY_ENTER -> {
                finished = true
                if (history.size > 50) history.clear()
                history += text
            }
            Input.KEY_ESCAPE -> { text = ""; cursor = 0; finished = true }
            Input.KEY_BACK -> if (cursor > 0) { text = text.removeRange(cursor - 1, cursor); cursor-- }
            Input.KEY_DELETE -> if (cursor < text.length) text = text.removeRange(cursor, cursor + 1)
            Input.KEY_LEFT -> cursor = (cursor - 1).coerceAtLeast(0)
            Input.KEY_RIGHT -> cursor = (cursor + 1).coerceAtMost(text.length)
            Input.KEY_HOME -> cursor = 0
            Input.KEY_END -> cursor = text.length
            Input.KEY_UP -> {
                if (history.isNotEmpty()) {
                    text = history[historyIndex.coerceIn(0, history.size - 1)]
                    cursor = text.length
                    historyIndex = if (historyIndex > 0) historyIndex - 1 else history.size - 1
                }
            }
            else -> if (c.code >= 32 && c.code != 127 && text.length < MAX_LENGTH) {
                var ch = c
                if (HS.cfg().consoleInvertCaps) {
                    if (ch in 'a'..'z') ch -= 32 else if (ch in 'A'..'Z') ch += 32
                }
                text = text.substring(0, cursor) + ch + text.substring(cursor)
                cursor++
            }
        }
        return true
    }

    /** CommandGui::OnLoop: a finished box closes and a non-empty text runs, once no event box is open. */
    @JvmStatic
    fun update(game: InGameState, delta: Float) {
        if (!open) return
        if (cursor != blinkCursor) { blinkCursor = cursor; blink = 0f }
        blink += delta
        if (blink > 1f) blink = 0f
        if (!finished || choiceBoxOpen(game)) return
        open = false; finished = false
        val command = text
        text = ""; cursor = 0
        if (command.isNotEmpty()) run(game, command)
    }

    /** InputBox::OnRender (CommandConsole.cpp hook). */
    @JvmStatic
    fun draw(game: InGameState, g: Graphics) {
        if (!open) return
        game.windowRenderer.render(X - 25, Y - 25, 530, 260)
        val font = NativeFonts.font(game::getFont, 8)
        val white = Colour.white
        // freetype::easy_printAutoNewlines(8, 381, 235, 490, mainText)
        val lines = font.font.wrapString("Command Prompt:", 490)
        lines.forEachIndexed { i, line -> font.print(X.f, (Y + i * font.lineHeight).f, line, white) }
        val top = Y + 20
        if (text.isNotEmpty()) font.print(X.f, top.f, text, white)
        // TextInput::OnRender: the cursor bar (255, 255, 50), 1 px wide after HS's GL_DrawRect hook, over the middle 80 % of the line
        if (blink < 0.5f) {
            g.colour = Colour(255, 255, 50)
            g.fillRect((X + font.advance(text.take(cursor))).f, top + 2f, 1f, 12f)
        }
    }

    /** The SQUISHY text (MouseControl::OnRender hook): right aligned at the top right in font 51. */
    @JvmStatic
    fun drawSquishy(game: InGameState) {
        if (squishy.isEmpty()) return
        NativeFonts.font(game::getFont, 51).printRight(1280f, 10f, squishy, Colour.white)
    }

    private fun printf(message: String) { /* the original logs; nothing on screen */ }

    private fun toInt(s: String): Int? = s.trim().toIntOrNull()

    // ------------------------------------------------------------------------------------------------ RunCommand

    /** CommandGui::RunCommand hook: [runHs] (CommandConsole::RunCommand), else the original's commands plus GOD's reactor. */
    @JvmStatic
    fun run(game: InGameState, command: String) {
        if (runHs(game, command)) return
        runVanilla(game, command)
        if (command.uppercase() == "GOD") {
            // HS: PowerManager currentPower.second = the ship definition's maxReactorLevel
            val ship = game.player ?: return
            val max = xyz.znix.xftl.hs.ship.HsShips.def(ship.type.name)?.maxReactorLevel
            if (max != null) ship.purchasedReactorPower = max
        }
    }

    /** CommandConsole::RunCommand. */
    private fun runHs(game: InGameState, command: String): Boolean {
        val ship = game.player
        val name = command.substringBefore(' ').uppercase()
        fun arg(from: Int) = command.substring(from).trim()
        when {
            name == "SQUISHY" && command.length > 8 -> {
                val title = arg(8)
                if (title.isNotEmpty()) squishy = title
                return true
            }
            name == "STORE" -> {
                if (command.length == 5) game.currentBeacon.hsCreateNewStore(game, game.eventCreator.currentLevel())
                else toInt(arg(6))?.let { game.currentBeacon.hsCreateNewStore(game, it) }
                game.shipUI.updateButtons()
                game.shipUI.hsOpenNewStore()
                return true
            }
            name == "FORCE_STORE" -> {
                if (command.length > 12) HsStores.forceCustomStore = arg(12)
                return true
            }
            name == "EXIT" -> {
                // StarMap::bOpen / bChoosingNewSector, then the original's EXIT (ForceExitBeacon) runs too
                game.shipUI.openSectorMap()
            }
            name == "KILL" && command.length > 5 -> {
                val crewName = arg(5)
                ship?.crew?.filterIsInstance<LivingCrew>()?.firstOrNull { it.info.name == crewName && it.ownerShip === ship }?.health = 0f
                return true
            }
            name == "SKILL" -> {
                for (crew in ship?.crew.orEmpty().filterIsInstance<LivingCrew>()) {
                    if (crew.ownerShip !== ship) continue
                    for (skill in Skill.entries) crew.info.skills[skill] = 1f
                }
                game.sounds.getSampleOrWarn("levelup")?.play()
            }
            name == "DELETECREW" || name == "COOLSWORDBRO" -> {
                val enemy = game.enemy
                if (enemy != null) for (crew in enemy.crew.filterIsInstance<LivingCrew>()) if (crew.ownerShip === enemy) crew.health = 0f
                return true
            }
            name == "SPEED" && command.length > 5 -> {
                toInt(arg(6))?.let { LuaFps.speedLevel = it }
                return true
            }
            name == "LUA" && command.length > 3 -> {
                if (HS.luaActive) HsLua.runString("console", arg(4))
            }
            command.uppercase() == "SYS ALL" -> {
                if (ship != null) sysAll(ship)
                return true
            }
            name == "DAMAGESYS" && command.length > 9 -> {
                val sys = ship?.let { s -> s.systems.firstOrNull { it.codename == arg(10) } }
                if (sys != null) sys.addDamage(sys.energyLevels)
                return true
            }
            name == "REMOVESYS" && command.length > 9 -> {
                if (ship != null) for (sys in ship.systems.filter { it.codename == arg(10) }) xyz.znix.xftl.hs.systems.HsSystemInstall.removeSystem(ship, sys)
                return true
            }
            name == "DEBUG" -> return true
            command == "SHIP ALL" -> { HsShipUnlocks.unlockAllShips(); return true }
            name == "SHIP_CUSTOM" -> {
                val ship2 = if (command.length > 11) arg(11) else ""
                if (HsShipUnlocks.customShipHasUnlock(ship2)) HsShipUnlocks.unlockShip(ship2, false, true, false)
                return true
            }
            name == "SHIP_CUSTOM_LOCK" -> {
                HsShipUnlocks.removeShipUnlock(if (command.length > 16) arg(16) else "")
                return true
            }
            name == "RICH" -> {
                if (ship != null) {
                    ship.scrap = maxOf(0, ship.scrap + 9999999)
                    if (ship.weapons != null) ship.missilesCount = maxOf(0, ship.missilesCount + 800)
                    ship.fuelCount += 800
                    ship.dronesCount = maxOf(0, ship.dronesCount + 800)
                }
                return true
            }
            name == "POOR" -> {
                if (ship != null) {
                    ship.scrap = maxOf(0, ship.scrap - 9999999)
                    if (ship.weapons != null) ship.missilesCount = maxOf(0, ship.missilesCount - 800)
                    ship.fuelCount = maxOf(0, ship.fuelCount - 800)
                    ship.dronesCount = maxOf(0, ship.dronesCount - 800)
                }
                return true
            }
            name == "FLEET" && command.length > 5 -> {
                toInt(arg(6))?.let { game.currentBeacon.sector.fleetAdvanceModifier += it }
                return true
            }
            name == "LOADEVENT" -> {
                if (command.length > 10) HsFlow.loadEvent(game, game.shipUI.hsHost, arg(10), true, -1, null)
                return true
            }
            name == "CREDITS" || name == "VICTORY" -> {
                game.browserProgress.skipVictoryAward()
                game.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
                return true
            }
            name == "VARIABLE" || name == "VAR" -> { variable(command, HsEvents.playerVariables); return true }
            name == "METAVARIABLE" || name == "METAVAR" || name == "MVAR" -> { variable(command, HsEvents.metaVariables); return true }
            name == "ACH" && command.length > 3 -> { HsAchievements.setAchievement(arg(4), false); return true }
            name == "ACH_LOCK" && command.length > 8 -> { HsAchievements.removeAchievement(arg(9)); return true }
            name == "SWITCHALL" && command.length > 9 -> { HsSwitchShip.switchShipTransfer(game, arg(10), 1); return true }
            name == "SWITCH" && command.length > 6 -> { HsSwitchShip.switchShip(game, arg(7)); return true }
            name == "WEAPON" && command.length > 6 -> {
                val bp = game.blueprintManager.getOrNull(arg(7)) as? AbstractWeaponBlueprint
                if (bp != null && ship != null) ship.addBlueprint(bp, true, false)
                return true
            }
        }
        return false
    }

    /** `VAR name [value]`: the token-compressed split on spaces; a value that is not an integer changes nothing. */
    private fun variable(command: String, map: MutableMap<String, Int>) {
        val parts = command.split(' ').filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val v = if (parts.size >= 3) parts[2].toIntOrNull() else null
            if (v != null) map[parts[1]] = v
        }
    }

    /** SYS ALL: every vanilla system, temporal and the custom ones the ship lacks and nothing replaces. */
    private fun sysAll(ship: Ship) {
        val ids = (0 until 16) + listOf(xyz.znix.xftl.hs.systems.HsSystems.SYS_TEMPORAL) +
                (xyz.znix.xftl.hs.systems.HsSystems.SYS_CUSTOM_FIRST..xyz.znix.xftl.hs.systems.HsSystems.lastSystemId())
        for (id in ids) addSystem(ship, id)
    }

    /** `!HasSystem(id) && SystemWillReplace(id) == SYS_INVALID` then ShipManager::AddSystem(id). */
    private fun addSystem(ship: Ship, id: Int) {
        val name = xyz.znix.xftl.hs.systems.HsSystems.idToName(id)
        if (name.isEmpty() || ship.systems.any { it.codename == name }) return
        if (xyz.znix.xftl.hs.systems.HsSystemInstall.willReplace(ship, name) != null) return
        xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, id)
    }

    // ------------------------------------------------------------------------------------- the original's commands

    /** CommandGui::RunCommand of 1.6.14 (the words read from the executable's string table). */
    private fun runVanilla(game: InGameState, command: String) {
        val ship = game.player ?: return
        val parts = command.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val word = parts[0].uppercase()
        val rest = command.trim().substringAfter(' ', "").trim()
        val n = parts.getOrNull(1)?.toIntOrNull()
        when (word) {
            "SCRAP" -> if (n != null) ship.scrap = maxOf(0, ship.scrap + n)
            "FUEL" -> if (n != null) ship.fuelCount += n
            "MISSILES" -> if (n != null && ship.weapons != null) ship.missilesCount = maxOf(0, ship.missilesCount + n)
            "DRONES" -> if (n != null) ship.dronesCount = maxOf(0, ship.dronesCount + n)
            // HULL n heals n (the oracle: 18/30 then HULL 4 gives 22/30, HULL 30 then 30/30); capped at the maximum
            "HULL" -> if (n != null) ship.health = (ship.health + n).coerceIn(0, ship.maxHealth)
            "WEAPON" -> (game.blueprintManager.getOrNull(rest) as? AbstractWeaponBlueprint)?.let { ship.addBlueprint(it, true, false) }
            "DRONE" -> (game.blueprintManager.getOrNull(rest) as? DroneBlueprint)?.let { ship.addBlueprint(it, true, false) }
            // ShipManager::AddAugmentation: only below three augments (ShipInfo::augCount), through
            // ShipObject::AddAugmentation (HS: a HIDDEN one is free, the others up to augSlots and AUGMENT_SLOT); an owned
            // augment is added again and nothing opens a box (no AUGMENT_FULL)
            "AUG" -> if (ship.augments.size < 3) {
                if (rest.startsWith("HIDDEN ")) ship.hs?.addHidden(rest.removePrefix("HIDDEN "))
                else (game.blueprintManager.getOrNull(rest) as? AugmentBlueprint)?.let {
                    if (ship.augments.size < ship.augmentSlotCount) { ship.augments.add(it); ship.cargoUpdated() }
                }
            }
            "CREW" -> (game.blueprintManager.getOrNull(rest) as? CrewBlueprint)?.let {
                ship.addCrewMember(LivingCrewInfo.generateRandom(it, game), false)
            }
            "SYS" -> {
                val id = xyz.znix.xftl.hs.systems.HsSystems.nameToId(rest)
                if (id >= 0) addSystem(ship, id)
            }
            "GOD" -> {
                for (system in ship.systems) system.energyLevels = system.maxLevel
                ship.purchasedReactorPower = ship.maxReactorPower
            }
            "DELETE" -> game.enemy?.let { enemy ->
                enemy.damage(enemy.rooms.random(ftl.browser.BrowserRandom), xyz.znix.xftl.weapons.Damage.hullOnly(100))
                enemy.health = 0
            }
            "EXIT" -> game.shipUI.openSectorMap()
            "EVENT" -> consoleEvent(game, rest)
            "SHIP" -> if (rest.uppercase() == "ALL") HsShipUnlocks.unlockAllShips()
            "ACH" -> HsAchievements.setAchievement(rest, false)
            else -> printf("unknown command")
        }
    }

    /**
     * EVENT name: CommandGui::RunCommand only stores it (CommandGui::loadEvent, seed -1); WorldManager::CheckLoadEvent
     * then takes GetBaseEvent(name, level, ignoreUnique) and, when there is one, runs ClearLocation,
     * puts the event on the current location and runs CreateLocation: HS's ClearLocation hooks (event
     * buttons, background objects), a new scene for the location, and the event shown as an arrival (the oracle
     * sw2-ebtn: the start beacon's station is gone after EVENT WP7R_BTN).
     */
    private fun consoleEvent(game: InGameState, name: String) {
        val created = HsFlow.getEvent(game, name, true, -1) ?: return
        HsFlow.beforeCreateLocation(null)
        game.clearLocation()
        val beacon = game.currentBeacon ?: return
        beacon.createdEvent = created
        // the new event's scene: SwitchBackground from its own lists (the start beacon's changeBackground station is gone)
        beacon.scene = null
        beacon.hsSpaceImage = null
        xyz.znix.xftl.environment.BeaconScene.arrive(game, beacon)
        game.shipUI.showEventDialogue(created, true)
    }
}
