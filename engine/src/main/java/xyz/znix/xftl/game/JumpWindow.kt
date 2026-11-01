package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
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
    // The touch layout centres the map in the HUD block, at y 100 of its 768.
    override val windowCentreOffset: IPoint =
        if (TouchMode.enabled) ConstPoint(0, -17)
        else if (PlatformSpecific.INSTANCE.isTouchUi) ConstPoint(0, -75) else ConstPoint(76, -10)

    // Tap-to-arm: first tap on a beacon/button highlights it, second tap
    // confirms (see Window.tapToArm). Runtime-gated on isTouchUi.
    override val tapToArm = true

    private val sectorInfoTab = game.getImg("img/map/side_sector.png")
    private val titleTab = game.getImg("img/map/side_beaconmap.png")
    private val nextSectorTab = game.getImg("img/map/side_nextsector.png")
    private val font = game.getFont("HL2", 3f)
    private val buttonFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)
    private val cutoutFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 14)
    private val hsCostFont by lazy { xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 0) }
    private val cancelButtonOutline = game.getImg("img/main_menus/button_cancel_base.png")
    private val sectorInfoFont = game.getFont("c&cnew", 2f)
    private val beaconLabelFont = game.getFont("HL1")
    private val distressBeaconFont = game.getFont("HL1", 2f) // Out-of-fuel UI

    private val outlineImage = game.getImg("img/window_outline.png")

    /** The touch layout's larger map art, where the data set has it. */
    private fun mapImg(name: String): Image =
        (if (TouchMode.enabled) game.getImgIfExists("img/ipad/map/$name") else null) ?: game.getImg("img/map/$name")

    private val lineImg = mapImg("dotted_line.png")
    private val lineHeight = if (TouchMode.enabled) lineImg.height else 4
    private val targetBox = game.getImg("img/map/map_targetbox.png")

    private val fleetAdvanceImg = game.getImg("img/map/map_warningcircle_point.png")
    private val fleetAdvanceColour = Colour(246, 128, 125, 21)
    private val fleetControlImg = game.getImg("img/map/map_warningcircle.png")
    private val fleetControlTile = game.getImg("img/map/map_warningcircle_tile.png")

    private val beaconShadow = game.getImg("img/map/map_icon_diamond_shadow.png")
    private val beaconEnvironmentHazard = mapImg("map_icon_hazard.png")

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
    private val beaconYellow = mapImg("map_icon_diamond_yellow.png")
    private val beaconBlue = mapImg("map_icon_diamond_blue.png")
    private val beaconDanger = mapImg("map_icon_triangle_red.png")
    private val beaconShipPresent = mapImg("map_icon_triangle_yellow.png")
    private val beaconWillOvertakeCircle = game.getImg("img/tutorial/player_circle.png") // This is the right image!
    private val beaconOvertaken = mapImg("map_icon_warning.png")

    private val playerShip = mapImg("map_icon_ship.png")
    private val playerShipNoFuel = mapImg("map_icon_ship_fuel.png")

    private val noFuelImage = game.getImg("img/map/map_fuel_text_nofuel.png")
    private val waitingImage = game.getImg("img/map/map_fuel_text_waiting.png")

    /**
     * StarMap's 2 s wait tracker (0x6b0), started by WAIT: "Waiting..." replaces NO FUEL and the map takes no
     * input until it ends, when the wait happens.
     */
    private var waitingTimer = 0f
    internal val isWaiting: Boolean get() = waitingTimer > 0f

    /** StarMap::dangerWaitStart: the danger zone's x when WAIT was pressed; the zone moves on from it while waiting. */
    private var waitStartX = 0
    private var waitSerial = 0
    private val waitDistressFrame = game.getImg("img/map/side_wait_distress.png")
    private val fuelDistressFlashLight = game.getImg("img/map/side_distressbeacon_redlight.png")

    // The labels, lines and target box are placed from the desktop icon's corner, whichever art is drawn.
    private val beaconOffset = game.getImg("img/map/map_icon_diamond_yellow.png").let { Point(-it.width / 2, -it.height / 2) }

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

    /** Hyperspace beacon types: the coloured label box (RenderLabels' boxCustom), loaded only in HS sessions. */
    private val labelCustom by lazy { (1..3).map { game.getImg("img/map/map_box_custom_$it.png") } }

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
        set(v) { field = v; if (!isWaiting && !outOfFuel) xyz.znix.xftl.hs.sector.HsStarMap.hoverLoc = v } // StarMap::hoverLoc outlives the map

    private var playerRotation: Float = (0f..10f).random(VisualRandom)

    // Touch layout: a finger picks the beacon nearest to it on every press and move. [hovered] then keeps the picked
    // beacon (its target box, tooltip and line); the target is set only when the ship can go there, and only the Jump
    // button jumps.
    private var touchFingerDown = false
    private var touchTarget: Beacon? = null
    private var touchJumpButton: Button? = null

    // Hyperspace StarMap::OnLoop rewrite (CustomMap.cpp): a script's ForceWaitMessage holds the wait controls too.
    private val outOfFuel: Boolean get() = xyz.znix.xftl.hs.sector.HsStarMap.outOfFuel(game.player.fuelCount)

    init {
        xyz.znix.xftl.hs.sector.HsStarMap.hoverLoc = game.currentBeacon.takeIf { game.player.fuelCount >= 1 } // StarMap::Open
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

        if (TouchMode.enabled) {
            val text = game.translator["button_jump"]
            val width = maxOf(buttonFont.measure(text) + 4 * 2, 101)
            touchJumpButton = object : Buttons.BasicButton(
                game, ConstPoint(size.x - 12 - width, 486), ConstPoint(width, 36), text,
                3, font, 27, ::touchJumpPressed
            ) {
                override val nativeFont = buttonFont
                override val disabled: Boolean get() = touchTarget == null || outOfFuel || isWaiting
            }.also { buttons += it }
        }
    }

    /**
     * Draw the insides of the window. This must be done with a stencil.
     */
    private fun drawMapContent(g: Graphics) {
        // Draw the background image, offset 4px to account for the line wall of the window.
        // Hyperspace: a script may replace StarMap::mapsBottom[worldLevel % 3]
        if (!(xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaStarMap.drawBottom(sector.sectorNumber % 3, position.x + 4f, position.y + 4f)))
            background.draw(position.x + 4f, position.y + 4f, Colour(0.75f, 0.75f, 0.75f, 1f))

        mapBase.x = position.x + Sector.OFFSET.x
        mapBase.y = position.y + Sector.OFFSET.y

        // In the last stand, the danger stripes sit behind everything else.
        // TODO draw the player and boss ships below this.
        if (sector.isLastStand) {
            drawLastStandDangerZone(g)
        }

        // Hyperspace showAllConnections (CustomMap.cpp StarMap::DrawConnection, 1.23.2): the first connection drawn
        // is preceded by every other one in white, but those of the current and the hovered beacon.
        if (game.eventManager.hs != null && xyz.znix.xftl.hs.sector.HsStarMap.showAllConnections()) {
            val drawn = HashSet<Pair<Beacon, Beacon>>()
            for (a in sector.beacons) for (b in a.neighbours) {
                if (a === game.currentBeacon || b === game.currentBeacon || a === hovered || b === hovered) continue
                if (!drawn.add(if (System.identityHashCode(a) < System.identityHashCode(b)) a to b else b to a)) continue
                drawBeaconLine(g, a, b, Colour.white, fade = false)
            }
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
            if (TouchMode.enabled) {
                // The path from the ship to the picked beacon
                drawBeaconLine(g, game.currentBeacon, hovered, Constants.BEACON_LINE_HOVER)
            } else {
                // Draw the lines between the hovered beacon and it's neighbours
                drawBeaconLinesTo(g, hovered, Constants.BEACON_LINE_HOVER) { it != game.currentBeacon }
            }
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

            // Hyperspace: Location::known, which scripts may write (MV's Aether boon reveals a ruin).
            val isNeighbour = if (game.eventManager.hs != null) beacon.known else neighbourVisSet.contains(beacon)
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

            // Hyperspace GET_BEACON_HAZARD (StarMap::OnRender): a beacon the callbacks name is drawn as a hazard beacon.
            if (showAdvInfo && (beacon.environmentType.isDangerous ||
                        (game.eventManager.hs != null && xyz.znix.xftl.hs.lua.LuaAdaptersWP1.beaconHazardMarker(beacon)))) {
                if (TouchMode.enabled) drawCentred(beaconEnvironmentHazard, centrePos)
                else beaconEnvironmentHazard.draw(pos.x - 5, pos.y - 5)
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

            if (TouchMode.enabled) beaconShadow.draw(centrePos.x - 20f, centrePos.y - 20f, 40f, 40f)
            else beaconShadow.draw(pos)

            val beaconImg = when (beacon.state) {
                Beacon.State.UNVISITED -> when {
                    showAdvInfo && beacon.event.loadShipName != null -> beaconShipPresent
                    else -> beaconYellow
                }

                Beacon.State.VISITED_CLEAR -> beaconBlue
                Beacon.State.VISITED_DANGER -> beaconDanger
                Beacon.State.OVERTAKEN -> beaconOvertaken
            }
            if (TouchMode.enabled) drawCentred(beaconImg, centrePos) else beaconImg.draw(pos)

            // Hyperspace <beaconType> (StarMap::RenderLabels hook): a custom label, and the original's quest, exit,
            // repair, store and distress labels hidden.
            val hsLabel = if (game.eventManager.hs != null)
                xyz.znix.xftl.hs.events.HsBeaconLabels.label(game, beacon, beacon.visited || isNeighbour, sector.mapRevealed) else null
            val hideVanilla = hsLabel?.hideVanilla == true
            hsLabel?.text?.let { text ->
                if (hsLabel.white) drawBeaconLabel(labelWhite, hsLabel.colour, pos, text)
                else drawBeaconLabel(labelCustom, hsLabel.colour, pos, text, hsLabel.colour)
            }

            if (!hideVanilla && beacon.event.isDistressBeacon && !beacon.visited && showBasicInfo && !willBeOvertaken)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_distress"])

            if (!hideVanilla && beacon.event.isLastStandRepair && !beacon.visited && !willBeOvertaken)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_repair"])

            // Note that quests and stores are cleared when the beacon is overrun,
            // so we don't have to check if willBeOvertaken is set.
            if (!hideVanilla && beacon.hasQuest && !beacon.visited)
                drawBeaconLabel(
                    labelPurple,
                    Constants.SECTOR_CUTOUT_TEXT_PURPLE,
                    pos,
                    game.translator["map_icon_quest"]
                )

            if (!hideVanilla && beacon.hasStore && showBasicInfo)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_store"])

            if (!hideVanilla && beacon.isExit)
                drawBeaconLabel(labelGreen, Constants.SECTOR_CUTOUT_TEXT_GREEN, pos, game.translator["map_icon_exit"])

            if (beacon.isBase)
                drawBeaconLabel(labelPurple, Constants.SECTOR_CUTOUT_TEXT_PURPLE, pos, game.translator["map_icon_base"])

            // A finger on a beacon the ship can't reach still shows where it landed.
            if (beacon == hovered && (TouchMode.enabled || canJumpTo(hovered))) {
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
                // Hyperspace: StarMap::ship / shipNoFuel as a script set them (FTL's primitive sits at -10, -32)
                if (!(xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.lua.LuaStarMap.drawIcon(fuelDistressFlashOn, centrePos.x.toFloat(), centrePos.y.toFloat())))
                    if (TouchMode.enabled && icon.height < 64) icon.draw(centrePos.x - icon.width / 2, centrePos.y - 10 - icon.height)
                    else icon.draw(centrePos.x - 8, centrePos.y - 32)
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
                // StarMap::OnLoop shows the beacon's text at once (an instant tooltip), Hyperspace's rewrite too.
                beaconTooltip = object : xyz.znix.xftl.rendering.StandardTooltip(game) {
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

        // Out of fuel or waiting: the map is tinted black and the text drawn on top. A wait keeps both until it ends,
        // even when a script gave fuel meanwhile; the labels then come on top of them (oracle hg-wait).
        if (outOfFuel || isWaiting) {
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
            val hsMessage = if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.sector.HsStarMap.waitMessage else null
            if (!isWaiting && hsMessage != null) {
                // A script's StarMap::fuelMessage (ForceWaitMessage), drawn in the map's frame.
                g.pushTransform()
                g.translate(position.x.f, position.y.f)
                xyz.znix.xftl.hs.lua.LuaGraphics.renderPrimitive(hsMessage, null)
                g.popTransform()
            } else if (isWaiting) {
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

        drawingDeferredLabels = true
        try {
            for (label in deferredLabels) label()
        } finally {
            drawingDeferredLabels = false
            deferredLabels.clear()
        }
    }

    private val deferredLabels = ArrayList<() -> Unit>()
    private var drawingDeferredLabels = false

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
        val nearby = if (game.eventManager.hs != null) beacon.known else beacon.visited || neighbourVisSet.contains(beacon)
        val known = nearby || sector.mapRevealed
        val scanned = sector.mapRevealed || (nearby && hasLRS)
        val bossBeacon = sector.bosses.any { it.beacon == beacon }
        val event = beacon.event

        // Hyperspace <beaconType> tooltips (StarMap::GetLocationText hook).
        var hsHide = false
        if (game.eventManager.hs != null) {
            val overtaken = willBeOvertaken(beacon, nextFleetPos, tutorial) && !beacon.isOvertaken
            when (val t = xyz.znix.xftl.hs.events.HsBeaconLabels.tooltip(game, beacon, nearby, sector.mapRevealed, overtaken)) {
                is xyz.znix.xftl.hs.events.HsBeaconLabels.Tooltip.Custom -> return hsTooltipLines(t.text, beacon, nearby, hasLRS)
                xyz.znix.xftl.hs.events.HsBeaconLabels.Tooltip.OriginalHidden -> hsHide = true
                else -> Unit
            }
        }

        val shipPresent = if (beacon.visited) beacon.ship != null else event.loadShipName != null
        val repair = !hsHide && event.isLastStandRepair && !beacon.eventCleared

        val description = when {
            beacon == game.currentBeacon -> "map_current_loc"
            bossBeacon && lastStand -> "map_boss_loc"
            (beacon.isExit && !(hsHide && !beacon.visited)) || beacon.isBase -> if (lastStand) "map_base_loc" else "map_exit_loc"
            willBeOvertaken(beacon, nextFleetPos, tutorial) && !beacon.isOvertaken -> "map_rebels_loc"
            beacon.visited && beacon.hasStore && !(bossBeacon && lastStand) -> "map_store_loc"
            known && repair -> "map_repair_loc"
            beacon.isOvertaken -> "map_fleet_loc"
            beacon.visited -> if (shipPresent) "map_hostile_loc" else "map_nothing_loc"
            !hsHide && event.isDistressBeacon && known -> "map_distress_loc"
            repair -> "map_repair_loc"
            shipPresent && scanned -> "map_ship_loc"
            !hsHide && beacon.hasQuest -> "map_quest_loc"
            !hsHide && beacon.hasStore && known -> "map_merchant_loc"
            else -> "map_unvisited_loc"
        }
        val sb = StringBuilder(tr[description])

        if (beacon.isMapNebula) {
            val fleetReady = sector.info.sectorClass == xyz.znix.xftl.sector.GameMap.SectorClass.NEBULA
            sb.append(" \n").append(tr[if (fleetReady) "map_nebula_fleet_loc" else "map_nebula_loc"])
        }

        if (scanned) {
            // Hyperspace GET_BEACON_HAZARD (StarMap::GetLocationText): a callback's string is the hazard line.
            val hsHazard = if (game.eventManager.hs != null) xyz.znix.xftl.hs.lua.LuaAdaptersWP1.beaconHazardText(beacon) else null
            if (hsHazard != null)
                return sb.append(" \n").append(hsHazard).toString()
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

    /** HS GetLocationText with a beacon type's tooltip: the text, then the nebula line and the hazard line. */
    private fun hsTooltipLines(text: String, beacon: Beacon, nearby: Boolean, hasLRS: Boolean): String {
        val tr = game.translator
        val sb = StringBuilder(text)
        if (beacon.isMapNebula) {
            val fleetReady = sector.info.sectorClass == xyz.znix.xftl.sector.GameMap.SectorClass.NEBULA
            sb.append(" \n").append(tr[if (fleetReady) "map_nebula_fleet_loc" else "map_nebula_loc"])
        }
        if (!sector.mapRevealed && (!nearby || !hasLRS)) return sb.toString()
        xyz.znix.xftl.hs.lua.LuaAdaptersWP1.beaconHazardText(beacon)?.let { return sb.append(" \n").append(it).toString() }
        when (beacon.environmentType) {
            Beacon.EnvironmentType.ASTEROID -> sb.append(" \n").append(tr["map_asteroid_loc"])
            Beacon.EnvironmentType.SUN -> sb.append(" \n").append(tr["map_sun_loc"])
            Beacon.EnvironmentType.ION_STORM -> sb.append(" \n").append(tr["map_ion_loc"])
            Beacon.EnvironmentType.PULSAR -> sb.append(" \n").append(tr["map_pulsar_loc"])
            Beacon.EnvironmentType.PDS -> if (!beacon.isBase)
                sb.append(" \n").append(tr[if (beacon.isOvertaken) "map_pds_fleet" else "map_pds_loc"])
            else -> Unit
        }
        return sb.toString()
    }

    // StarMap's FLAGSHIP JUMPING (0x005f1d20): at the map's spot plus (375, -72), with WARNING!, flashing, while
    // the flagship jumps this turn in the last stand (OnRender 0x005fdbf0).
    private val bossJumpWarning = WarningFlasher.atNative(
        game, 0, 0, "warning_boss_jump", true, GlowColour.RED, flash = true
    )

    private val bossHintFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 10)

    /** The touch layout explains the last stand under the map, in a window centred on it. */
    private fun drawBossHint(g: Graphics) {
        val lines = bossHintFont.font.wrapString(game.translator["ipad_boss"], 750)
        val width = lines.maxOf { bossHintFont.measure(it) } + 30
        val x = position.x + 375 - width / 2
        val y = position.y + 590
        game.windowRenderer.render(x, y, width, lines.size * bossHintFont.lineHeight + 20)
        bossHintFont.print(x + 20f, y + 10f, lines.joinToString("\n"), Colour.white)
    }

    private var tooltipBeacon: Beacon? = null
    private var beaconTooltip: xyz.znix.xftl.rendering.StandardTooltip? = null

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

        val sectorName = game.translator[sector.info.displayName]
        val numberX = buttonFont.measure(sectorText) + 18
        val nameX = numberX + 49
        val nameWidth = maxOf(459 - nameX, sectorInfoFont.getWidth(sectorName) + 8)
        drawCutout(position.x + numberX, position.y + size.y - 1, 38, (sector.sectorNumber + 1).toString())
        drawCutout(position.x + nameX, position.y + size.y - 1, nameWidth, sectorName)

        // Hyperspace FTL_JUMPER_GOOD (StarMap::OnRender hook): the hovered beacon's fuel cost, font 0 centred at (173, 83)
        // of the screen, under the fuel counter (oracle hg-nav)
        if (!outOfFuel) xyz.znix.xftl.hs.ship.HsNavAugments.costText(game.player)?.let { text ->
            val red = xyz.znix.xftl.hs.ship.HsNavAugments.fuelReq > game.player.fuelCount
            hsCostFont.printCenter(if (TouchMode.enabled) TouchMode.hudX + 173f else 173f, 83f, text, if (red) Colour(253, 84, 70) else Colour(243, 255, 230))
        }

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
        if (TouchMode.enabled && sector.isLastStand) drawBossHint(g)

        // Draw the out-of-fuel UI
        if (outOfFuel) {
            drawNoFuelUI(null)

            for (button in noFuelButtons) {
                button.draw(g)
            }
        }

        if (!outOfFuel && !isWaiting) touchJumpButton?.let { drawTouchJump(g, it) }

        // Hyperspace splitAllNodeView (StarMap.cpp, after StarMap::OnRender): the superGrid button.
        if (game.eventManager.hs != null) xyz.znix.xftl.hs.sector.HsStarMap.renderGrid(game, g, false)
    }

    /** The Jump button in the frame the out-of-fuel controls use at the same spot. */
    private fun drawTouchJump(g: Graphics, button: Button) {
        val rightX = position.x + size.x
        val imgY = position.y + size.y - 67
        val imgH = waitDistressFrame.height
        val midX = button.pos.x + 3
        waitDistressFrame.drawSection(button.pos.x - 19, imgY, 22, imgH)
        waitDistressFrame.drawSection(midX, imgY, 1, imgH, 22, 0, rightX - 15 - midX)
        waitDistressFrame.drawSection(rightX - 15, imgY, 15, imgH, 62)
        button.draw(g)
    }

    private fun drawCentred(image: Image, centre: IPoint) {
        image.draw(centre.x - image.width / 2, centre.y - image.height / 2)
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
            // StarMap::OnLoop while out of fuel: tooltip_map_wait / tooltip_map_distress over the two buttons.
            val waitTooltip = xyz.znix.xftl.rendering.FixedDelayedTooltip(game, "tooltip_map_wait")
            buttons += object : Buttons.BasicButton(
                game,
                ConstPoint(waitButtonX, frameY + 9), ConstPoint(waitButtonWidth, 36),
                waitText, 4, font, 27, { waitPressed() }
            ) {
                override fun draw(g: Graphics) {
                    super.draw(g)
                    if (hovered && !disabled) g.tooltip = waitTooltip
                }
            }
            val distressTooltip = xyz.znix.xftl.rendering.FixedDelayedTooltip(game, "tooltip_map_distress")

            // Toggle distress beacon
            buttons += object : Button(game, ConstPoint(toggleX, frameY + 9), ConstPoint(toggleButtonWidth, 36)) {
                override fun draw(g: Graphics) {
                    g.colour = when {
                        xyz.znix.xftl.hs.HS.active -> xyz.znix.xftl.hs.render.HsColors.tbFill(disabled, hovered)
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

                    font.drawStringCentred(pos.x.f, pos.y + 27f, size.x.f, label,
                        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.render.HsColors.tbText() else Constants.JUMP_DISABLED_TEXT)
                    if (hovered && !disabled) g.tooltip = distressTooltip
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
            touchTarget = null
            // The tracker advances once per frame (this mouse pass runs twice per update).
            if (waitSerial != game.browserUpdateSerial) {
                waitSerial = game.browserUpdateSerial
                waitingTimer -= game.renderingDeltaTime
            }
            val moving = !sector.isLastStand && !(game.mainGame?.isTutorialRunning ?: false)
            if (waitingTimer <= 0f) {
                waitingTimer = 0f
                // The fleet's move of the wait is completed from where it started (Sector.advanceFleet).
                if (moving) sector.dangerZoneCentre.x = waitStartX
                // StarMap::UpdateDangerZone: the wait itself only follows when the ship is still out of fuel (a
                // script can refill it while the tracker runs); otherwise the map stays open and only the fleet moved
                // (oracle hg-wait).
                if (outOfFuel || !moving) waitOutOfFuel() else game.advanceFleet()
            } else if (moving) {
                // StarMap::UpdateDangerZone while waiting: the zone's centre moves with the tracker's progress (oracle
                // hg-wait: one pixel at a time over the 2 s), and every beacon it reaches on the way is overtaken then.
                val progress = (WAIT_TIME - waitingTimer) / WAIT_TIME
                sector.dangerZoneCentre.x = (progress * sector.getFleetAdvanceFor(game.currentBeacon) + waitStartX).toInt()
                sector.overtakeReached(game)
            }
            return
        }

        super.updateUI(x, y)
        if (game.eventManager.hs != null) xyz.znix.xftl.hs.sector.HsStarMap.mouseMoveGrid(game, x, y, false)

        if (TouchMode.enabled) {
            if (outOfFuel) {
                hovered = null
                touchTarget = null
            } else if (touchFingerDown) {
                touchPick(x, y)
            }
            return
        }

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
        if (closest.second <= hoverDist * hoverDist)
            hovered = closest.first
        // Hyperspace FTL_JUMPER_GOOD (StarMap::MouseMove hook): any beacon the fuel reaches
        hsJumpGood = xyz.znix.xftl.hs.ship.HsNavAugments.mouseMove(game.player, game.currentBeacon, hovered, sector.beacons)
    }

    /** The finger's beacon, within sqrt(1000) px; away from any beacon the last pick stays. */
    private fun touchPick(x: Int, y: Int) {
        if (buttons.any { !it.disabled && it.contains(x, y) }) return
        val finger = ConstPoint(x, y)
        val pick = sector.beacons.minByOrNull { (it.pos + mapBase).distToSq(finger) } ?: return
        if ((pick.pos + mapBase).distToSq(finger) >= TOUCH_PICK_DIST_SQ) return
        hovered = pick
        hsJumpGood = xyz.znix.xftl.hs.ship.HsNavAugments.mouseMove(game.player, game.currentBeacon, pick, sector.beacons)
        touchTarget = pick.takeIf { canJumpTo(it) }
    }

    private fun touchJumpPressed() {
        val target = touchTarget ?: return
        if (canJumpTo(target)) jumpTo(target)
    }

    /** The hovered beacon is a jump target through FTL_JUMPER_GOOD ([xyz.znix.xftl.hs.ship.HsNavAugments]). */
    private var hsJumpGood = false

    /** StarMap::potentialLoc: the hovered beacon when it can be jumped to. */
    val hsPotentialLoc: Beacon? get() = hovered?.takeIf { canJumpTo(it) }

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
        if (hsJumpGood && beacon == hovered)
            return true

        return beacon.visited && hasAdvFtlNavigation
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (isWaiting)
            return
        if (TouchMode.enabled) {
            if (button != Input.MOUSE_LEFT_BUTTON) return
            touchFingerDown = true
            super.mouseClick(button, x, y)
            if (game.eventManager.hs != null) xyz.znix.xftl.hs.sector.HsStarMap.clickGrid(game, false)
            if (!outOfFuel) touchPick(x, y)
            return
        }
        if (!tapArmGate(button, x, y))
            return

        super.mouseClick(button, x, y)

        if (button != Input.MOUSE_LEFT_BUTTON)
            return
        if (game.eventManager.hs != null) xyz.znix.xftl.hs.sector.HsStarMap.clickGrid(game, false)

        val hovered = hovered ?: return

        if (!canJumpTo(hovered))
            return

        jumpTo(hovered)
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON) touchFingerDown = false
        super.mouseReleased(button, x, y)
    }

    private fun jumpTo(hovered: Beacon) {
        jump(hovered)

        // Advance the fleet pursuit *before* changing the beacon, since
        // if we're in a nebula that slows down the fleet pursuit. The scene of the beacon being left stays as
        // it is through the jump-out, even when this jump hands the beacon to the fleet (OvertakeLocation).
        val departing = game.currentBeacon
        val scene = departing.getEnvironment(game)
        sector.hsJumpTarget = hovered
        try {
            game.advanceFleet()
        } finally {
            sector.hsJumpTarget = null
        }
        departing.restoreEnvironment(scene)

        // ShipManager::JumpLeave takes the fuel and starts the jump-out; the beacon switch - and the
        // arrival animation at the new beacon - happens when it ends.
        game.beginJumpOut(hovered)
    }

    /** WAIT: StarMap starts its wait tracker; the wait itself follows when it ends (see [updateUI]). */
    internal fun waitPressed() {
        if (!isWaiting) {
            waitingTimer = WAIT_TIME
            waitStartX = sector.dangerZoneCentre.x
        }
    }

    internal fun waitOutOfFuel() {
        // Close the window and advance the fleet
        jump(game.currentBeacon)
        game.advanceFleet()
        // CommandGui::OnLoop's wait branch calls ShipManager::Wait (0x49d300): the partial jump reset.
        game.player.browserWaitOutOfFuel()
        if (sector.isLastStand && game.browserSpawnBossHere()) {
            game.mainGame.writeRunSave(); return
        }

        // StarMap's wait (0x5f4510): waiting in a hazard first drifts out of
        // it (FUEL_ESCAPE_*); a storm or ASB the fleet has taken over goes
        // straight to the fleet event.
        val beacon = game.currentBeacon
        val overtaken = beacon.isOvertaken
        val environments = beacon.environmentTypes
        // Hyperspace stacked hazards: the first dangerous one picks the escape event
        val environment = environments.firstOrNull { it.isDangerous } ?: beacon.environmentType
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
        beacon.forcedNebula = !overtaken && environments.any { it.isNebula }

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

    /**
     * StarMap::DrawConnection: dotted segments from [from] to [to] whose alpha falls from 1 at the start to 0.3 at the
     * end (the colour's own alpha is not used). Hyperspace's showAllConnections lines skip the per-vertex colours, so
     * they are drawn at full alpha ([fade] false).
     */
    private fun drawBeaconLine(g: Graphics, from: Beacon, to: Beacon, colour: Colour, fade: Boolean = true) {
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
        g.rotate(0f, lineHeight / 2f - 0.5f, angle)

        val segmentWidth = 10

        // Step along the path of the vector, drawing images in each place
        for (i in 6..(dist - 5) step segmentWidth) {
            if (!fade) {
                lineImg.drawSection(i, 0, segmentWidth, lineHeight, 1, 0, colour = colour)
                continue
            }
            val end = i + segmentWidth
            g.drawImageAlphaRamp(
                lineImg, i.f, 0f, end.f, lineHeight.f, 1f, 0f, 1f + segmentWidth, lineHeight.f, colour,
                1f - 0.7f * i / dist, if (end >= dist) 0.3f else 1f - 0.7f * end / dist
            )
        }

        g.popTransform()
    }

    private fun drawBeaconLabel(images: List<Image>, colour: Colour, pos: IPoint, text: String, boxFilter: Colour = Colour.white) {
        // StarMap's label pass (0x005ef050) is skipped while out of fuel.
        if (outOfFuel)
            return
        // The label pass comes after the map's tint and message: a wait refuelled by a script shows its labels over
        // "Waiting..." (oracle hg-wait).
        if (isWaiting && !drawingDeferredLabels) {
            deferredLabels += { drawBeaconLabel(images, colour, pos, text, boxFilter) }
            return
        }
        // AFAIK the labels in FTL are drawn with alpha=204, TODO use that

        val strWidth = beaconLabelFont.getWidth(text)

        val boxTop = pos.y - 11
        val boxTopWithGlow = boxTop - 6

        images[0].draw(pos.x + 15, boxTopWithGlow, boxFilter)
        images[1].draw(pos.x + 34f, boxTopWithGlow.f, strWidth - 8f, 32f, boxFilter)
        images[2].draw(pos.x + 26 + strWidth, boxTopWithGlow, boxFilter)

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

        /** How far (squared) from a beacon a finger still picks it. */
        private const val TOUCH_PICK_DIST_SQ = 1000

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
