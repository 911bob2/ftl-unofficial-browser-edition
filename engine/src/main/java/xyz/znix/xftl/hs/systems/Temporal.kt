// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/TemporalSystem.cpp,
// src/game/Ship/ShipSystems/TemporalSystemVTable.cpp, src/game/Ship/ShipSystems/System_Extend.h
// (TemporalSystem_Wrapper), src/game/UserInterface/TemporalBoxVTable.cpp and the temporal parts of
// src/game/Ship/ShipSystems/CustomSystems.cpp and src/game/UserInterface/AdditionalWeaponSlots.cpp (cursor).
// FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.Hotkey
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.StandardTooltip
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.SystemBlueprint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Hyperspace's temporal manipulator (system id 20): speeds up or slows down time in one room of either ship.
 *
 * TemporalSystem_Wrapper's state machine: a queued target ([queuedRoom]) starts on the next loop if the system is
 * ready; while on, the system holds the permanent lock (LockSystem(-1)), its strength follows the effective power
 * every frame and the timer's length follows the strength (TimerHelper::SetMaxTime keeps the elapsed share); it stops
 * when the timer runs out, the power is gone, a hacking pulse hits it, the target ship is gone, or another temporal
 * system takes the room, and then locks for the level's cooldown units (AddLock).
 */
class Temporal(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.TEMPORAL

    // ShipSystem_Extend::xOffset 54 for SYS_TEMPORAL (System_Extend.cpp).
    override val insertButtonSpace: Boolean get() = true

    // LockSystem(-1) while on; the power bars show the white lock (RenderPowerBoxes, iLockCount == -1).
    override val holdsPermanentLock: Boolean get() = turnedOn
    override val hasWhiteLockingBox: Boolean get() = hasPermanentLock

    /** TemporalSystem_Wrapper::bTurnedOn */
    var turnedOn = false
        private set

    /** isSpeeding */
    var isSpeeding = false
        private set

    /** dilationStrength: the effective power the dilation runs at. */
    var dilationStrength = 0
        private set

    /** The wrapper's TimerHelper: elapsed time and goal (seconds). */
    var timerTime = 0f
        private set
    var timerGoal = 0f
        private set

    /** currentRoom (on its own ship; HS keeps currentShipId alongside). */
    var currentRoom: Room? = null
        private set

    /** queuedRoomId/queuedShipId/queuedSpeedUp, set by the player's click or the AI, started by the next loop. */
    var queuedRoom: Room? = null
        private set
    var queuedSpeedUp = false
        private set

    /** GetRealDilation */
    val realDilation: Int get() = if (isSpeeding) dilationStrength else -dilationStrength

    /** ShipSystem::GetLocked: iLockCount != 0 (an ion/cooldown lock, or the permanent lock). */
    val isLocked: Boolean get() = isIonised || hasPermanentLock

    /** ShipSystem::Functioning for a non-manned system: Powered(), GetEffectivePower() > 0 (FUN_0063a960). */
    val functioning: Boolean get() = powerSupplied > 0

    /** IsReady */
    val isReady: Boolean get() = !turnedOn && !isLocked && functioning

    /** The player's arming state (TEMPORAL_ARM_*): kept as the game's room click listener. */
    val armState: Int
        get() {
            val l = ship.sys.clickEvent as? TemporalRoomListener ?: return ARM_NONE
            return if (l.system === this) l.state else ARM_NONE
        }

    private var box: TemporalBox? = null

    private fun play(name: String) {
        ship.sys.sounds.getSampleOrWarn(name)?.play()
    }

    /** SetArmed (the player's TemporalBox); arming forgets a queued target. */
    fun setArmed(state: Int) {
        if (state != ARM_NONE) {
            queuedRoom = null
            ship.sys.clickEvent = TemporalRoomListener(this, state)
        } else if (armState != ARM_NONE) {
            ship.sys.clickEvent = null
        }
    }

    /**
     * CombatControl::SelectTarget with temporal armed: a room of the own ship is queued; one of the target ship only
     * without a super shield (or with Zoltan Bypass). Either way the arming ends.
     */
    fun selectRoom(room: Room, speedUp: Boolean) {
        if (room.ship === ship) {
            queue(room, speedUp)
        } else if (room.ship.superShield <= 0 || ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS)) {
            queue(room, speedUp)
        }
    }

    internal fun queue(room: Room, speedUp: Boolean) {
        queuedRoom = room
        queuedSpeedUp = speedUp
    }

    /** StartTimeDilation */
    fun startTimeDilation(room: Room, speedUp: Boolean) {
        queuedRoom = null

        if (isLocked || !functioning) return
        if (!ship.sys.isShipPresent(room.ship)) return

        turnedOn = true
        isSpeeding = speedUp
        dilationStrength = powerSupplied

        // LockSystem(-1): replaces any lock (none can be held here, GetLocked was false).
        permanentLockOverridden = false

        timerTime = 0f
        timerGoal = HsSystems.dilationDuration(realDilation).toFloat()

        currentRoom = room

        val ex = HsTemporal.roomState(room)
        if (ex.timeDilation != 0 && (if (speedUp) ex.timeDilation < 0 else ex.timeDilation > 0)) {
            // Temporal counter: both effects end.
            ex.source = null
            ex.timeDilation = 0
            stopTimeDilation()
        } else {
            ex.source = this
            ex.timeDilation = realDilation
            play(if (speedUp) "temporalFast" else "temporalSlow")
        }
    }

    /** StopTimeDilation */
    fun stopTimeDilation() {
        if (!turnedOn) return

        val room = currentRoom
        if (room != null) {
            val ex = room.hsSys
            if (ex != null && ex.source === this) {
                ex.source = null
                ex.timeDilation = 0
            }
        }

        play("temporalEnd")
        // AddLock over the permanent lock sets the cooldown units (ShipSystem::AddLock 0x63bd80).
        addLockUnits(HsSystems.dilationCooldown(realDilation))
        turnedOn = false
        currentRoom = null
        dilationStrength = 0
    }

    override fun update(dt: Float) {
        // TemporalSystem::_HS_OnLoop: ShipSystem::OnLoop, then the wrapper's OnLoop.
        super.update(dt)

        queuedRoom?.let { startTimeDilation(it, queuedSpeedUp) }

        val room = currentRoom
        if (!functioning || isHackActive || room == null || !ship.sys.isShipPresent(room.ship) || room.ship.isDead) {
            stopTimeDilation()
        }

        if (turnedOn) {
            dilationStrength = powerSupplied

            // TimerHelper::SetMaxTime (0x5cb170): currTime = newMax * (currTime / oldMax).
            val newGoal = HsSystems.dilationDuration(realDilation).toFloat()
            if (timerGoal != 0f) timerTime = newGoal * (timerTime / timerGoal)
            timerGoal = newGoal
            timerTime += dt

            val current = currentRoom
            if (current != null) {
                val ex = current.hsSys
                if (ex == null || ex.source !== this) {
                    stopTimeDilation() // cancelled when overwritten
                } else {
                    ex.timeDilation = realDilation
                }
            }

            if (timerTime >= timerGoal) {
                stopTimeDilation()
            }
        }
    }

    // TemporalSystem::_HS_Jump: StopHacking, StopTimeDilation, LockSystem(0).
    override fun onJump() {
        super.onJump()
        stopTimeDilation()
        lockUnits(0)
        queuedRoom = null
    }

    /** TemporalSystem::_HS_ShipDestroyed */
    fun shipDestroyed() {
        stopTimeDilation()
        queuedRoom = null
    }

    override fun powerStateChanged() {
        super.powerStateChanged()
        // The box's charging image follows the power while on.
        if (ship == ship.sys.player) ship.sys.shipUI?.updateButtons()
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        val b = TemporalBox(ship.sys, powerPos)
        box = b
        return listOf(b)
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)
        // TemporalBox::OnKeyDown: only while unlocked, and only for an active button.
        if (isLocked) return
        val b = box ?: return
        when (key.id) {
            HOTKEY_SPEED -> if (b.speedActive) setArmed(ARM_SPEED)
            HOTKEY_SLOW -> if (b.slowActive) setArmed(ARM_SLOW)
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        // ExportBattleState: bTurnedOn, isSpeeding, the timer, the target room and the strength.
        SaveUtil.addAttrBool(elem, "on", turnedOn)
        SaveUtil.addAttrBool(elem, "speeding", isSpeeding)
        SaveUtil.addAttrInt(elem, "strength", dilationStrength)
        SaveUtil.addAttrFloat(elem, "timer", timerTime)
        SaveUtil.addAttrFloat(elem, "goal", timerGoal)
        currentRoom?.let { SaveUtil.addRoomRef(elem, "target", refs, it) }
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        turnedOn = elem.getAttributeValue("on") == "true"
        isSpeeding = elem.getAttributeValue("speeding") == "true"
        dilationStrength = elem.getAttributeValue("strength")?.toIntOrNull() ?: 0
        timerTime = elem.getAttributeValue("timer")?.toFloatOrNull() ?: 0f
        timerGoal = elem.getAttributeValue("goal")?.toFloatOrNull() ?: 0f
        currentRoom = null
        if (elem.getChild("target") != null) {
            SaveUtil.getRoomRef(elem, "target", refs) { room ->
                currentRoom = room
                // ImportBattleState: the room takes this system's dilation again.
                val ex = HsTemporal.roomState(room)
                ex.timeDilation = realDilation
                ex.source = this
            }
        }
    }

    /**
     * TemporalBox: a CooldownSystemBox (roundDown false) with the speed/slow buttons. While on, the cooldown box shows
     * the charge left at the effective power (GetCooldownLevel / GetCooldownFraction); otherwise the two buttons on
     * `systemUI/button_temporal_base.png` (40x81 at the box's location + (36, -39)), speed drawn at +(2 + 9, 9), slow at
     * +(2 + 9, 35) (hit boxes at +(9, 9) and +(9, 35)).
     */
    inner class TemporalBox(game: InGameState, private val powerPos: IPoint) :
        Button(game, ConstPoint(powerPos.x - 19 + 34, powerPos.y - 19 - 39), ConstPoint(42, 81)) {

        private val baseImage = game.getImg("img/systemUI/button_temporal_base.png")
        private val speedImages = ButtonImageSet.select2(game, "img/systemUI/button_temporal_speed")
        private val slowImages = ButtonImageSet.select2(game, "img/systemUI/button_temporal_slow")

        // Button::bActive, set by RenderBox while the system is off.
        var speedActive = false
            private set
        var slowActive = false
            private set

        private var mouseX = -1
        private var mouseY = -1

        private val buttonX get() = pos.x // buttonOffset.x (the box location + 34)
        private val buttonY get() = pos.y // buttonOffset.y (the box location - 39)

        private fun overSpeed() = inside(9, 9)
        private fun overSlow() = inside(9, 35)
        private fun inside(bx: Int, by: Int): Boolean {
            val x = mouseX - buttonX - bx
            val y = mouseY - buttonY - by
            return x in 0 until 22 && y in 0 until 22
        }

        override val makesHoverNoise: Boolean get() = false

        override fun update(x: Int, y: Int, blockHover: Boolean) {
            super.update(x, y, blockHover)
            mouseX = if (blockHover) -1 else x
            mouseY = if (blockHover) -1 else y
        }

        override fun draw(g: Graphics) {
            if (!turnedOn) {
                val ready = isReady
                speedActive = ready
                slowActive = ready
                // GL_Translate(buttonOffset) + (2, 0): the base image.
                baseImage.draw(buttonX + 2, buttonY)
                val unlocked = !isLocked
                drawButton(speedImages, 9, 9, speedActive, unlocked && overSpeed())
                drawButton(slowImages, 9, 35, slowActive, unlocked && overSlow())

                if (unlocked) {
                    if (speedActive && overSpeed()) g.tooltip = tooltip("temporal_button_speed", HOTKEY_SPEED)
                    if (slowActive && overSlow()) g.tooltip = tooltip("temporal_button_slow", HOTKEY_SLOW)
                }
            } else {
                drawCooldown()
            }

            // CombatControl::UpdateTarget while armed: the instant room tooltip.
            val state = armState
            if (state != ARM_NONE) {
                val room = game.hoveredRoom
                if (room != null) g.tooltip = armTooltip(room, state)
            }
        }

        private fun drawButton(images: ButtonImageSet, bx: Int, by: Int, active: Boolean, hover: Boolean) {
            val img = when {
                !active -> images.off
                hover -> images.hover
                else -> images.normal
            }
            // RenderBox draws the buttons under the base image's GL_Translate(2, 0); their hit boxes (MouseMove with
            // x - buttonOffset.x) are not shifted.
            img.draw(buttonX + 2 + bx, buttonY + by)
        }

        /**
         * CooldownSystemBox::OnRender (0x4d95a0) at level = effective power (1-4): the level's base box and the
         * charging image cut to `(3 * level + 2) * fraction` bars (rounded up), each 4 px over a 26 px base.
         */
        private fun drawCooldown() {
            val level = if (powerSupplied <= 0) 1 else min(powerSupplied, 4)
            val base = game.getImg("img/systemUI/button_cloaking${level}_base.png")
            val charge = game.getImg("img/systemUI/button_cloaking${level}_charging_on.png")
            val imageX = powerPos.x + 23f - 6f
            val imageY = powerPos.y + 17f - 79f - 7f
            base.draw(imageX, imageY)
            val fraction = if (timerGoal > 0f) 1f - timerTime / timerGoal else 0f
            if (fraction <= 0f) return
            val bars = ceil((level * 3 + 2) * fraction).toInt()
            val height = min(charge.height, 26 + bars * 4)
            val topY = charge.height - height
            charge.draw(
                imageX, imageY + topY, imageX + charge.width, imageY + charge.height,
                0f, topY.toFloat(), charge.width.toFloat(), charge.height.toFloat()
            )
        }

        public override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON) return
            // TemporalBox::LeftMouseClick
            if (isLocked) return
            if (speedActive && overSpeed()) setArmed(ARM_SPEED)
            if (slowActive && overSlow()) setArmed(ARM_SLOW)
        }

        private fun tooltip(key: String, hotkey: String): StandardTooltip = object : StandardTooltip(game) {
            override fun getText(): String =
                game.translator[key].replace("\\1", game.getButtonNameForHotkey(hotkey, "")).replace("\\n", "\n")
        }

        private fun armTooltip(room: Room, state: Int): StandardTooltip = object : StandardTooltip(game) {
            override fun getText(): String {
                if (room.ship !== ship && room.ship.superShield > 0 && !ship.hasAugment(AugmentBlueprint.ZOLTAN_BYPASS))
                    return game.translator["temporal_arm_super_shields"]
                val key = if (state == ARM_SPEED) "temporal_arm_speed" else "temporal_arm_slow"
                return replaceTemporalText(game.translator[key], powerSupplied)
            }
        }
    }

    companion object {
        const val ARM_NONE = 0
        const val ARM_SPEED = 1
        const val ARM_SLOW = 2

        /** HS hotkey ids (CustomHotkeys.cpp); defaults ',' slow, '.' speed, ';' power (render registers them). */
        const val HOTKEY_SPEED = "temporal_speed"
        const val HOTKEY_SLOW = "temporal_slow"

        @JvmField
        val INFO: SystemInfo = TemporalInfo

        /** RemoveTrailingZeros(std::fixed << setprecision(3) << value) */
        @JvmStatic
        fun format3(value: Float): String {
            val m = Math.round(value.toDouble() * 1000.0)
            val whole = m / 1000
            val frac = (m % 1000).toInt()
            var s = whole.toString() + "." + frac.toString().padStart(3, '0')
            s = s.trimEnd('0').trimEnd('.')
            return s
        }

        /** ReplaceTemporalText: \1..\3 speed (strength, cooldown x 5, duration), \4..\6 the same for slow. */
        @JvmStatic
        fun replaceTemporalText(text: String, power: Int): String {
            var t = text
            var counter = 0
            for (i in 0 until 2) {
                val real = if (i == 0) power else -power
                t = t.replace("\\" + (++counter), format3(HsSystems.dilationStrength(real)))
                t = t.replace("\\" + (++counter), (HsSystems.dilationCooldown(real) * 5).toString())
                t = t.replace("\\" + (++counter), HsSystems.dilationDuration(real).toString())
            }
            return t
        }
    }
}

/** CombatControl's temporal arming as the game's room click listener; right click or another selection disarms. */
class TemporalRoomListener(@JvmField val system: Temporal, @JvmField val state: Int) : InGameState.RoomClickListener {
    override fun roomClicked(room: Room, gc: xyz.znix.xftl.sys.GameContainer) {
        system.selectRoom(room, state == Temporal.ARM_SPEED)
    }

    /** MouseControl::OnRender (AdditionalWeaponSlots.cpp): HS names the files the other way round. */
    fun cursorPath(valid: Boolean): String {
        val kind = if (state == Temporal.ARM_SLOW) "slow" else "speed"
        return if (valid) "img/mouse/mouse_temporal_$kind.png" else "img/mouse/mouse_temporal_${kind}_valid.png"
    }
}

private object TemporalInfo : SystemInfo("temporal") {
    // bNeedsPower true, bBoostable/bNeedsManned/bLevelBoostable false (CustomSystems.cpp ShipSystem ctor hook).
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Temporal(blueprint)

    /** ShipSystem::GetLevelDescription(20, level): `temporal_tooltip` for level + 1. */
    override fun getLevelName(level: Int, translator: Translator): String {
        return Temporal.replaceTemporalText(translator["temporal_tooltip"], level + 1).replace("\\n", "\n")
    }
}
