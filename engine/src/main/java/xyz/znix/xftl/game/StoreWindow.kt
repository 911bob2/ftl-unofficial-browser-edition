package xyz.znix.xftl.game

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.HotkeyDelayedTooltip
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import kotlin.math.roundToInt

class StoreWindow(val game: InGameState, val ship: Ship, val store: StoreData, private val close: () -> Unit) :
    Window() {


    override val size = ConstPoint(587, 423)

    // Touch ergonomics: scale up 1.2x like the map windows. As with
    // ShipWindow, window + right-hand info panel is ~1120px scaled, so
    // pull the group left. Vertical: -50 because the close button
    // hangs below the declared rect (vanilla y 472 vs height 423),
    // raised to -70 after on-device feedback so it keeps a comfortable
    // margin from the screen bottom when scaled (same issue as
    // JumpWindow's CANCEL button).
    // Desktop: CommandGui's constructor (0x00538710) places storeScreens with the ship windows, and its
    // CommandGui::OnLanguageChange moves both by the widest system levels box (ShipWindow.storeScreensX): this window
    // at (335,85) in English vanilla, 10 further left under Multiverse.
    // The touch layout puts the window at (43,85) of the HUD block, with the info box to its right.
    override val windowCentreOffset = ConstPoint(
        if (TouchMode.enabled) -175 else if (PlatformSpecific.INSTANCE.isTouchUi) -266 else desktopX(),
        if (TouchMode.enabled) -87 else if (PlatformSpecific.INSTANCE.isTouchUi) -70 else -63
    )

    override val renderScale = if (PlatformSpecific.INSTANCE.isTouchUi) 1.2f else 1f

    override val desktopCentreOffset: IPoint get() = ConstPoint(desktopX(), -63)

    /** The desktop x offset: -11 puts the window at 335, at storeScreens (335 in vanilla). */
    private fun desktopX() = -11 - (335 - ShipWindow.storeScreensX(game))

    // Tap-to-arm: first tap highlights (and shows the item description),
    // second tap confirms (runtime-gated on isTouchUi). Selling already
    // happens via mouseReleased (drop), which the gate never sees.
    override val tapToArm = true

    private val buyImage = game.getImg("img/storeUI/store_buy_main.png")
    private val sellImage = game.getImg("img/storeUI/store_sell_main.png")
    private val closeButtonOutline = game.getImg("img/storeUI/store_close_base.png")

    private val buySellTabFont = game.getFont("HL2", 3f)
    private val sectionFont = game.getFont("HL2", 2f)
    private val repairFont = game.getFont("HL1", 2f)
    private val numberFont = game.getFont("num_font")
    private val augmentNameFont = game.getFont("c&cnew", 2f)
    private val crewNameFont = game.getFont("JustinFont8")
    private val labelFont63 = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 63)
    private val repairLabelFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 59)
    private val hullNumberFont = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 0)

    // Hyperspace: the custom store (StoreComplete) replaces the buy page; the ship's system limits (CustomShipDefinition).
    private val hsStore = store.hs
    private val hsDef = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.ship.HsShips.def(ship.type.name) else null
    private val systemLimit get() = hsDef?.systemLimit ?: 8
    private val subsystemLimit get() = hsDef?.subsystemLimit ?: 4

    private val buySound = game.sounds.getSample("buy")
    private val repairSound = game.sounds.getSample("repairShip")

    private val sellPanel = ShipEquipmentPanel(game, ship).apply {
        sellUI = true
        // Touch placement for the sell drop box: vanilla floats it left
        // of the window, which the touch group shift throws off-screen.
        // There's no spare VERTICAL room around the window (user), but
        // the info panel's column is free - park the box there, anchored
        // ~16px above the screen bottom. The info panel is empty while a
        // blueprint is being dragged, which is exactly when the box
        // matters; while hovering (not dragging) the description may
        // overlap the box's top briefly. Null on desktop = vanilla spot.
        sellBoxPlacer = { boxSize ->
            if (!PlatformSpecific.INSTANCE.isTouchUi) {
                null
            } else {
                val top = (CANVAS_H - size.y) / 2 + windowCentreOffset.y
                val maxBottomLocal = ((CANVAS_H - 16) - top) / renderScale
                ConstPoint(this@StoreWindow.size.x + 13, maxBottomLocal.roundToInt() - boxSize.y)
            }
        }
    }
    private val infoPanel = InfoPanel(game)

    // Touch layout: a finger on an item selects it, shown like a hovered item with its info box and BUY; only BUY spends
    // the scrap. The finger can slide to another item. While BUY is active, touches over the info column change nothing;
    // anywhere else, a touch away from every item clears the selection.
    private var touchSelected: BuyButton? = null
    private var touchFingerDown = false
    private var touchPressedBuy = false
    private val touchBuy: TouchGetBox? = if (TouchMode.enabled) TouchGetBox(
        game, game.translator["button_buy"], { touchSelected?.price }, { touchSelected?.disabled == false }
    ) { touchSelected?.touchBuy() } else null

    private val buyTabButton = SimpleButton(
        game, ConstPoint(0, 0), ConstPoint(170, 46), ConstPoint(0, 0),
        game.getImg("img/upgradeUI/Equipment/tabButtons/sell_buy_on.png"),
        game.getImg("img/upgradeUI/Equipment/tabButtons/sell_buy_select2.png"),
        null
    ) {
        sellTab = false
    }

    private val sellTabButton = SimpleButton(
        game, ConstPoint(163, 0), ConstPoint(179, 46), ConstPoint(0, 0),
        game.getImg("img/upgradeUI/Equipment/tabButtons/buy_sell_on.png"),
        game.getImg("img/upgradeUI/Equipment/tabButtons/buy_sell_select2.png"),
        null
    ) {
        sellTab = true
    }

    private val buySubTab1Button = SimpleButton.byRegion(
        game, ConstPoint(GLOW_WIDTH + 348 - 3, GLOW_WIDTH + 9 - 5),
        ConstPoint(5, 5), ConstPoint(111, 24),
        game.getImg("img/upgradeUI/Equipment/tabButtons/store_page2_on.png"),
        game.getImg("img/upgradeUI/Equipment/tabButtons/store_page2_select2.png"),
        HotkeyDelayedTooltip(game, "hotkey", null)
    ) {
        secondBuyTab = false
    }
    private val buySubTab2Button = SimpleButton.byRegion(
        game, ConstPoint(GLOW_WIDTH + 348 - 3, GLOW_WIDTH + 9 - 5),
        ConstPoint(119, 5), ConstPoint(111, 24),
        game.getImg("img/upgradeUI/Equipment/tabButtons/store_page1_on.png"),
        game.getImg("img/upgradeUI/Equipment/tabButtons/store_page1_select2.png"),
        HotkeyDelayedTooltip(game, "hotkey", null)
    ) {
        secondBuyTab = true
    }

    private val fuelButton = ResourceButton(ConstPoint(GLOW_WIDTH + 11, GLOW_WIDTH + 76), Resource.FUEL)
    private val missilesButton = ResourceButton(ConstPoint(GLOW_WIDTH + 11, GLOW_WIDTH + 126), Resource.MISSILES)
    private val dronesButton = ResourceButton(ConstPoint(GLOW_WIDTH + 11, GLOW_WIDTH + 175), Resource.DRONES)

    private val repairButtons = ArrayList<Button>()

    // To avoid having separate fields for all the different things that
    // can be bought, have lists of buttons for the top and bottom buy panels.
    // These will be populated during drawing which is a bit ugly, but means
    // we can keep all the section-related stuff together.
    private val buyButtonsUpper = ArrayList<BuyButton>()
    private val buyButtonsLower = ArrayList<BuyButton>()
    private var updatingBuyButtons = false

    // TabbedWindow::SetPosition (0x00518080): the store's Close TextButton sits 23,7
    // inside its art at (445,463), right-anchored with SetAutoWidth(margin 3).
    private val closeButton = run {
        val label = game.translator["button_close"]
        val width = maxOf(labelFont63.measure(label) + 6, 0x67)
        object : Buttons.BasicButton(
            game, position + ConstPoint(468 + 0x67 - width, 470),
            ConstPoint(width, 32), label,
            4, buySellTabFont, 25,
            this::escapePressed
        ) {
            override val nativeFont = labelFont63
        }
    }

    var sellTab: Boolean = false
        set(value) {
            field = value
            updateButtons()
        }

    // If there's two pages of stuff to buy, this is true if the second one is selected.
    var secondBuyTab: Boolean = false
        set(value) {
            field = value
            updateButtons()
        }

    init {
        // Tab/page navigation buttons stay single-click even when the
        // tap-to-arm gate is active - arming a page flipper is pure
        // friction (and would leave an item armed across a page flip).
        buyTabButton.skipTapToArm = true
        sellTabButton.skipTapToArm = true
        buySubTab1Button.skipTapToArm = true
        buySubTab2Button.skipTapToArm = true

        updateButtons()
    }

    private fun updateButtons() {
        buttons.clear()
        buttons.add(closeButton)

        if (sellTab) {
            buttons.add(buyTabButton)

            // Our position is centred at the 0,0 of the image, whereas
            // the sell panel excludes the glow part of the image.
            // Also note ShipEquipmentPanel is aligned properly with the ship
            // equipment menu, so we need to shift it around a little to make it
            // line up properly here.
            sellPanel.position = position + ConstPoint(GLOW_WIDTH, GLOW_WIDTH - 7)
        } else if (hsStore != null) {
            buttons.add(sellTabButton)
            addHsBuyButtons(hsStore)
        } else {
            buttons.add(sellTabButton)
            buttons.add(fuelButton)
            buttons.add(missilesButton)
            buttons.add(dronesButton)

            if (store.sections.size > 2) {
                buttons.add(if (secondBuyTab) buySubTab1Button else buySubTab2Button)
            }

            // Re-add the top and bottom buttons next draw
            updatingBuyButtons = true
        }

        // Re-position all the buttons
        positionUpdated()
    }

    override fun draw(g: Graphics) {
        // Under the confirm dialog the store is drawn at half brightness, the dialog itself in the middle of the screen.
        val tint = Graphics.tint
        if (hsConfirm != null) Graphics.tint = Colour(tint.r * .5f, tint.g * .5f, tint.b * .5f, tint.a)
        if (sellTab) {
            drawSell(g)
        } else if (hsStore != null) {
            drawBuyHs(g, hsStore)
        } else {
            drawBuy(g)
        }

        // See JumpWindow
        // Note the corners should have one step more roundness than the current
        // buttons, so the current ones look a bit ugly.
        if (hsConfirm == null) {
            closeButtonOutline.draw(closeButton.pos + ConstPoint(-23, -7))
            closeButton.draw(g)
        }

        buySellTabFont.drawString(
            position.x + GLOW_WIDTH + 47f,
            position.y + GLOW_WIDTH + 25f,
            game.translator["store_tab_buy"],
            Constants.JUMP_DISABLED_TEXT
        )

        buySellTabFont.drawString(
            position.x + GLOW_WIDTH + 47f + 161f,
            position.y + GLOW_WIDTH + 25f,
            game.translator["store_tab_sell"],
            Constants.JUMP_DISABLED_TEXT
        )

        // Draw the info for whatever the player is hovering
        if (touchBuy != null) {
            val selected = touchSelected
            if (selected != null && !sellTab) {
                infoPanel.touchBottom = 0
                selected.drawInfoPanel(g)
                touchBuy.drawBelow(g, infoPanel)
            } else {
                touchBuy.hide()
            }
        } else {
        val hoveredBuyButton = buttons.filterIsInstance<BuyButton>().firstOrNull { it.hovered }
        // The buy tab's item boxes (weapons, drones, augments, resources) are 323 wide, a system's 333 (1.6.14 ps-w3-van
        // s-h-weap1, s-h-fuel against s-h-sys1; oracle ps-w3-mv i-h-weap1 932..1254 under Multiverse).
        val width = infoPanel.customWidth
        if (hoveredBuyButton != null && hoveredBuyButton !is RepairButton && !TouchMode.enabled &&
            hoveredBuyButton.blueprint !is xyz.znix.xftl.systems.SystemBlueprint) infoPanel.customWidth = 323
        try { hoveredBuyButton?.drawInfoPanel(g) } finally { infoPanel.customWidth = width }
        }

        sellPanel.drawDrag(g)
        Graphics.tint = tint
        hsConfirm?.draw(g)
    }

    /** Hyperspace: the store's confirm dialog for a system purchase. */
    private var hsConfirm: ConfirmationWindow? = null

    private fun openHsConfirm(key: String, literal: String?, purchase: () -> Unit) {
        val window = ConfirmationWindow(SettingsContext(game), key, 300, accept = {
            hsConfirm = null
            purchase()
        }, cancel = { hsConfirm = null }, literalMessage = literal)
        window.centre(1280, 720)
        hsConfirm = window
    }

    /**
     * The confirm text SystemStoreBox::Activate (1.6.14 0x519250) shows before a vanilla store sells [system], or null to
     * sell at once: medbay over clone bay "confirm_buy_medbay", clone bay over medbay "confirm_buy_clonebay"; any main
     * system (not pilot, sensors, doors or battery) while the ship has seven (the power-needing count, 0x49bfd0), with
     * Advanced Edition on or artillery installed, "confirm_buy_last_system". Hyperspace's stores have their own rule.
     */
    internal fun vanillaSystemConfirm(system: xyz.znix.xftl.systems.SystemBlueprint): String? {
        if (xyz.znix.xftl.hs.HS.active) return null
        fun has(type: String) = ship.systems.any { it.codename == type }
        if (system.type == "medbay" && has("clonebay")) return "confirm_buy_medbay"
        if (system.type == "clonebay" && has("medbay")) return "confirm_buy_clonebay"
        if (system.info?.isSubSystem == true) return null
        if (ship.mainSystems.size == 7 && (game.isAdvancedEdition || has("artillery"))) return "confirm_buy_last_system"
        return null
    }

    /** Diagnostics: the confirm dialog's text key while one is open. */
    internal val diagnosticConfirmKey: String? get() = hsConfirm?.messageKey

    private fun drawBuy(g: Graphics) {
        // TabbedWindow::OnRender (0x00518660) draws the other tab before the window.
        sellTabButton.draw(g)
        buyImage.draw(position)

        // Draw the consumable items section (fuel, missiles, drones)
        sectionFont.drawString(
            position.x + GLOW_WIDTH + 11f,
            position.y + GLOW_WIDTH + 54f,
            game.translator["store_title_items"],
            Constants.JUMP_DISABLED_TEXT
        )

        fuelButton.draw(g)
        missilesButton.draw(g)
        dronesButton.draw(g)

        // Draw the hull repair section
        drawRepairSection(g)

        // If there's more than two sections, show the tab buttons to swap between them.
        if (store.sections.size > 2) {
            val swapButton = if (secondBuyTab) buySubTab1Button else buySubTab2Button
            swapButton.draw(g)

            sectionFont.drawString(
                position.x + GLOW_WIDTH + 374f,
                position.y + GLOW_WIDTH + 27f,
                game.translator["store_tab_page1"],
                Constants.JUMP_DISABLED_TEXT
            )
            sectionFont.drawString(
                position.x + GLOW_WIDTH + 374f + 104f,
                position.y + GLOW_WIDTH + 27f,
                game.translator["store_tab_page2"],
                Constants.JUMP_DISABLED_TEXT
            )
        }

        // Draw the two (top and bottom) main purchase sections
        val sectionOffset = if (secondBuyTab) 2 else 0
        drawBuySection(g, sectionOffset + 0, 59, buyButtonsUpper)
        drawBuySection(g, sectionOffset + 1, 268, buyButtonsLower)

        // We've just drawn both buy sections, so if we're supposed to
        // re-add the buy buttons, that's now done.
        updatingBuyButtons = false
    }

    private fun drawRepairSection(g: Graphics) {
        val repairSectionY = position.y + GLOW_WIDTH + 269
        sectionFont.drawString(
            position.x + GLOW_WIDTH + 11f,
            repairSectionY - 6f,
            game.translator["store_title_repair"],
            Constants.JUMP_DISABLED_TEXT
        )

        // Store::OnRender (0x004c0e30): the label in font 59 centred at x=73 from
        // y=419 (427 for a single line), then the hull in font 0 at (143,432).
        val currentHull = game.translator["store_current_hull"]
        repairLabelFont.printCenter(
            position.x + 73f, position.y + if ('\n' in currentHull) 419f else 427f,
            currentHull, Constants.SECTOR_CUTOUT_TEXT
        )
        hullNumberFont.print(position.x + 143f, position.y + 432f, ship.health.toString(), Constants.SECTOR_CUTOUT_TEXT)

        for (button in repairButtons) {
            button.draw(g)
        }

        // Add the fix buttons
        if (!updatingBuyButtons) {
            return
        }

        repairButtons.clear()

        val repairImg = ButtonImageSet.select2(game, "img/storeUI/store_repair")
        val buttonX = GLOW_WIDTH + 11
        val buttonBaseY = repairSectionY - position.y
        val repairPricePos = ConstPoint(130, 28)

        val repairPrice = StoreData.repairPrice(game.currentBeacon.sector.sectorNumber)

        repairButtons += object : RepairButton(ConstPoint(buttonX, buttonBaseY + 15), repairImg, repairPricePos) {
            override val nameKey: String get() = "repair_one_button"
            override val price: Int get() = repairPrice

            override fun buy() {
                ship.health++
            }
        }

        repairButtons += object : RepairButton(ConstPoint(buttonX, buttonBaseY + 70), repairImg, repairPricePos) {
            override val nameKey: String get() = "repair_all_button"
            override val price: Int get() = (ship.maxHealth - ship.health) * repairPrice

            override fun buy() {
                ship.health = ship.maxHealth
            }
        }

        for (button in repairButtons) {
            buttons += button
            button.windowOffset = position
        }
    }

    private fun drawBuySection(g: Graphics, sectionId: Int, y: Int, buyButtons: ArrayList<BuyButton>) {
        val section = store.sections.getOrNull(sectionId) ?: return

        val pos = ConstPoint(GLOW_WIDTH + 191, GLOW_WIDTH + y)

        sectionFont.drawString(
            position.x + pos.x + 7f,
            position.y + pos.y - 5f,
            game.translator[section.localisationKey],
            Constants.JUMP_DISABLED_TEXT
        )

        if (updatingBuyButtons)
            buyButtons.clear()

        when (section) {
            StoreData.Section.AUGMENTS -> drawBuyAugments(pos, buyButtons)
            StoreData.Section.CREW -> drawBuyCrew(pos, buyButtons)
            StoreData.Section.DRONES -> drawBuyDrones(pos, buyButtons)
            StoreData.Section.SYSTEMS -> drawBuySystems(pos, buyButtons)
            StoreData.Section.WEAPONS -> drawBuyWeapons(pos, buyButtons)
        }

        for (button in buyButtons) {
            if (updatingBuyButtons) {
                buttons.add(button)

                // Be sure to set the window position before drawing the button,
                // to avoid it flickering in the wrong position for the first
                // frame it's drawn.
                button.windowOffset = position
            }

            button.draw(g)
        }
    }

    private fun drawBuySystems(pos: IPoint, buyButtons: ArrayList<BuyButton>) {
        // We only need to add buy buttons, no custom drawing.
        if (!updatingBuyButtons)
            return

        for (i in 0 until 3) {
            val system = store.systems.getOrNull(i)
            val isSubSystem = system?.info?.isSubSystem == true

            val images = when {
                // The disabled image still has the cutout for the system icon, so
                // use the sold-out weapon image instead.
                system == null -> ButtonImageSet.static(game, "img/storeUI/store_weapons_off.png")

                isSubSystem -> ButtonImageSet.select2(game, "img/storeUI/store_subsystems")
                else -> ButtonImageSet.select2(game, "img/storeUI/store_systems")
            }

            // AddSystem (0x4a8370) has no room argument: it uses the ship blueprint's template for the system,
            // and a template listed twice keeps the last element (the std::map overwrite in 0x60c7a0).
            val systemSlot = ship.systemSlots.lastOrNull { it.system == system }

            // Drone control comes with a drone (SystemStoreBox 0x51ce20): half its price is added.
            val freeDrone = store.freeDrone(game, i)
            val systemPrice = if (system == null) 0 else system.cost + (freeDrone?.cost ?: 0) / 2
            val buttonPos = pos + ConstPoint(5, 17 + i * 53)
            // StoreBox::OnRender (0x0051f4b0): the price in font 0 from (345,14).
            buyButtons.add(object : BuyButton(buttonPos, images, ConstPoint(345, 26)) {
                override val blueprint: Blueprint? get() = system

                override val price: Int get() = systemPrice

                override val customDisabled: Boolean
                    get() {
                        // Stop the user from buying a system their ship doesn't support
                        if (systemSlot == null)
                            return true

                        // Hyperspace SystemStoreBox::CanHold: CanFitSystem/CanFitSubsystem(id), where a system that
                        // replaces an exclusive one always fits.
                        if (xyz.znix.xftl.hs.HS.active)
                            return systemSlot.isInstalled || !xyz.znix.xftl.hs.systems.HsSystemInstall.canFit(ship, systemSlot)

                        // Stop the user from installing more than eight systems, unless
                        // this replaces an existing system (clonebay/medbay).
                        // Hyperspace: the ship's systemLimit / subsystemLimit (CanFitSystem / CanFitSubsystem hooks).
                        val numSystems = ship.mainSystems.size
                        if (numSystems >= systemLimit && systemSlot.room.system == null && isSubSystem != true)
                            return true

                        // ShipManager::CanFitSystem (0x49b770) / 0x49b840: at most four subsystems.
                        if (isSubSystem && ship.systems.count { it.blueprint.info?.isSubSystem == true } >= subsystemLimit)
                            return true

                        // Stop the user from installing the same system twice
                        return systemSlot.isInstalled
                    }

                // SystemStoreBox::Activate (1.6.14 0x519250): with the scrap to pay, a medbay bought over a clone bay
                // (or the reverse) and a main system that takes the eighth slot (seven main systems, with Advanced
                // Edition on or artillery installed) ask first; yes buys it (SystemStoreBox::Confirm).
                override fun click(button: Int) {
                    val key = system?.let { vanillaSystemConfirm(it) }
                    if (key != null && button == Input.MOUSE_LEFT_BUTTON && !disabled && ship.scrap >= price) {
                        openHsConfirm(key, null) { super.click(button) }
                        return
                    }
                    super.click(button)
                }

                override fun buy() {
                    store.systems[i] = null
                    updateButtons() // Make this button show as sold out

                    // Hyperspace: the AddSystem hook replaces an exclusive system and carries its level.
                    if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.systems.HsSystemInstall.install(ship, systemSlot!!)
                    else systemSlot!!.room.setSystem(systemSlot)

                    // AddSystem (0x4a8370): the new system starts powered towards its starting target, and new
                    // shields are powered and charged at once.
                    systemSlot.room.system?.let { ship.browserPowerNewSystem(it) }

                    // SystemStoreBox::Confirm (0x51e910): drone control adds its drone (Equipment::AddDrone).
                    if (freeDrone != null)
                        ship.addBlueprint(freeDrone, false)
                }

                override fun drawInfoPanel(g: Graphics) {
                    if (system == null)
                        return

                    infoPanel.drawDescriptionBoxSystem(system)
                    infoPanel.drawSystemPowerBox(g, system, system.startPower, 0)
                }
            })
        }
    }

    private fun drawBuyCrew(pos: IPoint, buttons: ArrayList<BuyButton>) {
        // We only need to add buy buttons, no custom drawing.
        if (!updatingBuyButtons)
            return

        val images = ButtonImageSet.select2(game, "img/storeUI/store_buy_crew")

        for (index in 0 until 3) {
            val crew = store.crew.getOrNull(index)

            val buttonPos = pos + ConstPoint(19 + index * 125, 46)
            buttons += object : BuyButton(buttonPos, images, ConstPoint(33, 89)) {
                override val blueprint: Blueprint? get() = crew?.race
                override val price: Int get() = crew!!.race.cost

                // Hyperspace: CrewStoreBox::CanHold checks ShipManager::CanFitCrew for this race.
                override val customDisabled: Boolean = if (xyz.znix.xftl.hs.HS.active && crew != null)
                    !xyz.znix.xftl.hs.ship.HsShipState.canFitCrew(ship, game.playerCrew.size, crew.race.name) else game.isPlayerCrewFull

                override fun buy() {
                    store.crew[index] = null
                    ship.addCrewMember(crew!!, false)
                    game.gameMap.noteBlueprintCrew(game, crew!!)
                    updateButtons()
                }

                override fun draw(g: Graphics) {
                    val image = when {
                        disabled -> image.off
                        hovered -> image.hover
                        else -> image.normal
                    }
                    // 9px of padding
                    image.draw(this.pos.x - 9, this.pos.y - 9)

                    if (crew == null) {
                        return
                    }

                    crewNameFont.drawStringCentred(
                        this.pos.x + 2f, this.pos.y + 61f, 96f,
                        crew.name,
                        textColour
                    )

                    // Measured against the original's CrewStoreBox sprites.
                    val yOffset = when (crew.race.name) {
                        "rock" -> -6
                        "mantis", "crystal" -> -10
                        else -> -5
                    }

                    val scale = 2
                    val portraitX = this.pos.x + (96 - 35 * scale) / 2 + 3
                    crew.drawPortrait(portraitX, this.pos.y + yOffset, scale.f)

                    numberFont.drawString(
                        this.pos.x + priceOffset.x.f,
                        this.pos.y + priceOffset.y.f,
                        price.toString(),
                        textColour
                    )
                }

                override fun drawInfoPanel(g: Graphics) {
                    if (crew == null)
                        return

                    infoPanel.drawCrew(g, crew)
                }
            }
        }
    }

    @Suppress("DuplicatedCode") // Duplicated with BuyDrones
    private fun drawBuyWeapons(pos: ConstPoint, buyButtons: ArrayList<BuyButton>) {
        // We only need to add buy buttons, no custom drawing.
        if (!updatingBuyButtons)
            return

        val images = ButtonImageSet.select2(game, "img/storeUI/store_buy_weapons")

        for (i in 0 until 3) {
            val weapon = store.weapons.getOrNull(i)

            val buttonPos = pos + ConstPoint(10 + i * 125, 37)
            buyButtons.add(object : BuyButton(buttonPos, images, ConstPoint(42, 98)) {
                override val blueprint: Blueprint? get() = weapon
                override val price: Int get() = weapon?.cost ?: 0

                // WeaponStoreBox::CanHold (0x518ef0 -> 0x53d2d0): an empty weapon box (only with a
                // weapons system) or an empty cargo box; the overflow box (slot 4) never counts.
                override val customDisabled: Boolean
                    get() {
                        if (ship.weapons != null) {
                            for (slot in 0 until ship.weaponSlots!!) {
                                if (ship.hardpoints[slot].weapon == null)
                                    return false
                            }
                        }

                        return ship.cargoFull() && !xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)
                    }

                override fun buy() {
                    store.weapons[i] = null
                    updateButtons() // Make this button show as sold out

                    if (!ship.addBlueprint(weapon!!, false))
                        error("Couldn't find space to place purchased weapon!")
                }

                override fun drawInfoPanel(g: Graphics) {
                    if (weapon == null)
                        return

                    infoPanel.drawWeapon(weapon)
                }
            })
        }
    }

    @Suppress("DuplicatedCode") // Duplicated with BuyWeapons
    private fun drawBuyDrones(pos: ConstPoint, buttons: ArrayList<BuyButton>) {
        // We only need to add buy buttons, no custom drawing.
        if (!updatingBuyButtons)
            return

        val images = ButtonImageSet.select2(game, "img/storeUI/store_buy_drones")

        for (i in 0 until 3) {
            val drone = store.drones.getOrNull(i)

            val buttonPos = pos + ConstPoint(10 + i * 125, 37)
            buttons.add(object : BuyButton(buttonPos, images, ConstPoint(42, 98)) {
                override val blueprint: Blueprint? get() = drone
                override val price: Int get() = drone?.cost ?: 0

                // DroneStoreBox::CanHold (0x518e10 -> 0x53d470): an empty drone box or an empty cargo box (not the overflow box).
                override val customDisabled: Boolean
                    get() {
                        val droneSystem = ship.drones
                        if (droneSystem != null && droneSystem.drones.any { it == null })
                            return false

                        return ship.cargoFull() && !xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)
                    }

                override fun buy() {
                    store.drones[i] = null
                    updateButtons() // Make this button show as sold out

                    if (!ship.addBlueprint(drone!!, false))
                        error("Couldn't find space to place purchased drone!")
                }

                override fun drawInfoPanel(g: Graphics) {
                    if (drone == null)
                        return

                    infoPanel.drawDrone(drone)
                }
            })
        }
    }

    private fun drawBuyAugments(pos: ConstPoint, buyButtons: ArrayList<BuyButton>) {
        // We only need to add buy buttons, no custom drawing.
        if (!updatingBuyButtons)
            return

        val images = ButtonImageSet.select2(game, "img/storeUI/store_weapons")

        for (i in 0 until 3) {
            val augment = store.augments.getOrNull(i)

            val buttonPos = pos + ConstPoint(5, 17 + i * 53)
            buyButtons.add(object : BuyButton(buttonPos, images, ConstPoint(345, 27)) {
                override val blueprint: Blueprint? get() = augment

                override val price: Int get() = augment?.cost ?: 0

                override val customDisabled: Boolean
                    get() {
                        // Don't let the player buy multiple of a non-stackable augment.
                        if (augment?.stackable == false) {
                            if (ship.augments.contains(augment))
                                return true
                        }

                        // Check if the user's augments are full
                        return ship.augments.size >= ship.augmentSlotCount
                    }

                override fun buy() {
                    store.augments[i] = null
                    updateButtons() // Make this button show as sold out

                    if (!ship.addBlueprint(augment!!, false))
                        error("Couldn't find space to place purchased augment!")
                }

                override fun draw(g: Graphics) {
                    // There's no one way to draw augments, here for example they're just text.
                    // Thus we have to override it because BlueprintButton doesn't know how to.

                    val image = when {
                        disabled -> image.off
                        hovered -> image.hover
                        else -> image.normal
                    }
                    image.draw(this.pos)

                    if (empty)
                        return

                    val name = augment!!.translateTitle(game)
                    augmentNameFont.drawString(
                        this.pos.x + 48f,
                        this.pos.y + 26f,
                        name,
                        textColour
                    )

                    // StoreBox::OnRender (1.6.14 0x51f4b0) draws the price whenever the box holds an item, also while
                    // the ship's augments are full or it has this non-stackable one.
                    numberFont.drawString(
                        this.pos.x + priceOffset.x.f,
                        this.pos.y + priceOffset.y.f,
                        price.toString(),
                        textColour
                    )
                }

                override fun drawInfoPanel(g: Graphics) {
                    if (augment == null)
                        return

                    infoPanel.drawAugment(augment)
                }
            })
        }
    }

    // ---- Hyperspace custom store buy page (StoreComplete: SetPositions, OnRender, MouseClick, MouseMove, OnLoop) ----
    // Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomStore.cpp and CustomStoreBox.cpp.

    private val hsBuyImage by lazy { game.getImg("img/storeUI/store_buy_main_custom.png") }
    private val hsLimitImage by lazy { game.getImg("img/storeUI/store_limit_indicator.png") }
    private val font52 = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 52)
    private val font24 = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 24)
    private val font14 = xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, 14)
    private val hsLimitFont by lazy { xyz.znix.xftl.rendering.NativeFonts.font(game::getFont, xyz.znix.xftl.hs.ship.HsStores.limitFontSize) }

    private fun addHsBuyButtons(hs: xyz.znix.xftl.hs.ship.HsStoreState) {
        // ItemStoreBoxes at (18, 83 + {0, 50, 99}), the repair boxes at (18, 291) and (18, 346).
        val offsets = intArrayOf(0, 50, 99)
        for ((i, r) in hs.resources.withIndex()) {
            if (i >= offsets.size) break
            buttons += HsResourceButton(ConstPoint(18, 83 + offsets[i]), r)
        }
        hs.repairPrice?.let { price ->
            val repairImg = ButtonImageSet.select2(game, "img/storeUI/store_repair")
            val repairPricePos = ConstPoint(130, 28)
            buttons += object : RepairButton(ConstPoint(18, 291), repairImg, repairPricePos) {
                override val nameKey: String get() = "repair_one_button"
                override val price: Int get() = price
                override fun buy() { ship.health++ }
            }
            buttons += object : RepairButton(ConstPoint(18, 346), repairImg, repairPricePos) {
                override val nameKey: String get() = "repair_all_button"
                override val price: Int get() = (ship.maxHealth - ship.health) * price
                override fun buy() { ship.health = ship.maxHealth }
            }
        }
        // Page arrows (storeUI/button_store_arrow at (451,12) and, mirrored, (547,12)): active with more than one page.
        buttons += HsArrowButton(ConstPoint(451, 12), false, hs) { hs.previousPage(); updateButtons() }
        buttons += HsArrowButton(ConstPoint(547, 12), true, hs) { hs.nextPage(); updateButtons() }

        val page = hs.pages.getOrNull(hs.currentPage)
        if (page != null) {
            for ((secIndex, sec) in page.sections.withIndex()) {
                val boxes = sec.storeBoxes.getOrNull(sec.currentSection) ?: continue
                for ((boxIndex, box) in boxes.withIndex()) {
                    val equipment = sec.category != 2 && sec.category != 4
                    val pos = if (equipment) ConstPoint(208 + boxIndex * 125, 103 + secIndex * 209)
                    else ConstPoint(203, 83 + secIndex * 209 + boxIndex * 53)
                    buttons += HsBoxButton(pos, box, hs)
                }
            }
        }
        for (b in buttons) b.windowOffset = position
    }

    private fun drawBuyHs(g: Graphics, hs: xyz.znix.xftl.hs.ship.HsStoreState) {
        sellTabButton.draw(g)
        hsBuyImage.draw(position)

        sectionFont.drawString(position.x + GLOW_WIDTH + 11f, position.y + GLOW_WIDTH + 54f, game.translator["store_title_items"], Constants.JUMP_DISABLED_TEXT)
        sectionFont.drawString(position.x + GLOW_WIDTH + 11f, position.y + GLOW_WIDTH + 263f, game.translator["store_title_repair"], Constants.JUMP_DISABLED_TEXT)
        val currentHull = game.translator["store_current_hull"]
        repairLabelFont.printCenter(position.x + 73f, position.y + if ('\n' in currentHull) 419f else 427f, currentHull, Constants.SECTOR_CUTOUT_TEXT)

        // "%d/%d" (just the page past nine pages) centred at (515,11) in font 63.
        val pageText = when {
            hs.pages.size > 9 -> (hs.currentPage + 1).toString()
            hs.pages.isEmpty() -> "1/1"
            else -> "${hs.currentPage + 1}/${hs.pages.size}"
        }
        labelFont63.printCenter(position.x + 515f, position.y + 11f, pageText, Constants.JUMP_DISABLED_TEXT)

        val unavailable = game.translator["store_unavailable"]
        val page = hs.pages.getOrNull(hs.currentPage)
        if (page != null) {
            for ((i, sec) in page.sections.withIndex()) {
                val title = sec.customTitle.ifEmpty { game.translator[sec.headingKey] }
                sectionFont.drawString(position.x + 206f, position.y + 61f + i * 209, title, Constants.JUMP_DISABLED_TEXT)
            }
            if (page.sections.size == 1)
                font52.printCenter(position.x + 394f, position.y + 358f, unavailable, Constants.SECTOR_CUTOUT_TEXT)
        } else {
            font52.printCenter(position.x + 394f, position.y + 148f, unavailable, Constants.SECTOR_CUTOUT_TEXT)
            font52.printCenter(position.x + 394f, position.y + 358f, unavailable, Constants.SECTOR_CUTOUT_TEXT)
        }
        if (hs.resources.isEmpty())
            font52.printCenter(position.x + 102f, position.y + 148f, unavailable, Constants.SECTOR_CUTOUT_TEXT)
        if (hs.repairPrice == null)
            font52.printCenter(position.x + 102f, position.y + 332f, unavailable, Constants.SECTOR_CUTOUT_TEXT)

        for (b in buttons) if (b !== sellTabButton && b !== closeButton) b.draw(g)

        hullNumberFont.print(position.x + 143f, position.y + 432f, ship.health.toString(), Constants.SECTOR_CUTOUT_TEXT)

        if (hs.itemPurchaseLimit != -1) {
            hsLimitImage.draw(position.x + 7 - hsLimitImage.width, position.y + 26)
            // StoreComplete::MouseMove: an instant tooltip over the indicator, when the text exists.
            val mx = hsMouse.x; val my = hsMouse.y
            if (position.x + 10 - hsLimitImage.width < mx && mx < position.x + 7 && position.y + 38 < my &&
                my < position.y + 12 + hsLimitImage.height && !game.translator["tooltip_purchase_limit"].startsWith("Could not find:"))
                g.tooltip = hsLimitTooltip
            hsLimitFont.printCenter(
                position.x + xyz.znix.xftl.hs.ship.HsStores.limitX.f, position.y + xyz.znix.xftl.hs.ship.HsStores.limitY.f,
                (hs.itemPurchaseLimit - hs.itemsPurchased).toString(), Constants.SECTOR_CUTOUT_TEXT
            )
        }
    }

    private inner class HsArrowButton(
        pos: IPoint, val mirror: Boolean, val hs: xyz.znix.xftl.hs.ship.HsStoreState, val action: () -> Unit
    ) : Button(game, pos, ConstPoint(game.getImg("img/storeUI/button_store_arrow_on.png").width, game.getImg("img/storeUI/button_store_arrow_on.png").height)) {
        private val images = ButtonImageSet.select2(game, "img/storeUI/button_store_arrow")
        override val disabled: Boolean get() = hs.pages.size <= 1
        override fun draw(g: Graphics) {
            val img = when { disabled -> images.off; hovered -> images.hover; else -> images.normal }
            if (!mirror) { img.draw(pos); return }
            g.pushTransform(); g.translate(pos.x + img.width.f, pos.y.f); g.scale(-1f, 1f); img.draw(0, 0); g.popTransform()
        }
        override fun click(button: Int) { if (button == Input.MOUSE_LEFT_BUTTON) action() }
    }

    private inner class HsResourceButton(pos: IPoint, val box: xyz.znix.xftl.hs.ship.HsStores.ResourceBox) : BuyButton(
        pos, ButtonImageSet.select2(game, "img/storeUI/store_items_${resourceTextureName(box.resource)}"), ConstPoint(169, 40)
    ) {
        override val blueprint = box.resource.getBlueprint(game)!!
        override val disabled: Boolean get() = box.count <= 0
        override val touchSelectable: Boolean get() = box.count > 0
        override val price: Int get() = box.cost

        override fun draw(g: Graphics) {
            val img = when { box.count <= 0 -> image.off; hovered -> image.hover; else -> image.normal }
            img.draw(pos)
            if (box.count <= 0) return
            val textColour = if (hovered) Constants.STORE_BUY_HOVER else Constants.SECTOR_CUTOUT_TEXT
            numberFont.drawString(pos.x + 130f, pos.y + 26f, price.toString(), textColour)
            numberFont.drawString(pos.x + 70f, pos.y + 26f, box.count.toString(), textColour)
        }

        override fun buy() {
            if (box.count <= 0) return
            val resourceSet = ResourceSet()
            resourceSet[box.resource] = 1
            game.givePlayerResources(resourceSet)
            box.count--
        }

        override fun drawInfoPanel(g: Graphics) {
            if (box.count > 0) infoPanel.drawItem(blueprint)
        }
    }

    /** CustomStoreBox: an item of a custom store section (prices and mystery items are per box). */
    private inner class HsBoxButton(pos: IPoint, val box: xyz.znix.xftl.hs.ship.HsStores.Box, val hs: xyz.znix.xftl.hs.ship.HsStoreState) : BuyButton(
        if (box.category == 3) pos + ConstPoint(9, 9) else pos,
        when (box.category) {
            0 -> ButtonImageSet.select2(game, "img/storeUI/store_buy_weapons")
            1 -> ButtonImageSet.select2(game, "img/storeUI/store_buy_drones")
            3 -> ButtonImageSet.select2(game, "img/storeUI/store_buy_crew")
            4 -> if ((box.blueprint as? xyz.znix.xftl.systems.SystemBlueprint)?.info?.isSubSystem == true)
                ButtonImageSet.select2(game, "img/storeUI/store_subsystems") else ButtonImageSet.select2(game, "img/storeUI/store_systems")
            else -> ButtonImageSet.select2(game, "img/storeUI/store_weapons")
        },
        when (box.category) { 0, 1 -> ConstPoint(42, 98); 3 -> ConstPoint(33, 89); 4 -> ConstPoint(345, 26); else -> ConstPoint(345, 27) }
    ) {
        override val blueprint: Blueprint? get() = box.blueprint
        override val empty: Boolean get() = box.count <= 0 || box.blueprint == null
        override val price: Int get() = box.cost

        private val systemSlot
            get() = (box.blueprint as? xyz.znix.xftl.systems.SystemBlueprint)?.let { s -> ship.systemSlots.lastOrNull { it.system == s } }

        override val customDisabled: Boolean
            get() {
                // StoreComplete::OnLoop: every section box is inactive once the purchase limit is reached.
                if (hs.limitReached) return true
                when (box.category) {
                    0 -> {
                        if (ship.weapons != null) for (slot in 0 until ship.weaponSlots!!) if (ship.hardpoints[slot].weapon == null) return false
                        return ship.cargoFull() && !xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)
                    }
                    1 -> {
                        val droneSystem = ship.drones
                        if (droneSystem != null && droneSystem.drones.any { it == null }) return false
                        return ship.cargoFull() && !xyz.znix.xftl.hs.ship.HsEquipment.multipleOver(ship)
                    }
                    2 -> {
                        val augment = box.blueprint as xyz.znix.xftl.augments.AugmentBlueprint
                        if (!augment.stackable && ship.augments.contains(augment)) return true
                        return ship.augments.size >= ship.augmentSlotCount
                    }
                    3 -> return !xyz.znix.xftl.hs.ship.HsShipState.canFitCrew(ship, game.playerCrew.size, box.blueprint!!.name)
                    else -> {
                        val slot = systemSlot ?: return true
                        // CanFitSystem/CanFitSubsystem(id) (CustomSystems.cpp): exclusive replacements always fit.
                        return slot.isInstalled || !xyz.znix.xftl.hs.systems.HsSystemInstall.canFit(ship, slot)
                    }
                }
            }

        // SystemStoreBox::Activate hook: with the scrap to pay, a system that replaces one, or one that takes the
        // ship's last (sub)system slot, asks first; yes buys it (StoreComplete::MouseClick + confirmDialog).
        override fun click(button: Int) {
            if (box.category == 4 && button == Input.MOUSE_LEFT_BUTTON && !disabled && ship.scrap >= price) {
                val text = hsSystemConfirm()
                if (text != null) {
                    openHsConfirm(text.first, text.second) { super.click(button) }
                    return
                }
            }
            super.click(button)
        }

        /** The confirm text (key, literal or null) the HS hook picks for this system, or null to buy at once. */
        private fun hsSystemConfirm(): Pair<String, String?>? {
            val bought = box.blueprint as? xyz.znix.xftl.systems.SystemBlueprint ?: return null
            val isSub = bought.info?.isSubSystem == true
            // ShipManager::SystemWillReplace (systems' HsSystemInstall). Medbay/clonebay replacing each other keep the
            // original's texts (the hook calls it); any other replacement names both systems.
            val replaced = xyz.znix.xftl.hs.systems.HsSystemInstall.willReplace(ship, bought.type)
            if (replaced != null) {
                if (bought.type == "medbay" && replaced.blueprint.type == "clonebay") return Pair("confirm_buy_medbay", null)
                if (bought.type == "clonebay" && replaced.blueprint.type == "medbay") return Pair("confirm_buy_clonebay", null)
                val text = game.translator["confirm_buy_custom"].replace("\\1", bought.translateTitle(game))
                    .replace("\\2", replaced.blueprint.translateTitle(game))
                return Pair("confirm_buy_custom", text)
            }
            val limit = if (isSub) subsystemLimit else systemLimit
            // The original keeps its own behaviour for subsystems when all four fit and no custom ones exist.
            if (isSub && limit >= 4) return null
            val count = ship.systems.count { (it.blueprint.info?.isSubSystem == true) == isSub }
            if (limit - count == 1) return Pair(if (isSub) "confirm_buy_last_subsystem" else "confirm_buy_last_system", null)
            return null
        }

        override fun buy() {
            box.count = 0
            when (box.category) {
                0, 1, 2 -> if (!ship.addBlueprint(box.blueprint!!, false)) error("Couldn't find space to place purchased item!")
                3 -> { ship.addCrewMember(box.crew!!, false); game.gameMap.noteBlueprintCrew(game, box.crew!!) }
                else -> {
                    val slot = systemSlot!!
                    xyz.znix.xftl.hs.systems.HsSystemInstall.install(ship, slot)
                    slot.room.system?.let { ship.browserPowerNewSystem(it) }
                    box.freeDrone?.let { name ->
                        (game.blueprintManager.getOrNull(name) as? xyz.znix.xftl.weapons.DroneBlueprint)?.let { ship.addBlueprint(it, false) }
                    }
                }
            }
            // StoreComplete::MouseClick: a section item bought counts towards the purchase limit.
            hs.itemsPurchased++
            updateButtons()
        }

        override fun draw(g: Graphics) {
            if (box.category == 3 && !empty) { drawCrewBox(); return }
            if (box.category == 2 && !empty) { drawAugmentBox(g); return }
            if (!box.mysteryItem || empty) { super.draw(g); return }
            val img = when { disabled -> image.off; hovered -> image.hover; else -> image.normal }
            img.draw(pos)
            if (box.category == 0 || box.category == 1) {
                // A mystery item's name and icon are "?" (font 24 centred above the icon's centre).
                crewNameFont.drawStringCentred(pos.x + 11f, pos.y + 70f, 96f, "?", textColour)
                font24.printCenter(pos.x + 60f, pos.y + 34f - 26f, "?", Constants.SECTOR_CUTOUT_TEXT)
            } else {
                font14.print(pos.x + 48f, pos.y + 2f, "?", textColour)
            }
            numberFont.drawString(pos.x + priceOffset.x.f, pos.y + priceOffset.y.f, price.toString(), textColour)
        }

        private val augmentTooltip = object : xyz.znix.xftl.rendering.StandardTooltip(game) {
            override fun getText(): String {
                // AugmentStoreBox::MouseMove (CustomEquipment.cpp): "have_augment" for a non-stackable duplicate, else
                // "max_augments" (the text's backslash-1 and the literal 3 become the ship's augment slots).
                val aug = box.blueprint as? xyz.znix.xftl.augments.AugmentBlueprint
                if (aug != null && !aug.stackable && ship.augments.contains(aug)) return game.translator["have_augment"]
                val slots = ship.augmentSlotCount
                return xyz.znix.xftl.hs.ship.HsEquipment.maxAugmentsText(game.translator["max_augments"], ship.type.name).replace("\\1", slots.toString())
            }
        }

        private fun drawAugmentBox(g: Graphics) {
            val img = when { disabled -> image.off; hovered -> image.hover; else -> image.normal }
            img.draw(pos)
            // Hovering a button the ship cannot take (full augments or a duplicate) explains why.
            if (hovered && box.count >= 1 && !box.mysteryItem && customDisabled) g.tooltip = augmentTooltip
            val name = if (box.mysteryItem) "?" else (box.blueprint as xyz.znix.xftl.augments.AugmentBlueprint).translateTitle(game)
            augmentNameFont.drawString(pos.x + 48f, pos.y + 26f, name, textColour)
            // StoreBox::OnRender (1.6.14 0x51f4b0): the price whether or not the ship can take the augment.
            numberFont.drawString(pos.x + priceOffset.x.f, pos.y + priceOffset.y.f, price.toString(), textColour)
        }

        private fun drawCrewBox() {
            val crew = box.crew ?: return
            val img = when { disabled -> image.off; hovered -> image.hover; else -> image.normal }
            img.draw(pos.x - 9, pos.y - 9)
            crewNameFont.drawStringCentred(pos.x + 2f, pos.y + 61f, 96f, if (box.mysteryItem) "?" else crew.name, textColour)
            if (!box.mysteryItem) {
                val yOffset = when (crew.race.name) { "rock" -> -6; "mantis", "crystal" -> -10; else -> -5 }
                // A race whose art the engine cannot resolve yet (HS animBase: statcrew) is reported, not fatal.
                try {
                    crew.drawPortrait(pos.x + (96 - 35 * 2) / 2 + 3, pos.y + yOffset, 2f)
                } catch (e: Exception) {
                    xyz.znix.xftl.hs.HS.unsupported("crew", "animations", "store portrait of ${crew.race.name}: ${e.message}")
                }
            }
            numberFont.drawString(pos.x + priceOffset.x.f, pos.y + priceOffset.y.f, price.toString(), textColour)
        }

        override fun drawInfoPanel(g: Graphics) {
            // StoreComplete::MouseMove: no info box for a mystery item.
            if (empty || box.mysteryItem) return
            when (val bp = box.blueprint) {
                is xyz.znix.xftl.weapons.AbstractWeaponBlueprint -> infoPanel.drawWeapon(bp)
                is xyz.znix.xftl.weapons.DroneBlueprint -> infoPanel.drawDrone(bp)
                is xyz.znix.xftl.augments.AugmentBlueprint -> infoPanel.drawAugment(bp)
                is xyz.znix.xftl.systems.SystemBlueprint -> { infoPanel.drawDescriptionBoxSystem(bp); infoPanel.drawSystemPowerBox(g, bp, bp.startPower, 0) }
                else -> box.crew?.let {
                    // A race whose art the engine cannot resolve yet (HS <animSheet>: statcrew) is reported, not fatal.
                    try {
                        // Hyperspace's custom store sets its crew InfoBox without the original's 40 px lift (oracle
                        // ps-w3-mv / ps-w3-hs i-h-crew1: from y 159; the crew screen's from 119)
                        infoPanel.drawCrew(g, it, nativeLift = false)
                    } catch (e: Exception) {
                        xyz.znix.xftl.hs.HS.unsupported("crew", "animations", "store info of ${it.race.name}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun drawSell(g: Graphics) {
        buyTabButton.draw(g)
        sellImage.draw(position)

        sellPanel.draw(g)

        // Draw the information for the currently-hovered blueprint.
        ShipWindow.drawEquipmentInfo(infoPanel, sellPanel)
        if (TouchMode.enabled) sellPanel.drawTouchSell(g, infoPanel)
    }

    override fun escapePressed() {
        // The original's store (1.6.14 ps-w3-van w3v-confirm c-after-escape, HS oracle ps-w3-esc x-escape): Escape over
        // the confirm closes the whole store, nothing bought.
        hsConfirm = null
        close()
    }

    /** Hyperspace StoreComplete::KeyDown (custom stores): the left and right arrow keys turn the buy pages. */
    override fun onTextInput(key: Int, c: Char): Boolean {
        // The key reaches the confirm first; Escape over the confirm closes the store (escapePressed).
        if (key == Input.KEY_ESCAPE && hsConfirm != null) { escapePressed(); return true }
        hsConfirm?.let { return it.onTextInput(key, c) }
        val hs = hsStore ?: return false
        when (key) {
            Input.KEY_RIGHT -> hs.nextPage()
            Input.KEY_LEFT -> hs.previousPage()
            else -> return false
        }
        if (!sellTab) updateButtons()
        return true
    }

    override fun clickTargetAt(x: Int, y: Int): Any? {
        super.clickTargetAt(x, y)?.let { return it }

        // The sell tab's items live in the sell panel's own button list.
        if (sellTab)
            return sellPanel.buttonAt(x, y)
        return null
    }

    /** What a finger taps (TouchRouter): the confirmation's buttons, Buy, an item, a tab; items in the sell tab drag. */
    override fun liftTarget(x: Int, y: Int): Any? {
        val p = scaleWindowPoint(x, y)
        hsConfirm?.let { return it.liftTarget(p.x, p.y) }
        if (touchBuy?.hits(x, y) == true) return touchBuy
        super.liftTarget(x, y)?.let { return it }
        if (sellTab) return sellPanel.buttonAt(p.x, p.y)?.takeIf { it.touchLift }
        return null
    }

    /** The item under the finger, or none: see [touchSelected]. */
    private fun touchPick(x: Int, y: Int) {
        if (touchPressedBuy) return
        if (touchBuy?.disabled == false && touchSelected != null && x - TouchMode.hudX >= TOUCH_INFO_X) return
        touchSelected = buttons.lastOrNull { it is BuyButton && it.touchSelectable && it.contains(x, y) } as BuyButton?
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        hsConfirm?.let { val p = scaleWindowPoint(x, y); it.mouseClick(button, p.x, p.y); return }
        if (touchBuy != null) {
            if (button != Input.MOUSE_LEFT_BUTTON) return
            touchFingerDown = true
            touchPressedBuy = false
            if (touchBuy.press(x, y)) {
                touchPressedBuy = true
                return
            }
            for (btn in ArrayList(buttons)) if (btn !is BuyButton) btn.mouseDown(button, x, y)
            if (sellTab) sellPanel.mouseClick(button, x, y) else touchPick(x, y)
            return
        }
        if (!tapArmGate(button, x, y))
            return

        super.mouseClick(button, x, y)

        if (sellTab) {
            // The sell panel's buttons/blocks are in window coordinates
            // (identity when renderScale is 1); super converts for the
            // window's own buttons itself.
            val p = scaleWindowPoint(x, y)
            sellPanel.mouseClick(button, p.x, p.y)
        }
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON) {
            touchFingerDown = false
            touchPressedBuy = false
        }
        super.mouseReleased(button, x, y)

        if (sellTab) {
            val p = scaleWindowPoint(x, y)
            sellPanel.mouseReleased(button, p.x, p.y)
        }
    }

    private var hsMouse = ConstPoint(-1, -1)
    private val hsLimitTooltip by lazy { xyz.znix.xftl.rendering.FixedStandardTooltip(game, "tooltip_purchase_limit") }

    override fun updateUI(x: Int, y: Int) {
        hsConfirm?.let { val p = scaleWindowPoint(x, y); it.updateUI(p.x, p.y); return }
        if (touchBuy != null) {
            touchUpdate(touchBuy, x, y)
            return
        }
        super.updateUI(x, y)
        if (hsStore != null) scaleWindowPoint(x, y).let { hsMouse = ConstPoint(it.x, it.y) }

        if (sellTab) {
            val p = scaleWindowPoint(x, y)
            sellPanel.updateUI(p.x, p.y)
        }
    }

    private fun touchUpdate(buy: TouchGetBox, x: Int, y: Int) {
        if (hsStore != null) hsMouse = ConstPoint(x, y)
        if (touchFingerDown && !sellTab) touchPick(x, y)

        // A sold-out item, or one on a page or tab no longer shown
        touchSelected?.let { if (it !in buttons || !it.touchSelectable) touchSelected = null }

        val selected = touchSelected
        for (btn in buttons) {
            when {
                btn !is BuyButton -> btn.update(x, y)
                btn === selected -> btn.update(btn.pos.x + 1, btn.pos.y + 1)
                else -> btn.update(-1, -1)
            }
        }
        buy.updateHover(x, y)

        if (sellTab) sellPanel.updateUI(x, y)
    }

    override fun shipModified() {
        super.shipModified()
        sellPanel.shipModified()
    }

    override fun positionUpdated() {
        super.positionUpdated()
        // The shared InfoBox sits at (942,159), as it does for the ship windows.
        infoPanel.position = position + if (TouchMode.enabled) ConstPoint(603, 50) else ConstPoint(size.x + 20, 74)
    }

    abstract inner class BuyButton(pos: IPoint, images: ButtonImageSet, val priceOffset: IPoint) :
        Buttons.BlueprintButton(pos, game, images) {

        abstract val price: Int

        abstract fun buy()

        /** Touch layout: whether a finger can select this. An item the ship can't take still shows its info. */
        open val touchSelectable: Boolean get() = !empty

        /** Touch layout: the BUY button's press, with the checks and confirmations of a click. */
        fun touchBuy() = click(Input.MOUSE_LEFT_BUTTON)

        override fun draw(g: Graphics) {
            super.draw(g)

            // Don't draw the price text if we've already sold it
            // (or in the case of resources, sold out).
            if (empty)
                return

            numberFont.drawString(
                pos.x + priceOffset.x.f,
                pos.y + priceOffset.y.f,
                price.toString(),
                textColour
            )
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            if (ship.scrap < price) {
                game.shipUI.playInsufficientScrapAnimation()
                return
            }
            ship.scrap -= price

            // Hull repairs play repairShip, everything else buy.
            if (this is RepairButton) repairSound.play() else buySound.play()

            buy()
            game.browserAchievements.record(if (this is RepairButton) ftl.browser.BrowserAchievementProgress.Action.STORE_REPAIR else ftl.browser.BrowserAchievementProgress.Action.STORE_PURCHASE)
        }

        abstract fun drawInfoPanel(g: Graphics)
    }

    inner class ResourceButton(pos: IPoint, val resource: Resource) : BuyButton(
        pos,
        ButtonImageSet.select2(game, "img/storeUI/store_items_${resourceTextureName(resource)}"),
        ConstPoint(169, 40)
    ) {
        override val blueprint = resource.getBlueprint(game)!!

        val numAvailable: Int get() = store.availableResources[resource] ?: 0

        override val disabled: Boolean get() = numAvailable == 0
        override val touchSelectable: Boolean get() = numAvailable > 0

        override val price: Int get() = blueprint.cost

        override fun draw(g: Graphics) {
            val img = when {
                numAvailable == 0 -> image.off
                hovered -> image.hover
                else -> image.normal
            }
            img.draw(pos)

            // Don't draw the available quantity if we've sold out.
            if (numAvailable == 0)
                return

            val textColour = when {
                hovered -> Constants.STORE_BUY_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }

            numberFont.drawString(pos.x + 130f, pos.y + 26f, price.toString(), textColour)

            numberFont.drawString(pos.x + 70f, pos.y + 26f, numAvailable.toString(), textColour)
        }

        override fun buy() {
            if (numAvailable == 0)
                return

            val resourceSet = ResourceSet()
            resourceSet[resource] = 1
            game.givePlayerResources(resourceSet)

            store.availableResources[resource] = numAvailable - 1
        }

        override fun drawInfoPanel(g: Graphics) {
            if (numAvailable == 0)
                return

            infoPanel.drawItem(blueprint)
        }
    }

    private abstract inner class RepairButton(pos: IPoint, val repairImg: ButtonImageSet, priceOffset: IPoint) :
        BuyButton(pos, repairImg, priceOffset) {

        override val customDisabled: Boolean get() = ship.health >= ship.maxHealth
        override val blueprint: Blueprint get() = error("Repairs don't use blueprints")
        override val empty: Boolean get() = false

        abstract val nameKey: String

        override fun draw(g: Graphics) {
            val image = when {
                disabled -> repairImg.off
                hovered -> repairImg.hover
                else -> repairImg.normal
            }
            image.draw(this.pos)

            val ourTextColour = when {
                disabled -> Constants.JUMP_DISABLED
                hovered -> Constants.STORE_BUY_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }

            // RepairStoreBox::OnRender (0x00521460): font 59 centred at x=52 and x=133,
            // vertically about y=21.
            val label = game.translator[nameKey]
            repairLabelFont.printCenter(pos.x + 52f, pos.y + 21f - labelHeight(label) / 2, label, ourTextColour)

            if (!disabled) {
                numberFont.drawString(
                    pos.x + priceOffset.x.f,
                    pos.y + priceOffset.y.f,
                    price.toString(),
                    ourTextColour
                )
            } else {
                val max = game.translator["repair_max"]
                repairLabelFont.printCenter(pos.x + 133f, pos.y + 21f - labelHeight(max) / 2, max, ourTextColour)
            }
        }

        override fun drawInfoPanel(g: Graphics) {
            // No message
        }

        private fun labelHeight(text: String) = (text.count { it == '\n' } + 1) * repairLabelFont.lineHeight
    }

    companion object {
        // The engine's virtual canvas is fixed at 1280x720; the touch
        // sell-box placement below is computed against its height.
        const val CANVAS_H: Int = 720

        const val GLOW_WIDTH: Int = 7

        /** Touch layout: the info column's left edge in the HUD block. */
        private const val TOUCH_INFO_X = 643

        private fun resourceTextureName(resource: Resource): String {
            return when (resource) {
                Resource.FUEL -> "fuel"
                Resource.MISSILES -> "missiles"
                Resource.DRONES -> "drones"
                Resource.SCRAP -> error("Can't buy resource $resource")
            }
        }
    }
}
