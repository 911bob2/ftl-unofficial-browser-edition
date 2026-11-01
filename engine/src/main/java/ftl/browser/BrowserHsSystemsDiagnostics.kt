package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.systems.HsPower
import xyz.znix.xftl.hs.systems.HsArtillery
import xyz.znix.xftl.hs.systems.HsSystemBox
import xyz.znix.xftl.hs.systems.HsSystemInstall
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.SelectedTarget
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import xyz.znix.xftl.hs.systems.HsSystems
import xyz.znix.xftl.hs.systems.HsTemporal
import xyz.znix.xftl.hs.systems.Temporal
import xyz.znix.xftl.sector.EventStatus
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem
import kotlin.math.abs

/**
 * HS systems checks (`BrowserEngine.hsSystemsProbe(part)`) for a Hyperspace (Multiverse) session. Test-only.
 * `units`: the customSystems tables as MV sets them; the temporal manipulator on PLAYER_SHIP_CEL (install, power,
 * speed own room, save/load while running, duration and cooldown, slow an enemy room); the clonebay jump heal; the
 * PowerManager status (reactor limit/divide/clear statuses, their beacon save, Lua iTempPowerCap); and with Lua the
 * ShipSystem members, additionalPowerLoss from SHIP_LOOP, SET_BONUS_POWER, SetHackingLevel and iTempPowerCap.
 * Leaves the engine at the main menu.
 */
object BrowserHsSystemsDiagnostics {
    private const val DT = .05f

    fun run(main: MainGame, container: GameContainer, part: String): String {
        check(HS.active) { "hs-systems needs a Hyperspace session" }
        if (part.startsWith("shot:")) return shot(main, container, part.substring(5).split(':'))
        if (part == "menu") { main.switchToMainMenu(); return "PASS menu" }
        if (part == "tai") { val r = BrowserRandom.snapshot(); try { return temporalAi(main, container) } finally { BrowserRandom.restore(r); main.switchToMainMenu() } }
        val rng = BrowserRandom.snapshot()
        try {
            if (part.startsWith("ships:")) return ships(main, container, part.substring(6).split(',').filter { it.isNotEmpty() })
            return when (part) {
                "units" -> {
                    val out = ArrayList<String>()
                    try { units(main, container, out) } catch (e: Throwable) { throw IllegalStateException("after [" + out.joinToString("; ") + "]: " + e, e) }
                }
                "round3" -> {
                    val out = ArrayList<String>()
                    try { round3(main, container, out) } catch (e: Throwable) { throw IllegalStateException("after [" + out.joinToString("; ") + "]: " + e, e) }
                }
                "round2" -> {
                    val out = ArrayList<String>()
                    try { round2(main, container, out) } catch (e: Throwable) { throw IllegalStateException("after [" + out.joinToString("; ") + "]: " + e, e) }
                }
                else -> error("unknown part $part")
            }
        } finally {
            BrowserRandom.restore(rng)
            main.switchToMainMenu()
        }
    }

    /**
     * `ships:A,B,...`: each ship's systems right after the start of a new run (as the oracle's per-ship start dumps
     * record them), one JSON line per ship: id, name, level, maxLevel, power (reactor), battery, bonus, health, room.
     */
    private fun ships(main: MainGame, container: GameContainer, names: List<String>): String {
        val sb = StringBuilder()
        for (name in names) {
            try {
                main.startDiagnosticGame(name, Difficulty.EASY)
                repeat(2) { main.update(container, DT); dismiss(main) }
                val player = (main.currentState as InGameState).player
                sb.append("{\"ship\":\"").append(name).append("\",\"reactor\":").append(player.purchasedReactorPower).append(",\"systems\":[")
                player.systems.forEachIndexed { i, s ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"id\":").append(nativeSystemId(s)).append(",\"name\":\"").append(s.codename)
                        .append("\",\"level\":").append(s.energyLevels).append(",\"maxLevel\":").append(s.maxLevel)
                        .append(",\"power\":").append(xyz.znix.xftl.hs.systems.HsSystemsLua.reactorPower(s))
                        .append(",\"battery\":").append((s as? MainSystem)?.sharedPowerInUse ?: 0)
                        .append(",\"bonus\":").append((s as? MainSystem)?.forcedPower ?: 0)
                        .append(",\"health\":").append(s.energyLevels - s.damagedEnergyLevels)
                        .append(",\"room\":").append(s.room?.id ?: -1).append('}')
                }
                sb.append("]}").append('\n')
            } catch (e: Throwable) {
                sb.append("{\"ship\":\"").append(name).append("\",\"error\":").append(BrowserDebug.quote(e.toString())).append("}").append('\n')
            }
        }
        return sb.toString()
    }

    private fun dismiss(main: MainGame) {
        if ((main.currentState as InGameState).shipUI.isWindowOpen) {
            BrowserEngine.key(49, '1'.code, true); BrowserEngine.step(0f)
            BrowserEngine.key(49, '1'.code, false); BrowserEngine.step(0f)
        }
    }

    private fun fight(main: MainGame, container: GameContainer, ship: String): InGameState {
        main.startDiagnosticGame(ship, Difficulty.EASY)
        repeat(60) { main.update(container, DT); dismiss(main) }
        var state = main.currentState as InGameState
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state = main.currentState as InGameState
        state.debugFlags.noEnemyFire.set = true
        state.player.purchasedReactorPower = 25
        return state
    }

    /**
     * `shot:SHIP:AUG:TEMPORAL`: a new run on SHIP (as the oracle session systems-render starts it), the start windows
     * closed, augment AUG added ("-" for none), the temporal system powered by TEMPORAL; the run stays open for the
     * test's screenshot (then `menu`). Mouse moved off the bar.
     */
    private fun shot(main: MainGame, container: GameContainer, args: List<String>): String {
        main.startDiagnosticGame(args[0], Difficulty.EASY)
        repeat(60) { main.update(container, DT); dismiss(main) }
        val state = main.currentState as InGameState
        closeWindows(main, container, state)
        val player = state.player
        args.getOrNull(1)?.takeIf { it != "-" }?.let { player.addBlueprint(state.blueprintManager[it] as xyz.znix.xftl.Blueprint, true) }
        val t = player.systems.firstOrNull { it is Temporal } as Temporal?
        repeat(args.getOrNull(2)?.toIntOrNull() ?: 0) { t?.increasePower() }
        args.getOrNull(3)?.let { ev ->
            state.debugSpawnShip(state.eventManager.getShip(ev), Difficulty.NORMAL, 0, 71)
            state.debugFlags.noEnemyFire.set = true
        }
        val st = main.currentState as InGameState
        repeat(20) { main.update(container, DT) }
        closeWindows(main, container, st)
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        state.shipUI.updateButtons()
        return "PASS shot ${args[0]} window ${state.shipUI.diagnosticWindow}"
    }

    /**
     * `tai`: the oracle session systems-temporal-ai in the engine: MV Kestrel at the start beacon, SHIP_CONTINUITY_PROBE_EARLY
     * (temporal 2) spawned hostile, 100 s of fight; one line per change of the rooms' dilations and the temporal
     * system's power/lock (the oracle's temporal_ai lines).
     */
    private fun temporalAi(main: MainGame, container: GameContainer): String {
        main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
        repeat(60) { main.update(container, DT); dismiss(main) }
        var state = main.currentState as InGameState
        closeWindows(main, container, state)
        state.debugSpawnShip(state.eventManager.getShip("SHIP_CONTINUITY_PROBE_EARLY"), Difficulty.NORMAL, 0, 71)
        state = main.currentState as InGameState
        closeWindows(main, container, state)
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        fun dil(ship: Ship?) = ship?.rooms?.filter { HsTemporal.dilation(it) != 0 }?.joinToString(" ") { "${it.id}:${HsTemporal.dilation(it)}" } ?: ""
        val sb = StringBuilder("PASS tai").append('\n')
        var prev = ""
        var t = 0f
        while (t < 100f) {
            state.updateGameState(DT); t += DT
            val e = state.enemy
            val ts = e?.systems?.firstOrNull { it is Temporal } as Temporal?
            val sys = if (ts == null) "-" else "${ts.powerSupplied}/${ts.energyLevels - ts.damagedEnergyLevels} lock ${xyz.znix.xftl.hs.systems.HsSystemsLua.lockCount(ts)}"
            val key = "player '${dil(state.player)}' enemy '${dil(e)}' sys $sys"
            if (key != prev) { sb.append(Math.round(t * 100) / 100f).append(' ').append(key).append('\n'); prev = key }
        }
        sb.append("player room 0: ").append(state.player.rooms[0].system?.codename ?: "none")
        return sb.toString()
    }

    /** Answers dialogues with their last choice (MV: leave) and closes the other windows (the pause menu stays shut). */
    private fun closeWindows(main: MainGame, container: GameContainer, state: InGameState) {
        repeat(40) {
            if (!state.shipUI.isWindowOpen) return
            val d = state.shipUI.diagnosticDialogue
            if (d != null) d.selectOption(maxOf(0, d.optionCount - 1))
            else state.shipUI.escapePressed()
            main.update(container, DT)
        }
    }

    private fun step(state: InGameState, n: Int) { repeat(n) { state.updateGameState(DT) } }

    private fun lua(name: String, code: String) {
        val err = HsLua.runString(name, code)
        check(err == null) { "lua $name: $err" }
    }

    /** The reactor power the ship can use this frame (used by the systems plus still free). */
    private fun reactorTotal(ship: Ship): Int =
        (ship.powerAvailableTypes[ReactorEnergySource] ?: 0) + ship.systems.sumOf { (it as? MainSystem)?.reactorPowerDemand ?: 0 }

    private fun near(a: Float, b: Float, eps: Float = 0.06f) = abs(a - b) <= eps

    private fun units(main: MainGame, container: GameContainer, out: ArrayList<String>): String {

        // customSystems as MV 5.5.1 writes them (hyperspace.xml <customSystems>)
        run {
            val speed = (1..4).map { HsSystems.dilationDuration(it) to HsSystems.dilationStrength(it) }
            check(speed == listOf(10 to 2f, 7 to 4f, 5 to 8f, 2 to 16f)) { "temporal speed levels $speed" }
            val slow = (1..4).map { HsSystems.dilationDuration(-it) to HsSystems.dilationStrength(-it) }
            check(slow.map { it.first } == listOf(10, 17, 25, 33)) { "temporal slow durations $slow" }
            check(slow.map { it.second }.zip(listOf(.25f, .12f, .06f, .03f)).all { (a, b) -> near(a, b, 1e-4f) }) { "temporal slow strengths $slow" }
            val clone = (1..4).map { HsSystems.cloneLevel(it).let { l -> listOf(l.jumpHP, l.jumpHPPercent, l.cloneSpeed.toInt(), l.skillLossPercent) } }
            check(clone == listOf(listOf(0, 11, 13, 10), listOf(0, 16, 10, 10), listOf(0, 25, 8, 10), listOf(0, 100, 0, 10))) { "clone levels $clone" }
            val mind = (1..4).map { HsSystems.mindLevel(it).let { l -> listOf(l.duration, l.healthBoost, l.damageBoost, l.lock.toFloat(), l.count.toFloat()) } }
            check(mind == listOf(listOf(14f, 0f, 1f, 4f, 1f), listOf(20f, 15f, 1.25f, 4f, 1f), listOf(28f, 30f, 2f, 4f, 1f), listOf(28f, 30f, 2f, 4f, 2f))) { "mind levels $mind" }
            check(HsSystems.areExclusive(HsSystems.nameToId("medbay"), HsSystems.nameToId("clonebay")) && !HsSystems.areExclusive(HsSystems.nameToId("shields"), HsSystems.nameToId("engines"))) { "exclusivity" }
            check(HsSystems.nameToId("temporal") == 20 && HsSystems.idToName(20) == "temporal" && HsSystems.nameToId("reactor") == 17) { "system ids" }
            out += "customSystems tables"
        }

        out += "start"
        var state = fight(main, container, "PLAYER_SHIP_CEL")
        out += "fight"
        var player = state.player
        var t = player.systems.firstOrNull { it is Temporal } as Temporal? ?: error("PLAYER_SHIP_CEL has no temporal system")
        check(nativeSystemId(t) == 20 && t.energyLevels == 2) { "temporal id ${nativeSystemId(t)} level ${t.energyLevels}" }
        repeat(2) { t.increasePower() }
        step(state, 2)
        check(t.powerSupplied == 2 && t.isReady) { "temporal power ${t.powerSupplied} ready ${t.isReady}" }

        // speed up the player's weapons room: level 2 -> x4 for 7 s, then a 2-unit lock
        val own = player.weapons!!.room!!
        t.selectRoom(own, true)
        step(state, 1)
        check(t.turnedOn && t.currentRoom === own && HsTemporal.dilation(own) == 2) { "speed start: on ${t.turnedOn} room ${t.currentRoom?.id} dilation ${HsTemporal.dilation(own)}" }
        check(HsTemporal.factor(own) == 4f && t.timerGoal == 7f) { "speed factor ${HsTemporal.factor(own)} goal ${t.timerGoal}" }
        check(t.hasPermanentLock) { "running temporal not locked" }
        if (HS.luaActive) {
            lua("hs-systems-temporal", """
local s = Hyperspace.ships(0):GetSystem(20)
assert(s ~= nil, 'GetSystem(20)')
assert(s:GetId() == 20 and s.iSystemType == 20, 'GetId')
assert(s.powerState.second == 2 and s.healthState.first == 2 and s.healthState.second == 2, 'powerState/healthState')
assert(s:GetEffectivePower() == 2 and s.powerState.first == 2, 'effective power ' .. s:GetEffectivePower())
assert(s.iLockCount == -1 and s:GetLocked(), 'iLockCount ' .. s.iLockCount)
assert(Hyperspace.ShipSystem.NameToSystemId('temporal') == 20, 'NameToSystemId')
assert(Hyperspace.ShipSystem.SystemIdToName(20) == 'temporal', 'SystemIdToName')
assert(Hyperspace.ships(0).ship.vRoomList[${own.id}].extend.timeDilation == 2, 'room timeDilation')
""")
            out += "Lua ShipSystem members on the temporal system"
        }
        step(state, 20)
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        state = main.currentState as InGameState
        player = state.player
        t = player.systems.firstOrNull { it is Temporal } as Temporal? ?: error("no temporal system after load: ${player.systems.map { it.codename }}")
        val ownLoaded = player.rooms[own.id]
        check(t.turnedOn && t.currentRoom === ownLoaded && HsTemporal.dilation(ownLoaded) == 2 && near(t.timerTime, 21 * DT)) {
            "after load: on ${t.turnedOn} room ${t.currentRoom?.id} dilation ${HsTemporal.dilation(ownLoaded)} timer ${t.timerTime}"
        }
        var frames = 0
        while (t.turnedOn && frames < 400) { step(state, 1); frames++ }
        val ran = (21 + frames) * DT
        check(!t.turnedOn && near(ran, 7f, 0.11f)) { "speed ran $ran s (on ${t.turnedOn})" }
        check(HsTemporal.dilation(ownLoaded) == 0 && near(t.ionTimer, 10f, 0.11f)) { "after speed: dilation ${HsTemporal.dilation(ownLoaded)} lock ${t.ionTimer}" }
        out += "temporal speed own room x4 for ${"%.2f".format(ran)} s, survives save/load, 2-unit cooldown"

        // slow an enemy room: level 2 -> x0.12 for 17 s
        t.lockUnits(0)
        step(state, 2)
        val enemyRoom = state.enemy!!.rooms.first()
        check(t.isReady) { "temporal not ready after unlock" }
        t.selectRoom(enemyRoom, false)
        step(state, 1)
        check(t.turnedOn && HsTemporal.dilation(enemyRoom) == -2 && near(HsTemporal.factor(enemyRoom), .12f, 1e-4f) && t.timerGoal == 17f) {
            "slow: on ${t.turnedOn} dilation ${HsTemporal.dilation(enemyRoom)} factor ${HsTemporal.factor(enemyRoom)} goal ${t.timerGoal}"
        }
        t.stopTimeDilation()
        check(HsTemporal.dilation(enemyRoom) == 0) { "stop left dilation ${HsTemporal.dilation(enemyRoom)}" }
        out += "temporal slow enemy room x0.12 goal 17 s"

        // PowerManager status: reactor statuses and Lua iTempPowerCap reach the reactor
        run {
            step(state, 2)
            check(reactorTotal(player) == 25) { "reactor before ${reactorTotal(player)}" }
            HsPower.status(player, true, EventStatus.Operation.LIMIT, 3); step(state, 2)
            check(reactorTotal(player) == 3) { "reactor limit 3 -> ${reactorTotal(player)}" }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            state = main.currentState as InGameState; player = state.player
            step(state, 2)
            check(reactorTotal(player) == 3) { "reactor limit after load -> ${reactorTotal(player)}" }
            HsPower.status(player, true, EventStatus.Operation.CLEAR, 100); step(state, 2)
            check(reactorTotal(player) == 25) { "reactor clear -> ${reactorTotal(player)}" }
            HsPower.status(player, true, EventStatus.Operation.DIVIDE, 2); step(state, 2)
            check(reactorTotal(player) == 13 && HsPower.of(player).cap == 13) { "reactor divide 2 -> ${reactorTotal(player)} cap ${HsPower.of(player).cap}" }
            HsPower.status(player, true, EventStatus.Operation.CLEAR, 100)
            HsPower.status(player, true, EventStatus.Operation.LOSS, 4); step(state, 2)
            check(reactorTotal(player) == 21) { "reactor loss 4 -> ${reactorTotal(player)}" }
            HsPower.status(player, true, EventStatus.Operation.CLEAR, 100); step(state, 2)
            if (HS.luaActive) {
                lua("hs-systems-power", "local p = Hyperspace.PowerManager.GetPowerManager(0); assert(p.iTempPowerCap == 1000 and p.iTempDividePower == 1, 'defaults'); p.iTempPowerCap = 4")
                step(state, 2)
                check(reactorTotal(player) == 4) { "Lua iTempPowerCap 4 -> ${reactorTotal(player)}" }
                lua("hs-systems-power2", "local p = Hyperspace.PowerManager.GetPowerManager(0); assert(p.iTempPowerCap == 4); assert(p.currentPower.second == 25); p.iTempPowerCap = 1000")
                step(state, 2)
                check(reactorTotal(player) == 25) { "Lua iTempPowerCap 1000 -> ${reactorTotal(player)}" }
            }
            out += "reactor limit/divide/loss/clear statuses (limit saved), Lua iTempPowerCap"
        }

        if (HS.luaActive) {
            val engines = player.engines!!
            repeat(8) { engines.increasePower() }
            step(state, 2)
            val levels = engines.undamagedEnergy
            lua("hs-systems-loop", """
script.on_internal_event(Defines.InternalEvents.SHIP_LOOP, function(s)
  if HSSYS_LOSS and s.iShipId == 0 then local e = s:GetSystem(1); e.extend.additionalPowerLoss = e.extend.additionalPowerLoss + HSSYS_LOSS end
end)
script.on_internal_event(Defines.InternalEvents.SET_BONUS_POWER, function(sys, amount)
  if HSSYS_BONUS and sys:GetId() == 20 then return Defines.Chain.CONTINUE, amount + HSSYS_BONUS end
end)
HSSYS_LOSS = 1
""")
            step(state, 3)
            check(engines.undamagedEnergy == levels - 1 && engines.powerSupplied <= levels - 1) { "additionalPowerLoss: levels ${engines.undamagedEnergy} (was $levels) power ${engines.powerSupplied}" }
            lua("hs-systems-loop2", "HSSYS_LOSS = nil")
            step(state, 3)
            check(engines.undamagedEnergy == levels) { "additionalPowerLoss gone: ${engines.undamagedEnergy} want $levels" }
            out += "additionalPowerLoss from SHIP_LOOP takes $levels -> ${levels - 1} and back"

            val t2 = player.systems.firstOrNull { it is Temporal } as Temporal? ?: error("no temporal system (bonus)")
            t2.lockUnits(0)
            repeat(2) { t2.decreasePower() }
            step(state, 2)
            lua("hs-systems-bonus", "HSSYS_BONUS = 1")
            step(state, 2)
            check(t2.forcedPower == 1 && t2.powerSupplied == 1) { "SET_BONUS_POWER: bonus ${t2.forcedPower} power ${t2.powerSupplied}" }
            lua("hs-systems-bonus2", "HSSYS_BONUS = nil")
            step(state, 2)
            check(t2.forcedPower == 0) { "SET_BONUS_POWER gone: bonus ${t2.forcedPower}" }
            out += "SET_BONUS_POWER +1 on the temporal system"

            lua("hs-systems-hack", "local e = Hyperspace.ships(0):GetSystem(1); e:SetHackingLevel(2); assert(e.iHackEffect == 2); e.bUnderAttack = true; assert(e.bUnderAttack)")
            step(state, 1)
            check(engines.isHackActive) { "SetHackingLevel(2) did not start a pulse" }
            lua("hs-systems-hack2", "local e = Hyperspace.ships(0):GetSystem(1); e:SetHackingLevel(0); e.bUnderAttack = false; e.iTempPowerCap = 1")
            step(state, 2)
            check(!engines.isHackActive && engines.scriptedPowerLimit == 1 && engines.powerSupplied <= 1) { "hack end ${engines.isHackActive}, cap ${engines.scriptedPowerLimit} power ${engines.powerSupplied}" }
            lua("hs-systems-hack3", "local e = Hyperspace.ships(0):GetSystem(1); assert(e.iTempPowerCap == 1); e.iTempPowerCap = 1000; assert(e.iTempPowerCap == 1000)")
            out += "SetHackingLevel pulse, bUnderAttack, ShipSystem.iTempPowerCap"
        } else out += "SKIP Lua checks (no Lua runtime)"

        // augment super shields (CustomSuperShield.cpp): SYLVAN_SHIELD_REWARD value 12, UPG_AETHER_SHIELDS add 3
        run {
            out += "CEL super shield value ${xyz.znix.xftl.hs.systems.HsSuperShield.value(player)} (${player.superShield}/${player.maxSuperShield})"
            state = fight(main, container, "PLAYER_SHIP_KESTREL_3"); player = state.player
            val bm = state.blueprintManager
            player.augments.clear(); player.cargoUpdated()   // room for the two test augments
            check(xyz.znix.xftl.hs.systems.HsSuperShield.value(player) == 0) { "super shield value before: ${xyz.znix.xftl.hs.systems.HsSuperShield.value(player)}" }
            player.superShield = 0; player.maxSuperShield = 5
            player.addBlueprint(bm["SYLVAN_SHIELD_REWARD"] as xyz.znix.xftl.Blueprint, true)
            check(xyz.znix.xftl.hs.systems.HsSuperShield.value(player) == 12) { "SYLVAN_SHIELD_REWARD value ${xyz.znix.xftl.hs.systems.HsSuperShield.value(player)}" }
            step(state, 2)
            check(player.maxSuperShield == 12 && player.superShield == 12) { "super shield after OnLoop ${player.superShield}/${player.maxSuperShield}" }
            player.addBlueprint(bm["UPG_AETHER_SHIELDS"] as xyz.znix.xftl.Blueprint, true)
            step(state, 2)
            check(xyz.znix.xftl.hs.systems.HsSuperShield.value(player) == 15 && player.maxSuperShield == 15) { "with UPG_AETHER_SHIELDS ${xyz.znix.xftl.hs.systems.HsSuperShield.value(player)} max ${player.maxSuperShield}" }
            player.superShield = 3
            check(xyz.znix.xftl.hs.systems.HsSuperShield.addSuperShield(player) && player.superShield == 4) { "AddSuperShield ${player.superShield}" }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            state = main.currentState as InGameState; player = state.player
            step(state, 2)
            check(player.maxSuperShield == 15 && player.superShield == 4) { "super shield after load ${player.superShield}/${player.maxSuperShield}" }
            (player.shields ?: error("no shields system")).instantCharge()
            check(player.superShield == 15) { "InstantCharge ${player.superShield}" }
            out += "augment super shield 12 (+3 add = 15), AddSuperShield, InstantCharge, saved"
        }

        // clonebay jump heal at power 1: +11% of the maximum health
        run {
            state = fight(main, container, "PLAYER_SHIP_KESTREL_3"); player = state.player
            val cb = player.clonebay ?: error("PLAYER_SHIP_KESTREL_3 has no clonebay")
            val crew = player.crew.filterIsInstance<LivingCrew>().firstOrNull { it.ownerShip === player } ?: error("no own crew: ${player.crew.filterIsInstance<LivingCrew>().map { it.codename + "/" + (it.ownerShip === player) }}")
            crew.health = crew.maxHealth / 2
            val before = crew.health
            cb.onJump()
            val pct = HsSystems.cloneLevel(cb.energyLevels - cb.damagedEnergyLevels).jumpHPPercent
            val want = minOf(crew.maxHealth, before + crew.maxHealth * pct / 100f)
            check(near(crew.health, want, 0.01f)) { "jump heal ${crew.health} want $want (max ${crew.maxHealth}, clonebay health ${cb.energyLevels - cb.damagedEnergyLevels})" }
            out += "clonebay jump heal +$pct% (${before} -> ${crew.health})"
        }

        return "PASS hs-systems units: " + out.joinToString("; ")
    }

    /**
     * `round2`: store system replacement (a medbay bought over the clonebay takes its level, the per-ship limit),
     * temporal sold in stores, door lockdown shards under dilation (and saved), the targetable artillery (beam on
     * PLAYER_SHIP_FED, flak on PLAYER_SHIP_FED_3) and, with Lua, the SystemBox events.
     */
    private fun round2(main: MainGame, container: GameContainer, out: ArrayList<String>): String {
        // store replacement: a medbay replaces the clonebay and takes its level (dualMedical off in MV)
        run {
            var state = fight(main, container, "PLAYER_SHIP_KESTREL_3"); var player = state.player
            val cb = player.clonebay ?: error("PLAYER_SHIP_KESTREL_3 has no clonebay")
            val slot = player.systemSlots.firstOrNull { it.system.name == "medbay" && !it.isInstalled }
                ?: error("no medbay slot: ${player.systemSlots.map { it.system.name + "/" + it.isInstalled }}")
            check(!HS.cfg().dualMedical) { "dualMedical on" }
            cb.energyLevels = 2; cb.powerLimitChanged()
            check(HsSystemInstall.willReplace(player, "medbay") === cb) { "medbay does not replace the clonebay" }
            check(HsSystemInstall.canFit(player, slot)) { "replacing medbay does not fit" }
            val med = HsSystemInstall.install(player, slot) ?: error("install returned null")
            check(player.clonebay == null && player.medbay === med && med.energyLevels == 2) { "after install: clonebay ${player.clonebay} medbay ${player.medbay?.energyLevels}" }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            state = main.currentState as InGameState; player = state.player
            check(player.clonebay == null && player.medbay?.energyLevels == 2) { "after load: clonebay ${player.clonebay} medbay ${player.medbay?.energyLevels}" }
            out += "medbay over clonebay keeps level 2 (saved)"
            // per-ship limit: a ship at its systemLimit cannot fit a new, non-replacing main system
            val def = xyz.znix.xftl.hs.ship.HsShips.def(player.type.name)
            val free = player.systemSlots.firstOrNull { !it.isInstalled && it.system.info?.isSubSystem != true &&
                HsSystemInstall.willReplace(player, it.system.name) == null }
            if (free != null) {
                val fits = HsSystemInstall.canFit(player, free)
                check(fits == (player.mainSystems.size < def.systemLimit)) { "canFit ${free.system.name} = $fits with ${player.mainSystems.size}/${def.systemLimit}" }
                out += "limit ${player.mainSystems.size}/${def.systemLimit}: ${free.system.name} fits $fits"
            }
        }

        // temporal in stores
        val hook = xyz.znix.xftl.hs.ship.HsStores.customSystemSupported ?: error("HsStores.customSystemSupported not set")
        check(hook("temporal") && !hook("nosuchsystem")) { "customSystemSupported" }
        out += "stores sell temporal"

        // door lockdown under dilation: a shard ticks at its locking room's factor
        run {
            var state = fight(main, container, "PLAYER_SHIP_KESTREL_3"); var player = state.player
            val door = player.doors.firstOrNull { it.left != null && it.right != null } ?: error("no inner door")
            val a = door.left!!
            HsTemporal.roomState(a).timeDilation = 2
            check(HsTemporal.factor(a) == 4f && HsTemporal.factor(door.right) == 1f) { "factors ${HsTemporal.factor(a)} ${HsTemporal.factor(door.right)}" }
            door.lockdown(a)
            step(state, 20) // 1 s at x4
            check(near(door.lockdownTimer, 8f, 0.11f)) { "dilated shard ${door.lockdownTimer}" }
            door.lockdown(door.right)
            step(state, 20)
            check(near(door.lockdownTimer, 11f, 0.11f)) { "two shards: ${door.lockdownTimer}" }
            main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
            state = main.currentState as InGameState; player = state.player
            val d2 = player.doors.first { it.position == door.position }
            check(near(d2.lockdownTimer, 11f, 0.11f)) { "after load ${d2.lockdownTimer}" }
            step(state, 20 * 11 + 4)
            check(d2.lockdownTimer <= 0f) { "lockdown not over: ${d2.lockdownTimer}" }
            out += "lockdown shards 12 s at x4 / x1, saved"
        }

        // crew repair in a dilated room (the oracle session systems-dilation measures the same on the MV Kestrel)
        run {
            val state = fight(main, container, "PLAYER_SHIP_MVKESTREL"); val player = state.player
            val w = player.weapons ?: error("no weapons")
            val room = w.room!!
            check(player.crew.any { it.room === room }) { "nobody in the weapons room" }
            fun rate(): Float {
                if (!w.damaged) w.dealDamage(1, 0)
                step(state, 4)
                val a = w.repairProgress
                step(state, 10)
                return (w.repairProgress - a) / (10 * DT)
            }
            val r0 = rate()
            HsTemporal.roomState(room).timeDilation = 2
            val r2 = rate()
            HsTemporal.roomState(room).timeDilation = -2
            val rm = rate()
            HsTemporal.roomState(room).timeDilation = 0
            check(r0 > 0f && near(r2 / r0, 4f, 0.05f) && near(rm / r0, xyz.znix.xftl.hs.systems.HsSystems.dilationStrength(-2), 0.01f)) { "repair rates $r0 $r2 $rm" }
            fun r(v: Float) = Math.round(v * 10000f) / 10000f
            out += "crew repair ${r(r0)}/s (x${r(r2 / r0)} at +2, x${r(rm / r0)} at -2)"
        }

        // targetable artillery: beam (FED) and flak (FED_3)
        for (ship in listOf("PLAYER_SHIP_FED", "PLAYER_SHIP_FED_3")) {
            val state = fight(main, container, ship); val player = state.player
            val art = player.systems.filterIsInstance<Artillery>().firstOrNull() ?: error("$ship has no artillery")
            val w = art.browserWeapon
            repeat(art.energyLevels) { art.increasePower() }
            check(!HsArtillery.manual(player)) { "$ship manual without the augment" }
            player.augments.clear(); player.cargoUpdated()
            player.addBlueprint(state.blueprintManager[HsArtillery.AUGMENT] as xyz.znix.xftl.Blueprint, true)
            check(HsArtillery.manual(player)) { "$ship not manual with ARTILLERY_ORDER" }
            check(state.isEnemyHostile) { "enemy not hostile" }
            var n = 0
            while (!w.isCurrentCharged && n < 2000) { step(state, 1); n++ }
            check(w.isCurrentCharged) { "$ship artillery never charged (${w.timeCharged}/${w.chargeTime}, power ${art.powerSupplied})" }
            step(state, 40)
            check(w.isCurrentCharged) { "$ship artillery fired without an aim" }
            val enemy = state.enemy ?: error("no enemy")
            val room = enemy.rooms.first()
            fun aimAt(): SelectedTarget = when (w) {
                is BeamBlueprint.BeamInstance -> xyz.znix.xftl.hs.weapons.HsWeapons.pinpointAim(w, room, -1, null)
                is IRoomTargetingWeapon -> SelectedTarget.RoomAim(room, w, -1)
                else -> error("weapon $w")
            }
            closeWindows(main, container, state)
            val windowOpen = state.shipUI.diagnosticWindow
            if (!state.shipUI.isWindowOpen) {
                state.shipUI.hsArmArtillery(art)
                check(state.shipUI.hsArmedArtillery() === art) { "arming (click ${state.clickEvent})" }
                out += "armed through the button path"
            } else out += "arming not checked (window $windowOpen open)"
            state.clickEvent = null
            player.weapons?.setAutofire(false)
            HsArtillery.setAim(art, aimAt())
            step(state, 2)
            check(!w.isCurrentCharged && HsArtillery.aim(art) == null) { "$ship aimed shot: charged ${w.isCurrentCharged} aim ${HsArtillery.aim(art)}" }
            player.weapons?.setAutofire(true)
            HsArtillery.setAim(art, aimAt())
            n = 0
            while (!w.isCurrentCharged && n < 2000) { enemy.health = enemy.maxHealth; step(state, 1); n++ }
            enemy.health = enemy.maxHealth
            step(state, 2)
            check(!w.isCurrentCharged && HsArtillery.aim(art) != null) { "$ship autofire shot: charged ${w.isCurrentCharged} aim ${HsArtillery.aim(art)} hostile ${state.isEnemyHostile}" }
            art.onJump()
            check(HsArtillery.aim(art) == null) { "aim survived the jump" }
            out += "$ship ${w.type.name}: waits charged, fires at the aim, autofire keeps it, jump clears"
        }

        // SystemBox Lua events
        if (HS.luaActive) {
            val state = fight(main, container, "PLAYER_SHIP_KESTREL_3"); val player = state.player
            lua("hs-systems-box-reg", """
hss_on = true; hss_moves = {}; hss_built = 0
script.on_internal_event(Defines.InternalEvents.CONSTRUCT_SYSTEM_BOX, function(box) if hss_on then hss_built = hss_built + 1 end end)
script.on_internal_event(Defines.InternalEvents.SYSTEM_BOX_MOUSE_MOVE, function(box, x, y)
  if hss_on and box.bPlayerUI then hss_moves[box.pSystem.iSystemType] = {box.location.x, box.location.y, x, y} end
end)
script.on_internal_event(Defines.InternalEvents.SYSTEM_BOX_KEY_DOWN, function(box, key, shift)
  if hss_on and box.pSystem.iSystemType == 1 then hss_key = key; return Defines.Chain.PREEMPT end
end)
""")
            closeWindows(main, container, state)
            check(!state.shipUI.isWindowOpen) { "window ${state.shipUI.diagnosticWindow} stays open (event ${state.shipUI.diagnosticDialogue?.hsOpenEvent}, options ${state.shipUI.diagnosticDialogue?.optionCount})" }
            state.shipUI.updateButtons()
            BrowserEngine.render()
            state.shipUI.updateUI(100, 600, ConstPoint(0, 0))
            val first = player.mainSystems.first()
            val box = HsSystemBox.existing(first) ?: error("no box for ${first.codename}")
            check(box.location.x == 58 && box.location.y == 269) { "first box location ${box.location.x},${box.location.y}" }
            lua("hs-systems-box-check", """
assert(hss_built > 0, 'CONSTRUCT_SYSTEM_BOX not fired')
local m = hss_moves[${nativeSystemId(first)}]
assert(m ~= nil, 'no SYSTEM_BOX_MOUSE_MOVE for the first box')
assert(m[1] == 58 and m[2] == 269, 'location ' .. m[1] .. ',' .. m[2])
assert(m[3] == 100 - 58 and m[4] == 600 - 651, 'relative ' .. m[3] .. ',' .. m[4])
""")
            val engines = player.engines ?: error("no engines")
            val before = engines.powerSelected
            if (before < engines.undamagedEnergy) {
                val hk = state.hotkeyManager.byID["sys_power_engines"] ?: error("no engines hotkey")
                state.shipUI.hotkeyPressed(hk, container.input)
                check(engines.powerSelected == before) { "pre-empted engines key changed power $before -> ${engines.powerSelected}" }
                lua("hs-systems-box-key", "assert(hss_key == string.byte('s'), 'key ' .. tostring(hss_key))")
                out += "SYSTEM_BOX_KEY_DOWN pre-empts the engines hotkey"
            }
            lua("hs-systems-box-off", "hss_on = false")
            out += "CONSTRUCT_SYSTEM_BOX, SYSTEM_BOX_MOUSE_MOVE location (58,269), relative mouse"
        } else out += "SKIP SystemBox (no Lua runtime)"

        return "PASS hs-systems round2: " + out.joinToString("; ")
    }

    /**
     * `round3`: the HS oxygen environment (a breach drains 8 %/s; CALCULATE_LEAK_MODIFIER -1 turns it into a source;
     * a drained room ends the frame below 0 like FTL's) and, with Lua, Shields:CollisionReal.
     */
    private fun round3(main: MainGame, container: GameContainer, out: ArrayList<String>): String {
        val state = fight(main, container, "PLAYER_SHIP_MVKESTREL"); val player = state.player
        val room = player.rooms.firstOrNull { r -> r.crew.isEmpty() && r.doors.none { it.open } } ?: error("no empty closed room")
        room.oxygen = 0.5f
        room.spawnBreachAt(0)
        step(state, 1)
        val mod0 = xyz.znix.xftl.hs.systems.HsOxygen.leakModifier(player)
        val refill = player.oxygen?.refillRate?.minus(xyz.znix.xftl.systems.Oxygen.ROOM_DRAIN_RATE) ?: 0f
        fun rate(): Float { room.oxygen = 0.5f; val o0 = room.oxygen; step(state, 20); return room.oxygen - o0 }
        val r0 = rate()
        check(near(r0, -0.08f * mod0 + refill, 0.004f)) { "breach change ${r0}/s with leak modifier $mod0 (refill $refill)" }
        out += "breach ${Math.round(r0 * 1000) / 10f} %/s net at leak modifier $mod0 (MV atmosphere beacon: -1)"
        if (HS.luaActive) {
            lua("hs-systems-leak", "hss_leak = true script.on_internal_event(Defines.InternalEvents.CALCULATE_LEAK_MODIFIER, function(ship, mod) if hss_leak then return Defines.Chain.CONTINUE, hss_force or mod * 3 end end)")
            val r3 = rate()
            val mod3 = xyz.znix.xftl.hs.systems.HsOxygen.leakModifier(player)
            check(mod3 == mod0 * 3 && near(r3, -0.08f * mod3 + refill, 0.006f)) { "leak modifier $mod3: ${r3}/s" }
            out += "CALCULATE_LEAK_MODIFIER x3 -> $mod3: ${Math.round(r3 * 1000) / 10f} %/s"
            lua("hs-systems-leak-off", "hss_leak = false")
        }
        // a drained room with a leak: refill clamps, the drain leaves it below 0 every other frame (FTL)
        if (HS.luaActive || mod0 > 0f) {
            if (HS.luaActive) lua("hs-systems-leak-on", "hss_leak = true hss_force = 1")
            room.oxygen = 0f
            var minO = 1f
            repeat(10) { step(state, 1); minO = minOf(minO, room.oxygen) }
            if (HS.luaActive) lua("hs-systems-leak-off2", "hss_leak = false hss_force = nil")
            check(minO < 0f && minO > -0.01f) { "drained room minimum $minO" }
            out += "drained room dips to ${minO * 100f}"
        }
        room.breaches.indices.forEach { room.breaches[it] = null }

        if (HS.luaActive) {
            val shields = player.shields ?: error("no shields")
            shields.instantCharge()
            val before = shields.activeShields
            lua("hs-systems-collision", """
local sm = Hyperspace.ships(0)
local r = sm.shieldSystem:CollisionReal(100, 100, Hyperspace.Damage(), true)
assert(r.collision_type == 2, 'collision type ' .. tostring(r.collision_type))
""")
            check(shields.activeShields == before) { "zero-damage CollisionReal took a layer: $before -> ${shields.activeShields}" }
            out += "Shields:CollisionReal zero damage: type 2, layers kept ($before)"
        }
        return "PASS hs-systems round3: " + out.joinToString("; ")
    }
}
