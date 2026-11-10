package xyz.znix.xftl.layout

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Constants.ROOM_SIZE
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.abs
import kotlin.math.max

private const val FORCED_OPEN_TIME = 7f

data class Door(val position: ConstPoint, val left: Room?, val right: Room?, val isVertical: Boolean) {
    init {
        check(left != null || right != null) { "Cannot have a door detached from any room" }
    }

    val ship: Ship = left?.ship ?: right!!.ship

    // Offset of this room from the ship's 0,0 screen position
    val offsetX get() = ROOM_SIZE * (position.x + ship.offset.x)
    val offsetY get() = ROOM_SIZE * (position.y + ship.offset.y)

    val pixelCentre: ConstPoint by lazy {
        if (isVertical) {
            ConstPoint(offsetX, offsetY + ROOM_SIZE / 2)
        } else {
            ConstPoint(offsetX + ROOM_SIZE / 2, offsetY)
        }
    }

    val leftPos: RoomPoint? = if (left == null) null else RoomPoint(left, position - left.position + offsetFor(left))
    val rightPos: RoomPoint? =
        if (right == null) null else RoomPoint(right, position - right.position + offsetFor(right))

    val isAirlock: Boolean get() = left == null || right == null

    var lockdownTimer: Float = 0f
        private set

    /**
     * Door::SetLockdown(true) (0x4708a0, from the lockdown creation at 0x494f50) gives the door 50 hit points
     * (0x32, both its current and its maximum), which Door::ApplyDamage (0x46e0f0) wears down one per hit.
     */
    internal var lockdownHealth: Int = LOCKDOWN_HEALTH
        private set

    /** The door shard's health written by a script (LockdownShard_Extend::health): at zero the lockdown of the door ends. */
    internal fun setLockdownHealth(v: Int) {
        lockdownHealth = v
        if (v < 1 && lockdownTimer > 0f) endLockdown()
    }

    private fun endLockdown() {
        lockdownTimer = 0f
        hsShardLeft = 0f; hsShardRight = 0f
        lockdownHealth = LOCKDOWN_HEALTH
        open = true
        damage = 0
    }

    fun lockdown() { lockdownTimer = 12f; damage = 0; open = false; lockdownHealth = LOCKDOWN_HEALTH }

    /**
     * Hyperspace's lockdown rework (CustomLockdowns.cpp): the door stays locked while a shard of either locking room
     * lives, and each shard counts down at its room's temporal dilation (LockdownShard::Update with canDilate, the
     * default lockdown). Seconds left of the shards from [left] and [right]; zero when none.
     */
    private var hsShardLeft = 0f
    private var hsShardRight = 0f
    // LockdownShard_Extend::canDilate of the shard of each side: only a shard that can dilate counts down at its room's rate
    private var hsDilateLeft = true
    private var hsDilateRight = true

    /**
     * Room.lockdown from [from]: the vanilla timer, and under Hyperspace that side's shard. A Hyperspace custom
     * lockdown (CustomLockdownDefinition, CustomLockdowns.cpp) gives the shards its [duration] and the door shard its
     * [health]; the defaults are the vanilla 12 s and 50.
     */
    fun lockdown(from: Room?, duration: Float = 12f, health: Int = LOCKDOWN_HEALTH, canDilate: Boolean = true) {
        lockdown()
        if (xyz.znix.xftl.hs.HS.active) {
            lockdownTimer = duration
            lockdownHealth = health
            if (from === left) { hsShardLeft = duration; hsDilateLeft = canDilate }
            if (from === right) { hsShardRight = duration; hsDilateRight = canDilate }
        }
    }

    // Door::SetBlastDoor (0x470470) / Door::OnLoop (0x471380): a hacked door has the level-3
    // health (0x87895c) whatever the hack level is, otherwise the table for the doors
    // system's effective power, with levels 0 and 1 at 4.
    val maxHealth: Int
        get() {
            val healths = BASE_HEALTHS.getValue(ship.sys.difficulty)

            if (isHacked) {
                return healths[1] // Level 3 equivalent
            }

            val baseLevel = ship.doorsSystem?.effectivePower ?: 0
            if (baseLevel < 2) {
                // Low-level doors have four health for whatever reason,
                // but don't actually stop boarders.
                return 4
            }

            // SetBlastDoor has no table past level 4: a manned level 4 doors system
            // (effective power 5, Advanced Edition) keeps the health it already had.
            return healths[(baseLevel - 2).coerceAtMost(healths.size - 1)]
        }

    /**
     * True if this door is considered to be open for the purposes
     * of letting air and/or intruders through.
     *
     * The door will animate open when a friendly crewmember is walking
     * through, but that won't let intruders or air through.
     */
    var open: Boolean = false

    /**
     * The amount of damage this door has taken, so its health is
     * [maxHealth] minus this. Once the health reaches zero the door is
     * forced open and its health is restored (Door::ApplyDamage, 0x46e0f0).
     *
     * When [maxHealth] changes (doors upgraded/damaged or hacked) the
     * health keeps its fraction of the maximum, see [rescaleHealth].
     */
    var damage: Int = 0
        private set

    // The maximum health the damage was last scaled against, zero until
    // the first update (Door+0x1d0 in the original).
    private var scaledMax: Int = 0

    /** Door::forcedOpen: the door is drawn open while it runs. */
    @JvmField val forcedOpen = xyz.znix.xftl.systems.Shields.ShieldTracker(FORCED_OPEN_TIME)
    /** Door::gotHit: started by each hit. */
    @JvmField val gotHit = xyz.znix.xftl.systems.Shields.ShieldTracker(0.5f)
    @JvmField var hsGotHitIn: (() -> Unit)? = null
    @JvmField var hsGotHitOut: (() -> Unit)? = null
    /** Hyperspace Lua: the tracker a script reads and writes, into the engine's and back. */
    @JvmField var hsForcedIn: (() -> Unit)? = null
    @JvmField var hsForcedOut: (() -> Unit)? = null
    /** Hyperspace Lua: Door::doorLevel written by a script: the picture's level. */
    @JvmField var hsLevel: Int? = null
    /** Hyperspace Lua: Door::doorAnim's position moved by a script. */
    @JvmField var hsAnimShiftX = 0
    @JvmField var hsAnimShiftY = 0

    // True if the player is hovering over this door
    private var hovered: Boolean = false

    /** The mouse is over this door (CrewControl::selectedDoor, Hyperspace Lua). */
    val isHovered: Boolean get() = hovered

    // Set during the update cycle by crew walking through this door.
    // This is how we know if a door should appear to open for
    // a crewmember or not.
    private var crewOpenDemand: Boolean = false

    // How open this door is. This is updated to produce the animation.
    private var stateAnimation: Float = 0f
    val animationProgress: Float get() = stateAnimation

    /**
     * True if this door is controlled by a hacking system, either by hacking
     * one of the adjacent rooms or by actively hacking the door system.
     */
    var isHacked = false
        private set

    /** Hyperspace HACK_DOORS balance of the crew in the rooms beside this door this frame (intruders +1, own -1). */
    @JvmField var hsHackDoors = 0

    /**
     * The level of this door.
     */
    val level: Int
        get() {
            when {
                isHacked -> return 5
                else -> return ship.doorsSystem?.effectivePower ?: 0
            }
        }

    /**
     * The other room this door connects to
     */
    fun other(room: Room): Room? {
        return when (room) {
            left -> right
            right -> left
            else -> throw IllegalArgumentException("Supplied room '$room' is not part of the door")
        }
    }

    /**
     * The position (relative to the given room) for one side of the door
     */
    fun roomPos(room: Room): RoomPoint {
        return when (room) {
            left -> leftPos!!
            right -> rightPos!!
            else -> throw IllegalArgumentException("Supplied room '$room' is not part of the door")
        }
    }

    /**
     * The position of the other side of the door, relative to the other room
     *
     * This is essentially roomPos(other(room)) but with null handling
     */
    fun otherPos(room: Room): IPoint? {
        return roomPos(other(room) ?: return null)
    }

    private fun offsetFor(room: Room): ConstPoint {
        return if (isVertical)
            if (room.x == position.x) ConstPoint.ZERO else ConstPoint(-1, 0)
        else
            if (room.y == position.y) ConstPoint.ZERO else ConstPoint(0, -1)
    }

    /**
     * Find the direction you would have to walk to go through this door, if standing inside [room].
     */
    fun dirFor(room: Room): Direction {
        return if (isVertical)
            if (room.x == position.x) Direction.LEFT else Direction.RIGHT
        else
            if (room.y == position.y) Direction.UP else Direction.DOWN
    }

    /**
     * Called by crewmembers walking through this door to request that it opens visually.
     */
    fun crewRequestOpen(crew: AbstractCrew) {
        // We're obviously not going to open the door for boarders
        if (isLockedFor(crew))
            return

        crewOpenDemand = true
    }

    fun update(dt: Float) {
        hsForcedIn?.invoke()
        forcedOpen.update(dt)
        hsForcedOut?.invoke()
        hsGotHitIn?.invoke()
        gotHit.update(dt)
        hsGotHitOut?.invoke()
        if (xyz.znix.xftl.hs.HS.active && (hsShardLeft > 0f || hsShardRight > 0f) && lockdownTimer > 0f) {
            hsShardLeft = max(0f, hsShardLeft - dt * (if (hsDilateLeft) xyz.znix.xftl.hs.systems.HsTemporal.factor(left) else 1f))
            hsShardRight = max(0f, hsShardRight - dt * (if (hsDilateRight) xyz.znix.xftl.hs.systems.HsTemporal.factor(right) else 1f))
            lockdownTimer = max(hsShardLeft, hsShardRight)
        } else {
            hsShardLeft = 0f; hsShardRight = 0f
            lockdownTimer = max(0f, lockdownTimer - dt)
        }
        // If a crewmember is walking through this door, they'll
        // set crewOpenDemand every update.
        crewOpenDemand = false

        // OxygenSystem::UpdateAirlock (0x62cb00, from the per-room loop at 0x4a70xx): with an
        // open airlock door in a room, a room above 10% oxygen is emptied at once (with the
        // whoosh sound); at or below that the door only leaks at 16%/s like any other drain,
        // which OxygenTransfer applies. Air transfer between non-airlock doors is handled
        // by OxygenTransfer.
        // (Hyperspace: HsOxygen runs UpdateAirlock with the leak modifier.)
        if (open && (left == null || right == null) && !xyz.znix.xftl.hs.HS.active) {
            for (airlockRoom in listOfNotNull(left, right)) {
                if (airlockRoom.oxygen > 0.1f) {
                    ship.airLossSound.play()
                    airlockRoom.oxygen = 0f
                }
            }
        }

        // The door counts as hacked if the system on either side of it is hacked
        // and the hacking system is at least powered on, or (for any door) if
        // the doors system is hacked with a hacking pulse.
        // ShipManager::OnLoop (0x4ac...) sets the hack level of these doors every frame.
        isHacked = ship.doorsSystem?.isHackActive == true ||
                left?.system?.hackedBy?.isPoweredUp == true ||
                right?.system?.hackedBy?.isPoweredUp == true
        // Hyperspace Ship::OnLoop hook (CustomCrew.cpp, HACK_DOORS): set by HsHackDoors before this update.
        if (hsHackDoors > 0) isHacked = true else if (hsHackDoors < 0) isHacked = false

        // Door::OnLoop (0x471380): hacked doors shut themselves again every frame.
        if (isHacked) {
            open = false
        }

        rescaleHealth()
    }

    /**
     * Door::SetBlastDoor (0x470470) and Door::OnLoop (0x471380): when the level of
     * the door changes its health keeps its fraction of the maximum (truncated),
     * and a full door stays full.
     */
    private fun rescaleHealth() {
        val newMax = maxHealth
        if (scaledMax == 0) {
            scaledMax = newMax
            return
        }
        if (newMax == scaledMax) {
            return
        }

        val fraction = (scaledMax - damage).toFloat() / scaledMax.toFloat()
        var newHealth = newMax.toFloat()
        if (fraction < 1f) {
            newHealth *= fraction
        }
        damage = (newMax - newHealth.toInt()).coerceAtLeast(0)
        scaledMax = newMax
    }

    fun clearMouseHover() {
        hovered = false
    }

    fun updateMouseHover(x: Int, y: Int) {
        // Hacked doors can't be controlled by the player, and
        // neither can any door at all while the doors system is inoperable
        // (broken/ionised/hacked/powered down/absent) - vanilla then refuses
        // to open or close anything, so don't even highlight the doors.
        if (isHacked || (lockdownTimer > 0f && !isAirlock) || !ship.areDoorsOperable) {
            hovered = false
            return
        }

        var centreX = offsetX
        var centreY = offsetY

        if (isVertical) {
            centreY += ROOM_SIZE / 2
        } else {
            centreX += ROOM_SIZE / 2
        }

        hovered = false

        val hoverRange = 10

        if (x !in centreX - hoverRange..centreX + hoverRange)
            return
        if (y !in centreY - hoverRange..centreY + hoverRange)
            return

        hovered = true
    }

    fun click(x: Int, y: Int): Boolean {
        // Update our hovered status, since we use that
        // to determine if we're being clicked.
        updateMouseHover(x, y)

        // Belt and braces: an inoperable doors system means no door on the
        // ship may be toggled (see [Ship.areDoorsOperable]).
        if (!ship.areDoorsOperable) {
            return false
        }

        if (!hovered) {
            return false
        }

        open = !open

        return true
    }

    /**
     * Move the open/close animation on by [dt] seconds. The player's doors sound as their animation starts, like the
     * native Door's doorAnim (soundForward "doorOpen" and soundReverse "doorClose", set only on ship 0):
     * Animation::Start plays soundForward when it starts from fully closed and the reverse start plays soundReverse
     * from fully open, so a door turned back halfway makes no sound. A door clicked while the game is paused sounds at
     * once: CrewControl::OnTouch calls Door::Open / Door::Close, which start the animation, with no pause check.
     */
    internal fun stepAnimation(dt: Float) {
        val animationDir = when {
            open || crewOpenDemand -> 1
            else -> -1
        }
        val prevState = stateAnimation
        stateAnimation = (stateAnimation + animationDir * dt / ANIMATION_TIME).coerceIn(0f..1f)

        if (ship.isPlayerShip && prevState != stateAnimation) {
            if (prevState == 0f) {
                ship.sys.sounds.getSampleOrWarn("doorOpen")?.play()
            } else if (prevState == 1f) {
                ship.sys.sounds.getSampleOrWarn("doorClose")?.play()
            }
        }
    }

    fun render(g: Graphics, alpha: Float) {
        stepAnimation(ship.sys.renderingDeltaTime)

        val doorSheet = ship.sys.getImg("img/effects/door_sheet.png")
        val highlight = ship.sys.getImg("img/effects/door_highlight.png")

        // Broken and level 1 doors use the same sprite, but broken doors
        // have a colour filter applied to them.
        val sheetY = ROOM_SIZE * (hsLevel ?: level - 1 + 1).minus(1).coerceAtLeast(0)

        // This is used to animate the door opening and closing, selecting the
        // correct frame for its motion.
        // forcedOpen draws the door's third frame (oracle hw-o2v); gotHit changes no picture.
        hsForcedIn?.invoke()
        hsGotHitIn?.invoke()
        val sheetX = if (forcedOpen.running) ROOM_SIZE * 3 else ROOM_SIZE * (stateAnimation * 4).toInt()

        val filter = when { lockdownTimer > 0f -> Colour(0.35f, 0.75f, 1f); level == 0 -> Constants.DOOR_BROKEN_FILTER; else -> Colour.white }

        // Note that isVertical refers to whether the door is vertical
        // or not, NOT whether the two rooms it joins are placed one
        // above the other.
        g.pushTransform()
        g.translate(hsAnimShiftX.f, hsAnimShiftY.f)
        if (isVertical) {
            g.translate(offsetX.f - ROOM_SIZE / 2, offsetY.f)
        } else {
            g.translate(offsetX.f + ROOM_SIZE, offsetY.f - ROOM_SIZE / 2)
            g.rotate(0f, 0f, 90f)
        }

        // Draw the mouse hover highlight
        if (hovered) {
            val highlightFilter = Colour(1f, 1f, 1f, 0.75f)
            highlight.draw(0f, 0f, highlightFilter)
        }

        doorSheet.draw(
            0f, 0f, ROOM_SIZE.f, ROOM_SIZE.f,

            sheetX.f, sheetY.f,
            sheetX.f + ROOM_SIZE, sheetY.f + ROOM_SIZE,
            alpha, filter
        )

        g.popTransform()
    }

    fun attackDoor() {
        // A locked-down door has 50 hit points (see lockdownHealth). At zero ApplyDamage opens the door and
        // restores its normal health, which ends the lockdown for this door (the room stays locked for the
        // shards' 12 seconds, Ship::RoomLocked 0x48c510).
        if (lockdownTimer > 0f) {
            lockdownHealth--
            if (lockdownHealth < 1) endLockdown()
            return
        }
        // Door::ApplyDamage: a door still forced open takes nothing (oracle hw-dh).
        hsForcedIn?.invoke()
        if (forcedOpen.running) return
        gotHit.start()
        hsGotHitOut?.invoke()
        damage++

        // Door::ApplyDamage (0x46e0f0): one point of health per hit, and at zero
        // the door is forced open and its health restored. There is no timer; the
        // door can be closed again straight away (hacked doors shut on the next loop).
        if (damage >= maxHealth) {
            open = true
            damage = 0
            forcedOpen.start()
            hsForcedOut?.invoke()
        }
    }

    fun resetHealth() {
        damage = 0
    }

    /** Hyperspace Lua `Door.health = v`: a plain field write (no clamp to the maximum). */
    fun luaSetHealth(health: Int) {
        damage = maxHealth - health
    }

    /**
     * Door::ApplyDamage (1.6.9 0x463f30, the float amount is ignored): an open door takes nothing, a closed one
     * one hit ([attackDoor]); always returns false.
     */
    fun luaApplyDamage(): Boolean {
        if (!open) attackDoor()
        return false
    }

    fun saveToXML(): Element? {
        // Currently we have nothing to serialise. Returning null means there
        // isn't a blank element in the savefile.
        // Note that whether the door is open or closed is serialised separately.

        if (damage == 0 && lockdownTimer == 0f) {
            return null
        }

        val elem = Element("door")

        SaveUtil.addAttrFloat(elem, "browserLockdown", lockdownTimer)
        if (lockdownTimer > 0f)
            SaveUtil.addAttrInt(elem, "browserLockdownHealth", lockdownHealth)
        if (hsShardLeft > 0f) SaveUtil.addAttrFloat(elem, "hsShardLeft", hsShardLeft)
        if (hsShardRight > 0f) SaveUtil.addAttrFloat(elem, "hsShardRight", hsShardRight)
        SaveUtil.addAttrInt(elem, "damage", damage)

        return elem
    }

    fun loadFromXML(elem: Element) {
        lockdownTimer = elem.getAttributeValue("browserLockdown")?.toFloat() ?: 0f
        lockdownHealth = elem.getAttributeValue("browserLockdownHealth")?.toInt() ?: LOCKDOWN_HEALTH
        hsShardLeft = elem.getAttributeValue("hsShardLeft")?.toFloat() ?: 0f
        hsShardRight = elem.getAttributeValue("hsShardRight")?.toFloat() ?: 0f
        // (Older saves also carry browserLockdownDamage, from a removed rule; it is ignored.)
        damage = SaveUtil.getAttrInt(elem, "damage")
        scaledMax = 0
    }

    // Called if we returned null from saveToXML.
    fun loadWithoutXML() {
        lockdownTimer = 0f
        damage = 0
        scaledMax = 0
    }

    fun loadSavedOpen(open: Boolean) {
        // Called when this door's open/closed state is loaded.
        this.open = open

        // Skip the animation when the door is first opened.
        // Note the crew gets a few updates before this is called, so crew
        // requests will be handled properly.
        val visualOpen = open || crewOpenDemand
        stateAnimation = if (visualOpen) 1f else 0f
    }

    /**
     * Check if a player is walking through this door, based on their
     * top-left position.
     */
    fun checkPlayerPos(room: Room, pos: IPoint): Boolean {
        val x = pos.x
        val y = pos.y

        // We define the doorway as being the 35-pixel-wide box centred
        // on the door. This means we can walk with the edge of our sprite's
        // frame overhanging the room, but it shouldn't be an issue since
        // the sprites certainly aren't drawn all the way up to their edges.

        // Find the position of the cell we neighbour in the player's room.
        val posInRoom = roomPos(room)
        val ourX = posInRoom.offsetX
        val ourY = posInRoom.offsetY

        // The player must be reasonably well aligned, noting that the players
        // are displaced upto eight pixels when shooting at the door.
        if (isVertical && abs(y - ourY) > 8) {
            return false
        }
        if (!isVertical && abs(x - ourX) > 8) {
            return false
        }

        // Check if the player isn't within the rooms bounds, which implies
        // they're walking through the doorway.
        val pxInRoomX = x - room.offsetX
        val pxInRoomY = y - room.offsetY

        return when (isVertical) {
            true -> pxInRoomX !in 0..room.pixelWidth - ROOM_SIZE
            false -> pxInRoomY !in 0..room.pixelHeight - ROOM_SIZE
        }
    }

    /**
     * Check if a given position matches either of the cells we connect.
     */
    fun hasRoomPos(point: RoomPoint): Boolean {
        return point == leftPos || point == rightPos
    }

    /**
     * Check if the given crewmember is prevented from walking through the
     * door because it's locked closed.
     */
    fun isLockedFor(crew: AbstractCrew): Boolean {
        if (lockdownTimer > 0f && !isAirlock) return true
        if (open) {
            return false
        }

        if (isHacked) {
            return crew.mode == AbstractCrew.SlotType.CREW
        }

        // If there isn't a doors system, the door isn't locked.
        val baseLevel = ship.doorsSystem?.effectivePower ?: 0
        if (baseLevel < 2) {
            return false
        }

        return crew.mode == AbstractCrew.SlotType.INTRUDER
    }

    companion object {
        private const val LOCKDOWN_HEALTH: Int = 50
        private const val ANIMATION_TIME: Float = 0.2f

        // Door healths depend on the difficulty.
        // These values are the number of attacks required to break
        // a level 2/3/4 door.
        private val BASE_HEALTHS: Map<Difficulty, List<Int>> = mapOf(
            Pair(Difficulty.EASY, listOf(12, 16, 20)),
            Pair(Difficulty.NORMAL, listOf(8, 12, 18)),
            Pair(Difficulty.HARD, listOf(6, 10, 15))
        )
    }
}
