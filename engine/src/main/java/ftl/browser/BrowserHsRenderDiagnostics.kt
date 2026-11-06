package ftl.browser

import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.render.HsOverlay
import xyz.znix.xftl.hs.render.HsTitleScreen

/**
 * Hyperspace render checks and capture helpers (`BrowserEngine.hsRenderProbe(cmd)`), test-only. Commands, separated
 * by `;`: `state` (JSON of the render state), `splash:<name>` (force the title splash, for screenshot comparisons
 * with an oracle frame that drew that splash), `altMode:<bool>`, `ftlCharged`, `victory`, `start:<player ship>`, `fight:<enemy blueprint>[,<enemy hull>[,<player ship>]]` (a paused
 * Kestrel fight with that enemy blueprint, for HUD crops against the oracle's combat frames), `menu`, `jump` (the jump-out to the first neighbouring beacon).
 */
object BrowserHsRenderDiagnostics {
    fun run(game: MainGame, container: xyz.znix.xftl.sys.GameContainer, cmd: String): String {
        val out = ArrayList<String>()
        for (part in (if (cmd.startsWith("lua:")) listOf(cmd) else cmd.split(';')).map { it.trim() }.filter { it.isNotEmpty() }) {
            val (op, arg) = part.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (op) {
                "state" -> out += state()
                "splash" -> {
                    check(HS.active) { "Hyperspace is not active" }
                    val s = HsTitleScreen.config().splashes.firstOrNull { it.image == arg } ?: error("No splash $arg")
                    HsTitleScreen.current = s
                    out += "\"splash\":${BrowserDebug.quote(s.image)}"
                }
                "fight" -> out += fight(game, container, arg)
                "flagship" -> out += flagship(game, container, arg)
                // `enemypower` the enemy's power now; `enemypower:run:N` closes windows, unpauses and reports every second for N frames
                "enemypower" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    if (arg.startsWith("run:")) {
                        repeat(6) { st.shipUI.diagnosticCloseWindow(); game.update(container, DT) }
                        if (st.isPaused) st.toggleQuickPause()
                        val samples = ArrayList<String>()
                        repeat(arg.removePrefix("run:").toInt()) { i ->
                            game.update(container, DT)
                            if (i % 20 == 19) samples += enemyPower(st)
                        }
                        out += "\"enemyPowerRun\":[" + samples.joinToString(",") + "]"
                    } else out += "\"enemyPower\":" + enemyPower(st)
                }
                // a new run with that player ship, its first event left open (the beacon frame)
                "start" -> {
                    game.startDiagnosticGame(arg.ifEmpty { "PLAYER_SHIP_HARD" }, xyz.znix.xftl.game.Difficulty.NORMAL)
                    repeat(20) { game.update(container, DT) }
                    out += "\"start\":true"
                }
                // our own probe Lua (the render-probe markers), run like the HS console's LUA command
                "lua" -> {
                    val err = xyz.znix.xftl.hs.lua.HsLua.runString("hsRenderProbe", arg)
                    out += "\"lua\":" + (err?.let { BrowserDebug.quote(it) } ?: "null")
                }
                // the player's FTL drive full (the jump button's ready colours)
                "ftlCharged" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    st.player.ftlChargeProgress = 1f
                    out += "\"ftlCharged\":true"
                }
                // jump out to the current beacon's first neighbour (the jump-out zoom, for SHIP_JUMP / LAYER_FRONT frames)
                "jump" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    st.player.ftlChargeProgress = 1f
                    val target = st.currentBeacon.neighbours.first()
                    st.beginJumpOut(target)
                    out += "\"jump\":true"
                }
                // close the windows and unpause (a paused fight does not run the wreck's explosion)
                "unpause" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    st.shipUI.diagnosticCloseWindow()
                    if (st.isPaused) st.toggleQuickPause()
                    out += "\"unpause\":${!st.isPaused}"
                }
                "menu" -> { game.switchToMainMenu(); out += "\"menu\":true" }
                // the HS console's CREDITS / VICTORY (CommandGui::Victory): the victory screen with its credits
                "victory" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    st.shipUI.showGameOverScreen(xyz.znix.xftl.game.GameOverWindow.Outcome.WIN)
                    out += "\"victory\":true"
                }
                // append authored nodes to the loaded hyperspace.xml (the oracle session's hs_options "append"), before the game starts
                "hsxml" -> {
                    check(HS.active) { "Hyperspace is not active" }
                    val text = String(BrowserFiles.read(HS.HYPERSPACE_XML), Charsets.UTF_8)
                    val at = text.lastIndexOf("</FTL>")
                    val merged = text.substring(0, at) + arg + text.substring(at)
                    HS.config = xyz.znix.xftl.hs.HsConfig.parse(BrowserXml.parse(merged).rootElement)
                    out += "\"hsxml\":true"
                }
                // drop the open event window and pause (the oracle session leaves the beacon with no window)
                "closewin" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    st.shipUI.diagnosticCloseWindow()
                    repeat(4) { game.update(container, DT) }
                    st.shipUI.diagnosticCloseWindow()
                    if (!st.isPaused) st.toggleQuickPause()
                    out += "\"closewin\":true"
                }
                // add authored events (the nodes between <FTL> and </FTL>) to the running game's event data
                "evxml" -> {
                    val st = game.currentState as xyz.znix.xftl.game.InGameState
                    val root = BrowserXml.parse("<FTL>$arg</FTL>").rootElement
                    st.eventManager.diagnosticAppend(org.jdom2.Document(root.clone() as org.jdom2.Element))
                    out += "\"evxml\":true"
                }
                // queue an event by name (what an event button or a background-object timer does)
                "event" -> {
                    xyz.znix.xftl.hs.events.HsEvents.eventQueue.addLast(xyz.znix.xftl.hs.events.EventQueueEvent(arg, "", -1))
                    out += "\"event\":\"$arg\""
                }
                "altMode" -> { HsOverlay.altMode = arg.toBoolean(); HsOverlay.altModeChanged = true; xyz.znix.xftl.hs.render.HsMoreInfo.onLoop(); out += "\"altMode\":${HsOverlay.altMode}" }
                else -> error("Unknown hsRenderProbe command $op")
            }
        }
        return "{" + out.joinToString(",") + "}"
    }

    private const val DT = .05f
    /** MV's first beacon: the new-profile prompt, then the start text ("Proceed with your mission"). */
    private const val MAX_PRESSES = 2

    private fun fight(main: MainGame, container: xyz.znix.xftl.sys.GameContainer, arg: String): String {
        val parts = arg.split(',')
        val bpName = parts[0]
        val hullArg = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val playerShip = parts.getOrNull(2) ?: "PLAYER_SHIP_HARD"
        main.startDiagnosticGame(playerShip, xyz.znix.xftl.game.Difficulty.NORMAL)
        // Choose option 1 of the start beacon's event only (later events, such as MV's storage menu that a queued
        // event opens, stay as they are rather than being driven by more key presses).
        var presses = 0
        repeat(60) {
            main.update(container, DT)
            if (presses < MAX_PRESSES && (main.currentState as xyz.znix.xftl.game.InGameState).shipUI.isWindowOpen) {
                BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
                BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
                presses++
            }
        }
        val state = main.currentState as xyz.znix.xftl.game.InGameState
        val elem = org.jdom2.Element("ship").setAttribute("name", "HS_RENDER_PROBE").setAttribute("blueprint", bpName)
        val spec = xyz.znix.xftl.shipgen.EnemyShipSpec(elem, state.blueprintManager, state.eventManager)
        state.debugSpawnShip(spec, xyz.znix.xftl.game.Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val enemy = state.enemy!!
        hullArg?.let { enemy.health = it.toInt() }
        repeat(4) { state.updateGameState(DT) }
        // Whatever event chain the start beacon opened (MV queues several): drop it so the HUD and ships show.
        state.shipUI.diagnosticCloseWindow()
        if (!state.isPaused) state.toggleQuickPause()
        return "\"fight\":{\"enemy\":${BrowserDebug.quote(enemy.type.name)},\"hull\":${enemy.health},\"max\":${enemy.maxHealth}," +
            "\"player\":${state.player.health},\"playerMax\":${state.player.maxHealth}}"
    }

    /**
     * `flagship[:stage]`: like the oracle's console SECTOR FINAL plus a jump onto the flagship's beacon. From a run in
     * progress, jump to the last stand sector (the sector map's FINAL node), drop its arrival chain, put the flagship
     * at the player's beacon at [arg]'s stage (default 1) and spawn it there; its BOSS_TEXT box stays open.
     */
    private fun flagship(main: MainGame, container: xyz.znix.xftl.sys.GameContainer, arg: String): String {
        val st = main.currentState as xyz.znix.xftl.game.InGameState
        val info = st.gameMap.sectors.flatten().first { it.type.name == "FINAL" }
        st.shipUI.diagnosticCloseWindow()
        if (st.isPaused) st.toggleQuickPause()
        var frames = 0
        if (st.currentBeacon.sector.info !== info) {
            st.shipUI.sectorChosen(info)
            while (frames < 600 && st.currentBeacon.sector.info !== info) { main.update(container, DT); frames++ }
            repeat(20) { main.update(container, DT) }
        }
        st.shipUI.diagnosticCloseWindow()
        val boss = st.currentBeacon.sector.bosses.filterIsInstance<xyz.znix.xftl.game.FlagshipBoss>().first()
        // `flagship:<stage>:<frames>`: also reports the enemy's power state at spawn and after that many more frames.
        val parts = arg.split(':')
        parts[0].toIntOrNull()?.let { boss.stage = it }
        boss.debugTeleportTo(st.currentBeacon)
        val spawned = st.browserSpawnBossHere()
        fun power() = enemyPower(st)
        val atSpawn = power()
        repeat(4) { main.update(container, DT) }
        val early = power()
        val extra = parts.getOrNull(1)?.toIntOrNull() ?: 0
        repeat(extra) { main.update(container, DT) }
        val late = if (extra > 0) power() else "null"
        val enemy = st.enemy
        return "\"flagship\":{\"frames\":$frames,\"spawned\":$spawned,\"enemy\":${BrowserDebug.quote(enemy?.type?.name ?: "")}," +
            "\"stage\":${boss.stage},\"hull\":${enemy?.health ?: 0},\"systems\":${enemy?.systems?.size ?: 0}," +
            "\"powerAtSpawn\":$atSpawn,\"powerEarly\":$early,\"powerLate\":$late}"
    }

    /** The enemy's reactor and main systems: name, power, levels, undamaged levels, locked. */
    private fun enemyPower(st: xyz.znix.xftl.game.InGameState): String {
        val e = st.enemy ?: return "null"
        return "{\"crew\":${e.crew.count { c -> c is xyz.znix.xftl.crew.LivingCrew && c.ownerShip === e }},\"auto\":${e.isAutomated},\"avail\":${e.powerAvailable},\"systems\":[" + e.mainSystems.joinToString(",") {
            "[${BrowserDebug.quote(it.javaClass.simpleName)},${it.powerSelected},${it.energyLevels},${it.undamagedEnergy},${it.isPowerLocked}," +
                "${it.damagedEnergyLevels},${it.room?.fires?.count { f -> f != null }},${it.room?.breaches?.count { f -> f != null }}," +
                "${it.ionPowerLimit},${it.scriptedPowerLoss},${it.scriptedPowerLimit},${it.hsExt?.appliedLoss},${it.hsExt?.additionalPowerLoss}]"
        } + "]}"
    }

    private fun state(): String {
        if (!HS.active) return "\"active\":false"
        val t = HsTitleScreen.config()
        return "\"active\":true,\"title\":{\"custom\":${t.custom},\"logo\":[${t.logoX},${t.logoY}],\"splashes\":${t.splashes.size}," +
            "\"current\":${HsTitleScreen.current?.let { BrowserDebug.quote(it.image) } ?: "null"}},\"altMode\":${HsOverlay.altMode}"
    }
}
