package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.environment.AsbEnvironment
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ResourceSet
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.BackupBattery
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Round 6, Save and close: what a save followed by a reload keeps, state by state.
 *
 * Each scenario builds a live game, writes the save XML exactly as the browser does (BrowserXml round trip through
 * MainGame.loadSavedGame) and compares a digest of the live objects before and after, plus the XML written by the
 * loaded game (the serialiser must be a fixed point). The digest reads live state, not the XML, so something the
 * serialiser never writes shows up as a difference. The accepted differences are listed per scenario: the native
 * game loses the same things (SpaceManager::SaveSpace 0x4180c0 writes the PDS volley timer and the projectiles but
 * not the shots still queued; WorldManager::SaveGame 0x61a3a0 first completes a pending jump).
 */
internal object GameplayParityShell6Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            // Every scenario runs even after a failure, so one run lists them all.
            val rows = ArrayList<String>()
            val failures = ArrayList<String>()
            fun scenario(name: String, block: () -> String) {
                try {
                    rows += block()
                } catch (e: Throwable) {
                    failures += name + ": " + (e.message ?: e::class.simpleName)
                }
            }
            scenario("idle") { idle(main, container) }
            scenario("dialogue") { dialogue(main, container) }
            scenario("combat") { combat(main, container) }
            scenario("hazards") { hazards(main, container) }
            scenario("drones") { drones(main, container) }
            scenario("hacking") { hacking(main, container) }
            scenario("mind") { mind(main, container) }
            scenario("cloak") { cloak(main, container) }
            scenario("asb") { asb(main, container) }
            scenario("jump") { jump(main, container) }
            check(failures.isEmpty()) { failures.joinToString(" || ") }
            return "shell6 save/reload: " + rows.joinToString("; ")
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    // ---- The digest -------------------------------------------------------------------------------------------

    private fun f(x: Float): String = Math.round(x * 100f).toString()

    private fun shipFacts(out: MutableMap<String, String>, label: String, ship: Ship?) {
        if (ship == null) {
            out["$label"] = "none"
            return
        }
        out["$label.stats"] = "hull=${ship.health} fuel=${ship.fuelCount} scrap=${ship.scrap} missiles=${ship.missilesCount} " +
                "parts=${ship.dronesCount} superShield=${ship.superShield} ftl=${f(ship.ftlChargeProgress)} escape=${ship.escapeTimer?.let { f(it) }}"
        out["$label.projectiles"] = ship.projectiles.map {
            it.javaClass.simpleName + "@" + f(it.position.xf) + "," + f(it.position.yf)
        }.sorted().toString()
        out["$label.crewShots"] = ship.crewShots.size.toString()
        out["$label.externalDrones"] = ship.externalDrones.map { it.javaClass.simpleName }.sorted().toString()
        out["$label.fires"] = ship.rooms.map { r -> r.fires.count { it != null } }.toString()
        out["$label.breaches"] = ship.rooms.map { r -> r.breaches.count { it != null } }.toString()
        out["$label.oxygen"] = ship.rooms.map { f(it.oxygen) }.toString()
        out["$label.crew"] = ship.crew.map {
            val mind = (it as? LivingCrew)?.mindControlledBy != null
            // Crew standing at their post and crew walking to it are the same here: the loaded game sends them back
            // to their stations and the "manned" digest below checks they get there.
            val action = if (it.currentAction == AbstractCrew.Action.MANNING) AbstractCrew.Action.MOVING else it.currentAction
            val walking = it.pathingTarget?.let { t -> ">" + ship.rooms.indexOf(t.room) + "@" + t.x + "," + t.y } ?: ""
            it.javaClass.simpleName + ":" + ship.rooms.indexOf(it.room) + ":" + f(it.health) + ":" + action + ":" + it.mode + (if (mind) ":mind" else "") + (if (ship.isPlayerShip) walking else "")
        }.toString()
        // Enemy crew are planned by the ship AI, which sends them back to posts after a load; only the player's count.
        if (ship.isPlayerShip) out["$label.manned"] = manned(ship)
        out["$label.systems"] = ship.systems.map {
            val power = (it as? MainSystem)?.let { m -> "p${m.powerSelected}/${m.powerSupplied}" } ?: ""
            val extra = when (it) {
                is Shields -> ":bubbles" + it.activeShields + ":recharge" + f(it.rechargeTimer) + (if (it.discharging) ":draining" else "")
                is BackupBattery -> ":battery" + (it.timeRemaining?.let { t -> f(t) } ?: "-")
                is Clonebay -> ":clone" + f(it.cloneProgress) + "/" + f(it.dyingProgress)
                else -> ""
            }
            it.codename + ":" + power + ":d" + it.damagedEnergyLevels + ":ion" + f(it.ionTimer) + ":rp" + f(it.repairProgress) + ":dp" + f(it.damageProgress) + extra
        }.toString()
        out["$label.weapons"] = ship.hardpoints.mapIndexed { slot, hp ->
            hp.weapon?.let { w -> "$slot:" + w.type.name + ":on=" + w.isPowered + ":charge=" + f(w.timeCharged) + ":extra=" + w.extraCharges + ":auto=" + w.autofireOverride } ?: "$slot:-"
        }.toString() + " autofire=" + ship.weapons?.autofire + " targets=" + (ship.weapons?.selectedTargets?.let { t -> var n = 0; for (x in t) n++; n } ?: 0)
        out["$label.cloak"] = ship.cloaking?.timeRemaining?.let { f(it) } ?: "-"
        out["$label.hacking"] = ship.hacking?.let { "active=${it.active} launched=${it.droneLaunched} landed=${it.droneLanded} ion=${f(it.ionTimer)}" } ?: "-"
        out["$label.mind"] = ship.mindControl?.let { "time=${it.timeRemaining?.let { t -> f(t) }} crew=${it.controlledCrew != null}" } ?: "-"
    }

    private fun manned(ship: Ship): String =
        ship.systems.filter { s -> s.room?.crew?.any { it.currentAction == AbstractCrew.Action.MANNING } == true }.map { it.codename }.sorted().toString()

    private fun facts(state: InGameState): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val sector = state.currentBeacon.sector
        out["where"] = "sector=${sector.sectorNumber} beacon=${sector.beacons.indexOf(state.currentBeacon)}"
        out["dialogue"] = (state.shipUI.diagnosticDialogue != null).toString()
        out["jumpPending"] = state.browserJumpPending().toString()
        shipFacts(out, "player", state.player)
        shipFacts(out, "enemy", state.enemy)
        return out
    }

    private fun differences(a: Map<String, String>, b: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in a.keys + b.keys) if (a[key] != b[key]) out[key] = "${a[key]} -> ${b[key]}"
        return out
    }

    private class Trip(val loaded: InGameState, val diff: Map<String, String>, val xmlDiff: String?, val xmlTokens: Int, val rngKept: Boolean)

    /** The XML tokens (elements) only one of the two saves has, as a multiset, so a moved element is not a difference. */
    private fun tokenDiff(a: String, b: String): Pair<List<String>, List<String>> {
        fun counts(xml: String): MutableMap<String, Int> {
            val map = HashMap<String, Int>()
            for (token in xml.split("<")) map[token.trim()] = (map[token.trim()] ?: 0) + 1
            return map
        }
        val ca = counts(a)
        val cb = counts(b)
        val onlyA = ArrayList<String>()
        val onlyB = ArrayList<String>()
        for ((token, n) in ca) repeat(maxOf(0, n - (cb[token] ?: 0))) { onlyA += token }
        for ((token, n) in cb) repeat(maxOf(0, n - (ca[token] ?: 0))) { onlyB += token }
        return onlyA.sorted() to onlyB.sorted()
    }

    private fun xmlDiff(a: String, b: String): String? {
        if (a == b) return null
        val (onlyA, onlyB) = tokenDiff(a, b)
        if (onlyA.isEmpty() && onlyB.isEmpty()) return null
        fun names(tokens: List<String>) = tokens.map { it.substringBefore(' ').substringBefore('>') }.distinct().take(6).toString()
        return "only saved: ${onlyA.size} ${names(onlyA)} (${onlyA.firstOrNull()?.take(110)}); only re-saved: ${onlyB.size} ${names(onlyB)} (${onlyB.firstOrNull()?.take(110)})"
    }

    private fun differingTokens(a: String, b: String): Int {
        val (onlyA, onlyB) = tokenDiff(a, b)
        return onlyA.size + onlyB.size
    }

    /** Saves, reloads, and compares the save the loaded game writes with the one it was loaded from. */
    private fun trip(main: MainGame, container: GameContainer, state: InGameState, settle: Boolean = true): Trip {
        val before = facts(state)
        val rngBefore = BrowserRandom.snapshot() + "|" + NativeRandom.snapshot()
        val xml = BrowserXml.write(state.saveGameState())
        check(rngBefore == BrowserRandom.snapshot() + "|" + NativeRandom.snapshot()) { "Writing a save advanced the random stream" }
        main.loadSavedGame(BrowserXml.parse(xml))
        val loaded = main.currentState as InGameState
        val rngKept = rngBefore == BrowserRandom.snapshot() + "|" + NativeRandom.snapshot()
        val again = BrowserXml.write(loaded.saveGameState())
        val diff = LinkedHashMap(differences(before, facts(loaded)))
        // The crew walk back to their posts: after a few seconds the same systems are manned.
        if (settle && loaded.isPaused && !loaded.shipUI.isWindowOpen) loaded.toggleQuickPause()
        if (settle) repeat(80) { main.update(container, .05f) }
        for ((label, ship) in listOf("player" to loaded.player, "enemy" to loaded.enemy)) {
            if (!settle) break
            if (ship == null || !ship.isPlayerShip) continue
            // A system the enemy hacks during the settling seconds can't be manned (its drone can land on any system
            // depending on the fixture's random stream); it says nothing about the load, so it's left out.
            val hacked = ship.systems.filter { it.hackedBy != null }.map { it.codename }.toSet()
            val mannedBefore = before["$label.manned"].orEmpty().removeSurrounding("[", "]").split(", ").filter { it.isNotEmpty() && it !in hacked }
            val mannedNow = manned(ship).removeSurrounding("[", "]").split(", ").filter { it.isNotEmpty() && it !in hacked }
            if (mannedBefore != mannedNow) diff["$label.mannedAfterLoad"] = "${before["$label.manned"]} -> ${manned(ship)}"
        }
        val canonicalBefore = canonical(xml)
        val canonicalAfter = canonical(again)
        return Trip(loaded, diff, xmlDiff(canonicalBefore, canonicalAfter), differingTokens(canonicalBefore, canonicalAfter), rngKept)
    }

    /** The consumed-unique-event set is written in hash order; compare it sorted. */
    private fun canonical(xml: String): String {
        val tag = "<consumedUnique "
        val items = ArrayList<String>()
        val rest = StringBuilder()
        var i = 0
        while (true) {
            val at = xml.indexOf(tag, i)
            if (at < 0) {
                rest.append(xml.substring(i))
                break
            }
            rest.append(xml.substring(i, at))
            val end = xml.indexOf("/>", at) + 2
            items += xml.substring(at, end)
            i = end
        }
        return rest.toString() + items.sorted().joinToString("")
    }

    /** The scenario keeps everything except the listed digest entries, and those really are lost. */
    private fun expect(label: String, trip: Trip, vararg lost: String): String {
        // The saved XML of the loaded game is a diagnostic only: AI-planned crew posts and drone flight points are
        // re-derived on a load, so its text differs there; the digest above is what is asserted.
        val tail = trip.xmlDiff?.let { " [${trip.xmlTokens} XML tokens differ: ${it.take(260)}]" } ?: ""
        val unexpected = trip.diff.filterKeys { it !in lost }
        check(unexpected.isEmpty()) { "$label lost: " + unexpected.entries.joinToString(" | ") { it.key + ": " + it.value.take(220) } + tail }
        val missing = lost.filter { it !in trip.diff }
        check(missing.isEmpty()) { "$label expected to lose $missing but kept it$tail" }
        return label + (if (lost.isEmpty()) " kept all" else " kept all but ${lost.toList()}") + (if (trip.rngKept) "" else " (the load advanced the random stream)") + (trip.xmlDiff?.let { " (${trip.xmlTokens} saved-XML tokens differ)" } ?: "")
    }

    // ---- Scenarios --------------------------------------------------------------------------------------------

    private fun idle(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        repeat(40) { main.update(container, .05f) }
        return expect("idle at a beacon", trip(main, container, state))
    }

    /** The opening event's dialogue is saved with the ship UI and comes back open. */
    private fun dialogue(main: MainGame, container: GameContainer): String {
        main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
        repeat(10) { main.update(container, .05f) }
        val state = main.currentState as InGameState
        check(state.shipUI.diagnosticDialogue != null) { "The opening event has no dialogue" }
        return expect("event dialogue open", trip(main, container, state))
    }

    private fun powerAndTarget(state: InGameState) {
        val player = state.player
        val weapons = player.weapons!!
        player.purchasedReactorPower = 25
        for (hp in player.hardpoints) hp.weapon?.let { weapons.setWeaponPower(it, true) }
        player.update(0f)
        val rooms = state.enemy!!.rooms
        player.hardpoints.indices.forEach { slot -> if (player.hardpoints[slot].weapon != null) weapons.selectedTargets.targetRoom(slot, rooms[slot % rooms.size]) }
    }

    /** Charging weapons with targets, shots in flight both ways, shields and damage. */
    private fun combat(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = false
        powerAndTarget(state)
        state.player.crew.filterIsInstance<LivingCrew>().first().setTargetRoom(state.player.weapons!!.room!!)
        var frames = 0
        // Wait for a shot in flight in either direction and the weapons to be manned, with the others still charging.
        while (frames++ < 900 && (state.player.projectiles.isEmpty() && state.enemy!!.projectiles.isEmpty() || !manned(state.player).contains("weapons"))) state.updateGameState(.05f)
        check(state.player.projectiles.size + state.enemy!!.projectiles.size > 0) { "No shot flew in 900 frames" }
        check(manned(state.player).contains("weapons")) { "Nobody manned the weapons: ${manned(state.player)}" }
        state.updateGameState(.05f)
        return expect("combat, charging weapons and shots in flight (${state.player.projectiles.size}+${state.enemy!!.projectiles.size})", trip(main, container, state))
    }

    /** Fires, a breach, thin air, boarders (one just beaming in) and wounded crew. */
    private fun hazards(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        player.rooms[1].spawnFire()
        player.rooms[2].spawnBreach()
        player.rooms[3].oxygen = .3f
        player.crew.filterIsInstance<LivingCrew>().first().let { it.health = it.maxHealth / 2 }
        val human = state.blueprintManager["human"] as CrewBlueprint
        state.givePlayerResources(ResourceSet().also {
            it.intruders += LivingCrewInfo.generateRandom(human, state)
            it.intruders += LivingCrewInfo.generateRandom(human, state)
        })
        repeat(30) { state.updateGameState(.05f) }
        check(player.rooms.sumOf { r -> r.fires.count { it != null } } > 0) { "The fire burnt out" }
        check(player.crew.count { it.mode == xyz.znix.xftl.crew.AbstractCrew.SlotType.INTRUDER } == 2) { "Boarders missing" }
        // Crew projectiles in flight are not saved (CrewAnimation::SaveState 0x479a30 keeps only the teleport state).
        val lostShots = if (player.crewShots.isNotEmpty()) arrayOf("player.crewShots") else emptyArray()
        return expect("fires, breach, thin air, boarders and wounded crew", trip(main, container, state), *lostShots)
    }

    private fun drones(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        player.purchasedReactorPower = 25
        player.dronesCount = 10
        val drones = player.drones!!
        drones.energyLevels = 8
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
        for ((slot, id) in listOf(0 to "COMBAT_1", 1 to "DEFENSE_1")) {
            drones.drones[slot] = Drones.DroneInfo(state.blueprintManager[id] as DroneBlueprint)
            check(drones.setDronePower(slot, true)) { "Drone $id did not launch" }
        }
        repeat(60) { state.updateGameState(.05f) }
        check(player.externalDrones.isNotEmpty()) { "No drone is out" }
        return expect("drones deployed (${player.externalDrones.size})", trip(main, container, state))
    }

    private fun hacking(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE_3")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val hacking = state.player.hacking!!
        state.player.dronesCount = 3
        hacking.increasePower(); state.player.update(0f)
        hacking.selectTarget(state.enemy!!.weapons!!.room!!)
        repeat(300) { state.updateGameState(.05f) }
        check(hacking.droneLanded) { "The hacking drone did not land" }
        hacking.startHackingPulse()
        repeat(20) { state.updateGameState(.05f) }
        check(hacking.active) { "No hacking pulse" }
        return expect("hacking pulse on the enemy", trip(main, container, state))
    }

    private fun mind(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        state.player.purchasedReactorPower = 25
        var seed = 71
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            check(seed < 150) { "No crewed fixture ship" }
        } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated)
        state.debugFlags.noEnemyFire.set = true
        val enemy = state.enemy!!
        val mind = state.player.mindControl!!
        mind.energyLevels = 3
        repeat(3) { mind.increasePower(); state.player.update(0f) }
        enemy.rooms.forEach { it.update(0f) }
        val target = enemy.crew.filterIsInstance<LivingCrew>().first { !it.isMindControlResistant }
        mind.selectRoom(target.room); mind.update(0f)
        repeat(10) { state.updateGameState(.05f) }
        check(mind.active && mind.controlledCrew != null) { "No mind control" }
        return expect("mind control of an enemy crew member", trip(main, container, state))
    }

    private fun cloak(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_STEALTH")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = false
        val cloak = state.player.cloaking!!
        state.player.purchasedReactorPower = 25
        repeat(cloak.energyLevels) { cloak.increasePower() }
        state.player.update(0f)
        cloak.activateCloak()
        repeat(20) { state.updateGameState(.05f) }
        check(cloak.active) { "The cloak did not start" }
        return expect("cloak active", trip(main, container, state))
    }

    /**
     * The planetary defence between two shots of a volley: the shot already released flies on (it is a projectile), the
     * timers are kept, but the shot still queued is gone, as natively (SaveSpace 0x4180c0 never writes SpaceManager's
     * queue at +0x2ac, only the timer at +0x2a8 and the projectiles).
     */
    private fun asb(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val env = AsbEnvironment(state, state.currentBeacon, 2)
        state.currentBeacon.debugSetEnvironment(env)
        var t = 0f
        while (t < 40f && env.diagnosticQueue.isEmpty()) {
            env.update(1f / 32f)
            t += 1f / 32f
        }
        check(env.diagnosticQueue.isNotEmpty()) { "No volley started in 40 s" }
        val queued = env.diagnosticQueue.size
        val released = state.player.projectiles.size
        check(released >= 1) { "No shot released" }
        val trip = trip(main, container, state, settle = false)
        val loadedEnv = trip.loaded.currentBeacon.getEnvironment(trip.loaded) as AsbEnvironment
        check(loadedEnv.diagnosticQueue.isEmpty() && loadedEnv.pdsTarget == 2) { "Loaded volley queue ${loadedEnv.diagnosticQueue.size}" }
        check(trip.loaded.player.projectiles.size == released) { "Released shots: ${trip.loaded.player.projectiles.size} of $released" }
        return expect("ASB between two shots ($released flying, $queued queued: the queue is not saved, like natively)", trip)
    }

    /**
     * A jump in progress. A plain save (autosave, Save, blur) holds the old beacon with the fuel already spent; Save
     * and close first completes the jump like the native save does (WorldManager 0x61a3a0 -> 0x619fc0) so nothing is
     * lost.
     */
    private fun jump(main: MainGame, container: GameContainer): String {
        fun begin(): Triple<InGameState, Int, Int> {
            val state = BrowserGameplayParityDiagnostics.fresh(main, container)
            val sector = state.currentBeacon.sector
            val from = sector.beacons.indexOf(state.currentBeacon)
            val target = sector.beacons.first { it !== state.currentBeacon }
            val fuel = state.player.fuelCount
            state.beginJumpOut(target)
            repeat(20) { main.update(container, .05f) }
            check(state.browserJumpPending() && state.player.fuelCount == fuel - 1) { "No jump pending" }
            return Triple(state, from, sector.beacons.indexOf(target))
        }
        val (plain, from, _) = begin()
        val fuel = plain.player.fuelCount
        val lostJump = trip(main, container, plain, settle = false)
        check(lostJump.loaded.currentBeacon.sector.beacons.indexOf(lostJump.loaded.currentBeacon) == from && lostJump.loaded.player.fuelCount == fuel) {
            "A plain save mid-jump should hold the old beacon with the fuel spent"
        }
        // A second fixture game: its map comes from the random stream at that moment, so it has its own beacons.
        val (settled, _, settledTo) = begin()
        val settledFuel = settled.player.fuelCount
        check(settled.browserSettleForSave()) { "Nothing to settle" }
        val loaded = trip(main, container, settled, settle = false).loaded
        check(loaded.currentBeacon.sector.beacons.indexOf(loaded.currentBeacon) == settledTo && loaded.player.fuelCount == settledFuel) {
            "Save and close mid-jump should hold the new beacon"
        }
        check(!loaded.browserJumpPending()) { "A jump is still pending after the settled save" }
        return "jump: a plain save holds the old beacon (fuel spent, as at the animation start), Save and close completes it first (new beacon)"
    }
}
