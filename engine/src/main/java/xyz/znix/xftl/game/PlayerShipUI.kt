package xyz.znix.xftl.game

import org.jdom2.Element
import org.newdawn.slick.geom.Rectangle
import xyz.znix.xftl.*
import xyz.znix.xftl.Constants.*
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.environment.PulsarEnvironment
import xyz.znix.xftl.environment.SunEnvironment
import xyz.znix.xftl.game.InGameState.RoomClickListener
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import java.util.*
import kotlin.math.*

class PlayerShipUI(val ship: Ship, private val game: InGameState) {
    private val font = game.getFont("HL2", 2f)
    private val weaponNameText = game.getFont("JustinFont8")
    private val crewNameText = game.getFont(CREW_NAME_FONT)
    private val weaponNumberFont = game.getFont("c&c")
    private val numberFont = game.getFont("num_font")
    private val oxygenEvadeFont = game.getFont("JustinFont10")
    private val tooltipFont = game.getFont("JustinFont11Bold")

    private val numberDeltaFont = game.getFont("HL2", 3f)
    private val numberDeltaFontBg = game.getFont("HL1", 3f)

    private val powerUpSound = game.sounds.getSample("powerUpSystem")
    private val powerUpFailSound = game.sounds.getSample("powerUpFail")
    private val powerDownSound = game.sounds.getSample("powerDownSystem")
    private val autofireOnSound = game.sounds.getSample("autofireOn")
    private val autofireOffSound = game.sounds.getSample("autofireOff")

    private val cursorNormal = game.getCursor("img/mouse/pointerInvalid.png")
    private val cursorHover = game.getCursor("img/mouse/pointerValid.png")
    private val cursorTeleRetrieveInvalid = game.getCursor("img/mouse/mouse_teleport_in2.png")
    private val cursorTeleRetrieveValid = game.getCursor("img/mouse/mouse_teleport_in1.png")
    private val cursorTeleSendInvalid = game.getCursor("img/mouse/mouse_teleport_out2.png")
    private val cursorTeleSendValid = game.getCursor("img/mouse/mouse_teleport_out1.png")
    private val cursorMindInvalid = game.getCursor("img/mouse/mouse_mind_valid.png")
    private val cursorMindValid = game.getCursor("img/mouse/mouse_mind.png") // Valid doesn't have the _valid path
    private val cursorHacking = game.getCursor("img/mouse/mouse_hacking.png")

    private var selectWeaponClickEvent: RoomClickListener? = null
    private var targetingSelectedWeapon: Int? = null

    private val selectedCrew: MutableList<AbstractCrew> = ArrayList()

    /**
     * The iPad port's touch affordance: while crew are selected the game
     * auto-pauses and shows the ROOM SELECTION prompt; tapping a room then
     * sends the selected crew there and unpauses. Only active on touch
     * platforms (PlatformSpecific.isTouchUi).
     */
    var roomSelectionMode: Boolean = false
        private set

    /**
     * True while the touch power popup is open: manipulating power
     * auto-pauses the game until the popup is closed.
     */
    val powerPopupOpen: Boolean
        get() = powerPopup != null

    /**
     * How much selected crew are scaled up by while [roomSelectionMode] is
     * active (1 = normal size). Used by Ship.drawInterior.
     */
    fun crewEnlargeScale(crew: AbstractCrew): Float {
        return if (roomSelectionMode && crew in selectedCrew) ENLARGED_CREW_SCALE else 1f
    }

    private fun updateRoomSelectionMode() {
        if (!PlatformSpecific.INSTANCE.isTouchUi)
            return

        // Leave the mode when there's nothing left to move, or when
        // something else takes over the input (windows, weapon targeting,
        // the teleporter's room picker). Crew stay selected in that case.
        if (selectedCrew.isEmpty() || pauseWindow != null || currentWindow != null ||
            game.clickEvent != null || beamTargeting != null
        ) {
            roomSelectionMode = false
            return
        }

        // Engage whenever crew are selected otherwise - selecting a
        // crewmember is always the start of a move gesture on touch.
        roomSelectionMode = true
    }

    /**
     * The touch power popup (iPad-style): tap a system icon and a small
     * panel opens next to it with big +/- power buttons (and, for weapons
     * and drones, a per-item on/off toggle row). Built from the iPad UI
     * kit art that ships in ftl.dat (img/ipad/systemUI/...).
     */
    private var powerPopup: PowerPopup? = null

    /**
     * The buttons of the systems strip (system icons, weapon/drone boxes
     * and their extra buttons). These live in the strip's own coordinate
     * space, which is scaled up on touch (see [systemsBarScale]) - input
     * for them must go through [toBarSpace].
     */
    private val systemsBarButtons = ArrayList<Button>()

    /**
     * How much the systems strip is scaled up. 1 on desktop; on touch the
     * strip grows as far as the widest ship layout allows (see
     * [computeSystemsBarScale]) so the buttons are easier to tap.
     */
    private var systemsBarScale = 1f

    // The subsystems panel's last drawn position - functional state: a
    // change triggers a button rebuild (see render).
    private var lastSubSysBoxX = -1
    private var lastSubSysBoxY = -1

    private fun computeSystemsBarScale() {
        if (!PlatformSpecific.INSTANCE.isTouchUi) {
            systemsBarScale = 1f
            return
        }

        // Replicate the strip walk's advances to find its natural width,
        // then scale up as far as it fits the screen.
        var px = 58
        for (system in ship.mainSystems) {
            px += when {
                system is Weapons -> 48 + ship.weaponSlots!! * 97
                system is Drones -> 48 + ship.droneSlots!! * 97
                system.insertButtonSpace -> 54
                else -> 36
            }
        }

        systemsBarScale = 1.5f.coerceAtMost(1250f / px).coerceAtLeast(1f)
    }

    private fun toBarSpace(x: Int, y: Int): Pair<Int, Int> = Pair(
        (x / systemsBarScale).toInt(),
        ((y - height * (1 - systemsBarScale)) / systemsBarScale).toInt()
    )

    private fun barToScreen(x: Int, y: Int): Pair<Int, Int> = Pair(
        (x * systemsBarScale).toInt(),
        (height * (1 - systemsBarScale) + y * systemsBarScale).toInt()
    )

    private fun togglePowerPopup(system: MainSystem, anchor: IPoint) {
        powerPopup = when {
            powerPopup?.system === system -> null
            else -> {
                // The button's position is in the scaled bar space; the
                // popup draws in screen space.
                val (sx, sy) = barToScreen(anchor.x, anchor.y)
                PowerPopup(system, ConstPoint(sx, sy))
            }
        }
    }

    private fun updatePowerPopup() {
        // An armed weapon's room-tap listener also lives in game.clickEvent,
        // but it must NOT dismiss the popup - otherwise the popup closes
        // instantly whenever a weapon is armed. The popup is modal while
        // open, so the weapon simply can't fire until it's closed.
        val weaponArmedClickEvent =
            game.clickEvent != null && game.clickEvent === selectWeaponClickEvent

        if (powerPopup != null && (pauseWindow != null || currentWindow != null ||
            (game.clickEvent != null && !weaponArmedClickEvent) || beamTargeting != null)
        ) {
            powerPopup = null
        }
    }

    /**
     * Route a mouse click to the open popup. Returns false if no popup is
     * open. Any click while a popup is open is consumed: inside it hits
     * the +/- or item toggle buttons, outside it just closes the popup.
     */
    private fun popupClick(button: Int, x: Int, y: Int): Boolean {
        val popup = powerPopup ?: return false

        if (!popup.contains(x, y)) {
            powerPopup = null
            return true
        }

        popup.clickAt(button, x, y)
        return true
    }

    private data class PopupRect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Int, py: Int) = x <= px && px < x + w && y <= py && py < y + h
    }

    private inner class PowerPopup(val system: MainSystem, anchor: IPoint) {
        private val scale = 1.5f
        private val pad = (8 * scale).toInt()
        private val bigButton = (56 * scale).toInt()
        private val barScale = 1f
        private val barSize = (26 * barScale).toInt()
        private val toggleSize = (48 * scale).toInt()
        private val itemGap = (6 * scale).toInt()
        private val rowGap = (8 * scale).toInt()

        private val itemButtons: List<WeaponDroneButton> =
            buttons.filterIsInstance<WeaponDroneButton>()
                .filter { it.ownerSystem === system && !it.empty }
                .sortedBy { it.pos.x }

        private val hasItems = system is Weapons || system is Drones

        // Layout, top to bottom: the +/- buttons, then the energy bar at
        // 2x, then (weapons/drones only) the per-item power toggles.
        //
        // drawIconAndPower draws its icon from (x-19, y-19) and stacks the
        // power bars UPWARD from (x+5, y-5) at ~9px per bar, so the row is
        // sized for the system's full bar stack and the draw call is offset
        // to land everything inside the panel.
        private val barStack = system.energyLevels * 10
        private val barContentH = 39 + barStack
        private val barRowHeight = barContentH * 2
        private val barRowWidth = 80
        private val buttonsWidth = bigButton * 2 + (8 * scale).toInt()
        private val itemsWidth =
            if (hasItems) itemButtons.size * (toggleSize + itemGap) - itemGap else 0
        private val contentWidth = maxOf(buttonsWidth, barRowWidth, itemsWidth)
        private val width = contentWidth + pad * 2
        private val height = pad + bigButton + rowGap + barRowHeight +
                (if (hasItems) rowGap + toggleSize else 0) + pad

        private val x = (anchor.x + 26 + 10).coerceAtMost(1280 - width - 4)
        private val y = anchor.y.coerceIn(4, 720 - height - 4)

        private val buttonsStartX = x + (width - buttonsWidth) / 2
        private val downRect = PopupRect(buttonsStartX, y + pad, bigButton, bigButton)
        private val upRect = PopupRect(buttonsStartX + bigButton + (8 * scale).toInt(), y + pad, bigButton, bigButton)
        private val barX = x + (width - barRowWidth) / 2
        private val barY = y + pad + bigButton + rowGap
        private val itemRects = itemButtons.mapIndexed { i, _ ->
            PopupRect(
                x + (width - itemsWidth) / 2 + i * (toggleSize + itemGap),
                barY + barRowHeight + rowGap, toggleSize, toggleSize
            )
        }

        fun contains(px: Int, py: Int) =
            x <= px && px < x + width && y <= py && py < y + height

        fun clickAt(button: Int, px: Int, py: Int) {
            when {
                upRect.contains(px, py) -> changeSystemPower(system, true)
                downRect.contains(px, py) -> changeSystemPower(system, false)
                else -> itemRects.forEachIndexed { i, rect ->
                    if (!rect.contains(px, py))
                        return@forEachIndexed

                    // Tap toggles: powered items switch off, off items on.
                    itemButtons.getOrNull(i)?.popupTogglePower()
                }
            }
        }

        fun draw(g: Graphics) {
            // Panel background + border (tooltip style)
            g.colour = Colour(0f, 0f, 0f, 0.85f)
            g.fillRect(x.f, y.f, width.f, height.f)
            g.colour = Colour(1f, 1f, 1f, 0.5f)
            g.drawRect(x.f, y.f, width.f, height.f)

            // +/- buttons from the iPad UI kit; dimmed at the limits
            val upImg = game.getImg(
                if (system.powerSelected < system.powerAvailable) "img/ipad/systemUI/button_tele_up_on.png"
                else "img/ipad/systemUI/button_tele_up_off.png"
            )
            val downImg = game.getImg(
                if (system.powerSelected > 0) "img/ipad/systemUI/button_tele_down_on.png"
                else "img/ipad/systemUI/button_tele_down_off.png"
            )
            upImg.draw(upRect.x.f, upRect.y.f, upRect.w.f, upRect.h.f)
            downImg.draw(downRect.x.f, downRect.y.f, downRect.w.f, downRect.h.f)

            // The system's own icon + power bars, scaled up 2x. The draw
            // call is offset so the icon (drawn from x-19,y-19) and the
            // upward-stacking bars land inside the row.
            g.pushTransform()
            g.translate(barX.f, barY.f)
            g.scale(barScale, barScale)
            system.drawIconAndPower(game, g, true, true, false, 19, barStack + 24)
            g.popTransform()

            // Per-item power toggles for weapons/drones
            itemRects.forEachIndexed { i, rect ->
                val item = itemButtons.getOrNull(i) ?: return@forEachIndexed
                val img = game.getImg(
                    if (item.isPowered) "img/ipad/systemUI/button_weaponpower_on.png"
                    else "img/ipad/systemUI/button_weaponpower_off.png"
                )
                img.draw(rect.x.f, rect.y.f, rect.w.f, rect.h.f)
            }
        }
    }
    private val hoveredCrew: MutableList<AbstractCrew> = ArrayList()
    private var skillsHoveredCrew: AbstractCrew? = null

    private val buttons = ArrayList<Button>()

    /**
     * The currently shown window. When a window is shown, the main UI is darkened, buttons are locked
     * and all input is sent to the window.
     */
    private var currentWindow: Window? = null
        set(value) {
            field = value
            value?.let { positionMenuWindow(it) }
        }

    /**
     * The currently-open pause or options menu.
     *
     * This is handled separately from all other windows, since it can
     * be opened on top of them.
     */
    private var pauseWindow: Window? = null
        set(value) {
            field = value
            value?.let { positionMenuWindow(it) }
        }

    private fun positionMenuWindow(window: Window) {
        val container = game.mainGame?.gameContainer ?: return
        val scale = window.renderScale
        val halfGrowX = (window.size.x * (scale - 1) / 2).roundToInt()
        val halfGrowY = (window.size.y * (scale - 1) / 2).roundToInt()
        window.position = window.windowCentreOffset + ConstPoint(
            (container.width - window.size.x) / 2 + halfGrowX,
            (container.height - window.size.y) / 2 + halfGrowY)
    }

    private val hullWarningLines = listOf(
        ConstPoint(361, 51),
        ConstPoint(393, 82),
        ConstPoint(482, 82)
    )
    private val hullWarning25 = WarningFlasher(game, ConstPoint(437, 100), "warning_hull_25", true, hullWarningLines, sound = "warning")
    private val hullWarning50 = WarningFlasher(game, ConstPoint(437, 100), "warning_hull_50", true, hullWarningLines, sound = "warning")
    private val hullWarning75 = WarningFlasher(game, ConstPoint(437, 100), "warning_hull_75", true, hullWarningLines, sound = "warning")

    private val oxygenWarningLines = listOf(
        ConstPoint(99, 137),
        ConstPoint(116, 170),
        ConstPoint(216, 170)
    )
    private val oxygenWarning = WarningFlasher(
        game, ConstPoint(167, 188),
        "warning_oxygen", true, oxygenWarningLines, alwaysOn = true, sound = "warning"
    )

    private val shieldsWarning = WarningFlasher(
        game, ConstPoint(204, 132), "warning_shields", true,
        listOf(
            ConstPoint(129, 93),
            ConstPoint(151, 114),
            ConstPoint(250, 114)
        ),
        sound = "warning"
    )

    // ShipStatus's looping intruder WarningMessage (0x00552c50): red, centred, drawn
    // 120px further left while an enemy ship is present (0x005579a0).
    private val intrudersWarning = WarningFlasher(game, ConstPoint(851, 270), "warning_intruders", true, underline = true, sound = "warning")

    private val sunWarning = WarningFlasher(
        game, ConstPoint(825, 64), "warning_solar_flare", false, alwaysOn = true
    )
    private val pulsarWarning = WarningFlasher(
        game, ConstPoint(825, 64), "warning_ion_pulse", false, alwaysOn = true
    )
    // SpaceStatus's environment message also reads ASB TARGET LOCKED! (0x005017f0), at the same spot.
    private val asbWarning = WarningFlasher(
        game, ConstPoint(825, 64), "warning_pds_locked", false, alwaysOn = true
    )

    // SpaceStatus's power surge message (0x005005d0): at its position (690, 72) plus (2, 12), with
    // WARNING!, looping while the flagship's surge is under 5 s away (BossShip::IncomingFire 0x00406140).
    private val powerSurgeWarning =
        WarningFlasher.atNative(game, 692, 84, "warning_power_surge", true, GlowColour.RED, flash = true, sound = "surgeWarning")

    // SystemControl's NOT ENOUGH POWER (0x004c9380, drawn by 0x004c7530): left-aligned 6 px in and 73 px
    // over the top reactor bar, flashing for 2.31 s after a power-up the reactor can't supply.
    private val noPowerWarning =
        WarningFlasher.atNative(game, 6, 0, "warning_no_power", false, GlowColour.WHITE, flash = true, centred = false)

    // SystemBox's REQUIRES SYSTEM UPGRADE (0x004dad30): the shields box, 5 px right of it and 111 px over its
    // power button, for 2 s after a click on the last bar of an odd level (0x004da730).
    private val shieldUpgradeWarning =
        WarningFlasher.atNative(game, 0, 0, "warning_needs_upgrade", false, GlowColour.WHITE, flash = false, centred = false)
    private var shieldBoxPos: IPoint? = null

    // WeaponControl's (0x00569b30) and DroneControl's (0x0054e9e0) messages, centred around a spot relative to
    // their box, for 2 s. The system-power one is re-texted for the reason (0x0054e080).
    private val noMissilesWarning =
        WarningFlasher.atNative(game, 0, 0, "warning_no_missiles", false, GlowColour.WHITE, flash = false)
    private val weaponPowerWarning =
        WarningFlasher.atNative(game, 0, 0, "warning_no_system_power", false, GlowColour.WHITE, flash = false)
    private val noDronesWarning =
        WarningFlasher.atNative(game, 0, 0, "warning_no_drones", false, GlowColour.WHITE, flash = false)
    private val noEnemyShipWarning =
        WarningFlasher.atNative(game, 0, 0, "warning_no_enemy_ship", false, GlowColour.WHITE, flash = false)
    private val dronePowerWarning =
        WarningFlasher.atNative(game, 0, 0, "warning_no_system_power", false, GlowColour.WHITE, flash = false)
    private var weaponBoxXForWarnings: Int? = null
    private var droneBoxXForWarnings: Int? = null

    // CommandGui's IN DANGER (0x00532090): centred over the ship button, for 2 s after a click on it while
    // it's disabled outside the tutorial (0x00535a20).
    private val inDangerWarning =
        WarningFlasher.atNative(game, 662, 6, "warning_in_danger", false, GlowColour.RED, flash = false)

    // FTLButton's ENGINES CRITICAL (0x00558690): a lined message from the button's spot plus (78, 86), looping
    // while the engines have no health left (0x00559650).
    private val enginesWarningLines = listOf(Point(0, 0), Point(0, 0), Point(0, 0))
    private val enginesWarning = WarningFlasher(game, ConstPoint(0, 0), "warning_engine", true, enginesWarningLines, sound = "warning")
    private var jumpButtonRef: Buttons.JumpButton? = null
    internal val jumpButton: Buttons.JumpButton? get() = jumpButtonRef
    private var shipButtonRef: Buttons.ShipButton? = null

    /** The HUD warnings by name, for the parity checks. */
    internal val diagnosticWarnings: Map<String, WarningFlasher>
        get() = mapOf(
            "noPower" to noPowerWarning, "shieldUpgrade" to shieldUpgradeWarning,
            "noMissiles" to noMissilesWarning, "weaponPower" to weaponPowerWarning,
            "noDrones" to noDronesWarning, "noEnemyShip" to noEnemyShipWarning, "dronePower" to dronePowerWarning,
            "inDanger" to inDangerWarning, "engines" to enginesWarning, "powerSurge" to powerSurgeWarning
        )

    /** True while the FTL button shows its red base: the engine warning is running and in its bright half. */
    val ftlButtonRed: Boolean get() = enginesWarning.isFlashingHigh

    // SpaceStatus's hazard icons: their hover rectangles (0x00501df0) show these tooltips.
    private val hazardTooltips = HashMap<String, FixedDelayedTooltip>()
    private val dangerFont by lazy { xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 52) }

    private val hullTooltip = FixedStandardTooltip(game, "tooltip_hull")
    private val scrapTooltip = FixedStandardTooltip(game, "tooltip_scrapCount")
    private val fuelTooltip = FixedStandardTooltip(game, "tooltip_fuelCount")
    private val missileTooltip = FixedStandardTooltip(game, "tooltip_missileCount")
    private val droneTooltip = FixedStandardTooltip(game, "tooltip_droneCount")
    private val shieldTooltip = FixedStandardTooltip(game, "tooltip_shieldDisplay")
    private val evasionTooltip = FixedStandardTooltip(game, "tooltip_evadeDisplay")
    private val oxygenTooltip = FixedStandardTooltip(game, "tooltip_oxygenDisplay")

    private var lastHealth: Int = -1

    val isWindowOpen: Boolean get() = currentWindow != null || pauseWindow != null

    /**
     * CommandGui::RenderStatic (0x00535070) tints the whole scene by 0.15 while a
     * full-focus window is open (not the event ChoiceBox), then lifts the tint for
     * ShipStatus unless the menu, options, game over or leave-crew dialog is open.
     */
    val sceneTint: Colour
        get() = if (pauseWindow != null || (currentWindow != null && currentWindow !is DialogueWindow))
            FOCUS_TINT else Colour.white
    val statusTint: Colour
        get() = if (pauseWindow != null || currentWindow is GameOverWindow || currentWindow is ConfirmationWindow)
            FOCUS_TINT else Colour.white
    val tutorialSelectedCrewCount: Int get() = selectedCrew.size
    val tutorialMapOpen: Boolean get() = currentWindow is JumpWindow
    val tutorialJumpWindow: JumpWindow? get() = currentWindow as? JumpWindow
    val tutorialShipWindow: ShipWindow? get() = currentWindow as? ShipWindow
    private val tutorialVisuals get() = game.mainGame?.takeIf { it.currentState === game }?.tutorialAdapter?.visuals
    val tutorialWeaponFlashSlot: Int get() = tutorialVisuals?.weaponSlot ?: -1
    fun tutorialWeaponFlashAlpha(slot: Int): Float = tutorialVisuals?.weaponAlpha(slot) ?: 0f
    val tutorialDialogueOpen: Boolean get() = currentWindow is DialogueWindow
    val diagnosticDialogue: DialogueWindow? get() = currentWindow as? DialogueWindow
    val tutorialOverlayOpen: Boolean get() = pauseWindow != null
    val pendingUpgradeActions: Set<ftl.browser.BrowserAchievementProgress.Action>
        get() = (currentWindow as? ShipWindow)?.pendingUpgradeActions ?: emptySet()
    private val diagnosticActiveWindow: Window? get() = (pauseWindow ?: currentWindow).let {
        (it as? OptionsWindow)?.diagnosticActiveWindow ?: it
    }
    val diagnosticNotice: String get() = ((pauseWindow ?: currentWindow) as? OptionsWindow)?.diagnosticNotice ?: "null"
    val diagnosticWindow: String get() = diagnosticActiveWindow?.javaClass?.simpleName ?: "game"
    val diagnosticPauseWindow: Window? get() = pauseWindow
    val diagnosticWindowBounds: String get() = diagnosticActiveWindow?.let {
        "{\"x\":${it.position.x},\"y\":${it.position.y},\"width\":${it.size.x},\"height\":${it.size.y},\"scale\":${it.renderScale}}"
    } ?: "null"
    val diagnosticConfirmation: String get() = ((pauseWindow ?: currentWindow) as? ConfirmationWindow)?.diagnosticState ?: "null"
    val diagnosticArmaments: String get() {
        fun bounds(button: Button) = "{\"x\":${button.pos.x},\"y\":${button.pos.y},\"width\":${button.size.x},\"height\":${button.size.y}}"
        val autofire = buttons.filterIsInstance<AutofireButton>().firstOrNull()
        val slots = systemsBarButtons.filterIsInstance<WeaponDroneButton>()
            .filter { it.ownerSystem === ship.weapons }.joinToString(",") { bounds(it) }
        return "{\"autofire\":${autofire?.let { bounds(it) } ?: "null"},\"tooltip\":${ftl.browser.BrowserDebug.quote(if (autofire?.hovered == true) autofire.tooltipText else null)},\"slots\":[$slots]}"
    }
    val confirmationWindow get() = (pauseWindow ?: currentWindow) as? ConfirmationWindow
    val gameOverWindow get() = currentWindow as? GameOverWindow
    val strandedPlayerCrewCount: Int get() = game.enemy?.crew?.count {
        it is LivingCrew && it.ownerShip === ship && it.health > 0f
    } ?: 0
    // ArmamentControl's drag (+0x70 index, +0x78 dragging, 0x56ef40/0x56f750/0x56fab0): the
    // weapon or drone slot being dragged, the system it belongs to, where the press was, and
    // the anchor the dragged box is drawn relative to (box position + mouse - anchor).
    private var weaponDragSource: Int? = null
    private var weaponDragSystem: MainSystem? = null
    private var weaponDragStart = ConstPoint.ZERO
    private var weaponDragAnchor = ConstPoint.ZERO
    private var weaponDragMoved = false
    private var targetingModifier = false

    private fun armamentButtonAt(x: Int, y: Int): WeaponDroneButton? {
        val (bx, by) = toBarSpace(x, y)
        return systemsBarButtons.filterIsInstance<WeaponDroneButton>().firstOrNull {
            it.ownerSystem != null && (it.ownerSystem === ship.weapons || it.ownerSystem === ship.drones) && it.contains(bx, by)
        }
    }

    private fun armamentButton(system: MainSystem?, slot: Int): WeaponDroneButton? =
        systemsBarButtons.filterIsInstance<WeaponDroneButton>().firstOrNull { it.ownerSystem === system && it.slotNumber == slot }

    fun cancelDrag() { weaponDragSource = null; weaponDragSystem = null; weaponDragMoved = false }

    /**
     * ArmamentControl's mouse move (0x56f750): the drag starts once the mouse is 7px (Point::Distance)
     * from the press, and while dragging the box swaps places at once with any other filled slot the
     * mouse enters; empty slots are never drop targets. The anchor moves with the swap so the box
     * keeps following the mouse.
     */
    private fun updateArmamentDrag(x: Int, y: Int) {
        val source = weaponDragSource ?: return
        val system = weaponDragSystem ?: return
        if (!weaponDragMoved) {
            val dx = x - weaponDragStart.x
            val dy = y - weaponDragStart.y
            if (dx * dx + dy * dy < 49)
                return
            weaponDragMoved = true
        }
        val target = armamentButtonAt(x, y) ?: return
        if (target.ownerSystem !== system || target.slotNumber == source || target.empty)
            return
        val from = armamentButton(system, source) ?: return
        val fromPos = from.pos
        val targetPos = target.pos
        val targetSlot = target.slotNumber
        val swapped = when (system) {
            is Weapons -> system.swapSlots(source, targetSlot)
            is Drones -> system.swapSlots(source, targetSlot)
            else -> false
        }
        if (!swapped)
            return
        weaponDragAnchor = ConstPoint(
            weaponDragAnchor.x + ((targetPos.x - fromPos.x) * systemsBarScale).toInt(),
            weaponDragAnchor.y + ((targetPos.y - fromPos.y) * systemsBarScale).toInt()
        )
        weaponDragSource = targetSlot
    }

    /** True while [button] is the armament box being dragged (drawn under the mouse, not in its slot). */
    private fun isDraggedArmament(button: Button): Boolean =
        weaponDragMoved && button is WeaponDroneButton && button.ownerSystem === weaponDragSystem &&
            button.slotNumber == weaponDragSource

    fun dronesSlotsChanged() {
        updateButtons()
    }

    fun weaponSlotsChanged(from: Int, to: Int) {
        val armed = targetingSelectedWeapon
        if (armed == from || armed == to) {
            val replacement = if (armed == from) to else from
            targetingSelectedWeapon = replacement
            if (beamTargeting != null) beamTargeting!!.weaponNumber = replacement
            else if (game.clickEvent === selectWeaponClickEvent) weaponHotkeyPressed(replacement, false, targetingModifier)
        }
        updateButtons()
    }

    private val lastResources = ResourceSet()
    private val resourceDeltaAnimations = ArrayList<ResourceDeltaText>()

    private var crewSelectionRectangle: Pair<ConstPoint, Point>? = null
    private val isCrewSelectionPoint: Boolean
        get() = crewSelectionRectangle?.let { it.first.distToSq(it.second) < SELECTION_BOX_SIZE } ?: false

    // Non-null when the player is aiming a beam weapon
    private var beamTargeting: SelectedTarget.BeamAim? = null
        set(value) {
            field = value

            // Show the aiming-in-progress beam on the enemy ship
            ship.weapons!!.selectedTargets.beamAiming = value
        }
    private val beamTargetingStartPos = Point(0, 0)

    // Set by render
    private var width: Int = 500
    private var height: Int = 500

    // The last position of the cursor. Try to avoid using this if possible, there's
    // usually a better way to do stuff with a button.
    private val mousePos = Point(0, 0)
    private val reactorTooltip = FixedDelayedTooltip(game, "tooltip_powerTotal")
    private val crewTooltip = CrewHoverTooltip()

    // The time remaining on the insufficient scrap flash animation
    private var insufficientScrapTimer: Float = 0f

    // If true, buttons that are tightly intertwined with the rest of the UI
    // (for example, the weapon and drone buttons) will be re-populated.
    private var updatingButtons = false

    // The longest time it takes to charge a weapon - this is used for drawing
    // the weapon charge bars.
    private var maxWeaponChargeTime: Float = 1f

    // The position of the weapons box
    val weaponBoxY get() = height - 113

    // Whether or not we've previously seen a store UI at this sector.
    // This is used to auto-open the store when the dialogue is finished.
    private var storeAlreadyOpened = false

    private val crewX: Int = 10
    private var crewBaseY: Int = 0 // Set while rendering
    private var lastCrewCount: Int = 0

    // Set when the player has selected the AUTOFIRE button: the next
    // weapon taps toggle that weapon's autofire (touch-friendly flow).

    // These time the message that shows up for a second when you save/restore
    // your crew positions.
    private var saveCrewPosMessageTimer: Float = 0f
    private var saveCrewPosMessageText: String = ""

    /**
     * If the user is selecting a room to teleport to/from, this is non-null.
     * True if sending crew to an enemy ship, false if receiving.
     */
    val teleportMode: Boolean? get() = (game.clickEvent as? TeleportRoomListener)?.send

    val isSelectingHackingTarget: Boolean get() = game.clickEvent is HackingRoomListener
    val isSelectingMindControlTarget: Boolean get() = game.clickEvent is MindControlRoomListener

    /**
     * The touch room-picker targeting mode (iPad-style): while the
     * teleporter (send or recall), hacking or mind-control picker is
     * waiting for a room tap, the game auto-pauses, the player's ship is
     * drawn shrunken and the enemy ship box returns to full size - the
     * same treatment as weapon targeting, since all of these pick rooms
     * on the ENEMY ship. The iPad port auto-paused for these too; the
     * banner art (img/pause_teleport_leave.png, pause_teleport_arrive.png,
     * pause_mind.png, pause_hacking.png) ships in ftl.dat.
     */
    val systemTargetingMode: Boolean
        get() = PlatformSpecific.INSTANCE.isTouchUi &&
                (teleportMode != null || isSelectingHackingTarget ||
                        isSelectingMindControlTarget)

    init {
        updateButtons()
    }

    fun updateButtons() {
        buttons.clear()
        systemsBarButtons.clear()
        computeSystemsBarScale()
        updatingButtons = true

        // On touch layouts the top-bar buttons (JUMP / ship / store /
        // menu) are shifted right and scaled up, anchored at each
        // button's position, so they're easier to tap. While an enemy
        // ship is displayed the row has to end left of the enemy box
        // (its art edge is at x~911 on touch), so the scale is reduced
        // from 1.25 until the whole row fits. Vanilla stores never
        // coexist with an enemy (the store events spawn no ships, quests
        // skip store beacons, and the fleet overtaking a beacon strips
        // its store), so store rows get the full scale - only a modded
        // store-with-fight compresses.
        var startX = 531
        var buttonScale = 1f
        if (PlatformSpecific.INSTANCE.isTouchUi) {
            startX = 531 + 40
            val hasStore = game.currentBeacon?.hasStore == true
            val widths = if (hasStore) 74 + 60 + 88 + 41 else 74 + 60 + 41
            val gaps = if (hasStore) 27 + 17 + 17 else 27 + 17
            val rightBound = if (game.enemy != null) 905 else Int.MAX_VALUE
            buttonScale = min(
                1.25f,
                (rightBound - startX - gaps).toFloat() / widths
            ).coerceAtLeast(1f)
        }

        // Vanilla increments: the gap after each button is 101-74, 77-60
        // and 105-88; with a scale factor the WIDTH grows but the gaps
        // stay vanilla.
        fun step(width: Int, gap: Int) = (width * buttonScale).roundToInt() + gap

        var nextPos = ConstPoint(startX, 29)

        val jump = Buttons.JumpButton(nextPos, ship, game, { ftlButtonRed }) {
            openJumpMap()
        }.apply { renderScale = buttonScale }
        jumpButtonRef = jump
        nextPos += ConstPoint(step(74, 27), 0)

        val ship = Buttons.ShipButton(nextPos, game) {
            showShipWindow(ShipWindow.Tab.UPGRADES)
        }.apply { renderScale = buttonScale }
        shipButtonRef = ship
        nextPos += ConstPoint(step(60, 17), 0)

        if (game.currentBeacon?.hasStore == true) {
            val store = Buttons.StoreButton(nextPos, game) {
                showStoreWindow()
            }.apply { renderScale = buttonScale }
            nextPos += ConstPoint(step(88, 17), 0)
            buttons += store
        }

        val settings = SimpleButton(
            game, nextPos, ConstPoint(41, 41), ConstPoint(7, 7),
            game.getImg("img/statusUI/top_optionswrench_on.png"),
            game.getImg("img/statusUI/top_optionswrench_select2.png"),
            FixedDelayedTooltip(game, "open_options")
        ) {
            showPauseWindow()
        }
        settings.renderScale = buttonScale

        buttons += jump
        buttons += ship
        buttons += settings
    }

    /**
     * The touch weapon-targeting mode (iPad-style): while a weapon is
     * armed and waiting for a room to be clicked (or a beam is being
     * drawn), the game auto-pauses, the player's ship is drawn shrunken
     * (see InGameState.getPlayerShipRenderScale) and the enemy ship box
     * returns to full size, making its rooms easier to tap. Picking a
     * target (or cancelling) returns everything to normal.
     */
    var weaponTargetingMode: Boolean = false
        private set

    private fun updateWeaponTargetingMode() {
        if (!PlatformSpecific.INSTANCE.isTouchUi) {
            weaponTargetingMode = false
            return
        }

        // Only while a weapon is armed and waiting for a target - the
        // teleporter/hacking/mind-control room pickers get the same
        // treatment via systemTargetingMode. The null guard matters:
        // clickEvent is null most of the time, and null === null would
        // engage the mode permanently.
        weaponTargetingMode = beamTargeting != null ||
                (selectWeaponClickEvent != null &&
                        game.clickEvent === selectWeaponClickEvent)
    }

    /**
     * Convert a screen-space position to the player ship's render space.
     * While the touch weapon-targeting mode is active the ship is drawn
     * shrunken (InGameState.getPlayerShipRenderScale), so the plain
     * subtraction of [playerShipPosition] is only right at scale 1.
     */
    private fun screenToShipRender(x: Int, y: Int, playerShipPosition: IPoint): Point {
        val s = game.playerShipRenderScale
        return Point(
            ((x - playerShipPosition.x) / s).roundToInt(),
            ((y - playerShipPosition.y) / s).roundToInt()
        )
    }

    fun mouseClick(button: Int, x: Int, y: Int, playerShipPosition: IPoint) {
        pauseWindow?.let { win ->
            win.mouseClick(button, x, y)
            return
        }

        currentWindow?.let { win ->
            win.mouseClick(button, x, y)
            return
        }

        // When we're in beam targeting mode, block other mouse input.
        if (beamTargeting != null) {
            if (button == Input.MOUSE_LEFT_BUTTON) {
                targetBeamWeapon()
            } else if (button == Input.MOUSE_RIGHT_BUTTON) {
                beamTargeting = null
            }
            return
        }

        // The touch power popup floats above the systems strip and is modal
        // while open: every tap is consumed by it - taps on the +/- or item
        // toggles act, taps anywhere else close it. This must run BEFORE the
        // button loops below, otherwise the strip buttons underneath the
        // panel would swallow its taps.
        if (popupClick(button, x, y))
            return

        // ArmamentControl's press (0x56ef40): only a filled weapon or drone slot can be dragged.
        if (button == Input.MOUSE_LEFT_BUTTON) {
            val slot = armamentButtonAt(x, y)
            if (slot != null && !slot.empty) {
                weaponDragSource = slot.slotNumber
                weaponDragSystem = slot.ownerSystem
                weaponDragStart = ConstPoint(x, y)
                weaponDragAnchor = ConstPoint(x, y)
                weaponDragMoved = false
                return
            }
        }

        // System-strip buttons are in the scaled bar space on touch; hit
        // them first (they're drawn on top of the strip art).
        for (btn in systemsBarButtons) {
            val (bx, by) = toBarSpace(x, y)
            if (btn.mouseDown(button, bx, by)) return
        }

        // CommandGui's click (0x535a20) opens the ship screen from its button only while no weapon is armed
        // (CommandGui +0x2fe4); otherwise the click carries on as an ordinary click.
        val weaponArmed = selectWeaponClickEvent != null && game.clickEvent === selectWeaponClickEvent
        for (btn in buttons) {
            if (button == Input.MOUSE_LEFT_BUTTON && btn === shipButtonRef && weaponArmed)
                continue
            if (button == Input.MOUSE_LEFT_BUTTON && btn === shipButtonRef && btn.contains(x, y) &&
                game.isInDanger && game.mainGame?.isTutorialRunning != true
            ) {
                inDangerWarning.startFor(WARNING_TIME)
            }
            if (btn.mouseDown(button, x, y)) return
        }

        // Check if we're clicking on a door
        if (button == Input.MOUSE_LEFT_BUTTON) {
            val doorPos = screenToShipRender(x, y, playerShipPosition)
            for (door in ship.doors) {
                val hit = door.click(doorPos.x, doorPos.y)
                if (hit)
                    return
            }
        }

        // Select players
        if (button == Input.MOUSE_LEFT_BUTTON) {
            crewSelectionRectangle = Pair(ConstPoint(x, y), Point(x, y))
        }

        // Move players if they're right-clicking somewhere
        val shipMousePos = screenToShipRender(x, y, playerShipPosition)

        val roomPoint = Point(shipMousePos)
        roomPoint.divideFloor(ROOM_SIZE)
        roomPoint -= ship.offset
        for (room in ship.rooms) {
            if (!room.containsAbsolute(roomPoint))
                continue

            if (button == Input.MOUSE_RIGHT_BUTTON) {
                for (crew in selectedCrew) {
                    // Skip mind-controlled crew that remained selected.
                    if (!crew.playerControllable)
                        continue

                    // Exclude crew that are boarding an enemy ship, as
                    // this could cause a crash.
                    if (crew.room.ship == ship)
                        crew.setTargetRoom(room)
                }

                return
            }
        }
    }

    fun mouseUp(button: Int, x: Int, y: Int, playerShipPosition: IPoint) {
        weaponDragSource?.let {
            if (button != Input.MOUSE_LEFT_BUTTON) return@let
            updateArmamentDrag(x, y)
            val source = weaponDragSource ?: return
            val moved = weaponDragMoved
            val system = weaponDragSystem
            cancelDrag()
            // ArmamentControl's release (0x56fab0): the swaps already happened while dragging;
            // a release without a drag selects the weapon or powers the drone.
            if (!moved) {
                if (system is Drones) changeDronePower(source, true)
                else weaponHotkeyPressed(source, false, isCtrlDown())
            }
            return
        }
        pauseWindow?.let { win ->
            win.mouseReleased(button, x, y)
            return
        }

        currentWindow?.let { win ->
            win.mouseReleased(button, x, y)
            return
        }

        // Issue #11: releasing a drawn beam path fires the beam, like
        // vanilla's auto-fire behaviour. The port previously required a
        // second press after drawing, which made beams awkward to fire
        // from touch input (draw, lift, tap again).
        val beam = beamTargeting
        if (beam != null && beam.visible && beam.hitRooms.isNotEmpty() &&
            button == Input.MOUSE_LEFT_BUTTON
        ) {
            targetBeamWeapon()
            return
        }

        // Touch room-selection mode: a tap on a room sends the selected
        // crew there and unpauses. Taps that don't land on a room fall
        // through to the normal select/deselect handling below (tap a
        // crewmember to re-select and stay in the mode, tap empty space
        // to deselect and leave it).
        if (roomSelectionMode && button == Input.MOUSE_LEFT_BUTTON) {
            if (handleRoomSelectionTap(x, y, playerShipPosition)) {
                // The move action is complete: deselect and unpause.
                selectedCrew.clear()
                roomSelectionMode = false
                crewSelectionRectangle = null
                return
            }
        }

        crewSelectionRectangle?.let {
            if (button != Input.MOUSE_LEFT_BUTTON) return@let

            updateHoveredCrew(x, y, playerShipPosition)

            // CrewControl's mouse up (0x502c00): shift keeps the current selection and adds to it.
            if (!isShiftDown())
                selectedCrew.clear()
            for (crew in hoveredCrew) {
                if (crew !in selectedCrew)
                    selectedCrew += crew
            }

            crewSelectionRectangle = null
        }
    }

    /**
     * Handle a tap in room-selection mode: if it lands on a room (on
     * either ship), send every selected crewmember that can walk there
     * and return true. Walking only - boarding is done through the
     * teleporter UI, as on desktop.
     */
    private fun handleRoomSelectionTap(x: Int, y: Int, playerShipPosition: IPoint): Boolean {
        // Rooms on our own ship
        val shipMousePos = Point(x, y)
        shipMousePos -= playerShipPosition

        val roomPoint = Point(shipMousePos)
        roomPoint.divideFloor(ROOM_SIZE)
        roomPoint -= ship.offset
        for (room in ship.rooms) {
            if (!room.containsAbsolute(roomPoint))
                continue

            for (crew in selectedCrew) {
                if (!crew.playerControllable)
                    continue

                // Crew boarding the enemy ship can't walk home.
                if (crew.room.ship == ship)
                    crew.setTargetRoom(room)
            }

            return true
        }

        // Rooms on the enemy ship - these move crew already boarding.
        val enemy = game.enemy ?: return false

        val enemyPoint = Point(x, y)
        game.convertScreenToEnemyShip(enemyPoint)
        enemy.screenPosToShipPos(enemyPoint)
        val roomPos = enemy.shipToRoomPos(enemyPoint) ?: return false

        enemyRoomRightClicked(roomPos.room, enemy)
        return true
    }

    fun mouseScroll(change: Int) {
        pauseWindow?.let { win ->
            win.mouseScroll(change)
            return
        }

        currentWindow?.let { win ->
            win.mouseScroll(change)
            return
        }
    }

    fun crewScreenPos(crew: AbstractCrew, playerShipPosition: IPoint): IPoint {
        if (crew.room.ship == ship) {
            // Scale the crew's position too - the touch weapon-targeting
            // mode draws the ship shrunken (InGameState
            // .getPlayerShipRenderScale).
            val s = game.playerShipRenderScale
            return Point(
                (playerShipPosition.x + s * crew.screenX).roundToInt(),
                (playerShipPosition.y + s * crew.screenY).roundToInt()
            )
        }

        // The crewmember must be on the enemy ship, convert their
        // position over (the touch layout draws the enemy box scaled).
        return game.enemyShipRenderToScreen(crew.screenX, crew.screenY)
    }

    fun isCrewHovered(crew: AbstractCrew, mouseX: Int, mouseY: Int, crewPos: IPoint): Boolean {
        // Check if the crewmember's box is being hovered
        val index = game.playerCrew.indexOf(crew)
        if (index != -1) {
            val boxY = crewBaseY + index * CREW_BOX_SPACING
            if (mouseX in crewX..crewX + CREW_BOX_WIDTH && mouseY in boxY..boxY + CREW_BOX_HEIGHT) {
                skillsHoveredCrew = crew
                return true
            }
        }

        // Check if the physical crewmember is being hovered
        return mouseX in crewPos.x..crewPos.x + crew.icon.width &&
                mouseY in crewPos.y..crewPos.y + crew.icon.height
    }

    // Called by SlickGame, used for controlling boarders.
    fun enemyRoomRightClicked(room: Room, enemyShip: Ship) {
        for (crew in selectedCrew) {
            // Skip mind-controlled crew that remained selected.
            if (!crew.playerControllable)
                continue

            if (crew.room.ship == enemyShip)
                crew.setTargetRoom(room)
        }
    }

    fun weaponHotkeyPressed(id: Int, shiftPressed: Boolean, ctrlPressed: Boolean = false) {
        if (pauseWindow != null) return

        if (currentWindow != null) return

        // Note that modded ships might not have a weapons system
        val weapons = ship.weapons ?: return

        // Block interactions with hacked weapons
        if (weapons.isHackActive)
            return

        // If the user was previously targeting a beam, cancel that.
        beamTargeting = null

        val weapon = ship.hardpoints.getOrNull(id)?.weapon ?: return

        if (shiftPressed) {
            // Holding shift turns a weapon off
            if (weapon.isPowered && weapons.setWeaponPower(weapon, false)) {
                powerDownSound.play()
            }
            return
        }

        if (!weapon.isPowered) {
            // A weapon without missiles is accepted by the power manager but switched straight back off.
            if (weapons.setWeaponPower(weapon, true) && weapon.isPowered) {
                powerUpSound.play()
            } else {
                powerUpFailSound.play()
                if (!weapon.hasEnoughMissiles) {
                    noMissilesWarning.startFor(WARNING_TIME)
                } else {
                    val required = weapon.type.power - weapons.powerManager.getForcedPower(id)
                    armamentPowerFailed(weapons, required, weaponPowerWarning)
                }
            }
            return
        }

        weapons.selectedTargets.unTarget(id)

        targetingSelectedWeapon = id
        targetingModifier = ctrlPressed
        selectWeaponClickEvent = RoomClickListener { room, gc ->
            // Prevent the player from shooting at the enemy ship once
            // their crew is dead.
            if (room.ship != ship && room.ship != game.getEnemyOf(ship)) {
                return@RoomClickListener
            }

            if (room.ship == ship && !weapon.canTargetOwnShip) {
                return@RoomClickListener
            }

            // Desktop FTL applies the modifier when the aim is assigned.
            weapon.autofireOverride = if (ctrlPressed || isCtrlDown()) !weapons.autofire else null

            if (weapon is IRoomTargetingWeapon) {
                weapons.selectedTargets.targetRoom(id, room)
            } else if (weapon is BeamBlueprint.BeamInstance) {
                val mousePos = ConstPoint(gc.input.mouseX, gc.input.mouseY)
                val shipPos = Point(mousePos.x, mousePos.y)
                game.convertScreenToEnemyShip(shipPos)
                beamTargeting = SelectedTarget.BeamAim(weapon, id, game.enemy, shipPos)
                beamTargetingStartPos.set(mousePos)
            }
        }
        game.clickEvent = selectWeaponClickEvent
    }

    /**
     * The ship has jumped: cancel any pending weapon targeting (GitHub
     * issue #55). The armed weapon's click event, the aimed slot and any
     * beam being drawn all refer to the fight we just left - without this
     * the targeting UI would stay stuck on after arriving.
     */
    fun onJump() {
        targetingSelectedWeapon = null
        selectWeaponClickEvent = null
        beamTargeting = null
        game.clickEvent = null
    }

    /** Desktop global autofire changes immediately; enabling it clears pending aim selection. */
    fun toggleAutofire() {
        if (pauseWindow != null || currentWindow != null) return
        val weapons = ship.weapons ?: return
        weapons.toggleAutofire()
        if (weapons.autofire) onJump()
        if (weapons.autofire) autofireOnSound.play() else autofireOffSound.play()
    }

    private fun isCtrlDown(): Boolean = isTargetingModifierDown()

    /**
     * Will the weapon being aimed fire by itself once it has a target? That is the
     * ship's autofire setting reversed by the modifier, as at the click which sets the
     * aim (WeaponControl::OnLoop 0x56c1a0 keeps it in each weapon box, 0x56bd40 applies it).
     * The cursor, the weapon box and the beam being drawn show it before the click.
     */
    fun isAimingAutofire(): Boolean {
        val weapons = ship.weapons ?: return false
        return weapons.autofire != (targetingModifier || isCtrlDown())
    }

    private fun isShiftDown(): Boolean {
        val input = game.input ?: return false
        return input.isKeyDown(Input.KEY_LSHIFT) || input.isKeyDown(Input.KEY_RSHIFT)
    }

    fun isTargetingModifierDown(): Boolean {
        val input = game.input ?: return false
        val action = game.hotkeyManager.byID[VanillaHotkeys.WEAPON_AUTOFIRE_TARGET] ?: return false
        val binding = game.reverseHotkeyBindings[action] ?: return false
        // Treat the two physical Ctrl keys alike only for the default Ctrl
        // binding. A remapped modifier must use its configured physical key.
        return input.isKeyDown(binding.keyID) ||
            (binding.keyID == Input.KEY_LCTRL && input.isKeyDown(Input.KEY_RCTRL)) ||
            (binding.keyID == Input.KEY_RCTRL && input.isKeyDown(Input.KEY_LCTRL))
    }

    fun droneHotkeyPressed(slot: Int, powerDown: Boolean) {
        if (pauseWindow != null || currentWindow != null) return
        changeDronePower(slot, !powerDown)
    }

    private fun changeDronePower(slot: Int, powered: Boolean) {
        val drones = ship.drones ?: return
        val info = drones.drones.getOrNull(slot) ?: return
        if (drones.isHackActive && info.instance != null) return
        val wasPowered = info.instance?.isPowered == true
        val accepted = drones.setDronePower(slot, powered)
        val nowPowered = info.instance?.isPowered == true
        when {
            wasPowered && !nowPowered -> powerDownSound.play()
            !wasPowered && nowPowered -> powerUpSound.play()
            !wasPowered && powered -> {
                powerUpFailSound.play()
                dronePowerFailed(drones, slot, info)
            }
        }
        ftl.browser.BrowserDebug.record("drone", "power.request",
            "{\"slot\":$slot,\"type\":${ftl.browser.BrowserDebug.quote(info.type.name)},\"requested\":$powered,\"accepted\":$accepted,\"powered\":$nowPowered}")
    }

    fun systemPowerHotkeyPressed(type: Class<*>, powerUp: Boolean) {
        for (sys in ship.mainSystems) {
            if (sys.javaClass != type) continue

            changeSystemPower(sys, powerUp)
        }
    }

    private fun changeSystemPower(sys: MainSystem, increasePower: Boolean) {
        val prevPower = sys.powerSelected

        if (increasePower) {
            sys.increasePower()
        } else {
            sys.decreasePower()
        }

        if (sys.powerSelected > prevPower) {
            powerUpSound.play()
        } else if (sys.powerSelected < prevPower) {
            powerDownSound.play()
        } else if (increasePower) {
            powerUpFailSound.play()
            // PowerManager's failed power-up: the system had room for the step, so the reactor was short.
            val step = if (sys is Shields) 2 else 1
            if (!sys.isPowerLocked && prevPower + step <= sys.undamagedEnergy)
                noPowerWarning.startFor(NO_POWER_TIME)
        }
    }

    private fun updateBeamTargeting(mouseX: Int, mouseY: Int) {
        val beam = beamTargeting ?: return

        val point = Point(beamTargetingStartPos)

        val delta = ConstPoint(mouseX, mouseY) - point

        // If the mouse is too close to where the beam starts, no beam is drawn.
        beam.visible = delta.distToSq(ConstPoint.ZERO) > MIN_BEAM_LENGTH * MIN_BEAM_LENGTH
        if (!beam.visible)
            return

        beam.angle = atan2(delta.y.toDouble(), delta.x.toDouble()).toFloat()

        beam.updateHitRooms()
    }

    private fun targetBeamWeapon() {
        val beam = beamTargeting ?: error("Called targetBeamWeapon without beam")
        beamTargeting = null

        val weaponIndex = ship.hardpoints.indexOfFirst { it.weapon == beam.weapon }
        val weapons = ship.weapons!!
        beam.weapon.autofireOverride = if (targetingModifier || isCtrlDown()) !weapons.autofire else null
        ship.weapons!!.selectedTargets.targetBeam(weaponIndex, beam)
    }

    // Dispatch actions from the UI - this is only called when the game is not paused, so dispatch
    // everything from here so a player can cancel their actions if they remain paused.
    fun update() {
        ship.weapons?.selectedTargets?.fireChargedWeapons()
    }

    fun updateAlways(dt: Float) {
        // Always called, whether or not the player is paused.
        // This is therefore only to be used for UI stuff.
        doorCursorTime += dt
        (currentWindow as? DialogueWindow)?.updateChoiceClock(dt)
        tutorialVisuals?.let { visuals ->
            visuals.update(dt)
            val slot = visuals.weaponSlot
            // Native ArmamentBox updates the shared tracker before its empty
            // box return; only a slot beyond the displayed capacity is absent.
            if (slot in 0 until (ship.weaponSlots ?: 0))
                visuals.updateWeapon(dt)
        }
        insufficientScrapTimer -= dt
        if (insufficientScrapTimer < 0)
            insufficientScrapTimer = 0f

        // The resource number animations run while paused
        for (anim in resourceDeltaAnimations) {
            anim.update(dt)
        }
        resourceDeltaAnimations.removeIf { it.finished }
    }

    private val deferredArmamentBoxes = ArrayList<() -> Unit>()

    /** How many [systemsBarButtons] belong to the systems themselves (before weapons/drones). */
    private var systemButtonCount = 0

    fun render(gc: GameContainer, g: Graphics) {
        width = gc.width
        height = gc.height

        // Update the buttons if crew have been added or removed, as this
        // moves around the save/load position buttons.
        // We have to do this here since we can't safely call updateButtons
        // mid-way through drawing.
        if (lastCrewCount != game.playerCrew.size) {
            updateButtons()
            lastCrewCount = game.playerCrew.size
        }

        // The subsystems panel pops responsively (flush left vs right of
        // the reactor column); when it moves, its power buttons must be
        // rebuilt at the new spot. This has to happen BEFORE any drawing:
        // updateButtons() clears BOTH button lists, and
        // drawSystems/drawSubSystems only repopulate them while
        // updatingButtons is true (ie during this same frame).
        val (subBoxX, subBoxY) = computeSubSysBox(gc)
        if (subBoxX != lastSubSysBoxX || subBoxY != lastSubSysBoxY) {
            lastSubSysBoxX = subBoxX
            lastSubSysBoxY = subBoxY
            updateButtons()
        }

        drawTopBar(g)

        // The systems strip draws in its own coordinate space, anchored to
        // the bottom-left, scaled up on touch so its buttons are easier to
        // tap. Input for its buttons is mapped back through toBarSpace.
        g.pushTransform()
        g.translate(0f, height * (1 - systemsBarScale))
        g.scale(systemsBarScale, systemsBarScale)
        drawSystems(g)
        // CommandGui::RenderStatic draws SystemControl before CombatControl, so the
        // weapon and drone panels' glow lies over the system bars, under their slots.
        var draggedArmament: Button? = null
        for ((i, button) in systemsBarButtons.withIndex()) {
            if (i == systemButtonCount) {
                for (box in deferredArmamentBoxes) box()
                deferredArmamentBoxes.clear()
            }
            // ArmamentControl::OnRender (0x56fb70) leaves the dragged box's slot empty, and
            // RenderDragging (0x56ecf0) draws the box at its slot moved by mouse - anchor.
            if (isDraggedArmament(button)) {
                draggedArmament = button
                continue
            }
            button.draw(g)
        }
        for (box in deferredArmamentBoxes) box()
        deferredArmamentBoxes.clear()
        draggedArmament?.let {
            g.pushTransform()
            g.translate((mousePos.x - weaponDragAnchor.x) / systemsBarScale, (mousePos.y - weaponDragAnchor.y) / systemsBarScale)
            it.draw(g)
            g.popTransform()
        }
        drawBarWarnings(g)
        g.popTransform()

        // Draw the crew selection rectangle, if appropriate - in screen
        // space (ie NOT under the systems-strip transform), since its
        // corners are recorded from raw input positions.
        val csr = crewSelectionRectangle
        if (csr != null && !isCrewSelectionPoint) {
            g.colour = Colour.white
            val size = csr.second - csr.first
            g.drawRect(csr.first.x.f, csr.first.y.f, size.x.f, size.y.f)
            g.drawRect(csr.first.x + 1f, csr.first.y + 1f, size.x - 2f, size.y - 2f)
        }

        drawSubSystems(gc)

        // Draw the buttons last, so they don't disappear for a frame
        // when they're updated.
        for (button in buttons) {
            val s = button.renderScale
            if (s == 1f) {
                button.draw(g)
                continue
            }

            // Touch-scaled buttons draw larger than vanilla, anchored at
            // their position - Button.contains() converts hit-testing to
            // match.
            g.pushTransform()
            g.translate(button.pos.x.f, button.pos.y.f)
            g.scale(s, s)
            g.translate(-button.pos.x.f, -button.pos.y.f)
            button.draw(g)
            g.popTransform()
        }

        drawSpaceStatus(g)
        if (g.tooltip == null && currentWindow == null && crewSelectionRectangle == null)
            crewTooltip.show(g)
        inDangerWarning.draw(g)
        jumpButtonRef?.let { drawEnginesWarning(g, it) }

        // Draw the floating numbers that show a change in a resource
        val sceneTint = Graphics.tint
        Graphics.tint = statusTint
        for (anim in resourceDeltaAnimations) {
            anim.draw(g)
        }
        Graphics.tint = sceneTint

        updatingButtons = false

        // If the player's ship is full of crew, open the window to dismiss one.
        // Don't override another window to do this, however.
        if (currentWindow == null && game.playerHasTooManyCrew()) {
            showShipWindow(ShipWindow.Tab.CREW, true)
        }
    }

    private fun drawSystems(g: Graphics) {
        // The power tree's wire goes under its energy bars, which are drawn after it.
        var totalPower = ship.purchasedReactorPower
        ship.backupBattery?.let { totalPower += it.contributedPower }
        val fullPowerHeight = (totalPower - 1) * 9
        val availablePowerHeight = (ship.powerAvailable - 1) * 9

        val powerTreeX = 86 - 53
        val powerTreeY = height - 21 - 302
        val powerTreeMaskY = powerTreeY + 27
        val powerIsAvailable = ship.powerAvailable > 0
        val powerMaskImg = game.getImg("img/wire_left_mask.png")
        val powerWireImg = game.getImg("img/wireUI/wire_full.png")

        Utils.drawStenciled(Utils.StencilMode.BLOCKING, {
            powerMaskImg.draw(powerTreeX.f + 4, powerTreeMaskY.f - fullPowerHeight)
        }) {
            powerWireImg.draw(powerTreeX.f, powerTreeY.f, 0.5f)
        }

        // Have to check since this draws the first system's power wire too,
        // and it's not masked off.
        if (powerIsAvailable) {
            Utils.drawStenciled(Utils.StencilMode.BLOCKING, {
                powerMaskImg.draw(powerTreeX.f + 4, powerTreeMaskY.f - availablePowerHeight - 1)
            }) {
                // The power wire is taller than the mask (and way taller
                // than it needs to be), so the top shows if almost all
                // the ship's power is used.
                val maxHeight = powerMaskImg.height
                val exclusion = powerWireImg.height - maxHeight
                powerWireImg.draw(
                    powerTreeX.f, powerTreeY.f + exclusion,
                    powerTreeX.f + powerWireImg.width, powerTreeY.f + powerWireImg.height,
                    0f, exclusion.f,
                    powerWireImg.width.f, powerWireImg.height.f
                )
            }
        }

        drawReactorPower(g)

        // SystemControl::MouseMove (0x004c44a0) hovers the reactor's rect, strictly inside: 28 wide from x 12 and
        // 9 px per bar (set by the render, 0x004c7a8e). OnLoop (0x004c9d70) then shows tooltip_powerTotal.
        val (barMouseX, barMouseY) = toBarSpace(mousePos.x, mousePos.y)
        val reactorRectTop = height - 34 - 9 * (totalPower - 1) - 1
        if (barMouseX > 12 && barMouseX < 12 + 28 && barMouseY > reactorRectTop &&
            barMouseY < reactorRectTop + 9 * totalPower
        ) {
            g.tooltip = reactorTooltip
        }

        // Draw the systems and the wires under them
        var weaponPowerX: Int? = null
        var dronePowerX: Int? = null
        var powerX = 58
        val powerY = height - 69
        for ((i, system) in ship.mainSystems.withIndex()) {
            // Draw the power bar to the next item
            val wireX = powerX + 31
            val wireY = powerY +
                    45 + // Top of the power button image to the bottom
                    12 + // Distance between the power button and the main horizontal wire
                    -26 // Go back up to the top of the wire image

            // Save the position of the weapon and drone power icons, since
            // they're used to position the weapon and drone selection boxes.
            if (system is Shields)
                shieldBoxPos = ConstPoint(powerX, powerY)
            if (system is Weapons)
                weaponPowerX = wireX
            if (system is Drones)
                dronePowerX = wireX

            if (updatingButtons) {
                val powerPos = ConstPoint(powerX + 19, powerY + 19)
                systemsBarButtons += SystemPowerButton(powerPos, system)

                systemsBarButtons += system.makeExtraButtons(powerPos)
            }

            powerX += when {
                system is Weapons -> 48 + ship.weaponSlots!! * 97
                system is Drones -> 48 + ship.droneSlots!! * 97
                system.insertButtonSpace -> 54
                else -> 36
            }

            // Don't draw the bar under the last item - since each item draws the
            // wire to the next item, this would result in a wire sticking out.
            if (i == ship.mainSystems.size - 1)
                continue

            val lastSystem = i == ship.mainSystems.size - 2

            val wireWidth = when {
                // Remember that we'll only run this if this isn't the last system,
                // so we won't be drawing a wire under weapons if drones isn't installed.
                system is Weapons -> {
                    // Draw the wire that goes under the weapons to the drone system
                    when (ship.weaponSlots) {
                        4 -> "456"
                        else -> "456_${ship.weaponSlots!!}weapon"
                    }
                }

                system.insertButtonSpace -> "54"
                else -> "36"
            }
            val image = when (lastSystem) {
                true -> game.getImg("img/wireUI/wire_${wireWidth}_cap.png")
                false -> game.getImg("img/wireUI/wire_$wireWidth.png")
            }

            // The wire does indeed get drawn twice if power is available, the colours match vanilla.
            image.draw(wireX.f, wireY.f, 0.5f)
            if (powerIsAvailable) {
                image.draw(wireX.f, wireY.f)
            }
        }

        if (updatingButtons)
            systemButtonCount = systemsBarButtons.size

        // If a weapon is being targeted, check that our click event is still the game's active click
        // event. This ensures that if the click event is cancelled or overridden, targeting mode
        // is disabled.
        if (targetingSelectedWeapon != null && selectWeaponClickEvent != game.clickEvent) {
            targetingSelectedWeapon = null
            selectWeaponClickEvent = null
        }

        // Find the longest charge time of all equipped weapons
        maxWeaponChargeTime = ship.hardpoints.stream()
            .map { it.weapon }
            .filter { Objects.nonNull(it) }
            .map { it!!.chargeTime }
            .reduce { a, b -> Math.max(a, b) }
            .orElse(1f)

        // Draw the weapons box
        // Note that some modded ships don't have a weapons system
        val weapons = ship.weapons
        if (weapons != null) {
            val weaponBoxX = weaponPowerX!! + 1
            weaponBoxXForWarnings = weaponBoxX
            deferredArmamentBoxes += { drawWeaponBox(weaponBoxX, "weapons_label", ship.weaponSlots!!) }

            // Native WeaponControl only draws the toggle for three/four slots.
            if (updatingButtons && ship.weaponSlots!! > 2) {
                val autofire = AutofireButton(autofireButtonPos(weaponBoxX))
                autofire.renderScale = systemsBarScale
                buttons += autofire
            }

            for (i in 0 until ship.weaponSlots!!) {
                if (!updatingButtons)
                    continue

                // The origin position of the weapon button
                val wx = weaponBoxX + WEAPON_BOX_GLOW + 12 + i * 97
                val wy = weaponBoxY + 12 + 4

                val weapon = ship.hardpoints[i].weapon

                systemsBarButtons += object : WeaponDroneButton(ConstPoint(wx, wy), i, ConstPoint(87, 39)) {
                    override val ownerSystem: MainSystem? get() = ship.weapons
                    override val tutorialPowerFlashAlpha: Float get() = tutorialVisuals?.weaponAlpha(i) ?: 0f
                    override val empty: Boolean get() = weapon == null
                    override val name: String get() = weapon!!.type.translateShort(game)
                    override val requiredPower: Int get() = weapon!!.type.power
                    override val chargeTime: Float get() = weapon!!.chargeTime
                    override val chargeProgress: Float get() = weapon!!.chargeProgress
                    override val zoltanPower: Int get() = ship.weapons!!.getWeaponForcedPower(i)
                    override val isPowered: Boolean get() = weapon!!.isPowered
                    override val isCharged: Boolean get() = weapon!!.isCharged
                    override val isTargeted: Boolean get() = ship.weapons!!.selectedTargets.getTarget(i) != null
                    override val isSelectingTarget: Boolean get() = targetingSelectedWeapon == i
                    override val isSelectingAutofire: Boolean get() = isAimingAutofire()
                    override val isTargetAutofire: Boolean get() = ship.weapons!!.isAutofire(i)
                    override val hasChargeBar: Boolean get() = true
                    override val isBeingHacked: Boolean get() = ship.weapons!!.isHackActive
                    override val droneCooldownProgress: Float? get() = null
                    override val tooltip: ITooltipProvider? get() = weapon?.weaponBarTooltip

                    override fun click(button: Int) {
                        when (button) {
                            Input.MOUSE_LEFT_BUTTON -> weaponHotkeyPressed(i, false, isCtrlDown())
                            Input.MOUSE_RIGHT_BUTTON -> weaponHotkeyPressed(i, true)
                        }
                    }

                    override fun draw(g: Graphics) {
                        super.draw(g)

                        // WeaponBox::RenderBox1: missile weapons show Missile.png at the box
                        // origin (8px left of the outline) + (39,10), 4px higher for
                        // weapons with two or more charge levels, in the slot colour.
                        if (weapon != null && weapon.type.missilesUsed != 0) {
                            val lift = if ((weapon.type.chargeLevels ?: 0) >= 2) 4 else 0
                            game.getImg("img/Missile.png").draw(pos.x + 31, pos.y + 10 - lift, mainColour)
                        }

                        if (weapon != null && weapon.type.chargeLevels != null) {
                            drawChargedShots()
                        }

                        if (weapon != null && weapon.type.boost != null) {
                            drawChainIcon()
                        }
                    }

                    private fun drawChargedShots() {
                        requireNotNull(weapon)

                        val filter = mainColour
                        val max = weapon.maxTotalCharges
                        val ready = weapon.totalReadyCharges
                        val width = 11
                        val baseX = pos.x + (size.x - max * 11) / 2
                        val y = pos.y + 25

                        for (charge in 0 until max) {
                            val suffix = when {
                                charge < ready -> "use"
                                else -> "on"
                            }

                            val img = game.getImg("img/combatUI/icon_chain_$suffix.png")
                            val x = baseX + charge * width
                            img.draw(x, y, filter)
                        }
                    }

                    private fun drawChainIcon() {
                        requireNotNull(weapon)
                        val boost = weapon.type.boost
                        requireNotNull(boost)

                        val clockOn = when {
                            weapon.chainCount == 0 -> false
                            !isPowered -> false
                            else -> true
                        }

                        val mode = when (clockOn) {
                            true -> "on"
                            false -> "off"
                        }
                        val iconName = when (boost.type) {
                            AbstractWeaponBlueprint.BoostType.COOLDOWN -> "img/combatUI/icon_clock_$mode.png"
                            AbstractWeaponBlueprint.BoostType.DAMAGE -> "img/combatUI/icon_arrow_$mode.png"
                        }
                        val icon = game.getImg(iconName)

                        val filter = when (clockOn) {
                            true -> mainColour
                            false -> Colour.white // Don't filter the already-greyed image
                        }
                        icon.draw(pos.x + 32, pos.y + 20, filter)

                        if (clockOn) {
                            val text = "+" + weapon.chainCount
                            weaponNameText.drawString(pos.x + 50f, pos.y + 31f, text, filter)
                        }
                    }
                }
            }
        }

        // Draw the drone area, if it's installed
        val drones = ship.drones
        if (drones != null) {
            val droneBoxX = dronePowerX!! + 1
            droneBoxXForWarnings = droneBoxX
            deferredArmamentBoxes += { drawWeaponBox(droneBoxX, "drones_label", ship.droneSlots!!) }

            // Draw the in-operation drone boxes - this seems very similar to weapons
            for ((i, info) in drones.drones.withIndex()) {
                val drone = info?.type
                if (!updatingButtons)
                    break

                // The origin position of the drone button
                val pos = ConstPoint(
                    droneBoxX + WEAPON_BOX_GLOW + 4 + i * 97,
                    weaponBoxY + 12 + 4
                )

                systemsBarButtons += object : WeaponDroneButton(pos, i, ConstPoint(95, 39)) {
                    override val ownerSystem: MainSystem? get() = ship.drones
                    override val empty: Boolean get() = drone == null
                    override val name: String get() = drone!!.translateShort(game)
                    override val requiredPower: Int get() = drone!!.power
                    override val chargeTime: Float get() = error("Can't get charge time for drone")
                    override val chargeProgress: Float get() = error("Can't get charge progress for drone")
                    override val zoltanPower: Int get() = drones.getDroneForcedPower(i)
                    override val isPowered: Boolean get() = info!!.instance?.isPowered == true
                    override val isCharged: Boolean get() = isPowered // Always use the charged colour
                    override val isTargeted: Boolean get() = false
                    override val isSelectingTarget: Boolean get() = false
                    override val hasChargeBar: Boolean get() = false
                    override val isBeingHacked: Boolean get() = drones.isHackActive && info?.instance != null
                    override val droneCooldownProgress: Float?
                        get() = info?.cooldown?.let { it / AbstractDrone.DRONE_DESTROYED_COOLDOWN }
                    override val tooltip: ITooltipProvider? get() = null // TODO

                    override fun click(button: Int) {
                        if (button == Input.MOUSE_LEFT_BUTTON) changeDronePower(i, true)
                        if (button == Input.MOUSE_RIGHT_BUTTON) changeDronePower(i, false)
                    }

                    override fun draw(g: Graphics) {
                        super.draw(g)

                        if (info?.instance != null) {
                            game.getImg("img/ui_icons/icon_drones.png").draw(pos.x + 43 - 9, pos.y + 18 - 8, mainColour)
                        }
                    }
                }
            }
        }

    }

    private fun computeSubSysBox(gc: GameContainer): Pair<Int, Int> {
        if (!PlatformSpecific.INSTANCE.isTouchUi)
            return Pair(gc.width - 252, gc.height - 47)

        val reactorBars = ship.purchasedReactorPower +
                (ship.backupBattery?.contributedPower ?: 0)
        val boxX = if (reactorBars >= 12) 100 else 16
        val boxY = height - (119f * systemsBarScale).toInt() - 54
        return Pair(boxX, boxY)
    }

    private fun drawSubSystems(gc: GameContainer) {
        val (boxX, boxY) = computeSubSysBox(gc)

        game.getImg("img/box_subsystems4.png").draw(boxX - 6, boxY - 6)

        // Draw the subsystems label
        val label = game.translator["subsystems_label"]
        font.drawString(boxX + 7f, boxY + 23f, label, UI_TEXT_COLOUR_1)

        // Add all the system buttons
        if (!updatingButtons) {
            return
        }

        fun addSubSys(x: Int, sys: SubSystem?) {
            if (sys == null)
                return

            val powerPos = ConstPoint(boxX + x, boxY - 21)
            buttons += SystemPowerButton(powerPos, sys)
            buttons += sys.makeExtraButtons(powerPos)
        }

        addSubSys(6, ship.piloting)
        addSubSys(42, ship.sensors)
        addSubSys(78, ship.doorsSystem)
        addSubSys(129, ship.backupBattery)
    }

    /**
     * Draw the stack of reactor power icons.
     *
     * Returns the height of the stack of power bars, and the height of the top
     * of the highest non-empty power bar.
     */
    private fun drawReactorPower(g: Graphics) {
        // Top-left corner of the bottom power bar
        val x = 12
        val baseY = height - 34

        val barHeight = 7
        val spacing = barHeight + 2

        var totalPower = ship.purchasedReactorPower
        ship.backupBattery?.let { totalPower += it.contributedPower }
        var nextPowerIdx = 0
        for (type in EnergySource.GLOBAL_TYPES) {
            val available = ship.powerAvailableTypes[type] ?: 0

            for (i in 0 until available) {
                val y = baseY - spacing * nextPowerIdx
                type.drawReactorPowerBar(g, x, y, 28, barHeight)

                nextPowerIdx++
            }
        }

        while (nextPowerIdx < totalPower) {
            val y = baseY - spacing * nextPowerIdx

            // If this power is blocked (eg by an ion storm), show it as blue
            // with a line through it.
            if (nextPowerIdx >= totalPower - ship.blockedReactorPower) {
                g.colour = SYS_ENERGY_ION_STORM
                g.drawRect(x, y, 28 - 1, 7 - 1)
                g.drawLine(x, y + 7, x + 28, y)
            } else {
                g.colour = SYS_ENERGY_DEPOWERED
                g.drawRect(x, y, 28 - 1, 7 - 1)
            }

            nextPowerIdx++
        }

    }

    fun renderMenus(container: GameContainer, g: Graphics) {
        // The power popup sits above the ship UI but under any windows.
        // Manipulating power auto-pauses the game, so show the AUTO-PAUSED
        // banner (the iPad port's own art) while it's open.
        powerPopup?.let { popup ->
            popup.draw(g)

            val autoPause = game.getImg("img/autopause.png")
            autoPause.draw(
                (container.width - autoPause.width) / 2,
                container.height - 16 - autoPause.height
            )
        }

        currentWindow?.let { renderSingleMenu(container, g, it) }
        pauseWindow?.let { renderSingleMenu(container, g, it) }

        // The touch UI's room-selection overlay. Uses the iPad port's own
        // banner art from ftl.dat (img/autopause.png, img/
        // pause_roomselection.png), drawn centred like the vanilla pause
        // banner (img/Text_pause2.png) is in InGameState.render.
        if (roomSelectionMode) {
            val autoPause = game.getImg("img/autopause.png")
            val roomSelection = game.getImg("img/pause_roomselection.png")
            val centreX = container.width / 2

            // Anchored to the bottom of the screen (above the vanilla
            // pause banner's spot), AUTO-PAUSED block above ROOM SELECTION.
            val roomSelY = container.height - 16 - roomSelection.height
            val autoPauseY = roomSelY - 20 - autoPause.height

            autoPause.draw(centreX - autoPause.width / 2, autoPauseY)
            roomSelection.draw(centreX - roomSelection.width / 2, roomSelY)
        }

        // The touch weapon-targeting overlay, in the same bottom-anchored
        // banner style as the room-selection one, using the iPad port's
        // own art from ftl.dat (img/autopause.png + img/pause_target.png,
        // swapping to img/pause_target_beam.png while a beam is drawn).
        // The autofire arm mode pauses the game too (isPaused), so show
        // the auto-pause banner there as well - there's no iPad art for
        // "select a weapon", so it gets the single banner.
        if (PlatformSpecific.INSTANCE.isTouchUi && weaponTargetingMode) {
            val autoPause = game.getImg("img/autopause.png")
            val centreX = container.width / 2

            if (weaponTargetingMode) {
                val target = game.getImg(
                    if (beamTargeting != null) "img/pause_target_beam.png"
                    else "img/pause_target.png"
                )
                val targetY = container.height - 16 - target.height
                val autoPauseY = targetY - 20 - autoPause.height

                autoPause.draw(centreX - autoPause.width / 2, autoPauseY)
                target.draw(centreX - target.width / 2, targetY)
            } else {
                autoPause.draw(centreX - autoPause.width / 2, container.height - 16 - autoPause.height)
            }
        }

        // The touch room-picker overlay (teleporter send/recall, hacking
        // and mind control): same banner style, with the iPad port's art
        // for each picker. MIND THE ART'S FILE NAMING: 'arrive' is the
        // RECALL banner ("tap an enemy ship's room to retrieve any crew
        // inside" - the crew arrive back home) and 'leave' is the SEND
        // one ("tap an enemy ship's room to send your boarding team").
        if (PlatformSpecific.INSTANCE.isTouchUi && systemTargetingMode) {
            val autoPause = game.getImg("img/autopause.png")
            val banner = game.getImg(
                when (teleportMode) {
                    true -> "img/pause_teleport_leave.png"
                    false -> "img/pause_teleport_arrive.png"
                    null -> when {
                        isSelectingHackingTarget -> "img/pause_hacking.png"
                        else -> "img/pause_mind.png"
                    }
                }
            )
            val centreX = container.width / 2
            val bannerY = container.height - 16 - banner.height
            val autoPauseY = bannerY - 20 - autoPause.height

            autoPause.draw(centreX - autoPause.width / 2, autoPauseY)
            banner.draw(centreX - banner.width / 2, bannerY)
        }
    }

    private fun renderSingleMenu(container: GameContainer, g: Graphics, window: Window) {
        // Don't let tooltips show up through the tint
        g.tooltip = null

        // Centre the window. Touch-scaled windows (Window.renderScale)
        // are placed so the SCALED window is centred; the draw below
        // scales about the unscaled window's centre, which maps it
        // exactly onto that rectangle.
        val scale = window.renderScale
        positionMenuWindow(window)

        if (scale == 1f) {
            window.draw(g)
            return
        }

        val centreX = window.position.x + window.size.x / 2f
        val centreY = window.position.y + window.size.y / 2f
        g.pushTransform()
        g.translate(centreX, centreY)
        g.scale(scale, scale)
        g.translate(-centreX, -centreY)
        window.draw(g)
        g.popTransform()
    }

    fun drawWindowBackgroundTint(g: Graphics) {
        // Add the tint over all the regular game stuff to make the window clearer.
        g.colour = Colour(0f, 0f, 0f, 0.65f)
        g.fillRect(0, 0, width, height)
    }

    private fun drawTopBar(g: Graphics) {
        val sceneTint = Graphics.tint
        Graphics.tint = statusTint
        val isHullBoxRed = hullWarning25.isFlashingHigh || hullWarning50.isFlashingHigh || hullWarning75.isFlashingHigh
        val hullBoxImage = when {
            isHullBoxRed -> game.getImg("img/statusUI/top_hull_red.png")
            else -> game.getImg("img/statusUI/top_hull.png")
        }
        hullBoxImage.draw(0f, 0f)

        val hullLabelImg = when {
            isHullBoxRed -> game.getImg("img/statusUI/top_hull_red_label.png")
            else -> game.getImg("img/statusUI/top_hull_label.png")
        }
        val hullText = game.translator["status_hull"]
        // ShipStatus's hull tab (0x005523a0): a 16px cap, the next column stretched
        // under the label, the 34px ramp, then the last column stretched to x=121,
        // sized from the label's rounded width; the label is (25,49,51) at (9,9).
        val textWidth = font.getAdvanceWidth(hullText).f
        fun slice(left: Float, width: Float, u0: Float, u1: Float) =
            hullLabelImg.draw(left, 0f, left + width, hullLabelImg.height.f, u0, 0f, u1, hullLabelImg.height.f)
        slice(0f, 16f, 0f, 16f)
        slice(16f, textWidth - 15f, 16f, 17f)
        slice(textWidth + 1f, 34f, 17f, 51f)
        slice(textWidth + 35f, 121f - (textWidth + 35f), 51f, 52f)
        font.drawString(9f, 25f, hullText, TEXT_BUTTON_TEXT)

        if (mousePos.x in 10..370 && mousePos.y in 20..45) {
            g.tooltip = hullTooltip
        }

        // Draw the hull bar

        // TODO support ships with more or less health

        val hpW = 12f * ship.health
        val healthColour = when {
            ship.health < 10 -> SHIP_HEALTH_LOW
            ship.health < 20 -> SHIP_HEALTH_MED
            else -> SHIP_HEALTH_HIGH
        }
        val mask = game.getImg("img/statusUI/top_hull_bar_mask.png")
        val hpH = mask.height.f
        mask.draw(11f, 0f, 11f + hpW, hpH, 0f, 0f, hpW, hpH, 1f, healthColour)

        val lastFraction = lastHealth.f / ship.maxHealth
        val newFraction = ship.health.f / ship.maxHealth
        lastHealth = ship.health

        if (newFraction < 0.25f) {
            // Constantly trigger the hull critical warning
            hullWarning25.startFor(5f)
        } else if (lastFraction > 0.50f && newFraction < 0.50f) {
            hullWarning50.startFor(9f)
        } else if (lastFraction > 0.75f && newFraction < 0.75f) {
            hullWarning75.startFor(9f)
        }

        // Stop animations when we're healed, or our health drops
        // enough to trigger the next animation.
        if (newFraction > 0.25f) {
            hullWarning25.stop()
        }
        if (newFraction !in 0.25f..0.5f) {
            hullWarning50.stop()
        }
        if (newFraction !in 0.5f..0.75f) {
            hullWarning75.stop()
        }

        hullWarning25.draw(g)
        hullWarning50.draw(g)
        hullWarning75.draw(g)

        // Draw the scrap indicator
        val isScrapRed = insufficientScrapTimer != 0f &&
                (insufficientScrapTimer / INSUFFICIENT_SCRAP_FLASH_TIME).toInt() % 2 == 0
        val scrapPath = if (isScrapRed) "img/statusUI/top_scrap_red.png" else "img/statusUI/top_scrap.png"
        game.getImg(scrapPath).draw(374f + 8 - 5, 0f)
        numberFont.drawStringCentred(416f, 35f, 87f, ship.scrap.toString(), UI_SCRAP_TEXT_COLOUR)

        val deltaScrap = ship.scrap - lastResources.scrap
        if (deltaScrap != 0) {
            lastResources.scrap = ship.scrap
            val rdt = ResourceDeltaText(460f, 30f, deltaScrap)
            rdt.x += VisualRandom.nextInt(-10, 10)
            resourceDeltaAnimations.add(rdt)
        }

        if (mousePos.x in 383..505 && mousePos.y in 11..46) {
            g.tooltip = scrapTooltip
        }

        // Going down the side, for the shields/oxygen
        val shieldY = 43
        val shields = ship.shields

        // Draw the shields indicator
        val broken = shields != null
                && shields.energyLevels >= 2 // Don't warn for Zoltan B, which starts on 1
                && shields.energyLevels - shields.damagedEnergyLevels < 2
        val shieldBox = when {
            broken -> game.getImg("img/statusUI/top_shields4_red.png")
            shields == null || shields.selectedShieldBars == 0 -> game.getImg("img/statusUI/top_shields4_off.png")
            else -> game.getImg("img/statusUI/top_shields4_on.png")
        }
        shieldBox.draw(0, shieldY)
        val hacked = shields?.isHackActive == true
        if (hacked) {
            game.getImg("img/statusUI/top_shields4_purple.png").draw(31 - 8, shieldY)
        }

        if (ship.superShield != 0) {
            // Draw the super-shield bar where the recharge
            // bar would normally go.
            g.colour = SYS_ENERGY_ACTIVE
            var x = 33
            for (i in 0 until ship.superShield) {
                // The middle bar is one pixel shorter to make everything
                // else line up properly.
                val width = if (i == 2) 16 else 17

                g.fillRect(x.f, shieldY + 34f + 2f, width.f, 6f)
                x += width + 2
            }
        } else if (shields != null && shields.rechargeTimer != 0f) {
            // Draw the recharge bar
            val progress = (shields.rechargeTimer / shields.rechargeDelay).coerceIn(0f..1f)
            g.colour = when (hacked) {
                true -> SHIELD_BAR_HACKED
                false -> SHIELD_BAR_NORMAL
            }
            g.fillRect(
                33f, shieldY + 34f + 2f,
                92f * progress, 6f
            )
        }

        if (shields != null) {
            // Draw the shield bubble indicators
            val shieldOnImg = game.getImg("img/statusUI/top_shieldsquare1_on.png")
            val shieldOffImg = game.getImg("img/statusUI/top_shieldsquare1_off.png")
            val shieldHackedOnImg = game.getImg("img/statusUI/top_shieldsquare1_hacked_charged.png")
            val shieldHackedOffImg = game.getImg("img/statusUI/top_shieldsquare1_hacked.png")

            for (i in 0 until shields.selectedShieldBars) {
                val charged = shields.activeShields > i

                val img = when {
                    hacked && charged -> shieldHackedOnImg
                    hacked -> shieldHackedOffImg

                    charged -> shieldOnImg
                    else -> shieldOffImg
                }
                img.draw(30 + 23 * i, shieldY + 4)
            }

            when {
                broken -> shieldsWarning.startFor(1f)
                else -> shieldsWarning.stop()
            }
            shieldsWarning.draw(g)
        }

        // Measured with an enemy present; the no-enemy spot (+120) is unverified.
        intrudersWarning.centre = ConstPoint(if (game.enemy != null) 731 else 851, 270)
        when {
            ship.intruders.isNotEmpty() -> intrudersWarning.startFor(1f)
            else -> intrudersWarning.stop()
        }
        intrudersWarning.draw(g)

        if (mousePos.x in 12..128 && mousePos.y in 50..77) {
            g.tooltip = shieldTooltip
        }

        fun drawSmallCounter(
            name: String,
            x: Int,
            numAreaOffset: Int,
            count: Int, delta: Int,
            redThreshold: Int,
            tooltip: ITooltipProvider
        ) {
            val suffix = when {
                count <= redThreshold -> "_red"
                else -> ""
            }
            game.getImg("img/statusUI/top_${name}_on$suffix.png").draw(x, shieldY)

            val areaWidth = 35
            val areaStart = x.f + 33 + numAreaOffset

            val textWidth = numberFont.getWidth(count.toString())
            val textInternalX = ceil((areaWidth - textWidth) / 2f).toInt()

            // The resource render (0x00555a40) prints every counter in the scrap colour, low or not.
            numberFont.drawString(areaStart + textInternalX, shieldY + 27f, count.toString(), UI_SCRAP_TEXT_COLOUR)

            if (delta != 0) {
                val rdt = ResourceDeltaText(x + 40f, 65f, delta)
                resourceDeltaAnimations.add(rdt)
            }

            if (mousePos.x in x + 7..x + 7 + 59 && mousePos.y in shieldY + 7..shieldY + 7 + 26) {
                g.tooltip = tooltip
            }
        }

        val shieldsEndX = 122

        // Draw the environmental hazard imminent warnings (eg 'ion pulse imminent')
        when (val env = game.currentBeacon.getEnvironment(game)) {
            is SunEnvironment -> {
                if (env.showWarning)
                    sunWarning.draw(g)
            }

            is PulsarEnvironment -> {
                if (env.showWarning)
                    pulsarWarning.draw(g)
            }

            is xyz.znix.xftl.environment.AsbEnvironment -> {
                if (env.showWarning)
                    asbWarning.draw(g)
            }
        }

        val deltaFuel = ship.fuelCount - lastResources.fuel
        val deltaMissiles = ship.missilesCount - lastResources.missiles
        val deltaDrones = ship.dronesCount - lastResources.droneParts
        lastResources.fuel = ship.fuelCount
        lastResources.missiles = ship.missilesCount
        lastResources.droneParts = ship.dronesCount

        // Fuel shows as red when there's three or less left.
        drawSmallCounter("fuel", shieldsEndX, 0, ship.fuelCount, deltaFuel, 3, fuelTooltip)

        // Missiles only show as red when there's none left.
        // Explosive Replicator procs queue a vanilla-style '0' flash
        // (a consumed volley flashes its '-N' cost via the delta below).
        while (ship.replicatorProcPopups > 0) {
            ship.replicatorProcPopups--
            resourceDeltaAnimations.add(ResourceDeltaText(shieldsEndX + 66f + 40f, 65f, 0))
        }
        drawSmallCounter("missiles", shieldsEndX + 66, 1, ship.missilesCount, deltaMissiles, 0, missileTooltip)

        // Same for drones.
        drawSmallCounter("drones", shieldsEndX + 66 + 70, -2, ship.dronesCount, deltaDrones, 0, droneTooltip)

        // Oxygen and evasion indicator

        // Note we hide the oxygen critical warning if the player doesn't
        // have an oxygen system. This is Hyperspace behaviour, but it doesn't
        // affect vanilla, and it's hard to imagine a mod where this is a problem.
        // ShipStatus::RenderEvadeOxygen (0x00550ee0) never starts it for a ship whose blueprint name contains
        // ANAEROBIC (the Lanius cruisers).
        val avgOxygen = round(ship.averageOxygen * 100).toInt()
        val oxygenRed: Boolean
        if (avgOxygen < 25 && ship.oxygen != null && !ship.type.name.contains("ANAEROBIC")) {
            oxygenWarning.draw(g)
            oxygenRed = oxygenWarning.isFlashingHigh
        } else {
            oxygenRed = false
        }

        val evasion = ship.evasion
        val evasionRed = evasion == 0

        val evasionOxyPath = when {
            evasionRed && oxygenRed -> "img/statusUI/top_evade_oxygen_both_red.png"
            evasionRed -> "img/statusUI/top_evade_oxygen_up_red.png"
            oxygenRed -> "img/statusUI/top_evade_oxygen_down_red.png"
            else -> "img/statusUI/top_evade_oxygen.png"
        }
        val oxyY = 96
        game.getImg(evasionOxyPath).draw(1f, oxyY - 7f)

        val evadeTextRight = 92f

        // ShipStatus::RenderEvadeOxygen prints both in the UI text colour (235,245,229).
        // Evade
        oxygenEvadeFont.drawStringLeftAligned(evadeTextRight, oxyY + 15f, "$evasion%", Constants.TEXT_BUTTON_NORMAL)

        // Oxygen
        oxygenEvadeFont.drawStringLeftAligned(evadeTextRight, oxyY + 37f, "$avgOxygen%", Constants.TEXT_BUTTON_NORMAL)

        if (mousePos.x in 8..98 && mousePos.y in oxyY..oxyY + 20) {
            g.tooltip = evasionTooltip
        }
        if (mousePos.x in 8..98 && mousePos.y in oxyY + 22..oxyY + 42) {
            g.tooltip = oxygenTooltip
        }

        Graphics.tint = sceneTint

        // Draw all the crew boxes
        crewBaseY = oxyY + 59
        for ((index, crew) in ship.sys.playerCrew.withIndex()) {
            val crewY = crewBaseY + index * CREW_BOX_SPACING

            drawCrewBox(g, crew, crewX, crewY)
        }

        // Add the save/restore crew position buttons
        val lastCrewBoxY = crewBaseY + (ship.sys.playerCrew.size - 1).coerceAtLeast(0) * CREW_BOX_SPACING
        val crewSaveLoadY = lastCrewBoxY + 32
        if (updatingButtons) {
            val saveImg = ButtonImageSet.select2(game, "img/statusUI/button_station_assign")
            val loadImg = ButtonImageSet.select2(game, "img/statusUI/button_station_return")
            val base = game.getImg("img/statusUI/button_station_base.png")

            buttons += object : Button(game, ConstPoint(19 + 5, crewSaveLoadY + 5), saveImg.normal.imageSize) {
                val tooltip = HotkeyDelayedTooltip(game, "save_stations_tooltip", VanillaHotkeys.SAVE_CREW_POS)

                override fun draw(g: Graphics) {
                    // 7px glow, and the button is 5px inside the wrapper
                    base.draw(pos.x - 5 - 7, crewSaveLoadY - 7)

                    saveImg.pick(this).draw(pos)

                    if (hovered) {
                        g.tooltip = tooltip
                    }
                }

                override fun click(button: Int) {
                    if (button != Input.MOUSE_LEFT_BUTTON)
                        return

                    saveCrewPositions()
                }
            }

            buttons += object : Button(game, ConstPoint(58 + 5, crewSaveLoadY + 5), loadImg.normal.imageSize) {
                val tooltip = HotkeyDelayedTooltip(game, "ret_stations_tooltip", VanillaHotkeys.LOAD_CREW_POS)

                override fun draw(g: Graphics) {
                    // 7px glow, and the button is 5px inside the wrapper
                    base.draw(pos.x - 5 - 7, crewSaveLoadY - 7)

                    loadImg.pick(this).draw(pos)

                    if (hovered) {
                        g.tooltip = tooltip
                    }
                }

                override fun click(button: Int) {
                    if (button != Input.MOUSE_LEFT_BUTTON)
                        return

                    loadCrewPositions()
                }
            }
        }

        // The save/load stations messages, which fade out after a second
        if (saveCrewPosMessageTimer > 0f) {
            saveCrewPosMessageTimer -= game.renderingDeltaTime

            val message = game.translator[saveCrewPosMessageText]
            oxygenEvadeFont.drawString(11f, crewSaveLoadY + 55f, message, Colour.white)
        }
    }

    private fun drawCrewBox(g: Graphics, crew: LivingCrew, x: Int, y: Int) {
        val clonebay = ship.clonebay

        val isMindControlled = crew.mindControlledBy != null
        val isStunned = crew.stunTime > 0f
        val isFlashingHealth = false // TODO
        val isCloning = clonebay?.queue?.contains(crew) ?: false
        val cloneDyingProgress =
            if (clonebay?.queue?.lastOrNull() == crew)
                clonebay.dyingProgress
            else
                0f

        val colour = when {
            isMindControlled -> CREW_BOX_MIND_CONTROLLED
            crew in selectedCrew -> CREW_BOX_SELECT
            isStunned -> CREW_BOX_STUNNED
            crew in hoveredCrew -> CREW_BOX_HOVER
            isFlashingHealth -> CREW_BOX_LOW_HEALTH
            isCloning -> CREW_BOX_CLONING
            else -> CREW_BOX_NORMAL
        }

        val drawSkills = crew == skillsHoveredCrew

        // CrewBox::constructor (0x00512750): a crew with a special power (CrewMember vtable +200, the crystal's
        // lockdown) gets a box 2 px wider behind and an outline 4 px further left, split 4 px in by a divider.
        val crystal = crew as? xyz.znix.xftl.crew.CrewCrystal
        val powerWidth = if (crystal != null) 4 else 0

        if (!drawSkills) {
            // Draw the semi-transparent background
            g.colour = Colour(colour.r, colour.g, colour.b, CREW_BOX_BG_ALPHA)
            g.fillRect(x - powerWidth / 2f, y.f, CREW_BOX_WIDTH + powerWidth / 2f, CREW_BOX_HEIGHT.f)

            // Draw the solid outline, via two unfilled rectangles.
            g.colour = colour
            g.drawRect(x.f - powerWidth, y.f, CREW_BOX_WIDTH + powerWidth - 1f, CREW_BOX_HEIGHT - 1f)
            g.drawRect(x + 1f - powerWidth, y + 1f, CREW_BOX_WIDTH + powerWidth - 3f, CREW_BOX_HEIGHT - 3f)
            if (crystal != null)
                g.fillRect(x + 4f, y.f, 2f, CREW_BOX_HEIGHT.f)

            // Vanilla shows which skill a crewmember is currently gaining
            // experience in: the skill's icon, tinted green, with an up
            // arrow beside it - drawn to the right of their crew box. The
            // hovered crewmember's expanded skill panel already shows all
            // the skills, so skip the indicator there.
            val trainedSkill = crew.lastTrainedSkill
            if (trainedSkill != null) {
                val iconX = x + CREW_BOX_WIDTH + 1
                val icon = game.getImg(trainedSkill.iconPath)
                icon.draw(iconX.f, y.f, SYS_ENERGY_ACTIVE)

                // The arrow art points down; flip it to point up. It is
                // green too, and sits flush against the icon's right edge.
                val arrow = game.getImg("img/icons/arrow.png")
                g.pushTransform()
                g.translate(
                    iconX + icon.width + arrow.width / 2f,
                    y + CREW_BOX_HEIGHT / 2f
                )
                g.scale(1f, -1f)
                arrow.draw(-arrow.width / 2f, -arrow.height / 2f, SYS_ENERGY_ACTIVE)
                g.popTransform()
            }
        } else {
            // Draw the semi-transparent background
            g.colour = Colour(colour.r, colour.g, colour.b, 0.25f)
            g.fillRect(x.f, y.f, 89f, CREW_BOX_HEIGHT.f)
            g.fillRect(x + 89f, y.f, 80f, 146f)

            // Draw the outline, which we do with line drawing by rectangles.
            g.colour = colour
            g.fillRect(x.f, y.f, 2f, CREW_BOX_HEIGHT.f)
            g.fillRect(x.f, y.f, 169f, 2f)
            g.fillRect(x.f, y + 25f, 91f, 2f)
            g.fillRect(x + 89f, y + 27f, 2f, 119f)
            g.fillRect(x + 167f, y.f, 2f, 146f)
            g.fillRect(x + 91f, y + 144f, 76f, 2f)

            drawSkillBar(g, x + 93, y, crew, Skill.PILOTING)
            drawSkillBar(g, x + 93, y, crew, Skill.ENGINES)
            drawSkillBar(g, x + 93, y, crew, Skill.SHIELDS)
            drawSkillBar(g, x + 93, y, crew, Skill.WEAPONS)
            drawSkillBar(g, x + 93, y, crew, Skill.REPAIRS)
            drawSkillBar(g, x + 93, y, crew, Skill.COMBAT)
        }

        // CrewBox::OnRender (0x00516aa0): the power's charge as a 4 px bar 1 px left of the box, rising from 3 px
        // above its bottom over (height - 6) px.
        if (crystal != null && crystal.lockdownChargeMax > 0f) {
            val h = (crystal.lockdownCharge / crystal.lockdownChargeMax * (CREW_BOX_HEIGHT - 6)).toInt()
            if (h >= 1) {
                g.colour = LOCKDOWN_CHARGE
                g.fillRect(x - 1f, y + CREW_BOX_HEIGHT - 3f - h, 4f, h.f)
            }
        }

        // Draw the health bar
        val maxHpWidth = 49
        val hpFraction = crew.health / crew.maxHealth
        var hpWidth = (maxHpWidth * hpFraction).toInt()

        // Don't go below one pixel of health unless they're dead.
        // This is particularly notable when the crew is cloning.
        if (hpFraction > 0 && hpWidth == 0) {
            hpWidth = 1
        }

        if (crew.health == crew.maxHealth) {
            g.colour = Colour.green
        } else {
            g.colour = Colour(
                1f,
                (2f * hpFraction).coerceIn(0f..1f),
                0f
            )
        }
        g.fillRect(x + 33f, y + 19f, hpWidth.f, 4f)

        // Draw the crew portrait, mostly so you can see what race they are.
        // CrewBox::RenderIcon places it at (0,-2) from the box (measured against the original).
        crew.drawPortrait(x, y - 2, true)

        // Draw the crew name
        crewNameText.drawString(x + 33f, y + 14f, crew.info.shortName, CREW_BOX_NAME_COLOUR)

        // If the crew is dying in a clonebay, draw the red overlay
        if (cloneDyingProgress > 0f) {
            g.colour = CREW_BOX_CLONE_DYING_OVERLAY
            g.fillRect(x.f, y.f, CREW_BOX_WIDTH.f, CREW_BOX_HEIGHT * cloneDyingProgress)
        }

        // Add the tooltip
        if (drawSkills) {
            g.tooltip = object : ITooltipProvider {
                override fun drawTooltip(
                    g: Graphics,
                    mouseX: Int, mouseY: Int,
                    firstFrame: Boolean,
                    screenWidth: Int, screenHeight: Int
                ) {
                    val lines = listOf(
                        game.translator["health_tooltip"]
                            .replaceArg(crew.health.roundToInt())
                            .replaceArg(crew.maxHealth.roundToInt(), 2),
                        game.translator["hotkey"].replace(
                            "\\1",
                            game.getButtonNameForHotkey(VanillaHotkeys.SELECT_CREW.getOrNull(game.playerCrew.indexOf(crew)), "")
                        )
                    )

                    val width = TooltipConstants.widthOf(lines, tooltipFont)
                    val height = TooltipConstants.heightOf(lines)

                    TooltipConstants.drawTooltip(g, tooltipFont, x + 181, y, width, height, lines)
                }
            }
        }
    }

    private fun drawSkillBar(
        g: Graphics,
        x: Int, baseY: Int,
        crew: LivingCrew, skill: Skill
    ) {
        val y = baseY + skill.ordinal * 24 // Not 26, which is the icon height
        val skillLevel = crew.getSkillLevel(skill)

        // Draw the icon
        val icon = game.getImg(skill.iconPath)
        val iconColour = when (skillLevel) {
            SkillLevel.MAX -> SYS_ENERGY_REPAIR
            SkillLevel.PARTIAL -> SYS_ENERGY_ACTIVE
            else -> UI_BACKGROUND_GLOW_COLOUR
        }
        icon.draw(x, y, iconColour)

        // Draw the progress bar
        crew.info.drawSkillProgressBar(g, x + 30, y + 9, 40, 8, skill)
    }

    // Draw the box containing the weapon or drone selection buttons
    private fun drawWeaponBox(x: Int, label: String, size: Int) {
        val frameImg = game.getImg("img/box_weapons_bottom$size.png")

        frameImg.draw(x, weaponBoxY)

        val textX = x + 18
        val textY = weaponBoxY + 61

        // Draw the 'weapons' or 'drones' label
        val name = game.translator[label]
        val img = game.getImg("img/box_weapons_bottom_label.png")

        // ArmamentControl's label strip (0x00571760): five slices of the 53px art
        // along the whole frame - cap, stretched label, ramp, stretched bottom edge
        // and end cap - sized from the label's measured width.
        val textWidth = font.getAdvanceWidth(name)
        fun slice(left: Int, width: Int, u0: Float, u1: Float) =
            img.draw((x + left).f, textY.f, (x + left + width).f, textY + img.height.f, u0, 0f, u1, img.height.f)
        slice(18, 3, 0f, 3f)
        slice(21, textWidth - 11, 3f, 4f)
        slice(textWidth + 10, 35, 4f, 39f)
        slice(textWidth + 45, frameImg.width - (textWidth + 45) - 13, 39f, 40f)
        slice(frameImg.width - 13, 13, 40f, 53f)

        font.drawString(textX + 1f, textY + 15f, name, UI_TEXT_COLOUR_1)
    }

    fun updateUI(x: Int, y: Int, playerShipPosition: IPoint) {
        updateArmamentDrag(x, y)
        updateWeaponTargetingMode()
        updateRoomSelectionMode()
        updatePowerPopup()


        mousePos.set(x, y)

        // The HUD under a focus window shows no hover (the original's STORE button is
        // idle while its store is open): CommandGui::MouseMove (0x535e00) gives the open
        // window the mouse and everything else (-1,-1), so doors and crew lose their hover too.
        if (pauseWindow != null || currentWindow != null) {
            for (button in systemsBarButtons) button.update(-1, -1)
            for (button in buttons) button.update(-1, -1)
            for (door in ship.doors) door.clearMouseHover()
            hoveredCrew.clear()
            skillsHoveredCrew = null
        }

        pauseWindow?.let { win ->
            positionMenuWindow(win)
            win.updateUI(x, y)
            return
        }

        currentWindow?.let { win ->
            positionMenuWindow(win)
            win.updateUI(x, y)
            return
        }

        for (button in systemsBarButtons) {
            val (bx, by) = toBarSpace(x, y)
            // While a weapon or drone is dragged its strip shows no hover (0x56f750).
            if (weaponDragMoved && button is WeaponDroneButton && button.ownerSystem === weaponDragSystem)
                button.update(-1, -1)
            else
                button.update(bx, by)
        }

        for (button in buttons) {
            button.update(x, y)
        }

        if (beamTargeting != null) {
            updateBeamTargeting(x, y)
        }

        crewSelectionRectangle?.second?.set(x, y)

        updateHoveredCrew(x, y, playerShipPosition)

        // Update the door hover markers
        for (door in ship.doors) {
            door.updateMouseHover(x - playerShipPosition.x, y - playerShipPosition.y)
        }
    }

    fun showEventDialogue(event: Event, seed: Int) {
        openEventDialogue(event, seed)
    }

    /** Show an event the sector or the game already created. */
    fun showEventDialogue(event: xyz.znix.xftl.sector.CreatedEvent) {
        openDialogueWindow { close -> DialogueWindow(game, ship, event, close) }
    }

    private fun openEventDialogue(event: Event?, seed: Int) {
        openDialogueWindow { close -> DialogueWindow(game, ship, event, seed, close) }
    }

    private fun openDialogueWindow(create: (() -> Unit) -> DialogueWindow) {
        var hasImmediatelyClosed = false

        currentWindow = create {
            hasImmediatelyClosed = true
            eventDialogueClosed()
        }

        // It's possible for DialogueWindow to call close in its constructor,
        // in which case we have to close it not to avoid it being rendered
        // while in an invalid state.
        // (This is because the constructor would run before currentWindow
        //  is set).
        // This can be tested with the TRADER_LIST event, or any other
        // event list whose purpose is to pick one of several events and
        // close without any text set.
        if (hasImmediatelyClosed) {
            currentWindow = null
        }
    }

    fun showSyntheticDialogue(syntheticEvent: DialogueWindow.SyntheticEvent) {
        var dialogue = currentWindow as? DialogueWindow

        // Open a new, event-less window.
        // This would crash on the next render if we didn't add
        // the synthetic event.
        if (dialogue == null) {
            openEventDialogue(null, 0)
            dialogue = currentWindow as DialogueWindow
        }

        dialogue.addSyntheticEvent(syntheticEvent)
    }

    private fun eventDialogueClosed() {
        currentWindow = null
        if (ship.cargoBlueprints.drop(4).any { it != null } && !game.isInDanger) showShipWindow(ShipWindow.Tab.EQUIPMENT, true)

        // The beacon's event has fully resolved (rewards, ships, stores) -
        // save so a process kill resumes after the event, not before it.
        game.mainGame?.writeRunSave()

        // If a store was made available by the dialogue, open it
        if (game.currentBeacon.hasStore && !storeAlreadyOpened) {
            updateButtons() // Make the store button show up
            showStoreWindow()
        }
    }

    private fun showShipWindow(initialTab: ShipWindow.Tab, force: Boolean = false) {
        if (game.mainGame?.tutorialAdapter?.controller?.shipOpeningAllowed == false) {
            ftl.browser.BrowserDebug.event("tutorial", "navigation.rejected", "window", "ship")
            return
        }
        if (!force && game.isInDanger)
            return

        currentWindow = ShipWindow(game, ship, initialTab) {
            currentWindow = null
        }
    }

    private fun showStoreWindow() {
        if (game.isInDanger)
            return

        val store = game.currentBeacon.getStore(game) ?: return

        storeAlreadyOpened = true

        currentWindow = StoreWindow(game, ship, store) {
            currentWindow = null
        }
    }

    fun showPauseWindow() {
        pauseWindow = PauseWindow(game) { pauseWindow = null }
    }

    fun confirmEndRun(action: () -> Unit) {
        val previous = pauseWindow
        pauseWindow = ConfirmationWindow(SettingsContext(game), "confirm_end_game", accept = {
            if (game.mainGame.currentState === game) {
                pauseWindow = null
                action()
            }
        }, cancel = { if (game.mainGame.currentState === game) pauseWindow = previous }).also {
            it.backdrop = previous
            it.centre(game.mainGame.gameContainer.width, game.mainGame.gameContainer.height)
        }
        ftl.browser.BrowserDebug.event("ui", "confirmation.open", "message", "confirm_end_game")
    }

    fun showOptionsWindow() {
        // This uses the pause window slot, so it doesn't dismiss
        // a regular window, such as the dialogue or game-over windows.
        pauseWindow = OptionsWindow(game) { pauseWindow = null }
    }

    fun showKeybindWindow() {
        // Switch back to the options window when it's closed, that's how
        // vanilla behaves.
        pauseWindow = HotkeysWindow(game) { showOptionsWindow() }
    }

    // Hack used to draw the selected crew from the ship, rather than some cleaner solution
    fun isCrewHighlighted(crew: AbstractCrew): Boolean {
        return selectedCrew.contains(crew) || hoveredCrew.contains(crew)
    }

    fun escapePressed() {
        pauseWindow?.let { win ->
            win.escapePressed()
            return
        }

        currentWindow?.let { win ->
            win.escapePressed()
            return
        }

        showPauseWindow()
    }

    fun playInsufficientScrapAnimation() {
        // Three flashes: on-off-on-off-on
        insufficientScrapTimer = INSUFFICIENT_SCRAP_FLASH_TIME * 5

        // I think this is the right sound?
        powerUpFailSound.play()
    }

    /** The warnings drawn in the systems strip's space: SystemControl's, SystemBox's and the armament boxes'. */
    private fun drawBarWarnings(g: Graphics) {
        var totalPower = ship.purchasedReactorPower
        ship.backupBattery?.let { totalPower += it.contributedPower }
        val reactorTop = height - 34 - 9 * (totalPower - 1)
        noPowerWarning.placeNative(6, reactorTop - 73)
        noPowerWarning.draw(g)

        shieldBoxPos?.let {
            shieldUpgradeWarning.placeNative(it.x + 5, it.y - 111)
            shieldUpgradeWarning.draw(g)
        }

        weaponBoxXForWarnings?.let { x ->
            noMissilesWarning.placeNative(x + 203, weaponBoxY - 25)
            noMissilesWarning.draw(g)
            weaponPowerWarning.placeNative(x + 220, weaponBoxY - 45)
            weaponPowerWarning.draw(g)
        }

        droneBoxXForWarnings?.let { x ->
            noDronesWarning.placeNative(x + 112, weaponBoxY - 25)
            noDronesWarning.draw(g)
            noEnemyShipWarning.placeNative(x + 124, weaponBoxY - 60)
            noEnemyShipWarning.draw(g)
            dronePowerWarning.placeNative(x + 115, weaponBoxY - 45)
            dronePowerWarning.draw(g)
        }
    }

    /**
     * An armament the system can't power (DroneControl 0x0054e080; WeaponControl alike): when the system has
     * room for it only the reactor was short (SystemControl's NOT ENOUGH POWER); otherwise the box's message
     * says whether the level, the other items or the damage is in the way.
     */
    internal fun armamentPowerFailed(system: MainSystem, required: Int, warning: WarningFlasher) {
        if (system.isPowerLocked)
            return
        if (system.undamagedEnergy - system.powerSelected >= required) {
            noPowerWarning.startFor(NO_POWER_TIME)
            return
        }
        warning.setText(
            when {
                system.energyLevels < required -> "warning_needs_upgrade"
                system.energyLevels - system.powerSelected < required || system.damagedEnergyLevels == 0 ->
                    "warning_no_system_power"

                else -> "warning_system_broken"
            }
        )
        warning.startFor(WARNING_TIME)
    }

    /** 0x0054e080's checks before the power ones: drone parts, then (unlocked) a ship to deploy against. */
    private fun dronePowerFailed(drones: Drones, slot: Int, info: Drones.DroneInfo) {
        if (info.instance == null && ship.dronesCount <= 0 && !game.debugFlags.infiniteDrones.set) {
            noDronesWarning.startFor(WARNING_TIME)
            return
        }
        if (drones.isPowerLocked)
            return
        if (info.type.type.needsHostileShip && game.enemy == null) {
            noEnemyShipWarning.startFor(WARNING_TIME)
            return
        }
        // A drone being rebuilt is refused without a message.
        if (info.cooldown != null)
            return
        armamentPowerFailed(drones, info.type.power - drones.getDroneForcedPower(slot), dronePowerWarning)
    }

    /**
     * SpaceStatus (OnLoop 0x005017f0, OnRender 0x00502090), placed at (690, 72): the hazard icon at (660, 72),
     * a storm adding its own 72 px on, and DANGER! - ALLIES! in green for an ASB on the player's side - in font
     * 52 centred 51 px below (36 px further right with two icons). Hovering an icon (0x00501df0) shows its
     * tooltip. The power surge message is SpaceStatus's too.
     */
    private fun drawSpaceStatus(g: Graphics) {
        val boss = game.enemy?.boss as? FlagshipBoss
        powerSurgeWarning.loopWhile(boss?.incomingFire == true)
        powerSurgeWarning.draw(g)

        val beacon = game.currentBeacon
        val env = beacon.getEnvironment(game)
        var allies = false
        val icons: List<Pair<String, String>> = when (env.type) {
            Beacon.EnvironmentType.ASTEROID -> listOf("danger_asteroids" to "asteroids")
            Beacon.EnvironmentType.SUN -> listOf("danger_star" to "sun")
            Beacon.EnvironmentType.PULSAR -> listOf("danger_pulsar" to "pulsar")
            Beacon.EnvironmentType.NEBULA -> listOf("danger_nebula" to "nebula")
            Beacon.EnvironmentType.ION_STORM -> listOf("danger_nebula" to "nebula", "danger_storm" to "storm")
            Beacon.EnvironmentType.PDS -> {
                val target = (env as? xyz.znix.xftl.environment.AsbEnvironment)?.pdsTarget ?: 0
                allies = target == 1
                val key = when {
                    beacon.isOvertaken -> "PDS_FLEET"
                    target == 0 -> "PDS_PLAYER"
                    target == 1 -> "PDS_ENEMY"
                    else -> "PDS_ALL"
                }
                listOf((if (allies) "danger_pds_green" else "danger_pds") to key)
            }

            else -> emptyList()
        }
        if (icons.isEmpty())
            return

        for ((i, icon) in icons.withIndex()) {
            game.getImg("img/warnings/${icon.first}.png").draw(660 + 72 * i, 72)
            val left = 655 + 72 * i
            if (mousePos.x > left && mousePos.x < left + 70 && mousePos.y > 69 && mousePos.y < 69 + 73) {
                g.tooltip = hazardTooltips.getOrPut(icon.second) { FixedDelayedTooltip(game, "tooltip_" + icon.second) }
            }
        }

        val text = game.translator[if (allies) "warning_pds_allies" else "warning_environment_danger"]
        val font = dangerFont.font
        val centreX = 690 + if (icons.size > 1) 36 else 0
        UIUtils.drawStringWithGlow(
            game, font, text, centreX - font.getWidth(text) / 2, 123 + dangerFont.spec.baseline,
            if (allies) GlowColour.GREEN else GlowColour.RED, 1f
        )
    }

    /**
     * CrewControl::OnLoop (0x00503400): the crew under the mouse on the player's ship (ShipManager 0x0049ec50),
     * or failing that on the targeted ship (CombatControl::GetCrewTooltip 0x0057dbf0).
     */
    private inner class CrewHoverTooltip : DelayedTooltip(game) {
        private var text = ""

        fun show(g: Graphics) {
            val point = Point(mousePos.x, mousePos.y)
            game.convertScreenToPlayerShip(point)
            text = crewTooltipText(ship, point)
            val enemy = game.enemy
            if (text.isEmpty() && enemy != null) {
                point.set(mousePos.x, mousePos.y)
                game.convertScreenToEnemyShip(point)
                text = crewTooltipText(enemy, point)
            }
            if (text.isNotEmpty())
                g.tooltip = this
        }

        override fun getText(): String = text
    }

    /**
     * ShipManager's crew tooltip (0x0049ec50): each crew member alive on [target] whose square holds [point]
     * (CrewMember::ContainsPoint 0x0047db30: 35 px around its centre, strictly) in a room that isn't blacked out
     * adds its own text, " \n" apart, and a mind-controlled one mind_controlled_tooltip. CrewMember 0x0047f6e0:
     * the name, then health_tooltip with the health rounded down (at least 1) and the maximum, or death_tooltip
     * while dying.
     */
    private fun crewTooltipText(target: Ship, point: IPoint): String {
        val out = StringBuilder()
        for (crew in target.crew) {
            val centre = crew.getPixelPositionCentre()
            if (point.x <= centre.x - 17 || point.x >= centre.x + 17 || point.y <= centre.y - 17 || point.y >= centre.y + 17)
                continue
            if (!crew.room.playerHasVision && !crew.providesPlayerVision)
                continue
            if (out.isNotEmpty())
                out.append(" \n")
            val name = (crew as? LivingCrew)?.info?.name ?: ""
            if (name.isNotEmpty())
                out.append(name).append(" \n")
            if (crew.currentAction == AbstractCrew.Action.DYING) {
                out.append(game.translator["death_tooltip"])
            } else {
                out.append(
                    game.translator["health_tooltip"].replaceArg(maxOf(1, crew.health.toInt()))
                        .replaceArg(crew.maxHealth.toInt(), 2)
                )
            }
            if ((crew as? LivingCrew)?.mindControlledBy != null)
                out.append(" \n").append(game.translator["mind_controlled_tooltip"])
        }
        return out.toString()
    }

    /** FTLButton's ENGINES CRITICAL (0x00558690): origin at the button's spot plus (78, 86). */
    private fun drawEnginesWarning(g: Graphics, jump: Buttons.JumpButton) {
        val engines = ship.engines ?: return
        enginesWarning.loopWhile(engines.broken)
        // The jump button sits 10, 19 from the spot FTLButton is made at.
        val ox = jump.pos.x - 10 + 78
        val oy = jump.pos.y - 19 + 86
        for ((point, offset) in enginesWarningLines.zip(ENGINE_WARNING_LINES)) {
            point.x = ox + offset.x
            point.y = oy + offset.y
        }
        // The text is 110, 46 from the origin, and a lined message's WARNING! baseline is 23 over its text.
        enginesWarning.centre = ConstPoint(ox + 110, oy + 46 + 16 + 23)
        enginesWarning.draw(g)
    }

    fun shipModified() {
        updateButtons()
        currentWindow?.shipModified()
    }

    /**
     * Called by [Teleporter] when one of its buttons are clicked.
     *
     * When the 'send to enemy ship' button is clicked, [send] is true.
     * Otherwise it's false.
     */
    fun teleportSelected(send: Boolean) {
        game.clickEvent = TeleportRoomListener(send)
    }

    /**
     * Called by [Hacking] when we're supposed to select the room to hack.
     */
    fun hackSelected() {
        game.clickEvent = HackingRoomListener()
    }

    /**
     * Called by [MindControl] when we're supposed to select the room to
     * apply mind control to.
     */
    fun mindControlSelected() {
        game.clickEvent = MindControlRoomListener()
    }

    fun openJumpMap() {
        if (game.mainGame?.tutorialAdapter?.controller?.mapOpeningAllowed == false) {
            ftl.browser.BrowserDebug.event("tutorial", "navigation.rejected", "window", "map")
            return
        }
        if (!ship.isFtlReady || !ship.canChargeFTL)
            return

        if (isWindowOpen) return
        // Native ShipManager::CountPlayerCrew excludes dead crew and drones,
        // using ownership rather than temporary mind-control allegiance.
        if (strandedPlayerCrewCount > 0) {
            currentWindow = ConfirmationWindow(SettingsContext(game), "confirm_leave_crew", accept = {
                if (game.mainGame.currentState === game) {
                    currentWindow = null
                    openJumpMapConfirmed()
                }
            }, cancel = { if (game.mainGame.currentState === game) currentWindow = null }).also {
                it.centre(game.mainGame.gameContainer.width, game.mainGame.gameContainer.height)
            }
            ftl.browser.BrowserDebug.event("ui", "confirmation.open", "message", "confirm_leave_crew")
            return
        }
        openJumpMapConfirmed()
    }

    private fun openJumpMapConfirmed() {
        if (!ship.isFtlReady || !ship.canChargeFTL) return

        currentWindow = JumpWindow(game, ::openSectorMap) {
            if (it != null) {
                // Reset stuff after jumping
                storeAlreadyOpened = false
            }

            currentWindow = null
            updateButtons() // A store may now be available
        }

        // StarMap::Open (0x5ea3a0) picks two background angles (random32() % 360, kept in StarMap +0x8c and
        // +0x90) every time the map opens: they come from the global stream, between the draws of events.
        ftl.browser.NativeRandom.nextInt(360)
        ftl.browser.NativeRandom.nextInt(360)
    }

    fun openSectorMap() {
        currentWindow = SectorMapWindow(game) { sectorInfo ->
            currentWindow = null

            // Null just means the window was closed
            if (sectorInfo == null)
                return@SectorMapWindow

            val sector = game.gameMap.generateSector(sectorInfo, game)

            // Jump via the proper jump path (GitHub issue #63): the jump-out
            // animation plays, then arrival at the new sector's start beacon
            // runs setCurrentBeacon - which handles the sector music switch,
            // the arrival chime, spawning whatever's at the beacon, etc.
            // The run-save is written by the existing jump-out completion /
            // immediate-switch fallback sites, same as normal beacon jumps.
            game.beginJumpOut(sector.startBeacon)

            // In case we were at a store
            // TODO move this into an on-jump handler function
            updateButtons()
        }
    }

    fun openAllDoors() {
        // Vanilla refuses every door command - buttons, hotkeys - while the
        // doors system is inoperable. See Ship.areDoorsOperable.
        if (!ship.areDoorsOperable)
            return

        val internalDoors = ship.doors.filter { !it.isAirlock }
        val allInternalDoorsOpen = internalDoors.all { it.open }

        // Pressing the open key with all the internal doors open
        // opens all the airlocks, otherwise only the internal
        // doors are opened.
        for (door in ship.doors) {
            if (!allInternalDoorsOpen && door.isAirlock)
                continue

            door.open = true
        }
    }

    fun closeAllDoors() {
        // Same gate as openAllDoors: no manual door control at all while the
        // doors system is broken, ionised, hacked or unpowered.
        if (!ship.areDoorsOperable)
            return

        for (door in ship.doors) {
            door.open = false
        }
    }

    /**
     * Called whenever a hotkey is pressed.
     *
     * This is the proper way to handle hotkey input, as it supports control rebinding.
     */
    fun hotkeyPressed(key: Hotkey, input: Input) {
        // If a window is open, it absorbs all the input.
        // This is particularly important for keybinds like the jump/ship
        // ones as they open a window - the user could otherwise use them
        // to replace the currently-open dialogue window, to avoid the
        // result of an undesirable event.
        pauseWindow?.let {
            it.hotkeyPressed(key)
            return
        }
        currentWindow?.let {
            it.hotkeyPressed(key)
            return
        }

        for (system in ship.mainSystems) {
            if (key.id == "sys_power_${system.codename}") {
                val powerUp: Boolean = !input.isKeyDown(Input.KEY_LSHIFT) && !input.isKeyDown(Input.KEY_RSHIFT)
                changeSystemPower(system, powerUp)
            }
            if (key.id == "sys_power_${system.codename}_off") {
                changeSystemPower(system, false)
            }
        }

        for (system in ship.systems) {
            system.hotkeyPressed(key)
        }

        // The select-one-crewmember hotkeys
        for ((index, id) in VanillaHotkeys.SELECT_CREW.withIndex()) {
            if (key.id != id)
                continue

            val crew = game.playerCrew.getOrNull(index) ?: continue
            if (!crew.playerControllable)
                continue

            selectedCrew.clear()
            selectedCrew += crew
        }

        when (key.id) {
            VanillaHotkeys.FTL_JUMP -> openJumpMap()
            VanillaHotkeys.SHIP_UPGRADES -> showShipWindow(ShipWindow.Tab.UPGRADES)
            VanillaHotkeys.SHIP_CREW -> showShipWindow(ShipWindow.Tab.CREW)
            VanillaHotkeys.SHIP_INVENTORY -> showShipWindow(ShipWindow.Tab.EQUIPMENT)
            VanillaHotkeys.OPEN_STORE -> showStoreWindow()
            VanillaHotkeys.OPEN_OPTIONS -> showOptionsWindow()

            VanillaHotkeys.SYS_ACTION_DOOR_OPEN -> openAllDoors()
            VanillaHotkeys.SYS_ACTION_DOOR_CLOSE -> closeAllDoors()

            VanillaHotkeys.SELECT_CREW_ALL -> {
                // Should this include mind-controlled enemies?
                selectedCrew.clear()
                selectedCrew += game.playerCrew.filter { it.playerControllable }
            }

            VanillaHotkeys.LOCKDOWN_ACTIVATE -> selectedCrew.filterIsInstance<xyz.znix.xftl.crew.CrewCrystal>().forEach { it.activateLockdown() }

            VanillaHotkeys.SAVE_CREW_POS -> saveCrewPositions()
            VanillaHotkeys.LOAD_CREW_POS -> loadCrewPositions()

            // These hotkey applies to both medbays and clonebays
            VanillaHotkeys.SYS_POWER_MEDICAL -> {
                val powerUp: Boolean = !input.isKeyDown(Input.KEY_LSHIFT) && !input.isKeyDown(Input.KEY_RSHIFT)
                ship.medbay?.let { changeSystemPower(it, powerUp) }
                ship.clonebay?.let { changeSystemPower(it, powerUp) }
            }

            VanillaHotkeys.SYS_POWER_MEDICAL_OFF -> {
                ship.medbay?.let { changeSystemPower(it, false) }
                ship.clonebay?.let { changeSystemPower(it, false) }
            }
        }
    }

    /**
     * FOR DEBUG USE ONLY!
     *
     * See [InGameState.debugContinuousSaveRestore] for more information.
     */
    fun debugContinuousSaveRestore(prev: PlayerShipUI) {
        crewSelectionRectangle = prev.crewSelectionRectangle

        // Copy over the selected crew, based on the index into the ship
        // crew list. This is hacky but it's fine for a debug flag.
        for (prevCrew in prev.selectedCrew) {
            val index = prev.ship.crew.indexOf(prevCrew)

            // Don't bother with crew on the enemy ship.
            if (index == -1)
                continue

            selectedCrew += ship.crew[index]
        }

        // Reloading should keep the store open, if it's run manually.
        val oldStore = prev.currentWindow as? StoreWindow
        if (oldStore != null) {
            showStoreWindow()
            val store = currentWindow as StoreWindow
            store.sellTab = oldStore.sellTab
            store.secondBuyTab = oldStore.secondBuyTab
        }
    }

    private fun updateHoveredCrew(mouseX: Int, mouseY: Int, playerShipPosition: IPoint) {
        hoveredCrew.clear()
        skillsHoveredCrew = null

        // Allow for smart-casting
        val csr = crewSelectionRectangle

        // If a rectangle is visible on screen (ie, the player isn't clicking a point) then build a rectangle
        val rect = if (csr != null && !isCrewSelectionPoint) {
            val pos = csr.first.min(csr.second)
            val size = (csr.second - csr.first).abs()
            Rectangle(pos.x.f, pos.y.f, size.x.f, size.y.f)
        } else null

        // Any intruders on the enemy ship should also be controllable,
        // including mind controlled enemies (once that's implemented).
        // Mind-controlled crew are then filtered out in the for loop.
        val controllableCrew = ArrayList(ship.friendlyCrew)
        game.enemy?.let { controllableCrew.addAll(it.intruders) }

        for (crew in controllableCrew) {
            // Skip drones, mind controlled crew and the like which the
            // player isn't allowed to control.
            if (!crew.playerControllable)
                continue

            val crewPos = crewScreenPos(crew, playerShipPosition)

            @Suppress("IfThenToElvis")
            val hovered = if (rect != null) {
                // If we're in rectangle mode, check that it intersects the centre of the player's body
                // TODO also check if the player is selecting the crew name box
                rect.contains(crewPos.x + ROOM_SIZE / 2f, crewPos.y + ROOM_SIZE / 2f)
            } else {
                // Otherwise check the point overlaps the player
                isCrewHovered(crew, mouseX, mouseY, crewPos)
            }

            if (hovered) {
                hoveredCrew += crew
            }
        }

        // CrewControl's mouse move (0x5052c0) also selects the crew whose box in the crew list
        // the rectangle overlaps (CrewBox test 0x5122a0, strict on every edge).
        if (rect != null) {
            for ((index, crew) in game.playerCrew.withIndex()) {
                if (!crew.playerControllable || crew in hoveredCrew)
                    continue
                val boxY = crewBaseY + index * CREW_BOX_SPACING
                if (boxY < rect.y + rect.height && rect.x < crewX + CREW_BOX_WIDTH &&
                    crewX < rect.x + rect.width && rect.y < boxY + CREW_BOX_HEIGHT
                ) {
                    hoveredCrew += crew
                }
            }
        }
    }

    fun saveCrewPositions() {
        ship.saveCrewPositions()

        // Show the popup text
        saveCrewPosMessageTimer = 1f
        saveCrewPosMessageText = "stations_saved"
    }

    fun loadCrewPositions() {
        ship.loadCrewPositions()

        // Show the popup text
        saveCrewPosMessageTimer = 1f
        saveCrewPosMessageText = "return_stations"
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        val window = currentWindow

        // If the player is currently playing through an event, save that.
        if (window is DialogueWindow) {
            val dialogueElem = Element("dialogue")
            window.saveToXML(dialogueElem, refs)
            elem.addContent(dialogueElem)
        }
    }

    fun loadFromXML(elem: Element, refs: RefLoader) {
        val dialogueElem = elem.getChild("dialogue")
        if (dialogueElem != null) {
            currentWindow = DialogueWindow(game, ship, dialogueElem, refs, this::eventDialogueClosed)
        }
    }

    fun showGameOverScreen(outcome: GameOverWindow.Outcome) {
        game.browserProgress.finish(outcome)
        game.browserRunEnded = true
        game.mainGame.deleteRunSave()
        currentWindow = GameOverWindow(game, outcome)
    }

    // The door cursor's animation clock (see getCurrentCursor), restarted for each door hovered.
    private var doorCursorDoor: xyz.znix.xftl.layout.Door? = null
    private var doorCursorTime = 0f

    fun getCurrentCursor(): Cursor {
        // CommandGui::RenderStatic (0x535070) calls MouseControl::ResetArmed under an open focus
        // window, so no crosshair, room-click or door cursor shows over it; only the window's own
        // buttons and choices mark the pointer valid (GenericButton::MouseMove 0x58dd90,
        // ChoiceBox::MouseMove 0x4f6ab0).
        (pauseWindow ?: currentWindow)?.let { return if (it.cursorValid) cursorHover else cursorNormal }

        // Use the hacking/teleport/mind control cursor
        (game.clickEvent as? RoomClickCursor)?.getCursor()?.let { return it }

        targetingSelectedWeapon?.let { id ->
            // MouseControl's valid flag: an own-ship room is only a target for a weapon that
            // can aim at its own ship (the CanTargetSelf check in CrewControl's mouse move 0x5052c0).
            val room = game.hoveredRoom
            val weapon = ship.hardpoints.getOrNull(id)?.weapon
            val valid = room != null && (room.ship != ship || weapon?.canTargetOwnShip == true)
            val autofire = isAimingAutofire()

            // Combine the cursor and crosshairs icon
            val baseName = when {
                valid -> "img/mouse/mouse_crosshairs.png"
                autofire -> "img/mouse/mouse_crosshairs_valid2.png"
                else -> "img/mouse/mouse_crosshairs_valid.png"
            }
            val overlayName = when {
                autofire -> "img/mouse/mouse_crosshairs3_${id + 1}.png"
                else -> "img/mouse/mouse_crosshairs2_${id + 1}.png"
            }
            return game.getCursor("$baseName,$overlayName")
        }

        // CrewControl's mouse move (0x5052c0) hands MouseControl the door under the mouse when no
        // crew is selected, and MouseControl::SetDoor (0x4e7700) plays mouse_door_strip.png
        // (5 frames in 0.65s, looped with a 0.5s delay, 0x4e7780), backwards over an open door.
        val door = if (selectedCrew.isEmpty()) ship.doors.firstOrNull { it.isHovered } else null
        if (door != null) {
            if (door !== doorCursorDoor) {
                doorCursorDoor = door
                doorCursorTime = 0f
            }
            val cycle = doorCursorTime % 1.65f
            val forward = when {
                cycle < 0.65f -> (cycle / 0.13f).toInt().coerceAtMost(4)
                cycle < 1.15f -> 4
                else -> 0
            }
            val frame = if (door.open) 4 - forward else forward
            return game.getCursor("img/mouse/mouse_door_strip.png#$frame")
        }
        doorCursorDoor = null

        return when {
            buttons.any { it.hovered } -> cursorHover
            hoveredCrew.isNotEmpty() -> cursorHover
            else -> cursorNormal
        }
    }

    fun onTextInput(key: Int, c: Char): Boolean {
        pauseWindow?.let { return it.onTextInput(key, c) }
        currentWindow?.let { return it.onTextInput(key, c) }

        return false
    }

    // WeaponControl::CreateAutofireButton (00568c70), relative to its frame.
    private fun autofireButtonPos(weaponBoxX: Int): ConstPoint {
        val slots = ship.weaponSlots ?: return ConstPoint(1125, 600)
        val barX = weaponBoxX + slots * 97 + 20 - font.getWidth(game.translator["button_autofire"]) - 8
        val barY = weaponBoxY + 63

        if (!PlatformSpecific.INSTANCE.isTouchUi)
            return ConstPoint(barX, barY)

        val (screenX, screenY) = barToScreen(barX, barY)
        return ConstPoint(screenX, screenY)
    }

    private inner class AutofireButton(pos: ConstPoint) : Button(game, pos, ConstPoint(font.getWidth(game.translator["button_autofire"]) + 8, 24)) {
        private val label = game.translator["button_autofire"]
        private val textWidth = font.getWidth(label)

        private val tooltip = object : StandardTooltip(game) {
            override fun getText(): String {
                // Desktop uses the generic tooltip with both current bindings;
                // autofire_tooltip_on/off are the separate iPad strings.
                return game.translator["tooltip_autofire"]
                    .replace("\\1", game.getButtonNameForHotkey(VanillaHotkeys.WEAPON_AUTOFIRE_TOGGLE, ""))
                    .replace("\\2", game.getButtonNameForHotkey(VanillaHotkeys.WEAPON_AUTOFIRE_TARGET, ""))
            }
        }
        val tooltipText: String get() = tooltip.getText()

        override fun draw(g: Graphics) {
            val img = game.getImg("img/box_weapons_autofire_base.png")
            val x = pos.x.f
            val y = pos.y - 10f
            img.draw(x - 11f, y, x + 6f, y + 50f, 0f, 0f, 17f, 50f)
            img.draw(x + 6f, y, x + 6f + textWidth - 3f, y + 50f, 17f, 0f, 18f, 50f)
            img.draw(x + size.x - 5f, y, x + size.x + 14f, y + 50f, 18f, 0f, 37f, 50f)
            val idle = if (ship.weapons?.autofire == true) TEXT_BUTTON_SELECTED else TEXT_BUTTON_DISABLED
            val fill = if (hovered) Colour.lerp(idle, Colour.white, 0.5f) else idle
            Buttons.drawTextButton(g, pos.x, pos.y, size.x, size.y, 4, label, font, fill)

            if (hovered)
                g.tooltip = tooltip
        }

        override fun click(button: Int) {
            // The desktop button changes the global firing mode immediately.
            toggleAutofire()
        }
    }

    private abstract inner class WeaponDroneButton(pos: IPoint, val slotNumber: Int, size: ConstPoint) :
        Button(game, pos, size) {

        /**
         * The system this item belongs to (weapons or drones), used by the
         * touch power popup to find the item toggles for the tapped system.
         */
        open val ownerSystem: MainSystem? = null
        open val tutorialPowerFlashAlpha: Float = 0f

        abstract val empty: Boolean
        abstract val name: String
        abstract val chargeTime: Float
        abstract val chargeProgress: Float
        abstract val requiredPower: Int
        abstract val zoltanPower: Int
        abstract val isPowered: Boolean
        abstract val isCharged: Boolean
        abstract val isTargeted: Boolean
        abstract val isSelectingTarget: Boolean

        /**
         * True if the weapon being aimed would be set to fire by itself: the
         * ship's autofire setting reversed while the modifier key is held
         * (WeaponBox +0x1c1, set by WeaponControl::OnLoop 0x56c1a0).
         */
        open val isSelectingAutofire: Boolean get() = false

        /**
         * True if the weapon already aimed at a target fires there by itself
         * (ProjectileFactory::autoFiring, read by WeaponBox::RenderBox1 0x507a00).
         */
        open val isTargetAutofire: Boolean get() = false

        abstract val hasChargeBar: Boolean
        abstract val isBeingHacked: Boolean
        abstract val droneCooldownProgress: Float?
        abstract val tooltip: ITooltipProvider?

        override val disabled: Boolean get() = empty

        /**
         * Toggle this item's power for the touch power popup - tap the
         * weaponpower toggle to switch it on/off. [click] is protected, so
         * the popup goes through this wrapper.
         */
        fun popupTogglePower() {
            click(
                if (isPowered) Input.MOUSE_RIGHT_BUTTON
                else Input.MOUSE_LEFT_BUTTON
            )
        }

        protected val mainColour: Colour
            get() = when {
                // ArmamentBox tints an empty slot's outline 0.745 (190 grey).
                empty -> WEAPONS_ITEM_EMPTY
                isBeingHacked -> SYSTEM_HACKED
                droneCooldownProgress != null -> WEAPONS_ITEM_DRONE_COOLDOWN
                !isPowered -> WEAPONS_ITEM_DESELECTED
                isSelectingTarget && isSelectingAutofire -> WEAPONS_ITEM_AUTOFIRE
                isSelectingTarget -> WEAPONS_ITEM_TARGETING
                isCharged -> WEAPONS_ITEM_CHARGED
                else -> WEAPONS_ITEM_SELECTED
            }

        private var hackingSparks: FTLAnimation? = null
        private var hackingOffsetX: Int = 0
        private var hackingMaskY: Int = 0
        private var hackingMirror: Boolean = false
        private var hackingLastNS: Long = 0

        // Move these out to save a few allocations
        // (it's probably well into paranoid territory, but why not)
        private val nameLines by lazy { weaponNameText.wrapString(name, if (ownerSystem is Weapons) 55 else 58) }
        private val weaponNumberString = (slotNumber + 1).toString()

        override fun draw(g: Graphics) {
            if (hovered) {
                g.tooltip = tooltip

                // Draw the glow behind everything else
                game.getImg("img/bar_weapon_minibox_glow.png").draw(pos.x - 18, pos.y - 10)
            }

            // Draw the drone cooldown progress, if applicable
            val cooldownProgress = droneCooldownProgress
            if (cooldownProgress != null) {
                val height = (size.y * cooldownProgress).roundToInt()
                g.colour = DRONE_COOLDOWN_BACKGROUND
                g.fillRect(pos.x, pos.y + size.y - height, size.x, height)
            }

            // ArmamentBox's backing: (100,100,100) at 25% under the slot; an empty
            // slot's backing starts 8px further left (measured against the original).
            g.colour = WEAPONS_ITEM_BACKING
            if (empty) g.fillRect(pos.x - 8, pos.y, size.x + 8, size.y)
            else g.fillRect(pos.x, pos.y, size.x, size.y)

            // Draw the outline box
            g.colour = mainColour
            g.drawRect(pos.x.f, pos.y.f, (size.x - 1).f, (size.y - 1).f)
            g.drawRect((pos.x + 1).f, (pos.y + 1).f, (size.x - 3).f, (size.y - 3).f)

            if (empty)
                return

            if (hasChargeBar) {
                val maxBarSize = 35 - 2
                val barSize = (maxBarSize * chargeTime / maxWeaponChargeTime).toInt()

                // The Y position of the inside of the charge bar, relative to the main weapons box
                val top = maxBarSize - barSize

                // The top point of the triangle
                val triangleTop = top - 7

                for (j in 8 downTo 1) {
                    val triangleY = j + triangleTop
                    if (triangleY < 0)
                        continue
                    val y = pos.y + triangleY
                    g.drawLine((pos.x - j).f, y.f, pos.x.f, y.f)
                }

                if (isTargeted)
                    g.colour = if (isTargetAutofire) WEAPONS_ITEM_AUTOFIRE else WEAPONS_ITEM_TARGETING

                val chargePx = (barSize * chargeProgress).toInt()
                g.fillRect((pos.x - 5).f, (pos.y + 36 - chargePx).f, 4f, chargePx.f)

                g.colour = mainColour

                g.lineWidth = 2f
                g.drawLine(pos.x - 7.5f, pos.y.f + top.f + 1.5f, pos.x - 7.5f, pos.y + 39 - 1.5f)
                g.drawLine(pos.x - 7.5f, pos.y + 39 - 1.5f, pos.x - 0.5f, pos.y + 39 - 1.5f)
                g.lineWidth = 1f
            }

            // Draw the weapon number box
            val numBoxX = pos.x + size.x - 12
            val numBoxY = pos.y + size.y - 15
            g.lineWidth = 2f
            g.drawLine(numBoxX + 0.5f, numBoxY + 0.5f, numBoxX + 0.5f, numBoxY + 12f + 0.5f)
            g.drawLine(numBoxX + 0.5f, numBoxY + 0.5f, numBoxX + 10f + 0.5f, numBoxY + 0.5f)
            g.lineWidth = 1f

            // Draw the weapon/drone number itself
            val weaponNumberWidth = weaponNumberFont.getWidth(weaponNumberString)
            weaponNumberFont.drawString(
                (numBoxX + 2 + 1 + (8 - weaponNumberWidth) / 2).f,
                pos.y + size.y - 4f,
                weaponNumberString,
                mainColour
            )

            // Draw the item name, which is split across
            // multiple lines to fit in the box.
            var lineY = pos.y + 15
            for (line in nameLines) {
                weaponNameText.drawString(pos.x + 26f, lineY.f, line, mainColour)
                lineY += 15
            }

            for (bar in 0 until requiredPower) {
                val y = pos.y + size.y - 11 - bar * 8

                // Native ArmamentBox renders the red pulse below ordinary power
                // indicators: unpowered outlines reveal it, powered bars cover it.
                val flash = tutorialPowerFlashAlpha
                if (flash > 0f) {
                    g.colour = Colour(1f, 0f, 0f, flash)
                    g.fillRect((pos.x + 4).f, y.f, 16f, 7f)
                }

                if (zoltanPower > bar) {
                    g.colour = WEAPONS_ITEM_ENERGY_ZOLTAN
                } else if (droneCooldownProgress != null) {
                    g.colour = WEAPONS_ITEM_DRONE_COOLDOWN
                    g.drawRect((pos.x + 4).f, y.f, (16 - 1).f, (7 - 1).f)
                    continue
                } else if (!isPowered) {
                    g.colour = WEAPONS_ITEM_ENERGY_UNPOWERED
                    g.drawRect((pos.x + 4).f, y.f, (16 - 1).f, (7 - 1).f)
                    continue
                } else if (isBeingHacked) {
                    g.colour = SYSTEM_HACKED
                } else if (isSelectingTarget) {
                    g.colour = if (isSelectingAutofire) WEAPONS_ITEM_AUTOFIRE else WEAPONS_ITEM_TARGETING
                } else if (isCharged) {
                    g.colour = WEAPONS_ITEM_ENERGY_CHARGED
                } else {
                    g.colour = WEAPONS_ITEM_ENERGY_POWERED
                }
                g.fillRect((pos.x + 4).f, y.f, 16f, 7f)
            }

            drawHackingSparks()
        }

        private fun drawHackingSparks() {
            if (!isBeingHacked) {
                hackingSparks = null
                return
            }

            val currentNS = System.nanoTime()
            val dt = (currentNS - hackingLastNS) / 1_000_000_000f
            hackingLastNS = currentNS

            if (hackingSparks == null || hackingSparks?.isStopped == true) {
                hackingSparks = ship.sys.animations["stun_spark_big"].startSingle(game)

                hackingOffsetX = VisualRandom.nextInt(25)
                hackingMaskY = VisualRandom.nextInt(31)
                hackingMirror = VisualRandom.nextBoolean()
            }

            // Update the animation regardless of whether we're paused.
            hackingSparks!!.update(dt)

            val frame = hackingSparks!!.currentFrame
            val cutoutWidth = 69
            val cutoutHeight = 39
            frame.draw(
                // Screen points
                pos.x.f + hackingOffsetX, pos.y.f,
                pos.x.f + hackingOffsetX + cutoutWidth, pos.y.f + cutoutHeight,

                // Image points
                if (hackingMirror) cutoutWidth.f else 0f, hackingMaskY.f,
                if (hackingMirror) 0f else cutoutWidth.f, hackingMaskY.f + cutoutHeight
            )
        }
    }

    /**
     * This is the button that adjusts a system's power.
     */
    private inner class SystemPowerButton(pos: IPoint, val system: AbstractSystem) :
        Button(game, pos, ConstPoint(26, 26)) {

        // Subsystems can't be clicked, so don't play their hover noise.
        // This will also keep hovered set to false, since we don't want
        // to activate any hovering-related stuff for something you can't click.
        override val disabled: Boolean get() = system !is MainSystem

        private val tooltip = system.createTooltip()

        override fun draw(g: Graphics) {
            system.drawIconAndPower(game, g, true, true, hovered, pos.x, pos.y)

            if (rawHovered) {
                g.tooltip = tooltip
            }
        }

        override fun click(button: Int) {
            // You can't adjust the power of subsystems
            if (system !is MainSystem)
                return

            if (button == Input.MOUSE_LEFT_BUTTON) {
                // Touch UI: tapping a system opens the iPad-style power
                // popup instead of directly adding a power bar. Weapons are
                // excluded - their individual boxes are already directly
                // tappable, which is the better way to manage them.
                if (PlatformSpecific.INSTANCE.isTouchUi && system !is Weapons) {
                    togglePowerPopup(system, pos)
                    return
                }
                // SystemBox::MouseClick1 (0x004da730): the last bar of an odd shield level needs an upgrade.
                if (system is Shields && system.powerSelected == system.undamagedEnergy - 1 && system.energyLevels % 2 == 1)
                    shieldUpgradeWarning.startFor(WARNING_TIME)
                changeSystemPower(system, true)
            } else if (button == Input.MOUSE_RIGHT_BUTTON) {
                changeSystemPower(system, false)
            }
        }
    }

    /**
     * A version of [RoomClickListener] that also sets the mouse cursor.
     */
    private interface RoomClickCursor : RoomClickListener {
        fun getCursor(): Cursor?
    }

    private inner class TeleportRoomListener(val send: Boolean) : RoomClickCursor {
        override fun roomClicked(room: Room, gc: GameContainer) {
            // Can't teleport to/from our own ship
            if (room.ship == ship)
                return

            ship.teleporter!!.selectTeleportAction(send, room)
        }

        override fun getCursor(): Cursor {
            val valid = game.hoveredRoom != null

            return when {
                send && valid -> cursorTeleSendValid
                send -> cursorTeleSendInvalid
                valid -> cursorTeleRetrieveValid
                else -> cursorTeleRetrieveInvalid
            }
        }

        override fun canTargetRoom(room: Room): Boolean {
            // We can only teleport to/from the enemy ship
            return room.ship != ship
        }
    }

    private inner class HackingRoomListener : RoomClickCursor {
        override fun roomClicked(room: Room, gc: GameContainer) {
            // Can't hack our own ship
            if (room.ship == ship)
                return

            ship.hacking!!.selectTarget(room)
        }

        override fun getCursor(): Cursor {
            return cursorHacking
        }
    }

    private inner class MindControlRoomListener : RoomClickCursor {
        override fun roomClicked(room: Room, gc: GameContainer) {
            ship.mindControl!!.selectRoom(room)
        }

        override fun getCursor(): Cursor {
            return when {
                game.hoveredRoom?.ship == game.enemy -> cursorMindValid
                else -> cursorMindInvalid
            }
        }
    }

    private inner class ResourceDeltaText(var x: Float, var y: Float, amount: Int) {
        private var age: Float = 0f

        private val text: String = when {
            amount > 0 -> "+$amount"
            else -> amount.toString()
        }

        val finished: Boolean get() = age >= 1f

        init {
            x -= numberDeltaFont.getWidth(text) / 2f
            y += numberDeltaFont.baselineToTop * numberDeltaFont.scale
        }

        fun draw(g: Graphics) {
            numberDeltaFont.drawStringPaired(numberDeltaFontBg, x, y, text, Colour.black, Colour.white)
        }

        fun update(dt: Float) {
            y += 15f * dt
            age += dt
        }
    }

    companion object {
        /** CommandGui::RenderStatic's focus tint (0x0087d610). */
        @JvmField
        val FOCUS_TINT = Colour(0.15f, 0.15f, 0.15f, 1f)

        /**
         * How much selected crew are enlarged by in the touch UI's
         * room-selection mode (see [crewEnlargeScale]).
         */
        const val ENLARGED_CREW_SCALE = 2f

        /**
         * The diagonal distance (squared) that the crew selection box has to be before it appears. If the
         * player drags out an area smaller than this, it'll be treated as a click and select whoever is
         * standing underneath. CrewControl's mouse move (0x5052c0) keeps point selection below 82.
         */
        const val SELECTION_BOX_SIZE = 82

        /**
         * This is the font used for truncating crew names, so expose it here.
         */
        const val CREW_NAME_FONT = "JustinFont8"

        private const val INSUFFICIENT_SCRAP_FLASH_TIME = 0.35f
        private const val WEAPON_BOX_GLOW = 12

        /** ENGINES CRITICAL's line points from its origin (0x00558690). */
        private val ENGINE_WARNING_LINES = listOf(ConstPoint(9, 11), ConstPoint(64, 66), ConstPoint(155, 66))

        /** How long the click warnings (WarningMessage::InitText time 2.0) stay up. */
        private const val WARNING_TIME = 2f

        /** SystemControl's NOT ENOUGH POWER time (InitText 0x004c9380). */
        private const val NO_POWER_TIME = 2.31f

        private const val MIN_BEAM_LENGTH = 10f

        private const val CREW_BOX_WIDTH = 86
        private const val CREW_BOX_HEIGHT = 27
        // CrewBox's power bar colour (runtime DAT_00915d00), as captured from the original.
        private val LOCKDOWN_CHARGE = Colour(133, 231, 237)
        private const val CREW_BOX_SPACING = 30
    }
}
