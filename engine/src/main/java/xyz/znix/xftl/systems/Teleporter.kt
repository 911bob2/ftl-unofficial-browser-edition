package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sys.Input

class Teleporter(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.TELEPORTER
    override val insertButtonSpace: Boolean get() = true

    private val teleportSound by onInit { it.sounds.getSample("teleport") }

    private val padOffImage by onInit { it.getImg("img/ship/interior/teleporter_off.png") }
    private val padOnImage by onInit { it.getImg("img/ship/interior/teleporter_selected.png") }
    private val padActiveImage by onInit { it.getImg("img/ship/interior/teleporter_on.png") }

    private var onCooldown = false

    private var sendButton: TeleporterButton? = null
    private var recvButton: TeleporterButton? = null

    val isSendAvailable: Boolean
        get() {
            if (isPowerLocked || powerSelected == 0)
                return false

            // There must be an enemy ship
            if (ship.sys.getEnemyOf(ship) == null)
                return false

            // There must be at least one crew member standing in the room
            return room!!.crew.any { it.standingPosition != null && it.mode == AbstractCrew.SlotType.CREW && it is LivingCrew }
        }

    val isReceiveAvailable: Boolean
        get() {
            // TeleportSystem::CanReceive(true): an AI ship whose teleporter
            // is under a hacking pulse may pull its crew back while locked.
            if ((isPowerLocked && !(isHackActive && !ship.isPlayerShip)) || powerSelected == 0)
                return false

            // There must be another ship present, whether or not it's
            // currently hostile - if not, we can still teleport crew home
            // from it.
            val enemy = when (ship) {
                ship.sys.enemy -> ship.sys.player
                else -> ship.sys.enemy ?: return false
            }

            // If this is somehow run from a ship that's at a different beacon
            // (which it really shouldn't be!) then block the teleport.
            if (!ship.sys.isShipPresent(enemy))
                return false

            // There must be at least one crew member in the enemy ship
            require(ship != enemy)
            return enemy.intruders.isNotEmpty()
        }

    private var commandedTeleport: TeleportAction? = null

    override fun update(dt: Float) {
        super.update(dt)

        if (ionTimer == 0f) {
            onCooldown = false
        }

        disarmUnderSuperShield()

        if (xyz.znix.xftl.hs.HS.active) {
            hsLoop(dt)
            hsUpdateCommand()
            evacuate()
            neutralForceReady()
            return
        }

        evacuate()
        neutralForceReady()

        // If there's a command ready, grab and action it
        val command = commandedTeleport ?: return

        // If the enemy ship is cloaked, stop now without removing the command.
        // Thus the command will stay there until the enemy comes out of cloak.
        if (command.room.ship.isCloakActive)
            return

        commandedTeleport = null

        // Zoltan Shield Bypass lets crew teleport through a super shield
        // (TeleportSystem::CanSend/OnLoop, TeleportBox::KeyDown).
        val bypass = ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)

        if (command.send) {
            if (!isSendAvailable)
                return

            if (command.room.ship.superShield > 0 && !bypass)
                return

            if (sendCrew(command.room) == 0)
                return

            ship.crewAI.onCrewTeleportedOut()
        } else {
            if (!isReceiveAvailable)
                return

            if (command.room.ship.superShield > 0 && !bypass)
                return

            vanillaRecall(command.room)
            return
        }

        startCooldown(powerSupplied)
    }

    /**
     * CompleteShip::InitiateTeleport (0x409ae0) bringing crew back: ShipManager::TeleportCrew (0x4a2df0) takes the
     * first four of the room's intruders in the ship's crew list order (Room.crew keeps that order) that
     * CrewMember::CanTeleport (0x4790b0) lets go, whatever the teleporter's size; then the lock, the sound and
     * Reconstructive Teleport. Nothing while the previous party is still leaving. Returns how many went.
     */
    private fun vanillaRecall(target: Room): Int {
        if (partyBusy() || eitherJumping(target)) return 0
        val ourCrew = target.crew.filterIsInstance<LivingCrew>()
            .filter { it.mode == AbstractCrew.SlotType.INTRUDER && vanillaCanTeleport(it) }.take(4)
        // Don't waste a cooldown if we're not teleporting anyone
        if (ourCrew.isEmpty())
            return 0
        for (crew in ourCrew) {
            crew.teleportAnimatedTo(room!!)
        }
        party += ourCrew
        reconstructiveHeal(ourCrew)
        startCooldown(powerSupplied)
        return ourCrew.size
    }

    /** Sends the crew standing on the pads to [target], returning how many went. */
    private fun sendCrew(target: Room): Int {
        if (eitherJumping(target)) return 0
        // Crew are only teleported if they're assigned a slot on the teleporter.
        val ourCrew = room!!.crew.filter {
            it.mode == AbstractCrew.SlotType.CREW && it is LivingCrew && room!!.isCrewAssigned(it)
        }

        // Don't waste a cooldown if we're not teleporting anyone
        if (ourCrew.isEmpty())
            return 0

        if (ship.isPlayerShip) ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.TELEPORTED)
        for (crew in ourCrew) {
            crew.teleportAnimatedTo(target)
        }
        party += ourCrew
        reconstructiveHeal(ourCrew)
        return ourCrew.size
    }

    /**
     * CompleteShip::leavingParty / arrivingParty: the crew of the last teleport, until each one's leave animation is
     * over (CompleteShip::OnLoop 0x409ee0); InitiateTeleport (0x409ae0) starts nothing while either is set.
     */
    private val party = ArrayList<AbstractCrew>()

    /**
     * TeleportSystem::OnLoop (1.6.14 0x648640): an armed teleporter (iArmed, send or bring back) is disarmed while the
     * target has a super shield (bSuperShields, written from the target's shields) and the ship has no Zoltan Shield
     * Bypass. The player's arming is the teleport cursor.
     */
    private fun disarmUnderSuperShield() {
        if (!ship.isPlayerShip || ship.sys.shipUI?.teleportMode == null) return
        val target = ship.sys.getEnemyOf(ship) ?: return
        if (target.superShield > 0 && !ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)) ship.sys.clickEvent = null
    }

    /**
     * CompleteShip::InitiateTeleport (1.6.14 0x409ae0; Hyperspace's TeleporterBugFixes rewrite keeps it) does nothing
     * while either ship is jumping (ShipManager::bJumping, set by JumpLeave), sending or bringing back; the player's
     * command was already taken (CombatControl::GetTeleportationCommand 0x57f370 disarms it), so it is lost.
     */
    private fun eitherJumping(target: Room): Boolean = ship.browserJumping || target.ship.browserJumping

    private fun partyBusy(): Boolean {
        party.removeAll { !it.isTeleportLeaving }
        return party.isNotEmpty()
    }

    /** The other ship at the beacon (CompleteShip::enemyShip), hostile or not. */
    private fun otherShip(): Ship? {
        val game = ship.sys
        val other = when (ship) {
            game.player -> game.enemy
            game.enemy -> game.player
            else -> null
        } ?: return null
        return if (game.isShipPresent(other)) other else null
    }

    /** ShipSystem::iHackEffect >= 2 && bUnderAttack: a hacking pulse from a drone on this teleporter. */
    private val hackPulsed: Boolean
        get() = isHackActive && xyz.znix.xftl.hs.lua.LuaAdaptersWP5Systems.underAttack(this)

    /**
     * ShipAI::ShouldEvacuate (0x6254f0): an enemy calls its boarders back when it is a sabotage ship about to escape
     * or with more than two systems lost, or when it surrendered; any ship (the player's too) while its teleporter is
     * hacked with a pulse.
     */
    private fun shouldEvacuate(): Boolean {
        if (!ship.isPlayerShip && ship.health > 0) {
            if (ship.type.boardingStrategy == xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.SABOTAGE) {
                val escapeTimer = ship.escapeTimer
                if (escapeTimer != null && escapeTimer < 15f) return true
                if (ship.aiDestroyedSystems > 2) return true
            }
            if (ship.aiSurrendered) return true
        }
        return hackPulsed
    }

    /**
     * CrewAI::GetEvacTarget (0x6263f0) on the other ship: the room of the first of the boarders there (crew list
     * order) that is hurt enough to come home, or of the first one at all when [forced]. A sabotage or no-boarding
     * ship calls back boarders at a quarter of their health or less, an invasion ship never.
     */
    private fun evacTarget(other: Ship, forced: Boolean): Room? {
        val limit = when (ship.type.boardingStrategy) {
            xyz.znix.xftl.ai.FriendlyCrewAI.BoardingStrategy.INVASION -> -1f
            else -> 0.25f
        }
        for (crew in evacList(other)) {
            if (crew !is LivingCrew || crew.mode != AbstractCrew.SlotType.INTRUDER) continue
            if (forced || crew.health / crew.maxHealth <= limit) return crew.room
        }
        return null
    }

    /** The other ship's crew list as its CrewAI last saw it ([evacList]). */
    private var crewListShip: Ship? = null
    private var crewList: List<AbstractCrew> = emptyList()

    /**
     * CrewAI::crewList of the other ship, which GetEvacTarget walks: CrewAI::OnLoop rebuilds it only in the hostile
     * half of ShipAI::OnLoop (0x625c50), so the player's ship always has a fresh one and an enemy that is not hostile
     * keeps the list of its last hostile frame (oracle wp5-crew-tele: boarders that arrive on a neutral ship are not
     * called home by the player's hacked teleporter until the ship is hostile again).
     */
    private fun evacList(other: Ship): List<AbstractCrew> {
        if (other.isPlayerShip || ship.sys.isEnemyHostile) {
            crewListShip = other
            crewList = ArrayList(other.crew)
            return other.crew
        }
        if (crewListShip !== other) return emptyList()
        return crewList.filter { it in other.crew }
    }

    /**
     * Boarders called home, every frame whatever the ships' hostility (WorldManager::OnLoop 0x61b930 calls
     * ShipAI::SetEvacRequest for both ships), then the arrive half of ShipAI::GetTeleportCommand (0x6257d0):
     * - SetEvacRequest (0x625720): no request while the other ship has a super shield (Hyperspace's
     *   energyBypassTeleportRecall lets a ship with Zoltan Shield Bypass through), once the ship is destroyed or its
     *   escape timer has run out.
     * - GetTeleportCommand: nothing while the other ship is cloaked (unless this teleporter is hacked, with
     *   Hyperspace's teleporterHackFix), on the player's ship only while its teleporter is hacked, nothing while
     *   dying; the teleporter must be able to receive (CanReceive(true): working, and unlocked or hacked), except on
     *   an enemy ship that is not hostile. With teleporterHackFix a hacked teleporter brings boarders back from every
     *   room.
     */
    private fun evacuate() {
        val other = otherShip() ?: return
        if (ship.browserJumping || other.browserJumping) return
        val target = evacTarget(other, shouldEvacuate()) ?: return
        val hs = xyz.znix.xftl.hs.HS.active
        val bypass = hs && xyz.znix.xftl.hs.HS.config?.energyBypassTeleportRecall == true &&
            ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)
        if (other.superShield > 0 && !bypass) return
        if (ship.health <= 0 || (ship.escapeTimer ?: 0f) < 0f) return
        val hacked = hackPulsed
        val hackFix = hs && xyz.znix.xftl.hs.HS.config?.teleporterHackFix == true && hacked
        if (other.isCloakActive && !hackFix) return
        if (ship.isPlayerShip && !hacked) return
        val neutral = !ship.isPlayerShip && !ship.sys.isEnemyHostile
        if (!canReceiveHacked() && !neutral) return
        if (hs) hsInitiate(target, send = false, allRooms = hackFix) else vanillaRecall(target)
    }

    /** TeleportSystem::CanReceive(true) (0x6484e0) for a ship's own AI: working, and unlocked or hacked. */
    private fun canReceiveHacked(): Boolean {
        if (powerSupplied <= 0) return false
        if (!(ionTimer > 0f || hasPermanentLock || isHackActive)) return true
        return hackPulsed
    }

    /**
     * ShipAI::OnLoop (0x625c50) of an enemy ship that is not hostile: TeleportSystem::ForceReady (0x648390) every
     * frame, a full charge and the lock lifted.
     */
    private fun neutralForceReady() {
        if (ship.isPlayerShip || ship !== ship.sys.enemy || ship.sys.isEnemyHostile) return
        hsChargeLevel = 100f
        lockUnits(0)
    }

    private fun startCooldown(power: Int) {
        teleportSound.play()
        // TeleportSystem::InitiateTeleport (0x648570) also empties the charge.
        hsChargeLevel = 0f

        // TeleportSystem::InitiateTeleport: LockSystem for 4/3/2 units
        // (20s at 1 power, 15s at 2, and 10s at 3).
        lockUnits(cooldownTime(power) / 5)

        // Render the teleport pads blue until the ion timer runs out.
        // This prevents an ion weapon making the pads go blue.
        onCooldown = true
    }

    /**
     * An AI ship's first send decision (ShipAI 0x6257d0 on the first frame of the fight, see FriendlyCrewAI): the
     * systems already carry the power they were built with ([Ship.browserPowerAtCreation]) and the AI has not
     * changed it yet, so the crew standing on the pads go at once and InitiateTeleport (0x648570) locks the
     * teleporter for that power. Returns how many went.
     */
    fun sendAtArrival(target: Room): Int {
        if (!isSendAvailable || target.ship.isCloakActive)
            return 0
        if (xyz.znix.xftl.hs.HS.active)
            return hsInitiate(target, send = true, allRooms = false)
        if (target.ship.superShield > 0 && !ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS))
            return 0
        val sent = sendCrew(target)
        if (sent > 0)
            startCooldown(powerSupplied)
        return sent
    }

    // TeleportSystem::SetHackingLevel: a hacking pulse fully charges and
    // unlocks the teleporter.
    override fun hackPulseChanged(active: Boolean) {
        if (active == hsPulseApplied)
            return
        hsPulseApplied = active
        if (active) {
            hsChargeLevel = 100f
            lockUnits(0)
        }
    }

    // The pulse edge [hackPulseChanged] last applied (a Lua SetHackingLevel applies it at once).
    private var hsPulseApplied = false

    override fun onJump() {
        super.onJump()
        // TeleportSystem::Jump (0x648790): StopHacking, and the charge is empty.
        hsChargeLevel = 0f
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        val bottom = ButtonImageSet.select2(ship.sys, "img/systemUI/button_teleport_bottom")
        val top = ButtonImageSet.select2(ship.sys, "img/systemUI/button_teleport_top")

        val buttonBase = powerPos + ConstPoint(22, -49)

        val bgButton = TeleporterButtonBackground(buttonBase, powerPos)

        sendButton = TeleporterButton(buttonBase, ConstPoint(4, 4), true, top, bgButton.superShieldWarning)
        recvButton = TeleporterButton(buttonBase, ConstPoint(4, 27), false, bottom, bgButton.superShieldWarning)

        return listOf(
            // The background comes first so it doesn't draw on top of the others
            bgButton,

            sendButton!!,
            recvButton!!
        )
    }

    /** The pads, with the room's floor stage (the icon comes after the walls: [drawRoom]). */
    override fun drawRoomFloor(g: Graphics) {
        val padImage = when {
            powerSupplied == 0 -> padOffImage
            onCooldown -> padActiveImage
            else -> padOnImage
        }

        for (x in 0 until room!!.width) {
            for (y in 0 until room!!.height) {
                padImage.drawAlignedCentred(
                    room!!.offsetX + x * Constants.ROOM_SIZE + Constants.ROOM_SIZE / 2,
                    room!!.offsetY + y * Constants.ROOM_SIZE + Constants.ROOM_SIZE / 2,
                )
            }
        }
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        // Calling Button.click bit hacky, but it properly shows the super-shield warning
        if (key.id == VanillaHotkeys.SYS_ACTION_TELEPORT_SEND) {
            sendButton?.click(Input.MOUSE_LEFT_BUTTON)
        }
        if (key.id == VanillaHotkeys.SYS_ACTION_TELEPORT_RECV) {
            recvButton?.click(Input.MOUSE_LEFT_BUTTON)
        }
    }

    fun selectTeleportAction(send: Boolean, room: Room, allRooms: Boolean = false) {
        commandedTeleport = TeleportAction(room, send, allRooms)
    }

    // Surprisingly, we have very little to serialise - the crew
    // handle their teleportation state themselves, and our cooldown
    // is handled via ion damage.
    /**
     * CompleteShip::InitiateTeleport (0x409ae0): with Reconstructive Teleport, every crew member being sent or
     * brought back is healed by the augment's value x their maximum health (DirectModifyHealth 0x47a2b0, living
     * crew only) the moment the teleport starts - not when they arrive.
     */
    private fun reconstructiveHeal(crew: List<AbstractCrew>) {
        val value = ship.getAugmentValueOrNull(AugmentBlueprint.RECONSTRUCTIVE_TELEPORT) ?: return
        for (member in crew) {
            if (member.health > 0f)
                member.health = (member.health + value * member.maxHealth).coerceAtMost(member.maxHealth)
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        // If the player has selected a destination to teleport their
        // crew to, remember that across saves.
        val command = commandedTeleport ?: return

        val commandElem = Element("teleportCommand")
        commandElem.setAttribute("roomId", command.room.id.toString())
        commandElem.setAttribute("roomShip", refs[command.room.ship])
        commandElem.setAttribute("isSending", command.send.toString())
        elem.addContent(commandElem)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        val commandElem = elem.getChild("teleportCommand") ?: return

        val roomId = commandElem.getAttributeValue("roomId")!!.toInt()
        val shipRef = commandElem.getAttributeValue("roomShip")!!
        val send = commandElem.getAttributeValue("isSending")!!.toBoolean()

        refs.asyncResolve(Ship::class.java, shipRef) {
            val room = it!!.rooms[roomId]
            commandedTeleport = TeleportAction(room, send)
        }
    }

    private inner class TeleporterButton(
        val base: IPoint,
        offset: IPoint,
        val isSend: Boolean,
        val images: ButtonImageSet,
        val superShieldWarning: WarningFlasher
    ) :
        Button(ship.sys, base + offset, ConstPoint(20, 20)) {

        override val disabled: Boolean
            get() = when {
                isSend -> !isSendAvailable
                else -> !isReceiveAvailable
            }

        override fun draw(g: Graphics) {
            val img = when {
                disabled -> images.off
                hovered -> images.hover

                // If we're selecting, or have selected, a teleport of this type,
                // then keep the button highlighted.
                ship.sys.shipUI.teleportMode == isSend -> images.hover
                commandedTeleport?.send == isSend -> images.hover

                else -> images.normal
            }
            img.draw(base.x.f - BASE_GLOW, base.y.f - BASE_GLOW)
        }

        public override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            var enemyShip = game.getEnemyOf(ship)

            // Allow the player to teleport crew off a non-hostile ship
            if (!isSend && ship == game.player) {
                enemyShip = game.enemy
            }

            if (enemyShip == null)
                return

            if (enemyShip.superShield > 0 && !ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)) {
                superShieldWarning.startFor(3.5f)
                return
            }

            // If there's already an order queued up, clear it out.
            commandedTeleport = null

            ship.sys.shipUI.teleportSelected(isSend)
        }
    }

    private inner class TeleporterButtonBackground(pos: IPoint, powerPos: IPoint) :
        Button(ship.sys, pos, ConstPoint.ZERO) {

        override val makesHoverNoise: Boolean get() = false

        // Put this here so there's only one copy of it.
        val superShieldWarning = WarningFlasher(
            game, powerPos + ConstPoint(17, -59),
            "warning_super_shield_teleporter",
            false, colour = GlowColour.WHITE
        )

        override fun draw(g: Graphics) {
            val img = ship.sys.getImg("img/systemUI/button_teleport_base.png")
            img.draw(pos.x.f - BASE_GLOW, pos.y.f - BASE_GLOW)

            superShieldWarning.draw(g)
        }

        override fun click(button: Int) = Unit
    }

    private class TeleportAction(val room: Room, val send: Boolean, val allRooms: Boolean = false)

    // ---------------------------------------------------------------------------------------------------------------
    // Hyperspace: FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/AI/TeleporterBugFixes.cpp
    // (CompleteShip::InitiateTeleport, ShipAI::GetTeleportCommand and SetEvacRequest rewrites, the teleporterHackFix and
    // energyBypassTeleportRecall options) and src/game/Ship/Augments.cpp (TELEPORT_RECALL). The members follow the
    // native TeleportSystem (1.6.14: constructor 0x647f40, OnLoop 0x648640, CanSend 0x6483d0, CanReceive 0x6484e0,
    // SetArmed 0x6481f0, ClearCrewSlots 0x6480a0 / SetCrewSlot 0x6481a0 from ShipManager 0x4aa670, CombatControl
    // 0x580810 for the player's bCanSend/bCanReceive). Only used while HS is active.

    /** TeleportSystem::chargeLevel (+0x1c0): 0 after a teleport, filling to 100 while the system works. */
    @JvmField var hsChargeLevel = 0f

    /** TeleportSystem::bCanSend / bCanReceive (+0x1c4/+0x1c5). */
    @JvmField var hsCanSend = false
    @JvmField var hsCanReceive = false

    /** TeleportSystem::crewSlots (+0x1cc, one per slot of the room) and iPreparedCrew (+0x1e0). */
    @JvmField val hsCrewSlots = ArrayList<Boolean>()
    @JvmField var hsPreparedCrew = 0

    /** TeleportSystem::bSuperShields (+0x1e8): the target ship has a super shield. */
    @JvmField var hsSuperShields = false

    /** TeleportSystem::iNumSlots (+0x1e4): ShipGraph::GetNumSlots of the room. */
    /** TeleportSystem::iNumSlots written by a script. */
    @JvmField var hsNumSlotsWritten: Int? = null
    val hsNumSlots: Int get() = hsNumSlotsWritten ?: room?.cellCount ?: 0

    /** TeleportSystem::iArmed (+0x1c8): 1 sending, 2 receiving; for the player, the teleport button that is armed. */
    var hsArmed: Int
        get() {
            if (ship.isPlayerShip) return when (ship.sys.shipUI.teleportMode) { true -> 1; false -> 2; null -> 0 }
            return hsArmedValue
        }
        set(value) {
            hsArmedValue = value
            if (!ship.isPlayerShip) return
            when (value) {
                1 -> ship.sys.shipUI.teleportSelected(true)
                2 -> ship.sys.shipUI.teleportSelected(false)
                else -> if (ship.sys.shipUI.teleportMode != null) ship.sys.clickEvent = null
            }
        }
    private var hsArmedValue = 0

    /** ShipSystem vtable +0x2c (Functioning): the system has power. */
    private val hsFunctioning: Boolean get() = powerSupplied > 0

    /** ShipSystem::GetLocked (0x63bc00). */
    // ShipSystem::GetLocked: a lock count of -1 or above 0, or a hacking pulse on the system (iHackEffect > 1).
    private val hsLocked: Boolean get() = ionTimer > 0f || hasPermanentLock || isHackActive

    private val hsShipId: Int get() = xyz.znix.xftl.hs.crew.HsCrew.shipIdOf(ship)

    private fun hsBypass(): Boolean = xyz.znix.xftl.hs.events.HsReq.hasEquipment(ship.sys, ship, AugmentBlueprint.ZOLTAN_BYPASS) > 0

    /** The native OnLoop and the ship's per-frame writes (crew slots, super shield, the player's send/receive). */
    private fun hsLoop(dt: Float) {
        if (hsFunctioning) {
            val rate = CHARGE_RATE.getOrElse(powerSupplied - 1) { 0f }
            hsChargeLevel = (hsChargeLevel + 16f * dt * rate).coerceAtMost(100f) // GetSpeedFactor() * rate
        }
        // ShipManager 0x4aa670: ClearCrewSlots, then SetCrewSlot for each own crew member at its final goal here.
        hsPreparedCrew = 0
        hsCrewSlots.clear()
        repeat(hsNumSlots) { hsCrewSlots += false }
        hsCanSend = false
        val room = room
        if (room != null) for (crew in ship.crew) {
            if (crew.room !== room || crew.mode != AbstractCrew.SlotType.CREW || crew.health <= 0f) continue
            val at = crew.standingPosition ?: continue
            if (crew.pathingTarget != null) continue
            val slot = room.pointToSlot(at)
            if (slot !in hsCrewSlots.indices) continue
            if (!hsCrewSlots[slot]) hsPreparedCrew++
            hsCrewSlots[slot] = true
            hsCanSend = true
        }
        // ShipManager::OnLoop (0x4abd40) writes bSuperShields from the target's shields.
        val target = ship.sys.getEnemyOf(ship)
        hsSuperShields = (target?.superShield ?: 0) > 0
        // CombatControl 0x580810 (the player's ship only): with a target, receiving needs boarders on it and sending a
        // hostile target; without one both are off.
        if (ship.isPlayerShip) {
            val enemy = ship.sys.enemy
            if (enemy != null && ship.sys.isShipPresent(enemy)) {
                hsCanReceive = enemy.crew.any { it.mode == AbstractCrew.SlotType.INTRUDER && it.health > 0f }
                hsCanSend = target != null
            } else {
                hsCanSend = false
                hsCanReceive = false
            }
        }
    }

    /** TeleportSystem::CanSend (0x6483d0). */
    fun hsCanSendNow(): Boolean {
        if (hsShipId == 0 && !hsCanSend) return false
        if (hsShipId != 0 && hsSuperShields && !hsBypass()) return false
        return hsFunctioning && !hsLocked && hsPreparedCrew != 0
    }

    /** TeleportSystem::CanReceive(hacked) (0x6484e0). */
    fun hsCanReceiveNow(hacked: Boolean): Boolean {
        if (!(hsCanReceive || hsShipId != 0) || !hsFunctioning) return false
        if (!hsLocked) return true
        return hacked && isHackActive && xyz.znix.xftl.hs.lua.LuaAdaptersWP5Systems.underAttack(this)
    }

    /** TeleportSystem::Charged (0x648070). */
    fun hsCharged(): Boolean = !hsLocked && hsFunctioning

    /** TeleportSystem::ForceReady (0x648390): full charge, unlocked. */
    fun hsForceReady() {
        hsChargeLevel = 100f
        lockUnits(0)
    }

    /** TeleportSystem::InitiateTeleport (0x648570): the lock for the power (4/3/2 units), the charge emptied. */
    fun hsInitiateSystem() {
        lockUnits(LOCK_UNITS.getOrElse(powerSupplied - 1) { 0 })
        hsChargeLevel = 0f
        if (ship.isPlayerShip) ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.TELEPORTED)
    }

    /** TeleportSystem::SetArmed (0x6481f0): 1 only when CanSend holds, 2 only when CanReceive(false) holds. */
    fun hsSetArmed(value: Int) {
        hsArmed = when {
            value == 1 && hsCanSendNow() -> 1
            value == 2 && hsCanReceiveNow(false) -> 2
            else -> 0
        }
    }

    /** TeleportSystem::SetHackingLevel (0x647b10): a pulse that starts fills the charge and unlocks. */
    fun hsSetHackingLevel(level: Int) {
        val before = isHackActive
        hsHackLevel = level.coerceIn(0, 2)
        if (!before && isHackActive) hackPulseChanged(true)
        else if (before && !isHackActive) hackPulseChanged(false)
    }

    /** ShipSystem::iHackEffect >= 2 && bUnderAttack, with the teleporterHackFix option. */
    private val hsHackFix: Boolean get() = xyz.znix.xftl.hs.HS.config?.teleporterHackFix == true && isHackActive


    /** The engine's commanded teleport under Hyperspace's GetTeleportCommand / InitiateTeleport rules. */
    private fun hsUpdateCommand() {
        val command = commandedTeleport ?: return
        // Cloaking blocks the teleport, unless the AI's teleporter is hacked with teleporterHackFix.
        if (command.room.ship.isCloakActive && !(hsHackFix && !ship.isPlayerShip))
            return
        commandedTeleport = null
        val bypass = ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)
        if (command.send) {
            if (!isSendAvailable) return
            if (command.room.ship.superShield > 0 && !bypass) return
            if (hsInitiate(command.room, send = true, allRooms = false) == 0) return
            ship.crewAI.onCrewTeleportedOut()
        } else {
            if (!isReceiveAvailable) return
            // SetEvacRequest: the AI cannot call boarders back through the target's super shield; with
            // energyBypassTeleportRecall Zoltan Shield Bypass lets it. The player's recall keeps TeleportBox's rule.
            val aiBypass = xyz.znix.xftl.hs.HS.config?.energyBypassTeleportRecall == true && bypass
            if (command.room.ship.superShield > 0 && !(if (ship.isPlayerShip) bypass else aiBypass)) return
            hsInitiate(command.room, send = false, allRooms = command.allRooms)
        }
    }

    /**
     * CompleteShip::InitiateTeleport (Hyperspace rewrite): the senders are this ship's crew in the teleporter room, in
     * crew list order, as many as the target ship has free intruder slots (no limit of four); the boarders brought back
     * are the intruders of the target room (all rooms with teleporterHackFix on a hacked AI teleporter), at most
     * max(the room's slots, 4) (the room's slots alone for all rooms). Only crew that can teleport and are not already
     * leaving go. Then the system's InitiateTeleport, the sound and TELEPORT_HEAL (HasEquipment: value x max health
     * for each). Returns how many went.
     */
    private fun hsInitiate(target: Room, send: Boolean, allRooms: Boolean): Int {
        if (partyBusy() || eitherJumping(target)) return 0
        val party = ArrayList<LivingCrew>()
        if (send) {
            var free = 0
            for (r in target.ship.rooms) for (i in 0 until r.cellCount) {
                if (r.isSlotFree(r.slotToPoint(i), AbstractCrew.SlotType.INTRUDER)) free++
            }
            if (free > 0) for (crew in ship.crew) {
                if (crew !is LivingCrew || crew.room !== room || crew.mode != AbstractCrew.SlotType.CREW) continue
                if (!hsCanTeleport(crew) || crew.currentAction == AbstractCrew.Action.TELEPORTING) continue
                party += crew
                if (party.size >= free) break
            }
        } else {
            var cap = room!!.cellCount
            if (!allRooms) cap = maxOf(cap, 4)
            for (crew in target.ship.crew) {
                if (crew !is LivingCrew || (!allRooms && crew.room !== target)) continue
                if (crew.mode != AbstractCrew.SlotType.INTRUDER || !hsCanTeleport(crew)) continue
                if (crew.currentAction == AbstractCrew.Action.TELEPORTING) continue
                party += crew
                if (party.size >= cap) break
            }
        }
        if (party.isEmpty()) return 0
        if (send && ship.isPlayerShip) ship.sys.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.TELEPORTED)
        for (crew in party) crew.teleportAnimatedTo(if (send) target else room!!)
        this.party += party
        teleportSound.play()
        lockUnits(LOCK_UNITS.getOrElse(powerSupplied - 1) { 0 })
        hsChargeLevel = 0f
        onCooldown = true
        if (xyz.znix.xftl.hs.events.HsReq.hasEquipment(ship.sys, ship, AugmentBlueprint.RECONSTRUCTIVE_TELEPORT) > 0) {
            val value = ship.getAugmentValue(AugmentBlueprint.RECONSTRUCTIVE_TELEPORT)
            for (crew in party) {
                if (crew.health > 0f) crew.health = (crew.health + value * crew.maxHealth).coerceIn(0f, crew.maxHealth)
            }
        }
        return party.size
    }

    companion object {
        private const val BASE_GLOW: Int = 6

        /** TeleportSystem's charge per speed factor by power (0x88634c) and lock units by power (0x886358). */
        private val CHARGE_RATE = floatArrayOf(0.2f, 0.35f, 0.5f)
        private val LOCK_UNITS = intArrayOf(4, 3, 2)

        /**
         * CrewMember::CanTeleport as Hyperspace replaces it (CrewVTable.cpp _HS_CanTeleport): the native test
         * (0x4790b0: not dying; a crew member walking out of the room it stands in only as an intruder; the player's
         * own crew not while mind controlled), then the CAN_TELEPORT stat.
         */
        @JvmStatic
        fun hsCanTeleport(crew: LivingCrew): Boolean {
            if (crew.currentAction == AbstractCrew.Action.DYING || crew.health <= 0f) return false
            val goal = crew.pathingTarget
            if (goal != null && goal.room !== crew.room && crew.mode != AbstractCrew.SlotType.INTRUDER) return false
            if (xyz.znix.xftl.hs.crew.HsCrew.shipId(crew) == 0 && crew.isMindControlled) return false
            return xyz.znix.xftl.hs.crew.HsCrew.ex(crew).calculateBool(xyz.znix.xftl.hs.stat.CrewStat.CAN_TELEPORT, null)
        }

        /**
         * CrewMember::CanTeleport (0x4790b0): not dying; a crew member walking out of the room it stands in only as an
         * intruder; the player's own crew not while mind controlled.
         */
        @JvmStatic
        fun vanillaCanTeleport(crew: LivingCrew): Boolean {
            if (crew.currentAction == AbstractCrew.Action.DYING || crew.health <= 0f) return false
            val goal = crew.pathingTarget
            if (goal != null && goal.room !== crew.room && crew.mode != AbstractCrew.SlotType.INTRUDER) return false
            return !(crew.ownerShip?.isPlayerShip == true && crew.isMindControlled)
        }

        /**
         * TELEPORT_RECALL (Augments.cpp ShipManager::OnLoop, before the original): a ship whose hull is gone loses the
         * boarders of the other ship to that ship when it has the augment (RecallBoarders with effects).
         */
        @JvmStatic
        fun hsRecallOnLoop(ship: Ship) {
            if (ship.health > 0) return
            val game = ship.sys
            val other = (if (ship === game.player) game.enemy else game.player) ?: return
            if (!game.isShipPresent(other) || !other.hasAugment("TELEPORT_RECALL")) return
            xyz.znix.xftl.hs.events.HsEventEffects.recallBoarders(game, if (ship === game.player) -1 else 1, false, true)
        }

        /** TELEPORT_RECALL (Augments.cpp ShipManager::JumpLeave, before the original): both ships' boarders by augment. */
        @JvmStatic
        fun hsRecallOnJumpLeave(ship: Ship) {
            val game = ship.sys
            val player = game.player ?: return
            val enemy = game.enemy
            if (ship === player) {
                if (enemy != null && enemy.hasAugment("TELEPORT_RECALL")) xyz.znix.xftl.hs.events.HsEventEffects.recallBoarders(game, -1, false, true)
                if (player.hasAugment("TELEPORT_RECALL")) xyz.znix.xftl.hs.events.HsEventEffects.recallBoarders(game, 1, false, true)
            } else {
                if (player.hasAugment("TELEPORT_RECALL")) xyz.znix.xftl.hs.events.HsEventEffects.recallBoarders(game, 1, false, true)
                if (ship.hasAugment("TELEPORT_RECALL")) xyz.znix.xftl.hs.events.HsEventEffects.recallBoarders(game, -1, false, true)
            }
        }

        val INFO: SystemInfo = TeleporterInfo

        fun cooldownTime(power: Int): Int {
            if (power !in 1..3)
                return 0
            return 5 * (5 - power)
        }
    }
}

private object TeleporterInfo : SystemInfo("teleporter") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Teleporter(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        // Hyperspace 1.23.2 SystemDescriptionFixes: the fourth level reads teleporter_instant.
        if (xyz.znix.xftl.hs.HS.active && level == 3) return translator["teleporter_instant"]
        val time = Teleporter.cooldownTime(level + 1)
        return translator["teleporter_on"].replaceArg(time)
    }
}
