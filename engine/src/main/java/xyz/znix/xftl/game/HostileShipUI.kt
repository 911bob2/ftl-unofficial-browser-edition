package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.PlatformSpecific
import kotlin.math.roundToInt

class HostileShipUI(private val game: InGameState, private val enemy: Ship) {
    companion object {
        // The escaping ship's JumpLeave starts the same 2 s jump tracker as the player's (InGameState).
        private const val FLY_OUT_TIME = InGameState.JUMP_TRACKER_TIME

        /**
         * How much smaller than vanilla the enemy ship box is drawn on
         * touch layouts - see [boxScale].
         */
        private const val TOUCH_BOX_SCALE = 0.9f

        private val HS_BOX_TEXT = Colour(0f, 0f, 0f, 0.5f)

        /** CombatControl's origin on the 1280x720 canvas (measured against the original). */
        private const val COMBAT_ORIGIN_X = 747
    }


    private val mutableShipPos = Point(0, 0)
    val shipPos: IPoint get() = mutableShipPos

    /**
     * The scale the enemy's box - frame, labels, bars and the ship drawn
     * inside it - is rendered at. Vanilla is 1; on touch layouts the box
     * is drawn slightly smaller ([TOUCH_BOX_SCALE]) and anchored to the
     * vanilla content top/right edges, so it clears the scaled systems
     * strip while leaving the FTL escape warning (drawn above the box)
     * its vanilla headroom. While any touch targeting mode is active
     * (weapon targeting or one of the teleporter/hacking/mind-control
     * room pickers) it returns to full size ("scaled slightly up" from
     * the shrunken default), making the rooms easier to tap. Hit-testing
     * code must convert through [convertScreenToShipRender] /
     * [shipRenderToScreen].
     */
    private val boxScale: Float
        get() = when {
            game.shipUI.touch != null -> game.shipUI.touch!!.enemyScale
            enemy.isUsingBossUI -> 1f
            game.isTouchTargeting() -> 1f
            PlatformSpecific.INSTANCE.isTouchUi -> TOUCH_BOX_SCALE
            else -> 1f
        }

    // Issue #4: the jump-away animation state (goes from 1 to 0). Started
    // by [startFlyOut] when the ship's escape timer expires; InGameState
    // removes the ship once [isFlyOutDone] is true.
    private var flyOut = 0f
    val isFlyOutDone: Boolean get() = flyOut <= 0f

    fun startFlyOut() {
        flyOut = 1f
    }

    /**
     * Convert a screen-space position to the enemy ship's render space
     * (what (mouse - shipPos) used to be), accounting for the touch
     * layout's scaled box. Complements [getShipPos]. Only valid while
     * the enemy ship is displayed.
     */
    fun convertScreenToShipRender(point: Point) {
        point.x = ((point.x - mutableShipPos.x) / boxScale).roundToInt()
        point.y = ((point.y - mutableShipPos.y) / boxScale).roundToInt()
    }

    /**
     * Convert a position in the enemy ship's render space (e.g. a
     * crewmember's screenX/screenY) to screen space, for hit-testing
     * things on the enemy ship - your boarding party, mainly.
     */
    fun shipRenderToScreen(x: Int, y: Int): IPoint = Point(
        (mutableShipPos.x + boxScale * x).roundToInt(),
        (mutableShipPos.y + boxScale * y).roundToInt()
    )

    private val font = game.getFont("HL2")
    private val titleFont = game.getFont("HL2", 2f)
    private val statusFont = game.getFont("JustinFont8")
    private val jumpWarningFont = game.getFont("HL1", 2f)

    private val shieldIconStandard = game.getImg("img/combatUI/box_hostiles_shield1.png")
    private val shieldIconBroken = game.getImg("img/combatUI/box_hostiles_shield2.png")
    private val shieldIconStandardHacked = game.getImg("img/combatUI/box_hostiles_shield2_hacked_charged.png")
    private val shieldIconBrokenHacked = game.getImg("img/combatUI/box_hostiles_shield2_hacked.png")

    private val superShieldBar = game.getImg("img/combatUI/box_hostiles_shield_super5.png")
    private val superShieldBarBoss = game.getImg("img/combatUI/box_hostiles_shield_super12.png")
    private val shieldChargeBar = game.getImg("img/combatUI/box_hostiles_shield_charge.png")

    private val systemTooltips = HashMap<String, xyz.znix.xftl.rendering.FixedStandardTooltip>()

    // Hyperspace <hullNumbers>: the boxes with a number field (ResourceControl::GetImageId hook).
    private val boxNormal = game.getImg(hsBox("img/combatUI/box_hostiles2.png"))
    private val boxBoss = game.getImg(hsBox("img/combatUI/box_hostiles_boss.png"))

    private fun hsBox(path: String) = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsHullNumbers.imagePath(path) else path

    private val maskNormal = game.getImg("img/combatUI/box_hostiles_mask.png")
    private val maskBoss = game.getImg("img/combatUI/box_hostiles_boss_mask.png")

    fun render(gc: GameContainer, g: Graphics, hoveredRoom: Room?, interiorVisible: Boolean, isHostile: Boolean) {
        // Tick the jump-away animation (issue #4). Ticked from render so it
        // keeps playing while the game is frozen for the animation.
        if (flyOut > 0) {
            flyOut = (flyOut - game.renderingDeltaTime / FLY_OUT_TIME).coerceAtLeast(0f)
        }

        game.shipUI.touch?.let {
            renderTouch(gc, g, it, hoveredRoom, interiorVisible, isHostile)
            return
        }

        val box = when (enemy.isUsingBossUI) {
            true -> boxBoss
            false -> boxNormal
        }
        val mask = when (enemy.isUsingBossUI) {
            true -> maskBoss
            false -> maskNormal
        }

        val filter = when (isHostile) {
            true -> Constants.SHIP_BOX_HOSTILE
            false -> Constants.SHIP_BOX_NEUTRAL
        }
        val textColour = when {
            // Hyperspace sessions: the label colour is half-transparent black, so it keeps half of whatever box image
            // the mod supplies (MV's white box gives 127,89,89 hostile on the oracle frame; vanilla's box gives the
            // two constants below, which are that colour over the vanilla box).
            xyz.znix.xftl.hs.HS.active -> HS_BOX_TEXT
            isHostile -> Constants.SHIP_BOX_TEXT_HOSTILE
            else -> Constants.SHIP_BOX_TEXT_NEUTRAL
        }

        val leftGlow: Int
        val rightGlow: Int
        val topGlow: Int
        val bottomGlow: Int
        // The vanilla gap between the box's content edge and the screen's
        // right edge, and the vanilla y of the content box's top.
        val rightMargin: Int
        val vanillaBoxY: Int
        if (enemy.isUsingBossUI) {
            leftGlow = 4
            rightGlow = 4
            topGlow = 4
            bottomGlow = 4
            rightMargin = 10
            vanillaBoxY = 11
        } else {
            leftGlow = 10
            rightGlow = 20
            topGlow = 9
            bottomGlow = 17
            rightMargin = 18
            vanillaBoxY = 54
        }

        // On touch layouts the whole box - frame, text, bars and the ship
        // inside it - is drawn scaled down by [boxScale], anchored to the
        // vanilla content top/right position. The full-size box's bottom
        // (content bottom y = 583) collides with the scaled systems
        // strip's weapon/drone boxes (y~541), and the previous fix of
        // lifting the box pushed the FTL escape warning - drawn at
        // boxY - 10 - off the top of the screen. Drawing everything
        // slightly smaller keeps the warning's vanilla headroom while
        // the content bottom rises to ~530, clearing the strip. The boss
        // frame stays full-size: even scaled it can't clear the strip,
        // and it rarely overlaps one that long
        // (touch-strip-scaling-plan.md).
        val scale = boxScale

        // The screen-space position of the box image's top-left corner,
        // keeping the vanilla content top and right edges.
        val anchorX = gc.width - (box.width - rightGlow) - rightMargin +
                ((box.width - rightGlow) * (1 - scale)).roundToInt()
        val anchorY = vanillaBoxY - (topGlow * scale).roundToInt()

        // Everything from here to the escape warning is drawn through a
        // translate+scale transform, in coordinates local to the box
        // image (the vanilla layout with the image's top-left corner at
        // the origin). At a scale of 1 every element lands exactly where
        // vanilla draws it.
        val boxX = leftGlow
        val boxY = topGlow
        val boxRightX = box.width - rightGlow
        val boxBottomY = box.height - bottomGlow

        // CombatControl::AddEnemyShip (0x0057ce70): from the combat origin (747,0) the
        // ship sits at x = 328 - shipBox width / 2 and y = its layout's VERTICAL + 130;
        // the boss frame has its own branch (below).
        val layoutLocalX: Int
        val layoutLocalY: Int
        val hsBoss = enemy.hsBossShip
        if (hsBoss != null && enemy.boss?.useBossShipUI != true) {
            // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp
            // (CombatControl::AddEnemyShip hook): boxPosition (5, 7), targetPosition = (boxPosition.x + 261 -
            // shipBox.w / 2, boxPosition.y + yOffset) from the combat origin.
            val shipBoxWidth = (enemy.rooms.maxOf { it.x + it.width } - enemy.rooms.minOf { it.x }) * Constants.ROOM_SIZE
            layoutLocalX = COMBAT_ORIGIN_X + 5 + 261 - shipBoxWidth / 2 - anchorX
            layoutLocalY = 7 + hsBoss.yOffset - anchorY
        } else if (enemy.isUsingBossUI) {
            // CombatControl::AddEnemyShip (0x57ce70), boss branch (CompleteShip::IsBoss): boxPosition (5, 7), the ship
            // at (boxPosition.x + 261 + 3 - shipBox.w / 2, boxPosition.y + 120) from the combat origin, no layout shift
            // (1.6.14 code; HS oracle r3-flag1: the flagship, all three stages, 0 px). The engine centred the hull
            // image before (4 px right, 34 px up).
            val shipBoxWidth = (enemy.rooms.maxOf { it.x + it.width } - enemy.rooms.minOf { it.x }) * Constants.ROOM_SIZE
            layoutLocalX = COMBAT_ORIGIN_X + 5 + 261 + 3 - shipBoxWidth / 2 - anchorX
            layoutLocalY = 7 + 120 - anchorY
        } else {
            val shipBoxWidth = (enemy.rooms.maxOf { it.x + it.width } - enemy.rooms.minOf { it.x }) * Constants.ROOM_SIZE
            layoutLocalX = COMBAT_ORIGIN_X + 328 - shipBoxWidth / 2 - anchorX
            layoutLocalY = enemy.type.layoutShift.y + 130 - anchorY
        }
        mutableShipPos.x = (anchorX + scale * layoutLocalX).roundToInt()
        mutableShipPos.y = (anchorY + scale * layoutLocalY).roundToInt()

        g.pushTransform()
        g.translate(anchorX.f, anchorY.f)
        g.scale(scale, scale)

        box.draw(0, 0, filter)

        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            mask.draw(0, 0)
        }) {
            g.pushTransform()
            g.translate(layoutLocalX.f, layoutLocalY.f)

            // Jumping away: ShipManager::OnRender (0x4a3c00) draws only the shield bubble and Ship::OnRenderJump
            // (0x48cad0) with the shared jump fade (InGameState.browserJumpFade): the interior and the weapon
            // mounts are gone from the first frame, the hull keeps its size and fades out (alpha 1 - fade) while
            // the flash climbs the middle of the hull image from the bottom to the top.
            if (flyOut > 0) {
                enemy.renderJump(g, InGameState.browserJumpFade((1f - flyOut) * FLY_OUT_TIME))
            } else {
                enemy.render(g, interiorVisible, hoveredRoom)
            }

            val playerWeapons = game.shipUI.ship.weapons
            if (playerWeapons != null) {
                enemy.renderTargeting(g, playerWeapons.selectedTargets)
            }

            g.popTransform()
        }

        // The boss frame's bars sit 2 px further right and its TARGET label 2 px further left than the engine had
        // them (1.6.14 capture vn-boss vb1-arrive and HS oracle r3-flag1 f02-arrive: both at the same place;
        // CombatControl::RenderTarget 0x578950 draws the bars from boxPosition + (17, 45)).
        val textX = boxX + if (enemy.isUsingBossUI) 4 else 2

        // Draw the title
        // Measured against the original's TARGET label.
        val titleX = textX + 3
        titleFont.drawString(titleX.f, boxY + 16f, game.translator["target_window"], textColour)

        // Draw the class and relationship text
        val statusTextX = boxRightX - 11
        val classY = boxY + 38
        drawStatus(statusTextX, classY, isHostile)

        // The number is placed in screen coordinates: screen (0,0) is at -(box position at scale 1) here.
        drawHullAndShields(g, textX, boxY, filter, textColour, isHostile,
            -(gc.width - (box.width - rightGlow) - rightMargin).f, -(vanillaBoxY - topGlow).f)

        // The original's target box shows the enemy's power bars with level 3 sensors, but never the flagship's. Hyperspace
        // (CustomBoss.cpp RenderTarget hook) draws a boss's boxes again afterwards, with the bars when the sensors show them.
        val flagshipHidesPower = !xyz.znix.xftl.hs.HS.active && enemy.boss is FlagshipBoss
        val powerVisible = (game.player.sensors?.showsEnemyPowerLevels == true && !flagshipHidesPower)
                || game.debugFlags.showEverything.set

        // Draw the enemy's systems, in the order of the blueprint's systemList (the
        // original's vSystemList order).
        // Hovering an icon glows it and shows its tooltip_<system>_enemy text.
        val mouseLocalX = ((gc.input.mouseX - anchorX) / scale).roundToInt()
        val mouseLocalY = ((gc.input.mouseY - anchorY) / scale).roundToInt()
        // CombatControl::UpdateSysBoxes (0x57ff60) places the boxes 30 px apart at boxPosition + (40, 470), with the
        // boss frame at boxPosition + (80, 535), both 45 px further left with more than twelve systems. In the boss
        // frame that is 47 px right and 9 px down of the normal row's offsets from the box (HS oracle r2-delta 10d,
        // the Laboratory Delta <bossShip>, and r3-flag1, the flagship: 0 px).
        val sortedSystems = enemy.systems.sortedBy { it.configuration.spec.systemIndex }
        var rowX = if (enemy.isUsingBossUI) 47 else 0
        val rowY = if (enemy.isUsingBossUI) 9 else 0
        if (sortedSystems.size > 12) rowX -= 45
        for ((i, sys) in sortedSystems.withIndex()) {
            val y = boxBottomY - 49 + rowY
            val x = boxX + i * 30 + 49 + rowX

            val roomPowerVisible = powerVisible || sys.hackedBy?.isPoweredUp == true
            val hovered = mouseLocalX in x - 1 until x + 25 && mouseLocalY in y until y + 26

            sys.drawIconAndPower(game, g, false, roomPowerVisible, hovered, x, y)
            if (hovered && !game.shipUI.isWindowOpen)
                g.tooltip = systemTooltips.getOrPut(sys.codename) { xyz.znix.xftl.rendering.FixedStandardTooltip(game, "tooltip_${sys.codename}_enemy") }
        }

        g.popTransform()

        // Hyperspace enemy ship icons (EnemyShipIcons.cpp: CombatControl::RenderTarget, after the original)
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.render.HsShipIcons.render(game, g, enemy, enemy.isUsingBossUI, filter, gc.input.mouseX, gc.input.mouseY)
            // TriggeredEvents.cpp: CombatControl::RenderTarget hook (the combat boxes of triggered events)
            xyz.znix.xftl.hs.events.HsTriggeredGui.renderCombat(game, g, enemy.isUsingBossUI, gc.input.mouseX, gc.input.mouseY)
        }

        // Draw the FTL charging warning, if relevant. It sits above the
        // box and is drawn in screen space at the vanilla position, so
        // it keeps its full size and headroom even when the box itself
        // is scaled down on touch layouts.
        val centreX = (anchorX + scale * box.width / 2).roundToInt()
        // CombatControl::RenderTarget (0x578950): only for a hostile ship that is escaping and not dying.
        if (isHostile && !enemy.isDead)
            drawEscapeWarning(centreX, vanillaBoxY - 10)
    }

    private fun drawHullAndShields(g: Graphics, textX: Int, boxY: Int, filter: Colour, textColour: Colour,
                                   isHostile: Boolean, originX: Float, originY: Float) {
        // Draw the hull level
        val hullY = boxY + 20 + 4
        renderSmallbar(textX, hullY, "status_hull", filter, textColour)
        var hpWidth = 11 * enemy.health
        val hpX = textX + 5
        val hpY = hullY + 12
        val hull = game.getImg("img/combatUI/box_hostiles_hull2.png")
        if (xyz.znix.xftl.hs.HS.active) {
            // Hyperspace <hullBars> (CachedImage::SetPartial during CombatControl::RenderTarget): the bar's texture
            // fraction for hulls above maxHull; vanilla's 1/22 of the bar per point otherwise.
            val boss = enemy.isUsingBossUI
            val fraction = xyz.znix.xftl.hs.render.HsHullNumbers.enemyBarFraction(-1f, enemy.health, enemy.maxHealth, boss)
            if (fraction >= 0f) hpWidth = (fraction * hull.width).roundToInt()
        }
        hull.draw(
            hpX.f, hpY.f, hpX.f + hpWidth, hpY.f + hull.height,
            0f, 0f, hpWidth.f, hull.height.f,
            1f, Constants.SHIP_HEALTH_HIGH
        )
        if (xyz.znix.xftl.hs.HS.active) {
            val boss = enemy.isUsingBossUI
            // Type 1: a full bar in the colour of the layer below, then the top layer (RenderTarget, after super).
            xyz.znix.xftl.hs.render.HsHullNumbers.layers(enemy.health, boss)?.let { l ->
                for ((colourIndex, frac) in listOf(l[0] to l[2], l[1] to l[3])) {
                    val w = (frac * hull.width).roundToInt()
                    hull.draw(hpX.f, hpY.f, hpX.f + w, hpY.f + hull.height, 0f, 0f, w.f, hull.height.f, 1f,
                        xyz.znix.xftl.hs.render.HsHullNumbers.barColour(colourIndex.toInt()))
                }
            }
            xyz.znix.xftl.hs.render.HsHullNumbers.drawEnemy(game.content, enemy.health, isHostile, boss, "en", originX, originY)
        }

        enemy.shields?.let { shields ->
            // Draw the shield bubbles
            val shieldsY = hullY + 27
            renderSmallbar(textX, shieldsY, "status_shields", filter, textColour)

            var bubbleX = textX + 8

            for (i in 0 until shields.selectedShieldBars) {
                val intact = i < shields.activeShields
                val hacked = shields.isHackActive
                val img = when {
                    hacked && intact -> shieldIconStandardHacked
                    hacked && !intact -> shieldIconBrokenHacked
                    intact -> shieldIconStandard
                    else -> shieldIconBroken
                }
                img.draw(bubbleX, shieldsY + 15)
                bubbleX += 23
            }

            if (enemy.superShield != 0) {
                // TODO support ships with a super-shield but no shields system
                bubbleX += 10
                val superShieldY = shieldsY + 15 + 5

                if (xyz.znix.xftl.hs.HS.active) {
                    // Hyperspace CombatControl::RenderShipStatus: the 5- or 12-point box or a drawn one, ten pixels per point.
                    xyz.znix.xftl.hs.systems.HsSuperShield.drawEnemyBar(g, enemy, bubbleX, superShieldY, superShieldBar, superShieldBarBoss)
                } else {
                superShieldBar.draw(bubbleX, superShieldY)

                val width = 50f * enemy.superShield / enemy.maxSuperShield
                g.colour = Constants.SYS_ENERGY_ACTIVE
                g.fillRect(bubbleX + 3f, superShieldY + 3f, width, 7f)
                }
            } else if (shields.rechargeTimer != 0f) {
                // Draw the charge bar
                shieldChargeBar.draw(textX + 5, shieldsY + 39)

                val progress = shields.rechargeTimer / shields.rechargeDelay
                val colour = when {
                    shields.isHackActive -> Constants.SHIELD_BAR_HACKED
                    else -> Constants.SHIELD_BAR_NORMAL
                }

                val width = (56 * progress)
                g.colour = colour
                g.fillRect(textX.f + 5 + 3, shieldsY.f + 39 + 3, width, 6f)
            }
        }
    }

    private fun faded(colour: Colour, alpha: Float) = Colour(colour.r, colour.g, colour.b, colour.a * alpha)

    /**
     * The touch layout's target box: a window that grows when the enemy's ship has the focus, with the ship scaled
     * inside it about its centre. Its rooms are reached through [convertScreenToShipRender] as on the desktop.
     */
    private val touchSystemTooltips = HashMap<String, xyz.znix.xftl.rendering.AboveFingerTooltip>()

    private fun renderTouch(gc: GameContainer, g: Graphics, hud: TouchHud, hoveredRoom: Room?, interiorVisible: Boolean, isHostile: Boolean) {
        val boss = enemy.isUsingBossUI
        val rect = hud.targetBox(boss)
        val x = rect[0]
        val y = rect[1]
        val w = rect[2]
        val h = rect[3]
        val s = hud.enemyScale
        val hudX = TouchMode.hudX
        val filter = if (isHostile) Constants.SHIP_BOX_HOSTILE else Constants.SHIP_BOX_NEUTRAL
        val textColour = when {
            xyz.znix.xftl.hs.HS.active -> HS_BOX_TEXT
            isHostile -> Constants.SHIP_BOX_TEXT_HOSTILE
            else -> Constants.SHIP_BOX_TEXT_NEUTRAL
        }

        val graphWidth = (enemy.rooms.maxOf { it.x + it.width } - enemy.rooms.minOf { it.x }) * Constants.ROOM_SIZE
        val originX = if (boss) 491 + 240 - graphWidth / 2f + (542 - w) / 2f
        else 491 + 295 - graphWidth / 2f + (432 - w) / 2f + 20 * (1 - s)
        val originY = if (boss) 167f else enemy.type.layoutShift.y + 157f
        val graphHeight = (enemy.rooms.maxOf { it.y + it.height } - enemy.rooms.minOf { it.y }) * Constants.ROOM_SIZE
        val shipX = originX + (1 - s) * graphWidth / 2f
        val shipY = originY + (1 - s) * graphHeight / 2f
        mutableShipPos.x = hudX + shipX.roundToInt()
        mutableShipPos.y = shipY.roundToInt()

        g.pushTransform()
        g.translate(hudX.f, 0f)
        game.windowRenderer.renderTinted(x, y, w, h, filter)

        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            g.colour = Colour.white
            g.fillRect(x + 2f, y + 2f, w - 4f, h - 4f)
        }) {
            g.pushTransform()
            g.translate(shipX, shipY)
            g.scale(s, s)
            if (flyOut > 0) {
                enemy.renderJump(g, InGameState.browserJumpFade((1f - flyOut) * FLY_OUT_TIME))
            } else {
                enemy.render(g, interiorVisible, hoveredRoom)
            }
            val playerWeapons = game.shipUI.ship.weapons
            if (playerWeapons != null) enemy.renderTargeting(g, playerWeapons.selectedTargets)
            g.popTransform()
        }

        // The hull and shields sit at the same offset from the box's corner at every focus.
        val box = if (boss) boxBoss else boxNormal
        val rightGlow = if (boss) 4 else 20
        val leftGlow = if (boss) 4 else 10
        val rightMargin = if (boss) 10 else 18
        val vanillaBoxY = if (boss) 11 else 54
        val desktopLeft = 1280 - (box.width - rightGlow) - rightMargin + leftGlow
        drawHullAndShields(g, x + 4, y - 7, filter, textColour, isHostile, (x - desktopLeft).f, (y - vanillaBoxY).f)

        // The class and relationship fade out as the box shrinks.
        val labelAlpha = 1f - hud.focusAmount
        if (labelAlpha > 0f) drawStatus(x + w - 10, y + 18, isHostile, labelAlpha)

        val sortedSystems = enemy.systems.sortedBy { it.configuration.spec.systemIndex }
        val powerVisible = game.player.sensors?.showsEnemyPowerLevels == true || game.debugFlags.showEverything.set
        val fingerX = gc.input.mouseX - hudX
        val fingerY = gc.input.mouseY
        for ((i, sys) in sortedSystems.withIndex()) {
            val iconX = 491 + 80 - 7 + 30 * i + (432 - w) + 19
            val iconY = 72 + 477 + (h - 534) / 2 + 18
            val roomPowerVisible = powerVisible || sys.hackedBy?.isPoweredUp == true
            // The finger's position only counts with the box at full size, which is where the rectangles are.
            val hitX = 491 + 80 - 7 + 30 * i + 18
            val hovered = hud.focusAmount == 0f && fingerX in hitX until hitX + 28 && fingerY in 567 until 567 + 28
            sys.drawIconAndPower(game, g, false, roomPowerVisible, hovered, iconX, iconY)
            if (hovered && !game.shipUI.isWindowOpen)
                g.tooltip = touchSystemTooltips.getOrPut(sys.codename) { xyz.znix.xftl.rendering.AboveFingerTooltip(game, "tooltip_${sys.codename}_enemy") }.also {
                    it.centreX = hudX + hitX + 14
                    it.bottomY = 549
                }
        }

        if (xyz.znix.xftl.hs.HS.active) {
            g.pushTransform()
            g.translate((x - desktopLeft).f, (y - vanillaBoxY).f)
            val mx = fingerX - (x - desktopLeft)
            val my = fingerY - (y - vanillaBoxY)
            xyz.znix.xftl.hs.render.HsShipIcons.render(game, g, enemy, boss, filter, mx, my)
            xyz.znix.xftl.hs.events.HsTriggeredGui.renderCombat(game, g, boss, mx, my)
            g.popTransform()
        }

        if (isHostile && !enemy.isDead) {
            if (labelAlpha > 0.5f) drawEscapeWarning(x + 70 + jumpWarningFont.getWidth(game.translator["warning_ftl_charging"]) / 2, y + 97)
            else drawEscapeWarning(x + w / 2, y - 22, TouchMode.enabled)
        }
        g.popTransform()
    }

    private fun drawStatus(x: Int, classY: Int, isHostile: Boolean, alpha: Float = 1f) {
        val relationY = classY + 15

        enemy.type.shipClass?.let { enemyClass ->
            val shipClassName = game.translator[enemyClass]
            val classStr = game.translator["combat_class"].replaceArg(shipClassName)

            statusFont.drawStringLeftAligned(x.f, classY.f, classStr, faded(Constants.SHIP_STATUS_PLAIN, alpha))
        }

        val relationText = when (isHostile) {
            true -> game.translator["hostile"]
            false -> game.translator["neutral"]
        }
        val relationColour = when (isHostile) {
            true -> Constants.SHIP_STATUS_HOSTILE
            false -> Constants.SHIP_STATUS_PLAIN
        }
        val relationStr = game.translator["combat_relationship"].replaceArg(relationText)
        statusFont.drawStringLeftAligned(x.f, relationY.f, relationStr, faded(relationColour, alpha))
    }

    private fun drawEscapeWarning(centreX: Int, y: Int, stacked: Boolean = false) {
        val timeRemaining = enemy.escapeTimer ?: return

        val warningKey = when {
            !enemy.canChargeFTL -> "warning_ftl_delayed"
            timeRemaining <= 5f -> "warning_ftl_imminent"
            else -> "warning_ftl_charging"
        }

        var alpha = 1f

        // Flash the alpha if a jump is imminent.
        if (warningKey == "warning_ftl_imminent") {
            // Run the flashing animation while paused
            var timeNS = System.nanoTime()
            if (timeNS < 0) {
                timeNS += Long.MAX_VALUE
            }

            val period = 700_000_000
            val progress = (timeNS % period).toFloat() / period

            // Fade up then down
            alpha = progress * 2
            if (alpha > 1) {
                alpha = 2 - alpha
            }
        }

        // The small box has no room beside it on the touch layout: the short text stands over it, line on line.
        val lines = if (stacked) game.translator[warningKey + "_ipad"].split("\n") else listOf(game.translator[warningKey])
        for ((i, message) in lines.withIndex()) {
            // The text's own line breaks go downwards: Multiverse's warning opens with two, beside the box's title.
            val parts = message.split('\n')
            val partX = centreX - parts.maxOf { jumpWarningFont.getWidth(it) } / 2
            for ((n, part) in parts.withIndex())
                UIUtils.drawStringWithGlow(game, jumpWarningFont, part, partX, y - (lines.size - 1 - i) * 20 + n * 19, GlowColour.RED, alpha)
        }
    }

    private fun renderSmallbar(x: Int, y: Int, key: String, filter: Colour, textColour: Colour) {
        val text = game.translator[key]

        val textX = 2
        val textWidth = font.getWidth(text)

        val left = game.getImg("img/combatUI/box_hostiles_smallbar_left.png")
        left.draw(x, y, filter)
        val middle = game.getImg("img/combatUI/box_hostiles_smallbar_middle.png")
        val midWidth = textWidth + textX - left.width
        middle.draw(
            x.f + left.width, y.f,
            x.f + left.width + midWidth, y.f + middle.height,
            0f, 0f, middle.width.f, middle.height.f,
            1f, filter
        )

        val rightImg = game.getImg("img/combatUI/box_hostiles_smallbar_right.png")
        rightImg.draw(x + left.width + midWidth, y, filter)

        (if (xyz.znix.xftl.Languages.current == "ja") jaLabelFont else font).drawString(x + 2f, y + 9f, text, textColour)
    }

    private val jaLabelFont by lazy { game.getFont("HL1") }
}
