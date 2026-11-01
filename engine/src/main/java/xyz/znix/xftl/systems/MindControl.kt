package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.hs.crew.random32

class MindControl(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.MIND_CONTROL
    override val insertButtonSpace: Boolean get() = true

    /**
     * The time remaining on the mind control, or null if it's inactive.
     */
    var timeRemaining: Float? = null

    // The timer length last applied to [timeRemaining]; zero when idle.
    private var controlLength: Float = 0f

    val active: Boolean get() = timeRemaining != null

    var controlledCrew: LivingCrew? = null
        internal set

    override val holdsPermanentLock: Boolean get() = if (xyz.znix.xftl.hs.HS.active) hsPermanentLock else active
    override val hasWhiteLockingBox: Boolean get() = active

    /**
     * MindSystem::CanUse (0x62fc30) and InUse: usable while the enemy's request is not queued and nothing is
     * controlled, with power, or (the hacked case) while a hacking pulse is on - the pulse unlocks the system
     * and turns its targets around ([inverted]).
     */
    val ready: Boolean
        get() = queuedCandidates == null && !active &&
                (if (isHackActive) !isIonised else powerSelected > 0 && !isPowerLocked) &&
                (xyz.znix.xftl.hs.HS.active || canUse)

    /**
     * MindSystem +0x1c8 (canUse, which MindSystem::CanUse 0x62fc30 requires), set every frame by ShipManager::OnLoop
     * (0x4acefe): living, non-drone intruders aboard (ShipManager 0x49c180(true): crew whose current allegiance is
     * not this ship's), or a hostile target ship (ShipManager +0x488, Targetable::hostile +0x10) with living,
     * non-drone crew of its own aboard (0x49c180(false) on the target). Without it neither the player's button
     * (native capture vn-lanb-run: grey with no ship at the beacon, white once a hostile pirate is there) nor the ship
     * AI (CombatAI::UpdateMindControl 0x62ad50) can start mind control. Hyperspace keeps its own button-only rule
     * below (render round 3).
     */
    val canUse: Boolean
        get() {
            if (ship.crew.any { it.mode == AbstractCrew.SlotType.INTRUDER && countsForCanUse(it) }) return true
            val target = ship.sys.getEnemyOf(ship) ?: return false // null unless the encounter is hostile
            return target.crew.any { it.mode == AbstractCrew.SlotType.CREW && countsForCanUse(it) }
        }

    /** 0x49c180 skips dead crew (vtable +0x88) and drones (+0x30). */
    private fun countsForCanUse(crew: AbstractCrew): Boolean =
        crew !is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn && crew.currentAction != AbstractCrew.Action.DYING &&
            crew.health > 0f

    /**
     * MindSystem::InitiateMindControl (0x62fcc0) swaps the sides while the hacking pulse is on
     * (level >= 2 and the pulse flag): the system then takes the owner's own crew.
     */
    val inverted: Boolean get() = isHackActive

    /** MindSystem::QueueMindControl (0x62fa60): the crew picked by the UI or the AI, handled by the next OnLoop. */
    private var queuedCandidates: List<LivingCrew>? = null

    private val startSound by onInit { it.sounds.getSample("mindControl") }
    private val endSound by onInit { it.sounds.getSample("mindControlEnd") }

    private var button: MindControlButton? = null

    // The effective power indexes MindSystem's tables (0x885220-0x885260); under a hacking pulse the
    // system's health (+0x100) does instead.
    private val powerIndex: Int
        get() = (if (isHackActive) energyLevels - damagedEnergyLevels else powerSupplied).coerceIn(0, 3)

    /** MindSystem's control time by power (0x885260). */
    val duration: Float get() = if (xyz.znix.xftl.hs.HS.active) hsLevel.duration else DURATIONS[powerIndex]

    /** Extra health the controlled crewmember gets (0x885230). */
    val healthBoost: Float get() = if (xyz.znix.xftl.hs.HS.active) hsLevel.healthBoost else HEALTH_BOOSTS[powerIndex]

    /** The controlled crewmember's damage multiplier (0x885220). */
    val damageBoost: Float get() = if (xyz.znix.xftl.hs.HS.active) hsLevel.damageBoost else DAMAGE_BOOSTS[powerIndex]

    /**
     * Hyperspace: CustomMindSystem::GetLevel (hyperspace.xml mind levels): the health under a hacking pulse,
     * else the effective power, indexes levels[power - 1].
     */
    private val hsLevel: xyz.znix.xftl.hs.systems.HsSystems.MindLevel
        get() = xyz.znix.xftl.hs.systems.HsSystems.mindLevel(
            if (isHackActive) energyLevels - damagedEnergyLevels else powerSupplied
        )

    override fun update(dt: Float) {
        super.update(dt)

        if (xyz.znix.xftl.hs.HS.active) {
            hsLoop(dt)
            return
        }

        // MindSystem::OnLoop (0x630360) first starts a queued request.
        queuedCandidates?.let {
            queuedCandidates = null
            initiateMindControl(it)
        }

        val oldTime = timeRemaining
        if (oldTime == null || controlledCrew == null) {
            timeRemaining = null
            controlledCrew = null
            controlLength = 0f
            return
        }

        checkEnemyGone()
        if (timeRemaining == null) {
            // If checkEnemyGone reset the timer
            return
        }

        // MindSystem::OnLoop (0x630360): an unpowered system holds no crew
        // (0x885240), and releasing the last one starts the cooldown.
        if (powerIndex == 0) {
            switchToCooldown()
            return
        }

        // A new power level changes the timer's length but keeps the time
        // already elapsed.
        val length = duration
        var newTime = oldTime
        if (controlLength > 0f && controlLength != length)
            newTime += length - controlLength
        controlLength = length

        newTime -= dt
        if (newTime <= 0) {
            switchToCooldown()
            return
        }

        timeRemaining = newTime
    }

    /**
     * MindSystem's release (0x62f740) without its lock: the controlled
     * crewmember goes back to their side with the end sound.
     */
    /** Hyperspace RemoveSystem: MindSystem::ReleaseCrew. */
    internal fun hsRelease() = if (xyz.znix.xftl.hs.HS.active) hsReleaseCrew() else releaseCrew()

    private fun releaseCrew(playSound: Boolean = true) {
        // Only play the ending sound if a crew was mind-controlled.
        // If we counter enemy mind control, this should only play
        // for the enemy ship's system.
        if (controlledCrew != null && playSound) {
            endSound.play()
        }

        controlledCrew?.mindControlledBy = null
        timeRemaining = null
        controlledCrew = null
        controlLength = 0f
    }

    private fun switchToCooldown(playSound: Boolean = true) {
        releaseCrew(playSound)
        // Hyperspace: MindSystem::ReleaseCrew locks for the level's `lock` units.
        lockUnits(if (xyz.znix.xftl.hs.HS.active) hsLevel.lock else LOCK_UNITS)
    }

    // MindSystem::SetHackingLevel (0x62f870): a pulse releases the crew and
    // unlocks the system; its end releases again and locks for four units.
    override fun hackPulseChanged(active: Boolean) {
        if (xyz.znix.xftl.hs.HS.active) {
            if (active == hsPulseApplied) return
            hsPulseApplied = active
            // The same native rule (0x62f870) with Hyperspace's ReleaseCrew (which locks for the level's units).
            hsReleaseCrew()
            if (active) hsLock(0)
            return
        }
        if (active) {
            releaseCrew()
            lockUnits(0)
        } else {
            switchToCooldown()
        }
    }

    // MindSystem::Jump (0x6307b0): release, then LockSystem(0).
    override fun onJump() {
        super.onJump()
        if (xyz.znix.xftl.hs.HS.active) {
            hsReleaseCrew()
            hsLock(0)
            return
        }
        releaseCrew()
        lockUnits(0)
    }

    private fun checkEnemyGone() {
        val crew = controlledCrew ?: return

        // If the ship the crew was on has gone (destroyed, or it jumped
        // away), they drop out of the list silently and the system locks;
        // jumping ourselves unlocks it again (onJump).
        if (!ship.sys.isShipPresent(crew.room.ship)) {
            switchToCooldown(playSound = false)
            return
        }

        // If the crew disappears (most notably because they died), then
        // go to cooldown right away.
        if (!crew.room.ship.crew.contains(crew)) {
            switchToCooldown()
            return
        }

        // If the crew is no longer mind-controlled by us, something odd
        // happened - so go onto cooldown.
        if (crew.mindControlledBy != this) {
            switchToCooldown()
            return
        }
    }

    fun isControlling(crew: LivingCrew): Boolean {
        // Check if one of the ships has jumped away, or is no longer
        // hostile towards the other.
        // This doesn't apply to intruders, since they can always
        // be mind-controlled, regardless of what ship they belong to.
        if (ship.sys.getEnemyOf(ship) == null && crew.room.ship != ship)
            return false

        return active && crew == controlledCrew
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        button = MindControlButton(powerPos)
        return listOf(button!!)
    }

    /**
     * CombatControl::SelectRoom (0x57eba0) and SelectTarget (0x57fc50): the room under the cursor is only
     * selectable on a cloaked ship when the player has crew aboard (ShipManager +0x7ee, set in
     * ShipManager::OnLoop from the crew's vtable 0x9c: a player-side or mind-controlled crewmember); sensors and
     * vision play no part. The crew of the room are queued, the system picks among them on its next loop.
     */
    fun selectRoom(room: Room) {
        if (!ready)
            return

        if (xyz.znix.xftl.hs.HS.active) {
            val target = room.ship
            if (target != ship && target.isCloakActive && !target.hasPlayerPresence)
                return
            hsQueue(room.crew.filterIsInstance(LivingCrew::class.java), room.id, xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(target))
            return
        }

        val target = room.ship
        if (target != ship && target.isCloakActive && !target.hasPlayerPresence)
            return

        val inRoom = room.crew.filterIsInstance(LivingCrew::class.java)
        if (inRoom.isEmpty())
            return

        queuedCandidates = inRoom
    }

    /**
     * CombatAI::UpdateMindControl (0x62ad50) picks a crewmember and queues everyone non-resistant in that room;
     * [initiateMindControl] then draws among them like it does for the player.
     */
    fun selectCrew(crew: LivingCrew) {
        if (!ready)
            return

        if (xyz.znix.xftl.hs.HS.active) {
            hsQueue(crew.room.crew.filterIsInstance(LivingCrew::class.java).filter { !it.isMindControlResistant },
                crew.room.id, xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(crew.room.ship))
            return
        }

        val room = crew.room
        queuedCandidates = room.crew.filterIsInstance(LivingCrew::class.java)
            .filter { !it.isMindControlResistant }
    }

    /**
     * MindSystem::InitiateMindControl (0x62fcc0). A candidate is kept when it is not a drone and not dead, and
     * either it is on the opposite side and not yet mind-controlled, or on this system's side and
     * mind-controlled (countering the enemy's control). The hacking pulse swaps the two sides. Crew standing on
     * another ship need Zoltan Bypass past a super shield. If everything left resists nothing happens; otherwise
     * the resistant crew are dropped and one of the rest is drawn (the count table 0x885240 holds one for
     * power 1-3).
     */
    private fun initiateMindControl(candidates: List<LivingCrew>) {
        val swapped = inverted
        val bypass = ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)

        val valid = candidates.filter { crew ->
            // Dead crew, and crew playing their death animation (CrewAnimation state 3, crew +0x9c +0x38, which
            // the native tests next to IsDead), can't be picked.
            if (crew.health <= 0f || crew.currentAction == AbstractCrew.Action.DYING)
                return@filter false

            val sameSide = (crew.ownerShip?.isPlayerShip == true) == ship.isPlayerShip
            val takesOwn = if (swapped) !sameSide else sameSide
            if (takesOwn) crew.mindControlledBy != null else crew.mindControlledBy == null
        }.filter { crew ->
            val onShip = crew.room.ship
            onShip === ship || onShip.superShield <= 0 || bypass
        }

        val usable = valid.filter { !it.isMindControlResistant }
        if (usable.isEmpty())
            return

        // CrewMember::SetResisted (0x479fe0, +0x6f4): with someone to take, every resistant crew member is dropped
        // and flagged, and ShipManager's crew drawing (0x4a4059 / 0x4a422d, GetResisted clears the flag) pops up a
        // magenta 'RESIST' (numbers/text_resist.png) 20 pixels left of the crew. Nothing else reads the flag: it is
        // presentation only.
        for (resistant in valid) {
            if (!resistant.isMindControlResistant)
                continue
            val at = resistant.getPixelPositionCentre()
            resistant.room.ship.showDamageTextAt(ConstPoint(at.x - 20, at.y), "text_resist", Colour(255, 0, 255))
        }

        beginControl(usable.random(ftl.browser.BrowserRandom))
    }

    private fun beginControl(crew: LivingCrew) {
        // Countering mind control immediately puts both systems on cooldown
        val otherMC = crew.mindControlledBy
        if (otherMC != this && otherMC != null) {
            otherMC.switchToCooldown()
            switchToCooldown()
            return
        }

        crew.mindControlledBy = this
        controlledCrew = crew
        timeRemaining = duration
        controlLength = duration

        startSound.play()
    }

    /** MindSystem's release (0x62f740) for a ship that stops fighting: the controlled crew go back. */
    fun stopMindControl() {
        if (xyz.znix.xftl.hs.HS.active) {
            if (hsControlled.isNotEmpty()) hsReleaseCrew()
            return
        }
        if (active)
            releaseCrew()
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        if (key.id == VanillaHotkeys.SYS_ACTION_MIND) {
            // This is a bit hacky, but it properly shows the super-shield warning
            button?.click(Input.MOUSE_LEFT_BUTTON)
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        if (xyz.znix.xftl.hs.HS.active) {
            hsSave(elem, refs)
            return
        }
        // To avoid serialising a reference to a ship that's gone, check
        // if the enemy has jumped away.
        checkEnemyGone()

        SaveUtil.addTagFloat(elem, "timeRemaining", timeRemaining)

        val crew = controlledCrew
        if (crew != null) {
            val shipWithEnemy = crew.room.ship
            SaveUtil.addRef(elem, "shipWithEnemy", refs, shipWithEnemy)
            SaveUtil.addTagInt(elem, "crewId", shipWithEnemy.crew.indexOf(crew))
            SaveUtil.addTagFloat(elem, "browserHealthBoost", crew.mindControlHealthBoost)
        }
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        if (elem.getChild("hsMind") != null) {
            hsLoad(elem, refs)
            return
        }
        timeRemaining = SaveUtil.getTagFloatOrNull(elem, "timeRemaining")

        SaveUtil.getOptionalRef(elem, "shipWithEnemy", refs, Ship::class.java) { shipWithEnemy ->
            if (shipWithEnemy == null)
                return@getOptionalRef

            val crewId = SaveUtil.getTagInt(elem, "crewId")
            controlledCrew = shipWithEnemy.crew[crewId] as LivingCrew
            controlledCrew!!.mindControlledBy = this
            SaveUtil.getTagFloatOrNull(elem, "browserHealthBoost")?.let {
                controlledCrew!!.restoreMindControlHealthBoost(it)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hyperspace: FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp "Custom mind
    // control rewrites" (MindSystem::ReleaseCrew, OnLoop, InitiateMindControl over the CustomMindSystem levels) and
    // src/game/Ship/CustomCrew.cpp (CrewMember::SetMindControl). The members follow the native MindSystem (1.6.14
    // constructor 0x62f540: controlTimer +0x1c0/+0x1c4 = 14/14, bCanUse +0x1c8, iArmed +0x1cc, controlledCrew +0x1d0,
    // bSuperShields +0x1dc, bBlocked +0x1dd, iQueuedTarget/iQueuedShip +0x1e0/+0x1e4 = -1, queuedCrew +0x1e8). Only
    // used while HS is active; vanilla play keeps the single-crew code above.

    /** MindSystem::controlledCrew: everyone this system holds (CustomMindSystem levels allow more than one). */
    @JvmField val hsControlled = ArrayList<LivingCrew>()

    /** MindSystem::queuedCrew: the crew of the clicked room, drawn from by the next OnLoop. */
    @JvmField val hsQueued = ArrayList<LivingCrew>()

    /** MindSystem::controlTimer: first counts up to second while crew are held; equal when idle. */
    @JvmField var hsTimerFirst = 14f
    @JvmField var hsTimerSecond = 14f

    /** MindSystem::bCanUse, bSuperShields, bBlocked (ShipManager::OnLoop 0x4abd40 sets them), iQueuedTarget/Ship. */
    @JvmField var hsCanUse = false
    @JvmField var hsSuperShields = false
    @JvmField var hsBlocked = false
    @JvmField var hsQueuedTarget = -1
    @JvmField var hsQueuedShip = -1

    /** LockSystem(-1) from InitiateMindControl, until the next LockSystem. */
    private var hsPermanentLock = false

    private fun hsLock(units: Int) {
        hsPermanentLock = units < 0
        lockUnits(units)
    }

    private val hsShipId: Int get() = xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(ship)

    /**
     * ShipManager::OnLoop (0x4abd40) for this system: bSuperShields is the target's super shield; bBlocked is that
     * shield unless a player crew member is mind controlled aboard or intruders are aboard; bCanUse as [canUse].
     */
    private fun hsShipFlags() {
        val target = ship.sys.getEnemyOf(ship)
        hsSuperShields = (target?.superShield ?: 0) > 0
        val mcAboard = ship.crew.any {
            it is LivingCrew && xyz.znix.xftl.hs.crew.HsCrew.shipId(it) == 0 && it.isMindControlled
        }
        hsBlocked = !mcAboard && ship.intruders.isEmpty() && hsSuperShields
        hsCanUse = canUse
    }

    /** MindSystem::QueueMindControl (0x62fa60): the candidates, the room and the ship; iArmed is cleared. */
    fun hsQueue(crew: List<LivingCrew>, room: Int, shipId: Int) {
        hsQueued.clear()
        hsQueued.addAll(crew)
        hsQueuedTarget = room
        hsQueuedShip = shipId
        hsArmed = 0
    }

    /** MindSystem::iArmed: the player is picking a room (CombatControl's mind control arming), or a Lua write. */
    var hsArmed: Int
        get() = if (ship.isPlayerShip && ship.sys.shipUI.isSelectingMindControlTarget) 1 else hsArmedValue
        set(value) {
            hsArmedValue = value
            if (!ship.isPlayerShip) return
            if (value == 1) ship.sys.shipUI.mindControlSelected()
            else if (ship.sys.shipUI.isSelectingMindControlTarget) ship.sys.clickEvent = null
        }
    private var hsArmedValue = 0

    // The pulse edge [hackPulseChanged] last applied (a Lua SetHackingLevel applies it at once).
    private var hsPulseApplied = false

    /**
     * MindSystem::SetArmed (0x62f930): arming needs bCanUse, a system not destroyed, power (or a hacking pulse) and no
     * lock; arming clears the queued target, ship and crew.
     */
    fun hsSetArmed(value: Int) {
        val ok = value == 1 && hsCanUse && damagedEnergyLevels < energyLevels &&
            (powerSupplied > 0 || isHackActive) && !(ionTimer > 0f || hasPermanentLock) && powerSupplied > 0
        hsArmed = if (ok) 1 else 0
        if (ok) {
            hsQueuedTarget = -1
            hsQueuedShip = -1
            hsQueued.clear()
        }
    }

    /** MindSystem::SetHackingLevel (0x62f870), applied at once. */
    fun hsSetHackingLevel(level: Int) {
        val before = isHackActive
        hsHackLevel = level.coerceIn(0, 2)
        if (before != isHackActive) hackPulseChanged(isHackActive)
    }

    private fun hsLoop(dt: Float) {
        if (hsRebuild) hsRebuildControlled()
        hsShipFlags()
        if (hsQueued.isNotEmpty()) hsInitiate()

        val oldFirst = hsTimerFirst
        if (hsTimerFirst < hsTimerSecond) hsTimerFirst += dt // GetSpeedFactor() * 0.0625
        if (oldFirst != hsTimerSecond && hsTimerSecond <= hsTimerFirst) hsReleaseCrew()

        val level = hsLevel
        while (level.count < hsControlled.size) {
            setMindControl(hsControlled.removeAt(hsControlled.size - 1), false)
            endSound.play()
        }
        if (powerSupplied == 0 && hsControlled.isNotEmpty()) {
            while (hsControlled.isNotEmpty()) setMindControl(hsControlled.removeAt(hsControlled.size - 1), false)
            endSound.play()
        }
        hsControlled.removeAll {
            !inGame(it) || it.health <= 0f || it.currentAction == AbstractCrew.Action.DYING || !it.hsMindControlled
        }

        // CrewMember::SetHealthBoost takes an int.
        for (crew in hsControlled) crew.setMindControlBoost(level.healthBoost.toInt().toFloat(), level.damageBoost)
        if (hsControlled.isEmpty() && hsTimerFirst != hsTimerSecond) hsReleaseCrew()
        if (level.duration != hsTimerSecond) {
            if (hsTimerFirst == hsTimerSecond) hsTimerFirst = level.duration
            hsTimerSecond = level.duration
        }
        hsShowState()
    }

    /** The vanilla UI state (the button's timer, one controlled crew member) from the HS members. */
    private fun hsShowState() {
        timeRemaining = if (hsTimerFirst < hsTimerSecond) hsTimerSecond - hsTimerFirst else null
        controlLength = hsTimerSecond
        controlledCrew = hsControlled.firstOrNull()
    }

    /** !CrewMember::OutOfGame: still aboard a ship of this fight. */
    private fun inGame(crew: LivingCrew): Boolean =
        ship.sys.isShipPresent(crew.room.ship) && crew.room.ship.crew.contains(crew)

    /** MindSystem::ReleaseCrew (Hyperspace): every crew member goes back with the end sound, then the level's lock. */
    private fun hsReleaseCrew() {
        for (crew in hsControlled) {
            setMindControl(crew, false)
            endSound.play()
        }
        hsControlled.clear()
        hsLock(hsLevel.lock)
        hsTimerFirst = hsTimerSecond
        hsShowState()
    }

    /** MindSystem::InitiateMindControl (Hyperspace): up to the level's count, each drawn with random32. */
    private fun hsInitiate() {
        hsQueuedTarget = -1
        hsQueuedShip = -1
        val myId = hsShipId
        for (crew in hsQueued) {
            if (crew.isMindControlResistant && xyz.znix.xftl.hs.crew.HsCrew.shipId(crew) != myId) showResist(crew)
        }
        val allyId = if (isHackActive) 1 - myId else myId
        val bypass = xyz.znix.xftl.hs.events.HsReq.hasEquipment(ship.sys, ship, AugmentBlueprint.ZOLTAN_BYPASS) > 0
        hsQueued.removeAll { crew ->
            val id = xyz.znix.xftl.hs.crew.HsCrew.shipId(crew)
            !inGame(crew) || crew.health <= 0f || crew.currentAction == AbstractCrew.Action.DYING ||
                ((id == allyId) xor crew.isMindControlled) || crew.isMindControlResistant ||
                crew is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn ||
                (id == 1 && myId == 1 && crew.isMindControlled) ||
                (hsSuperShields && xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(crew.room.ship) != myId && !bypass)
        }
        val level = hsLevel
        var count = 0
        while (count < level.count && hsQueued.isNotEmpty() && powerSupplied > 0) {
            val crew = hsQueued.removeAt(xyz.znix.xftl.hs.crew.HsCrew.random32() % hsQueued.size)
            if (crew.isMindControlled) {
                setMindControl(crew, false)
                endSound.play()
            } else {
                setMindControl(crew, true)
                startSound.play()
                hsControlled += crew
            }
            count++
        }
        if (hsControlled.isNotEmpty()) {
            hsTimerFirst = 0f
            hsLock(-1)
        } else if (count > 0) {
            hsTimerFirst = hsTimerSecond
            hsLock(level.lock)
        } else {
            hsTimerFirst = hsTimerSecond
        }
        hsQueued.clear()
        hsShowState()
    }

    /** CrewMember::SetResisted(true): the magenta RESIST pop-up 20 pixels left of the crew member. */
    private fun showResist(crew: LivingCrew) {
        val at = crew.getPixelPositionCentre()
        crew.room.ship.showDamageTextAt(ConstPoint(at.x - 20, at.y), "text_resist", Colour(255, 0, 255))
    }

    /** MindSystem::Save (0x62f640): the control timer only; the crew are found again on load. */
    private fun hsSave(elem: Element, @Suppress("UNUSED_PARAMETER") refs: ObjectRefs) {
        val e = Element("hsMind")
        e.setAttribute("first", hsTimerFirst.toString())
        e.setAttribute("second", hsTimerSecond.toString())
        e.setAttribute("permanentLock", hsPermanentLock.toString())
        elem.addContent(e)
    }

    /**
     * MindSystem::Load (0x630220): the timer, then controlledCrew is every mind-controlled crew member of both ships
     * on the other side from this system (any side under a hacking pulse). The crew load after the systems, so the
     * list is rebuilt before the first loop.
     */
    private fun hsLoad(elem: Element, @Suppress("UNUSED_PARAMETER") refs: RefLoader) {
        val e = elem.getChild("hsMind")
        hsTimerFirst = e.getAttributeValue("first").toFloat()
        hsTimerSecond = e.getAttributeValue("second").toFloat()
        hsPermanentLock = e.getAttributeValue("permanentLock") == "true"
        hsControlled.clear()
        hsRebuild = true
    }

    private var hsRebuild = false

    private fun hsRebuildControlled() {
        hsRebuild = false
        hsControlled.clear()
        val myId = hsShipId
        for (s in listOfNotNull(ship.sys.player, ship.sys.enemy)) for (crew in s.crew) {
            if (crew !is LivingCrew || !crew.hsMindControlled) continue
            if (isHackActive || xyz.znix.xftl.hs.crew.HsCrew.shipId(crew) != myId) hsControlled += crew
        }
        hsShowState()
    }

    companion object {
        val INFO: SystemInfo = MindControlInfo

        /**
         * CrewMember::SetMindControl as Hyperspace hooks it (CustomCrew.cpp): a dead, dying or out-of-game crew member
         * keeps its flag; then the native setter (1.6.14 0x480c80) flips bMindControlled, which turns the crew member's
         * side around ([LivingCrew.mode]). An enemy crew member whose control changes while the player has MIND_ORDER
         * refreshes the player's crew boxes (MoveCrewMemberToEnd + UpdateCrewBoxes). Hyperspace only.
         */
        @JvmStatic
        fun setMindControl(crew: LivingCrew, controlled: Boolean) {
            val dying = crew.health <= 0f || crew.currentAction == AbstractCrew.Action.DYING ||
                !crew.room.ship.crew.contains(crew)
            val value = if (dying) crew.isMindControlled else controlled
            if (!value) crew.mindControlledBy = null
            crew.hsMindControlled = value
            val game = crew.room.ship.sys
            if (xyz.znix.xftl.hs.crew.HsCrew.shipId(crew) == 1 && game.player?.hasAugment("MIND_ORDER") == true)
                game.updatePlayerCrew()
        }

        private val DURATIONS = floatArrayOf(14f, 14f, 20f, 28f)
        private val HEALTH_BOOSTS = floatArrayOf(0f, 0f, 15f, 30f)
        private val DAMAGE_BOOSTS = floatArrayOf(1f, 1f, 1.25f, 2f)

        /** MindSystem's lock after a release (0x885250). */
        const val LOCK_UNITS = 4
    }

    // Note the mind control button always uses the two-power height
    private inner class MindControlButton(powerPos: IPoint) : SystemPowerButton(ship.sys, 2, powerPos) {
        override val timeRemaining: Float? get() = this@MindControl.timeRemaining
        override val duration: Float get() = this@MindControl.duration
        // ShipManager::OnLoop (0x4acf0e) also sets MindSystem's canUse: intruders aboard, or a target ship with crew.
        // Without them the button stays off (oracle r2-ships: the Rhyme's mind button is grey at the start beacon).
        // Hyperspace keeps this rule; the vanilla rule is part of [ready] ([canUse]).
        override val isOff: Boolean get() = !ready || (xyz.znix.xftl.hs.HS.active && !hsCanUse())

        private fun hsCanUse(): Boolean {
            if (ship.intruders.isNotEmpty()) return true
            val target = game.getEnemyOf(ship) ?: return false
            return target.crew.isNotEmpty()
        }

        private val superShieldWarning = WarningFlasher(
            game, powerPos + ConstPoint(35, -62),
            "warning_super_shield_mind",
            false, colour = GlowColour.WHITE
        )

        override val forceHighlight: Boolean
            get() = game.shipUI.isSelectingMindControlTarget

        public override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            // If the enemy ship has a zoltan shield but there are intruders
            // on the player's ship, they can activate mind control and place
            // it on the enemy ship - it just doesn't do anything once un-paused.
            val hasIntruders = ship.intruders.any { (it as? LivingCrew)?.isMindControlResistant == false }
            val enemyShip = game.getEnemyOf(ship)
            if (!hasIntruders && enemyShip != null && enemyShip.superShield > 0 &&
                !ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)
            ) {
                superShieldWarning.startFor(3.5f)
                return
            }

            game.shipUI.mindControlSelected()
        }

        override fun draw(g: Graphics) {
            super.draw(g)
            superShieldWarning.draw(g)
        }
    }
}

private object MindControlInfo : SystemInfo("mind") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = MindControl(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["mind_1"]
            1 -> translator["mind_2"]
            2 -> translator["mind_3"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}
