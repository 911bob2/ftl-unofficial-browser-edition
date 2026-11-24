package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.game.TouchRouter
import xyz.znix.xftl.hs.systems.HsSystemInstall
import xyz.znix.xftl.hs.systems.HsTemporal
import xyz.znix.xftl.hs.systems.Temporal
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer

/**
 * Player report (release 2.2.3, Multiverse, the C.E.L. Cruiser A `PLAYER_SHIP_CEL`): the temporal manipulator showed
 * one button that did nothing, where Hyperspace has two. Hyperspace 1.23.2's TemporalBox (TemporalBoxVTable.cpp, the
 * constructor; TemporalSystem.cpp RenderBox, NewMouseMove, LeftMouseClick): a speed up button at (9, 9) and a slow down
 * button at (9, 35) of the box, both active while the system IsReady and shown only while it is off; a click arms the
 * system (SetArmed) unless it is locked, and CombatControl::SelectTarget queues the room a click picks next, which the
 * system's next loop starts (StartTimeDilation). The touch layout had put the box in its open system panel as a single
 * button pressed at no point of the box, so neither button was reached; it now has the two buttons.
 *
 * For every ship blueprint that lists the temporal system: each player ship (installed first where it starts without)
 * on the mouse and the touch layout, both buttons arm and start their dilation on a room, the buttons are off while it
 * runs; each enemy ship builds with the system and its AI runs a fight (TemporalSystem_Wrapper::AISelectTarget).
 */
internal object GameplayParityTemporalChecks {
    private const val DT = 0.05f
    private const val ENEMY_FIGHT_SECONDS = 30f
    private var stage = ""
    private var touchTime = 0L

    fun run(main: MainGame, container: GameContainer): String {
        val names = main.gameContent.blueprintManager.blueprints.values.filterIsInstance<LazyShipBlueprint>()
            .filter { it.listsSystem("temporal") }.map { it.name }.sorted()
        if (names.isEmpty()) return "temporal: no ship lists the system"
        val players = names.filter { it.startsWith("PLAYER_SHIP_") }
        val enemies = names - players.toSet()
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val touchWas = TouchMode.enabled
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            stage = ""
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name" + (if (stage.isEmpty()) "" else " ($stage)") + ": " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            for (ship in players) for (touch in listOf(false, true))
                case("$ship ${if (touch) "touch" else "mouse"}") { playerBox(main, container, ship, touch) }
            if (enemies.isNotEmpty()) case("enemies") { enemyAi(main, container, enemies, players.firstOrNull()) }
        } finally {
            TouchMode.enabled = touchWas
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "temporal ok: " + results.joinToString(", ")
    }

    private fun temporal(state: InGameState): Temporal? = state.player.systems.firstOrNull { it is Temporal } as Temporal?

    /** A finger tapping the canvas point [p], as the Android host routes it (TouchRouter). */
    private fun tap(state: InGameState, p: IPoint) {
        val hud = state.shipUI.touch ?: error("no touch HUD")
        val mouse = TouchRouter.Mouse { _, _, _, _ -> }
        touchTime += 100
        TouchRouter.route(hud, null, TouchRouter.DOWN, 0, p.x, p.y, touchTime, mouse)
        touchTime += 80
        TouchRouter.route(hud, null, TouchRouter.UP, 0, p.x, p.y, touchTime, mouse)
        var n = 0
        while (TouchRouter.afterStep() && n++ < 4) Unit
    }

    /** The mouse moved to ([x], [y]) for two drawn frames (the box sets its buttons' bActive as it draws), then clicked. */
    private fun click(main: MainGame, container: GameContainer, x: Int, y: Int) {
        val g = BrowserEngine.diagnosticGraphics()
        BrowserEngine.diagnosticMouse(x, y, 0, 0)
        repeat(2) { main.update(container, 0f); main.render(container, g) }
        BrowserEngine.diagnosticMouse(x, y, 1, 0)
        main.update(container, 0f)
        BrowserEngine.diagnosticMouse(x, y, 2, 0)
        main.update(container, 0f)
    }

    private fun playerBox(main: MainGame, container: GameContainer, ship: String, touch: Boolean): String {
        TouchMode.enabled = touch
        stage = "start"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, ship)
        check(!state.shipUI.isWindowOpen) { "a window is open" }
        val player = state.player
        var installed = ""
        var t = temporal(state)
        if (t == null) {
            // `start="false"`: bought from its slot as a store installs it.
            stage = "install"
            val slot = player.systemSlots.firstOrNull { it.system.name == "temporal" && !it.isInstalled } ?: error("no temporal slot")
            HsSystemInstall.install(player, slot)
            state.shipUI.updateButtons()
            t = temporal(state) ?: error("the installed temporal system is missing")
            installed = " (installed)"
        }
        val sys: Temporal = t
        player.purchasedReactorPower = 25
        repeat(sys.energyLevels) { if (sys.powerSelected < sys.energyLevels) sys.increasePower() }
        state.updateGameState(DT)
        check(sys.powerSupplied > 0 && sys.isReady) { "not ready: power ${sys.powerSupplied}/${sys.energyLevels} locked ${sys.isLocked}" }
        val room: Room = player.rooms.firstOrNull { it !== sys.room } ?: player.rooms[0]

        fun press(speedUp: Boolean) {
            if (touch) {
                val hud = state.shipUI.touch ?: error("no touch HUD")
                if (hud.diagnosticPanelButtons(sys).isEmpty()) tap(state, hud.diagnosticBoxPoint(sys) ?: error("no touch box"))
                val buttons = hud.diagnosticPanelButtons(sys)
                check(buttons.size == 2) { "the touch panel has ${buttons.size} buttons, not speed up and slow down" }
                main.render(container, BrowserEngine.diagnosticGraphics())
                val b = buttons[if (speedUp) 0 else 1]
                tap(state, xyz.znix.xftl.math.ConstPoint(b[0] + b[2] / 2, b[1] + b[3] / 2))
            } else {
                // The bar's buttons are made as it is drawn.
                main.render(container, BrowserEngine.diagnosticGraphics())
                val pos = sys.diagnosticBoxPos ?: error("no temporal box")
                click(main, container, pos.x + 9 + 11, pos.y + (if (speedUp) 9 else 35) + 11)
            }
        }

        fun pickRoom() {
            val p = state.shipUI.touchRoomCentre(room)
            if (touch) tap(state, p) else click(main, container, p.x, p.y)
        }

        for (speedUp in listOf(true, false)) {
            val what = if (speedUp) "speed up" else "slow down"
            stage = "$what button"
            press(speedUp)
            val want = if (speedUp) Temporal.ARM_SPEED else Temporal.ARM_SLOW
            check(sys.armState == want) { "$what: armed ${sys.armState}, not $want" }
            stage = "$what room"
            pickRoom()
            check(sys.armState == Temporal.ARM_NONE) { "$what: still armed after a room was picked" }
            state.updateGameState(DT)
            check(sys.turnedOn && sys.currentRoom === room && sys.isSpeeding == speedUp) {
                "$what: on ${sys.turnedOn} room ${sys.currentRoom?.id} (picked ${room.id}) speeding ${sys.isSpeeding}"
            }
            val dilation = HsTemporal.dilation(room)
            check(if (speedUp) dilation > 0 else dilation < 0) { "$what: room dilation $dilation" }
            repeat(20) { state.updateGameState(DT) }
            check(sys.turnedOn && sys.chargeLeft > 0f && sys.chargeLeft < 1f) { "$what: charge left ${sys.chargeLeft} after a second" }
            // While it runs the box shows no buttons (RenderBox only draws them while off): a press arms nothing.
            stage = "$what running"
            press(speedUp)
            check(sys.armState == Temporal.ARM_NONE) { "$what: armed ${sys.armState} while the dilation runs" }
            if (touch) main.render(container, BrowserEngine.diagnosticGraphics())
            // A jump ends it and clears the lock (TemporalSystem::_HS_Jump).
            sys.onJump()
            state.updateGameState(DT)
            check(!sys.turnedOn && sys.isReady) { "$what: not ready after the jump (on ${sys.turnedOn} locked ${sys.isLocked})" }
        }
        return "$ship$installed ${if (touch) "touch" else "mouse"}"
    }

    /**
     * Each enemy blueprint with the system, hostile against the first player ship that has it, through a fight: the
     * system is built, and how many used it (the AI picks targets by the rooms' crew, so not every fight does).
     */
    private fun enemyAi(main: MainGame, container: GameContainer, names: List<String>, playerShip: String?): String {
        TouchMode.enabled = false
        stage = "start"
        BrowserGameplayParityDiagnostics.fresh(main, container, playerShip ?: "PLAYER_SHIP_HARD")
        var used = 0
        val failed = ArrayList<String>()
        for ((i, name) in names.withIndex()) {
            stage = name
            try {
                val state = main.currentState as InGameState
                val elem = Element("ship")
                elem.setAttribute("name", "PROBE_$name")
                elem.setAttribute("auto_blueprint", name)
                state.debugSpawnShip(EnemyShipSpec(elem, state.blueprintManager, state.eventManager), Difficulty.NORMAL, 4, 4001 + i)
                state.debugFlags.noEnemyFire.set = true
                val enemy = state.enemy ?: error("no enemy")
                val t = enemy.systems.firstOrNull { it is Temporal } as Temporal? ?: error("built without the temporal system")
                check(t.energyLevels > 0) { "level ${t.energyLevels}" }
                var on = false
                var time = 0f
                while (time < ENEMY_FIGHT_SECONDS) {
                    state.updateGameState(DT)
                    time += DT
                    if (t.turnedOn) on = true
                    if (state.enemy !== enemy) break
                }
                if (on) used++
            } catch (e: Throwable) {
                failed += "$name: ${e.message ?: e::class.simpleName}"
            }
        }
        check(failed.isEmpty()) { failed.joinToString("; ") }
        check(used > 0) { "no enemy of ${names.size} used its temporal system in $ENEMY_FIGHT_SECONDS s" }
        return "${names.size} enemies built with it, $used used it in a fight"
    }
}
