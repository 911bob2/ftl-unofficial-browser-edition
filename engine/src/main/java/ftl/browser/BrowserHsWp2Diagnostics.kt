package ftl.browser

import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsShips
import xyz.znix.xftl.sys.GameContainer

/**
 * WP2 in-run UI probe (`BrowserEngine.hsWp2Probe`): sets up a run and leaves the engine in it, so a browser test can
 * click through the real input path and take frames. Commands (see tests/hs-wp2ui.mjs):
 * - `start:<ship blueprint>`: a diagnostic run with the start beacon answered, unpaused.
 * - `give:<blueprint>`: Ship.addBlueprint (forced), `slots:aug=N,cargo=M`, `cargo`: the cargo state (JSON).
 * - `crew:<n>`: recruit n more human crew (names T1..), `limit:<n>`: set the player's `crewLimit`,
 *   `pause`: pause the game, `state`: the ship window's paging state (JSON), `lua:<code>`: run Lua, result = its error text.
 */
object BrowserHsWp2Diagnostics {
    private var added = 0

    fun run(main: MainGame, container: GameContainer, cmd: String): String {
        val name = cmd.substringBefore(':')
        val arg = cmd.substringAfter(':', "")
        return when (name) {
            "start" -> {
                added = 0
                val state = BrowserHsCrewUiDiagnostics.start(main, container, arg)
                if (!state.isPaused) state.toggleQuickPause()
                "ok hs=${HS.active} crew=${state.player.sys.playerCrew.size}"
            }
            "crew" -> {
                val state = main.currentState as InGameState
                val human = state.blueprintManager["human"] as xyz.znix.xftl.crew.CrewBlueprint
                repeat(arg.toInt()) {
                    val room = state.player.rooms.first()
                    state.player.addCrewMemberInRoom(LivingCrewInfo(state, human, "T${++added}", 0), room, recordRecruitment = false)
                }
                "ok crew=${state.player.sys.playerCrew.size}"
            }
            "limit" -> {
                val state = main.currentState as InGameState
                HsShips.def(state.player.type.name).crewLimit = arg.toInt()
                "ok"
            }
            "pause" -> {
                val state = main.currentState as InGameState
                if (!state.isPaused) state.toggleQuickPause()
                "ok"
            }
            "give" -> {
                val state = main.currentState as InGameState
                val bp = state.blueprintManager[arg] as xyz.znix.xftl.Blueprint
                "ok ${state.player.addBlueprint(bp, true)}"
            }
            "slots" -> {
                // slots:aug=N,cargo=M sets the player's definition (CustomShipDefinition) and re-evaluates the equipment.
                val state = main.currentState as InGameState
                val def = HsShips.def(state.player.type.name)
                for (kv in arg.split(',')) {
                    val (k, v) = kv.split('=')
                    if (k == "aug") def.augSlots = v.toInt() else if (k == "cargo") def.cargoSlots = v.toInt()
                }
                state.player.cargoUpdated()
                "ok"
            }
            "cargo" -> {
                val p = (main.currentState as InGameState).player
                fun n(b: xyz.znix.xftl.Blueprint?) = if (b == null) "null" else BrowserDebug.quote(b.name)
                "{\"cargo\":${p.cargoBlueprints.joinToString(",", "[", "]") { n(it) }},\"all\":${p.allCargo().joinToString(",", "[", "]") { n(it) }}," +
                    "\"augs\":${p.augments.joinToString(",", "[", "]") { n(it) }},\"augSlots\":${p.augmentSlotCount},\"cargoSlots\":${p.cargoSlotCount}," +
                    "\"shown\":${p.overCapacityBoxShown.joinToString(",", "[", "]")},\"over\":${xyz.znix.xftl.hs.ship.HsEquipment.overCount(p)}}"
            }
            "caps" -> xyz.znix.xftl.hs.HS.capabilityReport()
            "state" -> (main.currentState as InGameState).shipUI.diagnosticShipWindow
            // lua:<code>: run the Lua chunk; its error text (error("R|...")) is the result.
            "lua" -> xyz.znix.xftl.hs.lua.HsLua.runString("wp2probe", arg) ?: "no-result"
            else -> error("unknown command $cmd")
        }
    }
}
