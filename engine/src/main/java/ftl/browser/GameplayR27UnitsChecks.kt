package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.CombatTask
import xyz.znix.xftl.ai.ManningTask
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.luaKill
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.sys.GameContainer

/**
 * r27-units (Multiverse unit sweep) rules with a known answer, run by the gameplay parity suite in vanilla and with
 * mods.
 */
internal object GameplayR27UnitsChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            // Every check runs, so one run without the fixes names each one that fails.
            val checks = listOf<() -> String>({ combatTaskIgnoresCanFight(main, container) }, { noStationWithoutCanMan(main, container) },
                { rockThrowEveryPass(main, container) }, { killNoCloneStaysDead(main, container) },
                { podFliesAtBlueprintSpeed(main, container) }, { managerDronePowersItsRoom(main, container) }, { cloneQueueBoarderLoads(main, container) },
                { bodyPoweredDroneSwitchesOff(main, container) })
            val passed = ArrayList<String>()
            val failed = ArrayList<String>()
            for (c in checks) {
                try {
                    passed += c()
                } catch (e: IllegalStateException) {
                    failed += e.message ?: e.toString()
                }
            }
            check(failed.isEmpty()) { failed.joinToString("; ") }
            return "r27units ok: " + passed.joinToString(", ")
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** A crew member that cannot fight (Hyperspace CAN_FIGHT false: Multiverse holograms...). */
    private class NoFight(blueprint: CrewBlueprint, room: Room) :
        LivingCrew(blueprint, room.ship.sys.animations, room, SlotType.CREW) {
        override val canFight: Boolean get() = false
    }

    /** A crew member that cannot man a station (Hyperspace CAN_MAN false: Multiverse pony_engi_nano...). */
    private class NoMan(blueprint: CrewBlueprint, room: Room) :
        LivingCrew(blueprint, room.ship.sys.animations, room, SlotType.CREW) {
        override val canManSystem: Boolean get() = false
    }

    /**
     * HS's CrewAI::PrioritizeTask hook (CustomCrew.cpp) prices manning at 1001 for a crew member whose CanMan is false,
     * so it never takes a station: neither the one of its own room nor an unmanned one elsewhere (HS oracle r27u-late
     * pony_engi_nano: the uncontrollable player crew member stays in room 0; ours walked to room 8 to man it).
     */
    private fun noStationWithoutCanMan(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = crewedEnemy(state)
        val human = state.blueprintManager["human"] as CrewBlueprint
        enemy.update(0f)
        for (c in enemy.crew.toList()) if (c.mode == AbstractCrew.SlotType.CREW) c.removeFromShip()
        // In an unmanned system room, with every station of the ship unmanned.
        val station = enemy.rooms.first { it.system?.configuration?.computerPoint != null && it.doors.isNotEmpty() }
        val noMan = NoMan(human, station)
        noMan.info = LivingCrewInfo.generateRandom(human, state)
        enemy.crew.add(noMan)
        noMan.jumpTo(enemy.findSpaceForCrew(station, AbstractCrew.SlotType.CREW))
        repeat(3) {
            enemy.update(0f)
            enemy.crewAI.update()
            check(enemy.crewAI.taskOf(noMan) !is ManningTask) {
                "GameplayR27UnitsChecks.kt:82 a crew member that cannot man holds ${enemy.crewAI.taskOf(noMan)}"
            }
            check(noMan.pathingTarget == null) {
                "GameplayR27UnitsChecks.kt:85 a crew member that cannot man walks to room ${noMan.pathingTarget?.room?.id}"
            }
        }
        return "no station without CanMan"
    }

    /**
     * CrewAI::AttemptToAssign (1.6.9 0x602e90) offers a boarder problem to every crew member that is neither an
     * intruder (GetIntruder) nor a drone (IsDrone) nor player controlled; neither it nor PrioritizeTask (0x602ab0, HS
     * hook CustomCrew.cpp: only manning wants CanMan and repairs CanRepair) asks CanFight. So an idle crew member that
     * cannot fight, standing with the boarders, takes their problem before a crew member at a station elsewhere is
     * sent (HS oracle r27p-powers1 hologram: the enemy's hologram takes it and the crew member in room 2 stays; ours
     * sent that one to the boarders' room, and every later scenario started from a different crew).
     */
    private fun combatTaskIgnoresCanFight(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = crewedEnemy(state) { ship -> ship.rooms.any { stationless(it) && it.cellCount >= 2 } }
        val human = state.blueprintManager["human"] as CrewBlueprint
        enemy.update(0f)
        // One crew member at a station, settled there (the first loops hand out the manning problems).
        for (c in enemy.crew.toList()) if (c.mode == AbstractCrew.SlotType.CREW) c.removeFromShip()
        val station = enemy.rooms.first { it.system?.configuration?.computerPoint != null && it.doors.isNotEmpty() }
        val guard = enemy.addCrewMember(LivingCrewInfo.generateRandom(human, state), false)
        guard.jumpTo(enemy.findSpaceForCrew(station, AbstractCrew.SlotType.CREW))
        repeat(3) { enemy.update(0f); enemy.crewAI.update() }
        check(enemy.crewAI.taskOf(guard) is ManningTask) { "GameplayR27UnitsChecks.kt:110 the guard holds ${enemy.crewAI.taskOf(guard)}" }

        // A crew member that cannot fight, idle in another room (one without a station, so it takes none), and a
        // boarder there.
        val boarded = enemy.rooms.first { it !== station && stationless(it) && it.cellCount >= 2 && it.crew.isEmpty() }
        val noFight = NoFight(human, boarded)
        noFight.info = LivingCrewInfo.generateRandom(human, state)
        enemy.crew.add(noFight)
        noFight.jumpTo(enemy.findSpaceForCrew(boarded, AbstractCrew.SlotType.CREW))
        enemy.addCrewMember(LivingCrewInfo.generateRandom(human, state), false, true)
            .also { it.jumpTo(enemy.findSpaceForCrew(boarded, AbstractCrew.SlotType.INTRUDER)) }
        enemy.update(0f)
        enemy.crewAI.update()

        check(enemy.crewAI.taskOf(noFight) is CombatTask) {
            "GameplayR27UnitsChecks.kt:125 the crew member that cannot fight holds ${enemy.crewAI.taskOf(noFight)}, not the boarder problem"
        }
        check(enemy.crewAI.taskOf(guard) is ManningTask && guard.pathingTarget?.room.let { it == null || it === station }) {
            "GameplayR27UnitsChecks.kt:128 the guard left its station for the boarders (${enemy.crewAI.taskOf(guard)})"
        }
        return "combat task for a crew member that cannot fight"
    }

    /**
     * Animation::Update (1.6.9 0x59bd50): a looping strip's clock stops on its end for one update before the next pass
     * starts from 0, so a rock's throwing frame (frame 3 of its shoot strip, RockAnimation::FireShot) comes once per
     * pass whatever the frame time. Updates of half the strip's time: frames 0, 2, 3, 2, 3... (a throw every second
     * update); the modulo clock showed 0, 2, 0, 2 and never threw (HS oracle r27u-fight: Multiverse's
     * unique_jerry_pony, a 0.05 s strip, kills its boarder within 2 s; ours never threw at 60 fps).
     */
    private fun rockThrowEveryPass(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val room = ship.rooms.first { it.cellCount >= 2 && it.doors.isNotEmpty() && it.crew.isEmpty() }
        val rock = ship.addCrewMemberInRoom(LivingCrewInfo.generateRandom(state.blueprintManager["rock"] as CrewBlueprint, state), room, false)
        rock.jumpTo(xyz.znix.xftl.math.RoomPoint(room, room.slotToPoint(0)))
        val boarder = ship.addCrewMember(LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), false, true)
        boarder.jumpTo(xyz.znix.xftl.math.RoomPoint(room, room.slotToPoint(1)))
        ship.update(0f)
        val strips = listOf("down", "right", "up", "left").mapNotNull { ship.sys.animations.getOrNull("rock_shoot_$it") }
        check(strips.isNotEmpty() && strips.all { it.totalTime == strips[0].totalTime && it.length > 3 }) {
            "GameplayR27UnitsChecks.kt:151 the rock has no 4-frame shoot strips (${strips.map { it.totalTime to it.length }})"
        }
        val dt = strips[0].totalTime / 2f
        val start = boarder.health
        // The boarder is not updated, so it stays in its cell and the rock shoots (no shared cell: no punch).
        repeat(16) {
            rock.update(dt)
            ship.crewShots.removeAll { shot -> !shot.update(dt) }
        }
        check(boarder.health < start) {
            "GameplayR27UnitsChecks.kt:161 the rock never threw in 16 updates of half its strip (boarder ${boarder.health} of $start)"
        }
        return "rock throws every pass"
    }

    /** A room with doors and no station (no system, or one that cannot be manned: oxygen, medbay...). */
    private fun stationless(room: Room): Boolean = room.doors.isNotEmpty() && room.system?.configuration?.computerPoint == null

    /**
     * CrewMember::Kill(noClone) (1.6.9 0x4701c0) clears clone_ready when noClone is set, so a crew member a script kills
     * with Kill(true) is never queued in the clone bay (HS oracle r27p-powers1 human_technician: the enemy keeps its
     * crew count after each scenario's Kill(true); ours cloned the killed human, one crew member more). Hyperspace only
     * (the Lua binding); vanilla has no script kill.
     */
    private fun killNoCloneStaysDead(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "kill noClone (no Lua in vanilla)"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val room = ship.rooms.first { it.doors.isNotEmpty() }
        val human = state.blueprintManager["human"] as CrewBlueprint
        val crew = ship.addCrewMemberInRoom(LivingCrewInfo.generateRandom(human, state), room, false)
        crew.hsCloneReady = true // as the crew loop sets it on a ship with a clone bay
        HsCrew.luaKill(crew, true)
        check(!crew.hsCloneReady) { "GameplayR27UnitsChecks.kt:184 Kill(true) left the crew member ready to clone" }
        repeat(4) { ship.update(1f / 32) }
        check(!crew.hsCloneReady && ship.clonebay?.queue?.contains(crew) != true) {
            "GameplayR27UnitsChecks.kt:187 a crew member killed with Kill(true) went into the clone queue"
        }
        return "kill noClone stays dead"
    }

    /**
     * A boarding pod flies at its blueprint's `<speed>` (16 px per second each), as every space drone: HS oracle r27u-pod,
     * the pods of Multiverse BOARDER (18), A55_BOARDER (20) and GANA_BOARDER (50) move 4.8, 5.3 and 13.3 px a 1/60 s
     * frame, SPAWN_BOARDER (999) lands within 10 frames; ours flew every pod at the vanilla 18. A pod blueprint of speed
     * 50 (built here, so vanilla runs it too) launched by a generated enemy's drone system.
     */
    private fun podFliesAtBlueprintSpeed(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val drones = droneEnemy(state)
        val xml = org.jdom2.Element("droneBlueprint").setAttribute("name", "R27U_POD_50")
        for ((k, v) in listOf("type" to "BOARDER", "power" to "1", "speed" to "50")) xml.addContent(org.jdom2.Element(k).setText(v))
        val info = xyz.znix.xftl.systems.Drones.DroneInfo(xyz.znix.xftl.weapons.DroneBlueprint(xml))
        drones.drones[0] = info
        check(drones.setDronePower(0, true)) { "GameplayR27UnitsChecks.kt:205 the enemy's boarding pod did not launch" }
        state.updateGameState(.05f)
        val pod = info.instance as xyz.znix.xftl.drones.BoardingDrone
        check(pod.podSpeed == 50 * 16) { "GameplayR27UnitsChecks.kt:208 a speed 50 pod flies at ${pod.podSpeed} px/s (want 800)" }
        drones.setDronePower(0, false)
        pod.removeInstance()
        return "pod at blueprint speed"
    }

    /**
     * Hyperspace ShipManager::OnLoop (CustomCrew.cpp) counts the BONUS_POWER of every crew list member in a system's
     * room, crew drones included: HS oracle r27u-drones1, Multiverse's MANNING (power 0, race `manning` bonusPower 1)
     * deployed in the drone room gives the drone system an effective power of 1, ours 0 (the Manager Drone powered no
     * system). The enemy's MANNING_ENEMY (power 1) is deployed and its body, powered in the drone room, adds one bar.
     * Multiverse data only (F3).
     */
    private fun managerDronePowersItsRoom(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "manager drone (no Hyperspace)"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val bp = state.blueprintManager.getOrNull("MANNING_ENEMY") as? xyz.znix.xftl.weapons.DroneBlueprint
            ?: return "manager drone (no Multiverse MANNING_ENEMY)"
        val drones = droneEnemy(state)
        val info = xyz.znix.xftl.systems.Drones.DroneInfo(bp)
        drones.drones[0] = info
        check(drones.setDronePower(0, true)) { "GameplayR27UnitsChecks.kt:229 the enemy's Manager Drone did not deploy" }
        val body = (1..80).firstNotNullOfOrNull {
            state.updateGameState(.05f)
            (info.instance as? xyz.znix.xftl.drones.AbstractIndoorsDrone)?.pawn?.takeIf { p -> p.room === drones.room && info.instance!!.let { (it as xyz.znix.xftl.drones.AbstractIndoorsDrone).isPowered } }
        }
        checkNotNull(body) { "GameplayR27UnitsChecks.kt:234 the Manager Drone's body is not powered in the drone room after 4 s" }
        val bonus = xyz.znix.xftl.crew.ZoltanEnergySource.getSystemPower(drones)
        check(bonus == 1) { "GameplayR27UnitsChecks.kt:236 the Manager Drone in the drone room gives the system $bonus bonus power (want 1)" }
        drones.setDronePower(0, false)
        info.instance?.removeInstance()
        return "manager drone powers its room"
    }

    /**
     * A drone whose power its own crew drone body covers can be switched off: DroneSystem::DePowerDrone (1.6.9 0x611630)
     * succeeds and UpdateBonusPower (0x612f80) powers again only a drone the bonus still covers, and the body's bonus
     * ends with its drone (Functional). HS oracle r27u-drones1: the enemy's MANNING_ENEMY (power 1, its body's bonus 1)
     * goes off on DeselectArmament; r27u-d2a: the player's DIRECTOR depowered, powered 0. Ours kept such a drone on as
     * "purely Zoltan-powered". Multiverse data only (F3).
     */
    private fun bodyPoweredDroneSwitchesOff(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "body-powered drone off (no Hyperspace)"
        // The player's drone system (the switch is the player's; an enemy's drone AI powers its drones again from the
        // reactor), on a start ship with one, as the unit sweep picks it.
        val ships = listOf("PLAYER_SHIP_ENGI_3", "PLAYER_SHIP_CIRCLE").filter { main.gameContent.blueprintManager.getOrNull(it) != null }
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, ships.first())
        val bp = state.blueprintManager.getOrNull("MANNING_ENEMY") as? xyz.znix.xftl.weapons.DroneBlueprint
            ?: return "body-powered drone off (no Multiverse MANNING_ENEMY)"
        val player = state.player
        val drones = checkNotNull(player.drones) { "GameplayR27UnitsChecks.kt:258 Fixture: ${ships.first()} has no drone system" }
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        player.purchasedReactorPower = 25
        drones.energyLevels = 8
        player.updateAvailablePower()
        player.dronesCount = 5
        val info = xyz.znix.xftl.systems.Drones.DroneInfo(bp)
        drones.drones[0] = info
        check(drones.setDronePower(0, true)) { "GameplayR27UnitsChecks.kt:266 Fixture: the player's Manager Drone did not deploy" }
        fun st(): String {
            val inst = info.instance as? xyz.znix.xftl.drones.AbstractIndoorsDrone
            val p = inst?.pawn
            return "on${inst?.isPowered} cov${drones.powerManager.getForcedPower(0)} sysF${drones.forcedPower} sup${drones.powerSupplied} sel${drones.powerSelected} " +
                "inRoom${p?.room === drones.room} bon${p?.bonusSystemPower} fn${p?.let { xyz.znix.xftl.hs.crew.HsCrew.ex(it).functional }} req${info.requiredPower}"
        }
        val seen = ArrayList<String>()
        val body = (1..80).firstNotNullOfOrNull {
            state.updateGameState(.05f)
            if (seen.size < 4) seen += st()
            (info.instance as? xyz.znix.xftl.drones.AbstractIndoorsDrone)?.pawn?.takeIf { p -> p.room === drones.room && (info.instance as xyz.znix.xftl.drones.AbstractIndoorsDrone).isPowered &&
                drones.powerManager.getForcedPower(0) == 1 }
        }
        checkNotNull(body) { "GameplayR27UnitsChecks.kt:280 Fixture: the Manager Drone's body does not cover it in the drone room after 4 s (${seen.joinToString("; ")}; now ${st()})" }
        val off = drones.setDronePower(0, false)
        val after = st()
        repeat(40) { state.updateGameState(.05f) }
        val on = info.instance?.isPowered == true
        check(off && !on) {
            "GameplayR27UnitsChecks.kt:286 the body-powered Manager Drone does not switch off (accepted $off, powered after 2 s $on; at the switch $after, now ${st()})"
        }
        info.instance?.removeInstance()
        return "body-powered drone switches off"
    }

    /**
     * A boarder that dies breaking a door aboard the other ship waits in its own ship's clone bay; the save keeps no door
     * for it, and a load takes a saved door number past the clone bay ship's doors as none (unit sweep F3 case 19, the
     * die step: an enemy intruder killed at the player's door, queued in the Free Mantis clone bay, and the load read
     * the player's door number among the enemy's doors, IndexOutOfBoundsException). Here the player's crew member on
     * the enemy ship, queued in the player's clone bay.
     */
    private fun cloneQueueBoarderLoads(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        BrowserUnitSweepDiagnostics.cloneBay(player)
        player.purchasedReactorPower = 30
        BrowserUnitSweepDiagnostics.powerAll(player)
        val bay = checkNotNull(player.clonebay) { "GameplayR27UnitsChecks.kt:305 Fixture: no clone bay on the player's ship" }
        // Door::IsSealed: a door stops intruders only with a doors system powered to 2 or more.
        val enemy = crewedEnemy(state) { e -> e.doorsSystem != null && e.doors.any { it.left != null && it.right != null } }
        enemy.doorsSystem!!.let { d ->
            // a subsystem runs on its own bars, undamaged
            if (d.energyLevels < 2) d.energyLevels = 2
            d.damagedEnergyLevels = 0
            check(d.effectivePower >= 2) { "GameplayR27UnitsChecks.kt:312 Fixture: the enemy's doors are at ${d.effectivePower} (want 2)" }
        }
        // between two rooms without the enemy's crew, so the boarder walks before a fight holds it
        val inner = enemy.doors.filter { it.left != null && it.right != null }
        val door = inner.firstOrNull { it.left!!.crew.isEmpty() && it.right!!.crew.isEmpty() } ?: inner.first()
        val human = state.blueprintManager["human"] as CrewBlueprint
        // The player's crew member teleports aboard (teleportAnimatedTo, as the teleporter's send and the unit sweep's
        // teleport step), so it stays the player's (ownerShip) on the enemy ship; Ship.addCrewMember's intruders are
        // the enemy's (their owner is the ship they are added to).
        val crew = player.addCrewMemberInRoom(LivingCrewInfo.generateRandom(human, state), player.rooms.first { it.doors.isNotEmpty() }, false)
        crew.teleportAnimatedTo(door.left!!)
        var arrive = 0
        while ((crew.room.ship !== enemy || crew.isTeleporting) && arrive++ < 200) state.updateGameState(1f / 32)
        crew.jumpTo(enemy.findSpaceForCrew(door.left!!, AbstractCrew.SlotType.INTRUDER))
        crew.setTargetRoom(door.right!!)
        var walk = 0
        while (crew.browserDoorToAttack == null && walk++ < 64) state.updateGameState(1f / 32)
        check(crew.ownerShip === player && crew.browserDoorToAttack != null) {
            "GameplayR27UnitsChecks.kt:330 Fixture: the boarder breaks no door (the player's ${crew.ownerShip === player}, aboard the enemy ${crew.room.ship === enemy}, ${arrive} updates)"
        }
        crew.hsCloneReady = true
        crew.dealDamage(xyz.znix.xftl.crew.FireDamage(10_000f))
        var frames = 0
        // Its own loop plays the death and queues it (onFinishedDying): the enemy's intruder AI, which would give the
        // dying boarder another room to walk to (and so no door), stays out, as in the unit sweep's case 19.
        while (crew !in bay.queue && frames++ < 400) crew.update(1f / 32)
        check(crew in bay.queue && crew.browserDoorToAttack != null) {
            "GameplayR27UnitsChecks.kt:339 Fixture: the boarder is not queued with its door (queued ${crew in bay.queue}, door ${crew.browserDoorToAttack != null}, $frames updates)"
        }
        main.writeRunSave()
        val doc = BrowserXml.parse(checkNotNull(BrowserFiles.getRun()) { "GameplayR27UnitsChecks.kt:342 no run save" })
        fun queues(e: org.jdom2.Element): List<org.jdom2.Element> =
            (if (e.name == "cloningQueue") listOf(e) else emptyList()) + e.children.flatMap { queues(it) }
        val queued = queues(doc.rootElement).flatMap { it.getChildren("crewMember") }
        check(queued.size == 1) { "GameplayR27UnitsChecks.kt:346 the save's clone queues hold ${queued.size} crew (want 1)" }
        check(queued[0].getChild("doorToAttack") == null) {
            "GameplayR27UnitsChecks.kt:348 the save keeps the queued boarder's door (number ${queued[0].getChild("doorToAttack")?.getAttributeValue("id")} of the enemy's)"
        }
        // As an earlier build saved it: a door number past the clone bay ship's doors.
        queued[0].addContent(org.jdom2.Element("doorToAttack").setAttribute("id", (player.doors.size + enemy.doors.size).toString())
            .setAttribute("standX", "0").setAttribute("standY", "0"))
        try {
            main.loadSavedGame(doc)
        } catch (e: Exception) {
            error("GameplayR27UnitsChecks.kt:356 the save with a queued boarder's door does not load: $e")
        }
        val loaded = main.currentState as InGameState
        check(loaded.player.clonebay?.queue?.size == 1) {
            "GameplayR27UnitsChecks.kt:360 the loaded clone queue holds ${loaded.player.clonebay?.queue?.size} crew (want 1)"
        }
        return "queued boarder's save loads"
    }

    /** A generated enemy (PIRATE/REBEL) with a drone system, its drones off and removed, reactor and parts to spare. */
    private fun droneEnemy(state: InGameState): xyz.znix.xftl.systems.Drones {
        val specs = listOf("PIRATE", "REBEL").mapNotNull { runCatching { state.eventManager.getShip(it) }.getOrNull() }
        found@ for (seed in 71 until 171) for (spec in specs) {
            state.debugSpawnShip(spec, Difficulty.NORMAL, 6, seed)
            if (state.enemy?.drones != null) break@found
        }
        val enemy = state.enemy!!
        val drones = checkNotNull(enemy.drones) { "GameplayR27UnitsChecks.kt:373 no generated enemy with a drone system" }
        // the enemy's own drones leave the list: its drone AI would launch them again
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        enemy.purchasedReactorPower = 25
        drones.energyLevels = 8
        enemy.updateAvailablePower()
        enemy.dronesCount = 5
        if (drones.drones.isEmpty()) drones.drones.add(null)
        return drones
    }

    private fun crewedEnemy(state: InGameState, also: (Ship) -> Boolean = { true }): Ship {
        var seed = 71
        while (true) {
            check(seed < 400) { "No crewed fixture ship" }
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            val ship = state.enemy!!
            if (!ship.isAutoScout && !ship.isAutomated && ship.crew.any { it.mode == AbstractCrew.SlotType.CREW } &&
                ship.rooms.size >= 6 && ship.rooms.any { it.system?.configuration?.computerPoint != null && it.doors.isNotEmpty() } && also(ship)
            )
                return ship
        }
    }
}
