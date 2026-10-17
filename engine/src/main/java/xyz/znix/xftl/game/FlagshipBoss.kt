package xyz.znix.xftl.game

import ftl.browser.NativeRandom
import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.Sector
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


class FlagshipBoss private constructor(val sector: Sector, val game: InGameState) : BossManager {
    override val serialisationType: String get() = SERIALISATION_TYPE

    override val useBossShipUI: Boolean get() = true
    override val beacon: Beacon get() = realBeacon
    override var nextBeacon: Beacon? = null
        private set
    override var jumping: Boolean = false
        private set

    // Can't make beacon lateinit since the parent is nullable
    private lateinit var realBeacon: Beacon

    private var runningAway: Boolean = false

    /**
     * StarMap +0x874: the flagship's route to the base, worked out once (GenerateMap's last-stand branch 0x5f8ca0
     * runs Dijkstra 0x5f81d0) and then followed step by step, so a tie between equally short routes never makes it
     * switch half-way. See [nativePath] for the tie-break.
     */
    private var path: List<Beacon>? = null
    var stage: Int = 1
        internal set
    var browserArrivedAtBase = 0
        private set
    private var browserMoves = 0
    val browserBaseTurns: Int? get() = if (browserArrivedAtBase == 0) null else (4 - browserArrivedAtBase).coerceAtLeast(0)
    val browserFleetSelections: Int get() = if (browserMoves > 1 || runningAway) 2 else 1

    private var mapIconRotation: Float = (0f..10f).random(VisualRandom)
    private val flagshipIcon = game.getImg("img/map/map_icon_boss.png")

    /**
     * BossShip +0x150: how many living crew the flagship had in each room (indexed by room id) the last time it was
     * updated. Only these counts carry over between visits and stages - BossShip::StartStage (0x406980) builds a
     * brand new ship every time the player arrives, and fills its rooms with new human crew from the counts. Stage 1
     * restarts from the full crew each arrival, so retreating from it heals everything.
     */
    private var roomCrew = IntArray(0)

    constructor(sector: Sector, game: InGameState, start: Beacon) : this(sector, game) {
        realBeacon = start

        updateNextBeacon()
    }

    override fun createShip(): Ship {
        // BossShip::StartStage (0x406980): every arrival builds a brand new ship. Nothing of the last fight (damage,
        // systems, power) comes back, only the per-room crew counts.
        // The ship name is in the format of BOSS_1_NORMAL_DLC
        var shipName = String.format("BOSS_%d_%s", stage, game.difficulty)
        if (game.content.enableAdvancedEdition) {
            shipName += "_DLC"
        }

        val blueprint: ShipBlueprint = game.blueprintManager.getShip(shipName)
        val flagship = Ship(blueprint, game, null, null, this)
        flagship.loadDefaultContents()
        // Every system is powered to its blueprint level as it is made (ShipSystem ctor 0x642370), before any AI frame.
        for (system in flagship.mainSystems.sortedBy { xyz.znix.xftl.ai.systemOrderKey(it) })
            flagship.browserPowerAtCreation(system)

        // ShipManager::ModifyDroneCount is asked for ten drone parts whenever the count isn't ten already.
        if (flagship.dronesCount != 10) {
            flagship.dronesCount = (flagship.dronesCount + 10).coerceAtLeast(0)
        }

        // The last stage starts with the super shield already up (the shield surge, 0x49cb50).
        if (stage == 3) {
            shieldSurge(flagship)
        }

        // Stage 1 resets the crew table, every other stage uses what the last ship left.
        if (stage == 1 || roomCrew.isEmpty()) {
            roomCrew = IntArray(flagship.rooms.size)
            for (id in INITIAL_CREW_ROOMS) {
                if (id < roomCrew.size)
                    roomCrew[id] = 1
            }
        }

        // The table never grows, but it is cut down to the number of rooms.
        if (roomCrew.size > flagship.rooms.size) {
            roomCrew = roomCrew.copyOf(flagship.rooms.size)
        }

        // Spawn the crew through AddCrewMemberFromString (0x4aea60): human, a random name, the room from the table.
        val human = game.blueprintManager["human"] as CrewBlueprint
        val factory = game.crewFactory
        for ((id, count) in roomCrew.withIndex()) {
            val room = flagship.rooms.firstOrNull { it.id == id } ?: continue
            repeat(count) {
                val name = factory.newName()
                NativeRandom.next31() // the animation timer's start delay (TimerHelper::Start)
                val colours = factory.drawColours(human)
                val info = LivingCrewInfo(game, human, name.name, factory.colourIndex(human, colours, name.male))
                flagship.addCrewMemberInRoom(info, room, false)
            }
        }
        // CompleteShip::SetShip (0x4081a0) runs ShipAI::OnInit after the crew are in: its boarding budget and the
        // crew it keeps home come from this count.
        flagship.crewAI.initialCrewCount = flagship.crew.size

        // BossShip +0x138: the surge timer restarts, and so do the surge counter and the surge drones.
        surgeTimer = 0f
        surgeGoal = drawSurgeGoal()
        surgeCount = 0
        clearSurge()
        surgeDroneTypes.clear()

        val event: Event = game.eventManager["BOSS_TEXT_$stage"].resolve()
        game.shipUI.showEventDialogue(event, ftl.browser.BrowserRandom.nextInt())

        return flagship
    }

    override fun drawMapIcon(g: Graphics, centre: IPoint) {
        val rotationSpeed = TWO_PI / 20f // Go around every 20 seconds
        mapIconRotation += game.renderingDeltaTime * rotationSpeed

        // These offsets are approximate.
        g.pushTransform()
        g.rotate(centre.x.f, centre.y.f, -mapIconRotation)
        flagshipIcon.draw(centre.x - 8, centre.y - 32)
        g.popTransform()
    }

    override fun advanceFleet() {
        if (!jumping) {
            // Jump every second turn, this turn wasn't a jump.
            // (except if the flagship doesn't want to jump, when
            //  it's at the base - this is when the next beacon is null.)
            updateNextBeacon()
            jumping = nextBeacon != null
        } else {
            realBeacon = nextBeacon!!
            browserMoves++
            if (!realBeacon.isBase) ftl.browser.BrowserLastStand.capture(game, realBeacon)
            updateNextBeacon()

            // Don't jump again next turn
            jumping = false

            // If we were running away, we're allowed to go back to the base again.
            runningAway = false
        }
        browserArrivedAtBase = if (realBeacon.isBase && !jumping) (browserArrivedAtBase + 1).coerceAtMost(4) else 0
    }

    override fun drawJumpArc(g: Graphics, from: IPoint, to: IPoint) {
        val width = 10f
        val angle = atan2(from.y.f - to.y, from.x.f - to.x)
        val tangentX = cos(angle + PIf / 2f) * width / 2
        val tangentY = sin(angle + PIf / 2f) * width / 2

        g.drawCustomQuads { renderer ->
            renderer.pushVert(from.x + tangentX, from.y + tangentY, Constants.BEACON_LINE_FLAGSHIP)
            renderer.pushVert(from.x - tangentX, from.y - tangentY, Constants.BEACON_LINE_FLAGSHIP)
            renderer.pushVert(to.x - tangentX, to.y - tangentY, Constants.BEACON_LINE_FLAGSHIP)
            renderer.pushVert(to.x + tangentX, to.y + tangentY, Constants.BEACON_LINE_FLAGSHIP)
        }

        // Draw the animated flagship on top of it.
        // Note these numbers are approximate.
        val period = 1_500_000_000
        val progress: Float = (System.nanoTime() % period).toFloat() / period
        val movement = 20f + progress * 20f
        val alpha = min(1f, 2f - progress * 2f)

        g.pushTransform()
        g.rotate(from.x.f, from.y.f, Math.toDegrees(angle.toDouble()).toFloat() - 90f)
        flagshipIcon.draw(from.x - 32f, from.y - 32f - movement, Colour(1f, 1f, 1f, alpha))
        g.popTransform()
    }

    // BossShip::OnLoop (0x407d40): a surge timer (BossShip +0x138, a TimerHelper looping between 20 and 30 s,
    // restarted by StartStage). Stage 2 surges launch drones, stage 3 fires a heavy-laser barrage, every fourth
    // surge raising the super shield instead.
    private var surgeTimer = 0f
    private var surgeGoal = 0f

    /** BossShip::IncomingFire (0x00406140): the next surge is under 5 s away, past the first stage. */
    val incomingFire: Boolean get() = surgeGoal - surgeTimer < 5f && stage != 1
    private var surgeCount = 0

    /** The seven drones of the stage 2 pool (ShipManager +0x86c), created by the first surge. */
    private val surgeDroneTypes = ArrayList<String>()
    private val surgeDrones = arrayOfNulls<xyz.znix.xftl.drones.CombatDrone>(SURGE_DRONE_POOL)

    /** TimerHelper::Start (0x5cb0b0) with no goal: a random time between the minimum and the maximum. */
    private fun drawSurgeGoal(): Float {
        return (ftl.browser.BrowserRandom.nextInt(SURGE_TIME_MAX - SURGE_TIME_MIN) + SURGE_TIME_MIN) / 1000f
    }

    override fun updateShip(ship: Ship, dt: Float) {
        for (i in surgeDrones.indices) {
            val drone = surgeDrones[i] ?: continue
            if (!game.player.externalDrones.contains(drone)) {
                surgeDrones[i] = null
                continue
            }
            drone.update(dt)
        }

        // BossShip::OnLoop counts the living crew of each room every frame, for the next ship.
        if (roomCrew.isEmpty())
            roomCrew = IntArray(ship.rooms.size)
        for (id in roomCrew.indices) {
            roomCrew[id] = ship.crew.count {
                it is LivingCrew && it.health > 0f && it.ownerShip === ship && it.room.id == id
            }
        }

        // TimerHelper::Done (0x5cb200) fires once the time is over the goal, then loops with a new goal.
        surgeTimer += dt
        if (surgeTimer <= surgeGoal)
            return
        surgeTimer = 0f
        surgeGoal = drawSurgeGoal()

        when (stage) {
            2 -> droneSurge(ship)
            3 -> {
                if (surgeCount % 4 == 3) shieldSurge(ship) else laserSurge(ship)
                surgeCount++
            }
        }
    }

    // 0x4a5b00: the first surge makes the pool of seven drones, each Combat I or Combat Beam; every surge then
    // launches the first 4/6/7 (easy/normal/hard) of them, each leaving after two passes.
    private fun droneSurge(ship: Ship) {
        if (surgeDroneTypes.isEmpty()) {
            repeat(SURGE_DRONE_POOL) {
                surgeDroneTypes += if (ftl.browser.BrowserRandom.nextInt(2) == 1) "COMBAT_1" else "COMBAT_BEAM"
            }
        }
        val count = when (game.difficulty) {
            Difficulty.EASY -> 4
            Difficulty.NORMAL -> 6
            Difficulty.HARD -> 7
        }
        for (i in 0 until count) {
            surgeDrones[i]?.removeInstance()
            val type = game.blueprintManager[surgeDroneTypes[i]] as xyz.znix.xftl.weapons.DroneBlueprint
            val drone = type.makeInstance() as xyz.znix.xftl.drones.CombatDrone
            drone.init(ship)
            drone.isPowered = true
            drone.browserPassesLeft = 2
            surgeDrones[i] = drone
        }
    }

    // 0x4a6a90: seven LASER_HEAVY_1 shots at random rooms.
    private fun laserSurge(ship: Ship) {
        val laser = game.blueprintManager["LASER_HEAVY_1"] as xyz.znix.xftl.weapons.LaserBlueprint
        repeat(7) {
            val target = game.player.rooms.random(ftl.browser.BrowserRandom)
            val shot = laser.LaserProjectile(target)
            val spawnPos = ship.rooms.random(ftl.browser.BrowserRandom).pixelCentre
            shot.setInitialPath(spawnPos, spawnPos + ship.weaponFireDirection * 5000)
            ship.projectiles.add(shot)
        }
        laser.launchSounds?.get()?.play()
    }

    // 0x49cb50 -> Shields::PowerSuperShields (0x631810): a 12-point super shield.
    private fun shieldSurge(ship: Ship) {
        if (ship.shields == null)
            return
        ship.maxSuperShield = 12
        ship.superShield = 12
    }

    private fun clearSurge() {
        for (i in surgeDrones.indices) {
            surgeDrones[i]?.removeInstance()
            surgeDrones[i] = null
        }
    }

    // BossShip::OnLoop (0x407d40) sets the stage the next ship starts at once the flagship is going down; the stage
    // after the last is the victory.
    override fun bossShipKilled(enemy: Ship) {
        clearSurge()
        surgeTimer = 0f

        updateNextBeacon()

        // If we're not at the base, continue on our path there.
        // We have to set the is-jumping flag, otherwise the flagship
        // could wait at the same beacon as the player.
        if (nextBeacon != null) {
            jumping = true
        } else {
            // StarMap::ForceBossJump (0x5eed40): defeated at the base, the route is reversed (0x5eece0), so the
            // flagship retreats one step back along the way it came; the next move reverses it again (0x5f4ed0).
            val route = routeFromHere()
            val here = route.indexOf(beacon)
            nextBeacon = if (here > 0) route[here - 1] else beacon.neighbours.let { it[ftl.browser.BrowserRandom.nextInt(it.size)] }
            runningAway = true
            jumping = true
        }

        if (stage == 3) {
            game.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
        } else {
            stage++

            // The event when you defeat a stage
            val event: Event = game.eventManager["BOSS_ESCAPED"].resolve()
            game.shipUI.showEventDialogue(event, ftl.browser.BrowserRandom.nextInt())
        }
    }

    private fun updateNextBeacon() {
        // If the flagship is running away, don't touch its next beacon.
        // That'll have been set when it was marked as running away.
        if (runningAway) {
            return
        }

        // If the flagship is at the rebel base, it'll stay there.
        if (beacon == sector.finishBeacon) {
            nextBeacon = null
            return
        }

        val route = routeFromHere()
        nextBeacon = route[route.indexOf(beacon) + 1]
    }

    /** The stored route, rebuilt from the current beacon only when the flagship is off it (old saves, debug moves). */
    private fun routeFromHere(): List<Beacon> {
        path?.let { if (beacon in it) return it }
        val route = nativePath(sector.beacons, beacon, sector.finishBeacon)
        require(route != null) { "Flagship has no path to the federation base!" }
        return route.also { path = it }
    }

    /**
     * StarMap Dijkstra 0x5f81d0, start and end included. Its maps are keyed by location pointer but only used for
     * lookups; the order comes from vectors. Each step takes the unvisited location with the lowest distance from
     * the StarMap location list (0x5ef8c0: `<=`, so the last of equals in list order). Its connections are relaxed in
     * their own order and a strictly shorter distance takes the predecessor. The beacons and their neighbours keep
     * the native location and connection order (NativeSectorGenerator), so ties resolve the same way.
     */
    internal fun nativePath(locations: List<Beacon>, start: Beacon, end: Beacon): List<Beacon>? {
        val distance = HashMap<Beacon, Int>()
        val previous = HashMap<Beacon, Beacon>()
        for (b in locations) {
            distance[b] = Int.MAX_VALUE
            previous[b] = start
        }
        distance[start] = 0

        val open = ArrayList(locations)
        while (open.isNotEmpty()) {
            var at = -1
            var best = Int.MAX_VALUE
            for (i in open.indices) {
                val d = distance.getValue(open[i])
                if (d <= best) {
                    best = d
                    at = i
                }
            }
            val current = open.removeAt(at)
            val here = distance.getValue(current)
            if (here == Int.MAX_VALUE)
                return null
            if (current == end)
                break
            for (next in current.neighbours) {
                if (here + 1 < (distance[next] ?: 0)) {
                    distance[next] = here + 1
                    previous[next] = current
                }
            }
        }

        val route = arrayListOf(end)
        var step = end
        while (step != start) {
            step = previous.getValue(step)
            route += step
        }
        route.reverse()
        return route
    }

    /**
     * Debug/testing hook: move this boss to the given beacon. Only touches
     * testing state - the fight itself spawns when the player arrives.
     */
    fun debugTeleportTo(beacon: Beacon) {
        realBeacon = beacon
        browserArrivedAtBase = 0
        updateNextBeacon()
        jumping = false
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrRef(elem, "beacon", refs, beacon)
        SaveUtil.addAttrRef(elem, "next", refs, nextBeacon)
        SaveUtil.addAttrBool(elem, "jumping", jumping)
        SaveUtil.addAttrBool(elem, "runningAway", runningAway)
        SaveUtil.addAttrInt(elem, "stage", stage)
        elem.setAttribute("browserSurgeTimer", surgeTimer.toString())
        elem.setAttribute("browserSurgeGoal", surgeGoal.toString())
        elem.setAttribute("browserSurgeCount", surgeCount.toString())
        elem.setAttribute("browserArrivedAtBase", browserArrivedAtBase.toString())
        elem.setAttribute("browserMoves", browserMoves.toString())
        elem.setAttribute("browserRoomCrew", roomCrew.joinToString(","))
        path?.let { route -> elem.setAttribute("browserPath", route.joinToString(",") { sector.beacons.indexOf(it).toString() }) }
        if (surgeDroneTypes.isNotEmpty())
            elem.setAttribute("browserSurgeDrones", surgeDroneTypes.joinToString(","))
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        SaveUtil.getAttrRef(elem, "beacon", refs, Beacon::class.java) { realBeacon = it!! }
        SaveUtil.getAttrRef(elem, "next", refs, Beacon::class.java) { nextBeacon = it }
        jumping = SaveUtil.getAttrBool(elem, "jumping")
        runningAway = SaveUtil.getAttrBool(elem, "runningAway")
        stage = SaveUtil.getAttrInt(elem, "stage")
        surgeTimer = elem.getAttributeValue("browserSurgeTimer")?.toFloatOrNull() ?: 0f
        surgeGoal = elem.getAttributeValue("browserSurgeGoal")?.toFloatOrNull() ?: drawSurgeGoal()
        surgeCount = elem.getAttributeValue("browserSurgeCount")?.toIntOrNull() ?: 0
        browserArrivedAtBase = elem.getAttributeValue("browserArrivedAtBase")?.toInt()?.coerceIn(0, 4) ?: 0
        browserMoves = elem.getAttributeValue("browserMoves")?.toInt()?.coerceAtLeast(0) ?: 0

        // Saves from before the native per-room crew table carry crew infos and a parked ship instead; those are
        // dropped (the table is rebuilt from the live ship, or from the stage 1 crew).
        roomCrew = elem.getAttributeValue("browserRoomCrew")
            ?.split(",")?.filter { it.isNotEmpty() }?.map { it.toIntOrNull() ?: 0 }?.toIntArray()
            ?: IntArray(0)
        path = elem.getAttributeValue("browserPath")?.split(",")?.mapNotNull { it.toIntOrNull()?.let(sector.beacons::getOrNull) }
            ?.takeIf { it.size >= 1 && it.last() == sector.finishBeacon }
        surgeDroneTypes.clear()
        elem.getAttributeValue("browserSurgeDrones")?.split(",")?.filter { it.isNotEmpty() }?.let { surgeDroneTypes.addAll(it) }
    }

    companion object {
        /** BossShip +0x138: the surge TimerHelper's maximum and minimum, in milliseconds. */
        private const val SURGE_TIME_MAX = 30000
        private const val SURGE_TIME_MIN = 20000

        /** The number of drones in the stage 2 surge pool. */
        private const val SURGE_DRONE_POOL = 7

        /** The rooms StartStage fills with one human each at stage 1 (11 crew in all). */
        private val INITIAL_CREW_ROOMS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 9, 10, 14, 18)

        const val SERIALISATION_TYPE = "wormhole.flagship"

        // Force the deserialiser to use this function rather than making the constructor public,
        // as it makes it very clear that this function isn't supposed to be used for anything ele.
        fun createForDeserialisation(sector: Sector, game: InGameState): FlagshipBoss {
            return FlagshipBoss(sector, game)
        }
    }
}
