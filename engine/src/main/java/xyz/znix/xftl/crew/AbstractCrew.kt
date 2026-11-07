package xyz.znix.xftl.crew

import xyz.znix.xftl.hs.crew.random32
import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.Constants.*
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.LoopHandle
import xyz.znix.xftl.layout.BreachInstance
import xyz.znix.xftl.layout.Door
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.Oxygen
import kotlin.math.*
import kotlin.random.Random

abstract class AbstractCrew(
    blueprint: CrewBlueprint,
    private val anims: Animations,
    initialRoom: Room
) : ISerialReferencable {

    /** The race. Only Hyperspace's race transformation ([hsSetRace]) changes it. */
    var blueprint: CrewBlueprint = blueprint
        private set

    /** Hyperspace's CrewMember_Extend for this crew member (HS sessions only). */
    @JvmField
    var hsExtend: xyz.znix.xftl.hs.crew.CrewExtend? = null

    protected val game: InGameState get() = room.ship.sys

    /**
     * True while the ship this crewmember is standing on is hostile to
     * them, and hence one they may start sabotaging. The original only
     * picks a system to work on while the ship's crew AI is running
     * (CrewAI::UpdateCrewMember 0x627df0 -> 0x626860 -> CrewMember::StartRepair
     * 0x47e610, which sets CrewMember +0x68); the sabotage step itself
     * (CrewMember 0x47e850) then goes on with that system until the boarder
     * leaves the room or the system is destroyed. [sabotagingSystem] is
     * that latch.
     */
    open val standingShipHostile: Boolean get() = true

    /**
     * CrewMember +0x68 for a boarder: the system it started sabotaging while
     * [standingShipHostile], which it keeps breaking after the fight is over
     * for as long as it stays in that room.
     */
    internal var sabotagingSystem: xyz.znix.xftl.AbstractSystem? = null
        private set

    val codename: String get() = blueprint.name

    /** Hyperspace: CrewMember::species (a drone pawn's CrewDrone type string can differ from its blueprint). */
    open val hsSpecies: String get() = codename

    /**
     * The race whose animation strips this crewmember uses. The native
     * CrewAnimation constructor (0x483d70) swaps "ghost" for "human".
     */
    protected open val animationRace: String get() = codename

    /** CrewAnimation picks "ghost_death" for ghosts, else "<race>_death_right". */
    protected open val deathAnimationName: String get() = "${animationRace}_death_right"

    var icon: FTLAnimation
    open val backImg: Image? = initialRoom.ship.sys.getImgIfExists("img/people/${codename}_color.png")
    var portraitAnim: AnimationSpec
        private set
    var portraitImage: Image
        private set

    // This is used to avoid restarting the walking animation unless necessary.
    private var walkDirection: Direction? = null

    // When you set the room, be sure to call positionChanged!
    var room: Room = initialRoom
        private set

    // The position of the top-left corner of the 35x35 sprite relative
    // to the ship's 0,0 screen position (NOT relative to the room).
    // These are measured in pixels, NOT in units of 35x35 cells.
    private var pixelSpaceX: Float = room.offsetX.f
        set(value) {
            field = value
            positionChanged()
        }
    private var pixelSpaceY: Float = room.offsetY.f
        set(value) {
            field = value
            positionChanged()
        }
    private val pixelPosition = Point(0, 0)
    private val pixelPositionCentre = Point(0, 0)

    fun getPixelPositionCentre(): IPoint = pixelPositionCentre

    /** CrewMember::x / y: the centre in ship pixels, unrounded (Hyperspace Lua). */
    val hsCentreX: Float get() = pixelSpaceX + ROOM_SIZE / 2
    val hsCentreY: Float get() = pixelSpaceY + ROOM_SIZE / 2

    /**
     * If this crewmember is exactly aligned with a cell, this is non-null.
     *
     * Note that this being null does NOT mean the unit is not moving! It's
     * very unlikely - the position would have to be an exact int value - but
     * it's not impossible.
     *
     * You should usually use [standingPosition] instead, unless you have
     * a good reason. This is because this might be non-null when we are
     * attacking doors (or some future movement-like action), which this
     * won't account for.
     */
    private var roomPosition: RoomPoint? = null

    /**
     * Get [roomPosition], but null if this unit is moving (when doors
     * are implemented properly, this will also be null if the crewmember
     * is attacking a door).
     *
     * This is also null if we've been set to move, but haven't since updated.
     */
    val standingPosition: RoomPoint?
        get() {
            // Note that we have to check if nextTargetPos is non-null rather than
            // if currentAction is MOVING. This is because when we come to a stop,
            // currentAction isn't instantly changed - on the last update where we're
            // still set to moving, we'll end the update having completed our movement.
            // This would break the updateCrewReservedSlots system, since we'd
            // still count as moving and thus not occupy a slot.
            if (nextTargetPos != null || pathingTarget != null) {
                return null
            }
            return roomPosition
        }

    open val showHealthBar: Boolean
        get() = health < maxHealth || game.shipUI.isCrewHighlighted(this)

    // Used for drawing the coloured outline around the crew.
    // Intruders on the player ship and crew on the enemy ship are hostile.
    // This isn't very nice to read, but it gets the job done.
    open val showRedOutline: Boolean
        get() = (mode == SlotType.INTRUDER) == (room.ship == game.player)

    /**
     * True if the player can see inside the crewmember's room.
     */
    open val providesPlayerVision: Boolean
        get() = false

    init {
        // Initialise pixelPosition, pixelPositionCentre, and roomPosition
        positionChanged()
    }

    @Suppress("LeakingThis")
    var health: Float = maxHealth

    /** Native per-pawn boarding goal. Unknown older history must not earn. */
    var browserTargetsDestroyed: Int? = 0
    var stunTime: Float = 0f
        internal set
    fun stun(seconds: Float) { stunTime = max(stunTime, seconds); currentAction = Action.IDLE }
    open val maxHealth: Float get() = baseMaxHealth

    /** The race's own maximum health, before any mind control boost. */
    open val baseMaxHealth: Float get() = 100f

    open val canManSystem: Boolean get() = true
    open val repairSpeed: Float get() = 1f
    /**
     * Whether the crewmember belongs to the player's ship (native iShipId 0),
     * or null when the engine doesn't track an owner.
     */
    open val belongsToPlayer: Boolean? get() = null

    /** CrewMember::GetFireRepairMultiplier (0x7557a0). */
    open val fireFightingSpeed: Float get() = 1.2f
    open val canPunch: Boolean get() = true

    /**
     * How the shooting animation fires. RockAnimation (Rock, Crystal; FireShot 0x478870) and MantisAnimation
     * (0x478a00) throw when their looping 4-frame shoot strip reaches frame 3, and their strip animates
     * (GetFiringFrame -1). Every other CrewAnimation (FireShot 0x478b90) fires on its 1.0-1.3 s shot timer and holds
     * the strip on one aim frame (GetFiringFrame 0x477c30).
     */
    open val throwsOnShootFrame: Boolean get() = false

    /** Whether the shooting animation fires crew projectiles (the Ion intruder's animation has its own pulse). */
    open val firesCrewShots: Boolean get() = true
    open val canFight: Boolean get() = true
    open val canRepair: Boolean get() = true
    open val canSabotage: Boolean get() = true
    open val doorAttackMultiplier: Int get() = 1
    open val attackDamageMult: Float get() = 1f
    open val hasDyingAnimation: Boolean get() = true
    open val suffocationMultiplier: Float get() = 1f
    open val fireDamageMult: Float get() = 1f
    open val playerControllable: Boolean get() = false

    /** CrewMember::savedPosition; shared with crew drones. Optional savedPos keeps older saves compatible. */
    var savedPosition: ConstPoint? = null

    /** HS GPL 1b05a07 CustomCrew.cpp GetSavedPosition: uncontrolled crew report finalGoal, not savedPosition. */
    var hsFinalGoal: ConstPoint? = null

    fun getReportedStation(): ConstPoint? =
        if (xyz.znix.xftl.hs.HS.active && !playerControllable) hsFinalGoal else savedPosition

    /** The player may order this crew member to move (Hyperspace IsControlledByPlayer(1): also needs CAN_MOVE). */
    open val canBeOrderedToMove: Boolean get() = playerControllable

    /** The crew AI leaves this crew member alone (Hyperspace IsControlledByPlayer(-1): also NO_AI or rooted). */
    open val skippedByCrewAI: Boolean get() = playerControllable

    /** Set by Hyperspace crew while rooted (CAN_MOVE false) during their own update: MoveToRoom is refused. */
    @JvmField var hsMoveLock = false

    /**
     * CrewMember::bFrozen / bFrozenLocation (CrewMember::SetFrozen; Hyperspace freezes unpowered crew
     * drones and Lua may freeze anyone): while frozen, CrewMember::OnLoop skips UpdateRepair, so the crew
     * member neither repairs, fights fires nor sabotages. It still walks (oracle sweep-crew2-fx sw2_frozen: a frozen
     * crew member walks to its new room like an unfrozen one). Unfreezing a frozen crew member sets bFrozenLocation.
     */
    @JvmField var hsFrozen = false

    /** The crew animation is in its teleport state (status 6): teleporting, or arriving from the clone bay. */
    val hsAnimTeleport: Boolean get() = teleportingTo != null || cloneAnimationTimer > 0f

    /** CrewMember::clone_ready: see [update]; read by LivingCrew's death when Hyperspace is active. */
    @JvmField var hsCloneReady = false

    /** Seconds the finished death animation has held its last frame (see the dying branch of update). */
    private var deathLinger = 0f
    protected open fun cloneReadyNow(): Boolean = false

    /** Lua SetPath: the exact finish point (pixel top-left) once the route's cell is reached; held there after. */
    @JvmField var hsExactGoal: IPoint? = null
    @JvmField var hsHoldPoint = false

    /** Nesting of [update] (HsCrewMember adds its own part); Lua single steps asked for meanwhile run after it. */
    @JvmField var hsUpdateDepth = 0
    /** Updates run so far (marks Lua read-backs that last until this crew member's next update). */
    @JvmField var hsUpdates = 0
    private val hsPendingSteps = ArrayList<() -> Unit>()

    /**
     * A single step asked for from Lua (CrewMember::OnLoop / UpdateMovement / UpdateHealth / UpdateRepair): at once,
     * or, when it comes from inside this crew member's own update, right after that update in the same frame.
     */
    fun hsLuaStep(step: () -> Unit) {
        if (hsUpdateDepth > 0) hsPendingSteps += step else step()
    }

    protected fun hsRunPendingSteps() {
        while (hsPendingSteps.isNotEmpty()) {
            val steps = ArrayList(hsPendingSteps)
            hsPendingSteps.clear()
            for (st in steps) st()
        }
    }

    /** CrewMember::UpdateMovement: one step along the current walk (nothing when standing). */
    fun hsMovementStep(dt: Float) {
        val next = nextTargetPos ?: return
        if (stunTime > 0f || health <= 0f || isTeleporting) return
        hsUpdateDepth++
        try { stepMovement(next, dt) } finally { hsUpdateDepth-- }
    }

    /**
     * CrewMember::UpdateHealth as HS rewrites it (CustomCrew.cpp): one frame of fire damage, suffocation, the medbay
     * (fMedbay [medbay]) and, for HS crew, the heal amounts.
     */
    open fun hsHealthStep(dt: Float, medbay: Float) {
        val fires = room.fires.count { it != null }
        if (fires > 0) dealDamage(FireDamage(fires * 2.128f * dt * fireDamageMult))
        if (room.oxygen <= Oxygen.OXYGEN_CRITICAL_LEVEL) dealDamage(SuffocationDamage(6.4f * dt * suffocationMultiplier))
        var rate = medbay
        if (rate > 1f) rate = (rate - 1f) * 1.5f
        if (rate != 0f) dealDamage(MedbayHealing(-rate * 0.4f * 16f * dt))
    }
    @JvmField var hsFrozenLocation = false

    fun hsSetFrozen(frozen: Boolean) {
        if (!frozen) { if (hsFrozen) { hsFrozenLocation = true; hsFrozen = false; return } }
        else if (!hsFrozen) { hsFrozenLocation = false; hsFrozen = true; return }
        hsFrozen = frozen
    }
    open val movementSpeed: Float get() = BASE_MOVEMENT_SPEED

    /**
     * Movement speed multiplier from ship augments (Mantis Pheromones,
     * CREW_STIMS). Living crew get it from their home ship, so the buff
     * follows them when boarding; boarding drones don't benefit.
     */
    open val augmentSpeedMult: Float get() = 1f

    /**
     * The amount of oxygen to drain per second from this crew's room, and
     * all surrounding rooms with open doors, via the hull breach oxygen
     * draining logic.
     *
     * This should NOT be used for crew that refill oxygen in the current
     * room, unless you want them to also refill connected rooms.
     */
    open val anaerobicOxygenDrainRate: Float get() = 0f

    var pathingTarget: RoomPoint? = null
        private set(value) {
            field = value
            if (value != null) { hsExactGoal = null; hsHoldPoint = false }

            // CrewMember 0x480770: the route is planned once, when the goal is set (by updateMovement just below,
            // through nextStep), and then followed.
            doorRoute = null

            updateMovement()

            // If we're leaving a room, reset it's reserved slots so we don't keep taking up space there
            // The way it works in FTL (and should here) is that as soon as a crewmember starts walking, their
            // space is free and another crewmember can start pathing there.
            //
            // Likewise, if we start pathing to a room, mark that slot as taken.
            //
            // This is done by recalculating all the reserved slots on the ship. Previously
            // we kept a manually-updated list, but when we were adding and removing
            // crew it was very easy for it to fall out-of-sync with reality.
            room.ship.updateCrewReservedSlots()
        }

    var nextTargetPos: IPoint? = null
        set(value) {
            field = value

            // The direction may have changed. Only update the animation if
            // we're actually moving though, to avoid resetting other animations.
            if (currentAction == Action.MOVING) {
                updateAnimation()
            }
        }

    private var lastMode: SlotType = SlotType.CREW
    abstract val mode: SlotType

    // If we're currently attacking someone, the time until our next
    // punch or shot is fired.
    private var attackTimer: Float? = null
    private var attackDuration: Float = 1f
    private var damageTime: Float = 0f // Damage is applied half-way through the attack animation
    // Internal (not private) so the test kit can drive the targeting
    // seam directly.
    internal var enemyToAttack: AbstractCrew? = null
    private var isPunching: Boolean = false

    /** The doors the walk to [pathingTarget] goes through, in order (CrewMember path +0x40). */
    private var doorRoute: List<Door>? = null

    /**
     * The damage the attack animation has banked (CrewAnimation+0x8cc): a crew laser that arrived, or the 0.1 of a
     * shot that couldn't be fired. CrewMember::OnLoop (0x482a80) deals it to the current target.
     */
    private var pendingShotDamage: Float = 0f

    // CrewAnimation's shot timer (TimerHelper +0x89c, looping 1.0-1.3 s): it only ticks while shooting and carries
    // over between fights. Its goal is rolled on first use; the constructor's roll is the generation draw that
    // NativeCrewFactory already makes.
    private var shotTimerGoal: Float = -1f
    private var shotTimerElapsed: Float = 0f

    // RockAnimation/MantisAnimation: the shoot strip's clock (the strip loops from the constructor and only advances
    // while shooting) and the once-per-pass throw latch (+0x9ef / +0x9ee).
    private var shootStripTime: Float = 0f
    private var shootFrameThrown: Boolean = false

    // The aim point (CrewAnimation +0x8c4, ship pixels), its lean and whether it's exact (+0x8d4, doors): set while
    // fighting a crewmember or a door and kept afterwards, so a boarder that goes on to sabotage keeps shooting at the
    // last spot (each shot moves the stale point by its scatter, UpdateShooting 0x481de0).
    private var aimX: Int = 0
    private var aimY: Int = 0
    private var hasAim: Boolean = false
    private var aimLean: Int = CrewAim.NONE
    private var aimDirection: Direction = Direction.UP
    private var aimExact: Boolean = false

    // The shoot pose currently shown, so it's only rebuilt when the facing changes.
    private var shootPoseIcon: FTLAnimation? = null
    private var shootPoseDirection: Direction? = null

    // RockAnimation::UpdateFiring (0x47f100): the stomp latch for frame 9 of the fire-fighting strip.
    private var rockStompLatched: Boolean = false

    /** This race's projectile: the "crewLaser" frame (x=1, y=0) of its own base sheet (SetupStrips 0x480dc0). */
    private var crewShotImageRace: String? = null
    private var crewShotImageCache: Image? = null
    private val crewShotImage: Image?
        get() {
            val race = animationRace
            if (crewShotImageRace != race) {
                crewShotImageRace = race
                crewShotImageCache = buildCrewShotImage(race)
            }
            return crewShotImageCache
        }

    private fun buildCrewShotImage(race: String): Image? {
        val laser = anims.getOrNull("crewLaser") ?: return null
        val sheet = anims.sheet(race) ?: return null
        val rowFromBottom = laser.sheet.verticalCount - 1 - laser.y
        val row = sheet.verticalCount - 1 - rowFromBottom
        if (laser.x !in 0 until sheet.horizontalCount || row !in 0 until sheet.verticalCount)
            return null
        return AnimationSpec(sheet, "crewLaser:$race", laser.x, row, 1, 1f).spriteAt(game, 0)
    }

    /** CrewAnimation +0x9dc: laser races are tinted green (player crew) or red (enemy), everything else white. */
    private val crewShotColour: Colour
        get() = when {
            animationRace !in CrewAim.TINTED_RACES -> Colour.white
            belongsToPlayer == true -> CrewShot.PLAYER_LASER
            else -> CrewShot.ENEMY_LASER
        }
    private var sharesHostileCell: Boolean = false

    private var doorAttackPos: IPoint? = null // Where to stand when attacking the door
    private val phasedDoors = HashSet<Door>() // closed doors a phasing crew member already played the phase sound for
    private var doorToAttack: Door? = null

    private var teleportingTo: Room? = null
    private var teleportTimer: Float = 0f
    /** Hyperspace: the teleport animation's time passed its length in an update, and the updates since (see update). */
    private var hsTelePassed = false
    private var hsTeleDone = 0

    // The arrival half of a teleport is playing (the crew member already stands at [teleportingTo]).
    private var teleportArriving = false

    /**
     * Hyperspace CrewMember_Extend::customTele (CustomCrew.cpp, teleportMove): the armed teleport's ship id (-1 none),
     * room and slot (-1 any), and whether a teleport is playing (customTele.teleporting). Saved with the crew member.
     */
    @JvmField var hsTeleShip = -1
    @JvmField var hsTeleRoom = 0
    @JvmField var hsTeleSlot = 0
    @JvmField var hsTeleporting = false

    /** Hyperspace Lua CrewMember::Kill: dead at once on the next loop (bDead), without the dying animation. */
    @JvmField var hsKilled = false
    /** The ship loop's answer to CrewMember::bDead written by a script: dead at once, no dying animation. */
    /** Hyperspace: CrewAnimation::baseStrip and colorStrip written by a script. */
    @JvmField var hsBaseStrip: xyz.znix.xftl.hs.lua.LuaTexture? = null
    @JvmField var hsColorStrip: xyz.znix.xftl.hs.lua.LuaTexture? = null

    fun hsKillNow() { health = 0f; hsKilled = true }

    /** A teleport is playing its leaving half (CrewAnimation status 6, not reversed). */
    val isTeleportLeaving: Boolean get() = teleportingTo != null && !teleportArriving
    /** A teleport is playing (CrewAnimation status 6). */
    val isTeleporting: Boolean get() = teleportingTo != null

    private var cloneAnimationTimer: Float = 0f

    // The room slot of the fire we're currently putting out, or -1.
    private var currentFireSlot: Int = -1
    private var currentBreachSlot: Int = -1

    /**
     * True while something (normally a medbay) is actively healing this
     * crewmember.
     *
     * The healing system calls [markBeingHealed] every update in which it's
     * healing us. While the flag is set the green healing sparkles are drawn
     * above the crewmember.
     *
     * This isn't saved, as it's recomputed every update.
     */
    var isBeingHealed: Boolean = false
        private set

    /** The healing sparkles animation, created on first use. */
    private var healingAnimation: FTLAnimation? = null
    private var stunAnimation: FTLAnimation? = null

    /**
     * Set by [markBeingHealed] this frame, and adopted into
     * [isBeingHealed] at the start of our update.
     *
     * This two-stage dance is needed because systems update before crewmembers
     * (see [xyz.znix.xftl.Ship.update], which updates rooms then crew) - the
     * medbay sets the flag, then a few lines later our update runs. Adopting
     * the flag there (instead of clearing it) keeps it visible for this
     * frame's render, and it's dropped on the next update if nobody re-marks
     * us.
     */
    private var healingRequestedThisFrame: Boolean = false

    /**
     * Called by whatever is healing this crewmember (normally the medbay)
     * every update in which the healing is actually happening.
     */
    fun markBeingHealed() {
        healingRequestedThisFrame = true
    }

    // Vanilla plays soft loops while crew work or fight. Like fire, their
    // volume scales with the number of crew contributing to the same loop.
    private val repairLoop by lazy { game.sounds.getLoop("repair") }

    // CrewMember::GetUniqueRepairing: Lanius repair with their own loop and
    // Crystals make no repair sound.
    private val laniusRepairLoop by lazy { game.sounds.getLoop("shrikeRepair") }
    private val extinguishLoop by lazy { game.sounds.getLoop("extinguish") }
    private val brawlingLoop by lazy { game.sounds.getLoop("brawling") }

    // Rock crew don't use the generic repair loop: RockAlien::GetUniqueRepairing
    // (0x777110) returns an empty name. Instead the RockAlien constructor
    // (0x48756b) queues rockThud1..3 on frame 5 of its repair animation when
    // the crew belongs to ship 0, and Animation::Update (0x5bb8e0) plays one
    // of the frame's sounds, chosen with rand() % count, each time the
    // animation steps onto that frame.
    private val rockRepairThuds by lazy {
        listOf("rockThud1", "rockThud2", "rockThud3").mapNotNull { game.sounds.getSampleOrWarn(it) }
    }
    private var lastRepairFrame = -1

    /**
     * Keep the repair sound going this frame. Most races use the generic
     * repair loop; Rocks play a random thud (see [rockRepairThuds]). Like
     * [LoopHandle.continueLoopPlayerOnly], thuds are only audible on the
     * player's own ship.
     */
    protected open fun continueRepairSound(dt: Float) {
        if (this is CrewRock) {
            val frame = icon.frame
            val stepped = frame != lastRepairFrame
            lastRepairFrame = frame
            if (rockRepairThuds.isEmpty() || ownerShip?.isPlayerShip != true)
                return

            if (stepped && frame == ROCK_THUD_FRAME) {
                rockRepairThuds.random(ftl.browser.BrowserRandom).play()
            }
        } else if (codename == "anaerobic") {
            laniusRepairLoop.continueLoopPlayerOnly(room.ship)
        } else if (codename != "crystal") {
            repairLoop.continueLoopPlayerOnly(room.ship)
        }
    }

    val screenX: Int get() = pixelPosition.x

    /** CrewMember::PositionShift: how far a crew member sharing a cell with an enemy is displaced vertically. */
    val hsPositionShift: Int get() = screenY - 3 - pixelPosition.y

    val screenY: Int
        get() {
            val base = pixelPosition.y

            if (!sharesHostileCell)
                return base + 3

            // When we're fighting in the same cell as an enemy, displace both parties so
            // they don't fully overlap. Instead of 3 down, use 10 down or 4 up depending
            // on whether we're at the top or bottom.
            return when (mode) {
                SlotType.CREW -> base - 4
                SlotType.INTRUDER -> base + 10
            }
        }

    /**
     * The action this crewmember is currently performing.
     */
    var currentAction: Action = Action.IDLE
        private set(value) {
            val changed = field != value
            field = value

            if (!changed)
                return

            updateAnimation()

            // Reset stuff when we change to a different action.
            if (value != Action.FIRE_FIGHTING) {
                currentFireSlot = -1
            }
            if (value != Action.REPAIRING_BREACH) {
                currentBreachSlot = -1
            }
        }

    init {
        portraitAnim = buildPortraitAnim()
        icon = portraitAnim.startLooping(game)
        portraitImage = portraitAnim.spriteAt(game, 0)

        @Suppress("LeakingThis")
        updateAnimation()
    }

    private fun buildPortraitAnim(): AnimationSpec {
        // Since the portrait doesn't have a background colour frame, use the top-left frame.
        // Drones need to use the correct image, though!
        // Hyperspace races drawn with another race's sheet (animBase) use that race's portrait.
        val portrait = if (xyz.znix.xftl.hs.HS.active) {
            // Hyperspace races may ship no portrait animation (MV's snowman_chaos, spawned by a weapon): the
            // original draws the crew from its sheet regardless, so use the sheet's first frame.
            anims.getOrNull("${codename}_portrait") ?: anims.getOrNull("${animationRace}_portrait")
                ?: anims.sheet(animationRace)?.let { AnimationSpec(it, "$codename sheet portrait", 0, 0, 1, 1f) }
                ?: anims.crewStrip("${animationRace}_portrait")
        } else if (animationRace == codename) anims.crewStrip("${codename}_portrait")
        else anims.getOrNull("${codename}_portrait") ?: anims.crewStrip("${animationRace}_portrait")
        return if (backImg == null || portrait.blank) {
            portrait
        } else {
            val name = "$codename synthetic portrait animation"
            AnimationSpec(portrait.sheet, name, 0, 0, 1, 1f)
        }
    }

    /**
     * Hyperspace's CrewMember_Extend::TransformRace: this crew member becomes [race] in place. The portrait and the
     * current animation follow the new race.
     */
    fun hsSetRace(race: CrewBlueprint) {
        blueprint = race
        portraitAnim = buildPortraitAnim()
        portraitImage = portraitAnim.spriteAt(game, 0)
        walkDirection = null
        shootPoseIcon = null
        updateAnimation()
    }

    /** CrewExtraCondition::SHOOTING: the shooting animation (state 7) is playing. */
    val hsShooting: Boolean get() = currentAction.isAttackAction && !isPunching

    /** Hyperspace RANGED_DAMAGE_MULTIPLIER: scales the damage a crew shot banks (CrewAnimation::OnUpdateEffects). */
    open val rangedDamageMult: Float get() = 1f

    /** Hyperspace SABOTAGE_SPEED_MULTIPLIER (ShipSystem::PartialDamage during CrewMember::UpdateRepair). */
    open val sabotageSpeedMult: Float get() = 1f

    /** The ion intruder stands in the sabotage state for its pulse only: its repair step does nothing. */
    open val sabotageDamagesSystem: Boolean get() = true

    /** The shot and punch timers (CrewAnimation +0x89c / +0x8b0): minimum and span in ms. */
    protected open val shotTimerMinMs: Int get() = CrewAim.SHOT_TIMER_MIN_MS
    protected open val shotTimerSpanMs: Int get() = CrewAim.SHOT_TIMER_SPAN_MS
    protected open val punchTimerMinMs: Int get() = CrewAim.PUNCH_TIMER_MIN_MS
    protected open val punchTimerSpanMs: Int get() = CrewAim.PUNCH_TIMER_SPAN_MS

    /** CrewMember::IsTelepathic (vtable 46): reveals its room and the rooms next to it. */
    open val isTelepathic: Boolean get() = this is CrewSlug

    /** Counts for the lifeform scanner (ShipObject::HasEquipment("slug")). */
    open val detectsLifeforms: Boolean get() = this is CrewSlug

    /** The system power this crew member gives its owner's system in its room (Zoltan: 1). */
    open val bonusSystemPower: Int get() = if (this is CrewZoltan) 1 else 0

    open fun update(dt: Float) {
        hsUpdateDepth++
        hsUpdates++
        try {
        // CrewMember::OnLoop: every loop of a living crew member sets clone_ready from its own ship having
        // a clone bay; one killed before its first loop is not cloned (oracle sweep-crew4-clone sw4_clone0)
        if (health > 0f && currentAction != Action.DYING) hsCloneReady = cloneReadyNow()
        icon.update(dt)

        // Adopt whatever the medbay (or another healer) requested earlier
        // this frame - systems update before crew. Also keep the healing
        // animation running (if we have one) so it doesn't visibly jump when
        // healing resumes.
        isBeingHealed = healingRequestedThisFrame
        healingRequestedThisFrame = false
        healingAnimation?.update(dt)
        stunAnimation?.update(dt)

        // If we're moving we shouldn't be vertically offset to match
        // the enemy, if not this will be updated later on.
        sharesHostileCell = false

        // If we switched between being a crewmember and an intruder, update
        // the room slot reservation stuff, to make sure we're still valid
        // to stand in this cell.
        if (lastMode != mode) {
            lastMode = mode
            room.ship.updateCrewReservedSlots()
        }

        // Hyperspace: CrewMember::OnLoop runs UpdateHealth (fire, suffocation, the medbay, heal amounts) only while
        // the crew animation is not in its teleport state (status 6: teleporting, arriving from the clone bay;
        // oracle sweep-crew4-clone: a clone arriving in a room without air keeps 100 until the animation ends)
        val hsNoHealth = xyz.znix.xftl.hs.HS.active && hsAnimTeleport
        // The ShipManager crew loop (0x4aa670) suffocates crew at 5% O2 or below.
        if (room.oxygen <= Oxygen.OXYGEN_CRITICAL_LEVEL && !hsNoHealth) {
            dealDamage(SuffocationDamage(6.4f * dt * suffocationMultiplier))
        }

        val fires = room.fires.count { it != null }
        if (!hsNoHealth) dealDamage(FireDamage(fires * 2.128f * dt * fireDamageMult))

        if (health == 0f) {
            // CrewMember::Kill (Lua) marks the crew member dead at once (bDead): no dying animation.
            if (hsKilled) {
                hsKilled = false
                onFinishedDying()
                return
            }
            // Zoltan crew deal damage when their animation starts, so
            // we need to call out when that happens.
            if (currentAction != Action.DYING) {
                deathLinger = 0f
                onStartedDying()
            }

            if (!hasDyingAnimation) {
                onFinishedDying()
                return
            }

            // For crew with dying animations, play that before removing them.
            currentAction = Action.DYING

            if (icon.isStopped) {
                // CrewAnimation::Dead -> Animation::Done: the death animation's
                // fadeOut (1 s, Animation::SetFadeOut) runs down after its last frame, which is drawn at that opacity
                // (Animation::OnRender), and only then is the crew member dead (bDead). Native 1.6.14 crew
                // probe: health 0 to bDead 1.76 s, the human death animation 0.75 s; HS oracle sweep-crew4-clone 1.75 s
                if (deathLinger < 1f) {
                    deathLinger += dt
                    if (deathLinger < 1f) return
                }
                onFinishedDying()
            }

            return
        }

        if (stunTime > 0f) {
            stunTime = max(0f, stunTime - dt)
            currentAction = Action.IDLE
            return
        }

        // Playing the clone animation after being revived?
        if (cloneAnimationTimer > 0f) {
            cloneAnimationTimer = max(0f, cloneAnimationTimer - dt)
            currentAction = Action.CLONING
            return
        }

        if (teleportingTo != null) {
            currentAction = Action.TELEPORTING
            // Hyperspace (1.6.9 CrewAnimation::TeleportArrived, CheckForTeleport): the animation tracker is
            // done in the update its time passes the length (strictly), and the crew loop acts on that the update after;
            // the departure one update later still (oracle sweep-crew4-tele, frame by frame: the arrival ends in the
            // first update that starts past 0.5 s, the departure one update after it)
            if (xyz.znix.xftl.hs.HS.active && hsTelePassed) hsTeleDone++
            teleportTimer += dt
            if (xyz.znix.xftl.hs.HS.active && teleportTimer > TELEPORT_ANIMATION_TIME) hsTelePassed = true
            val teleEnded = if (xyz.znix.xftl.hs.HS.active) hsTeleDone >= (if (teleportArriving) 1 else 2)
            else teleportTimer >= TELEPORT_ANIMATION_TIME
            // CrewMember::StartTeleport without a destination (Lua): the departure animation plays and the crew
            // member stays gone in place (currentShipId -1, oracle sweep-crew2-fx sw2_tele)
            if (hsTeleLimbo) {
                if (teleportTimer > TELEPORT_ANIMATION_TIME) teleportTimer = TELEPORT_ANIMATION_TIME
                // CheckForTeleport sets currentShipId -1 once the animation has played, and
                // ShipManager::UpdateCrewMembers drops the crew member from vCrewList: it stays allocated, in no
                // list and no longer updated (oracle sweep-crew4-tele)
                if (teleEnded && this in room.ship.crew) removeFromShip()
                return
            }

            // Run a timer matching the duration of the teleport animation, then:
            // 1. When the animation has fully played, move
            //    to the enemy ship and play it backwards.
            // 2. When that's done, the teleporting is finished.
            if (!teleEnded)
                return
            teleportTimer = 0f; hsTelePassed = false; hsTeleDone = 0

            // CrewMember_Extend::InitiateTeleport to room -1 (CompleteShip::CheckTeleportMovement): a random room of
            // the armed ship, drawn when the departure ends
            if (!teleportArriving && hsTeleShip != -1 && hsTeleRoom == -1) {
                val target = if (hsTeleShip == 0) room.ship.sys.player else room.ship.sys.enemy
                if (target != null && target.rooms.isNotEmpty()) {
                    hsTeleRoom = xyz.znix.xftl.hs.crew.HsCrew.random32() % target.rooms.size
                    teleportingTo = target.rooms[hsTeleRoom]
                }
            }
            val destination = teleportingTo!!
            if (!teleportArriving && destination.ship == room.ship) {
                // Hyperspace teleportMove within the ship (CompleteShip::CheckTeleportMovement, AddCrewMember into the
                // armed room): the armed slot when it is free for our side, else a free slot of the room.
                nextTargetPos = null
                pathingTarget = null
                val wanted = if (hsTeleSlot in 0 until destination.cellCount) destination.slotToPoint(hsTeleSlot) else null
                val target = if (wanted != null && destination.isSlotFree(wanted, mode, this)) RoomPoint(destination, wanted)
                else room.ship.findSpaceForCrew(destination, mode)
                teleportArriving = true
                jumpTo(target)
                currentAction = Action.TELEPORTING
                updateAnimation()
                hsTeleShip = -1
                onMidTeleport()
                room.ship.sys.updatePlayerCrew()
                return
            }
            if (!teleportArriving) {
                // Step 1

                // Clear out any movement, as the pathfinding system
                // is understandably unhappy about walking between ships.
                nextTargetPos = null
                pathingTarget = null

                // Before we swap modes, we have to remove ourselves
                // from the current ship - otherwise when we swap modes, we may
                // conflict with the other crew (both standing on the same cell)
                // and thus force someone to move.
                removeFromShip()

                // Move to the other ship
                destination.ship.crew.add(this)

                // Set the current room to *somewhere* on the enemy ship, so
                // the mode is then correct.
                room = destination
                teleportArriving = true

                // Change the room over, preserving our current position if possible.

                val roomPosition = standingPosition
                // Hyperspace teleportMoveOtherShip: the armed slot of the armed room when it is free.
                val armed = if (hsTeleShip != -1 && hsTeleSlot in 0 until destination.cellCount) destination.slotToPoint(hsTeleSlot) else null
                if (armed != null && destination.isSlotFree(armed, mode)) {
                    val newPosition = RoomPoint(destination, armed)
                    pixelSpaceX = newPosition.offsetX.f
                    pixelSpaceY = newPosition.offsetY.f
                } else if (roomPosition != null && destination.containsRelative(roomPosition)
                    && destination.isSlotFree(roomPosition, mode)
                ) {
                    // The corresponding position in this new room is free
                    val newPosition = RoomPoint(destination, roomPosition)
                    pixelSpaceX = newPosition.offsetX.f
                    pixelSpaceY = newPosition.offsetY.f
                    room = destination
                } else {
                    // If our preferred location is unavailable, pick another
                    // spot (potentially in a different room, if required).
                    val free = destination.ship.findSpaceForCrew(destination, mode)
                    pixelSpaceX = free.offsetX.f
                    pixelSpaceY = free.offsetY.f
                    room = free.room
                }

                // Update our position to account for the room change
                positionChanged()

                // ShipManager::AddCrewMember -> CrewMember 0x47aec0: the arriving crew member takes a free slot and
                // that slot becomes its goal (path replanned to it with no lock penalty, 0x47ad70; task cleared), so
                // no walk carries over. Clearing the target above runs the old ship's slot-conflict pass while this
                // crew member is still listed there, which can hand it a walk on the ship it is leaving.
                pathingTarget = null
                nextTargetPos = null

                // Mark this slot as now in use
                room.ship.updateCrewReservedSlots()

                updateAnimation()

                hsTeleShip = -1
                onMidTeleport()

                // Fix the UI, since we removed ourselves from the player list while teleporting
                room.ship.sys.updatePlayerCrew()
            } else {
                // Step 2 - we're done
                teleportingTo = null
                teleportArriving = false
                hsTeleporting = false
            }

            // When the teleportation finishes, immediately continue with
            // our regular stuff. This avoids the player animation breaking
            // from still having the teleport action set, but not being able
            // to check whether they're on the source or destination ship.
            if (teleportingTo != null)
                return
        }

        // Bash at the door until it's opened. We still have our target set
        // while this is happening, so this must be done before the moving check.
        if (doorAttackPos == pixelPosition && doorToAttack!!.isLockedFor(this)) {

            // Just in case this didn't get reset, we don't want to punch the door.
            isPunching = false

            if (firesCrewShots) {
                // The door is the crew target (ExactTarget 1, no scatter): each shot that reaches it is one hit
                // (Door::ApplyDamage 0x46e0f0).
                val door = doorToAttack!!
                setAim(door.pixelCentre, exact = true)
                val hit = {
                    if (doorToAttack === door && door.isLockedFor(this))
                        repeat(doorAttackMultiplier) { door.attackDoor() }
                }
                updateShooting(Action.ATTACKING_DOOR, dt) { fireCrewShot(hit, hit) }
            } else {
                updateAttack(Action.ATTACKING_DOOR, dt, {
                    repeat(doorAttackMultiplier) { doorToAttack!!.attackDoor() }
                }, {
                    // Animation start, do nothing.
                })
            }

            return
        }

        // Lua SetPath: the route ends on its exact finish point (CrewMember::SetPath keeps x/y_destination)
        if (this.nextTargetPos == null && pathingTarget == null && hsExactGoal != null) {
            this.nextTargetPos = hsExactGoal; hsExactGoal = null; hsHoldPoint = true
        }
        val nextTargetPos = this.nextTargetPos // Avoid mutability errors
        if (nextTargetPos != null) {
            stepMovement(nextTargetPos, dt)
            return
        }

        // If we're not centred but not moving, start walking to align ourselves to a cell.
        // This probably shouldn't happen, but check just in case.
        val roomPosition = this.roomPosition
        if (roomPosition == null) {
            // standing on the exact end point of a Lua SetPath route (the native crew member stays there)
            if (hsHoldPoint) {
                currentAction = Action.IDLE
                return
            }
            this.nextTargetPos = pixelPositionCentre.divideTruncate(ROOM_SIZE.f) * ROOM_SIZE
            return
        }

        // Check if we're standing in the same cell as someone else.
        // This check is separate to the combat thing, since this
        // includes dying enemies (so their death animation is visible),
        // while the combat code doesn't (to avoid attacking
        // an already-dying enemy).
        sharesHostileCell = room.crew.any { it.mode != mode && it.standingPosition == roomPosition }

        // Check if any enemies are in the room
        val hostiles = room.crew.filter {
            it.mode != mode && it.currentAction != Action.DYING && (!xyz.znix.xftl.hs.HS.active || xyz.znix.xftl.hs.crew.HsCrewEffects.validTarget(it))
        }
        if (hostiles.isNotEmpty() && canFight) {
            // Check if someone is standing in the same cell as us
            val sameCell = hostiles.firstOrNull { it.standingPosition == roomPosition }

            isPunching = sameCell != null && canPunch

            // Has the enemy walked out of the room?
            enemyToAttack?.let { target ->
                if (!hostiles.contains(target)) {
                    enemyToAttack = null
                    attackTimer = null
                }
            }

            // CrewMember::OnLoop (0x482a80) runs the special power's automatic
            // use while it has a crew target.
            if (enemyToAttack != null)
                onFighting()

            enemyToAttack?.let { target ->
                if (pendingShotDamage > 0f) {
                    val banked = pendingShotDamage
                    pendingShotDamage = 0f
                    hitCrew(target, banked * attackDamageMult)
                }
            }

            if (isPunching) {
                updateAttack(Action.FIGHTING, dt, {
                    // The punch timer is done half-way through the punch: CrewAnimation::OnUpdate (0x482420) banks
                    // (rand%4001 + 3000) / 1000.
                    hitCrew(enemyToAttack!!, rollShotDamage() * attackDamageMult)
                }, {
                    // If we're punching someone always attack them.
                    enemyToAttack = pickFightingTarget(sameCell, hostiles)
                })
            } else {
                // Otherwise pick someone in the room at random to shoot. Keep shooting the crewmember we already
                // targeted while they're still valid (the fight target at CrewMember+0x188 is only re-rolled when
                // it becomes invalid, CrewMember 0x47d350).
                val target = pickFightingTarget(sameCell, hostiles)
                enemyToAttack = target
                setAim(target.getPixelPositionCentre(), exact = false)
                updateShooting(Action.FIGHTING, dt) {
                    // The damage, (rand%4001 + 3000) / 1000, is rolled when the shot arrives (0x4881c0) and dealt to
                    // whoever we're fighting then; a shot that can't be fired banks 0.1.
                    fireCrewShot({ pendingShotDamage = rollShotDamage() * rangedDamageMult },
                        { pendingShotDamage = 0.1f * rangedDamageMult })
                }
            }

            return
        }

        // Frozen (CrewMember::SetFrozen): OnLoop skips its UpdateRepair, but CrewAI::UpdateCrewMember
        // still starts a repair, fire or breach for a crew member without one, and StartRepair
        // runs one UpdateRepair: the work started holds (animation status 2 or 4) with no more progress
        // (oracle sweep-crew4-base sw4_rep_frozen)
        if (hsFrozen && (currentAction == Action.REPAIRING || currentAction == Action.REPAIRING_BREACH ||
                currentAction == Action.FIRE_FIGHTING || currentAction == Action.SABOTAGE)) return

        val system = room.system
        val latched = system != null && sabotagingSystem === system
        if (mode == SlotType.INTRUDER && canSabotage && (latched || standingShipHostile) &&
            system != null && !system.broken
        ) {
            sabotagingSystem = system
            isPunching = false
            enemyToAttack = null

            // Sabotage plays the shooting animation (CrewAnimation state 7). Its shots go to the last aim point, which
            // usually lies outside the room and so fires nothing (UpdateShooting 0x481de0); they deal no damage.
            if (firesCrewShots)
                updateShooting(Action.SABOTAGE, dt) { if (hasAim) fireCrewShot(null, null) }
            else
                updateAttack(Action.SABOTAGE, dt, {}, {})

            // Sabotage doesn't deal damage in bursts - it's constant 8% damage/second.
            // Note this is the same for any crew, regardless of the attack damage multiplier.
            val brokenLevel = sabotageDamagesSystem && system.attack(dt * 0.08f * sabotageSpeedMult)

            if (brokenLevel) {
                onSabotagedSystem(system)
            }

            return
        }

        // If we're not fighting, clear all the combat-related variables.
        sabotagingSystem = null
        attackTimer = null
        enemyToAttack = null
        isPunching = false

        // If we're an intruder and there's nothing for us to do, just stand idle.
        // Otherwise, we'd start repairing breaches.
        if (mode == SlotType.INTRUDER) {
            currentAction = Action.IDLE
            return
        }


        // Check if this room is on fire, and we need to put it out.
        currentFireSlot = if (canRepair) selectFireOrBreach(currentFireSlot, room.fires) else -1
        if (currentFireSlot != -1) {
            currentAction = Action.FIRE_FIGHTING
            extinguishLoop.continueLoopPlayerOnly(room.ship)
            if (this is CrewRock)
                continueRockStomp()

            // See doc/fires. [fireFightingSpeed] is the race's
            // GetFireRepairMultiplier (1.2 by default).
            val currentFire = room.fires[currentFireSlot]!!
            if (currentFire.health > 0f) {
                currentFire.health -= 0.08f * fireFightingSpeed * repairSpeed * dt

                if (currentFire.health == 0f) {
                    onFinishedExtinguishing()
                }
            }

            // TODO draw the fire extinguisher particles.

            return
        }

        // Do the same for fixing hull breaches.
        currentBreachSlot = if (canRepair) selectFireOrBreach(currentBreachSlot, room.breaches) else -1
        if (currentBreachSlot != -1) {
            currentAction = Action.REPAIRING_BREACH
            continueRepairSound(dt)

            val currentBreach = room.breaches[currentBreachSlot]!!
            currentBreach.health -= BreachInstance.CREW_REPAIR_RATE * repairSpeed * dt

            if (currentBreach.health == 0f) {
                onFinishedBreachRepair()
                room.breaches[currentBreachSlot] = null
            }

            return
        }

        // Check if the system in this room is broken, and if so repair it.
        system?.let { sys ->
            if (sys.damaged && canRepair) {
                currentAction = Action.REPAIRING
                continueRepairSound(dt)
                // The base repair speed is 8% per second, or 12.5 seconds to
                // repair one bar of damage.
                val didRepair = sys.repair(repairSpeed * 0.08f * dt)
                if (didRepair) {
                    // Builds skill in living crew
                    onFinishedRepair(sys)
                }
                return
            }
        }

        if (canManSystem && system?.isMannableBy(this) == true) {
            val computerPoint = system.configuration.computerPoint!!
            if (computerPoint posEq roomPosition) {
                currentAction = Action.MANNING
                return
            }

            // Walk to the computer if no-one is occupying that slot.
            // (don't specify ourselves as the allow argument, as we don't want
            //  to constantly call setTargetRoom when we're standing there)
            if (room.isSlotFree(computerPoint, mode)) {
                setTargetRoom(room)
            }
        }

        // Nothing else is being done
        currentAction = Action.IDLE
        } finally {
            hsUpdateDepth--
            if (hsUpdateDepth == 0) hsRunPendingSteps()
        }
    }

    /** One step of walking towards [nextTargetPos] (CrewMember::UpdateMovement). */
    private fun stepMovement(nextTargetPos: IPoint, dt: Float) {
            currentAction = Action.MOVING

            val deltaX = nextTargetPos.x - pixelSpaceX
            val deltaY = nextTargetPos.y - pixelSpaceY

            val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
            if (distance > 0.001f) {
                val directionX = deltaX / distance
                val directionY = deltaY / distance

                // Move closer towards our position
                val movement = min(distance, dt * movementSpeed * augmentSpeedMult)
                pixelSpaceX += directionX * movement
                pixelSpaceY += directionY * movement
            }

            // Check if we've switched room. Use our centre position, so
            // we switch room when we appear half-way across.
            // We must do this before updating our movement - if we're running
            // with a very high delta-time (for example, if we were stopped in
            // a debugger) then we'd be marked as being in the wrong room when
            // updateMovement was called.
            @Suppress("FoldInitializerAndIfToElvis")
            if (!room.containsShipSpace(pixelPositionCentre)) {
                val newRoom = room.ship.rooms.firstOrNull { it.containsShipSpace(pixelPositionCentre) }

                if (newRoom == null) {
                    throw IllegalStateException("Crewmember '$this' on ship '${room.ship.name}' walked into roomless position $pixelPosition")
                }

                room = newRoom

                // We should never be aligned to the grid while walking through
                // a door so we shouldn't have a roomPosition set, but check for
                // that just in case - if so, we need to update the room.
                positionChanged()
            }

            // Check if we've now reached our destination. If we're
            // very close, snap to the exact position.
            val newDistance = sqrt((nextTargetPos.x - pixelSpaceX).pow(2) + (nextTargetPos.y - pixelSpaceY).pow(2))
            if (newDistance < 0.01f) {
                pixelSpaceX = nextTargetPos.x.f
                pixelSpaceY = nextTargetPos.y.f

                this.nextTargetPos = null

                if (doorAttackPos == pixelPosition && doorToAttack!!.isLockedFor(this)) {
                    // Switch to attacking the door
                    currentAction = Action.ATTACKING_DOOR
                } else {
                    // Calculate the next movement
                    updateMovement()
                }
            }

            // Check if a door needs to be held open for us.
            for (door in room.doors) {
                var centreX = door.offsetX
                var centreY = door.offsetY

                if (door.isVertical) {
                    centreY += ROOM_SIZE / 2
                } else {
                    centreX += ROOM_SIZE / 2
                }

                val distSq = pixelPositionCentre.distToSq(centreX, centreY)

                if (distSq < DOOR_OPEN_DISTANCE.pow(2)) {
                    // CrewMember::GetNewGoal (CustomCrew.cpp): a crew member that phases never opens the door; it plays
                    // "phase" once when the door it walks through is closed
                    if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.crew.HsCrewEffects.phases(this)) {
                        if (!door.open && !phasedDoors.contains(door)) {
                            phasedDoors.add(door)
                            game.sounds.getSampleOrWarn("phase")?.play()
                        }
                    } else {
                        door.crewRequestOpen(this)
                    }
                    break
                }
            }
    }

    /** Hyperspace Lua CrewMember::OnRender(outlineOnly): 1 draws the outline alone (0: outline and body, oracle sw3-crewr). */
    @JvmField var hsRenderPart = 0

    open fun draw(g: Graphics) {
        if (icon.spec.blank) return

        val sprite = computeSpriteDraw()

        if (hsRenderPart != 2) drawBackground(sprite.frame, sprite.x0, sprite.y0, sprite.x1, sprite.y1, sprite.opacity)
        if (hsRenderPart == 1) return

        // Hyperspace (CrewAnimation::OnRender): an active temporary power may hide the body and draws strips over it
        val strips = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewRender.strips(this) else null

        // Draw the actual image
        // This is moved into its own function so that living crew with layered
        // rendering can apply their different colour filters.
        val luaBase = hsBaseStrip
        if (luaBase != null) {
            // CrewAnimation::baseStrip / colorStrip a script set: the same frame from those sheets.
            val f = sprite.frame
            for (t in arrayOf(luaBase, hsColorStrip)) {
                if (t == null) continue
                xyz.znix.xftl.hs.lua.LuaGraphics.resolve(t)
                t.image?.draw(sprite.x0, sprite.y0, sprite.x1, sprite.y1,
                    f.textureOffsetX.f, f.textureOffsetY.f, (f.textureOffsetX + f.width).f, (f.textureOffsetY + f.height).f, sprite.opacity, Colour.white)
            }
        } else if (strips == null || strips.baseVisible)
            drawImage(sprite.x0, sprite.y0, sprite.x1, sprite.y1, sprite.frame, sprite.opacity)
        if (strips != null) {
            val f = sprite.frame
            for (sheet in strips.sheets) {
                val strip = game.getImg("img/people/$sheet.png")
                strip.draw(
                    sprite.x0, sprite.y0, sprite.x1, sprite.y1,
                    f.textureOffsetX.f, f.textureOffsetY.f, (f.textureOffsetX + f.width).f, (f.textureOffsetY + f.height).f,
                    sprite.opacity, Colour.white
                )
            }
        }
        if (xyz.znix.xftl.hs.HS.active && currentAction != Action.DYING) xyz.znix.xftl.hs.crew.HsCrewRender.drawBody(this)
    }

    /**
     * Draw a red-tinted silhouette of this crewmember. This is how Slug
     * telepathy shows the crew aboard enemy ships: their positions are
     * visible even in rooms the player has no vision of, without revealing
     * the room's contents.
     */
    open fun drawTelepathy(g: Graphics) {
        val sprite = computeSpriteDraw()
        sprite.frame.draw(
            sprite.x0, sprite.y0, sprite.x1, sprite.y1,
            0f, 0f, sprite.frame.width.f, sprite.frame.height.f,
            sprite.opacity, Constants.CREW_TELEPATHY
        )
    }

    /**
     * Draw this crewmember's sprite scaled up around its centre. Used by
     * the touch UI's room-selection mode, which enlarges the selected
     * crew. The health bar is drawn separately via [drawScaledForeground]
     * (the engine draws all health bars in a second, on-top pass).
     */
    fun drawScaledBody(g: Graphics, scale: Float) {
        g.pushTransform()
        scaleAboutSpriteCentre(g, scale)
        draw(g)
        g.popTransform()
    }

    /**
     * The [drawScaledBody] counterpart for the foreground pass (health
     * bars, mind-control and healing animations).
     */
    fun drawScaledForeground(g: Graphics, scale: Float) {
        g.pushTransform()
        scaleAboutSpriteCentre(g, scale)
        drawForeground(g)
        g.popTransform()
    }

    private fun scaleAboutSpriteCentre(g: Graphics, scale: Float) {
        val sprite = computeSpriteDraw()
        val centreX = sprite.x0 + sprite.frame.width / 2f
        val centreY = sprite.y0 + sprite.frame.height / 2f

        g.translate(centreX, centreY)
        g.scale(scale, scale)
        g.translate(-centreX, -centreY)
    }

    // The shared sprite position/bounds maths for draw() and drawTelepathy().
    private fun computeSpriteDraw(): SpriteDraw {
        val cf = icon.currentFrame

        var spriteY = screenY
        var opacity = 1f
        var stretch = 0f
        if (currentAction == Action.TELEPORTING) {
            // The sprite image (and not the health bar - hence why this isn't
            // built into screenY) moves up when teleporting, since it needs
            // to go further up on the screen.
            spriteY -= 8

            // The player fades out, and stretches upwards while teleporting.
            val progress = teleportTimer / TELEPORT_ANIMATION_TIME
            if (!teleportArriving) {
                // Teleporting up
                opacity *= 1 - progress
                stretch = icon.frame * TELEPORT_IMAGE_STRETCH
            } else {
                // Teleporting down (the 2nd half of the animation)
                stretch = icon.frame * TELEPORT_IMAGE_STRETCH
                opacity *= progress
            }
        }

        // The death animation's fadeOut: its last frame fades out over the second before the crew member is dead.
        if (currentAction == Action.DYING && deathLinger > 0f) opacity *= max(0f, 1f - deathLinger)

        if (currentAction == Action.CLONING) {
            // Cloning has the same animation as the 2nd half of teleporting.
            // Note here though that cloneAnimationTimer goes down rather than up.
            val progress = 1 - cloneAnimationTimer / TELEPORT_ANIMATION_TIME
            spriteY -= 8
            stretch = icon.frame * TELEPORT_IMAGE_STRETCH
            opacity *= progress
        }

        if (icon.spec.blank) opacity = 0f

        // Calculate the bounds of the image
        val height = cf.height * (stretch + 1)
        val x0 = screenX.f
        val y0 = spriteY - stretch * cf.height
        val x1 = x0 + cf.width
        val y1 = y0 + height

        return SpriteDraw(cf, x0, y0, x1, y1, opacity)
    }

    private class SpriteDraw(
        val frame: Image,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val opacity: Float
    )

    fun drawPortrait(x: Int, y: Int, backgroundHighlight: Boolean, scale: Float = 1f) {
        if (portraitAnim.blank) return

        val x1 = x.f + portraitImage.width * scale
        val y1 = y.f + portraitImage.height * scale

        // CrewAnimation::RenderIcon(true) (1.6.14 0x47df60, the crew box's CrewBox::RenderIcon): the icon's border in
        // one global colour, whatever the crew member's selection (native frames u1v-a a-h-crew1, a-crew2-sel: a
        // hovered or selected crew box's portrait keeps the yellow border; the green one is the ship's sprite only).
        if (backgroundHighlight) {
            drawBackground(portraitImage, x.f, y.f, x1, y1, 1f, CREW_DESELECTED_BG)
        }

        drawImage(
            x.f, y.f, x1, y1,
            portraitImage, 1f
        )
    }

    private fun drawBackground(image: Image, x0: Float, y0: Float, x1: Float, y1: Float, opacity: Float, fixed: Colour? = null) {
        if (backImg == null) {
            return
        }

        // Bit of a hack, since we're drawn from the ship
        val isSelected = game.shipUI.isCrewHighlighted(this)

        // Draw the background image - the coloured hint that changes
        // when you mouse over the crew.
        // This is missing for drones, which makes sense as you can't
        // select them.
        val backSubImg = backImg!!.getSubImage(
            image.textureOffsetX,
            image.textureOffsetY,
            image.width,
            image.height
        )
        val backColour = when {
            fixed != null -> fixed
            showRedOutline -> CREW_HOSTILE_BG
            isSelected -> CREW_SELECTED_BG
            else -> CREW_DESELECTED_BG
        }
        backSubImg.draw(x0, y0, x1, y1, 0f, 0f, image.width.f, image.height.f, opacity, backColour)
    }

    protected open fun drawImage(
        // The corners of the image as it should be drawn on-screen.
        // This is stretched for the teleport animation.
        x0: Float, y0: Float,
        x1: Float, y1: Float,

        // The image to be drawn, from the base sprite sheet.
        baseFrame: Image,

        alpha: Float
    ) {
        baseFrame.draw(x0, y0, x1, y1, 0f, 0f, baseFrame.width.f, baseFrame.height.f, alpha, Colour.white)
    }

    open fun drawForeground(g: Graphics) {
        // It should already be pretty obvious what their health is...
        if (currentAction == Action.DYING)
            return

        // Hyperspace CrewMember::OnRenderHealth: power effect animations come before the health bar
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrewRender.drawForeground(this)

        // Draw the health bar (Hyperspace: inside the CREW_MEMBER_HEALTH render event)
        val hsHealth = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.crewHealthPre(this) else 0
        if (showHealthBar && hsHealth >= 0) {
            val healthBox = game.getImg("img/people/health_box.png")
            healthBox.draw(screenX - 1f, screenY.f)

            val friendly = (mode == SlotType.CREW) == room.ship.isPlayerShip

            val width = ceil(25f * health / maxHealth).toInt()
            g.colour = if (friendly) Colour.green else Colour.red
            g.fillRect(screenX + 4f, screenY + 3f, width.f, 3f)
        }
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaCrewEvents.crewHealthPost(hsHealth)

        // CrewMember::OnRender: a stunned crew member's icon in yellow, where a mind-controlled one shows its own.
        if (stunTime > 0f) {
            val stun = stunAnimation ?: STUN_ANIM.startLooping(game).also { stunAnimation = it }
            val stunX = if (showHealthBar) screenX.f - 11 else screenX.f + 9
            val stunY = if (showHealthBar) screenY.f - 3 else screenY.f
            stun.draw(stunX, stunY, STUN_ICON_COLOUR)
        }

        // Draw the healing sparkles centred above the crewmember's head.
        // Note drawForeground has already returned if we're dying.
        if (isBeingHealed) {
            if (healingAnimation == null)
                healingAnimation = HEALING_ANIM.startLooping(game)

            val anim = healingAnimation!!
            anim.draw(
                screenX.f + (icon.width - anim.width) / 2f,
                screenY.f - anim.height / 2f
            )
        }
    }

    open fun dealDamage(damage: AbstractCrewDamage) {
        if (game.debugFlags.noCrewDamage.set)
            return

        // If we're currently playing the dying animation, nothing can stop it.
        // Similarly, if we've hit zero health, we shouldn't then be able to
        // heal back out of it.
        // If on one update a crewmember was both damaged and then healed, they'd
        // never be able to die as long as both of those were done before the
        // crew's update function was called, as the health would be clamped at
        // zero then incremented by healing.
        if (currentAction == Action.DYING || health == 0f)
            return

        val oldHealth = health

        // Clamp max health to avoid over-healing with the heal burst
        health = (health - damage.amount).coerceIn(0f, maxHealth)
        if (oldHealth > 0f && health == 0f) game.browserAchievements.crewHealthDepleted(this)

        // Vanilla plays a warning beep when one of the player's crew is
        // damaged down to 25 HP or below (sounds.xml: lowCrewHealth ->
        // ship/bp_SFX_HealthWarning.ogg). It's edge-triggered - vanilla
        // compares the int-truncated health before and after the damage -
        // so it doesn't repeat while they stay low; healing back above the
        // threshold re-arms it. Only real crew (vanilla IsCrew()) belonging
        // to the player (vanilla iShipId == 0) beep: intruders, defenders
        // and boarding-drone pawns are silent. Ref: vanilla
        // CrewMember::DirectModifyHealth via the FTL-Hyperspace hook.
        if (damage.amount > 0f && this is LivingCrew && ownerShip?.isPlayerShip == true) {
            if (health.toInt() <= LOW_HEALTH_WARNING_HP && oldHealth.toInt() > LOW_HEALTH_WARNING_HP) {
                game.sounds.getSampleOrWarn("lowCrewHealth")?.play()
            }
        }

        // Dying is handled in the update loop
    }

    /**
     * Update the attack animation for fighting and sabotage actions.
     */
    /**
     * Pick who to fight this attack cycle: whoever occupies our cell
     * (melee) takes priority, then we stick with our current target while
     * they're still a valid hostile (the fight target at CrewMember+0x188
     * is kept, 0x47d350) and only brand-new fights pick a target at random.
     */
    internal fun pickFightingTarget(sameCell: AbstractCrew?, hostiles: List<AbstractCrew>): AbstractCrew {
        return when {
            sameCell != null -> sameCell
            hostiles.contains(enemyToAttack) -> enemyToAttack!!
            else -> hostiles.random(ftl.browser.BrowserRandom)
        }
    }

    private fun hitCrew(target: AbstractCrew, damage: Float) {
        val enemyWasDying = target.health <= 0f
        target.dealDamage(CombatDamage(damage, this))
        if (target.health <= 0f && !enemyWasDying)
            onKilledCrew(target)
    }

    /** The damage a punch or an arriving shot banks (CrewAnimation +0x8cc): (rand%4001 + 3000) / 1000. */
    private fun rollShotDamage(): Float = (ftl.browser.BrowserRandom.nextInt(4001) + 3000) / 1000f

    /** The animation's shooting sound (CrewAnimation::GetShootingSound, vtable slot 9). */
    protected open fun shootingSoundName(): String? {
        val shotSound = when (this) {
            is CrewMantis -> "mantisSpit"
            is CrewRock, is CrewCrystal -> "rockThrow"
            else -> "crewLaser"
        }
        return "$shotSound${(1..3).random(ftl.browser.BrowserRandom)}"
    }

    private fun rollShotTimer(): Float = (shotTimerMinMs + rollSpan(shotTimerSpanMs)) / 1000f

    private fun rollSpan(span: Int): Int = if (span > 0) ftl.browser.BrowserRandom.nextInt(span) else 0

    /** CrewMember::OnLoop (0x482a80) -> CrewAnimation::SetDirection (0x47e550): aim at [point] this tick. */
    private fun setAim(point: IPoint, exact: Boolean) {
        aimX = point.x
        aimY = point.y
        hasAim = true
        aimExact = exact
        val from = getPixelPositionCentre()
        val aim = CrewAim.aim((point.x - from.x).f, (point.y - from.y).f)
        aimLean = aim.lean
        aimDirection = aim.direction
    }

    /**
     * CrewAnimation::UpdateShooting (0x481de0): an aim point in another room more than 22 px away can't be shot at
     * ([onBlocked]: the animation banks 0.1). Otherwise the aim point moves by rand%17-8 pixels on each axis (x rolled
     * before y) unless the target is exact (doors), the race's shooting sound plays and the projectile flies there;
     * [onLanded] runs when it arrives.
     */
    private fun fireCrewShot(onLanded: (() -> Unit)?, onBlocked: (() -> Unit)?) {
        val ship = room.ship
        val from = getPixelPositionCentre()
        val fromRoom = ship.rooms.firstOrNull { it.containsPixelInclusive(from.x, from.y) }
        val aimRoom = ship.rooms.firstOrNull { it.containsPixelInclusive(aimX, aimY) }
        val dx = (aimX - from.x).f
        val dy = (aimY - from.y).f
        if (fromRoom != aimRoom && sqrt(dx * dx + dy * dy) > 22f) {
            onBlocked?.invoke()
            return
        }

        if (!aimExact) {
            aimX += ftl.browser.BrowserRandom.nextInt(17) - 8
            aimY += ftl.browser.BrowserRandom.nextInt(17) - 8
        }

        // The animation's shooting sound (vtable slot 9): Mantis mantisSpit1-3
        // (0x47f4e0), Rock and Crystal rockThrow1-3 (0x47f5e0), others crewLaser1-3 (0x47f3e0).
        shootingSoundName()?.let { game.sounds.getSampleOrWarn(it)?.play() }

        ship.spawnCrewShot(from, Point(aimX, aimY), room, crewShotImage, crewShotColour, onLanded)
    }

    /**
     * The shooting state (CrewAnimation::OnUpdate 0x482420, state 7) for fights, door attacks and sabotage. FireShot
     * runs before the strip advances: Rock/Crystal/Mantis throw once per pass when the strip is on frame 3, every
     * other race when its shot timer is done (TimerHelper::Done 0x5cb200: elapsed past the goal, then a new goal).
     */
    private fun updateShooting(action: Action, dt: Float, fire: () -> Unit) {
        // A punch that follows starts a fresh punch cycle.
        attackTimer = null
        currentAction = action

        // Without a strip there is no throwing frame to wait for, so the shot timer decides.
        val strip = if (throwsOnShootFrame) anims.getOrNull("${animationRace}_shoot_${dirAsString(shootDirection())}") else null
        if (strip != null) {
            val total = strip.totalTime
            val frame = if (total > 0f) (shootStripTime / total * strip.length).toInt().coerceIn(0, strip.length - 1) else 0
            if (frame == CrewAim.THROW_FRAME) {
                if (!shootFrameThrown) {
                    shootFrameThrown = true
                    fire()
                }
            } else if (frame < CrewAim.THROW_FRAME) {
                shootFrameThrown = false
            }
            if (total > 0f)
                shootStripTime = (shootStripTime + dt) % total
        } else {
            if (shotTimerGoal < 0f)
                shotTimerGoal = rollShotTimer()
            shotTimerElapsed += dt
            if (shotTimerGoal <= shotTimerElapsed && shotTimerElapsed != shotTimerGoal) {
                shotTimerElapsed = 0f
                shotTimerGoal = rollShotTimer()
                fire()
            }
        }

        refreshShootPose()
    }

    /** Which way the shooting animation faces. */
    private fun shootDirection(): Direction = when {
        // When sabotaging, boarders in a two-cell room face the obvious
        // centre, and in a four-cell room the top two point down, the
        // bottom-left boarder faces right, and the bottom-right one
        // faces left.
        currentAction == Action.SABOTAGE -> {
            val pos = roomPosition ?: ConstPoint.ZERO
            when {
                room.width == 1 && pos.y == 0 -> Direction.DOWN
                room.width == 1 && pos.y == 1 -> Direction.UP

                room.height == 1 && pos.x == 0 -> Direction.RIGHT
                room.height == 1 && pos.x == 1 -> Direction.LEFT

                pos.y == 0 -> Direction.DOWN
                pos.x == 0 -> Direction.RIGHT
                else -> Direction.UP
            }
        }

        // If two parties are in the same cell, the crewmember stands
        // at the top and the intruder stands at the bottom.
        currentAction == Action.FIGHTING && sharesHostileCell && mode == SlotType.CREW -> Direction.DOWN
        currentAction == Action.FIGHTING && sharesHostileCell && mode == SlotType.INTRUDER -> Direction.UP

        // When combat is starting, we might not have the target selected yet.
        !hasAim -> Direction.UP

        // Otherwise face the aim point (a crewmember, or the middle of the door) as SetDirection set it this tick;
        // the scatter a shot adds to the point doesn't turn the shooter.
        else -> aimDirection
    }

    /**
     * Set the shoot pose's frame: Rock/Crystal/Mantis show their strip at its own clock, the other races hold the
     * aim frame (CrewAnimation::OnRender 0x477f20 -> GetFiringFrame 0x477c30).
     */
    private fun applyShootPoseTime(pose: FTLAnimation, direction: Direction) {
        if (throwsOnShootFrame) {
            pose.timer = shootStripTime.coerceIn(0f, pose.duration)
            return
        }
        val frame = CrewAim.firingFrame(direction, aimLean).coerceIn(0, pose.frameCount - 1)
        pose.timer = (frame + 0.5f) * pose.duration / pose.frameCount
    }

    private fun refreshShootPose() {
        val pose = shootPoseIcon
        val direction = shootDirection()
        if (isPunching || pose == null || icon !== pose || shootPoseDirection != direction) {
            updateAnimation()
            return
        }
        applyShootPoseTime(pose, direction)
    }

    /**
     * RockAnimation::UpdateFiring (0x47f100): a fire-fighting Rock stomps once per pass of its strip, at frame 9;
     * the player's Rocks play rockStomp1-2.
     */
    private fun continueRockStomp() {
        if (icon.frame < ROCK_STOMP_FRAME) {
            rockStompLatched = false
            return
        }
        if (rockStompLatched)
            return
        rockStompLatched = true
        if (this is CrewRock && ownerShip?.isPlayerShip == true)
            game.sounds.getSampleOrWarn("rockStomp${1 + ftl.browser.BrowserRandom.nextInt(2)}")?.play()
    }

    private fun updateAttack(action: Action, dt: Float, dealDamage: () -> Unit, onAnimationStart: () -> Unit) {
        // Reset everything if we switch between attacking and sabotaging
        // Otherwise we could end up doing damage after we started attacking
        // a system, and have a null enemy we're shooting at. A fight has just
        // worked out whether it punches, so keep that.
        if (currentAction != action) {
            attackTimer = null
            enemyToAttack = null
            if (action != Action.FIGHTING)
                isPunching = false
        }
        currentAction = action

        // Melee fights play the brawling loop while punches fly - vanilla
        // behaviour, the volume scales with the number of fighting crew.
        if (action == Action.FIGHTING && isPunching)
            brawlingLoop.continueLoopAnyShip()

        if (attackTimer != null) {
            attackTimer = attackTimer!! - dt
        }

        if (attackTimer != null && attackTimer!! <= damageTime) {
            damageTime = -1f

            dealDamage()
        }

        if (attackTimer == null || attackTimer!! <= 0f) {
            // A punch (CrewAnimation::OnUpdate 0x482420) restarts the punch timer, (rand%150 + 500) ms, and plays
            // the punch for twice that; the Ion intruder's door bashing keeps a 1.0..1.3 s cycle.
            attackTimer = if (action == Action.FIGHTING)
                2f * (punchTimerMinMs + rollSpan(punchTimerSpanMs)) / 1000f
            else (1.0f..1.3f).random(ftl.browser.BrowserRandom)
            attackDuration = attackTimer!!

            // Apply the damage half-way through the animation
            damageTime = attackTimer!! / 2f

            onAnimationStart()

            // Restart the fighting animation, and set its duration equal to that of the attack.
            updateAnimation()
        }
    }

    /**
     * Recalculate (via pathfinding) the player's current [nextTargetPos] to get to
     * their desired target.
     */
    private fun updateMovement() {
        // Clear these out, so they don't stick around when we're not pathing to a door.
        // Otherwise this could result in us attacking a door in the update loop, if
        // we're supposed to be idle.
        val oldDoorToAttack = doorToAttack
        val oldAttackPos = doorAttackPos
        doorAttackPos = null
        doorToAttack = null

        val currentTarget = pathingTarget ?: return

        // It's fine to compare floats, since we snap into position.
        if (currentTarget.offsetX.f == pixelSpaceX && currentTarget.offsetY.f == pixelSpaceY) {
            pathingTarget = null
            return
        }

        // Rather than using roomPosition, recalculate it here from our centre.
        // This is because we might have our movement changed when we're already moving.
        val roomPos = findNearestRoomPos()

        // If we're already in the same cell as our target, we can safely walk there.
        // Using the pathfinder wouldn't do anything useful - indeed, we'd get
        // a null pointer exception since [next] would be null.
        if (roomPos == currentTarget) {
            nextTargetPos = ConstPoint(roomPos.offsetX, roomPos.offsetY)
            return
        }

        val next = nextStep(roomPos, currentTarget)

        // Walking through doorways is a bit more complicated than
        // other cases, because doors can be locked. Thus this is split
        // into its own method.
        if (next.room != room) {
            nextTargetPos = walkThroughDoor(roomPos, next, oldDoorToAttack, oldAttackPos)
            return
        }

        // We're being to walk somewhere in the same room. If we were walking
        // through a doorway when our target was changed, first walk back fully
        // inside the room before we go anywhere else.
        // We need to do this rather than just grid-aligning ourselves, to avoid
        // walking in odd directions if we were misaligned due to attacking a door.
        val roomX = room.offsetX
        val roomY = room.offsetY
        val clampedX = pixelPosition.x.coerceIn(roomX..roomX + room.pixelWidth - ROOM_SIZE)
        val clampedY = pixelPosition.y.coerceIn(roomY..roomY + room.pixelHeight - ROOM_SIZE)

        if (clampedX == pixelPosition.x && clampedY == pixelPosition.y) {
            // We're already within the room limits
            nextTargetPos = ConstPoint(next.offsetX, next.offsetY)
        } else {
            nextTargetPos = ConstPoint(clampedX, clampedY)
        }
    }

    /**
     * The next cell to walk to: the target when it's in this room, otherwise this room's side of the route's next
     * door, and once there the cell through it.
     */
    private fun nextStep(roomPos: RoomPoint, target: RoomPoint): RoomPoint {
        if (roomPos.room == target.room)
            return target

        var door = leavingDoor(roomPos.room)
        if (door == null) {
            doorRoute = room.ship.pathFinder.doorRoute(this, pixelPositionCentre, roomPos.room, target.room)
            door = leavingDoor(roomPos.room)
        }
        if (door == null) {
            val pf = room.ship.pathFinder
            pf.path(target)
            return pf.nodes.getValue(roomPos).next!!.pos
        }

        val here = if (door.left == roomPos.room) door.leftPos!! else door.rightPos!!
        val there = if (door.left == roomPos.room) door.rightPos!! else door.leftPos!!
        return if (roomPos == here) there else here
    }

    /** The route's door out of [from]: the last route door touching it. */
    private fun leavingDoor(from: Room): Door? = doorRoute?.lastOrNull { it.left == from || it.right == from }

    private fun walkThroughDoor(
        current: RoomPoint,
        next: RoomPoint,
        oldDoorToAttack: Door?,
        oldAttackPos: IPoint?
    ): IPoint {
        // Figure out what door we're supposed to walk through.
        // It's fine to only check next in hasRoomPos, since we should
        // only ever have one door in a single room that can connect
        // to a given cell in another room.
        val door = current.room.doors.first { it.hasRoomPos(next) }

        // If we're not either grid-aligned or already walking through
        // the door, align ourselves.
        if (roomPosition == null && !door.checkPlayerPos(room, pixelPosition)) {
            return ConstPoint(current.offsetX, current.offsetY)
        }

        // Unless the door is locked, we can walk right through (a phasing crew member also through a locked one).
        if (!door.isLockedFor(this) || (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.crew.HsCrewEffects.phases(this))) {
            return ConstPoint(next.offsetX, next.offsetY)
        }

        // If we're already attacking this door, don't move.
        if (oldDoorToAttack == door) {
            oldAttackPos?.let {
                doorToAttack = oldDoorToAttack
                doorAttackPos = it
                return it
            }
        }

        // Align ourselves with some point along the face of the door, and
        // from there we'll start shooting at it.

        // How far we have to move towards the door to stand in the shooting position.
        // There's 11 set somewhere in vanilla (CrewMember::GetNewGoal) for this, but
        // I can't figure out how it's related, and our value was measured from
        // a screenshot so it should be reasonable.
        val gapOffset = 8

        // The position along the face of the door to stand at
        val faceOffset = (-8..8).random(ftl.browser.BrowserRandom)

        // This is the top-left corner of the door
        val doorX = door.offsetX
        val doorY = door.offsetY

        val currentX = current.offsetX
        val currentY = current.offsetY

        // Line up along the face of the door, with the previously selected
        // random offset to make multiple crew visible.
        val pos = when {
            door.isVertical && pixelSpaceX < doorX -> ConstPoint(currentX + gapOffset, doorY + faceOffset)
            door.isVertical -> ConstPoint(currentX - gapOffset, doorY + faceOffset)

            pixelSpaceY < doorY -> ConstPoint(doorX + faceOffset, currentY + gapOffset)
            else -> ConstPoint(doorX + faceOffset, currentY - gapOffset)
        }
        doorAttackPos = pos
        doorToAttack = door
        return pos
    }

    private fun dirAsString(dir: Direction): String {
        return when (dir) {
            Direction.UP -> "up"
            Direction.DOWN -> "down"
            Direction.LEFT -> "left"
            Direction.RIGHT -> "right"

            Direction.RIGHT_DOWN -> "right"
            Direction.LEFT_UP -> "up"
            Direction.DOWN_LEFT -> "left"
            Direction.UP_RIGHT -> "up"
        }
    }

    protected open fun updateAnimation() {
        icon = when (currentAction) {
            Action.IDLE -> portraitAnim.startLooping(game)

            Action.MOVING -> {
                // We can have a null targetPos for a short amount of time, while
                // changing as we reach a target position before it's reset.
                val target = nextTargetPos ?: return

                val deltaX = target.x - pixelSpaceX
                val deltaY = target.y - pixelSpaceY
                val direction = Direction.bestFit(deltaX, deltaY)

                // If we're already using the right animation, don't restart it.
                if (walkDirection == direction)
                    return
                walkDirection = direction

                anims.crewStrip("${animationRace}_walk_${dirAsString(direction)}").startLooping(game)
            }

            // A console without a facing (a blueprint slot with no direction) keeps the current animation.
            Action.MANNING -> anims.crewStrip("${animationRace}_type_${dirAsString(room.system!!.configuration.computerDirection ?: return)}")
                .startLooping(game)

            Action.REPAIRING, Action.REPAIRING_BREACH -> anims.crewStrip("${animationRace}_repair").startLooping(game)

            Action.FIGHTING, Action.SABOTAGE, Action.ATTACKING_DOOR -> when {
                // Punches face the shared cell: the crewmember stands at the top and the intruder at the bottom.
                // We have to update the icon each time the punch timer expires, so make it obvious if that isn't
                // done by not looping. The punch lasts twice the punch timer (CrewAnimation::OnUpdate 0x482420);
                // leave it at its default 1 second until the timer is set (attackDuration, not attackTimer, so the
                // duration survives serialisation).
                isPunching -> {
                    val dir = when {
                        sharesHostileCell && mode == SlotType.INTRUDER -> Direction.UP
                        sharesHostileCell -> Direction.DOWN
                        // The enemy just left the cell. The game has punches only up and down.
                        enemyToAttack == null || mode == SlotType.INTRUDER -> Direction.UP
                        else -> Direction.DOWN
                    }
                    shootPoseIcon = null
                    val punch = anims.crewStrip("${animationRace}_punch_${dirAsString(dir)}").startSingle(game)
                    if (attackTimer != null)
                        punch.duration = attackDuration
                    punch
                }

                // The Ion intruder's own animation keeps replaying its strip once per attack cycle.
                !firesCrewShots -> {
                    shootPoseIcon = null
                    val shoot = anims.crewStrip("${animationRace}_shoot_${dirAsString(shootDirection())}").startSingle(game)
                    if (attackTimer != null)
                        shoot.duration = attackDuration
                    shoot
                }

                // The shooting pose: Rock/Crystal/Mantis run their throwing strip on its own clock, the other races
                // hold one aim frame (CrewAnimation::OnRender 0x477f20). updateShooting drives both.
                else -> {
                    val dir = shootDirection()
                    val pose = anims.crewStrip("${animationRace}_shoot_${dirAsString(dir)}").startSingle(game)
                    pose.isPaused = true
                    shootPoseIcon = pose
                    shootPoseDirection = dir
                    applyShootPoseTime(pose, dir)
                    pose
                }
            }

            // Don't loop, we'll disappear when the animation finishes
            Action.DYING -> anims.crewStrip(deathAnimationName).startSingle(game)

            Action.TELEPORTING -> {
                // If we're beaming back down on the destination ship, play it backwards.
                val backwards = teleportArriving

                // Stop at the end, as it would look weird if we restarted for a moment.
                anims.crewStrip("${animationRace}_teleport").startSingle(game, 1f, backwards)
            }

            Action.CLONING -> {
                // This just re-uses the teleport animation
                anims.crewStrip("${animationRace}_teleport").startSingle(game, 1f, true)
            }

            Action.FIRE_FIGHTING -> {
                // Note that currentFireSlot can be zero while we're deserialising
                // It'll later be changed and updateAnimation called again, but in
                // the meantime we mustn't crash.
                val firePos = when {
                    currentFireSlot == -1 -> ConstPoint.ZERO
                    else -> room.slotToPoint(currentFireSlot)
                }

                val direction: Direction = if (firePos posEq roomPosition!!) {
                    // If it's in the same cell as us, point down.
                    Direction.DOWN
                } else {
                    Direction.bestFit(roomPosition!!, firePos)
                }

                anims.crewStrip("${animationRace}_fire_${dirAsString(direction)}").startLooping(game)
            }
        }

        if (currentAction != Action.MOVING) {
            walkDirection = null
        }
    }

    fun setTargetRoom(value: Room): Boolean {
        val computerPoint = value.system?.configuration?.computerPoint
        if (computerPoint != null)
            if (setTargetRoom(value, computerPoint))
                return true

        // If we're being told to go to our current room, see if our
        // current position is still valid. This might not be the case
        // if this was called by updateCrewReservedSlots.
        val currentPos = standingPosition
        if (value == room && currentPos != null) {
            if (setTargetRoom(room, currentPos)) {
                return true
            }
        }

        val freeSlot = value.firstFreeSlot(mode)
        if (freeSlot != null) {
            if (setTargetRoom(value, freeSlot)) {
                return true
            }
        }

        return false
    }

    /**
     * The player's move order, ShipManager::CommandCrewMoveRoom (0x49ea80) = CrewMember::MoveToRoom(room, -1,
     * false) (0x4809b0): nothing happens for a dead crew member, one whose slot (CrewMember +0x88, the slot it
     * stands in or walks to) is already in that room, or one that is stunned while standing at its slot.
     */
    fun commandMoveRoom(value: Room): Boolean {
        if (health <= 0f) return false
        val slotRoom = pathingTarget?.room ?: standingPosition?.room ?: room
        if (slotRoom == value) return false
        if (stunTime > 0f && pathingTarget == null && nextTargetPos == null) return false
        return setTargetRoom(value)
    }

    /** The slot this crew member stands in or walks to (CrewMember::currentSlot). */
    val currentSlotPoint: RoomPoint get() = pathingTarget ?: standingPosition ?: findNearestRoomPos()

    /**
     * [setTargetRoom] to one slot that [vacating] may still hold: Hyperspace's tile movement swaps two crew
     * (TileBasedCrewMovement.cpp: swapCrew->EmptySlot, then MoveToRoom(room, tile, true) for both) inside
     * [Ship.deferCrewSlotUpdates], so the slot bookkeeping runs once both have their new goals.
     */
    fun setTargetSlot(value: Room, pos: IPoint, vacating: AbstractCrew?): Boolean = setTargetRoom(value, pos, vacating)

    fun setTargetRoom(value: Room, pos: IPoint): Boolean = setTargetRoom(value, pos, null)

    private fun setTargetRoom(value: Room, pos: IPoint, vacating: AbstractCrew?): Boolean {
        // Hyperspace CrewMember::MoveToRoom hook: no moves from a rooted crew member's own loop.
        if (hsMoveLock) return false

        // Check if the slot is free. We have to do this *before* checking
        // if we're already pathing to or in the slot.
        // This only matters if we're not marked as occupying this slot - this
        // should never be the case, but if two crew walk into the same
        // slot somehow it will happen while the conflict is being resolved.
        if (!value.isSlotFree(pos, mode, this) &&
            (vacating == null || value.isObstructed(pos) || value.slotOccupier(pos, mode) !== vacating)
        )
            return false

        // If we're already going to, or are at, this point then do nothing.
        if (pathingTarget?.room == value && pathingTarget!! posEq pos)
            return true
        if (room == value && standingPosition?.posEq(pos) == true)
            return true

        // Verify we have a path to this room
        val pf = room.ship.pathFinder
        pf.path(RoomPoint(value, ConstPoint.ZERO))
        val pNode = pf.nodes.getValue(findNearestRoomPos())
        if (pNode.next == null && value != room)
            return false

        pathingTarget = RoomPoint(value, pos)
        return true
    }

    fun jumpTo(newPoint: RoomPoint): Unit = jumpTo(newPoint.room, newPoint)

    /**
     * Hyperspace CrewSpawn::SpawnCrew: the new crew member stands with its centre at ([centreX], [centreY]) (ship
     * pixels, inside [inRoom]) and walks to [slot] (MoveToRoom(slot, true)); [walk] false leaves it standing there.
     */
    fun hsPlaceAt(inRoom: Room, centreX: Float, centreY: Float, slot: RoomPoint, walk: Boolean) {
        jumpTo(slot)
        room = inRoom
        pixelSpaceX = centreX - ROOM_SIZE / 2f
        pixelSpaceY = centreY - ROOM_SIZE / 2f
        currentAction = Action.IDLE
        if (walk && !(slot.offsetX.f == pixelSpaceX && slot.offsetY.f == pixelSpaceY)) pathingTarget = RoomPoint(slot.room, slot)
        else room.ship.updateCrewReservedSlots()
    }

    /**
     * Set the room and position to the given values.
     *
     * This ensures movement is updated appropriately and only once.
     *
     * [newPoint] is given in units of cells, not pixels.
     */
    fun jumpTo(newRoom: Room, newPoint: IPoint) {
        room = newRoom
        pixelSpaceX = newRoom.offsetX.f + newPoint.x * ROOM_SIZE
        pixelSpaceY = newRoom.offsetY.f + newPoint.y * ROOM_SIZE


        // This has already been done when we updated the pixel positions,
        // but make it explicit since we changed rooms.
        positionChanged()

        updateMovement()

        // If we stay in the manning state and switch room, we'll crash as
        // the system will disappear.
        currentAction = Action.IDLE

        // Unless we're moving (but even then do it just to be safe),
        // we're now occupying a different slot. Thus update them all.
        room.ship.updateCrewReservedSlots()
    }

    /**
     * Immediately remove this crew from the ship. This is run after
     * the dying animation finishes.
     */
    open fun removeFromShip() {
        room.ship.crew.remove(this)
        // Saves can happen while paused, before the next combat update clears
        // an opponent's target. A removed pawn must never remain a serialised
        // target or be hit by an already queued attack.
        for (other in room.ship.crew) {
            if (other.enemyToAttack !== this) continue
            other.enemyToAttack = null
            other.attackTimer = null
            other.isPunching = false
            other.sharesHostileCell = false
            if (other.currentAction.isAttackAction) {
                other.currentAction = Action.IDLE
                other.updateAnimation()
            }
        }
        room.ship.updateCrewReservedSlots()

        // If we're paused, make sure this applies immediately
        room.ship.sys.updatePlayerCrew()
    }

    /** Hyperspace: a crew drone's kept body deploys again (NativeDrone.hsCrewActor); its last fight does not carry over. */
    internal fun hsClearFight() {
        enemyToAttack = null
        attackTimer = null
        isPunching = false
        sharesHostileCell = false
        doorRoute = null
        pathingTarget = null
    }

    open fun onStartedDying() {
        game.browserAchievements.crewStartedDying(this)
        // Death sounds: engi, mantis, rock, zoltan and crystal crew have
        // race-specific ones (the engiDeath wiring is user-confirmed by
        // ear against vanilla); every other race (human, slug, ghost,
        // anaerobic) uses the generic gendered set death1-2 /
        // femaleDeath1-3 (the native default). The old slug mapping played shrikeDeath, a
        // leftover of the cut Shrike race that sounds like the Engi death.
        // Native: each CrewAnimation subclass's death sound (vtable slot
        // 10): Lanius use shrikeDeath (AnaerobicAnimation 0x768c80); the
        // Zoltan codename is "energy".
        deathSoundName()?.let { game.sounds.getSampleOrWarn(it)?.play() }
    }

    /** The animation's death sound (CrewAnimation::GetDeathSound, vtable slot 10). */
    protected open fun deathSoundName(): String? {
        return when (codename) {
            "engi" -> "engiDeath"
            "mantis" -> "mantisDeath"
            "rock" -> "rockDeath"
            "energy" -> "energyDeath"
            "crystal" -> "crystalDeath"
            "anaerobic" -> "shrikeDeath"
            else -> if ((this as? LivingCrew)?.info?.isFemale == true)
                "femaleDeath${(1..3).random(ftl.browser.BrowserRandom)}"
            else
                "death${(1..2).random(ftl.browser.BrowserRandom)}"
        }
    }

    open fun onFinishedDying() {
        removeFromShip()
    }

    /**
     * CrewMember::Restart called on its own (Lua): full health (GetMaxHealth), alive, no fire, not
     * suffocating, no repair or crew target, the crew animation restarted, and the crew member stands in its slot
     * (the one it walks to, when walking) with its path cleared.
     */
    open fun hsRestart() {
        health = maxHealth
        val slot = currentSlotPoint
        pathingTarget = null
        nextTargetPos = null
        jumpTo(slot)
        if (currentAction != Action.TELEPORTING && currentAction != Action.CLONING) currentAction = Action.IDLE
    }

    open fun onCloned() {
        health = maxHealth

        // Play the teleport animation after cloning.
        cloneAnimationTimer = TELEPORT_ANIMATION_TIME

        // Clear out movement for the same reason as when teleporting - we might
        // be respawning on a different ship than the one we were killed on.
        // In any case, in vanilla crew don't run back to the place they died.
        nextTargetPos = null
        pathingTarget = null

        // Switch to the cloning state immediately, so we don't spend one frame
        // with our old animation.
        currentAction = Action.CLONING
    }

    /**
     * Called whenever this crewmember does the final point of repair to fix
     * a system by a level.
     */
    open fun onFinishedRepair(sys: AbstractSystem) {
    }

    /**
     * Called whenever this crewmember finishes repairing a breach.
     */
    open fun onFinishedBreachRepair() {
    }

    /**
     * Called whenever this crewmember finishes putting out a fire.
     */
    open fun onFinishedExtinguishing() {
    }

    /** Called every update while this crewmember is in a fight with a target. */
    protected open fun onFighting() {
    }

    open fun onKilledCrew(enemy: AbstractCrew) {
        game.browserAchievements.crewKilled(this, enemy)
    }

    open fun onSabotagedSystem(system: AbstractSystem) {
        if (system.broken && !game.isCurrentlyLoadingSave)
            browserTargetsDestroyed = browserTargetsDestroyed?.plus(1)
    }

    /**
     * Teleport to a room on an enemy ship, playing the teleport animation.
     */
    fun teleportAnimatedTo(room: Room) {
        // Make sure we're teleporting to another ship
        require(room.ship != this.room.ship)

        teleportingTo = room
        teleportTimer = 0f; hsTelePassed = false; hsTeleDone = 0
        teleportArriving = false
        // Hyperspace CrewMember::StartTeleport hook: customTele.teleporting until the animation ends.
        if (xyz.znix.xftl.hs.HS.active) hsTeleporting = true
    }

    /**
     * Hyperspace teleportMove: CrewMember::StartTeleport towards [room] on either ship (the same ship included); the
     * armed slot ([hsTeleSlot]) is taken on arrival when free.
     */
    /** Lua CrewMember::StartTeleport with no armed destination: see [update]. */
    @JvmField var hsTeleLimbo = false

    /** The departure animation of [hsTeleLimbo] has played: the crew member is between ships (currentShipId -1). */
    val hsInTeleportLimbo: Boolean get() = hsTeleLimbo && (hsTeleDone >= 2 || this !in room.ship.crew)

    fun hsStartTeleportInPlace() {
        teleportingTo = room
        teleportTimer = 0f; hsTelePassed = false; hsTeleDone = 0
        teleportArriving = false
        hsTeleporting = true
        hsTeleLimbo = true
        currentAction = Action.TELEPORTING
    }

    /** Lua CrewMember::StartTeleportArrive: [startTeleportArrive], the animation status set at once. */
    fun hsStartTeleportArriveNow() {
        startTeleportArrive()
        currentAction = Action.TELEPORTING
    }

    /**
     * CrewMember_Extend::InitiateTeleport(shipId, roomId, slotId) (CustomCrew.cpp, Lua): StartTeleport (the departure
     * animation at once), then customTele armed with the destination. The departure is not interrupted by the
     * teleport checks; a room of -1 becomes a random room of that ship when it ends.
     */
    fun hsInitiateTeleport(shipId: Int, roomId: Int, slotId: Int) {
        val target = if (shipId == 0) room.ship.sys.player else if (shipId == 1) room.ship.sys.enemy else null
        hsStartTeleportMove(target?.rooms?.getOrNull(roomId) ?: room)
        hsTeleLimbo = false
        currentAction = Action.TELEPORTING
        hsTeleShip = shipId; hsTeleRoom = roomId; hsTeleSlot = slotId
    }

    fun hsStartTeleportMove(room: Room) {
        teleportingTo = room
        teleportTimer = 0f; hsTelePassed = false; hsTeleDone = 0
        teleportArriving = false
        hsTeleporting = true
    }

    /**
     * CrewMember::StartTeleportArrive: play the arrival half of the teleport
     * animation where the crewmember already stands, as event boarders do.
     */
    fun startTeleportArrive() {
        teleportingTo = room
        teleportTimer = 0f; hsTelePassed = false; hsTeleDone = 0
        teleportArriving = true
    }

    /**
     * A hook function for subclasses, called just after the crew swaps
     * between the two ships.
     */
    protected open fun onMidTeleport() {
    }

    /**
     * Find the RoomPos that best fits our current pixel position.
     *
     * This is distinct from [roomPosition], which is null unless
     * we're exactly pixel-aligned.
     */
    fun findNearestRoomPos(): RoomPoint {
        return RoomPoint(
            room,
            (pixelPositionCentre.x - room.offsetX) / ROOM_SIZE,
            (pixelPositionCentre.y - room.offsetY) / ROOM_SIZE
        )
    }

    private fun positionChanged() {
        pixelPosition.set(pixelSpaceX.toInt(), pixelSpaceY.toInt())
        pixelPositionCentre.x = pixelPosition.x + ROOM_SIZE / 2
        pixelPositionCentre.y = pixelPosition.y + ROOM_SIZE / 2

        // Check we're pixel-aligned. Being exact about floats is fine
        // since we snap into position anyway.
        if (pixelSpaceX.toInt().toFloat() != pixelSpaceX || pixelSpaceY.toInt().toFloat() != pixelSpaceY) {
            roomPosition = null
            return
        }

        // Calculate our cell-aligned position, if we are cell-aligned.
        val roomPosX = pixelPosition.x - room.offsetX
        val roomPosY = pixelPosition.y - room.offsetY
        if (roomPosX % ROOM_SIZE != 0 || roomPosY % ROOM_SIZE != 0) {
            roomPosition = null
            return
        }

        val cellX = roomPosX / ROOM_SIZE
        val cellY = roomPosY / ROOM_SIZE

        // Only update the position if it has changed, to save an allocation.
        val currentPos = roomPosition
        if (currentPos != null && currentPos.x == cellX && currentPos.y == cellY && currentPos.room == room) {
            return
        }

        roomPosition = RoomPoint(room, cellX, cellY)
    }

    private fun selectFireOrBreach(currentSlot: Int, slots: Array<*>): Int {
        var newSlot = currentSlot
        var changedSlot = false
        if (newSlot != -1 && slots[newSlot] == null) {
            // The fire we were fighting has gone out, so either pick
            // a different one or switch to another action.
            newSlot = -1
            changedSlot = true
        }
        if (newSlot == -1 && canRepair) {
            newSlot = findFirstFireOrBreach(slots)
        }
        if (newSlot == -1)
            return newSlot

        // We have to change our animation if we move on to a new fire.
        if (changedSlot) {
            updateAnimation()
        }
        return newSlot
    }

    private fun findFirstFireOrBreach(slots: Array<*>): Int {
        // This order is annoyingly different to the crew slot order
        for (x in 0 until room.width) {
            for (y in 0 until room.height) {
                val idx = x + y * room.width

                if (slots[idx] != null) {
                    return idx
                }
            }
        }

        return -1
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addObjectId(elem, refs, this)
        elem.setAttribute("type", codename)
        savedPosition?.let { SaveUtil.addPoint(elem, "savedPos", it) }
        hsFinalGoal?.let { SaveUtil.addPoint(elem, "hsFinalGoal", it) }

        // We can't use the normal point serialisation since
        // it doesn't support floating-point values.
        SaveUtil.addAttrFloat(elem, "x", pixelSpaceX)
        SaveUtil.addAttrFloat(elem, "y", pixelSpaceY)

        SaveUtil.addAttrFloat(elem, "health", health)
        browserTargetsDestroyed?.let { elem.setAttribute("browserTargetsDestroyed", it.toString()) }
        SaveUtil.addAttrFloat(elem, "browserStunTime", stunTime)
        // CrewMember +0x68 of a sabotaging boarder: the system is the one in the room it stands in.
        if (sabotagingSystem != null)
            elem.setAttribute("browserSabotaging", "true")
        SaveUtil.addAttr(elem, "action", currentAction.name)

        if (pathingTarget != null) {
            SaveUtil.addRoomPoint(elem, "pathingTarget", pathingTarget!!)
        }

        if (nextTargetPos != null) {
            SaveUtil.addPoint(elem, "nextTargetPos", nextTargetPos!!)
        }

        if (doorToAttack != null) {
            val doorAttackElem = Element("doorToAttack")
            val doorId = room.ship.doors.indexOf(doorToAttack!!)
            SaveUtil.addAttrInt(doorAttackElem, "id", doorId)
            SaveUtil.addAttrInt(doorAttackElem, "standX", doorAttackPos!!.x)
            SaveUtil.addAttrInt(doorAttackElem, "standY", doorAttackPos!!.y)
            elem.addContent(doorAttackElem)
        }

        // Combat stuff
        if (currentAction.isAttackAction) {
            val combat = Element("combat")
            SaveUtil.addAttrFloat(combat, "attackTimer", attackTimer)
            SaveUtil.addAttrFloat(combat, "attackDuration", attackDuration)
            SaveUtil.addAttrFloat(combat, "damageTimer", damageTime)
            SaveUtil.addAttrRef(combat, "enemy", refs, enemyToAttack)
            SaveUtil.addAttrBool(combat, "isPunching", isPunching)
            SaveUtil.addAttrBool(combat, "sharesHostileCell", sharesHostileCell)
            // isPunching and sharesHostileCell are only used for animations
            elem.addContent(combat)
        }

        if (teleportingTo != null) {
            val teleport = Element("teleporting")
            teleport.setAttribute("destRoom", teleportingTo!!.id.toString())
            teleport.setAttribute("destShip", refs[teleportingTo!!.ship])
            teleport.setAttribute("timer", teleportTimer.toString())
            if (teleportArriving) teleport.setAttribute("arriving", "true")
            elem.addContent(teleport)
        }
        if (hsTeleShip != -1 || hsTeleporting || hsTeleRoom != 0 || hsTeleSlot != 0 || hsTeleLimbo) {
            val tele = Element("hsCustomTele")
            tele.setAttribute("ship", hsTeleShip.toString())
            tele.setAttribute("room", hsTeleRoom.toString())
            tele.setAttribute("slot", hsTeleSlot.toString())
            tele.setAttribute("teleporting", hsTeleporting.toString())
            if (hsTeleLimbo) tele.setAttribute("limbo", "true")
            elem.addContent(tele)
        }
        if (hsFrozen || hsFrozenLocation) {
            val frozen = Element("hsFrozen")
            frozen.setAttribute("frozen", hsFrozen.toString())
            frozen.setAttribute("location", hsFrozenLocation.toString())
            elem.addContent(frozen)
        }

        if (currentAction == Action.CLONING) {
            SaveUtil.addTagFloat(elem, "cloneTimer", cloneAnimationTimer)
        }

        if (currentAction == Action.FIRE_FIGHTING) {
            SaveUtil.addTagInt(elem, "fireSlot", currentFireSlot)
        }

        if (currentAction == Action.REPAIRING_BREACH) {
            SaveUtil.addTagInt(elem, "breachSlot", currentBreachSlot)
        }

        // Serialise the animation progress, so we can use it for stuff like
        // checking if the dying animation has finished.
        SaveUtil.addAttrFloat(elem, "animTimer", icon.timer)
    }

    open fun loadFromXML(elem: Element, refs: RefLoader) {
        SaveUtil.registerObjectId(elem, refs, this)
        savedPosition = elem.getChild("savedPos")?.let { SaveUtil.getPoint(elem, "savedPos") }
        hsFinalGoal = elem.getChild("hsFinalGoal")?.let { SaveUtil.getPoint(elem, "hsFinalGoal") }

        // The 'type' property is already read by the class that spawns us,
        // we just need to check it.
        require(codename == elem.getAttributeValue("type")) { "Wrong crewmember type was spawned!" }

        val savedX = SaveUtil.getAttrFloat(elem, "x")
        val savedY = SaveUtil.getAttrFloat(elem, "y")

        // Correctly set what room we're in - as soon as we start setting
        // target positions and stuff like that, we'll be updating the
        // occupied cells which needs to know what room we're in.
        val centre = ConstPoint(savedX.toInt() + ROOM_SIZE / 2, savedY.toInt() + ROOM_SIZE / 2)
        val savedRoom = room.ship.rooms.firstOrNull { it.containsShipSpace(centre) }
        // A clone queue member that died aboard the other ship (a boarder) was saved with that ship's coordinates; it is
        // in no room of the clone bay's ship until cloneFinished places it, so it keeps its spawn position there.
        if (savedRoom != null) {
            pixelSpaceX = savedX
            pixelSpaceY = savedY
            room = savedRoom
        }
        positionChanged()

        health = SaveUtil.getAttrFloat(elem, "health")
        browserTargetsDestroyed = elem.getAttributeValue("browserTargetsDestroyed")?.toIntOrNull()?.coerceAtLeast(0)
        stunTime = elem.getAttributeValue("browserStunTime")?.toFloat() ?: 0f
        sabotagingSystem = if (elem.getAttributeValue("browserSabotaging") == "true") room.system else null
        currentAction = Action.valueOf(SaveUtil.getAttr(elem, "action"))

        if (elem.getChild("pathingTarget") != null) {
            pathingTarget = SaveUtil.getRoomPoint(elem, "pathingTarget", room.ship)
        } else {
            // This might be required if we're spawned into the wrong
            // room, while we're being created but before being deserialised.
            pathingTarget = null
        }

        // Have to deserialise this after pathingTarget, since setting
        // that overwrites it.
        if (elem.getChild("nextTargetPos") != null) {
            nextTargetPos = SaveUtil.getPoint(elem, "nextTargetPos")
        } else {
            nextTargetPos = null
        }

        val doorAttackElem = elem.getChild("doorToAttack")
        if (doorAttackElem != null) {
            val doorId = SaveUtil.getAttrInt(doorAttackElem, "id")
            val standX = SaveUtil.getAttrInt(doorAttackElem, "standX")
            val standY = SaveUtil.getAttrInt(doorAttackElem, "standY")

            doorToAttack = room.ship.doors[doorId]
            doorAttackPos = ConstPoint(standX, standY)
        } else {
            // Just in case this is set for the same reason as nextTargetPos.
            doorToAttack = null
            doorAttackPos = null
        }

        // Combat stuff
        val combat = elem.getChild("combat")
        if (currentAction.isAttackAction) {
            attackTimer = combat.getAttributeValue("attackTimer")?.toFloatOrNull()
            attackDuration = SaveUtil.getAttrFloat(combat, "attackDuration")
            damageTime = SaveUtil.getAttrFloat(combat, "damageTimer")
            SaveUtil.getAttrRef(combat, "enemy", refs, AbstractCrew::class.java) { enemyToAttack = it }
            isPunching = SaveUtil.getAttrBool(combat, "isPunching")
            sharesHostileCell = SaveUtil.getAttrBool(combat, "sharesHostileCell")
        }

        val teleport = elem.getChild("teleporting")
        if (teleport != null) {
            val roomId = teleport.getAttributeValue("destRoom").toInt()
            val shipRef = teleport.getAttributeValue("destShip")
            val arriving = teleport.getAttributeValue("arriving")
            refs.asyncResolve(Ship::class.java, shipRef) {
                teleportingTo = it!!.rooms[roomId]
                // Older saves: the arrival half is the one on the destination ship.
                teleportArriving = arriving?.toBoolean() ?: (it === room.ship)
            }
            teleportTimer = teleport.getAttributeValue("timer").toFloat()
        }
        elem.getChild("hsCustomTele")?.let {
            hsTeleShip = it.getAttributeValue("ship").toInt()
            hsTeleRoom = it.getAttributeValue("room").toInt()
            hsTeleSlot = it.getAttributeValue("slot").toInt()
            hsTeleporting = it.getAttributeValue("teleporting").toBoolean()
            hsTeleLimbo = it.getAttributeValue("limbo") == "true"
        }
        elem.getChild("hsFrozen")?.let {
            hsFrozen = it.getAttributeValue("frozen").toBoolean()
            hsFrozenLocation = it.getAttributeValue("location").toBoolean()
        }

        if (currentAction == Action.CLONING) {
            cloneAnimationTimer = SaveUtil.getTagFloat(elem, "cloneTimer")
        }

        if (currentAction == Action.FIRE_FIGHTING) {
            currentFireSlot = SaveUtil.getTagInt(elem, "fireSlot")
        }

        if (currentAction == Action.REPAIRING_BREACH) {
            currentBreachSlot = SaveUtil.getTagInt(elem, "breachSlot")
        }

        // Load this *after* the enemy we're attacking is set, which
        // means during resolution time.
        refs.addOnResolveFunction {
            // The icon might be changed by our newly-loaded combat stuff.
            // Otherwise, this would be set when currentAction is.
            updateAnimation()

            icon.timer = SaveUtil.getAttrFloat(elem, "animTimer")
        }
    }

    enum class SlotType {
        CREW,
        INTRUDER;

        val other
            get() = when (this) {
                CREW -> INTRUDER
                INTRUDER -> CREW
            }
    }

    enum class Action {
        IDLE,
        MOVING,
        ATTACKING_DOOR,
        MANNING, // Working at a computer
        REPAIRING,
        REPAIRING_BREACH,
        FIRE_FIGHTING,
        FIGHTING,
        SABOTAGE,
        TELEPORTING,
        DYING,
        CLONING, // Playing the animation after being cloned
        ;

        val isAttackAction: Boolean
            get() = when (this) {
                ATTACKING_DOOR, SABOTAGE, FIGHTING -> true
                else -> false
            }

        /**
         * Is the crew member in an idle-ish state, where they'll remain in
         * that state for a prolonged period of time if no more damage occurs?
         */
        val isIdle: Boolean
            get() = when (this) {
                IDLE, MANNING -> true
                else -> false
            }
    }

    companion object {
        /**
         * When one of the player's crew is damaged across this many HP
         * (int-truncated, vanilla semantics), the lowCrewHealth warning
         * sound plays. Vanilla uses a flat 25 HP for every race - not a
         * fraction of max health.
         */
        const val LOW_HEALTH_WARNING_HP = 25

        // The frame of the repair animation that carries the rock thud
        // (AddSoundQueue(5, ...) in the RockAlien constructor, 0x48756b).
        private const val ROCK_THUD_FRAME = 5

        /** RockAnimation::UpdateFiring (0x47f100): the stomp frame of the 10-frame rock_fire strip. */
        private const val ROCK_STOMP_FRAME = 9

        // The per-frame time; a full loop of the healing animation takes
        // 11 * this many seconds.
        private const val HEALING_ANIM_FRAME_TIME = 0.15f

        /**
         * The green sparkles vanilla FTL draws above crewmembers being healed
         * in a medbay. Vanilla hardcodes this effect - it isn't listed in
         * animations.xml - so the animation is defined here instead.
         * The strip is 11 frames of 32x32.
         */
        private val HEALING_ANIM = AnimationSpec(
            Animations.SpriteSheetSpec("img/people/healing_strip11.png", 32, 32, 352, 32),
            "healing", 0, 0, 11, HEALING_ANIM_FRAME_TIME
        )

        /** Hardcoded like the healing sparkles: 5 frames of 14x14, one second for the strip. */
        private val STUN_ANIM = AnimationSpec(
            Animations.SpriteSheetSpec("img/people/stun_strip5.png", 14, 14, 70, 14),
            "stun", 0, 0, 5, 0.2f
        )
        private val STUN_ICON_COLOUR = xyz.znix.xftl.rendering.Colour(1f, 1f, 0f, 1f)

        const val TELEPORT_ANIMATION_TIME: Float = 0.5f
        const val TELEPORT_IMAGE_STRETCH: Float = 0.1f

        // CrewMember::GetMoveSpeed (0x47b450) scales the frame time factor
        // (16 per second) by 5 pixels, giving 80 pixels per second before
        // the race multiplier and the augments.
        const val BASE_MOVEMENT_SPEED: Float = 80f

        const val DOOR_OPEN_DISTANCE: Float = ROOM_SIZE / 2f - 2f
    }
}
