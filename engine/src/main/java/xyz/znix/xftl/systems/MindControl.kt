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

    override val holdsPermanentLock: Boolean get() = active
    override val hasWhiteLockingBox: Boolean get() = active

    /**
     * MindSystem::CanUse (0x62fc30) and InUse: usable while the enemy's request is not queued and nothing is
     * controlled, with power, or (the hacked case) while a hacking pulse is on - the pulse unlocks the system
     * and turns its targets around ([inverted]).
     */
    val ready: Boolean
        get() = queuedCandidates == null && !active &&
                if (isHackActive) !isIonised else powerSelected > 0 && !isPowerLocked

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
    val duration: Float get() = DURATIONS[powerIndex]

    /** Extra health the controlled crewmember gets (0x885230). */
    val healthBoost: Float get() = HEALTH_BOOSTS[powerIndex]

    /** The controlled crewmember's damage multiplier (0x885220). */
    val damageBoost: Float get() = DAMAGE_BOOSTS[powerIndex]

    override fun update(dt: Float) {
        super.update(dt)

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
        lockUnits(LOCK_UNITS)
    }

    // MindSystem::SetHackingLevel (0x62f870): a pulse releases the crew and
    // unlocks the system; its end releases again and locks for four units.
    override fun hackPulseChanged(active: Boolean) {
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

    companion object {
        val INFO: SystemInfo = MindControlInfo

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
        override val isOff: Boolean get() = !ready

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
