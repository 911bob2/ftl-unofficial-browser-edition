package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class JumpWindow(val game: InGameState, showSectorMap: () -> Unit, val jump: (Beacon?) -> Unit) : Window() {
    override val size = ConstPoint(752, 534)

    // Touch ergonomics: the map window is scaled up 1.2x and moved up.
    // The CANCEL button hangs below the declared window rect (local y
    // 541..577 vs height 534), so at 1.2x the window must sit high
    // enough to keep it on-screen: centreY <= 348 with 10px margin.
    override val renderScale = if (PlatformSpecific.INSTANCE.isTouchUi) 1.2f else 1f
    // Desktop: StarMap renders translated to its position, (340,83) on the canvas.
    override val windowCentreOffset: IPoint =
        if (PlatformSpecific.INSTANCE.isTouchUi) ConstPoint(0, -75) else ConstPoint(76, -10)

    // Tap-to-arm: first tap on a beacon/button highlights it, second tap
    // confirms (see Window.tapToArm). Runtime-gated on isTouchUi.
    override val tapToArm = true

    private val sectorInfoTab = game.getImg("img/map/side_sector.png")
    private val titleTab = game.getImg("img/map/side_beaconmap.png")
    private val nextSectorTab = game.getImg("img/map/side_nextsector.png")
    private val font = game.getFont("HL2", 3f)
    private val buttonFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)
    private val cutoutFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 14)
    private val cancelButtonOutline = game.getImg("img/main_menus/button_cancel_base.png")
    private val sectorInfoFont = game.getFont("c&cnew", 2f)
    private val beaconLabelFont = game.getFont("HL1")
    private val distressBeaconFont = game.getFont("HL1", 2f) // Out-of-fuel UI

    private val outlineImage = game.getImg("img/window_outline.png")

    private val lineImg = game.getImg("img/map/dotted_line.png")
    private val targetBox = game.getImg("img/map/map_targetbox.png")

    private val fleetAdvanceImg = game.getImg("img/map/map_warningcircle_point.png")
    private val fleetAdvanceColour = Colour(246, 128, 125, 21)
    private val fleetControlImg = game.getImg("img/map/map_warningcircle.png")
    private val fleetControlTile = game.getImg("img/map/map_warningcircle_tile.png")

    private val beaconShadow = game.getImg("img/map/map_icon_diamond_shadow.png")
    private val beaconEnvironmentHazard = game.getImg("img/map/map_icon_hazard.png")

    // Nebula puffs (GitHub issue #74): vanilla envelopes each nebula beacon
    // on the map in one of these soft clouds, making the sector's nebula
    // areas visible at a glance. The variant is picked per-beacon, so it's
    // stable between map openings.
    private val nebulaPuffs = listOf(
        game.getImg("img/map/nebula_0.png"),
        game.getImg("img/map/nebula_1.png"),
        game.getImg("img/map/nebula_2.png"),
        game.getImg("img/map/nebula_3.png"),
    )
    private val beaconYellow = game.getImg("img/map/map_icon_diamond_yellow.png")
    private val beaconBlue = game.getImg("img/map/map_icon_diamond_blue.png")
    private val beaconDanger = game.getImg("img/map/map_icon_triangle_red.png")
    private val beaconShipPresent = game.getImg("img/map/map_icon_triangle_yellow.png")
    private val beaconWillOvertakeCircle = game.getImg("img/tutorial/player_circle.png") // This is the right image!
    private val beaconOvertaken = game.getImg("img/map/map_icon_warning.png")

    private val playerShip = game.getImg("img/map/map_icon_ship.png")
    private val playerShipNoFuel = game.getImg("img/map/map_icon_ship_fuel.png")

    private val noFuelImage = game.getImg("img/map/map_fuel_text_nofuel.png")
    private val waitingImage = game.getImg("img/map/map_fuel_text_waiting.png")

    /**
     * StarMap's 2 s wait tracker (0x6b0), started by WAIT: "Waiting..." replaces NO FUEL and the map takes no
     * input until it ends, when the wait happens.
     */
    private var waitingTimer = 0f
    internal val isWaiting: Boolean get() = waitingTimer > 0f
    private val waitDistressFrame = game.getImg("img/map/side_wait_distress.png")
    private val fuelDistressFlashLight = game.getImg("img/map/side_distressbeacon_redlight.png")

    private val beaconOffset = Point(-beaconYellow.width / 2, -beaconYellow.height / 2)

    // The offsets to the coordinate system the beacons are positioned on
    private val mapBase = Point(0, 0)

    private val sector = game.currentBeacon.sector

    private val flashTimerBase = System.nanoTime()

    // The set of all the beacons we've visited a neighbour of - this
    // is used to figure out which beacons to display information about.
    private val neighbourVisSet = sector.beacons.filter { it.visited }.flatMap { it.neighbours }.toSet()

    private val labelWhite = (1..3).map { game.getImg("img/map/map_box_white_$it.png") }
    private val labelGreen = (1..3).map { game.getImg("img/map/map_box_green_$it.png") }
    private val labelPurple = (1..3).map { game.getImg("img/map/map_box_purple_$it.png") }

    val cancelButton: Button
    val nextSectorButton: Button
    val noFuelButtons: List<Button>
    private var browserWaitButton: Button? = null

    // StarMap's constructor loads zone_1-3 (0x005f1d20); its render (0x005fdbf0) picks one by the sector,
    // world level % 3, and draws it under a 0.75 colour tint.
    val background = game.getImg("img/map/zone_${sector.sectorNumber % 3 + 1}.png")

    /**
     * True if the player's out-of-fuel distress beacon is enabled.
     */
    var fuelDistressOn: Boolean
        get() = game.fuelDistressBeacon
        set(value) { game.fuelDistressBeacon = value }

    private var fuelDistressFlashTimer: Float = 0f
    private val fuelDistressFlashOn: Boolean get() = fuelDistressOn && fuelDistressFlashTimer < 0.5f

    var hovered: Beacon? = null

    private var playerRotation: Float = (0f..10f).random(VisualRandom)

    private val outOfFuel: Boolean get() = game.player.fuelCount == 0

    init {
        // The buttons are fiddly to set up, as they move depending on
        // the length of the text inside them.

        // StarMap's constructor (0x005f1d20) right-anchors both TextButtons with
        // SetAutoWidth: font 63's measured width plus twice the margin, never
        // narrower than the width they were created with.
        val cancelText = game.translator["button_cancel"]
        val cancelButtonWidth = maxOf(buttonFont.measure(cancelText) + 3 * 2, 0x7c)
        cancelButton = object : Buttons.BasicButton(
            game, size + ConstPoint(-31 - cancelButtonWidth, 7),
            ConstPoint(cancelButtonWidth, 30), cancelText,
            3, font, 24,
            ::cancelClicked
        ) {
            override val nativeFont = buttonFont
        }
        buttons += cancelButton

        // Next sector button
        val nsText = game.translator["button_nextsector"]
        val nsButtonWidth = maxOf(buttonFont.measure(nsText) + 4 * 2, 0xe2)
        nextSectorButton = object : Buttons.BasicButton(
            game, ConstPoint(size.x - 12 - nsButtonWidth, 12),
            ConstPoint(nsButtonWidth, 36), nsText,
            3, font, 27, showSectorMap
        ) {
            override val nativeFont = buttonFont
        }

        // The sector exit is a jump like any other (ShipManager::JumpLeave 0x49c9c0 takes the fuel): out of
        // fuel the map shows the NO FUEL controls instead and no Next Sector button.
        if (game.currentBeacon.isExit && !outOfFuel)
            buttons += nextSectorButton

        // Hacky, the rendering code is also what populates the buttons
        noFuelButtons = ArrayList()
        drawNoFuelUI(noFuelButtons)
        if (outOfFuel)
            buttons += noFuelButtons
        else if (sector.isLastStand) {
            val text = game.translator["button_wait"]
            browserWaitButton = object : Buttons.BasicButton(game, ConstPoint(20, size.y - 45),
                ConstPoint(font.getWidth(text) + 16, 36), text, 4, font, 27, {
                    if (game.browserCanWait()) {
                        jump(game.currentBeacon)
                        ftl.browser.BrowserLastStand.waitAtBeacon(game)
                    }
                }) {
                override val disabled: Boolean get() = !game.browserCanWait()
            }.also { buttons += it }
        }
    }

    /**
     * Draw the insides of the window. This must be done with a stencil.
     */
    private fun drawMapContent(g: Graphics) {
        // Draw the background image, offset 4px to account for the line wall of the window.
        background.draw(position.x + 4f, position.y + 4f, Colour(0.75f, 0.75f, 0.75f, 1f))

        mapBase.x = position.x + Sector.OFFSET.x
        mapBase.y = position.y + Sector.OFFSET.y

        // In the last stand, the danger stripes sit behind everything else.
        // TODO draw the player and boss ships below this.
        if (sector.isLastStand) {
            drawLastStandDangerZone(g)
        }

        // Draw the connections to the adjacent beacons.
        drawBeaconLinesTo(g, game.currentBeacon, Constants.BEACON_LINE_PLAYER) { true }

        // Draw the line showing where the flagship will next jump
        for (boss in sector.bosses) {
            val currentBeacon = boss.beacon ?: continue
            val nextBeacon = boss.nextBeacon ?: continue

            if (!boss.jumping) {
                // Draw a dotted line if the flagship isn't jumping this turn
                drawBeaconLine(
                    g,
                    currentBeacon,
                    nextBeacon,
                    Constants.BEACON_LINE_FLAGSHIP
                )
            } else {
                // Draw a wide, continuous line if the flagship is jumping this turn.
                val a = mapBase + currentBeacon.pos
                val b = mapBase + nextBeacon.pos

                boss.drawJumpArc(g, a, b)
            }
        }

        // Make a local immutable copy to avoid nullability errors
        val hovered = hovered

        if (hovered != null && hovered != game.currentBeacon && (game.mainGame?.profile?.showBeaconPaths != false)) {
            // Draw the lines between the hovered beacon and it's neighbours
            drawBeaconLinesTo(g, hovered, Constants.BEACON_LINE_HOVER) { it != game.currentBeacon }
        }

        val nextFleetPos = Point(sector.dangerZoneCentre)
        val tutorial = game.mainGame?.isTutorialRunning == true
        if (!tutorial) nextFleetPos.x += sector.getFleetAdvanceFor(game.currentBeacon)

        // Whether or not the ship is equipped with long-range scanners
        val hasLRS = game.player.hasAugment(AugmentBlueprint.LONG_RANGE_SCANNERS)

        // The sector's nebula clouds, recorded when the map was generated.
        for (cloud in sector.nebulaClouds) {
            game.getImg(cloud.image).draw(mapBase.x + cloud.x, mapBase.y + cloud.y)
        }

        // Draw the beacons
        for (beacon in sector.beacons) {
            val centrePos = mapBase + beacon.pos
            val pos = centrePos + beaconOffset

            val isNeighbour = neighbourVisSet.contains(beacon)
            val showBasicInfo = beacon.visited || sector.mapRevealed || isNeighbour
            val showAdvInfo = beacon.visited || sector.mapRevealed || (isNeighbour && hasLRS)

            // Draw the nebula cloud puffs under nebulas (GitHub issue #74):
            // vanilla envelopes each nebula beacon in one of these soft
            // clouds so the sector's nebula areas stand out on the map —
            // AND it keeps the little blue glow oval underneath (the user
            // spotted the missing indicator against vanilla; the clouds
            // alone read wrong). Blue first, cloud on top.
            if (beacon.isMapNebula) {
                g.colour = Constants.BEACON_NEBULA_CIRCLE
                g.fillOval(centrePos.x - 9f, centrePos.y - 9f, 20f, 20f)
                // Sectors from older saves have no recorded clouds.
                if (sector.nebulaClouds.isEmpty()) {
                    val puff = nebulaPuffs[(beacon.pos.x * 31 + beacon.pos.y * 17).mod(nebulaPuffs.size)]
                    puff.draw(
                        centrePos.x - puff.width / 2f, centrePos.y - puff.height / 2f
                    )
                }
            }

            if (showAdvInfo && beacon.environmentType.isDangerous) {
                beaconEnvironmentHazard.draw(pos.x - 5, pos.y - 5)
            }

            // Draw the flashing background if this beacon
            // will be overtaken after this jump.
            val willBeOvertaken = willBeOvertaken(beacon, nextFleetPos, tutorial)
            if (willBeOvertaken && beacon.state != Beacon.State.OVERTAKEN) {
                val baseTimer = (System.nanoTime() - flashTimerBase) / 1000000000f
                val flashTimer = (baseTimer + beacon.overtakeFlashAnimationOffset).rem(2)

                val opacity = if (flashTimer < 1f) flashTimer else 2f - flashTimer

                beaconWillOvertakeCircle.draw(
                    pos.x + 1f, pos.y + 2f, pos.x + 31f, pos.y + 32f,
                    0f, 0f, 40f, 40f,
                    opacity, Colour.white
                )
            }

            beaconShadow.draw(pos)

            val beaconImg = when (beacon.state) {
                Beacon.State.UNVISITED -> when {
                    showAdvInfo && beacon.event.loadShipName != null -> beaconShipPresent
                    else -> beaconYellow
                }

                Beacon.State.VISITED_CLEAR -> beaconBlue
                Beacon.State.VISITED_DANGER -> beaconDanger
                Beacon.State.OVERTAKEN -> beaconOvertaken
            }
            beaconImg.draw(pos)

            if (beacon.event.isDistressBeacon && !beacon.visited && showBasicInfo && !willBeOvertaken)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_distress"])

            if (beacon.event.isLastStandRepair && !beacon.visited && !willBeOvertaken)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_repair"])

            // Note that quests and stores are cleared when the beacon is overrun,
            // so we don't have to check if willBeOvertaken is set.
            if (beacon.hasQuest && !beacon.visited)
                drawBeaconLabel(
                    labelPurple,
                    Constants.SECTOR_CUTOUT_TEXT_PURPLE,
                    pos,
                    game.translator["map_icon_quest"]
                )

            if (beacon.hasStore && showBasicInfo)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_store"])

            if (beacon.isExit)
                drawBeaconLabel(labelGreen, Constants.SECTOR_CUTOUT_TEXT_GREEN, pos, game.translator["map_icon_exit"])

            if (beacon.isBase)
                drawBeaconLabel(labelPurple, Constants.SECTOR_CUTOUT_TEXT_PURPLE, pos, game.translator["map_icon_base"])

            if (beacon == hovered && canJumpTo(hovered)) {
                drawTargetBox(g, pos)
            }

            // Draw the player ship rotating around the beacon.
            val rotationSpeed = TWO_PI / 20f // Go around every 20 seconds
            if (beacon == game.currentBeacon) {
                val icon = when (fuelDistressFlashOn) {
                    true -> playerShipNoFuel
                    false -> playerShip
                }

                // The player's ship doesn't rotate when they're out of fuel
                if (!outOfFuel) {
                    playerRotation += game.renderingDeltaTime * rotationSpeed
                }

                // These offsets are approximate
                g.pushTransform()
                g.rotate(centrePos.x.f, centrePos.y.f, -playerRotation)
                icon.draw(centrePos.x - 8, centrePos.y - 32)
                g.popTransform()
            }

            // Draw the flagship rotating around the beacon.
            for (boss in sector.bosses) {
                if (boss.beacon != beacon || boss.jumping)
                    continue

                boss.drawMapIcon(g, centrePos)
            }
        }

        if (hovered != null) {
            if (hovered !== tooltipBeacon) {
                tooltipBeacon = hovered
                val text = beaconTooltipText(hovered, nextFleetPos, tutorial, hasLRS)
                beaconTooltip = object : DelayedTooltip(game) {
                    override fun getText(): String = text
                }
            }
            g.tooltip = beaconTooltip
        } else {
            tooltipBeacon = null
        }

        // TODO draw the fleet advance after the beacons and the circling ship,
        //  but before the labels (which we currently draw at the same time
        //  as the beacons).
        if (!tutorial && !sector.isLastStand) {
            drawDangerZone(g)
        }

        // For debugging, this can draw the grid the sectors fit in
        if (game.debugFlags.showMapGrid.set) {
            for (x in 0 until Sector.GRID_SIZE.x) {
                for (y in 0 until Sector.GRID_SIZE.y) {
                    g.colour = Colour.red
                    g.drawRect(mapBase.x + x * 110f, mapBase.y + y * 110f, 110f, 110f)
                }
            }
        }

        // If we're out of fuel, tint the map black and draw the text on top
        if (outOfFuel) {
            g.colour = Constants.JUMP_NO_FUEL_TINT
            g.fillRect(position.x, position.y, size.x, size.y)

            // 0x005eb4a0: both images at the largest scale (3, 2 or 1) that keeps them within 750 px; NO FUEL
            // centred on (375, 263), Waiting... on (385, 263) less 6 px of its width.
            val widest = max(noFuelImage.width, waitingImage.width)
            val scale = when {
                widest * 3 <= 750 -> 3
                widest * 2 <= 750 -> 2
                else -> 1
            }
            if (isWaiting) {
                waitingImage.draw(
                    position.x + 385f - scale * (waitingImage.width - 12) / 2, position.y + 263f - scale * waitingImage.height / 2,
                    scale * waitingImage.width.f, scale * waitingImage.height.f
                )
            } else {
                noFuelImage.draw(
                    position.x + 375f - scale * noFuelImage.width / 2, position.y + 263f - scale * noFuelImage.height / 2,
                    scale * noFuelImage.width.f, scale * noFuelImage.height.f
                )
            }
        }
    }

    private fun willBeOvertaken(beacon: Beacon, nextFleetPos: IPoint, tutorial: Boolean): Boolean {
        return !tutorial && (if (sector.isLastStand) beacon.browserFleetThreatened else beacon.pos.distToSq(nextFleetPos) < Sector.DANGER_ZONE_RADIUS_SQUARED)
    }

    /**
     * StarMap's location text (0x005ee360), shown as the mouse tooltip over a
     * beacon: one description picked in the original's order, then a nebula
     * line and, on a revealed map or a known beacon with Long-Range Scanners,
     * the hazard line, each after " \n".
     */
    private fun beaconTooltipText(beacon: Beacon, nextFleetPos: IPoint, tutorial: Boolean, hasLRS: Boolean): String {
        val tr = game.translator
        val lastStand = sector.isLastStand
        // Location+0x15 (known: visited or next to a visited beacon), or the whole map revealed.
        val nearby = beacon.visited || neighbourVisSet.contains(beacon)
        val known = nearby || sector.mapRevealed
        val scanned = sector.mapRevealed || (nearby && hasLRS)
        val bossBeacon = sector.bosses.any { it.beacon == beacon }
        val event = beacon.event
        val shipPresent = if (beacon.visited) beacon.ship != null else event.loadShipName != null
        val repair = event.isLastStandRepair && !beacon.eventCleared

        val description = when {
            beacon == game.currentBeacon -> "map_current_loc"
            bossBeacon && lastStand -> "map_boss_loc"
            beacon.isExit || beacon.isBase -> if (lastStand) "map_base_loc" else "map_exit_loc"
            willBeOvertaken(beacon, nextFleetPos, tutorial) && !beacon.isOvertaken -> "map_rebels_loc"
            beacon.visited && beacon.hasStore && !(bossBeacon && lastStand) -> "map_store_loc"
            known && repair -> "map_repair_loc"
            beacon.isOvertaken -> "map_fleet_loc"
            beacon.visited -> if (shipPresent) "map_hostile_loc" else "map_nothing_loc"
            event.isDistressBeacon && known -> "map_distress_loc"
            repair -> "map_repair_loc"
            shipPresent && scanned -> "map_ship_loc"
            beacon.hasQuest -> "map_quest_loc"
            beacon.hasStore && known -> "map_merchant_loc"
            else -> "map_unvisited_loc"
        }
        val sb = StringBuilder(tr[description])

        if (beacon.isMapNebula) {
            val fleetReady = sector.info.sectorClass == xyz.znix.xftl.sector.GameMap.SectorClass.NEBULA
            sb.append(" \n").append(tr[if (fleetReady) "map_nebula_fleet_loc" else "map_nebula_loc"])
        }

        if (scanned) {
            val hazard = when (beacon.environmentType) {
                Beacon.EnvironmentType.ASTEROID -> "map_asteroid_loc"
                Beacon.EnvironmentType.SUN -> "map_sun_loc"
                Beacon.EnvironmentType.ION_STORM -> "map_ion_loc"
                Beacon.EnvironmentType.PULSAR -> "map_pulsar_loc"
                Beacon.EnvironmentType.PDS -> when {
                    beacon.isBase -> null
                    beacon.isOvertaken -> "map_pds_fleet"
                    else -> "map_pds_loc"
                }

                else -> null
            }
            if (hazard != null)
                sb.append(" \n").append(tr[hazard])
        }
        return sb.toString()
    }

    // StarMap's FLAGSHIP JUMPING (0x005f1d20): at the map's spot plus (375, -72), with WARNING!, flashing, while
    // the flagship jumps this turn in the last stand (OnRender 0x005fdbf0).
    private val bossJumpWarning = WarningFlasher.atNative(
        game, 0, 0, "warning_boss_jump", true, GlowColour.RED, flash = true
    )

    private var tooltipBeacon: Beacon? = null
    private var beaconTooltip: DelayedTooltip? = null

    private fun drawDangerZone(g: Graphics) {
        val dangerZoneRHS = sector.dangerZoneCentre.x + Sector.DANGER_ZONE_RADIUS
        val nextDangerZoneRHS = dangerZoneRHS + sector.getFleetAdvanceFor(game.currentBeacon)
        fleetAdvanceImg.draw(
            mapBase.x + nextDangerZoneRHS - 181f,
            mapBase.y + sector.dangerZoneCentre.y - 498f
        )
        g.colour = fleetAdvanceColour
        g.fillRect(
            position.x.f,
            position.y.f,
            (mapBase.x + nextDangerZoneRHS - 181f) - position.x,
            size.y.f
        )

        var fleetControlX = mapBase.x + dangerZoneRHS - 181f
        val fleetControlY = mapBase.y + sector.dangerZoneCentre.y - 498f
        fleetControlImg.draw(fleetControlX, fleetControlY)

        // Draw a bunch of tiles filling in the area controlled by
        // the fleet but not covered by the curved front image.
        while (fleetControlX >= mapBase.x) {
            fleetControlX -= fleetControlTile.width

            var tileY = fleetControlY
            while (tileY < position.y + size.y) {
                tileY += fleetControlTile.height

                fleetControlTile.draw(fleetControlX, tileY)
            }
        }
    }

    private fun drawLastStandDangerZone(g: Graphics) {
        // The last stand is entirely covered in stripes.
        g.colour = fleetAdvanceColour
        g.fillRect(position.x.f, position.y.f, size.x.f, size.y.f)

        for (tileX in position.x..position.x + size.x step fleetControlTile.width) {
            for (tileY in position.y..position.y + size.y step fleetControlTile.height) {
                fleetControlTile.draw(tileX, tileY)
            }
        }
    }

    override fun draw(g: Graphics) {
        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            // Draw the stencil
            g.colour = Colour.red // Any non-transparent colour will work

            // The window edge width means the inside of the window has a four pixel boundary
            // This does leave a tiny area in the bevelled right-hand corner unstenciled,
            // but it seems unlikely anything will actually draw there.
            // FIXME this is visible - the background clips out
            g.fillRect(position.x + 4, position.y + 4, size.x - 8, size.y - 8)
        }, {
            // Draw the contents of the map
            drawMapContent(g)
        })

        // Draw the top-left map label tab
        val tab = game.translator["map_title"]
        // StarMap's title tab (0x005eb0f0): the art's first 27 columns from (-7,-7), its
        // column 27 stretched to font 63's measured width less 12, then the last 44
        // columns. The frame outline is stencilled out of the tab's whole rectangle.
        val measure = buttonFont.measure(tab)
        val tabX = position.x.f
        val tabY = position.y.f - GLOW
        val tabH = titleTab.height.f
        titleTab.draw(tabX - GLOW, tabY, tabX + 20f, tabY + tabH, 0f, 0f, 27f, tabH)
        titleTab.draw(tabX + 20f, tabY, tabX + measure + 8f, tabY + tabH, 27f, 0f, 28f, tabH)
        titleTab.draw(tabX + measure + 8f, tabY, tabX + measure + 52f, tabY + tabH, 28f, 0f, titleTab.width.f, tabH)
        val tabWidth = measure + titleTab.width - 13
        buttonFont.print(position.x + 14f, position.y + 1f, tab, Constants.JUMP_DISABLED_TEXT)

        // Draw the rest of the top, going clockwise to the right side
        drawSide(Direction.UP, tabWidth.toInt())
        drawCorner(Direction.UP_RIGHT)
        drawSide(Direction.RIGHT)

        // Draw the bottom line connecting
        // Note these have to be drawn before the cancel button - see below
        drawSide(Direction.DOWN, sectorInfoTab.width)
        drawCorner(Direction.RIGHT_DOWN)

        // Cancel button
        // This is done in a slightly weird way - the bottom line is drawn exactly how it usually is, but
        // the cancel button is drawn just below it, cutting off part of its glow. The glow at the edges
        // of the cancel button image are modified specially to fit the glow of the line, so it looks
        // seamless. Note that this must be done after the lines are drawn, otherwise their glow would overlap
        // the cancel button frame.
        // Note we also have to stretch it to fit around the localised cancel button.
        val cancelY = position.y + size.y - 1
        val cancelH = cancelButtonOutline.height
        cancelButtonOutline.drawSection(cancelButton.pos.x - 24, cancelY, 34, cancelH)
        cancelButtonOutline.drawSection(cancelButton.pos.x + 10, cancelY, 20, cancelH, 34, 0, cancelButton.size.x - 20)
        cancelButtonOutline.drawSection(cancelButton.pos.x + cancelButton.size.x - 10, cancelY, 35, cancelH, 138)
        cancelButton.draw(g)

        // Draw the 'next sector' button frame, which has a similar glow trick to the cancel button
        if (game.currentBeacon.isExit && !outOfFuel) {
            val nsY = position.y + 4
            val nsH = nextSectorTab.height
            nextSectorTab.drawSection(nextSectorButton.pos.x - 19, nsY, 29, nsH)
            nextSectorTab.drawSection(nextSectorButton.pos.x + 10, nsY, 20, nsH, 24, 0, nextSectorButton.size.x - 20)
            nextSectorTab.drawSection(nextSectorButton.pos.x + nextSectorButton.size.x - 10, nsY, 18, nsH, 235)
            nextSectorButton.draw(g)
        }

        // Draw the sector info
        sectorInfoTab.draw(position.x - GLOW, position.y + size.y - 20)

        // Cutouts for text
        fun drawCutout(x: Int, y: Int, width: Int, text: String) {
            g.colour = Constants.SECTOR_CUTOUT
            g.fillRect(x + 3f, y.f, width - 6f, 2f)
            g.fillRect(x + 2f, y + 1f, width - 4f, 2f)
            g.fillRect(x + 1f, y + 2f, width - 2f, 2f)
            g.fillRect(x.f, y + 3f, width.f, 20f)
            g.fillRect(x + 1f, y + 22f + 0f, width - 2f, 2f)
            g.fillRect(x + 2f, y + 22f + 1f, width - 4f, 2f)
            g.fillRect(x + 3f, y + 22f + 2f, width - 6f, 2f)

            // Centred on the cutout's middle with freetype::easy_printCenter, top 529.
            cutoutFont.printCenter((x + width / 2).f, y - 4f, text, Constants.SECTOR_CUTOUT_TEXT)
        }

        // StarMap::OnRender (0x005fdbf0): the label in font 63 at (6,531), the number
        // cutout 18px after its measured width, then the name cutout out to x=459.
        val sectorText = game.translator["map_sector"]
        buttonFont.print(position.x + 6f, position.y + 531f, sectorText, Constants.JUMP_DISABLED_TEXT)

        val sectorName = game.translator[sector.type.nameTextId]
        val numberX = buttonFont.measure(sectorText) + 18
        val nameX = numberX + 49
        val nameWidth = maxOf(459 - nameX, sectorInfoFont.getWidth(sectorName) + 8)
        drawCutout(position.x + numberX, position.y + size.y - 1, 38, (sector.sectorNumber + 1).toString())
        drawCutout(position.x + nameX, position.y + size.y - 1, nameWidth, sectorName)

        // The top and bottom tabs are slightly different sizes, this compensates for them
        drawSide(Direction.LEFT, 45, size.y + GLOW * 2 - 27)

        browserWaitButton?.let { button ->
            button.draw(g)
            sectorInfoFont.drawString(button.pos.x + button.size.x + 12f,
                button.pos.y + 25f, game.translator["final_sector_wait"], Colour.white)
        }
        val baseTurns = sector.bosses.filterIsInstance<FlagshipBoss>().mapNotNull { it.browserBaseTurns }.minOrNull()
        if (baseTurns != null) sectorInfoFont.drawString(position.x + 18f, position.y + 42f,
            "${game.translator["boss_jumps"]}: $baseTurns", Colour.red)

        bossJumpWarning.loopWhile(sector.isLastStand && sector.bosses.any { it.jumping })
        bossJumpWarning.placeNative(position.x + 375, position.y - 72)
        bossJumpWarning.draw(g)

        // Draw the out-of-fuel UI
        if (outOfFuel) {
            drawNoFuelUI(null)

            for (button in noFuelButtons) {
                button.draw(g)
            }
        }
    }

    // It's a bit horrible, but this function can both draw and create buttons, since
    // most of the work is calculating the positions of everything.
    private fun drawNoFuelUI(buttons: MutableList<Button>?) {
        val rightX = position.x + size.x
        val frameY = position.y + size.y - 57

        // Calculate all the text-based positions
        val offText = game.translator["button_distress_off"]
        val onText = game.translator["button_distress_on"]
        val toggleTextWidth = max(font.getWidth(offText), font.getWidth(onText))
        val toggleButtonWidth = toggleTextWidth + 4 * 2
        val toggleX = rightX - 12 - toggleButtonWidth

        val distressText = game.translator["map_distress_beacon"]
        val distressLabelWidth = distressBeaconFont.getWidth(distressText)
        val labelAreaWidth = 7 + distressLabelWidth + 8
        val labelAreaX = toggleX - 7 - labelAreaWidth

        val waitText = game.translator["button_wait"]
        val waitButtonWidth = font.getWidth(waitText) + 4 * 2
        val waitButtonX = labelAreaX - 5 - 4 - waitButtonWidth

        val leftX = waitButtonX - 4 - 5

        if (buttons == null) {
            // Draw the background box
            val imgY = frameY - 10
            val imgH = waitDistressFrame.height
            waitDistressFrame.drawSection(leftX - 10, imgY, 22, imgH)
            waitDistressFrame.drawSection(waitButtonX + 3, imgY, 1, imgH, 22, 0, waitButtonWidth - 6)
            waitDistressFrame.drawSection(labelAreaX - 12, imgY, 22, imgH, 27)
            waitDistressFrame.drawSection(labelAreaX + 10, imgY, 1, imgH, 49, 0, labelAreaWidth - 10 - 5)
            waitDistressFrame.drawSection(toggleX - 7 - 5, imgY, 12, imgH, 48)
            waitDistressFrame.drawSection(toggleX, imgY, 1, imgH, 60, 0, toggleButtonWidth - 3)
            waitDistressFrame.drawSection(rightX - 15, imgY, 15, imgH, 62)

            // Draw the beacon toggle button's label text
            // Note the Y value comes from the DE localisation.
            distressBeaconFont.drawStringCentredVertical(
                labelAreaX, frameY + 33,
                labelAreaWidth,
                18,
                distressText,
                Constants.SECTOR_CUTOUT_TEXT
            )

            if (fuelDistressFlashOn) {
                fuelDistressFlashLight.draw(rightX - 2 - 33, frameY + 45 - 23)
            }

            if (fuelDistressOn) {
                fuelDistressFlashTimer = (fuelDistressFlashTimer + game.renderingDeltaTime).mod(1f)
            } else {
                // Reset, so it immediately lights up when turned back on
                fuelDistressFlashTimer = 0f
            }
        } else {
            // Wait button
            buttons += Buttons.BasicButton(
                game,
                ConstPoint(waitButtonX, frameY + 9), ConstPoint(waitButtonWidth, 36),
                waitText, 4, font, 27
            ) {
                waitPressed()
            }

            // Toggle distress beacon
            buttons += object : Button(game, ConstPoint(toggleX, frameY + 9), ConstPoint(toggleButtonWidth, 36)) {
                override fun draw(g: Graphics) {
                    g.colour = when {
                        disabled -> Constants.JUMP_DISABLED
                        hovered -> Constants.UI_BUTTON_HOVER
                        else -> Constants.SECTOR_CUTOUT_TEXT
                    }
                    Buttons.drawRounded(g, pos.x, pos.y, size.x, size.y, 4)

                    // Draw the background, which is rounded on the right only
                    // Draw a bunch of overlapping rectangles, making a diagonal corner.
                    for (i in 0..4) {
                        g.fillRect(pos.x, pos.y + 4 - i, size.x - i, size.y - (4 - i) * 2)
                    }

                    val label = when (fuelDistressOn) {
                        true -> onText
                        false -> offText
                    }

                    font.drawStringCentred(pos.x.f, pos.y + 27f, size.x.f, label, Constants.JUMP_DISABLED_TEXT)
                }

                override fun click(button: Int) {
                    fuelDistressOn = !fuelDistressOn
                }
            }
        }
    }

    override fun updateUI(x: Int, y: Int) {
        // StarMap::MouseMove/MouseClick (0x005fb210, 0x005fa930) do nothing while waiting.
        if (isWaiting) {
            for (button in buttons) button.update(-1, -1)
            hovered = null
            waitingTimer -= game.renderingDeltaTime
            if (waitingTimer <= 0f) {
                waitingTimer = 0f
                waitOutOfFuel()
            }
            return
        }

        super.updateUI(x, y)

        val p = scaleWindowPoint(x, y)

        hovered = null

        // Can't hover over beacons while out of fuel
        if (outOfFuel) {
            return
        }

        val closest = sector.beacons.map {
            val bp = it.pos + mapBase
            val dist = bp.distToSq(ConstPoint(p.x, p.y))
            Pair(it, dist)
        }.minByOrNull { it.second } ?: return

        val hoverDist = 12
        if (closest.second > hoverDist * hoverDist)
            return

        hovered = closest.first
    }

    override fun clickTargetAt(x: Int, y: Int): Any? {
        super.clickTargetAt(x, y)?.let { return it }

        // Beacons aren't buttons - match the updateUI proximity hit-test
        // (hovered is already computed from the cursor position, which
        // sits at the tap point).
        return hovered
    }

    /**
     * True if the player has the Adv. FTL Navigation augment, which lets
     * them jump to any beacon they've previously visited in this sector.
     */
    private val hasAdvFtlNavigation: Boolean
        get() = game.player.hasAugment(AugmentBlueprint.ADVANCED_FTL_NAVIGATION)

    /**
     * Work out whether the player can jump to the given beacon right now.
     *
     * Ordinarily the only valid destinations are the beacons neighbouring
     * the player's current one, but the Adv. FTL Navigation augment also
     * permits jumping to any previously visited beacon in the sector
     * (including ones the Rebel Fleet has since overtaken). The 'aj'
     * (anyjump) debug flag takes precedence over all of that, letting the
     * player jump to any beacon on the map - convenient for testing.
     */
    private fun canJumpTo(beacon: Beacon): Boolean {
        if (game.debugFlags.anyJump.set)
            return true

        if (beacon == game.currentBeacon)
            return false

        // StarMap 0x5f5680 offers no jump target while out of fuel (StarMap +0x6ac).
        if (outOfFuel)
            return false

        if (game.currentBeacon.neighbours.contains(beacon))
            return true

        return beacon.visited && hasAdvFtlNavigation
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (isWaiting)
            return
        if (!tapArmGate(button, x, y))
            return

        super.mouseClick(button, x, y)

        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        val hovered = hovered ?: return

        if (!canJumpTo(hovered))
            return

        jump(hovered)

        // Advance the fleet pursuit *before* changing the beacon, since
        // if we're in a nebula that slows down the fleet pursuit. The scene of the beacon being left stays as
        // it is through the jump-out, even when this jump hands the beacon to the fleet (OvertakeLocation).
        val departing = game.currentBeacon
        val scene = departing.getEnvironment(game)
        game.advanceFleet()
        departing.restoreEnvironment(scene)

        // ShipManager::JumpLeave takes the fuel and starts the jump-out; the beacon switch - and the
        // arrival animation at the new beacon - happens when it ends.
        game.beginJumpOut(hovered)
    }

    /** WAIT: StarMap starts its wait tracker; the wait itself follows when it ends (see [updateUI]). */
    internal fun waitPressed() {
        if (!isWaiting)
            waitingTimer = WAIT_TIME
    }

    internal fun waitOutOfFuel() {
        // Close the window and advance the fleet
        jump(game.currentBeacon)
        game.advanceFleet()
        // CommandGui::OnLoop's wait branch calls ShipManager::Wait (0x49d300): the partial jump reset.
        game.player.browserWaitOutOfFuel()
        if (ftl.browser.BrowserLastStand.checkDefeat(game, game.currentBeacon)) return
        if (sector.isLastStand && game.browserSpawnBossHere()) {
            game.mainGame.writeRunSave(); return
        }

        // StarMap's wait (0x5f4510): waiting in a hazard first drifts out of
        // it (FUEL_ESCAPE_*); a storm or ASB the fleet has taken over goes
        // straight to the fleet event.
        val beacon = game.currentBeacon
        val overtaken = beacon.isOvertaken
        val environment = beacon.environmentType
        val escapeName: String? = when (environment) {
            Beacon.EnvironmentType.ASTEROID -> "FUEL_ESCAPE_ASTEROIDS"
            Beacon.EnvironmentType.SUN -> "FUEL_ESCAPE_SUN"
            Beacon.EnvironmentType.PULSAR -> "FUEL_ESCAPE_PULSAR"
            Beacon.EnvironmentType.ION_STORM -> if (overtaken) null else "FUEL_ESCAPE_STORM"
            Beacon.EnvironmentType.PDS -> if (overtaken) null else "FUEL_ESCAPE_PDS"
            else -> null
        }

        // Pick a suitable event
        val eventName: String = when {
            // Rebels have overtaken this beacon
            overtaken -> when (game.content.enableAdvancedEdition) {
                true -> "NO_FUEL_FLEET_DLC"
                false -> "NO_FUEL_FLEET"
            }

            fuelDistressOn -> "NO_FUEL_DISTRESS"
            else -> "NO_FUEL"
        }
        // The wait draws its own seed from the global stream and everything
        // it creates comes from that seed (StarMap 0x5f4510), which the
        // sector keeps for the saves.
        val waitSeed = ftl.browser.NativeRandom.next31()
        sector.waitSeed = waitSeed
        ftl.browser.NativeRandom.seed(waitSeed)
        val event = game.eventCreator.create(game.eventManager[eventName], sector.sectorNumber)!!

        // The wait event replaces the beacon's event, so the hazard is gone;
        // a nebula or storm beacon stays a (plain) nebula.
        beacon.createdEvent = event
        beacon.forcedNebula = !overtaken && environment.isNebula

        game.shipUI.showEventDialogue(event)
        if (escapeName != null) {
            // FUEL_ESCAPE_PULSAR and _PDS live in dlcEvents.xml, loaded only with AE.
            val escapeText = game.eventManager.getOrNull(escapeName)?.resolve()?.text?.resolve(ftl.browser.BrowserRandom)
            if (!escapeText.isNullOrEmpty())
                game.shipUI.showSyntheticDialogue(DialogueWindow.SyntheticEvent(escapeText))
        }

        // Clear the environment, so if the beacon was overtaken it now
        // has the enemy background etc there.
        beacon.clearEnvironment()
        game.mainGame.writeRunSave()
    }

    override fun escapePressed() {
        // StarMap::Close (0x005ea780) does nothing while waiting.
        if (isWaiting)
            return
        cancelClicked()
    }

    private fun drawTargetBox(g: Graphics, pos: IPoint) {
        drawTargetMarkers(g, targetBox, pos.x + targetBox.width / 2, pos.y + targetBox.height / 2)
    }

    private fun drawBeaconLinesTo(g: Graphics, beacon: Beacon, colour: Colour, predicate: (Beacon) -> Boolean) {
        for (neighbour in beacon.neighbours) {
            if (!predicate(neighbour))
                continue

            drawBeaconLine(g, beacon, neighbour, colour)
        }
    }

    private fun drawBeaconLine(g: Graphics, from: Beacon, to: Beacon, colour: Colour) {
        g.pushTransform()

        val fromPos = from.pos + mapBase + beaconOffset
        val toPos = to.pos + mapBase + beaconOffset

        // Find the delta vector between the two points we're drawing between, and the length of said vector
        val delta = toPos - fromPos
        val dist = delta.distTo(ConstPoint.ZERO)
        val angle = atan2(delta.y.f, delta.x.f) * 180 / PIf

        // Translate to the start position, and rotate so that positive x runs along our line.
        // The beacons are drawn at their image origins, and they're 32px² - so +16 for x,y.
        g.translate(fromPos.x + 16f, fromPos.y + 16f)
        g.rotate(0f, 1.5f, angle)

        val segmentWidth = 10

        // Step along the path of the vector, drawing images in each place
        for (i in 6..(dist - 5) step segmentWidth) {
            // Draw the line itself
            lineImg.drawSection(i, 0, segmentWidth, 4, 1, 0, colour = colour)
        }

        g.popTransform()
    }

    private fun drawBeaconLabel(images: List<Image>, colour: Colour, pos: IPoint, text: String) {
        // StarMap's label pass (0x005ef050) is skipped while out of fuel.
        if (outOfFuel)
            return
        // AFAIK the labels in FTL are drawn with alpha=204, TODO use that

        val strWidth = beaconLabelFont.getWidth(text)

        val boxTop = pos.y - 11
        val boxTopWithGlow = boxTop - 6

        images[0].draw(pos.x + 15, boxTopWithGlow)
        images[1].draw(pos.x + 34f, boxTopWithGlow.f, strWidth - 8f, 32f)
        images[2].draw(pos.x + 26 + strWidth, boxTopWithGlow)

        beaconLabelFont.drawString(pos.x + 30f, boxTop + 10f, text, colour)
    }

    private fun drawCorner(edge: Direction) {
        val x = position.x - GLOW + edge.x.coerceAtLeast(0) * (size.x + GLOW * 2 - 33)
        val y = position.y - GLOW + edge.y.coerceAtLeast(0) * (size.y + GLOW * 2 - 36)
        val tx = edge.x.coerceAtLeast(0) * outlineImage.width / 2
        val ty = edge.y.coerceAtLeast(0) * outlineImage.height / 2

        outlineImage.drawSection(x, y, 33, 36, tx, ty)
    }

    private fun drawSide(edge: Direction, start: Int? = null, stop: Int? = null) {
        val paddedSizeX = size.x + GLOW * 2
        val paddedSizeY = size.y + GLOW * 2

        val xb = position.x - GLOW + edge.x.coerceAtLeast(0) * (paddedSizeX - 33)
        val yb = position.y - GLOW + edge.y.coerceAtLeast(0) * (paddedSizeY - 36)

        val texPos = when (edge) {
            Direction.UP -> ConstPoint(33, 0)
            Direction.LEFT -> ConstPoint(0, 36)
            Direction.DOWN, Direction.RIGHT -> ConstPoint(33, 36)
            else -> error("Invalid side edge $edge")
        }

        val texSize = when (edge) {
            Direction.UP, Direction.DOWN -> ConstPoint(1, 36)
            Direction.LEFT, Direction.RIGHT -> ConstPoint(33, 1)
            else -> error("Invalid side edge $edge")
        }

        val x: Int
        val y: Int

        val drawSize: ConstPoint
        when (edge) {
            Direction.UP, Direction.DOWN -> {
                x = xb + (start ?: 33)
                y = yb
                drawSize = ConstPoint((stop ?: (paddedSizeX - 33)) - (start ?: 33), 36)
            }

            Direction.LEFT, Direction.RIGHT -> {
                x = xb
                y = yb + (start ?: 36)
                drawSize = ConstPoint(33, (stop ?: (paddedSizeY - 36)) - (start ?: 36))
            }

            else -> error("Invalid edge $edge")
        }

        outlineImage.draw(
            x.f,
            y.f,
            x.f + drawSize.x,
            y.f + drawSize.y,
            texPos.x.f,
            texPos.y.f,
            texPos.x.f + texSize.x,
            texPos.y.f + texSize.y
        )
    }

    private fun cancelClicked() {
        jump(null)
    }

    companion object {
        // The width of the glow around the edge of the window
        private const val GLOW = 7

        // StarMap's wait tracker (0x6b0) runs 2 s.
        private const val WAIT_TIME = 2f

        /**
         * This draws the little rounded corners that move in and out, which is used
         * both on the beacon and sector map.
         */
        fun drawTargetMarkers(g: Graphics, targetBox: Image, centreX: Int, centreY: Int) {
            val period = 1_000_000_000
            val timePoint = (System.nanoTime() % period) / period.toFloat()
            val distFactor = (1 + sin(timePoint * (Math.PI * 2))) / 2
            val spacing = (distFactor * 4).roundToInt()

            g.pushTransform()
            g.translate(centreX.f, centreY.f)

            val boxX = -spacing - targetBox.width / 2
            val boxY = -spacing - targetBox.height / 2

            targetBox.draw(boxX, boxY)
            g.rotate(0f, 0f, 90f)
            targetBox.draw(boxX, boxY)
            g.rotate(0f, 0f, 90f)
            targetBox.draw(boxX, boxY)
            g.rotate(0f, 0f, 90f)
            targetBox.draw(boxX, boxY)

            g.popTransform()
        }
    }
}
