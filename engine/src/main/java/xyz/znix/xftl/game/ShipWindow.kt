package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.systems.SubSystem
import xyz.znix.xftl.systems.SystemBlueprint
import kotlin.math.max

private typealias UndoFn = () -> Unit

class ShipWindow(val game: InGameState, val ship: Ship, initialTab: Tab, private val close: () -> Unit) :
    Window() {
    override val size = ConstPoint(587, 464)

    // Touch ergonomics: scale up 1.2x like the map windows. The window
    // plus its right-hand info panel is ~933*1.2 = 1120px wide, so the
    // whole group is pulled left to stay centred on screen (vanilla
    // nudges it just -10 to make room for the power-display column).
    // The -40 vertical nudge clears the ACCEPT/UNDO buttons: they're
    // drawn at local y 464 == the declared window height (their click
    // regions reach y 503), so scaled they hang ~73px below the rect
    // and were ~1/3 clipped off the screen bottom.
    // CommandGui's constructor (0x00538710) places shipScreens at (340,85) and ends with CommandGui::OnLanguageChange,
    // which moves it to 682 less the widest system levels box (shipScreensX): 335 in English vanilla and plain
    // Hyperspace, 325 under Multiverse (oracle hs-offset wp-hs / wp-mv). The native window art lands at (x,78), which
    // puts this origin at (x + 7,85): (342,85) in vanilla.
    // Native window-relative coordinates below are therefore offset by -7 in x.
    // The touch layout puts this origin at (50,85) of the HUD block, with the info box to its right.
    override val windowCentreOffset = ConstPoint(
        if (TouchMode.enabled) -168 else if (PlatformSpecific.INSTANCE.isTouchUi) -266 else desktopX(),
        if (TouchMode.enabled) -67 else if (PlatformSpecific.INSTANCE.isTouchUi) -40 else -43
    )

    override val renderScale = if (PlatformSpecific.INSTANCE.isTouchUi) 1.2f else 1f

    override val desktopCentreOffset: IPoint get() = ConstPoint(desktopX(), -43)

    /** The desktop x offset: -4 puts the origin at 342, 7 right of shipScreens at 335. */
    private fun desktopX() = -4 - (335 - shipScreensX(game))

    override val appliesSelfTint: Boolean get() = dismissConfirm != null

    private val acceptButtonImage = game.getImg("img/upgradeUI/buttons_accept_base.png")
    private val undoButtonImage = game.getImg("img/upgradeUI/buttons_undo_base.png")

    private val shipNameFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 24)
    private val numberFont = game.getFont("num_font")
    private val reactorFont = game.getFont("HL1", 2f)
    private val crewMessageFont = game.getFont("JustinFont10")
    private val noteFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 10)
    private val crewLabelFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 6)
    private val dismissFont = game.getFont("HL1")
    private val maxFont = game.getFont("HL1", 2f)
    private val sectionFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 62)

    // Hyperspace: `<customReactor>` prices and maximum (CustomReactor.cpp ReactorButton hooks).
    private val hsDef = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsShips.def(ship.type.name) else null
    private val font5 by lazy { xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 5) }
    private val font52 by lazy { xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 52) }

    /** The price of a reactor column (`reactorCosts[col]`, or the base cost plus `(col - 1) * increment` where it is -1). */
    private fun hsReactorCost(def: xyz.znix.xftl.hs.ship.CustomShipDefinition, column: Int): Int {
        val c = def.reactorPrices.getOrNull(column) ?: -1
        return if (c >= 0) c else def.reactorPrices[0] + (column - 1) * def.reactorPriceIncrement
    }

    private val upgradeSystemSound = game.sounds.getSample("upgradeSystem")
    private val downgradeSystemSound = game.sounds.getSample("downgradeSystem")
    private val undoUpgradesSound = game.sounds.getSample("undoUpgrades")

    // Hyperspace upgrade screen (CustomUpgrades.cpp): the box pages and the ship rename input.
    private var hsSystemPage = 0
    private var hsSubsystemPage = 0
    private var renamingShip = false
        set(value) {
            field = value
            if (TouchMode.enabled) {
                TouchMode.keyboardWanted = value || renamingCrew != null
                if (value) TouchMode.keyboardFocusY = 0
            }
        }
    private var renameJustStopped = false
    private var renameCursorTimer = 0f

    private var tab: Tab = initialTab
    val tutorialTab: Tab get() = tab
    val pendingWeaponUpgrades: Int
        get() = systemUpgradeUndos.filterKeys { it is xyz.znix.xftl.systems.Weapons }.values.sumOf { it.size }

    fun selectTutorialEquipment() {
        if (tab == Tab.EQUIPMENT) return
        tab = Tab.EQUIPMENT
        updateButtons()
    }

    private val equipmentPanel = ShipEquipmentPanel(game, ship)

    private val infoPanel = InfoPanel(game)

    // These store functions that each undo the last
    // upgrade of a system or the reactor.
    private val systemUpgradeUndos = HashMap<AbstractSystem, ArrayList<UndoFn>>()
    private val reactorUpgradeUndo = ArrayList<UndoFn>()

    /** Touch layout: a box of the upgrades tab, which a finger selects; the get button then buys its next level. */
    interface TouchUpgradeBox {
        val touchIsSelected: Boolean
        val touchIsSelectable: Boolean

        /** The next level's price, or null when there is none to buy. */
        val touchPrice: Int?
        fun touchBuy()
        fun touchSelect()
    }

    private var touchFingerDown = false
    private var touchPressedGet = false
    private var touchSelectedSystem: AbstractSystem? = null
    private var touchReactorSelected = false

    private fun touchSelectedBox(): TouchUpgradeBox? =
        buttons.firstOrNull { it is TouchUpgradeBox && it.touchIsSelected } as TouchUpgradeBox?

    private val touchGet: TouchGetBox? = if (TouchMode.enabled) TouchGetBox(
        game, game.translator["button_upgrade"], { touchSelectedBox()?.touchPrice },
        { touchSelectedBox()?.touchPrice.let { it != null && it != Int.MAX_VALUE } }
    ) { touchSelectedBox()?.touchBuy() } else null

    /** What a finger taps (TouchRouter): the dismiss confirmation's buttons, Upgrade, a button; crew and items drag. */
    override fun liftTarget(x: Int, y: Int): Any? {
        dismissConfirm?.let { val p = scaleWindowPoint(x, y); return it.liftTarget(p.x, p.y) }
        if (touchUpgradesShown && touchGet?.hits(x, y) == true) return touchGet
        super.liftTarget(x, y)?.let { return it }
        if (tab == Tab.EQUIPMENT) {
            val p = scaleWindowPoint(x, y)
            return equipmentPanel.buttonAt(p.x, p.y)?.takeIf { it.touchLift }
        }
        return null
    }

    /** The boxes under the finger select; away from them the selection stays. */
    private fun touchPickUpgrade(x: Int, y: Int) {
        if (touchPressedGet) return
        val box = buttons.lastOrNull { it is TouchUpgradeBox && it.touchIsSelectable && it.contains(x, y) } as TouchUpgradeBox?
        box?.touchSelect()
    }

    private val touchUpgradesShown: Boolean get() = TouchMode.enabled && tab == Tab.UPGRADES && customTab < 0

    private fun touchUpdateUpgrades(x: Int, y: Int) {
        if (touchFingerDown) touchPickUpgrade(x, y)
        for (button in buttons) {
            if (button !is TouchUpgradeBox) button.update(x, y)
            else if (button.touchIsSelected) button.update(button.pos.x + 1, button.pos.y + 1)
            else button.update(-1, -1)
        }
        touchGet?.updateHover(x, y)
    }

    // Touch layout: a crew box's finger: past 9 px it picks the crew member up; lifted on the name without that, renames.
    private var touchCrewCandidate: LivingCrew? = null
    private var touchCrewRename = false
    private var touchCrewStart: ConstPoint = ConstPoint.ZERO

    private fun touchPressCrew(crew: LivingCrew, onName: Boolean) {
        touchCrewCandidate = crew
        touchCrewRename = onName
        touchCrewStart = ConstPoint(mouseX, mouseY)
    }

    private fun touchUpdateCrew(x: Int, y: Int) {
        val candidate = touchCrewCandidate ?: return
        val dx = x - touchCrewStart.x
        val dy = y - touchCrewStart.y
        if (dx * dx + dy * dy > TOUCH_DRAG_DIST * TOUCH_DRAG_DIST) {
            hsDragging = candidate
            touchCrewCandidate = null
        }
    }

    private fun touchReleaseCrew() {
        val candidate = touchCrewCandidate ?: return
        touchCrewCandidate = null
        if (touchCrewRename) renamingCrew = candidate
    }

    private var crewToDismiss: LivingCrew? = null

    // CrewManifest::OnRender (0x004eaf80) opens a 300-wide ConfirmWindow at the
    // crew box + (-53,86) and tints everything but that box 0.25 under it.
    private var dismissConfirm: ConfirmationWindow? = null
    private val dismissHighlightButtons = ArrayList<Button>()
    // The crew boxes drawn last (diagnosticOverCapacity); before the init block, whose updateButtons clears it.
    private val diagnosticCrewBoxes = ArrayList<DiagnosticCrewBox>()

    // If non-null, this is the crewmember whose name we're changing
    private var renamingCrew: LivingCrew? = null
        set(value) {
            field = value
            if (TouchMode.enabled) TouchMode.keyboardWanted = value != null || renamingShip
        }
    private var cursorFlashTimer = 0f

    // If true, the drawing code should re-create any buttons it added
    private var updatingButtons = false

    // Hyperspace customTabs (CustomTabbedWindow.cpp): the custom tab shown, an index into HsTabs.tabs, or -1 for Upgrades,
    // Crew and Equipment (the three tabs the original has).
    private var customTab = -1
    private val hsTabbed = xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.render.HsTabs.enabled && xyz.znix.xftl.hs.render.HsTabs.tabs.isNotEmpty()
    private val hsTabCount get() = 3 + xyz.znix.xftl.hs.render.HsTabs.tabs.size
    private val hsCurrentIndex get() = if (customTab >= 0) 3 + customTab else tab.ordinal
    private fun hsTabName(i: Int) = if (i < 3) HS_BUILTIN_TABS[i] else xyz.znix.xftl.hs.render.HsTabs.tabs[i - 3].name

    /** TabbedWindow::SetTab hook / TabbedWindow::Close / the done button: TABBED_WINDOW_CONFIRM with the current tab's name. */
    private fun hsConfirm() {
        if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.lua.LuaEvents.tabbedWindow(xyz.znix.xftl.hs.lua.LuaApi.EV_TABBED_WINDOW_CONFIRM, hsTabName(hsCurrentIndex))
    }

    // The window opening is SetTab(current tab): the event for the tab it shows (the oracle: one CONFIRM at the open).
    init {
        hsConfirm()
    }

    /** The done button closes the window, which fires TABBED_WINDOW_CONFIRM once (the oracle: one event per click). */
    private fun hsDoneClicked() {
        escapePressed()
    }

    private fun hsSetTab(index: Int) {
        if (index == hsCurrentIndex) return
        hsConfirm()
        if (index < 3) { customTab = -1; tab = Tab.values()[index] } else customTab = index - 3
        updateButtons()
    }

    private val closeButton = object : Buttons.BasicButton(
        // TabbedWindow::SetPosition (0x00518080) puts the button 23,7 inside its art.
        game, position + ConstPoint(429, 471),
        ConstPoint(132, 32), game.translator["button_accept"],
        4, game.getFont("HL2", 3f), 25,
        { hsDoneClicked() }
    ) {
        override val nativeFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)

        override val disabled: Boolean get() = !tutorialAcceptAllowed()
    }

    init {
        // TODO too many items

        updateButtons()

    }

    private fun confirmDismiss(crew: LivingCrew) {
        crewToDismiss = crew
        dismissConfirm = ConfirmationWindow(SettingsContext(game), "confirm_dismiss", 300, accept = {
            game.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.LOST_CREW)
            // If the crewmember is in the cloning queue, remove them from there.
            ship.clonebay?.queue?.remove(crew)

            crew.removeFromShip()
            // CrewEquipBox::RemoveItem hook (dismissSound, "airLoss" by default).
            if (hsDef != null) xyz.znix.xftl.hs.HS.cfg().dismissSound.takeIf { it.isNotEmpty() }?.let { game.sounds.getSampleOrWarn(it)?.play() }
            crewToDismiss = null
            dismissConfirm = null
            updateButtons()
        }, cancel = {
            crewToDismiss = null
            dismissConfirm = null
            updateButtons()
        })
        updateButtons()
    }

    private fun updateButtons() {
        buttons.clear()
        dismissHighlightButtons.clear()
        diagnosticCrewBoxes.clear()

        buttons += closeButton

        // The buttons can change their icon depending on which of the other two
        // tabs are selected, so re-create them whenever a different tab is selected.

        if (hsTabbed) {
            for (i in 0 until hsTabCount) if (i != hsCurrentIndex) buttons += HsTabButton(i)
        } else {
            if (tab != Tab.UPGRADES)
                buttons += TabButton(Tab.UPGRADES, ConstPoint(-GLOW_WIDTH, -GLOW_WIDTH), ConstPoint(109, 48))
            if (tab != Tab.CREW)
                buttons += TabButton(Tab.CREW, ConstPoint(75, -GLOW_WIDTH), ConstPoint(127, 48))
            if (tab != Tab.EQUIPMENT)
                buttons += TabButton(Tab.EQUIPMENT, ConstPoint(175, -GLOW_WIDTH), ConstPoint(123, 48))
        }

        // Switching tabs away from upgrades commits the system upgrades
        if (tab != Tab.UPGRADES || customTab >= 0) {
            commitUpgrades()
        }

        // Update the button window offsets
        positionUpdated()

        // Update any buttons added in the drawing code
        updatingButtons = true
    }

    override fun draw(g: Graphics) {
        if (!xyz.znix.xftl.hs.HS.luaActive) { drawBody(g); return }
        // TabbedWindow::OnRender (CustomTabbedWindow.cpp): RenderEvents.TABBED_WINDOW with the tab's name, in the window's frame
        // (the art's origin); a pre callback that pre-empts it skips the window.
        val ox = (position.x - GLOW_WIDTH).f; val oy = (position.y - GLOW_WIDTH).f
        val re = xyz.znix.xftl.hs.render.HsRenderEvents.preString(xyz.znix.xftl.hs.lua.LuaApi.RE_TABBED_WINDOW, hsTabName(hsCurrentIndex), ox, oy)
        if (re >= 0) drawBody(g)
        xyz.znix.xftl.hs.render.HsRenderEvents.postString(xyz.znix.xftl.hs.lua.LuaApi.RE_TABBED_WINDOW, re, ox, oy)
    }

    private fun drawBody(g: Graphics) {
        // With custom tabs the equipment window uses HS's wider art, equipment_main_2 (the tab shape moves right).
        val equipment2 = if (customTab < 0 && hsTabbed && tab == Tab.EQUIPMENT && game.content.datafile.getOrNull("img/upgradeUI/Equipment/equipment_main_2.png") != null)
            game.getImg("img/upgradeUI/Equipment/equipment_main_2.png") else null
        val backgroundImage = equipment2 ?: if (customTab >= 0) hsCustomBackground() else game.getImg("img/upgradeUI/Equipment/${tab.textureName}_main.png")
        val baseTint = Graphics.tint
        val dismissing = dismissConfirm != null
        infoPanel.touchBottom = 0
        fun tinted(factor: Float) = Colour(baseTint.r * factor, baseTint.g * factor, baseTint.b * factor, baseTint.a)

        // TabbedWindow::OnRender (0x00518660) draws the other tabs before the window,
        // so the window art's glow overlaps their bottom edge. Under a priority popup
        // it tints them (measured 0.35 of their normal colour).
        if (dismissing) Graphics.tint = tinted(TAB_POPUP_TINT)
        // A custom tab's window art is drawn first: the oracle shows the tab buttons over it.
        if (customTab < 0) for (button in buttons) {
            if (button is TabButton || button is HsTabButton)
                button.draw(g)
        }
        if (dismissing) Graphics.tint = tinted(MANIFEST_POPUP_TINT)

        backgroundImage.draw(
            position.x - GLOW_WIDTH,
            position.y - GLOW_WIDTH
        )
        if (customTab >= 0) for (button in buttons) {
            if (button is HsTabButton) button.draw(g)
        }

        if (!dismissing)
            acceptButtonImage.draw(position.x + 406f, position.y + 464f)

        if (customTab >= 0) drawCustomTab(g)
        else when (tab) {
            Tab.UPGRADES -> drawUpgrades(g)
            Tab.CREW -> drawCrew(g)
            Tab.EQUIPMENT -> drawEquipment(g)
        }
        if (hsTabbed) drawHsTabOverlays(g)

        if (updatingButtons) {
            // Update any newly-created button positions
            positionUpdated()
            updatingButtons = false
        }

        // When we're dismissing crew we grey out everything else, this
        // means fiddling with how the buttons are drawn.
        if (dismissing) {
            drawButtonsDismissing(g, baseTint)
        } else {
            for (button in buttons) {
                if (button !is TabButton && button !is HsTabButton)
                    button.draw(g)
            }
        }

        if (touchGet != null) {
            if (touchUpgradesShown && touchSelectedBox() != null) touchGet.drawBelow(g, infoPanel) else touchGet.hide()
            hsDragging?.let { it.drawPortrait(mouseX - 34, mouseY - 34, false, 2f) }
        }

        equipmentPanel.drawDrag(g)
    }

    private fun drawButtonsDismissing(g: Graphics, baseTint: Colour) {
        // TabbedWindow skips its Accept button under the popup.
        for (button in buttons) {
            if (dismissHighlightButtons.contains(button) || button is TabButton || button is HsTabButton || button === closeButton)
                continue

            button.draw(g)
        }

        Graphics.tint = baseTint
        for (button in dismissHighlightButtons) {
            button.draw(g)
        }

        dismissConfirm?.draw(g)
    }

    override fun shipModified() {
        super.shipModified()
        equipmentPanel.shipModified()
    }

    private fun drawUpgrades(g: Graphics) {
        // Draw the ship name
        // Upgrades::OnRender prints the ship's own name (the one entered in the hangar, or renamed in the run).
        val name = ship.displayName ?: ship.type.shipTitle?.let { game.translator[it] } ?: ""

        shipNameFont.printCenter(position.x + 295f, position.y + 39f, name, Colour.white)
        if (renamingShip) drawRenameCursor(g, name)

        // Upgrades::OnRender (0x0054c7d0) labels the three frames in font 62.
        val labelColour = Colour(25, 49, 51)
        sectionFont.print(position.x + 11f, position.y + 66f, game.translator["upgrade_frame_systems"], labelColour)
        sectionFont.print(position.x + 11f, position.y + 290f, game.translator["upgrade_frame_subsystems"], labelColour)
        sectionFont.print(position.x + 289f, position.y + 290f, game.translator["upgrade_frame_reactor"], labelColour)

        undoButtonImage.draw(position.x + 3f, position.y + 464f)
        if (updatingButtons) {
            buttons += object : Buttons.BasicButton(
                game, ConstPoint(26, 471), ConstPoint(97, 32),
                game.translator["button_undo"],
                4, game.getFont("HL2", 3f), 25,
                this::undoAllSystems
            ) {
                override val nativeFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)

                // Grey the button out when there's nothing to undo
                override val disabled: Boolean get() = systemUpgradeUndos.all { it.value.isEmpty() } && reactorUpgradeUndo.isEmpty()
            }
        }

        // Draw the system power info.
        // Note the reactor button draws this itself.
        val hoveredButton = buttons.filterIsInstance(UpgradeButton::class.java).firstOrNull { it.hovered }
        val hoveredSystem = if (TouchMode.enabled) touchSelectedSystem else hoveredButton?.system
        if (hoveredSystem != null) {
            val undoablePower = systemUpgradeUndos[hoveredSystem]?.size ?: 0
            // CommandGui::OnLanguageChange gives the upgrades window's InfoBox the widest system levels box: 333 here
            // for vanilla's 347, 10 wider under Multiverse (oracle hs-offset wp-hs2 / wp-mv2 13-sensors-info)
            val width = infoPanel.customWidth
            if (!TouchMode.enabled && !PlatformSpecific.INSTANCE.isTouchUi) infoPanel.customWidth = 333 + systemBoxWidth(game) - 347
            diagnosticSystemInfo = "${infoPanel.position.x},${infoPanel.position.y},${infoPanel.customWidth ?: 333}"
            infoPanel.drawDescriptionBoxSystem(hoveredSystem)
            // InfoBox::SetSystem takes the system's own maximum (ShipSystem), not the blueprint's
            infoPanel.drawSystemPowerBox(g, hoveredSystem.blueprint, hoveredSystem.energyLevels, undoablePower, hoveredSystem.maxLevel)
            infoPanel.customWidth = width
        }

        // Hyperspace pages the boxes: eight systems and four subsystems a page, at least the ship's systemLimit /
        // subsystemLimit boxes (CustomUpgrades.cpp AddUpgradeBox / AddEmptyUpgradeBox).
        val hsUp = hsDef != null
        val subsystems = ship.rooms.mapNotNull { it.system as? SubSystem }.sortedBy { it.sortingType }
        val systemTotal = if (hsDef != null) maxOf(ship.mainSystems.size, hsDef.systemLimit) else 8
        val subsystemTotal = if (hsDef != null) maxOf(subsystems.size, hsDef.subsystemLimit) else 4
        val systemPages = xyz.znix.xftl.hs.render.HsUpgrades.pages(systemTotal, 8)
        val subsystemPages = xyz.znix.xftl.hs.render.HsUpgrades.pages(subsystemTotal, 4)
        hsSystemPage = hsSystemPage.coerceIn(0, systemPages - 1)
        hsSubsystemPage = hsSubsystemPage.coerceIn(0, subsystemPages - 1)

        // Draw the systems
        for (i in 0 until 8) {
            if (!updatingButtons)
                continue
            if (hsUp && hsSystemPage * 8 + i >= systemTotal)
                continue

            val system = ship.mainSystems.getOrNull(if (hsUp) hsSystemPage * 8 + i else i)

            val price: Int? = when {
                system == null -> null
                system.energyLevels == system.maxLevel -> null
                // a level past the blueprint's price list (a ship `max` above it, HS) reads past UpgradeBox's vector in the
                // original (oracle sw4-up24: "--" and 0); here it cannot be bought
                else -> system.blueprint.upgradeCost.getOrNull(system.energyLevels - 1) ?: Int.MAX_VALUE
            }

            val isFullyUpgraded = system != null && price == null

            val baseImage = when {
                system == null -> "img/upgradeUI/upgrade_system_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_system_bar_max_on.png"
                else -> "img/upgradeUI/upgrade_system_bar_on.png"
            }
            val selectImage = when {
                system == null -> "img/upgradeUI/upgrade_system_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_system_bar_max_select2.png"
                else -> "img/upgradeUI/upgrade_system_bar_select2.png"
            }

            buttons += UpgradeButton(
                ConstPoint(32 + 66 * i, 115), ConstPoint(66, 150),
                game.getImg(selectImage), game.getImg(baseImage),
                system, price
            )
        }

        // Draw the subsystems
        // TODO only go until 3 on non-AE?
        for (i in 0 until 4) {
            if (!updatingButtons)
                continue
            if (hsUp && hsSubsystemPage * 4 + i >= subsystemTotal)
                continue

            val index = if (hsUp) hsSubsystemPage * 4 + i else i
            val system = if (index < subsystems.size) subsystems[index] else null

            val price: Int? = when {
                system == null -> null
                system.energyLevels == system.maxLevel -> null
                // a level past the blueprint's price list (a ship `max` above it, HS) reads past UpgradeBox's vector in the
                // original (oracle sw4-up24: "--" and 0); here it cannot be bought
                else -> system.blueprint.upgradeCost.getOrNull(system.energyLevels - 1) ?: Int.MAX_VALUE
            }

            val isFullyUpgraded = system != null && price == null

            val baseImage = when {
                system == null -> "img/upgradeUI/upgrade_subsystem_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_subsystem_bar_max_on.png"
                else -> "img/upgradeUI/upgrade_subsystem_bar_on.png"
            }
            val selectImage = when {
                system == null -> "img/upgradeUI/upgrade_subsystem_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_subsystem_bar_max_select2.png"
                else -> "img/upgradeUI/upgrade_subsystem_bar_select2.png"
            }

            buttons += UpgradeButton(
                ConstPoint(9 + 66 * i, 330), ConstPoint(66, 113),
                game.getImg(selectImage), game.getImg(baseImage),
                system, price, 1
            )
        }

        // Hyperspace page arrows (UpgradeBoxSet buttons at (18,182)/(572,182) and (18,315)/(265,315)) and the rename button
        if (updatingButtons && hsUp) {
            if (systemPages > 1) {
                buttons += PageArrow(ConstPoint(11, 182), false) { hsSystemPage = (hsSystemPage + systemPages - 1) % systemPages; updateButtons() }
                buttons += PageArrow(ConstPoint(565, 182), true) { hsSystemPage = (hsSystemPage + 1) % systemPages; updateButtons() }
            }
            if (subsystemPages > 1) {
                buttons += PageArrow(ConstPoint(11, 315), false) { hsSubsystemPage = (hsSubsystemPage + subsystemPages - 1) % subsystemPages; updateButtons() }
                buttons += PageArrow(ConstPoint(258, 315), true) { hsSubsystemPage = (hsSubsystemPage + 1) % subsystemPages; updateButtons() }
            }
            if (xyz.znix.xftl.hs.HS.cfg().renameShipAllowed && xyz.znix.xftl.hs.HS.cfg().renameShipButton) buttons += RenameButton()
        }

        // Add the reactor button
        if (updatingButtons) {
            val reactorImg = game.getImg("img/upgradeUI/Equipment/equipment_reactor_on.png")
            val reactorHighlight = game.getImg("img/upgradeUI/Equipment/equipment_reactor_select2.png")
            buttons += object : Button(game, ConstPoint(298, 327), reactorImg.imageSize), TouchUpgradeBox {
                override val disabled: Boolean
                    get() = hsDef != null && ship.purchasedReactorPower - reactorUpgradeUndo.size >= hsDef.maxReactorLevel

                override val touchIsSelected: Boolean get() = touchReactorSelected
                override val touchIsSelectable: Boolean get() = true
                override val touchPrice: Int?
                    get() = currentPrice.takeIf { !disabled && ship.purchasedReactorPower < ship.maxReactorPower }

                override fun touchBuy() = click(Input.MOUSE_LEFT_BUTTON)

                override fun touchSelect() {
                    touchReactorSelected = true
                    touchSelectedSystem = null
                }

                val currentPrice: Int
                    get() {
                        hsDef?.let { return hsReactorCost(it, ship.purchasedReactorPower / 5 + 1) }

                        // Expensive first power on the Cernkov
                        if (ship.purchasedReactorPower < 5)
                            return 30

                        return (ship.purchasedReactorPower / 5) * 5 + 15
                    }

                override fun draw(g: Graphics) {
                    if (hsDef != null) { drawHs(g, hsDef); return }
                    if (hovered) {
                        reactorHighlight.draw(pos)

                        infoPanel.drawDescriptionBox(
                            GameText.localised("upgrade_reactor"),
                            GameText.localised("reactor_desc"),
                            null, emptyList(),
                            InfoPanel.INFO_HEIGHT_SYSTEM
                        )
                    } else {
                        reactorImg.draw(pos)
                    }

                    val fontColour = when (hovered) {
                        true -> Constants.STORE_BUY_HOVER
                        false -> Constants.SECTOR_CUTOUT_TEXT
                    }

                    // Figure out the power range that should show as downgrades
                    val lastNonRefundablePower = ship.purchasedReactorPower - reactorUpgradeUndo.size
                    val refundRange = lastNonRefundablePower until ship.purchasedReactorPower

                    // Draw the energy bars
                    for (level in 0 until ship.maxReactorPower) {
                        g.colour = when {
                            level in refundRange -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                            ship.purchasedReactorPower > level -> Constants.SYS_ENERGY_ACTIVE
                            hovered -> Constants.SYS_ENERGY_PURCHASE_HOVER
                            else -> Constants.SYS_ENERGY_PURCHASE
                        }

                        g.fillRect(
                            pos.x + 30f + (level / 5) * 44,
                            pos.y + 66f - (level % 5) * 13,
                            32f, 8f
                        )
                    }

                    // Draw the current price, or two dashes if it's maxed out.
                    val priceStr = when (ship.purchasedReactorPower < ship.maxReactorPower) {
                        true -> currentPrice.toString()
                        false -> "--"
                    }
                    numberFont.drawString(pos.x + 235f, pos.y + 105f, priceStr, fontColour)

                    // Draw the 'n power bars' text - this is annoyingly mixed between two fonts
                    // TODO mix them properly to work in languages where the power number doesn't
                    //  come first - is this something FTL does?
                    val text = game.translator["upgrade_reactor_power"].replaceArg("")
                    reactorFont.drawStringLeftAligned(pos.x + 179f, pos.y + 105f, text, fontColour)
                    numberFont.drawStringLeftAligned(
                        pos.x + 47f,
                        pos.y + 105f,
                        ship.purchasedReactorPower.toString(),
                        fontColour
                    )
                }

                /** ReactorButton::OnRender under Hyperspace: past 25 bars the five columns scroll; column prices on top. */
                private fun drawHs(g: Graphics, def: xyz.znix.xftl.hs.ship.CustomShipDefinition) {
                    val maxLevel = def.maxReactorLevel
                    val tempUpgrade = reactorUpgradeUndo.size
                    val reactorLevel = ship.purchasedReactorPower - tempUpgrade
                    val tempLevel = ship.purchasedReactorPower
                    val active = reactorLevel < maxLevel
                    val img = if (!active) game.getImg("img/upgradeUI/Equipment/equipment_reactor_off.png") else if (hovered) reactorHighlight else reactorImg
                    img.draw(pos)
                    if (hovered) infoPanel.drawDescriptionBox(GameText.localised("upgrade_reactor"), GameText.localised("reactor_desc"), null, emptyList(), InfoPanel.INFO_HEIGHT_SYSTEM)

                    var overCol = 0
                    var displayReactorLevel = reactorLevel
                    var displayTempUpgrade = tempUpgrade
                    if (tempLevel >= 25) {
                        overCol = (tempLevel - 25) / 5 + 1
                        if (tempLevel == maxLevel && maxLevel % 5 == 0) overCol--
                        if (displayReactorLevel < overCol * 5) {
                            displayTempUpgrade -= overCol * 5 - displayReactorLevel
                            displayReactorLevel = 0
                        } else displayReactorLevel -= overCol * 5
                    }
                    val displayTempLevel = displayTempUpgrade + displayReactorLevel
                    var displayMaxLevel = maxLevel
                    while (displayMaxLevel > 25) displayMaxLevel -= 5
                    val displayMaxCol = if (displayMaxLevel > 20) 5 else displayMaxLevel / 5

                    val fontColour = if (active && hovered) Constants.STORE_BUY_HOVER else Constants.SECTOR_CUTOUT_TEXT
                    for (col in 0 until 5) {
                        for (row in 0 until 5) {
                            val bar = col * 5 + row + 1
                            g.colour = when {
                                bar <= displayReactorLevel -> Constants.SYS_ENERGY_ACTIVE
                                bar <= displayTempLevel -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                                bar > displayMaxLevel -> continue
                                else -> HS_EMPTY_BAR
                            }
                            g.fillRect(pos.x + 30f + col * 44, pos.y + 66f - row * 13, 32f, 8f)
                        }
                        val price = if (col >= displayMaxCol) "--" else hsReactorCost(def, col + 1 + overCol).toString()
                        font5.printRight(pos.x + 47f + col * 44, pos.y + 2f, price, fontColour)
                    }

                    val text = game.translator["upgrade_reactor_power"].replaceArg("")
                    reactorFont.drawStringLeftAligned(pos.x + 179f, pos.y + 105f, text, fontColour)
                    numberFont.drawStringLeftAligned(pos.x + 47f, pos.y + 105f, tempLevel.toString(), fontColour)
                    when {
                        !active -> font52.printCenter(pos.x + 238f, pos.y + 88f, game.translator["upgrade_max"], fontColour)
                        tempLevel == maxLevel -> font52.print(pos.x + 233f, pos.y + 88f, "--", fontColour)
                        else -> numberFont.drawString(pos.x + 235f, pos.y + 105f, currentPrice.toString(), fontColour)
                    }
                }

                override fun click(button: Int) {
                    if (button == Input.MOUSE_RIGHT_BUTTON) {
                        val undoLast = reactorUpgradeUndo.lastOrNull() ?: return
                        reactorUpgradeUndo.remove(undoLast)
                        undoLast()
                        downgradeSystemSound.play()
                        return
                    }

                    if (button != Input.MOUSE_LEFT_BUTTON)
                        return

                    if (ship.purchasedReactorPower >= ship.maxReactorPower)
                        return

                    // Store the price so undos work properly.
                    val price = currentPrice

                    if (ship.scrap < price) {
                        game.shipUI.playInsufficientScrapAnimation()
                        return
                    }
                    ship.scrap -= price

                    ship.purchasedReactorPower++

                    // This is how the user can revert an upgrade
                    reactorUpgradeUndo += {
                        ship.scrap += price
                        ship.purchasedReactorPower--
                    }

                    upgradeSystemSound.play()
                }
            }
        }
    }

    /** A UpgradeBoxSet page arrow (`upgradeUI/buttons_system_arrow`, the right one mirrored). */
    private inner class PageArrow(pos: ConstPoint, private val mirrored: Boolean, private val action: () -> Unit) :
        Button(game, pos, ConstPoint(14, 12)) {
        override val makesHoverNoise: Boolean get() = true

        override fun draw(g: Graphics) {
            val image = game.getImg("img/upgradeUI/buttons_system_arrow_${if (hovered) "select2" else "on"}.png")
            if (mirrored) {
                g.pushTransform(); g.translate(pos.x + image.width.f, pos.y.f); g.scale(-1f, 1f); image.draw(0, 0); g.popTransform()
            } else image.draw(pos.x, pos.y)
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON) action()
        }
    }

    /** CustomUpgrades' `renameButton` (`upgradeUI/buttons_system_rename`, allowButton). */
    private inner class RenameButton : Button(game, ConstPoint(470, 46), ConstPoint(38, 38)) {
        override fun draw(g: Graphics) {
            game.getImg("img/upgradeUI/buttons_system_rename_${if (hovered) "select2" else "on"}.png").draw(pos.x, pos.y)
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON && !renameJustStopped) startShipRename()
        }
    }

    private fun startShipRename() {
        renamingShip = true
        renameCursorTimer = 0f
        if (ship.displayName == null) ship.displayName = ship.type.shipTitle?.let { game.translator[it] } ?: ""
    }

    /** TextInput::Stop plus the empty-name fallback (CustomUpgrades::MouseClick / Close). */
    private fun stopShipRename() {
        if (!renamingShip) return
        renamingShip = false
        if (ship.displayName.isNullOrEmpty()) ship.displayName = game.translator["nameless_one"]
    }

    private fun drawRenameCursor(g: Graphics, name: String) {
        renameCursorTimer += game.renderingDeltaTime
        val period = 0.5f
        if (renameCursorTimer > period) renameCursorTimer -= period
        if (renameCursorTimer < period / 2f) {
            val width = shipNameFont.width(name)
            g.colour = Colour.yellow
            g.fillRect(position.x + 295f + width / 2f + 2f, position.y + 18f, 2f, 22f)
        }
    }

    inner class UpgradeButton(
        pos: ConstPoint, size: ConstPoint,
        private val selectImage: Image,
        private val baseImage: Image,
        val system: AbstractSystem?,
        private val upgradePrice: Int?,
        /** Subsystem boxes draw their icon and bars one pixel lower than system boxes. */
        private val contentYOffset: Int = 0
    ) : Button(game, pos, size), TouchUpgradeBox {
        override val disabled: Boolean get() = system == null

        override val touchIsSelected: Boolean get() = system != null && system === touchSelectedSystem
        override val touchIsSelectable: Boolean get() = system != null
        override val touchPrice: Int? get() = upgradePrice

        override fun touchBuy() = click(Input.MOUSE_LEFT_BUTTON)

        override fun touchSelect() {
            touchSelectedSystem = system
            touchReactorSelected = false
        }

        override fun draw(g: Graphics) {
            // Draw the main button image
            val image = if (hovered) selectImage else baseImage
            image.draw(pos.x, pos.y)

            if (system == null)
                return

            val fontColour = when (hovered) {
                true -> Constants.STORE_BUY_HOVER
                false -> Constants.SECTOR_CUTOUT_TEXT
            }

            // Draw the system icon
            val systemIcon = game.getImg(system.blueprint.onIconPath)
            systemIcon.draw(
                pos.x - SystemBlueprint.ICON_GLOW + 19f,
                pos.y + size.y - SystemBlueprint.ICON_GLOW - 65f + contentYOffset
            )

            // Figure out the power range that should show as downgrades
            val undoablePower = systemUpgradeUndos[system]?.size ?: 0
            val lastNonRefundablePower = system.energyLevels - undoablePower + 1 // Power here is 1-indexed
            val refundRange = lastNonRefundablePower..system.energyLevels

            // Draw the energy bars (ShipSystem::RenderPowerBoxesPlain: 15x6 bars 2 apart from the bottom up).
            // Hyperspace (CustomUpgrades.cpp, Upgrades::OnRender + RenderPowerBoxesPlain + GL_RenderPrimitiveWithColor
            // hooks): over 8 bars, two columns of eight show, 10 px left and 11 px right of the usual place, the left one
            // the column below the column the bought and pending levels reach into; their top levels are written
            // above them once the left column is not the first.
            val bottomY = pos.y + size.y - 76f + contentYOffset
            val max = system.maxLevel
            val twoColumns = xyz.znix.xftl.hs.HS.active && max > 8
            var leftColumn = 0
            if (twoColumns) {
                val reached = system.energyLevels
                leftColumn = if (reached / 8 > 0) reached / 8 - 1 else 0
                if (reached % 8 == 0 && reached == max && leftColumn > 0) leftColumn--
                if (leftColumn > 0) {
                    // easy_printCenter(5, x + 21, y - 62): x is 24 left of the bars, the bottom bar 8 below y (oracle
                    // sw4-up24 u0: digits' rows 207-213 over a bottom bar at 274)
                    font5.printCenter(pos.x + 21f, bottomY - 8f - 62f, ((leftColumn + 1) * 8).toString(), Colour.white)
                    font5.printCenter(pos.x + 42f, bottomY - 8f - 62f, ((leftColumn + 2) * 8).toString(), Colour.white)
                }
            }
            for (level in 1..max) {
                var dx = 0f
                var dy = 0f
                if (twoColumns) {
                    val column = (level - 1) / 8
                    dx = when (column) { leftColumn -> -10f; leftColumn + 1 -> 11f; else -> continue }
                    dy = column * 64f
                }
                g.colour = when {
                    level in refundRange -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                    system.energyLevels >= level -> Constants.SYS_ENERGY_ACTIVE
                    // Hyperspace (FTL 1.6.9): the bars still to buy are the reactor's 1,1,100/255 at 0.25 (71,80,50 on the
                    // panel), hovered or not (oracle sw3-up16 u0, u-h0..h5); 1.6.14 lightens a hovered box's (vn-aug n5-n7)
                    xyz.znix.xftl.hs.HS.active -> HS_EMPTY_BAR
                    hovered -> Constants.SYS_ENERGY_PURCHASE_HOVER
                    else -> Constants.SYS_ENERGY_PURCHASE
                }

                g.fillRect(
                    pos.x + 24f + dx,
                    bottomY - (level - 1) * 8f + dy,
                    15f, 6f
                )
            }

            // Draw the scrap amount
            if (upgradePrice != null) {
                numberFont.drawString(pos.x + 30f, pos.y + size.y - 8f, xyz.znix.xftl.hs.render.HsUpgrades.priceText(upgradePrice), fontColour)
            }

            if (system.energyLevels == system.maxLevel) {
                val maxStr = game.translator["upgrade_max"]
                maxFont.drawStringCentred(pos.x.f, pos.y + size.y - 8f, size.x.f, maxStr, fontColour)
            }
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_RIGHT_BUTTON) {
                val undos = systemUpgradeUndos[system] ?: return
                val undoLast = undos.lastOrNull() ?: return
                undos.remove(undoLast)
                undoLast()
                downgradeSystemSound.play()
                return
            }

            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (upgradePrice == null)
                return

            if (ship.scrap < upgradePrice) {
                game.shipUI.playInsufficientScrapAnimation()
                return
            }
            ship.scrap -= upgradePrice

            // ShipSystem::UpgradeSystem (0x63be60): the level and the healthy bars grow together, except for a
            // completely destroyed system, which stays destroyed (the new bar is damaged too).
            val destroyed = system!!.broken
            system.energyLevels++
            if (destroyed)
                system.damagedEnergyLevels++

            val undos = systemUpgradeUndos.getOrPut(system) { ArrayList() }
            undos += {
                system.energyLevels--
                if (destroyed)
                    system.damagedEnergyLevels--
                ship.scrap += upgradePrice
                updateButtons()
            }

            // Replace this button to reflect the upgrade
            updateButtons()

            upgradeSystemSound.play()
        }
    }

    /** Diagnostics (WP2 probe): the Hyperspace paging state of the open tab. */
    val diagnosticState: String get() = "{\"tab\":\"$tab\",\"sysPage\":$hsSystemPage,\"subPage\":$hsSubsystemPage,\"crewPage\":$hsCrewPage," +
        "\"crewLimit\":${if (hsDef != null) hsCrewLimit() else -1},\"renaming\":$renamingShip,\"name\":${ftl.browser.BrowserDebug.quote(ship.displayName ?: "")}," +
        "\"boxes\":${hsCrewBoxes.joinToString(",", "[", "]") { "[${it.first.x},${it.first.y},${it.second}]" }},\"equip\":${equipmentPanel.diagnosticState}}"

    /** A crew box as drawn last: its slot, place (window frame), crew member and whether it is the over-capacity one. */
    private class DiagnosticCrewBox(val id: Int, val pos: ConstPoint, val crew: LivingCrew?, val over: Boolean)

    /**
     * Tests (tests/overcap.mjs through BrowserEngine.overcapProbe): the open tab's over-capacity boxes and crew boxes,
     * relative to the window's origin ([position], given as `origin`): the crew boxes (100 x 86, the dismiss button 92 x 12
     * at (4, 70)), the crew DropBox, the equipment tab's over-capacity boxes, the other things they must not cover, the
     * dismiss confirmation's Yes and the screen (the desktop's 1280 x 720, the touch HUD block's 1024 x 768 around the
     * window's (50, 85)).
     */
    val diagnosticOverCapacity: String get() {
        fun r(x: Int, y: Int, w: Int, h: Int) = "{\"x\":$x,\"y\":$y,\"w\":$w,\"h\":$h}"
        fun rb(b: Button) = r(b.pos.x - position.x, b.pos.y - position.y, b.size.x, b.size.y)
        val drop = buttons.firstOrNull { it is ShipEquipmentPanel.SellDropBox }
        val crew = if (tab != Tab.CREW) "" else diagnosticCrewBoxes.joinToString(",") { b ->
            "{\"id\":${b.id},\"over\":${b.over},\"crew\":${ftl.browser.BrowserDebug.quote(b.crew?.info?.name)}," +
                "\"x\":${b.pos.x},\"y\":${b.pos.y},\"w\":100,\"h\":86" +
                (if (b.crew != null) ",\"dismiss\":${r(b.pos.x + 4, b.pos.y + 70, 92, 12)}" else "") + "}"
        }
        val yes = dismissConfirm?.yesCentre
        val screen = if (TouchMode.enabled) r(-50, -85, 1024, 768) else r(-position.x, -position.y, 1280, 720)
        val others = "{\"name\":\"window\",\"rect\":${r(-GLOW_WIDTH, -GLOW_WIDTH, 601, 478)}},{\"name\":\"accept\",\"rect\":${rb(closeButton)}}"
        return "{\"tab\":\"$tab\",\"touch\":${TouchMode.enabled},\"origin\":{\"x\":${position.x},\"y\":${position.y}},\"screen\":$screen," +
            "\"drop\":${if (tab == Tab.CREW && drop != null) rb(drop) else "null"},\"crew\":[$crew]," +
            "\"confirmYes\":${if (yes == null) "null" else r(yes.x - position.x - 5, yes.y - position.y - 5, 10, 10)}," +
            "\"confirm\":${dismissConfirm?.let { r(it.position.x - position.x, it.position.y - position.y, it.size.x, it.size.y) } ?: "null"}," +
            "\"others\":[$others]," + equipmentPanel.diagnosticOverCapacity(position) + "}"
    }

    /** Tests: the touch pager of the equipment tab's over-capacity boxes turns. */
    fun diagnosticTurnOverPage() = equipmentPanel.touchTurnOverPage()

    /** Tests: the buttons made again for the current layout (TouchMode.enabled) and drawn once, which places them. */
    fun diagnosticRelayout(g: Graphics) {
        updateButtons()
        equipmentPanel.diagnosticRelayout()
        draw(g)
    }

    // ---- Hyperspace crew manifest (CustomCrewManifest.cpp) ----

    private var hsCrewPage = 0
    private var hsDragging: LivingCrew? = null
    private var mouseX = 0
    private var mouseY = 0
    private val hsCrewBoxes = ArrayList<Pair<ConstPoint, Int>>() // box position and slot of the boxes drawn last

    /** The crew the manifest lists: those whose `noSlot` stat is off (CustomCrewManifest::Update). */
    private fun hsListedCrew(): List<LivingCrew> = ship.sys.playerCrew.filter { xyz.znix.xftl.hs.events.HsCrew.noSlot?.invoke(it) != true }

    /** CustomCrewManifest::GetEffectiveCrewLimit: a box for every crew member plus the free slots. */
    private fun hsCrewLimit(): Int {
        val limit = xyz.znix.xftl.hs.ship.HsShipState.crewLimit(ship)
        val count = ship.sys.playerCrew.size
        val used = xyz.znix.xftl.hs.ship.HsShipState.capacityUsed(ship, count)
        return if (used > limit) count - kotlin.math.ceil(used - limit).toInt() else count + limit - (used + 0.001f).toInt()
    }

    /** CustomCrewManifest::OnInit: eight boxes a page, three to a row, a short last row centred. */
    private fun hsCrewBoxPositions(limit: Int): List<ConstPoint> {
        val result = ArrayList<ConstPoint>()
        var boxX = 68
        var boxY = -45
        for (i in 0 until limit) {
            if (i % 8 == 0) { boxX = 68; boxY = -45 }
            if ((i % 8) % 3 == 0) { boxX = 68; boxY += 133 }
            if (i == limit - ((limit % 8) % 3)) {
                if (limit == i + 1) boxX += 170 else if (limit == i + 2) boxX += 86
            } else if (i % 8 == 6) boxX += 86
            result += ConstPoint(boxX, boxY)
            boxX += 170
        }
        return result
    }

    /**
     * The crew screen's over-capacity DropBox (the sell box widget sits 7 px inside the art, the art at the native
     * place 7 px left in this window's frame). CrewManifest's constructor (0x4e9f30) puts it at (-275, 100), left of
     * the window. On touch the window group is pulled left, so that spot is off the screen; the iPad game puts it
     * at (30, 484) instead, under the window's left half (the window art ends at 471).
     */
    private val crewDropBoxPos: ConstPoint
        get() = when {
            TouchMode.enabled -> ConstPoint(30, 484 + GLOW_WIDTH)
            PlatformSpecific.INSTANCE.isTouchUi -> ConstPoint(-60, 250)
            else -> ConstPoint(-275, CREW_DROP_BOX_Y + GLOW_WIDTH)
        }

    private fun drawCrewHs(g: Graphics) {
        noteFont.print(position.x + 18f, position.y + 55f, game.translator["rename_note"], Colour.white)

        val listed = hsListedCrew()
        val limit = hsCrewLimit()
        val positions = hsCrewBoxPositions(limit)
        val pages = xyz.znix.xftl.hs.render.HsUpgrades.pages(limit, 8)
        hsCrewPage = hsCrewPage.coerceIn(0, pages - 1)

        // CustomCrewManifest::OnRender: the over-capacity DropBox first (while the extra box holds someone), then the
        // crew boxes over it.
        val overCrew = listed.getOrNull(limit)
        if (overCrew != null && updatingButtons) {
            buttons += ShipEquipmentPanel.SellDropBox.create(
                game, ShipEquipmentPanel.SellDropBox.Type.TOO_MANY_CREW, crewDropBoxPos, { null }, { null }
            )
        }

        hsCrewBoxes.clear()
        for (i in hsCrewPage * 8 until minOf(limit, hsCrewPage * 8 + 8)) {
            drawCrewBox(g, listed, positions[i], i)
            hsCrewBoxes += Pair(positions[i], i)
        }

        // The crew past the limit (the first one) goes in its own box (CustomCrewManifest::OnInit overCrewBox): at x 146 of
        // the screen, right below the over-capacity DropBox (the manifest's y + the box's y + DropBox::GetHeight). The
        // touch layout has no room below its DropBox, so the box sits right of it.
        if (overCrew != null) {
            val overPos = if (TouchMode.enabled) TOUCH_EXTRA_CREW_BOX
            else ConstPoint(146 - position.x, CREW_DROP_BOX_Y + ShipEquipmentPanel.dropBoxHeight(game.translator, "overcapacity_crew_title", "overcapacity_crew_text", 0, ""))
            drawCrewBox(g, listed, overPos, limit, true)
            hsCrewBoxes += Pair(overPos, limit)
        }

        if (updatingButtons) {
            if (limit > 8) {
                buttons += PageCrewArrow(ConstPoint(508, 54), false) { hsCrewPage = (hsCrewPage + pages - 1) % pages; updateButtons() }
                buttons += PageCrewArrow(ConstPoint(543, 54), true) { hsCrewPage = (hsCrewPage + 1) % pages; updateButtons() }
            }
        }

        // The dragged crew member follows the mouse at 2x (CustomCrewManifest::OnRender).
        if (touchGet == null) hsDragging?.let { it.drawPortrait(mouseX - 34, mouseY - 34, false, 2f) }
    }

    /** `upgradeUI/Equipment/button_crew_arrow` (the right one mirrored). */
    private inner class PageCrewArrow(pos: ConstPoint, private val mirrored: Boolean, private val action: () -> Unit) :
        Button(game, pos, ConstPoint(32, 28)) {
        override fun draw(g: Graphics) {
            val image = game.getImg("img/upgradeUI/Equipment/button_crew_arrow_${if (hovered) "select2" else "on"}.png")
            if (mirrored) {
                g.pushTransform(); g.translate(pos.x + image.width.f, pos.y.f); g.scale(-1f, 1f); image.draw(0, 0); g.popTransform()
            } else image.draw(pos.x, pos.y)
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON) action()
        }
    }

    /** CustomCrewManifest::MouseUp: the box under the mouse takes the dragged crew member (swap, or to the end). */
    private fun hsCrewDrop(x: Int, y: Int) {
        val dragged = hsDragging ?: return
        hsDragging = null
        val listed = hsListedCrew()
        val target = hsCrewBoxes.firstOrNull { (pos, _) ->
            x >= position.x + pos.x && x < position.x + pos.x + 100 && y >= position.y + pos.y && y < position.y + pos.y + 133
        } ?: return
        val all = ship.sys.playerCrew
        val other = listed.getOrNull(target.second)
        if (other == null) {
            all.remove(dragged); all.add(dragged)
        } else if (other !== dragged) {
            java.util.Collections.swap(all, all.indexOf(dragged), all.indexOf(other))
        }
        updateButtons()
    }

    override fun mouseScroll(change: Int) {
        if (hsDef != null && tab == Tab.EQUIPMENT) { val p = scaleWindowPoint(mouseX, mouseY); equipmentPanel.mouseScroll(change, p.x, p.y); return }
        // CustomCrewManifest::OnScrollWheel
        if (hsDef == null || tab != Tab.CREW) return
        val pages = xyz.znix.xftl.hs.render.HsUpgrades.pages(hsCrewLimit(), 8)
        if (hsCrewLimit() > 8) {
            hsCrewPage = if (change < 0) (hsCrewPage + 1) % pages else (hsCrewPage + pages - 1) % pages
            updateButtons()
        }
    }

    private fun drawCrew(g: Graphics) {
        if (hsDef != null) {
            drawCrewHs(g)
            return
        }
        val crew = ship.sys.playerCrew
        if (TouchMode.enabled) hsCrewBoxes.clear()

        // CrewManifest::OnRender (0x004eaf80): font 10 at (25,55) from the native origin.
        noteFont.print(position.x + 18f, position.y + 55f, game.translator["rename_note"], Colour.white)

        // First row
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 0, 88 + 133 * 0), 0)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 1, 88 + 133 * 0), 1)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 2, 88 + 133 * 0), 2)

        // Second row
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 0, 88 + 133 * 1), 3)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 1, 88 + 133 * 1), 4)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 2, 88 + 133 * 1), 5)

        // Third row
        if (game.playerHasTooManyCrew()) {
            // Too many crew members, show a 3x3 grid
            drawCrewBox(g, crew, ConstPoint(68 + 170 * 0, 88 + 133 * 2), 6)
            drawCrewBox(g, crew, ConstPoint(68 + 170 * 1, 88 + 133 * 2), 7)
            drawCrewBox(g, crew, ConstPoint(68 + 170 * 2, 88 + 133 * 2), 8)
        } else {
            drawCrewBox(g, crew, ConstPoint(154, 88 + 133 * 2), 6)
            drawCrewBox(g, crew, ConstPoint(324, 88 + 133 * 2), 7)
        }

        if (game.playerHasTooManyCrew() && updatingButtons) {
            buttons += ShipEquipmentPanel.SellDropBox.create(
                game,
                ShipEquipmentPanel.SellDropBox.Type.TOO_MANY_CREW,
                crewDropBoxPos,
                { null },
                { null }
            )
        }
    }

    private fun drawCrewBox(g: Graphics, crewList: List<LivingCrew>, boxPos: ConstPoint, id: Int, over: Boolean = false) {
        val crew = crewList.getOrNull(id)
        // The drop looks these up; the Hyperspace manifest lists its own.
        if (TouchMode.enabled && hsDef == null) hsCrewBoxes += Pair(boxPos, id)
        if (updatingButtons) diagnosticCrewBoxes += DiagnosticCrewBox(id, boxPos, crew, over)

        if (crew == null) {
            val boxEmpty = game.getImg("img/upgradeUI/Equipment/box_crew_off.png")
            boxEmpty.draw(boxPos + position)
            return
        }

        val dismissButtonBox = game.getImg("img/customizeUI/box_crewcustom_on.png")
        val dismissButtonBoxHover = game.getImg("img/customizeUI/box_crewcustom_selected.png")

        val boxNormal = game.getImg("img/upgradeUI/Equipment/box_crew_on.png")
        val boxHover = game.getImg("img/upgradeUI/Equipment/box_crew_selected.png")

        // CrewManifest: the dismiss confirmation at the box + (-53, 86). On touch a box under the window (Hyperspace's
        // extra crew box) would put it below the screen, so it goes above the box there.
        if (crewToDismiss == crew) dismissConfirm?.let {
            val below = boxPos.y + 86
            val y = if (TouchMode.enabled && below + it.size.y > TOUCH_HUD_BOTTOM) boxPos.y - it.size.y else below
            it.position = position + ConstPoint(boxPos.x - 53, y)
        }

        if (!updatingButtons)
            return

        val mainButton = object : Button(game, boxPos, boxNormal.imageSize) {
            override val makesHoverNoise: Boolean get() = false

            // The finger picks the crew member up from its press, or renames on the lift (touchPressCrew).
            override val touchLift: Boolean get() = false

            var nameHover = false

            override fun draw(g: Graphics) {
                val box = if (hovered) boxHover else boxNormal
                box.draw(pos)

                val dismissBox = if (hovered) dismissButtonBoxHover else dismissButtonBox
                dismissBox.draw(pos.x.f, pos.y + 62f)

                if (renamingCrew == crew) {
                    // Draw the rename box
                    val width = 150
                    val height = 23
                    val boxX = pos.x + (size.x - width) / 2
                    val boxY = pos.y + 43
                    if (TouchMode.enabled) TouchMode.keyboardFocusY = boxY + height

                    g.colour = Colour.white
                    g.fillRect(boxX, boxY, width, height)
                    g.colour = Colour.black
                    g.fillRect(boxX + 1, boxY + 1, width - 2, height - 2)

                    val name = crew.info.name
                    val nameWidth = crewMessageFont.getWidth(name) + 2 + 2 // 2+2 for the gap and cursor
                    val nameX = boxX + (width - nameWidth) / 2
                    crewMessageFont.drawString(nameX.f, pos.y + 61f, name, Colour.white)

                    // Draw the cursor
                    cursorFlashTimer += game.renderingDeltaTime
                    val period = 0.5f
                    if (cursorFlashTimer > period)
                        cursorFlashTimer -= period

                    if (cursorFlashTimer < period / 2f) {
                        g.colour = Colour.yellow
                        g.fillRect(nameX + nameWidth - 2, boxY + 8, 2, 13)
                    }
                } else if (hsDragging !== crew) {
                    // Draw the name
                    // CrewEquipBox::RenderLabels (0x00542470): font 6 centred at (50,49).
                    val name = crew.info.shortName
                    val nameColour = when (nameHover) {
                        true -> Constants.CREW_RENAME_TEXT_COLOUR
                        false -> Colour.white
                    }
                    // (with ignoreLanguage for a Latin or Cyrillic name, NativeFonts.nameIgnoresLanguage)
                    val labelFont = if (xyz.znix.xftl.rendering.NativeFonts.nameIgnoresLanguage(name)) crewLabelFont.english else crewLabelFont
                    labelFont.printCenter(pos.x + 50f, pos.y + 49f, name, nameColour)
                }

                // EquipmentBox::OnRender (0x00540150) renders the crew icon at 2x from
                // (50,27); its 35px frame starts 17 source pixels up and left.
                if (hsDragging !== crew) crew.drawPortrait(pos.x + 16, pos.y - 7, false, 2f)

                // Draw the information about this crewmember while hovering over them
                if (hovered && hsDragging == null) {
                    infoPanel.drawCrew(g, crew.info)
                }
            }

            override fun update(x: Int, y: Int, blockHover: Boolean) {
                super.update(x, y, blockHover)

                nameHover = x in (pos.x..pos.x + 100) && y in (pos.y + 49..pos.y + 65)
            }

            override fun click(button: Int) {
                if (button != Input.MOUSE_LEFT_BUTTON)
                    return
                if (TouchMode.enabled) {
                    if (nameHover || mouseY < pos.y + 62) touchPressCrew(crew, nameHover)
                    return
                }
                if (!nameHover) {
                    // CustomCrewManifest::MouseClick: pressing a crew box (not its name or dismiss button) picks the crew
                    // member up to move or swap.
                    if (hsDef != null && mouseY < pos.y + 62) hsDragging = crew
                    return
                }

                renamingCrew = crew
            }
        }

        val dismissButton = object : Buttons.BasicButton(
            game, boxPos + ConstPoint(4, 70), ConstPoint(92, 12),
            game.translator["crewbox_dismiss"],
            2, dismissFont, 9, { confirmDismiss(crew) }
        ) {
            // The box being dismissed keeps its button highlighted under the popup.
            override fun draw(g: Graphics) {
                if (crewToDismiss != crew) {
                    super.draw(g)
                    return
                }
                g.colour = Constants.UI_BUTTON_HOVER
                Buttons.drawRounded(g, pos.x, pos.y, size.x, size.y, 2)
                val x = (size.x - dismissFont.getWidth(label)) / 2f
                dismissFont.drawString(pos.x + x, pos.y + 9f, label, Constants.JUMP_DISABLED_TEXT)
            }
        }

        buttons += mainButton
        buttons += dismissButton

        // Don't grey out the buttons corresponding to crew we're about to dismiss
        if (crewToDismiss == crew) {
            dismissHighlightButtons += mainButton
            dismissHighlightButtons += dismissButton
        }
    }

    private fun drawEquipment(g: Graphics) {
        equipmentPanel.draw(g)

        // Draw the information for the currently-hovered blueprint.
        drawEquipmentInfo(infoPanel, equipmentPanel)
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        // The dismiss popup intercepts clicks before everything else.
        dismissConfirm?.let {
            val p = scaleWindowPoint(x, y)
            it.mouseClick(button, p.x, p.y)
            return
        }

        // If we were renaming a crewmember, we're not any more
        renamingCrew = null

        // CustomUpgrades::MouseClick: a click on the rename button (or, without the button, on the name) starts the
        // input unless it is running; any other click stops it.
        if (hsDef != null && xyz.znix.xftl.hs.HS.cfg().renameShipAllowed) {
            renameJustStopped = renamingShip
            val p = scaleWindowPoint(x, y)
            val onName = tab == Tab.UPGRADES && !xyz.znix.xftl.hs.HS.cfg().renameShipButton &&
                p.x - position.x > 148 && p.x - position.x < 458 && p.y - position.y > 46 && p.y - position.y < 86
            if (onName && !renamingShip) startShipRename() else stopShipRename()
        }

        if (touchUpgradesShown && button == Input.MOUSE_LEFT_BUTTON) {
            touchFingerDown = true
            touchPressedGet = false
            if (touchGet?.press(x, y) == true) {
                touchPressedGet = true
                return
            }
            for (btn in ArrayList(buttons)) if (btn !is TouchUpgradeBox) btn.mouseDown(button, x, y)
            touchPickUpgrade(x, y)
            return
        }

        // Deliberately NO tap-to-arm here (user decision): upgrades are
        // single-click on touch too - the UNDO button is the safety net.
        super.mouseClick(button, x, y)

        if (tab == Tab.EQUIPMENT) {
            val p = scaleWindowPoint(x, y)
            equipmentPanel.mouseClick(button, p.x, p.y)
        }
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON) {
            touchFingerDown = false
            touchPressedGet = false
        }
        if (dismissConfirm != null) {
            return
        }

        super.mouseReleased(button, x, y)
        if (TouchMode.enabled && button == Input.MOUSE_LEFT_BUTTON) touchReleaseCrew()

        if (hsDragging != null) hsCrewDrop(x, y)

        if (tab == Tab.EQUIPMENT) {
            val p = scaleWindowPoint(x, y)
            equipmentPanel.mouseReleased(button, p.x, p.y)
        }
    }

    override fun updateUI(x: Int, y: Int) {
        // If the crew dismiss window is up, block all other interactions.
        dismissConfirm?.let {
            val p = scaleWindowPoint(x, y)
            it.updateUI(p.x, p.y)
            return
        }

        if (touchUpgradesShown) {
            mouseX = x
            mouseY = y
            touchUpdateUpgrades(x, y)
            return
        }

        super.updateUI(x, y)
        mouseX = x
        mouseY = y
        if (TouchMode.enabled) touchUpdateCrew(x, y)

        if (tab == Tab.EQUIPMENT) {
            val p = scaleWindowPoint(x, y)
            equipmentPanel.updateUI(p.x, p.y)
        }
    }

    override fun escapePressed() {
        // Escape under the dismiss popup cancels it and closes the window too.
        dismissConfirm?.escapePressed()
        if (!tutorialAcceptAllowed()) {
            ftl.browser.BrowserDebug.event("tutorial", "window.accept.rejected", "tab", tab.name)
            return
        }
        hsConfirm() // TabbedWindow::Close
        stopShipRename()
        commitUpgrades()
        close()
    }

    private fun tutorialAcceptAllowed(): Boolean =
        game.isRunningAutomatedTest || (game.mainGame.tutorialAdapter?.acceptShipWindow(game) ?: true)

    val pendingUpgradeActions: Set<ftl.browser.BrowserAchievementProgress.Action>
        get() = HashSet<ftl.browser.BrowserAchievementProgress.Action>().also { actions ->
            if (systemUpgradeUndos.any { it.value.isNotEmpty() }) actions += ftl.browser.BrowserAchievementProgress.Action.SYSTEM_UPGRADE
            if (systemUpgradeUndos.any { it.key is xyz.znix.xftl.systems.Weapons && it.value.isNotEmpty() })
                actions += ftl.browser.BrowserAchievementProgress.Action.WEAPON_UPGRADE
            // Upgrades confirm (0x546a30): the reactor box sets only the reactor_upgrade flag.
            if (reactorUpgradeUndo.isNotEmpty())
                actions += ftl.browser.BrowserAchievementProgress.Action.REACTOR_UPGRADE
        }

    private fun commitUpgrades() {
        for (action in pendingUpgradeActions) game.browserAchievements.record(action)
        systemUpgradeUndos.clear()
        reactorUpgradeUndo.clear()
    }

    override fun positionUpdated() {
        super.positionUpdated()

        infoPanel.position = position + if (TouchMode.enabled) ConstPoint(596, 50) else ConstPoint(size.x + 13, 74)
        equipmentPanel.position = position
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        if (dismissConfirm != null) return key != Input.KEY_ESCAPE
        if (renamingShip) {
            // CApp::OnTextInput / OnTextEvent / OnKeyDown hooks of CustomUpgrades.cpp: the input takes every key.
            var name = ship.displayName ?: ""
            when {
                key == Input.KEY_BACK -> name = name.dropLast(1)
                key == Input.KEY_ENTER || key == Input.KEY_ESCAPE -> { stopShipRename(); return true }
                c != 0.toChar() && !c.isISOControl() -> if (name.length < xyz.znix.xftl.hs.render.HsUpgrades.NAME_LENGTH) name += c
            }
            ship.displayName = name
            return true
        }
        val info = renamingCrew?.info ?: return false

        if (key == Input.KEY_BACK) {
            val endPos = max(0, info.name.length - 1)
            info.name = info.name.substring(0, endPos)
            return true
        }

        if (key == Input.KEY_ENTER) {
            renamingCrew = null
            return true
        }

        if (c != 0.toChar()) {
            // TextInput::OnTextInput: only the characters of the current language's set.
            if (info.name.length < 15 && xyz.znix.xftl.TextInputChars.accepts(c))
                info.name += c
            return true
        }

        return false
    }

    private fun undoAllSystems() {
        for (undos in systemUpgradeUndos.values) {
            for (undo in undos) {
                undo()
            }
        }
        for (undo in reactorUpgradeUndo) {
            undo()
        }
        systemUpgradeUndos.clear()
        reactorUpgradeUndo.clear()

        // The UNDO button plays undoUpgrades (single bars still play downgradeSystem).
        undoUpgradesSound.play()
    }

    enum class Tab(val textureName: String) {
        UPGRADES("upgrades"),
        CREW("crew"),
        EQUIPMENT("equipment"),
    }

    private inner class TabButton(val newTab: Tab, pos: ConstPoint, size: ConstPoint) : Button(game, pos, size) {
        val images =
            ButtonImageSet.select2(game, "img/upgradeUI/Equipment/tabButtons/${tab.textureName}_${newTab.textureName}")

        override val disabled: Boolean
            get() = (!game.isRunningAutomatedTest && game.mainGame.isTutorialRunning) || when (tab) {
                Tab.CREW -> game.playerHasTooManyCrew()
                else -> false
            }

        override fun draw(g: Graphics) {
            val image = when {
                disabled -> images.off
                hovered -> images.hover
                else -> images.normal
            }
            image.draw(pos)
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON || disabled)
                return

            hsConfirm() // the TabbedWindow::SetTab hook (priority 1000) fires TABBED_WINDOW_CONFIRM for the tab it leaves
            tab = newTab
            updateButtons()
        }
    }

    /** The background of a custom tab: `img/<windowPath>.png` (CustomTabbedWindow::ParseWindowNode), blank when it has none. */
    private fun hsCustomBackground(): Image {
        val path = xyz.znix.xftl.hs.render.HsTabs.tabs[customTab].windowPath
        return game.getImg("img/" + (path ?: "upgradeUI/Equipment/upgrades_main") + ".png")
    }

    /** A custom tab's window: nothing of its own but the UNDO button of a tab with hasUndo (CustomTabbedWindow::PopulateWindow). */
    private fun drawCustomTab(g: Graphics) {
        if (!xyz.znix.xftl.hs.render.HsTabs.tabs[customTab].hasUndo) return
        undoButtonImage.draw(position.x + 3f, position.y + 464f)
        if (updatingButtons) {
            buttons += object : Buttons.BasicButton(
                game, ConstPoint(26, 471), ConstPoint(97, 32), game.translator["button_undo"], 4, game.getFont("HL2", 3f), 25,
                {
                    // CommandGui::LButtonDown: TABBED_WINDOW_UNDO with the tab's name
                    if (xyz.znix.xftl.hs.HS.luaActive)
                        xyz.znix.xftl.hs.lua.LuaEvents.tabbedWindow(xyz.znix.xftl.hs.lua.LuaApi.EV_TABBED_WINDOW_UNDO, hsTabName(hsCurrentIndex))
                }
            ) {
                override val nativeFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)
            }
        }
    }

    /** TabbedWindow::OnRender's additions: the selected custom tab's backdrop and every tab's icon over its button. */
    private fun drawHsTabOverlays(g: Graphics) {
        val n = hsTabCount
        for (i in 0 until n) {
            val geo = hsTabGeometry(i)
            val isLast = i == n - 1
            if (i == hsCurrentIndex && i > 2) {
                val back = game.getImg("img/upgradeUI/Equipment/tabButtons/" + if (isLast) "select_button_end.png" else "select_button.png")
                back.draw((position.x + geo.imageX + if (isLast) -17 else 2).f, (position.y + geo.imageY).f)
            }
            val icon = game.getImg("img/upgradeUI/Equipment/tabButtons/icon_" + hsTabName(i) + ".png")
            icon.draw((position.x + geo.imageX + if (isLast) -10 else 0).f, (position.y + geo.imageY).f)
        }
    }

    /** Where tab [i]'s button image and hit box lie (window relative), as TabbedWindow, CustomTabbedWindow::InitialiseButton and SetTab leave them. */
    private class HsGeo(val imageX: Int, val imageY: Int, val hitX: Int, val hitY: Int, val hitW: Int, val hitH: Int)

    private fun hsTabGeometry(i: Int): HsGeo {
        val n = hsTabCount
        // native window-relative x is the port's + 7
        if (i < 3) {
            if (n > 3 && i == 2) return HsGeo(182 - 19 - 7, -GLOW_WIDTH, 197 - 5 - 7, 0, 70, 38)
            return when (i) {
                0 -> HsGeo(-GLOW_WIDTH, -GLOW_WIDTH, -GLOW_WIDTH, -GLOW_WIDTH, 109, 48)
                1 -> HsGeo(75, -GLOW_WIDTH, 75, -GLOW_WIDTH, 127, 48)
                else -> HsGeo(175, -GLOW_WIDTH, 175, -GLOW_WIDTH, 123, 48)
            }
        }
        val butX = xyz.znix.xftl.hs.render.HsTabs.tabs[i - 3].butX
        if (i == n - 1) {
            val last = (n - 1) * xyz.znix.xftl.hs.render.HsTabs.HITBOX_WIDTH
            return HsGeo(last + 20 + 8 - 7, -GLOW_WIDTH, last + 20 + 10 + 15 - 7, 0, 75, 38)
        }
        return HsGeo(butX - 7, -GLOW_WIDTH, butX + 25 - 7, 0, 75, 38)
    }

    /** A tab button with customTabs on: left_start / left / right / right_end images by the tab's place (TabbedWindow::SetTab hook). */
    private inner class HsTabButton(val index: Int) : Button(
        game, ConstPoint(hsTabGeometry(index).hitX, hsTabGeometry(index).hitY),
        ConstPoint(hsTabGeometry(index).hitW, hsTabGeometry(index).hitH)
    ) {
        private val geo = hsTabGeometry(index)
        private val images = run {
            val n = hsTabCount
            val cur = hsCurrentIndex
            val base = when {
                index == 0 -> "left_start"
                index == n - 1 -> "right_end"
                index > cur -> "right"
                else -> "left"
            }
            ButtonImageSet.select2(game, "img/upgradeUI/Equipment/tabButtons/$base")
        }

        override val disabled: Boolean
            get() = (!game.isRunningAutomatedTest && game.mainGame.isTutorialRunning) || (index == 1 && game.playerHasTooManyCrew())

        override fun draw(g: Graphics) {
            val image = when {
                disabled -> images.off
                hovered -> images.hover
                else -> images.normal
            }
            image.draw((pos.x - geo.hitX + geo.imageX).f, (pos.y - geo.hitY + geo.imageY).f)
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON || disabled) return
            hsSetTab(index)
        }
    }

    companion object {
        const val GLOW_WIDTH: Int = 7

        /** CrewManifest's over-capacity DropBox y (its constructor, 0x4e9f30): the art's top in this window's frame. */
        private const val CREW_DROP_BOX_Y = 100

        /**
         * Touch layout, Hyperspace: the crew member past the limit, right of the DropBox (whose art ends at x 295) and
         * level with its body, clear of the Accept button (x 429). Below the DropBox, as on the desktop, it would be off
         * the screen.
         */
        private val TOUCH_EXTRA_CREW_BOX = ConstPoint(310, 484 + GLOW_WIDTH)

        /** Touch layout: the HUD block's bottom (768 high) in this window's frame, whose origin is at its (50, 85). */
        private const val TOUCH_HUD_BOTTOM = 768 - 85

        /** Hyperspace reactor box: an unbought bar (255, 255, 100, 0.25). */
        private val HS_EMPTY_BAR = Colour(1f, 1f, 100 / 255f, 0.25f)

        /** CrewManifest's tint under the dismiss popup (0x004eaf80). */
        private const val MANIFEST_POPUP_TINT = 0.25f

        /** How far a finger moves on a crew box before the crew member comes up with it. */
        private const val TOUCH_DRAG_DIST = 9

        /** TabbedWindow::names of the three tabs the original has (CustomTabbedWindow's icons are named by them). */
        private val HS_BUILTIN_TABS = arrayOf("upgrades", "crew", "equipment")

        /**
         * Equipment::SetPosition (0x00539430; Hyperspace's CustomEquipment::SetPosition keeps it): the item InfoBox at the
         * equipment's x + 600, y - 25, 25 above the upgrades tab's, and an item box is 10 narrower than the levels box
         * (oracle overcap oc-hs 04 and info-button ib-mv-eq: top 134, 323 wide in plain Hyperspace and Multiverse). The
         * store's sell tab is the same Equipment. Desktop layout only.
         */
        /** For the checks (tests/window-places.mjs): where the levels box of a hovered system was last placed, x,y,width. */
        @JvmStatic
        var diagnosticSystemInfo = ""

        /** For the checks (tests/window-places.mjs): where the desktop item box was last placed, as x,y,width. */
        @JvmStatic
        var diagnosticEquipmentInfo = ""

        @JvmStatic
        fun drawEquipmentInfo(infoPanel: InfoPanel, equipment: ShipEquipmentPanel) {
            if (TouchMode.enabled || PlatformSpecific.INSTANCE.isTouchUi) {
                equipment.drawInfoPanel(infoPanel)
                return
            }
            val position = infoPanel.position
            val width = infoPanel.customWidth
            infoPanel.position = position + ConstPoint(0, -25)
            infoPanel.customWidth = 323
            diagnosticEquipmentInfo = "${infoPanel.position.x},${infoPanel.position.y},323"
            try {
                equipment.drawInfoPanel(infoPanel)
            } finally {
                infoPanel.position = position
                infoPanel.customWidth = width
            }
        }

        /** The other tabs under a TabbedWindow priority popup, as measured. */
        private const val TAB_POPUP_TINT = 0.35f

        private var boxWidthGame: InGameState? = null
        private var boxWidthLanguage: String? = null
        private var boxWidths = IntArray(0)

        /**
         * CommandGui::OnLanguageChange (0x00537cc0, run by CommandGui's constructor and on a language change): the
         * widest system levels box, InfoBox::CalcWidthForSystem (0x0055e490) for system ids 0-15 with each system
         * blueprint's maxPower. It places shipScreens and storeScreens ([shipScreensX], [storeScreensX]).
         */
        @JvmStatic
        fun systemBoxWidth(game: InGameState): Int = systemBoxWidths(game).max()

        /** InfoBox::CalcWidthForSystem for system ids 0-15, as CommandGui::OnLanguageChange measures them. */
        @JvmStatic
        fun systemBoxWidths(game: InGameState): IntArray {
            val language = Languages.current
            if (boxWidthGame === game && boxWidthLanguage == language) return boxWidths
            val widths = IntArray(16) { id -> measureSystemBox(game, id, null) }
            boxWidthGame = game; boxWidthLanguage = language; boxWidths = widths
            return widths
        }

        /** For the checks: each system's widest level text as width:text. */
        @JvmStatic
        fun systemBoxTexts(game: InGameState): List<String> =
            (0 until 16).map { id -> StringBuilder().also { measureSystemBox(game, id, it) }.toString() }

        private fun measureSystemBox(game: InGameState, id: Int, widest: StringBuilder?): Int {
            val left = game.getImg("img/upgradeUI/details_base_A.png").width
            val centre = game.getImg("img/upgradeUI/details_base_B.png").width
            val right = game.getImg("img/upgradeUI/details_base_C.png").width
            val font = game.getFont("JustinFont10")
            val name = xyz.znix.xftl.hs.systems.HsSystems.idToName(id)
            val blueprint = game.blueprintManager.getOrNull(name) as? SystemBlueprint
            // InfoBox::CalcCenterCountForSystem (0x0055dde0): the levels below maxPower (one more for sensors and
            // doors, at most eight), each GetLevelDescription measured in font 10 (0x00595f30), plus 143, less the
            // left art, in whole centre pieces, at least one
            val levels = minOf(8, (blueprint?.maxPower ?: 0) + if (id == 7 || id == 8) 1 else 0)
            var text = 0
            for (level in 0 until levels) {
                val description = xyz.znix.xftl.hs.lua.LuaShipEvents.levelDescription(id, level, false) {
                    (blueprint?.info ?: SystemBlueprint.BY_NAME[name])?.getLevelName(level, game.translator) ?: ""
                }
                val w = font.getExtentWidth(description)
                if (w > text) {
                    text = w
                    widest?.setLength(0); widest?.append(w)?.append(':')?.append(description)
                }
            }
            return max(1, (text + 143 - left + centre - 1) / centre) * centre + left + right
        }

        /**
         * CommandGui::OnLanguageChange: shipScreens at x 682 less [systemBoxWidth], at most 340 (CommandGui's
         * constructor, 0x00538710) and at least 150, so the upgrades info box at the window's x + 600 ends at 1282.
         * English vanilla and plain Hyperspace: 347 wide (sensors' "See enemy weapon charge", 178 px), x 335;
         * Multiverse: 357 (its mind control level 2 text, 189 px), x 325; German vanilla: x 305.
         */
        @JvmStatic
        fun shipScreensX(game: InGameState): Int = (682 - systemBoxWidth(game)).let { if (it < 150) 150 else minOf(it, 340) }

        /** CommandGui::OnLanguageChange: storeScreens as [shipScreensX], but at least 283. */
        @JvmStatic
        fun storeScreensX(game: InGameState): Int = (682 - systemBoxWidth(game)).let { if (it < 283) 283 else minOf(it, 340) }
    }
}
