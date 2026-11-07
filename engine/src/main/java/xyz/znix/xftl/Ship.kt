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
    /** ShipManager::OnInit reads the blueprint once: a later write to it reaches only ships built after. */
    val builtMaxHealth = type.maxHealth
    private val builtWeaponSlots = type.weaponSlots
    private val builtDroneSlots = type.droneSlots
    val boardingStrategy = type.boardingStrategy
    val maxHealth: Int get() = browserMaxHealth ?: builtMaxHealth

    /**
     * A generated enemy's hull: ShipGenerator::CreateShip adds the effective
     * level to the blueprint's (at most 20). Null for every other ship and
     * for saves that predate it.
     */
    var browserMaxHealth: Int? = null
    val isPlayerShip: Boolean get() = type.isPlayerShip
    val isUsingBossUI: Boolean get() = (boss?.useBossShipUI ?: false) || hsBossShip != null

    /**
     * Hyperspace `<bossShip>`: an enemy whose blueprint is listed gets the flagship's box (the CombatControl /
     * CommandGui::AddEnemyShip hooks of CustomEvents.cpp, `boss_visual`); see HostileShipUI for its placement.
     */
    val hsBossShip: xyz.znix.xftl.hs.events.BossShipDefinition?
        get() = if (xyz.znix.xftl.hs.HS.active && !isPlayerShip) sys.eventManager.hs?.getBossShipDefinition(name) else null
    val isAutoScout: Boolean get() = type.isAutoScout

    /**
     * Crew-less ships that run as if their systems were manned, repairing
     * damage as they go - vanilla's flagship becomes one when all its crew
     * are killed (the BOSS_AUTOMATED event, BossShip::GetSubEvent 0x4066d0).
     * Unlike [isAutoScout] this isn't from the blueprint - it's a runtime
     * state, so it needs serialising.
     */
    var isAutomated: Boolean = false
    // A mod's playable ship may leave these out; the hangar and the HUD then count the usual boxes.
    val weaponSlots: Int? get() = customised?.weaponSlots ?: builtWeaponSlots ?: if (isPlayerShip) 4 else null
    val droneSlots: Int? get() = customised?.droneSlots ?: builtDroneSlots ?: if (isPlayerShip) 2 else null

    /** Hyperspace: Ship::shipImage's x and y moved by a script. */
    @JvmField var hsHullShiftX = 0
    @JvmField var hsHullShiftY = 0

    /** Hyperspace: WeaponSystem::slot_count and DroneSystem::slot_count written by a script: what fits; the weapons on board stay (oracle hw-sl). */
    @JvmField var hsWeaponSlots: Int? = null
    @JvmField var hsDroneSlots: Int? = null

    /**
     * If this ship is a boss, this points to its manager. Null otherwise.
     */
    var boss: BossManager? = boss
        private set // Mutable only for deserialisation

    val rooms: List<Room>
    /** Ship::lockdowns (the crystal shards of every lockdown on this ship), see [lockdownRoom]. */
    val lockdownShards = ArrayList<xyz.znix.xftl.layout.LockdownShard>()

    /**
     * Ship::LockdownRoom (1.6.14 0x494f50; Hyperspace CustomLockdowns.cpp): the shards of a lockdown of [room] from
     * ship pixel ([posX], [posY]). Per 35 px of the room's width three top/bottom pairs and per 35 px of its height three
     * left/right pairs, each pair drawing two wall offsets (`random32() % 12`) before its two shards are made; then one
     * shard per inner door of the room to the door's centre. The doors themselves are locked by [Room.lockdown].
     * Hyperspace: [hsDuration] (the lockdown definition's), its [anims] and [colour]; each wall shard draws its
     * animation again (LockdownShard::Initialize, after the vanilla constructor's draws).
     */
    fun lockdownRoom(room: Room, posX: Float, posY: Float, hsDuration: Float? = null,
                     anims: List<String>? = null, colour: xyz.znix.xftl.rendering.Colour? = null,
                     canDilate: Boolean = true, doorHealth: Int = 50) {
        fun r32() = ftl.browser.BrowserRandom.nextBits(31)
        val hs = xyz.znix.xftl.hs.HS.active
        fun shard(gx: Int, gy: Int, door: Boolean): xyz.znix.xftl.layout.LockdownShard {
            // the constructor: animation `random32() % 2 + 1` (not for a door shard), then the speed
            var name = if (door) "crystal_1" else "crystal_" + (r32() % 2 + 1)
            val speed = xyz.znix.xftl.layout.LockdownShard.speedOf(r32())
            if (hs) {
                val list = anims ?: listOf("crystal_1", "crystal_2")
                if (list.isNotEmpty()) name = if (door) list[0] else list[r32() % list.size]
                else if (!door) name = if (r32() % 2 == 0) "crystal_1" else "crystal_2"
            }
            return xyz.znix.xftl.layout.LockdownShard(this, room.id, posX, posY, gx, gy, name, speed, door && hs,
                colour ?: xyz.znix.xftl.rendering.Colour.white, if (hs) (hsDuration ?: 12f) else null).also { it.canDilate = canDilate }
        }
        val x = room.offsetX; val y = room.offsetY; val w = room.pixelWidth; val h = room.pixelHeight
        // Hyperspace links a wall shard that lands on a door to it (HS_GetSelectedDoor). It has no health of its own,
        // so it goes on the next frame (Ship::OnLoop, CustomLockdowns.cpp).
        fun wall(gx: Int, gy: Int) = shard(gx, gy, false).also { s ->
            if (hs && doors.any { kotlin.math.abs(it.pixelCentre.x - gx) <= 10 && kotlin.math.abs(it.pixelCentre.y - gy) <= 10 }) s.done = true
        }
        for (i in 0 until w / 35 * 3) {
            val a = r32(); val b = r32()
            lockdownShards += wall(x + i * 12 + a % 12, y)
            lockdownShards += wall(x + i * 12 + b % 12, y + h)
        }
        for (i in 0 until h / 35 * 3) {
            val a = r32(); val b = r32()
            lockdownShards += wall(x, y + i * 12 + a % 12)
            lockdownShards += wall(x + w, y + i * 12 + b % 12)
        }
        for (door in doors) {
            if (door.isAirlock || (door.left !== room && door.right !== room)) continue
            lockdownShards += shard(door.pixelCentre.x, door.pixelCentre.y, true).also { it.door = door; it.ownHealth = doorHealth }
        }
    }

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

    /** The over-capacity slot (4 the item box, 5 the augment box) that took an item last, and how many it took, for the touch layout's pager. */
    var overCapacityLastSlot = 4
    var overCapacityAdds = 0

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

    /** Modded sessions: the starting weapons/drones already picked (hangar or run start); null = pick in loadDefaultContents. */
    @JvmField var hsStartEquipment: xyz.znix.xftl.hs.ship.HsShipStart.Equipment? = null

    /** Hyperspace per-ship state (hidden augments, room definitions, ...); null in vanilla play. */
    @JvmField var hs: xyz.znix.xftl.hs.ship.HsShipState? = null

    /** Spreader<Fire>::count and roomCount as of the last ship loop (Hyperspace Lua). */
    @JvmField var hsFireCount = 0
    @JvmField var hsFireRoomCount = IntArray(0)

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

    val animations: MutableList<FloatingAnimation> = ArrayList()

    // Cosmetic laser bolts fired between fighting crewmembers.
    // Transient on purpose - not saved, the longest flight is ~0.3s.
    val crewShots: MutableList<CrewShot> = ArrayList()

    internal val damageNumbers = ArrayList<DamageNumber>()

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

    /** ShipManager.jump_timer.second (85 unless a script writes it): a higher goal charges proportionally slower. */
    @JvmField var hsJumpGoal: Float = 85f

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
            // Hyperspace ships (MV stations) can lack either system: no FTL charge then.
            val engines = engines ?: return false
            val piloting = piloting ?: return false
            val hasPilot = isAutoScout || isAutomated || piloting.hasPilot
            return engines.powerSupplied > 0 && piloting.undamagedEnergy > 0 && hasPilot
                    && !engines.isHackActive && !piloting.isHackActive
        }

    // Hyperspace: the ship's `<customReactor maxLevel>` (ShipManager::CanUpgrade hook for the reactor).
    val maxReactorPower: Int get() = if (hs != null) xyz.znix.xftl.hs.ship.HsShips.def(type.name).maxReactorLevel else 25

    // The raw amount of reactor power purchased by the player
    var purchasedReactorPower: Int = 5

    // The amount of reactor power available for use by the player, taking ion storms, events and so on into account
    val reactorPower: Int get() = purchasedReactorPower

    // Hyperspace: PowerManager.currentPower.first written by a script: the tally apart from what the systems hold
    @JvmField var hsReactorUsedOffset: Int = 0

    // The unused reactor/battery power
    val powerAvailableTypes = HashMap<EnergySource, Int>()

    val powerAvailable: Int get() = powerAvailableTypes.values.sum()

    /**
     * The amount of power that's unusable due to events or the environment (in
     * a plasma storm).
     */
    var blockedReactorPower: Int = 0
        private set

    /** Hyperspace: this ship's PowerManager temporary status (null until used; see HsPower). */
    @JvmField
    var hsPower: xyz.znix.xftl.hs.systems.HsPower? = null

    /** ShipManager::iLastDamage as DamageBeam stores it (the hull damage of its last room hit). */
    @JvmField var nativeLastDamage = 0
    /** ShipManager::killedByBeam: crew killed per beam (Damage.selfId), for ACH_SLUG_BIO. */
    @JvmField val nativeBeamKills = HashMap<Int, Int>()

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
    private var superShieldValue: Int = 0
    var superShield: Int
        get() = superShieldValue
        set(value) {
            superShieldValue = value.coerceIn(0..maxSuperShield)
            if (value > 0 && hsSuperShieldSet == 0 && xyz.znix.xftl.hs.HS.active) hsSuperShieldSet = 1
        }

    /** Native Shields::CollisionReal subtracts the full hit; OnLoop clamps a transient negative value next frame. */
    internal fun nativeSetSuperShield(value: Int) { superShieldValue = value }

    /**
     * Hyperspace Lua: whether Shields::shields.power.super.second is set (FTL keeps 0 until Shields::AddSuperShield or
     * PowerSuperShields gives a super shield, 0x631790 / 0x631810, and DrainSuperShield clears it on a jump).
     */
    @JvmField var hsSuperShieldSet = 0

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

    /** ShipManager::bDestroyed (+0x499): latched by ShipManager::OnLoop (0x4abd40) the first loop it sees hull < 1. */
    var nativeDestroyed = false

    /**
     * Ship::DestroyedDone: the ship is dead and its ExplosionAnimation tracker (3 s from the Ship constructor
     * and 4 s for a jump-out) has finished.
     */
    val isGone: Boolean get() = isDead && destructionTime >= destructionEnd

    /**
     * ShipManager::bInvincible: at hull 0 the wreck ends in an emergency jump. ShipManager::OnLoop
     * calls Ship::SetDestroyed(true, bInvincible); SetDestroyed sets ExplosionAnimation::bJumpOut and a 4 s
     * tracker. BossShip::StartStage sets it to stage != 3; Hyperspace's `<invincible/>` event ships and Lua's
     * `ShipManager.bInvincible` set it too (HS: emergency FTL upon destruction, as the flagship's stages 1 and 2).
     */
    @JvmField var invincible = false

    /** ExplosionAnimation::bJumpOut: [invincible] when the explosion started. */
    var destructionJumpOut = false
        private set
    private val destructionEnd: Float get() = if (destructionJumpOut) DESTRUCTION_JUMP_TIME else hsDestructionTime ?: DESTRUCTION_TIME

    /** Hyperspace: a defeated `<finalBoss>` ship's explosion tracker runs 6 s (ship.explosion.time = 6.0, HsFinalBoss). */
    @JvmField var hsDestructionTime: Float? = null

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

    // GetDodgeFactor (0x49fe90): engines and piloting only, without the cloak. It returns 0 when the ship has no
    // engines (system slot 1 is -1) or no piloting (slot 6 is -1), e.g. Multiverse stations such as MU_ROCK_STATION.
    private val engineDodge: Float
        get() {
            val piloting = piloting ?: return 0f
            val engines = engines ?: return 0f
            return (piloting.evasion + engines.evasion.f) * piloting.evasionMultiplier * engines.evasionMultiplier
        }

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

    /** ShipManager::GetDodgeFactor excludes the cloak bonus, which GetDodged adds separately. */
    /** Diagnostics only (native replays): a dodge factor standing in for the native ship's manning. */
    var browserDiagnosticDodge: Int? = null

    val dodgeFactor: Int
        get() {
            browserDiagnosticDodge?.let { return it }
            // GetDodged (0x4a00d0): GetDodgeFactor (0 without engines or piloting), plus 60 while cloaked.
            val piloting = piloting
            val engines = engines
            var evasion = 0f
            if (piloting != null && engines != null) {
                evasion = piloting.evasion + engines.evasion.f
                evasion *= piloting.evasionMultiplier
                evasion *= engines.evasionMultiplier
            }

            // HS 1b05a07 CustomDamage.cpp286 wraps InternalEvents.cpp337 in the actual oracle:
            // callback result first, then the current projectile's accuracy (including direct Lua queries).
            return if (xyz.znix.xftl.hs.HS.active)
                xyz.znix.xftl.hs.weapons.HsWeaponHooks.dodgeFactor(this, evasion.toInt()) -
                    (xyz.znix.xftl.hs.weapons.HsDamage.current?.accuracyMod ?: 0)
            else evasion.toInt()
        }

    // The ship's final evasion, in percent.
    val evasion: Int get() = dodgeFactor + if (isCloakActive) 60 else 0

    /**
     * From 0-1.
     */
    var averageOxygen: Float = 1f
        private set

    /** Hyperspace AddSystem of an oxygen system: every room full, the average with it (see HsSystemInstall.install). */
    fun hsFillOxygen() {
        for (room in rooms) room.oxygen = 1f
        averageOxygen = 1f
    }

    /** Hyperspace RemoveSystem of the oxygen system (oxygenWithoutSystem): the rooms keep their levels, the dummy system's total starts empty until the next update. */
    fun hsEmptyOxygenTotal() {
        averageOxygen = 0f
    }

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

        hardpoints = type.hardpoints.mapTo(ArrayList()) { Hardpoint(it) }
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

        // Load the starting systems (Hyperspace: in CreateSystems' order, see HsSystemState.creating)
        xyz.znix.xftl.hs.systems.HsSystemState.creating(this) {
            for (config in systemSlots) {
                if (!config.spec.availableByDefault)
                    continue

                config.room.setSystem(config)
            }
        }

        // Load the starting resources
        missilesCount = type.initialMissiles
        dronesCount = type.initialDroneParts

        // Modded data (lists, equipment without its system): the original's rules, see HsShipStart.
        if (xyz.znix.xftl.hs.HS.modded) xyz.znix.xftl.hs.ship.HsShipStart.loadEquipment(this, hsStartEquipment)
        // Load all the weapon blueprints
        else for ((index, name) in type.initialWeapons.let { l -> type.weaponListCount?.let { l.take(it) } ?: l }.withIndex()) {
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
        if (!xyz.znix.xftl.hs.HS.modded) for ((idx, name) in type.initialDrones.withIndex()) {
            val drone = sys.blueprintManager.getOrNull(name) as? DroneBlueprint

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
            val augment = sys.blueprintManager.getOrNull(name) as? AugmentBlueprint

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

        // Hyperspace: ShipManager_Extend::Initialize after OnInit (hidden augments, room definitions, automation).
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsShipState.initialize(this)

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
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSuperShield.detachedInstantCharge(this)
        // Settle the power and evasion for the start event, without simulating any time.
        update(0f)
    }

    /**
     * AddSystem (0x4a8370) for a system the player gets during a run: the ShipSystem ctor powers it towards its
     * starting target ([browserStartingTargetPower]) as far as the reactor allows; new shields then go through
     * InstantPowerShields.
     */
    fun browserPowerNewSystem(system: AbstractSystem) {
        if (system !is MainSystem) {
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSystemState.firePending()
            return
        }
        updateAvailablePower()
        val target = browserStartingTargetPower(system)
        var guard = system.undamagedEnergy + 2
        while (system.powerSelected < target && guard-- > 0) {
            val before = system.powerSelected
            system.increasePower()
            if (system.powerSelected == before) {
                // Hyperspace Lua: PowerManager::failedPowerup records the refused bar (oracle wp5-sys, artillery
                // added with a full reactor)
                if (xyz.znix.xftl.hs.HS.active && powerAvailable <= 0) xyz.znix.xftl.hs.systems.HsPower.of(this).failedPowerup = true
                break
            }
        }
        // Hyperspace: CONSTRUCT_SHIP_SYSTEM ends the constructor, before AddSystem's InstantPowerShields
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSystemState.firePending()
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
        val system = shields ?: run {
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSuperShield.detachedInstantCharge(this)
            return
        }
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

    /** Hyperspace render state of this ship (hs.render: layout thrusters). */
    @JvmField var hsRender: Any? = null

    private fun drawThrusters(g: Graphics, alpha: Float = renderAlpha) {
        // Hyperspace: SHIP_ENGINES render event and the layout's <thrusters> (Ship::RenderEngineAnimation)
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.render.HsThrusters.render(this, g, thrusterOffsets != null, alpha) { drawVanillaThrusters(alpha) }
            return
        }
        drawVanillaThrusters(alpha)
    }

    private fun drawVanillaThrusters(alpha: Float) {
        val offsets = thrusterOffsets ?: return
        val sheet = sys.getImg("img/effects/thrusters_on.png")
        for ((i, offset) in offsets.withIndex()) {
            val written = if (i == 0) hsEngineAnim else null
            val frame = written?.frame() ?: (thrusterTimes[i] / 0.125f).toInt().coerceAtMost(3)
            sheet.getSubImage(frame * 22, 0, 22, 70).draw(
                (written?.x() ?: (hullOffset.x + offset.x)), (written?.y() ?: (hullOffset.y + offset.y)), Colour(1f, 1f, 1f, alpha))
        }
    }

    /** What Lua sees of engineAnim: a write of its frame or position reaches the first thruster. */
    interface EngineAnimView {
        fun frame(): Int
        fun x(): Int
        fun y(): Int
        fun updated(time: Float)
        fun time(): Float
    }
    @JvmField var hsEngineAnim: EngineAnimView? = null
    /** Native position of the first thruster, from the hull image's place. */
    fun hsEngineAnimPos(): ConstPoint? = thrusterOffsets?.let { ConstPoint(hullOffset.x + it[0].x, hullOffset.y + it[0].y) }
    /** Its loop time: where a view of it starts. */
    fun hsEngineAnimTime(): Float = thrusterTimes[0]

    /** Ship::OnLoop: the engine animations run with the game, not while it is paused. */
    private fun updateThrusters(dt: Float) {
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.render.HsThrusters.update(this, dt)) return
        hsEngineAnim?.let { thrusterTimes[0] = it.time() }
        for (i in thrusterTimes.indices) thrusterTimes[i] = (thrusterTimes[i] + dt) % 0.5f
        hsEngineAnim?.updated(thrusterTimes[0])
    }

    /** ShipGraph::shipBox moved by a script: the hull, cloak and floor images are drawn from it. */
    @JvmField var hsBoxShiftX = 0
    @JvmField var hsBoxShiftY = 0

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
        // Hyperspace SHIP (CompleteShip::OnRenderShip) around SHIP_JUMP (Ship::OnRenderJump)
        val luaShip = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.preShip(xyz.znix.xftl.hs.lua.LuaApi.RE_SHIP, this) else 0
        if (luaShip >= 0) {
            val luaJump = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.preShipJump(this, fade) else 0
            if (luaJump >= 0) renderJumpInner(g, fade)
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.postShipJump(this, luaJump)
        }
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.postShip(xyz.znix.xftl.hs.lua.LuaApi.RE_SHIP, this, luaShip)
    }

    private fun renderJumpInner(g: Graphics, fade: Float) {
        val alpha = 1f - fade
        val previousAlpha = renderAlpha
        renderAlpha = alpha

        xyz.znix.xftl.weapons.ProjectileRuntime.renderSmoke(g, this)
        for (proj in projectiles) {
            if (xyz.znix.xftl.weapons.ProjectileRuntime.rendersInLayer(proj, 2)) {
                xyz.znix.xftl.weapons.ProjectileRuntime.render(proj, g, this)
            }
        }
        // SpaceManager's render pass (0x41a8b0, layer 2, before the ship): every drone of this space draws here too,
        // and again over the ship (measured: OnRender in both passes, native 1.6.14 drone-order probe).
        for (drone in xyz.znix.xftl.drones.DroneRuntime.renderOrder(externalDrones)) drone.renderExternal(g)

        if (!isDead) {
            renderShields(g)
            renderShieldHits(g)
        }

        hullImage?.draw(hullOffset.x + hsBoxShiftX, hullOffset.y + hsBoxShiftY, Colour(1f, 1f, 1f, alpha))

        val progress = InGameState.browserJumpFlareProgress(fade)
        drawThrusters(g, 1f - progress.coerceAtMost(1f))

        val starSize = InGameState.browserJumpFlareSize(progress)
        if (starSize > 0f) {
            val (x, y) = jumpFlareCentre(progress)
            sys.jumpFlare.draw(x - starSize / 2f, y - starSize / 2f, starSize, starSize)
        }

        renderAlpha = previousAlpha

        for (proj in projectiles) {
            if (xyz.znix.xftl.weapons.ProjectileRuntime.rendersInLayer(proj, 1)) {
                xyz.znix.xftl.weapons.ProjectileRuntime.render(proj, g, this)
            }
        }
        // Cross-space beams stored in the other ship's projectiles draw their target part here.
        BeamProjectile.renderInbound(g, this)
        for (drone in xyz.znix.xftl.drones.DroneRuntime.renderOrder(externalDrones)) {
            drone.renderExternal(g)
        }
    }

    fun render(g: Graphics, interiorVisible: Boolean, selected: Room?) {
        // Hyperspace SHIP (CompleteShip::OnRenderShip) and SHIP_MANAGER (ShipManager::OnRender) render events
        if (xyz.znix.xftl.hs.HS.luaActive) {
            val s = xyz.znix.xftl.hs.render.HsRenderEvents.preShip(xyz.znix.xftl.hs.lua.LuaApi.RE_SHIP, this)
            if (s >= 0) {
                val i = xyz.znix.xftl.hs.render.HsRenderEvents.preShipManager(this, interiorVisible, false)
                if (i >= 0) renderManager(g, interiorVisible, selected)
                xyz.znix.xftl.hs.render.HsRenderEvents.postShipManager(this, i)
            }
            xyz.znix.xftl.hs.render.HsRenderEvents.postShip(xyz.znix.xftl.hs.lua.LuaApi.RE_SHIP, this, s)
            return
        }
        renderManager(g, interiorVisible, selected)
    }

    private fun renderManager(g: Graphics, interiorVisible: Boolean, selected: Room?) {
        // Draw departing shots, under the shield bubble as the original layers them (a missile leaving the
        // player's ship is tinted by the bubble). We'll draw the rest of them later.
        xyz.znix.xftl.weapons.ProjectileRuntime.renderSmoke(g, this)
        for (proj in projectiles) {
            if (xyz.znix.xftl.weapons.ProjectileRuntime.rendersInLayer(proj, 2)) {
                xyz.znix.xftl.weapons.ProjectileRuntime.render(proj, g, this)
            }
        }
        // SpaceManager's render pass (0x41a8b0, layer 2, before the ship): every drone of this space draws here too,
        // and again over the ship (measured: OnRender in both passes, native 1.6.14 drone-order probe).
        for (drone in xyz.znix.xftl.drones.DroneRuntime.renderOrder(externalDrones)) drone.renderExternal(g)

        // Draw the shield and the flashes of the shots it stopped; a destroyed ship has none.
        if (!isDead) {
            renderShields(g)
            renderShieldHits(g)
        }

        // ShipManager::OnRender draws only the wreck for a destroyed ship: no systems, weapons or artillery
        // in place (the weapons ride their gibs, see renderDestruction)
        if (!isDead) for (system in systems)
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
            // Hyperspace SHIP_HULL render event around the hull, cloak and engines of Ship::OnRenderBase
            val luaHull = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.preShipHull(this, cloakFade) else 0
            if (luaHull >= 0) {
            hullImage?.draw(hullOffset.x + hsBoxShiftX, hullOffset.y + hsBoxShiftY, Colour(1f, 1f, 1f, hullAlpha))
            drawThrusters(g)

            if (cloakFade != 0f) {
                // A mod's ship can lack the image (Multiverse's STL_PANTS): the ship then just draws no cloak.
                cloakImage?.draw(
                    hullOffset.x + cloakOffset.x + hsBoxShiftX, hullOffset.y + cloakOffset.y + hsBoxShiftY,
                    Colour(1f, 1f, 1f, cloakFade)
                )
            }
            }
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.postShipHull(this, luaHull)

            // Hyperspace cloakRenderFix (CustomShips.cpp, Ship::OnRenderBase / ShipManager::OnRender hooks): a cloaked
            // player ship with no one aboard loses its floor image unless the sensors work, and its interior (hidden
            // natively while cloaked without player presence, ShipManager +0x7ee) shows again while they do.
            var drawFloor = true
            var hideInterior = false
            if (xyz.znix.xftl.hs.HS.active && isPlayerShip && isCloakActive && xyz.znix.xftl.hs.HS.cfg().cloakRenderFix) {
                val sensorsOn = sensors?.providesPlayerVision == true
                val ownCrew = crew.any { it is LivingCrew && it.ownerShip === this }
                drawFloor = ownCrew || sensorsOn
                hideInterior = !hasPlayerPresence && !sensorsOn
            }
            if (interiorVisible && !hideInterior) {
                drawInterior(g, selected, interiorAlpha, drawFloor)
            } else if (interiorVisible && drawFloor) {
                floorImage?.draw(floorOffset.x + hullOffset.x + hsBoxShiftX, floorOffset.y + hullOffset.y + hsBoxShiftY, Colour(1f, 1f, 1f, interiorAlpha))
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
            if (xyz.znix.xftl.weapons.ProjectileRuntime.rendersInLayer(proj, 1)) {
                xyz.znix.xftl.weapons.ProjectileRuntime.render(proj, g, this)
            }
        }

        // Cross-space beams stored in the other ship's projectiles draw their target part here.
        BeamProjectile.renderInbound(g, this)

        // Draw any drones flying around the ship
        for (drone in xyz.znix.xftl.drones.DroneRuntime.renderOrder(externalDrones)) {
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

        // Hyperspace room animations of layers 3 and 4 (ShipManager::OnRender hook, after the original)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsRoomAnims.renderShip(g, this, interiorVisible)
    }

    /** A hit on the shield bubble (Shields' ShieldAnimation list, +0x204): a white flash along the bubble. */
    private class ShieldHit(var x: Float, var y: Float, val owner: Any?) {
        var size = 0f
        var thickness = 0f
        var nativeId: Int? = null
        var nativeLayers: Int = 0
    }

    private val shieldHits = ArrayList<ShieldHit>()

    /** Shields::CollisionReal 1.6.9 60d980 /1.6.14 6336f0: an existing flash also remembers this shot's layer count. */
    internal fun beginNativeShieldHit(pos: xyz.znix.xftl.math.FPoint, selfId: Int, layers: Int): Int? {
        val old = shieldHits.firstOrNull { it.nativeId == selfId }
        if (old != null) {
            old.x = pos.xf; old.y = pos.yf
            old.size = SHIELD_HIT_SIZE - 16f * sys.renderingDeltaTime * 2f * SHIELD_HIT_SPEED
            return old.nativeLayers
        }
        shieldHits += ShieldHit(pos.xf, pos.yf, null).also { it.nativeId = selfId; it.nativeLayers = layers }
        return null
    }

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

    /**
     * Shields::OnRenderBaseShield: the bubble, then on the player's ship the hole a lost layer opens (the bubble as it was
     * except inside a growing circle) or the circle a new super shield opens from.
     */
    private fun renderShields(g: Graphics) {
        val sh = shields
        if (sh != null && xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaAdaptersWritesPictures.shieldsIn(sh)
        renderShieldBase(g, sh?.activeShields ?: 0, superShield, maxSuperShield, 1f)
        if (sh == null || !isPlayerShip) return
        if (!sh.superUpTracker.running) {
            if (!sh.downTracker.running) return
            val progress = sh.downTracker.progress
            Utils.drawStenciled(Utils.StencilMode.BLOCKING, { renderShieldHole(g, sh.downX, sh.downY, progress) }) {
                if (sh.superShieldDown) renderShieldBase(g, 0, 5, 5, 1f)
                else renderShieldBase(g, 1, 0, 5, 1f)
            }
        } else {
            val progress = sh.superUpTracker.progress
            val over = progress - 0.5f
            val fade = if (over > 0f) 1f - (over + over) else 1f
            Utils.drawStenciled(Utils.StencilMode.MASKING, { renderShieldHole(g, sh.superUpX, sh.superUpY, progress) }) {
                renderShieldBase(g, 0, superShield, maxSuperShield, fade)
            }
        }
    }

    /** The stencil primitive of Shields::OnRenderBaseShield, scaled about the point it opens from. */
    private fun renderShieldHole(g: Graphics, x: Int, y: Int, scale: Float) {
        g.pushTransform()
        g.translate(x.f, y.f)
        g.scale(scale, scale)
        g.colour = Colour.white
        g.fillOval(-1000f, -1000f, 2000f, 2000f)
        g.popTransform()
    }

    /** Shields::RenderBase with these power values: the layers' bubble (grown by shieldsUp), then the super shield. */
    private fun renderShieldBase(g: Graphics, level: Int, superShield: Int, maxSuperShield: Int, alphaScale: Float) {
        // Draw the standard (non-Zoltan) shield
        val shieldMax = if (isPlayerShip) 3.5f else 4f // This is odd, but correct
        val levelFraction = level / shieldMax
        val alpha = SHIELD_OPACITY_BASE + SHIELD_OPACITY_SCALING * levelFraction

        if (level == 0) {
            // Do nothing, shields disabled
        } else {
            // Native: a stopped shieldsUp hides the bubble (oracle hw-o2v).
            val up = shields?.upTracker?.let { if (it.running) it.progress else 0f } ?: 1f
            if (up < 1f) {
                g.pushTransform()
                g.translate(shieldOrigin.x.f, shieldOrigin.y.f)
                g.scale(up, up)
                g.translate(-shieldOrigin.x.f, -shieldOrigin.y.f)
                renderSingleShield(alpha * alphaScale, Colour.white)
                g.popTransform()
            } else
            renderSingleShield(alpha * alphaScale, Colour.white)
        }

        // Draw the super shields
        if (superShield != 0) {
            var superShieldAlpha = superShield.f / maxSuperShield

            if (level == 0) {
                superShieldAlpha = SHIELD_OPACITY_BASE + SHIELD_OPACITY_SCALING * superShieldAlpha
            }

            // Hyperspace: an augment super shield's colour and image (Shields::RenderBase, CustomSuperShield.cpp).
            val look = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSuperShield.bubble(this) else null
            if (look != null) {
                val c = look.color!!
                val path = look.image[if (isPlayerShip) 0 else 1]
                renderSingleShield(superShieldAlpha * c.a * alphaScale, Colour(c.r, c.g, c.b), path?.let { sys.getImg("img/$it") })
            } else
            renderSingleShield(superShieldAlpha * alphaScale, SYS_ENERGY_ACTIVE)

            // TODO fix the ugly darkened line around the edge of enemy super-shields
            // I suspect this is caused by the scaling - Slick has had problems
            // rendering drones while rotating with white aliasing artefacts
            // along their edges, so maybe it's the same thing?
            // Or it could possible be some kind of SRGB-related issue.
        }
    }

    /** Hyperspace Lua: Shields::shieldImage or shieldPrimitive written by a script: what the bubble draws. */
    @JvmField var hsShieldImage: Image? = null

    private fun renderSingleShield(alpha: Float, filter: Colour, image: Image? = null) {
        val shieldAlpha = alpha * renderAlpha
        val basePosX = shieldOrigin.x - shieldHalfSize.x
        val basePosY = shieldOrigin.y - shieldHalfSize.y
        val shieldImage = image ?: hsShieldImage ?: this.shieldImage

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
            val suffix = if (target.isAutofire) "_yellow" else ""
            // Hyperspace WeaponControl::GetAimingPrimitive (AdditionalWeaponSlots.cpp): slots 5 to 10 have their own
            // crosshairs, any further slot the fallback set.
            val name = if (xyz.znix.xftl.hs.HS.active) {
                if (target.weaponNumber < 10) "crosshairs_placed${target.weaponNumber + 1}" else "crosshairs_placed_fallback"
            } else {
                "crosshairs_placed${min(target.weaponNumber + 1, 4)}"
            }
            val img = sys.getImg("img/misc/${name}${suffix}.png")

            val pos = Point(room.pixelCentre)

            // a weapon with a radius (flak's spread) first draws a red disc of it at a quarter opacity around the target
            val radius = target.weapon.aimRadius
            if (radius != 0) {
                val previous = g.colour
                g.colour = Colour(1f, 0f, 0f, 0.25f)
                g.fillOval(pos.x - radius.f, pos.y - radius.f, 2f * radius, 2f * radius)
                g.colour = previous
            }

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

        // Hyperspace WeaponControl::RenderAimingNew: the player's targetable artillery aims.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsArtillery.renderAims(this, g)
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
        get() = crew.any { it.belongsToPlayer == true || (it as? LivingCrew)?.isMindControlled == true }

    /**
     * Can the player pick one of this ship's rooms (weapon aim, beam start, teleporter, hacking, mind control)?
     * The hover pass of CombatControl::MouseMove (0x57dd70) and CombatControl::SelectRoom (0x57eba0) select no
     * room on a dying ship (+0x499) or on a cloaked one (vtable +0x10, 0x49b100) without [hasPlayerPresence].
     */
    val roomsSelectableByPlayer: Boolean
        get() = !isDead && !(isCloakActive && !hasPlayerPresence)

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
            if (sys.player?.crew?.any { it.detectsLifeforms && (it as? xyz.znix.xftl.crew.LivingCrew)?.ownerShip === sys.player } == true)
                return true
            if (sys.enemy?.crew?.any { it.detectsLifeforms && (it as? xyz.znix.xftl.crew.LivingCrew)?.ownerShip === sys.player } == true)
                return true

            // The scanner only senses lifeforms aboard enemy vessels.
            return !isPlayerShip && sys.player?.hasAugment(AugmentBlueprint.LIFE_SCANNER) == true
        }

    private fun drawInterior(g: Graphics, selected: Room?, alpha: Float, drawFloor: Boolean = true) {
        if (drawFloor) floorImage?.draw(
            floorOffset.x + hullOffset.x + hsBoxShiftX,
            floorOffset.y + hullOffset.y + hsBoxShiftY,
            Colour(1f, 1f, 1f, alpha)
        )

        // Draw the rooms. Plain: each room in one go. While a Lua script listens to SHIP_FLOOR, SHIP_BREACHES or
        // SHIP_SPARKS, FTL's stages run over all rooms in turn (Ship::OnRenderFloor, the systems,
        // Ship::OnRenderBreaches, Ship::OnRenderSparks, then the rest) with their render events around them.
        fun isSelected(room: Room): Boolean {
            // If the user is currently aiming a beam, highlight
            // the rooms it would hit.
            return selected == room || inboundBeamAim?.hitRooms?.contains(room) == true
        }

        val ren = xyz.znix.xftl.hs.render.HsRenderEvents
        val lua = xyz.znix.xftl.hs.lua.LuaApi
        if (xyz.znix.xftl.hs.HS.luaActive && (ren.has(lua.RE_SHIP_FLOOR) || ren.has(lua.RE_SHIP_BREACHES) || ren.has(lua.RE_SHIP_SPARKS))) {
            val floor = ren.preShip(lua.RE_SHIP_FLOOR, this)
            if (floor >= 0) for (room in rooms) room.renderFloorPart(g, alpha)
            ren.postShip(lua.RE_SHIP_FLOOR, this, floor)

            for (room in rooms) room.renderSystemPart(g)

            val breaches = ren.preShip(lua.RE_SHIP_BREACHES, this)
            if (breaches >= 0) for (room in rooms) room.renderBreachPart(g)
            ren.postShip(lua.RE_SHIP_BREACHES, this, breaches)

            val sparks = ren.preShip(lua.RE_SHIP_SPARKS, this)
            if (sparks >= 0) for (room in rooms) room.renderSparkPart(g, true)
            ren.postShip(lua.RE_SHIP_SPARKS, this, sparks)

            for (room in rooms) room.renderRest(g, isSelected(room), alpha, false, renderAlpha)
        } else {
            for (room in rooms) room.render(g, isSelected(room), alpha, renderAlpha)
        }

        // Draw the doors
        for (door in doors) {
            door.render(g, alpha)
        }
        // Ship::OnRenderWalls: the lockdown shards after the doors
        for (shard in lockdownShards) shard.render(g, alpha)
        // then the systems' icons (Room.renderSystemIcon)
        for (room in rooms) room.renderSystemIcon(g)

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

        // Hyperspace: the player's queued temporal target on this ship.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsTemporal.renderQueued(this, g)
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
    /** Diagnostics: the wreck's tracker time (s). */
    val diagnosticDestructionTime: Float get() = destructionTime

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
        if (destructionTime == 0f) destructionJumpOut = invincible
        if (destructionTime >= destructionEnd)
            return
        val before = destructionTime / destructionEnd
        destructionTime = min(destructionTime + dt, destructionEnd)
        // ExplosionAnimation::OnLoop: a jump-out plays jumpLeave as its tracker passes 0.75 (3 s of 4)
        if (destructionJumpOut && before < JUMP_OUT_PROGRESS && destructionTime / destructionEnd >= JUMP_OUT_PROGRESS)
            sys.sounds.getSampleOrWarn("jumpLeave")?.play()

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

    // GetAnimation("explosion_random") picks explosion_big2-4 in that call (0x5a6450), on the wreck's visual stream.
    private fun randomBigExplosion(): AnimationSpec = sys.animations.getOrNull("explosion_random", VisualRandom)!!

    /**
     * The wreck (ShipManager::OnRender draws nothing else for a destroyed ship): ExplosionAnimation::
     * OnRender, the gibs (last first) with the weapons mounted on each (WeaponAnimation::OnRender in the
     * gib's matrix, under its image), the whole hull on top until it blows apart, then the explosions until the tracker
     * reaches 3 s. A jump-out (bJumpOut) leaves its last gib out past 0.75 and draws Ship::OnRenderJump((p - 0.75) x 4).
     */
    private fun renderDestruction(g: Graphics) {
        val progress = destructionTime / destructionEnd
        val jumping = destructionJumpOut && progress > JUMP_OUT_PROGRESS
        // Hyperspace artilleryGibMountFix (CustomShips.cpp ExplosionAnimation::OnRender): each gib carries the artillery
        // mounted on it (the layout's mount gib number, one-based), drawn under the gib's own image
        val onGibs = hsArtilleryOnGibs()
        for (index in gibs.indices.reversed()) {
            if (jumping && index == gibs.size - 1) continue
            val weaponsHere = hardpoints.any { it.weapon != null && it.spec.gib == index + 1 }
            val artilleryHere = onGibs && artillery.any { it.hsMountGib == index + 1 }
            val mounted: ((Graphics) -> Unit)? = if (weaponsHere || artilleryHere) {
                { gg ->
                    for (hp in hardpoints) if (hp.spec.gib == index + 1) hp.weapon?.let { renderWeaponOnGib(gg, hp, it) }
                    if (artilleryHere) for (a in artillery) if (a.hsMountGib == index + 1) a.renderOnGib(gg)
                }
            } else null
            gibs[index].draw(g, hullOffset, type.hullOffset, mounted)
        }
        // Hyperspace hideHullDuringExplosion (CustomShips.cpp ExplosionAnimation::OnRender): no hull while the gibs wait
        val hideHull = xyz.znix.xftl.hs.HS.active &&
                (xyz.znix.xftl.hs.HS.cfg().hideHullDuringExplosion || xyz.znix.xftl.hs.ship.HsShips.def(type.name).hideHullDuringExplosion)
        if (!destructionSoundPlayed && !hideHull)
            hullImage?.draw(hullOffset.x, hullOffset.y)
        if (destructionTime < DESTRUCTION_TIME) {
            for (explosion in destructionExplosions)
                explosion.render(g)
        }
        if (jumping) renderJumpOut(g, (progress - JUMP_OUT_PROGRESS) * 4f)
    }

    /** A weapon's launcher on its gib (Weapons' placement, slide included), without the charge bar. */
    private fun renderWeaponOnGib(g: Graphics, hp: Hardpoint, weapon: AbstractWeaponInstance) {
        val spec = hp.spec
        val anim = weapon.animation
        g.pushTransform()
        if (spec.slide != null) {
            val dist = (12 * (weapon.slide - 1)).roundToInt()
            g.translate(spec.slide.x * dist.toFloat(), spec.slide.y * dist.toFloat())
        }
        // The mounts draw from Ship::shipImage's corner, ahead of the mount's own rotation (a write moves the weapons, not the hull).
        g.translate(hsHullShiftX.toFloat(), hsHullShiftY.toFloat())
        xyz.znix.xftl.systems.Weapons.translateHardpoint(g, spec)
        g.translate(-anim.mountPoint.x.toFloat() - weapon.hsMountShiftX, -anim.mountPoint.y.toFloat() - weapon.hsMountShiftY)
        weapon.render(g)
        g.popTransform()
    }

    /**
     * Ship::OnRenderJump of a jump-out wreck: the whole hull at alpha 1 - fade, the player's thrusters and
     * the flash, inside Hyperspace's SHIP_JUMP render event. Hyperspace artilleryGibMountFix (CustomShips.cpp
     * Ship::OnRenderJump): the enemy flagship at stage 1 or 2 first draws the artillery of its last gib, tinted
     * 1 - fade, translated by the hull image offset less that gib's position.
     */
    private fun renderJumpOut(g: Graphics, fade: Float) {
        val lua = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.preShipJump(this, fade) else 0
        if (lua >= 0) {
            val flagship = boss as? xyz.znix.xftl.game.FlagshipBoss
            if (hsArtilleryOnGibs() && gibs.isNotEmpty() && !isPlayerShip && flagship != null && (flagship.stage == 1 || flagship.stage == 2)) {
                val last = gibs.size
                val pos = gibs[last - 1].position
                g.pushTransform()
                g.translate(type.hullOffset.x - pos.xf, type.hullOffset.y - pos.yf)
                val previous = renderAlpha
                renderAlpha = 1f - fade
                for (a in artillery) if (a.hsMountGib == last) a.renderOnGib(g)
                renderAlpha = previous
                g.popTransform()
            }
            hullImage?.draw(hullOffset.x, hullOffset.y, Colour(1f, 1f, 1f, 1f - fade))
            val flare = InGameState.browserJumpFlareProgress(fade)
            drawThrusters(g, 1f - flare.coerceAtMost(1f))
            val starSize = InGameState.browserJumpFlareSize(flare)
            if (starSize > 0f) {
                val (x, y) = jumpFlareCentre(flare)
                sys.jumpFlare.draw(x - starSize / 2f, y - starSize / 2f, starSize, starSize)
            }
        }
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.render.HsRenderEvents.postShipJump(this, lua)
    }

    /**
     * Hyperspace artilleryGibMountFix: a wrecked ship's artillery is drawn on the gib its mount belongs to, moving with it,
     * and no longer in place (the global `<artilleryGibMountFix>` or the ship's own definition).
     */
    internal fun hsArtilleryOnGibs(): Boolean =
        isDead && xyz.znix.xftl.hs.HS.active && artillery.isNotEmpty() &&
                (xyz.znix.xftl.hs.HS.cfg().artilleryGibMountFix || xyz.znix.xftl.hs.ship.HsShips.def(type.name).artilleryGibMountFix)

    private fun updateDestructionSounds(dt: Float) {
        if (destructionSoundTimer >= 0f) {
            destructionSoundTimer -= dt
            if (destructionSoundTimer >= 0f)
                return
        }
        sys.sounds.getSampleOrWarn("explosion${ftl.browser.BrowserRandom.nextInt(4) + 1}")?.play()?.volume = 0.25f
        destructionSoundTimer = (ftl.browser.BrowserRandom.nextInt(3) + 1) * 0.3f
    }

    /**
     * Once a save is loaded: the weapons' and drones' power as native ShipManager::ImportShip leaves it. The rooms'
     * crew lists are filled first: they are otherwise refreshed only in [Room.update], after the first
     * [updateAvailablePower], so the first update after a load saw no Zoltan in any room, dropped every Zoltan bar
     * and switched off the weapons those bars held (native ImportShip adds the crew in their rooms with the ship).
     */
    fun afterSaveLoad() {
        for (room in rooms) room.updateCrewInRoom()
        weapons?.powerManager?.afterSaveLoad()
        drones?.powerManager?.afterSaveLoad()
    }

    fun update(dt: Float) {
        updateThrusters(dt)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.systems.Teleporter.hsRecallOnLoop(this) // HS TELEPORT_RECALL
        if (xyz.znix.xftl.hs.HS.active && this === sys.player) xyz.znix.xftl.hs.crew.HsCustomTeleport.onLoop(sys) // HS teleportMove
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.systems.Hacking.hsShipSoundLoop(this) // HS hackLoopHS
        if (dt > 0f && health <= 0) nativeDestroyed = true
        // Hyperspace Lua: Spreader<Fire>::count / roomCount are recounted by the spreader's loop, not by SetFire
        // (oracle wp5-ship: StartFire while paused leaves count at 0)
        if (dt > 0f && hs != null) {
            // Lua SetSystemPowerLoss: the stored loss reaches CheckMaxPower in this loop
            hs?.lua?.pendingLoss?.let { p -> if (p.isNotEmpty()) { for ((sys, loss) in HashMap(p)) sys.scriptedPowerLoss = loss; p.clear() } }
            xyz.znix.xftl.hs.ship.HsNavAugments.nanobots(this, dt)
            hs?.lua?.let {
                it.lastEngineStatus = xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.engineStatus(this)
                // ShipManager::CheckVision reveals and clears every tempVision flag; OnLoop
                // zeroes damageCloaked while the ship is not cloaked (oracle sweep-world-ys1: 0 a second after a write)
                it.tempVision.clear()
                if (!isCloakActive) it.ints.remove(xyz.znix.xftl.hs.lua.LuaApi.A_ShipManager_damageCloaked)
            }
            hsFireRoomCount = IntArray(rooms.size) { i -> rooms[i].fires.count { it != null } }
            hsFireCount = hsFireRoomCount.sum()
        }
        // Hyperspace ShipManager::OnLoop (CustomCrew.cpp) sets every system's bonus power from its crew again, and
        // the loop reconciles battery bars a script forced: the Lua per-call values end here. The weapons do it
        // through their RemoveBatteryPower (oracle wp4-power-calls: the last weapon goes off, its bars taken from
        // the battery, so its reactor bars stay with the system).
        for (s in systems) if (s is xyz.znix.xftl.systems.MainSystem) {
            s.hsLuaBonusPower = null
            // Weapons and drones: RemoveBatteryPower (ShipManager's battery check 0x49c510 calls it while a system
            // holds battery bars over the battery's supply) once per loop while bars are left. It takes bars only
            // from the items it switches off, so bars can stay (oracle wp4-power-calls: two battery bars on two
            // drones turn one drone off per call and end at 0; on the weapons a cost-2 weapon takes both at once
            // and the weapon still on keeps its reactor bar).
            val manager = when (s) {
                is xyz.znix.xftl.systems.Weapons -> s.powerManager
                is xyz.znix.xftl.systems.Drones -> s.powerManager
                else -> null
            }
            val battery = s.hsLuaBatteryPower
            s.hsLuaBatteryPower = null
            if (manager != null && battery != null && battery > 0)
                s.hsLuaBatteryPower = manager.hsRemoveBatteryPower(battery).takeIf { it > 0 }
        }
        for (h in hardpoints) h.weapon?.hsLuaAmmo = null
        // Update the power, in case a Zoltan has moved between rooms.
        updateAvailablePower()
        // Hyperspace room animations (ShipManager::OnLoop hook: RoomAnim::OnUpdate)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsRoomAnims.update(this, dt)

        updateShieldHits(dt)

        updateExterior(dt)

        if (isDead) {
            // Hyperspace: TemporalSystem::_HS_ShipDestroyed ends a running dilation.
            if (xyz.znix.xftl.hs.HS.active) (systems.firstOrNull { it is xyz.znix.xftl.hs.systems.Temporal } as xyz.znix.xftl.hs.systems.Temporal?)?.shipDestroyed()
            updateDestruction(dt)
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.shipLoop(this)
            return
        }

        // Hyperspace OxygenSystem::OnLoop: CALCULATE_LEAK_MODIFIER for this frame's breaches and airlocks.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsOxygen.beginFrame(this)
        for (room in rooms)
            room.update(dt)
        // Hyperspace ShipManager::UpdateEnvironment hook (ErosionEffect.cpp): eroding rooms.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsErosionRoom.update(this, dt)

        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsHackDoors.apply(this)

        // Update the door open/close animations
        for (door in doors) {
            door.update(dt)
        }
        // Ship::OnLoop (0x494af0): the lockdown shards after the doors and rooms; a finished one is erased
        if (lockdownShards.isNotEmpty()) {
            for (shard in lockdownShards) shard.update(dt)
            lockdownShards.removeAll { it.done }
        }

        // Move oxygen around the ship
        OxygenTransfer.update(this, dt)

        averageOxygen = rooms.map { it.oxygen }.average().toFloat()

        crewAI.update()
        intruderAI.update()

        checkSkills()

        updateCrew(dt)

        // ShipManager::OnLoop hook (StatBoost.cpp): room stat boost timers.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.stat.StatBoostManager.updateRoomBoosts(this, dt)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsPowerDrain.update(this)

        // This FTL charge logic is for the player; enemies have a completely
        // separate charge time stored in ShipAI.
        if (canChargeFTL && !browserJumping) {
            // WorldManager boosts the charge (SetBoostFTL) in a hostile
            // environment with no enemy ship; without danger the drive is
            // simply full (InGameState).
            val boost = sys.getEnemyOf(this) == null
            val rate = engines?.ftlChargePerUnit(boost) ?: 0f
            ftlChargeProgress += rate * 16f * dt / Engines.FTL_CHARGE_MAX * (85f / hsJumpGoal)
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

        // Hyperspace: the tail of TemporalSystem.cpp's ShipManager::OnLoop hook (room animations).
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsTemporal.afterShipUpdate(this, dt)
        // Hyperspace: ShipManager::OnLoop (CustomCrew.cpp) restarts every system's additionalPowerLoss at the crew's
        // POWER_DRAIN and runs CheckMaxPower and CheckForRepower on every system; SHIP_LOOP comes after it (its hook has
        // priority -100, InternalEvents.cpp). A script's addition there takes power only when the script calls
        // CheckMaxPower itself (Multiverse's sys-limit.lua does); otherwise it only lowers GetMaxPower until the next
        // loop (HS oracle ps-c1-ex: engines and shields keep 8 bars under an added loss of 1 and 3, GetMaxPower 7 and 5).
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSysExt.resetLoop(this)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSysExt.commit(this)
        // Hyperspace SHIP_LOOP: ShipManager::OnLoop hook, after the original
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.shipLoop(this)
    }

    private fun updateCrew(dt: Float) {
        // Duplicate the crew and drone lists, in case some are removed
        // by dying or (in the case of drones) the enemy ship blowing up.
        for (crew in crew.toTypedArray()) {
            // Hyperspace: crew loops run at their room's temporal dilation (TemporalSystem.cpp UpdateCrewmembers).
            crew.update(if (xyz.znix.xftl.hs.HS.active) dt * xyz.znix.xftl.hs.systems.HsTemporal.crewFactor(crew, this) else dt)
            // Hyperspace CREW_LOOP: CrewMember::OnLoop hook, after the original
            if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.crewLoop(crew)
        }

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
            val projectile = projectiles[i]
            if (projectile is AbstractProjectile &&
                (xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(projectile) || projectile.updatesInProjectileLoop)) continue
            projectile.update(dt, this)
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

                if ((first is AbstractProjectile && xyz.znix.xftl.weapons.ProjectileRuntime.handlesContacts(first)) ||
                    (second is AbstractProjectile && xyz.znix.xftl.weapons.ProjectileRuntime.handlesContacts(second))) continue

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

                if (!IProjectile.endpointContact(first.position, second.position)) {
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

                if (proj is AbstractProjectile && xyz.znix.xftl.weapons.ProjectileRuntime.handlesContacts(proj)) continue

                if (!proj.collisionsEnabled)
                    continue

                if (!IProjectile.endpointContact(proj.position, drone.flightController.position)) {
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

        // Update the crew combat projectiles (they deal their damage on arrival, then play the impact puff).
        crewShots.removeAll { shot -> !shot.update(dt) }

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
        sys.currentBeacon.powerEnvironment(sys)?.adjustPowerStack(this, powerAvailableTypes)
        // Hyperspace: the PowerManager's cap / loss / divisor (reactor statuses and Lua) limit the reactor.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsPower.apply(this, powerAvailableTypes)
        // Hyperspace: a system a second AddSystem displaced keeps its bars (HsSystemInstall.duplicate)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSystemInstall.ghostPower(this).takeIf { it > 0 }?.let { g ->
            powerAvailableTypes[ReactorEnergySource]?.let { r -> powerAvailableTypes[ReactorEnergySource] = maxOf(0, r - g) }
        }

        // Remember how much power is unavailable, as it's shown in the UI.
        val postEnvPower = powerAvailableTypes.values.sum()
        blockedReactorPower = max(0, preEnvPower - postEnvPower)
        if (hsReactorUsedOffset != 0) powerAvailableTypes[ReactorEnergySource]?.let { r ->
            powerAvailableTypes[ReactorEnergySource] = maxOf(0, r - hsReactorUsedOffset)
        }

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
            if (damagePos != null && damageValue > 0) shields?.startDown(damagePos.x, damagePos.y, true)

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
            if (damagePos != null) shields?.startDown(damagePos.x, damagePos.y, false)
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
    fun damage(target: Room, damage: Damage, textPos: IPoint? = null, rockArmourRoll: Boolean? = null,
               /** ShipManager::DamageArea's location: where a lockdown's shards start (the room centre when unknown). */
               hitPos: IPoint? = textPos) {
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
        // DamageArea rolls it on every hit, also one with negative hull damage (which it doesn't stop).
        val rockRoll = rockArmourRoll ?: rollRockArmour()
        val hullArmorNegates = damage.hullDamage >= 0 && rockRoll
        // Hyperspace replaces ShipManager::DamageArea (CustomCrew.cpp, priority 9999) with its own body; where
        // it differs from 1.6.14, the HS rule is noted below.
        val hsArea = xyz.znix.xftl.hs.HS.active

        // Reverse Ion Field (ION_ARMOR): a chance to negate the incoming
        // ion damage entirely. Hull, system and crew damage are all
        // unaffected. This covers weapon ion on any room (including ion
        // against the shield system itself) and ion bombs; the pulsar rolls
        // per-pulse via the same [ionArmourNegates] helper. On a proc
        // vanilla shows the same 'RESIST' popup as Rock Plating, tinted
        // with the ion damage-number blue; any hull/system components of
        // the hit still show their own numbers.
        // Hyperspace CustomShips.cpp ShipManager::DamageSystem: the room's sys/ion damage resist chances (Room_Extend)
        // roll before the native body (ion armour); DAMAGE_SYSTEM below still receives the hit as it came.
        val resisted = if (xyz.znix.xftl.hs.HS.active) damage.copy().also { xyz.znix.xftl.weapons.BeamDamageRules.hsSystemResist(this, target, it) } else damage
        val ionNegated = resisted.ionDamage > 0 && ionArmourNegates()
        val appliedIonDamage = if (ionNegated) 0 else resisted.ionDamage

        crewWeaponDamage(target, damage.effectiveCrewDamage.f, damage)

        // ShipManager::DamageArea (1.6.14 0x4a6100, 1.6.9 0x499fb0) rolls the fire, then (without one) the breach,
        // before the system and hull damage. Fire and breach are mutually exclusive. A Rock Plating proc stops a
        // breach, except a certain one (breachChance 10 exactly: Heavy Crystal II, the Anti-Ship Battery's shot).
        // In 1.6.14 that breach also ends the proc, so the hull damage goes through and no RESIST shows; Hyperspace's
        // DamageArea breaches and keeps the proc (no hull damage, RESIST).
        var rockProc = hullArmorNegates
        if (!sys.debugFlags.noDmg.set) {
            if (ftl.browser.BrowserRandom.rollChance(damage.fireChance)) {
                // Spawns two fires (or possibly only one, if they both roll on the same cell).
                target.spawnFire()
                target.spawnFire()
            } else if (ftl.browser.BrowserRandom.rollChance(damage.breachChance)) {
                if (!rockProc || damage.breachChance == 100) {
                    target.spawnBreach()
                    if (!hsArea) rockProc = false
                }
            }
        }

        // Titanium System Casing (SYSTEM_CASING): rolled after the breach, the same way as Rock Plating. A proc
        // from a hit with hull or system damage takes both out of the system damage. Ion damage, crew damage, fires
        // and breaches all still apply, and so does the hull damage. 1.6.14 rolls on every hit and ignores the proc
        // on a completely destroyed system; Hyperspace rolls only in a room with a system and has no such check.
        val casingSystem = target.system
        val systemCasingChance = getAugmentValueOrNull(AugmentBlueprint.SYSTEM_CASING)
        val casingRoll = systemCasingChance != null && (!hsArea || casingSystem != null) &&
                ftl.browser.BrowserRandom.nextInt(100) + 1 <= (systemCasingChance * 100).toInt()
        val casingNegates = casingRoll && casingSystem != null &&
                (hsArea || casingSystem.damagedEnergyLevels < casingSystem.energyLevels) &&
                (damage.hullDamage > 0 || damage.effectiveSysDamage > 0)

        // The numbers, in the native order: ShipManager::DamageSystem (1.6.14 0x4a4ab0) shows the ion armour's RESIST
        // or the ion number, then the system damage number (Damage.iSystemDamage: what a hit adds to its hull
        // damage) of the room's system; DamageArea then shows RESIST when Rock Plating stopped damage or the System
        // Casing took the system damage (the casing passes DamageSystem no hull or system damage), else the hull
        // number. Hyperspace's DamageArea shows its RESIST or hull number only for a hit with hull damage, so a
        // casing proc on system-only damage shows nothing there.
        if (ionNegated)
            target.showDamageText("text_resist", DAMAGE_COLOUR_ION, textPos)
        val shownSysDamage = if (casingNegates) 0 else resisted.effectiveSysDamage - damage.hullDamage.coerceAtLeast(0)
        if (target.system != null) {
            if (appliedIonDamage > 0) target.showDamageText(appliedIonDamage, DAMAGE_COLOUR_ION, textPos)
            if (shownSysDamage > 0) target.showDamageText(shownSysDamage, DAMAGE_COLOUR_SYSTEM, textPos)
        }
        if ((rockProc && damage.hullDamage > 0) || (casingNegates && (!hsArea || damage.hullDamage > 0)))
            target.showDamageText("text_resist", Colour.white, textPos)
        else if (!rockProc && hullDamage > 0)
            target.showDamageText(hullDamage, Colour.white, textPos)

        if (sys.debugFlags.noDmg.set)
            return

        if (!rockProc) {
            health -= hullDamage

            // The player's last hull damage drives the screen shake (ShipManager +0x49c).
            if (sys.player === this)
                sys.shakeForHullDamage(hullDamage)
        }
        // Hyperspace ShipManager::DamageSystem: DAMAGE_SYSTEM may pre-empt the system damage.
        if (!(xyz.znix.xftl.hs.HS.luaActive && target.system != null && xyz.znix.xftl.hs.weapons.HsWeaponsLua.damageSystem(this, target.id, damage)))
            target.system?.dealDamage(if (casingNegates) 0 else resisted.effectiveSysDamage, appliedIonDamage)
        if (damage.lockdown) {
            // ShipManager::DamageArea runs with currentLockdown = the weapon's customLockdown (CustomLockdowns.cpp Ship::LockdownRoom)
            val custom = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsDamage.current?.def?.lockdownDef else null
            if (custom != null) xyz.znix.xftl.hs.ship.HsLockdowns.lockdown(target, custom, hitPos?.x?.toFloat(), hitPos?.y?.toFloat())
            else target.lockdown(hitPos?.x?.toFloat(), hitPos?.y?.toFloat())
        }

        // Crystal Vengeance (CRYSTAL_SHARDS, 0x4a5df0): only hull damage that gets through rolls a shard.
        if (hullDamage > 0 && !rockProc) checkCrystalAugment(target.pixelCentre)
    }

    /**
     * ShipManager::CheckCrystalAugment (1.6.14 0x4a5df0): rolls rand%100 < value*100 to break off a CRYSTAL_1
     * shard at the ship being fought. It's a neutral projectile, so ANY defence drone (including ours - a vanilla
     * quirk) can shoot it. DamageArea passes the room's centre here and DamageBeam the beam's current point.
     */
    fun checkCrystalAugment(spawnPos: IPoint) {
        val enemy = sys.getEnemyOf(this)
        if (enemy != null && !enemy.isGone) {
            val shardChance = getAugmentValue(AugmentBlueprint.CRYSTAL_SHARDS)
            if (shardChance > 0f && ftl.browser.BrowserRandom.rollChance((shardChance * 100).toInt())) {
                val shard = VengeanceShardProjectile(enemy.rooms.random(ftl.browser.BrowserRandom))

                // Launch like a weapon does: aim far past the ±800 ship
                // space boundary (in the enemy-facing direction), so the
                // shard crosses it and switches into the enemy's space
                // before reaching its intermediate target.
                val endPos = spawnPos + weaponFireDirection * 5000
                shard.setInitialPath(spawnPos, endPos)
                projectiles.add(shard)
            }
            // Hyperspace: the <crystalShard> functions of custom augments (CheckCrystalAugment hook).
            if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsCrystalShards.check(this, enemy, spawnPos)
        }
    }

    fun playDamageEffect(type: AbstractWeaponBlueprint, position: IPoint): FloatingAnimation {
        val animation = sys.animations.orEmpty(type.explosion ?: "")
        return playCentredAnimation(animation, position)
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
        // Hyperspace ShipManager::DamageCrew (priority -100, before super): weapon stat boosts.
        // Hyperspace ShipManager::DamageCrew (CustomCrew.cpp): a crew power's damage never hurts its user.
        if (xyz.znix.xftl.hs.HS.active && dmg.hsSelfId != -1 && dmg.hsSelfId == xyz.znix.xftl.hs.crew.HsCrew.ex(crew).selfId)
            return false
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsDamage.crewDamaged(crew)
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
        if (hs != null) xyz.znix.xftl.hs.ship.HsEquipment.onJump(this)
        cargoUpdated()
        crew.filterIsInstance<xyz.znix.xftl.crew.CrewCrystal>().forEach { it.resetLockdown() }
        // ShipManager::JumpArrive: CrewMember::ResetPower (HS _HS_ResetPower) and the StatBoost.cpp hook.
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewContent.jumpArrive(this)
        // Shields::Jump (0x6340e0) only resets the hit list and the Zoltan shield (below): the layers are not
        // recharged, they keep their charge and recharge normally at the new beacon.

        // And reset the FTL drive
        ftlChargeProgress = 0f

        // Jumping clears the super shield (if the augment is
        // present, it'll add it back in afterwards).
        superShield = 0
        hsSuperShieldSet = 0

        // Remove all incoming and outgoing projectiles
        projectiles.clear()

        // Get rid of any drones orbiting us
        externalDrones.clear()

        resetWeaponsForJump()

        // Clear any previously-set scripted power limits, and re-apply
        // any that were previously set at this beacon.
        updateScriptedPowerLimits()
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsPower.arrive(this)

        for (augment in augmentValues.keys) {
            augment.onJump(this)
        }
        // Hyperspace: Shields::Jump fills an augment super shield (CustomSuperShield.cpp).
        if (xyz.znix.xftl.hs.HS.active && shields != null) xyz.znix.xftl.hs.systems.HsSuperShield.fill(this)

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
        // ShipManager::JumpArrive (0x49d490): after a beacon that was safe, MindSystem::ForceReady and LockSystem(0) on
        // the shields, engines, weapons and piloting end their ion locks and cooldowns (and the Hyperspace temporal
        // system's, TemporalSystem.cpp JumpArrive hook).
        if (browserWasSafe) {
            mindControl?.lockUnits(0)
            for (system in listOfNotNull(shields, engines, weapons, piloting)) {
                system.lockUnits(0)
            }
            if (xyz.znix.xftl.hs.HS.active)
                systems.filterIsInstance<xyz.znix.xftl.hs.systems.Temporal>().forEach { it.lockUnits(0) }
        }
        browserWasSafe = false
        // Hyperspace JUMP_ARRIVE: ShipManager::JumpArrive hook, after the original
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.jumpArrive(this)
    }

    /** Set by [browserJumpLeave] until the arrival's [resetAfterJump]; not saved (a repeated system Jump is harmless). */
    private var browserLeftBeacon = false

    /**
     * ShipManager::JumpLeave (0x49c9c0): as the jump-out starts, every system but the weapons jumps (vtable +0x8c:
     * the battery stops and unlocks, the cloak drops, hacking and mind control end, artillery loses its charge)
     * and the doors are repaired when no boarder is aboard.
     */
    fun browserJumpLeave() {
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.systems.Teleporter.hsRecallOnJumpLeave(this) // HS TELEPORT_RECALL
        for (system in systems) {
            if (system !is Weapons && system !is xyz.znix.xftl.systems.Clonebay)
                system.onJump()
        }
        resetDoorsForJump()
        browserLeftBeacon = true
        // ShipManager::JumpLeave (1.6.14 0x49c9c0, 1.6.9 0x491b70) ends with PlaySoundMix("jumpLeave") whichever ship
        // leaves: an escaping enemy too. The player's jump plays it in InGameState.beginJumpOut.
        if (!isPlayerShip) sys.sounds.getSampleOrWarn("jumpLeave")?.play()
        // Hyperspace: the rooms lose their temporal dilation (TemporalSystem.cpp ShipManager::JumpLeave hook).
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsTemporal.jumpLeave(this)
        // Hyperspace JUMP_LEAVE: ShipManager::JumpLeave hook, after the original
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.jumpLeave(this)
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
     * ShipManager::SetSafe, every frame nothing around the player is hostile: the teleporter is charged and unlocked, the
     * cloak's and the battery's cooldowns end.
     */
    fun browserSetSafe() {
        browserWasSafe = true
        teleporter?.hsForceReady()
        for (system in systems) {
            if ((system is xyz.znix.xftl.systems.Cloaking || system is xyz.znix.xftl.systems.BackupBattery) &&
                xyz.znix.xftl.hs.systems.HsSystemsLua.lockCount(system) > 0) system.lockUnits(0)
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
        hsSuperShieldSet = 0
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
        browserWasSafe = false
    }

    /**
     * ShipManager::bWasSafe (+0x8bc): set by SetSafe (0x49b5b0) while nothing is hostile, cleared by JumpArrive and
     * Wait. Not saved: the first frame after a load sets it again when the beacon is safe.
     */
    private var browserWasSafe = false

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

    /**
     * Hyperspace player start (HsShipState.startingCrewRooms): a starting crew member in the room 0x4ae2f0 chose,
     * standing by the computer of a station like the vanilla placement below.
     */
    fun addInitialCrewInRoom(info: LivingCrewInfo, room: Room): LivingCrew {
        require(room.ship === this) { "Crew spawn room belongs to another ship" }
        val system = room.system
        val slot = if (system != null) system.configuration.computerPoint ?: ConstPoint.ZERO else null
        val station = if (slot != null && room.isSlotFree(slot, AbstractCrew.SlotType.CREW)) RoomPoint(room, slot) else null
        return spawnCrewMember(info, true, false, room, true, station)
    }

    private fun spawnCrewMember(info: LivingCrewInfo, initial: Boolean, isIntruder: Boolean,
                               startingRoom: Room?, recordRecruitment: Boolean, station: RoomPoint? = null): LivingCrew {
        val mode = if (isIntruder) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW
        var freeSpace: RoomPoint? = station ?: startingRoom?.let { findSpaceForCrew(it, mode) }

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

        // Modded ships can hold more crew than tiles reachable from the room (a Hyperspace <crew> list on top of the
        // generated crew, rooms without doors): the original stacks them, so take any free tile, else share one.
        if (xyz.znix.xftl.hs.HS.modded) {
            for (room in rooms) room.firstFreeSlot(type)?.let { return RoomPoint(room, it) }
            return RoomPoint(firstRoom, ConstPoint.ZERO)
        }

        // No free space!
        error("Couldn't find any free space in ship $name, starting at room $firstRoom")
    }

    fun updateAvailableSystems() {
        systems = rooms.mapNotNull { it.system }
        // Hyperspace SystemControl::CreateSystemBoxes sorts the boxes by SystemPositionManager's `position` (CustomSystems.cpp)
        mainSystems = systems.mapNotNull { it as? MainSystem }.let { l ->
            // (CreateSystemBoxes collects the systems by id first; std::sort of so few is an insertion sort, so ties keep id order)
            if (xyz.znix.xftl.hs.HS.active) l.sortedWith(compareBy({ xyz.znix.xftl.hs.systems.HsSystems.position(xyz.znix.xftl.ai.nativeSystemId(it)) }, { xyz.znix.xftl.ai.nativeSystemId(it) }))
            else l.sortedBy { it.sortingType }
        }
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

    /** The augment slots (vanilla three; Hyperspace `augSlots` plus AUGMENT_SLOT). */
    val augmentSlotCount: Int get() = if (hs != null) xyz.znix.xftl.hs.ship.HsEquipment.augCapacity(this) else MAX_AUGMENTS

    /** The ordinary cargo slots (vanilla four; Hyperspace `cargoSlots` plus CARGO_SLOT). */
    val cargoSlotCount: Int get() = if (hs != null) xyz.znix.xftl.hs.ship.HsEquipment.cargoCapacity(this) else CARGO_SLOTS

    /** The ordinary cargo slot [slot]; slots beyond the first four are Hyperspace's. */
    fun cargoAt(slot: Int): Blueprint? = if (slot < 4 || hs == null) cargoBlueprints[slot] else xyz.znix.xftl.hs.ship.HsEquipment.cargoAt(this, slot)

    fun setCargoAt(slot: Int, item: Blueprint?) {
        if (slot < 4 || hs == null) cargoBlueprints[slot] = item else xyz.znix.xftl.hs.ship.HsEquipment.setCargoAt(this, slot, item)
    }

    /** Every cargo item: the slots, and with Hyperspace the extra slots and the over-capacity items of every page. */
    fun allCargo(): List<Blueprint> = if (hs != null) xyz.znix.xftl.hs.ship.HsEquipment.cargoItems(this) else cargoBlueprints.filterNotNull()

    /** Something is in an over-capacity box (the equipment screen opens on arrival so it can be dealt with). */
    fun hasOverflow(): Boolean = cargoBlueprints.drop(4).any { it != null } || (hs != null && xyz.znix.xftl.hs.ship.HsEquipment.hasOverItems(this))

    /** Every ordinary cargo slot is taken (the store boxes for weapons and drones are disabled then). */
    fun cargoFull(): Boolean = (0 until cargoSlotCount).all { cargoAt(it) != null }

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
        val hsState = hs
        if (hsState != null) {
            // Hyperspace: hidden augments and augment functions count too (HsAugments has the rules).
            xyz.znix.xftl.hs.ship.HsAugments.fillValues(this, privateAugmentValues)
            // CustomEquipment::OnLoop: the augment and cargo slot counts, locked augments (CustomAugments.cpp).
            xyz.znix.xftl.hs.ship.HsEquipment.normalize(this)
            xyz.znix.xftl.hs.ship.HsEquipment.swapLocked(this)
            return
        }
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
        if (xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(this)) {
            // multipleOverCapacity: IsCompletelyFull is false for weapons, drones and augments, every item is kept.
            xyz.znix.xftl.hs.ship.HsEquipment.addOver(this, item)
            cargoUpdated()
            return true
        }
        if (cargoBlueprints[slot] == null) {
            cargoBlueprints[slot] = item
            overCapacityBoxShown[slot - 4] = true
            overCapacityLastSlot = slot
            overCapacityAdds++
            cargoUpdated()
        } else {
            sys.eventCreator.create(sys.eventManager["EQUIP_FULL"], sys.eventCreator.currentLevel())
            val event = sys.eventManager["EQUIP_FULL"].resolve()
            val message = DialogueWindow.SyntheticEvent(event.text!!.resolve(ftl.browser.BrowserRandom), "EQUIP_FULL")
            message.resources.scrap = 25
            sys.shipUI.showSyntheticDialogue(message)
        }
        return true
    }

    /** [forceCargo] (Hyperspace Equipment::AddToCargo): weapons and drones skip their own slots and go to the cargo. */
    @JvmOverloads
    fun addBlueprint(item: Blueprint, forced: Boolean, forceCargo: Boolean = false): Boolean {
        // Augments can only go in their special area
        if (item is AugmentBlueprint) {
            // Break down non-stackable duplicated augments
            // to scrap, using the AUGMENT_FULL event.
            // Note this goes before the max augments check, so the player
            // gets paid for duplicate augments when they're full.
            // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomAugments.cpp
            // (ShipObject::HasAugmentation hook): with Hyperspace the duplicate test counts hidden copies and
            // functional augment functions, so FTL_JUMPER_SCANNER (function ADV_SCANNERS) turns ADV_SCANNERS to scrap.
            val owned = if (hs != null) hasAugment(item.name) else augments.any { it == item }
            if (!item.stackable && owned) {
                // The original creates the event, which draws like any other, before its reward message.
                sys.eventCreator.create(sys.eventManager["AUGMENT_FULL"], sys.eventCreator.currentLevel())
                val realEvent = sys.eventManager["AUGMENT_FULL"].resolve()
                val synthetic = DialogueWindow.SyntheticEvent(realEvent.text!!.resolve(ftl.browser.BrowserRandom), "AUGMENT_FULL")
                synthetic.resources.scrap = 25
                sys.shipUI.showSyntheticDialogue(synthetic)
                return true
            }

            if (augments.size < augmentSlotCount) {
                augments.add(item)
                cargoUpdated()
                return true
            }

            return storeOverflow(item, forced, 5)
        }

        if (item is AbstractWeaponBlueprint && !forceCargo) {
            val weaponSlotCount = weaponSlots ?: hardpoints.size

            for (slot in 0 until weaponSlotCount) {
                if (hardpoints[slot].weapon != null)
                    continue

                hardpoints[slot].weapon = item.buildInstance(this)
                xyz.znix.xftl.shipgen.ShipConstructionDraws.weapon()
                cargoUpdated()
                return true
            }
        }

        val drones = drones
        if (item is DroneBlueprint && drones != null && !forceCargo) {
            for (slot in 0 until drones.drones.size) {
                if (drones.drones[slot] != null)
                    continue

                drones.drones[slot] = Drones.DroneInfo(item, null)
                cargoUpdated()
                return true
            }
        }

        for (slot in 0 until cargoSlotCount) {
            if (cargoAt(slot) != null)
                continue

            setCargoAt(slot, item)
            // Equipment::AddWeapon makes the ProjectileFactory for a weapon in the hold too.
            if (item is AbstractWeaponBlueprint) xyz.znix.xftl.shipgen.ShipConstructionDraws.weapon()
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
            if (hs != null) xyz.znix.xftl.hs.ship.HsEquipment.syncOver(this)

            cargoUpdated()
            return true
        }

        if (hs != null && xyz.znix.xftl.hs.ship.HsEquipment.removeExtra(this, item)) {
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
        if (hs != null) return xyz.znix.xftl.hs.ship.HsAugments.count(this, name) > 0
        val blueprint = sys.blueprintManager.getOrNull(name) as? AugmentBlueprint ?: return false
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
        if (hs != null) return xyz.znix.xftl.hs.ship.HsAugments.valueOrNull(this, name)
        val blueprint = sys.blueprintManager.getOrNull(name) as? AugmentBlueprint ?: return null
        return augmentValues[blueprint]
    }

    /** While above zero, [updateCrewReservedSlots] waits (several crew get new slots in one step); see [AbstractCrew.setTargetSlot]. */
    @JvmField var deferCrewSlotUpdates = 0

    /**
     * Update [Room.reservedPlayerSlots] and [Room.reservedEnemySlots]
     * for every room on the ship.
     *
     * This should mainly be called by [AbstractCrew], whenever a change
     * to their pathfinding target is made. It can also be called whenever
     * the crew on the ship are changed, such as from deaths or new crew.
     */
    fun updateCrewReservedSlots(resolveConflicts: Boolean = true) {
        if (deferCrewSlotUpdates > 0) return
        val conflicts = ArrayList<AbstractCrew>()

        for (room in rooms) {
            room.updateCrewReservedSlots(conflicts)
        }
        if (!resolveConflicts) return

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

        // Native GetDodged 1.6.9 492060 /1.6.14 4a00d0: no draw for a nonpositive first factor;
        // otherwise draw, then query again. HS callbacks can change the second value inside this operation.
        val first = evasion + deltaEvasion
        val missed = first > 0 && (ftl.browser.BrowserRandom.nextInt().ushr(1) % 100) <
            (if (xyz.znix.xftl.hs.HS.active) evasion + deltaEvasion else first)
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

    fun spawnCrewShot(
        start: IPoint, end: IPoint, room: Room, image: Image?, colour: Colour, onLanded: (() -> Unit)? = null
    ) {
        val impact = sys.animations.getOrNull("explosion_missile1")?.startSingle(sys)
        impact?.duration = CrewShot.IMPACT_TIME
        crewShots += CrewShot(room, start, end, image, colour, impact, onLanded)
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

    /** Native portrait crew, plus HS's owned drones currently on their home ship (CustomDrones.cpp). */
    private fun stationCrew(): List<AbstractCrew> {
        val result = ArrayList<AbstractCrew>()
        if (isPlayerShip) result.addAll(sys.playerCrew.filter { it.ownerShip === this })
        // During a new run the global portrait list has not been rebuilt yet.
        for (member in crew.filterIsInstance<LivingCrew>())
            if (member.ownerShip === this && member !in result) result.add(member)
        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().droneSaveStations)
            result.addAll(crew.filterIsInstance<AbstractIndoorsDrone.Pawn>().filter {
                it.drone.ownerShip === this && it.room.ship === this
            })
        return result
    }

    fun saveCrewPositions() {
        // SaveCrewPositions 1.6.9 0x490440 / 1.6.14 CrewMember::SavePosition 0x47a7e0:
        // save the reserved destination slot at home; ClearPosition for crew on another ship.
        for (member in stationCrew()) {
            member.savedPosition = if (member.room.ship === this)
                member.currentSlotPoint.shipPoint else null
        }
    }

    fun loadCrewPositions(): Boolean {
        // Native RestoreCrewPositions (1.6.9 0x497540) empties every home slot BEFORE restoring the saved ones,
        // then fits crew without saved stations around them. A batch permits swaps and larger rotations.
        val members = stationCrew().filter {
            it.room.ship === this && (it !is LivingCrew || !it.isMindControlled)
        }
        val old = members.associateWith { it.pathingTarget ?: it.standingPosition }
        val stations = members.associateWith { member -> member.getReportedStation()?.let { shipToRoomPos(it) } }
        val ordered = members.filter { stations[it] != null } + members.filter { stations[it] == null }
        deferCrewSlotUpdates++
        try {
            for (room in rooms) room.releaseStationSlots(members)
            for (member in ordered) {
                val target = stations[member] ?: old[member] ?: continue
                if (member.health <= 0f) continue
                if (!member.setTargetRoom(target.room, target)) member.setTargetRoom(target.room)
                val goal = member.pathingTarget ?: member.standingPosition
                goal?.room?.reserveStationSlot(member)
            }
        } finally {
            deferCrewSlotUpdates--
            updateCrewReservedSlots()
        }
        return stations.values.any { it != null }
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
        // Hyperspace: a crew list reorder is kept (PlayerShipUI.hsSaveCrewOrder)
        for (crew in if (isPlayerShip) sys.shipUI?.hsSaveCrewOrder(this) ?: this.crew else this.crew) {
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
        // Hyperspace ship state (modded saves only).
        hs?.save(elem)

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

        // Ship::SaveState writes the lockdown shards (LockdownShard::SaveState 0x48b200)
        if (lockdownShards.isNotEmpty()) {
            val shardsElem = Element("lockdownShards")
            for (shard in lockdownShards) shardsElem.addContent(shard.saveToXML())
            elem.addContent(shardsElem)
        }

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
            // A shot still flying at a ship that was destroyed or left has nothing to hit, and the save holds no
            // such ship to aim it at.
            if ((projectile as? AbstractProjectile)?.targetShip?.let { refs.isRegistered(it) } == false)
                continue
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

        // Load the systems (Hyperspace: the load's CreateSystems order, see HsSystemState.creating)
        xyz.znix.xftl.hs.systems.HsSystemState.creating(this) { for (elem in rootElem.getChild("systems").getChildren("system")) {
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
        } }

        // Load the crew. A crew member's side (LivingCrew.mode) needs its ownerShip, which only resolves with the refs,
        // so the slot reservations wait until then (released below): rebuilt mid-load, an intruder counts as crew and
        // loses its goal to a crew member standing on that cell.
        deferCrewSlotUpdates++
        // Hyperspace: the load's OnInit made the blueprint's crew first (a space id and CONSTRUCT_CREWMEMBER each)
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsDummyCrew.loadPhantoms(this)
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
        lockdownShards.clear()
        rootElem.getChild("lockdownShards")?.getChildren("shard")?.forEach { lockdownShards += xyz.znix.xftl.layout.LockdownShard.loadFromXML(this, it) }
        val oxygenLevels = rootElem.getChildTextTrim("oxygenLevels").split(' ', '\t')
        // Hyperspace ShipManager::ImportShip (Ship/OxygenWithoutSystem.cpp, 1b05a07): the dummy oxygen system of a ship
        // without one reads each level through an int (`int oxygenLevel = FileHelper::readFloat(fd)`), so a level comes
        // back as a whole percentage.
        val hsWholeOxygen = xyz.znix.xftl.hs.HS.active && oxygen == null && xyz.znix.xftl.hs.HS.cfg().oxygenWithoutSystem
        for ((index, levelStr) in oxygenLevels.withIndex()) {
            val room = rooms[index]
            val level = levelStr.toFloat()
            room.oxygen = if (hsWholeOxygen) (level * 100f).toInt() / 100f else level
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
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsShipState.load(this, rootElem)

        // Deserialise loose cargo
        for (cargoElem in rootElem.getChildren("cargoBlueprint")) {
            val name = SaveUtil.getAttr(cargoElem, "name")
            val index = SaveUtil.getAttrInt(cargoElem, "index")
            cargoBlueprints[index] = sys.blueprintManager[name] as Blueprint
        }

        if (hs != null) xyz.znix.xftl.hs.ship.HsEquipment.load(this, rootElem.getChild(xyz.znix.xftl.hs.ship.HsShipState.ELEMENT)) { sys.blueprintManager.getOrNull(it) as? Blueprint }

        // Deserialise explosion (and similar) animations
        for (animElem in rootElem.getChild("animations").getChildren("animation")) {
            animations += FloatingAnimation.loadFromXML(animElem, sys)
        }

        // Deserialise the in-flight projectiles
        for (projectileElem in rootElem.getChild("projectiles").getChildren("projectile")) {
            val serialisationType = SaveUtil.getAttr(projectileElem, "loadType")
            IProjectile.loadFromXML(sys, projectileElem, refs, serialisationType) {
                // Hyperspace: a loaded projectile's current space is the ship whose list holds it
                if (it is xyz.znix.xftl.weapons.AbstractProjectile && it.hsExt != null) it.hsCurrentSpace = this
                projectiles += it
            }
        }

        // The sides are still unknown here: fill the slots without moving anyone, and rebuild them properly once
        // the refs resolve (a loader that never resolves keeps the filled slots).
        deferCrewSlotUpdates--
        updateCrewReservedSlots(resolveConflicts = false)
        refs.addOnResolveFunction { updateCrewReservedSlots() }
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

    // Hyperspace Lua: CONSTRUCT_SHIP_MANAGER after the ShipManager constructor, then CONSTRUCT_ROOM for each room
    // Ship::OnInit builds (both before the systems are installed). The last initialiser of the class, so a callback
    // sees every field set.
    init {
        if (xyz.znix.xftl.hs.HS.luaActive) {
            xyz.znix.xftl.hs.crew.HsDummyCrew.once(this)
            xyz.znix.xftl.hs.lua.LuaShipEvents.constructShipManager(this)
            for (room in rooms) xyz.znix.xftl.hs.lua.LuaShipEvents.constructRoom(room)
        }
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

        /** A jump-out's tracker (Ship::SetDestroyed / ExplosionAnimation::SetJumpOut: 4.0). */
        const val DESTRUCTION_JUMP_TIME: Float = 4f

        /** ExplosionAnimation::OnLoop / OnRender: where a jump-out leaves (0.75). */
        private const val JUMP_OUT_PROGRESS = 0.75f

        /** Ship::OnLoop 0x493f70: no new small explosion while 25 are playing. */
        private const val MAX_DESTRUCTION_EXPLOSIONS = 25
    }

    /** ShipInfo::equipList entries with no item behind them: weapons a script added with no slot left. */
    @JvmField val hsExtraEquipment = HashMap<String, Int>()

    /** Weapons a script replaced (WeaponSystem::AddWeapon keeps them in weaponsTrashList). */
    @JvmField val hsReplacedWeapons = ArrayList<xyz.znix.xftl.weapons.AbstractWeaponInstance>()

    /** A weapon a script added past the ship's mounts (WeaponSystem::AddWeapon appends one past slot_count): it shares the last mount. */
    fun hsExtraHardpoint(): Hardpoint {
        val h = Hardpoint(hardpoints.last().spec)
        (hardpoints as MutableList<Hardpoint>).add(h)
        return h
    }

    class Hardpoint(val spec: ShipBlueprint.ParsedHardpoint) {
        var weapon: AbstractWeaponInstance? = null
    }

    class FloatingAnimation(game: InGameState, val spec: AnimationSpec, val pos: ConstPoint, val scaling: Float = 1f) {
        private val animation = spec.startSingle(game)

        val isFinished get() = animation.isStopped

        fun render(g: Graphics) {
            if (animation.spec.blank) return
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

                    else -> game.animations.orEmpty(name)
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
