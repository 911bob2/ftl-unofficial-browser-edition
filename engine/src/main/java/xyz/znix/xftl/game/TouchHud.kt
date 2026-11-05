package xyz.znix.xftl.game

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Constants
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.f
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.rendering.StandardTooltip
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.BackupBattery
import xyz.znix.xftl.systems.Doors
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.Hacking
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Piloting
import xyz.znix.xftl.systems.Sensors
import xyz.znix.xftl.systems.Teleporter
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.Utils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The flight HUD and touch controls of the touch layout (TouchMode): one primary finger, the widgets in a 1024×768
 * block. The game state underneath is the ordinary one: selection, power, weapons and doors go through PlayerShipUI's
 * own operations, so hotkeys and saves behave the same. Coordinates are in the HUD block unless named canvas.
 */
class TouchHud(private val ui: PlayerShipUI, private val game: InGameState) : TouchRouter.Hud {
    private val ship get() = ui.ship
    private val hudX get() = TouchMode.hudX

    private fun img(path: String): Image = game.getImg("img/$path")

    // ---- ship focus: T = 1 the player's ship is large, T = 0 the enemy's ------------------------------------------
    private var focusOnPlayer = true
    private var focusT = 1f
    private var lastEnemy: Any? = null

    private val hasTarget get() = game.enemy != null

    val playerScale: Float get() = 0.75f + 0.25f * focusT
    val enemyScale: Float get() = 0.75f + 0.25f * (1f - focusT)
    val playerOnTop: Boolean get() = focusT > 0.5f
    val focusAmount: Float get() = focusT

    // What the tutorial watches.
    val doorsSelected: Boolean get() = selected?.system is Doors
    val weaponsSelected: Boolean get() = selected?.system is Weapons

    /** The open box has an info drawer open (as on the iPad, a box with its tooltip out counts). */
    val drawerOpened: Boolean get() = selected?.let { box -> allDrawers(box).any { it.isOpen } } == true

    /**
     * The tutorial's iPad lessons open a system's box (the box of that system is selected, as
     * on the iPad); [keep] holds it open against a finger elsewhere and the lift after a power drag, as the box's own flag
     * does there. Nothing happens when the ship has no box for [system].
     */
    fun tutorialOpenBox(system: AbstractSystem?, keep: Boolean) {
        val box = boxes().firstOrNull { it.system === system } ?: return
        if (selected !== box) closeBox()
        selected = box
        boxDragging = false
        keepOpen = keep
    }

    /** Every box closed and none kept open (as the iPad tutorial closes them all). */
    fun tutorialCloseBoxes() = closeBox()

    private var keepOpen = false
    private var strayFinger = false

    private fun updateFocus(dt: Float) {
        val enemy = game.enemy
        if (enemy !== lastEnemy) {
            lastEnemy = enemy
            focusOnPlayer = enemy == null
            focusT = if (focusOnPlayer) 1f else 0f
        }
        val goal = if (focusOnPlayer) 1f else 0f
        focusT = when {
            focusT < goal -> minOf(goal, focusT + dt / FOCUS_TIME)
            focusT > goal -> maxOf(goal, focusT - dt / FOCUS_TIME)
            else -> focusT
        }
    }

    private fun toggleFocus() {
        if (!hasTarget) return
        focusOnPlayer = !focusOnPlayer
    }

    /** The target box's rectangle at the current focus. */
    fun targetBox(boss: Boolean): IntArray {
        val t = focusT
        val w = (292 + (1 - t) * (if (boss) 250 else 140)).roundToInt()
        val h = (381 + (1 - t) * 153).roundToInt()
        val x = if (boss) 491 - 30 + (542 - w) else 491 + 80 + (432 - w)
        val y = 72 + (534 - h) / 2
        return intArrayOf(x, y, w, h)
    }

    /** The player's ship's origin on the canvas at the current focus: its scale goes about the hull's centre. */
    fun layOutPlayerShip(offset: Point, player: xyz.znix.xftl.Ship) {
        val shift = player.type.layoutShift
        val s = playerScale
        // As it shrinks the ship moves left, clear of the target box.
        val x = shift.x + 222 - 2 * (1 - s) * (290 - shift.x) + (1 - s) * player.hullCentreX
        val y = shift.y + 184 + (1 - s) * player.hullCentreY
        offset.x = hudX + x.roundToInt()
        offset.y = y.roundToInt()
    }

    // ---- buttons ---------------------------------------------------------------------------------------------------
    private inner class TouchButton(var x: Int, val y: Int, var w: Int, val h: Int) {
        var active = true
        var pressed = false
        var clicked = false
        fun contains(px: Int, py: Int) = px > x && px < x + w && py > y && py < y + h

        /** True while this button holds the finger. */
        fun touch(action: Int, px: Int, py: Int): Boolean {
            when (action) {
                TouchRouter.DOWN -> {
                    pressed = active && contains(px, py)
                    return pressed
                }
                TouchRouter.MOVE -> {
                    if (pressed && !contains(px, py)) pressed = false
                    return pressed
                }
                TouchRouter.UP -> {
                    val was = pressed
                    pressed = false
                    if (was && active && contains(px, py)) clicked = true
                    return was
                }
                else -> {
                    pressed = false
                    return false
                }
            }
        }

        fun consumeClick(): Boolean = clicked.also { clicked = false }
    }

    private val jumpButton = TouchButton(511, 0, 93, 54)
    private val shipButton = TouchButton(606, 0, 82, 63)
    private val storeButton = TouchButton(693, 0, 90, 53)
    private val optionsButton = TouchButton(968, 0, 56, 56)
    private val pauseButton = TouchButton(842, 686, 182, 82)
    private val hudButtons = listOf(jumpButton, shipButton, storeButton, optionsButton, pauseButton)
    private val storeHere get() = game.currentBeacon?.hasStore == true

    /** The desktop HUD's other buttons and Hyperspace's elements (TouchExtras). */
    val extras = TouchExtras(ui, game)

    /** Where the top row's buttons end: the hazards go between here and the options button. */
    private var topRowEnd = 0f

    private val labelFont = NativeFonts.font(game::getFont, 62)
    private val tabSmallFont = NativeFonts.font(game::getFont, 12)
    private val pauseFont = NativeFonts.font(game::getFont, 64)
    private val statusFont = NativeFonts.font(game::getFont, 61)
    private val numberFont = game.getFont("num_font")
    private val smallFont = NativeFonts.font(game::getFont, 5)
    private val crewNoteFont = game.getFont("JustinFont10")
    private val tooltipFont = game.getFont("JustinFont11Bold")
    private val slotFont = game.getFont("JustinFont8")
    private val autofireFont = NativeFonts.font(game::getFont, 62)

    private fun updateButtons() {
        val readyToJump = ship.isFtlReady && ship.canChargeFTL && !(ship.fuelCount == 0 && game.enemy != null) &&
            game.mainGame?.tutorialAdapter?.controller?.mapOpeningAllowed != false && !game.isPlayerJumping
        jumpButton.active = readyToJump
        shipButton.active = game.mainGame?.tutorialAdapter?.controller?.shipOpeningAllowed != false
        storeButton.active = storeHere
        // Multiverse blanks the wrench: a labelled button then stands in its place.
        val blank = img("statusUI/top_optionswrench_on.png").width <= 1
        optionsButton.x = if (blank) 910 else 968
        optionsButton.w = if (blank) 114 else 56
        layOutExtras()
    }

    /**
     * The top row as Hyperspace orders it (ShipStatus::OnLoop): jump, event buttons of location 1, ship, location 2, store,
     * location 3, then 4 and the other top-bar pieces, all smaller when they would reach the options button. Then the
     * rest of TouchExtras' places.
     */
    private fun layOutExtras() {
        extras.sync()
        extras.beginLayout()
        val zones = listOf(TouchExtras.Zone.BEFORE_SHIP, TouchExtras.Zone.AFTER_SHIP, TouchExtras.Zone.AFTER_STORE, TouchExtras.Zone.TOP_END)
        val need = zones.sumOf { extras.topWidth(it).toDouble() }.toFloat()
        val fixed = SHIP_STEP + if (storeHere) STORE_STEP else 0f
        val room = optionsButton.x - 8f - SHIP_X - fixed
        val scale = if (need > room && need > 0f) maxOf(0.5f, room / need) else 1f
        var x = extras.placeTop(TouchExtras.Zone.BEFORE_SHIP, SHIP_X.toFloat(), scale)
        shipButton.x = x.roundToInt()
        extras.placeTop(TouchExtras.Zone.SECOND_ROW, x, scale, 80f, 59f)
        x = extras.placeTop(TouchExtras.Zone.AFTER_SHIP, shipButton.x + SHIP_STEP, scale)
        if (storeHere) {
            storeButton.x = (x + 3).roundToInt()
            x = storeButton.x + STORE_STEP - 3
        }
        x = extras.placeTop(TouchExtras.Zone.AFTER_STORE, x, scale)
        topRowEnd = extras.placeTop(TouchExtras.Zone.TOP_END, x, scale)
        extras.placeRest(CREW_X, stationButtonsY + 50, CREW_X + 55 + 36, stationButtonsY, TouchMode.canvasW, hudX)
    }

    private fun doButtons() {
        if (shipButton.consumeClick()) {
            cancelSystemSelection()
            if (game.isInDanger && game.mainGame?.isTutorialRunning != true) ui.touchShipRefused() else ui.openUpgrades()
            return
        }
        if (storeButton.consumeClick()) {
            ui.openStore()
            return
        }
        if (optionsButton.consumeClick()) {
            ui.showPauseWindow()
            return
        }
        if (pauseButton.consumeClick()) {
            togglePause()
            return
        }
        if (jumpButton.consumeClick()) {
            cancelSystemSelection()
            ui.openJumpMap()
        }
    }

    // ---- pause and Touch Command Auto-Pause ------------------------------------------------------------------------
    private var autoPaused = false
    private var commandMode = false
    private var commandReason = 0
    private var pulseT = 0f
    private var pulseDir = 1f

    private fun togglePause() {
        if (game.isPlayerJumping) return
        val window = ui.isWindowOpen
        if (window && !ui.isEventOpen) return
        if (ui.isEventOpen && game.isPlayerPaused && !autoPaused) {
            autoPaused = true
            return
        }
        game.setPlayerPaused(if (window) true else !game.isPlayerPaused)
        autoPaused = false
    }

    private fun updateAutoPause() {
        val prev = commandMode
        commandMode = false
        if (game.mainGame?.profile?.touchAutoPause == true) {
            if (doorMode) {
                commandMode = true
                commandReason = REASON_DOORS
            }
            if (dragging && dragSeconds() > 0.1f) {
                commandMode = true
                commandReason = REASON_CREW_DRAG
            } else if (ui.touchSelected.isNotEmpty()) {
                commandMode = true
                commandReason = REASON_ROOM
            }
            if (isTargeting) {
                commandMode = true
                commandReason = ui.touchTargetingReason()
            }
            if (!prev && commandMode && !game.isPlayerPaused) {
                game.setPlayerPaused(true)
                autoPaused = true
            } else if (!commandMode) releaseAutoPause()
        } else releaseAutoPause()
    }

    private fun releaseAutoPause() {
        if (!ui.isEventOpen && autoPaused) {
            game.setPlayerPaused(false)
            autoPaused = false
        }
    }

    private val isTargeting get() = ui.touchIsTargeting

    private fun pausedLook() = game.isPlayerPaused || ui.isWindowOpen || commandMode

    private fun drawBanners() {
        if (!pausedLook()) return
        val pulse = pulseT * 0.3f / 0.75f + 0.6f
        val paused = game.isPlayerPaused || ui.isEventOpen
        if (paused) {
            val image = if (autoPaused) img("autopause.png") else img("ipad/pause.png")
            val y = 536 + if (ui.isEventOpen) 50 else 0
            image.draw(512f - image.width / 2f, y - image.height / 2f, pulse)
        }
        if (commandMode) {
            val image = img(when (commandReason) {
                REASON_CREW_DRAG -> "pause_crewselection.png"
                REASON_ROOM -> "pause_roomselection.png"
                REASON_DOORS -> "pause_doors.png"
                REASON_MIND -> "pause_mind.png"
                REASON_TELE_ARRIVE -> "pause_teleport_arrive.png"
                REASON_TELE_LEAVE -> "pause_teleport_leave.png"
                else -> if (ui.touchArmedIsBeam) "pause_target_beam.png" else "pause_target.png"
            })
            image.draw(512f - image.width / 2f, 599f - image.height / 2f, Colour(pulse, pulse, pulse, 1f))
        }
    }

    private fun advancePulse(dt: Float) {
        pulseT += pulseDir * dt
        if (pulseT > 0.75f) {
            pulseT = 1.5f - pulseT
            pulseDir = -1f
        } else if (pulseT < 0f) {
            pulseT = -pulseT
            pulseDir = 1f
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------------------
    /** The store button's look: a dark word on the light base, a highlight under it while pressed. */
    private fun drawTextTab(g: Graphics, x: Int, textKey: String, pressed: Boolean) {
        img("statusUI/top_store_base.png").draw(x.f, 1f)
        if (pressed) {
            g.colour = Constants.UI_BUTTON_HOVER
            Buttons.drawChamfer(g, x + 12, 13, 78, 31, 3)
        }
        // A long word (OPTIONS, some languages' STORE) takes the smaller font to stay on the tab.
        val text = game.translator[textKey]
        val font = if (labelFont.measure(text) > 78) tabSmallFont else labelFont
        font.printButtonLabel(x + 12, 13, 78, 31, text, Constants.JUMP_DISABLED_TEXT)
    }

    fun renderTopButtons(g: Graphics) {
        g.pushTransform()
        g.translate(hudX.f, 0f)
        drawJump(g)
        val ship = when {
            !shipButton.active -> "off"
            shipButton.pressed -> "select2"
            else -> "on"
        }
        img("statusUI/top_ship_$ship.png").draw(shipButton.x + 4f, 1f)
        ui.touchDrawInDanger(g)
        if (storeHere) {
            drawTextTab(g, storeButton.x - 6, "button_store", storeButton.pressed)
        }
        if (optionsButton.w == 56) {
            img(if (optionsButton.pressed) "statusUI/top_optionswrench_select2.png" else "statusUI/top_optionswrench_on.png").draw(968f, 1f)
        } else {
            drawTextTab(g, 910, "button_options", optionsButton.pressed)
        }
        img("ipad/pause_large_base.png").draw(845f, 689f)
        var colour = BUTTON_TEXT
        if (pauseButton.pressed) colour = BUTTON_PRESSED
        else if (pausedLook()) {
            val k = pulseT / (0.75f * 0.5f)
            val f = if (k <= 1f) k else 2f - k
            colour = Colour(BUTTON_TEXT.r * (1 - f) + PAUSE_FLASH.r * f, BUTTON_TEXT.g * (1 - f) + PAUSE_FLASH.g * f, BUTTON_TEXT.b * (1 - f) + PAUSE_FLASH.b * f, 1f)
        }
        g.colour = colour
        Buttons.drawChamfer(g, 859, 703, 140, 40, 4)
        pauseFont.printButtonLabel(859, 703, 140, 40, game.translator["button_pause"], Constants.JUMP_DISABLED_TEXT)
        g.popTransform()
    }

    private fun drawJump(g: Graphics) {
        val ready = ship.isFtlCharged
        val red = ui.touchEnginesWarningRed
        val piloting = ship.piloting
        val pilotOn = piloting != null && piloting.undamagedEnergy > 0 &&
            (ship.isAutoScout || ship.friendlyCrew.any { it.room == piloting.room })
        val enginesOn = (ship.engines?.powerSelected ?: 0) >= 1
        // The tab with the two lights comes out only to say what stops the jump.
        if (!pilotOn || !enginesOn) {
            img(if (red) "buttons/FTL/ftl_pullout_red.png" else "buttons/FTL/FTL_pullout.png").draw(520f, 42f)
            img(if (pilotOn) "buttons/FTL/FTL_pilot_on.png" else "buttons/FTL/FTL_pilot_off1.png").draw(534f, 59f)
            img(if (enginesOn) "buttons/FTL/FTL_engine_on.png" else "buttons/FTL/FTL_engine_off1.png").draw(561f, 58f)
        }
        if (red) img("buttons/FTL/FTL_base_red.png").draw(503f, -5f) else img("buttons/FTL/FTL_base.png").draw(509f, 1f)
        val canCharge = ship.canChargeFTL
        if (!ready) {
            val bars = img(if (canCharge) "buttons/FTL/FTL_loadingbars.png" else "buttons/FTL/FTL_loadingbars_off.png")
            val w = 74f * ship.ftlChargeProgress
            bars.draw(521f, 14f, 521f + w, 14f + bars.height, 0f, 0f, w, bars.height.f)
            if (canCharge) {
                val blue = (pulseT / -0.75f + 1f) * 0.898f
                statusFont.printCenter(558f, 51f, game.translator["ftl_charging"], Colour(235 / 255f, 245 / 255f, blue, 1f))
            }
        } else {
            val hs = xyz.znix.xftl.hs.HS.active
            val noFuel = ship.fuelCount == 0 && game.enemy != null
            val down = !jumpButton.active && !noFuel
            g.colour = when {
                noFuel -> if (hs) xyz.znix.xftl.hs.render.HsColors.jump(6) else Constants.WARNING_COLOUR_RED
                hs -> xyz.znix.xftl.hs.render.HsColors.jump(if (down) 0 else if (jumpButton.pressed) 3 else 2)
                down -> Constants.JUMP_DISABLED
                jumpButton.pressed -> BUTTON_PRESSED
                else -> Constants.JUMP_READY
            }
            Buttons.drawChamfer(g, 521, 14, 74, 29, 3)
            val text = when {
                noFuel -> if (hs) xyz.znix.xftl.hs.render.HsColors.jump(7) else Constants.JUMP_DISABLED_TEXT
                hs -> xyz.znix.xftl.hs.render.HsColors.jump(if (down) 1 else if (jumpButton.pressed) 5 else 4)
                down -> Constants.JUMP_DISABLED_TEXT
                jumpButton.pressed -> Constants.JUMP_READY_TEXT_HOVER
                else -> Constants.JUMP_READY_TEXT
            }
            labelFont.printButtonLabel(521, 14, 74, 29, game.translator["button_jump"], text)
            if (noFuel) {
                g.colour = text
                g.fillRect(523f, 27f, 70f, 2f)
            }
            if (jumpButton.active) statusFont.printCenter(558f, 51f, game.translator["ftl_ready"], Colour(235 / 255f, 245 / 255f, 0f, 1f))
        }
    }

    private fun drawStatus(g: Graphics) {
        val hullFraction = ship.health.f / ship.maxHealth
        val hullRed = ui.touchHullWarningRed
        img(if (hullRed) "ipad/statusUI/top_hull_red.png" else "ipad/statusUI/top_hull.png").draw(-4f, -4f)
        val mask = img("ipad/statusUI/top_hull_bar_mask.png")
        val w = 360f * hullFraction
        val colour = when {
            ship.health < ship.maxHealth / 3 -> Constants.SHIP_HEALTH_LOW
            ship.health < ship.maxHealth * 2 / 3 -> Constants.SHIP_HEALTH_MED
            else -> Constants.SHIP_HEALTH_HIGH
        }
        mask.draw(9f, -4f, 9f + w, -4f + mask.height, 0f, 0f, mask.width * hullFraction, mask.height.f, 1f, colour)
        ui.touchUpdateHullWarnings(hullFraction, g)

        val shields = ship.shields
        val broken = shields != null && shields.energyLevels >= 2 && shields.energyLevels - shields.damagedEnergyLevels < 2
        val hacked = shields?.isHackActive == true
        img(when {
            hacked -> "statusUI/top_shields4_purple.png"
            broken -> "ipad/statusUI/top_shields4_red.png"
            shields == null || shields.selectedShieldBars == 0 -> "ipad/statusUI/top_shields4_off.png"
            else -> "ipad/statusUI/top_shields4_on.png"
        }).draw(-4f, 20f)
        if (shields != null) {
            ShieldSquares.render(game, g, shields.activeShields, shields.selectedShieldBars, hacked, 3f, 24f, 6f, 56f)
            when {
                ship.superShield != 0 -> {
                    g.colour = Constants.SYS_ENERGY_ACTIVE
                    var x = 6
                    for (i in 0 until ship.superShield) {
                        val width = if (i == 2) 16 else 17
                        g.fillRect(x.f, 56f, width.f, 6f)
                        x += width + 2
                    }
                }
                shields.rechargeTimer != 0f -> {
                    g.colour = if (hacked) Constants.SHIELD_BAR_HACKED else Constants.SHIELD_BAR_NORMAL
                    g.fillRect(6f, 56f, 92f * (shields.rechargeTimer / shields.rechargeDelay).coerceIn(0f, 1f), 6f)
                }
            }
        }
        if (shields != null) img("statusUI/energy_shield_box.png").draw(3f, 53f)
        ui.touchUpdateShieldWarning(broken, shields != null, g)

        fun counter(name: String, x: Float, textX: Float, count: Int, redAt: Int) {
            img("ipad/statusUI/top_${name}_on${if (count <= redAt) "_red" else ""}.png").draw(x, 20f)
            numberFont.drawStringCentred(textX, 35f + 8f, 0f, count.toString(), Constants.UI_SCRAP_TEXT_COLOUR)
        }
        counter("fuel", 101f, 146f, ship.fuelCount, 3)
        counter("missiles", 161f, 206f, ship.missilesCount, 0)
        counter("drones", 221f, 266f, ship.dronesCount, 0)

        val avgOxygen = Math.round(ship.averageOxygen * 100)
        val oxygenRed = avgOxygen < 25 && ship.oxygen != null && !ship.type.name.contains("ANAEROBIC") && ui.touchOxygenWarningRed(g)
        val evadeRed = ship.evasion == 0
        img(when {
            evadeRed && oxygenRed -> "ipad/statusUI/top_evade_oxygen_both_red.png"
            evadeRed -> "ipad/statusUI/top_evade_oxygen_up_red.png"
            oxygenRed -> "ipad/statusUI/top_evade_oxygen_down_red.png"
            else -> "ipad/statusUI/top_evade_oxygen.png"
        }).draw(280f, 20f)
        fun percent(y: Float, value: String) {
            val x = (if (value.startsWith("1")) 335 else 333) - value.length * 6
            smallFont.print(x.f, y, value, BUTTON_TEXT)
        }
        percent(26f, "${ship.evasion}")
        percent(41f, "$avgOxygen")

        val scrapRed = ui.touchScrapRed
        img(if (scrapRed) "statusUI/top_scrap_red.png" else "statusUI/top_scrap.png").draw(378f, 0f)
        numberFont.drawStringCentred(460f, 23f + 8f, 0f, ship.scrap.toString(), Constants.UI_SCRAP_TEXT_COLOUR)
    }

    // ---- crew list ---------------------------------------------------------------------------------------------------
    private fun crewBoxY(index: Int) = CREW_TOP + CREW_SPACING * index
    private val boxCrew: List<LivingCrew> get() = ui.touchBoxCrew
    private val stationButtonsY: Int get() = crewBoxY(boxCrew.size - 1) + CREW_H + 10
    private var stationMessage = 0f
    private var stationMessageKey = ""
    private var assignPressed = false
    private var returnPressed = false

    private fun drawCrew(g: Graphics) {
        ui.touchClearPowerButtons()
        val tooltip = g.tooltip
        val lifted = reorderCrew
        for ((i, crew) in boxCrew.withIndex()) if (crew !== lifted) ui.touchDrawCrewBox(g, crew, CREW_X, crewBoxY(i))
        // The crew member a held finger picked up follows it up and down the list (CrewBox::OnRender hook).
        if (lifted != null && lifted in boxCrew) ui.touchDrawCrewBox(g, lifted, CREW_X, reorderFingerY - CREW_H / 2)
        // A crew box's tooltip lasts as long as the finger stays down.
        if (primary == -1) g.tooltip = tooltip
        if (boxCrew.isEmpty()) return
        val y = stationButtonsY
        val base = img("ipad/statusUI/button_station_base.png")
        base.draw(CREW_X + 5f - 12, y - 12f)
        base.draw(CREW_X + 55f - 12, y - 12f)
        img("ipad/statusUI/button_station_assign_${if (assignPressed) "select2" else "on"}.png").draw(CREW_X + 5f, y.f)
        img("ipad/statusUI/button_station_return_${if (returnPressed) "select2" else "on"}.png").draw(CREW_X + 55f, y.f)
        if (stationMessage > 0f) {
            stationMessage -= game.renderingDeltaTime
            crewNoteFont.drawString(CREW_X.f, y + 38f + 10f, game.translator[stationMessageKey], Colour.white)
        }
    }

    private fun stationButtonAt(x: Int, y: Int): Int {
        if (boxCrew.isEmpty()) return 0
        val top = stationButtonsY - 10
        if (y <= top || y >= top + 49) return 0
        if (x > CREW_X + 5 - 10 && x < CREW_X + 5 + 36) return 1
        if (x > CREW_X + 55 - 10 && x < CREW_X + 55 + 36) return 2
        return 0
    }

    // ---- crew control: paint to select, lift on a room to move ------------------------------------------------------
    private var dragging = false
    private var dragStartNanos = 0L
    private var selectOnPlayer = true
    private var touchedBox = -1
    private var shipHit = false

    private fun dragSeconds() = (System.nanoTime() - dragStartNanos) / 1e9f

    /** The crew draw and hit size: twice as large while a selecting finger stays on their ship past 0.1 s. */
    fun crewScale(crew: AbstractCrew): Float {
        if (!dragging || dragSeconds() <= 0.1f || !shipHit) return 1f
        val onPlayer = crew.room.ship === ship
        return if (onPlayer == selectOnPlayer) 2f else 1f
    }

    private fun crewAt(cx: Int, cy: Int): AbstractCrew? {
        var best: AbstractCrew? = null
        var bestDist = Float.MAX_VALUE
        for (crew in ui.touchControllableCrew) {
            if (!crew.playerControllable) continue
            val onPlayer = crew.room.ship === ship
            if (onPlayer != selectOnPlayer) continue
            val half = (crewScale(crew) * 35).roundToInt() / 2
            val p = ui.touchCrewCentre(crew)
            val dx = abs(cx - p.x)
            val dy = abs(cy - p.y)
            if (dx <= half && dy <= half) {
                val d = (dx * dx + dy * dy).f
                if (d < bestDist) {
                    bestDist = d
                    best = crew
                }
            }
        }
        return best
    }

    // ---- Hyperspace's crew list reorder on touch: hold a crew box, then slide it up or down ---------------------------
    // The desktop drags a box with the right button (CustomCommandGui.cpp: CrewControl::RButton picks the crew member up,
    // MouseMove moves it to the box under the mouse with CrewMemberFactory::MoveCrewMemberToSpot, the button's release
    // drops it). Touch has no right button: a finger held still on a box for REORDER_HOLD picks it up; then the same
    // move runs for the box under the finger. Until the finger moves the crew member to another box, the touch does what
    // it always did (lifted, the box is selected; its tooltip shows while held), so a long press to read a box still works.

    /** The box a finger went down on, while it may still become a hold (-1: none). */
    private var reorderBox = -1
    private var reorderDownNanos = 0L
    private var reorderDownX = 0
    private var reorderDownY = 0

    /** The finger's y while it holds a crew member. */
    private var reorderFingerY = 0

    /** The crew member a held finger picked up; null when none. */
    internal var reorderCrew: LivingCrew? = null
        private set

    /** For the checks: the crew list's names top to bottom, and the held crew member's. */
    val crewOrderNames: List<String> get() = boxCrew.map { it.info.name }
    val heldCrewName: String? get() = reorderCrew?.info?.name

    /** The held crew member moved to another box during this touch. */
    private var reordered = false

    /** Reorder's part of a crew list touch; true when it took the touch. */
    private fun reorderTouch(action: Int, x: Int, y: Int): Boolean {
        when (action) {
            TouchRouter.DOWN -> {
                reorderCrew = null
                reordered = false
                reorderBox = if (xyz.znix.xftl.hs.HS.active) boxCrew.indices.firstOrNull { i ->
                    val by = crewBoxY(i)
                    x > CREW_X && x < CREW_X + CREW_W && y > by && y < by + CREW_H
                } ?: -1 else -1
                reorderDownNanos = System.nanoTime()
                reorderDownX = x
                reorderDownY = y
                return false
            }
            TouchRouter.MOVE -> {
                val crew = reorderCrew
                if (crew == null) {
                    if (reorderBox >= 0 && Math.hypot((x - reorderDownX).toDouble(), (y - reorderDownY).toDouble()) > REORDER_SLOP)
                        reorderBox = -1
                    return false
                }
                reorderFingerY = y
                // CrewControl::MouseMove hook: (mY - top) / spacing, truncated towards zero, clamped to the boxes
                if (ui.touchCrewMoveToSpot(crew, (y - CREW_TOP) / CREW_SPACING)) {
                    reordered = true
                    ui.touchSkillsCrew = null
                    ui.touchPotential.clear()
                }
                return true
            }
            else -> {
                val took = reorderCrew != null && reordered
                reorderCrew = null
                reorderBox = -1
                if (!took) return false
                dragging = false
                touchedBox = -1
                ui.touchPotential.clear()
                ui.touchSkillsCrew = null
                return true
            }
        }
    }

    /** A finger held still on a crew box long enough picks its crew member up (from [update]). */
    private fun updateReorder() {
        if (reorderBox < 0 || reorderCrew != null || primary == -1) return
        if ((System.nanoTime() - reorderDownNanos) / 1e9f < REORDER_HOLD) return
        reorderCrew = boxCrew.getOrNull(reorderBox)
        reorderFingerY = reorderDownY
        reorderBox = -1
    }

    private fun crewControl(action: Int, x: Int, y: Int, cx: Int, cy: Int) {
        if (reorderTouch(action, x, y)) return
        if (action == TouchRouter.CANCEL) {
            dragging = false
            ui.touchPotential.clear()
            return
        }
        val selected = ui.touchSelected
        val room = roomUnderFinger(cx, cy)
        ui.touchHighlightRoom(if (action == TouchRouter.UP) null else if (selected.isNotEmpty()) room else null)

        if (selected.isNotEmpty()) {
            if (crystalTouch(action, cx, cy)) return
            if (action != TouchRouter.UP) return
            if (room != null) ui.touchMoveSelected(room)
            selected.clear()
            touchedBox = -1
            ui.touchSkillsCrew = null
            return
        }

        touchedBox = -1
        if (action == TouchRouter.DOWN) {
            ui.touchPotential.clear()
            dragging = true
            dragStartNanos = System.nanoTime()
            selectOnPlayer = when {
                !hasTarget -> true
                focusOnPlayer -> true
                else -> !targetBoxContains(x, y)
            }
        }
        if (!dragging) return
        var boxHit = false
        for ((i, crew) in boxCrew.withIndex()) {
            val by = crewBoxY(i)
            if (x > CREW_X && x < CREW_X + CREW_W && y > by && y < by + CREW_H) {
                touchedBox = i
                selectOnPlayer = crew.room.ship === ship
                boxHit = true
                // A crew member waiting in the clone queue can't be selected and shows no skills.
                if (crew.health <= 0f || ship.clonebay?.queue?.contains(crew) == true) continue
                if (crew.playerControllable) {
                    ui.touchPotential.clear()
                    ui.touchPotential += crew
                }
                ui.touchSkillsCrew = crew
            }
        }
        if (!boxHit) ui.touchSkillsCrew = null
        crewAt(cx, cy)?.let { if (it !in ui.touchPotential) ui.touchPotential += it }
        if (action == TouchRouter.UP) {
            dragging = false
            for (crew in ui.touchPotential) if (crew !in selected) selected += crew
            ui.touchPotential.clear()
        }
    }

    // The crystal crew's lockdown: a button on the room of a lone selected crystal, shown while the power is ready.
    private var crystalPressed = false

    private val crystalCrew: xyz.znix.xftl.crew.CrewCrystal?
        get() {
            if (xyz.znix.xftl.hs.HS.active) return null
            val crew = ui.touchSelected.singleOrNull() as? xyz.znix.xftl.crew.CrewCrystal ?: return null
            return crew.takeIf { it.playerControllable && it.lockdownReady && it.stunTime <= 0f }
        }

    private fun overCrystal(crew: xyz.znix.xftl.crew.CrewCrystal, cx: Int, cy: Int): Boolean {
        val c = ui.touchRoomCentre(crew.room)
        return cx >= c.x - 26 && cx < c.x + 26 && cy >= c.y - 26 && cy < c.y + 26
    }

    /** True while the lockdown button holds the finger. */
    private fun crystalTouch(action: Int, cx: Int, cy: Int): Boolean {
        val crew = crystalCrew
        if (crew == null) {
            crystalPressed = false
            return false
        }
        when (action) {
            TouchRouter.DOWN -> crystalPressed = overCrystal(crew, cx, cy)
            TouchRouter.UP -> if (crystalPressed) {
                crystalPressed = false
                if (overCrystal(crew, cx, cy)) {
                    crew.activateLockdown()
                    ui.touchSelected.clear()
                    touchedBox = -1
                    ui.touchSkillsCrew = null
                }
                return true
            }
            TouchRouter.CANCEL -> crystalPressed = false
        }
        return crystalPressed
    }

    private fun drawCrystal() {
        val crew = crystalCrew ?: return
        val c = ui.touchRoomCentre(crew.room)
        img("ipad/people/crystal_button_${if (crystalPressed) "select2" else "on"}.png").draw(c.x - hudX - 26f, c.y - 26f)
    }

    // The panel beside a touched crew box: Hyperspace's power buttons are on it.
    private var crewPanelFinger = false

    private fun overCrewPanel(x: Int, y: Int): Boolean {
        val i = boxCrew.indexOf(ui.touchSkillsCrew)
        if (i < 0) return false
        val by = crewBoxY(i)
        return x > CREW_X + 84 && x < CREW_X + 84 + 85 && y > by && y < by + 170
    }

    private fun crewPanelTouch(action: Int, x: Int, y: Int): Boolean {
        if (action == TouchRouter.DOWN) crewPanelFinger = overCrewPanel(x, y)
        if (!crewPanelFinger) return false
        if (action == TouchRouter.UP || action == TouchRouter.CANCEL) {
            crewPanelFinger = false
            val i = boxCrew.indexOf(ui.touchSkillsCrew)
            val fired = action == TouchRouter.UP && (ui.touchPowerButtonClick(x, y) ||
                (i >= 0 && ui.touchLockdownClick(ui.touchSkillsCrew, CREW_X, crewBoxY(i), x, y)))
            if (fired) {
                ui.touchSelected.clear()
                touchedBox = -1
                ui.touchSkillsCrew = null
            }
        }
        return true
    }

    private fun roomUnderFinger(cx: Int, cy: Int): Room? {
        if (!hasTarget || focusOnPlayer) ui.touchRoomOnPlayer(cx, cy)?.let { return it }
        if (hasTarget && !focusOnPlayer) return ui.touchRoomOnEnemy(cx, cy)
        return null
    }

    private fun targetBoxContains(x: Int, y: Int): Boolean {
        val enemy = game.enemy ?: return false
        val b = targetBox(enemy.isUsingBossUI)
        return x > b[0] && x < b[0] + b[2] && y > b[1] && y < b[1] + b[3]
    }

    private fun overSystemsForFinger(x: Int, y: Int): Boolean {
        val open = selected
        if (open != null) {
            if (extraAt(open, x, y) != null || open.hit(x, y)) return true
            val panelTop = open.buttonTops.firstOrNull()
            if (panelTop != null && x > open.x + 6 && x < open.x + 58 && y > panelTop && y < BOX_Y + 66) return true
            if (drawersOf(open).any { it.onTab(x, y) || it.onPanel(x, y) }) return true
            if (open.wide) {
                val lx = panelX(open)
                for (i in 0 until slotCount(open)) {
                    val sy = slotY(open, i)
                    if (x > lx - 78 && x < lx + 68 && y > sy && y < sy + 76) return true
                }
                val ay = autofireY(open)
                if (open.system is Weapons && x > open.x + 15 && x < open.x + 89 && y > ay && y < ay + 47) return true
            }
        }
        return boxes().any { it.hit(x, y) }
    }

    private fun overCrewArea(x: Int, y: Int): Boolean {
        if (boxCrew.isEmpty()) return false
        return x > CREW_X && x < CREW_X + CREW_W && y > crewBoxY(0) && y < crewBoxY(boxCrew.size - 1) + CREW_H
    }

    // ---- systems: a box per system, drag for power, flick for all or nothing ----------------------------------------
    private inner class Box(val system: AbstractSystem, val x: Int, val wide: Boolean) {
        val extras: List<Button> = system.makeExtraButtons(ConstPoint.ZERO)
        val isMain get() = system is MainSystem
        val tooltip = system.createTooltip()
        val drawer = Drawer(!isMain) { (tooltip as? StandardTooltip)?.getText() }
        val slotDrawers = ArrayList<Drawer>()
        val autofireDrawer = Drawer(false) {
            game.translator[if (ship.weapons?.autofire == true) "autofire_tooltip_on" else "autofire_tooltip_off"]
        }

        val rows: Int get() = system.energyLevels
        val raised: Boolean get() = wide && this === selected

        private val paired: Boolean get() = system is xyz.znix.xftl.systems.Shields

        /** The bars' height on the canvas (TouchPowerDrag): past eight levels the height of eight, so a 16-level shield does not reach the ship. */
        val barScale: Float get() = TouchPowerDrag.barScale(rows)

        /** One level's drag step on the canvas: the iPad's up to eight levels, the drawn bars' spacing past that. */
        val barStep: Float get() = TouchPowerDrag.step(rows, paired)

        /** From the bottom bar's top to the top bar's: the bars go in pairs on the shields box, with a gap between the pairs. */
        private val barsSpan: Int get() = TouchPowerDrag.span(rows, paired).roundToInt()

        /** The y of the top power bar. */
        val barsTop: Int get() = BOX_Y - 4 - (if (raised) 16 else 0) - barsSpan

        val mannable: Boolean get() = system.info.canBeManned
        val cooldownLevel: Int get() = when (system) {
            is xyz.znix.xftl.systems.Cloaking, is BackupBattery, is Hacking, is xyz.znix.xftl.systems.MindControl,
            is xyz.znix.xftl.systems.Artillery -> maxOf((system as? MainSystem)?.powerSupplied ?: 1, 1).coerceAtMost(4)
            else -> 0
        }

        /** The height the status rows above the base take, without the slack a mannable box always keeps. */
        val rowsHeight: Int get() {
            val used = cooldownLevel * 20 + (if (mannable) 16 else 0)
            val keep = if (!mannable) 0 else if (isMain) 24 else 16
            return maxOf(used, keep) - 4 + barsSpan
        }

        /** The stretch of the open panel above its bottom piece, without any button sections. */
        val baseHeight: Int get() = rowsHeight + if (wide) 78 else 62

        /** The heights of the panel's sections above its stretch, the bottom one first. */
        private val sections: List<Int> get() = when (system) {
            is Doors, is Teleporter -> listOf(56, 56)
            is xyz.znix.xftl.systems.Clonebay -> List(minOf(system.queue.size, 3)) { 34 }
            else -> if (extras.isEmpty()) emptyList() else listOf(56)
        }

        private val sectionsShare: Int get() = sections.sumOf { it + 8 }

        /** The tops of the sections, the top one first: the buttons or queue rows on the open panel go here. */
        val buttonTops: List<Int> get() {
            var y = PANEL_BASE - panelStretch
            return sections.map { h ->
                y -= 12 + h
                y
            }.asReversed()
        }

        private val drawerExcess: Int get() = if (drawer.open >= 1f) (drawer.h - baseHeight).coerceAtLeast(0) else 0

        /** The stretch of the panel: an open drawer taller than it grows it, buttons going up with it. */
        val panelStretch: Int get() {
            val excess = drawerExcess
            return if (sectionsShare < excess) baseHeight + excess - sectionsShare else baseHeight
        }

        /** The middle of the drawer's tab: centred on the panel, its bottom on the panel's base when it is the taller. */
        val drawerCentreY: Int get() {
            return PANEL_BASE - panelStretch / 2 - minOf(sectionsShare, drawerExcess) / 2
        }

        val panelButtons: List<Button> get() = when (system) {
            is Teleporter -> extras.drop(1)
            is xyz.znix.xftl.systems.Clonebay -> emptyList()
            else -> extras
        }

        fun hit(px: Int, py: Int): Boolean {
            val w = if (wide) 90 else 52
            val top = barsTop - (if (mannable) 16 else 0) - cooldownLevel * 20 + 16
            return px > x + 6 && px < x + 6 + w && py > top && py < BOX_Y + 66
        }

        /** How far each slot's power tab has slid out, 0 to 1. */
        val tabSlide = HashMap<Int, Float>()

        fun slotDrawer(i: Int): Drawer {
            while (slotDrawers.size <= i) {
                val index = slotDrawers.size
                slotDrawers += Drawer(false) { slotText(this, index) }
            }
            return slotDrawers[i]
        }
    }

    /** The slide-out information panel of a system box, a weapon or drone slot, or AUTO FIRE. */
    private inner class Drawer(val mirrored: Boolean, val text: () -> String?) {
        var open = 0f
        var isOpen = false
        var dragging = false
        var tapped = false
        var dragStart = 0f
        var dragX = 0
        var lastDelta = 0f
        var w = 0
        var h = 0
        var lines: List<String> = emptyList()
        var anchorX = 0
        var anchorY = 0

        fun refresh() {
            val value = text()
            if (value == null) {
                lines = emptyList()
                return
            }
            lines = tooltipFont.wrapString(value, 250)
            w = lines.maxOfOrNull { tooltipFont.getWidth(it) }?.plus(20) ?: 0
            h = lines.size * 16 + 24
        }

        fun onTab(px: Int, py: Int): Boolean {
            if (lines.isEmpty()) return false
            val tabX = if (mirrored) anchorX - open.toInt() - 37 else anchorX + open.toInt() - 12
            val tabW = if (mirrored) 49 else 37
            return px >= tabX && px < tabX + tabW && py >= anchorY - 40 && py < anchorY + 41
        }

        fun onPanel(px: Int, py: Int): Boolean {
            if (lines.isEmpty()) return false
            val left = if (mirrored) anchorX - open.toInt() else anchorX
            return px >= left && px < left + open && py >= anchorY - h / 2 && py < anchorY + h / 2
        }

        fun close() {
            isOpen = false
            open = 0f
            dragging = false
        }
    }

    private fun slotText(box: Box, i: Int): String? {
        if (box.system is Weapons) {
            val tip = ship.hardpoints.getOrNull(i)?.weapon?.weaponBarTooltip as? StandardTooltip ?: return null
            // The keyboard and mouse lines at the end do not apply to a finger.
            return tip.getText().substringBeforeLast("\n\n" + game.translator["hotkey"].substringBefore("\\1"))
        }
        val drone = ship.drones?.drones?.getOrNull(i) ?: return null
        val title = drone.type.translateTitle(game)
        val desc = drone.type.desc?.let { game.translator[it] }
        return if (desc == null) title else "$title\n\n$desc"
    }

    /** The drawers of an open box, with their tabs placed for this frame. */
    private fun drawersOf(box: Box): List<Drawer> {
        val e = if (box.wide) 19 else 11
        box.drawer.anchorX = if (box.isMain) box.x + e + (if (box.wide) 19 else 0) + 58 else box.x + 6 - e
        box.drawer.anchorY = box.drawerCentreY
        if (!box.wide) return listOf(box.drawer)
        val all = arrayListOf(box.drawer)
        for (i in 0 until slotCount(box)) {
            if (slotEmpty(box, i)) continue
            val d = box.slotDrawer(i)
            d.anchorX = box.drawer.anchorX
            d.anchorY = slotY(box, i) + 38
            all += d
        }
        if (box.system is Weapons && ship.hardpoints.any { it.weapon != null }) {
            box.autofireDrawer.anchorX = box.drawer.anchorX
            box.autofireDrawer.anchorY = autofireY(box) + 24
            all += box.autofireDrawer
        }
        return all
    }

    private fun allDrawers(box: Box) = listOf(box.drawer, box.autofireDrawer) + box.slotDrawers

    private var boxKey: List<AbstractSystem> = emptyList()
    private var boxCache: List<Box> = emptyList()

    private fun boxes(): List<Box> {
        val all = ship.mainSystems + ship.subSystems
        if (all == boxKey) return boxCache
        boxKey = all
        val made = ArrayList<Box>()
        val weapons = ship.weapons
        val drones = ship.drones
        if (weapons != null) made += Box(weapons, 54, true)
        if (drones != null) made += Box(drones, if (weapons != null) 148 else 54, true)
        var next = (if (weapons != null) 92 else -2) + (if (drones != null) 94 else 0) + 56
        for (s in ship.mainSystems) {
            if (s === weapons || s === drones) continue
            made += Box(s, next, false)
            next += 56
        }
        val subs = ship.subSystems.sortedBy {
            when (it) {
                is Piloting -> 0
                is Sensors -> 1
                is BackupBattery -> 2
                else -> 3
            }
        }
        var x = 830 - 56 * subs.size
        for (s in subs) {
            made += Box(s, x, false)
            x += 56
        }
        boxCache = made
        return made
    }

    private var selected: Box? = null
    private var boxDragging = false
    private var startPower = 0
    private var startX = 0
    private var startY = 0
    private var flickVelocity = 0f
    private var lastY = 0
    private var lastTime = 0L
    private var pressedExtra: Button? = null
    private var drawerOwner: Drawer? = null

    private val doorMode: Boolean get() = selected?.system is Doors

    private fun closeBox() {
        selected?.let { box ->
            allDrawers(box).forEach { it.close() }
            box.tabSlide.clear()
        }
        drawerOwner = null
        selected = null
        keepOpen = false
        strayFinger = false
        boxDragging = false
        pressedExtra = null
        slotFinger = false
        draggedSlot = -1
    }

    private fun cancelSystemSelection() = closeBox()

    private fun setPower(system: MainSystem, target: Int) {
        val goal = target.coerceIn(0, system.energyLevels)
        val moved = TouchHaptics.stepTowards({ system.powerSelected }, goal) { up -> ui.touchChangePower(system, up) }
        TouchHaptics.play(TouchHaptics.POWER, moved)
    }

    private fun extraAt(box: Box, px: Int, py: Int): Button? {
        val tops = box.buttonTops
        for ((i, b) in box.panelButtons.withIndex()) {
            val top = tops.getOrNull(i) ?: continue
            val bx = box.x + 4
            if (px > bx && px < bx + 56 && py > top && py < top + 56) return b
        }
        return null
    }

    private fun fire(b: Button) {
        // The aim button of a targetable artillery answers at the desktop box's place, which the panel does not use.
        if (b is xyz.znix.xftl.hs.systems.HsArtilleryButton) {
            b.arm()
            return
        }
        b.mouseDown(Input.MOUSE_LEFT_BUTTON, b.pos.x, b.pos.y)
    }

    /** True while the system bar holds the finger. */
    private fun systemTouch(action: Int, x: Int, y: Int, time: Long): Boolean {
        val open = selected
        if (open != null) {
            drawerOwner?.let { if (drawerTouch(it, action, x)) return true }
            if (action == TouchRouter.DOWN && drawersOf(open).any { drawerDown(open, it, x, y) }) return true
            if (action == TouchRouter.DOWN) pressedExtra = extraAt(open, x, y)
            pressedExtra?.let { pressed ->
                if (action == TouchRouter.UP) {
                    if (extraAt(open, x, y) === pressed) fire(pressed)
                    pressedExtra = null
                }
                if (action == TouchRouter.CANCEL) pressedExtra = null
                return true
            }
            if (open.wide && armamentTouch(open, action, x, y)) return true
            return boxTouch(open, action, x, y, time)
        }
        if (action != TouchRouter.DOWN && action != TouchRouter.MOVE) return false
        for (b in boxes()) if (b.hit(x, y)) {
            selected = b
            boxDragging = false
            startX = x
            startY = y
            lastY = y
            lastTime = time - 1
            return true
        }
        return false
    }

    private fun boxTouch(box: Box, action: Int, x: Int, y: Int, time: Long): Boolean {
        // A finger that came down away from a box kept open is not the box's.
        if (action == TouchRouter.DOWN) strayFinger = false
        else if (strayFinger) {
            if (action == TouchRouter.UP || action == TouchRouter.CANCEL) strayFinger = false
            return false
        }
        when (action) {
            TouchRouter.DOWN -> {
                startX = x
                startY = y
                if (box.hit(x, y)) {
                    boxDragging = true
                    startPower = (box.system as? MainSystem)?.powerSelected ?: 0
                    flickVelocity = 0f
                    lastY = y
                    lastTime = time - 1
                    return true
                }
                // A finger on the panel around the buttons does nothing; it does not close the box.
                val panelTop = box.buttonTops.firstOrNull()
                if (panelTop != null && x > box.x + 6 && x < box.x + 58 && y > panelTop && y < BOX_Y + 66) return true
                // A box the tutorial keeps open stays open; the finger goes on to what is under it.
                if (keepOpen) strayFinger = true else closeBox()
                return false
            }
            TouchRouter.MOVE -> {
                val dx = x - startX
                val dy = y - startY
                drawersOf(box)
                val pastTab = if (box.isMain) x > box.drawer.anchorX else x < box.drawer.anchorX
                if (box.drawer.lines.isNotEmpty() && !box.drawer.isOpen && pastTab && abs(dx) > abs(dy)) {
                    boxDragging = false
                    beginDrawerDrag(box, box.drawer, x)
                    return true
                }
                val system = box.system as? MainSystem ?: return true
                if (!boxDragging) {
                    if (abs(dy) < 18) return true
                    boxDragging = true
                    startPower = system.powerSelected
                    flickVelocity = 0f
                    lastY = y
                    lastTime = time - 1
                }
                flickVelocity = (y - lastY) * 1000f / (time - lastTime).coerceAtLeast(1)
                lastY = y
                lastTime = time
                // A level per bar step, with a dead band at each step's ends so a finger on the line does not flicker.
                TouchPowerDrag.level(startPower, dy, box.barStep)?.let { setPower(system, it) }
                return true
            }
            TouchRouter.UP -> {
                // A tap leaves the box open, a drag or the tap that follows closes it.
                if (boxDragging) {
                    val system = box.system as? MainSystem
                    if (system != null && abs(flickVelocity) >= 1200f) setPower(system, if (flickVelocity <= 0f) system.energyLevels else 0)
                    if (keepOpen) boxDragging = false else closeBox()
                }
                return true
            }
            else -> {
                closeBox()
                return false
            }
        }
    }

    /** A drag from the box or slot carries on as the drawer's, following the finger from here. */
    private fun beginDrawerDrag(box: Box, d: Drawer, x: Int) {
        for (other in allDrawers(box)) if (other !== d && other !== box.drawer) other.close()
        drawerOwner = d
        d.dragging = true
        d.tapped = false
        d.dragStart = d.open
        d.dragX = x
        d.lastDelta = 0f
    }

    /** A finger coming down on a drawer: a tap on the closed tab opens it, otherwise it follows the finger. */
    private fun drawerDown(box: Box, d: Drawer, x: Int, y: Int): Boolean {
        val onTab = d.onTab(x, y)
        if (!onTab && !d.onPanel(x, y)) return false
        beginDrawerDrag(box, d, x)
        d.tapped = onTab && !d.isOpen
        if (d.tapped) d.isOpen = true
        return true
    }

    private fun drawerTouch(d: Drawer, action: Int, x: Int): Boolean {
        if (!d.dragging) return false
        if (d.tapped) {
            if (action == TouchRouter.UP || action == TouchRouter.CANCEL) {
                d.dragging = false
                d.tapped = false
                drawerOwner = null
            }
            return true
        }
        when (action) {
            TouchRouter.MOVE -> {
                val before = d.open
                val delta = if (d.mirrored) d.dragX - x else x - d.dragX
                d.open = (d.dragStart + delta).coerceIn(0f, d.w.f)
                d.lastDelta = d.open - before
            }
            TouchRouter.UP -> {
                d.isOpen = when {
                    d.lastDelta >= 6 -> true
                    d.lastDelta <= -6 -> false
                    // A small slide from closed opens it, and one from open closes it.
                    !d.isOpen -> d.open.toInt() >= d.w / 10
                    else -> d.open.toInt() > d.w * 9 / 10
                }
                d.dragging = false
                drawerOwner = null
            }
            else -> {
                d.dragging = false
                drawerOwner = null
            }
        }
        return true
    }

    private fun advanceDrawers(dt: Float) {
        val box = selected ?: return
        if (box.wide) {
            for (i in 0 until slotCount(box)) {
                val goal = if (slotPowered(box, i)) 1f else 0f
                val now = box.tabSlide[i] ?: 0f
                box.tabSlide[i] = if (now < goal) minOf(goal, now + dt / TAB_SLIDE_TIME) else maxOf(goal, now - dt / TAB_SLIDE_TIME)
            }
        }
        for (d in allDrawers(box)) {
            if (d.dragging) continue
            val goal = if (d.isOpen) d.w.f else 0f
            val step = 2500f * dt
            d.open = when {
                d.open < goal -> minOf(goal, d.open + step)
                d.open > goal -> maxOf(goal, d.open - step)
                else -> d.open
            }
        }
    }

    private fun drawNine(image: Image, x: Float, y: Float, w: Float, h: Float) {
        val c = image.width / 2f
        val iw = image.width.f
        val ih = image.height.f
        fun piece(dx: Float, dy: Float, dw: Float, dh: Float, u0: Float, v0: Float, u1: Float, v1: Float) =
            image.draw(dx, dy, dx + dw, dy + dh, u0, v0, u1, v1)
        val mw = (w - 2 * c).coerceAtLeast(0f)
        val mh = (h - 2 * c).coerceAtLeast(0f)
        piece(x, y, c, c, 0f, 0f, c, c)
        piece(x + c, y, mw, c, c, 0f, c + 1, c)
        piece(x + c + mw, y, c, c, iw - c, 0f, iw, c)
        piece(x, y + c, c, mh, 0f, c, c, c + 1)
        piece(x + c, y + c, mw, mh, c, c, c + 1, c + 1)
        piece(x + c + mw, y + c, c, mh, iw - c, c, iw, c + 1)
        piece(x, y + c + mh, c, c, 0f, ih - c, c, ih)
        piece(x + c, y + c + mh, mw, c, c, ih - c, c + 1, ih)
        piece(x + c + mw, y + c + mh, c, c, iw - c, ih - c, iw, ih)
    }

    private fun drawDrawer(g: Graphics, d: Drawer) {
        d.refresh()
        if (d.lines.isEmpty()) return
        val anchorX = d.anchorX
        val anchorY = d.anchorY
        val tab = img("ipad/tooltips_tab.png")
        val top = anchorY - d.h / 2f
        if (!d.mirrored) {
            if (d.open > 0f) {
                Utils.drawStenciled(Utils.StencilMode.MASKING, { g.colour = Colour.white; g.fillRect(anchorX.f, top - 6, d.open + 6, d.h + 12f) }) {
                    val left = anchorX + d.open - d.w
                    drawNine(img("ipad/tooltips_border.png"), left, top - 6, d.w + 6f, d.h + 12f)
                    for ((i, line) in d.lines.withIndex()) tooltipFont.drawString(left + 14, top + 24 + i * 16, line, Colour.white)
                }
            }
            tab.draw(anchorX + d.open, anchorY - 30f)
        } else {
            if (d.open > 0f) {
                Utils.drawStenciled(Utils.StencilMode.MASKING, { g.colour = Colour.white; g.fillRect(anchorX - d.open - 6, top - 6, d.open + 6, d.h + 12f) }) {
                    val left = anchorX - d.open - 6
                    drawNine(img("ipad/tooltips_border.png"), left, top - 6, d.w + 6f, d.h + 12f)
                    for ((i, line) in d.lines.withIndex()) tooltipFont.drawString(left + 18, top + 24 + i * 16, line, Colour.white)
                }
            }
            tab.draw(anchorX - d.open, anchorY - 30f, anchorX - d.open - tab.width, anchorY - 30f + tab.height, 0f, 0f, tab.width.f, tab.height.f)
        }
    }

    private fun drawWires() {
        val mains = boxes().filter { it.isMain }
        for ((i, b) in mains.withIndex()) {
            when {
                i == 0 -> img("ipad/wireUI/wire_left.png").draw(b.x - 32f, BOX_Y + 47f)
                i == mains.lastIndex -> img("ipad/wireUI/wire_right.png").draw(b.x - 24f, BOX_Y + 49f)
                mains[i - 1].wide -> img("ipad/wireUI/wire_94.png").draw(b.x - 62f, BOX_Y + 49f)
                else -> img("ipad/wireUI/wire_56.png").draw(b.x - 24f, BOX_Y + 49f)
            }
        }
        val subs = boxes().filter { !it.isMain }
        // The subsystems' wires run the other way, so their pictures are mirrored.
        fun mirrored(name: String, x: Float) {
            val wire = img("ipad/wireUI/$name.png")
            wire.draw(x + wire.width, BOX_Y + 49f, x, BOX_Y + 49f + wire.height, 0f, 0f, wire.width.f, wire.height.f)
        }
        for ((i, b) in subs.withIndex()) {
            when (i) {
                0 -> mirrored(if (subs.size == 1) "wire_left2" else "wire_right", b.x + 26f)
                subs.lastIndex -> mirrored("wire_left2", b.x + 32f)
                else -> mirrored("wire_56", b.x + 32f)
            }
        }
    }

    private fun iconColour(s: AbstractSystem): Colour = when (s.iconColourName) {
        "red" -> ICON_RED
        "orange" -> ICON_ORANGE
        "grey" -> ICON_GREY
        else -> ICON_GREEN
    }

    private fun drawBox(g: Graphics, box: Box) {
        val system = box.system
        val isOpen = box === selected
        if (isOpen) drawPanel(g, box)
        // A weapon or drone box moves right and up while open, under its column.
        val raised = box.raised
        val shift = if (raised) 19 else 0
        val cx = box.x + 2 + shift
        val iconY = BOX_Y + 2f - (if (raised) 8 else 0)
        val rows = box.rows
        if (rows > 0) {
            g.pushTransform()
            g.translate(box.x + 17f + shift, BOX_Y + 8f - (if (raised) 16 else 0))
            g.scale(30f / 16f, box.barScale)
            system.touchDrawBars(g)
            g.popTransform()
        }
        if (system.room != null) system.touchDrawStatus(g, game, box.x + 17 + shift + 7, box.barsTop - STATUS_GAP)
        drawCooldownBar(box, shift)
        if (system is xyz.znix.xftl.systems.Clonebay) {
            if (isOpen) box.buttonTops.lastOrNull()?.let { system.touchDrawLargeQueue(game, g, box.x + 32, it + 17) }
            else system.touchDrawQueue(game, g, box.x + 6, BOX_Y - 16 * rows.coerceIn(1, 3) + 3, box.x, box.barsTop)
        }
        if (system.isIonised) {
            val levels = minOf(9, Math.ceil((system.ionTimer / AbstractSystem.TIME_PER_ION).toDouble()).toInt()).coerceAtLeast(1)
            val type = if (system is MainSystem) "ring" else "octa"
            img("ipad/icons/locking/s_${type}_${levels}_base.png").draw(cx.f, iconY)
        } else {
            val large = game.getImgIfExists("img/ipad/icons/s_${system.codename}_large.png")
            if (raised) {
                // The open box shows the filled picture, twice its size.
                val icon = game.getImg("img/icons/s_${system.codename}_${system.iconColourName}2.png")
                icon.draw(box.x + 19f - 32f, BOX_Y - 40f, box.x + 19f - 32f + 128f, BOX_Y - 40f + 128f, 0f, 0f, 64f, 64f)
            } else if (large != null) large.draw(cx.f, iconY, iconColour(system))
            else game.getImg("img/icons/s_${system.codename}_${system.iconColourName}1.png").draw(cx.f, BOX_Y + 2f, cx + 60f, BOX_Y + 62f, 0f, 0f, 64f, 64f)
        }
        if (system.isHackActive) img("icons/s_hacked.png").draw(box.x + 16f + shift, BOX_Y - 10f)
        if (isOpen) drawPanelButtons(g, box)
    }

    /** How far the system's timer has run, 0 to 1, for a system that has one. */
    private fun cooldownFraction(box: Box): Float = when (val s = box.system) {
        is xyz.znix.xftl.systems.Clonebay -> (s.hsTimeToClone / s.cloneDuration).coerceIn(0f, 1f)
        is xyz.znix.xftl.systems.Artillery -> s.touchCharge
        else -> {
            val b = box.extras.firstOrNull { it is SystemPowerButton } as? SystemPowerButton
            val left = b?.timeRemaining
            if (b == null || left == null || b.duration <= 0f) 0f else (left / b.duration).coerceIn(0f, 1f)
        }
    }

    private fun drawCooldownBar(box: Box, shift: Int) {
        val n = box.cooldownLevel
        if (n <= 0) return
        val frame = img("ipad/systemUI/bar_${n}_box.png")
        val mask = img("ipad/systemUI/bar_${n}_mask.png")
        val x = box.x + 43f + shift
        val y = BOX_Y + 15f - frame.height
        frame.draw(x, y)
        val total = 20 * n + 10
        val visible = (total - 4 * (cooldownFraction(box) * 5 * n).toInt()).coerceIn(0, total)
        val filled = total - visible
        if (filled > 0) {
            val v0 = mask.height - filled - 5f
            mask.draw(x, y + v0, x + mask.width, y + v0 + filled, 0f, v0, mask.width.f, v0 + filled, 1f, iconColour(box.system))
        }
    }

    private fun drawPanel(g: Graphics, box: Box) {
        val prefix = if (box.wide) "ipad/systemUI/sysbox" else "ipad/systemUI/sysbox2"
        val e = if (box.wide) 19 else 11
        val px = box.x - e + (if (box.wide) 19 else 0)
        val bottom = img("${prefix}_bottom.png")
        val line = img("${prefix}_1px.png")
        val cap = img("${prefix}_top.png")
        val separator = img("${prefix}_separator.png")
        bottom.draw(px.f, PANEL_BASE.f)
        fun stretch(from: Int, to: Int) = line.draw(px.f, from.f, px + line.width.f, to.f, 0f, 0f, line.width.f, 1f)
        var y = PANEL_BASE - box.panelStretch
        stretch(y, PANEL_BASE)
        for (top in box.buttonTops.asReversed()) {
            separator.draw(px.f, y - 12f)
            stretch(top, y - 12)
            y = top
        }
        cap.draw(px.f, (y - cap.height).f)
    }

    private fun drawPanelButtons(g: Graphics, box: Box) {
        val tops = box.buttonTops
        for ((i, b) in box.panelButtons.withIndex()) {
            val top = tops.getOrNull(i) ?: continue
            val name = when (box.system) {
                is Doors -> if (i == 0) "button_door_top" else "button_door_bottom"
                is Teleporter -> if (i == 0) "button_tele_up" else "button_tele_down"
                is Hacking -> "button_hacklaunch"
                else -> "button_default"
            }
            val state = when {
                b.disabled -> "off"
                b === pressedExtra -> "select2"
                else -> "on"
            }
            img("ipad/systemUI/${name}_$state.png").draw(box.x + 4f, top.f)
        }
    }

    // ---- weapons and drones: the column of the open panel ---------------------------------------------------------
    private var slotFinger = false
    private var pressedSlot = -1
    private var pressedPower = -1
    private var draggedSlot = -1
    private var dragAnchorY = 0
    private var slotDragY = 0
    private var autofirePressed = false

    private fun slotCount(box: Box) = if (box.system is Weapons) ship.weaponSlots ?: 0 else ship.droneSlots ?: 0
    private fun panelX(box: Box) = box.x + 17
    private fun panelY(box: Box) = BOX_Y - 28 - box.rowsHeight
    private fun slotY(box: Box, i: Int) = panelY(box) - (if (box.system is Weapons) 145 else 88) - 88 * i
    private fun autofireY(box: Box) = panelY(box) - 59

    private fun slotEmpty(box: Box, i: Int): Boolean =
        if (box.system is Weapons) ship.hardpoints.getOrNull(i)?.weapon == null else ship.drones?.drones?.getOrNull(i) == null

    private fun slotPowered(box: Box, i: Int): Boolean =
        if (box.system is Weapons) ship.hardpoints.getOrNull(i)?.weapon?.isPowered == true
        else ship.drones?.drones?.getOrNull(i)?.instance?.isPowered == true

    private fun armamentTouch(box: Box, action: Int, x: Int, y: Int): Boolean {
        val lx = panelX(box)
        if (box.system is Weapons) {
            val ay = autofireY(box)
            val inAuto = x > box.x + 15 && x < box.x + 15 + 74 && y > ay && y < ay + 47
            if (action == TouchRouter.DOWN && inAuto) {
                autofirePressed = true
                return true
            }
            if (autofirePressed) {
                if (action == TouchRouter.UP && inAuto) ui.toggleAutofire()
                if (action == TouchRouter.UP || action == TouchRouter.CANCEL) autofirePressed = false
                return true
            }
        }
        fun slotAt(px: Int, py: Int): Int {
            for (i in 0 until slotCount(box)) {
                val sy = slotY(box, i)
                if (px > lx && px < lx + 68 && py > sy && py < sy + 76) return i
            }
            return -1
        }
        fun powerAt(px: Int, py: Int): Int {
            for (i in 0 until slotCount(box)) {
                if (!slotPowered(box, i)) continue
                val sy = slotY(box, i)
                if (px > lx - 78 && px < lx + 3 && py > sy - 2 && py < sy + 79) return i
            }
            return -1
        }
        when (action) {
            TouchRouter.DOWN -> {
                pressedPower = powerAt(x, y)
                if (pressedPower >= 0) return true
                pressedSlot = slotAt(x, y)
                if (pressedSlot >= 0 && slotEmpty(box, pressedSlot)) pressedSlot = -1
                if (pressedSlot >= 0) {
                    draggedSlot = -1
                    dragAnchorY = y
                    slotDragY = y
                    startX = x
                    startY = y
                    return true
                }
                return false
            }
            TouchRouter.MOVE -> {
                if (pressedPower >= 0) return true
                if (pressedSlot < 0) return false
                slotDragY = y
                if (draggedSlot < 0) {
                    if (x - lx in 0 until 69 && abs(y - startY) > 20) draggedSlot = pressedSlot
                    else if (x - lx >= 69) {
                        val d = box.slotDrawer(pressedSlot)
                        d.refresh()
                        if (!d.isOpen && d.lines.isNotEmpty() && abs(y - startY) < x - startX) beginDrawerDrag(box, d, x)
                        pressedSlot = -1
                    }
                }
                if (draggedSlot >= 0) {
                    val over = slotAt(x, y)
                    if (over >= 0 && over != draggedSlot) {
                        val moved = when (val s = box.system) {
                            is Weapons -> s.swapSlots(draggedSlot, over)
                            is Drones -> s.swapSlots(draggedSlot, over)
                            else -> false
                        }
                        if (moved) {
                            dragAnchorY += slotY(box, over) - slotY(box, draggedSlot)
                            draggedSlot = over
                        }
                    }
                }
                return true
            }
            TouchRouter.UP -> {
                if (pressedPower >= 0) {
                    if (powerAt(x, y) == pressedPower) ui.touchSlotPower(box.system, pressedPower, false)
                    pressedPower = -1
                    return true
                }
                val slot = pressedSlot
                val wasDrag = draggedSlot >= 0
                pressedSlot = -1
                draggedSlot = -1
                if (slot < 0) return false
                if (!wasDrag && slotAt(x, y) == slot) ui.touchSlotClick(box.system, slot)
                return true
            }
            else -> {
                pressedSlot = -1
                pressedPower = -1
                draggedSlot = -1
                return false
            }
        }
    }

    private fun drawArmamentClosed(g: Graphics, box: Box) {
        val n = slotCount(box)
        if (n <= 0) return
        val x = panelX(box) + 30f
        img("systemUI/weapbox_box_bottom.png").draw(x, 659f)
        for (i in 1 until n - 1) img("systemUI/weapbox_box_mid.png").draw(x, 659f - 35 * i)
        if (n > 1) img("systemUI/weapbox_box_top.png").draw(x, 659f - 35 * (n - 2) - 42)
        val isWeapon = box.system is Weapons
        val ix = panelX(box) + 43f
        for (i in 0 until n) {
            val iy = 665f - 35 * i
            if (slotEmpty(box, i)) {
                img("systemUI/weapbox_inner_droneoff.png").draw(ix, iy, EMPTY_SLOT_ALPHA)
                continue
            }
            val colour = slotColour(box, i)
            img("systemUI/weapbox_inner_dronebox.png").draw(ix, iy, colour)
            slotIcon(box, i)?.draw(ix, iy)
            val weapon = if (isWeapon) ship.hardpoints.getOrNull(i)?.weapon else null
            if (weapon != null) {
                val icon = chargeIcon(i, weapon, colour)
                g.colour = SLOT_BLACK
                g.fillRect(ix + icon.left.f, iy + icon.top.f, icon.width.f, icon.height.f)
                g.colour = icon.barColour
                g.fillRect(ix + icon.barLeft.f, iy + icon.barBottom.f - icon.bar, icon.barWidth.f, icon.bar.f)
            }
        }
    }

    private fun slotColour(box: Box, i: Int): Colour {
        val isWeapon = box.system is Weapons
        val weapon = if (isWeapon) ship.hardpoints.getOrNull(i)?.weapon else null
        val drone = if (!isWeapon) ship.drones?.drones?.getOrNull(i) else null
        return when {
            slotEmpty(box, i) -> SLOT_LIGHT
            box.system.isHackActive -> Constants.SYSTEM_HACKED
            !slotPowered(box, i) -> Constants.WEAPONS_ITEM_DESELECTED
            isWeapon && ui.touchArmedSlot == i && ui.isAimingAutofire() -> Constants.WEAPONS_ITEM_AUTOFIRE
            isWeapon && ui.touchArmedSlot == i -> Constants.WEAPONS_ITEM_TARGETING
            weapon?.isCharged == true || drone != null -> Constants.WEAPONS_ITEM_CHARGED
            else -> SLOT_LIGHT
        }
    }

    private fun slotIcon(box: Box, i: Int): Image? {
        if (box.system is Weapons) {
            val w = ship.hardpoints.getOrNull(i)?.weapon ?: return null
            val name = when {
                w is BeamBlueprint.BeamInstance -> "beam"
                w is xyz.znix.xftl.weapons.BombBlueprint.BombInstance -> "bomb"
                w is xyz.znix.xftl.weapons.MissileBlueprint.MissileInstance -> "missile"
                w is xyz.znix.xftl.weapons.FlakBlueprint.FlakInstance -> "flak"
                else -> "laser"
            }
            return img("systemUI/weapbox_icon_W_$name.png")
        }
        val d = ship.drones?.drones?.getOrNull(i) ?: return null
        val name = when {
            d.type.typeName.contains("BOARDER") -> "boarder"
            d.type.typeName.contains("REPAIR") -> "repair"
            d.type.typeName.contains("DEFENSE") -> "defense"
            d.type.typeName.contains("SHIELD") -> "shield"
            else -> "combat"
        }
        return img("systemUI/weapbox_icon_D_$name.png")
    }

    private fun drawSlot(g: Graphics, box: Box, i: Int, offsetY: Int) {
        val x = panelX(box)
        val y = slotY(box, i) + offsetY
        val empty = slotEmpty(box, i)
        val isWeapon = box.system is Weapons
        val weapon = if (isWeapon) ship.hardpoints.getOrNull(i)?.weapon else null
        val drone = if (!isWeapon) ship.drones?.drones?.getOrNull(i) else null
        val armed = isWeapon && ui.touchArmedSlot == i
        val powered = slotPowered(box, i)
        val colour = slotColour(box, i)
        if (armed || i == pressedSlot) {
            val glow = img(if (isWeapon) "ipad/systemUI/weaponbox_glow.png" else "ipad/systemUI/dronebox_glow.png")
            glow.draw(x - 14f, y - 16f)
            glow.draw(x - 14f, y - 16f)
        }
        g.colour = SLOT_BLACK
        g.fillRect(x.f, y.f, 68f, 76f)
        if (armed || box.system.isHackActive) {
            g.colour = colour
            g.drawRect(x + 1f, y + 1f, 65f, 73f)
        }
        if (empty) return

        // The power tab to the left: the octagon turns the slot off.
        val slide = box.tabSlide[i] ?: 0f
        if (slide > 0f) {
            // The tab comes out from behind the slot.
            val hidden = 64 - (slide * 64).toInt()
            val state = if (i == pressedPower) "select2" else "on"
            Utils.drawStenciled(Utils.StencilMode.MASKING, { g.colour = Colour.white; g.fillRect(x - 75f, y + 1f, 64f, 75f) }) {
                img("ipad/systemUI/tabs_drones_leftbox.png").draw(x - 75f + hidden, y + 1f)
                img("ipad/systemUI/button_weaponpower_$state.png").draw(x - 62f + hidden, y + 14f)
            }
        }

        // The top half is the slot's state colour, with the weapon's picture on it.
        g.colour = colour
        g.fillRect(x + 1f, y + 1f, 66f, 38f)
        slotIcon(box, i)?.let { icon ->
            icon.draw(x + 18f, y - 7f, x + 18f + icon.width * 2, y - 7f + icon.height * 2, 0f, 0f, icon.width.f, icon.height.f)
        }

        val cellsX = if (isWeapon) x + 14 else x + 6
        if (weapon != null) {
            val column = chargeColumn(i, weapon, colour)
            g.colour = SLOT_BLACK
            g.fillRect(x + 4f, y + column.top.f, 6f, column.height.f)
            g.colour = column.barColour
            g.fillRect(x + 5f, y + 36f - column.bar, 4f, column.bar.f)
        }
        val required = weapon?.type?.power ?: drone?.type?.power ?: 0
        g.colour = SLOT_BLACK
        g.fillRect(cellsX - 2f, y + 3f, 20f, 34f)
        for (bar in 0 until required) {
            val by = y + 28 - bar * 8
            g.colour = if (powered) colour else Constants.WEAPONS_ITEM_ENERGY_UNPOWERED
            if (powered) g.fillRect(cellsX.f, by.f, 16f, 7f) else g.drawRect(cellsX.f, by.f, 15f, 6f)
        }
        val name = weapon?.type?.translateShort(game) ?: drone?.type?.translateShort(game) ?: ""
        var lineY = y + 54f
        for (line in slotFont.wrapString(name, 60).take(2)) {
            slotFont.drawString(x + 5f, lineY, line, colour)
            lineY += 14f
        }
    }

    /** A closed weapon column's charge frame (left, top, size in the icon) and the bar in it (left, bottom, width, height). */
    internal class ChargeIcon(val left: Int, val top: Int, val width: Int, val height: Int, val fullBar: Int,
                              val barLeft: Int, val barBottom: Int, val barWidth: Int, val bar: Int, val barColour: Colour)

    /**
     * The closed column shows each box's icon view, which draws the weapon box's charge frame at its small size: the full
     * bar is [Weapons.chargeBarHeight] out of 22 px, 2 px wide inside a 6 px frame of 1 px lines, its bottom 24 px down
     * the icon, so a quicker weapon's frame is shorter as in the open slot. The bar takes the box's status colour, or the
     * aiming colours once the weapon has a target. It used to be a 24 px bar for every weapon.
     */
    internal fun chargeIcon(i: Int, weapon: AbstractWeaponInstance, slotColour: Colour): ChargeIcon {
        val weapons = ship.weapons!!
        val fullBar = weapons.chargeBarHeight(weapon, 22)
        val colour = when {
            weapons.selectedTargets.getTarget(i) != null -> if (weapons.isAutofire(i)) Constants.WEAPONS_ITEM_AUTOFIRE else Constants.WEAPONS_ITEM_TARGETING
            else -> slotColour
        }
        return ChargeIcon(1, 23 - fullBar, 4, fullBar + 2, fullBar, 2, 24, 2,
            (fullBar * weapon.chargeProgress).toInt().coerceIn(0, fullBar), colour)
    }

    /** An open weapon slot's charge column: its black frame (top and height in the slot) and the bar in it. */
    internal class ChargeColumn(val top: Int, val height: Int, val fullBar: Int, val bar: Int, val barColour: Colour)

    /**
     * The weapon box's charge frame at its large size, as the iPad's open slot draws it: the full bar is
     * [Weapons.chargeBarHeight] out of 33 px, so a quicker weapon's frame is shorter, and the frame holds that bar with
     * a pixel above it and below it, its bottom 36 px down the slot. The bar takes the box's status colour, or the aiming
     * colours once the weapon has a target (WeaponBox::RenderBox). The frame used to be 33 px for every weapon, so a full
     * Defense Laser on a ship with a slower weapon looked two thirds charged.
     */
    internal fun chargeColumn(i: Int, weapon: AbstractWeaponInstance, slotColour: Colour): ChargeColumn {
        val weapons = ship.weapons!!
        val fullBar = weapons.chargeBarHeight(weapon, 33)
        val colour = when {
            weapons.selectedTargets.getTarget(i) != null -> if (weapons.isAutofire(i)) Constants.WEAPONS_ITEM_AUTOFIRE else Constants.WEAPONS_ITEM_TARGETING
            else -> slotColour
        }
        return ChargeColumn(35 - fullBar, fullBar + 2, fullBar, (fullBar * weapon.chargeProgress).toInt().coerceIn(0, fullBar), colour)
    }

    private fun drawArmamentOpen(g: Graphics, box: Box) {
        val n = slotCount(box)
        // The column and the box below it are one frame.
        if (n > 0) {
            val top = slotY(box, n - 1) - 5
            g.colour = SLOT_LIGHT
            Buttons.drawChamfer(g, panelX(box) - 6, top, 80, autofireY(box) + 47 - top, 6)
        }
        for (i in 0 until n) if (i != draggedSlot) drawSlot(g, box, i, 0)
        if (box.system is Weapons && ship.hardpoints.any { it.weapon != null }) {
            val y = autofireY(box)
            val on = ship.weapons?.autofire == true
            g.colour = SLOT_BLACK
            Buttons.drawChamfer(g, box.x + 12, y - 3, 80, 53, 5)
            g.colour = if (autofirePressed) BUTTON_PRESSED else if (on) Constants.TEXT_BUTTON_SELECTED else Constants.TEXT_BUTTON_NORMAL
            Buttons.drawChamfer(g, box.x + 15, y, 74, 47, 4)
            autofireFont.printButtonLabel(box.x + 15, y, 74, 47, game.translator["button_autofire_ipad"], AUTOFIRE_TEXT)
        }
        if (draggedSlot >= 0) drawSlot(g, box, draggedSlot, slotDragY - dragAnchorY)
    }

    private fun drawSystems(g: Graphics) {
        // The rest of the bar dims while a box is open.
        val open = selected
        val tint = Graphics.tint
        if (open != null) Graphics.tint = Colour(tint.r * 0.75f, tint.g * 0.75f, tint.b * 0.75f, tint.a)
        drawWires()
        drawReactor(g)
        for (b in boxes()) {
            if (b.wide && b !== open) drawArmamentClosed(g, b)
        }
        for (b in boxes()) if (b !== open) drawBox(g, b)
        Graphics.tint = tint
    }

    private fun drawOpenSystem(g: Graphics) {
        val open = selected ?: return
        val drawers = drawersOf(open)
        for (d in drawers) drawDrawer(g, d)
        drawBox(g, open)
        if (open.wide) drawArmamentOpen(g, open)
    }

    private fun drawReactor(g: Graphics) {
        val total = ship.purchasedReactorPower + (ship.backupBattery?.contributedPower ?: 0)
        val available = ship.powerAvailable
        val shown = minOf(total, 30)
        ui.touchDrawNoPower(g, 729 - 9 * (shown - 1))
        for (i in 0 until shown) {
            val y = 729f - i * 9
            if (i < available) {
                g.colour = Constants.SYS_ENERGY_ACTIVE
                g.fillRect(16f, y, 28f, 7f)
            } else {
                g.colour = REACTOR_USED
                g.drawRect(16f, y, 27f, 6f)
            }
        }
    }

    private fun overSystems(x: Int, y: Int): Boolean = selected != null || boxes().any { it.hit(x, y) }

    // ---- doors ----------------------------------------------------------------------------------------------------
    private var doorLatchSet = false
    private var doorLatchOpen = false

    private fun doorTouch(action: Int, canvasX: Int, canvasY: Int) {
        if (!ship.areDoorsOperable) return
        if (action == TouchRouter.DOWN) doorLatchSet = false
        if (action != TouchRouter.DOWN && action != TouchRouter.MOVE) return
        val p = Point(canvasX, canvasY)
        game.convertScreenToPlayerShip(p)
        var best: xyz.znix.xftl.layout.Door? = null
        var bestDist = Int.MAX_VALUE
        for (door in ship.doors) {
            var cx = door.offsetX
            var cy = door.offsetY
            if (door.isVertical) cy += Constants.ROOM_SIZE / 2 else cx += Constants.ROOM_SIZE / 2
            val halfLong = 45
            val halfShort = 24
            val dx = abs(p.x - cx)
            val dy = abs(p.y - cy)
            val inside = if (door.isVertical) dx <= halfShort && dy <= halfLong else dx <= halfLong && dy <= halfShort
            if (!inside) continue
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = door
            }
        }
        val door = best ?: return
        if (door.isHacked) return
        if (!doorLatchSet) {
            doorLatchSet = true
            doorLatchOpen = !door.open
        }
        if (door.open != doorLatchOpen) door.open = doorLatchOpen
    }

    // ---- combat: aiming a weapon, teleporter, hacking or mind control -----------------------------------------------
    private var activeTargeting = false
    private var targetInvalid = false

    private fun combatTouch(action: Int, canvasX: Int, canvasY: Int): Boolean {
        val armed = isTargeting
        if (action == TouchRouter.DOWN) {
            activeTargeting = armed
            targetInvalid = false
        }
        if (!activeTargeting && !armed) return false
        val room = if (focusOnPlayer) ui.touchRoomOnPlayer(canvasX, canvasY) else ui.touchRoomOnEnemy(canvasX, canvasY) ?: ui.touchRoomOnPlayer(canvasX, canvasY)
        val valid = room?.takeIf { game.clickEvent == null || game.clickEvent.canTargetRoom(it) }
        game.hoverRoomForTouch(if (action == TouchRouter.UP || action == TouchRouter.CANCEL) null else valid)
        if (ui.touchArmedBeam) {
            when (action) {
                TouchRouter.DOWN -> if (valid != null) ui.touchClickRoom(valid) else targetInvalid = true
                TouchRouter.MOVE -> if (extraFingers.size == 1 && lastBeamX != Int.MIN_VALUE) ui.touchSlideBeam(canvasX - lastBeamX, canvasY - lastBeamY) else ui.touchMoveBeam(canvasX, canvasY)
                TouchRouter.UP -> if (!targetInvalid && !ui.touchFireBeam()) ui.touchCancelTargeting()
                // The system took the finger back: the player did not choose this line, so it goes and the weapon stays armed.
                TouchRouter.CANCEL -> ui.touchAbortBeam()
            }
            lastBeamX = canvasX
            lastBeamY = canvasY
            if (action == TouchRouter.UP || action == TouchRouter.CANCEL) lastBeamX = Int.MIN_VALUE
            return true
        }
        if (action == TouchRouter.UP) {
            if (valid != null) ui.touchClickRoom(valid)
            else if (!ui.touchNearEnemyRoom(canvasX, canvasY)) ui.touchCancelTargeting()
        }
        return true
    }

    private var lastBeamX = Int.MIN_VALUE
    private var lastBeamY = 0

    /** This finger started on a beam aim and has not lifted: it keeps the aim wherever it goes on the screen. */
    private val beamGesture get() = lastBeamX != Int.MIN_VALUE && ui.touchArmedBeam

    // ---- the finger -------------------------------------------------------------------------------------------------
    private var primary = -1
    /** The first finger has done its work (a focus change) and only waits to lift; it still owns the gesture. */
    private var primarySpent = false
    private var hudConsumed = false
    private var tutorialFinger = false
    private var pendingRedown = false
    private var redownFlag = false
    private val extraFingers = ArrayList<Int>()

    /**
     * Whether a new finger is this HUD's; otherwise the open window takes it as the mouse. With an event box up, the
     * options and pause buttons still work.
     */
    override fun takesFinger(canvasX: Int, canvasY: Int): Boolean {
        if (!ui.isWindowOpen) return true
        val x = canvasX - hudX
        return ui.isEventOpen && (optionsButton.contains(x, canvasY) || pauseButton.contains(x, canvasY))
    }

    /** A new gesture starts (TouchRouter): whatever this HUD still held from an earlier one, the system has ended. */
    override fun newGesture() {
        primary = -1
        primarySpent = false
        extraFingers.clear()
        reorderCrew = null
        reorderBox = -1
        if (beamGesture) ui.touchAbortBeam()
        lastBeamX = Int.MIN_VALUE
    }

    /** Called only while no focus window has the finger (TouchRouter). */
    override fun touch(action: Int, id: Int, canvasX: Int, canvasY: Int, time: Long) {
        when (action) {
            TouchRouter.DOWN -> {
                if (primary != -1) {
                    extraFingers += id
                    return
                }
                primary = id
                primarySpent = false
                hudConsumed = false
                extraFingers.clear()
            }
            TouchRouter.MOVE -> if (id != primary) return
            else -> {
                if (id != primary) {
                    extraFingers -= id
                    return
                }
                primary = -1
                extraFingers.clear()
                if (primarySpent) {
                    primarySpent = false
                    return
                }
            }
        }
        if (primarySpent) return
        val x = canvasX - hudX
        val y = canvasY

        pendingRedown = redownFlag
        redownFlag = false

        if (game.isPlayerJumping) {
            primarySpent = primary != -1
            return
        }

        val down = action == TouchRouter.DOWN
        // The tutorial's panel and Continue button come before everything else.
        val overlay = game.mainGame?.tutorialOverlay
        if (down && overlay != null && overlay.hitTest(game, canvasX, canvasY)) tutorialFinger = true
        if (tutorialFinger) {
            if (action == TouchRouter.UP) overlay?.click(game, Input.MOUSE_LEFT_BUTTON, canvasX, canvasY)
            if (action == TouchRouter.UP || action == TouchRouter.CANCEL) tutorialFinger = false
            return
        }
        // A window came up under an aiming finger (an event, or Back's menu): the aim is not what the player is on now.
        if (ui.isWindowOpen && beamGesture) ui.touchAbortBeam()
        if (ui.isWindowOpen && (action == TouchRouter.UP || action == TouchRouter.CANCEL)) lastBeamX = Int.MIN_VALUE
        if (ui.isEventOpen) {
            optionsButton.touch(action, x, y)
            pauseButton.touch(action, x, y)
            doButtons()
            return
        }
        if (down || hudConsumed || pendingRedown) {
            var consumed = false
            for (b in hudButtons) if (b.touch(action, x, y)) consumed = true
            if (extras.touch(action, x, y)) consumed = true
            hudConsumed = consumed
        }
        if (!hudConsumed && !down && beamGesture) {
            combatTouch(action, canvasX, canvasY)
            doButtons()
            return
        }
        if (!hudConsumed) {
            if (down) {
                val station = stationButtonAt(x, y)
                assignPressed = station == 1
                returnPressed = station == 2
            } else if (action == TouchRouter.UP) {
                val station = stationButtonAt(x, y)
                if (assignPressed && station == 1) {
                    ui.saveCrewPositions()
                    stationMessage = 1f
                    stationMessageKey = "stations_saved"
                } else if (returnPressed && station == 2) {
                    stationMessageKey = if (ui.touchReturnToStations()) "return_stations" else "no_stations"
                    stationMessage = 1f
                }
                assignPressed = false
                returnPressed = false
            }

            // A held crew member goes with the finger wherever it is, until it lifts.
            if (reorderCrew != null && reorderTouch(action, x, y)) {
                doButtons()
                return
            }
            if (crewPanelTouch(action, x, y)) {
                doButtons()
                return
            }
            val wasTargeting = isTargeting
            val holdingSystem = selected != null && (boxDragging || pressedExtra != null || pressedSlot >= 0 || pressedPower >= 0 || autofirePressed || drawerOwner != null)
            if (holdingSystem) {
                systemTouch(action, x, y, time)
            } else {
                val over = overSystemsForFinger(x, y)
                val onPlayer = !over && ui.touchNearPlayerRoom(canvasX, canvasY)
                val onEnemy = !over && !doorMode && hasTarget && (if (focusOnPlayer) targetBoxContains(x, y) else ui.touchNearEnemyRoom(canvasX, canvasY))
                val onShip = onPlayer || onEnemy
                if (down || pendingRedown) shipHit = onShip
                if (down && hasTarget && onShip) {
                    val touchedPlayer = onPlayer && (focusOnPlayer || !onEnemy)
                    if (touchedPlayer != focusOnPlayer) {
                        toggleFocus()
                        primarySpent = true
                        return
                    }
                }
                if (onShip || (dragging && !over)) {
                    when {
                        doorMode -> doorTouch(action, canvasX, canvasY)
                        isTargeting || activeTargeting -> {
                            if (!combatTouch(action, canvasX, canvasY)) crewControl(action, x, y, canvasX, canvasY)
                        }
                        else -> crewControl(if (pendingRedown && action == TouchRouter.MOVE) TouchRouter.DOWN else action, x, y, canvasX, canvasY)
                    }
                } else if (over && selected?.wide == true) {
                    // The open column comes before aiming: its slots and drawers keep working with a weapon armed.
                    systemTouch(action, x, y, time)
                } else if (!combatTouch(action, canvasX, canvasY)) {
                    if (overCrewArea(x, y) || dragging) {
                        crewControl(action, x, y, canvasX, canvasY)
                        val first = ui.touchSelected.firstOrNull()
                        if (first != null && hasTarget && (first.room.ship === ship) != focusOnPlayer) toggleFocus()
                    } else if (!systemTouch(action, x, y, time)) {
                        redownFlag = action == TouchRouter.MOVE
                        statusTip = if (action == TouchRouter.DOWN || action == TouchRouter.MOVE) statusAt(x, y) else null
                    }
                }
            }
            if (doorMode && hasTarget && !focusOnPlayer) toggleFocus()
            if (!wasTargeting && isTargeting && hasTarget && focusOnPlayer) toggleFocus()
        }
        doButtons()
    }

    // ---- a finger held on the status bar or the reactor shows what it is ------------------------------------------
    private var statusTip: xyz.znix.xftl.rendering.ITooltipProvider? = null

    private fun statusAt(x: Int, y: Int): xyz.znix.xftl.rendering.ITooltipProvider? {
        if (y < 20 && x in 0 until 380) return ui.touchStatusHull
        if (y in 20 until 61) when (x) {
            in 0 until 101 -> return ui.touchStatusShields
            in 101 until 161 -> return ui.touchStatusFuel
            in 161 until 221 -> return ui.touchStatusMissiles
            in 221 until 281 -> return ui.touchStatusDrones
            in 281 until 380 -> return if (y < 40) ui.touchStatusEvade else ui.touchStatusOxygen
        }
        if (y < 55 && x in 380 until 513) return ui.touchStatusScrap
        val bars = minOf(ship.purchasedReactorPower + (ship.backupBattery?.contributedPower ?: 0), 30)
        if (x in 12 until 48 && y in 729 - 9 * (bars - 1) until 736) return ui.touchStatusReactor
        return null
    }

    // ---- per frame -----------------------------------------------------------------------------------------------
    fun update(dt: Float) {
        ui.touchDropBeamIfTargetGone()
        updateFocus(dt)
        updateButtons()
        extras.update()
        updateAutoPause()
        advancePulse(dt)
        advanceDrawers(dt)
        updateReorder()
    }

    /**
     * The HUD elements in both layouts (TouchPointers), HUD block coordinates: the crew boxes (with the strip of
     * Hyperspace's power buttons to their right), the jump, ship, store and options buttons, the player's ship and the
     * target box. TouchExtras adds its pieces.
     */
    fun anchors(): List<TouchPointers.Anchor> {
        val out = ArrayList<TouchPointers.Anchor>()
        // PlayerShipUI's desktop crew list: boxes from y 155 (the oxygen box's 96 + 59), 30 apart, at x 10, 86 x 27,
        // Hyperspace's power buttons up to 83 px right of them; the touch list has the same boxes from CREW_TOP.
        for (i in boxCrew.indices) {
            val y = DESK_CREW_TOP + CREW_SPACING * i
            out += TouchPointers.Anchor.shift("crew:$i", floatArrayOf(CREW_X.f, y.f, CREW_X + CREW_W + 83f, y + CREW_H.f),
                0f, (crewBoxY(i) - y).f)
        }
        val desk = ui.touchDesktopButtons
        for ((i, b) in listOf(jumpButton, shipButton, storeButton, optionsButton).withIndex()) {
            val d = desk.getOrNull(i) ?: continue
            if (i == 2 && !storeHere) continue
            val dr = floatArrayOf(d.pos.x.f, d.pos.y.f, d.pos.x + d.size.x * d.renderScale, d.pos.y + d.size.y * d.renderScale)
            out += TouchPointers.Anchor.fit(listOf("button:jump", "button:ship", "button:store", "button:options")[i], dr,
                floatArrayOf(b.x.f, b.y.f, (b.x + b.w).f, (b.y + b.h).f))
        }
        // The player's ship: InGameState's desktop origin (350, 200 beside an enemy, 150 beside a boss, y 170, plus the
        // layout's shift), the touch one scaled by the focus.
        val player = game.player
        if (player.rooms.isNotEmpty()) {
            val shift = player.type.layoutShift
            val enemy = game.enemy
            val dx = (if (enemy == null) 350 else if (enemy.isUsingBossUI) 150 else 200) + shift.x
            val dy = 170 + shift.y
            val off = player.offset
            val x0 = player.rooms.minOf { it.x + off.x } * Constants.ROOM_SIZE + dx
            val y0 = player.rooms.minOf { it.y + off.y } * Constants.ROOM_SIZE + dy
            val x1 = player.rooms.maxOf { it.x + it.width + off.x } * Constants.ROOM_SIZE + dx
            val y1 = player.rooms.maxOf { it.y + it.height + off.y } * Constants.ROOM_SIZE + dy
            val s = game.playerShipRenderScale
            val o = game.playerShipOffsetPoint
            out += TouchPointers.Anchor("ship:player", floatArrayOf(x0.f, y0.f, x1.f, y1.f), s, s, o.x - hudX - dx * s, o.y - dy * s)
        }
        if (game.enemy != null) game.enemyShipUI?.touchAnchor?.let { out += it }
        return out
    }

    /** The HUD proper, over the world; the top buttons and target box were drawn before. */
    fun render(gc: GameContainer, g: Graphics) {
        g.pushTransform()
        g.translate(hudX.f, 0f)
        drawCrew(g)
        drawSystems(g)
        val right = maxOf(if (storeHere) storeButton.x + storeButton.w else shipButton.x + shipButton.w - 10, topRowEnd.roundToInt())
        ui.touchDrawHazards(g, (right + optionsButton.x) / 2)
        // The banners sit over the open box, except door control's: its panel and drawer stay readable.
        if (commandMode && commandReason != REASON_DOORS) {
            drawOpenSystem(g)
            drawBanners()
        } else {
            drawBanners()
            drawOpenSystem(g)
        }
        drawCrystal()
        drawStatus(g)
        TouchPointers.beginFrame()
        extras.hudAnchors = anchors()
        extras.render(g)
        if (primary == -1) statusTip = null
        statusTip?.let { g.tooltip = it }
        g.popTransform()
    }

    private companion object {
        const val FOCUS_TIME = 0.1f
        const val SHIP_X = 606
        const val SHIP_STEP = 84f
        const val STORE_STEP = 95f
        const val BOX_Y = 689
        const val PANEL_BASE = BOX_Y + 50
        const val SECTION_STEP = 68
        const val STATUS_GAP = 4
        const val TAB_SLIDE_TIME = 0.1f
        const val EMPTY_SLOT_ALPHA = 0.5f
        const val CREW_X = 10
        const val CREW_TOP = 116
        const val DESK_CREW_TOP = 155
        const val CREW_SPACING = 30

        /** How long a finger stays still on a crew box to pick it up for reordering (Android's long press is 0.4-0.5 s). */
        const val REORDER_HOLD = 0.5f

        /** How far (HUD px) a finger may wander and still be held still. */
        const val REORDER_SLOP = 8.0
        const val CREW_W = 86
        const val CREW_H = 27
        const val REASON_CREW_DRAG = 0
        const val REASON_ROOM = 1
        const val REASON_DOORS = 2
        const val REASON_MIND = 4
        const val REASON_TARGET = 5
        const val REASON_TELE_ARRIVE = 6
        const val REASON_TELE_LEAVE = 7
        val BUTTON_TEXT = Colour(235 / 255f, 245 / 255f, 229 / 255f, 1f)
        val BUTTON_PRESSED = Colour(0.243f, 0.490f, 0.514f, 1f)
        val PAUSE_FLASH = Colour(1f, 230 / 255f, 94 / 255f, 1f)
        val AUTOFIRE_TEXT = Colour(11 / 255f, 3 / 255f, 6 / 255f, 1f)
        val ICON_GREEN = Colour(120 / 255f, 1f, 120 / 255f, 1f)
        val ICON_RED = Colour(1f, 70 / 255f, 70 / 255f, 1f)
        val ICON_ORANGE = Colour(1f, 150 / 255f, 50 / 255f, 1f)
        val ICON_GREY = Colour(0.5f, 0.5f, 0.5f, 1f)
        val SLOT_BLACK = Colour(0.06f, 0.02f, 0.03f, 1f)
        val SLOT_LIGHT = Constants.TEXT_BUTTON_NORMAL
        val REACTOR_USED = Colour(235 / 255f, 245 / 255f, 229 / 255f, 1f)
    }
}
