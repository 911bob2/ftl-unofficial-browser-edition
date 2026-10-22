package ftl.browser

import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.Cloaking
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Round 3 combat fixes: the flagship's artillery power (ShipManager::AddSystem 0x4a8370, ShipAI 0x6259b0), the cloak
 * timer rescale (CloakingSystem::OnLoop 0x62f280), manning anywhere in the room (crew loop 0x47b190), the crew laser's
 * damage on arrival (0x481de0 / 0x4881c0), the Rock's 1 s throw, and the door route (ShipGraph 0x463880).
 */
internal object GameplayParityCombat3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val artillery = flagshipArtillery(main, container)
            val pilot = pilotAnywhere(main, container)
            val rock = rockStrike(main, container)
            val laser = laserOnArrival(main, container)
            val route = doorRoute(main, container)
            val bossPath = bossPathTieBreak(main, container)
            val zoltan = zoltanBonus(main, container)
            val telepathy = telepathyColour()
            val boarder = flagshipOpeningBoarder(main, container)
            val built = enemyCreationPower(main, container)
            val stun = droneStunExplosion(main, container)
            val asb = asbVolley(main, container)
            // CFPS::OnLoop (0x5ba020): a frame never advances the game by more than 1/32 s.
            check(BrowserEngine.MAX_STEP == 1f / 32f) { "Frame step cap ${BrowserEngine.MAX_STEP}" }
            return "combat3 ok: $artillery, $pilot, $rock, $laser, $route, $bossPath, $zoltan, $telepathy, $boarder, $built, step cap 1/32 s, $stun, $asb"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * Repeated systems share one systemInfo, the last entry's (BOSS_1 artillery 4/4/3/3 -> 3 each), and the AI's last
     * power step raises every artillery copy. Also the cloak rescale: losing a bar mid-cloak keeps the elapsed share.
     */
    private fun flagshipArtillery(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        boss.stage = 1
        state.currentBeacon = boss.beacon!!
        if (state.enemy == null) check(state.browserSpawnBossHere()) { "Flagship did not spawn" }
        val flagship = state.enemy!!
        state.shipUI.diagnosticDialogue?.selectOption(0)

        val artillery = flagship.mainSystems.filterIsInstance<Artillery>()
        check(artillery.size >= 3) { "Stage 1 flagship has ${artillery.size} artillery" }
        check(artillery.all { it.energyLevels == 3 }) { "Artillery levels ${artillery.map { it.energyLevels }}" }

        val ai = ShipAI(flagship, state.player)
        repeat(4) { ai.update(0.05f) }
        check(artillery.all { it.powerSupplied > 0 }) { "Unpowered artillery: ${artillery.map { it.powerSupplied }}" }

        // Cloak: dropping from 2 bars (10 s) to 1 (5 s) halves what is left.
        val cloak = flagship.mainSystems.filterIsInstance<Cloaking>().firstOrNull() ?: return "artillery ${artillery.size} x 3"
        while (cloak.powerSelected < 2 && cloak.powerSelected < cloak.energyLevels) {
            val before = cloak.powerSelected
            cloak.increasePower()
            if (cloak.powerSelected == before) break
        }
        while (cloak.powerSelected > 2) cloak.decreasePower()
        check(cloak.powerSupplied == 2) { "Fixture cloak at ${cloak.powerSupplied} bars" }
        cloak.activateCloak()
        check(cloak.active) { "Fixture cloak did not start" }
        repeat(20) { cloak.update(0.1f) }
        val before = cloak.timeRemaining!!
        // An active cloak holds its power (a permanent lock), so the bar is lost to damage, as in a fight.
        cloak.dealDamage(cloak.energyLevels - 1, 0)
        cloak.update(0.1f)
        check(cloak.powerSupplied == 1) { "Fixture cloak didn't drop to one bar: ${cloak.powerSupplied}" }
        val left = cloak.timeRemaining ?: error("The cloak ended when it lost a bar")
        val expected = before * 0.5f - 0.1f
        check(abs(left - expected) < 0.01f) { "Cloak left $left s after losing a bar at $before (expected $expected)" }
        return "artillery ${artillery.size} x 3 all powered, cloak rescale"
    }

    /** A pilot anywhere in the piloting room mans it (0x47b190); a stunned one doesn't. */
    private fun pilotAnywhere(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val piloting = ship.piloting!!
        val room = piloting.room!!
        check(room.cellCount >= 2) { "Fixture piloting room has one cell" }
        val parking = ship.rooms.first { it != room && it.crew.isEmpty() && it.cellCount >= 2 }
        for (crew in room.crew.toList()) {
            crew.jumpTo(parking, ConstPoint.ZERO)
            crew.nextTargetPos = null
        }
        room.updateCrewInRoom(); parking.updateCrewInRoom()
        check(!piloting.hasPilot) { "Empty piloting room counts as manned" }

        val console = piloting.configuration.computerPoint ?: ConstPoint.ZERO
        val spot = (0 until room.cellCount).map { room.slotToPoint(it) }.first { it.x != console.x || it.y != console.y }
        val pilot = ship.crew.filterIsInstance<LivingCrew>().first()
        pilot.jumpTo(room, spot)
        pilot.nextTargetPos = null
        room.updateCrewInRoom(); parking.updateCrewInRoom(); ship.updateCrewReservedSlots()
        check(piloting.hasPilot) { "A pilot off the console slot doesn't man the piloting room" }
        pilot.stun(3f)
        check(!piloting.hasPilot) { "A stunned pilot mans the piloting room" }
        return "pilot anywhere in the room"
    }

    private fun duel(state: InGameState, shooterRace: String): Triple<LivingCrew, LivingCrew, Room> {
        val ship = state.player
        val room = ship.rooms.first { it.width >= 2 && it.height >= 2 }
        ship.crew.toList().forEach { it.removeFromShip() }
        val shooter = ship.addCrewMember(
            LivingCrewInfo.generateRandom(state.blueprintManager[shooterRace] as CrewBlueprint, state), false, true
        )
        val victim = ship.addCrewMember(
            LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), false, false
        )
        shooter.jumpTo(room, ConstPoint(0, 0))
        victim.jumpTo(room, ConstPoint(1, 1))
        shooter.nextTargetPos = null; victim.nextTargetPos = null
        room.updateCrewInRoom(); ship.updateCrewReservedSlots()
        state.debugFlags.noCrewDamage.set = false
        // A stunned victim doesn't shoot back, so every crew laser is the shooter's.
        victim.stun(60f)
        return Triple(shooter, victim, room)
    }

    private fun stepShots(state: InGameState, dt: Float) {
        val shots = state.player.crewShots
        for (shot in shots.toList()) {
            // A shot deals its damage on arrival and is removed after its impact puff.
            if (!shot.update(dt))
                shots.remove(shot)
        }
    }

    /**
     * A Rock shooting from another cell throws a rock (RockAnimation::FireShot 0x478870, frame 3 of its looping 1 s
     * strip) once a second. (Round 3 read a bogus ZHL address for this FireShot and had Rocks strike without a
     * projectile; round 7 corrected it.)
     */
    private fun rockStrike(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val (rock, victim, _) = duel(state, "rock")
        check(rock.throwsOnShootFrame) { "Rock doesn't throw on its shoot strip" }
        val seen = HashSet<xyz.znix.xftl.crew.CrewShot>()
        val throws = ArrayList<Float>()
        for (i in 1..240) {
            rock.update(0.05f); victim.update(0.05f)
            for (shot in state.player.crewShots) if (seen.add(shot)) throws += i * 0.05f
            stepShots(state, 0.05f)
        }
        check(throws.size >= 8) { "Rock threw ${throws.size} times in 12 s" }
        val gaps = throws.zipWithNext { a, b -> b - a }
        check(gaps.all { abs(it - 1f) < 0.06f }) { "Rock throw gaps $gaps" }
        return "rock throws every 1.0 s (${throws.size})"
    }

    /** A crew laser's damage lands when the laser arrives, rolled 3..7, not half-way through the shot cycle. */
    private fun laserOnArrival(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val (shooter, victim, _) = duel(state, "human")
        var firstShot = -1f
        var firstHit = -1f
        val start = victim.health
        for (i in 1..80) {
            shooter.update(0.05f); victim.update(0.05f)
            if (firstShot < 0f && state.player.crewShots.isNotEmpty()) firstShot = i * 0.05f
            stepShots(state, 0.05f)
            if (firstHit < 0f && victim.health < start) firstHit = i * 0.05f
        }
        check(firstShot > 0f && firstHit > 0f) { "No laser fight: shot $firstShot hit $firstHit" }
        // Diagonal neighbours are ~49 px apart (+-8 px scatter): at 160 px/s the laser flies at least 0.2 s.
        check(firstHit - firstShot >= 0.2f) { "Laser damage $firstHit s came with the shot at $firstShot s" }
        val dealt = start - victim.health
        check(dealt >= 2.9f) { "First laser dealt $dealt" }
        return "laser damage on arrival (+${"%.2f".format(firstHit - firstShot)} s)"
    }

    /** The door route (Dijkstra 0x463880) is a chain of doors from the start room to the goal, and is walked. */
    private fun doorRoute(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val crew = ship.crew.filterIsInstance<LivingCrew>().first()
        val start = crew.room
        val reachable = ship.rooms.filter { it != start }
        var longest = 0
        for (dest in reachable) {
            val route = ship.pathFinder.doorRoute(crew, crew.getPixelPositionCentre(), start, dest)
                ?: error("No route ${start.id} -> ${dest.id}")
            check(route.isNotEmpty()) { "Empty route to another room" }
            var here = start
            for (door in route) {
                check(door.left == here || door.right == here) { "Route ${start.id} -> ${dest.id} jumps rooms" }
                here = (if (door.left == here) door.right else door.left)!!
            }
            check(here == dest) { "Route ${start.id} -> ${dest.id} ends in ${here.id}" }
            longest = maxOf(longest, route.size)
        }

        val far = reachable.maxByOrNull {
            ship.pathFinder.doorRoute(crew, crew.getPixelPositionCentre(), start, it)!!.size
        }!!
        ship.doors.forEach { it.open = true }
        check(crew.setTargetRoom(far)) { "Can't send the crew to room ${far.id}" }
        repeat(400) { if (crew.room != far || crew.pathingTarget != null) crew.update(0.05f) }
        check(crew.room == far) { "Crew didn't walk the route to room ${far.id}, stuck in ${crew.room.id}" }
        return "door routes, longest $longest doors"
    }

    /**
     * The flagship's route (StarMap Dijkstra 0x5f81d0) on two native last-stand maps that have two equally short
     * routes each: location lists and connection orders as dumped from the original (FS0 and FS2 runs). The native
     * paths were 20-17-13-8 and 19-14-17-12; a plain breadth-first search gives 20-16-12-8 and 19-14-13-12.
     */
    private fun bossPathTieBreak(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        val maps = listOf(
            "1,4,5;0,2,4,5;1,3,5,6,7;2,6,7;0,1,5,8;0,1,2,4,6,8,9,10;2,3,5,7,9,10;2,3,6;4,5,9,12,13;5,6,8,10,13;" +
                "5,6,9,11,13,14;10,14,15;8,13,16;8,9,10,12,14,17;10,11,13,15,17,18,19;11,14,18,19;12,17,20;" +
                "13,14,16,18,20,21,22;14,15,17,19,21,22;14,15,18,23;16,17,21;17,18,20,22;17,18,21;19" to listOf(20, 17, 13, 8),
            "1,4;0,2,5,6;1,3,6;2,6,7;0,5,8;1,4,8,9;1,2,3,7;3,6,11;4,5,9,12,13;5,8,10,13;9,11,13,14,15;7,10,15;" +
                "8,13,16,17;8,9,10,12,14,16,17;10,13,15,17,18,19;10,11,14,19;12,13,17,20;12,13,14,16,20,21;" +
                "14,19,22,23;14,15,18,22,23;16,17,21;17,20;18,19,23;18,19,22" to listOf(19, 14, 17, 12),
        )
        for ((links, expected) in maps) {
            val lists = links.split(";").map { row -> row.split(",").map { it.toInt() } }
            val nodes = lists.indices.map { i ->
                val template = sector.beacons[i % sector.beacons.size]
                xyz.znix.xftl.sector.Beacon(template.pos, template.event, false, false, 71)
            }
            nodes.forEachIndexed { i, node -> node.bindSector(sector, lists[i].map { nodes[it] }) }
            val path = boss.nativePath(nodes, nodes[expected.first()], nodes[expected.last()])
            val got = path?.map { nodes.indexOf(it) }
            check(got == expected) { "Boss route $got, native $expected" }
        }
        return "boss route tie-break (2 native maps)"
    }

    /**
     * ShipSystem::SetBonusPower (0x63b180): a Zoltan's bar comes on top of the reactor power while it fits (native
     * RC-energy run: shields at 2 reactor bars of 8 with the Zoltan in the room had bonus 1 and still 2 reactor bars),
     * and only displaces a reactor bar when the system would go over its capacity.
     */
    private fun zoltanBonus(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val shields = ship.shields!!
        val room = shields.room!!
        val other = ship.rooms.first { it != room && it.system == null && it.crew.isEmpty() }
        ship.purchasedReactorPower = 25
        shields.energyLevels = 4
        ship.updateAvailablePower()
        while (shields.powerSelected > 2) shields.decreasePower()
        while (shields.powerSelected < 2) shields.increasePower()
        val reactorBefore = ship.powerAvailable

        val zoltan = ship.addCrewMemberInRoom(
            LivingCrewInfo.generateRandom(state.blueprintManager["energy"] as CrewBlueprint, state), room, false
        )
        room.updateCrewInRoom(); ship.updateAvailablePower()
        check(shields.powerSupplied == 3) { "Zoltan on 2 of 4 bars: supplied ${shields.powerSupplied}" }
        check(ship.powerAvailable == reactorBefore) { "The Zoltan freed reactor power: ${ship.powerAvailable} vs $reactorBefore" }

        zoltan.jumpTo(other, ConstPoint.ZERO); zoltan.nextTargetPos = null
        room.updateCrewInRoom(); other.updateCrewInRoom(); ship.updateAvailablePower()
        check(shields.powerSupplied == 2) { "After the Zoltan left: supplied ${shields.powerSupplied}" }

        // Full system: the bar displaces a reactor bar, which comes back when the Zoltan leaves.
        shields.energyLevels = 2
        ship.updateAvailablePower()
        zoltan.jumpTo(room, ConstPoint.ZERO); zoltan.nextTargetPos = null
        room.updateCrewInRoom(); other.updateCrewInRoom(); ship.updateAvailablePower()
        check(shields.powerSupplied == 2 && ship.powerAvailable == reactorBefore + 1) {
            "Zoltan on a full 2-bar shield: supplied ${shields.powerSupplied}, reactor ${ship.powerAvailable}"
        }
        zoltan.jumpTo(other, ConstPoint.ZERO); zoltan.nextTargetPos = null
        room.updateCrewInRoom(); other.updateCrewInRoom(); ship.updateAvailablePower()
        check(shields.powerSupplied == 2 && ship.powerAvailable == reactorBefore) {
            "After the Zoltan left the full shield: supplied ${shields.powerSupplied}, reactor ${ship.powerAvailable}"
        }
        return "zoltan bonus on top"
    }

    /**
     * Stage 3 flagship: ShipAI::OnInit counts the crew the stage spawned (invasion: 3/4 go, the rest stay), and on the
     * fight's first frame the crew already on the teleporter go at once (0x6257d0 before ShipAI::OnLoop), locking it
     * for 3 units at power 2. Native c5-FS3d: one boarder at the start, the next pair 15 s later.
     */
    private fun flagshipOpeningBoarder(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        boss.stage = 3
        state.currentBeacon = boss.beacon!!
        if (state.enemy == null) check(state.browserSpawnBossHere()) { "Flagship did not spawn" }
        val flagship = state.enemy!!
        state.shipUI.diagnosticDialogue?.selectOption(0)
        check(flagship.crewAI.initialCrewCount == flagship.crew.size) {
            "Flagship AI counts ${flagship.crewAI.initialCrewCount} initial crew of ${flagship.crew.size}"
        }
        val teleporter = flagship.teleporter!!
        // Built at full power (native c5-FS3d: the stage-3 teleporter at 2 of 2 from the first sample).
        for (system in flagship.mainSystems) {
            if (system is xyz.znix.xftl.systems.Weapons || system is xyz.znix.xftl.systems.Drones) continue
            check(system.powerSelected == system.energyLevels || flagship.powerAvailable == 0) {
                "Flagship ${system.codename} built at ${system.powerSelected} of ${system.energyLevels}"
            }
        }
        // The fight's first update (the spawn may already have run it).
        flagship.update(0f)
        val boarders = flagship.crew.filter { it.currentAction == AbstractCrew.Action.TELEPORTING } +
            state.player.crew.filter { (it as? LivingCrew)?.ownerShip === flagship }
        check(boarders.size == 1) { "Opening boarders: ${boarders.size}" }
        check(abs(teleporter.ionTimer - 15f) < 0.01f) { "Teleporter lock after the opening send: ${teleporter.ionTimer}" }
        flagship.update(0f)
        val after = flagship.crew.count { it.currentAction == AbstractCrew.Action.TELEPORTING } +
            state.player.crew.count { (it as? LivingCrew)?.ownerShip === flagship }
        check(after == 1) { "A second decision sent more crew: $after" }
        return "opening boarder 1, lock 15 s"
    }

    /**
     * ShipSystem ctor (0x642370): an AI ship's systems are powered as they are built, before its AI's first frame:
     * each reactor system climbs to its level at that moment while the reactor allows, the weapons and drones stay
     * off, and a level the generator adds afterwards is not powered. Native c5-PW (ZOLTAN_FIGHTER_P during the event
     * text): oxygen 1, shields 2, engines 1 of 2, weapons 0. The opening boarding send then locks the teleporter by
     * that power (InitiateTeleport 0x648570), not by its level.
     */
    private fun enemyCreationPower(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var systems = 0
        var raisedLevel = 0
        for (seed in 0 until 30) {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), xyz.znix.xftl.game.Difficulty.NORMAL, 3, 900 + seed)
            val enemy = state.enemy!!
            for (system in enemy.mainSystems) {
                if (system is xyz.znix.xftl.systems.Weapons || system is xyz.znix.xftl.systems.Drones) {
                    // A Zoltan's bonus bar (forced power) is not reactor power (SetBonusPower 0x63b180).
                    check(system.powerSelected - system.forcedPower <= 0) { "${enemy.name} ${system.codename} built with ${system.powerSelected}" }
                    continue
                }
                val built = minOf(maxOf(1, system.configuration.spec.startingPower), system.energyLevels)
                check(system.powerSelected >= built || enemy.powerAvailable == 0) {
                    "${enemy.name} ${system.codename} built at ${system.powerSelected}, level ${system.energyLevels} (seed $seed)"
                }
                systems++
                if (system !is xyz.znix.xftl.systems.Shields && system.powerSelected < system.energyLevels) raisedLevel++
            }
        }
        check(systems > 60 && raisedLevel > 0) { "Creation power sample $systems/$raisedLevel" }

        // A teleporter built at one bar whose level was raised afterwards: the opening send locks it for 4 units.
        var found: Pair<xyz.znix.xftl.Ship, xyz.znix.xftl.systems.Teleporter>? = null
        for (seed in 0 until 400) {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), xyz.znix.xftl.game.Difficulty.NORMAL, 5, 2000 + seed)
            val tp = state.enemy!!.teleporter ?: continue
            if (tp.powerSelected == 1 && tp.energyLevels >= 2) { found = state.enemy!! to tp; break }
        }
        val (enemy, teleporter) = checkNotNull(found) { "No pirate with a raised one-bar teleporter in 400 seeds" }
        val boarder = enemy.addCrewMemberInRoom(
            LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state), teleporter.room!!, false
        )
        enemy.crewAI.resetTeleportBudget()
        val pads = teleporter.room!!
        val slot = enemy.findSpaceForCrew(pads, AbstractCrew.SlotType.CREW)
        check(slot.room === pads) { "No free pad on the teleporter" }
        boarder.jumpTo(slot)
        boarder.nextTargetPos = null
        pads.updateCrewInRoom(); enemy.updateCrewReservedSlots()
        val state0 = "available ${teleporter.isSendAvailable}, locked ${teleporter.isPowerLocked}, power ${teleporter.powerSelected}, " +
            "room crew ${pads.crew.size}, standing ${boarder.standingPosition != null}, assigned ${pads.isCrewAssigned(boarder)}, " +
            "boarder in ${boarder.room?.id} (pads ${pads.id})"
        val sent = teleporter.sendAtArrival(state.player.rooms.first())
        check(sent == 1) { "Opening send from a one-bar teleporter: sent $sent; $state0" }
        check(abs(teleporter.ionTimer - 20f) < 0.01f) { "Opening send locked ${teleporter.ionTimer} s at power 1" }
        return "built power ($systems systems, $raisedLevel below level), one-bar send locks 20 s"
    }

    /** An ion shot as a drone sees it (SpaceDrone::CollisionMoving 0x420c90 reads the weapon blueprint). */
    private class IonShot(private val weapon: xyz.znix.xftl.weapons.AbstractWeaponBlueprint) : xyz.znix.xftl.weapons.IProjectile {
        override val position = xyz.znix.xftl.math.ConstFPoint(0f, 0f)
        override val velocity = xyz.znix.xftl.math.ConstFPoint(0f, 0f)
        override val antiDroneBP: xyz.znix.xftl.weapons.AbstractWeaponBlueprint get() = weapon
        override val antiDroneExemption: xyz.znix.xftl.Ship? get() = null
        override val serialisationType: String get() = "fake"
        override fun update(dt: Float, currentSpace: xyz.znix.xftl.Ship) {}
        override fun render(g: xyz.znix.xftl.rendering.Graphics, currentSpace: xyz.znix.xftl.Ship) {}
        override fun hitOtherProjectile(currentSpace: xyz.znix.xftl.Ship) {}
        override fun saveToXML(elem: org.jdom2.Element, refs: xyz.znix.xftl.savegame.ObjectRefs) {}
    }

    /**
     * A stunned space drone (SpaceDrone::Ionize 0x427b30: five seconds per ion point, counted down after Drone::OnLoop
     * in SpaceDrone::OnLoop 0x421250) spins and does nothing, and Drone::OnLoop (0x462220) rolls rand%20 < 3 each time
     * its stun passes a whole second from two on: at 2, 3, 4 and 5 s for a one-point ion hit, else it recovers. An
     * explosion is an ordinary destruction (BlowUp, ten second rebuild). An unpowered drone never rolls. Native c5-DDz:
     * 28 stuns of 5 s, 13 exploded at 2.0/3.0/4.0/5.0 s, 15 recovered at 5.0 s.
     */
    private fun droneStunExplosion(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.player.purchasedReactorPower = 25
        state.player.dronesCount = 500
        val drones = state.player.drones!!
        drones.energyLevels = 8
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
        val info = xyz.znix.xftl.systems.Drones.DroneInfo(state.blueprintManager["DEFENSE_1"] as xyz.znix.xftl.weapons.DroneBlueprint)
        drones.drones[0] = info
        val ion = IonShot(state.blueprintManager["ION_1"] as xyz.znix.xftl.weapons.AbstractWeaponBlueprint)
        // A 60 fps frame: like the native speed factor, its sum lands just past each whole second.
        val step = 1f / 60f

        fun launch(): xyz.znix.xftl.drones.AbstractExternalDrone {
            drones.setDronePower(0, false)
            info.instance?.removeInstance()
            info.cooldown = null
            check(drones.setDronePower(0, true)) { "Defence drone did not launch" }
            return info.instance as xyz.znix.xftl.drones.AbstractExternalDrone
        }

        val explodedAt = ArrayList<Float>()
        var recovered = 0
        repeat(60) {
            val drone = launch()
            drone.hitProjectile(ion)
            check(drone.isStunned && !drone.isRunning) { "An ion hit did not stun the drone" }
            var t = 0f
            while (drone.isStunned && !drone.isBlowingUp && t < 7f) {
                drone.update(step)
                t += step
            }
            if (drone.isBlowingUp) explodedAt += t else recovered++
            check(drone.isBlowingUp || abs(t - 5f) < step * 1.5f) { "A five second stun ended after $t s" }
            if (drone.isBlowingUp) {
                var guard = 0
                while (info.instance === drone && guard++ < 200) drone.update(step)
                check(info.instance == null && info.cooldown == xyz.znix.xftl.drones.AbstractDrone.DRONE_DESTROYED_COOLDOWN) {
                    "The stun explosion left instance ${info.instance} cooldown ${info.cooldown}"
                }
            }
        }
        check(explodedAt.all { e -> (2..5).any { k -> e > k - 1e-3f && e <= k + step * 1.5f } } &&
            explodedAt.any { it < 2.5f }) { "Stun explosions at $explodedAt" }
        check(explodedAt.size in 10..50 && recovered in 10..50) { "Stun outcomes: ${explodedAt.size} exploded, $recovered recovered" }

        // Unpowered while stunned: no roll, however long the stun.
        val quiet = launch()
        quiet.hitProjectile(ion)
        quiet.isPowered = false
        repeat((5f / step).toInt() + 4) { quiet.update(step) }
        check(!quiet.isBlowingUp) { "An unpowered stunned drone exploded" }

        // A pod stunned for five seconds rolls at 2, 3, 4 and 5 s too (roll before the timer counts down).
        val pods = IntArray(7)
        repeat(400) {
            val pod = xyz.znix.xftl.drones.PodHealth(state, 0)
            pod.contact(ion, state.player, state.player, xyz.znix.xftl.math.ConstFPoint(0f, 0f), true)
            check(pod.ionTimer == 5f) { "Pod ion stun ${pod.ionTimer}" }
            var t = 0f
            while (!pod.isBlowingUp && t < 6.5f) { pod.update(step, false); t += step }
            if (pod.isBlowingUp) pods[(t + 0.01f).toInt()]++
        }
        check(pods[2] > 0 && pods[5] > 0 && pods[0] + pods[1] + pods[6] == 0) { "Pod stun explosions by second ${pods.toList()}" }
        return "drone stun: ${explodedAt.size}/60 exploded at whole seconds 2-5, $recovered recovered at 5 s"
    }

    /**
     * The planetary ASB (SpaceManager::UpdatePDS 0x41d5e0, PDSFire vtable 0x90cc2c). Every shot of a volley, hit or
     * miss, is a PDSFire that starts at the planet centre in the player's space and leaves half a second after the
     * previous one, the player's first. A miss flies to (-400, rand % 800) and on. A hit on the player homes on a room;
     * one on the enemy leaves on a 135-225 degree heading and enters the enemy's space. The shot grows with the
     * distance flown (scale 1 + 7 * ratio, speed 3 * scale + 20, both measured from the player-space start). At its
     * target it hits with forceHit (no dodge, no shields), then flies on. It never collides. Native c5-ASB-0/1: every
     * shot starts at (640, 307) at 23.1 x16 px/s; hits do 3 hull at 44; the enemy's shot enters 600 px from (200, 200).
     */
    private fun asbVolley(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), xyz.znix.xftl.game.Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        val enemy = state.enemy!!
        for (ship in listOf(player, enemy)) {
            repeat(10) { ship.engines!!.decreasePower() }
            ship.superShield = 5
            ship.projectiles.clear()
        }
        check(player.evasion == 0 && enemy.evasion == 0) { "Evasion ${player.evasion}/${enemy.evasion}" }
        val env = xyz.znix.xftl.environment.AsbEnvironment(state, state.currentBeacon, 2)
        val origin = env.diagnosticOrigin
        val step = 1f / 32f

        // The volley: release times, origins and order, with no game update moving the shots.
        val released = ArrayList<Pair<Float, xyz.znix.xftl.environment.AsbProjectile>>()
        var t = 0f
        while (t < 31f && released.count { it.second.isHit } < 2) {
            env.update(step)
            t += step
            for (p in player.projectiles.filterIsInstance<xyz.znix.xftl.environment.AsbProjectile>()) {
                if (released.none { it.second === p }) released += t to p
            }
        }
        val shots = released.map { it.second }
        check(shots.all { it.position.xf == origin.x.toFloat() && it.position.yf == origin.y.toFloat() }) { "ASB shot origins" }
        check(shots.all { it.startPoint.xf == origin.x.toFloat() && it.startPoint.yf == origin.y.toFloat() }) { "ASB shot starts" }
        check(shots.all { !it.collisionsEnabled && it.antiDroneBP == null }) { "An ASB shot can collide" }
        val hits = released.filter { it.second.isHit }
        check(hits.size == 2 && hits[0].second.room!!.ship === player && hits[1].second.room!!.ship === enemy &&
            abs(hits[1].first - hits[0].first - 0.5f) < step * 1.5f && hits[0].first >= 20f) {
            "Live volley ${hits.map { "%.2f %s".format(it.first, it.second.room!!.ship.isPlayerShip) }}"
        }
        val misses = shots.filter { !it.isHit }
        check(misses.size >= 6 && misses.all { it.aimPoint.xf == -400f && it.aimPoint.yf in 0f..799f && !it.showsMiss }) {
            "ASB misses ${misses.map { it.aimPoint }}"
        }
        val times = released.map { it.first }
        val gaps = times.zipWithNext { a, b -> b - a }
        check(gaps.all { abs(it - 0.5f) < step * 1.5f || it in 2f..5.1f }) { "ASB volley gaps $gaps" }
        player.projectiles.clear()

        // Fly one shot through both spaces, one frame at a time.
        class Flight(val hullAt: ArrayList<Pair<Int, Float>> = ArrayList(), val enteredAt: ArrayList<Float> = ArrayList(),
                     var frames: Int = 0, var framesAfterHit: Int = 0, var firstSpeed: Int = 0)
        fun fly(p: xyz.znix.xftl.environment.AsbProjectile): Flight {
            val f = Flight()
            var hull = listOf(player.health, enemy.health)
            f.firstSpeed = p.speed
            var hit = false
            while (f.frames++ < 2000) {
                val space = listOf(player, enemy).firstOrNull { p in it.projectiles } ?: break
                p.update(step, space)
                if (space === player && p in enemy.projectiles) {
                    f.enteredAt += hypot(p.position.xf - 200f, p.position.yf - 200f)
                }
                val now = listOf(player.health, enemy.health)
                if (now != hull) {
                    f.hullAt += (hull[0] - now[0]) * 100 + (hull[1] - now[1]) to p.scale
                    hull = now
                    hit = true
                } else if (hit) f.framesAfterHit++
            }
            return f
        }

        // A miss: no damage, past (-400, y) and out of the player's space.
        val missShot = xyz.znix.xftl.environment.AsbProjectile.miss(player, origin, true)
        player.projectiles.add(missShot)
        val missFlight = fly(missShot)
        check(missFlight.hullAt.isEmpty() && missShot !in player.projectiles && missShot.showsMiss) { "ASB miss flight" }
        check(missShot.position.xf < -800f || missShot.position.yf > 800f || missShot.position.yf < -800f) { "ASB miss exit ${missShot.position}" }
        check(missFlight.firstSpeed == 368) { "ASB start speed ${missFlight.firstSpeed}" }

        // A hit on the player: shields and the Zoltan shield don't stop it, 3 hull, a breach, then it flies on.
        val playerRoom = player.rooms.first()
        val playerShot = xyz.znix.xftl.environment.AsbProjectile.hit(playerRoom, player, origin)
        player.projectiles.add(playerShot)
        val playerFlight = fly(playerShot)
        check(playerFlight.hullAt.size == 1 && playerFlight.hullAt[0].first == 300 && abs(playerFlight.hullAt[0].second - 8f) < 0.01f) {
            "ASB player hit ${playerFlight.hullAt}"
        }
        check(player.superShield == 5 && playerRoom.breaches.any { it != null }) { "ASB player hit shield/breach" }
        check(playerFlight.framesAfterHit > 3 && playerShot !in player.projectiles) { "ASB shot stopped at its target" }

        // A hit on the enemy: a heading of 135-225 degrees, into the enemy's space 600 px from (200, 200), 3 hull.
        val enemyRoom = enemy.rooms.first()
        val enemyShot = xyz.znix.xftl.environment.AsbProjectile.hit(enemyRoom, player, origin)
        val heading = Math.toDegrees(enemyShot.rotation.toDouble()).let { if (it < 0) it + 360 else it }
        check(heading in 135.0..224.999) { "ASB enemy heading $heading" }
        player.projectiles.add(enemyShot)
        val enemyFlight = fly(enemyShot)
        check(enemyFlight.enteredAt.size == 1 && abs(enemyFlight.enteredAt[0] - 600f) < 1f) { "ASB entry ${enemyFlight.enteredAt}" }
        check(enemyFlight.hullAt.size == 1 && enemyFlight.hullAt[0].first == 3 && abs(enemyFlight.hullAt[0].second - 8f) < 0.01f &&
            enemy.superShield == 5) { "ASB enemy hit ${enemyFlight.hullAt}" }

        // Save and load mid-flight.
        val savedMiss = xyz.znix.xftl.environment.AsbProjectile.miss(player, origin, true)
        val savedHit = xyz.znix.xftl.environment.AsbProjectile.hit(enemy.rooms.last(), player, origin)
        player.projectiles.add(savedMiss)
        player.projectiles.add(savedHit)
        repeat(10) { savedMiss.update(step, player); savedHit.update(step, player) }
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        val loaded = (main.currentState as InGameState).player.projectiles.filterIsInstance<xyz.znix.xftl.environment.AsbProjectile>()
        check(loaded.size == 2) { "ASB shots after load: ${loaded.size}" }
        for ((before, after) in listOf(savedMiss, savedHit).zip(loaded)) {
            check(before.isHit == after.isHit && before.showsMiss == after.showsMiss && before.aimPoint.xf == after.aimPoint.xf &&
                before.aimPoint.yf == after.aimPoint.yf && before.startPoint.xf == after.startPoint.xf &&
                abs(before.position.xf - after.position.xf) < 0.01f && abs(before.scale - after.scale) < 0.001f &&
                (after.room == null) == (before.room == null)) { "ASB shot changed by save/load" }
        }
        check(loaded[1].room?.ship?.isPlayerShip == false) { "ASB enemy shot target after load" }
        return "ASB volley: ${shots.size} shots from (${origin.x}, ${origin.y}), player first, misses to x -400, hits 3 hull at scale 8 through shields, enemy entry 600 px"
    }

    /** Telepathy silhouettes: CrewAnimation::OnRender 0x477f20, enemy outline colour (255, 50, 50) at 0.75 opacity. */
    private fun telepathyColour(): String {
        val c = xyz.znix.xftl.Constants.CREW_TELEPATHY
        check(c.r == 1f && abs(c.g * 255f - 50f) < 0.01f && abs(c.b * 255f - 50f) < 0.01f && c.a == 0.75f) {
            "Telepathy colour ${c.r},${c.g},${c.b},${c.a}"
        }
        return "telepathy red"
    }
}
