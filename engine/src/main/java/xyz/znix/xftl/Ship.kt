package xyz.znix.xftl

import org.jdom2.Element
import xyz.znix.xftl.Constants.*
import xyz.znix.xftl.ai.FriendlyCrewAI
import xyz.znix.xftl.ai.IntruderCrewAI
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.drones.AbstractExternalDrone
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.*
import xyz.znix.xftl.hangar.EditableShip
import xyz.znix.xftl.hangar.FinalisedEditableSystem
import xyz.znix.xftl.layout.*
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.*
import java.util.*
import java.util.stream.Collectors
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class Ship(
    val type: ShipBlueprint,

    val sys: InGameState,

    /**
     * If this ship was customised by the player, this is it.
     *
     * Note this must ABSOLUTELY NOT be modified once the game has started!
     * Serialisation in particular would be completely wrecked if the room
     * indices change.
     *
     * While it would be possible to always create an EditableShip instance,
     * even for unmodified ships, it constraints what simplifications we can
     * do with EditableShip while still being able to properly load all the
     * vanilla ships, with all their details.
     */
    val customised: EditableShip?,

    val spec: EnemyShipSpec?,

    boss: BossManager?,
) :
    ISerialReferencable {

    // Helper methods, providing quick access to important stuff from the blueprint
    val name: String get() = type.name
    /** Player-facing name; blueprint identity remains [name]. */
    var displayName: String? = null
    val maxHealth: Int get() = browserMaxHealth ?: type.maxHealth

    /**
     * A generated enemy's hull: ShipGenerator::CreateShip adds the effective
     * level to the blueprint's (at most 20). Null for every other ship and
     * for saves that predate it.
     */
    var browserMaxHealth: Int? = null
    val isPlayerShip: Boolean get() = type.isPlayerShip
    val isUsingBossUI: Boolean get() = boss?.useBossShipUI ?: false
    val isAutoScout: Boolean get() = type.isAutoScout

    /**
     * Crew-less ships that run as if their systems were manned, repairing
     * damage as they go - vanilla's flagship becomes one when all its crew
     * are killed (the BOSS_AUTOMATED event, BossShip::GetSubEvent 0x4066d0).
     * Unlike [isAutoScout] this isn't from the blueprint - it's a runtime
     * state, so it needs serialising.
     */
    var isAutomated: Boolean = false
    val weaponSlots: Int? get() = customised?.weaponSlots ?: type.weaponSlots
    val droneSlots: Int? get() = customised?.droneSlots ?: type.droneSlots

    /**
     * If this ship is a boss, this points to its manager. Null otherwise.
     */
    var boss: BossManager? = boss
        private set // Mutable only for deserialisation

    val rooms: List<Room>
    val doors: List<Door>

    val offset: ConstPoint = type.roomOffset
    val floorOffset: ConstPoint = type.floorOffset
    val cloakOffset: ConstPoint = type.cloakOffset
    val hullOffset: ConstPoint

    val weaponFireDirection: Direction = when (type.isPlayerShip) {
        true -> Direction.RIGHT // Weapons fly right
        false -> Direction.UP // Weapons fly upwards
    }

    // Played when any two projectiles collide.
    private val projectileCollisionSound: FTLSound = sys.sounds.getSample("hitHull1")
    val breachSound: FTLSound = sys.sounds.getSample("hullBreach")
    val airLossSound: FTLSound = sys.sounds.getSample("airLoss")

    val floorImage: Image? = type.floorImage?.let { sys.getImg(it) }
    /**
     * Null when the blueprint names a hull image the data does not have (LONG_ELITE_MED / LONG_ELITE_HARD, img
     * rebel_long_hard): the original builds and draws those ships without a hull, so nothing is drawn and the
     * hull counts as 0 x 0 wherever its size is used.
     */
    val hullImage: Image? = type.hullImage.firstNotNullOfOrNull { sys.getImgIfExists(it) }
    val hullWidth: Int get() = hullImage?.width ?: 0
    val hullHeight: Int get() = hullImage?.height ?: 0

    /** The centre of the hull image, in ship-local coordinates - useful as
     *  a pivot for rendering effects like the beacon-arrival animation
     *  (issue #4). */
    val hullCentreX: Float
        get() = hullOffset.x + hullWidth / 2f

    /** The vertical centre of the hull image, in ship-local coordinates. */
    val hullCentreY: Float
        get() = hullOffset.y + hullHeight / 2f

    /** The left/right edges of the hull image, in ship-local coordinates:
     *  the beacon-arrival flare sweeps between the two (issue #4). */
    val hullLeftX: Float
        get() = hullOffset.x.toFloat()
    val hullRightX: Float
        get() = hullOffset.x + hullWidth.toFloat()

    /** Extra transparency applied to the whole ship while rendering: 1 is
     *  fully opaque, 0 invisible. Used by the beacon-arrival animation
     *  (issue #4); reset it to 1 when done! */
    var renderAlpha: Float = 1f
    val cloakImage: Image? = type.cloakImage?.let { sys.getImg(it) }
    val gibs: List<ShipGib.Instance>

    val shieldImage: Image = sys.getImg(type.shieldImage)

    val selectedShieldHalfSize: ConstPoint
    val shieldHalfSize: ConstPoint

    val shieldOrigin: ConstPoint

    // All the crew currently standing on this ship. This includes drone pawns.
    val crew: MutableList<AbstractCrew> = ArrayList()

    // The subset of the crew that are or aren't intruders. This also includes drone pawns.
    val intruders: List<AbstractCrew> = ArrayList()
    val friendlyCrew: List<AbstractCrew> = ArrayList()

    val cargoBlueprints = ArrayList<Blueprint?>(listOf(null, null, null, null, null, null))

    /**
     * Equipment +0x3e0 / +0x3e1: set when Equipment::AddWeapon / AddDrone (0x539970 region) or AddAugment
     * (0x53d900) puts something in the item or augment overflow box, cleared only by Equipment::Jump (0x53d290)
     * (and OnInit). While set, the over-capacity box stays on the equipment screen even once emptied.
     * Not saved, as natively.
     */
    val overCapacityBoxShown = BooleanArray(2)

    /**
     * The ship's augment slots. This should only be used for UI relating
     * to the augment objects as seen on the ship screen - in the future,
     * mods will be able to apply blueprint effects to the ship without
     * using an augment slot, and those show up in [augmentValues], so it
     * should be used for mod compatibility whenever possible.
     *
     * One must always call [cargoUpdated] after modifying this, so the
     * changes show up in [augmentValues].
     */
    val augments = ArrayList<AugmentBlueprint>()

    /**
     * This contains the sum of the values for each type of augment. Mods
     * will be able to apply augment effects without a regular augments,
     * and those effects will then be stored here.
     */
    private val privateAugmentValues = HashMap<AugmentBlueprint, Float>()
    val augmentValues: Map<AugmentBlueprint, Float> = Collections.unmodifiableMap(privateAugmentValues)

    val pathFinder: PathFinder

    val hardpoints: List<Hardpoint>

    // This contains all the projectiles, both incoming and outgoing.
    val projectiles: MutableList<IProjectile> = ArrayList()

    val inboundBeams: MutableList<BeamBlueprint.BeamInstance> = ArrayList()
    val animations: MutableList<FloatingAnimation> = ArrayList()

    // Cosmetic laser bolts fired between fighting crewmembers.
    // Transient on purpose - not saved, the longest flight is ~0.3s.
    val crewShots: MutableList<CrewShot> = ArrayList()

    private val damageNumbers = ArrayList<DamageNumber>()

    /**
     * A list of all the drones (friendly or otherwise) that are deployed
     * around this ship.
     */
    val externalDrones = ArrayList<AbstractExternalDrone>()

    /**
     * The AI that controls friendly (non-intruder) crew on this ship.
     *
     * It's present on all ships, including the player ship. This is because
     * it's used for mind-controlled intruders, as well as regular enemies
     * on their ships.
     */
    val crewAI = FriendlyCrewAI(this)

    val intruderAI = IntruderCrewAI(this)

    // This really is a bit horrible - if this is the enemy ship, we store the beam the
    // player is currently aiming at us to highlight the affected rooms.
    private var inboundBeamAim: SelectedTarget.BeamAim? = null

    // The number of fuel, missiles and drones this ship has. The missiles and drones
    // are set during ship loading. Player ships always seem to start with 16 fuel.
    var fuelCount: Int = 16
    var missilesCount: Int = 0

    /**
     * Explosive Replicator procs waiting to be shown as a '0' flash by
     * the player HUD's missile counter (vanilla: a proc pops '0' where a
     * consumed volley pops '-N'). Queued by [AbstractWeaponInstance.fire],
     * drained by PlayerShipUI; purely cosmetic, never saved.
     */
    var replicatorProcPopups: Int = 0
    var dronesCount: Int = 0

    // How much scrap the player has. ShipManager::ResetScrapLevel (0x49bf90, WorldManager::StartGame and
    // ShipManager::Restart) starts a run with 30 on easy, 10 on normal and 0 on hard.
    var scrap: Int = when (sys.difficulty) {
        Difficulty.EASY -> 30
        Difficulty.NORMAL -> 10
        Difficulty.HARD -> 0
    }

    // How far through charging the FTL drive, 1=fully charged.
    // These only apply to the player, enemies use a fixed timer.
    var ftlChargeProgress: Float = 0f

    /**
     * The seed this ship was generated with by [xyz.znix.xftl.shipgen.ShipGenerator],
     * for ships spawned from an [EnemyShipSpec]. Used by the vanilla-format
     * save writer, which can only store the spawn spec rather than the ship
     * itself. Null for hand-authored ships and for ships loaded from saves
     * that predate this field.
     */
    var generationSeed: Int? = null

    /**
     * The seed of the ship event that loaded this ship (LocationEvent +0x444,
     * kept at WorldManager+0x1218), which also seeds its ending events
     * ([xyz.znix.xftl.sector.EventCreator.createShipEnding]). Null when unknown.
     */
    var eventSeed: Int? = null
    val isFtlCharged get() = ftlChargeProgress >= 1f
    val isFtlReady get() = isFtlCharged && engines!!.powerSupplied > 0

    // This is for both the player and enemies
    val canChargeFTL: Boolean
        get() {
            // For the FTL to charge, the engines and piloting must
            // be working, and a pilot must be present.
            val hasPilot = isAutoScout || isAutomated || piloting!!.hasPilot
            return engines!!.powerSupplied > 0 && piloting!!.undamagedEnergy > 0 && hasPilot
                    && !engines!!.isHackActive && !piloting!!.isHackActive
        }

    val maxReactorPower: Int get() = 25

    // The raw amount of reactor power purchased by the player
    var purchasedReactorPower: Int = 5

    // The amount of reactor power available for use by the player, taking ion storms, events and so on into account
    val reactorPower: Int get() = purchasedReactorPower

    // The unused reactor/battery power
    val powerAvailableTypes = HashMap<EnergySource, Int>()

    val powerAvailable: Int get() = powerAvailableTypes.values.sum()

    /**
     * The amount of power that's unusable due to events or the environment (in
     * a plasma storm).
     */
    var blockedReactorPower: Int = 0
        private set

    var health = maxHealth
        set(value) {
            val previous = field
            field = value.coerceAtLeast(0).coerceAtMost(maxHealth)
            sys.browserAchievements.hullChanged(this, previous, field)
        }

    // The amount of health at which this ship will surrender and attempt to escape
    var escapeHealth: Int = 0
    var surrenderHealth: Int = 0

    /**
     * If this ship is attempting to escape, this is the time remaining until it jumps.
     */
    var escapeTimer: Float? = null

    /**
     * ShipAI +0xa9: set once the ship has surrendered. A surrendered ship neither surrenders again nor starts to
     * escape, and recalls its boarders ([ShipAI]).
     */
    var aiSurrendered: Boolean = false

    /** ShipAI +0xf0: the seconds a stalemate has lasted. */
    var aiStalemateTimer: Float = 0f

    /** ShipAI +0xfc: the teleporter send waves completed, the AI stops sending after two. */
    var aiTeleportWaves: Int = 0

    /** ShipAI +0xe0: the number of completely destroyed systems, refreshed by the AI's power management. */
    var aiDestroyedSystems: Int = 0

    /**
     * The maximum number of super-shield levels this ship can have.
     *
     * This is always 5, except for the flagship since it has a super-super-shield.
     */
    var maxSuperShield: Int = 5

    /**
     * The number of points of super-shield this ship currently has.
     */
    var superShield: Int = 0
        set(value) {
            field = value.coerceIn(0..maxSuperShield)
        }

    /**
     * ShipManager +0x4d8: set by ShipManager::JumpLeave and cleared when the reversed jump animation ends.
     * While it is set the ship's systems do not run (ShipManager::OnLoop skips every system OnLoop) and
     * every shot at the ship misses (GetDodged 0x4a00d0). InGameState keeps it in step with the animations.
     */
    var browserJumping: Boolean = false

    /**
     * Returns true if this ship has ran out of health
     */
    val isDead: Boolean get() = health == 0

    /**
     * Ship::DestroyedDone (0x48c880): the ship is dead and its 3 s ExplosionAnimation
     * tracker (Ship constructor 0x48b570) has finished.
     */
    val isGone: Boolean get() = isDead && destructionTime >= DESTRUCTION_TIME

    val isCloakActive: Boolean get() = cloaking?.active ?: false

    // The amount of power currently used by the ship's systems
    val powerConsumed: Int
        get() {
            var used = 0
            for (room in rooms) {
                used += (room.system as? MainSystem ?: continue).powerSupplied
            }
            return used
        }

    /**
     * Set by SlickGame, this is true if the opponent's cloak is active.
     *
     * This prevents the weapons from charging.
     */
    var opponentCloakActive: Boolean = false

    /**
     * This is the list of all the possible systems that can be installed on this ship.
     *
     * It's simply the sum of all the [Room.systemSlots] lists.
     */
    val systemSlots: List<SystemInstallConfiguration>

    var systems: List<AbstractSystem> = emptyList()
        private set

    /**
     * The list of all the [MainSystem]s in the ship, sorted into the order
     * they appear left-to-right in the power UI.
     */
    var mainSystems: List<MainSystem> = emptyList()
        private set

    /**
     * The list of all the [SubSystem]s in the ship, sorted into the order
     * they appear in the subsystem tray.
     */
    var subSystems: List<SubSystem> = emptyList()
        private set

    var weapons: Weapons? = null
        private set

    var drones: Drones? = null
        private set

    var engines: Engines? = null
        private set

    var shields: Shields? = null
        private set

    var medbay: Medbay? = null
        private set

    var clonebay: Clonebay? = null
        private set

    var cloaking: Cloaking? = null
        private set

    var teleporter: Teleporter? = null
        private set

    var hacking: Hacking? = null
        private set

    var mindControl: MindControl? = null
        private set

    var piloting: Piloting? = null
        private set

    var sensors: Sensors? = null
        private set

    var backupBattery: BackupBattery? = null
        private set

    // Can't just call this 'doors' since that's the list of all
    // the doors in the ship.
    var doorsSystem: Doors? = null
        private set

    /**
     * True if the player may open or close this ship's doors.
     *
     * Vanilla blocks all manual door control - clicking individual doors and
     * airlocks, the open/close-all buttons in the doors subsystem, and their
     * hotkeys - whenever the doors system is inoperable: broken, ionised,
     * actively hacked, left without power, or missing entirely. Note that the
     * doors themselves keep whatever state they had; crew, drones and
     * boarders still move through them freely.
     */
    val areDoorsOperable: Boolean
        get() {
            val system = doorsSystem ?: return false
            return !system.broken && !system.isIonised && !system.isHackActive && system.effectivePower > 0
        }

    var oxygen: Oxygen? = null
        private set

    // The flagship has multiple artillery systems.
    var artillery: List<Artillery> = emptyList()
        private set

    // GetDodgeFactor (0x49fe90): engines and piloting only, without the cloak.
    private val engineDodge: Float
        get() = (piloting!!.evasion + engines!!.evasion.f) * piloting!!.evasionMultiplier * engines!!.evasionMultiplier

    // Last frame's jump-ready (+0x7ed) and dodging (+0x7ec) flags. A new run
    // starts with the drive charged and silent; a loaded ship clears them.
    private var ftlReadyLatch = true
    private var dodgeLatch = false

    /**
     * True from a load until the end of the first update. The systems' manned flags (ShipSystem +0x45) are only set
     * by the crew pass (0x4aa670), which ShipManager::OnLoop (0x4abd40) runs after its dodge and FTL checks, so the
     * first loop after a load sees an unmanned cockpit.
     */
    var browserMannedStale = false
        private set

    /**
     * Set while a new run's ship is warmed up: those updates are silent, and
     * the drive counts as ready, so filling it at the start beacon is too.
     */
    var startSoundsMuted = false

    // The ship's evasion, in percent
    val evasion: Int
        get() {
            var evasion: Float = piloting!!.evasion + engines!!.evasion.f
            evasion *= piloting!!.evasionMultiplier
            evasion *= engines!!.evasionMultiplier

            if (isCloakActive)
                evasion += 60

            return evasion.toInt()
        }

    /**
     * From 0-1.
     */
    var averageOxygen: Float = 1f
        private set

    init {
        if (customised != null) {
            val roomIds = customised.rooms.withIndex().associate { Pair(it.value, it.index) }
            rooms = customised.rooms.withIndex().map { (idx, r) -> Room(this, idx, r.x, r.y, r.w, r.h) }

            doors = customised.doors.mapNotNull { door ->
                val firstRoom = door.findNeighbourRoom(customised, null)
                val secondRoom = door.findNeighbourRoom(customised, firstRoom)

                // Skip disconnected doors.
                if (firstRoom == null && secondRoom == null)
                    return@mapNotNull null

                val firstRoomReal = firstRoom?.let { rooms[roomIds.getValue(it)] }
                val secondRoomReal = secondRoom?.let { rooms[roomIds.getValue(it)] }

                Door(ConstPoint(door.x, door.y), firstRoomReal, secondRoomReal, door.isVertical)
            }
        } else {
            rooms = type.rooms.map { Room(this, it.id, it.pos.x, it.pos.y, it.size.x, it.size.y) }
            doors = type.doors.mapNotNull { it ->
                // Some modded ships have doors that don't connect to any rooms.
                // (eg, the circle destroyer from Dino's Hangar)
                // We should probably guess what rooms these connect to and link
                // them up, for now ignore those doors.
                if (it.left == null && it.right == null) {
                    println("[WARN] Ignoring disconnected door at ${it.pos.x},${it.pos.y}.")
                    return@mapNotNull null
                }

                Door(
                    it.pos,
                    it.left?.let { rooms[it.id] },
                    it.right?.let { rooms[it.id] },
                    it.isVertical
                )
            }
        }

        for (room in rooms) {
            val roomDoors =
                doors.stream().filter { d -> d.left == room || d.right == room }.collect(Collectors.toList())
            room.initialise(roomDoors)
        }

        val shieldOffset = type.shieldEllipse.pos.const
        selectedShieldHalfSize = type.shieldEllipse.size.const

        // The player ship uses the exact size of the shield image,
        // while enemies scale it to fit a custom size.
        shieldHalfSize = when {
            isPlayerShip -> ConstPoint(shieldImage.width / 2, shieldImage.height / 2)
            else -> selectedShieldHalfSize
        }

        if (customised != null) {
            var systemIdx = 0
            for ((idx, room) in rooms.withIndex()) {
                val editable = customised.rooms[idx]
                val system = editable.system ?: continue
                val finalised = FinalisedEditableSystem(system, systemIdx++, sys, room)
                room.systemSlots.add(SystemInstallConfiguration(finalised, sys, room))
            }
        } else {
            for (node in type.systems) {
                if (!node.availableByDefault && sys.blueprintManager.getOrNull(node.systemName) == null) continue
                val room = rooms[node.room.id]

                // Load the information about the available system into the room.
                // This will be used when loading the system from a save, spawning
                // a new ship, or buying a system at a store.
                room.systemSlots.add(SystemInstallConfiguration(node, sys, room))
            }
        }

        systemSlots = rooms.flatMap { it.systemSlots }

        // The shield origin is found by drawing a box that contains all
        // the rooms, then taking its centre.
        val minX = offset.x + rooms.minOf { it.x }
        val minY = offset.y + rooms.minOf { it.y }
        val maxX = offset.x + rooms.maxOf { it.x + it.width }
        val maxY = offset.y + rooms.maxOf { it.y + it.height }
        val centreRoomX = (minX + maxX) / 2f
        val centreRoomY = (minY + maxY) / 2f
        val origin = Point(
            (ROOM_SIZE * centreRoomX).toInt(),
            (ROOM_SIZE * centreRoomY).toInt(),
        )
        origin += shieldOffset
        if (!isPlayerShip) {
            // Yes this looks silly, but it's what vanilla does
            origin.y += 110
        }
        shieldOrigin = origin.const

        hullOffset = ConstPoint(minX, minY) * ROOM_SIZE + type.hullOffset

        hardpoints = type.hardpoints.map { Hardpoint(it) }
        gibs = type.gibs.map { it.createInstance(sys) }

        // Set up the pathfinder after the layout is loaded
        pathFinder = PathFinder(this)
    }

    /**
     * Loads the default version of the ship's stuff (system, blueprints, etc) - this
     * is all stuff that the player can upgrade and needs to be saved.
     */
    @JvmOverloads
    fun loadDefaultContents(omitAugment: String? = null) {
        // Load the starting reactor power
        purchasedReactorPower = type.startingReactorPower

        // Load the starting systems
        for (config in systemSlots) {
            if (!config.spec.availableByDefault)
                continue

            config.room.setSystem(config)
        }

        // Load the starting resources
        missilesCount = type.initialMissiles
        dronesCount = type.initialDroneParts

        // Load all the weapon blueprints
        for ((index, name) in type.initialWeapons.withIndex()) {
            // If there's a ship with invalid weapons, vanilla loads the weapon
            // with an empty name.
            // This empty-name weapon isn't actually set in the vanilla XML, but
            // that's fine since only mods have ships with invalid weapon names.
            var weapon = sys.blueprintManager.getOrNull(name)
            if (weapon == null) {
                println("[WARN] Invalid weapon '$name' in ship '${this.name}' initial weapons")
                weapon = sys.blueprintManager[""]
            }
            check(weapon is AbstractWeaponBlueprint)

            hardpoints[index].weapon = weapon.buildInstance(this)
        }

        // Load all the drone blueprints
        for ((idx, name) in type.initialDrones.withIndex()) {
            val drone = sys.blueprintManager.getOrNull(name) as DroneBlueprint?

            // If a ship has an invalid drone, just ignore it.
            if (drone == null) {
                println("[WARN] Invalid drone '$name' in ship '${this.name}' initial drones")
                continue
            }

            // If the ship doesn't have enough drone slots, just give it some more.
            // This is required for the flagship at least, but it makes sense
            // that if there is some fixed list of included drones then there ought
            // to be enough space for them.
            if (drones!!.drones.size <= idx) {
                drones!!.drones.add(null)
            }

            drones!!.drones[idx] = Drones.DroneInfo(drone, null)
        }

        // Load any augments
        for (name in type.initialAugments) {
            // ShipGenerator::CreateShip drops the Zoltan shield from the blueprint before OnInit on easy's first sector.
            if (name == omitAugment)
                continue
            val augment = sys.blueprintManager.getOrNull(name) as AugmentBlueprint?

            // Also ignore invalid blueprints.
            if (augment == null) {
                println("[WARN] Invalid augment '$name' in ship '${this.name}' initial augments")
                continue
            }

            augment.onShipSpawn(this)
            augments.add(augment)
        }

        // If we don't have an oxygen system, the rooms start with no oxygen by default
        if (oxygen == null) {
            for (room in rooms) {
                room.oxygen = 0f
            }
        }

        // Calculate the number of each type of augment
        cargoUpdated()

        // CreateSystems (0x4a96f0) adds the shields through AddSystem, which
        // powers and charges them straight away.
        instantPowerShields()
    }

    /**
     * ShipManager::InstantPowerShields (0x49d710): power the shields up as
     * far as the reactor and the system allow, then charge every bubble at
     * once (Shields::InstantCharge 0x631870). The original does this whenever
     * a shield system is added to a ship, and once more at the end of
     * ShipGenerator::CreateShip, so ships never start with their shields
     * charging.
     */
    /**
     * The player's starting target power (ShipSystem ctor 0x642370): one bar, two for the shields, none for
     * the weapons and drones; capped at the system's level. An enemy keeps the reactor bars it was built with
     * ([browserPowerAtCreation]: the ctor's target is the level at construction, so a level the generator adds
     * later stays unpowered; native c5-PW: ZOLTAN_FIGHTER_P oxygen and engines at 1 of 2).
     */
    fun browserStartingTargetPower(system: MainSystem): Int {
        val target = when (system) {
            is Weapons, is Drones -> 0
            else -> if (!isPlayerShip) max(0, system.powerSelected - system.forcedPower) else if (system is Shields) 2 else 1
        }
        return min(target, system.undamagedEnergy)
    }

    /**
     * The reactor bars each system of a new player ship takes, in creation order (ShipManager::CreateSystems
     * 0x4a96f0: the blueprint's systemList in file order): each climbs one bar at a time towards its
     * starting target while the reactor has power left, and the shields, added through AddSystem, go on
     * to InstantPowerShields (0x49d710): as many whole layers as the reactor still allows.
     */
    fun browserStartingReactorPower(): Map<MainSystem, Int> {
        updateAvailablePower()
        // The whole reactor, including what loadDefaultContents already gave the shields.
        var reactorLeft = (powerAvailableTypes[ReactorEnergySource] ?: 0) +
                mainSystems.sumOf { it.powerSupplied - it.forcedPower - it.sharedPowerInUse }
        val result = LinkedHashMap<MainSystem, Int>()
        for (system in mainSystems.sortedBy { xyz.znix.xftl.ai.systemOrderKey(it) }) {
            val bars = when (system) {
                is Shields -> min(system.undamagedEnergy, reactorLeft) / 2 * 2
                else -> min(browserStartingTargetPower(system), reactorLeft)
            }
            reactorLeft -= bars
            result[system] = bars
        }
        return result
    }

    /**
     * Applies [browserStartingReactorPower] once the crew is aboard. A Zoltan's bonus bar (SetBonusPower
     * 0x63b180) comes on top of the reactor power, up to the system's level. The shields are charged at once.
     */
    fun browserApplyStartingPower(reactor: Map<MainSystem, Int>) {
        // Let the rooms see their new crew, so the Zoltan bars are known (no time passes).
        update(0f)
        updateAvailablePower()
        // Free what the install already took first, then hand out the bars.
        for (pass in 0..1) {
            for ((system, bars) in reactor) {
                val wanted = min(bars + system.forcedPower, system.undamagedEnergy)
                var guard = system.undamagedEnergy + 2
                while (guard-- > 0) {
                    val before = system.powerSelected
                    when {
                        pass == 0 && system.powerSelected > wanted -> system.decreasePower()
                        pass == 1 && system.powerSelected < wanted -> system.increasePower()
                        else -> break
                    }
                    if (system.powerSelected == before)
                        break
                }
            }
        }
        shields?.instantCharge()
        // Settle the power and evasion for the start event, without simulating any time.
        update(0f)
    }

    /**
     * AddSystem (0x4a8370) for a system the player gets during a run: the ShipSystem ctor powers it towards its
     * starting target ([browserStartingTargetPower]) as far as the reactor allows; new shields then go through
     * InstantPowerShields.
     */
    fun browserPowerNewSystem(system: AbstractSystem) {
        if (system !is MainSystem)
            return
        updateAvailablePower()
        val target = browserStartingTargetPower(system)
        var guard = system.undamagedEnergy + 2
        while (system.powerSelected < target && guard-- > 0) {
            val before = system.powerSelected
            system.increasePower()
            if (system.powerSelected == before)
                break
        }
        if (system is Shields)
            instantPowerShields()
    }

    /**
     * The ShipSystem ctor (0x642370) on an AI ship: a reactor system climbs one bar at a time towards its level at
     * construction while the reactor has power left; the weapons and drones start unpowered (the player instead
     * takes [browserStartingTargetPower]). The AI only changes this from its first frame on.
     */
    fun browserPowerAtCreation(system: AbstractSystem) {
        if (system !is MainSystem || system is Weapons || system is Drones)
            return
        updateAvailablePower()
        var guard = system.energyLevels + 2
        while (system.powerSelected < system.energyLevels && guard-- > 0) {
            val before = system.powerSelected
            system.increasePower()
            if (system.powerSelected == before)
                break
        }
    }

    fun instantPowerShields() {
        val system = shields ?: return
        // The native reactor accounting is always current; a freshly generated ship has not run its first update yet.
        updateAvailablePower()
        var guard = system.energyLevels + 2
        while (guard-- > 0) {
            val before = system.powerSelected
            system.increasePower()
            if (system.powerSelected == before)
                break
        }
        system.instantCharge()
    }

    // Ship::OnInit (0x00496720) gives the player's Kestrel and Federation hulls two
    // looping 4-frame, 0.5s thruster animations, started at random points, which
    // Ship::OnRenderBase (0x0048cad0) draws over the hull base.
    private val thrusterOffsets: List<ConstPoint>? = when {
        !type.isPlayerShip -> null
        type.layout in listOf("kestral", "kestral_2", "kestral_3") -> listOf(ConstPoint(60, 39), ConstPoint(60, 305))
        type.layout in listOf("fed_cruiser", "fed_cruiser_2", "fed_cruiser_3") -> listOf(ConstPoint(17, 44), ConstPoint(17, 285))
        else -> null
    }
    // Presentation only: the random start must not draw from the simulation RNG.
    private val thrusterTimes = FloatArray(2) { (Math.random() * 0.5).toFloat() }

    private fun drawThrusters(alpha: Float = renderAlpha) {
        val offsets = thrusterOffsets ?: return
        val sheet = sys.getImg("img/effects/thrusters_on.png")
        for ((i, offset) in offsets.withIndex()) {
            thrusterTimes[i] = (thrusterTimes[i] + sys.renderingDeltaTime) % 0.5f
            val frame = (thrusterTimes[i] / 0.125f).toInt().coerceAtMost(3)
            sheet.getSubImage(frame * 22, 0, 22, 70).draw(
                hullOffset.x + offset.x, hullOffset.y + offset.y, Colour(1f, 1f, 1f, alpha))
        }
    }

    /** Where the jump flash is centred at progress p (Ship::OnRenderJump): see [renderJump]. */
    fun jumpFlareCentre(progress: Float): Pair<Float, Float> =
        if (isPlayerShip) Pair(hullOffset.x + hullWidth * progress, hullCentreY)
        else Pair(hullCentreX, hullOffset.y + hullHeight * (1f - progress))

    /**
     * ShipManager::OnRender (0x004a3c00) while the jump AnimationTracker runs (a ship leaving, or arriving, which
     * runs the tracker backwards): the interior is not drawn at all, nor are the weapon mounts, doors, crew, fires
     * or damage numbers. All that is left is the shield bubble (Shields::OnRenderBaseShield, alpha 1 - fade) and
     * Ship::OnRenderJump (0x0048cad0): the hull image at alpha 1 - fade, the player's thruster flames at
     * 1 - p, and the flash (the glowing star, full alpha) at p = fade / 0.75, over the middle line of the hull
     * from stern to bow for the player, or from the bottom of the hull image up for a ship in the target box.
     * Shots in flight and external drones belong to SpaceManager, so they keep drawing as ever.
     */
    fun renderJump(g: Graphics, fade: Float) {
        val alpha = 1f - fade
        val previousAlpha = renderAlpha
        renderAlpha = alpha

        for (proj in projectiles) {
            if (proj.drawUnderShip) {
                proj.render(g, this)
            }
        }

        if (!isDead) {
            renderShields()
            renderShieldHits(g)
        }

        hullImage?.draw(hullOffset.x, hullOffset.y, Colour(1f, 1f, 1f, alpha))

        val progress = InGameState.browserJumpFlareProgress(fade)
        drawThrusters(1f - progress.coerceAtMost(1f))

        val starSize = InGameState.browserJumpFlareSize(progress)
        if (starSize > 0f) {
            val (x, y) = jumpFlareCentre(progress)
            sys.jumpFlare.draw(x - starSize / 2f, y - starSize / 2f, starSize, starSize)
        }

        renderAlpha = previousAlpha

        for (proj in projectiles) {
            if (!proj.drawUnderShip) {
                proj.render(g, this)
            }
        }
        for (beam in inboundBeams) {
            beam.renderInbound(g)
        }
        for (drone in externalDrones) {
            drone.renderExternal(g)
        }
    }

    fun render(g: Graphics, interiorVisible: Boolean, selected: Room?) {
        // Draw departing shots, under the shield bubble as the original layers them (a missile leaving the
        // player's ship is tinted by the bubble). We'll draw the rest of them later.
        for (proj in projectiles) {
            if (proj.drawUnderShip) {
                proj.render(g, this)
            }
        }

        // Draw the shield and the flashes of the shots it stopped; a destroyed ship has none.
        if (!isDead) {
            renderShields()
            renderShieldHits(g)
        }

        for (system in systems)
            system.drawBackground(g)

        // If the ship is exploding, draw no further. The player's wreckage stays
        // on screen behind the game over window, as natively.
        if (isDead) {
            if (isGone && !isPlayerShip) {
                return
            }
            renderDestruction(g)
        } else {
            // Animate between cloaked and uncloaked
            val cloakFade = cloaking?.cloakFade ?: 0f

            val interiorAlpha = (1f - cloakFade / 2f) * renderAlpha
            val hullAlpha = when (cloakFade) {
                0f -> renderAlpha
                else -> interiorAlpha * 0.75f
            }
            hullImage?.draw(hullOffset.x, hullOffset.y, Colour(1f, 1f, 1f, hullAlpha))
            drawThrusters()

            if (cloakFade != 0f) {
                requireNotNull(cloakImage) { "Ship '$name' cloaked, but it doesn't have a cloak image!" }

                cloakImage.draw(
                    hullOffset.x + cloakOffset.x, hullOffset.y + cloakOffset.y,
                    Colour(1f, 1f, 1f, cloakFade)
                )
            }

            if (interiorVisible) {
                drawInterior(g, selected, interiorAlpha)
            }
        }

        // Draw the debug hardpoint visuals
        if (sys.debugFlags.showHardpoints.set) {
            for ((index, hp) in hardpoints.withIndex()) {
                val pos = hp.spec.position
                g.colour = Colour.red
                g.drawLine(pos.x - 5f, pos.y - 5f, pos.x + 5f, pos.y + 5f)
                g.drawLine(pos.x + 5f, pos.y - 5f, pos.x - 5f, pos.y + 5f)

                sys.getFont("JustinFont8").drawString(
                    pos.x.f + 5f,
                    pos.y + 8f,
                    index.toString(), Colour.red
                )
            }
        }

        // Draw the projectiles, except for those we already
        // drew underneath the ship earlier.
        for (proj in projectiles) {
            if (!proj.drawUnderShip) {
                proj.render(g, this)
            }
        }

        for (beam in inboundBeams) {
            beam.renderInbound(g)
        }

        // Draw any drones flying around the ship
        for (drone in externalDrones) {
            drone.renderExternal(g)
        }

        // Draw the floating animations (eg, from projectile explosions)
        for (a in animations)
            a.render(g)

        animations.removeIf { a -> a.isFinished }

        // Draw the damage numbers that appear when your ship is hit
        for (num in damageNumbers) {
            num.draw()
        }
    }

    /** A hit on the shield bubble (Shields' ShieldAnimation list, +0x204): a white flash along the bubble. */
    private class ShieldHit(var x: Float, var y: Float, val owner: Any?) {
        var size = 0f
        var thickness = 0f
    }

    private val shieldHits = ArrayList<ShieldHit>()

    /**
     * Shields::CollisionReal (0x006336f0): a shot reaching a raised shield or super-shield starts a flash at
     * [pos]. A beam ([owner]) keeps its own one: each further hit moves it and holds it near the end of its life.
     */
    fun addShieldHit(pos: IPoint, owner: Any? = null) {
        if (owner != null) {
            val existing = shieldHits.firstOrNull { it.owner === owner }
            if (existing != null) {
                existing.x = pos.x.f
                existing.y = pos.y.f
                existing.size = SHIELD_HIT_SIZE - 16f * sys.renderingDeltaTime * 2f * SHIELD_HIT_SPEED
                return
            }
        }
        if ((shields?.activeShields ?: 0) <= 0 && superShield <= 0)
            return
        shieldHits += ShieldHit(pos.x.f, pos.y.f, owner)
    }

    /**
     * Shields' loop (0x006342b0): each flash grows 10 per 1/16 s to 40, then ends; its band thickens 6 times as
     * fast, up to 75 px.
     */
    private fun updateShieldHits(dt: Float) {
        val sf = dt * 16f
        shieldHits.removeAll { hit ->
            if (hit.size >= SHIELD_HIT_SIZE)
                return@removeAll true
            hit.size += sf * SHIELD_HIT_SPEED
            hit.thickness = min(hit.thickness + sf * 6f * SHIELD_HIT_SPEED, 75f)
            false
        }
    }

    /**
     * Shields::RenderBase (0x00631cb0) after the bubble: for each flash, CSurface::GL_DrawShield (0x005b8fd0)
     * draws 40 degrees either side of the hit's angle around the shield ellipse, one step per degree, from the
     * ellipse (white, alpha rising then falling as f^2 over the arc) in to the ellipse less the band thickness
     * (transparent).
     */
    private fun renderShieldHits(g: Graphics) {
        if (shieldHits.isEmpty())
            return
        val a = shieldHalfSize.x.f
        val ratio = shieldHalfSize.y.f / a
        val cx = shieldOrigin.x.f
        val cy = shieldOrigin.y.f
        g.drawCustomQuads { quads ->
            for (hit in shieldHits) {
                val dx = hit.x - cx
                val dy = hit.y - cy
                var angle = Math.toDegrees(acos(min(dx / a, 1f).toDouble())).toFloat()
                if (dy < 0f) angle = -angle
                val start = (angle - SHIELD_HIT_SIZE).toInt()
                val end = (angle + SHIELD_HIT_SIZE).toInt()
                if (end <= start) continue
                val inner = a - hit.thickness
                var step = 2f / (end - start)
                var f = 0f
                var prevOuterX = 0f; var prevOuterY = 0f; var prevInnerX = 0f; var prevInnerY = 0f; var prevAlpha = 0f
                for (deg in start until end) {
                    val rad = Math.toRadians(deg.toDouble())
                    val outerX = cos(rad).toFloat() * a + cx
                    val outerY = sin(rad).toFloat() * a * ratio + cy
                    val alpha = f * f * renderAlpha
                    val radIn = rad + 0.001
                    val innerX = cos(radIn).toFloat() * inner + cx
                    val innerY = sin(radIn).toFloat() * ratio * inner + cy
                    if (deg > start) {
                        quads.pushVert(prevOuterX, prevOuterY, 1f, 1f, 1f, prevAlpha)
                        quads.pushVert(outerX, outerY, 1f, 1f, 1f, alpha)
                        quads.pushVert(innerX, innerY, 1f, 1f, 1f, 0f)
                        quads.pushVert(prevInnerX, prevInnerY, 1f, 1f, 1f, 0f)
                    }
                    prevOuterX = outerX; prevOuterY = outerY; prevInnerX = innerX; prevInnerY = innerY; prevAlpha = alpha
                    f += step
                    if (f >= 1f) step = -step
                }
            }
        }
    }

    private fun renderShields() {
        val level = shields?.activeShields ?: 0

        // Draw the standard (non-Zoltan) shield
        val shieldMax = if (isPlayerShip) 3.5f else 4f // This is odd, but correct
        val levelFraction = level / shieldMax
        val alpha = SHIELD_OPACITY_BASE + SHIELD_OPACITY_SCALING * levelFraction

        if (level == 0) {
            // Do nothing, shields disabled
        } else {
            renderSingleShield(alpha, Colour.white)
        }

        // Draw the super shields
        if (superShield != 0) {
            var superShieldAlpha = superShield.f / maxSuperShield

            if (level == 0) {
                superShieldAlpha = SHIELD_OPACITY_BASE + SHIELD_OPACITY_SCALING * superShieldAlpha
            }

            renderSingleShield(superShieldAlpha, SYS_ENERGY_ACTIVE)

            // TODO fix the ugly darkened line around the edge of enemy super-shields
            // I suspect this is caused by the scaling - Slick has had problems
            // rendering drones while rotating with white aliasing artefacts
            // along their edges, so maybe it's the same thing?
            // Or it could possible be some kind of SRGB-related issue.
        }
    }

    private fun renderSingleShield(alpha: Float, filter: Colour) {
        val shieldAlpha = alpha * renderAlpha
        val basePosX = shieldOrigin.x - shieldHalfSize.x
        val basePosY = shieldOrigin.y - shieldHalfSize.y

        // Draw the image scaled to fit the
        shieldImage.draw(
            basePosX.f, basePosY.f,
            basePosX.f + shieldHalfSize.x * 2, basePosY.f + shieldHalfSize.y * 2,
            0f, 0f,
            shieldImage.width.f, shieldImage.height.f,
            shieldAlpha, filter
        )
    }

    /**
     * Render the crosshairs, beam marker, etc that appear when a weapon
     * is targeted to a specific room. This is in Ship rather than the
     * hostile ship UI, as the player can teleport bombs to their own ship
     * and for debug purposes we may want to show where the AI is targeting
     * their shots.
     */
    fun renderTargeting(g: Graphics, targets: Weapons.TargetList) {
        for (target in targets) {
            if (target.targetShip != this)
                continue

            if (target is SelectedTarget.BeamAim)
                renderTargetingBeam(g, target, target.isAutofire)
            if (target !is SelectedTarget.RoomAim)
                continue

            val room = target.room

            // WeaponControl::RenderAiming (0x56b530) picks the yellow set of
            // crosshairs for a weapon that will fire again by itself.
            val weaponNumber = min(target.weaponNumber + 1, 4)
            val suffix = if (target.isAutofire) "_yellow" else ""
            val img = sys.getImg("img/misc/crosshairs_placed${weaponNumber}${suffix}.png")

            val pos = Point(room.pixelCentre)

            pos.x -= img.width / 2
            pos.y -= img.height / 2

            img.draw(pos)
        }

        if (targets.beamAiming?.targetShip == this) {
            inboundBeamAim = targets.beamAiming
            // CombatControl::RenderTarget (0x578950) draws the beam being aimed
            // in yellow when the ship's autofire differs from the held modifier.
            targets.beamAiming?.let { renderTargetingBeam(g, it, targets.aimingAutofire) }
        } else {
            inboundBeamAim = null
        }
    }

    fun renderTargetingBeam(g: Graphics, beam: SelectedTarget.BeamAim, autofire: Boolean = false) {
        // This is false for beams the player is currently aiming, where their
        // mouse is too close to the start of the beam.
        if (!beam.visible)
            return

        // FTL has a nice 'cap' image that squares off the ends of a beam. However
        // this seemed to cause flickering at the transition between the cap and centre
        // image when rotated, so don't bother with it. Notably the game itself
        // doesn't seem to use this asset.
        // TODO use the cap without a flicker, it really does look nicer.
        val img = sys.getImg(if (autofire) "img/misc/beam_center_yellow.png" else "img/misc/beam_center.png")

        g.pushTransform()
        g.translate(beam.startShipPoint.x.f, beam.startShipPoint.y.f)
        g.rotate(0f, 0f, Math.toDegrees(beam.angle.toDouble()).toFloat())

        // Draw the stretched-out middle image
        val length = (beam.weapon.type as BeamBlueprint).length
        img.draw(
            0f, -img.height / 2f, length.f, img.height.f / 2f,
            // Stretch out the centre of the beam - if we don't do this, then
            // the ends appear faded (windows and nvidia, if that's important).
            0.5f, 0f, 0.5f, img.height.f
        )

        g.popTransform()
    }

    /**
     * ShipManager +0x7ee (ShipManager::OnLoop 0x4aa9d1): does this ship carry a crewmember on the player's side
     * or one under mind control (CrewMember vtable +0x9c)? While a ship is cloaked, CombatControl::SelectRoom
     * (0x57eba0) only lets the player pick one of its rooms when this holds.
     */
    val hasPlayerPresence: Boolean
        get() = crew.any { it.belongsToPlayer == true || (it as? LivingCrew)?.mindControlledBy != null }

    /**
     * Can the player see (and mind-control-target) the living crew on this
     * ship through the hull, even without room vision? True via a Slug's
     * telepathy - a living player-owned Slug aboard - or, on enemy ships,
     * the Lifeform Scanner augment (both are tested together by the native
     * crew render pass, ShipManager::OnRender 0x4a3c00).
     */
    val crewVisibleThroughHull: Boolean
        get() {
            // A living player-owned Slug reveals the crew of whichever ship
            // it is aboard - including one of your Slugs boarding the enemy
            // ship.
            // Ship.crew lists only the crew physically standing aboard, so
            // boarding Slugs are found in the enemy ship's list.
            if (sys.player?.crew?.any { it is CrewSlug && it.ownerShip === sys.player } == true)
                return true
            if (sys.enemy?.crew?.any { it is CrewSlug && it.ownerShip === sys.player } == true)
                return true

            // The scanner only senses lifeforms aboard enemy vessels.
            return !isPlayerShip && sys.player?.hasAugment(AugmentBlueprint.LIFE_SCANNER) == true
        }

    private fun drawInterior(g: Graphics, selected: Room?, alpha: Float) {
        floorImage?.draw(
            floorOffset.x + hullOffset.x,
            floorOffset.y + hullOffset.y,
            Colour(1f, 1f, 1f, alpha)
        )

        // Draw the rooms
        for (room in rooms) {
            var roomSelected = selected == room

            // If the user is currently aiming a beam, highlight
            // the rooms it would hit.
            if (inboundBeamAim?.hitRooms?.contains(room) == true) {
                roomSelected = true
            }

            room.render(g, roomSelected, alpha)
        }

        // Draw the doors
        for (door in doors) {
            door.render(g, alpha)
        }

        // Draw the crew
        // Slug telepathy: while a living player-owned Slug is aboard, enemy
        // crew show up as red-tinted silhouettes even in rooms the player
        // has no vision of - both aboard enemy ships (sensed through the
        // hull) and as intruders on the player's own ship when the sensors
        // are absent or disabled. Full vision of the Slug's room and the
        // rooms connected to it is granted in Room.update.
        val telepathy = crewVisibleThroughHull
        for (crew in crew) {
            // If the crew provides vision, that works if playerHasVision
            // isn't set. This prevents flickering as the crew walks between
            // rooms without sensors.
            if (crew.room.playerHasVision || crew.providesPlayerVision) {
                val scale = sys.shipUI.crewEnlargeScale(crew)
                if (scale == 1f)
                    crew.draw(g)
                else
                    crew.drawScaledBody(g, scale)
            } else if (telepathy && crew is LivingCrew) {
                crew.drawTelepathy(g)
            }
        }

        // Draw laser bolts from fighting crew over the crew, under health bars.
        // Same visibility rule as the crew themselves.
        for (shot in crewShots) {
            if (shot.room.playerHasVision) {
                shot.render(g)
            }
        }

        // Draw the health bars on top of all the other crew, so two
        // fighting crewmembers can't block one of their health bars.
        for (crew in crew) {
            if (crew.room.playerHasVision || crew.providesPlayerVision) {
                val scale = sys.shipUI.crewEnlargeScale(crew)
                if (scale == 1f)
                    crew.drawForeground(g)
                else
                    crew.drawScaledForeground(g, scale)
            }
        }

        // Draw the system foregrounds
        for (system in systems)
            system.drawForeground(g)
    }

    fun screenPosToShipPos(point: Point) {
        point.x = Math.floorDiv(point.x, ROOM_SIZE)
        point.y = Math.floorDiv(point.y, ROOM_SIZE)
        point.sub(offset.x, offset.y)
    }

    fun shipToRoomPos(pos: IPoint): RoomPoint? {
        if (pos is RoomPoint)
            return pos

        for (r in rooms) {
            if (r.containsAbsolute(pos))
                return RoomPoint(r, pos - r.position)
        }

        return null
    }

    // The ExplosionAnimation (Ship::OnLoop 0x493f70): time since the ship died,
    // the countdowns to the next small explosion sound and animation, whether
    // the hull has blown apart, and the explosion animations on the wreck.
    private var destructionTime = 0f
    private var destructionSoundTimer = 0f
    private var destructionExplosionTimer = 0f
    private var destructionSoundPlayed = false
    private val destructionExplosions = ArrayList<FloatingAnimation>()

    /** For the parity checks: the explosions on the wreck and whether the hull has blown apart. */
    val diagnosticDestruction: Pair<Int, Boolean> get() = Pair(destructionExplosions.size, destructionSoundPlayed)

    /**
     * Ship::OnLoop (0x493f70). For the first 1.5 s a random explosion1-4 plays at a
     * quarter volume every 0.3, 0.6 or 0.9 s (the first at once), and every 0 to 0.45 s
     * (while fewer than 25 are playing) an explosion1, an explosion1 over an
     * explosion_random, or another explosion1 starts with its top left corner at a
     * random point of the hull image rect (x up to 32 px short of the right edge).
     * After 1.5 s the hull blows apart: 2 or 3 double size explosion_random, each at
     * least 64 px from the previous one, and the final explosion1 at full volume;
     * from then on the gibs drift. The tracker stops at 3 s.
     */
    private fun updateDestruction(dt: Float) {
        if (destructionTime >= DESTRUCTION_TIME)
            return
        destructionTime = min(destructionTime + dt, DESTRUCTION_TIME)

        if (destructionTime >= DESTRUCTION_SOUND_TIME) {
            if (destructionTime > DESTRUCTION_SOUND_TIME) {
                if (!destructionSoundPlayed) {
                    blowApart()
                    destructionSoundPlayed = true
                    sys.sounds.getSampleOrWarn("explosion1")?.play()
                } else {
                    for (gib in gibs)
                        gib.update(dt)
                }
            }
        } else {
            updateDestructionSounds(dt)

            if (destructionExplosionTimer < 0f) {
                if (destructionExplosions.size < MAX_DESTRUCTION_EXPLOSIONS) {
                    val kind = VisualRandom.nextInt(3)
                    val y = VisualRandom.nextInt(hullHeight.coerceAtLeast(1))
                    val x = VisualRandom.nextInt((hullWidth - 32).coerceAtLeast(1))
                    val pos = ConstPoint(hullOffset.x + x, hullOffset.y + y)
                    if (kind == 0)
                        destructionExplosions += FloatingAnimation(sys, randomBigExplosion(), pos)
                    destructionExplosions += FloatingAnimation(sys, sys.animations["explosion1"], pos)
                }
                destructionExplosionTimer = VisualRandom.nextInt(10) * 0.05f
            }
            destructionExplosionTimer -= dt
        }

        for (explosion in destructionExplosions)
            explosion.update(dt)
        destructionExplosions.removeIf { it.isFinished }
    }

    private fun blowApart() {
        var previous: ConstPoint? = null
        repeat(VisualRandom.nextInt(2) + 2) {
            var pos: ConstPoint
            do {
                val y = VisualRandom.nextInt(hullHeight.coerceAtLeast(1))
                val x = VisualRandom.nextInt((hullWidth - 128).coerceAtLeast(1))
                pos = ConstPoint(hullOffset.x + x, hullOffset.y + y)
            } while (previous != null && pos.distTo(previous!!) < 64)
            previous = pos
            destructionExplosions += FloatingAnimation(sys, randomBigExplosion(), pos, 2f)
        }
    }

    // AnimationControl resolves explosion_random to one of the three big explosions.
    private fun randomBigExplosion(): AnimationSpec =
        sys.animations["explosion_big${VisualRandom.nextInt(3) + 2}"]

    /**
     * ExplosionAnimation::OnRender (0x488e80): the gibs (last first), the whole hull
     * on top until it blows apart, then the explosions until the tracker reaches 3 s.
     */
    private fun renderDestruction(g: Graphics) {
        for (gib in gibs.asReversed()) {
            gib.draw(g, hullOffset, type.hullOffset)
        }
        if (!destructionSoundPlayed)
            hullImage?.draw(hullOffset.x, hullOffset.y)
        if (destructionTime < DESTRUCTION_TIME) {
            for (explosion in destructionExplosions)
                explosion.render(g)
        }
    }

    private fun updateDestructionSounds(dt: Float) {
        if (destructionSoundTimer >= 0f) {
            destructionSoundTimer -= dt
            if (destructionSoundTimer >= 0f)
                return
        }
        sys.sounds.getSampleOrWarn("explosion${ftl.browser.BrowserRandom.nextInt(4) + 1}")?.play()?.volume = 0.25f
        destructionSoundTimer = (ftl.browser.BrowserRandom.nextInt(3) + 1) * 0.3f
    }

    fun update(dt: Float) {
        // Update the power, in case a Zoltan has moved between rooms.
        updateAvailablePower()

        updateShieldHits(dt)

        updateExterior(dt)

        if (isDead) {
            updateDestruction(dt)
            return
        }

        for (room in rooms)
            room.update(dt)

        // Update the door open/close animations
        for (door in doors) {
            door.update(dt)
        }

        // Move oxygen around the ship
        OxygenTransfer.update(this, dt)

        averageOxygen = rooms.map { it.oxygen }.average().toFloat()

        crewAI.update()
        intruderAI.update()

        checkSkills()

        updateCrew(dt)

        // This FTL charge logic is for the player; enemies have a completely
        // separate charge time stored in ShipAI.
        if (canChargeFTL && !browserJumping) {
            // WorldManager boosts the charge (SetBoostFTL) in a hostile
            // environment with no enemy ship; without danger the drive is
            // simply full (InGameState).
            val boost = sys.getEnemyOf(this) == null
            val rate = engines?.ftlChargePerUnit(boost) ?: 0f
            ftlChargeProgress += rate * 16f * dt / Engines.FTL_CHARGE_MAX
        }
        if (ftlChargeProgress > 1f) {
            ftlChargeProgress = 1f
        }
        // ShipManager::OnLoop (0x4abd40): jumpReady rings when the drive is
        // charged and was not last frame (also the first frame after a load);
        // enginesOff when the dodge factor of engines and piloting drops to 0.
        // There is no engines-on sound.
        if (isPlayerShip && !startSoundsMuted) {
            if (isFtlCharged && !ftlReadyLatch)
                sys.sounds.getSampleOrWarn("jumpReady")?.play()
            val dodging = engineDodge > 0
            if (dodgeLatch && !dodging)
                sys.sounds.getSampleOrWarn("enginesOff")?.play()
            dodgeLatch = dodging
            ftlReadyLatch = isFtlCharged
        } else if (startSoundsMuted) {
            dodgeLatch = engineDodge > 0
        } else {
            ftlReadyLatch = isFtlCharged
        }
        browserMannedStale = false

        for ((augment, totalValue) in augmentValues) {
            augment.update(this, dt, totalValue)
        }
    }

    private fun updateCrew(dt: Float) {
        // Duplicate the crew and drone lists, in case some are removed
        // by dying or (in the case of drones) the enemy ship blowing up.
        for (crew in crew.toTypedArray())
            crew.update(dt)

        // Update the list of intruders and friendly (non-intruder) crewmembers
        require(intruders is ArrayList)
        require(friendlyCrew is ArrayList)
        intruders.clear()
        friendlyCrew.clear()
        for (crew in crew) {
            when (crew.mode) {
                AbstractCrew.SlotType.CREW -> friendlyCrew.add(crew)
                AbstractCrew.SlotType.INTRUDER -> intruders.add(crew)
            }
        }
    }

    private fun updateExterior(dt: Float) {
        // Walk backwards, since projectiles may remove themselves
        for (i in projectiles.size - 1 downTo 0) {
            projectiles[i].update(dt, this)
        }

        // Check for collisions between any two projectiles. To avoid
        // checking a pair twice (but in a swapped order), only check
        // a projectile against those that come after it in the list.
        // By iterating down, we can again ignore the problem of changing
        // indices when projectiles are removed.
        for (lowerI in projectiles.size - 1 downTo 0) {
            val first = projectiles[lowerI]
            if (!first.collisionsEnabled)
                continue

            for (upperI in projectiles.size - 1 downTo lowerI + 1) {
                val second = projectiles[upperI]

                if (!second.collisionsEnabled)
                    continue

                // Make sure the projectiles are targeting different ships - otherwise
                // you could shoot down your own projectiles, and in particular flak
                // projectiles would collide with each other.
                if (first !is AbstractProjectile || second !is AbstractProjectile) {
                    continue
                }

                // A projectile that's not fired at a ship is assumed to be friendly,
                // like a defence drone laser.
                val isFirstFriendly = first.targetShip != this
                val isSecondFriendly = second.targetShip != this
                if (isFirstFriendly == isSecondFriendly) {
                    continue
                }

                // Run a CCD check to see if the projectiles have collided
                // at any point between the last frame and this frame.
                if (!IProjectile.checkCollision(
                        dt,
                        first.position, second.position,
                        first.velocity, second.velocity,
                        first.hitboxRadius.f, second.hitboxRadius.f
                    )
                ) {
                    continue
                }

                // Drone pods in flight apply the drone collision rules to
                // the shots that touch them (dodging, ion stun, exploding).
                val firstContact = first.onProjectileContact(second)
                val secondContact = second.onProjectileContact(first)
                if (firstContact == ProjectileContact.IGNORED || secondContact == ProjectileContact.IGNORED)
                    continue

                if (firstContact == ProjectileContact.DESTROYED)
                    first.hitOtherProjectile(this)
                if (secondContact == ProjectileContact.DESTROYED)
                    second.hitOtherProjectile(this)

                // Play a single hit sound. It seems that FTL doesn't
                // play this missile explode sound when a drone shoots
                // down a missile, so always using one is probably
                // close to correct.
                // It also means we don't play two sounds for the same
                // thing, which would be the case if hitOtherProjectile
                // played a sound effect.
                projectileCollisionSound.play()

                if (secondContact == ProjectileContact.DESTROYED)
                    projectiles.removeAt(upperI)
                if (firstContact == ProjectileContact.DESTROYED) {
                    projectiles.removeAt(lowerI)

                    // Don't process any more projectiles in relation to the
                    // one from the outer loop, as we removed it.
                    break
                }
            }
        }

        // Check for collisions between projectiles and drones
        for (droneI in externalDrones.size - 1 downTo 0) {
            val drone = externalDrones[droneI]

            for (projectileI in projectiles.size - 1 downTo 0) {
                val proj = projectiles[projectileI]

                if (!proj.collisionsEnabled)
                    continue

                if (!IProjectile.checkCollision(
                        dt,
                        drone.flightController.position, proj.position,
                        drone.flightController.velocity, proj.velocity,
                        drone.hitboxRadius.f, proj.hitboxRadius.f
                    )
                ) {
                    continue
                }

                val distSq = drone.flightController.position.distToSq(proj.position)
                val maxDist = drone.hitboxRadius + proj.hitboxRadius

                // No overlap?
                if (distSq > maxDist * maxDist)
                    continue

                if (!drone.canCollideWith(proj))
                    continue

                proj.hitOtherProjectile(this)
                projectiles.removeAt(projectileI)

                // The drone is expected to play a sound.
                drone.hitProjectile(proj)

                break
            }
        }


        // Update the animations
        for (a in animations) {
            a.update(dt)
        }

        // Update the cosmetic crew combat laser bolts.
        crewShots.removeAll { shot ->
            val landed = !shot.update(dt)
            if (landed)
                shot.onLanded?.invoke()
            landed
        }

        // update returns false if the number should be removed
        damageNumbers.removeIf { !it.update(dt) }
    }

    private fun reactorPowerDemand(): Int {
        var demand = 0
        for (system in systems) {
            if (system is MainSystem)
                demand += system.reactorPowerDemand
        }
        return demand
    }

    /**
     * The native save holds the powers left after ShipManager::UpdateReactor (0x49c6d0) has run, so it
     * never loads above the reactor cap, and the native RNG state is part of the save. Our saves from
     * before that rule can load above the cap (a plasma storm, demand kept), and then the strip must
     * not depend on the unsaved simulation RNG: it draws from this generator, seeded from the loaded ship,
     * until the demand fits. Null for a ship that was not loaded from a save.
     */
    private var loadStripRandom: Random? = null

    private fun removeExcessReactorPower(reactorAvailable: Int, raisedDemand: Boolean): Boolean {
        val demand = reactorPowerDemand()

        // PowerManager::IncreasePower (0x647760) puts a raise that the full reactor can't carry on the backup
        // battery, which UpdateReactor (0x49c6d0) never counts (it only looks at the reactor's used count). Here the
        // raise gets its source in the passes below, after this check, so the battery power not yet held by any
        // system is room for it.
        var batterySpare = 0
        if (raisedDemand) {
            batterySpare = powerAvailableTypes.entries.filter { it.key !== ReactorEnergySource }.sumOf { it.value } -
                    systems.sumOf { (it as? MainSystem)?.sharedPowerInUse ?: 0 }
            batterySpare = batterySpare.coerceAtLeast(0)
        }

        if (demand <= reactorAvailable + batterySpare) {
            loadStripRandom = null
            return false
        }

        // The native picks a random system until one gives up power; the
        // bound only guards against systems that refuse (an active cloak).
        val random: Random = loadStripRandom ?: ftl.browser.BrowserRandom
        for (attempt in 0 until 1000) {
            val picked = systems[random.nextInt(systems.size)]
            if (picked !is MainSystem || picked.reactorPowerDemand <= 0)
                continue

            picked.forceDecreasePower()
            if (reactorPowerDemand() < demand)
                return true
        }
        return false
    }

    @JvmOverloads
    fun updateAvailablePower(raisedDemand: Boolean = false) {
        // Don't update our power during saving/loading, since different types
        // of power that systems can use (notably batteries, but other mods
        // might add similar power types) need all the systems to be loaded
        // first, and this would thus cause a system to pull different types
        // of power as a replacement during deserialisation.
        if (sys.isCurrentlyLoadingSave)
            return

        powerAvailableTypes.clear()

        for (type in EnergySource.GLOBAL_TYPES) {
            type.adjustShipPower(this, powerAvailableTypes)
        }

        val preEnvPower = powerAvailableTypes.values.sum()

        // Let the environment (for ion storms) deduct power
        sys.currentBeacon.getEnvironment(sys).adjustShipPower(this, powerAvailableTypes)

        // Remember how much power is unavailable, as it's shown in the UI.
        val postEnvPower = powerAvailableTypes.values.sum()
        blockedReactorPower = max(0, preEnvPower - postEnvPower)

        // ShipManager::UpdateReactor (0x49c6d0, every ShipManager::OnLoop): while the
        // systems use more reactor power than is available (a plasma storm halved
        // the reactor), a random system loses a step of power for good. Each removal
        // reruns this function with the new demand, so it ends after the first one.
        if (removeExcessReactorPower(powerAvailableTypes[ReactorEnergySource] ?: 0, raisedDemand))
            return

        // First make the systems use the power they're already using
        for (system in systems) {
            if (system !is MainSystem)
                continue

            system.consumePowerFirst(powerAvailableTypes)
        }

        // Then grab any extra for increasing power
        for (system in systems) {
            if (system !is MainSystem)
                continue

            system.consumePowerSecond(powerAvailableTypes)
        }
    }

    /**
     * Attack the shields, either damaging the super-shield or popping
     * a regular shield bubble.
     */
    fun attackShields(damage: Damage, damagePos: IPoint?, ionRoll: Boolean? = null) {
        if (damagePos != null)
            addShieldHit(damagePos)

        // Break super shields first
        // The reason this function is in Ship and not Shields is to support
        // ships with a super shield, but no shield system.
        if (superShield != 0) {
            // Shields::CollisionReal (0x6336f0): Reverse Ion Field is rolled
            // against the super-shield too, and on a proc only the ion
            // component of the hit is negated. [ionRoll] passes a roll the
            // projectile already made (AbstractProjectile.crossedShieldLine).
            val ionNegated = damage.ionDamage > 0 && (ionRoll ?: ionArmourNegates())
            val ionArmourIon = if (ionNegated) 0 else max(damage.ionDamage, 0)

            // Clamp damages to at least zero, to avoid negative values
            // repairing the super shield.
            val damageValue = ionArmourIon * 2 + max(damage.hullDamage, 0)
            superShield -= damageValue

            if (damagePos != null && ionNegated) {
                showDamageTextAt(damagePos, "text_resist", DAMAGE_COLOUR_ION)
            }
            if (damagePos != null && damageValue > 0) {
                showDamageTextAt(damagePos, damageValue, DAMAGE_COLOUR_ZOLTAN)
            }

            return
        }

        // Only projectiles with hull damage pop shields!
        if (damage.hullDamage > 0) {
            // Shields::CollisionReal (0x633f4a) raises event 8 when a bubble
            // of the player's shields is lost while an enemy ship is set
            // (Shields+0x274, written by ShipManager::SetEnemy 0x4a2b5a).
            if (isPlayerShip && (shields?.activeShields ?: 0) > 0 && sys.getEnemyOf(this) != null)
                skillEvents.shield++
            shields?.popShieldLayer()
        }

        attackShieldsIon(damage)
    }

    /**
     * Apply the ion and system damage components of a [Damage] object to the shields.
     *
     * This is the standard behaviour that should be used when attacking shields with
     * a weapon, if it can do ion damage to the ship's shield.
     */
    fun attackShieldsIon(damage: Damage) {
        if (damage.ionDamage <= 0 || shields == null)
            return

        damage(shields!!.room!!, damage.copyIonAndSys())
    }

    /**
     * One Rock Plating roll (0x49c770: rand%100+1 <= value*100). False without the augment, and the roll is
     * not consumed then.
     */
    fun rollRockArmour(): Boolean {
        val rockArmor = getAugmentValueOrNull(AugmentBlueprint.ROCK_ARMOR) ?: return false
        return ftl.browser.BrowserRandom.nextInt(100) + 1 <= (rockArmor * 100).toInt()
    }

    /**
     * [rockArmourRoll] is an already-rolled Rock Plating result. A beam rolls once for every step it takes
     * (ShipManager::DamageBeam, 0x4a9d40) and uses the same roll for the room's hull damage and the cell's breach.
     */
    @JvmOverloads
    fun damage(target: Room, damage: Damage, textPos: IPoint? = null, rockArmourRoll: Boolean? = null) {
        // ShipManager::DamageArea (0x4a6100): a hull buster doubles the hull
        // damage to a room without a system.
        var hullMult = 1
        if (target.system == null) {
            hullMult += damage.emptyRoomBonus
        }

        val hullDamage = damage.hullDamage * hullMult

        // Rock Plating (ROCK_ARMOR) rolls on every hit (0x49c770:
        // rand%100+1 <= value*100). A proc stops the hull damage and any
        // breach short of a certain one, and shows 'RESIST' for a hit with
        // damage; system damage, crew damage and fires still apply. Only
        // ROCK_ARMOR is rolled: HULL_ARMOR/ADV_HULL_ARMOR are commented out
        // in the vanilla data.
        val hullArmorNegates = damage.hullDamage >= 0 && (rockArmourRoll ?: rollRockArmour())

        // Titanium System Casing (SYSTEM_CASING): the same roll; a proc on a
        // system that isn't completely destroyed, from a hit with hull or
        // system damage, takes both out of the system damage. Ion damage,
        // crew damage, fires and breaches all still apply, and so does the
        // hull damage.
        val systemCasingChance = getAugmentValueOrNull(AugmentBlueprint.SYSTEM_CASING)
        val casingRoll = systemCasingChance != null &&
                ftl.browser.BrowserRandom.nextInt(100) + 1 <= (systemCasingChance * 100).toInt()
        val casingSystem = target.system
        val casingNegates = casingRoll && casingSystem != null &&
                casingSystem.damagedEnergyLevels < casingSystem.energyLevels &&
                (damage.hullDamage > 0 || damage.effectiveSysDamage > 0)

        // Reverse Ion Field (ION_ARMOR): a chance to negate the incoming
        // ion damage entirely. Hull, system and crew damage are all
        // unaffected. This covers weapon ion on any room (including ion
        // against the shield system itself) and ion bombs; the pulsar rolls
        // per-pulse via the same [ionArmourNegates] helper. On a proc
        // vanilla shows the same 'RESIST' popup as Rock Plating, tinted
        // with the ion damage-number blue; any hull/system components of
        // the hit still show their own numbers.
        val ionNegated = damage.ionDamage > 0 && ionArmourNegates()
        val appliedIonDamage = if (ionNegated) 0 else damage.ionDamage

        if (ionNegated)
            target.showDamageText("text_resist", DAMAGE_COLOUR_ION, textPos)

        if (hullArmorNegates && hullDamage > 0)
            target.showDamageText("text_resist", Colour.white, textPos)
        else if (hullArmorNegates)
            showDamageText(target, 0, if (casingNegates) 0 else damage.effectiveSysDamage, appliedIonDamage, textPos)
        else
            showDamageText(target, hullDamage, if (casingNegates) 0 else damage.effectiveSysDamage, appliedIonDamage, textPos)
        crewWeaponDamage(target, damage.effectiveCrewDamage.f, damage)

        if (sys.debugFlags.noDmg.set)
            return

        if (!hullArmorNegates) {
            health -= hullDamage

            // The player's last hull damage drives the screen shake (ShipManager +0x49c).
            if (sys.player === this)
                sys.shakeForHullDamage(hullDamage)
        }
        target.system?.dealDamage(if (casingNegates) 0 else damage.effectiveSysDamage, appliedIonDamage)
        if (damage.lockdown) target.lockdown()

        // Fire and breach are mutually exclusive, if a fire spawns then a breach cannot.
        if (ftl.browser.BrowserRandom.rollChance(damage.fireChance)) {
            // Spawns two fires (or possibly only one, if they both roll on the same cell).
            target.spawnFire()
            target.spawnFire()
        } else if (ftl.browser.BrowserRandom.rollChance(damage.breachChance) &&
            (!hullArmorNegates || damage.breachChance >= 100)
        ) {
            target.spawnBreach()
        }

        // Crystal Vengeance (CRYSTAL_SHARDS, 0x4a5df0): hull damage that
        // gets through rolls rand%100 < value*100 to break off a CRYSTAL_1
        // shard at the ship being fought. It's a neutral projectile, so ANY
        // defence drone (including ours - a vanilla quirk) can shoot it.
        // System-only and ion-only hits don't count.
        val enemy = sys.getEnemyOf(this)
        val damageTaken = hullDamage > 0 && !hullArmorNegates
        if (enemy != null && !enemy.isGone && damageTaken) {
            val shardChance = getAugmentValue(AugmentBlueprint.CRYSTAL_SHARDS)
            if (shardChance > 0f && ftl.browser.BrowserRandom.rollChance((shardChance * 100).toInt())) {
                val shard = VengeanceShardProjectile(enemy.rooms.random(ftl.browser.BrowserRandom))
                val spawnPos = target.pixelCentre

                // Launch like a weapon does: aim far past the ±800 ship
                // space boundary (in the enemy-facing direction), so the
                // shard crosses it and switches into the enemy's space
                // before reaching its intermediate target.
                val endPos = spawnPos + weaponFireDirection * 5000
                shard.setInitialPath(spawnPos, endPos)
                projectiles.add(shard)
            }
        }
    }

    fun playDamageEffect(type: AbstractWeaponBlueprint, position: IPoint): FloatingAnimation {
        val animation = sys.animations[type.explosion ?: error("Default explosion not set")]
        return playCentredAnimation(animation, position)
    }

    private fun showDamageText(target: Room, damage: Int, systemDamage: Int, ionDamage: Int, pos: IPoint?) {
        // Don't show the popup for system damage in a system-less room.
        if (target.system == null && damage == 0)
            return

        // Damage numbers split based on their type, also huge thanks to Gabriel Cooper
        // for helpfully providing data on the order of damage numbers!
        if (ionDamage > 0 && target.system != null) {
            target.showDamageText(ionDamage, DAMAGE_COLOUR_ION, pos)
        }
        val extraSystemDamage = systemDamage - damage.coerceAtLeast(0)
        if (extraSystemDamage > 0 && target.system != null) {
            // because the specific sys damage shown is that that exceeds the hull damage
            target.showDamageText(extraSystemDamage, DAMAGE_COLOUR_SYSTEM, pos)
        }
        if (damage > 0) {
            target.showDamageText(damage, Colour.white, pos)
        }
    }

    /**
     * Show the damage text, as it relates to a projectile or impact at a given position.
     *
     * This doesn't draw the text exactly at said position - it's shown a bit above.
     */
    fun showDamageTextAt(damagePos: IPoint, imageName: String, colour: Colour) {
        val image = sys.getImg("img/numbers/$imageName.png")
        val floatingNum = DamageNumber(damagePos.x.f, damagePos.y.f, image, colour)
        floatingNum.y -= 30f
        floatingNum.x += VisualRandom.nextInt(-10, 10).f
        damageNumbers.add(floatingNum)
    }

    fun showDamageTextAt(damagePos: IPoint, number: Int, colour: Colour) {
        val clamped = number.coerceIn(0..9)
        val imageName = "Text_${clamped}_L"
        showDamageTextAt(damagePos, imageName, colour)
    }

    // ShipManager::DamageCrew (0x49cf80), run for every crewmember in the room.
    fun crewWeaponDamage(target: Room, damage: Float, dmg: Damage) {
        for (crew in target.crew.toList()) {
            damageCrewMember(crew, damage, dmg)
        }
    }

    /**
     * ShipManager::DamageCrew (0x49cf80) for one crew member. Returns true if it killed them.
     *
     * Nothing happens (no damage, no stun) to a crew member whose own ship is the weapon's owner, unless the damage
     * is friendly fire: a ship's weapons never hurt its own boarders. Otherwise the crew member is stunned for the
     * weapon's stun time, rolls the stun chance separately (which only exists in the Advanced Edition,
     * Settings::GetDlcEnabled, and stuns for three seconds, 0x879df0), and takes the damage. A negative amount
     * (healing) is only applied to crew of the owner's ship.
     */
    fun damageCrewMember(crew: AbstractCrew, damage: Float, dmg: Damage): Boolean {
        val owner = dmg.ownerShip
        val sameShip = owner != null && crewHomeShip(crew) === owner
        if (sameShip && !dmg.friendlyFire)
            return false

        if (dmg.stunDuration > 0f)
            crew.stun(dmg.stunDuration)
        if (ftl.browser.BrowserRandom.rollChance(dmg.stunChance) && sys.content.enableAdvancedEdition)
            crew.stun(3f)

        if (damage < 0f && !sameShip)
            return false

        val wasAlive = crew.health > 0f
        crew.dealDamage(ShipDamage(damage, dmg))
        return wasAlive && crew.health == 0f
    }

    /**
     * The ship a crew member belongs to (CrewMember.iShipId): the ship that owns it, not the one it is standing on or
     * the one controlling it with mind control. Null for crew without an owner.
     */
    private fun crewHomeShip(crew: AbstractCrew): Ship? = when (crew) {
        is xyz.znix.xftl.crew.LivingCrew -> crew.ownerShip
        is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn -> crew.drone.ownerShip
        else -> null
    }

    fun resetAfterJump() {
        cargoBlueprints[4] = null
        cargoBlueprints[5] = null
        overCapacityBoxShown.fill(false)
        cargoUpdated()
        crew.filterIsInstance<xyz.znix.xftl.crew.CrewCrystal>().forEach { it.resetLockdown() }
        // Shields::Jump (0x6340e0) only resets the hit list and the Zoltan shield (below): the layers are not
        // recharged, they keep their charge and recharge normally at the new beacon.

        // And reset the FTL drive
        ftlChargeProgress = 0f

        // Jumping clears the super shield (if the augment is
        // present, it'll add it back in afterwards).
        superShield = 0

        // Remove all incoming and outgoing projectiles
        projectiles.clear()

        // Get rid of any drones orbiting us
        externalDrones.clear()

        resetWeaponsForJump()

        // Clear any previously-set scripted power limits, and re-apply
        // any that were previously set at this beacon.
        updateScriptedPowerLimits()

        for (augment in augmentValues.keys) {
            augment.onJump(this)
        }

        // The weapons jump and the clone bay heals on arrival (ShipManager::JumpArrive 0x49d490); the other systems
        // and the doors already did at the jump-out (browserJumpLeave), unless the beacon changed without one.
        val leftBeacon = browserLeftBeacon
        browserLeftBeacon = false
        for (system in systems) {
            if (!leftBeacon || system is Weapons || system is xyz.znix.xftl.systems.Clonebay)
                system.onJump()
        }
        if (!leftBeacon)
            resetDoorsForJump()
    }

    /** Set by [browserJumpLeave] until the arrival's [resetAfterJump]; not saved (a repeated system Jump is harmless). */
    private var browserLeftBeacon = false

    /**
     * ShipManager::JumpLeave (0x49c9c0): as the jump-out starts, every system but the weapons jumps (vtable +0x8c:
     * the battery stops and unlocks, the cloak drops, hacking and mind control end, artillery loses its charge)
     * and the doors are repaired when no boarder is aboard.
     */
    fun browserJumpLeave() {
        for (system in systems) {
            if (system !is Weapons && system !is xyz.znix.xftl.systems.Clonebay)
                system.onJump()
        }
        resetDoorsForJump()
        browserLeftBeacon = true
    }

    /** FUN_0048ca90 from JumpLeave and Wait: the door health is reset while no non-drone boarder is aboard. */
    private fun resetDoorsForJump() {
        if (crew.none { it is LivingCrew && it.mode == AbstractCrew.SlotType.INTRUDER }) {
            for (door in doors) {
                door.resetHealth()
            }
        }
    }

    /** WeaponSystem::Jump (0x635830): every weapon loses its charge, chain/charge count and aim. */
    private fun resetWeaponsForJump() {
        for (hp in hardpoints) {
            hp.weapon?.timeCharged = 0f
            hp.weapon?.chainCount = 0
            hp.weapon?.extraCharges = 0
            hp.weapon?.onJump()
        }
    }

    /**
     * ShipManager::Wait (0x49d300), the no-fuel wait: no fuel and no jump animation, and only these resets -
     * the weapons, shields, artillery and hacking Jump, the door repair when no boarder is aboard, the jump
     * heal (CloneHealing1), MindSystem::ForceReady and LockSystem(0) on the shields, engines, weapons and
     * piloting. Every other system keeps its state.
     */
    fun browserWaitOutOfFuel() {
        weapons?.onJump()
        resetWeaponsForJump()
        augmentValues.keys.filterIsInstance<xyz.znix.xftl.augments.AugPreigniter>().forEach { it.onJump(this) }
        superShield = 0
        augmentValues.keys.filterIsInstance<xyz.znix.xftl.augments.AugZoltanShield>().forEach { it.onJump(this) }
        for (system in systems) {
            if (system is xyz.znix.xftl.systems.Artillery || system is xyz.znix.xftl.systems.Hacking)
                system.onJump()
        }
        resetDoorsForJump()
        clonebay?.onJump()
        mindControl?.lockUnits(0)
        for (system in listOfNotNull(shields, engines, weapons, piloting)) {
            system.lockUnits(0)
        }
    }

    /**
     * This updates the power limits imposed on systems by scripted events.
     *
     * This must only be called on the player ship - on the enemy ship, systems
     * should be limited directly. This is because the player can jump between
     * beacons, and the effects need to be cleared and re-applied as appropriate.
     */
    fun updateScriptedPowerLimits() {
        for (system in systems) {
            val limit = sys.currentBeacon.powerLimitEffects[system.codename]
            system.scriptedPowerLimit = limit
            system.scriptedPowerLoss = sys.currentBeacon.powerLossEffects[system.codename] ?: 0
        }
    }

    fun addCrewMember(info: LivingCrewInfo, initial: Boolean, isIntruder: Boolean = false): LivingCrew =
        spawnCrewMember(info, initial, isIntruder, null, true)

    /** Scripted placement without temporarily reserving a different room. Native
     * tutorial setup recruits through this path without a career acquisition. */
    fun addCrewMemberInRoom(info: LivingCrewInfo, room: Room, recordRecruitment: Boolean): LivingCrew {
        require(room.ship === this) { "Crew spawn room belongs to another ship" }
        return spawnCrewMember(info, false, false, room, recordRecruitment)
    }

    private fun spawnCrewMember(info: LivingCrewInfo, initial: Boolean, isIntruder: Boolean,
                               startingRoom: Room?, recordRecruitment: Boolean): LivingCrew {
        val mode = if (isIntruder) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW
        var freeSpace: RoomPoint? = startingRoom?.let { findSpaceForCrew(it, mode) }

        // If this crewmember is being created with the ship, put them
        // in a system that makes sense.
        if (freeSpace == null && initial && !isIntruder) {
            val systems = listOf(piloting, engines, weapons, shields)
            for (system in systems) {
                if (system == null)
                    continue

                val room = system.room!!

                // If someone's already at the computer, don't put another person there.
                val firstSlot = system.configuration.computerPoint ?: ConstPoint.ZERO
                if (!room.isSlotFree(firstSlot, mode))
                    continue

                // Stand by the computer, if possible
                freeSpace = RoomPoint(room, firstSlot)
                break
            }
        }

        // Otherwise just put them wherever they'll fit.
        if (freeSpace == null) {
            freeSpace = findSpaceForCrew(null, mode)
        }

        val crewMember = info.race.spawn(freeSpace.room, mode)
        crewMember.info = info
        crew.add(crewMember)
        crewMember.jumpTo(freeSpace)

        // ShipManager::AddCrewMember (0x4a24a0) with the starting-crew flag (OnInit's crew loop, 0x4ae2f0): an
        // anaerobic crew member empties its room's oxygen (OxygenSystem::EmptyOxygen 0x62cbe0) on a ship with
        // an oxygen system. Later recruits keep the room's air.
        if (initial && crewMember.codename == "anaerobic" && oxygen != null)
            freeSpace.room.oxygen = 0f

        // If this is an intruder, make sure they don't count as being
        // 'owned' by the player. This means they can't use the player's
        // augments, for example.
        if (isIntruder) {
            crewMember.ownerShip = null
        }

        sys.updatePlayerCrew()

        // Count newly acquired player identities, including the starting crew.
        // Teleportation/cloning reuse identities and restoration does not acquire.
        if (recordRecruitment && sys.player === this && !isIntruder && !sys.isCurrentlyLoadingSave)
            sys.browserProgress.recruitCrew()

        return crewMember
    }

    /**
     * Find a slot somewhere in the ship (accessible from [startingRoom],
     * if this ship has some disconnected rooms) that's empty.
     *
     * This works with the pathfinding slots - the points that a crewmember
     * can be sent to by right-clicking. Thus a room that has no crew in
     * it would be considered full if crew are walking towards every spot.
     *
     * Similarly, a room full of crew would be considered empty if they're
     * all walking elsewhere.
     */
    fun findSpaceForCrew(startingRoom: Room?, type: AbstractCrew.SlotType): RoomPoint {
        val checked = HashSet<Room>()

        // Checks a single room for free space
        fun checkRoom(room: Room): RoomPoint? {
            if (checked.contains(room))
                return null
            checked.add(room)

            val freeSpot = room.firstFreeSlot(type)
            if (freeSpot != null) {
                return RoomPoint(room, freeSpot)
            }

            for (door in room.doors) {
                val neighbour = door.other(room) ?: continue
                checkRoom(neighbour)?.let { return it }
            }

            return null
        }

        // If no room has been specified, start at the piloting room.
        // This is the behaviour used for newly-spawned crew.
        // This might be called very early on before the systems
        // are added, so don't require that piloting exists.
        val firstRoom = startingRoom ?: piloting?.room ?: rooms.first()

        // Check every room via recursion. This will prefer the first
        // room if it's empty, and will only return a room if every room
        // in a path from it to piloting is connected.
        checkRoom(firstRoom)?.let { return it }

        // No free space!
        error("Couldn't find any free space in ship $name, starting at room $firstRoom")
    }

    fun updateAvailableSystems() {
        systems = rooms.mapNotNull { it.system }
        mainSystems = systems.mapNotNull { it as? MainSystem }.sortedBy { it.sortingType }
        subSystems = systems.mapNotNull { it as? SubSystem }.sortedBy { it.sortingType }

        weapons = systems.mapNotNull { it as? Weapons }.firstOrNull()
        drones = systems.mapNotNull { it as? Drones }.firstOrNull()
        engines = systems.mapNotNull { it as? Engines }.firstOrNull()
        shields = systems.mapNotNull { it as? Shields }.firstOrNull()
        medbay = systems.mapNotNull { it as? Medbay }.firstOrNull()
        clonebay = systems.mapNotNull { it as? Clonebay }.firstOrNull()
        cloaking = systems.mapNotNull { it as? Cloaking }.firstOrNull()
        teleporter = systems.mapNotNull { it as? Teleporter }.firstOrNull()
        hacking = systems.mapNotNull { it as? Hacking }.firstOrNull()
        mindControl = systems.mapNotNull { it as? MindControl }.firstOrNull()
        piloting = systems.mapNotNull { it as? Piloting }.firstOrNull()
        sensors = systems.mapNotNull { it as? Sensors }.firstOrNull()
        backupBattery = systems.mapNotNull { it as? BackupBattery }.firstOrNull()
        doorsSystem = systems.mapNotNull { it as? Doors }.firstOrNull()
        oxygen = systems.mapNotNull { it as? Oxygen }.firstOrNull()

        // If we have multiple artillery systems, sort them by the order
        // they appear in the XML. This probably isn't necessary, but if
        // we're using this order to display them in the UI or anything
        // like that it's nice to have this order.
        artillery = systems.mapNotNull { it as? Artillery }.sortedBy { it.configuration.spec.systemIndex }

        // The UI will need to change to reflect this
        // Note that this function is called very early on, before shipUI
        // is initialised, right after ship initialisation.
        sys.shipUI?.shipModified()
    }

    fun cargoUpdated() {
        // Weapon slots are persistent, including intentionally empty slots.
        weapons?.selectedTargets?.reindex()

        // Avoid power artefacts from Zoltan power interactions, as how that
        // power is distributed needs to be updated when the weapons change.
        weapons?.powerStateChanged()

        drones?.drones?.let { droneList ->
            var droneDest = 0
            for (drone in droneList) {
                if (drone == null)
                    continue
                droneList[droneDest++] = drone
            }
            for (i in droneDest until droneList.size) {
                droneList[i] = null
            }
        }

        // Update all the buttons that have to change to reflect
        // the cargo being modified.
        // This is called very early, before the ship UI is created.
        sys.shipUI?.shipModified()

        // Update the number of each type of augment, for fast lookups.
        privateAugmentValues.clear()
        for (aug in augments) {
            privateAugmentValues[aug] = aug.value + (augmentValues[aug] ?: 0f)
        }
    }

    /**
     * Place an item in this ship's inventory.
     *
     * If [forced] is true and the ship's inventory is full, the item is placed
     * into the left-behind-when-you-jump window. Otherwise, nothing happens.
     *
     * @return True if the item fits in the ship, false otherwise.
     */
    private fun storeOverflow(item: Blueprint, forced: Boolean, slot: Int): Boolean {
        if (!forced) return false
        if (cargoBlueprints[slot] == null) {
            cargoBlueprints[slot] = item
            overCapacityBoxShown[slot - 4] = true
            cargoUpdated()
        } else {
            val event = sys.eventManager["EQUIP_FULL"].resolve()
            val message = DialogueWindow.SyntheticEvent(event.text!!.resolve(ftl.browser.BrowserRandom))
            message.resources.scrap = 25
            sys.shipUI.showSyntheticDialogue(message)
        }
        return true
    }

    fun addBlueprint(item: Blueprint, forced: Boolean): Boolean {
        // Augments can only go in their special area
        if (item is AugmentBlueprint) {
            // Break down non-stackable duplicated augments
            // to scrap, using the AUGMENT_FULL event.
            // Note this goes before the max augments check, so the player
            // gets paid for duplicate augments when they're full.
            if (!item.stackable && augments.any { it == item }) {
                val realEvent = sys.eventManager["AUGMENT_FULL"].resolve()
                val synthetic = DialogueWindow.SyntheticEvent(realEvent.text!!.resolve(ftl.browser.BrowserRandom))
                synthetic.resources.scrap = 25
                sys.shipUI.showSyntheticDialogue(synthetic)
                return true
            }

            if (augments.size < MAX_AUGMENTS) {
                augments.add(item)
                cargoUpdated()
                return true
            }

            return storeOverflow(item, forced, 5)
        }

        if (item is AbstractWeaponBlueprint) {
            val weaponSlotCount = weaponSlots ?: hardpoints.size

            for (slot in 0 until weaponSlotCount) {
                if (hardpoints[slot].weapon != null)
                    continue

                hardpoints[slot].weapon = item.buildInstance(this)
                cargoUpdated()
                return true
            }
        }

        val drones = drones
        if (item is DroneBlueprint && drones != null) {
            for (slot in 0 until drones.drones.size) {
                if (drones.drones[slot] != null)
                    continue

                drones.drones[slot] = Drones.DroneInfo(item, null)
                cargoUpdated()
                return true
            }
        }

        for ((slot, current) in cargoBlueprints.take(4).withIndex()) {
            if (current != null)
                continue

            cargoBlueprints[slot] = item
            cargoUpdated()
            return true
        }

        return storeOverflow(item, forced, 4)
    }

    /**
     * Remove an item from the ship's inventory, including it's weapons and
     * drones areas.
     *
     * Returns true if the item was removed, false otherwise.
     */
    fun removeBlueprint(item: Blueprint): Boolean {
        // Check the cargo storage first, so the player doesn't have to swap
        // the item in again if they have multiple.
        for ((index, cargo) in cargoBlueprints.withIndex()) {
            if (cargo != item)
                continue
            cargoBlueprints[index] = null

            cargoUpdated()
            return true
        }

        if (augments.remove(item)) {
            cargoUpdated()
            return true
        }

        drones?.let { drones ->
            for ((index, drone) in drones.drones.withIndex()) {
                if (drone?.type != item)
                    continue

                drone.instance?.removeInstance()
                drones.drones[index] = null

                cargoUpdated()
                return true
            }
        }

        for (hp in hardpoints) {
            if (hp.weapon?.type != item)
                continue

            hp.weapon = null

            cargoUpdated()
            return true
        }

        return false
    }

    /**
     * Check if the ship has an augment by name.
     *
     * This throws an exception if the named blueprint doesn't exist.
     */
    fun hasAugment(name: String): Boolean {
        // Multiverse removes the O2_MASKS blueprint, so we need to account
        // for augments not existing.
        val blueprint = sys.blueprintManager.getOrNull(name) as AugmentBlueprint? ?: return false
        return augmentValues.containsKey(blueprint)
    }

    /**
     * Get the value for the given augment. If multiple of an augment
     * are installed, their values are summed together.
     *
     * Returns 0 if the ship has no such augments.
     */
    fun getAugmentValue(name: String): Float {
        return getAugmentValueOrNull(name) ?: 0f
    }

    /**
     * Reverse Ion Field (ION_ARMOR): roll against this ship's ion armour.
     * Returns true if an incoming amount of ion damage is negated entirely.
     * One arm gives a 50% chance, two or more are a total immunity - the
     * native roll (Shields::PulsarDamage 0x6329c0, ShipManager::DamageSystem
     * 0x4a4ab0) is int(value * 10) >= rand % 10 + 1.
     */
    fun ionArmourNegates(): Boolean {
        val armour = getAugmentValue(AugmentBlueprint.ION_ARMOR)
        return (armour * 10f).toInt() >= ftl.browser.BrowserRandom.nextInt(10) + 1
    }

    /**
     * Get the value for the given augment. If multiple of an augment
     * are installed, their values are summed together.
     *
     * Returns null if the ship has no such augments, or if the specified
     * augment doesn't exist.
     */
    fun getAugmentValueOrNull(name: String): Float? {
        val blueprint = sys.blueprintManager.getOrNull(name) as AugmentBlueprint? ?: return null
        return augmentValues[blueprint]
    }

    /**
     * Update [Room.reservedPlayerSlots] and [Room.reservedEnemySlots]
     * for every room on the ship.
     *
     * This should mainly be called by [AbstractCrew], whenever a change
     * to their pathfinding target is made. It can also be called whenever
     * the crew on the ship are changed, such as from deaths or new crew.
     */
    fun updateCrewReservedSlots() {
        val conflicts = ArrayList<AbstractCrew>()

        for (room in rooms) {
            room.updateCrewReservedSlots(conflicts)
        }

        // If two crewmembers are in, or set to be in, the same location then
        // one of them will be in the conflicts list. Make them find a new
        // free slot.
        for (conflict in conflicts) {
            val freeSpot = findSpaceForCrew(conflict.room, conflict.mode)
            require(conflict.setTargetRoom(freeSpot.room)) { "Failed to set target of empty room!" }
        }
    }

    /**
     * Perform the projectile missed test.
     *
     * This credits piloting/engine skills if it succeeds.
     *
     * [deltaEvasion] is added to the ship's evasion for this calculation. Note
     * this is the opposite of [AbstractWeaponBlueprint.accuracyModifier], which
     * when positive reduces the effective evasion.
     *
     * If [missDeadEnemies] is true, this will always return true if this ship
     * is an enemy ship that's been crew-killed. This should be set so that you
     * can't kill an enemy ship with your in-flight volley after they surrender,
     * however environmental stuff like asteroids should set this to false.
     */
    fun pickMissed(deltaEvasion: Int = 0, missDeadEnemies: Boolean = true): Boolean {
        // Weapons fired at enemy ships before they surrender always
        // miss after a surrender.
        if (!isPlayerShip && sys.getEnemyOf(this) == null && missDeadEnemies)
            return true

        // GetDodged (0x4a00d0) answers "dodged" for a jumping ship before any roll.
        if (browserJumping)
            return true

        val missed = ftl.browser.BrowserRandom.rollChance(evasion + deltaEvasion)
        sys.browserAchievements.dodgeRolled(this, missed)
        if (missed) {
            // ShipManager's dodge roll (0x4a0190..0x4a0225) raises event 7 only
            // for the player's ship, outside cloaking, and with either an enemy
            // ship present or a living intruder aboard: ShipManager+0x7f0 is the
            // number of living hostile crew (GetIntruder and not IsDead, counted
            // by ShipManager::OnLoop 0x4aa7xx, so asteroids train only while
            // boarders are on the ship). CrewMember::CheckSkills turns it into
            // experience.
            if (isPlayerShip && !isCloakActive && !sys.isCurrentlyLoadingSave &&
                (sys.getEnemyOf(this) != null || intruders.any { it.health > 0f })
            )
                skillEvents.dodge++
        }
        return missed
    }

    /**
     * The experience events of the previous frame, EventSystem 0x9196e0. Native
     * AddEvent (0x5ba3a0) counts into a pending vector that CApp::OnLoop
     * commits (0x5ba3c0) before the game loop, so CheckSkills reads last
     * frame's events. Only the player's ship raises them.
     */
    class SkillEvents {
        /** Event 7: a dodge. */
        var dodge = 0

        /** Event 8: a shield bubble lost. */
        var shield = 0

        /** Event 9: a weapon fired its last queued shot. */
        var weapon = 0

        var committedDodge = 0
            private set
        var committedShield = 0
            private set
        var committedWeapon = 0
            private set

        fun commit() {
            committedDodge = dodge
            committedShield = shield
            committedWeapon = weapon
            dodge = 0
            shield = 0
            weapon = 0
        }
    }

    val skillEvents = SkillEvents()

    /**
     * CrewMember::CheckSkills (0x479d70) for every crewmember. A player crewmember
     * who is manning a system (on either ship) with a skill that is not maxed
     * learns from the global events: the pilot and engine skills once for a
     * dodge, shields once for any lost bubble, weapons once per fired weapon.
     * The pilot's evasion record counts inside that same gate.
     */
    private fun checkSkills() {
        if (!isPlayerShip)
            return

        val events = skillEvents
        events.commit()
        if (events.committedDodge == 0 && events.committedShield == 0 && events.committedWeapon == 0)
            return

        val everyone = crew + (sys.enemy?.crew ?: emptyList())
        for (member in everyone) {
            val learner = member as? LivingCrew ?: continue
            if (learner.ownerShip?.isPlayerShip != true || learner.currentAction != AbstractCrew.Action.MANNING)
                continue

            when (learner.room.system) {
                is Piloting -> {
                    if (events.committedDodge == 0 || learner.isSkillMaxed(Skill.PILOTING)) continue
                    learner.addSkillPoint(Skill.PILOTING)
                    if (!sys.isCurrentlyLoadingSave) {
                        learner.info.browserCounters.increment(ftl.browser.CrewRecordKind.EVASIONS)
                        sys.mainGame?.profile?.updateCrewRecords(learner.info)
                    }
                }

                is Engines -> {
                    if (events.committedDodge == 0 || learner.isSkillMaxed(Skill.ENGINES)) continue
                    learner.addSkillPoint(Skill.ENGINES)
                }

                is Shields -> {
                    if (events.committedShield == 0 || learner.isSkillMaxed(Skill.SHIELDS)) continue
                    learner.addSkillPoint(Skill.SHIELDS)
                }

                is Weapons -> {
                    if (learner.isSkillMaxed(Skill.WEAPONS)) continue
                    repeat(events.committedWeapon) { learner.addSkillPoint(Skill.WEAPONS) }
                }

                else -> {}
            }
        }
    }

    fun spawnCrewShot(start: IPoint, end: IPoint, room: Room, onLanded: (() -> Unit)? = null) {
        if (start == end) {
            onLanded?.invoke()
            return
        }
        crewShots += CrewShot(room, start, end, onLanded)
    }

    fun playCentredAnimation(animation: AnimationSpec, centre: IPoint): FloatingAnimation {
        val firstFrame = animation.spriteAt(sys, 0)
        val offsetPos = ConstPoint(centre.x - firstFrame.width / 2, centre.y - firstFrame.height / 2)
        val floating = FloatingAnimation(sys, animation, offsetPos)
        animations += floating
        return floating
    }

    fun enemyShipUpdated() {
        drones?.enemyShipUpdated()

        val enemy = sys.getEnemyOf(this)

        // If our weapons are currently targeted on the now-neutral enemy,
        // un-target them.
        val targets = weapons?.selectedTargets
        if (targets != null) {
            val toUnTarget = ArrayList<Int>()
            for (target in targets.iterator()) {
                if (target.targetShip == this || target.targetShip == enemy)
                    continue

                toUnTarget.add(target.weaponNumber)
            }

            for (id in toUnTarget) {
                targets.unTarget(id)
            }
        }
    }

    fun saveCrewPositions() {
        for (member in crew) {
            if (member.mode != AbstractCrew.SlotType.CREW)
                continue

            if (member !is LivingCrew)
                continue

            if (member.ownerShip != this)
                continue

            val currentPos = member.pathingTarget ?: member.standingPosition ?: continue
            member.savedPosition = currentPos.shipPoint
        }
    }

    fun loadCrewPositions() {
        // Note that since we change the crew target positions one at a time,
        // if crew need to swap positions then we can end up not being able
        // to do that, even if it should be valid.
        for (member in crew) {
            if (member.mode != AbstractCrew.SlotType.CREW)
                continue

            if (member !is LivingCrew)
                continue

            if (member.ownerShip != this)
                continue

            val saved = member.savedPosition ?: continue
            val roomPos = shipToRoomPos(saved) ?: continue

            if (!member.setTargetRoom(roomPos.room, roomPos)) {
                // If our spot is taken, go somewhere else in the room.
                member.setTargetRoom(roomPos.room)
            }
        }
    }


    /**
     * Serialise this ship and everything related to it (projectiles,
     * crew, etc) into an XML element.
     */
    fun saveToXML(elem: Element, globalRefs: ObjectRefs) {
        displayName?.let { elem.setAttribute("displayName", it) }
        // Create IDs for all the objects that might reference each other
        // Note that events can reference crewmembers, so register them
        // into the global refs.
        val refs = ObjectRefs(globalRefs)
        for (crew in this.crew) {
            globalRefs.register(crew, crew.codename)
        }

        // Build the XML
        SaveUtil.addObjectId(elem, refs, this)

        // These two are read by InGameState, as they're needed for constructor arguments.
        SaveUtil.addAttr(elem, "shipId", name)
        SaveUtil.addAttr(elem, "specId", spec?.name ?: "null")
        if (generationSeed != null) {
            SaveUtil.addAttrInt(elem, "genSeed", generationSeed!!)
        }
        eventSeed?.let { SaveUtil.addAttrInt(elem, "eventSeed", it) }

        val resources = ResourceSet()
        resources.fuel = fuelCount
        resources.missiles = missilesCount
        resources.droneParts = dronesCount
        resources.scrap = scrap

        val resourcesElem = Element("resources")
        resources.saveToXML(resourcesElem, refs)
        elem.addContent(resourcesElem)

        SaveUtil.addAttrFloat(elem, "ftlProgress", ftlChargeProgress)
        SaveUtil.addAttrInt(elem, "reactorPower", purchasedReactorPower)
        browserMaxHealth?.let { SaveUtil.addAttrInt(elem, "browserMaxHealth", it) }
        SaveUtil.addAttrInt(elem, "health", health)

        SaveUtil.addTagInt(elem, "escapeHealth", escapeHealth, 0)
        SaveUtil.addTagInt(elem, "surrenderHealth", surrenderHealth, 0)
        SaveUtil.addTagFloat(elem, "escapeTimer", escapeTimer, null)
        SaveUtil.addTagBoolIfTrue(elem, "aiSurrendered", aiSurrendered)
        SaveUtil.addTagFloat(elem, "aiStalemateTimer", aiStalemateTimer, 0f)
        SaveUtil.addTagInt(elem, "aiTeleportWaves", aiTeleportWaves, 0)
        // CrewAI +0x68 and +0x69, optional so older saves still load.
        SaveUtil.addTagBoolIfTrue(elem, "aiAvoidBadFighters", crewAI.avoidBadFighters)
        SaveUtil.addTagBoolIfTrue(elem, "aiBadFighterPenaltyDropped", crewAI.badFighterPenaltyIsDropped)
        // CrewAI +0x24, the breach list in creation order (the system id of a breach problem depends on it).
        crewAI.breachOrderForSave()?.let { SaveUtil.addTag(elem, "aiBreachOrder", it) }
        SaveUtil.addTagInt(elem, "maxSuperShield", maxSuperShield, 5)
        SaveUtil.addTagInt(elem, "superShield", superShield, 0)
        SaveUtil.addTagBoolIfTrue(elem, "automated", isAutomated)

        SaveUtil.addAttrRef(elem, "bossManager", refs, boss)

        val crewListElem = Element("crew")
        for (crew in this.crew) {
            // We save drones separately
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            val crewElem = Element("crewMember")
            crew.saveToXML(crewElem, refs)
            crewListElem.addContent(crewElem)
        }
        elem.addContent(crewListElem)

        val systemListElem = Element("systems")
        for (system in systems) {
            val systemElem = Element("system")
            system.saveToXML(systemElem, refs)

            // Save the room this system is in, as we'll need it to match up
            // artillery weapons to the correct room on the flagship.
            SaveUtil.addAttrInt(systemElem, "room", system.room!!.id)
            system.configuration.computerOverride?.let { (point, dir) ->
                systemElem.setAttribute("computerSlot", "${point.x},${point.y},${dir.name}")
            }

            systemListElem.addContent(systemElem)
        }
        elem.addContent(systemListElem)

        // Serialise the weapons
        val weaponsElem = Element("weapons")
        for ((index, hardpoint) in hardpoints.withIndex()) {
            val weapon = hardpoint.weapon ?: continue

            val weaponElem = Element("weapon")
            SaveUtil.addAttrInt(weaponElem, "hardpoint", index)
            weapon.saveToXML(weaponElem, refs)
            weaponsElem.addContent(weaponElem)
        }
        elem.addContent(weaponsElem)

        // Serialise the drones. If a drone is deployed to another ship, it's
        // serialised on that ship instead of this one.
        val dronesElem = Element("drones")
        for (drone in externalDrones) {
            // Skip drones that are left over from a now-destroyed fight
            if (!sys.isShipPresent(drone.ownerShip))
                continue

            val droneElem = Element("drone")
            drone.saveToXML(droneElem, refs)
            dronesElem.addContent(droneElem)
        }
        for (pawn in crew.mapNotNull { it as? AbstractIndoorsDrone.Pawn }) {
            if (!sys.isShipPresent(pawn.drone.ownerShip)) continue
            val droneElem = Element("drone")
            pawn.drone.saveToXML(droneElem, refs)
            dronesElem.addContent(droneElem)
        }
        for (flyingDrone in projectiles.mapNotNull { it as? FlyingDroneProjectile }) {
            if (!sys.isShipPresent(flyingDrone.drone.ownerShip)) continue
            val droneElem = Element("drone")
            flyingDrone.drone.saveToXML(droneElem, refs)
            dronesElem.addContent(droneElem)
        }
        elem.addContent(dronesElem)

        // Serialise the augments. Since these are only the blueprint name,
        // they don't need a new parent element line drones/weapons/etc do.
        for (aug in augments) {
            val augElem = Element("augment")
            SaveUtil.addAttr(augElem, "name", aug.name)
            elem.addContent(augElem)
        }

        // Serialise loose cargo
        for ((index, item) in cargoBlueprints.withIndex()) {
            if (item == null)
                continue

            val cargoElem = Element("cargoBlueprint")
            SaveUtil.addAttr(cargoElem, "name", item.name)
            SaveUtil.addAttrInt(cargoElem, "index", index)
            elem.addContent(cargoElem)
        }

        // Serialise the doors. Most of the time there's nothing interesting
        // about them other than whether they're open or closed, but occasionally
        // they have more information (like the damage they've taken from
        // being attacked).
        val doorsElem = Element("doors")
        val doorStateString = StringBuilder()
        for ((index, door) in doors.withIndex()) {
            val thisDoorElem = door.saveToXML()
            thisDoorElem?.setAttribute("doorIndex", index.toString())
            thisDoorElem?.let { doorsElem.addContent(it) }

            doorStateString.append(if (door.open) 'Y' else 'n')
        }
        val doorStateElem = Element("isOpen")
        doorStateElem.addContent(doorStateString.toString())
        doorsElem.addContent(doorStateElem)
        elem.addContent(doorsElem)

        // Do the same thing for rooms, they won't emit an element unless
        // there's a fire or breach or something like that.
        val roomsElem = Element("rooms")
        for (room in rooms) {
            val thisRoomElem = room.saveToXML()
            thisRoomElem?.setAttribute("id", room.id.toString())
            thisRoomElem?.let { roomsElem.addContent(it) }
        }
        elem.addContent(roomsElem)

        // Serialise the room oxygen levels. Since there's a lot of rooms,
        // don't serialise them all individually.
        val oxygenLevelString = StringBuilder()
        for (room in rooms) {
            oxygenLevelString.append(room.oxygen)
            oxygenLevelString.append(' ')
        }
        val oxygenLevelElem = Element("oxygenLevels")
        oxygenLevelElem.addContent(oxygenLevelString.toString().trim())
        elem.addContent(oxygenLevelElem)

        // Serialise any floating animations, like the animations from a weapon
        // hitting a room or the shields.
        val animationsElem = Element("animations")
        for (animation in animations) {
            val animElem = Element("animation")
            animation.saveToXML(animElem)
            animationsElem.addContent(animElem)
        }
        elem.addContent(animationsElem)

        // Serialise in-flight projectile
        val projectilesElem = Element("projectiles")
        for (projectile in projectiles) {
            // A flying drone is saved as a drone rather than a projectile,
            // though really it's kinda both.
            if (projectile is FlyingDroneProjectile)
                continue

            val projectileElem = Element("projectile")
            SaveUtil.addAttr(projectileElem, "loadType", projectile.serialisationType)
            projectile.saveToXML(projectileElem, refs)
            projectilesElem.addContent(projectileElem)
        }
        elem.addContent(projectilesElem)
    }

    fun loadFromXml(rootElem: Element, refs: RefLoader) {
        // A continued run rings jumpReady again on its first frame. The ship is built first (ShipManager::OnInit
        // 0x4b1bf0 runs one OnLoop, with the cockpit manned from the ShipSystem ctor and the start power), so the
        // dodge latch is set; the first loop after the load then sees an unmanned cockpit (browserMannedStale) and,
        // without an autopilot, rings enginesOff.
        ftlReadyLatch = false
        browserMannedStale = true
        displayName = rootElem.getAttributeValue("displayName")
        loadStripRandom = Random(0x49C6D0L + systems.size)
        SaveUtil.registerObjectId(rootElem, refs, this)

        val resources = ResourceSet(rootElem.getChild("resources"), refs, sys)
        fuelCount = resources.fuel
        missilesCount = resources.missiles
        dronesCount = resources.droneParts
        scrap = resources.scrap

        ftlChargeProgress = SaveUtil.getAttrFloat(rootElem, "ftlProgress")
        purchasedReactorPower = SaveUtil.getAttrInt(rootElem, "reactorPower")
        browserMaxHealth = rootElem.getAttributeValue("browserMaxHealth")?.toIntOrNull()
        // Saves from before eventSeed: the generation seed is the same seed when the ship was reseeded.
        eventSeed = rootElem.getAttributeValue("eventSeed")?.toIntOrNull() ?: rootElem.getAttributeValue("genSeed")?.toIntOrNull()
        health = SaveUtil.getAttrInt(rootElem, "health")

        escapeHealth = SaveUtil.getOptionalTagInt(rootElem, "escapeHealth") ?: 0
        surrenderHealth = SaveUtil.getOptionalTagInt(rootElem, "surrenderHealth") ?: 0
        escapeTimer = SaveUtil.getOptionalTagFloat(rootElem, "escapeTimer")
        aiSurrendered = SaveUtil.getOptionalTagBool(rootElem, "aiSurrendered") ?: false
        aiStalemateTimer = SaveUtil.getOptionalTagFloat(rootElem, "aiStalemateTimer") ?: 0f
        aiTeleportWaves = SaveUtil.getOptionalTagInt(rootElem, "aiTeleportWaves") ?: 0
        crewAI.restoreBadFighterState(
            SaveUtil.getOptionalTagBool(rootElem, "aiAvoidBadFighters") ?: false,
            SaveUtil.getOptionalTagBool(rootElem, "aiBadFighterPenaltyDropped") ?: false
        )
        crewAI.restoreBreachOrder(SaveUtil.getOptionalTag(rootElem, "aiBreachOrder"))
        maxSuperShield = SaveUtil.getOptionalTagInt(rootElem, "maxSuperShield") ?: 5
        isAutomated = SaveUtil.getOptionalTagBool(rootElem, "automated") ?: false

        // This must be set after maxSuperShield to avoid it being wrongly clamped
        superShield = SaveUtil.getOptionalTagInt(rootElem, "superShield") ?: 0

        SaveUtil.getAttrRef(rootElem, "bossManager", refs, BossManager::class.java) { boss = it }

        // Load the systems
        for (elem in rootElem.getChild("systems").getChildren("system")) {
            // Spawn in the system
            val name: String = elem.getAttributeValue("name")
            val blueprint = sys.blueprintManager[name] as SystemBlueprint

            // Find the slot this system is in.
            // This isn't as simple as it might sound, since for artillery
            // we can have multiple systems installed. Thus we need to use
            // the serialised room ID to make sure we load this into the
            // correct instance of the system.
            val roomId = SaveUtil.getAttrInt(elem, "room")
            val room = rooms[roomId]

            val slot = room.systemSlots.first { it.system == blueprint }
            room.setSystem(slot)
            elem.getAttributeValue("computerSlot")?.split(",")?.takeIf { it.size == 3 }?.let {
                slot.overrideComputer(ConstPoint(it[0].toInt(), it[1].toInt()), Direction.valueOf(it[2]))
            }

            // Re-load the system's properties
            room.system!!.loadFromXML(elem, refs)
        }

        // Load the crew
        for (crewElem in rootElem.getChild("crew").getChildren("crewMember")) {
            val raceName = crewElem.getAttributeValue("type")
            val race = sys.blueprintManager[raceName] as CrewBlueprint
            val info = LivingCrewInfo.generateRandom(race, sys)
            val crewMember = addCrewMember(info, false)
            crewMember.loadFromXML(crewElem, refs)
        }

        // Deserialise the doors.
        val doorsElem = rootElem.getChild("doors")
        val doorsWithXML = HashSet<Door>()
        for (doorElem in doorsElem.getChildren("door")) {
            val index = SaveUtil.getAttrInt(doorElem, "doorIndex")
            doors[index].loadFromXML(doorElem)
            doorsWithXML.add(doors[index])
        }
        for (door in doors) {
            if (door !in doorsWithXML) {
                door.loadWithoutXML()
            }
        }
        val doorStateString = doorsElem.getChildTextTrim("isOpen")
        for ((index, door) in doors.withIndex()) {
            val open = doorStateString[index] == 'Y'
            door.loadSavedOpen(open)
        }

        // Deserialise rooms, without their oxygen levels which are stored separately below.
        val roomsElem = rootElem.getChild("rooms")
        for (elem in roomsElem.getChildren("room")) {
            val id = elem.getAttributeValue("id").toInt()
            rooms[id].loadFromXML(elem)
        }

        // Deserialise the oxygen levels, which are just numbers for each room.
        val oxygenLevels = rootElem.getChildTextTrim("oxygenLevels").split(' ', '\t')
        for ((index, levelStr) in oxygenLevels.withIndex()) {
            val room = rooms[index]
            room.oxygen = levelStr.toFloat()
        }

        // Deserialise the weapons
        for (weaponElem in rootElem.getChild("weapons").getChildren("weapon")) {
            val hardpointIndex = SaveUtil.getAttrInt(weaponElem, "hardpoint")
            val type = SaveUtil.getAttr(weaponElem, "type")
            val blueprint = sys.blueprintManager[type] as AbstractWeaponBlueprint

            val weapon = blueprint.buildInstance(this)
            weapon.loadFromXML(weaponElem, refs)
            hardpoints[hardpointIndex].weapon = weapon
        }

        // Deserialise the drones, both friendly and not.
        for (droneElem in rootElem.getChild("drones").getChildren("drone")) {
            val type = SaveUtil.getAttr(droneElem, "type")
            val blueprint = sys.blueprintManager[type] as DroneBlueprint

            val drone = blueprint.makeInstance()
            drone.loadFromXML(droneElem, refs, this)

            // The drone will add itself to the externalDrones or crew list.
        }

        // Deserialise the augments.
        for (augElem in rootElem.getChildren("augment")) {
            val name = SaveUtil.getAttr(augElem, "name")
            augments.add(sys.blueprintManager[name] as AugmentBlueprint)
        }

        // Deserialise loose cargo
        for (cargoElem in rootElem.getChildren("cargoBlueprint")) {
            val name = SaveUtil.getAttr(cargoElem, "name")
            val index = SaveUtil.getAttrInt(cargoElem, "index")
            cargoBlueprints[index] = sys.blueprintManager[name] as Blueprint
        }

        // Deserialise explosion (and similar) animations
        for (animElem in rootElem.getChild("animations").getChildren("animation")) {
            animations += FloatingAnimation.loadFromXML(animElem, sys)
        }

        // Deserialise the in-flight projectiles
        for (projectileElem in rootElem.getChild("projectiles").getChildren("projectile")) {
            val serialisationType = SaveUtil.getAttr(projectileElem, "loadType")
            IProjectile.loadFromXML(sys, projectileElem, refs, serialisationType) { projectiles += it }
        }

        updateCrewReservedSlots()
        updateAvailableSystems()
        cargoUpdated()
        // The OnInit loop's dodge latch (see the top): set once the systems are known.
        dodgeLatch = engines != null && piloting != null
    }

    fun hasCrewOwnedByShip(otherShip: Ship): Boolean {
        return crew.filterIsInstance(LivingCrew::class.java)
            .any { it.ownerShip == otherShip }
    }

    /**
     * Checks if there's any crew onboard that don't belong to the player.
     *
     * This is needed to check for the enemy crew dead events, as FTL
     * doesn't seem to care where the enemy crew ultimately came from.
     */
    fun hasCrewOwnedByAnyOtherShip(): Boolean {
        return crew.filterIsInstance(LivingCrew::class.java)
            .any { it.ownerShip != this }
    }

    companion object {
        /** ShieldAnimation's end size (40, from 0x00885390): the flash's half-arc in degrees and its lifetime. */
        private const val SHIELD_HIT_SIZE = 40f

        /** ShieldAnimation's growth per 1/16 s (10, 0x00885330). */
        private const val SHIELD_HIT_SPEED = 10f

        const val MAX_AUGMENTS: Int = 3

        /** The ordinary cargo boxes; [cargoBlueprints] slots 4 (weapon/drone) and 5 (augment) are the overflow boxes. */
        const val CARGO_SLOTS: Int = 4

        /** Ship::OnLoop 0x87989c: how long the small destruction explosions last. */
        const val DESTRUCTION_SOUND_TIME: Float = 1.5f

        /** The ExplosionAnimation tracker's length (Ship constructor 0x48b570). */
        const val DESTRUCTION_TIME: Float = 3f

        /** Ship::OnLoop 0x493f70: no new small explosion while 25 are playing. */
        private const val MAX_DESTRUCTION_EXPLOSIONS = 25
    }

    class Hardpoint(val spec: ShipBlueprint.ParsedHardpoint) {
        var weapon: AbstractWeaponInstance? = null
    }

    class FloatingAnimation(game: InGameState, val spec: AnimationSpec, val pos: ConstPoint, val scaling: Float = 1f) {
        private val animation = spec.startSingle(game)

        val isFinished get() = animation.isStopped

        fun render(g: Graphics) {
            val frame = animation.currentFrame

            if (scaling == 1f) {
                frame.draw(pos.x.f, pos.y.f)
                return
            }

            // This is used for flak
            g.pushTransform()
            g.translate(pos.x.f, pos.y.f)
            g.scale(scaling, scaling)
            frame.draw()
            g.popTransform()
        }

        fun update(dt: Float) {
            animation.update(dt)
        }

        fun saveToXML(elem: Element) {
            SaveUtil.addAttr(elem, "name", spec.name)
            SaveUtil.addAttrInt(elem, "x", pos.x)
            SaveUtil.addAttrInt(elem, "y", pos.y)
            SaveUtil.addAttrFloat(elem, "timer", animation.timer)
            SaveUtil.addAttrFloat(elem, "scaling", scaling)
        }

        companion object {
            fun loadFromXML(elem: Element, game: InGameState): FloatingAnimation {
                val spec = when (val name = SaveUtil.getAttr(elem, "name")) {
                    // Fires don't have an animation set in the XML,
                    // so we have to grab the proper animation here.
                    FireInstance.SMOKE_ANIMATION.name -> FireInstance.SMOKE_ANIMATION

                    else -> game.animations[name]
                }

                val x = SaveUtil.getAttrInt(elem, "x")
                val y = SaveUtil.getAttrInt(elem, "y")
                val scaling = SaveUtil.getAttrFloat(elem, "scaling")

                val anim = FloatingAnimation(game, spec, ConstPoint(x, y), scaling)
                anim.animation.timer = SaveUtil.getAttrFloat(elem, "timer")

                return anim
            }
        }
    }

    inner class DamageNumber(var x: Float, var y: Float, val image: Image, val colour: Colour) {
        var timer: Float = 0f

        private val LIFETIME = 1.2f

        fun draw() {
            image.drawAlignedCentred(x.roundToInt(), y.roundToInt(), colour)
        }

        fun update(dt: Float): Boolean {
            timer += dt
            y -= dt / LIFETIME * 10

            return timer < LIFETIME
        }
    }
}
